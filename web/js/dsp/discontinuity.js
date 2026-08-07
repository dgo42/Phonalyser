/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Frequency-domain glitch / discontinuity rejector - a port of gates 2 & 3 of the
// desktop SpectralDiscontinuityDetector. Each block is reduced to log-spaced band
// levels (dB) and compared to the running statistics of accepted blocks:
//   gate 2 (broadband floor): mean lift over a per-band running median, vs a
//           MAD-self-calibrated threshold - catches a uniform floor rise.
//   gate 3 (total power): a generator stall / long dropout where lines collapse.
// (Gate 1, the near-carrier pedestal, needs the tone-lobe finder and lands with
//  the full FFT-engine port.) Rejected frames never enter the coherent average.

function median(a) {
  if (!a.length) return 0;
  const c = [...a].sort((x, y) => x - y), n = c.length;
  return n & 1 ? c[n >> 1] : 0.5 * (c[(n >> 1) - 1] + c[n >> 1]);
}
function mad(a, m) {
  if (!a.length) return 0;
  const d = a.map(v => Math.abs(v - m)).sort((x, y) => x - y), n = d.length;
  const x = n & 1 ? d[n >> 1] : 0.5 * (d[(n >> 1) - 1] + d[n >> 1]);
  return 1.4826 * x;                                   // scaled to ≈σ for a normal distribution
}

export class SpectralDiscontinuityDetector {
  constructor({ bands = 24, refLen = 8, calLen = 12, sigmaK = 6.0, guardDb = 10.0, minMad = 0.5 } = {}) {
    this.B = bands; this.REF_L = refLen; this.CAL = calLen;
    this.K = sigmaK; this.GUARD_DB = guardDb; this.MIN_MAD = minMad;
    this._half = -1; this.lo = []; this.hi = []; this.ref = []; this.scoreHist = []; this.powerHist = [];
  }

  /** (Re)build the log-spaced band layout and clear history when the FFT length changes. */
  _configure(half) {
    if (half === this._half) return;
    this._half = half; this.lo = []; this.hi = []; this.ref = []; this.scoreHist = []; this.powerHist = [];
    const lnHi = Math.log(half);
    let prev = 1;
    for (let b = 0; b < this.B; b++) {
      let e = Math.round(Math.exp(lnHi * (b + 1) / this.B));
      e = Math.max(prev + 2, Math.min(half, e));
      this.lo.push(prev); this.hi.push(e); prev = e;
      if (e >= half) break;
    }
  }

  /** Clear reference + calibration history (on reset-statistics). */
  reset() { this.ref = []; this.scoreHist = []; this.powerHist = []; }

  /**
   * Ingest one block's half-spectrum (de-rotation does not change magnitude, so
   * the de-rotated spectrum is fine). @returns {boolean} true if the block is an
   * outlier to be REJECTED.
   */
  reject(re, im, half) {
    this._configure(half);
    const bands = this.lo.length, level = new Float64Array(bands);
    let totalPow = 0;
    for (let b = 0; b < bands; b++) {
      let s = 0;
      for (let k = this.lo[b]; k < this.hi[b]; k++) s += re[k] * re[k] + im[k] * im[k];
      const n = this.hi[b] - this.lo[b];
      level[b] = 10 * Math.log10((n > 0 ? s / n : 0) + 1e-300);
      totalPow += s;
    }
    const powerDb = 10 * Math.log10(totalPow + 1e-300);

    // A band is a line/skirt when it towers over its local median (self-references; no history needed).
    const isLine = (b) => {
      const lo = Math.max(0, b - 3), hi = Math.min(bands, b + 4), loc = [];
      for (let j = lo; j < hi; j++) loc.push(level[j]);
      return level[b] > median(loc) + this.GUARD_DB;
    };

    if (this.ref.length === 0) {                       // first block seeds the reference, accept
      this.ref.push(Float64Array.from(level));
      this.powerHist.push(powerDb);
      return false;
    }

    // gate 2 - broadband floor lift vs the running per-band median (lines excluded).
    const rho = [];
    for (let b = 0; b < bands; b++) {
      if (isLine(b)) continue;
      rho.push(level[b] - median(this.ref.map(r => r[b])));
    }
    const score = rho.length ? rho.reduce((a, c) => a + c, 0) / rho.length : 0;
    const sMed = median(this.scoreHist);
    const scoreOut = score > sMed + this.K * Math.max(mad(this.scoreHist, sMed), this.MIN_MAD);

    // gate 3 - total power outlier (stall / dropout).
    const pMed = median(this.powerHist);
    const powerOut = Math.abs(powerDb - pMed) > this.K * Math.max(mad(this.powerHist, pMed), this.MIN_MAD);

    const rejected = scoreOut || powerOut;
    this.scoreHist.push(score); if (this.scoreHist.length > this.CAL) this.scoreHist.shift();
    this.powerHist.push(powerDb); if (this.powerHist.length > this.CAL) this.powerHist.shift();
    if (!rejected) { this.ref.push(Float64Array.from(level)); if (this.ref.length > this.REF_L) this.ref.shift(); }
    return rejected;
  }
}
