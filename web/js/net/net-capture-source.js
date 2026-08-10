/*
 * Phonalyser web - a capture stream that lives on another machine.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of org.edgo.audio.measure.net.client.NetPcmCapture, on the SAME CaptureSource
 * seam web-audio-capture-source.js and qa40x-capture-source.js implement - so SharedCapture
 * neither knows nor cares that these batches crossed a network (the contract is the
 * CaptureSource typedef in js/audio/shared-capture.js).
 *
 * THE DEVICE IS TAKEN FOR THE STREAM'S LIFE, not for the session's: open() acquires the lock
 * of spec 4.3 and close() gives it back, because a device this client is not capturing from is
 * a device another client may have. The same discipline the local backends follow.
 *
 * TWO KINDS OF LOSS, TOLD APART. A GAP frame is the server's honest confession that it dropped
 * capture data (spec 5); it goes to the manager's fault hub so the analyzers re-sync instead
 * of splicing across it. A jump in the packet counter is something else entirely: intact TCP
 * cannot lose or reorder, so the stream is broken - it is surfaced and the stream stops,
 * exactly as a device error would.
 *
 * THREE WAYS IT CAN END WITHOUT BEING ASKED TO: the bench's input lane fails (spec 4.3's
 * ev.device.error), the session dies (spec 4.1), or the packet counter jumps. All three land
 * in _halt(): the frames stop being routed here and the server is told to stop sending them.
 * Every one of them finishes the lane through the capture-ended seam, so the pane
 * that claims the reason tells the operator - once.
 *
 * NO SECOND WATCHDOG, deliberately: the Java client does not arm AbstractPcmCapture's delivery
 * deadline on this class. A remote stream that goes quiet is already covered by the keepalive
 * of spec 4.1 (4 missed pings = 2 s), whose death lands here as a session end - a second timer
 * would only race it and give the same fault two names.
 */
import { MessageType, NetFields, NetDirection, FrameType, MARKER_SWEEP_START } from './net-proto.js';
import { NetCloseReason } from './net-close-reason.js';
import { CaptureEndReason } from '../audio/capture-end-reason.js';
import { refInto, refText } from './net-device-ref.js';
import { DeviceFailureReason } from '../audio/device-failure-reason.js';

/** captureId before the open and after the close. */
const NOT_OPEN = -1;
/** The pipeline is stereo downstream (spec 4.4 writes the channel count as the constant 2, and
 *  the server upmixes a mono device itself). */
const STEREO = 2;

export class NetCaptureSource {

  /**
   * @param {Object} deps
   * @param {import('./net-connection.js').NetConnection} deps.connection the live session
   * @param {Object} deps.device the remote device ref (spec 4.3's four fields)
   * @param {import('./net-device-manager.js').NetDeviceManager} deps.owner where a gap goes
   *        and where this stream's device lock is registered - the whole client has to agree
   *        on which devices it holds. Injected, never set later: a stream whose losses had
   *        nowhere to go would splice across them in silence.
   */
  constructor({ connection, device, owner }) {
    this._connection = connection;
    this._device = device;
    this._owner = owner;
    this._captureId = NOT_OPEN;
    /** The rate the server GRANTED (spec 4.4: "the granted rate may differ (device reality),
     *  client re-pins"); 0 until the open succeeded. */
    this._grantedRate = 0;
    this._grantedBits = 0;
    this._acquired = false;
    /** True from the first step of OUR teardown until it has fully resolved - the stream
     *  closed on the bench AND the device lock given back. isOpen reports it, so the
     *  measurement idle-wait blocks until this session genuinely holds nothing any more
     *  (the same contract WebAudioCaptureSource keeps with its own closing flag). */
    this._closing = false;
    this._onBatch = null;
    this._onCaptureEnded = null;
    /** Whether the stream has already ended by itself; a failure is acted on exactly once. */
    this._ended = false;
    /** Spec 4.4: the stream "pauses" - set before capture.stop goes out and cleared before
     *  capture.start does, so the batches the server's audio worker still has queued are
     *  counted but no longer delivered. */
    this._paused = true;
    /** The packet counter the NEXT frame must carry (spec 5: per stream, starts at 0, +1 per
     *  frame of ANY type). */
    this._nextPacket = 0;
    /** PCM payload bytes handed to the pipeline so far - the position a marker is reported at. */
    this._pcmBytesDelivered = 0;
    /** Who is assembling a bounded record out of this stream (the sweep), or null - and then a
     *  marker is counted and dropped, which is what every stream but a sweep-bounded one
     *  wants. ONE listener: the mark bounds a single measurement's record, and two assemblers
     *  on one stream would be two measurements sharing an ADC. */
    this._markerListener = null;
    this._events = (event) => this._onEvent(event);
    this._sessionEnd = (reason) => this._onSessionEnd(reason);
    this._frames = (frame) => this._onFrame(frame);
  }

