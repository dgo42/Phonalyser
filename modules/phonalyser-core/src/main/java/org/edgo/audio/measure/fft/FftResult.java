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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

    /** Width (bins) of each flank {@link #localNoiseFloorDbFs()} samples just
     *  beyond the fundamental's skirt - the "near range".  Wide enough to clear a
     *  broad skirt (which can run ±32-50 bins) and give a stable median: a true
     *  tone has dropped to the floor here, a broadband hump has not. */
    private static final int LOCAL_FLOOR_FLANK_BINS = 64;

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
    public double[] phaseDeg;
    public double[] re;
    public double[] im;

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
     *  window's own main-lobe half-width on either side, clamped to the
     *  spectrum.  The span is empty whenever {@link #pilot1MaskHiBin} is
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
    public double fundamentalDbFs;
    public double fundamentalLinear;

    /** Tracked mains fundamental (Hz) for the FFT pane's frequency-domain mains
     *  rejection, or {@link Double#NaN} when mains suppression is off / unlocked.
     *  The worker only TRACKS it; the actual notch correction is applied on the
     *  displayed frame in {@code FftView} (so it runs once per painted frame, not
     *  per averaging cycle), using a cached per-bin response keyed on this value. */
    public double mainsF0Hz = Double.NaN;
    /** Pinned coherent fundamental bin (κ) for the plot-time .frc "before-cal"
     *  re-derive, or {@link Double#NaN} for a single (non-averaged) analysis
     *  cycle.  The worker owns the accumulator and sets this; the UI's
     *  post-average pipeline consumes it (non-NaN => averaging). */
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

    // Harmonics (index 0 = 2nd harmonic, ...)
    public int      harmonicCount;
    public int[]    harmonicBins;
    public double[] harmonicHz;
    public double[] harmonicDbFs;
    public double[] harmonicPct;

    /** The local noise floor under each distortion line - every harmonic and
     *  every intermodulation product - by the line's bin: the level, dBFS in
     *  the domain {@link #amplitudeDbFs} stands in when the statistics are
     *  derived, below which the line's reading is not taken.  A line under
     *  its floor carries no stable level: what its bin holds is the noise
     *  there, wandering across the floor's band from one collection to the
     *  next, and summed into THD it would move THD and everything steered by
     *  it.  So the reading is held at the floor from below and is free to
     *  rise above it.  Index-parallel; a bin of -1 holds no floor.  Both
     *  {@code null} when no floor was measured, which clamps nothing. */
    public int[]    lineFloorBins;
    public double[] lineFloorDbFs;

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
     *  written wherever the analyser derives this result's statistics, from
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
     *  each still subject to the distortion window above.  Set by the caller
     *  the same way the window is - the analysis input, and what the readout
     *  labels the sum with; 0 on a result never told, which the analyser
     *  treats as its historic H2..H9. */
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
    /** Pre-correction (BLUE-dot) snapshot of fundamental + harmonic
     *  (freq, dBFS) pairs, paired with this Result so they publish
     *  atomically together via {@code lastResult = r}.  Lives in
     *  Result rather than as a separate volatile field on the worker
     *  to avoid a tearing race where the view reads "new pre / old
     *  r" and renders BLUE based on a future analysis cycle's
     *  cumulative data while RED still reflects the current cycle -
     *  visible as the BLUE dot jumping every few cycles even when the
     *  cal lookup itself is bit-stable.
     *  Layout: {@code [0] = freqs[], [1] = dBFs[]} with index 0
     *  holding the fundamental and indices 1..harmonicCount holding
     *  H2..HN.  {@code null} when no calibration is loaded. */
    public double[][] preCorrectionPeaks;
    /** Pre-correction (BLUE-dot) snapshot of the dual-tone lines, paired with
     *  this Result for the same reason as {@link #preCorrectionPeaks}.
     *  Layout: {@code [0] = freqs[], [1] = dBFs[]} holding F1, F2, then the
     *  lower and the upper product of each order 2..{@link ImdResult#MAX_ORDER}.
     *  {@code null} when no calibration is loaded or the frame carries no
     *  intermodulation readout. */
    public double[][] imdPreCorrectionPeaks;
    /**
     * Half-width of the dynamic fundamental exclusion zone in Hz (one side).
     * For a clean recording this is a few Hz; a signal interruption causes
     * spectral splatter that broadens this to hundreds or thousands of Hz.
     */
    public double fundamentalDynExclusionHz;
    /**
     * User-supplied true fundamental level in dBFS - the manual fundamental
     * (from {@code --fund-v} / {@code --fund-dbv} or the FFT-tab manual-fund
     * field), converted from the stated dBV to dBFS once at the input boundary
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
    /** Current accepted block's pre-average spectrum (dBFs) for the discontinuity
     *  debug overlay; {@code null} unless the gate debug is on. */
    public double[] gateBlockDbFs;
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
     *  {@code im} / {@code amplitudeDbFs} / {@code phaseDeg}
     *  directly as its output buffers - no per-cycle allocation. */
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

    /** Places the harmonic ladder on the refined fundamental: harmonic
     *  {@code h + 2} at {@code (h + 2) · fundamentalHzRefined}, its bin the
     *  nearest one, or -1 beyond the spectrum.  The levels are the
     *  statistics' ({@link FftAnalyzer#recomputeStats}), which read the
     *  positions written here. */
    public void placeHarmonics() {
        int halfSize = amplitudeDbFs.length - 1;
        for (int h = 0; h < harmonicCount; h++) {
            double hz = (h + 2) * fundamentalHzRefined;
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
        c.mainsF0Hz                  = src.mainsF0Hz;
        c.coherentKappa              = src.coherentKappa;
        c.channelLeft                = src.channelLeft;
        c.samplesAbsStart            = src.samplesAbsStart;
        c.epoch                      = src.epoch;
        c.harmonicCount              = src.harmonicCount;
        c.harmonicBins               = reuseCopy(c.harmonicBins,  src.harmonicBins);
        c.harmonicHz                 = reuseCopy(c.harmonicHz,    src.harmonicHz);
        c.harmonicDbFs               = reuseCopy(c.harmonicDbFs,  src.harmonicDbFs);
        c.harmonicPct                = reuseCopy(c.harmonicPct,   src.harmonicPct);
        c.lineFloorBins              = reuseCopy(c.lineFloorBins, src.lineFloorBins);
        c.lineFloorDbFs              = reuseCopy(c.lineFloorDbFs, src.lineFloorDbFs);
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
        c.gateBlockDbFs              = src.gateBlockDbFs;    // debug snapshots - share (not mutated)
        c.gateRejectDbFs             = src.gateRejectDbFs;
        c.gateRejectGates            = src.gateRejectGates;
        c.preCorrectionPeaks         = copyRows(src.preCorrectionPeaks);
        c.imdPreCorrectionPeaks      = copyRows(src.imdPreCorrectionPeaks);
    }

    /** A copy of a 2-D peak list: the outer array AND each non-null row
     *  cloned, so the copy can be mutated independently of the source (rows
     *  are tiny peak lists - no reuse needed); {@code null} stays null. */
    private double[][] copyRows(double[][] rows) {
        if (rows == null) return null;
        double[][] dst = new double[rows.length][];
        for (int i = 0; i < rows.length; i++) {
            dst[i] = rows[i] != null ? rows[i].clone() : null;
        }
        return dst;
    }

    /**
     * Noise floor as the spectrum actually SHOWS it (dBFS): the high-percentile
     * "top of the grass" with the fundamental, the harmonics and their skirts
     * removed - the practical level a harmonic must clear to be measurable,
     * well above the RMS {@link #avgNoiseFloorDbFs}.  Decays as the coherent
     * average deepens.  {@link Double#NaN} when no spectrum is present.
     */
    public double noisePeakFloorDbFs() {
        if (amplitudeDbFs == null || !(freqResolution > 0)) return Double.NaN;
        int n = amplitudeDbFs.length;
        boolean[] excl = new boolean[n];
        int fundHalf = (int) Math.max(1, Math.ceil(fundamentalDynExclusionHz / freqResolution));
        markSignalBins(excl, fundamentalBin, fundHalf);            // fundamental + its skirt
        int harmHalf = Math.max(3, fundHalf / 8);
        if (harmonicBins != null) {
            for (int i = 0; i < harmonicCount && i < harmonicBins.length; i++) {
                markSignalBins(excl, harmonicBins[i], harmHalf);   // each harmonic + its skirt
            }
        }
        double[] noise = new double[n];
        int cnt = 0;
        for (int b = 1; b < n; b++) {
            if (!excl[b] && Double.isFinite(amplitudeDbFs[b])) noise[cnt++] = amplitudeDbFs[b];
        }
        if (cnt == 0) return Double.NaN;
        Arrays.sort(noise, 0, cnt);
        int idx = (int) Math.min(cnt - 1, Math.round(0.999 * (cnt - 1)));   // grass peaks
        return noise[idx];
    }

    /**
     * Robust noise floor (dBFS) in the NEAR RANGE of the fundamental - the median
     * of two {@link #LOCAL_FLOOR_FLANK_BINS}-wide flanks placed immediately beyond
     * the fundamental's dynamic skirt.  This is a SHARPNESS probe, not a wide-band
     * floor: a genuine tone has dropped to the noise floor a few lobes out (so it
     * towers over this), whereas the "peak" of a broadband hump (speech) or a
     * noise bin is still surrounded by near-equal grass here.  {@link Double#NaN}
     * when the flanks hold no usable bins.
     */
    public double localNoiseFloorDbFs() {
        if (amplitudeDbFs == null || !(freqResolution > 0) || fundamentalBin <= 0) return Double.NaN;
        int n = amplitudeDbFs.length;
        int skirt = (int) Math.max(1, Math.ceil(fundamentalDynExclusionHz / freqResolution));
        double[] band = new double[2 * LOCAL_FLOOR_FLANK_BINS];
        int cnt = 0;
        for (int b = fundamentalBin - skirt - LOCAL_FLOOR_FLANK_BINS; b < fundamentalBin - skirt; b++) {
            if (b >= 1 && b < n && Double.isFinite(amplitudeDbFs[b])) band[cnt++] = amplitudeDbFs[b];
        }
        for (int b = fundamentalBin + skirt + 1; b <= fundamentalBin + skirt + LOCAL_FLOOR_FLANK_BINS; b++) {
            if (b >= 1 && b < n && Double.isFinite(amplitudeDbFs[b])) band[cnt++] = amplitudeDbFs[b];
        }
        if (cnt == 0) return Double.NaN;
        Arrays.sort(band, 0, cnt);
        return band[cnt / 2];                                          // median = local grass level
    }

    /** The local noise floor a distortion line at {@code bin} is held at from
     *  below ({@link #lineFloorDbFs}), dBFS; {@link Double#NaN} where none was
     *  measured. */
    public double lineFloorDbFs(int bin) {
        if (lineFloorBins == null || lineFloorDbFs == null || bin < 1) return Double.NaN;
        for (int i = 0; i < lineFloorBins.length; i++) {
            if (lineFloorBins[i] == bin) return lineFloorDbFs[i];
        }
        return Double.NaN;
    }

    /** {@code linear}, a line's amplitude at {@code bin} (full scale 1), held
     *  at the line's local noise floor from below
     *  ({@link #lineFloorDbFs(int)}); unchanged where no floor was measured
     *  or the reading is not finite. */
    public double atLeastLineFloor(int bin, double linear) {
        double floorDbFs = lineFloorDbFs(bin);
        if (!Double.isFinite(floorDbFs) || !Double.isFinite(linear)) return linear;
        return Math.max(linear, Math.pow(10.0, floorDbFs / 20.0));
    }

    /** One distortion line as the readouts carry it: the bin it sits on and
     *  its readout in dBFS, held at the line's floor from below. */
    public record DistortionLine(int bin, double readoutDbFs) {}

    /** The harmonics as distortion lines, H2 first; empty without
     *  harmonics. */
    public List<DistortionLine> harmonicLines() {
        List<DistortionLine> lines = new ArrayList<>();
        if (harmonicBins == null || harmonicDbFs == null) return lines;
        for (int h = 0; h < harmonicCount; h++) {
            lines.add(new DistortionLine(harmonicBins[h], harmonicDbFs[h]));
        }
        return lines;
    }

    /** The intermodulation products as distortion lines - both sidebands of
     *  every order from 2 to {@link ImdResult#MAX_ORDER}, the lower first, the
     *  lines the combined intermod figure sums; empty for a single tone or a
     *  spectrum whose bin width is unknown.  A product outside the measurable
     *  range carries a {@link Double#NaN} readout. */
    public List<DistortionLine> intermodLines() {
        List<DistortionLine> lines = new ArrayList<>();
        if (imd == null || !(freqResolution > 0.0)) return lines;
        for (int k = 2; k <= ImdResult.MAX_ORDER; k++) {
            lines.add(new DistortionLine((int) Math.round(imd.dnLHz[k] / freqResolution), imd.dnLDbFs[k]));
            lines.add(new DistortionLine((int) Math.round(imd.dnHHz[k] / freqResolution), imd.dnHDbFs[k]));
        }
        return lines;
    }

    /**
     * Harmonic {@code i}'s level (dBFS) as ORIGINALLY measured - before any
     * {@code .frc} de-embedding.  When a cal is loaded the displayed
     * {@link #harmonicDbFs} is lifted by the cal, but {@link #preCorrectionPeaks}
     * retains the raw pre-correction levels; with no cal, {@link #harmonicDbFs}
     * is already raw.  Index 0 = H2.  {@link Double#NaN} when unavailable.
     *
     * <p>This is the honest measurability reference: the cal can boost a buried
     * harmonic above the displayed floor, but its raw level is what the ADC
     * actually captured relative to the (raw) noise grass.
     */
    public double rawHarmonicDbFs(int i) {
        if (preCorrectionPeaks != null && preCorrectionPeaks.length > 1
                && preCorrectionPeaks[1] != null && 1 + i < preCorrectionPeaks[1].length) {
            return preCorrectionPeaks[1][1 + i];   // [1][0] = fundamental, [1][1+i] = harmonic i
        }
        return (harmonicDbFs != null && i < harmonicDbFs.length) ? harmonicDbFs[i] : Double.NaN;
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
     * run together.  Shared by the GUI analyser worker and the CLI iterative
     * compensator so both feed the correction identical raw phase.  Where no
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

    /** Marks {@code center ± half} bins as signal (excluded from the noise set). */
    private void markSignalBins(boolean[] excl, int center, int half) {
        if (center <= 0) return;
        int lo = Math.max(0, center - half);
        int hi = Math.min(excl.length - 1, center + half);
        for (int b = lo; b <= hi; b++) excl[b] = true;
    }
}
