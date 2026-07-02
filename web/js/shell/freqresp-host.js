/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Frequency-response (Farina) tab controller. Drives a LOG_SWEEP through the live
// DDS generator, records BOTH ADC channels off the engine in a single stereo pass,
// deconvolves each side against the SAME reference sweep (the parallel L/R
// FreqRespAnalyzer pipeline), and hands the stereo result to the interactive
// FreqRespView (log-freq / dB / phase axes, zoom+pan, crosshair, scrollbars). The
// loaded .frc calibration is applied at RENDER time by the view (not baked into the
// measured result), so swapping a calibration retraces without re-sweeping. .frc
// save writes the REAL stereo L/R curves.
//
// All DSP comes from the ported modules; this file is pure wiring.

import { renderLogSweep, sweepFadeSamples } from '../freqresp/farina-sweep.js';
import { computeFromLogSweep, divideInPlace } from '../freqresp/deconvolve.js';
import { FreqRespController } from '../freqresp/freqresp-controller.js';
import { FreqRespView } from '../freqresp/freqresp-view.js';
import { FreqRespCorrectionStore } from '../freqresp/correction-store.js';
import { FreqRespLiveMeter } from '../freqresp/live-meter.js';
import { FreqRespWizard } from '../freqresp/wizard.js';
import { makeFreqRespResult, makeStereoResult } from '../freqresp/stereo-result.js';
import { saveFrc, loadFrc } from '../io/frc.js';
import { CalibrationEntry, FreqRespPreset } from '../store/preferences.js';
import { GenSignalForm } from '../generator/dds-kernel.js';
import { t } from '../i18n/i18n.js';

const FRC_TYPE = [{ description: 'Filter calibration', accept: 'text/plain', extensions: ['.frc'] }];

/** Builds a log-spaced output frequency grid [f0, f1], `points` entries. */
function logGrid(f0, f1, points) {
  const g = new Float64Array(points);
  const l0 = Math.log(f0), l1 = Math.log(f1);
  for (let i = 0; i < points; i++) g[i] = Math.exp(l0 + (l1 - l0) * (i / (points - 1)));
  return g;
}

export class FreqRespHost {
  /**
   * @param {import('../audio/backend.js').AudioEngine} engine the live engine
   * @param {object} prefs Preferences.instance()
   * @param {{saveFile:Function, openFile:Function, bytesToText:Function}} io file-picker glue
   */
  constructor(engine, prefs, io) {
    this.engine = engine;
    this.prefs = prefs;
    this.io = io;
    this.$ = window.jQuery;

    // Owns the sweep-timing rules (Java FreqRespController): subscribes to the
    // fftSize / leadIn prefs and re-derives + persists freqRespDurationSec, so
    // the analyzer's nextPow2(leadIn + sweep + tail) lands exactly on fftSize.
    this.controller = new FreqRespController(engine, prefs);

    // Loaded-correction store (Java FreqRespCorrectionStore): the .frc entries +
    // wizard `direct` buffer the view divides out at render time. The change
    // callback retraces the view from the raw results (no re-sweep) AND rebuilds the
    // calibration-tab rows when the change came from OUTSIDE the tab (e.g. wizard
    // setCurrent / setDirect / Cancel-restore — Java FREQRESP_CALIBRATION_CHANGED).
    this.correctionStore = new FreqRespCorrectionStore('FreqResp',
      () => this.onStoreChanged());

    this.stereo = null;        // last measured StereoFreqRespResult {left,right} (raw)
    this.running = false;

    this.canvas = document.getElementById('frPlot');
    // The interactive view owns the canvas: axes, zoom/pan, crosshair, scrollbars,
    // render-time calibration. The L/R radio + RIAA/phase toggles re-derive + repaint.
    this.view = new FreqRespView(this.canvas, prefs, this.correctionStore, {
      engine,
      freqScroll: document.getElementById('frFreqScroll'),
      magScroll: document.getElementById('frMagScroll'),
      onRangeChanged: null,
    });

    // One CalRow per visible calibration row, in display order (Java FreqRespTabControl.calRows).
    this._calRows = [];
    // Re-entrancy guard so the store's change callback doesn't rebuild rows for our own writes.
    this._calMutationInFlight = false;

    // Calibration wizard (Java FreqRespWizardDialog): the 3-page guided flow. Reuses this
    // host's sweep runner (capture + deconvolve) + the live meter; Cancel restores a
    // pre-wizard snapshot of the correction store.
    this.wizard = new FreqRespWizard(this, prefs, this.view, this.correctionStore);

    this.bind();
  }

  status(t) { this.$('#frStatus').text(t); }

