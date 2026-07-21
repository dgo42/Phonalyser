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
        assertTrue(m.commit("10k"));      // "k" short alias for kHz
        assertEquals(10_000, m.getValue(), EPS);
        assertTrue(m.commit("20kh"));     // "kh" short alias for kHz
        assertEquals(20_000, m.getValue(), EPS);
        assertTrue(m.commit("1002"));     // digits-only = base unit Hz (NOT the displayed kHz)
        assertEquals(1002, m.getValue(), EPS);
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
    void voltage_parsesLinearUnits_rejectsDbv() {
        // VOLTAGE is AMPLITUDE without the log unit — calibration entry where a
        // dB reference makes no sense.  Same nV/µV/mV/V parsing and switching.
        NumericStepModel m = new NumericStepModel(UnitFamily.VOLTAGE, 1e-9, 1000, 6);
        assertTrue(m.commit("499 mV"));
        assertEquals(0.499, m.getValue(), EPS);
        assertTrue(m.commit("250 uV"));   // ASCII alias for µV
        assertEquals(2.5e-4, m.getValue(), EPS);
        m.setValue(2.5e-4);
        assertTrue(m.text().endsWith("µV"), "µV display below 1 mV: " + m.text());
        m.setValue(2.5);
        assertTrue(m.text().endsWith("V") && !m.text().endsWith("mV"), m.text());
        assertFalse(m.commit("-3.5 dBV"), "dBV is not a VOLTAGE unit");
        assertEquals(2.5, m.getValue(), EPS, "rejected entry leaves the value unchanged");
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
    void perDiv_suffixlessEntryUsesBaseUnit() {
        double[] series = {1e-6, 1e-3, 1.0};
        NumericStepModel m = new NumericStepModel(UnitFamily.VOLTS_PER_DIV, 1e-6, 500, series, 3);
        m.setValue(2e-3);                 // displays as mV/div
        assertTrue(m.commit("5"));        // digits-only = base unit V/div (NOT the displayed mV/div)
        assertEquals(5.0, m.getValue(), EPS);
    }

    @Test
    void clampOnEnter_appliesBounds() {
        NumericStepModel m = new NumericStepModel(UnitFamily.FREQUENCY, 1, 192_000, 9);
        assertTrue(m.commit("300 kHz"));
        assertEquals(192_000, m.getValue(), EPS, "clamped to Nyquist");
        assertTrue(m.commit("0.2"));      // digits-only = base unit Hz, below min
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

    // -------------------------------------------------------------------------
    // DITHER policy — full-scale-aware bits⇄dBV, ±1-bit / ±10-dBV, Off at top
    // -------------------------------------------------------------------------

    private static final double DITHER_DB_PER_BIT = 6.0206;
    private static final double DITHER_OFFSET_DB  = 7.782;

    /** Peak full-scale = 1 Vpeak → 20·log10(1) = 0 dBV reference, and window
     *  ENBW = 1 bin (Rectangular → no FFT over-read), so a bit's dBV is just
     *  −(bits−1)·6.0206 − 7.782 and the conversions have a clean anchor.
     *  (dBV anchors to the PEAK full-scale, not the RMS full-scale.) */
    private NumericStepModel dither(int maxBits) {
        return new NumericStepModel(UnitFamily.DITHER, maxBits, () -> 1.0, () -> 1.0);
    }
    private double ditherDbv(double bits) {   // fsDbv = 20·log10(1) = 0
        return -(bits - 1) * DITHER_DB_PER_BIT - DITHER_OFFSET_DB;
    }
    private double ditherBits(double dbv) {    // fsDbv = 20·log10(1) = 0
        return 1 + (-DITHER_OFFSET_DB - dbv) / DITHER_DB_PER_BIT;
    }

    @Test
    void dither_bitsAndDbvViews_areFullScaleAwareInverses() {
        NumericStepModel m = dither(16);
        m.setValue(16);
        assertFalse(m.isLogDisplay());
        assertEquals("16 bits", m.text());
        // Field in bits → companion shows the dBV of that whole-bit value.
        assertEquals(String.format(Locale.ROOT, "%.1f", ditherDbv(16)) + " dBV", m.companionText());
        // Field in dBV → companion shows the bits.
        m.setLogDisplay(true);
        assertTrue(m.isLogDisplay());
        assertEquals(String.format(Locale.ROOT, "%.1f", ditherDbv(16)) + " dBV", m.text());
        assertEquals("16 bits", m.companionText());
    }

    @Test
    void dither_dbvEntry_storesFractionalBits_andSticksDbv() {
        NumericStepModel m = dither(24);
        assertTrue(m.commit("-95 dBV"));
        assertEquals(ditherBits(-95), m.getValue(), 1e-9);
        assertTrue(m.isLogDisplay(), "dBV entry sticks the dBV view");
        assertTrue(m.commit("-95 db"), "short dBV alias");
        assertEquals(ditherBits(-95), m.getValue(), 1e-9);
    }

    @Test
    void dither_dbvAnchorsToPeakFullScale_notRms() {
        // Regression guard: dBV anchors to the PEAK full-scale
        // (20·log10(dacFsVoltageAmpl)) — NOT the RMS full-scale (/√2), which
        // would read ~3 dB low and make an entered dBV land ~0.5 bit hot.
        double fsAmpl = 2.79351;                 // realistic DAC peak full-scale (Vpeak)
        NumericStepModel m = new NumericStepModel(UnitFamily.DITHER, 24, () -> fsAmpl, () -> 1.0);
        assertTrue(m.commit("-100 dBV"));
        double fsDbv   = 20 * Math.log10(fsAmpl);   // peak anchor, no /√2
        double expBits = 1 + (fsDbv - DITHER_OFFSET_DB - (-100)) / DITHER_DB_PER_BIT;
        assertEquals(expBits, m.getValue(), 1e-9);
        // The RMS anchor (/√2) would give bits 3.01/6.0206 ≈ 0.5 lower.
        double rmsBits = 1 + (fsDbv - 20 * Math.log10(Math.sqrt(2.0)) - DITHER_OFFSET_DB - (-100)) / DITHER_DB_PER_BIT;
        assertTrue(Math.abs(m.getValue() - rmsBits) > 0.4, "must not use the RMS anchor");
    }

    @Test
    void dither_dbvEntryAccountsForWindowEnbw() {
        // The dBV is stated as it reads on the FFT noise floor (physical level +
        // 10·log10(ENBW)), so hitting a given floor with a WIDER window (higher
        // ENBW, more over-read) needs a QUIETER physical dither — i.e. more bits.
        // Hann (1.5) vs Rectangular (1) → 10·log10(1.5) = 1.761 dB → ~0.29 bit.
        double enbwDb = 10 * Math.log10(1.5);
        NumericStepModel rect = new NumericStepModel(UnitFamily.DITHER, 24, () -> 1.0, () -> 1.0);
        NumericStepModel hann = new NumericStepModel(UnitFamily.DITHER, 24, () -> 1.0, () -> 1.5);
        assertTrue(rect.commit("-100 dBV"));
        assertTrue(hann.commit("-100 dBV"));
        assertEquals(enbwDb / DITHER_DB_PER_BIT, hann.getValue() - rect.getValue(), 1e-9,
                "wider window (higher ENBW) → same FFT-floor dBV needs more bits");
    }

    @Test
    void dither_reanchor_dbvMode_holdsDbvAndResolvesBits() {
        // A window/full-scale change with a dBV entered keeps the shown dBV and
        // moves the bits by the config delta (so the FFT-floor target holds).
        double[] enbw = { 1.0 };
        NumericStepModel m = new NumericStepModel(UnitFamily.DITHER, 30, () -> 1.0, () -> enbw[0]);
        assertTrue(m.commit("-100 dBV"));
        double bits0 = m.getValue();
        enbw[0] = 1.5;                                  // Hann-width window
        assertTrue(m.reanchor(), "dBV view re-solves the bits");
        assertEquals(bits0 + 10 * Math.log10(1.5) / DITHER_DB_PER_BIT, m.getValue(), 1e-9,
                "bits move by the ENBW delta → the shown dBV (FFT-floor target) is held");
    }

    @Test
    void dither_reanchor_bitsMode_holdsBits() {
        double[] enbw = { 1.0 };
        NumericStepModel m = new NumericStepModel(UnitFamily.DITHER, 30, () -> 1.0, () -> enbw[0]);
        assertTrue(m.commit("16 bits"));               // bits view = fixed physical dither
        enbw[0] = 1.5;
        assertFalse(m.reanchor(), "bits view holds the physical dither");
        assertEquals(16, m.getValue(), EPS);
    }

    @Test
    void dither_bitsEntry_clampsAndOffOnZero() {
        NumericStepModel m = dither(16);
        assertTrue(m.commit("12 bits"));
        assertEquals(12, m.getValue(), EPS);
        assertFalse(m.isLogDisplay());
        assertTrue(m.commit("20"));        // above max → clamp to 16
        assertEquals(16, m.getValue(), EPS);
        assertTrue(m.commit("15.5 b"));    // fractional bits, short alias
        assertEquals(15.5, m.getValue(), EPS);
        assertTrue(m.commit("0"));         // 0 → Off
        assertEquals(0, m.getValue(), EPS);
        assertEquals("Off", m.text());
        assertTrue(m.commit("off"));       // the word too
        assertEquals(0, m.getValue(), EPS);
    }

    @Test
    void dither_bitsStepping_wholeBitStepsWithOffAtTop() {
        NumericStepModel m = dither(16);
        m.setValue(2);
        m.arrow(+1);                       // up → fewer bits
        assertEquals(1, m.getValue(), EPS);
        m.arrow(+1);                       // up from 1 bit → Off (top of range)
        assertEquals(0, m.getValue(), EPS);
        m.arrow(+1);                       // up from Off → stays Off
        assertEquals(0, m.getValue(), EPS);
        m.arrow(-1);                       // down from Off → 1 bit
        assertEquals(1, m.getValue(), EPS);
        m.setValue(16);
        m.arrow(-1);                       // down at max → saturates
        assertEquals(16, m.getValue(), EPS);
    }

    @Test
    void dither_bitsStepping_preservesFraction() {
        NumericStepModel m = dither(24);
        assertTrue(m.commit("15.5 bits"));
        m.arrow(-1);                       // down → more bits, whole-bit step
        assertEquals(16.5, m.getValue(), EPS);
        m.arrow(+1);
        assertEquals(15.5, m.getValue(), EPS);
    }

    @Test
    void dither_dbvStepping_walksExactlyTenDb() {
        NumericStepModel m = dither(16);
        m.setLogDisplay(true);
        m.setValue(16);
        m.wheel(+1);                       // +10 dBV exactly (fractional bits, no snap)
        assertEquals(ditherBits(ditherDbv(16) + 10), m.getValue(), EPS);
        m.setValue(16);
        m.wheel(-1);                       // −10 dBV → more bits, clamped at max
        assertEquals(16, m.getValue(), EPS);
        m.setValue(1);
        m.wheel(+1);                       // up from the loudest bit → Off
        assertEquals(0, m.getValue(), EPS);
    }

    @Test
    void dither_offHasNoCompanionView() {
        NumericStepModel m = dither(16);
        m.setValue(0);
        assertEquals("Off", m.text());
        assertEquals("", m.companionText(), "Off has no alternate view");
    }
}
