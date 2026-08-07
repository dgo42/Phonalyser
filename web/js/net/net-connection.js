/*
 * Phonalyser web - one session with a headless Phonalyser server.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of org.edgo.audio.measure.net.client.NetConnection: the `hello` of spec 1, the
 * request/response correlation of spec 4.0, the keepalive of spec 4.1 and the binary-frame
 * demux of spec 5.
 *
 * WHAT IT OWNS. The socket, the id space, the requests still waiting for their `resp`, the
 * keepalive counters and the routing table from streamId to the capture that asked for it.
 * What the messages MEAN is not here: the device catalogue belongs to net-device-manager.js
 * and a stream's own vocabulary to net-capture-source.js, so this type never has to grow a
 * case for a message a later phase adds.
 *
 * KEEPALIVE. The client pings every 500 ms and counts the unanswered ones; four in a row (2 s
 * of silence) and the connection is dead - spec 4.1's rule, run against the SERVER by the same
 * arithmetic the server runs against the client. It also answers the server's pings, which
 * arrive as requests with NEGATIVE ids (spec 4.0), with the ordinary `resp` envelope. Time
 * arrives through an injected Ticker, never from a clock read or a sleep, so the whole death
 * sequence is a handful of calls in a test.
 *
 * THREADING (the web's version of Java's reader-thread contract). Everything that arrives is
 * decoded and delivered on the socket's own turn of the event loop - the same place a local
 * capture's batch callback lands, which is why a remote PCM batch reaches the pipeline exactly
 * as a local one does. A listener must therefore consume without awaiting a request of its own:
 * send() queues and returns, and is the way out of a listener.
 */
import { MessageType, ErrorCode, NetFields, NetProto, parseBinaryFrame } from './net-proto.js';
import { NetCloseReason } from './net-close-reason.js';
import { deviceFailureReasonFromName, DeviceFailureReason } from '../audio/device-failure-reason.js';

/** How long a request may go unanswered before the caller is told the server is not answering.
 *  Generous on purpose: an exclusive-mode device open on the far end really can take seconds,
 *  and the keepalive - not this - is what notices a dead peer. */
const REQUEST_TIMEOUT_MS = 10_000;
/** How long a UI-driven ask may hold the operator: ~1 s still reads as the app working, 10 s
 *  reads as a hang - the dialog-path asks (backend/device/card lists, select, preview) bound
 *  HERE, while device operations keep REQUEST_TIMEOUT_MS. */
export const UI_REQUEST_TIMEOUT_MS = 1_000;

/** How long one drain slice may hold the event loop before it yields (see onBinary). Eight
 *  milliseconds is under half a 60 Hz frame, so the ui stays live and a keepalive that arrives
 *  mid-backlog waits at most this long - a fortieth of spec 4.1's 2 s death budget. */
const FRAME_DRAIN_BUDGET_MS = 8;

/** A backlog this deep means the client genuinely cannot keep up with the bench's rate; it is
 *  said once per slice rather than silently absorbed, because it is the client-side half of the
 *  server's own "the client is not keeping up". */
const FRAME_BACKLOG_WARN = 200;

/**
 * Runs a callback on the NEXT turn of the event loop - the yield between two drain slices.
 *
 * Deliberately NOT setTimeout(0): browsers clamp a timer nested more than five deep to 4 ms,
 * which would cap the drain at a few hundred frames a second and make a client that is behind
 * fall further behind. Node's setImmediate and a MessageChannel port message are both ordinary
 * tasks with no such clamp, and both let the socket deliver what has arrived in between -
 * which is what gets the keepalive answered. setImmediate is preferred where it exists so the
 * node test process is never held open by a live port.
 */
function makeTaskPoster() {
  if (typeof setImmediate === 'function') return (fn) => setImmediate(fn);
  if (typeof MessageChannel === 'function') {
    const channel = new MessageChannel();
    const queue = [];
    channel.port1.onmessage = () => {
      const fn = queue.shift();
      if (fn) fn();
    };
    return (fn) => { queue.push(fn); channel.port2.postMessage(0); };
  }
  return (fn) => setTimeout(fn, 0);
}

