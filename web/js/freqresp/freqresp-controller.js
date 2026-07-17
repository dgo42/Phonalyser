/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of gui/freqresp/FreqRespController + its FreqRespAnalyzerWorker lifecycle.
// Owns TWO things:
//  1. the sweep-timing rules — the lead-in floor and the derived sweep duration that pairs with
//     the chosen FFT size, so the analyzer's nextPow2(leadIn + sweep + tail) lands exactly on
//     fftSize (no wasted bins);
//  2. the MEASUREMENT lifecycle — runSweep / captureAndDeconvolve + the busy live-meter. Java
//     puts this on a daemon-thread FreqRespAnalyzerWorker; the web has no worker thread, so the
//     sweep is an async method here (the controller = lifecycle owner + worker owner, collapsed).
//     It publishes FREQRESP_MEASUREMENT_STARTED/STOPPED/FAILED so every consumer self-stops
//     (GeneratorController.stopEngines; Scope/FftPane recorder stop + LED gray; GeneratorPane
//     play-button visuals) and re-enables on STOPPED.
//
// The constructor subscribes to the prefs the timing rules depend on (FFT size, lead-in) and
// re-derives + persists freqRespDurationSec. Drives the passive FreqRespView directly.

import { renderLogSweep, renderWindowedLogSweep, sweepFadeSamples } from './farina-sweep.js';
import { computeFromLogSweep, divideInPlace, binAlignedFreqs } from './deconvolve.js';
import { nextPow2 } from '../dsp/mathutil.js';
import { FreqRespLiveMeter } from './live-meter.js';
import { makeFreqRespResult, makeStereoResult } from './stereo-result.js';
import { waitForWorkersIdle } from './worker-idle.js';
import { MessageBus } from '../bus/message-bus.js';
import { Events } from '../bus/events.js';
import { t } from '../i18n/i18n.js';

export class FreqRespController {
  /** Lead-in floor (s) — shorter lead-ins starve the deconvolution. */
  static MIN_LEAD_IN_SEC = 0.05;
  /** Capture tail (s) the analyzer records past lead-in + sweep. */
  static ANALYZER_TAIL_SEC = 0.5;
  /** Sweep-duration floor (s) — a small FFT size with a long lead-in
   *  must not produce a negative or unworkable sweep. */
  static MIN_SWEEP_SEC = 0.5;

  /**
   * @param {import('../audio/backend.js').AudioEngine} engine the live engine
   *   (the capture/input sample rate drives the derived duration; the sweep records off it).
   * @param {import('../store/preferences.js').Preferences} prefs Preferences.instance()
   * @param {import('./freqresp-view.js').FreqRespView} view the passive canvas view the sweep drives
   * @param {import('./correction-store.js').FreqRespCorrectionStore} correctionStore the loaded-.frc store
   */
  constructor(engine, prefs, view, correctionStore) {
    this.engine = engine;
    this.prefs = prefs;
    this.view = view;
    this.correctionStore = correctionStore;
    this.$ = window.jQuery;

    this.stereo = null;        // last measured StereoFreqRespResult {left,right} (raw)
    this.running = false;
    // Cooperative cancel flag (Java FreqRespAnalyzerWorker.cancelFlag), polled by the sweep's
    // wait loops every ≤50 ms. Nothing sets this true today; the plumbing stays so the wait
    // loops keep their early-abort hook if a cancel path is ever added.
    this._cancelled = false;

    prefs.freqRespFftSize.addListener(() => this.deriveDuration());
    prefs.freqRespLeadInSec.addListener((v) => {
      if (v < FreqRespController.MIN_LEAD_IN_SEC) {
        prefs.freqRespLeadInSec.set(FreqRespController.MIN_LEAD_IN_SEC);
        return;   // the re-set re-enters here with the clamped value
      }
      this.deriveDuration();
    });
    // Initial sync: the persisted durationSec may no longer match the persisted fftSize / leadIn.
    // Re-derive and persist on build so the analyzer + label agree.
    this.deriveDuration();
  }

  status(txt) { this.$('#status').text(txt); }

  // ===========================================================================
  // Timing rules (Java FreqRespController)
  // ===========================================================================

  /** Re-derives the duration on an audio-format (input sample rate) change. The desktop rides
   *  AUDIO_FORMAT_CHANGED; the web has no such bus, so the rate-change site calls this directly. */
  recompute() {
    this.deriveDuration();
  }

