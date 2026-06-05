package org.edgo.audio.measure.gui.scope;

import java.util.Arrays;
import java.util.function.Consumer;

import lombok.extern.log4j.Log4j2;

import org.edgo.audio.measure.dsp.LowPassFilter;
import org.edgo.audio.measure.dsp.MainsCombFilter;
import org.edgo.audio.measure.dsp.MedianFilter;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.enums.LpfMode;
import org.edgo.audio.measure.enums.MainsSuppression;
import org.edgo.audio.measure.gui.preferences.Preferences;
import org.edgo.audio.measure.gui.sound.SignalBufferReader;
import org.edgo.audio.measure.sound.AudioBackend;

/**
 * Background measurement worker for {@link OscilloscopeView}.  Owns the
 * compute thread, the latest {@link SignalMeasurements} snapshot, the
 * rolling history ring (for avg / min / max / σ stats), and the
 * per-channel DC mean used by the AC-coupling display.
 *
 * <p>Paint code in {@link OscilloscopeView} reads worker state via
 * accessors and via {@link #walkRecentHistory} / {@link #averagedChannelMean}
 * — no direct field access, no shared lock with the view.
 */
@Log4j2
public final class ScopeMeasurementWorker {

    /** Period between worker passes — 100 ms = 10 Hz. */
    private static final long MEAS_COMPUTE_PERIOD_NANOS = 100_000_000L;
    /** Excluded prefix of the captured buffer to dodge ADC startup transient. */
    private static final long AC_WARMUP_NANOS = 500_000L;
    /** Cap on samples used per pass — 96 000 ≈ 1 s @ 96 kHz / ¼ s @ 384 kHz.
     *  Exposed so {@link OscilloscopeView}'s paint code can size its
     *  trigger-search lookback window to match this worker's read window
     *  (and not waste samples it'll never reach in the same paint). */
    public static final int  MEAS_MAX_SAMPLES = 96_000;
    /** Depth of the rolling history ring. */
    private static final int  MEAS_HISTORY_CAP = 1024;
    /** Butterworth order for the scope HF spike-removal low-pass — shared
     *  with {@link OscilloscopeView} so display and measurement match.  The
     *  cutoff is per-channel and comes from the {@code LpfMode} preference. */
    public static final int    SCOPE_HF_LPF_ORDER = 8;

    private volatile SignalBufferReader reader;

    private Thread          measThread;
    private volatile boolean measThreadRunning;

    private volatile SignalMeasurements lastMeasResult;
    private volatile double             lastLeftMeanNormalized;
    private volatile double             lastRightMeanNormalized;

    private float[] measLeftBuf;
    private float[] measRightBuf;
    /** Reusable tail-slice buffer for measuring the comb's settled region. */
    private float[] measTailBuf;
    /** Reusable copy of the raw (pre-comb) selected channel, for re-measuring
     *  the comb-located tone's frequency free of the comb's notch bias. */
    private float[] rawSelBuf;

    /** Per-channel mains-hum combs for the measured values; lazily built
     *  for the capture rate, used when a channel's mains-suppression mode
     *  is IIR_COMB.  DC-preserving, so Vmean still reflects the true bias. */
    private MainsCombFilter measCombLeft, measCombRight;
    private int             measCombSampleRate;
    /** Per-channel HF low-pass for the measured values (matches the
     *  display-side filter so Vpp/Vrms aren't inflated by >80 kHz spikes). */
    private LowPassFilter   measLpfLeft, measLpfRight;
    private int             measLpfSampleRate;
    /** Per-channel median de-spike filters for the measured values. */
    private MedianFilter    measDespikeLeft, measDespikeRight;
    /** Notch −3 dB width (Hz) — matches the display-side combs. */
    private static final double MAINS_NOTCH_BW_HZ = 2.0;
    /** Half-width (Hz) of the raw-signal band used to re-pin the comb-located
     *  tone's frequency.  Wide enough to cover the comb's frequency pull, far
     *  narrower than the ≥ ~50 Hz spacing of mains harmonics so none competes. */
    private static final double FREQ_REFINE_HALF_HZ = 2.0;

    /** Guards multi-field updates to the measurement history ring. */
    private final Object measHistoryLock = new Object();
    private final SignalMeasurements[] measHistory     = new SignalMeasurements[MEAS_HISTORY_CAP];
    private final long[]               measHistoryTime = new long[MEAS_HISTORY_CAP];
    private final double[]             meanHistoryLeftNorm  = new double[MEAS_HISTORY_CAP];
    private final double[]             meanHistoryRightNorm = new double[MEAS_HISTORY_CAP];
    private int measHistoryWrite;
    private int measHistorySize;

