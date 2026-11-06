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
import java.util.List;

import org.edgo.audio.measure.dsp.FreqRespCalHelper;
import org.edgo.audio.measure.dsp.SpectralDiscontinuityDetector;
import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.WindowType;

/**
 * Container for all FFT analysis outputs.  A standalone top-level class so
 * each {@code FftResult} instance is fully independent of the
 * {@link FftAnalyzer} instance that produced it - important for the FFT
 * worker's double-buffered hand-off, where one analyser keeps emitting
 * fresh results while previous ones are still on the paint thread.
 */
public class FftResult {

    static final long serialVersionUID = 42L;

    /** FFT frame length (power of 2). */
    public int fftSize;
    /** Sample rate of the analyzed signal in Hz. */
    public int sampleRate;
    /** Number of frames that were coherently averaged. */
    public int frameCount;
    /** Frequency resolution in Hz per bin (= sampleRate / fftSize). */
    public double freqResolution;
    /** √(bin bandwidth in Hz) the spectrum was captured with - non-null only
     *  for results reconstructed from a saved file ({@code # bin_bw_hz=}
     *  header); {@code null} for live results, where the V/√Hz conversion
     *  uses the live-config cache in {@code Preferences} instead. */
    public Double binBwSqrt;
    /** Window function used. */
    public WindowType windowType;
    /** FftOverlap used. */
    public FftOverlap overlap;
    /** The frame spacing (samples) the analysis actually used.  Under the
     *  random overlap this is the cycle's drawn hop - the enum carries no
     *  single fraction.  Provenance first (saved into spectrum files); the
     *  skirt look-ahead falls back to it only when {@link #overlap} is
     *  unknown.  0 for a spectrum whose spacing is unknown (a loaded
     *  file without geometry headers). */
    public int stepSamples;

    // Single-sided spectrum, bins 0 ... fftSize/2.  Arrays are reused
    // across analysis cycles via the pool; {@link #ensureArrays} grows
    // them when the FFT length changes.
    public double[] amplitudeDbFs;
    /** The phase per bin in degrees as a spectrum file stores it, read back
     *  into {@link #re} / {@link #im} by {@link FftAnalyzer#rebuildPhasors};
     *  {@code null} on a live result, whose phase stands in its phasors. */
    public double[] phaseDeg;
    public double[] re;
    public double[] im;
    /** The power average of the frames a coherent average holds, per bin
     *  0 ... fftSize/2, as an RMS amplitude in dBFS on {@link #amplitudeDbFs}'s
     *  scale: the noise readouts (N, N+D, SNR, THD+N, ENOB, TD+N) integrate
     *  it, since the coherent mean lets noise fall with the frames held.
     *  Valid only while {@link #hasPowerAverage}; the array itself is kept
     *  across pooled reuse. */
    public double[] powerAverageDbFs;
    /** Whether {@link #powerAverageDbFs} belongs to this result: set by the
     *  coherent average's overlay and by a loaded file carrying the column,
     *  cleared by every analysis.  Without it the power is read off
     *  {@link #amplitudeDbFs}, which for a single frame and for an incoherent
     *  average already is the power average. */
    public boolean  hasPowerAverage;

