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

package org.edgo.audio.measure.dsp;

import lombok.experimental.UtilityClass;
import org.edgo.audio.measure.enums.FilterResponse;

/**
 * Pure special-function math backing {@link FilterDesign}: Chebyshev
 * polynomials, reverse Bessel polynomials, the complete elliptic integral K,
 * the Jacobi elliptic functions (sn/cn/dn/cd) and the elliptic rational
 * function.  These are stateless numeric primitives with no owning object -
 * {@link FilterDesign} both derives its order/constants (before the value
 * object exists) and evaluates each point through them - so they live in a
 * dedicated utility namespace rather than being smeared onto the value object.
 *
 * <p>Algorithm references:
 * <ul>
 *   <li>Chebyshev T<sub>n</sub> via cos/cosh (Abramowitz &amp; Stegun 22.3).</li>
 *   <li>Reverse Bessel polynomial recurrence
 *       θ<sub>k</sub> = (2k−1)θ<sub>k−1</sub> + s²θ<sub>k−2</sub>.</li>
 *   <li>K(k) and Jacobi sn/cn/dn via the arithmetic-geometric mean /
 *       descending Landen transformation (Abramowitz &amp; Stegun 16.4, 17.6).</li>
 *   <li>Elliptic rational function and the degree/nome relations from
 *       Orfanidis, "Lecture Notes on Elliptic Filter Design" (2006).</li>
 * </ul>
 */
@UtilityClass
public class FilterMath {

    /** Hard upper bound on the derived order - keeps the math well conditioned
     *  in double precision and bounds Bessel's numeric search. */
    public static final int MAX_ORDER = 20;
    /** Smallest order any design may resolve to. */
    public static final int MIN_ORDER = 1;

    /** Landen recursion depth for the Jacobi evaluation. */
    private static final int LANDEN_ITERS = 8;
    /** Iterations for the AGM and the bisection root finders. */
    private static final int AGM_ITERS = 60;
    private static final int BISECTION_ITERS = 60;
    /** Theta-null series term count (q < 1 => converges geometrically). */
    private static final int THETA_TERMS = 20;
    private static final double CONVERGENCE_EPS = 1e-15;

    // ---------------------------------------------------------------------
    //  Chebyshev polynomial Tn(x)
    // ---------------------------------------------------------------------

    /** Chebyshev polynomial of the first kind, valid for all real x. */
    public double chebyshevT(int n, double x) {
        if (x <= 1.0 && x >= -1.0) {
            return Math.cos(n * Math.acos(x));
        }
        double ax = Math.abs(x);
        double val = Math.cosh(n * acosh(ax));
        return (x < 0.0 && (n & 1) == 1) ? -val : val;
    }

    /** Inverse hyperbolic cosine (x ≥ 1). */
    public double acosh(double x) {
        return Math.log(x + Math.sqrt(x * x - 1.0));
    }

    // ---------------------------------------------------------------------
    //  Reverse Bessel polynomial θn(s)
    // ---------------------------------------------------------------------

    /**
     * Coefficients of the reverse Bessel polynomial θn(s), c[k] = coeff of s^k,
     * via θk(s) = (2k−1)·θ(k−1)(s) + s²·θ(k−2)(s), θ0 = 1, θ1 = s + 1.
     */
    public double[] besselCoeffs(int n) {
        double[] prev = { 1.0 };                 // θ0
        if (n == 0) return prev;
        double[] cur = { 1.0, 1.0 };             // θ1 = 1 + s
        for (int k = 2; k <= n; k++) {
            double[] next = new double[k + 1];
            double a = 2.0 * k - 1.0;
            for (int i = 0; i < cur.length; i++) next[i] += a * cur[i];        // (2k−1)·θ(k−1)
            for (int i = 0; i < prev.length; i++) next[i + 2] += prev[i];      // s²·θ(k−2)
            prev = cur;
            cur = next;
        }
        return cur;
    }

    /**
     * |θn(0)|²/|θn(jΩ)|² for the reverse Bessel polynomial with coefficients
     * {@code c} evaluated at s = jΩ.  (jΩ)^k cycles 1, j, −1, −j so the even
     * powers land in the real part and the odd powers in the imaginary part.
     */
    public double besselMagSqAt(double[] c, int n, double omega) {
        double re = 0.0;
        double im = 0.0;
        double omk = 1.0;   // Ω^k
        for (int k = 0; k <= n; k++) {
            switch (k & 3) {
                case 0: re += c[k] * omk; break;
                case 1: im += c[k] * omk; break;
                case 2: re -= c[k] * omk; break;
                default: im -= c[k] * omk; break;
            }
            omk *= omega;
        }
        double denom = re * re + im * im;
        double num = c[0] * c[0];   // θn(0)²
        return (denom > 0.0) ? num / denom : 0.0;
    }