  bind() {
    const $ = this.$;
    // frStart/frStop/frAmp/frPoints/frLeadIn are NumericStepFields owned by
    // app.js (they two-way bind the prefs); here we only own the dropdowns,
    // checkboxes and action wiring.
    $('#frDur').val(this.controller.durationSec());
    $('#frRiaaRev').prop('checked', this.prefs.freqRespReverseRiaa.get());
    $('#frRiaaIec').prop('checked', this.prefs.freqRespIecAmendment.get());
    $('#frRiaaCompare').prop('checked', this.prefs.freqRespCompareMode.get());

    // The L/R radio (.lr.l/.lr.r) reflects the persisted channel-visible state.
    $('#tab-fr .lr.l').toggleClass('on', this.prefs.freqRespLeftVisible.get());
    $('#tab-fr .lr.r').toggleClass('on', this.prefs.freqRespRightVisible.get());

    // FFT size dropdown ↔ freqRespFftSize (index-mapped value list); the label
    // shows the derived sweep duration (Java refreshFftSizeLabel). The size
    // write re-derives freqRespDurationSec via the controller's subscription.
    $('#frFft').val(String(this.prefs.freqRespFftSize.get()));
    $('#frFft').on('change', () => { this.prefs.freqRespFftSize.set(parseInt($('#frFft').val(), 10)); this.refreshFftLabel(); });
    // NOTE: the initial label is set by app.js init's step('refreshFftLabel') AFTER
    // the i18n bundle has loaded — calling t() here (constructor time, before
    // initBase/setLocale) would resolve to the bare key and log a Missing-i18n-key
    // warning. The hardcoded #frFftLabel HTML serves as the pre-init placeholder.

    // Dither dropdown ↔ freqRespDitherBits. Java FreqRespTabControl populates 0..31
    // (index == bit count; 0 → "Off", else the bare number).
    this.rebuildDitherCombo();
    $('#frDither').val(String(this.prefs.freqRespDitherBits.get()));
    $('#frDither').on('change', () => this.prefs.freqRespDitherBits.set(parseInt($('#frDither').val(), 10) || 0));

    // Phase is toggled ONLY via the header graph-up icon (Java FreqRespView) — see the
    // .lr.ico handler below; the RIAA tab has no phase checkbox.
    // RIAA / phase toggles read the live prefs at paint time, so a repaint is all
    // that's needed. Show RIAA is not persisted, so mirror the checkbox into the pref.
    $('#frRiaa').on('change', () => {
      this.prefs.freqRespShowRiaa.set($('#frRiaa').is(':checked'));
      this.refreshRiaaEnable();   // Show gates Reverse / IEC / Compare
      // Show RIAA toggled on while Compare is already armed → fit the compare curve once
      // (mirrors FreqRespView's Show one-shot compare auto-zoom).
      if (this.prefs.freqRespShowRiaa.get() && this.prefs.freqRespCompareMode.get() && this.view.hasAnyResult()) {
        this.view.autoSetupCompare();
      }
    });
    $('#frRiaaRev').on('change', () => { this.prefs.freqRespReverseRiaa.set($('#frRiaaRev').is(':checked')); this.refreshRiaaEnable(); });
    $('#frRiaaIec').on('change', () => { this.prefs.freqRespIecAmendment.set($('#frRiaaIec').is(':checked')); this.refreshRiaaTile(); });
    // Compare mode (Java RIAA tab Compare checkbox): the no-measurement veto + one-shot
    // auto-zoom on entry + repaint. The view reads the live pref at paint time.
    $('#frRiaaCompare').on('change', () => {
      const enable = $('#frRiaaCompare').is(':checked');
      if (enable && !this.view.hasAnyResult()) {
        this.status(t('freqResp.error.compare.noMeasurement'));
        // Veto: roll the pref + checkbox back to off.
        $('#frRiaaCompare').prop('checked', false);
        this.prefs.freqRespCompareMode.set(false);
        return;
      }
      this.prefs.freqRespCompareMode.set(enable);
      if (enable) this.view.autoSetupCompare();   // one-shot fit on entry only
      this.refreshRiaaTile();
      this.view.render();
    });
    // freqRespApplyCalibration has NO manual UI (Java FreqRespTabControl): it is flipped
    // only by the wizard path, so the view divides the loaded .frc out at render time.
    $('#frRiaa, #frRiaaRev, #frRiaaIec').on('change', () => this.view.render());

    // L/R header radio (index.html .lr.l / .lr.r): selects which channel the view
    // shows. Mutually exclusive; persists freqRespLeftVisible / RightVisible.
    $('#tab-fr .lr.l').on('click', () => {
      this.prefs.freqRespLeftVisible.set(true); this.prefs.freqRespRightVisible.set(false); this.prefs.save();
      $('#tab-fr .lr.l').addClass('on'); $('#tab-fr .lr.r').removeClass('on');
      this.view.render();
    });
    $('#tab-fr .lr.r').on('click', () => {
      this.prefs.freqRespRightVisible.set(true); this.prefs.freqRespLeftVisible.set(false); this.prefs.save();
      $('#tab-fr .lr.r').addClass('on'); $('#tab-fr .lr.l').removeClass('on');
      this.view.render();
    });
    // Header phase / auto-setup / maximize icons (Java .lr-tools: phase toggle →
    // freqRespPhaseVisible, auto-setup → autoSetupMagnitudeRange, maximize → resetToDefaultView).
    // Phase icon shows its pressed/lit state like the L/R buttons (.on). Reflect the persisted
    // pref at init AND after each toggle (Java FreqRespView header phase button selected state).
    $('#tab-fr .lr.ico').eq(0).toggleClass('on', this.prefs.freqRespPhaseVisible.get());
    $('#tab-fr .lr.ico').eq(0).on('click', () => {
      const on = !this.prefs.freqRespPhaseVisible.get();
      this.prefs.freqRespPhaseVisible.set(on);
      $('#tab-fr .lr.ico').eq(0).toggleClass('on', on);
      this.view.render();
    });
    $('#tab-fr .lr.ico').eq(1).on('click', () => this.view.autoSetupMagnitudeRange());
    $('#tab-fr .lr.ico').eq(2).on('click', () => this.view.resetToDefaultView());

    // Both the visible strip Play (.fr-play) and the legacy hidden #frRun drive
    // the same sweep; the strip is the one the user sees, #frRun stays for the
    // e2e smoke harness that clicks it directly.
    $('#frRunStrip, #frRun').on('click', () => this.runSweep());
    // Wizard button (Java FreqRespPane wizard launch).
    $('#tab-fr .fr-wizard').on('click', () => this.wizard.open());

    // The compare-smoothing-window pref (changed in the Preferences dialog) refreshes the
    // compare anchor + min/max table (Java FREQRESP_COMPARE_PARAMS_CHANGED).
    this.prefs.freqRespCompareSmoothWindow.addListener(() => this.view.onCompareParamsChanged());

    this.bindPresets();
    this.bindUtility();
    this.bindSaveLoad();
    // The calibration rows + preset list + RIAA enable cascade build DOM text via t(), so
    // they run from seedTabs() in app.js's init AFTER the i18n bundle is loaded (the host is
    // constructed before setLocale — calling t() at construction logs Missing-i18n warnings).
  }