    // Fundamental
    public int    fundamentalBin;
    public double fundamentalHz;
    /** Phase-difference refined frequency in Hz - sub-bin accurate (~1e-5 bin). */
    public double fundamentalHzRefined;
    /** Sub-bin refined frequency (Hz) of the second tone in a dual-/
     *  multi-tone signal, estimated by the same clean-frame method as
     *  {@link #fundamentalHzRefined}.  {@link Double#NaN} for single-tone
     *  (no second-tone hint was supplied). */
    public double fundamental2HzRefined = Double.NaN;
    /** Frequency (Hz) of the first pilot tone - one of the two generated
     *  lines the fundamental's phase alignment reads as its time base.  A
     *  pilot stands on a WHOLE bin of this very grid, so this is
     *  {@link #pilot1Bin} times {@link #freqResolution} and nothing about the
     *  fundamental's fractional position enters it.  {@link Double#NaN}
     *  whenever no first pilot is pinned. */
    public double pilot1HzRefined = Double.NaN;
    /** Frequency (Hz) of the second pilot tone, the companion of
     *  {@link #pilot1HzRefined}; {@link Double#NaN} whenever the second slot
     *  carries no pilot. */
    public double pilot2HzRefined = Double.NaN;
    /** The bin the first pilot sits on as pinned on the analyser, or
     *  {@code -1} when no first pilot is running. */
    public int pilot1Bin = -1;
    /** The bin the second pilot sits on, or {@code -1} when the second slot
     *  carries no pilot. */
    public int pilot2Bin = -1;
    /** The bins the first pilot's line covers, INCLUSIVE: its bin and the
     *  window's own main-lobe half-width plus the leakage guard on either
     *  side, clamped to the spectrum.  The span is empty whenever {@link #pilot1MaskHiBin} is
     *  below {@link #pilot1MaskLoBin}, which is what a result with no first
     *  pilot carries.  A generated pilot is neither signal nor distortion, so
     *  every readout that integrates bins leaves both spans out - the noise
     *  floor, SNR and ENOB of the single-tone path, and the dual-tone TD+N
     *  residual. */
    public int pilot1MaskLoBin =  0;
    public int pilot1MaskHiBin = -1;
    /** The bins the second pilot's line covers, the companion pair of
     *  {@link #pilot1MaskLoBin} / {@link #pilot1MaskHiBin}. */
    public int pilot2MaskLoBin =  0;
    public int pilot2MaskHiBin = -1;
    /** The fundamental's frequency in Hz as the REFERENCE channel carries it,
     *  refined the same sub-bin way, or {@link Double#NaN} whenever no
     *  reference channel was read this cycle.  Behind a notch the analysed
     *  fundamental is buried tens of dB deep while the reference one stands
     *  clear, so this is the better number to steer a frequency lock with; the
     *  accumulator, the coherent bin κ and every plot keep using the analysed
     *  channel's own estimate.  Written by the FFT worker after the analysis,
     *  never by {@code analyze}. */
    public double referenceFundamentalHzRefined = Double.NaN;
    /** The second tone's frequency in Hz on the reference channel, the
     *  dual-tone companion of {@link #referenceFundamentalHzRefined} and
     *  {@link Double#NaN} under the same conditions and for a single tone. */
    public double referenceFundamental2HzRefined = Double.NaN;
    /** The fundamental's frequency in Hz the generator frequency lock steers
     *  on while it is tracking: the moving average of its readings over the
     *  last two FFT lengths of samples, fewer while that span is still
     *  filling - the reference channel's readings in the reference-channel
     *  alignment, the analysed channel's otherwise.  {@link Double#NaN} while
     *  the lock is not tracking.  Written by the FFT worker, never by
     *  {@code analyze}. */
    public double fllAveragedHz  = Double.NaN;
    /** The second tone's, the dual-tone companion of {@link #fllAveragedHz}. */
    public double fllAveraged2Hz = Double.NaN;
    /** The fundamental's level as the spectrum carries it at
     *  {@link #fundamentalBin}, dBFS and linear (full scale 1): the measured
     *  level, and the corrected one once a {@code .frc} correction is applied
     *  to the spectrum in place - the scalars follow the bin.
     *  {@link #fundamental} carries both. */
    public double fundamentalDbFs;
    public double fundamentalLinear;
    /** The fundamental as the statistics measure it - its level as measured
     *  and de-embedded; {@code null} until the statistics ran. */
    public SpectralLine fundamental;

