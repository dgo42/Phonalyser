/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.dsp.SpectralDiscontinuityDetector - the
// frequency-domain glitch / discontinuity rejector for the coherently averaged FFT.
// Where a time-domain Nth-difference test fails on weak signals (low SNR buries the
// glitch under broadband noise), this works on the SPECTRUM and compares each block
// to the running statistics of the blocks already collected - robust because a real
// line references itself while a glitch lifts the floor or the near-carrier pedestal.
//
// THREE GATES (all dB ratios, self-calibrated against the collected history -> the
// test is amplitude-independent and learns its own clean baseline):
//   1. Near-carrier pedestal - the noise "rock" in the bins flanking each
//      fundamental (the tone's ACTUAL data-derived main lobe excluded), measured as
//      its excess over the block's own broadband floor (pedestal − floor). Its
//      running median + k·MAD over the collected blocks is the threshold.
//   2. Broadband floor - reduce the spectrum to B log-spaced band levels (dB), keep
//      a per-band running median over the last L accepted blocks, and reject when the
//      mean lift over the floor bands exceeds a MAD-self-calibrated median + k·MAD.
//   3. Total power - a generator stall / long ADC dropout where the lines collapse
//      instead of the floor rising.
//
// Only the very first block (which seeds the reference) is accepted unconditionally;
// from the second block on every gate is live, the thin early history covered by
// small MAD floors and sharpening as more clean averages accumulate. Streaming,
// single-threaded (the FFT worker / pool thread); not synchronized.
//
// This SUPERSEDES the partial 2-gate js/dsp/discontinuity.js (gates 2 & 3 only); the
// full detector needs ToneLobeLift, which is now ported - gate 1 lands with it. The
// older stub is left in place (it has no importers; not this change's to remove).

import { ToneLobeLift } from './tone-lobe-lift.js';

/** dB floors on the self-calibrated MADs so a momentarily very-stable run can't
 *  collapse a threshold onto its median and false-fire on ordinary bin noise. */
const MIN_SCORE_MAD = 0.5;
const MIN_POWER_MAD = 0.5;
const MIN_PEDESTAL_MAD = 0.5;

/** The near-carrier pedestal is sampled in a strong tone's skirt, where ordinary
 *  window leakage breathes over a heavier tail than the broadband floor - so it gets
 *  its OWN (larger) sigma; only splatter well above the leakage envelope trips it. */
const PEDESTAL_SIGMA_K = 10.0;

/** Minimum tone-lobe exclusion half-width, and the pedestal sampling span, in HZ -
 *  converted to bins via binWidthHz so they don't drift with FFT size / sample rate. */
const PEAK_HALFWIDTH_HZ = 1.1;
const SKIRT_WIDTH_HZ = 8.8;

/** Around each fundamental the floor keeps out only the tone's OWN band plus any band
 *  whose centre lands within this guard - so the lobe's leakage can't drop the
 *  neighbour bands and open a wide hole in the floor at the tone. */
const FLOOR_GUARD_HZ = 100.0;

/** Median of a[0..n) (copies + sorts; mirrors the desktop helper exactly). */
function median(a, n) {
  if (n <= 0) return 0.0;
  const c = Array.prototype.slice.call(a, 0, n).sort((x, y) => x - y);
  return (n % 2 === 1) ? c[(n / 2) | 0] : 0.5 * (c[n / 2 - 1] + c[n / 2]);
}

/** Median absolute deviation × 1.4826 (≈σ for a normal distribution). */
function mad(a, n, med) {
  if (n <= 0) return 0.0;
  const d = new Float64Array(n);
  for (let i = 0; i < n; i++) d[i] = Math.abs(a[i] - med);
  d.sort();
  const m = (n % 2 === 1) ? d[(n / 2) | 0] : 0.5 * (d[n / 2 - 1] + d[n / 2]);
  return 1.4826 * m;
}

