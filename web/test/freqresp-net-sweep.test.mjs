/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * The FreqResp sweep against a BENCH - the consumer half of the in-band sweep mark
 * (doc/NET-PROTOCOL.md §4.5/§5, faithful port of gui/sound/SweepCapture):
 *
 *  1. the chirp is COMMANDED, never sent - there is no client->server audio (spec 7), so the
 *     bench renders it from gen.config{sweep} and the pre-rendered buffer never leaves;
 *  2. the record begins at the bench's own sample 0, taken from the MARKER - and a stale mark
 *     from an earlier sweep on the same stream is not it;
 *  3. a bench that never marks FAILS the measurement instead of hanging it (MARK_TIMEOUT_MS);
 *  4. the local / lane-player paths are untouched by any of it.
 *
 * The two prototype methods are exercised off the prototype with a minimal `this` (as
 * backend-capture-dispatch.test.mjs does): they need a reader and a chunk list, and constructing
 * a whole engine or a controller would drag in jQuery, bootstrap and the browser audio graph.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

globalThis.window = globalThis.window || { addEventListener: () => {} };
globalThis.performance = globalThis.performance || { now: () => Date.now() };
globalThis.AudioContext = globalThis.AudioContext || function () {};   // never instantiated here

const { AudioEngine } = await import('../js/shell/audio-engine.js');
const { FreqRespController } = await import('../js/freqresp/freqresp-controller.js');
const { GeneratorController } = await import('../js/generator/generator-controller.js');
const { GenSignalForm } = await import('../js/enums/gen-signal-form.js');
const { computeFromLogSweep, binAlignedFreqs } = await import('../js/freqresp/deconvolve.js');
const { renderLogSweep } = await import('../js/freqresp/farina-sweep.js');

// ===========================================================================
// 1 - awaiting the bench's mark (SweepCapture.awaitSweepMark)
// ===========================================================================

/** The measurement ring's cursor, reduced to what the mark wait consults. */
class FakeReader {
  constructor({ writePos = 1000, mark = -1 } = {}) {
    this.writePos = writePos;
    this.mark = mark;
    this.finished = null;
  }
  getWritePos() { return this.writePos; }
  getSweepMarkPos() { return this.mark; }
  isFinished() { return this.finished != null; }
  getFinishedReason() { return this.finished; }
}

/** A controller reduced to the cancel flag the wait polls. */
function waiter() {
  return Object.assign(Object.create(FreqRespController.prototype), { _cancelled: false });
}

/** Runs {@code change} once the wait has had time to poll. UNREF'd like every timer in this
 *  tree: a pending one holds the Node test process open long after its test has passed. */
function afterAPoll(change) {
  const timer = setTimeout(change, 3 * FreqRespController.MARK_POLL_MS);
  if (timer && typeof timer.unref === 'function') timer.unref();
  return timer;
}

test('the mark that arrives AFTER the arm is the sweep\'s; the one before it is stale', async () => {
  const controller = waiter();
  // A mark left on this stream by an EARLIER sweep. Taking it would start the record before the
  // chirp that is playing now - a whole measurement deconvolved at the wrong offset.
  const reader = new FakeReader({ writePos: 1000, mark: 500 });

  const waiting = controller._awaitSweepMark(reader, 1000);
  afterAPoll(() => { reader.mark = 1234; });

  assert.equal(await waiting, 1234, 'the bench\'s sample 0, in ring coordinates');
});

test('a cancel mid-wait returns -1 - the measurement ends, it does not fail', async () => {
  const controller = waiter();
  const reader = new FakeReader();

  const waiting = controller._awaitSweepMark(reader, 1000);
  afterAPoll(() => { controller._cancelled = true; });

  assert.equal(await waiting, -1);
});

test('a capture that ends before the mark fails the measurement, naming the reason', async () => {
  const controller = waiter();
  const reader = new FakeReader();
  reader.finished = { name: 'DEVICE_LOST', logText: 'the input device was lost' };

  await assert.rejects(() => controller._awaitSweepMark(reader, 1000),
    /ended before the sweep was marked - the input device was lost/);
});

test('a silent bench FAILS the measurement at the timeout - it never hangs it', async () => {
  const controller = waiter();
  const reader = new FakeReader();
  const realTimeout = FreqRespController.MARK_TIMEOUT_MS;
  FreqRespController.MARK_TIMEOUT_MS = 3 * FreqRespController.MARK_POLL_MS;
  try {
    await assert.rejects(() => controller._awaitSweepMark(reader, 1000),
      /never marked its sweep start/);
  } finally {
    FreqRespController.MARK_TIMEOUT_MS = realTimeout;
  }
});

