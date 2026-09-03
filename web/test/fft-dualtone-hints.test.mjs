/*
 * Phonalyser web - DUAL-TONE generator-hint contract. Run with
 * `node --test test/fft-dualtone-hints.test.mjs`.
 *
 * Two sibling divergences of the same hint plumbing that FftController._dispatch feeds to
 * FftAnalyzer, both mirrored from Java FftAnalyzerWorker.doAnalysis:
 *
 *  1) The SECOND-tone hint must be gated on the generator ACTUALLY RUNNING and on the
 *     "FFT fundamental from generator" pref, exactly like the fundamental hint
 *     (Java :1752-1755 `genActive && prefs.isFftFundFromGenerator() && dualTone`). Its search
 *     window is only ±2 bins (fft-analyzer.js:303-306) - 5× tighter than the fundamental's ±10 -
 *     so with the generator idle (an EXTERNAL dual tone, frequency unknown) a commanded nominal
 *     resolves `fundamental2HzRefined` onto a NOISE bin. Every IMD product frequency is derived
 *     from that refined pair and readBinVrms reads the EXACT nearest bin, so the whole product
 *     grid then reads the noise floor. Withheld, the analyzer auto-detects the real second tone
 *     from the clean frame (fft-analyzer.js:285-301).
 *
 *  2) The dual-tone fundamental anchor is min(f1, f2) and the second-tone hint max(f1, f2)
 *     (Java :1709-1714 / :1752-1755) - the LOWER tone anchors the analysis whichever field the
 *     user typed it in. Hinting tone 1 instead makes the measurement ENTRY-ORDER dependent: with
 *     F1 = 7 kHz / F2 = 1.1 kHz the ±10-bin search latches the UPPER tone, so the reported
 *     fundamental, the harmonic grid (14 kHz, 21 kHz ... instead of 2.2 kHz, 3.3 kHz ...) and the
 *     N+D/SINAD residual all change for a signal that did not.
 */
import test from 'node:test';
import assert from 'node:assert';
import { FftAnalyzer } from '../js/fft/fft-analyzer.js';
import { FftResult } from '../js/fft/fft-result.js';
import { FftController } from '../js/fft/fft-controller.js';
import { testPrefs } from './_test-prefs.mjs';
import { analyzeImd } from '../js/fft/imd-analyzer.js';
import { GenSignalForm } from '../js/enums/gen-signal-form.js';
import { MessageBus } from '../js/bus/message-bus.js';
import { Events } from '../js/bus/events.js';

// ── geometry (small enough to run in ms, coarse enough to hold both tones apart) ──
const FS = 48000;
const N = 32768;
const FREQ_RES = FS / N;                       // 1.46484375 Hz/bin
const HOP = N / 16;                            // 93.75 % overlap
const BUF_LEN = N + HOP;                       // perTickFrames = 2, as the controller sizes it
const WINDOW = 'BH7';
const OVERLAP = 'PCT_93_75';
const HARMONICS = 9;

const bins = (hz) => hz / FREQ_RES;
const onBin = (hz, off) => (Math.round(bins(hz)) + off) * FREQ_RES;

let seed = 0x2545f491;
function rnd(n) { let s = (n * 2654435761 + seed) >>> 0; s ^= s >>> 15; s = (s * 2246822519) >>> 0; return s / 0x100000000 - 0.5; }

/** One analysis window of a sum of tones plus tiny deterministic dither. */
function windowOf(tones) {
  const x = new Float64Array(BUF_LEN);
  for (let j = 0; j < BUF_LEN; j++) {
    let v = 1e-8 * rnd(j);
    for (const t of tones) v += t.amp * Math.sin(2 * Math.PI * ((j * (t.hz / FS)) % 1) + t.phase);
    x[j] = v;
  }
  return x;
}

