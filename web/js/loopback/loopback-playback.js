/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.sound.loopback.LoopbackPlayback - the playback lane of
// the digital loopback, as the web's PlaybackSink (the contract declared in
// generator/generator-controller.js). A THIN client that attaches and detaches the generator lane
// on the manager's one duplex session (loopback-duplex-engine.js): there is no hardware behind
// this lane, and the capture lane on that same session is the only thing that ever reads what it
// writes.
//
// THIS LANE RENDERS NOTHING ON ITS OWN CLOCK. The session pulls a block from nextBlock() when the
// block is due, so all the lane owns is the quantiser and where one mono sample comes from - the
// same shape the analyzer's lane has.
//
// DITHER IS THIS BACKEND'S POINT, and the lane is NEVER undithered: by DEFAULT it quantises
// with TPDF on the last bit of the SELECTED depth, which is what makes this backend a bench
// with a KNOWN minimal noise floor (3.0103 - 6.0206 * N dBFS broadband) - a loop that
// introduced no noise at all would hand the FFT the correlated quantisation artifacts of a
// bare rounding. A dither the generator CONFIGURES - the start spec's value and the live
// change that rides every retune - overrides the default, exactly as it would on a hardware
// lane; a zero (dither off) falls back to the last-bit default rather than to silence. The
// session's UNATTACHED generator lane applies the same default to a zero-amplitude source, so the
// floor is continuous across a start and a stop.
//
// THE LANE GATE AND THE PER-LANE SCALE ARE NOT HONOURED EITHER, for the same structural reason:
// Java's loopback lane never calls setOutputChannels / setChannelScale, so its quantiser keeps
// the BOTH gate and the 1.0 scales and both channels carry the identical quantised sample. There
// is no DAC here whose two lanes could differ.
//
// WEB DIVERGENCES, all forced by the platform rather than chosen:
//   - FLOAT BLOCKS, NOT BYTES. Java writes interleaved little-endian PCM bytes and its capture
//     side decodes them; the web capture path carries normalised floats and has no byte stride,
//     so the integer sample is divided back by the quantiser's own scale into a Float64 pair.
//     The staging is Float64 rather than the Float32Array of the batch typedef ON PURPOSE: a
//     32-bit quantiser's step is 2 pow -31, which Float32 (24-bit mantissa) cannot represent, so
//     Float32 staging would mask the very floor this backend exists to expose. Both consumers of
//     a batch take it: audio/signal-buffer.js appendBatch copies through subarray/set into its
//     Float64 ring, and audio/backend.js copies with Float64Array.from.
//   - NO BOUNDED PLAY, BUT A BUFFER LANE. Java has a second play() overload that renders a fixed
//     number of seconds; nothing on the web asks a sink for that, so the lane runs until close().
//     What the web does ask for is playBuffer: the sweep and the file player hand over a finished
//     buffer instead of a DDS description, and a sink without that entry point is silently skipped
//     by the controller, which then opens a Web Audio context - so the sound would leave through
//     the speakers while this backend captured its own silence. Both paths therefore share one
//     quantiser and one attach here.
//   - THE SESSION IS ACQUIRED WHEN THE LANE GOES LIVE, not at open(). Java's openPlayback carries
//     the depth from the moment the lane is constructed; the web reads the SELECTED depth at the
//     session boundary, and going live is that boundary - which is also where the whole session
//     moves to this lane's format.

import {
  DdsKernel,
  GenSignalForm,
  isDualToneCorrectionFile,
  loadHarmonics,
  loadIntermod,
  quantizePcm,
} from '../generator/dds-kernel.js';

export class LoopbackPlayback {

  /** @type {import('./loopback-device-manager.js').LoopbackDeviceManager} */
  #manager;
  /** @type {function():number} the SELECTED output bit depth, asked when the lane goes live. */
  #depthOf;
  /** @type {?import('./loopback-duplex-engine.js').LoopbackDuplexEngine} */
  #engine = null;
  /** That depth for the running lane - the quantiser resolution AND the default dither depth. */
  #bitDepth = 0;
  /** The dither depth the generator asked for, raw: 0 or less means none configured, and the
   *  render falls back to the last-bit default - the lane is never undithered. */
  #ditherRequested = 0;
  /** @type {function():number} uniform [0,1) source for the dither - Java's SplittableRandom. */
  #rng;
  /** The quantiser's full-scale integer, (2 pow (N-1)) - 1 and NOT 2 pow (N-1): the same value
   *  the encoder scales by, so dividing by it returns the sample to -1...+1 exactly. */
  #scale = 0;
  /** Session rate, from open(); 0 while closed. */
  #sampleRate = 0;
  /** @type {?DdsKernel} the mono DDS; null before start() and for a pre-rendered buffer lane,
   *  which has no kernel to retune. */
  #generator = null;
  /** @type {?function():number} where one mono sample comes from while the lane is live - the
   *  kernel, or a reader over a pre-rendered buffer. Null = the lane is not producing. */
  #source = null;
  /** Whether this lane is attached to the session - so a second close() cannot detach a lane it
   *  does not hold. */
  #attached = false;