// ===========================================================================
// 2 - seeking the recording to that mark (SweepCapture's reader.seek(mark))
// ===========================================================================

const f64 = (...v) => Float64Array.from(v);

/** The recording tap alone: the chunk lists, the ring position their first frame sits at, and
 *  the cursor the mark is read off. */
function tap({ basePos = 1000 } = {}) {
  return Object.assign(Object.create(AudioEngine.prototype), {
    _recChunks: { l: [f64(1, 2, 3, 4), f64(5, 6, 7, 8)], r: [f64(11, 12, 13, 14), f64(15, 16, 17, 18)] },
    _recBasePos: basePos,
    _recReader: new FakeReader({ writePos: basePos + 8 }),
    _recOwnsCapture: false,
  });
}

test('the record is cut to start AT the mark - whole chunks and the one that straddles it', async () => {
  const engine = tap();

  const dropped = engine.dropRecordingBefore(1005);

  assert.equal(dropped, 5, 'five frames sat between the tap\'s first sample and the bench\'s 0');
  assert.equal(engine.recordingLength(), 3);
  const rec = await engine.stopCaptureRecording();
  assert.deepEqual([...rec.left], [6, 7, 8], 'the record now begins at the sweep\'s sample 0');
  assert.deepEqual([...rec.right], [16, 17, 18], 'both ADC channels are cut identically');
});

test('the cut is relative to the ring, and a mark at (or behind) the front cuts nothing', () => {
  const engine = tap();

  assert.equal(engine.dropRecordingBefore(1000), 0, 'the tap already starts there');
  assert.equal(engine.dropRecordingBefore(500), 0, 'a position behind the front is not a cut');
  assert.equal(engine.recordingLength(), 8);

  // The base follows every cut, so a second one is measured from where the record now starts.
  engine.dropRecordingBefore(1002);
  engine.dropRecordingBefore(1003);
  assert.equal(engine.recordingLength(), 5);
});

test('re-anchoring reads the ring: the tap\'s next frame is the ring\'s write position', () => {
  const engine = tap({ basePos: 1000 });
  engine._recReader.writePos = 7777;

  engine.resetCaptureRecording();

  assert.equal(engine._recBasePos, 7777);
  assert.equal(engine.recordingLength(), 0, 'the warmup silence is discarded with it');
});

// ===========================================================================
// 3 - the sweep is COMMANDED to a bench, not played at it
// ===========================================================================

/** A bench's generator lane: it reports what the FAR END emits and cannot be handed samples. */
class FakeBenchLane {
  constructor() {
    this.opened = null; this.started = null; this.closed = 0; this.sampleRate = 0;
    this.port = { postMessage: () => {} };
  }
  emittedHz() { return [0, 0]; }
  async open(spec) { this.opened = spec; this.sampleRate = 44100; return this.sampleRate; }
  async start(spec) { this.started = spec; }
  async close() { this.closed++; }
}

/** A lane that DOES play pre-rendered buffers (the QA40x's) - the path that must not change. */
class FakeBufferLane {
  constructor() { this.opened = null; this.played = null; this.closed = 0; this.sampleRate = 0; }
  async open(spec) { this.opened = spec; this.sampleRate = spec.sampleRate; }
  async playBuffer(buf, opts) { this.played = { buf, opts }; }
  async close() { this.closed++; }
}

const { testPrefs } = await import('./_test-prefs.mjs');
const PREFS = () => testPrefs({ outputDeviceName: 'dac-1', outputSampleRate: 48000, inputSampleRate: 48000,
  genDitherBits: 0, genSignalForm: GenSignalForm.SINE, genFrequencyHz: 1000, fftLength: 32768,
  genSnapToFftBin: true, genDualToneFreq2Hz: 1100, genAmplitudeVrms: 0.5, dacFsVoltageAmpl: Math.SQRT2,
  genDualToneSplitPct: 50, genSweepFreqStartHz: 20, genSweepFreqEndHz: 20000, genSweepDurationSec: 1,
  genSweepFadeInSec: 0.01, genSweepFadeOutSec: 0.01, genSweepLoop: true, genOutputChannels: 'BOTH' });

const SWEEP = { f0: 20, f1: 20000, sweepSamples: 480000, leadInSamples: 2400,
  fadeSamples: 12000, amplitudeVRms: 0.5 };

