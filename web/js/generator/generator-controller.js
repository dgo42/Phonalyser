/*
 * Phonalyser web — the DDS generator + file-player output path.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of gui/generator/GeneratorController. Owns the generator LIFECYCLE (tone / sweep /
 * dual-tone / compensated), the monitoring file-player lane, and the analysis frequencies
 * (`snapped`/`binW`/`fundBin`) that keep what is PLAYED, MEASURED and SHOWN consistent. Has nothing
 * to do with capture. The shared `config` object is held by reference, so a live edit on either side
 * is visible to both. The FFT-side frequency-lock loop (which OWNS the FLL state) steers the tone by
 * publishing GENERATOR_FREQ_TRIM; this controller subscribes and applies the trim to its sink (see
 * _applyFllTrim).
 *
 * The DAC itself lives behind the PlaybackSink seam below — web-audio-playback-sink.js (the output
 * AudioContext + the dds-processor worklet) or qa40x/qa40x-playback-sink.js (the QA402/QA403 duplex
 * engine's generator lane). Same split as the desktop, where this controller asks
 * AudioBackend.openPlayback for an AudioPlayback and never touches a device line itself.
 */
import { GenSignalForm, isDualTone } from './dds-kernel.js';
import { MessageBus } from '../bus/message-bus.js';
import { Events } from '../bus/events.js';
import { openOutputContext, WebAudioPlaybackSink } from './web-audio-playback-sink.js';

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

/**
 * The playback sink: ONE output lane, opened per generator start and closed on stop — the web's
 * AudioPlayback (org.edgo.audio.measure.sound.AudioPlayback). Two implementations:
 * {@link WebAudioPlaybackSink} and qa40x/Qa40xPlaybackSink. Java splits the same two phases
 * (open() opens the line, play(generator, …) starts producing from an already-configured
 * SignalGenerator) and this seam keeps that split, because the emit frequency is re-resolved
 * against the rate the device actually granted BEFORE the DDS is built.
 *
 * @typedef {Object} PlaybackSink
 * @property {(spec: {sampleRate: number, deviceId: ?string}) => Promise<number>} open opens the
 *           output lane at the requested rate; resolves to the rate actually GRANTED
 * @property {(spec: PlaybackSpec) => Promise<void>} start builds the DDS and puts it on air;
 *           resolves once it is producing (Java's readyLatch)
 * @property {?{postMessage: (msg: Object) => void}} port live-control channel, MessagePort-shaped
 *           (the dds-processor message protocol); null before start / after close
 * @property {() => Promise<void>} close stops the signal and releases the device
 * @property {number} sampleRate the granted rate (0 while closed)
 */

/**
 * The generator description handed to {@link PlaybackSink#start} — Java's fully-configured
 * SignalGenerator plus the three AudioPlayback tunables (dither, lane gate, per-lane scale).
 *
 * @typedef {Object} PlaybackSpec
 * @property {string} form
 * @property {number} frequency        the EMIT frequency, resolved against the granted rate
 * @property {number} amplitudeVRms
 * @property {number} dacFsVoltageAmpl
 * @property {number} ditherBits
 * @property {string} outputChannels   'BOTH' | 'LEFT' | 'RIGHT'
 * @property {number} rightLaneScale   fsLeft/fsRight (the left lane is the amplitude reference)
 * @property {?Object} control         one live-control message applied BEFORE the lane goes live
 *           (duty, dual-tone tone2/split, sweep config, predistortion), so nothing ever renders
 *           at a default value
 */

