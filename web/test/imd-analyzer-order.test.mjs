/*
 * Phonalyser web - dual-tone IMD slot-order (F1/F2 crossing) test.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Run with `node web/test/imd-analyzer-order.test.mjs`.
 *
 * Pins the F1/F2 crossing fix in analyzeImd (imd-analyzer.js). The refined
 * frequency pair (fundamentalHzRefined / fundamental2HzRefined) arrives in
 * ANALYZER-SLOT order - slot 1 is the detector's primary, which is the HIGHER
 * tone when the user enters tones high-first (the verified F1=7000 / F2=1300
 * case). The peaks p1@fLow / p2@fHigh are ALWAYS sorted (F1 = lower), so the
 * refined substitution must re-pair to the sorted slots, else f1Hz gets the low
 * tone's level attached to the high tone's frequency, the dots draw crossed, and
 * the product formulas run on the unsorted pair - d2L = f2 − f1 = −5700 Hz reads
 * the "no bin" sentinel while the real 5.7 kHz product goes unmeasured.
 *
 * Synthetic setup: two tones at 1300 Hz and 7000 Hz at DISTINCT levels on a
 * 10 Hz bin grid, plus the difference product at 5700 Hz, on a flat floor. The
 * refined slots are REVERSED (slot 1 = 7000.1, slot 2 = 1300.1).
 */

import { analyzeImd, MAX_ORDER } from '../js/fft/imd-analyzer.js';

let passed = 0, failed = 0;
function ok(cond, msg) { if (cond) { passed++; } else { failed++; console.error('FAIL:', msg); } }

// --- Grid: 48 kHz / 4800-point FFT -> 10 Hz/bin so every tone and the difference
//     product land on an exact bin. ---------------------------------------------
const SAMPLE_RATE = 48000;
const FFT_SIZE = 4800;
const BIN_HZ = SAMPLE_RATE / FFT_SIZE;   // 10 Hz
const HALF = FFT_SIZE / 2;               // 2400

const F_LOW_HZ = 1300;    // bin 130 - the LOWER tone (should end up F1)
const F_HIGH_HZ = 7000;   // bin 700 - the HIGHER tone (should end up F2)
const D2L_HZ = F_HIGH_HZ - F_LOW_HZ; // 5700 Hz, bin 570 - 2nd-order difference product

const LOW_DBFS = -6.0;    // distinct levels so a crossed assignment is detectable
const HIGH_DBFS = -20.0;
const D2L_DBFS = -60.0;   // difference product, well above the floor
const FLOOR_DBFS = -140.0;

/** Builds the dBFS spectrum: a flat floor with three single-bin tones. Only
 *  amplitudeDbFs / freqResolution / the refined-frequency hints are consumed by
 *  analyzeImd, so a single-bin "lobe" per tone is enough (peak refinement clamps
 *  the sub-bin delta to the bin centre when the neighbours are flat). */
function makeSpectrum(reversed) {
  const db = new Float64Array(HALF + 1);
  db.fill(FLOOR_DBFS);
  db[0] = -300.0;                                   // DC
  db[Math.round(F_LOW_HZ / BIN_HZ)] = LOW_DBFS;
  db[Math.round(F_HIGH_HZ / BIN_HZ)] = HIGH_DBFS;
  db[Math.round(D2L_HZ / BIN_HZ)] = D2L_DBFS;
  return {
    amplitudeDbFs: db,
    freqResolution: BIN_HZ,
    // Reversed slots: slot 1 carries the HIGH tone's refined value (the user
    // entered 7000 first), slot 2 the LOW tone's - analyzer-slot order.
    fundamentalHzRefined: reversed ? F_HIGH_HZ + 0.1 : F_LOW_HZ + 0.1,
    fundamental2HzRefined: reversed ? F_LOW_HZ + 0.1 : F_HIGH_HZ + 0.1,
    fundamentalTrueDbFs: NaN,
  };
}

