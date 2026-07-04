/*
 * Phonalyser web — the Frequency-Response calibration wizard (3-page guided flow:
 * loopback DAC→ADC · device-under-test · save + apply).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of org.edgo.audio.measure.gui.freqresp.FreqRespWizardDialog. A modal
 * walks the user through measuring the DAC→ADC loopback transfer (page 1 → store.setDirect
 * so it divides out at display), then the device under test (page 2, sweep with the page-1
 * loopback divided out), finally saving the result as a .frc (page 3 Save → divide the DUT
 * by the direct loopback → saveFrc → notify) and applying it as the active calibration
 * (page 3 Apply → loadFrc → store.setCurrent → set the primary-calibration-path +
 * apply-calibration prefs). Cancel discards every measurement taken inside the wizard and
 * restores the correction store to the snapshot captured at open().
 *
 * Reuses the host's sweep runner (captureAndDeconvolve, which shows the busy meter) and the
 * shared confirm dialog. The desktop runs a blocking SWT dialog with its own busy shell; the
 * web reuses the host's busy modal between the wizard's own modal (it's hidden while the
 * sweep's busy modal is up, then re-shown). The DEVIATION is purely structural (Bootstrap
 * modal vs SWT Shell, async vs blocking); the page flow + save/apply/cancel logic mirror the
 * desktop exactly.
 */

import { divideInPlace } from './deconvolve.js';
import { saveFrc, loadFrc } from '../io/frc.js';
import { t } from '../i18n/i18n.js';

const FRC_TYPE = [{ description: 'Filter calibration', accept: 'text/plain', extensions: ['.frc'] }];

export class FreqRespWizard {
  /**
   * @param {import('../shell/freqresp-host.js').FreqRespHost} host the host (sweep runner + io + confirm)
   * @param {object} prefs Preferences.instance()
   * @param {import('./freqresp-view.js').FreqRespView} view the shared view
   * @param {import('./correction-store.js').FreqRespCorrectionStore} store the shared correction store
   */
  constructor(host, prefs, view, store) {
    this.host = host;
    this.prefs = prefs;
    this.view = view;
    this.store = store;
    this.$ = window.jQuery;

    this.directResult = null;        // page-1 loopback StereoFreqRespResult
    this.dutResult = null;           // page-2 DUT StereoFreqRespResult
    this.savedCalPath = null;
    this.savedCalText = null;        // the saved .frc text (for in-sandbox Apply without re-read)
    this.unsavedDirty = false;
    this.appliedSuccessfully = false;
    this.preWizardSnapshot = null;
    this.currentPageIndex = 0;
    this._bound = false;
  }

  /** Opens the wizard. Captures a pre-wizard snapshot of the store so Cancel can restore it. */
  open() {
    const el = document.getElementById('frWizardModal');
    if (!el || !window.bootstrap) return;
    this.preWizardSnapshot = this.store.snapshot();
    this.directResult = null;
    this.dutResult = null;
    this.savedCalPath = null;
    this.savedCalText = null;
    this.unsavedDirty = false;
    this.appliedSuccessfully = false;
    this.currentPageIndex = 0;
    this.$('#frWizApply').prop('disabled', true);
    if (!this._bound) { this.bind(el); this._bound = true; }
    this.showPage(0);
    this.modal = window.bootstrap.Modal.getOrCreateInstance(el);
    this.modal.show();
  }

