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

package org.edgo.audio.measure.fft;

import lombok.experimental.UtilityClass;

/**
 * Pure-math helpers used by {@link FftAnalyzer}.  Side-effect-free -
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

    /** Added to a bin's power before its logarithm, so a bin that cancelled to
     *  exactly zero reads as a very small power instead of minus infinity. */
    private static final double BIN_POWER_FLOOR = 1e-30;
    /** Below this the parabola's curvature term is indistinguishable from zero
     *  (three equal log powers), and the fit has no peak to offer. */
    private static final double FLAT_CURVATURE = 1e-15;

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
        return parabolicBinInterp(re[lo], im[lo], re[peakBin], im[peakBin],
                re[hi], im[hi], peakBin);
    }

    /**
     * The three-value core of the fit above, for a caller that MEASURED the
     * peak bin and its two neighbours instead of holding a whole spectrum -
     * three bin phasors, one fit, the same formula and the same log-power
     * domain, so a bin refined from single-bin transforms and one refined from
     * an FFT array cannot disagree.
     *
     * <p>The caller owns the clamping: it passes whatever three bins it read,
     * and {@code peakBin} is the index the offset is measured from.
     *
     * @return fractional bin index (peakBin + δ)
     */
    public static double parabolicBinInterp(double loRe, double loIm,
                                            double midRe, double midIm,
                                            double hiRe, double hiIm, int peakBin) {
        double pLo   = Math.log(loRe * loRe + loIm * loIm + BIN_POWER_FLOOR);
        double pMid  = Math.log(midRe * midRe + midIm * midIm + BIN_POWER_FLOOR);
        double pHi   = Math.log(hiRe * hiRe + hiIm * hiIm + BIN_POWER_FLOOR);
        double denom = pLo - 2.0 * pMid + pHi;
        if (Math.abs(denom) < FLAT_CURVATURE) {
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
