package org.edgo.audio.measure.gui.fft;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.eclipse.swt.widgets.Display;
import org.edgo.audio.measure.cli.util.FreqRespCalHelper;
import org.edgo.audio.measure.cli.util.FreqRespCalibration;
import org.edgo.audio.measure.dsp.MainsCombFilter;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.MainsSuppression;
import org.edgo.audio.measure.enums.WindowType;
import org.edgo.audio.measure.fft.FftAnalyzer;
import org.edgo.audio.measure.fft.FftAnalyzer.FrameFftCache;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.GenChangeCause;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.preferences.Preferences;
import org.edgo.audio.measure.gui.sound.SignalBuffer;

import lombok.extern.log4j.Log4j2;

/**
 * Background analyser worker that owns the FFT compute thread and its
 * {@link FftAnalyzer} instance.  Holds none of the view's state — the
 * worker thread is fully decoupled from {@link FftView} via the bus:
 * each completed analysis is {@linkplain FftResult#deepCopy
 * deep-copied} and published on
 * {@link Events#FFT_RESULT_AVAILABLE}; subscribers (typically
 * {@link FftView}) keep the snapshot and paint from it without
 * touching anything the worker owns.
 *
 * <p>Lifecycle is parent-owned: {@link FftView} constructs one worker
 * and calls {@link #start} / {@link #stop} alongside its own visibility
 * and dispose lifecycle.
 */
@Log4j2
public final class FftAnalyzerWorker {

    /** Idle tick when there's no buffer / not enough fresh samples. */
    private static final int IDLE_TICK_MS = 250;
    /** Maximum sleep between analysis attempts. */
    private static final int MAX_SLEEP_MS = 500;

    private final Display  display;

    private final FftAnalyzer analyzer = new FftAnalyzer();

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

    /** Mains-hum comb, lazily built for the current sample rate.  Used
     *  only when {@code fftMainsSuppression == IIR_COMB}; re-tracks the
     *  mains frequency each tick and filters the captured window in place
     *  before the spectrum is computed. */
    private MainsCombFilter mainsComb;
    private int             mainsCombSampleRate;
    /** Notch −3 dB width (Hz) for the mains comb. */
    private static final double MAINS_NOTCH_BW_HZ = 2.0;
    private Thread worker;

    @SuppressWarnings("unused")
    private long startAnalyze;
    @SuppressWarnings("unused")
    private long startSendToUi;

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
    private final Consumer<Void> invalidateOnEvent = ignored -> recycleAndClearCache();
    /** Variant of {@link #invalidateOnEvent} for
     *  {@link Events#GENERATOR_SIGNAL_CHANGED}: inspects the payload's
     *  {@link GenChangeCause} and skips the cache flush when the cause
     *  is {@link GenChangeCause#FLL_TRIM}.  An FLL trim is a sub-Hz
     *  alignment of the generator with the FFT bin grid; the cached
     *  FFTs are still correct (the underlying samples haven't changed)
     *  and we want to KEEP averaging so the lock converges. */
    private final Consumer<GenChangeCause> invalidateOnGenChange = cause -> {
        if (cause == GenChangeCause.FLL_TRIM) return;
        recycleAndClearCache();
    };
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
    /** Fractional bins of the strong tones detected at restart (sorted
     *  ascending).  Length ≤ 1 ⇒ single-reference rotation (legacy path);
     *  length ≥ 2 ⇒ each tone's lobe is de-rotated by its OWN frequency so
     *  the accumulated spectrum keeps every fundamental at its true sub-bin
     *  position instead of locking it to the FFT grid.  Found from the
     *  spectrum itself, so it works for unknown / external multi-tone
     *  signals where the app can't know the frequencies up front. */
    private double[] accumToneKappa = new double[0];
    /** A spectral peak is its own de-rotation reference ("strong tone")
     *  when it rises at least this many dB above the noise floor.  Measured
     *  from the floor (not the strongest peak) so a very lopsided pair —
     *  e.g. one tone at 99.99 %, the other at 0.01 % (~80 dB down) — still
     *  gives BOTH tones a reference. */
    private static final double STRONG_TONE_FLOOR_DB = 20.0;
    /** Half-width (bins) of the lobe a tone's constant phase covers. */
    private static final int    TONE_LOBE_HALF_BINS = 16;
    /** Two peaks closer than this (bins) are merged into one tone. */
    private static final int    MIN_TONE_SEP_BINS   = 48;
    /** Cap on independent tone references. */
    private static final int    MAX_TONES           = 8;
    /** Config the accumulator was built for; mismatch ⇒ reset. */
    private int     accumFftSize;
    private boolean accumCoherent;
    /** True once at least one tick has contributed. */
    private boolean accumHasData;
    /** Tracks the previous tick's forever-mode flag so we can reset on
     *  transitions between finite-N and forever modes. */
    private boolean lastWasForever;

