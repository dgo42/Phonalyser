/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.dsp.FilterMath.

import { FilterResponse } from './filter-types.js';

/** Hard upper bound on the derived order - keeps the math well conditioned
 *  in double precision and bounds Bessel's numeric search. */
export const MAX_ORDER = 20;
/** Smallest order any design may resolve to. */
export const MIN_ORDER = 1;

/** Landen recursion depth for the Jacobi evaluation. */
const LANDEN_ITERS = 8;
/** Iterations for the AGM and the bisection root finders. */
const AGM_ITERS = 60;
const BISECTION_ITERS = 60;
/** Theta-null series term count (q < 1 => converges geometrically). */
const THETA_TERMS = 20;
const CONVERGENCE_EPS = 1e-15;

// ---------------------------------------------------------------------
//  Chebyshev polynomial Tn(x)
// ---------------------------------------------------------------------

/** Chebyshev polynomial of the first kind, valid for all real x. */
export function chebyshevT(n, x) {
  if (x <= 1.0 && x >= -1.0) {
    return Math.cos(n * Math.acos(x));
  }
  const ax = Math.abs(x);
  const val = Math.cosh(n * acosh(ax));
  return (x < 0.0 && (n & 1) === 1) ? -val : val;
}

/** Inverse hyperbolic cosine (x ≥ 1). */
export function acosh(x) {
  return Math.log(x + Math.sqrt(x * x - 1.0));
}

// ---------------------------------------------------------------------
//  Reverse Bessel polynomial θn(s)
// ---------------------------------------------------------------------

/**
 * Coefficients of the reverse Bessel polynomial θn(s), c[k] = coeff of s^k,
 * via θk(s) = (2k−1)·θ(k−1)(s) + s²·θ(k−2)(s), θ0 = 1, θ1 = s + 1.
 */
