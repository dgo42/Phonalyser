/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// FFT analysis windows. Each builder returns { win: Float64Array, cg, enbw }:
//   cg   = coherent gain  = (Σ w) / N         - amplitude calibration of an on-bin tone
//   enbw = equivalent noise bandwidth (bins)  = N·Σ(w²) / (Σ w)²
// Window choice trades main-lobe width (frequency resolution) against side-lobe
// suppression (dynamic range). Blackman-Harris is the default for distortion work.

function finish(win) {
  const n = win.length;
  let s = 0, s2 = 0;
  for (let i = 0; i < n; i++) { s += win[i]; s2 += win[i] * win[i]; }
  return { win, cg: s / n, enbw: (n * s2) / (s * s) };
}

/** 4-term Blackman-Harris (~ −92 dB side lobes). Default for THD/IMD/null work. */
export function blackmanHarris(n) {
  const a0 = 0.35875, a1 = 0.48829, a2 = 0.14128, a3 = 0.01168, w = new Float64Array(n);
  for (let i = 0; i < n; i++) {
    const x = 2 * Math.PI * i / (n - 1);
    w[i] = a0 - a1 * Math.cos(x) + a2 * Math.cos(2 * x) - a3 * Math.cos(3 * x);
  }
  return finish(w);
}

/** Hann - narrow main lobe, −31 dB side lobes. */
export function hann(n) {
  const w = new Float64Array(n);
  for (let i = 0; i < n; i++) w[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1));
  return finish(w);
}

/** Rectangular (no window) - best frequency resolution, worst leakage. */
export function rectangular(n) {
  const w = new Float64Array(n).fill(1);
  return finish(w);
}

export const WINDOWS = { blackmanHarris, hann, rectangular };
