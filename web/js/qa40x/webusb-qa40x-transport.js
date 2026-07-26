/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.sound.qa40x.LibUsbQa40xTransport —
// the same Qa40xTransport seam, with WebUSB (navigator.usb) where the desktop
// has libusb/JNA.
//
// Constructed around an already opened AND claimed USBDevice: the finder does
// the open / claim, exactly as the Java finder does the open / reset / claim;
// this class only moves bytes.
//
// ENDPOINT NUMBERS. WebUSB addresses an endpoint by NUMBER and infers the
// direction from the call, so Java's EP 0x01/0x81 are both endpoint 1 and
// 0x02/0x82 are both endpoint 2 (doc/QA40X-WEBUSB.md).
//
// REGISTERS go through transferOut/transferIn on endpoint 1 (doc §4): a write is
// the 5-byte big-endian frame; a read writes 0x80|reg then decodes the 4-byte
// big-endian reply. The frame codec is the canonical, unit-tested Qa40xProtocol
// — this module only moves the bytes it produces. WebUSB has NO per-transfer
// timeout, so Java's REG_TIMEOUT_MS (1000 ms) is imposed here in JS: every
// register transfer races a deadline AND an abort hook, so a device that never
// answers rejects — naming the register — instead of parking the engine forever,
// and cancelAll() / close() trip the same hook so a stop is never blocked by a
// register transfer in flight.
//
// AUDIO goes through endpoint 2, asynchronously. Each submitAudioWrite /
// submitAudioRead starts a transfer and PUSHES ITS PROMISE ONTO A PER-DIRECTION
// QUEUE; a pump per direction consumes the queue by awaiting its HEAD and
// dispatching to the TransferListener. Awaiting the head rather than racing the
// outstanding promises is NOT optional: USB bulk completes FIFO, but concurrent
// transferIn promises carry no ordering guarantee, so racing them silently
// scrambles the sample stream (visible only as unexplained FFT noise). The pump
// replaces Java's libusb event thread; the engine still keeps >=2 transfers in
// flight per direction by submitting more than one, and a listener callback may
// re-submit re-entrantly (the engine re-arms its read inside readCompleted).
//
// STOP DISCIPLINE (doc §3/§7). WebUSB has neither a per-transfer cancel nor a
// per-transfer timeout: once the device stops sending, an outstanding transferIn
// NEVER settles. releaseInterface() is the only thing that aborts pending
// transfers, so it is what implements cancelAll() — followed by claimInterface()
// so the engine's subsequent reg 8 = RUN_STOP write still has a claimed
// interface. Queued promises get a .catch() attached BEFORE the release, or
// their rejections surface as unhandled. Neither cancelAll() nor close() ever
// resets or clear-halts a pipe — that hangs the next session's first read.
//
// A teardown is TOTAL and idempotent: releaseInterface() can itself reject (the
// device was unplugged, another context grabbed the interface), and that must
// never leave the transport dead. Swallow the queued rejections, trip the
// register aborts, release inside try/catch — then ALWAYS reset the transfer
// bookkeeping in a finally, whatever the device did, so the next session starts
// from a consistent state instead of a queue whose head can never settle.

import { Qa40xTransport } from './qa40x-transport.js';
import { REGISTER_REPLY_BYTES, decodeReply, readRequestFrame, writeFrame } from './qa40x-protocol.js';

/** Interface index — the only interface used on QA402/QA403 (doc §3). */
export const INTERFACE_0 = 0;

/** Register endpoint: Java's EP 0x01 OUT / 0x81 IN, one WebUSB endpoint number. */
export const REG_ENDPOINT = 1;
/** Audio endpoint: Java's EP 0x02 OUT / 0x82 IN, one WebUSB endpoint number. */
export const AUDIO_ENDPOINT = 2;

/**
 * Register-transfer deadline in ms — Java's LibUsbQa40xTransport.REG_TIMEOUT_MS,
 * the timeout every PyQa40x bulk call uses (doc §4/§5). libusb enforces it for
 * the desktop; WebUSB has no per-transfer timeout at all, so the same 1000 ms is
 * enforced here instead. Audio transfers keep Java's `timeout = 0` (streaming:
 * no per-transfer deadline).
 */
