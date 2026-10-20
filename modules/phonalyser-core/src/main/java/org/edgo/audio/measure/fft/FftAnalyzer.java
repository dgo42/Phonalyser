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

package org.edgo.audio.measure.fft;

import java.util.Arrays;
import java.util.Locale;

import org.edgo.audio.measure.dsp.FftDsp;
import org.edgo.audio.measure.dsp.Goertzel;
import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.WindowType;

import lombok.Setter;
import lombok.extern.log4j.Log4j2;

/**
 * FFT-based audio analyzer: the raw transform of one frame, fundamental
 * detection, THD, THD+N (IEC 61672:2003 A-weighting), SNR.  Averaging over
 * frames is the {@link CoherentAccumulator}'s; the statistics it needs on
 * the average come back through {@link #recomputeStats}.
 *
 * <p>Supports configurable overlap (0 %, 50 %, 75 %, 87.5 %, 93.75 %) and
 * configurable window function (Rectangular, Hann, Blackman-Harris 4/7,
 * Flat-top, Dolph-Chebyshev 150 dB / 200 dB).
 */
@Log4j2
public class FftAnalyzer {

    /** Shared empty grid published on {@link FftResult} for non-dual-tone (or
     *  non-de-rotated) analysis cycles - avoids a per-cycle allocation on the
     *  hot path. */
    private static final int[] NO_IMD_PRODUCTS = new int[0];

    /** Lower edge (Hz) of the no-hint fundamental search: DC ... this is ignored so
     *  window leakage from a residual DC offset can't win the global max. */
    private static final double FUND_SEARCH_MIN_HZ = 5.0;
    /** Upper edge of the no-hint fundamental search as a fraction of Nyquist:
     *  bins above this can't be a fundamental of interest and are ignored. */
    private static final double FUND_SEARCH_MAX_NYQUIST_FRACTION = 0.7;

    /** IEC 61672 A-weighting pole frequencies (Hz) of the analog magnitude
     *  response R_A(f), and its 1 kHz normalization:
     *  A(f) = 20·log₁₀ R_A(f) + {@link #AW_NORM_1KHZ_DB}, so A(1 kHz) = 0 dB. */
    private static final double AW_POLE1_HZ     = 20.6;
    private static final double AW_POLE2_HZ     = 107.7;
    private static final double AW_POLE3_HZ     = 737.9;
    private static final double AW_POLE4_HZ     = 12194.0;
    private static final double AW_NORM_1KHZ_DB = 2.00;
    /** {@link #AW_NORM_1KHZ_DB} as a power ratio, folded into aWeightPower. */
    private static final double AW_NORM_POWER   = Math.pow(10.0, AW_NORM_1KHZ_DB / 10.0);

    /** Cached window-function table - sized to {@link #cachedWindowSize}
     *  for {@link #cachedWindowType}.  analyze() is called repeatedly on
     *  the FFT worker thread with the same (fftSize, windowType) pair, so
     *  reusing this table avoids both the per-call allocation (8 MB at
     *  fftSize = 1 M) AND the ~1 M Math.cos()/Math.sinh() evaluations
     *  that rebuilding it would cost. */
    private double[]   cachedWindow;
    private int        cachedWindowSize;
    private WindowType cachedWindowType;
    /** Coherent gain (mean) of {@link #cachedWindow} - cached alongside it
     *  so {@code analyze} doesn't re-sum the multi-million-entry table
     *  every analysis cycle. */
    private double     cachedCohGain;
    /** Normalized equivalent noise bandwidth of {@link #cachedWindow} in bins,
     *  {@code N·Σw² / (Σw)²} (Hann: 1.5) - the factor by which one windowed
     *  periodogram bin overstates a bin-width of broadband power.  Cached with
     *  the window; divides the integrated noise sums (never coherent lines). */
    private double     cachedNenbw;

    /** Cached per-bin A-weighting table - entry k = {@link #aWeightPower}(k·freqRes)
     *  for {@link #cachedAweightFreqRes}.  The noise integrals in analyze() and
     *  {@link #recomputeStats} consult it for every non-signal bin each analysis
     *  cycle; without it that is ~halfSize sqrt evaluations per call for a
     *  curve that only changes when the spectrum geometry (fftSize / sample rate)
     *  does. */
    private double[]   cachedAweight;
    private double     cachedAweightFreqRes;

    /** Optional second-tone frequency hint (Hz) for dual-/multi-tone
     *  signals; {@link Double#NaN} disables it.  When set, {@code analyze}
     *  also produces a sub-bin frequency estimate for this tone - via the
     *  same one-frame parabola used for the fundamental
     *  - so a dual-tone readout reports its TRUE frequency rather than a
     *  peak read off the coherently-collapsed average. */
    private double secondToneHintHz = Double.NaN;

    /** Multi-tone flag for the next {@code analyze}.  When set, the
     *  single-sine R-invariant glitch scan is skipped - that invariant
     *  cannot hold for a sum of tones and would otherwise flag nearly
     *  every sample, then invalidate itself and accept all frames anyway. */
    private boolean multiTone = false;

    /** The bins the two pilot tones sit on for the next {@code analyze};
     *  {@code -1} = that slot carries no pilot.  A pilot is a generated line
     *  the phase alignment reads as its time-base reference, held on a whole
     *  bin of the analysis grid, so its position does not follow the
     *  fundamental's fractional one.  Pinning a bin here has three effects:
     *  the pilot gets its own member in the coherent de-rotation family at
     *  that bin, so the frames of an analysis cycle sum without the residual a
     *  nearest-harmonic rotation would leave on it; the result carries the
     *  bin and the frequency it stands for; and the bin plus its window main
     *  lobe stay out of the noise, SNR and ENOB sums.
     *  Written by {@link #setPilotBins(int, int)}. */
    private int pilotBin1 = -1;
    private int pilotBin2 = -1;

    /** Pins the pilot bins for the next {@code analyze} - see
     *  {@link #pilotBin1}.  {@code -1} in a slot clears it, and two cleared
     *  slots are a run with no pilot at all. */
    public void setPilotBins(int p1, int p2) {
        pilotBin1 = p1;
        pilotBin2 = p2;
    }

    /** Spectrum-only fast-path flag for the next {@code analyze}.  When set,
     *  {@code analyze} produces the averaged complex spectrum + dB magnitude +
     *  fundamental / harmonic BIN positions, but SKIPS the throwaway per-cycle
     *  THD / SNR / SINAD / noise-floor sweeps (including the two O(N log N)
     *  noise sorts).  The live FFT view sets this while cross-cycle averaging:
     *  it discards the single-cycle stats and re-derives them with
     *  {@link #recomputeStats} on the accumulated spectrum (display cycles only),
     *  so the per-cycle computation is pure waste.  Leave {@code false} for
     *  one-shot / test callers and single-cycle (non-averaging) display. */
    @Setter
    private boolean spectrumOnly = false;

    /** The STORED spectrum this instance measures - one read back from file,
     *  which arrives as magnitude and phase instead of samples.  Held so every
     *  step that measures it ({@link #rebuildPhasors}, {@link #findFundamental},
     *  {@link #applyDistortionSettings}) speaks about the same spectrum;
     *  {@code null} on an analyser built for live samples. */
    private final FftResult stored;

    /** The intermodulation estimator every dual-tone result's {@code imd} is
     *  derived with - one per analyser, so its noise-floor scratch is reused
     *  across results instead of reallocated per spectrum. */
    private final ImdAnalyzer imdAnalyzer;

    /** An analyser for live samples - what {@link #analyze} is called on. */
    public FftAnalyzer() {
        this(null);
    }

    /** An analyser bound to one stored spectrum, for the file-read path. */
    public FftAnalyzer(FftResult stored) {
        this.stored = stored;
        this.imdAnalyzer = new ImdAnalyzer();
    }

    // =========================================================================
    // Reusable scratch buffers
    // =========================================================================

    /** Per-fftSize scratch pair for the frame's transform, purely internal
     *  to one {@code analyze} call - filled in, consumed, and discarded
     *  before the {@link FftResult} is returned (nothing in the result holds
     *  a reference to it).  Caching it as instance fields and re-using it
     *  across calls eliminates the 2 × fftSize × 8 B allocation per analyze
     *  (16 MB at fftSize = 1 M).  Lazily (re-)allocated when fftSize changes. */
    private double[] scratchF0Re, scratchF0Im;
    /** Half-size scratch for amplitudes - used only inside one
     *  {@code analyze} call by the harmonic-detection and noise-floor
     *  paths; never returned. */
    private double[] scratchAmplLinear;
    /** Half-size scratch for a power average's linear amplitudes - used only
     *  inside one {@code recomputeStats} call; never returned. */
    private double[] scratchPowerLinear;
    /** Signal-bin mask scratch for the stats passes - see
     *  {@link #buildSignalBinMask}; never returned. */
    private boolean[] scratchSignalMask;
    /** Grow-only scratch pools for the noise-floor selection (candidate /
     *  global bin powers) - see {@link #computeNoiseFloorAndExtendSignalMask};
     *  never returned. */
    private double[] scratchNoiseCand;
    private double[] scratchNoiseGlob;

    /** Returns the cached window table for {@code (N, type)}, rebuilding
     *  it via {@link #buildWindow} only when the (size, type) pair
     *  changes. */
    private double[] getCachedWindow(int N, WindowType type) {
        if (cachedWindow != null && cachedWindowSize == N && cachedWindowType == type) {
            return cachedWindow;
        }
        cachedWindow     = FftDsp.buildWindow(N, type);
        cachedWindowSize = N;
        cachedWindowType = type;
        double sum  = 0.0;
        double sum2 = 0.0;
        for (double v : cachedWindow) {
            sum  += v;
            sum2 += v * v;
        }
        cachedCohGain = sum / N;
        cachedNenbw   = (double) N * sum2 / (sum * sum);
        return cachedWindow;
    }

