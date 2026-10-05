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

package org.edgo.audio.measure.dsp;

import java.util.Random;

import org.edgo.audio.measure.generator.SignalGenerator;
import org.jtransforms.fft.DoubleFFT_1D;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deconvolution's transport-delay estimate on a sweep that excites only
 * part of the converter's range.
 *
 * <p><b>What the delay is.</b>  Between the first played sample and the first
 * captured one lie the DAC's and the ADC's buffers and the analog path, so the
 * recording is the stimulus delayed by some tens of samples.  That delay is a
 * straight line in the phase of {@code Y/X} ({@code d} samples are
 * {@code -2*pi*f*d/fs} radians), and {@code computeFromLogSweep} measures it as
 * the position of the deconvolved impulse and turns it back out - otherwise
 * every measurement would read the cable's length instead of the filter's own
 * phase.
 *
 * <p><b>What goes wrong on a partial sweep.</b>  Above the sweep's stop
 * frequency nothing is excited while the converter goes on delivering its own
 * noise, so {@code H = Y/X} in those bins is noise over nearly nothing - orders
 * of magnitude above the real response.  An impulse built from every bin is
 * then owned by that garbage: its peak lands wherever the noise happened to add
 * up, and EVERY bin of the answer is de-rotated by that random delay.  Two
 * things follow, and both are asserted below: the delay of a plain delayed
 * recording has to come back right from a grid covering a fifth of the swept
 * band, and a measurement has to be indifferent to noise that lies outside the
 * band it swept - the same sweep deconvolved with and without the converter's
 * noise floor has to give the same answer.
 *
 * <p><b>What a narrow band cannot pin, and why.</b>  Over a narrow band a pure
 * transport delay and the measured filter's OWN group delay are the same thing:
 * the modelled notch turns its phase by about 176 degrees across 800 to
 * 1200 Hz, some 59 samples of apparent time shift at 48 kHz, and no estimator
 * reading the position of a band-limited impulse can tell those apart.  Even
 * across the whole converter that filter is worth a quarter of a sample.  So
 * the delay itself is asserted against a FLAT path, where it is the only time
 * shift there is, while with the notch in place the phase is compared to the
 * model after the band's straight line is taken out - which is how a notch
 * measurement reads it anyway.  The MAGNITUDE carries no delay at all and is
 * compared absolutely everywhere.
 *
 * <p><b>The model.</b>  No device: the stimulus is the rendered log sweep, and
 * the recording is that same sweep put through a known fractional delay, in
 * some cases a known notch, and white noise at the level a converter's own
 * floor sits at - all in the frequency domain, so the expectation is exact.
 * The notch is shaped after a simulated twin-T of the kind this measurement is
 * built for: a null about 85 dB deep at 1 kHz whose phase runs through 168
 * degrees inside 8 Hz, so that adjacent grid points differ by several degrees
 * and nothing but a correct de-rotation brings the shape back.
 */
class FreqRespCalDelayBandTest {

    private static final int    RATE          = 48_000;
    /** A power of two, so the deconvolution's transform is exactly one sweep
     *  and {@code Y/X} is the model itself rather than the model plus the
     *  error of a zero-padded, non-circular division. */
    private static final int    SWEEP_SAMPLES = 16_384;
    /** The modelled transport delay, deliberately not a whole sample: the
     *  sub-sample fit is part of what is under test. */
    private static final double DELAY_SAMPLES = 37.4;
    private static final double NOTCH_HZ      = 1000.0;
    /** The zero pair's Q - how sharply the null closes, and with it how steep
     *  the phase is across it.  1220 puts 168 degrees between 994.2 and
     *  1002.0 Hz, which is the slope a twin-T notch of the kind this
     *  measurement exists for really has. */
    private static final double NOTCH_ZERO_Q  = 1220.0;
    /** The pole pair's Q - the skirt width, and with the zero's Q the depth of
     *  the null: {@code 20*log10(POLE_Q / ZERO_Q)} = -85 dB, a real twin-T's
     *  null rather than a mathematical zero. */
    private static final double NOTCH_POLE_Q  = 0.0686;
    /** The converter's own noise, at -100 dBFS.  It is what makes the
     *  unexcited bins garbage instead of a clean model, which is the whole
     *  point of the partial-sweep cases; inside the swept band it is 100 dB
     *  under the stimulus and moves no assertion below. */
    private static final double NOISE_STD     = 1.0e-5;
    private static final long   SEED          = 20_260_915L;
    /** Drive and ADC full scale are equal, so the returned magnitude is the
     *  modelled |H| itself with no scaling in between. */
    private static final double AMP_VRMS      = 1.0;
    private static final double ADC_FS_VRMS   = 1.0;
    private static final double DAC_FS_VRMS   = 1.0;

