package org.edgo.audio.measure.cli.util;

/**
 * In-memory representation of a filter calibration: a sparse set of measured
 * magnitude/phase points at log-spaced frequencies, produced by the stepped-sine
 * sweep in the filter-sweep mode.
 *
 * <p>All three arrays are the same length N (the number of sweep points) and
 * {@code freqs} is strictly ascending.  {@code magLin[i]} is the **relative**
 * filter magnitude |H(freqs[i])| — i.e. the captured peak amplitude divided
 * by the DAC drive peak fraction, so the pass-band sits at unity (≈ 1.0)
 * and a notch reads e.g. 1e-4 (−80 dB).  {@code phaseRad[i]} is the filter
 * phase in radians.
 *
 * <p>Callers wanting H at an arbitrary frequency interpolate in log-frequency
 * (dB magnitude, linear phase) between the surrounding two sweep points.
 */
public class FreqRespCalibration {
    public final double[] freqs;       // length N, strictly ascending Hz
    public final double[] magLin;      // |H| linear, passband normalised to 1.0
    public final double[] phaseRad;    // phase in radians, unwrapped
    /** ADC full-scale RMS voltage at the time of measurement, from the CSV header
     *  ({@code adc_fs_voltage_rms}); {@link Double#NaN} when absent. */
    public double         adcFsVoltageRms = Double.NaN;

    public FreqRespCalibration(double[] freqs, double[] magLin, double[] phaseRad) {
        this.freqs    = freqs;
        this.magLin   = magLin;
        this.phaseRad = phaseRad;
    }
}