    /** The fundamental's fractional bin (κ) the rendered harmonic ladder -
     *  and with it every line's de-embedding - stands on: the accumulator's
     *  pinned one while averaging, the refined fundamental over the bin width
     *  otherwise - a single cycle's refined fundamental jitters, the pinned
     *  one does not.  Set by the worker on a live frame and by the loader on
     *  a file; {@link Double#NaN} on a result neither set. */
    public double coherentKappa = Double.NaN;
    /** Channel this result was analyzed for ({@code true} = left) - picks the
     *  left/right .frc calibration in the UI's post-average pipeline. */
    public boolean channelLeft = true;
    /** Absolute sample index of this result's analysis-window start (the
     *  worker's {@code samplesAbsStart}) - a frame's identity downstream: the
     *  predistortion statistics writer records each analysed frame once by it,
     *  however often the same result is polled. */
    public long samplesAbsStart;
    /** The producing worker's reset epoch at analysis start.  A result can sit
     *  parked in the coalescing UI hand-off across a signal change (the worker's
     *  epoch gates only cover production, not consumption); consumers compare
     *  this against the worker's CURRENT epoch and drop stale frames - without
     *  it, a pre-change spectrum is displayed and analysed as the new signal's
     *  after the change. */
    public long epoch;

    // Harmonics (index 0 = 2nd harmonic, ...): the ladder's positions, placed
    // on the refined fundamental ({@link #placeHarmonics}).
    public int      harmonicCount;
    public int[]    harmonicBins;
    public double[] harmonicHz;
    /** Each harmonic as the statistics measure it - its level as measured and
     *  de-embedded - index-parallel to {@link #harmonicHz}; {@code null} until
     *  the statistics ran. */
    public SpectralLine[] harmonics;
    /** Each harmonic's de-embedded level in % of the fundamental the ratios
     *  stand on. */
    public double[] harmonicPct;

    // Raw (pre-.frc-de-embed) complex phasors of the fundamental and of each
    // distortion peak, stamped by the analyser ({@link #captureRawPeaks}) off
    // the raw spectrum BEFORE any in-place .frc de-embed.  The DAC-predistortion
    // correction divides these by the calibration response H(f) itself - de-
    // embedding both MAGNITUDE and PHASE explicitly, independent of the display
    // pipeline.  NaN / null when no peaks were captured (then the correction
    // falls back to the live re/im).
    public double   rawFundRe = Double.NaN;
    public double   rawFundIm = Double.NaN;
    /** Raw phasors at each distortion peak the correction touches: the HARMONIC
     *  bins for a single tone, the de-rotated INTERMOD-PRODUCT bins
     *  ({@link #imdProductBin}) for a dual tone.  The two paths never run
     *  together, so one array serves both. */
    public double[] rawPeakRe;
    public double[] rawPeakIm;

    /** Each tone's phase AS THE DAC EMITTED IT, radians in a measured bin's
     *  convention, at the time origin the accumulated spectrum - and so
     *  {@link #rawPeakRe} - is referenced to.  {@link Double#NaN} where the
     *  run cannot know it.
     *
     *  <p>The predistortion loop measures every harmonic against the tone
     *  that generated it, and taking that tone off the fundamental's own bin
     *  reads it through the notch: its phase there is the calibration's
     *  static value plus however far the null has moved since, and that
     *  error reaches harmonic h multiplied by h.  The phase alignment already
     *  has the tone away from the notch - the opposite channel never passes
     *  it, and the pilot lines stand far above it - and states it here. */
    public double   dacFundPhaseRad  = Double.NaN;
    public double   dacFund2PhaseRad = Double.NaN;

    // Dual-tone intermod-product grid - for every intermodulation product
    // a·f1 + b·f2 (b ≠ 0) the analyzer de-rotated by a·Φ(F1) + b·Φ(F2) over
    // its lobe, the integer coefficients and the product's bin index, aligned
    // across the three arrays.  This lets the predistortion engine read a
    // phase-stable, coherently-averaged correction phasor straight from
    // {@code re}/{@code im} at each product bin (the same trustworthy phase
    // the per-tone harmonics enjoy via the single-reference de-rotation).
    // Empty when the signal isn't dual-tone or the per-product de-rotation
    // wasn't engaged this analysis cycle (F1's own harmonics live in
    // {@code harmonic*}).
    public int[] imdProductA;
    public int[] imdProductB;
    public int[] imdProductBin;

    /** Intermodulation readout of a dual-tone spectrum, all levels in dBFS -
     *  written with this result's statistics, from
     *  {@link #fundamentalHzRefined} and {@link #fundamental2HzRefined}.
     *  {@code null} when the spectrum carries no second tone. */
    public ImdResult imd;

