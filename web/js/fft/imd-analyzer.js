/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.gui.fft.ImdAnalyzer and ImdResult.
//
// Compiles an IMD result from a dual-tone FFT spectrum: detects the two
// fundamentals (argmax + quadratic peak refinement around the commanded
// frequencies), reads the CCIF/DIN intermodulation products (n = 2..5 plus
// DFD2 / DFD3), and derives the combined IMD power and total-distortion+noise
// figures. All ratios are computed against the linear-magnitude reference
// |F1| + |F2| and are dBV-offset invariant; only the absolute V_rms / dBV
// outputs apply dbvOffsetDb. TD+N is the Parseval residual drop from total RMS
// to the fundamental skirts and is window-independent (the ENBW cancels).
//
// All math runs on the raw dBFS spectrum (offset-invariant for peak picking,
// the skirt walk and every figure of merit). The manual-fundamental override
// splits the TRUE COMBINED level across the two tones by their measured ratio
// (equal tones at 1 V together → each √(½) = −3.01 dBV).

/** Max IMD-order index (d2..d5). Array slots 0..1 are unused. */
export const MAX_ORDER = 5;

/** Half-width of the bin window scanned around each commanded frequency. Small
 *  enough that we never latch onto an IMD product as a fundamental, large
 *  enough to cover sample-rate / FFT-length rounding when snap is off. Exported
 *  so the FLL dual-tone steer refines each tone over the SAME window. */
export const TONE_SEARCH_BINS = 8;

/**
 * One slot of intermodulation-distortion measurements computed from a dual-tone
 * FFT spectrum. Mirrors the Java ImdResult field-for-field. Arrays are sized
 * for n = 0..MAX_ORDER with [0] / [1] unused (so dnLPct[2] reads as "d₂L").
 *
 * @typedef {Object} ImdResult
 * @property {number} f1Hz   Refined F1 frequency (Hz).
 * @property {number} f2Hz   Refined F2 frequency (Hz).
 * @property {number} f1Mag  F1 V_rms in volts (10^(f1DbV/20)).
 * @property {number} f2Mag  F2 V_rms in volts.
 * @property {number} f1DbV  F1 absolute level (dBV), possibly manual-anchored.
 * @property {number} f2DbV  F2 absolute level (dBV).
 * @property {number} f1DbFs F1 measured peak level (dBFS) — drives markers/autoscale.
 * @property {number} f2DbFs F2 measured peak level (dBFS).
 * @property {number} diffHz f2 − f1 (Hz).
 * @property {number} dfd2Pct DFD2 (f2 − f1) amplitude, % of |F1|+|F2|.
 * @property {number} dfd3Pct DFD3 (√(|2f1−f2|²+|2f2−f1|²)), % of |F1|+|F2|.
 * @property {number} imdPwrPct Combined intermod RMS, % of |F1|+|F2|.
 * @property {number} tdnPct  Total distortion + noise, % (window-independent).
 * @property {Float64Array} dnLHz  Lower-sideband product frequencies (Hz), [2..MAX_ORDER].
 * @property {Float64Array} dnHHz  Upper-sideband product frequencies (Hz).
 * @property {Float64Array} dnLPct Lower-sideband levels, % of |F1|+|F2|.
 * @property {Float64Array} dnHPct Upper-sideband levels, % of |F1|+|F2|.
 * @property {Float64Array} dnLDbV Lower-sideband levels, dBV.
 * @property {Float64Array} dnHDbV Upper-sideband levels, dBV.
 */

/**
 * Builds an {@link ImdResult} from a dual-tone FFT result.
 *
 * @param {{amplitudeDbFs: ?Float64Array, freqResolution: number,
 *          fundamentalHzRefined: number, fundamental2HzRefined: number,
 *          fundamentalTrueDbFs: number}} r
 *        FFT result. `amplitudeDbFs` is the per-bin dBFS spectrum;
 *        `freqResolution` is Hz/bin; `fundamentalHzRefined` /
 *        `fundamental2HzRefined` are the analyzer's clean-frame sub-bin
 *        frequency estimates (use NaN / ≤0 when unavailable);
 *        `fundamentalTrueDbFs` is the manual-fundamental override (non-finite
 *        when not supplied).
 * @param {number} f1Cmd commanded tone 1 frequency (Hz).
 * @param {number} f2Cmd commanded tone 2 frequency (Hz).
 * @param {number} dbvOffsetDb dBFS→dBV anchor (cached Preferences value).
 * @returns {?ImdResult} null when there isn't enough data to compute.
 */