  /**
   * Live-control channel, MessagePort-shaped so every sink speaks ONE protocol (the Web Audio
   * twin hands out its worklet node's real MessagePort; here the message is applied
   * synchronously to the in-process kernel). Built once - a getter returning a fresh object
   * would allocate on every FLL trim.
   */
  #port = { postMessage: (msg) => this.#applyControl(msg || {}) };

  /**
   * @param {import('./loopback-device-manager.js').LoopbackDeviceManager} manager the manager
   *        whose one duplex session both lanes attach to
   * @param {function():number} depthOf the selected output bit depth (16 / 20 / 24 / 32) - the
   *        quantiser resolution and the dither depth in one. A SUPPLIER, read when the lane goes
   *        live rather than frozen at construction, so the depth a session encodes at is the one
   *        the operator has selected at that moment.
   * @param {Object} [deps]
   * @param {function():number} [deps.rng=Math.random] uniform [0,1) source for the TPDF dither
   *        (Java's per-instance SplittableRandom); injected so a test can pin the noise
   * @param {number} [deps.ditherBits=0] the configured dither at open - the twin of Java
   *        openPlayback's argument. The start spec overwrites it; 0 means the last-bit default.
   */
  constructor(manager, depthOf, { rng = Math.random, ditherBits = 0 } = {}) {
    if (manager == null) {
      throw new Error('manager');
    }
    if (typeof depthOf !== 'function') {
      throw new Error('depthOf');
    }
    this.#manager = manager;
    this.#depthOf = depthOf;
    this.#rng = rng;
    this.#ditherRequested = ditherBits;
  }

