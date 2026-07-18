/*
 * Phonalyser web — the DDS generator + file-player output path.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of gui/generator/GeneratorController. Owns the OUTPUT AudioContext + dds-processor
 * worklet (tone / sweep / dual-tone / compensated), the monitoring file-player lane, and the
 * analysis frequencies (`snapped`/`binW`/`fundBin`) that keep what is PLAYED, MEASURED and SHOWN
 * consistent. Has nothing to do with capture. The shared `config` object is held by reference, so
 * a live edit on either side is visible to both. The FFT-side frequency-lock loop (which OWNS the
 * FLL state) steers the tone by publishing GENERATOR_FREQ_TRIM; this controller subscribes and
 * applies the trim to its worklet (see _applyFllTrim).
 */
import { GenSignalForm, isDualTone } from '../generator/dds-kernel.js';
import { MessageBus } from '../bus/message-bus.js';
import { Events } from '../bus/events.js';
import { debug } from '../util/debug.js';

/** Converts a dBFS amplitude to the DDS kernel's V RMS scale. A full-scale sine has
 *  RMS = dacFsVoltageAmpl/√2, so ampDbfs dBFS → 10^(dBFS/20)·(dacFs/√2). */
function dbfsToVrms(dbFs, dacFsAmpl) {
  return Math.pow(10, dbFs / 20) * (dacFsAmpl / Math.SQRT2);
}

/** Generator amplitude in V RMS feeding the DDS kernel. The UI holds canonical Vrms
 *  (config.ampVrms); the legacy dBFS path stays as a fallback for any caller that still sets only
 *  config.ampDbfs. */
function ampVrmsOf(c) {
  return (c.ampVrms != null) ? c.ampVrms : dbfsToVrms(c.ampDbfs, c.dacFsVoltageAmpl);
}

/** Bounded output-device-open retry — faithful to GeneratorController's MAX_ATTEMPTS /
 *  RETRY_PAUSE_MS: a measurement takeover stops the other modules then opens the DAC itself,
 *  but a just-stopped output context releases the OS device tens of ms AFTER its close()
 *  resolved, so the first open can lose the race and reject NotReadableError / AbortError even
 *  though no OTHER app holds it (a self-contention). Retry with a short pause before reporting. */
const OPEN_MAX_ATTEMPTS = 3;
const OPEN_RETRY_PAUSE_MS = 250;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Opens an output AudioContext + applies the selected sink, retrying the open on a
 *  NotReadableError/AbortError contention (a self-contention while a just-stopped context is
 *  still releasing the OS DAC). Throws the last error only after the attempts are exhausted
 *  (a genuine external hold). Shared by startGenerator / openSweepContext / playSweepBuffer. */
