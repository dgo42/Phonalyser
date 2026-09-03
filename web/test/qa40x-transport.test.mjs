/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Contract test for the QA40x transport seam. Run with `npm test` (or
// `node --test web/test/qa40x-transport.test.mjs`).
//
// Proves WebUsbQa40xTransport (js/qa40x/webusb-qa40x-transport.js) against a
// FAKE USBDevice - a plain stub with transferIn / transferOut / claimInterface /
// releaseInterface / close - so the wire behaviour is pinned with no hardware
// and no browser:
//
//   1. register writes are 5-byte BIG-endian frames on endpoint 1;
//   2. a register read is the same frame with 0x80|reg and value 0, and the
//      4-byte big-endian reply decodes SIGNED;
//   3. endpoint-number mapping - registers 1, audio 2 (WebUSB infers direction);
//   4. head-await ordering - N transfers submitted, settled out of order in the
//      stub, still delivered to the listener in SUBMISSION order (this is what
//      keeps the sample stream contiguous, doc/QA40X-WEBUSB.md) - and the two
//      directions pump INDEPENDENTLY, so an outstanding capture transfer never
//      delays a playback completion;
//   5. a short capture transfer reports the bytes RECEIVED and an over-long one is
//      clipped to the caller's buffer;
//   6. cancelAll() releases + re-claims BEFORE the engine's reg 8 = RUN_STOP (one
//      ordered op log pins that cross-ordering), and close() cancels everything in
//      flight with NO unhandled rejections - including queued transfers no pump is
//      awaiting, which only the pre-release .catch() can keep handled;
//   7. a register transfer that never settles rejects on the REG_TIMEOUT_MS
//      deadline (Java's 1000 ms; WebUSB has no per-transfer timeout of its own)
//      and is abortable by cancelAll(), so neither can park the engine;
//   8. teardown is TOTAL: a rejecting releaseInterface() still leaves the queues
//      cleared and the pumps restartable.
//
// Plus a sanity pass over the FakeTransport test double (test/fake-qa40x-transport.mjs).

import test from 'node:test';
import assert from 'node:assert/strict';

import { WebUsbQa40xTransport, AUDIO_ENDPOINT, REG_ENDPOINT, REG_TIMEOUT_MS } from '../js/qa40x/webusb-qa40x-transport.js';
import { REGISTER_REPLY_BYTES } from '../js/qa40x/qa40x-protocol.js';
import { FakeTransport } from './fake-qa40x-transport.mjs';

/** Lets queued microtasks (and the transport's pumps) run to completion. */
const tick = () => new Promise((resolve) => setTimeout(resolve, 0));

/**
 * Minimal USBDevice stub. Register traffic settles immediately from a scripted
 * reply queue; audio traffic is DEFERRED, so a test decides when - and in which
 * order - each transfer settles.
 *
 * EVERYTHING the device is asked to do lands in ONE ordered log, `ops`: transfers,
 * claim / release and close alike. Separate per-kind logs cannot express a
 * cross-ordering - "reg 8 = RUN_STOP goes out AFTER the release + re-claim" is
 * exactly the property the stop discipline turns on (doc §7 step 7) - so the
 * single log is what sequencing assertions read.
 */
class FakeUsbDevice {

  constructor() {
    this.ops = [];               // THE ordered log: 'reg-out:8:0' / 'reg-in:4' / 'audio-out:16' / 'audio-in:16' / 'release:0' / 'claim:0' / 'close'
    this.outTransfers = [];      // {endpoint, bytes} for every transferOut - payload detail, not ordering
    this.inTransfers = [];       // {endpoint, length} for every transferIn - ditto
    this.regReplies = [];        // Uint8Array replies served on endpoint 1 IN
    this.audioIn = [];           // deferred capture transfers, in submission order
    this.audioOut = [];          // deferred playback transfers, in submission order
    this.closed = false;
    // A wedged device: the register transfer NEVER settles (WebUSB has no
    // per-transfer timeout, so nothing but the transport itself can end it).
    this.wedgeRegisterIn = false;
    this.wedgeRegisterOut = false;
    // Audio transfers that never settle AND are never even queued for the
    // release to abort - the WebUSB reality of a device that has stopped sending.
    // Counted, so a test can wedge just the head of a direction and leave the
    // tail abortable.
    this.wedgeAudioIn = 0;
    this.wedgeAudioOut = 0;
    // A release that fails - and therefore aborts NOTHING: every pending audio
    // transfer stays in limbo forever.
    this.failRelease = false;
    this.failClose = false;
  }

  transferOut(endpoint, data) {
    const bytes = Uint8Array.from(data);
    this.outTransfers.push({ endpoint, bytes });
    if (endpoint === AUDIO_ENDPOINT) {
      this.ops.push(`audio-out:${bytes.length}`);
      if (this.wedgeAudioOut > 0) {
        this.wedgeAudioOut--;
        return new Promise(() => {});
      }
      return this.#defer(this.audioOut, { endpoint, length: bytes.length });
    }
    this.ops.push(`reg-out:${bytes[0]}:${this.#frameValue(bytes)}`);
    if (this.wedgeRegisterOut) {
      return new Promise(() => {});
    }
    return Promise.resolve({ status: 'ok', bytesWritten: bytes.length });
  }

  transferIn(endpoint, length) {
    this.inTransfers.push({ endpoint, length });
    if (endpoint === AUDIO_ENDPOINT) {
      this.ops.push(`audio-in:${length}`);
      if (this.wedgeAudioIn > 0) {
        this.wedgeAudioIn--;
        return new Promise(() => {});
      }
      return this.#defer(this.audioIn, { endpoint, length });
    }
    this.ops.push(`reg-in:${length}`);
    if (this.wedgeRegisterIn) {
      return new Promise(() => {});
    }
    const reply = this.regReplies.shift();
    if (reply === undefined) {
      return Promise.resolve({ status: 'stall', data: null });
    }
    return Promise.resolve({ status: 'ok', data: new DataView(reply.buffer, reply.byteOffset, reply.byteLength) });
  }

  async claimInterface(index) {
    this.ops.push(`claim:${index}`);
  }

  async releaseInterface(index) {
    this.ops.push(`release:${index}`);
    if (this.failRelease) {
      // The release failed, so it aborted nothing: the pending transfers below are
      // left in limbo and can NEVER settle.
      throw new Error('NotFoundError: the interface was not found');
    }
    // WebUSB aborts every pending transfer on release - that is the only cancel
    // there is, so the stub models it as a rejection of all outstanding promises.
    const pending = [...this.audioIn, ...this.audioOut];
    this.audioIn = [];
    this.audioOut = [];
    for (const entry of pending) {
      entry.reject(new Error('The transfer was cancelled.'));
    }
  }

  async close() {
    this.ops.push('close');
    this.closed = true;
    if (this.failClose) {
      throw new Error('NetworkError: the device was disconnected');
    }
  }

  /** The claim/release subsequence of {@link ops} - the interface lifecycle on its
   *  own, so a teardown test can assert "released, and re-claimed or not" without
   *  spelling out every transfer that happened around it. Deliberately excludes
   *  'close': closing the device is not an interface operation, and cancelAll never
   *  does it. */
  get ifaceOps() {
    return this.ops.filter((op) => op.startsWith('claim:') || op.startsWith('release:'));
  }

  /** The big-endian value of a 5-byte register frame (doc §4). */
  #frameValue(bytes) {
    return ((bytes[1] << 24) | (bytes[2] << 16) | (bytes[3] << 8) | bytes[4]) >>> 0;
  }

  /** Queues a 4-byte big-endian register reply word. */
  pushRegReply(word) {
    const bytes = new Uint8Array(4);
    new DataView(bytes.buffer).setUint32(0, word >>> 0, false);
    this.regReplies.push(bytes);
  }

  /** Queues a raw (possibly short) register reply - for the malformed-reply path. */
  pushRegReplyBytes(bytes) {
    this.regReplies.push(bytes);
  }

  /** Settles capture transfer `index` with `payload`, out of order if you like. */
  completeIn(index, payload) {
    this.audioIn[index].resolve({
      status: 'ok',
      data: new DataView(payload.buffer, payload.byteOffset, payload.byteLength),
    });
  }

  /** Settles playback transfer `index`. */
  completeOut(index, bytesWritten) {
    this.audioOut[index].resolve({ status: 'ok', bytesWritten });
  }

  #defer(list, meta) {
    let resolve;
    let reject;
    const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
    list.push({ ...meta, resolve, reject, promise });
    return promise;
  }
}

