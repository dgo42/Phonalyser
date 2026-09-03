/*
 * Phonalyser web - the far end of a capture's data connection, faked.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Spec 4.7 gives every open capture a WebSocket of its own, dialled to the same url as the
 * control connection and declared by `capture.attach`. Every net test that opens a capture
 * therefore needs a bench that hands out a SECOND socket - so the seam lives here rather than
 * being written out again in each of them (the same reason fake-qa40x-transport.mjs exists).
 *
 * It is the socket only: what a bench DOES with an attach - which sessions and captures it
 * knows, which refusals it raises - stays with the bench double that owns that state.
 */
import { MessageType, HEADER_BYTES } from '../js/net/net-proto.js';

/** RFC 6455's "normal closure" - what an orderly close of a finished capture's data connection
 *  carries (spec 4.7), and what tells it apart from a socket that simply died. */
const NORMAL_CLOSURE = 1000;
/** RFC 6455's "abnormal closure": no close frame ever arrived - a pulled socket. */
const ABNORMAL_CLOSURE = 1006;

/**
 * One data connection's far end. It opens on the next turn (a real dial is never synchronous),
 * answers the `capture.attach` it is given, and then carries binary frames one way.
 */
export class FakeDataSocket {

  /** @param {(attach: Object) => Object} answerAttach the bench's own verdict on the attach:
   *         the `resp` body to send back, `{ error: {...} }` for a refusal */
  constructor(answerAttach) {
    this._answerAttach = answerAttach;
    /** Every message the client wrote here - exactly one `capture.attach`, if the client keeps
     *  spec 4.7's rule that a data connection has nothing else to say. */
    this.sent = [];
    this.closed = 0;
    this.binaryType = '';
    this.attachedTo = null;
    this.onopen = null; this.onmessage = null; this.onclose = null; this.onerror = null;
    // The dial completes on a later turn, like a real one: a socket that was already open when
    // the factory returned would hide every ordering bug between the dial and the attach.
    const open = () => { if (this.onopen) this.onopen(); };
    if (typeof setImmediate === 'function') setImmediate(open); else setTimeout(open, 0);
  }

  send(text) {
    const message = JSON.parse(text);
    this.sent.push(message);
    if (message.t !== MessageType.CAPTURE_ATTACH) return;
    const answer = this._answerAttach(message);
    if (!answer.error) this.attachedTo = message.captureId;
    this._deliver({ t: MessageType.RESP, id: message.id, ok: !answer.error, ...answer });
  }

  /** An ORDERLY close, carrying the NORMAL status code a real close handshake does - which is
   *  what the far end reads spec 4.7's "this was the end of a stream" off. */
  close() { this.closed++; if (this.onclose) this.onclose({ code: NORMAL_CLOSURE }); }

  /** The socket DROPS - pulled with no close frame, so the far end sees the ABNORMAL code and
   *  nothing else. Spec 4.1's death rule is what a client has to answer this with. */
  drop() { if (this.onclose) this.onclose({ code: ABNORMAL_CLOSURE }); }

  /** A control message pushed down this DATA connection - the one thing spec 4.7 forbids a
   *  bench outright, and therefore the only way to ask what a client does when its peer mixes
   *  the planes. */
  pushText(message) { this._deliver(message); }

  /** One spec-5 binary frame, on the connection that carries this capture. */
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

  _deliver(message) { if (this.onmessage) this.onmessage({ data: JSON.stringify(message) }); }
}

/**
 * The socketFactory a NetConnection is built with when its bench serves both planes: the FIRST
 * dial is the control connection (the bench double itself), every one after it is a fresh data
 * connection (spec 4).
 *
 * @param {Object} control the bench double that plays the control socket
 * @param {(attach: Object) => Object} answerAttach what that bench answers an attach with
 * @param {FakeDataSocket[]} [opened] filled with each data socket as it is dialled, so a test
 *        can feed frames into one or drop it
 */
export function twoPlaneFactory(control, answerAttach, opened = []) {
  let dialled = 0;
  return () => {
    if (dialled++ === 0) return control;
    const data = new FakeDataSocket(answerAttach);
    opened.push(data);
    return data;
  };
}
