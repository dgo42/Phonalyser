/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Regression net for the playback-sink SEAM - the twin of web-audio-capture-source.test.mjs for
 * the OUTPUT half of the same extraction: the output AudioContext + the dds-processor node were
 * lifted out of GeneratorController (which now keeps only the lifecycle), and what is pinned here
 * is exactly what that move can silently lose. Every one of these is a silent failure on real
 * hardware, never a thrown error:
 *
 *   - the BOUNDED open retry and its NotReadableError / AbortError tolerance (a SELF-contention
 *     while a just-stopped output context still holds the OS DAC). Unbounded, a genuinely held
 *     device stalls the Start button for tens of seconds instead of reporting;
 *   - the teardown ORDER - disconnect the worklet -> suspend -> close - with the `closing` guard
 *     raised FIRST, so the 'closed' statechange our own close() fires is never reported as a
 *     device loss (that would raise an AUDIO_DEVICE_ERROR alert on every normal Stop);
 *   - the hidden-resampling WARNING gated on the DEFAULT sink: the rate-unspecified probe
 *     describes the default output device only, so claiming "the device runs at X Hz" for a
 *     specifically selected sink asserts a rate that was never probed;
 *   - the two-phase open/start split: the rate the browser GRANTED is what the DDS is built at.
 *
 * Browser globals (AudioContext, AudioWorkletNode) are doubles - the real module under test runs
 * with no browser at all. Same fake shapes as the capture twin.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

/** Ordered log of everything the doubles are asked to do - the teardown-order assertions. */
let ops = [];
/** Scripted OUTPUT-context constructions, consumed in order; a string means "throw this name". */
let openScript = [];
/** Every AudioContext(options) the sink built, in order - the probe included. */
let ctxCalls = [];
/** What a rate-UNSPECIFIED context (the probe) reports; 0 = the probe constructor throws. */
let probeRate = 0;
/** Rate the output context reports (0 = grant exactly what was requested). */
let grantedRate = 0;
/** True to make setSinkId reject, as an unavailable/renamed sink does. */
let sinkIdFails = false;
/** The doubles the sink built last - how a test reaches inside a sealed sink. */
let lastCtx = null;
let lastNode = null;

class FakeNode {
  constructor(name) { this.name = name; }
  connect(next) { ops.push(`connect:${this.name}->${next.name}`); return next; }
  disconnect() { ops.push(`disconnect:${this.name}`); }
}

class FakeAudioContext {
  constructor(options = {}) {
    ctxCalls.push(options);
    this.state = 'suspended';
    this.onerror = null;
    this.destination = new FakeNode('destination');
    this.audioWorklet = { addModule: async (url) => { ops.push(`addModule:${String(url).split('/').pop()}`); } };
    this._listeners = [];
    // A context created WITHOUT an explicit sampleRate is the sink's rate probe: it reports the
    // platform's native output rate, which is the whole point of that extra open/close.
    if (options.sampleRate === undefined) {
      ops.push(`probe(${probeRate})`);
      if (probeRate === 0) throw new Error('fake: no output device to probe');
      this.sampleRate = probeRate;
      return;
    }
    const outcome = openScript.shift();
    if (typeof outcome === 'string') {
      ops.push(`new AudioContext(${options.sampleRate}) !! ${outcome}`);
      const e = new Error(`fake ${outcome}`);
      e.name = outcome;
      throw e;
    }
    this.sampleRate = grantedRate || options.sampleRate;
    ops.push(`new AudioContext(${this.sampleRate})`);
    lastCtx = this;
  }

  addEventListener(type, fn) { this._listeners.push([type, fn]); }

  async setSinkId(id) {
    ops.push(`setSinkId:${id}`);
    if (sinkIdFails) throw new Error('fake: sink unavailable');
  }

