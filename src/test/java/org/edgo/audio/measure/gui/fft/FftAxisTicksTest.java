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

package org.edgo.audio.measure.gui.fft;

import org.edgo.audio.measure.enums.FftMagnitudeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link FftAxisTicks} — pure-math tick calculators for the
 * FFT chart axes.  Anchors the magnitude / frequency tick spacing so a
 * regression here would show up as a visibly wrong grid.
 */
class FftAxisTicksTest {

    @Test
    void niceLinear_returnsAscendingTicksWithinRange() {
        double[] ticks = FftAxisTicks.niceLinear(0.0, 100.0, 10);
        assertTrue(ticks.length >= 5, "≥5 ticks for span/count=10/10");
        // Ascending + inside [0, 100].
        for (int i = 0; i < ticks.length; i++) {
            assertTrue(ticks[i] >= 0.0 && ticks[i] <= 100.0,
                    "tick " + ticks[i] + " out of [0, 100]");
            if (i > 0) assertTrue(ticks[i] > ticks[i - 1], "ticks must ascend");
        }
        // Step size is 1/2/2.5/5 × 10ⁿ.
        double step = ticks[1] - ticks[0];
        double mant = step / Math.pow(10, Math.floor(Math.log10(step)));
        assertTrue(Math.abs(mant - 1) < 1e-9
                || Math.abs(mant - 2) < 1e-9
                || Math.abs(mant - 2.5) < 1e-9
                || Math.abs(mant - 5) < 1e-9,
                "step mantissa " + mant + " not in {1, 2, 2.5, 5}");
    }

    @Test
    void niceLinear_zeroSpan_returnsSingleTick() {
        double[] ticks = FftAxisTicks.niceLinear(5.0, 5.0, 10);
        assertTrue(ticks.length <= 2, "zero span → no real ticks");
    }

    @Test
    void niceLinearMinStep_enforcesFloor() {
        // Natural step for [0,4]/count=10 is 0.5.  Force minStep=2 →
        // ticks should land at 0, 2, 4.
        double[] ticks = FftAxisTicks.niceLinearMinStep(0.0, 4.0, 10, 2.0);
        assertTrue(ticks.length >= 2);
        for (int i = 1; i < ticks.length; i++) {
            assertTrue(ticks[i] - ticks[i - 1] >= 2.0 - 1e-9,
                    "step " + (ticks[i] - ticks[i - 1]) + " < 2");
        }
    }

    @Test
    void logFreqAll_emitsExpectedDecades() {
        // [1, 100] with labelsOnly=false → 1, 2, 3, 4, 5, 6, 7, 8, 9,
        // 10, 20, …, 90, 100 — that's 19 ticks.
        double[] ticks = FftAxisTicks.logFreqAll(1.0, 100.0, false);
        assertTrue(ticks.length >= 17 && ticks.length <= 19,
                "expected ~19 ticks across two decades, got " + ticks.length);
        // First tick at 1.0, last at 100.0 (or 90 if 100 is excluded).
        assertEquals(1.0, ticks[0], 1e-9);
    }

    @Test
    void logFreqAll_labelsOnly_thinsToReasonableCount() {
        // Across 4 decades the keep set is {1, 2, 5} → ~3 labels per
        // decade ≈ 12 total.
        double[] labels = FftAxisTicks.logFreqAll(1.0, 10_000.0, true);
        assertTrue(labels.length >= 5 && labels.length <= 16,
                "thinned labels for 4 decades should be in 5..16, got " + labels.length);
        // Monotonic increase.
        for (int i = 1; i < labels.length; i++) {
            assertTrue(labels[i] > labels[i - 1]);
        }
    }

    @Test
    void freqMajor_linearMode_isAscendingAndWithinRange() {
        double[] major = FftAxisTicks.freqMajor(0.0, 1000.0, false);
        assertTrue(major.length >= 5);
        for (int i = 0; i < major.length; i++) {
            assertTrue(major[i] >= 0.0 && major[i] <= 1000.0);
            if (i > 0) assertTrue(major[i] > major[i - 1]);
        }
    }

    @Test
    void freqMinor_excludesMajorTicks() {
        // Log-mode minor ticks = all 1..9×10ⁿ minus the labelled subset.
        double[] all   = FftAxisTicks.logFreqAll(1.0, 1000.0, false);
        double[] major = FftAxisTicks.logFreqAll(1.0, 1000.0, true);
        double[] minor = FftAxisTicks.freqMinor(1.0, 1000.0, true);

        // every major tick should be MISSING from minor
        for (double m : major) {
            for (double mn : minor) {
                assertTrue(Math.abs(m - mn) > 1e-6 * Math.max(1, Math.abs(m)),
                        "major tick " + m + " leaked into minor list");
            }
        }
        // and minor ⊕ major = all (counts match)
        assertEquals(all.length, major.length + minor.length,
                "minor + major should partition all log-frequency ticks");
    }

    @Test
    void subtract_removesElementsWithinTolerance() {
        double[] all     = {1.0, 2.0, 3.0, 4.0, 5.0};
        double[] exclude = {2.0, 4.0};
        double[] kept    = FftAxisTicks.subtract(all, exclude);
        assertEquals(3, kept.length);
        assertEquals(1.0, kept[0], 1e-9);
        assertEquals(3.0, kept[1], 1e-9);
        assertEquals(5.0, kept[2], 1e-9);
    }

    @Test
    void isMagLog_classifiesUnitsCorrectly() {
        assertTrue(FftAxisTicks.isMagLog(FftMagnitudeUnit.V));
        assertTrue(FftAxisTicks.isMagLog(FftMagnitudeUnit.V_SQRT_HZ));
        assertTrue(!FftAxisTicks.isMagLog(FftMagnitudeUnit.DBFS));
        assertTrue(!FftAxisTicks.isMagLog(FftMagnitudeUnit.DBV));
    }
}
