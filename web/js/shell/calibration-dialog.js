/*
 * Phonalyser web - unified ADC/DAC full-scale calibration dialog.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of gui/common/CalibrationDialog - the ONE modal that replaces the desktop's
 * (deleted) AdcCalibrationDialog + DacCalibrationDialog, opened from the scope pane, the FFT
 * pane and the generator. One shape, always two rows - a Left row and a Right row, each
 * [channel label] [AMPLITUDE NumericStepField]. A null seed disables + blanks its row (the
 * ADC's non-measured channel); a non-null seed prefills + enables it. On OK every enabled row
 * whose field carries a positive value fires - an invalid enabled field rejects the whole
 * submit (CalibrationDialog.onOk).
 *
 * The per-opener wording (title / prompt / tooltip) is the desktop's CalibrationDialog.Texts.
 * The OK WRITE routes through DeviceProfileStore.storeAdc/DacCalibration (NOT the raw prefs
 * setters): a bound stereo card writes only the measured channel (per-channel), a MONO card or
 * an unbound device writes the shared both-channels full-scale - exactly ScopeTabControl /
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
   * @param deps  {getLeftField, getRightField, inputLabel, outputLabel, bench, restartGenerator}
   *   - getLeftField / getRightField: () => the Left / Right NumericStepField (canonical Vrms).
   *   - inputLabel / outputLabel: () => the current in/out device LABEL (recognition patterns
   *     match the human label, not the deviceId the <select> value carries).
   *   - bench: () => the bench write seam while a SERVER's backend is selected, else null - see
   *     {@link #_storeAdc}. Resolved per call, not held: it is built with the Preferences dialog,
   *     after this one, and the selected backend changes under it.
   *   - restartGenerator: () => re-opens the playback lane (a no-op while nothing is playing) -
   *     how a DAC calibration reaches a tone the BENCH is emitting, see {@link #_applyToLiveTone}.
   */
  constructor(engine, prefs, deviceStore,
      { getLeftField, getRightField, inputLabel, outputLabel, bench, restartGenerator }) {
    this.engine = engine;
    this.prefs = prefs;
    this.deviceStore = deviceStore;
    this._getLeftField = getLeftField;
    this._getRightField = getRightField;
    this._inputLabel = inputLabel;
    this._outputLabel = outputLabel;
    this._bench = bench;
    this._restartGenerator = restartGenerator;
    // The onCalibrate committed for the currently open dialog; replaced per open().
    this._onCalibrate = null;
    // What that dialog does ONCE, after the whole submit; replaced per open().
    this._onCommitted = null;
    // Which rows are enabled this open (a null seed -> disabled + skipped on OK).
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
   *          onCalibrate:(channel:'L'|'R', value:number)=>void, onCommitted:?()=>void}} cfg
   */
  open(cfg) {
    $('#calTitle').text(t(cfg.titleKey));
    $('#calPrompt').text(t(cfg.promptKey));
    $('#calError').addClass('d-none');
    this._leftEnabled = this._seedRow(this._getLeftField(), cfg.seedLeft, cfg.tooltipKey);
    this._rightEnabled = this._seedRow(this._getRightField(), cfg.seedRight, cfg.tooltipKey);
    this._onCalibrate = cfg.onCalibrate;
    this._onCommitted = cfg.onCommitted || null;
    this._modal().show();
  }

  /** OK: commit + validate every enabled row, then fire onCalibrate for each. An invalid
   *  enabled field rejects the whole submit and keeps the dialog open (CalibrationDialog.onOk).
   *
   *  <p>Awaited per row: a device on a bench is calibrated by a wire write that may be REFUSED
   *  (the device is locked by somebody else, or the bench reads its own full scales), and the row
   *  after it must not go out until the one before it has been answered.
   *
   *  <p>What the opener does with the RESULT runs once, after every row - a live-tone step belongs
   *  to the submit, not to each channel of it (a two-row card would otherwise re-open the bench's
   *  lane twice, see {@link #_applyToLiveTone}). */
  async _onOk() {
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
    for (const c of committed) await this._onCalibrate(c.ch, c.v);
    if (this._onCommitted) await this._onCommitted();
    this._modal().hide();
  }

  // -------------------------------------------------------------------------
  // Where a calibration is WRITTEN (Java gui/sound/CalibrationStore)
  // -------------------------------------------------------------------------

  /**
   * Stores one ADC full scale where the device it belongs to lives: this machine's card store for
   * a local device, and the SERVER's own store - spec 4.3 `device.setCalibration`, under that
   * device's lock - for a device on a bench: a calibration made in the ADC/DAC calibration dialog
   * happens ON THE SERVER whenever the device being calibrated is a server's.
   *
   * <p><b>The wire carries a PAIR</b>, so the channel that was not measured travels unchanged -
   * read before anything moves, so a refused write leaves this client holding exactly what the
   * bench still holds.
   *
   * <p><b>Applied on SUCCESS, never before.</b> A refusal must leave every reading on screen as
   * it was: the bench is still measuring against its old full scale, and a client that had
   * already moved its own would put a number in front of the operator that is true nowhere.
   *
   * @returns {Promise<boolean>} whether the calibration was stored
   */
  async _storeAdc(ch, newFs, label, stereo) {
    const bench = this._bench ? this._bench() : null;
    if (bench == null) {
      if (stereo) this.deviceStore.storeAdcCalibrationChannel(ch, newFs, label);
      else this.deviceStore.storeAdcCalibration(newFs, label);
      return true;
    }
    const left = stereo && ch === 'R' ? this.prefs.getAdcFsVoltageRms('L') : newFs;
    const right = stereo && ch === 'L' ? this.prefs.getAdcFsVoltageRms('R') : newFs;
    if (!await bench.send(true, label, left, right)) return false;
    if (stereo && ch === 'R') this.prefs.setAdcFsVoltageRmsRight(newFs);
    else if (stereo) this.prefs.setAdcFsVoltageRms(newFs);
    else { this.prefs.setAdcFsVoltageRms(newFs); this.prefs.setAdcFsVoltageRmsRight(newFs); }
    return true;
  }

  /** The output mirror of {@link #_storeAdc}. The scalars here are PEAK amplitudes and spec 4.3
   *  carries RMS volts, so the pair is divided by sqrt(2) on its way out - the one conversion, in
   *  the one place (Java CalibrationStore.sendDac). */
  async _storeDac(ch, newFsAmpl, label, stereo) {
    const bench = this._bench ? this._bench() : null;
    if (bench == null) {
      if (stereo) this.deviceStore.storeDacCalibrationChannel(ch, newFsAmpl, label);
      else this.deviceStore.storeDacCalibration(newFsAmpl, label);
      return true;
    }
    const left = stereo && ch === 'R' ? this.prefs.getDacFsVoltageAmpl('L') : newFsAmpl;
    const right = stereo && ch === 'L' ? this.prefs.getDacFsVoltageAmpl('R') : newFsAmpl;
    if (!await bench.send(false, label, left / Math.SQRT2, right / Math.SQRT2)) return false;
    if (stereo && ch === 'R') this.prefs.setDacFsVoltageAmplRight(newFsAmpl);
    else if (stereo) this.prefs.setDacFsVoltageAmpl(newFsAmpl);
    else { this.prefs.setDacFsVoltageAmpl(newFsAmpl); this.prefs.setDacFsVoltageAmplRight(newFsAmpl); }
    return true;
  }

  // -------------------------------------------------------------------------
  // ADC (scope / FFT input) - seeds analyzed-channel-only, writes the input card
  // (ScopeTabControl / FftTabControl.openCalibrationDialog)
  // -------------------------------------------------------------------------

  /**
   * Opens the ADC calibration dialog seeded on the analyzed channel only: the FFT / scope
   * measures ONE channel ({@code measCh}), so only that row carries the measured Vrms; the
   * other row is disabled and blank. On OK the entered ACTUAL Vrms rescales that channel's ADC
   * full-scale - a bound stereo card writes only that channel (per-channel), a MONO card or an
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
      // A CORRECTION, not an absolute: the reading was already scaled by the full scale in force
      // (on a bench, the SERVER's), so the ratio rescales that value - newFs = currentFs ×
      // known/measured - and calibrating twice in a row converges, the second pass measuring the
      // known voltage and correcting by 1.0.
      // MONO / unbound writes the shared both-channels full-scale (the local path auto-creates
      // the profile on first calibrate); a bound stereo card writes only the measured channel.
      onCalibrate: async (ch, actualVrms) => {
        const scale = actualVrms / measuredVrms;
        const newFs = this.prefs.getAdcFsVoltageRms(stereo ? ch : 'L') * scale;
        if (!await this._storeAdc(ch, newFs, label, stereo)) return;
        this.prefs.save();
      },
    });
  }

  // -------------------------------------------------------------------------
  // DAC (generator output) - seeds the configured amplitude, writes the output card
  // (GeneratorPane.openDacCalibrationDialog)
  // -------------------------------------------------------------------------

  /**
   * Opens the DAC calibration dialog. A stereo (LINKED / INDEPENDENT) card gets both rows,
   * each prefilled with the single commanded amplitude (the generator drives both lanes from one
   * amplitude), so each channel's measured output rescales its OWN DAC full-scale. A MONO card or
   * an unbound device is single-row (Left only) - the shared both-channels full-scale.
   */
  openDac() {
    const configured = this.prefs.genAmplitudeVrms.get();
    if (!(configured > 0)) return;
    const label = this._outputLabel();
    const stereo = this.deviceStore.isBoundStereo(label, false);
    // Whether anything actually reached a store this submit: a refused bench write must leave the
    // tone that is playing exactly as it leaves the scalars - at the full scale still in force.
    let stored = false;
    this.open({
      titleKey: 'calibrate.dac.title', promptKey: 'calibrate.dac.input', tooltipKey: 'calibrate.dac.input.tooltip',
      seedLeft: configured,
      seedRight: stereo ? configured : null,
      onCalibrate: async (ch, measuredVrms) => {
        const newFs = this.prefs.getDacFsVoltageAmpl(stereo ? ch : 'L') * (measuredVrms / configured);
        if (!await this._storeDac(ch, newFs, label, stereo)) return;
        this.prefs.save();
        stored = true;
      },
      onCommitted: async () => { if (stored) await this._applyToLiveTone(); },
    });
  }

  /**
   * Makes the tone that is PLAYING use the full scale just stored - once per submit, after every
   * channel of it.
   *
   * <p>Locally that is a rescale with NO restart: the scalar is already in prefs, so the left
   * full-scale flows into the engine and the DDS re-derives its normalised amplitude from it -
   * the entered volts keep coming out as those volts.
   *
   * <p><b>On a BENCH it is a RE-OPEN</b>, because the conversion is not made on this machine. The
   * server scales the amplitude with the full scale IT stores for that device, and it reads that
   * value exactly ONCE, when the lane opens (GeneratorSession.applyCardCalibration); spec 4.5's
   * gen.config carries no full scale at all - this client stopped sending one and the server
   * ignores it - so there is nothing a retune could say that would move the level. The very lane
   * the calibration was measured through therefore goes on emitting at the pre-calibration scale
   * until it is opened again - which, before this re-open, meant a DAC calibration landed in the
   * server's devices.yaml while the client went on emitting at the old value until the page was
   * reloaded by hand.
   *
   * <p>The INPUT direction needs none of this, which is why only this half was reported broken:
   * a capture arrives as raw PCM and THIS client scales it, so its own new scalar is the whole of
   * the calibration and the next frame is already right.
   */
  async _applyToLiveTone() {
    if (this._bench && this._bench() != null) {
      if (this._restartGenerator) await this._restartGenerator();
      return;
    }
    // Rescale the live tone against the new full-scale (no restart) - the store already
    // pushed the scalar into prefs; flow the LEFT full-scale into the engine and retune.
    this.engine.config.dacFsVoltageAmpl = this.prefs.getDacFsVoltageAmpl();
    this.engine.retuneGenerator();
  }
}
