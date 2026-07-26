/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.sound.qa40x.Qa40xDuplexEngine.
//
// The QA402/QA403 session — ONE always-duplex stream per open transport (see
// doc/QA40X-PROTOCOL.md §10 "Session model"). Input and output are a single
// hardware streaming session: on the QA403 reads only complete once the output
// priming crosses the 1024-frame start threshold, so the two directions can
// never be started independently. Rather than restart the running direction when
// the other side joins, this engine keeps one primed duplex stream and lets
// clients attach / detach lanes.
//
// LIFECYCLE
//   - The FIRST attach (attachGenerator or attachCapture) starts the stream:
//     reg8=0 -> reg5 -> reg6 -> reg9 -> reg11 -> reg10 -> 100 ms settle ->
//     reg8=5, then primes the output past the 1024-frame threshold with steady
//     16 KB chunks (2048 stereo int32 frames) and keeps IN_FLIGHT_TRANSFERS
//     reads in flight. A completed read is the pacing clock — it submits the
//     next read and, only while fewer than IN_FLIGHT_TRANSFERS writes are
//     already outstanding, the next write (§5). The write side is bounded the
//     same way as the read side (double-buffered): a burst of read completions —
//     e.g. under scheduling jitter, dramatically worse when the tab is
//     backgrounded and the browser throttles the pump — can NEVER inflate the
//     output queue, so playback stays sample-locked to capture instead of racing
//     open-loop ahead.
//   - An unattached generator lane sends silence; an unattached capture lane's
//     reads are discarded. A LATER attach swaps the silence source for real
//     samples LIVE — no restart, no register write.
//   - The LAST detach stops: cancelAll strictly BEFORE reg8=0 (§7 step 7); the
//     transport never pipe-resets / clear-halts (§3).
//   - A range or sample-rate change while running does a full stop + start —
//     reg 9 is a shared rate register, so the app's input/output rates must be
//     constrained equal for this backend (§10).
//
// ALWAYS DUPLEX. The ADC does not stream unless the DAC is fed, so the engine
// keeps writing for as long as it is streaming — silence when no generator is
// attached — exactly as the Java does. A capture-only client is still a duplex
// session on the wire.
//
// WIRE FORMAT (§5). Samples are interleaved stereo int32 LITTLE-endian. The DAC
// output L/R are SWAPPED before sending (device out ch0 <- logical R). The ADC
// input is NOT swapped and the right input is NOT inverted — those are
// QA401-only quirks (§9 item 6).
//
// THREADING. Java guards its stream state with two monitors: ioLock serialises
// the blocking start / stop / re-range register sequences, and stateLock guards
// the mutable counters that the libusb event thread touches from its completion
// callbacks. JS has ONE thread, so stateLock has no analogue at all — every
// Java `synchronized (stateLock)` section is plain synchronous code here, and a
// completion callback can never interleave with it. ioLock DOES have an
// analogue, because the register calls are promises rather than blocking bulk
// transfers: a register sequence yields, so two attach calls could otherwise
// interleave their traffic. #serialize() chains each sequence behind the last,
// and — exactly like the Java, which re-reads `streaming` only after acquiring
// ioLock — the start / stop DECISION is taken inside that chained section, not
// before it. The attach / detach / change methods therefore return promises
// where Java returns void; that is the only deviation.

import { TransferListener } from './qa40x-transport.js';
import {
  I2S_BITS_32,
  I2S_START,
  I2S_STOP,
  I2S_WIDTH_OFF,
  REG_I2S,
  REG_I2S_WIDTH,
  REG_INPUT_FS,
  REG_OUTPUT_FS,
  REG_RUN,
  REG_SAMPLE_RATE,
  RUN_START,
  RUN_STOP,
  SAFE_INPUT_DBV,
  SAFE_OUTPUT_DBV,
  i2sWidthCode,
  inputRangeCode,
  outputRangeCode,
  sampleRateCode,
} from './qa40x-protocol.js';

