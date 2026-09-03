/*
 * Phonalyser web - dual-tone IMD-product .frc de-embed test.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Run with `node web/test/fft-compensation-imd.test.mjs`.
 *
 * Pins the dual-tone half of the tone-only .frc correction (dsp/frc.js
 * toneFrequencies + cascadeInverse + applyInverseInPlace, mirror of
 * FreqRespCalHelper): with a .frc active and "with noise" OFF, a DUAL-TONE
 * signal must have its intermod PRODUCT lobes corrected too (dnL/dnH, orders
 * 2..MAX_IMD_ORDER) - the same discrete tones ImdAnalyzer reads - while the
 * noise BETWEEN products stays untouched (that whole-spectrum divide is the
 * "with noise" mode's job).
 *
 * Synthetic setup: two tones f1 = 2000 Hz, f2 = 2500 Hz on a 10 Hz bin grid, a
 * third-order upper product d3H = 2*f2 - f1 = 3000 Hz seeded as its own lobe,
 * and a flat noise floor. The cal is unity everywhere except a localized dip at
 * d3H. After the de-embed the d3H lobe must rise by the cal dB, while a genuine
 * noise bin between f2 and d3H must be unchanged bit for bit.
 */

import { FftResult } from '../js/fft/fft-result.js';
import {
  applyInverseInPlace, cascadeInverse, toneBins, toneFrequencies, toneLobeSpans,
} from '../js/dsp/frc.js';

let passed = 0, failed = 0;
function ok(cond, msg) { if (cond) { passed++; } else { failed++; console.error('FAIL:', msg); } }

// --- Grid: 48 kHz / 4800-point FFT -> 10 Hz/bin so every tone and every
//     linear-combination product lands on an exact bin. -----------------------
const SAMPLE_RATE = 48000;
const FFT_SIZE = 4800;
const BIN_HZ = SAMPLE_RATE / FFT_SIZE;   // 10 Hz
const HALF = FFT_SIZE / 2;               // 2400

const F1_HZ = 2000;   // bin 200
const F2_HZ = 2500;   // bin 250
const D3H_HZ = 2 * F2_HZ - F1_HZ; // 3000 Hz, bin 300 - 3rd-order upper product (k=3)
const D3H_BIN = Math.round(D3H_HZ / BIN_HZ);
const NOISE_PROBE_BIN = 275;     // 2750 Hz - between f2 (250) and d3H (300), off every lobe

const NOISE = 1e-4;              // flat floor magnitude (linear)
const PEAK = 1.0;                // lobe peak magnitude
const SHOULDER1 = 0.30;          // one bin out
const SHOULDER2 = 0.02;          // two bins out (still above floor; three drops to floor)
const CAL_DIP_DB = -12.0;        // cal attenuation at d3H -> de-embed lifts by +12 dB
const CAL_DIP_LIN = Math.pow(10.0, CAL_DIP_DB / 20.0);

/** Seeds a narrow main lobe (peak plus two shoulders above the flat floor). */
function seedLobe(re, bin) {
  re[bin] = PEAK;
  re[bin - 1] = SHOULDER1; re[bin + 1] = SHOULDER1;
  re[bin - 2] = SHOULDER2; re[bin + 2] = SHOULDER2;
  // Three bins out and beyond stay at the floor so the lobe-edge walk terminates.
}

/** Builds a fresh dual-tone FftResult with three seeded lobes on a flat floor. */
function makeResult() {
  const r = new FftResult();
  r.fftSize = FFT_SIZE;
  r.sampleRate = SAMPLE_RATE;
  r.freqResolution = BIN_HZ;
  r.ensureArrays(HALF + 1, 0);
  r.harmonicCount = 0;

  const re = r.re, im = r.im, db = r.amplitudeDbFs;
  for (let k = 0; k <= HALF; k++) { re[k] = NOISE; im[k] = 0.0; }
  seedLobe(re, Math.round(F1_HZ / BIN_HZ));
  seedLobe(re, Math.round(F2_HZ / BIN_HZ));
  seedLobe(re, D3H_BIN);
  for (let k = 0; k <= HALF; k++) {
    db[k] = re[k] > 0.0 ? 20.0 * Math.log10(re[k]) : -300.0;
  }

  r.fundamentalBin = Math.round(F1_HZ / BIN_HZ);
  r.fundamentalHz = F1_HZ;
  r.fundamentalHzRefined = F1_HZ;
  r.fundamental2HzRefined = F2_HZ;      // the dual-tone marker
  r.fundamentalDbFs = db[r.fundamentalBin];
  r.fundamentalLinear = PEAK;
  return r;
}

/** Unity cal across the band, except a localized dip centred on `dipHz`. The dip
 *  is one calibration point (its two neighbours sit at unity), so interpolate()
 *  returns the full dip only AT the product frequency - exactly the discrete-tone
 *  correction this pins. */
function makeCal(dipHz, dipLin) {
  const freqs = new Float64Array([1.0, dipHz - BIN_HZ, dipHz, dipHz + BIN_HZ, SAMPLE_RATE / 2]);
  const magLin = new Float64Array([1.0, 1.0, dipLin, 1.0, 1.0]);
  const phaseRad = new Float64Array(freqs.length); // 0 everywhere - magnitude-only cal
  return { freqs, magLin, phaseRad };
}

