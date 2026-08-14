/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.sound.loopback.LoopbackDuplexEngine - the digital
// loopback SESSION: ONE always-duplex stream per device manager, on the same session model the
// QA40x analyzer runs (qa40x-duplex-engine.js). Input and output are two lanes of a single
// stream, and the lanes attach and detach rather than starting a direction of their own. The
// loop has one clock, so a capture and a playback that ran independently would be two clocks
// pretending to be one.
//
// LIFECYCLE
//   - The FIRST attach (attachGenerator or attachCapture) starts the session; a LATER attach
//     swaps that lane's source / sink LIVE, with no restart.
//   - An unattached generator lane produces DITHERED SILENCE, not digital zeros: a zero-amplitude
//     source through the lane quantiser at the session depth, with TPDF on its last bit. That is
//     the point of the backend - a bench whose noise floor is known exactly
//     (3.0103 - 6.0206 * N dBFS broadband at depth N), present whether or not a signal happens to
//     be playing. Dither lives on the PLAYBACK side only, here as everywhere, so the floor is
//     PRODUCED and the capture lane is a pure consumer of whatever the session rendered.
//   - ONE capture consumer. A second attach with a lane already live is a wiring bug (two
//     independent capture owners on one stream) and is refused loudly rather than silently
//     replacing the first.
//   - The LAST detach ends the session; changeFormat is a full stop + start while running.
//
// WHAT A SESSION WITHOUT HARDWARE IS INSTEAD - the Java class states these as the properties a
// software session has in place of a USB transport's, and they hold here unchanged: an absolute
// schedule instead of a transfer clock, no write debt, no register sequences, no two-lock
// discipline, no transfer-failure channel (nothing can vanish - there is no device to lose), and
// RENDER-THEN-DELIVER, the engine handing each block straight to the consumer so no queue exists
// between the lanes. The Java crossing and this file's predecessor both died of that last one.
//
// WEB DIVERGENCES, all forced by the platform rather than chosen:
//   - THE THREAD BECOMES A TIMER. Java paces on its own thread, sleeping to the next slot of an
//     absolute schedule. The browser has one task queue, so the session runs on setInterval
//     against the same schedule (start + n * blockMillis) and a late tick renders EVERY block that
//     has come due - which is what Java's loop gets for free when a negative wait skips the sleep.
//     A background tab clamps timer callbacks to about one per second; the schedule then delivers
//     the whole backlog in one tick, a known and deliberately un-engineered limitation of a
//     software session on a browser clock.
//   - FLOAT BLOCKS, NOT BYTES. Java renders interleaved PCM bytes that its capture base class
//     decodes; a web block is already the normalised {l, r, n} batch the ring consumes. The
//     staging is Float64 rather than Float32 ON PURPOSE: a 32-bit quantiser's step is 2 pow -31,
//     which Float32 (24-bit mantissa) cannot represent, so Float32 staging would mask the very
//     floor this backend exists to expose.
//   - NO STALL WATCHDOG anywhere in the backend. There is no device to lose, and a watchdog would
//     be actively wrong: the background-tab clamp above would false-report a healthy session as a
//     dead device.

import { DdsKernel, GenSignalForm, quantizePcm } from '../generator/dds-kernel.js';

/** Blocks per second - a 20 ms block. */
const BLOCKS_PER_SECOND = 50;
const MILLIS_PER_SECOND = 1000;
/** The silence source's nominal tone. At amplitude zero every sample is exactly 0.0 before
 *  quantisation, so what leaves the lane is the dither floor and nothing else - the frequency is
 *  only what the kernel needs to be constructed. */
const SILENCE_TONE_HZ = 1000;

/**
 * One rendered block - the shape audio/shared-capture.js already dispatches, so the capture lane
 * hands it on unchanged.
 *
 * @typedef {Object} LoopbackBlock
 * @property {Float64Array} l ch0, normalised to -1...+1
 * @property {Float64Array} r ch1, the calibrated/attenuated channel every capture path measures
 * @property {number} n frames carried in this block
 */