export class GeneratorController {
  /**
   * @param config the SHARED engine config object (held by reference).
   * @param deps {status, openPlayback} — status: (text) => void; openPlayback: (deps) =>
   *   PlaybackSink, the backend's sink factory. Called at EVERY start, exactly as Java re-resolves
   *   AudioBackend.instance().openPlayback(device, rate, bitDepth, dither) per start — so a backend
   *   switch between sessions needs no push-down here. Defaults to the Web Audio sink.
   */
  constructor(config, { status, openPlayback } = {}) {
    this.config = config;
    this._status = status || (() => {});
    this._openPlayback = openPlayback || ((deps) => new WebAudioPlaybackSink(deps));
    this._genOn = false;
    // Reason key of the last failed start (mirror Java getLastStartError) — null on success.
    this.lastStartError = null;
    // Analysis freqs — derived in computeAnalysisFreqs() from the input rate + form.
    this.binW = 0;
    this.snapped = 0;
    this.fundBin = 1;
    // Output (DAC) lane — the sink opened by _openPlayback for the current session, null while
    // idle (Java's `playback` field, likewise nulled by stop()). The teardown order and the
    // suppression of our own close()'s statechange live INSIDE the sink, with the context.
    this._sink = null;
    this.outSampleRate = 0;
    // FLL trim state moved to FftController (which owns the frequency-lock loop); the generator
    // only APPLIES trims via the GENERATOR_FREQ_TRIM subscription below.
    // File-player lane (monitoring convenience, NOT the measurement path).
    this._fileSrc = null;
    this._fileCtx = null;
    // Preferences-bounce OUTPUT lifecycle (moved from GeneratorPane): which output engine was
    // playing when stopPlayForPrefs() recorded it, so startPlayForPrefs() restarts the same one.
    this._ddsWasRunningForPrefs = false;
    this._fileWasRunningForPrefs = false;
    this._lastFile = null;   // {channels, sampleRate, loop} — the last USER file played, for prefs-bounce resume
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
    // stopGenerator()/stopFile() flip their playing flags SYNCHRONOUSLY but the sink's close()
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

  /** Preferences-dialog OUTPUT bracket, phase 1 (Java GeneratorPane.stopPlayForPrefs, now owned by the
   *  controller): record which output engine was playing, then stop it before the commit so the old
   *  output line closes cleanly. */
  async stopPlayForPrefs() {
    this._ddsWasRunningForPrefs  = this.running;
    this._fileWasRunningForPrefs = this.filePlaying;
    if (this._ddsWasRunningForPrefs) await this.stopGenerator();
    else if (this._fileWasRunningForPrefs) await this.stopFile();
  }

  /** Phase 2: restart, on the just-committed output device/rate, whichever engine was playing — the DDS
   *  tone (startGenerator reads the shared config, whose generator params are unchanged by a prefs OK) or
   *  the retained file buffer. On a DDS restart failure startGenerator already publishes AUDIO_DEVICE_ERROR
   *  (its _reportDeviceError) so the pane resets its visuals — this method stays UI-free. */
  async startPlayForPrefs() {
    if (this._ddsWasRunningForPrefs) { await this.startGenerator(); return; }
    if (this._fileWasRunningForPrefs && this._lastFile) {
      await this.playFileBuffer(this._lastFile.channels, this._lastFile.sampleRate, this._lastFile.loop);
    }
  }

  /** Publishes AUDIO_DEVICE_ERROR (direction = output) so the shell can raise a visible alert —
   *  the output device (generator / freqresp playback) failed to open or was lost mid-play
   *  (typically another app grabbed it exclusively). */
  _reportDeviceError(detail) {
    MessageBus.instance().publish(Events.AUDIO_DEVICE_ERROR, { direction: 'output', detail });
  }

  /** True while the generator is producing a signal (DDS tone or the WAV file player). */
  get running() { return this._genOn; }

  /** True while ANY output lane is still open — the DDS tone sink (_sink), the file /
   *  sweep playback lane (_fileCtx), or a sweep context opened but not yet handed a buffer
   *  (_pendingSweepCtx). Stays true through the brief window AFTER a stop flipped the playing
   *  flag but BEFORE the context's close() resolved, so the idle-wait blocks until the OS DAC
   *  is genuinely released, not merely until the flag flipped (stopGenerator nulls _sink only
   *  after the sink's close() has resolved). */
  get outputContextOpen() { return this._sink != null || this._fileCtx != null || this._pendingSweepCtx != null; }

  /** The live DDS control handle — the sink's MessagePort-shaped port, so a consumer outside this
   *  module keeps reading it exactly as it read the worklet node's: FftController's FLL gate takes
   *  its truthiness as "the DDS is live and steerable", AudioEngine.postGen posts through
   *  `genNode.port.postMessage`. Backed by the worklet node's real port on Web Audio and by a
   *  direct call into the in-process kernel on the QA40x. */
  get genNode() { return this._sink; }

  /** Posts a live message to the DDS (no-op when not running). */
  postGen(msg) {
    if (this._genOn && this._sink) this._sink.port.postMessage(msg);
  }

  /** Applies an FLL frequency trim (GENERATOR_FREQ_TRIM) from the FFT consumer to the running
   *  DDS. No-op when the generator isn't running (dropped, like postGen). */
  _applyFllTrim(freqHz) {
    if (this._genOn && this._sink) this._sink.port.postMessage({ frequency: freqHz });
  }

  /** Applies a SECOND-tone FLL trim (GENERATOR_FREQ_TRIM_2) to the running DDS. Posts
   *  { frequency2 } so the dds kernel retunes tone 2 phase-continuously
   *  (setDualToneFrequency2), like {@link #_applyFllTrim} does tone 1. No-op when not running. */
  _applyFllTrim2(freqHz) {
    if (this._genOn && this._sink) this._sink.port.postMessage({ frequency2: freqHz });
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

  /** Opens the playback sink and starts the tone. Idempotent.
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
      // The backend's sink, resolved per start (Java: AudioBackend.instance().openPlayback(…)).
      this._sink = this._openPlayback({
        status: this._status,
        // An unexpected device loss mid-play — the sink's own close() never reports.
        onDeviceError: (detail) => this._reportDeviceError(detail),
      });
      // PHASE 1 — open the line and learn the rate it GRANTED, then re-resolve the emit frequency
      // (esp. the RECTANGLE/TRIANGLE sample-period alignment) against that ACTUAL rate before the
      // DDS is built. This is why the sink opens and starts in two steps.
      this.outSampleRate = await this._sink.open({ sampleRate: c.outRate, deviceId: c.outDeviceId });
      this.computeAnalysisFreqs();
      // PHASE 2 — build the DDS and put it on air. Everything the kernel needs travels in ONE
      // description, `control` carrying the parameters that are not constructor options (duty,
      // dual-tone tone2/split, sweep config, predistortion), applied before the lane goes live —
      // Java likewise hands play() a SignalGenerator that already has them.
      await this._sink.start({
        form: c.form, frequency: this.snapped,
        amplitudeVRms: ampVrmsOf(c), dacFsVoltageAmpl: c.dacFsVoltageAmpl,
        // TPDF dither depth applied LIVE by the sink (Java PcmQuantizer): added to the mono
        // sample before the per-lane scale, so it shows on the FFT floor where the dBV view sets
        // it. 0 = Off.
        ditherBits: c.ditherBits != null ? c.ditherBits : 0,
        // Output routing (Java GeneratorController.pushOutputRoutingToPlayback): the lane
        // gate + right-lane scale (= fsLeft/fsRight). Left keeps the mono amplitude (scale 1.0).
        outputChannels: c.outputChannels != null ? c.outputChannels : 'BOTH',
        rightLaneScale: c.rightLaneScale != null ? c.rightLaneScale : 1.0,
        control: {
          rectDuty: c.rectDuty, triDuty: c.triDuty,
          frequency2: this._genEmitFreq2(), dualAmp1Pct: c.amp1Pct, dualAmp2Pct: c.amp2Pct,
          ...this._sweepConfigMsg(),   // sweep forms: linear/log config + fade/loop
          // Compensated forms (SINE_COMP / DUAL_TONE_COMP): the loaded .dpd text, which the
          // kernel parses (loadHarmonics / loadIntermod) and pre-distorts the output with.
          ...(c.dpdText ? { dpdText: c.dpdText, dpdFrequency: this.snapped } : null),
        },
      });
      this._genOn = true;
      this._status(`generator running — out ${this.outSampleRate} Hz, tone ${this.snapped.toFixed(3)} Hz`);
      return null;
    } catch (e) {
      this._status('generator start failed: ' + e.name + ' — ' + e.message);
      this._reportDeviceError(e.name + ' — ' + e.message);   // visible alert: the output device couldn't be opened
      await this.stopGenerator();
      this.lastStartError = 'generator.error.startUnknown';
      return this.lastStartError;
    }
  }

  /** Stops the tone and releases the output lane. The sink owns the teardown order (Web Audio:
   *  disconnect → suspend → close, since closing a context with a live worklet wired can crash
   *  the renderer); _sink is nulled only AFTER close() resolved, so outputContextOpen still
   *  reports the device as held while it is genuinely still closing. */
  async stopGenerator() {
    this._genOn = false;
    const sink = this._sink;
    // Swallowed exactly as the old inline teardown swallowed its context errors: a wedged or
    // unplugged device must never block the stop, and the lane is dropped either way.
    try { if (sink) await sink.close(); } catch (_) {}
    this._sink = null;
  }

  /** Live retune of generator parameters that don't change structure (no restart):
   *  amplitude, duty, second-tone frequency, dual-tone split. */
  retuneGenerator() {
    if (!this._genOn || !this._sink) return;
    const c = this.config;
    this.computeAnalysisFreqs();   // re-resolve the emit frequency for the (possibly edited) tone/form
    this._sink.port.postMessage({
      frequency: this.snapped,      // primary tone — was previously dropped on live freq edits
      amplitudeVRms: ampVrmsOf(c), dacFsVoltageAmpl: c.dacFsVoltageAmpl,
      rectDuty: c.rectDuty, triDuty: c.triDuty,
      frequency2: this._genEmitFreq2(), dualAmp1Pct: c.amp1Pct, dualAmp2Pct: c.amp2Pct,
      // Dither depth rides every retune (Java setDitherBits live-applies to the running playback).
      ditherBits: c.ditherBits != null ? c.ditherBits : 0,
      // Output routing rides every retune (Java pushOutputRoutingToPlayback): a lane-gate
      // or DAC-full-scale edit lands on the sink's next block.
      outputChannels: c.outputChannels != null ? c.outputChannels : 'BOTH',
      rightLaneScale: c.rightLaneScale != null ? c.rightLaneScale : 1.0,
    });
    this._postSweepConfig();
  }

  /** Sends the sweep configuration for the current form to the running sink. No-op for non-sweep
   *  forms (and when nothing is running). Called from retuneGenerator; startGenerator folds the
   *  same message into the start description instead, so the first rendered block already has it. */
  _postSweepConfig() {
    if (!this._sink) return;
    const msg = this._sweepConfigMsg();
    if (msg) this._sink.port.postMessage(msg);
  }

  /** The sweep control message for the current form (linear vs Farina log), plus loop + Hann
   *  fade lengths, or null for a non-sweep form. Durations → samples at the output rate. */
  _sweepConfigMsg() {
    const c = this.config;
    const rate = this.outSampleRate || (this._sink && this._sink.sampleRate) || c.outRate;
    const samples = Math.max(1, Math.round(c.sweepDurationSec * rate));
    let msg;
    if (c.form === GenSignalForm.LINEAR_SWEEP) {
      msg = { linearSweep: { freqStart: c.sweepStartHz, freqEnd: c.sweepEndHz, periodSamples: samples } };
    } else if (c.form === GenSignalForm.LOG_SWEEP) {
      msg = { logSweep: { f0: c.sweepStartHz, f1: c.sweepEndHz, sweepSamples: samples, leadInSamples: 0 } };
    } else {
      return null;
    }
    msg.sweepParams = { loop: c.sweepLoop, fadeInSamples: Math.round(c.sweepFadeInSec * rate), fadeOutSamples: Math.round(c.sweepFadeOutSec * rate) };
    return msg;
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
    // A backend whose DAC is NOT a Web Audio device plays the buffer through its own lane. The
    // QA40x is one: building an AudioContext here sent the file to whatever Web Audio output was
    // selected and never to the analyzer. Feature-detected rather than switched on a backend name,
    // so the controller still knows nothing about which backend it is driving.
    const laneSink = await this.#bufferSink(sampleRate);
    if (laneSink) {
      this._lastFile = { channels, sampleRate, loop: !!loop };
      await laneSink.playBuffer(channels[0], {
        loop: !!loop,
        onEnded: () => { if (this.onFileEnded) this.onFileEnded(); },
      });
      return;
    }
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
    // Retain the last USER file so a prefs-bounce (startPlayForPrefs) can resume it on the new
    // output device/rate. The sweep uses playSweepBuffer, which never sets _lastFile.
    this._lastFile = { channels, sampleRate, loop: !!loop };
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
    // A lane backend grants the rate it is already clocked at — the analyzer's ADC and DAC share
    // one reg-9 register, so the sweep is authored at exactly the rate it will be played at and the
    // caller's band-cap against grantedRate/2 is a no-op rather than a real restriction.
    const laneSink = await this.#bufferSink(requestedRate);
    if (laneSink) {
      this._sweepLaneSink = laneSink;
      return requestedRate;
    }
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
    // The lane backends play the pre-rendered sweep sample-for-sample (one shared clock), so
    // played == reference by construction — no AudioBuffer, no resampler, no rate tagging.
    const laneSink = this._sweepLaneSink || await this.#bufferSink(sampleRate);
    this._sweepLaneSink = null;
    if (laneSink) {
      await laneSink.playBuffer(buf instanceof Float32Array ? buf : Float32Array.from(buf), {
        loop: false,
        outputChannels: opts.outputChannels || 'BOTH',
        onEnded: () => { if (this.onFileEnded) this.onFileEnded(); },
      });
      return sampleRate;
    }
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
  setFilePlayLoop(loop) { if (this._fileSrc) this._fileSrc.loop = !!loop; if (this._lastFile) this._lastFile.loop = !!loop; }

  /** Stops file playback and tears down its context (idempotent). Also closes any
   *  sweep context that openSweepContext opened but that never received a buffer
   *  (an aborted measurement), so the output device is never left held open. */
  async stopFile() {
    // The lane sinks first: closing one detaches the generator lane, which is what stops the
    // analyzer's DAC and — on the last detach — parks and releases the device.
    const lane = this._fileLaneSink, pendingLane = this._sweepLaneSink;
    this._fileLaneSink = null; this._sweepLaneSink = null;
    if (lane) { try { await lane.close(); } catch (e) { /* ignore */ } }
    if (pendingLane && pendingLane !== lane) { try { await pendingLane.close(); } catch (e) { /* ignore */ } }

    const src = this._fileSrc, ctx = this._fileCtx;
    const pending = this._pendingSweepCtx;
    this._fileSrc = null; this._fileCtx = null; this._pendingSweepCtx = null;
    if (src) { try { src.onended = null; src.stop(); } catch (e) { /* already stopped */ } try { src.disconnect(); } catch (e) { /* ignore */ } }
    if (ctx) { try { await ctx.close(); } catch (e) { /* ignore */ } }
    if (pending && pending !== ctx) { try { await pending.close(); } catch (e) { /* ignore */ } }
  }

  /**
   * The active backend's sink IF it plays pre-rendered buffers through its own lane (the QA40x),
   * else null for a Web Audio backend, whose file/sweep paths keep their own AudioContext code
   * unchanged. Opened through the same injected factory a generator start uses, so the controller
   * never learns which backend it is driving.
   *
   * @param {number} sampleRate the rate the buffer was authored at
   * @returns {Promise<?Object>} the opened sink, or null when this backend has no lane player
   */
  async #bufferSink(sampleRate) {
    const sink = this._openPlayback({ config: this.config, status: this._status });
    if (typeof sink.playBuffer !== 'function') {
      // A Web Audio sink: nothing was opened yet (the sink opens its context lazily), so there is
      // nothing to close — just fall back to the caller's own context path.
      return null;
    }
    await sink.open({ sampleRate, outDeviceId: this.config.outDeviceId });
    this._fileLaneSink = sink;
    return sink;
  }

  get filePlaying() { return !!this._fileSrc || !!this._fileLaneSink; }
}
