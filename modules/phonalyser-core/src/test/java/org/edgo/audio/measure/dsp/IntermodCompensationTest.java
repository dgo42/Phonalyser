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

import org.edgo.audio.measure.dsp.IntermodCompensation.ProductPhasors;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.test.AbstractReflectiveTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The correction grid of the intermodulation accumulator, the clearance its
 * measurement carries per product, and the apply gate on that clearance.  The
 * grid is the IMD readout's eight products: f2 - f1 and f1 + f2, then
 * (n-1)*f1 - (n-2)*f2 and (n-1)*f2 - (n-2)*f1 for n = 3 .. 5; a product's
 * clearance is its raw level over the noise band
 * beside its bin; only a product at least 5 dB over that band moves its
 * correction, and a product held by the gate keeps its correction while its
 * frequency is still taken.
 */
class IntermodCompensationTest extends AbstractReflectiveTest {

    private static final double F1_HZ = 1000.0;
    private static final double F2_HZ = 1100.0;
    /** The grid's (a, b) pairs of a*f1 + b*f2, in grid order. */
    private static final int[]  GRID_A = { -1, 1, 2, -1, 3, -2, 4, -3 };
    private static final int[]  GRID_B = { 1, 1, -1, 2, -2, 3, -3, 4 };
    /** The index n of the grid's first and last sideband pair, after the
     *  second-order pair f2 - f1, f1 + f2. */
    private static final int    FIRST_SIDEBAND = 3;
    private static final int    LAST_SIDEBAND  = 5;

    /** The measured result: 1 Hz bins, a flat noise floor, and one product
     *  in the analyzer's product grid - 2 F1 - F2 at its bin. */
    private static final double BIN_HZ               = 1.0;
    private static final int    SPECTRUM_BINS        = 8192;
    /** Hann's equivalent noise bandwidth in bins. */
    private static final double NENBW_BINS           = 1.5;
    private static final double FLOOR_DBFS           = -140.0;
    private static final double PRODUCT_DBFS         = -100.0;
    private static final int    PRODUCT_A            = 2;
    private static final int    PRODUCT_B            = -1;
    /** On a flat floor the upper border of the noise band is the floor
     *  itself (median + 4 MAD with a MAD of 0). */
    private static final double PRODUCT_CLEARANCE_DB = PRODUCT_DBFS - FLOOR_DBFS;
    private static final double CLEARANCE_TOLERANCE_DB = 1e-9;

    /** The gate readings: 2 F1 - F2 is the product that moves, 2 F2 - F1 the
     *  one the gate holds. */
    private static final double AT_GATE_DB    = 5.0;
    private static final double UNDER_GATE_DB = 4.9;
    private static final double CLEAR_DB      = 40.0;
    private static final int    MOVED_A       = 2;
    private static final int    MOVED_B       = -1;
    private static final int    HELD_A        = -1;
    private static final int    HELD_B        = 2;
    /** Every grid product is read at this level, each at its own phase, so no
     *  two entries coincide. */
    private static final double READING_LEVEL = 1.0e-6;
    private static final double READING_RAD   = 0.7;
    /** The held product read a quarter hertz off its nominal frequency, so
     *  the frequency write is visible beside the held correction. */
    private static final double SHIFT_HZ      = 0.25;
    private static final double STEP          = 1.0;
    /** Far under the 1e-6 a second step adds, far over the rounding of a
     *  1e-6 amplitude. */
    private static final double AMPLITUDE_TOLERANCE = 1e-18;
    /** The accumulator's private state, by name because that is what
     *  reflection takes: each grid slot's correction phasor and the
     *  frequency the slot was last measured at. */
    private static final String ACC_RE  = "accRe";
    private static final String ACC_IM  = "accIm";
    private static final String FREQ_HZ = "freqHz";