export function analyzeImd(r, f1Cmd, f2Cmd, dbvOffsetDb) {
  if (r == null || r.amplitudeDbFs == null) return null;
  const binBw = r.freqResolution;
  if (!(binBw > 0)) return null;
  const amplitudeDbFs = r.amplitudeDbFs;

  // The smaller-index ("lower") fundamental is always F1 even if the user typed
  // them in the other order, so dnL (below F1) / dnH (above F2) stay meaningful.
  const fLow = Math.min(f1Cmd, f2Cmd);
  const fHigh = Math.max(f1Cmd, f2Cmd);

  // Detect F1 / F2 (argmax + quadratic refinement; offset-invariant).
  const p1 = refinePeak(amplitudeDbFs, binBw, fLow, TONE_SEARCH_BINS);
  const p2 = refinePeak(amplitudeDbFs, binBw, fHigh, TONE_SEARCH_BINS);
  if (p1 == null || p2 == null) return null;

  const out = newImdResult();

  // Frequencies come from the analyzer's clean-frame sub-bin estimate; fall
  // back to the local peak when the estimate is unavailable.
  out.f1Hz = (r.fundamentalHzRefined > 0.0)
    ? r.fundamentalHzRefined : p1.freqHz;
  out.f2Hz = (!Number.isNaN(r.fundamental2HzRefined) && r.fundamental2HzRefined > 0.0)
    ? r.fundamental2HzRefined : p2.freqHz;

  // Manual fundamental override: split the TRUE COMBINED level across the two
  // tones by their measured ratio. Only absolute dBV / V_rms outputs are
  // anchored; the spectrum dBFS is left untouched.
  out.f1DbFs = p1.levelDbFs; // measured — drives the dBFS column + markers
  out.f2DbFs = p2.levelDbFs;
  let f1Lvl = p1.levelDbFs;
  let f2Lvl = p2.levelDbFs;
  if (Number.isFinite(r.fundamentalTrueDbFs)) {
    const m1 = Math.pow(10.0, p1.levelDbFs / 20.0);
    const m2 = Math.pow(10.0, p2.levelDbFs / 20.0);
    const mTot = Math.hypot(m1, m2);
    if (mTot > 0.0) {
      f1Lvl = r.fundamentalTrueDbFs + 20.0 * Math.log10(m1 / mTot);
      f2Lvl = r.fundamentalTrueDbFs + 20.0 * Math.log10(m2 / mTot);
    }
  }
  out.f1DbV = f1Lvl + dbvOffsetDb;
  out.f2DbV = f2Lvl + dbvOffsetDb;
  // f1Mag / f2Mag are V_rms in volts (= 10^(dBV/20), dBV referenced to 1 V_rms).
  out.f1Mag = Math.pow(10.0, out.f1DbV / 20.0);
  out.f2Mag = Math.pow(10.0, out.f2DbV / 20.0);
  out.diffHz = out.f2Hz - out.f1Hz;

  // Reference magnitude for ratios = |F1| + |F2| in V_rms, floored at a tiny
  // positive so the % divide doesn't blow up when both tones are muted.
  const refMag = Math.max(1e-12, out.f1Mag + out.f2Mag);

  // DFD2 (= f2 − f1) and DFD3 (= 2f1 − f2 / 2f2 − f1).
  const dfd2Mag = readBinVrms(amplitudeDbFs, binBw, out.f2Hz - out.f1Hz, dbvOffsetDb);
  const dfd3LowMag = readBinVrms(amplitudeDbFs, binBw, 2.0 * out.f1Hz - out.f2Hz, dbvOffsetDb);
  const dfd3HighMag = readBinVrms(amplitudeDbFs, binBw, 2.0 * out.f2Hz - out.f1Hz, dbvOffsetDb);
  const dfd3Mag = Math.sqrt(dfd3LowMag * dfd3LowMag + dfd3HighMag * dfd3HighMag);
  out.dfd2Pct = 100.0 * dfd2Mag / refMag;
  out.dfd3Pct = 100.0 * dfd3Mag / refMag;

  // n-th-order IMD products (n = 2..5). CCIF/DIN naming:
  //   d2L = f2 − f1, d2H = f1 + f2
  //   dnL = (n−1)·f1 − (n−2)·f2, dnH = (n−1)·f2 − (n−2)·f1  for n ≥ 3
  let imdPwrSq = 0.0;
  for (let k = 2; k <= MAX_ORDER; k++) {
    let fL, fH;
    if (k === 2) {
      fL = out.f2Hz - out.f1Hz;        // difference
      fH = out.f2Hz + out.f1Hz;        // sum
    } else {
      fL = (k - 1) * out.f1Hz - (k - 2) * out.f2Hz;
      fH = (k - 1) * out.f2Hz - (k - 2) * out.f1Hz;
    }
    const magL = readBinVrms(amplitudeDbFs, binBw, fL, dbvOffsetDb);
    const magH = readBinVrms(amplitudeDbFs, binBw, fH, dbvOffsetDb);
    out.dnLHz[k] = fL;
    out.dnHHz[k] = fH;
    out.dnLPct[k] = 100.0 * magL / refMag;
    out.dnHPct[k] = 100.0 * magH / refMag;
    out.dnLDbV[k] = 20.0 * Math.log10(Math.max(1e-30, magL));
    out.dnHDbV[k] = 20.0 * Math.log10(Math.max(1e-30, magH));
    imdPwrSq += magL * magL + magH * magH;
  }
  // Include DFD2 / DFD3 components in the combined IMD power.
  imdPwrSq += dfd2Mag * dfd2Mag + dfd3LowMag * dfd3LowMag + dfd3HighMag * dfd3HighMag;
  out.imdPwrPct = 100.0 * Math.sqrt(imdPwrSq) / refMag;

  // TD+N as the scalar drop from total RMS to the fundamentals —
  // (Vrms − √(F1² + F2²)) / Vrms — straight from the spectrum and
  // window-independent. F1 / F2 are stripped with the same dynamic skirt walk
  // FftAnalyzer uses: estimate a leakage-immune floor (10th-percentile bin
  // level) and walk outward from each tone's peak while the level stays above
  // it, stopping at the first bin that dips into the noise.
  const nBins = amplitudeDbFs.length;
  const floorDbFs = noiseFloorDbFs(amplitudeDbFs);
  const binF1 = Math.round(out.f1Hz / binBw);
  const binF2 = Math.round(out.f2Hz / binBw);
  const lo1 = skirtEdge(amplitudeDbFs, binF1, -1, floorDbFs);
  const hi1 = skirtEdge(amplitudeDbFs, binF1, +1, floorDbFs);
  const lo2 = skirtEdge(amplitudeDbFs, binF2, -1, floorDbFs);
  const hi2 = skirtEdge(amplitudeDbFs, binF2, +1, floorDbFs);
  let sumAll = 0.0;
  let sumResidual = 0.0;
  for (let b = 1; b < nBins; b++) {
    // FS-relative bin voltage — TD+N is a ratio, the dBV offset cancels.
    const vBin = Math.pow(10.0, amplitudeDbFs[b] / 20.0);
    const sq = vBin * vBin;
    sumAll += sq;
    const inSkirt = (b >= lo1 && b <= hi1) || (b >= lo2 && b <= hi2);
    if (!inSkirt) {
      sumResidual += sq;
    }
  }
  const vTotal = Math.sqrt(sumAll);
  const vFund = Math.sqrt(sumAll - sumResidual);
  out.tdnPct = (vTotal > 0)
    ? 100.0 * (Math.sqrt((vTotal * vTotal) - (vFund * vFund))) / vTotal
    : 0.0;
  return out;
}

