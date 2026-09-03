/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * G1: WHAT the operator is told when a bench refuses a device.
 *
 * The bench's own report was one sentence long: "The bench refused to open the device: reason
 * unknown" - for a device another client was holding, for a malformed request, for an
 * unsupported call, for a stale index. Every one of those is a spec-4.2 CODE without the
 * optional `reason` field, so the client read UNKNOWN and rendered the one string UNKNOWN has.
 * The refusal itself always carried more (NetConnection.refusal composes code + the server's
 * message + " (held by <holder>)"); the wording boundaries threw it away by taking the
 * classified reason alone.
 *
 * These tests run the WHOLE chain rather than a boundary in isolation, because the bug lived
 * between the links: a real NetConnection over a scripted socket (so the real refusal() runs), a
 * real NetDeviceManager as the lock register, the real net lane sources, and the real
 * SharedCapture / GeneratorController that produce the sentence. Only the socket and the two
 * device-shaped answers are doubles.
 *
 * The socket double here is deliberately NOT the full MockBench of net-connection.test.mjs: this
 * file needs a handshake and a refusal, never a binary frame or a keepalive death, and a copy of
 * a bench that can do everything would invite tests that quietly test the double.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

import { NetConnection } from '../js/net/net-connection.js';
import { NetDeviceManager } from '../js/net/net-device-manager.js';
import { NetCaptureSource } from '../js/net/net-capture-source.js';
import { NetPlaybackSink } from '../js/net/net-playback-sink.js';
import { NetPreferences, makeServerEntry } from '../js/net/net-preferences.js';
import { MessageType, NetFields, ErrorCode, NetProto } from '../js/net/net-proto.js';
import { SharedCapture } from '../js/audio/shared-capture.js';
import { GeneratorController } from '../js/generator/generator-controller.js';
import { testPrefs } from './_test-prefs.mjs';
import { DeviceFailureReason } from '../js/enums/device-failure-reason.js';
import { deviceErrorText } from '../js/audio/device-failure-reason.js';
import { MessageBus } from '../js/bus/message-bus.js';
import { Events } from '../js/bus/events.js';
import { initBase, t } from '../js/i18n/i18n.js';

