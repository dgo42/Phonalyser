/*
 * Phonalyser web — the Preferences dialog (staged audio/L&F/Osc/FFT/FR prefs, commit on OK).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of gui/preferences/PreferencesDialog.
 */

import { MessageBus } from '../bus/message-bus.js';
import { Events } from '../bus/events.js';
import { NumericStepField, NumericStepModel, UNIT_FAMILIES } from '../widgets/numeric-step-field.js';
import { CardSection } from './card-section.js';

// The ten NumericStepField rows across the Osc / FFT / FreqResp tabs — min/max/wheelStep/
// arrowStep/decimals lifted verbatim from PreferencesDialog.java (constants at :100-133, field
// ctors at :316-596). All FIXED policy (family, min, max, wheelStep, arrowStep, decimals).
const F = UNIT_FAMILIES;
const PREF_FIELD_SPECS = {
  // Oscilloscope tab
  prefOscMeasAvg:   { model: { family: F.SECONDS, min: 0.5, max: 100, wheelStep: 0.5, arrowStep: 0.5, decimals: 1 } },
  prefOscLineWidth: { model: { family: F.PIXEL,   min: 1,   max: 5,   wheelStep: 0.5, arrowStep: 0.5, decimals: 1 } },
  prefOscDotDia:    { model: { family: F.PIXEL,   min: 3,   max: 12,  wheelStep: 1,   arrowStep: 1,   decimals: 0 } },
  prefOscPersistManual: { model: { family: F.SECONDS, min: 0.1, max: 60, wheelStep: 0.5, arrowStep: 0.5, decimals: 1 } },
  // FFT tab
  prefFftLineWidth: { model: { family: F.PIXEL,   min: 1,   max: 5,   wheelStep: 0.5, arrowStep: 0.5, decimals: 1 } },
  prefFftDotDia:    { model: { family: F.PIXEL,   min: 3,   max: 12,  wheelStep: 1,   arrowStep: 1,   decimals: 0 } },
  prefFftStrongTone:{ model: { family: F.DECIBEL, min: 10,  max: 140, wheelStep: 10,  arrowStep: 1,   decimals: 1 } },
  // FreqResp tab (prefFrMaxNyq: `pct` → its onChange keeps the live (Hz) readout in sync)
  prefFrLineWidth:  { model: { family: F.PIXEL,   min: 1,   max: 5,   wheelStep: 0.5, arrowStep: 0.5, decimals: 1 } },
  prefFrMaxNyq:     { pct: true, model: { family: F.PERCENT, min: 83, max: 100, wheelStep: 0.5, arrowStep: 1, decimals: 1 } },
  prefFrSmooth:     { model: { family: F.NONE,    min: 0,   max: 100, wheelStep: 1,   arrowStep: 1,   decimals: 0 } },
};

// Staged exactly like the audio controls: seeded from the live prefs on open, written to
// prefs + applied ONLY on OK (Cancel/Esc/X leave the prefs untouched — the next open
// re-seeds). Every label reuses an existing Java i18n key; no new keys. Colours are stored
// as 0xRRGGBB ints, the <input type=color> uses #rrggbb; UI fonts are "Family|size|style".
const intToHex = (n) => '#' + (((n | 0) & 0xffffff)).toString(16).padStart(6, '0');
const hexToInt = (s) => parseInt(String(s).replace('#', ''), 16) | 0;
// UI fonts are stored "Family|size|style". The web has no native font dialog, so the picker
// is a curated family dropdown + a size field — the faithful web analog of
// PreferencesDialog.buildFontRow's OS FontDialog (minus OS font enumeration the browser
// can't do). The style (normal/bold) is fixed per row and preserved from the stored spec.
const FONT_FAMILIES = ['Consolas', 'Cascadia Code', 'Courier New', 'Menlo', 'Monaco', 'monospace',
  'Segoe UI', 'Arial', 'Tahoma', 'Verdana', 'system-ui', 'sans-serif', 'serif'];
