package org.edgo.audio.measure.fft;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.WindowType;
import org.edgo.audio.measure.sound.AudioBackend;

import lombok.extern.log4j.Log4j2;

/**
 * FFT-based audio analyzer: coherent averaging, fundamental detection, THD,
 * THD+N (IEC 61672:2003 A-weighting), SNR.
 *
 * <p>Supports configurable overlap (0 %, 50 %, 75 %, 87.5 %, 93.75 %) and
 * configurable window function (Rectangular, Hann, Blackman-Harris 4/7,
 * Flat-top, Dolph-Chebyshev 150 dB / 200 dB).
 */
@Log4j2
public class FftAnalyzer {

    /** Cached window-function table — sized to {@link #cachedWindowSize}
     *  for {@link #cachedWindowType}.  analyze() is called repeatedly on
     *  the FFT worker thread with the same (fftSize, windowType) pair, so
     *  reusing this table avoids both the per-call allocation (8 MB at
     *  fftSize = 1 M) AND the ~1 M Math.cos()/Math.sinh() evaluations
     *  that rebuilding it would cost. */
    private double[]   cachedWindow;
    private int        cachedWindowSize;
    private WindowType cachedWindowType;

    /** Returns the cached window table for {@code (N, type)}, rebuilding
     *  it via {@link #buildWindow} only when the (size, type) pair
     *  changes. */
    private double[] getCachedWindow(int N, WindowType type) {
        if (cachedWindow != null && cachedWindowSize == N && cachedWindowType == type) {
            return cachedWindow;
        }
        cachedWindow     = buildWindow(N, type);
        cachedWindowSize = N;
        cachedWindowType = type;
        return cachedWindow;
    }

    // =========================================================================
    // Per-frame FFT cache (optional; populated by the caller)
    // =========================================================================

    /** Optional per-frame raw windowed-FFT cache.  The caller (typically
     *  the GUI worker) provides an implementation keyed by absolute
     *  sample-start position; the analyser consults it before every
     *  per-frame FFT and stores fresh results back.  This avoids
     *  re-FFT-ing the same raw frame across overlapping sliding-window
     *  ticks (the dominant cost at large fftSize × N). */
    public interface FrameFftCache {
        /** If a cached result exists for {@code (absStart, fftSize)},
         *  copy its real and imaginary parts into {@code outRe} /
         *  {@code outIm} (length = fftSize) and return {@code true}.
         *  Otherwise return {@code false}. */
        boolean tryFill(long absStart, int fftSize, double[] outRe, double[] outIm);

        /** Stores a copy of {@code (re, im)} (length = fftSize) for the
         *  key {@code (absStart, fftSize)}.  The cache MUST copy — the
         *  caller reuses the input arrays. */
        void put(long absStart, int fftSize, double[] re, double[] im);
    }

    /** Cache instance to consult during {@link #analyze}; {@code null}
     *  disables caching (CLI default).  Set by the caller via
     *  {@link #setFrameCache}; copied into a local at the top of
     *  {@code analyze} so changes mid-call have no effect. */
    private FrameFftCache frameCache;
    /** Absolute sample-start position of {@code samples[0]} in the
     *  caller's stream, used to key the cache.  Only meaningful when
     *  {@link #frameCache} is non-null. */
    private long samplesAbsStart;

    /** Wires (or clears) the per-frame FFT cache.  Call with
     *  {@code null} to disable caching for the next {@code analyze}. */
    public void setFrameCache(FrameFftCache cache) {
        this.frameCache = cache;
    }

    /** Sets the absolute sample-start position of the next
     *  {@code analyze} call's {@code samples[0]}.  Used as the cache
     *  key offset.  Ignored when {@link #frameCache} is null. */
    public void setSamplesAbsStart(long absStart) {
        this.samplesAbsStart = absStart;
    }

    /** Optional second-tone frequency hint (Hz) for dual-/multi-tone
     *  signals; {@link Double#NaN} disables it.  When set, {@code analyze}
     *  also produces a sub-bin frequency estimate for this tone — via the
     *  same clean-frame parabolic / phase method used for the fundamental
     *  — so a dual-tone readout reports its TRUE frequency rather than a
     *  peak read off the coherently-collapsed average. */
    private double secondToneHintHz = Double.NaN;

    /** Sets (or clears, with NaN) the second-tone frequency hint for the
     *  next {@code analyze}. */
    public void setSecondToneHintHz(double hz) {
        this.secondToneHintHz = hz;
    }

    // =========================================================================
    // Reusable scratch buffers
    // =========================================================================

    /** Per-fftSize scratch arrays that are purely internal to one
     *  {@code analyze} call — they are filled in, consumed, and then
     *  discarded before the {@link Result} is returned (i.e., nothing
     *  in the Result holds a reference to them).  Caching them as
     *  instance fields and re-using them across calls eliminates the
     *  10 × fftSize × 8 B allocation per analyze (≈ 80 MB at fftSize=1 M,
     *  ≈ 320 MB at 4 M).  Lazily (re-)allocated when fftSize changes. */
    private double[] scratchF0Re, scratchF0Im;
    private double[] scratchSumRe, scratchSumIm;
    private double[] scratchFrameRe, scratchFrameIm;
    private double[] scratchS0Re, scratchS0Im;
    private double[] scratchS1Re, scratchS1Im;
    /** Half-size scratch for amplitudes — used only inside one
     *  {@code analyze} call by the harmonic-detection and noise-floor
     *  paths; never returned. */
    private double[] scratchAmplLinear;

    /** Returns {@code current} if it has the requested length; else
     *  allocates a fresh array.  Caller is responsible for any required
     *  zero-fill (most call sites overwrite every cell, so no fill is
     *  needed; accumulators must explicitly {@code Arrays.fill(0)}). */
    private double[] scratchOrAlloc(double[] current, int size) {
        if (current != null && current.length == size) return current;
        return new double[size];
    }

    /** Tries the cache for the windowed FFT of the frame starting at
     *  local sample offset {@code localStart}.  On miss, windows +
     *  FFTs from {@code samples} and stores the result.  Always leaves
     *  {@code re} / {@code im} populated with the result. */
    private void cachedFrameFft(float[] samples, int localStart, int fftSize,
                                double[] window, double[] re, double[] im) {
        long absStart = samplesAbsStart + localStart;
        if (frameCache != null && frameCache.tryFill(absStart, fftSize, re, im)) {
            return;
        }
        for (int n = 0; n < fftSize; n++) {
            re[n] = samples[localStart + n] * window[n];
            im[n] = 0.0;
        }
        Fft.forward(re, im);
        if (frameCache != null) {
            frameCache.put(absStart, fftSize, re, im);
        }
    }


    // =========================================================================
    // Result
    // =========================================================================

    /** Container for all FFT analysis outputs.  Static inner class so each
     *  {@code Result} instance is independent of the {@link FftAnalyzer}
     *  instance that produced it — important for the FFT worker's
     *  double-buffered hand-off, where one analyser keeps emitting fresh
     *  results while previous ones are still on the paint thread. */
    public class Result {

        static final long serialVersionUID = 42L;

        /** FFT frame length (power of 2). */
        public int fftSize;
        /** Sample rate of the analyzed signal in Hz. */
        public int sampleRate;
        /** Number of frames that were coherently averaged. */
        public int frameCount;
        /** Frequency resolution in Hz per bin (= sampleRate / fftSize). */
        public double freqResolution;
        /** Window function used. */
        public WindowType windowType;
        /** FftOverlap used. */
        public FftOverlap overlap;

        // Single-sided spectrum, bins 0 … fftSize/2.  Arrays are reused
        // across analysis ticks via the pool; {@link #ensureArrays} grows
        // them when the FFT length changes.
        public double[] amplitudeDbFs;
        /** Per-bin amplitude in dBV (= dBFs + 20·log10(adcFsVoltageRms)).
         *  Populated by the analyser worker once the ADC full-scale
         *  RMS calibration is known.  This is the SOURCE OF TRUTH for
         *  voltage-based downstream analysis (e.g. {@code ImdAnalyzer})
         *  — {@code amplitudeDbFs} stays around only as a display
         *  alternative.  {@code null} when the ADC calibration
         *  hasn't been set. */
        public double[] amplitudeDbV;
        /** {@code 20·log10(adcFsVoltageRms)} — the constant offset
         *  between dBFs and dBV scales (dBV = dBFs + dbvOffsetDb).
         *  Cached once per analysis so consumers that need to convert
         *  between scales for display don't recompute the {@code log}
         *  per pixel / per row.  {@code 0} when the ADC calibration
         *  isn't set (i.e. dBFs == dBV in that case). */
        public double dbvOffsetDb;
        public double[] phaseDeg;
        public double[] re;
        public double[] im;

        // Fundamental
        public int    fundamentalBin;
        public double fundamentalHz;
        /** Phase-difference refined frequency in Hz — sub-bin accurate (~1e-5 bin). */
        public double fundamentalHzRefined;
        /** Sub-bin refined frequency (Hz) of the second tone in a dual-/
         *  multi-tone signal, estimated by the same clean-frame method as
         *  {@link #fundamentalHzRefined}.  {@link Double#NaN} for single-tone
         *  (no second-tone hint was supplied). */
        public double fundamental2HzRefined = Double.NaN;
        public double fundamentalDbFs;
        public double fundamentalLinear;

        // Harmonics (index 0 = 2nd harmonic, …)
        public int      harmonicCount;
        public int[]    harmonicBins;
        public double[] harmonicHz;
        public double[] harmonicDbFs;
        public double[] harmonicPct;

