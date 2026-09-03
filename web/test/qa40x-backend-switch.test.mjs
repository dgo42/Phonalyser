/*
 * Phonalyser web - AudioEngine backend-dispatch + prefs-bracket test.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * The wiring that makes the QA40x backend real has no Java unit test to port (Java's AudioBackend
 * is a switch statement exercised only against hardware), and every mistake it can make is silent
 * on the bench rather than loud:
 *
 *   - a scan that probes getUserMedia while the QA40x is the active backend lists the machine's
 *     microphones instead of the analyzer - and never reaches the WebUSB chooser, which only the
 *     Scan click's user activation can open;
 *   - a capture source or playback sink built for the WRONG backend opens a Web Audio line under
 *     the QA40x (or vice versa) and simply measures nothing;
 *   - a backend change bounced in ONE direction leaves the QA40x half-started: it is one
 *     always-duplex device whose ADC does not stream unless the DAC is fed, so the input lane
 *     alone is silence and the output lane alone is a stream nobody reads;
 *   - a switch away that skips the park leaves the analyzer at whatever input range the last
 *     session used - a 0 dBV session leaves the front end at maximum sensitivity, unprotected,
 *     while another backend measures (Java AudioBackend.setActive shuts the deactivated manager
 *     down for exactly this reason).
 *
 * The device manager is a fake (its own contract is covered by qa40x-device-manager.test.mjs) but
 * the handles it hands out are the REAL Qa40xDeviceRef, so the id/label mapping is asserted against
 * the real displayName(). navigator.mediaDevices is a double, installed only for the cases that
 * assert the Web Audio branch.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { AudioBackend } from '../js/audio/backend.js';
import { AudioEngine } from '../js/shell/audio-engine.js';
import { WebAudioCaptureSource } from '../js/audio/web-audio-capture-source.js';
import { WebAudioPlaybackSink } from '../js/generator/web-audio-playback-sink.js';
import { Qa40xCaptureSource } from '../js/qa40x/qa40x-capture-source.js';
import { Qa40xDeviceRef } from '../js/qa40x/qa40x-device-manager.js';
import { Qa40xPlaybackSink } from '../js/qa40x/qa40x-playback-sink.js';
import { testPrefs } from './_test-prefs.mjs';

const WEB_AUDIO = 'WEB_AUDIO';
const QA40X = 'QA40X';
/** The QA403's rate set (Qa40xProtocol.sampleRatesHz) - the highest is what a scan reports. */
const QA403_RATES = [48000, 96000, 192000, 384000];

/** A detached Preferences store on the given backend - the backend is the one property the
 *  dispatch reads; the engine built over it reads every controller setting from the same store. */
function fakePrefs(backend) {
  return testPrefs({ backend });
}

/** Stands in for Qa40xDeviceManager: logs every call the engine is allowed to make on it. */
class FakeManager {
  constructor(log, model = 'QA403') {
    this.log = log;
    this.ref = new Qa40xDeviceRef(0, model, model);
  }

  async listInputDevices() { this.log.push('listInput'); return [this.ref]; }

  async listOutputDevices() { this.log.push('listOutput'); return [this.ref]; }

  listSupportedFormats(device, output) {
    this.log.push(`formats ${device.name} ${output ? 'out' : 'in'}`);
    return QA403_RATES.map((sampleRate) => ({ sampleRate, bits: 24, channels: 2 }));
  }

  acquireEngine(sampleRateHz) { this.log.push(`acquire ${sampleRateHz}`); return {}; }

  async shutdown() { this.log.push('shutdown'); }
}

/** The engine as app.js composes it: over a backend built with the same prefs and manager. */
function engineOver(prefs, qa40xManager = null) {
  return new AudioEngine({ prefs, backend: new AudioBackend({ prefs, qa40xManager }) });
}

/** Replaces the three consumer controllers with recorders - the bracket's only observable. */
function stubConsumers(engine, log) {
  engine._scope = {
    stopCaptureForPrefs: async () => { log.push('scope-stop'); },
    startCaptureForPrefs: async () => { log.push('scope-start'); },
  };
  engine._fft = {
    stopCaptureForPrefs: async () => { log.push('fft-stop'); },
    startCaptureForPrefs: async () => { log.push('fft-start'); },
  };
  engine._gen = {
    stopPlayForPrefs: async () => { log.push('gen-stop'); },
    startPlayForPrefs: async () => { log.push('gen-start'); },
  };
}

/** Runs both bracket phases the way PreferencesDialog.applyAudioPrefs does. */
async function bracket(engine, captureChanged, outputChanged) {
  await engine.beforeApplyBackendChanges(captureChanged, outputChanged);
  await engine.afterApplyBackendChanges(captureChanged, outputChanged);
}

