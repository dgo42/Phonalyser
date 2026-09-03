/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * The net session's contract, driven exactly as the Java client's own tests drive it: an
 * injected socket (the MockBench seam) and an injected ticker (FakeTicker), so there is no
 * real socket and no real clock anywhere in here - the whole keepalive death sequence is a
 * handful of calls.
 *
 * What is pinned: the hello handshake and its version negotiation (spec 1), envelope id
 * matching (4.0), the server's negative-id pings being answered, an unknown server REQUEST
 * answered UNSUPPORTED, the 4-missed-ping death (4.1) in both directions, the refusal's
 * reason field parsed through the R2.1 reason table, and the binary demux of spec 5 -
 * including a malformed frame ending the session instead of being dropped.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

import { NetConnection, NetRefusal } from '../js/net/net-connection.js';
import { NetCloseReason } from '../js/net/net-close-reason.js';
import { MessageType, ErrorCode, NetFields, NetProto, FrameType, HEADER_BYTES } from '../js/net/net-proto.js';
import { DeviceFailureReason } from '../js/enums/device-failure-reason.js';
import { twoPlaneFactory } from './fake-net-data-socket.mjs';

/** The session handle this bench answers `hello` with - spec 4.1's secret, and the only thing
 *  a `capture.attach` may name a session by. */
const CLIENT_ID = 'client-uuid-1';

/** The passage of time, in the test's hands (Java FakeTicker): nothing ticks until tick() is
 *  called, and a period never elapses on its own. */
class FakeTicker {
  constructor() { this.task = null; this.periodMs = 0; this.stopped = 0; }
  start(periodMs, task) { this.periodMs = periodMs; this.task = task; }
  stop() { this.task = null; this.stopped++; }
  /** Runs `n` whole keepalive periods. */
  tick(n = 1) { for (let i = 0; i < n; i++) if (this.task) this.task(); }
}

/** The bench, faked at the socket seam (Java MockBench): it records what the client sent and
 *  answers what the test scripts. Nothing here is asynchronous by accident - every delivery is
 *  an explicit call, so a test never waits on a timer. */
class MockBench {
  constructor({ autoHello = true, chosenProto = NetProto.PROTO_VERSION, helloError = null } = {}) {
    this.sent = [];              // every JSON message the client wrote
    this.binaryType = '';
    this.closed = 0;
    this.autoHello = autoHello;
    this.chosenProto = chosenProto;
    this.helloError = helloError;
    /** Pings the client sent that this bench has NOT answered (the keepalive test's knob). */
    this.answerPings = true;
    /** The data connections of spec 4.7 this bench has handed out, in dial order. */
    this.dataSockets = [];
    /** What an attach is answered with; refused when set to a `{ error }` (spec 4.7). */
    this.attachError = null;
    this.onopen = null; this.onmessage = null; this.onclose = null; this.onerror = null;
  }

  /** Spec 4.7: the bench binds the named capture to the socket the attach arrived on - or
   *  refuses, in which case it also closes that socket. */
  answerAttach(attach) {
    if (this.attachError) return { error: this.attachError };
    this.attachedTo = attach[NetFields.CAPTURE_ID];
    this.attachedAs = attach[NetFields.CLIENT_ID];
    return { data: {} };
  }

  /** The socket factory the connection is built with: spec 4's first dial is the control
   *  connection, and every one after it a capture's data connection. */
  factory() {
    return twoPlaneFactory(this, (attach) => this.answerAttach(attach), this.dataSockets);
  }

  /** Completes the WebSocket handshake - the client's open() waits for exactly this. */
  accept() { if (this.onopen) this.onopen(); }

