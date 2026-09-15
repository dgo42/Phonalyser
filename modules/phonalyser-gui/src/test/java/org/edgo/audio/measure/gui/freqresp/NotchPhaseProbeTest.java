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

package org.edgo.audio.measure.gui.freqresp;

import org.edgo.audio.measure.dsp.FreqRespCalibration;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.OutputChannels;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Math tests for {@link NotchPhaseProbe} - the part of a probe that needs no
 * audio device: the loop-period and swept-band geometry, the complex average
 * of the grabbed responses, and the de-trended phase reading at the
 * fundamental.
 *
 * <p>The synthetic responses model what a probe really sees around a notch:
 * a large LINEAR phase across the band (the session's transport delay, which
 * wraps several times over the band and carries no information about the
 * filter) plus a LOCAL bump centred on the fundamental (the notch's own
 * phase swing).  The de-trend exists to return the bump and throw the line
 * away, and the tests read exactly that back.  Phases are wrapped into
 * (-pi, pi] before they go in, because that is how the deconvolution hands
 * them over.
 */
class NotchPhaseProbeTest {

    /** The pinned fundamental of the synthetic probes, in Hz. */
    private static final double F0_HZ = 1000.0;
    /** Points on the synthetic grid - odd, so the grid has a middle point
     *  and the fundamental falls exactly on it. */
    private static final int POINTS = 201;
    /** Total linear phase across the band, in radians: several wraps, so a
     *  reading that skipped the unwrap cannot pass by accident. */
    private static final double TREND_SPAN_RAD = 10.0;
    /** Half-width of the synthetic notch bump as a fraction of the band -
     *  a quarter of it, which leaves the outermost bins the de-trend line is
     *  fitted through at exactly zero bump. */
    private static final double BUMP_HALF_FRACTION = 0.25;

    /** The probe's target loop period and per-seam fade, in seconds.  The
     *  probe keeps these to itself and hands them to the engine, which owns
     *  the rules that turn them into a period and a swept band; the two
     *  geometry tests below mirror them to check that the probe's own
     *  durations land where they have to. */
    private static final double PROBE_PERIOD_SEC = 2.7;
    private static final double PROBE_FADE_SEC   = 0.025;

    private static final int    OUT_RATE_HZ  = 48000;
    private static final int    OUT_BITS     = 24;
    private static final int    DITHER_BITS  = 0;
    private static final double DAC_FS_VRMS  = 1.0;
    private static final double ADC_FS_VRMS  = 1.0;
    private static final double AMP_VRMS     = 0.1;
    private static final double TOL_RAD      = 1e-9;

    // -------------------------------------------------------------------------
    // Geometry
    // -------------------------------------------------------------------------

    @Test
    void loopPeriodIsThePowerOfTwoNearestTheProbesTargetDuration() {
        // A power of two AT THE CAPTURE CLOCK is what makes the
        // deconvolution's transform exactly one period; both configured
        // input rates have to land on one at the probe's target duration.
        try (NotchSweepEngine at48k  = engine(48000);
             NotchSweepEngine at384k = engine(384000)) {
            int period48k  = at48k.loopPeriodSamples(PROBE_PERIOD_SEC);
            int period384k = at384k.loopPeriodSamples(PROBE_PERIOD_SEC);

            assertEquals(1, Integer.bitCount(period48k),  "48 kHz period is a power of two");
            assertEquals(1, Integer.bitCount(period384k), "384 kHz period is a power of two");
            assertEquals(1 << 17, period48k);
            assertEquals(1 << 20, period384k);
            // Both land near the target, each on its own clock.
            assertEquals(PROBE_PERIOD_SEC, period48k  / 48000.0,  0.1);
            assertEquals(PROBE_PERIOD_SEC, period384k / 384000.0, 0.1);
        }
    }

