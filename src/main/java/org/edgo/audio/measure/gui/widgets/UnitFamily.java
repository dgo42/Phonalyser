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

import java.util.List;
import java.util.Locale;

import org.edgo.audio.measure.gui.i18n.I18n;

/**
 * Unit family of a {@link NumericStepField}: the set of display units a field
 * can show and accept, with their canonical-unit conversion factors, the
 * automatic display-unit switching thresholds, and the default unit applied to
 * suffix-less input.  Display suffixes are resolved through {@link I18n}
 * ({@code unit.*} keys); parsing additionally accepts the ASCII aliases (e.g.
 * {@code "uV"} for {@code µV}) so typed input is locale-independent.
 *
 * <p>Canonical units (what the bound preference stores): Hz, Vrms, seconds,
 * percent, pixels, dB, divisions, plain count.  {@code dBV} is the one
 * logarithmic unit — {@code x dBV = 10^(x/20)} Vrms — and the only one whose
 * displayed value may be negative.
 */
public enum UnitFamily {

    /** Hz / kHz; display switches to kHz at 1000 Hz; suffix-less input is Hz. */
    FREQUENCY(0,
            new Unit("unit.hz",  1.0,   false, List.of("hz")),
            new Unit("unit.khz", 1e3,   false, List.of("khz"))),

    /** µV / mV / V / dBV; display switches mV below 0.5 V.  µV is parse-only
     *  (it renders as mV); dBV sticks for display once typed — the only unit
     *  the range switching can never choose.  Suffix-less input is V. */
    AMPLITUDE(2,
            new Unit("unit.uv",  1e-6,  false, List.of("uv")),
            new Unit("unit.mv",  1e-3,  false, List.of("mv")),
            new Unit("unit.v",   1.0,   false, List.of("v")),
            new Unit("unit.dbv", 1.0,   true,  List.of("dbv"))),

    /** ms / s; display switches to ms below 0.5 s; suffix-less input is s. */
    TIME(1,
            new Unit("unit.ms",  1e-3,  false, List.of("ms")),
            new Unit("unit.s",   1.0,   false, List.of("s"))),

    /** µs/div … s/div for the scope's horizontal resolution; suffix-less input
     *  uses the unit currently displayed. */
    TIME_PER_DIV(-1,
            new Unit("unit.usdiv", 1e-6, false, List.of("us/div", "us", "µs")),
            new Unit("unit.msdiv", 1e-3, false, List.of("ms/div", "ms")),
            new Unit("unit.sdiv",  1.0,  false, List.of("s/div", "s"))),

    /** µV/div … V/div for the scope's vertical resolution; suffix-less input
     *  uses the unit currently displayed. */
    VOLTS_PER_DIV(-1,
            new Unit("unit.uvdiv", 1e-6, false, List.of("uv/div", "uv", "µv")),
            new Unit("unit.mvdiv", 1e-3, false, List.of("mv/div", "mv")),
            new Unit("unit.vdiv",  1.0,  false, List.of("v/div", "v"))),

    PERCENT(0,   new Unit("unit.percent", 1.0, false, List.of("%"))),
    PIXEL(0,     new Unit("unit.px",      1.0, false, List.of("px"))),
    DECIBEL(0,   new Unit("unit.db",      1.0, false, List.of("db"))),
    SECONDS(0,   new Unit("unit.s",       1.0, false, List.of("s"))),
    DIVISIONS(0, new Unit("unit.div",     1.0, false, List.of("div"))),

    /** Unitless count fields (averages, harmonics, sweep points). */
    NONE(0, new Unit(null, 1.0, false, List.of()));

    /** One display/input unit of a family: i18n suffix key ({@code null} =
     *  suffix-less), canonical-unit factor (linear) or the dB(V) marker, and
     *  the locale-independent suffixes accepted on input. */
    public record Unit(String i18nKey, double factor, boolean log, List<String> aliases) {

        /** Display suffix, resolved per current locale. */
        public String suffix() {
            return i18nKey == null ? "" : I18n.t(i18nKey);
        }

        /** Displayed-unit value → canonical value. */
        public double toCanonical(double x) {
            return log ? Math.pow(10.0, x / DB_PER_DECADE) : x * factor;
        }

        /** Canonical value → displayed-unit value. */
        public double fromCanonical(double v) {
            return log ? DB_PER_DECADE * Math.log10(v) : v / factor;
        }

        /** True when {@code typed} (already trimmed) names this unit — the
         *  localized suffix or any ASCII alias, case-insensitively.
         *  {@link Locale#ROOT} folding: under the Turkish default locale
         *  {@code "US/DIV".toLowerCase()} yields a dotless ı and would miss
         *  the alias table. */
        public boolean matches(String typed) {
            if (typed.isEmpty()) return false;
            if (typed.equalsIgnoreCase(suffix())) return true;
            String lower = typed.toLowerCase(Locale.ROOT);
            return aliases.contains(lower);
        }
    }

    /** dB per factor-of-10 amplitude — the dBV ↔ Vrms exponent scale. */
    private static final double DB_PER_DECADE = 20.0;
    /** FREQUENCY display switches Hz → kHz here. */
    private static final double KILO_SWITCH_HZ = 1e3;
    /** AMPLITUDE display switches mV → V and TIME switches ms → s here. */
    private static final double HALF_UNIT_SWITCH = 0.5;
    /** Per-div families switch µx → mx here… */
    private static final double MILLI_SWITCH = 1e-3;
    /** …and mx → x here. */
    private static final double UNIT_SWITCH = 1.0;

    private final Unit[] units;
    /** Index of the unit applied to suffix-less input; −1 = use the unit
     *  currently displayed (the per-div families). */
    private final int defaultUnitIndex;

    private UnitFamily(int defaultUnitIndex, Unit... units) {
        this.defaultUnitIndex = defaultUnitIndex;
        this.units = units;
    }

    /** The display unit for {@code canonical} per the family's switching
     *  thresholds.  Sticky explicitly-typed units (µV, dBV) are the model's
     *  concern — this is the automatic choice only. */
    public Unit displayUnit(double canonical) {
        switch (this) {
            case FREQUENCY:     return canonical < KILO_SWITCH_HZ   ? units[0] : units[1];
            case AMPLITUDE:     return canonical < HALF_UNIT_SWITCH ? units[1] : units[2];
            case TIME:          return canonical < HALF_UNIT_SWITCH ? units[0] : units[1];
            case TIME_PER_DIV:
            case VOLTS_PER_DIV: return canonical < MILLI_SWITCH ? units[0]
                                     : canonical < UNIT_SWITCH  ? units[1] : units[2];
            default:            return units[0];
        }
    }

    /** The unit applied to input typed without a suffix.  {@code canonical} is
     *  the field's current value — the per-div families default to whatever
     *  unit is currently displayed. */
    public Unit defaultUnit(double canonical) {
        return defaultUnitIndex >= 0 ? units[defaultUnitIndex] : displayUnit(canonical);
    }

    /** Resolves a typed suffix to this family's unit, or {@code null} when the
     *  suffix names no unit of the family.  Longest-suffix families ("kHz" vs
     *  "Hz") work because each unit matches exactly, not by prefix. */
    public Unit match(String typedSuffix) {
        for (Unit u : units) {
            if (u.matches(typedSuffix)) return u;
        }
        return null;
    }
}
