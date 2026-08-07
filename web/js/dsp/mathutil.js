/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.fft.MathUtil (chebyshevT, acosh,
// parabolicBinInterp, nextPow2) plus the modified Bessel I0 power series from
// org.edgo.audio.measure.fft.FftAnalyzer#besselI0. Side-effect-free pure math.

/**
 * Chebyshev polynomial of the first kind T_n(x). Uses the trigonometric
 * identity inside |x| ≤ 1 and the hyperbolic identity outside it, so the
 * result is finite for any real x.
 * @param {number} n  order
 * @param {number} x  argument
 * @returns {number}  T_n(x)
 */
export function chebyshevT(n, x) {
  if (x > 1.0) {
    return Math.cosh(n * acosh(x));
  } else if (x < -1.0) {
    return (n % 2 === 0 ? 1.0 : -1.0) * Math.cosh(n * acosh(-x));
  } else {
    return Math.cos(n * Math.acos(x));
  }
}

/**
 * Inverse hyperbolic cosine: acosh(x) = ln(x + √(x²−1)), x ≥ 1.
 * @param {number} x
 * @returns {number}
 */
export function acosh(x) {
  return Math.log(x + Math.sqrt(x * x - 1.0));
}

/**
 * Parabolic interpolation on the log-power spectrum to refine a peak bin to a
 * fractional bin index. Uses the three-point formula
 * δ = 0.5·(α − γ) / (α − 2β + γ) where α, β, γ are log-power at bins
 * peakBin−1, peakBin, peakBin+1.
 * @param {Float64Array|number[]} re  real parts
 * @param {Float64Array|number[]} im  imaginary parts
 * @param {number} peakBin            integer peak bin
 * @param {number} fftSize            FFT length N
 * @returns {number}  fractional bin index (peakBin + δ)
 */
export function parabolicBinInterp(re, im, peakBin, fftSize) {
  const halfSize = (fftSize / 2) | 0;
  const lo = Math.max(1, peakBin - 1);
  const hi = Math.min(halfSize, peakBin + 1);
  const pLo  = Math.log(re[lo] * re[lo] + im[lo] * im[lo] + 1e-30);
  const pMid = Math.log(re[peakBin] * re[peakBin] + im[peakBin] * im[peakBin] + 1e-30);
  const pHi  = Math.log(re[hi] * re[hi] + im[hi] * im[hi] + 1e-30);
  const denom = pLo - 2.0 * pMid + pHi;
  if (Math.abs(denom) < 1e-15) {
    return peakBin;
  }
  const delta = 0.5 * (pLo - pHi) / denom;
  return peakBin + delta;
}

/**
 * Smallest power of 2 ≥ x. Returns 1 for x ≤ 1.
 * @param {number} x
 * @returns {number}
 */
export function nextPow2(x) {
  if (x <= 1) return 1;
  // Integer.highestOneBit(x): the highest set bit of (x|0).
  const v = x | 0;
  let hi = 1;
  while (hi << 1 > 0 && (hi << 1) <= v) hi <<= 1;
  return hi === v ? v : hi << 1;
}

/**
 * Modified Bessel function of the first kind, order 0 - power series
 * Σ ((x/2)ᵏ/k!)²; converges to double precision well within the iteration cap.
 * @param {number} x
 * @returns {number}  I₀(x)
 */
export function besselI0(x) {
  let sum = 1.0;
  let term = 1.0;
  const half = x / 2.0;
  for (let k = 1; k < 200; k++) {
    const f = half / k;
    term *= f * f;
    sum += term;
    if (term < sum * 1e-17) break;
  }
  return sum;
}
