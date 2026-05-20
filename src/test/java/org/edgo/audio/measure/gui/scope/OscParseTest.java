package org.edgo.audio.measure.gui.scope;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link OscParse} — V/div and t/div label parsing, formatting,
 * and the lenient user-input parser used by the editable step selectors.
 */
class OscParseTest {

    private static final double EPS = 1e-12;

    // ─── tryParseStepInput ───────────────────────────────────────────────────

    @Test
    void tryParseStepInput_bareNumber_isBaseUnit() {
        assertEquals(5.0,    OscParse.tryParseStepInput("5"),    EPS);
        assertEquals(100.0,  OscParse.tryParseStepInput("100"),  EPS);
        assertEquals(0.45,   OscParse.tryParseStepInput("0.45"), EPS);
    }

    @Test
    void tryParseStepInput_commaIsDecimal() {
        assertEquals(45.5, OscParse.tryParseStepInput("45,5"), EPS);
    }

    @Test
    void tryParseStepInput_unitPrefixes() {
        assertEquals(100e-3,  OscParse.tryParseStepInput("100m"),       EPS);
        assertEquals(100e-3,  OscParse.tryParseStepInput("100 mV"),     EPS);
        assertEquals(100e-3,  OscParse.tryParseStepInput("100mV/div"),  EPS);
        assertEquals(100e-6,  OscParse.tryParseStepInput("100µs"),      EPS);
        assertEquals(100e-6,  OscParse.tryParseStepInput("100μs"),      EPS);
        assertEquals(100e-6,  OscParse.tryParseStepInput("100us"),      EPS);
        assertEquals(100e-9,  OscParse.tryParseStepInput("100ns"),      EPS);
    }

    @Test
    void tryParseStepInput_invalidReturnsNull() {
        assertNull(OscParse.tryParseStepInput(null));
        assertNull(OscParse.tryParseStepInput(""));
        assertNull(OscParse.tryParseStepInput("  "));
        assertNull(OscParse.tryParseStepInput("nonsense"));
        assertNull(OscParse.tryParseStepInput("100xyz"));
    }

    @Test
    void tryParseStepInput_signedAndScientific() {
        assertEquals(-5.0,   OscParse.tryParseStepInput("-5"),     EPS);
        assertEquals( 1e-6,  OscParse.tryParseStepInput("1e-6"),   EPS);
        assertEquals( 1.5e-3, OscParse.tryParseStepInput("1.5e-3"), EPS);
    }

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