// ---------------------------------------------------------------------------
// navigator.mediaDevices double - only the two calls devices.js makes on the
// Web Audio path (the permission prime + the enumeration). No
// MediaStreamTrackProcessor exists in node, so nativeInputRate falls back to
// the track's getSettings() rate, exactly as it does on a non-Chromium engine.
// ---------------------------------------------------------------------------
const navigatorDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'navigator');

function installMediaDevices(log, rate = 48000) {
  const track = { stop() {}, getSettings: () => ({ sampleRate: rate }) };
  const stream = { getAudioTracks: () => [track], getTracks: () => [track] };
  const mediaDevices = {
    getUserMedia: async () => { log.push('getUserMedia'); return stream; },
    enumerateDevices: async () => {
      log.push('enumerateDevices');
      return [{ kind: 'audioinput', deviceId: 'mic-1', label: 'Fake mic' },
        { kind: 'audiooutput', deviceId: 'spk-1', label: 'Fake speaker' }];
    },
  };
  Object.defineProperty(globalThis, 'navigator', { value: { mediaDevices }, configurable: true, writable: true });
}

function restoreNavigator() {
  Object.defineProperty(globalThis, 'navigator', navigatorDescriptor);
}

// ---------------------------------------------------------------------------
// Scan dispatch (spec §4.3)
// ---------------------------------------------------------------------------

test('scan on WEB_AUDIO probes getUserMedia and never touches the QA40x manager', async (t) => {
  const log = [];
  installMediaDevices(log);
  t.after(restoreNavigator);
  const manager = new FakeManager(log);
  const backend = new AudioBackend({ prefs: fakePrefs(WEB_AUDIO), qa40xManager: manager });

  const { inputs, outputs } = await backend.scanDevices();

  assert.deepEqual(inputs, [{ id: 'mic-1', label: 'Fake mic', nativeRate: 48000 }]);
  assert.deepEqual(outputs, [{ id: 'spk-1', label: 'Fake speaker' }]);
  // A dispatch that fell through to the manager would show listInput/listOutput here.
  assert.equal(log.includes('listInput'), false);
  assert.equal(log.includes('listOutput'), false);
});

test('scan on QA40X enumerates through the manager and never probes getUserMedia', async () => {
  const log = [];
  const manager = new FakeManager(log);
  const backend = new AudioBackend({ prefs: fakePrefs(QA40X), qa40xManager: manager });

  const { inputs, outputs } = await backend.scanDevices();

  // The real Qa40xDeviceRef's display name - it CONTAINS the model, which is what the card
  // resolution (a substring match on the option text) needs to find the device-written card.
  const label = '[0] QA403 (QuantAsylum QA40x) - QuantAsylum';
  assert.deepEqual(inputs, [{ id: 'QA403', label, nativeRate: 384000 }]);
  assert.deepEqual(outputs, [{ id: 'QA403', label }]);
  assert.deepEqual(log.filter((e) => e.startsWith('list')), ['listInput', 'listOutput']);
  // The rate comes off the INPUT format list: the I2S port widens the OUTPUT depths only.
  assert.equal(log.includes('formats QA403 in'), true);
  assert.equal(log.includes('formats QA403 out'), false);
  // No mediaDevices double is installed in this case, so any Web Audio probe would have thrown.
  assert.equal(log.includes('enumerateDevices'), false);
});

test('scan on QA40X with no manager wired fails loudly instead of listing nothing', async () => {
  const backend = new AudioBackend({ prefs: fakePrefs(QA40X) });
  await assert.rejects(() => backend.scanDevices(), /no Qa40xDeviceManager/);
});

// ---------------------------------------------------------------------------
// Per-backend capture source / playback sink (spec §4.1 / §4.2)
// ---------------------------------------------------------------------------

test('the capture source SharedCapture was given resolves the active backend at open()', async () => {
  const log = [];
  const prefs = fakePrefs(QA40X);
  const engine = engineOver(prefs, new FakeManager(log));
  const source = engine._capture._source;   // SharedCapture's injected device

  await source.open('ignored', 96000);
  assert.ok(source.device instanceof Qa40xCaptureSource);
  // The QA40x source's open() IS acquireEngine at the session rate - proof the real device was
  // built and driven, not merely that a constructor picked the right class.
  assert.deepEqual(log, ['acquire 96000']);
  assert.equal(source.sampleRate, 96000);

  // Switch backends: the SAME injected source must hand the next open() to the Web Audio device
  // (which then fails for want of a browser - that failure IS the evidence it was reached).
  prefs.backend.set(WEB_AUDIO);
  await assert.rejects(() => source.open('mic-1', 48000));
  assert.ok(source.device instanceof WebAudioCaptureSource);
  assert.deepEqual(log, ['acquire 96000']);   // no second engine acquire
});

