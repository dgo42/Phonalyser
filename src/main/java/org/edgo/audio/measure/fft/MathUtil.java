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

package org.edgo.audio.measure.fft;

import lombok.experimental.UtilityClass;

/**
 * Pure-math helpers used by {@link FftAnalyzer}.  Side-effect-free —
 * trivially unit-testable in isolation.
 *
 * <p>Lives in the same package as {@link FftAnalyzer} so its callers
 * can reference the unqualified names; promote to a wider visibility
 * if other packages start needing the same building blocks.
 */
@UtilityClass
public final class MathUtil {

    /** Chebyshev polynomial of the first kind T_n(x).
     *  Uses the trigonometric identity inside |x| ≤ 1 and the
     *  hyperbolic identity outside it, so the result is finite for any
     *  real x. */
    public static double chebyshevT(int n, double x) {
        if (x > 1.0) {
            return Math.cosh(n * acosh(x));
        } else if (x < -1.0) {
            return (n % 2 == 0 ? 1.0 : -1.0) * Math.cosh(n * acosh(-x));
        } else {
            return Math.cos(n * Math.acos(x));
        }
    }

    /** Inverse hyperbolic cosine: acosh(x) = ln(x + √(x²−1)), x ≥ 1. */
    public static double acosh(double x) {
        return Math.log(x + Math.sqrt(x * x - 1.0));
    }

    /**
     * Parabolic interpolation on the log-power spectrum to refine a
     * peak bin to a fractional bin index.  Uses the three-point formula
     * δ = 0.5·(α − γ) / (α − 2β + γ) where α, β, γ are log-power at
     * bins peakBin−1, peakBin, peakBin+1.
     *
     * @return fractional bin index (peakBin + δ)
     */
    public static double parabolicBinInterp(double[] re, double[] im,
                                            int peakBin, int fftSize) {
        int halfSize = fftSize / 2;
        int lo = Math.max(1, peakBin - 1);
        int hi = Math.min(halfSize, peakBin + 1);
        double pLo   = Math.log(re[lo] * re[lo] + im[lo] * im[lo] + 1e-30);
        double pMid  = Math.log(re[peakBin] * re[peakBin] + im[peakBin] * im[peakBin] + 1e-30);
        double pHi   = Math.log(re[hi] * re[hi] + im[hi] * im[hi] + 1e-30);
        double denom = pLo - 2.0 * pMid + pHi;
        if (Math.abs(denom) < 1e-15) {
            return peakBin;
        }
        double delta = 0.5 * (pLo - pHi) / denom;
        return peakBin + delta;
    }

    /** Smallest power of 2 ≥ {@code x}. Returns 1 for {@code x ≤ 1}. */
    public static int nextPow2(int x) {
        if (x <= 1) return 1;
        int hi = Integer.highestOneBit(x);
        return hi == x ? x : hi << 1;
    }
}