  send(text) {
    const message = JSON.parse(text);
    this.sent.push(message);
    if (message.t === MessageType.HELLO && this.autoHello) {
      if (this.helloError) {
        this.respondError(message.id, this.helloError.code, this.helloError.message);
      } else {
        this.respond(message.id, {
          [NetFields.PROTO]: this.chosenProto,
          [NetFields.SERVER_ID]: 'server-uuid-1',
          [NetFields.NAME]: 'Bench QA403',
          [NetFields.APP]: '1.1.1',
          // Spec 4.1 (v2): the session handle every data connection attaches with.
          [NetFields.CLIENT_ID]: CLIENT_ID,
          [NetFields.CAPS]: ['qa40x', 'gen', 'files'],
        });
      }
      return;
    }
    if (message.t === MessageType.PING && this.answerPings) this.respond(message.id, {});
  }

  close() { this.closed++; }

  /** Delivers a successful resp for `id`. */
  respond(id, data) { this.deliver({ t: MessageType.RESP, id, ok: true, data }); }

  /** Delivers a refusal for `id`; `extra` carries spec 4.2's optional `by` / `reason`. */
  respondError(id, code, message, extra = {}) {
    this.deliver({ t: MessageType.RESP, id, ok: false, error: { code, message, ...extra } });
  }

  /** Delivers any text frame (an event, a server request). */
  deliver(message) { if (this.onmessage) this.onmessage({ data: JSON.stringify(message) }); }

  /** Delivers one binary frame of spec 5. */
  deliverFrame({ typeCode, streamId, packetCounter, n, payload = new Uint8Array(0) }) {
    const buf = new ArrayBuffer(HEADER_BYTES + payload.byteLength);
    const view = new DataView(buf);
    view.setUint8(0, typeCode);
    view.setUint16(2, streamId, true);
    view.setBigUint64(4, BigInt(packetCounter), true);
    view.setUint32(12, n == null ? payload.byteLength : n, true);
    new Uint8Array(buf, HEADER_BYTES).set(payload);
    if (this.onmessage) this.onmessage({ data: buf });
  }

  /** The messages of one type the client sent. */
  ofType(t) { return this.sent.filter((m) => m.t === t); }
}

/** An opened, handshaken session over a fresh MockBench. */
async function connected(options = {}) {
  const bench = new MockBench(options);
  const ticker = new FakeTicker();
  const conn = new NetConnection({
    url: 'ws://bench.local:8377/', clientName: 'Web client', clientApp: 'Phonalyser.web/1.1.1',
    ticker, socketFactory: bench.factory(),
  });
  const opening = conn.open(5000);
  bench.accept();
  await opening;
  return { conn, bench, ticker };
}

test('hello is FIRST, carries the offered range, and its answer governs the session', async () => {
  const { conn, bench } = await connected();

  assert.equal(bench.sent[0].t, MessageType.HELLO, 'spec 4.1: hello MUST be first');
  assert.equal(bench.sent[0][NetFields.PROTO], NetProto.PROTO_VERSION);
  assert.equal(bench.sent[0][NetFields.PROTO_MIN], NetProto.PROTO_MIN_VERSION);
  assert.equal(bench.sent[0][NetFields.CLIENT], 'Phonalyser.web/1.1.1');
  assert.equal(bench.sent[0][NetFields.NAME], 'Web client', 'the name a DEVICE_LOCKED names');

  assert.equal(conn.proto, NetProto.PROTO_VERSION,
    'the CHOSEN version governs the session');
  assert.equal(conn.serverId, 'server-uuid-1');
  assert.equal(conn.serverName, 'Bench QA403');
  assert.deepEqual(conn.caps, ['qa40x', 'gen', 'files']);
  assert.equal(conn.isOpen, true);
});

