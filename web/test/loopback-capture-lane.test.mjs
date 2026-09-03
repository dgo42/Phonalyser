/*
 * Phonalyser web - the loopback capture lane: a thin consumer of the one duplex session.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * What this lane has to get right, all of it invisible on a trace until it is wrong:
 *
 *   1. IT PRODUCES NOTHING OF ITS OWN, SILENCE INCLUDED. The known floor is dither, dither is
 *      playback-side, and the session's unattached generator lane emits it - so a capture with
 *      nothing playing receives the same dithered blocks a playing lane would send, and there is
 *      no second quantiser here to keep in step with the first.
 *   2. THE SESSION IS ACQUIRED AT OPEN, AT THE SELECTED DEPTH, and the whole session moves to it.
 *      A depth frozen at construction would go on asking for the resolution that was selected when
 *      the page loaded.
 *   3. START ATTACHES, STOP DETACHES. The first attach starts the session and the last detach ends
 *      it, so a closed lane delivers nothing and a second stop detaches nothing.
 *   4. THE BLOCK IS HANDED ON UNCHANGED, synchronously - the session reuses it.
 *   5. NOTHING ENDS THIS STREAM FROM BELOW. There is no device to lose, so the capture-ended
 *      callback of the contract is accepted and never invoked.
 *
 * The clock is faked and the interval is node's mock, so a LATE tick is staged exactly (the wall
 * clock moves several periods while the timer fires once) instead of sleep-tested.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

import { LoopbackDeviceManager } from '../js/loopback/loopback-device-manager.js';

const RATE = 48000;
/** 50 blocks per second: 960 frames, 20 ms exactly - so the mock timer needs no rounding. */
const BLOCK_FRAMES = 960;
const BLOCK_MS = 20;
/** A triangular +/-1 LSB dither plus the rounding it linearises: 1/6 + 1/12 = 1/4 LSB squared. */
const FLOOR_LSB = 0.5;
const FLOOR_TOLERANCE_LSB = 0.06;

/** Deterministic uniform [0,1) source (mulberry32), so the floor figures are fixed rather than
 *  merely probable. */