  bind(el) {
    const $ = this.$;
    $('#frWizPlay1').on('click', () => this.runMeasurement(true));
    $('#frWizPlay2').on('click', () => this.runMeasurement(false));
    $('#frWizSave').on('click', () => this.doSaveCalibration());
    $('#frWizApply').on('click', () => this.doApplyCalibration());
    $('#frWizBack').on('click', () => { if (this.currentPageIndex > 0) this.showPage(this.currentPageIndex - 1); });
    $('#frWizNext').on('click', () => { if (this.currentPageIndex < 2) this.showPage(this.currentPageIndex + 1); });
    $('#frWizCancel').on('click', async () => { if (await this.handleCancel()) this.close(); });
    // Closing via the ✕ / backdrop / Esc routes through the SAME guarded handleCancel as the
    // Cancel button (Java SWT.Close → e.doit = handleCancel()): when there is unsaved data we
    // veto the default hide, await the confirm, and only restore+close on Yes — on No the modal
    // stays open (re-shown, since Bootstrap had already begun hiding it).
    el.addEventListener('hide.bs.modal', (e) => {
      if (this._closing) return;       // an internal close() already restored / committed
      if (this.appliedSuccessfully) return;   // Apply committed; close() drives this path
      const needsConfirm = this.directResult != null || this.dutResult != null || this.unsavedDirty;
      if (!needsConfirm) { this.store.restore(this.preWizardSnapshot); return; }
      // Veto the hide, then resolve the guarded decision asynchronously.
      e.preventDefault();
      this.handleCancel().then((ok) => { if (ok) this.close(); else this.modal.show(); });
    });
  }

  showPage(idx) {
    this.currentPageIndex = idx;
    const $ = this.$;
    $('#frWizPage1').toggleClass('d-none', idx !== 0);
    $('#frWizPage2').toggleClass('d-none', idx !== 1);
    $('#frWizPage3').toggleClass('d-none', idx !== 2);
    this.refreshNavEnable();
  }

  refreshNavEnable() {
    const $ = this.$;
    $('#frWizBack').prop('disabled', this.currentPageIndex <= 0);
    let nextEnabled = false;
    if (this.currentPageIndex === 0) nextEnabled = this.directResult != null;
    else if (this.currentPageIndex === 1) nextEnabled = this.dutResult != null;
    $('#frWizNext').prop('disabled', !nextEnabled);
  }

  /** Runs a sweep for the wizard. Page 1 stores the loopback as the `direct` calibration;
   *  page 2 sweeps with the page-1 loopback divided out so the DUT shows alone (Java runMeasurement). */
  async runMeasurement(directLeg) {
    if (this.host.running) return;
    // No engine-running precondition: captureAndDeconvolve owns the full measurement
    // lifecycle (publish STARTED → wait idle → own playback + capture), so the wizard
    // sweep works from ANY prior state, exactly like the Play button.
    const $ = this.$;
    $('#frWizPlay1, #frWizPlay2, #frWizBack, #frWizNext').prop('disabled', true);
    // Hide the wizard while the busy meter modal is up (one modal at a time).
    if (this.modal) this.modal.hide();
    this._closing = true;   // suppress the hide→restore path: this is an internal hide
    try {
      // Page 2 divides out the page-1 loopback (applyDirect); page 1 captures it raw.
      const r = await this.host.captureAndDeconvolve(!directLeg);
      this.onMeasurementDone(directLeg, r, null);
    } catch (e) {
      this.onMeasurementDone(directLeg, null, e.message || String(e));
    } finally {
      this._closing = false;
      if (this.modal) this.modal.show();   // re-show the wizard after the sweep
    }
  }

  onMeasurementDone(directLeg, r, error) {
    const $ = this.$;
    $('#frWizPlay1, #frWizPlay2').prop('disabled', false);
    if (error != null) {
      this.host.status(t('freqResp.wizard.error.measureFailed', error));
      this.refreshNavEnable();
      return;
    }
    if (r == null) { this.refreshNavEnable(); return; }
    this.unsavedDirty = true;
    if (directLeg) {
      this.directResult = r;
      // Only the direct (transient) slot — the view applies it alongside the entries list so
      // page 2 gets the page-1 loopback subtracted without polluting the calibration tab.
      this.store.setDirect(this.stereoCalFromResult(r));
      this.view.setLeftResult(r.left);
      this.view.setRightResult(r.right);
      this.view.sourceFilePath = null;
      this.showPage(1);
    } else {
      this.dutResult = r;
      this.view.setLeftResult(r.left);
      this.view.setRightResult(r.right);
      this.showPage(2);
    }
  }