        // Metrics — non-final so post-processing (e.g. ADC/frequency response correction) can mutate.
        public double thdPct;
        public double thdDb;
        public double thdNDb;
        public double snrDb;
        /** Unweighted SINAD: 10·log10(refLin² / (noisePower + Σ harmonic power)) — basis for ENOB. */
        public double sinadDb;
        /** Lower bound of the frequency range used for SNR (Hz); 0 = no limit. */
        public double snrFreqMin;
        /** Upper bound of the frequency range used for SNR (Hz); 0 = no limit. */
        public double snrFreqMax;
        /** True = coherent (complex) averaging; false = incoherent (power) averaging. */
        public boolean coherentAveraging;
        /** Sum of amplLinear[k]^2 for noise bins inside the SNR band (unweighted). */
        public double noisePower;
        /** A-weighted noise+distortion power (all non-fundamental bins, no band limit). */
        public double awNoisePower;
        /** Average noise floor: RMS amplitude of a single noise bin converted to dBFS. */
        public double avgNoiseFloorDbFs;
        /** Pre-correction (BLUE-dot) snapshot of fundamental + harmonic
         *  (freq, dBFS) pairs, paired with this Result so they publish
         *  atomically together via {@code lastResult = r}.  Lives in
         *  Result rather than as a separate volatile field on the worker
         *  to avoid a tearing race where the view reads "new pre / old
         *  r" and renders BLUE based on a future tick's cumulative data
         *  while RED still reflects the current tick — visible as the
         *  BLUE dot jumping every few ticks even when the cal lookup
         *  itself is bit-stable.
         *  Layout: {@code [0] = freqs[], [1] = dBFs[]} with index 0
         *  holding the fundamental and indices 1..harmonicCount holding
         *  H2..HN.  {@code null} when no calibration is loaded. */
        public double[][] preCorrectionPeaks;
        /**
         * Half-width of the dynamic fundamental exclusion zone in Hz (one side).
         * For a clean recording this is a few Hz; a signal interruption causes
         * spectral splatter that broadens this to hundreds or thousands of Hz.
         */
        public double fundamentalDynExclusionHz;
        /**
         * Reference dBV for the fundamental.  When set, dBV is shown for every bin
         * as {@code amplitudeDbFs[k] + (fundRefDbV - fundamentalDbFs)}; {@link Double#NaN}
         * suppresses the dBV scale.  Sources, in priority order:
         *   1. ADC full-scale voltage (from the cal CSV header or {@code --adc-fs-vrms}):
         *      {@code fundRefDbV = fundamentalDbFs + 20·log10(adcFsVoltageRms)} —
         *      gives every bin its calibrated absolute dBV (e.g. -82 dBFS → -76.93 dBV
         *      with FS = 1.7931 V_rms).  This is the preferred source.
         *   2. {@code --fund-v}/{@code --fund-dbv}: legacy, treats the fundamental's
         *      dBV as fixed at the user-stated value.  Only used when the calibrated
         *      ADC voltage is unknown.
         * Mutable so post-processing (e.g. frequency response compensation) can refresh it once
         * the corrected fundamentalDbFs is known.
         */
        public double fundRefDbV;
        /**
         * User-supplied true fundamental dBV (from {@code --fund-v} / {@code --fund-dbv}),
         * preserved verbatim and never overwritten by post-processing.  When set:
         * <ul>
         *   <li>THD / SNR / THD+N / harmonic % use this as the ratio denominator,
         *       so an external twin-T notch on H1 cannot poison the metrics.</li>
         *   <li>The chart's fundamental peak marker and info-table dBV column
         *       show this value instead of the (notched-and-cal-converted)
         *       {@code fundamentalDbFs + dbFsToDbV}.</li>
         * </ul>
         * The global dBFS→dBV offset for spectrum/harmonics still comes from
         * {@code fundRefDbV} (cal-CSV-derived when available), which is the
         * correct anchor for bins outside the notch.  {@link Double#NaN} when
         * no user override is supplied.
         */
        public double fundamentalTrueDbV;

        /** Empty constructor used by the {@link ResultPool} to allocate
         *  reusable slots.  All fields stay at Java defaults until the
         *  worker calls {@link #ensureArrays} and the analyzer fills the
         *  result via direct field writes. */
        public Result() { }

        /** Ensures the bin / harmonic arrays are sized for the next
         *  analysis.  Reuses the existing arrays when their length
         *  already matches; otherwise reallocates (FFT-length change
         *  in prefs or a new pool slot that's never been filled).
         *  Called from {@link FftAnalyzer#analyze} before the bin
         *  arrays are written so the analyzer can use {@code re} /
         *  {@code im} / {@code amplitudeDbFs} / {@code phaseDeg}
         *  directly as its output buffers — no per-tick allocation. */
        public void ensureArrays(int binCount, int harmonicCount) {
            if (amplitudeDbFs == null || amplitudeDbFs.length != binCount) {
                amplitudeDbFs = new double[binCount];
                phaseDeg      = new double[binCount];
                re            = new double[binCount];
                im            = new double[binCount];
            }
            if (harmonicBins == null || harmonicBins.length != harmonicCount) {
                harmonicBins  = new int   [harmonicCount];
                harmonicHz    = new double[harmonicCount];
                harmonicDbFs  = new double[harmonicCount];
                harmonicPct   = new double[harmonicCount];
            }
        }

        /** Returns an independent copy of this result.  All scalar
         *  fields are copied by value; every array is cloned so a
         *  later in-place mutation of the source (e.g. cal cascade
         *  re-write on the worker thread) doesn't perturb the copy.
         *  Use when a consumer needs a stable snapshot decoupled from
         *  the live worker tick — e.g. a screenshot, a frozen
         *  comparison reference, or a paint that may outlive the next
         *  worker write. */
        public Result deepCopy() {
            Result c = new Result();
            c.fftSize                    = fftSize;
            c.sampleRate                 = sampleRate;
            c.frameCount                 = frameCount;
            c.freqResolution             = freqResolution;
            c.windowType                 = windowType;
            c.overlap                    = overlap;
            c.amplitudeDbFs              = amplitudeDbFs != null ? amplitudeDbFs.clone() : null;
            c.amplitudeDbV               = amplitudeDbV  != null ? amplitudeDbV.clone()  : null;
            c.dbvOffsetDb                = dbvOffsetDb;
            c.phaseDeg                   = phaseDeg      != null ? phaseDeg.clone()      : null;
            c.re                         = re            != null ? re.clone()            : null;
            c.im                         = im            != null ? im.clone()            : null;
            c.fundamentalBin             = fundamentalBin;
            c.fundamentalHz              = fundamentalHz;
            c.fundamentalHzRefined       = fundamentalHzRefined;
            c.fundamental2HzRefined      = fundamental2HzRefined;
            c.fundamentalDbFs            = fundamentalDbFs;
            c.fundamentalLinear          = fundamentalLinear;
            c.harmonicCount              = harmonicCount;
            c.harmonicBins               = harmonicBins  != null ? harmonicBins.clone()  : null;
            c.harmonicHz                 = harmonicHz    != null ? harmonicHz.clone()    : null;
            c.harmonicDbFs               = harmonicDbFs  != null ? harmonicDbFs.clone()  : null;
            c.harmonicPct                = harmonicPct   != null ? harmonicPct.clone()   : null;
            c.thdPct                     = thdPct;
            c.thdDb                      = thdDb;
            c.thdNDb                     = thdNDb;
            c.snrDb                      = snrDb;
            c.sinadDb                    = sinadDb;
            c.snrFreqMin                 = snrFreqMin;
            c.snrFreqMax                 = snrFreqMax;
            c.coherentAveraging          = coherentAveraging;
            c.noisePower                 = noisePower;
            c.awNoisePower               = awNoisePower;
            c.avgNoiseFloorDbFs          = avgNoiseFloorDbFs;
            c.fundamentalDynExclusionHz  = fundamentalDynExclusionHz;
            c.fundRefDbV                 = fundRefDbV;
            c.fundamentalTrueDbV         = fundamentalTrueDbV;
            // 2-D array: clone the outer array AND each non-null row so
            // the copy can be mutated independently of the source.
            if (preCorrectionPeaks != null) {
                double[][] src = preCorrectionPeaks;
                double[][] dst = new double[src.length][];
                for (int i = 0; i < src.length; i++) {
                    dst[i] = src[i] != null ? src[i].clone() : null;
                }
                c.preCorrectionPeaks = dst;
            }
            return c;
        }

        Result(int fftSize, int sampleRate, int frameCount, double freqResolution,
               WindowType windowType, FftOverlap overlap,
               double[] amplitudeDbFs, double[] phaseDeg, double[] re, double[] im,
               int fundamentalBin, double fundamentalHz, double fundamentalHzRefined,
               double fundamentalDbFs, double fundamentalLinear,
               int harmonicCount, int[] harmonicBins, double[] harmonicHz,
               double[] harmonicDbFs, double[] harmonicPct,
               double thdPct, double thdDb, double thdNDb, double snrDb,
               double snrFreqMin, double snrFreqMax, boolean coherentAveraging,
               double noisePower, double awNoisePower, double fundRefDbV,
               double avgNoiseFloorDbFs, double fundamentalDynExclusionHz) {
            this.fftSize           = fftSize;
            this.sampleRate        = sampleRate;
            this.frameCount        = frameCount;
            this.freqResolution    = freqResolution;
            this.windowType        = windowType;
            this.overlap           = overlap;
            this.amplitudeDbFs     = amplitudeDbFs;
            this.phaseDeg          = phaseDeg;
            this.re                = re;
            this.im                = im;
            this.fundamentalBin        = fundamentalBin;
            this.fundamentalHz         = fundamentalHz;
            this.fundamentalHzRefined  = fundamentalHzRefined;
            this.fundamentalDbFs       = fundamentalDbFs;
            this.fundamentalLinear = fundamentalLinear;
            this.harmonicCount     = harmonicCount;
            this.harmonicBins      = harmonicBins;
            this.harmonicHz        = harmonicHz;
            this.harmonicDbFs      = harmonicDbFs;
            this.harmonicPct       = harmonicPct;
            this.thdPct            = thdPct;
            this.thdDb             = thdDb;
            this.thdNDb            = thdNDb;
            this.snrDb             = snrDb;
            this.snrFreqMin        = snrFreqMin;
            this.snrFreqMax        = snrFreqMax;
            this.coherentAveraging = coherentAveraging;
            this.noisePower           = noisePower;
            this.awNoisePower         = awNoisePower;
            this.fundRefDbV                = fundRefDbV;
            this.fundamentalTrueDbV        = fundRefDbV;   // verbatim user input — cal CSV does not overwrite
            this.avgNoiseFloorDbFs         = avgNoiseFloorDbFs;
            this.fundamentalDynExclusionHz = fundamentalDynExclusionHz;
        }

    }

    // =========================================================================
    // Analysis — backward-compatible overload (Hann, 0 % overlap)
    // =========================================================================

    public Result analyze(float[] samples, int sampleRate,
                                 int fftSize, int harmonicCount) {
        return analyze(samples, sampleRate, fftSize, harmonicCount,
                WindowType.HANN, FftOverlap.PCT_0, 0.0, 0.0, true, Double.NaN);
    }

    public Result analyze(float[] samples, int sampleRate,
                                 int fftSize, int harmonicCount,
                                 WindowType windowType, FftOverlap overlap) {
        return analyze(samples, sampleRate, fftSize, harmonicCount,
                windowType, overlap, 0.0, 0.0, true, Double.NaN);
    }

    public Result analyze(float[] samples, int sampleRate,
                                 int fftSize, int harmonicCount,
                                 WindowType windowType, FftOverlap overlap,
                                 double snrFreqMin, double snrFreqMax,
                                 boolean coherentAveraging) {
        return analyze(samples, sampleRate, fftSize, harmonicCount,
                windowType, overlap, snrFreqMin, snrFreqMax, coherentAveraging, Double.NaN);
    }

    // =========================================================================
    // Analysis — full version
    // =========================================================================

    /**
     * Runs coherent-averaged FFT analysis on a normalized mono signal.
     *
     * @param samples       signal samples, range −1.0 … +1.0
     * @param sampleRate    sample rate in Hz
     * @param fftSize       FFT frame length — must be a power of 2
     * @param harmonicCount number of harmonics to evaluate (2nd … N-th)
     * @param windowType    window function to apply per frame
     * @param overlap       overlap between successive frames
     * @param snrFreqMin         lower bound for SNR noise integration in Hz (0 = no limit)
     * @param snrFreqMax         upper bound for SNR noise integration in Hz (0 = no limit)
     * @param coherentAveraging  true = accumulate complex spectra (noise cancels across frames);
     *                           false = accumulate power per bin (no phase coherence required)
     * @param fundRefDbV    known real level of the fundamental in dBV; {@link Double#NaN} = unknown
     * @return fully populated {@link Result}
     */
    public Result analyze(float[] samples, int sampleRate,
                                 int fftSize, int harmonicCount,
                                 WindowType windowType, FftOverlap overlap,
                                 double snrFreqMin, double snrFreqMax,
                                 boolean coherentAveraging, double fundRefDbV) {
        return analyze(samples, sampleRate, fftSize, harmonicCount,
                windowType, overlap, snrFreqMin, snrFreqMax, coherentAveraging,
                fundRefDbV, true);
    }

