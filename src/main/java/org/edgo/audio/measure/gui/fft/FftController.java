package org.edgo.audio.measure.gui.fft;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

import org.eclipse.swt.widgets.Display;
import org.edgo.audio.measure.fft.FftAnalyzer;
import org.edgo.audio.measure.gui.Preferences;
import org.edgo.audio.measure.gui.scope.SignalBuffer;
import org.edgo.audio.measure.gui.scope.TriggerChannel;

import lombok.extern.log4j.Log4j2;

/**
 * Drives the live FFT view.
 *
 * <p>Every {@value #TICK_MS} ms (on the SWT UI thread, via
 * {@link Display#timerExec}) the controller:
 * <ol>
 *   <li>Reads the latest window of audio from the shared {@link SignalBuffer}
 *       belonging to the oscilloscope's capture path.</li>
 *   <li>Reads the current analyser knobs (FFT length, window function,
 *       overlap, averages, etc.) from {@link Preferences}.</li>
 *   <li>Runs {@link FftAnalyzer#analyze} on the window.</li>
 *   <li>Publishes the new {@link FftAnalyzer.Result} for {@code FftView}
 *       to read during paint.</li>
 * </ol>
 *
 * <p>"Stop after N" pauses the loop once the configured number of analyses
 * have completed.  {@link #resetStatistics()} re-arms it.
 *
 * <p>Analysis runs on the UI thread — typical FFT sizes (≤ 2²⁰) complete
 * in tens of milliseconds and don't perceptibly block redraws.  Very large
 * sizes (2²¹–2²²) may stutter; can be moved off-thread later if needed.
 */
@Log4j2
public final class FftController {

    /** Maximum wait while idle (no buffer / not yet enough samples). */
    private static final int IDLE_TICK_MS = 250;
    /** Maximum sleep granularity — keeps interrupts responsive even when
     *  the next hop is several seconds away (e.g. 4 M-bin FFT at 0%
     *  overlap on a 48 kHz capture takes ~87 s to acquire). */
    private static final int MAX_SLEEP_MS = 500;

    private final Display              display;
    private final Supplier<SignalBuffer> bufferSupplier;
    private final DoubleSupplier       generatorFreqSupplier;
    /** True when the audio generator is currently producing a signal.
     *  When false the controller ignores the "fundamental from generator"
     *  and "manual fundamental" prefs (the analyser auto-detects from
     *  the spectrum) and triggers a one-shot reset on the next tick. */
    private final BooleanSupplier      generatorActiveSupplier;
    private final Runnable             redrawCallback;
    /** Fired once on the UI thread when {@code isFftStopAfterNEnabled}
     *  triggers and the configured count of analyses has been reached.
     *  Null = no-op.  Owner uses it to disengage record mode (flip the
     *  Record button + release the shared capture). */
    private volatile Runnable          onStopAfterFired;
    private final FftAnalyzer          analyzer = new FftAnalyzer();

    /** Latest published result — paint reads, worker writes.  Effectively
     *  double-buffered: the previous result remains valid for paint while
     *  the worker is computing the next one. */
    private volatile FftAnalyzer.Result lastResult;
    /** Worker-thread state.  AtomicBoolean instead of volatile boolean to
     *  make the {@link #resetStatistics()} re-arm trivially thread-safe. */
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private volatile int    completedAnalyses;
    private volatile boolean running;
    /** Sample buffer write position at the start of the previous analysis
     *  window.  Used to compute "how many new samples since last frame"
     *  so we can fire the next FFT as soon as a hop (fftLength × (1 −
     *  overlap)) of fresh data is available — see {@link #doAnalysis}. */
    private long lastAnalysisWritePos = -1;
    /** Sample buffer write position the moment the FFT pane started
     *  recording (or was reset).  Samples older than this are treated
     *  as stale — the next analysis fires only after a full fftLength
     *  of FRESH samples has been captured.  Sentinel −1 = "set me on
     *  the next tick". */
    private volatile long startWritePos = -1;
    /** False until the very first analysis has run since {@link #start}.
     *  Drives the "wait for full window vs wait for one hop" decision
     *  in {@link #doAnalysis} and the corresponding gauge regime in
     *  {@link #getNextFrameProgress}.  Reset by
     *  {@link #resetStatistics} so the next analysis waits for fresh
     *  data again. */
    private volatile boolean firstFrameDone;
    /** Tracks generator on/off transitions so the data collection
     *  (averages, retained spectrum) is reset whenever the user starts
     *  or stops the generator.  {@code null} = "no observation yet". */
    private volatile Boolean lastGeneratorActive;
    /** Reusable per-tick sample buffers — grown lazily, never shrunk.
     *  Without these the worker allocates ~{@code needed} floats per
     *  tick (≈20 MB at fftLength=1 M / averages=32 / overlap=87.5 %)
     *  on top of the analyser's own scratch arrays — every tick.
     *  Owned by the worker thread only; never read concurrently. */
    private float[] reusableLeftBuf  = new float[0];
    private float[] reusableRightBuf = new float[0];
    private Thread worker;

