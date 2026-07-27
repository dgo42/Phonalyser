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

package org.edgo.audio.measure.dsp;

import lombok.Getter;

/**
 * Exact 3-parameter least-squares sine fit at a fixed frequency.
 *
 * <p>Model: {@code y[k] = a·sin(omega·k) + b·cos(omega·k) + c}, where
 * {@code omega = 2·PI·frequencyHz/sampleRate} and {@code k} counts samples from
 * the fit window start.  With the frequency held fixed the model is linear in
 * {@code a, b, c}, so a single pass builds the 3×3 normal equations (accumulated
 * via a sin/cos rotation recurrence, no per-sample trig) and a partial-pivoted
 * Gaussian elimination solves them.
 *
 * <p>Ported verbatim from {@code RegressionCalibrator.fitSine}.  Not thread-safe
 * in the sense of shared mutable state, but instances are immutable once built.
 */
public final class SineFit {

    /** Sine coefficient of the fit model. */
    @Getter
    private final double a;
    /** Cosine coefficient of the fit model. */
    @Getter
    private final double b;
    /** DC offset of the fit model. */
    @Getter
    private final double c;
    /** Fixed frequency the fit was performed at (Hz). */
    @Getter
    private final double frequencyHz;
    /** Angular increment per sample (rad), {@code 2·PI·frequencyHz/sampleRate}. */
    private final double omega;

    private SineFit(double a, double b, double c, double frequencyHz, double omega) {
        this.a           = a;
        this.b           = b;
        this.c           = c;
        this.frequencyHz = frequencyHz;
        this.omega       = omega;
    }

    /**
     * Fits the model over the whole {@code samples} array.
     *
     * @param samples    signal samples, {@code k = 0} at index 0
     * @param sampleRate sample rate in Hz
     * @param freqHz     fixed frequency to fit at (Hz)
     * @return the fitted model
     */
    public static SineFit of(double[] samples, int sampleRate, double freqHz) {
        double omega    = 2.0 * Math.PI * freqHz / sampleRate;
        double cosOmega = Math.cos(omega), sinOmega = Math.sin(omega);
        double curSin = 0.0, curCos = 1.0;
        int N = samples.length;

        // Accumulate normal-equation matrix entries
        double ss = 0, sc = 0, s1 = 0, cc = 0, c1 = 0;
        double ys = 0, yc = 0, y1 = 0;
        for (int n = 0; n < N; n++) {
            double sn = curSin, cn = curCos, yn = samples[n];
            ss += sn * sn;  sc += sn * cn;  s1 += sn;
            cc += cn * cn;  c1 += cn;
            ys += yn * sn;  yc += yn * cn;  y1 += yn;
            double nextSin = sn * cosOmega + cn * sinOmega;
            curCos = cn * cosOmega - sn * sinOmega;
            curSin = nextSin;
        }
        return fromNormalEquations(ss, sc, s1, cc, c1, N, ys, yc, y1, freqHz, omega);
    }

    /**
     * Fits the model over {@code data[from .. from+len)}, with {@code k = 0} at
     * index {@code from}.
     *
     * @param data       signal buffer
     * @param from       window start index
     * @param len        window length in samples
     * @param sampleRate sample rate in Hz
     * @param freqHz     fixed frequency to fit at (Hz)
     * @return the fitted model
     */
    public static SineFit of(float[] data, int from, int len, double sampleRate, double freqHz) {
        double omega    = 2.0 * Math.PI * freqHz / sampleRate;
        double cosOmega = Math.cos(omega), sinOmega = Math.sin(omega);
        double curSin = 0.0, curCos = 1.0;

        double ss = 0, sc = 0, s1 = 0, cc = 0, c1 = 0;
        double ys = 0, yc = 0, y1 = 0;
        for (int i = 0; i < len; i++) {
            double sn = curSin, cn = curCos, yn = data[from + i];
            ss += sn * sn;  sc += sn * cn;  s1 += sn;
            cc += cn * cn;  c1 += cn;
            ys += yn * sn;  yc += yn * cn;  y1 += yn;
            double nextSin = sn * cosOmega + cn * sinOmega;
            curCos = cn * cosOmega - sn * sinOmega;
            curSin = nextSin;
        }
        return fromNormalEquations(ss, sc, s1, cc, c1, len, ys, yc, y1, freqHz, omega);
    }

