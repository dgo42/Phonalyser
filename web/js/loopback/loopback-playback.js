/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.sound.loopback.LoopbackPlayback - the playback lane of
// the digital loopback, as the web's PlaybackSink (the contract declared in
// generator/generator-controller.js). It quantises the generator to the selected bit depth and
// hands the blocks TO THE CROSSING instead of to a device: there is no hardware behind this lane,
// and loopback-capture.js is the only thing that ever reads what it writes.
//
// It is paced to the wall clock all the same, so the crossing runs at the sample rate the caller
// asked for - the workers above assume a real-time stream, and a lane that produced as fast as
// the CPU allows would starve their averaging of any time reference.
//
// DITHER IS THIS BACKEND'S POINT, and it is NOT the caller's to set. The depth is the SELECTED
// bit depth and nothing else: dithering at bit N puts TPDF on the last bit, which is what makes
// this backend a bench with a KNOWN noise floor (3.0103 - 6.0206 * N dBFS broadband). The
// caller's dither setting - the value in the start spec and the live change that rides every
// generator retune - is therefore accepted and discarded; honouring it would let the floor move,
// or vanish, under a measurement. In Java the same rule reads as an un-overridden interface
// no-op; on the web the live-control channel simply ignores the field.
//
// THE LANE GATE AND THE PER-LANE SCALE ARE NOT HONOURED EITHER, for the same structural reason:
// Java's loopback lane never calls setOutputChannels / setChannelScale, so its quantiser keeps
// the BOTH gate and the 1.0 scales and both channels carry the identical quantised sample. There
// is no DAC here whose two lanes could differ.
//
// WEB DIVERGENCES, all forced by the platform rather than chosen:
//   - THREADS BECOME A TIMER. Java renders on its own thread and sleeps out the remainder of
//     each block period. The browser has one task queue, so the lane runs on setInterval against
//     the same ABSOLUTE schedule (start + n * blockMillis) and a late tick offers EVERY block
//     that has come due, which is what Java's loop gets for free when a negative wait skips the
//     sleep. A background tab clamps timer callbacks to about one per second: the schedule then
//     delivers the whole backlog in one tick, which is a known and deliberately un-engineered
//     limitation of a software lane on a browser clock.
//   - FLOAT BLOCKS, NOT BYTES. Java writes interleaved little-endian PCM bytes and its capture
//     side decodes them; the web capture path carries normalised floats and has no byte stride,
//     so the integer sample is divided back by the quantiser's own scale into a Float64 pair.
//     The staging is Float64 rather than the Float32Array of the batch typedef ON PURPOSE: a
//     32-bit quantiser's step is 2 pow -31, which Float32 (24-bit mantissa) cannot represent, so
//     Float32 staging would mask the very floor this backend exists to expose. Both consumers of
//     a batch take it: audio/signal-buffer.js appendBatch copies through subarray/set into its
//     Float64 ring, and audio/backend.js copies with Float64Array.from.
//   - NO BOUNDED PLAY, BUT A BUFFER LANE. Java has a second play() overload that renders a fixed
//     number of seconds and a SHORT last block; nothing on the web asks a sink for that, so the
//     lane runs until close(). What the web does ask for is playBuffer: the sweep and the file
//     player hand over a finished buffer instead of a DDS description, and a sink without that
//     entry point is silently skipped by the controller, which then opens a Web Audio context -
//     so the sound would leave through the speakers while this backend captured its own silence.
//     Both paths therefore share one schedule and one quantiser here.

import {
  DdsKernel,
  GenSignalForm,
  isDualToneCorrectionFile,
  loadHarmonics,
  loadIntermod,
  quantizePcm,
} from '../generator/dds-kernel.js';

/** Blocks per second - a 20 ms block, matching the capture lane's period so one produced block
 *  per consumed block keeps the crossing shallow. */
const BLOCKS_PER_SECOND = 50;
const MILLIS_PER_SECOND = 1000;

export class LoopbackPlayback {