  async resume() { ops.push('resume'); this.#toState('running'); }

  async suspend() { ops.push('suspend'); this.#toState('suspended'); }

  async close() { ops.push('close'); this.#toState('closed'); }

  /** Fires a statechange the way a lost device does. */
  emitStateChange(state) { this.#toState(state); }

  /** Every real state transition fires statechange - including the ones OUR teardown causes,
   *  which is precisely what the `closing` guard has to survive. */
  #toState(state) {
    this.state = state;
    for (const [type, fn] of this._listeners) if (type === 'statechange') fn();
  }
}

/** The dds-processor node - its port is the live-control channel the controller writes to. */
class FakeAudioWorkletNode extends FakeNode {
  constructor(ctx, name, options) {
    super('dds');
    this.processorName = name;
    this.options = options;
    this.port = { messages: [], postMessage(m) { ops.push('post-control'); this.messages.push(m); } };
    ops.push(`new AudioWorkletNode(${name})`);
    lastNode = this;
  }
}

globalThis.AudioContext = FakeAudioContext;
globalThis.AudioWorkletNode = FakeAudioWorkletNode;

const { WebAudioPlaybackSink, openOutputContext } = await import('../js/generator/web-audio-playback-sink.js');

/** Fresh per-test state; the retry pause is real, so keep the scripts short. */
function reset() {
  ops = [];
  ctxCalls = [];
  openScript = [];
  probeRate = 0;
  grantedRate = 0;
  sinkIdFails = false;
  lastCtx = lastNode = null;
}

/** The OUTPUT-context constructions only - the probe is not an attempt. */
function outCalls() { return ctxCalls.filter((o) => o.sampleRate !== undefined); }

const DEVICE_ID = 'output-42';
const RATE_HZ = 48_000;

/** A sink whose status traffic the caller can inspect. `deviceId` is passed through verbatim -
 *  undefined/'' mean "the default sink", which is a case under test. A device loss is NOT a
 *  callback: the sink RECORDS it (lostFromBelow) and the pane polls, so the tests read the flag. */
function makeSink(deviceId) {
  const status = [];
  const sink = new WebAudioPlaybackSink({ status: (t) => status.push(t) });
  return { sink, status, spec: { sampleRate: RATE_HZ, deviceId } };
}

/** A sink that is open AND on air - the state every teardown case starts from. */
async function running(deviceId = DEVICE_ID) {
  const h = makeSink(deviceId);
  probeRate = grantedRate || RATE_HZ;        // matched rates: no resampling notice in the way
  await h.sink.open(h.spec);
  await h.sink.start({
    form: 'SINE', frequency: 1000, amplitudeVRms: 0.5, dacFsVoltageAmpl: 1,
    ditherBits: 0, outputChannels: 'BOTH', rightLaneScale: 1,
    control: { rectDuty: 0.5 },
  });
  return h;
}

test('a busy DAC is retried, bounded, and reported through status', async () => {
  reset();
  const { sink, status, spec } = makeSink(DEVICE_ID);
  // Two exclusive-hold rejections (the self-contention: the just-stopped output context has not
  // released the OS DAC yet), then the device opens.
  openScript = ['NotReadableError', 'AbortError', null];

  const granted = await sink.open(spec);

  assert.equal(outCalls().length, 3, 'the open must be retried, not abandoned on the first hold');
  assert.deepEqual(status, [
    'output device busy (attempt 1/3) - retrying...',
    'output device busy (attempt 2/3) - retrying...',
  ], 'each retry is surfaced, and the successful attempt is not');
  assert.equal(granted, RATE_HZ);
  assert.equal(sink.sampleRate, RATE_HZ);
});

test('the retry is BOUNDED - a permanent hold surfaces after 3 attempts', async () => {
  reset();
  const { sink, status, spec } = makeSink(DEVICE_ID);
  // Three holds then a success: with the bound intact the third throw is the answer; a raised
  // bound would keep going, "succeed" on the 4th, and resolve instead of reporting - while a real
  // held device would have burned 250 ms per extra attempt with the Start button stuck.
  openScript = ['AbortError', 'AbortError', 'AbortError', null];

  await assert.rejects(() => sink.open(spec), /AbortError|fake AbortError/);

  assert.equal(outCalls().length, 3, 'exactly OPEN_MAX_ATTEMPTS attempts - never an unbounded loop');
  assert.deepEqual(status, [
    'output device busy (attempt 1/3) - retrying...',
    'output device busy (attempt 2/3) - retrying...',
  ], 'the notice names the real bound, and the exhausting attempt reports rather than promises more');
  assert.equal(sink.sampleRate, 0, 'a failed open leaves no half-held line behind');
  assert.equal(sink.port, null);
});

test('a non-retriable open failure surfaces immediately', async () => {
  reset();
  const { sink, status, spec } = makeSink(DEVICE_ID);
  // An unsupported rate is a hard NO from the constructor, not a contention: retrying it would
  // only delay the error by 500 ms.
  openScript = ['NotSupportedError', null];

  await assert.rejects(() => sink.open(spec), /NotSupportedError|fake NotSupportedError/);

  assert.equal(outCalls().length, 1, 'a permanent rejection must not burn the retry budget');
  assert.deepEqual(status, []);
});

test('open() asks for the requested rate on the SELECTED sink and returns what was granted', async () => {
  reset();
  const { sink, spec } = makeSink(DEVICE_ID);
  probeRate = 44_100;
  grantedRate = 44_100;                      // the browser granted something other than the ask

  const granted = await sink.open(spec);

  assert.deepEqual(outCalls(), [{ sampleRate: RATE_HZ, latencyHint: 'playback' }],
    'the DAC is asked for the configured rate, at the playback latency hint');
  assert.ok(ops.includes(`setSinkId:${DEVICE_ID}`), 'the configured output device is applied');
  assert.equal(granted, 44_100, 'the GRANTED rate is returned - the emit frequency is resolved against it');
  assert.equal(sink.sampleRate, 44_100);
});

test('the hidden-resampling WARNING is DEFAULT-sink only', async () => {
  // The probe context carries no sampleRate, so it reports the DEFAULT device's rate. On the
  // default sink that is a fact worth shouting; with a specific sink selected it describes a
  // different device, so the status line must stay silent rather than assert a rate never probed.
  for (const deviceId of [undefined, '', 'default']) {
    reset();
    const { sink, status, spec } = makeSink(deviceId);
    probeRate = 44_100;

    await sink.open(spec);

    assert.equal(status.length, 1, `default sink (${JSON.stringify(deviceId)}) must warn`);
    assert.match(status[0], /^WARNING: output device runs at 44100 Hz .*resamples 48000 Hz/);
  }

  reset();
  const { sink, status, spec } = makeSink('output-7');
  probeRate = 44_100;

  await sink.open(spec);

  assert.deepEqual(status, [],
    'a specific sink is not the probed device - the mismatch is a debug hint, not a status claim');
});

test('matched rates and an unprobeable device both stay silent', async () => {
  reset();
  const a = makeSink(undefined);
  probeRate = RATE_HZ;                       // device already at the requested rate
  await a.sink.open(a.spec);
  assert.deepEqual(a.status, []);

  reset();
  const b = makeSink(undefined);
  probeRate = 0;                             // the probe itself throws
  const granted = await b.sink.open(b.spec);
  assert.deepEqual(b.status, [], 'an unknown device rate is not something to warn about');
  assert.equal(granted, RATE_HZ, 'and a failed probe must never block the real open');
});

test('start() applies the live control BEFORE connecting, at the GRANTED rate', async () => {
  reset();
  grantedRate = 44_100;
  const { sink } = await running();

  assert.equal(lastNode.processorName, 'dds-processor');
  assert.deepEqual(lastNode.options.outputChannelCount, [2],
    'stereo out: a mono lane up-mixed by the destination cannot carry per-lane values');
  assert.deepEqual(lastNode.options.processorOptions, {
    form: 'SINE', frequency: 1000, sampleRate: 44_100,
    amplitudeVRms: 0.5, dacFsVoltageAmpl: 1,
    ditherBits: 0, outputChannels: 'BOTH', rightLaneScale: 1,
  }, 'the kernel is built at the rate the DEVICE granted, not the one requested');
  assert.deepEqual(lastNode.port.messages, [{ rectDuty: 0.5 }]);
  assert.ok(ops.indexOf('post-control') < ops.indexOf('connect:dds->destination'),
    'the first rendered block must already carry duty/dual-tone/sweep - never a default value');
  assert.equal(lastCtx.state, 'running');
  assert.equal(sink.port, lastNode.port, 'port exposes the real MessagePort for live control');
});

test('close(): disconnect the worklet -> suspend -> close, and it is idempotent', async () => {
  reset();
  const { sink } = await running();
  ops = [];                                  // from here on: the teardown only

  await sink.close();

  assert.deepEqual(ops, ['disconnect:dds', 'suspend', 'close'],
    'closing a context with a live worklet still wired can crash the renderer');
  assert.equal(sink.sampleRate, 0);
  assert.equal(sink.port, null);

  ops = [];
  await sink.close();                        // Stop after a failed start does exactly this
  assert.deepEqual(ops, [], 'a second close must not touch a released line');
});

test('the closing guard keeps OUR close() from being read as a lane death', async () => {
  reset();
  const { sink } = await running();

  assert.equal(sink.lostFromBelow(), null, 'a healthy lane confesses nothing');

  // A genuine loss while playing IS recorded - once, however many paths notice it (a dying
  // device fires the async error AND drops the context's state).
  lastCtx.onerror();
  lastCtx.emitStateChange('interrupted');
  assert.match(sink.lostFromBelow().message, /AudioContext error/);

  // ...but the suspend/close our own teardown fires must not look like a death, or every normal
  // Stop would pop the playback-ended dialog.
  const { sink: clean } = await running();
  await clean.close();
  assert.equal(clean.lostFromBelow(), null, 'our own teardown is not a device failure');
});

test('a reopened sink records device losses again', async () => {
  // The guard is raised by close() and stays raised; open() must clear it, or a device lost in
  // the NEXT session would be swallowed silently.
  reset();
  const first = await running();
  await first.sink.close();

  const { sink, spec } = makeSink(DEVICE_ID);
  probeRate = RATE_HZ;
  await sink.open(spec);
  lastCtx.emitStateChange('closed');

  assert.match(sink.lostFromBelow().message, /AudioContext state=closed/);
});

test('openOutputContext survives a rejecting setSinkId (the shared sweep lane)', async () => {
  // The controller's file/sweep lane opens through the same helper. A sink that vanished between
  // the scan and the play must not fail the open - the tone belongs on the default device rather
  // than nowhere.
  reset();
  sinkIdFails = true;
  const warned = [];
  const realWarn = console.warn;
  console.warn = (...a) => warned.push(a[0]);
  let ctx;
  try {
    ctx = await openOutputContext({ sampleRate: RATE_HZ, latencyHint: 'playback' },
      DEVICE_ID, () => {});
  } finally {
    console.warn = realWarn;
  }

  assert.equal(ctx.sampleRate, RATE_HZ);
  assert.deepEqual(warned, ['setSinkId'], 'swallowed for the caller, but not silently');
  assert.deepEqual(ops, [`new AudioContext(${RATE_HZ})`, `setSinkId:${DEVICE_ID}`, 'resume'],
    'the device is forced to engage inside the retry, so a busy DAC surfaces here - not later');
});
