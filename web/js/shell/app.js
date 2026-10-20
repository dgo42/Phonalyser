/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// App entry: wires the native-styled Bootstrap/jQuery chrome to the live FFT
// engine and renders the scope + spectrum Canvas views. First runnable slice;
// the remaining blueprint modules layer on around this engine.

import { AudioBackend } from '../audio/backend.js';
import { AudioEngine } from './audio-engine.js';
import { deviceErrorText } from '../audio/device-failure-reason.js';   // the shell's alert body
import { Qa40xDeviceFinder } from '../qa40x/qa40x-device-finder.js';
import { Qa40xDeviceManager } from '../qa40x/qa40x-device-manager.js';
import { QA40X_BACKEND } from '../qa40x/qa40x-rate-constraint.js';
import { LoopbackDeviceManager } from '../loopback/loopback-device-manager.js';
import { LOOPBACK_BACKEND } from '../loopback/loopback-device-ref.js';
import { FftViewCorrection } from '../fft/fft-view-correction.js';
import { CalibrationDialog } from './calibration-dialog.js';
import { CardEditorDialog } from './card-editor-dialog.js';
import { NetDeviceManager } from '../net/net-device-manager.js';
import { netManagerFacade } from '../net/net-manager-facade.js';
import { BenchCards } from '../net/bench-cards.js';
import { CalibrationCopyOffers } from '../net/calibration-copy-offers.js';
import { CalibrationStore } from '../net/calibration-store.js';
import { BenchCardSource } from '../net/bench-card-source.js';
import { NetConnection } from '../net/net-connection.js';
import { NetPreferences, makeServerEntry } from '../net/net-preferences.js';
import { NetServerListDialog } from '../net/net-server-list-dialog.js';
import { NetServerList } from '../net/net-server-list.js';
import { IntervalTicker } from '../net/ticker.js';
import { ServerProber, wsUrlOf } from '../net/server-prober.js';
import { stackOverOpenModals } from '../ui/modal-stack.js';
import { FftView } from '../fft/fft-view.js';
import { ScopeView } from '../scope/scope-view.js';
import { GenSignalForm } from '../enums/gen-signal-form.js';
import { Preferences } from '../store/preferences.js';
import { DeviceProfileStore } from '../store/device-profiles.js';
import { loadDeviceCatalog } from '../store/device-catalog.js';
import { createConfigPorts } from '../store/config-port.js';
import { JsonConfigDialog } from './json-config-dialog.js';
import { t, initBase, setLocale, stripMnemonics } from '../i18n/i18n.js';
import { LOCALES } from '../i18n/locales.js';
import { readWav, readAiff } from '../io/wav.js';
import { saveScopeCapture, saveStreaming, findFullPeriodWindow, formatForName } from '../io/scope-capture.js';
import { encodeFlac, decodeFlac } from '../io/flac.js';
import { saveSpectrum, loadSpectrum } from '../fft/fft-spectrum.js';
import { loadFrc } from '../io/frc.js';
import { pruneCals } from '../io/cal-store.js';
import { saveFile, openFile, bytesToText, pickSaveTarget, writeToTarget } from '../io/file-picker.js';
import { FreqRespPane } from '../freqresp/freqresp-pane.js';
import { TuneNotchWizard } from '../freqresp/tune-notch-wizard.js';
import { ScreenshotDialog } from '../ui/screenshot.js';
import { PredistortionHost } from './predistortion-host.js';
import { PredistortionWizard } from './predistortion-wizard.js';
import { PreferencesDialog } from './preferences-dialog.js';
import { Qa40xSettingsDialog } from '../qa40x/qa40x-settings-dialog.js';
import { StartupSplash } from './startup-splash.js';
import { TipDialog } from './tip-dialog.js';
import { MainTab } from './main-tab.js';
import { ScopePane } from '../scope/scope-pane.js';
import { ScopeTabControl } from '../scope/scope-tab-control.js';
import { FftPane } from '../fft/fft-pane.js';
import { FftTabControl } from '../fft/fft-tab-control.js';
import { GeneratorPane } from '../generator/generator-pane.js';
import { MessageBus } from '../bus/message-bus.js';
import { Events } from '../bus/events.js';

const $ = window.jQuery;
const prefs = Preferences.instance();  // load() runs in the constructor
// Per-card device-profile store (Java: the store lives inside Preferences). Seeds the
// shipped devices.yaml catalog on first run + runs the once-per-contentVersion upgrade merge
// in its constructor (Preferences.loadDevices at startup); its apply* setters push per-channel
// full-scale into prefs at device selection (wired in PreferencesDialog). Constructed in init()
// once the catalog YAML has been fetched + parsed (an async load - see loadDeviceCatalog).
let deviceStore;
// Selectable sample rates - identical to the Java list (sound/*DeviceManager, cli/util/SampleRates):
// 8/11.025/16/22.05/32 kHz + 44.1/48 kHz and their multiples up to 768 kHz.
const RATES = [8000, 11025, 16000, 22050, 32000, 44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000, 705600, 768000];
// GenSignalForm token -> localized display label for the #signalForm select.
const formLabel = (form) => t(`generator.signalForm.${form}`);
// GenSignalForm token -> per-form waveform pictogram (web/assets/icons/signal-<kebab>.svg).
const formIcon = (form) => `assets/icons/signal-${form.toLowerCase().replace(/_/g, '-')}.svg`;

// The audio backend (Java sound.AudioBackend) and the QA40x device manager it dispatches to.
// Both are built in init(), NOT here: the manager needs the DeviceProfileStore, which exists only
// once the device-catalog fetch has resolved (see `deviceStore` above).
//
// LATE CONSTRUCTION of the engine - not late injection into it, and not a manager supplier -
// because that is the ownership Java has: AudioBackend OWNS its per-backend managers and hands
// them out (AudioBackend.qa40x() / manager(type) / qa40xManager()), and the web engine takes the
// FINISHED manager through its constructor, which is its only seam. So the engine is what waits
// for the store, not the manager for the engine. Everything at module scope that needs the engine
// at CONSTRUCTION time (the FFT view correction + view, the FreqResp pane, the predistortion host,
// the three engine callbacks) is built in that same init block; everything else reaches it from a
// callback, which cannot run earlier - the startup splash covers the UI until the load-time scan
// settles, so no pane handler can fire in between.
let engine;
let qa40xManager;
// The digital loopback backend's manager. Built in the same init block, and for the same reason
// held here rather than inside the engine: it OWNS the crossing its capture and playback lanes
// meet on, so one instance per page is what makes a lane pair a loop at all. It needs no device
// and no permission - only the two depth suppliers below.
let loopbackManager;
// The net backend (doc/NET-PROTOCOL.md): the session + remote catalogue, and the remembered
// servers block the server-list modal edits. Both are constructed in init's modals step, where
// the Preferences store is already loaded - registration replays the stored block into it.
let netManager;
let netPreferences;
/**
 * The Phonalyser server that SERVED THIS PAGE, as `GET /info` on the page's own origin answered
 * it (spec §3) - null when the origin is not one: docs/web on GitHub Pages, a dev server, a
 * file:// open. Probed ONCE at load, and two things read it:
 *
 *  - the auto-connect below: the server the operator loaded the client from is the server they
 *    meant, whichever packaging the bundle is (the embedded build flag used to be the only test,
 *    so a FULL bundle served from the same place connected to nothing);
 *  - the backend combo: a page served from there is plain http from a LAN host and therefore not
 *    a secure context, so it offers no local backends at all.
 *
 * One fact, one probe, two consumers - the alternative is two tests that can disagree.
 */
let servingServer = null;
/** How long the operator waits for a bench to answer a dial - the whole handshake, socket and
 *  hello together (NetConnection.open's budget). One second is what a user reads as the app
 *  working; the request timeouts behind it are the generous ones. */
const CONNECT_TIMEOUT_MS = 3000;
// The FFT display corrections: the .frc de-embed, the plot-time mains comb and the statistics
// recompute run in the analysis pipeline's finalize stage (fft/fft-finalize.js), which hands the
// pane a finished spectrum; the coherent accumulator stays raw. FftViewCorrection adds the
// view-side rest (the IMD table, the cal gain the blue dots read) in the FFT pane's
// FFT_RESULT_AVAILABLE subscriber. Both take the engine at construction, so both are built in
// init's audio-backend block (see `engine`).
let fftViewCorrection;
let fftView;
const scopeView = new ScopeView(document.getElementById('scope'), { prefs });
let prefsModal, aboutModal;
let shotModal;
let confirmModal;
let alertModal;   // the shared one-button alert modal (device-error surface; constructed in init's modals step)
let prefsDialog;   // the Preferences dialog (constructed in init's modals step)
let calibrationDialog;   // the unified ADC/DAC calibration dialog (constructed in init, after applyI18n)
let benchCalibration;   // where a calibrate WRITE goes while a server's backend is selected (init's modals step)
/** The human LABEL of the selected input device - what the card recognition patterns match (the
 *  <select> value carries a Web Audio deviceId). The Preferences dialog owns the combo; before it
 *  is built the DOM answers directly. */
const inputDeviceLabel = () => (prefsDialog ? prefsDialog.inputDeviceLabel()
  : $('#inSel option:selected').text());
let scopeTabControl;   // the oscilloscope settings strip (constructed in init's modals step)
let fftTabControl;   // the FFT settings strip (constructed in init's modals step)
let genPane;   // the generator pane (constructed in init, before the first applyPrefsToUi)
let scopePane;   // the oscilloscope pane (canvas / scrollbars / record / render loop; constructed in init, before the scope strip)
let fftPane;   // the FFT pane (spectrum canvas / record / readout+IMD / render loop; constructed in init, before fftTabControl)
let mainTab;   // the main tab (the rAF render-frame driver + the 3-pane collapse/sash layout; constructed in init, after both panes)