    // Metrics - non-final so post-processing (e.g. ADC/frequency response correction) can mutate.
    public double thdPct;
    public double thdDb;
    public double thdNDb;
    public double snrDb;
    /** UNWEIGHTED in-band SINAD (MT-003): 10·log10(refLin² / (noisePower +
     *  in-band harmonic power)).  {@link #thdNDb} is its reciprocal. */
    public double sinadDb;
    /** The A-WEIGHTED noise+distortion level relative to the signal (negative
     *  dB) - the N+D audibility readout, the ONLY weighted figure. */
    public double awThdNDb;
    /** ENOB basis: the unweighted in-band SINAD denominator referred to FULL
     *  SCALE (the signal level cancels, so no fundamental term exists and no
     *  de-embedding combination can skew it) -
     *  (enobSinadDb − 1.76)/6.02 compares against converter datasheets.  Not
     *  the full Nyquist band: an oversampled sigma-delta parks its shaped
     *  noise above the audio band by design. */
    public double enobSinadDb;
    /** Lower bound of the frequency range used for SNR (Hz); 0 = no limit. */
    public double snrFreqMin;
    /** Upper bound of the frequency range used for SNR (Hz); 0 = no limit. */
    public double snrFreqMax;
    /** How many harmonics the THD sums, counted from the 2nd: H2 .. H(n+1),
     *  each still subject to the distortion window above - the readout
     *  settings' value the statistics were calculated with, and what the
     *  readout labels the sum with. */
    public int thdHarmonics;
    /** True = coherent (complex) averaging; false = incoherent (power) averaging. */
    public boolean coherentAveraging;
    /** Sum of amplLinear[k]^2 for noise bins inside the SNR band (unweighted),
     *  already divided by {@link #windowNenbwBins}. */
    public double noisePower;
    /** {@link #noisePower}'s IEC 61672 A-weighted sibling: the same noise bins
     *  weighted by the A curve before summing (same SNR band, same
     *  excluded-zone rescale, already divided by {@link #windowNenbwBins}).
     *  Feeds the N+D audibility readout ALONE - THD+N, SINAD and ENOB divide
     *  by the unweighted {@link #noisePower} basis (the MT-003 definitions),
     *  as do SNR and N. */
    public double awNoisePower;
    /** Normalized equivalent noise bandwidth of the analysis window, in bins
     *  (Hann: 1.5) - stamped at analysis time so a band-change recompute can
     *  apply the same noise-integral correction to THIS spectrum regardless of
     *  the window selected by then. */
    public double windowNenbwBins;
    /** Coherent gain of the analysis window - the mean of its table, 1 for
     *  the rectangular window - stamped at analysis time: a bin's amplitude
     *  is its raw magnitude times {@code 2 / (fftSize · gain)} (half that at
     *  DC and Nyquist), the scale an average of frames is converted with. */
    public double windowCoherentGain;
    /** Average noise floor: RMS amplitude of a single noise bin converted to dBFS. */
    public double avgNoiseFloorDbFs;
    /**
     * Half-width of the dynamic fundamental exclusion zone in Hz (one side).
     * For a clean recording this is a few Hz; a signal interruption causes
     * spectral splatter that broadens this to hundreds or thousands of Hz.
     */
    public double fundamentalDynExclusionHz;
    /**
     * User-supplied true fundamental level in dBFS - the manual fundamental
     * of the readout settings ({@link ReadoutSettings#manualFundamentalDbFs}),
     * converted from the stated volts to dBFS once at the input boundary
     * via the global ADC offset and never overwritten by post-processing.
     * When set:
     * <ul>
     *   <li>THD / SNR / THD+N / harmonic % use this as the ratio denominator,
     *       so an external twin-T notch on H1 cannot poison the metrics.</li>
     *   <li>The chart's fundamental peak marker and info-table show this
     *       level (lifted by the global dBV offset for the dBV column)
     *       instead of the notched, measured {@code fundamentalDbFs}.</li>
     * </ul>
     * It affects ONLY the fundamental - every other bin (harmonics, noise
     * floor, the spectrum trace) keeps the global dBFS->dBV offset.
     * {@link Double#NaN} when no manual override is supplied.
     */
    public double fundamentalTrueDbFs;

