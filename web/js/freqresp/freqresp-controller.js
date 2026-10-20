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
import { LEAD_IN_MIN_SEC, SWEEP_MIN_SEC } from './freqresp-constants.js';
import { nextPow2 } from '../dsp/mathutil.js';
import { FreqRespLiveMeter } from './live-meter.js';
import { makeFreqRespResult, makeStereoResult } from './stereo-result.js';
import { waitForWorkersIdle } from './worker-idle.js';
import { MessageBus } from '../bus/message-bus.js';
import { Events } from '../bus/events.js';
import { t } from '../i18n/i18n.js';

/** Settle time with the capture + output contexts open before the one-shot sweep plays, in ms -
 *  a cold USB device is still spinning up when its line opens. No UI field (Java hardwires it). */
const DEVICE_WARMUP_MS = 500;

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
  // The lead-in floor and the sweep-duration floor come from freqresp-constants -
  // the same limits the lead-in entry field clamps to.
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
   * @param {import('../shell/audio-engine.js').AudioEngine} engine the live engine - it carries BOTH
   *   configured clocks: the capture/input rate the derived duration and the record are counted
   *   in, and the playback/output rate the chirp is rendered and emitted on.
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
      if (v < LEAD_IN_MIN_SEC) {
        prefs.freqRespLeadInSec.set(LEAD_IN_MIN_SEC);
        return;   // the re-set re-enters here with the clamped value
      }
      this.deriveDuration();
    });
    // Initial sync: the persisted durationSec may no longer match the persisted fftSize / leadIn.
    // Re-derive and persist on build so the analyzer + label agree.
    this.deriveDuration();
    // A committed audio-format change moves the input rate the duration fit depends on (Java
    // FreqRespController subscribes AUDIO_FORMAT_CHANGED and re-derives): without this a live
    // rate change left freqRespDurationSec sized for the OLD rate until an unrelated fftSize or
    // lead-in edit happened to re-run the derivation.
    MessageBus.instance().subscribe(Events.AUDIO_FORMAT_CHANGED, () => this.recompute());
  }

  status(txt) { this.$('#status').text(txt); }

  // ===========================================================================
  // Timing rules (Java FreqRespController)
  // ===========================================================================

  /** Re-derives the duration on an audio-format (input sample rate) change, driven by the
   *  AUDIO_FORMAT_CHANGED subscription above - the same ride the desktop takes. The derived
   *  duration exists so nextPow2(leadIn + sweep + tail) lands exactly on the chosen FFT size,
   *  and that fit moves with the input rate. */
  recompute() {
    this.deriveDuration();
  }

  /** Total expected capture time of one sweep - lead-in + sweep + the analyzer's
   *  fixed tail (SWEEP_MIN_SEC, the span the decay ring-out needs regardless of
   *  how short the lead-in is).  Sizes the busy meter's time axis AND, in the
   *  web, the capture window itself (Java records the tail in its own
   *  analyzer), so the recorded window is exactly leadIn + sweep + tail = the
   *  FFT size deriveDuration solved for - no wasted bins. */
  expectedMeasurementSeconds() {
    return this.prefs.freqRespLeadInSec.get()
         + this.prefs.freqRespDurationSec.get()
         + SWEEP_MIN_SEC;
  }

  /** The derived sweep duration (s), already persisted by deriveDuration. */
  durationSec() {
    return this.prefs.freqRespDurationSec.get();
  }

  /** Derives and persists the sweep duration that pairs with the chosen FFT size, so the
   *  analyzer's nextPow2(leadIn + sweep + tail) lands exactly on fftSize. The tail the
   *  analyzer records is the fixed SWEEP_MIN_SEC span - the decay ring-out needs it in
   *  full even when the lead-in is short - so that is what subtracts here. Clamped to
   *  SWEEP_MIN_SEC. */
  deriveDuration() {
    const prefs = this.prefs;
    const sr = Math.max(1, this.captureRateHz());
    const leadIn = Math.round(prefs.freqRespLeadInSec.get() * sr);
    const tail = Math.round(SWEEP_MIN_SEC * sr);
    let sweep = prefs.freqRespFftSize.get() - leadIn - tail;
    const minSweep = Math.round(SWEEP_MIN_SEC * sr);
    if (sweep < minSweep) sweep = minSweep;
    prefs.freqRespDurationSec.set(sweep / sr);
  }

  /** The ADC's configured rate (Hz): the clock the recording arrives on, and therefore the clock
   *  the deconvolution, its reference, its bin grid and the result's Nyquist are all counted in
   *  (Java FreqRespAnalyzerConfig.captureRateHz, read off BackendPrefs.getInputSampleRate). The
   *  FFT window deriveDuration solves for is in these samples too. */
  captureRateHz() {
    return this.prefs.current().inputSampleRate || 48000;
  }

  /** The DAC's configured rate (Hz) - the format the output line is opened with and the clock the
   *  played chirp is rendered on (Java FreqRespAnalyzerConfig.playbackRateHz, read off
   *  BackendPrefs.getOutputSampleRate). Independent of {@link #captureRateHz}: with Web Audio the
   *  capture rate is whatever the device runs at while the playback rate is configurable, so the
   *  two differ exactly as on a card whose sides are set differently. */
  playbackRateHz() {
    return this.prefs.current().outputSampleRate || 48000;
  }

  /** Ceiling for the SWEPT BAND's two edges: the lower of the two configured Nyquist limits (Java
   *  FreqRespAnalyzer.validate's bandCeilingHz, FreqRespTabControl.sweepBandCeilingHz). The band
   *  has to be BOTH emittable and observable - above the DAC's Nyquist the chirp folds back on the
   *  way out, above the ADC's it folds back on the way in. */
  sweepBandCeilingHz() {
    return Math.min(this.captureRateHz(), this.playbackRateHz()) / 2.0;
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
   *  (Java FreqRespAnalyzerWorker lifecycle). A finished sweep publishes FREQRESP_RESULT_AVAILABLE
   *  once the view holds the result; the pane re-runs its enable cascade off that event and off the
   *  STOPPED event captureAndDeconvolve's finally publishes (which also covers cancel and failure). */
  async runSweep() {
    if (this.running) return;
    this.view.clearResults();   // Java FREQRESP_MEASUREMENT_STARTED clears the chart
    try {
      const stereo = await this.captureAndDeconvolve(false);
      if (!stereo) { this.status('sweep cancelled.'); return; }   // cooperative cancel - no error
      this.stereo = stereo;
      this.view.setStereoResult(stereo);
      this.view.syncScrollbars();
      // The result is on the view NOW - announce it, so every gate derived from a present
      // measurement re-runs against a populated view (Java FreqRespAnalyzerWorker publishes
      // RESULT_AVAILABLE before its finally queues STOPPED). Here STOPPED went out from
      // captureAndDeconvolve's finally while the view was still cleared - the bus dispatches
      // synchronously, so it arrives before this continuation - which leaves this the only
      // event that can carry "a measurement exists" to the consumers.
      MessageBus.instance().publish(Events.FREQRESP_RESULT_AVAILABLE, stereo);
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
    // FreqRespAnalyzerWorker's finally always publishes STOPPED).
    //
    // ONE sweep, TWO clocks (Java FreqRespAnalyzer.run). The chirp is a stretch of time - lead-in
    // silence, then the sweep - and each converter counts that same time in its own configured
    // samples: the play* counts describe what leaves the DAC, the rec* counts describe what the ADC
    // delivered and everything read off it (the deconvolution, its reference, the bin grid, the
    // result's Nyquist). Deriving both from the SECONDS keeps them equal in time whatever the rate
    // pair is; the two roundings can differ by less than one sample, which the deconvolution's own
    // delay correction absorbs. The rec* counts are taken AFTER the capture device is open, at the
    // rate the browser ACTUALLY granted the ADC (not the nominal one that was asked for), so the
    // deconvolution reads the exact reference + rate captured; the play* counts are on the DAC's
    // own CONFIGURED rate, the format its line is opened with.
    let sweepRef, played, grantedOutRate, playbackRate;
    let captureRate, recSweepSamples, recLeadInSamples, recFade;
    let playSweepSamples, playLeadInSamples, playFade;
    let recStarted = false, playStarted = false, sweepCtxOpened = false;
    try {
      // Publish FIRST (Java FreqRespAnalyzerWorker.start): every consumer runs its stop logic -
      // GeneratorController.stopEngines, Scope/FftPane recorder stop + LED gray, GeneratorPane
      // play-button visuals. The idle wait below covers the async teardown. INSIDE the try so a
      // throw in the setup below still hits the finally that publishes STOPPED (no orphaned START).
      MessageBus.instance().publish(Events.FREQRESP_MEASUREMENT_STARTED);
      $('#frRunStrip, #frRun').prop('disabled', true);
      // The band has to be BOTH emittable and observable (Java FreqRespAnalyzer.validate). Above
      // the DAC's Nyquist the chirp folds back on the way out; above the ADC's it folds back on the
      // way in - and either way the deconvolution reference, which is rendered from the asked band
      // and never folds, divides a signal that did. The whole trace then comes out wrong while
      // still looking plausible, because the impulse-response peak that sets the delay correction
      // is computed from that same polluted division. Refused, with both configured rates named,
      // rather than drawn - and refused BEFORE the busy modal opens, so nothing has to be torn down.
      const bandCeilingHz = this.sweepBandCeilingHz();
      if (f1 > bandCeilingHz) {
        throw new Error(`stopHz ${f1} Hz is above ${bandCeilingHz} Hz, half the lower of the `
          + `configured ${this.playbackRateHz()} Hz output and ${this.captureRateHz()} Hz input `
          + 'rates - the sweep would alias and the measurement would be wrong everywhere, not just '
          + 'at the top');
      }
      this.openBusyMeter(totalSec, leadInSec, durSec, f0, f1);
      this.status(`sweeping ${f0}-${f1} Hz over ${durSec}s...`);

      // Peak scale = target V RMS mapped to the DAC's normalised peak, the SAME conversion the DDS
      // kernel uses for a sine/sweep: amplitude = Vrms / (dacFsPeak · (1/√2)).
      // DAC full-scale PEAK from PREFS (the calibrated source of truth), as adcFsVoltageRms is
      // read above and as Java does (cfg.getDacFsVoltageRms()).
      const dacFsPeak = this.prefs.dacFsVoltageAmpl.get() || Math.SQRT2;
      const peakScale = amplitudeVRms / (dacFsPeak * (1.0 / Math.SQRT2));

      // Wait for the other panes' workers to actually release the capture device + DAC before
      // opening them for the sweep (Java runMeasurement's spin-wait).
      if (!(await this.waitForOtherWorkersStopped(2000)) && !this._cancelled) {
        console.warn('FreqResp: timeout waiting for other workers to release the audio device');
      }
      if (this._cancelled) return null;

      // Open the CAPTURE device FIRST so the sweep is authored at the rate the browser ACTUALLY
      // granted the ADC - the shared capture reports it once open. Authoring the
      // reference / played buffer / deconvolution at the NOMINAL requested rate instead
      // time-scales the capture against the reference (the comb-of-nulls + "runs faster" bug).
      await engine.startCaptureRecording();
      recStarted = true;
      captureRate      = engine.capture.inSampleRate || this.captureRateHz();   // ACTUAL granted ADC rate
      playbackRate     = this.playbackRateHz();         // the DAC's own configured rate
      recSweepSamples  = Math.round(durSec * captureRate);
      recLeadInSamples = Math.round(leadInSec * captureRate);
      playSweepSamples  = Math.round(durSec * playbackRate);
      playLeadInSamples = Math.round(leadInSec * playbackRate);
      // The Tukey fade is a FRACTION of the sweep, so these two counts are the same fade in
      // seconds on the two clocks (Java FreqRespAnalyzer's playFadeSamples / recFadeSamples).
      recFade  = sweepFadeSamples(recSweepSamples);
      playFade = sweepFadeSamples(playSweepSamples);

      // Open the DAC context at the DAC's OWN configured rate: each converter opens in ITS
      // configured format, and a card whose two sides are set differently refuses an output line
      // opened in the ADC's format outright. NO band cap: the sweep goes to the full requested f1,
      // which the ceiling check above has already held below both Nyquist limits. playSweepBuffer
      // tags the buffer at the authoring rate, so the browser resampler preserves the physical
      // sweep duration if the local context's granted rate differs from the configured one.
      grantedOutRate = await engine.openSweepContext(playbackRate, { outputChannels });
      sweepCtxOpened = true;
      // A bench that granted ANOTHER rate is refused BEFORE anything is authored (Java
      // SweepCapture.requireRate on the generator lane): every playback count above is computed
      // against the asked output clock, so a lane running on a different one measures the mismatch
      // and not the device. A local context resamples the buffer instead (playSweepBuffer's rate
      // tag), which is why the guard is the remote lane's alone.
      if (remote && grantedOutRate !== playbackRate) {
        throw new Error(`the generator lane runs at ${grantedOutRate} Hz but the sweep was built `
          + `for ${playbackRate} Hz - the measurement is refused rather than shown wrong`);
      }

      // Reference X(t): the RAW (unwindowed) unit-amplitude Farina sweep over [f0,f1] at the
      // CAPTURE rate and in CAPTURE samples. It divides a recording that came off the ADC, and
      // computeFromLogSweep sizes one transform over the longer of the two - so a reference on a
      // faster DAC clock would be a different number of samples for the same seconds, zero-pad the
      // recording inside that transform, and shift the lead-in INDEX by leadInSec x the rate
      // difference, an error the impulse-response delay correction absorbs into a plausible wrong
      // trace. computeFromLogSweep applies the same Tukey fade to it internally.
      sweepRef = renderLogSweep(f0, f1, recSweepSamples, captureRate);
      // Played buffer: [lead-in silence] + [windowed sweep, amplitude-scaled to the DAC peak], in
      // PLAYBACK samples at the PLAYBACK rate - it describes what leaves the DAC. A BENCH builds
      // the identical chirp from the numbers it is sent (there is no uplink audio, spec 7), so only
      // a local lane needs the samples themselves.
      if (!remote) {
        const windowed = renderWindowedLogSweep(f0, f1, playSweepSamples, playbackRate, playFade);
        played = new Float64Array(playLeadInSamples + playSweepSamples);
        for (let i = 0; i < playSweepSamples; i++) {
          played[playLeadInSamples + i] = windowed[i] * peakScale;
        }
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
        throw new Error(engine.getLastStartError() || t('freqResp.error.noInputDevice'));
      }
      // Marks recorded after this position are THIS sweep's; a stale one from an earlier sweep on
      // the same stream is never mistaken for it (SweepCapture's armedAt, taken before the start).
      const armedAt = remote ? reader.getWritePos() : 0;
      await engine.playSweepBuffer(played, playbackRate, { outputChannels,
        // The bench's copy of the same chirp: SAMPLES at the asked OUTPUT rate, never seconds - a
        // duration converted at the wrong clock is a sweep of the wrong length (Java
        // StereoCaptureProvider.forSweep, whose counts are all against playbackRateHz).
        sweep: { f0, f1, sweepSamples: playSweepSamples, leadInSamples: playLeadInSamples,
          fadeSamples: playFade, amplitudeVRms } });
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
      // The record is the ADC's, so every count and the meter's time axis divide by the CAPTURE
      // rate (Java FreqRespWizardDialog's progress callback: "the busy meter's time axis is the
      // RECORD's").
      const meterBlock = Math.max(1, Math.round(captureRate * 0.02));   // ~20 ms blocks
      let meterCursor = 0;
      // The record is full after totalSec of audio: locally that is totalSec of wall clock, but a
      // bench's stream reaches us a transport hop later, so there the FRAMES decide (Java
      // SweepCapture.assemble collects a COUNT, not a duration). Bounded by the same mark timeout
      // so a stream that dies mid-sweep fails the measurement instead of hanging it.
      const totalFrames = Math.round(totalSec * captureRate);
      const tEnd = performance.now() + totalSec * 1000;
      const tHardEnd = tEnd + (remote ? FreqRespController.MARK_TIMEOUT_MS : 0);
      while (performance.now() < tHardEnd) {
        if (this._cancelled) return null;
        meterCursor = this.pumpMeter(meterCursor, meterBlock, captureRate);
        if (performance.now() >= tEnd && (!remote || engine.recordingLength() >= totalFrames)) break;
        await new Promise((r) => setTimeout(r, 50));
      }
      // Flush the tail blocks that landed after the last poll.
      this.pumpMeter(meterCursor, meterBlock, captureRate);
      await engine.stopFile();
      playStarted = false;
      const rec = await engine.stopCaptureRecording();
      recStarted = false;
      if (!rec.left || rec.left.length === 0) {
        // Device never delivered a sample (open failure / permission denied) - surface the
        // capture layer's reason; the shared finally closes the modal either way.
        throw new Error(engine.getLastStartError() || t('freqResp.error.noInputDevice'));
      }

      // Output grid sampled at the deconvolution's FFT bin centres so each bin is read with
      // fractional offset 0 - no phase-sensitive complex interpolation between bins. Built from
      // the ACTUAL capture length so binHz matches computeFromLogSweep's nextPow2(...) FFT.
      const deconvM = nextPow2(Math.max(rec.left.length, recLeadInSamples + recSweepSamples));
      const binHz = captureRate / deconvM;
      const freqs = binAlignedFreqs(f0, f1, binHz, this.prefs.freqRespSweepPoints.get());
      // The RAW capture peak is read HERE, before the channels are handed to the worker: the
      // deconvolution takes their buffers as transferables, so after that call this side holds
      // two empty arrays.
      const rawPeak = rawPeakOf(rec.left, rec.right);
      // applySavGol=false on BOTH channels (Java FreqRespAnalyzer.java:151,156): the SG output
      // smoothing rounds the bottom off a deep/narrow notch null, so the main sweep leaves it OFF
      // (the Tune-notch wizard already does). The default stays true for other callers.
      // Every number on this job is a CAPTURE-clock quantity - the recording, the reference beside
      // it, the lead-in index into it and the fade that shapes both ends. The job keys keep
      // computeFromLogSweep's own parameter names, as they do in Java, where only the caller's
      // locals carry the clock.
      const { calL, calR } = await this._deconvolveBoth({
        recLeft: rec.left, recRight: rec.right, sweepRef, leadInSamples: recLeadInSamples,
        sampleRate: captureRate, freqs, amplitudeVRms, adcFsVoltageRms, fade: recFade,
        applySavGol: false,
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
      // The result carries the CAPTURE rate: it is the measurement's own Nyquist, the one every
      // consumer draws and saves the trace against (Java FreqRespAnalyzer's two FreqRespResults).
      const left = makeFreqRespResult('L', captureRate, calL.freqs, calL.magLin, calL.phaseRad, sweepParams, null, false);
      const right = makeFreqRespResult('R', captureRate, calR.freqs, calR.magLin, calR.phaseRad, sweepParams, null, false);
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

  /** Waits DEVICE_WARMUP_MS with the capture + output contexts open so a cold USB device finishes
   *  spinning up before the one-shot sweep plays. Polled in 50 ms steps so a cooperative cancel
   *  aborts the wait. */
  async _warmupDevices() {
    const end = performance.now() + DEVICE_WARMUP_MS;
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