    /** Returns the mains comb, (re)building it when the sample rate
     *  changes.  Worker-thread only. */
    private MainsCombFilter mainsComb(int sampleRate) {
        if (mainsComb == null || mainsCombSampleRate != sampleRate) {
            mainsComb = new MainsCombFilter(sampleRate, MAINS_NOTCH_BW_HZ);
            mainsCombSampleRate = sampleRate;
        }
        return mainsComb;
    }

    /** Drops the cross-tick accumulator.  Safe to call from any thread —
     *  arrays are reassigned, not cleared in place. */
    private void resetAccumulator() {
        accumRe = null;
        accumIm = null;
        accumPow = null;
        accumFrames = 0;
        accumHasData = false;
        accumToneKappa = new double[0];
    }

    /** Adds the post-cal Result's spectrum to the cross-tick accumulator.
     *  For coherent mode, applies the time-shift phase rotation that aligns
     *  this tick's frame-0 with the accumulator's reference (so tones
     *  add coherently across ticks).  For incoherent mode, just sums
     *  power per bin.  Resets the accumulator transparently when fftSize
     *  or coherent flag changes between ticks. */
    private void accumulateIntoForeverBuffer(FftResult r,
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
            accumToneKappa         = coherent ? detectStrongTones(r) : new double[0];
            accumHasData = true;
        }