test('the playback sink GeneratorController was given is the active backend\'s', () => {
  const prefs = fakePrefs(WEB_AUDIO);
  const engine = engineOver(prefs, new FakeManager([]));
  const openPlayback = engine._gen._openPlayback;   // the factory the controller calls per start

  assert.ok(openPlayback({}) instanceof WebAudioPlaybackSink);
  prefs.backend.set(QA40X);
  assert.ok(openPlayback({}) instanceof Qa40xPlaybackSink);
});

// ---------------------------------------------------------------------------
// The prefs bracket (spec §4.6)
// ---------------------------------------------------------------------------

test('a backend change bounces BOTH directions even with no device or rate change', async () => {
  const log = [];
  const prefs = fakePrefs(WEB_AUDIO);
  const engine = engineOver(prefs, new FakeManager(log));
  stubConsumers(engine, log);

  prefs.backend.set(QA40X);                 // the Backend combo commits live, before OK
  await bracket(engine, false, false);      // neither device nor rate moved

  assert.deepEqual(log, ['scope-stop', 'fft-stop', 'gen-stop',
    'scope-start', 'fft-start', 'gen-start']);
});

test('without a backend change the per-direction gating stands', async () => {
  const log = [];
  const engine = engineOver(fakePrefs(WEB_AUDIO));
  stubConsumers(engine, log);

  await bracket(engine, true, false);
  assert.deepEqual(log, ['scope-stop', 'fft-stop', 'scope-start', 'fft-start']);

  log.length = 0;
  await bracket(engine, false, true);
  assert.deepEqual(log, ['gen-stop', 'gen-start']);

  log.length = 0;
  await bracket(engine, false, false);
  assert.deepEqual(log, []);
});

test('the backend change is consumed by the bracket - a second apply bounces nothing', async () => {
  const log = [];
  const prefs = fakePrefs(WEB_AUDIO);
  const engine = engineOver(prefs, new FakeManager(log));
  stubConsumers(engine, log);

  prefs.backend.set(QA40X);
  await bracket(engine, false, false);
  log.length = 0;

  await bracket(engine, false, false);   // same backend now - nothing to bounce
  assert.deepEqual(log, []);
});

test('switching AWAY from QA40X parks the analyzer, after both lanes are down and before any restart', async () => {
  const log = [];
  const prefs = fakePrefs(QA40X);
  const engine = engineOver(prefs, new FakeManager(log));
  stubConsumers(engine, log);

  prefs.backend.set(WEB_AUDIO);
  await bracket(engine, false, false);

  assert.deepEqual(log, ['scope-stop', 'fft-stop', 'gen-stop', 'shutdown',
    'scope-start', 'fft-start', 'gen-start']);
});

test('switching TO QA40X parks nothing - there is no live QA40x session to leave', async () => {
  const log = [];
  const prefs = fakePrefs(WEB_AUDIO);
  const engine = engineOver(prefs, new FakeManager(log));
  stubConsumers(engine, log);

  prefs.backend.set(QA40X);
  await bracket(engine, false, false);

  assert.equal(log.includes('shutdown'), false);
});

test('an input-device change on QA40X does not park the device it is still using', async () => {
  const log = [];
  const engine = engineOver(fakePrefs(QA40X), new FakeManager(log));
  stubConsumers(engine, log);

  await bracket(engine, true, false);   // same backend, different device/rate

  assert.equal(log.includes('shutdown'), false);
});

// ---------------------------------------------------------------------------
// Page unload (spec §3.7 shutdown / desktop commit 8d8ee8d)
// ---------------------------------------------------------------------------

test('pagehide and beforeunload both park the analyzer', (t) => {
  const log = [];
  const handlers = new Map();
  const windowDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'window');
  Object.defineProperty(globalThis, 'window', {
    value: { addEventListener: (type, fn) => handlers.set(type, fn) },
    configurable: true, writable: true,
  });
  t.after(() => {
    if (windowDescriptor) Object.defineProperty(globalThis, 'window', windowDescriptor);
    else delete globalThis.window;
  });

  new AudioBackend({ prefs: fakePrefs(QA40X), qa40xManager: new FakeManager(log) });

  assert.deepEqual([...handlers.keys()].sort(), ['beforeunload', 'pagehide']);
  handlers.get('pagehide')();
  handlers.get('beforeunload')();
  assert.deepEqual(log, ['shutdown', 'shutdown']);
});

test('no QA40x manager, no unload hook (nothing to park)', (t) => {
  const handlers = new Map();
  const windowDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'window');
  Object.defineProperty(globalThis, 'window', {
    value: { addEventListener: (type, fn) => handlers.set(type, fn) },
    configurable: true, writable: true,
  });
  t.after(() => {
    if (windowDescriptor) Object.defineProperty(globalThis, 'window', windowDescriptor);
    else delete globalThis.window;
  });

  new AudioBackend({ prefs: fakePrefs(WEB_AUDIO) });

  assert.equal(handlers.size, 0);
});