  /** Wraps both channels of a stereo result into a StereoFreqRespCalibration {left,right}. */
  stereoCalFromResult(r) {
    return {
      left: { freqs: r.left.freqs, magLin: r.left.magLin, phaseRad: r.left.phaseRad },
      right: { freqs: r.right.freqs, magLin: r.right.magLin, phaseRad: r.right.phaseRad },
    };
  }

  /** Page-3 Save: divide the DUT by the page-1 direct loopback, then write the .frc (Java doSaveCalibration). */
  async doSaveCalibration() {
    if (!this.dutResult) { this.host.status(t('freqResp.saveTo.error.noResult')); return; }
    // Clone the DUT cal (don't mutate the displayed result) and divide out the direct loopback.
    const stereoCal = {
      left: { freqs: this.dutResult.left.freqs, magLin: Float64Array.from(this.dutResult.left.magLin), phaseRad: Float64Array.from(this.dutResult.left.phaseRad) },
      right: { freqs: this.dutResult.right.freqs, magLin: Float64Array.from(this.dutResult.right.magLin), phaseRad: Float64Array.from(this.dutResult.right.phaseRad) },
    };
    const direct = this.store.getDirect();
    if (direct) {
      divideInPlace(stereoCal.left, direct.left);
      divideInPlace(stereoCal.right, direct.right);
    }
    const p = this.dutResult.left.sweepParams || {};
    const text = saveFrc(stereoCal, {
      sampleRate: this.dutResult.left.sampleRate,
      sweepStart: p.startHz || stereoCal.left.freqs[0],
      sweepEnd: p.stopHz || stereoCal.left.freqs[stereoCal.left.freqs.length - 1],
      sweepPoints: p.sweepPoints || stereoCal.left.freqs.length,
      amplitudeVRms: p.amplitudeVrms || 0,
    });
    try {
      const res = await this.host.io.saveFile(text, 'freqresp_cal.frc', FRC_TYPE);
      if (!res.saved) return;
      this.savedCalPath = res.name;
      this.savedCalText = text;   // keep the text so Apply can reload without re-reading the file
      this.unsavedDirty = false;
      this.$('#frWizApply').prop('disabled', false);
      this.host.status('saved ' + res.name);
    } catch (e) { this.host.status(t('freqResp.error.measurement.save', e.message)); }
  }

  /** Page-3 Apply: load the saved .frc as the active calibration (Java doApplyCalibration). */
  doApplyCalibration() {
    if (this.savedCalText == null) return;
    try {
      const cal = loadFrc(this.savedCalText);
      this.store.setCurrent(cal, this.savedCalPath);
      this.prefs.setFreqRespPrimaryCalibrationPath(this.savedCalPath);
      this.prefs.freqRespApplyCalibration.set(true);
      this.prefs.save();
      this.unsavedDirty = false;
      this.appliedSuccessfully = true;   // suppress the "unsaved?" prompt + the restore
      this.close();
    } catch (e) { this.host.status(t('freqResp.error.calibration.load', e.message)); }
  }

  /** Cancel guard (Java boolean handleCancel): confirms when there's unsaved data, restores
   *  the pre-wizard snapshot, and returns whether the wizard is allowed to close. After Apply
   *  the calibration is committed (appliedSuccessfully) → skip the prompt and the restore.
   *  Returns false (veto) when the user answers No; the caller keeps the modal open. */
  async handleCancel() {
    if (this.appliedSuccessfully) return true;
    if (this.directResult != null || this.dutResult != null || this.unsavedDirty) {
      const ok = await this.host.showConfirm(t('freqResp.wizard.unsaved.title'), t('freqResp.wizard.unsaved.message'));
      if (!ok) return false;
    }
    this.store.restore(this.preWizardSnapshot);
    return true;
  }

  /** Closes the modal without going through the cancel-restore path (Apply / a handleCancel
   *  that already restored). The _closing guard suppresses the hide handler's own restore. */
  close() {
    this._closing = true;
    if (this.modal) this.modal.hide();
    this._closing = false;
  }
}
