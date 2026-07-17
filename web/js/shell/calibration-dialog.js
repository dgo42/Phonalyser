/*
 * Phonalyser web — unified ADC/DAC full-scale calibration dialog.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of gui/common/CalibrationDialog — the ONE modal that replaces the desktop's
 * (deleted) AdcCalibrationDialog + DacCalibrationDialog, opened from the scope pane, the FFT
 * pane and the generator. One shape, always two rows — a Left row and a Right row, each
 * [channel label] [AMPLITUDE NumericStepField]. A null seed disables + blanks its row (the
 * ADC's non-measured channel); a non-null seed prefills + enables it. On OK every enabled row
 * whose field carries a positive value fires — an invalid enabled field rejects the whole
 * submit (CalibrationDialog.onOk).
 *
 * The per-opener wording (title / prompt / tooltip) is the desktop's CalibrationDialog.Texts.
 * The OK WRITE routes through DeviceProfileStore.storeAdc/DacCalibration (NOT the raw prefs
 * setters): a bound stereo card writes only the measured channel (per-channel), a MONO card or
 * an unbound device writes the shared both-channels full-scale — exactly ScopeTabControl /
 * FftTabControl.openCalibrationDialog (ADC) and GeneratorPane.openDacCalibrationDialog (DAC).
 * The desktop reads the device NAME off BackendPrefs; the web slot holds a Web Audio deviceId,
 * so the resolvable human LABEL is threaded in (inputLabel / outputLabel).
 */
import { t } from '../i18n/i18n.js';

export class CalibrationDialog {
  /**
   * @param engine the AudioEngine (live generator retune after a DAC calibrate).
   * @param prefs  Preferences (per-channel full-scale getters + save).
   * @param deviceStore the DeviceProfileStore (the calibrate write path).
   * @param deps  {getLeftField, getRightField, inputLabel, outputLabel}
   *   - getLeftField / getRightField: () => the Left / Right NumericStepField (canonical Vrms).
   *   - inputLabel / outputLabel: () => the current in/out device LABEL (recognition patterns
   *     match the human label, not the deviceId the <select> value carries).
   */
  constructor(engine, prefs, deviceStore, { getLeftField, getRightField, inputLabel, outputLabel }) {
    this.engine = engine;
    this.prefs = prefs;
    this.deviceStore = deviceStore;
    this._getLeftField = getLeftField;
    this._getRightField = getRightField;
    this._inputLabel = inputLabel;
    this._outputLabel = outputLabel;
    // The onCalibrate committed for the currently open dialog; replaced per open().
    this._onCalibrate = null;
    // Which rows are enabled this open (a null seed → disabled + skipped on OK).
    this._leftEnabled = false;
    this._rightEnabled = false;
    this._okBound = false;
  }

  /** Wires the OK button once and the generator's DAC-calibrate button. */
  bind() {
    if (!this._okBound) {
      $('#calOk').on('click', () => this._onOk());
      this._okBound = true;
    }
    $('#calibrateDac').on('click', () => this.openDac());
    return this;
  }

  _modal() {
    return window.bootstrap.Modal.getOrCreateInstance(document.getElementById('calibrationModal'));
  }

  /** Seeds one row: a non-null seed prefills + enables the field; a null seed blanks +
   *  disables it (its channel has no measurement / configuration to calibrate against). */
  _seedRow(field, seed, tooltipKey) {
    if (!field) return false;
    field.tooltipBase = t(tooltipKey);
    if (seed != null) {
      field.setDisabled(false);
      field.setValue(seed);
      return true;
    }
    field.setDisabled(true);
    field.input.value = '';   // CalibrationDialog leaves a null-seed row blank, not clamped-to-min
    return false;
  }

  /**
   * The generic two-row open (CalibrationDialog constructor): sets the per-opener wording,
   * seeds + enables/disables each row, and stores the commit callback for OK.
   * @param {{titleKey, promptKey, tooltipKey, seedLeft:?number, seedRight:?number,
   *          onCalibrate:(channel:'L'|'R', value:number)=>void}} cfg
   */
  open(cfg) {
    $('#calTitle').text(t(cfg.titleKey));
    $('#calPrompt').text(t(cfg.promptKey));
    $('#calError').addClass('d-none');
    this._leftEnabled = this._seedRow(this._getLeftField(), cfg.seedLeft, cfg.tooltipKey);
    this._rightEnabled = this._seedRow(this._getRightField(), cfg.seedRight, cfg.tooltipKey);
    this._onCalibrate = cfg.onCalibrate;
    this._modal().show();
  }

