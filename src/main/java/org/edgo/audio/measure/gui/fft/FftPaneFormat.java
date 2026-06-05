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

import lombok.experimental.UtilityClass;
import org.edgo.audio.measure.enums.AmplitudeUnit;
import org.edgo.audio.measure.gui.preferences.Preferences;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure text / number parsers + formatters used by the FFT settings pane.
 * Decoupled from {@link FftPane} so the formulas are unit-testable
 * without instantiating an SWT widget.  Handles window-type and overlap
 * labels, FFT-length and frequency abbreviations, amplitude unit
 * parsing/formatting, and the averages-field cycling stepper.
 */
@UtilityClass
public class FftPaneFormat {

    /** Averages presets used by the cycling stepper (wheel / arrows). */
    public static final double[] AVERAGES_PRESETS = { 2, 4, 8, 16, 32, Double.POSITIVE_INFINITY };

    public String shortFftLength(int n) {
        if (n >= 1 << 20) return (n >> 20) + "M";
        if (n >= 1 << 10) return (n >> 10) + "k";
        return Integer.toString(n);
    }

    public String shortDistRange(Preferences prefs) {
        if (!prefs.isFftDistMinEnabled() && !prefs.isFftDistMaxEnabled()) return "all";
        String lo = prefs.isFftDistMinEnabled() ? shortHz(prefs.getFftDistMinHz()) : "0";
        String hi = prefs.isFftDistMaxEnabled() ? shortHz(prefs.getFftDistMaxHz()) : "∞";
        return lo + "-" + hi;
    }

    public String shortHz(double f) {
        if (f >= 1000) return ((int) Math.round(f / 1000)) + "k";
        return Long.toString(Math.round(f));
    }

    public int indexOfInt(int[] arr, int value) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == value) return i;
        return -1;
    }

    public <E extends Enum<E>> E enumOr(Class<E> type, String name, E fallback) {
        if (name == null) return fallback;
        try { return Enum.valueOf(type, name); }
        catch (IllegalArgumentException e) { return fallback; }
    }

    /**
     * Parses an amplitude with an embedded unit suffix.  Accepts trailing
     * {@code mV}, {@code V} (or no suffix), {@code dBV}, {@code dBFS}.
     * Side-effect: stores the chosen unit into Preferences so the
     * formatter re-renders the value in the same unit the user typed.
     * Canonical return value is volts (linear).
     */
    public Double parseAmplitudeWithUnit(String s) {
        if (s == null) return null;
        String t = s.trim().replace(',', '.');
        if (t.isEmpty()) return null;
        String unit = "V";
        String numText = t;
        Matcher m = Pattern
                .compile("([+-]?[0-9]*\\.?[0-9]+(?:[eE][+-]?[0-9]+)?)\\s*([a-zA-Z]*)$")
                .matcher(t);
        if (!m.matches()) return null;
        numText = m.group(1);
        String u = m.group(2);
        if (!u.isEmpty()) {
            if      (u.equalsIgnoreCase("mV"))   unit = "mV";
            else if (u.equalsIgnoreCase("V"))    unit = "V";
            else if (u.equalsIgnoreCase("dBV"))  unit = "dBV";
            else if (u.equalsIgnoreCase("dBFS")) unit = "dBFS";
            else return null;
        }
        double raw;
        try { raw = Double.parseDouble(numText); }
        catch (NumberFormatException ex) { return null; }
        double v;
        switch (unit) {
            case "mV":   v = raw * 0.001; break;
            case "V":    v = raw;         break;
            case "dBV":
            case "dBFS": v = Math.pow(10, raw / 20.0); break;
            default:     v = raw;
        }
        Preferences.instance().setFftManualFundUnit(AmplitudeUnit.fromString(unit));
        return v;
    }

    public String formatAmplitudeWithUnit(double vrms, String unit) {
        if (unit == null) unit = "V";
        switch (unit) {
            case "mV":   return String.format("%.3f mV",  vrms * 1000);
            case "dBV":  return String.format("%+.3f dBV", 20 * Math.log10(Math.max(1e-30, vrms)));
            case "dBFS": return String.format("%+.3f dBFS",20 * Math.log10(Math.max(1e-30, vrms)));
            case "V":
            default:     return String.format("%.4f V", vrms);
        }
    }

    /** Wheel / arrow stepper for the averages field — snaps to the next
     *  / previous preset rather than ±1.  Manual typing in the field is
     *  unaffected because parsing goes through {@link #parseAveragesNumeric}. */
    public double stepAveragesCycle(double current, int dir) {
        if (dir > 0) {
            for (double t : AVERAGES_PRESETS) if (t > current + 1e-9) return t;
            return Double.POSITIVE_INFINITY;
        }
        double best = 2;
        for (double t : AVERAGES_PRESETS) {
            if (Double.isInfinite(t)) break;
            if (t < current - 1e-9) best = t; else break;
        }
        return best;
    }

    /** Parses an averages-field value — accepts any positive integer
     *  AND the special tokens {@code "∞"} / {@code "forever"} → +Infinity. */
    public Double parseAveragesNumeric(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        if (t.equals("∞") || t.equalsIgnoreCase("forever") || t.equalsIgnoreCase("inf")) {
            return Double.POSITIVE_INFINITY;
        }
        try {
            long n = Long.parseLong(t);
            return n >= 1 ? (double) n : null;
        } catch (NumberFormatException e) { return null; }
    }

    /** Formats an averages value — {@code +Infinity} → {@code "∞"},
     *  finite values → plain integer string. */
    public String formatAverages(double v) {
        if (Double.isInfinite(v)) return "∞";
        return Long.toString((long) Math.round(v));
    }

    public Double parseDoubleStrict(String s) {
        try { return Double.parseDouble(s.trim().replace(',', '.')); }
        catch (NumberFormatException e) { return null; }
    }

    public Double parseIntStrict(String s) {
        try { return (double) Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return null; }
    }
}
