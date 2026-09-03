/*
 * Phonalyser web - IMD product range-semantics test.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Run with `node web/test/imd-analyzer-range.test.mjs`.
 *
 * Mirror of org.edgo.audio.measure.gui.fft.ImdAnalyzerRangeTest. Range
 * semantics of the IMD product readout: a product whose frequency falls outside
 * the measurable spectrum (below DC or beyond the last bin) must report NaN -
 * "not measurable" - never a synthetic floor voltage (no −600, no −300). One-
 * sided DFD3 (SMPTE-style tone pairs, where 2f1 − f2 lands below DC) must still
 * report the measurable sideband, and the combined IMD power must skip
 * unmeasurable products instead of going NaN.
 */

import { analyzeImd, MAX_ORDER } from '../js/fft/imd-analyzer.js';

let passed = 0, failed = 0;
function ok(cond, msg) { if (cond) { passed++; } else { failed++; console.error('FAIL:', msg); } }

const BIN_BW_HZ = 10.0;
/** Spectrum length in bins - top frequency ≈ 40.95 kHz. */
const BINS = 4096;
/** Any physically meaningful dBV reading sits far above this. */
const ABSURD_DBV = -400.0;

/** Synthetic dBFS spectrum: −140 dBFS floor with two −6 dBFS tones. */
function spectrumWithTones(f1Hz, f2Hz) {
  const amplitudeDbFs = new Float64Array(BINS);
  amplitudeDbFs.fill(-140.0);
  amplitudeDbFs[Math.round(f1Hz / BIN_BW_HZ)] = -6.0;
  amplitudeDbFs[Math.round(f2Hz / BIN_BW_HZ)] = -6.0;
  return {
    amplitudeDbFs,
    freqResolution: BIN_BW_HZ,
    fundamentalHzRefined: f1Hz,
    fundamental2HzRefined: f2Hz,
    fundamentalTrueDbFs: NaN,   // no manual override
  };
}

/** A dBV cell is either NaN (unmeasurable) or a plausible voltage - never the
 *  old −600 / −300 synthetic floor. */
function assertAbsurdFree(dbv, what) {
  ok(Number.isNaN(dbv) || dbv > ABSURD_DBV, `${what} = ${dbv}`);
}

// --- outOfRangeProducts: 600 Hz + 15 kHz. 2f1−f2 and all higher dnL sidebands
//     land below DC; d4H (43.8 kHz) and d5H (58.2 kHz) land beyond the spectrum.
{
  const imd = analyzeImd(spectrumWithTones(600.0, 15_000.0), 600.0, 15_000.0, 0.0);
  ok(imd != null, 'out-of-range pair: analyzeImd returns a result');

  ok(Number.isNaN(imd.dnLDbV[3]), '2f1-f2 below DC must be NaN');
  ok(Number.isNaN(imd.dnHDbV[4]), 'd4H beyond spectrum must be NaN');
  ok(Number.isNaN(imd.dnHDbV[5]), 'd5H beyond spectrum must be NaN');
  ok(Number.isFinite(imd.dnHDbV[3]), 'd3H (29.4 kHz) is measurable');
  ok(Number.isFinite(imd.dfd2Pct), 'f2-f1 (14.4 kHz) is measurable');
  // SMPTE-style one-sided DFD3: the lower sideband is below DC, but the upper
  // one is measurable and must still be reported.
  ok(Number.isFinite(imd.dfd3Pct), 'one-sided DFD3 must still report');
  // NaN products are skipped in the power sum, never poisoning it.
  ok(Number.isFinite(imd.imdPwrPct), 'IMD power must skip NaN products');

  for (let k = 2; k <= MAX_ORDER; k++) {
    assertAbsurdFree(imd.dnLDbV[k], `dnLDbV[${k}]`);
    assertAbsurdFree(imd.dnHDbV[k], `dnHDbV[${k}]`);
  }
}

// --- allInRangePair: 19 + 20 kHz CCIF pair - every product from d2 to d5 fits
//     the grid, so every cell is finite.
{
  const imd = analyzeImd(spectrumWithTones(19_000.0, 20_000.0), 19_000.0, 20_000.0, 0.0);
  ok(imd != null, 'in-range pair: analyzeImd returns a result');

  for (let k = 2; k <= MAX_ORDER; k++) {
    ok(Number.isFinite(imd.dnLDbV[k]), `dnLDbV[${k}] finite`);
    ok(Number.isFinite(imd.dnHDbV[k]), `dnHDbV[${k}] finite`);
  }
  ok(Number.isFinite(imd.dfd2Pct), 'in-range DFD2 finite');
  ok(Number.isFinite(imd.dfd3Pct), 'in-range DFD3 finite');
  ok(Number.isFinite(imd.imdPwrPct), 'in-range IMD power finite');
}

console.log(`imd-analyzer-range.test: ${passed} passed, ${failed} failed (MAX_ORDER=${MAX_ORDER})`);
process.exit(failed ? 1 : 0);