/** Recording TransferListener: one ordered log of everything the transport reports. */
class RecordingListener {
  events = [];

  readCompleted(buffer, transferred) {
    this.events.push({ kind: 'read', buffer, transferred, bytes: Uint8Array.from(buffer.subarray(0, transferred)) });
  }

  writeCompleted(buffer, transferred) {
    this.events.push({ kind: 'write', buffer, transferred });
  }

  transferFailed(read, detail) {
    this.events.push({ kind: 'failed', read, detail });
  }
}

// --- 1/2/3: register frames, read requests, endpoint mapping ----------------

test('registerWrite emits a 5-byte big-endian frame on the register endpoint', async () => {
  const device = new FakeUsbDevice();
  const transport = new WebUsbQa40xTransport(device);

  await transport.registerWrite(0x09, 2);
  await transport.registerWrite(0x08, 0x05);

  assert.equal(device.outTransfers.length, 2);
  assert.equal(device.outTransfers[0].endpoint, REG_ENDPOINT);
  assert.equal(REG_ENDPOINT, 1);
  assert.deepEqual([...device.outTransfers[0].bytes], [0x09, 0x00, 0x00, 0x00, 0x02]);
  assert.deepEqual([...device.outTransfers[1].bytes], [0x08, 0x00, 0x00, 0x00, 0x05]);
});

