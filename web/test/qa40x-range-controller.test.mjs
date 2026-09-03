/*
 * Phonalyser web - Qa40xRangeController routing contract test.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * The Java class has no unit test (it is wired through the live MessageBus and a real
 * device manager); these assertions pin the four gates its listener applies - active
 * backend, open device, resolvable label, direction - plus the arming that makes it fire
 * at all. One controller is built for the whole file, exactly as the device manager builds
 * one, and the fake manager's state is moved per case.
 *
 * The FIRST case goes through the real bus, so a missing subscribe() or a mistyped event
 * name fails. The ignore cases call the listener DIRECTLY: MessageBus.publish swallows a
 * throwing subscriber, so a bus-driven "nothing was routed" assertion would stay green
 * over a crash.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { Events } from '../js/bus/events.js';
import { MessageBus } from '../js/bus/message-bus.js';
import { Qa40xRangeController } from '../js/qa40x/qa40x-range-controller.js';

const QA40X = 'QA40X';
const WEB_AUDIO = 'WEB_AUDIO';
/** The open device's card name - the model name the Java manager reports. */
const CARD = 'QA403';

/** Only in inputRangeDbvValues() (0..42 dBV in 6 dB steps). */
const INPUT_ONLY_LABEL = '42 dBV';
/** Only in outputRangeDbvValues() (-12/-2/+8/+18 dBV). */
const OUTPUT_ONLY_LABEL = '-12 dBV';

/** Stands in for Qa40xDeviceManager: the two state reads plus the recording sink. */
const manager = {
  backend: QA40X,
  card: CARD,
  calls: [],
  activeBackend() { return this.backend; },
  cardName() { return this.card; },
  applyActiveRangeChange(...args) { this.calls.push(args); },
};

const controller = new Qa40xRangeController(manager);

/** Puts the fake back into "QA40x, device open, nothing routed yet". */
const reset = () => { manager.backend = QA40X; manager.card = CARD; manager.calls = []; };

test('a QA40x input range change on an open device is routed to the manager', () => {
  reset();
  MessageBus.instance().publish(Events.DEVICE_ACTIVE_RANGE_CHANGED,
    { input: true, activeRangeLabel: INPUT_ONLY_LABEL });

  assert.deepEqual(manager.calls, [[QA40X, CARD, true, 42]],
    'the decoded dBV reaches applyActiveRangeChange(backend, card, input, dbv)');
});

test('a change while another backend is active is ignored', () => {
  reset();
  manager.backend = WEB_AUDIO;

  controller.onActiveRangeChanged({ input: true, activeRangeLabel: INPUT_ONLY_LABEL });

  assert.deepEqual(manager.calls, [], 'only QA40x re-ranges hardware');
});

test('a change while the device is not open is ignored', () => {
  reset();
  manager.card = null;   // not opened yet - the next open reads the store

  controller.onActiveRangeChanged({ input: true, activeRangeLabel: INPUT_ONLY_LABEL });

  assert.deepEqual(manager.calls, [], 'no card ⇒ nothing to re-range');
});

test('a label that is not a QA40x range is ignored', () => {
  reset();

  // The event is generic and may describe any device's card, so a foreign label is
  // normal traffic, not an error.
  controller.onActiveRangeChanged({ input: true, activeRangeLabel: 'Line in 1.0 V' });

  assert.deepEqual(manager.calls, [], 'an unresolvable label routes nothing');
});

test('the direction picks the candidate list, so a foreign-direction label resolves to nothing', () => {
  reset();
  // -12 dBV exists on the OUTPUT side only.
  controller.onActiveRangeChanged({ input: false, activeRangeLabel: OUTPUT_ONLY_LABEL });
  assert.deepEqual(manager.calls, [[QA40X, CARD, false, -12]],
    'an output label resolves against outputRangeDbvValues()');

  reset();
  controller.onActiveRangeChanged({ input: true, activeRangeLabel: OUTPUT_ONLY_LABEL });
  assert.deepEqual(manager.calls, [], 'the same label is no input range');

  reset();
  // 42 dBV exists on the INPUT side only (max attenuation).
  controller.onActiveRangeChanged({ input: false, activeRangeLabel: INPUT_ONLY_LABEL });
  assert.deepEqual(manager.calls, [], 'an input-only label is no output range');
});

test('a null payload is ignored without throwing', () => {
  reset();

  assert.doesNotThrow(() => controller.onActiveRangeChanged(null));
  assert.deepEqual(manager.calls, [], 'no payload ⇒ nothing routed');
});