function fillFontFamilies(sel) {
  const $s = $(sel);
  if ($s.children('option').length) return;
  for (const f of FONT_FAMILIES) $s.append(`<option value="${f}">${f}</option>`);
}
function seedFont(spec, familySel, sizeSel, boldChk, italicChk) {
  fillFontFamilies(familySel);
  const p = String(spec).split('|');
  const fam = p[0] || 'Consolas', size = p[1] || '9', style = p[2] || 'normal';
  if (!$(familySel).find(`option[value="${fam}"]`).length) $(familySel).append(`<option value="${fam}">${fam}</option>`);
  $(familySel).val(fam);
  $(sizeSel).val(size);
  $(boldChk).prop('checked', style.includes('bold'));
  $(italicChk).prop('checked', style.includes('italic'));
}
// Bold + italic are the only styles an SWT Font carries (Fonts.toSpec); combine as "bold+italic".
function buildFont(familySel, sizeSel, boldChk, italicChk) {
  const fam = $(familySel).val() || 'Consolas';
  const size = Math.max(6, Math.min(48, parseInt($(sizeSel).val(), 10) || 9));
  const b = $(boldChk).is(':checked'), i = $(italicChk).is(':checked');
  const style = (b && i) ? 'bold+italic' : b ? 'bold' : i ? 'italic' : 'normal';
  return `${fam}|${size}|${style}`;
}

export class PreferencesDialog {
  /**
   * @param engine the AudioEngine (capture reopen + generator restart on a committed device/rate).
   * @param prefs  Preferences.
   * @param deps   {modal, RATES, stepFields, inRate, outRate, isBusy, setBusy, fftView}
   *               - modal: the bootstrap Modal for #prefsModal
   *               - RATES: the full output sample-rate list (input-rate fallback when native unknown)
   *               - stepFields: the NumericStepField map (FR/generator Nyquist ceilings re-pin)
   *               - inRate / outRate: () => the live input/output sample rate (Nyquist source)
   *               - isBusy / setBusy: the shared re-entrancy guard accessors (capture-reopen serialize)
   *               - fftView: the FFT view (applyPrefs() on OK so it re-reads its colour/line prefs)
   *               - deviceStore: the DeviceProfileStore — apply the resolved card's per-channel
   *                 full-scale on a device selection (Java SharedCapture / GeneratorController /
   *                 PreferencesDialog applyInput|OutputDeviceProfile)
   */
  constructor(engine, prefs, { modal, RATES, stepFields, inRate, outRate, isBusy, setBusy, fftView, deviceStore, cardEditorDialog, showConfirm }) {
    this.engine = engine;
    this.prefs = prefs;
    this.modal = modal;
    this.RATES = RATES;
    this.stepFields = stepFields;
    this.inRate = inRate;
    this.outRate = outRate;
    this.isBusy = isBusy;
    this.setBusy = setBusy;
    this.fftView = fftView;
    this.deviceStore = deviceStore;
    // The Audio-tab per-card profile sections (Java PreferencesDialog.CardSection) — the card
    // combo + edit button + ranges table for each direction. Built only when the device store +
    // card editor are injected (they always are in the live app).
    this.inputCard = (deviceStore && cardEditorDialog) ? new CardSection(prefs, deviceStore, cardEditorDialog, {
      input: true, comboSel: '#inCardSel', editSel: '#inCardEdit', rangesSel: '#inRanges',
      deviceLabel: () => this.inputDeviceLabel(), showConfirm, onChanged: () => this.refreshFsReadouts(),
    }) : null;
    this.outputCard = (deviceStore && cardEditorDialog) ? new CardSection(prefs, deviceStore, cardEditorDialog, {
      input: false, comboSel: '#outCardSel', editSel: '#outCardEdit', rangesSel: '#outRanges',
      deviceLabel: () => this.outputDeviceLabel(), showConfirm, onChanged: () => this.refreshFsReadouts(),
    }) : null;
    // True while the Preferences dialog is open: its device/rate controls STAGE
    // edits (Java PreferencesDialog edits a detached copy) and apply only on OK.
    this._staging = false;
    // Snapshot of the audio-control DOM values taken when the dialog opens, so a
    // Cancel/close restores the on-screen selections to what they were on open.
    this._audioSnapshot = null;
    // Stage-on-open / commit-on-OK / discard-on-close (Java PreferencesDialog).
    // Set true by the OK button so the `hidden` handler commits instead of reverting;
    // any other dismissal (Cancel / backdrop / Esc / X) leaves it false → revert.
    this._okClicked = false;
  }

  // ----- Preferences dialog: stage on open, commit on OK, discard on Cancel -----
  // Mirrors Java PreferencesDialog (edits a detached Preferences.copyForDialog(),
  // hands it to applyFromDialog() only on OK; Cancel touches nothing live).  The
  // dialog's only live controls are the audio device/rate <select>s, so the
  // "detached copy" here is just the four DOM values; the live prefs / engine are
  // untouched until applyAudioPrefs() commits them.