const CHANNELS = 2;
const BYTES_PER_SAMPLE = 4;
const FRAME_BYTES = CHANNELS * BYTES_PER_SAMPLE;
/** Steady-state frames per transfer — 2048 stereo frames = 16 KB (§5). */
const STEADY_FRAMES = 2048;
const STEADY_CHUNK_BYTES = STEADY_FRAMES * FRAME_BYTES;
/** Transfers kept in flight per direction — double-buffered (§5). */
const IN_FLIGHT_TRANSFERS = 2;
/** Output frames that must be queued before the QA403 starts streaming (§5). */
const START_THRESHOLD_FRAMES = 1024;
/**
 * Upper bound on banked write debt. In a matched-clock duplex the ADC and DAC
 * run at one rate, so the backlog oscillates near zero and never reaches this;
 * the ceiling only guards a genuinely stuck output from unbounded latency, and
 * it is generous (~0.34 s at 192 kHz) so ordinary read-completion bursts are
 * fully repaid rather than forfeited — a forfeited write is a silent DAC frame,
 * which the loopback captures as a discontinuity (bench 2026-07-17).
 */
const MAX_WRITE_DEBT = 32;
/** Rate-write settle delay in ms — the ABA hazard guard (§8). */
const SETTLE_MILLIS = 100;

/** Zero-fill source for an unattached generator lane. */
const SILENCE = (destination, frames) => destination.fill(0, 0, frames * CHANNELS);

/** Default last-detach hook: keep the claimed device, exactly as the Java does. */
const NO_SESSION_END = () => {};

/** Java's convenience-constructor default: the front-panel I2S port stays off. */
const I2S_DISABLED = () => false;
/** Java's convenience-constructor default frame width. */
const I2S_DEFAULT_BITS = () => I2S_BITS_32;

export { CHANNELS, FRAME_BYTES, STEADY_FRAMES, STEADY_CHUNK_BYTES, IN_FLIGHT_TRANSFERS,
  START_THRESHOLD_FRAMES, MAX_WRITE_DEBT, SETTLE_MILLIS };

/**
 * Source of DAC samples for the generator lane (Java's SampleSource functional
 * interface): fills `destination` with `frames` interleaved LOGICAL L,R int32
 * samples (destination[2i] = left, destination[2i+1] = right). The engine swaps
 * L/R and packs little-endian before sending (§5). `destination.length` is at
 * least 2 * frames.
 * @typedef {(destination: Int32Array, frames: number) => void} SampleSource
 */

/**
 * Sink for ADC samples on the capture lane (Java's CaptureConsumer functional
 * interface): receives one ADC read, `length` bytes of `buffer`, interleaved
 * little-endian int32 stereo (L,R) straight off the input endpoint — NOT
 * swapped, right NOT inverted (§9 item 6). Consume synchronously; the buffer is
 * recycled after return.
 * @typedef {(buffer: Uint8Array, length: number) => void} CaptureConsumer
 */

/**
 * Injected settle clock so the ABA rate-write delay (§8) is real in production
 * yet instant in tests (Java's Sleeper functional interface). Awaited, so it may
 * return a promise or nothing.
 * @typedef {(millis: number) => (Promise<void>|void)} Sleeper
 */

export class Qa40xDuplexEngine extends TransferListener {

  /** @type {Object} the injected Qa40xTransport — never constructed here. */
  #transport;
  /** @type {Sleeper} */
  #sleeper;
  /** The front-panel I2S setting, consulted at each session boundary. */
  #i2sEnabled;
  /** The session's frame width in bits, written to reg 0x0B when the port is on. */
  #i2sBits;
  /** Run once the last-detach park has completed — the owner's chance to release the
   *  device. @type {function(): (void|Promise<void>)} */
  #onSessionEnd;
  /** Java's `int[] scratch` — Int32Array so the DAC words wrap at 32 bits. */
  #scratch = new Int32Array(STEADY_FRAMES * CHANNELS);
  /** @type {Uint8Array[]} */
  #freeReadBuffers = [];
  /** @type {Uint8Array[]} */
  #freeWriteBuffers = [];

  /**
   * Java's ioLock: the tail of the serialized register-sequence chain. Start /
   * stop / re-range sequences run one after another, never interleaved. The
   * completion callbacks NEVER touch it, so a completion can never queue behind
   * device I/O.
   * @type {Promise<void>}
   */
  #ioChain = Promise.resolve();