// The scope navigation scrollbars (vertSlider / navSlider) + NAV_RANGE / condensed-overview
// decimation moved to scope/scope-pane.js (Java ScopePane); app.js drives them through the
// scopePane instance.

// A DAC full-scale move - a card RANGE switch, or a DAC re-calibration - must NOT change the
// generated LEVEL. The amplitude the user typed is absolute volts; the DDS derives its normalised
// amplitude as Vrms/(fsPeak·rawRms(form)), so the physical output is Vrms only while the kernel
// knows the CURRENT full scale. The kernel learns it on a retune - the generator reads the full
// scale and the right-lane ratio fsLeft/fsRight (which the same card edit moves) off the
// preferences on every retune - so retune here, which is a no-op unless the generator is running;
// without it the worklet kept the OLD full scale while the hardware moved to the new one, and the
// trace jumped by fsNew/fsOld on every range change. The amplitude CEILING is a separate concern,
// handled by syncAmpMax.
const syncDacFullScale = () => {
  if (!engine) return;   // the card store can resolve a profile before init built the engine
  engine.retuneGenerator();
};
prefs.dacFsVoltageAmpl.addListener(syncDacFullScale);
prefs.dacFsVoltageAmplRight.addListener(syncDacFullScale);

// The live sample rates the panes derive their Nyquist ceilings from - the Preferences dialog's
// combos (which show the device-native input rate) with the committed prefs as the fallback.
const outRate = () => parseInt($('#outRate').val(), 10) || prefs.current().outputSampleRate || 48000;
const inRate = () => parseInt($('#inRate').val(), 10) || prefs.current().inputSampleRate || 48000;

// Every NumericStepField is built by the pane or settings strip that owns it (Java: each pane
// constructs its own): the generator pane (frequency / amplitude / dither / duty / dual-tone
// split / sweep / export duration), the FFT strip (averages, stop-after-N, the THD band and
// harmonic counts, the manual fundamental), the scope strip (V/div, t/div, hysteresis, save
// duration - and the 1-2-5 ladders its view's wheel zoom shares), the FreqResp strip (the sweep
// band, amplitude, lead-in, points) and the calibration dialog (its two channel fields).

// ----- i18n: replace every [data-i18n] element's text with its translation -----
// SWT mnemonics are stripped (and '&&' unescaped to '&') so the desktop bundle
// values render cleanly in a chrome that draws no accelerators.
function applyI18n() {
  document.querySelectorAll('[data-i18n]').forEach((el) => {
    el.textContent = stripMnemonics(t(el.getAttribute('data-i18n'))).replace(/\.\.\.$/, '...');
  });
  document.querySelectorAll('[data-i18n-title]').forEach((el) => {
    el.title = stripMnemonics(t(el.getAttribute('data-i18n-title')));
  });
  document.querySelectorAll('[data-i18n-placeholder]').forEach((el) => {
    el.placeholder = stripMnemonics(t(el.getAttribute('data-i18n-placeholder')));
  });
  // Custom-file "Browse" button text lives in a ::after pseudo-element; localize it
  // through a CSS custom property the stylesheet reads.
  document.querySelectorAll('[data-i18n-browse]').forEach((el) => {
    el.style.setProperty('--browse-label', '"' + stripMnemonics(t(el.getAttribute('data-i18n-browse'))) + '"');
  });
}

function fill($sel, values, def, fmt = v => v) {
  $sel.empty();
  for (const v of values) $sel.append(`<option value="${v}" ${v === def ? 'selected' : ''}>${fmt(v)}</option>`);
}

function initSelects() {
  const be = prefs.current();
  fill($('#inRate'), [be.inputSampleRate], be.inputSampleRate, v => v + ' Hz');   // input rate = device native rate only (set on scan)
  fill($('#outRate'), RATES, be.outputSampleRate, v => v + ' Hz');
  const hc = navigator.hardwareConcurrency || 4;
  fill($('#threads'), Array.from({ length: Math.min(hc, 16) }, (_, i) => i + 1), 1);   // default 1 = single-worker (low GC churn); pool is opt-in to avoid starving the audio thread
  fill($('#signalForm'), Object.values(GenSignalForm), prefs.genSignalForm.get(), formLabel);
  buildLanguageMenu();
}

// Build the Language submenu (Java MainWindow.addLanguageMenuItem): one
// radio-style item per discovered locale, labelled with its endonym, the active
// locale pre-checked.  Selecting one switches the locale and re-renders.
function buildLanguageMenu() {
  const active = prefs.uiLanguage.get();
  const $menu = $('#langMenu').empty();
  // English is NOT pinned first - every locale sorts alphabetically by tag (en falls between el and es).
  for (const l of [...LOCALES].sort((a, b) => a.tag.localeCompare(b.tag))) {
    const checked = l.tag === active ? ' <i class="bi bi-check2"></i>' : '';
    $menu.append(
      `<li><button class="dropdown-item" type="button" data-lang="${l.tag}">${l.endonym}${checked}</button></li>`);
  }
}

// ----- bind the persistent UI controls to their Preferences Propertys -----
// Initial control values come FROM the loaded prefs; user edits write BACK so the
// debounced localStorage save (and flush() on pagehide) persists them.
function applyPrefsToUi() {
  // The FFT settings controls (#fftSize / #window / #overlap / #averages /
  // #coherent / #align / #fftStopAfter* / #fftMains) are seeded by FftTabControl
  // .seedFftControls(); a preset recall re-seeds them through this function (the strip's
  // injected applyPrefsToUi).
  if (fftTabControl) fftTabControl.seedFftControls();

  // The generator settings controls (#signalForm / #dither / #snap / sweep + .dpd rows)
  // are seeded by GeneratorPane.seedGeneratorControls(); a FFT-preset recall re-seeds them
  // through this function too (guarded - genPane is built after this first run).
  if (genPane) genPane.seedGeneratorControls();

  const be = prefs.current();
  if (be.inputSampleRate) $('#inRate').val(String(be.inputSampleRate));
  if (be.outputSampleRate) $('#outRate').val(String(be.outputSampleRate));
  // Per-channel ADC/DAC full-scale readouts (info only - the crosshair Calibrate flows own them,
  // now stored per-card). Rendered as L / R spans; kept in sync by PreferencesDialog on open + card edit.
  const fmtFs = (v) => ((v > 0 && Number.isFinite(v)) ? v.toFixed(6) : '-');
  $('#adcFsVrms').text(fmtFs(prefs.getAdcFsVoltageRms('L')));
  $('#adcFsVrmsRight').text(fmtFs(prefs.getAdcFsVoltageRms('R')));
  $('#dacFsAmpl').text(fmtFs(prefs.getDacFsVoltageAmpl('L')));
  $('#dacFsAmplRight').text(fmtFs(prefs.getDacFsVoltageAmpl('R')));
  // Look & Feel (main-tab orientation + small icons + UI font) is applied from the saved
  // prefs in the modals step, once the PreferencesDialog instance exists.
}

function bindPrefs() {
  // The FFT settings inputs (#fftSize / #window / #overlap / #averages /
  // #coherent / #fftStopAfter* / #fftMains) are wired by FftTabControl.bind().
  // The generator settings inputs (#signalForm / #dither / #snap / sweep loop / .dpd) are
  // wired by GeneratorPane.bind().

  // The audio device/rate <select>s (#inSel/#outSel/#inRate/#outRate) live inside the
  // Preferences dialog; their staged change handlers are wired by PreferencesDialog.bind().
  // The ADC/DAC full-scale is now per-card (owned by the crosshair Calibrate flows), shown as
  // read-only per-channel spans in the Audio tab - there is no editable full-scale field to bind.
}

// There is no engine-side settings snapshot (Java has none either): every controller reads the
// one Preferences store live. The panes write it - the capture / playback devices and rates
// through the Preferences dialog (on scan and on OK), the generator's tone, sweep, dither and
// output routing through the generator pane's controls, the FFT geometry, averaging, THD settings
// and calibration anchors through the FFT settings strip, the scope's time/div through the scope
// pane.

// Render an array of values as `.tile` chip spans (the Java painted tile look).
const tileChips = (...vals) => vals.filter(v => v != null && v !== '').map(v => `<span class="tile">${v}</span>`).join('');

// The FFT live tab-tile chips + THD/stop-after gating helpers (fftAveragesValue /
// formatAverages / shortHz / updateTabSub / updateThdTabSub / updateCalTabSub /
// updatePresetTabSub / refreshFftTiles / syncThdEnable / refreshStopAfterEnable /
// syncAlign) moved to fft/fft-tab-control.js (Java FftTabControl); app.js reaches them
// via the fftTabControl instance (constructed in init's modals step).

// The FFT readout + THD/harmonics table (updateReadout) and the dual-tone IMD table (updateImd)
// moved to fft/fft-pane.js (Java FftPane); the FFT render branch drives them via the fftPane
// instance.

// The scope tab tiles (shortNum / shortSi / shortTimePerDiv / refreshScopeTiles), the
// measurement-table render + reusable-cell machinery (renderMeasurementTable / measTableCells /
// setText / ensureMeasTableRows) and the measurement-button sync (syncScopeMeasButtons /
// syncMeasChannelButtons) moved to scope/scope-pane.js (Java ScopePane); app.js drives them
// through the scopePane instance.

// Data callbacks only STORE the latest; a single rAF loop renders at ~60 fps so the
// realtime capture threads can never saturate the UI (mirrors the desktop render loop).
// The scope frame (latestScope) is consumed by the scope pane's render() each frame and by the
// scope Save (ScopeTabControl) via the injected getter; the FFT result (latestResult) lives in
// the FFT pane, which subscribes FFT_RESULT_AVAILABLE itself and applies the view correction
// before storing it.
let latestScope = null;
/** Wires the scope frame callback - called from init's audio-backend block, since the engine
 *  is constructed there (see `engine`). The FFT side needs no callback: the controller publishes
 *  every displayed result as FFT_RESULT_AVAILABLE and its self-stops as
 *  FFT_RECORDING_AUTO_STOPPED, both subscribed by the FFT pane. */
