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
import org.edgo.audio.measure.enums.FftMagnitudeUnit;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-math tick-position calculators for the FFT chart axes.  Decoupled
 * from {@link FftView} so the formulas are unit-testable without
 * instantiating an SWT widget.  Magnitude axis ticks switch between
 * linear-dB and log-decade modes based on {@link FftMagnitudeUnit};
 * frequency axis ticks switch between linear and log via the
 * {@code logFreq} flag.
 */
@UtilityClass
public class FftAxisTicks {

    /**
     * Returns the magnitude-axis minor (unlabelled) ticks for the visible
     * {@code [magBot, magTop]} range.  Linear (dB) axes walk a fixed 5 dB
     * grid minus the major positions; log axes emit every 1..9 × 10ⁿ
     * minus the labelled subset, then drop pairs closer than 5 dB so
     * the column never renders denser than the 5-dB grid.
     */
    public double[] magMinor(double magBot, double magTop, FftMagnitudeUnit unit) {
        if (isMagLog(unit)) {
            double[] all   = logMagAllSteps(magBot, magTop);
            double[] major = logMagMajor(magBot, magTop, true);
            return enforceMinDbSpacing(subtract(all, major), unit, 5.0);
        }
        double[] major = niceLinear(magBot, magTop, 10);
        double minorStep = 5.0;
        double first = Math.ceil(magBot / minorStep) * minorStep;
        List<Double> out = new ArrayList<>();
        for (double m = first; m <= magTop + 1e-9; m += minorStep) {
            if (m < magBot || m > magTop) continue;
            boolean isMajor = false;
            for (double M : major) {
                if (Math.abs(m - M) < 1e-6) { isMajor = true; break; }
            }
            if (!isMajor) out.add(m);
        }
        double[] arr = new double[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    /**
     * Keeps only positions whose dB-equivalent distance from the previous
     * kept value is at least {@code minDb}.  Used on log magnitude units
     * where each 1..9 × 10ⁿ position is {@code 20·log10(v)} dB.
     */
    public double[] enforceMinDbSpacing(double[] positions, FftMagnitudeUnit unit, double minDb) {
        if (positions.length == 0) return positions;
        List<Double> out = new ArrayList<>();
        double prevDb = Double.NEGATIVE_INFINITY;
        for (double v : positions) {
            double db = isMagLog(unit)
                    ? 20 * Math.log10(Math.max(Double.MIN_NORMAL, v))
                    : v;
            if (db - prevDb < minDb) continue;
            out.add(v);
            prevDb = db;
        }
        double[] arr = new double[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    /**
     * Every 1..9 × 10ⁿ position in the visible log-magnitude range, with
     * no thinning — used by {@link #magMinor} to derive the unlabelled
     * subset.
     */
    public double[] logMagAllSteps(double magBot, double magTop) {
        double lo = Math.min(magBot, magTop);
        double hi = Math.max(magBot, magTop);
        if (lo <= 0) lo = 1e-30;
        if (hi <= lo) hi = lo * 10;
        int loDec = (int) Math.floor(Math.log10(lo));
        int hiDec = (int) Math.ceil (Math.log10(hi));
        List<Double> out = new ArrayList<>();
        for (int d = loDec; d <= hiDec; d++) {
            double base = Math.pow(10, d);
            for (int m = 1; m <= 9; m++) {
                double v = base * m;
                if (v < lo || v > hi) continue;
                out.add(v);
            }
        }
        double[] arr = new double[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    /**
     * Minor (unlabelled) ticks on the frequency axis.  Returns positions
     * that aren't already in {@link #freqMajor} so callers can draw a
     * short tick mark at each.
     */
    public double[] freqMinor(double freqMin, double freqMax, boolean logFreq) {
        if (logFreq) {
            double[] all   = logFreqAll(freqMin, freqMax, false);
            double[] major = logFreqAll(freqMin, freqMax, true);
            return subtract(all, major);
        }
        double[] major = niceLinear(freqMin, freqMax, 10);
        if (major.length < 2) return new double[0];
        double step = major[1] - major[0];
        double sub = step / 5.0;
        List<Double> out = new ArrayList<>();
        for (double f = major[0] - 4 * sub; f <= freqMax; f += sub) {
            if (f < freqMin || f > freqMax) continue;
            boolean isMajor = false;
            for (double m : major) {
                if (Math.abs(f - m) < sub * 0.5) { isMajor = true; break; }
            }
            if (!isMajor) out.add(f);
        }
        double[] arr = new double[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    /**
     * Major-tick values for the frequency axis (used by both grid + labels).
     * Linear axes use {@link #niceLinear}; log axes use
     * {@link #logFreqAll} with the labels-only thinning enabled.
     */
    public double[] freqMajor(double freqMin, double freqMax, boolean logFreq) {
        if (logFreq) return logFreqAll(freqMin, freqMax, true);
        return niceLinear(freqMin, freqMax, 10);
    }

    /**
     * REW-style log frequency ticks: at each decade emit 1×, 2×, … 9× × 10ⁿ.
     * When {@code labelsOnly = true} returns only the values that should
     * get a printed label, auto-thinned by visible decade count.
     */
    public double[] logFreqAll(double freqMin, double freqMax, boolean labelsOnly) {
        double safeMin = Math.max(1, freqMin);
        double safeMax = Math.max(safeMin + 1, freqMax);
        int loDec = (int) Math.floor(Math.log10(safeMin));
        int hiDec = (int) Math.ceil (Math.log10(safeMax));
        List<Double> out = new ArrayList<>();
        for (int d = loDec; d <= hiDec; d++) {
            double base = Math.pow(10, d);
            for (int m = 1; m <= 9; m++) {
                double f = base * m;
                if (f < safeMin || f > safeMax) continue;
                out.add(f);
            }
        }
        if (!labelsOnly) {
            double[] arr = new double[out.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
            return arr;
        }
        int decades = hiDec - loDec;
        int[] keep;
        if (decades <= 1)      keep = new int[] {1,2,3,4,5,6,7,8};
        else if (decades <= 2) keep = new int[] {1,2,3,4,5,6,8};
        else if (decades <= 3) keep = new int[] {1,2,3,5,7};
        else if (decades <= 5) keep = new int[] {1,2,5};
        else                   keep = new int[] {1};
        List<Double> labels = new ArrayList<>();
        for (double f : out) {
            double base = Math.pow(10, Math.floor(Math.log10(f)));
            int   m    = (int) Math.round(f / base);
            for (int k : keep) if (k == m) { labels.add(f); break; }
        }
        double[] arr = new double[labels.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = labels.get(i);
        return arr;
    }

    /**
     * Major ticks on a log magnitude axis — emits 1×10ⁿ decades by default,
     * with 2× and 5× intermediates added when the visible decade count is
     * small enough that they fit without crowding.
     */
    public double[] logMagMajor(double magBot, double magTop, boolean labelsOnly) {
        double lo = Math.min(magBot, magTop);
        double hi = Math.max(magBot, magTop);
        if (lo <= 0) lo = 1e-30;
        if (hi <= lo) hi = lo * 10;
        int loDec = (int) Math.floor(Math.log10(lo));
        int hiDec = (int) Math.ceil (Math.log10(hi));
        int decades = Math.max(1, hiDec - loDec);
        int[] keep;
        if      (decades <= 3) keep = new int[] {1, 2, 5};
        else if (decades <= 6) keep = new int[] {1, 2};
        else                   keep = new int[] {1};
        List<Double> out = new ArrayList<>();
        for (int d = loDec; d <= hiDec; d++) {
            double base = Math.pow(10, d);
            for (int m : keep) {
                double v = base * m;
                if (v < lo || v > hi) continue;
                out.add(v);
            }
        }
        if (labelsOnly && decades > 8) {
            List<Double> labels = new ArrayList<>();
            for (double v : out) {
                double base = Math.pow(10, Math.floor(Math.log10(v)));
                if (Math.abs(v / base - 1) < 1e-9) labels.add(v);
            }
            out = labels;
        }
        double[] arr = new double[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    /**
     * Same as {@link #niceLinear} but rounds the chosen step UP to at
     * least {@code minStep} so callers can enforce a per-axis density
     * floor (e.g. "no ticks closer than 5 dB" on the magnitude axis).
     */
    public double[] niceLinearMinStep(double min, double max, int targetCount, double minStep) {
        double[] candidate = niceLinear(min, max, targetCount);
        if (candidate.length < 2) return candidate;
        double step = candidate[1] - candidate[0];
        if (step >= minStep) return candidate;
        double first = Math.ceil(min / minStep) * minStep;
        List<Double> out = new ArrayList<>();
        for (double f = first; f <= max + 1e-9; f += minStep) out.add(f);
        double[] arr = new double[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    /** ~{@code targetCount} round-number tick positions between {@code min}
     *  and {@code max}.  Step is 1 / 2 / 2.5 / 5 × 10ⁿ. */
    public double[] niceLinear(double min, double max, int targetCount) {
        double range = Math.max(1e-9, max - min);
        double rough = range / Math.max(1, targetCount);
        double pow   = Math.pow(10, Math.floor(Math.log10(rough)));
        double mant  = rough / pow;
        double step;
        if      (mant < 1.5) step = 1     * pow;
        else if (mant < 3)   step = 2     * pow;
        else if (mant < 4)   step = 2.5   * pow;
        else if (mant < 7)   step = 5     * pow;
        else                 step = 10    * pow;
        double first = Math.ceil(min / step) * step;
        List<Double> out = new ArrayList<>();
        for (double f = first; f <= max + step * 1e-9; f += step) out.add(f);
        double[] arr = new double[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    /** Elements of {@code all} not present in {@code exclude} (per a small
     *  numeric tolerance).  Used to split the log axis's all-minor-tick
     *  list into a labelled subset + unlabelled remainder. */
    public double[] subtract(double[] all, double[] exclude) {
        List<Double> out = new ArrayList<>();
        for (double v : all) {
            boolean keep = true;
            for (double e : exclude) {
                if (Math.abs(v - e) < 1e-9 * Math.max(1, Math.abs(v))) { keep = false; break; }
            }
            if (keep) out.add(v);
        }
        double[] arr = new double[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    /** Magnitude axis is log-scaled for {@code V} and {@code V/√Hz}; dBV
     *  and dBFS are already log-domain so the axis stays linear in dB. */
    public boolean isMagLog(FftMagnitudeUnit unit) {
        return unit == FftMagnitudeUnit.V || unit == FftMagnitudeUnit.V_SQRT_HZ;
    }
}