  // ---------------------------------------------------------------------------
  // The CaptureSource seam
  // ---------------------------------------------------------------------------

  /** The ACTUAL captured rate - what SharedCapture re-pins the analysis to. 0 while closed. */
  get sampleRate() { return this._grantedRate; }

  /** True while this session still holds something on the bench for this device - the stream
   *  OR, during a teardown, its device lock. The lock matters as much as the stream: capture.stop,
   *  capture.close and device.release are round-trips, and reporting "free" the moment the local
   *  handle was dropped let the next measurement's device.acquire + capture.open interleave with
   *  the previous consumer's release still in flight. The bench then processed the release BETWEEN
   *  them and answered NOT_LOCKED - the taking-over measurement refused on a device this very
   *  client had just given back. */
  get isOpen() { return this._captureId !== NOT_OPEN || this._closing; }

  /**
   * Whether this source is bound to a session that is no longer the one to use - the socket it
   * was built on has closed, or the client has since opened another.
   *
   * It exists because the source is CACHED: the backend builds one per selected backend and
   * keeps it across starts, so a session that died between two measurements left the next start
   * talking to a closed socket - "the connection to ws://... is closed", classified UNKNOWN
   * because a plain Error carries no reason, and nothing but a page reload ever cleared it: once
   * the connection to the server was lost, every later start failed the same way.
   * A stale source is rebuilt instead, which is what lets a reconnect be used without one.
   */
  isStale() {
    const live = this._owner == null ? null : this._owner.connection;
    return this._connection == null || !this._connection.isOpen || this._connection !== live;
  }

  /** WHY an open failed, machine-readably. The reading was not done here: a remote device is
   *  classified by the backend that owns it, ON THE BENCH, and the answer travelled as the
   *  reason name - this only hands it on (Java NetDeviceManager.classifyFailure). */
  classifyFailure(err) { return this._owner.classifyFailure(err); }

  /** WHAT the bench said about it, when the refusal carried more than a reason (spec 4.2's code,
   *  message and holder) - handed on unread, exactly like the reason above. */
  refusalText(err) { return this._owner.refusalText(err); }

  /** Who is assembling a sweep-bounded record out of this stream (spec 5's MARKER). */
  setMarkerListener(listener) { this._markerListener = listener; }

  /**
   * Takes the device (spec 4.4: "requires input lock") and opens the stream.
   *
   * @param {string} _deviceId ignored - the device is the ref this source was built with; the
   *        seam passes the local id, which means nothing on the far end
   * @param {number} requestedRateHz
   * @throws the session's own refusal, so a device somebody else is using reads as such and
   *         not as a mystery. The lock is given back before the throw: an open that failed
   *         must cost nothing.
   */
  async open(_deviceId, requestedRateHz) {
    const bits = this._bitsFor(requestedRateHz);
    this._closing = false;   // a fresh open owns the flag, whatever a previous teardown left
    const taken = await this._owner.acquireDevice(this._device);
    if (taken != null) throw taken;
    this._acquired = true;
    let opened;
    try {
      const request = refInto(this._device, this._connection.newRequest(MessageType.CAPTURE_OPEN));
      request[NetFields.RATE] = requestedRateHz;
      request[NetFields.BITS] = bits;
      opened = await this._connection.request(request);
    } catch (e) {
      await this._release();
      throw e;
    }
    if (opened.ok === false) {
      await this._release();
      throw this._connection.refusal(`cannot open a capture on ${refText(this._device)}`, opened);
    }
    const data = opened.data || {};
    const grantedBits = Number(data[NetFields.BITS]) || bits;
    const grantedChannels = Number(data[NetFields.CHANNELS]) || STEREO;
    const grantedRate = Number(data[NetFields.RATE]) || requestedRateHz;
    const handle = data[NetFields.CAPTURE_ID] == null ? NOT_OPEN : Number(data[NetFields.CAPTURE_ID]);
    if (grantedChannels !== STEREO || handle === NOT_OPEN || !SUPPORTED_BITS.has(grantedBits)) {
      // The sample width decides how the payload is read, so a width this decoder does not
      // know would not be re-pinned but MIS-READ - every sample silently wrong. The rate is
      // another matter: it changes nothing about the bytes, and sampleRate reports the granted
      // one.
      await this._closeRemote(handle);
      await this._release();
      throw new Error(`the server granted ${grantedChannels} channel(s) at ${grantedBits} bit `
        + `on ${refText(this._device)}, which this stream cannot decode - it asked for `
        + `${STEREO} at ${bits} bit`);
    }
    this._grantedRate = grantedRate;
    this._grantedBits = grantedBits;
    this._nextPacket = 0;
    this._pcmBytesDelivered = 0;
    this._ended = false;
    this._captureId = handle;
    this._connection.addStreamListener(handle, this._frames);
    // The two ways this stream can end without the socket saying anything: the bench's input
    // lane failing (spec 4.3) and the session dying (spec 4.1). Both look exactly like a quiet
    // bench until they are heard.
    this._connection.addEventListener(this._events);
    this._connection.addCloseListener(this._sessionEnd);
    console.info(`net capture ${handle}: open on ${refText(this._device)} at ${grantedRate} Hz / ${grantedBits} bit`);
  }