test('a chosen proto outside the offered range is PROTO_MISMATCH, and closes', async () => {
  const bench = new MockBench({ chosenProto: 7 });
  const ticker = new FakeTicker();
  const conn = new NetConnection({
    url: 'ws://bench.local:8377/', clientName: 'c', clientApp: 'a', ticker,
    socketFactory: bench.factory(),
  });
  const opening = conn.open(5000);
  bench.accept();

  await assert.rejects(() => opening, (e) => {
    assert.equal(e.code, ErrorCode.PROTO_MISMATCH);
    assert.match(e.message, /chose proto 7/);
    return true;
  });
  assert.equal(conn.isOpen, false, 'no session may run at a version this client cannot speak');
  assert.equal(bench.closed, 1);
});

test('a refused hello surfaces the server\'s code and closes', async () => {
  const bench = new MockBench({ helloError: { code: ErrorCode.PROTO_MISMATCH, message: 'v2..v3 only' } });
  const ticker = new FakeTicker();
  const conn = new NetConnection({
    url: 'ws://bench.local:8377/', clientName: 'c', clientApp: 'a', ticker,
    socketFactory: bench.factory(),
  });
  const opening = conn.open(5000);
  bench.accept();

  await assert.rejects(() => opening, /refused the session: PROTO_MISMATCH - v2\.\.v3 only/);
  assert.equal(conn.isOpen, false);
});

test('ids are per-connection monotonic and answers match by id, out of order', async () => {
  const { conn, bench } = await connected();

  const first = conn.request(conn.newRequest(MessageType.DEVICES_LIST));
  const second = conn.request(conn.newRequest(MessageType.CARDS_LIST));
  const [a, b] = bench.sent.slice(1);
  assert.equal(b.id, a.id + 1, 'spec 4.0: a per-connection monotonically increasing integer');

  // Answered in the WRONG order on purpose: correlation is by id, never by arrival.
  bench.respond(b.id, { marker: 'cards' });
  bench.respond(a.id, { marker: 'devices' });

  assert.equal((await first).data.marker, 'devices');
  assert.equal((await second).data.marker, 'cards');
});

test('the server\'s negative-id ping is answered with the resp envelope', async () => {
  const { bench } = await connected();
  bench.sent.length = 0;

  bench.deliver({ t: MessageType.PING, id: -3 });

  assert.deepEqual(bench.sent, [{ t: MessageType.RESP, id: -3, ok: true, data: {} }]);
});

test('an unknown server REQUEST is answered UNSUPPORTED; an unknown EVENT is not', async () => {
  const { conn, bench } = await connected();
  const events = [];
  conn.addEventListener((m) => events.push(m));
  bench.sent.length = 0;

  bench.deliver({ t: 'ev.something.new', payload: 1 });   // no id ⇒ an event
  assert.deepEqual(bench.sent, [], 'events are never answered');
  assert.equal(events.length, 1, 'and are handed to the listeners, unknown or not');

  bench.deliver({ t: 'somethingNew', id: -9 });           // an id ⇒ a REQUEST
  assert.equal(bench.sent.length, 1);
  assert.equal(bench.sent[0].ok, false);
  assert.equal(bench.sent[0].error.code, ErrorCode.UNSUPPORTED,
    'spec 1: an older peer stays predictable - it refuses instead of going silent');
});

test('four unanswered pings end the session; an answered one keeps it alive', async () => {
  const { conn, bench, ticker } = await connected();
  const closes = [];
  conn.addCloseListener((r) => closes.push(r));
  assert.equal(ticker.periodMs, NetProto.PING_INTERVAL_MS, 'spec 4.1: 500 ms, both ways');

  // Answered pings: the count never builds up.
  ticker.tick(10);
  await Promise.resolve();
  assert.equal(conn.isOpen, true);
  assert.equal(bench.ofType(MessageType.PING).length, 10);

  bench.answerPings = false;
  ticker.tick(4);                       // 4 sent, none answered
  await Promise.resolve();
  assert.equal(conn.isOpen, true, 'the count is checked BEFORE the next ping goes out');
  ticker.tick(1);                       // the 5th tick sees 4 unanswered = 2 s of silence

  assert.equal(conn.isOpen, false);
  assert.deepEqual(closes, [NetCloseReason.KEEPALIVE_TIMEOUT]);
  assert.equal(ticker.stopped >= 1, true, 'the keepalive stops with the session');
});

