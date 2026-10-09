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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;

import org.edgo.audio.measure.fft.CoherentAccumulator.FrameTurns;
import org.junit.jupiter.api.Test;

/**
 * The rotation rule of the coherent average, read off a frame of unit
 * phasors: every bin starts at angle zero, so the accumulated angle at a bin
 * is the rotation that bin received.  Geometry: a transform of 4096 samples,
 * the fundamental on bin 100, a hump of 3 bins to either side.
 */
class CoherentAccumulatorTest {

    private static final int    FFT_SIZE  = 4096;
    private static final double KAPPA     = 100.0;
    private static final int    LOBE_HALF = 3;
    /** A frame's advance since the reference frame, samples - chosen so that
     *  no angle below folds onto another. */
    private static final double DELTA     = 37.0;
    private static final double TOL_RAD   = 1e-9;
    private static final int    NO_PILOT  = -1;

    private double[] ones() {
        double[] v = new double[FFT_SIZE / 2 + 1];
        Arrays.fill(v, 1.0);
        return v;
    }

    private double[] zeros() {
        return new double[FFT_SIZE / 2 + 1];
    }

    /** The angle bin k received: the accumulated phasor's, the frame's being 0. */
    private double angleAt(CoherentAccumulator acc, int k) {
        return Math.atan2(acc.getSumIm()[k], acc.getSumRe()[k]);
    }

    private double proportional(int k) {
        return Math.IEEEremainder(-2.0 * Math.PI * DELTA * k / FFT_SIZE, 2.0 * Math.PI);
    }

    private void assertAngle(double expected, double actual, String what) {
        assertEquals(0.0, Math.IEEEremainder(actual - expected, 2.0 * Math.PI), TOL_RAD, what);
    }

    private CoherentAccumulator singleTone() {
        CoherentAccumulator acc = new CoherentAccumulator();
        acc.restart(FFT_SIZE, true, KAPPA, Double.NaN, null, null, LOBE_HALF, NO_PILOT, NO_PILOT);
        return acc;
    }

    @Test
    void aHumpTurnsByItsLinesAngleAndTheRestInProportionToTheBin() {
        CoherentAccumulator acc = singleTone();
        acc.add(ones(), zeros(), 1.0, FrameTurns.of(DELTA), null, null);
        double phi1 = acc.fundamentalAngle(DELTA);
        int h2 = (int) Math.round(2 * KAPPA);
        // every bin of H2's hump turns by 2 x Phi1, the centre included
        for (int m = -LOBE_HALF; m <= LOBE_HALF; m++) {
            assertAngle(2.0 * phi1, angleAt(acc, h2 + m), "H2 hump, bin " + (h2 + m));
        }
        // the first bins outside the hump turn by their own angle
        assertAngle(proportional(h2 - LOBE_HALF - 1), angleAt(acc, h2 - LOBE_HALF - 1), "below H2's hump");
        assertAngle(proportional(h2 + LOBE_HALF + 1), angleAt(acc, h2 + LOBE_HALF + 1), "above H2's hump");
        // half-way between H2 and H3 the proportional rule holds: the angle of
        // the advance at that bin's own frequency, whole turns included
        int mid = (int) Math.round(2.5 * KAPPA);
        assertAngle(proportional(mid), angleAt(acc, mid), "between H2 and H3");
        assertEquals(1.0, acc.getFrames(), 0.0, "one frame held");
    }

    @Test
    void theExcessReachesTheFundamentalsHumpAlone() {
        CoherentAccumulator acc = singleTone();
        double phiF = 0.4;
        acc.add(ones(), zeros(), 1.0,
                new FrameTurns(DELTA, Double.NaN, phiF, 0.0, Double.NaN, Double.NaN), null, null);
        double phi1 = acc.fundamentalAngle(DELTA);
        int k0 = (int) Math.round(KAPPA);
        for (int m = -LOBE_HALF; m <= LOBE_HALF; m++) {
            assertAngle(phi1 - phiF, angleAt(acc, k0 + m), "fundamental's hump, bin " + (k0 + m));
        }
        assertAngle(proportional(k0 + LOBE_HALF + 1), angleAt(acc, k0 + LOBE_HALF + 1), "just above the hump");
        assertAngle(2.0 * phi1, angleAt(acc, 2 * k0), "H2 keeps its own angle");
    }

    @Test
    void aOneBinHumpIsTheRectangularWindowsCase() {
        CoherentAccumulator acc = new CoherentAccumulator();
        acc.restart(FFT_SIZE, true, KAPPA, Double.NaN, null, null, 1, NO_PILOT, NO_PILOT);
        acc.add(ones(), zeros(), 1.0, FrameTurns.of(DELTA), null, null);
        double phi1 = acc.fundamentalAngle(DELTA);
        int h3 = (int) Math.round(3 * KAPPA);
        assertAngle(3.0 * phi1, angleAt(acc, h3 - 1), "H3's hump, one bin below");
        assertAngle(3.0 * phi1, angleAt(acc, h3 + 1), "H3's hump, one bin above");
        assertAngle(proportional(h3 + 2), angleAt(acc, h3 + 2), "two bins above: proportional");
    }