  /**
   * Spec 4.4: the binary frames begin. The pause is lifted BEFORE the request goes out,
   * because a frame is not ordered against the response either - the server answers on its
   * request worker and streams from another thread - and the first batch of a measurement is
   * measurement data.
   *
   * @param {(batch: {l: Float32Array, r: Float32Array, n: number}) => void} onBatch
   * @param {?(reason: Object) => void} onCaptureEnded the stream's END on the same seam the
   *        data arrives on (the CaptureSource contract)
   */
  async start(onBatch, onCaptureEnded) {
    this._onBatch = onBatch;
    this._onCaptureEnded = onCaptureEnded || null;
    this._paused = false;
    await this._command(MessageType.CAPTURE_START);
  }

  /** Spec 4.4: "stream pauses; counters keep their values" - so the packet counter is
   *  deliberately NOT reset here. The pause is taken BEFORE the request goes out, so when this
   *  returns nothing is reaching the module any more: that is what joining a consume thread
   *  buys a local backend. */
  async stop() {
    this._paused = true;
    // A stop is the first step of every teardown, and the close that follows it is another two
    // round-trips: from here on this source counts as still holding the device (see isOpen).
    this._closing = true;
    await this._command(MessageType.CAPTURE_STOP);
  }

  /** Ends the stream and gives the device back, in that order and whatever went wrong on the
   *  way: close may not throw, and a lock left behind is a device no other client can ever
   *  take again. */
  async close() {
    this._paused = true;
    this._closing = true;   // held until the release below has been ANSWERED - see isOpen
    this._onBatch = null;
    this._connection.removeEventListener(this._events);
    this._connection.removeCloseListener(this._sessionEnd);
    const open = this._captureId;
    this._captureId = NOT_OPEN;
    this._grantedRate = 0;
    try {
      if (open !== NOT_OPEN) {
        this._connection.removeStreamListener(open);
        await this._closeRemote(open);
      }
      await this._release();
    } finally { this._closing = false; }
  }

  // ---------------------------------------------------------------------------
  // The frames of spec 5
  // ---------------------------------------------------------------------------

  /**
   * One binary frame for this stream. The counter is checked first and for EVERY type: spec 5
   * increments it per frame of any type, so a GAP or a marker that went missing would
   * otherwise pass unnoticed and the client would splice across real loss.
   */
  _onFrame(frame) {
    if (frame.packetCounter !== this._nextPacket) {
      this._fail(`the packet counter jumped from ${this._nextPacket} to ${frame.packetCounter}`
        + ' - intact TCP neither loses nor reorders, so this stream is broken');
      return;
    }
    this._nextPacket++;
    switch (frame.typeCode) {
      case FrameType.PCM: this._deliver(frame.payload); break;
      case FrameType.GAP: this._gap(frame.n); break;
      case FrameType.MARKER: this._mark(frame.n); break;
      default:
        // Spec 1: an unknown binary frame type is skipped whole.
        console.debug(`net capture ${this._captureId}: frame type ${frame.typeCode} skipped`);
        break;
    }
  }