  #inputRangeDbv;
  #outputRangeDbv;
  #sampleRateHz;
  /** @type {SampleSource} */
  #source = SILENCE;
  /** @type {CaptureConsumer|null} */
  #consumer = null;
  #generatorAttached = false;
  #captureAttached = false;
  #streaming = false;

  /**
   * True while ANY client is using the analyzer — a lane attached, or the stream still up. The
   * owner asks this before handing the device back: an engine OBJECT is not a session, because
   * acquireEngine() builds it before the first attach, so "the engine exists" would wrongly read
   * as "in use" and leave the analyzer claimed with nothing running.
   * @returns {boolean}
   */
  get inUse() {
    return this.#captureAttached || this.#generatorAttached || this.#streaming;
  }
  /**
   * Outstanding playback transfers — capped at IN_FLIGHT_TRANSFERS so a burst of
   * read completions cannot pump the output queue open-loop (§5).
   */
  #writesInFlight = 0;
  /**
   * Write debt: read completions whose paced write found both slots busy. A
   * 2048-frame write drains through the device's 1024-frame queue in a full read
   * period, so the skip-vs-submit race is routine — the debt is repaid the
   * moment writeCompleted frees a slot, keeping the long-run pacing exactly 1:1
   * (bench 2026-07-17: skipping without repayment lost ~1/3 of all writes — a
   * periodic underrun "meander"). Bounded by MAX_WRITE_DEBT — generous, so
   * read-completion bursts are repaid rather than forfeited: a forfeited write
   * is a silent DAC frame the loopback captures as a discontinuity.
   */
  #writesOwed = 0;

  /**
   * @param {Object} transport the Qa40xTransport this session runs on
   * @param {Sleeper} sleeper the injected settle clock
   * @param {number} inputRangeDbv
   * @param {number} outputRangeDbv
   * @param {number} sampleRateHz
   * @param {() => boolean} [i2sEnabled] the front-panel I2S setting, READ at each
   *        session boundary rather than copied — so a Preferences OK between
   *        sessions is picked up without any push, and a stale copy cannot leave
   *        the port running. Defaults, as Java's convenience constructor does, to
   *        "off": without an I2S source the front-panel generator simply stays
   *        off — the state every session then starts and ends in.
   * @param {() => number} [i2sBits] the frame width in bits
   */
  constructor(transport, sleeper, inputRangeDbv, outputRangeDbv, sampleRateHz,
              i2sEnabled = I2S_DISABLED, i2sBits = I2S_DEFAULT_BITS,
              onSessionEnd = NO_SESSION_END) {
    super();
    if (transport == null) {
      throw new Error('transport');
    }
    if (sleeper == null) {
      throw new Error('sleeper');
    }
    this.#transport = transport;
    this.#sleeper = sleeper;
    this.#i2sEnabled = i2sEnabled;
    this.#i2sBits = i2sBits;
    this.#onSessionEnd = onSessionEnd;
    // Fail fast on an invalid range / rate via the protocol code maps.
    inputRangeCode(inputRangeDbv);
    outputRangeCode(outputRangeDbv);
    sampleRateCode(sampleRateHz);
    this.#inputRangeDbv = inputRangeDbv;
    this.#outputRangeDbv = outputRangeDbv;
    this.#sampleRateHz = sampleRateHz;
    transport.setListener(this);
  }

  // --- lanes ---------------------------------------------------------------

