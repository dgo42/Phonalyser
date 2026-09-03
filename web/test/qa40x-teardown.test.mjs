/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Teardown ordering for the QA40x, and the scope's re-attach guard. Both were found by a static
// trace of every path that can open the analyzer against every path that can release it
// (2026-07-25), and neither was reachable from the suite: one only happens on page unload, the other
// only when a device fails to reopen.

import test from 'node:test';
import assert from 'node:assert/strict';

import { Qa40xDeviceManager } from '../js/qa40x/qa40x-device-manager.js';
import { ScopeController } from '../js/scope/scope-controller.js';
import { testPrefs } from './_test-prefs.mjs';
import { MessageBus } from '../js/bus/message-bus.js';
import { Events } from '../js/bus/events.js';

function fakeTransport() {
  return {
    ops: [], closed: false, closes: 0,
    async registerWrite(reg, value) { this.ops.push(`w${reg}=${value}`); },
    async registerRead() { return 0; },
    submitAudioWrite() {}, submitAudioRead() {}, setListener() {},
    async cancelAll() { this.ops.push('cancelAll'); },
    async close() { this.closes++; this.closed = true; this.ops.push('close'); },
  };
}

function makeManager() {
  const finder = {
    last: null,
    async list() { return [{ model: 'QA403' }]; },
    async open() { this.last = fakeTransport(); return this.last; },
  };
  const manager = new Qa40xDeviceManager({
    prefs: { backend: { get: () => 'QA40X' }, current: () => ({ outputBitDepth: 24 }),
      registerCustomPreferences() {} },
    deviceStore: { findAudioDeviceProfile: () => null, putAudioDeviceProfile() {}, saveDevices() {} },
    finder, sleeper: () => {}, armBus: false,
  });
  return { manager, finder };
}

// --- shutdown() owns the order --------------------------------------------

test('shutdown stops the session itself - a caller that forgot cannot leave a pump running', async () => {
  const { manager, finder } = makeManager();
  const engine = await manager.acquireEngine(48_000);
  await engine.attachCapture(() => {});
  const transport = finder.last;

  // The pagehide / beforeunload path: shutdown() with NO prior lane stop. Before the fix the engine
  // stayed `streaming` and its completion pump kept submitting to the just-closed handle.
  await manager.shutdown();

  assert.equal(engine.inUse, false, 'the session is stopped, not merely abandoned');
  assert.equal(transport.closed, true, 'and the analyzer is handed back');
  assert.equal(transport.closes, 1, 'exactly once - the session release must not double-close it');
});

test('shutdown after the lanes were already stopped is still correct (the backend-switch path)', async () => {
  const { manager, finder } = makeManager();
  const engine = await manager.acquireEngine(48_000);
  await engine.attachCapture(() => {});
  const transport = finder.last;

  await engine.detachCapture();          // what a backend switch does first
  assert.equal(transport.closed, true, 'the last detach already released the device');

  await manager.shutdown();              // must be a no-op, not a second park + close
  assert.equal(transport.closes, 1, 'no second close');
});

test('shutdown parks the safe ranges when no session ever ran', async () => {
  const { manager, finder } = makeManager();
  await manager.ensureDeviceCard();      // opens to read the cal page, then releases
  assert.equal(finder.last.closed, true);

  await manager.readDeviceInfo();        // opens again, releases again
  const transport = finder.last;
  await manager.shutdown();              // nothing open: must not throw
  assert.ok(transport.closed, 'still closed, and shutdown was harmless');
});

// --- the scope's re-attach guard ------------------------------------------

test('a failed re-acquire really STOPS the scope, and tells the pane', async () => {
  let measReattached = 0, measStopped = 0, released = 0, stoppedEvents = 0;
  const capture = {
    async acquire() { return null; },                 // the device did not come back
    async release() { released++; },
    get refCount() { return 0; },
  };
  const controller = new ScopeController(capture, testPrefs({ inputSampleRate: 48_000, oscTimePerDiv: 0.001 }));
  controller._measClient = {
    async reattach() { measReattached++; },
    async stop() { measStopped++; },
  };
  MessageBus.instance().subscribe(Events.SCOPE_RECORDING_STOPPED, () => { stoppedEvents++; });

  const ok = await controller.reattach();

  assert.equal(ok, false, 'the re-attach reports failure');
  assert.equal(measReattached, 0,
    'it does NOT re-acquire through the client: that re-opens a device this consumer just disowned,'
    + ' and setRecording(false) then early-returns on !_scopeOn so nothing can ever release it');
  // Clearing the flag alone left the scope LOOKING like it was running - LED lit, worker spinning,
  // and the app slowing down over time. The stop has to be real.
  assert.equal(controller.recording, false, 'the scope is off');
  assert.equal(measStopped, 1, 'the measurement worker is stopped, not left running');
  assert.equal(released, 1, 'and the capture reference is released');
  assert.equal(stoppedEvents, 1, 'the pane is told, so the Record LED can grey');
});

