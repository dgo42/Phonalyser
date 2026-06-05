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

package org.edgo.audio.measure.gui.scope;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ScopeFormat} — formatters and step-pickers shared
 * across the oscilloscope view + pane.  Pins V/div tile labels, axis
 * formatters, the V/div-change offset-preservation math, and the
 * trigger-auto-timeout parser.
 */
class ScopeFormatTest {

    @Test
    void clamp01_clampsToUnitInterval() {
        assertEquals(0.0, ScopeFormat.clamp01(-0.5), 1e-12);
        assertEquals(0.0, ScopeFormat.clamp01( 0.0), 1e-12);
        assertEquals(0.5, ScopeFormat.clamp01( 0.5), 1e-12);
        assertEquals(1.0, ScopeFormat.clamp01( 1.0), 1e-12);
        assertEquals(1.0, ScopeFormat.clamp01( 1.5), 1e-12);
    }

    @Test
    void midRgb_scalesEachChannelToRoughly65pct() {
        // 0xFFFFFF → each channel ≈ 0.65 × 255 = 166 (0xA6).
        int mid = ScopeFormat.midRgb(0xFFFFFF);
        assertEquals(0xA6, (mid >> 16) & 0xFF);
        assertEquals(0xA6, (mid >>  8) & 0xFF);
        assertEquals(0xA6,  mid        & 0xFF);
        // Pure black stays black.
        assertEquals(0, ScopeFormat.midRgb(0x000000));
    }

    @Test
    void windowMean_emptyOrNull_returnsZero() {
        assertEquals(0.0, ScopeFormat.windowMean(null,           0, 10), 1e-12);
        assertEquals(0.0, ScopeFormat.windowMean(new float[10],  0, 0),  1e-12);
        assertEquals(0.0, ScopeFormat.windowMean(new float[10],  0, -1), 1e-12);
    }

    @Test
    void windowMean_correctAverage() {
        float[] data = {1f, 2f, 3f, 4f, 5f};
        assertEquals(3.0, ScopeFormat.windowMean(data, 0, 5), 1e-12);
        assertEquals(3.0, ScopeFormat.windowMean(data, 1, 3), 1e-12);  // {2,3,4} → 3
    }

    @Test
    void formatVolts_picksRightSiPrefix() {
        assertTrue(ScopeFormat.formatVolts(1.5,     0.5).endsWith(" V"));
        assertTrue(ScopeFormat.formatVolts(0.020,   0.01).endsWith(" mV"));
        assertTrue(ScopeFormat.formatVolts(1e-5,    1e-6).endsWith(" µV"));
        assertEquals("0 V", ScopeFormat.formatVolts(0.0, 1.0));
    }

    @Test
    void formatSeconds_picksRightSiPrefix() {
        assertTrue(ScopeFormat.formatSeconds(1.5).endsWith(" s"));
        assertTrue(ScopeFormat.formatSeconds(0.025).endsWith(" ms"));
        assertTrue(ScopeFormat.formatSeconds(50e-6).endsWith(" µs"));
        assertTrue(ScopeFormat.formatSeconds(100e-9).endsWith(" ns"));
        assertEquals("0 s", ScopeFormat.formatSeconds(0.0));
    }

    @Test
    void shortVoltsPerDiv_compactLabels() {
        // 1 V → "1", 100 mV → "100m", 50 µV → "50u".
        assertEquals("1",    ScopeFormat.shortVoltsPerDiv(1.0));
        assertEquals("100m", ScopeFormat.shortVoltsPerDiv(0.1));
        assertEquals("50u",  ScopeFormat.shortVoltsPerDiv(50e-6));
    }

    @Test
    void shortTimePerDiv_handlesNsRange() {
        // 500 ns → "500n", 50 µs → "50u", 20 ms → "20m".
        assertEquals("500n", ScopeFormat.shortTimePerDiv(500e-9));
        assertEquals("50u",  ScopeFormat.shortTimePerDiv(50e-6));
        assertEquals("20m",  ScopeFormat.shortTimePerDiv(0.02));
    }

