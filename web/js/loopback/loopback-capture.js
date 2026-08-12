/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.sound.loopback.LoopbackCapture - the capture lane of
// the digital loopback, as the web's CaptureSource (the contract declared in
// audio/shared-capture.js). It takes the blocks the playback lane quantised off the crossing and
// delivers them to the ring, paced to the wall clock at the selected sample rate: the workers
// above assume a real-time stream, and a capture that ran as fast as the CPU allows would starve
// their averaging of any time reference.
//
// WHEN NO PLAYBACK LANE IS PRODUCING, the delivered block is this lane's own DITHERED SILENCE
// rather than digital zeros. That is the point of the backend: a bench whose noise floor is known
// exactly (3.0103 - 6.0206 * N dBFS broadband at depth N), present whether or not a signal
// happens to be playing. The silence is a ZERO-AMPLITUDE signal source pushed through the same
// quantiser the playback lane encodes with, at the same depth - not a zeroed buffer, which would
// read as minus infinity and hide the very floor this backend exists to show.
//
// WEB DIVERGENCES, all forced by the platform rather than chosen:
//   - THREADS BECOME A TIMER. Java delivers on its own consume thread, sleeping to the next slot
//     of an absolute schedule. The browser has one task queue, so the lane runs on setInterval
//     against the same schedule (start + n * blockMillis) and a late tick delivers EVERY block
//     that has come due, which is what Java's loop gets for free when a negative wait skips the
//     sleep. Its order is kept: the wait comes FIRST and the crossing is polled after it, so a
//     block the playback lane offered during the period is picked up rather than missed. A
//     background tab clamps timer callbacks to about one per second; the schedule then delivers
//     the whole backlog in one tick, a known and deliberately un-engineered limitation of a
//     software lane on a browser clock.
//   - NO STALL WATCHDOG. Java arms the base class's delivery deadline and never consults it, so
//     there is nothing to port; and a watchdog would be actively wrong here, because the
//     background-tab clamp above would false-report a healthy lane as a dead device. Nothing can
//     end this stream from below either - there is no device to lose - so the capture-ended
//     callback of the contract is accepted and never invoked.
//   - FLOAT BLOCKS, NOT BYTES. Java's blocks are interleaved PCM bytes that its base class
//     decodes; a web block is already the normalised {l, r, n} batch the ring consumes, so this
//     lane hands a polled block on unchanged.

import { DdsKernel, GenSignalForm, quantizePcm } from '../generator/dds-kernel.js';

/** Blocks per second - a 20 ms block, matching the playback lane's period. */
const BLOCKS_PER_SECOND = 50;
const MILLIS_PER_SECOND = 1000;
/** The silence source's nominal tone. At amplitude zero every sample is exactly 0.0 before
 *  quantisation, so what reaches the ring is the dither floor and nothing else - the frequency
 *  is only what the kernel needs to be constructed. */
const SILENCE_TONE_HZ = 1000;

export class LoopbackCapture {

  /** @type {import('./loopback-crossing.js').LoopbackCrossing} */
  #crossing;
  /** @type {function():number} the SELECTED input bit depth, asked at each open(). */
  #depthOf;
  /** That depth for the open session - the quantiser resolution AND the dither depth, so the
   *  floor is identical whether or not a signal is present. 0 while closed. */
  #bitDepth = 0;
  /** @type {function():number} uniform [0,1) source for the dither. */
  #rng;
  /** (2 pow (N-1)) - 1: the quantiser's full-scale integer, which the sample is divided by to
   *  return to -1...+1. */
  #scale = 0;
  #sampleRate = 0;
  /** Frames per block, floored - the schedule follows the floored block, not a nominal 20 ms. */
  #blockFrames = 0;
  /** That block's duration in milliseconds - the unit of the absolute schedule. */
  #blockMillis = 0;
  /** @type {?DdsKernel} the zero-amplitude source behind the dithered silence; null while closed. */
  #silence = null;
  /** @type {?import('./loopback-crossing.js').LoopbackBlock} ONE reused silence block, refilled
   *  with fresh dither whenever the crossing is empty. Reusing it is safe for exactly the reason
   *  it is safe in Java: the dispatch below is synchronous, so a consumer has read the block
   *  before the next refill can touch it. */
  #silenceBlock = null;
  /** @type {?(batch: import('./loopback-crossing.js').LoopbackBlock) => void} */
  #onBatch = null;
  /** @type {?Object} the consume timer; null while the lane is not delivering. */
  #timer = null;
  /** performance.now() at the moment delivery started - the schedule's origin. */
  #startMillis = 0;
  /** Blocks delivered so far; block n is due at startMillis + n * blockMillis, counting from 1. */
  #delivered = 0;

  /**
   * @param {import('./loopback-crossing.js').LoopbackCrossing} crossing the manager's crossing,
   *        which the playback lane produces into and this lane consumes from
   * @param {function():number} depthOf the selected input bit depth (16 / 20 / 24 / 32) - the
   *        quantiser resolution and the dither depth in one. A SUPPLIER, read at each open() and
   *        not a value frozen at construction: this source outlives a session (the engine's
   *        dispatch keeps one per backend and rebuilds it only when the backend changes), so a
   *        frozen depth would go on encoding at the old resolution after the operator picked a
   *        new one. Reading it per session is what the QA40x does with its own width.
   * @param {Object} [deps]
   * @param {function():number} [deps.rng=Math.random] uniform [0,1) source for the TPDF dither;
   *        injected so a test can pin the noise
   */
  constructor(crossing, depthOf, { rng = Math.random } = {}) {
    if (crossing == null) {
      throw new Error('crossing');
    }
    if (typeof depthOf !== 'function') {
      throw new Error('depthOf');
    }
    this.#crossing = crossing;
    this.#depthOf = depthOf;
    this.#rng = rng;
  }