test('registerWrite serialises a wide value MSB-first', async () => {
  const device = new FakeUsbDevice();
  const transport = new WebUsbQa40xTransport(device);

  await transport.registerWrite(0x0D, 0x12345678);

  assert.deepEqual([...device.outTransfers[0].bytes], [0x0D, 0x12, 0x34, 0x56, 0x78]);
});

test('registerRead sends 0x80|reg with value 0 and decodes the signed big-endian reply', async () => {
  const device = new FakeUsbDevice();
  const transport = new WebUsbQa40xTransport(device);
  device.pushRegReply(0x0000002A);
  device.pushRegReply(0xFFFFFFFF);

  const first = await transport.registerRead(0x19);
  const second = await transport.registerRead(0x0A);

  assert.equal(first, 42);
  assert.equal(second, -1, 'the reply decodes as a SIGNED 32-bit word, like Java int');
  assert.deepEqual([...device.outTransfers[0].bytes], [0x99, 0x00, 0x00, 0x00, 0x00], '0x80|0x19');
  assert.deepEqual([...device.outTransfers[1].bytes], [0x8A, 0x00, 0x00, 0x00, 0x00], '0x80|0x0A');
  assert.ok(device.outTransfers.every((t) => t.endpoint === REG_ENDPOINT));
  // The REPLY read is pinned too - endpoint AND requested length. A reply asked
  // for on the audio endpoint would steal a capture transfer's bytes, and asking
  // for more than the 4-byte reply frame lets a device pad the answer.
  assert.deepEqual(device.inTransfers, [
    { endpoint: REG_ENDPOINT, length: REGISTER_REPLY_BYTES },
    { endpoint: REG_ENDPOINT, length: REGISTER_REPLY_BYTES },
  ]);
  assert.equal(REGISTER_REPLY_BYTES, 4, "the reply frame is 4 big-endian bytes (Java's Qa40xProtocol)");
  assert.deepEqual(device.ops, ['reg-out:153:0', 'reg-in:4', 'reg-out:138:0', 'reg-in:4'],
    'request frame first, then the reply read - on the register endpoint both times');
});

test('registerRead rejects a short reply', async () => {
  const device = new FakeUsbDevice();
  const transport = new WebUsbQa40xTransport(device);
  device.pushRegReplyBytes(new Uint8Array([0x00, 0x01]));

  await assert.rejects(() => transport.registerRead(0x19), /expected 4 bytes, got 2/);
});

test('audio traffic uses the audio endpoint, registers the register endpoint', async () => {
  const device = new FakeUsbDevice();
  const transport = new WebUsbQa40xTransport(device);

  await transport.registerWrite(0x08, 0);
  transport.submitAudioRead(new Uint8Array(64));
  transport.submitAudioWrite(new Uint8Array(32), 16);

  assert.equal(AUDIO_ENDPOINT, 2);
  assert.equal(device.audioIn.length, 1);
  assert.equal(device.audioIn[0].endpoint, AUDIO_ENDPOINT);
  assert.equal(device.audioIn[0].length, 64, 'a capture transfer asks for the whole buffer');
  assert.equal(device.audioOut.length, 1);
  assert.equal(device.audioOut[0].endpoint, AUDIO_ENDPOINT);
  assert.equal(device.audioOut[0].length, 16, 'only the first `length` bytes are sent');
  assert.equal(device.outTransfers.filter((t) => t.endpoint === REG_ENDPOINT).length, 1);
  assert.deepEqual(device.inTransfers, [{ endpoint: AUDIO_ENDPOINT, length: 64 }]);
});

// --- 4: head-await ordering, per direction -----------------------------------

