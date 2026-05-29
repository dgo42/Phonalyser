package org.edgo.audio.measure.fft;

import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.WindowType;

/**
 * Container for all FFT analysis outputs.  A standalone top-level class so
 * each {@code FftResult} instance is fully independent of the
 * {@link FftAnalyzer} instance that produced it — important for the FFT
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

    // Frame-rejection diagnostics — rejections discard frames and slow the
    // averaging convergence.  Structured (not a pre-formatted string) so the
    // GUI layer can localise the warning banner / tooltip.  rejectedFrames
    // == 0 means the capture was clean this tick.
    /** Number of frames rejected this tick; {@code 0} = clean. */
    public int     rejectedFrames;
    /** Total frames examined this tick (rejection-ratio denominator). */
    public int     rejectionTotalFrames;
    /** {@code true} = phase-coherence rejection (sub-sample drop);
     *  {@code false} = R-invariant rejection. */
    public boolean rejectionPhaseCoherence;
    /** Detail value: max {@code |R−A|/A} in % (R-invariant) or max phase
     *  deviation in degrees (phase-coherence). */
    public double  rejectionDetail;

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
    public FftResult deepCopy() {
        FftResult c = new FftResult();
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
        c.rejectedFrames             = rejectedFrames;
        c.rejectionTotalFrames       = rejectionTotalFrames;
        c.rejectionPhaseCoherence    = rejectionPhaseCoherence;
        c.rejectionDetail            = rejectionDetail;
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

    FftResult(int fftSize, int sampleRate, int frameCount, double freqResolution,
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
