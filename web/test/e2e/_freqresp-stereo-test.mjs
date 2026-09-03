/*
 * Phonalyser web - stereo FreqResp deconvolution + render-time calibration test
 * (no browser). Temp (_-prefixed) data-layer verification for steps 3-5 of the
 * FreqResp port:
 *
 *   (a) Parallel L/R deconvolution against the SAME reference sweep recovers a KNOWN
 *       per-channel difference - here L = unity loopback, R = the same loopback
 *       attenuated by a flat 6 dB. We synthesize the captured response as the FADED
 *       reference sweep (the same Tukey fade the player + computeFromLogSweep apply)
 *       placed after a lead-in, scaled per channel, then deconvolve each side and
 *       assert the recovered L/R magnitude curves differ by 6 dB (±0.5 dB) across the
 *       mid band, and that the absolute levels read ~0 dB (L) and ~−6 dB (R).
 *   (b) The FreqRespCorrectionStore + the view's render-time applyCurrentCalibration
 *       are reversible: loading a calibration equal to the L curve flattens L to
 *       0 dB; clearing it restores the raw L curve bit-for-bit (no re-sweep, no
 *       destructive mutation of the raw result).
 *
 * Run: node test/e2e/_freqresp-stereo-test.mjs   (exits non-zero on any FAIL)
 */
import { renderLogSweep, sweepFadeSamples } from '../../js/freqresp/farina-sweep.js';
import { computeFromLogSweep } from '../../js/freqresp/deconvolve.js';
import { makeFreqRespResult } from '../../js/freqresp/stereo-result.js';
import { CorrectionStore } from '../../js/common/correction-store.js';
import { FreqRespView } from '../../js/freqresp/freqresp-view.js';

let pass = 0, fail = 0;
const ok = (cond, msg) => { if (cond) { pass++; console.log('PASS: ' + msg); } else { fail++; console.log('FAIL: ' + msg); } };

// ── Sweep / capture parameters ───────────────────────────────────────────────
const SR = 48000;
const F0 = 20, F1 = 20000;
const SWEEP_SAMPLES = 1 << 16;          // 65536-sample sweep
const LEAD_IN = 2048;                   // silent lead-in before the sweep
const POINTS = 512;                     // log-spaced output grid
const AMP_VRMS = 1.0, ADC_FS_VRMS = 1.0;   // equal -> recovered magLin reads the true gain
const R_GAIN = Math.pow(10, -6 / 20);   // R channel attenuated by a flat 6 dB

// Log-spaced output grid [F0, F1].
function logGrid(f0, f1, points) {
  const g = new Float64Array(points);
  const l0 = Math.log(f0), l1 = Math.log(f1);
  for (let i = 0; i < points; i++) g[i] = Math.exp(l0 + (l1 - l0) * (i / (points - 1)));
  return g;
}

// The Tukey (per-side Hann) envelope computeFromLogSweep + the player apply.
function applyFade(buf, fadeSamples) {
  const n = buf.length;
  const fS = Math.max(0, Math.min(fadeSamples, Math.trunc(n / 2)));
  const out = new Float64Array(n);
  for (let i = 0; i < n; i++) {
    let env;
    if (fS > 0 && i < fS) env = 0.5 * (1.0 - Math.cos((Math.PI * i) / fS));
    else if (fS > 0 && i >= n - fS) env = 0.5 * (1.0 - Math.cos((Math.PI * (n - 1 - i)) / fS));
    else env = 1.0;
    out[i] = buf[i] * env;
  }
  return out;
}

// Synthesize a captured loopback: the FADED reference sweep placed after the lead-in,
// scaled by `gain` (a flat per-channel level), + a short tail of silence. This is the
// Y(t) the real ADC would record for a flat-response loopback at that gain.
function synthCapture(fadedSweep, gain) {
  const tail = Math.round(SR / 2);
  const out = new Float64Array(LEAD_IN + fadedSweep.length + tail);
  for (let i = 0; i < fadedSweep.length; i++) out[LEAD_IN + i] = fadedSweep[i] * gain;
  return out;
}

console.log('\n== stereo FreqResp deconvolution + render-time calibration test ==');
console.log(`SR=${SR} sweep=${SWEEP_SAMPLES} leadIn=${LEAD_IN} points=${POINTS} R offset=-6 dB\n`);

const sweepRef = renderLogSweep(F0, F1, SWEEP_SAMPLES, SR);
const fade = sweepFadeSamples(SWEEP_SAMPLES);
const fadedSweep = applyFade(sweepRef, fade);
const freqs = logGrid(F0, F1, POINTS);

// L = unity loopback; R = same loopback −6 dB. Single stereo pass, ONE reference.
const yL = synthCapture(fadedSweep, 1.0);
const yR = synthCapture(fadedSweep, R_GAIN);
const calL = computeFromLogSweep(yL, sweepRef, LEAD_IN, SR, freqs, AMP_VRMS, ADC_FS_VRMS, fade);
const calR = computeFromLogSweep(yR, sweepRef, LEAD_IN, SR, freqs, AMP_VRMS, ADC_FS_VRMS, fade);

