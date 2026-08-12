/*
 * Phonalyser web - the FFT settings strip (window / overlap / averages / threads /
 * warm-up / coherent / snap-to-bin · THD band + harmonics · presets · save / load ·
 * calibration · screenshot + ADC-calibrate controls).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of gui/fft/FftTabControl. Owns the FFT toolbar tabs and their bound
 * controls; reaches the FFT PANE (spectrum canvas / freq+mag scrollbars / Record LED /
 * readout+THD+IMD table / FFT render-loop branch, which stay in app.js) only through
 * the narrow injected `host` object - mirroring Java FftTabControl->FftPane.Host
 * (getResult / setResult / applyPrefsToUi). The FFT NumericStepFields (manual-fundamental)
 * are built in app.js's initStepFields and reached here via the injected getField; the
 * io / fftViewCorrection / store / restartFft / tileChips collaborators + the shared
 * confirm dialog are injected too.
 */
import { t } from '../i18n/i18n.js';
import { FftAnalyzer } from './fft-analyzer.js';
import { FftResult } from './fft-result.js';
import { analyzeImd } from './imd-analyzer.js';
import { parabolicBinInterp } from '../dsp/mathutil.js';
import { MessageBus } from '../bus/message-bus.js';
import { Events } from '../bus/events.js';
import * as fileStore from '../io/file-store.js';
import { putCal, getCal } from '../io/cal-store.js';
import { CalibrationEntry } from '../store/preferences.js';
import { registerShotCanvasRenderer, ensureShotRenderCanvas } from '../shell/screenshot.js';
import { TileTabs } from '../widgets/tile-tabs.js';
import { PresetBar } from '../widgets/preset-bar.js';
import { OFF_LABEL } from '../widgets/numeric-step-field.js';

// Off is a distinct sentinel BELOW 1 so the averages dial reads Off ↔ 1 ↔ 2 ↔ 4 ... 128 ↔ ∞ -
// with Off == 1 the wheel jumped 2 -> Off and 1 was unreachable. Engine behaviour is unchanged:
// the analyser accumulates only from 2 up (fft-controller ringN = max(1, averages)), so 0 and 1
// are both "a single spectrum". Java FftTabControl.AVERAGES_OFF / AVERAGES_SERIES.
export const FFT_AVERAGES_OFF = 0;
export const FFT_AVERAGES_SERIES = [FFT_AVERAGES_OFF, 1, 2, 4, 8, 16, 32, 64, 128, Infinity];

// FftOverlap enum token -> display %, for the FFT-settings sub-label.
const OVERLAP_PCT = { PCT_0: '0', PCT_50: '50', PCT_75: '75', PCT_87_5: '87.5', PCT_93_75: '93.75' };

export class FftTabControl {
  /**
   * @param engine the AudioEngine (FFT structural changes restart the FFT consumer via host).
   * @param prefs  Preferences.
   * @param deps   {host, fftView, fftViewCorrection, store, getField, io, restartFft,
   *                showConfirm, setStatus, tileChips}
   *   - host: the narrow FFT-PANE seam (Java FftTabControl.Host) -
   *       getResult() (=> latestResult, the live analyzed spectrum: Save / ADC-calibrate read it),
   *       setResult(r) (a loaded .fft spectrum -> latestResult + resultDirty so the render loop paints it),
   *       applyPrefsToUi() (a preset recall re-seeds the main FFT controls: app.js applyPrefsToUi).
   *   - fftView: the FFT view (applyPrefs() re-reads colour/line/axis prefs on a preset recall).
   *   - fftViewCorrection: the render-time FFT spectral corrections (reads the shared store).
   *   - store: the shared FFT CorrectionStore (Java FftController's) - rebuildCalEntries mutates it;
   *       both the render-time de-embed (fftViewCorrection) and the predistortion calResponseAt read it.
   *   - getField: (id) => the FFT NumericStepField (built in app.js initStepFields).
   *   - io: {saveFile, openFile, bytesToText, loadFrc, saveSpectrum, loadSpectrum, FFT_TYPE, FRC_TYPE} -
   *       the file save / load collaborators.
   *   - restartFft: () => Promise - re-read config + re-acquire the FFT consumer (app.js lifecycle).
   *   - showConfirm: (title, message) => Promise<boolean> - the shared Bootstrap confirm modal.
   *   - setStatus: (msg) => set the status line.
   *   - tileChips: (...vals) => the `.tile` chip-span renderer (shared with the scope tiles, app.js).
   */
  constructor(engine, prefs, { host, fftView, fftViewCorrection, store, getField, io,
    restartFft, showConfirm, setStatus, tileChips, calibrationDialog }) {
    this.engine = engine;
    this.prefs = prefs;
    this.host = host;
    this.fftView = fftView;
    this.fftViewCorrection = fftViewCorrection;
    this.store = store;
    this._getField = getField;
    this.io = io;
    this._restartFft = restartFft;
    this._showConfirm = showConfirm;
    this._setStatus = setStatus;
    this._tileChips = tileChips;
    // The unified ADC/DAC calibration dialog (Java CalibrationDialog), late-bound
    // (built after this control) - () => the shared dialog instance.
    this._calibrationDialog = calibrationDialog;
    this.fftAnalyzer = new FftAnalyzer();
  }

  // Effective FFT averages: the averages NumericStepField's canonical value (Java averagesField -
  // AVERAGES_SERIES {Off, 1, 2, 4, 8, 16, 32, 64, 128, ∞}; both Off and ∞ are series entries, not
  // separate toggles). The stop-after gate + the worker key off ∞ (Infinity).
  fftAveragesValue() {
    const f = this._getField('averages');
    return f ? f.getValue() : (parseInt($('#averages').val(), 10) || 4);
  }
  // Tile text (Java FftTabControl.formatAverages): ∞ glyph, the Off label at/below the Off
  // sentinel (0), else the plain count - so the tile and the field always agree.
  formatAverages(v) {
    if (v === Infinity) return '∞';
    if (v <= FFT_AVERAGES_OFF) return OFF_LABEL;
    return String(v);
  }
  shortHz(hz) {
    if (hz >= 1000) { const k = hz / 1000; return (Number.isInteger(k) ? k : k.toFixed(1)) + 'k'; }
    return String(Math.round(hz));
  }