    /**
     * Same as the 10-arg overload, plus {@code logSummary}: when {@code false},
     * the trailing THD / THD+N / SNR log lines are suppressed so the caller
     * can mutate the result (e.g. apply ADC correction) and log the final
     * numbers itself without producing a misleading uncorrected line first.
     */
    public Result analyze(float[] samples, int sampleRate,
                                 int fftSize, int harmonicCount,
                                 WindowType windowType, FftOverlap overlap,
                                 double snrFreqMin, double snrFreqMax,
                                 boolean coherentAveraging, double fundRefDbV,
                                 boolean logSummary) {
        return analyze(samples, sampleRate, fftSize, harmonicCount,
                windowType, overlap, snrFreqMin, snrFreqMax, coherentAveraging,
                fundRefDbV, logSummary, Double.NaN);
    }

    /**
     * Full overload with {@code expectedFundHz}: when set (not {@link Double#NaN}),
     * the fundamental peak search is restricted to a narrow ±10-bin window around
     * the expected frequency instead of the loudest bin in the spectrum.  Use this
     * when the fundamental can be deeply attenuated (e.g. measuring distortion
     * through a notch filter at the notch frequency) — global max would otherwise
     * latch onto a harmonic, mains hum, or a spur and the entire downstream
     * harmonic table would be wrong.  Pass {@link Double#NaN} for the legacy
     * loudest-bin behaviour.
     *
     * <p>Allocates a fresh {@link Result} per call — preferred for one-shot
     * CLI / test callers.  The live FFT view goes through
     * {@link #analyze(float[], int, int, int, WindowType, FftOverlap,
     * double, double, boolean, double, boolean, double, Result)} to write
     * into a pre-allocated pool slot and avoid per-tick array churn.
     */
    public Result analyze(float[] samples, int sampleRate,
                                 int fftSize, int harmonicCount,
                                 WindowType windowType, FftOverlap overlap,
                                 double snrFreqMin, double snrFreqMax,
                                 boolean coherentAveraging, double fundRefDbV,
                                 boolean logSummary, double expectedFundHz) {
        return analyze(samples, sampleRate, fftSize, harmonicCount,
                windowType, overlap, snrFreqMin, snrFreqMax, coherentAveraging,
                fundRefDbV, logSummary, expectedFundHz, new Result());
    }

