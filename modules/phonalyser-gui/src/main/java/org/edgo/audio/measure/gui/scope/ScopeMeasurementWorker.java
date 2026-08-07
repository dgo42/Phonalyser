/*
 * Phonalyser - precision audio measurement workbench.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.edgo.audio.measure.gui.scope;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.edgo.audio.measure.dsp.MainsFilters;
import org.edgo.audio.measure.dsp.MainsTimeFilter;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.MainsSuppression;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.sound.SignalBufferReader;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.CaptureEndReason;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;

/**
 * Background measurement worker for {@link ScopeView}.  Owns the
 * compute thread, the latest {@link SignalMeasurements} snapshot, the
 * rolling history ring (for avg / min / max / σ stats), and the
 * per-channel DC mean used by the AC-coupling display.
 *
 * <p>Paint code in {@link ScopeView} reads worker state via
 * accessors and via {@link #walkRecentHistory} / {@link #averagedChannelMean}
 * - no direct field access, no shared lock with the view.
 */
@Log4j2
public final class ScopeMeasurementWorker {

    /** Period between worker passes - 100 ms = 10 Hz. */
    private static final long MEAS_COMPUTE_PERIOD_NANOS = 100_000_000L;
    /** Excluded prefix of the captured buffer to dodge ADC startup transient. */
    private static final long AC_WARMUP_NANOS = 500_000L;
    /** Cap on samples used per pass - 96 000 ≈ 1 s @ 96 kHz / ¼ s @ 384 kHz.
     *  Exposed so {@link ScopeView}'s paint code can size its
     *  trigger-search lookback window to match this worker's read window
     *  (and not waste samples it'll never reach in the same paint). */
    public static final int  MEAS_MAX_SAMPLES = 96_000;
    /** Depth of the rolling history ring. */
    private static final int  MEAS_HISTORY_CAP = 1024;
    /** Notch −3 dB width (Hz) - matches the display-side combs. */
    private static final double MAINS_NOTCH_BW_HZ = 2.0;
    /** Half-width (Hz) of the raw-signal band used to re-pin the comb-located
     *  tone's frequency.  Wide enough to cover the comb's frequency pull, far
     *  narrower than the ≥ ~50 Hz spacing of mains harmonics so none competes. */
    private static final double FREQ_REFINE_HALF_HZ = 2.0;

    private volatile SignalBufferReader reader;

    /** "Informed from below" seam: called ON THE WORKER THREAD, once per
     *  capture session, when the reader answers terminally (the capture
     *  writer finished the ring buffer - device lost / delivery stalled).
     *  Carries the CLAIMED reason, {@code null} when another consumer of the
     *  same dead capture already claimed the one operator report.  Wired by
     *  {@link ScopeView#attachController} to the controller, which owns the
     *  worker/UI border. */
    @Setter
    private volatile Consumer<CaptureEndReason> captureEndListener;

    private Thread          measThread;
    /** Per-session run flag - a fresh instance per {@link #start()}, captured
     *  by the loop, so a join-timed-out predecessor polling its own (stale)
     *  flag can never be revived by the next start. */
    private volatile AtomicBoolean measThreadRunning = new AtomicBoolean(false);

    /** Latest per-channel measurement snapshots.  The worker measures BOTH
     *  channels every pass; the view shows the one the table's L/R selector
     *  points at (see {@link #getLastMeasResult()}). */
    private volatile SignalMeasurements lastMeasLeft;
    private volatile SignalMeasurements lastMeasRight;
    @Getter
    private volatile double             lastLeftMeanNormalized;
    @Getter
    private volatile double             lastRightMeanNormalized;

    private float[] measLeftBuf;
    private float[] measRightBuf;
    /** Reusable tail-slice buffer for measuring the comb's settled region
     *  (channels are processed sequentially, so one buffer serves both). */
    private float[] measTailBuf;
    /** Reusable copy of a channel's raw (pre-comb) samples, for re-measuring
     *  the comb-located tone's frequency free of the comb's notch bias
     *  (sequential per-channel use, one buffer serves both). */
    private float[] rawChanBuf;

    /** Off-thread broad-band frequency scan.  A weak / noisy signal needs the
     *  costly per-bin Goertzel sweep to find its fundamental; running that on
     *  the measurement thread drops the cadence and throttles the whole table,
     *  so it runs here instead.  Only f / period lag - every other readout
     *  stays real-time.  {@link #freqScanBusy} coalesces overlapping requests. */
    private ExecutorService freqScanExec;
    private final AtomicBoolean freqScanBusy = new AtomicBoolean(false);
    private volatile double asyncFreqLeft  = Double.NaN;
    private volatile double asyncFreqRight = Double.NaN;
    private float[] freqScanBuf;