export class LoopbackDuplexEngine {

  /** The session format and everything sized from it. */
  #sampleRate = 0;
  #bitDepth = 0;
  #blockFrames = 0;
  #blockMillis = 0;
  /** @type {?LoopbackBlock} the ONE block the session renders into and hands on. Reused for the
   *  reason it is reused in Java: delivery is synchronous, so the consumer has read it before the
   *  next render can touch it - the same contract every capture source states. */
  #block = null;
  /** @type {function():number} uniform [0,1) source for the silence lane's dither. */
  #rng;
  /** @type {?Object} the zero-amplitude source behind the dithered silence. */
  #silence = null;
  /** @type {?Object} the attached generator lane, or the silence source while none is. */
  #source = null;
  /** @type {?Object} the attached capture consumer; null while none is. */
  #consumer = null;
  #generatorAttached = false;
  #captureAttached = false;
  /** @type {?Object} the session timer; null while the session is not running. */
  #timer = null;
  /** performance.now() at the moment the session started - the schedule's origin. */
  #startMillis = 0;
  /** Blocks rendered so far; block n is due at startMillis + n * blockMillis, counting from 1. */
  #rendered = 0;

  /**
   * @param {number} sampleRateHz the session rate (Hz)
   * @param {number} bitDepth the session depth - the quantiser resolution AND the silence lane's
   *        dither depth
   * @param {Object} [deps]
   * @param {function():number} [deps.rng=Math.random] uniform [0,1) source for the TPDF dither;
   *        injected so a test can pin the noise
   */
  constructor(sampleRateHz, bitDepth, { rng = Math.random } = {}) {
    this.#rng = rng;
    this.#applyFormat(sampleRateHz, bitDepth);
  }

