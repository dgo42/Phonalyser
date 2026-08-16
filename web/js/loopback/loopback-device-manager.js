/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.sound.loopback.LoopbackDeviceManager - the manager of
// the digital loopback backend: one device, listed for both directions, and the one always-duplex
// session the two lanes attach to.
//
// IT OWNS THE SESSION because both lanes are created here: a playback and a capture opened from
// this manager are the two ends of the same loop, and the loop has ONE clock. One session per
// manager, built lazily at the first acquire - not a module-level singleton (which would join
// lanes across managers) and not one per lane (which would join nothing at all).
//
// The formats are the standard rate ladder at the four depths the encoder supports; nothing is
// probed, because there is no hardware to ask.
//
// THE SIXTH JAVA CLASS HAS NO TWIN HERE. Java registers this backend through a ServiceLoader
// provider whose whole job is to report the backend as unconditionally available and to
// construct the manager. On the web that role IS the composition seam: the engine's backend
// dispatch builds this manager and lists the backend beside the other local ones, so a separate
// provider class would carry no decision of its own. Availability stays unconditional in the
// sense the provider means it - there is no device to find, no permission to request and no
// transport to open - subject only to the rule every LOCAL backend obeys: a build that serves a
// remote bench offers no local backend at all.

import { LoopbackCapture } from './loopback-capture.js';
import { LoopbackDeviceRef } from './loopback-device-ref.js';
import { LoopbackDuplexEngine } from './loopback-duplex-engine.js';
import { LoopbackFormatConstraint } from './loopback-format-constraint.js';
import { LoopbackPlayback } from './loopback-playback.js';

/** The standard sample-rate ladder, 8 k to 768 k. Both directions offer the same list - the loop
 *  has ONE clock, so an input and an output rate that differ cannot exist here. This backend
 *  enumerates its own formats, so the ladder is longer than the Web Audio one by the rates a
 *  browser context will not grant (32000 among them). */
const SAMPLE_RATES = Object.freeze([
  8000, 11025, 16000, 22050, 32000, 44100, 48000, 88200, 96000,
  176400, 192000, 352800, 384000, 705600, 768000,
]);
/** 20 bits is a real card format (right-aligned in a 3-byte container), not a rounding of 24. */
const BIT_DEPTHS = Object.freeze([16, 20, 24, 32]);
const CHANNELS = 2;
const BITS_PER_BYTE = 8;

/** Java's AudioFormat.Encoding.PCM_SIGNED, as the advertised format's encoding tag. */
const PCM_SIGNED = 'PCM_SIGNED';
/** The samples are little-endian - the AudioFormat bigEndian flag is false. */
const BIG_ENDIAN = false;

export class LoopbackDeviceManager {

  /** @type {LoopbackDeviceRef} the one device, the same handle on both directions. */
  #device = new LoopbackDeviceRef();
  /** @type {?LoopbackDuplexEngine} the one session both lanes of this manager attach to; built at
   *  the first acquire, because the format it runs at is the format the first lane asks for. */
  #engine = null;
  /** The format the session is on; 0 while it has not been built. */
  #currentRate = 0;
  #currentBits = 0;
  /** @type {function():number} */
  #inputDepthOf;
  /** @type {function():number} */
  #outputDepthOf;
  /** @type {function():number} uniform [0,1) source handed to the session's silence lane. */
  #rng;

  /**
   * The two depths arrive as SUPPLIERS rather than values, and are handed to the lanes unread:
   * a lane resolves its depth at its own session boundary, which is where the QA40x session reads
   * its sample width too. The manager is built once for the life of the page and the engine keeps
   * its capture source across sessions, so a number captured here would freeze the resolution at
   * whatever was selected when the page loaded.
   *
   * @param {Object} deps
   * @param {function():number} deps.inputDepthOf the selected capture bit depth
   * @param {function():number} deps.outputDepthOf the selected playback bit depth
   * @param {function():number} [deps.rng=Math.random] uniform [0,1) source for the session's
   *        silence lane; injected so a test can pin the noise
   * @param {boolean} [deps.armBus=true] arm the format constraint - off for a test that drives the
   *        manager without wanting a bus subscriber, the same seam the QA40x manager offers
   */
  constructor({ inputDepthOf, outputDepthOf, rng = Math.random, armBus = true } = {}) {
    if (typeof inputDepthOf !== 'function' || typeof outputDepthOf !== 'function') {
      throw new Error('inputDepthOf / outputDepthOf');
    }
    this.#inputDepthOf = inputDepthOf;
    this.#outputDepthOf = outputDepthOf;
    this.#rng = rng;
    if (armBus) {
      // The format constraint keeps this backend's two directions on ONE rate and ONE depth; its
      // subscription must be live before the Preferences dialog can move a combo. Java arms it
      // from the backend's settings-UI service, which the web has no equivalent of - the manager
      // is built once at composition time and is the only thing that owns this backend here.
      LoopbackFormatConstraint.instance();
    }
  }