// --- Case A: reversed slots (the buggy input). analyzeImd is called with the
//     user's high-first commanded order (7000, 1300) - exactly the reported case.
{
  const out = analyzeImd(makeSpectrum(true), F_HIGH_HZ, F_LOW_HZ, 0.0);
  ok(out != null, 'reversed pair: analyzeImd returns a result');

  // f1Hz must be the LOWER refined value, f2Hz the higher - no crossing.
  ok(Math.abs(out.f1Hz - (F_LOW_HZ + 0.1)) < 1e-6,
     `reversed pair: f1Hz = LOWER refined (${(F_LOW_HZ + 0.1)}), got ${out.f1Hz}`);
  ok(Math.abs(out.f2Hz - (F_HIGH_HZ + 0.1)) < 1e-6,
     `reversed pair: f2Hz = HIGHER refined (${(F_HIGH_HZ + 0.1)}), got ${out.f2Hz}`);
  ok(out.f1Hz < out.f2Hz, `reversed pair: f1Hz < f2Hz (${out.f1Hz} < ${out.f2Hz})`);

  // f1DbFs must carry the LOW tone's level (−6 dBFS), NOT the 7 kHz tone's
  // (−20). This is the crossing the user saw: F1's row showing the 1.3 kHz
  // frequency with the wrong tone's levels.
  ok(Math.abs(out.f1DbFs - LOW_DBFS) < 0.05,
     `reversed pair: f1DbFs = the 1300-tone level (${LOW_DBFS}), got ${out.f1DbFs.toFixed(3)}`);
  ok(Math.abs(out.f2DbFs - HIGH_DBFS) < 0.05,
     `reversed pair: f2DbFs = the 7000-tone level (${HIGH_DBFS}), got ${out.f2DbFs.toFixed(3)}`);

  // d2L = f2 − f1 must land at ≈5700 Hz (the real product), and be measured as a
  // finite voltage - NOT the NaN a negative (below-DC) frequency would yield.
  ok(Math.abs(out.dnLHz[2] - D2L_HZ) < 1e-3,
     `reversed pair: d2L frequency ≈ ${D2L_HZ} Hz (got ${out.dnLHz[2]})`);
  ok(Number.isFinite(out.dnLDbV[2]),
     `reversed pair: d2L measured (finite dBV), got ${out.dnLDbV[2]}`);
  ok(out.dnLPct[2] > 0.0,
     `reversed pair: d2L % > 0 (real 5.7 kHz product measured), got ${out.dnLPct[2]}`);
  // Absent-product semantics: an out-of-spectrum product is "not measurable" -
  // its dBV is NaN (the IMD table renders it as "---"), never a synthetic floor.
  ok(Number.isNaN(out.dnHDbV[5]),
     `absent d5H is NaN (renders "---"), got ${out.dnHDbV[5]}`);
}

// --- Case B: normal (already-sorted) slots produce the SAME sorted result - the
//     fix must not perturb the correctly-ordered path. --------------------------
{
  const out = analyzeImd(makeSpectrum(false), F_LOW_HZ, F_HIGH_HZ, 0.0);
  ok(out != null && Math.abs(out.f1Hz - (F_LOW_HZ + 0.1)) < 1e-6
     && Math.abs(out.f2Hz - (F_HIGH_HZ + 0.1)) < 1e-6
     && Math.abs(out.f1DbFs - LOW_DBFS) < 0.05,
     `ordered pair: unchanged - f1Hz=${out && out.f1Hz}, f2Hz=${out && out.f2Hz}, f1DbFs=${out && out.f1DbFs.toFixed(3)}`);
}

// --- Case C: only ONE refined value finite - it must attach to the slot whose
//     PEAK frequency is closer, the other slot keeping its peak fallback. Here
//     the sole refined value sits near the HIGH tone, so it lands in f2Hz and
//     f1Hz falls back to the LOW peak. ------------------------------------------
{
  const spec = makeSpectrum(false);
  spec.fundamentalHzRefined = F_HIGH_HZ + 0.1;   // the only finite refined value...
  spec.fundamental2HzRefined = NaN;              // ...near the HIGH tone
  const out = analyzeImd(spec, F_LOW_HZ, F_HIGH_HZ, 0.0);
  ok(out != null && Math.abs(out.f2Hz - (F_HIGH_HZ + 0.1)) < 1e-6,
     `one-refined: value attaches to the CLOSER (high) slot f2Hz, got ${out && out.f2Hz}`);
  ok(out != null && Math.abs(out.f1Hz - F_LOW_HZ) < BIN_HZ,
     `one-refined: f1Hz falls back to the LOW peak (≈${F_LOW_HZ}), got ${out && out.f1Hz}`);
  ok(out != null && out.f1Hz < out.f2Hz,
     `one-refined: f1Hz < f2Hz still holds (${out && out.f1Hz} < ${out && out.f2Hz})`);
}

console.log(`imd-analyzer-order.test: ${passed} passed, ${failed} failed (MAX_ORDER=${MAX_ORDER})`);
process.exit(failed ? 1 : 0);