  // Render `.tile` chips carrying a per-chip state-aware tooltip (Java fftTabTiles attaches a
  // tooltip per Tile). The shared tileChips renderer makes plain (untitled) spans; here each chip
  // needs its own title, so emit the titled spans directly with the same `.tile` class.
  titledChips(...pairs) {
    return pairs.filter(p => p && p[0] != null && p[0] !== '')
      .map(([text, title]) => `<span class="tile" title="${title}">${text}</span>`).join('');
  }
  // Live tab-tile chips (Java fftTabTiles + refreshTab) - one set per tab, repainted on change.
  updateTabSub() {
    const sz = $('#fftSize option:selected').text();
    const win = $('#window').val();   // short enum token (BH4 / BH7 / HFT248D ...), not the full name
    const winName = $('#window option:selected').text();   // full window name for the tooltip
    const ov = OVERLAP_PCT[$('#overlap').val()] || '0';
    const avg = this.formatAverages(this.fftAveragesValue());
    const coh = $('#coherent').is(':checked');
    $('#fftTabSub').html(this.titledChips(
      [sz, t('fft.tile.length', $('#fftSize').val())],
      [win, t('fft.tile.window', winName)],
      [ov + '%', t('fft.tile.overlap', ov + '%')],
      [avg + '×', t('fft.tile.averages', avg)],
      [coh ? 'coh' : 'inc', t(coh ? 'fft.tile.coh' : 'fft.tile.inc')]));
  }
  // Canonical value of a THD NumericStepField (the input DISPLAYS an auto-ranged
  // unit, e.g. "22.05" + kHz, so the raw input text is NOT the Hz value).
  thdFieldValue(id, dflt) {
    const f = this._getField(id);
    return f ? f.getValue() : dflt;
  }
  updateThdTabSub() {
    const hpOn = $('#thdDistMinEn').is(':checked'), lpOn = $('#thdDistMaxEn').is(':checked');
    const range = (!hpOn && !lpOn) ? 'all'
      : (hpOn ? this.shortHz(this.thdFieldValue('thdDistMin', 0)) : '0') + '-'
        + (lpOn ? this.shortHz(this.thdFieldValue('thdDistMax', 0)) : '∞');
    const maxH = Math.round(this.thdFieldValue('thdMaxH', 9));
    const chips = [
      [range, t('fft.tile.distRange', range)],
      ['H' + maxH, t('fft.tile.thdMaxHarmonic', maxH)],
    ];
    if ($('#fftManualFundEn').is(':checked')) chips.push(['manF', t('fft.tile.manualFund')]);
    $('#thdTabSub').html(this.titledChips(...chips));
  }
  updateCalTabSub() {
    const n = this.store.getEntries().length;
    $('#calTabSub').html(n <= 0 ? '' : this._tileChips(n === 1 ? t('calibration.tile.loaded') : t('calibration.tile.loadedN', n)));
  }
  updatePresetTabSub() {
    const n = this.prefs.fftPresets.size;
    $('#presetTabSub').html(n <= 0 ? '' : this._tileChips(n + ' saved'));
  }
  refreshFftTiles() { this.updateTabSub(); this.updateThdTabSub(); this.updateCalTabSub(); this.updatePresetTabSub(); }
  // THD-tab field gating (Java): a distortion-band field is enabled only when its enable check is
  // on; the manual-fundamental amplitude field only when Manual fundamental is on. The fields are
  // NumericStepFields - setDisabled greys the whole .numfield (input + ▲▼ + unit).
  syncThdEnable() {
    const gate = (id, on) => { const f = this._getField(id); if (f) f.setDisabled(!on); };
    gate('thdDistMin', $('#thdDistMinEn').is(':checked'));
    gate('thdDistMax', $('#thdDistMaxEn').is(':checked'));
    gate('fftManualFund', $('#fftManualFundEn').is(':checked'));
  }

  // Enable-gating for the stop-after-N controls (Java FftTabControl.refreshStopAfterEnable):
  // the Stop-after-N checkbox is available ONLY when averages is ∞ (forever) - a finite N is
  // already a moving average window; the count field follows (forever AND checkbox checked).
  refreshStopAfterEnable() {
    const forever = !Number.isFinite(this.fftAveragesValue());
    $('#fftStopAfterNEn').prop('disabled', !forever);
    const f = this._getField('fftStopAfterN');
    if (f) f.setDisabled(!(forever && $('#fftStopAfterNEn').is(':checked')));
  }

  syncAlign() {
    // Java updateAlignGenEnabled: align only when the generator snaps to bin AND the
    // fundamental is taken from the generator.
    $('#align').prop('disabled', !($('#snap').is(':checked') && $('#fftFundFromGen').is(':checked')));
  }

  /** A THD setting (HP/LP band, manual fundamental, max harmonics) changed - Java
   *  FftView.onThdSettingChanged (FftView.java:907-913): "For a displayed STATIC spectrum
   *  (not a live capture) re-apply the settings and recompute its THD / harmonics, then
   *  repaint. During live capture the worker re-reads these every tick." Never a capture
   *  restart, never a statistics/accumulator reset. readConfig pushes the new
   *  params into the live dispatch; the retained result is recomputed + repainted so the
   *  change shows immediately in the STOPPED state too (the pane's rAF branch only paints
   *  while recording, hence the direct view render - Java redraw()). Java gates the static
   *  recompute on !controller.isRecording(); the web tab control has no recording-state
   *  seam, so it recomputes unconditionally - idempotent with the next live frame, which
   *  re-derives the same stats from the same config. */
  onThdSettingChanged() {
    this.host.readConfig();
    const r0 = this.host.getResult && this.host.getResult();
    if (!r0 || !r0.amplitudeDbFs) return;
    const r = FftResult.adopt(r0);
    this.recomputeStaticResult(r);
    this.host.setResult(r);
    this.fftView.render(r);
  }

  /** Faithful port of Java FftController.recomputeStaticResult (FftController.java:486-506):
   *  re-applies the current THD settings to an already-built spectrum and recomputes its
   *  harmonics / THD / SNR - harmonic positions re-derived for the (possibly changed)
   *  max-harmonics count (the spectrum itself is preserved; only the harmonic arrays resize),
   *  the manual-fundamental Vrms->dBFS anchor, the HP/LP distortion band, then
   *  FftAnalyzer.recomputeStats. Settings come from engine.config (the readConfig snapshot -
   *  the same source the live dispatch reads). */
  recomputeStaticResult(r) {
    const c = this.engine.config;
    const halfSize = r.amplitudeDbFs.length - 1;
    // Web harmonic-count convention: the COUNT of H2..HN = max(9, calcMax) − 1, matching the
    // live analyzer (Java FftAnalyzerWorker:1661 calcMaxH = max(9, N) − 1). Java's
    // recomputeStaticResult:490 predates that −1 - we keep static and live consistent.
    const harmCount = c.harmonicCount;
    r.ensureArrays(halfSize + 1, harmCount);   // keeps the spectrum; resizes harmonic arrays only if changed
    r.harmonicCount = harmCount;
    // Java :493-498 derives the harmonic grid off fundamentalHzRefined; fall back to the
    // located fundamentalHz for a loaded spectrum that carries no refined value.
    const fundHz = (r.fundamentalHzRefined > 0) ? r.fundamentalHzRefined : r.fundamentalHz;
    for (let h = 0; h < harmCount; h++) {
      const hz = (h + 2) * fundHz;
      const hb = Math.round(hz / r.freqResolution);
      r.harmonicHz[h] = hz;
      r.harmonicBins[h] = (hb >= 1 && hb <= halfSize) ? hb : -1;
    }
    // Manual-fundamental amplitude anchor, Vrms -> dBFS (Java :499-502).
    r.fundamentalTrueDbFs = (c.manualFundEnabled && c.manualFundVrms > 0)
      ? 20.0 * Math.log10(c.manualFundVrms) - (c.dbvOffsetDb || 0)
      : NaN;
    // High-pass / low-pass distortion band (Java :503-504).
    r.snrFreqMin = c.snrFreqMin || 0;
    r.snrFreqMax = c.snrFreqMax || 0;
    this.fftAnalyzer.recomputeStats(r);
  }