  /** OK: commit + validate every enabled row, then fire onCalibrate for each. An invalid
   *  enabled field rejects the whole submit and keeps the dialog open (CalibrationDialog.onOk). */
  _onOk() {
    const committed = [];
    const rows = [
      { ch: 'L', field: this._getLeftField(), enabled: this._leftEnabled },
      { ch: 'R', field: this._getRightField(), enabled: this._rightEnabled },
    ];
    for (const r of rows) {
      if (!r.enabled || !r.field) continue;
      r.field.model.commit(r.field.input.value.trim());
      const v = r.field.getValue();
      if (!(v > 0) || !Number.isFinite(v)) { $('#calError').removeClass('d-none'); return; }
      committed.push({ ch: r.ch, v });
    }
    for (const c of committed) this._onCalibrate(c.ch, c.v);
    this._modal().hide();
  }

  // -------------------------------------------------------------------------
  // ADC (scope / FFT input) — seeds analyzed-channel-only, writes the input card
  // (ScopeTabControl / FftTabControl.openCalibrationDialog)
  // -------------------------------------------------------------------------

  /**
   * Opens the ADC calibration dialog seeded on the analyzed channel only: the FFT / scope
   * measures ONE channel ({@code measCh}), so only that row carries the measured Vrms; the
   * other row is disabled and blank. On OK the entered ACTUAL Vrms rescales that channel's ADC
   * full-scale — a bound stereo card writes only that channel (per-channel), a MONO card or an
   * unbound device writes the shared both-channels full-scale.
   * @param {number} measuredVrms the open-time reading the dialog seeds + divides by.
   * @param {'L'|'R'} measCh the analyzed channel.
   */
  openAdc(measuredVrms, measCh) {
    const label = this._inputLabel();
    const stereo = this.deviceStore.isBoundStereo(label, true);
    this.open({
      titleKey: 'calibrate.title', promptKey: 'calibrate.input', tooltipKey: 'calibrate.input.tooltip',
      seedLeft: measCh === 'L' ? measuredVrms : null,
      seedRight: measCh === 'R' ? measuredVrms : null,
      onCalibrate: (ch, actualVrms) => {
        const scale = actualVrms / measuredVrms;
        if (stereo) {
          const newFs = this.prefs.getAdcFsVoltageRms(ch) * scale;
          this.deviceStore.storeAdcCalibrationChannel(ch, newFs, label);
        } else {
          // MONO / unbound: shared both-channels full-scale (auto-creates the profile on first
          // calibrate); also sets the FS scalar.
          const newFs = this.prefs.getAdcFsVoltageRms() * scale;
          this.deviceStore.storeAdcCalibration(newFs, label);
        }
        this.prefs.save();
      },
    });
  }

  // -------------------------------------------------------------------------
  // DAC (generator output) — seeds the configured amplitude, writes the output card
  // (GeneratorPane.openDacCalibrationDialog)
  // -------------------------------------------------------------------------

  /**
   * Opens the DAC calibration dialog. A stereo (LINKED / INDEPENDENT) card gets both rows,
   * each prefilled with the single commanded amplitude (the generator drives both lanes from one
   * amplitude), so each channel's measured output rescales its OWN DAC full-scale. A MONO card or
   * an unbound device is single-row (Left only) — the shared both-channels full-scale.
   */
  openDac() {
    const configured = this.prefs.genAmplitudeVrms.get();
    if (!(configured > 0)) return;
    const label = this._outputLabel();
    const stereo = this.deviceStore.isBoundStereo(label, false);
    this.open({
      titleKey: 'calibrate.dac.title', promptKey: 'calibrate.dac.input', tooltipKey: 'calibrate.dac.input.tooltip',
      seedLeft: configured,
      seedRight: stereo ? configured : null,
      onCalibrate: (ch, measuredVrms) => {
        if (stereo) {
          const oldFs = this.prefs.getDacFsVoltageAmpl(ch);
          const newFs = oldFs * (measuredVrms / configured);
          this.deviceStore.storeDacCalibrationChannel(ch, newFs, label);
        } else {
          const oldFs = this.prefs.getDacFsVoltageAmpl();
          const newFs = oldFs * (measuredVrms / configured);
          this.deviceStore.storeDacCalibration(newFs, label);
        }
        this.prefs.save();
        // Rescale the live tone against the new full-scale (no restart) — the store already
        // pushed the scalar into prefs; flow the LEFT full-scale into the engine and retune.
        this.engine.config.dacFsVoltageAmpl = this.prefs.getDacFsVoltageAmpl();
        this.engine.retuneGenerator();
      },
    });
  }
}