  /** Builds the i18n-dependent tab UI (calibration rows, preset list, RIAA enable). Called
   *  from app.js's init AFTER the i18n bundle is loaded so t() resolves real strings. */
  seedTabs() {
    this.bindCalibration();
    this.refreshPresetList();
    this.refreshRiaaEnable();
  }

  /** Re-runs the RIAA enable cascade (Java FreqRespTabControl.refreshRiaaEnable): Show
   *  gates Reverse / IEC / Compare; Compare additionally needs a measurement present. */
  refreshRiaaEnable() {
    const show = $('#frRiaa').is(':checked');
    $('#frRiaaRev').prop('disabled', !show);
    $('#frRiaaIec').prop('disabled', !show);
    $('#frRiaaCompare').prop('disabled', !(show && this.view.hasAnyResult()));
    this.refreshRiaaTile();
  }

  /** RIAA tab tile chips (Java freqRespTabTiles RIAA branch). */
  refreshRiaaTile() {
    const prefs = this.prefs;
    const chips = [];
    if (prefs.freqRespShowRiaa.get()) {
      chips.push(prefs.freqRespReverseRiaa.get() ? 'rec' : 'play');
      if (prefs.freqRespIecAmendment.get()) chips.push('+IEC');
      if (prefs.freqRespCompareMode.get()) chips.push('comp');
    }
    $('#frRiaaTabSub').html(chips.map((c) => `<span class="tile">${c}</span>`).join(''));
  }

  /** Populates the dither combo with 0..31, where the option index IS the bit count
   *  (0 → "Off", else the bare number) — faithful to Java FreqRespTabControl (the
   *  dither Combo loop, labels hardcoded in Java too). */
  rebuildDitherCombo() {
    let html = '';
    for (let i = 0; i <= 31; i++) html += `<option value="${i}">${i === 0 ? 'Off' : i}</option>`;
    this.$('#frDither').html(html);
  }

  /** "FFT size (D.Ds)": the label shows the controller's DERIVED sweep duration
   *  (Java refreshFftSizeLabel), the part of the FFT window actually swept. */
  refreshFftLabel() {
    const secs = this.controller.durationSec().toFixed(1);
    this.$('#frFftLabel').text(t('freqResp.settings.fftSize') + ' (' + secs + 's)');
    this.$('#frDur').val(this.controller.durationSec());
  }

  /** Drives a single LOG_SWEEP through the DDS, records the loopback, deconvolves, and
   *  shows the result on the view. Mirrors FreqRespPane's Play action. */
  async runSweep() {
    if (this.running) return;
    // Java's FreqRespController.startMeasurement opens the audio device itself, so the sweep
    // runs unconditionally. The web hosts a single shared Web-Audio engine that the sweep
    // commandeers via postGen — it must already be streaming to capture the loopback, and
    // auto-starting it here (device permission + render graph) is non-trivial within that
    // single-engine model. Intended divergence: require the engine running and prompt the user.
    if (!this.engine.running) { this.status('Start the generator first: press the green ▶ on the Multifunctional tab.'); return; }
    this.view.clearResults();   // Java FREQRESP_MEASUREMENT_STARTED clears the chart
    try {
      const stereo = await this.captureAndDeconvolve(false);
      this.stereo = stereo;
      this.view.setStereoResult(stereo);
      this.view.syncScrollbars();
      this.status(`done — ${stereo.left.freqs.length} points captured.`);
    } catch (e) {
      this.status('sweep failed: ' + e.message);
    } finally {
      // Java FREQRESP_MEASUREMENT_STOPPED is always published (success OR abort), and its
      // FreqRespPane handler re-runs refreshRiaaEnable so the enable cascade re-evaluates on
      // BOTH paths — a successful sweep makes Compare available, a failed one leaves it off.
      this.refreshRiaaEnable();
    }
  }