  /**
   * Attaches / live-swaps the generator lane's sample source; starts the stream
   * if idle.
   * @param {SampleSource} generatorSource
   * @returns {Promise<void>} settles once any start sequence has completed
   */
  attachGenerator(generatorSource) {
    if (generatorSource == null) {
      throw new Error('generatorSource');
    }
    this.#source = generatorSource;
    this.#generatorAttached = true;
    return this.#serialize(async () => {
      if (!this.#streaming) {
        await this.#startStream();
      }
    });
  }

  /**
   * Detaches the generator lane (reverts to silence); stops the stream if it was
   * the last client.
   * @returns {Promise<void>}
   */
  detachGenerator() {
    this.#source = SILENCE;
    this.#generatorAttached = false;
    return this.#serialize(async () => {
      if (this.#streaming && !this.#captureAttached) {
        await this.#stopSession();
      }
    });
  }

  /**
   * Attaches the capture lane's consumer; starts the stream if idle. A duplex
   * engine has exactly ONE capture consumer — the scope and the FFT share it
   * through SharedCapture (doc §10). A second attach with a lane already live is
   * a wiring bug (two independent capture owners on one stream); it is refused
   * loudly rather than silently overwriting the first consumer, which would
   * starve one view and split the single capture lane.
   * @param {CaptureConsumer} captureConsumer
   * @returns {Promise<void>}
   */
  attachCapture(captureConsumer) {
    if (captureConsumer == null) {
      throw new Error('captureConsumer');
    }
    if (this.#captureAttached) {
      throw new Error('QA40x capture lane already attached — '
          + 'one duplex engine has a single capture consumer (scope and FFT share it '
          + 'through SharedCapture); refusing to split the stream (doc §10)');
    }
    this.#consumer = captureConsumer;
    this.#captureAttached = true;
    return this.#serialize(async () => {
      if (!this.#streaming) {
        await this.#startStream();
      }
    });
  }

  /**
   * Detaches the capture lane (reads discarded); stops the stream if it was the
   * last client.
   * @returns {Promise<void>}
   */
  detachCapture() {
    this.#consumer = null;
    this.#captureAttached = false;
    return this.#serialize(async () => {
      if (this.#streaming && !this.#generatorAttached) {
        await this.#stopSession();
      }
    });
  }

  // --- session settings ----------------------------------------------------

  /**
   * Changes the input full-scale range; a full stop + start if streaming.
   * @param {number} dbv
   * @returns {Promise<void>}
   */
  changeInputRange(dbv) {
    inputRangeCode(dbv);                    // fail fast before touching state
    this.#inputRangeDbv = dbv;
    return this.#serialize(() => this.#restartIfStreaming());
  }

  /**
   * Changes the output full-scale range; a full stop + start if streaming.
   * @param {number} dbv
   * @returns {Promise<void>}
   */
  changeOutputRange(dbv) {
    outputRangeCode(dbv);
    this.#outputRangeDbv = dbv;
    return this.#serialize(() => this.#restartIfStreaming());
  }

  /**
   * Changes the sample rate; a full stop + start if streaming (§8/§10).
   * @param {number} hz
   * @returns {Promise<void>}
   */
  changeSampleRate(hz) {
    sampleRateCode(hz);
    this.#sampleRateHz = hz;
    return this.#serialize(() => this.#restartIfStreaming());
  }

  // --- register sequences (Java: under ioLock) ------------------------------

  /**
   * Java's `synchronized (ioLock)`: runs `action` after every previously queued
   * sequence, so two callers can never interleave register traffic. A failed
   * sequence never wedges the chain — it is reported to ITS caller and the next
   * action still runs.
   *
   * The tail is chained on a SETTLED promise, never on `run` itself: Java's
   * monitor is released when the sequence throws and the very next
   * `synchronized (ioLock)` block runs normally, whereas a rejected tail would
   * make every later `.then(action)` skip its callback and re-reject the same
   * failure. One stalled register write (a device yanked mid-start) would then
   * brick the engine for the page's life — and the detach that still owes
   * #parkSafeRanges() would never run it, leaving the analyzer on the session's
   * sensitive input range (desktop commit 8d8ee8d).
   */
  #serialize(action) {
    const run = this.#ioChain.then(action);
    this.#ioChain = run.then(() => {}, () => {});
    return run;
  }

  async #restartIfStreaming() {
    if (this.#streaming) {
      await this.#stopStream();
      await this.#startStream();
    }
  }

  /**
   * The last-detach teardown: stop, then park the idle analyzer. Java inlines
   * these three calls at both detach sites.
   */
  async #stopSession() {
    await this.#stopStream();
    await this.#parkSafeRanges();
    await this.#stopI2s();
    // The analyzer is now idle and parked, so the owner may hand the hardware back. WEB-ONLY, and
    // the reason it exists: WebUSB claims interface 0 EXCLUSIVELY, so a claimed-but-idle analyzer
    // is unavailable to the vendor software (and to a second tab) even though nothing here is using
    // it — the desktop keeps its libusb claim to app exit and nobody notices. Never let a failure
    // here escape: a detach must complete regardless, or the lane clients hang on a stop.
    try {
      await this.#onSessionEnd();
    } catch (error) {
      console.warn('qa40x: session-end release failed:', error);
    }
  }

  async #startStream() {
    const inputCode = inputRangeCode(this.#inputRangeDbv);
    const outputCode = outputRangeCode(this.#outputRangeDbv);
    const rateCode = sampleRateCode(this.#sampleRateHz);
    await this.#transport.registerWrite(REG_RUN, RUN_STOP);              // recover / idle
    await this.#transport.registerWrite(REG_INPUT_FS, inputCode);
    await this.#transport.registerWrite(REG_OUTPUT_FS, outputCode);
    await this.#transport.registerWrite(REG_SAMPLE_RATE, rateCode);
    // Front-panel I2S generator (§4 reg 0x0A) — driven to the CURRENT setting
    // rather than only switched on, so the port state always matches the
    // preference. Written unconditionally, which also makes a restart (range or
    // rate change) idempotent instead of interrupting the tone. Both registers
    // still before the engine starts: the frame width (reg 0x0B, 16- or 32-bit
    // taken from the output bit depth) is set FIRST, so the port is already on
    // the right width at the moment the control register (reg 0x0A) starts it.
    // Both read 0 when off.
    const i2sOn = this.#i2sEnabled();
    await this.#transport.registerWrite(REG_I2S_WIDTH,
        i2sOn ? i2sWidthCode(this.#i2sBits()) : I2S_WIDTH_OFF);
    await this.#transport.registerWrite(REG_I2S, i2sOn ? I2S_START : I2S_STOP);
    await this.#sleeper(SETTLE_MILLIS);                                  // ABA settle (§8)
    await this.#transport.registerWrite(REG_RUN, RUN_START);             // start
    this.#streaming = true;
    this.#writesInFlight = 0;
    this.#writesOwed = 0;
    this.#primeStream();
  }

  /**
   * Clearing `streaming` first makes any completion that is still queued behind
   * the cancel bail out cheaply, exactly as it does in Java.
   */
  async #stopStream() {
    this.#streaming = false;
    await this.#transport.cancelAll();                                   // BEFORE reg8=0 (§7 step 7)
    await this.#transport.registerWrite(REG_RUN, RUN_STOP);
  }

  /**
   * Parks the idle analyzer at the protected ranges (doc §7 step 8) so a
   * sensitive range never sits live between measurements; the next #startStream
   * re-applies the session ranges. Deliberately NOT part of #stopStream: the
   * restart path (a range / rate change) would otherwise clack the attenuator
   * relay to +42 dBV and back on every change.
   */
  async #parkSafeRanges() {
    await this.#transport.registerWrite(REG_INPUT_FS, inputRangeCode(SAFE_INPUT_DBV));
    await this.#transport.registerWrite(REG_OUTPUT_FS, outputRangeCode(SAFE_OUTPUT_DBV));
  }

  /**
   * Stops the front-panel I2S generator when the SESSION ends, leaving the port
   * in the same state a fresh connect finds it (§6 safe init). Sits beside
   * #parkSafeRanges and NOT in #stopStream for the same reason: a restart
   * (range / rate change) would otherwise interrupt the I2S tone on every change.
   */
  async #stopI2s() {
    await this.#transport.registerWrite(REG_I2S, I2S_STOP);
    await this.#transport.registerWrite(REG_I2S_WIDTH, I2S_WIDTH_OFF);
  }

  // --- transfer pump -------------------------------------------------------

  /** Submits the priming transfers; non-blocking (async submits, not bulk transfers). */
  #primeStream() {
    for (let i = 0; i < IN_FLIGHT_TRANSFERS; i++) {
      this.#submitRead();
    }
    // Enough steady chunks to cross the start threshold, at least the in-flight
    // count — with 2048-frame chunks a single chunk already exceeds 1024 (§5).
    const primeWrites = Math.max(IN_FLIGHT_TRANSFERS,
        Math.ceil(START_THRESHOLD_FRAMES / STEADY_FRAMES));
    for (let i = 0; i < primeWrites; i++) {
      this.#submitWrite();
    }
  }

  #submitRead() {
    this.#transport.submitAudioRead(this.#borrowReadBuffer());
  }

  #submitWrite() {
    const chunk = this.#borrowWriteBuffer();
    this.#fillWriteChunk(chunk);
    this.#transport.submitAudioWrite(chunk, STEADY_CHUNK_BYTES);
    this.#writesInFlight++;
  }

  #fillWriteChunk(chunk) {
    this.#source(this.#scratch, STEADY_FRAMES);
    for (let f = 0; f < STEADY_FRAMES; f++) {
      const left = this.#scratch[CHANNELS * f];
      const right = this.#scratch[CHANNELS * f + 1];
      const offset = f * FRAME_BYTES;
      // DAC L/R swapped before sending (§5): device slot 0 = R, slot 1 = L.
      this.#putLittleEndianInt(chunk, offset, right);
      this.#putLittleEndianInt(chunk, offset + BYTES_PER_SAMPLE, left);
    }
  }

  // The three completion callbacks below are invoked by the transport's
  // completion pump. Java's run on the libusb event thread and take ONLY
  // stateLock; here they are plain synchronous code on the single JS thread and
  // never touch #ioChain, so a completion can never queue behind a register
  // write.

  /**
   * @param {Uint8Array} buffer
   * @param {number} transferred
   */
  readCompleted(buffer, transferred) {
    if (!this.#streaming) {                 // a late / cancelled completion after stop
      this.#returnReadBuffer(buffer);
      return;
    }
    this.#submitRead();                     // re-arm FIRST — the pipe never waits on the consumer (§5)
    const sink = this.#consumer;
    if (sink != null) {
      sink(buffer, transferred);            // ADC bytes pass through — not swapped, not inverted (§9 item 6)
    }
    this.#returnReadBuffer(buffer);
    this.#writesOwed = Math.min(this.#writesOwed + 1, MAX_WRITE_DEBT);
    this.#drainOwedWrites();                // read-clocked, bounded, AND debt-repaying (1:1 long-run)
  }

  /**
   * @param {Uint8Array} buffer
   * @param {number} transferred
   */
  writeCompleted(buffer, transferred) {
    if (this.#writesInFlight > 0) {
      this.#writesInFlight--;               // a drained buffer frees an in-flight slot for the next read-clocked write
    }
    this.#returnWriteBuffer(buffer);
    if (this.#streaming) {
      this.#drainOwedWrites();              // repay a write skipped while both slots were busy
    }
  }

  /**
   * Submits owed writes while an in-flight slot is free — writes stay
   * read-clocked (only read completions create debt) and bounded (never more
   * than IN_FLIGHT_TRANSFERS outstanding), but a write skipped at the cap is
   * repaid as soon as a slot frees instead of being lost.
   */
  #drainOwedWrites() {
    while (this.#writesOwed > 0 && this.#writesInFlight < IN_FLIGHT_TRANSFERS) {
      this.#writesOwed--;
      this.#submitWrite();
    }
  }

  /**
   * @param {boolean} read
   * @param {string} detail
   */
  transferFailed(read, detail) {
    // No auto-recovery (§5); a transfer cancelled during stop also lands here (benign).
    if (!read && this.#writesInFlight > 0) {
      this.#writesInFlight--;               // a cancelled / failed write frees its in-flight slot
    }
    console.warn(`QA40x ${read ? 'read' : 'write'} transfer failed: ${detail}`);
  }

  // --- buffer pool ---------------------------------------------------------

  #borrowReadBuffer() {
    const buffer = this.#freeReadBuffers.shift();
    return buffer !== undefined ? buffer : new Uint8Array(STEADY_CHUNK_BYTES);
  }

  #returnReadBuffer(buffer) {
    this.#freeReadBuffers.push(buffer);
  }

  #borrowWriteBuffer() {
    const buffer = this.#freeWriteBuffers.shift();
    return buffer !== undefined ? buffer : new Uint8Array(STEADY_CHUNK_BYTES);
  }

  #returnWriteBuffer(buffer) {
    this.#freeWriteBuffers.push(buffer);
  }

  #putLittleEndianInt(buffer, offset, value) {
    buffer[offset] = value;
    buffer[offset + 1] = value >> 8;
    buffer[offset + 2] = value >> 16;
    buffer[offset + 3] = value >> 24;
  }
}