/**
 * A refusal that also carries WHY, when the far end said why.
 *
 * It is an Error exactly as this client's refusals have always been - every catch upstream is
 * untouched - with the far end's DeviceFailureReason beside the text. That is what lets the
 * device manager answer classifyFailure for a REMOTE device without reading the server's
 * English back: the classification was already done, on the bench, by the backend that owns
 * the driver, and this only carries it the last hop to the operator's language.
 */
export class NetRefusal extends Error {
  /** @param {string} message @param {{name: string, i18nKey: string}} reason */
  constructor(message, reason) {
    super(message);
    this.name = 'NetRefusal';
    /** The far end's reading of its own failure; UNKNOWN when it sent none - an older bench,
     *  or a fault that was never a device's. */
    this.reason = reason || DeviceFailureReason.UNKNOWN;
  }
}

export class NetConnection {

  /**
   * @param {Object} deps
   * @param {string} deps.url the `ws://host:port/` to dial (spec 4: the upgrade lives at `/`)
   * @param {string} deps.clientName the user-visible client name of spec 4.1 - what a
   *        DEVICE_LOCKED error names as the holder on somebody else's screen
   * @param {string} deps.clientApp the application-and-version string of spec 4.1's `client`
   *        field. Injected: the version belongs to the application, not to a backend
   * @param {{start: Function, stop: Function}} deps.ticker the 500 ms keepalive clock
   * @param {(url: string) => Object} [deps.socketFactory] builds the socket; injected so a
   *        test drives a fake one (the seam Java's MockBench uses). Default: a real WebSocket
   */
  constructor({ url, clientName, clientApp, ticker, socketFactory, postTask }) {
    this._url = url;
    this._clientName = clientName;
    this._clientApp = clientApp;
    this._ticker = ticker;
    this._socketFactory = socketFactory || ((u) => new WebSocket(u));
    /** The yield between two frame-drain slices (see onBinary) - injected so a test drives the
     *  slices itself and can look at the queue between them. */
    this._postTask = postTask || makeTaskPoster();
    /** Frames parsed off the socket and not yet handed to their stream, with the head they are
     *  read from: the backlog that must not be processed inside the socket's own turn. */
    this._frameQueue = [];
    this._frameHead = 0;
    this._drainScheduled = false;
    /** Stable reference, so a slice can be scheduled without allocating a closure per frame. */
    this._drainSlice = () => { this._drainScheduled = false; this._drainFrames(); };
    this._socket = null;
    /** Requests waiting for their `resp`, keyed by the id of spec 4.0. */
    this._pending = new Map();
    /** streamId -> frame listener (the demux of spec 5). */
    this._streams = new Map();
    this._eventListeners = [];
    this._closeListeners = [];
    /** Spec 4.0: "a per-connection monotonically increasing integer". */
    this._lastRequestId = 0;
    /** Sequence number of the NEWEST client ping the server answered. Spec 4.1 counts
     *  CONSECUTIVE unanswered pings, which is _pingCounter minus this - so an answer that
     *  arrived after four later pings went unanswered cannot resurrect a connection that is
     *  already 2 s behind. */
    this._lastAnsweredPing = 0;
    this._pingCounter = 0;
    /** id -> ping sequence number, for the pings still unanswered (see onText). */
    this._pingSequences = new Map();
    this._closed = false;
    /** The negotiated session version (spec 1); 0 until `hello` succeeded. */
    this.proto = 0;
    /** The server's installation UUID (spec 2.1) - what a remembered-server list keys on,
     *  never the address. */
    this.serverId = null;
    /** The operator-configured server name, for the window title and the combo. */
    this.serverName = null;
    /** The capability tokens of spec 4.1. A token is a promise, its absence is not a refusal:
     *  this build must never HARD-require one, because a server that serves an extension
     *  without advertising it yet is a server we still talk to. */
    this.caps = [];
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  /**
   * Connects and completes the handshake of spec 1: the client offers the range
   * [protoMin..proto], the server answers with the CHOSEN version that governs the session,
   * and the keepalive starts.
   *
   * Nothing is sent before the answer arrives - `hello` MUST be first (spec 4.1) - and a
   * refusal closes the connection here rather than leaving a socket open that no request may
   * use. `timeoutMs` bounds the WHOLE handshake, socket and `hello` together: this runs from a
   * button, and a host that completes the WebSocket handshake and then says nothing must not
   * hold the dialog for the request timeout.
   *
   * @param {number} timeoutMs
   * @throws {Error} when the server cannot be reached within `timeoutMs` or refuses the
   *         session; `.code` carries the error code of spec 4.2 where the server gave one,
   *         because "cannot connect" and "your protocol is too old" are different problems
   */
  async open(timeoutMs) {
    const deadline = Date.now() + timeoutMs;
    await this._connectSocket(timeoutMs);
    const hello = await this._request({
      t: MessageType.HELLO,
      id: ++this._lastRequestId,
      [NetFields.PROTO]: NetProto.PROTO_VERSION,
      [NetFields.PROTO_MIN]: NetProto.PROTO_MIN_VERSION,
      [NetFields.CLIENT]: this._clientApp,
      [NetFields.NAME]: this._clientName,
    }, Math.max(1, deadline - Date.now()));
    if (hello.ok === false) {
      const error = hello.error || {};
      this.close(NetCloseReason.HANDSHAKE_REFUSED);
      throw withCode(new Error(`the server at ${this._url} refused the session: `
        + (error.code ? `${error.code} - ${error.message}` : 'no reason given')), error.code);
    }
    const data = hello.data || {};
    // Spec 1: the chosen version governs the WHOLE session and binds both sides, so one
    // outside the range this client offered is not a session to run at a version it cannot
    // speak - it is a mismatch. An absent field reads as 0 and is caught by the same test,
    // which is the point of testing the value rather than its presence.
    const chosen = Number(data[NetFields.PROTO]) || 0;
    if (chosen < NetProto.PROTO_MIN_VERSION || chosen > NetProto.PROTO_VERSION) {
      this.close(NetCloseReason.HANDSHAKE_REFUSED);
      throw withCode(new Error(`${ErrorCode.PROTO_MISMATCH}: the server at ${this._url} chose `
        + `proto ${chosen}, and this client speaks `
        + `${NetProto.PROTO_MIN_VERSION}..${NetProto.PROTO_VERSION}`), ErrorCode.PROTO_MISMATCH);
    }
    this.proto = chosen;
    this.serverId = textOf(data, NetFields.SERVER_ID);
    this.serverName = textOf(data, NetFields.NAME);
    this.caps = Array.isArray(data[NetFields.CAPS]) ? data[NetFields.CAPS].map(String) : [];
    this._ticker.start(NetProto.PING_INTERVAL_MS, () => this._keepaliveTick());
    console.info(`net client: connected to '${this.serverName}' (${this.serverId}) at `
      + `${this._url}, proto ${this.proto}, caps [${this.caps}]`);
  }

  /** Opens the socket, or rejects when it does not come up inside the budget. The browser's
   *  own failure (a refused TCP connect, a mixed-content block) arrives as `error` with no
   *  detail by design - the caller turns that into the spec §3 explanation. */
  _connectSocket(timeoutMs) {
    return new Promise((resolve, reject) => {
      let socket;
      try {
        socket = this._socketFactory(this._url);
      } catch (e) {
        reject(new Error(`cannot dial ${this._url}: ${e.message}`));
        return;
      }
      this._socket = socket;
      socket.binaryType = 'arraybuffer';
      let settled = false;
      const timer = setTimeout(() => {
        if (settled) return;
        settled = true;
        try { socket.close(); } catch (_) { /* already gone */ }
        reject(new Error(`no Phonalyser server answered at ${this._url}`));
      }, timeoutMs);
      if (timer && typeof timer.unref === 'function') timer.unref();
      socket.onopen = () => {
        if (settled) return;
        settled = true; clearTimeout(timer);
        resolve();
      };
      socket.onmessage = (ev) => {
        if (typeof ev.data === 'string') this.onText(ev.data);
        else this.onBinary(ev.data);
      };
      socket.onclose = () => {
        if (!settled) { settled = true; clearTimeout(timer); reject(new Error(`no Phonalyser server answered at ${this._url}`)); return; }
        this.close(NetCloseReason.TRANSPORT_CLOSED);
      };
      socket.onerror = () => {
        if (!settled) { settled = true; clearTimeout(timer); reject(new Error(`cannot reach ${this._url}`)); return; }
        console.warn(`net client: transport error on ${this._url}`);
        this.close(NetCloseReason.TRANSPORT_ERROR);
      };
    });
  }

  /** True while the session is usable: the handshake succeeded, nothing has closed it. */
  get isOpen() { return !this._closed && this._socket != null; }

  /**
   * The bench's HTTP base - where spec §3's REST endpoints live, {@code PUT /files} above all
   * (Java NetConnection.httpBase).
   *
   * ONE port serves both: the server's front end multiplexes the WebSocket session and the REST
   * endpoints onto a single listener, so the session's own host and port ARE the upload address
   * and no second setting can drift out of step with it. The SCHEME is the only thing that
   * changes - and here it is derived from the ws url the session was dialled on, so a wss://
   * session uploads over https:// rather than dropping to plaintext.
   */
  httpBase() {
    return this._url.replace(/^ws(s?):/i, 'http$1:').replace(/\/+$/, '');
  }

  /**
   * Ends the session and tells everyone waiting. Idempotent and callable from anywhere - the
   * keepalive declaring the server dead, the socket's own close callback and the operator
   * pressing Disconnect all land here, in any order.
   *
   * BYE sends the `bye` of spec 4.1 first, so the server releases this connection's locks at
   * once instead of waiting for its own keepalive to notice. It is not waited for.
   *
   * Every request still waiting fails HERE rather than timing out ten seconds later, because a
   * caller blocked on a device open must learn that the bench is gone while its measurement
   * can still be stopped.
   *
   * @param {{name: string, detail: string, messageKey: string}} reason
   */
  close(reason) {
    if (this._closed) return;
    this._closed = true;
    this._ticker.stop();
    if (reason === NetCloseReason.BYE) {
      this._sendRaw({ t: MessageType.BYE, id: ++this._lastRequestId });
    }
    try { if (this._socket) this._socket.close(); } catch (_) { /* already gone */ }
    const ended = new Error(`the connection to ${this._url} ended: ${reason.detail}`);
    for (const [id, entry] of [...this._pending]) {
      this._pending.delete(id);
      entry.reject(ended);
    }
    this._streams.clear();
    this._pingSequences.clear();
    // A backlog belongs to a session: the streams it was addressed to are gone, and holding the
    // received buffers past the close would keep them alive for nothing.
    this._frameQueue.length = 0;
    this._frameHead = 0;
    for (const listener of [...this._closeListeners]) {
      try { listener(reason); } catch (e) { console.error('net client: close listener failed (continuing)', e); }
    }
    console.info(`net client: session with ${this._url} ended - ${reason.detail}`);
  }

  // ---------------------------------------------------------------------------
  // Sending
  // ---------------------------------------------------------------------------

  /** An empty request of `type` carrying the next id (spec 4.0); the fields are the caller's
   *  to add. */
  newRequest(type) { return { t: type, id: ++this._lastRequestId }; }

  /**
   * Sends `message` and WAITS for the server's answer - a `resp`, successful or not: a refusal
   * is an answer, not a failure, and the caller reads its code from `.error`.
   *
   * @param {Object} message @returns {Promise<Object>}
   */
  request(message) { return this._request(message, REQUEST_TIMEOUT_MS); }

  /** The UI's ask: {@link #request} bounded at UI_REQUEST_TIMEOUT_MS, so a dialog never waits
   *  out the device-operation budget on a server that is not answering. */
  uiRequest(message) { return this._request(message, UI_REQUEST_TIMEOUT_MS); }

  _request(message, timeoutMs) {
    const answer = this.send(message);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this._pending.delete(message.id);   // nobody is waiting for it any more
        reject(new Error(`the server did not answer ${message.t} within ${timeoutMs} ms`));
      }, timeoutMs);
      if (timer && typeof timer.unref === 'function') timer.unref();
      answer.then((m) => { clearTimeout(timer); resolve(m); },
        (e) => { clearTimeout(timer); reject(e); });
    });
  }

  /**
   * Sends `message` and answers with the promise its `resp` will settle. It never waits, which
   * is what makes it the only way to send from a listener (see the module comment).
   *
   * @param {Object} message @returns {Promise<Object>}
   */
  send(message) {
    if (message.id == null) {
      throw new Error(`spec 4.0: a request needs an id to be answered - ${message.t}`);
    }
    return new Promise((resolve, reject) => {
      // Registered BEFORE the write, never after: a close that runs in between must find this
      // entry and fail it, or the caller waits for an answer the ended session cannot bring.
      this._pending.set(message.id, { resolve, reject });
      if (this._closed) {
        this._failPending(message.id, `the connection to ${this._url} is closed`);
        return;
      }
      try {
        this._socket.send(JSON.stringify(message));
      } catch (e) {
        this._failPending(message.id, `cannot send ${message.t}: ${e.message}`);
      }
    });
  }

  /**
   * A refused `resp` turned into the exception the caller catches, with the code and the
   * holder of spec 4.2 in the message - "in use by Developer's laptop" is actionable, "cannot
   * open device" is not. It lives here rather than in each caller because every one of them
   * refuses the same way; two spellings of the same refusal read as two different faults.
   *
   * @param {string} what @param {Object} answer the refused resp
   * @returns {NetRefusal}
   */
  refusal(what, answer) {
    const error = answer && answer.error;
    if (!error) return new NetRefusal(what, DeviceFailureReason.UNKNOWN);
    const by = error[NetFields.BY];
    return new NetRefusal(`${what}: ${error.code} - ${error.message}`
      + (by == null ? '' : ` (held by ${by})`),
      deviceFailureReasonFromName(error[NetFields.REASON]));
  }

  // ---------------------------------------------------------------------------
  // Listeners
  // ---------------------------------------------------------------------------

  /** Routes the binary frames of `streamId` (spec 5) to `listener` until it is removed. A
   *  second registration on the same id replaces the first - the server hands an id out once
   *  at a time. */
  addStreamListener(streamId, listener) { this._streams.set(streamId, listener); }

  /** Stops routing `streamId`; frames for it are dropped from here on, which is what a closed
   *  stream's late frames deserve. */
  removeStreamListener(streamId) { this._streams.delete(streamId); }

  /** Subscribes to the server-initiated events of spec 4.3 and 4.5 (`ev.*`) - they carry no id
   *  and are never answered. */
  addEventListener(listener) { this._eventListeners.push(listener); }

  removeEventListener(listener) {
    const i = this._eventListeners.indexOf(listener);
    if (i >= 0) this._eventListeners.splice(i, 1);
  }

  /** Subscribes to the end of the session, whichever end it turns out to be. Fired once. */
  addCloseListener(listener) { this._closeListeners.push(listener); }

  /** Unsubscribes: a capture that closed in the ordinary way has nothing left to be told, and
   *  a subscription it could not take back would outlive it for the rest of the session. */
  removeCloseListener(listener) {
    const i = this._closeListeners.indexOf(listener);
    if (i >= 0) this._closeListeners.splice(i, 1);
  }

  // ---------------------------------------------------------------------------
  // Receiving
  // ---------------------------------------------------------------------------

  /** One control message off the socket. Public for the same two reasons Java's is
   *  package-private: the transport hands it in, and a test delivers the messages a real
   *  server cannot be made to produce on demand. */
  onText(text) {
    let message;
    try {
      message = JSON.parse(text);
    } catch (e) {
      console.warn(`net client: undecodable message from ${this._url} dropped: ${e.message}`);
      return;
    }
    if (message == null || typeof message !== 'object') return;
    const type = message.t;
    const id = message.id;
    if (type === MessageType.RESP) {
      // The keepalive is marked answered HERE, as the answer arrives - not in a callback on
      // the request's promise. Java's thenRun runs synchronously on the completing thread; a
      // JS promise callback is a microtask, so a burst of ticks inside one turn of the event
      // loop would see four "unanswered" pings that had in fact all been answered.
      const sequence = this._pingSequences.get(id);
      if (sequence != null) {
        this._pingSequences.delete(id);
        this._lastAnsweredPing = Math.max(this._lastAnsweredPing, sequence);
      }
      const entry = id == null ? null : this._pending.get(id);
      if (entry) { this._pending.delete(id); entry.resolve(message); }
      return;
    }
    if (type === MessageType.PING) {
      // Spec 4.0: the server's own requests carry NEGATIVE ids so they cannot collide with
      // ours, and are answered with the same resp envelope a client request gets.
      if (id != null) this._sendRaw({ t: MessageType.RESP, id, ok: true, data: {} });
      return;
    }
    if (id != null) {
      // Spec 1: "Unknown message types answer error UNSUPPORTED", and the rule is written for
      // BOTH sides. Spec 4.0 says an id makes this a REQUEST, so a v2 server asking something
      // this build never heard of would otherwise wait for an answer that never comes.
      this._sendRaw({ t: MessageType.RESP, id, ok: false,
        error: { code: ErrorCode.UNSUPPORTED, message: `this client does not answer '${type}'` } });
      return;
    }
    for (const listener of [...this._eventListeners]) {
      try { listener(message); } catch (e) { console.error(`net client: ${type} listener failed (continuing)`, e); }
    }
  }

  /**
   * One binary audio message off the socket (spec 5), routed to the stream it names. A frame
   * for a stream nobody is listening to is dropped: a close races the frames already in
   * flight, and that race is ordinary.
   *
   * A MALFORMED frame is not dropped: spec 5 counts every frame, so the next one would trip
   * the receiving stream's counter check and be reported as transport loss - a malformed frame
   * told as the wrong fault, on a stream that would meanwhile have gone on splicing. A peer
   * whose framing is broken is not one to keep measuring with, so the session ends here and
   * every stream on it stops with one honest reason.
   *
   * @param {ArrayBuffer} message
   */
  onBinary(message) {
    let frame;
    try {
      frame = parseBinaryFrame(message);
    } catch (e) {
      console.error(`net client: malformed binary frame from ${this._url}: ${e.message}`);
      this.close(NetCloseReason.PROTOCOL_ERROR);
      return;
    }
    // QUEUED, NOT DELIVERED HERE. Handing the frame straight to its stream ran the whole
    // decode-and-analyse pipeline inside the socket's own turn of the event loop, so at
    // 192 kHz stereo the browser spent every turn in the consumer and the frames - AND the
    // server's keepalive ping, which arrives as a text message in the very same queue - piled
    // up behind it. The server then reports what it sees from its side: "the client is not
    // keeping up", and a little later "4 pings unanswered - connection dead".
    // This is the web's shape of Java's reader-thread-plus-queue: the socket handler
    // returns at once, and {@link #_drainFrames} does the work in slices with a yield between
    // them, so a ping is delivered and answered BETWEEN two slices instead of after the whole
    // backlog. Frame ORDER is untouched, which is what the per-stream packet counter checks.
    this._frameQueue.push(frame);
    this._scheduleDrain();
  }

  /** Asks for a drain slice on the next turn, once - the flag is what keeps a burst of arriving
   *  frames from queueing one task each. */
  _scheduleDrain() {
    if (this._drainScheduled || this._closed) return;
    this._drainScheduled = true;
    this._postTask(this._drainSlice);
  }

  /**
   * One slice of the frame backlog: frames are handed to their streams until the budget is
   * spent, then the rest is left for the next turn. Yielding is the whole point - between two
   * slices the browser delivers whatever else has arrived, which is how the keepalive gets
   * answered while a backlog is still draining.
   *
   * The queue is read with a moving head rather than shift(), which is O(n) per frame on a long
   * backlog - the one place where being behind would make being behind worse.
   */
  _drainFrames() {
    const until = Date.now() + FRAME_DRAIN_BUDGET_MS;
    while (this._frameHead < this._frameQueue.length) {
      const frame = this._frameQueue[this._frameHead];
      this._frameQueue[this._frameHead++] = null;   // drop the reference with the slot
      this._deliverFrame(frame);
      if (Date.now() >= until) break;
    }
    if (this._frameHead >= this._frameQueue.length) {
      this._frameQueue.length = 0;
      this._frameHead = 0;
      return;
    }
    if (this._frameQueue.length - this._frameHead >= FRAME_BACKLOG_WARN) {
      console.warn(`net client: ${this._frameQueue.length - this._frameHead} audio frames are `
        + 'waiting to be processed - this client is not keeping up with the bench');
    }
    this._scheduleDrain();
  }

  /** One queued frame to the stream that asked for it. A frame for a stream nobody is listening
   *  to is dropped: a close races the frames already in flight, and that race is ordinary. */
  _deliverFrame(frame) {
    const listener = this._streams.get(frame.streamId);
    if (!listener) return;
    try {
      listener(frame);
    } catch (e) {
      // A consumer that fails must not kill the delivery path, which would freeze every
      // stream on this connection.
      console.error(`net client: stream ${frame.streamId} consumer failed (continuing)`, e);
    }
  }

  /**
   * One keepalive period. The count is checked BEFORE the next ping goes out, so four
   * unanswered pings - 2 s of silence, spec 4.1 - end the session half a period later, and the
   * modules are told while their measurement can still be stopped.
   */
  _keepaliveTick() {
    if (this._closed) return;
    try {
      const unanswered = this._pingCounter - this._lastAnsweredPing;
      if (unanswered >= NetProto.MAX_MISSED_PINGS) {
        console.warn(`net client: ${unanswered} pings to ${this._url} unanswered - the server is gone`);
        this.close(NetCloseReason.KEEPALIVE_TIMEOUT);
        return;
      }
      const sequence = ++this._pingCounter;
      const ping = this.newRequest(MessageType.PING);
      this._pingSequences.set(ping.id, sequence);
      // The promise is deliberately not awaited: its rejection at close is not an error here,
      // and the ANSWER is accounted for in onText the moment it lands.
      this.send(ping).catch(() => { /* the close that failed it already ended the session */ });
    } catch (e) {
      console.warn(`net client: keepalive to ${this._url} failed: ${e.message}`);
      this.close(NetCloseReason.TRANSPORT_ERROR);
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** A message that correlates with nothing - a ping answer, a farewell. Failure is logged and
   *  swallowed: both are sent on a socket that may already be going away, and neither has
   *  anybody left to tell. */
  _sendRaw(message) {
    try {
      this._socket.send(JSON.stringify(message));
    } catch (e) {
      console.debug(`net client: ${message.t} not sent to ${this._url}: ${e.message}`);
    }
  }

  _failPending(id, reason) {
    const entry = this._pending.get(id);
    if (entry) { this._pending.delete(id); entry.reject(new Error(reason)); }
  }
}

/** The spec 4.2 code, kept on the Error so a caller can branch on the CODE instead of parsing
 *  a sentence (Java puts it in the message; JS has a field for it). */
function withCode(error, code) {
  if (code) error.code = code;
  return error;
}

function textOf(owner, field) {
  const v = owner[field];
  return typeof v === 'string' ? v : null;
}