async function openOutputContext(options, sinkId, status) {
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

export class GeneratorController {
  /**
   * @param config the SHARED engine config object (held by reference).
   * @param deps {status} — status: (text) => void.
   */
  constructor(config, { status } = {}) {
    this.config = config;
    this._status = status || (() => {});
    this._genOn = false;
    // Reason key of the last failed start (mirror Java getLastStartError) — null on success.
    this.lastStartError = null;
    // Analysis freqs — derived in computeAnalysisFreqs() from the input rate + form.
    this.binW = 0;
    this.snapped = 0;
    this.fundBin = 1;
    // Output (DAC) graph.
    this.outCtx = null;
    this.genNode = null;
    this.outSampleRate = 0;
    // True only while WE are stopping the generator, so the context's own 'closed' statechange
    // during stopGenerator() is not misread as an unexpected output-device failure.
    this._closing = false;
    // FLL trim state moved to FftController (which owns the frequency-lock loop); the generator
    // only APPLIES trims via the GENERATOR_FREQ_TRIM subscription below.
    // File-player lane (monitoring convenience, NOT the measurement path).
    this._fileSrc = null;
    this._fileCtx = null;
    // Output context opened by openSweepContext() but not yet handed a buffer — the
    // freqresp sweep opens it first to learn the granted output rate (so it can cap
    // the sweep band to the granted Nyquist), then plays into it via playSweepBuffer.
    this._pendingSweepCtx = null;
    this.onFileEnded = null;   // () => void — fires on natural (non-loop) end

    // The FreqResp sweep needs the DAC exclusively — stop both engines (Java
    // GeneratorController wireBusListeners: freqRespStarted → stopEngines()). The
    // pane-side visuals (Play LEDs, ON-AIR banner) ride the panes' OWN subscriptions.
    MessageBus.instance().subscribe(Events.FREQRESP_MEASUREMENT_STARTED,
      () => { this.stopEngines(); });
    // "Is the generator producing a signal — OR still holding an output device?" — polled by
    // the FreqResp sweep + Tune-notch while they wait for the DAC to go idle (Java
    // registerResponder GENERATOR_RUNNING → isProducingSignal). Reports true not only while a
    // tone/file is playing but also while ANY output context is still open, because
    // stopGenerator()/stopFile() flip their playing flags SYNCHRONOUSLY but the outCtx.close()
    // that actually releases the OS DAC resolves tens of ms later. Without this the idle-wait
    // returned as soon as the flags flipped and the takeover opened the DAC while the previous
    // context was still closing → NotReadableError (a self-contention).
    MessageBus.instance().registerResponder(Events.GENERATOR_RUNNING,
      () => this.running || this.filePlaying || this.outputContextOpen);
    // FLL trim (Java GeneratorController subscribes GENERATOR_FREQ_TRIM): the FFT consumer owns
    // the frequency-lock loop and publishes the corrected tone frequency; the generator applies
    // it to its OWN worklet. No republish of GENERATOR_SIGNAL_CHANGED — an FLL trim is a sub-Hz
    // tweak that emitted no event before, and re-broadcasting it could spuriously reset other
    // subscribers (the scope reconstructed-beat gate, the FFT drain-skip).
    MessageBus.instance().subscribe(Events.GENERATOR_FREQ_TRIM,
      (freqHz) => this._applyFllTrim(freqHz));
    // Second-tone FLL trim (Java FftController publishes GENERATOR_FREQ_TRIM_2 for the dual-tone
    // fll2 loop): the FFT consumer steers tone 2 independently; the generator applies it to the
    // SAME running worklet as a phase-continuous second-tone retune. Only fires in dual-tone mode
    // (nobody publishes it otherwise), so no form guard is needed here.
    MessageBus.instance().subscribe(Events.GENERATOR_FREQ_TRIM_2,
      (freqHz) => this._applyFllTrim2(freqHz));
  }

  /** Stops BOTH output engines — the DDS tone and the file player (Java
   *  GeneratorController.stopEngines, the FREQRESP_MEASUREMENT_STARTED reaction). */
  async stopEngines() {
    await this.stopGenerator();
    await this.stopFile();
  }

  /** Publishes AUDIO_DEVICE_ERROR (direction = output) so the shell can raise a visible alert —
   *  the output device (generator / freqresp playback) failed to open or was lost mid-play
   *  (typically another app grabbed it exclusively). */
  _reportDeviceError(detail) {
    MessageBus.instance().publish(Events.AUDIO_DEVICE_ERROR, { direction: 'output', detail });
  }

  /** True while the generator is producing a signal (DDS tone or the WAV file player). */
  get running() { return this._genOn; }

  /** True while ANY output context is still open — the DDS tone context (outCtx), the file /
   *  sweep playback lane (_fileCtx), or a sweep context opened but not yet handed a buffer
   *  (_pendingSweepCtx). Stays true through the brief window AFTER a stop flipped the playing
   *  flag but BEFORE the context's close() resolved, so the idle-wait blocks until the OS DAC
   *  is genuinely released, not merely until the flag flipped. */
  get outputContextOpen() { return this.outCtx != null || this._fileCtx != null || this._pendingSweepCtx != null; }

  /** Posts a live message to the DDS worklet (no-op when not running). */
  postGen(msg) {
    if (this._genOn && this.genNode) this.genNode.port.postMessage(msg);
  }

  /** Applies an FLL frequency trim (GENERATOR_FREQ_TRIM) from the FFT consumer to the running
   *  DDS worklet. No-op when the generator isn't running (dropped, like postGen). */
  _applyFllTrim(freqHz) {
    if (this._genOn && this.genNode) this.genNode.port.postMessage({ frequency: freqHz });
  }

  /** Applies a SECOND-tone FLL trim (GENERATOR_FREQ_TRIM_2) to the running DDS worklet. Posts
   *  { frequency2 } so the dds-processor kernel retunes tone 2 phase-continuously
   *  (setDualToneFrequency2), like {@link #_applyFllTrim} does tone 1. No-op when not running. */
  _applyFllTrim2(freqHz) {
    if (this._genOn && this.genNode) this.genNode.port.postMessage({ frequency2: freqHz });
  }

  /** The frequency the generator actually emits for the current form — faithful port of
   *  GeneratorController.emitFrequency: RECTANGLE (hard +1/-1 edge) and TRIANGLE (duty
   *  corner — a derivative discontinuity with the same problem) are BOTH sample-period-
   *  aligned (fs/round(fs/f)) so the edge/corner always lands on a sample and cannot drift
   *  against the sample grid cycle to cycle; SINE / SINE_COMP / DUAL_TONE take the FFT-bin
   *  snap ONLY when snap-to-bin is on (Java FftBinSnap.snapIfEnabled admits SINE_COMP since
   *  4887ecb); every other form (noise, …) emits the raw entered value. */
  _genEmitFreq() {
    const c = this.config;
    const raw = c.toneHz;
    if (c.form === GenSignalForm.RECTANGLE || c.form === GenSignalForm.TRIANGLE) {
      const outRate = this.outSampleRate || c.outRate;   // ACTUAL context rate, not the requested
      if (raw <= 0 || outRate <= 0) return raw;
      return outRate / Math.max(2, Math.round(outRate / raw));   // samplePeriodAlignedHz
    }
    if ((c.form === GenSignalForm.SINE || c.form === GenSignalForm.SINE_COMP
        || isDualTone(c.form)) && c.snapToBin) {
      // Java FftBinSnap.snapIfEnabled: binHz = OUTPUT sampleRate / fftLength
      // (the emit grid is the DAC's bin width, not the capture-side binW).
      const outRate = this.outSampleRate || c.outRate;
      const binHz = outRate / c.fftSize;
      if (c.fftSize < 8 || outRate <= 0 || binHz <= 0) return raw;
      return Math.round(raw / binHz) * binHz;
    }
    return raw;
  }

  /** The second-tone frequency the DDS actually emits — Java FftBinSnap.snapIfEnabled
   *  for DUAL_TONE: snapped to the OUTPUT-rate bin grid (outRate/fftSize) when
   *  snap-to-bin is on and the form is dual-tone, else the raw entered value.
   *  Keeps tone 2 on a bin centre exactly like {@link #_genEmitFreq} does tone 1. */
  _genEmitFreq2() {
    const c = this.config;
    const raw = c.tone2Hz;
    if (isDualTone(c.form) && c.snapToBin) {
      const outRate = this.outSampleRate || c.outRate;
      const binHz = outRate / c.fftSize;
      if (c.fftSize < 8 || outRate <= 0 || binHz <= 0) return raw;
      return Math.round(raw / binHz) * binHz;
    }
    return raw;
  }

  /** Faithful port of {@link FftBinSnap#snapIfEnabled}: snaps the entered SINE / DUAL_TONE tone
   *  to the bin grid of an ARBITRARY sample rate (binHz = sampleRate / fftSize), returning the raw
   *  value for other forms or when snap-to-bin is off. Java hardwires the OUTPUT rate here because
   *  its DAC and ADC share ONE exclusive-mode clock; the web's generator (outCtx) and capture (inCtx)
   *  are two independent AudioContext clocks, so the FLL must snap its target to the CAPTURE rate to
   *  land the captured tone on the actual FFT bin. Parameterised on `sampleRate` for that reason;
   *  {@link #_genEmitFreq} keeps using the OUTPUT rate for what the DAC actually plays. */
  snapToRate(sampleRate) {
    const c = this.config;
    const raw = c.toneHz;
    // SINE_COMP snaps like SINE: Java FftBinSnap.snapIfEnabled admits SINE / SINE_COMP / DUAL_TONE
    // (and FftController.applyFrequencyLock:315 additionally hard-codes GenSignalForm.SINE), so the
    // FLL target and the generator emit agree on the bin-snapped frequency for a compensated sine.
    if (c.form !== GenSignalForm.SINE && c.form !== GenSignalForm.SINE_COMP && !isDualTone(c.form)) return raw;
    if (!c.snapToBin) return raw;
    const binHz = sampleRate / c.fftSize;
    if (c.fftSize < 8 || sampleRate <= 0 || binHz <= 0) return raw;
    return Math.round(raw / binHz) * binHz;
  }

  /** {@link #snapToRate} for the SECOND dual-tone tone (config.tone2Hz). Mirrors Java
   *  FftController.applyFrequencyLock:273-274, which snaps t2 with FftBinSnap.snapIfEnabled
   *  (DUAL_TONE) at slot.sampleRate. Same gates as snapToRate but only ever active for a
   *  dual-tone form (there is no tone 2 otherwise), and parameterised on the CAPTURE rate for
   *  the same two-independent-clocks reason the FLL targets the capture-rate bin, not the DAC's. */
  snapToRate2(sampleRate) {
    const c = this.config;
    const raw = c.tone2Hz;
    if (!isDualTone(c.form)) return raw;
    if (!c.snapToBin) return raw;
    const binHz = sampleRate / c.fftSize;
    if (c.fftSize < 8 || sampleRate <= 0 || binHz <= 0) return raw;
    return Math.round(raw / binHz) * binHz;
  }

  /** Derives binW + the generator emit frequency (`snapped`) + its analysis bin (`fundBin`).
   *  `snapped` is the form-appropriate emit frequency and drives BOTH the DDS and the FFT/scope
   *  geometry, so what is played, measured and shown stay consistent. */
  computeAnalysisFreqs() {
    const c = this.config;
    this.binW = c.inRate / c.fftSize;
    this.snapped = this._genEmitFreq();
    this.fundBin = Math.max(1, Math.round(this.snapped / this.binW));
  }

  /** Opens the output AudioContext + dds-processor and starts the tone. Idempotent.
   *  Returns the i18n reason KEY of a failed start (null on success) — mirrors Java
   *  tryStartOnce, whose non-null return carries the failure reason. Also cached in
   *  {@link #lastStartError}. */
  async startGenerator() {
    if (this._genOn) return null;
    const c = this.config;
    this.lastStartError = null;
    // Compensated forms refuse to start without a loaded .dpd (Java tryStartOnce returns
    // generator.error.needPredistortion before opening the device) — abort BEFORE opening
    // the output context, leaving _genOn false.
    if ((c.form === GenSignalForm.SINE_COMP || c.form === GenSignalForm.DUAL_TONE_COMP)
        && !c.dpdText) {
      this.lastStartError = 'generator.error.needPredistortion';
      return this.lastStartError;
    }
    this.computeAnalysisFreqs();
    this._status('opening output context + device…');
    try {
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
      this._closing = false;
      this.outCtx = await openOutputContext({ sampleRate: c.outRate, latencyHint: 'playback' },
        c.outDeviceId, this._status);
      // Surface an unexpected output-device loss: the async 'AudioContext encountered an error from
      // the audio device' fires onerror, and losing the device exclusively drops the context to
      // 'interrupted'/'closed'. Only alert when it wasn't OUR stopGenerator().
      this.outCtx.onerror = () => { if (!this._closing) this._reportDeviceError('AudioContext error'); };
      this.outCtx.addEventListener('statechange', () => {
        if (this._closing || !this.outCtx) return;
        const st = this.outCtx.state;
        if (st === 'interrupted' || st === 'closed') this._reportDeviceError('AudioContext state=' + st);
      });
      // The context is granted c.outRate exactly (see the probe comment above); re-resolve the
      // emit frequency (esp. the RECTANGLE/TRIANGLE sample-period alignment) against the ACTUAL rate.
      this.outSampleRate = this.outCtx.sampleRate;
      this.computeAnalysisFreqs();
      // Surface the hidden browser+Windows resampling. The probe rate describes the DEFAULT output
      // device only, so a hard "device runs at X Hz" claim is honest ONLY when we're on the default
      // sink. With a specific sink selected (c.outDeviceId set, non-'default'), the probe may not
      // describe THAT device — so we drop to a softer debug-only "cannot verify" hint rather than
      // risk asserting a wrong rate on the status line.
      const ctxRate = this.outCtx.sampleRate;
      const defaultSink = !c.outDeviceId || c.outDeviceId === 'default';
      if (probeRate > 0 && probeRate !== ctxRate && defaultSink) {
        const msg = `WARNING: output device runs at ${probeRate} Hz — the browser silently resamples ${ctxRate} Hz to it; set the Windows output device format to ${ctxRate} Hz for a clean signal`;
        this._status(msg);
        debug(`[generator] ${msg}`);
      } else if (probeRate > 0 && probeRate !== ctxRate) {
        debug(`[generator] default device runs at ${probeRate} Hz but the selected sink's rate cannot be verified from a rate-unspecified probe; if it isn't ${ctxRate} Hz the browser silently resamples ${ctxRate} Hz to it — set the Windows output device format to ${ctxRate} Hz for a clean signal`);
      } else if (probeRate === 0) {
        debug(`[generator] could not probe the output device rate; if it isn't ${ctxRate} Hz the browser silently resamples ${ctxRate} Hz to it — set the Windows output device format to ${ctxRate} Hz for a clean signal`);
      }
      await this.outCtx.audioWorklet.addModule(new URL('./worklets/dds-processor.js', import.meta.url));
      this.genNode = new AudioWorkletNode(this.outCtx, 'dds-processor', {
        // Stereo out: the worklet writes each lane explicitly to honour the output-lane
        // gate (Java's interleave seam, PcmQuantizer). A mono [1] lane up-mixed by the
        // destination could not carry per-lane values (left ≠ right for a gated / scaled
        // lane), so this is the required web-seam adaptation of Java's PcmQuantizer point.
        outputChannelCount: [2],
        processorOptions: {
          form: c.form, frequency: this.snapped, sampleRate: this.outCtx.sampleRate,
          amplitudeVRms: ampVrmsOf(c), dacFsVoltageAmpl: c.dacFsVoltageAmpl,
          // TPDF dither depth applied LIVE in the worklet (Java PcmQuantizer): added to the mono
          // sample before the per-lane scale, so it shows on the FFT floor where the dBV view sets
          // it. 0 = Off.
          ditherBits: c.ditherBits != null ? c.ditherBits : 0,
          // Output routing (Java GeneratorController.pushOutputRoutingToPlayback): the lane
          // gate + right-lane scale (= fsLeft/fsRight). Left keeps the mono amplitude (scale 1.0).
          outputChannels: c.outputChannels != null ? c.outputChannels : 'BOTH',
          rightLaneScale: c.rightLaneScale != null ? c.rightLaneScale : 1.0,
        },
      });
      // Push the remaining live parameters (duty, dual-tone tone2/split) — absent
      // processorOptions fields the kernel already defaulted.
      this.genNode.port.postMessage({
        rectDuty: c.rectDuty, triDuty: c.triDuty,
        frequency2: this._genEmitFreq2(), dualAmp1Pct: c.amp1Pct, dualAmp2Pct: c.amp2Pct,
      });
      this._postSweepConfig();   // sweep forms: send linear/log config + fade/loop
      // Compensated forms (SINE_COMP / DUAL_TONE_COMP): hand the loaded .dpd text to the
      // worklet, which parses it (loadHarmonics / loadIntermod) and pre-distorts the output.
      if (c.dpdText) this.genNode.port.postMessage({ dpdText: c.dpdText, dpdFrequency: this.snapped });
      this.genNode.connect(this.outCtx.destination);
      if (this.outCtx.state === 'suspended') await this.outCtx.resume();
      this._genOn = true;
      this._status(`generator running — out ${this.outCtx.sampleRate} Hz, tone ${this.snapped.toFixed(3)} Hz`);
      return null;
    } catch (e) {
      this._status('generator start failed: ' + e.name + ' — ' + e.message);
      this._reportDeviceError(e.name + ' — ' + e.message);   // visible alert: the output device couldn't be opened
      await this.stopGenerator();
      this.lastStartError = 'generator.error.startUnknown';
      return this.lastStartError;
    }
  }

  /** Stops the tone and tears the output graph down (disconnect → suspend → close —
   *  closing a context with a live worklet wired can crash the renderer). */
  async stopGenerator() {
    this._genOn = false;
    this._closing = true;   // suppress the statechange our own close() will fire
    try { if (this.genNode) this.genNode.disconnect(); } catch (_) {}
    this.genNode = null;
    try { if (this.outCtx) { await this.outCtx.suspend().catch(() => {}); await this.outCtx.close(); } } catch (_) {}
    this.outCtx = null;
  }

  /** Live retune of generator parameters that don't change structure (no restart):
   *  amplitude, duty, second-tone frequency, dual-tone split. */
  retuneGenerator() {
    if (!this._genOn || !this.genNode) return;
    const c = this.config;
    this.computeAnalysisFreqs();   // re-resolve the emit frequency for the (possibly edited) tone/form
    this.genNode.port.postMessage({
      frequency: this.snapped,      // primary tone — was previously dropped on live freq edits
      amplitudeVRms: ampVrmsOf(c), dacFsVoltageAmpl: c.dacFsVoltageAmpl,
      rectDuty: c.rectDuty, triDuty: c.triDuty,
      frequency2: this._genEmitFreq2(), dualAmp1Pct: c.amp1Pct, dualAmp2Pct: c.amp2Pct,
      // Dither depth rides every retune (Java setDitherBits live-applies to the running playback).
      ditherBits: c.ditherBits != null ? c.ditherBits : 0,
      // Output routing rides every retune (Java pushOutputRoutingToPlayback): a lane-gate
      // or DAC-full-scale edit lands on the worklet's next block.
      outputChannels: c.outputChannels != null ? c.outputChannels : 'BOTH',
      rightLaneScale: c.rightLaneScale != null ? c.rightLaneScale : 1.0,
    });
    this._postSweepConfig();
  }

  /** Sends the sweep configuration to the dds-processor for the current form (linear vs
   *  Farina log), plus loop + Hann fade lengths. No-op for non-sweep forms. Durations →
   *  samples at the output rate. Called from startGenerator + retuneGenerator. */
  _postSweepConfig() {
    if (!this.genNode) return;
    const c = this.config;
    const rate = this.outSampleRate || (this.outCtx && this.outCtx.sampleRate) || c.outRate;
    const samples = Math.max(1, Math.round(c.sweepDurationSec * rate));
    let msg;
    if (c.form === GenSignalForm.LINEAR_SWEEP) {
      msg = { linearSweep: { freqStart: c.sweepStartHz, freqEnd: c.sweepEndHz, periodSamples: samples } };
    } else if (c.form === GenSignalForm.LOG_SWEEP) {
      msg = { logSweep: { f0: c.sweepStartHz, f1: c.sweepEndHz, sweepSamples: samples, leadInSamples: 0 } };
    } else {
      return;
    }
    msg.sweepParams = { loop: c.sweepLoop, fadeInSamples: Math.round(c.sweepFadeInSec * rate), fadeOutSamples: Math.round(c.sweepFadeOutSec * rate) };
    this.genNode.port.postMessage(msg);
  }

  // ---------------------------------------------------------------------------
  // FILE PLAYER (generator "Load from…") — faithful port of FilePlayController:
  // a MONITORING CONVENIENCE on its own DAC lane, deliberately NOT the
  // measurement path. The caller decodes (readWav/Aiff/decodeFlac) so this just
  // owns the AudioBuffer playback (loop toggled live like the volatile flag).
  // ---------------------------------------------------------------------------

  /** Plays decoded float channels through a fresh output context routed to the DAC.
   *  Replaces any current playback. `onFileEnded` (if set) fires on natural (non-loop) end. */
  async playFileBuffer(channels, sampleRate, loop) {
    await this.stopFile();
    const ctx = new AudioContext({ latencyHint: 'playback' });
    if (ctx.setSinkId && this.config.outDeviceId) {
      try { await ctx.setSinkId(this.config.outDeviceId); } catch (e) { console.warn('setSinkId', e); }
    }
    const frames = channels[0].length;
    const buf = ctx.createBuffer(channels.length, frames, sampleRate);
    for (let ch = 0; ch < channels.length; ch++) buf.copyToChannel(channels[ch], ch);
    const src = ctx.createBufferSource();
    src.buffer = buf; src.loop = !!loop;
    src.connect(ctx.destination);
    src.onended = () => {
      if (this._fileSrc !== src) return;   // superseded by a newer session
      this._fileSrc = null; this._fileCtx = null;
      ctx.close().catch(() => {});
      if (this.onFileEnded) this.onFileEnded();
    };
    this._fileCtx = ctx; this._fileSrc = src;
    if (ctx.state === 'suspended') await ctx.resume();
    src.start();
  }

  /** Opens the fresh output context the sweep will play into, requesting it AT
   *  {@code requestedRate} (the capture rate), and returns the rate the browser
   *  ACTUALLY granted. The caller MUST inspect the returned rate BEFORE authoring
   *  the sweep and cap the sweep's top frequency to just under grantedRate/2: the
   *  Windows output device often runs at 48/96 kHz even on a 192 kHz-input box, so
   *  the granted output Nyquist can sit far below the requested sweep band. Anything
   *  above the granted Nyquist is discarded by the AudioBufferSourceNode resampler
   *  before it reaches the DAC, so it is never physically played — yet the reference
   *  X would still carry full energy there, collapsing H = Y/X to the noise floor
   *  over the un-played band and blowing it up (÷ near-zero |X|) at the reference's
   *  own band edge (the reported noise-floor plot + huge near-Nyquist spike). The
   *  context is stashed for the paired {@link #playSweepBuffer}; call it once per
   *  measurement, then playSweepBuffer, then stopFile. */
  async openSweepContext(requestedRate) {
    await this.stopFile();
    // Bounded-retry open: the FreqResp takeover stops the other modules then opens the DAC
    // here, but a just-stopped context can still be releasing the OS device — retry the
    // NotReadable/Abort contention before it surfaces as an alert (a self-contention).
    const ctx = await openOutputContext({ sampleRate: requestedRate, latencyHint: 'playback' },
      this.config.outDeviceId, this._status);
    this._pendingSweepCtx = ctx;
    return ctx.sampleRate;
  }

  /** Plays ONE pre-rendered mono measurement buffer (the exact Farina sweep the
   *  deconvolution uses as its reference X) through the output context, so what is
   *  PLAYED equals the reference by construction and shares the reference's clock
   *  domain — mirroring the desktop, which pre-renders the sweep and hands that same
   *  buffer to the DAC (FreqRespAnalyzer: gen.getLogSweepBuffer() is both played and
   *  deconvolved against). Reuses the context opened by {@link #openSweepContext}
   *  (so the caller could cap the sweep band to the granted output Nyquist); if none
   *  is pending it opens one AT {@code sampleRate} (legacy path). Uses the file lane
   *  (_fileSrc/_fileCtx) so stopFile() tears it down; NOT looped. Resolves once
   *  playback has started; returns the context's granted rate. */
  async playSweepBuffer(buf, sampleRate, opts = {}) {
    let ctx = this._pendingSweepCtx;
    this._pendingSweepCtx = null;
    if (ctx) {
      // The pending context already had stopFile() run inside openSweepContext; do
      // NOT run it again here or it would tear the just-opened context down.
    } else {
      await this.stopFile();
      ctx = await openOutputContext({ sampleRate, latencyHint: 'playback' },
        this.config.outDeviceId, this._status);
    }
    // Tag the AudioBuffer with the rate the sweep was AUTHORED at (the capture
    // rate), NOT ctx.sampleRate. The browser may grant an output context at a
    // different rate than requested (a mismatched or capped output device); an
    // AudioBufferSourceNode resamples buffer.sampleRate → ctx.sampleRate with its
    // own high-quality resampler, so the sweep still plays over its correct
    // PHYSICAL duration (buf.length / sampleRate seconds) and its instantaneous-
    // frequency-vs-time law matches the deconvolution reference (rendered at this
    // same capture rate). Tagging it with ctx.sampleRate instead told the engine
    // "these samples are already at the context rate" — so on any rate mismatch the
    // sweep played at the wrong speed, breaking reference==playback and smearing H
    // into comb-noise with a huge near-Nyquist spike (division by near-zero
    // reference energy where the mis-clocked sweep no longer has content).
    // Output-lane gate (Java CaptureWithGenerator.runStereo → setOutputChannels):
    // the sweep is GATE-ONLY — an un-driven lane carries digital silence, and the
    // driven lane(s) are NEVER scaled (calibration enters only in the deconvolution
    // math; scaling the played lane against the unscaled reference X would inject a
    // gain error into H). BOTH keeps the legacy identical-lanes behaviour.
    const outputChannels = opts.outputChannels || 'BOTH';
    const mono = buf instanceof Float32Array ? buf : Float32Array.from(buf);
    // copyToChannel wants a Float32Array; the sweep is Float64 — narrow it (32-bit
    // float is the documented web-audio deviation and is what the DAC plays anyway).
    const audioBuf = ctx.createBuffer(2, buf.length, sampleRate);
    const silence = (outputChannels !== 'BOTH') ? new Float32Array(buf.length) : null;
    audioBuf.copyToChannel(outputChannels === 'RIGHT' ? silence : mono, 0);
    audioBuf.copyToChannel(outputChannels === 'LEFT' ? silence : mono, 1);
    const src = ctx.createBufferSource();
    src.buffer = audioBuf; src.loop = false;
    src.connect(ctx.destination);
    src.onended = () => {
      if (this._fileSrc !== src) return;   // superseded by a newer session
      this._fileSrc = null; this._fileCtx = null;
      ctx.close().catch(() => {});
    };
    this._fileCtx = ctx; this._fileSrc = src;
    if (ctx.state === 'suspended') await ctx.resume();
    src.start();
    // Report the rate the browser ACTUALLY granted. When it differs from the requested
    // (capture) rate the output device could not open at that rate and the buffer is
    // resampled to ctx.sampleRate — everything above ctx.sampleRate/2 is lost before it
    // ever reaches the DAC, so the deconvolution combs and spikes at HF. The caller
    // (FreqRespHost) compares this against the capture rate and surfaces it.
    return ctx.sampleRate;
  }

  /** Live-toggles looping on the running file source (picked up immediately). */
  setFilePlayLoop(loop) { if (this._fileSrc) this._fileSrc.loop = !!loop; }

  /** Stops file playback and tears down its context (idempotent). Also closes any
   *  sweep context that openSweepContext opened but that never received a buffer
   *  (an aborted measurement), so the output device is never left held open. */
  async stopFile() {
    const src = this._fileSrc, ctx = this._fileCtx;
    const pending = this._pendingSweepCtx;
    this._fileSrc = null; this._fileCtx = null; this._pendingSweepCtx = null;
    if (src) { try { src.onended = null; src.stop(); } catch (e) { /* already stopped */ } try { src.disconnect(); } catch (e) { /* ignore */ } }
    if (ctx) { try { await ctx.close(); } catch (e) { /* ignore */ } }
    if (pending && pending !== ctx) { try { await pending.close(); } catch (e) { /* ignore */ } }
  }

  get filePlaying() { return !!this._fileSrc; }
}
