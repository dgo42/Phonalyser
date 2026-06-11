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

package org.edgo.audio.measure.gui.widgets;

import java.util.Locale;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@link NumericStepModel} behaviours straight from the field
 * specification: the "careful 10 %" wheel walks, the 1-significant-digit
 * down grid, list jumping with off-list entry, displayed-unit arrows,
 * unit parsing/formatting per family, and clamp-on-enter.
 */
class NumericStepModelTest {

    private static final double EPS = 1e-9;

    // -------------------------------------------------------------------------
    // PERCENT wheel — the spec sequences, verbatim
    // -------------------------------------------------------------------------

    @Test
    void percentWheel_up_walksTheSpecSequence() {
        NumericStepModel m = new NumericStepModel(UnitFamily.FREQUENCY, 1, 192_000, 9);
        m.setValue(1000);
        double[] expected = {1100, 1200, 1300, 1400, 1500, 1600, 1700, 1800, 1900, 2000, 2200};
        for (double e : expected) {
            m.wheel(+1);
            assertEquals(e, m.getValue(), EPS, "up walk");
        }
    }

    @Test
    void percentWheel_down_walksTheOneSigGrid() {
        NumericStepModel m = new NumericStepModel(UnitFamily.FREQUENCY, 1, 192_000, 9);
        m.setValue(1000);
        double[] expected = {900, 800, 700, 600, 500, 400, 300, 200, 100, 90};
        for (double e : expected) {
            m.wheel(-1);
            assertEquals(e, m.getValue(), EPS, "down walk");
        }
    }

    @Test
    void percentWheel_down_offGridStepsTheTenPercentGrid() {
        NumericStepModel m = new NumericStepModel(UnitFamily.FREQUENCY, 1, 192_000, 9);
        m.setValue(1300);
        m.wheel(-1);
        assertEquals(1200, m.getValue(), EPS);
        m.setValue(2200);
        m.wheel(-1);
        assertEquals(2100, m.getValue(), EPS);
        m.wheel(-1);
        assertEquals(2000, m.getValue(), EPS);
    }

    @Test
    void percentWheel_clampsAtBounds() {
        NumericStepModel m = new NumericStepModel(UnitFamily.FREQUENCY, 1, 192_000, 9);
        m.setValue(192_000);
        m.wheel(+1);
        assertEquals(192_000, m.getValue(), EPS, "max clamp");
        m.setValue(1);
        m.wheel(-1);
        assertEquals(1, m.getValue(), EPS, "min clamp");
    }

    // -------------------------------------------------------------------------
    // PERCENT arrows — ±1 in the DISPLAYED unit
    // -------------------------------------------------------------------------

    @Test
    void percentArrow_stepsOneDisplayedUnit() {
        NumericStepModel m = new NumericStepModel(UnitFamily.FREQUENCY, 1, 384_000, 9);
        m.setValue(192_000);              // displays as 192 kHz
        m.arrow(+1);
        assertEquals(193_000, m.getValue(), EPS, "+1 kHz at kHz display");
        m.setValue(500);                  // displays as 500 Hz
        m.arrow(+1);
        assertEquals(501, m.getValue(), EPS, "+1 Hz at Hz display");
    }

    @Test
    void percentArrow_amplitudeInMillivoltRange_stepsOneMillivolt() {
        NumericStepModel m = new NumericStepModel(UnitFamily.AMPLITUDE, 1e-6, 10, 5);
        m.setValue(0.2);                  // < 0.5 V → displays as 200 mV
        m.arrow(+1);
        assertEquals(0.201, m.getValue(), EPS);
    }