  /** Seed the FFT settings / THD / mains / averages controls from the live prefs (init).
   *  Mirrors the FFT lines of app.js applyPrefsToUi + the fftSeed init step. */
  seedFftControls() {
    const prefs = this.prefs;
    $('#fftSize').val(String(prefs.fftLength.get()));
    $('#window').val(prefs.fftWindow.get());
    $('#overlap').val(prefs.fftOverlap.get());
    // Threads (web-only pref): the select only offers 1..min(hardwareConcurrency,16),
    // so a pref beyond the option list falls back to 1 (jQuery leaves val() null).
    const $thr = $('#threads');
    $thr.val(String(prefs.fftThreads.get()));
    if ($thr.val() == null) $thr.val('1');
    // Averages NumericStepField (Java averagesField) - push the pref (a finite count or ∞) into
    // the field; setValue is silent so it won't re-fire restartFft on a preset recall.
    const fAvg = this._getField('averages');
    if (fAvg) fAvg.setValue(prefs.fftAverages.get());
    $('#coherent').prop('checked', prefs.fftCoherentAveraging.get());
    $('#align').val(prefs.fftAlignGenerator.get() === 'FLL' ? 'fll' : 'off');
    // Stop-after-N (enable + count) and mains-suppression combo (FftTabControl).
    $('#fftStopAfterNEn').prop('checked', prefs.fftStopAfterNEnabled.get());
    // Stop-after-N NumericStepField (Java stopAfterNField) - push the pref count into the field
    // (setValue is silent), then apply the full forever-AND-checked gate: seeded state must be
    // gated exactly like a change (finite averages + a checked box saved from an earlier
    // forever session otherwise renders the checkbox live where it must be greyed).
    const fStopN = this._getField('fftStopAfterN');
    if (fStopN) fStopN.setValue(prefs.fftStopAfterN.get());
    this.refreshStopAfterEnable();
    $('#fftMains').val(prefs.fftMainsSuppression.get());

    $('.fft-pane .lr.l, .fft-pane .lr.r').removeClass('on');
    $('.fft-pane .lr.' + (prefs.fftChannel.get() === 'R' ? 'r' : 'l')).addClass('on');
    $('#logAxis').prop('checked', prefs.fftLogFreqAxis.get());
    $('#fftDetectTimeDisc').prop('checked', prefs.fftDetectTimeDiscontinuity.get());
    $('#fftMagUnit').val(prefs.fftMagUnit.get());
    $('#fftFundFromGen').prop('checked', prefs.fftFundFromGenerator.get());
    // THD NumericStepFields - push the prefs into the widgets (setValue is silent).
    const seedF = (id, v) => { const f = this._getField(id); if (f) f.setValue(v); };
    seedF('thdDistMin', prefs.fftDistMinHz.get()); seedF('thdDistMax', prefs.fftDistMaxHz.get());
    $('#thdDistMinEn').prop('checked', prefs.fftDistMinEnabled.get()); $('#thdDistMaxEn').prop('checked', prefs.fftDistMaxEnabled.get());
    seedF('thdCalcMaxH', prefs.fftCalcMaxHarmonic.get());
    seedF('thdMaxH', prefs.fftThdMaxHarmonic.get());
    $('#fftManualFundEn').prop('checked', prefs.fftManualFundEnabled.get());
    // Save / Load read-only path fields (Java buildSaveToTab / buildLoadFromTab seed from
    // prefs.fftSavePath / fftLoadPath; empty -> the localized tooltip hint).
    const savePath = prefs.fftSavePath.get(), loadPath = prefs.fftLoadPath.get();
    $('#fftSavePath').val(savePath || '').attr('title', savePath || t('fft.save.path.tooltip'));
    $('#fftLoadPath').val(loadPath || '').attr('title', loadPath || t('fft.load.path.tooltip'));
    this.refreshFftTiles(); this.syncThdEnable(); this.syncAlign();
  }

  // ----- FFT Presets tab (Java FftTabControl.buildPresetsTab -> PresetBar): named save/load/delete -----
  captureFftPreset() {
    const prefs = this.prefs;
    return {
      channel: prefs.fftChannel.get(), magUnit: prefs.fftMagUnit.get(), logFreqAxis: prefs.fftLogFreqAxis.get(),
      freqMinHz: prefs.fftFreqMinHz.get(), freqMaxHz: prefs.fftFreqMaxHz.get(),
      magTop: prefs.fftMagTop.get(), magBottom: prefs.fftMagBottom.get(),
      fftLength: prefs.fftLength.get(), averages: prefs.fftAverages.get(),
      stopAfterNEnabled: prefs.fftStopAfterNEnabled.get(), stopAfterN: prefs.fftStopAfterN.get(),
      fundFromGenerator: prefs.fftFundFromGenerator.get(), window: prefs.fftWindow.get(),
      overlap: prefs.fftOverlap.get(), coherentAveraging: prefs.fftCoherentAveraging.get(),
      distMinHz: prefs.fftDistMinHz.get(), distMaxHz: prefs.fftDistMaxHz.get(),
      distMinEnabled: prefs.fftDistMinEnabled.get(), distMaxEnabled: prefs.fftDistMaxEnabled.get(),
      thdMaxHarmonic: prefs.fftThdMaxHarmonic.get(), calcMaxHarmonic: Math.max(9, prefs.fftCalcMaxHarmonic.get()),
      manualFundVrms: prefs.fftManualFundVrms.get(), manualFundDbvDisplay: prefs.fftManualFundDbvDisplay.get(),
      manualFundEnabled: prefs.fftManualFundEnabled.get(),
    };
  }
  applyFftPreset(p) {
    const prefs = this.prefs;
    prefs.fftChannel.set(p.channel); prefs.fftMagUnit.set(p.magUnit); prefs.fftLogFreqAxis.set(p.logFreqAxis);
    prefs.fftFreqMinHz.set(p.freqMinHz); prefs.fftFreqMaxHz.set(p.freqMaxHz);
    prefs.fftMagTop.set(p.magTop); prefs.fftMagBottom.set(p.magBottom);
    prefs.fftLength.set(p.fftLength); prefs.fftAverages.set(p.averages);
    prefs.fftStopAfterNEnabled.set(p.stopAfterNEnabled); prefs.fftStopAfterN.set(p.stopAfterN);
    prefs.fftFundFromGenerator.set(p.fundFromGenerator); prefs.fftWindow.set(p.window);
    prefs.fftOverlap.set(p.overlap); prefs.fftCoherentAveraging.set(p.coherentAveraging);
    prefs.fftDistMinHz.set(p.distMinHz); prefs.fftDistMaxHz.set(p.distMaxHz);
    prefs.fftDistMinEnabled.set(p.distMinEnabled); prefs.fftDistMaxEnabled.set(p.distMaxEnabled);
    prefs.fftThdMaxHarmonic.set(p.thdMaxHarmonic); prefs.fftCalcMaxHarmonic.set(p.calcMaxHarmonic);
    prefs.fftManualFundVrms.set(p.manualFundVrms); prefs.fftManualFundDbvDisplay.set(p.manualFundDbvDisplay);
    prefs.fftManualFundEnabled.set(p.manualFundEnabled);
    prefs.save();
    // A preset writes the freq / mag window like any other writer of those four prefs, so it
    // announces it like any other writer: the pane's FFT_RANGE_CHANGED subscriber clamps to
    // [binSize, Nyquist] × [floor, ceiling] and re-aligns the scrollbars. Without this publish a
    // preset was the ONE path that reached the axis unclamped - a stored preset with
    // magTop == magBottom (or an inverted or out-of-range pair) went straight to the mapping and
    // blanked the plot (Java FftTabControl.applyFftPreset).
    MessageBus.instance().publish(Events.FFT_RANGE_CHANGED);
    this.host.applyPrefsToUi();                          // reflect the main FFT controls
    $('#fftMagUnit').val(p.magUnit);                     // + the controls applyPrefsToUi doesn't cover
    const setF = (id, v) => { const f = this._getField(id); if (f) f.setValue(v); };   // THD steppers
    setF('thdDistMin', p.distMinHz); setF('thdDistMax', p.distMaxHz); setF('thdCalcMaxH', p.calcMaxHarmonic);
    setF('thdMaxH', p.thdMaxHarmonic);
    $('#logAxis').prop('checked', p.logFreqAxis);
    this.fftView.applyPrefs();
    this._restartFft();
  }
  /** Repopulates the preset dropdown + chip + button enablement - delegates to the shared
   *  PresetBar. Kept as a named method for app.js init + the tiles refresh. */
  refreshFftPresetList() { this._presetBar.refreshList(); }