    /** Per-channel mains-hum combs for the measured values; lazily built
     *  for the capture rate, used when a channel's mains-suppression mode
     *  is IIR_COMB.  DC-preserving, so Vmean still reflects the true bias. */
    private MainsTimeFilter measLeft, measRight;
    private MainsSuppression measLeftMode  = MainsSuppression.NONE;
    private MainsSuppression measRightMode = MainsSuppression.NONE;
    private int             measCombSampleRate;
    /** Guards multi-field updates to the measurement history ring. */
    private final Object measHistoryLock = new Object();
    private final SignalMeasurements[] measHistoryLeft  = new SignalMeasurements[MEAS_HISTORY_CAP];
    private final SignalMeasurements[] measHistoryRight = new SignalMeasurements[MEAS_HISTORY_CAP];
    private final long[]               measHistoryTime  = new long[MEAS_HISTORY_CAP];
    private final double[]             meanHistoryLeftNorm  = new double[MEAS_HISTORY_CAP];
    private final double[]             meanHistoryRightNorm = new double[MEAS_HISTORY_CAP];
    private int measHistoryWrite;
    private int measHistorySize;

    /** Amplitude-occupancy accumulators, live only while the scope's histogram
     *  window is open.  Their range tracks the signal's own extremes rather than
     *  full scale, and is binned far finer than the drawn bars - see the
     *  accumulator for why.  Nothing a user does to V/div, a range switch or a
     *  recalibration invalidates the counts.  Worker-thread only. */
    private AmplitudeHistogram histLeft, histRight;
    /** Published for the paint thread - a snapshot, never the live accumulator. */
    private volatile AmplitudeHistogram histSnapLeft, histSnapRight;
    private volatile boolean histEnabled;
    /** Reset asked for by the UI, consumed at the top of the next worker pass -
     *  see {@link #resetHistograms()}. */
    private volatile boolean histResetRequested;
    /** Peak {@code |sample|} seen since the current ranging window opened, per
     *  channel, and when it opened - see {@link #binAmplitudes}.  Worker-thread only. */
    private double histWinPeakL;
    private double histWinPeakR;
    private long   histWinStartNanos;
    /** Absolute sample position already binned.  {@link SignalBufferReader#readLatest}
     *  hands back a whole window every pass, and consecutive windows OVERLAP by
     *  roughly 20× at the worker's cadence - without this cursor every sample
     *  would be counted about twenty times, and unevenly, which would bias the
     *  distribution towards whatever the overlap happened to cover. */
    private long histBinnedUpTo;

    // ─── External wiring ────────────────────────────────────────────────────

    public void setBuffer(SignalBufferReader r) { this.reader = r; }

    /** Starts / stops amplitude binning.  Off is the default and costs nothing:
     *  no accumulators exist and the pass skips the block entirely.  Turning it
     *  off drops the counts - the window owns the distribution's lifetime. */
    public void setHistogramEnabled(boolean on) {
        if (on == histEnabled) return;
        if (on) {
            // The preference sets the accumulation granularity here only.  The
            // painter re-reads it every frame, so changing it later re-draws the
            // counts already collected instead of discarding them.
            int bars = Math.max(2, Preferences.instance().getOscHistogramBins());
            histLeft  = new AmplitudeHistogram(bars);
            histRight = new AmplitudeHistogram(bars);
            histBinnedUpTo = 0;
        } else {
            histLeft  = null;
            histRight = null;
            histSnapLeft  = null;
            histSnapRight = null;
        }
        histEnabled = on;
    }

    /** Clears the accumulated distribution.  Driven from the histogram window's own
     *  reset button, and alongside the measurement statistics whenever those are
     *  cleared - the same events invalidate both, so the two restart together.
     *
     *  <p>Called from the UI thread (the histogram window's reset button).  While
     *  the worker is running the clear is REQUESTED and performed by the worker
     *  itself, so it can never land in the middle of an {@code add()}; the request
     *  is consumed at the top of the next pass, one 100&nbsp;ms cadence away at
     *  worst.  With the worker stopped nothing can be mutating the accumulators,
     *  so the clear happens straight away rather than waiting for a pass that is
     *  not coming. */
    public void resetHistograms() {
        if (measThreadRunning.get()) {
            histResetRequested = true;
        } else {
            applyHistogramReset();
        }
    }

