/*
 * Phonalyser web - the loopback duplex session: lifecycle, the one clock, the produced floor.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * What the session has to get right, all of it invisible on a trace until it is wrong:
 *
 *   1. THE FIRST ATTACH STARTS IT, THE LAST DETACH ENDS IT. Two lanes, one stream: a capture and
 *      a playback that started independently would be two clocks pretending to be one.
 *   2. ONE CAPTURE CONSUMER. A second attach is a wiring bug - two independent capture owners on
 *      one stream - and is refused loudly rather than silently replacing the first.
 *   3. DITHERED SILENCE, NOT ZEROS, PRODUCED PLAYBACK-SIDE. The unattached generator lane is the
 *      silence source: a zero-amplitude source through the lane quantiser at the session depth.
 *      Digital zeros would read as minus infinity and hide the known floor this backend exists to
 *      provide, and a floor made on the capture side would be a second quantiser to keep in step.
 *   4. THE FLOOR IS THE DEPTH'S. 0.5 LSB RMS at every depth, so two depths stand
 *      6.0206 * (N2 - N1) dB apart.
 *   5. RENDER THEN DELIVER, ON THE ABSOLUTE SCHEDULE. Nothing at the origin, block n due at
 *      start + n * period, a late tick renders every block that has come due, and the block is
 *      handed to the consumer synchronously - which is what makes ONE reused block safe.
 *   6. A FORMAT CHANGE MOVES THE WHOLE SESSION, and a running capture keeps receiving.
 *
 * The clock is faked and the interval is node's mock, so a LATE tick is staged exactly (the wall
 * clock moves several periods while the timer fires once) instead of sleep-tested.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

import { LoopbackDuplexEngine } from '../js/loopback/loopback-duplex-engine.js';

const RATE = 48000;
/** 50 blocks per second: 960 frames, 20 ms exactly - so the mock timer needs no rounding. */
const BLOCK_FRAMES = 960;
const BLOCK_MS = 20;
/** A triangular +/-1 LSB dither plus the rounding it linearises: 1/6 + 1/12 = 1/4 LSB squared. */
const FLOOR_LSB = 0.5;
const FLOOR_TOLERANCE_LSB = 0.06;
/** One bit of resolution is 20 * log10(2) dB of floor. */
const DB_PER_BIT = 6.0206;

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

