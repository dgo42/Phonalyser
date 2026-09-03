/*
 * Phonalyser web - the pooled fork tracking estimate and the retroactive
 * discontinuity drop.
 *
 * Vocabulary (defined once): a TICK is one analysis cycle of the FFT consumer (a
 * few per second). KAPPA is a tone's FRACTIONAL bin position. The FORK is the
 * per-tick tracking correction, in samples, pooled from every admitted line of
 * the coherent family - the harmonic ladder plus, for a dual pair, the published
 * intermodulation grid. The SUBTRACTABLE RING holds the last accepted tick's
 * contribution exactly as it entered the accumulator, so a discontinuity gate can
 * take it back out.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

import { FftAccumulator } from '../js/fft/fft-accumulator.js';
import { FftController } from '../js/fft/fft-controller.js';
import { testPrefs } from './_test-prefs.mjs';
import { ieeeRemainder } from '../js/dsp/mathutil.js';

const FFT = 64;
const HALF = FFT / 2;
const N = HALF + 1;
const KAPPA = 4.0;                    // the fundamental sits on bin 4
const REL_DB = 100.0;

/** An accumulator with the pinned state the fork reads, and a deep reference whose
 *  phase is 0 at every bin - so a fresh bin's own phase IS its residual. */
function forkFixture({ kappa2 = NaN } = {}) {
  const acc = new FftAccumulator();
  acc._accumFftSize = FFT;
  acc._accumCoherent = true;
  acc._accumHasData = true;
  acc._accumKFractional = KAPPA;
  acc._accumIntFundBinRounded = 4;
  acc._accumKappa2 = kappa2;
  acc._accumRe = Float64Array.from({ length: N }, () => 1.0);
  acc._accumIm = new Float64Array(N);
  acc.setStrongToneRelDb(REL_DB);
  return acc;
}

/** A fresh half-spectrum with the named bins set to (magnitude, phase). */
function freshLines(lines) {
  const re = new Float64Array(N);
  const im = new Float64Array(N);
  for (const [k, mag, phase] of lines) {
    re[k] = mag * Math.cos(phase);
    im[k] = mag * Math.sin(phase);
  }
  return { fftSize: FFT, re, im, imdProductA: null, imdProductB: null };
}

/** The offset in samples a line at fractional bin kappaL reports for residual res. */
const offsetOf = (res, kappaL) => res * FFT / (2.0 * Math.PI * kappaL);

// ── The fork ────────────────────────────────────────────────────────────────

test('with nothing else admitted the fork IS the fundamental estimate, unchanged', () => {
  const acc = forkFixture();
  const r = freshLines([[4, 1.0, 0.2]]);          // a lone fundamental
  const fundOffset = offsetOf(0.2, KAPPA);
  assert.equal(acc._forkPooledOffset(r, HALF, 0, 4, fundOffset), fundOffset);
});

test('the pool averages with (fractional bin x magnitude) squared weights', () => {
  // A common sample offset rotates each line proportionally to its frequency, so
  // each line's residual divided by its own fractional bin estimates the SAME
  // offset. The weights are variance-optimal: phase noise is floor over magnitude
  // while the offset sensitivity grows with frequency.
  const acc = forkFixture();
  const M1 = 1.0, P1 = 0.2;                       // fundamental at bin 4
  const M2 = 0.5, P2 = -0.1;                      // second harmonic at bin 8
  const r = freshLines([[4, M1, P1], [8, M2, P2]]);
  const fundOffset = offsetOf(P1, KAPPA);
  const w1 = KAPPA * KAPPA * M1 * M1;
  const w2 = (2 * KAPPA) * (2 * KAPPA) * M2 * M2;
  const want = (w1 * fundOffset + w2 * offsetOf(P2, 2 * KAPPA)) / (w1 + w2);
  const got = acc._forkPooledOffset(r, HALF, 0, 4, fundOffset);
  assert.ok(Math.abs(got - want) < 1e-12, `pooled ${got}, expected ${want}`);
  // ...and pooling actually moved the estimate off the fundamental's own value.
  assert.ok(Math.abs(got - fundOffset) > 1e-6, 'the harmonic pulled the estimate');
});

