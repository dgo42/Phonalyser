/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of gui/freqresp/FreqRespController + its FreqRespAnalyzerWorker lifecycle.
// Owns TWO things:
//  1. the sweep-timing rules - the lead-in floor and the derived sweep duration that pairs with
//     the chosen FFT size, so the analyzer's nextPow2(leadIn + sweep + tail) lands exactly on
//     fftSize (no wasted bins);
//  2. the MEASUREMENT lifecycle - runSweep / captureAndDeconvolve + the busy live-meter. Java
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

/** The largest |sample| across both raw capture channels, normalised (Java
 *  FreqRespAnalyzer.rawPeak). 0 for an empty capture. */
function rawPeakOf(left, right) {
  let peak = 0;
  for (const ch of [left, right]) {
    if (!ch) continue;
    for (let i = 0; i < ch.length; i++) {
      const a = Math.abs(ch[i]);
      if (a > peak) peak = a;
    }
  }
  return peak;
}

export class FreqRespController {
  /** Lead-in floor (s) - shorter lead-ins starve the deconvolution. */
  static MIN_LEAD_IN_SEC = 0.05;
  /** Capture tail (s) the analyzer records past lead-in + sweep. */
  static ANALYZER_TAIL_SEC = 0.5;
  /** Sweep-duration floor (s) - a small FFT size with a long lead-in
   *  must not produce a negative or unworkable sweep. */
  static MIN_SWEEP_SEC = 0.5;
  /** How long a bench may take to mark its sweep start after being told to play (Java
   *  SweepCapture.MARK_TIMEOUT_MS) - a bound on a bench that stopped answering, not a deadline
   *  for the audio. */
  static MARK_TIMEOUT_MS = 10000;
  /** Park between checks while waiting for that mark (Java SweepCapture.MARK_POLL_MS) - the same
   *  50 ms slice every cancel poll in this pipeline uses. */
  static MARK_POLL_MS = 50;
  /** How long the deconvolution worker may take before it is declared gone. The work scales with
   *  the FFT size and tops out at the largest one the preference allows (2^24), which measures
   *  ~10 s for both channels on a current desktop - so a minute is several times the slowest
   *  legitimate run and can only fire on a worker that is never going to answer. */
  static DECONVOLVE_TIMEOUT_MS = 60000;

