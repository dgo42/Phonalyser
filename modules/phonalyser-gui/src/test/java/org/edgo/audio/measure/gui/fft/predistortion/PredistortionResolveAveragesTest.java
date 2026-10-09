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

package org.edgo.audio.measure.gui.fft.predistortion;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.fft.ImdResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The round's resolution rule: it holds while no distortion line stands 5 dB
 * over its band-top floor and the distortion stands above half the target,
 * and its count is the averages the strongest line needs to stand that far
 * over, the floor falling with sqrt(N).
 * The lines are the harmonics for a single tone and the intermodulation
 * products for a dual tone.  Every line's floor sits at -180 dBFS in the
 * result's line-floor table, so every clearance is exact.
 */
class PredistortionResolveAveragesTest {

    /** H2..H9. */
    private static final int HARMONICS = 8;
    private static final int FUND_BIN = 250;
    private static final double BIN_HZ = 1.0;
    /** The tones of the dual-tone results; every product lands on a bin of
     *  its own. */
    private static final double F1_HZ = 1000.0;
    private static final double F2_HZ = 1300.0;
    private static final double FLOOR_DBFS = -180.0;
    private static final double TARGET_PCT = 1.0e-6;
    /** Above half the target - the rule applies. */
    private static final double ABOVE_HALF_PCT = 0.9e-6;

    private final PredistortionEngine engine = new PredistortionEngine(null, null, null, null, null);

    /** A single-tone result: H2..H9 at the given levels on their floors'
     *  table, averaged over {@code frames} frames. */
    private FftResult singleTone(int frames, double... harmonicDbFs) {
        FftResult r = new FftResult();
        r.freqResolution = BIN_HZ;
        r.frameCount = frames;
        r.harmonicCount = HARMONICS;
        r.harmonicBins = new int[HARMONICS];
        r.harmonicDbFs = harmonicDbFs.clone();
        for (int h = 0; h < HARMONICS; h++) {
            r.harmonicBins[h] = (h + 2) * FUND_BIN;
        }
        floorTable(r, r.harmonicBins);
        return r;
    }

    /** A dual-tone result: every product of orders 2..5 on its floor except
     *  where {@code lowerDbFs} / {@code upperDbFs} set a level (NaN = on the
     *  floor), and the F1 harmonics at {@code harmonicDbFs} above theirs. */
    private FftResult dualTone(int frames, double harmonicDbFs, double[] lowerDbFs, double[] upperDbFs) {
        FftResult r = singleTone(frames, harmonicDbFs, harmonicDbFs, harmonicDbFs, harmonicDbFs,
                harmonicDbFs, harmonicDbFs, harmonicDbFs, harmonicDbFs);
        ImdResult imd = new ImdResult();
        int[] bins = Arrays.copyOf(r.harmonicBins, HARMONICS + 2 * (ImdResult.MAX_ORDER - 1));
        int n = HARMONICS;
        for (int k = 2; k <= ImdResult.MAX_ORDER; k++) {
            imd.dnLHz[k] = k == 2 ? F2_HZ - F1_HZ : (k - 1) * F1_HZ - (k - 2) * F2_HZ;
            imd.dnHHz[k] = k == 2 ? F2_HZ + F1_HZ : (k - 1) * F2_HZ - (k - 2) * F1_HZ;
            imd.dnLDbFs[k] = Double.isNaN(lowerDbFs[k]) ? FLOOR_DBFS : lowerDbFs[k];
            imd.dnHDbFs[k] = Double.isNaN(upperDbFs[k]) ? FLOOR_DBFS : upperDbFs[k];
            bins[n++] = (int) Math.round(imd.dnLHz[k] / BIN_HZ);
            bins[n++] = (int) Math.round(imd.dnHHz[k] / BIN_HZ);
        }
        r.imd = imd;
        floorTable(r, bins);
        return r;
    }

    private void floorTable(FftResult r, int[] bins) {
        r.lineFloorBins = bins.clone();
        r.lineFloorDbFs = new double[bins.length];
        Arrays.fill(r.lineFloorDbFs, FLOOR_DBFS);
    }

    /** Products of orders 2..5 all on the floor. */
    private double[] onFloor() {
        double[] levels = new double[ImdResult.MAX_ORDER + 1];
        Arrays.fill(levels, Double.NaN);
        return levels;
    }