    /** Spectral discontinuity detector gate snapshot for the debug overlay
     *  ({@code DebugSwitches.SHOW_DISCONTINUITY_GATES}); {@code null} when the
     *  detector isn't running. */
    public SpectralDiscontinuityDetector.Gates gates;
    /** Last gate-REJECTED block's spectrum (dBFs), held so the debug overlay can
     *  show what tripped a gate (rejected blocks never reach the display). */
    public double[] gateRejectDbFs;
    /** Gate verdict (which gate fired + values) for that last rejected block. */
    public SpectralDiscontinuityDetector.Gates gateRejectGates;

    /** Empty constructor used by the result pool to allocate reusable
     *  slots.  All fields stay at Java defaults until the analyzer calls
     *  {@link #ensureArrays} and fills the result via direct field writes. */
    public FftResult() { }

    /** Ensures the bin / harmonic arrays are sized for the next
     *  analysis.  Reuses the existing arrays when their length
     *  already matches; otherwise reallocates (FFT-length change
     *  in prefs or a new pool slot that's never been filled).
     *  Called from {@link FftAnalyzer#analyze} before the bin
     *  arrays are written so the analyzer can use {@code re} /
     *  {@code im} / {@code amplitudeDbFs} directly as its output
     *  buffers - no per-cycle allocation. */
    public void ensureArrays(int binCount, int harmonicCount) {
        if (amplitudeDbFs == null || amplitudeDbFs.length != binCount) {
            amplitudeDbFs = new double[binCount];
            re            = new double[binCount];
            im            = new double[binCount];
        }
        ensureHarmonicArrays(harmonicCount);
    }

    /** Sizes the harmonic arrays for {@code count} harmonics, reusing them
     *  when their length already matches. */
    private void ensureHarmonicArrays(int count) {
        if (harmonicBins == null || harmonicBins.length != count) {
            harmonicBins  = new int   [count];
            harmonicHz    = new double[count];
            harmonicPct   = new double[count];
        }
    }

    /** The factor a bin's raw magnitude ({@code hypot(re, im)}) is multiplied
     *  by for its single-sided amplitude, full scale 1: {@code 2 / (fftSize ·
     *  windowCoherentGain)}.  At DC and Nyquist, where the two sides are one
     *  bin, a caller applies half of it.  0 while the result carries no
     *  window gain. */
    public double amplitudePerMagnitude() {
        return windowCoherentGain > 0.0 && fftSize > 0
                ? 2.0 / ((double) fftSize * windowCoherentGain)
                : 0.0;
    }

    /** Places a ladder of harmonics on the fundamental's fractional bin:
     *  harmonic {@code h + 2} at {@code (h + 2) · kappa} bins, its bin the
     *  nearest one, or -1 beyond the spectrum.  The arrays are sized to
     *  {@code count} first.  The levels are the statistics'
     *  ({@link org.edgo.audio.measure.common.SpectrumStatistics#calculateStat}),
     *  which read the positions written here.
     *
     * @param count the harmonics to place, counted from the 2nd
     * @param kappa the fundamental's fractional bin the ladder stands on */
    public void placeHarmonics(int count, double kappa) {
        ensureHarmonicArrays(count);
        harmonicCount = count;
        int halfSize = amplitudeDbFs.length - 1;
        for (int h = 0; h < harmonicCount; h++) {
            double hz = (h + 2) * kappa * freqResolution;
            int    hb = (int) Math.round(hz / freqResolution);
            harmonicHz[h]   = hz;
            harmonicBins[h] = (hb >= 1 && hb <= halfSize) ? hb : -1;
        }
    }

    /** Returns an independent copy of this result.  All scalar
     *  fields are copied by value; every array is cloned so a
     *  later in-place mutation of the source (e.g. cal cascade
     *  re-write on the worker thread) doesn't perturb the copy.
     *  Use when a consumer needs a stable snapshot decoupled from
     *  the live worker analysis cycle - e.g. a screenshot, a frozen
     *  comparison reference, or a paint that may outlive the next
     *  worker write. */
    public FftResult deepCopy() {
        FftResult c = new FftResult();
        c.copyFrom(this);
        return c;
    }