/** @returns {ImdResult} a zeroed result with all arrays allocated. */
function newImdResult() {
  return {
    f1Hz: 0, f2Hz: 0, f1Mag: 0, f2Mag: 0,
    f1DbV: 0, f2DbV: 0, f1DbFs: 0, f2DbFs: 0, diffHz: 0,
    dfd2Pct: 0, dfd3Pct: 0, imdPwrPct: 0, tdnPct: 0,
    dnLHz: new Float64Array(MAX_ORDER + 1),
    dnHHz: new Float64Array(MAX_ORDER + 1),
    dnLPct: new Float64Array(MAX_ORDER + 1),
    dnHPct: new Float64Array(MAX_ORDER + 1),
    dnLDbV: new Float64Array(MAX_ORDER + 1),
    dnHDbV: new Float64Array(MAX_ORDER + 1),
  };
}

/** Picks the highest bin within ±searchBins of centreHz and refines its
 *  position via the standard 3-point quadratic peak-interpolation formula.
 *  Works on the raw dBFS spectrum. Returns null when centreHz falls outside
 *  the representable bin range. Exported so the FLL dual-tone steer can refine
 *  each tone off the controller-side spectrum (see fft-controller.js) without
 *  running the full IMD product table. */
export function refinePeak(amplitudeDbFs, binBw, centreHz, searchBins) {
  const n = amplitudeDbFs.length;
  const centre = Math.round(centreHz / binBw);
  if (centre < 1 || centre >= n - 1) return null;
  const lo = Math.max(1, centre - searchBins);
  const hi = Math.min(n - 2, centre + searchBins);
  let kMax = lo;
  let vMax = amplitudeDbFs[lo];
  for (let k = lo + 1; k <= hi; k++) {
    if (amplitudeDbFs[k] > vMax) { vMax = amplitudeDbFs[k]; kMax = k; }
  }
  // Quadratic peak refinement. Skip if the parabola is degenerate.
  const yL = amplitudeDbFs[kMax - 1];
  const yC = amplitudeDbFs[kMax];
  const yR = amplitudeDbFs[kMax + 1];
  const denom = (yL - 2 * yC + yR);
  let delta = (Math.abs(denom) < 1e-12) ? 0.0 : 0.5 * (yL - yR) / denom;
  if (delta < -0.5) delta = -0.5;
  if (delta > 0.5) delta = 0.5;
  return {
    freqHz: (kMax + delta) * binBw,
    // Interpolated peak value (Smith & Serra, eqn. 6).
    levelDbFs: yC - 0.25 * (yL - yR) * delta,
  };
}

