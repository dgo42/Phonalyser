/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Qa40xCaptureSource - the web twin of org.edgo.audio.measure.sound.qa40x.Qa40xRecorder.
 * The Java has no unit test of its own (its recorder is exercised through the desktop
 * capture path), so this pins the things the port could get silently wrong:
 *
 *   - the int32 -> normalised-float scaling, INCLUDING both full-scale rails, which is the
 *     web's replacement for Java's 24-bit repack;
 *   - the channel order: slot 0 -> l (ch0), slot 1 -> r (ch1, the calibrated/attenuated
 *     channel), NOT swapped and the right NOT inverted (QA401-only quirks);
 *   - attach/detach pairing on the one duplex engine - a leaked attach leaves the analyzer
 *     streaming, a double detach re-parks a device another lane is using;
 *   - the dropped-batch counter and its rate-limited warning, the only overrun signal left
 *     once Java's SPSC ring is gone.
 *
 * Driven entirely through a fake engine - no hardware, no browser, no transport.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

import { Qa40xCaptureSource } from '../js/qa40x/qa40x-capture-source.js';
import { FRAME_BYTES } from '../js/qa40x/qa40x-duplex-engine.js';
import { MAXINT } from '../js/qa40x/qa40x-levels.js';

const RATE_HZ = 48_000;
/** int32 minimum - one LSB past −MAXINT, the rail an ADC at negative clip can emit. */
const INT32_MIN = -2147483648;

/**
 * Qa40xDuplexEngine double: records the lane calls in order and lets a test deliver ADC
 * bytes the way readCompleted does. Refuses a second attach exactly as the real engine
 * does (one duplex engine has a single capture consumer).
 */
class FakeEngine {
  ops = [];
  /** The last attached lane callback, KEPT after a detach so a test can model a read that
   *  completes into it late (Java's recording flag is what makes that harmless). */
  lane = null;
  #consumer = null;

  async attachCapture(consumer) {
    if (this.#consumer != null) {
      throw new Error('QA40x capture lane already attached');
    }
    this.#consumer = consumer;
    this.lane = consumer;
    this.ops.push('attachCapture');
  }

  async detachCapture() {
    this.#consumer = null;
    this.ops.push('detachCapture');
  }

