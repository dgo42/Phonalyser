/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * The remote capture stream on the CaptureSource seam: the lock taken for the stream's life,
 * the granted-rate re-pin, the native PCM decode, and the four things only spec 5 can produce
 * - a GAP confession, a sweepStart marker, a jumped packet counter, and a lane that ends
 * without anyone asking. No socket: the connection is a double.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

import { NetCaptureSource, decodeInterleaved } from '../js/net/net-capture-source.js';
import { NetRefusal } from '../js/net/net-connection.js';
import { NetCloseReason } from '../js/net/net-close-reason.js';
import { MessageType, NetFields, ErrorCode, FrameType, HEADER_BYTES, MARKER_SWEEP_START,
  parseBinaryFrame } from '../js/net/net-proto.js';
import { CaptureEndReason } from '../js/audio/capture-end-reason.js';
import { DeviceFailureReason } from '../js/enums/device-failure-reason.js';
import { makeNetDeviceRef } from '../js/net/net-device-ref.js';

const DEVICE = makeNetDeviceRef({ index: 0, name: 'QA403', description: 'd', vendor: 'v',
  isInput: true, remoteBackend: 'QA40X' });

class FakeConnection {
  constructor() {
    this.sent = [];
    this.streams = new Map();
    this.events = [];
    this.closeListeners = [];
    this._nextId = 0;
    this.answers = {};
    /** The data connections of spec 4.7 this source dialled, newest last - what a test asks
     *  whether the capture attached one at all, and whether it marked the close before it
     *  ended the stream. */
    this.dataConnections = [];
    /** When set, every attach is refused (spec 4.7) - the stale handle, the unknown capture
     *  and the one already attached all reach the client as the same failure. */
    this.refuseAttach = null;
  }
  newRequest(t) { return { t, id: ++this._nextId }; }
  /** Spec 4.7's dial-and-attach, as the source sees it: a handle with the two marks. */
  async attachData(captureId) {
    if (this.refuseAttach) throw this.refuseAttach;
    const data = {
      captureId, expectedClose: false, closed: false,
      expectClose() { this.expectedClose = true; },
      close() { this.expectedClose = true; this.closed = true; },
    };
    this.dataConnections.push(data);
    return data;
  }
  /** The data connection of the capture opened most recently, or null. */
  get lastData() {
    return this.dataConnections.length === 0 ? null
      : this.dataConnections[this.dataConnections.length - 1];
  }
  async request(m) {
    this.sent.push(m);
    const scripted = this.answers[m.t];
    const answer = typeof scripted === 'function' ? scripted(m) : scripted;
    return answer || { t: MessageType.RESP, id: m.id, ok: true, data: {} };
  }
  send(m) { this.sent.push(m); return Promise.resolve({ ok: true }); }
  refusal(what, answer) {
    const e = answer.error || {};
    return new NetRefusal(`${what}: ${e.code} - ${e.message}`,
      e[NetFields.REASON] ? DeviceFailureReason[e[NetFields.REASON]] : DeviceFailureReason.UNKNOWN);
  }
  addStreamListener(id, l) { this.streams.set(id, l); }
  removeStreamListener(id) { this.streams.delete(id); }
  addEventListener(l) { this.events.push(l); }
  removeEventListener(l) { this.events = this.events.filter((x) => x !== l); }
  addCloseListener(l) { this.closeListeners.push(l); }
  removeCloseListener(l) { this.closeListeners = this.closeListeners.filter((x) => x !== l); }
  deliver(event) { for (const l of [...this.events]) l(event); }
  endSession(reason) { for (const l of [...this.closeListeners]) l(reason); }
  /** Feeds one spec-5 frame to whoever listens on `streamId`. */
  frame(streamId, { typeCode, packetCounter, n, payload = new Uint8Array(0) }) {
    const buf = new ArrayBuffer(HEADER_BYTES + payload.byteLength);
    const view = new DataView(buf);
    view.setUint8(0, typeCode);
    view.setUint16(2, streamId, true);
    view.setBigUint64(4, BigInt(packetCounter), true);
    view.setUint32(12, n == null ? payload.byteLength : n, true);
    new Uint8Array(buf, HEADER_BYTES).set(payload);
    const listener = this.streams.get(streamId);
    if (listener) listener(parseBinaryFrame(buf));
  }
  ofType(t) { return this.sent.filter((m) => m.t === t); }
}

