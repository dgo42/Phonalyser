/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of gui/freqresp/FreqRespTabControl. Owns the freq-response SETTINGS strip:
// the sweep/FFT/dither dropdowns + derived-duration label, the RIAA tab (Show/Reverse/IEC/
// Compare + enable cascade + tiles), the Presets tab (save/load/delete + list), the Utility
// tab (screenshot + DAC/ADC-cal stubs), the Save-to / Load-from .frc tabs, and the multi-row
// calibration loader. All DSP + the canvas view live elsewhere; this is settings wiring over
// the shared FreqRespView + FreqRespCorrectionStore, mirroring the FftTabControl split.

import { makeFreqRespResult } from './stereo-result.js';
import { saveFrc, loadFrc } from '../io/frc.js';
import { putCal, getCal } from '../io/cal-store.js';
import { CalibrationEntry, FreqRespPreset } from '../store/preferences.js';
import { t } from '../i18n/i18n.js';
import { TileTabs } from '../widgets/tile-tabs.js';
import { PresetBar } from '../widgets/preset-bar.js';

const FRC_TYPE = [{ description: 'Filter calibration', accept: 'text/plain', extensions: ['.frc'] }];

export class FreqRespTabControl {
  /**
   * @param {import('../audio/backend.js').AudioEngine} engine the live engine (load uses inRate)
   * @param {object} prefs Preferences.instance()
   * @param {import('./freqresp-view.js').FreqRespView} view the interactive canvas view
   * @param {import('./correction-store.js').FreqRespCorrectionStore} correctionStore the loaded-.frc store
   * @param {{saveFile:Function, openFile:Function, bytesToText:Function}} io file-picker glue
   * @param {import('./freqresp-controller.js').FreqRespController} controller sweep-timing owner (durationSec)
   */
  constructor(engine, prefs, view, correctionStore, io, controller) {
    this.engine = engine;
    this.prefs = prefs;
    this.view = view;
    this.correctionStore = correctionStore;
    this.io = io;
    this.controller = controller;
    this.$ = window.jQuery;

    // One CalRow per visible calibration row, in display order (Java FreqRespTabControl.calRows).
    this._calRows = [];
    // Re-entrancy guard so the store's change callback doesn't rebuild rows for our own writes.
    this._calMutationInFlight = false;
    // Set by bindCalibration(); awaited by app.js (with the FFT pane's) before pruneCals (issue 2.3).
    this._calRestore = null;
  }

  status(txt) { this.$('#status').text(txt); }