function seededRandom(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** The lane on a faked clock and node's mock interval, with a batch collector that reads each
 *  batch SYNCHRONOUSLY - which is what makes the session's one reused block safe. The depth
 *  arrives as a SUPPLIER, so a case can move it between sessions the way the Preferences depth
 *  combo does. armBus is off: this file drives the lane, not the Preferences constraint. */
function fixture(t, { depth = 16, seed = 1 } = {}) {
  const realPerformance = globalThis.performance;
  const clock = { ms: 0 };
  globalThis.performance = { now: () => clock.ms };
  t.mock.timers.enable({ apis: ['setInterval'] });
  const selected = { depth };
  const manager = new LoopbackDeviceManager({
    inputDepthOf: () => selected.depth,
    outputDepthOf: () => selected.depth,
    rng: seededRandom(seed),
    armBus: false,
  });
  const collected = { batches: [], heads: [], samples: [] };
  return {
    manager,
    selected,
    collected,
    lane: manager.openCapture(manager.listInputDevices()[0]),
    /** The ring sink: identity, a copy of the first samples, and every sample for the floor. */
    onBatch(batch) {
      collected.batches.push(batch);
      collected.heads.push(Array.from(batch.l.subarray(0, 4)));
      for (let i = 0; i < batch.n; i++) collected.samples.push(batch.l[i]);
    },
    /** RMS of everything delivered so far, in LSB of the given depth. */
    rmsLsb(bits) {
      const scale = Math.pow(2, bits - 1) - 1;
      let sum = 0;
      for (const v of collected.samples) sum += (v * scale) * (v * scale);
      return Math.sqrt(sum / collected.samples.length);
    },
    /** Moves the WALL clock by that many block periods and lets the interval fire ONCE - the
     *  background-tab clamp, staged. */
    lateTick(periods) {
      clock.ms += periods * BLOCK_MS;
      t.mock.timers.tick(BLOCK_MS);
    },
    restore() {
      t.mock.timers.reset();
      globalThis.performance = realPerformance;
    },
  };
}

test('with nothing playing the lane receives DITHERED SILENCE at the selected depth', async (t) => {
  const f = fixture(t);
  const ended = [];
  try {
    await f.lane.open('any', RATE);
    await f.lane.start((b) => f.onBatch(b), (r) => ended.push(r));
    assert.equal(f.collected.batches.length, 0, 'nothing is delivered at the origin itself');

    f.lateTick(5);

    assert.equal(f.collected.batches.length, 5);
    assert.equal(f.collected.samples.length, 5 * BLOCK_FRAMES);
    const first = f.collected.batches[0];
    let nonZero = 0;
    for (let i = 0; i < first.n; i++) {
      assert.equal(first.r[i], first.l[i], `frame ${i} must carry one sample on both channels`);
      if (first.l[i] !== 0) nonZero++;
    }
    assert.ok(nonZero > 0, 'digital zeros would read as minus infinity, not as a known floor');
    assert.ok(Math.abs(f.rmsLsb(16) - FLOOR_LSB) < FLOOR_TOLERANCE_LSB,
      `the floor is ${FLOOR_LSB} LSB RMS, measured ${f.rmsLsb(16)}`);
    assert.deepEqual(ended, [], 'nothing can end this stream from below - there is no device');
  } finally {
    f.restore();
  }
});

test('a late tick delivers every block that has come due, and never runs ahead', async (t) => {
  const f = fixture(t);
  try {
    await f.lane.open('any', RATE);
    await f.lane.start((b) => f.onBatch(b), () => {});

    // The page was busy for four periods and the timer fired once.
    f.lateTick(4);
    assert.equal(f.collected.batches.length, 4, 'all four due blocks, in the one callback');

    f.lateTick(1);
    assert.equal(f.collected.batches.length, 5, 'the catch-up must not run the lane ahead');
  } finally {
    f.restore();
  }
});

test('the session\'s block is handed on UNCHANGED - one reused buffer, refilled', async (t) => {
  const f = fixture(t);
  try {
    await f.lane.open('any', RATE);
    await f.lane.start((b) => f.onBatch(b), () => {});

    f.lateTick(3);

    const [first, second, third] = f.collected.batches;
    assert.equal(second, first, 'the lane copies nothing - it hands the session\'s block on');
    assert.equal(third, second);
    assert.notDeepEqual(f.collected.heads[1], f.collected.heads[0],
      'and the session refills it with fresh dither, not the same block twice');
  } finally {
    f.restore();
  }
});

test('start ATTACHES and stop DETACHES - a closed lane delivers nothing', async (t) => {
  const f = fixture(t);
  try {
    await f.lane.open('any', RATE);
    assert.equal(f.lane.isOpen, true);
    assert.equal(f.lane.sampleRate, RATE);
    const engine = f.manager.acquireEngine(RATE, 16);
    assert.equal(engine.streaming, false, 'open() alone starts nothing');

    await f.lane.start((b) => f.onBatch(b), () => {});
    assert.equal(engine.streaming, true, 'the attach starts the session');

    await f.lane.close();
    assert.equal(engine.streaming, false, 'and the last detach ends it');
    assert.equal(f.lane.isOpen, false, 'the lane is never held open across captures');

    f.lateTick(5);
    assert.equal(f.collected.batches.length, 0, 'a closed lane delivers nothing');

    // A second stop must not detach a lane this one no longer holds - the session would then be
    // ended under whatever attached after it.
    await f.lane.stop();
    assert.equal(engine.streaming, false);
  } finally {
    f.restore();
  }
});

test('the depth is read at EVERY open - a depth change lands on the next capture', async (t) => {
  const f = fixture(t);
  const peaks = [];
  const collect = (b) => {
    let peak = 0;
    for (let i = 0; i < b.n; i++) peak = Math.max(peak, Math.abs(b.l[i]));
    peaks.push(peak);
  };
  try {
    await f.lane.open('any', RATE);
    await f.lane.start(collect, () => {});
    f.lateTick(2);
    await f.lane.close();
    const at16 = Math.max(...peaks);
    peaks.length = 0;

    // The operator moves the depth combo between two captures. The lane object is the same one -
    // the engine keeps its capture source across sessions and rebuilds it only when the BACKEND
    // changes - so a depth frozen at construction would go on asking for 16 bits forever.
    f.selected.depth = 24;
    await f.lane.open('any', RATE);
    assert.equal(f.manager.acquireEngine(RATE, 24).bitDepth, 24,
      'the session moved to the newly selected depth');
    await f.lane.start(collect, () => {});
    f.lateTick(2);
    await f.lane.close();
    const at24 = Math.max(...peaks);

    assert.ok(at16 > 0 && at24 > 0, 'both sessions must deliver a floor');
    // The dither's +/-1 LSB peak is 256 times smaller 8 bits down; identical peaks would mean the
    // second session never asked.
    const ratio = at16 / at24;
    assert.ok(ratio > 128 && ratio < 512, `the floor must follow the new depth (ratio ${ratio})`);
  } finally {
    f.restore();
  }
});

test('start() without open() is refused - there is no session to attach to', async (t) => {
  const f = fixture(t);
  try {
    await assert.rejects(() => f.lane.start(() => {}, () => {}), /open\(\) before start\(\)/);
  } finally {
    f.restore();
  }
});