  /** Total expected capture time of one sweep — lead-in + sweep + the analyzer's tail. */
  expectedMeasurementSeconds() {
    return this.prefs.freqRespLeadInSec.get()
         + this.prefs.freqRespDurationSec.get()
         + FreqRespController.ANALYZER_TAIL_SEC;
  }

  /** The derived sweep duration (s), already persisted by deriveDuration. */
  durationSec() {
    return this.prefs.freqRespDurationSec.get();
  }

  /** Derives and persists the sweep duration that pairs with the chosen FFT size, so the
   *  analyzer's nextPow2(leadIn + sweep + tail) lands exactly on fftSize. Clamped to MIN_SWEEP_SEC. */
  deriveDuration() {
    const prefs = this.prefs;
    const sr = Math.max(1, this.sampleRate());
    const leadIn = Math.round(prefs.freqRespLeadInSec.get() * sr);
    const tail = Math.round(FreqRespController.ANALYZER_TAIL_SEC * sr);
    let sweep = prefs.freqRespFftSize.get() - leadIn - tail;
    const minSweep = Math.round(FreqRespController.MIN_SWEEP_SEC * sr);
    if (sweep < minSweep) sweep = minSweep;
    prefs.freqRespDurationSec.set(sweep / sr);
  }

  /** The capture (input) sample rate the duration is derived against — the loopback sweep is
   *  recorded on the input, so the FFT window is in input samples (Java getInputSampleRate). */
  sampleRate() {
    return (this.engine.config && this.engine.config.inRate)
        || this.prefs.current().inputSampleRate
        || 384000;
  }

  // ===========================================================================
  // Measurement lifecycle (Java FreqRespAnalyzerWorker)
  // ===========================================================================

  /** Polls the shared capture + generator state until BOTH are idle, or the timeout expires
   *  (Java FreqRespAnalyzerWorker.waitForOtherWorkersStopped) — delegates to the shared
   *  waitForWorkersIdle helper, passing the cooperative-cancel flag so a mid-wait Cancel aborts. */
  waitForOtherWorkersStopped(timeoutMs) {
    return waitForWorkersIdle(this.engine, timeoutMs, () => this._cancelled);
  }

  /** Drives a single LOG_SWEEP through the DDS, records the loopback, deconvolves, and shows the
   *  result on the view. captureAndDeconvolve publishes FREQRESP_MEASUREMENT_STARTED so every
   *  consumer stops itself, waits for the device to go idle, then runs its own playback + capture
   *  (Java FreqRespAnalyzerWorker lifecycle). The pane re-runs refreshRiaaEnable off the STOPPED
   *  event captureAndDeconvolve's finally publishes. */
  async runSweep() {
    if (this.running) return;
    this.view.clearResults();   // Java FREQRESP_MEASUREMENT_STARTED clears the chart
    try {
      const stereo = await this.captureAndDeconvolve(false);
      if (!stereo) { this.status('sweep cancelled.'); return; }   // cooperative cancel — no error
      this.stereo = stereo;
      this.view.setStereoResult(stereo);
      this.view.syncScrollbars();
      this.status(`done — ${stereo.left.freqs.length} points captured.`);
    } catch (e) {
      // Java reportError → FREQRESP_MEASUREMENT_FAILED (always followed by STOPPED, which
      // captureAndDeconvolve's finally already published).
      MessageBus.instance().publish(Events.FREQRESP_MEASUREMENT_FAILED, e.message);
      this.status('sweep failed: ' + e.message);
    }
  }

