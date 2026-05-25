package org.edgo.audio.measure.gui.fft;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.swt.widgets.Display;

import org.edgo.audio.measure.cli.util.FreqRespCalHelper;
import org.edgo.audio.measure.cli.util.FreqRespCalibration;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.WindowType;
import org.edgo.audio.measure.fft.FftAnalyzer;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.preferences.Preferences;
import org.edgo.audio.measure.gui.sound.SignalBuffer;

import java.util.List;

/**
 * Background analyser worker that owns the FFT compute thread, its
 * {@link FftAnalyzer} instance, and every piece of state that the
 * spectrum-paint side of {@link FftView} merely consumes.
 *
 * <p>Lifecycle is parent-owned: {@link FftView} constructs one worker
 * and calls {@link #start} / {@link #stop} alongside its own visibility
 * and dispose lifecycle.  The worker publishes "new result available"
 * back to {@link FftView} by invoking the {@link Runnable} passed into
 * the constructor — already marshalled to the SWT thread via
 * {@link Display#asyncExec}.
 *
 * <p>All getters return current state read from {@code volatile} fields
 * so {@link FftView}'s paint listener can pull values without locking.
 */
public final class FftAnalyzerWorker {

    /** Idle tick when there's no buffer / not enough fresh samples. */
    private static final int IDLE_TICK_MS = 250;
    /** Maximum sleep between analysis attempts. */
    private static final int MAX_SLEEP_MS = 500;

    private final Display  display;
    private final Runnable onResultReady;

    private final FftAnalyzer analyzer = new FftAnalyzer();

    private volatile FftAnalyzer.Result lastResult;
    // (preCorrectionPeaks now lives inside Result so it publishes
    //  atomically with the rest of the result; getter reads via lastResult.)
    private volatile SignalBuffer       buffer;
    private final    AtomicBoolean      paused = new AtomicBoolean(false);
    private volatile int                completedAnalyses;
    private volatile boolean            running;
    /** Sample-buffer write position at the start of the previous analysis
     *  window — used to compute "how many new samples since last frame". */
    private volatile long lastAnalysisWritePos = -1;
    /** Sample-buffer write position the moment the FFT pane started
     *  recording (or was reset).  Samples older than this are treated
     *  as stale.  Sentinel −1 = "set me on the next tick". */
    private volatile long startWritePos = -1;
    /** False until the very first analysis has run since {@link #start}. */
    private volatile boolean firstFrameDone;
    /** Tracks generator on/off transitions so the data collection is
     *  reset whenever the user starts or stops the generator. */
    private volatile Boolean lastGeneratorActive;
    /** Reusable per-tick sample buffers — grown lazily, never shrunk.
     *  Owned by the worker thread only. */
    private float[] reusableLeftBuf  = new float[0];
    private float[] reusableRightBuf = new float[0];
    private Thread worker;