test('a line below the admission threshold does not join', () => {
  const acc = forkFixture();
  const fundOffset = offsetOf(0.2, KAPPA);
  // The harmonic is 120 dB below the fundamental in POWER terms, so at a 100 dB
  // threshold it is out; at 130 dB it is in.
  const weak = Math.pow(10.0, -120.0 / 20.0);
  const r = freshLines([[4, 1.0, 0.2], [8, weak, -0.1]]);
  acc.setStrongToneRelDb(100.0);
  assert.equal(acc._forkPooledOffset(r, HALF, 0, 4, fundOffset), fundOffset,
    'below the threshold the pool is the fundamental alone');
  acc.setStrongToneRelDb(130.0);
  assert.ok(Math.abs(acc._forkPooledOffset(r, HALF, 0, 4, fundOffset) - fundOffset) > 0,
    'a wider threshold admits it');
});

test('the dual pair F2 - the (0,1) grid member - joins regardless of level', () => {
  // Behind a notch a partner can be tens of dB down; F2 is a fundamental, not a
  // distortion product, so it is exempt from the admission level.
  const acc = forkFixture({ kappa2: 20.0 });
  const fundOffset = offsetOf(0.2, KAPPA);
  const weak = Math.pow(10.0, -120.0 / 20.0);
  const r = freshLines([[4, 1.0, 0.2], [20, weak, 0.3]]);
  r.imdProductA = Int32Array.of(0);
  r.imdProductB = Int32Array.of(1);
  const withF2 = acc._forkPooledOffset(r, HALF, 0, 4, fundOffset);
  const w1 = KAPPA * KAPPA;
  const w2 = 20.0 * 20.0 * weak * weak;
  const want = (w1 * fundOffset + w2 * offsetOf(0.3, 20.0)) / (w1 + w2);
  assert.ok(Math.abs(withF2 - want) < 1e-15, 'F2 joined with its own weight');
  // The same weak line as an ordinary product (any coefficient pair other than
  // (0,1)) is refused - only a fundamental is exempt from the admission level.
  const plain = freshLines([[4, 1.0, 0.2], [20, weak, 0.3]]);
  plain.imdProductA = Int32Array.of(-4);
  plain.imdProductB = Int32Array.of(2);  // -4*4 + 2*20 = 24, a real in-band product
  assert.equal(acc._forkPooledOffset(plain, HALF, 0, 4, fundOffset), fundOffset,
    'an ordinary product below the threshold stays out');
});

test('a line within a quarter turn of the phase wrap is dropped for the tick', () => {
  // Folded to the wrong side it would pull the pooled offset instead of refining it.
  const acc = forkFixture();
  const fundOffset = offsetOf(0.2, KAPPA);
  const justInside = freshLines([[4, 1.0, 0.2], [8, 0.5, Math.PI / 2 - 0.01]]);
  const justOutside = freshLines([[4, 1.0, 0.2], [8, 0.5, Math.PI / 2 + 0.01]]);
  assert.ok(Math.abs(acc._forkPooledOffset(justInside, HALF, 0, 4, fundOffset) - fundOffset) > 1e-6,
    'inside the guard the line joins');
  assert.equal(acc._forkPooledOffset(justOutside, HALF, 0, 4, fundOffset), fundOffset,
    'past the quarter-turn guard the line is dropped');
});

test('the re-sync one-shot realign stays on the FUNDAMENTAL alone', () => {
  // A large offset wraps the higher lines' phases, so pooling is not valid there.
  // Drive the real accumulate() path: refine kappa, then fire a re-sync.
  const mk = (phase) => ({
    fftSize: FFT, sampleRate: FFT, freqResolution: 1.0,
    fundamentalHzRefined: KAPPA, fundamental2HzRefined: 0, frameCount: 1,
    windowType: 'BH7', imdProductA: null, imdProductB: null,
    re: Float64Array.from({ length: N }, (_, k) => (k === 4 ? Math.cos(phase) : 0.0)),
    im: Float64Array.from({ length: N }, (_, k) => (k === 4 ? Math.sin(phase) : 0.0)),
  });
  const acc = new FftAccumulator();
  for (let t = 0; t < 30; t++) acc.accumulate(mk(0), t * FFT, true, Infinity);
  assert.ok(acc._kappaRefined, 'the one-shot kappa refine has landed');
  const before = acc._accumDroppedSamples;
  acc.onResync();
  acc.accumulate(mk(0.3), 30 * FFT, true, Infinity);
  const moved = acc._accumDroppedSamples - before;
  // The whole fundamental residual is folded (gain 1), not PHASE_TRACK_GAIN of it.
  assert.ok(Math.abs(moved) > 0, 'the one-shot realign moved the de-rotation');
  assert.equal(acc._gapRecoverPending, false, 'the one-shot is consumed');
});

