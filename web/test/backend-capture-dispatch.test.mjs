/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * The backend dispatch's FORWARDING WRAPPER - the CaptureSource every real acquire actually
 * gets, which resolves the concrete device at open() so a backend switch lands without
 * replacing the SharedCapture whose ring and cursors the consumers already hold.
 *
 * WHY THIS FILE EXISTS. The seam tests drive a StubSource straight into SharedCapture, so the
 * wrapper was never in their path - and it silently dropped start()'s SECOND argument, the
 * capture-ended callback the whole capture-death machinery hangs on. A dead device could
 * therefore never finish its ring on any real acquire, and the trace went on drawing a flat
 * line: the exact failure that seam exists to end. Everything the wrapper must pass through is
 * proved HERE, through the wrapper, never on the device double.
 *
 * The wrapper is exercised off the prototype with a minimal `this`: it needs only the backend
 * name and the per-backend factory, and constructing a whole AudioBackend would drag in the
 * browser globals the audio graph wants.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

import { AudioBackend } from '../js/audio/backend.js';
import { DeviceFailureReason } from '../js/enums/device-failure-reason.js';

/** A concrete capture device double - the thing _newCaptureSource returns. */
class FakeDevice {
  constructor(name, { classify = true, markers = true } = {}) {
    this.name = name;
    this.opened = null;
    this.onBatch = null;
    this.onCaptureEnded = null;
    this.markerListener = null;
    this.stopped = 0;
    this.closed = 0;
    this.sampleRate = 0;
    this.isOpen = false;
    if (classify) this.classifyFailure = (err) => (err && err.name === 'NotReadableError'
      ? DeviceFailureReason.DEVICE_IN_USE : DeviceFailureReason.UNKNOWN);
    if (markers) this.setMarkerListener = (l) => { this.markerListener = l; };
  }
  async open(deviceId, rate) { this.opened = [deviceId, rate]; this.sampleRate = rate; this.isOpen = true; }
  async start(onBatch, onCaptureEnded) { this.onBatch = onBatch; this.onCaptureEnded = onCaptureEnded; }
  async stop() { this.stopped++; }
  async close() { this.closed++; this.isOpen = false; this.sampleRate = 0; }
}

/** The wrapper, built over a scripted backend name and factory. */
function wrapperOver(devices) {
  const state = { backend: 'WEB_AUDIO', built: [] };
  const engine = {
    activeBackend: () => state.backend,
    _newCaptureSource: (backend) => {
      const device = devices[backend];
      state.built.push(backend);
      return device;
    },
  };
  return { source: AudioBackend.prototype.openCapture.call(engine), state };
}

test('a STALE bench source is rebuilt - a dropped session does not need a page reload',
  async () => {
    // The maintainer's bench: the session dies (a silent socket close), and every capture start
    // afterwards failed with "the connection to ws://... is closed", classified UNKNOWN, until
    // the page was reloaded. The wrapper CACHES the source, so it went on talking to the closed
    // socket forever. A source that says it is stale must be rebuilt instead.
    const dead = new FakeDevice('bench-1');
    dead.isStale = () => true;
    const fresh = new FakeDevice('bench-2');
    fresh.isStale = () => false;
    const queue = [dead, fresh];
    const state = { backend: 'net:JAVASOUND', built: [] };
    const engine = {
      activeBackend: () => state.backend,
      _newCaptureSource: (backend) => { state.built.push(backend); return queue.shift(); },
    };
    const source = AudioBackend.prototype.openCapture.call(engine);

    await source.open('Bench ADC', 48000);          // the first start: builds the source
    await source.open('Bench ADC', 48000);          // the next one, after the session died

    assert.equal(state.built.length, 2, 'the stale source was replaced, not re-used');
    assert.deepEqual(fresh.opened, ['Bench ADC', 48000], 'and the new one carries the open');
  });

test('a source that is NOT stale is kept - a healthy session is not rebuilt per start',
  async () => {
    const device = new FakeDevice('bench');
    device.isStale = () => false;
    const { source, state } = wrapperOver({ 'net:JAVASOUND': device });
    state.backend = 'net:JAVASOUND';

    await source.open('Bench ADC', 48000);
    await source.open('Bench ADC', 48000);

    assert.deepEqual(state.built, ['net:JAVASOUND'], 'built once');
  });

