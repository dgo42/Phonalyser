/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Iterative in-place radix-2 Cooley–Tukey FFT on parallel real/imag Float64Array.
// JavaScript `number` is IEEE-754 binary64 — the same type as the desktop's Java
// `double` — so this produces bit-identical results to the Java FFT engine.

/** @returns {boolean} true when n is a power of two (and > 0). */
export function isPow2(n) { return n > 0 && (n & (n - 1)) === 0; }

/**
 * Forward FFT, in place. `re`/`im` are Float64Array of equal length N (a power of two).
 * On return they hold the complex spectrum X[k], k = 0..N-1.
 */
export function fft(re, im) {
  const n = re.length;
  // Gold–Rader bit reversal.
  for (let i = 1, j = 0; i < n; i++) {
    let bit = n >> 1;
    for (; j & bit; bit >>= 1) j ^= bit;
    j ^= bit;
    if (i < j) { let t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
  }
  // Butterflies; twiddle by incremental rotation (recurrence error ~1e-13, far below any audio floor).
  for (let len = 2; len <= n; len <<= 1) {
    const ang = -2 * Math.PI / len, wr = Math.cos(ang), wi = Math.sin(ang), half = len >> 1;
    for (let i = 0; i < n; i += len) {
      let cr = 1, ci = 0;
      for (let k = 0; k < half; k++) {
        const ar = re[i + k], ai = im[i + k];
        const br = re[i + k + half], bi = im[i + k + half];
        const vr = br * cr - bi * ci, vi = br * ci + bi * cr;
        re[i + k] = ar + vr; im[i + k] = ai + vi;
        re[i + k + half] = ar - vr; im[i + k + half] = ai - vi;
        const ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr;
      }
    }
  }
}

/**
 * Inverse FFT, in place, via conjugation: ifft(x) = conj(fft(conj(x))) / N.
 * Used by frequency-response deconvolution. Leaves re/im as the time-domain signal.
 */
export function ifft(re, im) {
  const n = re.length;
  for (let i = 0; i < n; i++) im[i] = -im[i];
  fft(re, im);
  const inv = 1 / n;
  for (let i = 0; i < n; i++) { re[i] = re[i] * inv; im[i] = -im[i] * inv; }
}