  /**
   * Hands one PCM batch to the pipeline - unless the stream is paused.
   *
   * Spec 4.4's capture.stop is answered by the server's REQUEST worker while its audio worker
   * is still draining the batches it has queued, so frames really do arrive after stop()
   * returned. They are COUNTED - the counter of spec 5 runs over every frame - and then
   * dropped, because a batch reaching a module after its stop returned is a divergence.
   * Gating on a "recording" flag instead would have the mirror bug at the START: a frame is
   * not ordered against the capture.start response either.
   */
  _deliver(payload) {
    if (this._paused || this._onBatch == null) {
      console.debug(`net capture ${this._captureId}: a batch queued before the stop was dropped`);
      return;
    }
    const batch = decodeInterleaved(payload, this._grantedBits);
    if (batch == null) return;
    this._onBatch(batch);
    // AFTER the hand-over, so the count is what the pipeline has really been given: a marker
    // reported at this boundary says "everything up to here was before the mark".
    this._pcmBytesDelivered += payload.byteLength;
  }

  /** Spec 5's MARKER: an in-band position mark, passed on to whoever is assembling a
   *  sweep-bounded record. Nobody listening is the ordinary case - a scope or an FFT has no
   *  use for it - and then counting the frame is all this stream owes it. */
  _mark(markerKind) {
    const listener = this._markerListener;
    if (listener == null) return;
    console.debug(`net capture ${this._captureId}: marker ${markerKind} after ${this._pcmBytesDelivered} PCM byte(s)`);
    try {
      listener.marker(markerKind, this._pcmBytesDelivered);
    } catch (e) {
      console.error(`net capture ${this._captureId}: marker listener failed (continuing)`, e);
    }
  }

  /**
   * Spec 5: the server dropped `lostFrames` stereo frames and says so, "an explicit confession
   * - the client resets averaging instead of silently splicing".
   *
   * It goes BOTH ways, and the two are different questions. The manager is the whole client's
   * fault hub: it raises the re-sync confession the running analyzers already know. A record
   * being assembled out of this same stream cannot carry on - a sweep with a hole in it
   * deconvolves into a result that looks like a measurement - so the assembler is told too, at
   * the byte position the hole falls on, and fails its own measurement honestly.
   */
  _gap(lostFrames) {
    console.warn(`net capture ${this._captureId}: the server lost ${lostFrames} stereo frame(s)`);
    const assembler = this._markerListener;
    if (assembler != null && typeof assembler.gap === 'function') {
      try {
        assembler.gap(lostFrames, this._pcmBytesDelivered);
      } catch (e) {
        console.error(`net capture ${this._captureId}: record assembler failed on a gap (continuing)`, e);
      }
    }
    try {
      this._owner.gap(lostFrames);
    } catch (e) {
      console.error(`net capture ${this._captureId}: gap listener failed (continuing)`, e);
    }
  }

  /** Spec 4.3's ev.device.error: the bench's INPUT lane failed, so this stream is over
   *  whatever the socket still says. The end travels the seam the data travelled - the ring
   *  finishes, every reader answers terminally, and the pane that claims the reason tells the
   *  operator. Never sideways. */
  _onEvent(event) {
    if (event.t !== MessageType.EV_DEVICE_ERROR
      || event[NetFields.DIRECTION] !== NetDirection.INPUT) return;
    if (!this._halt()) return;
    // The bench's own reading of the fault rides along (spec 4.3's reason); the ring's
    // terminal state stays the operator's report, so here it is the log that gains the WHY.
    console.warn(`net capture ${this._captureId}: the bench's input lane failed `
      + `(${event[NetFields.REASON] || DeviceFailureReason.UNKNOWN.name}) - ${event[NetFields.DETAIL]}`);
    this._endCapture(CaptureEndReason.DEVICE_LOST);
  }

  /** Spec 4.1: the session is dead, so "stop all modules, show the connection error". The
   *  stream stops for EVERY end - an operator's own BYE as much as a keepalive death: the
   *  bench is gone either way, and a pane left feeding on it draws a measurement of nothing.
   *  The loss of the SESSION stays the session's own to report, once. */
  _onSessionEnd(reason) {
    if (!this._halt()) return;
    console.warn(`net capture ${this._captureId}: the session ended - ${reason.detail}`);
    this._endCapture(CaptureEndReason.DEVICE_LOST);
  }

  /** The stream ended by itself - the end travels the data seam so whoever is measuring is
   *  told why without anything going sideways. */
  _fail(detail) {
    if (!this._halt()) return;
    console.error(`net capture ${this._captureId}: ${detail}`);
    this._endCapture(CaptureEndReason.DEVICE_LOST);
  }