    /** The partial sweep: 200 Hz to 2 kHz leaves more than 90 percent of the
     *  converter's range unexcited - the bench condition. */
    private static final double NARROW_START  = 200.0;
    private static final double NARROW_STOP   = 2_000.0;
    /** The grid the notch is read on inside that sweep. */
    private static final double GRID_LO       = 800.0;
    private static final double GRID_HI       = 1_200.0;
    /** The sweep that reaches nearly half the sample rate - the case that was
     *  always right and has to keep reading exactly as it did when the
     *  estimate was taken over the whole transform. */
    private static final double WIDE_START    = 20.0;
    private static final double WIDE_STOP     = 23_000.0;

    private static final double DELAY_TOL_SAMPLES = 0.1;
    private static final double PHASE_TOL_DEG     = 1.0;
    private static final double MAG_TOL_DB        = 0.1;
    /** Grid points where the model is deeper than this are left out of the
     *  comparison: at the very bottom of a null the response is zero, its
     *  level has no dB and its phase turns over inside one bin. */
    private static final double NULL_SKIP_DB      = -60.0;
    /** Points at each band edge whose mean anchors the de-trend line, as in
     *  the phase probe that consumes this measurement. */
    private static final int    EDGE_POINTS       = 5;

    // ── The delay itself, against a flat path ──────────────────────────────

    @Test
    void theDelayComesBackFromAGridInsideAPartialSweep() {
        // A fifth of the swept band, with more than 90 percent of the
        // converter unexcited and carrying nothing but noise: the delay has
        // to be the modelled one all the same.
        assertFlatPath(NARROW_START, NARROW_STOP, GRID_LO, GRID_HI, "grid inside the sweep");
    }

    @Test
    void theDelayComesBackFromThePartialSweepsOwnBand() {
        assertFlatPath(NARROW_START, NARROW_STOP, NARROW_START, NARROW_STOP,
                "the sweep's own band");
    }

    @Test
    void theDelayComesBackFromASweepOverNearlyTheWholeConverter() {
        // Nearly every bin excited: the reading that was always correct, and
        // that band-limiting the estimate must not have changed.
        FreqRespCalibration cal = deconvolve(WIDE_START, WIDE_STOP,
                WIDE_START, WIDE_STOP, false, true);

        assertEquals(0.0, leftoverDelaySamples(cal, false), DELAY_TOL_SAMPLES,
                "the removed delay must be the modelled one to a tenth of a sample");
        // A tenth of a sample still IS 17 degrees at 23 kHz, so over a band
        // this wide the phase is read the way a wide measurement reads it -
        // with that residual ramp taken out.  What is asserted is that no
        // SHAPE is left in it: a flat path came back flat.
        double[] deTrended = deTrended(cal, false);
        for (int i = 0; i < cal.freqs.length; i++) {
            assertEquals(0.0, dbOf(cal.magLin[i]), MAG_TOL_DB,
                    "level at " + cal.freqs[i] + " Hz (wide sweep)");
            assertEquals(0.0, Math.toDegrees(deTrended[i]), PHASE_TOL_DEG,
                    "phase shape at " + cal.freqs[i] + " Hz (wide sweep)");
        }
    }

    // ── The notch, on a partial sweep ──────────────────────────────────────

    @Test
    void aGridInsideAPartialSweepGivesTheNotchBack() {
        assertNotchIsBack(GRID_LO, GRID_HI, "grid inside the sweep");
    }

    @Test
    void thePartialSweepsOwnBandGivesTheNotchBack() {
        assertNotchIsBack(NARROW_START, NARROW_STOP, "the sweep's own band");
    }

    /**
     * A recording that is nothing but the stimulus delayed: the delay is the
     * only time shift in it, so the deconvolution owes the modelled one back,
     * a flat 0 dB and a phase of zero at every grid point.
     */
    private void assertFlatPath(double sweepLo, double sweepHi,
                                double gridLo, double gridHi, String where) {
        FreqRespCalibration cal = deconvolve(sweepLo, sweepHi, gridLo, gridHi, false, true);

        assertTrue(cal.freqs.length > 2 * EDGE_POINTS,
                "the grid needs points to check (" + where + ")");
        assertEquals(0.0, leftoverDelaySamples(cal, false), DELAY_TOL_SAMPLES,
                "the removed delay must be the modelled one to a tenth of a sample ("
                        + where + ")");
        double[] residuals = residualsRad(cal, false);
        for (int i = 0; i < cal.freqs.length; i++) {
            assertEquals(0.0, dbOf(cal.magLin[i]), MAG_TOL_DB,
                    "level at " + cal.freqs[i] + " Hz (" + where + ")");
            assertEquals(0.0, Math.toDegrees(residuals[i]), PHASE_TOL_DEG,
                    "phase at " + cal.freqs[i] + " Hz (" + where + ")");
        }
    }