  /** Captures one stereo loopback sweep and deconvolves both channels against the SAME
   *  reference sweep (Java FreqRespAnalyzer). When {@code applyDirect} is true the wizard's
   *  page-1 loopback is divided out of each channel so page 2 displays the DUT alone. Shows
   *  the busy modal + live meter for the capture; the meter is fed post-hoc from the recorded
   *  buffer (windowed RMS walk) since the web records in one shot — see live-meter.js. Returns
   *  the raw StereoFreqRespResult (no calibration baked in). Reused by the wizard. */
  async captureAndDeconvolve(applyDirect) {
    const $ = this.$;
    const f0 = Math.max(1, this.prefs.freqRespStartHz.get());
    const f1 = Math.max(f0 + 1, this.prefs.freqRespStopHz.get());
    const durSec = this.controller.durationSec();
    const leadInSec = Math.max(0, this.prefs.freqRespLeadInSec.get());
    const sampleRate = this.engine.config.inRate;
    const sweepSamples = Math.round(durSec * sampleRate);
    const leadInSamples = Math.round(leadInSec * sampleRate);
    const fade = sweepFadeSamples(sweepSamples);
    const amplitudeVRms = this.prefs.freqRespAmplitudeVrms.get();
    const adcFsVoltageRms = this.prefs.adcFsVoltageRms.get();
    const totalSec = this.controller.expectedMeasurementSeconds();

    this.running = true;
    // The sweep commandeers the live DDS — lock out the generator + sweep Play buttons.
    // Java fires FREQRESP_MEASUREMENT_STARTED so the scope AND FFT panes stop any active
    // Record and gray their LEDs (ScopePane/FftPane.onFreqRespMeasurementStarted): the
    // sweep owns the device. Mirror it — stop a live recording, then disable each Record LED.
    const $scopeRec = $('.scope-pane .led-btn');
    if ($scopeRec.hasClass('rec')) $scopeRec.trigger('click');
    $scopeRec.prop('disabled', true);
    const $fftRec = $('.fft-pane .led-btn');
    if ($fftRec.hasClass('rec')) $fftRec.trigger('click');
    $fftRec.prop('disabled', true);
    // The sweep needs the DAC exclusively — stop a running DDS generator AND file player
    // (Java GeneratorController.stopEngines() on FREQRESP_MEASUREMENT_STARTED). Driving the
    // pane's own Play buttons clears the app.js-owned genRunning flag, drops the ON-AIR banner
    // and resets each "playing" LED exactly as a user stop would — then disable both.
    const $genPlay = $('#genPlay');
    if ($genPlay.hasClass('playing')) $genPlay.trigger('click');
    const $genFilePlay = $('#genFilePlay');
    if ($genFilePlay.hasClass('playing')) $genFilePlay.trigger('click');
    $('#frRunStrip, #frRun, #genPlay, #genFilePlay').prop('disabled', true);
    this.openBusyMeter(totalSec, leadInSec, durSec, f0, f1);
    this.status(`sweeping ${f0}–${f1} Hz over ${durSec}s…`);
    try {
      await this.engine.startCaptureRecording();
      this.engine.postGen({
        logSweep: { f0, f1, sweepSamples, leadInSamples },
        sweepParams: { loop: false, fadeInSamples: fade, fadeOutSamples: fade },
        resetSweepPosition: true,
      });
      this.engine.postGen({ form: GenSignalForm.LOG_SWEEP });
      await new Promise(r => setTimeout(r, totalSec * 1000));
      const rec = await this.engine.stopCaptureRecording();
      this.engine.postGen({ form: this.engine.config.form, frequency: this.engine.snapped });

      // Feed the live meter post-hoc from the recorded buffer so the user sees the level
      // envelope (the web has no per-block analyzer cadence — DEVIATION, see live-meter.js).
      this.feedMeterFromRecording(rec.left, sampleRate, totalSec);

      const sweepRef = renderLogSweep(f0, f1, sweepSamples, sampleRate);
      const freqs = logGrid(f0, f1, this.prefs.freqRespSweepPoints.get());
      const calL = computeFromLogSweep(rec.left, sweepRef, leadInSamples, sampleRate, freqs, amplitudeVRms, adcFsVoltageRms, fade);
      const calR = computeFromLogSweep(rec.right, sweepRef, leadInSamples, sampleRate, freqs, amplitudeVRms, adcFsVoltageRms, fade);
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
      this.running = false;
      // Re-enable the scope + FFT Record LEDs (Java FREQRESP_MEASUREMENT_STOPPED →
      // Scope/FftPane.onFreqRespMeasurementStopped) once the sweep releases the device.
      $('.scope-pane .led-btn').prop('disabled', false);
      $('.fft-pane .led-btn').prop('disabled', false);
      $('#frRunStrip, #frRun, #genPlay, #genFilePlay').prop('disabled', false);
      this.closeBusyMeter();
    }
  }

  /** Opens the busy modal hosting the live level meter for the sweep (Java FreqRespPane
   *  busy shell). The meter geometry feeds its time → instantaneous-frequency mapping. */
  openBusyMeter(totalSec, leadInSec, sweepSec, f0, f1) {
    const cv = document.getElementById('frMeter');
    if (cv) {
      this.busyMeter = new FreqRespLiveMeter(cv, this.prefs, totalSec, leadInSec, sweepSec, f0, f1);
      this.busyMeter.clear();
    }
    const el = document.getElementById('frBusyModal');
    if (el && window.bootstrap) {
      this.busyModal = window.bootstrap.Modal.getOrCreateInstance(el);
      this.busyModal.show();
    }
  }

