/*
 * Phonalyser web - the loopback manager: the one device, the format table, the one session.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Four claims of the manager, each of which the Preferences dialog or the audio path would
 * otherwise expose only as a puzzling absence:
 *
 *   1. THE FORMAT TABLE. The rate ladder times the four depths, with the .sampleRate and .bits
 *      fields the dialog reads. This backend enumerates its own formats, so its ladder is not
 *      the Web Audio one and carries rates a browser context will not grant.
 *   2. THE DEVICE IDENTITY. The name is what the device catalogue binds a card to by SUBSTRING,
 *      so a bare Loopback would also claim the loopback endpoints Windows exposes.
 *   3. ONE SESSION PER MANAGER. A playback and a capture opened from the SAME manager are the
 *      two ends of the same loop; lanes from different managers meet nothing. This is the whole
 *      backend: get it wrong and the capture silently delivers its own silence forever, which
 *      looks exactly like a working bench with the generator switched off.
 *   4. ACQUIRE BUILDS ONCE AND MOVES. A lane that goes live at another format takes the WHOLE
 *      session with it - the loop has one clock and one depth to move.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

import { GenSignalForm } from '../js/enums/gen-signal-form.js';
import { LoopbackDeviceManager } from '../js/loopback/loopback-device-manager.js';
import { LoopbackDeviceRef } from '../js/loopback/loopback-device-ref.js';

const RATE = 48000;
const BLOCK_MS = 20;
const FLOOR_LSB = 0.5;
const FLOOR_TOLERANCE_LSB = 0.06;
const DEPTH = 16;
/** A value no dithered block can carry - the playback lane's provenance mark. */
const STAMP = 0.25;

const EXPECTED_RATES = [8000, 11025, 16000, 22050, 32000, 44100, 48000, 88200, 96000,
  176400, 192000, 352800, 384000, 705600, 768000];
const EXPECTED_DEPTHS = [16, 20, 24, 32];

const SILENT_SPEC = Object.freeze({
  form: GenSignalForm.SINE,
  frequency: 1000,
  amplitudeVRms: 0.0,
  dacFsVoltageAmpl: 1.0,
  ditherBits: 0,
  outputChannels: 'BOTH',
  rightLaneScale: 1.0,
});

/** A manager whose two depth suppliers answer constants - what the app wires to the per-backend
 *  prefs block. armBus is off: these cases drive the manager, not the Preferences constraint. */
function managerAt(depth = DEPTH) {
  return new LoopbackDeviceManager({
    inputDepthOf: () => depth, outputDepthOf: () => depth, armBus: false,
  });
}

test('the format table is the rate ladder times the four depths, both directions', () => {
  const manager = managerAt();
  const [device] = manager.listInputDevices();

  for (const output of [false, true]) {
    const formats = manager.listSupportedFormats(device, output);
    assert.equal(formats.length, EXPECTED_RATES.length * EXPECTED_DEPTHS.length);
    assert.deepEqual([...new Set(formats.map((f) => f.sampleRate))], EXPECTED_RATES);
    assert.deepEqual([...new Set(formats.map((f) => f.bits))], EXPECTED_DEPTHS);
    for (const f of formats) assert.equal(f.channels, 2);
  }
  // 20 bits rides right-aligned in a 3-byte container - a real card format, not a rounded 24.
  const twenty = manager.listSupportedFormats(device, false).find((f) => f.bits === 20);
  assert.equal(twenty.frameBytes, 6);
  assert.equal(manager.hasBitDepth(), true, 'the depth IS this backend\'s subject');
  assert.deepEqual(manager.listSupportedFormats({ name: 'someone else\'s handle' }, false), [],
    'a handle from another backend gets no formats');
});

test('one device, both directions, named so a card cannot bind to a real endpoint', () => {
  const manager = managerAt();

  const inputs = manager.listInputDevices();
  const outputs = manager.listOutputDevices();
  assert.equal(inputs.length, 1);
  assert.equal(outputs.length, 1);
  assert.equal(inputs[0], outputs[0], 'both directions are the same loop, so the same handle');

  const device = inputs[0];
  assert.ok(device instanceof LoopbackDeviceRef);
  assert.equal(device.index, 0);
  assert.equal(device.name, 'Phonalyser Loopback');
  assert.equal(device.description(), 'Digital loopback (playback returns to capture)');
  assert.equal(device.vendor(), 'Phonalyser');
  assert.equal(device.backend(), 'LOOPBACK');
  assert.equal(device.isInput(), true);
  assert.equal(device.isOutput(), true);
  assert.equal(device.displayName(),
    '[0] Phonalyser Loopback (Digital loopback (playback returns to capture)) - Phonalyser');

  assert.equal(manager.getDeviceByIndex(0, false), device);
  assert.throws(() => manager.getDeviceByIndex(1, false), /index 1/);
});