/** A manager double: the lock register and the fault hub, nothing else. */
class FakeOwner {
  constructor(formats = [{ rate: 48000, bits: 24, channels: 2 }]) {
    this.acquired = 0; this.released = 0; this.gaps = [];
    this.refuseAcquire = null;
    this._formats = formats;
  }
  async acquireDevice() { this.acquired++; return this.refuseAcquire; }
  async releaseDevice() { this.released++; }
  listSupportedFormats() { return this._formats; }
  gap(lost) { this.gaps.push(lost); }
  classifyFailure(e) { return (e instanceof NetRefusal) ? e.reason : DeviceFailureReason.UNKNOWN; }
}

/** An opened, started stream over a fake bench. */
async function streaming({ grantedRate = 48000, grantedBits = 24, owner = new FakeOwner() } = {}) {
  const connection = new FakeConnection();
  connection.answers[MessageType.CAPTURE_OPEN] = (m) => ({ t: MessageType.RESP, id: m.id, ok: true,
    data: { [NetFields.CAPTURE_ID]: 7, [NetFields.RATE]: grantedRate,
      [NetFields.BITS]: grantedBits, [NetFields.CHANNELS]: 2 } });
  const source = new NetCaptureSource({ connection, device: DEVICE, owner });
  await source.open('ignored', 48000);
  const batches = [];
  const ended = [];
  await source.start((b) => batches.push(b), (r) => ended.push(r));
  return { source, connection, owner, batches, ended };
}

test('spec 4.7: the capture attaches a data connection between the open and the start',
  async () => {
    const connection = new FakeConnection();
    connection.answers[MessageType.CAPTURE_OPEN] = (m) => ({ t: MessageType.RESP, id: m.id,
      ok: true, data: { [NetFields.CAPTURE_ID]: 7, [NetFields.RATE]: 48000,
        [NetFields.BITS]: 24, [NetFields.CHANNELS]: 2 } });
    const source = new NetCaptureSource({ connection, device: DEVICE, owner: new FakeOwner() });

    await source.open('ignored', 48000);

    assert.equal(connection.dataConnections.length, 1, 'one data connection per open capture');
    assert.equal(connection.lastData.captureId, 7, 'and it attaches to THIS capture');
    assert.equal(connection.ofType(MessageType.CAPTURE_START).length, 0,
      'the start comes after the attach, never before - the bench refuses it NOT_ATTACHED '
      + 'while the frames would have nowhere to go');
  });

test('spec 4.7: an attach the bench refuses costs nothing and reads as the open failing',
  async () => {
    const owner = new FakeOwner();
    const connection = new FakeConnection();
    connection.answers[MessageType.CAPTURE_OPEN] = (m) => ({ t: MessageType.RESP, id: m.id,
      ok: true, data: { [NetFields.CAPTURE_ID]: 7, [NetFields.RATE]: 48000,
        [NetFields.BITS]: 24, [NetFields.CHANNELS]: 2 } });
    connection.refuseAttach = new NetRefusal('cannot attach the data connection of capture 7',
      DeviceFailureReason.UNKNOWN);
    const source = new NetCaptureSource({ connection, device: DEVICE, owner });

    await assert.rejects(() => source.open('ignored', 48000), NetRefusal);

    assert.equal(owner.released, 1, 'the device lock goes back - a failed open costs nothing');
    assert.equal(connection.ofType(MessageType.CAPTURE_CLOSE).length, 1,
      'and the capture the bench had already opened is closed');
    assert.equal(source.isOpen, false);
  });

test('spec 4.7: the close MARKS the data connection before it ends the capture', async () => {
  const { source, connection } = await streaming();
  const data = connection.lastData;

  await source.close();

  assert.equal(data.expectedClose, true,
    'the capture.close makes the BENCH close this socket, and the answer travels on the other '
    + 'connection - so an unmarked close could reach this client first and read as spec 4.1\'s '
    + 'drop, ending the session over an ordinary teardown');
  assert.equal(data.closed, true, 'and it is closed here too, for a bench that left it to us');
});

test('spec 4.7: a stream that ends by itself marks its data connection too', async () => {
  const { source, connection } = await streaming();
  const data = connection.lastData;

  connection.endSession(NetCloseReason.KEEPALIVE_TIMEOUT);

  assert.equal(data.expectedClose, true,
    'the bench either caused this ending or already knows about it, so the socket closing '
    + 'after it is the end of a stream and not a second death report');
});

