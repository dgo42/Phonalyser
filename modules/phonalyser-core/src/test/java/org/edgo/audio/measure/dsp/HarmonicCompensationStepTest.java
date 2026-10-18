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

import org.edgo.audio.measure.dsp.HarmonicCompensation.HarmonicPhasors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The correction step of the harmonic accumulator: the plain inversion.  A
 * correction comes back at the analyser inverted, so a clear harmonic's
 * measured residual is added to the correction in force and cancels on the
 * next round; a harmonic under the apply gate moves nothing.
 */
class HarmonicCompensationStepTest {

    private static final int    HARMONICS = 8;          // H2..H9
    private static final double F0_HZ     = 1000.0;
    private static final double RAW_LEVEL = 1.0e-6;
    private static final double CLEAR_DB  = 40.0;       // well over the apply gate
    private static final double FLOOR_DB  = 2.0;        // under the apply gate
    private static final double STEP      = 1.0;

    private final double[] rawRe = new double[HARMONICS];
    private final double[] rawIm = new double[HARMONICS];

    HarmonicCompensationStepTest() {
        for (int h = 0; h < HARMONICS; h++) {
            double phase = 0.7 * (h + 2);
            rawRe[h] = RAW_LEVEL * Math.cos(phase);
            rawIm[h] = RAW_LEVEL * Math.sin(phase);
        }
    }

    /** The accumulated correction of every harmonic as {@code [re, im]}. */
    private double[][] applied(HarmonicCompensation comp) {
        double[][] acc = new double[HARMONICS][];
        for (int h = 0; h < HARMONICS; h++) {
            acc[h] = comp.correction(h);
        }
        return acc;
    }

    /** What the analyser reads with the correction {@code acc} applied: the
     *  raw harmonic plus the correction, which the chain returns inverted. */
    private HarmonicPhasors measure(double[][] acc, double clearanceDb) {
        double[] re = new double[HARMONICS], im = new double[HARMONICS];
        double[] hz = new double[HARMONICS], clear = new double[HARMONICS];
        for (int h = 0; h < HARMONICS; h++) {
            re[h] = rawRe[h] - acc[h][0];
            im[h] = rawIm[h] - acc[h][1];
            hz[h] = (h + 2) * F0_HZ;
            clear[h] = clearanceDb;
        }
        return new HarmonicPhasors(re, im, hz, clear);
    }

    private double residual(HarmonicPhasors z, int h) {
        return Math.hypot(z.re()[h], z.im()[h]) / RAW_LEVEL;
    }

    @Test
    void theStepCopiesTheReadingIntoTheCorrection() {
        HarmonicCompensation comp = new HarmonicCompensation(HARMONICS);
        comp.accumulate(measure(new double[HARMONICS][2], CLEAR_DB), STEP);
        double[][] acc = applied(comp);
        for (int h = 0; h < HARMONICS; h++) {
            assertEquals(rawRe[h], acc[h][0], 1e-15, "H" + (h + 2) + " correction, real");
            assertEquals(rawIm[h], acc[h][1], 1e-15, "H" + (h + 2) + " correction, imaginary");
            double[] delta = comp.lastDelta(h);
            assertEquals(rawRe[h], delta[0], 1e-15, "H" + (h + 2) + " delta of the round, real");
            assertEquals(rawIm[h], delta[1], 1e-15, "H" + (h + 2) + " delta of the round, imaginary");
        }
    }

    @Test
    void theCorrectionCancelsTheHarmonicOnTheNextRound() {
        HarmonicCompensation comp = new HarmonicCompensation(HARMONICS);
        comp.accumulate(measure(new double[HARMONICS][2], CLEAR_DB), STEP);
        HarmonicPhasors round1 = measure(applied(comp), CLEAR_DB);
        for (int h = 0; h < HARMONICS; h++) {
            assertEquals(0.0, residual(round1, h), 1e-9, "H" + (h + 2) + " is cancelled");
        }
        // A residual left by the chain is added on top, the sum stays.
        comp.accumulate(round1, STEP);
        double[][] acc = applied(comp);
        for (int h = 0; h < HARMONICS; h++) {
            assertEquals(rawRe[h], acc[h][0], 1e-9, "H" + (h + 2) + " correction holds");
            assertEquals(rawIm[h], acc[h][1], 1e-9);
        }
    }

    @Test
    void aReadingUnderTheGateMovesNothing() {
        HarmonicCompensation comp = new HarmonicCompensation(HARMONICS);
        comp.accumulate(measure(new double[HARMONICS][2], FLOOR_DB), STEP);
        double[][] acc = applied(comp);
        for (int h = 0; h < HARMONICS; h++) {
            assertEquals(0.0, acc[h][0], 0.0, "H" + (h + 2) + " keeps no correction from a floor reading");
            assertEquals(0.0, acc[h][1], 0.0);
            double[] delta = comp.lastDelta(h);
            assertEquals(0.0, delta[0], 0.0);
            assertEquals(0.0, delta[1], 0.0);
        }
    }
}