    /**
     * Frequency scale s such that the order-n Bessel prototype hits −3.0103 dB
     * (|H|² = ½) at w = 1.  |H(jΩ)|² is monotone-decreasing in Ω, so bisection
     * on Ω converges.
     */
    public double besselHalfPowerScale(int n) {
        if (n <= 0) return 1.0;
        double[] c = besselCoeffs(n);
        double lo = 0.0;
        double hi = 1.0;
        while (besselMagSqAt(c, n, hi) > 0.5) hi *= 2.0;
        for (int it = 0; it < BISECTION_ITERS; it++) {
            double mid = 0.5 * (lo + hi);
            if (besselMagSqAt(c, n, mid) > 0.5) lo = mid; else hi = mid;
        }
        return 0.5 * (lo + hi);
    }

    // ---------------------------------------------------------------------
    //  Complete elliptic integral of the first kind K(k) - AGM
    // ---------------------------------------------------------------------

    /** K(k) via the arithmetic-geometric mean; k is the modulus (not m = k²). */
    public double ellipticK(double k) {
        double kk = Math.min(Math.abs(k), 1.0 - CONVERGENCE_EPS);
        double a = 1.0;
        double b = Math.sqrt(1.0 - kk * kk);
        for (int i = 0; i < AGM_ITERS; i++) {
            double an = 0.5 * (a + b);
            double bn = Math.sqrt(a * b);
            if (Math.abs(a - b) < CONVERGENCE_EPS) { a = an; break; }
            a = an; b = bn;
        }
        return Math.PI / (2.0 * a);
    }

    /** Complementary complete elliptic integral K'(k) = K(√(1−k²)). */
    public double ellipticKComplement(double k) {
        double kp = Math.sqrt(Math.max(0.0, 1.0 - k * k));
        return ellipticK(kp);
    }

    /** Elliptic nome q = exp(−π·K'(k)/K(k)). */
    public double nome(double k) {
        double kk = ellipticK(k);
        double kkp = ellipticKComplement(k);
        if (kk <= 0.0) return 0.0;
        return Math.exp(-Math.PI * kkp / kk);
    }

    /**
     * Modulus k from the nome q via the Jacobi theta-null series:
     *   θ2 = 2·Σ q^{(m+½)²},  θ3 = 1 + 2·Σ q^{m²},  k = (θ2/θ3)².
     */
    public double modulusFromNome(double q) {
        if (q <= 0.0) return 0.0;
        double theta2 = 0.0;   // 2·Σ q^{(m+1/2)²}, factor-of-2 applied at end
        double theta3 = 1.0;   // 1 + 2·Σ_{m≥1} q^{m²}
        for (int m = 0; m <= THETA_TERMS; m++) {
            theta2 += Math.pow(q, (m + 0.5) * (m + 0.5));
            if (m >= 1) theta3 += 2.0 * Math.pow(q, (double) m * m);
        }
        theta2 *= 2.0;
        double kv = (theta2 * theta2) / (theta3 * theta3);
        return Math.min(1.0, Math.max(0.0, kv));
    }

    // ---------------------------------------------------------------------
    //  Jacobi elliptic functions and the elliptic rational function
    // ---------------------------------------------------------------------

    /** Jacobi cd(u·K, k) with u in units of K; cd = cn/dn. */
    public double cd(double uInK, double k) {
        double[] scd = jacobiSnCnDn(uInK, k);
        double cn = scd[1];
        double dn = scd[2];
        return (dn != 0.0) ? cn / dn : 0.0;
    }