  /** @type {import('./loopback-crossing.js').LoopbackCrossing} */
  #crossing;
  /** @type {function():number} the SELECTED output bit depth, asked when the lane goes live. */
  #depthOf;
  /** That depth for the running lane - the quantiser resolution AND the dither depth. */
  #bitDepth = 0;
  /** @type {function():number} uniform [0,1) source for the dither - Java's SplittableRandom. */
  #rng;
  /** The quantiser's full-scale integer, (2 pow (N-1)) - 1 and NOT 2 pow (N-1): the same value
   *  the encoder scales by, so dividing by it returns the sample to -1...+1 exactly. */
  #scale = 0;
  /** Session rate, from open(); 0 while closed. */
  #sampleRate = 0;
  /** Frames per block, floored - the schedule follows the floored block, not a nominal 20 ms. */
  #blockFrames = 0;
  /** That block's duration in milliseconds - the unit of the absolute schedule. */
  #blockMillis = 0;
  /** @type {?DdsKernel} the mono DDS; null before start() and for a pre-rendered buffer lane,
   *  which has no kernel to retune. */
  #generator = null;
  /** @type {?function():number} where one mono sample comes from while the lane is live - the
   *  kernel, or a reader over a pre-rendered buffer. Null = the lane is not producing, which is
   *  the ONE test the schedule makes (a buffer lane has no kernel to test instead). */
  #source = null;
  /** @type {?Object} the render timer; null while the lane is not producing. */
  #timer = null;
  /** performance.now() at the moment the lane went live - the schedule's origin. */
  #startMillis = 0;
  /** Blocks offered so far; block n is due at startMillis + n * blockMillis. */
  #produced = 0;

  /**
   * Live-control channel, MessagePort-shaped so every sink speaks ONE protocol (the Web Audio
   * twin hands out its worklet node's real MessagePort; here the message is applied
   * synchronously to the in-process kernel). Built once - a getter returning a fresh object
   * would allocate on every FLL trim.
   */
  #port = { postMessage: (msg) => this.#applyControl(msg || {}) };

  /**
   * @param {import('./loopback-crossing.js').LoopbackCrossing} crossing the manager's crossing,
   *        which this lane produces into and the capture lane consumes from
   * @param {function():number} depthOf the selected output bit depth (16 / 20 / 24 / 32) - the
   *        quantiser resolution and the dither depth in one. A SUPPLIER, read when the lane goes
   *        live rather than frozen at construction, so the depth a session encodes at is the one
   *        the operator has selected at that moment - the same session-boundary read the QA40x
   *        session makes for its own sample width.
   * @param {Object} [deps]
   * @param {function():number} [deps.rng=Math.random] uniform [0,1) source for the TPDF dither
   *        (Java's per-instance SplittableRandom); injected so a test can pin the noise
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

  /** The session rate - exactly the requested one: a software lane has no clock to negotiate
   *  against. 0 while closed. */
  get sampleRate() { return this.#sampleRate; }

  /** Live-control channel; non-null once the lane has been started. */
  get port() { return this.#generator != null ? this.#port : null; }

  /**
   * Takes the session rate and sizes the block from it - Java LoopbackPlayback's constructor
   * plus open(). Nothing is produced yet, so the crossing stays as it is.
   *
   * @param {Object} spec
   * @param {number} spec.sampleRate the session rate (Hz)
   * @returns {Promise<number>} the granted rate - the requested one
   */
  async open(spec) {
    this.#sampleRate = spec.sampleRate;
    this.#blockFrames = Math.max(1, Math.floor(this.#sampleRate / BLOCKS_PER_SECOND));
    this.#blockMillis = this.#blockFrames * MILLIS_PER_SECOND / this.#sampleRate;
    return this.#sampleRate;
  }

  /**
   * Java play(generator, stopFlag, readyLatch): builds the mono DDS from the fully-configured
   * description and puts the lane on air. Built HERE rather than handed in, because the Web
   * Audio twin can only build its kernel inside the worklet and the controller therefore passes
   * options rather than an instance to every sink.
   *
   * There is nothing to pre-fill - Java counts its readyLatch down before its render loop for
   * the same reason - so "ready" is simply "the lane started".
   *
   * @param {Object} spec the generator description; see the PlaybackSink typedef. Its ditherBits
   *        field is deliberately ignored (see the header).
   * @returns {Promise<void>}
   */
  async start(spec) {
    const generator = new DdsKernel({
      form: spec.form,
      frequency: spec.frequency,
      sampleRate: this.#sampleRate,
      amplitudeVRms: spec.amplitudeVRms,
      dacFsVoltageAmpl: spec.dacFsVoltageAmpl,
      rng: this.#rng,
    });
    this.#generator = generator;
    // Duty / dual-tone / sweep / predistortion BEFORE the lane goes live, exactly as Java hands
    // play() an already-configured SignalGenerator - nothing ever renders at a default value.
    if (spec.control) this.#applyControl(spec.control);
    // Warmup consumed nextSample() advances sweep state; rewind to sample 0 so a freq-response
    // sweep aligns with the deconvolution reference.
    generator.resetSweepPosition();
    this.#goLive(() => generator.nextSample());
  }

  /**
   * Plays ONE pre-rendered buffer through the crossing - the FreqResp sweep and the file player,
   * which hand a finished buffer rather than a DDS description.
   *
   * NO RESAMPLING, and that is the point for the sweep: the buffer is authored at the session
   * rate and the crossing runs at that same rate, so what is played is sample-for-sample what the
   * deconvolution holds as its reference. The samples go through the SAME quantise-then-normalise
   * path a tone does, because the depth's floor is what this backend is for; they are otherwise
   * unscaled, since calibration enters the deconvolution maths and scaling the played lane
   * against an unscaled reference would inject a gain error.
   *
   * Past the end of a non-looping buffer the lane keeps producing the dithered silence of the
   * same depth - the capture lane must never see the stream stop - and the caller is told once.
   *
   * @param {Float32Array|Float64Array} mono the pre-rendered samples, at the session rate
   * @param {Object} [opts]
   * @param {boolean} [opts.loop] repeat until stopped (the file player; never the sweep)
   * @param {string} [opts.outputChannels] accepted for contract parity and NOT honoured - both
   *        channels of the crossing carry the same sample (see the header)
   * @param {function(): void} [opts.onEnded] fired once, on natural (non-looped) end
   * @returns {Promise<void>}
   */
  async playBuffer(mono, { loop = false, outputChannels = 'BOTH', onEnded = null } = {}) {
    let position = 0;
    let ended = false;
    // A buffer lane has no DDS kernel to retune, so the control port stays closed for it.
    this.#generator = null;
    this.#goLive(() => {
      if (position < mono.length) {
        const sample = mono[position++];
        if (loop && position >= mono.length) position = 0;
        return sample;
      }
      if (!ended) {
        ended = true;
        if (onEnded) onEnded();
      }
      return 0.0;
    });
  }