    /**
     * Pool-friendly variant: writes the analysis into the supplied
     * {@code outResult} slot instead of allocating a fresh Result.
     * The slot's bin / harmonic arrays are resized as needed
     * ({@link Result#ensureArrays}) and then used directly as the
     * analysis output buffers — no per-tick allocation for the four
     * fftSize/2+1-long spectrum arrays.  Returns {@code outResult} for
     * convenience.
     */
    public Result analyze(float[] samples, int sampleRate,
                                 int fftSize, int harmonicCount,
                                 WindowType windowType, FftOverlap overlap,
                                 double snrFreqMin, double snrFreqMax,
                                 boolean coherentAveraging, double fundRefDbV,
                                 boolean logSummary, double expectedFundHz,
                                 Result outResult) {
        if (Integer.bitCount(fftSize) != 1) {
            throw new IllegalArgumentException("fftSize must be a power of 2, got: " + fftSize);
        }

        int step = Math.max(1, (int) Math.round(fftSize * (1.0 - overlap.fraction)));
        int frameCount = samples.length >= fftSize
                ? (samples.length - fftSize) / step + 1
                : 0;
        if (frameCount == 0) {
            throw new IllegalArgumentException(
                    "Signal too short for fftSize=" + fftSize +
                    ": need at least " + fftSize + " samples, got " + samples.length);
        }

        double freqRes = (double) sampleRate / fftSize;
        log.info("FFT: size={}, frames={}, overlap={}, window={}, averaging={}, freq-res={} Hz, harmonics={}",
                fftSize, frameCount, overlap.label, windowType,
                coherentAveraging ? "coherent" : "incoherent",
                String.format(Locale.US, "%.4f", freqRes), harmonicCount);

        // --- Window + coherent gain ------------------------------------------
        // Cached: rebuilt only on (fftSize, windowType) change.  At
        // fftSize = 1 M this avoids 8 MB / call plus ~1 M Math.cos()
        // evaluations.
        double[] window  = getCachedWindow(fftSize, windowType);
        double   cohGain = 0.0;
        for (double v : window) {
            cohGain += v;
        }
        cohGain /= fftSize;

        // --- Estimate fundamental fractional bin (always, for both averaging modes) --
        // The true signal frequency is k_f × Fs/N where k_f is non-integer.
        // Using the integer bin for harmonic synthesis causes phase drift of
        // 2π×ε×n/N per sample → complete cancellation failure over long recordings.
        // Phase-difference method: window-independent, accurate to ~1e-5 bins.
        //
        // Strategy: do peak-bin detection on a frame from inside the longest
        // CLEAN segment (re-estimated below), so glitchy frames at the
        // beginning of the recording cannot poison the kFractional estimate.
        int halfSize = fftSize / 2;

        // Resize the pool slot's bin / harmonic arrays once up-front so
        // the per-frame loops below can write into them directly (no
        // per-tick allocation for the four halfSize+1-long arrays).
        outResult.ensureArrays(halfSize + 1, harmonicCount);

        // --- Pre-pass peak-bin estimate (from frame 0; only used to size the
        //     slew threshold — final kFractional is re-estimated post-rejection)
        scratchF0Re = scratchOrAlloc(scratchF0Re, fftSize);
        scratchF0Im = scratchOrAlloc(scratchF0Im, fftSize);
        double[] f0Re = scratchF0Re;
        double[] f0Im = scratchF0Im;
        cachedFrameFft(samples, 0, fftSize, window, f0Re, f0Im);
        // Skip the DC + ULF zone below 10 Hz when searching for the fundamental:
        // window leakage from a residual DC offset can populate bin 1 at roughly
        // -32 dB (Hann) below DC level, easily beating a deeply notched
        // fundamental at e.g. -100 dBFS.  10 Hz is well below any audio-band
        // fundamental and gives a comfortable margin past the window's main lobe.
        int fundSearchMinBin = Math.max(2, (int) Math.ceil(10.0 / freqRes));
        int intFundBin;
        if (!Double.isNaN(expectedFundHz) && expectedFundHz > 0.0) {
            // Hint provided — search ±10 bins around the expected bin instead of
            // global max, so a deeply notched fundamental is not lost to a louder
            // harmonic / mains spur / noise spike elsewhere in the spectrum.
            int expectedBin = (int) Math.round(expectedFundHz * fftSize / (double) sampleRate);
            int kLo = Math.max(1,        expectedBin - 10);
            int kHi = Math.min(halfSize, expectedBin + 10);
            intFundBin = peakBin(f0Re, f0Im, kLo, kHi);
            log.info("Fundamental seeded from expected freq {} Hz: bin {} (±10-bin window)",
                    String.format(Locale.US, "%.6f", expectedFundHz), intFundBin);
        } else {
            intFundBin = peakBin(f0Re, f0Im, fundSearchMinBin, halfSize);
            log.info("Fundamental search (no hint): scanned bins {}..{} (~{} Hz upward); picked bin {} (~{} Hz)",
                    fundSearchMinBin, halfSize,
                    String.format(Locale.US, "%.2f", fundSearchMinBin * freqRes),
                    intFundBin,
                    String.format(Locale.US, "%.4f", intFundBin * freqRes));
        }

        // --- Frame-rejection threshold (signal-interruption detector) --------
        // Pythagorean sine invariant: for s = A·sin(θ) and k = 2π·f/Fs,
        //   ds/dn = A·k·cos(θ)  ⇒  s² + (ds/dn / k)² ≡ A²
        // For each sample we compute R = √(s² + (Δs/k)²) using a central
        // difference. Clean samples → R ≈ A; glitches violate the invariant.
        // The test is amplitude-relative and phase-aware: a small |Δ| at a
        // peak (where slope ≈ 0) still produces a large R deviation, which the
        // old constant-|Δ| threshold would have missed.
        //
        // peakAmp is RMS-derived (peak = √2·RMS for a sine) so that overshoots
        // from dropouts do not inflate A and mask further glitches.
        // BOTH the RMS and the per-sample R scan operate on the
        // DC-removed signal — a live ADC capture typically carries a
        // small DC bias that, when not subtracted, makes peakAmp =
        // √(2·DC² + A²) instead of A.  For a small AC signal
        // dominated by DC (e.g. 5 mV sine on top of 10 mV bias) this
        // inflates peakAmp by √2·DC, and R for every sample drifts
        // ~29% away from peakAmp regardless of glitches — flagging
        // half the samples and rejecting every frame.
        final double dcMean  = sampleMean(samples);
        double       rms     = dcRemovedRms(samples, dcMean);
        double       peakAmp = rms * Math.sqrt(2.0);
        double estFundHz = intFundBin * freqRes;
        double omegaPerSample = 2.0 * Math.PI * estFundHz / sampleRate;
        final double R_TOLERANCE = 0.10;      // |R−A|/A > 10 %  → flag sample

        // The R-invariant test assumes s(n) ≈ A·sin(ωn); when the fundamental
        // sine is not the dominant component of the time-domain signal (typical
        // when measuring distortion through a deep filter notch — fundamental
        // at -100 dBFS while DC offset + noise + harmonics dominate the time
        // domain), the invariant cannot hold and the whole recording would be
        // rejected.  Compare the spectrum-bin amplitude at the candidate
        // fundamental against the time-domain peak; skip rejection unless the
        // sine carries at least half the time-domain peak amplitude.
        double specMag = Math.sqrt(f0Re[intFundBin] * f0Re[intFundBin]
                                 + f0Im[intFundBin] * f0Im[intFundBin]);
        double specPeakAmp = specMag * 2.0 / ((double) fftSize * cohGain);
        boolean rejectionFeasible = peakAmp > 0.0 && specPeakAmp >= 0.5 * peakAmp;
        log.info("Frame R-invariant check: peakAmp(RMS-derived)={}, fund-bin peak={} (ratio {} dB), k={}, tolerance={}% (using intBin={}, ~{} Hz)",
                String.format(Locale.US, "%.6e", peakAmp),
                String.format(Locale.US, "%.6e", specPeakAmp),
                peakAmp > 0
                        ? String.format(Locale.US, "%+.1f", 20.0 * Math.log10(specPeakAmp / peakAmp))
                        : "n/a",
                String.format(Locale.US, "%.6f", omegaPerSample),
                String.format(Locale.US, "%.1f", R_TOLERANCE * 100.0),
                intFundBin,
                String.format(Locale.US, "%.4f", estFundHz));
        if (!rejectionFeasible) {
            log.info("Frame R-invariant rejection skipped: fundamental sine buried in noise/spurs "
                    + "(fund-bin peak >20 dB below time-domain peak); accepting all frames");
        }

        // Quick noise-derivative check: for a clean A·sin(ωn) the
        // second difference Δ²s = s[n+1] − 2·s[n] + s[n−1] has peak
        // |A·k²|; broadband per-sample noise produces |Δ²s| ≈ 4N.
        // When the observed RMS(Δ²) is much larger than the expected
        // sine contribution, derivative noise dominates dq = Δs/k
        // and the R-invariant test fires on most samples regardless
        // of glitches — turning every analysis into a "rejection
        // invalidated" log entry.  Bail out upfront instead.
        if (rejectionFeasible && peakAmp > 0 && omegaPerSample > 0) {
            double noiseAmplification = derivativeNoiseRatio(samples, peakAmp, omegaPerSample);
            final double MAX_NOISE_AMPLIFICATION = 5.0;
            if (noiseAmplification > MAX_NOISE_AMPLIFICATION) {
                log.info("Frame R-invariant rejection skipped: derivative-noise ratio {} > {} (signal {} too small for the 1/k amplification at k={}); accepting all frames",
                        String.format(Locale.US, "%.1f", noiseAmplification),
                        String.format(Locale.US, "%.1f", MAX_NOISE_AMPLIFICATION),
                        String.format(Locale.US, "%.4e", peakAmp),
                        String.format(Locale.US, "%.6f", omegaPerSample));
                rejectionFeasible = false;
            }
        }

        // --- Averaging (coherent: complex sum; incoherent: power sum) --------
        scratchSumRe   = scratchOrAlloc(scratchSumRe,   fftSize);
        scratchSumIm   = scratchOrAlloc(scratchSumIm,   fftSize);
        scratchFrameRe = scratchOrAlloc(scratchFrameRe, fftSize);
        scratchFrameIm = scratchOrAlloc(scratchFrameIm, fftSize);
        // sumRe / sumIm are the averaging accumulators — must start at 0.
        // frameRe / frameIm are fully overwritten per frame (either by the
        // cache copy or by the windowing loop in cachedFrameFft), so no
        // explicit fill is needed.
        Arrays.fill(scratchSumRe, 0.0);
        Arrays.fill(scratchSumIm, 0.0);
        double[] sumRe   = scratchSumRe;          // complex Re  OR  power (incoherent)
        double[] sumIm   = scratchSumIm;          // complex Im  OR  unused (incoherent)
        double[] frameRe = scratchFrameRe;
        double[] frameIm = scratchFrameIm;

        // --- Pass 1: per-sample R scan, then group hits into events ----------
        // Adjacent hits (within GAP samples) belong to the same disturbance, so we
        // collapse them into one "event" with a start/end sample index.
        // Each event is logged with its file-time so it can be cross-checked
        // against an external viewer (e.g. Audacity).
        // Then, for every event, all FFT frames whose sample range overlaps
        // the event are marked dirty. Longest-clean-segment selection follows.
        final int EVENT_GAP_SAMPLES = 16;
        int hitCount = 0;
        double maxRDevSeen = 0.0;             // max |R−A|/A over the whole signal
        List<long[]> events = new ArrayList<>();  // {startSample, endSample, peakDevScaled (×1e9 as long)}
        if (rejectionFeasible) {
            int evStart = -1;
            int evEnd   = -1;
            double evPeak = 0.0;
            for (int n = 1; n < samples.length - 1; n++) {
                double sCur     = samples[n]     - dcMean;
                double sPrev    = samples[n - 1] - dcMean;
                double sNext    = samples[n + 1] - dcMean;
                double dCentral = (sNext - sPrev) * 0.5;
                double dq       = dCentral / omegaPerSample;
                double r        = Math.sqrt(sCur * sCur + dq * dq);
                double dev      = Math.abs(r - peakAmp) / peakAmp;
                if (dev > maxRDevSeen) maxRDevSeen = dev;
                if (dev > R_TOLERANCE) {
                    hitCount++;
                    if (evStart < 0 || n - evEnd > EVENT_GAP_SAMPLES) {
                        if (evStart >= 0) {
                            events.add(new long[]{evStart, evEnd, Math.round(evPeak * 1e9)});
                        }
                        evStart = n;
                        evPeak  = dev;
                    }
                    evEnd = n;
                    if (dev > evPeak) evPeak = dev;
                }
            }
            if (evStart >= 0) {
                events.add(new long[]{evStart, evEnd, Math.round(evPeak * 1e9)});
            }
        }

        boolean[] frameClean = new boolean[frameCount];
        Arrays.fill(frameClean, true);
        int rejectedFrames = 0;
        if (!events.isEmpty()) {
            log.warn("R-invariant scan: {} sample hit(s) > {}%, grouped into {} event(s) (max |R−A|/A in signal = {}%)",
                    hitCount,
                    String.format(Locale.US, "%.1f", R_TOLERANCE * 100.0),
                    events.size(),
                    String.format(Locale.US, "%.2f", maxRDevSeen * 100.0));
            for (int e = 0; e < events.size(); e++) {
                long[] ev     = events.get(e);
                int   evS     = (int) ev[0];
                int   evE     = (int) ev[1];
                double evPk   = ev[2] / 1e9;
                double tStart = evS / (double) sampleRate;
                int    width  = evE - evS + 1;
                // Frames whose [base .. base+fftSize-1] window touches [evS .. evE]:
                //   base + fftSize - 1 >= evS  AND  base <= evE
                //   → base in [evE - fftSize + 1 .. evS] / step
                int fLo = Math.max(0, (int) Math.ceil((evE - (long) fftSize + 1) / (double) step));
                int fHi = Math.min(frameCount - 1, evS / step);
                int dirty = 0;
                for (int f = fLo; f <= fHi; f++) {
                    if (frameClean[f]) {
                        frameClean[f] = false;
                        dirty++;
                    }
                }
                log.warn("  event {}/{}: sample {} (t={} s), width={} samples ({} µs), peak |R−A|/A={}% → frames [{}..{}] dirty (+{})",
                        e + 1, events.size(),
                        evS,
                        String.format(Locale.US, "%.6f", tStart),
                        width,
                        String.format(Locale.US, "%.2f", width * 1e6 / sampleRate),
                        String.format(Locale.US, "%.2f", evPk * 100.0),
                        fLo, fHi, dirty);
            }
            for (boolean c : frameClean) if (!c) rejectedFrames++;
        }
        // Sanity gate: the R-invariant test assumes a clean sine
        // dominates the time domain.  At low SNR / low amplitude the
        // derivative term dq = Δs/k amplifies broadband noise by
        // ~1/k (≈60× at 1 kHz on a 384 kHz ADC), so R drifts far
        // above peakAmp on most samples.  Even a 3 % sample-hit rate
        // groups into tens of thousands of events spread evenly
        // across the signal, marking every frame dirty.  When the
        // rejection would mark ALL frames dirty (no clean segment
        // left to analyse), the test is no longer a glitch detector;
        // bypass it and use every frame as-is.
        if (!events.isEmpty() && rejectedFrames >= frameCount) {
            log.warn("R-invariant rejection invalidated: would reject all {} frame(s) (likely low-SNR signal — derivative-noise amplification); accepting all frames",
                    frameCount);
            Arrays.fill(frameClean, true);
            rejectedFrames = 0;
            events.clear();
            hitCount = 0;
        }
        double maxRDevAccepted = events.isEmpty() ? maxRDevSeen : 0.0;
        double maxRDevRejected = events.isEmpty() ? 0.0 : maxRDevSeen;
        if (!events.isEmpty()) {
            // Recompute max accepted R deviation over surviving frames so the
            // log line below stays meaningful when some frames are rejected.
            for (int f = 0; f < frameCount; f++) {
                if (!frameClean[f]) continue;
                int base = f * step;
                int from = Math.max(1, base);
                int to   = Math.min(samples.length - 2, base + fftSize - 1);
                for (int n = from; n <= to; n++) {
                    double sCur     = samples[n]     - dcMean;
                    double sPrev    = samples[n - 1] - dcMean;
                    double sNext    = samples[n + 1] - dcMean;
                    double dCentral = (sNext - sPrev) * 0.5;
                    double dq       = dCentral / omegaPerSample;
                    double r        = Math.sqrt(sCur * sCur + dq * dq);
                    double dev      = Math.abs(r - peakAmp) / peakAmp;
                    if (dev > maxRDevAccepted) maxRDevAccepted = dev;
                }
            }
        }

        // --- Find longest contiguous clean run -------------------------------
        int bestStart = 0, bestLen = 0;
        int curStart  = 0, curLen  = 0;
        for (int f = 0; f < frameCount; f++) {
            if (frameClean[f]) {
                if (curLen == 0) curStart = f;
                curLen++;
                if (curLen > bestLen) { bestLen = curLen; bestStart = curStart; }
            } else {
                curLen = 0;
            }
        }

        log.info("Frame R-invariant stats: max accepted |R−A|/A={}%, max rejected |R−A|/A={}",
                String.format(Locale.US, "%.2f", maxRDevAccepted * 100.0),
                rejectedFrames > 0 ? String.format(Locale.US, "%.2f%%", maxRDevRejected * 100.0) : "n/a");
        if (rejectedFrames > 0) {
            log.warn("Frame rejection: {} of {} frames R-invariant-rejected; using longest clean segment [{}..{}] ({} frames)",
                    rejectedFrames, frameCount,
                    bestStart, bestStart + bestLen - 1, bestLen);
        }
        if (bestLen == 0) {
            throw new IllegalStateException("All " + frameCount + " frames rejected as corrupt — aborting FFT.");
        }

        // --- Re-estimate kFractional from the clean segment ------------------
        // Glitchy frames at the start of the recording would otherwise poison
        // the phase-difference estimate, leaving a linear residual phase drift
        // across the (clean) segment that smears the fundamental over many bins.
        // We use the first frame of the segment + one frame `step` later when
        // available, otherwise fall back to parabolic interpolation.
        int    segBaseSample = bestStart * step;
        scratchS0Re = scratchOrAlloc(scratchS0Re, fftSize);
        scratchS0Im = scratchOrAlloc(scratchS0Im, fftSize);
        double[] s0Re = scratchS0Re;
        double[] s0Im = scratchS0Im;
        cachedFrameFft(samples, segBaseSample, fftSize, window, s0Re, s0Im);
        // refine intFundBin within ±2 bins of the pre-pass estimate (in case of drift)
        int refIntBin = peakBin(s0Re, s0Im,
                Math.max(1, intFundBin - 2),
                Math.min(halfSize, intFundBin + 2));
        double kFractional;
        if (bestLen >= 2) {
            int    estStep = step > 0 ? step : fftSize;
            scratchS1Re = scratchOrAlloc(scratchS1Re, fftSize);
            scratchS1Im = scratchOrAlloc(scratchS1Im, fftSize);
            double[] s1Re = scratchS1Re;
            double[] s1Im = scratchS1Im;
            cachedFrameFft(samples, segBaseSample + estStep, fftSize, window, s1Re, s1Im);
            double phi0 = Math.atan2(s0Im[refIntBin], s0Re[refIntBin]);
            double phi1 = Math.atan2(s1Im[refIntBin], s1Re[refIntBin]);
            double expectedDiff = 2.0 * Math.PI * refIntBin * estStep / (double) fftSize;
            double rawDiff      = phi1 - phi0;
            long   m            = Math.round((rawDiff - expectedDiff) / (2.0 * Math.PI));
            kFractional = (rawDiff - 2.0 * Math.PI * m) * fftSize / (2.0 * Math.PI * estStep);
        } else {
            kFractional = MathUtil.parabolicBinInterp(s0Re, s0Im, refIntBin, fftSize);
        }
        intFundBin = refIntBin;
        log.info("k_f re-estimate (from clean segment frame {}): intBin={}, k_f={}, refinedHz={}",
                bestStart, intFundBin,
                String.format(Locale.US, "%.6f", kFractional),
                String.format(Locale.US, "%.6f", kFractional * freqRes));

        // --- Second tone (dual-/multi-tone): same clean-frame sub-bin
        // estimate around its hinted bin.  Computed on the SAME single clean
        // frame as the fundamental, so the dual-tone frequency readout is as
        // honest and un-pinned as the single-tone fundamental — never read
        // off the coherently-collapsed average.  Only the 3 lobe bins feed
        // the phase difference / parabola, so noise bins can't bias it.
        outResult.fundamental2HzRefined = Double.NaN;
        if (!Double.isNaN(secondToneHintHz) && secondToneHintHz > 0.0) {
            int k0b = (int) Math.round(secondToneHintHz / freqRes);
            if (k0b >= 3 && k0b <= halfSize - 3) {
                int ri2 = peakBin(s0Re, s0Im,
                        Math.max(1, k0b - 2), Math.min(halfSize, k0b + 2));
                double kf2;
                if (bestLen >= 2) {
                    int    estStep = step > 0 ? step : fftSize;
                    double p0   = Math.atan2(s0Im[ri2], s0Re[ri2]);
                    double p1   = Math.atan2(scratchS1Im[ri2], scratchS1Re[ri2]);
                    double exp2 = 2.0 * Math.PI * ri2 * estStep / (double) fftSize;
                    long   m2   = Math.round((p1 - p0 - exp2) / (2.0 * Math.PI));
                    kf2 = (p1 - p0 - 2.0 * Math.PI * m2) * fftSize / (2.0 * Math.PI * estStep);
                } else {
                    kf2 = MathUtil.parabolicBinInterp(s0Re, s0Im, ri2, fftSize);
                }
                outResult.fundamental2HzRefined = kf2 * freqRes;
            }
        }

        // --- Pass 2: accumulate frames in the longest clean segment ----------
        // Also store per-frame corrected fundamental complex value so we can
        // detect sub-slew sample drops (which manifest as a phase step in the
        // fundamental that the time-shift correction cannot account for).
        int    intFundBinRounded = (int) Math.round(kFractional);
        double[] fundCorrRe = coherentAveraging ? new double[bestLen] : null;
        double[] fundCorrIm = coherentAveraging ? new double[bestLen] : null;
        int acceptedFrames = 0;
        for (int f = bestStart; f < bestStart + bestLen; f++) {
            int base = f * step;
            cachedFrameFft(samples, base, fftSize, window, frameRe, frameIm);
            if (coherentAveraging) {
                // DFT time-shift theorem: frame f starting at sample f·step imparts phase
                //   exp(+j · 2π · k_f · f · step / N)
                // to bin k₀ (= round(k_f)).  To align all frames to frame-0 phase, multiply
                // each frame's spectrum by the CONJUGATE:
                //   correction(k) = exp(−j · 2π · k_f · f · step / N)
                // Generalised to bin k (to correct the whole spectrum consistently):
                //   correction(k) = exp(−j · k · 2π · f · step · k_f / (N · k₀))
                // which gives the exact per-bin rotation via an incremental complex phasor.
                double baseAngle  = -2.0 * Math.PI * (long) f * step * kFractional
                                    / ((double) fftSize * intFundBinRounded);   // NEGATIVE: conjugate rotation
                double cosBase = Math.cos(baseAngle);
                double sinBase = Math.sin(baseAngle);
                double corrRe = 1.0, corrIm = 0.0;   // exp(−j·0) = 1, k=0
                for (int k = 0; k < fftSize; k++) {
                    double cRe = frameRe[k] * corrRe - frameIm[k] * corrIm;
                    double cIm = frameRe[k] * corrIm + frameIm[k] * corrRe;
                    sumRe[k] += cRe;
                    sumIm[k] += cIm;
                    if (k == intFundBin) {
                        fundCorrRe[f - bestStart] = cRe;
                        fundCorrIm[f - bestStart] = cIm;
                    }
                    double nextCorrRe = corrRe * cosBase - corrIm * sinBase;
                    corrIm            = corrRe * sinBase + corrIm * cosBase;
                    corrRe            = nextCorrRe;
                }
            } else {
                for (int k = 0; k < fftSize; k++) {
                    sumRe[k] += frameRe[k] * frameRe[k] + frameIm[k] * frameIm[k];
                }
            }
            acceptedFrames++;
        }

        // --- Per-frame fundamental phase coherence check ---------------------
        // After time-shift correction, every frame's fundamental should land at
        // the same phase (to within thermal noise).  A sub-slew sample drop
        // produces a phase step Δφ = 2π·f·Δsamples/Fs that the correction does
        // not account for, so the offending frame stands out as a phase outlier.
        // We reject any frame deviating from the circular median by more than
        // PHASE_COHERENCE_THRESHOLD_RAD, then re-FFT and SUBTRACT each rejected
        // frame's contribution from the accumulator.
        if (coherentAveraging && bestLen >= 4) {
            final double PHASE_COHERENCE_THRESHOLD_RAD = Math.toRadians(3.0);   // 3°
            double medianPhase = circularMedianPhase(fundCorrRe, fundCorrIm, bestLen);

            int phaseRejected = 0;
            double maxDeviationDeg = 0.0;
            for (int i = 0; i < bestLen; i++) {
                double phi = Math.atan2(fundCorrIm[i], fundCorrRe[i]);
                double dev = phi - medianPhase;
                while (dev >  Math.PI) dev -= 2.0 * Math.PI;
                while (dev < -Math.PI) dev += 2.0 * Math.PI;
                double devAbs = Math.abs(dev);
                if (devAbs > Math.toRadians(maxDeviationDeg)) maxDeviationDeg = Math.toDegrees(devAbs);
                if (devAbs > PHASE_COHERENCE_THRESHOLD_RAD) {
                    // Re-FFT the rejected frame and subtract its corrected spectrum
                    int f    = bestStart + i;
                    int base = f * step;
                    cachedFrameFft(samples, base, fftSize, window, frameRe, frameIm);
                    double baseAngle  = -2.0 * Math.PI * (long) f * step * kFractional
                                        / ((double) fftSize * intFundBinRounded);
                    double cosBase = Math.cos(baseAngle);
                    double sinBase = Math.sin(baseAngle);
                    double corrRe = 1.0, corrIm = 0.0;
                    for (int k = 0; k < fftSize; k++) {
                        sumRe[k] -= frameRe[k] * corrRe - frameIm[k] * corrIm;
                        sumIm[k] -= frameRe[k] * corrIm + frameIm[k] * corrRe;
                        double nextCorrRe = corrRe * cosBase - corrIm * sinBase;
                        corrIm            = corrRe * sinBase + corrIm * cosBase;
                        corrRe            = nextCorrRe;
                    }
                    phaseRejected++;
                    acceptedFrames--;
                }
            }
            log.info("Phase coherence: max deviation from median = {} deg (threshold {} deg)",
                    String.format(Locale.US, "%.3f", maxDeviationDeg),
                    String.format(Locale.US, "%.3f", Math.toDegrees(PHASE_COHERENCE_THRESHOLD_RAD)));
            if (phaseRejected > 0) {
                log.warn("Phase coherence rejection: {} of {} segment frames rejected as out-of-phase",
                        phaseRejected, bestLen);
            }
            if (acceptedFrames < 2) {
                throw new IllegalStateException("Only " + acceptedFrames
                        + " frame(s) survived phase coherence check — aborting FFT.");
            }
        }

        frameCount = acceptedFrames;

        // --- Single-sided amplitude & phase spectrum -------------------------
        double   normFactor = 1.0 / ((double) fftSize * cohGain);
        // avgRe / avgIm / amplDbFs / phaseDeg are stored in outResult and
        // outlive this call — but the pool guarantees outResult is owned
        // exclusively by this analysis until the worker publishes it.
        // Aliasing the slot's pre-sized arrays as locals avoids per-tick
        // allocation of four halfSize+1-long arrays.  amplLinear is
        // purely local and overwritten cell-by-cell below — safe to
        // reuse from scratch.
        double[] avgRe      = outResult.re;
        double[] avgIm      = outResult.im;
        scratchAmplLinear   = scratchOrAlloc(scratchAmplLinear, halfSize + 1);
        double[] amplLinear = scratchAmplLinear;
        double[] amplDbFs   = outResult.amplitudeDbFs;
        double[] phaseDeg   = outResult.phaseDeg;

        for (int k = 0; k <= halfSize; k++) {
            double mag;
            if (coherentAveraging) {
                avgRe[k] = sumRe[k] / frameCount;
                avgIm[k] = sumIm[k] / frameCount;
                mag = Math.sqrt(avgRe[k] * avgRe[k] + avgIm[k] * avgIm[k]);
                phaseDeg[k] = Math.toDegrees(Math.atan2(avgIm[k], avgRe[k]));
            } else {
                mag = Math.sqrt(sumRe[k] / frameCount);
                avgRe[k] = mag;   // store magnitude in re for export
                avgIm[k] = 0.0;
                phaseDeg[k] = 0.0;
            }
            amplLinear[k] = (k == 0 || k == halfSize)
                    ? mag * normFactor
                    : mag * normFactor * 2.0;
            amplDbFs[k] = amplLinear[k] > 1e-15
                    ? 20.0 * Math.log10(amplLinear[k])
                    : -300.0;
        }

        // --- Fundamental (max bin, skip DC + ULF) ----------------------------
        // When the caller provided expectedFundHz, restrict the search to a
        // ±10-bin window around the expected bin — same reason as the pre-pass:
        // a deeply notched fundamental must not be lost to a louder spur.
        // Without a hint, scan above 10 Hz so DC leakage doesn't win.
        int fundBin;
        if (!Double.isNaN(expectedFundHz) && expectedFundHz > 0.0) {
            int expectedBin = (int) Math.round(expectedFundHz * fftSize / (double) sampleRate);
            int kLo = Math.max(1,        expectedBin - 10);
            int kHi = Math.min(halfSize, expectedBin + 10);
            fundBin = kLo;
            for (int k = kLo + 1; k <= kHi; k++) {
                if (amplLinear[k] > amplLinear[fundBin]) fundBin = k;
            }
        } else {
            fundBin = fundSearchMinBin;
            for (int k = fundSearchMinBin + 1; k <= halfSize; k++) {
                if (amplLinear[k] > amplLinear[fundBin]) fundBin = k;
            }
        }
        double fundHz         = fundBin * freqRes;
        double fundHzRefined  = kFractional * freqRes;   // sub-bin accurate frequency
        double fundDbFs       = amplDbFs[fundBin];
        double fundLinear     = amplLinear[fundBin];

        // refLin is the fundamental amplitude used as the *denominator* for
        // every ratio: THD, SNR, THD+N, harmonic %.  When fundRefDbV is set
        // (e.g. an external twin-T notch suppresses only H1 by a drift-prone
        // amount, so amplLinear[fundBin] is unreliable while H2..Hn stay
        // valid as measured), use the user's true fundamental as the anchor.
        // The measured fundDbFs / fundLinear are still stored verbatim into
        // Result — downstream code (frequency response calibration scale, chart, cal-CSV anchor)
        // depends on those reflecting the actual FFT measurement.
        // Convert dBV → dBFS-equivalent via the ADC full-scale voltage so
        // refLin lives in the same FS-relative units as amplLinear[k];
        // otherwise harmonic % / THD / SNR scale by an unwanted factor of
        // adcFsVoltageRms (≈ 1.79 for a 1.7931 V_rms FS).
        double refLin;
        if (Double.isNaN(fundRefDbV)) {
            refLin = fundLinear;
        } else {
            double fsV = AudioBackend.getAdcFsVoltageRms();
            double trueDbFs = fsV > 0.0 ? fundRefDbV - 20.0 * Math.log10(fsV) : fundRefDbV;
            refLin = Math.pow(10.0, trueDbFs / 20.0);
        }

        if (Double.isNaN(fundRefDbV)) {
            log.info("Fundamental: bin={}, freq={} Hz, refined={} Hz, level={} dBFS",
                    fundBin,
                    String.format(Locale.US, "%.3f", fundHz),
                    String.format(Locale.US, "%.6f", fundHzRefined),
                    String.format(Locale.US, "%.3f", fundDbFs));
        } else {
            log.info("Fundamental: bin={}, freq={} Hz, refined={} Hz, measured={} dBFS, ref={} dBV",
                    fundBin,
                    String.format(Locale.US, "%.3f", fundHz),
                    String.format(Locale.US, "%.6f", fundHzRefined),
                    String.format(Locale.US, "%.3f", fundDbFs),
                    String.format(Locale.US, "%.3f", fundRefDbV));
        }

        // --- Harmonics -------------------------------------------------------
        // Aliased onto the pool slot's harmonic arrays — sized by
        // ensureArrays at the top of analyze.  hLinear is purely local
        // (intermediate THD-sum term), kept as a fresh allocation; it
        // doesn't outlive the call.
        int[]    hBins   = outResult.harmonicBins;
        double[] hHz     = outResult.harmonicHz;
        double[] hDbFs   = outResult.harmonicDbFs;
        // hLinear holds the proportional-bin-combined harmonic amplitude
        // (see detectHarmonics) — used by the THD sum below so it matches
        // the per-harmonic readout, instead of resampling from amplLinear.
        double[] hLinear = new double[harmonicCount];
        double[] hPct    = outResult.harmonicPct;
        detectHarmonics(kFractional, fundHzRefined, halfSize,
                amplLinear, amplDbFs, refLin,
                harmonicCount, hBins, hHz, hDbFs, hLinear, hPct);

        double snrLo = snrFreqMin > 0.0 ? snrFreqMin : 0.0;
        double snrHi = snrFreqMax > 0.0 ? snrFreqMax : Double.MAX_VALUE;

        // --- THD — H2..H9 (max 8 harmonics) that fall within dist range ------
        double harmPowerSum = 0.0;
        for (int h = 0; h < Math.min(harmonicCount, 8); h++) {
            if (hBins[h] > 0) {
                double harmFreq = hHz[h];
                if (harmFreq >= snrLo && harmFreq <= snrHi) {
                    double a = hLinear[h];
                    harmPowerSum += a * a;
                }
            }
        }
        double thdPct = refLin > 0
                ? Math.sqrt(harmPowerSum) / refLin * 100.0
                : 0.0;
        double thdDb = thdPct > 0
                ? 20.0 * Math.log10(thdPct / 100.0)
                : -300.0;

        // --- Signal-bin mask -------------------------------------------------
        // isSignalBin: fundamental + all harmonics + dynamic leakage zones.
        //              Excluded from both the noise-floor median and the SNR
        //              peak-spur search, so harmonics do not appear in SNR.
        final int EXCL_BINS = 4;
        boolean[] isSignalBin = buildSignalBinMask(halfSize, fundBin, hBins, harmonicCount, EXCL_BINS);

        // --- SNR — integrated noise over the measurement band ----------------
        // Fundamental and all harmonics are excluded via isSignalBin.
        // SNR = signal_power / sum(noise_bins_in_range) → bandwidth-dependent.

        NoiseFloor nf = computeNoiseFloorAndExtendSignalMask(
                amplLinear, isSignalBin, halfSize, freqRes, snrLo, snrHi, fundBin, EXCL_BINS);
        double medianNoisePow = nf.medianNoisePow;
        int    dynWidthBins   = nf.dynWidthBins;
        if (dynWidthBins > EXCL_BINS) {
            log.info("Fundamental dynamic exclusion: +/-{} bins ({} Hz each side)",
                    dynWidthBins, String.format(Locale.US, "%.2f", dynWidthBins * freqRes));
        }

        // SNR: signal RMS² / total unweighted noise power, integrated over
        // every non-signal bin in the SNR range.  Harmonics are excluded via
        // isSignalBin; the dynamic walk above keeps window leakage from the
        // fundamental out of the noise sum.  Signal² is refLin² so an
        // external twin-T notch on H1 cannot deflate the numerator.
        double noisePower = 0.0;
        for (int k = 1; k <= halfSize; k++) {
            double freq = k * freqRes;
            if (!isSignalBin[k] && freq >= snrLo && freq <= snrHi) {
                double pow = amplLinear[k] * amplLinear[k];
                noisePower += pow;
            }
        }
        double snrDb;
        if (noisePower <= 0) {
            log.warn("SNR: no non-signal bins remain in measurement range");
            snrDb = 300.0;
        } else {
            snrDb = 10.0 * Math.log10((refLin * refLin) / noisePower);
        }

        // SINAD: signal RMS² / (noise + distortion) — denominator includes both
        // the integrated noise sum AND the in-band harmonic power, so ENOB
        // = (SINAD − 1.76)/6.02 reflects every non-fundamental contribution.
        double sinadDenom = noisePower + harmPowerSum;
        double sinadDb    = sinadDenom > 0
                ? 10.0 * Math.log10((refLin * refLin) / sinadDenom)
                : 300.0;
        // Average noise floor: median of clean bins converted to dBFS
        double avgNoiseFloorDbFs = medianNoisePow > 0
                ? 20.0 * Math.log10(Math.sqrt(medianNoisePow))
                : fundDbFs - 300.0;

        // THD+N is the reciprocal of SINAD: (noise + distortion) / signal,
        // expressed in dB → −sinadDb.  Same band, same numerator definition.
        double thdNDb = -sinadDb;

        if (logSummary) {
            log.info("THD   : {}%  ({} dB)",
                    String.format(Locale.US, "%.8f", thdPct),
                    String.format(Locale.US, "%.2f", thdDb));
            log.info("THD+N (A-weighted): {} dB", String.format(Locale.US, "%.2f", thdNDb));
            if (snrFreqMin > 0.0 || snrFreqMax > 0.0) {
                log.info("SNR   : {} dB  (range: {}-{} Hz)",
                        String.format(Locale.US, "%.2f", snrDb),
                        String.format(Locale.US, "%.0f", snrLo),
                        String.format(Locale.US, "%.0f", snrHi));
            } else {
                log.info("SNR   : {} dB", String.format(Locale.US, "%.2f", snrDb));
            }
        }

        // outResult's arrays were aliased above (avgRe/avgIm/amplDbFs/
        // phaseDeg/hBins/hHz/hDbFs/hPct point into outResult).  Only
        // the scalars need to be written here.
        outResult.fftSize                    = fftSize;
        outResult.sampleRate                 = sampleRate;
        outResult.frameCount                 = frameCount;
        outResult.freqResolution             = freqRes;
        outResult.windowType                 = windowType;
        outResult.overlap                    = overlap;
        outResult.fundamentalBin             = fundBin;
        outResult.fundamentalHz              = fundHz;
        outResult.fundamentalHzRefined       = fundHzRefined;
        outResult.fundamentalDbFs            = fundDbFs;
        outResult.fundamentalLinear          = fundLinear;
        outResult.harmonicCount              = harmonicCount;
        outResult.thdPct                     = thdPct;
        outResult.thdDb                      = thdDb;
        outResult.thdNDb                     = thdNDb;
        outResult.snrDb                      = snrDb;
        outResult.sinadDb                    = sinadDb;
        outResult.snrFreqMin                 = snrFreqMin;
        outResult.snrFreqMax                 = snrFreqMax;
        outResult.coherentAveraging          = coherentAveraging;
        outResult.noisePower                 = noisePower;
        outResult.awNoisePower               = noisePower;
        outResult.avgNoiseFloorDbFs          = avgNoiseFloorDbFs;
        outResult.fundRefDbV                 = fundRefDbV;
        outResult.fundamentalTrueDbV         = fundRefDbV;
        outResult.fundamentalDynExclusionHz  = dynWidthBins * freqRes;
        // preCorrectionPeaks is owned by the worker (set after analyze
        // returns when calibration is loaded); reset here so a previous
        // tick's value doesn't leak across pool slot recycle.
        outResult.preCorrectionPeaks = null;
        return outResult;
    }