// ── (a) Recovered L/R difference = the injected 6 dB, mid band ────────────────
const dbOf = (m) => 20 * Math.log10(m);
const MID_LO = 200, MID_HI = 5000;       // mid band where deconvolution is cleanest
let nMid = 0, maxDiffErr = 0, maxLErr = 0, maxRErr = 0;
for (let i = 0; i < freqs.length; i++) {
  const f = freqs[i];
  if (f < MID_LO || f > MID_HI) continue;
  nMid++;
  const dbL = dbOf(calL.magLin[i]);
  const dbR = dbOf(calR.magLin[i]);
  maxLErr = Math.max(maxLErr, Math.abs(dbL - 0));         // L ≈ 0 dB
  maxRErr = Math.max(maxRErr, Math.abs(dbR - (-6)));      // R ≈ −6 dB
  maxDiffErr = Math.max(maxDiffErr, Math.abs((dbL - dbR) - 6));   // L − R ≈ +6 dB
}
ok(nMid > 0, `mid band ${MID_LO}-${MID_HI} Hz has ${nMid} output points`);
ok(maxLErr <= 0.5, `L channel recovers ~0 dB across the mid band (max err ${maxLErr.toFixed(3)} dB ≤ 0.5)`);
ok(maxRErr <= 0.5, `R channel recovers ~−6 dB across the mid band (max err ${maxRErr.toFixed(3)} dB ≤ 0.5)`);
ok(maxDiffErr <= 0.5, `recovered L−R = injected +6 dB across the mid band (max err ${maxDiffErr.toFixed(3)} dB ≤ 0.5)`);

// ── (b) Correction-store calibration apply ↔ clear is reversible ──────────────
// A minimal Preferences stub: just the prefs the view's applyCurrentCalibration reads.
function boundedStub(v) { let val = v; return { get: () => val, set: (x) => { val = x; }, addListener() {} }; }
const prefs = {
  freqRespApplyCalibration: boundedStub(true),
  freqRespNotchEnabled: boundedStub(false),
  freqRespNotchBaseHz: boundedStub(50),
  freqRespLeftVisible: boundedStub(true),
  freqRespRightVisible: boundedStub(false),
  freqRespPhaseVisible: boundedStub(false),
  freqRespShowRiaa: boundedStub(false),
  freqRespReverseRiaa: boundedStub(false),
  freqRespIecAmendment: boundedStub(false),
  freqRespNyquistFraction: boundedStub(1.0),
  freqRespFreqMinHz: boundedStub(F0),
  freqRespFreqMaxHz: boundedStub(F1),
  freqRespMagTopDb: boundedStub(20),
  freqRespMagBotDb: boundedStub(-150),
  freqRespSignalColor: boundedStub(0x0064c8),
  freqRespPhaseColor: boundedStub(0xff0000),
  freqRespReferenceColor: boundedStub(0x009600),
  freqRespBackgroundColor: boundedStub(0xffffff),
  freqRespLineWidth: boundedStub(2.0),
  save() {},
  current: () => ({ inputSampleRate: SR }),
};
const store = new CorrectionStore('test', null);
// Headless: no canvas - call applyCurrentCalibration directly (the part under test).
const view = Object.create(FreqRespView.prototype);
view.prefs = prefs;
view.correctionStore = store;

const rawL = makeFreqRespResult('L', SR, calL.freqs, calL.magLin, calL.phaseRad,
  { startHz: F0, stopHz: F1, sweepPoints: POINTS, durationSec: SWEEP_SAMPLES / SR, leadInSec: LEAD_IN / SR },
  null, false);
const rawMagSnapshot = Float64Array.from(rawL.magLin);

// No calibration loaded -> applyCurrentCalibration is identity (returns the raw object).
const disp0 = view.applyCurrentCalibration(rawL);
ok(disp0 === rawL, 'no calibration loaded -> display copy IS the raw result (identity)');

// Load a calibration equal to the measured L curve. Dividing L by L flattens it to 0 dB.
const selfCal = {
  left: { freqs: calL.freqs, magLin: Float64Array.from(calL.magLin), phaseRad: Float64Array.from(calL.phaseRad) },
  right: { freqs: calR.freqs, magLin: Float64Array.from(calR.magLin), phaseRad: Float64Array.from(calR.phaseRad) },
};
store.setCurrent(selfCal, 'self.frc');
const dispCal = view.applyCurrentCalibration(rawL);
ok(dispCal !== rawL && dispCal.calibrationApplied,
  'calibration loaded -> a NEW calibrated copy is produced (calibrationApplied flag set)');
let maxFlatErr = 0;
for (let i = 0; i < freqs.length; i++) {
  const f = freqs[i];
  if (f < MID_LO || f > MID_HI) continue;
  maxFlatErr = Math.max(maxFlatErr, Math.abs(dbOf(dispCal.magLin[i]) - 0));
}
ok(maxFlatErr <= 0.05,
  `dividing L by a calibration equal to L flattens it to 0 dB (max ${maxFlatErr.toExponential(2)} dB ≤ 0.05)`);

// The raw result must be untouched by the (cloning) calibration step.
let rawIntact = rawL.magLin.length === rawMagSnapshot.length;
for (let i = 0; rawIntact && i < rawL.magLin.length; i++) if (rawL.magLin[i] !== rawMagSnapshot[i]) rawIntact = false;
ok(rawIntact, 'render-time calibration did NOT mutate the raw measured result (clone, not in-place)');

// Clear the calibration -> display copy returns to the raw curve bit-for-bit.
store.clearAll();
const dispClr = view.applyCurrentCalibration(rawL);
ok(dispClr === rawL, 'clearing the calibration -> display copy is the raw result again (identity)');
let reversible = dispClr.magLin.length === rawMagSnapshot.length;
for (let i = 0; reversible && i < dispClr.magLin.length; i++) if (dispClr.magLin[i] !== rawMagSnapshot[i]) reversible = false;
ok(reversible, 'apply-then-clear is reversible: the cleared L curve equals the original raw L curve');

console.log(`\n${fail === 0 ? 'ALL PASSED' : fail + ' FAILED'}  (${pass} passed, ${fail} failed)\n`);
process.exit(fail === 0 ? 0 : 1);
