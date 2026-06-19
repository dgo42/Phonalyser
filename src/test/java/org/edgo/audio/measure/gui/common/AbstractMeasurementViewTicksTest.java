/*
 * Phonalyser — precision audio measurement workbench.
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

package org.edgo.audio.measure.gui.common;

import org.edgo.audio.measure.enums.MagnitudeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the single source of truth for axis-tick math, now consolidated on
 * {@link AbstractMeasurementView} (the FFT and FreqResp views share it; the
 * former standalone {@code FftAxisTicks} / {@code FreqRespAxisTicks} copies are
 * gone).  Pure-math static methods — no SWT widget is instantiated.
 */
class AbstractMeasurementViewTicksTest {

    @Test
    void niceLinearMajors_picksRoundStep() {
        double[] t = AbstractMeasurementView.niceLinearMajors(0.0, 100.0, 10);
        assertTrue(t.length >= 9);
        assertEquals(0.0, t[0], 1e-9);
        assertEquals(10.0, t[1] - t[0], 1e-9);     // 1 / 2 / 2.5 / 5 × 10ⁿ family → 10
    }

    @Test
    void niceLinearMajors_subDecadeZoom_givesManyFineTicks() {
        // The sub-decade improvement: a 990–1010 Hz window must produce several
        // round ticks (≤ 2 Hz steps), not the single "1000" decade gridpoint.
        double[] t = AbstractMeasurementView.niceLinearMajors(990.0, 1010.0, 12);
        assertTrue(t.length >= 5, "expected many fine ticks, got " + t.length);
        assertTrue(t[1] - t[0] <= 5.0, "step too coarse: " + (t[1] - t[0]));
    }

    @Test
    void logMajorTicks_areDecadeBoundaries() {
        assertArrayEquals(new double[]{1, 10, 100},
                AbstractMeasurementView.logMajorTicks(1.0, 100.0), 1e-9);
    }

    @Test
    void logMinorTicks_areSubDecadeOnly() {
        double[] t = AbstractMeasurementView.logMinorTicks(1.0, 100.0);
        assertTrue(t.length >= 16);
        for (double v : t) {                        // never a 1 × 10ⁿ decade boundary
            double base = Math.pow(10, Math.floor(Math.log10(v)));
            assertNotEquals(1.0, v / base, 1e-9);
        }
    }

    @Test
    void adaptiveLogLabels_thinPerDecadeAsRangeWidens() {
        double[] oneDecade   = AbstractMeasurementView.adaptiveLogLabels(1.0, 10.0);
        double[] fiveDecades = AbstractMeasurementView.adaptiveLogLabels(1.0, 100_000.0);
        assertTrue(countIn(oneDecade, 1.0, 9.999) > countIn(fiveDecades, 1.0, 9.999),
                "a 1-decade view should label more sub-decade positions than a 5-decade view");
    }

    @Test
    void isLog_trueForLinearVoltageUnits() {
        assertTrue(MagnitudeUnit.V.isLog());
        assertTrue(MagnitudeUnit.V_SQRT_HZ.isLog());
        assertFalse(MagnitudeUnit.DBV.isLog());
        assertFalse(MagnitudeUnit.DBFS.isLog());
    }

    private static int countIn(double[] a, double lo, double hi) {
        int n = 0;
        for (double v : a) if (v >= lo && v <= hi) n++;
        return n;
    }
}