  /** Captures the current audio-control DOM values so a Cancel/close can restore
   *  them.  Called on dialog open. */
  snapshotAudioPrefs() {
    this._audioSnapshot = {
      inSel:   $('#inSel').val(),
      outSel:  $('#outSel').val(),
      inRate:  $('#inRate').val(),
      outRate: $('#outRate').val(),
    };
  }

  /** Restores the audio-control selects to the snapshot taken on open — the
   *  discard path for Cancel / backdrop / Esc / X (no live prefs were written
   *  while staging, so only the on-screen widgets need rolling back). */
  restoreAudioPrefs() {
    if (!this._audioSnapshot) return;
    $('#inSel').val(this._audioSnapshot.inSel);
    $('#outSel').val(this._audioSnapshot.outSel);
    $('#outRate').val(this._audioSnapshot.outRate);
    // #inRate is DERIVED from the input device's native rate (one option), so rebuild it for the
    // RESTORED device — its options changed while staging. Discard path → no persist.
    const native = parseInt($('#inSel option:selected').attr('data-rate'), 10);
    if (native > 0) {
      $('#inRate').empty().append(`<option value="${native}">${native} Hz</option>`).val(String(native));
    } else {
      $('#inRate').empty(); this.RATES.forEach((r) => $('#inRate').append(`<option value="${r}">${r} Hz</option>`));
      $('#inRate').val(this._audioSnapshot.inRate);
    }
    this._audioSnapshot = null;
  }

  /** Commits the staged audio selections to the live prefs and applies them
   *  (persist, re-pin the FR/generator Nyquist field bounds, re-acquire the
   *  capture device, restart the generator).  The OK path of the dialog. */
  async applyAudioPrefs() {
    const inDev = $('#inSel').val(), outDev = $('#outSel').val();
    const inR = parseInt($('#inRate').val(), 10), outR = parseInt($('#outRate').val(), 10);
    const bp = this.prefs.current();
    const captureChanged = bp.inputDeviceName  !== inDev || bp.inputSampleRate  !== inR;
    const outputChanged  = bp.outputDeviceName !== outDev || bp.outputSampleRate !== outR;

    // Two-phase bracket (Java PreferencesDialog OK → MainWindow.before/afterApplyBackendChanges):
    // STOP the live consumers each CHANGED direction owns BEFORE the commit — while the current
    // device is still open — then COMMIT, then RESTART exactly what was running on the new config.
    // Capture (scope+FFT) and the generator are bracketed INDEPENDENTLY: Web Audio's input and
    // output are separate devices, so an input-only change must not disturb a playing generator
    // (unlike Java's shared-clock backend, which bounces all three on any audio change). The whole
    // apply runs under the busy guard so it can't overlap another reopen; unlike the old code it is
    // never SKIPPED when busy — an OK must never be silently dropped, leaving streams at the old rate.
    this.setBusy(true);
    try {
      await this.engine.beforeApplyBackendChanges(captureChanged, outputChanged);

      // Commit the staged working copy → live prefs (Java applyFromDialog).
      bp.inputDeviceName = inDev; bp.outputDeviceName = outDev;
      if (inR)  bp.inputSampleRate  = inR;
      if (outR) bp.outputSampleRate = outR;
      this.prefs.save();
      this._audioSnapshot = null;

      // Apply the committed devices' per-card per-channel full-scale (Java
      // PreferencesDialog OK path: applyInput/OutputDeviceProfile on commit).
      this._applyDeviceProfiles();

      // Re-pin the Nyquist-derived field bounds from the committed input/output rates.
      const inNyq = this.inRate() / 2;
      for (const id of ['frStart', 'frStop']) if (this.stepFields[id]) this.stepFields[id].setMax(inNyq);
      const outNyq = this.outRate() / 2;
      for (const id of ['toneHz', 'tone2Hz', 'sweepStart', 'sweepStop']) if (this.stepFields[id]) this.stepFields[id].setMax(outNyq);

      // Flow the committed device/rate into the live engine config so each restart re-acquires there.
      if (captureChanged) {
        this.engine.config.inDeviceId = inDev;
        this.engine.config.inRate = inR || this.engine.config.inRate;
      }
      if (outputChanged) this.engine.config.outDeviceId = outDev;

      await this.engine.afterApplyBackendChanges(captureChanged, outputChanged);
    } finally { this.setBusy(false); }
  }

  // ----- Preferences dialog: Look&Feel / Oscilloscope / FFT / FreqResp tabs -----