/** One dual-tone analysis with an explicit hint pair (the two values _dispatch posts). */
function analyzeWithHints(samples, expectedFundHz, secondToneHintHz) {
  const a = new FftAnalyzer();
  a.setMultiTone(true);
  a.setSecondToneHintHz(secondToneHintHz);
  return a.analyze(samples, FS, N, HARMONICS, WINDOW, OVERLAP, 0, 0, true, NaN,
    expectedFundHz, new FftResult());
}

// ════════════════════════════════════════════════════════════════════════════════
// 1) the hint PLUMBING - what FftController._dispatch posts to the worker
// ════════════════════════════════════════════════════════════════════════════════

/** A controller wired to stubs, with just the state _dispatch reads. `answer` false models a
 *  tree with NO generator responder - the hints must then be withheld entirely. */
function dispatched({ running, fundFromGenerator, form, t1, t2, answer = true }) {
  const prefs = testPrefs({
    inputSampleRate: FS, outputSampleRate: FS, fftLength: N, genFrequencyHz: t1,
    genDualToneFreq1Hz: t1, genDualToneFreq2Hz: t2, genSignalForm: form,
    fftCalcMaxHarmonic: HARMONICS + 1, fftWindow: WINDOW, fftOverlap: OVERLAP,
    fftCoherentAveraging: true, fftAverages: Infinity, fftMainsSuppression: 'NONE',
    fftFundFromGenerator: fundFromGenerator, fftManualFundEnabled: false, fftThreads: 1,
    fftChannel: 'R', genSnapToFftBin: false,
  });
  const gen = {
    snapped: t1, binW: FREQ_RES, genNode: null, running,
    computeAnalysisFreqs() {}, snapToRate: () => t1, snapToRate2: () => t2,
    _genEmitFreq2: () => t2,
  };
  const c = new FftController({}, gen, prefs, {});
  c.N = N; c.minHop = HOP; c.maxHop = HOP; c.pendingHop = HOP;
  c.perTickFrames = 2; c.bufLen = BUF_LEN;
  c.buf = new Float64Array(BUF_LEN); c.bufW = 0;
  c.foreverMode = true; c.ringN = 1; c.poolSize = 1;
  c._busy = false; c._dispatchId = 0;
  const posted = [];
  c.worker = { postMessage: (msg) => posted.push(msg) };
  // The hints are ASKED for now (GENERATOR_EMITTED_HZ), not recomputed from the config: the
  // generator answers with the pair it emits, in entry order, and the controller re-labels it
  // min/max. That is the whole plumbing under test.
  const bus = MessageBus.instance();
  if (answer) bus.registerResponder(Events.GENERATOR_EMITTED_HZ, () => [t1, t2]);
  try {
    c._dispatch(0, 0);
  } finally {
    bus.unregisterResponder(Events.GENERATOR_EMITTED_HZ);
  }
  return posted[0];
}

const HI_CMD = 7000.0;
const LO_CMD = 1100.0;

test('the SECOND-tone hint is gated on the generator RUNNING + the pref (Java parity)', () => {
  const idle = dispatched({ running: false, fundFromGenerator: true, form: GenSignalForm.DUAL_TONE, t1: HI_CMD, t2: LO_CMD });
  assert.ok(Number.isNaN(idle.secondToneHintHz),
    'generator idle ⇒ no tone-2 hint: an external dual tone must be auto-detected');
  const prefOff = dispatched({ running: true, fundFromGenerator: false, form: GenSignalForm.DUAL_TONE, t1: HI_CMD, t2: LO_CMD });
  assert.ok(Number.isNaN(prefOff.secondToneHintHz), 'pref off ⇒ no tone-2 hint');
  const live = dispatched({ running: true, fundFromGenerator: true, form: GenSignalForm.DUAL_TONE, t1: HI_CMD, t2: LO_CMD });
  assert.strictEqual(live.secondToneHintHz, HI_CMD,
    'generator running + pref on ⇒ hint the UPPER tone (Java Math.max of the two dual-tone freqs)');
  const sine = dispatched({ running: true, fundFromGenerator: true, form: GenSignalForm.SINE, t1: HI_CMD, t2: LO_CMD });
  assert.ok(Number.isNaN(sine.secondToneHintHz), 'single tone ⇒ there is no second tone to hint');
});