    @Test
    void sweptBandEnclosesTheProbesReadBandByTheFadeFraction() {
        double readLo = F0_HZ / 1.1;
        double readHi = F0_HZ * 1.1;
        try (NotchSweepEngine engine = engine(48000)) {
            int period = engine.loopPeriodSamples(PROBE_PERIOD_SEC);
            int fade   = (int) Math.round(PROBE_FADE_SEC * 48000);
            double[] swept = engine.sweptBand(readLo, readHi, fade / (double) period);

            assertTrue(swept[0] < readLo, "the sweep starts below the band that is read");
            assertTrue(swept[1] > readHi, "the sweep ends above the band that is read");
            // The fade is 25 ms of a 2.73 s period, so the widening is parts
            // per thousand - the read band's outermost bins clear the fade
            // without the sweep wandering off the notch.
            assertEquals(1.0, readLo / swept[0], 0.02);
            assertEquals(1.0, swept[1] / readHi, 0.02);

            // A fade worth a tenth of the period widens the band by a lot
            // more: the margin follows the fade fraction, it is not a fixed
            // number.
            double[] coarse = engine.sweptBand(readLo, readHi, 0.1);
            assertTrue(coarse[0] < swept[0] && coarse[1] > swept[1],
                    "a longer fade has to push the swept band further out");
        }
    }

    // -------------------------------------------------------------------------
    // Complex average
    // -------------------------------------------------------------------------

    @Test
    void complexAverageCrossesThePhaseWrap() {
        // Two readings 10 degrees apart that happen to straddle +/-180.  The
        // arithmetic mean of +175 and -175 degrees is 0 - the exact opposite
        // point on the circle; the complex mean is 180 degrees.
        FreqRespCalibration plus  = flat(0.5, Math.toRadians(175.0));
        FreqRespCalibration minus = flat(0.5, Math.toRadians(-175.0));
        FreqRespCalibration mean  = probe(48000)
                .averageComplex(new FreqRespCalibration[] { plus, minus });

        assertEquals(Math.PI, Math.abs(mean.phaseRad[0]), 1e-12,
                "the mean of +175 and -175 degrees is 180 degrees, not 0");
        assertEquals(0.5 * Math.cos(Math.toRadians(5.0)), mean.magLin[0], 1e-12,
                "the mean phasor is shortened by the 5 degree disagreement");
    }

    @Test
    void complexAverageOfAgreeingGrabsChangesNothing() {
        // Three identical readings: neither the level nor the phase moves -
        // the proof that the magnitudes are not averaged in dB and the
        // phases not averaged as angles.
        FreqRespCalibration one = flat(0.25, 2.0);
        FreqRespCalibration mean = probe(48000)
                .averageComplex(new FreqRespCalibration[] { one, one, one });

        assertEquals(0.25, mean.magLin[0],   1e-12);
        assertEquals(2.0,  mean.phaseRad[0], 1e-12);
    }

    // -------------------------------------------------------------------------
    // De-trend and delta
    // -------------------------------------------------------------------------

    @Test
    void deTrendRecoversTheLocalBumpUnderAWrappingLinearPhase() {
        double bumpRad = 0.42;
        FreqRespCalibration response = bumped(bumpRad, 0.0);

        assertEquals(bumpRad, probe(48000).residualAtHz(response, F0_HZ), TOL_RAD,
                "the reading at the fundamental is the notch's own phase, "
                + "with the band's linear phase removed");
    }

    @Test
    void deTrendIsBlindToAConstantPhaseOffset() {
        // Where the unwrap started, and any constant the session sits at,
        // move the whole curve AND the line through the band edges by the
        // same amount - so two probes taken minutes apart stay comparable.
        NotchPhaseProbe probe = probe(48000);
        double plain   = probe.residualAtHz(bumped(0.42, 0.0), F0_HZ);
        double shifted = probe.residualAtHz(bumped(0.42, 1.234), F0_HZ);

        assertEquals(plain, shifted, TOL_RAD);
    }