function mean(a, n) {
  if (n <= 0) return 0.0;
  let s = 0.0;
  for (let i = 0; i < n; i++) s += a[i];
  return s / n;
}

export class SpectralDiscontinuityDetector {
  /** Defaults mirror the desktop no-arg constructor:
   *  (numBands=48, historyBlocks=8, calibBlocks=12, scoreSigmaK=6, powerSigmaK=6,
   *   guardDb=10, minBandBins=8). */
  constructor(numBands = 48, historyBlocks = 8, calibBlocks = 12,
              scoreSigmaK = 6.0, powerSigmaK = 6.0, guardDb = 10.0, minBandBins = 8) {
    this._numBands = Math.max(4, numBands);
    this._historyBlocks = Math.max(3, historyBlocks);
    this._calibBlocks = Math.max(3, calibBlocks);
    this._scoreSigmaK = scoreSigmaK;
    this._powerSigmaK = powerSigmaK;
    this._guardDb = guardDb;
    this._minBandBins = Math.max(1, minBandBins);

    /** Data-derived main-lobe finder (the same one the .frc stretch uses). */
    this._lobe = new ToneLobeLift();
    this._binWidthHz = 1.0;

    this._halfSize = -1;
    this._bands = 0;
    this._bandLo = null;
    this._bandHi = null;

    // per-band level history ring (dB), most-recent L accepted blocks
    this._ref = null; this._refFill = 0; this._refHead = 0;
    // calibration histories
    this._scoreHist = null; this._scoreFill = 0; this._scoreHead = 0;
    this._powerHist = null; this._powerFill = 0; this._powerHead = 0;
    this._pedestalHist = null; this._pedFill = 0; this._pedHead = 0;
    // scratch
    this._level = null; this._mref = null; this._rho = null; this._scratch = null;
    this._lineBand = null;
  }

  /** (Re)builds the log-spaced band layout for a spectrum of halfSize bins and clears
   *  all history. Call when the FFT length changes. */
  configure(halfSize) {
    if (halfSize === this._halfSize) return;
    this._halfSize = halfSize;
    this._ref = [];
    for (let i = 0; i < this._historyBlocks; i++) this._ref.push(new Float64Array(this._numBands));
    this._scoreHist = new Float64Array(this._calibBlocks);
    this._powerHist = new Float64Array(this._calibBlocks);
    this._pedestalHist = new Float64Array(this._calibBlocks);
    this._level = new Float64Array(this._numBands);
    this._mref = new Float64Array(this._numBands);
    this._rho = new Float64Array(this._numBands);
    this._lineBand = new Uint8Array(this._numBands);
    this._scratch = new Float64Array(Math.max(this._historyBlocks, Math.max(this._numBands, this._calibBlocks)));
    this.reset();
  }

  /** Clears the reference + calibration history (e.g. on reset-statistics) without
   *  rebuilding the band layout. */
  reset() {
    this._refFill = this._refHead = 0;
    this._scoreFill = this._scoreHead = 0;
    this._powerFill = this._powerHead = 0;
    this._pedFill = this._pedHead = 0;
  }