  bind() {
    const $ = this.$;
    // FreqResp tile-tabs — Settings / RIAA-IEC / Presets / Utility / Calibration / Save / Load
    // drop-down panels (Java TileTabFolder). Was a raw handler in app.js; owned here now.
    new TileTabs('#frTabs').bind();
    // frStart/frStop/frAmp/frPoints/frLeadIn are NumericStepFields owned by app.js
    // (they two-way bind the prefs); here we only own the dropdowns, checkboxes and action wiring.
    $('#frDur').val(this.controller.durationSec());
    $('#frRiaaRev').prop('checked', this.prefs.freqRespReverseRiaa.get());
    $('#frRiaaIec').prop('checked', this.prefs.freqRespIecAmendment.get());
    $('#frRiaaCompare').prop('checked', this.prefs.freqRespCompareMode.get());

    // FFT size dropdown ↔ freqRespFftSize (index-mapped value list); the label shows the
    // derived sweep duration (Java refreshFftSizeLabel). The size write re-derives
    // freqRespDurationSec via the controller's subscription.
    $('#frFft').val(String(this.prefs.freqRespFftSize.get()));
    $('#frFft').on('change', () => { this.prefs.freqRespFftSize.set(parseInt($('#frFft').val(), 10)); this.refreshFftLabel(); });
    // NOTE: the initial label is set by app.js init's step('refreshFftLabel') AFTER the i18n
    // bundle has loaded — calling t() at construction would resolve to the bare key.

    // Dither dropdown ↔ freqRespDitherBits (0..31; index == bit count).
    this.rebuildDitherCombo();
    $('#frDither').val(String(this.prefs.freqRespDitherBits.get()));
    $('#frDither').on('change', () => this.prefs.freqRespDitherBits.set(parseInt($('#frDither').val(), 10) || 0));

    // RIAA / phase toggles read the live prefs at paint time, so a repaint is all that's
    // needed. Show RIAA is not persisted, so mirror the checkbox into the pref.
    $('#frRiaa').on('change', () => {
      this.prefs.freqRespShowRiaa.set($('#frRiaa').is(':checked'));
      this.refreshRiaaEnable();   // Show gates Reverse / IEC / Compare
      // Show RIAA toggled on while Compare is already armed → fit the compare curve once.
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
    // freqRespApplyCalibration has NO manual UI (Java FreqRespTabControl): it is flipped only
    // by the wizard path, so the view divides the loaded .frc out at render time.
    $('#frRiaa, #frRiaaRev, #frRiaaIec').on('change', () => this.view.render());

    // The compare-smoothing-window pref (changed in the Preferences dialog) refreshes the
    // compare anchor + min/max table (Java FREQRESP_COMPARE_PARAMS_CHANGED).
    this.prefs.freqRespCompareSmoothWindow.addListener(() => this.view.onCompareParamsChanged());

    this.bindPresets();
    this.bindUtility();
    this.bindSaveLoad();
    // The calibration rows + preset list + RIAA enable cascade build DOM text via t(), so they
    // run from seedTabs() in app.js's init AFTER the i18n bundle is loaded.
  }

  /** Builds the i18n-dependent tab UI (calibration rows, preset list, RIAA enable). Called from
   *  app.js's init AFTER the i18n bundle is loaded so t() resolves real strings. */
  seedTabs() {
    this.bindCalibration();
    this.refreshPresetList();
    this.refreshRiaaEnable();
  }

  /** Re-runs the RIAA enable cascade (Java FreqRespTabControl.refreshRiaaEnable): Show gates
   *  Reverse / IEC / Compare; Compare additionally needs a measurement present. */
  refreshRiaaEnable() {
    const $ = this.$;
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
    this.$('#frRiaaTabSub').html(chips.map((c) => `<span class="tile">${c}</span>`).join(''));
  }

  /** Populates the dither combo with 0..31, where the option index IS the bit count (0 → "Off",
   *  else the bare number) — faithful to Java FreqRespTabControl. */
  rebuildDitherCombo() {
    let html = '';
    for (let i = 0; i <= 31; i++) html += `<option value="${i}">${i === 0 ? 'Off' : i}</option>`;
    this.$('#frDither').html(html);
  }

  /** "FFT size (D.Ds)": the label shows the controller's DERIVED sweep duration (Java
   *  refreshFftSizeLabel), the part of the FFT window actually swept. */
  refreshFftLabel() {
    const secs = this.controller.durationSec().toFixed(1);
    this.$('#frFftLabel').text(t('freqResp.settings.fftSize') + ' (' + secs + 's)');
    this.$('#frDur').val(this.controller.durationSec());
  }

  // ===========================================================================
  // Presets tab (Java FreqRespTabControl.buildPresetsTab → PresetBar)
  // ===========================================================================

  bindPresets() {
    // Java FreqRespTabControl.buildPresetsTab → PresetBar<FreqRespPreset>.
    this._presetBar = new PresetBar({
      ids: { name: '#frPresetName', save: '#frPresetSave', load: '#frPresetLoad',
        delete: '#frPresetDelete', menu: '#frPresetMenu', menuBtn: '#frPresetMenuBtn' },
      store: {
        presets: () => this.prefs.freqRespPresets,
        put: (n, p) => this.prefs.putFreqRespPreset(n, p),
        remove: (n) => this.prefs.removeFreqRespPreset(n),
        captureCurrent: () => this.captureFreqRespPreset(),
        apply: (p) => this.applyFreqRespPreset(p),
      },
      confirm: (title, msg) => this.showConfirm(title, msg),
      i18nPrefix: 'freqResp.presets',
      onChanged: (names) => this.$('#frPresetTabSub').html(names.length === 0 ? '' : `<span class="tile">${names.length} saved</span>`),
    });
    this._presetBar.bind();
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
    // Reflect the recalled prefs into the live widgets (the NumericStepFields are owned by
    // app.js; reseed the ones this control owns + the derived label).
    const $ = this.$;
    $('#frStart').val(p.startHz); $('#frStop').val(p.stopHz); $('#frAmp').val(p.amplitudeVrms);
    $('#frPoints').val(p.sweepPoints); $('#frLeadIn').val(p.leadInSec);
    $('#frFft').val(String(p.fftSize)); $('#frDither').val(String(p.ditherBits));
    $('#frRiaa').prop('checked', p.showRiaa); $('#frRiaaRev').prop('checked', p.reverseRiaa);
    $('#frRiaaIec').prop('checked', p.iecAmendment); $('#frRiaaCompare').prop('checked', p.compareMode);
    this.refreshFftLabel();
    this.refreshRiaaEnable();
    // jQuery .prop('checked') fires no change event, so mirror the one-shot autoSetupCompare
    // explicitly when the recalled preset lands in compare+Show with a result.
    if (this.prefs.freqRespShowRiaa.get() && this.prefs.freqRespCompareMode.get() && this.view.hasAnyResult()) {
      this.view.autoSetupCompare();
    }
    this.view.render();
  }

  /** Repopulates the preset dropdown + chip + button enablement — delegates to the shared
   *  PresetBar. Kept as a named method (called at setup + after settings recall). */
  refreshPresetList() { this._presetBar.refreshList(); }

  // ===========================================================================
  // Utility tab (Java FreqRespTabControl.buildUtilityTab): screenshot-only here —
  // the desktop DAC/ADC calibrate buttons are stubs (acceptable parity).
  // ===========================================================================

  bindUtility() {
    // Screenshot (#frShot) is wired in app.js via shotDialog.addPane. DAC / ADC calibrate —
    // desktop stubs (dialog wired in a Phase 6 follow-up).
    this.$('#frCalDac').on('click', () => console.info('FreqResp DAC-cal clicked (stub)'));
    this.$('#frCalAdc').on('click', () => console.info('FreqResp ADC-cal clicked (stub)'));
  }

  // ===========================================================================
  // Save-to / Load-from tabs (Java buildSaveToTab / buildLoadFromTab)
  // ===========================================================================

  bindSaveLoad() {
    const $ = this.$, prefs = this.prefs;
    $('#frSavePath').val(prefs.freqRespSavePath.get() || '');
    $('#frLoadPath').val(prefs.freqRespLoadPath.get() || '');
    $('#frSaveBtn').on('click', () => this.saveMeasurement());
    $('#frLoadBtn').on('click', () => this.loadMeasurement());
  }

  /** Writes the current measurement (BOTH channels) to a .frc (Java openSaveDialog). When one
   *  channel is hidden the visible one is duplicated so the file round-trips as strict stereo. */
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
    // Build the rows from prefs (always at least row 0). Entries with a persisted hash are
    // restored from the cal-store (getCal) without a re-pick; entries without a hash show the
    // remembered path but stay un-loaded until the user re-picks the file.
    if (prefs.freqRespCalibrations.length === 0) prefs.addFreqRespCalibration(new CalibrationEntry());
    const restore = async () => {
      for (const entry of prefs.freqRespCalibrations) {
        const row = this.createCalRowUi(entry);
        if (entry.hash) {
          try {
            const stored = await getCal(entry.hash);
            if (stored) {
              const text = new TextDecoder().decode(stored.bytes);
              this.loadFileIntoRow(row, text, stored.name);
            } else if (entry.path) {
              row.$path.val(entry.path).attr('title', entry.path);
            }
          } catch (_) {
            if (entry.path) row.$path.val(entry.path).attr('title', entry.path);
          }
        } else if (entry.path) {
          row.$path.val(entry.path).attr('title', entry.path);
        }
        this.updateCalRowEnable(row);
      }
      this.syncStoreFromRows();
    };
    // Exposed so app.js can await BOTH panes' cal restore before pruneCals (issue 2.3).
    this._calRestore = restore().catch((e) => console.error('FreqResp: cal restore failed', e));
  }

  /** Builds + appends a fresh calibration row. Row 0 has no Remove button (its grid cell is
   *  left empty) so the column widths stay aligned (Java createRowUi). */
  createCalRowUi(entry) {
    const $ = this.$;
    const isRow0 = this._calRows.length === 0;
    const $row = $('<div class="fr-cal-row">');
    const $path = $('<input class="form-control form-control-sm fr-cal-path" type="text" readonly>')
      .val(t('freqResp.calibration.path.none')).attr('title', t('freqResp.calibration.path.tooltip'));
    const $active = $('<input class="form-check-input fr-cal-active" type="checkbox">').attr('title', t('fft.calibration.active.tooltip'));
    const $activeLbl = $('<label class="form-check-label small">').append($active).append(' ' + t('fft.calibration.active'));
    const $load = $('<button class="fcal-load btn btn-sm btn-outline-secondary" type="button">').attr('title', t('freqResp.calibration.load.tooltip')).html('<img src="assets/icons/folder-open.svg" class="util-svg" alt="">');
    // Icon-tinted like the FFT cal rows (CSS): green ADD(+), red CLEAR(×)/REMOVE(−), neutral load
    // — a plain secondary border, NOT a red outline button.
    const $clear = $('<button class="fcal-clear btn btn-sm btn-outline-secondary" type="button">').attr('title', t('freqResp.calibration.clear.tooltip')).html('<img src="assets/icons/rectangle-xmark.svg" class="util-svg" alt="">');
    const $add = $('<button class="fcal-add btn btn-sm btn-outline-secondary" type="button">').attr('title', t('freqResp.calibration.add.tooltip')).html('<img src="assets/icons/plus.svg" class="util-svg" alt="">');
    const $remove = $('<button class="fcal-remove btn btn-sm btn-outline-secondary" type="button">').attr('title', t('freqResp.calibration.remove.tooltip')).html('<img src="assets/icons/minus.svg" class="util-svg" alt="">');
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
      // Store the raw bytes in the shared cal-store and record the hash on the entry so the row
      // can be restored after a reload without a re-pick (issue 2.3).
      try {
        const bytes = f.bytes instanceof ArrayBuffer ? f.bytes : f.bytes.buffer;
        row.entry.hash = await putCal(bytes, f.name);
      } catch (e) { console.warn('FreqResp: putCal failed (cal not persisted):', e && e.message); }
      this.syncStoreFromRows();
      this.prefs.save();
    } catch (e) { this.status(t('freqResp.error.calibration.load', e.message)); }
  }

