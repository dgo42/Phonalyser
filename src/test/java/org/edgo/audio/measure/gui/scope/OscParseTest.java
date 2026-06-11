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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link OscParse} — V/div and t/div label parsing, formatting,
 * and the lenient user-input parser used by the editable step selectors.
 */
class OscParseTest {

    // ─── formatWithUnit (round-trip via the public formatters) ──────────────

    @Test
    void formatVoltsPerDiv_voltsRange() {
        assertEquals("1 V/div",     OscParse.formatVoltsPerDiv(1.0));
        assertEquals("100 mV/div",  OscParse.formatVoltsPerDiv(0.1));
        assertEquals("5 µV/div",    OscParse.formatVoltsPerDiv(5e-6));
    }

    @Test
    void formatTimePerDiv_timeRange() {
        assertEquals("1 s/div",    OscParse.formatTimePerDiv(1.0));
        assertEquals("10 ms/div",  OscParse.formatTimePerDiv(0.01));
        assertEquals("1 µs/div",   OscParse.formatTimePerDiv(1e-6));
        assertEquals("100 ns/div", OscParse.formatTimePerDiv(1e-7));
    }

    @Test
    void formatVoltsPerDiv_stripsTrailingZeros() {
        assertEquals("100 mV/div",  OscParse.formatVoltsPerDiv(0.1));
        assertEquals("45.5 mV/div", OscParse.formatVoltsPerDiv(0.0455));
    }

    // ─── target-array correctness ───────────────────────────────────────────

    @Test
    void voltsPerDivTargets_areAscending() {
        double[] targets = OscParse.voltsPerDivTargets();
        for (int i = 1; i < targets.length; i++) {
            assertTrue(targets[i] > targets[i - 1],
                    "v/div step list must be strictly ascending");
        }
    }

    @Test
    void timePerDivTargets_areAscending() {
        double[] targets = OscParse.timePerDivTargets();
        for (int i = 1; i < targets.length; i++) {
            assertTrue(targets[i] > targets[i - 1],
                    "t/div step list must be strictly ascending");
        }
    }
}
