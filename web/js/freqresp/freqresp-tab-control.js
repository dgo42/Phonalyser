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
import { saveFrc, loadFrc, readSampleRateHz } from '../io/frc.js';
import { putCal, getCal } from '../io/cal-store.js';
import { CalibrationEntry, FreqRespPreset } from '../store/preferences.js';
import { FreqRespFilterTypeParams } from '../store/freqresp-filter-type-params.js';
import { FilterType, hasRipple } from '../dsp/filter-types.js';
import { t } from '../i18n/i18n.js';
import { TileTabs } from '../widgets/tile-tabs.js';
import { PresetBar } from '../widgets/preset-bar.js';
import { NumericStepField, NumericStepModel, UNIT_FAMILIES } from '../widgets/numeric-step-field.js';

// ---- Filters / Unevenness field bounds (Java FreqRespTabControl constants) ----
const FILTER_DB_MIN = 0.001;
const FILTER_DB_MAX = 200.0;
const FILTER_DB_DECIMALS = 3;
const FILTER_ORDER_MIN = 1;
const FILTER_ORDER_MAX = 20;
const FILTER_Q_MIN = 0.1;
const FILTER_Q_MAX = 100.0;
const FILTER_Q_DECIMALS = 3;
const UNEVEN_DB_MIN = 0.001;
const UNEVEN_DB_MAX = 20.0;

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

    // Filter parameter step fields, keyed by dom id (built in bindFilters).
    this._filterFields = {};
    // Re-entrancy guard: true while loadFilterParams seeds the widgets from the per-type
    // params map, so the widgets' own change listeners don't write the just-loaded values
    // straight back into the map (Java filterParamsLoading).
    this._filterParamsLoading = false;

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

    // Output-lane gate ↔ freqRespOutputChannels (OutputChannels enum names, mirroring
    // Java FreqRespTabControl's Bindings.combo). Both capture channels are still
    // deconvolved; the view's L/R buttons pick which trace shows.
    $('#frOutputChannel').val(this.prefs.freqRespOutputChannels.get());
    $('#frOutputChannel').on('change', () => this.prefs.freqRespOutputChannels.set($('#frOutputChannel').val()));

    // RIAA / phase toggles read the live prefs at paint time, so a repaint is all that's
    // needed. Show RIAA is not persisted, so mirror the checkbox into the pref.
    $('#frRiaa').on('change', () => {
      const show = $('#frRiaa').is(':checked');
      this.prefs.freqRespShowRiaa.set(show);
      if (show) {   // only one reference curve can be active — enabling RIAA turns the
        // filter overlay off (symmetric with the filter-show handler, Java parity)
        this.prefs.freqRespShowFilter.set(false);
        $('#frFilterShow').prop('checked', false);
        this.refreshFilterEnable();
        this.refreshFilterTile();
      }
      this.refreshRiaaEnable();   // Show gates Reverse / IEC / Compare
      // Show RIAA toggled on while Compare is already armed → fit the compare curve once.
      if (this.prefs.freqRespShowRiaa.get() && this.prefs.freqRespCompareMode.get() && this.view.hasAnyResult()) {
        this.view.autoSetupCompare();
      }
      this.view.render();
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
      if (enable) this.view.autoSetupCompare();   // one-shot fit on entry
      else this.view.autoSetupMagnitudeRange();   // leaving compare: re-fit the normal view (Java parity, fixed there in parallel)
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
    // run from seedTabs() in app.js's init AFTER the i18n bundle is loaded. The Filters /
    // Unevenness tabs build NumericStepFields whose unit suffixes come from t('unit.*'),
    // so they are deferred there too (same ordering reason app.js runs initStepFields
    // after applyI18n — building them here rendered raw "unit.*" keys + console misses).
  }

  /** Builds the i18n-dependent tab UI (filter/unevenness fields, calibration rows, preset
   *  list, RIAA enable). Called from app.js's init AFTER the i18n bundle is loaded so t()
   *  resolves real strings. */
  seedTabs() {
    this.bindFilters();
    this.bindUnevenness();
    this.bindCalibration();
    this.refreshPresetList();
    this.refreshRiaaEnable();
    this.refreshUnevenTile();
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
    // The Filters tab's Compare is likewise gated on a present measurement, so re-run its
    // cascade from the same pane entry point (Java refreshRiaaEnable → refreshFilterEnable).
    this.refreshFilterEnable();
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
  // Filters tab (Java FreqRespTabControl.buildFiltersTab): ideal filter overlay
  // vs measured response — Show / Compare + type & response + By-spec / By-order.
  // ===========================================================================

  bindFilters() {
    const $ = this.$, prefs = this.prefs;

    // The per-type params scalars are step fields owned here (Java NumericStepFields).
    const F = UNIT_FAMILIES;
    const mkField = (id, model) => {
      const input = document.getElementById(id);
      if (!input) return null;
      const f = new NumericStepField(input, model, { tooltipBase: input.title || '' });
      this._filterFields[id] = f;
      return f;
    };
    const nyquist = () => (this.prefs.current().inputSampleRate || 48000) / 2;
    mkField('frFilterRipple', new NumericStepModel({ family: F.DECIBEL, min: FILTER_DB_MIN, max: FILTER_DB_MAX, maxDecimals: FILTER_DB_DECIMALS }));
    mkField('frFilterStopAtten', new NumericStepModel({ family: F.DECIBEL, min: FILTER_DB_MIN, max: FILTER_DB_MAX, maxDecimals: FILTER_DB_DECIMALS }));
    mkField('frFilterCenter', new NumericStepModel({ family: F.FREQUENCY, min: 1, max: nyquist(), maxDecimals: 9 }));
    mkField('frFilterPass', new NumericStepModel({ family: F.FREQUENCY, min: 1, max: nyquist(), maxDecimals: 9 }));
    mkField('frFilterStop', new NumericStepModel({ family: F.FREQUENCY, min: 1, max: nyquist(), maxDecimals: 9 }));
    mkField('frFilterOrderPass', new NumericStepModel({ family: F.FREQUENCY, min: 1, max: nyquist(), maxDecimals: 9 }));
    mkField('frFilterOrderRipple', new NumericStepModel({ family: F.DECIBEL, min: FILTER_DB_MIN, max: FILTER_DB_MAX, maxDecimals: FILTER_DB_DECIMALS }));
    mkField('frFilterOrder', new NumericStepModel({ family: F.NONE, min: FILTER_ORDER_MIN, max: FILTER_ORDER_MAX, maxDecimals: 0 }));
    mkField('frFilterQ', new NumericStepModel({ family: F.NONE, min: FILTER_Q_MIN, max: FILTER_Q_MAX, maxDecimals: FILTER_Q_DECIMALS }));

    // Field write-through to the current type's params entry (Java onFilterField).
    this.onFilterField('frFilterRipple', (p, v) => { p.rippleDb = v; });
    this.onFilterField('frFilterStopAtten', (p, v) => { p.stopAttenDb = v; });
    this.onFilterField('frFilterCenter', (p, v) => { p.centerHz = v; });
    this.onFilterField('frFilterPass', (p, v) => { p.passHz = v; });
    this.onFilterField('frFilterStop', (p, v) => { p.stopHz = v; });
    this.onFilterField('frFilterOrderPass', (p, v) => { p.orderPassHz = v; });
    this.onFilterField('frFilterOrderRipple', (p, v) => { p.orderRippleDb = v; });
    this.onFilterField('frFilterOrder', (p, v) => { p.order = Math.round(v); });
    this.onFilterField('frFilterQ', (p, v) => { p.q = v; });

    // Show-filter checkbox: Show-RIAA ⇄ Show-filter mutual exclusion (only one reference
    // curve active), enable cascade, tab tile refresh, one-shot compare auto-zoom on entry.
    $('#frFilterShow').prop('checked', prefs.freqRespShowFilter.get());
    $('#frFilterShow').on('change', () => {
      const show = $('#frFilterShow').is(':checked');
      prefs.freqRespShowFilter.set(show);
      if (show) {   // enabling the filter overlay turns RIAA off (symmetric with RIAA-show)
        prefs.freqRespShowRiaa.set(false);
        $('#frRiaa').prop('checked', false);
        this.refreshRiaaEnable();
      }
      this.refreshFilterEnable();
      this.refreshFilterTile();
      if (show && prefs.freqRespFilterCompare.get() && this.view.hasAnyResult()) {
        this.view.autoSetupCompare();
      }
      this.view.render();
    });

    // Filter-compare checkbox: no-measurement veto + one-shot auto-zoom + redraw (mirrors
    // the RIAA compare, reusing the generalized view.autoSetupCompare pipeline).
    $('#frFilterCompare').prop('checked', prefs.freqRespFilterCompare.get());
    $('#frFilterCompare').on('change', () => {
      const enable = $('#frFilterCompare').is(':checked');
      if (enable && !this.view.hasAnyResult()) {
        this.status(t('freqResp.error.compare.noMeasurement'));
        $('#frFilterCompare').prop('checked', false);
        prefs.freqRespFilterCompare.set(false);
        return;
      }
      prefs.freqRespFilterCompare.set(enable);
      if (enable) this.view.autoSetupCompare();
      else this.view.autoSetupMagnitudeRange();   // leaving compare: re-fit the normal view (Java parity, fixed there in parallel)
      this.refreshFilterTile();
      this.view.render();
    });

    // Type / response combos two-way bound to the enum-name prefs. A type change reloads the
    // new type's map entry into the widgets and re-gates + swaps labels / picture.
    $('#frFilterType').val(prefs.freqRespFilterType.get());
    $('#frFilterType').on('change', () => {
      const type = $('#frFilterType').val();
      prefs.freqRespFilterType.set(type);
      this.loadFilterParams(type);
      this.refreshFilterEnable();
      this.refreshFilterTile();
      this.view.render();
    });
    $('#frFilterResponse').val(prefs.freqRespFilterResponse.get());
    $('#frFilterResponse').on('change', () => {
      prefs.freqRespFilterResponse.set($('#frFilterResponse').val());
      this.refreshFilterEnable();
      this.view.render();
    });

    // Mode radios (By-spec = Mode 1, By-order = Mode 2): the mode flag is part of the per-type
    // params (modeOrder). Both radios carry the same handler and read the settled state.
    const modeHandler = () => {
      if (this._filterParamsLoading) return;
      const mode2 = $('#frFilterMode2').is(':checked');
      this.putFilterParam((p) => { p.modeOrder = mode2; });
      this.refreshFilterEnable();
    };
    $('#frFilterMode1').on('change', modeHandler);
    $('#frFilterMode2').on('change', modeHandler);

    // Seed every widget FROM the current type's map entry (the map is authoritative).
    this.loadFilterParams(prefs.freqRespFilterType.get());

    // Audio-format edits move the Nyquist ceiling of every Hz field (Java AUDIO_FORMAT_CHANGED).
    const applyNyquist = () => {
      const n = nyquist();
      for (const id of ['frFilterCenter', 'frFilterPass', 'frFilterStop', 'frFilterOrderPass']) {
        if (this._filterFields[id]) this._filterFields[id].setMax(n);
      }
    };
    $('#inRate').on('input-sample-rate-change change', applyNyquist);

    this.refreshFilterEnable();
  }

  /** Write-through for one filter parameter: applies {@code mutator} to the CURRENT filter
   *  type's map entry and persists it (Java putFilterParam). getFreqRespFilterParams hands
   *  back the live entry (or a fresh fromType default on a miss), so mutate + put round-trips
   *  exactly one entry. The view re-reads the map on its next paint. */
  putFilterParam(mutator) {
    const prefs = this.prefs;
    const type = prefs.freqRespFilterType.get();
    const p = prefs.getFreqRespFilterParams(type);
    mutator(p);
    prefs.putFreqRespFilterParams(type, p);
    this.view.render();
  }

  /** Wires one filter step field to the per-type params map: on a committed edit it writes
   *  setter(entry, value) through putFilterParam. The guard skips writes made while
   *  loadFilterParams is seeding the field (Java onFilterField). */
  onFilterField(id, setter) {
    const field = this._filterFields[id];
    if (!field) return;
    field.onChange = (v) => {
      if (this._filterParamsLoading) return;
      this.putFilterParam((p) => setter(p, v));
    };
  }

  /** Seeds every filter widget (fields + mode radio) FROM {@code type}'s map entry — a missing
   *  entry falls back to fromType defaults. The map is authoritative, so the widgets follow it
   *  and never the reverse. The re-entrancy guard stops the programmatic setValue from writing
   *  the values straight back through the field / radio listeners (Java loadFilterParams). */
  loadFilterParams(type) {
    const $ = this.$;
    const p = this.prefs.getFreqRespFilterParams(type);
    this._filterParamsLoading = true;
    try {
      this._filterFields.frFilterRipple.setValue(p.rippleDb);
      this._filterFields.frFilterStopAtten.setValue(p.stopAttenDb);
      this._filterFields.frFilterCenter.setValue(p.centerHz);
      this._filterFields.frFilterPass.setValue(p.passHz);
      this._filterFields.frFilterStop.setValue(p.stopHz);
      this._filterFields.frFilterOrderPass.setValue(p.orderPassHz);
      this._filterFields.frFilterOrderRipple.setValue(p.orderRippleDb);
      this._filterFields.frFilterOrder.setValue(p.order);
      this._filterFields.frFilterQ.setValue(p.q);
      $('#frFilterMode1').prop('checked', !p.modeOrder);
      $('#frFilterMode2').prop('checked', p.modeOrder);
    } finally {
      this._filterParamsLoading = false;
    }
  }

  /** Deep copy of a FreqRespFilterTypeParams so a preset embeds its OWN snapshot rather than
   *  aliasing the live map entry (Java copyFilterParams). */
  copyFilterParams(s) {
    const c = new FreqRespFilterTypeParams();
    c.modeOrder = s.modeOrder;
    c.rippleDb = s.rippleDb;
    c.stopAttenDb = s.stopAttenDb;
    c.centerHz = s.centerHz;
    c.passHz = s.passHz;
    c.stopHz = s.stopHz;
    c.orderPassHz = s.orderPassHz;
    c.orderRippleDb = s.orderRippleDb;
    c.order = s.order;
    c.q = s.q;
    return c;
  }

  /** Re-runs the Filters enable cascade (Java refreshFilterEnable): every control is gated on
   *  Show-filter; ripple fields depend on the response family; centre / Q on the type; the two
   *  Mode radios gate their own field rows; Compare needs a present measurement. Also swaps the
   *  Passband / Stopband labels (Fc/Fs for LP/HP, PB/SB for BP/notch), the mode field
   *  visibility, and the per-type picture. */
  refreshFilterEnable() {
    const $ = this.$, prefs = this.prefs;
    if ($('#frFilterShow').length === 0) return;
    const show = $('#frFilterShow').is(':checked');
    const type = prefs.freqRespFilterType.get();
    const isBpNotch = type === FilterType.BAND_PASS || type === FilterType.NOTCH;
    const ripple = hasRipple(prefs.freqRespFilterResponse.get());
    const mode2 = $('#frFilterMode2').is(':checked');
    const mode1 = !mode2;

    $('#frFilterCompare').prop('disabled', !(show && this.view.hasAnyResult()));
    $('#frFilterType').prop('disabled', !show);
    $('#frFilterResponse').prop('disabled', !show);
    $('#frFilterMode1').prop('disabled', !show);
    $('#frFilterMode2').prop('disabled', !show);

    const setF = (id, on) => { if (this._filterFields[id]) this._filterFields[id].setDisabled(!on); };
    // Mode-1 rows — ripple only for equiripple families, centre only for band-pass / notch.
    setF('frFilterRipple', show && mode1 && ripple);
    setF('frFilterStopAtten', show && mode1);
    setF('frFilterCenter', show && mode1 && isBpNotch);
    setF('frFilterPass', show && mode1);
    setF('frFilterStop', show && mode1);
    // Passband / Stopband labels read Fc/Fs for LP/HP, PB/SB for BP/notch.
    $('#frFilterPassLbl').text(t(isBpNotch ? 'freqResp.filter.passband.bp' : 'freqResp.filter.passband'));
    $('#frFilterStopLbl').text(t(isBpNotch ? 'freqResp.filter.stopband.bp' : 'freqResp.filter.stopband'));
    // Mode-2 rows — ripple only for equiripple families, Q only for band-pass / notch.
    setF('frFilterOrderPass', show && mode2);
    setF('frFilterOrderRipple', show && mode2 && ripple);
    setF('frFilterOrder', show && mode2);
    setF('frFilterQ', show && mode2 && isBpNotch);

    // Visibility swap: show the active mode's field row, hide the other (Java applyModeVisibility).
    // The per-type picture shows only in Mode 1 (Java issue 10).
    const $body = $('#frFilterPanel .fr-filter-body');
    $body.toggleClass('mode-spec', mode1).toggleClass('mode-order', mode2);
    this.refreshFilterPicture(type, mode1);
  }

  /** Swaps the panel picture to the one matching {@code type} and shows / hides it (Mode 1
   *  only — Java refreshFilterPicture). */
  refreshFilterPicture(type, mode1) {
    const src = { LOW_PASS: 'LPF', HIGH_PASS: 'HPF', BAND_PASS: 'BPF', NOTCH: 'Notch' }[type] || 'LPF';
    this.$('#frFilterPic').attr('src', 'assets/img/' + src + '.svg').css('visibility', mode1 ? '' : 'hidden');
  }

  /** Filters tab tile chips (Java freqRespTabTiles FILTERS branch). */
  refreshFilterTile() {
    const prefs = this.prefs;
    const chips = [];
    if (prefs.freqRespShowFilter.get()) {
      chips.push(t(this.filterTypeKey(prefs.freqRespFilterType.get())));
      if (prefs.freqRespFilterCompare.get()) chips.push('comp');
    }
    this.$('#frFiltersTabSub').html(chips.map((c) => `<span class="tile">${c}</span>`).join(''));
  }

  /** i18n key for a filter type's display name (Java filterTypeKey). */
  filterTypeKey(type) {
    switch (type) {
      case FilterType.HIGH_PASS: return 'freqResp.filter.type.highpass';
      case FilterType.BAND_PASS: return 'freqResp.filter.type.bandpass';
      case FilterType.NOTCH: return 'freqResp.filter.type.notch';
      case FilterType.LOW_PASS:
      default: return 'freqResp.filter.type.lowpass';
    }
  }

  // ===========================================================================
  // Unevenness tab (Java FreqRespTabControl.buildUnevennessTab): response flatness
  // readout mode + parameters — Off / ±dB / Range radios, Notch, dB + start/stop.
  // ===========================================================================

  bindUnevenness() {
    const $ = this.$, prefs = this.prefs;
    const F = UNIT_FAMILIES;
    const nyquist = () => (this.prefs.current().inputSampleRate || 48000) / 2;

    const dbField = new NumericStepField(document.getElementById('frUnevenDb'),
      new NumericStepModel({ family: F.DECIBEL, min: UNEVEN_DB_MIN, max: UNEVEN_DB_MAX, maxDecimals: FILTER_DB_DECIMALS }),
      { tooltipBase: $('#frUnevenDb').attr('title') || '' });
    const startField = new NumericStepField(document.getElementById('frUnevenStart'),
      new NumericStepModel({ family: F.FREQUENCY, min: 1, max: nyquist(), maxDecimals: 9 }),
      { tooltipBase: $('#frUnevenStart').attr('title') || '' });
    const stopField = new NumericStepField(document.getElementById('frUnevenStop'),
      new NumericStepModel({ family: F.FREQUENCY, min: 1, max: nyquist(), maxDecimals: 9 }),
      { tooltipBase: $('#frUnevenStop').attr('title') || '' });
    this._unevenDbField = dbField;
    this._unevenStartField = startField;
    this._unevenStopField = stopField;

    dbField.setValue(prefs.freqRespUnevenDb.get());
    startField.setValue(prefs.freqRespUnevenStartHz.get());
    stopField.setValue(prefs.freqRespUnevenStopHz.get());
    dbField.onChange = (v) => { prefs.freqRespUnevenDb.set(v); this.refreshUnevenTile(); };
    // Cross-field clamp: stop must stay at least start + 1 (Java Bindings.onChange couplings).
    startField.onChange = (v) => {
      const ceil = prefs.freqRespUnevenStopHz.get() - 1.0;
      const val = v > ceil ? ceil : v;
      prefs.freqRespUnevenStartHz.set(val);
      if (val !== v) startField.setValue(val);
      this.refreshUnevenTile();
    };
    stopField.onChange = (v) => {
      const floor = prefs.freqRespUnevenStartHz.get() + 1.0;
      const val = v < floor ? floor : v;
      prefs.freqRespUnevenStopHz.set(val);
      if (val !== v) stopField.setValue(val);
      this.refreshUnevenTile();
    };

    // Notch checkbox two-way bound.
    $('#frUnevenNotch').prop('checked', prefs.freqRespUnevenNotch.get());
    $('#frUnevenNotch').on('change', () => prefs.freqRespUnevenNotch.set($('#frUnevenNotch').is(':checked')));

    // Three-state mode radios bound to the UnevenMode pref (Off / LEVEL / RANGE).
    const modeFor = { frUnevenOff: 'OFF', frUnevenPm: 'LEVEL', frUnevenRange: 'RANGE' };
    const setModeChecked = (mode) => {
      $('#frUnevenOff').prop('checked', mode === 'OFF');
      $('#frUnevenPm').prop('checked', mode === 'LEVEL');
      $('#frUnevenRange').prop('checked', mode === 'RANGE');
    };
    setModeChecked(prefs.freqRespUnevenMode.get());
    for (const id of Object.keys(modeFor)) {
      $('#' + id).on('change', () => {
        prefs.freqRespUnevenMode.set(modeFor[id]);
        this.refreshUnevenEnable();
        this.refreshUnevenTile();
      });
    }

    // Audio-format edits move the Nyquist ceiling of the range fields (Java AUDIO_FORMAT_CHANGED).
    $('#inRate').on('input-sample-rate-change change', () => {
      const n = nyquist();
      startField.setMax(n);
      stopField.setMax(n);
    });

    this.refreshUnevenEnable();
  }

  /** Each Unevenness mode enables only its own row's fields — the dB field iff LEVEL, the
   *  start / stop fields iff RANGE, the Notch checkbox in both active modes, none in OFF
   *  (Java refreshUnevenEnable). */
  refreshUnevenEnable() {
    const mode = this.prefs.freqRespUnevenMode.get();
    if (this._unevenDbField) this._unevenDbField.setDisabled(mode !== 'LEVEL');
    this.$('#frUnevenNotch').prop('disabled', mode === 'OFF');
    const range = mode === 'RANGE';
    if (this._unevenStartField) this._unevenStartField.setDisabled(!range);
    if (this._unevenStopField) this._unevenStopField.setDisabled(!range);
  }

  /** Unevenness tab tile chip (Java freqRespTabTiles UNEVENNESS branch): the mode readout. */
  refreshUnevenTile() {
    const prefs = this.prefs;
    let readout;
    switch (prefs.freqRespUnevenMode.get()) {
      case 'RANGE':
        readout = this.formatShortHz(prefs.freqRespUnevenStartHz.get()) + '–'
          + this.formatShortHz(prefs.freqRespUnevenStopHz.get());
        break;
      case 'LEVEL':
        readout = '±' + this.trimNum(prefs.freqRespUnevenDb.get()) + ' dB';
        break;
      case 'OFF':
      default:
        readout = t('freqResp.uneven.off');
        break;
    }
    this.$('#frUnevenTabSub').html(`<span class="tile">${readout}</span>`);
  }

  /** Compact Hz for the Unevenness tile: 20 → "20", 20000 → "20k" (Java formatShortHz). */
  formatShortHz(hz) {
    if (hz >= 1000) {
      const k = hz / 1000;
      return (k === Math.floor(k) ? k.toFixed(0) : k.toFixed(1)) + 'k';
    }
    return this.trimNum(hz);
  }

  /** Compact dB value for the Unevenness tile: 3.0 → "3", 1.5 → "1.5" (Java trimNum). */
  trimNum(v) {
    if (v === Math.floor(v)) return v.toFixed(0);
    return String(v).replace(/0+$/, '').replace(/\.$/, '');
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
    // Filters — the per-type scalars live in the params map; the preset embeds ONE deep copy
    // of the entry for its captured filter type (Java captureCurrentFreqRespPreset).
    p.showFilter = prefs.freqRespShowFilter.get();
    p.filterCompare = prefs.freqRespFilterCompare.get();
    p.filterType = prefs.freqRespFilterType.get();
    p.filterResponse = prefs.freqRespFilterResponse.get();
    p.filterParams = this.copyFilterParams(prefs.getFreqRespFilterParams(prefs.freqRespFilterType.get()));
    // Unevenness
    p.unevenMode = prefs.freqRespUnevenMode.get();
    p.unevenNotch = prefs.freqRespUnevenNotch.get();
    p.unevenDb = prefs.freqRespUnevenDb.get();
    p.unevenStartHz = prefs.freqRespUnevenStartHz.get();
    p.unevenStopHz = prefs.freqRespUnevenStopHz.get();
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
    // Filters — write the embedded params copy back into the map entry for the preset's filter
    // type; the filter widgets then reload from the map below (Java applyFreqRespPreset).
    prefs.freqRespShowFilter.set(p.showFilter);
    prefs.freqRespFilterCompare.set(p.filterCompare);
    prefs.freqRespFilterType.set(p.filterType);
    prefs.freqRespFilterResponse.set(p.filterResponse);
    prefs.putFreqRespFilterParams(p.filterType, this.copyFilterParams(p.filterParams));
    // Unevenness
    prefs.freqRespUnevenMode.set(p.unevenMode);
    prefs.freqRespUnevenNotch.set(p.unevenNotch);
    prefs.freqRespUnevenDb.set(p.unevenDb);
    prefs.freqRespUnevenStartHz.set(p.unevenStartHz);
    prefs.freqRespUnevenStopHz.set(p.unevenStopHz);
    prefs.save();
    // Reflect the recalled prefs into the live widgets (the NumericStepFields are owned by
    // app.js; reseed the ones this control owns + the derived label).
    const $ = this.$;
    $('#frStart').val(p.startHz); $('#frStop').val(p.stopHz); $('#frAmp').val(p.amplitudeVrms);
    $('#frPoints').val(p.sweepPoints); $('#frLeadIn').val(p.leadInSec);
    $('#frFft').val(String(p.fftSize)); $('#frDither').val(String(p.ditherBits));
    $('#frRiaa').prop('checked', p.showRiaa); $('#frRiaaRev').prop('checked', p.reverseRiaa);
    $('#frRiaaIec').prop('checked', p.iecAmendment); $('#frRiaaCompare').prop('checked', p.compareMode);
    // Filters / Unevenness widgets (this control owns them): reflect the recalled prefs.
    $('#frFilterShow').prop('checked', p.showFilter); $('#frFilterCompare').prop('checked', p.filterCompare);
    $('#frFilterType').val(p.filterType); $('#frFilterResponse').val(p.filterResponse);
    this.loadFilterParams(p.filterType);   // reload the widgets from the just-written map entry
    $('#frUnevenOff').prop('checked', p.unevenMode === 'OFF');
    $('#frUnevenPm').prop('checked', p.unevenMode === 'LEVEL');
    $('#frUnevenRange').prop('checked', p.unevenMode === 'RANGE');
    $('#frUnevenNotch').prop('checked', p.unevenNotch);
    if (this._unevenDbField) this._unevenDbField.setValue(p.unevenDb);
    if (this._unevenStartField) this._unevenStartField.setValue(p.unevenStartHz);
    if (this._unevenStopField) this._unevenStopField.setValue(p.unevenStopHz);
    this.refreshFftLabel();
    this.refreshRiaaEnable();   // also re-runs refreshFilterEnable
    this.refreshUnevenEnable();
    this.refreshFilterTile();
    this.refreshUnevenTile();
    // jQuery .prop('checked') fires no change event, so mirror the one-shot autoSetupCompare
    // explicitly when the recalled preset lands in compare+Show with a result.
    if (this.prefs.freqRespShowRiaa.get() && this.prefs.freqRespCompareMode.get() && this.view.hasAnyResult()) {
      this.view.autoSetupCompare();
    }
    if (this.prefs.freqRespShowFilter.get() && this.prefs.freqRespFilterCompare.get() && this.view.hasAnyResult()) {
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
      const text = this.io.bytesToText(f.bytes);
      const st = loadFrc(text);
      const cL = st.left, cR = st.right;
      const params = { startHz: cL.freqs[0], stopHz: cL.freqs[cL.freqs.length - 1], sweepPoints: cL.freqs.length, durationSec: 0, leadInSec: 0, amplitudeVrms: 0 };
      // Nyquist for a loaded file comes from the file's own sample_rate_hz header; only legacy
      // headerless files fall back to the live input rate (Java openLoadDialog change).
      const fileSr = readSampleRateHz(text);
      const sr = fileSr > 0 ? fileSr : this.engine.config.inRate;
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
