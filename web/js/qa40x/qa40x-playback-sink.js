/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.sound.qa40x.Qa40xGenerator.
//
// QA402/QA403 stereo playback - a thin PlaybackSink (the web's AudioPlayback, declared in
// generator/generator-controller.js) that attaches / detaches the generator lane on the
// manager's one duplex engine (doc §10). This module IS the engine's generator sample source:
// for each frame it pulls one sample from the mono DDS, adds TPDF dither, applies the per-lane
// full-scale scale and the Left/Right/Both gate, and scales to int32.
//
// AMPLITUDE CONVENTION (doc §6, qa40x-levels.js)
// The DDS already emits samples normalised to [-1, +1] PEAK-relative to the DAC full scale it
// was given (its dacFsVoltageAmpl is the range's peak full-scale voltage, set from the device
// card). So the wire sample is just round(sample · MAXINT) - the PEAK convention, with the range
// dBV and the on-device cal factor already folded into the card's full-scale voltage. There is
// deliberately NO extra √2/RMS factor here (that would be ~3 dB hot). The engine does the L/R
// swap and little-endian packing (doc §5).
//
// LIVE TUNABLES
// setChannelScale / setOutputChannels / setDitherBits are honoured live inside the sample
// source. TPDF dither is added to the mono sample before the per-lane scale, mirroring the
// byte-PCM backends' encoder (Java PcmQuantizer, web dds-kernel.tpdfNoise). Its ±1 LSB amplitude
// is set by the SELECTED target bit depth, not the 32-bit wire container - so 8-bit dither
// raises the floor to ~−42 dBFS, while 24-bit dither lands in the DAC's dropped low byte.
// 0 = off.
//
// NO AudioContext, NO worklet. This sink drives the DDS kernel DIRECTLY from the engine's fill
// callback - which is precisely why dds-kernel.js was kept separate from
// audio/worklets/dds-processor.js. The oscillator maths, the TPDF noise and the lane gate are
// reused from there, not reimplemented. The one thing that cannot be shared is the
// message -> kernel dispatch in dds-processor._onMessage: that module only loads inside an
// AudioWorkletGlobalScope (importing it would run registerProcessor), so #applyControl below is
// the same live-control protocol applied to an in-process kernel.
//
// THREADING. Java confines its SplittableRandom to the USB event thread and marks the tunables
// volatile because the sample source runs on that thread; JS has one thread, so the fill
// callback can never interleave with a setter and those notes have no web counterpart.

import { debug } from '../util/debug.js';
import {
  DdsKernel,
  GenSignalForm,
  isDualToneCorrectionFile,
  loadHarmonics,
  loadIntermod,
  outputLaneGate,
  tpdfNoise,
} from '../generator/dds-kernel.js';
import { CHANNELS } from './qa40x-duplex-engine.js';
import { MAXINT } from './qa40x-levels.js';

/** Clamp to [-1, 1] - Java Qa40xGenerator.clamp (and PcmQuantizer.clamp). */
function clamp(v) {
  return v > 1.0 ? 1.0 : (v < -1.0 ? -1.0 : v);
}

/**
 * What this sink needs of its device manager (implemented by Qa40xDeviceManager): the one
 * always-duplex engine for the shared rate. Injected, never constructed here.
 *
 * @typedef {Object} Qa40xEngineSource
 * @property {(sampleRateHz: number) => (Promise<Object>|Object)} acquireEngine the single duplex
 *           engine, built on first use and re-clocked when the shared rate moved (one reg-9 clock)
 */

export class Qa40xPlaybackSink {