  /** Show one prefs panel, hide the rest (the tab strip switches panels — no Bootstrap tabs). */
  prefsTab(id) {
    $('#prefsTabs .nav-link').each(function () { $(this).toggleClass('active', this.dataset.prefsPanel === id); });
    $('.prefs-panel').each(function () { $(this).toggleClass('d-none', this.dataset.prefsPanel !== id); });
  }

  /** Seed the Look&Feel / Oscilloscope / FFT / FreqResp controls from the live prefs (open). */
  seedPrefsTabs() {
    const prefs = this.prefs;
    $('#prefTabOrientation').val(prefs.tabOrientation.get());
    $('#prefSmallIcons').prop('checked', prefs.smallIconsInMainTab.get());
    $('#prefShowTips').prop('checked', prefs.showTipsAtStartup.get());
    seedFont(prefs.uiFontNormal.get(), '#prefUiFontFamily', '#prefUiFontSize', '#prefUiFontWBold', '#prefUiFontWItalic');
    seedFont(prefs.uiFontBold.get(), '#prefUiFontBoldFamily', '#prefUiFontBoldSize', '#prefUiFontBoldWBold', '#prefUiFontBoldWItalic');

    // NumericStepField rows: seed the model (auto-formats the unit-in-text). prefFrMaxNyq holds a
    // percent (fraction × 100); the others hold their canonical pref value directly.
    this.prefFields.prefOscMeasAvg.setValue(prefs.oscMeasurementAverageSeconds.get());
    this.prefFields.prefOscLineWidth.setValue(prefs.oscLineWidth.get());
    this.prefFields.prefOscDotDia.setValue(prefs.oscDotDiameter.get());
    // Persistence mode combo + manual-seconds field (enabled only when mode == MANUAL,
    // re-gated live on combo change; see the #prefOscPersistence handler in bind()).
    $('#prefOscPersistence').val(prefs.oscPersistenceMode.get());
    this.prefFields.prefOscPersistManual.setValue(prefs.oscPersistenceManualSeconds.get());
    this.gateOscPersistManual();
    this.prefFields.prefFftLineWidth.setValue(prefs.fftLineWidth.get());
    this.prefFields.prefFftDotDia.setValue(prefs.fftHarmonicDotDiameter.get());
    this.prefFields.prefFftStrongTone.setValue(prefs.fftStrongToneRelDb.get());
    this.prefFields.prefFrLineWidth.setValue(prefs.freqRespLineWidth.get());
    this.prefFields.prefFrMaxNyq.setValue(prefs.freqRespNyquistFraction.get() * 100);
    this.prefFields.prefFrSmooth.setValue(prefs.freqRespCompareSmoothWindow.get());
    this.updateFrMaxNyqHz();

    // Colour buttons: seed the native picker value, then paint the swatch + hex text.
    this.seedColor('#prefOscLeftColor', prefs.oscLeftChannelColor.get());
    this.seedColor('#prefOscRightColor', prefs.oscRightChannelColor.get());
    this.seedColor('#prefFftLineColor', prefs.fftLineColor.get());
    this.seedColor('#prefFftBgColor', prefs.fftChartBackgroundColor.get());
    this.seedColor('#prefFftDotColor', prefs.fftHarmonicDotColor.get());
    this.seedColor('#prefFftFilterColor', prefs.fftFreqRespColor.get());
    this.seedColor('#prefFftBeforeCalColor', prefs.fftBeforeCalDotColor.get());
    this.seedColor('#prefFftCalColor', prefs.fftCalOverlayColor.get());
    this.seedColor('#prefFrSignalColor', prefs.freqRespSignalColor.get());
    this.seedColor('#prefFrPhaseColor', prefs.freqRespPhaseColor.get());
    this.seedColor('#prefFrRefColor', prefs.freqRespReferenceColor.get());
    this.seedColor('#prefFrBgColor', prefs.freqRespBackgroundColor.get());

    $('#prefFrNotch').prop('checked', prefs.freqRespNotchEnabled.get());
    $('#prefFrNotchHz').val(String(prefs.freqRespNotchBaseHz.get()));
  }

  /** Seeds one colour picker from a 0xRRGGBB int and paints its swatch + hex text. */
  seedColor(sel, rgbInt) {
    const inp = $(sel)[0];
    if (!inp) return;
    inp.value = intToHex(rgbInt);
    this.paintColorButton(inp);
  }

