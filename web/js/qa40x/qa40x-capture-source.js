/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.sound.qa40x.Qa40xRecorder - the QA402/QA403
// stereo capture client. It attaches / detaches the capture lane on the manager's one
// duplex engine (doc §10): the engine starts the stream on the first attach and stops it
// on the last detach, so this class holds no device state of its own beyond the engine
// the manager handed it.
//
// It is the QA40x implementation of the capture-source contract SharedCapture requires
// (the CaptureSource typedef in js/audio/shared-capture.js) - the seat Java fills with
// AudioBackend.openCapture(device, rate, depth). Java takes (manager, sampleRate) at
// construction because its SharedCapture builds a fresh AudioCapture per acquire; the web
// source is long-lived (one instance held by SharedCapture), so the device + rate arrive at
// open() instead, and nothing is retained across a close. A rate change between sessions is
// therefore picked up by the next open() -> acquireEngine(), which restarts the one reg-9
// clock. The DeviceRef Java passes is ignored there too: the analyzer is a single
// always-duplex device.
//
// CHANNEL MAPPING (verified in Qa40xDuplexEngine §5/§9 item 6). The ADC bytes pass through
// the engine UNSWAPPED with the right input NOT inverted - those are QA401-only quirks - so
// the wire is interleaved little-endian int32 (slot 0, slot 1) and slot 0 -> l, slot 1 -> r.
// `r` is ch1, the calibrated/attenuated channel every capture path in this app measures
// (.claude/memory/project_adc_channel.md); swapping it silently measures the wrong input.
//
// TWO INTENDED DIVERGENCES FROM THE JAVA, both because the web ring is not a byte stride:
//
//   1. NO 24-BIT REPACK. Java's onAudio repacks every int32 wire sample into 3 bytes
//      (dropping the zero pad byte) because its SharedCapture strides RAW BYTES and scales
//      by the advertised bit depth, so advertised MUST equal delivered or every sample is
//      read at the wrong offset. The web's SharedCapture takes normalised float L/R, so the
//      repack has nothing to serve: we convert int32 / MAXINT directly. That is
//      arithmetically the same number Java arrives at - the pad byte is zero, so the int32
//      IS the 24-bit value << 8 - and the advertised-vs-delivered hazard the Java comment
//      warns about cannot exist here, because there is no byte stride to get wrong.
//   2. NO SPSC RING + CONSUME THREAD. Java hands the batch to a consume thread so the libusb
//      event thread - which also paces every transfer - never waits on a consumer. The web
//      has no such thread: WebUsbQa40xTransport already awaits its transfer queue on the task
//      queue and SharedCapture.appendBatch is a memcpy. The SHAPE is kept (batch in -> staged
//      into the ring -> consumers read their own cursors) and so is the drop counter with its
//      rate-limited warning, because the one loss path the web still has - a consumer that
//      throws, which Java's AbstractPcmCapture.dispatch also has to survive - must stay
//      visible instead of silently costing audio.

import { CHANNELS, FRAME_BYTES } from './qa40x-duplex-engine.js';
import { MAXINT } from './qa40x-levels.js';

/** Wire container width per sample (int32) - Java's WIRE_SAMPLE_BYTES, derived from the
 *  engine's frame so the frame layout stays defined in exactly one place. */
const WIRE_SAMPLE_BYTES = FRAME_BYTES / CHANNELS;
/** Little-endian flag for the DataView accessors - the ADC's own byte order (§5). */
const LITTLE_ENDIAN = true;
/** Rate limit for the dropped-batch warning - Java's LOG_INTERVAL_NANOS (1 s). */
const DROP_WARN_INTERVAL_MS = 1000;

/**
 * What this source needs of its device manager (implemented by Qa40xDeviceManager): the one
 * duplex engine, built on first use and re-clocked when the shared rate moved.
 *
 * @typedef {Object} Qa40xEngineSource
 * @property {(sampleRateHz: number) => (Promise<Object>|Object)} acquireEngine the one
 *           Qa40xDuplexEngine for this session's rate
 */

export class Qa40xCaptureSource {

  /** @type {Qa40xEngineSource} */
  #manager;
  /** The manager's one duplex engine - null until open(). */
  #engine = null;
  #sampleRateHz = 0;
  /** @type {?(batch: {l: Float32Array, r: Float32Array, n: number}) => void} */
  #onBatch = null;
  /** Java's `recording` AtomicBoolean: gates the engine's capture-lane callback, so a read
   *  completing between stop() and the detach taking effect is discarded, not dispatched. Also
   *  what close() consults to know whether it must stop first. In the web the nulled #onBatch
   *  already blocks that late read on its own - this is the Java line, kept because losing it
   *  would leave the discard resting on a single unguarded field. */
  #recording = false;
  /** Grow-only staging buffers, mirroring Java SharedCapture's reusable convBuf: one pair
   *  for the whole session instead of ~100 fresh 16 KB pairs per second. Float32 is the raw
   *  capture width the ring widens on append (the DSP downstream is Float64). */
  #left = new Float32Array(0);
  #right = new Float32Array(0);
  /** Batches lost since start() (Java's droppedBatchesSinceLog, cumulative here so the count
   *  survives being warned about) and the value the last warning reported. */
  #droppedBatches = 0;
  #warnedDrops = 0;
  /** -Infinity so the FIRST loss always warns, as Java's lastLogNanos = 0 does against
   *  System.nanoTime() - performance.now() may still be below the interval at that point. */
  #lastWarnMs = -Infinity;