  /** @type {Qa40xEngineSource} */
  #manager;
  /** @type {function():number} uniform [0,1) source for the dither - Java's SplittableRandom field. */
  #rng;
  /** @type {?Object} the manager's Qa40xDuplexEngine. */
  #engine = null;
  /** Session rate, from open() - the DDS runs on it and the engine is acquired for it. */
  #sampleRate = 0;
  /** @type {?DdsKernel} Java's `currentGenerator`. */
  #generator = null;
  #scaleL = 1.0;
  #scaleR = 1.0;
  /** Output-lane gate, honoured live in the sample source (default BOTH). */
  #outputChannels = 'BOTH';
  /** TPDF dither depth in bits (may be fractional); 0 = off. Honoured live in the sample source. */
  #ditherBits = 0;
  #attached = false;

  /**
   * Live-control channel, MessagePort-shaped so both sinks speak ONE protocol (the Web Audio
   * twin hands out its worklet node's real MessagePort and the message crosses to the render
   * thread; here the same message is applied synchronously to the in-process kernel). Built once
   * - a getter returning a fresh object would allocate on every FLL trim.
   */
  #port = { postMessage: (msg) => this.#applyControl(msg || {}) };

  /**
   * @param {Qa40xEngineSource} manager the device manager owning the duplex engine
   * @param {Object} [deps]
   * @param {function():number} [deps.rng=Math.random] uniform [0,1) source for the TPDF dither
   *        (Java's per-instance SplittableRandom); injected so a test can pin the noise
   */
  constructor(manager, { rng = Math.random } = {}) {
    if (manager == null) {
      throw new Error('manager');
    }
    this.#manager = manager;
    this.#rng = rng;
  }