// ── The retroactive discontinuity drop ──────────────────────────────────────

/** A per-window result whose bins are all 1 + 0j (coherent) or all `mag` (power). */
function tickResult({ coherentBins = true, mag = 1.0, weight = 1 } = {}) {
  return {
    fftSize: FFT, sampleRate: FFT, freqResolution: 1.0,
    fundamentalHzRefined: KAPPA, fundamental2HzRefined: 0, frameCount: weight,
    windowType: 'BH7', imdProductA: null, imdProductB: null,
    re: Float64Array.from({ length: N }, () => (coherentBins ? 1.0 : mag)),
    im: new Float64Array(N),
  };
}

test('the coherent path stores its tick exactly as added and gives it back', () => {
  const acc = new FftAccumulator();
  acc.accumulate(tickResult(), 0, true, Infinity);
  const afterOne = { re: acc._accumRe.slice(), im: acc._accumIm.slice(), n: acc.accumFrames };
  acc.accumulate(tickResult(), 7, true, Infinity);          // a rotated second tick
  assert.notEqual(acc.accumFrames, afterOne.n);
  acc.subtractPreviousTicks();
  for (let k = 0; k < N; k++) {
    assert.ok(Math.abs(acc._accumRe[k] - afterOne.re[k]) < 1e-15
      && Math.abs(acc._accumIm[k] - afterOne.im[k]) < 1e-15,
    `bin ${k} restored to its pre-tick value`);
  }
  assert.equal(acc.accumFrames, afterOne.n, 'the depth counter gives the weight back');
});

test('the INCOHERENT power path is subtractable too, exactly as the coherent one', () => {
  const acc = new FftAccumulator();
  acc.accumulate(tickResult({ coherentBins: false, mag: 2.0 }), 0, false, Infinity);
  const afterOne = { pow: acc._accumPow.slice(), n: acc.accumFrames };
  acc.accumulate(tickResult({ coherentBins: false, mag: 3.0, weight: 2 }), 7, false, Infinity);
  acc.subtractPreviousTicks();
  for (let k = 0; k < N; k++) {
    assert.ok(Math.abs(acc._accumPow[k] - afterOne.pow[k]) < 1e-15, `power bin ${k} restored`);
  }
  assert.equal(acc.accumFrames, afterOne.n, 'the weight 2 tick gave back 2 frames');
});

test('a bounded window scales the ring with the sum, so a subtraction cannot over-remove', () => {
  // targetN turns the running sum into an exponential window; the held contribution
  // must scale with it or the later subtraction removes more than was added.
  const acc = new FftAccumulator();
  const TARGET = 3;
  acc.accumulate(tickResult({ weight: 2 }), 0, true, TARGET);
  acc.accumulate(tickResult({ weight: 2 }), 7, true, TARGET);   // this one scales the sum
  const afterTwo = { re: acc._accumRe.slice(), im: acc._accumIm.slice(), n: acc.accumFrames };
  acc.accumulate(tickResult({ weight: 2 }), 14, true, TARGET);  // scales again, then adds
  acc.subtractPreviousTicks();
  // The third tick is gone; what remains is the scaled-down second-tick state, so
  // every bin must sit at or below where it was, never overshoot past zero.
  for (let k = 0; k < N; k++) {
    const scaledDown = Math.hypot(acc._accumRe[k], acc._accumIm[k])
      <= Math.hypot(afterTwo.re[k], afterTwo.im[k]) + 1e-12;
    assert.ok(scaledDown, `bin ${k} did not grow through the subtraction`);
  }
  assert.ok(acc.accumFrames >= 0, 'the depth never goes negative');
});

test('subtracting twice is a no-op, and a reset clears the ring', () => {
  const acc = new FftAccumulator();
  acc.accumulate(tickResult(), 0, true, Infinity);
  acc.accumulate(tickResult(), 7, true, Infinity);
  acc.subtractPreviousTicks();
  const held = { re: acc._accumRe.slice(), n: acc.accumFrames };
  acc.subtractPreviousTicks();
  for (let k = 0; k < N; k++) assert.equal(acc._accumRe[k], held.re[k]);
  assert.equal(acc.accumFrames, held.n);
  acc.reset();
  assert.equal(acc._prevTickCount, 0, 'a reset clears the ring');
});