    @Test
    void deltaBetweenTwoProbesIsTheChangeInTheNotchPhase() {
        // What the accumulator is told is never one probe's reading but the
        // change since the run's first probe.
        NotchPhaseProbe probe = probe(48000);
        double baseline = probe.residualAtHz(bumped(0.10, 0.0), F0_HZ);
        double later    = probe.residualAtHz(bumped(0.35, 2.0), F0_HZ);

        assertEquals(0.25, later - baseline, TOL_RAD);
    }

    @Test
    void readingBetweenTwoGridPointsIsTheirStraightLine() {
        // The fundamental rarely lands on a bin centre, so the reading is
        // interpolated; between two neighbours it is exactly their mean.
        NotchPhaseProbe probe = probe(48000);
        FreqRespCalibration response = bumped(0.42, 0.0);
        double[] freqs = response.freqs;
        int k = POINTS / 2 - 3;
        double atLow  = probe.residualAtHz(response, freqs[k]);
        double atHigh = probe.residualAtHz(response, freqs[k + 1]);
        double between = probe.residualAtHz(response,
                0.5 * (freqs[k] + freqs[k + 1]));

        assertEquals(0.5 * (atLow + atHigh), between, TOL_RAD);
    }

    // -------------------------------------------------------------------------
    // Synthetic responses
    // -------------------------------------------------------------------------

    /** A sweep session's engine, constructed only to be asked about the loop
     *  geometry it owns: nothing is acquired until {@code start}, and these
     *  tests never call it. */
    private NotchSweepEngine engine(int captureRateHz) {
        return new NotchSweepEngine(captureRateHz, OUT_RATE_HZ, OUT_BITS, DITHER_BITS);
    }

    /** A probe configured for {@code captureRateHz}; no device is opened by
     *  construction, and none of these tests calls {@code run}. */
    private NotchPhaseProbe probe(int captureRateHz) {
        return new NotchPhaseProbe(captureRateHz, OUT_RATE_HZ, OUT_BITS, DITHER_BITS,
                DAC_FS_VRMS, DAC_FS_VRMS, ADC_FS_VRMS, AMP_VRMS,
                OutputChannels.BOTH, Channel.R);
    }

    /** One response whose magnitude and phase are the same at every point. */
    private FreqRespCalibration flat(double magLin, double phaseRad) {
        int n = 3;
        double[] freqs = new double[n];
        double[] mags  = new double[n];
        double[] phase = new double[n];
        for (int i = 0; i < n; i++) {
            freqs[i] = F0_HZ + i;
            mags[i]  = magLin;
            phase[i] = phaseRad;
        }
        return new FreqRespCalibration(freqs, mags, phase);
    }

    /**
     * A synthetic probe response on a grid centred on the fundamental: a
     * linear phase spanning {@link #TREND_SPAN_RAD} across the band, a raised
     * cosine bump of {@code bumpRad} at the fundamental that dies out well
     * before the band edges, and a constant {@code offsetRad} on top.  The
     * phase is wrapped into (-pi, pi] the way the deconvolution returns it.
     */
    private FreqRespCalibration bumped(double bumpRad, double offsetRad) {
        double lo   = F0_HZ / 1.1;
        double hi   = F0_HZ * 1.1;
        double step = (hi - lo) / (POINTS - 1);
        double half = (hi - lo) * BUMP_HALF_FRACTION;
        double slope = TREND_SPAN_RAD / (hi - lo);

        double[] freqs = new double[POINTS];
        double[] mags  = new double[POINTS];
        double[] phase = new double[POINTS];
        for (int i = 0; i < POINTS; i++) {
            double f = F0_HZ + (i - POINTS / 2) * step;
            double d = Math.abs(f - F0_HZ);
            double bump = d >= half ? 0.0
                    : bumpRad * 0.5 * (1.0 + Math.cos(Math.PI * d / half));
            double raw = offsetRad + slope * (f - F0_HZ) + bump;
            freqs[i] = f;
            mags[i]  = 1.0;
            phase[i] = Math.atan2(Math.sin(raw), Math.cos(raw));
        }
        return new FreqRespCalibration(freqs, mags, phase);
    }
}