        int weight = Math.max(1, r.frameCount);
        if (coherent) {
            long delta = samplesAbsStart - accumRefSampleStart;
            if (accumToneKappa.length >= 2) {
                // Multi-tone: de-rotate each detected tone's lobe by its OWN
                // frequency (constant phase), so every fundamental keeps its
                // true position — no single reference, hence no grid-lock.
                accumulateMultiTone(r, N, weight, delta);
            } else {
                // Single reference: per-bin time-shift rotation tied to the one
                // detected fundamental (and, via the linear ramp, its harmonics).
                // Same formula as analyze()'s per-frame correction with f·step
                // replaced by the absolute sample delta from the reference.
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
                    accumRe[k] += rRe * corrRe - rIm * corrIm;
                    accumIm[k] += rRe * corrIm + rIm * corrRe;
                    double nextRe = corrRe * cosBase - corrIm * sinBase;
                    corrIm = corrRe * sinBase + corrIm * cosBase;
                    corrRe = nextRe;
                }
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

    /** Multi-tone cross-tick rotation: each detected strong tone's lobe is
     *  de-rotated by a CONSTANT phase equal to that tone's own inter-tick
     *  advance, so the lobe is preserved intact and the accumulated peak
     *  stays at the tone's true (sub-bin) frequency — no locking to the FFT
     *  grid.  Bins outside every tone lobe get the plain time-shift ramp,
     *  which leaves broadband noise to average down.  This is the honest
     *  "measure what was sampled" path for unknown / external multi-tone
     *  signals: the tones are found from the spectrum, not assumed. */
    private void accumulateMultiTone(FftResult r, int N, int weight, long delta) {
        int fftSize = r.fftSize;
        double rampSlope = -2.0 * Math.PI * delta / (double) fftSize;
        double stepRe = Math.cos(rampSlope);
        double stepIm = Math.sin(rampSlope);
        double phRe = 1.0, phIm = 0.0;   // plain time-shift phasor: exp(j·k·rampSlope)
        int nT = accumToneKappa.length;
        double[] cRe = new double[nT];
        double[] cIm = new double[nT];
        int[]    lo  = new int[nT];
        int[]    hi  = new int[nT];
        for (int t = 0; t < nT; t++) {
            double ang = -2.0 * Math.PI * delta * accumToneKappa[t] / (double) fftSize;
            cRe[t] = Math.cos(ang);
            cIm[t] = Math.sin(ang);
            int k0 = (int) Math.round(accumToneKappa[t]);
            lo[t] = Math.max(0,     k0 - TONE_LOBE_HALF_BINS);
            hi[t] = Math.min(N - 1, k0 + TONE_LOBE_HALF_BINS);
        }
        int curT = 0;
        for (int k = 0; k < N; k++) {
            while (curT < nT && k > hi[curT]) curT++;
            double rotRe, rotIm;
            if (curT < nT && k >= lo[curT]) { rotRe = cRe[curT]; rotIm = cIm[curT]; }
            else                            { rotRe = phRe;      rotIm = phIm;      }
            double rRe = r.re[k] * weight;
            double rIm = r.im[k] * weight;
            accumRe[k] += rRe * rotRe - rIm * rotIm;
            accumIm[k] += rRe * rotIm + rIm * rotRe;
            double nextRe = phRe * stepRe - phIm * stepIm;
            phIm = phRe * stepIm + phIm * stepRe;
            phRe = nextRe;
        }
    }

    /** Finds the fractional bins of the "strong" tones in {@code r}'s
     *  spectrum — local maxima within {@link #STRONG_TONE_REL_DB} dB of the
     *  strongest peak, merged within {@link #MIN_TONE_SEP_BINS} and refined
     *  to sub-bin by parabolic interpolation, sorted ascending.  Detection
     *  from the spectrum (not commanded frequencies) is deliberate: the
     *  generator may be external, so the FFT is the only thing that knows
     *  what was actually received. */
    private double[] detectStrongTones(FftResult r) {
        double[] db = r.amplitudeDbFs;
        if (db == null) return new double[0];
        int halfSize = r.fftSize / 2;
        if (halfSize < 4) return new double[0];
        double thresh = noiseFloorDb(db, halfSize) + STRONG_TONE_FLOOR_DB;
        // Keep the strongest MAX_TONES local maxima above the floor, merging
        // peaks closer than MIN_TONE_SEP_BINS (one tone's lobe).  Selecting by
        // strength (not scan order) keeps the real tones even when 1/f or the
        // HF-rise noise also clears the threshold.
        int[]    bins = new int[MAX_TONES];
        double[] lvls = new double[MAX_TONES];
        int count = 0;
        for (int k = 2; k < halfSize; k++) {
            if (db[k] < thresh) continue;
            if (!(db[k] > db[k - 1] && db[k] >= db[k + 1])) continue;   // local max
            double lvl = db[k];
            int near = -1;
            for (int t = 0; t < count; t++) {
                if (Math.abs(k - bins[t]) < MIN_TONE_SEP_BINS) { near = t; break; }
            }
            if (near >= 0) {
                if (lvl > lvls[near]) { bins[near] = k; lvls[near] = lvl; }
            } else if (count < MAX_TONES) {
                bins[count] = k; lvls[count] = lvl; count++;
            } else {
                int weakest = 0;
                for (int t = 1; t < count; t++) if (lvls[t] < lvls[weakest]) weakest = t;
                if (lvl > lvls[weakest]) { bins[weakest] = k; lvls[weakest] = lvl; }
            }
        }
        // Sort the kept tones by bin ascending (needed by the region walk).
        for (int i = 1; i < count; i++) {
            int b = bins[i]; double l = lvls[i]; int j = i - 1;
            while (j >= 0 && bins[j] > b) { bins[j + 1] = bins[j]; lvls[j + 1] = lvls[j]; j--; }
            bins[j + 1] = b; lvls[j + 1] = l;
        }
        double[] out = new double[count];
        for (int i = 0; i < count; i++) out[i] = refineBin(db, bins[i], halfSize);
        return out;
    }

    /** 10th-percentile bin level (dB) via a coarse 1-dB histogram — a
     *  leakage-immune estimate of the broadband noise floor, cheap enough
     *  to run on the full half-spectrum without sorting. */
    private double noiseFloorDb(double[] db, int halfSize) {
        final int LO = -320, NB = 360;   // 1-dB buckets covering [-320, 40) dB
        int[] hist = new int[NB];
        int total = 0;
        for (int k = 1; k <= halfSize; k++) {
            int b = (int) Math.floor(db[k]) - LO;
            if (b < 0) b = 0; else if (b >= NB) b = NB - 1;
            hist[b]++; total++;
        }
        if (total == 0) return -160.0;
        int target = total / 10;
        int cum = 0;
        for (int b = 0; b < NB; b++) {
            cum += hist[b];
            if (cum >= target) return LO + b;
        }
        return -160.0;
    }

    /** 3-point parabolic peak interpolation (on dB) around bin {@code k}. */
    private double refineBin(double[] db, int k, int halfSize) {
        if (k <= 1 || k >= halfSize) return k;
        double yl = db[k - 1], yc = db[k], yr = db[k + 1];
        double denom = yl - 2.0 * yc + yr;
        double d = (Math.abs(denom) < 1e-12) ? 0.0 : 0.5 * (yl - yr) / denom;
        if (d < -0.5) d = -0.5;
        if (d >  0.5) d =  0.5;
        return k + d;
    }

    /** Mutates {@code r}'s re/im/amplitudeDbFs/phaseDeg arrays in place to
     *  reflect the cumulative accumulator average.  After this, the caller
     *  should run {@link FftAnalyzer#recomputeStats} so the fundamental,
     *  harmonic table, THD/SNR are re-derived from the cumulative spectrum
     *  (the per-tick stats were just one tick's contribution). */
    private void overlayAccumulatorOnto(FftResult r) {
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
     * @param display SWT display used to marshal {@link Events#FFT_RESULT_AVAILABLE}
     *                publishes back to the UI thread.  Subscribers to that
     *                event (typically the FFT view's {@code redraw()}) are
     *                where the post-analysis paint hook now lives.
     */
    public FftAnalyzerWorker(Display display) {
        this.display = display;
        analyzer.setFrameCache(frameFftCacheImpl);
    }

    // ─── External wiring ────────────────────────────────────────────────────

    public void setBuffer(SignalBuffer b)       { this.buffer = b; }
    public SignalBuffer getBuffer()              { return buffer; }
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
        completedAnalyses     = 0;
        resetStatistics();
        paused.set(false);
        MessageBus.instance().subscribe(Events.GENERATOR_SIGNAL_CHANGED, invalidateOnGenChange);
        MessageBus.instance().subscribe(Events.FFT_CALIBRATION_CHANGED, invalidateOnEvent);
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
        MessageBus.instance().unsubscribe(Events.GENERATOR_SIGNAL_CHANGED, invalidateOnGenChange);
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
        startAnalyze = System.nanoTime();
        // Generator-state transition: wipe accumulated stats so the next
        // analysis starts with a clean slate.
        boolean genActiveNow = isGeneratorActive();
        if (lastGeneratorActive != null && lastGeneratorActive != genActiveNow) {
            resetStatistics();
            // No fresh Result — just nudge the UI to repaint the (now
            // empty) chart.  Subscribers receive null and just call
            // redraw / update.
            // Do not erase only if generator is toggled on/off
            /*if (display != null && !display.isDisposed()) {
                display.asyncExec(() ->
                        MessageBus.instance().publish(Events.FFT_RESULT_AVAILABLE));
            }*/
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
        // Mains-suppression also enters the fingerprint: toggling it changes
        // the samples fed to analyze, so cached frames / the accumulator must
        // be dropped.  When active, the per-frame cache is bypassed entirely
        // (see below) because block-resetting the comb each tick makes a
        // frame's samples depend on its offset within the window, breaking
        // the cache's "same absStart ⇒ same frame" assumption.
        boolean mainsSuppress = MainsSuppression.fromNameOr(
                prefs.getFftMainsSuppression(), MainsSuppression.NONE) == MainsSuppression.IIR_COMB;
        long cfgFingerprint = fftLength;
        cfgFingerprint = 31 * cfgFingerprint + sampleRate;
        cfgFingerprint = 31 * cfgFingerprint + window.ordinal();
        cfgFingerprint = 31 * cfgFingerprint + (wantLeft ? 1 : 0);
        cfgFingerprint = 31 * cfgFingerprint + (mainsSuppress ? 1 : 0);
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
        // The coherent de-rotation locks onto the detected fundamental, so
        // in dual-tone the hint must point at a real tone (the lower one).
        // Hinting the single-tone frequency — usually nowhere near F1/F2 —
        // pegs the reference to a noise bin that jitters tick-to-tick, which
        // smears BOTH tones and makes the measured frequencies (and Δf
        // readout) swing wildly even though the generator never moves.
        boolean dualTone = "DUAL_TONE".equalsIgnoreCase(prefs.getGenSignalForm());
        double expectedFundHz = (genActive && prefs.isFftFundFromGenerator())
                ? (dualTone
                    ? Math.min(prefs.getGenDualToneFreq1Hz(), prefs.getGenDualToneFreq2Hz())
                    : prefs.getGenFrequencyHz())
                : Double.NaN;

        // Mains suppression: track the live mains frequency on a short
        // prefix (a second is ample for a mHz-accurate estimate), then comb-
        // filter the whole window in place before it's analysed.  The comb is
        // reset each tick so the block is filtered deterministically; its
        // start transient is tapered away by the FFT window.  With the filter
        // on, the per-frame cache is bypassed (frame samples are no longer
        // position-independent across overlapping ticks).
        if (mainsSuppress) {
            MainsCombFilter comb = mainsComb(sampleRate);
            comb.track(samples, Math.min(samples.length, sampleRate));
            comb.reset();
            comb.process(samples, samples.length);
        }
        analyzer.setFrameCache(mainsSuppress ? null : frameFftCacheImpl);

        analyzer.setSamplesAbsStart(samplesAbsStart);
        // Dual-tone: hint the upper tone too, so the analyzer produces an
        // honest sub-bin frequency estimate for it from a clean frame — the
        // dual-tone readout then reports true frequencies instead of reading
        // peaks off the coherently-collapsed average (see ImdAnalyzer).
        analyzer.setSecondToneHintHz(
                (genActive && prefs.isFftFundFromGenerator() && dualTone)
                        ? Math.max(prefs.getGenDualToneFreq1Hz(), prefs.getGenDualToneFreq2Hz())
                        : Double.NaN);
        // Dual tone breaks the single-sine R-invariant glitch test, so skip
        // it (it would otherwise flag nearly every sample and self-invalidate).
        analyzer.setMultiTone(dualTone);
        FftResult r;
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
        // reflect the corrected spectrum.  Each entry carries its own
        // "with noise" flag (set per-row in the Load-calibration tab):
        // when true, this entry's correction applies to every FFT bin
        // including the noise floor; when false, only the fundamental
        // + harmonic bin neighbourhoods are corrected.
        List<FftCalibrationStore.Entry> calEntries =
                FftCalibrationStore.instance().getEntries();
        if (!calEntries.isEmpty()) {
            // Snapshot pre-correction peaks BEFORE the in-place mutate
            // so the view can draw "before" dots next to the corrected
            // "after" ones.
            r.preCorrectionPeaks = FreqRespCalHelper.capturePreCorrectionPeaks(r);
            for (FftCalibrationStore.Entry e : calEntries) {
                FreqRespCalibration calForChan = wantLeft
                        ? e.getCalibration().left()
                        : e.getCalibration().right();
                FreqRespCalHelper.applyCompensationInPlace(
                        r, calForChan, e.isWithNoise());
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

        // Lift the FINAL spectrum onto the dBV scale — done here, after
        // every mutation (cal cascade + forever-mode overlay +
        // recomputeStats) so {@code amplitudeDbV} reflects exactly the
        // spectrum the view shows, not a stale single-tick snapshot.
        // This is the SOURCE OF TRUTH for voltage-based downstream
        // analysis (ImdAnalyzer / TD+N / IMD %); those consumers never
        // touch {@code amplitudeDbFs}, only {@code amplitudeDbV}.  The
        // offset is a pure hardware-calibration constant, cached on the
        // Result so display code that converts dBV ↔ dBFs reuses it
        // without recomputing {@code log10} per pixel.
        if (fs > 0) {
            double anchor = 20.0 * Math.log10(fs);
            r.dbvOffsetDb = anchor;
            int nbv = r.amplitudeDbFs.length;
            if (r.amplitudeDbV == null || r.amplitudeDbV.length != nbv) {
                r.amplitudeDbV = new double[nbv];
            }
            for (int i = 0; i < nbv; i++) {
                r.amplitudeDbV[i] = r.amplitudeDbFs[i] + anchor;
            }
        } else {
            r.amplitudeDbV = null;
            r.dbvOffsetDb = 0.0;
        }

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
        //log.warn("FFT analyse took {} ms", (double)(System.nanoTime() - startAnalyze) / 1_000_000);
        if (foreverMode
                && prefs.isFftStopAfterNEnabled()
                && completedAnalyses >= prefs.getFftStopAfterN()) {
            paused.set(true);
            if (display != null && !display.isDisposed()) {
                display.asyncExec(() ->
                        MessageBus.instance().publish(Events.FFT_RECORDING_AUTO_STOPPED));
            }
        }
        // Hand off to the UI: deep-copy the freshly-filled result so
        // the worker can mutate its local {@code r} on the next tick
        // without the view (which keeps the snapshot it received) ever
        // seeing a torn read.
        publishResult(r);
        return msForSamples(hopSamples, sampleRate);
    }

    /** Deep-copies {@code newSlot} on the worker thread and publishes
     *  the snapshot on {@link Events#FFT_RESULT_AVAILABLE} via
     *  {@link Display#asyncExec} so subscribers receive the payload on
     *  the UI thread.  The copy decouples the worker (which may keep
     *  mutating its working {@code r}) from {@link FftView} (which
     *  paints from the published snapshot for many frames). */
    private void publishResult(FftResult newSlot) {
        if (display == null || display.isDisposed()) return;
        FftResult clone = newSlot/* .deepCopy()*/;
        startSendToUi = System.nanoTime();
        display.asyncExec(() -> {
            //log.warn("FFT result achieved asyncExec in {} ms", (double)(System.nanoTime() - startSendToUi) / 1_000_000);
            try {
                MessageBus.instance().publish(Events.FFT_RESULT_AVAILABLE, clone);
            } catch(Exception e) {
                log.error("Can't dispatch Events.FFT_RESULT_AVAILABLE", e);
            }
        });
        // Force the SWT main loop to wake from Display.sleep() if it was
        // dozing.  asyncExec already posts a wake message but on Windows
        // the message queue can drop wakes under load — wake() is the
        // belt-and-suspenders nudge.
        display.wake();
    }

    public void uiGotResult() {
        //log.warn("FFT result achieved UI in {} ms", (double)(System.nanoTime() - startSendToUi) / 1_000_000);
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
