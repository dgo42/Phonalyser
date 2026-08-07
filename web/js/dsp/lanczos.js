/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.common.Lanczos.
//
// Lanczos-windowed sinc reconstruction kernel. Reconstructs a band-limited
// trace at any fractional sample-domain position t, with the kernel widened by
// `scale` so it acts as a low-pass filter at the output rate (kills energy
// between the output Nyquist and the input Nyquist that would otherwise fold
// into beat envelopes). Per-scale phase tables are cached so the inner loop is
// a plain table lookup with zero Math.sin calls.

/** Kernel half-width in samples (windowed-sinc lobes each side -> ≥ 80 dB stop-band). */
export const LANCZOS_A = 16;

/** Largest downsample factor for which the scaled kernel is cheap enough per pixel. */
export const MAX_LANCZOS_DOWNSAMPLE = 5;

/** Buffer padding (each side) so the widest kernel still has real context. */
export const LANCZOS_PADDING = LANCZOS_A * MAX_LANCZOS_DOWNSAMPLE;

/** Phase resolution for the cached kernel tables (≈ 0.001 sample precision). */
const LANCZOS_PHASES = 1024;

// Two-slot table cache: one slot is permanently kept for scale == 1 (used by
// trigger refinement and every fast-time/div render), the other holds whatever
// non-unit scale was last requested. Two slots avoid thrashing because each
// frame issues at most those two scales.
let cachedKernelScale1 = null;     // {Float64Array[]} | null
let cachedKernelOther = null;      // {Float64Array[]} | null
let cachedKernelOtherScale = NaN;

/**
 * sinc(x) = sin(πx) / (πx) with full double precision near 0. For |x| < 0.1 an
 * 8th-order Taylor series in u = (πx)² avoids the cancellation loss that the
 * sin(πx)/(πx) form suffers near zero.
 * @param {number} x
 * @returns {number}
 */
export function sinc(x) {
  if (x === 0.0) return 1.0;
  const pix = Math.PI * x;
  if (Math.abs(x) < 0.1) {
    const u = pix * pix;
    return 1.0 + u * (-1.0 / 6.0
               + u * ( 1.0 / 120.0
               + u * (-1.0 / 5040.0
               + u * ( 1.0 / 362880.0
               + u * (-1.0 / 39916800.0)))));
  }
  return Math.sin(pix) / pix;
}

/**
 * Pre-bakes the kernel for `scale` into a phase table. Each row is normalised to
 * unit DC gain (Σw = 1) regardless of the ULP-level drift the analytic form
 * leaves behind.
 * @param {number} scale
 * @returns {Float64Array[]}
 */
function buildKernelTable(scale) {
  const halfWidth = Math.ceil(LANCZOS_A * scale);
  const taps = 2 * halfWidth;
  const invScale = 1.0 / scale;
  const table = new Array(LANCZOS_PHASES);
  for (let p = 0; p < LANCZOS_PHASES; p++) {
    const frac = p / LANCZOS_PHASES;
    const row = new Float64Array(taps);
    for (let j = 0; j < taps; j++) {
      const x = (frac + (halfWidth - 1) - j) * invScale;
      if (Math.abs(x) >= LANCZOS_A) {
        row[j] = 0.0;
      } else {
        row[j] = sinc(x) * sinc(x / LANCZOS_A) * invScale;
      }
    }
    let s = 0.0;
    for (let j = 0; j < taps; j++) s += row[j];
    if (s !== 0.0) {
      const k = 1.0 / s;
      for (let j = 0; j < taps; j++) row[j] *= k;
    }
    table[p] = row;
  }
  return table;
}

/**
 * Kernel-table lookup with the two-slot cache. Builds on a miss.
 * @param {number} scale
 * @returns {Float64Array[]}
 */