    /**
     * Solves the accumulated 3×3 normal-equation system by Gaussian elimination
     * with partial pivoting and wraps the result.
     *
     * <pre>
     * [ss sc s1] [a]   [ys]
     * [sc cc c1] [b] = [yc]
     * [s1 c1  N] [c]   [y1]
     * </pre>
     */
    private static SineFit fromNormalEquations(double ss, double sc, double s1,
                                               double cc, double c1, int n,
                                               double ys, double yc, double y1,
                                               double freqHz, double omega) {
        double[][] aug = {
                {ss, sc, s1, ys},
                {sc, cc, c1, yc},
                {s1, c1, n,  y1},
        };
        for (int col = 0; col < 3; col++) {
            int maxRow = col;
            for (int row = col + 1; row < 3; row++) {
                if (Math.abs(aug[row][col]) > Math.abs(aug[maxRow][col])) maxRow = row;
            }
            double[] tmp = aug[col]; aug[col] = aug[maxRow]; aug[maxRow] = tmp;
            double diag = aug[col][col];
            if (Math.abs(diag) < 1e-15) continue;
            for (int row = col + 1; row < 3; row++) {
                double f = aug[row][col] / diag;
                for (int j = col; j <= 3; j++) aug[row][j] -= f * aug[col][j];
            }
        }
        double[] x = new double[3];
        for (int i = 2; i >= 0; i--) {
            x[i] = aug[i][3];
            for (int j = i + 1; j < 3; j++) x[i] -= aug[i][j] * x[j];
            x[i] /= aug[i][i];
        }
        return new SineFit(x[0], x[1], x[2], freqHz, omega);
    }

    /** Peak amplitude of the fitted sinusoid, {@code hypot(a, b)}. */
    public double amplitude() {
        return Math.hypot(a, b);
    }

    /** Initial phase of the fitted sinusoid in radians, {@code atan2(b, a)}. */
    public double phaseRadians() {
        return Math.atan2(b, a);
    }

    /**
     * Subtracts the fitted sinusoid (excluding the DC term {@code c}) from
     * {@code src[srcFrom .. srcFrom+count)} into {@code dst[dstFrom ..]}:
     * <pre>
     * dst[dstFrom+i] = src[srcFrom+i] − (a·sin(omega·(kOffset+i)) + b·cos(omega·(kOffset+i)))
     * </pre>
     * DC is left in the trace on purpose (the AC toggle handles DC).  Uses a
     * double-precision sin/cos rotation recurrence seeded at {@code omega·kOffset}
     * — one {@link Math#sin}/{@link Math#cos} pair, then rotate per sample, no
     * allocation.
     *
     * @param src     source buffer
     * @param srcFrom source start index
     * @param count   number of samples to process
     * @param kOffset sample index of {@code src[srcFrom]} relative to the fit window start
     * @param dst     destination buffer (may be {@code src})
     * @param dstFrom destination start index
     */
    public void subtractSineInto(float[] src, int srcFrom, int count,
                                 double kOffset, float[] dst, int dstFrom) {
        double startAngle = omega * kOffset;
        double curSin = Math.sin(startAngle);
        double curCos = Math.cos(startAngle);
        double cosOmega = Math.cos(omega), sinOmega = Math.sin(omega);
        for (int i = 0; i < count; i++) {
            double sine = a * curSin + b * curCos;
            dst[dstFrom + i] = (float) (src[srcFrom + i] - sine);
            double nextSin = curSin * cosOmega + curCos * sinOmega;
            curCos = curCos * cosOmega - curSin * sinOmega;
            curSin = nextSin;
        }
    }

    /**
     * Subtracts the WHOLE fitted model — sinusoid <em>and</em> the DC term
     * {@code c} — from {@code src[srcFrom .. srcFrom+count)}, then adds back a
     * caller-supplied replacement DC {@code dcAdd}, into {@code dst[dstFrom ..]}:
     * <pre>
     * dst[dstFrom+i] = src[srcFrom+i] − (a·sin(omega·(kOffset+i)) + b·cos(omega·(kOffset+i)) + c) + dcAdd
     * </pre>
     * Unlike {@link #subtractSineInto} this removes the fit's own {@code c}: over
     * a non-integer-cycle window the least-squares fit re-splits the true DC
     * between {@code c} and the windowed mean of {@code a·sin+b·cos}, so a caller
     * that wants a stable baseline removes the whole model (the residual is then
     * exactly orthogonal to the constant regressor over the fit window) and adds
     * back a stable DC of its own. Same double-precision sin/cos rotation
     * recurrence and no allocation as {@link #subtractSineInto}.
     *
     * @param src     source buffer
     * @param srcFrom source start index
     * @param count   number of samples to process
     * @param kOffset sample index of {@code src[srcFrom]} relative to the fit window start
     * @param dcAdd   DC level to add back after removing the full model
     * @param dst     destination buffer (may be {@code src})
     * @param dstFrom destination start index
     */
    public void subtractFullInto(float[] src, int srcFrom, int count,
                                 double kOffset, double dcAdd, float[] dst, int dstFrom) {
        double startAngle = omega * kOffset;
        double curSin = Math.sin(startAngle);
        double curCos = Math.cos(startAngle);
        double cosOmega = Math.cos(omega), sinOmega = Math.sin(omega);
        for (int i = 0; i < count; i++) {
            double model = a * curSin + b * curCos + c;
            dst[dstFrom + i] = (float) (src[srcFrom + i] - model + dcAdd);
            double nextSin = curSin * cosOmega + curCos * sinOmega;
            curCos = curCos * cosOmega - curSin * sinOmega;
            curSin = nextSin;
        }
    }
}
