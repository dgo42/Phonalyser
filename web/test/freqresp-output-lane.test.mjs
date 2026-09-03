/*
 * Phonalyser web - FreqResp / Tune-notch output-lane + per-channel-calibration test.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Run with `node web/test/freqresp-output-lane.test.mjs`.
 *
 * Guards the two DSP invariants of the per-channel output-lane port:
 *
 *  1. PER-CHANNEL ADC FULL-SCALE - the Tune-notch wizard deconvolves ch0 (L) with
 *     getAdcFsVoltageRms('L') and ch1 (R) with getAdcFsVoltageRms('R') (Java
 *     TuneNotchWizardDialog.deconvolveAndPublish). computeFromLogSweep normalises the
 *     magnitude by amplitudeVRms / adcFsVoltageRms, so |H| is EXACTLY proportional to the
 *     ADC full-scale fed in: deconvolving the SAME recording with the L vs R full-scale
 *     yields magnitudes whose ratio equals adcFsR / adcFsL bin-for-bin. A single shared
 *     scalar (the pre-feature bug) would give ratio 1.0 everywhere.
 *
 *  2. LANE-GATED STEREO BUFFER SHAPE - the seam both the FreqResp sweep and the tune-notch
 *     stream drive (outputLaneGate / outputLaneSamples, the port of PcmQuantizer.encode):
 *     left = sample unless gated to RIGHT; right = sample*rightLaneScale unless gated to
 *     LEFT; the un-selected lane is digital zero, and the LEFT lane is never scaled.
 */

import { renderLogSweep, renderWindowedLogSweep, sweepFadeSamples } from '../js/freqresp/farina-sweep.js';
import { computeFromLogSweep, binAlignedFreqs } from '../js/freqresp/deconvolve.js';
import { nextPow2 } from '../js/dsp/mathutil.js';
import { outputLaneGate, outputLaneSamples } from '../js/generator/dds-kernel.js';

let passed = 0, failed = 0;
function ok(cond, msg) { if (cond) { passed++; } else { failed++; console.error('FAIL:', msg); } }

// ===========================================================================
// Part 1 - per-channel ADC full-scale flows into each channel's deconvolution
// ===========================================================================
const fs = 48000, f0 = 200, f1 = 2000;
const durSec = 0.5, leadInSec = 0.05;
const sweepSamples = Math.round(durSec * fs);
const leadInSamples = Math.round(leadInSec * fs);
const fade = sweepFadeSamples(sweepSamples);

const sweepRef = renderLogSweep(f0, f1, sweepSamples, fs);
const windowed = renderWindowedLogSweep(f0, f1, sweepSamples, fs, fade);
// Clean single-clock loopback: the windowed sweep, delayed by a fixed transport delay.
const delay = 61;
const rec = new Float64Array(leadInSamples + sweepSamples + delay + 16);
for (let i = 0; i < sweepSamples; i++) rec[leadInSamples + delay + i] = windowed[i];

const deconvM = nextPow2(Math.max(rec.length, leadInSamples + sweepSamples));
const binHz = fs / deconvM;
const freqs = binAlignedFreqs(f0, f1, binHz, 200);
const amp = 0.5;
const adcFsL = 1.5, adcFsR = 3.0;   // the R endpoint's full-scale is 2× the L endpoint's

// The wizard's two deconvolutions: the SAME recording, each channel's OWN ADC full-scale.
const calL = computeFromLogSweep(rec, sweepRef, leadInSamples, fs, freqs, amp, adcFsL, fade, false);
const calR = computeFromLogSweep(rec, sweepRef, leadInSamples, fs, freqs, amp, adcFsR, fade, false);

let maxRelErr = 0;
let compared = 0;
const expectRatio = adcFsR / adcFsL;
for (let i = 0; i < freqs.length; i++) {
  if (calL.magLin[i] <= 0) continue;
  const ratio = calR.magLin[i] / calL.magLin[i];
  maxRelErr = Math.max(maxRelErr, Math.abs(ratio - expectRatio) / expectRatio);
  compared++;
}
ok(compared > 10, `per-channel FS: enough grid points compared (${compared})`);
ok(maxRelErr < 1e-9,
  `per-channel FS: |H_R|/|H_L| == adcFsR/adcFsL bin-for-bin (max rel err ${maxRelErr.toExponential(2)})`);
// A single shared full-scale (the pre-feature single-scalar path) would collapse the ratio to 1.
ok(Math.abs(expectRatio - 1.0) > 0.5 && maxRelErr < 1e-9,
  'per-channel FS: the two channels are NOT normalised by one shared scalar');

// ===========================================================================
// Part 2 - lane-gated stereo buffer shape (BOTH / LEFT / RIGHT × scale)
// ===========================================================================
const mono = Float64Array.from([1.0, -0.5, 0.25, -0.125, 0.9]);
const zeros = new Float64Array(mono.length);
const rightLaneScale = 0.6;                       // a LINKED card with fsLeft/fsRight = 0.6
const scaled = mono.map((v) => v * rightLaneScale);

function buildLanes(gate, scale) {
  const left = new Float64Array(mono.length);
  const right = new Float64Array(mono.length);
  for (let i = 0; i < mono.length; i++) {
    const [l, r] = outputLaneSamples(mono[i], gate, scale);
    left[i] = l; right[i] = r;
  }
  return { left, right };
}
function arrEq(a, b) {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (Math.abs(a[i] - b[i]) > 1e-12) return false;
  return true;
}

// The gate booleans (outputLaneGate - the hoisted per-block form).
ok(outputLaneGate('BOTH').wantL && outputLaneGate('BOTH').wantR, 'gate BOTH drives both lanes');
ok(outputLaneGate('LEFT').wantL && !outputLaneGate('LEFT').wantR, 'gate LEFT silences the right lane');
ok(!outputLaneGate('RIGHT').wantL && outputLaneGate('RIGHT').wantR, 'gate RIGHT silences the left lane');

// BOTH × scale 1.0 -> both lanes carry the identical sweep (pre-feature behaviour;
// the gate-only FreqResp sweep path, whose rightLaneScale is fixed at 1.0).
let b = buildLanes('BOTH', 1.0);
ok(arrEq(b.left, mono) && arrEq(b.right, mono), 'BOTH × 1.0 -> both lanes carry the sweep');

// BOTH × 0.6 -> left lane UNSCALED (the amplitude reference), right lane × rightLaneScale
// (the tune-notch path's setChannelScale(1.0, rightLaneScale)).
b = buildLanes('BOTH', rightLaneScale);
ok(arrEq(b.left, mono), 'BOTH × 0.6 -> LEFT lane is never scaled');
ok(arrEq(b.right, scaled), 'BOTH × 0.6 -> RIGHT lane is ×0.6');

// LEFT -> left carries the sweep, right is digital silence.
b = buildLanes('LEFT', rightLaneScale);
ok(arrEq(b.left, mono) && arrEq(b.right, zeros), 'LEFT -> left carries the sweep, right silent');

// RIGHT -> left is digital silence, right carries the scaled sweep.
b = buildLanes('RIGHT', rightLaneScale);
ok(arrEq(b.left, zeros) && arrEq(b.right, scaled), 'RIGHT -> left silent, right carries the ×0.6 sweep');

console.log(`freqresp-output-lane.test: ${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
