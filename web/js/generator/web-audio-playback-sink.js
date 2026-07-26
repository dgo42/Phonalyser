/*
 * Phonalyser web — the Web Audio playback sink (the DAC lane behind the generator seam).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * The Web Audio implementation of the PlaybackSink contract declared in generator-controller.js —
 * the web's AudioPlayback (org.edgo.audio.measure.sound.AudioPlayback), the twin of
 * qa40x/qa40x-playback-sink.js. Extracted VERBATIM out of GeneratorController so the controller
 * keeps only the lifecycle (Java: GeneratorController owns the lifecycle and asks
 * AudioBackend.openPlayback for the line): the output AudioContext, the dds-processor worklet
 * node, the bounded open-retry, the hidden-resampling probe and the device-loss reporting all
 * live here, exactly as JavaSoundGenerator / WdmksGenerator own their line in the desktop.
 *
 * Sits in js/generator/ (not js/audio/) because the DDS worklet + kernel it drives live here and
 * the QA40x twin is one module away; nothing about it is shared with the capture path.
 */
import { debug } from '../util/debug.js';

/** Bounded output-device-open retry — faithful to GeneratorController's MAX_ATTEMPTS /
 *  RETRY_PAUSE_MS: a measurement takeover stops the other modules then opens the DAC itself,
 *  but a just-stopped output context releases the OS device tens of ms AFTER its close()
 *  resolved, so the first open can lose the race and reject NotReadableError / AbortError even
 *  though no OTHER app holds it (a self-contention). Retry with a short pause before reporting. */
const OPEN_MAX_ATTEMPTS = 3;
const OPEN_RETRY_PAUSE_MS = 250;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * Opens an output AudioContext + applies the selected sink, retrying the open on a
 * NotReadableError/AbortError contention (a self-contention while a just-stopped context is
 * still releasing the OS DAC). Throws the last error only after the attempts are exhausted
 * (a genuine external hold). Shared by this sink and the controller's file / sweep lane
 * (openSweepContext / playSweepBuffer), which is Web-Audio-only by design.
 *
 * @param {AudioContextOptions} options constructor options (sampleRate + latencyHint)
 * @param {?string} sinkId output device id for setSinkId, if any
 * @param {(text: string) => void} status status line for the retry notice
 * @returns {Promise<AudioContext>} the opened, resumed context
 */
export async function openOutputContext(options, sinkId, status) {
  for (let attempt = 1; ; attempt++) {
    let ctx = null;
    try {
      ctx = new AudioContext(options);
      if (ctx.setSinkId && sinkId) {
        try { await ctx.setSinkId(sinkId); } catch (e) { console.warn('setSinkId', e); }
      }
      // Force the device to actually engage so a busy DAC surfaces its NotReadable/Abort HERE
      // (inside the retry) rather than asynchronously after we've reported success.
      if (ctx.state === 'suspended') await ctx.resume();
      return ctx;
    } catch (e) {
      try { if (ctx) await ctx.close(); } catch (_) { /* ignore */ }
      const retriable = e.name === 'NotReadableError' || e.name === 'AbortError';
      if (!retriable || attempt >= OPEN_MAX_ATTEMPTS) throw e;
      status(`output device busy (attempt ${attempt}/${OPEN_MAX_ATTEMPTS}) — retrying…`);
      await sleep(OPEN_RETRY_PAUSE_MS);
    }
  }
}

export class WebAudioPlaybackSink {

  /** @type {(text: string) => void} */
  #status;
  /** @type {(detail: string) => void} */
  #onDeviceError;
  /** @type {?AudioContext} the output (DAC) context — the device line itself. */
  #ctx = null;
  /** @type {?AudioWorkletNode} the dds-processor node — the generator. */
  #node = null;
  /** True only while WE are closing, so the context's own 'closed' statechange during close()
   *  is not misread as an unexpected output-device failure. */
  #closing = false;

  /**
   * @param {Object} deps
   * @param {(text: string) => void} [deps.status] status line (open retry, granted-rate notices)
   * @param {(detail: string) => void} [deps.onDeviceError] an UNEXPECTED device loss — the async
   *        'AudioContext encountered an error from the audio device' or a drop to
   *        interrupted/closed. A real event, so it is a callback; the controller turns it into
   *        AUDIO_DEVICE_ERROR.
   */
  constructor({ status, onDeviceError } = {}) {
    this.#status = status || (() => {});
    this.#onDeviceError = onDeviceError || (() => {});
  }