  /** Screenshot path: re-renders the FFT view - the SAME live FftView instance, so the
   *  SAME paint path (grid + axis ticks, trace, harmonic dots, readout + THD/IMD table)
   *  - into a persistent attached offscreen canvas laid out at cssW×cssH CSS px, and
   *  returns that canvas. The view is retargeted for ONE synchronous render, then
   *  restored (JS is single-threaded, so no live paint can interleave). Java instead
   *  PRINTS a fresh clone - AbstractPane.renderOffscreen (:214-254) laying out
   *  FftPane.createSnapshotClone (:495-500, a fresh FftView + view.copySnapshotFrom) at
   *  the target size; the web reuses the one live view, which already carries that
   *  snapshot (its _last result). The view applies its own devicePixelRatio backing
   *  (fft-view), so the returned bitmap is cssW·dpr × cssH·dpr - exactly the
   *  output-pixel rect the shot compositor blits 1:1, never a scaled live-bitmap copy. */
  renderSpecShotCanvas(cssW, cssH) {
    const view = this.fftView;
    const shotCv = ensureShotRenderCanvas(cssW, cssH);
    const liveCv = view.cv, liveG = view.g;
    view.cv = shotCv; view.g = shotCv.getContext('2d');
    try {
      view.applyPrefs();   // render(last result) sized off the offscreen canvas's box
    } finally {
      view.cv = liveCv; view.g = liveG;
    }
    return shotCv;
  }

  /** The cal-store hashes referenced by the current FFT calibration rows. Called by
   *  app.js after startup restore to build the union for pruneCals. Await {@link _calRestore}
   *  first so the rebuilt rows are in place. */
  getCalHashes() {
    const hashes = new Set();
    $('#fftCalRows .fft-cal-row').each(function () {
      const h = $(this).data('hash');
      if (h) hashes.add(h);
    });
    return hashes;
  }