    public FftController(Display display,
                         Supplier<SignalBuffer> bufferSupplier,
                         DoubleSupplier generatorFreqSupplier,
                         BooleanSupplier generatorActiveSupplier,
                         Runnable redrawCallback) {
        this.display                 = display;
        this.bufferSupplier          = bufferSupplier;
        this.generatorFreqSupplier   = generatorFreqSupplier;
        this.generatorActiveSupplier = generatorActiveSupplier;
        this.redrawCallback          = redrawCallback;
    }

    /** Starts the analysis loop on a daemon worker thread.  Idempotent. */
    public synchronized void start() {
        if (running) return;
        running = true;
        // Re-arm the first-frame regime + drop any stale spectrum so
        // the chart starts blank.  startWritePos sentinel (−1) means
        // "set me on the next tick" — recorded against the live
        // SignalBuffer's writePos so the first analysis waits for a
        // FULL fftLength of FRESH samples captured AFTER this point,
        // ignoring whatever the scope had recorded earlier.  Also
        // reset the averages counter + paused flag so the user gets
        // a fresh "0" in the averages-count indicator every time
        // they re-engage the Record button.
        firstFrameDone = false;
        lastAnalysisWritePos = -1;
        startWritePos = -1;
        lastResult = null;
        completedAnalyses = 0;
        paused.set(false);
        worker = new Thread(this::workerLoop, "fft-analyzer");
        worker.setDaemon(true);
        worker.start();
    }

