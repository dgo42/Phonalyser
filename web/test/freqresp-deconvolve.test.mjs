/*
 * Phonalyser web - frequency-response deconvolution faithfulness test.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Run with `node web/test/freqresp-deconvolve.test.mjs`.
 *
 * Guards the Task-1 root cause: the deconvolution DSP (deconvolve.js +
 * farina-sweep.js) is bit-faithful to the desktop FreqRespCalHelper and must
 * produce a CLEAN response for a clean single-clock loopback - flat ~0 dB in the
 * passband, a real notch where the DUT notches, and NO broadband comb / no huge
 * near-Nyquist spike. When the whole pipeline is fed a matched-clock capture
 * (reference X and recorded Y at the SAME rate - the invariant the host now
 * enforces by refusing to trust a resampled output), the trace is smooth.
 *
 * The reported garbage plot (comb + wrong HF envelope + +100 dB spike) was NOT a
 * DSP bug; it was the browser resampling the sweep because the output device
 * could not open at the capture rate. This test pins the DSP so a future edit to
 * deconvolve.js can't silently reintroduce combing, and documents the contract
 * the host's rate-mismatch warning protects.
 */

import { renderLogSweep, renderWindowedLogSweep, sweepFadeSamples } from '../js/freqresp/farina-sweep.js';
import { computeFromLogSweep, binAlignedFreqs } from '../js/freqresp/deconvolve.js';
import { nextPow2 } from '../js/dsp/mathutil.js';

let passed = 0, failed = 0;
function ok(cond, msg) { if (cond) { passed++; } else { failed++; console.error('FAIL:', msg); } }

// --- synthetic matched-clock loopback: play the windowed sweep through a 1 kHz
//     biquad notch (twin-T-like) + a fixed transport delay, record at the SAME
//     rate as the reference. This is the "output ran at the capture rate" case. ---
const fs = 192000, f0 = 20, f1 = 90000;
const durSec = 1.0, leadInSec = 0.1;
const sweepSamples = Math.round(durSec * fs);
const leadInSamples = Math.round(leadInSec * fs);
const fade = sweepFadeSamples(sweepSamples);

const sweepRef = renderLogSweep(f0, f1, sweepSamples, fs);              // reference X (raw)
const windowed = renderWindowedLogSweep(f0, f1, sweepSamples, fs, fade); // what is "played"

const delay = 137;
const played = new Float64Array(leadInSamples + sweepSamples + fs / 2);
for (let i = 0; i < sweepSamples; i++) played[leadInSamples + i] = windowed[i];

// 1 kHz notch biquad (RBJ notch, Q = 10).
const w0 = 2 * Math.PI * 1000 / fs, cw = Math.cos(w0), sw = Math.sin(w0), Q = 10, al = sw / (2 * Q);
const b0 = 1, b1 = -2 * cw, b2 = 1, a0 = 1 + al, a1 = -2 * cw, a2 = 1 - al;
const rec = new Float64Array(played.length + delay);
let x1 = 0, x2 = 0, y1 = 0, y2 = 0;
for (let i = 0; i < played.length; i++) {
  const x = played[i];
  const y = (b0 / a0) * x + (b1 / a0) * x1 + (b2 / a0) * x2 - (a1 / a0) * y1 - (a2 / a0) * y2;
  x2 = x1; x1 = x; y2 = y1; y1 = y;
  rec[i + delay] = y;
}

const deconvM = nextPow2(Math.max(rec.length, leadInSamples + sweepSamples));
const binHz = fs / deconvM;
const freqs = binAlignedFreqs(f0, f1, binHz, 400);
const cal = computeFromLogSweep(rec, sweepRef, leadInSamples, fs, freqs, 1.0, 1.0, fade);

function dbAt(hz) {
  let bi = 0, bd = Infinity;
  for (let i = 0; i < freqs.length; i++) { const d = Math.abs(freqs[i] - hz); if (d < bd) { bd = d; bi = i; } }
  return 20 * Math.log10(cal.magLin[bi]);
}

// Passband is flat at ~0 dB (matched-clock unity loopback through a notch DUT).
ok(Math.abs(dbAt(100)) < 0.5, `100 Hz ≈ 0 dB (got ${dbAt(100).toFixed(2)})`);
ok(Math.abs(dbAt(10000)) < 0.5, `10 kHz ≈ 0 dB (got ${dbAt(10000).toFixed(2)})`);
ok(Math.abs(dbAt(80000)) < 0.5, `80 kHz ≈ 0 dB (got ${dbAt(80000).toFixed(2)})`);

// The notch shows a real dip at 1 kHz and NOT elsewhere.
ok(dbAt(1000) < -5, `1 kHz notch dips (got ${dbAt(1000).toFixed(2)} dB)`);
ok(dbAt(1000) < dbAt(500) - 3 && dbAt(1000) < dbAt(2000) - 3, 'the dip is localised at the notch frequency');

// NO broadband comb: passband ripple away from the notch stays tiny.
let mn = Infinity, mx = -Infinity;
for (let i = 0; i < freqs.length; i++) {
  if (freqs[i] > 2000 && freqs[i] < 85000) {
    const d = 20 * Math.log10(cal.magLin[i]);
    if (d < mn) mn = d; if (d > mx) mx = d;
  }
}
ok((mx - mn) < 1.0, `no comb: passband ripple 2k-85k < 1 dB (got ${(mx - mn).toFixed(2)})`);

// NO near-Nyquist blow-up: the top of the swept band stays bounded (a resampled
// output would spike +tens of dB here).
ok(dbAt(89000) < 3, `no HF spike at the band top (got ${dbAt(89000).toFixed(2)} dB)`);

console.log(`freqresp-deconvolve.test: ${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
