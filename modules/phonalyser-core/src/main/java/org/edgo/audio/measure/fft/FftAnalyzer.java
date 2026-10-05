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
import java.util.stream.IntStream;

import org.edgo.audio.measure.common.Constants;
import org.edgo.audio.measure.dsp.Goertzel;
import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.WindowType;

import lombok.Setter;
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

    /** Below this bin count the per-bin passes run serially - the fork/dispatch
     *  overhead would dominate. */
    private static final int PASS_PARALLEL_THRESHOLD = 1 << 16;   // 64k bins

    /** Shared empty grid published on {@link FftResult} for non-dual-tone (or
     *  non-de-rotated) ticks - avoids a per-tick allocation on the hot path. */
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
     *  every tick. */
    private double     cachedCohGain;
    /** Normalized equivalent noise bandwidth of {@link #cachedWindow} in bins,
     *  {@code N·Σw² / (Σw)²} (Hann: 1.5) - the factor by which one windowed
     *  periodogram bin overstates a bin-width of broadband power.  Cached with
     *  the window; divides the integrated noise sums (never coherent lines). */
    private double     cachedNenbw;

    /** Cached per-bin A-weighting table - entry k = {@link #aWeightPower}(k·freqRes)
     *  for {@link #cachedAweightFreqRes}.  The noise integrals in analyze() and
     *  {@link #recomputeStats} consult it for every non-signal bin each tick;
     *  without it that is ~halfSize sqrt evaluations per call for a curve that
     *  only changes when the spectrum geometry (fftSize / sample rate) does. */
    private double[]   cachedAweight;
    private double     cachedAweightFreqRes;

    /** Cache instance to consult during {@link #analyze}; {@code null}
     *  disables caching (CLI default).  Set by the caller via
     *  {@link #setFrameCache}; copied into a local at the top of
     *  {@code analyze} so changes mid-call have no effect. */
    private FrameFftCache frameCache;
    /** Absolute sample-start position of {@code samples[0]} in the
     *  caller's stream, used to key the cache.  Only meaningful when
     *  {@link #frameCache} is non-null. */
    private long samplesAbsStart;

    /** Optional second-tone frequency hint (Hz) for dual-/multi-tone
     *  signals; {@link Double#NaN} disables it.  When set, {@code analyze}
     *  also produces a sub-bin frequency estimate for this tone - via the
     *  same clean-frame parabolic / phase method used for the fundamental
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
     *  that bin, so the frames of a tick sum without the residual a
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
     *  fundamental / harmonic BIN positions, but SKIPS the throwaway per-tick
     *  THD / SNR / SINAD / noise-floor sweeps (including the two O(N log N)
     *  noise sorts).  The live FFT view sets this while cross-tick averaging:
     *  it discards the single-tick stats and re-derives them with
     *  {@link #recomputeStats} on the accumulated spectrum (display ticks only),
     *  so the per-tick computation is pure waste.  Leave {@code false} for
     *  one-shot CLI / test callers and single-tick (non-averaging) display. */
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

    /** Per-fftSize scratch arrays that are purely internal to one
     *  {@code analyze} call - they are filled in, consumed, and then
     *  discarded before the {@link FftResult} is returned (i.e., nothing
     *  in the Result holds a reference to them).  Caching them as
     *  instance fields and re-using them across calls eliminates the
     *  10 × fftSize × 8 B allocation per analyze (≈ 80 MB at fftSize=1 M,
     *  ≈ 320 MB at 4 M).  Lazily (re-)allocated when fftSize changes. */
    private double[] scratchF0Re, scratchF0Im;
    private double[] scratchSumRe, scratchSumIm;
    private double[] scratchFrameRe, scratchFrameIm;
    private double[] scratchS0Re, scratchS0Im;
    private double[] scratchS1Re, scratchS1Im;
    /** Half-size scratch for amplitudes - used only inside one
     *  {@code analyze} call by the harmonic-detection and noise-floor
     *  paths; never returned. */
    private double[] scratchAmplLinear;
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
        cachedWindow     = buildWindow(N, type);
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
         *  key {@code (absStart, fftSize)}.  The cache MUST copy - the
         *  caller reuses the input arrays. */
        void put(long absStart, int fftSize, double[] re, double[] im);
    }

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
     *  window table - at fftSize 4 M the set idles on ~300 MB after the last
     *  {@code analyze}.  Everything reallocates on demand at the next call,
     *  so this belongs in a stop path, never mid-run.  Callers must not have
     *  an analysis in flight. */
    public void releaseScratch() {
        scratchF0Re    = scratchF0Im    = null;
        scratchSumRe   = scratchSumIm   = null;
        scratchFrameRe = scratchFrameIm = null;
        scratchS0Re    = scratchS0Im    = null;
        scratchS1Re    = scratchS1Im    = null;
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

    /** Tries the cache for the windowed FFT of the frame starting at
     *  local sample offset {@code localStart}.  On miss, windows +
     *  FFTs from {@code samples} and stores the result.  Always leaves
     *  {@code re} / {@code im} populated with the result. */
    private void cachedFrameFft(double[] samples, int localStart, int fftSize,
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

    /** One spectral bin's complex value, in the raw FFT units of
     *  {@link FftResult#re} / {@link FftResult#im}. */
    public record BinPhasor(double re, double im) {}

    /** The frame-averaged phasor of ONE bin of {@code samples}, produced by
     *  the recipe {@code analyze} uses for its coherent {@code re[k]/im[k]}:
     *  per frame of {@code fftSize} samples (frame f starting at
     *  {@code f·step}) a windowed single-bin Goertzel, de-rotated to the
     *  window's own origin by the fundamental's phase advance
     *  {@code −2π·base·kFractional/fftSize} - the same region-constant angle
     *  the coherent pass applies to the fundamental's bin - and the frames
     *  averaged.  The window table is the analyser's own cached one, so the
     *  caller never holds a second copy of the coefficients.
     *
     *  <p>The answer therefore shares the time origin and the scale of a
     *  spectrum computed by {@code analyze} over the same span, and the phase
     *  difference between the two is free of any hop-dependent term.  That is
     *  what the FFT worker's reference-channel phase alignment compares: the
     *  analysed channel's fundamental bin against the same bin of the
     *  opposite channel.
     *
     *  @param bin         integer bin, 0 .. fftSize/2
     *  @param step        spacing between successive frame starts, in samples
     *  @param frameCount  frames to average, the first one starting at
     *                     {@code samples[0]}
     *  @param kFractional the de-rotation frequency in fractional bins (the
     *                     tick's refined fundamental bin) */
    public BinPhasor binPhasor(double[] samples, int fftSize, WindowType windowType,
                               int bin, int step, int frameCount, double kFractional) {
        if (Integer.bitCount(fftSize) != 1) {
            throw new IllegalArgumentException("fftSize must be a power of 2, got: " + fftSize);
        }
        if (bin < 0 || bin > fftSize / 2) {
            throw new IllegalArgumentException("Bin out of range: " + bin);
        }
        if (step < 1 || frameCount < 1) {
            throw new IllegalArgumentException("Frame geometry must be positive, got step "
                    + step + " and " + frameCount + " frame(s)");
        }
        if ((long) (frameCount - 1) * step + fftSize > samples.length) {
            throw new IllegalArgumentException(
                    "Stated geometry exceeds the buffer: " + frameCount + " frames at step "
                    + step + " need " + ((long) (frameCount - 1) * step + fftSize)
                    + " samples, got " + samples.length);
        }
        double[] window = getCachedWindow(fftSize, windowType);
        final double w  = 2.0 * Math.PI * bin / fftSize;
        double   sumRe = 0.0;
        double   sumIm = 0.0;
        for (int f = 0; f < frameCount; f++) {
            int base = f * step;
            Goertzel.SingleBin gz = Goertzel.goertzelBin(samples, base, fftSize, window, w, 0.0);
            double ang   = -2.0 * Math.PI
                    * Math.IEEEremainder((double) base * kFractional, fftSize) / fftSize;
            double c = Math.cos(ang);
            double s = Math.sin(ang);
            sumRe += gz.re() * c - gz.im() * s;
            sumIm += gz.re() * s + gz.im() * c;
        }
        return new BinPhasor(sumRe / frameCount, sumIm / frameCount);
    }

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
     * The fractional bin of a tone sitting at {@code bin} in {@code samples},
     * measured the way the analysis refines the fundamental: with two frames or
     * more, the tone's phase change at {@code bin} between the first frame and
     * the one {@code step} samples later ({@link #kappaFromPhaseStep}); with a
     * single frame, the log-power parabolic fit of
     * {@link MathUtil#parabolicBinInterp(double, double, double, double, double, double, int)}
     * over {@code bin-1}, {@code bin} and {@code bin+1} of that frame.
     * Multiplied by the frequency resolution it is that tone's frequency.
     *
     * <p>It exists for a buffer the analysis never transformed - the opposite
     * channel's window in the reference-channel phase alignment, whose
     * fundamental stands tens of dB clear of the notched one and is therefore
     * the better measurement to steer a frequency lock with.  The same
     * estimator as on the analysed channel, so the two readings differ by the
     * channel and nothing else.  Two single-bin transforms per call (three for
     * a single frame); no spectrum is built and no window table copied.
     *
     * <p>In the single-frame fit the three bins are clamped into
     * {@code 1 .. fftSize/2}, so a tone at the very first or last bin
     * degenerates to a two-point fit exactly as the array form of the same fit
     * does.
     *
     * @param bin        the tone's integer bin - the phase is read there, and
     *                   the answer's whole turns are resolved against it
     * @param step       spacing between successive frame starts, in samples
     * @param frameCount frames the buffer holds, the first at {@code samples[0]}
     */
    public double refinedBin(double[] samples, int fftSize, WindowType windowType,
                             int bin, int step, int frameCount) {
        if (step < 1 || frameCount < 1) {
            throw new IllegalArgumentException("Frame geometry must be positive, got step "
                    + step + " and " + frameCount + " frame(s)");
        }
        if (frameCount >= 2) {
            BinPhasor first  = frameBinPhasor(samples, 0,    fftSize, windowType, bin);
            BinPhasor second = frameBinPhasor(samples, step, fftSize, windowType, bin);
            return kappaFromPhaseStep(Math.atan2(first.im(), first.re()),
                    Math.atan2(second.im(), second.re()), bin, step, fftSize);
        }
        int halfSize = fftSize / 2;
        int lo = Math.max(1, bin - 1);
        int hi = Math.min(halfSize, bin + 1);
        BinPhasor pLo  = frameBinPhasor(samples, 0, fftSize, windowType, lo);
        BinPhasor pMid = frameBinPhasor(samples, 0, fftSize, windowType, bin);
        BinPhasor pHi  = frameBinPhasor(samples, 0, fftSize, windowType, hi);
        return MathUtil.parabolicBinInterp(pLo.re(), pLo.im(), pMid.re(), pMid.im(),
                pHi.re(), pHi.im(), bin);
    }

    /**
     * Full-integration refinement of the fundamental's fractional bin κ.  The
     * coarse estimate is a single one-hop phase difference; its residual error δk
     * is applied as a linear de-rotation ramp across the whole segment, so on a
     * long coherent average the harmonics de-cohere (∝ sinc(π·h·δk·M·step/N)) and
     * read low - the slow harmonic decline seen on long captures.  Here κ is
     * measured from the SLOPE of the fundamental's phase at {@code refIntBin} over
     * every clean-segment frame: a {@code (bestLen-1)·step} baseline instead of one
     * hop, shrinking δk by ~that factor.  The phase RESIDUAL relative to the coarse
     * κ stays within ±π over the segment (no unwrapping), and the sample-offsets are
     * centred so the regression stays well-conditioned.  Returns the refined κ.
     *
     * <p>Only the fundamental BIN is needed per frame, so a single-bin Goertzel
     * (O(N) per frame, ~6% of a full FFT) replaces the FFT - no extra spectra.  The
     * Goertzel result carries a constant phase factor e^{−jω}; being frame-independent
     * it cancels in the phase SLOPE and does not bias κ.  The residuals are re-centred
     * on their circular mean before the fit so an arbitrary fundamental phase landing
     * near ±π cannot wrap mid-segment and corrupt the slope.
     */
    private double refineKappaOverSegment(double[] samples, double[] window, int fftSize,
                                          int step, int bestStart, int bestLen,
                                          int refIntBin, double kCoarse) {
        final double w     = 2.0 * Math.PI * refIntBin / fftSize;
        double[] resid  = new double[bestLen];
        double   sumCos = 0.0, sumSin = 0.0;
        for (int f = bestStart; f < bestStart + bestLen; f++) {
            int base = f * step;
            Goertzel.SingleBin gz = Goertzel.goertzelBin(samples, base, fftSize, window, w, 0.0);
            double ph       = Math.atan2(gz.im(), gz.re());
            double expected = 2.0 * Math.PI * kCoarse * base / (double) fftSize;
            double r        = Math.IEEEremainder(ph - expected, 2.0 * Math.PI);
            resid[f - bestStart] = r;
            sumCos += Math.cos(r);
            sumSin += Math.sin(r);
        }
        double meanAngle = Math.atan2(sumSin, sumCos);          // circular mean -> wrap-safe centre
        double meanBase  = ((double) bestStart + (bestStart + bestLen - 1)) * 0.5 * step;
        double sDbR = 0.0, sDb2 = 0.0;
        for (int f = bestStart; f < bestStart + bestLen; f++) {
            double rc = Math.IEEEremainder(resid[f - bestStart] - meanAngle, 2.0 * Math.PI);
            double db = (double) f * step - meanBase;
            sDbR += db * rc;
            sDb2 += db * db;
        }
        if (!(sDb2 > 0.0)) {
            return kCoarse;
        }
        return kCoarse + (sDbR / sDb2) * fftSize / (2.0 * Math.PI);
    }

    /**
     * Sub-bin fractional bin κ of the line sitting near {@code hintBin},
     * measured on the clean segment the same way the fundamental's own κ is:
     * the peak is re-found within ±{@code searchBins} of the hint, then the
     * one-hop phase difference between the segment's first two frames gives
     * the fractional position; with a single frame ({@code s1Re == null}) the
     * three lobe bins carry a parabolic interpolation instead.  Only the lobe
     * bins feed the estimate, so noise elsewhere in the spectrum cannot bias
     * it.  The integer part of the answer is the bin the search settled on, so
     * a caller whose line can stray states how far; a caller that knows where
     * its line stands passes 0 and the hint bin is measured as it is, which
     * also keeps a neighbouring line from taking the bin.  The phase
     * difference then resolves an offset of up to half a frame spacing's worth
     * of bins ({@code fftSize / (2·step)}, one bin at 50 % overlap) either way.
     *
     * <p>Serves every line the analysis reports a TRUE frequency for beside the
     * fundamental - the second tone of a dual-tone signal - so it too is read
     * off a fresh frame rather than off the
     * coherently-collapsed average.  Returns {@link Double#NaN} when the hint
     * sits within 3 bins of an edge of the single-sided spectrum, where no full
     * lobe exists to measure.
     */
    private double refineToneKappaAt(int hintBin, int searchBins,
                                     double[] s0Re, double[] s0Im,
                                     double[] s1Re, double[] s1Im,
                                     int halfSize, int fftSize, int step) {
        if (hintBin < 3 || hintBin > halfSize - 3) {
            return Double.NaN;
        }
        int peak = peakBin(s0Re, s0Im,
                Math.max(1, hintBin - searchBins), Math.min(halfSize, hintBin + searchBins));
        if (s1Re == null) {
            return MathUtil.parabolicBinInterp(s0Re, s0Im, peak, fftSize);
        }
        double p0 = Math.atan2(s0Im[peak], s0Re[peak]);
        double p1 = Math.atan2(s1Im[peak], s1Re[peak]);
        return kappaFromPhaseStep(p0, p1, peak, step, fftSize);
    }

    /**
     * The fractional bin of a tone from its phase at {@code bin} in two frames
     * {@code step} samples apart: the phase change with its whole turns taken
     * from the advance a tone exactly on {@code bin} makes, scaled to bins.
     * Resolves an offset of up to {@code fftSize / (2·step)} bins either way of
     * {@code bin}.  The one recipe every live tone frequency is read with - the
     * fundamental, the second tone and the reference channel's tones.
     *
     * @param p0 the tone's phase at {@code bin} in the first frame, radians
     * @param p1 the same in the frame {@code step} samples later
     */
    private double kappaFromPhaseStep(double p0, double p1, int bin, int step, int fftSize) {
        double expected = 2.0 * Math.PI * bin * step / (double) fftSize;
        long   turns    = Math.round((p1 - p0 - expected) / (2.0 * Math.PI));
        return (p1 - p0 - 2.0 * Math.PI * turns) * fftSize / (2.0 * Math.PI * step);
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

    /** Harmonics the THD sums when the caller states no limit: H2..H9, the
     *  sum every overload below the full one has always taken.  A caller that
     *  offers the limit as a setting passes its own count to the full
     *  overload. */
    private static final int THD_HARMONICS_LEGACY = 8;

    // =========================================================================
    // Analysis - backward-compatible overload (Hann, 0 % overlap)
    // =========================================================================

    public FftResult analyze(double[] samples, int sampleRate,
                                 int fftSize, int harmonicCount) {
        return analyze(samples, sampleRate, fftSize, harmonicCount,
                WindowType.HANN, FftOverlap.PCT_0, 0.0, 0.0, true, Double.NaN);
    }

    public FftResult analyze(double[] samples, int sampleRate,
                                 int fftSize, int harmonicCount,
                                 WindowType windowType, FftOverlap overlap) {
        return analyze(samples, sampleRate, fftSize, harmonicCount,
                windowType, overlap, 0.0, 0.0, true, Double.NaN);
    }

    public FftResult analyze(double[] samples, int sampleRate,
                                 int fftSize, int harmonicCount,
                                 WindowType windowType, FftOverlap overlap,
                                 double snrFreqMin, double snrFreqMax,
                                 boolean coherentAveraging) {
        return analyze(samples, sampleRate, fftSize, harmonicCount,
                windowType, overlap, snrFreqMin, snrFreqMax, coherentAveraging, Double.NaN);
    }

    // =========================================================================
    // Analysis - full version
    // =========================================================================

    /**
     * Runs coherent-averaged FFT analysis on a normalized mono signal.
     *
     * @param samples       signal samples, range −1.0 ... +1.0
     * @param sampleRate    sample rate in Hz
     * @param fftSize       FFT frame length - must be a power of 2
     * @param harmonicCount number of harmonics to evaluate (2nd ... N-th)
     * @param windowType    window function to apply per frame
     * @param overlap       overlap between successive frames
     * @param snrFreqMin         lower bound for SNR noise integration in Hz (0 = no limit)
     * @param snrFreqMax         upper bound for SNR noise integration in Hz (0 = no limit)
     * @param coherentAveraging  true = accumulate complex spectra (noise cancels across frames);
     *                           false = accumulate power per bin (no phase coherence required)
     * @param fundRefDbFs   known real level of the fundamental in dBFS (callers convert dBV inputs at the boundary); {@link Double#NaN} = unknown
     * @return fully populated {@link FftResult}
     */
    public FftResult analyze(double[] samples, int sampleRate,
                                 int fftSize, int harmonicCount,
                                 WindowType windowType, FftOverlap overlap,
                                 double snrFreqMin, double snrFreqMax,
                                 boolean coherentAveraging, double fundRefDbFs) {
        return analyze(samples, sampleRate, fftSize, harmonicCount,
                windowType, overlap, snrFreqMin, snrFreqMax, coherentAveraging,
                fundRefDbFs, true);
    }

    /**
     * Same as the 10-arg overload, plus {@code logSummary}: when {@code false},
     * the trailing THD / THD+N / SNR log lines are suppressed so the caller
     * can mutate the result (e.g. apply ADC correction) and log the final
     * numbers itself without producing a misleading uncorrected line first.
     */
    public FftResult analyze(double[] samples, int sampleRate,
                                 int fftSize, int harmonicCount,
                                 WindowType windowType, FftOverlap overlap,
                                 double snrFreqMin, double snrFreqMax,
                                 boolean coherentAveraging, double fundRefDbFs,
                                 boolean logSummary) {
        return analyze(samples, sampleRate, fftSize, harmonicCount,
                windowType, overlap, snrFreqMin, snrFreqMax, coherentAveraging,
                fundRefDbFs, logSummary, Double.NaN);
    }

    /**
     * Full overload with {@code expectedFundHz}: when set (not {@link Double#NaN}),
     * the fundamental peak search is restricted to a narrow ±10-bin window around
     * the expected frequency instead of the loudest bin in the spectrum.  Use this
     * when the fundamental can be deeply attenuated (e.g. measuring distortion
     * through a notch filter at the notch frequency) - global max would otherwise
     * latch onto a harmonic, mains hum, or a spur and the entire downstream
     * harmonic table would be wrong.  Pass {@link Double#NaN} for the legacy
     * loudest-bin behaviour.
     *
     * <p>Allocates a fresh {@link FftResult} per call - preferred for one-shot
     * CLI / test callers; the THD sums its historic H2..H9.  The live FFT view
     * goes through
     * {@link #analyze(double[], int, int, int, int, WindowType, FftOverlap,
     * double, double, boolean, double, boolean, double, FftResult, int, int)}
     * to state the THD's harmonic limit and to write into a pre-allocated pool
     * slot, avoiding per-tick array churn.
     */
    public FftResult analyze(double[] samples, int sampleRate,
                                 int fftSize, int harmonicCount,
                                 WindowType windowType, FftOverlap overlap,
                                 double snrFreqMin, double snrFreqMax,
                                 boolean coherentAveraging, double fundRefDbFs,
                                 boolean logSummary, double expectedFundHz) {
        // The one place the fixed overlap's spacing is derived: everything
        // below runs on the stated geometry.  A random overlap has no fixed
        // spacing (hopSamples throws) - its drawn hop enters as the stated
        // step directly.
        int step = overlap.hopSamples(fftSize);
        int frameCount = samples.length >= fftSize
                ? (samples.length - fftSize) / step + 1
                : 0;
        return analyze(samples, sampleRate, fftSize, harmonicCount, THD_HARMONICS_LEGACY,
                windowType, overlap, snrFreqMin, snrFreqMax, coherentAveraging,
                fundRefDbFs, logSummary, expectedFundHz, new FftResult(),
                step, frameCount);
    }

    /** A contiguous half-open [lo, hi) bin range to process. */
    @FunctionalInterface
    private interface RangeTask { void run(int lo, int hi); }

    /** Runs {@code task} over [0, n) split into one contiguous chunk per core on
     *  the common pool; below {@link #PASS_PARALLEL_THRESHOLD} it runs the whole
     *  range on the caller's thread.  Chunks are disjoint, so a task that writes
     *  only its own [lo, hi) slice needs no synchronisation. */
    private void parallelChunks(int n, RangeTask task) {
        if (n < PASS_PARALLEL_THRESHOLD) { task.run(0, n); return; }
        int chunks    = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
        int chunkSize = (n + chunks - 1) / chunks;
        IntStream.range(0, chunks).parallel().forEach(c -> {
            int lo = c * chunkSize;
            int hi = Math.min(n, lo + chunkSize);
            if (lo < hi) task.run(lo, hi);
        });
    }

    /**
     * The one full analysis method - every overload above funnels into it.
     * The caller states the frame geometry: {@code step} is the spacing
     * between successive frame starts and {@code frameCount} the number of
     * frames to take, so an over-sized buffer is never mis-read as extra
     * frames.  Fixed overlaps derive both once in the convenience overload;
     * the analyser worker states its per-tick DRAWN hop directly - under the
     * random overlap the buffer's two frames are spaced by the hop drawn for
     * the next tick, so the second frame is exactly the next tick's first
     * and the raw-frame cache keeps hitting.
     *
     * <p>{@code thdHarmonics} is how many of the evaluated harmonics the THD
     * sums, counted from the 2nd (H2 .. H(thdHarmonics+1)), each still subject
     * to the distortion window; the harmonic table itself always holds all
     * {@code harmonicCount}.  The result carries the number it summed.
     *
     * <p>Writes the analysis into the supplied {@code outResult} slot instead
     * of allocating a fresh result: the slot's bin / harmonic arrays are
     * resized as needed ({@link FftResult#ensureArrays}) and then used
     * directly as the analysis output buffers - no per-tick allocation for
     * the four fftSize/2+1-long spectrum arrays.  Returns {@code outResult}
     * for convenience.
     */
    @SuppressWarnings("unused")
    public FftResult analyze(double[] samples, int sampleRate,
                                 int fftSize, int harmonicCount, int thdHarmonics,
                                 WindowType windowType, FftOverlap overlap,
                                 double snrFreqMin, double snrFreqMax,
                                 boolean coherentAveraging, double fundRefDbFs,
                                 boolean logSummary, double expectedFundHz,
                                 FftResult outResult, int step, int frameCount) {
        if (Integer.bitCount(fftSize) != 1) {
            throw new IllegalArgumentException("fftSize must be a power of 2, got: " + fftSize);
        }
        if (step < 1) {
            throw new IllegalArgumentException("Frame spacing must be positive, got: " + step);
        }
        if (frameCount <= 0 || samples.length < fftSize) {
            throw new IllegalArgumentException(
                    "Signal too short for fftSize=" + fftSize +
                    ": need at least " + fftSize + " samples, got " + samples.length);
        }
        if ((long) (frameCount - 1) * step + fftSize > samples.length) {
            throw new IllegalArgumentException(
                    "Stated geometry exceeds the buffer: " + frameCount + " frames at step "
                    + step + " need " + ((long) (frameCount - 1) * step + fftSize)
                    + " samples, got " + samples.length);
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
        double   cohGain = cachedCohGain;   // cached with the window table
        double   nenbw   = cachedNenbw;     // ditto - corrects the noise integrals

        // --- Estimate fundamental fractional bin (always, for both averaging modes) --
        // The true signal frequency is k_f × Fs/N where k_f is non-integer.
        // Using the integer bin for harmonic synthesis causes phase drift of
        // 2π×ε×n/N per sample -> complete cancellation failure over long recordings.
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
        //     slew threshold - final kFractional is re-estimated post-rejection)
        scratchF0Re = scratchOrAlloc(scratchF0Re, fftSize);
        scratchF0Im = scratchOrAlloc(scratchF0Im, fftSize);
        double[] f0Re = scratchF0Re;
        double[] f0Im = scratchF0Im;
        cachedFrameFft(samples, 0, fftSize, window, f0Re, f0Im);
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

        // --- Frame-rejection threshold (signal-interruption detector) --------
        // Pythagorean sine invariant: for s = A·sin(θ) and k = 2π·f/Fs,
        //   ds/dn = A·k·cos(θ)  =>  s² + (ds/dn / k)² ≡ A²
        // For each sample we compute R = √(s² + (Δs/k)²) using a central
        // difference. Clean samples -> R ≈ A; glitches violate the invariant.
        // The test is amplitude-relative and phase-aware: a small |Δ| at a
        // peak (where slope ≈ 0) still produces a large R deviation, which the
        // old constant-|Δ| threshold would have missed.
        //
        // peakAmp is RMS-derived (peak = √2·RMS for a sine) so that overshoots
        // from dropouts do not inflate A and mask further glitches.
        // BOTH the RMS and the per-sample R scan operate on the
        // DC-removed signal - a live ADC capture typically carries a
        // small DC bias that, when not subtracted, makes peakAmp =
        // √(2·DC² + A²) instead of A.  For a small AC signal
        // dominated by DC (e.g. 5 mV sine on top of 10 mV bias) this
        // inflates peakAmp by √2·DC, and R for every sample drifts
        // ~29% away from peakAmp regardless of glitches - flagging
        // half the samples and rejecting every frame.
        // FRAME REJECTION DISABLED - capture glitches of every origin are now
        // handled by the cross-tick gap recovery, and per-frame rejection also
        // (harmlessly) flags AGC amplitude wobble.  This one switch gates BOTH
        // the R-invariant scan and the phase-coherence check, and short-circuits
        // their per-sample work so a disabled analysis costs nothing.  Flip to
        // true to restore.
        final boolean frameRejection = false;
        // DC mean + RMS feed only the rejection-feasibility test and the DEBUG
        // diagnostic - two full passes over the multi-million-sample window
        // that are pure waste while both are off.
        final double dcMean;
        final double peakAmp;
        if (frameRejection || log.isDebugEnabled()) {
            dcMean  = sampleMean(samples);
            peakAmp = dcRemovedRms(samples, dcMean) * Constants.SQRT2;
        } else {
            dcMean  = 0.0;
            peakAmp = 0.0;
        }
        double estFundHz = intFundBin * freqRes;
        double omegaPerSample = 2.0 * Math.PI * estFundHz / sampleRate;
        final double R_TOLERANCE = 0.10;      // |R−A|/A > 10 %  -> flag sample

        // The R-invariant test assumes s(n) ≈ A·sin(ωn); when the fundamental
        // sine is not the dominant component of the time-domain signal (typical
        // when measuring distortion through a deep filter notch - fundamental
        // at -100 dBFS while DC offset + noise + harmonics dominate the time
        // domain), the invariant cannot hold and the whole recording would be
        // rejected.  Compare the spectrum-bin amplitude at the candidate
        // fundamental against the time-domain peak; skip rejection unless the
        // sine carries at least half the time-domain peak amplitude.
        double specMag = Math.sqrt(f0Re[intFundBin] * f0Re[intFundBin]
                                 + f0Im[intFundBin] * f0Im[intFundBin]);
        double specPeakAmp = specMag * 2.0 / ((double) fftSize * cohGain);
        boolean rejectionFeasible = frameRejection && peakAmp > 0.0 && specPeakAmp >= 0.5 * peakAmp;
        // Per-analysis diagnostic - demoted to DEBUG and guarded so its five
        // String.format calls don't run (or spam the log) on every tick.
        if (log.isDebugEnabled()) {
            log.debug("Frame R-invariant check: peakAmp(RMS-derived)={}, fund-bin peak={} (ratio {} dB), k={}, tolerance={}% (using intBin={}, ~{} Hz)",
                    String.format(Locale.US, "%.6e", peakAmp),
                    String.format(Locale.US, "%.6e", specPeakAmp),
                    peakAmp > 0
                            ? String.format(Locale.US, "%+.1f", 20.0 * Math.log10(specPeakAmp / peakAmp))
                            : "n/a",
                    String.format(Locale.US, "%.6f", omegaPerSample),
                    String.format(Locale.US, "%.1f", R_TOLERANCE * 100.0),
                    intFundBin,
                    String.format(Locale.US, "%.4f", estFundHz));
        }
        if (frameRejection && !rejectionFeasible && log.isInfoEnabled()) {
            log.info("Frame R-invariant rejection skipped: fundamental sine buried in noise/spurs "
                    + "(fund-bin peak >20 dB below time-domain peak); accepting all frames");
        }

        // Quick noise-derivative check: for a clean A·sin(ωn) the
        // second difference Δ²s = s[n+1] − 2·s[n] + s[n−1] has peak
        // |A·k²|; broadband per-sample noise produces |Δ²s| ≈ 4N.
        // When the observed RMS(Δ²) is much larger than the expected
        // sine contribution, derivative noise dominates dq = Δs/k
        // and the R-invariant test fires on most samples regardless
        // of glitches - turning every analysis into a "rejection
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

        // Multi-tone signals (dual tone, etc.): the R-invariant assumes a
        // single sine, so a sum of tones violates it at almost every sample -
        // the scan would flag tens of thousands of "events", then invalidate
        // itself and accept all frames anyway.  Skip it outright.
        if (rejectionFeasible && multiTone) {
            log.info("Frame R-invariant rejection skipped: multi-tone signal "
                    + "(single-sine invariant does not apply); accepting all frames");
            rejectionFeasible = false;
        }

        // --- Averaging (coherent: complex sum; incoherent: power sum) --------
        scratchSumRe   = scratchOrAlloc(scratchSumRe,   fftSize);
        scratchSumIm   = scratchOrAlloc(scratchSumIm,   fftSize);
        scratchFrameRe = scratchOrAlloc(scratchFrameRe, fftSize);
        scratchFrameIm = scratchOrAlloc(scratchFrameIm, fftSize);
        // sumRe / sumIm are the averaging accumulators - must start at 0.
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
                // Frames whose [base .. base+fftSize-1] window SPANS the whole
                // event [evS .. evE]:
                //   base <= evS  AND  base + fftSize - 1 >= evE
                //   -> base in [evE - fftSize + 1 .. evS] / step.
                int fLo = Math.max(0, (int) Math.ceil((evE - (long) fftSize + 1) / (double) step));
                int fHi = Math.min(frameCount - 1, evS / step);
                int dirty = 0;
                for (int f = fLo; f <= fHi; f++) {
                    if (frameClean[f]) {
                        frameClean[f] = false;
                        dirty++;
                    }
                }
                log.warn("  event {}/{}: sample {} (t={} s), width={} samples ({} µs), peak |R−A|/A={}% -> frames [{}..{}] dirty (+{})",
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
            log.warn("R-invariant rejection invalidated: would reject all {} frame(s) (likely low-SNR signal - derivative-noise amplification); accepting all frames",
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

        if (frameRejection && log.isInfoEnabled()) {
            log.info("Frame R-invariant stats: max accepted |R−A|/A={}%, max rejected |R−A|/A={}",
                    String.format(Locale.US, "%.2f", maxRDevAccepted * 100.0),
                    rejectedFrames > 0 ? String.format(Locale.US, "%.2f%%", maxRDevRejected * 100.0) : "n/a");
        }
        if (rejectedFrames > 0) {
            log.warn("Frame rejection: {} of {} frames R-invariant-rejected; using longest clean segment [{}..{}] ({} frames)",
                    rejectedFrames, frameCount,
                    bestStart, bestStart + bestLen - 1, bestLen);
            outResult.rejectedFrames          = rejectedFrames;
            outResult.rejectionTotalFrames    = frameCount;
            outResult.rejectionPhaseCoherence = false;
            outResult.rejectionDetail         = maxRDevRejected * 100.0;   // %
        }
        if (bestLen == 0) {
            throw new IllegalStateException("All " + frameCount + " frames rejected as corrupt - aborting FFT.");
        }

        // --- Re-estimate kFractional from the clean segment ------------------
        // Glitchy frames at the start of the recording would otherwise poison
        // the phase-difference estimate, leaving a linear residual phase drift
        // across the (clean) segment that smears the fundamental over many bins.
        // We use the segment's first frame + its immediate successor when
        // available, otherwise fall back to parabolic interpolation.
        int    segBaseSample = bestStart * step;
        double[] s0Re;
        double[] s0Im;
        if (segBaseSample == 0) {
            // The segment's first frame IS the pre-pass frame (bestStart is 0
            // whenever rejection is off - the only shipping configuration):
            // alias it instead of re-fetching the same cached FFT into a
            // second scratch pair (2 × 8.4 MB memcpy per tick at 1 M).
            // Both pairs are write-once-then-read-only in this method.
            s0Re = f0Re;
            s0Im = f0Im;
        } else {
            scratchS0Re = scratchOrAlloc(scratchS0Re, fftSize);
            scratchS0Im = scratchOrAlloc(scratchS0Im, fftSize);
            s0Re = scratchS0Re;
            s0Im = scratchS0Im;
            cachedFrameFft(samples, segBaseSample, fftSize, window, s0Re, s0Im);
        }
        // refine intFundBin within ±2 bins of the pre-pass estimate (in case of drift)
        int refIntBin = peakBin(s0Re, s0Im,
                Math.max(1, intFundBin - 2),
                Math.min(halfSize, intFundBin + 2));
        double kFractional;
        if (bestLen >= 2) {
            int    estStep = step;
            scratchS1Re = scratchOrAlloc(scratchS1Re, fftSize);
            scratchS1Im = scratchOrAlloc(scratchS1Im, fftSize);
            double[] s1Re = scratchS1Re;
            double[] s1Im = scratchS1Im;
            cachedFrameFft(samples, segBaseSample + estStep, fftSize, window, s1Re, s1Im);
            double phi0 = Math.atan2(s0Im[refIntBin], s0Re[refIntBin]);
            double phi1 = Math.atan2(s1Im[refIntBin], s1Re[refIntBin]);
            kFractional = kappaFromPhaseStep(phi0, phi1, refIntBin, estStep, fftSize);
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
        // honest and un-pinned as the single-tone fundamental - never read
        // off the coherently-collapsed average.  Only the 3 lobe bins feed
        // the phase difference / parabola, so noise bins can't bias it.
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
            int bestK = partnerToneBin(s0Re, s0Im, intFundBin, halfSize, freqRes);
            if (bestK > 0) effHint = bestK * freqRes;
        }
        // The clean segment's SECOND frame, or null when the segment is one
        // frame long - that is what decides between the one-hop phase
        // difference and the single-frame parabola in the estimate below.
        double[] segS1Re = (bestLen >= 2) ? scratchS1Re : null;
        double[] segS1Im = (bestLen >= 2) ? scratchS1Im : null;
        if (!Double.isNaN(effHint) && effHint > 0.0) {
            double kf2 = refineToneKappaAt((int) Math.round(effHint / freqRes), 2,
                    s0Re, s0Im, segS1Re, segS1Im, halfSize, fftSize, step);
            if (!Double.isNaN(kf2)) {
                outResult.fundamental2HzRefined = kf2 * freqRes;
            }
        }

        // --- Full-integration κ refit (long-baseline) ------------------------
        // The coarse κ above is a one-hop phase difference; its residual error is
        // applied as a de-rotation ramp across the whole segment, so on long
        // averages the harmonics de-cohere and read low.  Refine κ by regressing
        // the fundamental phase over EVERY clean frame - a whole-segment
        // baseline vs one hop.  See refineKappaOverSegment.
        if (coherentAveraging && bestLen >= 4) {
            double kRefined = refineKappaOverSegment(samples, window, fftSize, step,
                    bestStart, bestLen, refIntBin, kFractional);
            if (Math.abs(kRefined - kFractional) < 0.5) {       // sanity: reject a wild fit
                if (log.isInfoEnabled()) {
                    log.info("k_f full-segment refit over {} frames: {} -> {} ({} bins, {} Hz)",
                            bestLen,
                            String.format(Locale.US, "%.6f", kFractional),
                            String.format(Locale.US, "%.6f", kRefined),
                            String.format(Locale.US, "%.2e", kRefined - kFractional),
                            String.format(Locale.US, "%.6f", (kRefined - kFractional) * freqRes));
                }
                kFractional = kRefined;
            }
        }

        // --- Pilot tones: the two bins the generator holds them on.  Written
        // on every call (NaN / -1 when a slot carries no pilot) so a recycled
        // result slot cannot hand the next tick a pilot that is no longer
        // there.  Nothing is measured at a pilot itself: it stands on a whole
        // bin of this very grid, so its frequency is that bin times the bin
        // width, whatever fractional position the fundamental holds.
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
        // as the product part of pass 2's coherent family below: each entry
        // becomes a de-rotation region of its own, rotated by a·Φ1 + b·Φ2.
        // Default to "no products published": recycled result slots must not
        // leak a previous dual-tone tick's grid into a single-tone tick.
        outResult.imdProductA   = NO_IMD_PRODUCTS;
        outResult.imdProductB   = NO_IMD_PRODUCTS;
        outResult.imdProductBin = NO_IMD_PRODUCTS;
        if (coherentAveraging && bestLen >= 2 && !Double.isNaN(outResult.fundamental2HzRefined)
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

        // --- Pass 2: accumulate frames in the longest clean segment ----------
        // Also store per-frame corrected fundamental complex value so we can
        // detect sub-slew sample drops (which manifest as a phase step in the
        // fundamental that the time-shift correction cannot account for).
        int    intFundBinRounded = Math.max(1, (int) Math.round(kFractional));
        double[] fundCorrRe = coherentAveraging ? new double[bestLen] : null;
        double[] fundCorrIm = coherentAveraging ? new double[bestLen] : null;
        // Coherent family for the region-constant de-rotation (the same
        // construction as the cross-tick accumulator's - see the pass-2
        // comment below): member positions and (a, b, p) coefficients built
        // once per call, phasors re-derived per frame from that frame's
        // Φ1/Φ2/Φpilot1/Φpilot2.  The pilot coefficient p names which pilot a
        // member is: 0 none, 1 the first, 2 the second.  Members: the harmonic
        // ladder h·κ (h = 0 covers DC and the deep bass side) merged
        // position-sorted with the published product grid and with the bin of
        // each pinned pilot.  A pilot is neither a multiple of κ nor a
        // product, so without a member of its own its lobe would ride the
        // nearest harmonic's rotation and the frames of a tick would sum there
        // with a residual of up to half the harmonic spacing - attenuated and
        // turned by the drawn hop.  As a member it sums as coherently as the
        // fundamental does, and each pilot turns by its own bin's time-shift
        // phase alone, independent of the fundamental's fractional position.
        final double f2Kappa = (outResult.imdProductBin.length > 0)
                ? outResult.fundamental2HzRefined / freqRes : Double.NaN;
        int     famCount = 0;
        int[]   fA  = null;
        int[]   fB  = null;
        int[]   fPil = null;
        double[] fP = null;
        if (coherentAveraging && kFractional > 0.0) {
            int hMaxT = (int) Math.min(halfSize, halfSize / kFractional);
            int[] pa = outResult.imdProductA;
            int[] pb = outResult.imdProductB;
            int   nGrid = (f2Kappa > 0.0) ? pa.length : 0;
            int[]    spA = new int[nGrid + 2];              // + a slot per pilot
            int[]    spB = new int[nGrid + 2];
            int[]    spPil = new int[nGrid + 2];
            double[] spP = new double[nGrid + 2];
            int nP = 0;
            for (int p = 0; p < nGrid; p++) {
                double posP = pa[p] * kFractional + pb[p] * f2Kappa;
                if (posP < 1.0 || posP > halfSize - 1) continue;
                spA[nP] = pa[p];
                spB[nP] = pb[p];
                spP[nP] = posP;
                nP++;
            }
            if (pilotBin1 > 1 && pilotBin1 < halfSize - 1) {
                spA[nP] = 0;
                spB[nP] = 0;
                spPil[nP] = 1;
                spP[nP] = pilotBin1;
                nP++;
            }
            if (pilotBin2 > 1 && pilotBin2 < halfSize - 1) {
                spA[nP] = 0;
                spB[nP] = 0;
                spPil[nP] = 2;
                spP[nP] = pilotBin2;
                nP++;
            }
            for (int i = 1; i < nP; i++) {                  // position sort, tiny n
                int ia = spA[i];
                int ib = spB[i];
                int ip2 = spPil[i];
                double ip = spP[i];
                int j = i - 1;
                while (j >= 0 && spP[j] > ip) {
                    spA[j + 1] = spA[j];
                    spB[j + 1] = spB[j];
                    spPil[j + 1] = spPil[j];
                    spP[j + 1] = spP[j];
                    j--;
                }
                spA[j + 1] = ia;
                spB[j + 1] = ib;
                spPil[j + 1] = ip2;
                spP[j + 1] = ip;
            }
            fA = new int[hMaxT + 1 + nP];
            fB = new int[fA.length];
            fPil = new int[fA.length];
            fP = new double[fA.length];
            int pi = 0;
            for (int h = 0; h <= hMaxT; h++) {
                double hp = h * kFractional;
                while (pi < nP && spP[pi] <= hp) {
                    fA[famCount] = spA[pi];
                    fB[famCount] = spB[pi];
                    fPil[famCount] = spPil[pi];
                    fP[famCount] = spP[pi];
                    famCount++;
                    pi++;
                }
                fA[famCount] = h;
                fB[famCount] = 0;
                fPil[famCount] = 0;
                fP[famCount] = hp;
                famCount++;
            }
            while (pi < nP) {
                fA[famCount] = spA[pi];
                fB[famCount] = spB[pi];
                fPil[famCount] = spPil[pi];
                fP[famCount] = spP[pi];
                famCount++;
                pi++;
            }
        }
        final int      nFam   = famCount;
        final int[]    famA   = fA;
        final int[]    famB   = fB;
        final int[]    famPil = fPil;
        final double[] famPos = fP;
        final double[] famC   = (nFam > 0) ? new double[nFam] : null;
        final double[] famS   = (nFam > 0) ? new double[nFam] : null;
        // Region-constant de-rotation.  A windowed tone's lobe - the peak and
        // its off-centre bins together - advances by ONE common phase per
        // frame step (the tone's own frequency times the step), so a per-bin
        // ramp mis-rotates every off-centre lobe bin and the overlapped frame
        // sum carves the lobe into a comb; a constant phasor per lobe keeps
        // it intact under every window.  The regions tile the spectrum at the
        // MIDPOINTS between the coherent family's members (the same
        // half-spacing rule the SNR noise separation is built on), each
        // member rotated by its derived phase a·Φ1 + b·Φ2 - so no edge is
        // ever measured and no environmental line can earn a region of its
        // own.  The upper half of the full-length transform carries the
        // NEGATIVE frequencies: a bin there belongs to the member at |kf| and
        // rotates by the CONJUGATE phasor.
        // RECT exception: on-bin content occupies single bins (no lobe to hold
        // together) while the 1/Δf skirt crosses every region midpoint, which
        // is where a piecewise rotation prints - so RECT keeps the per-bin
        // time-shift ramp with the SIGNED bin index; tapered windows take the
        // region-constant family.
        final boolean rectWin   = windowType == WindowType.RECT;
        final double  rampScale = (kFractional > 0.0 && intFundBinRounded > 0)
                ? kFractional / intFundBinRounded
                : 1.0;
        int acceptedFrames = 0;
        for (int f = bestStart; f < bestStart + bestLen; f++) {
            int base = f * step;
            cachedFrameFft(samples, base, fftSize, window, frameRe, frameIm);
            if (coherentAveraging && rectWin) {
                final double thetaF = -2.0 * Math.PI * (double) base * rampScale
                                      / (double) fftSize;
                final double stepRe = Math.cos(thetaF);
                final double stepIm = Math.sin(thetaF);
                final int frameIdx = f - bestStart;
                final int ifb      = intFundBin;
                parallelChunks(fftSize, (kLo, kHi) -> {
                    int seg = kLo;
                    while (seg < kHi) {
                        int segEnd = (seg <= halfSize) ? Math.min(kHi, halfSize + 1) : kHi;
                        int    kfSeed = (seg <= halfSize) ? seg : seg - fftSize;
                        double ang    = Math.IEEEremainder(thetaF * kfSeed, 2.0 * Math.PI);
                        double phRe   = Math.cos(ang);
                        double phIm   = Math.sin(ang);
                        for (int k = seg; k < segEnd; k++) {
                            double cRe = frameRe[k] * phRe - frameIm[k] * phIm;
                            double cIm = frameRe[k] * phIm + frameIm[k] * phRe;
                            sumRe[k] += cRe;
                            sumIm[k] += cIm;
                            if (k == ifb) {
                                fundCorrRe[frameIdx] = cRe;
                                fundCorrIm[frameIdx] = cIm;
                            }
                            double nextRe = phRe * stepRe - phIm * stepIm;
                            phIm = phRe * stepIm + phIm * stepRe;
                            phRe = nextRe;
                        }
                        seg = segEnd;
                    }
                });
            } else if (coherentAveraging && nFam > 0) {
                final double phi1 = -2.0 * Math.PI
                        * Math.IEEEremainder((double) base * kFractional, fftSize) / fftSize;
                final double phi2 = (f2Kappa > 0.0)
                        ? -2.0 * Math.PI
                            * Math.IEEEremainder((double) base * f2Kappa, fftSize) / fftSize
                        : 0.0;
                // Indexed by the member's pilot coefficient: slot 0 is the
                // members that are no pilot and contributes nothing.
                final double[] phiPilot = {
                        0.0,
                        (pilotBin1 > 0)
                                ? -2.0 * Math.PI
                                    * Math.IEEEremainder((double) base * pilotBin1, fftSize) / fftSize
                                : 0.0,
                        (pilotBin2 > 0)
                                ? -2.0 * Math.PI
                                    * Math.IEEEremainder((double) base * pilotBin2, fftSize) / fftSize
                                : 0.0 };
                for (int i = 0; i < nFam; i++) {
                    double ang = Math.IEEEremainder(
                            famA[i] * phi1 + famB[i] * phi2 + phiPilot[famPil[i]],
                            2.0 * Math.PI);
                    famC[i] = Math.cos(ang);
                    famS[i] = Math.sin(ang);
                }
                final int frameIdx = f - bestStart;
                final int ifb      = intFundBin;
                parallelChunks(fftSize, (kLo, kHi) -> {
                    // Lower half: |kf| = k ascends, the member cursor advances
                    // upward.  Upper half: |kf| = fftSize - k DESCENDS as k
                    // ascends, so its cursor walks downward.  A chunk spanning
                    // the boundary handles each side with its own cursor.
                    int seg = kLo;
                    while (seg < kHi) {
                        boolean lower = seg <= halfSize;
                        int segEnd = lower ? Math.min(kHi, halfSize + 1) : kHi;
                        if (lower) {
                            int c = 0;
                            while (c < nFam - 1 && seg > 0.5 * (famPos[c] + famPos[c + 1])) c++;
                            for (int k = seg; k < segEnd; k++) {
                                while (c < nFam - 1 && k > 0.5 * (famPos[c] + famPos[c + 1])) c++;
                                double cRe = frameRe[k] * famC[c] - frameIm[k] * famS[c];
                                double cIm = frameRe[k] * famS[c] + frameIm[k] * famC[c];
                                sumRe[k] += cRe;
                                sumIm[k] += cIm;
                                if (k == ifb) {
                                    fundCorrRe[frameIdx] = cRe;
                                    fundCorrIm[frameIdx] = cIm;
                                }
                            }
                        } else {
                            int c = nFam - 1;
                            while (c > 0 && fftSize - seg <= 0.5 * (famPos[c - 1] + famPos[c])) c--;
                            for (int k = seg; k < segEnd; k++) {
                                while (c > 0 && fftSize - k <= 0.5 * (famPos[c - 1] + famPos[c])) c--;
                                double cRe =  frameRe[k] * famC[c] + frameIm[k] * famS[c];
                                double cIm = -frameRe[k] * famS[c] + frameIm[k] * famC[c];
                                sumRe[k] += cRe;
                                sumIm[k] += cIm;
                            }
                        }
                        seg = segEnd;
                    }
                });
            } else if (coherentAveraging) {
                // No pinned fundamental: nothing coherent to align - add the
                // frame straight (the capture below still records the bin).
                final int frameIdx = f - bestStart;
                final int ifb      = intFundBin;
                parallelChunks(fftSize, (kLo, kHi) -> {
                    for (int k = kLo; k < kHi; k++) {
                        sumRe[k] += frameRe[k];
                        sumIm[k] += frameIm[k];
                        if (k == ifb) {
                            fundCorrRe[frameIdx] = frameRe[k];
                            fundCorrIm[frameIdx] = frameIm[k];
                        }
                    }
                });
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
        if (frameRejection && coherentAveraging && bestLen >= 4) {
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
                    // Re-FFT the rejected frame and subtract its corrected
                    // spectrum.  This is the exact inverse of the pass-2 add
                    // above, so it runs the SAME region-constant family
                    // rotation - the member phasors re-derived for THIS
                    // frame's offset, the upper (negative-frequency) half
                    // subtracting with the conjugate.  Serial; only an
                    // outlier frame reaches this loop.
                    int f    = bestStart + i;
                    int base = f * step;
                    cachedFrameFft(samples, base, fftSize, window, frameRe, frameIm);
                    if (rectWin) {
                        // RECT: the same signed-index ramp as the add path,
                        // serial - seeded at bin 0 and at the half boundary.
                        double thetaR  = -2.0 * Math.PI * (double) base * rampScale
                                         / (double) fftSize;
                        double cosBase = Math.cos(thetaR);
                        double sinBase = Math.sin(thetaR);
                        int seg = 0;
                        while (seg < fftSize) {
                            int segEnd = (seg <= halfSize) ? halfSize + 1 : fftSize;
                            int    kfSeed = (seg <= halfSize) ? seg : seg - fftSize;
                            double ang    = Math.IEEEremainder(thetaR * kfSeed, 2.0 * Math.PI);
                            double corrRe = Math.cos(ang);
                            double corrIm = Math.sin(ang);
                            for (int k = seg; k < segEnd; k++) {
                                sumRe[k] -= frameRe[k] * corrRe - frameIm[k] * corrIm;
                                sumIm[k] -= frameRe[k] * corrIm + frameIm[k] * corrRe;
                                double nextCorrRe = corrRe * cosBase - corrIm * sinBase;
                                corrIm            = corrRe * sinBase + corrIm * cosBase;
                                corrRe            = nextCorrRe;
                            }
                            seg = segEnd;
                        }
                    } else {
                        double phi1R = -2.0 * Math.PI
                                * Math.IEEEremainder((double) base * kFractional, fftSize) / fftSize;
                        double phi2R = (f2Kappa > 0.0)
                                ? -2.0 * Math.PI
                                    * Math.IEEEremainder((double) base * f2Kappa, fftSize) / fftSize
                                : 0.0;
                        for (int n = 0; n < nFam; n++) {
                            double ang = Math.IEEEremainder(famA[n] * phi1R + famB[n] * phi2R,
                                    2.0 * Math.PI);
                            famC[n] = Math.cos(ang);
                            famS[n] = Math.sin(ang);
                        }
                        int c = 0;
                        for (int k = 0; k <= halfSize; k++) {
                            while (c < nFam - 1 && k > 0.5 * (famPos[c] + famPos[c + 1])) c++;
                            sumRe[k] -= frameRe[k] * famC[c] - frameIm[k] * famS[c];
                            sumIm[k] -= frameRe[k] * famS[c] + frameIm[k] * famC[c];
                        }
                        c = nFam - 1;
                        for (int k = halfSize + 1; k < fftSize; k++) {
                            while (c > 0 && fftSize - k <= 0.5 * (famPos[c - 1] + famPos[c])) c--;
                            sumRe[k] -=  frameRe[k] * famC[c] + frameIm[k] * famS[c];
                            sumIm[k] -= -frameRe[k] * famS[c] + frameIm[k] * famC[c];
                        }
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
                outResult.rejectedFrames          = phaseRejected;
                outResult.rejectionTotalFrames    = bestLen;
                outResult.rejectionPhaseCoherence = true;
                outResult.rejectionDetail         = maxDeviationDeg;   // degrees
            }
            if (acceptedFrames < 2) {
                throw new IllegalStateException("Only " + acceptedFrames
                        + " frame(s) survived phase coherence check - aborting FFT.");
            }
        }

        frameCount = acceptedFrames;

        // --- Single-sided amplitude & phase spectrum -------------------------
        double   normFactor = 1.0 / ((double) fftSize * cohGain);
        // avgRe / avgIm / amplDbFs / phaseDeg are stored in outResult and
        // outlive this call - but the pool guarantees outResult is owned
        // exclusively by this analysis until the worker publishes it.
        // Aliasing the slot's pre-sized arrays as locals avoids per-tick
        // allocation of four halfSize+1-long arrays.  amplLinear is
        // purely local and overwritten cell-by-cell below - safe to
        // reuse from scratch.
        double[] avgRe      = outResult.re;
        double[] avgIm      = outResult.im;
        scratchAmplLinear   = scratchOrAlloc(scratchAmplLinear, halfSize + 1);
        double[] amplLinear = scratchAmplLinear;
        double[] amplDbFs   = outResult.amplitudeDbFs;
        double[] phaseDeg   = outResult.phaseDeg;

        // Per-bin and independent -> parallelize across cores (the 1M× log10/atan2
        // is the single biggest compute chunk).  Disjoint k-writes, no recursion.
        final int fc = frameCount;
        parallelChunks(halfSize + 1, (lo, hi) -> {
            for (int k = lo; k < hi; k++) {
                double mag;
                if (coherentAveraging) {
                    avgRe[k] = sumRe[k] / fc;
                    avgIm[k] = sumIm[k] / fc;
                    mag = Math.sqrt(avgRe[k] * avgRe[k] + avgIm[k] * avgIm[k]);
                    phaseDeg[k] = Math.toDegrees(Math.atan2(avgIm[k], avgRe[k]));
                } else {
                    mag = Math.sqrt(sumRe[k] / fc);
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
        });

        // --- Fundamental (max bin, skip DC + ULF) ----------------------------
        // When the caller provided expectedFundHz, restrict the search to a
        // ±10-bin window around the expected bin - same reason as the pre-pass:
        // a deeply notched fundamental must not be lost to a louder spur.
        // Without a hint, scan FUND_SEARCH_MIN_HZ ... 0.7·Nyquist so neither DC
        // leakage nor a high-frequency spur wins.
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
            for (int k = fundSearchMinBin + 1; k <= fundSearchMaxBin; k++) {
                if (amplLinear[k] > amplLinear[fundBin]) fundBin = k;
            }
        }
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
        // only; the GUI / CLI callers convert their dBV inputs at the boundary
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

        // --- Averaging fast-path: skip the per-tick THD / SNR / SINAD /
        // noise-floor sweeps (including the two O(N log N) noise sorts).  While
        // the live view is cross-tick averaging it throws these single-tick
        // stats away and re-derives them with recomputeStats() on the
        // ACCUMULATED spectrum (display ticks only) - so computing them here ~3×
        // per second is pure waste.  Everything recomputeStats() and the worker
        // actually read was already produced above: the averaged complex
        // spectrum (re/im), the dB magnitude (amplitudeDbFs - read by the
        // worker's tone-detect / overlay / dBV lift), and the fundamental +
        // harmonic BIN positions (recomputeStats reuses them, never re-finds).
        // The skipped scalars are neutralised so a recycled result slot cannot
        // leak a previous tick's numbers if anything reads them before the
        // display rebuild.
        if (spectrumOnly) {
            outResult.fftSize                    = fftSize;
            outResult.sampleRate                 = sampleRate;
            outResult.frameCount                 = frameCount;
            outResult.freqResolution             = freqRes;
            outResult.windowType                 = windowType;
            outResult.overlap                    = overlap;
            outResult.stepSamples                = step;
            // The display path re-derives all stats via recomputeStats() on THIS
            // result - it needs the window's NENBW stamped or its noise-integral
            // correction silently no-ops (that exact miss shipped the first
            // version of the ENBW fix as a visible no-change).
            outResult.windowNenbwBins            = nenbw;
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
            // previous dual-tone tick's products.
            outResult.imd                        = null;
            outResult.preCorrectionPeaks         = null;
            // The reference channel's frequencies are the worker's to write
            // after this returns; cleared here for the same reason
            // preCorrectionPeaks is - a pooled slot must not hand the next tick
            // the previous one's numbers.
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
        final int EXCL_BINS = 4;
        boolean[] isSignalBin = buildSignalBinMask(halfSize, fundBin, hBins, harmonicCount,
                EXCL_BINS, windowType, outResult.pilot1Bin, outResult.pilot2Bin);

        // --- SNR - integrated noise over the measurement band ----------------
        // Fundamental and all harmonics are excluded via isSignalBin.
        // SNR = signal_power / sum(noise_bins_in_range) -> bandwidth-dependent.

        NoiseFloor nf = computeNoiseFloorAndExtendSignalMask(
                amplLinear, isSignalBin, halfSize, freqRes, snrLo, snrHi, fundBin, EXCL_BINS,
                skirtLookAheadBins(fftSize, overlap.minHopSamples(fftSize)));
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

        if (logSummary) {
            log.info("THD   : {}%  ({} dB)",
                    String.format(Locale.US, "%.8f", thdPct),
                    String.format(Locale.US, "%.2f", thdDb));
            log.info("THD+N : {} dB", String.format(Locale.US, "%.2f", thdNDb));
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
        outResult.avgNoiseFloorDbFs          = avgNoiseFloorDbFs;
        outResult.fundamentalTrueDbFs        = fundTrueDbFs;
        outResult.fundamentalDynExclusionHz  = dynWidthBins * freqRes;
        deriveImd(outResult);
        // preCorrectionPeaks is owned by the worker (set after analyze
        // returns when calibration is loaded); reset here so a previous
        // tick's value doesn't leak across pool slot recycle.  The reference
        // channel's two frequencies are the worker's in exactly the same way.
        outResult.preCorrectionPeaks = null;
        outResult.referenceFundamentalHzRefined  = Double.NaN;
        outResult.referenceFundamental2HzRefined = Double.NaN;
        outResult.captureRawPeaks();   // raw F + peak phasors for the DAC-predistortion de-embed
        return outResult;
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
     * live path refines a one-frame segment, written to
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
        // One spectrum, no second frame: the parabolic branch, so the hop
        // length is never read.
        double kf2 = refineToneKappaAt(partnerBin, 2, stored.re, stored.im, null, null,
                halfSize, stored.fftSize, 0);
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
        int halfSize = stored.amplitudeDbFs.length - 1;
        // Spectrum kept; the harmonic arrays resize only when the count changed.
        stored.ensureArrays(stored.amplitudeDbFs.length, calcHarmonics);
        stored.harmonicCount = calcHarmonics;
        for (int h = 0; h < calcHarmonics; h++) {
            double hz = (h + 2) * stored.fundamentalHzRefined;
            int    hb = (int) Math.round(hz / stored.freqResolution);
            stored.harmonicHz[h]   = hz;
            stored.harmonicBins[h] = (hb >= 1 && hb <= halfSize) ? hb : -1;
        }
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
        parallelChunks(halfSize + 1, (lo, hi) -> {
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
            double aCombined = Math.sqrt((1.0 - w) * pMain + w * pAdj);
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

        // --- Signal-bin mask: fundamental + every harmonic +/- EXCL_BINS ---
        final int EXCL_BINS = 4;
        boolean[] isSignalBin = buildSignalBinMask(halfSize, fundBin,
                r.harmonicBins, harmonicCount, EXCL_BINS, r.windowType,
                r.pilot1Bin, r.pilot2Bin);

        // --- Noise floor + signal-mask extension (shared with analyze()) ---
        // recomputeStats discards dynWidthBins - the
        // Result.fundamentalDynExclusionHz is final and was set on the
        // original analyze() pass; we only need the median noise power.
        double medianNoisePow = computeNoiseFloorAndExtendSignalMask(
                amplLinear, isSignalBin, halfSize, freqRes, snrLo, snrHi, fundBin, EXCL_BINS,
                skirtLookAheadBins(r.fftSize, r.overlap != null
                        ? r.overlap.minHopSamples(r.fftSize) : r.stepSamples))
                .medianNoisePow;

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
                    double p = amplLinear[k] * amplLinear[k];
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
        r.avgNoiseFloorDbFs = medianNoisePow > 0
                ? 20.0 * Math.log10(Math.sqrt(medianNoisePow))
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
    // Window functions
    // =========================================================================

    /**
     * Builds a normalized analysis window of length {@code N} for the given type.
     * All windows use (N−1) symmetric normalization except Flat-top which uses
     * periodic (N) normalization for optimal amplitude accuracy.
     */
    double[] buildWindow(int N, WindowType type) {
        switch (type) {
            case RECT: {
                double[] w = new double[N];
                Arrays.fill(w, RECT_COEFFS[0]);
                return w;
            }

            case HANN:
                return buildCosineSum(N, HANN_COEFFS, N - 1);

            case BH4:
                return buildCosineSum(N, BH4_COEFFS, N - 1);

            case BH7:
                return buildCosineSum(N, BH7_COEFFS, N - 1);

            case FT:
                return buildCosineSum(N, FT_COEFFS, N);

            case HFT144D:
                return buildHftWindow(N, HFT144D_COEFFS);

            case HFT248D:
                return buildHftWindow(N, HFT248D_COEFFS);

            case KB24:
                return buildKaiserWindow(N, KB24_BETA);

            case KB38:
                return buildKaiserWindow(N, KB38_BETA);

            case DC150:
                return buildChebyshevWindow(N, DC150_ATTEN_DB);

            case DC200:
                return buildChebyshevWindow(N, DC200_ATTEN_DB);

            case DC250:
                return buildChebyshevWindow(N, DC250_ATTEN_DB);

            case DC300:
                return buildChebyshevWindow(N, DC300_ATTEN_DB);

            default:
                throw new IllegalArgumentException("Unknown window type: " + type);
        }
    }

    /** Builds a cosine-sum window of length {@code N} from {@code coeffs}:
     *  {@code w[n] = Σ (−1)ᵏ·cₖ·cos(k·2πn/period)}, the sign alternating with
     *  the term index.  {@code period} is {@code N−1} for the symmetric
     *  windows and {@code N} for the periodic flat-top, the normalization
     *  each family is defined with. */
    private double[] buildCosineSum(int N, double[] coeffs, int period) {
        double[] w = new double[N];
        for (int n = 0; n < N; n++) {
            double t   = 2.0 * Math.PI * n / period;
            double val = 0.0;
            for (int k = 0; k < coeffs.length; k++) {
                val += (k % 2 == 0 ? 1.0 : -1.0) * coeffs[k] * Math.cos(k * t);
            }
            w[n] = val;
        }
        return w;
    }

    /** Rectangular window: one constant term, no cosine at all. */
    private static final double[] RECT_COEFFS = { 1.0 };

    /** Hann coefficients - the raised cosine {@code ½(1 − cos t)}. */
    private static final double[] HANN_COEFFS = { 0.5, 0.5 };

    /** Minimum 4-term Blackman-Harris coefficients (Harris 1978). */
    private static final double[] BH4_COEFFS = { 0.35875, 0.48829, 0.14128, 0.01168 };

    /** 7-term Blackman-Harris coefficients (Nuttall 1981). */
    private static final double[] BH7_COEFFS = {
            0.2712203606, 0.4334446123, 0.2180041184, 0.0657853433,
            0.0107618673, 0.0007700127, 0.0000136547 };

    /** SRS SR785 5-term flat-top coefficients - periodic normalization (N). */
    private static final double[] FT_COEFFS = {
            0.21557895, 0.41663158, 0.27726316, 0.08357895, 0.00694737 };

    /** HFT144D cosine-sum coefficients (Heinzel/Rüdiger/Schilling 2002,
     *  "Spectrum and spectral density estimation by the DFT...") - flat-top,
     *  −144.1 dB highest sidelobe, ±0.0001 dB amplitude flatness,
     *  differentiable. */
    private static final double[] HFT144D_COEFFS = {
            1.0, -1.96760033, 1.57983607, -0.81123644,
            0.22583558, -0.02773848, 0.00090360 };

    /** HFT248D coefficients (same paper) - flat-top, −248.4 dB highest
     *  sidelobe, ±0.0001 dB amplitude flatness; sidelobes below anything a
     *  physical ADC produces. */
    private static final double[] HFT248D_COEFFS = {
            1.0, -1.985844164102, 1.791176438506, -1.282075284005,
            0.667777530266, -0.240160796576, 0.056656381764,
            -0.008134974479, 0.000624544650, -0.000019808998,
            0.000000132974 };

    /** Kaiser-Bessel shape parameters β of the two offered windows. */
    private static final double KB24_BETA = 24.0;
    private static final double KB38_BETA = 38.0;

    /** Dolph-Chebyshev sidelobe attenuations of the four offered windows, dB. */
    private static final double DC150_ATTEN_DB = 150.0;
    private static final double DC200_ATTEN_DB = 200.0;
    private static final double DC250_ATTEN_DB = 250.0;
    private static final double DC300_ATTEN_DB = 300.0;

    /**
     * Main-lobe half-width of a window in BINS - the first null of its own
     * transform, so two lines closer than this are one hump and neither can be
     * read.  Analytic, from the window's definition alone: it does not depend
     * on the transform size and no window table is built to obtain it.
     *
     * <ul>
     *   <li>cosine sums (incl. the HFT flat-tops): a tone sitting on a bin
     *       raises exactly the {@code coeffs.length − 1} bins each side and
     *       nothing beyond, so the first null is the coefficient count;</li>
     *   <li>Kaiser: the trigonometric branch of its transform,
     *       {@code sin(√(u² − β²))/√(u² − β²)} with {@code u = πd}, first
     *       vanishes at {@code d = √(β² + π²)/π};</li>
     *   <li>Dolph-Chebyshev: the first zero of {@code T_{N−1}(x₀·cos(πd/N))}
     *       sits at {@code d = N·acos(cos(π/(2(N−1)))/x₀)/π}, and since
     *       {@code x₀ = cosh(acosh(10^(A/20))/(N−1))} that equals
     *       {@code √((π/2)² + acosh(10^(A/20))²)/π} for every transform size
     *       in use, far inside the one bin the ceiling rounds to.</li>
     * </ul>
     */
    int mainLobeBins(WindowType type) {
        switch (type) {
            case RECT:
                return RECT_COEFFS.length;
            case HANN:
                return HANN_COEFFS.length;
            case BH4:
                return BH4_COEFFS.length;
            case BH7:
                return BH7_COEFFS.length;
            case FT:
                return FT_COEFFS.length;
            case HFT144D:
                return HFT144D_COEFFS.length;
            case HFT248D:
                return HFT248D_COEFFS.length;
            case KB24:
                return kaiserLobeBins(KB24_BETA);
            case KB38:
                return kaiserLobeBins(KB38_BETA);
            case DC150:
                return chebyshevLobeBins(DC150_ATTEN_DB);
            case DC200:
                return chebyshevLobeBins(DC200_ATTEN_DB);
            case DC250:
                return chebyshevLobeBins(DC250_ATTEN_DB);
            case DC300:
                return chebyshevLobeBins(DC300_ATTEN_DB);
            default:
                throw new IllegalArgumentException("Unknown window type: " + type);
        }
    }

    /** Bins added to a main lobe before a clearance is reported, so that no
     *  distance is ever answered from inside the lobe itself. */
    private static final int LEAKAGE_GUARD_BINS = 2;

    /**
     * The smallest WHOLE-bin distance at which this window's own leakage from
     * a line sitting on a bin is {@code dbDown} or more below that line -
     * never less than {@link #mainLobeBins} plus {@value #LEAKAGE_GUARD_BINS}
     * bins.  What a caller needs that has to keep two generated lines far
     * enough apart for each to be read at its own bin without the other's
     * skirt turning the phase it reads.
     *
     * <p>Derivation for a cosine sum {@code w[n] = Σ aₘ·cos(2πmn/N)}: its
     * transform is the sum of Dirichlet kernels displaced by {@code ±m},
     * {@code W(d) = Σ aₘ/2 · (sinc(d − m) + sinc(d + m))} in the large-N
     * limit, worth {@code a₀} at {@code d = 0}.  At a WHOLE bin {@code d} the
     * sinc of every non-zero integer vanishes, so a single term survives,
     * {@code a_d / 2}, and the leakage is {@code |a_d| / (2·a₀)} of the peak -
     * a magnitude, so which of the two families carries the alternating sign
     * in its table does not enter.  Past the last coefficient the transform is
     * zero, which bounds the search.  Two lines on whole bins of one grid are
     * exactly the case this answers.
     *
     * <p>Kaiser and Dolph-Chebyshev are not cosine sums - their transforms
     * have no such closed form and do not vanish between bins - so for them
     * the answer is the main lobe plus the guard.
     */
    int leakageClearBins(WindowType type, double dbDown) {
        double[] coeffs;
        switch (type) {
            case RECT:
                coeffs = RECT_COEFFS;
                break;
            case HANN:
                coeffs = HANN_COEFFS;
                break;
            case BH4:
                coeffs = BH4_COEFFS;
                break;
            case BH7:
                coeffs = BH7_COEFFS;
                break;
            case FT:
                coeffs = FT_COEFFS;
                break;
            case HFT144D:
                coeffs = HFT144D_COEFFS;
                break;
            case HFT248D:
                coeffs = HFT248D_COEFFS;
                break;
            default:
                return mainLobeBins(type) + LEAKAGE_GUARD_BINS;
        }
        double limit = Math.pow(10.0, -dbDown / 20.0);
        int    d     = 1;
        while (d < coeffs.length
                && Math.abs(coeffs[d]) / (2.0 * Math.abs(coeffs[0])) > limit) {
            d++;
        }
        return Math.max(d, mainLobeBins(type) + LEAKAGE_GUARD_BINS);
    }

    /** First null of the Kaiser transform, {@code √(β² + π²)/π} bins. */
    private int kaiserLobeBins(double beta) {
        return (int) Math.ceil(Math.sqrt(beta * beta + Math.PI * Math.PI) / Math.PI);
    }

    /** First null of the Dolph-Chebyshev transform for a sidelobe attenuation
     *  of {@code attenDb}, {@code √((π/2)² + acosh(10^(A/20))²)/π} bins. */
    private int chebyshevLobeBins(double attenDb) {
        double ripple = MathUtil.acosh(Math.pow(10.0, attenDb / 20.0));
        double halfPi = Math.PI / 2.0;
        return (int) Math.ceil(Math.sqrt(halfPi * halfPi + ripple * ripple) / Math.PI);
    }

    /** Builds an HFT-family flat-top window: a PERIODIC cosine sum
     *  {@code w(z) = Σ cₖ·cos(k·z)}, {@code z = 2πn/N} (signs live in the
     *  coefficients), normalized to peak 1.  The amplitude scaling
     *  self-normalizes via the coherent gain - peak-1 just keeps the
     *  window family consistent. */
    private double[] buildHftWindow(int N, double[] coeffs) {
        double[] w = new double[N];
        double max = 0.0;
        for (int n = 0; n < N; n++) {
            double z = 2.0 * Math.PI * n / N;
            double val = 0.0;
            for (int k = 0; k < coeffs.length; k++) {
                val += coeffs[k] * Math.cos(k * z);
            }
            w[n] = val;
            if (val > max) max = val;
        }
        for (int n = 0; n < N; n++) {
            w[n] /= max;
        }
        return w;
    }

    /** Builds a symmetric Kaiser-Bessel window,
     *  {@code w[n] = I₀(β·√(1−x²)) / I₀(β)} with {@code x = 2n/(N−1) − 1}.
     *  Sidelobe suppression follows Kaiser's {@code A ≈ 8.7 + β/0.1102} dB
     *  relation - β=24 ≈ −226 dB with a narrower main lobe than equally
     *  suppressing cosine windows; β=38 sits below the double-precision
     *  FFT floor (like DC300). */
    private double[] buildKaiserWindow(int N, double beta) {
        double[] w = new double[N];
        double denom = besselI0(beta);
        for (int n = 0; n < N; n++) {
            double x = 2.0 * n / (N - 1) - 1.0;
            w[n] = besselI0(beta * Math.sqrt(Math.max(0.0, 1.0 - x * x))) / denom;
        }
        return w;
    }

    /** Modified Bessel function of the first kind, order 0 - power series
     *  {@code Σ ((x/2)ᵏ/k!)²}; converges to double precision well within
     *  the iteration cap for the β range used here. */
    private double besselI0(double x) {
        double sum  = 1.0;
        double term = 1.0;
        double half = x / 2.0;
        for (int k = 1; k < 200; k++) {
            double f = half / k;
            term *= f * f;
            sum  += term;
            if (term < sum * 1e-17) break;
        }
        return sum;
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

    public String exportFftCsv(FftResult r, String directory) throws IOException {
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
     *  - without subtracting the bias the RMS-derived peakAmp inflates by
     *  √2·DC for any signal where DC is comparable to the AC swing. */
    private double sampleMean(double[] samples) {
        double sum = 0.0;
        for (double s : samples) sum += s;
        return sum / samples.length;
    }

    /** RMS of {@code samples} with the supplied {@code dcMean} subtracted
     *  beforehand.  Two-pass: caller computes {@link #sampleMean} first,
     *  then passes the result here. */
    private double dcRemovedRms(double[] samples, double dcMean) {
        double sumSq = 0.0;
        for (double s : samples) {
            double d = s - dcMean;
            sumSq += d * d;
        }
        return Math.sqrt(sumSq / samples.length);
    }

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
     *  main-lobe half-width, which is the width its skirt occupies, and
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
        int lobe = mainLobeBins(windowType);
        int lo   = Math.max(0, pilotBin - lobe);
        int hi   = Math.min(halfSize, pilotBin + lobe);
        return new int[]{ lo, hi };
    }

    /** Builds the "signal bin" mask: fundamental bin + all harmonic bins,
     *  each smeared by {@code exclBins} on either side to cover window
     *  leakage, plus - for a pilot bin of 0 or more - that bin smeared by the
     *  window's own main-lobe half-width, which is the width its skirt
     *  actually occupies.  A generated pilot is neither signal nor noise, so
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
                candidateCount = 0;
                for (int k = 1; k <= halfSize; k++) {
                    double freq = k * freqRes;
                    if (!isSignalBin[k] && freq >= snrLo && freq <= snrHi) {
                        candidatePow[candidateCount++] = amplLinear[k] * amplLinear[k];
                    }
                }
                medianNoisePow = candidateCount > 0
                        ? selectKth(candidatePow, candidateCount, candidateCount / 2)
                        : medianNoisePow;
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

    /** Circular median of {@code n} complex phasors {@code (re[i], im[i])}.
     *  Computes median(sin) and median(cos) on the unit-circle-normalised
     *  components, then takes {@code atan2(medSin, medCos)}.  Robust for
     *  tightly clustered phases - the expected case for the fundamental
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
     *  detector and every frame gets rejected - so the analyser bails
     *  out of rejection entirely instead.  See the inline rationale in
     *  {@code analyze}. */
    private double derivativeNoiseRatio(double[] samples, double peakAmp, double omegaPerSample) {
        double sumD2Sq = 0.0;
        for (int n = 1; n < samples.length - 1; n++) {
            double d2 = (double) samples[n + 1] - 2.0 * samples[n] + samples[n - 1];
            sumD2Sq += d2 * d2;
        }
        double rmsD2             = Math.sqrt(sumD2Sq / Math.max(1, samples.length - 2));
        double expectedSineRmsD2 = peakAmp * omegaPerSample * omegaPerSample / Constants.SQRT2;
        return rmsD2 / Math.max(1e-30, expectedSineRmsD2);
    }
}