test('capture completions arrive in SUBMISSION order even when transfers settle out of order', async () => {
  const device = new FakeUsbDevice();
  const transport = new WebUsbQa40xTransport(device);
  const listener = new RecordingListener();
  transport.setListener(listener);

  const buffers = [new Uint8Array(4), new Uint8Array(4), new Uint8Array(4)];
  for (const buffer of buffers) {
    transport.submitAudioRead(buffer);
  }
  assert.equal(device.audioIn.length, 3);

  // Settle the LAST transfer first - nothing may be dispatched, because the
  // consumer awaits the queue HEAD rather than racing the promises.
  device.completeIn(2, new Uint8Array([3, 3, 3, 3]));
  await tick();
  assert.equal(listener.events.length, 0, 'a late head must hold back an early tail');

  device.completeIn(0, new Uint8Array([1, 1, 1, 1]));
  await tick();
  assert.equal(listener.events.length, 1, 'only the head is released; #2 still gates #3');

  device.completeIn(1, new Uint8Array([2, 2, 2, 2]));
  await tick();

  assert.equal(listener.events.length, 3);
  assert.deepEqual(listener.events.map((e) => e.kind), ['read', 'read', 'read']);
  assert.deepEqual(listener.events.map((e) => [...e.bytes]), [[1, 1, 1, 1], [2, 2, 2, 2], [3, 3, 3, 3]]);
  assert.equal(listener.events[0].buffer, buffers[0], 'each completion carries back its OWN buffer');
  assert.equal(listener.events[1].buffer, buffers[1]);
  assert.equal(listener.events[2].buffer, buffers[2]);
  assert.deepEqual(listener.events.map((e) => e.transferred), [4, 4, 4]);
});

test('playback completions arrive in submission order too, and re-submission from the callback keeps the pump alive', async () => {
  const device = new FakeUsbDevice();
  const transport = new WebUsbQa40xTransport(device);
  const first = new Uint8Array(8);
  const second = new Uint8Array(8);
  const third = new Uint8Array(8);
  const order = [];
  transport.setListener({
    readCompleted() {},
    writeCompleted(buffer, transferred) {
      order.push({ buffer, transferred });
      if (order.length === 2) {
        transport.submitAudioWrite(third, 8);   // re-arm re-entrantly, as the engine does
      }
    },
    transferFailed() {},
  });

  transport.submitAudioWrite(first, 8);
  transport.submitAudioWrite(second, 8);
  device.completeOut(1, 8);
  await tick();
  assert.equal(order.length, 0, 'the second write cannot overtake the first');

  device.completeOut(0, 8);
  await tick();
  assert.deepEqual(order.map((o) => o.buffer), [first, second]);
  assert.equal(device.audioOut.length, 3, 're-submission from the callback reached the device');

  device.completeOut(2, 8);
  await tick();
  assert.deepEqual(order.map((o) => o.buffer), [first, second, third]);
  assert.deepEqual(order.map((o) => o.transferred), [8, 8, 8]);
});

test('capture and playback pumps advance INDEPENDENTLY - a parked read never holds a write', async () => {
  // Both directions outstanding at once, which no other test does: the transport
  // keeps ONE queue and ONE pump PER DIRECTION, so a capture transfer that has not
  // settled (the normal state - the engine always has reads armed) must not delay a
  // playback completion by so much as a tick. A single shared queue, or a write pump
  // that awaits the read head, silently converts the duplex stream into a
  // half-duplex one and starves the DAC.
  const device = new FakeUsbDevice();
  const transport = new WebUsbQa40xTransport(device);
  const listener = new RecordingListener();
  transport.setListener(listener);

  const readBuffer = new Uint8Array(8);
  const firstWrite = new Uint8Array(8);
  const secondWrite = new Uint8Array(8);
  transport.submitAudioRead(readBuffer);        // stays in flight throughout the writes
  transport.submitAudioWrite(firstWrite, 8);
  transport.submitAudioWrite(secondWrite, 8);

  // Settle the writes OUT OF ORDER with the capture transfer still outstanding.
  device.completeOut(1, 8);
  await tick();
  assert.deepEqual(listener.events, [], 'the second write cannot overtake the first');

  device.completeOut(0, 8);
  await tick();
  assert.deepEqual(listener.events.map((e) => e.kind), ['write', 'write'],
    'the playback pump drained fully while the capture transfer was still in flight');
  assert.deepEqual(listener.events.map((e) => e.buffer), [firstWrite, secondWrite]);

  // And the capture pump then delivers on its own clock.
  device.completeIn(0, new Uint8Array([1, 2, 3, 4, 5, 6, 7, 8]));
  await tick();
  assert.deepEqual(listener.events.map((e) => e.kind), ['write', 'write', 'read']);
  assert.equal(listener.events[2].transferred, 8);
});