    /** Stops the analysis loop and joins the worker.  Call before the
     *  owning pane is disposed.  Idempotent and safe even if start()
     *  was never called. */
    public synchronized void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
    }

    /** Latest published result, or {@code null} before the first successful
     *  tick. */
    public FftAnalyzer.Result getLastResult() {
        return lastResult;
    }

    /** Injects a snapshot result without running the analysis loop —
     *  used by the offscreen screenshot pane so it can render the
     *  same spectrum the live pane is showing. */
    public void setLastResult(FftAnalyzer.Result r) {
        this.lastResult = r;
    }

    /** Registers a callback that fires (on the UI thread) when
     *  stop-after-N triggers the pause.  Used by the pane to release
     *  its record-button + capture hold so the user sees record mode
     *  disengage automatically when N analyses are reached. */
    public void setOnStopAfterFired(Runnable cb) {
        this.onStopAfterFired = cb;
    }

    /** Number of analyses completed since the last reset.  Used by the
     *  view to indicate progress toward a stop-after-N target. */
    public int getCompletedAnalyses() {
        return completedAnalyses;
    }

    /** True when the audio generator is currently producing a signal.
     *  Read via the supplier passed in at construction (defaults to
     *  {@code true} when none was provided).  Used by the view to
     *  decide whether to draw the generator-anchored Δf row in the THD
     *  table — without an active generator the row would compare the
     *  measured fundamental against a stale frequency value and be
     *  meaningless. */
    public boolean isGeneratorActive() {
        return generatorActiveSupplier == null || generatorActiveSupplier.getAsBoolean();
    }

    /** Fraction (0..1) of the data needed for the *current* FFT frame
     *  that has already been captured.  Drives the "NN%" indicator
     *  next to the magnitude-unit combo so the user can see how soon
     *  the next analysis will fire.
     *
     *  <p>Two regimes:
     *  <ul>
     *    <li><b>First frame</b> (before any analysis has completed):
     *        gauge fills as {@code writePos / fftLength} — we need a
     *        full window before we can run the first FFT.  At 87.5 %
     *        overlap on a 256 k FFT @ 48 kHz that's ~5.4 s.</li>
     *    <li><b>Subsequent frames</b>: gauge fills as
     *        {@code newSamples / hop}, where {@code hop = fftLength ×
     *        (1 − overlap)}.  At 87.5 % overlap on the same setup
     *        that's ~0.7 s.</li>
     *  </ul>
     */
    public double getNextFrameProgress() {
        SignalBuffer buf = (bufferSupplier == null) ? null : bufferSupplier.get();
        if (buf == null) return 0;
        Preferences prefs = Preferences.instance();
        int fftLength = prefs.getFftLength();
        if (fftLength < 8) return 0;
        long writePos = buf.getWritePos();
        if (!firstFrameDone) {
            // First-frame regime: need a complete window of FRESH
            // samples (captured after start / reset).  startWritePos
            // = −1 before the worker has registered its baseline.
            if (startWritePos < 0) return 0;
            long fresh = writePos - startWritePos;
            double f = (double) fresh / fftLength;
            if (f < 0) f = 0; else if (f > 1) f = 1;
            return f;
        }
        // Hop regime — newSamples toward the next hop.  When
        // lastAnalysisWritePos is still −1 (set true after start /
        // reset but before the next analysis runs), the gauge sits at
        // 100 % to indicate "ready to fire".
        if (lastAnalysisWritePos < 0) return 1;
        FftAnalyzer.Overlap overlap;
        try { overlap = FftAnalyzer.Overlap.valueOf(prefs.getFftOverlap()); }
        catch (IllegalArgumentException e) { overlap = FftAnalyzer.Overlap.PCT_0; }
        double hop = Math.max(1, fftLength * (1.0 - overlap.fraction));
        long  newSamples = writePos - lastAnalysisWritePos;
        if (newSamples <= 0) return 0;
        double f = newSamples / hop;
        return (f > 1) ? 1 : f;
    }

    /** Clears the completed-analyses counter and resumes the loop if it
     *  was paused by stop-after-N.  Fires a redraw so any visible counter
     *  is refreshed immediately.  Safe to call from any thread. */
    public void resetStatistics() {
        paused.set(false);
        completedAnalyses = 0;
        // Drop any retained spectrum and re-arm the fresh-data wait —
        // the user expects "Reset" to wipe the screen and start the
        // collection from 0 % again rather than blending old samples
        // into the next analysis.
        lastResult = null;
        firstFrameDone = false;
        lastAnalysisWritePos = -1;
        startWritePos = -1;
        if (redrawCallback != null && display != null && !display.isDisposed()) {
            display.asyncExec(redrawCallback);
        }
    }

    /** Worker-thread body.  Runs analysis on a background thread, posts
     *  the redraw via {@link Display#asyncExec} so the SWT thread isn't
     *  blocked by the FFT compute (which can take hundreds of
     *  milliseconds for 2²⁰-bin sizes).
     *
     *  <p>The sleep between analyses is derived from the configured
     *  overlap so the FFT cadence honours the user's choice:
     *  at 0 % overlap we wait for a full {@code fftLength} of new
     *  samples; at 87.5 % overlap we wait for {@code fftLength × 0.125}
     *  new samples — eight times faster.  This matches the standard
     *  meaning of overlap and gives the user perceptibly snappier
     *  updates when they crank it up. */
    private void workerLoop() {
        while (running) {
            int sleepMs = IDLE_TICK_MS;
            if (!paused.get()) {
                try {
                    sleepMs = doAnalysis();
                } catch (Throwable t) {
                    log.warn("FFT analysis tick failed: {}", t.getMessage(), t);
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
     *  size (8× faster at 87.5 % overlap than at 0 %).
     *  Returns {@link #IDLE_TICK_MS} when no analysis was possible
     *  (no buffer, not enough samples yet). */
    private int doAnalysis() {
        // Generator-state transition: wipe accumulated stats so the next
        // analysis starts with a clean slate.  Without this the averages
        // counter, retained spectrum, and progress gauge would all carry
        // over data captured under the previous generator state.
        boolean genActiveNow = (generatorActiveSupplier == null)
                || generatorActiveSupplier.getAsBoolean();
        if (lastGeneratorActive != null && lastGeneratorActive != genActiveNow) {
            resetStatistics();
        }
        lastGeneratorActive = genActiveNow;

        SignalBuffer buf = (bufferSupplier == null) ? null : bufferSupplier.get();
        if (buf == null) return IDLE_TICK_MS;
        int sampleRate = buf.getSampleRate();
        if (sampleRate <= 0) return IDLE_TICK_MS;

        Preferences prefs = Preferences.instance();
        int fftLength = prefs.getFftLength();
        if (fftLength < 8 || (fftLength & (fftLength - 1)) != 0) return IDLE_TICK_MS;  // must be power of 2

        // "forever" averages → use 2 frames per tick.  Otherwise use the
        // configured int.  We don't try to honour very high averages
        // counts within a single tick — each tick is its own window.
        double avgRaw = prefs.getFftAverages();
        int averages = Double.isInfinite(avgRaw) ? 2 : Math.max(1, (int) avgRaw);

        FftAnalyzer.WindowType window;
        try { window = FftAnalyzer.WindowType.valueOf(prefs.getFftWindow()); }
        catch (IllegalArgumentException e) { window = FftAnalyzer.WindowType.HANN; }

        FftAnalyzer.Overlap overlap;
        try { overlap = FftAnalyzer.Overlap.valueOf(prefs.getFftOverlap()); }
        catch (IllegalArgumentException e) { overlap = FftAnalyzer.Overlap.PCT_0; }

        // Samples needed for `averages` overlapped frames: fftLength stays
        // in every frame, hop is fftLength × (1 - overlap).
        double hop = fftLength * (1.0 - overlap.fraction);
        if (hop < 1) hop = 1;
        int hopSamples = (int) Math.max(1, Math.round(hop));
        int needed = (int) Math.ceil(fftLength + (averages - 1) * hop);
        needed = Math.min(needed, buf.getCapacity());

        // Gate the analysis on having a hop's worth of new samples since
        // the previous frame.  This is what gives the user the standard
        // "overlap speeds up the update rate" behaviour: at 87.5 %
        // overlap we fire every fftLength/8 samples, not every fftLength
        // samples.  The hop-wait converted to milliseconds also becomes
        // our sleep value, so the worker doesn't burn CPU spinning.
        long writePos = buf.getWritePos();
        if (startWritePos < 0) startWritePos = writePos;
        long fresh = writePos - startWritePos;
        if (!firstFrameDone) {
            // First frame after reset: wait for one FFT-window of
            // FRESH samples to accumulate.
            if (fresh < fftLength) {
                int waitMs = msForSamples((int)(fftLength - fresh), sampleRate);
                return Math.max(20, waitMs);
            }
        } else {
            // Subsequent frames: wait for one hop's worth of new
            // samples since the previous analysis read.
            if (lastAnalysisWritePos < 0) lastAnalysisWritePos = writePos;
            long newSamples = writePos - lastAnalysisWritePos;
            if (newSamples < hopSamples) {
                int waitMs = msForSamples(hopSamples - (int) newSamples, sampleRate);
                return Math.max(20, waitMs);
            }
        }
        // CLAMP the read window to fresh-only samples on EVERY tick
        // until the full averaging window has accumulated fresh data.
        // resetStatistics() clears the FFT state but the audio
        // SignalBuffer still holds the old samples, and without this
        // clamp readLatest(needed, …) pulls (needed − fresh) of them
        // back into the average — visible as the 1 kHz tone ghosting
        // for 2nd / 3rd / 4th analyses after the generator stops.
        // With clamping, each successive analysis adds one more fresh
        // frame to the average until naturally fresh >= needed.
        needed = (int) Math.min(needed, fresh);

        TriggerChannel channel = prefs.getFftChannel();
        boolean wantLeft  = (channel == TriggerChannel.L);
        // Reuse the worker's scratch buffer when its size matches
        // `needed` (the steady-state case once the averaging window has
        // filled).  During the ramp-up after a reset `needed` grows
        // hop-by-hop and we allocate a new exact-fit array per tick,
        // but those few ticks are cheap compared to the steady-state
        // allocation we'd otherwise be doing forever.  analyze()
        // processes the full array length, so the buffer must be
        // exactly `needed` long.
        float[] leftBuf  = null;
        float[] rightBuf = null;
        if (wantLeft) {
            if (reusableLeftBuf.length != needed) reusableLeftBuf = new float[needed];
            leftBuf = reusableLeftBuf;
        } else {
            if (reusableRightBuf.length != needed) reusableRightBuf = new float[needed];
            rightBuf = reusableRightBuf;
        }
        int got = buf.readLatest(needed, leftBuf, rightBuf);
        if (got < fftLength) return IDLE_TICK_MS;   // race: writePos moved but readLatest came up short

        float[] samples = wantLeft ? leftBuf : rightBuf;
        if (got < samples.length) {
            // Buffer didn't yet have `needed` samples; analyse what we got
            // (head-aligned to [0]).  Drop the trailing zero tail.
            float[] trimmed = new float[got];
            System.arraycopy(samples, 0, trimmed, 0, got);
            samples = trimmed;
        }

        // Pref value N means "calculate up to HN" → harmonicCount = N − 1
        // (because the analyzer's count is "number of harmonics from H2
        // upward").  Clamp the pref to the same minimum the UI enforces.
        int    calcMaxH = Math.max(9, prefs.getFftCalcMaxHarmonic()) - 1;
        double distMin  = prefs.isFftDistMinEnabled() ? prefs.getFftDistMinHz() : 0;
        double distMax  = prefs.isFftDistMaxEnabled() ? prefs.getFftDistMaxHz() : 0;
        boolean coherent = prefs.isFftCoherentAveraging();
        // When the generator is OFF, the "fundamental from generator" /
        // "manual fundamental" prefs are ignored so the analyser falls
        // back to auto-detecting from the spectrum — otherwise we'd be
        // locking onto a frequency the user isn't actually feeding in.
        boolean genActive = (generatorActiveSupplier == null)
                || generatorActiveSupplier.getAsBoolean();
        double fundRefDbV = genActive ? resolveFundRefDbV(prefs) : Double.NaN;
        double expectedFundHz = (genActive && prefs.isFftFundFromGenerator()
                                  && generatorFreqSupplier != null)
                ? generatorFreqSupplier.getAsDouble()
                : Double.NaN;

        FftAnalyzer.Result r;
        try {
            r = analyzer.analyze(samples, sampleRate, fftLength, calcMaxH,
                    window, overlap, distMin, distMax, coherent, fundRefDbV,
                    /*logSummary=*/ false, expectedFundHz);
        } catch (RuntimeException ex) {
            // Bad samples / size mismatch / etc.  Skip this tick and retry.
            log.warn("FFT analyze() failed: {}", ex.getMessage());
            return IDLE_TICK_MS;
        }
        // Anchor absolute dBV via the calibration when no manual
        // fundamental was supplied — without this the bin table shows
        // raw dBFS-shifted values and the user sees e.g. +5 dBV for a
        // 1 V_rms tone on a 1.79 V_rms FS ADC.
        applyDbvCalibration(r, prefs);
        // Atomic reference swap — paint thread sees either the previous
        // result or the new one, never a partially-constructed one.
        lastResult = r;
        completedAnalyses++;
        // Anchor the hop gauge at the buffer's CURRENT write position
        // (post-analyze), not the one captured at start of doAnalysis.
        // With high overlap (>= 75 %) the analyze itself takes longer
        // than a hop, so samples accumulated during the analyze were
        // making the next-frame gauge sit at 100 % immediately.  Now
        // the gauge starts at 0 % after every analysis and climbs as
        // fresh samples arrive — at the cost of a tiny reduction in
        // effective overlap (~the analyze duration), which is what the
        // user actually experiences anyway.
        lastAnalysisWritePos = buf.getWritePos();
        firstFrameDone = true;
        // Stop-after only fires when the user is in "forever" averages
        // mode AND has explicitly enabled the cap.  A finite N already
        // implements a moving window so the analysis is continuous;
        // applying stop-after there would have it stop mid-stream
        // even though the user just asked for moving-average behaviour.
        if (Double.isInfinite(avgRaw)
                && prefs.isFftStopAfterNEnabled()
                && completedAnalyses >= prefs.getFftStopAfterN()) {
            paused.set(true);
            // Tell the owner (FftPane) so it can flip the Record
            // button off and release its hold on the shared capture
            // — without this, the worker pauses but the visible
            // record state and the capture refcount stay engaged.
            Runnable cb = onStopAfterFired;
            if (cb != null && display != null && !display.isDisposed()) {
                display.asyncExec(() -> { if (!display.isDisposed()) cb.run(); });
            }
        }
        if (redrawCallback != null && display != null && !display.isDisposed()) {
            display.asyncExec(() -> {
                if (!display.isDisposed()) redrawCallback.run();
            });
        }
        // Next analysis runs once another hop has arrived.
        return msForSamples(hopSamples, sampleRate);
    }

    /** Translates a sample count into a sleep duration at the current
     *  sample rate, with a small floor so we don't drop to 0 ms when
     *  the hop fits comfortably in one tick. */
    private static int msForSamples(int samples, int sampleRate) {
        if (sampleRate <= 0 || samples <= 0) return 0;
        return (int) Math.ceil(1000.0 * samples / sampleRate);
    }

    /** Computes the dBV reference anchor passed into {@link FftAnalyzer}.
     *  The analyzer uses {@code fundRefDbV} as the user-supplied TRUE
     *  dBV of the fundamental (it locks the ratio denominator to that
     *  value).  In manual-fundamental mode we pass the user's value
     *  directly; otherwise we pass {@link Double#NaN} so the analyzer
     *  uses the measured {@code fundLinear} as its anchor — the ADC
     *  calibration is applied as a separate dBFS → dBV offset by
     *  {@link #applyDbvCalibration} after analyze returns. */
    private static double resolveFundRefDbV(Preferences prefs) {
        if (prefs.isFftManualFundEnabled()) {
            String unit = prefs.getFftManualFundUnit();
            double v = prefs.getFftManualFundVrms();
            if ("dBV".equalsIgnoreCase(unit)) return v;
            if ("mV" .equalsIgnoreCase(unit)) v *= 0.001;
            return (v > 0) ? 20.0 * Math.log10(v) : Double.NaN;
        }
        return Double.NaN;
    }

    /** Post-processes the analyzer result so absolute dBV is correct.
     *  Unconditionally re-anchors {@code fundRefDbV} to the ADC
     *  full-scale calibration when available — this is the dBV /
     *  dBFS offset used for EVERY non-fundamental bin (harmonics,
     *  noise floor, the spectrum trace).
     *
     *  <p>Manual-fundamental mode is intentionally NOT applied here:
     *  the user-entered value lives in {@link FftAnalyzer.Result#fundamentalTrueDbV}
     *  (set by the analyzer from the {@code fundRefDbV} input arg),
     *  and the view's fundamental-row formatter prefers
     *  {@code fundamentalTrueDbV} over {@code fundRefDbV}.  Keeping
     *  {@code fundRefDbV} on the calibration anchor means harmonics
     *  and noise still display in absolute dBV instead of inheriting
     *  the manual override — matches CLI behaviour. */
    private static void applyDbvCalibration(FftAnalyzer.Result r, Preferences prefs) {
        double fs = prefs.getAdcFsVoltageRms();
        if (fs > 0 && Double.isFinite(r.fundamentalDbFs)) {
            r.fundRefDbV = r.fundamentalDbFs + 20.0 * Math.log10(fs);
        }
    }
}