/** The session on a faked clock and node's mock interval. */
function fixture(t, { rate = RATE, depth = 16, seed = 1 } = {}) {
  const realPerformance = globalThis.performance;
  const clock = { ms: 0 };
  globalThis.performance = { now: () => clock.ms };
  t.mock.timers.enable({ apis: ['setInterval'] });
  return {
    clock,
    engine: new LoopbackDuplexEngine(rate, depth, { rng: seededRandom(seed) }),
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

/** A consumer that records identity AND a copy - the block is reused, so only a copy survives. */
function collector() {
  const seen = { blocks: [], copies: [] };
  return {
    seen,
    onAudio(block) {
      seen.blocks.push(block);
      seen.copies.push(Array.from(block.l));
    },
  };
}

/** The RMS of a copied block in LSB of `depth`. */
function rmsLsb(samples, depth) {
  const scale = Math.pow(2, depth - 1) - 1;
  let sum = 0;
  for (const v of samples) sum += (v * scale) * (v * scale);
  return Math.sqrt(sum / samples.length);
}

test('the FIRST attach starts the session and the LAST detach ends it', async (t) => {
  const f = fixture(t);
  try {
    assert.equal(f.engine.streaming, false, 'a session with no lane attached does not run');

    const sink = collector();
    f.engine.attachCapture(sink);
    assert.equal(f.engine.streaming, true, 'the first attach starts it');

    // A generator lane joins the RUNNING session - no restart - and holds it open on its own.
    f.engine.attachGenerator({ nextBlock: (b) => { b.l.fill(0.25); b.r.fill(0.25); } });
    assert.equal(f.engine.streaming, true);
    f.engine.detachCapture();
    assert.equal(f.engine.streaming, true, 'the generator lane still holds the session open');

    f.engine.detachGenerator();
    assert.equal(f.engine.streaming, false, 'the last detach ends it');
  } finally {
    f.restore();
  }
});

test('a SECOND capture consumer is refused', async (t) => {
  const f = fixture(t);
  try {
    f.engine.attachCapture(collector());
    assert.throws(() => f.engine.attachCapture(collector()), /single capture consumer/,
      'two independent capture owners on one stream is a wiring bug, not a fallback');
    f.engine.detachCapture();
    assert.equal(f.engine.streaming, false);
    // And the lane is free again once it was given back.
    f.engine.attachCapture(collector());
    f.engine.detachCapture();
  } finally {
    f.restore();
  }
});

test('with no generator lane the session renders DITHERED SILENCE at its depth', async (t) => {
  const f = fixture(t);
  const sink = collector();
  try {
    f.engine.attachCapture(sink);
    assert.equal(sink.seen.blocks.length, 0, 'nothing is rendered at the origin itself');

    f.lateTick(5);
    assert.equal(sink.seen.blocks.length, 5, 'all five due blocks, in the one callback');

    const first = sink.seen.blocks[0];
    assert.equal(first.n, BLOCK_FRAMES);
    let nonZero = 0;
    for (let i = 0; i < first.n; i++) {
      assert.equal(first.r[i], first.l[i], `frame ${i} must carry one sample on both channels`);
      if (first.l[i] !== 0) nonZero++;
    }
    assert.ok(nonZero > 0, 'digital zeros would read as minus infinity, not as a known floor');

    const all = sink.seen.copies.flat();
    assert.ok(Math.abs(rmsLsb(all, 16) - FLOOR_LSB) < FLOOR_TOLERANCE_LSB,
      `the floor is ${FLOOR_LSB} LSB RMS, measured ${rmsLsb(all, 16)}`);
  } finally {
    f.restore();
  }
});

test('the silence floor is SCALED BY THE DEPTH - 6.0206 dB per bit', async (t) => {
  const measured = {};
  for (const depth of [16, 24]) {
    const f = fixture(t, { depth });
    const sink = collector();
    try {
      f.engine.attachCapture(sink);
      f.lateTick(5);
      const samples = sink.seen.copies.flat();
      measured[depth] = {
        lsb: rmsLsb(samples, depth),
        normalised: Math.sqrt(samples.reduce((s, v) => s + v * v, 0) / samples.length),
      };
    } finally {
      f.restore();
    }
  }
  assert.ok(measured[16].normalised > 0 && measured[24].normalised > 0,
    'both depths must deliver a floor at all');
  const ratioDb = 20 * Math.log10(measured[24].normalised / measured[16].normalised);
  const expected = -DB_PER_BIT * (24 - 16);
  assert.ok(Math.abs(ratioDb - expected) < 1.0,
    `24-bit silence must sit ${expected.toFixed(2)} dB under 16-bit, measured ${ratioDb.toFixed(2)}`);
  // Each depth is separately on its own 0.5 LSB figure, so the ratio above cannot be right by two
  // errors cancelling.
  assert.ok(Math.abs(measured[16].lsb - FLOOR_LSB) < FLOOR_TOLERANCE_LSB);
  assert.ok(Math.abs(measured[24].lsb - FLOOR_LSB) < FLOOR_TOLERANCE_LSB);
});

test('ONE reused block, refilled per render - safe because delivery is SYNCHRONOUS', async (t) => {
  const f = fixture(t);
  const sink = collector();
  try {
    f.engine.attachCapture(sink);
    f.lateTick(3);

    const [first, second, third] = sink.seen.blocks;
    assert.equal(second, first, 'the session hands over ONE block, not a fresh buffer per render');
    assert.equal(third, second);
    assert.notDeepEqual(sink.seen.copies[1], sink.seen.copies[0],
      'and it is refilled with fresh dither, not delivered twice');
  } finally {
    f.restore();
  }
});

test('an attached generator lane replaces the silence LIVE, and a detach restores it', async (t) => {
  const f = fixture(t);
  const sink = collector();
  try {
    f.engine.attachCapture(sink);
    f.lateTick(1);
    const silenceFirst = sink.seen.copies[0];

    f.engine.attachGenerator({ nextBlock: (b) => { b.l.fill(0.5); b.r.fill(0.5); } });
    f.lateTick(1);
    assert.equal(sink.seen.copies[1][0], 0.5, 'the attached lane renders in place of the silence');

    f.engine.detachGenerator();
    f.lateTick(1);
    assert.notEqual(sink.seen.copies[2][0], 0.5, 'the detach hands the lane back to the silence');
    assert.ok(Math.abs(rmsLsb(sink.seen.copies[2], 16) - FLOOR_LSB) < FLOOR_TOLERANCE_LSB,
      'and the floor is continuous across the stop');
    assert.ok(Math.abs(rmsLsb(silenceFirst, 16) - FLOOR_LSB) < FLOOR_TOLERANCE_LSB);
  } finally {
    f.restore();
  }
});

test('changeFormat moves the WHOLE session, and a running capture keeps receiving', async (t) => {
  const f = fixture(t);
  const sink = collector();
  try {
    f.engine.attachCapture(sink);
    f.lateTick(2);
    const before = sink.seen.blocks.length;
    assert.equal(before, 2);

    // A lane that goes live for a file recorded at another rate: the loop has one clock to move.
    f.engine.changeFormat(96000, 24);
    assert.equal(f.engine.sampleRate, 96000);
    assert.equal(f.engine.bitDepth, 24);
    assert.equal(f.engine.streaming, true, 'the running capture survived the move');

    // 96 kHz at 50 blocks per second is 1920 frames - and still a 20 ms period.
    f.lateTick(1);
    assert.ok(sink.seen.blocks.length > before, 'the capture stopped delivering across the move');
    const after = sink.seen.blocks[sink.seen.blocks.length - 1];
    assert.equal(after.n, 1920, 'the block is sized from the NEW rate');
    assert.ok(Math.abs(rmsLsb(Array.from(after.l), 24) - FLOOR_LSB) < FLOOR_TOLERANCE_LSB,
      'and the floor is the NEW depth\'s');
  } finally {
    f.restore();
  }
});

test('a format change while idle leaves the session idle', async (t) => {
  const f = fixture(t);
  try {
    f.engine.changeFormat(96000, 32);
    assert.equal(f.engine.streaming, false, 'nothing was attached, so nothing starts');
    assert.equal(f.engine.sampleRate, 96000);
    assert.equal(f.engine.bitDepth, 32);
  } finally {
    f.restore();
  }
});