  /** The session rate (Hz). */
  get sampleRate() { return this.#sampleRate; }

  /** The session depth - the quantiser resolution AND the silence lane's dither depth. */
  get bitDepth() { return this.#bitDepth; }

  /** True while the session is running, i.e. at least one lane is attached. */
  get streaming() { return this.#timer != null; }

  /** The session's ONE floor - the unattached generator lane's dithered silence. A lane that has
   *  to fill a block with no source behind it renders from here rather than writing zeros, so
   *  nothing this backend delivers is ever undithered.
   *  @returns {{nextBlock: function(LoopbackBlock): void}} */
  get silence() { return this.#silence; }

  /**
   * Attaches / live-swaps the generator lane's sample source; starts the session if idle.
   *
   * @param {{nextBlock: function(LoopbackBlock): void}} source fills a block with the lane's own
   *        quantised, re-normalised stereo
   */
  attachGenerator(source) {
    if (source == null) {
      throw new Error('source');
    }
    this.#source = source;
    this.#generatorAttached = true;
    if (!this.streaming) this.#startSession();
  }

  /** Detaches the generator lane (reverts to dithered silence); ends the session if it was the
   *  last lane. */
  detachGenerator() {
    this.#source = this.#silence;
    this.#generatorAttached = false;
    if (this.streaming && !this.#captureAttached) this.#endSession();
  }

  /**
   * Attaches the capture lane's consumer; starts the session if idle. ONE consumer per engine -
   * the scope and the FFT share it through the shared capture - so a second attach with a lane
   * already live is refused rather than silently replacing the first.
   *
   * @param {{onAudio: function(LoopbackBlock): void}} consumer the capture lane
   */
  attachCapture(consumer) {
    if (consumer == null) {
      throw new Error('consumer');
    }
    if (this.#captureAttached) {
      throw new Error('loopback capture lane already attached - one duplex engine has a single '
        + 'capture consumer (scope and FFT share it through the shared capture); refusing to '
        + 'split the stream');
    }
    this.#consumer = consumer;
    this.#captureAttached = true;
    if (!this.streaming) this.#startSession();
  }

  /** Detaches the capture lane (rendered blocks are discarded); ends the session if it was the
   *  last lane. */
  detachCapture() {
    this.#consumer = null;
    this.#captureAttached = false;
    if (this.streaming && !this.#generatorAttached) this.#endSession();
  }

  /**
   * Moves the WHOLE session to another rate / depth; a full stop + start while running. Both
   * fields together, because both size the session: the block frame count comes from the rate and
   * the silence quantiser from the depth, and a session cannot be half at one format.
   *
   * A lane that goes live at a format the session is not on is what brings this about - playing a
   * file recorded at another rate, for instance. A capture already running keeps delivering; the
   * application's two directions are kept on one format by the Preferences constraint rather than
   * by a notification from here.
   *
   * @param {number} sampleRateHz
   * @param {number} bitDepth
   */
  changeFormat(sampleRateHz, bitDepth) {
    const running = this.streaming;
    if (running) this.#endSession();
    this.#applyFormat(sampleRateHz, bitDepth);
    if (running) this.#startSession();
  }

  /** Sizes everything the session format decides and rebuilds the silence source. */
  #applyFormat(sampleRateHz, bitDepth) {
    this.#sampleRate = sampleRateHz;
    this.#bitDepth = bitDepth;
    this.#blockFrames = Math.max(1, Math.floor(sampleRateHz / BLOCKS_PER_SECOND));
    this.#blockMillis = this.#blockFrames * MILLIS_PER_SECOND / sampleRateHz;
    this.#block = {
      l: new Float64Array(this.#blockFrames),
      r: new Float64Array(this.#blockFrames),
      n: this.#blockFrames,
    };
    // The floor is the DEPTH's own - TPDF on its last bit - and it is produced here, on the
    // playback side, because that is the only side dither exists on.
    const generator = new DdsKernel({
      form: GenSignalForm.SINE,
      frequency: SILENCE_TONE_HZ,
      sampleRate: sampleRateHz,
      amplitudeVRms: 0.0,
      dacFsVoltageAmpl: 1.0,
      rng: this.#rng,
    });
    const scale = Math.pow(2, bitDepth - 1) - 1;
    const rng = this.#rng;
    this.#silence = {
      nextBlock: (block) => {
        const frames = block.n;
        const l = block.l;
        const r = block.r;
        for (let f = 0; f < frames; f++) {
          // ONE dithered sample per FRAME written to both channels, exactly as a playing lane
          // encodes - so the floor is the same block whichever lane produced it.
          const sample = quantizePcm(generator.nextSample(), bitDepth, bitDepth, rng) / scale;
          l[f] = sample;
          r[f] = sample;
        }
      },
    };
    if (!this.#generatorAttached) this.#source = this.#silence;
  }

  #startSession() {
    this.#startMillis = performance.now();
    this.#rendered = 0;
    // No delivery at the origin: block 1 is due one period LATER, because the Java pacer
    // increments its counter and sleeps BEFORE it renders.
    this.#timer = setInterval(() => this.#tick(), this.#blockMillis);
    // A pending timer holds a Node test process open; browsers have no unref and ignore this.
    if (this.#timer && typeof this.#timer.unref === 'function') this.#timer.unref();
  }

  #endSession() {
    if (this.#timer != null) {
      clearInterval(this.#timer);
      this.#timer = null;
    }
  }

  /** Renders every block whose slot on the ABSOLUTE schedule (start + n * blockMillis) has come
   *  due and hands each one straight to the attached consumer, so the session cannot drift the way
   *  repeated relative delays would. */
  #tick() {
    const elapsed = performance.now() - this.#startMillis;
    while ((this.#rendered + 1) * this.#blockMillis <= elapsed) {
      this.#rendered++;
      this.#source.nextBlock(this.#block);
      const sink = this.#consumer;
      if (sink != null) sink.onAudio(this.#block);
    }
  }
}