  /**
   * The stream stops being a stream: no more frames are routed here, and the server is told to
   * stop sending them. Answers false when it had already ended, so every ending is acted on
   * exactly once.
   *
   * The stop is FIRE-AND-FORGET on purpose - this runs while a frame or a close is being
   * delivered, and waiting for an answer here would wait on the very turn that is delivering
   * it. The handle is deliberately kept, so the caller's close() still closes the remote
   * stream and releases the lock.
   */
  _halt() {
    if (this._ended) return false;
    this._ended = true;
    this._paused = true;
    const open = this._captureId;
    if (open !== NOT_OPEN) {
      this._connection.removeStreamListener(open);
      const stop = this._connection.newRequest(MessageType.CAPTURE_STOP);
      stop[NetFields.CAPTURE_ID] = open;
      this._connection.send(stop).catch(() => { /* a dead session has nobody left to tell */ });
    }
    return true;
  }

  _endCapture(reason) {
    const ended = this._onCaptureEnded;
    if (ended == null) return;
    try { ended(reason); } catch (e) { console.error('net capture: captureEnded consumer failed', e); }
  }

  // ---------------------------------------------------------------------------
  // Talking to the server
  // ---------------------------------------------------------------------------

  /** capture.start and capture.stop - the same message shape with nothing in it but the
   *  handle. A command on a stream that already ended is not an error: the halt already told
   *  the server to stop, and the caller's own teardown is still owed its close. */
  async _command(type) {
    const open = this._captureId;
    if (open === NOT_OPEN) {
      throw new Error(`${type} on a capture that is not open - ${refText(this._device)}`);
    }
    if (this._ended) return;
    const request = this._connection.newRequest(type);
    request[NetFields.CAPTURE_ID] = open;
    const answer = await this._connection.request(request);
    if (answer.ok === false) {
      throw this._connection.refusal(`${type} failed on ${refText(this._device)}`, answer);
    }
  }

  /** Closes the remote stream, best effort: this is a teardown step, and every step of a
   *  teardown runs whether or not the one before it succeeded. */
  async _closeRemote(handle) {
    if (handle === NOT_OPEN) return;
    try {
      const request = this._connection.newRequest(MessageType.CAPTURE_CLOSE);
      request[NetFields.CAPTURE_ID] = handle;
      await this._connection.request(request);
    } catch (e) {
      console.warn(`net capture ${handle}: close failed: ${e.message}`);
    }
  }

  /** Gives the device lock back (spec 4.3: the release also closes anything still open on it),
   *  exactly once and through the manager that registered it, so what the client believes it
   *  holds stays true. */
  async _release() {
    if (!this._acquired) return;
    this._acquired = false;
    await this._owner.releaseDevice(this._device);
  }

  /** The sample width to ask for: the widest the device offers at this rate, which is what a
   *  measurement wants - the local backends ask their own way and the server re-validates. */
  _bitsFor(rateHz) {
    let best = 0;
    for (const format of this._owner.listSupportedFormats(this._device)) {
      if (format.sampleRate === rateHz && SUPPORTED_BITS.has(format.bits) && format.bits > best) {
        best = format.bits;
      }
    }
    return best > 0 ? best : DEFAULT_BITS;
  }
}

/** Sample widths this decoder reads (spec 4.4: signed little-endian interleaved stereo). */
const SUPPORTED_BITS = new Set([8, 16, 24, 32]);
/** What to ask for when the catalogue offers nothing at the wanted rate - the width every
 *  backend in this project treats as the measurement default. */
const DEFAULT_BITS = 24;

/**
 * Decodes one native PCM payload - signed little-endian interleaved stereo at `bits` (spec
 * 4.4: "the client decodes with the same code path a local device uses") - into the two
 * Float32 lanes the CaptureSource seam hands on. `r` is ch1, the calibrated/attenuated channel
 * every capture path in this app measures.
 *
 * Float32Array for raw capture, Float64 only for the DSP that follows: this is the same
 * normalisation the local sources do, one sample at a time, on the socket's turn.
 *
 * @param {DataView} payload @param {number} bits
 * @returns {?{l: Float32Array, r: Float32Array, n: number}} null for an empty or partial frame
 */