  /**
   * Puts the lane on air against a fresh absolute schedule. There is nothing to pre-fill - Java
   * counts its readyLatch down before its render loop for the same reason - so "ready" is simply
   * "the lane started".
   *
   * @param {function():number} source where one mono sample comes from
   */
  #goLive(source) {
    if (this.#sampleRate <= 0) {
      throw new Error('Call open() before start()');
    }
    // The depth for THIS run, asked at the boundary and then held: a live change mid-lane would
    // otherwise split one block across two quantiser resolutions.
    this.#bitDepth = this.#depthOf();
    this.#scale = Math.pow(2, this.#bitDepth - 1) - 1;
    this.#source = source;
    this.#startMillis = performance.now();
    this.#produced = 0;
    // Block 0 is due AT the origin: the Java lane renders and offers, THEN sleeps out the
    // remainder of the period. Keeping that order is what makes the crossing carry a block
    // before the capture lane's first poll comes due one period later.
    this.#tick();
    this.#timer = setInterval(() => this.#tick(), this.#blockMillis);
    // A pending timer holds a Node test process open; browsers have no unref and ignore this.
    if (this.#timer && typeof this.#timer.unref === 'function') {
      this.#timer.unref();
    }
  }

  /**
   * Stops producing and drops the kernel - Java close(). The crossing is deliberately NOT
   * cleared here: only the capture lane clears it, at ITS open and close, so a block already in
   * flight still reaches a capture that is running.
   *
   * @returns {Promise<void>}
   */
  async close() {
    if (this.#timer != null) {
      clearInterval(this.#timer);
      this.#timer = null;
    }
    this.#source = null;
    this.#generator = null;
  }

  /**
   * Offers every block whose slot on the ABSOLUTE schedule (start + n * blockMillis) has come
   * due, so the lane cannot drift the way repeated relative delays would, and a tick the browser
   * delivered late produces the whole backlog rather than one block. Java gets the same catch-up
   * for free: a negative wait skips its sleep and the next block is rendered at once.
   */
  #tick() {
    const source = this.#source;
    if (source == null) {
      return;
    }
    const elapsed = performance.now() - this.#startMillis;
    while (this.#produced * this.#blockMillis <= elapsed) {
      // A block the crossing refuses is DROPPED, not retried: the capture lane is behind or
      // absent, and this lane runs on the clock exactly as a device would. The boolean is
      // ignored for that reason.
      this.#crossing.offer(this.#renderBlock(source));
      this.#produced++;
    }
  }

  /**
   * One block of quantised, re-normalised stereo.
   *
   * @param {function():number} source the mono sample source
   * @returns {import('./loopback-crossing.js').LoopbackBlock}
   */
  #renderBlock(source) {
    const frames = this.#blockFrames;
    // A FRESH pair per block: the capture lane may still be reading the previous one, so a
    // recycled buffer would tear the block already in flight.
    const l = new Float64Array(frames);
    const r = new Float64Array(frames);
    const depth = this.#bitDepth;
    const scale = this.#scale;
    const rng = this.#rng;
    for (let f = 0; f < frames; f++) {
      // ONE dithered sample per FRAME, written to both channels. The Java quantiser draws its
      // noise once and writes the same integer to both lanes (its gate is BOTH and its scales
      // are 1.0), so the two channels are identical by construction. A second draw for the
      // right channel would make the channels independently noisy - a different bench.
      const sample = quantizePcm(source(), depth, depth, rng) / scale;
      l[f] = sample;
      r[f] = sample;
    }
    return { l, r, n: frames };
  }

  /**
   * The live-control protocol of #port - the same message vocabulary
   * audio/worklets/dds-processor.js applies to its kernel (that module only loads inside an
   * AudioWorkletGlobalScope, so importing it here is not possible). Each field maps to one
   * kernel call; absent fields are left unchanged.
   *
   * The DAC-side tunables of the message (ditherBits, outputChannels, rightLaneScale) have no
   * effect on purpose - see the header: this lane's dither depth is the selected bit depth, and
   * its two channels are one crossing rather than two DAC lanes.
   *
   * @param {Object} d one control message
   */
  #applyControl(d) {
    const k = this.#generator;
    if (k == null) {
      return;
    }
    if (d.form != null) k.setForm(d.form);
    if (d.frequency != null) k.setFrequency(d.frequency);
    if (d.frequency2 != null) k.setDualToneFrequency2(d.frequency2);
    if (d.amplitudeVRms != null) k.setAmplitudeVrms(d.amplitudeVRms);
    if (d.dacFsVoltageAmpl != null) k.setDacFsVoltageAmpl(d.dacFsVoltageAmpl);
    if (d.rectDuty != null) k.setRectangleDuty(d.rectDuty);
    if (d.triDuty != null) k.setTriangleDuty(d.triDuty);
    if (d.dualAmp1Pct != null && d.dualAmp2Pct != null) {
      k.setDualToneAmplitudes(d.dualAmp1Pct, d.dualAmp2Pct);
    }
    if (d.linearSweep) {
      const s = d.linearSweep;
      k.configureLinearSweep(s.freqStart, s.freqEnd, s.periodSamples);
      k.setForm(GenSignalForm.LINEAR_SWEEP);
    }
    if (d.logSweep) {
      const s = d.logSweep;
      k.configureLogSweep(s.f0, s.f1, s.sweepSamples, s.leadInSamples);
      k.setForm(GenSignalForm.LOG_SWEEP);
    }
    if (d.sweepParams) {
      const s = d.sweepParams;
      k.setSweepParams(!!s.loop, s.fadeInSamples | 0, s.fadeOutSamples | 0);
    }
    if (d.resetSweepPosition) k.resetSweepPosition();
    if (d.compensation) k.applyCompensation(d.compensation);
    if (d.dualToneCompensation) k.applyDualToneCompensation(d.dualToneCompensation);
    if (d.dpdText != null) {
      if (isDualToneCorrectionFile(d.dpdText)) {
        k.applyDualToneCompensation(loadIntermod(d.dpdText));
      } else {
        const freq = d.dpdFrequency != null ? d.dpdFrequency : (d.frequency != null ? d.frequency : 1000);
        k.applyCompensation(loadHarmonics(d.dpdText, freq));
      }
    }
    if (d.clearCompensation) k.clearCompensation();
  }
}