    // ─── Per-frame FFT cache (perf) ─────────────────────────────────────────
    /** Soft budget for total cache memory (bytes).  Cap on frame count is
     *  derived from this so a large fftSize automatically shrinks the
     *  number of cached frames instead of inflating the heap to GB. */
    private static final long FRAME_CACHE_BYTE_BUDGET = 128L * 1024 * 1024;
    /** Shared monitor for all {@link #frameCacheMap} and {@link #arrayPool}
     *  access — both are written/read on the worker thread but cleared
     *  from arbitrary threads (event subscriptions, {@link #resetStatistics}). */
    private final Object frameCacheLock = new Object();
    /** Position-keyed cache of raw windowed FFT results (re[], im[]) for
     *  individual frames.  Lets each tick FFT only the genuinely-new
     *  frame instead of re-FFT-ing all N frames in the sliding window. */
    private final LinkedHashMap<Long, double[][]> frameCacheMap =
            new LinkedHashMap<Long, double[][]>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, double[][]> eldest) {
            if (size() <= frameCacheCap) return false;
            // Salvage the evicted entry's arrays to the pool so the next
            // put can reuse them via arraycopy instead of allocating
            // fresh doubles[fftSize] — eliminates the GC churn that
            // makes large-fftSize FFT panes drift toward heap exhaustion.
            double[][] v = eldest.getValue();
            if (v != null && v[0] != null) {
                arrayPool.addLast(v[0]);
                arrayPool.addLast(v[1]);
            }
            capPoolLocked();
            return true;
        }
    };
    /** Reusable double[] buffers for the cache.  Arrays evicted from
     *  {@link #frameCacheMap} land here; the next {@link FrameFftCache#put}
     *  pops one instead of allocating.  Capped at {@code 2 × cap} to
     *  bound retained memory on rapid cap shrinks. */
    private final ArrayDeque<double[]> arrayPool = new ArrayDeque<>();
    /** Current cap on cached frames — derived per-tick from
     *  {@link #FRAME_CACHE_BYTE_BUDGET} and the configured averaging count,
     *  so the cache stays bounded regardless of fftSize. */
    private volatile int frameCacheCap = 16;
    /** Fingerprint of (fftSize, window, channel, sampleRate) at the time
     *  the cache was last known to be coherent.  Mismatch ⇒ clear. */
    private volatile long frameCacheFingerprint = Long.MIN_VALUE;
    /** Event-bus callback that drops the per-frame raw-FFT cache.  Does
     *  NOT call {@link #resetStatistics}: the cache contents are
     *  mathematically independent of cal changes (cal is applied after
     *  {@code analyze}) and of generator parameter changes (the cached
     *  FFTs are still correct for the samples that are physically in
     *  the buffer).  Calling resetStatistics here would force the next
     *  analysis to wait for a full fftLength of fresh samples — a
     *  multi-second blank period the user perceives as a glitch every
     *  time they nudge an amplitude / frequency / cal row. */
    private final Runnable invalidateOnEvent = this::recycleAndClearCache;
    /** Cache adapter passed to {@link FftAnalyzer#setFrameCache}; backed
     *  by {@link #frameCacheMap} under {@link #frameCacheLock}. */
    private final FftAnalyzer.FrameFftCache frameFftCacheImpl =
            new FftAnalyzer.FrameFftCache() {
        @Override
        public boolean tryFill(long absStart, int fftSize,
                               double[] outRe, double[] outIm) {
            double[][] hit;
            synchronized (frameCacheLock) {
                hit = frameCacheMap.get(absStart);
            }
            if (hit == null || hit[0].length != fftSize) return false;
            System.arraycopy(hit[0], 0, outRe, 0, fftSize);
            System.arraycopy(hit[1], 0, outIm, 0, fftSize);
            return true;
        }
        @Override
        public void put(long absStart, int fftSize, double[] re, double[] im) {
            double[] reCopy, imCopy;
            synchronized (frameCacheLock) {
                reCopy = obtainArrayLocked(fftSize);
                imCopy = obtainArrayLocked(fftSize);
            }
            // Copy outside the lock — System.arraycopy is the hot path
            // (megabytes per put at large fftSize); holding the lock
            // would needlessly block cache invalidate from other threads.
            System.arraycopy(re, 0, reCopy, 0, fftSize);
            System.arraycopy(im, 0, imCopy, 0, fftSize);
            synchronized (frameCacheLock) {
                frameCacheMap.put(absStart, new double[][] { reCopy, imCopy });
            }
        }
    };

    /** Pop a pooled {@code double[size]} if one is available; otherwise
     *  allocate.  Wrong-sized arrays in the pool are discarded.  Caller
     *  MUST hold {@link #frameCacheLock}. */
    private double[] obtainArrayLocked(int size) {
        while (!arrayPool.isEmpty()) {
            double[] a = arrayPool.pollLast();
            if (a.length == size) return a;
            // wrong size (config change leftover) → let GC reclaim
        }
        return new double[size];
    }

    /** Bounds the pool so it never holds more spare arrays than the
     *  cache cap can re-consume.  Caller MUST hold the lock. */
    private void capPoolLocked() {
        int budget = Math.max(4, frameCacheCap * 2);
        while (arrayPool.size() > budget) arrayPool.pollFirst();
    }

    /** Clears the cache, recycling every entry's arrays into the pool
     *  so the next {@link FrameFftCache#put} reuses them instead of
     *  allocating.  Safe to call from any thread. */
    private void recycleAndClearCache() {
        synchronized (frameCacheLock) {
            for (double[][] v : frameCacheMap.values()) {
                if (v != null && v[0] != null) {
                    arrayPool.addLast(v[0]);
                    arrayPool.addLast(v[1]);
                }
            }
            frameCacheMap.clear();
            capPoolLocked();
        }
    }

    /** Drops both the cache AND the pool (e.g., after a config change
     *  where every pooled array has the wrong size). */
    private void discardCacheAndPool() {
        synchronized (frameCacheLock) {
            frameCacheMap.clear();
            arrayPool.clear();
        }
    }

    // ─── Cross-tick accumulator (forever-mode only) ────────────────────────
    /** Coherent accumulator: running complex sum (re/im) across every
     *  forever-mode tick since the last reset.  Each tick contributes its
     *  per-bin r.re/r.im rotated to align with {@link #accumRefSampleStart}'s
     *  time origin and weighted by that tick's {@code frameCount}, so the
     *  cumulative SNR boost is √(total frames) instead of √(frames per
     *  tick).  Null when not in forever-coherent mode or before the first
     *  contribution. */
    private double[] accumRe;
    private double[] accumIm;
    /** Incoherent (power) accumulator counterpart.  Holds {@code |X[k]|²}
     *  weighted by frame count, no phase alignment needed. */
    private double[] accumPow;
    /** Total frame count accumulated since the last reset.  Drives the
     *  "N average(s)" label in forever mode and the stop-after-N
     *  threshold. */
    private volatile int accumFrames;
    /** Absolute sample-stream position of the first contributing tick's
     *  frame-0 (= its {@code samplesAbsStart}).  Every subsequent tick's
     *  spectrum is phase-rotated by the time delta from this reference
     *  to keep tones coherent across ticks. */
    private long    accumRefSampleStart;
    /** Pinned kFractional from the first contributing tick — held constant
     *  so the cross-tick rotation is well-defined.  Signals that drift in
     *  frequency more than a few ppm during the recording will lose phase
     *  coherence; that's a hardware-stability concern, not fixable here. */
    private double  accumKFractional;
    /** Pinned {@code round(kFractional)} for the rotation formula. */
    private int     accumIntFundBinRounded;
    /** Config the accumulator was built for; mismatch ⇒ reset. */
    private int     accumFftSize;
    private boolean accumCoherent;
    /** True once at least one tick has contributed. */
    private boolean accumHasData;
    /** Tracks the previous tick's forever-mode flag so we can reset on
     *  transitions between finite-N and forever modes. */
    private boolean lastWasForever;

    /** Drops the cross-tick accumulator.  Safe to call from any thread —
     *  arrays are reassigned, not cleared in place. */
    private void resetAccumulator() {
        accumRe = null;
        accumIm = null;
        accumPow = null;
        accumFrames = 0;
        accumHasData = false;
    }

    /** Adds the post-cal Result's spectrum to the cross-tick accumulator.
     *  For coherent mode, applies the time-shift phase rotation that aligns
     *  this tick's frame-0 with the accumulator's reference (so tones
     *  add coherently across ticks).  For incoherent mode, just sums
     *  power per bin.  Resets the accumulator transparently when fftSize
     *  or coherent flag changes between ticks. */
    private void accumulateIntoForeverBuffer(FftAnalyzer.Result r,
                                             long samplesAbsStart,
                                             boolean coherent) {
        int fftSize  = r.fftSize;
        int halfSize = fftSize / 2;
        int N        = halfSize + 1;
        double kFrac = r.freqResolution > 0
                ? r.fundamentalHzRefined / r.freqResolution
                : 0.0;
        int intFundBin = Math.max(1, (int) Math.round(kFrac));

        boolean restart = !accumHasData
                || accumFftSize != fftSize
                || accumCoherent != coherent;
        if (restart) {
            accumRe  = coherent ? new double[N] : null;
            accumIm  = coherent ? new double[N] : null;
            accumPow = coherent ? null          : new double[N];
            accumFrames = 0;
            accumFftSize = fftSize;
            accumCoherent = coherent;
            accumRefSampleStart    = samplesAbsStart;
            accumKFractional       = kFrac;
            accumIntFundBinRounded = intFundBin;
            accumHasData = true;
        }

        int weight = Math.max(1, r.frameCount);
        if (coherent) {
            // Per-bin time-shift rotation: same formula as analyze()'s
            // per-frame correction, with f·step replaced by the absolute
            // sample delta from the accumulator's reference.  For bin
            // k = N·intFundBinRounded (the Nth harmonic), this rotates
            // by exactly the phase shift the tone accumulated over
            // (samplesAbsStart − accumRefSampleStart) samples.
            long delta = samplesAbsStart - accumRefSampleStart;
            double baseAngle = (accumIntFundBinRounded > 0)
                    ? -2.0 * Math.PI * delta * accumKFractional
                      / ((double) fftSize * accumIntFundBinRounded)
                    : 0.0;
            double cosBase = Math.cos(baseAngle);
            double sinBase = Math.sin(baseAngle);
            double corrRe = 1.0, corrIm = 0.0;
            for (int k = 0; k < N; k++) {
                double rRe = r.re[k] * weight;
                double rIm = r.im[k] * weight;
                double rotRe = rRe * corrRe - rIm * corrIm;
                double rotIm = rRe * corrIm + rIm * corrRe;
                accumRe[k] += rotRe;
                accumIm[k] += rotIm;
                double nextRe = corrRe * cosBase - corrIm * sinBase;
                corrIm = corrRe * sinBase + corrIm * cosBase;
                corrRe = nextRe;
            }
        } else {
            for (int k = 0; k < N; k++) {
                double ampLin = r.amplitudeDbFs[k] > -290.0
                        ? Math.pow(10.0, r.amplitudeDbFs[k] / 20.0)
                        : 0.0;
                accumPow[k] += ampLin * ampLin * weight;
            }
        }
        accumFrames += weight;
    }

    /** Mutates {@code r}'s re/im/amplitudeDbFs/phaseDeg arrays in place to
     *  reflect the cumulative accumulator average.  After this, the caller
     *  should run {@link FftAnalyzer#recomputeStats} so the fundamental,
     *  harmonic table, THD/SNR are re-derived from the cumulative spectrum
     *  (the per-tick stats were just one tick's contribution). */
    private void overlayAccumulatorOnto(FftAnalyzer.Result r) {
        if (!accumHasData || accumFrames <= 0) return;
        int halfSize = r.fftSize / 2;
        int N        = halfSize + 1;

        // Derive the (mag → amplitude) conversion factor used by analyze()
        // from the pre-overlay r itself: amplLin = hypot(re,im) · normFactor·2
        // for non-DC/Nyquist bins.  Picking the fundamental bin guarantees
        // a high-SNR sample for the ratio; the factor depends only on the
        // window's coherent gain (= constant for a given fftSize/window).
        int kFund = Math.max(1, (int) Math.round(
                r.fundamentalHzRefined / r.freqResolution));
        if (kFund > halfSize) kFund = halfSize;
        double magFund = Math.hypot(r.re[kFund], r.im[kFund]);
        double ampFund = r.amplitudeDbFs[kFund] > -290.0
                ? Math.pow(10.0, r.amplitudeDbFs[kFund] / 20.0)
                : 0.0;
        if (magFund < 1e-30 || ampFund < 1e-30) return;
        double conv = ampFund / magFund;   // normFactor · 2 (for non-DC/Nyquist)

        if (accumCoherent) {
            for (int k = 0; k < N; k++) {
                double avgRe = accumRe[k] / accumFrames;
                double avgIm = accumIm[k] / accumFrames;
                r.re[k] = avgRe;
                r.im[k] = avgIm;
                double mag = Math.hypot(avgRe, avgIm);
                double scale = (k == 0 || k == halfSize) ? 0.5 : 1.0;
                double amp = mag * conv * scale;
                r.amplitudeDbFs[k] = amp > 1e-15 ? 20.0 * Math.log10(amp) : -300.0;
                r.phaseDeg[k] = Math.toDegrees(Math.atan2(avgIm, avgRe));
            }
        } else {
            for (int k = 0; k < N; k++) {
                double avgPow = accumPow[k] / accumFrames;
                double mag = Math.sqrt(avgPow);
                double scale = (k == 0 || k == halfSize) ? 0.5 : 1.0;
                double amp = mag * conv * scale;
                r.amplitudeDbFs[k] = amp > 1e-15 ? 20.0 * Math.log10(amp) : -300.0;
                // Match analyze()'s incoherent convention: re holds magnitude,
                // im = 0 (phase is undefined for power averaging).
                r.re[k] = amp;
                r.im[k] = 0.0;
                r.phaseDeg[k] = 0.0;
            }
        }
    }

    /**
     * @param display       SWT display used to marshal redraw / event
     *                      publish hops back to the UI thread.
     * @param onResultReady invoked on the UI thread after each
     *                      successful analysis pass.  Typically the
     *                      view's {@code redraw()}.
     */
    public FftAnalyzerWorker(Display display, Runnable onResultReady) {
        this.display       = display;
        this.onResultReady = onResultReady;
        analyzer.setFrameCache(frameFftCacheImpl);
        MessageBus.instance().subscribe(Events.GENERATOR_SIGNAL_CHANGED, invalidateOnEvent);
        MessageBus.instance().subscribe(Events.FFT_CALIBRATION_CHANGED, invalidateOnEvent);
    }

    // ─── External wiring ────────────────────────────────────────────────────

    public void setBuffer(SignalBuffer b)       { this.buffer = b; }
    public SignalBuffer getBuffer()              { return buffer; }
    public FftAnalyzer.Result getLastResult()   { return lastResult; }
    public void setLastResult(FftAnalyzer.Result r) { this.lastResult = r; }
    /** Returns the fundamental + harmonic (freq, dBFS) pairs measured
     *  BEFORE calibration was applied, or {@code null} when no
     *  calibration is loaded.  Read from the published Result so that
     *  it stays consistent with {@link #getLastResult()} — no tearing
     *  race where a fresh pre is paired with a stale r (or vice versa).
     *  Same layout as {@code FreqRespCalHelper.capturePreCorrectionPeaks}. */
    public double[][] getPreCorrectionPeaks() {
        FftAnalyzer.Result r = lastResult;
        return r != null ? r.preCorrectionPeaks : null;
    }
    public int  getCompletedAnalyses()          { return completedAnalyses; }
    /** Cumulative frame count accumulated across all forever-mode ticks
     *  since the last reset.  Returns 0 outside forever mode or before
     *  any contribution.  This is the meaningful "N average(s)" depth
     *  for forever mode — equal to the SNR-improvement factor √N². */
    public int  getAccumulatedFrames()          { return accumFrames; }
    public boolean isRunning()                  { return running; }
    public boolean isPaused()                   { return paused.get(); }
    public boolean isFirstFrameDone()           { return firstFrameDone; }

    // ─── Lifecycle ──────────────────────────────────────────────────────────

    /** Starts the analyser on a daemon worker thread.  Idempotent. */
    public synchronized void start() {
        if (running) return;
        running = true;
        // Re-arm the first-frame regime + drop any stale spectrum so
        // the chart starts blank.  startWritePos sentinel (−1) means
        // "set me on the next tick" — recorded against the live buffer's
        // writePos so the first analysis waits for a FULL fftLength of
        // FRESH samples captured AFTER this point.
        firstFrameDone        = false;
        lastAnalysisWritePos  = -1;
        startWritePos         = -1;
        lastResult            = null;
        completedAnalyses     = 0;
        resetAccumulator();
        paused.set(false);
        worker = new Thread(this::workerLoop, "fft-analyzer");
        worker.setDaemon(true);
        worker.start();
    }

    /** Stops the analyser and interrupts the worker.  Idempotent. */
    public synchronized void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        MessageBus.instance().unsubscribe(Events.GENERATOR_SIGNAL_CHANGED, invalidateOnEvent);
        MessageBus.instance().unsubscribe(Events.FFT_CALIBRATION_CHANGED, invalidateOnEvent);
        discardCacheAndPool();
        resetAccumulator();
    }

    /** Clears the completed-analyses counter, drops the retained
     *  spectrum, and resumes the loop if it was paused by stop-after-N.
     *  Safe to call from any thread.  Does NOT post the redraw — the
     *  caller does that. */
    public void resetStatistics() {
        paused.set(false);
        completedAnalyses     = 0;
        lastResult            = null;
        firstFrameDone        = false;
        lastAnalysisWritePos  = -1;
        startWritePos         = -1;
        recycleAndClearCache();
        resetAccumulator();
    }

    // ─── Status queries ─────────────────────────────────────────────────────

    /** True when the audio generator is currently producing a signal.
     *  Resolved via {@link MessageBus} — the generator pane registers a
     *  responder for {@link Events#GENERATOR_RUNNING}.  Defaults to
     *  {@code true} when no responder is registered. */
    public boolean isGeneratorActive() {
        Boolean answer = MessageBus.instance().request(Events.GENERATOR_RUNNING);
        return answer == null || answer;
    }

    /** Fraction (0..1) of the data needed for the *current* FFT frame
     *  that has already been captured.  Drives the "NN%" indicator next
     *  to the magnitude-unit combo. */
    public double getNextFrameProgress() {
        SignalBuffer buf = buffer;
        if (buf == null) return 0;
        Preferences prefs = Preferences.instance();
        int fftLength = prefs.getFftLength();
        if (fftLength < 8) return 0;
        long writePos = buf.getWritePos();
        if (!firstFrameDone) {
            // First-frame regime: need a complete window of FRESH samples.
            if (startWritePos < 0) return 0;
            long fresh = writePos - startWritePos;
            double f = (double) fresh / fftLength;
            if (f < 0) f = 0; else if (f > 1) f = 1;
            return f;
        }
        // Hop regime — newSamples toward the next hop.
        if (lastAnalysisWritePos < 0) return 1;
        FftOverlap overlap;
        try { overlap = FftOverlap.valueOf(prefs.getFftOverlap()); }
        catch (IllegalArgumentException e) { overlap = FftOverlap.PCT_0; }
        double hop = Math.max(1, fftLength * (1.0 - overlap.fraction));
        long newSamples = writePos - lastAnalysisWritePos;
        if (newSamples <= 0) return 0;
        double f = newSamples / hop;
        return (f > 1) ? 1 : f;
    }

    // ─── Worker loop ────────────────────────────────────────────────────────

    /** Worker-thread body.  Runs analysis on a background thread, posts
     *  the redraw via {@link Display#asyncExec} so the SWT thread isn't
     *  blocked by the FFT compute. */
    private void workerLoop() {
        while (running) {
            int sleepMs = IDLE_TICK_MS;
            if (!paused.get()) {
                try {
                    sleepMs = doAnalysis();
                } catch (Throwable t) {
                    sleepMs = IDLE_TICK_MS;
                }
            }
            if (sleepMs <= 0) sleepMs = IDLE_TICK_MS;
            if (sleepMs > MAX_SLEEP_MS) sleepMs = MAX_SLEEP_MS;
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /** Performs at most one analysis pass.  Returns the suggested sleep
     *  in milliseconds before the next attempt — derived from the
     *  configured overlap so the FFT update rate scales with the hop
     *  size. */
    private int doAnalysis() {
        // Generator-state transition: wipe accumulated stats so the next
        // analysis starts with a clean slate.
        boolean genActiveNow = isGeneratorActive();
        if (lastGeneratorActive != null && lastGeneratorActive != genActiveNow) {
            resetStatistics();
            postRedraw();
        }
        lastGeneratorActive = genActiveNow;

        SignalBuffer buf = buffer;
        if (buf == null) return IDLE_TICK_MS;
        int sampleRate = buf.getSampleRate();
        if (sampleRate <= 0) return IDLE_TICK_MS;

        Preferences prefs = Preferences.instance();
        int fftLength = prefs.getFftLength();
        if (fftLength < 8 || (fftLength & (fftLength - 1)) != 0) return IDLE_TICK_MS;

        double avgRaw = prefs.getFftAverages();
        int averages = Double.isInfinite(avgRaw) ? 2 : Math.max(1, (int) avgRaw);

        WindowType window;
        try { window = WindowType.valueOf(prefs.getFftWindow()); }
        catch (IllegalArgumentException e) { window = WindowType.HANN; }

        FftOverlap overlap;
        try { overlap = FftOverlap.valueOf(prefs.getFftOverlap()); }
        catch (IllegalArgumentException e) { overlap = FftOverlap.PCT_0; }

        double hop = fftLength * (1.0 - overlap.fraction);
        if (hop < 1) hop = 1;
        int hopSamples = (int) Math.max(1, Math.round(hop));
        int needed = (int) Math.ceil(fftLength + (averages - 1) * hop);
        needed = Math.min(needed, buf.getCapacity());

        long writePos = buf.getWritePos();
        if (startWritePos < 0) startWritePos = writePos;
        long fresh = writePos - startWritePos;
        if (!firstFrameDone) {
            if (fresh < fftLength) {
                int waitMs = msForSamples((int)(fftLength - fresh), sampleRate);
                return Math.max(20, waitMs);
            }
        } else {
            if (lastAnalysisWritePos < 0) lastAnalysisWritePos = writePos;
            long newSamples = writePos - lastAnalysisWritePos;
            if (newSamples < hopSamples) {
                int waitMs = msForSamples(hopSamples - (int) newSamples, sampleRate);
                return Math.max(20, waitMs);
            }
        }
        // Clamp read window to fresh-only samples until the full averaging
        // window has accumulated fresh data.  Without this resetStatistics
        // clears FFT state but the SignalBuffer still holds old samples,
        // and readLatest(needed,…) pulls (needed − fresh) of them back
        // into the average — visible as ghosting after the generator stops.
        needed = (int) Math.min(needed, fresh);

        Channel channel = prefs.getFftChannel();
        boolean wantLeft  = (channel == Channel.L);
        float[] leftBuf  = null;
        float[] rightBuf = null;
        if (wantLeft) {
            if (reusableLeftBuf.length != needed) reusableLeftBuf = new float[needed];
            leftBuf = reusableLeftBuf;
        } else {
            if (reusableRightBuf.length != needed) reusableRightBuf = new float[needed];
            rightBuf = reusableRightBuf;
        }
        // readEndingAt (not readLatest) so the samples returned have a
        // deterministic absolute end position == writePos snapshotted
        // above.  This makes samplesAbsStart = writePos − got, which the
        // FrameFftCache uses as a stable key across ticks.
        int got = buf.readEndingAt(writePos, needed, leftBuf, rightBuf);
        if (got < fftLength) return IDLE_TICK_MS;
        long samplesAbsStart = writePos - got;

        // Cache invalidation: any change to fftLength / window / channel /
        // sampleRate makes previously cached raw FFTs stale.  Drop both
        // the cache AND the pool (pooled arrays would also be the wrong
        // size after an fftLength change).
        long cfgFingerprint = fftLength;
        cfgFingerprint = 31 * cfgFingerprint + sampleRate;
        cfgFingerprint = 31 * cfgFingerprint + window.ordinal();
        cfgFingerprint = 31 * cfgFingerprint + (wantLeft ? 1 : 0);
        if (cfgFingerprint != frameCacheFingerprint) {
            discardCacheAndPool();
            // Same config change invalidates the cross-tick accumulator
            // (different fftSize/window/channel would make the cumulative
            // spectrum meaningless).
            resetAccumulator();
            frameCacheFingerprint = cfgFingerprint;
        }
        // Memory-aware cap.  A single cached entry is 2 × fftSize × 8 B
        // (re[] + im[]), so at fftSize = 2 M each entry is 32 MB and at
        // 4 M it's 64 MB.  Capping the count alone (e.g. 2 × averages)
        // means a high averages-count + large fftSize trivially burns
        // gigabytes.  Instead, divide a fixed byte budget by the per-entry
        // size, and never exceed averages + 2 (which is all the ring
        // ever actually needs).
        long perEntryBytes = 2L * fftLength * Double.BYTES;
        int budgetCap = (int) Math.max(2L,
                FRAME_CACHE_BYTE_BUDGET / Math.max(1L, perEntryBytes));
        frameCacheCap = Math.max(2, Math.min(averages + 2, budgetCap));

        float[] samples = wantLeft ? leftBuf : rightBuf;
        if (got < samples.length) {
            float[] trimmed = new float[got];
            System.arraycopy(samples, 0, trimmed, 0, got);
            samples = trimmed;
        }

        int    calcMaxH = Math.max(9, prefs.getFftCalcMaxHarmonic()) - 1;
        double distMin  = prefs.isFftDistMinEnabled() ? prefs.getFftDistMinHz() : 0;
        double distMax  = prefs.isFftDistMaxEnabled() ? prefs.getFftDistMaxHz() : 0;
        boolean coherent = prefs.isFftCoherentAveraging();
        boolean genActive = isGeneratorActive();
        double fundRefDbV = genActive ? resolveFundRefDbV(prefs) : Double.NaN;
        double expectedFundHz = (genActive && prefs.isFftFundFromGenerator())
                ? Preferences.instance().getGenFrequencyHz()
                : Double.NaN;

        analyzer.setSamplesAbsStart(samplesAbsStart);
        FftAnalyzer.Result r;
        try {
            r = analyzer.analyze(samples, sampleRate, fftLength, calcMaxH,
                    window, overlap, distMin, distMax, coherent, fundRefDbV,
                    /*logSummary=*/ false, expectedFundHz);
        } catch (RuntimeException ex) {
            return IDLE_TICK_MS;
        }
        // Anchor absolute dBV via the ADC full-scale calibration when
        // available — used as the dBV/dBFS offset for every non-fundamental
        // bin (harmonics, noise floor, spectrum trace).
        double fs = prefs.getAdcFsVoltageRms();
        if (fs > 0 && Double.isFinite(r.fundamentalDbFs)) {
            r.fundRefDbV = r.fundamentalDbFs + 20.0 * Math.log10(fs);
        }
        // Apply every loaded .frc calibration in cascade.  Each call
        // mutates r in place and re-runs the harmonic / THD / SNR
        // recompute, so the numeric readouts the view reads from r
        // reflect the corrected spectrum.  "Calibrate with noise"
        // (THD-tab checkbox) → correct EVERY bin; otherwise just the
        // fundamental + harmonic bin neighbourhoods.
        List<FftCalibrationStore.Entry> calEntries =
                FftCalibrationStore.instance().getEntries();
        if (!calEntries.isEmpty()) {
            // Snapshot pre-correction peaks BEFORE the in-place mutate
            // so the view can draw "before" dots next to the corrected
            // "after" ones.
            r.preCorrectionPeaks = FreqRespCalHelper.capturePreCorrectionPeaks(r);
            boolean correctAll = prefs.isFftCalibrateWithNoise();
            for (FftCalibrationStore.Entry e : calEntries) {
                FreqRespCalibration calForChan = wantLeft
                        ? e.getCalibration().left()
                        : e.getCalibration().right();
                FreqRespCalHelper.applyCompensationInPlace(
                        r, calForChan, correctAll);
            }
            // applyCompensationInPlace only re-anchors fundRefDbV when
            // the cal file itself carries adcFsVoltageRms, and loadCsv
            // never reads that field — so after the cascade fundRefDbV
            // is stale (still references pre-cal fundamentalDbFs).
            // Re-apply the global ADC FS anchor here so the displayed
            // dBV reflects the cal-restored fundamental level.
            if (fs > 0 && Double.isFinite(r.fundamentalDbFs)) {
                r.fundRefDbV = r.fundamentalDbFs + 20.0 * Math.log10(fs);
            }
        } else {
            // No cal loaded — r.preCorrectionPeaks is already null
            // (default for a freshly-allocated Result), nothing to do.
        }

        // Mode-transition detection — switching between finite-N and
        // forever resets the cross-tick accumulator so the new mode
        // starts from a clean slate.
        boolean foreverMode = Double.isInfinite(avgRaw);
        if (foreverMode != lastWasForever) {
            resetAccumulator();
            lastWasForever = foreverMode;
        }
        // Cross-tick accumulation: in forever mode each tick contributes
        // its frames to a running sum (coherently rotated to align with
        // the accumulator's time reference), then the displayed Result
        // is rebuilt from the cumulative average and the harmonic / THD
        // / SNR stats are re-derived via recomputeStats.  This is the
        // real "infinite average" the user expects — SNR boost √(total
        // frames accumulated) instead of √(frames per tick).
        if (foreverMode) {
            accumulateIntoForeverBuffer(r, samplesAbsStart, coherent);
            overlayAccumulatorOnto(r);
            analyzer.recomputeStats(r);
            // recomputeStats updates fundamentalDbFs from the cumulative
            // spectrum; re-anchor fundRefDbV to keep the displayed dBV
            // consistent with the cal-restored level.
            if (fs > 0 && Double.isFinite(r.fundamentalDbFs)) {
                r.fundRefDbV = r.fundamentalDbFs + 20.0 * Math.log10(fs);
            }
            // Re-derive preCorrectionPeaks from the cumulative post-cal r
            // by adding back cal dB at each harmonic frequency.  Without
            // this the "before-cal" (BLUE) dots reflect a SINGLE-tick
            // pre-cal noise reading while the "after-cal" (RED) dots
            // reflect the cumulative average; for harmonics whose lifted
            // signal sits below the single-tick noise floor (very common
            // when the user is averaging precisely to reveal them), the
            // BLUE dots float above the RED dots even though the cal
            // genuinely lifts — a confusing visual mismatch.  After this
            // recompute both dots are measured on the SAME cumulative
            // spectrum and the BLUE-vs-RED difference equals exactly
            // the cal's dB lift at that harmonic.
            if (!calEntries.isEmpty()) {
                int hc = r.harmonicCount;
                double[] preFreqs = new double[1 + hc];
                double[] preDbFs  = new double[1 + hc];
                // Use the PINNED accumKFractional (frozen at the first
                // contributing tick) to derive the cal-lookup frequency.
                // The single-tick r.fundamentalHzRefined can jitter
                // tick-to-tick (worst case when frame-rejection collapses
                // bestLen to 1 and kFractional falls back to parabolic
                // interp); that jitter, multiplied by (h+2) for higher
                // harmonics, would dance the cal sampling point around
                // and make the BLUE dot jump even though RED (which is
                // derived from the cumulative bin amplitudes) stays put.
                double stableKF = accumHasData
                        ? accumKFractional
                        : (r.freqResolution > 0
                                ? r.fundamentalHzRefined / r.freqResolution
                                : 0.0);
                double stableFundHz = stableKF * r.freqResolution;
                preFreqs[0] = r.fundamentalHz;
                preDbFs[0]  = r.fundamentalDbFs
                        + sumCalDb(calEntries, wantLeft, stableFundHz);
                for (int h = 0; h < hc; h++) {
                    if (r.harmonicBins[h] > 0) {
                        preFreqs[1 + h] = r.harmonicHz[h];
                        double stableHarmHz = stableFundHz * (h + 2);
                        preDbFs[1 + h]  = r.harmonicDbFs[h]
                                + sumCalDb(calEntries, wantLeft, stableHarmHz);
                    }
                }
                r.preCorrectionPeaks = new double[][] { preFreqs, preDbFs };
            }
        }

        lastResult = r;
        completedAnalyses++;
        lastAnalysisWritePos = buf.getWritePos();
        firstFrameDone = true;
        // Stop-after-N only fires when the user is in "forever" averages
        // AND has explicitly enabled the cap.  Counts TICKS (matches the
        // displayed "N average(s)" label so "stop after 256" means
        // 256 analyses contributed to the running average).  The cross-
        // tick accumulator deepens each tick beyond just 2 frames; the
        // actual averaging depth is roughly 2× this threshold in frame
        // count.  Finite N already implements a moving window so the
        // analysis is continuous.
        if (foreverMode
                && prefs.isFftStopAfterNEnabled()
                && completedAnalyses >= prefs.getFftStopAfterN()) {
            paused.set(true);
            if (display != null && !display.isDisposed()) {
                display.asyncExec(() ->
                        MessageBus.instance().publish(Events.FFT_RECORDING_AUTO_STOPPED));
            }
        }
        postRedraw();
        return msForSamples(hopSamples, sampleRate);
    }

    private void postRedraw() {
        if (display != null && !display.isDisposed()) {
            display.asyncExec(onResultReady);
        }
    }

    private int msForSamples(int samples, int sampleRate) {
        if (sampleRate <= 0 || samples <= 0) return 0;
        return (int) Math.ceil(1000.0 * samples / sampleRate);
    }

    /** Returns the cumulative cal-cascade dB at frequency {@code f Hz}
     *  for the channel currently being analyzed.  Each loaded calibration
     *  contributes {@code 20·log10(|H(f)|)} (the cascade is multiplicative
     *  in magnitude, additive in dB).  Used to derive the cumulative
     *  pre-cal amplitude from the cumulative post-cal amplitude in
     *  forever-mode (see the {@code preCorrectionPeaks} recompute in
     *  {@link #doAnalysis}). */
    private static double sumCalDb(List<FftCalibrationStore.Entry> calEntries,
                                   boolean wantLeft, double f) {
        double sum = 0.0;
        for (FftCalibrationStore.Entry e : calEntries) {
            FreqRespCalibration cal = wantLeft
                    ? e.getCalibration().left()
                    : e.getCalibration().right();
            double m = FreqRespCalHelper.interpolate(cal, f)[0];
            sum += m > 0.0 ? 20.0 * Math.log10(m) : -300.0;
        }
        return sum;
    }

    /** Computes the dBV reference anchor passed into {@link FftAnalyzer}.
     *  In manual-fundamental mode we pass the user's value directly;
     *  otherwise we pass {@code NaN} so the analyser uses the measured
     *  {@code fundLinear} as its anchor. */
    private double resolveFundRefDbV(Preferences prefs) {
        if (prefs.isFftManualFundEnabled()) {
            String unit = prefs.getFftManualFundUnit();
            double v = prefs.getFftManualFundVrms();
            if ("dBV".equalsIgnoreCase(unit)) return v;
            if ("mV" .equalsIgnoreCase(unit)) v *= 0.001;
            return (v > 0) ? 20.0 * Math.log10(v) : Double.NaN;
        }
        return Double.NaN;
    }
}