    /** The clear itself.  Worker thread, or any thread while the worker is stopped. */
    private void applyHistogramReset() {
        AmplitudeHistogram l = histLeft;
        AmplitudeHistogram r = histRight;
        if (l != null) l.reset();
        if (r != null) r.reset();
        histSnapLeft   = (l != null) ? l.snapshot() : null;
        histSnapRight  = (r != null) ? r.snapshot() : null;
        histBinnedUpTo = 0;
        // Open a fresh ranging window too, so a reset re-ranges onto the signal as
        // it is NOW instead of inheriting the peak that built the old range.
        histWinPeakL      = 0.0;
        histWinPeakR      = 0.0;
        histWinStartNanos = 0L;
    }

    /** The paint thread's view of a channel's distribution - a snapshot, so it
     *  never walks an array the worker is mutating.  {@code null} until the first
     *  pass has binned something. */
    public AmplitudeHistogram getHistogram(Channel ch) {
        return (ch == Channel.L) ? histSnapLeft : histSnapRight;
    }

    /** Wipes accumulated history and the latest-result fields.  Safe to
     *  call from any thread; takes the history lock internally. */
    public void clearHistory() {
        synchronized (measHistoryLock) {
            measHistoryWrite = 0;
            measHistorySize  = 0;
            // Null every slot so stale SignalMeasurements references are
            // released for GC immediately on reset (up to 1024 instances
            // would otherwise stay reachable until overwritten).
            Arrays.fill(measHistoryLeft,  null);
            Arrays.fill(measHistoryRight, null);
        }
    }

    /** Drops the latest-result fields.  Companion to {@link #clearHistory}
     *  for setBuffer(null) / pause paths that want a "no current value"
     *  state without losing the ring. */
    public void clearLatest() {
        lastMeasLeft             = null;
        lastMeasRight            = null;
        lastLeftMeanNormalized   = 0;
        lastRightMeanNormalized  = 0;
    }

    // ─── Lifecycle ──────────────────────────────────────────────────────────

    /**
     * Starts the worker thread.  Self-heals against a half-stopped
     * predecessor (switching from record -> play-from-file once left
     * {@code measThreadRunning == true} from a dying thread).
     */
    public synchronized void start() {
        if (measThreadRunning.get() || measThread != null) {
            stop();
        }
        AtomicBoolean session = new AtomicBoolean(true);
        measThreadRunning = session;
        // Clear stale state so the first paint after start doesn't show
        // measurements from the previous session.
        lastMeasLeft   = null;
        lastMeasRight  = null;
        asyncFreqLeft  = Double.NaN;
        asyncFreqRight = Double.NaN;
        clearHistory();
        if (freqScanExec == null) {
            freqScanExec = Executors.newSingleThreadExecutor(r -> {
                Thread th = new Thread(r, "osc-freq-scan");
                th.setDaemon(true);
                return th;
            });
        }
        Thread t = new Thread(() -> measurementLoop(session), "osc-measurement");
        t.setDaemon(true);
        t.start();
        measThread = t;
    }

