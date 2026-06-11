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

import org.edgo.audio.measure.preferences.Preferences;

import java.util.Locale;

/**
 * Converts the user-facing labels from the V/div and t/div pick lists back
 * into numeric values (volts and seconds) for layout / drawing math.
 *
 * <p>The labels themselves live as constant arrays inside {@code MainWindow}
 * (see {@code VOLT_PER_DIV} / {@code TIME_PER_DIV}), and the indices into
 * those arrays are stored in {@link Preferences}.  These helpers exist so
 * the {@code OscilloscopeView} / {@code CondensedView} renderers don't have
 * to duplicate the lookup tables.
 */
public final class OscParse {

    /** Same content as {@code MainWindow.VOLT_PER_DIV}.  Kept in sync manually. */
    private static final String[] VOLT_PER_DIV = {
            "1 μV/div", "2 μV/div", "5 μV/div",
            "10 μV/div", "20 μV/div", "50 μV/div",
            "100 μV/div", "200 μV/div", "500 μV/div",
            "1 mV/div", "2 mV/div", "5 mV/div",
            "10 mV/div", "20 mV/div", "50 mV/div",
            "100 mV/div", "200 mV/div", "500 mV/div",
            "1 V/div", "2 V/div", "5 V/div",
            "10 V/div", "20 V/div", "50 V/div",
            "100 V/div", "200 V/div", "500 V/div"
    };
    /** Same content as {@code MainWindow.TIME_PER_DIV}. */
    private static final String[] TIME_PER_DIV = {
            "1 μs/div", "2 μs/div", "5 μs/div",
            "10 μs/div", "20 μs/div", "50 μs/div",
            "100 μs/div", "200 μs/div", "500 μs/div",
            "1 ms/div", "2 ms/div", "5 ms/div",
            "10 ms/div", "20 ms/div", "50 ms/div",
            "100 ms/div", "200 ms/div", "500 ms/div",
            "1 s/div"
    };

    private OscParse() {}

    /** Legacy helper — returns the V/div value for the given list index.
     *  Public so {@link org.edgo.audio.measure.gui.Preferences} can migrate
     *  old integer-index prefs into the new double-valued field. */
    public static double voltsPerDivFromIdx(int index) {
        if (index < 0 || index >= VOLT_PER_DIV.length) index = 15; // "100 mV/div"
        return parseUnit(VOLT_PER_DIV[index], "V");
    }

    /** Legacy helper — returns the t/div value for the given list index. */
    public static double timePerDivFromIdx(int index) {
        if (index < 0 || index >= TIME_PER_DIV.length) index = 6;  // "1 ms/div"
        return parseUnit(TIME_PER_DIV[index], "s");
    }

    /** Returns the standard V/div step list as an array of values in volts,
     *  ascending.  Series for the scope's V/div field — its wheel and arrows
     *  step through these "nice" values from a free-form current value. */
    public static double[] voltsPerDivTargets() {
        double[] out = new double[VOLT_PER_DIV.length];
        for (int i = 0; i < VOLT_PER_DIV.length; i++) {
            out[i] = parseUnit(VOLT_PER_DIV[i], "V");
        }
        return out;
    }

    /** Same as {@link #voltsPerDivTargets()} but for the t/div step list. */
    public static double[] timePerDivTargets() {
        double[] out = new double[TIME_PER_DIV.length];
        for (int i = 0; i < TIME_PER_DIV.length; i++) {
            out[i] = parseUnit(TIME_PER_DIV[i], "s");
        }
        return out;
    }

    /** Pretty-prints {@code volts} as "{@code N μV/div}", "{@code N mV/div}"
     *  or "{@code N V/div}" with auto-prefix — the scope measurement rows'
     *  display format. */
    public static String formatVoltsPerDiv(double volts) {
        return formatWithUnit(volts, "V") + "/div";
    }

    /** Pretty-prints {@code seconds} as "{@code N ns/div}", "{@code N μs/div}"
     *  / "{@code N ms/div}" / "{@code N s/div}" with auto-prefix. */
    public static String formatTimePerDiv(double seconds) {
        return formatWithUnit(seconds, "s") + "/div";
    }

    /** Shared auto-prefix formatter — picks the SI prefix that lands the
     *  mantissa in the [1, 1000) range when possible.  Trailing zeros are
     *  stripped from the decimal part for tidier display. */
    private static String formatWithUnit(double v, String baseUnit) {
        double a = Math.abs(v);
        double scaled;
        String prefix;
        if (a == 0.0) { scaled = 0.0; prefix = ""; }
        else if (a >= 1.0)   { scaled = v;       prefix = "";  }
        else if (a >= 1e-3)  { scaled = v * 1e3; prefix = "m"; }
        else if (a >= 1e-6)  { scaled = v * 1e6; prefix = "µ"; }
        else                 { scaled = v * 1e9; prefix = "n"; }
        // Strip trailing zeros after the decimal point so e.g. 100.000
        // shows as "100" but 45.5 shows as "45.5".
        String s = String.format(Locale.ROOT, "%.3f", scaled);
        if (s.contains(".")) {
            int end = s.length();
            while (end > 0 && s.charAt(end - 1) == '0') end--;
            if (end > 0 && s.charAt(end - 1) == '.') end--;
            s = s.substring(0, end);
        }
        return s + " " + prefix + baseUnit;
    }

    /**
     * Parses a label like {@code "100 mV/div"} or {@code "10 μs/div"} into
     * the corresponding numeric value in base units (volts or seconds).
     */
    private static double parseUnit(String label, String baseUnit) {
        String stripped = label.replace("/div", "").trim();
        int spaceIdx = stripped.indexOf(' ');
        if (spaceIdx <= 0) return 1.0;
        double value = Double.parseDouble(stripped.substring(0, spaceIdx));
        String unit  = stripped.substring(spaceIdx + 1);
        double mult = 1.0;
        if (unit.startsWith("μ") || unit.startsWith("u")) mult = 1e-6;
        else if (unit.startsWith("m"))                    mult = 1e-3;
        // bare "V" / "s" → mult = 1.0
        return value * mult;
    }

}