    /**
     * One notch case: the same 200 Hz to 2 kHz sweep deconvolved with and
     * without the converter's noise floor.  The two answers have to be the
     * same measurement - that is the property a delay estimate confined to the
     * excited band has and a whole-transform one does not - and the magnitude
     * and the de-trended phase have to be the model's.
     */
    private void assertNotchIsBack(double gridLo, double gridHi, String where) {
        FreqRespCalibration noisy = deconvolve(NARROW_START, NARROW_STOP,
                gridLo, gridHi, true, true);
        FreqRespCalibration quiet = deconvolve(NARROW_START, NARROW_STOP,
                gridLo, gridHi, true, false);

        assertTrue(noisy.freqs.length > 2 * EDGE_POINTS,
                "the grid needs points to check (" + where + ")");
        double[] deTrended = deTrended(noisy, true);
        for (int i = 0; i < noisy.freqs.length; i++) {
            if (modelDb(noisy.freqs[i]) < NULL_SKIP_DB) {
                continue;
            }
            assertEquals(0.0,
                    Math.toDegrees(Math.IEEEremainder(noisy.phaseRad[i] - quiet.phaseRad[i],
                            2.0 * Math.PI)),
                    PHASE_TOL_DEG,
                    "noise outside the swept band moved the phase at " + noisy.freqs[i]
                            + " Hz (" + where + ")");
            assertEquals(dbOf(quiet.magLin[i]), dbOf(noisy.magLin[i]), MAG_TOL_DB,
                    "noise outside the swept band moved the level at " + noisy.freqs[i]
                            + " Hz (" + where + ")");
            assertEquals(modelDb(noisy.freqs[i]), dbOf(noisy.magLin[i]), MAG_TOL_DB,
                    "magnitude at " + noisy.freqs[i] + " Hz (" + where + ")");
            assertEquals(0.0, Math.toDegrees(deTrended[i]), PHASE_TOL_DEG,
                    "phase at " + noisy.freqs[i] + " Hz (" + where + ")");
        }
    }

    /** One deconvolution of the modelled recording, on a bin-aligned grid over
     *  {@code [gridLo, gridHi]}.  Fade 0: the model is circular over exactly
     *  one sweep, so {@code Y/X} is the model itself and nothing has to be
     *  shaped at the seam. */
    private FreqRespCalibration deconvolve(double sweepLo, double sweepHi,
                                           double gridLo, double gridHi,
                                           boolean withNotch, boolean withNoise) {
        double[] reference = new SignalGenerator(sweepLo, sweepHi, SWEEP_SAMPLES, 0,
                RATE, AMP_VRMS, AMP_VRMS, DAC_FS_VRMS, DAC_FS_VRMS).getLogSweepBuffer();
        double[] recording = model(reference, withNotch, withNoise);
        double[] freqs = FreqRespCalHelper.binAlignedFreqs(gridLo, gridHi,
                RATE / (double) SWEEP_SAMPLES, Integer.MAX_VALUE);
        return FreqRespCalHelper.computeFromLogSweep(
                recording, reference, 0, RATE, freqs, AMP_VRMS, ADC_FS_VRMS, 0, "T", false);
    }

    /** What is left of the delay, in samples: a delay error of {@code d}
     *  samples is a phase ramp of {@code 2*pi*f*d/fs}, so the residual phase
     *  across the grid measures it, read as a difference between its two
     *  ends. */
    private double leftoverDelaySamples(FreqRespCalibration cal, boolean withNotch) {
        double[] residuals = residualsRad(cal, withNotch);
        int last = residuals.length - 1;
        return (residuals[last] - residuals[0]) * RATE
                / (2.0 * Math.PI * (cal.freqs[last] - cal.freqs[0]));
    }

    /** The deconvolved phase minus the model's at every grid point, unwrapped
     *  across the band so a leftover ramp stays one continuous line. */
    private double[] residualsRad(FreqRespCalibration cal, boolean withNotch) {
        double[] residuals = new double[cal.freqs.length];
        residuals[0] = Math.IEEEremainder(
                cal.phaseRad[0] - expectedPhase(cal.freqs[0], withNotch), 2.0 * Math.PI);
        for (int i = 1; i < residuals.length; i++) {
            double step = Math.IEEEremainder(
                    (cal.phaseRad[i] - expectedPhase(cal.freqs[i], withNotch))
                            - (cal.phaseRad[i - 1] - expectedPhase(cal.freqs[i - 1], withNotch)),
                    2.0 * Math.PI);
            residuals[i] = residuals[i - 1] + step;
        }
        return residuals;
    }

