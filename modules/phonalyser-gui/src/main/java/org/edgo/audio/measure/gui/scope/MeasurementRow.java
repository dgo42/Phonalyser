/*
 * Phonalyser - precision audio measurement workbench.
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

/**
 * One formatted row of the oscilloscope's measurement table.  Picks a
 * unit prefix based on the current value's magnitude and renders cur /
 * avg / min / max / σ in that same prefix so the column entries line
 * up vertically.
 *
 * <p>The texts are formatted with the default locale when the row is built;
 * the scaled numbers behind them are kept, so {@link #reformatted()} renders
 * the same readings again after a language switch.
 */
public final class MeasurementRow {

    public final String name;
    public final String cur, avg, min, max, sigma;
    /** The scaled numbers the texts above were formatted from. */
    private final double curVal, avgVal, minVal, maxVal, sigmaVal;
    /** A frequency row formats through {@link #fmtFreq}, every other row through {@link #fmt}. */
    private final boolean frequency;

    private MeasurementRow(String name, boolean frequency,
                           double cur, double avg, double min, double max, double sigma) {
        this.name = name; this.frequency = frequency;
        this.curVal = cur; this.avgVal = avg; this.minVal = min; this.maxVal = max; this.sigmaVal = sigma;
        this.cur = format(cur); this.avg = format(avg); this.min = format(min);
        this.max = format(max); this.sigma = format(sigma);
    }

    /** The same readings formatted again with the current default locale.  The
     *  numbers are not re-measured, so a held (frozen) table keeps its values
     *  and only the number format follows the language. */
    public MeasurementRow reformatted() {
        return new MeasurementRow(name, frequency, curVal, avgVal, minVal, maxVal, sigmaVal);
    }

    private String format(double v) {
        return frequency ? fmtFreq(v) : fmt(v);
    }

    public static MeasurementRow forVolts(String base, double curVal, MeasurementStats s) {
        String unit; double scale;
        double m = peakMag(curVal, s);
        if (m >= 1)     { unit = "V";  scale = 1; }
        else if (m >= 1e-3) { unit = "mV"; scale = 1e3; }
        else if (m >= 1e-6) { unit = "μV"; scale = 1e6; }
        else                 { unit = "V";  scale = 1; }
        return scaled(base + ", " + unit, scale, curVal, s);
    }

    public static MeasurementRow forTime(String base, double curVal, MeasurementStats s) {
        String unit; double scale;
        double m = peakMag(curVal, s);
        if (m >= 1)     { unit = "s";  scale = 1; }
        else if (m >= 1e-3) { unit = "ms"; scale = 1e3; }
        else if (m >= 1e-6) { unit = "μs"; scale = 1e6; }
        else                 { unit = "s";  scale = 1; }
        return scaled(base + ", " + unit, scale, curVal, s);
    }

    public static MeasurementRow forFreq(String base, double curVal, MeasurementStats s) {
        return new MeasurementRow(base + ", Hz", true,
                curVal,
                s.getMean(),
                s.getMin(),
                s.getMax(),
                s.getSigma());
    }

    /**
     * Frequency formatter capped at 7 significant digits (excluding the
     * decimal point).  Below 100 kHz the readout keeps 0.01 Hz resolution;
     * between 100 kHz and 1 MHz it drops to 0.1 Hz; at or above 1 MHz it
     * rounds to whole hertz.
     */
    private static String fmtFreq(double v) {
        if (Double.isNaN(v)) return "---";
        double a = Math.abs(v);
        if (a >= 1_000_000.0) return String.format("%.0f", v);
        if (a >= 100_000.0)   return String.format("%.1f", v);
        return String.format("%.2f", v);
    }

    public static MeasurementRow forPct(String base, double curVal, MeasurementStats s) {
        return scaled(base + ", %", 100, curVal, s);
    }

    private static double peakMag(double cur, MeasurementStats s) {
        double a = Double.isNaN(cur) ? 0 : Math.abs(cur);
        if (!Double.isNaN(s.getMean())) a = Math.max(a, Math.abs(s.getMean()));
        if (!Double.isNaN(s.getMax()))  a = Math.max(a, Math.abs(s.getMax()));
        if (!Double.isNaN(s.getMin()))  a = Math.max(a, Math.abs(s.getMin()));
        return a;
    }

    private static MeasurementRow scaled(String name, double scale, double cur, MeasurementStats s) {
        return new MeasurementRow(name, false,
                cur * scale,
                s.getMean()  * scale,
                s.getMin()   * scale,
                s.getMax()   * scale,
                s.getSigma() * scale);
    }

    private static String fmt(double v) {
        if (Double.isNaN(v)) return "---";
        double a = Math.abs(v);
        if (a == 0)   return "0.000";
        if (a >= 1000) return String.format("%.1f", v);
        if (a >= 100)  return String.format("%.2f", v);
        return String.format("%.3f", v);
    }
}
