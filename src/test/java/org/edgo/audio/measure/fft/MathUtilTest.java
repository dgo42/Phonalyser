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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the pure-math helpers in {@link MathUtil}.  Three families:
 * Chebyshev polynomial T_n(x), inverse hyperbolic cosine, and the
 * parabolic three-point interpolation used to refine an FFT peak bin.
 */
class MathUtilTest {

    private static final double EPS = 1e-9;

    // ─── acosh ──────────────────────────────────────────────────────────────

    @Test
    void acosh_one_isZero() {
        // cosh(0) = 1, so acosh(1) = 0.
        assertEquals(0.0, MathUtil.acosh(1.0), EPS);
    }

    @Test
    void acosh_matchesMathDefinition() {
        // acosh(x) = ln(x + sqrt(x^2 - 1)).  Check against a hand-computed
        // sample: acosh(2) = ln(2 + sqrt(3)) ≈ 1.3169578969.
        double expected = Math.log(2.0 + Math.sqrt(3.0));
        assertEquals(expected, MathUtil.acosh(2.0), EPS);
    }

    // ─── chebyshevT ─────────────────────────────────────────────────────────

    @Test
    void chebyshevT_lowOrders() {
        // T_0(x) = 1, T_1(x) = x, T_2(x) = 2x^2 - 1.
        assertEquals( 1.0, MathUtil.chebyshevT(0, 0.7), EPS);
        assertEquals( 0.7, MathUtil.chebyshevT(1, 0.7), EPS);
        assertEquals(2.0 * 0.7 * 0.7 - 1.0, MathUtil.chebyshevT(2, 0.7), EPS);
    }

    @Test
    void chebyshevT_endpoints() {
        // T_n(1) = 1 and T_n(-1) = (-1)^n for every n.
        for (int n = 0; n <= 8; n++) {
            assertEquals( 1.0, MathUtil.chebyshevT(n,  1.0), EPS, "T_" + n + "(1)");
            assertEquals((n % 2 == 0) ? 1.0 : -1.0,
                    MathUtil.chebyshevT(n, -1.0), EPS, "T_" + n + "(-1)");
        }
    }

    @Test
    void chebyshevT_outsideUnitInterval() {
        // For |x| > 1, T_n grows as cosh(n*acosh(|x|)).  Check the symmetric
        // |x| > 1 branch: T_n(x) and T_n(-x) differ only by a (-1)^n sign.
        double x = 1.4;
        for (int n = 1; n <= 6; n++) {
            double pos = MathUtil.chebyshevT(n,  x);
            double neg = MathUtil.chebyshevT(n, -x);
            double sign = (n % 2 == 0) ? 1.0 : -1.0;
            assertEquals(pos * sign, neg, Math.abs(pos) * 1e-12 + EPS,
                    "T_" + n + " sign symmetry");
            // And the magnitude grows (not constant) past the unit interval.
            assertTrue(Math.abs(pos) > 1.0, "T_" + n + "(" + x + ") should exceed 1");
        }
    }

    // ─── parabolicBinInterp ─────────────────────────────────────────────────

    @Test
    void parabolicBinInterp_exactPeak_returnsBin() {
        // Synthesise a power spectrum where bin 100 is the peak and the
        // log-power profile around it is exactly parabolic.  The
        // interpolator should report the peak at exactly bin 100 (zero
        // fractional offset).
        int fftSize = 256;
        int half = fftSize / 2;
        double[] re = new double[fftSize];
        double[] im = new double[fftSize];
        // bin 99, 100, 101 → magnitudes 1, e, 1   (so log = 0, 1, 0)
        re[99]  = 1.0;
        re[100] = Math.E;
        re[101] = 1.0;
        // Fill the rest with a small floor so logs are finite.
        for (int k = 0; k < fftSize; k++) {
            if (k != 99 && k != 100 && k != 101) re[k] = 1e-10;
        }

        double refined = MathUtil.parabolicBinInterp(re, im, 100, fftSize);
        assertEquals(100.0, refined, 1e-6,
                "symmetric parabola → fractional bin is the peak itself");
        // Stays in valid range
        assertTrue(refined >= 1 && refined <= half);
    }

    @Test
    void parabolicBinInterp_asymmetric_shiftsToHigherSide() {
        // Concave parabola at bin 100 with γ > α: the formula returns
        // peakBin + 0.5·(α − γ)/(α − 2β + γ).  Use log-powers 1 (lo), 4
        // (mid), 3 (hi): α=1, β=4, γ=3 → δ = 0.5·(1−3)/(1−8+3) = 0.25.
        // So the refined bin is 100.25 — between 100 and 101.
        int fftSize = 256;
        double[] re = new double[fftSize];
        double[] im = new double[fftSize];
        re[99]  = Math.exp(0.5);   // log(re²) = 1
        re[100] = Math.exp(2.0);   // log(re²) = 4    ← local max
        re[101] = Math.exp(1.5);   // log(re²) = 3

        double refined = MathUtil.parabolicBinInterp(re, im, 100, fftSize);
        assertTrue(refined > 100.0, "asymmetric peak shifts toward higher bin");
        assertTrue(refined < 101.0, "but no further than one bin");
        assertEquals(100.25, refined, 1e-9);
    }

    @Test
    void parabolicBinInterp_flatNeighbourhood_returnsPeakBin() {
        // alpha == beta == gamma → denom = 0 in the formula; the
        // implementation returns peakBin verbatim instead of dividing by
        // zero.
        int fftSize = 256;
        double[] re = new double[fftSize];
        double[] im = new double[fftSize];
        re[49] = re[50] = re[51] = 1.0;

        double refined = MathUtil.parabolicBinInterp(re, im, 50, fftSize);
        assertEquals(50.0, refined, EPS);
    }

    @Test
    void parabolicBinInterp_handlesEdgeBins() {
        // Peak at bin 1 (lowest valid).  Implementation clamps lo to 1
        // (so pLo == pMid), which makes the parabolic fit degenerate.
        // The result may fall outside [peakBin, peakBin+1] in this edge
        // case; we only assert finiteness so the analyser doesn't blow
        // up at the spectrum's lowest bin.
        int fftSize = 256;
        double[] re = new double[fftSize];
        double[] im = new double[fftSize];
        re[1] = 2.0;
        re[2] = 1.5;

        double refined = MathUtil.parabolicBinInterp(re, im, 1, fftSize);
        assertTrue(Double.isFinite(refined),
                "edge-bin call must not produce NaN/Infinity");
    }
}
