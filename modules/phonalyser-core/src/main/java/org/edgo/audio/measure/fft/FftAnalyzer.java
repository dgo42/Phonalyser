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

import lombok.extern.log4j.Log4j2;

/**
 * FFT-based audio analyzer: the raw transform of one frame, the fundamental
 * and second-tone detection, the harmonic ladder's positions, the pilots and
 * the intermodulation product grid - nothing measured on top of them.
 * Averaging over frames is the {@link SpectrumAccumulator}'s; the statistics
 * of the spectrum that is rendered - one frame or an average - are
 * {@link org.edgo.audio.measure.common.SpectrumStatistics}'.
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
     *  the window and stamped on the result, whose statistics divide their
     *  integrated noise sums by it (never coherent lines). */
    private double     cachedNenbw;

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
     *  bin and the frequency it stands for; and the result carries the bins
     *  it covers, which the statistics keep out of the noise, SNR and ENOB
     *  sums.
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

    /** The STORED spectrum this instance measures - one read back from file,
     *  which arrives as magnitude and phase instead of samples.  Held so every
     *  step that measures it ({@link #rebuildPhasors}, {@link #restoreNenbw},
     *  {@link #findFundamental}, {@link #findSecondTone}) speaks about the
     *  same spectrum;
     *  {@code null} on an analyser built for live samples. */
    private final FftResult stored;

    /** An analyser for live samples - what {@link #analyze} is called on. */
    public FftAnalyzer() {
        this(null);
    }

    /** An analyser bound to one stored spectrum, for the file-read path. */
    public FftAnalyzer(FftResult stored) {
        this.stored = stored;
    }

    // =========================================================================
    // Reusable buffers
    // =========================================================================

    /** Per-fftSize pair of working arrays for the frame's transform, purely
     *  internal to one {@code analyze} call - filled in, consumed, and
     *  discarded before the {@link FftResult} is returned (nothing in the
     *  result holds a reference to it).  Keeping them as instance fields and
     *  re-using them across calls eliminates the 2 × fftSize × 8 B allocation
     *  per analyze (16 MB at fftSize = 1 M).  Lazily (re-)allocated when
     *  fftSize changes. */
    private double[] frameRe, frameIm;

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
     *  allocates a fresh array.  The caller overwrites every cell. */
    private double[] reuse(double[] current, int size) {
        if (current != null && current.length == size) return current;
        return new double[size];
    }

    /** Drops the retained fftSize-scaled working arrays and the cached
     *  window table - at fftSize 4 M the set idles on tens of MB after the
     *  last {@code analyze}.  Everything reallocates on demand at the next
     *  call, so this belongs in a stop path, never mid-run.  Callers must not
     *  have an analysis in flight. */
    public void releaseBuffers() {
        frameRe          = null;
        frameIm          = null;
        cachedWindow     = null;
        cachedWindowSize = 0;
        cachedWindowType = null;
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

    // =========================================================================
    // Analysis
    // =========================================================================

    /**
     * The analysis method.
     * It analyses ONE frame: the {@code fftSize} samples at the start of
     * {@code samples}, transformed as they stand.  Averaging over frames is
     * the {@link SpectrumAccumulator}'s, which takes this result's spectrum
     * as one frame; {@code step} - the spacing between successive frame
     * starts, in samples - is stamped for the statistics that depend on how
     * many frames share a sample (the skirt look-ahead), and is the fixed
     * overlap's hop or the drawn one under the random overlap.
     *
     * <p>The frame carries the readout {@code settings} it was analysed with:
     * the harmonic count places the harmonic ladder, sizes the IMD product
     * grid and the captured peaks, and the predistortion reads the manual
     * fundamental off the frame.  Nothing is measured on the frame beyond
     * the tones' positions - the statistics are calculated on the spectrum
     * that is rendered.
     *
     * <p>Writes the analysis into the supplied {@code outResult} slot instead
     * of allocating a fresh result: the slot's bin / harmonic arrays are
     * resized as needed ({@link FftResult#ensureArrays}) and then used
     * directly as the analysis output buffers - no per-cycle allocation for
     * the three fftSize/2+1-long spectrum arrays.  Returns {@code outResult}
     * for convenience.
     */
    public FftResult analyze(double[] samples, int sampleRate, int fftSize,
                                 ReadoutSettings settings,
                                 WindowType windowType, FftOverlap overlap,
                                 boolean coherentAveraging, double expectedFundHz,
                                 FftResult outResult, int step) {
        int harmonicCount = settings.harmonicCount();
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
        if (log.isInfoEnabled()) {
            log.info("FFT: size={}, overlap={}, window={}, averaging={}, freq-res={} Hz, harmonics={}",
                    fftSize, overlap.label, windowType,
                    coherentAveraging ? "coherent" : "incoherent",
                    String.format(Locale.US, "%.4f", freqRes), harmonicCount);
        }

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
        // per-call allocation for the three halfSize+1-long arrays).
        outResult.ensureArrays(halfSize + 1, harmonicCount);
        // One frame's power average is the frame itself - its amplitudeDbFs -
        // so it needs no separate array; a pooled slot's earlier one is not
        // this frame's.
        outResult.hasPowerAverage = false;

        // --- The frame -------------------------------------------------------
        frameRe = reuse(frameRe, fftSize);
        frameIm = reuse(frameIm, fftSize);
        double[] f0Re = frameRe;
        double[] f0Im = frameIm;
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
            if (log.isInfoEnabled()) {
                log.info("Fundamental seeded from expected freq {} Hz: bin {} (±10-bin window)",
                        String.format(Locale.US, "%.6f", expectedFundHz), intFundBin);
            }
        } else {
            intFundBin = peakBin(f0Re, f0Im, fundSearchMinBin, fundSearchMaxBin);
            if (log.isInfoEnabled()) {
                log.info("Fundamental search (no hint): scanned bins {}..{} (~{}..{} Hz); picked bin {} (~{} Hz)",
                        fundSearchMinBin, fundSearchMaxBin,
                        String.format(Locale.US, "%.2f", fundSearchMinBin * freqRes),
                        String.format(Locale.US, "%.2f", fundSearchMaxBin * freqRes),
                        intFundBin,
                        String.format(Locale.US, "%.4f", intFundBin * freqRes));
            }
        }

        // --- Fundamental's fractional bin --------------------------------------
        // A single frame holds no phase step to read it from, so the
        // log-power parabola over the peak and its two neighbours gives it.
        // That is the one-frame readout only: an average pins it from the
        // phase step between consecutive frames (SpectrumAccumulator) and the
        // integer bin above is all that pin needs.
        double kFractional = MathUtil.parabolicBinInterp(f0Re, f0Im, intFundBin, fftSize);
        if (log.isInfoEnabled()) {
            log.info("k_f (one-frame parabola): intBin={}, k_f={}, refinedHz={}",
                    intFundBin,
                    String.format(Locale.US, "%.6f", kFractional),
                    String.format(Locale.US, "%.6f", kFractional * freqRes));
        }

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
        // The bins each one covers travel with it: the readouts that
        // integrate bins - the noise, SNR and ENOB sums and the dual-tone TD+N
        // residual - leave these spans out, and the window the width comes
        // from is known here alone.
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
        // (SpectrumAccumulator): each entry's hump turns by a·Φ1 + b·Φ2.
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

        // --- Single-sided amplitude spectrum ---------------------------------
        double   normFactor = 1.0 / ((double) fftSize * cohGain);
        // re / im / amplDbFs are stored in outResult and outlive this call -
        // but the pool guarantees outResult is owned exclusively by this
        // analysis until the worker publishes it.  Aliasing the slot's
        // pre-sized arrays as locals avoids per-cycle allocation of three
        // halfSize+1-long arrays.
        double[] outRe      = outResult.re;
        double[] outIm      = outResult.im;
        double[] amplDbFs   = outResult.amplitudeDbFs;

        // Per-bin and independent -> parallelize across cores (the 1M× log10
        // is the single biggest compute chunk).  Disjoint k-writes, no recursion.
        // The result carries the frame as it stands: the complex phasor per
        // bin under coherent averaging, the magnitude in re (im = 0) under
        // incoherent - the form each average of frames takes, so a single
        // frame and an average read alike.  The phase stays in the phasor.
        BinRanges.parallel(halfSize + 1, (lo, hi) -> {
            for (int k = lo; k < hi; k++) {
                double mag = Math.sqrt(f0Re[k] * f0Re[k] + f0Im[k] * f0Im[k]);
                if (coherentAveraging) {
                    outRe[k] = f0Re[k];
                    outIm[k] = f0Im[k];
                } else {
                    outRe[k] = mag;
                    outIm[k] = 0.0;
                }
                double amplLinear = (k == 0 || k == halfSize)
                        ? mag * normFactor
                        : mag * normFactor * 2.0;
                amplDbFs[k] = amplLinear > 1e-15
                        ? 20.0 * Math.log10(amplLinear)
                        : -300.0;
            }
        });

        // --- Fundamental: the bin the search above settled on ---------------
        int    fundBin        = intFundBin;
        double fundHz         = fundBin * freqRes;
        double fundHzRefined  = kFractional * freqRes;   // sub-bin accurate frequency
        double fundDbFs       = amplDbFs[fundBin];
        double fundLinear     = Math.pow(10.0, fundDbFs / 20.0);
        double fundRefDbFs    = settings.manualFundamentalDbFs();

        if (log.isInfoEnabled()) {
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
        }

        // --- The frame's scalars ----------------------------------------------
        // Written on every call, so a recycled result slot cannot hand the
        // next analysis cycle a previous one's numbers.  The statistics are
        // calculated on the spectrum that is rendered, never on this frame;
        // what they read from it is stamped here - the window's NENBW
        // (without it their noise-integral correction silently no-ops, the
        // exact miss that shipped the first version of the ENBW fix as a
        // visible no-change) and coherent gain, the geometry, the fundamental,
        // the harmonic ladder and the readout settings the frame was analysed
        // with.  The measured fundamental stays as the spectrum carries it:
        // the manual fundamental, already in dBFS, travels beside it.
        outResult.fftSize                    = fftSize;
        outResult.sampleRate                 = sampleRate;
        outResult.frameCount                 = 1;
        outResult.freqResolution             = freqRes;
        outResult.windowType                 = windowType;
        outResult.overlap                    = overlap;
        outResult.stepSamples                = step;
        outResult.windowNenbwBins            = nenbw;
        outResult.windowCoherentGain         = cohGain;
        outResult.fundamentalBin             = fundBin;
        outResult.fundamentalHz              = fundHz;
        outResult.fundamentalHzRefined       = fundHzRefined;
        outResult.fundamentalDbFs            = fundDbFs;
        outResult.fundamentalLinear          = fundLinear;
        outResult.thdHarmonics               = settings.thdHarmonics();
        outResult.snrFreqMin                 = settings.bandMinHz();
        outResult.snrFreqMax                 = settings.bandMaxHz();
        outResult.coherentAveraging          = coherentAveraging;
        outResult.fundamentalTrueDbFs        = fundRefDbFs;
        outResult.placeHarmonics(harmonicCount, kFractional);
        // The reference channel's frequencies are the worker's to write
        // after this returns; cleared here - a pooled slot must not hand the
        // next analysis cycle the previous one's numbers.
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
     * Stamps the window's equivalent noise bandwidth on a stored spectrum -
     * the NENBW in bins {@link #analyze} stamps on a live frame, computed from
     * the window the spectrum was analysed with ({@code windowType}) at its
     * FFT length.  The statistics divide their noise integrals by it; a
     * spectrum read back from file carries no copy of it.
     */
    public void restoreNenbw() {
        if (stored == null) return;     // no spectrum held - nothing to stamp
        getCachedWindow(stored.fftSize, stored.windowType);
        stored.windowNenbwBins = cachedNenbw;
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

    // =========================================================================
    // Helpers
    // =========================================================================

    /** The bins one pilot line covers, as the inclusive pair
     *  {@code {lo, hi}}: the bin it sits on, widened by the window's own
     *  main-lobe half-width plus {@link FftDsp#LEAKAGE_GUARD_BINS}, and
     *  clamped to the spectrum.  {@code {0, -1}}, an empty span, for a slot
     *  that carries no pilot.
     *
     *  <p>The one place that width is decided.  Each span is stamped on the
     *  result for the readouts that integrate bins - the noise, SNR and ENOB
     *  sums of the statistics and the dual-tone TD+N residual - so a pilot
     *  cannot be excluded in one of them and counted in another. */
    private int[] pilotMaskSpan(int pilotBin, WindowType windowType, int halfSize) {
        if (pilotBin < 0) return new int[]{ 0, -1 };
        int half = FftDsp.mainLobeBins(windowType) + FftDsp.LEAKAGE_GUARD_BINS;
        int lo   = Math.max(0, pilotBin - half);
        int hi   = Math.min(halfSize, pilotBin + half);
        return new int[]{ lo, hi };
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