  /** The captured rate - exactly the requested one: a software lane has no clock of its own to
   *  re-pin the analysis to. 0 while closed. */
  get sampleRate() { return this.#sampleRate; }

  /** True while the lane is held open (Java: while its buffer is allocated). */
  get isOpen() { return this.#silenceBlock != null; }

  /**
   * Reads the selected depth for this session, sizes the block from the session rate, allocates
   * the silence this lane delivers when nothing is playing, and CLEARS the crossing so nothing
   * from an earlier capture can reach this one.
   *
   * @param {string} deviceId ignored - the backend has exactly one device, exactly as Java's
   *        openCapture ignores the DeviceRef it is handed
   * @param {number} sampleRateHz the session rate (Hz)
   * @returns {Promise<void>}
   */
  async open(deviceId, sampleRateHz) {
    // The depth is a SESSION boundary value, like the rate beside it: asked here, held for the
    // life of the open lane, so no block is ever encoded at half one depth and half another.
    this.#bitDepth = this.#depthOf();
    this.#scale = Math.pow(2, this.#bitDepth - 1) - 1;
    this.#sampleRate = sampleRateHz;
    this.#blockFrames = Math.max(1, Math.floor(sampleRateHz / BLOCKS_PER_SECOND));
    this.#blockMillis = this.#blockFrames * MILLIS_PER_SECOND / sampleRateHz;
    this.#silence = new DdsKernel({
      form: GenSignalForm.SINE,
      frequency: SILENCE_TONE_HZ,
      sampleRate: sampleRateHz,
      amplitudeVRms: 0.0,
      dacFsVoltageAmpl: 1.0,
      rng: this.#rng,
    });
    this.#silenceBlock = {
      l: new Float64Array(this.#blockFrames),
      r: new Float64Array(this.#blockFrames),
      n: this.#blockFrames,
    };
    this.#crossing.clear();
  }

  /**
   * Starts delivering one block per block period.
   *
   * @param {(batch: import('./loopback-crossing.js').LoopbackBlock) => void} onBatch the ring
   *        sink; r is ch1, the calibrated/attenuated channel every capture path measures. A
   *        SILENCE batch carries the one reused pair, so a consumer must read it synchronously
   *        and must not retain it (the same contract every capture source states).
   * @param {?(reason: {name: string, logText: string}) => void} onCaptureEnded accepted for the
   *        contract and never invoked - see the header: this lane has no device that can die
   *        under it and no stall watchdog to declare one
   * @returns {Promise<void>}
   */
  async start(onBatch, onCaptureEnded) {
    if (this.#silenceBlock == null) {
      throw new Error('Call open() before start()');
    }
    this.#onBatch = onBatch;
    this.#startMillis = performance.now();
    this.#delivered = 0;
    // No delivery at the origin: the first block is due one period LATER, because the wait comes
    // before the poll (the Java consume loop increments its counter and sleeps first).
    this.#timer = setInterval(() => this.#tick(), this.#blockMillis);
    // A pending timer holds a Node test process open; browsers have no unref and ignore this.
    if (this.#timer && typeof this.#timer.unref === 'function') {
      this.#timer.unref();
    }
  }

  /** Stops delivering. The crossing is NOT drained here - Java's stopRecording only joins its
   *  consume thread - so a stop that is followed by another start resumes on what is in flight;
   *  the drain belongs to the open/close pair below. */
  async stop() {
    if (this.#timer != null) {
      clearInterval(this.#timer);
      this.#timer = null;
    }
    this.#onBatch = null;
  }

  /** Stops first if still delivering, then drops the buffers and CLEARS the crossing a second
   *  time. The lane is never held open across captures: the next open() starts from a cleared
   *  crossing and a fresh buffer, which is what keeps one capture's tail out of the next. */
  async close() {
    await this.stop();
    this.#silence = null;
    this.#silenceBlock = null;
    this.#crossing.clear();
  }

  /**
   * Delivers every block whose slot on the ABSOLUTE schedule (start + n * blockMillis) has come
   * due, so the stream cannot drift the way repeated relative delays would, and a tick the
   * browser delivered late hands over the whole backlog rather than one block.
   */
  #tick() {
    if (this.#onBatch == null) {
      return;
    }
    const elapsed = performance.now() - this.#startMillis;
    while ((this.#delivered + 1) * this.#blockMillis <= elapsed) {
      // A null poll is NORMAL: nothing is playing, and this lane then delivers its own dithered
      // silence for that slot.
      let block = this.#crossing.poll();
      if (block == null) {
        block = this.#fillSilence();
      }
      this.#delivered++;
      this.#onBatch(block);
    }
  }

  /**
   * Refills the one reused silence block with a fresh draw of dithered silence - the same
   * quantise-then-normalise path the playback lane renders a signal through (the shared unit is
   * quantizePcm, as it is PcmQuantizer in Java); only the buffer policy differs, because nothing
   * else ever holds this block.
   *
   * @returns {import('./loopback-crossing.js').LoopbackBlock}
   */
  #fillSilence() {
    const block = this.#silenceBlock;
    const generator = this.#silence;
    const frames = block.n;
    const depth = this.#bitDepth;
    const scale = this.#scale;
    const rng = this.#rng;
    const l = block.l;
    const r = block.r;
    for (let f = 0; f < frames; f++) {
      // ONE dithered sample per FRAME written to both channels, exactly as the playback lane
      // encodes - so the floor is the same block whether it came from the crossing or from here.
      const sample = quantizePcm(generator.nextSample(), depth, depth, rng) / scale;
      l[f] = sample;
      r[f] = sample;
    }
    return block;
  }
}
