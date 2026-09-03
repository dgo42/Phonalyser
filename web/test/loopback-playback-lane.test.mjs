/*
 * Phonalyser web - the loopback playback lane: the dither rule, the buffer lane, the attach.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Four rules of the lane, each a silent failure if it slips:
 *
 *   1. IT RENDERS ON THE SESSION'S CLOCK, NOT ITS OWN. Going live ATTACHES the lane to the one
 *      duplex session, which pulls a block when the block is due; a lane with a timer of its own
 *      would be a second clock, and the two would drift into each other's blocks.
 *   2. THE DITHER RULE. By default the depth's own last bit, so the floor is the arithmetic TPDF
 *      floor of the SELECTED depth; a configured value overrides it, live included; a zero falls
 *      back to the default rather than to silence. That fixed floor is the whole reason the
 *      backend exists.
 *   3. THE DEPTH IS READ WHEN THE LANE GOES LIVE, and the whole session moves to it - one loop,
 *      one clock, one depth.
 *   4. CLOSING DETACHES, IT DOES NOT STOP THE SESSION. The capture lane goes on receiving the
 *      dithered silence the unattached generator lane emits, so a stopped generator never looks
 *      like a stopped device.
 *
 * The clock is faked and the interval is node's mock, so a LATE tick can be staged exactly (the
 * wall clock moves several periods while the timer fires once) instead of sleep-tested.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

import { GenSignalForm } from '../js/enums/gen-signal-form.js';
import { LoopbackDeviceManager } from '../js/loopback/loopback-device-manager.js';

const RATE = 48000;
/** 50 blocks per second: 960 frames, 20 ms exactly - so the mock timer needs no rounding. */
const BLOCK_FRAMES = 960;
const BLOCK_MS = 20;
/** The arithmetic TPDF floor: a triangular +/-1 LSB dither plus the rounding it linearises has a
 *  total error power of 1/6 + 1/12 = 1/4 LSB squared. */
const FLOOR_LSB = 0.5;
/** The desktop bench tolerance on that figure. */
const FLOOR_TOLERANCE_LSB = 0.06;

/** A silent tone: every sample is exactly 0.0 before quantisation, so what lands in the block is
 *  the dither floor and nothing else. */
const SILENT_SPEC = Object.freeze({
  form: GenSignalForm.SINE,
  frequency: 1000,
  amplitudeVRms: 0.0,
  dacFsVoltageAmpl: 1.0,
  ditherBits: 0,
  outputChannels: 'BOTH',
  rightLaneScale: 1.0,
});

/** Deterministic uniform [0,1) source (mulberry32), so the floor figures below are fixed rather
 *  than merely probable. */