function bindEngineCallbacks() {
  engine.scope.onScope = (buf, info) => { latestScope = { buf, info }; };
}
// The MAIN rAF render loop (renderLoop: scopePane.render() + fftPane.render() each frame, each
// pane self-gated on its OWN record state) moved to shell/main-tab.js (Java MultifunctionalTab);
// app.js kicks it via mainTab.start() in init (after both panes are constructed).

// The scope resize -> redraw (ResizeObserver: redrawScopeOnResize) moved to
// scope/scope-pane.js (Java ScopePane); the pane installs its own observer in bind().

// The generator pane (signal-form combo, freq / amp / duty labels, dither combo, snap,
// dual-tone split, sweep, .dpd corrections row, Play / ON-AIR, Save-to, file player) and
// restartGenerator() moved to generator/generator-pane.js (Java GeneratorPane); constructed
// as the genPane init step (before applyPrefsToUi so its seed runs in the init seed). app.js
// reaches its cross-pane methods (syncFormUI / restartGenerator / refreshFreqLabel /
// buildFormCombo) via the genPane instance, and the shared lifecycle flags flow the other
// way through injected closures.

// A structural FFT change re-acquires ONLY the FFT consumer - neither disturbs the other two
// lifecycles (Java: an FFT-length change is FftController's concern, a form change is
// GeneratorController's, and the scope keeps running throughout).
async function restartFft() {
  if (busy || !engine.fft.recording) return;
  busy = true;
  try { await engine.fft.setRecording(false); await engine.fft.setRecording(true); }
  finally { fftPane.syncFftLed(); busy = false; }
}

// The FFT SETTINGS STRIP (tile-tabs, window / overlap / averages / threads / coherent /
// align / THD band + harmonics, presets, save / load, calibration, screenshot + ADC
// calibrate) lives in fft/fft-tab-control.js (Java FftTabControl); constructed in init's
// fftTabControl step. The FFT PANE machinery (spectrum canvas / Record LED / readout +
// THD/IMD table / render loop) lives in fft/fft-pane.js (Java FftPane); the strip holds
// the pane itself for its result state, as Java's strip holds the FftView.

// The scope SETTINGS STRIP (tile-tabs, channel / filter / trigger controls,
// presets, save / load, ADC calibrate) lives in scope/scope-tab-control.js
// (Java ScopeTabControl); constructed in init's modals step. It holds the scope
// PANE (canvas / scrollbars / record / render loop) the way Java's strip holds
// the ScopeView + ScopeController pair.

// The scope PANE machinery - the file-mode horizontal nav (scopeOnFileBack), the
// SINGLE-mode Start gate (syncTriggerStart), the measurement-table header buttons +
// pop-out window (setMeasTablePopped / makeMeasWindowDraggable), and the vertical /
// horizontal navigation scrollbars (onVertScrollMoved / onHorizScrollMoved /
// offsetFracBounds / syncOffsetScrollbar) - moved to scope/scope-pane.js (Java
// ScopePane). app.js drives them through the scopePane instance; the strip seeds the
// controls via scopeTabControl.seedScopeControls() and reaches the pane through its
// public methods.

// ----- L/R channel toggles -----
// Scope has NO channel-enable L/R buttons in its header (Java ScopeView): channel
// enable lives in the Left/Right tab "Enabled" checkbox (#scopeLeftEnable /
// #scopeRightEnable, wired above); the header L/R pair is the measurement-channel
// picker (#scopeMeasL / #scopeMeasR), wired below. The FFT header's L/R radio is the FFT
// pane's (FftPane.bindChannelRadio).

// ----- record LED (per-pane capture toggle) -----
// Independent per-pane Record (Java: the scope and FFT panes each hold their own
// SharedCapture reference; the device opens on the first acquire and closes on the
// last release). The scope LED drives setScopeRecording (moved to ScopePane); the FFT
// LED drives setFftRecording (moved to FftPane) - each lights its OWN LED. The
// generator (#genPlay) is separate. app.js reaches the FFT LED sync through
// fftPane.syncFftLed() (restartFft / restartPreservingConfig).

// The scope SETTINGS STRIP (ScopeTabControl) reaches the scope PANE through the scopePane
// instance it is constructed with: requestRedraw / refreshTiles / refreshFields /
// syncTriggerStart / setTriggerControlsEnabled / syncOffsetScrollbar / redrawScrollbars /
// syncMeasChannelButtons / syncMeasButtons / recState / onFileBack / stopCaptureForFileLoad /
// onSignalFileLoaded are all its public methods (see init's scopeTabControl step).

// FFT view header buttons (Java FftView autoSetupBtn / maximizeBtn) - were dead.
$('#fftAutoSetup').on('click', () => fftView.autoSetup());
$('#fftMaximize').on('click', () => fftView.maximize());

// The FFT SETTINGS STRIP (FftTabControl) holds the FFT PANE for its result state (getResult /
// setResult / showLoadedBanner) and gets the two shell helpers it still needs by name
// (applyPrefsToUi); everything cross-pane travels on the bus - the FFT-length
// change the generator re-snaps to (FFT_LENGTH_CHANGED), the stop before a .fft load
// (FFT_RECORDING_STOP_REQUESTED), the Utility camera (FFT_SCREENSHOT_REQUESTED).

// Scope view header Save-name field moved to ScopeTabControl.
// FFT #logAxis / #fftMagUnit selectors moved to FftTabControl.