    @Test
    void aProductTurnsByItsCombinationAndTheSecondToneTakesItsOwnExcess() {
        double kappa2 = 260.0;
        double phi2 = 1.1, phiF2 = 0.25;
        CoherentAccumulator acc = new CoherentAccumulator();
        acc.restart(FFT_SIZE, true, KAPPA, kappa2, new int[] { 0, 1, -1 }, new int[] { 1, 1, 1 },
                LOBE_HALF, NO_PILOT, NO_PILOT);
        acc.add(ones(), zeros(), 1.0,
                new FrameTurns(DELTA, phi2, 0.0, phiF2, Double.NaN, Double.NaN), null, null);
        double phi1 = acc.fundamentalAngle(DELTA);
        assertAngle(phi2 - phiF2, angleAt(acc, (int) kappa2), "F2's hump: its turn less its excess");
        assertAngle(phi1 + phi2, angleAt(acc, (int) (KAPPA + kappa2)), "the sum product F1 + F2");
        assertAngle(phi2 - phi1, angleAt(acc, (int) (kappa2 - KAPPA)), "the difference product F2 - F1");
        assertAngle(proportional(230), angleAt(acc, 230), "a bin between the products: proportional");
    }

    @Test
    void aCountedSecondToneTurnsByItsOwnBin() {
        double kappa2 = 260.5;
        CoherentAccumulator acc = new CoherentAccumulator();
        acc.restart(FFT_SIZE, true, KAPPA, kappa2, new int[] { 0 }, new int[] { 1 },
                LOBE_HALF, NO_PILOT, NO_PILOT);
        acc.add(ones(), zeros(), 1.0, FrameTurns.of(DELTA), null, null);
        double phi2 = Math.IEEEremainder(-2.0 * Math.PI * kappa2 * DELTA / FFT_SIZE, 2.0 * Math.PI);
        for (int k = 258; k <= 263; k++) {
            assertAngle(phi2, angleAt(acc, k), "F2's hump turned by F2's counted turn, bin " + k);
        }
    }

    @Test
    void aPilotsHumpTurnsByItsOwnReadingOrByItsBin() {
        int pilot = 1234;
        CoherentAccumulator acc = new CoherentAccumulator();
        acc.restart(FFT_SIZE, true, KAPPA, Double.NaN, null, null, LOBE_HALF, pilot, NO_PILOT);
        double read = 0.7;
        acc.add(ones(), zeros(), 1.0,
                new FrameTurns(DELTA, Double.NaN, 0.0, 0.0, read, Double.NaN), null, null);
        for (int m = -LOBE_HALF; m <= LOBE_HALF; m++) {
            assertAngle(read, angleAt(acc, pilot + m), "the pilot's hump by its reading, bin " + (pilot + m));
        }
        assertAngle(proportional(pilot + LOBE_HALF + 1), angleAt(acc, pilot + LOBE_HALF + 1),
                "the bin above the pilot's hump: proportional");

        CoherentAccumulator counted = new CoherentAccumulator();
        counted.restart(FFT_SIZE, true, KAPPA, Double.NaN, null, null, LOBE_HALF, pilot, NO_PILOT);
        counted.add(ones(), zeros(), 1.0, FrameTurns.of(DELTA), null, null);
        for (int m = -LOBE_HALF; m <= LOBE_HALF; m++) {
            assertAngle(proportional(pilot), angleAt(counted, pilot + m),
                    "no reading: the whole hump by the pilot's own bin, bin " + (pilot + m));
        }
    }

    @Test
    void withoutAPinnedFundamentalTheFrameIsAddedAsItStands() {
        CoherentAccumulator acc = new CoherentAccumulator();
        acc.restart(FFT_SIZE, true, 0.0, Double.NaN, null, null, LOBE_HALF, NO_PILOT, NO_PILOT);
        acc.add(ones(), zeros(), 2.0, FrameTurns.of(DELTA), null, null);
        assertEquals(2.0, acc.getSumRe()[777], 0.0, "weighted, unrotated");
        assertEquals(0.0, acc.getSumIm()[777], 0.0, "no rotation");
        assertEquals(2.0, acc.getFrames(), 0.0, "two frames held");
    }