// --- 5: short / over-long capture transfers ----------------------------------

test('a SHORT capture transfer reports the bytes received, not the buffer size', async () => {
  // A bulk IN may return fewer bytes than asked for (a partial USB transfer at the
  // end of the device's queue). The transferred count the listener gets is what the
  // engine hands its consumer as `length`, so reporting the buffer size instead
  // feeds stale tail bytes into the FFT as if they were samples.
  const device = new FakeUsbDevice();
  const transport = new WebUsbQa40xTransport(device);
  const listener = new RecordingListener();
  transport.setListener(listener);

  const buffer = new Uint8Array(16);
  buffer.fill(0xAA);                   // pre-dirtied: only the received prefix may change
  transport.submitAudioRead(buffer);
  device.completeIn(0, new Uint8Array([1, 2, 3, 4]));
  await tick();

  assert.equal(listener.events.length, 1);
  assert.equal(listener.events[0].kind, 'read');
  assert.equal(listener.events[0].transferred, 4, 'a 4-byte transfer into a 16-byte buffer reports 4');
  assert.deepEqual([...buffer.subarray(0, 4)], [1, 2, 3, 4], 'the received bytes are copied in');
  assert.deepEqual([...buffer.subarray(4)], new Array(12).fill(0xAA),
    'and nothing beyond them is touched');
});

test('an over-long capture transfer is clipped to the buffer', async () => {
  // The other side of the same clamp: a babbling device must not overrun the
  // caller's buffer (a TypedArray.set past the end throws, killing the pump).
  const device = new FakeUsbDevice();
  const transport = new WebUsbQa40xTransport(device);
  const listener = new RecordingListener();
  transport.setListener(listener);

  const buffer = new Uint8Array(4);
  transport.submitAudioRead(buffer);
  device.completeIn(0, new Uint8Array([1, 2, 3, 4, 5, 6, 7, 8]));
  await tick();

  assert.equal(listener.events.length, 1, 'the pump survives an over-long transfer');
  assert.equal(listener.events[0].transferred, 4, 'clipped to the buffer length');
  assert.deepEqual([...buffer], [1, 2, 3, 4]);
});

// --- 6: stop discipline ------------------------------------------------------

test('cancelAll releases and re-claims interface 0, and RUN_STOP goes out AFTER the re-claim', async () => {
  const device = new FakeUsbDevice();
  const transport = new WebUsbQa40xTransport(device);
  transport.setListener(new RecordingListener());
  transport.submitAudioRead(new Uint8Array(16));

  await transport.cancelAll();
  await transport.registerWrite(0x08, 0x00);   // engine writes RUN_STOP right after (doc §7 step 7)

  // ONE ordered log, so the cross-ordering is pinned: release, re-claim, and only
  // THEN the reg 8 = RUN_STOP frame. Asserted from separate logs, a transport that
  // wrote RUN_STOP before releasing (or that never re-claimed before writing it,
  // i.e. wrote to an unclaimed interface) would look identical.
  assert.deepEqual(device.ops, ['audio-in:16', 'release:0', 'claim:0', 'reg-out:8:0']);
  assert.deepEqual([...device.outTransfers.at(-1).bytes], [0x08, 0x00, 0x00, 0x00, 0x00]);
  assert.equal(device.closed, false);
});

test('close cancels every in-flight transfer with no unhandled rejections', async () => {
  const unhandled = [];
  const onUnhandled = (reason) => unhandled.push(reason);
  process.on('unhandledRejection', onUnhandled);
  try {
    const device = new FakeUsbDevice();
    const transport = new WebUsbQa40xTransport(device);
    const listener = new RecordingListener();
    transport.setListener(listener);

    for (let i = 0; i < 3; i++) {
      transport.submitAudioRead(new Uint8Array(16));
    }
    transport.submitAudioWrite(new Uint8Array(16), 16);
    assert.equal(device.audioIn.length, 3);

    await transport.close();
    await new Promise((resolve) => setTimeout(resolve, 50));

    assert.deepEqual(unhandled, []);
    assert.deepEqual(device.ops, [
      'audio-in:16', 'audio-in:16', 'audio-in:16', 'audio-out:16', 'release:0', 'close',
    ], 'close releases, does NOT re-claim, and closes only after the release');
    assert.equal(device.closed, true);

    // Every aborted transfer is reported, exactly as libusb reports CANCELLED.
    const failures = listener.events.filter((e) => e.kind === 'failed');
    assert.equal(failures.length, 4);
    assert.equal(failures.filter((f) => f.read).length, 3);
    assert.equal(failures.filter((f) => !f.read).length, 1);

    assert.throws(() => transport.submitAudioRead(new Uint8Array(16)), /transport is closed/);
  } finally {
    process.off('unhandledRejection', onUnhandled);
  }
});