  /** Captures one stereo loopback sweep and deconvolves both channels against the SAME reference
   *  sweep (Java FreqRespAnalyzer). When {@code applyDirect} is true the wizard's page-1 loopback
   *  is divided out of each channel so page 2 displays the DUT alone.
   *
   *  Lifecycle (Java FreqRespAnalyzerWorker.start + runMeasurement): publishes
   *  FREQRESP_MEASUREMENT_STARTED FIRST so every consumer stops itself, WAITS until the shared
   *  capture is fully idle, THEN opens the device for its OWN one-shot playback + capture — so the
   *  sweep works from ANY prior state. EVERY exit runs the shared finally: device released, busy
   *  modal closed, FREQRESP_MEASUREMENT_STOPPED published so the consumers re-enable themselves.
   *  Returns null on cooperative cancel, else the raw StereoFreqRespResult. Reused by the wizard. */
  async captureAndDeconvolve(applyDirect) {
    const $ = this.$;
    const engine = this.engine;
    const f0 = Math.max(1, this.prefs.freqRespStartHz.get());
    const f1 = Math.max(f0 + 1, this.prefs.freqRespStopHz.get());
    const durSec = this.durationSec();
    const leadInSec = Math.max(0, this.prefs.freqRespLeadInSec.get());
    const amplitudeVRms = this.prefs.freqRespAmplitudeVrms.get();
    const adcFsVoltageRms = this.prefs.adcFsVoltageRms.get();
    // Output-lane gate threaded from the pref into the playback call (Java's
    // config field → CaptureWithGenerator.runStereo audioGen.setOutputChannels).
    // The FreqResp sweep is gate-ONLY — it never scales lanes (Java CaptureWithGenerator
    // calls setOutputChannels but NOT setChannelScale: "the sweep never scales lanes —
    // calibration enters only in the deconvolution math"), so rightLaneScale stays 1.0.
    const outputChannels = this.prefs.freqRespOutputChannels.get();
    const totalSec = this.expectedMeasurementSeconds();

    this.running = true;
    this._cancelled = false;

    // Declared BEFORE the try so the finally can still read them if any setup below throws:
    // every FREQRESP_MEASUREMENT_STARTED must be balanced by the finally's STOPPED (Java
    // FreqRespAnalyzerWorker's finally always publishes STOPPED). Reference + played sweep are
    // authored later, AFTER the capture device is open at the ACTUAL granted ADC rate (NOT the
    // nominal engine.config.inRate), so the deconvolution reads the exact reference + rate played.
    let sweepRef, played, grantedOutRate, sampleRate, sweepSamples, leadInSamples, fade;
    let recStarted = false, playStarted = false, sweepCtxOpened = false;
    try {
      // Publish FIRST (Java FreqRespAnalyzerWorker.start): every consumer runs its stop logic —
      // GeneratorController.stopEngines, Scope/FftPane recorder stop + LED gray, GeneratorPane
      // play-button visuals. The idle wait below covers the async teardown. INSIDE the try so a
      // throw in the setup below still hits the finally that publishes STOPPED (no orphaned START).
      MessageBus.instance().publish(Events.FREQRESP_MEASUREMENT_STARTED);
      $('#frRunStrip, #frRun').prop('disabled', true);
      this.openBusyMeter(totalSec, leadInSec, durSec, f0, f1);
      this.status(`sweeping ${f0}–${f1} Hz over ${durSec}s…`);

      // Peak scale = target V RMS mapped to the DAC's normalised peak, the SAME conversion the DDS
      // kernel uses for a sine/sweep: amplitude = Vrms / (dacFsPeak · (1/√2)).
      // DAC full-scale PEAK from PREFS (the calibrated source of truth) — NOT engine.config,
      // which can still hold the backend default √2 when a sweep runs before readConfig has
      // synced the pref, giving a +20·log10(dacFsPref/√2) dB level error (e.g. 2.79 V → +5.9 dB).
      // Mirrors how adcFsVoltageRms is read from prefs above, and Java (cfg.getDacFsVoltageRms()).
      const dacFsPeak = this.prefs.dacFsVoltageAmpl.get() || Math.SQRT2;
      const peakScale = amplitudeVRms / (dacFsPeak * (1.0 / Math.SQRT2));

      // Wait for the other panes' workers to actually release the capture device + DAC before
      // opening them for the sweep (Java runMeasurement's spin-wait).
      if (!(await this.waitForOtherWorkersStopped(2000)) && !this._cancelled) {
        console.warn('FreqResp: timeout waiting for other workers to release the audio device');
      }
      if (this._cancelled) return null;

      // Open the CAPTURE device FIRST so the sweep is authored at the rate the browser ACTUALLY
      // granted the ADC. acquire() re-pins engine.config.inRate to that rate — authoring the
      // reference / played buffer / deconvolution at the NOMINAL requested rate instead
      // time-scales the capture against the reference (the comb-of-nulls + "runs faster" bug).
      await engine.startCaptureRecording();
      recStarted = true;
      sampleRate    = engine.config.inRate;             // ACTUAL granted ADC rate
      sweepSamples  = Math.round(durSec * sampleRate);
      leadInSamples = Math.round(leadInSec * sampleRate);
      fade          = sweepFadeSamples(sweepSamples);

      // Open the DAC context (playback needs it), requesting the same rate. NO band cap: the DAC
      // runs on its own clock and the sweep goes to the full requested f1. playSweepBuffer tags
      // the buffer at the authoring rate, so the browser resampler preserves the physical sweep
      // duration if the DAC's granted rate differs.
      grantedOutRate = await engine.openSweepContext(sampleRate);
      sweepCtxOpened = true;

      // Reference X(t): the RAW (unwindowed) unit-amplitude Farina sweep over [f0,f1] at the
      // CAPTURE rate. computeFromLogSweep applies the same Tukey fade to it internally.
      sweepRef = renderLogSweep(f0, f1, sweepSamples, sampleRate);
      // Played buffer = the EXACT signal BOTH played and deconvolved against, at the CAPTURE rate:
      // [lead-in silence] + [windowed sweep, amplitude-scaled to the DAC peak].
      const windowed = renderWindowedLogSweep(f0, f1, sweepSamples, sampleRate, fade);
      played = new Float64Array(leadInSamples + sweepSamples);
      for (let i = 0; i < sweepSamples; i++) played[leadInSamples + i] = windowed[i] * peakScale;
      // Cold-device warmup BEFORE the one-shot sweep. A freshly-opened USB ADC/DAC streams
      // silence for the first tens–hundreds of ms while it spins up; without a settle the sweep
      // is captured as zeros → garbage H. Both contexts are open by now; let them run first.
      await this._warmupDevices();
      if (this._cancelled) return null;
      // Discard the warmup silence so the recorded + live-metered window starts CLEAN at the
      // sweep: the busy meter's time axis is [0, totalSec] from here.
      engine.resetCaptureRecording();
      await engine.playSweepBuffer(played, sampleRate, { outputChannels });
      playStarted = true;
      // Capture wait loop, polling the cooperative cancel flag every 50 ms. Each pass also pumps
      // the live meter from the recording's freshly-arrived tail so the trace fills in AS the
      // sweep collects — the web equivalent of the desktop's per-block StereoCaptureProgress.
      const meterBlock = Math.max(1, Math.round(sampleRate * 0.02));   // ~20 ms blocks
      let meterCursor = 0;
      const tEnd = performance.now() + totalSec * 1000;
      while (performance.now() < tEnd) {
        if (this._cancelled) return null;
        meterCursor = this.pumpMeter(meterCursor, meterBlock, sampleRate);
        await new Promise((r) => setTimeout(r, 50));
      }
      // Flush the tail blocks that landed after the last poll.
      this.pumpMeter(meterCursor, meterBlock, sampleRate);
      await engine.stopFile();
      playStarted = false;
      const rec = await engine.stopCaptureRecording();
      recStarted = false;
      if (!rec.left || rec.left.length === 0) {
        // Device never delivered a sample (open failure / permission denied) — surface the
        // capture layer's reason; the shared finally closes the modal either way.
        throw new Error(engine.getMeasurementStartError() || t('freqResp.error.noInputDevice'));
      }

      // Output grid sampled at the deconvolution's FFT bin centres so each bin is read with
      // fractional offset 0 — no phase-sensitive complex interpolation between bins. Built from
      // the ACTUAL capture length so binHz matches computeFromLogSweep's nextPow2(...) FFT.
      const deconvM = nextPow2(Math.max(rec.left.length, leadInSamples + sweepSamples));
      const binHz = sampleRate / deconvM;
      const freqs = binAlignedFreqs(f0, f1, binHz, this.prefs.freqRespSweepPoints.get());
      // applySavGol=false on BOTH channels (Java FreqRespAnalyzer.java:151,156): the SG output
      // smoothing rounds the bottom off a deep/narrow notch null, so the main sweep leaves it OFF
      // (the Tune-notch wizard already does). The default stays true for other callers.
      const calL = computeFromLogSweep(rec.left, sweepRef, leadInSamples, sampleRate, freqs, amplitudeVRms, adcFsVoltageRms, fade, false);
      const calR = computeFromLogSweep(rec.right, sweepRef, leadInSamples, sampleRate, freqs, amplitudeVRms, adcFsVoltageRms, fade, false);
      // The wizard's page 2 divides out the page-1 loopback so the DUT shows alone (Java
      // FreqRespAnalyzerConfig.applyCalibration on the direct cal).
      if (applyDirect) {
        const direct = this.correctionStore.getDirect();
        if (direct) {
          divideInPlace({ freqs: calL.freqs, magLin: calL.magLin, phaseRad: calL.phaseRad }, direct.left);
          divideInPlace({ freqs: calR.freqs, magLin: calR.magLin, phaseRad: calR.phaseRad }, direct.right);
        }
      }
      const sweepParams = {
        startHz: f0, stopHz: f1, sweepPoints: freqs.length,
        durationSec: durSec, leadInSec, amplitudeVrms: amplitudeVRms,
      };
      const left = makeFreqRespResult('L', sampleRate, calL.freqs, calL.magLin, calL.phaseRad, sweepParams, null, false);
      const right = makeFreqRespResult('R', sampleRate, calR.freqs, calR.magLin, calR.phaseRad, sweepParams, null, false);
      return makeStereoResult(left, right);
    } finally {
      // EVERY exit — success, cancel, throw, device error — stops the sweep playback, releases the
      // capture device, closes the busy modal, frees the Play buttons and publishes STOPPED so the
      // consumers re-enable themselves (Java worker's finally).
      this.running = false;
      // stopFile also closes a sweep context that openSweepContext opened but that never received
      // a buffer (abort/cancel between open and play).
      if (playStarted || sweepCtxOpened) { try { await engine.stopFile(); } catch (_) { /* ignore */ } }
      if (recStarted) { try { await engine.stopCaptureRecording(); } catch (_) { /* ignore */ } }
      $('#frRunStrip, #frRun').prop('disabled', false);
      this.closeBusyMeter();
      MessageBus.instance().publish(Events.FREQRESP_MEASUREMENT_STOPPED);
    }
  }

