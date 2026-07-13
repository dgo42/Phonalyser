/*
 * Phonalyser web — DAC full-scale calibration dialog.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of gui/generator/DacCalibrationDialog. The user enters the voltage actually
 * measured at the DAC output for the configured amplitude; the true full-scale satisfies
 * measured/configured = FS_true/FS_old, so FS_new = FS_old × ratio. Writing the pref recomputes the
 * running generator's amplitude against the new full-scale (a live retune, no restart).
 */
import { t } from '../i18n/i18n.js';

/** Formats a voltage with adaptive units (V / mV / µV) for the calibration readout. */
function fmtCalVoltage(v) {
  const a = Math.abs(v);
  if (a >= 1) return `${v.toFixed(4)} V`;
  if (a >= 1e-3) return `${(v * 1e3).toFixed(3)} mV`;
  if (a >= 1e-6) return `${(v * 1e6).toFixed(2)} µV`;
  return `${v.toPrecision(3)} V`;
}

export class DacCalibrationDialog {
  /**
   * @param engine the AudioEngine (for the live retune).
   * @param prefs  Preferences.
   * @param deps   {modal, getField} — modal: the bootstrap Modal for #dacCalModal;
   *               getField: () => the dacCalValue NumericStepField (canonical Vrms).
   */
  constructor(engine, prefs, { modal, getField }) {
    this.engine = engine;
    this.prefs = prefs;
    this.modal = modal;
    this._getField = getField;
  }

  bind() {
    // Java renders the configured-amplitude line at construction (DacCalibrationDialog:66),
    // so it is always present when the dialog shows. Mirror that on the modal show event.
    $('#dacCalModal').on('show.bs.modal', () => {
      const configured = this.prefs.genAmplitudeVrms.get();
      $('#dacCalConfigured').text(t('calibrate.dac.configured', fmtCalVoltage(configured)));
    });

    $('#calibrateDac').on('click', () => {
      const configured = this.prefs.genAmplitudeVrms.get();
      const field = this._getField();
      if (field) field.setValue(configured);   // seed with the configured Vrms
      $('#dacCalError').addClass('d-none');
      this.modal.show();
    });

    $('#dacCalOk').on('click', () => {
      // The measured amplitude is the AMPLITUDE step field's canonical Vrms (unit lives in
      // the input text — V / mV / µV / dBV — no separate <select>). Commit any pending typed
      // text first, then read the canonical value (mirrors the field's own commit-on-blur).
      const field = this._getField();
      if (field) field.model.commit(field.input.value.trim());
      const measured = field ? field.getValue() : NaN;
      const configured = this.prefs.genAmplitudeVrms.get();
      // Java pops an error dialog on a non-positive / unparsable value and keeps the dialog
      // open (DacCalibrationDialog:112-117); the web surfaces it inline instead of silently hiding.
      if (!(measured > 0) || !(configured > 0) || !Number.isFinite(measured)) {
        $('#dacCalError').removeClass('d-none');
        return;
      }
      const newFs = this.prefs.dacFsVoltageAmpl.get() * (measured / configured);
      this.prefs.dacFsVoltageAmpl.set(newFs); this.prefs.save();
      this.engine.config.dacFsVoltageAmpl = newFs;
      this.engine.retuneGenerator();   // rescale the live tone against the new full-scale (no restart)
      this.modal.hide();
    });

    return this;
  }
}