  /** Delivers one ADC read to the attached lane. `length` defaults to the whole buffer. */
  deliver(bytes, length) {
    assert.ok(this.#consumer != null, 'test bug: nothing attached');
    this.#consumer(bytes, length === undefined ? bytes.length : length);
  }

  /** True while a capture consumer is attached - a leaked attach is a streaming analyzer. */
  get attached() { return this.#consumer != null; }
}

/** Qa40xDeviceManager double: hands out the one engine and logs every acquired rate. */
class FakeManager {
  engine = new FakeEngine();
  rates = [];

  releases = 0;

  async acquireEngine(sampleRateHz) {
    this.rates.push(sampleRateHz);
    return this.engine;
  }

  /** The manager hands the analyzer back when nothing is using it. A lane client calls this from
   *  close() because an open() whose start() never completed has NO lane to detach, so the
   *  engine's last-detach release can never fire (see qa40x-release.test.mjs). */
  async releaseIfIdle() {
    this.releases++;
  }
}

/** Builds interleaved little-endian int32 stereo ADC bytes from [slot0, slot1] pairs. */
function adcPayload(frames) {
  const bytes = new Uint8Array(frames.length * FRAME_BYTES);
  const view = new DataView(bytes.buffer);
  frames.forEach(([slot0, slot1], f) => {
    view.setInt32(FRAME_BYTES * f, slot0, true);
    view.setInt32(FRAME_BYTES * f + FRAME_BYTES / 2, slot1, true);
  });
  return bytes;
}

/** An opened + started source over a fresh fake manager; `batches` holds COPIES (the
 *  staging pair is recycled, so a live reference would show only the last batch). */
async function started() {
  const manager = new FakeManager();
  const source = new Qa40xCaptureSource(manager);
  await source.open('ignored-device-id', RATE_HZ);
  const batches = [];
  await source.start((b) => {
    batches.push({ l: Array.from(b.l.subarray(0, b.n)), r: Array.from(b.r.subarray(0, b.n)), n: b.n });
  });
  return { source, manager, engine: manager.engine, batches };
}

/** Runs `body` with console.warn captured; returns the collected messages. */
async function withCapturedWarnings(body) {
  const warnings = [];
  const original = console.warn;
  console.warn = (msg) => warnings.push(String(msg));
  try {
    await body();
  } finally {
    console.warn = original;
  }
  return warnings;
}

test('int32 wire samples arrive as normalised floats, both rails included', async () => {
  const { engine, batches } = await started();

  // Both rails plus a mid-scale word. −INT32_MIN is one LSB beyond −MAXINT, so the quotient
  // is fractionally past −1; Float32 (the raw capture width) lands it exactly on −1, which is
  // what the ring must see - never a value outside [−1, +1].
  engine.deliver(adcPayload([
    [MAXINT, INT32_MIN],
    [0, 1],
    [0x12345678, -0x12345678],
  ]));

  assert.equal(batches.length, 1);
  assert.equal(batches[0].n, 3);
  assert.deepEqual(batches[0].l, [1, 0, Math.fround(0x12345678 / MAXINT)]);
  assert.deepEqual(batches[0].r, [-1, Math.fround(1 / MAXINT), Math.fround(-0x12345678 / MAXINT)]);
  // The scale itself: a mid-scale word must land at its int32 fraction, NOT 256× hot (what
  // Java's 24-bit divisor would give here - the repack this port deliberately skips).
  assert.ok(Math.abs(batches[0].l[2] - 0.142222222) < 1e-7,
    `0x12345678 must normalise to ≈0.1422, was ${batches[0].l[2]}`);
});

test('the batch arrays are Float32Array - the raw capture width the ring widens', async () => {
  const manager = new FakeManager();
  const source = new Qa40xCaptureSource(manager);
  await source.open('', RATE_HZ);
  let seen = null;
  await source.start((b) => { seen = b; });

  manager.engine.deliver(adcPayload([[1, 2]]));

  assert.ok(seen.l instanceof Float32Array, 'l must be a Float32Array');
  assert.ok(seen.r instanceof Float32Array, 'r must be a Float32Array');
});

test('slot 0 is l (ch0) and slot 1 is r (ch1) - not swapped, right not inverted', async () => {
  const { engine, batches } = await started();

  // Asymmetric and signed on purpose: a swap, a sign flip on the right, or both, each
  // produce a different result here. r is ch1 - the calibrated/attenuated channel every
  // capture path in this app measures.
  const slot0 = 0x20000000;
  const slot1 = -0x40000000;
  engine.deliver(adcPayload([[slot0, slot1]]));

  assert.deepEqual(batches[0].l, [Math.fround(slot0 / MAXINT)], 'slot 0 must land in l');
  assert.deepEqual(batches[0].r, [Math.fround(slot1 / MAXINT)], 'slot 1 must land in r, sign intact');
  assert.ok(batches[0].r[0] < 0, 'the right channel must NOT be inverted (a QA401-only quirk)');
});

test('a pooled buffer is decoded to the TRANSFERRED length, not its own size', async () => {
  const { engine, batches } = await started();

  // The engine hands out 16 KB pooled read buffers and reports how much actually arrived.
  // Decoding buffer.byteLength instead would re-inject the previous transfer's tail as audio.
  const pooled = new Uint8Array(64);
  pooled.fill(0x7F);                                     // stale tail: full-scale-ish garbage
  pooled.set(adcPayload([[MAXINT, INT32_MIN], [0, 0]]), 0);

  engine.deliver(pooled, 2 * FRAME_BYTES);

  assert.equal(batches[0].n, 2, 'only the transferred frames may be published');
  assert.deepEqual(batches[0].l, [1, 0]);
  assert.deepEqual(batches[0].r, [-1, 0]);
});

test('a buffer that is a VIEW into a larger pool decodes from its own byteOffset', async () => {
  const { engine, batches } = await started();

  const pool = new Uint8Array(3 * FRAME_BYTES);
  pool.fill(0xEE);                                       // never part of the transfer
  const frame = adcPayload([[MAXINT, INT32_MIN]]);
  pool.set(frame, FRAME_BYTES);
  const view = pool.subarray(FRAME_BYTES, 2 * FRAME_BYTES);

  engine.deliver(view);

  assert.deepEqual(batches[0].l, [1], 'the DataView must honour the buffer byteOffset');
  assert.deepEqual(batches[0].r, [-1]);
});

test('the staging pair is REUSED across batches instead of reallocated per read', async () => {
  const manager = new FakeManager();
  const source = new Qa40xCaptureSource(manager);
  await source.open('', RATE_HZ);
  const seen = [];
  await source.start((b) => seen.push(b));

  manager.engine.deliver(adcPayload([[1, 2], [3, 4]]));
  manager.engine.deliver(adcPayload([[5, 6], [7, 8]]));

  assert.ok(seen[0].l === seen[1].l, 'l must be the same staging buffer (Java pools; ~100 reads/s)');
  assert.ok(seen[0].r === seen[1].r, 'r must be the same staging buffer');

  // A LARGER batch may grow the pair - but never shrink it, and it must still hold the
  // samples for the frames it reports.
  manager.engine.deliver(adcPayload(Array.from({ length: 5 }, (_, i) => [i + 1, -(i + 1)])));
  const grown = seen[2];
  assert.equal(grown.n, 5);
  assert.ok(grown.l.length >= 5, 'the staging pair must cover the reported frame count');
  assert.equal(grown.l[4], Math.fround(5 / MAXINT));
  assert.equal(grown.r[4], Math.fround(-5 / MAXINT));

  // The other half of "grow-only": a SMALLER read must decode into the SAME, still-larger pair.
  // Nothing above exercises that - the batches only ever grow - so a size test that reallocated
  // on ANY change would look identical here. USB read sizes do oscillate (a short transfer at
  // the tail of the device's queue), and reallocating on each swing is the per-read allocation
  // this pair exists to avoid, on a path that runs ~100×/s.
  manager.engine.deliver(adcPayload([[9, -9], [10, -10]]));
  const shrunk = seen[3];
  assert.ok(shrunk.l === grown.l, 'a smaller batch must NOT reallocate the staging pair');
  assert.ok(shrunk.r === grown.r);
  assert.equal(shrunk.l.length, 5, 'the pair keeps its high-water length');
  assert.equal(shrunk.n, 2, 'and publishes only the frames this read delivered');
  assert.equal(shrunk.l[1], Math.fround(10 / MAXINT), 'decoded into the reused pair');
  assert.equal(shrunk.r[1], Math.fround(-10 / MAXINT));
});

test('open acquires the engine for the requested rate; start attaches the lane', async () => {
  const manager = new FakeManager();
  const source = new Qa40xCaptureSource(manager);

  assert.equal(source.isOpen, false);
  assert.equal(source.sampleRate, 0);

  await source.open('ignored-device-id', RATE_HZ);
  assert.deepEqual(manager.rates, [RATE_HZ], 'the session rate reaches acquireEngine');
  assert.equal(source.isOpen, true);
  assert.equal(source.sampleRate, RATE_HZ, 'the QA40x delivers exactly the requested rate');
  assert.deepEqual(manager.engine.ops, [], 'open() alone must not start the stream');

  await source.start(() => {});
  assert.deepEqual(manager.engine.ops, ['attachCapture']);
});

test('start before open is refused (Java: "Call open() before startRecording()")', async () => {
  const source = new Qa40xCaptureSource(new FakeManager());
  await assert.rejects(() => source.start(() => {}), /Call open\(\) before start\(\)/);
});

test('stop detaches the lane exactly once, and close does not detach again', async () => {
  const { source, engine } = await started();

  await source.stop();
  assert.deepEqual(engine.ops, ['attachCapture', 'detachCapture']);
  assert.equal(engine.attached, false);

  await source.close();
  assert.deepEqual(engine.ops, ['attachCapture', 'detachCapture'],
    'close() after stop() must not detach a lane it no longer owns');
  assert.equal(source.isOpen, false);
});

test('close on a RUNNING source stops it first - no attach may outlive the source', async () => {
  const { source, engine } = await started();

  await source.close();

  assert.deepEqual(engine.ops, ['attachCapture', 'detachCapture'],
    'a leaked attach leaves the analyzer streaming with no consumer');
  assert.equal(engine.attached, false);
  assert.equal(source.isOpen, false);
});

test('a reopened source re-acquires the engine, which is where a rate change lands', async () => {
  const { source, manager } = await started();

  await source.stop();
  await source.close();
  await source.open('', 96_000);
  await source.start(() => {});

  assert.deepEqual(manager.rates, [RATE_HZ, 96_000],
    'the new rate must reach acquireEngine (one shared reg-9 clock)');
  assert.equal(source.sampleRate, 96_000);
  assert.deepEqual(manager.engine.ops, ['attachCapture', 'detachCapture', 'attachCapture']);
});

test('a read completing after stop is discarded, not dispatched and not counted', async () => {
  const manager = new FakeManager();
  const source = new Qa40xCaptureSource(manager);
  await source.open('', RATE_HZ);
  let calls = 0;
  const sink = (b) => { calls += b.n; };
  await source.start(sink);
  const payload = adcPayload([[1, 2]]);
  manager.engine.deliver(payload);
  assert.equal(calls, 1);

  // Java's `recording` gate: the lane callback the source handed over may still be invoked by
  // a read that was already in flight when the detach happened. It must discard, not decode
  // into a ring the owner has already released.
  await source.stop();
  manager.engine.lane(payload, payload.length);

  assert.equal(calls, 1, 'a post-stop read must not reach the ring');
  assert.equal(source.droppedBatches, 0, 'and it is not a dropped batch - nothing was lost');
});

test('a faulting consumer costs one counted batch, warned about, and the pump survives', async () => {
  const manager = new FakeManager();
  const source = new Qa40xCaptureSource(manager);
  await source.open('', RATE_HZ);
  let delivered = 0;
  let fault = true;
  await source.start(() => {
    delivered++;
    if (fault) throw new Error('consumer boom');
  });

  const warnings = await withCapturedWarnings(async () => {
    manager.engine.deliver(adcPayload([[1, 2]]));       // faults -> dropped + warned
    manager.engine.deliver(adcPayload([[3, 4]]));       // faults -> dropped, inside the rate limit
    fault = false;
    manager.engine.deliver(adcPayload([[5, 6]]));       // must still be delivered
  });

  assert.equal(source.droppedBatches, 2, 'every lost batch is counted');
  assert.equal(delivered, 3, 'a throwing consumer must not kill the capture pump');
  assert.equal(warnings.length, 1, 'the warning is rate-limited - one per interval, not per batch');
  assert.match(warnings[0], /QA40x capture: 1 batch\(es\) dropped/);
});

test('the drop counter resets per session, so an old session cannot mask a new overrun', async () => {
  const manager = new FakeManager();
  const source = new Qa40xCaptureSource(manager);
  await source.open('', RATE_HZ);
  await withCapturedWarnings(async () => {
    await source.start(() => { throw new Error('boom'); });
    manager.engine.deliver(adcPayload([[1, 2]]));
    assert.equal(source.droppedBatches, 1);
    await source.stop();

    await source.start(() => {});
    assert.equal(source.droppedBatches, 0, 'a fresh session starts from zero');
  });
});

test('a manager is required - a source with no engine supply is a wiring bug', () => {
  assert.throws(() => new Qa40xCaptureSource(null), /manager/);
});