  /**
   * @param {import('../audio/backend.js').AudioEngine} engine the live engine
   *   (the capture/input sample rate drives the derived duration; the sweep records off it).
   * @param {import('../store/preferences.js').Preferences} prefs Preferences.instance()
   * @param {import('./freqresp-view.js').FreqRespView} view the passive canvas view the sweep drives
   * @param {import('../common/correction-store.js').CorrectionStore} correctionStore the loaded-.frc store
   */
  constructor(engine, prefs, view, correctionStore, showAlert = null) {
    this.engine = engine;
    this.prefs = prefs;
    this.view = view;
    this.correctionStore = correctionStore;
    // The shell's one alert surface (the web's Dialogs.warn). Absent -> console (a headless
    // construction / the node tests).
    this._showAlert = showAlert || ((title, message) => console.warn(title, message));
    this.$ = window.jQuery;

    this.stereo = null;        // last measured StereoFreqRespResult {left,right} (raw)
    this.running = false;
    // Cooperative cancel flag (Java FreqRespAnalyzerWorker.cancelFlag), polled by the sweep's
    // wait loops every ≤50 ms - set by the busy shell's Cancel button (cancelMeasurement).
    this._cancelled = false;
    // True while OUR OWN teardown is closing the busy modal, so its close handler can tell that
    // from a user close (Java closeBusyShell disposes rather than closes for the same reason).
    this._closingBusy = false;
    // One bound reference, so the same function attaches and detaches (see _onBusyHide).
    this._onBusyHide = (e) => this._busyHideRequested(e);

    // Debug/e2e hook, mirroring FftPane/ScopePane: the keepalive proof has to reach the
    // measurement's own deconvolution step (_deconvolveBoth) from the page.
    if (typeof window !== 'undefined') window.__freqRespController = this;

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

  /** Total expected capture time of one sweep - lead-in + sweep + the analyzer's tail. */
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

  /** The capture (input) sample rate the duration is derived against - the loopback sweep is
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
   *  (Java FreqRespAnalyzerWorker.waitForOtherWorkersStopped) - delegates to the shared
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
      if (!stereo) { this.status('sweep cancelled.'); return; }   // cooperative cancel - no error
      this.stereo = stereo;
      this.view.setStereoResult(stereo);
      this.view.syncScrollbars();
      this.status(`done - ${stereo.left.freqs.length} points captured.`);
      this.warnIfClipped(stereo);
    } catch (e) {
      // Java reportError -> FREQRESP_MEASUREMENT_FAILED (always followed by STOPPED, which
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
   *  capture is fully idle, THEN opens the device for its OWN one-shot playback + capture - so the
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
    // config field -> CaptureWithGenerator.runStereo audioGen.setOutputChannels).
    // The FreqResp sweep is gate-ONLY - it never scales lanes (Java CaptureWithGenerator
    // calls setOutputChannels but NOT setChannelScale: "the sweep never scales lanes -
    // calibration enters only in the deconvolution math"), so rightLaneScale stays 1.0.
    const outputChannels = this.prefs.freqRespOutputChannels.get();
    const totalSec = this.expectedMeasurementSeconds();
    // Whether the chirp is rendered here or commanded to a bench - the ONE local/remote fact this
    // measurement consults, exactly as Java SweepCapture consults lane.isRemoteSession(): where
    // its record starts (the bench's in-band mark, or the local warmup anchor).
    const remote = engine.isRemoteSession();

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
      // Publish FIRST (Java FreqRespAnalyzerWorker.start): every consumer runs its stop logic -
      // GeneratorController.stopEngines, Scope/FftPane recorder stop + LED gray, GeneratorPane
      // play-button visuals. The idle wait below covers the async teardown. INSIDE the try so a
      // throw in the setup below still hits the finally that publishes STOPPED (no orphaned START).
      MessageBus.instance().publish(Events.FREQRESP_MEASUREMENT_STARTED);
      $('#frRunStrip, #frRun').prop('disabled', true);
      this.openBusyMeter(totalSec, leadInSec, durSec, f0, f1);
      this.status(`sweeping ${f0}-${f1} Hz over ${durSec}s...`);

      // Peak scale = target V RMS mapped to the DAC's normalised peak, the SAME conversion the DDS
      // kernel uses for a sine/sweep: amplitude = Vrms / (dacFsPeak · (1/√2)).
      // DAC full-scale PEAK from PREFS (the calibrated source of truth) - NOT engine.config,
      // which can still hold the backend default √2 when a sweep runs before readConfig has
      // synced the pref, giving a +20·log10(dacFsPref/√2) dB level error (e.g. 2.79 V -> +5.9 dB).
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
      // granted the ADC. acquire() re-pins engine.config.inRate to that rate - authoring the
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
      grantedOutRate = await engine.openSweepContext(sampleRate, { outputChannels });
      sweepCtxOpened = true;
      // A bench that granted ANOTHER rate is refused BEFORE anything is authored (Java
      // SweepCapture.requireRate on the generator lane): every sample count below is computed
      // against the asked clock, so a lane running on a different one measures the mismatch and
      // not the device. A local context resamples the buffer instead (playSweepBuffer's rate tag),
      // which is why the guard is the remote lane's alone.
      if (remote && grantedOutRate !== sampleRate) {
        throw new Error(`the generator lane runs at ${grantedOutRate} Hz but the sweep was built `
          + `for ${sampleRate} Hz - the measurement is refused rather than shown wrong`);
      }

      // Reference X(t): the RAW (unwindowed) unit-amplitude Farina sweep over [f0,f1] at the
      // CAPTURE rate. computeFromLogSweep applies the same Tukey fade to it internally.
      sweepRef = renderLogSweep(f0, f1, sweepSamples, sampleRate);
      // Played buffer = the EXACT signal BOTH played and deconvolved against, at the CAPTURE rate:
      // [lead-in silence] + [windowed sweep, amplitude-scaled to the DAC peak]. A BENCH builds the
      // identical chirp from the numbers it is sent (there is no uplink audio, spec 7), so only a
      // local lane needs the samples themselves.
      if (!remote) {
        const windowed = renderWindowedLogSweep(f0, f1, sweepSamples, sampleRate, fade);
        played = new Float64Array(leadInSamples + sweepSamples);
        for (let i = 0; i < sweepSamples; i++) played[leadInSamples + i] = windowed[i] * peakScale;
      }
      // Cold-device warmup BEFORE the one-shot sweep. A freshly-opened USB ADC/DAC streams
      // silence for the first tens-hundreds of ms while it spins up; without a settle the sweep
      // is captured as zeros -> garbage H. Both contexts are open by now; let them run first.
      await this._warmupDevices();
      if (this._cancelled) return null;
      // Discard the warmup silence so the recorded + live-metered window starts CLEAN at the
      // sweep: the busy meter's time axis is [0, totalSec] from here.
      engine.resetCaptureRecording();
      // Java SweepCapture.run takes its reader - and refuses on a null one - BEFORE the lane
      // starts: a capture that never opened must fail the measurement here, not after a sweep
      // has played into nothing.
      const reader = engine.recordingReader();
      if (remote && reader == null) {
        throw new Error(engine.getMeasurementStartError() || t('freqResp.error.noInputDevice'));
      }
      // Marks recorded after this position are THIS sweep's; a stale one from an earlier sweep on
      // the same stream is never mistaken for it (SweepCapture's armedAt, taken before the start).
      const armedAt = remote ? reader.getWritePos() : 0;
      await engine.playSweepBuffer(played, sampleRate, { outputChannels,
        // The bench's copy of the same chirp: SAMPLES at the asked rate, never seconds - a
        // duration converted at the wrong clock is a sweep of the wrong length.
        sweep: { f0, f1, sweepSamples, leadInSamples, fadeSamples: fade, amplitudeVRms } });
      playStarted = true;
      if (remote) {
        // Where the bench's sweep actually began, in-band (spec 5's MARKER): the local warmup
        // anchor says nothing about when a chirp rendered on another machine started.
        const mark = await this._awaitSweepMark(reader, armedAt);
        if (mark < 0) return null;                       // cooperative cancel
        engine.dropRecordingBefore(mark);
      }
      // Capture wait loop, polling the cooperative cancel flag every 50 ms. Each pass also pumps
      // the live meter from the recording's freshly-arrived tail so the trace fills in AS the
      // sweep collects - the web equivalent of the desktop's per-block StereoCaptureProgress.
      const meterBlock = Math.max(1, Math.round(sampleRate * 0.02));   // ~20 ms blocks
      let meterCursor = 0;
      // The record is full after totalSec of audio: locally that is totalSec of wall clock, but a
      // bench's stream reaches us a transport hop later, so there the FRAMES decide (Java
      // SweepCapture.assemble collects a COUNT, not a duration). Bounded by the same mark timeout
      // so a stream that dies mid-sweep fails the measurement instead of hanging it.
      const totalFrames = Math.round(totalSec * sampleRate);
      const tEnd = performance.now() + totalSec * 1000;
      const tHardEnd = tEnd + (remote ? FreqRespController.MARK_TIMEOUT_MS : 0);
      while (performance.now() < tHardEnd) {
        if (this._cancelled) return null;
        meterCursor = this.pumpMeter(meterCursor, meterBlock, sampleRate);
        if (performance.now() >= tEnd && (!remote || engine.recordingLength() >= totalFrames)) break;
        await new Promise((r) => setTimeout(r, 50));
      }
      // Flush the tail blocks that landed after the last poll.
      this.pumpMeter(meterCursor, meterBlock, sampleRate);
      await engine.stopFile();
      playStarted = false;
      const rec = await engine.stopCaptureRecording();
      recStarted = false;
      if (!rec.left || rec.left.length === 0) {
        // Device never delivered a sample (open failure / permission denied) - surface the
        // capture layer's reason; the shared finally closes the modal either way.
        throw new Error(engine.getMeasurementStartError() || t('freqResp.error.noInputDevice'));
      }

      // Output grid sampled at the deconvolution's FFT bin centres so each bin is read with
      // fractional offset 0 - no phase-sensitive complex interpolation between bins. Built from
      // the ACTUAL capture length so binHz matches computeFromLogSweep's nextPow2(...) FFT.
      const deconvM = nextPow2(Math.max(rec.left.length, leadInSamples + sweepSamples));
      const binHz = sampleRate / deconvM;
      const freqs = binAlignedFreqs(f0, f1, binHz, this.prefs.freqRespSweepPoints.get());
      // The RAW capture peak is read HERE, before the channels are handed to the worker: the
      // deconvolution takes their buffers as transferables, so after that call this side holds
      // two empty arrays.
      const rawPeak = rawPeakOf(rec.left, rec.right);
      // applySavGol=false on BOTH channels (Java FreqRespAnalyzer.java:151,156): the SG output
      // smoothing rounds the bottom off a deep/narrow notch null, so the main sweep leaves it OFF
      // (the Tune-notch wizard already does). The default stays true for other callers.
      const { calL, calR } = await this._deconvolveBoth({
        recLeft: rec.left, recRight: rec.right, sweepRef, leadInSamples, sampleRate, freqs,
        amplitudeVRms, adcFsVoltageRms, fade, applySavGol: false,
      });
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
      // The RAW capture peak travels with the result (Java FreqRespAnalyzer.rawPeak): after the
      // deconvolution a clipped sweep is a smooth, plausible curve - the flat tops are gone and
      // nothing downstream can tell. Measured on the samples is the only chance.
      return makeStereoResult(left, right, rawPeak);
    } finally {
      // EVERY exit - success, cancel, throw, device error - stops the sweep playback, releases the
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

  /**
   * Deconvolves BOTH captured channels against the reference sweep and hands back the two
   * calibrations - off the browser's main thread (Java runs this on the FreqRespAnalyzerWorker
   * daemon thread, which the web port had collapsed into this controller).
   *
   * WHY IT MOVED. At the shipped FFT size (2^22) the pair of computeFromLogSweep calls takes
   * seconds, and while they run on the main thread the socket's message handler does not - so a
   * bench's 500 ms keepalive pings go unanswered and the SERVER closes the session as dead after
   * four of them (spec 4.1), which is how the FIRST measurement of a session ended in "the
   * connection was closed".
   *
   * ONE WORKER PER MEASUREMENT, terminated with the round trip: measurements are rare and a
   * held worker is idle memory. The capture channels and the reference travel as TRANSFERABLES
   * - at this FFT size they are tens of megabytes, which must not be copied twice.
   *
   * No Worker in this environment (the node tests) -> the identical call, inline, so the math the
   * tests cover stays the math the worker runs.
   *
   * EVERY WAY IT CAN END ENDS THIS PROMISE. A worker that dies without an ErrorEvent - the
   * browser killing it for memory, which the scratch arrays make a real possibility at the top FFT
   * sizes - would otherwise leave the measurement's await pending for good: the busy modal up, Run
   * disabled, and FREQRESP_MEASUREMENT_STOPPED never published, because the finally that publishes
   * it is never reached. A failure here has to fail the measurement like any other.
   *
   * @param {Object} job the arguments of both computeFromLogSweep calls
   * @returns {Promise<{calL: Object, calR: Object}>}
   */
  _deconvolveBoth(job) {
    if (typeof Worker === 'undefined') {
      return Promise.resolve({
        calL: computeFromLogSweep(job.recLeft, job.sweepRef, job.leadInSamples, job.sampleRate,
          job.freqs, job.amplitudeVRms, job.adcFsVoltageRms, job.fade, job.applySavGol),
        calR: computeFromLogSweep(job.recRight, job.sweepRef, job.leadInSamples, job.sampleRate,
          job.freqs, job.amplitudeVRms, job.adcFsVoltageRms, job.fade, job.applySavGol),
      });
    }
    return new Promise((resolve, reject) => {
      const worker = new Worker(new URL('./deconvolve-worker.js', import.meta.url), { type: 'module' });
      let timer = null;
      const settle = (finish, value) => {
        if (timer != null) { clearTimeout(timer); timer = null; }
        worker.terminate();
        finish(value);
      };
      timer = setTimeout(() => settle(reject, new Error('the deconvolution did not finish within '
        + `${FreqRespController.DECONVOLVE_TIMEOUT_MS / 1000} s - the worker is gone`)),
      FreqRespController.DECONVOLVE_TIMEOUT_MS);
      worker.onmessage = (e) => settle(resolve, e.data);
      worker.onerror = (e) => settle(reject, new Error(e.message || 'the deconvolution worker failed'));
      // A result that cannot be deserialised on this side - no ErrorEvent is raised for it, so
      // without this handler the round trip simply never completes.
      worker.onmessageerror = () => settle(reject,
        new Error('the deconvolution result could not be delivered'));
      worker.postMessage(job, [job.recLeft.buffer, job.recRight.buffer, job.sweepRef.buffer]);
    });
  }

  /**
   * Raises the clipping warning for a finished measurement - DEFERRED to here, after the busy
   * shell is down and FREQRESP_MEASUREMENT_STOPPED has been published (a modal raised over the
   * sweep's own modal is not raised at all). A clipped capture deconvolves into a perfectly
   * smooth curve, so without this the operator sees a good-looking response of a distorted
   * signal (Java FreqRespPane's deferred freqResp.warning.clipped dialog).
   *
   * @param {?{clipped: () => boolean}} stereo the finished result, or null (cancelled)
   */
  warnIfClipped(stereo) {
    if (!stereo || !stereo.clipped()) return;
    console.warn(`FreqResp: the capture is clipped (raw peak ${stereo.rawPeakLin.toFixed(6)} of full scale)`);
    this._showAlert(t('freqResp.warning.clipped.title'), t('freqResp.warning.clipped.message'));
  }

  /**
   * Waits for the bench's sweep-start mark past {@code armedAt} - faithful port of
   * SweepCapture.awaitSweepMark. BOUNDED, because a bench that stopped answering must fail the
   * measurement, not hang it: without a sample 0 the record has no place to begin, and cutting it
   * at a guess would deconvolve a shifted capture into a plausible-looking response.
   *
   * @param {import('../audio/signal-buffer-reader.js').SignalBufferReader} reader the measurement
   *        ring's cursor (the mark is a position ON it)
   * @param {number} armedAt the write position this sweep armed at
   * @returns {Promise<number>} the mark's absolute frame position, or -1 on a cooperative cancel
   */
  async _awaitSweepMark(reader, armedAt) {
    const deadline = performance.now() + FreqRespController.MARK_TIMEOUT_MS;
    for (;;) {
      const mark = reader.getSweepMarkPos();
      if (mark >= armedAt) return mark;
      if (this._cancelled) return -1;
      if (reader.isFinished()) {
        const reason = reader.getFinishedReason();
        throw new Error('the capture ended before the sweep was marked - '
          + (reason ? reason.logText : 'unknown'));
      }
      if (performance.now() > deadline) {
        throw new Error('the bench never marked its sweep start within '
          + (FreqRespController.MARK_TIMEOUT_MS / 1000) + ' s - the record has no sample 0 to '
          + 'begin at, so the measurement is refused rather than cut at a guess');
      }
      await new Promise((resolve) => {
        const timer = setTimeout(resolve, FreqRespController.MARK_POLL_MS);
        // A pending timer holds a Node test process open; browsers have no unref and ignore this.
        if (timer && typeof timer.unref === 'function') timer.unref();
      });
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
    // Cooperative Cancel (Java's busy shell has one too): the sweep's own wait loops
    // poll the flag every ≤50 ms and every exit runs the same finally, so a cancel tears the
    // measurement down exactly like a normal finish - no half-open device, no orphaned modal.
    // One shot, like Java's cancel button, which disables itself: the teardown takes a moment.
    this.$('#frBusyCancel').prop('disabled', false)
      .off('click.frCancel').on('click.frCancel', () => this._requestCancel());
    const el = document.getElementById('frBusyModal');
    if (el && window.bootstrap) {
      this.busyModal = window.bootstrap.Modal.getOrCreateInstance(el);
      const onShown = () => {
        el.removeEventListener('shown.bs.modal', onShown);
        // A fast failure may have torn the sweep down while the fade-in was in flight - close
        // (again) instead of building the meter, so the modal can never get stuck. Through
        // closeBusyMeter, so this counts as OUR close and the veto below lets it through.
        if (!this._meterArgs) { this.closeBusyMeter(); return; }
        this._createBusyMeter();
      };
      el.addEventListener('shown.bs.modal', onShown);
      // ESC and the modal's own dismiss are this window's user-close. Letting it vanish would
      // leave the sweep playing behind a locked, inert pane - so a user close IS the cancel:
      // vetoed here, and the normal STOPPED path closes the modal exactly like a completed run.
      // Once a cancel is already pending, a second close is let through as an escape hatch in
      // case the teardown hangs (Java FreqRespPane's SWT.Close handler). The wizard's trial
      // sweep runs through this same modal and this same runner, so it cancels here too, where
      // Java's separate wizard progress window can only REFUSE the close - its analyzer has no
      // cancel hook. Both ends at the same guarantee: no sweep is ever orphaned by a close.
      el.addEventListener('hide.bs.modal', this._onBusyHide);
      this.busyModal.show();
    } else {
      this._createBusyMeter();   // no bootstrap (headless harness) - build it directly
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

  /** Asks the running measurement to stop at its next poll (Java
   *  FreqRespAnalyzerWorker.cancelMeasurement). Cooperative, never abrupt: the sweep's own wait
   *  loops return null, the shared finally releases the device and publishes STOPPED. No-op
   *  when nothing is running. */
  cancelMeasurement() {
    if (!this.running) return;
    this._cancelled = true;
    this.status('cancelling...');
  }

  /** The one-shot cancel request behind BOTH the Cancel button and a user close (Java
   *  FreqRespPane.requestCancel): the button goes dead because the teardown takes a moment,
   *  and the close handler reads the same disabled state as "already asked". */
  _requestCancel() {
    if (this.$('#frBusyCancel').prop('disabled')) return;
    this.$('#frBusyCancel').prop('disabled', true);
    this.cancelMeasurement();
  }

  /** hide.bs.modal on the busy modal: our own close passes, a USER close becomes the cancel
   *  request. Reached through the bound {@code _onBusyHide} so the same reference detaches. */
  _busyHideRequested(e) {
    if (this._closingBusy) return;                             // Java's dispose(), not close()
    // Nothing to protect once the measurement is over - a sweep that never started (no device
    // configured) or one that already failed leaves the modal on screen, and vetoing THAT close
    // is how a dialog becomes unclosable. Java's busy shell only exists while the sweep runs.
    if (!this.running) return;
    if (this.$('#frBusyCancel').prop('disabled')) return;      // a cancel is already pending
    if (e && e.preventDefault) e.preventDefault();
    this._requestCancel();
  }

  /** Closes the busy modal + drops the meter (Java closeBusyShell, which disposes rather than
   *  closes so the teardown is not mistaken for a user close). */
  closeBusyMeter() {
    this._meterArgs = null;
    const el = document.getElementById('frBusyModal');
    if (el) el.removeEventListener('hide.bs.modal', this._onBusyHide);
    this._closingBusy = true;
    try {
      // The instance is re-resolved from the element rather than read off this.busyModal alone:
      // a close during the show transition is IGNORED by Bootstrap, and the 'shown' handler then
      // closes again - by which time this.busyModal has already been nulled. Reading only the
      // field there left the second attempt with nothing to hide, and the modal stayed up over a
      // sweep that never started (no device configured) with no way to dismiss it.
      const modal = this.busyModal
        || (el && window.bootstrap ? window.bootstrap.Modal.getOrCreateInstance(el) : null);
      this.busyModal = null;
      if (modal) modal.hide();
    } finally { this._closingBusy = false; }
    this.busyMeter = null;
  }
}