function seededRandom(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/**
 * The lane on a faked clock and node's mock interval, with a collector attached to the SAME
 * session - which is the only way to see what the lane renders, now that the session owns the
 * clock and the block. Every observation is a COPY: the block is reused by construction.
 * The depth arrives as a SUPPLIER, so a case can move it the way the Preferences depth combo does.
 */
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
  const rendered = [];
  const engine = manager.acquireEngine(RATE, depth);
  engine.attachCapture({ onAudio: (b) => rendered.push({ l: Array.from(b.l), r: Array.from(b.r), n: b.n }) });
  return {
    manager,
    engine,
    selected,
    rendered,
    get depth() { return selected.depth; },
    lane: manager.openPlayback(manager.listOutputDevices()[0], 0),
    /** Everything rendered since the last call, oldest first. */
    drain() {
      return rendered.splice(0, rendered.length);
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

/** The block's RMS in LSB of the given depth - the floor figure the bench states. */
function rmsLsb(block, depth) {
  const scale = Math.pow(2, depth - 1) - 1;
  let sum = 0;
  for (let i = 0; i < block.n; i++) sum += (block.l[i] * scale) * (block.l[i] * scale);
  return Math.sqrt(sum / block.n);
}

test('going live ATTACHES the lane, and a LATE tick renders every block that is due', async (t) => {
  const f = fixture(t);
  try {
    await f.lane.open({ sampleRate: RATE });
    assert.equal(f.drain().length, 0, 'open() alone renders nothing - the lane is not live yet');

    await f.lane.start(SILENT_SPEC);
    assert.equal(f.drain().length, 0, 'nothing at the origin: the session waits out the period');

    // The page was busy for five periods and the timer fired once.
    f.lateTick(5);
    assert.equal(f.drain().length, 5, 'all five due blocks, in the one callback');

    // ...and the schedule is unchanged afterwards: one period, one block.
    f.lateTick(1);
    assert.equal(f.drain().length, 1, 'the catch-up must not run the lane ahead');
  } finally {
    f.restore();
  }
});

test('the block size follows the floored block, and both channels carry the same sample', async (t) => {
  const f = fixture(t, { depth: 24 });
  try {
    await f.lane.open({ sampleRate: RATE });
    await f.lane.start(SILENT_SPEC);
    f.lateTick(1);
    const [block] = f.drain();

    assert.equal(block.n, BLOCK_FRAMES);
    assert.equal(block.l.length, BLOCK_FRAMES);
    const scale = Math.pow(2, f.depth - 1) - 1;
    let nonZero = 0;
    for (let i = 0; i < block.n; i++) {
      // One dither draw per FRAME: a second draw for the right channel would make the two
      // channels independently noisy, which is a 3 dB different bench.
      assert.equal(block.r[i], block.l[i], `frame ${i} must carry one sample on both channels`);
      // Quantised at the SELECTED depth: every sample sits on that depth's integer grid.
      const steps = block.l[i] * scale;
      assert.ok(Math.abs(steps - Math.round(steps)) < 1e-9, `frame ${i} is off the 24-bit grid`);
      if (block.l[i] !== 0) nonZero++;
    }
    assert.ok(nonZero > 0, 'a silent tone still carries its dither - never digital zeros');
  } finally {
    f.restore();
  }
});

test('dither defaults to the SELECTED depth and a configured value overrides it, live included',
  async (t) => {
    const f = fixture(t);
    // A dither of b bits on a d-bit lane floors at sqrt(4^(d-b)/6 + 1/12) LSB of d: the
    // triangular noise keeps its own grid while the rounding keeps the quantiser's. The
    // familiar 0.5 LSB is the b = d case of the same expression.
    const configured = f.depth - 4;
    const configuredFloor = Math.sqrt(Math.pow(4, 4) / 6 + 1 / 12);
    // The bench tolerance, kept relative (0.06 on 0.5 is twelve percent).
    const configuredTolerance = configuredFloor * (FLOOR_TOLERANCE_LSB / FLOOR_LSB);
    try {
      await f.lane.open({ sampleRate: RATE });
      // ditherBits 0 in the start spec: none configured, so the last-bit DEFAULT holds - the
      // lane is never undithered, or the FFT would show bare-rounding artifacts.
      await f.lane.start(SILENT_SPEC);
      f.lateTick(1);
      const [atStart] = f.drain();
      assert.ok(Math.abs(rmsLsb(atStart, f.depth) - FLOOR_LSB) < FLOOR_TOLERANCE_LSB,
        `the floor is ${FLOOR_LSB} LSB with nothing configured, not silence`);

      // The live change that rides every generator retune, on the same control channel the Web
      // Audio worklet speaks: a CONFIGURED dither applies, exactly as on a hardware lane.
      f.lane.port.postMessage({ ditherBits: configured });
      f.lateTick(1);
      const [afterLiveChange] = f.drain();
      assert.ok(Math.abs(rmsLsb(afterLiveChange, f.depth) - configuredFloor) < configuredTolerance,
        `a configured dither moves the floor to its own depth (${configuredFloor} LSB)`);

      // Turning the generator's dither OFF returns to the default, never to silence.
      f.lane.port.postMessage({ ditherBits: 0 });
      f.lateTick(1);
      const [afterOff] = f.drain();
      assert.ok(Math.abs(rmsLsb(afterOff, f.depth) - FLOOR_LSB) < FLOOR_TOLERANCE_LSB,
        'dither off falls back to the last-bit default');
    } finally {
      f.restore();
    }
  });

test('playBuffer sends a pre-rendered buffer through the same quantiser, then the depth floor',
  async (t) => {
    // Without this entry point the generator controller skips the sink and opens a Web Audio
    // context instead, so a sweep would leave through the speakers while this backend captured
    // its own silence.
    const f = fixture(t);
    try {
      await f.lane.open({ sampleRate: RATE });
      const mono = new Float64Array(2 * BLOCK_FRAMES).fill(0.5);
      let ended = 0;
      await f.lane.playBuffer(mono, { onEnded: () => { ended++; } });

      const scale = Math.pow(2, f.depth - 1) - 1;
      f.lateTick(1);
      const [first] = f.drain();
      for (let i = 0; i < first.n; i++) {
        // The buffer's own value, dithered and quantised - never rescaled: a gain applied here
        // against an unscaled deconvolution reference would be read as a response.
        assert.ok(Math.abs(first.l[i] - 0.5) < 2 / scale, `frame ${i} carries the buffer sample`);
        const steps = first.l[i] * scale;
        assert.ok(Math.abs(steps - Math.round(steps)) < 1e-9, `frame ${i} is off the depth's grid`);
      }
      assert.equal(ended, 0, 'the buffer is not over yet');
      // The control channel is OPEN for a pre-rendered lane too: it has no kernel to retune, but
      // it has a quantiser, and the dither that rides every retune must reach it - Java pushes
      // setDitherBits to the file line exactly as it pushes it to the tone line. The kernel-only
      // messages are ignored for it, which the next two lines are.
      assert.notEqual(f.lane.port, null, 'a producing lane answers on its port');
      f.lane.port.postMessage({ frequency: 2000 });      // no kernel: ignored, must not throw

      f.lateTick(1);
      f.drain();                              // the buffer's second block
      f.lateTick(1);
      const [afterEnd] = f.drain();
      assert.equal(ended, 1, 'the end is announced exactly once');
      assert.ok(Math.abs(rmsLsb(afterEnd, f.depth) - FLOOR_LSB) < FLOOR_TOLERANCE_LSB,
        'past the end the lane keeps feeding the depth floor - the capture must never starve');
      f.lateTick(1);
      assert.equal(ended, 1, 'and never twice');
    } finally {
      f.restore();
    }
  });

test('the depth is read when the lane goes live, and the whole SESSION moves to it', async (t) => {
  const f = fixture(t);
  try {
    await f.lane.open({ sampleRate: RATE });
    f.selected.depth = 24;      // the operator moved the depth combo before pressing play
    await f.lane.start(SILENT_SPEC);
    assert.equal(f.engine.bitDepth, 24, 'the session took the lane\'s format');

    f.lateTick(1);
    const [block] = f.drain();
    const scale24 = Math.pow(2, 24 - 1) - 1;
    let peak = 0;
    for (let i = 0; i < block.n; i++) peak = Math.max(peak, Math.abs(block.l[i]));
    assert.ok(peak > 0, 'the block carries its dither');
    assert.ok(peak < 4 / scale24, 'the floor is the NEW depth, not the one selected at build time');
    assert.ok(Math.abs(rmsLsb(block, 24) - FLOOR_LSB) < FLOOR_TOLERANCE_LSB);
  } finally {
    f.restore();
  }
});

test('a block pulled with no source behind the lane carries the DITHERED floor, never zeros', async (t) => {
  const f = fixture(t);
  try {
    await f.lane.open({ sampleRate: RATE });
    await f.lane.start(SILENT_SPEC);
    // The window between a detach and the lane's own bookkeeping: the session still pulls this
    // lane and it has no source to pull from. One undithered block reads as minus infinity where
    // every neighbouring block reads the known floor.
    await f.lane.close();
    const block = { l: new Float64Array(BLOCK_FRAMES), r: new Float64Array(BLOCK_FRAMES), n: BLOCK_FRAMES };

    f.lane.nextBlock(block);

    assert.ok(block.l.some((v) => v !== 0), 'digital zeros are not a floor');
    assert.ok(Math.abs(rmsLsb(block, f.depth) - FLOOR_LSB) < FLOOR_TOLERANCE_LSB,
      `the session's last-bit floor, measured ${rmsLsb(block, f.depth)}`);
  } finally {
    f.restore();
  }
});

test('close() detaches the lane and the capture keeps receiving the session\'s silence', async (t) => {
  const f = fixture(t);
  try {
    await f.lane.open({ sampleRate: RATE });
    await f.lane.start(SILENT_SPEC);
    await f.lane.close();

    assert.equal(f.engine.streaming, true, 'the capture lane still holds the session open');
    f.lateTick(2);
    const blocks = f.drain();
    assert.equal(blocks.length, 2, 'a stopped generator must never look like a stopped device');
    for (const block of blocks) {
      assert.ok(Math.abs(rmsLsb(block, f.depth) - FLOOR_LSB) < FLOOR_TOLERANCE_LSB,
        'and what arrives is the session\'s own dithered silence, at the same floor');
    }
    assert.equal(f.lane.port, null, 'no live-control channel once the lane is closed');

    // A second close must not detach a lane this one no longer holds.
    await f.lane.close();
    assert.equal(f.engine.streaming, true);
  } finally {
    f.restore();
  }
});