    /** Reuses {@code dst} for a copy of {@code src} when the lengths already
     *  match (straight arraycopy), else clones; {@code null} stays null. */
    private double[] reuseCopy(double[] dst, double[] src) {
        if (src == null) return null;
        if (dst == null || dst.length != src.length) return src.clone();
        System.arraycopy(src, 0, dst, 0, src.length);
        return dst;
    }

    private int[] reuseCopy(int[] dst, int[] src) {
        if (src == null) return null;
        if (dst == null || dst.length != src.length) return src.clone();
        System.arraycopy(src, 0, dst, 0, src.length);
        return dst;
    }

    /** Copies every field of {@code src} into THIS result, reusing this
     *  result's arrays when their lengths already match and allocating
     *  otherwise - the in-place sibling of {@link #deepCopy()} (which
     *  delegates here, so the field list lives once).  Lets a steady-state
     *  consumer - the FFT view's displayed snapshot - refresh per displayed
     *  frame without cloning ~64 MB of spectrum arrays into garbage each
     *  time at large FFT sizes. */
    public void copyFrom(FftResult src) {
        FftResult c = this;
        c.fftSize                    = src.fftSize;
        c.sampleRate                 = src.sampleRate;
        c.frameCount                 = src.frameCount;
        c.stepSamples                = src.stepSamples;
        c.freqResolution             = src.freqResolution;
        c.binBwSqrt                  = src.binBwSqrt;
        c.windowType                 = src.windowType;
        c.overlap                    = src.overlap;
        c.amplitudeDbFs              = reuseCopy(c.amplitudeDbFs, src.amplitudeDbFs);
        c.phaseDeg                   = reuseCopy(c.phaseDeg,      src.phaseDeg);
        c.re                         = reuseCopy(c.re,            src.re);
        c.im                         = reuseCopy(c.im,            src.im);
        c.hasPowerAverage            = src.hasPowerAverage;
        if (src.hasPowerAverage) {
            c.powerAverageDbFs       = reuseCopy(c.powerAverageDbFs, src.powerAverageDbFs);
        }
        c.fundamentalBin             = src.fundamentalBin;
        c.fundamentalHz              = src.fundamentalHz;
        c.fundamentalHzRefined       = src.fundamentalHzRefined;
        c.fundamental2HzRefined      = src.fundamental2HzRefined;
        c.pilot1HzRefined            = src.pilot1HzRefined;
        c.pilot2HzRefined            = src.pilot2HzRefined;
        c.pilot1Bin                  = src.pilot1Bin;
        c.pilot2Bin                  = src.pilot2Bin;
        c.pilot1MaskLoBin            = src.pilot1MaskLoBin;
        c.pilot1MaskHiBin            = src.pilot1MaskHiBin;
        c.pilot2MaskLoBin            = src.pilot2MaskLoBin;
        c.pilot2MaskHiBin            = src.pilot2MaskHiBin;
        c.referenceFundamentalHzRefined  = src.referenceFundamentalHzRefined;
        c.referenceFundamental2HzRefined = src.referenceFundamental2HzRefined;
        c.fllAveragedHz              = src.fllAveragedHz;
        c.fllAveraged2Hz             = src.fllAveraged2Hz;
        c.fundamentalDbFs            = src.fundamentalDbFs;
        c.fundamentalLinear          = src.fundamentalLinear;
        c.fundamental                = src.fundamental;   // immutable - share the reference
        c.coherentKappa              = src.coherentKappa;
        c.channelLeft                = src.channelLeft;
        c.samplesAbsStart            = src.samplesAbsStart;
        c.epoch                      = src.epoch;
        c.harmonicCount              = src.harmonicCount;
        c.harmonicBins               = reuseCopy(c.harmonicBins,  src.harmonicBins);
        c.harmonicHz                 = reuseCopy(c.harmonicHz,    src.harmonicHz);
        c.harmonics                  = src.harmonics != null ? src.harmonics.clone() : null;
        c.harmonicPct                = reuseCopy(c.harmonicPct,   src.harmonicPct);
        c.rawFundRe                  = src.rawFundRe;
        c.rawFundIm                  = src.rawFundIm;
        c.dacFundPhaseRad            = src.dacFundPhaseRad;
        c.dacFund2PhaseRad           = src.dacFund2PhaseRad;
        c.rawPeakRe                  = reuseCopy(c.rawPeakRe,     src.rawPeakRe);
        c.rawPeakIm                  = reuseCopy(c.rawPeakIm,     src.rawPeakIm);
        c.imdProductA                = reuseCopy(c.imdProductA,   src.imdProductA);
        c.imdProductB                = reuseCopy(c.imdProductB,   src.imdProductB);
        c.imdProductBin              = reuseCopy(c.imdProductBin, src.imdProductBin);
        c.imd                        = src.imd != null ? src.imd.copy() : null;
        c.thdPct                     = src.thdPct;
        c.thdDb                      = src.thdDb;
        c.thdNDb                     = src.thdNDb;
        c.snrDb                      = src.snrDb;
        c.sinadDb                    = src.sinadDb;
        c.awThdNDb                   = src.awThdNDb;
        c.enobSinadDb                = src.enobSinadDb;
        c.snrFreqMin                 = src.snrFreqMin;
        c.snrFreqMax                 = src.snrFreqMax;
        c.thdHarmonics               = src.thdHarmonics;
        c.coherentAveraging          = src.coherentAveraging;
        c.noisePower                 = src.noisePower;
        c.awNoisePower               = src.awNoisePower;
        c.windowNenbwBins            = src.windowNenbwBins;
        c.windowCoherentGain         = src.windowCoherentGain;
        c.avgNoiseFloorDbFs          = src.avgNoiseFloorDbFs;
        c.fundamentalDynExclusionHz  = src.fundamentalDynExclusionHz;
        c.fundamentalTrueDbFs        = src.fundamentalTrueDbFs;
        c.gates                      = src.gates;   // immutable snapshot - share the reference
        c.gateRejectDbFs             = src.gateRejectDbFs;
        c.gateRejectGates            = src.gateRejectGates;
    }