test('acquireEngine builds ONE session and MOVES it when a lane asks for another format', () => {
  const manager = managerAt();
  const first = manager.acquireEngine(RATE, DEPTH);
  assert.equal(manager.acquireEngine(RATE, DEPTH), first, 'the same format gets the same session');
  assert.equal(first.sampleRate, RATE);
  assert.equal(first.bitDepth, DEPTH);

  const moved = manager.acquireEngine(96000, 24);
  assert.equal(moved, first, 'a format change MOVES the session, it does not build a second one');
  assert.equal(first.sampleRate, 96000, 'the whole session followed the lane that asked');
  assert.equal(first.bitDepth, 24);
});

test('the two lanes of ONE manager meet on ONE session - and lanes of two managers do not',
  async (t) => {
    const realPerformance = globalThis.performance;
    const clock = { ms: 0 };
    globalThis.performance = { now: () => clock.ms };
    t.mock.timers.enable({ apis: ['setInterval'] });
    try {
      // Both sessions render a dithered floor, so the floor alone cannot tell them apart: the
      // playback lane is STAMPED, and what the capture receives is asked whether it came from
      // there. The loop, then the negative control - the same script against two managers.
      const looped = await runLoop(clock, t, { shared: true, stamp: STAMP });
      const split = await runLoop(clock, t, { shared: false, stamp: STAMP });

      assert.equal(looped.length, 2);
      for (const batch of looped) {
        assert.equal(batch.l[0], STAMP, 'the capture receives what the PLAYBACK lane rendered');
        assert.equal(batch.r[0], STAMP);
      }

      assert.equal(split.length, 2, 'the capture never starves - the silence keeps arriving');
      for (const batch of split) {
        assert.notEqual(batch.l[0], STAMP,
          'with no shared session the capture only ever receives its own session\'s silence');
      }

      // And the played block carries the depth's floor: the dither argument handed to
      // openPlayback was 0 and is discarded, so the last-bit default holds.
      const floored = await runLoop(clock, t, { shared: true, stamp: null });
      const scale = Math.pow(2, DEPTH - 1) - 1;
      for (const batch of floored) {
        let sum = 0;
        for (let i = 0; i < batch.n; i++) sum += (batch.l[i] * scale) * (batch.l[i] * scale);
        const rms = Math.sqrt(sum / batch.n);
        assert.ok(Math.abs(rms - FLOOR_LSB) < FLOOR_TOLERANCE_LSB,
          `the played block carries the ${DEPTH}-bit floor, measured ${rms}`);
      }
    } finally {
      t.mock.timers.reset();
      globalThis.performance = realPerformance;
    }
  });

/** Opens a capture and a playback - on one manager, or on two - and returns what the capture
 *  received. A `stamp` replaces the playback lane's render with a recognisable constant, which is
 *  what proves the block crossed from THAT lane rather than from a session's own silence. */
async function runLoop(clock, t, { shared, stamp }) {
  const manager = managerAt();
  const other = shared ? manager : managerAt();
  const [device] = manager.listInputDevices();
  const capture = manager.openCapture(device);
  const playback = other.openPlayback(other.listOutputDevices()[0], 0);
  const batches = [];
  await capture.open(device.name, RATE);
  await playback.open({ sampleRate: RATE });
  await playback.start(SILENT_SPEC);
  if (stamp != null) {
    // An own property shadows the prototype method, so the session's pull lands here.
    playback.nextBlock = (block) => { block.l.fill(stamp); block.r.fill(stamp); };
  }
  await capture.start((b) => batches.push({ l: Array.from(b.l), r: Array.from(b.r), n: b.n }), () => {});
  for (let i = 0; i < 2; i++) {
    clock.ms += BLOCK_MS;
    t.mock.timers.tick(BLOCK_MS);
  }
  await playback.close();
  await capture.close();
  return batches;
}