export function besselCoeffs(n) {
  let prev = [1.0];                        // θ0
  if (n === 0) return prev;
  let cur = [1.0, 1.0];                    // θ1 = 1 + s
  for (let k = 2; k <= n; k++) {
    const next = new Float64Array(k + 1);
    const a = 2.0 * k - 1.0;
    for (let i = 0; i < cur.length; i++) next[i] += a * cur[i];        // (2k−1)·θ(k−1)
    for (let i = 0; i < prev.length; i++) next[i + 2] += prev[i];      // s²·θ(k−2)
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
export function besselMagSqAt(c, n, omega) {
  let re = 0.0;
  let im = 0.0;
  let omk = 1.0;   // Ω^k
  for (let k = 0; k <= n; k++) {
    switch (k & 3) {
      case 0: re += c[k] * omk; break;
      case 1: im += c[k] * omk; break;
      case 2: re -= c[k] * omk; break;
      default: im -= c[k] * omk; break;
    }
    omk *= omega;
  }
  const denom = re * re + im * im;
  const num = c[0] * c[0];   // θn(0)²
  return (denom > 0.0) ? num / denom : 0.0;
}

/**
 * Frequency scale s such that the order-n Bessel prototype hits −3.0103 dB
 * (|H|² = ½) at w = 1.  |H(jΩ)|² is monotone-decreasing in Ω, so bisection
 * on Ω converges.
 */
export function besselHalfPowerScale(n) {
  if (n <= 0) return 1.0;
  const c = besselCoeffs(n);
  let lo = 0.0;
  let hi = 1.0;
  while (besselMagSqAt(c, n, hi) > 0.5) hi *= 2.0;
  for (let it = 0; it < BISECTION_ITERS; it++) {
    const mid = 0.5 * (lo + hi);
    if (besselMagSqAt(c, n, mid) > 0.5) lo = mid; else hi = mid;
  }
  return 0.5 * (lo + hi);
}

// ---------------------------------------------------------------------
//  Complete elliptic integral of the first kind K(k) - AGM
// ---------------------------------------------------------------------

/** K(k) via the arithmetic-geometric mean; k is the modulus (not m = k²). */
export function ellipticK(k) {
  const kk = Math.min(Math.abs(k), 1.0 - CONVERGENCE_EPS);
  let a = 1.0;
  let b = Math.sqrt(1.0 - kk * kk);
  for (let i = 0; i < AGM_ITERS; i++) {
    const an = 0.5 * (a + b);
    const bn = Math.sqrt(a * b);
    if (Math.abs(a - b) < CONVERGENCE_EPS) { a = an; break; }
    a = an; b = bn;
  }
  return Math.PI / (2.0 * a);
}

/** Complementary complete elliptic integral K'(k) = K(√(1−k²)). */
export function ellipticKComplement(k) {
  const kp = Math.sqrt(Math.max(0.0, 1.0 - k * k));
  return ellipticK(kp);
}

/** Elliptic nome q = exp(−π·K'(k)/K(k)). */
export function nome(k) {
  const kk = ellipticK(k);
  const kkp = ellipticKComplement(k);
  if (kk <= 0.0) return 0.0;
  return Math.exp(-Math.PI * kkp / kk);
}

/**
 * Modulus k from the nome q via the Jacobi theta-null series:
 *   θ2 = 2·Σ q^{(m+½)²},  θ3 = 1 + 2·Σ q^{m²},  k = (θ2/θ3)².
 */
export function modulusFromNome(q) {
  if (q <= 0.0) return 0.0;
  let theta2 = 0.0;   // 2·Σ q^{(m+1/2)²}, factor-of-2 applied at end
  let theta3 = 1.0;   // 1 + 2·Σ_{m≥1} q^{m²}
  for (let m = 0; m <= THETA_TERMS; m++) {
    theta2 += Math.pow(q, (m + 0.5) * (m + 0.5));
    if (m >= 1) theta3 += 2.0 * Math.pow(q, m * m);
  }
  theta2 *= 2.0;
  const kv = (theta2 * theta2) / (theta3 * theta3);
  return Math.min(1.0, Math.max(0.0, kv));
}

// ---------------------------------------------------------------------
//  Jacobi elliptic functions and the elliptic rational function
// ---------------------------------------------------------------------

/** Jacobi cd(u·K, k) with u in units of K; cd = cn/dn. */
export function cd(uInK, k) {
  const scd = jacobiSnCnDn(uInK, k);
  const cn = scd[1];
  const dn = scd[2];
  return (dn !== 0.0) ? cn / dn : 0.0;
}

/**
 * Jacobi elliptic functions sn, cn, dn at argument {@code uInK}·K(k),
 * returned as {sn, cn, dn}.  Descending Landen / AGM (Abramowitz &amp;
 * Stegun 16.4).  Accurate for k ∈ [0,1).
 */
export function jacobiSnCnDn(uInK, k) {
  const u = uInK * ellipticK(k);
  const m = k * k;   // parameter
  const nItems = LANDEN_ITERS;
  const a = new Float64Array(nItems + 1);
  const cc = new Float64Array(nItems + 1);
  a[0] = 1.0;
  cc[0] = k;
  let bPrev = Math.sqrt(1.0 - m);
  let nn = 0;
  for (let i = 1; i <= nItems; i++) {
    a[i] = 0.5 * (a[i - 1] + bPrev);
    const bCur = Math.sqrt(a[i - 1] * bPrev);
    cc[i] = 0.5 * (a[i - 1] - bPrev);
    bPrev = bCur;
    nn = i;
    if (cc[i] < CONVERGENCE_EPS) break;
  }
  let phi = Math.pow(2.0, nn) * a[nn] * u;
  for (let i = nn; i >= 1; i--) {
    phi = 0.5 * (phi + Math.asin(cc[i] / a[i] * Math.sin(phi)));
  }
  const sn = Math.sin(phi);
  const cn = Math.cos(phi);
  const dn = Math.sqrt(1.0 - m * sn * sn);
  return [sn, cn, dn];
}

/**
 * Elliptic (Chebyshev) rational function R_n(ξ, w) in product
 * form (Orfanidis 2006, "Lecture Notes on Elliptic Filter Design",
 * eq. 2.13-2.19).  With modulus k = 1/ξ the passband zeros are
 *   z_r = cd((2r−1)·K/n, k),  r = 1..⌊n/2⌋,
 * and R_n is the pole/zero product
 *   even n: R_n(w) = f · Π (w²−z_r²)/(1−k²z_r²w²)
 *   odd  n: R_n(w) = f · w · Π (w²−z_r²)/(1−k²z_r²w²)
 * with f chosen so R_n(1) = 1.  This form has the correct
 * stopband poles at w = 1/(k·z_r) > ξ, giving the equiripple
 * stopband, and equiripples in ±1 across the passband.  Reduces to the
 * Chebyshev polynomial as ξ -> ∞ (k -> 0).
 */
export function ellipticRational(n, xi, w) {
  const aw = Math.abs(w);
  if (xi <= 1.0) {
    return chebyshevT(n, aw);   // degenerate selectivity -> Chebyshev-like
  }
  const k = 1.0 / xi;
  const half = Math.trunc(n / 2);
  const odd = (n & 1) === 1;

  let num = odd ? aw : 1.0;
  let den = 1.0;
  let normNum = odd ? 1.0 : 1.0;   // R at w = 1
  let normDen = 1.0;
  for (let r = 1; r <= half; r++) {
    const zr = cd((2.0 * r - 1.0) / n, k);   // cd((2r−1)K/n, k)
    const zr2 = zr * zr;
    num *= (aw * aw - zr2);
    den *= (1.0 - k * k * zr2 * aw * aw);
    normNum *= (1.0 - zr2);                    // (1²−zr²)
    normDen *= (1.0 - k * k * zr2);            // (1−k²zr²·1²)
  }
  const rNum = num / den;                       // unnormalised Rn(w)
  const rAtOne = (normDen !== 0.0) ? normNum / normDen : 1.0;   // unnormalised Rn(1)
  if (rAtOne === 0.0) return rNum;
  return rNum / rAtOne;                          // scale so Rn(1) = 1
}

// ---------------------------------------------------------------------
//  Mode-1 order derivation + ripple/selectivity constants
// ---------------------------------------------------------------------

/**
 * Butterworth minimum order meeting {@code stopAttenDb} at stop ratio ws:
 * n ≥ log10(10^{A/10}−1) / (2·log10(ws)).
 */
export function butterworthOrder(stopAttenDb, ws) {
  const a = Math.pow(10.0, stopAttenDb / 10.0) - 1.0;
  return Math.ceil(Math.log10(a) / (2.0 * Math.log10(ws)));
}

/**
 * Chebyshev / Inverse-Chebyshev minimum order:
 * n ≥ acosh( √((10^{A/10}−1)/(10^{R/10}−1)) ) / acosh(ws).
 */
export function chebyshevOrder(rippleDb, stopAttenDb, ws) {
  const a = Math.pow(10.0, stopAttenDb / 10.0) - 1.0;
  const e2 = Math.pow(10.0, rippleDb / 10.0) - 1.0;
  return Math.ceil(acosh(Math.sqrt(a / e2)) / acosh(ws));
}

/**
 * Elliptic minimum order via the complete-elliptic-integral ratio:
 * n ≥ [K(1/ws)·K'(k1)] / [K'(1/ws)·K(k1)] with k1 = √((10^{R/10}−1)/(10^{A/10}−1)).
 */
export function ellipticOrder(rippleDb, stopAttenDb, ws) {
  const a = Math.pow(10.0, stopAttenDb / 10.0) - 1.0;
  const e2 = Math.pow(10.0, rippleDb / 10.0) - 1.0;
  const k = 1.0 / ws;
  const k1 = Math.sqrt(e2 / a);
  const numer = ellipticK(k) * ellipticKComplement(k1);
  const denom = ellipticKComplement(k) * ellipticK(k1);
  return Math.ceil(numer / denom);
}

/**
 * Elliptic selectivity ξ = ws/wp for an order-n filter given passband
 * ripple and a nominal stopband attenuation (Mode 2 helper): inverts the
 * degree equation for the w-plane modulus k (q = nome(k1)^{1/n}), ξ = 1/k.
 */
export function ellipticSelectivityForOrder(n, rippleDb, stopDb) {
  const e2 = Math.pow(10.0, rippleDb / 10.0) - 1.0;
  const a = Math.pow(10.0, stopDb / 10.0) - 1.0;
  const k1 = Math.sqrt(e2 / a);
  const q1 = nome(k1);
  const q = Math.pow(q1, 1.0 / n);
  const k = modulusFromNome(q);
  const xi = (k > 0.0) ? 1.0 / k : 2.0;
  return Math.max(1.0001, xi);
}

/** ε² for the family: Cheb I / Elliptic use passband ripple R; Inverse
 *  Chebyshev uses the stopband attenuation A (its "ripple" is the floor);
 *  Butterworth / Bessel are ripple-free. */
export function rippleEpsilonSq(response, rippleDb, stopAttenDb) {
  switch (response) {
    case FilterResponse.CHEBYSHEV:
    case FilterResponse.ELLIPTIC:
      return Math.pow(10.0, rippleDb / 10.0) - 1.0;      // 10^{R/10} − 1
    case FilterResponse.INV_CHEBYSHEV:
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
export function deriveOrder(response, rippleDb, stopAttenDb, ws) {
  switch (response) {
    case FilterResponse.BUTTERWORTH:
      return clampOrder(butterworthOrder(stopAttenDb, ws));
    case FilterResponse.CHEBYSHEV:
    case FilterResponse.INV_CHEBYSHEV:
      return clampOrder(chebyshevOrder(rippleDb, stopAttenDb, ws));
    case FilterResponse.ELLIPTIC:
      return clampOrder(ellipticOrder(rippleDb, stopAttenDb, ws));
    case FilterResponse.BESSEL:
    default:
      return deriveBesselOrder(stopAttenDb, ws);
  }
}

/** Lowest Bessel order reaching {@code stopAttenDb} at the stop edge, else
 *  {@link #MAX_ORDER} (Bessel's soft roll-off often needs the cap). */
export function deriveBesselOrder(stopAttenDb, ws) {
  for (let n = MIN_ORDER; n <= MAX_ORDER; n++) {
    const scale = besselHalfPowerScale(n);
    const c = besselCoeffs(n);
    const magSq = besselMagSqAt(c, n, scale * ws);
    const attenDb = -10.0 * Math.log10(magSq);
    if (attenDb >= stopAttenDb) return n;
  }
  return MAX_ORDER;
}

/** Clamps an order into [{@link #MIN_ORDER}, {@link #MAX_ORDER}]. */
export function clampOrder(n) {
  if (n < MIN_ORDER) return MIN_ORDER;
  return Math.min(n, MAX_ORDER);
}
