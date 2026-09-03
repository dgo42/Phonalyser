/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Regression net for the capture-source SEAM: the Web Audio device half was lifted out of
 * SharedCapture (which now owns only the refcount, the one ring and the reader cursors), and
 * every behaviour in that half was paid for on the bench. What is pinned here is exactly what
 * an extraction can silently lose:
 *
 *   - the bounded open retry, its NotReadableError / AbortError / OverconstrainedError
 *     tolerance (a SELF-contention while the just-stopped contexts still hold the OS device)
 *     and its refusal to substitute the default input when the rate constraint is dropped;
 *   - the teardown ORDER: silence the port -> disconnect the graph -> stop the tracks -> suspend
 *     -> close, with the `closing` guard up first so our own close is never reported as a
 *     device failure;
 *   - on the SharedCapture side: the rate re-pin, the ALWAYS-computeAnalysisFreqs, the ring
 *     built before the batch sink is installed, and stop-then-close on teardown.
 *
 * Browser globals (navigator.mediaDevices, AudioContext, AudioWorkletNode) are doubles, so
 * the real module under test runs with no browser at all.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

/** Ordered log of everything the doubles are asked to do - the teardown-order assertions. */
let ops = [];
/** Scripted getUserMedia outcomes, consumed in order; a string means "reject with this name". */
let gumScript = [];
/** Every constraint object getUserMedia was called with, in order. */
let gumCalls = [];
/** Rate the fake track reports (0 = report nothing, so the requested rate stands). */
let trackRate = 0;
/** The doubles the source built last - how a test reaches inside a sealed source. */
let lastCtx = null;
let lastCapNode = null;
/** The stream the fake getUserMedia last handed out - its track is where device removal shows. */
let lastStream = null;