export const REG_TIMEOUT_MS = 1000;

/** WebUSB transfer result status meaning "no error" — 'stall' / 'babble' are the failures. */
const STATUS_OK = 'ok';

/** Renders a rejected transfer for TransferListener#transferFailed, mirroring LibUsb.transferStatusName. */
function describeFailure(error) {
  if (error == null) {
    return 'unknown';
  }
  return error.name ? `${error.name}: ${error.message}` : String(error);
}

export class WebUsbQa40xTransport extends Qa40xTransport {

  /** @type {USBDevice} already opened and with interface 0 claimed. */
  #device;
  /** @type {Object|null} Qa40xTransport#setListener */
  #listener = null;
  /** Set by close(); blocks further submissions the way Java's nulled handle does. */
  #closed = false;

  /** In-flight capture transfers, in submission order: {buffer, length, promise}. */
  #readQueue = [];
  /** In-flight playback transfers, in submission order: {buffer, length, promise}. */
  #writeQueue = [];
  /** True while the capture pump is draining #readQueue. */
  #readPumping = false;
  /** True while the playback pump is draining #writeQueue. */
  #writePumping = false;
  /** Reject hooks of the register transfers in flight — tripped by the deadline, cancelAll() and close(). */
  #registerAborts = new Set();

  /**
   * @param {USBDevice} device an already opened device with interface 0 claimed
   */
  constructor(device) {
    super();
    this.#device = device;
  }

  /** @returns {USBDevice} the device this transport was built around. */
  get device() {
    return this.#device;
  }

  // --- registers -----------------------------------------------------------

  /**
   * Writes `value` to register `reg` — the 5-byte big-endian frame on endpoint 1.
   * @param {number} reg
   * @param {number} value
   * @returns {Promise<void>}
   */
  async registerWrite(reg, value) {
    await this.#bulkOut(writeFrame(reg, value), `registerWrite(0x${reg.toString(16)})`);
  }

  /**
   * Reads register `reg`: writes 0x80|reg, then decodes the 4-byte big-endian
   * reply from endpoint 1 into a signed 32-bit word.
   * @param {number} reg
   * @returns {Promise<number>}
   */
  async registerRead(reg) {
    await this.#bulkOut(readRequestFrame(reg), `registerRead-request(0x${reg.toString(16)})`);
    const op = `registerRead-reply(0x${reg.toString(16)})`;
    const result = await this.#awaitRegister(this.#device.transferIn(REG_ENDPOINT, REGISTER_REPLY_BYTES), op);
    if (result.status !== STATUS_OK) {
      throw new Error(`${op} failed: ${result.status}`);
    }
    const view = result.data;
    const got = view ? view.byteLength : 0;
    if (got !== REGISTER_REPLY_BYTES) {
      throw new Error(`registerRead(0x${reg.toString(16)}): expected ${REGISTER_REPLY_BYTES} bytes, got ${got}`);
    }
    return decodeReply(new Uint8Array(view.buffer, view.byteOffset, view.byteLength));
  }

