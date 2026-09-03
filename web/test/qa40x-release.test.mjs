/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// The WEB-ONLY release-after-idle contract, which had NO test at all until a static trace found
// six unpaired opens (2026-07-25). WebUSB claims interface 0 EXCLUSIVELY, so any path that opens
// the analyzer and leaves it claimed locks it out of QuantAsylum's own software and every other
// tab for the life of the page - the maintainer's report: "stop gen/scope/fft everything, the
// device is STILL not released".
//
// The rule under test: the analyzer is held exactly while a SESSION holds it (#engine non-null,
// i.e. a lane is attached). Every other moment must leave it released.

import test from 'node:test';
import assert from 'node:assert/strict';

import { Qa40xDeviceManager } from '../js/qa40x/qa40x-device-manager.js';

/** A transport that records the register traffic and whether it was closed. */
function fakeTransport() {
  return {
    ops: [], closed: false,
    async registerWrite(reg, value) { this.ops.push(`w${reg}=${value}`); },
    async registerRead(reg) { this.ops.push(`r${reg}`); return 0; },
    submitAudioWrite() {}, submitAudioRead() {}, setListener() {},
    async cancelAll() {}, async close() { this.closed = true; },
  };
}

/** A finder that hands out a fresh transport per open, and counts the opens. */
function fakeFinder() {
  return {
    opens: 0, last: null,
    async list() { return [{ model: 'QA403', busNumber: 1, address: 2 }]; },
    async open() { this.opens++; this.last = fakeTransport(); return this.last; },
    async scan() { return []; },
  };
}

const fakePrefs = () => ({
  backend: { get: () => 'QA40X' },
  current: () => ({ outputBitDepth: 24 }),
  registerCustomPreferences() {},
});

const fakeStore = () => ({
  findAudioDeviceProfile: () => null,
  putAudioDeviceProfile() {},
  saveDevices() {},
});

function makeManager(finder) {
  return new Qa40xDeviceManager({
    prefs: fakePrefs(), deviceStore: fakeStore(), finder,
    sleeper: () => {}, armBus: false,
  });
}

test('reading the device panel does not keep the analyzer claimed', async () => {
  const finder = fakeFinder();
  const manager = makeManager(finder);

  await manager.readDeviceInfo();

  assert.equal(finder.opens, 1, 'the panel opens the device to read its registers');
  assert.equal(finder.last.closed, true,
    'and hands it back: Preferences ▸ QA40x Settings must not cost the analyzer for the page life');
});

test('a failing register read still releases the analyzer', async () => {
  const finder = fakeFinder();
  const manager = makeManager(finder);
  const realOpen = finder.open.bind(finder);
  finder.open = async () => {
    const t = await realOpen();
    t.registerRead = async () => { throw new Error('device yanked'); };
    return t;
  };

  const info = await manager.readDeviceInfo();

  assert.equal(info.firmwareVersion, '---', 'a wedged analyzer shows dashes, not a broken dialog');
  assert.equal(finder.last.closed, true, 'and the claim is released on the failure path too');
});

test('building the device card at scan time does not keep the analyzer claimed', async () => {
  const finder = fakeFinder();
  const manager = makeManager(finder);

  await manager.ensureDeviceCard();

  assert.equal(finder.opens, 1);
  assert.equal(finder.last.closed, true, 'the Scan-time calibration read releases the device');
});

test('a device left claimed by an earlier path is cleaned up, not preserved', async () => {
  const finder = fakeFinder();
  const manager = makeManager(finder);

  // Simulate the old leak: something opened the analyzer and left it claimed with no session.
  await manager.readDeviceInfo();
  const first = finder.last;
  first.closed = false;                      // pretend the release never happened

  await manager.ensureDeviceCard();

  assert.equal(finder.last.closed, true,
    'the guard must not be "did I open it just now" - any idle moment cleans up a stale claim');
});

test('a live session keeps the analyzer - the release is idle-only', async () => {
  const finder = fakeFinder();
  const manager = makeManager(finder);

  const engine = await manager.acquireEngine(48000);
  await engine.attachCapture(() => {});
  const held = finder.last;

  await manager.releaseIfIdle();
  assert.equal(held.closed, false, 'a session in progress must never have its device pulled');

  await engine.detachCapture();
  assert.equal(held.closed, true, 'and the last detach releases it');
});

test('open() without a completed start() still releases (the lane client has nothing to detach)', async () => {
  const finder = fakeFinder();
  const manager = makeManager(finder);

  // What a lane client does when its start() throws between open() and attach: it closes, but has
  // no lane to detach, so only the manager's idle release can hand the device back.
  await manager.acquireEngine(48000);
  const held = finder.last;
  assert.equal(held.closed, false, 'acquiring the engine opens the analyzer');

  await manager.releaseIfIdle();

  assert.equal(held.closed, true, 'an acquired-but-never-attached engine must not hold the device');
});
