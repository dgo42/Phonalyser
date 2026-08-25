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

import java.util.Arrays;
import java.util.function.DoubleFunction;

import org.edgo.audio.measure.dsp.HarmonicCompensation.GeneratorCorrections;
import org.edgo.audio.measure.dsp.HarmonicCompensation.HarmonicPhasors;
import org.edgo.audio.measure.fft.FftResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plant probe: a planted per-harmonic transfer must come back out of
 * {@link HarmonicCompensation#measurePlant} exactly, an accumulator without a
 * plant must convert exactly as it did before one existed, and a probe that
 * does not clear the noise floor must leave the identity behind.
 */
class HarmonicCompensationPlantTest {

    private static final int    SLOTS       = 8;          // H2..H9
    private static final double F1_HZ       = 1000.0;
    /** Probe amplitude ratio to the DAC fundamental (-60 dB). */
    private static final double PROBE_AMP   = 1.0e-3;
    private static final double CLEARANCE_DB = 20.0;
    /** The excess delay planted in the synthetic chain: every harmonic's
     *  transfer is turned by (n-1) times this. */
    private static final double TURN_PER_HARMONIC_DEG = 12.5;

    /** A flat, transparent chain - the probe math must not need a real one. */
    private final DoubleFunction<double[]> unitChain = f -> new double[]{ 1.0, 0.0 };

    @Test
    void plantedTransferIsRecoveredExactly() {
        double[] baseRe = { 2.0e-5, -1.0e-5, 5.0e-6, 0.0, 0.0, 0.0, 0.0, 0.0 };
        double[] baseIm = { 1.0e-5,  3.0e-5, 2.0e-6, 0.0, 0.0, 0.0, 0.0, 0.0 };
        HarmonicCompensation comp = new HarmonicCompensation(SLOTS);

        FftResult baseline = frame(baseRe, baseIm, -200.0);
        double[] probeRe = new double[SLOTS];
        double[] probeIm = new double[SLOTS];
        for (int h = 0; h < 3; h++) {
            // The chain answers the commanded partial with the planted turn.
            // Expected arrival with a unit chain and a unit fundamental level:
            // -c, so the measured arrival is -c turned by the plant.
            double turn = Math.toRadians((h + 1) * TURN_PER_HARMONIC_DEG);
            double cRe  = PROBE_AMP * Math.cos(commandedPhase(h));
            double cIm  = PROBE_AMP * Math.sin(commandedPhase(h));
            double arrivalRe = -(cRe * Math.cos(turn) - cIm * Math.sin(turn));
            double arrivalIm = -(cRe * Math.sin(turn) + cIm * Math.cos(turn));
            probeRe[h] = baseRe[h] + arrivalRe;
            probeIm[h] = baseIm[h] + arrivalIm;
        }
        FftResult probed = frame(probeRe, probeIm, -200.0);

        HarmonicPhasors plant = comp.measurePlant(baseline, probed,
                probeAmplitudes(3), commandedPhases(), unitChain, CLEARANCE_DB);

        for (int h = 0; h < 3; h++) {
            double mag   = Math.hypot(plant.re()[h], plant.im()[h]);
            double angle = Math.toDegrees(Math.atan2(plant.im()[h], plant.re()[h]));
            assertEquals(1.0, mag, 1e-9, "H" + (h + 2) + " magnitude");
            assertEquals((h + 1) * TURN_PER_HARMONIC_DEG, angle, 1e-9, "H" + (h + 2) + " phase");
        }
        // A harmonic that was never probed carries no measurement at all.
        assertTrue(Double.isNaN(plant.re()[3]));
    }

    @Test
    void unsetPlantLeavesTheCorrectionUntouched() {
        HarmonicCompensation plain = new HarmonicCompensation(SLOTS);
        HarmonicCompensation withNaN = new HarmonicCompensation(SLOTS);
        double[] re = { 2.0e-5, -1.0e-5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0 };
        double[] im = { 1.0e-5,  3.0e-5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0 };
        plain.accumulate(frame(re, im, -200.0), 1.0, 0.0);
        withNaN.accumulate(frame(re, im, -200.0), 1.0, 0.0);

        double[] nan = new double[SLOTS];
        Arrays.fill(nan, Double.NaN);
        withNaN.setPlant(new HarmonicPhasors(nan, nan, new double[SLOTS]));

        GeneratorCorrections a = plain.toGeneratorCorrections(F1_HZ, unitChain, 1.0, 1.0);
        GeneratorCorrections b = withNaN.toGeneratorCorrections(F1_HZ, unitChain, 1.0, 1.0);
        assertEquals(a.ampRatios().length, b.ampRatios().length);
        for (int i = 0; i < a.ampRatios().length; i++) {
            // Bit-exact: an unmeasured plant divides by 1 + 0j.
            assertEquals(a.ampRatios()[i], b.ampRatios()[i]);
            assertEquals(a.phiInits()[i],  b.phiInits()[i]);
            assertEquals(a.harmonicNumbers()[i], b.harmonicNumbers()[i]);
        }
    }

    @Test
    void installedPlantTurnsTheCorrectionBackByItsPhase() {
        HarmonicCompensation comp = new HarmonicCompensation(SLOTS);
        double[] re = { 1.0e-5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0 };
        double[] im = { 0.0,    0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0 };
        comp.accumulate(frame(re, im, -200.0), 1.0, 0.0);
        GeneratorCorrections before = comp.toGeneratorCorrections(F1_HZ, unitChain, 1.0, 1.0);

        // A unit-magnitude plant turned by -30 degrees: dividing by it turns
        // the commanded correction by +30 and leaves its amplitude alone.
        double[] planeRe = new double[SLOTS];
        double[] planeIm = new double[SLOTS];
        Arrays.fill(planeRe, Double.NaN);
        Arrays.fill(planeIm, Double.NaN);
        planeRe[0] = Math.cos(Math.toRadians(-30.0));
        planeIm[0] = Math.sin(Math.toRadians(-30.0));
        comp.setPlant(new HarmonicPhasors(planeRe, planeIm, new double[SLOTS]));

        GeneratorCorrections after = comp.toGeneratorCorrections(F1_HZ, unitChain, 1.0, 1.0);
        assertEquals(before.ampRatios()[0], after.ampRatios()[0], 1e-15);
        assertEquals(Math.toDegrees(before.phiInits()[0]) + 30.0,
                Math.toDegrees(after.phiInits()[0]), 1e-9);
    }

    @Test
    void measuredPlantPreTurnsTheNextCorrection() {
        double[] baseRe = new double[SLOTS];
        double[] baseIm = new double[SLOTS];
        baseRe[0] = 2.0e-5;
        double turn  = Math.toRadians(TURN_PER_HARMONIC_DEG);
        double cRe   = PROBE_AMP * Math.cos(commandedPhase(0));
        double cIm   = PROBE_AMP * Math.sin(commandedPhase(0));
        double[] probeRe = baseRe.clone();
        double[] probeIm = baseIm.clone();
        probeRe[0] += -(cRe * Math.cos(turn) - cIm * Math.sin(turn));
        probeIm[0] += -(cRe * Math.sin(turn) + cIm * Math.cos(turn));

        HarmonicCompensation probedComp = new HarmonicCompensation(SLOTS);
        HarmonicPhasors plant = probedComp.measurePlant(frame(baseRe, baseIm, -200.0),
                frame(probeRe, probeIm, -200.0), probeAmplitudes(1), commandedPhases(),
                unitChain, CLEARANCE_DB);

        // The same measured residual, converted with and without that plant.
        HarmonicCompensation plain = new HarmonicCompensation(SLOTS);
        double[] re = new double[SLOTS];
        double[] im = new double[SLOTS];
        re[0] = 1.0e-5;
        plain.accumulate(frame(re, im, -200.0), 1.0, 0.0);
        probedComp.accumulate(frame(re, im, -200.0), 1.0, 0.0);
        probedComp.setPlant(plant);

        double without = plain.toGeneratorCorrections(F1_HZ, unitChain, 1.0, 1.0).phiInits()[0];
        double with    = probedComp.toGeneratorCorrections(F1_HZ, unitChain, 1.0, 1.0).phiInits()[0];
        // The chain turns what it is commanded by +12.5 degrees, so the command
        // leaves pre-turned by -12.5 and the correction arrives where the loop
        // meant it to.
        assertEquals(-TURN_PER_HARMONIC_DEG, Math.toDegrees(with - without), 1e-9);
    }

    @Test
    void probeAtTheNoiseFloorKeepsTheIdentityPlant() {
        HarmonicCompensation comp = new HarmonicCompensation(SLOTS);
        double[] baseRe = new double[SLOTS];
        double[] baseIm = new double[SLOTS];
        baseRe[0] = 2.0e-5;
        double[] probeRe = baseRe.clone();
        double[] probeIm = baseIm.clone();
        probeRe[0] += -PROBE_AMP;                       // arrival at -60 dBFS

        // Floor at -70 dBFS: the arrival stands 10 dB above it, short of the
        // 20 dB clearance, so the measurement is refused.
        FftResult probed = frame(probeRe, probeIm, -70.0);
        HarmonicPhasors plant = comp.measurePlant(frame(baseRe, baseIm, -70.0), probed,
                probeAmplitudes(1), commandedPhases(), unitChain, CLEARANCE_DB);
        assertTrue(Double.isNaN(plant.re()[0]), "refused below the clearance");

        // The same arrival against a floor at -100 dBFS clears it.
        FftResult quiet = frame(probeRe, probeIm, -100.0);
        HarmonicPhasors ok = comp.measurePlant(frame(baseRe, baseIm, -100.0), quiet,
                probeAmplitudes(1), commandedPhases(), unitChain, CLEARANCE_DB);
        assertFalse(Double.isNaN(ok.re()[0]), "accepted above the clearance");
    }

    /** Commanded phase of probe partial {@code h}, 45 degrees apart. */
    private double commandedPhase(int h) {
        return Math.toRadians(h * 45.0);
    }

    private double[] commandedPhases() {
        double[] phases = new double[SLOTS];
        for (int h = 0; h < SLOTS; h++) phases[h] = commandedPhase(h);
        return phases;
    }

    /** Probe amplitudes with the first {@code probed} harmonics commanded. */
    private double[] probeAmplitudes(int probed) {
        double[] amp = new double[SLOTS];
        for (int h = 0; h < probed; h++) amp[h] = PROBE_AMP;
        return amp;
    }

    /**
     * A frame whose harmonics carry exactly the given phasors in the
     * accumulator's frame.  The raw fundamental is placed at -90 degrees with
     * unit magnitude, which makes the de-rotation delay zero, so each
     * harmonic's raw phase IS its accumulator-frame phase and its raw level is
     * the phasor magnitude.
     */
    private FftResult frame(double[] re, double[] im, double noiseFloorDbFs) {
        FftResult r = new FftResult();
        r.freqResolution       = 1.0;
        r.fundamentalBin       = (int) F1_HZ;
        r.fundamentalHzRefined = F1_HZ;
        r.fundamentalDbFs      = 0.0;
        r.rawFundRe            = 0.0;
        r.rawFundIm            = -1.0;
        r.avgNoiseFloorDbFs    = noiseFloorDbFs;
        r.harmonicCount        = SLOTS;
        r.harmonicBins         = new int[SLOTS];
        r.harmonicHz           = new double[SLOTS];
        r.harmonicDbFs         = new double[SLOTS];
        r.rawPeakRe            = new double[SLOTS];
        r.rawPeakIm            = new double[SLOTS];
        for (int h = 0; h < SLOTS; h++) {
            double mag = Math.hypot(re[h], im[h]);
            r.harmonicBins[h] = (h + 2) * (int) F1_HZ;
            r.harmonicHz[h]   = (h + 2) * F1_HZ;
            r.harmonicDbFs[h] = mag > 0.0 ? 20.0 * Math.log10(mag) : -300.0;
            r.rawPeakRe[h]    = re[h];
            r.rawPeakIm[h]    = im[h];
        }
        return r;
    }
}