    @Test
    void theGridIsTheImdReadoutsEightProductsInOrder() {
        ProductPhasors grid = new IntermodCompensation().grid(F1_HZ, F2_HZ);
        assertArrayEquals(GRID_A, grid.toneOneMultiples(), "a of a*f1 + b*f2, in grid order");
        assertArrayEquals(GRID_B, grid.toneTwoMultiples(), "b of a*f1 + b*f2, in grid order");
        assertEquals(F2_HZ - F1_HZ, grid.hz()[0], 0.0, "d2L, f2 - f1");
        assertEquals(F1_HZ + F2_HZ, grid.hz()[1], 0.0, "d2H, f1 + f2");
        for (int n = FIRST_SIDEBAND; n <= LAST_SIDEBAND; n++) {
            int lower = 2 * (n - FIRST_SIDEBAND + 1);
            assertEquals((n - 1) * F1_HZ - (n - 2) * F2_HZ, grid.hz()[lower], 0.0,
                    "(n-1)*f1 - (n-2)*f2 below F1, n = " + n);
            assertEquals((n - 1) * F2_HZ - (n - 2) * F1_HZ, grid.hz()[lower + 1], 0.0,
                    "(n-1)*f2 - (n-2)*f1 above F2, n = " + n);
        }
    }

    @Test
    void aProductWithABinCarriesItsClearanceAndOneWithoutCarriesNaN() {
        ProductPhasors z = new IntermodCompensation().measurePhasors(result(), F1_HZ, F2_HZ, 0.0, 0.0);
        int published = slotOf(z, PRODUCT_A, PRODUCT_B);
        assertEquals(PRODUCT_A * F1_HZ + PRODUCT_B * F2_HZ, z.hz()[published], 0.0, "2 F1 - F2 is measured");
        assertEquals(PRODUCT_CLEARANCE_DB, z.clearanceDb()[published], CLEARANCE_TOLERANCE_DB,
                "2 F1 - F2 stands its raw level over the flat floor");
        for (int g = 0; g < z.hz().length; g++) {
            if (g == published) continue;
            String name = "(" + z.toneOneMultiples()[g] + ", " + z.toneTwoMultiples()[g] + ")";
            assertEquals(0.0, z.hz()[g], 0.0, name + " has no bin on the result");
            assertTrue(Double.isNaN(z.clearanceDb()[g]), name + " without a bin reads NaN");
        }
    }

    @Test
    void aResultWithoutASpectrumCarriesNoClearance() {
        FftResult r = result();
        r.amplitudeDbFs = null;
        ProductPhasors z = new IntermodCompensation().measurePhasors(r, F1_HZ, F2_HZ, 0.0, 0.0);
        int published = slotOf(z, PRODUCT_A, PRODUCT_B);
        assertTrue(z.hz()[published] > 0.0, "2 F1 - F2 is still measured");
        assertTrue(Double.isNaN(z.clearanceDb()[published]), "no spectrum, no clearance");
    }

    @Test
    void aProductAtFiveDbMovesAndOneAtFourPointNineDoesNot() {
        IntermodCompensation comp = new IntermodCompensation();
        ProductPhasors z = reading(AT_GATE_DB, UNDER_GATE_DB, 0.0);
        comp.accumulate(z, STEP);
        double[] accRe = field(comp, ACC_RE);
        double[] accIm = field(comp, ACC_IM);

        // 2 F2 - F1 at 4.9 dB and every product without a clearance stay.
        int moved = slotOf(z, MOVED_A, MOVED_B);
        for (int g = 0; g < accRe.length; g++) {
            if (g == moved) continue;
            String name = "(" + z.toneOneMultiples()[g] + ", " + z.toneTwoMultiples()[g] + ")";
            assertEquals(0.0, accRe[g], 0.0, name + " stays, real part");
            assertEquals(0.0, accIm[g], 0.0, name + " stays, imaginary part");
        }
        assertEquals(z.re()[moved], accRe[moved], 0.0, "the full step takes the reading, real part");
        assertEquals(z.im()[moved], accIm[moved], 0.0, "the full step takes the reading, imaginary part");
    }