test('a bench sweep: the lane opens at the GRANTED rate with the gate fixed at gen.open', async () => {
  const lane = new FakeBenchLane();
  const gen = new GeneratorController(PREFS(),{ status: () => {}, openPlayback: () => lane });

  const granted = await gen.openSweepContext(48000, { outputChannels: 'LEFT' });

  assert.equal(granted, 44100, 'the bench\'s DAC has the last word - the caller refuses a mismatch');
  assert.equal(lane.opened.sampleRate, 48000);
  assert.equal(lane.opened.outputChannels, 'LEFT', 'spec 4.5 fixes the lane gate at gen.open');
});

test('the chirp is DESCRIBED to the bench - the samples never leave, the card stays the bench\'s', async () => {
  const lane = new FakeBenchLane();
  const gen = new GeneratorController(PREFS(),{ status: () => {}, openPlayback: () => lane });
  await gen.openSweepContext(48000, { outputChannels: 'BOTH' });

  const rate = await gen.playSweepBuffer(undefined, 48000, { outputChannels: 'BOTH', sweep: SWEEP });

  assert.equal(rate, 44100);
  assert.equal(lane.started.form, GenSignalForm.LOG_SWEEP);
  assert.equal(lane.started.pushCalibration, false,
    'the DAC full scale belongs where the DAC is - a client\'s own would mis-scale the sweep');
  assert.equal(lane.started.amplitudeVRms, 0.5);
  const { logSweep, sweepParams } = lane.started.control;
  assert.deepEqual(logSweep, { f0: 20, f1: 20000, sweepSamples: 480000, leadInSamples: 2400 },
    'samples at the asked rate - a duration converted at the wrong clock is the wrong sweep');
  assert.equal(sweepParams.loop, false, 'a measurement plays its chirp once');
  assert.equal(sweepParams.fadeInSamples, 12000);
  assert.equal(sweepParams.fadeOutSamples, 12000);
  assert.equal(lane.started.control.fftSize, undefined, 'a sweep has no bin snap to push');

  assert.equal(gen.filePlaying, true, 'the bench is emitting, so the lane is held');
  await gen.stopFile();
  assert.equal(lane.closed, 1, 'and every measurement exit gives the DAC back');
});

test('a lane that plays buffers is untouched: it still gets the pre-rendered sweep', async () => {
  const lane = new FakeBufferLane();
  const gen = new GeneratorController(PREFS(),{ status: () => {}, openPlayback: () => lane });

  const granted = await gen.openSweepContext(48000, { outputChannels: 'BOTH' });
  await gen.playSweepBuffer(Float64Array.from([0.1, -0.2, 0.3]), 48000, { outputChannels: 'BOTH' });

  assert.equal(granted, 48000, 'one clock: the lane grants the rate it is already running at');
  assert.ok(lane.played.buf instanceof Float32Array, 'the samples themselves, as before');
  assert.equal(lane.played.opts.loop, false);
});

// ===========================================================================
// 4 - B14: the deconvolution runs OFF the browser's main thread
// ===========================================================================
//
// A measurement that deconvolves on the main thread stops answering the bench's 500 ms keepalive
// pings for as long as it runs - seconds at the shipped FFT size - and the SERVER then closes the
// session as dead after four of them (spec 4.1). What a node test can pin is the seam: that the
// job really is handed to a worker where there is one, and that the fallback the tests themselves
// run through is the SAME math, so nothing here is proving a code path the browser never takes.

/** A small but real deconvolution job - the arguments captureAndDeconvolve builds. */
function deconvolveJob() {
  const sampleRate = 48000, f0 = 20, f1 = 20000;
  const sweepSamples = 8192, leadInSamples = 512, fade = 256;
  const sweepRef = renderLogSweep(f0, f1, sweepSamples, sampleRate);
  const recLeft = new Float64Array(leadInSamples + sweepSamples);
  const recRight = new Float64Array(leadInSamples + sweepSamples);
  for (let i = 0; i < sweepSamples; i++) {
    recLeft[leadInSamples + i] = sweepRef[i] * 0.5;
    recRight[leadInSamples + i] = sweepRef[i] * 0.25;
  }
  const freqs = binAlignedFreqs(f0, f1, sampleRate / 16384, 64);
  return { recLeft, recRight, sweepRef, leadInSamples, sampleRate, freqs,
    amplitudeVRms: 1, adcFsVoltageRms: 1, fade, applySavGol: false };
}