  /** Waits config.warmupMs (default 500 ms) with the capture + output contexts open so a cold USB
   *  device finishes spinning up before the one-shot sweep plays. Polled in 50 ms steps so a
   *  cooperative cancel aborts the wait. */
  async _warmupDevices() {
    const warmMs = (this.engine.config && this.engine.config.warmupMs) || 500;
    const end = performance.now() + warmMs;
    while (performance.now() < end) {
      if (this._cancelled) return;
      await new Promise((r) => setTimeout(r, 50));
    }
  }

  /** Opens the busy modal hosting the live level meter for the sweep (Java FreqRespPane busy
   *  shell). The meter is built only once the dialog is LAID OUT ('shown.bs.modal'): its canvas
   *  sizes off clientWidth/Height, which read 0 while the modal is display:none. */
  openBusyMeter(totalSec, leadInSec, sweepSec, f0, f1) {
    this._meterArgs = [totalSec, leadInSec, sweepSec, f0, f1];
    const el = document.getElementById('frBusyModal');
    if (el && window.bootstrap) {
      this.busyModal = window.bootstrap.Modal.getOrCreateInstance(el);
      const onShown = () => {
        el.removeEventListener('shown.bs.modal', onShown);
        // A fast failure may have torn the sweep down while the fade-in was in flight — close
        // (again) instead of building the meter, so the modal can never get stuck.
        if (!this._meterArgs) { window.bootstrap.Modal.getOrCreateInstance(el).hide(); return; }
        this._createBusyMeter();
      };
      el.addEventListener('shown.bs.modal', onShown);
      this.busyModal.show();
    } else {
      this._createBusyMeter();   // no bootstrap (headless harness) — build it directly
    }
  }