class FakeTrack {
  // A real MediaStreamTrack is an EventTarget, and the source now listens for 'ended' / 'mute' -
  // an unplugged USB interface can leave the track ALIVE and silent, which the AudioContext never
  // notices, so the track is the only place device removal shows.
  #listeners = new Map();
  stop() { ops.push('track-stop'); }
  getSettings() { return trackRate ? { sampleRate: trackRate } : {}; }
  addEventListener(type, fn) { this.#listeners.set(type, fn); }
  removeEventListener(type) { this.#listeners.delete(type); }
  /** Test hook: fire one of the events the browser would. */
  emit(type) { const fn = this.#listeners.get(type); if (fn) fn(); return fn != null; }
}

class FakeStream {
  #tracks = [new FakeTrack()];
  getAudioTracks() { return this.#tracks; }
  getTracks() { return this.#tracks; }
}

class FakeNode {
  constructor(name) { this.name = name; }
  connect(next) { ops.push(`connect:${this.name}->${next.name}`); return next; }
  disconnect() { ops.push(`disconnect:${this.name}`); }
}

class FakeAudioContext {
  constructor(options) {
    this.sampleRate = options.sampleRate;
    this.state = 'suspended';
    this.destination = new FakeNode('destination');
    this.audioWorklet = { addModule: async (url) => { ops.push(`addModule:${String(url).split('/').pop()}`); } };
    this.onerror = null;
    this._listeners = [];
    ops.push(`new AudioContext(${options.sampleRate})`);
    lastCtx = this;
  }

  addEventListener(type, fn) { this._listeners.push([type, fn]); }

  createGain() { const g = new FakeNode('gain'); g.gain = { value: 1 }; return g; }

  createMediaStreamSource() { return new FakeNode('src'); }

  async resume() { ops.push('resume'); this.state = 'running'; }

  async suspend() { ops.push('suspend'); this.state = 'suspended'; }

  async close() { ops.push('close'); this.state = 'closed'; }

  /** Fires a statechange the way a lost device does. */
  emitStateChange(state) {
    this.state = state;
    for (const [type, fn] of this._listeners) if (type === 'statechange') fn();
  }
}

/** The capture worklet node - its port is what the source silences on stop. */
class FakeAudioWorkletNode extends FakeNode {
  constructor(ctx, name) {
    super('cap');
    this.processorName = name;
    this.port = {
      _onmessage: null,
      set onmessage(fn) { ops.push(fn ? 'port-onmessage' : 'port-silenced'); this._onmessage = fn; },
      get onmessage() { return this._onmessage; },
    };
    ops.push(`new AudioWorkletNode(${name})`);
    lastCapNode = this;
  }

  /** Delivers one worklet batch, as the realtime thread does. */
  post(data) { this.port.onmessage({ data }); }
}

function installGlobals() {
  const mediaDevices = {
    getUserMedia: async (constraints) => {
      gumCalls.push(constraints.audio);
      const outcome = gumScript.shift();
      if (typeof outcome === 'string') {
        const e = new Error(`fake ${outcome}`);
        e.name = outcome;
        throw e;
      }
      lastStream = new FakeStream();
      return lastStream;
    },
  };
  Object.defineProperty(globalThis, 'navigator', {
    value: { mediaDevices }, configurable: true, writable: true,
  });
  globalThis.AudioContext = FakeAudioContext;
  globalThis.AudioWorkletNode = FakeAudioWorkletNode;
}
installGlobals();

const { WebAudioCaptureSource } = await import('../js/audio/web-audio-capture-source.js');
const { SharedCapture, BUFFER_SECONDS } = await import('../js/audio/shared-capture.js');
const { testPrefs } = await import('./_test-prefs.mjs');
const { classifyBrowserFailure } = await import('../js/audio/device-failure-reason.js');
const { DeviceFailureReason, deviceFailureReasonFromName }
  = await import('../js/enums/device-failure-reason.js');
const { CaptureEndReason } = await import('../js/audio/capture-end-reason.js');
const { MessageBus } = await import('../js/bus/message-bus.js');
const { Events } = await import('../js/bus/events.js');

/** Fresh per-test state; the retry pause is real, so keep the scripts short. */
function reset() {
  ops = [];
  gumCalls = [];
  gumScript = [];
  trackRate = 0;
}

/** Collects AUDIO_DEVICE_ERROR payloads published while `body` runs. */
async function withDeviceErrors(body) {
  const seen = [];
  const listener = (payload) => seen.push(payload);
  MessageBus.instance().subscribe(Events.AUDIO_DEVICE_ERROR, listener);
  try {
    await body();
  } finally {
    MessageBus.instance().unsubscribe(Events.AUDIO_DEVICE_ERROR, listener);
  }
  return seen;
}

const DEVICE_ID = 'input-42';
const RATE_HZ = 48_000;

test('a busy device is retried, bounded, and reported through status', async () => {
  reset();
  const status = [];
  const source = new WebAudioCaptureSource({ status: (t) => status.push(t) });
  // Two exclusive-hold rejections (the self-contention: the just-stopped contexts have not
  // released the OS device yet), then the device opens.
  gumScript = ['NotReadableError', 'NotReadableError', null];

  await source.open(DEVICE_ID, RATE_HZ);

  assert.equal(gumCalls.length, 3, 'the open must be retried, not abandoned on the first hold');
  assert.deepEqual(status, [
    'input device busy (attempt 1/3) - retrying...',
    'input device busy (attempt 2/3) - retrying...',
  ], 'each retry is surfaced, and the successful attempt is not');
  assert.equal(source.isOpen, true);
});

test('the retry is BOUNDED - a permanent hold surfaces after 3 attempts', async () => {
  reset();
  const source = new WebAudioCaptureSource({});
  gumScript = ['AbortError', 'AbortError', 'AbortError', null];

  await assert.rejects(() => source.open(DEVICE_ID, RATE_HZ), /AbortError|fake AbortError/);

  assert.equal(gumCalls.length, 3, 'exactly OPEN_MAX_ATTEMPTS attempts - never an unbounded loop');
  assert.equal(source.isOpen, false);
});

test('OverconstrainedError is retriable (a cold device), NotFoundError is not', async () => {
  reset();
  // Overconstrained on the rate-constrained form falls through to the unconstrained form
  // inside one attempt; failing THAT too retries the whole attempt. 3 attempts × 2 forms.
  gumScript = Array(6).fill('OverconstrainedError');
  await assert.rejects(() => new WebAudioCaptureSource({}).open(DEVICE_ID, RATE_HZ));
  assert.equal(gumCalls.length, 6, 'a cold device gets the full bounded retry');

  reset();
  // A vanished device: the rate-free form is still tried once (a NotFoundError on the
  // constrained form is a constraint miss as far as that catch can tell), but the OUTER loop
  // must not retry it - 1 attempt × 2 forms, then the failure surfaces.
  gumScript = ['NotFoundError', 'NotFoundError', null, null];
  await assert.rejects(() => new WebAudioCaptureSource({}).open(DEVICE_ID, RATE_HZ), /NotFound/);
  assert.equal(gumCalls.length, 2, 'a vanished device must fail fast, not burn the retry budget');
});

test('the rate fallback keeps deviceId:{exact} - never the default input', async () => {
  reset();
  gumScript = ['OverconstrainedError', null];

  await new WebAudioCaptureSource({}).open(DEVICE_ID, RATE_HZ);

  assert.equal(gumCalls.length, 2);
  assert.deepEqual(gumCalls[0].sampleRate, { exact: RATE_HZ }, 'the exact rate is asked for first');
  assert.equal(gumCalls[1].sampleRate, undefined, 'the retry drops the RATE constraint');
  for (const [i, audio] of gumCalls.entries()) {
    assert.deepEqual(audio.deviceId, { exact: DEVICE_ID },
      `attempt ${i + 1} must stay pinned to the CONFIGURED input`);
    assert.equal(audio.echoCancellation, false);
    assert.equal(audio.noiseSuppression, false);
    assert.equal(audio.autoGainControl, false);
    assert.deepEqual(audio.channelCount, { ideal: 2 }, 'stereo, so ch1 (calibrated) exists');
  }
});

test('the context is built at the STREAM rate, which is what sampleRate reports', async () => {
  reset();
  gumScript = [null];
  trackRate = 44_100;                       // the device refuses 48 k and gives its own rate
  const source = new WebAudioCaptureSource({});

  await source.open(DEVICE_ID, RATE_HZ);

  assert.ok(ops.includes(`new AudioContext(${trackRate})`),
    'a MediaStreamSource whose stream rate ≠ the context rate silently kills capture');
  assert.equal(source.sampleRate, trackRate);
});

test('teardown order: silence the port -> disconnect -> stop tracks -> suspend -> close', async () => {
  reset();
  gumScript = [null];
  const source = new WebAudioCaptureSource({});
  await source.open(DEVICE_ID, RATE_HZ);
  await source.start(() => {});
  ops = [];                                 // from here on: the teardown only

  await source.stop();
  assert.deepEqual(ops, ['port-silenced'],
    'stop() ONLY silences the sink - no batch may dispatch into a half-torn ring, and the '
    + 'device must still be held until close()');

  ops = [];
  await source.close();
  assert.deepEqual(ops, [
    'port-silenced',                        // the guard is re-raised idempotently (see the next test)
    'disconnect:src', 'disconnect:cap', 'disconnect:gain',
    'track-stop',                           // release the mic
    'suspend', 'close',                     // closing with worklets still wired can crash the renderer
  ]);
  assert.equal(source.sampleRate, 0);
});

test('close() alone still tears down in order (the acquire-failure path)', async () => {
  reset();
  gumScript = [null];
  const source = new WebAudioCaptureSource({});
  await source.open(DEVICE_ID, RATE_HZ);
  await source.start(() => {});
  ops = [];

  await source.close();                     // no stop() first - a failed acquire does exactly this

  assert.deepEqual(ops, [
    'port-silenced', 'disconnect:src', 'disconnect:cap', 'disconnect:gain',
    'track-stop', 'suspend', 'close',
  ]);
});

test('the closing guard keeps OUR teardown from being reported as a lost device', async () => {
  reset();
  gumScript = [null];
  const source = new WebAudioCaptureSource({});
  await source.open(DEVICE_ID, RATE_HZ);
  const ended = [];
  await source.start(() => {}, (r) => ended.push(r));
  const ctx = lastCtx;

  // A genuine loss while running ENDS the stream...
  ctx.emitStateChange('interrupted');
  assert.deepEqual(ended, [CaptureEndReason.DEVICE_LOST]);

  // ...but the 'closed' our own close() fires must not end anything.
  ended.length = 0;
  await source.stop();
  ctx.emitStateChange('closed');
  await source.close();
  assert.deepEqual(ended, [], 'our own teardown is not a device failure');
});

test('start() before open() is refused', async () => {
  reset();
  await assert.rejects(() => new WebAudioCaptureSource({}).start(() => {}),
    /Call open\(\) before start\(\)/);
});

test('start installs the batch sink and resumes a suspended context', async () => {
  reset();
  gumScript = [null];
  const source = new WebAudioCaptureSource({});
  await source.open(DEVICE_ID, RATE_HZ);
  const batches = [];

  await source.start((b) => batches.push(b));

  assert.ok(ops.indexOf('port-onmessage') < ops.indexOf('resume'),
    'the sink must be live before the context runs');
  lastCapNode.post({ l: new Float32Array(2), r: new Float32Array(2), n: 2 });
  assert.equal(batches.length, 1);
  assert.equal(batches[0].n, 2);
});

// --- the seam: SharedCapture over an arbitrary CaptureSource ------------------------------

/** Minimal CaptureSource double - the contract, nothing else. */
class StubSource {
  calls = [];
  sampleRate = 0;
  isOpen = false;
  onBatch = null;
  openError = null;

  constructor(reportedRate) { this.reportedRate = reportedRate; }

  async open(deviceId, rateHz) {
    this.calls.push(`open(${deviceId},${rateHz})`);
    if (this.openError) throw this.openError;
    this.sampleRate = this.reportedRate || rateHz;
    this.isOpen = true;
  }

  async start(onBatch, onCaptureEnded) {
    this.calls.push('start');
    this.onBatch = onBatch;
    this.onCaptureEnded = onCaptureEnded;   // the capture-ended seam (Java PcmBatchListener)
    // Some devices dispatch the instant the sink is installed (the capture worklet can post on
    // the very first render quantum after resume) - modelled on demand.
    if (this.batchOnStart) onBatch({ l: new Float32Array([0.5]), r: new Float32Array([-0.5]), n: 1 });
  }

  async stop() { this.calls.push('stop'); this.onBatch = null; }

  async close() { this.calls.push('close'); this.isOpen = false; this.sampleRate = 0; }

  /** A real source classifies its OWN native errors (Java AudioDeviceManager.classifyFailure);
   *  the browser lanes read the DOMException table. Deleted per-test to model the SPI default. */
  classifyFailure(err) { return classifyBrowserFailure(err); }
}

function sharedOver(source, extra = {}) {
  const prefs = testPrefs({ inputDeviceName: DEVICE_ID, inputSampleRate: RATE_HZ });
  let freqsComputed = 0;
  const capture = new SharedCapture({
    prefs,
    status: () => {},
    computeAnalysisFreqs: () => { freqsComputed++; },
    captureSource: source,
    ...extra,
  });
  return { capture, prefs, freqs: () => freqsComputed };
}

test('the seam: acquire opens the injected source, builds the ring, then starts it', async () => {
  const source = new StubSource();
  const { capture, prefs, freqs } = sharedOver(source);

  const reader = await capture.acquire();

  assert.ok(reader != null, 'a cursor over the live ring');
  assert.deepEqual(source.calls, [`open(${DEVICE_ID},${RATE_HZ})`, 'start'],
    'the ring must exist before the sink is installed - start() comes last');
  assert.equal(capture.refCount, 1);
  assert.equal(capture.contextOpen, true, 'contextOpen tracks the SOURCE, not a local flag');
  assert.equal(capture.buffer.getCapacity(), Math.ceil(prefs.current().inputSampleRate * BUFFER_SECONDS));
  assert.equal(freqs(), 1);
});

test('the seam: a device dispatching the instant the sink is installed still hits the ring', async () => {
  // Why the ring is built BEFORE start(): the very first batch must have somewhere to go. Build
  // it after and that batch is silently lost - a missing head of capture nobody sees.
  const source = new StubSource();
  source.batchOnStart = true;
  const { capture } = sharedOver(source);

  await capture.acquire();

  assert.equal(capture.buffer.getWritePos(), 1, 'the first batch must land in the shared ring');
});

test('the seam: the analysis is ALWAYS recomputed, and the preference follows the device rate', async () => {
  // Computing the freqs only on a rate MISMATCH left snapped=undefined -> period=NaN -> a broken
  // scope trace whenever Scope Record ran alone, so the matching-rate case must compute too.
  const matched = new StubSource(RATE_HZ);
  const a = sharedOver(matched);
  await a.capture.acquire();
  assert.equal(a.freqs(), 1, 'computed even when the device gave exactly the requested rate');
  assert.equal(a.prefs.current().inputSampleRate, RATE_HZ);

  const imposed = new StubSource(44_100);
  const b = sharedOver(imposed);
  await b.capture.acquire();
  assert.equal(b.prefs.current().inputSampleRate, 44_100,
    'the input-rate preference follows the ACTUAL rate, so every reader sees the ring\'s clock');
  assert.equal(b.capture.inSampleRate, 44_100);
  assert.equal(b.capture.buffer.getCapacity(), Math.ceil(44_100 * BUFFER_SECONDS),
    'and the ring is sized at the actual rate, not the requested one');
  assert.equal(b.freqs(), 1);
});

test('the seam: the refcount opens once and tears down on the LAST release', async () => {
  const source = new StubSource();
  const { capture } = sharedOver(source);

  await capture.acquire();
  await capture.acquire();
  assert.equal(capture.refCount, 2);
  assert.deepEqual(source.calls, [`open(${DEVICE_ID},${RATE_HZ})`, 'start'], 'one device, two cursors');

  await capture.release();
  assert.equal(capture.refCount, 1);
  assert.equal(source.isOpen, true, 'a consumer still holds the device');

  await capture.release();
  assert.deepEqual(source.calls, [`open(${DEVICE_ID},${RATE_HZ})`, 'start', 'stop', 'close'],
    'teardown is stop THEN close - the sink is silenced before the device goes away');
  assert.equal(capture.refCount, 0);
  assert.equal(capture.contextOpen, false);
  assert.equal(capture.buffer, null);
});

test('the seam: a failing stop() still closes the device', async () => {
  const source = new StubSource();
  source.stop = async () => { source.calls.push('stop'); throw new Error('detach failed'); };
  const { capture } = sharedOver(source);

  await capture.acquire();
  await capture.release();

  assert.deepEqual(source.calls, [`open(${DEVICE_ID},${RATE_HZ})`, 'start', 'stop', 'close'],
    'a wedged stop must never skip the close that frees the device');
  assert.equal(source.isOpen, false);
});

test('the seam: an open failure reports the REASON, tears down, and returns no cursor', async () => {
  const source = new StubSource();
  const err = new Error('device held');
  err.name = 'NotReadableError';
  source.openError = err;
  const { capture } = sharedOver(source);

  const seen = await withDeviceErrors(async () => {
    assert.equal(await capture.acquire(), null);
  });

  // The raw DOMException must never reach the operator: the SOURCE classified it, and what is
  // published is the machine-readable reason plus the sentence it renders to (J8 - raw driver
  // text is confined to the log).
  assert.equal(seen.length, 1);
  assert.equal(seen[0].direction, 'input');
  assert.equal(seen[0].reason, 'DEVICE_IN_USE', 'NotReadableError = another app holds the device');
  assert.ok(seen[0].message && !seen[0].message.includes('NotReadableError'),
    'the alert shows the localized reason, never the browser error name');
  assert.ok(!capture.getLastStartError().includes('NotReadableError'),
    'and so does the status / streaming-save text');
  assert.equal(capture.refCount, 0);
  assert.deepEqual(source.calls.slice(-2), ['stop', 'close'], 'a half-open device is torn down');
});

test('the seam: a source that cannot classify gets the SPI default, UNKNOWN', async () => {
  const source = new StubSource();
  source.classifyFailure = undefined;         // the SPI default: a backend that reads no codes
  const err = new Error('something native');
  err.name = 'NotReadableError';
  source.openError = err;
  const { capture } = sharedOver(source);

  const seen = await withDeviceErrors(async () => { await capture.acquire(); });

  assert.equal(seen[0].reason, DeviceFailureReason.UNKNOWN.name,
    'SharedCapture must never parse a driver text itself - unknown is always a legal answer');
});

test('the seam: a permission denial is status-only - no modal', async () => {
  const source = new StubSource();
  const err = new Error('denied');
  err.name = 'NotAllowedError';
  source.openError = err;
  const { capture } = sharedOver(source);

  const seen = await withDeviceErrors(async () => { await capture.acquire(); });

  assert.deepEqual(seen, [], 'NotAllowed/Security is a permission denial, not a device failure');
  assert.ok(!capture.getLastStartError().includes('NotAllowedError'),
    'even the status line carries the reason, not the DOMException name');
});

test('the seam: staged batches reach the ring, the owner tap and the bus', async () => {
  const source = new StubSource();
  const taps = [];
  const { capture } = sharedOver(source, { publishBatch: true, onBatch: (d) => taps.push(d.n) });
  const published = [];
  const listener = () => published.push(1);
  MessageBus.instance().subscribe(Events.CAPTURE_BATCH_AVAILABLE, listener);
  try {
    await capture.acquire();
    const l = new Float32Array([0.25, -0.5]);
    const r = new Float32Array([-0.25, 0.5]);

    source.onBatch({ l, r, n: 2 });

    assert.equal(capture.buffer.getWritePos(), 2, 'the batch is staged into the shared ring');
    assert.deepEqual(taps, [2], 'the owner tap fires so its consumers can read their cursors');
    assert.equal(published.length, 1, 'and the live capture publishes CAPTURE_BATCH_AVAILABLE');
  } finally {
    MessageBus.instance().unsubscribe(Events.CAPTURE_BATCH_AVAILABLE, listener);
  }
});

test('the seam: a batch arriving after teardown is dropped, not appended to a dead ring', async () => {
  const source = new StubSource();
  const { capture } = sharedOver(source);
  await capture.acquire();
  const sink = source.onBatch;

  await capture.release();
  sink({ l: new Float32Array(2), r: new Float32Array(2), n: 2 });   // late worklet message

  assert.equal(capture.buffer, null);
});

// --- device removal: the track, not the context ------------------------------
// The death is reported as STATE up the data seam (captureEnded -> the ring finishes), never as
// an alert: from a reader's side a dead lane and a silent one are both "no new samples", so the
// side holding the card is the only one that can say which it is.

test('an unplugged input ENDS the stream: the TRACK ends while the context stays happy', async () => {
  const source = new WebAudioCaptureSource({ status: () => {} });
  await source.open(DEVICE_ID, RATE_HZ);
  const ended = [];
  await source.start(() => {}, (r) => ended.push(r));

  // What Chrome does on USB removal: it ends the track. The AudioContext is untouched - which is
  // why watching only the context let the app record a flat line with cap/s still ticking.
  assert.equal(lastStream.getAudioTracks()[0].emit('ended'), true, 'the source listens for it');

  assert.deepEqual(ended, [CaptureEndReason.DEVICE_LOST], 'the device went away');
});

test('a muted input - device present but delivering nothing - is DELIVERY_STALLED', async () => {
  const source = new WebAudioCaptureSource({ status: () => {} });
  await source.open(DEVICE_ID, RATE_HZ);
  const ended = [];
  await source.start(() => {}, (r) => ended.push(r));

  assert.equal(lastStream.getAudioTracks()[0].emit('mute'), true);

  // The worklet keeps posting blocks of zeros, so the delivery deadline cannot see this one.
  assert.deepEqual(ended, [CaptureEndReason.DELIVERY_STALLED]);
});

test('OUR OWN teardown is silent - closing must not look like a device failure', async () => {
  const source = new WebAudioCaptureSource({ status: () => {} });
  await source.open(DEVICE_ID, RATE_HZ);
  const ended = [];
  await source.start(() => {}, (r) => ended.push(r));
  const track = lastStream.getAudioTracks()[0];
  const ctx = lastCtx;

  await source.stop();
  ctx.emitStateChange('closed');
  await source.close();
  track.emit('ended');            // stopping a track fires 'ended' in a real browser

  assert.deepEqual(ended, [], 'the closing flag suppresses our own end');
});

test('fromName resolves only OWN enum values - a wire value naming a prototype key is UNKNOWN', () => {
  // The forward-compatible read for a reason that arrived from ANOTHER process (the net client
  // feeds it the wire's reason field): an object literal inherits Object.prototype, so without
  // an own-property check 'toString' would come back as a FUNCTION posing as a reason.
  assert.equal(deviceFailureReasonFromName('DEVICE_IN_USE'), DeviceFailureReason.DEVICE_IN_USE);
  for (const key of ['toString', 'constructor', 'hasOwnProperty', '__proto__', 'NEWER_PEER_VALUE', '', null]) {
    assert.equal(deviceFailureReasonFromName(key), DeviceFailureReason.UNKNOWN, `${key} must be UNKNOWN`);
  }
});

// --- the delivery deadline: a worklet that just stops posting ----------------

test('a worklet that STOPS POSTING while the track stays live is DELIVERY_STALLED', async (t) => {
  // The loss signal for a device that tells us nothing when it goes: no track event, no context
  // statechange, just no more messages - and the app went on averaging silence. Driven on mocked
  // timers + a mocked clock so the contract is pinned without spending the real 2 s.
  const realPerf = globalThis.performance;
  let nowMs = 0;
  globalThis.performance = { now: () => nowMs };
  t.mock.timers.enable({ apis: ['setInterval'] });
  try {
    const source = new WebAudioCaptureSource({ status: () => {} });
    await source.open(DEVICE_ID, RATE_HZ);
    const ended = [];
    await source.start(() => {}, (r) => ended.push(r));

    // Blocks keep arriving: each one re-arms the deadline, so a long capture never trips it.
    for (let i = 0; i < 10; i++) {
      nowMs += 1000;
      lastCapNode.post({ l: new Float32Array(1), r: new Float32Array(1), n: 1 });
      t.mock.timers.tick(1000);
    }
    assert.deepEqual(ended, [], 'a delivering stream must never be declared lost');

    // ...then the worklet goes silent while everything else still looks healthy.
    nowMs += 1900;
    t.mock.timers.tick(1900);
    assert.deepEqual(ended, [], 'under the deadline: not yet a loss');

    nowMs += 200;
    t.mock.timers.tick(200);
    assert.deepEqual(ended, [CaptureEndReason.DELIVERY_STALLED]);

    // Said once per open, and the watch is over.
    nowMs += 5000;
    t.mock.timers.tick(5000);
    assert.equal(ended.length, 1);
  } finally {
    t.mock.timers.reset();
    globalThis.performance = realPerf;
  }
});

test('a clean stop is never a stall - the deadline is disarmed before the device is touched', async (t) => {
  const realPerf = globalThis.performance;
  let nowMs = 0;
  globalThis.performance = { now: () => nowMs };
  t.mock.timers.enable({ apis: ['setInterval'] });
  try {
    const source = new WebAudioCaptureSource({ status: () => {} });
    await source.open(DEVICE_ID, RATE_HZ);
    const ended = [];
    await source.start(() => {}, (r) => ended.push(r));

    await source.stop();
    nowMs += 10_000;
    t.mock.timers.tick(10_000);

    assert.deepEqual(ended, [], 'our own stop must never be read as a loss');
  } finally {
    t.mock.timers.reset();
    globalThis.performance = realPerf;
  }
});

test('the seam: a bench\'s in-band sweep mark becomes a position ON THE RING', async () => {
  // Spec 5's MARKER: the mark is dispatched between two batches by the same source that
  // appends them, so the ring's write position at the callback IS sweep sample 0.
  const source = new StubSource();
  let marks = null;
  source.setMarkerListener = (listener) => { marks = listener; };
  const { capture } = sharedOver(source);
  const reader = await capture.acquire();

  assert.equal(reader.getSweepMarkPos(), -1, 'no mark has arrived on this stream');
  source.onBatch({ l: new Float32Array(64), r: new Float32Array(64), n: 64 });
  marks.marker(1);                                  // kind 1 = generator sweep sample 0
  source.onBatch({ l: new Float32Array(16), r: new Float32Array(16), n: 16 });

  assert.equal(reader.getSweepMarkPos(), 64, 'the consumer seeks its cursor exactly there');
  marks.marker(7);                                  // an unknown kind is not a sweep start
  assert.equal(reader.getSweepMarkPos(), 64);
});

// --- the ring's terminal state: refused, claimed once, re-opened clean -------

test('the seam: a dead lane finishes the ring, wakes the consumers, and is never joined', async () => {
  const source = new StubSource();
  const { capture } = sharedOver(source, { publishBatch: true });
  const reader = await capture.acquire();
  const woken = [];
  const listener = () => woken.push(1);
  MessageBus.instance().subscribe(Events.CAPTURE_BATCH_AVAILABLE, listener);
  try {
    source.onCaptureEnded(CaptureEndReason.DEVICE_LOST);

    assert.equal(reader.isFinished(), true, 'every reader over the lane answers terminally');
    assert.equal(capture.isCapturing, false,
      'a reference held over a FINISHED stream is not a capture - the LED must not stay lit');
    assert.equal(woken.length, 1,
      'the death wakes the self-feeding consumers ONCE (Java finish() notifyAll) - no batch will');

    assert.equal(reader.takeFinishedReasonForReport(), CaptureEndReason.DEVICE_LOST);
    assert.equal(reader.takeFinishedReasonForReport(), null,
      'one unplugged device = ONE message: the claim is once across ALL readers');

    // A second death on the same lane is swallowed: one dead device reports through more than
    // one path (the track AND the context) and must be logged once.
    source.onCaptureEnded(CaptureEndReason.DELIVERY_STALLED);
    assert.equal(reader.getFinishedReason(), CaptureEndReason.DEVICE_LOST, 'the first reason wins');

    // The next acquire may NOT hand out a cursor over the dead ring: it gives the device back
    // and opens a fresh lane (a line held across captures is what makes the next one silent).
    source.calls = [];
    const fresh = await capture.acquire();
    assert.deepEqual(source.calls, ['stop', 'close', `open(${DEVICE_ID},${RATE_HZ})`, 'start'],
      'the dead lane is discarded - device back first, then re-opened');
    assert.equal(fresh.isFinished(), false, 'and the fresh cursor is over a LIVE ring');
  } finally {
    MessageBus.instance().unsubscribe(Events.CAPTURE_BATCH_AVAILABLE, listener);
  }
});