    private void tone(boolean dual) throws Exception {
        Field dualTone = PredistortionEngine.class.getDeclaredField("dualTone");
        dualTone.setAccessible(true);
        dualTone.setBoolean(engine, dual);
    }

    private boolean holds(FftResult r, double distPct, boolean dual) throws Exception {
        tone(dual);
        Method m = PredistortionEngine.class.getDeclaredMethod("resolutionHolds",
                FftResult.class, double.class, double.class);
        m.setAccessible(true);
        return (boolean) m.invoke(engine, r, distPct, TARGET_PCT);
    }

    private int count(FftResult r, int done, boolean dual) throws Exception {
        tone(dual);
        Method m = PredistortionEngine.class.getDeclaredMethod("averagesToResolve", FftResult.class, int.class);
        m.setAccessible(true);
        return (int) m.invoke(engine, r, done);
    }

    @Test
    void aHarmonicHeldOnItsFloorNeedsTheWholeFiveDecibels() throws Exception {
        // 1500 frames, every harmonic on its floor: 5 dB more takes
        // 1500 x (10^0.5 - 1) = 3243.4 frames more.
        FftResult r = singleTone(1500, FLOOR_DBFS, FLOOR_DBFS, FLOOR_DBFS, FLOOR_DBFS,
                FLOOR_DBFS, FLOOR_DBFS, FLOOR_DBFS, FLOOR_DBFS);
        assertTrue(holds(r, ABOVE_HALF_PCT, false));
        assertEquals(1500 + 3244, count(r, 1500, false));
    }

    @Test
    void theStrongestHarmonicSetsTheMissingClearance() throws Exception {
        // H2 2 dB over its floor is the strongest; 3 dB more takes
        // 1000 x (10^0.3 - 1) = 995.3 frames more.
        FftResult r = singleTone(1000, FLOOR_DBFS + 2.0, FLOOR_DBFS + 1.0, FLOOR_DBFS, FLOOR_DBFS,
                FLOOR_DBFS, FLOOR_DBFS, FLOOR_DBFS, FLOOR_DBFS);
        assertTrue(holds(r, ABOVE_HALF_PCT, false));
        assertEquals(1000 + 996, count(r, 1000, false));
    }

    @Test
    void theRuleStopsHoldingOnceAnyHarmonicIsFiveDecibelsClear() throws Exception {
        FftResult r = singleTone(1000, FLOOR_DBFS, FLOOR_DBFS, FLOOR_DBFS, FLOOR_DBFS + 5.0,
                FLOOR_DBFS, FLOOR_DBFS, FLOOR_DBFS, FLOOR_DBFS);
        assertFalse(holds(r, ABOVE_HALF_PCT, false));
    }

    @Test
    void theRuleStopsHoldingAtOrUnderHalfTheTarget() throws Exception {
        FftResult r = singleTone(1000, FLOOR_DBFS, FLOOR_DBFS, FLOOR_DBFS, FLOOR_DBFS,
                FLOOR_DBFS, FLOOR_DBFS, FLOOR_DBFS, FLOOR_DBFS);
        assertFalse(holds(r, 0.5 * TARGET_PCT, false));
    }

    @Test
    void aDualToneWatchesTheIntermodProductsNotTheHarmonics() throws Exception {
        // The F1 harmonics stand 6 dB clear but do not count; the strongest
        // product, 2f1 - f2 1 dB over its floor, needs 4 dB more:
        // 1000 x (10^0.4 - 1) = 1511.9 frames more.
        double[] lower = onFloor();
        lower[3] = FLOOR_DBFS + 1.0;
        FftResult r = dualTone(1000, FLOOR_DBFS + 6.0, lower, onFloor());
        assertTrue(holds(r, ABOVE_HALF_PCT, true));
        assertEquals(1000 + 1512, count(r, 1000, true));
    }

    @Test
    void theRuleStopsHoldingOnceAnIntermodProductIsFiveDecibelsClear() throws Exception {
        double[] upper = onFloor();
        upper[2] = FLOOR_DBFS + 5.0;
        FftResult r = dualTone(1000, FLOOR_DBFS, onFloor(), upper);
        assertFalse(holds(r, ABOVE_HALF_PCT, true));
    }
}