    /**
     * Recomputes the fundamental level, harmonic table, THD, THD+N, SNR, and noise
     * statistics from the (possibly mutated) {@code amplitudeDbFs}/{@code re}/{@code im}
     * arrays in {@code r}.  Use after any post-processing step that mutates the
     * spectrum (frequency response de-embedding, ADC correction) so all derived fields stay
     * consistent with the corrected bins.
     *
     * <p>The caller is responsible for keeping {@code amplitudeDbFs[]} in sync
     * with {@code re}/{@code im} as it mutates bins; this method trusts
     * {@code amplitudeDbFs[]} as the source of truth for linear amplitude
     * (because {@code re}/{@code im} are unscaled FFT outputs — the
     * analyzer-side normFactor and single-sided ×2 are folded into
     * {@code amplitudeDbFs}, not into {@code re}/{@code im}).
     *
     * <p>{@code fundamentalBin} and {@code harmonicBins[]} are kept unchanged —
     * smooth per-bin scaling does not move peaks, so the originally identified
     * bin positions remain valid.
     *
     * <p>Logic mirrors the stats portion of {@link #analyze}; keep both in sync.
     */
    public void recomputeStats(Result r) {
        int    halfSize = r.fftSize / 2;
        double freqRes  = r.freqResolution;
        double snrLo    = r.snrFreqMin > 0.0 ? r.snrFreqMin : 0.0;
        double snrHi    = r.snrFreqMax > 0.0 ? r.snrFreqMax : Double.MAX_VALUE;

        // Derive properly-scaled linear amplitudes from amplitudeDbFs.
        double[] amplLinear = new double[halfSize + 1];
        for (int k = 0; k <= halfSize; k++) {
            amplLinear[k] = r.amplitudeDbFs[k] > -290.0
                    ? Math.pow(10.0, r.amplitudeDbFs[k] / 20.0)
                    : 0.0;
        }

        // --- Fundamental ----------------------------------------------------
        int    fundBin    = r.fundamentalBin;
        double fundLinear = amplLinear[fundBin];
        double fundDbFs   = r.amplitudeDbFs[fundBin];
        r.fundamentalLinear = fundLinear;
        r.fundamentalDbFs   = fundDbFs;
        // Mirror analyze(): when --fund-v / --fund-dbv was provided, use it
        // as the ratio denominator (THD / SNR / THD+N / H%); the measured
        // fundamental scalars above stay verbatim so downstream cal/chart
        // logic keeps working.  Read fundamentalTrueDbV (not fundRefDbV)
        // because the cal CSV overwrites fundRefDbV with a notched-fundamental
        // anchor before recomputeStats runs in iterative paths.  Convert
        // dBV → dBFS via adcFsVoltageRms so refLin is in the same FS-units
        // as amplLinear[].
        double refLin;
        if (Double.isNaN(r.fundamentalTrueDbV)) {
            refLin = fundLinear;
        } else {
            double fsV = AudioBackend.getAdcFsVoltageRms();
            double trueDbFs = fsV > 0.0
                    ? r.fundamentalTrueDbV - 20.0 * Math.log10(fsV)
                    : r.fundamentalTrueDbV;
            refLin = Math.pow(10.0, trueDbFs / 20.0);
        }

        // --- Harmonic table -------------------------------------------------
        // Mirror the proportional-bin combination from detectHarmonics: the
        // Nth harmonic sits at the fractional bin (harmonicHz/freqRes), which
        // equals N·kFractional by construction.  Power-weight the two
        // straddling bins so the readout reflects the real harmonic energy
        // even when the harmonic falls between bins.
        int harmonicCount = r.harmonicCount;
        double[] hLinear = new double[harmonicCount];
        for (int h = 0; h < harmonicCount; h++) {
            if (r.harmonicBins[h] <= 0) continue;
            double kH = freqRes > 0 ? r.harmonicHz[h] / freqRes : 0.0;
            int binMain = (int) Math.round(kH);
            if (binMain < 1 || binMain > halfSize) {
                r.harmonicBins[h] = -1;
                r.harmonicDbFs[h] = -300.0;
                r.harmonicPct[h]  = 0.0;
                hLinear[h]        = 0.0;
                continue;
            }
            double offset = kH - binMain;
            int binAdj = (offset >= 0.0)
                    ? Math.min(halfSize, binMain + 1)
                    : Math.max(1, binMain - 1);
            double w = Math.abs(offset);
            double pMain = amplLinear[binMain] * amplLinear[binMain];
            double pAdj  = amplLinear[binAdj]  * amplLinear[binAdj];
            double aCombined = Math.sqrt((1.0 - w) * pMain + w * pAdj);
            r.harmonicBins[h] = binMain;
            r.harmonicDbFs[h] = aCombined > 1e-15 ? 20.0 * Math.log10(aCombined) : -300.0;
            r.harmonicPct[h]  = refLin > 0 ? aCombined / refLin * 100.0 : 0.0;
            hLinear[h]        = aCombined;
        }

        // --- THD (H2..H9 within SNR range) ---------------------------------
        double harmPowerSum = 0.0;
        for (int h = 0; h < Math.min(harmonicCount, 8); h++) {
            int hb = r.harmonicBins[h];
            if (hb > 0) {
                double freq = r.harmonicHz[h];
                if (freq >= snrLo && freq <= snrHi) {
                    double a = hLinear[h];
                    harmPowerSum += a * a;
                }
            }
        }
        r.thdPct = refLin > 0 ? Math.sqrt(harmPowerSum) / refLin * 100.0 : 0.0;
        r.thdDb  = r.thdPct > 0  ? 20.0 * Math.log10(r.thdPct / 100.0)        : -300.0;

        // --- Signal-bin mask: fundamental + every harmonic +/- EXCL_BINS ---
        final int EXCL_BINS = 4;
        boolean[] isSignalBin = buildSignalBinMask(halfSize, fundBin,
                r.harmonicBins, harmonicCount, EXCL_BINS);

        // --- Noise floor + signal-mask extension (shared with analyze()) ---
        // recomputeStats discards dynWidthBins — the
        // Result.fundamentalDynExclusionHz is final and was set on the
        // original analyze() pass; we only need the median noise power.
        double medianNoisePow = computeNoiseFloorAndExtendSignalMask(
                amplLinear, isSignalBin, halfSize, freqRes, snrLo, snrHi, fundBin, EXCL_BINS)
                .medianNoisePow;

        // --- SNR (signal RMS² / total unweighted noise power) ---------------
        double noisePower = 0.0;
        for (int k = 1; k <= halfSize; k++) {
            double freq = k * freqRes;
            if (!isSignalBin[k] && freq >= snrLo && freq <= snrHi) {
                double pow = amplLinear[k] * amplLinear[k];
                noisePower += pow;
            }
        }
        r.noisePower        = noisePower;
        r.snrDb             = noisePower <= 0
                ? 300.0
                : 10.0 * Math.log10((refLin * refLin) / noisePower);
        // SINAD denominator = noise + in-band harmonic power (basis for ENOB).
        double sinadDenom = noisePower + harmPowerSum;
        r.sinadDb = sinadDenom > 0
                ? 10.0 * Math.log10((refLin * refLin) / sinadDenom)
                : 300.0;
        r.avgNoiseFloorDbFs = medianNoisePow > 0
                ? 20.0 * Math.log10(Math.sqrt(medianNoisePow))
                : fundDbFs - 300.0;

        // THD+N is the reciprocal of SINAD: −sinadDb in dB.
        r.awNoisePower = noisePower;
        r.thdNDb       = -r.sinadDb;
    }