/** One PCM payload of `frames` stereo frames at 24 bit, left = +value, right = −value. */
function pcm24(frames, value) {
  const bytes = new Uint8Array(frames * 6);
  const view = new DataView(bytes.buffer);
  for (let i = 0; i < frames; i++) {
    const put = (at, v) => {
      const raw = v < 0 ? v + 0x1000000 : v;
      view.setUint8(at, raw & 0xFF); view.setUint8(at + 1, (raw >> 8) & 0xFF); view.setUint8(at + 2, (raw >> 16) & 0xFF);
    };
    put(i * 6, value); put(i * 6 + 3, -value);
  }
  return bytes;
}

test('C2: the bulk decode agrees with the portable one, at every width and both alignments',
  () => {
    // The decode was rewritten to read through typed-array views - one branch per BATCH instead
    // of a call with a switch per SAMPLE - because it runs on the socket's turn and its cost is
    // what decides whether this client keeps up at 192 kHz (and leaves room for 384). A
    // performance rewrite is only worth having if it decodes the same bytes to the same
    // numbers, so the fast paths are checked against the slow one they replaced.
    //
    // BOTH alignments matter: a real frame's payload starts at the 16-byte header (even, and a
    // multiple of 4 - the wide views are legal), while an ODD offset can only be read the
    // portable way. The two must not disagree by a sample.
    for (const bits of [8, 16, 24, 32]) {
      const bytesPerSample = bits / 8;
      const frames = 64;
      const body = new Uint8Array(frames * bytesPerSample * 2);
      for (let i = 0; i < body.length; i++) body[i] = (i * 37 + bits) & 0xFF;   // deterministic

      const aligned = new Uint8Array(16 + body.length);
      aligned.set(body, 16);
      const odd = new Uint8Array(1 + body.length);
      odd.set(body, 1);

      const fast = decodeInterleaved(new DataView(aligned.buffer, 16, body.length), bits);
      const slow = decodeInterleaved(new DataView(odd.buffer, 1, body.length), bits);

      assert.equal(fast.n, frames, `${bits} bit: frame count`);
      assert.deepEqual(Array.from(fast.l), Array.from(slow.l), `${bits} bit: left lane`);
      assert.deepEqual(Array.from(fast.r), Array.from(slow.r), `${bits} bit: right lane`);
      for (const v of fast.l) assert.ok(v >= -1 && v < 1, `${bits} bit: normalised to [-1, 1)`);
    }
  });

test('the decode is signed little-endian interleaved stereo, at every width', () => {
  // 16-bit: +full scale left, −full scale right.
  const b16 = new Uint8Array([0xFF, 0x7F, 0x00, 0x80]);
  const d16 = decodeInterleaved(new DataView(b16.buffer), 16);
  assert.equal(d16.n, 1);
  assert.ok(Math.abs(d16.l[0] - 0.999969) < 1e-5);
  assert.equal(d16.r[0], -1);

  // 24-bit is the one width a DataView cannot read directly - and the one an ADC speaks.
  const d24 = decodeInterleaved(new DataView(pcm24(2, 0x400000).buffer), 24);
  assert.equal(d24.n, 2);
  assert.ok(Math.abs(d24.l[0] - 0.5) < 1e-6, 'half full scale');
  assert.ok(Math.abs(d24.r[0] + 0.5) < 1e-6, 'and its negation, sign-extended');

  assert.equal(decodeInterleaved(new DataView(new ArrayBuffer(4)), 24), null,
    'a partial frame is not a frame');
});

test('open takes the lock, asks the device\'s widest width, and re-pins to the GRANTED rate', async () => {
  const owner = new FakeOwner([{ rate: 48000, bits: 16 }, { rate: 48000, bits: 24 }, { rate: 96000, bits: 32 }]);
  const { source, connection } = await streaming({ grantedRate: 44100, owner });

  assert.equal(owner.acquired, 1, 'spec 4.4: requires the input lock');
  const open = connection.ofType(MessageType.CAPTURE_OPEN)[0];
  assert.equal(open[NetFields.RATE], 48000);
  assert.equal(open[NetFields.BITS], 24, 'the widest the device offers AT that rate');
  assert.equal(open[NetFields.NAME], 'QA403', 'the ref\'s four fields ride along');
  assert.equal(source.sampleRate, 44100, 'the granted rate is what the analysis re-pins to');
  assert.equal(source.isOpen, true);
});

