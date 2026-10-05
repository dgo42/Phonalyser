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

package org.edgo.audio.measure.gui.fft;

import lombok.Getter;

/**
 * The last {@code capacity} readings of a value against the times they were
 * taken at, and the value a polynomial through them predicts at any other
 * time.
 *
 * <p>A reading refused on one frame - a pilot line buried for a moment -
 * leaves a hole the frame's own time can fill: the readings before it,
 * fitted against their own times, say where the value was heading, and a
 * drift that bends within the window is followed by the fit's curvature
 * where a held value or a mean would lag it.
 *
 * <p>The degree is held to one: a straight line through the held readings.
 * A curve of a higher degree follows the bend of a drift inside the window
 * and spends that curvature outside it: read one hop past the newest
 * reading it turns the readings' noise into tenths of a degree, and read
 * four window spans past it, on a bending drift of 1.4 degrees, a quadratic
 * stood at 2 degrees and a cubic at minus 12.  A line under-follows a bend
 * and never overshoots it - the conservative reading a bridged frame's
 * excess and the pair's continuity choice need.
 *
 * <p>The fit is least squares of degree one, and a constant while a single
 * reading is held.  Times enter centred on the newest reading and scaled to
 * the window's span, so the normal equations stay conditioned however the
 * clock counts; they are solved by Gaussian elimination with pivoting, and
 * a system the times cannot support - readings taken at one instant -
 * falls back on the newest value.
 *
 * <p>Not thread-safe: fed and read on one thread, like {@link MovingAverage}.
 */
public final class PhaseTrend {

    /** Highest polynomial degree fitted: a line. */
    private static final int MAX_DEGREE = 1;
    /** A pivot below this marks a system the held times cannot support. */
    private static final double SINGULAR_PIVOT = 1e-12;

    /** The held readings, oldest overwritten first. */
    private final double[] times;
    private final double[] values;
    /** How many of the slots hold a reading, up to their length. */
    @Getter
    private int count;
    /** Where the next reading goes. */
    private int next;

    public PhaseTrend(int capacity) {
        times  = new double[capacity];
        values = new double[capacity];
    }

    /** Adds a reading taken at {@code timeSec}, dropping the oldest once the
     *  window is full. */
    public void add(double timeSec, double value) {
        times[next]  = timeSec;
        values[next] = value;
        next = (next + 1) % times.length;
        if (count < times.length) count++;
    }

    /** The value the polynomial through the held readings takes at
     *  {@code timeSec}; NaN while none are held. */
    public double predict(double timeSec) {
        if (count == 0) return Double.NaN;
        int    newest = Math.floorMod(next - 1, times.length);
        double t0     = times[newest];
        double span   = 0.0;
        for (int i = 0; i < count; i++) span = Math.max(span, Math.abs(times[i] - t0));
        int degree = span > 0.0 ? Math.min(MAX_DEGREE, count - 1) : 0;
        int n      = degree + 1;
        // the normal equations, augmented with the right-hand side
        double[][] aug    = new double[n][n + 1];
        double[]   powers = new double[2 * n - 1];
        for (int i = 0; i < count; i++) {
            double x = span > 0.0 ? (times[i] - t0) / span : 0.0;
            powers[0] = 1.0;
            for (int k = 1; k < powers.length; k++) powers[k] = powers[k - 1] * x;
            for (int row = 0; row < n; row++) {
                for (int col = 0; col < n; col++) aug[row][col] += powers[row + col];
                aug[row][n] += values[i] * powers[row];
            }
        }
        double[] coef = solve(aug, n);
        if (coef == null) return values[newest];
        double x  = span > 0.0 ? (timeSec - t0) / span : 0.0;
        double y  = 0.0;
        double xp = 1.0;
        for (int k = 0; k < n; k++) {
            y  += coef[k] * xp;
            xp *= x;
        }
        return y;
    }

    /** The same, only while {@code timeSec} lies at most {@code horizonSec}
     *  past the newest reading: NaN beyond it, where a polynomial run out on
     *  its own curvature no longer says where the value is. */
    public double predict(double timeSec, double horizonSec) {
        if (count == 0) return Double.NaN;
        double newest = times[Math.floorMod(next - 1, times.length)];
        if (timeSec - newest > horizonSec) return Double.NaN;
        return predict(timeSec);
    }

    /** Forgets every held reading - the window fills again from empty. */
    public void reset() {
        count = 0;
        next  = 0;
    }

    /** Gaussian elimination with partial pivoting over the augmented
     *  {@code n x (n + 1)} system, in place; {@code null} when a pivot
     *  vanishes. */
    private double[] solve(double[][] aug, int n) {
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int row = col + 1; row < n; row++) {
                if (Math.abs(aug[row][col]) > Math.abs(aug[pivot][col])) pivot = row;
            }
            double[] tmp = aug[col];
            aug[col]   = aug[pivot];
            aug[pivot] = tmp;
            double diag = aug[col][col];
            if (Math.abs(diag) < SINGULAR_PIVOT) return null;
            for (int row = col + 1; row < n; row++) {
                double f = aug[row][col] / diag;
                for (int j = col; j <= n; j++) aug[row][j] -= f * aug[col][j];
            }
        }
        double[] x = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            x[i] = aug[i][n];
            for (int j = i + 1; j < n; j++) x[i] -= aug[i][j] * x[j];
            x[i] /= aug[i][i];
        }
        return x;
    }
}