  /** Live "(… Hz/kHz)" readout next to the Max-analysed-frequency field — mirrors
   *  PreferencesDialog.formatMaxFreqLabel: shows the concrete band (typed % of the
   *  live input Nyquist), tracking the field on every edit. */
  updateFrMaxNyqHz() {
    const sr = this.inRate();
    let hz = '— Hz';
    if (sr > 0) {
      // Parse the leading number of the input text so the (Hz) readout tracks live typing
      // (before commit) as well as stepper/commit changes — the committed "95.0 %" also leads
      // with the number, so one parse serves both (Java nyqField selectionListener reads the value).
      const raw = parseFloat($('#prefFrMaxNyq').val());
      const pct = Math.max(83, Math.min(100, Number.isFinite(raw) ? raw : 100));
      const f = sr * 0.5 * (pct / 100);
      hz = (f >= 1000) ? `${(f / 1000).toFixed(2)} kHz` : `${f.toFixed(0)} Hz`;
    }
    $('#prefFrMaxNyqHz').text(`(${hz})`);
  }

  /** Enables the manual-persistence field only when the combo is on "Manual" — mirrors
   *  PreferencesDialog.java:355-357 (setEnabled + onChange re-gate). Called from seed and
   *  from the #prefOscPersistence change handler. */
  gateOscPersistManual() {
    const f = this.prefFields.prefOscPersistManual;
    if (f) f.setDisabled($('#prefOscPersistence').val() !== 'MANUAL');
  }

  /** Commit all four tabs' controls to the live prefs and apply (the OK path). */
  applyPrefsTabs() {
    const prefs = this.prefs;
    // Commit any pending text in each NumericStepField (Java NumericStepField commits on focus-out;
    // on OK the user may not have blurred), then read the already-clamped canonical value — exactly
    // the DacCalibrationDialog OK path (field.model.commit(input.value); field.getValue()).
    const fv = (id) => {
      const f = this.prefFields[id];
      if (!f) return NaN;
      f.model.commit(f.input.value.trim());
      return f.getValue();
    };

    prefs.tabOrientation.set($('#prefTabOrientation').val());
    prefs.smallIconsInMainTab.set($('#prefSmallIcons').is(':checked'));
    prefs.showTipsAtStartup.set($('#prefShowTips').is(':checked'));
    prefs.uiFontNormal.set(buildFont('#prefUiFontFamily', '#prefUiFontSize', '#prefUiFontWBold', '#prefUiFontWItalic'));
    prefs.uiFontBold.set(buildFont('#prefUiFontBoldFamily', '#prefUiFontBoldSize', '#prefUiFontBoldWBold', '#prefUiFontBoldWItalic'));

    prefs.oscMeasurementAverageSeconds.set(fv('prefOscMeasAvg'));
    prefs.oscLineWidth.set(fv('prefOscLineWidth'));
    prefs.oscDotDiameter.set(Math.round(fv('prefOscDotDia')));
    prefs.oscPersistenceMode.set($('#prefOscPersistence').val());
    prefs.oscPersistenceManualSeconds.set(fv('prefOscPersistManual'));
    prefs.oscLeftChannelColor.set(hexToInt($('#prefOscLeftColor').val()));
    prefs.oscRightChannelColor.set(hexToInt($('#prefOscRightColor').val()));

    prefs.fftLineWidth.set(fv('prefFftLineWidth'));
    prefs.fftHarmonicDotDiameter.set(Math.round(fv('prefFftDotDia')));
    prefs.fftStrongToneRelDb.set(fv('prefFftStrongTone'));
    prefs.fftLineColor.set(hexToInt($('#prefFftLineColor').val()));
    prefs.fftChartBackgroundColor.set(hexToInt($('#prefFftBgColor').val()));
    prefs.fftHarmonicDotColor.set(hexToInt($('#prefFftDotColor').val()));
    prefs.fftFreqRespColor.set(hexToInt($('#prefFftFilterColor').val()));
    prefs.fftBeforeCalDotColor.set(hexToInt($('#prefFftBeforeCalColor').val()));
    prefs.fftCalOverlayColor.set(hexToInt($('#prefFftCalColor').val()));

    prefs.freqRespLineWidth.set(fv('prefFrLineWidth'));
    prefs.freqRespNyquistFraction.set(fv('prefFrMaxNyq') / 100);
    // FreqResp Nyquist fraction → freq-window clamp (Java Preferences.applyFromDialog,
    // Preferences.java:797-805): if the new max-band drops below the current right edge,
    // pull freqMaxHz (and freqMinHz if needed) in so the view re-clamps to the new ceiling.
    {
      const sr = this.inRate();
      const maxBand = (sr > 0 ? sr * 0.5 : 24000.0) * prefs.freqRespNyquistFraction.get();
      if (prefs.freqRespFreqMaxHz.get() > maxBand) {
        prefs.freqRespFreqMaxHz.set(maxBand);
        if (prefs.freqRespFreqMinHz.get() > maxBand) prefs.freqRespFreqMinHz.set(Math.max(1.0, maxBand * 0.5));
      }
    }
    prefs.freqRespCompareSmoothWindow.set(Math.round(fv('prefFrSmooth')));
    prefs.freqRespNotchEnabled.set($('#prefFrNotch').is(':checked'));
    prefs.freqRespNotchBaseHz.set(parseInt($('#prefFrNotchHz').val(), 10) || 50);
    prefs.freqRespSignalColor.set(hexToInt($('#prefFrSignalColor').val()));
    prefs.freqRespPhaseColor.set(hexToInt($('#prefFrPhaseColor').val()));
    prefs.freqRespReferenceColor.set(hexToInt($('#prefFrRefColor').val()));
    prefs.freqRespBackgroundColor.set(hexToInt($('#prefFrBgColor').val()));
    prefs.save();

    // Apply immediately (no restart): Look & Feel re-lays out the main tabs; the FFT view
    // re-reads its prefs; the scope re-renders every paint so it picks up width / colour /
    // measurement-average on the next frame. (FreqResp view picks the values up when it
    // exists, part 5.)
    this.applyLookAndFeel();
    if (typeof this.fftView !== 'undefined' && this.fftView && this.fftView.applyPrefs) this.fftView.applyPrefs();
    // Fire the FreqResp range-changed event so the FreqResp host re-clamps its view to the
    // new Nyquist ceiling and re-syncs its scrollbars (Java PreferencesDialog OK handler,
    // PreferencesDialog.java:784: bus.publish(Events.FREQRESP_RANGE_CHANGED)).
    MessageBus.instance().publish(Events.FREQRESP_RANGE_CHANGED);
  }