    /**
     * Jacobi elliptic functions sn, cn, dn at argument {@code uInK}·K(k),
     * returned as {sn, cn, dn}.  Descending Landen / AGM (Abramowitz &amp;
     * Stegun 16.4).  Accurate for k ∈ [0,1).
     */
    public double[] jacobiSnCnDn(double uInK, double k) {
        double u = uInK * ellipticK(k);
        double m = k * k;   // parameter
        int nItems = LANDEN_ITERS;
        double[] a = new double[nItems + 1];
        double[] cc = new double[nItems + 1];
        a[0] = 1.0;
        cc[0] = k;
        double bPrev = Math.sqrt(1.0 - m);
        int nn = 0;
        for (int i = 1; i <= nItems; i++) {
            a[i] = 0.5 * (a[i - 1] + bPrev);
            double bCur = Math.sqrt(a[i - 1] * bPrev);
            cc[i] = 0.5 * (a[i - 1] - bPrev);
            bPrev = bCur;
            nn = i;
            if (cc[i] < CONVERGENCE_EPS) break;
        }
        double phi = Math.pow(2.0, nn) * a[nn] * u;
        for (int i = nn; i >= 1; i--) {
            phi = 0.5 * (phi + Math.asin(cc[i] / a[i] * Math.sin(phi)));
        }
        double sn = Math.sin(phi);
        double cn = Math.cos(phi);
        double dn = Math.sqrt(1.0 - m * sn * sn);
        return new double[] { sn, cn, dn };
    }

    /**
     * Elliptic (Chebyshev) rational function R<sub>n</sub>(ξ, w) in product
     * form (Orfanidis 2006, "Lecture Notes on Elliptic Filter Design",
     * eq. 2.13-2.19).  With modulus k = 1/ξ the passband zeros are
     *   z<sub>r</sub> = cd((2r−1)·K/n, k),  r = 1..⌊n/2⌋,
     * and R<sub>n</sub> is the pole/zero product
     *   even n: R<sub>n</sub>(w) = f · Π (w²−z<sub>r</sub>²)/(1−k²z<sub>r</sub>²w²)
     *   odd  n: R<sub>n</sub>(w) = f · w · Π (w²−z<sub>r</sub>²)/(1−k²z<sub>r</sub>²w²)
     * with f chosen so R<sub>n</sub>(1) = 1.  This form has the correct
     * stopband poles at w = 1/(k·z<sub>r</sub>) &gt; ξ, giving the equiripple
     * stopband, and equiripples in ±1 across the passband.  Reduces to the
     * Chebyshev polynomial as ξ -> ∞ (k -> 0).
     */
    public double ellipticRational(int n, double xi, double w) {
        double aw = Math.abs(w);
        if (xi <= 1.0) {
            return chebyshevT(n, aw);   // degenerate selectivity -> Chebyshev-like
        }
        double k = 1.0 / xi;
        int half = n / 2;
        boolean odd = (n & 1) == 1;

        double num = odd ? aw : 1.0;
        double den = 1.0;
        double normNum = odd ? 1.0 : 1.0;   // R at w = 1
        double normDen = 1.0;
        for (int r = 1; r <= half; r++) {
            double zr = cd((2.0 * r - 1.0) / n, k);   // cd((2r−1)K/n, k)
            double zr2 = zr * zr;
            num *= (aw * aw - zr2);
            den *= (1.0 - k * k * zr2 * aw * aw);
            normNum *= (1.0 - zr2);                    // (1²−zr²)
            normDen *= (1.0 - k * k * zr2);            // (1−k²zr²·1²)
        }
        double rNum = num / den;                       // unnormalised Rn(w)
        double rAtOne = (normDen != 0.0) ? normNum / normDen : 1.0;   // unnormalised Rn(1)
        if (rAtOne == 0.0) return rNum;
        return rNum / rAtOne;                          // scale so Rn(1) = 1
    }

    // ---------------------------------------------------------------------
    //  Mode-1 order derivation + ripple/selectivity constants
    // ---------------------------------------------------------------------

    /**
     * Butterworth minimum order meeting {@code stopAttenDb} at stop ratio ws:
     * n ≥ log10(10^{A/10}−1) / (2·log10(ws)).
     */
    public int butterworthOrder(double stopAttenDb, double ws) {
        double a = Math.pow(10.0, stopAttenDb / 10.0) - 1.0;
        return (int) Math.ceil(Math.log10(a) / (2.0 * Math.log10(ws)));
    }

    /**
     * Chebyshev / Inverse-Chebyshev minimum order:
     * n ≥ acosh( √((10^{A/10}−1)/(10^{R/10}−1)) ) / acosh(ws).
     */
    public int chebyshevOrder(double rippleDb, double stopAttenDb, double ws) {
        double a = Math.pow(10.0, stopAttenDb / 10.0) - 1.0;
        double e2 = Math.pow(10.0, rippleDb / 10.0) - 1.0;
        return (int) Math.ceil(acosh(Math.sqrt(a / e2)) / acosh(ws));
    }