    /** Stops the worker and waits up to 2 s for it to exit.  Idempotent.
     *  Never joins itself: the capture-end reaction runs the stop ON the
     *  worker thread (the device half must stay off the display thread), and
     *  that thread exits its loop right after the reaction returns. */
    public synchronized void stop() {
        measThreadRunning.set(false);
        Thread t = measThread;
        if (t != null) {
            if (t != Thread.currentThread()) {
                try { t.join(2000); } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            measThread = null;
        }
    }

    // ─── Paint-side queries ─────────────────────────────────────────────────

    /** Latest measurement snapshot of one channel.  The worker measures BOTH
     *  channels every pass; the view shows the selected one. */
    public SignalMeasurements getLastMeasResult(boolean leftChannel) {
        return leftChannel ? lastMeasLeft : lastMeasRight;
    }

    /**
     * Walks one channel's history ring backwards from the newest entry,
     * stopping at the first one older than {@code cutoffNanos}.  Visitor is
     * invoked under the history lock - keep it short.  Use to aggregate
     * stats (avg / min / max / σ) over the user's selected averaging
     * window.
     */
    public void walkRecentHistory(boolean leftChannel, long cutoffNanos,
                                  Consumer<SignalMeasurements> visitor) {
        SignalMeasurements[] ring = leftChannel ? measHistoryLeft : measHistoryRight;
        synchronized (measHistoryLock) {
            for (int i = 0; i < measHistorySize; i++) {
                int idx = (measHistoryWrite - 1 - i + MEAS_HISTORY_CAP) % MEAS_HISTORY_CAP;
                if (measHistoryTime[idx] < cutoffNanos) break;
                visitor.accept(ring[idx]);
            }
        }
    }

    /**
     * Returns the time-windowed average of one channel's recent DC means.
     * Walks history backwards from the most recent worker tick until the
     * entry's timestamp falls outside {@code windowNanos} ago.  Falls
     * back to the latest single-tick value when the history doesn't span
     * the window (the first few hundred ms after capture starts).
     */
    public double averagedChannelMean(boolean leftChannel, long windowNanos) {
        long cutoff = System.nanoTime() - windowNanos;
        double sum = 0;
        int count = 0;
        synchronized (measHistoryLock) {
            for (int i = 0; i < measHistorySize; i++) {
                int idx = (measHistoryWrite - 1 - i + MEAS_HISTORY_CAP) % MEAS_HISTORY_CAP;
                if (measHistoryTime[idx] < cutoff) break;
                sum += leftChannel ? meanHistoryLeftNorm[idx] : meanHistoryRightNorm[idx];
                count++;
            }
        }
        if (count > 0) return sum / count;
        // History too short for the requested window - use the latest snapshot
        // rather than 0 so AC removal isn't suddenly off-zero for half a second.
        return leftChannel ? lastLeftMeanNormalized : lastRightMeanNormalized;
    }

    /** Arithmetic mean of {@code data[0..n)}.  Returns 0 for empty inputs.
     *  Exposed as {@code public static} so {@link ScopeView}'s
     *  paint code can fall back to a per-frame DC mean when the worker
     *  hasn't published a measurement yet. */
    public static double sampleMean(float[] data, int n) {
        if (data == null || n <= 0) return 0.0;
        double sum = 0;
        for (int i = 0; i < n; i++) sum += data[i];
        return sum / n;
    }

    // ─── Cross-instance snapshot (used by the screenshot pane) ──────────────

    /**
     * Atomically copies all measurement state from {@code other} into
     * this worker.  Used by the offscreen screenshot pane so its passive
     * (worker-less) OscilloscopeView still draws the measurement table
     * with the live values.  Two distinct locks - no nested deadlock
     * even if both workers were ever live in parallel.
     */
    public void snapshotFrom(ScopeMeasurementWorker other) {
        if (other == null || other == this) return;
        SignalMeasurements snapL = other.lastMeasLeft;
        SignalMeasurements snapR = other.lastMeasRight;
        double leftMeanSnap  = other.lastLeftMeanNormalized;
        double rightMeanSnap = other.lastRightMeanNormalized;
        synchronized (other.measHistoryLock) {
            int cap = MEAS_HISTORY_CAP;
            SignalMeasurements[] hl = new SignalMeasurements[cap];
            SignalMeasurements[] hr = new SignalMeasurements[cap];
            long[]               t  = new long[cap];
            double[]             ml = new double[cap];
            double[]             mr = new double[cap];
            System.arraycopy(other.measHistoryLeft,     0, hl,   0, cap);
            System.arraycopy(other.measHistoryRight,    0, hr,   0, cap);
            System.arraycopy(other.measHistoryTime,     0, t,    0, cap);
            System.arraycopy(other.meanHistoryLeftNorm, 0, ml,   0, cap);
            System.arraycopy(other.meanHistoryRightNorm,0, mr,   0, cap);
            int w = other.measHistoryWrite;
            int s = other.measHistorySize;
            synchronized (this.measHistoryLock) {
                System.arraycopy(hl,   0, this.measHistoryLeft,     0, cap);
                System.arraycopy(hr,   0, this.measHistoryRight,    0, cap);
                System.arraycopy(t,    0, this.measHistoryTime,     0, cap);
                System.arraycopy(ml,   0, this.meanHistoryLeftNorm, 0, cap);
                System.arraycopy(mr,   0, this.meanHistoryRightNorm,0, cap);
                this.measHistoryWrite = w;
                this.measHistorySize  = s;
            }
        }
        this.lastMeasLeft             = snapL;
        this.lastMeasRight            = snapR;
        this.lastLeftMeanNormalized   = leftMeanSnap;
        this.lastRightMeanNormalized  = rightMeanSnap;
    }

    // ─── Worker loop ────────────────────────────────────────────────────────

    /**
     * Measurement worker loop: sleeps for {@link #MEAS_COMPUTE_PERIOD_NANOS}
     * between probes, reads the latest samples from the {@link SignalBufferReader},
     * runs {@link SignalMeasurements#from}, and stores the result for
     * the SWT thread to pick up at next paint.  Runs at fixed cadence
     * (drift-compensated) so the avg / min / max / σ stats are evenly
     * spaced even if a single compute occasionally overruns the period.
     */
    private void measurementLoop(AtomicBoolean sessionRunning) {
        long nextWake = System.nanoTime() + MEAS_COMPUTE_PERIOD_NANOS;
        while (sessionRunning.get()) {
            long now = System.nanoTime();
            long sleepNs = nextWake - now;
            if (sleepNs > 0) {
                try {
                    Thread.sleep(sleepNs / 1_000_000L, (int) (sleepNs % 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (!sessionRunning.get()) return;
            try {
                computeMeasurementOnce();
            } catch (RuntimeException ex) {
                log.warn("measurement loop iteration failed: {}", ex.toString());
            }
            nextWake += MEAS_COMPUTE_PERIOD_NANOS;
            // Drift compensation: if a compute overran badly, snap forward so
            // we don't busy-loop trying to catch up.
            long lag = System.nanoTime() - nextWake;
            if (lag > MEAS_COMPUTE_PERIOD_NANOS) {
                nextWake = System.nanoTime() + MEAS_COMPUTE_PERIOD_NANOS;
            }
        }
    }

    /** Lazily (re)builds the per-channel measurement combs for the given
     *  sample rate.  Worker-thread only. */
    private MainsTimeFilter measFilter(boolean left, MainsSuppression mode, int sampleRate) {
        if (measCombSampleRate != sampleRate) {
            measLeft = measRight = null;
            measLeftMode = measRightMode = MainsSuppression.NONE;
            measCombSampleRate = sampleRate;
        }
        if (left) {
            if (measLeft == null || measLeftMode != mode) {
                measLeft = MainsFilters.of(mode, sampleRate, MAINS_NOTCH_BW_HZ);
                measLeftMode = mode;
            }
            return measLeft;
        }
        if (measRight == null || measRightMode != mode) {
            measRight = MainsFilters.of(mode, sampleRate, MAINS_NOTCH_BW_HZ);
            measRightMode = mode;
        }
        return measRight;
    }

    /**
     * Bins the part of this pass's window that has not been binned before, into
     * both channels' accumulators, and publishes fresh snapshots.
     *
     * <p>Samples are counted EXACTLY AS CAPTURED - no DC removal, no filtering.
     * The {@code skip} below drops the overlap with the previous window so nothing
     * is counted twice, and that is the only thing done to them.  A DC offset is
     * part of the signal and belongs in the picture: the distribution simply sits
     * off the centre line by however much offset there is.  Subtracting the running
     * mean instead would be worse than useless here, because that estimate is still
     * settling in the first seconds - the same voltage would land in different bins
     * as it moved, smearing one distribution into two.
     *
     * <p>If the worker misses passes under load the unread samples are simply
     * never binned.  That is an unbiased sub-sample and is fine for a
     * distribution - but it does mean the total is not a count of captured
     * samples and must not be presented as one.  Worker-thread only.
     */
    private void binAmplitudes(int avail, long absStart) {
        AmplitudeHistogram l = histLeft;
        AmplitudeHistogram r = histRight;
        if (l == null || r == null) return;
        // A frozen / re-anchored buffer can hand back a window that starts before
        // what we already binned; clamp rather than re-count it.
        long already = histBinnedUpTo - absStart;
        // A cursor sitting BEYOND the stream's write position can only mean the
        // positions restarted under us: every capture session (and every freeze
        // snapshot) builds a fresh ring numbered from 0.  Re-anchor on this window
        // and bin from the next pass - without this, binning would silently stop for
        // as long as the previous session ran, until the new one caught up.
        if (already > avail) {
            histBinnedUpTo = absStart + avail;
            return;
        }
        int skip = (int) Math.max(0, Math.min(avail, already));
        double peakL = blockPeak(measLeftBuf,  skip, avail);
        double peakR = blockPeak(measRightBuf, skip, avail);
        // Range on the peak gathered over the scope's MEASUREMENT-AVERAGE window,
        // not on this 100 ms block.  A block's peak is a random draw from the
        // signal's tail, so on anything noisy it wanders enough to escape the range
        // nearly every pass - and every escape restarts the distribution.  Over the
        // averaging window it is the peak of some fifty blocks and barely moves, so
        // a restart now means the level genuinely changed.
        long now = System.nanoTime();
        long windowNanos = (long) (Preferences.instance().getOscMeasurementAverageSeconds() * 1e9);
        if (now - histWinStartNanos >= windowNanos) {
            // Start the next window from this block rather than carrying the old
            // peak, so a signal that has quietened is reflected within one window.
            // Shrinking never re-ranges, so this cannot cost any counts.
            histWinPeakL      = 0.0;
            histWinPeakR      = 0.0;
            histWinStartNanos = now;
        }
        if (peakL >= 0.0) {
            if (peakL > histWinPeakL) histWinPeakL = peakL;
            l.fit(-histWinPeakL, histWinPeakL);
            for (int i = skip; i < avail; i++) l.add(measLeftBuf[i]);
        }
        if (peakR >= 0.0) {
            if (peakR > histWinPeakR) histWinPeakR = peakR;
            r.fit(-histWinPeakR, histWinPeakR);
            for (int i = skip; i < avail; i++) r.add(measRightBuf[i]);
        }
        histBinnedUpTo = absStart + avail;
        histSnapLeft  = l.snapshot();
        histSnapRight = r.snapshot();
    }

    /**
     * Largest {@code |sample|} in the block, on the samples exactly as captured.
     * A magnitude is all the accumulator needs because its range is symmetric about
     * zero - 0&nbsp;V is the axis centre - and taking it on the raw samples means a
     * DC-offset signal is ranged to reach the rail it actually reaches.
     *
     * @return {@code -1} when the block holds nothing finite, in which case there
     *         is nothing to bin either
     */
    private double blockPeak(float[] buf, int from, int to) {
        double peak = -1.0;
        for (int i = from; i < to; i++) {
            double v = Math.abs(buf[i]);
            if (Double.isFinite(v) && v > peak) peak = v;
        }
        return peak;
    }

    /** Runs one measurement pass on the worker thread and updates the cache. */
    private void computeMeasurementOnce() {
        // Before the reader check on purpose: the loop keeps ticking with no capture
        // attached, so a reset asked for while stopped still lands.
        if (histResetRequested) {
            histResetRequested = false;
            applyHistogramReset();
        }
        SignalBufferReader b = reader;
        if (b == null) return;
        // Reader-terminal consult: the capture writer finished the ring buffer
        // from below.  Measuring a dead buffer is a measurement of nothing, so
        // stop this session and inform upward - the listener (the controller)
        // runs the device half of the stop RIGHT HERE on the worker thread
        // (it must stay off the display thread) and marshals the visuals.
        if (b.isFinished()) {
            measThreadRunning.set(false);
            Consumer<CaptureEndReason> listener = captureEndListener;
            if (listener != null) listener.accept(b.takeFinishedReasonForReport());
            return;
        }
        Preferences prefs = Preferences.instance();
        int sampleRate = b.getSampleRate();
        // Exclude the first AC_WARMUP_NANOS of captured samples from every
        // read - those contain the ADC's startup transient and would bias
        // the published DC mean (which then biases the AC-mode trace's
        // history-averaged DC subtraction).
        long warmupSamples   = (long) sampleRate * AC_WARMUP_NANOS / 1_000_000_000L;
        long postWarmupCount = b.getWritePos() - warmupSamples;
        if (postWarmupCount < 64) return;
        int measN = (int) Math.min(postWarmupCount, MEAS_MAX_SAMPLES);
        if (measLeftBuf == null || measLeftBuf.length < measN) {
            measLeftBuf  = new float[measN];
            measRightBuf = new float[measN];
        }
        // Always read both channels - even when only one is the measurement
        // selection - so we can publish a fresh per-channel DC mean for the
        // paint thread's AC-mode trace offset and AC-mode trigger-level shift.
        int avail = b.readLatest(measN, measLeftBuf, measRightBuf);
        if (avail < 64) return;
        long absStart = b.getWritePos() - avail;
        // Amplitude binning goes FIRST, on the samples exactly as captured - before
        // the DC mean is removed, before the mains comb.  A distribution is a
        // statement about the signal that arrived, so anything the scope does to
        // make the TRACE readable would be a lie in it.  The DC mean in particular
        // is a running estimate that is still settling in the first seconds:
        // subtracting it would bin the same voltage into different bins as the
        // estimate moved, smearing one distribution into two.
        if (histEnabled) {
            binAmplitudes(avail, absStart);
        }
        // Nothing filters this window on the way to the table.  The per-channel HF
        // cleanup (80 kHz low-pass / de-spike) used to run here so the numbers
        // would match the drawn trace, and that was backwards: it is a DISPLAY
        // setting the operator picks to make a trace readable, and the moment it
        // moves Vpp, Vmean, Tr, Tf or Duty those stop being measurements of the
        // signal and become measurements of our rendering of it.  It also lied in
        // one direction only - the filter is reset per pass, so it was measured
        // ringing its way out of a step of up to the full peak-to-peak, which a
        // peak detector reads as signal (the bench saw Vpp 12 % high and rise times
        // a quarter long on a sine that was clean).  ScopeView still filters what
        // it draws; the same reasoning already governs the histogram above and the
        // frequency below.
        // Each channel scales by its OWN ADC full-scale (LINKED cards give equal L/R
        // peaks), so the RIGHT channel's Vpp/Vrms/Vmean no longer inherit the LEFT
        // full-scale; the back-conversion below divides each mean by its own peak.
        double peakVoltsL = prefs.getAdcPeakVolts(Channel.L);
        double peakVoltsR = prefs.getAdcPeakVolts(Channel.R);
        // Both channels run the SAME measurement pipeline; the view shows the
        // channel the table's L/R selector points at.  The per-channel DC
        // means (AC-coupling offset, residual baseline) are the channels' own
        // whole-period Vmean values - identical to the table readout.
        SignalMeasurements resultLeft  = measureChannel(true,  avail, sampleRate, peakVoltsL, absStart, prefs);
        SignalMeasurements resultRight = measureChannel(false, avail, sampleRate, peakVoltsR, absStart, prefs);
        double leftMean  = resultLeft.getVmean()  / peakVoltsL;
        double rightMean = resultRight.getVmean() / peakVoltsR;
        long now = System.nanoTime();
        synchronized (measHistoryLock) {
            measHistoryLeft [measHistoryWrite] = resultLeft;
            measHistoryRight[measHistoryWrite] = resultRight;
            measHistoryTime [measHistoryWrite] = now;
            meanHistoryLeftNorm [measHistoryWrite] = leftMean;
            meanHistoryRightNorm[measHistoryWrite] = rightMean;
            measHistoryWrite = (measHistoryWrite + 1) % MEAS_HISTORY_CAP;
            if (measHistorySize < MEAS_HISTORY_CAP) measHistorySize++;
        }
        lastLeftMeanNormalized  = leftMean;
        lastRightMeanNormalized = rightMean;
        lastMeasLeft  = resultLeft;
        lastMeasRight = resultRight;
    }

    /**
     * Runs the full measurement pipeline on one channel's captured buffer:
     * mains suppression (DC-preserving, with a raw copy kept for the two-step
     * frequency measurement), comb-settle tail trim, whole-period measurement,
     * weak-signal async frequency fallback, raw-band frequency re-pin, dual-tone
     * time-field clearing.  Channels are processed sequentially, so the raw/tail
     * scratch buffers are shared.
     *
     * <p>The buffer arrives exactly as captured - the operator's HF cleanup is a
     * property of the TRACE and is applied by the view, not here.  That also makes
     * the {@code raw} copy below what its name has always claimed: until the
     * low-pass was taken out of this path, the "raw" signal the frequency was
     * re-pinned on had itself been through an 8th-order Butterworth.
     *
     * <p>Two-step frequency: the comb suppresses an often-dominant mains so
     * the tone becomes the spectral peak (a reliable SEED), but its notches
     * sit at every mains harmonic and one can land within a few Hz of the
     * tone, and at high sample rates the comb never settles inside the
     * window - both pull the combed frequency low.  So the raw (un-combed)
     * copy is re-measured in a narrow band around the seed (the mains is
     * stronger there but its harmonics are tens of Hz away, outside the
     * band) - un-biased and free of the mains.
     */
    private SignalMeasurements measureChannel(boolean left, int avail, int sampleRate,
                                              double peakVolts, long absStart,
                                              Preferences prefs) {
        float[] buf = left ? measLeftBuf : measRightBuf;
        MainsSuppression mode = left ? prefs.getOscLeftMainsSuppression()
                                     : prefs.getOscRightMainsSuppression();
        float[] raw = null;
        if (mode != MainsSuppression.NONE) {
            if (rawChanBuf == null || rawChanBuf.length < avail) rawChanBuf = new float[avail];
            System.arraycopy(buf, 0, rawChanBuf, 0, avail);
            raw = rawChanBuf;
            MainsTimeFilter c = measFilter(left, mode, sampleRate);
            c.track(buf, avail);
            // Comb: zeroed delay lines each pass -> reset; adaptive filters keep state.
            if (mode == MainsSuppression.IIR_COMB) c.reset();
            c.processPreservingDc(buf, avail, absStart);
        }
        float[] data = buf;
        int measLen  = avail;
        if (mode == MainsSuppression.IIR_COMB) {
            // The comb's delay lines start zeroed each pass, so its head is an
            // un-suppressed pass-through that would skew Vpp/Vrms.  Measure the
            // settled tail instead (≈3 time-constants in; capped so at least
            // half the window remains - at very high sample rates the window
            // can be shorter than the settle time, leaving some residual hum).
            int settle = (int) (3.0 * sampleRate / (Math.PI * MAINS_NOTCH_BW_HZ));
            int from   = Math.min(settle, avail / 2);
            if (from > 0) {
                measLen = avail - from;
                if (measTailBuf == null || measTailBuf.length < measLen) measTailBuf = new float[measLen];
                System.arraycopy(data, from, measTailBuf, 0, measLen);
                data = measTailBuf;
            }
        }
        SignalMeasurements result = SignalMeasurements.from(data, measLen, sampleRate, peakVolts, false);
        if (Double.isNaN(result.getFrequency())) {
            // Weak / noisy signal: the broad-band fundamental search is too
            // costly for this thread - it would drop the 10 Hz cadence and
            // throttle the whole table.  Fold in the latest off-thread result
            // and kick a fresh scan; only f / period lag, the rest stays live.
            double async = left ? asyncFreqLeft : asyncFreqRight;
            if (!Double.isNaN(async)) result = result.withFrequency(async);
            submitFrequencyScan(left, data, measLen, sampleRate, peakVolts);
        }
        boolean dual = prefs.getGenSignalForm().isDualTone();
        if (!dual && raw != null && Double.isFinite(result.getFrequency())) {
            // Re-pin the comb-located tone on the raw signal, free of the
            // comb's notch bias, with a narrow band around the seed.  Skipped
            // in dual-tone mode: the single-value frequency is cleared by
            // withoutTimes() below, so re-pinning it would be wasted work - the
            // two dual-tone frequencies are measured separately just after.
            double precise = SignalMeasurements.refineFrequencyAround(
                    raw, avail, sampleRate, result.getFrequency(), FREQ_REFINE_HALF_HZ);
            if (Double.isFinite(precise)) result = result.withFrequency(precise);
        }
        // Dual-tone has two simultaneous fundamentals - a single Tp /
        // Tr / Tf / f / duty value has no physical meaning.  Clear
        // the time fields so the readout shows {@code ---} for every
        // time row instead of latching onto whichever spectral peak
        // happened to win the Goertzel search on this tick.  Vpp /
        // Vrms / Vmean stay intact since they're well-defined for
        // any signal mode.
        if (dual) {
            result = result.withoutTimes();
            // Measure BOTH tones as-captured for the scope's residual fit.  DAC
            // and ADC run on independent clocks with no FLL, so the generator's
            // commanded (FFT-bin-snapped) frequencies are exact only in the DAC
            // domain; in the ADC capture they are off by the clock ratio (ppm).
            // Over a fit window up to 65536 samples that error accrues phase the
            // least-squares fit can't absorb, leaving fundamental leakage.  So
            // seed from what the generator emits (mirroring ScopeView's source
            // exactly) and refine each seed on the raw signal to the AS-CAPTURED
            // frequency - the pre-comb copy when mains suppression is on (the
            // comb's notches would bias the tones), the channel buffer itself
            // otherwise (with suppression off it IS the raw signal, so the pair
            // is measurable in every configuration).  ±FREQ_REFINE_HALF_HZ
            // (2 Hz) covers ~100 ppm at 20 kHz, far above any real crystal
            // offset; the Hann window keeps the other tone - always tens of Hz
            // or more away - out of the narrow band.
            float[] src = raw != null ? raw : buf;
            double[] emitted = MessageBus.instance()
                .request(Events.GENERATOR_EMITTED_HZ, sampleRate);
            double r1 = Double.NaN;
            double r2 = Double.NaN;
            if (emitted != null) {
                double seed1 = emitted[0];
                double seed2 = emitted[1];
                if (seed1 > 0) {
                    double refined = SignalMeasurements.refineFrequencyAround(
                            src, avail, sampleRate, seed1, FREQ_REFINE_HALF_HZ);
                    if (Double.isFinite(refined)) r1 = refined;
                }
                if (seed2 > 0) {
                    double refined = SignalMeasurements.refineFrequencyAround(
                            src, avail, sampleRate, seed2, FREQ_REFINE_HALF_HZ);
                    if (Double.isFinite(refined)) r2 = refined;
                }
            }
            result = result.withDualTones(r1, r2);
        }
        return result;
    }

    /**
     * Copies the measured window and runs the broad-band fundamental search
     * on the {@code osc-freq-scan} thread, publishing the channel's async
     * frequency.  A scan already in flight is left to finish, so requests
     * never queue up - f / period just refresh at the scan's own (slower)
     * rate; when both channels are weak they take turns across ticks.
     */
    private void submitFrequencyScan(boolean left, float[] src, int n, int sampleRate,
                                     double peakVolts) {
        if (freqScanExec == null || !freqScanBusy.compareAndSet(false, true)) {
            return;
        }
        if (freqScanBuf == null || freqScanBuf.length < n) {
            freqScanBuf = new float[n];
        }
        System.arraycopy(src, 0, freqScanBuf, 0, n);
        float[] buf = freqScanBuf;
        freqScanExec.execute(() -> {
            try {
                double f = SignalMeasurements.from(buf, n, sampleRate, peakVolts, true).getFrequency();
                if (left) asyncFreqLeft = f; else asyncFreqRight = f;
            } finally {
                freqScanBusy.set(false);
            }
        });
    }
}