  /**
   * Ingests one block's complex spectrum (bins 0..halfSize-1) and returns true if the
   * block is an outlier to be REJECTED. peakBins are the known fundamental bin(s)
   * whose near-carrier pedestal excess is gated (may be null/empty to skip gate 1).
   * @param {Float64Array} re
   * @param {Float64Array} im
   * @param {number} halfSize
   * @param {number} binWidthHz
   * @param {?Int32Array|number[]} peakBins
   * @returns {boolean} true => reject
   */
  reject(re, im, halfSize, binWidthHz, peakBins) {
    this._binWidthHz = binWidthHz > 0.0 ? binWidthHz : 1.0;
    this.configure(halfSize);
    this._buildBands(halfSize, peakBins);   // re-apply the ±FLOOR_GUARD_HZ tone-band narrowing

    const bands = this._bands;
    const level = this._level;
    const bandLo = this._bandLo, bandHi = this._bandHi;

    // --- band levels (dB) + total power ---
    let totalPow = 0.0;
    for (let b = 0; b < bands; b++) {
      let sum = 0.0;
      for (let k = bandLo[b]; k < bandHi[b]; k++) sum += re[k] * re[k] + im[k] * im[k];
      const n = bandHi[b] - bandLo[b];
      level[b] = 10.0 * Math.log10((n > 0 ? sum / n : 0.0) + 1e-300);
      totalPow += sum;
    }
    const lastPowerDb = 10.0 * Math.log10(totalPow + 1e-300);

    // Per-block line detection (no history) -> noise floor + pedestal excess.
    const lineBand = this._lineBand;
    for (let b = 0; b < bands; b++) lineBand[b] = this._isLocalLine(level, b) ? 1 : 0;
    const lastFloorDb = this._floorMedian(level);
    const lastPedestalDb = this._pedestalDb(re, im, peakBins);
    let pedFloorDb = this._pedestalFloorDb(peakBins);    // two bands flanking the tone
    if (Number.isNaN(pedFloorDb)) pedFloorDb = lastFloorDb;
    const lastPedestalExcess = Number.isNaN(lastPedestalDb) ? NaN : lastPedestalDb - pedFloorDb;

    if (this._refFill === 0) {                    // first block: seed the history, accept
      this._pushPower(lastPowerDb);
      this._pushPedestal(lastPedestalExcess);
      this._pushRef(level);
      this.lastGates = null;                      // seed block - no gate ran
      this.lastFloorDb = lastFloorDb; this.lastPowerDb = lastPowerDb;
      this.lastPedestalExcess = lastPedestalExcess;
      return false;
    }

    // --- gate 1: near-carrier pedestal excess vs its collected median ---
    let pedestalOut = false;
    let pedestalThresh = NaN;
    if (!Number.isNaN(lastPedestalExcess) && this._pedFill > 0) {
      const pedMed = median(this._pedestalHist, this._pedFill);
      const pedMad = mad(this._pedestalHist, this._pedFill, pedMed);
      pedestalThresh = pedMed + PEDESTAL_SIGMA_K * Math.max(pedMad, MIN_PEDESTAL_MAD);
      pedestalOut = lastPedestalExcess > pedestalThresh;
    }

    // --- gate 2: broadband floor lift vs the running-median reference ---
    const mref = this._mref, scratch = this._scratch, ref = this._ref;
    for (let b = 0; b < bands; b++) {
      for (let i = 0; i < this._refFill; i++) scratch[i] = ref[i][b];
      mref[b] = median(scratch, this._refFill);
    }
    const rho = this._rho;
    let m = 0;
    for (let b = 0; b < bands; b++) {
      if (lineBand[b]) continue;         // a real line / skirt - self-references, skip
      rho[m++] = level[b] - mref[b];
    }
    const score = m > 0 ? mean(rho, m) : 0.0;
    const sMed = this._scoreFill > 0 ? median(this._scoreHist, this._scoreFill) : 0.0;
    const sMad = this._scoreFill > 0 ? mad(this._scoreHist, this._scoreFill, sMed) : 0.0;
    const scoreThresh = sMed + this._scoreSigmaK * Math.max(sMad, MIN_SCORE_MAD);
    const scoreOut = score > scoreThresh;

    // --- gate 3: total power (stall / dropout) ---
    const pMed = median(this._powerHist, this._powerFill);
    const pMad = mad(this._powerHist, this._powerFill, pMed);
    const powerThresh = this._powerSigmaK * Math.max(pMad, MIN_POWER_MAD);
    const powerOut = Math.abs(lastPowerDb - pMed) > powerThresh;

    const rejected = pedestalOut || scoreOut || powerOut;
    this._pushScore(score);                      // baselines track all blocks (robust to outliers)
    this._pushPower(lastPowerDb);
    this._pushPedestal(lastPedestalExcess);
    if (!rejected) this._pushRef(level);         // reference only from accepted blocks

    // Live diagnostics of THIS decision (the desktop's lastScore/lastThreshold/
    // lastPedestalExcess/lastFloorDb/lastPowerDb fields) - which gate fired and
    // against what threshold, for the controller's debug line.
    this.lastGates = { pedestal: pedestalOut, score: scoreOut, power: powerOut };
    this.lastScore = score; this.lastScoreThresh = scoreThresh;
    this.lastPowerDb = lastPowerDb; this.lastPowerMed = pMed; this.lastPowerThresh = powerThresh;
    this.lastPedestalExcess = lastPedestalExcess; this.lastPedestalThresh = pedestalThresh;
    this.lastFloorDb = lastFloorDb;
    return rejected;
  }