test('the teardown swallows the rejections of QUEUED transfers no pump is awaiting', async () => {
  // The real test of #swallowQueuedRejections. In the test above every queued
  // transfer is one the pump reaches and awaits, and awaiting attaches a handler -
  // so gutting the guard changes nothing there. Here each direction's HEAD is a
  // transfer that can never settle (WebUSB has no per-transfer cancel: once the
  // device stops sending, the promise simply never resolves), so both pumps park on
  // their heads and never touch the TAIL of either queue. releaseInterface() then
  // rejects those tail promises while nothing is awaiting them: only the .catch()
  // the guard attaches BEFORE the release can keep them handled - otherwise they
  // surface as unhandled rejections, which in a browser is a console error storm on
  // every stop and, under `--unhandled-rejections=strict`, a hard crash.
  const unhandled = [];
  const onUnhandled = (reason) => unhandled.push(reason);
  process.on('unhandledRejection', onUnhandled);
  try {
    const device = new FakeUsbDevice();
    device.wedgeAudioIn = 1;           // the capture pump parks on this one forever
    device.wedgeAudioOut = 1;          // and the playback pump on this one
    const transport = new WebUsbQa40xTransport(device);
    const listener = new RecordingListener();
    transport.setListener(listener);

    transport.submitAudioRead(new Uint8Array(16));      // wedged head
    transport.submitAudioRead(new Uint8Array(16));      // queued tail - abortable, unawaited
    transport.submitAudioRead(new Uint8Array(16));
    transport.submitAudioWrite(new Uint8Array(16), 16); // wedged head
    transport.submitAudioWrite(new Uint8Array(16), 16); // queued tail - abortable, unawaited
    await tick();

    assert.equal(device.audioIn.length, 2, 'the two abortable reads reached the stub');
    assert.equal(device.audioOut.length, 1, 'and the one abortable write');
    assert.deepEqual(listener.events, [],
      'both pumps are parked on their wedged heads - no pump handler is in play here');

    await transport.close();            // the release rejects every queued tail promise
    await new Promise((resolve) => setTimeout(resolve, 50));

    assert.deepEqual(unhandled, [],
      'no rejection may escape: every queued promise gets a .catch() BEFORE the release');
    assert.deepEqual(listener.events, [],
      'and the parked pumps still report nothing - the guard, not a pump, handled them');
  } finally {
    process.off('unhandledRejection', onUnhandled);
  }
});

test('a stalled transfer is reported through transferFailed', async () => {
  const device = new FakeUsbDevice();
  const transport = new WebUsbQa40xTransport(device);
  const listener = new RecordingListener();
  transport.setListener(listener);

  transport.submitAudioRead(new Uint8Array(8));
  device.audioIn[0].resolve({ status: 'stall', data: null });
  await tick();

  assert.deepEqual(listener.events, [{ kind: 'failed', read: true, detail: 'stall' }]);
});

// --- 7: register transfers can never wedge the engine ------------------------

test('a register transfer that never settles rejects on the REG_TIMEOUT_MS deadline', { timeout: 10_000 }, async () => {
  // WebUSB has NO per-transfer timeout: a wedged device's transferIn / transferOut
  // promise stays pending forever, and the engine's serialized register chain
  // parks behind it - no stop, no restart, no error. The transport therefore
  // imposes Java's REG_TIMEOUT_MS itself. The node:test timeout above bounds this
  // test, so a regression FAILS instead of hanging the suite.
  const inWedged = new FakeUsbDevice();
  inWedged.wedgeRegisterIn = true;
  const outWedged = new FakeUsbDevice();
  outWedged.wedgeRegisterOut = true;
  const readTransport = new WebUsbQa40xTransport(inWedged);
  const writeTransport = new WebUsbQa40xTransport(outWedged);

  const started = Date.now();
  await Promise.all([
    assert.rejects(() => readTransport.registerRead(0x19),
      /registerRead-reply\(0x19\) timed out after 1000 ms/),
    assert.rejects(() => writeTransport.registerWrite(0x08, 0x05),
      /registerWrite\(0x8\) timed out after 1000 ms/),
  ]);

  assert.equal(REG_TIMEOUT_MS, 1000, "Java's LibUsbQa40xTransport.REG_TIMEOUT_MS");
  assert.ok(Date.now() - started >= REG_TIMEOUT_MS - 100,
    'the rejection must come from the deadline, not from an early failure');
});