    // =========================================================================
    // Window functions
    // =========================================================================

    /**
     * Builds a normalized analysis window of length {@code N} for the given type.
     * All windows use (N−1) symmetric normalization except Flat-top which uses
     * periodic (N) normalization for optimal amplitude accuracy.
     */
    double[] buildWindow(int N, WindowType type) {
        double[] w = new double[N];
        switch (type) {
            case RECTANGULAR:
                Arrays.fill(w, 1.0);
                break;

            case HANN:
                for (int n = 0; n < N; n++) {
                    w[n] = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * n / (N - 1)));
                }
                break;

            case BLACKMAN_HARRIS_4: {
                // Harris 1978 "minimum 4-term Blackman-Harris"
                double a0 = 0.35875, a1 = 0.48829, a2 = 0.14128, a3 = 0.01168;
                for (int n = 0; n < N; n++) {
                    double t = 2.0 * Math.PI * n / (N - 1);
                    w[n] = a0 - a1 * Math.cos(t) + a2 * Math.cos(2 * t) - a3 * Math.cos(3 * t);
                }
                break;
            }

            case BLACKMAN_HARRIS_7: {
                // Nuttall 1981 7-term Blackman-Harris
                double[] a = {
                    0.2712203606, 0.4334446123, 0.2180041184, 0.0657853433,
                    0.0107618673, 0.0007700127, 0.0000136547
                };
                for (int n = 0; n < N; n++) {
                    double t   = 2.0 * Math.PI * n / (N - 1);
                    double val = 0.0;
                    for (int k = 0; k < a.length; k++) {
                        val += (k % 2 == 0 ? 1.0 : -1.0) * a[k] * Math.cos(k * t);
                    }
                    w[n] = val;
                }
                break;
            }