  /**
   * @param {Qa40xEngineSource} manager the device manager that owns the duplex engine
   */
  constructor(manager) {
    if (manager == null) {
      throw new Error('manager');
    }
    this.#manager = manager;
  }

  /** The captured rate - exactly the requested one: the analyzer's rates are the discrete
   *  set reg 9 encodes, so there is no device-imposed rate to re-pin to. 0 while closed. */
  get sampleRate() { return this.#sampleRateHz; }

  /** True while the engine is held (the analyzer session may still be idle - the engine
   *  streams only while a lane is attached). */
  get isOpen() { return this.#engine != null; }

  /** Batches lost to a faulting consumer since start(). Java only logs its counter; the
   *  getter is how the web makes an overrun assertable. */
  get droppedBatches() { return this.#droppedBatches; }

  /**
   * Acquires the manager's duplex engine for this rate (opening the device, reading
   * calibration and refreshing the card on first use), matching Java's
   * {@code engine = manager.acquireEngine(sampleRate)}.
   *
   * @param {string} deviceId ignored - the analyzer is one always-duplex device, exactly as
   *        Java's openCapture ignores the DeviceRef it is handed
   * @param {number} sampleRateHz the session rate; a change since the last open restarts the
   *        engine's one shared reg-9 clock
   */
  async open(deviceId, sampleRateHz) {
    this.#engine = await this.#manager.acquireEngine(sampleRateHz);
    this.#sampleRateHz = sampleRateHz;
  }

  /**
   * Attaches the capture lane - which starts the duplex stream if it was idle.
   *
   * @param {(batch: {l: Float32Array, r: Float32Array, n: number}) => void} onBatch the ring
   *        sink; `r` is ch1. The two arrays are RECYCLED staging buffers, so a consumer must
   *        read them synchronously and must not retain them (Java's dispatch contract).
   */
  async start(onBatch) {
    if (this.#engine == null) {
      throw new Error('Call open() before start()');
    }
    this.#onBatch = onBatch;
    this.#droppedBatches = 0;
    this.#warnedDrops = 0;
    this.#lastWarnMs = -Infinity;
    this.#recording = true;
    await this.#engine.attachCapture((buffer, length) => this.#onAudio(buffer, length));
  }

  /** Detaches the capture lane - which stops the stream only if this was the last client. */
  async stop() {
    this.#recording = false;
    this.#onBatch = null;
    if (this.#engine != null) {
      await this.#engine.detachCapture();
    }
  }

  /** Releases the engine reference (the manager owns the engine itself, so Java does not
   *  null it either - but a closed source must report closed, and the next open() has to go
   *  back through acquireEngine, which is where a rate change is applied). */
  async close() {
    if (this.#recording) {
      await this.stop();
    }
    this.#engine = null;
    // open() acquired the engine, which OPENS the analyzer; if start() never ran (a rejecting
    // attach, or a throw between the two in SharedCapture.acquire) there is no lane to detach, so
    // the engine's last-detach release cannot fire and the device would stay claimed for the life
    // of the page. A no-op while a session is live.
    await this.#manager.releaseIfIdle();
    this.#left = new Float32Array(0);      // drop the staging pair with the session
    this.#right = new Float32Array(0);
  }

  /**
   * Engine capture-lane sink: decode `length` bytes of interleaved little-endian int32
   * stereo into the staging pair and hand them to the ring. `length` is the transfer's
   * TRANSFERRED count, not the pooled buffer's size - decoding the whole buffer would
   * re-inject the previous transfer's tail.
   */
  #onAudio(buffer, length) {
    if (!this.#recording || this.#onBatch == null) {
      return;                              // a read completing after stop - discarded, not dropped
    }
    const frames = Math.floor(length / FRAME_BYTES);
    if (frames <= 0) {
      return;
    }
    if (this.#left.length < frames) {
      this.#left = new Float32Array(frames);
      this.#right = new Float32Array(frames);
    }
    const l = this.#left;
    const r = this.#right;
    const view = new DataView(buffer.buffer, buffer.byteOffset, buffer.byteLength);
    for (let f = 0, o = 0; f < frames; f++, o += FRAME_BYTES) {
      // Slot 0 -> l (ch0), slot 1 -> r (ch1, the calibrated/attenuated channel): NOT swapped
      // and the right NOT inverted (§9 item 6). / MAXINT is the same normalisation
      // Qa40xLevels.adcVolts applies before the range's full-scale voltage.
      l[f] = view.getInt32(o, LITTLE_ENDIAN) / MAXINT;
      r[f] = view.getInt32(o + WIRE_SAMPLE_BYTES, LITTLE_ENDIAN) / MAXINT;
    }
    try {
      this.#onBatch({ l, r, n: frames });
    } catch (e) {
      // A consumer that throws must not kill the capture pump (Java
      // AbstractPcmCapture.dispatch catches for the same reason) - but the batch IS lost, so
      // it is counted and warned about rather than swallowed.
      this.#countDroppedBatch(e);
    }
  }

  /** Counts one lost batch and emits the rate-limited warning - Java's consumeLoop drop log,
   *  reporting only the losses since the last warning. */
  #countDroppedBatch(cause) {
    this.#droppedBatches++;
    const now = performance.now();
    if (now - this.#lastWarnMs < DROP_WARN_INTERVAL_MS) {
      return;
    }
    const dropped = this.#droppedBatches - this.#warnedDrops;
    this.#warnedDrops = this.#droppedBatches;
    this.#lastWarnMs = now;
    console.warn(`QA40x capture: ${dropped} batch(es) dropped - the consumer faulted: ${cause}`);
  }
}