    // ─── External wiring ────────────────────────────────────────────────────

    public void setBuffer(SignalBufferReader r) { this.reader = r; }
    public SignalMeasurements getLastMeasResult() { return lastMeasResult; }
    public double getLastLeftMeanNormalized()  { return lastLeftMeanNormalized; }
    public double getLastRightMeanNormalized() { return lastRightMeanNormalized; }

    /** Wipes accumulated history and the latest-result fields.  Safe to
     *  call from any thread; takes the history lock internally. */
    public void clearHistory() {
        synchronized (measHistoryLock) {
            measHistoryWrite = 0;
            measHistorySize  = 0;
            // Null every slot so stale SignalMeasurements references are
            // released for GC immediately on reset (up to 1024 instances
            // would otherwise stay reachable until overwritten).
            Arrays.fill(measHistory, null);
        }
    }

    /** Drops the latest-result fields.  Companion to {@link #clearHistory}
     *  for setBuffer(null) / pause paths that want a "no current value"
     *  state without losing the ring. */
    public void clearLatest() {
        lastMeasResult           = null;
        lastLeftMeanNormalized   = 0;
        lastRightMeanNormalized  = 0;
    }

    // ─── Lifecycle ──────────────────────────────────────────────────────────

    /**
     * Starts the worker thread.  Self-heals against a half-stopped
     * predecessor (switching from record → play-from-file once left
     * {@code measThreadRunning == true} from a dying thread).
     */
    public synchronized void start() {
        if (measThreadRunning || measThread != null) {
            stop();
        }
        measThreadRunning = true;
        // Clear stale state so the first paint after start doesn't show
        // measurements from the previous session.
        lastMeasResult = null;
        clearHistory();
        Thread t = new Thread(this::measurementLoop, "osc-measurement");
        t.setDaemon(true);
        t.start();
        measThread = t;
    }