test('an open that fails costs nothing - the lock goes back before the throw', async () => {
  const owner = new FakeOwner();
  const connection = new FakeConnection();
  connection.answers[MessageType.CAPTURE_OPEN] = (m) => ({ t: MessageType.RESP, id: m.id, ok: false,
    error: { code: ErrorCode.DEVICE_ERROR, message: 'busy', [NetFields.REASON]: 'DEVICE_IN_USE' } });
  const source = new NetCaptureSource({ connection, device: DEVICE, owner });

  await assert.rejects(() => source.open('x', 48000), (e) => {
    assert.equal(e.reason, DeviceFailureReason.DEVICE_IN_USE, 'classified on the bench, carried here');
    return true;
  });
  assert.equal(owner.released, 1);
  assert.equal(source.isOpen, false);
});

test('the device counts as HELD until the release has been ANSWERED', async () => {
  // SharedCapture.contextOpen reads isOpen, and the measurement idle-wait reads that: it must
  // block until this session genuinely holds nothing on the bench. Reporting "free" the moment
  // the local capture handle was dropped let the taking-over measurement's device.acquire +
  // capture.open interleave with the previous consumer's device.release still in flight - the
  // bench processed the release BETWEEN them and answered NOT_LOCKED, refusing a sweep on a
  // device this same client had just given back. So the flag spans the WHOLE teardown.
  const owner = new FakeOwner();
  let answerRelease;
  owner.releaseDevice = () => new Promise((resolve) => { answerRelease = () => { owner.released++; resolve(); }; });
  const { source } = await streaming({ owner });
  assert.equal(source.isOpen, true, 'streaming');

  const closing = source.close();
  assert.equal(source.isOpen, true, 'held from the very first step of the teardown');
  // Let capture.close round-trip, so the teardown is now sitting on device.release.
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.equal(source.isOpen, true, 'still held while device.release is in flight');
  assert.equal(owner.released, 0, 'the bench has not answered the release yet');

  answerRelease();
  await closing;
  assert.equal(source.isOpen, false, 'and free once the bench has answered - not before');
  assert.equal(owner.released, 1);
});

test('a width this decoder cannot read is refused, not mis-read', async () => {
  const connection = new FakeConnection();
  connection.answers[MessageType.CAPTURE_OPEN] = (m) => ({ t: MessageType.RESP, id: m.id, ok: true,
    data: { [NetFields.CAPTURE_ID]: 1, [NetFields.RATE]: 48000, [NetFields.BITS]: 20, [NetFields.CHANNELS]: 2 } });
  const owner = new FakeOwner();
  const source = new NetCaptureSource({ connection, device: DEVICE, owner });

  await assert.rejects(() => source.open('x', 48000), /cannot decode/);
  assert.equal(connection.ofType(MessageType.CAPTURE_CLOSE).length, 1, 'the remote stream is closed');
  assert.equal(owner.released, 1, 'and the lock goes back');
});

test('PCM frames reach the pipeline; the counter runs over EVERY frame type', async () => {
  const { connection, batches, owner } = await streaming();

  connection.frame(7, { typeCode: FrameType.PCM, packetCounter: 0, payload: pcm24(4, 0x400000) });
  connection.frame(7, { typeCode: FrameType.GAP, packetCounter: 1, n: 128 });
  connection.frame(7, { typeCode: FrameType.PCM, packetCounter: 2, payload: pcm24(2, 0x200000) });

  assert.deepEqual(batches.map((b) => b.n), [4, 2]);
  assert.ok(Math.abs(batches[0].l[0] - 0.5) < 1e-6);
  assert.deepEqual(owner.gaps, [128], 'the confession reaches the fault hub, not the batch stream');
});

test('a jumped packet counter is a PROTOCOL error: the lane finishes and the server is stopped', async () => {
  const { connection, ended, batches } = await streaming();
  connection.frame(7, { typeCode: FrameType.PCM, packetCounter: 0, payload: pcm24(1, 1) });
  connection.sent.length = 0;

  connection.frame(7, { typeCode: FrameType.PCM, packetCounter: 5, payload: pcm24(1, 1) });

  assert.deepEqual(ended, [CaptureEndReason.DEVICE_LOST],
    'intact TCP neither loses nor reorders - the stream is broken, and the ring says so');
  assert.equal(connection.ofType(MessageType.CAPTURE_STOP).length, 1, 'the server is told to stop');
  assert.equal(connection.streams.has(7), false, 'and no frame is routed here again');
  assert.equal(batches.length, 1, 'the jumped frame is NOT delivered');
});