  /** Drives the live meter from the recorded buffer with a windowed RMS walk at a fixed
   *  block size, so the EMA sees a faithful (time, rmsLin) stream even though the web
   *  records in one shot (DEVIATION from the desktop's per-block cadence — see live-meter.js). */
  feedMeterFromRecording(buf, sampleRate, totalSec) {
    if (!this.busyMeter || !buf || buf.length === 0) return;
    const block = Math.max(1, Math.round(sampleRate * 0.02));   // ~20 ms blocks
    for (let i = 0; i + block <= buf.length; i += block) {
      let sum = 0.0;
      for (let j = 0; j < block; j++) { const v = buf[i + j]; sum += v * v; }
      const rms = Math.sqrt(sum / block);
      this.busyMeter.appendSample((i + block) / sampleRate, rms);
    }
  }

  /** Closes the busy modal + drops the meter (Java closeBusyShell). */
  closeBusyMeter() {
    if (this.busyModal) { this.busyModal.hide(); this.busyModal = null; }
    this.busyMeter = null;
  }

  /** Repaints the interactive view (kept for app.js's init `freqResp` step + any
   *  external repaint trigger). The view owns the canvas now. */
  plot() {
    this.view.render();
    this.view.syncScrollbars();
  }

  // ===========================================================================
  // Presets tab (Java FreqRespTabControl.buildPresetsTab → PresetBar)
  // ===========================================================================

  bindPresets() {
    const $ = this.$, prefs = this.prefs;
    $('#frPresetName').on('input', () => this.refreshPresetButtons());
    $('#frPresetMenu').on('click', '.dropdown-item', (ev) => { $('#frPresetName').val($(ev.currentTarget).text()); this.refreshPresetButtons(); });
    $('#frPresetSave').on('click', async () => {
      const name = ($('#frPresetName').val() || '').trim();
      if (!name) return;
      if (prefs.freqRespPresets.has(name)) {
        const ok = await this.showConfirm(t('freqResp.presets.overwrite.title'), t('freqResp.presets.overwrite.message', name));
        if (!ok) return;
      }
      prefs.putFreqRespPreset(name, this.captureFreqRespPreset());
      $('#frPresetName').val(name);
      this.refreshPresetList();
    });
    $('#frPresetLoad').on('click', () => {
      const p = prefs.freqRespPresets.get(($('#frPresetName').val() || '').trim());
      if (p) { this.applyFreqRespPreset(p); this.refreshPresetButtons(); }
    });
    $('#frPresetDelete').on('click', async () => {
      const name = ($('#frPresetName').val() || '').trim();
      if (!prefs.freqRespPresets.has(name)) return;
      const ok = await this.showConfirm(t('freqResp.presets.delete.title'), t('freqResp.presets.delete.message', name));
      if (!ok) return;
      prefs.removeFreqRespPreset(name); this.refreshPresetList();
    });
    this.refreshPresetList();
  }

  /** Captures every Settings + RIAA pref into a FreqRespPreset (Java captureCurrentFreqRespPreset). */
  captureFreqRespPreset() {
    const prefs = this.prefs;
    const p = new FreqRespPreset();
    p.startHz = prefs.freqRespStartHz.get();
    p.stopHz = prefs.freqRespStopHz.get();
    p.amplitudeVrms = prefs.freqRespAmplitudeVrms.get();
    p.sweepPoints = prefs.freqRespSweepPoints.get();
    p.fftSize = prefs.freqRespFftSize.get();
    p.leadInSec = prefs.freqRespLeadInSec.get();
    p.ditherBits = prefs.freqRespDitherBits.get();
    p.showRiaa = prefs.freqRespShowRiaa.get();
    p.reverseRiaa = prefs.freqRespReverseRiaa.get();
    p.iecAmendment = prefs.freqRespIecAmendment.get();
    p.compareMode = prefs.freqRespCompareMode.get();
    return p;
  }

  /** Applies a FreqRespPreset to the prefs + reflects it into the UI (Java applyFreqRespPreset). */
  applyFreqRespPreset(p) {
    const prefs = this.prefs;
    prefs.freqRespStartHz.set(p.startHz);
    prefs.freqRespStopHz.set(p.stopHz);
    prefs.freqRespAmplitudeVrms.set(p.amplitudeVrms);
    prefs.freqRespSweepPoints.set(p.sweepPoints);
    prefs.freqRespFftSize.set(p.fftSize);   // re-derives durationSec via the controller subscription
    prefs.freqRespLeadInSec.set(p.leadInSec);
    prefs.freqRespDitherBits.set(p.ditherBits);
    prefs.freqRespShowRiaa.set(p.showRiaa);
    prefs.freqRespReverseRiaa.set(p.reverseRiaa);
    prefs.freqRespIecAmendment.set(p.iecAmendment);
    prefs.freqRespCompareMode.set(p.compareMode);
    prefs.save();
    // Reflect the recalled prefs into the live widgets (the NumericStepFields are
    // owned by app.js; reseed the ones the host owns + the derived label).
    const $ = this.$;
    $('#frStart').val(p.startHz); $('#frStop').val(p.stopHz); $('#frAmp').val(p.amplitudeVrms);
    $('#frPoints').val(p.sweepPoints); $('#frLeadIn').val(p.leadInSec);
    $('#frFft').val(String(p.fftSize)); $('#frDither').val(String(p.ditherBits));
    $('#frRiaa').prop('checked', p.showRiaa); $('#frRiaaRev').prop('checked', p.reverseRiaa);
    $('#frRiaaIec').prop('checked', p.iecAmendment); $('#frRiaaCompare').prop('checked', p.compareMode);
    this.refreshFftLabel();
    this.refreshRiaaEnable();
    // Java reflects the compare-mode pref through a two-way bind whose onChange fires the
    // one-shot autoSetupCompare on entry. jQuery .prop('checked') fires no change event, so
    // mirror that effect explicitly when the recalled preset lands in compare+Show with a result.
    if (this.prefs.freqRespShowRiaa.get() && this.prefs.freqRespCompareMode.get() && this.view.hasAnyResult()) {
      this.view.autoSetupCompare();
    }
    this.view.render();
  }