  /** The session rate - the analyzer grants exactly what was asked (one reg-9 clock, §10). */
  get sampleRate() { return this.#sampleRate; }

  /** Live-control channel; non-null once the lane has been started. */
  get port() { return this.#generator != null ? this.#port : null; }

  /**
   * Acquires the manager's duplex engine for this rate - Java Qa40xGenerator.open(). No lane is
   * attached yet, so nothing streams and no register is written.
   *
   * @param {Object} spec
   * @param {number} spec.sampleRate the session rate (Hz); input and output are locked equal
   * @returns {Promise<number>} the granted rate - the requested one: reg 9 is a single clock, so
   *          the app's rates are constrained equal for this backend rather than negotiated here
   */
  async open(spec) {
    this.#sampleRate = spec.sampleRate;
    // The DAC-side dither is carried by the OPEN as well as by start(): a buffer lane
    // (file play, a pre-rendered sweep) never goes through start(), and it exposes no live
    // control port either - so a dither the open dropped could not reach it afterwards by any
    // route at all, and the lane would quantise undithered for its whole life.
    this.setDitherBits(spec.ditherBits);
    this.#engine = await this.#manager.acquireEngine(this.#sampleRate);
    debug(`[qa40x] generator opened : ${this.#sampleRate} Hz / 32 bit`);
    return this.#sampleRate;
  }

  /**
   * Java Qa40xGenerator.play(generator, stopFlag, readyLatch) -> startLane: builds the mono DDS
   * from the fully-configured description, then attaches it as the engine's generator lane
   * (a live source swap if the stream is already up). The engine primes the output past the
   * 1024-frame start threshold synchronously inside attachGenerator, so "ready" is simply "the
   * lane started" - there is no readyLatch to await.
   *
   * @param {Object} spec the generator description; see the PlaybackSink typedef
   * @returns {Promise<void>} settles once the lane is attached and primed
   */
  async start(spec) {
    if (this.#engine == null) {
      this.#engine = await this.#manager.acquireEngine(this.#sampleRate);
    }
    // The DAC-side tunables first: the fill callback may run as soon as the lane is attached.
    this.setDitherBits(spec.ditherBits);
    this.setOutputChannels(spec.outputChannels);
    // Java pushOutputRoutingToPlayback: setChannelScale(1.0, dacRightLaneScale()) - the left lane
    // IS the mono amplitude reference, the right lane carries fsLeft/fsRight.
    this.setChannelScale(1.0, spec.rightLaneScale != null ? spec.rightLaneScale : 1.0);
    // The mono DDS itself - Java's SignalGenerator, built by the controller and handed to play().
    // Built HERE instead, because the Web Audio twin can only build its kernel inside the worklet
    // and the controller therefore passes options rather than an instance to both sinks.
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
    // play() an already-configured SignalGenerator - nothing renders at a default value.
    if (spec.control) this.#applyControl(spec.control);
    // Warmup consumed nextSample() advances sweep state; rewind to sample 0 so a freq-response
    // sweep aligns with the deconvolution reference (audio-backends memory).
    generator.resetSweepPosition();
    this.#attached = true;
    await this.#engine.attachGenerator(this.#nextFrames);
  }

  /**
   * Live-updates the per-lane full-scale scale factors - the ratio that lets a LINKED stereo
   * card with distinct DAC full-scales emit the same physical level on both lanes.
   * @param {number} left
   * @param {number} right
   */
  setChannelScale(left, right) {
    this.#scaleL = left;
    this.#scaleR = right;
  }

  /**
   * Live-updates the TPDF dither resolution (bits, may be fractional; 0 = off).
   * @param {number} bits
   */
  setDitherBits(bits) {
    this.#ditherBits = Math.max(0.0, bits != null ? bits : 0.0);
  }

  /**
   * Live-updates the output-lane gate.
   * @param {string} channels 'BOTH' | 'LEFT' | 'RIGHT'
   */
  setOutputChannels(channels) {
    if (channels != null) this.#outputChannels = channels;
  }

  /**
   * Detaches the generator lane - Java close() -> stopLane. The engine stops (and parks the
   * analyzer) only if this was its last client. Idempotent. The engine itself is the manager's
   * and is deliberately NOT released: a re-open reacquires the same session.
   * @returns {Promise<void>}
   */
  async close() {
    if (this.#attached) {
      this.#attached = false;
      await this.#engine.detachGenerator();
      this.#generator = null;
    }
    this.#engine = null;
    // open() acquired the engine, which OPENS the analyzer, but #attached is set only AFTER the DDS
    // kernel is built and the controls applied - both of which can throw (a malformed .dpd does
    // it). The close() above then has no lane to detach, stopGenerator swallows the original error,
    // and the device stays claimed for the life of the page. A no-op while a session is live.
    await this.#manager.releaseIfIdle();
  }

  /**
   * Engine generator-lane source (Java's nextFrames, handed over as `this::nextFrames`): fills
   * `destination` with `frames` interleaved LOGICAL L,R int32 samples ([2i] = left, [2i+1] =
   * right). An arrow field so the identity is stable and `this` survives the engine's call.
   *
   * @type {(destination: Int32Array, frames: number) => void}
   */
  #nextFrames = (destination, frames) => {
    const gen = this.#generator;
    const sl = this.#scaleL;
    const sr = this.#scaleR;
    // Gate hoisted once per call, like the Java reads `outputChannels` once before its loop.
    const { wantL, wantR } = outputLaneGate(this.#outputChannels);
    for (let f = 0; f < frames; f++) {
      // tpdfNoise takes the depth BY VALUE, so the two uniform draws of one noise value always
      // share one depth - a live setDitherBits() mid-fill can never shift the noise by (0 − 1),
      // which is exactly what Java's `double bits = ditherBits;` single read buys.
      const sample = gen != null ? clamp(gen.nextSample() + tpdfNoise(this.#ditherBits, this.#rng)) : 0.0;
      destination[CHANNELS * f] = wantL ? this.#toInt32(sample * sl) : 0;
      destination[CHANNELS * f + 1] = wantR ? this.#toInt32(sample * sr) : 0;
    }
  };

  /**
   * Plays ONE pre-rendered buffer through the analyzer's generator lane - the FreqResp sweep and
   * the "Load from..." file player.
   *
   * Why this exists: both of those paths used to build their own AudioContext, so on the QA40x the
   * audio went to whatever Web Audio device was selected and NEVER to the analyzer - a sweep
   * measured nothing. Here they reach the same DAC every other QA40x sound does.
   *
   * NO RESAMPLING, and that is the point for the sweep: the analyzer's ADC and DAC share one
   * reg-9 clock, so a buffer authored at the session rate is played sample-for-sample. The Web
   * Audio sink has to resample (its output context may be granted a different rate than the
   * capture) and carries an elaborate rate-tagging contract to keep played == reference; here the
   * two are identical by construction, which is exactly what the deconvolution needs.
   *
   * The lane gate is honoured (a sweep is GATE-only - an un-driven lane carries digital silence),
   * and the samples are NOT scaled: calibration enters the deconvolution maths, and scaling the
   * played lane against an unscaled reference would inject a gain error into H.
   *
   * @param {Float32Array|Float64Array} mono the pre-rendered samples, at the session rate
   * @param {Object} [opts]
   * @param {boolean} [opts.loop] repeat until stopped (the file player; never the sweep)
   * @param {string} [opts.outputChannels] 'BOTH' | 'LEFT' | 'RIGHT'
   * @param {function(): void} [opts.onEnded] fired once, on natural (non-looped) end
   * @returns {Promise<void>} settles once the lane is attached and primed
   */
  async playBuffer(mono, { loop = false, outputChannels = 'BOTH', onEnded = null } = {}) {
    if (this.#engine == null) {
      this.#engine = await this.#manager.acquireEngine(this.#sampleRate);
    }
    this.#outputChannels = outputChannels;
    let position = 0;
    let ended = false;
    const source = (destination, frames) => {
      const { wantL, wantR } = outputLaneGate(this.#outputChannels);
      for (let f = 0; f < frames; f++) {
        let sample = 0.0;
        if (position < mono.length) {
          sample = mono[position++];
          if (loop && position >= mono.length) position = 0;
        } else if (!ended) {
          // Past the end of a non-looping buffer: the lane keeps feeding silence (the engine is
          // always-duplex and must never starve), and the caller is told exactly once.
          ended = true;
          if (onEnded) onEnded();
        }
        // Dithered like the DDS lane above, and for the same reason: these samples are rounded
        // onto the DAC's int32 grid here, and a rounding with nothing under it is what dither
        // exists to linearise. The depth comes from the open (0 = off), read once per frame so a
        // live change can never split one noise value across two depths.
        const v = this.#toInt32(clamp(sample + tpdfNoise(this.#ditherBits, this.#rng)));
        destination[CHANNELS * f] = wantL ? v : 0;
        destination[CHANNELS * f + 1] = wantR ? v : 0;
      }
    };
    this.#generator = null;      // a buffer lane has no DDS kernel to retune
    this.#attached = true;
    await this.#engine.attachGenerator(source);
  }

  /** Peak-convention scale to int32: round(clamp(v) · MAXINT), saturated to ±MAXINT. */
  #toInt32(v) {
    const scaled = Math.round(clamp(v) * MAXINT);
    if (scaled > MAXINT) {
      return MAXINT;
    }
    if (scaled < -MAXINT) {
      return -MAXINT;
    }
    return scaled;
  }

  /**
   * The live-control protocol of #port - the same message vocabulary
   * audio/worklets/dds-processor.js applies to its kernel (see the header: that module cannot be
   * imported outside an AudioWorklet). Each field maps to one setter; absent fields are left
   * unchanged. Compensation may arrive prebuilt or as a raw .dpd/CSV body.
   *
   * @param {Object} d one control message
   */
  #applyControl(d) {
    const k = this.#generator;
    if (k != null) {
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
    // The DAC-side tunables (Java's AudioPlayback setters) - valid with or without a generator.
    if (d.outputChannels != null) this.setOutputChannels(d.outputChannels);
    if (d.rightLaneScale != null) this.setChannelScale(1.0, d.rightLaneScale);
    if (d.ditherBits != null) this.setDitherBits(d.ditherBits);
  }
}
