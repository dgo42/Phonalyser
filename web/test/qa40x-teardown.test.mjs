/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Teardown ordering for the QA40x, found by a static trace of every path that can open the
// analyzer against every path that can release it; the suite never reached it because it only
// happens on page unload.

import test from 'node:test';
import assert from 'node:assert/strict';

import { Qa40xDeviceManager } from '../js/qa40x/qa40x-device-manager.js';
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