test('a source that cannot say whether it is stale is never treated as stale', async () => {
  // Every LOCAL device: no session behind it, no isStale member, and the wrapper must not
  // start rebuilding it on every open.
  const device = new FakeDevice('web');
  const { source, state } = wrapperOver({ WEB_AUDIO: device });

  await source.open('mic-1', 48000);
  await source.open('mic-1', 48000);

  assert.deepEqual(state.built, ['WEB_AUDIO']);
});

test('start() forwards BOTH callbacks - the batches AND the capture-ended seam', async () => {
  const device = new FakeDevice('web');
  const { source } = wrapperOver({ WEB_AUDIO: device });
  await source.open('mic-1', 48000);

  const batches = [];
  const ended = [];
  await source.start((b) => batches.push(b), (r) => ended.push(r));

  assert.equal(typeof device.onBatch, 'function');
  assert.equal(typeof device.onCaptureEnded, 'function',
    'the wrapper used to drop this argument, and a dead device could then never finish its ring');
  device.onBatch({ n: 1 });
  device.onCaptureEnded({ name: 'DEVICE_LOST' });
  assert.equal(batches.length, 1);
  assert.deepEqual(ended, [{ name: 'DEVICE_LOST' }], 'the end reaches the ring through the wrapper');
});

test('classifyFailure reaches the device that owns the native error', async () => {
  const device = new FakeDevice('web');
  const { source } = wrapperOver({ WEB_AUDIO: device });
  await source.open('mic-1', 48000);

  const busy = new Error('held'); busy.name = 'NotReadableError';
  assert.equal(source.classifyFailure(busy), DeviceFailureReason.DEVICE_IN_USE,
    'without the forward every open failure read UNKNOWN - the operator lost the reason');
});

test('a device that cannot classify still answers the SPI default, not undefined', async () => {
  // The wrapper always HAS the method, so the caller's "does it have one" test can no longer be
  // the fallback: the default has to be answered here.
  const device = new FakeDevice('plain', { classify: false });
  const { source } = wrapperOver({ WEB_AUDIO: device });
  await source.open('mic-1', 48000);

  assert.equal(source.classifyFailure(new Error('whatever')), DeviceFailureReason.UNKNOWN);
});

test('classifyFailure before any open answers UNKNOWN rather than throwing', () => {
  const { source } = wrapperOver({ WEB_AUDIO: new FakeDevice('web') });
  assert.equal(source.classifyFailure(new Error('x')), DeviceFailureReason.UNKNOWN);
});

test('setMarkerListener reaches an in-band-marker device, and is a no-op for one without', async () => {
  const marked = new FakeDevice('net');
  const { source } = wrapperOver({ WEB_AUDIO: marked });
  await source.open('remote', 48000);
  const listener = { marker: () => {}, gap: () => {} };

  source.setMarkerListener(listener);
  assert.equal(marked.markerListener, listener, 'a bench-rendered sweep\'s mark must reach the ring');

  const plain = new FakeDevice('web', { markers: false });
  const other = wrapperOver({ WEB_AUDIO: plain });
  await other.source.open('mic-1', 48000);
  other.source.setMarkerListener(listener);   // must not throw for a device with no marker seam
});

test('the concrete device is resolved at OPEN, and replaced when the backend moved', async () => {
  const web = new FakeDevice('web');
  const qa = new FakeDevice('qa');
  const { source, state } = wrapperOver({ WEB_AUDIO: web, QA40X: qa });

  await source.open('mic-1', 48000);
  assert.equal(source.device, web);
  assert.equal(source.sampleRate, 48000);
  assert.equal(source.isOpen, true);

  await source.open('mic-1', 48000);
  assert.deepEqual(state.built, ['WEB_AUDIO'], 'the same backend keeps the same device');

  state.backend = 'QA40X';
  await source.open('analyzer', 96000);
  assert.equal(source.device, qa, 'a backend switch lands WITHOUT replacing the SharedCapture');
  assert.deepEqual(state.built, ['WEB_AUDIO', 'QA40X']);
});

test('stop/close before any open are null-guarded - a failed acquire tears down too', async () => {
  const { source } = wrapperOver({ WEB_AUDIO: new FakeDevice('web') });

  // SharedCapture's teardown runs stop-then-close even when the open never happened; a
  // TypeError here would mask the open failure it is in the middle of reporting.
  assert.equal(source.stop(), undefined);
  assert.equal(source.close(), undefined);
  assert.equal(source.isOpen, false);
  assert.equal(source.sampleRate, 0);
});