  refreshPresetList() {
    const $menu = this.$('#frPresetMenu').empty();
    const names = [...this.prefs.freqRespPresets.keys()];
    for (const name of names) this.$('<li>').append(this.$('<button type="button" class="dropdown-item">').text(name)).appendTo($menu);
    this.$('#frPresetMenuBtn').prop('disabled', names.length === 0);
    this.$('#frPresetTabSub').html(names.length === 0 ? '' : `<span class="tile">${names.length} saved</span>`);
    this.refreshPresetButtons();
  }

  refreshPresetButtons() {
    const name = (this.$('#frPresetName').val() || '').trim();
    const $s = this.$('#frPresetSave'), $l = this.$('#frPresetLoad'), $d = this.$('#frPresetDelete');
    if (!name) { $s.prop('disabled', true); $l.prop('disabled', true); $d.prop('disabled', true); return; }
    const existing = this.prefs.freqRespPresets.get(name);
    if (!existing) { $s.prop('disabled', false); $l.prop('disabled', true); $d.prop('disabled', true); }
    else {
      $s.prop('disabled', JSON.stringify(existing) === JSON.stringify(this.captureFreqRespPreset()));
      $l.prop('disabled', false); $d.prop('disabled', false);
    }
  }

  // ===========================================================================
  // Utility tab (Java FreqRespTabControl.buildUtilityTab): screenshot-only here —
  // the desktop DAC/ADC calibrate buttons are stubs (acceptable parity).
  // ===========================================================================

  bindUtility() {
    this.$('#frShot').on('click', () => {
      const cv = document.getElementById('frPlot');
      if (!cv) return;
      cv.toBlob((blob) => {
        if (!blob) return;
        const a = document.createElement('a');
        a.href = URL.createObjectURL(blob); a.download = 'freqresp.png';
        document.body.appendChild(a); a.click(); a.remove();
        setTimeout(() => URL.revokeObjectURL(a.href), 1000);
      }, 'image/png');
    });
    // DAC / ADC calibrate — desktop stubs (dialog wired in a Phase 6 follow-up).
    this.$('#frCalDac').on('click', () => console.info('FreqResp DAC-cal clicked (stub)'));
    this.$('#frCalAdc').on('click', () => console.info('FreqResp ADC-cal clicked (stub)'));
  }

  // ===========================================================================
  // Save-to / Load-from tabs (Java buildSaveToTab / buildLoadFromTab)
  // ===========================================================================

  bindSaveLoad() {
    const $ = this.$, prefs = this.prefs;
    const savePath = prefs.freqRespSavePath.get();
    $('#frSavePath').val(savePath || '');
    const loadPath = prefs.freqRespLoadPath.get();
    $('#frLoadPath').val(loadPath || '');
    $('#frSaveBtn').on('click', () => this.saveMeasurement());
    $('#frLoadBtn').on('click', () => this.loadMeasurement());
  }

  /** Writes the current measurement (BOTH channels) to a .frc (Java openSaveDialog). When
   *  one channel is hidden the visible one is duplicated into the missing slot so the file
   *  round-trips as strict stereo. */
  async saveMeasurement() {
    const left = this.view.getLeftResultOrNull();
    const right = this.view.getRightResultOrNull();
    if (!left && !right) { this.status(t('freqResp.saveTo.error.noResult')); return; }
    const primary = left || right;
    let other = left ? right : left;
    if (!other) other = primary;
    const stereo = {
      left: { freqs: primary.freqs, magLin: primary.magLin, phaseRad: primary.phaseRad },
      right: { freqs: other.freqs, magLin: other.magLin, phaseRad: other.phaseRad },
    };
    const p = primary.sweepParams || {};
    const text = saveFrc(stereo, {
      sampleRate: primary.sampleRate,
      sweepStart: p.startHz || primary.freqs[0],
      sweepEnd: p.stopHz || primary.freqs[primary.freqs.length - 1],
      sweepPoints: p.sweepPoints || primary.freqs.length,
      amplitudeVRms: p.amplitudeVrms || 0,
    });
    try {
      const res = await this.io.saveFile(text, 'freqresp.frc', FRC_TYPE);
      if (res.saved) {
        this.$('#frSavePath').val(res.name);
        this.prefs.freqRespSavePath.set(res.name); this.prefs.save();
        this.status('saved ' + res.name);
        // If this .frc is loaded as a calibration row, reload it so the new curve applies.
        this.onCalibrationFileSaved(res.name);
      }
    } catch (e) { this.status(t('freqResp.error.measurement.save', e.message)); }
  }