    @Test
    void percentWheel_stickyDbv_walksTheTenDecibelGrid() {
        // In log display the wheel steps dB, not linear volts: 0 → −10 → −20;
        // an off-grid −3.5 snaps to −10 down and 0 up.
        NumericStepModel m = new NumericStepModel(UnitFamily.AMPLITUDE, 1e-6, 10, 5);
        assertTrue(m.commit("0 dBV"));
        m.wheel(-1);
        assertEquals(Math.pow(10, -10 / 20.0), m.getValue(), EPS, "0 → −10 dBV");
        m.wheel(-1);
        assertEquals(Math.pow(10, -20 / 20.0), m.getValue(), EPS, "−10 → −20 dBV");
        m.wheel(+1);
        assertEquals(Math.pow(10, -10 / 20.0), m.getValue(), EPS, "−20 → −10 dBV");
        assertTrue(m.commit("-3.5 dBV"));
        m.wheel(-1);
        assertEquals(Math.pow(10, -10 / 20.0), m.getValue(), EPS, "−3.5 snaps to −10 down");
        assertTrue(m.commit("-3.5 dBV"));
        m.wheel(+1);
        assertEquals(1.0, m.getValue(), EPS, "−3.5 snaps to 0 dBV up");
    }

    @Test
    void list_namedFirstEntry_walksInGivenOrder() {
        // The sweep-points list pins "Nyquist/2" FIRST despite its larger
        // numeric value — the wheel walks the list order, not sorted order.
        double[] series = {192_000, 8192, 16384};
        NumericStepModel m = new NumericStepModel(UnitFamily.NONE, 4096, 10_000_000, series, 0);
        m.setValue(192_000);
        m.wheel(+1);
        assertEquals(8192, m.getValue(), EPS, "head steps to the first preset");
        m.wheel(-1);
        assertEquals(192_000, m.getValue(), EPS, "and back to the head");
        m.wheel(-1);
        assertEquals(192_000, m.getValue(), EPS, "list start saturates");
        m.setValue(16384);
        m.wheel(+1);
        assertEquals(16384, m.getValue(), EPS, "list end saturates");
    }

    @Test
    void logDisplay_persistRestoreAndRelease() {
        NumericStepModel m = new NumericStepModel(UnitFamily.AMPLITUDE, 1e-6, 10, 5);
        m.setValue(0.5);
        m.setLogDisplay(true);                // the persisted-choice restore path
        assertTrue(m.isLogDisplay());
        assertTrue(m.text().endsWith("dBV"), m.text());
        assertTrue(m.commit("0.7"));          // suffix-less entry releases it
        assertFalse(m.isLogDisplay());
        assertTrue(m.commit("-6 dBV"));       // typing dBV sets it
        assertTrue(m.isLogDisplay());
    }

    @Test
    void namedValue_rendersAndParsesAsLabel() {
        // The sweep-points "Nyquist/2" entry: rate-derived value shown as text.
        double[] series = {8192, 131_072, 262_144};
        NumericStepModel m = new NumericStepModel(UnitFamily.NONE, 8192, 10_000_000, series, 0);
        m.setNamedValue(192_000, "Nyquist/2");
        m.setValue(192_000);
        assertEquals("Nyquist/2", m.text());
        assertTrue(m.commit("nyquist/2"), "label parses case-insensitively");
        assertEquals(192_000, m.getValue(), EPS);
        assertTrue(m.acceptsPartial("Nyquist/2"), "label survives the mid-edit filter");
        m.wheel(+1);
        assertEquals(262_144, m.getValue(), EPS, "stepping treats it as its number");
        assertTrue(m.commit("8192"));
        assertEquals("8192", m.text(), "plain values still render numerically");
    }

    @Test
    void percentArrow_stickyDbv_stepsOneDecibel() {
        NumericStepModel m = new NumericStepModel(UnitFamily.AMPLITUDE, 1e-6, 10, 5);
        assertTrue(m.commit("-3 dBV"));
        m.arrow(+1);
        assertEquals(Math.pow(10, -2 / 20.0), m.getValue(), EPS);
    }

    // -------------------------------------------------------------------------
    // LIST policy
    // -------------------------------------------------------------------------