test('the dual-tone fundamental anchor is the LOWER tone, whatever the entry order', () => {
  const hiFirst = dispatched({ running: true, fundFromGenerator: true, form: GenSignalForm.DUAL_TONE, t1: HI_CMD, t2: LO_CMD });
  const loFirst = dispatched({ running: true, fundFromGenerator: true, form: GenSignalForm.DUAL_TONE, t1: LO_CMD, t2: HI_CMD });
  assert.strictEqual(hiFirst.expectedFundHz, LO_CMD,
    'F1 > F2 ⇒ the anchor is still F2 (Java Math.min of the two dual-tone freqs)');
  assert.strictEqual(loFirst.expectedFundHz, LO_CMD, 'F1 < F2 ⇒ the anchor is F1');
  // Entry order must not reach the analyzer at all.
  assert.strictEqual(hiFirst.expectedFundHz, loFirst.expectedFundHz);
  assert.strictEqual(hiFirst.secondToneHintHz, loFirst.secondToneHintHz);
  // The single-tone anchor stays the one commanded frequency (Java prefs.getGenFrequencyHz()).
  const sine = dispatched({ running: true, fundFromGenerator: true, form: GenSignalForm.SINE, t1: HI_CMD, t2: LO_CMD });
  assert.strictEqual(sine.expectedFundHz, HI_CMD);
});

// ════════════════════════════════════════════════════════════════════════════════
// 2) gap 1, behavioural - an EXTERNAL dual tone 13 bins off its commanded nominal
// ════════════════════════════════════════════════════════════════════════════════
const EXT_NOM2 = 6000.0;                       // what the UI still holds for tone 2
const EXT_F1 = onBin(5000.0, 0.37);
const EXT_F2 = onBin(EXT_NOM2, 13.0);          // the captured tone, 13 bins above nominal
const EXT_PRODUCTS = [EXT_F2 - EXT_F1, EXT_F1 + EXT_F2, 2 * EXT_F1 - EXT_F2, 2 * EXT_F2 - EXT_F1];
const EXT_WINDOW = windowOf([
  { hz: EXT_F1, amp: 0.50, phase: 0.30 },
  { hz: EXT_F2, amp: 0.25, phase: 1.10 },
  // Real intermod products at the TRUE product frequencies, −80 dB below tone 1.
  ...EXT_PRODUCTS.map((hz, i) => ({ hz, amp: 0.5 * Math.pow(10, -80 / 20), phase: 0.2 * i })),
]);

test('an installed tone-2 hint outside the ±2-bin window mis-resolves the second tone', () => {
  const hinted = analyzeWithHints(EXT_WINDOW, NaN, EXT_NOM2);
  const auto = analyzeWithHints(EXT_WINDOW, NaN, NaN);
  // Both find the same (stronger) tone 1 ...
  assert.ok(Math.abs(hinted.fundamentalHzRefined - EXT_F1) < 1e-3);
  assert.ok(Math.abs(auto.fundamentalHzRefined - EXT_F1) < 1e-3);
  // ... but the hinted tone 2 is pinned inside ±2 bins of the stale nominal, ~13 bins low.
  const errHinted = (hinted.fundamental2HzRefined - EXT_F2) / FREQ_RES;
  const errAuto = (auto.fundamental2HzRefined - EXT_F2) / FREQ_RES;
  assert.ok(Math.abs(errHinted) > 5,
    `hinted tone 2 should be stuck near the nominal (was ${errHinted.toFixed(3)} bins off the real tone)`);
  assert.ok(Math.abs(errAuto) < 0.01,
    `auto-detected tone 2 should land on the real tone (was ${errAuto.toFixed(4)} bins off)`);
});