  /** Loads a saved measurement onto the view as a static L/R trace with calibrationApplied
   *  (Java openLoadDialog) and auto-fits the range. */
  async loadMeasurement() {
    try {
      const f = await this.io.openFile(FRC_TYPE);
      if (!f) return;
      const st = loadFrc(this.io.bytesToText(f.bytes));
      const cL = st.left, cR = st.right;
      const params = { startHz: cL.freqs[0], stopHz: cL.freqs[cL.freqs.length - 1], sweepPoints: cL.freqs.length, durationSec: 0, leadInSec: 0, amplitudeVrms: 0 };
      const sr = this.engine.config.inRate;
      this.view.setLeftResult(makeFreqRespResult('L', sr, cL.freqs, cL.magLin, cL.phaseRad, params, f.name, true));
      this.view.setRightResult(makeFreqRespResult('R', sr, cR.freqs, cR.magLin, cR.phaseRad, params, f.name, true));
      this.view.sourceFilePath = f.name;
      this.refreshRiaaEnable();   // a loaded measurement counts as "has result"
      this.view.autoSetupMagnitudeRange();
      this.$('#frLoadPath').val(f.name);
      this.prefs.freqRespLoadPath.set(f.name); this.prefs.save();
      this.status(`loaded ${f.name} (${cL.freqs.length} points)`);
    } catch (e) { this.status(t('freqResp.error.measurement.load', e.message)); }
  }

  // ===========================================================================
  // Calibration tab (Java FreqRespTabControl.buildCalibrationTab): multi-row .frc loader
  // ===========================================================================

  bindCalibration() {
    const prefs = this.prefs;
    // Build the rows from prefs (always at least row 0). A persisted row carries only a
    // path; the browser sandbox can't re-read a file by stored path (no bytes), so the row
    // shows the remembered path but stays un-loaded until the user re-picks the file with
    // the load button — DEVIATION from the desktop, which re-reads from disk at startup.
    if (prefs.freqRespCalibrations.length === 0) prefs.addFreqRespCalibration(new CalibrationEntry());
    for (const entry of prefs.freqRespCalibrations) {
      const row = this.createCalRowUi(entry);
      if (entry.path) { row.$path.val(entry.path).attr('title', entry.path); }
      this.updateCalRowEnable(row);
    }
    this.syncStoreFromRows();
  }

  /** Builds + appends a fresh calibration row. Row 0 has no Remove button (its grid cell
   *  is left empty) so the column widths stay aligned (Java createRowUi). */
  createCalRowUi(entry) {
    const $ = this.$;
    const isRow0 = this._calRows.length === 0;
    const $row = $('<div class="fr-cal-row">');
    const $path = $('<input class="form-control form-control-sm fr-cal-path" type="text" readonly>')
      .val(t('freqResp.calibration.path.none')).attr('title', t('freqResp.calibration.path.tooltip'));
    const $active = $('<input class="form-check-input fr-cal-active" type="checkbox">').attr('title', t('fft.calibration.active.tooltip'));
    const $activeLbl = $('<label class="form-check-label small">').append($active).append(' ' + t('fft.calibration.active'));
    const $load = $('<button class="btn btn-sm btn-outline-secondary" type="button">').attr('title', t('freqResp.calibration.load.tooltip')).html('<img src="assets/icons/folder-open.svg" class="util-svg" alt="">');
    // Clear is destructive — red outline + red X (Java desktop's red clear button).
    const $clear = $('<button class="btn btn-sm btn-outline-danger" type="button">').attr('title', t('freqResp.calibration.clear.tooltip')).html('<img src="assets/icons/rectangle-xmark.svg" class="util-svg" alt="">');
    const $add = $('<button class="btn btn-sm btn-outline-secondary" type="button">').attr('title', t('freqResp.calibration.add.tooltip')).html('<img src="assets/icons/plus.svg" class="util-svg" alt="">');
    const $remove = $('<button class="btn btn-sm btn-outline-secondary" type="button">').attr('title', t('freqResp.calibration.remove.tooltip')).html('<img src="assets/icons/minus.svg" class="util-svg" alt="">');
    if (isRow0) $remove.css('visibility', 'hidden');   // placeholder keeps the columns aligned
    $row.append($path, $activeLbl, $load, $clear, $add, $remove);
    this.$('#frCalRows').append($row);

    const row = { $row, $path, $active, calibration: null, entry };
    this._calRows.push(row);

    // Active toggle is two-way bound to entry.active(); only an enabled + loaded row pushes.
    $active.prop('checked', entry.active().get());
    $active.on('change', () => { entry.active().set($active.is(':checked')); this.updateCalRowEnable(row); this.syncStoreFromRows(); });
    $load.on('click', () => this.userLoadInRow(row));
    $clear.on('click', () => this.userClearRow(row));
    $add.on('click', () => this.userAddRow());
    if (!isRow0) $remove.on('click', () => this.userRemoveRow(row));
    this.updateCalRowEnable(row);
    return row;
  }

  /** The Active checkbox is enabled only when a file is loaded into the row (Java updateCalRowEnable). */
  updateCalRowEnable(row) {
    const loaded = row.entry.path != null && row.calibration != null;
    row.$active.prop('disabled', !loaded);
  }

  async userLoadInRow(row) {
    try {
      const f = await this.io.openFile(FRC_TYPE);
      if (!f) return;
      if (!this.loadFileIntoRow(row, this.io.bytesToText(f.bytes), f.name)) return;
      this.syncStoreFromRows();
      this.prefs.save();
    } catch (e) { this.status(t('freqResp.error.calibration.load', e.message)); }
  }

