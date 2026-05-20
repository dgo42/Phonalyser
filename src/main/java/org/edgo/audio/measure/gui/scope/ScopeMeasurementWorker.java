package org.edgo.audio.measure.gui.scope;

import java.util.Arrays;
import java.util.function.Consumer;

import lombok.extern.log4j.Log4j2;

import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.gui.preferences.Preferences;
import org.edgo.audio.measure.gui.sound.SignalBuffer;
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

    private volatile SignalBuffer       buffer;

    private Thread          measThread;
    private volatile boolean measThreadRunning;

    private volatile SignalMeasurements lastMeasResult;
    private volatile double             lastLeftMeanNormalized;
    private volatile double             lastRightMeanNormalized;

    private float[] measLeftBuf;
    private float[] measRightBuf;

    /** Guards multi-field updates to the measurement history ring. */
    private final Object measHistoryLock = new Object();
    private final SignalMeasurements[] measHistory     = new SignalMeasurements[MEAS_HISTORY_CAP];
    private final long[]               measHistoryTime = new long[MEAS_HISTORY_CAP];
    private final double[]             meanHistoryLeftNorm  = new double[MEAS_HISTORY_CAP];
    private final double[]             meanHistoryRightNorm = new double[MEAS_HISTORY_CAP];
    private int measHistoryWrite;
    private int measHistorySize;

    // ─── External wiring ────────────────────────────────────────────────────

    public void setBuffer(SignalBuffer b) { this.buffer = b; }
    public SignalBuffer getBuffer()        { return buffer; }
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
     * between probes, reads the latest samples from the {@link SignalBuffer},
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

    /** Runs one measurement pass on the worker thread and updates the cache. */
    private void computeMeasurementOnce() {
        SignalBuffer b = buffer;
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
        double peakVolts = AudioBackend.getAdcFsVoltageRms() * Math.sqrt(2.0);
        double leftMean  = sampleMean(measLeftBuf,  avail);
        double rightMean = sampleMean(measRightBuf, avail);
        float[] data = (selected == Channel.L) ? measLeftBuf : measRightBuf;
        SignalMeasurements result = SignalMeasurements.compute(data, avail, sampleRate, peakVolts);
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