    /** Sets (or clears, with NaN) the second-tone frequency hint for the
     *  next {@code analyze}. */
    public void setSecondToneHintHz(double hz) {
        this.secondToneHintHz = hz;
    }

    /** Marks the next {@code analyze} as a multi-tone signal (dual tone,
     *  etc.).  See {@link #multiTone}. */
    public void setMultiTone(boolean multiTone) {
        this.multiTone = multiTone;
    }

    /** Returns {@code current} if it has the requested length; else
     *  allocates a fresh array.  Caller is responsible for any required
     *  zero-fill (most call sites overwrite every cell, so no fill is
     *  needed; accumulators must explicitly {@code Arrays.fill(0)}). */
    private double[] scratchOrAlloc(double[] current, int size) {
        if (current != null && current.length == size) return current;
        return new double[size];
    }

    /** IEC 61672 A-weighting at {@code freqHz} as a POWER ratio (1 kHz -> 1.0):
     *  the analog magnitude R_A(f) squared, with the +{@value #AW_NORM_1KHZ_DB} dB
     *  1 kHz normalization folded in.  Weighs the integrals behind the
     *  A-suffixed readouts - N+D, THD+N and SINAD -> ENOB - so they reflect
     *  audibility; SNR and N stay unweighted. */
    private double aWeightPower(double freqHz) {
        double f2 = freqHz * freqHz;
        double ra = (AW_POLE4_HZ * AW_POLE4_HZ * f2 * f2)
                / ((f2 + AW_POLE1_HZ * AW_POLE1_HZ)
                   * Math.sqrt((f2 + AW_POLE2_HZ * AW_POLE2_HZ) * (f2 + AW_POLE3_HZ * AW_POLE3_HZ))
                   * (f2 + AW_POLE4_HZ * AW_POLE4_HZ));
        return ra * ra * AW_NORM_POWER;
    }

    /** The cached per-bin {@link #aWeightPower} table for (halfSize, freqRes) -
     *  rebuilt only when the spectrum geometry changes (bin 0 evaluates to 0). */
    private double[] aWeightTable(int halfSize, double freqRes) {
        if (cachedAweight == null || cachedAweight.length != halfSize + 1
                || cachedAweightFreqRes != freqRes) {
            double[] table = new double[halfSize + 1];
            for (int k = 0; k <= halfSize; k++) {
                table[k] = aWeightPower(k * freqRes);
            }
            cachedAweight        = table;
            cachedAweightFreqRes = freqRes;
        }
        return cachedAweight;
    }

    /** Drops every retained fftSize-scaled scratch buffer and the cached
     *  window table - at fftSize 4 M the set idles on ~100 MB after the last
     *  {@code analyze}.  Everything reallocates on demand at the next call,
     *  so this belongs in a stop path, never mid-run.  Callers must not have
     *  an analysis in flight. */
    public void releaseScratch() {
        scratchF0Re    = scratchF0Im    = null;
        scratchAmplLinear = null;
        scratchSignalMask = null;
        scratchNoiseCand  = null;
        scratchNoiseGlob  = null;
        cachedWindow      = null;
        cachedWindowSize  = 0;
        cachedWindowType  = null;
        cachedAweight        = null;
        cachedAweightFreqRes = 0.0;
        imdAnalyzer.releaseScratch();
    }

    /** The windowed FFT of the frame starting at local sample offset
     *  {@code localStart}: the window times the raw samples, transformed in
     *  place into {@code re} / {@code im}. */
    private void frameFft(double[] samples, int localStart, int fftSize,
                          double[] window, double[] re, double[] im) {
        for (int n = 0; n < fftSize; n++) {
            re[n] = samples[localStart + n] * window[n];
            im[n] = 0.0;
        }
        Fft.forward(re, im);
    }

    /** One spectral bin's complex value, in the raw FFT units of
     *  {@link FftResult#re} / {@link FftResult#im}. */
    public record BinPhasor(double re, double im) {}

    /** The raw DFT bin {@code bin} of ONE frame of {@code samples}, the frame
     *  starting at {@code base}: the windowed single-bin Goertzel with its
     *  constant factor undone, in the units of {@link FftResult#re} /
     *  {@link FftResult#im} - the frame's own phase at its own start, with no
     *  de-rotation and nothing summed.  What a phase relation formed inside
     *  one frame reads, where a mean of frames would already be an average.
     *
     *  @param base first sample of the frame
     *  @param bin  integer bin, 0 .. fftSize/2 */
    public BinPhasor frameBinPhasor(double[] samples, int base, int fftSize,
                                    WindowType windowType, int bin) {
        if (Integer.bitCount(fftSize) != 1) {
            throw new IllegalArgumentException("fftSize must be a power of 2, got: " + fftSize);
        }
        if (bin < 0 || bin > fftSize / 2) {
            throw new IllegalArgumentException("Bin out of range: " + bin);
        }
        if (base < 0 || (long) base + fftSize > samples.length) {
            throw new IllegalArgumentException("Frame at " + base + " exceeds the buffer: "
                    + fftSize + " samples needed, " + samples.length + " held");
        }
        double[] window = getCachedWindow(fftSize, windowType);
        Goertzel.SingleBin gz = Goertzel.goertzelBin(samples, base, fftSize, window,
                2.0 * Math.PI * bin / fftSize, 0.0);
        return new BinPhasor(gz.re(), gz.im());
    }

    /**
     * Sub-bin fractional bin κ of the line sitting near {@code hintBin},
     * measured on the frame the same way the fundamental's own κ is: the
     * peak is re-found within ±{@code searchBins} of the hint, then the
     * three lobe bins carry a parabolic interpolation.  Only the lobe bins
     * feed the estimate, so noise elsewhere in the spectrum cannot bias it.
     * The integer part of the answer is the bin the search settled on, so a
     * caller whose line can stray states how far; a caller that knows where
     * its line stands passes 0 and the hint bin is measured as it is, which
     * also keeps a neighbouring line from taking the bin.
     *
     * <p>Serves every line the analysis reports a TRUE frequency for beside the
     * fundamental - the second tone of a dual-tone signal.  Returns
     * {@link Double#NaN} when the hint sits within 3 bins of an edge of the
     * single-sided spectrum, where no full lobe exists to measure.
     */
    private double refineToneKappaAt(int hintBin, int searchBins,
                                     double[] re, double[] im,
                                     int halfSize, int fftSize) {
        if (hintBin < 3 || hintBin > halfSize - 3) {
            return Double.NaN;
        }
        int peak = peakBin(re, im,
                Math.max(1, hintBin - searchBins), Math.min(halfSize, hintBin + searchBins));
        return MathUtil.parabolicBinInterp(re, im, peak, fftSize);
    }

    /**
     * Bin of the second tone of a dual-tone spectrum given as the phasor pair
     * {@code re} / {@code im}: the pair is simply the two strongest tones, so
     * the partner is the strongest local maximum left once the fundamental's
     * own peak - {@code fundBin} and the 2 bins either side of it - is
     * excluded.  No minimum separation and no minimum level; the lower edge is
     * a fixed 10 Hz guard keeping the leakage of a residual DC offset through
     * the window main lobe out of the contest.  Returns {@code -1} when no
     * local maximum qualifies.  Serves the clean frame of a live analysis and
     * a stored spectrum alike ({@link #findSecondTone}).
     */
    private int partnerToneBin(double[] re, double[] im, int fundBin,
                               int halfSize, double freqRes) {
        int    loB      = Math.max(3, (int) Math.ceil(10.0 / freqRes));
        double bestAmp2 = 0.0;
        int    bestK    = -1;
        for (int k = loB; k < halfSize; k++) {
            if (Math.abs(k - fundBin) <= 2) continue;   // F1's own peak, not a partner
            double a2 = re[k] * re[k] + im[k] * im[k];
            if (a2 > bestAmp2
                    && a2 >= re[k - 1] * re[k - 1] + im[k - 1] * im[k - 1]
                    && a2 >  re[k + 1] * re[k + 1] + im[k + 1] * im[k + 1]) {
                bestAmp2 = a2;
                bestK = k;
            }
        }
        return bestK;
    }


    // =========================================================================
    // Result - extracted to the standalone top-level class FftResult so a
    // result is fully independent of the producer instance.
    // =========================================================================

    // (FftResult lives in its own file now.)

    /** Harmonics the THD sums for a result that carries no limit of its own
     *  ({@link FftResult#thdHarmonics} unset): H2..H9.  The analysis itself
     *  always states the limit its caller passes. */
    private static final int THD_HARMONICS_LEGACY = 8;

    // =========================================================================
    // Analysis
    // =========================================================================