  /** Parses .frc text into the row + records the path (Java loadFileIntoRow). Returns true
   *  when the calibration parsed, so the caller pushes it into the store. */
  loadFileIntoRow(row, text, path) {
    try {
      row.calibration = loadFrc(text);
      row.entry.path = path;
      row.$path.val(path).attr('title', path);
      this.updateCalRowEnable(row);
      return true;
    } catch (e) {
      this.status(t('freqResp.error.calibration.load', e.message));
      return false;
    }
  }

  userClearRow(row) {
    row.calibration = null;
    row.entry.path = null;
    row.$path.val(t('freqResp.calibration.path.none')).attr('title', '');
    this.updateCalRowEnable(row);
    this.syncStoreFromRows();
    this.prefs.save();
  }

  userAddRow() {
    const entry = new CalibrationEntry();
    this.prefs.addFreqRespCalibration(entry);
    this.createCalRowUi(entry);
  }

  userRemoveRow(row) {
    if (this._calRows.length <= 1) return;
    const idx = this._calRows.indexOf(row);
    if (idx <= 0) return;   // never remove row 0
    this._calRows.splice(idx, 1);
    this.prefs.removeFreqRespCalibration(row.entry);
    row.$row.remove();
    this.syncStoreFromRows();
  }

  /** Pushes the active + loaded rows into the correction store, in order (Java syncStoreFromRows). */
  syncStoreFromRows() {
    this._calMutationInFlight = true;
    try {
      this.correctionStore.clearAll();
      for (const r of this._calRows) {
        if (r.calibration && r.entry.path && r.entry.active().get()) {
          this.correctionStore.addEntry(r.calibration, r.entry.path);
        }
      }
    } finally {
      this._calMutationInFlight = false;
    }
    this.refreshCalTile();
  }

  /** Re-reads any loaded row referencing the just-saved .frc so the new curve takes effect
   *  immediately (Java onCalibrationFileSaved). The browser sandbox can't re-read by path, so
   *  this only refreshes the store + tile from the in-memory rows. */
  onCalibrationFileSaved(path) {
    let touched = false;
    for (const r of this._calRows) {
      if (r.entry.path === path) touched = true;
    }
    if (touched) this.syncStoreFromRows();
  }

  refreshCalTile() {
    const n = this.correctionStore.getEntries().length;
    this.$('#frCalTabSub').html(n <= 0 ? '' : `<span class="tile">${n === 1 ? t('calibration.tile.loaded') : t('calibration.tile.loadedN', n)}</span>`);
  }

  /** Store-changed handler: retraces the view, and rebuilds the calibration-tab rows when
   *  the change came from OUTSIDE the tab (Java FreqRespTabControl.onCalibrationChanged —
   *  skips the rebuild for the control's own writes). */
  onStoreChanged() {
    if (this.view) this.view.onCalibrationChanged();
    if (!this._calMutationInFlight && this._calRows && this._calRows.length) {
      this.rebuildRowsFromStore();
    }
    this.refreshCalTile();
  }

  /** Rebuilds the row UI from the store's entries, dropping user-added empty rows — exactly
   *  one row per loaded entry, row 0 always present (Java rebuildRowsFromStore). No-op when
   *  the loaded rows already line up with the store (so an unrelated event — e.g. the wizard's
   *  setDirect, which doesn't touch entries — preserves user-added empty rows). */
  rebuildRowsFromStore() {
    const $ = this.$, prefs = this.prefs;
    const entries = this.correctionStore.getEntries();
    if (this.loadedRowsMatch(entries)) return;
    for (const r of this._calRows) r.$row.remove();
    this._calRows = [];
    prefs.freqRespCalibrations.length = 0;
    const rowCount = Math.max(1, entries.length);
    for (let i = 0; i < rowCount; i++) {
      const entry = (i < entries.length) ? new CalibrationEntry(entries[i].path, true, false) : new CalibrationEntry();
      prefs.addFreqRespCalibration(entry);
      const row = this.createCalRowUi(entry);
      if (i < entries.length) {
        row.calibration = entries[i].calibration;
        row.$path.val(entries[i].path).attr('title', entries[i].path);
        this.updateCalRowEnable(row);
      }
    }
    prefs.save();
  }

  /** True when the loaded subset of rows equals `entries`, in order (Java loadedRowsMatch). */
  loadedRowsMatch(entries) {
    let j = 0;
    for (const r of this._calRows) {
      if (!r.calibration) continue;
      if (j >= entries.length) return false;
      const e = entries[j];
      if (r.calibration !== e.calibration) return false;
      if (!r.entry.path || r.entry.path !== e.path) return false;
      j++;
    }
    return j === entries.length;
  }

  /** Bootstrap confirm modal wrapper (mirrors app.js showConfirm). */
  showConfirm(title, message) {
    return new Promise((resolve) => {
      const el = document.getElementById('confirmModal');
      if (!el || !window.bootstrap) { resolve(window.confirm(message)); return; }
      this.$('#confirmTitle').text(title); this.$('#confirmMessage').text(message);
      const modal = window.bootstrap.Modal.getOrCreateInstance(el);
      let done = false;
      const onOk = () => { if (done) return; done = true; modal.hide(); resolve(true); };
      const onHidden = () => { el.removeEventListener('hidden.bs.modal', onHidden); this.$('#confirmOk').off('click', onOk); if (!done) resolve(false); };
      this.$('#confirmOk').on('click', onOk);
      el.addEventListener('hidden.bs.modal', onHidden);
      modal.show();
    });
  }
}