    /**
     * Elliptic minimum order via the complete-elliptic-integral ratio:
     * n ≥ [K(1/ws)·K'(k1)] / [K'(1/ws)·K(k1)] with k1 = √((10^{R/10}−1)/(10^{A/10}−1)).
     */
    public int ellipticOrder(double rippleDb, double stopAttenDb, double ws) {
        double a = Math.pow(10.0, stopAttenDb / 10.0) - 1.0;
        double e2 = Math.pow(10.0, rippleDb / 10.0) - 1.0;
        double k = 1.0 / ws;
        double k1 = Math.sqrt(e2 / a);
        double numer = ellipticK(k) * ellipticKComplement(k1);
        double denom = ellipticKComplement(k) * ellipticK(k1);
        return (int) Math.ceil(numer / denom);
    }

    /**
     * Elliptic selectivity ξ = ws/wp for an order-n filter given passband
     * ripple and a nominal stopband attenuation (Mode 2 helper): inverts the
     * degree equation for the w-plane modulus k (q = nome(k1)^{1/n}), ξ = 1/k.
     */
    public double ellipticSelectivityForOrder(int n, double rippleDb, double stopDb) {
        double e2 = Math.pow(10.0, rippleDb / 10.0) - 1.0;
        double a = Math.pow(10.0, stopDb / 10.0) - 1.0;
        double k1 = Math.sqrt(e2 / a);
        double q1 = nome(k1);
        double q = Math.pow(q1, 1.0 / n);
        double k = modulusFromNome(q);
        double xi = (k > 0.0) ? 1.0 / k : 2.0;
        return Math.max(1.0001, xi);
    }

    /** ε² for the family: Cheb I / Elliptic use passband ripple R; Inverse
     *  Chebyshev uses the stopband attenuation A (its "ripple" is the floor);
     *  Butterworth / Bessel are ripple-free. */
    public double rippleEpsilonSq(FilterResponse response, double rippleDb, double stopAttenDb) {
        switch (response) {
            case CHEBYSHEV:
            case ELLIPTIC:
                return Math.pow(10.0, rippleDb / 10.0) - 1.0;      // 10^{R/10} − 1
            case INV_CHEBYSHEV:
                return 1.0 / (Math.pow(10.0, stopAttenDb / 10.0) - 1.0);   // so floor = −A dB
            default:
                return 0.0;
        }
    }

    /**
     * Minimum order meeting {@code stopAttenDb} at the stop edge (ratio ws).
     * Closed-form per family; Bessel is searched numerically and may fall
     * short -> {@link #MAX_ORDER} (it has no ripple/closed-form order).
     */
    public int deriveOrder(FilterResponse response, double rippleDb, double stopAttenDb, double ws) {
        switch (response) {
            case BUTTERWORTH:
                return clampOrder(butterworthOrder(stopAttenDb, ws));
            case CHEBYSHEV:
            case INV_CHEBYSHEV:
                return clampOrder(chebyshevOrder(rippleDb, stopAttenDb, ws));
            case ELLIPTIC:
                return clampOrder(ellipticOrder(rippleDb, stopAttenDb, ws));
            case BESSEL:
            default:
                return deriveBesselOrder(stopAttenDb, ws);
        }
    }

    /** Lowest Bessel order reaching {@code stopAttenDb} at the stop edge, else
     *  {@link #MAX_ORDER} (Bessel's soft roll-off often needs the cap). */
    public int deriveBesselOrder(double stopAttenDb, double ws) {
        for (int n = MIN_ORDER; n <= MAX_ORDER; n++) {
            double scale = besselHalfPowerScale(n);
            double[] c = besselCoeffs(n);
            double magSq = besselMagSqAt(c, n, scale * ws);
            double attenDb = -10.0 * Math.log10(magSq);
            if (attenDb >= stopAttenDb) return n;
        }
        return MAX_ORDER;
    }

    /** Clamps an order into [{@link #MIN_ORDER}, {@link #MAX_ORDER}]. */
    public int clampOrder(int n) {
        if (n < MIN_ORDER) return MIN_ORDER;
        return Math.min(n, MAX_ORDER);
    }
}