  // ---------------------------------------------------------------- internals

  /** Median power (-> dB) of the bins flanking each fundamental, main lobe excluded.
   *  NaN when no peaks are supplied. */
  _pedestalDb(re, im, peakBins) {
    if (peakBins == null || peakBins.length === 0) return NaN;
    const mag = (k) => Math.hypot(re[k], im[k]);
    const peakHalf = Math.max(1, Math.round(PEAK_HALFWIDTH_HZ / this._binWidthHz));
    const skirt = Math.max(8, Math.round(SKIRT_WIDTH_HZ / this._binWidthHz));
    const buf = new Float64Array(peakBins.length * 2 * skirt);
    let n = 0;
    const mb = this._halfSize - 1;   // bins here run 1..halfSize-1
    for (let i = 0; i < peakBins.length; i++) {
      const f = peakBins[i];
      // Exclude the tone's DATA-DERIVED main lobe (not a fixed width) so normal window
      // leakage isn't counted; sample the skirt just beyond.
      const e = this._lobe.lobeBins(mag, f, mb, this._lobe.localFloor(mag, f, mb));
      const lo = Math.min(e[0], f - peakHalf);
      const hi = Math.max(e[1], f + peakHalf);
      n = this._collectSkirt(re, im, lo - skirt, lo, buf, n);
      n = this._collectSkirt(re, im, hi + 1, hi + 1 + skirt, buf, n);
    }
    if (n === 0) return NaN;
    return 10.0 * Math.log10(median(buf, n) + 1e-300);
  }

  _collectSkirt(re, im, from, to, buf, n) {
    from = Math.max(1, from);
    to = Math.min(this._halfSize, to);
    for (let k = from; k < to && n < buf.length; k++) buf[n++] = re[k] * re[k] + im[k] * im[k];
    return n;
  }

  /** Noise floor = median of the non-line band levels (dB). */
  _floorMedian(lvl) {
    const scratch = this._scratch, lineBand = this._lineBand;
    let n = 0;
    for (let b = 0; b < this._bands; b++) if (!lineBand[b]) scratch[n++] = lvl[b];
    if (n === 0) for (let b = 0; b < this._bands; b++) scratch[n++] = lvl[b];   // all lines: fall back
    return median(scratch, n);
  }

  /** Pedestal-gate noise floor: median of the two bands flanking each fundamental
   *  (its left/right neighbours) - the local floor right beside the tone, free of the
   *  jittery far-out bands. NaN when no flanking band exists. */
  _pedestalFloorDb(peakBins) {
    if (peakBins == null || peakBins.length === 0) return NaN;
    const buf = new Float64Array(peakBins.length * 2);
    let n = 0;
    for (let i = 0; i < peakBins.length; i++) {
      const bf = this._bandOf(peakBins[i]);
      if (bf < 0) continue;
      if (bf - 1 >= 0) buf[n++] = this._level[bf - 1];
      if (bf + 1 < this._bands) buf[n++] = this._level[bf + 1];
    }
    return n === 0 ? NaN : median(buf, n);
  }