  /** The rate the browser actually granted (0 while closed). */
  get sampleRate() { return this.#ctx != null ? this.#ctx.sampleRate : 0; }

  /** Live-control channel — the worklet node's real MessagePort (null before start / after close). */
  get port() { return this.#node != null ? this.#node.port : null; }

  /**
   * Opens the DAC line at the requested rate and reports the rate actually granted. Java's
   * AudioBackend.openPlayback + AudioPlayback.open(): the line only, no signal yet.
   *
   * @param {Object} spec
   * @param {number} spec.sampleRate requested output rate (Hz)
   * @param {?string} [spec.deviceId] output device id (empty / 'default' = the default sink)
   * @returns {Promise<number>} the granted output sample rate
   */
  async open(spec) {
    // Probe the OS/default-device preferred rate BEFORE opening the real context. A context
    // created WITHOUT an explicit sampleRate reports the platform's native output rate, whereas
    // one created WITH `sampleRate: c.outRate` is granted that rate exactly (or the constructor
    // throws) and then the browser SILENTLY resamples the rendered stream down to whatever the
    // Windows shared-mode mix rate of the device is — an invisible stage the native Java
    // generator has no equivalent of. Comparing the two rates lets us surface that hidden
    // resampling. Cheap: opened and closed immediately, never wired to anything.
    let probeRate = 0;
    try {
      const probe = new AudioContext({ latencyHint: 'playback' });
      probeRate = probe.sampleRate;
      await probe.close();
    } catch (_) { probeRate = 0; /* rate unknown — continue silently */ }
    // OUTPUT context (DAC) — generator at the DAC's native rate. Opened through the
    // bounded-retry helper so a NotReadable/Abort contention (a just-stopped context still
    // releasing the OS DAC) is retried before it surfaces to the user.
    this.#closing = false;
    this.#ctx = await openOutputContext({ sampleRate: spec.sampleRate, latencyHint: 'playback' },
      spec.deviceId, this.#status);
    // Surface an unexpected output-device loss: the async 'AudioContext encountered an error from
    // the audio device' fires onerror, and losing the device exclusively drops the context to
    // 'interrupted'/'closed'. Only alert when it wasn't OUR close().
    this.#ctx.onerror = () => { if (!this.#closing) this.#onDeviceError('AudioContext error'); };
    this.#ctx.addEventListener('statechange', () => {
      if (this.#closing || !this.#ctx) return;
      const st = this.#ctx.state;
      if (st === 'interrupted' || st === 'closed') this.#onDeviceError('AudioContext state=' + st);
    });
    // Surface the hidden browser+Windows resampling. The probe rate describes the DEFAULT output
    // device only, so a hard "device runs at X Hz" claim is honest ONLY when we're on the default
    // sink. With a specific sink selected (deviceId set, non-'default'), the probe may not
    // describe THAT device — so we drop to a softer debug-only "cannot verify" hint rather than
    // risk asserting a wrong rate on the status line.
    const ctxRate = this.#ctx.sampleRate;
    const defaultSink = !spec.deviceId || spec.deviceId === 'default';
    if (probeRate > 0 && probeRate !== ctxRate && defaultSink) {
      const msg = `WARNING: output device runs at ${probeRate} Hz — the browser silently resamples ${ctxRate} Hz to it; set the Windows output device format to ${ctxRate} Hz for a clean signal`;
      this.#status(msg);
      debug(`[generator] ${msg}`);
    } else if (probeRate > 0 && probeRate !== ctxRate) {
      debug(`[generator] default device runs at ${probeRate} Hz but the selected sink's rate cannot be verified from a rate-unspecified probe; if it isn't ${ctxRate} Hz the browser silently resamples ${ctxRate} Hz to it — set the Windows output device format to ${ctxRate} Hz for a clean signal`);
    } else if (probeRate === 0) {
      debug(`[generator] could not probe the output device rate; if it isn't ${ctxRate} Hz the browser silently resamples ${ctxRate} Hz to it — set the Windows output device format to ${ctxRate} Hz for a clean signal`);
    }
    return ctxRate;
  }

  /**
   * Builds the dds-processor node from the fully-configured generator description and puts it on
   * air. Java's AudioPlayback.play(generator, …): the SignalGenerator arrives already carrying
   * its duty / dual-tone / sweep / compensation settings, so nothing renders at a default value
   * — here `spec.control` is applied to the worklet BEFORE it is connected, for the same reason.
   * Resolves once the graph is running (Java's readyLatch).
   *
   * @param {Object} spec the generator description; see the PlaybackSink typedef
   * @returns {Promise<void>}
   */
  async start(spec) {
    await this.#ctx.audioWorklet.addModule(new URL('../audio/worklets/dds-processor.js', import.meta.url));
    this.#node = new AudioWorkletNode(this.#ctx, 'dds-processor', {
      // Stereo out: the worklet writes each lane explicitly to honour the output-lane
      // gate (Java's interleave seam, PcmQuantizer). A mono [1] lane up-mixed by the
      // destination could not carry per-lane values (left ≠ right for a gated / scaled
      // lane), so this is the required web-seam adaptation of Java's PcmQuantizer point.
      outputChannelCount: [2],
      processorOptions: {
        form: spec.form, frequency: spec.frequency, sampleRate: this.#ctx.sampleRate,
        amplitudeVRms: spec.amplitudeVRms, dacFsVoltageAmpl: spec.dacFsVoltageAmpl,
        // TPDF dither depth applied LIVE in the worklet (Java PcmQuantizer): added to the mono
        // sample before the per-lane scale, so it shows on the FFT floor where the dBV view sets
        // it. 0 = Off.
        ditherBits: spec.ditherBits,
        // Output routing (Java GeneratorController.pushOutputRoutingToPlayback): the lane
        // gate + right-lane scale (= fsLeft/fsRight). Left keeps the mono amplitude (scale 1.0).
        outputChannels: spec.outputChannels,
        rightLaneScale: spec.rightLaneScale,
      },
    });
    // The remaining live parameters (duty, dual-tone tone2/split, sweep config, predistortion)
    // — absent processorOptions fields the kernel already defaulted. Posted before connect() so
    // the very first rendered block already carries them.
    if (spec.control) this.#node.port.postMessage(spec.control);
    this.#node.connect(this.#ctx.destination);
    if (this.#ctx.state === 'suspended') await this.#ctx.resume();
  }

  /**
   * Stops the tone and tears the output graph down (disconnect → suspend → close — closing a
   * context with a live worklet wired can crash the renderer). Idempotent.
   * @returns {Promise<void>}
   */
  async close() {
    this.#closing = true;   // suppress the statechange our own close() will fire
    try { if (this.#node) this.#node.disconnect(); } catch (_) {}
    this.#node = null;
    try { if (this.#ctx) { await this.#ctx.suspend().catch(() => {}); await this.#ctx.close(); } } catch (_) {}
    this.#ctx = null;
  }
}