test('a request still waiting fails AT the close, not at its timeout', async () => {
  const { conn, bench, ticker } = await connected();
  const pending = conn.request(conn.newRequest(MessageType.CAPTURE_OPEN));

  bench.answerPings = false;
  ticker.tick(5);

  await assert.rejects(() => pending, /ended: the server stopped answering/);
});

test('a refusal carries the code, the holder and the far end\'s REASON', async () => {
  const { conn, bench } = await connected();

  const asking = conn.request(conn.newRequest(MessageType.DEVICE_ACQUIRE));
  const id = bench.sent[bench.sent.length - 1].id;
  bench.respondError(id, ErrorCode.DEVICE_LOCKED, 'in use', { by: "Developer's laptop" });
  const answer = await asking;

  assert.equal(answer.ok, false, 'a refusal is an ANSWER, not a failure');
  const refusal = conn.refusal('cannot take the device', answer);
  assert.ok(refusal instanceof NetRefusal);
  assert.match(refusal.message, /DEVICE_LOCKED - in use \(held by Developer's laptop\)/);
  assert.equal(refusal.reason, DeviceFailureReason.UNKNOWN, 'a lock is nobody\'s device failure');
});

test('a DEVICE_ERROR refusal\'s reason is read through the shared reason table', async () => {
  const { conn, bench } = await connected();

  const asking = conn.request(conn.newRequest(MessageType.CAPTURE_OPEN));
  const id = bench.sent[bench.sent.length - 1].id;
  bench.respondError(id, ErrorCode.DEVICE_ERROR, 'PaErrorCode -9996',
    { [NetFields.REASON]: 'DEVICE_IN_USE' });

  const refusal = conn.refusal('cannot open the capture', await asking);
  assert.equal(refusal.reason, DeviceFailureReason.DEVICE_IN_USE,
    'the classification was done on the bench; this only carries it the last hop');

  // ...and an older bench that sends none, or a value this build never heard of, reads UNKNOWN.
  const asking2 = conn.request(conn.newRequest(MessageType.CAPTURE_OPEN));
  const id2 = bench.sent[bench.sent.length - 1].id;
  bench.respondError(id2, ErrorCode.DEVICE_ERROR, 'no idea', { [NetFields.REASON]: 'SOMETHING_NEW' });
  assert.equal(conn.refusal('x', await asking2).reason, DeviceFailureReason.UNKNOWN);
});

/** Waits for the connection's frame-drain slice, which runs on the next turn (setImmediate is
 *  the poster node picks). Two hops, so a slice that reschedules itself has run too. */
const drained = () => new Promise((resolve) => setImmediate(() => setImmediate(resolve)));

test('binary frames are demuxed by streamId; a closed stream\'s late frames are dropped', async () => {
  const { conn, bench } = await connected();
  const seen = [];
  conn.addStreamListener(7, (f) => seen.push(f));

  bench.deliverFrame({ typeCode: FrameType.PCM, streamId: 7, packetCounter: 0, payload: new Uint8Array([1, 2, 3, 4]) });
  bench.deliverFrame({ typeCode: FrameType.GAP, streamId: 7, packetCounter: 1, n: 128 });
  bench.deliverFrame({ typeCode: FrameType.PCM, streamId: 9, packetCounter: 0, payload: new Uint8Array([9]) });
  // Frames are handed to their streams on the NEXT turn, not inside the socket's own (C2/C6):
  // the yield is what lets the keepalive be answered while a backlog drains.
  await drained();

  assert.equal(seen.length, 2, 'only stream 7\'s frames reach stream 7');
  assert.equal(seen[0].typeCode, FrameType.PCM);
  assert.equal(seen[0].payload.byteLength, 4);
  assert.equal(seen[1].typeCode, FrameType.GAP);
  assert.equal(seen[1].n, 128, 'GAP\'s n is the count of lost stereo frames');
  assert.equal(seen[1].packetCounter, 1, 'and it counts like every other frame');

  conn.removeStreamListener(7);
  bench.deliverFrame({ typeCode: FrameType.PCM, streamId: 7, packetCounter: 2, payload: new Uint8Array([5]) });
  await drained();
  assert.equal(seen.length, 2, 'a close races the frames in flight, and that race is ordinary');
});

test('C2/C6: the server\'s keepalive is answered WHILE a frame backlog is still queued',
  async () => {
    // The bench's report: at 192 kHz the server logged "the client is not keeping up" and then
    // "4 pings unanswered - connection dead". The cause was that every frame ran the whole
    // consumer inside the socket's own turn, so the ping - a text message in the SAME queue -
    // waited behind the entire backlog. The drain is now sliced, and this is the contract.
    const bench = new MockBench();
    const ticker = new FakeTicker();
    // The yield, driven by hand: nothing drains until the test says so, which is exactly the
    // state a busy client is in when the ping arrives.
    const slices = [];
    const conn = new NetConnection({
      url: 'ws://bench.local:8377/', clientName: 'Web client', clientApp: 'Phonalyser.web/1.1.1',
      ticker, socketFactory: bench.factory(), postTask: (fn) => slices.push(fn),
    });
    const opening = conn.open(1000);
    bench.accept();
    await opening;

    const seen = [];
    conn.addStreamListener(3, (f) => seen.push(f.packetCounter));
    for (let i = 0; i < 50; i++) {
      bench.deliverFrame({ typeCode: FrameType.PCM, streamId: 3, packetCounter: i,
        payload: new Uint8Array([1, 2, 3, 4]) });
    }
    assert.equal(seen.length, 0, 'the socket\'s turn hands nothing to the consumer');
    assert.equal(slices.length, 1, 'a burst of frames asks for ONE drain slice, not fifty');

    // The server pings mid-backlog (spec 4.0: its requests carry negative ids).
    bench.deliver({ t: MessageType.PING, id: -7 });

    const answer = bench.ofType(MessageType.RESP).find((m) => m.id === -7);
    assert.ok(answer, 'answered on the spot, with 50 frames still waiting - this is what stops '
      + 'the server counting four unanswered pings and declaring the connection dead');
    assert.equal(answer.ok, true);
    assert.equal(seen.length, 0, 'and answering it did not drain the backlog either');

    slices.shift()();
    assert.equal(seen.length, 50, 'the backlog then drains, in arrival order');
    assert.deepEqual(seen.slice(0, 3), [0, 1, 2]);
  });

test('spec 4.7: a capture dials a SECOND socket and declares it with capture.attach', async () => {
  const { conn, bench } = await connected();

  const data = await conn.attachData(9);

  assert.equal(bench.dataSockets.length, 1, 'one data connection per open capture');
  assert.equal(bench.dataSockets[0], data._socket, 'and the handle names that socket');
  const attach = bench.dataSockets[0].sent[0];
  assert.equal(attach.t, MessageType.CAPTURE_ATTACH,
    'its FIRST message is what makes it a data connection (spec 4)');
  assert.equal(attach[NetFields.CLIENT_ID], CLIENT_ID,
    'named by the handle the hello answered - never by the client name');
  assert.equal(attach[NetFields.CAPTURE_ID], 9);
  assert.equal(bench.sent.some((m) => m.t === MessageType.CAPTURE_ATTACH), false,
    'and it never touches the control connection');

  const seen = [];
  conn.addStreamListener(9, (f) => seen.push(f.packetCounter));
  bench.dataSockets[0].deliverFrame({ typeCode: FrameType.PCM, streamId: 9, packetCounter: 0,
    payload: new Uint8Array([1, 2, 3, 4]) });
  await drained();
  assert.deepEqual(seen, [0], 'the frames of that capture arrive through the same demux');
});

test('spec 4.7: a refused attach throws and leaves the SESSION running', async () => {
  const { conn, bench } = await connected();
  bench.attachError = { code: ErrorCode.BAD_REQUEST, message: 'no such capture' };

  await assert.rejects(() => conn.attachData(4), (e) => {
    assert.match(e.message, /cannot attach the data connection of capture 4/);
    return true;
  });

  assert.equal(bench.dataSockets[0].closed, 1,
    'the socket is closed - a connection that may not attach has no other purpose');
  assert.equal(conn.isOpen, true,
    'and the refusal is the CAPTURE\'s failure, not the bench dying: the client surfaces it '
    + 'as a capture.open that did not work');
});

test('spec 4.1: a data connection that DROPS ends the whole session', async () => {
  const { conn, bench } = await connected();
  const ended = [];
  conn.addCloseListener((reason) => ended.push(reason));
  await conn.attachData(2);

  bench.dataSockets[0].drop();

  assert.equal(conn.isOpen, false,
    'one connection dropping is the session dead - the control connection is healthy here, '
    + 'so nothing else would ever notice that the audio stopped arriving');
  assert.equal(ended.length, 1, 'and every module is told, once');
});

test('spec 4.7: closing a data connection is expected, and ends no session', async () => {
  const { conn } = await connected();
  const ended = [];
  conn.addCloseListener((reason) => ended.push(reason));
  const data = await conn.attachData(2);

  data.expectClose();
  data.close();

  assert.equal(conn.isOpen, true,
    'a socket this end closed is the ordinary end of a stream, not spec 4.1\'s drop');
  assert.equal(ended.length, 0);
});

test('spec 4.7: an UNMARKED normal close is still the ordinary end of a capture', async () => {
  const { conn, bench } = await connected();
  const ended = [];
  conn.addCloseListener((reason) => ended.push(reason));
  await conn.attachData(2);

  // Nothing marked here: the bench closes this socket as it handles a capture.close, and its
  // resp travels on the OTHER connection - so the close can arrive first. The STATUS CODE is
  // what makes the decision race-free.
  bench.dataSockets[0].close();

  assert.equal(conn.isOpen, true,
    'a NORMAL close is orderly whether or not this end had marked the socket in time - a rule '
    + 'that needed the mark would end the session over an ordinary teardown');
  assert.equal(ended.length, 0);
});

test('spec 4.7: control text on an attached data connection ends the session', async () => {
  const { conn, bench } = await connected();
  const ended = [];
  conn.addCloseListener((reason) => ended.push(reason));
  await conn.attachData(2);

  bench.dataSockets[0].pushText({ t: MessageType.EV_DEVICES_CHANGED, backends: [] });

  assert.equal(conn.isOpen, false,
    'the planes do not mix: handling it would put control traffic behind this capture\'s audio, '
    + 'which is the very thing the split exists to prevent');
  assert.equal(ended[0], NetCloseReason.PROTOCOL_ERROR,
    'and it ends the same way a malformed frame does - a peer whose framing is broken is not '
    + 'one to keep measuring with');
});

test('a malformed binary frame ENDS the session rather than being dropped', async () => {
  const { conn, bench } = await connected();
  const closes = [];
  conn.addCloseListener((r) => closes.push(r));

  // A PCM frame whose declared n disagrees with what it carries: the next frame's counter
  // would otherwise be reported as transport loss - the wrong fault, on a stream that would
  // meanwhile go on splicing.
  bench.deliverFrame({ typeCode: FrameType.PCM, streamId: 1, packetCounter: 0, n: 999, payload: new Uint8Array([1, 2]) });

  assert.deepEqual(closes, [NetCloseReason.PROTOCOL_ERROR]);
  assert.equal(conn.isOpen, false);
});

test('close(BYE) says goodbye first, then fails everything still waiting', async () => {
  const { conn, bench } = await connected();
  const pending = conn.request(conn.newRequest(MessageType.DEVICES_LIST));
  bench.sent.length = 0;

  conn.close(NetCloseReason.BYE);

  assert.equal(bench.sent[0].t, MessageType.BYE, 'so the server frees the locks at once');
  assert.equal(bench.closed, 1);
  await assert.rejects(() => pending, /ended: the client closed the session/);

  conn.close(NetCloseReason.TRANSPORT_CLOSED);   // idempotent
  assert.equal(bench.closed, 1);
});

// --- the dial's budget (R2.2-10: the handshake edges the per-item tests did not reach) ---

test('a host that never completes the WebSocket handshake fails the DIAL, and drops the socket',
  async () => {
    const bench = new MockBench();
    const conn = new NetConnection({
      url: 'ws://absent.local:8377/', clientName: 'Web client', clientApp: 'Phonalyser.web/1.1.1',
      ticker: new FakeTicker(), socketFactory: bench.factory(),
    });

    // No accept() - the socket never opens, which is what a dead host on the LAN looks like.
    await assert.rejects(() => conn.open(20), /no Phonalyser server answered/);
    assert.equal(bench.closed, 1, 'the half-open socket is not left dangling');
    assert.equal(bench.sent.length, 0, 'and nothing was sent into it - hello is FIRST or nothing');
  });

test('a host that accepts the socket and then says nothing does not hold the dialog for the '
  + 'request timeout', async () => {
  // Spec 1 + the open() contract: `timeoutMs` bounds the WHOLE handshake, socket and hello
  // together. Without the shared deadline this wait would be REQUEST_TIMEOUT_MS (10 s) - the
  // connect button would look wedged for ten seconds on a host that is not a Phonalyser server.
  const bench = new MockBench({ autoHello: false });
  const conn = new NetConnection({
    url: 'ws://mute.local:8377/', clientName: 'Web client', clientApp: 'Phonalyser.web/1.1.1',
    ticker: new FakeTicker(), socketFactory: bench.factory(),
  });

  const opening = conn.open(30);
  bench.accept();
  const started = Date.now();
  await assert.rejects(() => opening, /did not answer hello/);
  assert.ok(Date.now() - started < 2000, 'the dial is bounded by its own budget, not by the request one');
  assert.equal(bench.ofType(MessageType.HELLO).length, 1, 'and hello WAS the first thing sent');
});

test('the UI\'s ask is bounded tighter than a device operation, and a timeout forgets it',
  async () => {
    // The two budgets are the maintainer's 2026-08-01 order: a dialog-path ask (backend / device /
    // card lists) may hold the operator for ~1 s, a device operation for the full 10 s. Driven
    // through a setTimeout spy rather than a real wait, so the test is instant AND exact.
    const { conn, bench } = await connected();
    const realSetTimeout = globalThis.setTimeout;
    const scheduled = [];
    globalThis.setTimeout = (fn, ms) => { scheduled.push({ fn, ms }); return { unref() {} }; };
    try {
      const ui = conn.uiRequest(conn.newRequest(MessageType.CARDS_LIST));
      const device = conn.request(conn.newRequest(MessageType.DEVICE_ACQUIRE));
      assert.deepEqual(scheduled.map((s) => s.ms), [1000, 10000]);

      scheduled[0].fn();                                   // the UI ask times out
      await assert.rejects(() => ui, /did not answer cards.list within 1000 ms/);
      // The pending entry is GONE: a late answer must not resolve a caller that already gave up.
      bench.respond(bench.ofType(MessageType.CARDS_LIST)[0].id, {});
      scheduled[1].fn();
      await assert.rejects(() => device, /did not answer device.acquire within 10000 ms/);
    } finally { globalThis.setTimeout = realSetTimeout; }
  });