    /** Every line of the spectrum in the slot order of
     *  {@link FreqRespCalHelper#toneFrequencies}: the fundamental, each
     *  harmonic, then for a dual tone the second fundamental and the
     *  intermodulation products, each order's lower product before its upper.
     *
     * @return the lines by slot; {@code null} for a slot not carried or
     *         before the statistics ran */
    public SpectralLine[] toneLines() {
        SpectralLine[] lines = new SpectralLine[FreqRespCalHelper.toneCount(this)];
        lines[0] = fundamental;
        for (int h = 0; harmonics != null && h < harmonics.length && h < harmonicCount; h++) {
            lines[1 + h] = harmonics[h];
        }
        if (imd != null) {
            int slot = FreqRespCalHelper.secondToneSlot(this);
            lines[slot++] = fundamentalHzRefined <= fundamental2HzRefined ? imd.f2 : imd.f1;
            for (int k = 2; k <= ImdResult.MAX_ORDER; k++) {
                lines[slot++] = imd.dnL[k];
                lines[slot++] = imd.dnH[k];
            }
        }
        return lines;
    }

    /** The bin whose levels stand in for bin {@code k} once the pilot lines
     *  are cut out of the spectrum: {@code k} itself outside the pilots'
     *  spans ({@link #pilot1MaskLoBin} .. {@link #pilot1MaskHiBin},
     *  {@link #pilot2MaskLoBin} .. {@link #pilot2MaskHiBin}); inside one, the
     *  noise bin just outside the span, mirrored in at the nearer edge, so a
     *  cut span carries the noise beside it - its power and its texture.  A
     *  pilot is the alignment's time base, not part of the measured signal.
     *
     * @param k the bin
     * @return the bin to read for {@code k}; {@code k} itself where the mirror
     *         would leave the spectrum */
    public int pilotFreeBin(int k) {
        int lo;
        int hi;
        if (k >= pilot1MaskLoBin && k <= pilot1MaskHiBin) {
            lo = pilot1MaskLoBin;
            hi = pilot1MaskHiBin;
        } else if (k >= pilot2MaskLoBin && k <= pilot2MaskHiBin) {
            lo = pilot2MaskLoBin;
            hi = pilot2MaskHiBin;
        } else {
            return k;
        }
        int source = k - lo <= hi - k ? lo - 1 - (k - lo) : hi + 1 + (hi - k);
        return source >= 1 && source < amplitudeDbFs.length ? source : k;
    }