test('the mis-resolved second tone collapses the whole IMD product grid to the noise floor', () => {
  const hinted = analyzeWithHints(EXT_WINDOW, NaN, EXT_NOM2);
  const auto = analyzeWithHints(EXT_WINDOW, NaN, NaN);
  const imdHinted = analyzeImd(hinted, 5000.0, EXT_NOM2, 0);
  const imdAuto = analyzeImd(auto, 5000.0, EXT_NOM2, 0);
  // Every product frequency is derived from the refined pair, and readBinVrms takes the EXACT
  // nearest bin - so a tone 2 that is 13 bins off reads d₂L 13 bins away from the real product.
  const trueD2Bin = Math.round((EXT_F2 - EXT_F1) / FREQ_RES);
  assert.strictEqual(Math.round(imdAuto.diffHz / FREQ_RES), trueD2Bin,
    'the auto-detected pair puts d₂L on the real difference-tone bin');
  assert.notStrictEqual(Math.round(imdHinted.diffHz / FREQ_RES), trueD2Bin);
  // The real product sits ≈ −87 dBFS; the wrong bin reads the −210 dBFS floor.
  const trueD2Db = auto.amplitudeDbFs[trueD2Bin];
  assert.ok(imdAuto.dnLDbV[2] > trueD2Db - 1.0,
    `auto d₂L ${imdAuto.dnLDbV[2].toFixed(2)} dB should read the real product ${trueD2Db.toFixed(2)} dBFS`);
  assert.ok(imdHinted.dnLDbV[2] < imdAuto.dnLDbV[2] - 60,
    `hinted d₂L ${imdHinted.dnLDbV[2].toFixed(2)} dB should have collapsed far below `
    + `${imdAuto.dnLDbV[2].toFixed(2)} dB`);
  assert.ok(imdHinted.imdPwrPct < imdAuto.imdPwrPct / 100,
    'the combined IMD power understates by orders of magnitude');
});

test('with NO generator to ask, both hints are withheld - never re-derived here', () => {
  // Java's rule: an analyzer with no generator to ask has no emitted tone to report, not a pair
  // it may compute from the same preferences (GENERATOR_EMITTED_HZ answering null).
  const posted = dispatched({
    running: true, fundFromGenerator: true, form: GenSignalForm.DUAL_TONE,
    t1: HI_CMD, t2: LO_CMD, answer: false,
  });
  assert.ok(Number.isNaN(posted.expectedFundHz), 'no responder ⇒ no fundamental hint');
  assert.ok(Number.isNaN(posted.secondToneHintHz), 'no responder ⇒ no tone-2 hint');
});

test('the gate removes the mis-measurement: an idle generator installs no tone-2 hint', () => {
  const posted = dispatched({
    running: false, fundFromGenerator: true, form: GenSignalForm.DUAL_TONE,
    t1: 5000.0, t2: EXT_NOM2,
  });
  const r = analyzeWithHints(EXT_WINDOW, posted.expectedFundHz, posted.secondToneHintHz);
  assert.ok(Math.abs((r.fundamental2HzRefined - EXT_F2) / FREQ_RES) < 0.01,
    'the external tone 2 is measured where it actually is');
  const imd = analyzeImd(r, 5000.0, EXT_NOM2, 0);
  assert.strictEqual(Math.round(imd.diffHz / FREQ_RES), Math.round((EXT_F2 - EXT_F1) / FREQ_RES));
});

// ════════════════════════════════════════════════════════════════════════════════
// 3) gap 2, behavioural - the anchor must not depend on which box the tones went in
// ════════════════════════════════════════════════════════════════════════════════
const G2_LO = onBin(1100.0, 0.41);
const G2_HI = onBin(7000.0, 0.23);
const G2_WINDOW = windowOf([
  { hz: G2_LO, amp: 0.40, phase: 0.7 },
  { hz: G2_HI, amp: 0.40, phase: 2.1 },
  { hz: G2_HI - G2_LO, amp: 0.40 * Math.pow(10, -80 / 20), phase: 0.5 },
  { hz: G2_HI + G2_LO, amp: 0.40 * Math.pow(10, -80 / 20), phase: 1.5 },
  { hz: 2 * G2_LO, amp: 0.40 * Math.pow(10, -90 / 20), phase: 0.9 },
  { hz: 2 * G2_HI, amp: 0.40 * Math.pow(10, -90 / 20), phase: 1.9 },
]);
const K_LO = Math.round(bins(G2_LO));
const K_HI = Math.round(bins(G2_HI));