/** Returns the V_rms voltage at the bin nearest freqHz: the dBFS bin lifted to
 *  dBV via dbvOffsetDb, then to volts. Out-of-range frequencies return 0. */
function readBinVrms(amplitudeDbFs, binBw, freqHz, dbvOffsetDb) {
  if (!(freqHz > 0)) return 0.0;
  const n = amplitudeDbFs.length;
  const b = Math.round(freqHz / binBw);
  if (b < 1 || b >= n) return 0.0;
  return Math.pow(10.0, (amplitudeDbFs[b] + dbvOffsetDb) / 20.0);
}

/** Leakage-immune noise-floor estimate: the 10th-percentile bin level (dBFS)
 *  across the spectrum (DC excluded). dB is monotonic in power, so the
 *  percentile bin is identical whether taken on levels or powers. */
function noiseFloorDbFs(amplitudeDbFs) {
  const n = amplitudeDbFs.length;
  if (n <= 1) return Number.NEGATIVE_INFINITY;
  const len = n - 1;
  const scratch = new Float64Array(len);
  scratch.set(amplitudeDbFs.subarray(1, n));
  return selectKth(scratch, len, Math.trunc(len / 10));
}

/** In-place quickselect: partitions a[0..len) until the k-th smallest element
 *  sits at index k, and returns it. Hoare partition with a median-of-three
 *  pivot. Same result as sort(a)[k] at O(n) expected. */
function selectKth(a, len, k) {
  let lo = 0;
  let hi = len - 1;
  while (lo < hi) {
    const pivot = medianOfThree(a[lo], a[(lo + hi) >>> 1], a[hi]);
    let i = lo;
    let j = hi;
    while (i <= j) {
      while (a[i] < pivot) i++;
      while (a[j] > pivot) j--;
      if (i <= j) {
        const tmp = a[i]; a[i] = a[j]; a[j] = tmp;
        i++; j--;
      }
    }
    if (k <= j) hi = j;
    else if (k >= i) lo = i;
    else return a[k];
  }
  return a[k];
}

/** Middle value of the three — pivot choice for {@link selectKth}. */
function medianOfThree(a, b, c) {
  if (a > b) { const t = a; a = b; b = t; }
  if (b > c) { b = c; }
  return Math.max(a, b);
}

/** Walks from peakBin in direction dir (+1 / −1) while bins stay above
 *  floorDbFs, returning the farthest bin still on the fundamental's skirt. */
function skirtEdge(amplitudeDbFs, peakBin, dir, floorDbFs) {
  const n = amplitudeDbFs.length;
  let b = peakBin;
  while (b + dir >= 1 && b + dir < n && amplitudeDbFs[b + dir] > floorDbFs) {
    b += dir;
  }
  return b;
}