  bind() {
    const prefs = this.prefs;
    const engine = this.engine;
    const io = this.io;
    const getField = (id) => this._getField(id);
    const restartFft = () => this._restartFft();
    const showConfirm = (title, message) => this._showConfirm(title, message);

    // ----- FFT-settings inputs (Java FftTabControl bindings) -> the fft* prefs -----
    $('#fftSize').on('change', () => prefs.fftLength.set(parseInt($('#fftSize').val(), 10)));
    $('#window').on('change', () => prefs.fftWindow.set($('#window').val()));
    $('#overlap').on('change', () => prefs.fftOverlap.set($('#overlap').val()));
    // #averages is a NumericStepField (Java averagesField): its own onChange (app.js initStepFields)
    // writes prefs.fftAverages, refreshes the stop-after gate + tab tile, and restarts the FFT - so
    // there is no plain jQuery change handler here, and no ∞ checkbox (∞ is the top of the series).
    $('#coherent').on('change', () => prefs.fftCoherentAveraging.set($('#coherent').is(':checked')));
    // Stop-after-N enable (gates the count field) and mains-suppression combo -
    // persisted to prefs (FftTabControl bindings); no engine restart / accumulator reset
    // (Java FftView never wires these to resetStatistics). BUT the running FftController reads
    // config.stopAfterNEnabled / config.stopAfterN every tick (fft-controller _onWorkerResult),
    // so a live toggle MUST push into engine.config - else enabling stop-after mid-run had no
    // effect until a restart: the ∞-target run kept going past N because config was stale.
    // host.readConfig() refreshes the whole config snapshot from the live UI (incl. these two).
    // The count itself is a NumericStepField (Java stopAfterNField): its model clamps to 2..1e6,
    // the wheel jumps by 100 and arrows by 1 - its onChange is rebound below (like averages).
    $('#fftStopAfterNEn').on('change', () => {
      prefs.fftStopAfterNEnabled.set($('#fftStopAfterNEn').is(':checked'));
      this.refreshStopAfterEnable();
      if (engine.running) this.host.readConfig();
    });
    $('#fftMains').on('change', () => prefs.fftMainsSuppression.set($('#fftMains').val()));

    // ---- RESTART-FROM-0 SEMANTICS: the FFT restarts from 0 ONLY when the FFT length, the
    // window, get-fundamental, align-generator or coherent changes; nothing else may cause a
    // restart, and a restart means resetting the statistics AND the average accumulator.
    // So EXACTLY these five reset the running statistics + the cross-tick
    // accumulator: #fftSize, #window, #fftFundFromGen, #align, #coherent (plus L/R channel +
    // ADC/DAC calibration, wired in app.js - Java FftView.java:439/459-460). Everything else
    // (overlap, averages, stop-after, mains, threads, log axis, mag unit, THD settings,
    // calibration toggles) applies LIVE with the average KEPT: the consumer re-derives its
    // hop/pool/targets per batch (FftController._syncLiveConfig - the web analog of the Java
    // worker re-reading Preferences every tick).
    //
    // FFT LENGTH resets (Java FftView.java:449 fftLengthProperty -> resetStatistics) AND is the
    // only change that needs a fresh CONSUMER: it resizes the analysis window / bin grid, so
    // the worker + buffers must be rebuilt (restartFft resets as a side effect). It also
    // re-snaps the generator bin (Java FFT_LENGTH_CHANGED, FftTabControl.java:360-361).
    $('#fftSize').on('change', async () => {
      this.updateTabSub();
      await restartFft();
      // FFT length changes the bin grid (binW = inRate/fftSize); re-snap the generator so the
      // played tone still lands exactly on a bin (Java FFT_LENGTH_CHANGED consumer).
      if (engine.running && $('#snap').is(':checked')) engine.retuneGenerator();
      // The bracketed snapped-freq label depends on the bin grid too, so refresh it regardless
      // of run/snap state (Java GeneratorPane subscribes FFT_LENGTH_CHANGED and calls
      // updateFreqLabel unconditionally) - else changing the FFT length while stopped leaves a
      // stale "(xxx Hz)".
      this.host.refreshFreqLabel();
    });
    // THREADS (web-only worker-pool size, no Java counterpart): NO reset. The worker reads the
    // pool size per dispatch message, so the running consumer just re-derives poolSize on the
    // next batch (_syncLiveConfig) - the accumulator is untouched. (This used to restartFft,
    // resetting the average - the exact opposite of the restart-from-0 rule.)
    $('#threads').on('change', () => { this.prefs.fftThreads.set(parseInt($('#threads').val(), 10) || 1); this.updateTabSub(); if (engine.running) this.host.readConfig(); });
    // OVERLAP: NO reset - Java FftTabControl.java:377-380: "Overlap only changes the hop, not
    // the spectrum/accumulator - refresh the tab tile but DON'T reset the average; the worker
    // adapts next tick." The running consumer re-derives its hop/bufLen on the next batch
    // (_syncLiveConfig) keeping the accumulator depth (κ/PLL re-anchored via onResync).
    $('#overlap').on('change', () => { this.updateTabSub(); if (engine.running) this.host.readConfig(); });
    // WINDOW + COHERENT are passed per analyze dispatch (they retune LIVE - no consumer rebuild),
    // but BOTH reset the running statistics + accumulator (Java FftView.java:436 window ->
    // resetStatistics; :451 coherent -> resetStatistics): a window/mode change makes the already-
    // accumulated frames incompatible with the new ones. Push the live config + reset (NOT a
    // full consumer re-acquire - that's the lighter Java resetStatistics()).
    $('#window,#coherent').on('change', () => {
      this.updateTabSub();
      if (engine.running) { this.host.readConfig(); engine.resetAnalyses(); }
    });
    // ALIGN generator (None / FLL): restart from 0 (engaging/releasing
    // the FLL changes what tone the accumulated frames were captured under, so the average is
    // stale). Java FftView.java:465-466 additionally resets the LOOP when an active mode is
    // selected ("Selecting an active alignment mode (PID / FLL) resets its loop so each session
    // converges fresh") - the web FftController._syncLiveConfig handles that OFF->ON transition.
    $('#align').on('change', () => {
      engine.config.fllOn = $('#align').val() === 'fll';
      prefs.fftAlignGenerator.set($('#align').val() === 'fll' ? 'FLL' : 'NONE');
      this.syncAlign();
      if (engine.running) engine.resetAnalyses();
    });

    // Fundamental-from-generator: restart from 0 (the toggle switches
    // the analyzer between the generator-frequency hint and auto-detect, i.e. WHICH tone the
    // accumulator de-rotates around: expectedFundHz per dispatch, Java FftAnalyzerWorker:1678).
    // Also gates the align combo (Java FftTabControl.java:507 -> updateAlignGenEnabled).
    $('#fftFundFromGen').on('change', () => {
      prefs.fftFundFromGenerator.set($('#fftFundFromGen').is(':checked'));
      this.syncAlign();
      if (engine.running) { this.host.readConfig(); engine.resetAnalyses(); }
    });
    // THD settings (Java FftView.java:473-479/486: distMin/distMax band, manual fundamental,
    // max harmonics -> onThdSettingChanged): POST-FFT - never a capture restart, never a
    // statistics/accumulator reset. Applied live via readConfig; with the FFT stopped the
    // displayed static result is recomputed NOW - see onThdSettingChanged below.
    // ONE commit path shared by the three enable checkboxes (jQuery change) and the four
    // NumericStepFields (their onChange is rebound below, like averages/manualFund).
    // The field models already clamp to the Java ranges (0..inRate/2, 2..9, 9..50 -
    // FftTabControl.java:570/594/641/655), so no re-clamp here.
    const commitThdSettings = () => {
      this.updateThdTabSub();   // repaint the THD tile chips (dist range / H / manF)
      this.syncThdEnable();
      prefs.fftDistMinHz.set(this.thdFieldValue('thdDistMin', 20));
      prefs.fftDistMaxHz.set(this.thdFieldValue('thdDistMax', 20000));
      prefs.fftDistMinEnabled.set($('#thdDistMinEn').is(':checked'));
      prefs.fftDistMaxEnabled.set($('#thdDistMaxEn').is(':checked'));
      prefs.fftCalcMaxHarmonic.set(Math.round(this.thdFieldValue('thdCalcMaxH', 9)));
      prefs.fftThdMaxHarmonic.set(Math.round(this.thdFieldValue('thdMaxH', 9)));     // Max harmonic for THD
      prefs.fftManualFundEnabled.set($('#fftManualFundEn').is(':checked'));          // Manual fundamental
      this.onThdSettingChanged();
    };
    $('#thdDistMinEn, #thdDistMaxEn, #fftManualFundEn').on('change', commitThdSettings);
    for (const id of ['thdDistMin', 'thdDistMax', 'thdMaxH', 'thdCalcMaxH']) {
      const f = this._getField(id);
      if (f) f.onChange = commitThdSettings;
    }

    // FFT "Averages": NO reset - Java FftTabControl.java:391-397: "No reset here: the worker
    // resets the average only on a ring↔∞ switch or a smaller ring (a larger ring keeps the
    // depth)." The NumericStepField is built in app.js (initStepFields) with an onChange that
    // restartFft()'d - a full consumer teardown that reset the accumulator on EVERY averages
    // change. Rebind its onChange here (bind() runs after initStepFields):
    // write the pref, refresh the stop-after gate + tab tile, push the live config - the
    // running consumer re-derives its targets next batch (_syncLiveConfig) and _onWorkerResult
    // applies the exact Java discard rules.
    const fAvg = this._getField('averages');
    if (fAvg) {
      fAvg.onChange = (v) => {
        prefs.fftAverages.set(v);
        this.refreshStopAfterEnable();
        this.updateTabSub();
        if (engine.running) this.host.readConfig();
      };
    }
    // Stop-after-N COUNT (Java stopAfterNField): NO reset - write the pref and push the live
    // config so the running consumer sees the new N next tick (like the enable toggle above).
    // The field model clamps to 2..1e6 (Java STOP_AFTER_MIN/MAX), so no re-clamp here. Rebind
    // its onChange here (bind() runs after initStepFields, where it was a placeholder).
    const fStopN = this._getField('fftStopAfterN');
    if (fStopN) {
      fStopN.onChange = (v) => {
        prefs.fftStopAfterN.set(v);
        if (engine.running) this.host.readConfig();
      };
    }
    // THD "Manual fundamental" VALUE (Java fftManualFundVrmsProperty -> onThdSettingChanged,
    // FftView.java:478): a THD setting - never a restart/reset. The app.js-built field's
    // onChange restartFft()'d when enabled (an over-reset mid-run, and a no-op while stopped
    // so the change never applied). Rebind to the THD-settings path: write the prefs,
    // then onThdSettingChanged (live readConfig / stopped static recompute + repaint).
    const fManFund = this._getField('fftManualFund');
    if (fManFund) {
      fManFund.onChange = (v) => {
        prefs.fftManualFundVrms.set(v);
        prefs.fftManualFundDbvDisplay.set(fManFund.model.isLogDisplay());
        this.updateThdTabSub();
        this.onThdSettingChanged();
      };
    }

    // ----- tile tabs: toggle the drop-down panel a tile owns (Java TileTabFolder) -----
    new TileTabs('#fftTabs').bind();

    // ----- FFT Presets tab (Java FftTabControl.buildPresetsTab -> PresetBar<FftPreset>) -----
    this._presetBar = new PresetBar({
      ids: { name: '#fftPresetName', save: '#fftPresetSave', load: '#fftPresetLoad',
        delete: '#fftPresetDelete', menu: '#fftPresetMenu', menuBtn: '#fftPresetMenuBtn' },
      store: {
        presets: () => prefs.fftPresets,
        put: (n, p) => prefs.putFftPreset(n, p),
        remove: (n) => prefs.removeFftPreset(n),
        captureCurrent: () => this.captureFftPreset(),
        apply: (p) => this.applyFftPreset(p),
      },
      confirm: (title, msg) => showConfirm(title, msg),
      i18nPrefix: 'fft.presets',
      onChanged: () => this.updatePresetTabSub(),   // repaint the "N saved" tile chip
    });
    this._presetBar.bind();

    // ----- FFT Utility tab (Java FftTabControl.buildUtilityTab): screenshot + ADC calibrate -----
    // Screenshot: the FFT Utility camera (#fftShot) opens the SHARED composited ScreenshotDialog
    // (offscreen pane clone + caption + size) - Java FFT_SCREENSHOT_REQUESTED -> the pane's offscreen-
    // clone dialog. The dialog + its FFT renderShot (renderFftShot) are wired in app.js via
    // shotDialog.addPane({openBtn:'#fftShot', ...}); there is NO direct export here any more (the old
    // raw #spec PNG download was the wrong shortcut).
    // Compressed/distorted-shot fix: the #spec bitmap bakes ALL its text in
    // (axis ticks, harmonic labels, readout/THD table), so the compositor's scaled blit of
    // the LIVE bitmap distorted it. Register the offscreen re-renderer instead: the
    // compositor calls it with the reflowed rect's CSS size and the live FftView re-renders
    // itself there from scratch (renderSpecShotCanvas) - the web mirror of Java
    // AbstractPane.renderOffscreen printing a fresh FftPane.createSnapshotClone at the
    // target size (no bitmap scaling).
    registerShotCanvasRenderer('spec', (cssW, cssH) => this.renderSpecShotCanvas(cssW, cssH));
    // ADC calibration (Java FftTabControl.openCalibrationDialog): opens the unified two-row
    // CalibrationDialog seeded analyzed-channel-only with the OPEN-time fundamental Vrms of the
    // FFT's analyzed channel (FftView.getLastVrms); the dialog owns the OK write (per-channel on a
    // bound stereo card, shared otherwise). No live Vrms -> the localized info dialog (Java
    // Dialogs.info with calibrate.title / calibrate.error.noVrms), never a raw prompt.
    $('#fftAdcCalibrate').on('click', () => {
      const measured = this.fftView.getLastVrms();
      if (!(measured > 0) || !Number.isFinite(measured)) {
        this._showConfirm(t('calibrate.title'), t('calibrate.error.noVrms'));
        return;
      }
      const dlg = this._calibrationDialog && this._calibrationDialog();
      if (dlg) dlg.openAdc(measured, prefs.fftChannel.get());
    });

    // ----- FFT "Save to..." / "Load from..." -----
    $('#fftSaveBtn').on('click', async () => {
      if (!this.host.getResult()) { $('#status').text('FFT: nothing analyzed yet - press ▶ first.'); return; }
      const r = this.host.getResult(), c = engine.config, imd = !!r.imd;
      try {
        const text = io.saveSpectrum(r, {
          imd, tone1Hz: c.toneHz, tone2Hz: c.tone2Hz, dbvOffsetDb: prefs.dbvOffsetDb,
        });
        const res = await io.saveFile(text, imd ? 'spectrum-imd.fft' : 'spectrum.fft', io.FFT_TYPE);
        if (res.saved) {
          // Reflect the chosen file in the read-only path field + persist it (Java
          // buildSaveToTab: pathField.setText(chosen) + prefs.setFftSavePath). The browser
          // sandbox only exposes the file name, not a full disk path.
          prefs.fftSavePath.set(res.name); prefs.save();
          $('#fftSavePath').val(res.name).attr('title', res.name);
          // Only the dialog path can claim the file is written; the download path was
          // merely handed to the browser, which reports nothing back (io/file-picker.js).
          $('#status').text(res.viaDownload
            ? t('web.save.handedToDownload', res.name) : 'saved ' + res.name);
        }
      } catch (e) { $('#status').text('FFT save failed: ' + e.message); }
    });
    $('#fftLoadBtn').on('click', async () => {
      const f = await io.openFile(io.FFT_TYPE);
      if (!f) return;
      try {
        const harm = Math.max(9, prefs.fftCalcMaxHarmonic.get()) - 1;   // H2..HN count (Java calcMaxH = max(9,N)-1), matching the live analyzer
        const loaded = io.loadSpectrum(io.bytesToText(f.bytes), prefs.dbvOffsetDb, harm, parabolicBinInterp);
        const r = loaded.result;
        // Seed harmonic bins (n×fundamental) so recomputeStats can rebuild the THD
        // table - loadSpectrum only locates the fundamental.
        const halfSize = r.fftSize / 2;
        for (let h = 0; h < r.harmonicCount; h++) {
          const hz = (h + 2) * r.fundamentalHz;
          const bin = Math.round(hz / r.freqResolution);
          r.harmonicBins[h] = (bin >= 1 && bin <= halfSize) ? bin : -1;
          r.harmonicHz[h] = hz;
        }
        this.fftAnalyzer.recomputeStats(r);
        r.binW = r.freqResolution;
        r.framesDone = r.frameCount || 0; r.avgTarget = r.frameCount || 0; r.coherent = r.coherentAveraging;
        r.fll = { on: false, locked: false, ppm: 0, genFreq: 0 };
        r.imd = loaded.modeImd ? analyzeImd(r, loaded.tone1Hz, loaded.tone2Hz, prefs.dbvOffsetDb) : null;
        // Stop live FFT recording first (Java publishes FFT_RECORDING_STOP_REQUESTED before
        // displayLoadedResult) so the next live frame can't clobber the loaded static spectrum.
        await this.host.stopFftRecording();
        this.host.setResult(r);
        // Reflect the chosen file in the read-only path field + persist it (Java
        // buildLoadFromTab: pathField.setText(chosen) + prefs.setFftLoadPath).
        prefs.fftLoadPath.set(f.name); prefs.save();
        $('#fftLoadPath').val(f.name).attr('title', f.name);
        this.host.showLoadedBanner(f.name);   // "Loaded: file" blink (Java FftView.setSourceFilePath)
        $('#status').text(`loaded ${f.name}`);
      } catch (e) { $('#status').text('FFT load failed: ' + e.message); }
    });

    // ----- FFT Calibration panel (Java FftTabControl.buildCalibrationTab): a multi-row .frc cascade.
    // Each row is one loaded file with Active + With-noise; all Active rows are de-embedded in sequence
    // (rebuilt into the shared store). "With noise" -> that row's correctAllBins: every FFT bin (noise
    // floor incl.) corrected when on, harmonic/dot bins only when off. Row 0 is always present and hides
    // its Remove.
    //
    // PERSISTENCE: the Java desktop persists each row's .frc PATH and re-reads the file at
    // startup; the browser sandbox exposes no re-readable path, so instead we keep the .frc TEXT
    // itself (plus filename + Active + With-noise) in the file-store. On startup restoreCalRows()
    // re-creates the rows, re-parses the stored text and re-applies the cascade - the row layout,
    // loaded files, and Active/With-noise state all survive a reload. Clearing / removing a row
    // also drops its stored entry.
    //
    // PERSISTENCE, continued: the .frc BYTES live in the shared content-addressed cal-store
    // (cal.<sha256>, gzip-compressed, deduplicated) - the SAME record the FreqResp pane uses
    // when it loads the same file. Each row's metadata (filename + hash + Active + With-noise)
    // is kept in prefs.fftCalibrations (a CalibrationEntry list, serialized with the hash), so a
    // reload rebuilds the rows and re-reads the bytes by hash without a re-pick. Clearing /
    // removing a row drops its entry from prefs; unreferenced cal-store records are swept later
    // by pruneCals over the union of BOTH panes' hashes (app.js startup).
    //
    // The startup migration + restore ride ONE promise chain so a user persist can never run
    // before the rows have been rebuilt.
    const LEGACY_CAL_KEY_PREFIX = 'fftcal.';   // pre-2.3 records (raw .frc text) - migrated once
    let calStoreChain = Promise.resolve();
    const chainCalStoreOp = (op) => {
      calStoreChain = calStoreChain.then(op)
        .catch((e) => console.error('FFT cal-row store failed', e));
      return calStoreChain;
    };
    // Rebuild prefs.fftCalibrations from the CURRENT loaded rows (DOM order, loaded rows only) so
    // the row layout + hash + Active/With-noise survive a reload. The bytes are already in the
    // cal-store (stored at load time), so this only rewrites the lightweight entry list.
    const persistCalRows = () => {
      prefs.fftCalibrations.length = 0;
      $('#fftCalRows .fft-cal-row').each(function () {
        const $row = $(this);
        const hash = $row.data('hash');
        if ($row.data('stereo') == null) return;   // only loaded rows persist
        const entry = new CalibrationEntry(
          $row.data('frcName') || null,
          $row.find('.fcal-active').is(':checked'),
          $row.find('.fcal-noise').is(':checked'),
          hash || null);
        prefs.addFftCalibration(entry);
      });
      prefs.save();
    };
    // Row layout: NO inline styles - .fft-cal-row (app.css) owns the flex row; the .fcal-path
    // rule (flex:1, max-width:none) lets the path field fill ALL free width (the old inline
    // max-width:300px override was why the row never stretched). Buttons align in columns
    // across rows because every row has the same fixed-width controls after the flexed path
    // (row 0's Remove is visibility:hidden, keeping its column reserved).
    const calRowHtml = () => `
      <div class="fft-cal-row">
        <input class="fcal-path form-control form-control-sm" type="text" readonly value="${t('freqResp.calibration.path.none')}" title="${t('fft.calibration.path.tooltip')}"/>
        <label class="form-check form-check-sm mb-0" style="white-space:nowrap"><input class="fcal-active form-check-input me-1" type="checkbox" disabled title="${t('fft.calibration.active.tooltip')}"/><span>${t('fft.calibration.active')}</span></label>
        <label class="form-check form-check-sm mb-0" style="white-space:nowrap"><input class="fcal-noise form-check-input me-1" type="checkbox" disabled title="${t('fft.calibration.withNoise.tooltip')}"/><span>${t('fft.calibration.withNoise')}</span></label>
        <button class="fcal-load btn btn-sm btn-outline-secondary" title="${t('freqResp.calibration.load.tooltip')}"><img src="assets/icons/folder-open.svg" class="tool-svg" alt=""></button>
        <button class="fcal-clear btn btn-sm btn-outline-secondary" title="${t('freqResp.calibration.clear.tooltip')}"><img src="assets/icons/rectangle-xmark.svg" class="tool-svg" alt=""></button>
        <button class="fcal-add btn btn-sm btn-outline-secondary" title="${t('freqResp.calibration.add.tooltip')}"><img src="assets/icons/plus.svg" class="tool-svg" alt=""></button>
        <button class="fcal-remove btn btn-sm btn-outline-secondary" title="${t('freqResp.calibration.remove.tooltip')}"><img src="assets/icons/minus.svg" class="tool-svg" alt=""></button>
      </div>`;
    const syncCalRowEnable = ($row) => {
      const loaded = !!$row.data('stereo');
      $row.find('.fcal-active').prop('disabled', !loaded);
      const active = loaded && $row.find('.fcal-active').is(':checked');
      $row.find('.fcal-noise').prop('disabled', !active);   // Java: With-noise only meaningful when Active
    };
    const refreshCalRemoveBtns = () => {
      const $rows = $('#fftCalRows .fft-cal-row');
      $rows.find('.fcal-remove').css('visibility', 'visible');
      $rows.first().find('.fcal-remove').css('visibility', 'hidden');   // row 0 keeps its column, hides Remove
    };
    const rebuildCalEntries = () => {
      const store = this.store;
      store.clearAll();
      $('#fftCalRows .fft-cal-row').each(function () {
        const $row = $(this), stereo = $row.data('stereo');
        if (stereo && $row.find('.fcal-active').is(':checked')) {
          store.addEntry({ left: stereo.left, right: stereo.right },
            $row.data('frcName') || '(unnamed)', $row.find('.fcal-noise').is(':checked'));
        }
      });
      this.updateCalTabSub();
      // A cal change must also apply to a STOPPED FFT (Java: the .frc
      // de-embed is a plot-time transform over the raw lastResult - a toggle there just
      // repaints). Re-emit the display from the RAW accumulator through engine.onResult
      // (the fresh cascade applies + pane.setResult), then paint directly - the rAF
      // branch only paints while recording. No-op for a loaded .fft file or an
      // accumulator-less (non-averaged) run; while running it pre-empts the next tick.
      const base = this.host.getResult && this.host.getResult();
      if (base && this.engine.reemitFftDisplay && this.engine.reemitFftDisplay(base)) {
        const r = this.host.getResult();
        if (r) this.fftView.render(r);
      }
    };
    const appendCalRow = () => { const $row = $(calRowHtml()).appendTo('#fftCalRows'); refreshCalRemoveBtns(); return $row; };
    $('#fftCalRows')
      .on('click', '.fcal-load', async function () {
        const $row = $(this).closest('.fft-cal-row');
        const f = await io.openFile(io.FRC_TYPE);
        if (!f) return;
        try {
          // Keep the .frc text-decode + parse OFF the click handler's hot path so loading
          // a calibration during live FFT capture cannot stall the rAF render loop. The Java
          // FftTabControl.loadFileIntoFftCalRow parses synchronously on the SWT thread, which is
          // fine there (the native FileDialog already detached the parse from rendering); in the
          // browser the click handler runs inside the same event loop that drives capture/render,
          // so yield a macrotask first - the handler returns, the current frame paints, then the
          // (potentially large) decode + loadFrc run without holding up the frame.
          $row.find('.fcal-path').val(f.name);   // immediate feedback that the pick registered
          await new Promise((resolve) => setTimeout(resolve, 0));
          const frcText = io.bytesToText(f.bytes);
          const stereo = io.loadFrc(frcText);
          $row.data('stereo', stereo);
          $row.data('frcName', f.name);   // for the in-session live-reload match (CALIBRATION_FILE_SAVED)
          // Store the ORIGINAL bytes in the shared cal-store (dedup by SHA-256; shared with the
          // FreqResp pane) and keep the hash on the row so it round-trips through persistCalRows.
          try { $row.data('hash', await putCal(f.bytes, f.name)); }
          catch (e) { console.warn('FFT: putCal failed (cal not persisted):', e && e.message); }
          $row.find('.fcal-path').val(f.name);
          $row.find('.fcal-active').prop('disabled', false);   // loading enables the toggle but leaves its checked state UNCHANGED (Java loadFileIntoFftCalRow never touches activeCheck; it is bound to entry.active())
          syncCalRowEnable($row);
          rebuildCalEntries();
          persistCalRows();   // remember the loaded file (name + hash + state) across reloads
          $('#status').text(`calibration loaded: ${f.name} (${stereo.left.freqs.length} points)`);
        } catch (e) { $('#status').text('calibration load failed: ' + e.message); }
      })
      .on('click', '.fcal-clear', function () {
        const $row = $(this).closest('.fft-cal-row');
        $row.removeData('stereo'); $row.removeData('frcName'); $row.removeData('hash');
        $row.find('.fcal-path').val(t('freqResp.calibration.path.none'));
        $row.find('.fcal-active, .fcal-noise').prop({ checked: false, disabled: true });
        rebuildCalEntries();
        persistCalRows();   // clearing a row drops its entry (bytes swept later by pruneCals)
      })
      .on('click', '.fcal-add', () => appendCalRow())
      .on('click', '.fcal-remove', function () {
        if ($('#fftCalRows .fft-cal-row').length <= 1) return;
        $(this).closest('.fft-cal-row').remove();
        rebuildCalEntries();
        persistCalRows();   // removing a row drops its stored entry
      })
      .on('change', '.fcal-active, .fcal-noise', function () {
        syncCalRowEnable($(this).closest('.fft-cal-row'));
        rebuildCalEntries();
        persistCalRows();   // remember the Active / With-noise state too
      });
    appendCalRow();   // row 0 always present (Java buildCalibrationTab)

    // One-time migration of pre-2.3 records (fftcal.N -> { active, noise, frc }): move each row's
    // .frc TEXT into the shared cal-store (putCal -> hash) and rewrite it as a prefs
    // CalibrationEntry, then delete the legacy key. Also drains the even older localStorage-backed
    // fftcal.N records into that same path. Runs before the row rebuild below.
    const migrateLegacyCalRows = async () => {
      // Drain the oldest localStorage records into IndexedDB so the loop below sees them.
      for (const key of fileStore.keys(LEGACY_CAL_KEY_PREFIX)) {
        const old = fileStore.get(key);
        if (old && (await fileStore.idbGet(key)) == null) await fileStore.idbPut(key, old.name, old.data);
        fileStore.remove(key);
      }
      const legacyKeys = (await fileStore.idbKeys(LEGACY_CAL_KEY_PREFIX))
        .sort((a, b) => (parseInt(a.slice(LEGACY_CAL_KEY_PREFIX.length), 10) || 0) - (parseInt(b.slice(LEGACY_CAL_KEY_PREFIX.length), 10) || 0));
      if (legacyKeys.length === 0) return;
      const migrated = [];
      for (const key of legacyKeys) {
        const stored = await fileStore.idbGet(key);
        if (!stored) { await fileStore.idbRemove(key); continue; }
        let rec;
        try { rec = JSON.parse(stored.data); } catch (_) { await fileStore.idbRemove(key); continue; }
        if (rec == null || rec.frc == null) { await fileStore.idbRemove(key); continue; }
        let hash = null;
        // Only drop the legacy record AFTER the bytes are safely in the cal-store, so a
        // putCal failure leaves it in place for a later retry (no data loss).
        try { hash = await putCal(new TextEncoder().encode(rec.frc), stored.name); }
        catch (e) { console.warn('FFT: legacy cal migration putCal failed:', e && e.message); continue; }
        await fileStore.idbRemove(key);
        migrated.push(new CalibrationEntry(stored.name || null, !!rec.active, !!rec.noise, hash));
      }
      // Replace prefs.fftCalibrations with the migrated set (a legacy install has no 2.3 entries yet).
      if (migrated.length) {
        prefs.fftCalibrations.length = 0;
        for (const e of migrated) prefs.addFftCalibration(e);
        prefs.save();
      }
    };

    // Restore the persisted calibration rows on startup: rebuild one row per stored
    // CalibrationEntry, re-read its bytes from the shared cal-store by hash, re-parse + restore
    // Active / With-noise, and re-apply the cascade. Runs FIRST on the store chain (after the
    // legacy migration), so any user persist queues behind it.
    const restoreCalRows = async () => {
      await migrateLegacyCalRows();
      const entries = prefs.fftCalibrations.filter((e) => e.hash);
      let idx = 0;
      for (const entry of entries) {
        let stored;
        try { stored = await getCal(entry.hash); } catch (_) { continue; }
        if (!stored) continue;   // bytes gone (pruned / cleared elsewhere) - skip
        let stereo;
        try { stereo = io.loadFrc(new TextDecoder().decode(stored.bytes)); } catch (_) { continue; }
        const name = entry.path || stored.name;
        // Row 0 already exists (appendCalRow above); later rows need appending.
        const $row = idx === 0 ? $('#fftCalRows .fft-cal-row').first() : appendCalRow();
        $row.data('stereo', stereo);
        $row.data('frcName', name);
        $row.data('hash', entry.hash);
        $row.find('.fcal-path').val(name).attr('title', name);
        $row.find('.fcal-active').prop({ disabled: false, checked: entry.active().get() });
        $row.find('.fcal-noise').prop('checked', entry.withNoise().get());
        syncCalRowEnable($row);
        idx++;
      }
      if (idx > 0) rebuildCalEntries();
    };
    this._calRestore = chainCalStoreOp(restoreCalRows);

    // In-session .frc save live-reload (Java FftTabControl subscribes CALIBRATION_FILE_SAVED ->
    // onCalibrationFileSaved): if any loaded calibration row references the just-saved file,
    // re-apply the rows through the shared store (rebuildCalEntries) so the new curve takes effect
    // without re-browsing. Across-reload restoration is handled by restoreCalRows() above (the
    // .frc text is persisted in the file-store); this only refreshes from the in-memory rows.
    MessageBus.instance().subscribe(Events.CALIBRATION_FILE_SAVED, (path) => {
      let touched = false;
      $('#fftCalRows .fft-cal-row').each(function () {
        if ($(this).data('frcName') === path) touched = true;
      });
      if (touched) rebuildCalEntries();
    });

    // Logarithmic frequency axis toggle (Java fftLogFreqAxis) - drives the FFT view.
    $('#logAxis').on('change', () => { prefs.fftLogFreqAxis.set($('#logAxis').is(':checked')); this.fftView.applyPrefs(); });
    // Time-domain discontinuity gate toggle (Java detectTimeDiscCheck bound to the pref, read
    // live by the worker): mirror into engine.config so the change takes effect mid-record.
    $('#fftDetectTimeDisc').on('change', () => {
      const v = $('#fftDetectTimeDisc').is(':checked');
      prefs.fftDetectTimeDiscontinuity.set(v);
      this.engine.config.fftDetectTimeDiscontinuity = v;
    });
    // FFT magnitude-unit selector (Java magUnitCombo) - relabels the view's y-axis
    // (V / V√Hz / dBV / dBFS); the range stays canonical dBFS, so this is draw-time only.
    $('#fftMagUnit').on('change', () => { prefs.fftMagUnit.set($('#fftMagUnit').val()); this.fftView.applyPrefs(); });

    return this;
  }
}