    /** The harmonics as the statistics measured them, H2 first; empty
     *  before the statistics ran. */
    public List<SpectralLine> harmonicLines() {
        return harmonics != null ? Arrays.asList(harmonics) : List.of();
    }

    /**
     * Stamps the RAW (pre-.frc-de-embed) fundamental + distortion-peak phasors
     * off the current {@code re}/{@code im} into {@link #rawFundRe} /
     * {@link #rawPeakRe} for the DAC-predistortion correction - call while
     * {@code re}/{@code im} still hold the raw averaged spectrum, i.e. BEFORE
     * any in-place {@code .frc} de-embed.  The peak array carries the HARMONIC
     * bins for a single tone (derived from the refined fundamental, index
     * 0 = 2nd harmonic) and the de-rotated INTERMOD-PRODUCT bins
     * ({@link #imdProductBin}) for a dual tone - the two correction paths never
     * run together.  Shared by the analyzer (each analysed frame) and the GUI
     * analyser worker (the averaged spectrum) so both feed the correction
     * identical raw phase.  Where no
     * fundamental places the peaks, none is captured: the phasors are left
     * NaN / null rather than a previous analysis's on a reused result.
     */
    public void captureRawPeaks() {
        if (re == null || im == null || freqResolution <= 0 || !(fundamentalHzRefined > 0)) {
            clearRawPeaks();
            return;
        }
        int fundBin = (int) Math.round(fundamentalHzRefined / freqResolution);
        if (fundBin <= 0 || fundBin >= re.length) {
            clearRawPeaks();
            return;
        }

        // Scale the captured phasors by the FFT amplitude factor so |phasor|
        // equals the ABSOLUTE level (dBFS-linear) at that bin, not raw re/im -
        // the dual-tone correction reads the magnitude straight off them.  The
        // factor (= |amplitude_linear| / |re,im|) is constant across the spectrum
        // and cancels the notch, so deriving it from the fundamental bin is
        // exact; phases are untouched (the single-tone path reads only arg).
        double fundMag = Math.hypot(re[fundBin], im[fundBin]);
        double conv = (amplitudeDbFs != null && fundMag > 0.0)
                ? Math.pow(10.0, amplitudeDbFs[fundBin] / 20.0) / fundMag : 1.0;
        rawFundRe = re[fundBin] * conv;
        rawFundIm = im[fundBin] * conv;

        boolean dualTone = imdProductBin != null && imdProductBin.length > 0;
        int[] peakBins;
        if (dualTone) {
            peakBins = imdProductBin;
        } else {
            int n = Math.max(0, harmonicCount);   // harmonic table size set by analyze()
            peakBins = new int[n];
            for (int i = 0; i < n; i++) {
                peakBins[i] = (int) Math.round((i + 2) * fundamentalHzRefined / freqResolution);
            }
        }
        rawPeakRe = new double[peakBins.length];
        rawPeakIm = new double[peakBins.length];
        for (int i = 0; i < peakBins.length; i++) {
            int b = peakBins[i];
            if (b > 0 && b < re.length) {
                rawPeakRe[i] = re[b] * conv;
                rawPeakIm[i] = im[b] * conv;
            }
        }
    }

    /** No raw phasors captured: the fundamental's NaN, the peaks null, the
     *  state the correction reads as "fall back to the live spectrum". */
    private void clearRawPeaks() {
        rawFundRe = Double.NaN;
        rawFundIm = Double.NaN;
        rawPeakRe = null;
        rawPeakIm = null;
    }
}
