/*
 * Phonalyser web — the DDS generator + file-player output path.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of gui/generator/GeneratorController. Owns the OUTPUT AudioContext + dds-processor
 * worklet (tone / sweep / dual-tone / compensated), the monitoring file-player lane, and the
 * analysis frequencies (`snapped`/`binW`/`fundBin`) that keep what is PLAYED, MEASURED and SHOWN
 * consistent. Has nothing to do with capture. The shared `config` object is held by reference, so
 * a live edit on either side is visible to both. The FFT-side frequency-lock loop steers the tone
 * through `genFreq` + the FLL trim state held here (the FFT consumer reads/updates them for now).
 */
import { GenSignalForm, isDualTone } from '../generator/dds-kernel.js';
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
    // FLL trim state (the FFT consumer drives it off fundamentalHzRefined; reset on each start).
    this.genFreq = 0;
    this.fllErrHz = 0;
    this.fllLocked = false;
    this.fllStable = 0;
    this.rejectedCount = 0;
    // File-player lane (monitoring convenience, NOT the measurement path).
    this._fileSrc = null;
    this._fileCtx = null;
    this.onFileEnded = null;   // () => void — fires on natural (non-loop) end
  }

  /** True while the generator is producing a signal (DDS tone or the WAV file player). */
  get running() { return this._genOn; }

  /** Posts a live message to the DDS worklet (no-op when not running). */
  postGen(msg) {
    if (this._genOn && this.genNode) this.genNode.port.postMessage(msg);
  }

  /** The frequency the generator actually emits for the current form — faithful port of
   *  GeneratorController.emitFrequency: RECTANGLE is sample-period-aligned (fs/round(fs/f))
   *  so its hard +1/-1 edge always lands on a sample (no per-cycle edge jitter); SINE /
   *  DUAL_TONE take the FFT-bin snap ONLY when snap-to-bin is on; every other form
   *  (TRIANGLE, noise, SINE_COMP, …) emits the raw entered value. */
  _genEmitFreq() {
    const c = this.config;
    const raw = c.toneHz;
    if (c.form === GenSignalForm.RECTANGLE) {
      const outRate = this.outSampleRate || c.outRate;   // ACTUAL context rate, not the requested
      if (raw <= 0 || outRate <= 0) return raw;
      return outRate / Math.max(2, Math.round(outRate / raw));   // samplePeriodAlignedHz
    }
    if ((c.form === GenSignalForm.SINE || isDualTone(c.form)) && c.snapToBin) {
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
    // The FLL target snaps a compensated sine as a SINE: Java FftController.applyFrequencyLock:315
    // calls snapIfEnabled with GenSignalForm.SINE HARD-CODED, so SINE_COMP locks to the bin even though
    // FftBinSnap.snapIfEnabled(form) + the generator emit leave SINE_COMP raw. Without SINE_COMP here the
    // FLL targeted the raw off-bin frequency and never aligned (user: "sine comp doesn't snap/lock").
    if (c.form !== GenSignalForm.SINE && c.form !== GenSignalForm.SINE_COMP && !isDualTone(c.form)) return raw;
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
    // FLL state (driven off fundamentalHzRefined — sub-bin, generator-steered).
    this.genFreq = this.snapped;
    this.fllErrHz = 0; this.fllLocked = false; this.fllStable = 0;
    this.rejectedCount = 0;
    this._status('opening output context + device…');
    try {
      // OUTPUT context (DAC) — generator at the DAC's native rate.
      this.outCtx = new AudioContext({ sampleRate: c.outRate, latencyHint: 'playback' });
      if (this.outCtx.setSinkId && c.outDeviceId) {
        try { await this.outCtx.setSinkId(c.outDeviceId); } catch (e) { console.warn('setSinkId', e); }
      }
      // The browser may grant a different rate than requested; re-resolve the emit frequency
      // (esp. the RECTANGLE sample-period alignment) against the ACTUAL context rate.
      this.outSampleRate = this.outCtx.sampleRate;
      this.computeAnalysisFreqs();
      this.genFreq = this.snapped;
      if (this.outCtx.sampleRate !== c.outRate) {
        debug(`[generator] output rate is ${this.outCtx.sampleRate} Hz (requested ${c.outRate}) — browser/OS capped it; set the Windows output device to ${c.outRate} Hz to avoid resampling/slowdown`);
      }
      await this.outCtx.audioWorklet.addModule(new URL('./worklets/dds-processor.js', import.meta.url));
      this.genNode = new AudioWorkletNode(this.outCtx, 'dds-processor', {
        outputChannelCount: [1],
        processorOptions: {
          form: c.form, frequency: this.snapped, sampleRate: this.outCtx.sampleRate,
          amplitudeVRms: ampVrmsOf(c), dacFsVoltageAmpl: c.dacFsVoltageAmpl,
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
      await this.stopGenerator();
      this.lastStartError = 'generator.error.startUnknown';
      return this.lastStartError;
    }
  }

  /** Stops the tone and tears the output graph down (disconnect → suspend → close —
   *  closing a context with a live worklet wired can crash the renderer). */
  async stopGenerator() {
    this._genOn = false;
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
    this.genFreq = this.snapped;
    this.genNode.port.postMessage({
      frequency: this.snapped,      // primary tone — was previously dropped on live freq edits
      amplitudeVRms: ampVrmsOf(c), dacFsVoltageAmpl: c.dacFsVoltageAmpl,
      rectDuty: c.rectDuty, triDuty: c.triDuty,
      frequency2: this._genEmitFreq2(), dualAmp1Pct: c.amp1Pct, dualAmp2Pct: c.amp2Pct,
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

  /** Live-toggles looping on the running file source (picked up immediately). */
  setFilePlayLoop(loop) { if (this._fileSrc) this._fileSrc.loop = !!loop; }

  /** Stops file playback and tears down its context (idempotent). */
  async stopFile() {
    const src = this._fileSrc, ctx = this._fileCtx;
    this._fileSrc = null; this._fileCtx = null;
    if (src) { try { src.onended = null; src.stop(); } catch (e) { /* already stopped */ } try { src.disconnect(); } catch (e) { /* ignore */ } }
    if (ctx) { try { await ctx.close(); } catch (e) { /* ignore */ } }
  }

  get filePlaying() { return !!this._fileSrc; }
}