    @Test
    void aGatedOutProductKeepsItsCorrectionAndTakesTheNewFrequency() {
        IntermodCompensation comp = new IntermodCompensation();
        comp.accumulate(reading(CLEAR_DB, CLEAR_DB, 0.0), STEP);
        double[] reBefore = this.<double[]>field(comp, ACC_RE).clone();
        double[] imBefore = this.<double[]>field(comp, ACC_IM).clone();
        double[] hzBefore = this.<double[]>field(comp, FREQ_HZ).clone();

        comp.accumulate(reading(CLEAR_DB, UNDER_GATE_DB, SHIFT_HZ), STEP);
        double[] reAfter = field(comp, ACC_RE);
        double[] imAfter = field(comp, ACC_IM);
        double[] hzAfter = field(comp, FREQ_HZ);

        ProductPhasors grid = comp.grid(F1_HZ, F2_HZ);
        int held = slotOf(grid, HELD_A, HELD_B);
        assertEquals(reBefore[held], reAfter[held], 0.0, "2 F2 - F1 under the gate keeps its correction, real part");
        assertEquals(imBefore[held], imAfter[held], 0.0,
                "2 F2 - F1 under the gate keeps its correction, imaginary part");
        assertNotEquals(hzBefore[held], hzAfter[held], "the frequency moved");
        assertEquals(HELD_A * F1_HZ + HELD_B * F2_HZ + SHIFT_HZ, hzAfter[held], 0.0,
                "2 F2 - F1 stores the frequency it was read at");
        int moved = slotOf(grid, MOVED_A, MOVED_B);
        assertEquals(2.0 * Math.hypot(reBefore[moved], imBefore[moved]), Math.hypot(reAfter[moved], imAfter[moved]),
                AMPLITUDE_TOLERANCE, "2 F1 - F2 over the gate takes the second step");
    }

    /** A dual-tone result with only 2 F1 - F2 in the analyzer's product
     *  grid, at its bin and PRODUCT_DBFS, on a flat FLOOR_DBFS spectrum;
     *  both tones at phase 0. */
    private FftResult result() {
        FftResult r = new FftResult();
        r.freqResolution   = BIN_HZ;
        r.windowNenbwBins  = NENBW_BINS;
        r.fundamentalBin   = (int) Math.round(F1_HZ / BIN_HZ);
        r.dacFundPhaseRad  = 0.0;
        r.dacFund2PhaseRad = 0.0;
        r.re               = new double[SPECTRUM_BINS];
        r.im               = new double[SPECTRUM_BINS];
        r.amplitudeDbFs    = new double[SPECTRUM_BINS];
        Arrays.fill(r.amplitudeDbFs, FLOOR_DBFS);
        int bin = (int) Math.round((PRODUCT_A * F1_HZ + PRODUCT_B * F2_HZ) / BIN_HZ);
        r.amplitudeDbFs[bin] = PRODUCT_DBFS;
        r.imdProductA      = new int[] { PRODUCT_A };
        r.imdProductB      = new int[] { PRODUCT_B };
        r.imdProductBin    = new int[] { bin };
        r.rawPeakRe        = new double[] { Math.pow(10.0, PRODUCT_DBFS / 20.0) };
        r.rawPeakIm        = new double[] { 0.0 };
        return r;
    }

    /** A reading of every grid product, each at its own phase and at its
     *  nominal frequency; no clearance except for the two gate products,
     *  the held one read {@code heldShiftHz} off its nominal frequency. */
    private ProductPhasors reading(double movedClearanceDb, double heldClearanceDb, double heldShiftHz) {
        ProductPhasors grid = new IntermodCompensation().grid(F1_HZ, F2_HZ);
        int products = grid.hz().length;
        double[] re = new double[products];
        double[] im = new double[products];
        double[] hz = grid.hz().clone();
        double[] clearance = new double[products];
        Arrays.fill(clearance, Double.NaN);
        for (int g = 0; g < products; g++) {
            re[g] = READING_LEVEL * Math.cos(READING_RAD * (g + 1));
            im[g] = READING_LEVEL * Math.sin(READING_RAD * (g + 1));
        }
        clearance[slotOf(grid, MOVED_A, MOVED_B)] = movedClearanceDb;
        int held = slotOf(grid, HELD_A, HELD_B);
        clearance[held] = heldClearanceDb;
        hz[held] += heldShiftHz;
        return new ProductPhasors(grid.toneOneMultiples(), grid.toneTwoMultiples(), re, im, hz, clearance);
    }

    /** Grid slot of product {@code (a, b)} in {@code z}. */
    private int slotOf(ProductPhasors z, int a, int b) {
        for (int g = 0; g < z.toneOneMultiples().length; g++) {
            if (z.toneOneMultiples()[g] == a && z.toneTwoMultiples()[g] == b) return g;
        }
        throw new AssertionError("(" + a + ", " + b + ") is not on the grid");
    }
}