    @Test
    void aContributionHandedBackTakesTheFrameOutExactly() {
        CoherentAccumulator acc = singleTone();
        double[] cRe = zeros(), cIm = zeros();
        acc.add(ones(), zeros(), 1.0, FrameTurns.of(0.0), null, null);
        acc.add(ones(), zeros(), 1.0, FrameTurns.of(DELTA), cRe, cIm);
        acc.remove(cRe, cIm, 1.0);
        for (int k = 0; k <= FFT_SIZE / 2; k += 97) {
            assertEquals(1.0, acc.getSumRe()[k], TOL_RAD, "the reference frame alone remains, bin " + k);
            assertEquals(0.0, acc.getSumIm()[k], TOL_RAD, "bin " + k);
        }
        assertEquals(1.0, acc.getFrames(), 0.0, "one frame held");
    }

    @Test
    void scalingKeepsTheMeanAndShrinksTheFramesHeld() {
        CoherentAccumulator acc = singleTone();
        acc.add(ones(), zeros(), 1.0, FrameTurns.of(0.0), null, null);
        acc.add(ones(), zeros(), 1.0, FrameTurns.of(0.0), null, null);
        acc.scale(0.5);
        assertEquals(1.0, acc.getFrames(), 0.0, "frames scaled with the sums");
        assertEquals(1.0, acc.getSumRe()[5] / acc.getFrames(), TOL_RAD, "the mean is unchanged");
    }

    /** A frame whose fundamental bin carries the phase of a tone at
     *  {@code kappaTrue} bins after an advance of {@code delta} samples,
     *  every other bin a unit phasor. */
    private double[][] toneFrame(double kappaTrue, double delta) {
        double[] re = ones(), im = zeros();
        int    k0  = (int) Math.round(kappaTrue);
        double ang = 2.0 * Math.PI * kappaTrue * delta / FFT_SIZE;
        re[k0] = Math.cos(ang);
        im[k0] = Math.sin(ang);
        return new double[][] { re, im };
    }

    @Test
    void theFundamentalsPhaseSlopeCorrectsKappaOnceTheMeasurementCloses() {
        // Pinned 0.2 bin above the tone: kappa stands through the measurement
        // and the slope over the whole stretch puts it on the tone when the
        // measurement closes.
        double kappaTrue = 100.0;
        CoherentAccumulator acc = new CoherentAccumulator();
        acc.restart(FFT_SIZE, true, kappaTrue + 0.2, Double.NaN, null, null, LOBE_HALF, NO_PILOT, NO_PILOT);
        for (int f = 0; f < CoherentAccumulator.KAPPA_MEAS_FRAMES - 1; f++) {
            double[][] fr = toneFrame(kappaTrue, f * DELTA);
            acc.add(fr[0], fr[1], 1.0, FrameTurns.of(f * DELTA), null, null);
            assertEquals(kappaTrue + 0.2, acc.getKappa(), 0.0, "kappa stands while measuring, frame " + f);
            assertEquals(false, acc.isKappaRefined(), "still measuring, frame " + f);
        }
        int last = CoherentAccumulator.KAPPA_MEAS_FRAMES - 1;
        double[][] fr = toneFrame(kappaTrue, last * DELTA);
        acc.add(fr[0], fr[1], 1.0, FrameTurns.of(last * DELTA), null, null);
        assertEquals(kappaTrue, acc.getKappa(), 1e-9, "corrected onto the tone when the measurement closes");
        assertEquals(true, acc.isKappaRefined(), "the measurement closed");
    }

    @Test
    void aSlopeBeyondHalfABinIsNoToneAndIsDiscarded() {
        // The pinned bin holds a phasor that does not advance with the frames
        // - no tone at the fundamental: turned back by the pinned kappa it
        // reads a slope of a whole 100 bins when the measurement closes,
        // which is discarded.
        CoherentAccumulator acc = new CoherentAccumulator();
        acc.restart(FFT_SIZE, true, KAPPA, Double.NaN, null, null, LOBE_HALF, NO_PILOT, NO_PILOT);
        for (int f = 0; f < CoherentAccumulator.KAPPA_MEAS_FRAMES; f++) {
            acc.add(ones(), zeros(), 1.0, FrameTurns.of(f), null, null);
        }
        assertEquals(KAPPA, acc.getKappa(), 0.0, "kappa stands");
        assertEquals(false, acc.isKappaRefined(), "nothing closed");
    }

    @Test
    void theIncoherentAverageSumsPower() {
        CoherentAccumulator acc = new CoherentAccumulator();
        acc.restart(FFT_SIZE, false, KAPPA, Double.NaN, null, null, LOBE_HALF, NO_PILOT, NO_PILOT);
        double[] mag = ones();
        mag[10] = 3.0;
        acc.addPower(mag, zeros(), 1.0, null);
        acc.addPower(mag, zeros(), 1.0, null);
        assertEquals(18.0, acc.getSumPow()[10], 0.0, "twice the squared magnitude");
        assertEquals(2.0, acc.getFrames(), 0.0, "two frames held");
    }
}