test('B14: with no Worker the seam computes exactly what computeFromLogSweep does', async () => {
  const controller = Object.create(FreqRespController.prototype);
  const job = deconvolveJob();
  const expected = computeFromLogSweep(job.recLeft, job.sweepRef, job.leadInSamples, job.sampleRate,
    job.freqs, job.amplitudeVRms, job.adcFsVoltageRms, job.fade, false);

  const { calL, calR } = await controller._deconvolveBoth(job);

  assert.deepEqual([...calL.magLin], [...expected.magLin],
    'the node fallback IS the browser worker\'s math - the suite would otherwise cover code the '
    + 'app no longer runs');
  assert.deepEqual([...calL.phaseRad], [...expected.phaseRad]);
  assert.equal(calR.magLin.length, calL.magLin.length);
  assert.ok(calR.magLin[10] < calL.magLin[10], 'and the two channels are told apart');
});

test('B14: where there is a Worker the job goes to it, transferred, and it is not kept', async () => {
  const posted = [];
  let terminated = 0, url = null;
  globalThis.Worker = class {
    constructor(u) { url = String(u); this.onmessage = null; this.onerror = null; }
    postMessage(job, transfer) {
      posted.push({ job, transfer });
      // What a real worker answers, on a later turn of the event loop.
      setTimeout(() => this.onmessage({ data: { calL: 'L', calR: 'R' } }), 0);
    }
    terminate() { terminated++; }
  };
  try {
    const controller = Object.create(FreqRespController.prototype);
    const job = deconvolveJob();

    const result = await controller._deconvolveBoth(job);

    assert.deepEqual(result, { calL: 'L', calR: 'R' });
    assert.ok(url.endsWith('/js/freqresp/deconvolve-worker.js'), url);
    assert.equal(posted.length, 1);
    assert.deepEqual(posted[0].transfer,
      [job.recLeft.buffer, job.recRight.buffer, job.sweepRef.buffer],
      'the capture channels are tens of megabytes at the shipped FFT size - they move, never copy');
    assert.equal(terminated, 1, 'one worker per measurement, and it goes with the measurement');
  } finally {
    delete globalThis.Worker;
  }
});

test('B14: a worker that dies without an error event fails the measurement instead of hanging it',
  async () => {
    // The browser kills a worker for memory without raising an ErrorEvent - realistic at the top
    // FFT sizes, where the scratch arrays alone run to hundreds of megabytes. A promise left
    // pending there would strand the measurement: busy modal up, Run disabled, and the finally
    // that publishes FREQRESP_MEASUREMENT_STOPPED never reached.
    let terminated = 0;
    globalThis.Worker = class {
      constructor() { this.onmessage = null; this.onerror = null; this.onmessageerror = null; }
      postMessage() { /* the worker is already gone: nothing ever answers */ }
      terminate() { terminated++; }
    };
    const realTimeout = FreqRespController.DECONVOLVE_TIMEOUT_MS;
    FreqRespController.DECONVOLVE_TIMEOUT_MS = 60;
    try {
      const controller = Object.create(FreqRespController.prototype);

      await assert.rejects(() => controller._deconvolveBoth(deconvolveJob()),
        /did not finish within 0.06 s - the worker is gone/);
      assert.equal(terminated, 1, 'and the dead worker is not left behind');
    } finally {
      FreqRespController.DECONVOLVE_TIMEOUT_MS = realTimeout;
      delete globalThis.Worker;
    }
  });

test('B14: a result that cannot be delivered is a failure, not a stall', async () => {
  let terminated = 0;
  globalThis.Worker = class {
    constructor() { this.onmessage = null; this.onerror = null; this.onmessageerror = null; }
    postMessage() { setTimeout(() => this.onmessageerror({}), 0); }
    terminate() { terminated++; }
  };
  try {
    const controller = Object.create(FreqRespController.prototype);

    await assert.rejects(() => controller._deconvolveBoth(deconvolveJob()),
      /result could not be delivered/);
    assert.equal(terminated, 1);
  } finally {
    delete globalThis.Worker;
  }
});

test('B14: the measurement itself takes that seam - the deconvolution is not inline any more',
  async () => {
    // A node test cannot run a browser measurement (device, jQuery, Bootstrap), and this is the
    // regression the whole item is about: the two computeFromLogSweep calls that used to sit in
    // captureAndDeconvolve blocked the thread the socket is answered on.
    const source = await readFile(new URL('../js/freqresp/freqresp-controller.js', import.meta.url), 'utf8');
    const body = source.slice(source.indexOf('async captureAndDeconvolve('),
      source.indexOf('_deconvolveBoth(job) {'));
    assert.ok(body.includes('await this._deconvolveBoth({'),
      'the measurement hands the job to the worker seam');
    assert.ok(!body.includes('computeFromLogSweep('),
      'and calls the deconvolution nowhere on the main thread itself');
  });