    @Test
    void list_jumpsAlongSeries_andSaturatesAtEnds() {
        double[] series = {1e-6, 2e-6, 5e-6, 1e-5};
        NumericStepModel m = new NumericStepModel(UnitFamily.VOLTS_PER_DIV, 1e-6, 1e-5, series, 3);
        m.setValue(1e-6);
        m.wheel(+1);
        assertEquals(2e-6, m.getValue(), 1e-15);
        m.wheel(+1);
        assertEquals(5e-6, m.getValue(), 1e-15);
        m.setValue(1e-5);
        m.wheel(+1);
        assertEquals(1e-5, m.getValue(), 1e-15, "saturates at top");
        m.wheel(-1);
        assertEquals(5e-6, m.getValue(), 1e-15);
    }

    @Test
    void list_offListValue_jumpsToNearestInDirection() {
        double[] series = {1e-6, 2e-6, 5e-6, 1e-5};
        NumericStepModel m = new NumericStepModel(UnitFamily.VOLTS_PER_DIV, 1e-6, 1e-5, series, 3);
        m.setValue(3e-6);                 // manual off-list entry
        m.wheel(+1);
        assertEquals(5e-6, m.getValue(), 1e-15);
        m.setValue(3e-6);
        m.wheel(-1);
        assertEquals(2e-6, m.getValue(), 1e-15);
    }

    @Test
    void list_averages_reachInfinityAndBack() {
        double[] series = {2, 4, 8, 16, 32, 64, 128, Double.POSITIVE_INFINITY};
        NumericStepModel m = new NumericStepModel(UnitFamily.NONE, 2, Double.POSITIVE_INFINITY, series, 0);
        m.setValue(128);
        m.wheel(+1);
        assertTrue(Double.isInfinite(m.getValue()));
        assertEquals("∞", m.text());
        m.wheel(-1);
        assertEquals(128, m.getValue(), EPS);
        assertTrue(m.commit("inf"));
        assertTrue(Double.isInfinite(m.getValue()));
    }

    @Test
    void list_infinityRejectedWhenBounded() {
        double[] series = {2, 4, 8};
        NumericStepModel m = new NumericStepModel(UnitFamily.NONE, 2, 8, series, 0);
        m.setValue(4);
        assertFalse(m.commit("∞"));
        assertEquals(4, m.getValue(), EPS, "value unchanged after invalid entry");
    }

    // -------------------------------------------------------------------------
    // FIXED policy
    // -------------------------------------------------------------------------

    @Test
    void fixed_distinctWheelAndArrowSteps() {
        // Multitone detect threshold: wheel ±10 dB, arrows ±1 dB, 10–140.
        NumericStepModel m = new NumericStepModel(UnitFamily.DECIBEL, 10, 140, 10, 1, 1);
        m.setValue(100);
        m.wheel(+1);
        assertEquals(110, m.getValue(), EPS);
        m.arrow(-1);
        assertEquals(109, m.getValue(), EPS);
        m.setValue(140);
        m.wheel(+1);
        assertEquals(140, m.getValue(), EPS, "max clamp");
    }

    @Test
    void fixed_hysteresis_zeroToFiveByTenth() {
        NumericStepModel m = new NumericStepModel(UnitFamily.DIVISIONS, 0, 5, 0.1, 0.1, 1);
        m.setValue(0);
        m.wheel(+1);
        assertEquals(0.1, m.getValue(), EPS);
        m.setValue(5);
        m.arrow(+1);
        assertEquals(5, m.getValue(), EPS, "max clamp");
    }

    // -------------------------------------------------------------------------
    // Parsing + formatting
    // -------------------------------------------------------------------------

    @Test
    void frequency_parsesSuffixedAndSuffixlessInput() {
        NumericStepModel m = new NumericStepModel(UnitFamily.FREQUENCY, 1, 192_000, 9);
        assertTrue(m.commit("1.5 kHz"));
        assertEquals(1500, m.getValue(), EPS);
        assertTrue(m.commit("200"));      // suffix-less = Hz
        assertEquals(200, m.getValue(), EPS);
        assertTrue(m.commit("1,5kHz"));   // decimal comma, no space
        assertEquals(1500, m.getValue(), EPS);
        assertFalse(m.commit("12 parsec"));
        assertEquals(1500, m.getValue(), EPS, "invalid entry leaves value");
    }