  /** Applies Look & Feel prefs LIVE (no reload): main-tab orientation (TOP strip / LEFT
   *  vertical icon sidebar) + small icons, by toggling body classes the CSS keys off —
   *  faithful to MainTab's live orientation / icon-size rebuild. Called on load + on OK. */
  applyLookAndFeel() {
    const prefs = this.prefs;
    document.body.classList.toggle('orient-left', prefs.tabOrientation.get() === 'LEFT');
    document.body.classList.toggle('small-icons', prefs.smallIconsInMainTab.get());
    // UI font → CSS variables the measurement table / readouts consume (Java applies
    // uiFontNormal there). Point size → px at the conventional 96 dpi (1 pt ≈ 1.333 px).
    const p = String(prefs.uiFontNormal.get()).split('|');
    const style = p[2] || 'normal';
    const root = document.documentElement.style;
    root.setProperty('--ui-font', p[0] || 'Consolas');
    root.setProperty('--ui-font-size', Math.round((parseFloat(p[1]) || 9) * 1.333) + 'px');
    root.setProperty('--ui-font-weight', style.includes('bold') ? 'bold' : 'normal');
    root.setProperty('--ui-font-style', style.includes('italic') ? 'italic' : 'normal');
  }

  // Enumerate input/output devices and populate the selects (runs on app load AND
  // from the Preferences "Scan devices" button — no dialog needed to get going).
  async scan() {
    if (this.isBusy()) return;   // serialize with the live capture reopen — don't probe a device mid-reopen
    this.setBusy(true);
    $('#scan').prop('disabled', true);
    try {
      const { inputs, outputs } = await this.engine.scanDevices();
      // Inputs whose native sample rate was determined are listed with that rate; on engines that
      // can't determine any rate scanDevices falls back to listing them with no rate (suffix hidden).
      $('#inSel').empty(); inputs.forEach(d => {
        const suffix = (d.nativeRate > 0) ? ` — ${d.nativeRate} Hz` : '';
        $('#inSel').append(`<option value="${d.id}" data-rate="${d.nativeRate || ''}">${d.label}${suffix}</option>`);
      });
      $('#outSel').empty(); outputs.forEach(d => $('#outSel').append(`<option value="${d.id}">${d.label}</option>`));
      // Restore the persisted device selection when its id is still present.
      const inDev = this.prefs.current().inputDeviceName, outDev = this.prefs.current().outputDeviceName;
      if (inDev && inputs.some(d => d.id === inDev)) $('#inSel').val(inDev);
      if (outDev && outputs.some(d => d.id === outDev)) $('#outSel').val(outDev);
      this.applyInputDeviceRate();   // #inRate shows ONLY the selected device's native rate
      // A rescan repopulates the device selects (no change event) — re-derive the card combos +
      // range tables + FS readouts for the restored selection.
      if (this.inputCard) this.inputCard.refresh();
      if (this.outputCard) this.outputCard.refresh();
      this.refreshFsReadouts();
      // Seed the live engine config from the freshly-selected devices. The pane Record
      // paths call readConfig() before opening the device, but the FreqResp sweep and the
      // Tune-notch wizard read engine.config DIRECTLY — so on a cold page (before any
      // scope/gen/FFT start ran readConfig) config.inDeviceId was still '' and their
      // getUserMedia({deviceId:{exact:''}}) threw OverconstrainedError → the measurement
      // couldn't start until a scope/gen start happened to populate it. Seed it here so
      // every path has the configured device from load. Skipped while the Preferences
      // dialog is staging (it commits its own selection on OK via applyAudioPrefs).
      if (!this._staging) {
        this.engine.config.inDeviceId = $('#inSel').val();
        this.engine.config.outDeviceId = $('#outSel').val();
        this.engine.config.inRate = parseInt($('#inRate').val(), 10) || this.engine.config.inRate;
        this.engine.config.outRate = parseInt($('#outRate').val(), 10) || this.engine.config.outRate;
        // Apply the resolved card's per-channel full-scale for the selected devices
        // (Java: applyInput/OutputDeviceProfile on the initial capture / generator open).
        // resolveDeviceProfile matches the human LABEL (substring), not the deviceId the
        // <select> value carries — so pass the option text.
        this._applyDeviceProfiles();
      }
      $('#status').text(`${inputs.length} input(s), ${outputs.length} output(s) found — pick devices and press ▶.`);
    } catch (e) { $('#status').text('scan failed: ' + e.message); }
    finally { this.setBusy(false); $('#scan').prop('disabled', false); }
  }