/** The tone-only correction of ONE loaded row, the way FftViewCorrection runs it:
 *  the frame's tone set and lobe spans, one cascade of that single row, then the
 *  fast path over the marked lobe bins. */
function deEmbedToneOnly(r, cal) {
  const tones = toneFrequencies(r);
  const spans = toneLobeSpans(r, tones);
  const inv = cascadeInverse([], [cal], tones, spans, r.fftSize, r.sampleRate);
  return applyInverseInPlace(r, inv, toneBins(spans, r.fftSize / 2));
}

const mag = (r, k) => Math.hypot(r.re[k], r.im[k]);

// --- Run the de-embed with the dual marker present. ---------------------------
const r = makeResult();
const cal = makeCal(D3H_HZ, CAL_DIP_LIN);
const productMagBefore = mag(r, D3H_BIN);
const noiseMagBefore = mag(r, NOISE_PROBE_BIN);
const noiseReBefore = r.re[NOISE_PROBE_BIN];
const noiseImBefore = r.im[NOISE_PROBE_BIN];

deEmbedToneOnly(r, cal);

const productMagAfter = mag(r, D3H_BIN);
const noiseMagAfter = mag(r, NOISE_PROBE_BIN);

// 1) The product lobe's peak rose by the cal attenuation (1/|H| = +12 dB). The
//    correction is one flat complex multiply across the lobe, so every bin of it
//    - the peak included - gains exactly the cal dB.
const riseDb = 20.0 * Math.log10(productMagAfter / productMagBefore);
ok(Math.abs(riseDb - (-CAL_DIP_DB)) < 0.2,
   `d3H product lobe rose by the cal dB: expected +${(-CAL_DIP_DB).toFixed(1)} dB, got ${riseDb.toFixed(3)} dB`);

// 2) A noise bin BETWEEN the products is untouched - bit for bit (no lobe covers
//    it, so neither the tone slots nor the product slots wrote it).
ok(r.re[NOISE_PROBE_BIN] === noiseReBefore && r.im[NOISE_PROBE_BIN] === noiseImBefore,
   `noise bin ${NOISE_PROBE_BIN} (between f2 and d3H) unchanged: `
   + `re ${noiseReBefore}->${r.re[NOISE_PROBE_BIN]}, im ${noiseImBefore}->${r.im[NOISE_PROBE_BIN]}`);
ok(Math.abs(20.0 * Math.log10(noiseMagAfter / noiseMagBefore)) < 1e-9,
   `noise bin level between products did not move (${(20.0 * Math.log10(noiseMagAfter / noiseMagBefore)).toExponential(2)} dB)`);

// 3) Control: WITHOUT the dual marker (single-tone), no product slot is filled,
//    so the same d3H bin is left alone - isolating the product set as the cause
//    of assertion (1).
const rSingle = makeResult();
rSingle.fundamental2HzRefined = NaN; // no second tone -> single-tone path
const productMagSingleBefore = mag(rSingle, D3H_BIN);
deEmbedToneOnly(rSingle, makeCal(D3H_HZ, CAL_DIP_LIN));
ok(Math.abs(mag(rSingle, D3H_BIN) - productMagSingleBefore) < 1e-12,
   `single-tone: d3H bin NOT corrected (no dual marker) - ${productMagSingleBefore} -> ${mag(rSingle, D3H_BIN)}`);

// 4) Reversed refined pair: fundamentalHzRefined = HIGH tone (f2),
//    fundamental2HzRefined = LOW tone (f1) - the analyzer-slot order the user hits
//    by entering tones high-first. The tone-set builder SORTS the pair (min/max),
//    so the 2nd-order DIFFERENCE product d2L = fHigh - fLow (= 500 Hz) stays
//    positive and its lobe IS de-embedded by the cal dB. Without the sort the
//    difference goes negative and is skipped, leaving the real product
//    uncorrected. This sort is an intended web divergence from the Java tone set.
const D2L_HZ = F2_HZ - F1_HZ;          // 500 Hz - 2nd-order lower (difference) product
const D2L_BIN = Math.round(D2L_HZ / BIN_HZ);
const rRev = makeResult();
seedLobe(rRev.re, D2L_BIN);            // seed the difference-product lobe
for (let k = 0; k <= HALF; k++) {
  rRev.amplitudeDbFs[k] = rRev.re[k] > 0.0 ? 20.0 * Math.log10(rRev.re[k]) : -300.0;
}
rRev.fundamentalHzRefined = F2_HZ;    // reversed: slot 1 carries the HIGH tone
rRev.fundamental2HzRefined = F1_HZ;   //           slot 2 carries the LOW tone
const d2lMagBefore = mag(rRev, D2L_BIN);
deEmbedToneOnly(rRev, makeCal(D2L_HZ, CAL_DIP_LIN));
const d2lRiseDb = 20.0 * Math.log10(mag(rRev, D2L_BIN) / d2lMagBefore);
ok(Math.abs(d2lRiseDb - (-CAL_DIP_DB)) < 0.2,
   `reversed pair: d2L difference product (${D2L_HZ} Hz) STILL corrected by the cal dB: `
   + `expected +${(-CAL_DIP_DB).toFixed(1)} dB, got ${d2lRiseDb.toFixed(3)} dB`);

console.log(`fft-compensation-imd.test: ${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