// The abort tests are bounded too: without the abort hook the pending register
// call can only end on the 1000 ms deadline (and, if that is gone too, never), so
// a regression FAILS on this timeout instead of hanging the suite.
test('cancelAll aborts a register transfer in flight, so a stop is never blocked by one', { timeout: 500 }, async () => {
  const device = new FakeUsbDevice();
  device.wedgeRegisterIn = true;
  const transport = new WebUsbQa40xTransport(device);

  const pending = transport.registerRead(0x19);
  // Handler attached BEFORE the stop, so the rejection can never be unhandled.
  const rejected = assert.rejects(() => pending, /registerRead-reply\(0x19\) aborted: cancelAll/);
  await tick();                        // the request frame goes out; the REPLY is what wedges
  assert.equal(device.outTransfers.length, 1);

  await transport.cancelAll();
  await rejected;                      // rejects on the abort, ~1 s before the deadline
});

test('close aborts a register transfer in flight', { timeout: 500 }, async () => {
  const device = new FakeUsbDevice();
  device.wedgeRegisterOut = true;
  const transport = new WebUsbQa40xTransport(device);

  const pending = transport.registerWrite(0x08, 0x00);
  const rejected = assert.rejects(() => pending, /registerWrite\(0x8\) aborted: close/);
  await transport.close();
  await rejected;
});

// --- 8: teardown is total, whatever the device does --------------------------

test('a rejecting releaseInterface still clears the queues and restarts the pump', async () => {
  const unhandled = [];
  const onUnhandled = (reason) => unhandled.push(reason);
  process.on('unhandledRejection', onUnhandled);
  try {
    const device = new FakeUsbDevice();
    device.failRelease = true;         // rejects - and therefore aborts nothing
    const transport = new WebUsbQa40xTransport(device);
    const listener = new RecordingListener();
    transport.setListener(listener);

    transport.submitAudioRead(new Uint8Array(8));       // stale: can NEVER settle
    transport.submitAudioWrite(new Uint8Array(8), 8);   // stale: can NEVER settle

    await transport.cancelAll();       // must not throw, whatever the device did
    await tick();

    // Restartable: the fresh transfers must be pumped and dispatched even though
    // the stale heads are wedged forever. Without the reset, cancelAll leaves the
    // stale entries queued and the pump flags stuck true, so nothing below is ever
    // delivered and the transport is dead with no way back.
    const freshRead = new Uint8Array(4);
    const freshWrite = new Uint8Array(4);
    transport.submitAudioRead(freshRead);
    transport.submitAudioWrite(freshWrite, 4);
    assert.equal(device.audioIn.length, 2, 'the stub still holds the wedged transfer plus the fresh one');
    device.completeIn(1, new Uint8Array([9, 9, 9, 9]));
    device.completeOut(1, 4);
    await tick();

    const reads = listener.events.filter((e) => e.kind === 'read');
    const writes = listener.events.filter((e) => e.kind === 'write');
    assert.equal(reads.length, 1, 'the restarted capture pump delivered the fresh read');
    assert.equal(reads[0].buffer, freshRead, 'and it is the FRESH buffer, not the abandoned one');
    assert.equal(writes.length, 1, 'the restarted playback pump delivered the fresh write');
    assert.equal(writes[0].buffer, freshWrite);

    // Registers still work: cancelAll warned about the failed re-claim, it did not throw.
    assert.deepEqual(device.ifaceOps, ['release:0'],
      'the rejecting release skipped the re-claim - and still must not wedge the transport');
    await transport.registerWrite(0x08, 0x00);
    assert.deepEqual([...device.outTransfers.at(-1).bytes], [0x08, 0x00, 0x00, 0x00, 0x00]);
    assert.deepEqual(unhandled, [], 'no queued rejection may escape a failed teardown');
  } finally {
    process.off('unhandledRejection', onUnhandled);
  }
});