  async #bulkOut(frame, op) {
    const result = await this.#awaitRegister(this.#device.transferOut(REG_ENDPOINT, frame), op);
    if (result && result.status !== STATUS_OK) {
      throw new Error(`${op} failed: ${result.status}`);
    }
  }

  /**
   * Bounds ONE register transfer, which is what libusb's REG_TIMEOUT_MS does for
   * the desktop and WebUSB does for nobody: the transfer races a REG_TIMEOUT_MS
   * deadline and an abort hook that cancelAll() / close() trip, so an unanswered
   * register call rejects — naming the register through `op` — instead of parking
   * the engine's serialized register chain forever. WebUSB cannot cancel the
   * underlying transfer, so the loser of the race is simply abandoned; the race
   * has already attached handlers to it, so a late settlement is swallowed rather
   * than surfacing as an unhandled rejection.
   */
  async #awaitRegister(transfer, op) {
    let abort;
    let timer;
    const deadline = new Promise((_, reject) => {
      abort = (reason) => reject(new Error(`${op} ${reason}`));
      timer = setTimeout(() => abort(`timed out after ${REG_TIMEOUT_MS} ms`), REG_TIMEOUT_MS);
    });
    this.#registerAborts.add(abort);
    try {
      return await Promise.race([transfer, deadline]);
    } finally {
      clearTimeout(timer);
      this.#registerAborts.delete(abort);
    }
  }

  /**
   * Rejects every register transfer in flight so a stop is never blocked by one —
   * the WebUSB stand-in for libusb aborting a synchronous bulk transfer when the
   * handle goes away.
   */
  #abortPendingRegisters(op) {
    const aborts = [...this.#registerAborts];
    this.#registerAborts.clear();
    for (const abort of aborts) {
      abort(`aborted: ${op}`);
    }
  }

  // --- async audio ---------------------------------------------------------

  /**
   * Submits an async playback transfer of `length` bytes from `data` on
   * endpoint 2. `data` is handed back untouched to writeCompleted.
   * @param {Uint8Array} data
   * @param {number} length
   * @returns {void}
   */
  submitAudioWrite(data, length) {
    this.#ensureOpen('submitAudioWrite');
    const payload = data.subarray(0, length);
    this.#writeQueue.push({ buffer: data, length, promise: this.#device.transferOut(AUDIO_ENDPOINT, payload) });
    this.#startPump(false);
  }

  /**
   * Submits an async capture transfer filling up to `buffer.length` bytes from
   * endpoint 2. The received bytes are copied into `buffer`, which is handed to
   * readCompleted — mirroring Java's copy out of the native transfer buffer.
   * @param {Uint8Array} buffer
   * @returns {void}
   */
  submitAudioRead(buffer) {
    this.#ensureOpen('submitAudioRead');
    const length = buffer.length;
    this.#readQueue.push({ buffer, length, promise: this.#device.transferIn(AUDIO_ENDPOINT, length) });
    this.#startPump(true);
  }

  /**
   * Installs the listener that receives audio-transfer completions and failures.
   * @param {Object} listener
   * @returns {void}
   */
  setListener(listener) {
    this.#listener = listener;
  }

  // --- stop / teardown -----------------------------------------------------

  /**
   * Cancels in-flight transfers. WebUSB has no per-transfer cancel, so the abort
   * IS releaseInterface(); the interface is re-claimed immediately so the
   * engine's following reg 8 = RUN_STOP write still works (doc §7 step 7).
   * Never pipe-resets or clear-halts (doc §3). A rejecting release / re-claim is
   * warned about and the transfer bookkeeping is reset anyway (#resetTransfers),
   * so a stop always ends in a restartable state.
   * @returns {Promise<void>}
   */
  async cancelAll() {
    this.#swallowQueuedRejections();
    this.#abortPendingRegisters('cancelAll');
    if (this.#closed) {
      return;
    }
    try {
      await this.#device.releaseInterface(INTERFACE_0);
      await this.#device.claimInterface(INTERFACE_0);
    } catch (error) {
      console.warn('qa40x-usb: cancelAll re-claim failed:', describeFailure(error));
    } finally {
      this.#resetTransfers();
    }
  }

  /**
   * Releases interface 0 and closes the device. The release doubles as the
   * cancel of everything still in flight (see cancelAll), with the .catch()
   * attached first so no queued rejection goes unhandled.
   * @returns {Promise<void>}
   */
  async close() {
    if (this.#closed) {
      return;
    }
    this.#closed = true;
    this.#swallowQueuedRejections();
    this.#abortPendingRegisters('close');
    try {
      await this.#device.releaseInterface(INTERFACE_0);
    } catch (error) {
      console.warn('qa40x-usb: releaseInterface failed:', describeFailure(error));
    }
    try {
      await this.#device.close();
    } catch (error) {
      console.warn('qa40x-usb: close failed:', describeFailure(error));
    } finally {
      this.#resetTransfers();
    }
  }

  // --- completion pump -----------------------------------------------------

  #ensureOpen(op) {
    if (this.#closed) {
      throw new Error(`${op}: transport is closed`);
    }
  }

  /**
   * Attaches a no-op rejection handler to every queued transfer BEFORE the
   * interface is released. The pump still awaits the original promises (and so
   * still reports each abort through transferFailed, exactly as libusb reports
   * CANCELLED), but they are no longer unhandled while it works down the queue.
   */
  #swallowQueuedRejections() {
    for (const entry of this.#readQueue) {
      entry.promise.catch(() => {});
    }
    for (const entry of this.#writeQueue) {
      entry.promise.catch(() => {});
    }
  }

  /**
   * The last step of every teardown: drops the in-flight bookkeeping and lets a
   * pump start again. Without it a releaseInterface() that REJECTED — which
   * aborts nothing — leaves a queue whose head can never settle and a pump flag
   * stuck true, i.e. a transport that can never be restarted.
   *
   * The queue arrays are REPLACED rather than truncated: a pump still awaiting a
   * transfer keeps draining the array it captured, so an abort that does settle is
   * still reported through transferFailed (exactly as libusb reports CANCELLED),
   * while every new submission goes to the fresh queue with its own pump. #pump
   * checks that identity before clearing a flag, so a pump on a detached array can
   * never clear the live pump's flag and let a second pump race it.
   */
  #resetTransfers() {
    this.#readQueue = [];
    this.#writeQueue = [];
    this.#readPumping = false;
    this.#writePumping = false;
  }

  /** Starts the pump for one direction if it is not already draining. */
  #startPump(read) {
    if (read) {
      if (this.#readPumping) {
        return;
      }
      this.#readPumping = true;
    } else {
      if (this.#writePumping) {
        return;
      }
      this.#writePumping = true;
    }
    // Fire and forget: #pump never rejects, and its last act is to clear the flag.
    this.#pump(read);
  }

  /**
   * Consumes one direction's queue in SUBMISSION order — shift the head, await
   * it, dispatch, repeat. A callback that re-submits pushes onto the same queue
   * and is picked up by this very loop, so the pump keeps running as long as the
   * engine keeps the pipe armed. The queue-empty test and the flag clear happen
   * with no await between them, so a submit can never race a dying pump.
   */
  async #pump(read) {
    const queue = read ? this.#readQueue : this.#writeQueue;
    while (queue.length > 0) {
      const entry = queue.shift();
      let result;
      try {
        result = await entry.promise;
      } catch (error) {
        this.#dispatchFailed(read, describeFailure(error));
        continue;
      }
      if (result && result.status !== STATUS_OK) {
        this.#dispatchFailed(read, result.status);
        continue;
      }
      this.#dispatchCompleted(read, entry, result);
    }
    if (queue !== (read ? this.#readQueue : this.#writeQueue)) {
      return;      // a teardown detached this queue: the live pump owns the flag now
    }
    if (read) {
      this.#readPumping = false;
    } else {
      this.#writePumping = false;
    }
  }

  #dispatchCompleted(read, entry, result) {
    const listener = this.#listener;
    try {
      if (read) {
        // Copy out of the transfer's own buffer into the caller's, exactly as
        // Java copies out of the native Memory — and unconditionally, before the
        // listener check, so a transfer completed with no listener installed
        // still leaves the caller's buffer consistent.
        const view = result ? result.data : null;
        const transferred = view ? Math.min(view.byteLength, entry.buffer.length) : 0;
        if (transferred > 0) {
          entry.buffer.set(new Uint8Array(view.buffer, view.byteOffset, transferred), 0);
        }
        if (listener != null) {
          listener.readCompleted(entry.buffer, transferred);
        }
      } else if (listener != null) {
        const transferred = result && typeof result.bytesWritten === 'number' ? result.bytesWritten : entry.length;
        listener.writeCompleted(entry.buffer, transferred);
      }
    } catch (error) {
      console.error('qa40x-usb transfer callback failed:', error);
    }
  }

  #dispatchFailed(read, detail) {
    const listener = this.#listener;
    if (listener == null) {
      return;
    }
    try {
      listener.transferFailed(read, detail);
    } catch (error) {
      console.error('qa40x-usb transfer callback failed:', error);
    }
  }
}
