package org.edgo.audio.measure.gui.freqresp;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.edgo.audio.measure.enums.Channel;

/**
 * Immutable snapshot of a single Frequency Response measurement.  One instance
 * carries the result for one capture channel; the {@link FreqRespView} can hold
 * one slot for {@link Channel#L} and one for {@link Channel#R} so both traces
 * can be drawn simultaneously when the user enables both channels.
 *
 * <p>Files loaded from disk arrive with {@link #isCalibrationApplied()}
 * {@code = true} so the GUI's calibration pipeline doesn't divide them by the
 * loaded calibration a second time — the result on disk was already corrected
 * before being saved.  Fresh measurements set the flag to {@code true} when
 * the analyzer divided by the active calibration, {@code false} otherwise.
 */
@Getter
@RequiredArgsConstructor
public final class FreqRespResult {

    /** Which capture channel this measurement came from. */
    private final Channel channel;

    /** Sample rate at which the sweep was captured (Hz). */
    private final int sampleRate;

    /** Log-spaced frequency points where the response was evaluated, in Hz,
     *  strictly ascending.  All three arrays ({@link #freqs}, {@link #magLin},
     *  {@link #phaseRad}) share this length. */
    private final double[] freqs;

    /** Linear magnitude at each frequency point; the passband sits at ≈ 1.0
     *  (i.e. the DAC drive level has already been divided out).  Notches /
     *  cuts read as small fractions, e.g. 1e-4 ≡ −80 dB. */
    private final double[] magLin;

    /** Wrapped phase at each frequency point, in radians, in [−π, +π]. */
    private final double[] phaseRad;

    /** Sweep parameters that produced this result, captured at measurement
     *  time so a save / reload cycle preserves enough metadata to interpret
     *  the curve.  Never {@code null} for fresh measurements; may be a
     *  best-effort reconstruction for files loaded from disk. */
    private final FreqRespSweepParams sweepParams;

    /** Absolute filesystem path the result was loaded from, or {@code null}
     *  for results produced by a fresh measurement (not yet saved). */
    private final String sourceFilePath;

    /** {@code true} when calibration has already been applied to
     *  {@link #magLin} / {@link #phaseRad}.  Files loaded from disk carry
     *  this flag in their CSV header; fresh measurements set it according
     *  to whether the analyzer divided by the active calibration. */
    private final boolean calibrationApplied;
}