  /** Parses .frc text into the row + records the path (Java loadFileIntoRow). Returns true when
   *  the calibration parsed, so the caller pushes it into the store. */
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
    row.entry.hash = null;
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

  /** Returns the set of cal-store hashes referenced by the current calibration rows. Called by
   *  app.js after startup restore to build the union for pruneCals. */
  getCalHashes() {
    const hashes = new Set();
    for (const r of this._calRows) {
      if (r.entry.hash) hashes.add(r.entry.hash);
    }
    return hashes;
  }

  refreshCalTile() {
    const n = this.correctionStore.getEntries().length;
    this.$('#frCalTabSub').html(n <= 0 ? '' : `<span class="tile">${n === 1 ? t('calibration.tile.loaded') : t('calibration.tile.loadedN', n)}</span>`);
  }

  /** Store-changed handler: retraces the view, and rebuilds the calibration-tab rows when the
   *  change came from OUTSIDE the tab (Java FreqRespTabControl.onCalibrationChanged — skips the
   *  rebuild for the control's own writes). */
  onStoreChanged() {
    if (this.view) this.view.onCalibrationChanged();
    if (!this._calMutationInFlight && this._calRows && this._calRows.length) {
      this.rebuildRowsFromStore();
    }
    this.refreshCalTile();
  }

  /** Rebuilds the row UI from the store's entries, dropping user-added empty rows — exactly one
   *  row per loaded entry, row 0 always present (Java rebuildRowsFromStore). No-op when the loaded
   *  rows already line up with the store. */
  rebuildRowsFromStore() {
    const prefs = this.prefs;
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