// Static HTML help (copied from the desktop app, lunr-searchable). Opens in a separate
// POP-UP WINDOW (not a tab) sized 1024×800, top-right corner aligned to the app window's
// top-right corner, reusing the named window on re-open. Language follows the UI locale
// (help ships en/de/uk), falling back to en. `page` defaults to the contents index.
const HELP_LANGS = ['en', 'de', 'uk'];
let helpWin = null;   // the open help pop-up, kept so a UI-language switch can re-navigate it
// The help language for the current UI locale (help ships en/de/uk), falling back to en.
function helpLang() {
  const loc = (document.documentElement.lang || 'en').slice(0, 2).toLowerCase();
  return HELP_LANGS.includes(loc) ? loc : 'en';
}
function openHelp(page) {
  const w = 783, h = 712;
  const appX = window.screenX != null ? window.screenX : (window.screenLeft || 0);
  const appY = window.screenY != null ? window.screenY : (window.screenTop || 0);
  const left = appX + window.outerWidth - 13;   // help top-LEFT corner aligned to app top-RIGHT corner - 13 pixel gap
  const top = Math.max(0, appY);
  const features = `popup=yes,width=${w},height=${h},left=${left},top=${top}`;
  helpWin = window.open(`help/${helpLang()}/${page || 'index.html'}`, 'phonalyser-help', features);
  if (helpWin) helpWin.focus();
}
// On a UI-language switch, re-point an ALREADY-OPEN help pop-up to the SAME page in the new
// language (Java HelpViewer.refreshLanguage live-switches its viewer). Reads the pop-up's current
// page from its same-origin location; leaves it alone if it navigated to an external URL.
function refreshOpenHelp() {
  if (!helpWin || helpWin.closed) return;
  let path;
  try { path = helpWin.location.pathname; } catch (e) { return; }   // external (cross-origin) page - don't yank it
  const m = /\/help\/(?:en|de|uk)\/([^/?#]*)/.exec(path);
  const page = (m && m[1]) ? m[1] : 'index.html';
  helpWin.location.href = `help/${helpLang()}/${page}`;
  helpWin.focus();
}

// Which help page matches the current context - for Ctrl+F1. Read-only DOM inspection at
// keypress time (no listeners on the panes): the active top tab, then the focused pane.
function helpContextPage() {
  const fr = document.getElementById('tab-fr');
  if (fr && fr.classList.contains('active')) return 'freqresp.html';
  const ae = document.activeElement;
  if (ae && ae.closest) {
    if (ae.closest('#scopePane')) return 'oscilloscope.html';
    if (ae.closest('#genCol')) return 'generator.html';
    if (ae.closest('#fftPane')) return 'fft.html';
  }
  return 'index.html';
}

// Help submenu (#menuHelp is the dropdown toggle). Desktop-only items (check-for-update,
// startup checks, rebuild index) are omitted: the web auto-updates via the
// service worker and the search index is built at build time.
$('#helpShow').on('click', () => openHelp());
$('#helpShowActive').on('click', () => openHelp(helpContextPage()));
$('#helpReport').on('click', () => window.open('https://github.com/dgo42/Phonalyser/issues/new', '_blank', 'noopener'));
// Tip of the day (Java MainWindow Help -> Tip of the day -> new TipOfTheDayDialog(shell).open()):
// a small non-modal popup docked bottom-left. The Help entry opens it unconditionally; the
// startup path (init, after the splash dismisses) opens it only when showTipsAtStartup is set.
const tipDialog = new TipDialog({ prefs, t });
$('#helpTip').on('click', () => tipDialog.open());
// About dialog: paint the SAME branded artwork the startup splash draws (Java
// MainWindow.showAboutDialog -> StartupSplash.showAsAbout) - version/tagline/
// copyright/license/URL are all on the canvas, and the repo URL is clickable.
// Painted on show (fonts + version + locale are all resolved by then), and
// re-painted whenever the modal opens so a locale switch re-localizes the tagline.
const aboutSplash = new StartupSplash({
  version: () => ($('.menu-ver').text() || '').replace(/·.*$/, '').trim(),
  t,
});
$('#helpAbout').on('click', () => {
  aboutSplash.showAsAbout(document.getElementById('aboutCanvas'));
  aboutModal.show();
});
// F1 -> help contents; Ctrl+F1 -> contextual help for the active pane / tab. preventDefault
// so the browser's own F1 help doesn't also fire.
document.addEventListener('keydown', (e) => {
  if (e.key === 'F1') {
    e.preventDefault();
    openHelp(e.ctrlKey ? helpContextPage() : 'index.html');
  }
});

// ----- shared re-entrancy guard (web-only; no Java equivalent) -----
// The three running/recording lifecycles are OWNED BY THE CONTROLLERS and read through their
// observable getters - engine.generator.running / engine.scope.recording / engine.fft.recording;
// the shell keeps no copy (Java: GeneratorController.running, ScopeController.isCapturing(),
// FftController.worker.isRunning()). `busy` alone stays here: startGenerator/stopGenerator and the
// consumer acquire/release are async (open/close AudioContexts), so a second click mid-transition
// races and can tear down a half-built audio graph (STATUS_BREAKPOINT). Ignore clicks until
// settled. Java is synchronous on the UI thread so it needs no such guard. Panes read/write it
// through the injected closures below.
let busy = false;

// ----- Language submenu: switch locale, persist, re-render the chrome -----
$('#langMenu').on('click', '[data-lang]', async (ev) => {
  const tag = ev.currentTarget.getAttribute('data-lang');
  if (tag === prefs.uiLanguage.get()) return;
  prefs.uiLanguage.set(tag);
  await setLocale(tag);
  refreshOpenHelp();   // if the help pop-up is open, switch it to the new language too
  buildLanguageMenu();   // move the check mark to the new active locale
  applyI18n();
  // Re-resolve the dynamically-built signal-form labels (hidden select + combo).
  fill($('#signalForm'), Object.values(GenSignalForm), $('#signalForm').val(), formLabel);
  genPane.buildFormCombo();
  // Re-resolve the unit suffixes of every pane's step fields.
  genPane.refreshFields(); freqRespPane.refreshFields();
  if (scopeTabControl) scopeTabControl.refreshFields();
  if (fftTabControl) fftTabControl.refreshFields();
  if (calibrationDialog) calibrationDialog.refreshFields();
  genPane.refreshFreqLabel(); if (fftTabControl) fftTabControl.refreshFftTiles(); freqRespPane.refreshFftLabel();
});

// ============================ File I/O wiring ============================

const WAV_TYPE = [{ description: 'Audio (WAV / AIFF / FLAC)', accept: 'audio/wav', extensions: ['.wav', '.aiff', '.aif', '.flac'] }];
const FFT_TYPE = [{ description: 'FFT spectrum', accept: 'text/plain', extensions: ['.fft'] }];
const FRC_TYPE = [{ description: 'Filter calibration', accept: 'text/plain', extensions: ['.frc'] }];

// The generator "Save to..." (DdsKernel render + dither + STEREO WAV/AIFF/FLAC) and the
// "Load from..." file player (decode + engine DAC playback lane, loop, play/stop) moved to
// generator/generator-pane.js (Java GeneratorPane); they reach the io decode/save helpers +
// WAV_TYPE through the injected `io` / WAV_TYPE deps.

// ----- Calibrate DAC (Java GeneratorPane.openDacCalibrationDialog) -----
// The DAC full-scale calibrate flow is the unified shell/calibration-dialog.js (constructed in
// the 'calibrationDialog' step); its bind() wires the generator #calibrateDac button.

// The scope "Save to..." / "Load signal..." flows moved to ScopeTabControl; the
// pane-side load orchestration (centre the view on the loaded signal + show the
// nav slider) is scopePane.onSignalFileLoaded.

// The file-mode trigger-control lock (setScopeTriggerControlsEnabled), the loaded-signal
// view orchestration (scopeOnSignalFileLoaded), the blinking loaded-file banner
// (setScopeFileBanner), the file-mode render + scroll-back (renderLoadedScope /
// refreshScopeFileMode / fileMaxBack / syncHorizScrollbar) and the horizontal nav-bar
// visibility (setScopeHScrollVisible) moved to scope/scope-pane.js (Java ScopePane); the
// tab-control reaches them through the scopePane it holds.

// The scope Presets (capture / apply / list / save / load / delete of the
// OscPreset field set) moved to ScopeTabControl; app.js seeds the preset list via
// scopeTabControl.refreshOscPresetList() in init.

/** Shows the shared Bootstrap confirm modal with the given title + message and
 *  resolves true on OK, false on Cancel / dismiss (Java Dialogs.confirm). One
 *  click handler is bound per show so a Cancel doesn't leak into the next call. */
function showConfirm(title, message) {
  return new Promise((resolve) => {
    $('#confirmTitle').text(title || t('common.ok'));
    $('#confirmMessage').text(message || '');
    const el = document.getElementById('confirmModal');
    let decided = false;
    const onOk = () => { decided = true; confirmModal.hide(); };
    const onHidden = () => {
      $('#confirmOk').off('click', onOk);
      el.removeEventListener('hidden.bs.modal', onHidden);
      resolve(decided);
    };
    $('#confirmOk').on('click', onOk);
    el.addEventListener('hidden.bs.modal', onHidden);
    confirmModal.show();
  });
}

// The one alert currently on screen, so a device that fires several statechange/onerror events in
// a row (an exclusive grab typically bursts them) raises exactly one modal, not a stack.
let alertShowing = false;
/** Shows the shared one-button alert modal (title + message + Close). No return value -
 *  informational only. Coalesces repeats while one is already open. */
function showAlert(title, message) {
  if (!alertModal) { console.error(title, message); return; }   // fired before the modals step wired up
  if (alertShowing) return;
  alertShowing = true;
  $('#alertTitle').text(title || t('common.ok'));
  $('#alertMessage').text(message || '');
  const el = document.getElementById('alertModal');
  const onHidden = () => { el.removeEventListener('hidden.bs.modal', onHidden); alertShowing = false; };
  el.addEventListener('hidden.bs.modal', onHidden);
  alertModal.show();
}

// Device-failure surface: the AudioContext lives in shared-capture.js (input) and
// generator-controller.js (output); both publish AUDIO_DEVICE_ERROR on a getUserMedia/open
// rejection or an unexpected 'interrupted'/'closed'/onerror while running. Turn it into a visible,
// actionable alert naming the failed direction (output = generator/freqresp playback; input =
// scope/FFT/freqresp capture) - the usual cause is the device being held exclusively by another app.
MessageBus.instance().subscribe(Events.AUDIO_DEVICE_ERROR, (p) => {
  // The publisher owns the wording: it knows WHICH operation failed and asked the backend that
  // owned the native error what it meant, so the alert shows the LOCALIZED reason it composed
  // (Java SharedCapture.openFailureText / GeneratorController.localize). Raw driver text never
  // reaches here - it went to the log at the failure site. A publisher that carries no message
  // (a device the app only knows went away) still gets the generic per-direction sentence.
  // Title AND body are the desktop's own keys now. The three web-only sentences this used
  // to fall back on each ended in "it appears to be in use by another application" - a guess
  // printed over whatever had really happened, so a device that was not found, or one that had
  // been unplugged, was reported as held by another program. deviceErrorText composes the true
  // reason instead, from the same table every publisher already classifies against.
  showAlert(t('audio.deviceError.title'), (p && p.message) ? p.message : deviceErrorText(p));
});

// ----- Screenshots (Java ScreenshotDialog + AbstractPane + ScreenshotOverlay) -----
// The whole capture pipeline lives in ui/screenshot.js (the generic clone + paint
// compositor, the shared watermark + caption overlay, the dialog); each PANE owns its
// prep + renderShot + dialog spec (scope-pane / fft-pane / freqresp-pane shotDialogPane),
// as the desktop's AbstractPane renders itself. Wired below in init's modals step.

// The scope ADC-calibrate handler (#scopeCalibrate) + its enable gate
// (syncCalibrateEnabled) moved to ScopeTabControl; the render loop drives the gate
// via scopeTabControl.syncCalibrateEnabled().

// The FFT Presets / Utility (screenshot + ADC calibrate) / Save / Load / Load-calibration
// handlers moved to fft/fft-tab-control.js (Java FftTabControl): presets recall re-seeds the
// main FFT controls via the injected applyPrefsToUi; the loaded .fft spectrum flows back via
// fftPane.setResult; the .frc load mutates the FFT controller's correctionStore, read by both
// the finalize stage's de-embed and the predistortion engine's calResponseAt.

// ============================ Frequency response ============================
// Takes the engine at construction -> built in init's audio-backend block (see `engine`).
let freqRespPane;

// ============================ Predistortion wizard ============================
let predistModal;

// Restart on the (predistortion-written) preferences - configureForRun's coherent / ∞-averaging
// settings are what the FFT session then sets up on.
async function restartPreservingConfig() {
  if (!engine.running) return;
  // Predistortion drives the GENERATOR and the FFT analysis (with the FLL) - and nothing
  // else. Bounce exactly those two so configureForRun's coherent / infinite-averaging
  // window takes effect; the engine's lifecycles are independent, and the controllers own
  // the running state so the FFT view keeps rendering.
  //
  // The SCOPE is not touched here, in either direction. The fused engine.stop() plus an
  // unconditional engine.scope.setRecording(true) used to run through this path, so opening
  // a predistortion run STARTED the scope on a bench where the operator had it stopped -
  // an extra consumer on the shared capture, and a lit Record LED nobody asked for.
  await engine.fft.setRecording(false);
  await engine.stopGenerator();
  await engine.startGenerator();
  await engine.fft.setRecording(true);
  $('#genPlay').addClass('playing').attr('title', t('generator.play.stop'));
  genPane.setOnAir(true);   // ...and re-arms the pane's ON-AIR tick (the lane-death consult)
  fftPane.syncFftLed();
}

// Takes the engine at construction -> built in init's audio-backend block (see `engine`).
let predistHost;





// The pane layout (initPaneLayout: the collapsible generator / scope / FFT panes, the
// draggable horizontal + vertical SASH splitters, and the pane-weight / collapse-state
// persistence) moved to shell/main-tab.js (Java MultifunctionalTab); app.js drives it via
// mainTab.initLayout() in init.

// Stop capture/generate + flush prefs when the tab is hidden/closed/reloaded, so a
// reload WHILE RUNNING can't orphan the AudioContexts + worklets (they'd keep
// generating/capturing and churning memory in the background). stop() is async but
// terminate()/disconnect() are synchronous, which is what matters on unload.
function teardown() { try { engine.stop(); } catch (_) {} prefs.flush(); }
window.addEventListener('pagehide', teardown);
window.addEventListener('beforeunload', teardown);

// ----- Chromium-engine gate -----
// The app depends on Chromium-only audio platform APIs (frame-accurate capture-rate probing via
// MediaStreamTrackProcessor, AudioWorklet module workers). On any other engine it cannot run, so
// it refuses to start with a clear warning rather than failing in confusing ways later.
function isChromium() {
  const brands = navigator.userAgentData && navigator.userAgentData.brands;
  if (Array.isArray(brands) && brands.length) return brands.some((b) => /Chromium/i.test(b.brand));
  return typeof MediaStreamTrackProcessor !== 'undefined';   // Chromium-only API the app requires
}
function showUnsupportedBrowserOverlay() {
  const tr = (key, fb) => { try { const v = t(key); return (v && v !== key) ? v : fb; } catch (_) { return fb; } };
  const box = document.createElement('div'); box.className = 'ub-box';
  const h = document.createElement('div'); h.className = 'ub-title';
  h.textContent = '⚠ ' + tr('web.browser.unsupported.title', 'Unsupported browser');
  const p = document.createElement('p');
  p.textContent = tr('web.browser.unsupported.message',
    'Phonalyser.web requires a Chromium-based browser - Google Chrome, Microsoft Edge, Opera or Brave. '
    + 'It relies on Chromium-only audio APIs (frame-accurate capture-rate probing, AudioWorklet) that this browser '
    + 'does not provide, so it cannot run here. Please open this page in Chrome or Edge.');
  box.appendChild(h); box.appendChild(p);
  const el = document.createElement('div'); el.id = 'unsupportedBrowser'; el.setAttribute('role', 'alertdialog');
  el.appendChild(box); document.body.appendChild(el);
}

/**
 * Fills the Tools ▸ Preferences / Devices submenus and opens the JSON editor from them.
 *
 * BUILT WHEN THE MENU OPENS, not at load. "Current" is always there, but "Corrupt" exists
 * only while a quarantined copy does - and a document can be quarantined at any time, by this
 * very editor writing something the reader then refuses on the next start. A list built once
 * at load would be wrong for the rest of the session.
 *
 * ONE item, not a numbered list: store-quarantine.js writes a fixed `<key>.corrupt` and
 * overwrites it, so there is never more than one.
 *
 * @param {Array<Object>} ports the config ports (store/config-port.js)
 * @param {Object} dialog the JsonConfigDialog to open
 */
function buildConfigMenus(ports, dialog) {
  const $ = window.jQuery;
  const hosts = {
    preferences: '#menuCfgPreferencesItems',
    devices: '#menuCfgDevicesItems',
  };
  const rebuild = () => {
    for (const port of ports) {
      const host = $(hosts[port.id]);
      if (!host.length) continue;
      host.empty();
      const item = (labelText, mode) => $('<li>').append(
        $('<button type="button" class="dropdown-item">').text(labelText)
          .on('click', () => dialog.open(port, mode)),
      );
      host.append(item(t('web.menu.tools.config.current'), 'live'));
      if (port.corruptEntry() != null) host.append(item(t('web.menu.tools.config.corrupt'), 'corrupt'));
    }
  };
  // The Tools dropdown is the one holding the Preferences item; it carries no id of its own,
  // so it is reached through that item rather than by adding one.
  const tools = document.getElementById('menuPrefs');
  const dropdown = tools ? tools.closest('.dropdown') : null;
  if (dropdown) dropdown.addEventListener('show.bs.dropdown', rebuild);
  rebuild();   // so the submenus are never empty, even before the menu is first opened
}

// ----- bootstrap: i18n must resolve before first paint of the chrome -----
async function init() {
  // Each setup step is ISOLATED + logged: one failing step can no longer abort the
  // rest (which previously left the device scan un-run -> "nothing works", silently).
  // The failing step's name + error surface in the console so it's pinpointable.
  const step = (name, fn) => { try { return fn(); } catch (e) { console.error('init step failed:', name, e); } };
  await initBase();
  try { await setLocale(prefs.uiLanguage.get()); } catch (e) { console.error('init step failed: setLocale', e); }
  // Branded launch splash (Java StartupSplash): the static overlay div is already
  // covering the viewport with the backdrop colour from first paint; draw the full
  // artwork now that the locale is set (so the tagline is localized). Version comes
  // from the SAME source the About dialog reads (the .menu-ver span), '· web' stripped.
  const splash = new StartupSplash({
    version: () => ($('.menu-ver').text() || '').replace(/·.*$/, '').trim(),
    t,
  });
  splash.show();
  // Refuse to run on a non-Chromium engine: show the warning and stop before any device/UI setup.
  if (!isChromium()) { splash.dismiss(); showUnsupportedBrowserOverlay(); return; }
  // Fetch + parse the shipped devices.yaml (the Java single-source catalog, copied verbatim by
  // the build) BEFORE building the store, so its constructor seeds / merges from it. A failed load
  // (null) -> no seed: the store still boots with the user's persisted localStorage cards. The two
  // consumers below (calibrationDialog, PreferencesDialog) run after this await, so they get the
  // constructed store.
  // Asked at the SAME time as the catalogue rather than after it: the probe is one bounded HTTP
  // round trip (600 ms worst case) and the two have nothing to do with each other, so overlapping
  // them costs the slower one alone. Awaited below, before anything reads `servingServer`.
  const servingProbe = probeServingOrigin();
  const deviceCatalog = await loadDeviceCatalog();
  servingServer = await servingProbe;
  deviceStore = new DeviceProfileStore(prefs, deviceCatalog ? { catalog: deviceCatalog } : {});
  // ----- audio backend + per-backend device managers (Java sound.AudioBackend) -----
  // Deliberately NOT in a step(): a failing engine breaks everything downstream, so it must
  // surface as init's own rejection rather than be swallowed as one skippable step.
  //
  // ONE finder instance, handed to the manager (which enumerates and opens with it) AND to the
  // engine as the GRANT seam: only a scan that carries the Scan click's user activation may raise
  // the WebUSB chooser, and it must be raised on the same object the later open() enumerates
  // through (qa40x-device-finder.js: scan() prompts, list()/open() never do).
  const qa40xFinder = new Qa40xDeviceFinder();
  // The one bench seam: every request this build sends to a Phonalyser server's own backend goes
  // through it (spec 4.6's QA40x passthrough below, spec 4.3's card binding in the modals step).
  // A LIVE getter for the same reason the engine gets one: netManager is built later, in the
  // modals step, and each write asks per event.
  const benchSeam = {
    call: (backend, request, fields) => (netManager
      ? netManager.call(backend, request, fields) : Promise.resolve(null)),
    callLocked: (backend, request, fields) => (netManager
      ? netManager.callLocked(backend, request, fields) : Promise.resolve(null)),
    // The remote analyzer's device NAME - the synced card's key. First input, else first output:
    // the catalogue names the same analyzer both ways (Java Qa40xSettingsUi.remoteDeviceName).
    deviceName: () => {
      if (!netManager) return null;
      const device = netManager.listInputDevices()[0] || netManager.listOutputDevices()[0];
      return device ? device.name : null;
    },
  };
  qa40xManager = new Qa40xDeviceManager({
    prefs, deviceStore, finder: qa40xFinder,
    // Java constructs the SWT settings dialog in place and passes it the parent Shell; the web
    // dialog is a shell concern, so it arrives as this opener. The `parent` Java hands down is the
    // Preferences shell - here that is the #prefsModal ELEMENT, and mounting a Bootstrap modal
    // inside another modal's element nests the two (qa40x/qa40x-settings-dialog.js), so the settings
    // dialog mounts on <body> and Bootstrap stacks it over Preferences.
    openSettingsDialog: (_parent, info, i2sEnabled) => new Qa40xSettingsDialog(null, info).open(i2sEnabled),
    // The bench seam for a QA40x that hangs on a SERVER (spec 4.6), forwarded to the range
    // controller this constructor arms.
    bench: benchSeam,
  });
  // Constructing the manager is also what ARMS the QA40x bus wiring (its own constructor registers
  // custom.qa40x with the prefs store so it loads AND saves, holds the range controller and calls
  // Qa40xRateConstraint.instance()) - all live before the Preferences dialog can move a rate combo
  // or commit a range. The engine hooks pagehide/beforeunload to park the analyzer, which is why it
  // must receive the manager at CONSTRUCTION (Java AudioBackend.shutdown() on the exit path).
  // netManager is constructed in the modals step (it needs the Preferences store loaded), so the
  // engine is handed a LIVE getter rather than the instance: the dispatch asks per open, exactly
  // as it asks activeBackend() per open.
  // The forward the audio layer holds instead of the manager (net-manager-facade.js owns the
  // member list, the absent-session answers and the arity that must match the manager's).
  // The loopback's depths come from the LOOPBACK block of the per-backend preferences, read at
  // each session boundary through these two suppliers: the operator can change a depth without
  // changing backend, and the engine keeps its capture source across sessions, so a value read
  // once here would go on encoding at the resolution that was selected when the page loaded.
  // Read from the LOOPBACK block by name rather than from current(): a session may well be opened
  // while the Preferences dialog is showing another backend's block.
  loopbackManager = new LoopbackDeviceManager({
    inputDepthOf: () => prefs.prefsFor(LOOPBACK_BACKEND).inputBitDepth,
    outputDepthOf: () => prefs.prefsFor(LOOPBACK_BACKEND).outputBitDepth,
  });
  // The backend (Java sound.AudioBackend) is composed FIRST, with the managers the shell owns;
  // the engine (Java gui.UIEngines) is composed over it and reports its status through it.
  const backend = new AudioBackend({ prefs, qa40xManager, qa40xFinder, loopbackManager,
    netManager: netManagerFacade(() => netManager), status: (text) => engine._status(text) });
  engine = new AudioEngine({ prefs, backend });
  fftViewCorrection = new FftViewCorrection(prefs, engine.fft.correctionStore);
  fftView = new FftView(document.getElementById('spec'), { prefs, genActive: () => engine.generator.running, correction: fftViewCorrection });
  bindEngineCallbacks();
  freqRespPane = new FreqRespPane(engine, prefs, { saveFile, openFile, bytesToText, showAlert });
  predistHost = new PredistortionHost(engine, prefs, {
    getResult: () => fftPane.getResult(),
    restart: restartPreservingConfig,
    // Read the FFT store LIVE - the predistortion engine reads correctionEntries on demand, so a
    // captured array snapshot would go stale as the calibration rows change.
    get correctionEntries() { return engine.fft.correctionStore.getEntries(); },
  });
  step('initSelects', initSelects);
  // Generator pane (Java GeneratorPane): the signal-form combo + freq/amp/duty/dual-tone/
  // sweep/dither/snap/.dpd controls + Play/ON-AIR + Save-to + file player. Constructed before
  // applyPrefsToUi so its seedGeneratorControls() runs in the init seed; reaches the SHELL
  // state (the busy guard + engine.generator.running) + the FFT align combo through the injected
  // closures. Its step fields are built in the genStepFields step below, once i18n resolved.
  step('genPane', () => {
    genPane = new GeneratorPane(engine, prefs, {
      io: { pickSaveTarget, writeToTarget, saveScopeCapture, readWav, readAiff, decodeFlac },
      WAV_TYPE, formLabel, formIcon, outRate,
      isGenRunning: () => engine.generator.running,
      isBusy: () => busy, setBusy: (v) => { busy = v; },
      syncFftAlign: () => { if (fftTabControl) fftTabControl.syncAlign(); },
      // The shell's one alert surface - how a refused file reaches the operator (Java shows its
      // Cannot-play-file MessageBox); the same seam the tune-notch wizard and the card section use.
      showAlert,
    });
  });
  step('applyPrefsToUi', applyPrefsToUi);
  step('bindPrefs', bindPrefs);
  step('genPaneBind', () => genPane.bind());   // generator handlers (was the generator part of bindPrefs)
  step('buildFormCombo', () => genPane.buildFormCombo());
  step('applyI18n', applyI18n);
  step('refreshFftLabel', () => freqRespPane.refreshFftLabel());   // localized after the bundle is loaded
  step('freqRespSeed', () => freqRespPane.seedTabs());   // builds the cal rows / preset list / RIAA enable (t() needs the bundle)
  step('genStepFields', () => genPane.buildStepFields());   // after applyI18n so unit suffixes resolve
  // Unified ADC/DAC calibration dialog (Java CalibrationDialog): built here, after applyI18n (its
  // two per-channel fields resolve their unit suffixes), so the scope / FFT strips can call
  // openAdc and its bind() can wire the generator #calibrateDac button. The current-device LABEL
  // comes from the prefs dialog's device <select> (recognition patterns match the label, not the
  // deviceId value).
  step('calibrationDialog', () => {
    calibrationDialog = new CalibrationDialog(engine, prefs, deviceStore, {
      inputLabel: inputDeviceLabel,
      outputLabel: () => (prefsDialog ? prefsDialog.outputDeviceLabel() : $('#outSel option:selected').text()),
      // Null for every LOCAL selection, which is what keeps a local calibrate writing where it
      // always did. Asked per calibrate, not held: the seam is built with the Preferences dialog
      // (below), and the selected backend changes under this dialog anyway.
      bench: () => (benchCalibration && benchCalibration.remote() != null ? benchCalibration : null),
      // A DAC calibration written to a BENCH reaches the tone only at the lane's next open - the
      // server converts volts with the card it read there (see the dialog's _applyToLiveTone) - so
      // the dialog re-opens it. The generator pane owns the restart (busy guard) and it is a
      // no-op while nothing is playing.
      restartGenerator: () => genPane.restartGenerator(),
      // The shell's one alert surface - the same one the card section's refused copy uses.
      showAlert,
    }).bind();
  });
  // Oscilloscope PANE (Java ScopePane): the trace canvas wiring, the two nav scrollbars,
  // the Record LED, the measurement table + pop-out, the file-mode load/scroll, and the
  // scope branch of the rAF loop (render()). Built BEFORE the scope settings strip, which
  // holds this pane. Reaches
  // the shell state (the busy guard + engine.scope.recording) + the latest scope frame
  // through the injected closures; the scope V/T/hyst NumericStepFields (the settings strip's,
  // built after this pane) via getField; the ScopeView + tileChips injected too. bind() wires the
  // Record LED + measurement buttons + resize observer.
  step('scopePane', () => {
    scopePane = new ScopePane(engine, prefs, {
      view: scopeView, getField: (id) => (scopeTabControl ? scopeTabControl.field(id) : undefined), tileChips,
      isScopeRec: () => engine.scope.recording,
      isBusy: () => busy, setBusy: (v) => { busy = v; },
      getLatestScope: () => latestScope,
      syncCalibrateGate: () => { if (scopeTabControl) scopeTabControl.syncCalibrateEnabled(); },
    }).bind();
  });
  // Oscilloscope settings strip (Java ScopeTabControl): its step fields + handlers + presets +
  // save/load + ADC-calibrate. Holds the scope PANE (canvas / scrollbars / record / render loop)
  // built above.
  step('scopeTabControl', () => {
    scopeTabControl = new ScopeTabControl(engine, prefs, {
      pane: scopePane, view: scopeView,
      io: { pickSaveTarget, writeToTarget, encodeFlac, saveScopeCapture, saveStreaming,
        findFullPeriodWindow, formatForName, readWav, readAiff, decodeFlac },
      WAV_TYPE, latestScope: () => latestScope, showConfirm,
      setStatus: (m) => $('#status').text(m),
      calibrationDialog: () => calibrationDialog,
      // The calibrate gate drops its 25 % accuracy threshold for a selection that has no real
      // calibration to measure against (Java ScopePane's CalibrationStore.isUncalibrated call).
      // Late-bound like the dialog: the seam is built with the Preferences dialog, further down.
      inputUncalibrated: () => (benchCalibration
        ? benchCalibration.uncalibrated(true, inputDeviceLabel()) : false),
    }).bind();
  });
  step('seedScopeControls', () => scopeTabControl.seedScopeControls());
  step('refreshOscPresetList', () => scopeTabControl.refreshOscPresetList());
  step('syncTriggerStart', () => scopePane.syncTriggerStart());
  // FFT PANE (Java FftPane): the spectrum-view wiring, the Record LED, the readout / THD /
  // IMD render, and the FFT branch of the rAF loop (render()). Built BEFORE the FFT settings
  // strip, which holds this pane for its result state. Reaches the shell state (the busy
  // guard + engine.fft.recording) through the injected closures; the FftView + the view
  // correction + tileChips injected too. bind() wires the Record LED.
  step('fftPane', () => {
    fftPane = new FftPane(engine, prefs, {
      view: fftView, viewCorrection: fftViewCorrection, tileChips,
      isFftRec: () => engine.fft.recording,
      isBusy: () => busy, setBusy: (v) => { busy = v; },
    }).bind();
  });
  // MAIN TAB (Java MultifunctionalTab): the rAF render-frame driver + the 3-pane collapse/sash
  // layout. Built after BOTH panes exist; start() kicks the requestAnimationFrame loop (which
  // drives scopePane.render() + fftPane.render() each frame, each pane self-gated on its OWN
  // record state), and initLayout() wires the collapsible panes + draggable sashes + the
  // pane-weight / collapse-state persistence (it owns NO lifecycle flags - those stay in app.js).
  step('mainTab', () => {
    mainTab = new MainTab({ scopePane, fftPane, prefs });
    // The FFT's per-result fold runs BETWEEN animation frames, so the render loop cannot see it
    // by itself: the controller reports each fold's duration into the loop's load meter, which
    // is what the scope paces against. Config handed INTO the controller - it learns a function,
    // not a view (Java's controllers never reach up either).
    engine.fft.setBusyReporter((ms) => mainTab.reportBusy(ms));
    mainTab.start();
    mainTab.initLayout();
  });
  // FFT settings strip (Java FftTabControl): its step fields + handlers + presets + save/load +
  // calibration + screenshot/ADC-calibrate. Holds the FFT PANE built above for its result state;
  // applyPrefsToUi is the one shell helper it still reaches by name.
  step('fftTabControl', () => {
    fftTabControl = new FftTabControl(engine, prefs, {
      pane: fftPane, applyPrefsToUi,
      fftView, fftViewCorrection, store: engine.fft.correctionStore,
      io: { saveFile, openFile, bytesToText, loadFrc, saveSpectrum, loadSpectrum, FFT_TYPE, FRC_TYPE },
      restartFft, showConfirm, setStatus: (m) => $('#status').text(m), tileChips,
      calibrationDialog: () => calibrationDialog,
    }).bind();
  });
  step('seedFftControls', () => fftTabControl.seedFftControls());
  step('refreshFftPresetList', () => fftTabControl.refreshFftPresetList());
  // Shared calibration store cleanup (issue 2.3): once BOTH panes have finished restoring their
  // calibration rows, sweep any cal.<hash> record referenced by neither pane. Both panes share
  // one record per hash, so the union of their referenced hashes is exactly what must survive.
  step('pruneCals', () => {
    Promise.all([freqRespPane._calRestore, fftTabControl._calRestore])
      .then(() => pruneCals(new Set([...freqRespPane.getCalHashes(), ...fftTabControl.getCalHashes()])))
      .catch((e) => console.error('pruneCals failed', e));
  });
  step('fftSeed', () => { genPane.refreshFreqLabel(); genPane.syncFormUI(); });   // generator label + form-gated UI follow the seeded FFT controls
  step('modals', () => {
    prefsModal = new window.bootstrap.Modal(document.getElementById('prefsModal'));
    // The dialog is resizable (css .modal-content `resize: both`, 640×480 as its minimum).
    // The drag writes an inline width/height on the content, which would otherwise outlive the
    // dialog - so it is dropped on hide and the next open re-packs at the minimum, exactly as the
    // predistortion wizard does below and as Java does by opening a freshly packed shell.
    (() => {
      const modal = document.getElementById('prefsModal');
      const content = modal && modal.querySelector('.modal-content');
      if (!content) return;
      modal.addEventListener('hidden.bs.modal', () => { content.style.width = ''; content.style.height = ''; });
    })();
    aboutModal = new window.bootstrap.Modal(document.getElementById('aboutModal'));
    predistModal = new window.bootstrap.Modal(document.getElementById('predistModal'), { backdrop: 'static', keyboard: false });   // a real modal - no click-away / Esc dismiss (a running tuning must not be lost to a stray click)
    // The Java wizard Shell is user-movable AND user-resizable (SWT.DIALOG_TRIM |
    // SWT.RESIZE, PredistortionWizardDialog:138) and packs to its content (:162) - a modest
    // ~470px-wide box, not a full-screen dialog. Bootstrap modals give us neither, so we add
    // both by hand:
    //   - MOVE:   header-drag translates the .modal-dialog (composes with Bootstrap centering).
    //   - RESIZE: the CSS `resize: both` affordance lives on .modal-CONTENT, not .modal-dialog -
    //             Bootstrap sets pointer-events:none on .modal-dialog and hands them to
    //             .modal-content, so a resize handle on the dialog can never be grabbed. We seed
    //             a sensible default size on the CONTENT on open (Java pack() equivalent), which
    //             both keeps the window modest AND gives the corner handle a concrete box to drag.
    // Both the drag offset and the seeded size are cleared on close so the next open re-centers
    // and re-packs to the default (Java opens a fresh packed+centered Shell each time).
    (() => {
      const modal = document.getElementById('predistModal');
      const dlg = modal && modal.querySelector('.modal-dialog');
      const content = modal && modal.querySelector('.modal-content');
      const bar = modal && modal.querySelector('.modal-header');
      if (!dlg || !content || !bar) return;
      let dx = 0, dy = 0, dragging = false, sx = 0, sy = 0;
      const apply = () => { dlg.style.transform = `translate(${dx}px, ${dy}px)`; };
      bar.addEventListener('mousedown', (e) => { if (e.target.closest('button')) return; dragging = true; sx = e.clientX - dx; sy = e.clientY - dy; e.preventDefault(); });
      document.addEventListener('mousemove', (e) => { if (!dragging) return; dx = e.clientX - sx; dy = e.clientY - sy; apply(); });
      document.addEventListener('mouseup', () => { dragging = false; });
      // Seed the modest default size on open so the window isn't huge and the resize corner
      // has a defined box to drag (the CSS phase owns the numeric default via .dpd-resizable).
      modal.addEventListener('show.bs.modal', () => { content.classList.add('dpd-resizable'); });
      modal.addEventListener('hidden.bs.modal', () => {
        dx = 0; dy = 0; dlg.style.transform = '';
        content.style.width = ''; content.style.height = '';   // drop any user resize -> re-pack on reopen
      });
    })();
    shotModal = new window.bootstrap.Modal(document.getElementById('shotModal'));
    confirmModal = new window.bootstrap.Modal(document.getElementById('confirmModal'));
    alertModal = new window.bootstrap.Modal(document.getElementById('alertModal'));
    // Wire the shared screenshot dialog: pane-agnostic (Java ScreenshotDialog), and each PANE
    // owns its render / native-size / persisted-size spec (Java AbstractPane renders itself) -
    // the dialog just takes the three specs. Constructed here (not at module top) so the
    // shotModal instance above is already live.
    const shotDialog = new ScreenshotDialog({
      modal: shotModal, ...scopePane.shotDialogPane(),
      saveFile, status: (m) => $('#status').text(m),
    });
    shotDialog.bind();
    shotDialog.addPane(fftPane.shotDialogPane());
    shotDialog.addPane(freqRespPane.shotDialogPane());
    // Predistortion wizard (Java PredistortionWizardDialog): the live UI half. The host wiring +
    // generator restart stay in app.js (generator-pane concerns) and are injected.
    new PredistortionWizard(engine, prefs, predistHost, {
      modal: predistModal, getResult: () => fftPane.getResult(), saveFile,
      onApply: (applyForm, text, name) => {
        // The .dpd slot is written BEFORE the form: the form change is what a live generator
        // reacts to, and it must find the correction already in place - the other order lets a
        // compensated form come up for an instant with the previous (or an empty) slot.
        // The slot takes the correction TEXT (the web has no OS path to re-open) and the NAME
        // pref takes the file name the row displays. Both writes are preferences, so the
        // generator pane's listeners carry them to the running DDS - the one hot-load path the
        // browse and clear buttons use (Java GeneratorController listens on the same two
        // properties and calls lane.dpdChanged).
        prefs.setGenDpd(applyForm, text);
        prefs.setGenDpdName(applyForm, name);
        prefs.genSignalForm.set(applyForm);
        prefs.save();
        $('#signalForm').val(applyForm); genPane.syncFormUI();
      },
    }).bind();
    // Tune-notch wizard (Java MainWindow Tools -> Tune notch... -> TuneNotchWizardDialog).
    // Static backdrop like the predistortion wizard: a live streaming session must not be
    // torn down by a stray click-away. The wizard is autonomous - it publishes
    // FREQRESP_MEASUREMENT_STARTED and drives its OWN generator + capture, so it needs no
    // generator-running gate (see tune-notch-wizard.js module header).
    const tuneNotchModal = new window.bootstrap.Modal(document.getElementById('tuneNotchModal'), { backdrop: 'static', keyboard: false });
    new TuneNotchWizard(engine, prefs, { modal: tuneNotchModal, showAlert }).bind();
    // Card create / edit dialog (Java CardEditorDialog) - opened from the Preferences Audio tab's
    // card combo / edit button. Delegates every decision to store/card-editor-logic.js.
    const cardEditorDialog = new CardEditorDialog({ showConfirm });
    // The net backend (doc/NET-PROTOCOL.md): a Phonalyser server's backends offered beside the
    // local ones. The manager owns the session + the remote catalogue; the server-list modal
    // (Java NetServerListDialog) drives it through the narrow bench seam. The remembered
    // servers are a SubPreferences block, registered like the QA40x's - registration replays
    // whatever the stored document already had into it.
    netPreferences = new NetPreferences();
    prefs.registerCustomPreferences(netPreferences);
    netManager = new NetDeviceManager({
      preferences: netPreferences,
      // The dial itself, injected: one session per bench, its own keepalive ticker, and the
      // client name a DEVICE_LOCKED shows on somebody else's screen (spec 4.1).
      openConnection: async (server) => {
        const connection = new NetConnection({
          url: wsUrlOf(server.host, server.port),
          // The version the menu shows is the one the build injected - the same single source
          // of truth the About box reads, so a bench log never names a version nothing built.
          clientName: t('app.title'),
          clientApp: `Phonalyser.web/${($('.menu-ver').text() || '').replace(/·.*$/, '').trim()}`,
          ticker: new IntervalTicker(),
        });
        await connection.open(CONNECT_TIMEOUT_MS);
        return connection;
      },
    });
    // Opened from INSIDE Preferences, so it has to be stacked explicitly: Bootstrap's backdrop
    // sits below every modal's own layer and cannot cover the dialog underneath, which is what
    // made this one read as "not modal" with the focus left behind on Preferences.
    // The JSON config editor (shell/json-config-dialog.js). The ONE place the two live stores
    // are handed to it is createConfigPorts - the dialog and the menu below never see either
    // store, so there is exactly ONE connection between the editor and them. The live objects are
    // passed IN: `prefs` is the singleton this module already holds and `deviceStore` is the
    // instance built above and injected everywhere else, so the editor edits the documents the
    // app is actually running on rather than a second copy of them.
    const jsonConfigEl = document.getElementById('jsonConfigModal');
    const jsonConfigDialog = new JsonConfigDialog({
      modal: new window.bootstrap.Modal(jsonConfigEl),
    }).bind();
    const configPorts = createConfigPorts({ prefs, deviceStore });
    buildConfigMenus(configPorts, jsonConfigDialog);

    const netServersEl = document.getElementById('netServersModal');
    stackOverOpenModals(netServersEl);
    const netServersModal = new window.bootstrap.Modal(netServersEl);
    // The server list is the APP's, not the dialog's, and the servers it already knows are
    // asked ONCE, here, as the app loads - known servers are probed after load and no sooner.
    // No loop, no timer: the only recurring traffic this app makes is the session's own
    // keepalive, to the one server it is connected to. The answer sticks (the model paints what
    // the last completed round found), so the window opens on real state instead of on grey.
    //
    // The servers are the COMMITTED ones, and they are there by now: registering the block
    // above replays the stored document into it (Preferences.registerCustomPreferences ->
    // NetPreferences.fromMap, which fills the live map and only then seeds the edit copy).
    // Unawaited - the LAN is not on the boot path - and a failure costs the round, not the app.
    const netServers = new NetServerList(netPreferences);
    netServers.useProber({ prober: new ServerProber(), now: () => Date.now() });
    netServers.pollNow().catch((e) => console.warn('net discovery: the load-time round failed', e));
    // ...and again whenever Preferences opens. That is where a bench is
    // chosen, so it is where the liveness has to be current: between the load-time round and the
    // moment the operator goes looking for a server, a bench can have been switched on or off,
    // and only the Servers window asked again. Still no timer - one round per opening, the
    // model's own guard drops it if a round is already in flight, and each probe carries the
    // prober's 600 ms budget. Unawaited: the dialog must open at once, and the rows it feeds are
    // painted from the model whenever an answer lands.
    const prefsModalEl = document.getElementById('prefsModal');
    if (prefsModalEl) {
      prefsModalEl.addEventListener('show.bs.modal', () => {
        netServers.pollNow().catch(
          (e) => console.warn('net discovery: the preferences-open round failed', e));
      });
    }
    new NetServerListDialog({ bench: netManager, modal: netServersModal, servers: netServers }).bind();
    // On a bench, the card a remote device uses is the operator's choice, made from the
    // SERVER's cards and stored there (spec 4.3 cards.list / device.setCard), mirrored locally
    // under the server's id. Java builds this per dialog session on the working copy; the web store
    // is live, so one instance lives here and the dialog clears its staged picks on every open.
    const benchCards = new BenchCards({ bench: benchSeam, store: deviceStore });
    // Where the ADC/DAC calibrate dialog's result is WRITTEN while a server's backend is selected
    // (Java gui/sound/CalibrationStore), and the one place the bench selection is resolved. The
    // dialog asks for this seam per calibrate and writes locally whenever it answers null. The net
    // manager is asked per call: it is built above in this same step and a session comes and goes.
    benchCalibration = new CalibrationStore(prefs, deviceStore, benchCards, () => netManager);
    // Where the card combo's entries come from for a REMOTE selection, and where a pick is staged /
    // sent / read back (the local store answers for every local one). The once-per-RUN copy-offer
    // register is held for the app's lifetime, not the dialog's, and never persisted.
    const cardSource = new BenchCardSource(benchCalibration, benchCards, new CalibrationCopyOffers());
    // Preferences dialog (Java PreferencesDialog): staged audio/L&F/Osc/FFT/FR prefs, commit on OK.
    prefsDialog = new PreferencesDialog(engine, prefs, {
      modal: prefsModal, RATES, inRate, outRate,
      isBusy: () => busy, setBusy: (v) => { busy = v; }, fftView, deviceStore,
      cardEditorDialog, showConfirm, showAlert,
      // Java AudioBackend.instance().manager(type): the per-backend manager the dialog asks for the
      // Settings button (hasCustomPreferences / openCustomPreferences) and - for a backend that
      // enumerates its own formats - the sample-rate list. Web Audio has no manager, so it answers
      // null and that backend keeps the native-rate probe.
      //
      // A net: value answers the net manager, because THAT is the manager of that backend
      // INSTANCE: the bench's catalogue is where its devices' formats live, and asking it is
      // what makes the dialog offer the rates and sample widths the server declared instead of
      // the web's static list and a hidden depth row. Settings are a
      // different question and are resolved by TYPE - see PreferencesDialog.settingsManager.
      backendManager: (name) => {
        if (name === QA40X_BACKEND) return qa40xManager;
        // The loopback enumerates its OWN formats - the full rate ladder at four sample widths -
        // so the dialog must reach its manager, or the rate combo would fall back to the static
        // Web Audio list and the depth rows would stay hidden on the one backend whose whole
        // subject is the sample width.
        if (name === LOOPBACK_BACKEND) return loopbackManager;
        return (netManager && netManager.isRemoteBackend(name)) ? netManager : null;
      },
      // The connected server's backends, read synchronously at each combo build from the
      // manager's cache (it re-asks on connect and on ev.devices.changed).
      netBackends: () => (netManager ? netManager.getRemoteBackendEntries() : []),
      // Where the card combo's entries come from for a REMOTE selection, and where a pick is
      // staged / sent / read back (the local store answers for every local one).
      cardSource,
      // Whether this page came from a Phonalyser server - the combo offers no LOCAL backend on a
      // page that has neither getUserMedia nor WebUSB to open one with.
      servedByServer: () => servingServer != null,
    }).bind();
    prefsDialog.applyLookAndFeel();   // main-tab orientation + small icons + UI font from the saved prefs
  });
  step('freqResp', () => freqRespPane.plot());
  // Auto-enumerate audio devices on load (no Preferences dialog needed) - and dismiss the
  // startup splash once that scan settles (SAFETY: startup-splash also self-dismisses on a
  // 20 s timeout, so a hung permission prompt can never brick the app behind the overlay).
  // One of the TWO places that enumerate at all (the other is the Scan click); this one carries no
  // user gesture, so the QA40x lists only already-granted analyzers and raises no WebUSB chooser.
  // fromUserGesture stays FALSE: there is no user activation at load, so handing the QA40x path its
  // granter would call requestDevice() without one - it throws, scan() aborts in its catch, and the
  // device combos are never filled (they then fall back to the persisted-id stub, which for Web
  // Audio renders as a raw deviceId hash).
  // A PAGE SERVED BY A SERVER DIALS THAT SERVER FIRST - whichever bundle it is. The server IS the
  // address: window.location carries it, and no operator entry can add anything to that (spec
  // §"GET /"). Ordered BEFORE the scan on purpose: the scan enumerates the SELECTED backend, and
  // until the session is up there is no backend to enumerate. The server-list modal stays
  // available for the peer table, it is simply not needed.
  if (servingServer != null) await autoConnectToServingServer();
  const scanPromise = prefsDialog.scan();
  splash.dismissOnScan(scanPromise);
  // Tip of the day at startup (Java MainWindow.open: shown once the window is up when
  // Preferences.isShowTipsAtStartup()). Web equivalent boot moment: after the branded splash
  // dismisses - the scan settling is what dismisses it - so the popup never overlaps the splash.
  if (prefs.showTipsAtStartup.get()) {
    const openTip = () => setTimeout(() => tipDialog.open(), 300);
    Promise.resolve(scanPromise).then(openTip, openTip);
  }
}
/**
 * Does the page's OWN ORIGIN answer as a Phonalyser server? One `GET /info` (spec §3) through the
 * prober the server-list dialog already probes typed addresses with - same endpoint, same 600 ms
 * budget, same "anything that is not a Phonalyser server is null" rule, so there is no second
 * definition of what a server is.
 *
 * It fails FAST and silently everywhere it should: on GitHub Pages `/info` is a 404, on file://
 * fetch throws, on a dev server the JSON does not carry a serverId - all null, all inside the
 * prober's own budget, none of them an error the operator is told about (this is a question, not
 * an attempt). The probe is plain http:// like every other one here, so an https-served page
 * answers null too - which is the honest answer for the one route this app supports being
 * served over (spec §3's mixed-content note).
 *
 * @returns {Promise<?Object>} the server's identity, or null when this page came from elsewhere
 */
async function probeServingOrigin() {
  if (typeof location === 'undefined' || !location.hostname) return null;
  if (typeof fetch !== 'function') return null;
  // The page's OWN address, scheme and base path - `https://host/app/` asks
  // `https://host/app/info` (server-prober.probeOrigin). Not host+port with a hardcoded
  // http://, which asked a TLS-fronted bench at http://host:443/info and never found it.
  const base = typeof document !== 'undefined' && document.baseURI ? document.baseURI : location.href;
  const info = await new ServerProber().probeOrigin(base);
  if (info != null) console.info(`net client: this page was served by '${info.name}'`);
  return info;
}

/**
 * Connects to the server that served this page. A CALLER of the net machinery and nothing more:
 * the same NetDeviceManager.connect the server-list modal drives, so the session, the
 * remembered-server write, the backend-entry refresh and the localized failure text are all the
 * paths the operator would otherwise have taken by hand - and the server-list dialog therefore
 * shows this server as the connected one, with its own name.
 *
 * IDENTITY comes from the probe (`GET /info` already told us the serverId and the name, so the
 * remembered entry is keyed correctly from the first millisecond rather than by a placeholder).
 * The ADDRESS does not: it is window.location's, because the port this page demonstrably came
 * from is the port that works - a server behind a proxy reports its internal one, and this is
 * the one case where the address is not a matter of belief.
 *
 * A failure is reported exactly as a manual connect's is (the localized sentence the manager
 * composed, including spec §3's mixed-content explanation) and leaves the app running: the
 * server-list modal is the retry path.
 */
async function autoConnectToServingServer() {
  if (netManager == null || servingServer == null) return;
  const host = location.hostname;
  const port = Number(location.port) || (location.protocol === 'https:' ? 443 : 80);
  const failure = await netManager.connect(
    makeServerEntry(servingServer.serverId, servingServer.name || host, host, port, false));
  if (failure == null) {
    console.info(`net client: connected to the serving server at ${host}:${port}`);
    return;
  }
  console.warn(`net client: the serving server at ${host}:${port} did not connect - ${failure}`);
  $('#status').text(failure);
}

init().catch(e => console.error('init failed', e));
 