  /**
   * The one duplex session, built at the first acquire and MOVED to `sampleRateHz` / `bitDepth`
   * when a lane goes live at another format (Java acquireEngine).
   *
   * The whole session moves, which is what makes a file played at another rate simply work: the
   * lane that goes live names the format, the running lanes keep delivering on it, and nothing has
   * to negotiate. There is only one clock to move, so there is nothing else it could mean.
   *
   * @param {number} sampleRateHz
   * @param {number} bitDepth
   * @returns {LoopbackDuplexEngine}
   */
  acquireEngine(sampleRateHz, bitDepth) {
    if (this.#engine == null) {
      this.#engine = new LoopbackDuplexEngine(sampleRateHz, bitDepth, { rng: this.#rng });
    } else if (this.#currentRate !== sampleRateHz || this.#currentBits !== bitDepth) {
      this.#engine.changeFormat(sampleRateHz, bitDepth);
    }
    this.#currentRate = sampleRateHz;
    this.#currentBits = bitDepth;
    return this.#engine;
  }

  /** @returns {LoopbackDeviceRef[]} the one device, as a capture handle. Synchronous: there is
   *           nothing to enumerate and nothing to await. */
  listInputDevices() {
    return [this.#device];
  }

  /** @returns {LoopbackDeviceRef[]} the same handle, as a playback handle. */
  listOutputDevices() {
    return [this.#device];
  }

  /**
   * @param {number} index
   * @param {boolean} isOutput ignored - one device is both directions - and kept for signature
   *        parity with the other backends
   * @returns {LoopbackDeviceRef}
   */
  getDeviceByIndex(index, isOutput) {
    if (index !== 0) {
      throw new Error(`No loopback device at index ${index}`);
    }
    return this.#device;
  }

  /** True - the depth is this backend's whole subject: it is the quantiser resolution the lanes
   *  encode at and therefore the noise floor the bench delivers, so Preferences shows the depth
   *  combos and commits what is picked.
   *  @returns {boolean} */
  hasBitDepth() {
    return true;
  }

  /**
   * The rate ladder times the four depths, identical for both directions - signed PCM, stereo,
   * little-endian, frame rate equal to the sample rate.
   *
   * @param {LoopbackDeviceRef} device
   * @param {boolean} output true for the playback direction; the list is the same either way
   * @returns {Object[]} empty for a handle from another backend
   */
  listSupportedFormats(device, output) {
    if (!(device instanceof LoopbackDeviceRef)) {
      return [];
    }
    const formats = [];
    for (const rate of SAMPLE_RATES) {
      for (const bits of BIT_DEPTHS) {
        formats.push(Object.freeze({
          encoding: PCM_SIGNED,
          sampleRate: rate,
          bits,
          channels: CHANNELS,
          // Rounded UP: a depth that is not a whole number of bytes rides right-aligned in the
          // next larger container (20 bits in 3 bytes).
          frameBytes: Math.ceil(bits / BITS_PER_BYTE) * CHANNELS,
          frameRate: rate,
          bigEndian: BIG_ENDIAN,
        }));
      }
    }
    return formats;
  }

  /**
   * The capture lane, bound to this manager's one session. The session rate reaches it at its own
   * open(), which is where every web capture source takes it (Java passes it to the constructor
   * because its capture is built fresh per acquire), and so does the depth.
   *
   * @param {LoopbackDeviceRef} device ignored - the backend has exactly one
   * @returns {LoopbackCapture}
   */
  openCapture(device) {
    return new LoopbackCapture(this, this.#inputDepthOf);
  }

  /**
   * The playback lane, bound to this manager's one session.
   *
   * @param {LoopbackDeviceRef} device ignored - the backend has exactly one
   * @param {number} ditherBits the configured dither, handed to the lane as its opening value;
   *        zero means the last-bit default - the lane is never undithered
   *        (loopback-playback.js states the rule)
   * @returns {LoopbackPlayback}
   */
  openPlayback(device, ditherBits) {
    return new LoopbackPlayback(this, this.#outputDepthOf,
      { ditherBits: ditherBits != null ? ditherBits : 0, rng: this.#rng });
  }
}