    @Test
    void frequency_displaySwitchesToKiloAtThousand() {
        NumericStepModel m = new NumericStepModel(UnitFamily.FREQUENCY, 1, 384_000, 9);
        m.setValue(999);
        assertTrue(m.text().endsWith("Hz"));
        assertFalse(m.text().endsWith("kHz"));
        m.setValue(1234.5);
        assertTrue(m.text().endsWith("kHz"), "kHz from 1000 up: " + m.text());
        assertTrue(m.commit(m.text()), "format/parse round-trip");
        assertEquals(1234.5, m.getValue(), EPS);
    }

    @Test
    void amplitude_parsesAllUnits_dbvAllowsNegative() {
        NumericStepModel m = new NumericStepModel(UnitFamily.AMPLITUDE, 1e-6, 10, 5);
        assertTrue(m.commit("499 mV"));
        assertEquals(0.499, m.getValue(), EPS);
        assertTrue(m.commit("250 uV"));   // ASCII alias for µV
        assertEquals(2.5e-4, m.getValue(), EPS);
        assertTrue(m.commit("-3.5 dBV"));
        assertEquals(Math.pow(10, -3.5 / 20.0), m.getValue(), EPS);
        assertTrue(m.text().endsWith("dBV"), "dBV sticky after explicit entry");
        assertTrue(m.commit("0.7"));      // suffix-less = V, releases sticky
        assertEquals(0.7, m.getValue(), EPS);
        assertTrue(m.text().endsWith("V") && !m.text().endsWith("dBV"));
    }

    @Test
    void time_switchesToMillisecondsBelowHalfSecond() {
        NumericStepModel m = new NumericStepModel(UnitFamily.TIME, 1e-3, 1_000_000, 3);
        m.setValue(0.4);
        assertTrue(m.text().endsWith("ms"), m.text());
        assertTrue(m.commit(m.text()));
        assertEquals(0.4, m.getValue(), EPS);
        m.setValue(2.5);
        assertTrue(m.text().endsWith("s") && !m.text().endsWith("ms"), m.text());
    }

    @Test
    void perDiv_suffixlessEntryUsesDisplayedUnit() {
        double[] series = {1e-6, 1e-3, 1.0};
        NumericStepModel m = new NumericStepModel(UnitFamily.VOLTS_PER_DIV, 1e-6, 500, series, 3);
        m.setValue(2e-3);                 // displays as mV/div
        assertTrue(m.commit("5"));        // means 5 mV/div
        assertEquals(5e-3, m.getValue(), EPS);
    }

    @Test
    void clampOnEnter_appliesBounds() {
        NumericStepModel m = new NumericStepModel(UnitFamily.FREQUENCY, 1, 192_000, 9);
        assertTrue(m.commit("300 kHz"));
        assertEquals(192_000, m.getValue(), EPS, "clamped to Nyquist");
        assertTrue(m.commit("0.2"));
        assertEquals(1, m.getValue(), EPS, "clamped to min");
    }

    @Test
    void dynamicBounds_reclampCurrentValue() {
        NumericStepModel m = new NumericStepModel(UnitFamily.FREQUENCY, 1, 192_000, 9);
        m.setValue(150_000);
        m.setMax(96_000);                 // sample rate dropped
        assertEquals(96_000, m.getValue(), EPS);
    }

    // -------------------------------------------------------------------------
    // Adversarial-review regressions
    // -------------------------------------------------------------------------

    @Test
    void percentWheel_upFromZero_stepsOneDisplayLsb() {
        // Fade fields legitimately sit at 0; the wheel must escape it (and
        // never poison the value with NaN — 0 sits on no decade grid).
        NumericStepModel m = new NumericStepModel(UnitFamily.TIME, 0, 1_000_000, 3);
        m.setValue(0);
        m.wheel(+1);
        assertEquals(1e-6, m.getValue(), 1e-15, "one LSB of the ms display");
        assertTrue(Double.isFinite(m.getValue()));
    }

