/*
 * Phonalyser web - what a MAINS-MODE switch does to a running FFT.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * The rule, from the Java worker that re-reads the mode every tick: a transition
 * that touches one of the two TIME-DOMAIN cancellers (SYNC_SUBTRACT, LMS) -
 * entering one, leaving one, or swapping between them - regathers ONLY the
 * analysis window, because the samples already gathered towards the current one
 * were filtered under the old mode. IIR_COMB is not such a transition: it leaves
 * the window raw and corrects the plot instead.
 *
 * What must NOT happen, in either case: the cross-tick accumulator and its
 * counter are untouched. The hum is an additive component the canceller removes,
 * not a change of what is measured, so the average goes on. A round that reset
 * the statistics on a mains switch threw away minutes of averaging on a click.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

import { FftController } from '../js/fft/fft-controller.js';
import { testPrefs } from './_test-prefs.mjs';

const SR = 48000;
const FFT = 1024;
const HOP = 256;

/** A controller with the analysis geometry set by hand and every collaborator
 *  stubbed - the seam under test is _syncLiveConfig, which runs per batch. */
function controllerAt(mode) {
  const prefs = testPrefs({
    inputSampleRate: SR, fftLength: FFT, fftOverlap: 'PCT_75', fftAverages: Infinity,
    fftCoherentAveraging: true, fftThreads: 1, fftChannel: 'R', fftMainsSuppression: mode,
    genSignalForm: 'SINE', fftFundFromGenerator: false, fftManualFundEnabled: false,
    fftStrongToneRelDb: 100, fftDetectTimeDiscontinuity: false,
  });
  const gen = {
    snapped: 1000, binW: SR / FFT, genNode: null, running: false,
    computeAnalysisFreqs() {}, _genEmitFreq2: () => 0,
  };
  const c = new FftController({}, gen, prefs, {});
  c.N = FFT;
  c.minHop = HOP; c.maxHop = HOP; c.pendingHop = HOP; c.perTickFrames = 2;
  c.bufLen = FFT + HOP;
  c.buf = new Float64Array(c.bufLen);
  c.bufW = 100; c.bufFilled = 100;      // part-gathered window, the thing at stake
  c.foreverMode = true; c.ringN = 1; c.avgTarget = Infinity;
  c._refill = HOP; c._winAbsStart = 7; c._dispatchedOnce = true;
  c._accumEpoch = 0;
  c.framesDone = 42;                    // the readout depth
  c._analysesDone = 42;
  c._fftReader = null;                  // _restartWindow tolerates no reader
  // The average lives in the analyzer worker; what the controller decides is which one-shot
  // rides the next dispatch - re-anchor the slope and PLL, or start the average over.
  c._workerResyncPending = false; c._workerResetPending = false;
  c._fftTimeFilter = { marker: 'old-mode-state' };
  c._fftTimeFilterMode = mode;
  c._lastMainsMode = mode;              // the latch starts where the controller starts
  return c;
}

/** The state a regather is recognised by, sampled after the call. */
const snapshot = (c) => ({
  bufFilled: c.bufFilled, winAbsStart: c._winAbsStart, epoch: c._accumEpoch,
  resyncs: c._workerResyncPending, resets: c._workerResetPending, framesDone: c.framesDone,
  analysesDone: c._analysesDone, filter: c._fftTimeFilter,
});

test('entering a time-domain canceller regathers the window and keeps the average', () => {
  const c = controllerAt('NONE');
  c._prefs.fftMainsSuppression.set('LMS');
  c._syncLiveConfig();
  const s = snapshot(c);

  assert.equal(s.bufFilled, 0, 'the part-gathered window is dropped');
  assert.equal(s.winAbsStart, 0, 'and the next one is anchored fresh');
  assert.equal(s.epoch, 1, 'the in-flight window of the old mode is dropped by the epoch bump');
  assert.equal(s.resyncs, true, 'the accumulator re-anchors its slope and PLL');
  assert.equal(s.filter, null, 'the canceller state adapted under the old mode is dropped');
  assert.equal(s.resets, false, 'the accumulator is NOT reset');
  assert.equal(s.framesDone, 42, 'the collected depth survives');
  assert.equal(s.analysesDone, 42, 'and so does the analyses counter');
});

test('swapping between the two time-domain cancellers regathers as well', () => {
  const c = controllerAt('SYNC_SUBTRACT');
  c._prefs.fftMainsSuppression.set('LMS');
  c._syncLiveConfig();
  const s = snapshot(c);
  assert.equal(s.bufFilled, 0, 'the window is regathered');
  assert.equal(s.resets, false, 'without resetting the average');
  assert.equal(s.framesDone, 42, 'or its counter');
});

test('leaving a time-domain canceller regathers too', () => {
  const c = controllerAt('LMS');
  c._prefs.fftMainsSuppression.set('NONE');
  c._syncLiveConfig();
  assert.equal(c.bufFilled, 0, 'the window gathered while filtering is dropped');
  assert.equal(c._workerResetPending, false, 'the average is kept');
});

test('an IIR_COMB switch keeps the gathered window - it corrects the plot, not the samples', () => {
  const c = controllerAt('NONE');
  c._prefs.fftMainsSuppression.set('IIR_COMB');
  c._syncLiveConfig();
  const s = snapshot(c);

  assert.equal(s.bufFilled, 100, 'the part-gathered window stands');
  assert.equal(s.winAbsStart, 7, 'and keeps its anchor');
  assert.equal(s.epoch, 0, 'nothing in flight is dropped');
  assert.equal(s.resyncs, false, 'the accumulator is not even re-anchored');
  assert.equal(s.resets, false, 'and certainly not reset');
  assert.equal(s.framesDone, 42, 'the depth is untouched');
});

test('no mode change at all leaves everything alone', () => {
  const c = controllerAt('LMS');
  c._syncLiveConfig();
  const s = snapshot(c);
  assert.equal(s.bufFilled, 100, 'the window is not regathered on a no-op sync');
  assert.equal(s.epoch, 0, 'no epoch bump');
  assert.notEqual(s.filter, null, 'and the canceller keeps its adapted state');
});