    @Test
    void preserveCanvasMiddle_zeroVpdiv_returnsInputUnchanged() {
        // Defensive path: 0 or negative V/div should not produce NaN.
        assertEquals(0.7, ScopeFormat.preserveCanvasMiddle(0.7, 0,  1), 1e-12);
        assertEquals(0.7, ScopeFormat.preserveCanvasMiddle(0.7, 1,  0), 1e-12);
        assertEquals(0.7, ScopeFormat.preserveCanvasMiddle(0.7, -1, 1), 1e-12);
    }

    @Test
    void preserveCanvasMiddle_doublingVpdiv_compressesAroundCentre() {
        // V/div doubles → distance from 0.5 halves → 0.75 becomes 0.625.
        double newFrac = ScopeFormat.preserveCanvasMiddle(0.75, 1.0, 2.0);
        assertEquals(0.625, newFrac, 1e-12);
    }

    @Test
    void parseSeconds_rejectsNonPositiveAndGarbage() {
        assertNull(ScopeFormat.parseSeconds(null));
        assertNull(ScopeFormat.parseSeconds(""));
        assertNull(ScopeFormat.parseSeconds("not-a-number"));
        assertNull(ScopeFormat.parseSeconds("0"));
        assertNull(ScopeFormat.parseSeconds("-5 s"));
    }

    @Test
    void parseSeconds_acceptsBothDecimalSeparators() {
        assertEquals(1.5, ScopeFormat.parseSeconds("1.5 s"), 1e-12);
        assertEquals(1.5, ScopeFormat.parseSeconds("1,5 s"), 1e-12);
        assertEquals(2.0, ScopeFormat.parseSeconds("2"),     1e-12);
    }

    @Test
    void displaySamplesFor_spans10Divisions() {
        // 1 ms/div at 1 MHz → 10 ms × 1e6 = 10000 samples.
        assertEquals(10_000, ScopeFormat.displaySamplesFor(1e-3, 1_000_000));
        // 1 s/div at 48 kHz → 10 s × 48000 = 480000 samples.
        assertEquals(480_000, ScopeFormat.displaySamplesFor(1.0, 48_000));
        // Minimum guard: always ≥ 2 samples.
        assertEquals(2, ScopeFormat.displaySamplesFor(1e-12, 1));
    }

    @Test
    void hysteresisDivSteps_emits_0_to_5_in_0_1_steps() {
        String[] steps = ScopeFormat.hysteresisDivSteps();
        assertEquals(51, steps.length);
        assertEquals("0.0", steps[0]);
        assertEquals("5.0", steps[50]);
        assertEquals("0.1", steps[1]);
    }

    @Test
    void ceilToStep_picksSmallestTargetAtOrAbove() {
        double[] targets = {1, 2, 5, 10};
        assertEquals(1,  ScopeFormat.ceilToStep(0.5,  targets), 1e-12);
        assertEquals(2,  ScopeFormat.ceilToStep(1.5,  targets), 1e-12);
        assertEquals(5,  ScopeFormat.ceilToStep(3.0,  targets), 1e-12);
        // Beyond the top: returns the largest target.
        assertEquals(10, ScopeFormat.ceilToStep(20.0, targets), 1e-12);
    }

    @Test
    void nextTargetFrom_walksTheLadder() {
        double[] targets = {1, 2, 5, 10};
        assertEquals(2,  ScopeFormat.nextTargetFrom(1, targets, +1), 1e-12);
        assertEquals(10, ScopeFormat.nextTargetFrom(5, targets, +1), 1e-12);
        // At top, going up: stays at current.
        assertEquals(10, ScopeFormat.nextTargetFrom(10, targets, +1), 1e-12);
        // Going down.
        assertEquals(5,  ScopeFormat.nextTargetFrom(10, targets, -1), 1e-12);
        assertEquals(1,  ScopeFormat.nextTargetFrom(2,  targets, -1), 1e-12);
        // delta == 0 → unchanged.
        assertEquals(5,  ScopeFormat.nextTargetFrom(5,  targets,  0), 1e-12);
    }

    @Test
    void nearestIndex_picksClosestEntry() {
        String[] values = {"0.0", "0.5", "1.0", "2.0", "5.0"};
        assertEquals(0, ScopeFormat.nearestIndex(values, -0.1));
        assertEquals(1, ScopeFormat.nearestIndex(values,  0.6));
        assertEquals(2, ScopeFormat.nearestIndex(values,  0.9));
        assertEquals(4, ScopeFormat.nearestIndex(values, 100));
    }
}