test('a sweepStart MARKER is reported at the PCM byte boundary it fell on', async () => {
  const { source, connection } = await streaming();
  const marks = [];
  const gaps = [];
  source.setMarkerListener({ marker: (kind, at) => marks.push([kind, at]), gap: (n, at) => gaps.push([n, at]) });

  connection.frame(7, { typeCode: FrameType.PCM, packetCounter: 0, payload: pcm24(10, 1) });   // 60 bytes
  connection.frame(7, { typeCode: FrameType.MARKER, packetCounter: 1, n: MARKER_SWEEP_START });
  connection.frame(7, { typeCode: FrameType.GAP, packetCounter: 2, n: 32 });

  assert.deepEqual(marks, [[MARKER_SWEEP_START, 60]],
    'spec 5: the first PCM byte AFTER the frame is sweep sample 0');
  assert.deepEqual(gaps, [[32, 60]], 'the assembler fails its own measurement honestly');
});

test('batches queued before a stop are counted and dropped', async () => {
  const { source, connection, batches } = await streaming();

  await source.stop();
  connection.frame(7, { typeCode: FrameType.PCM, packetCounter: 0, payload: pcm24(4, 1) });

  assert.equal(batches.length, 0, 'a batch reaching a module after its stop returned is a divergence');
  // ...and the counter still ran, so the next frame after a restart is not read as a jump.
  await source.start((b) => batches.push(b), () => {});
  connection.frame(7, { typeCode: FrameType.PCM, packetCounter: 1, payload: pcm24(4, 1) });
  assert.equal(batches.length, 1, 'spec 4.4: "counters keep their values"');
});

test('the bench\'s input lane failing finishes the lane - once', async () => {
  const { connection, ended } = await streaming();

  connection.deliver({ t: MessageType.EV_DEVICE_ERROR, [NetFields.DIRECTION]: NetFields.INPUT,
    [NetFields.DETAIL]: 'ADC gone', [NetFields.REASON]: 'DEVICE_DISCONNECTED' });
  connection.deliver({ t: MessageType.EV_DEVICE_ERROR, [NetFields.DIRECTION]: NetFields.INPUT,
    [NetFields.DETAIL]: 'again', [NetFields.REASON]: 'DEVICE_DISCONNECTED' });

  assert.deepEqual(ended, [CaptureEndReason.DEVICE_LOST], 'every ending is acted on exactly once');
});

test('an OUTPUT-lane failure is not this stream\'s to end', async () => {
  const { connection, ended } = await streaming();

  connection.deliver({ t: MessageType.EV_DEVICE_ERROR, [NetFields.DIRECTION]: NetFields.OUTPUT,
    [NetFields.DETAIL]: 'DAC gone', [NetFields.REASON]: 'DEVICE_DISCONNECTED' });

  assert.deepEqual(ended, [], 'it comes back out of the remote generator\'s own calls');
});

test('a session that dies takes the stream with it - including our own goodbye', async () => {
  const a = await streaming();
  a.connection.endSession(NetCloseReason.KEEPALIVE_TIMEOUT);
  assert.deepEqual(a.ended, [CaptureEndReason.DEVICE_LOST], 'spec 4.1: stop all modules');

  const b = await streaming();
  b.connection.endSession(NetCloseReason.BYE);
  assert.deepEqual(b.ended, [CaptureEndReason.DEVICE_LOST],
    'the bench is gone either way - a pane left feeding on it draws a measurement of nothing');
});

test('close ends the remote stream, gives the lock back, and unsubscribes', async () => {
  const { source, connection, owner } = await streaming();

  await source.close();

  assert.equal(connection.ofType(MessageType.CAPTURE_CLOSE).length, 1);
  assert.equal(owner.released, 1);
  assert.equal(connection.streams.size, 0);
  assert.equal(connection.events.length, 0);
  assert.equal(connection.closeListeners.length, 0);
  assert.equal(source.isOpen, false);
  assert.equal(source.sampleRate, 0);
});

test('close after the lane already ended still closes the remote stream and frees the device', async () => {
  const { source, connection, owner } = await streaming();
  connection.frame(7, { typeCode: FrameType.PCM, packetCounter: 9, payload: pcm24(1, 1) });   // a jump

  await source.close();

  assert.equal(connection.ofType(MessageType.CAPTURE_CLOSE).length, 1,
    'the handle is deliberately kept by the halt, exactly so this still works');
  assert.equal(owner.released, 1, 'a lock left behind is a device no other client can take');
});