  // The input rate IS the selected device's native rate — the only truly meaningful input rate
  // (docs/htmls/audio-devices.html): requesting any other rate just resamples. So #inRate lists
  // EXACTLY that one rate (the full rate list applies only to the output / DAC). When the native
  // rate couldn't be determined (non-Chromium fallback) the full rate list is shown so the user
  // can still choose. During the Preferences dialog only the DOM value is staged — applyAudioPrefs()
  // commits it on OK (no premature prefs.save()).
  applyInputDeviceRate() {
    const native = parseInt($('#inSel option:selected').attr('data-rate'), 10);
    if (native > 0) {
      $('#inRate').empty().append(`<option value="${native}">${native} Hz</option>`).val(String(native));
      if (this._staging) return;
      this.prefs.current().inputSampleRate = native; this.prefs.save();
    } else {
      // Native rate unknown → graceful fallback to the full list (keep/restore the persisted value).
      const saved = parseInt(this.prefs.current().inputSampleRate, 10) || this.RATES[0];
      $('#inRate').empty();
      this.RATES.forEach((r) => $('#inRate').append(`<option value="${r}">${r} Hz</option>`));
      $('#inRate').val(String(saved));
    }
  }

  /** Applies the resolved per-card per-channel full-scale for the currently-selected
   *  input + output devices (Java applyInput/OutputDeviceProfile). resolveDeviceProfile
   *  matches the human device LABEL (the option text) as a substring — the <select>
   *  value carries the Web Audio deviceId, which the recognition patterns do not match.
   *  A no-op when the device resolves to no card (the legacy scalars stand). */
  _applyDeviceProfiles() {
    if (!this.deviceStore) return;
    this.deviceStore.applyInputDeviceProfile(this.inputDeviceLabel());
    this.deviceStore.applyOutputDeviceProfile(this.outputDeviceLabel());
  }

  /** The human LABEL of the currently selected input / output device — the <option> text the
   *  recognition patterns match (NOT the Web Audio deviceId the <select> value carries). The
   *  small accessor the calibration dialog + card sections resolve the current card by. */
  inputDeviceLabel() { return $('#inSel option:selected').text(); }

  outputDeviceLabel() { return $('#outSel option:selected').text(); }

  /** Re-renders the per-channel ADC/DAC full-scale readouts from the live prefs (after a card
   *  edit / calibrate, and on dialog open). '—' for a non-positive value. */
  refreshFsReadouts() {
    const fmt = (v) => ((v > 0 && Number.isFinite(v)) ? v.toFixed(6) : '—');
    $('#adcFsVrms').text(fmt(this.prefs.getAdcFsVoltageRms('L')));
    $('#adcFsVrmsRight').text(fmt(this.prefs.getAdcFsVoltageRms('R')));
    $('#dacFsAmpl').text(fmt(this.prefs.getDacFsVoltageAmpl('L')));
    $('#dacFsAmplRight').text(fmt(this.prefs.getDacFsVoltageAmpl('R')));
  }