    /** The same residuals with the band's straight line removed - the line
     *  through the mean of the outermost {@link #EDGE_POINTS} at each edge.
     *  On a narrow band that line is the part a transport delay and the
     *  filter's own group delay share, and it is what the phase probe takes
     *  out before it reads anything. */
    private double[] deTrended(FreqRespCalibration cal, boolean withNotch) {
        double[] residuals = residualsRad(cal, withNotch);
        int n = residuals.length;
        int edge = Math.min(EDGE_POINTS, n / 2);
        double loFreq = mean(cal.freqs, 0, edge);
        double loRes  = mean(residuals, 0, edge);
        double hiFreq = mean(cal.freqs, n - edge, n);
        double hiRes  = mean(residuals, n - edge, n);
        double slope  = (hiRes - loRes) / (hiFreq - loFreq);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = residuals[i] - (loRes + slope * (cal.freqs[i] - loFreq));
        }
        return out;
    }

    private double mean(double[] values, int from, int to) {
        double sum = 0.0;
        for (int i = from; i < to; i++) {
            sum += values[i];
        }
        return sum / (to - from);
    }

    /**
     * The modelled recording: the reference put through the fractional delay
     * and, when the caller asks for it, the notch - one complex multiply per
     * bin - and then the converter's noise floor in the time domain.
     *
     * <p>Doing it in the frequency domain is what makes the expectation exact:
     * the deconvolution divides the same transform back out, so anything the
     * assertions see beyond the model is the deconvolution's own doing.
     */
    private double[] model(double[] reference, boolean withNotch, boolean withNoise) {
        int m = reference.length;
        double[] buf = reference.clone();
        DoubleFFT_1D fft = new DoubleFFT_1D(m);
        fft.realForward(buf);
        // Packing: [0] is DC, [1] the Nyquist bin, then re/im per bin.  The
        // notch passes DC unchanged and a delay turns it not at all, so DC
        // stands; the sweep carries nothing at Nyquist, which no grid reaches.
        double binHz = RATE / (double) m;
        for (int k = 1; k < m / 2; k++) {
            double theta = -2.0 * Math.PI * k * DELAY_SAMPLES / m;
            double[] h   = withNotch ? notch(k * binHz) : new double[] { 1.0, 0.0 };
            // The filter and the delay, multiplied together first.
            double gRe = h[0] * Math.cos(theta) - h[1] * Math.sin(theta);
            double gIm = h[0] * Math.sin(theta) + h[1] * Math.cos(theta);
            double re  = buf[2*k];
            double im  = buf[2*k + 1];
            buf[2*k]     = re * gRe - im * gIm;
            buf[2*k + 1] = re * gIm + im * gRe;
        }
        fft.realInverse(buf, true);

        if (withNoise) {
            Random rng = new Random(SEED);
            for (int i = 0; i < m; i++) {
                buf[i] += NOISE_STD * rng.nextGaussian();
            }
        }
        return buf;
    }

    /**
     * The modelled notch at {@code f} as {@code { re, im }}: the biquad
     * band-stop whose zero pair does NOT sit on the imaginary axis,
     * {@code (x + j*u/Qz) / (x + j*u/Qp)} with {@code u = f/f0} and
     * {@code x = 1 - u^2}.
     *
     * <p>The two Q values are what make it a measured notch instead of a
     * textbook one: the pole pair sets the skirts, the zero pair closes the
     * null to a finite {@code Qp/Qz} instead of to nothing, and their ratio is
     * what the phase does across the null - 168 degrees inside 8 Hz, several
     * degrees from one grid point to the next.  A response that steep is the
     * reason the measurement has to bring the phase back right.
     */
    private double[] notch(double f) {
        double u = f / NOTCH_HZ;
        double x = 1.0 - u * u;
        double a = u / NOTCH_ZERO_Q;
        double b = u / NOTCH_POLE_Q;
        double den = x * x + b * b;
        return new double[] { (x * x + a * b) / den, x * (a - b) / den };
    }

    /** The phase the deconvolution owes at {@code f}: the notch's own where
     *  the path carries one, zero where it is flat - the delay being what the
     *  deconvolution is expected to have taken out either way. */
    private double expectedPhase(double f, boolean withNotch) {
        if (!withNotch) {
            return 0.0;
        }
        double[] h = notch(f);
        return Math.atan2(h[1], h[0]);
    }

    private double modelDb(double f) {
        double[] h = notch(f);
        return dbOf(Math.hypot(h[0], h[1]));
    }

    private double dbOf(double magLin) {
        return 20.0 * Math.log10(magLin);
    }
}