    /**
     * The analysis method.
     * It analyses ONE frame: the {@code fftSize} samples at the start of
     * {@code samples}, transformed as they stand.  Averaging over frames is
     * the {@link CoherentAccumulator}'s, which takes this result's spectrum
     * as one frame; {@code step} - the spacing between successive frame
     * starts, in samples - enters only the statistics that depend on how
     * many frames share a sample (the skirt look-ahead), and is the fixed
     * overlap's hop or the drawn one under the random overlap.
     *
     * <p>{@code thdHarmonics} is how many of the evaluated harmonics the THD
     * sums, counted from the 2nd (H2 .. H(thdHarmonics+1)), each still subject
     * to the distortion window; the harmonic table itself always holds all
     * {@code harmonicCount}.  The result carries the number it summed.
     *
     * <p>Writes the analysis into the supplied {@code outResult} slot instead
     * of allocating a fresh result: the slot's bin / harmonic arrays are
     * resized as needed ({@link FftResult#ensureArrays}) and then used
     * directly as the analysis output buffers - no per-cycle allocation for
     * the four fftSize/2+1-long spectrum arrays.  Returns {@code outResult}
     * for convenience.
     */
    public FftResult analyze(double[] samples, int sampleRate,
                                 int fftSize, int harmonicCount, int thdHarmonics,
                                 WindowType windowType, FftOverlap overlap,
                                 double snrFreqMin, double snrFreqMax,
                                 boolean coherentAveraging, double fundRefDbFs,
                                 boolean logSummary, double expectedFundHz,
                                 FftResult outResult, int step) {
        if (Integer.bitCount(fftSize) != 1) {
            throw new IllegalArgumentException("fftSize must be a power of 2, got: " + fftSize);
        }
        if (step < 1) {
            throw new IllegalArgumentException("Frame spacing must be positive, got: " + step);
        }
        if (samples.length < fftSize) {
            throw new IllegalArgumentException(
                    "Signal too short for fftSize=" + fftSize +
                    ": need at least " + fftSize + " samples, got " + samples.length);
        }

        double freqRes = (double) sampleRate / fftSize;
        log.info("FFT: size={}, overlap={}, window={}, averaging={}, freq-res={} Hz, harmonics={}",
                fftSize, overlap.label, windowType,
                coherentAveraging ? "coherent" : "incoherent",
                String.format(Locale.US, "%.4f", freqRes), harmonicCount);

        // --- Window + coherent gain ------------------------------------------
        // Cached: rebuilt only on (fftSize, windowType) change.  At
        // fftSize = 1 M this avoids 8 MB / call plus ~1 M Math.cos()
        // evaluations.
        double[] window  = getCachedWindow(fftSize, windowType);
        double   cohGain = cachedCohGain;   // cached with the window table
        double   nenbw   = cachedNenbw;     // ditto - corrects the noise integrals

        int halfSize = fftSize / 2;

        // Resize the pool slot's bin / harmonic arrays once up-front so
        // the per-bin loops below can write into them directly (no
        // per-call allocation for the four halfSize+1-long arrays).
        outResult.ensureArrays(halfSize + 1, harmonicCount);
        // One frame's power average is the frame itself - its amplitudeDbFs -
        // so it needs no separate array; a pooled slot's earlier one is not
        // this frame's.
        outResult.hasPowerAverage = false;

        // --- The frame -------------------------------------------------------
        scratchF0Re = scratchOrAlloc(scratchF0Re, fftSize);
        scratchF0Im = scratchOrAlloc(scratchF0Im, fftSize);
        double[] f0Re = scratchF0Re;
        double[] f0Im = scratchF0Im;
        frameFft(samples, 0, fftSize, window, f0Re, f0Im);
        // Restrict the fundamental search to FUND_SEARCH_MIN_HZ ... 0.7·Nyquist:
        // window leakage from a residual DC offset can populate the lowest bins at
        // roughly -32 dB (Hann) below DC level, easily beating a deeply notched
        // fundamental, while a bin above 0.7·Nyquist is never a fundamental of
        // interest - either end would otherwise steal the global max.
        int fundSearchMinBin = Math.max(2, (int) Math.ceil(FUND_SEARCH_MIN_HZ / freqRes));
        int fundSearchMaxBin = Math.min(halfSize,
                (int) Math.floor(FUND_SEARCH_MAX_NYQUIST_FRACTION * halfSize));
        int intFundBin;
        if (!Double.isNaN(expectedFundHz) && expectedFundHz > 0.0) {
            // Hint provided - search ±10 bins around the expected bin instead of
            // global max, so a deeply notched fundamental is not lost to a louder
            // harmonic / mains spur / noise spike elsewhere in the spectrum.
            int expectedBin = (int) Math.round(expectedFundHz * fftSize / (double) sampleRate);
            int kLo = Math.max(1,        expectedBin - 10);
            int kHi = Math.min(halfSize, expectedBin + 10);
            intFundBin = peakBin(f0Re, f0Im, kLo, kHi);
            log.info("Fundamental seeded from expected freq {} Hz: bin {} (±10-bin window)",
                    String.format(Locale.US, "%.6f", expectedFundHz), intFundBin);
        } else {
            intFundBin = peakBin(f0Re, f0Im, fundSearchMinBin, fundSearchMaxBin);
            log.info("Fundamental search (no hint): scanned bins {}..{} (~{}..{} Hz); picked bin {} (~{} Hz)",
                    fundSearchMinBin, fundSearchMaxBin,
                    String.format(Locale.US, "%.2f", fundSearchMinBin * freqRes),
                    String.format(Locale.US, "%.2f", fundSearchMaxBin * freqRes),
                    intFundBin,
                    String.format(Locale.US, "%.4f", intFundBin * freqRes));
        }

        // --- Fundamental's fractional bin --------------------------------------
        // A single frame holds no phase step to read it from, so the
        // log-power parabola over the peak and its two neighbours gives it.
        // That is the one-frame readout only: an average pins it from the
        // phase step between consecutive frames (CoherentAccumulator) and the
        // integer bin above is all that pin needs.
        double kFractional = MathUtil.parabolicBinInterp(f0Re, f0Im, intFundBin, fftSize);
        log.info("k_f (one-frame parabola): intBin={}, k_f={}, refinedHz={}",
                intFundBin,
                String.format(Locale.US, "%.6f", kFractional),
                String.format(Locale.US, "%.6f", kFractional * freqRes));

        // --- Second tone (dual-/multi-tone): the same one-frame sub-bin
        // estimate around its hinted bin, on the same frame as the
        // fundamental.  Only the 3 lobe bins feed the parabola, so noise bins
        // can't bias it.
        outResult.fundamental2HzRefined = Double.NaN;
        // The hint is the COMMANDED second-tone frequency - set only when
        // fund-from-generator is on AND the generator is internal.  When it's
        // absent (external generator, or that option off) but the signal IS
        // dual-tone, DETECT the second tone from the CLEAN single frame: the
        // pair is simply THE TWO STRONGEST TONES, so F2 is the strongest local
        // maximum left once the fundamental's own peak is excluded.  The
        // exclusion is only the 2 bins either side of it - those ARE the same
        // peak, not a partner.  No minimum separation and no minimum level:
        // a bass pair sits 8 bins apart at 41 and 89 Hz with 8k points at
        // 48 kHz (5.86 Hz per bin), and a partner may be tens of dB down after
        // the device under test attenuates it.  The lower edge is a fixed
        // 10 Hz guard keeping the leakage of a residual DC offset through the
        // window main lobe out of the contest.
        double effHint = secondToneHintHz;
        if (Double.isNaN(effHint) && multiTone && intFundBin > 0) {
            int bestK = partnerToneBin(f0Re, f0Im, intFundBin, halfSize, freqRes);
            if (bestK > 0) effHint = bestK * freqRes;
        }
        if (!Double.isNaN(effHint) && effHint > 0.0) {
            double kf2 = refineToneKappaAt((int) Math.round(effHint / freqRes), 2,
                    f0Re, f0Im, halfSize, fftSize);
            if (!Double.isNaN(kf2)) {
                outResult.fundamental2HzRefined = kf2 * freqRes;
            }
        }

        // --- Pilot tones: the two bins the generator holds them on.  Written
        // on every call (NaN / -1 when a slot carries no pilot) so a recycled
        // result slot cannot hand the next analysis cycle a pilot that is no
        // longer there.  Nothing is measured at a pilot itself: it stands on a
        // whole bin of this very grid, so its frequency is that bin times the
        // bin width, whatever fractional position the fundamental holds.
        outResult.pilot1Bin = pilotBin1;
        outResult.pilot2Bin = pilotBin2;
        outResult.pilot1HzRefined = (pilotBin1 > 0) ? pilotBin1 * freqRes : Double.NaN;
        outResult.pilot2HzRefined = (pilotBin2 > 0) ? pilotBin2 * freqRes : Double.NaN;
        // The bins each one covers travel with it: the readouts outside this
        // class that integrate bins - the dual-tone TD+N residual - have to
        // leave the same spans out as the noise, SNR and ENOB sums below, and
        // the window the width comes from is known here alone.
        int[] pilot1Span = pilotMaskSpan(pilotBin1, windowType, halfSize);
        outResult.pilot1MaskLoBin = pilot1Span[0];
        outResult.pilot1MaskHiBin = pilot1Span[1];
        int[] pilot2Span = pilotMaskSpan(pilotBin2, windowType, halfSize);
        outResult.pilot2MaskLoBin = pilot2Span[0];
        outResult.pilot2MaskHiBin = pilot2Span[1];

        // --- Dual-tone: publish the intermodulation-product grid -------------
        // Every product a·κ_F1 + b·κ_F2 (b≠0, |a|+|b| ≤ order) that lands in
        // band is listed with its bin, so the predistortion engine can read
        // each product's phasor straight from re/im[bin].  The list doubles
        // as the product part of the coherent average's family
        // (CoherentAccumulator): each entry's hump turns by a·Φ1 + b·Φ2.
        // Default to "no products published": recycled result slots must not
        // leak a previous dual-tone analysis cycle's grid into a single-tone
        // analysis cycle.
        outResult.imdProductA   = NO_IMD_PRODUCTS;
        outResult.imdProductB   = NO_IMD_PRODUCTS;
        outResult.imdProductBin = NO_IMD_PRODUCTS;
        if (coherentAveraging && !Double.isNaN(outResult.fundamental2HzRefined)
                && outResult.fundamental2HzRefined > 0.0 && kFractional > 0.0) {
            double kappa2 = outResult.fundamental2HzRefined / freqRes;
            int    k2     = (int) Math.round(kappa2);
            if (k2 >= 1 && k2 <= halfSize) {          // the second tone is present, in band
                final int ORDER = Math.max(5, harmonicCount + 1);
                int[] ta = new int[(2 * ORDER + 1) * (2 * ORDER + 1)];
                int[] tb = new int[ta.length];
                int[] tk = new int[ta.length];
                int cap = 0;
                for (int a = -ORDER; a <= ORDER; a++) {
                    for (int b = -ORDER; b <= ORDER; b++) {
                        if (b == 0 || Math.abs(a) + Math.abs(b) > ORDER) continue;
                        double kp = a * kFractional + b * kappa2;
                        if (kp < 1.0 || kp > halfSize) continue;
                        tk[cap] = (int) Math.round(kp);
                        ta[cap] = a;
                        tb[cap] = b;
                        cap++;
                    }
                }
                outResult.imdProductA   = Arrays.copyOf(ta, cap);
                outResult.imdProductB   = Arrays.copyOf(tb, cap);
                outResult.imdProductBin = Arrays.copyOf(tk, cap);
            }
        }

        // --- Single-sided amplitude & phase spectrum -------------------------
        double   normFactor = 1.0 / ((double) fftSize * cohGain);
        // re / im / amplDbFs / phaseDeg are stored in outResult and outlive
        // this call - but the pool guarantees outResult is owned exclusively
        // by this analysis until the worker publishes it.  Aliasing the
        // slot's pre-sized arrays as locals avoids per-cycle allocation of
        // four halfSize+1-long arrays.  amplLinear is purely local and
        // overwritten cell-by-cell below - safe to reuse from scratch.
        double[] outRe      = outResult.re;
        double[] outIm      = outResult.im;
        scratchAmplLinear   = scratchOrAlloc(scratchAmplLinear, halfSize + 1);
        double[] amplLinear = scratchAmplLinear;
        double[] amplDbFs   = outResult.amplitudeDbFs;
        double[] phaseDeg   = outResult.phaseDeg;

        // Per-bin and independent -> parallelize across cores (the 1M× log10/atan2
        // is the single biggest compute chunk).  Disjoint k-writes, no recursion.
        // The result carries the frame as it stands: the complex phasor per
        // bin under coherent averaging, the magnitude in re (im = 0) under
        // incoherent - the form each average of frames takes, so a single
        // frame and an average read alike.
        BinRanges.parallel(halfSize + 1, (lo, hi) -> {
            for (int k = lo; k < hi; k++) {
                double mag = Math.sqrt(f0Re[k] * f0Re[k] + f0Im[k] * f0Im[k]);
                if (coherentAveraging) {
                    outRe[k] = f0Re[k];
                    outIm[k] = f0Im[k];
                    phaseDeg[k] = Math.toDegrees(Math.atan2(f0Im[k], f0Re[k]));
                } else {
                    outRe[k] = mag;
                    outIm[k] = 0.0;
                    phaseDeg[k] = 0.0;
                }
                amplLinear[k] = (k == 0 || k == halfSize)
                        ? mag * normFactor
                        : mag * normFactor * 2.0;
                amplDbFs[k] = amplLinear[k] > 1e-15
                        ? 20.0 * Math.log10(amplLinear[k])
                        : -300.0;
            }
        });

        // --- Fundamental: the bin the search above settled on ---------------
        int    fundBin        = intFundBin;
        double fundHz         = fundBin * freqRes;
        double fundHzRefined  = kFractional * freqRes;   // sub-bin accurate frequency
        double fundDbFs       = amplDbFs[fundBin];
        double fundLinear     = amplLinear[fundBin];

        // refLin is the fundamental amplitude used as the *denominator* for
        // every ratio: THD, SNR, THD+N, harmonic %.  When fundRefDbFs is set
        // (e.g. an external twin-T notch suppresses only H1 by a drift-prone
        // amount, so amplLinear[fundBin] is unreliable while H2..Hn stay
        // valid as measured), use the user's true fundamental as the anchor.
        // The measured fundDbFs / fundLinear are still stored verbatim into
        // Result - downstream code (frequency response calibration scale, chart, cal-CSV anchor)
        // depends on those reflecting the actual FFT measurement.
        // The reference arrives already in dBFS - the analyzer speaks one scale
        // only; the GUI callers convert their dBV inputs at the boundary
        // (unit translation is a presentation concern, not a measurement one).
        // refLin then lives in the same FS-relative units as amplLinear[k], so
        // harmonic % / THD / SNR don't pick up a spurious adcFsVoltageRms factor.
        double fundTrueDbFs = Double.NaN;
        double refLin;
        if (Double.isNaN(fundRefDbFs)) {
            refLin = fundLinear;
        } else {
            fundTrueDbFs = fundRefDbFs;
            refLin = Math.pow(10.0, fundTrueDbFs / 20.0);
        }

        if (Double.isNaN(fundRefDbFs)) {
            log.info("Fundamental: bin={}, freq={} Hz, refined={} Hz, level={} dBFS",
                    fundBin,
                    String.format(Locale.US, "%.3f", fundHz),
                    String.format(Locale.US, "%.6f", fundHzRefined),
                    String.format(Locale.US, "%.3f", fundDbFs));
        } else {
            log.info("Fundamental: bin={}, freq={} Hz, refined={} Hz, measured={} dBFS, ref={} dBFS",
                    fundBin,
                    String.format(Locale.US, "%.3f", fundHz),
                    String.format(Locale.US, "%.6f", fundHzRefined),
                    String.format(Locale.US, "%.3f", fundDbFs),
                    String.format(Locale.US, "%.3f", fundRefDbFs));
        }

        // --- Harmonics -------------------------------------------------------
        // Aliased onto the pool slot's harmonic arrays - sized by
        // ensureArrays at the top of analyze.  hLinear is purely local
        // (intermediate THD-sum term), kept as a fresh allocation; it
        // doesn't outlive the call.
        int[]    hBins   = outResult.harmonicBins;
        double[] hHz     = outResult.harmonicHz;
        double[] hDbFs   = outResult.harmonicDbFs;
        // hLinear holds the proportional-bin-combined harmonic amplitude
        // (see detectHarmonics) - used by the THD sum below so it matches
        // the per-harmonic readout, instead of resampling from amplLinear.
        double[] hLinear = new double[harmonicCount];
        double[] hPct    = outResult.harmonicPct;
        detectHarmonics(kFractional, fundHzRefined, halfSize,
                amplLinear, amplDbFs, refLin,
                harmonicCount, hBins, hHz, hDbFs, hLinear, hPct);

        // --- Averaging fast-path: skip the per-cycle THD / SNR / SINAD /
        // noise-floor sweeps (including the two O(N log N) noise sorts).  While
        // the live view is cross-cycle averaging it throws these single-cycle
        // stats away and re-derives them with recomputeStats() on the
        // ACCUMULATED spectrum (display cycles only) - so computing them here ~3×
        // per second is pure waste.  Everything recomputeStats() and the worker
        // actually read was already produced above: the averaged complex
        // spectrum (re/im), the dB magnitude (amplitudeDbFs - read by the
        // worker's tone-detect / overlay / dBV lift), and the fundamental +
        // harmonic BIN positions (recomputeStats reuses them, never re-finds).
        // The skipped scalars are neutralised so a recycled result slot cannot
        // leak a previous cycle's numbers if anything reads them before the
        // display rebuild.
        if (spectrumOnly) {
            outResult.fftSize                    = fftSize;
            outResult.sampleRate                 = sampleRate;
            outResult.frameCount                 = 1;
            outResult.freqResolution             = freqRes;
            outResult.windowType                 = windowType;
            outResult.overlap                    = overlap;
            outResult.stepSamples                = step;
            // The display path re-derives all stats via recomputeStats() on THIS
            // result - it needs the window's NENBW stamped or its noise-integral
            // correction silently no-ops (that exact miss shipped the first
            // version of the ENBW fix as a visible no-change).
            outResult.windowNenbwBins            = nenbw;
            outResult.windowCoherentGain         = cohGain;
            outResult.fundamentalBin             = fundBin;
            outResult.fundamentalHz              = fundHz;
            outResult.fundamentalHzRefined       = fundHzRefined;
            outResult.fundamentalDbFs            = fundDbFs;
            outResult.fundamentalLinear          = fundLinear;
            outResult.harmonicCount              = harmonicCount;
            outResult.thdHarmonics               = thdHarmonics;
            outResult.snrFreqMin                 = snrFreqMin;
            outResult.snrFreqMax                 = snrFreqMax;
            outResult.coherentAveraging          = coherentAveraging;
            outResult.fundamentalTrueDbFs        = fundTrueDbFs;
            outResult.thdPct                     = 0.0;
            outResult.thdDb                      = -300.0;
            outResult.thdNDb                     = 300.0;
            outResult.snrDb                      = 300.0;
            outResult.sinadDb                    = 300.0;
            outResult.awThdNDb                   = 300.0;
            outResult.enobSinadDb                = 300.0;
            outResult.noisePower                 = 0.0;
            outResult.awNoisePower               = 0.0;
            outResult.avgNoiseFloorDbFs          = fundDbFs - 300.0;
            outResult.fundamentalDynExclusionHz  = 0.0;
            // The intermodulation readout is a statistic too - re-derived with
            // the rest by recomputeStats(); a pooled slot must not republish a
            // previous dual-tone analysis cycle's products.
            outResult.imd                        = null;
            outResult.preCorrectionPeaks         = null;
            outResult.imdPreCorrectionPeaks      = null;
            // The reference channel's frequencies are the worker's to write
            // after this returns; cleared here for the same reason
            // preCorrectionPeaks is - a pooled slot must not hand the next
            // analysis cycle the previous one's numbers.
            outResult.referenceFundamentalHzRefined  = Double.NaN;
            outResult.referenceFundamental2HzRefined = Double.NaN;
            outResult.captureRawPeaks();   // raw F + peak phasors for the DAC-predistortion de-embed
            return outResult;
        }

        double snrLo = snrFreqMin > 0.0 ? snrFreqMin : 0.0;
        double snrHi = snrFreqMax > 0.0 ? snrFreqMax : Double.MAX_VALUE;

        // --- THD - H2..H(thdHarmonics+1) that fall within dist range --------
        // awHarmPowerSum carries the same powers through the IEC A-weighting
        // curve for the A-suffixed readouts (N+D / THD+N / SINAD -> ENOB).
        double harmPowerSum   = 0.0;
        double awHarmPowerSum = 0.0;
        for (int h = 0; h < Math.min(harmonicCount, thdHarmonics); h++) {
            if (hBins[h] > 0) {
                double harmFreq = hHz[h];
                if (harmFreq >= snrLo && harmFreq <= snrHi) {
                    double a = hLinear[h];
                    harmPowerSum   += a * a;
                    awHarmPowerSum += a * a * aWeightPower(harmFreq);
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
        //              Each line is masked over the window's main lobe plus
        //              the leakage guard.
        int exclBins = FftDsp.mainLobeBins(windowType) + FftDsp.LEAKAGE_GUARD_BINS;
        boolean[] isSignalBin = buildSignalBinMask(halfSize, fundBin, hBins, harmonicCount,
                exclBins, windowType, outResult.pilot1Bin, outResult.pilot2Bin);

        // --- SNR - integrated noise over the measurement band ----------------
        // Fundamental and all harmonics are excluded via isSignalBin.
        // SNR = signal_power / sum(noise_bins_in_range) -> bandwidth-dependent.

        NoiseFloor nf = computeNoiseFloorAndExtendSignalMask(
                amplLinear, isSignalBin, halfSize, freqRes, snrLo, snrHi, fundBin, exclBins,
                skirtLookAheadBins(fftSize, overlap.minHopSamples(fftSize)));
        double medianNoisePow = nf.medianNoisePow;
        int    dynWidthBins   = nf.dynWidthBins;
        if (dynWidthBins > exclBins) {
            log.info("Fundamental dynamic exclusion: +/-{} bins ({} Hz each side)",
                    dynWidthBins, String.format(Locale.US, "%.2f", dynWidthBins * freqRes));
        }

        // SNR: signal RMS² / total unweighted noise power, integrated over
        // every non-signal bin in the SNR range.  Harmonics are excluded via
        // isSignalBin; the dynamic walk above keeps window leakage from the
        // fundamental out of the noise sum.  Signal² is refLin² so an
        // external twin-T notch on H1 cannot deflate the numerator.
        double[] awTable    = aWeightTable(halfSize, freqRes);
        double noisePower   = 0.0;
        double awNoisePower = 0.0;   // same sum through the A-weighting curve
        int bandBins    = 0;
        int countedBins = 0;
        for (int k = 1; k <= halfSize; k++) {
            double freq = k * freqRes;
            if (freq >= snrLo && freq <= snrHi) {
                bandBins++;
                if (!isSignalBin[k]) {
                    double p = amplLinear[k] * amplLinear[k];
                    noisePower   += p;
                    awNoisePower += p * awTable[k];
                    countedBins++;
                }
            }
        }
        // The excluded zones (fundamental skirt, harmonics) held noise too -
        // estimate it as the surrounding floor by rescaling the counted sum to
        // the full band width, instead of silently dropping those slots (which
        // flattered SNR by the excluded fraction; the same effect, with far
        // wider zones, measured 0.8 dB in the vendor QA40x app).  Exact for a
        // flat floor, and a better estimate than zero for a structured one.
        if (countedBins > 0) {
            double rescale = (double) bandBins / countedBins;
            noisePower   *= rescale;
            awNoisePower *= rescale;
        }
        // A windowed periodogram bin holds NENBW bin-widths of broadband power
        // (Hann: 1.5), so summing noise bins as-is overstates the band's noise
        // power by exactly that factor - divide it back out.  Coherent lines
        // (fundamental, harmonics) need NO such correction: a sine's amplitude
        // is already window-normalized via cohGain, so refLin² and harmPowerSum
        // stay untouched.  Without this, SNR / SINAD / ENOB / THD+N read
        // 10·log10(NENBW) pessimistic (1.76 dB for Hann) - found against a
        // 23-bit-TPDF-dither hardware simulator where QA40x's software matched
        // the constructed theory value and we sat exactly one ENBW low.
        noisePower   /= nenbw;
        awNoisePower /= nenbw;
        double snrDb;
        if (noisePower <= 0) {
            log.warn("SNR: no non-signal bins remain in measurement range");
            snrDb = 300.0;
        } else {
            snrDb = 10.0 * Math.log10((refLin * refLin) / noisePower);
        }

        // SINAD: signal RMS² / (noise + distortion) - the integrated
        // measurement-band noise plus the in-band harmonic power, UNWEIGHTED
        // (the MT-003 definition; THD+N below is its reciprocal and ENOB
        // derives from it - none of them carries a weighting curve).
        double sinadDenom = noisePower + harmPowerSum;
        double sinadDb    = sinadDenom > 0
                ? 10.0 * Math.log10((refLin * refLin) / sinadDenom)
                : 300.0;
        // A-weighted noise+distortion for the N+D readout ONLY - the one
        // audibility figure (IEC 61672 supplies the curve; the weighting is
        // this readout's own choice, worth ~5.3 dB on an HF-rising Cosmos
        // floor).  Negative dB relative to the signal.
        double awDenom  = awNoisePower + awHarmPowerSum;
        double awThdNDb = awDenom > 0 && refLin > 0
                ? 10.0 * Math.log10(awDenom / (refLin * refLin))
                : 300.0;
        // ENOB basis: the SAME unweighted in-band denominator referred to
        // FULL SCALE - where the signal level cancels exactly, because the
        // denominator is already full-scale-relative power.  No fundamental
        // term appears, so neither the notched bin level nor the
        // manual-fundamental reference can skew the figure under any
        // de-embedding combination.  Not the full Nyquist band: an
        // oversampled sigma-delta parks its shaped noise above the audio
        // band by design, and a full-band integral reads that as bits lost.
        double enobSinadDb = sinadDenom > 0
                ? -10.0 * Math.log10(sinadDenom)
                : 300.0;
        // Average noise floor: median of clean bins converted to dBFS
        double avgNoiseFloorDbFs = medianNoisePow > 0
                ? 20.0 * Math.log10(Math.sqrt(medianNoisePow))
                : fundDbFs - 300.0;

        // THD+N is the reciprocal of SINAD: (noise + distortion) / signal,
        // expressed in dB -> −sinadDb.  Same band, same numerator definition,
        // unweighted like SINAD.
        double thdNDb = -sinadDb;

        // outResult's arrays were aliased above (outRe/outIm/amplDbFs/
        // phaseDeg/hBins/hHz/hDbFs/hPct point into outResult).  Only
        // the scalars need to be written here.
        outResult.fftSize                    = fftSize;
        outResult.sampleRate                 = sampleRate;
        outResult.frameCount                 = 1;
        outResult.freqResolution             = freqRes;
        outResult.windowType                 = windowType;
        outResult.overlap                    = overlap;
        outResult.stepSamples                = step;
        outResult.fundamentalBin             = fundBin;
        outResult.fundamentalHz              = fundHz;
        outResult.fundamentalHzRefined       = fundHzRefined;
        outResult.fundamentalDbFs            = fundDbFs;
        outResult.fundamentalLinear          = fundLinear;
        outResult.harmonicCount              = harmonicCount;
        outResult.thdHarmonics               = thdHarmonics;
        outResult.thdPct                     = thdPct;
        outResult.thdDb                      = thdDb;
        outResult.thdNDb                     = thdNDb;
        outResult.snrDb                      = snrDb;
        outResult.sinadDb                    = sinadDb;
        outResult.awThdNDb                   = awThdNDb;
        outResult.enobSinadDb                = enobSinadDb;
        outResult.snrFreqMin                 = snrFreqMin;
        outResult.snrFreqMax                 = snrFreqMax;
        outResult.coherentAveraging          = coherentAveraging;
        outResult.noisePower                 = noisePower;
        outResult.awNoisePower               = awNoisePower;
        outResult.windowNenbwBins            = nenbw;
        outResult.windowCoherentGain         = cohGain;
        outResult.avgNoiseFloorDbFs          = avgNoiseFloorDbFs;
        outResult.fundamentalTrueDbFs        = fundTrueDbFs;
        outResult.fundamentalDynExclusionHz  = dynWidthBins * freqRes;
        deriveImd(outResult);
        // preCorrectionPeaks and imdPreCorrectionPeaks are owned by the view
        // (set after analyze returns when calibration is loaded); reset here
        // so a previous analysis cycle's value doesn't leak across pool slot
        // recycle.  The reference channel's two frequencies are the worker's
        // in exactly the same way.
        outResult.preCorrectionPeaks    = null;
        outResult.imdPreCorrectionPeaks = null;
        outResult.referenceFundamentalHzRefined  = Double.NaN;
        outResult.referenceFundamental2HzRefined = Double.NaN;
        outResult.captureRawPeaks();   // raw F + peak phasors for the DAC-predistortion de-embed
        if (logSummary) logSummary(outResult);
        return outResult;
    }

    /** The trailing THD / THD+N / SNR lines of an analysis, from the result's
     *  own numbers - after the statistics are final, whether the analyser
     *  wrote them for one frame or {@link #recomputeStats} for an average. */
    public void logSummary(FftResult r) {
        if (!log.isInfoEnabled()) return;
        log.info("THD   : {}%  ({} dB)",
                String.format(Locale.US, "%.8f", r.thdPct),
                String.format(Locale.US, "%.2f", r.thdDb));
        log.info("THD+N : {} dB", String.format(Locale.US, "%.2f", r.thdNDb));
        if (r.snrFreqMin > 0.0 || r.snrFreqMax > 0.0) {
            log.info("SNR   : {} dB  (range: {}-{} Hz)",
                    String.format(Locale.US, "%.2f", r.snrDb),
                    String.format(Locale.US, "%.0f", r.snrFreqMin > 0.0 ? r.snrFreqMin : 0.0),
                    String.format(Locale.US, "%.0f", r.snrFreqMax > 0.0 ? r.snrFreqMax : Double.MAX_VALUE));
        } else {
            log.info("SNR   : {} dB", String.format(Locale.US, "%.2f", r.snrDb));
        }
    }

    /**
     * Rebuilds the bin phasors of a spectrum that arrives as magnitude and
     * phase instead of samples - a stored spectrum read back from file.
     * {@code amplitudeDbFs} and {@code phaseDeg} are the source; {@code re} and
     * {@code im} are written from them, so every later step that reads phasors
     * (the sub-bin refinement, the intermodulation products) sees the same
     * spectrum the stats are derived from.
     */
    public void rebuildPhasors() {
        if (stored == null) return;     // no spectrum held - nothing to rebuild
        int n = stored.amplitudeDbFs.length;
        for (int k = 0; k < n; k++) {
            double lin = Math.pow(10.0, stored.amplitudeDbFs[k] / 20.0);
            double rad = Math.toRadians(stored.phaseDeg != null && k < stored.phaseDeg.length
                    ? stored.phaseDeg[k] : 0.0);
            stored.re[k] = lin * Math.cos(rad);
            stored.im[k] = lin * Math.sin(rad);
        }
    }

    /**
     * Measures the fundamental of a spectrum that states none: the strongest
     * bin at or above {@code minHz}, refined to sub-bin by the same parabolic
     * fit the live path uses, written to {@code fundamentalBin},
     * {@code fundamentalHz} and {@code fundamentalHzRefined}.
     *
     * <p>The floor keeps the search off the DC and mains foot, which outweighs
     * the tone in a spectrum recorded through a notch at the fundamental.  A
     * capture knows its own fundamental and states it; this is for the stored
     * spectrum whose file does not.
     */
    public void findFundamental(double minHz) {
        if (stored == null) return;     // no spectrum held - nothing to search
        int    halfSize = stored.amplitudeDbFs.length - 1;
        double freqRes  = stored.freqResolution;
        int    minBin   = Math.min(halfSize, Math.max(1, (int) Math.ceil(minHz / freqRes)));
        int    fundBin  = peakBin(stored.re, stored.im, minBin, halfSize);
        stored.fundamentalBin       = fundBin;
        stored.fundamentalHz        = fundBin * freqRes;
        stored.fundamentalHzRefined =
                MathUtil.parabolicBinInterp(stored.re, stored.im, fundBin, stored.fftSize) * freqRes;
    }

    /**
     * Measures the second tone of a stored dual-tone spectrum: the strongest
     * partner of the fundamental ({@link #partnerToneBin}, the detection the
     * live path runs when no hint is given), refined to sub-bin the way the
     * live path refines a frame, written to
     * {@code fundamental2HzRefined} - {@link Double#NaN} when no partner is
     * found.  Needs {@code fundamentalBin} (a stated one, or
     * {@link #findFundamental}) and the phasors ({@link #rebuildPhasors}).
     *
     * <p>The detection sets no minimum level, so on a single-tone spectrum it
     * names the strongest spur: call it only for a spectrum known to be dual
     * tone, as the live path searches only on a multi-tone signal.
     */
    public void findSecondTone() {
        if (stored == null) return;     // no spectrum held - nothing to search
        int    halfSize = stored.amplitudeDbFs.length - 1;
        double freqRes  = stored.freqResolution;
        stored.fundamental2HzRefined = Double.NaN;
        if (stored.fundamentalBin <= 0) return;
        int partnerBin = partnerToneBin(stored.re, stored.im, stored.fundamentalBin,
                halfSize, freqRes);
        if (partnerBin <= 0) return;
        double kf2 = refineToneKappaAt(partnerBin, 2, stored.re, stored.im,
                halfSize, stored.fftSize);
        if (!Double.isNaN(kf2)) {
            stored.fundamental2HzRefined = kf2 * freqRes;
        }
    }

    /**
     * Applies the distortion settings to a STORED spectrum and recomputes its
     * statistics: the harmonic ladder from the refined fundamental, the manual
     * fundamental anchor, the distortion band, the number of harmonics the THD
     * sum takes, then {@link #recomputeStats}.  The harmonic arrays grow with
     * {@code calcHarmonics} and the spectrum itself is preserved.
     *
     * <p>The live path carries the same settings into {@link #analyze} as
     * arguments; a stored spectrum has no samples to analyse, so the settings
     * are written onto the result here instead.  {@code fundRefDbFs} is the
     * manual anchor in dBFS, {@code NaN} when the reading stands on the
     * measured fundamental; {@code distMinHz} / {@code distMaxHz} are 0 when
     * the band is not limited.
     */
    public void applyDistortionSettings(int calcHarmonics, int thdHarmonics,
                                        double fundRefDbFs, double distMinHz, double distMaxHz) {
        if (stored == null) return;     // no spectrum held - nothing to recompute
        // Spectrum kept; the harmonic arrays resize only when the count changed.
        stored.ensureArrays(stored.amplitudeDbFs.length, calcHarmonics);
        stored.harmonicCount = calcHarmonics;
        stored.placeHarmonics();
        stored.fundamentalTrueDbFs = fundRefDbFs;
        stored.snrFreqMin          = distMinHz;
        stored.snrFreqMax          = distMaxHz;
        stored.thdHarmonics        = thdHarmonics;
        recomputeStats(stored);
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
     * (because {@code re}/{@code im} are unscaled FFT outputs - the
     * analyzer-side normFactor and single-sided ×2 are folded into
     * {@code amplitudeDbFs}, not into {@code re}/{@code im}).
     *
     * <p>{@code fundamentalBin} and {@code harmonicBins[]} are kept unchanged -
     * smooth per-bin scaling does not move peaks, so the originally identified
     * bin positions remain valid.
     *
     * <p>Logic mirrors the stats portion of {@link #analyze}; keep both in sync.
     */
    public void recomputeStats(FftResult r) {
        int    halfSize = r.fftSize / 2;
        double freqRes  = r.freqResolution;
        double snrLo    = r.snrFreqMin > 0.0 ? r.snrFreqMin : 0.0;
        double snrHi    = r.snrFreqMax > 0.0 ? r.snrFreqMax : Double.MAX_VALUE;

        // Derive properly-scaled linear amplitudes from amplitudeDbFs.
        // Reuses the instance scratch (the view retains its analyzer for
        // exactly this) - otherwise 8.4 MB allocated per displayed frame at
        // fftSize 2 M.  Every cell is overwritten below, so no zero-fill.
        final double[] amplLinear = scratchOrAlloc(scratchAmplLinear, halfSize + 1);
        scratchAmplLinear = amplLinear;
        BinRanges.parallel(halfSize + 1, (lo, hi) -> {
            for (int k = lo; k < hi; k++) {
                amplLinear[k] = r.amplitudeDbFs[k] > -290.0
                        ? Math.pow(10.0, r.amplitudeDbFs[k] / 20.0)
                        : 0.0;
            }
        });

        // --- Fundamental ----------------------------------------------------
        int    fundBin    = r.fundamentalBin;
        double fundLinear = amplLinear[fundBin];
        double fundDbFs   = r.amplitudeDbFs[fundBin];
        r.fundamentalLinear = fundLinear;
        r.fundamentalDbFs   = fundDbFs;
        // Mirror analyze(): when --fund-v / --fund-dbv (manual fundamental) was
        // provided, use it as the ratio denominator (THD / SNR / THD+N / H%); the
        // measured fundamental scalars above stay verbatim so downstream cal/chart
        // logic keeps working.  fundamentalTrueDbFs is already in dBFS (converted
        // once at the input boundary), so refLin reads straight off it.
        double refLin = Double.isNaN(r.fundamentalTrueDbFs)
                ? fundLinear
                : Math.pow(10.0, r.fundamentalTrueDbFs / 20.0);

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
            // Held at the harmonic's local noise floor from below, where the
            // result carries one (FftResult#lineFloorDbFs): every reading
            // below derives from this level, THD among them.
            double aCombined = r.atLeastLineFloor(binMain, Math.sqrt((1.0 - w) * pMain + w * pAdj));
            r.harmonicBins[h] = binMain;
            r.harmonicDbFs[h] = aCombined > 1e-15 ? 20.0 * Math.log10(aCombined) : -300.0;
            r.harmonicPct[h]  = refLin > 0 ? aCombined / refLin * 100.0 : 0.0;
            hLinear[h]        = aCombined;
        }

        // --- THD (H2..H(thdHarmonics+1) within SNR range) -------------------
        // awHarmPowerSum: the same powers through the IEC A-weighting curve
        // for the A-suffixed readouts - see analyze().  A result never told
        // its limit sums the historic H2..H9.
        int thdHarmonics = r.thdHarmonics > 0 ? r.thdHarmonics : THD_HARMONICS_LEGACY;
        double harmPowerSum   = 0.0;
        double awHarmPowerSum = 0.0;
        for (int h = 0; h < Math.min(harmonicCount, thdHarmonics); h++) {
            int hb = r.harmonicBins[h];
            if (hb > 0) {
                double freq = r.harmonicHz[h];
                if (freq >= snrLo && freq <= snrHi) {
                    double a = hLinear[h];
                    harmPowerSum   += a * a;
                    awHarmPowerSum += a * a * aWeightPower(freq);
                }
            }
        }
        r.thdPct = refLin > 0 ? Math.sqrt(harmPowerSum) / refLin * 100.0 : 0.0;
        r.thdDb  = r.thdPct > 0  ? 20.0 * Math.log10(r.thdPct / 100.0)        : -300.0;

        // --- Signal-bin mask: fundamental + every harmonic over the window's
        //     main lobe plus the leakage guard ---
        int exclBins = FftDsp.mainLobeBins(r.windowType) + FftDsp.LEAKAGE_GUARD_BINS;
        boolean[] isSignalBin = buildSignalBinMask(halfSize, fundBin,
                r.harmonicBins, harmonicCount, exclBins, r.windowType,
                r.pilot1Bin, r.pilot2Bin);

        // --- The spectrum the noise is read from ---------------------------
        // The power average where the result carries one - the coherent mean
        // lets noise, and every part of a tone not coherent with it, fall with
        // the frames held, its power does not - else the spectrum itself,
        // which then is the power reading.  The mask below is found on this
        // same spectrum, so what it excludes as signal is what the noise sum
        // would otherwise count.
        final double[] noiseLinear;
        if (r.hasPowerAverage) {
            noiseLinear = scratchOrAlloc(scratchPowerLinear, halfSize + 1);
            scratchPowerLinear = noiseLinear;
            final double[] powDbFs = r.powerAverageDbFs;
            BinRanges.parallel(halfSize + 1, (lo, hi) -> {
                for (int k = lo; k < hi; k++) {
                    noiseLinear[k] = powDbFs[k] > -290.0 ? Math.pow(10.0, powDbFs[k] / 20.0) : 0.0;
                }
            });
        } else {
            noiseLinear = amplLinear;
        }

        // --- Noise floor + signal-mask extension (shared with analyze()) ---
        // recomputeStats discards dynWidthBins - the
        // Result.fundamentalDynExclusionHz is final and was set on the
        // original analyze() pass; we only need the median noise power.
        double medianNoisePow = computeNoiseFloorAndExtendSignalMask(
                noiseLinear, isSignalBin, halfSize, freqRes, snrLo, snrHi, fundBin, exclBins,
                skirtLookAheadBins(r.fftSize, r.overlap != null
                        ? r.overlap.minHopSamples(r.fftSize) : r.stepSamples))
                .medianNoisePow;
        // The floor the display is ranged on is the drawn spectrum's: over the
        // same noise bins, read off the displayed levels.
        double displayMedianPow = medianNoisePow;
        if (r.hasPowerAverage) {
            double displayed = bandMedianPow(amplLinear, isSignalBin, halfSize, freqRes, snrLo, snrHi);
            if (displayed >= 0.0) displayMedianPow = displayed;
        }

        // --- SNR (signal RMS² / total unweighted noise power) ---------------
        double[] awTable    = aWeightTable(halfSize, freqRes);
        double noisePower   = 0.0;
        double awNoisePower = 0.0;   // same sum through the A-weighting curve
        int bandBins    = 0;
        int countedBins = 0;
        for (int k = 1; k <= halfSize; k++) {
            double freq = k * freqRes;
            if (freq >= snrLo && freq <= snrHi) {
                bandBins++;
                if (!isSignalBin[k]) {
                    double p = noiseLinear[k] * noiseLinear[k];
                    noisePower   += p;
                    awNoisePower += p * awTable[k];
                    countedBins++;
                }
            }
        }
        // Excluded-zone rescale + NENBW - same corrections as analyze(), see
        // the comments there.
        if (countedBins > 0) {
            double rescale = (double) bandBins / countedBins;
            noisePower   *= rescale;
            awNoisePower *= rescale;
        }
        // Same NENBW correction as analyze() - see the comment there.  The
        // factor is read from the RESULT (stamped at analysis time), not the
        // current window cache: a recompute must match the spectrum it is
        // recomputing from, whatever window is selected by now.
        if (r.windowNenbwBins > 0) {
            noisePower   /= r.windowNenbwBins;
            awNoisePower /= r.windowNenbwBins;
        }
        r.noisePower        = noisePower;
        r.snrDb             = noisePower <= 0
                ? 300.0
                : 10.0 * Math.log10((refLin * refLin) / noisePower);
        // SINAD, UNWEIGHTED (MT-003) - see analyze(); the A-curve exists only
        // in the N+D figure below.
        double sinadDenom = noisePower + harmPowerSum;
        r.sinadDb = sinadDenom > 0
                ? 10.0 * Math.log10((refLin * refLin) / sinadDenom)
                : 300.0;
        double awDenom = awNoisePower + awHarmPowerSum;
        r.awThdNDb = awDenom > 0 && refLin > 0
                ? 10.0 * Math.log10(awDenom / (refLin * refLin))
                : 300.0;
        // ENOB basis - the same unweighted in-band denominator referred to
        // FULL SCALE, where the signal level cancels exactly; see analyze().
        // No fundamental term, so no de-embedding combination can skew it.
        r.enobSinadDb = sinadDenom > 0
                ? -10.0 * Math.log10(sinadDenom)
                : 300.0;
        r.avgNoiseFloorDbFs = displayMedianPow > 0
                ? 20.0 * Math.log10(Math.sqrt(displayMedianPow))
                : fundDbFs - 300.0;

        // THD+N is the reciprocal of SINAD: −sinadDb in dB.
        r.awNoisePower = awNoisePower;
        r.thdNDb       = -r.sinadDb;
        deriveImd(r);
    }

    /**
     * Writes {@code r.imd}: the intermodulation readout of the tone pair
     * {@code fundamentalHzRefined} / {@code fundamental2HzRefined} when the
     * spectrum carries a second tone, {@code null} otherwise - so a result
     * never keeps a previous spectrum's products.  Each call hands a NEW
     * {@link ImdResult}; a reader may compare the object to tell a fresh
     * readout from the one it already shows.  Reads the result's stored
     * fields only (spectrum, manual fundamental, pilot spans), so it runs
     * last among the stats.
     */
    private void deriveImd(FftResult r) {
        r.imd = r.fundamental2HzRefined > 0.0
                ? imdAnalyzer.analyze(r, r.fundamentalHzRefined, r.fundamental2HzRefined)
                : null;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Locates each harmonic (H2..H{@code harmonicCount+1}) in the
     *  averaged spectrum.  Physics fixes the Nth harmonic at exactly
     *  {@code N × fundamentalFreq}, so its fractional FFT bin is
     *  {@code N × kFractional} - and {@link #kFractional} already
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

    /** The bins one pilot line covers, as the inclusive pair
     *  {@code {lo, hi}}: the bin it sits on, widened by the window's own
     *  main-lobe half-width plus {@link FftDsp#LEAKAGE_GUARD_BINS}, and
     *  clamped to the spectrum.  {@code {0, -1}}, an empty span, for a slot
     *  that carries no pilot.
     *
     *  <p>The one place that width is decided.  Each span is stamped on the
     *  result for the readouts that integrate bins outside this class - the
     *  dual-tone TD+N residual is one - and it is what
     *  {@link #buildSignalBinMask} takes out of the noise, SNR and ENOB sums,
     *  so a pilot cannot be excluded in one of them and counted in another. */
    private int[] pilotMaskSpan(int pilotBin, WindowType windowType, int halfSize) {
        if (pilotBin < 0) return new int[]{ 0, -1 };
        int half = FftDsp.mainLobeBins(windowType) + FftDsp.LEAKAGE_GUARD_BINS;
        int lo   = Math.max(0, pilotBin - half);
        int hi   = Math.min(halfSize, pilotBin + half);
        return new int[]{ lo, hi };
    }

    /** Builds the "signal bin" mask: fundamental bin + all harmonic bins,
     *  each smeared by {@code exclBins} on either side - the window's main
     *  lobe plus the leakage guard - plus, for a pilot bin of 0 or more, that
     *  bin smeared by the same width ({@link #pilotMaskSpan}).  A generated pilot is neither signal nor noise, so
     *  leaving one in would raise the noise integral behind SNR, N+D and ENOB
     *  by its whole power.  The pilot bins are parameters rather than the live
     *  fields: a recompute works on a spectrum that was analysed earlier and
     *  takes the bins THAT analysis pinned, which a re-selection in between
     *  can have moved.  Used by both
     *  {@link #analyze} and {@code recomputeStats} as the starting mask for
     *  the noise-floor computation; the mask is then extended by
     *  {@link #computeNoiseFloorAndExtendSignalMask} with the dynamic
     *  fundamental-exclusion walk and phase-noise zone. */
    private boolean[] buildSignalBinMask(int halfSize, int fundBin,
                                         int[] hBins, int harmonicCount, int exclBins,
                                         WindowType windowType, int pilot1Bin, int pilot2Bin) {
        // Reused scratch (1 MB at fftSize 2 M, rebuilt per stats pass) -
        // must be re-zeroed since previous masks marked other bins.
        boolean[] isSignalBin =
                (scratchSignalMask != null && scratchSignalMask.length == halfSize + 1)
                        ? scratchSignalMask : new boolean[halfSize + 1];
        scratchSignalMask = isSignalBin;
        Arrays.fill(isSignalBin, false);
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
        int[] pilot1Span = pilotMaskSpan(pilot1Bin, windowType, halfSize);
        for (int bin = pilot1Span[0]; bin <= pilot1Span[1]; bin++) {
            isSignalBin[bin] = true;
        }
        int[] pilot2Span = pilotMaskSpan(pilot2Bin, windowType, halfSize);
        for (int bin = pilot2Span[0]; bin <= pilot2Span[1]; bin++) {
            isSignalBin[bin] = true;
        }
        return isSignalBin;
    }

    /** Result of {@link #computeNoiseFloorAndExtendSignalMask} - the
     *  refined range-restricted noise floor (squared linear amplitude)
     *  plus the dynamic fundamental exclusion half-width in bins. */
    private static final class NoiseFloor {
        final double medianNoisePow;
        final int    dynWidthBins;
        NoiseFloor(double m, int d) { this.medianNoisePow = m; this.dynWidthBins = d; }
    }

    /** Computes the median non-signal-bin power inside the SNR band,
     *  performs the dynamic fundamental-exclusion walk (against the
     *  global median floor, with an overlap-scaled look-ahead), and applies the
     *  phase-noise exclusion zone within ±{@code fundBin/2} of the
     *  fundamental.  Mutates {@code isSignalBin} in place to mark every
     *  bin that ends up classified as signal (window leakage + phase
     *  noise + already-marked harmonic neighbours).
     *
     *  <p>Pulled out of {@link #analyze} and {@code recomputeStats}
     *  where the same ~60 lines of bookkeeping appeared verbatim.
     *  Single point of truth for the SNR/THD-N noise-floor model. */
    /** Skirt-walk look-ahead in bins.  With frame overlap, {@code 1/(1−ovl)}
     *  frames share any given sample, so the averaged floor wobbles with that
     *  correlation length - a single sub-floor dip inside the skirt is then
     *  noise texture, not the skirt's end.  +2 bins guards the 0%-overlap
     *  case as well.  0% -> 3, 50% -> 4, 75% -> 6, 87.5% -> 10, 93.75% -> 18,
     *  random -> its densest spacing (12 for an 80-90 % range).  Callers pass
     *  the mode's SMALLEST possible spacing, not the drawn or mean one: the
     *  walk must bridge the coarsest floor texture the mode can produce, and
     *  random hopping is not credited with decorrelating it. */
    private int skirtLookAheadBins(int fftSize, int stepSamples) {
        // Frames sharing samples = fftSize / step.  For the fixed modes this
        // equals the old round(1/(1-overlap)) exactly.  An unknown spacing (a
        // loaded spectrum file without geometry headers) degrades to the
        // 0%-overlap value.
        int framesSharing = stepSamples > 0
                ? Math.max(1, (int) Math.round((double) fftSize / stepSamples))
                : 1;
        return framesSharing + 2;
    }

    private NoiseFloor computeNoiseFloorAndExtendSignalMask(
            double[] amplLinear, boolean[] isSignalBin,
            int halfSize, double freqRes,
            double snrLo, double snrHi,
            int fundBin, int exclBins, int lookAheadBins) {

        // One scan collects both pools (grow-only scratch - no per-call
        // allocation):
        //   globalPow    : full spectrum (no range limit) - used only for the
        //                  dynamic fundamental exclusion walk, so it is never
        //                  contaminated by leakage bins inside a narrow range.
        //   candidatePow : range-restricted - used for spike threshold / SNR.
        if (scratchNoiseCand == null || scratchNoiseCand.length < halfSize) {
            scratchNoiseCand = new double[halfSize];
            scratchNoiseGlob = new double[halfSize];
        }
        double[] candidatePow = scratchNoiseCand;
        double[] globalPow    = scratchNoiseGlob;
        int candidateCount = 0;
        int globalCount    = 0;
        for (int k = 1; k <= halfSize; k++) {
            double freq = k * freqRes;
            if (!isSignalBin[k]) {
                double pow = amplLinear[k] * amplLinear[k];
                globalPow[globalCount++] = pow;
                if (freq >= snrLo && freq <= snrHi) candidatePow[candidateCount++] = pow;
            }
        }
        // Order statistics via in-place quickselect - only ONE rank of each
        // pool is ever read, so the full O(n log n) sorts this replaced were
        // 20-50 ms of waste per stats pass at fftSize 2 M.
        double medianNoisePow       = candidateCount > 0
                ? selectKth(candidatePow, candidateCount, candidateCount / 2) : 0.0;
        // Walk threshold: the GLOBAL pool's median (band-independent, so the
        // walk width still does not depend on the chosen SNR range).  The old
        // tenth-percentile bar broke the look-ahead walk: only ~10 % of noise
        // bins fall below it, so L consecutive sub-threshold bins occur with
        // ~0.1^L probability - at 87.5 %+ overlap (L = 10/18) the walk never
        // stopped and marked the whole band as signal (N showed the
        // empty-readout placeholder instead of a level, SNR pinned
        // at the 300 dB sentinel).  Half of all noise bins sit below the
        // median, so the look-ahead ends within a few correlation lengths of
        // entering true noise, while genuine skirt bins - well above the
        // floor - keep the walk going exactly as before.
        double walkStopPow = globalCount > 0
                ? selectKth(globalPow, globalCount, globalCount / 2)          : 0.0;

        // Inter-pass - dynamic fundamental exclusion at noise floor level.
        // Uses the GLOBAL-pool walkStopPow so the exclusion width is independent
        // of the chosen SNR frequency range (narrow range bins near the
        // fundamental are contaminated by window leakage and would give a
        // falsely short walk).
        int dynWidthBins = exclBins;
        if (walkStopPow > 0) {
            // The walk ends only when LOOKAHEAD consecutive bins sit at/below the
            // floor - a shorter sub-floor dip is the wobble of an overlap-averaged
            // floor (correlated frames), not the skirt's end, and is stepped over
            // (the dip bins get marked as signal with the rest of the skirt).
            // Hard cap at ±fundBin/2 - the same "can never reach H2" bound the
            // phase-noise pass uses - so no floor statistics can ever extend the
            // exclusion across the whole band (the rescaled noise sum estimates
            // whatever the zone covers, but it needs surviving bins to do it).
            int walkLo = Math.max(1, fundBin - fundBin / 2);
            int walkHi = Math.min(halfSize - 1, fundBin + fundBin / 2);
            int dynLo = fundBin, dynHi = fundBin;
            while (dynLo > walkLo) {
                int stepTo = -1;
                int limit = Math.max(walkLo, dynLo - lookAheadBins);
                for (int k = dynLo - 1; k >= limit; k--) {
                    if (amplLinear[k] * amplLinear[k] > walkStopPow) {
                        stepTo = k;
                        break;                    // nearest above-floor bin wins
                    }
                }
                if (stepTo < 0) break;            // look-ahead exhausted: true skirt end
                dynLo = stepTo;
            }
            while (dynHi < walkHi) {
                int stepTo = -1;
                int limit = Math.min(walkHi, dynHi + lookAheadBins);
                for (int k = dynHi + 1; k <= limit; k++) {
                    if (amplLinear[k] * amplLinear[k] > walkStopPow) {
                        stepTo = k;
                        break;
                    }
                }
                if (stepTo < 0) break;
                dynHi = stepTo;
            }
            dynWidthBins = Math.max(fundBin - dynLo, dynHi - fundBin);
            if (dynWidthBins > exclBins) {
                for (int k = dynLo; k <= dynHi; k++) isSignalBin[k] = true;

                // Pass 2 - refined range-restricted median with updated exclusion
                double refined = bandMedianPow(amplLinear, isSignalBin, halfSize, freqRes, snrLo, snrHi);
                if (refined >= 0.0) medianNoisePow = refined;
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

    /** The median power of the bins of {@code amplLinear} the mask leaves as
     *  noise inside the SNR band, or -1 when the band holds none. */
    private double bandMedianPow(double[] amplLinear, boolean[] isSignalBin,
                                 int halfSize, double freqRes, double snrLo, double snrHi) {
        if (scratchNoiseCand == null || scratchNoiseCand.length < halfSize) {
            scratchNoiseCand = new double[halfSize];
            scratchNoiseGlob = new double[halfSize];
        }
        double[] candidatePow = scratchNoiseCand;
        int candidateCount = 0;
        for (int k = 1; k <= halfSize; k++) {
            double freq = k * freqRes;
            if (!isSignalBin[k] && freq >= snrLo && freq <= snrHi) {
                candidatePow[candidateCount++] = amplLinear[k] * amplLinear[k];
            }
        }
        return candidateCount > 0 ? selectKth(candidatePow, candidateCount, candidateCount / 2) : -1.0;
    }

    /** In-place Hoare quickselect: returns the {@code k}-th smallest element
     *  of {@code a[0..len)}, partially reordering the array (callers treat
     *  the pools as scratch).  O(len) average vs the O(len·log len) full
     *  sorts it replaced - only one order statistic per pool is ever read.
     *  Package-private for the unit test. */
    double selectKth(double[] a, int len, int k) {
        int lo = 0, hi = len - 1;
        while (lo < hi) {
            // Median-of-3 pivot: cheap insurance against the sorted /
            // constant runs a quiet noise floor produces.
            double p1 = a[lo], p2 = a[(lo + hi) >>> 1], p3 = a[hi];
            double pivot = Math.max(Math.min(p1, p2), Math.min(Math.max(p1, p2), p3));
            int i = lo, j = hi;
            while (i <= j) {
                while (a[i] < pivot) i++;
                while (a[j] > pivot) j--;
                if (i <= j) {
                    double t = a[i]; a[i] = a[j]; a[j] = t;
                    i++; j--;
                }
            }
            if (k <= j)      hi = j;
            else if (k >= i) lo = i;
            else             return a[k];
        }
        return a[k];
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
}
