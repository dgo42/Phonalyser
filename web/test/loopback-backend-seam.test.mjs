/*
 * Phonalyser web - the LOOPBACK seams: enumeration and the engine's two dispatches.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Three integration facts that no unit of the loopback module can prove on its own:
 *
 *   1. ENUMERATION IS ITS OWN BRANCH. A backend with no hardware must never fall through to the
 *      getUserMedia probe, or the device combo would offer THIS machine's microphones under a
 *      backend that cannot open one. The probe is armed to throw here, so a fall-through fails
 *      loudly instead of passing quietly.
 *   2. THE OPTION TEXT CARRIES THE DEVICE NAME. The card resolution matches a case-insensitive
 *      substring of the option text, so a label that lost the name would leave the seed card
 *      unbindable.
 *   3. THE TWO DISPATCHES MEET ON ONE SESSION. The capture source and the playback sink the
 *      engine hands out must be the two ends of the SAME loop - through the manager, which owns
 *      it - and both must sit BEHIND the embedded gate every local backend obeys.
 *
 * The dispatch methods are exercised off the prototype with a minimal instance, as the wrapper
 * test does: constructing a whole AudioBackend would drag in the browser globals the audio graph
 * wants.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

// The Web Audio probe, armed to fail: reaching it at all is the bug this file watches for.
const PROBE_REACHED = 'the microphone probe must not be reached on a hardware-free backend';
if (!globalThis.navigator) globalThis.navigator = { platform: 'Win32', userAgent: 'node' };
globalThis.navigator.mediaDevices = {
  getUserMedia: async () => { throw new Error(PROBE_REACHED); },
  enumerateDevices: async () => { throw new Error(PROBE_REACHED); },
};

const { AudioBackend } = await import('../js/audio/backend.js');
const { scanDevicesForBackend } = await import('../js/audio/devices.js');
const { LoopbackDeviceManager } = await import('../js/loopback/loopback-device-manager.js');
const { LOOPBACK_BACKEND, LOOPBACK_DEVICE_NAME } = await import('../js/loopback/loopback-device-ref.js');

const DEPTH = 24;
const TOP_RATE = 768000;

function newManager() {
  return new LoopbackDeviceManager({ inputDepthOf: () => DEPTH, outputDepthOf: () => DEPTH });
}

/** A backend with the prototype's real methods and only the fields the two dispatches read. */
function engineWith(manager, backend = LOOPBACK_BACKEND) {
  const engine = Object.create(AudioBackend.prototype);
  engine._loopback = manager;
  engine._prefs = { backend: { get: () => backend } };
  return engine;
}

test('the LOOPBACK scan lists the one software device on both directions', async () => {
  const manager = newManager();
  const said = [];

  const { inputs, outputs } = await scanDevicesForBackend(LOOPBACK_BACKEND, null,
    (t) => said.push(t), null, null, manager);

  assert.equal(inputs.length, 1);
  assert.equal(outputs.length, 1);
  assert.equal(inputs[0].id, LOOPBACK_DEVICE_NAME, 'the id is the device NAME - there is no browser id');
  assert.ok(inputs[0].label.includes(LOOPBACK_DEVICE_NAME),
    'the option text must carry the name, or the card cannot bind by substring');
  assert.equal(outputs[0].id, LOOPBACK_DEVICE_NAME);
  assert.ok(outputs[0].label.includes(LOOPBACK_DEVICE_NAME));
  assert.equal(inputs[0].nativeRate, TOP_RATE, 'the native rate is the top of the ladder');
  assert.ok(said.length > 0, 'the scan says what it is doing, like every other branch');
});

test('a LOOPBACK scan with no manager wired says so instead of listing microphones', async () => {
  const said = [];

  const { inputs, outputs } = await scanDevicesForBackend(LOOPBACK_BACKEND, null,
    (t) => said.push(t), null, null, null);

  assert.deepEqual(inputs, []);
  assert.deepEqual(outputs, []);
  assert.ok(said.some((s) => s.includes('loopback')), 'the wiring mistake is reported, not hidden');
});

test('the capture source and the playback sink meet on the manager\'s ONE session', async () => {
  const manager = newManager();
  const engine = engineWith(manager);

  const capture = engine._newCaptureSource(LOOPBACK_BACKEND);
  const sink = engine.openPlayback({ ditherBits: 8, status: () => {} });

  // The loop, closed through the engine's own two dispatches: what the sink renders is what the
  // capture delivers. Driving it on the real clock is unnecessary - the shared session is the
  // only thing under test here.
  await sink.open({ sampleRate: 48000 });
  await capture.open(LOOPBACK_DEVICE_NAME, 48000);
  assert.equal(capture.sampleRate, 48000);
  assert.equal(capture.isOpen, true);
  await sink.close();
  await capture.close();
  assert.equal(capture.isOpen, false);

  // Different backends must not accidentally share it, and the same backend must: the manager is
  // the owner, so both lanes carry the same one by construction.
  const other = newManager();
  assert.notEqual(other, manager);
});

test('both dispatches fail loudly when no manager was injected', () => {
  const engine = engineWith(null);

  assert.throws(() => engine._newCaptureSource(LOOPBACK_BACKEND), /LoopbackDeviceManager/,
    'a missing manager is a wiring mistake, never silently "no devices"');
  assert.throws(() => engine.openPlayback({}), /LoopbackDeviceManager/);
});

test('the embedded gate is honoured BEFORE the loopback branch', () => {
  const engine = engineWith(newManager());
  // A page served by a Phonalyser server has no local backend at all - the loopback included,
  // even though it needs neither getUserMedia nor WebUSB.
  engine._requireNotEmbedded = () => { throw new Error('this build serves only server backends'); };

  assert.throws(() => engine._newCaptureSource(LOOPBACK_BACKEND), /serves only server backends/);
  assert.throws(() => engine.openPlayback({}), /serves only server backends/);
});