// ── The controller's gate wiring ────────────────────────────────────────────

/** A controller with just the state _onWorkerResult reads. The fold itself runs in the analyzer
 *  worker now; what is asked here is the RECOVERY this side owns - the capture cursor, the
 *  epoch and the post-glitch drain, none of which exist in the worker. */
function recoveringController() {
  const prefs = testPrefs({
    inputSampleRate: 48000, fftLength: FFT, fftOverlap: 'PCT_0', fftAverages: Infinity,
    fftCoherentAveraging: true, fftThreads: 1, fftChannel: 'R', fftMainsSuppression: 'NONE',
    fftDetectTimeDiscontinuity: true, fftStrongToneRelDb: 100,
  });
  const gen = { snapped: 1000, binW: 1, running: false, computeAnalysisFreqs() {} };
  const c = new FftController({}, gen, prefs, {});
  c._fftOn = true;
  c._accumEpoch = 0;
  c._tickEpoch = 0;
  c.foreverMode = true;
  c.ringN = 1;
  c._lastAccumulate = true;
  c._lastForeverMode = true;
  c._lastRingN = 1;
  c._workerResyncPending = false;
  c._workerResetPending = false;
  return c;
}

/** A worker-shaped result the fold can consume - one tone on bin KAPPA. */
function workerResult(fields = {}) {
  const re = new Float64Array(N);
  re[KAPPA] = 1.0;
  return {
    ms: 1, fftSize: FFT, sampleRate: FFT, freqResolution: 1.0,
    frameCount: 1, coherentAveraging: true, windowType: 'BH7',
    fundamentalHzRefined: KAPPA, fundamental2HzRefined: NaN,
    re, im: new Float64Array(N),
    amplitudeDbFs: new Float64Array(N), phaseDeg: new Float64Array(N),
    imdProductA: null, imdProductB: null,
    ...fields,
  };
}

test('a gated tick takes the previous one back out of the average', async () => {
  // The worker module installs its message handler on `self` as it loads - a worker global a
  // plain node run does not have. Only the fold is asked here, through its own export.
  globalThis.self = globalThis.self || { postMessage() {}, onmessage: null };
  const { foldTick, primaryAccum } = await import('../js/fft/fft-worker.js');
  const d = {
    accumulate: true, targetN: Infinity, timeGate: true, spectralGate: false,
    strongToneRelDb: REL_DB, winAbsStart: 0, resetAccum: true, resyncAccum: false,
  };
  const accepted = foldTick(d, workerResult(), false);
  assert.equal(accepted.gate, null, 'a clean tick passes both gates');
  const depth = primaryAccum.accumFrames;
  assert.ok(depth > 0, 'and enters the average');

  const gated = foldTick({ ...d, resetAccum: false }, workerResult(), true);
  assert.equal(gated.gate, 'time', 'the time-domain gate names itself to the controller');
  assert.equal(gated.accumulated, false, 'the detecting tick never enters');
  assert.ok(primaryAccum.accumFrames < depth,
    'and the tick before it - whose window shared nearly all these samples - is taken back out');
});

test('BOTH gate verdicts run the controller-side re-sync recovery', () => {
  const timeGate = recoveringController();
  timeGate._onWorkerResult(workerResult({ gate: 'time', accumulated: false, accumFrames: 0 }));
  assert.equal(timeGate._workerResyncPending, true, 'time gate: the average is re-anchored');
  assert.equal(timeGate._accumEpoch, 1, 'the window straddling the glitch cannot fold in');
  assert.ok(timeGate._drainSkipRemaining > 0, 'and the rebuild starts past the glitch');

  const specGate = recoveringController();
  specGate._onWorkerResult(workerResult({
    gate: 'spectral', accumulated: false, accumFrames: 0, gateDiag: { gates: {}, score: 0 },
  }));
  assert.equal(specGate._workerResyncPending, true, 'spectral gate: the same recovery');
  assert.equal(specGate._accumEpoch, 1, 'with the same epoch bump');
});

test('a ring overrun re-anchors without restarting the average - a coverage gap, not damage', () => {
  const c = recoveringController();
  c._fftReader = null;
  c._onOverrun();
  assert.equal(c._workerResyncPending, true, 'an overrun re-anchors');
  assert.equal(c._workerResetPending, false, 'but it keeps every accepted tick');
  assert.equal(c._drainSkipRemaining, 0, 'and discards nothing - the samples are sound');
});