  /** The session rate - exactly the requested one: a software lane has no clock to negotiate
   *  against. 0 while closed. */
  get sampleRate() { return this.#sampleRate; }

  /** Live-control channel; non-null while the lane is producing - a PRE-RENDERED buffer lane
   *  included. It has no DDS kernel to retune, but it has a quantiser, and the dither that rides
   *  every retune must reach it: Java pushes setDitherBits to the file line exactly as it pushes
   *  it to the tone line. The kernel-only messages are ignored for it (see #applyControl). */
  get port() { return this.#source != null ? this.#port : null; }

  /**
   * Takes the session rate - Java LoopbackPlayback's constructor plus open(). Nothing is produced
   * yet and the session is not touched: the lane joins it when it goes live.
   *
   * @param {Object} spec
   * @param {number} spec.sampleRate the session rate (Hz)
   * @returns {Promise<number>} the granted rate - the requested one
   */
  async open(spec) {
    this.#sampleRate = spec.sampleRate;
    // The configured dither, when the caller states one at the open - the file player does,
    // because a file reaching a lane is scaled and re-quantised like any other signal. A zero or
    // an absent value leaves the last-bit default in force, which is this backend's floor.
    if (spec.ditherBits != null) this.#ditherRequested = spec.ditherBits;
    return this.#sampleRate;
  }

  /**
   * Java play(generator, stopFlag, readyLatch): builds the mono DDS from the fully-configured
   * description and puts the lane on air. Built HERE rather than handed in, because the Web
   * Audio twin can only build its kernel inside the worklet and the controller therefore passes
   * options rather than an instance to every sink.
   *
   * There is nothing to pre-fill - Java counts its readyLatch down before its render loop for
   * the same reason - so "ready" is simply "the lane attached".
   *
   * @param {Object} spec the generator description; see the PlaybackSink typedef. Its ditherBits
   *        field overrides the last-bit default when set (see the header).
   * @returns {Promise<void>}
   */
  async start(spec) {
    this.#ditherRequested = spec.ditherBits != null ? spec.ditherBits : 0;
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
   * Plays ONE pre-rendered buffer through the session - the FreqResp sweep and the file player,
   * which hand a finished buffer rather than a DDS description.
   *
   * NO RESAMPLING, and that is the point for the sweep: the buffer is authored at the session
   * rate and the session runs at that same rate, so what is played is sample-for-sample what the
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
   *        channels of the session carry the same sample (see the header)
   * @param {function(): void} [opts.onEnded] fired once, on natural (non-looped) end
   * @returns {Promise<void>}
   */
  async playBuffer(mono, { loop = false, outputChannels = 'BOTH', onEnded = null } = {}) {
    let position = 0;
    let ended = false;
    // A buffer lane has no DDS kernel to retune, so the control port stays closed for it. The
    // dither stated at the open STANDS - it is the configured generator dither, and a file is
    // scaled and re-quantised on its way out like anything else; with none stated the last-bit
    // default is what renders, so this lane is never undithered either way.
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
   * The session's generator-lane source, on its clock: quantises one block's worth of the mono
   * source into the block's two channels.
   *
   * @param {import('./loopback-duplex-engine.js').LoopbackBlock} block the session's block
   */
  nextBlock(block) {
    const source = this.#source;
    const frames = block.n;
    const l = block.l;
    const r = block.r;
    if (source == null) {
      // Between the detach and the lane's own bookkeeping. Filled from the SESSION's silence,
      // never with digital zeros: this backend's floor is dither on the last bit of the selected
      // depth, and one undithered block reads as minus infinity where every neighbouring block
      // reads the known floor - a hole in the very thing the bench exists to show.
      this.#engine.silence.nextBlock(block);
      return;
    }
    const depth = this.#bitDepth;
    const scale = this.#scale;
    const rng = this.#rng;
    // A configured dither wins; none configured means the depth's own last bit.
    const dither = this.#ditherRequested > 0 ? this.#ditherRequested : depth;
    for (let f = 0; f < frames; f++) {
      // ONE dithered sample per FRAME, written to both channels. The Java quantiser draws its
      // noise once and writes the same integer to both lanes (its gate is BOTH and its scales
      // are 1.0), so the two channels are identical by construction. A second draw for the
      // right channel would make the channels independently noisy - a different bench.
      const sample = quantizePcm(source(), depth, dither, rng) / scale;
      l[f] = sample;
      r[f] = sample;
    }
  }

  /**
   * Puts the lane on air. The depth for THIS run is read at the boundary and then held - a live
   * change mid-lane would otherwise split one block across two quantiser resolutions - and the
   * WHOLE session moves to this lane's format, because the loop has one clock and one depth.
   *
   * @param {function():number} source where one mono sample comes from
   */
  #goLive(source) {
    if (this.#sampleRate <= 0) {
      throw new Error('Call open() before start()');
    }
    this.#bitDepth = this.#depthOf();
    this.#scale = Math.pow(2, this.#bitDepth - 1) - 1;
    this.#source = source;
    this.#engine = this.#manager.acquireEngine(this.#sampleRate, this.#bitDepth);
    this.#attached = true;
    // ITSELF, not a bound method: the session's lane source is this object, so a live swap while
    // it runs replaces the whole lane.
    this.#engine.attachGenerator(this);
  }

  /**
   * Detaches the lane and drops the kernel - Java close(). The session ends only when no other
   * lane holds it open, and the capture lane goes on receiving the dithered silence the
   * unattached generator lane emits.
   *
   * @returns {Promise<void>}
   */
  async close() {
    if (this.#attached) {
      this.#attached = false;
      this.#engine.detachGenerator();
    }
    this.#source = null;
    this.#generator = null;
  }

  /**
   * The live-control protocol of #port - the same message vocabulary
   * audio/worklets/dds-processor.js applies to its kernel (that module only loads inside an
   * AudioWorkletGlobalScope, so importing it here is not possible). Each field maps to one
   * kernel call; absent fields are left unchanged.
   *
   * ditherBits is honoured here - the live half of the dither rule in the header. The OTHER
   * DAC-side tunables (outputChannels, rightLaneScale) have no effect on purpose: this lane's
   * two channels are one session block rather than two DAC lanes.
   *
   * @param {Object} d one control message
   */
  #applyControl(d) {
    // The quantiser is the lane's own and exists with or without a kernel, so the dither applies
    // FIRST - a pre-rendered buffer lane is re-quantised on its way out like any other signal.
    if (d.ditherBits != null) this.#ditherRequested = d.ditherBits;
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