export function decodeInterleaved(payload, bits) {
  const bytesPerSample = bits / 8;
  const frameBytes = bytesPerSample * STEREO;
  const frames = Math.floor(payload.byteLength / frameBytes);
  if (frames <= 0) return null;
  const l = new Float32Array(frames);
  const r = new Float32Array(frames);
  // The full-scale divisor of a signed sample of this width: 2^(bits-1).
  const scale = 1 / Math.pow(2, bits - 1);
  // ONE decision per BATCH, never per sample. This used to call a shared readSigned() with a
  // switch on the width for every sample - 384 000 calls a second at 192 kHz stereo, six
  // DataView.getUint8 among them per 24-bit frame - and the socket's turn of the event loop is
  // where that cost lands. It is the turn the server's keepalive ping has to be answered on,
  // and a client that cannot drain its socket is exactly what the server reports as "the client
  // is not keeping up" before it declares the connection dead.
  //
  // 16 and 32 bit are read through a typed-array VIEW over the same bytes, which the engine
  // reads without a call per sample. The view is legal because spec 5's payload starts at the
  // 16-byte header boundary - 16 is a multiple of both widths - and it is only taken on a
  // LITTLE-ENDIAN host, since a typed array reads in the platform's byte order while the wire
  // is little-endian by spec. Anywhere else the DataView path below stands, correct and slower.
  const at0 = payload.byteOffset;
  const buffer = payload.buffer;
  if (LITTLE_ENDIAN && bits === 16 && at0 % 2 === 0) {
    const src = new Int16Array(buffer, at0, frames * STEREO);
    for (let i = 0, s = 0; i < frames; i++, s += STEREO) {
      l[i] = src[s] * scale;
      r[i] = src[s + 1] * scale;
    }
    return { l, r, n: frames };
  }
  if (LITTLE_ENDIAN && bits === 32 && at0 % 4 === 0) {
    const src = new Int32Array(buffer, at0, frames * STEREO);
    for (let i = 0, s = 0; i < frames; i++, s += STEREO) {
      l[i] = src[s] * scale;
      r[i] = src[s + 1] * scale;
    }
    return { l, r, n: frames };
  }
  if (bits === 24) {
    // No typed array exists for 24 bit - the one width a browser cannot read directly, and the
    // one a measurement ADC most often speaks. A Uint8Array view still beats DataView: plain
    // indexed loads instead of a method call per byte.
    const src = new Uint8Array(buffer, at0, frames * frameBytes);
    for (let i = 0, b = 0; i < frames; i++, b += frameBytes) {
      const left = src[b] | (src[b + 1] << 8) | (src[b + 2] << 16);
      const right = src[b + 3] | (src[b + 4] << 8) | (src[b + 5] << 16);
      // Sign-extend a 24-bit two's-complement value by shifting it up to 32 and back down,
      // which the engine compiles to two machine instructions.
      l[i] = ((left << 8) >> 8) * scale;
      r[i] = ((right << 8) >> 8) * scale;
    }
    return { l, r, n: frames };
  }
  if (bits === 8) {
    const src = new Int8Array(buffer, at0, frames * STEREO);
    for (let i = 0, s = 0; i < frames; i++, s += STEREO) {
      l[i] = src[s] * scale;
      r[i] = src[s + 1] * scale;
    }
    return { l, r, n: frames };
  }
  // A big-endian host, or an offset the wide views cannot sit on: correct, one call per sample.
  for (let i = 0; i < frames; i++) {
    const at = i * frameBytes;
    l[i] = readSigned(payload, at, bytesPerSample) * scale;
    r[i] = readSigned(payload, at + bytesPerSample, bytesPerSample) * scale;
  }
  return { l, r, n: frames };
}

/** Whether this host stores a multi-byte integer the way the wire does (spec 5 is little-endian
 *  throughout). Probed once: every browser this app runs on answers true, and the check is what
 *  makes the typed-array fast paths above a decision rather than an assumption. */
const LITTLE_ENDIAN = (() => {
  const probe = new Uint16Array([1]);
  return new Uint8Array(probe.buffer)[0] === 1;
})();

/** One signed little-endian sample of `bytes` width - the portable fallback. */
function readSigned(view, at, bytes) {
  switch (bytes) {
    case 1: return view.getInt8(at);
    case 2: return view.getInt16(at, true);
    case 4: return view.getInt32(at, true);
    default: {
      const raw = view.getUint8(at) | (view.getUint8(at + 1) << 8) | (view.getUint8(at + 2) << 16);
      return raw >= 0x800000 ? raw - 0x1000000 : raw;
    }
  }
}