  /** Builds the ten Osc/FFT/FreqResp NumericStepFields over their `.numfield` chrome and the
   *  twelve colour buttons' live repaint, once (the DOM is static). Mirrors PreferencesDialog's
   *  per-field NumericStepField ctors + applyButtonColor. Values are staged/committed by
   *  seedPrefsTabs()/applyPrefsTabs(); this only owns the widget construction + chrome. */
  buildPrefFields() {
    this.prefFields = {};
    for (const [id, spec] of Object.entries(PREF_FIELD_SPECS)) {
      const input = document.getElementById(id);
      if (!input) continue;
      const onChange = spec.pct ? () => this.updateFrMaxNyqHz() : null;
      this.prefFields[id] = new NumericStepField(input, new NumericStepModel(spec.model),
        { onChange, tooltipBase: input.title || '' });
    }
    // Colour buttons: repaint background + hex text live as the native picker changes, and open
    // the picker on a click anywhere on the button (the label already forwards to its input, but
    // the hidden 1px input isn't the click target, so trigger it explicitly).
    $('.pref-color').each((_, el) => {
      const inp = el.querySelector('input[type=color]');
      $(inp).on('input change', () => this.paintColorButton(inp));
      // The <label> natively forwards a click to its inner input; cancel that default and open
      // the picker once ourselves so the OS colour dialog can't double-open (open→close→reopen).
      $(el).on('click', (ev) => { if (ev.target === inp) return; ev.preventDefault(); inp.click(); });
    });
  }

  /** Paints one colour button's swatch: background = the picked colour, centred "#RRGGBB" text
   *  (PreferencesDialog.applyButtonColor — foreground left black, no contrast rule). */
  paintColorButton(inp) {
    const hex = String(inp.value || '#000000').toLowerCase();
    const btn = inp.closest('.pref-color');
    if (!btn) return;
    btn.style.background = hex;
    const span = btn.querySelector('.pref-color-hex');
    if (span) span.textContent = hex.toUpperCase();
  }

  /** Wires #menuPrefs/#prefsOk + the staged audio device/rate handlers + the tab strip. */
  bind() {
    const engine = this.engine, prefs = this.prefs;

    this.buildPrefFields();
    if (this.inputCard) this.inputCard.bind();
    if (this.outputCard) this.outputCard.bind();

    $('#menuPrefs').on('click', () => this.modal.show());

    // #inSel change → derive #inRate from the new device's native rate (applyInputDeviceRate).
    // Staging only: the device/rate selections apply to the live engine solely via applyAudioPrefs()
    // on OK (there is no live reopen/restart on selection). Pairs with the Scan button.
    $('#inSel').on('change', () => this.applyInputDeviceRate());
    // A device change re-resolves the direction's card (visibly switching / clearing to "New
    // card…", offering to create one for an unrecognised device) — Java CardSection.onDeviceChanged.
    $('#inSel').on('change', () => { if (this.inputCard) this.inputCard.onDeviceChanged(); });
    $('#outSel').on('change', () => { if (this.outputCard) this.outputCard.onDeviceChanged(); });
    $('#scan').on('click', () => this.scan());

    $('#prefsTabs').on('click', '.nav-link', (ev) => this.prefsTab(ev.currentTarget.dataset.prefsPanel));
    $('#prefFrMaxNyq').on('input', () => this.updateFrMaxNyqHz());
    $('#prefOscPersistence').on('change', () => this.gateOscPersistManual());

    $('#prefsModal').on('show.bs.modal', () => {
      this._staging = true;
      this._okClicked = false;
      this.snapshotAudioPrefs();
      this.seedPrefsTabs();
      // Re-derive the per-card combo + range table for the current devices, and the FS readouts.
      if (this.inputCard) this.inputCard.refresh();
      if (this.outputCard) this.outputCard.refresh();
      this.refreshFsReadouts();
    });
    $('#prefsOk').on('click', () => { this._okClicked = true; });
    $('#prefsModal').on('hidden.bs.modal', async () => {
      this._staging = false;
      if (this._okClicked) {
        this._okClicked = false;
        await this.applyAudioPrefs();
        this.applyPrefsTabs();
      } else {
        this.restoreAudioPrefs();
      }
    });

    return this;
  }
}
