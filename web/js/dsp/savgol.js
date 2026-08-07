/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of the Savitzky-Golay smoother in
// org.edgo.audio.measure.dsp.FreqRespCalHelper (savGolCoefficients,
// applySavGol, invertSquareMatrix).
//
// Central-tap SG smoothing: the smoothed value at each index is the dot product
// of the central row of (VᵀV)⁻¹ Vᵀ with the windowed samples, where
// V[i][j] = (i − centre)^j. Matrix inversion is plain Gauss-Jordan with partial
// pivoting on the tiny (order+1)² Vandermonde-normal matrix. Out-of-bounds
// indices are reflected (mirror at the boundary) so edge samples are smoothed.

/**
 * Computes the central-tap Savitzky-Golay smoothing coefficients for an odd
 * window length and polynomial order. Equivalent to the first row of
 * (VᵀV)⁻¹ Vᵀ with V[i][j] = (i − window/2)^j.
 *
 * @param {number} window odd window length (must be > order)
 * @param {number} order  polynomial order
 * @returns {Float64Array} window coefficients (summing to 1)
 */
export function savGolCoefficients(window, order) {
  const m = Math.trunc(window / 2);
  const n = order + 1;
  const v = [];
  for (let i = 0; i < window; i++) {
    const x = i - m;
    const row = new Float64Array(n);
    row[0] = 1.0;
    for (let j = 1; j < n; j++) row[j] = row[j - 1] * x;
    v.push(row);
  }
  const vtv = [];
  for (let a = 0; a < n; a++) {
    const row = new Float64Array(n);
    for (let b = 0; b < n; b++) {
      let s = 0.0;
      for (let i = 0; i < window; i++) s += v[i][a] * v[i][b];
      row[b] = s;
    }
    vtv.push(row);
  }
  const inv = invertSquareMatrix(vtv);
  const c = new Float64Array(window);
  for (let i = 0; i < window; i++) {
    let s = 0.0;
    for (let j = 0; j < n; j++) s += inv[0][j] * v[i][j];
    c[i] = s;
  }
  return c;
}

/**
 * Convolves {@code arr} with {@code coeffs} at index {@code i}. Out-of-bounds
 * indices are reflected (mirror at the boundary).
 *
 * @param {ArrayLike<number>} arr    source samples
 * @param {number} i                 centre index
 * @param {Float64Array} coeffs      SG window coefficients (odd length)
 * @returns {number} smoothed value at index i
 */
export function applySavGol(arr, i, coeffs) {
  const half = Math.trunc(coeffs.length / 2);
  let sum = 0.0;
  for (let j = -half; j <= half; j++) {
    let idx = i + j;
    if (idx < 0) idx = -idx;
    if (idx >= arr.length) idx = 2 * (arr.length - 1) - idx;
    if (idx < 0) idx = 0;
    if (idx >= arr.length) idx = arr.length - 1;
    sum += coeffs[j + half] * arr[idx];
  }
  return sum;
}

/**
 * Gauss-Jordan inverse of a small square matrix with partial pivoting. Returns
 * a fresh inverse; does not mutate the input.
 *
 * @param {Array<Float64Array>} a square matrix (n rows of length n)
 * @returns {Array<Float64Array>} the inverse, n rows of length n
 * @throws {Error} if the matrix is singular
 */
export function invertSquareMatrix(a) {
  const n = a.length;
  const m = [];
  for (let i = 0; i < n; i++) {
    const row = new Float64Array(2 * n);
    for (let j = 0; j < n; j++) row[j] = a[i][j];
    row[n + i] = 1.0;
    m.push(row);
  }
  for (let col = 0; col < n; col++) {
    let pivot = col;
    let pivotMag = Math.abs(m[col][col]);
    for (let row = col + 1; row < n; row++) {
      const mag = Math.abs(m[row][col]);
      if (mag > pivotMag) { pivot = row; pivotMag = mag; }
    }
    if (pivot !== col) { const tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp; }
    const diag = m[col][col];
    if (diag === 0.0) throw new Error('Singular matrix in SG coefficient solve');
    const inv = 1.0 / diag;
    for (let j = 0; j < 2 * n; j++) m[col][j] *= inv;
    for (let row = 0; row < n; row++) {
      if (row === col) continue;
      const factor = m[row][col];
      if (factor === 0.0) continue;
      for (let j = 0; j < 2 * n; j++) m[row][j] -= factor * m[col][j];
    }
  }
  const out = [];
  for (let i = 0; i < n; i++) {
    const row = new Float64Array(n);
    for (let j = 0; j < n; j++) row[j] = m[i][n + j];
    out.push(row);
  }
  return out;
}