// The REAL English bundle, off the disk: what the operator is told is the whole subject here, so
// a test that asserted against key names would pass while the screen said nothing.
const I18N_DIR = fileURLToPath(new URL('../i18n/', import.meta.url));
globalThis.fetch = async (path) => {
  const name = String(path).replace(/^i18n\//, '');
  try {
    return { ok: true, text: async () => readFileSync(I18N_DIR + name, 'utf8') };
  } catch {
    return { ok: false, text: async () => '' };
  }
};
await initBase();

/** Time in the test's hands (Java FakeTicker) - nothing pings on its own. */
class FakeTicker {
  start(periodMs, task) { this.periodMs = periodMs; this.task = task; }
  stop() { this.task = null; }
}

/**
 * The bench at the socket seam: it completes the handshake, answers backend.list /
 * backend.select with one device, and refuses whatever `refuse` names - spec 4.2's error object,
 * verbatim, including the extra `by` a DEVICE_LOCKED carries.
 */
class ScriptedBench {
  constructor() {
    this.sent = [];
    /** message type -> the spec-4.2 error object to answer it with. */
    this.refuse = {};
    this.onopen = null; this.onmessage = null; this.onclose = null; this.onerror = null;
  }

  factory() { return () => this; }

  accept() { if (this.onopen) this.onopen(); }

  send(text) {
    const m = JSON.parse(text);
    this.sent.push(m);
    const refusal = this.refuse[m.t];
    if (refusal) { this._deliver({ t: MessageType.RESP, id: m.id, ok: false, error: refusal }); return; }
    this._deliver({ t: MessageType.RESP, id: m.id, ok: true, data: this._dataFor(m) });
  }

  close() { }

  _dataFor(m) {
    switch (m.t) {
      case MessageType.HELLO:
        return { [NetFields.PROTO]: NetProto.PROTO_VERSION, [NetFields.SERVER_ID]: 'server-uuid-1',
          [NetFields.NAME]: 'Bench QA403', [NetFields.APP]: '1.1.1',
          [NetFields.CAPS]: ['qa40x', 'gen', 'files'] };
      case MessageType.BACKEND_LIST:
        return { [NetFields.BACKENDS]: [{ [NetFields.BACKEND]: 'JAVASOUND',
          [NetFields.DISPLAY_NAME]: 'Java Sound', [NetFields.AVAILABLE]: true,
          [NetFields.OPERATIONAL]: true, [NetFields.HAS_BIT_DEPTH]: true }] };
      case MessageType.BACKEND_SELECT:
        return { [NetFields.BACKEND]: 'JAVASOUND', [NetFields.DEVICES]: [
          this._device(true), this._device(false)] };
      default:
        return {};
    }
  }

  _device(input) {
    return {
      [NetFields.INDEX]: 0, [NetFields.NAME]: 'Cubilux USB Audio',
      [NetFields.DESCRIPTION]: 'desc', [NetFields.VENDOR]: 'Cubilux',
      [NetFields.INPUT]: input, [NetFields.OUTPUT]: !input,
      [NetFields.HAS_BIT_DEPTH]: true, [NetFields.LOCK]: null,
      [NetFields.FORMATS]: [{ [NetFields.RATE]: 48000, [NetFields.BITS]: 24,
        [NetFields.CHANNELS]: 2 }],
    };
  }

  _deliver(message) { if (this.onmessage) this.onmessage({ data: JSON.stringify(message) }); }
}

/** A connected manager routed at the bench's one backend, plus the bench to script. */
async function bench() {
  const socket = new ScriptedBench();
  const connection = new NetConnection({
    url: 'ws://bench.local:8377/', clientName: 'Web client', clientApp: 'Phonalyser.web/1.1.1',
    ticker: new FakeTicker(), socketFactory: socket.factory(),
  });
  const manager = new NetDeviceManager({
    preferences: new NetPreferences(),
    openConnection: async () => { const opening = connection.open(5000); socket.accept(); await opening; return connection; },
  });
  await manager.connect(makeServerEntry('server-uuid-1', 'Bench QA403', '10.0.0.1', 8377, true));
  await manager.selectBackend('JAVASOUND');
  return { socket, connection, manager };
}

/** Collects the AUDIO_DEVICE_ERROR payloads published while `body` runs. */
async function withDeviceErrors(body) {
  const seen = [];
  const listener = (p) => seen.push(p);
  MessageBus.instance().subscribe(Events.AUDIO_DEVICE_ERROR, listener);
  try { await body(); } finally { MessageBus.instance().unsubscribe(Events.AUDIO_DEVICE_ERROR, listener); }
  return seen;
}

function capturePrefs() {
  return testPrefs({ inputDeviceName: 'remote', inputSampleRate: 48000, fftLength: 65536,
    outputSampleRate: 48000, genSignalForm: 'SINE', genFrequencyHz: 1000, genAmplitudeVrms: 0.5,
    dacFsVoltageAmpl: 1.0 });
}

test('B8/B9 capture: a device somebody else holds names the CODE and the HOLDER, never '
  + '"reason unknown"', async () => {
  const { socket, manager } = await bench();
  // Spec 4.3: the lock is taken at open, and a second client is refused THERE. DEVICE_LOCKED
  // carries no `reason` field (§4.2 attaches one to DEVICE_ERROR alone), which is exactly the
  // case that used to render as the UNKNOWN string.
  socket.refuse[MessageType.DEVICE_ACQUIRE] = { code: ErrorCode.DEVICE_LOCKED,
    message: 'device already in use', by: "Developer's laptop" };
  const device = manager.listInputDevices()[0];
  const capture = new SharedCapture({
    prefs: capturePrefs(),
    captureSource: new NetCaptureSource({ connection: manager.connection, device, owner: manager }),
  });

  const errors = await withDeviceErrors(async () => {
    assert.equal(await capture.acquire(), null, 'a refused open hands out no reader');
  });

  assert.equal(errors.length, 1, 'the shell is told once');
  const shown = errors[0].message;
  assert.equal(errors[0].direction, 'input');
  assert.equal(errors[0].reason, DeviceFailureReason.UNKNOWN.name,
    'the machine-readable half is untouched - the bench sent no reason, and inventing one would '
    + 'make every later branch lie');
  assert.match(shown, /DEVICE_LOCKED/, 'spec 4.2 code');
  assert.match(shown, /device already in use/, "the server's own message");
  assert.match(shown, /held by Developer's laptop/, 'WHO holds it - the actionable half');
  assert.doesNotMatch(shown, /reason unknown/);
  assert.equal(capture.getLastStartError(), shown,
    'the freqresp lane reads this field (backend.getMeasurementStartError), so the two must be '
    + 'the same sentence');
});

test('B8 capture: a bench that DID classify the failure keeps the operator\'s word', async () => {
  const { socket, manager } = await bench();
  // The one refusal that already worked on his bench: DEVICE_ERROR with §4.2's optional reason.
  // The localized word is the operator's language and the desktop's; the wire sentence is the
  // server's. The word wins.
  socket.refuse[MessageType.CAPTURE_OPEN] = { code: ErrorCode.DEVICE_ERROR,
    message: 'javax.sound.sampled.LineUnavailableException: 48000/24 refused',
    reason: DeviceFailureReason.FORMAT_UNSUPPORTED.name };
  const device = manager.listInputDevices()[0];
  const capture = new SharedCapture({
    prefs: capturePrefs(),
    captureSource: new NetCaptureSource({ connection: manager.connection, device, owner: manager }),
  });

  const errors = await withDeviceErrors(async () => {
    assert.equal(await capture.acquire(), null);
  });

  assert.equal(errors[0].reason, DeviceFailureReason.FORMAT_UNSUPPORTED.name);
  assert.equal(errors[0].message, t('device.error.reason.formatUnsupported'));
  assert.doesNotMatch(errors[0].message, /LineUnavailableException/,
    'a driver class name is not a reason; it stays in the log');
});

test('B9/F2 generator: the bench\'s refusal fills the {0} of the remote-refused sentence',
  async () => {
    const { socket, manager } = await bench();
    socket.refuse[MessageType.GEN_OPEN] = { code: ErrorCode.DEVICE_LOCKED,
      message: 'device already in use', by: "Developer's laptop" };
    const device = manager.listOutputDevices()[0];
    const gen = new GeneratorController(capturePrefs(), {
      openPlayback: () => new NetPlaybackSink({ connection: manager.connection, device, owner: manager }),
    });

    let failed;
    const errors = await withDeviceErrors(async () => { failed = await gen.startGenerator(); });

    assert.equal(errors.length, 1);
    assert.equal(errors[0].direction, 'output');
    assert.equal(failed, errors[0].message, 'one sentence, whether it is read or published');
    assert.match(failed, /^The bench refused to open the output device: /,
      'a server that said no is not "could not open the output device" - the frame stays');
    assert.match(failed, /DEVICE_LOCKED/);
    assert.match(failed, /held by Developer's laptop/);
    assert.doesNotMatch(failed, /reason unknown/);
  });

test('a LOCAL lane still shows the reason word alone - no raw driver text can reach the screen',
  async () => {
    // The Web Audio sink implements no refusalText: its errors are the browser's, and a
    // DOMException name says the operator nothing (the standing rule, 2026-08-02). The boundary
    // must therefore fall back to the reason word even though the failure HAS a message.
    const busy = Object.assign(new Error('Could not start audio source'), { name: 'NotReadableError' });
    const gen = new GeneratorController(capturePrefs(), {
      openPlayback: () => ({
        classifyFailure: () => DeviceFailureReason.DEVICE_IN_USE,
        open: async () => { throw busy; },
        close: async () => {},
      }),
    });

    const failed = await gen.startGenerator();

    assert.equal(failed, t('generator.error.openDeviceFailed', t('device.error.reason.inUse')));
    assert.doesNotMatch(failed, /NotReadableError|Could not start audio source/);
  });

// ---------------------------------------------------------------------------
// B26 - the shell's alert says what actually happened, not what usually happens
// ---------------------------------------------------------------------------
//
// The alert used to fall back on three pre-composed web-only sentences, each ending "it appears
// to be in use by another application" - printed over a device that was NOT FOUND, or one that
// had been unplugged, whenever the publisher sent no sentence of its own. The fallback now
// composes from the SAME reason table every publisher classifies against, using the desktop's
// keys, so the reason on screen is the reason that was reported.

test('B26: the alert body names the REAL reason, never the in-use guess', () => {
  for (const reason of [DeviceFailureReason.DEVICE_NOT_FOUND, DeviceFailureReason.DEVICE_DISCONNECTED,
    DeviceFailureReason.FORMAT_UNSUPPORTED, DeviceFailureReason.DEVICE_NOT_ANSWERING]) {
    const body = deviceErrorText({ direction: 'input', reason: reason.name });
    assert.equal(body, t(reason.i18nKey), `${reason.name} must state itself`);
    assert.notEqual(body, t('device.error.reason.inUse'));
  }
});

test('B26: the playback lane fills the desktop\'s own carrier sentence', () => {
  assert.equal(deviceErrorText({ direction: 'output', reason: DeviceFailureReason.DEVICE_NOT_FOUND.name }),
    t('generator.error.openDeviceFailed', t('device.error.reason.notFound')),
    'GeneratorPane\'s wording, with the reason in its {0} - not a web-only sentence');
});

test('B26: a payload that knows nothing says so, rather than blaming another application', () => {
  assert.equal(deviceErrorText(null), t('device.error.reason.unknown'));
  assert.equal(deviceErrorText({ direction: 'input' }), t('device.error.reason.unknown'));
  assert.equal(deviceErrorText({ direction: 'input', reason: 'A_REASON_FROM_A_NEWER_BUILD' }),
    t('device.error.reason.unknown'), 'an unrecognised name is UNKNOWN, never a guess');
});