            case FLAT_TOP: {
                // SRS SR785 5-term flat-top (periodic normalization, N)
                double a0 = 0.21557895, a1 = 0.41663158, a2 = 0.27726316,
                       a3 = 0.08357895, a4 = 0.00694737;
                for (int n = 0; n < N; n++) {
                    double t = 2.0 * Math.PI * n / N;
                    w[n] = a0 - a1 * Math.cos(t) + a2 * Math.cos(2 * t)
                              - a3 * Math.cos(3 * t) + a4 * Math.cos(4 * t);
                }
                break;
            }

            case DOLPH_CHEBYSHEV_150:
                return buildChebyshevWindow(N, 150.0);

            case DOLPH_CHEBYSHEV_200:
                return buildChebyshevWindow(N, 200.0);

            default:
                throw new IllegalArgumentException("Unknown window type: " + type);
        }
        return w;
    }

    /**
     * Builds a Dolph-Chebyshev window via FFT-based IDFT.
     *
     * <ol>
     *   <li>Compute x0 = cosh(acosh(10^(atten/20)) / (N−1))</li>
     *   <li>For k = 0..N−1: W[k] = T_{N−1}(x0 · cos(πk/N)), symmetrically filled</li>
     *   <li>IFFT(W) / N gives the time-domain window</li>
     *   <li>fftshift by N/2 centers the main lobe</li>
     *   <li>Normalize to max = 1</li>
     * </ol>
     */
    double[] buildChebyshevWindow(int N, double attenDb) {
        double beta = Math.pow(10.0, attenDb / 20.0);
        double x0   = Math.cosh(MathUtil.acosh(beta) / (N - 1));

        double[] wRe = new double[N];
        double[] wIm = new double[N];

        // Compute W[k] = T_{N-1}(x0 * cos(π*k/N)) for k=0..N/2, symmetric fill
        for (int k = 0; k <= N / 2; k++) {
            double val = MathUtil.chebyshevT(N - 1, x0 * Math.cos(Math.PI * k / N));
            wRe[k] = val;
            if (k > 0 && k < N - k) {
                wRe[N - k] = val;
            }
        }

        // IFFT via FFT conjugate: IFFT(W) = conj(FFT(conj(W))) / N
        // Since W is real, conj(W) = W, so IFFT = FFT / N
        Fft.forward(wRe, wIm);
        for (int i = 0; i < N; i++) {
            wRe[i] /= N;
        }

        // fftshift: rotate by N/2 to center the main lobe
        double[] window = new double[N];
        int half = N / 2;
        for (int i = 0; i < N; i++) {
            window[(i + half) % N] = wRe[i];
        }

        // Normalize to max = 1
        double max = 0.0;
        for (double v : window) {
            if (v > max) {
                max = v;
            }
        }
        if (max > 0.0) {
            for (int i = 0; i < N; i++) {
                window[i] /= max;
            }
        }
        return window;
    }




    // =========================================================================
    // CSV export
    // =========================================================================

    public String exportFftCsv(Result r, String directory) throws IOException {
        String ts      = ts();
        File   outFile = new File(directory, "fft_spectrum_" + ts + ".csv");
        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(outFile)))) {
            pw.println("frequency_hz;amplitude_dbfs;phase_deg;re;im");
            for (int k = 0; k <= r.fftSize / 2; k++) {
                pw.printf(Locale.GERMAN, "%.6f;%.6f;%.4f;%.10e;%.10e%n",
                        k * r.freqResolution,
                        r.amplitudeDbFs[k],
                        r.phaseDeg[k],
                        r.re[k],
                        r.im[k]);
            }
        }
        log.info("FFT spectrum CSV saved: {}", outFile.getAbsolutePath());
        return outFile.getAbsolutePath();
    }



    // =========================================================================
    // Helpers
    // =========================================================================

    private String ts() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
    }

    /** Arithmetic mean of {@code samples}.  Used by {@link #analyze} to
     *  estimate the DC bias before computing the R-invariant peak amplitude
     *  — without subtracting the bias the RMS-derived peakAmp inflates by
     *  √2·DC for any signal where DC is comparable to the AC swing. */
    private static double sampleMean(float[] samples) {
        double sum = 0.0;
        for (float s : samples) sum += s;
        return sum / samples.length;
    }

    /** RMS of {@code samples} with the supplied {@code dcMean} subtracted
     *  beforehand.  Two-pass: caller computes {@link #sampleMean} first,
     *  then passes the result here. */
    private static double dcRemovedRms(float[] samples, double dcMean) {
        double sumSq = 0.0;
        for (float s : samples) {
            double d = s - dcMean;
            sumSq += d * d;
        }
        return Math.sqrt(sumSq / samples.length);
    }

    /** Locates each harmonic (H2..H{@code harmonicCount+1}) in the
     *  averaged spectrum.  Physics fixes the Nth harmonic at exactly
     *  {@code N × fundamentalFreq}, so its fractional FFT bin is
     *  {@code N × kFractional} — and {@link #kFractional} already
     *  captures any DAC↔ADC clock skew with ~1e-5 bin precision.
     *
     *  <p>When the theoretical fractional bin lands between two FFT bins
     *  (which it almost always does at higher harmonics, even when the
     *  generator is bin-aligned, because tiny clock-skew accumulates as
     *  {@code N × skew}), the harmonic energy splits across both bins
     *  via the window's main-lobe leakage.  We recover it by power-
     *  weighting the two straddling bins proportionally to the sub-bin
     *  offset: {@code power = (1−w)·|X[main]|² + w·|X[adj]|²} where
     *  {@code w = |offset_from_main|} ∈ [0, 0.5].  This replaces the
     *  earlier "local-max within ±~3 bins" search, which snapped to
     *  random noise peaks when a harmonic was buried in the noise.
     *
     *  <p>Out-parameters: {@code hBins}, {@code hHz}, {@code hDbFs},
     *  {@code hLinear}, {@code hPct} are pre-allocated by the caller
     *  and filled in place. */
    private static void detectHarmonics(double kFractional, double fundHzRefined,
                                        int halfSize, double[] amplLinear, double[] amplDbFs,
                                        double refLin, int harmonicCount,
                                        int[] hBins, double[] hHz,
                                        double[] hDbFs, double[] hLinear, double[] hPct) {
        for (int h = 0; h < harmonicCount; h++) {
            int hNum = h + 2;
            double kH  = kFractional * hNum;             // theoretical fractional bin
            int binMain = (int) Math.round(kH);
            if (binMain < 1 || binMain > halfSize) {
                hBins[h]   = -1;
                hHz[h]     = fundHzRefined * hNum;
                hDbFs[h]   = -300.0;
                hLinear[h] = 0.0;
                hPct[h]    = 0.0;
                continue;
            }
            double offset = kH - binMain;                // signed, (−0.5, +0.5]
            int binAdj;
            if (offset >= 0.0) {
                binAdj = Math.min(halfSize, binMain + 1);
            } else {
                binAdj = Math.max(1, binMain - 1);
            }
            double w        = Math.abs(offset);          // [0, 0.5]
            double pMain    = amplLinear[binMain] * amplLinear[binMain];
            double pAdj     = amplLinear[binAdj]  * amplLinear[binAdj];
            double pCombined = (1.0 - w) * pMain + w * pAdj;
            double aCombined = Math.sqrt(pCombined);
            hBins[h]   = binMain;
            hHz[h]     = fundHzRefined * hNum;
            hLinear[h] = aCombined;
            hDbFs[h]   = aCombined > 1e-15 ? 20.0 * Math.log10(aCombined) : -300.0;
            hPct[h]    = refLin > 0 ? aCombined / refLin * 100.0 : 0.0;
        }
    }

    /** Builds the "signal bin" mask: fundamental bin + all harmonic bins,
     *  each smeared by {@code exclBins} on either side to cover window
     *  leakage.  Used by both {@link #analyze} and {@code recomputeStats}
     *  as the starting mask for the noise-floor computation; the mask is
     *  then extended by {@link #computeNoiseFloorAndExtendSignalMask}
     *  with the dynamic fundamental-exclusion walk and phase-noise zone. */
    private static boolean[] buildSignalBinMask(int halfSize, int fundBin,
                                                int[] hBins, int harmonicCount, int exclBins) {
        boolean[] isSignalBin = new boolean[halfSize + 1];
        for (int d = -exclBins; d <= exclBins; d++) {
            int bin = fundBin + d;
            if (bin >= 0 && bin <= halfSize) isSignalBin[bin] = true;
        }
        for (int h = 0; h < harmonicCount; h++) {
            if (hBins[h] > 0) {
                for (int d = -exclBins; d <= exclBins; d++) {
                    int bin = hBins[h] + d;
                    if (bin >= 0 && bin <= halfSize) isSignalBin[bin] = true;
                }
            }
        }
        return isSignalBin;
    }

    /** Result of {@link #computeNoiseFloorAndExtendSignalMask} — the
     *  refined range-restricted noise floor (squared linear amplitude)
     *  plus the dynamic fundamental exclusion half-width in bins. */
    private static final class NoiseFloor {
        final double medianNoisePow;
        final int    dynWidthBins;
        NoiseFloor(double m, int d) { this.medianNoisePow = m; this.dynWidthBins = d; }
    }

    /** Computes the median non-signal-bin power inside the SNR band,
     *  performs the dynamic fundamental-exclusion walk (against the
     *  global 10th-percentile floor — leakage-immune), and applies the
     *  phase-noise exclusion zone within ±{@code fundBin/2} of the
     *  fundamental.  Mutates {@code isSignalBin} in place to mark every
     *  bin that ends up classified as signal (window leakage + phase
     *  noise + already-marked harmonic neighbours).
     *
     *  <p>Pulled out of {@link #analyze} and {@code recomputeStats}
     *  where the same ~60 lines of bookkeeping appeared verbatim.
     *  Single point of truth for the SNR/THD-N noise-floor model. */
    private static NoiseFloor computeNoiseFloorAndExtendSignalMask(
            double[] amplLinear, boolean[] isSignalBin,
            int halfSize, double freqRes,
            double snrLo, double snrHi,
            int fundBin, int exclBins) {

        // Pass 1 — two medians in one scan:
        //   globalMedianNoisePow : full spectrum (no range limit) — used only for the
        //                          dynamic fundamental exclusion walk, so it is never
        //                          contaminated by leakage bins inside a narrow range.
        //   medianNoisePow       : range-restricted — used for spike threshold / SNR.
        int candidateCount = 0;
        int globalCount    = 0;
        for (int k = 1; k <= halfSize; k++) {
            double freq = k * freqRes;
            if (!isSignalBin[k]) {
                globalCount++;
                if (freq >= snrLo && freq <= snrHi) candidateCount++;
            }
        }
        double[] candidatePow = new double[candidateCount];
        double[] globalPow    = new double[globalCount];
        int ci = 0, gi = 0;
        for (int k = 1; k <= halfSize; k++) {
            double freq = k * freqRes;
            if (!isSignalBin[k]) {
                double pow = amplLinear[k] * amplLinear[k];
                globalPow[gi++] = pow;
                if (freq >= snrLo && freq <= snrHi) candidatePow[ci++] = pow;
            }
        }
        Arrays.sort(candidatePow);
        Arrays.sort(globalPow);
        double medianNoisePow       = candidateCount > 0 ? candidatePow[candidateCount / 2] : 0.0;
        // 10th-percentile of the full-spectrum powers: closer to the true quantization
        // noise floor than the median, which is pulled up by non-harmonic spurs.
        double globalMedianNoisePow = globalCount    > 0 ? globalPow[globalCount / 10]      : 0.0;

        // Inter-pass — dynamic fundamental exclusion at noise floor level.
        // Uses globalMedianNoisePow so the exclusion width is independent of the
        // chosen SNR frequency range (narrow range bins near the fundamental are
        // contaminated by window leakage and would give a falsely short walk).
        int dynWidthBins = exclBins;
        if (globalMedianNoisePow > 0) {
            int dynLo = fundBin, dynHi = fundBin;
            while (dynLo > 1
                    && amplLinear[dynLo - 1] * amplLinear[dynLo - 1] > globalMedianNoisePow) {
                dynLo--;
            }
            while (dynHi < halfSize - 1
                    && amplLinear[dynHi + 1] * amplLinear[dynHi + 1] > globalMedianNoisePow) {
                dynHi++;
            }
            dynWidthBins = Math.max(fundBin - dynLo, dynHi - fundBin);
            if (dynWidthBins > exclBins) {
                for (int k = dynLo; k <= dynHi; k++) isSignalBin[k] = true;

                // Pass 2 — refined range-restricted median with updated exclusion
                candidateCount = 0;
                for (int k = 1; k <= halfSize; k++) {
                    double freq = k * freqRes;
                    if (!isSignalBin[k] && freq >= snrLo && freq <= snrHi) candidateCount++;
                }
                candidatePow = new double[candidateCount];
                ci = 0;
                for (int k = 1; k <= halfSize; k++) {
                    double freq = k * freqRes;
                    if (!isSignalBin[k] && freq >= snrLo && freq <= snrHi) {
                        candidatePow[ci++] = amplLinear[k] * amplLinear[k];
                    }
                }
                Arrays.sort(candidatePow);
                medianNoisePow = candidateCount > 0 ? candidatePow[candidateCount / 2] : medianNoisePow;
            }
        }

        // Phase-noise exclusion: scan a wide zone around the fundamental and
        // mark any bin above the refined noise-floor median as signal.  The
        // contiguous dynamic walk above terminates on the first sub-floor
        // dip, so isolated phase-noise bumps beyond it would otherwise be
        // counted toward SNR.  Zone is capped at ±fundBin/2 so it cannot
        // reach H2 at 2·fundBin.
        if (medianNoisePow > 0) {
            int phaseLo = Math.max(1, fundBin - fundBin / 2);
            int phaseHi = Math.min(halfSize, fundBin + fundBin / 2);
            for (int k = phaseLo; k <= phaseHi; k++) {
                if (!isSignalBin[k] && amplLinear[k] * amplLinear[k] > medianNoisePow) {
                    isSignalBin[k] = true;
                }
            }
        }
        return new NoiseFloor(medianNoisePow, dynWidthBins);
    }

    /** Returns the bin index in {@code [kLo, kHi]} with the largest
     *  squared-magnitude {@code re[k]² + im[k]²}.  Used by {@link #analyze}
     *  for the pre-pass fundamental search (both the global "loudest"
     *  variant and the hinted ±10-bin variant). */
    private static int peakBin(double[] re, double[] im, int kLo, int kHi) {
        int    best    = kLo;
        double peakPow = re[kLo] * re[kLo] + im[kLo] * im[kLo];
        for (int k = kLo + 1; k <= kHi; k++) {
            double p = re[k] * re[k] + im[k] * im[k];
            if (p > peakPow) { peakPow = p; best = k; }
        }
        return best;
    }

    /** Circular median of {@code n} complex phasors {@code (re[i], im[i])}.
     *  Computes median(sin) and median(cos) on the unit-circle-normalised
     *  components, then takes {@code atan2(medSin, medCos)}.  Robust for
     *  tightly clustered phases — the expected case for the fundamental
     *  bin across all accepted FFT frames.  Used by {@link #analyze}'s
     *  phase-coherence outlier rejection. */
    private static double circularMedianPhase(double[] re, double[] im, int n) {
        double[] sins = new double[n];
        double[] coss = new double[n];
        for (int i = 0; i < n; i++) {
            double mag = Math.sqrt(re[i] * re[i] + im[i] * im[i]);
            sins[i] = mag > 0 ? im[i] / mag : 0.0;
            coss[i] = mag > 0 ? re[i] / mag : 0.0;
        }
        Arrays.sort(sins);
        Arrays.sort(coss);
        return Math.atan2(sins[n / 2], coss[n / 2]);
    }

    /** Ratio of {@code RMS(Δ²s)} to the expected sine contribution at
     *  {@code peakAmp · omega²/√2}.  Used inside {@link #analyze} to
     *  decide whether per-sample R-invariant rejection is feasible: when
     *  this ratio exceeds the threshold, the 1/k amplification on
     *  dq = Δs/k turns broadband noise into a per-sample "glitch"
     *  detector and every frame gets rejected — so the analyser bails
     *  out of rejection entirely instead.  See the inline rationale in
     *  {@code analyze}. */
    private static double derivativeNoiseRatio(float[] samples, double peakAmp, double omegaPerSample) {
        double sumD2Sq = 0.0;
        for (int n = 1; n < samples.length - 1; n++) {
            double d2 = (double) samples[n + 1] - 2.0 * samples[n] + samples[n - 1];
            sumD2Sq += d2 * d2;
        }
        double rmsD2             = Math.sqrt(sumD2Sq / Math.max(1, samples.length - 2));
        double expectedSineRmsD2 = peakAmp * omegaPerSample * omegaPerSample / Math.sqrt(2.0);
        return rmsD2 / Math.max(1e-30, expectedSineRmsD2);
    }
}