    /** Stops the worker and waits up to 2 s for it to exit.  Idempotent. */
    public synchronized void stop() {
        measThreadRunning = false;
        Thread t = measThread;
        if (t != null) {
            try { t.join(2000); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            measThread = null;
        }
    }

    // ─── Paint-side queries ─────────────────────────────────────────────────

    /**
     * Walks the history ring backwards from the newest entry, stopping
     * at the first one older than {@code cutoffNanos}.  Visitor is
     * invoked under the history lock — keep it short.  Use to aggregate
     * stats (avg / min / max / σ) over the user's selected averaging
     * window.
     */
    public void walkRecentHistory(long cutoffNanos, Consumer<SignalMeasurements> visitor) {
        synchronized (measHistoryLock) {
            for (int i = 0; i < measHistorySize; i++) {
                int idx = (measHistoryWrite - 1 - i + MEAS_HISTORY_CAP) % MEAS_HISTORY_CAP;
                if (measHistoryTime[idx] < cutoffNanos) break;
                visitor.accept(measHistory[idx]);
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
        // History too short for the requested window — use the latest snapshot
        // rather than 0 so AC removal isn't suddenly off-zero for half a second.
        return leftChannel ? lastLeftMeanNormalized : lastRightMeanNormalized;
    }

    /** Arithmetic mean of {@code data[0..n)}.  Returns 0 for empty inputs.
     *  Exposed as {@code public static} so {@link OscilloscopeView}'s
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
     * with the live values.  Two distinct locks — no nested deadlock
     * even if both workers were ever live in parallel.
     */
    public void snapshotFrom(ScopeMeasurementWorker other) {
        if (other == null || other == this) return;
        SignalMeasurements snap = other.lastMeasResult;
        double leftMeanSnap  = other.lastLeftMeanNormalized;
        double rightMeanSnap = other.lastRightMeanNormalized;
        synchronized (other.measHistoryLock) {
            int cap = MEAS_HISTORY_CAP;
            SignalMeasurements[] hist = new SignalMeasurements[cap];
            long[]               t    = new long[cap];
            double[]             ml   = new double[cap];
            double[]             mr   = new double[cap];
            System.arraycopy(other.measHistory,         0, hist, 0, cap);
            System.arraycopy(other.measHistoryTime,     0, t,    0, cap);
            System.arraycopy(other.meanHistoryLeftNorm, 0, ml,   0, cap);
            System.arraycopy(other.meanHistoryRightNorm,0, mr,   0, cap);
            int w = other.measHistoryWrite;
            int s = other.measHistorySize;
            synchronized (this.measHistoryLock) {
                System.arraycopy(hist, 0, this.measHistory,         0, cap);
                System.arraycopy(t,    0, this.measHistoryTime,     0, cap);
                System.arraycopy(ml,   0, this.meanHistoryLeftNorm, 0, cap);
                System.arraycopy(mr,   0, this.meanHistoryRightNorm,0, cap);
                this.measHistoryWrite = w;
                this.measHistorySize  = s;
            }
        }
        this.lastMeasResult           = snap;
        this.lastLeftMeanNormalized   = leftMeanSnap;
        this.lastRightMeanNormalized  = rightMeanSnap;
    }

    // ─── Worker loop ────────────────────────────────────────────────────────

    /**
     * Measurement worker loop: sleeps for {@link #MEAS_COMPUTE_PERIOD_NANOS}
     * between probes, reads the latest samples from the {@link SignalBufferReader},
     * runs {@link SignalMeasurements#compute}, and stores the result for
     * the SWT thread to pick up at next paint.  Runs at fixed cadence
     * (drift-compensated) so the avg / min / max / σ stats are evenly
     * spaced even if a single compute occasionally overruns the period.
     */
    private void measurementLoop() {
        long nextWake = System.nanoTime() + MEAS_COMPUTE_PERIOD_NANOS;
        while (measThreadRunning) {
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
            if (!measThreadRunning) return;
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

    /** Applies the per-channel HF low-pass to both measurement buffers in
     *  place (rebuilt on a sample-rate change; reset each pass since reads
     *  overlap).  No-op below the LPF's Nyquist gate.  Worker-thread only. */
    private void applyHfLowPass(int sampleRate, int avail) {
        Preferences prefs = Preferences.instance();
        LpfMode lm = prefs.getOscLeftLpf();
        LpfMode rm = prefs.getOscRightLpf();
        if (lm == LpfMode.NONE && rm == LpfMode.NONE) return;
        if (measLpfSampleRate != sampleRate) {
            measLpfLeft = null; measLpfRight = null;
            measLpfSampleRate = sampleRate;
        }
        applyChannelHf(lm, true,  sampleRate, measLeftBuf,  avail);
        applyChannelHf(rm, false, sampleRate, measRightBuf, avail);
    }

    /** Applies one channel's selected HF cleanup ({@link LpfMode}) to the
     *  measurement buffer in place — matching the display path. */
    private void applyChannelHf(LpfMode mode, boolean left, int sampleRate, float[] buf, int len) {
        if (mode == LpfMode.HZ_80) {
            LowPassFilter f = left ? measLpfLeft : measLpfRight;
            if (f == null) {
                f = new LowPassFilter(sampleRate, mode.cutoffHz, SCOPE_HF_LPF_ORDER);
                if (left) measLpfLeft = f; else measLpfRight = f;
            }
            if (f.isActive()) { f.reset(); f.process(buf, len); }
        } else if (mode == LpfMode.DESPIKE) {
            MedianFilter m = left ? measDespikeLeft : measDespikeRight;
            if (m == null) {
                m = new MedianFilter(mode.window);
                if (left) measDespikeLeft = m; else measDespikeRight = m;
            }
            m.process(buf, len);
        }
    }

    /** Lazily (re)builds the per-channel measurement combs for the given
     *  sample rate.  Worker-thread only. */
    private MainsCombFilter measComb(boolean left, int sampleRate) {
        if (measCombSampleRate != sampleRate || measCombLeft == null) {
            measCombLeft  = new MainsCombFilter(sampleRate, MAINS_NOTCH_BW_HZ);
            measCombRight = new MainsCombFilter(sampleRate, MAINS_NOTCH_BW_HZ);
            measCombSampleRate = sampleRate;
        }
        return left ? measCombLeft : measCombRight;
    }

    /** Runs one measurement pass on the worker thread and updates the cache. */
    private void computeMeasurementOnce() {
        SignalBufferReader b = reader;
        if (b == null) return;
        Preferences prefs = Preferences.instance();
        Channel selected = prefs.getOscMeasurementChannel();
        int sampleRate = b.getSampleRate();
        // Exclude the first AC_WARMUP_NANOS of captured samples from every
        // read — those contain the ADC's startup transient and would bias
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
        // Always read both channels — even when only one is the measurement
        // selection — so we can publish a fresh per-channel DC mean for the
        // paint thread's AC-mode trace offset and AC-mode trigger-level shift.
        int avail = b.readLatest(measN, measLeftBuf, measRightBuf);
        if (avail < 64) return;
        // HF spike removal (80 kHz LPF) before everything else, so the DC
        // means, the comb, and Vpp/Vrms all see the de-spiked signal.  No-op
        // below the LPF's Nyquist gate.
        applyHfLowPass(sampleRate, avail);
        double peakVolts = AudioBackend.getAdcFsVoltageRms() * Math.sqrt(2.0);
        double leftMean  = sampleMean(measLeftBuf,  avail);
        double rightMean = sampleMean(measRightBuf, avail);
        // Mains suppression for the measured values (Vpp/Vrms/Vmean): the
        // raw per-channel means above are kept for AC-coupling display, but
        // the measurement reads the de-hummed signal.  DC-preserving so
        // Vmean stays meaningful.
        MainsSuppression leftMode  = prefs.getOscLeftMainsSuppression();
        MainsSuppression rightMode = prefs.getOscRightMainsSuppression();
        MainsSuppression selMode = (selected == Channel.L) ? leftMode : rightMode;
        // When the comb is on, the frequency is found in two steps to avoid the
        // comb biasing it: the comb suppresses an often-dominant mains so the
        // tone becomes the spectral peak (a reliable SEED), but its notches sit
        // at every mains harmonic and one can land within a few Hz of the tone,
        // and at high sample rates the comb never settles inside the window —
        // both pull the combed frequency low.  So keep a copy of the raw
        // (un-combed) selected channel and, once the comb gives the seed,
        // re-measure on the RAW signal in a narrow band around it (the mains is
        // stronger there but its harmonics are tens of Hz away, outside the
        // band) — un-biased and free of the mains.  Copied before the comb
        // rewrites the selected channel's buffer in place.
        float[] rawSel = null;
        if (selMode == MainsSuppression.IIR_COMB) {
            float[] src = (selected == Channel.L) ? measLeftBuf : measRightBuf;
            if (rawSelBuf == null || rawSelBuf.length < avail) rawSelBuf = new float[avail];
            System.arraycopy(src, 0, rawSelBuf, 0, avail);
            rawSel = rawSelBuf;
        }
        if (leftMode == MainsSuppression.IIR_COMB) {
            MainsCombFilter c = measComb(true, sampleRate);
            c.track(measLeftBuf, avail);
            c.reset();
            c.processPreservingDc(measLeftBuf, avail);
        }
        if (rightMode == MainsSuppression.IIR_COMB) {
            MainsCombFilter c = measComb(false, sampleRate);
            c.track(measRightBuf, avail);
            c.reset();
            c.processPreservingDc(measRightBuf, avail);
        }
        float[] data = (selected == Channel.L) ? measLeftBuf : measRightBuf;
        int measLen  = avail;
        if (selMode == MainsSuppression.IIR_COMB) {
            // The comb's delay lines start zeroed each pass, so its head is an
            // un-suppressed pass-through that would skew Vpp/Vrms.  Measure the
            // settled tail instead (≈3 time-constants in; capped so at least
            // half the window remains — at very high sample rates the window
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
        SignalMeasurements result = SignalMeasurements.compute(data, measLen, sampleRate, peakVolts);
        if (rawSel != null && Double.isFinite(result.getFrequency())) {
            // Re-pin the comb-located tone on the raw signal, free of the
            // comb's notch bias, with a narrow band around the seed.
            double precise = SignalMeasurements.refineFrequencyAround(
                    rawSel, avail, sampleRate, result.getFrequency(), FREQ_REFINE_HALF_HZ);
            if (Double.isFinite(precise)) result = result.withFrequency(precise);
        }
        // Dual-tone has two simultaneous fundamentals — a single Tp /
        // Tr / Tf / f / duty value has no physical meaning.  Clear
        // the time fields so the readout shows {@code ---} for every
        // time row instead of latching onto whichever spectral peak
        // happened to win the Goertzel search on this tick.  Vpp /
        // Vrms / Vmean stay intact since they're well-defined for
        // any signal mode.
        if (prefs.getGenSignalForm() == GenSignalForm.DUAL_TONE) {
            result = result.withoutTimes();
        }
        long now = System.nanoTime();
        synchronized (measHistoryLock) {
            measHistory[measHistoryWrite] = result;
            measHistoryTime[measHistoryWrite] = now;
            meanHistoryLeftNorm [measHistoryWrite] = leftMean;
            meanHistoryRightNorm[measHistoryWrite] = rightMean;
            measHistoryWrite = (measHistoryWrite + 1) % MEAS_HISTORY_CAP;
            if (measHistorySize < MEAS_HISTORY_CAP) measHistorySize++;
        }
        lastLeftMeanNormalized  = leftMean;
        lastRightMeanNormalized = rightMean;
        lastMeasResult = result;
    }
}