test('hinting tone 1 anchors the analysis on the WRONG tone when F1 > F2', () => {
  const onToneOne = analyzeWithHints(G2_WINDOW, G2_HI, G2_LO);     // the pre-fix pair
  const onLower = analyzeWithHints(G2_WINDOW, G2_LO, G2_HI);       // Java's min / max pair
  assert.strictEqual(onToneOne.fundamentalBin, K_HI,
    'the ±10-bin search latches the UPPER tone when tone 1 is hinted');
  assert.strictEqual(onLower.fundamentalBin, K_LO, 'min(f1,f2) anchors the LOWER tone');
  // The harmonic grid walks a completely different series ...
  assert.ok(Math.abs(onToneOne.harmonicHz[0] - 2 * G2_HI) < 2 * FREQ_RES);
  assert.ok(Math.abs(onLower.harmonicHz[0] - 2 * G2_LO) < 2 * FREQ_RES);
  // ... and the fundamental-referenced residual figures move with it (the excluded skirt +
  // harmonic zones differ), so the same signal reads a different SINAD and a different N+D.
  // The two now measure different things: SINAD (and its reciprocal THD+N) divides by the
  // PLAIN in-band noise-plus-distortion power, while N+D carries the A-curve - and the
  // curve widens the gap here, because the two anchors exclude zones at 1.1 kHz and 7 kHz
  // where the weighting differs.
  assert.ok(Math.abs(onToneOne.sinadDb - onLower.sinadDb) > 0.5,
    `unweighted SINAD must differ materially (${onToneOne.sinadDb.toFixed(3)} vs ${onLower.sinadDb.toFixed(3)} dB)`);
  assert.ok(Math.abs(onToneOne.awThdNDb - onLower.awThdNDb) > 1.0,
    `A-weighted N+D must differ materially (${onToneOne.awThdNDb.toFixed(3)} vs ${onLower.awThdNDb.toFixed(3)} dB)`);
});

test('with min(f1,f2) the measurement is identical for both entry orders', () => {
  const hiFirst = dispatched({ running: true, fundFromGenerator: true, form: GenSignalForm.DUAL_TONE, t1: G2_HI, t2: G2_LO });
  const loFirst = dispatched({ running: true, fundFromGenerator: true, form: GenSignalForm.DUAL_TONE, t1: G2_LO, t2: G2_HI });
  const a = analyzeWithHints(G2_WINDOW, hiFirst.expectedFundHz, hiFirst.secondToneHintHz);
  const b = analyzeWithHints(G2_WINDOW, loFirst.expectedFundHz, loFirst.secondToneHintHz);
  assert.strictEqual(a.fundamentalBin, K_LO, 'the anchor is the lower tone either way');
  assert.strictEqual(a.fundamentalBin, b.fundamentalBin);
  assert.strictEqual(a.fundamentalHzRefined, b.fundamentalHzRefined);
  assert.strictEqual(a.fundamental2HzRefined, b.fundamental2HzRefined);
  assert.strictEqual(a.harmonicHz[0], b.harmonicHz[0]);
  assert.strictEqual(a.thdPct, b.thdPct);
  assert.strictEqual(a.sinadDb, b.sinadDb);
  // Both tones are still measured at their true frequencies (the pair is only re-labelled).
  assert.ok(Math.abs(a.fundamentalHzRefined - G2_LO) < 1e-3);
  assert.ok(Math.abs(a.fundamental2HzRefined - G2_HI) < 1e-3);
});
