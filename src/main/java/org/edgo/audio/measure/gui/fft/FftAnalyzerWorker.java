package org.edgo.audio.measure.gui.fft;

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
    /** Snapshot of fundamental + harmonic (freq, dBFS) pairs BEFORE
     *  calibration was subtracted from {@link #lastResult}, or {@code null}
     *  when no calibration is loaded.  Layout matches
     *  {@code FreqRespCalHelper.capturePreCorrectionPeaks}:
     *  {@code [0]} = freqs[], {@code [1]} = dBFs[].  Read by the view
     *  to draw the "before-cal" dots in the configured colour. */
    private volatile double[][]         preCorrectionPeaks;
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
    /** Shared monitor for all {@link #frameCacheMap} access — the cache is
     *  written/read on the worker thread but cleared from arbitrary
     *  threads (event subscriptions, {@link #resetStatistics}). */
    private final Object frameCacheLock = new Object();
    /** Position-keyed cache of raw windowed FFT results (re[], im[]) for
     *  individual frames.  Lets each tick FFT only the genuinely-new
     *  frame instead of re-FFT-ing all N frames in the sliding window. */
    private final LinkedHashMap<Long, double[][]> frameCacheMap =
            new LinkedHashMap<Long, double[][]>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, double[][]> eldest) {
            return size() > frameCacheCap;
        }
    };
    /** Current cap on cached frames — scaled to 2× the configured
     *  averaging count so the entire sliding window fits with slack. */
    private volatile int frameCacheCap = 16;
    /** Fingerprint of (fftSize, window, channel, sampleRate) at the time
     *  the cache was last known to be coherent.  Mismatch ⇒ clear. */
    private volatile long frameCacheFingerprint = Long.MIN_VALUE;
    /** Event-bus callback that clears the cache + flushes the spectrum.
     *  Subscribed in the constructor; unsubscribed in {@link #stop}. */
    private final Runnable invalidateOnEvent = this::resetStatistics;
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
            double[][] copy = { re.clone(), im.clone() };
            synchronized (frameCacheLock) {
                frameCacheMap.put(absStart, copy);
            }
        }
    };

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
    }

    // ─── External wiring ────────────────────────────────────────────────────

    public void setBuffer(SignalBuffer b)       { this.buffer = b; }
    public SignalBuffer getBuffer()              { return buffer; }
    public FftAnalyzer.Result getLastResult()   { return lastResult; }
    public void setLastResult(FftAnalyzer.Result r) { this.lastResult = r; }
    /** Returns the fundamental + harmonic (freq, dBFS) pairs measured
     *  BEFORE calibration was applied, or {@code null} when no
     *  calibration is loaded.  Same layout as
     *  {@code FreqRespCalHelper.capturePreCorrectionPeaks}. */
    public double[][] getPreCorrectionPeaks()   { return preCorrectionPeaks; }
    public int  getCompletedAnalyses()          { return completedAnalyses; }
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
        synchronized (frameCacheLock) { frameCacheMap.clear(); }
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
        synchronized (frameCacheLock) { frameCacheMap.clear(); }
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
        // sampleRate makes previously cached raw FFTs stale.  Clear and
        // re-fingerprint before handing the cache to the analyzer.
        long cfgFingerprint = fftLength;
        cfgFingerprint = 31 * cfgFingerprint + sampleRate;
        cfgFingerprint = 31 * cfgFingerprint + window.ordinal();
        cfgFingerprint = 31 * cfgFingerprint + (wantLeft ? 1 : 0);
        if (cfgFingerprint != frameCacheFingerprint) {
            synchronized (frameCacheLock) { frameCacheMap.clear(); }
            frameCacheFingerprint = cfgFingerprint;
        }
        frameCacheCap = Math.max(8, 2 * averages);

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
            preCorrectionPeaks = FreqRespCalHelper.capturePreCorrectionPeaks(r);
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
            preCorrectionPeaks = null;
        }
        lastResult = r;
        completedAnalyses++;
        lastAnalysisWritePos = buf.getWritePos();
        firstFrameDone = true;
        // Stop-after-N only fires when the user is in "forever" averages
        // AND has explicitly enabled the cap.  Finite N already implements
        // a moving window so the analysis is continuous.
        if (Double.isInfinite(avgRaw)
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