test('a pump draining a DETACHED queue must not clear the LIVE pump flag', async () => {
  // The other half of #resetTransfers: it REPLACES the queue arrays instead of truncating them,
  // so a pump parked on an abandoned transfer keeps draining the array it captured. When that
  // stale pump finally runs dry it reaches the flag clear - and the flag by then belongs to the
  // pump of the NEW queue. Clearing it lets the next submit start a SECOND pump on the live
  // queue, and two pumps shifting the same array each await their own head, so completions are
  // dispatched in settlement order instead of submission order. That is the exact scrambling the
  // head-await discipline exists to prevent (module header: "visible only as unexplained FFT
  // noise") - no exception, no failed transfer, just wrong samples.
  //
  // The rejecting-release test above cannot see this: its stale transfers never settle, so the
  // stale pump never reaches the guard at all. Here it does.
  const device = new FakeUsbDevice();
  device.failRelease = true;               // aborts nothing: the stale transfer stays in limbo
  const transport = new WebUsbQa40xTransport(device);
  const listener = new RecordingListener();
  transport.setListener(listener);

  const stale = new Uint8Array(4);
  transport.submitAudioRead(stale);        // pump #1 shifts it and parks on it
  await tick();

  await transport.cancelAll();             // detaches the queue array and clears the flags
  const first = new Uint8Array(4);
  transport.submitAudioRead(first);        // pump #2, on the FRESH queue, parks on `first`
  await tick();

  // The abandoned transfer settles late - the device aborted it after all, or the interface went
  // away. Pump #1 drains its DETACHED array to empty and arrives at the flag clear.
  device.audioIn[0].reject(new Error('The transfer was cancelled.'));
  await tick();

  const second = new Uint8Array(4);
  transport.submitAudioRead(second);       // must be QUEUED behind `first`, not given its own pump
  await tick();

  // Settle out of order, tail first - the case a second pump gets wrong.
  device.completeIn(2, new Uint8Array([2, 2, 2, 2]));
  await tick();
  assert.equal(listener.events.filter((e) => e.kind === 'read').length, 0,
    'a late head must still hold back an early tail: one queue, ONE pump');

  device.completeIn(1, new Uint8Array([1, 1, 1, 1]));
  await tick();

  const reads = listener.events.filter((e) => e.kind === 'read');
  assert.equal(reads.length, 2);
  assert.equal(reads[0].buffer, first, 'submission order must survive a stale pump finishing');
  assert.equal(reads[1].buffer, second);
  assert.deepEqual(reads.map((e) => [...e.bytes]), [[1, 1, 1, 1], [2, 2, 2, 2]]);
  assert.equal(listener.events.filter((e) => e.kind === 'failed').length, 1,
    'the abandoned transfer is reported once, exactly as libusb reports CANCELLED');
});

test('close is total and idempotent even when the device refuses everything', async () => {
  const device = new FakeUsbDevice();
  device.failRelease = true;
  device.failClose = true;
  const transport = new WebUsbQa40xTransport(device);
  transport.setListener(new RecordingListener());
  transport.submitAudioRead(new Uint8Array(8));

  await transport.close();             // both device calls reject; close still resolves
  await transport.close();             // idempotent

  assert.equal(device.closed, true, 'a failed release must not skip the close');
  assert.deepEqual(device.ops, ['audio-in:8', 'release:0', 'close'], 'the second close is a no-op');
  assert.throws(() => transport.submitAudioRead(new Uint8Array(8)), /transport is closed/);
});

// --- the headless test double ------------------------------------------------

test('FakeTransport records register traffic and feeds synthetic audio', () => {
  const transport = new FakeTransport();
  const listener = new RecordingListener();
  transport.setListener(listener);
  transport.readReplies.push(0x1234);

  transport.registerWrite(0x08, 0x00);
  transport.registerWrite(0x09, 2);
  const word = transport.registerRead(0x19);
  const chunk = new Uint8Array(8);
  chunk.fill(7);
  transport.submitAudioWrite(chunk, 4);
  const readBuffer = new Uint8Array(4);
  transport.submitAudioRead(readBuffer);
  transport.cancelAll();
  transport.close();

  assert.equal(word, 0x1234);
  assert.equal(transport.registerRead(0x19), 0, 'an exhausted reply queue yields 0');
  assert.deepEqual(transport.registerWrites, [{ reg: 0x08, value: 0x00 }, { reg: 0x09, value: 2 }]);
  assert.deepEqual(transport.ops, ['reg=8:0', 'reg=9:2', 'read=25', 'cancelAll', 'close', 'read=25']);
  assert.equal(transport.cancelAllCount, 1);
  assert.deepEqual([...transport.submittedWrites[0]], [7, 7, 7, 7], 'the snapshot is cut to `length`');
  assert.equal(transport.totalWriteBytesSubmitted(), 4);

  transport.completeNextRead(new Uint8Array([1, 2, 3, 4, 5]));
  transport.completeNextWrite();

  assert.deepEqual(listener.events.map((e) => e.kind), ['read', 'write']);
  assert.deepEqual([...listener.events[0].bytes], [1, 2, 3, 4], 'the payload is clipped to the buffer');
  assert.equal(listener.events[0].buffer, readBuffer);
  assert.equal(listener.events[1].buffer, chunk);
});
