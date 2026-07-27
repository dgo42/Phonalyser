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

/**
 * Online avg / min / max / variance accumulator (Welford's method) used
 * by the oscilloscope measurement table.  Skips {@link Double#NaN}
 * inputs so missing-cycle samples don't poison the window stats.
 */
public final class MeasurementStats {

    private int count;
    private double mean;
    private double m2;
    private double min = Double.POSITIVE_INFINITY;
    private double max = Double.NEGATIVE_INFINITY;

    public void add(double v) {
        if (Double.isNaN(v)) return;
        count++;
        double delta = v - mean;
        mean += delta / count;
        m2 += delta * (v - mean);
        if (v < min) min = v;
        if (v > max) max = v;
    }

    public double getMean()  { return count > 0 ? mean : Double.NaN; }
    public double getMin()   { return count > 0 ? min  : Double.NaN; }
    public double getMax()   { return count > 0 ? max  : Double.NaN; }
    public double getSigma() { return count > 1 ? Math.sqrt(m2 / (count - 1)) : Double.NaN; }
}