test('a successful re-acquire does re-anchor the measurement stream', async () => {
  let measReattached = 0;
  const reader = { getWritePos: () => 0, read: () => 0, readEndingAt: () => 0 };
  const capture = {
    async acquire() { return reader; },
    async release() {},
    get refCount() { return 1; },
  };
  const controller = new ScopeController(capture, testPrefs({ inputSampleRate: 48_000, oscTimePerDiv: 0.001 }));
  controller._measClient = { async reattach() { measReattached++; }, async stop() {} };

  const ok = await controller.reattach();

  assert.equal(ok, true);
  assert.equal(measReattached, 1, 'the fresh ring is re-anchored for the measurement stream too');
});

// --- the USB disconnect watch ----------------------------------------------

test('unplugging the armed analyzer raises the input-device alert and cleans up', async () => {
  // A finder that offers the hook BEFORE construction - the watch arms in the constructor.
  const finder2 = {
    async list() { return [{ model: 'QA403' }]; },
    async open() { this.last = { ops: [], closed: false, closes: 0,
      async registerWrite() {}, async registerRead() { return 0; },
      submitAudioWrite() {}, submitAudioRead() {}, setListener() {},
      async cancelAll() {}, async close() { this.closes++; this.closed = true; } };
      return this.last; },
    watchDisconnect(handler) { this.unplug = handler; },
  };
  const manager2 = new Qa40xDeviceManager({
    prefs: { backend: { get: () => 'QA40X' }, current: () => ({ outputBitDepth: 24 }),
      registerCustomPreferences() {} },
    deviceStore: { findAudioDeviceProfile: () => null, putAudioDeviceProfile() {}, saveDevices() {} },
    finder: finder2, sleeper: () => {}, armBus: false,
  });
  assert.equal(typeof finder2.unplug, 'function', 'the constructor arms the watch');

  const engine = await manager2.acquireEngine(48_000);
  await engine.attachCapture(() => {});
  const transport = finder2.last;

  const errors = [];
  const listener = (payload) => errors.push(payload);
  MessageBus.instance().subscribe(Events.AUDIO_DEVICE_ERROR, listener);
  try {
    await finder2.unplug({ model: 'QA403' });
  } finally {
    MessageBus.instance().unsubscribe(Events.AUDIO_DEVICE_ERROR, listener);
  }

  assert.equal(errors.length, 1, 'exactly one alert - the same surface a dead Web Audio input uses');
  assert.equal(errors[0].direction, 'input',
    'direction input, so the scope/FFT pane subscribers stop their capture');
  assert.equal(engine.inUse, false, 'the session is stopped, its pump with it');
  assert.equal(transport.closed, true, 'and the dead transport is closed, so a replug opens clean');
});

test('unplugging an idle, already-released analyzer is routine - no alert', async () => {
  const finder2 = {
    async list() { return [{ model: 'QA403' }]; },
    async open() { throw new Error('never opened in this test'); },
    watchDisconnect(handler) { this.unplug = handler; },
  };
  const manager2 = new Qa40xDeviceManager({
    prefs: { backend: { get: () => 'QA40X' }, current: () => ({ outputBitDepth: 24 }),
      registerCustomPreferences() {} },
    deviceStore: { findAudioDeviceProfile: () => null, putAudioDeviceProfile() {}, saveDevices() {} },
    finder: finder2, sleeper: () => {}, armBus: false,
  });

  const errors = [];
  const listener = (payload) => errors.push(payload);
  MessageBus.instance().subscribe(Events.AUDIO_DEVICE_ERROR, listener);
  try {
    await finder2.unplug({ model: 'QA403' });
  } finally {
    MessageBus.instance().unsubscribe(Events.AUDIO_DEVICE_ERROR, listener);
  }

  assert.deepEqual(errors, [], 'nothing was open, so nothing is wrong');
});