    @Test
    void percentWheel_dutyNearMinimum_stepsStayVisible() {
        // Duty min 0.001 % with 3 decimals: a raw 10% step (0.0001) would be
        // below display resolution — invisible steps that snap back on commit.
        // The display-LSB grid floor keeps every notch visible.
        NumericStepModel m = new NumericStepModel(UnitFamily.PERCENT, 0.001, 99.999, 3);
        m.setValue(0.001);
        m.wheel(+1);
        assertEquals(0.002, m.getValue(), EPS, "one visible LSB up");
        assertTrue(m.commit(m.text()), "commit of displayed text");
        assertEquals(0.002, m.getValue(), EPS, "no snap-back");
        m.wheel(-1);
        assertEquals(0.001, m.getValue(), EPS);
    }

    @Test
    void list_valueBeyondSeriesTop_upGestureSaturatesInPlace() {
        // Sweep points: series tops at 4M but manual entry allows 10M — an
        // up gesture from 6M must not DECREASE the value to the series top.
        double[] series = {8192, 16384, 4_194_304};
        NumericStepModel m = new NumericStepModel(UnitFamily.NONE, 8192, 10_000_000, series, 0);
        m.setValue(6_000_000);
        m.wheel(+1);
        assertEquals(6_000_000, m.getValue(), EPS, "saturates in place above the top");
        m.wheel(-1);
        assertEquals(4_194_304, m.getValue(), EPS, "down re-enters the series");
    }

    @Test
    void commit_acceptsDanglingDecimalSeparator() {
        NumericStepModel m = new NumericStepModel(UnitFamily.FREQUENCY, 1, 192_000, 9);
        assertTrue(m.commit("200,"));
        assertEquals(200, m.getValue(), EPS);
        assertTrue(m.commit("315."));
        assertEquals(315, m.getValue(), EPS);
        assertFalse(m.commit("."), "a lone separator is still invalid");
    }

    @Test
    void commit_acceptsGreekMuAsMicro() {
        // Pasted scientific text / Greek keyboards produce U+03BC, not U+00B5.
        NumericStepModel m = new NumericStepModel(UnitFamily.AMPLITUDE, 1e-6, 10, 5);
        assertTrue(m.commit("5 μV"));
        assertEquals(5e-6, m.getValue(), 1e-15);
    }

    @Test
    void unitMatching_survivesTurkishLocale() {
        // tr locale lowercases "US/DIV" to a dotless ı — Locale.ROOT folding
        // in Unit.matches must keep the ASCII aliases reachable.
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            NumericStepModel m = new NumericStepModel(UnitFamily.TIME_PER_DIV,
                    1e-6, 1, new double[]{1e-6, 1e-3, 1.0}, 3);
            assertTrue(m.commit("5 US/DIV"));
            assertEquals(5e-6, m.getValue(), 1e-15);
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    void acceptsPartial_allowsEveryPrefixOfValidInput() {
        NumericStepModel m = new NumericStepModel(UnitFamily.AMPLITUDE, 1e-6, 10, 5);
        assertTrue(m.acceptsPartial("-"));
        assertTrue(m.acceptsPartial("1e-"));
        assertTrue(m.acceptsPartial("5."));
        assertTrue(m.acceptsPartial("1,"));
        assertTrue(m.acceptsPartial("1.5 dB"));
        assertTrue(m.acceptsPartial("∞"));
    }

    @Test
    void setValue_ignoresNaN() {
        NumericStepModel m = new NumericStepModel(UnitFamily.TIME, 0, 100, 3);
        m.setValue(2.5);
        m.setValue(Double.NaN);
        assertEquals(2.5, m.getValue(), EPS, "NaN can never poison the value");
    }
}