  /** Builds + clears the live meter over the (now laid-out) #frMeter canvas. */
  _createBusyMeter() {
    if (!this._meterArgs) return;   // the modal was already closed before layout finished
    const cv = document.getElementById('frMeter');
    if (cv) {
      this.busyMeter = new FreqRespLiveMeter(cv, this.prefs, ...this._meterArgs);
      this.busyMeter.clear();
    }
  }

  /** Pumps the live meter from the in-flight recording's newly-arrived tail: consumes every whole
   *  ~20 ms block available past {@code cursor} and appends its max(L,R) RMS at the block-end time,
   *  returning the advanced cursor. Called each pass of the capture wait loop. */
  pumpMeter(cursor, block, sampleRate) {
    if (!this.busyMeter) return cursor;
    const available = this.engine.recordingLength();
    while (cursor + block <= available) {
      const rms = this.engine.recordingRms(cursor, block);
      this.busyMeter.appendSample((cursor + block) / sampleRate, rms);
      cursor += block;
    }
    return cursor;
  }

  /** Closes the busy modal + drops the meter (Java closeBusyShell). Nulling _meterArgs also tells
   *  a still-pending 'shown' handler to close instead of building the meter. */
  closeBusyMeter() {
    this._meterArgs = null;
    if (this.busyModal) { this.busyModal.hide(); this.busyModal = null; }
    this.busyMeter = null;
  }
}
