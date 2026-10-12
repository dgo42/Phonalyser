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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.edgo.audio.measure.dsp.ToneLobeLift;
import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.WindowType;
import org.edgo.audio.measure.fft.CoherentAccumulator.FrameTurns;
import org.edgo.audio.measure.fft.FftResult.LineState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    /** The fundamental of the line-reset tests, off the bin grid so that the
     *  last-digit residual of a κ refinement moves no lobe edge and no centre
     *  bin: H2 at 200.4 holds bins 198 .. 203 around bin 200, H3 at 300.6
     *  bins 298 .. 303 around bin 301; the ladder ends at H20, H21 at 2104.2
     *  lying beyond bin 2048. */
    private static final double LINE_KAPPA  = 100.2;
    private static final double LINE_KAPPA2 = 260.0;
    private static final int    H2_LO       = 198;
    private static final int    H2_HI       = 203;
    private static final int    H2_BIN      = 200;
    private static final int    H3_LO       = 298;
    private static final int    H3_HI       = 303;
    private static final int    H3_BIN      = 301;
    private static final int    LADDER_LINES = 19;
    private static final int    PILOT_BIN   = 1234;
    /** The local bands' geometry: 1 Hz bins and a noise bandwidth of 1.5
     *  bins give a window of 25 bins to either side of a line - H2's 175 ..
     *  225, H3's 276 .. 326, apart; at 0.25 Hz bins the window is 100 bins,
     *  and H2's 100 .. 300 and H3's 201 .. 401 share 100 bins. */
    private static final double BAND_BIN_HZ    = 1.0;
    private static final double OVERLAP_BIN_HZ = 0.25;
    private static final double BAND_NENBW     = 1.5;
    private static final long   NOISE_SEED     = 42L;
    private static final int    NOISE_FRAMES   = 10;

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

    /** {@code count} frames of a tone standing on {@link #LINE_KAPPA}, one
     *  advance of {@link #DELTA} apart from the reference frame on. */
    private void addToneFrames(CoherentAccumulator acc, int count) {
        for (int f = 0; f < count; f++) {
            double[][] fr = toneFrame(LINE_KAPPA, f * DELTA);
            acc.add(fr[0], fr[1], 1.0, FrameTurns.of(f * DELTA), null, null);
        }
    }

    /** A single-tone average on {@link #LINE_KAPPA} with a pilot on
     *  {@link #PILOT_BIN}, its κ measurement closed on
     *  {@link CoherentAccumulator#KAPPA_MEAS_FRAMES} frames. */
    private CoherentAccumulator refinedSingleTone() {
        CoherentAccumulator acc = new CoherentAccumulator();
        acc.restart(FFT_SIZE, true, LINE_KAPPA, Double.NaN, null, null, LOBE_HALF, PILOT_BIN, NO_PILOT);
        addToneFrames(acc, CoherentAccumulator.KAPPA_MEAS_FRAMES);
        assertTrue(acc.isKappaRefined(), "the kappa measurement closed");
        return acc;
    }

    /** The average written onto a result of unit window gain, the
     *  fundamental on its bin, its harmonic table H2 .. H20. */
    private FftResult overlaid(CoherentAccumulator acc) {
        return overlaid(acc, LADDER_LINES);
    }

    /** The same with a harmonic table of {@code harmonicCount} entries, H2
     *  .. H(harmonicCount + 1). */
    private FftResult overlaid(CoherentAccumulator acc, int harmonicCount) {
        FftResult r = new FftResult();
        r.ensureArrays(FFT_SIZE / 2 + 1, harmonicCount);
        r.harmonicCount      = harmonicCount;
        r.fftSize            = FFT_SIZE;
        r.windowCoherentGain = 1.0;
        r.fundamentalBin     = (int) Math.round(LINE_KAPPA);
        acc.overlayOnto(r);
        return r;
    }

    private void assertEveryLineOnTheGlobalCount(FftResult r, int global) {
        assertEquals(global, r.frameCount, "the global frame count");
        assertFalse(r.lineStates.isEmpty(), "the family holds lines");
        for (LineState s : r.lineStates) {
            assertEquals(global, s.localCount(), "line (" + s.a() + ", " + s.b() + ")");
        }
    }

    @Test
    void aLinesLocalCountFollowsTheGlobalCountUntilItsReset() {
        CoherentAccumulator acc = refinedSingleTone();
        double[] cRe = zeros(), cIm = zeros();
        acc.add(ones(), zeros(), 1.0, FrameTurns.of(DELTA), null, null);
        acc.add(ones(), zeros(), 1.0, FrameTurns.of(DELTA), null, null);
        acc.scale(0.5);
        acc.add(ones(), zeros(), 1.0, FrameTurns.of(DELTA), cRe, cIm);
        acc.remove(cRe, cIm, 1.0);
        // 24 + 2 frames, halved, one more added and taken out again
        assertEveryLineOnTheGlobalCount(overlaid(acc), 13);

        CoherentAccumulator power = new CoherentAccumulator();
        power.restart(FFT_SIZE, false, LINE_KAPPA, Double.NaN, null, null, LOBE_HALF, NO_PILOT, NO_PILOT);
        double[] cPow = zeros();
        power.addPower(ones(), zeros(), 1.0, null);
        power.addPower(ones(), zeros(), 1.0, cPow);
        power.addPower(ones(), zeros(), 1.0, null);
        power.removePower(cPow, 1.0);
        power.scale(2.0);
        assertEveryLineOnTheGlobalCount(overlaid(power), 4);

        assertTrue(acc.resetLine(2, 0), "H2 reset");
        acc.add(ones(), zeros(), 1.0, FrameTurns.of(DELTA), null, null);
        FftResult r = overlaid(acc);
        assertEquals(14, r.frameCount, "the global frame count goes on");
        for (LineState s : r.lineStates) {
            int expected = s.a() == 2 && s.b() == 0 ? 1 : 14;
            assertEquals(expected, s.localCount(), "line (" + s.a() + ", " + s.b() + ")");
        }
    }

    @Test
    void aLineResetEmptiesOnlyItsLobeAndCountAndClearsItsMark() {
        CoherentAccumulator acc = refinedSingleTone();
        assertTrue(acc.markLineWaiting(2, 0), "H2 marked");
        assertTrue(acc.markLineWaiting(3, 0), "H3 marked");
        double[] re = acc.getSumRe().clone();
        double[] im = acc.getSumIm().clone();
        double frames = acc.getFrames();
        double kappa  = acc.getKappa();
        List<LineState> before = overlaid(acc).lineStates;

        assertTrue(acc.resetLine(2, 0), "the reset is taken");
        for (int k = 0; k <= FFT_SIZE / 2; k++) {
            boolean lobe = k >= H2_LO && k <= H2_HI;
            String what = lobe ? "H2's lobe emptied, bin " + k : "untouched - the pilot's hump included - bin " + k;
            assertEquals(lobe ? 0.0 : re[k], acc.getSumRe()[k], 0.0, what);
            assertEquals(lobe ? 0.0 : im[k], acc.getSumIm()[k], 0.0, what);
        }
        assertEquals(frames, acc.getFrames(), 0.0, "the global frame count stands");
        assertEquals(kappa, acc.getKappa(), 0.0, "kappa stands");
        assertTrue(Double.isNaN(acc.getKappa2()), "no second tone");
        assertTrue(acc.isKappaRefined(), "still refined");

        List<LineState> after = overlaid(acc).lineStates;
        assertEquals(before.size(), after.size(), "the family stands");
        for (int i = 0; i < before.size(); i++) {
            LineState was = before.get(i);
            if (was.a() == 2 && was.b() == 0) {
                assertEquals(new LineState(2, 0, H2_BIN, 0, false, Double.NaN), after.get(i),
                        "H2: count zero, mark cleared");
            } else {
                assertEquals(was, after.get(i), "line (" + was.a() + ", " + was.b() + ") untouched");
            }
        }
    }

    @Test
    void aToneAPilotOrALineOutsideTheFamilyIsNeverReset() {
        CoherentAccumulator acc = new CoherentAccumulator();
        acc.restart(FFT_SIZE, true, LINE_KAPPA, LINE_KAPPA2, new int[] { 0, 1 }, new int[] { 1, 1 },
                LOBE_HALF, PILOT_BIN, NO_PILOT);
        addToneFrames(acc, CoherentAccumulator.KAPPA_MEAS_FRAMES);
        assertTrue(acc.isKappaRefined(), "the kappa measurement closed");
        // the fundamental, the second tone, the pilot (no tone's coefficients)
        // and H21, which lies beyond the spectrum
        int[][] never = { { 1, 0 }, { 0, 1 }, { 0, 0 }, { 21, 0 } };
        for (int[] line : never) {
            String name = "(" + line[0] + ", " + line[1] + ")";
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> acc.resetLine(line[0], line[1]), "reset of " + name);
            assertTrue(ex.getMessage().contains(name), "the message names " + name + ": " + ex.getMessage());
            assertThrows(IllegalArgumentException.class,
                    () -> acc.markLineWaiting(line[0], line[1]), "waiting mark of " + name);
        }
        assertTrue(acc.resetLine(1, 1), "the product F1 + F2 is reset");
    }

    @Test
    void aLineResetIsRefusedUntilKappaIsRefined() {
        CoherentAccumulator acc = new CoherentAccumulator();
        acc.restart(FFT_SIZE, true, LINE_KAPPA, Double.NaN, null, null, LOBE_HALF, NO_PILOT, NO_PILOT);
        addToneFrames(acc, CoherentAccumulator.KAPPA_MEAS_FRAMES - 1);
        assertFalse(acc.isKappaRefined(), "still measuring");
        double[] re = acc.getSumRe().clone();
        double[] im = acc.getSumIm().clone();
        assertFalse(acc.resetLine(2, 0), "the reset is refused");
        assertFalse(acc.markLineWaiting(2, 0), "the waiting mark is refused");
        assertArrayEquals(re, acc.getSumRe(), "nothing emptied");
        assertArrayEquals(im, acc.getSumIm(), "nothing emptied");
        LineState h2 = overlaid(acc).lineState(2, 0);
        assertEquals(CoherentAccumulator.KAPPA_MEAS_FRAMES - 1, h2.localCount(), "H2 on the global count");
        assertFalse(h2.waitingReset(), "H2 unmarked");
    }

    @Test
    void theOverlayDividesAResetLobeByItsLocalCountAndTheRestByTheGlobalCount() {
        CoherentAccumulator acc = refinedSingleTone();
        assertTrue(acc.resetLine(2, 0), "H2 reset");
        FftResult empty = overlaid(acc);
        assertEquals(0.0, empty.re[H2_BIN], 0.0, "an emptied lobe reads no signal");
        assertEquals(-300.0, empty.amplitudeDbFs[H2_BIN], 0.0, "an emptied lobe reads no signal");

        // two frames on the reference frame's time origin, added as they stand
        double[] three = ones();
        Arrays.fill(three, 3.0);
        acc.add(three, zeros(), 1.0, FrameTurns.of(0.0), null, null);
        acc.add(three, zeros(), 1.0, FrameTurns.of(0.0), null, null);
        FftResult r = overlaid(acc);
        assertEquals(CoherentAccumulator.KAPPA_MEAS_FRAMES + 2, r.frameCount, "the global frame count");
        for (int k = H2_LO; k <= H2_HI; k++) {
            assertEquals(3.0, r.re[k], TOL_RAD, "H2's lobe over its local count of 2, bin " + k);
            assertEquals(0.0, r.im[k], TOL_RAD, "bin " + k);
        }
        double mean = 1.0 / acc.getFrames();
        for (int k : new int[] { H2_LO - 1, H2_HI + 1, H3_LO, H3_BIN, H3_HI, PILOT_BIN, 777 }) {
            assertEquals(acc.getSumRe()[k] * mean, r.re[k], 0.0, "over the global count, bin " + k);
            assertEquals(acc.getSumIm()[k] * mean, r.im[k], 0.0, "over the global count, bin " + k);
        }
    }

    @Test
    void theLineStatesCarryEachResettableLinesCoefficientsBinCountAndMark() {
        CoherentAccumulator acc = new CoherentAccumulator();
        acc.restart(FFT_SIZE, true, LINE_KAPPA, LINE_KAPPA2, new int[] { 0, 1, -1 }, new int[] { 1, 1, 1 },
                LOBE_HALF, PILOT_BIN, NO_PILOT);
        addToneFrames(acc, CoherentAccumulator.KAPPA_MEAS_FRAMES);
        assertTrue(acc.markLineWaiting(3, 0), "H3 marked");
        assertTrue(acc.resetLine(2, 0), "H2 reset");
        acc.add(ones(), zeros(), 1.0, FrameTurns.of(DELTA), null, null);
        FftResult r = overlaid(acc);
        int global = CoherentAccumulator.KAPPA_MEAS_FRAMES + 1;
        assertEquals(global, r.frameCount, "the global frame count");
        assertEquals(LADDER_LINES + 2, r.lineStates.size(), "H2 .. H20 and the products F1 + F2, F2 - F1");
        assertEquals(new LineState(2, 0, H2_BIN, 1, false, Double.NaN), r.lineState(2, 0), "H2");
        assertEquals(new LineState(3, 0, H3_BIN, global, true, Double.NaN), r.lineState(3, 0), "H3");
        assertEquals(new LineState(1, 1, 360, global, false, Double.NaN), r.lineState(1, 1), "F1 + F2 at 360.2");
        assertEquals(new LineState(-1, 1, 160, global, false, Double.NaN), r.lineState(-1, 1), "F2 - F1 at 159.8");
        assertNull(r.lineState(1, 0), "the fundamental carries no line state");
        assertNull(r.lineState(0, 1), "the second tone carries none");
        assertNull(r.lineState(0, 0), "the pilot carries none");
        for (int i = 1; i < r.lineStates.size(); i++) {
            assertTrue(r.lineStates.get(i - 1).bin() <= r.lineStates.get(i).bin(), "in position order, entry " + i);
        }
    }

    @Test
    void anAverageWithoutAResettableLineStampsAnEmptyList() {
        CoherentAccumulator acc = new CoherentAccumulator();
        acc.restart(FFT_SIZE, true, 0.0, Double.NaN, null, null, LOBE_HALF, NO_PILOT, NO_PILOT);
        acc.add(ones(), zeros(), 1.0, FrameTurns.of(DELTA), null, null);
        FftResult r = overlaid(acc);
        assertNotNull(r.lineStates, "stamped");
        assertTrue(r.lineStates.isEmpty(), "no line a reset may name");
    }

    @Test
    void aRemoveAfterAResetKeepsANeverResetLineOnTheGlobalCount() {
        CoherentAccumulator acc = refinedSingleTone();
        assertTrue(acc.resetLine(2, 0), "H2 reset");
        double[] cRe = zeros(), cIm = zeros();
        acc.add(ones(), zeros(), 1.0, FrameTurns.of(DELTA), cRe, cIm);
        acc.add(ones(), zeros(), 1.0, FrameTurns.of(DELTA), null, null);
        acc.remove(cRe, cIm, 1.0);
        FftResult r = overlaid(acc);
        int global = CoherentAccumulator.KAPPA_MEAS_FRAMES + 1;
        assertEquals(global, r.frameCount, "the global frame count");
        assertEquals(global, r.lineState(3, 0).localCount(), "H3, never reset, on the global count");
        assertEquals(1, r.lineState(2, 0).localCount(), "H2: two frames since its reset, one taken out");
        double mean = 1.0 / acc.getFrames();
        for (int k = H3_LO; k <= H3_HI; k++) {
            assertEquals(acc.getSumRe()[k] * mean, r.re[k], 0.0, "H3's lobe over the global count, bin " + k);
        }
    }

    @Test
    void aCopyOfTheResultCarriesItsLineStates() {
        CoherentAccumulator acc = refinedSingleTone();
        assertTrue(acc.markLineWaiting(2, 0), "H2 marked");
        FftResult r = overlaid(acc);
        FftResult copy = r.deepCopy();
        assertEquals(r.lineStates, copy.lineStates, "the deep copy carries the line states");
        assertNotSame(r.lineStates, copy.lineStates, "in a list of its own");
        FftResult into = new FftResult();
        into.copyFrom(r);
        assertEquals(r.lineStates, into.lineStates, "the in-place copy carries them");
        assertNull(new FftResult().deepCopy().lineStates, "a result no accumulator stamped carries none");
    }

    @Test
    void aLinesStateIsRebuiltWithTheFamilyOnTheRefinedKappa() {
        // Pinned 0.2 bin above the tone: the refinement moves H3 from 301.2
        // (bins 299 .. 304) to 300.6 (bins 298 .. 303).
        CoherentAccumulator acc = new CoherentAccumulator();
        acc.restart(FFT_SIZE, true, LINE_KAPPA + 0.2, Double.NaN, null, null, LOBE_HALF, NO_PILOT, NO_PILOT);
        addToneFrames(acc, CoherentAccumulator.KAPPA_MEAS_FRAMES);
        assertTrue(acc.isKappaRefined(), "the kappa measurement closed");
        assertEquals(LINE_KAPPA, acc.getKappa(), 1e-9, "re-pinned on the tone");
        assertEquals(new LineState(3, 0, H3_BIN, CoherentAccumulator.KAPPA_MEAS_FRAMES, false, Double.NaN),
                overlaid(acc).lineState(3, 0), "H3 on the global count after the rebuild");
        double[] re = acc.getSumRe().clone();
        assertTrue(acc.resetLine(3, 0), "H3 reset");
        for (int k = H3_LO - 1; k <= H3_HI + 1; k++) {
            boolean lobe = k >= H3_LO && k <= H3_HI;
            assertEquals(lobe ? 0.0 : re[k], acc.getSumRe()[k], 0.0,
                    (lobe ? "the refined lobe emptied, bin " : "outside the refined lobe, bin ") + k);
        }
    }

    @Test
    void aLinesClearanceIsItsLevelOverItsLocalBandTopAndNaNWithoutOne() {
        FftResult r = new FftResult();
        assertTrue(Double.isNaN(r.lineClearanceDb(2, 0, -100.0)), "a result without line states");
        r.lineStates = List.of(new LineState(2, 0, H2_BIN, 10, false, -130.0),
                new LineState(3, 0, H3_BIN, 10, false, Double.NaN));
        assertEquals(30.0, r.lineClearanceDb(2, 0, -100.0), 0.0, "H2: 30 dB over its local band top");
        assertTrue(Double.isNaN(r.lineClearanceDb(3, 0, -100.0)), "H3: no local band top measured");
        assertTrue(Double.isNaN(r.lineClearanceDb(4, 0, -100.0)), "H4: no line state");
        assertTrue(Double.isNaN(overlaid(refinedSingleTone()).lineClearanceDb(2, 0, -100.0)),
                "stamped by an overlay with no local band laid out");
    }

    @Test
    void aResetLeavesTheBinsALowerNeighbourOwnsInASharedLobe() {
        // A fundamental at 5.2 bins with a 3-bin half-width: H1 spans 3 .. 8,
        // H2 at 10.4 spans 8 .. 13, H3 at 15.6 spans 13 .. 18.  The add turns
        // a shared bin by the lower line's angle, so H1 owns bin 8, H2 owns
        // 9 .. 13 and H3 owns 14 .. 18.
        double kappa = 5.2;
        int ownLo = 9;
        int ownHi = 13;
        CoherentAccumulator acc = new CoherentAccumulator();
        acc.restart(FFT_SIZE, true, kappa, Double.NaN, null, null, LOBE_HALF, NO_PILOT, NO_PILOT);
        for (int f = 0; f < CoherentAccumulator.KAPPA_MEAS_FRAMES; f++) {
            double[][] fr = toneFrame(kappa, f * DELTA);
            acc.add(fr[0], fr[1], 1.0, FrameTurns.of(f * DELTA), null, null);
        }
        assertTrue(acc.isKappaRefined(), "the kappa measurement closed");
        double[] re = acc.getSumRe().clone();
        double[] im = acc.getSumIm().clone();

        assertTrue(acc.resetLine(2, 0), "H2 reset");
        for (int k = 0; k <= FFT_SIZE / 2; k++) {
            boolean owned = k >= ownLo && k <= ownHi;
            String what = owned ? "H2's own bin emptied, bin " : "untouched, the shared bin 8 included, bin ";
            assertEquals(owned ? 0.0 : re[k], acc.getSumRe()[k], 0.0, what + k);
            assertEquals(owned ? 0.0 : im[k], acc.getSumIm()[k], 0.0, what + k);
        }

        double[] three = ones();
        Arrays.fill(three, 3.0);
        acc.add(three, zeros(), 1.0, FrameTurns.of(0.0), null, null);
        acc.add(three, zeros(), 1.0, FrameTurns.of(0.0), null, null);
        FftResult r = overlaid(acc);
        for (int k = ownLo; k <= ownHi; k++) {
            assertEquals(3.0, r.re[k], TOL_RAD, "H2's own bin over its local count of 2, bin " + k);
        }
        double mean = 1.0 / acc.getFrames();
        for (int k : new int[] { ownLo - 1, ownHi + 1 }) {
            assertEquals(acc.getSumRe()[k] * mean, r.re[k], 0.0, "a neighbour's bin over the global count, bin " + k);
        }
    }

    @Test
    void aLineResetAfterAFullResetOrBeforeTheRefinementIsRefusedNotThrown() {
        CoherentAccumulator acc = refinedSingleTone();
        acc.reset();
        assertFalse(acc.resetLine(2, 0), "dropped collection: the reset is refused");
        assertFalse(acc.markLineWaiting(2, 0), "dropped collection: the waiting mark is refused");
        assertThrows(IllegalArgumentException.class, () -> acc.resetLine(1, 0), "the fundamental, always");
        assertThrows(IllegalArgumentException.class, () -> acc.resetLine(0, 1), "the second tone, always");
        assertThrows(IllegalArgumentException.class, () -> acc.markLineWaiting(0, 0), "a pilot, always");

        acc.restart(FFT_SIZE, true, LINE_KAPPA, Double.NaN, null, null, LOBE_HALF, NO_PILOT, NO_PILOT);
        assertFalse(acc.resetLine(2, 0), "new collection, not refined: refused");
        assertFalse(acc.resetLine(21, 0), "a line the family lacks, not refined: refused");
        addToneFrames(acc, CoherentAccumulator.KAPPA_MEAS_FRAMES);
        assertTrue(acc.isKappaRefined(), "the kappa measurement closed");
        assertThrows(IllegalArgumentException.class, () -> acc.resetLine(21, 0), "refined: H21 is no family line");
        assertTrue(acc.resetLine(2, 0), "refined: H2 is reset");
    }

    @Test
    void aLineResetOnAnIncoherentAverageIsRefused() {
        CoherentAccumulator acc = new CoherentAccumulator();
        acc.restart(FFT_SIZE, false, LINE_KAPPA, Double.NaN, null, null, LOBE_HALF, NO_PILOT, NO_PILOT);
        for (int f = 0; f <= CoherentAccumulator.KAPPA_MEAS_FRAMES; f++) {
            acc.addPower(ones(), zeros(), 1.0, null);
        }
        double[] pow = acc.getSumPow().clone();
        assertFalse(acc.isKappaRefined(), "kappa is measured by the coherent add alone");
        assertFalse(acc.resetLine(2, 0), "the reset is refused");
        assertFalse(acc.markLineWaiting(2, 0), "the waiting mark is refused");
        assertArrayEquals(pow, acc.getSumPow(), "the power sums untouched");
    }

    @Test
    void theResultCarriesWhetherKappaIsRefined() {
        CoherentAccumulator measuring = new CoherentAccumulator();
        measuring.restart(FFT_SIZE, true, LINE_KAPPA, Double.NaN, null, null, LOBE_HALF, NO_PILOT, NO_PILOT);
        addToneFrames(measuring, CoherentAccumulator.KAPPA_MEAS_FRAMES - 1);
        assertFalse(overlaid(measuring).coherentKappaRefined, "still measuring");
        assertTrue(overlaid(refinedSingleTone()).coherentKappaRefined, "refined");
        FftResult r = overlaid(refinedSingleTone());
        assertTrue(r.deepCopy().coherentKappaRefined, "the copy carries it");
    }

    @Test
    void theStampHoldsTheHarmonicTableAndEveryProduct() {
        CoherentAccumulator acc = new CoherentAccumulator();
        acc.restart(FFT_SIZE, true, LINE_KAPPA, LINE_KAPPA2, new int[] { 0, 1, -1 }, new int[] { 1, 1, 1 },
                LOBE_HALF, PILOT_BIN, NO_PILOT);
        addToneFrames(acc, CoherentAccumulator.KAPPA_MEAS_FRAMES);
        // H5 lies beyond a table of H2, H3: no state is stamped for it, but a
        // reset of it still divides its lobe by its own count
        assertTrue(acc.resetLine(5, 0), "H5 reset");
        double[] three = ones();
        Arrays.fill(three, 3.0);
        acc.add(three, zeros(), 1.0, FrameTurns.of(0.0), null, null);
        acc.add(three, zeros(), 1.0, FrameTurns.of(0.0), null, null);
        FftResult r = overlaid(acc, 2);
        assertEquals(4, r.lineStates.size(), "H2, H3 and the products F2 - F1, F1 + F2");
        assertNotNull(r.lineState(2, 0), "H2");
        assertNotNull(r.lineState(3, 0), "H3");
        assertNull(r.lineState(4, 0), "H4 lies beyond the table");
        assertNull(r.lineState(5, 0), "H5 lies beyond the table");
        assertNotNull(r.lineState(1, 1), "F1 + F2");
        assertNotNull(r.lineState(-1, 1), "F2 - F1");
        int h5 = (int) Math.round(5 * LINE_KAPPA);
        assertEquals(3.0, r.re[h5], TOL_RAD, "H5's centre over its local count of 2");
    }

    @Test
    void aReusedResultSlotCarriesNoLineStateFromAnEarlierAverage() {
        int sampleRate = 48_000;
        int fftSize    = 8_192;
        double freqHz  = (double) sampleRate / fftSize * 50;
        double[] signal = new double[fftSize];
        for (int n = 0; n < signal.length; n++) {
            signal[n] = 0.5 * Math.sin(2.0 * Math.PI * freqHz * n / sampleRate);
        }
        int step = FftOverlap.PCT_0.hopSamples(fftSize);
        for (boolean spectrumOnly : new boolean[] { false, true }) {
            FftResult slot = new FftResult();
            slot.lineStates = List.of(new LineState(2, 0, H2_BIN, 1, true, Double.NaN));
            slot.coherentKappaRefined = true;
            FftAnalyzer analyzer = new FftAnalyzer();
            analyzer.setSpectrumOnly(spectrumOnly);
            FftResult r = analyzer.analyze(signal, sampleRate, fftSize, 8, 8,
                    WindowType.HANN, FftOverlap.PCT_0, 0.0, 0.0, true, Double.NaN, false, Double.NaN,
                    slot, step);
            String path = spectrumOnly ? "spectrum-only analysis" : "full analysis";
            assertNull(r.lineStates, path + ": no line states");
            assertFalse(r.coherentKappaRefined, path + ": not refined");
        }
    }

    /** The statistics column named {@code name}. */
    private int column(CoherentAccumulator acc, String name) {
        int c = acc.statisticsColumns().indexOf(name);
        assertTrue(c >= 0, "a column " + name);
        return c;
    }

    @Test
    void theStatisticsGrowOneRowPerFrameAndARestartOrAResetDropsThem() {
        CoherentAccumulator acc = refinedSingleTone();
        // beyond the store's first sizing of 64 rows
        int more = 100;
        for (int f = 0; f < more; f++) {
            acc.add(ones(), zeros(), 1.0, FrameTurns.of(DELTA), null, null);
        }
        int rows = CoherentAccumulator.KAPPA_MEAS_FRAMES + more;
        assertEquals(rows, acc.statisticsRowCount(), "one row per frame added");
        for (int row = 0; row < rows; row++) {
            assertEquals(row + 1, acc.statistic(row, 0), 0.0, "the global frame count after the add, row " + row);
        }

        acc.restart(FFT_SIZE, true, LINE_KAPPA, Double.NaN, null, null, LOBE_HALF, NO_PILOT, NO_PILOT);
        assertEquals(0, acc.statisticsRowCount(), "dropped by the restart");
        acc.add(ones(), zeros(), 1.0, FrameTurns.of(0.0), null, null);
        assertEquals(1, acc.statisticsRowCount(), "the new collection's first row");
        acc.reset();
        assertEquals(0, acc.statisticsRowCount(), "dropped by the reset");
        assertThrows(IndexOutOfBoundsException.class, () -> acc.statistic(0, 0), "no row to read");
    }

    @Test
    void theStatisticsFollowTheTonesH2ToH9AndEveryProduct() {
        CoherentAccumulator acc = new CoherentAccumulator();
        acc.restart(FFT_SIZE, true, LINE_KAPPA, LINE_KAPPA2, new int[] { 0, 1, -1 }, new int[] { 1, 1, 1 },
                LOBE_HALF, PILOT_BIN, NO_PILOT);
        List<String> columns = acc.statisticsColumns();
        // the frame count, four columns per tone, six per line: H2 .. H9 and
        // the products F2 - F1, F1 + F2
        assertEquals(1 + 4 * 2 + 6 * (8 + 2), columns.size(), "the columns");
        assertEquals(List.of("frames",
                "(1,0) raw", "(1,0) accumulated", "(1,0) raw phase", "(1,0) accumulated phase",
                "(0,1) raw", "(0,1) accumulated", "(0,1) raw phase", "(0,1) accumulated phase"),
                columns.subList(0, 9), "the count, then the tones");
        assertEquals(List.of("(-1,1) raw", "(-1,1) accumulated", "(-1,1) raw phase", "(-1,1) accumulated phase",
                "(-1,1) raw band top", "(-1,1) accumulated band top"), columns.subList(9, 15),
                "then the lines in position order, F2 - F1 at 159.8 first");
        assertTrue(columns.contains("(9,0) accumulated band top"), "H9");
        assertFalse(columns.contains("(10,0) raw"), "H10 lies beyond the statistics' harmonics");
        assertTrue(columns.contains("(1,1) raw phase"), "F1 + F2");
        assertFalse(columns.contains("(0,1) raw band top"), "a tone has no band top column");
        assertFalse(columns.stream().anyMatch(n -> n.startsWith("(0,0)")), "no pilot");
        assertThrows(UnsupportedOperationException.class, () -> columns.add("x"), "read-only");
    }

    @Test
    void theStatisticsHoldTheValuesTheAddSaw() {
        CoherentAccumulator acc = refinedSingleTone();
        assertTrue(acc.resetLine(3, 0), "H3 reset");
        double[] re = ones();
        double[] im = zeros();
        re[H2_BIN] = 3.0;
        im[H2_BIN] = 4.0;
        acc.add(re, im, 1.0, FrameTurns.of(DELTA), null, null);
        int    row    = acc.statisticsRowCount() - 1;
        double frames = acc.getFrames();
        double sRe    = acc.getSumRe()[H2_BIN];
        double sIm    = acc.getSumIm()[H2_BIN];
        int    k0     = (int) Math.round(LINE_KAPPA);
        assertEquals(frames, acc.statistic(row, 0), 0.0, "the global frame count");
        assertEquals(5.0, acc.statistic(row, column(acc, "(2,0) raw")), 1e-12, "H2's raw magnitude, |3 + 4i|");
        assertEquals(Math.hypot(sRe, sIm) / frames, acc.statistic(row, column(acc, "(2,0) accumulated")), 1e-12,
                "H2's accumulated magnitude over the global count");
        // the frame's phase turned back by H2's angle, 2 x the fundamental's
        double turned = Math.atan2(4.0, 3.0) + 2.0 * acc.fundamentalAngle(DELTA);
        assertAngle(turned, Math.toRadians(acc.statistic(row, column(acc, "(2,0) raw phase"))),
                "H2's raw phase, as the add turned it back");
        assertEquals(Math.toDegrees(Math.atan2(sIm, sRe)), acc.statistic(row, column(acc, "(2,0) accumulated phase")),
                1e-12, "H2's accumulated phase");
        assertTrue(Double.isNaN(acc.statistic(row, column(acc, "(2,0) raw band top"))), "no band laid out");
        assertTrue(Double.isNaN(acc.statistic(row, column(acc, "(2,0) accumulated band top"))), "no band laid out");
        assertEquals(1.0, acc.statistic(row, column(acc, "(3,0) accumulated")), 1e-12,
                "H3: the one frame since its reset over its local count of 1");
        assertEquals(acc.statistic(row, column(acc, "(3,0) accumulated phase")),
                acc.statistic(row, column(acc, "(3,0) raw phase")), 1e-12,
                "H3, one frame since its reset: the average's phase is that frame's turned phase");
        assertEquals(Math.hypot(acc.getSumRe()[k0], acc.getSumIm()[k0]) / frames,
                acc.statistic(row, column(acc, "(1,0) accumulated")), 1e-12, "the fundamental's accumulated magnitude");
    }

    @Test
    void theStatisticsFollowALineToItsPlaceOnTheRefinedKappa() {
        // Pinned 0.2 bin above the tone: H9 stands at 903.6 (bin 904) until
        // the refinement moves it to 901.8 (bin 902).
        CoherentAccumulator acc = new CoherentAccumulator();
        acc.restart(FFT_SIZE, true, LINE_KAPPA + 0.2, Double.NaN, null, null, LOBE_HALF, NO_PILOT, NO_PILOT);
        addToneFrames(acc, CoherentAccumulator.KAPPA_MEAS_FRAMES);
        assertTrue(acc.isKappaRefined(), "the kappa measurement closed");
        double[] re = ones();
        re[902] = 7.0;
        acc.add(re, zeros(), 1.0, FrameTurns.of(0.0), null, null);
        assertEquals(7.0, acc.statistic(acc.statisticsRowCount() - 1, column(acc, "(9,0) raw")), 1e-12,
                "H9 read at its refined centre bin 902");
        assertEquals(CoherentAccumulator.KAPPA_MEAS_FRAMES + 1, acc.statisticsRowCount(), "no row lost");
    }

    @Test
    void theExportWritesAHeaderAndOneRowPerFrameWithTheValuesTheAddSaw(@TempDir Path dir) throws IOException {
        CoherentAccumulator acc = refinedSingleTone();
        double[] re = ones();
        double[] im = zeros();
        re[H2_BIN] = 3.0;
        im[H2_BIN] = 4.0;
        acc.add(re, im, 1.0, FrameTurns.of(0.0), null, null);
        Path file = dir.resolve("stats.csv");
        acc.exportStatistics(file, 1e-3);

        List<String> lines = Files.readAllLines(file);
        int rows = acc.statisticsRowCount();
        assertEquals(rows + 1, lines.size(), "a header and one row per frame");
        String[] header = lines.get(0).split(";", -1);
        assertEquals(acc.statisticsColumns().size(), header.length, "every column named");
        assertEquals("frames", header[0], "the count");
        assertEquals("(2,0) raw dBFS", header[column(acc, "(2,0) raw")], "a magnitude column");
        assertEquals("(2,0) accumulated dBFS", header[column(acc, "(2,0) accumulated")], "a magnitude column");
        assertEquals("(2,0) raw phase deg", header[column(acc, "(2,0) raw phase")], "a phase column");
        assertEquals("(2,0) accumulated phase deg", header[column(acc, "(2,0) accumulated phase")], "a phase column");
        assertEquals("(2,0) raw band top dBFS", header[column(acc, "(2,0) raw band top")], "a band top column");
        assertEquals("(2,0) accumulated band top dBFS", header[column(acc, "(2,0) accumulated band top")],
                "a band top column");
        for (int i = 1; i < lines.size(); i++) {
            assertEquals(header.length, lines.get(i).split(";", -1).length, "every row carries every column, row " + i);
        }
        String[] last = lines.get(rows).split(";", -1);
        assertEquals("25,0000", last[0], "the frame count, decimal comma");
        assertEquals("-46,0206", last[column(acc, "(2,0) raw")], "|3 + 4i| x 1e-3 = 0.005, -46.0206 dBFS");
        assertEquals("", last[column(acc, "(2,0) accumulated band top")], "no band laid out: empty");
        int phase = column(acc, "(2,0) accumulated phase");
        assertEquals(acc.statistic(rows - 1, phase), Double.parseDouble(last[phase].replace(',', '.')), 1e-4,
                "the phase in degrees");
    }

    @Test
    void theIncoherentAverageRecordsNoStatistics() {
        CoherentAccumulator acc = new CoherentAccumulator();
        acc.restart(FFT_SIZE, false, LINE_KAPPA, Double.NaN, null, null, LOBE_HALF, NO_PILOT, NO_PILOT);
        for (int f = 0; f < 3; f++) {
            acc.addPower(ones(), zeros(), 1.0, null);
        }
        assertEquals(3.0, acc.getFrames(), 0.0, "three frames held");
        assertEquals(0, acc.statisticsRowCount(), "no row: a power average carries no phase");
    }

    @Test
    void aFrameWithoutAPinnedFundamentalRecordsTheFrameCountAlone() {
        CoherentAccumulator acc = new CoherentAccumulator();
        acc.restart(FFT_SIZE, true, 0.0, Double.NaN, null, null, LOBE_HALF, NO_PILOT, NO_PILOT);
        acc.add(ones(), zeros(), 2.0, FrameTurns.of(DELTA), null, null);
        assertEquals(List.of("frames"), acc.statisticsColumns(), "no line to follow");
        assertEquals(1, acc.statisticsRowCount(), "one row");
        assertEquals(2.0, acc.statistic(0, 0), 0.0, "the global frame count");
        assertThrows(IndexOutOfBoundsException.class, () -> acc.statistic(0, 1), "no column beyond the count");
    }

    @Test
    void aStatisticsLineTheRefinedFamilyLacksReadsNaN() {
        // F1 pinned 0.2 bin above the tone at 100.2, F2 at 99.3: the product
        // F1 - F2 stands at 1.1 bins on the pinned kappa and at 0.9 on the
        // refined one, below the family's lowest bin.
        CoherentAccumulator acc = new CoherentAccumulator();
        acc.restart(FFT_SIZE, true, LINE_KAPPA + 0.2, 99.3, new int[] { 0, 1 }, new int[] { 1, -1 },
                LOBE_HALF, NO_PILOT, NO_PILOT);
        addToneFrames(acc, CoherentAccumulator.KAPPA_MEAS_FRAMES);
        assertTrue(acc.isKappaRefined(), "the kappa measurement closed");
        int raw  = column(acc, "(1,-1) raw");
        int last = acc.statisticsRowCount() - 1;
        assertEquals(1.0, acc.statistic(0, raw), 1e-12, "before the refinement: read at bin 1");
        assertTrue(Double.isNaN(acc.statistic(last, raw)), "after it: the line is gone");
        assertTrue(Double.isNaN(acc.statistic(last, column(acc, "(1,-1) accumulated"))), "its accumulated too");
        assertTrue(Double.isNaN(acc.statistic(last, column(acc, "(1,-1) accumulated phase"))), "its phase too");
        assertEquals(1.0, acc.statistic(last, column(acc, "(2,0) raw")), 1e-12, "H2 still read");
    }

    @Test
    void aRestartFromASingleToADualToneLaysOutNewColumns() {
        CoherentAccumulator acc = refinedSingleTone();
        assertEquals(1 + 4 + 6 * 8, acc.statisticsColumns().size(), "single tone: the fundamental and H2 .. H9");
        assertFalse(acc.statisticsColumns().contains("(0,1) raw"), "no second tone");
        acc.restart(FFT_SIZE, true, LINE_KAPPA, LINE_KAPPA2, new int[] { 0, 1, -1 }, new int[] { 1, 1, 1 },
                LOBE_HALF, PILOT_BIN, NO_PILOT);
        List<String> columns = acc.statisticsColumns();
        assertEquals(1 + 4 * 2 + 6 * (8 + 2), columns.size(), "dual tone: two tones, H2 .. H9 and two products");
        assertTrue(columns.contains("(0,1) accumulated phase"), "the second tone");
        assertTrue(columns.contains("(1,1) accumulated band top"), "F1 + F2");
        assertEquals(0, acc.statisticsRowCount(), "no row of the single tone left");
        acc.add(ones(), zeros(), 1.0, FrameTurns.of(0.0), null, null);
        assertEquals(columns.size(), acc.statisticsColumns().size(), "the new layout holds");
        assertEquals(1.0, acc.statistic(0, column(acc, "(1,1) raw")), 1e-12, "a row on the new layout");
    }

    @Test
    void aResultWithoutAFundamentalCarriesNoRawPeaksFromAnEarlierCapture() {
        FftResult r = new FftResult();
        r.ensureArrays(FFT_SIZE / 2 + 1, 2);
        r.harmonicCount        = 2;
        r.freqResolution       = 1.0;
        r.fundamentalHzRefined = LINE_KAPPA;
        r.re[H2_BIN] = 3.0;
        r.captureRawPeaks();
        assertNotNull(r.rawPeakRe, "captured with a fundamental");
        assertFalse(Double.isNaN(r.rawFundRe), "the fundamental's phasor captured");

        // the same result reused for an analysis that placed no fundamental
        r.fundamentalHzRefined = Double.NaN;
        r.captureRawPeaks();
        assertNull(r.rawPeakRe, "no peaks of the earlier capture");
        assertNull(r.rawPeakIm, "no peaks of the earlier capture");
        assertTrue(Double.isNaN(r.rawFundRe), "no fundamental phasor of the earlier capture");
        assertTrue(Double.isNaN(r.rawFundIm), "no fundamental phasor of the earlier capture");
        FftResult copy = r.deepCopy();
        assertNull(copy.rawPeakRe, "the copy carries none either");
        assertTrue(Double.isNaN(copy.rawFundRe), "the copy carries none either");
    }

    /** A frame of seeded Gaussian noise, unit spread, in every bin. */
    private double[][] noiseFrame(Random random) {
        double[] re = zeros();
        double[] im = zeros();
        for (int k = 0; k < re.length; k++) {
            re[k] = random.nextGaussian();
            im[k] = random.nextGaussian();
        }
        return new double[][] { re, im };
    }

    /** A single-tone average on {@link #LINE_KAPPA} with a pilot, its local
     *  bands laid out at {@code binHz} right after the restart, its κ
     *  measurement closed. */
    private CoherentAccumulator refinedWithBands(double binHz) {
        CoherentAccumulator acc = new CoherentAccumulator();
        acc.restart(FFT_SIZE, true, LINE_KAPPA, Double.NaN, null, null, LOBE_HALF, PILOT_BIN, NO_PILOT);
        acc.lineBands(binHz, BAND_NENBW);
        addToneFrames(acc, CoherentAccumulator.KAPPA_MEAS_FRAMES);
        assertTrue(acc.isKappaRefined(), "the kappa measurement closed");
        return acc;
    }

    @Test
    void aResetLinesBandTopEqualsAFreshAverageOfTheSameFrames() {
        CoherentAccumulator acc = refinedWithBands(BAND_BIN_HZ);
        assertTrue(acc.resetLine(2, 0), "H2 reset");
        // the same geometry from the start, on the refined kappa
        CoherentAccumulator fresh = new CoherentAccumulator();
        fresh.restart(FFT_SIZE, true, acc.getKappa(), Double.NaN, null, null, LOBE_HALF, PILOT_BIN, NO_PILOT);
        fresh.lineBands(BAND_BIN_HZ, BAND_NENBW);
        Random random = new Random(NOISE_SEED);
        for (int f = 0; f < NOISE_FRAMES; f++) {
            double[][] fr = noiseFrame(random);
            acc.add(fr[0], fr[1], 1.0, FrameTurns.of(f * DELTA), null, null);
            fresh.add(fr[0], fr[1], 1.0, FrameTurns.of(f * DELTA), null, null);
        }
        LineState reset   = overlaid(acc).lineState(2, 0);
        LineState average = overlaid(fresh).lineState(2, 0);
        assertEquals(NOISE_FRAMES, reset.localCount(), "H2 counts the frames since its reset");
        assertTrue(Double.isFinite(reset.localBandTopDbFs()), "a band top measured");
        assertEquals(average.localBandTopDbFs(), reset.localBandTopDbFs(), 0.0,
                "the band top of the frames since the reset alone");
        assertTrue(overlaid(acc).lineState(3, 0).localBandTopDbFs() != reset.localBandTopDbFs(),
                "H3, never reset, holds the tone frames as well");
    }

    /** The overlay indexes every line's state by its centre bin: a reader
     *  holding the bin - the display's line-floor hold - gets the line's own
     *  band top, which after the line's reset is not the band beside it in
     *  the overlaid spectrum (that band holds every frame of the collection). */
    @Test
    void aLinesOwnBandTopIsFoundByItsBin() {
        CoherentAccumulator acc = refinedWithBands(BAND_BIN_HZ);
        assertTrue(acc.resetLine(2, 0), "H2 reset");
        Random random = new Random(NOISE_SEED);
        for (int f = 0; f < NOISE_FRAMES; f++) {
            double[][] fr = noiseFrame(random);
            acc.add(fr[0], fr[1], 1.0, FrameTurns.of(f * DELTA), null, null);
        }
        FftResult r  = overlaid(acc);
        LineState h2 = r.lineState(2, 0);
        assertTrue(Double.isFinite(h2.localBandTopDbFs()), "a band top measured");
        assertEquals(h2.localBandTopDbFs(), r.localBandTopDbFs(h2.bin()), 0.0, "H2's own band top by its bin");
        assertEquals(h2.localBandTopDbFs(), r.deepCopy().localBandTopDbFs(h2.bin()), 0.0, "the copy carries the index");
        double spectrumFloor = 20.0 * Math.log10(new ToneLobeLift().lineFloor(
                k -> Math.pow(10.0, r.amplitudeDbFs[k] / 20.0), h2.bin(), FFT_SIZE / 2, BAND_BIN_HZ, BAND_NENBW));
        assertTrue(h2.localBandTopDbFs() != spectrumFloor, "not the band beside H2 in the overlaid spectrum");
        assertTrue(Double.isNaN(r.localBandTopDbFs(0)), "no line on the DC bin");
    }

    @Test
    void aNeverResetLinesBandTopIsTheLineFloorOfTheOverlay() {
        CoherentAccumulator acc = refinedWithBands(BAND_BIN_HZ);
        Random random = new Random(NOISE_SEED);
        double[][] fr = null;
        for (int f = 0; f < NOISE_FRAMES; f++) {
            fr = noiseFrame(random);
            acc.add(fr[0], fr[1], 1.0, FrameTurns.of(f * DELTA), null, null);
        }
        FftResult    r     = overlaid(acc);
        ToneLobeLift lobe  = new ToneLobeLift();
        int          half  = FFT_SIZE / 2;
        double expected = lobe.lineFloor(k -> Math.pow(10.0, r.amplitudeDbFs[k] / 20.0), H3_BIN, half,
                BAND_BIN_HZ, BAND_NENBW);
        double top = r.lineState(3, 0).localBandTopDbFs();
        assertEquals(20.0 * Math.log10(expected), top, 1e-9, "lineFloor over the averaged spectrum");

        int    row  = acc.statisticsRowCount() - 1;
        double conv = r.amplitudePerMagnitude();
        assertEquals(top, 20.0 * Math.log10(acc.statistic(row, column(acc, "(3,0) accumulated band top")) * conv),
                1e-9, "the statistics' accumulated band top is the overlay's");
        final double[][] last = fr;
        double raw = lobe.lineFloor(k -> Math.hypot(last[0][k], last[1][k]), H3_BIN, half, BAND_BIN_HZ, BAND_NENBW);
        assertEquals(raw, acc.statistic(row, column(acc, "(3,0) raw band top")), raw * 1e-12,
                "the raw band top over the frame's own band bins");
    }

    @Test
    void theBandsLeaveTheGlobalSumsBitIdentical() {
        CoherentAccumulator banded = refinedWithBands(BAND_BIN_HZ);
        CoherentAccumulator plain  = new CoherentAccumulator();
        plain.restart(FFT_SIZE, true, LINE_KAPPA, Double.NaN, null, null, LOBE_HALF, PILOT_BIN, NO_PILOT);
        addToneFrames(plain, CoherentAccumulator.KAPPA_MEAS_FRAMES);
        Random random = new Random(NOISE_SEED);
        double[] cRe = zeros(), cIm = zeros();
        for (int f = 0; f < NOISE_FRAMES; f++) {
            double[][] fr = noiseFrame(random);
            banded.add(fr[0], fr[1], 1.0, FrameTurns.of(f * DELTA), cRe, cIm);
            plain.add(fr[0], fr[1], 1.0, FrameTurns.of(f * DELTA), null, null);
        }
        banded.scale(0.5);
        plain.scale(0.5);
        assertArrayEquals(plain.getSumRe(), banded.getSumRe(), "the global sums, real parts");
        assertArrayEquals(plain.getSumIm(), banded.getSumIm(), "the global sums, imaginary parts");
        assertArrayEquals(overlaid(plain).amplitudeDbFs, overlaid(banded).amplitudeDbFs, "the overlay's spectrum");
        assertTrue(Double.isNaN(overlaid(plain).lineState(2, 0).localBandTopDbFs()), "no band without lineBands");
    }

    @Test
    void neighbouringBandsShareBinsAndEachKeepsItsOwnSums() {
        CoherentAccumulator acc = refinedWithBands(OVERLAP_BIN_HZ);
        Random random = new Random(NOISE_SEED);
        for (int f = 0; f < NOISE_FRAMES; f++) {
            double[][] fr = noiseFrame(random);
            acc.add(fr[0], fr[1], 1.0, FrameTurns.of(f * DELTA), null, null);
        }
        double h3Before = overlaid(acc).lineState(3, 0).localBandTopDbFs();
        assertTrue(Double.isFinite(h3Before), "H3's band top measured");
        assertTrue(acc.resetLine(2, 0), "H2 reset");
        FftResult r = overlaid(acc);
        assertEquals(h3Before, r.lineState(3, 0).localBandTopDbFs(), 0.0,
                "H3's band, sharing bins 201 .. 300 with H2's, keeps its sums");
        assertTrue(Double.isNaN(r.lineState(2, 0).localBandTopDbFs()), "H2's band is empty until its next frame");
        double[][] fr = noiseFrame(random);
        acc.add(fr[0], fr[1], 1.0, FrameTurns.of(0.0), null, null);
        assertTrue(Double.isFinite(overlaid(acc).lineState(2, 0).localBandTopDbFs()), "H2's band from one frame");
    }

    @Test
    void aRestartDropsTheBands() {
        CoherentAccumulator acc = refinedWithBands(BAND_BIN_HZ);
        assertTrue(Double.isFinite(overlaid(acc).lineState(2, 0).localBandTopDbFs()), "a band laid out");
        acc.restart(FFT_SIZE, true, LINE_KAPPA, Double.NaN, null, null, LOBE_HALF, PILOT_BIN, NO_PILOT);
        addToneFrames(acc, CoherentAccumulator.KAPPA_MEAS_FRAMES);
        int row = acc.statisticsRowCount() - 1;
        assertTrue(Double.isNaN(overlaid(acc).lineState(2, 0).localBandTopDbFs()), "dropped by the restart");
        assertTrue(Double.isNaN(acc.statistic(row, column(acc, "(2,0) accumulated band top"))),
                "and in the statistics");
        assertTrue(Double.isNaN(acc.statistic(row, column(acc, "(2,0) raw band top"))), "the raw one too");
        assertTrue(acc.resetLine(2, 0), "a reset with no band laid out");
    }
}
