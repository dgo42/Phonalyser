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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.edgo.audio.measure.dsp.ToneLobeLift;
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
        acc.add(ones(), zeros(), 1.0, FrameTurns.of(DELTA));
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
                new FrameTurns(DELTA, Double.NaN, phiF, 0.0, Double.NaN, Double.NaN));
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
        acc.add(ones(), zeros(), 1.0, FrameTurns.of(DELTA));
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
                new FrameTurns(DELTA, phi2, 0.0, phiF2, Double.NaN, Double.NaN));
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
        acc.add(ones(), zeros(), 1.0, FrameTurns.of(DELTA));
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
                new FrameTurns(DELTA, Double.NaN, 0.0, 0.0, read, Double.NaN));
        for (int m = -LOBE_HALF; m <= LOBE_HALF; m++) {
            assertAngle(read, angleAt(acc, pilot + m), "the pilot's hump by its reading, bin " + (pilot + m));
        }
        assertAngle(proportional(pilot + LOBE_HALF + 1), angleAt(acc, pilot + LOBE_HALF + 1),
                "the bin above the pilot's hump: proportional");

        CoherentAccumulator counted = new CoherentAccumulator();
        counted.restart(FFT_SIZE, true, KAPPA, Double.NaN, null, null, LOBE_HALF, pilot, NO_PILOT);
        counted.add(ones(), zeros(), 1.0, FrameTurns.of(DELTA));
        for (int m = -LOBE_HALF; m <= LOBE_HALF; m++) {
            assertAngle(proportional(pilot), angleAt(counted, pilot + m),
                    "no reading: the whole hump by the pilot's own bin, bin " + (pilot + m));
        }
    }

    @Test
    void withoutAPinnedFundamentalTheFrameIsAddedAsItStands() {
        CoherentAccumulator acc = new CoherentAccumulator();
        acc.restart(FFT_SIZE, true, 0.0, Double.NaN, null, null, LOBE_HALF, NO_PILOT, NO_PILOT);
        acc.add(ones(), zeros(), 2.0, FrameTurns.of(DELTA));
        assertEquals(2.0, acc.getSumRe()[777], 0.0, "weighted, unrotated");
        assertEquals(0.0, acc.getSumIm()[777], 0.0, "no rotation");
        assertEquals(2.0, acc.getFrames(), 0.0, "two frames held");
    }

    @Test
    void subtractingThePreviousCycleTakesItsFrameOutExactly() {
        CoherentAccumulator acc = singleTone();
        acc.add(ones(), zeros(), 1.0, FrameTurns.of(0.0));
        acc.add(ones(), zeros(), 1.0, FrameTurns.of(DELTA));
        acc.subtractPreviousCycles();
        for (int k = 0; k <= FFT_SIZE / 2; k += 97) {
            assertEquals(1.0, acc.getSumRe()[k], TOL_RAD, "the reference frame alone remains, bin " + k);
            assertEquals(0.0, acc.getSumIm()[k], TOL_RAD, "bin " + k);
        }
        assertEquals(1.0, acc.getFrames(), 0.0, "one frame held");
    }

    @Test
    void subtractingThePreviousCycleTakesItsPowerOutExactly() {
        CoherentAccumulator acc = new CoherentAccumulator();
        acc.restart(FFT_SIZE, false, KAPPA, Double.NaN, null, null, LOBE_HALF, NO_PILOT, NO_PILOT);
        double[] mag = ones();
        mag[10] = 3.0;
        acc.addPower(ones(), zeros(), 1.0);
        acc.addPower(mag, zeros(), 1.0);
        acc.subtractPreviousCycles();
        assertEquals(1.0, acc.getSumPow()[10], 0.0, "the first frame's power alone remains");
        assertEquals(1.0, acc.getFrames(), 0.0, "one frame held");
    }

    @Test
    void aScaledPreviousCycleComesOutWithItsScaledWeight() {
        CoherentAccumulator acc = singleTone();
        acc.add(ones(), zeros(), 1.0, FrameTurns.of(0.0));
        acc.add(ones(), zeros(), 1.0, FrameTurns.of(DELTA));
        acc.scale(0.5);
        acc.subtractPreviousCycles();
        for (int k = 0; k <= FFT_SIZE / 2; k += 97) {
            assertEquals(0.5, acc.getSumRe()[k], TOL_RAD, "the scaled reference frame alone remains, bin " + k);
            assertEquals(0.0, acc.getSumIm()[k], TOL_RAD, "bin " + k);
        }
        assertEquals(0.5, acc.getFrames(), TOL_RAD,
                "the scaled frames less the scaled weight of the frame taken out");
    }

    @Test
    void aSubtractionWithNothingHeldChangesNothing() {
        CoherentAccumulator acc = singleTone();
        acc.add(ones(), zeros(), 1.0, FrameTurns.of(0.0));
        acc.add(ones(), zeros(), 1.0, FrameTurns.of(DELTA));
        acc.subtractPreviousCycles();
        double[] held = acc.getSumRe().clone();
        acc.subtractPreviousCycles();
        assertArrayEquals(held, acc.getSumRe(), "the first subtraction emptied the ring");
        assertEquals(1.0, acc.getFrames(), 0.0, "one frame held");

        acc.add(ones(), zeros(), 1.0, FrameTurns.of(DELTA));
        acc.restart(FFT_SIZE, true, KAPPA, Double.NaN, null, null, LOBE_HALF, NO_PILOT, NO_PILOT);
        acc.subtractPreviousCycles();
        assertArrayEquals(zeros(), acc.getSumRe(), "a restart empties the ring with the sums");
        assertEquals(0.0, acc.getFrames(), 0.0, "no frame held");

        acc.add(ones(), zeros(), 1.0, FrameTurns.of(0.0));
        acc.reset();
        acc.subtractPreviousCycles();
        assertEquals(0.0, acc.getFrames(), 0.0, "a reset empties the ring with the sums");
    }

    @Test
    void scalingKeepsTheMeanAndShrinksTheFramesHeld() {
        CoherentAccumulator acc = singleTone();
        acc.add(ones(), zeros(), 1.0, FrameTurns.of(0.0));
        acc.add(ones(), zeros(), 1.0, FrameTurns.of(0.0));
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
            acc.add(fr[0], fr[1], 1.0, FrameTurns.of(f * DELTA));
            assertEquals(kappaTrue + 0.2, acc.getKappa(), 0.0, "kappa stands while measuring, frame " + f);
            assertEquals(false, acc.isKappaRefined(), "still measuring, frame " + f);
        }
        int last = CoherentAccumulator.KAPPA_MEAS_FRAMES - 1;
        double[][] fr = toneFrame(kappaTrue, last * DELTA);
        acc.add(fr[0], fr[1], 1.0, FrameTurns.of(last * DELTA));
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
            acc.add(ones(), zeros(), 1.0, FrameTurns.of(f));
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
        acc.addPower(mag, zeros(), 1.0);
        acc.addPower(mag, zeros(), 1.0);
        assertEquals(18.0, acc.getSumPow()[10], 0.0, "twice the squared magnitude");
        assertEquals(2.0, acc.getFrames(), 0.0, "two frames held");
        assertEquals(0, acc.statisticsRowCount(), "no statistics row: a power average carries no phase");
    }

    /** Between H15 and H16 of the fundamental at bin 100, outside both humps:
     *  a bin the noise cases fill with a phase of their own. */
    private static final int NOISE_BIN    = 1_550;
    /** Frames of the power-average cases - fewer than the κ measurement
     *  spans, so κ stands throughout. */
    private static final int POWER_FRAMES = 16;
    /** The coherent mean of {@link #POWER_FRAMES} random phases stands near
     *  1/sqrt(16) = 0.25; far under the unit power it keeps. */
    private static final double RANDOM_MEAN_LIMIT = 0.6;
    private static final double TWO_PI = 2.0 * Math.PI;

    @Test
    void theCoherentAverageKeepsThePowerOfItsFrames() {
        CoherentAccumulator acc = singleTone();
        Random random = new Random(NOISE_SEED);
        for (int f = 0; f < POWER_FRAMES; f++) {
            double[] re = ones();
            double[] im = zeros();
            double phase = TWO_PI * random.nextDouble();
            re[NOISE_BIN] = Math.cos(phase);
            im[NOISE_BIN] = Math.sin(phase);
            acc.add(re, im, 1.0, FrameTurns.of(f * DELTA));
        }
        assertEquals(POWER_FRAMES, acc.getSumPow()[NOISE_BIN], TOL_RAD,
                "every frame's unit power, whatever its phase or rotation");
        double mean = Math.hypot(acc.getSumRe()[NOISE_BIN], acc.getSumIm()[NOISE_BIN]) / acc.getFrames();
        assertTrue(mean < RANDOM_MEAN_LIMIT, "while the coherent mean of the random phases falls: " + mean);
    }

    @Test
    void aScaledSubtractionTakesThePowerOutWithTheComplexSums() {
        CoherentAccumulator acc = singleTone();
        double[] loud = ones();
        loud[NOISE_BIN] = 3.0;
        acc.add(ones(), zeros(), 1.0, FrameTurns.of(0.0));
        acc.add(loud, zeros(), 1.0, FrameTurns.of(DELTA));
        acc.scale(0.5);
        acc.subtractPreviousCycles();
        assertEquals(0.5, acc.getSumPow()[NOISE_BIN], TOL_RAD, "the scaled first frame's power alone remains");
        assertEquals(0.5, acc.getSumRe()[NOISE_BIN], TOL_RAD, "as in the complex sums");
        assertEquals(0.5, acc.getFrames(), TOL_RAD, "the weight taken off once");
    }

    // ── Statistics ─────────────────────────────────────────────────────────

    /** H2's centre bin on the fundamental at bin 100. */
    private static final int    H2_BIN      = 200;
    /** The band top's geometry in the statistics cases: 1 Hz bins and a noise
     *  bandwidth of 2 bins - a window of 25 bins to either side of the line,
     *  the lobe excluded out to 8. */
    private static final double BAND_BIN_HZ = 1.0;
    private static final double BAND_NENBW  = 2.0;
    private static final long   NOISE_SEED  = 7L;

    /** The statistics column named {@code name}. */
    private int column(CoherentAccumulator acc, String name) {
        int c = acc.statisticsColumns().indexOf(name);
        assertTrue(c >= 0, "a column " + name);
        return c;
    }

    /** A frame of unit-variance complex Gaussian noise. */
    private double[][] noiseFrame(Random random) {
        double[] re = zeros(), im = zeros();
        for (int k = 0; k < re.length; k++) {
            re[k] = random.nextGaussian();
            im[k] = random.nextGaussian();
        }
        return new double[][] { re, im };
    }

    @Test
    void theStatisticsGrowOneRowPerFrameAndARestartOrAResetDropsThem() {
        CoherentAccumulator acc = singleTone();
        // beyond the store's first sizing of 64 rows
        int rows = 100;
        for (int f = 0; f < rows; f++) {
            acc.add(ones(), zeros(), 1.0, FrameTurns.of(DELTA));
        }
        assertEquals(rows, acc.statisticsRowCount(), "one row per frame added");
        for (int row = 0; row < rows; row++) {
            assertEquals(row + 1, acc.statistic(row, 0), 0.0, "the frame count after the add, row " + row);
        }
        acc.restart(FFT_SIZE, true, KAPPA, Double.NaN, null, null, LOBE_HALF, NO_PILOT, NO_PILOT);
        assertEquals(0, acc.statisticsRowCount(), "dropped by the restart");
        acc.add(ones(), zeros(), 1.0, FrameTurns.of(0.0));
        assertEquals(1, acc.statisticsRowCount(), "the new collection's first row");
        acc.reset();
        assertEquals(0, acc.statisticsRowCount(), "dropped by the reset");
        assertThrows(IndexOutOfBoundsException.class, () -> acc.statistic(0, 0), "no row to read");
    }

    @Test
    void theStatisticsFollowTheTonesH2ToH9AndEveryProduct() {
        CoherentAccumulator acc = new CoherentAccumulator();
        acc.restart(FFT_SIZE, true, KAPPA, 260.0, new int[] { 0, 1, -1 }, new int[] { 1, 1, 1 },
                LOBE_HALF, 1234, NO_PILOT);
        List<String> columns = acc.statisticsColumns();
        // the frame count, four columns per tone, six per line: H2 .. H9 and
        // the products F2 - F1 at 160, F1 + F2 at 360
        assertEquals(1 + 4 * 2 + 6 * (8 + 2), columns.size(), "the columns");
        assertEquals(List.of("frames",
                "(1,0) raw", "(1,0) accumulated", "(1,0) raw phase", "(1,0) accumulated phase",
                "(0,1) raw", "(0,1) accumulated", "(0,1) raw phase", "(0,1) accumulated phase"),
                columns.subList(0, 9), "the count, then the tones");
        assertEquals(List.of("(-1,1) raw", "(-1,1) accumulated", "(-1,1) raw phase", "(-1,1) accumulated phase",
                "(-1,1) raw band top", "(-1,1) accumulated band top"), columns.subList(9, 15),
                "then the lines in position order, F2 - F1 at 160 first");
        assertTrue(columns.contains("(9,0) accumulated band top"), "H9");
        assertFalse(columns.contains("(10,0) raw"), "H10 lies beyond the statistics' harmonics");
        assertTrue(columns.contains("(1,1) raw phase"), "F1 + F2");
        assertFalse(columns.contains("(0,1) raw band top"), "a tone has no band top column");
        assertFalse(columns.stream().anyMatch(n -> n.startsWith("(0,0)")), "no pilot");
        assertThrows(UnsupportedOperationException.class, () -> columns.add("x"), "read-only");
    }

    @Test
    void theStatisticsHoldTheValuesTheAddSaw() {
        CoherentAccumulator acc = singleTone();
        acc.add(ones(), zeros(), 1.0, FrameTurns.of(0.0));
        double[] re = ones();
        double[] im = zeros();
        re[H2_BIN] = 3.0;
        im[H2_BIN] = 4.0;
        acc.add(re, im, 1.0, FrameTurns.of(DELTA));
        int    row    = acc.statisticsRowCount() - 1;
        double frames = acc.getFrames();
        double sRe    = acc.getSumRe()[H2_BIN];
        double sIm    = acc.getSumIm()[H2_BIN];
        int    k0     = (int) Math.round(KAPPA);
        assertEquals(frames, acc.statistic(row, 0), 0.0, "the frame count");
        assertEquals(5.0, acc.statistic(row, column(acc, "(2,0) raw")), 1e-12, "H2's raw magnitude, |3 + 4i|");
        assertEquals(Math.hypot(sRe, sIm) / frames, acc.statistic(row, column(acc, "(2,0) accumulated")), 1e-12,
                "H2's accumulated magnitude over the frame count");
        // the frame's phase turned back by H2's angle, 2 x the fundamental's
        double turned = Math.atan2(4.0, 3.0) + 2.0 * acc.fundamentalAngle(DELTA);
        assertAngle(turned, Math.toRadians(acc.statistic(row, column(acc, "(2,0) raw phase"))),
                "H2's raw phase, as the add turned it back");
        assertEquals(Math.toDegrees(Math.atan2(sIm, sRe)), acc.statistic(row, column(acc, "(2,0) accumulated phase")),
                1e-12, "H2's accumulated phase");
        assertTrue(Double.isNaN(acc.statistic(row, column(acc, "(2,0) raw band top"))), "no band geometry set");
        assertTrue(Double.isNaN(acc.statistic(row, column(acc, "(2,0) accumulated band top"))), "no band geometry set");
        assertEquals(Math.hypot(acc.getSumRe()[k0], acc.getSumIm()[k0]) / frames,
                acc.statistic(row, column(acc, "(1,0) accumulated")), 1e-12, "the fundamental's accumulated magnitude");
    }

    @Test
    void theBandTopsAreTheLineFloorsOfTheFrameAndOfTheAverage() {
        CoherentAccumulator acc = singleTone();
        acc.bandGeometry(BAND_BIN_HZ, BAND_NENBW);
        Random random = new Random(NOISE_SEED);
        double[][] fr = null;
        for (int f = 0; f < 5; f++) {
            fr = noiseFrame(random);
            acc.add(fr[0], fr[1], 1.0, FrameTurns.of(f * DELTA));
        }
        ToneLobeLift   lobe   = new ToneLobeLift();
        int            half   = FFT_SIZE / 2;
        double         frames = acc.getFrames();
        double[]       sRe    = acc.getSumRe();
        double[]       sIm    = acc.getSumIm();
        final double[][] last = fr;
        double raw = lobe.lineFloor(k -> Math.hypot(last[0][k], last[1][k]), H2_BIN, half, BAND_BIN_HZ, BAND_NENBW);
        double avg = lobe.lineFloor(k -> Math.hypot(sRe[k], sIm[k]) / frames, H2_BIN, half, BAND_BIN_HZ, BAND_NENBW);
        int row = acc.statisticsRowCount() - 1;
        assertEquals(raw, acc.statistic(row, column(acc, "(2,0) raw band top")), raw * 1e-12,
                "the band top over the frame's own bins beside H2");
        assertEquals(avg, acc.statistic(row, column(acc, "(2,0) accumulated band top")), avg * 1e-12,
                "the band top over the average's bins beside H2");

        acc.restart(FFT_SIZE, true, KAPPA, Double.NaN, null, null, LOBE_HALF, NO_PILOT, NO_PILOT);
        acc.add(ones(), zeros(), 1.0, FrameTurns.of(0.0));
        assertTrue(Double.isNaN(acc.statistic(0, column(acc, "(2,0) raw band top"))),
                "a restart drops the geometry");
    }

    @Test
    void aFrameWithoutAPinnedFundamentalRecordsTheFrameCountAlone() {
        CoherentAccumulator acc = new CoherentAccumulator();
        acc.restart(FFT_SIZE, true, 0.0, Double.NaN, null, null, LOBE_HALF, NO_PILOT, NO_PILOT);
        acc.add(ones(), zeros(), 2.0, FrameTurns.of(DELTA));
        assertEquals(List.of("frames"), acc.statisticsColumns(), "no line to follow");
        assertEquals(1, acc.statisticsRowCount(), "one row");
        assertEquals(2.0, acc.statistic(0, 0), 0.0, "the frame count");
        assertThrows(IndexOutOfBoundsException.class, () -> acc.statistic(0, 1), "no column beyond the count");
    }
}