function getKernelTable(scale) {
  if (scale === 1.0) {
    if (cachedKernelScale1 === null) cachedKernelScale1 = buildKernelTable(1.0);
    return cachedKernelScale1;
  }
  if (cachedKernelOther === null || cachedKernelOtherScale !== scale) {
    cachedKernelOther = buildKernelTable(scale);
    cachedKernelOtherScale = scale;
  }
  return cachedKernelOther;
}

/**
 * Lanczos sinc reconstruction of a `Float32Array`/`Float64Array` scope buffer at
 * fractional position `t`, using a precomputed phase table for `scale`. Scope
 * buffers cannot contain NaN by construction, so this branch-free overload sums
 * baseline·Σw + Σdeltas. Mirrors the Java float[] overload.
 *
 * @param {Float32Array|Float64Array|number[]} data   sample buffer
 * @param {number} n      valid length of `data`
 * @param {number} t      sample-domain position (may be fractional)
 * @param {number} scale  downsample factor; 1 = classic Whittaker-Shannon, >1
 *                        widens the kernel to act as an anti-aliasing low-pass
 * @returns {number}
 */
export function lanczos(data, n, t, scale) {
  const table = getKernelTable(scale);
  const halfWidth = Math.ceil(LANCZOS_A * scale);
  const center = Math.floor(t);
  const frac = t - center;
  let phase = Math.trunc(frac * LANCZOS_PHASES);
  if (phase < 0) phase = 0;
  if (phase >= LANCZOS_PHASES) phase = LANCZOS_PHASES - 1;
  const w = table[phase];
  const iLo = Math.max(0, center - halfWidth + 1);
  const iHi = Math.min(n - 1, center + halfWidth);
  const centerClamped = (center < 0) ? 0 : (center >= n ? n - 1 : center);
  const baseline = data[centerClamped];
  let sumWeights = 0.0;
  let sumDeltas = 0.0;
  for (let i = iLo; i <= iHi; i++) {
    const j = i - center + halfWidth - 1;
    const wj = w[j];
    sumWeights += wj;
    sumDeltas += (data[i] - baseline) * wj;
  }
  return baseline * sumWeights + sumDeltas;
}

/**
 * NaN-aware `Float64Array` overload - used by frequency-domain views whose
 * magnitude/phase arrays carry NaN for invalid points (unswept regions,
 * non-positive magnitudes). NaN taps are treated as MISSING and the remaining
 * taps renormalized; a NaN center sample still returns NaN so genuine gaps
 * render as gaps. The renormalization also keeps full gain where the kernel
 * truncates at the array ends. Mirrors the Java double[] overload.
 *
 * @param {Float64Array|number[]} data   sample buffer (may contain NaN)
 * @param {number} n      valid length of `data`
 * @param {number} t      sample-domain position (may be fractional)
 * @param {number} scale  downsample factor
 * @returns {number}
 */
export function lanczosNaN(data, n, t, scale) {
  const table = getKernelTable(scale);
  const halfWidth = Math.ceil(LANCZOS_A * scale);
  const center = Math.floor(t);
  const frac = t - center;
  let phase = Math.trunc(frac * LANCZOS_PHASES);
  if (phase < 0) phase = 0;
  if (phase >= LANCZOS_PHASES) phase = LANCZOS_PHASES - 1;
  const w = table[phase];
  const iLo = Math.max(0, center - halfWidth + 1);
  const iHi = Math.min(n - 1, center + halfWidth);
  const centerClamped = (center < 0) ? 0 : (center >= n ? n - 1 : center);
  const baseline = data[centerClamped];
  if (Number.isNaN(baseline)) return NaN;   // genuine gap stays a gap
  let sumWeights = 0.0;
  let sumDeltas = 0.0;
  for (let i = iLo; i <= iHi; i++) {
    const di = data[i];
    if (Number.isNaN(di)) continue;          // missing sample - renormalized out
    const wj = w[i - center + halfWidth - 1];
    sumWeights += wj;
    sumDeltas += (di - baseline) * wj;
  }
  return (sumWeights > 0.0) ? baseline + sumDeltas / sumWeights : NaN;
}