  /** Index of the band containing bin, or −1. */
  _bandOf(bin) {
    for (let b = 0; b < this._bands; b++) if (bin >= this._bandLo[b] && bin < this._bandHi[b]) return b;
    return -1;
  }

  /** A band is a line / skirt when its level towers over the local median of nearby
   *  bands - derived from the current block, so it needs no history. */
  _isLocalLine(lvl, b) {
    const lo = Math.max(0, b - 3), hi = Math.min(this._bands, b + 4);
    const loc = new Float64Array(hi - lo);
    let n = 0;
    for (let j = lo; j < hi; j++) loc[n++] = lvl[j];
    return lvl[b] > median(loc, n) + this._guardDb;
  }

  _buildBands(halfSize, peakBins) {
    const firstBin = 1;                      // skip DC
    const lastBin = Math.max(firstBin + 1, halfSize);
    const lnLo = Math.log(firstBin), lnHi = Math.log(lastBin);
    const lo = new Int32Array(this._numBands);
    const hi = new Int32Array(this._numBands);
    let count = 0, prev = firstBin;
    for (let b = 0; b < this._numBands; b++) {
      const f = (b + 1) / this._numBands;
      let edge = Math.round(Math.exp(lnLo + (lnHi - lnLo) * f));
      edge = Math.max(prev + this._minBandBins, Math.min(lastBin, edge)); // ≥ minBandBins bins
      if (prev >= lastBin) break;
      lo[count] = prev;
      hi[count] = edge;
      count++;
      prev = edge;
      if (edge >= lastBin) break;
    }
    this._bands = Math.max(1, count);
    this._bandLo = lo.slice(0, this._bands);
    this._bandHi = hi.slice(0, this._bands);
    const bands = this._bands;
    const bandLo = this._bandLo, bandHi = this._bandHi;

    // Shrink the band holding each fundamental to ±FLOOR_GUARD_HZ, handing its freed
    // bins to the immediate neighbours, so the floor's gap at the tone is the guard
    // width - not the lobe's full leakage extent.
    if (peakBins != null) {
      const guard = Math.max(1, Math.round(FLOOR_GUARD_HZ / this._binWidthHz));
      for (let i = 0; i < peakBins.length; i++) {
        const pf = peakBins[i];
        let bf = -1;
        for (let b = 0; b < bands; b++) if (pf >= bandLo[b] && pf < bandHi[b]) { bf = b; break; }
        if (bf < 0) continue;
        const nlo = Math.max(bf > 0 ? bandLo[bf - 1] + 1 : firstBin, pf - guard);
        const nhi = Math.min(bf < bands - 1 ? bandHi[bf + 1] - 1 : lastBin, pf + guard);
        if (nhi <= nlo) continue;
        if (bf > 0) bandHi[bf - 1] = nlo;
        if (bf < bands - 1) bandLo[bf + 1] = nhi;
        bandLo[bf] = nlo;
        bandHi[bf] = nhi;
      }
    }
  }

  _pushRef(lvl) {
    this._ref[this._refHead].set(lvl.subarray(0, this._bands), 0);
    this._refHead = (this._refHead + 1) % this._historyBlocks;
    if (this._refFill < this._historyBlocks) this._refFill++;
  }

  _pushScore(v) {
    this._scoreHist[this._scoreHead] = v;
    this._scoreHead = (this._scoreHead + 1) % this._calibBlocks;
    if (this._scoreFill < this._calibBlocks) this._scoreFill++;
  }

  _pushPower(v) {
    this._powerHist[this._powerHead] = v;
    this._powerHead = (this._powerHead + 1) % this._calibBlocks;
    if (this._powerFill < this._calibBlocks) this._powerFill++;
  }

  _pushPedestal(v) {
    if (Number.isNaN(v)) return;
    this._pedestalHist[this._pedHead] = v;
    this._pedHead = (this._pedHead + 1) % this._calibBlocks;
    if (this._pedFill < this._calibBlocks) this._pedFill++;
  }
}
