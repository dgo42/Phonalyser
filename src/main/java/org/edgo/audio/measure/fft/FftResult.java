/*
 * Phonalyser — precision audio measurement workbench.
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

import org.edgo.audio.measure.dsp.SpectralDiscontinuityDetector;
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
    /** √(bin bandwidth in Hz) the spectrum was captured with — non-null only
     *  for results reconstructed from a saved file ({@code # bin_bw_hz=}
     *  header); {@code null} for live results, where the V/√Hz conversion
     *  uses the live-config cache in {@code Preferences} instead. */
    public Double binBwSqrt;
    /** Window function used. */
    public WindowType windowType;
    /** FftOverlap used. */
    public FftOverlap overlap;

    // Single-sided spectrum, bins 0 … fftSize/2.  Arrays are reused
    // across analysis ticks via the pool; {@link #ensureArrays} grows
    // them when the FFT length changes.
    public double[] amplitudeDbFs;
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

    /** Tracked mains fundamental (Hz) for the FFT pane's frequency-domain mains
     *  rejection, or {@link Double#NaN} when mains suppression is off / unlocked.
     *  The worker only TRACKS it; the actual notch correction is applied on the
     *  displayed frame in {@code FftView} (so it runs once per painted frame, not
     *  per averaging tick), using a cached per-bin response keyed on this value. */
    public double mainsF0Hz = Double.NaN;
    /** Pinned coherent fundamental bin (κ) for the plot-time .frc "before-cal"
     *  re-derive, or {@link Double#NaN} for a single (non-averaged) tick.  The
     *  worker owns the accumulator and sets this; the UI's post-average pipeline
     *  consumes it (non-NaN ⇒ averaging). */
    public double coherentKappa = Double.NaN;
    /** Channel this result was analyzed for ({@code true} = left) — picks the
     *  left/right .frc calibration in the UI's post-average pipeline. */
    public boolean channelLeft = true;
    /** Absolute sample index of this result's analysis-window start (the
     *  worker's {@code samplesAbsStart}).  Lets the frequency-lock loop compute
     *  the REAL elapsed time between corrections — {@code (Δstart)/sampleRate} —
     *  so its gain is time-correct under wildly varying tick durations. */
    public long samplesAbsStart;
    /** Live capture {@code writePos} when this result was produced — where a
     *  correction issued in response first lands in the capture stream.  Lets the
     *  frequency-lock loop place the correction's change-point past the
     *  analysis/publish backlog ({@code writePos − samplesAbsStart − fftSize}). */
    public long writePos;

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
     * User-supplied true fundamental level in dBFS — the manual fundamental
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
     * It affects ONLY the fundamental — every other bin (harmonics, noise
     * floor, the spectrum trace) keeps the global dBFS→dBV offset.
     * {@link Double#NaN} when no manual override is supplied.
     */
    public double fundamentalTrueDbFs;

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
        c.binBwSqrt                  = binBwSqrt;
        c.windowType                 = windowType;
        c.overlap                    = overlap;
        c.amplitudeDbFs              = amplitudeDbFs != null ? amplitudeDbFs.clone() : null;
        c.phaseDeg                   = phaseDeg      != null ? phaseDeg.clone()      : null;
        c.re                         = re            != null ? re.clone()            : null;
        c.im                         = im            != null ? im.clone()            : null;
        c.fundamentalBin             = fundamentalBin;
        c.fundamentalHz              = fundamentalHz;
        c.fundamentalHzRefined       = fundamentalHzRefined;
        c.fundamental2HzRefined      = fundamental2HzRefined;
        c.fundamentalDbFs            = fundamentalDbFs;
        c.fundamentalLinear          = fundamentalLinear;
        c.mainsF0Hz                  = mainsF0Hz;
        c.coherentKappa              = coherentKappa;
        c.channelLeft                = channelLeft;
        c.samplesAbsStart            = samplesAbsStart;
        c.writePos                   = writePos;
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
        c.fundamentalTrueDbFs        = fundamentalTrueDbFs;
        c.rejectedFrames             = rejectedFrames;
        c.rejectionTotalFrames       = rejectionTotalFrames;
        c.rejectionPhaseCoherence    = rejectionPhaseCoherence;
        c.rejectionDetail            = rejectionDetail;
        c.gates                      = gates;   // immutable snapshot — share the reference
        c.gateBlockDbFs              = gateBlockDbFs;    // debug snapshots — share (not mutated)
        c.gateRejectDbFs             = gateRejectDbFs;
        c.gateRejectGates            = gateRejectGates;
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
}
