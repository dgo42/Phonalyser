/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// App entry: wires the native-styled Bootstrap/jQuery chrome to the live FFT
// engine and renders the scope + spectrum Canvas views. First runnable slice;
// the remaining blueprint modules layer on around this engine.

import { AudioEngine } from '../audio/backend.js';
import { deviceErrorText } from '../audio/device-failure-reason.js';
import { Qa40xDeviceFinder } from '../qa40x/qa40x-device-finder.js';
import { Qa40xDeviceManager } from '../qa40x/qa40x-device-manager.js';
import { QA40X_BACKEND } from '../qa40x/qa40x-rate-constraint.js';
import { LoopbackDeviceManager } from '../loopback/loopback-device-manager.js';
import { LOOPBACK_BACKEND } from '../loopback/loopback-device-ref.js';
import { FftViewCorrection } from '../fft/fft-view-correction.js';
import { CorrectionStore } from '../common/correction-store.js';
import { CalibrationDialog } from './calibration-dialog.js';
import { CardEditorDialog } from './card-editor-dialog.js';
import { NetDeviceManager } from '../net/net-device-manager.js';
import { netManagerFacade } from '../net/net-manager-facade.js';
import { BenchCards } from '../net/bench-cards.js';
import { CalibrationCopyOffers } from '../net/calibration-copy-offers.js';
import { namesNetDevice, remoteBackendOf } from '../net/net-device-ref.js';
import { NetConnection } from '../net/net-connection.js';
import { NetPreferences, makeServerEntry } from '../net/net-preferences.js';
import { NetServerListDialog } from '../net/net-server-list-dialog.js';
import { NetServerList } from '../net/net-server-list.js';
import { IntervalTicker } from '../net/ticker.js';
import { ServerProber, wsUrlOf } from '../net/server-prober.js';
import { stackOverOpenModals } from '../ui/modal-stack.js';
import { FftView } from '../ui/fft-view.js';
import { ScopeView } from '../ui/scope-view.js';
import { GenSignalForm, isDualTone, rawRms } from '../generator/dds-kernel.js';
import { Preferences } from '../store/preferences.js';
import { DeviceProfileStore } from '../store/device-profiles.js';
import { loadDeviceCatalog } from '../store/device-catalog.js';
import { createConfigPorts } from '../store/config-port.js';
import { JsonConfigDialog } from './json-config-dialog.js';
import { t, initBase, setLocale, stripMnemonics } from '../i18n/i18n.js';
import { LOCALES } from '../i18n/locales.js';
import { WavWriter, AiffWriter, readWav, readAiff } from '../io/wav.js';
import { saveScopeCapture, saveStreaming, findFullPeriodWindow, formatForName } from '../io/scope-capture.js';
import { encodeFlac, decodeFlac } from '../io/flac.js';
import { saveSpectrum, loadSpectrum } from '../io/fft-spectrum.js';
import { loadFrc } from '../io/frc.js';
import { pruneCals } from '../io/cal-store.js';
import { saveFile, openFile, bytesToText, pickSaveTarget, writeToTarget } from '../io/file-picker.js';
import { FreqRespPane } from '../freqresp/freqresp-pane.js';
import { TuneNotchWizard } from '../freqresp/tune-notch-wizard.js';
import { ScreenshotDialog } from './screenshot.js';
import { clonePaneForShot, preloadCloneIcons, paintCloneToCanvas } from './screenshot.js';
import { PredistortionHost } from './predistortion-host.js';
import { PredistortionWizard } from './predistortion-wizard.js';
import { PreferencesDialog } from './preferences-dialog.js';
import { Qa40xSettingsDialog } from '../qa40x/qa40x-settings-dialog.js';
import { StartupSplash } from './startup-splash.js';
import { TipDialog } from './tip-dialog.js';
import { MainTab } from './main-tab.js';
import { ScopePane } from '../scope/scope-pane.js';
import { ScopeTabControl } from '../scope/scope-tab-control.js';
import { preserveCanvasMiddle } from '../scope/scope-format.js';
import { FftPane } from '../fft/fft-pane.js';
import { FftTabControl, FFT_AVERAGES_OFF, FFT_AVERAGES_SERIES } from '../fft/fft-tab-control.js';
import { GeneratorPane } from '../generator/generator-pane.js';
import { PredistortionEngine } from '../predistortion/engine.js';
import { writeHarmonicDpd, writeIntermodDpd } from '../io/dpd.js';
import { NumericStepField, NumericStepModel, OFF_LABEL, UNIT_FAMILIES } from '../widgets/numeric-step-field.js';
import { unitValueEquals } from '../widgets/unit-conversion.js';
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
// 8/11.025/16/22.05 kHz + 44.1/48 kHz and their multiples up to 768 kHz. No 32 kHz (Java omits it).
const RATES = [8000, 11025, 16000, 22050, 44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000, 705600, 768000];
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
// The FFT side's loaded-.frc store (Java FftController owns
// new CorrectionStore("FFT", Events.FFT_CALIBRATION_CHANGED)). FftViewCorrection READS it, the
// predistortion bridge reads it live, and FftTabControl mutates it from the calibration rows.
// Silent (null change callback): the sole mutator, rebuildCalEntries, does its own single re-render
// after a clearAll()+addEntry batch, so a per-mutation callback would only re-render redundantly.
const fftCorrectionStore = new CorrectionStore('FFT', null);
// Render-time FFT spectral corrections (.frc de-embed + mains + IMD) - applied in the VIEW path
// (engine.onResult below), NOT in the engine; the coherent accumulator stays raw. Both take the
// engine at construction, so both are built in init's audio-backend block (see `engine`).
let fftViewCorrection;
let fftView;
const scopeView = new ScopeView(document.getElementById('scope'), { prefs });
let prefsModal, aboutModal;
let shotModal;
let confirmModal;
let alertModal;   // the shared one-button alert modal (device-error surface; constructed in init's modals step)
let prefsDialog;   // the Preferences dialog (constructed in init's modals step)
let calibrationDialog;   // the unified ADC/DAC calibration dialog (constructed after initStepFields)
let benchCalibration;   // where a calibrate WRITE goes while a server's backend is selected (init's modals step)
/** The human LABEL of the selected input device - what the card recognition patterns match (the
 *  <select> value carries a Web Audio deviceId). The Preferences dialog owns the combo; before it
 *  is built the DOM answers directly. */
const inputDeviceLabel = () => (prefsDialog ? prefsDialog.inputDeviceLabel()
  : $('#inSel option:selected').text());
let scopeTabControl;   // the oscilloscope settings strip (constructed in init's modals step)
let fftTabControl;   // the FFT settings strip (constructed in init's modals step)
let genPane;   // the generator pane (constructed in init, after initStepFields)
let scopePane;   // the oscilloscope pane (canvas / scrollbars / record / render loop; constructed in init, after initStepFields)
let fftPane;   // the FFT pane (spectrum canvas / record / readout+IMD / render loop; constructed in init, before fftTabControl)
let mainTab;   // the main tab (the rAF render-frame driver + the 3-pane collapse/sash layout; constructed in init, after both panes)

// The scope navigation scrollbars (vertSlider / navSlider) + NAV_RANGE / condensed-overview
// decimation moved to scope/scope-pane.js (Java ScopePane); app.js drives them through the
// scopePane instance.

// ADC/DAC re-calibration rescales every measured voltage (ADC) or the generated
// loopback level (DAC), making the accumulated running statistics inconsistent -
// clear the scope measurement history on a calibration change (Java ScopeView
// adcFsVoltageRms / dacFsVoltageAmpl onChange -> clearMeasurementHistory). Java
// FftView wires the SAME two prefs to resetStatistics(), so the FFT cross-tick
// average must be restarted too - else it keeps folding pre-calibration frames at
// the old scale into the displayed spectrum. Reset the accumulator while recording.
// `engine` is null-checked because the calibration prefs can move before init's audio-backend
// block has run (the device-profile store applies a card's full-scale as it resolves one).
const onCalChange = () => {
  scopeView._clearMeasurementHistory();
  if (engine && engine.fft.recording) engine.resetAnalyses();
  // The amplitude distribution goes with the running statistics: counts gathered at a different
  // calibration describe a different measurement, and merging them would present two populations
  // as one distribution. (A V/div or range change does NOT come through here - those relabel the
  // axis rather than recounting, and must keep their counts.)
  if (engine) engine.scope.resetHistograms();
};
prefs.adcFsVoltageRms.addListener(onCalChange);
// The RIGHT-channel calibration siblings rescale R-channel measurements the same way
// (Java FftView:460 resetStatistics also subscribes to adcFsVoltageRmsRightProperty /
// dacFsVoltageAmplRightProperty).
prefs.adcFsVoltageRmsRight.addListener(onCalChange);
prefs.dacFsVoltageAmpl.addListener(onCalChange);
prefs.dacFsVoltageAmplRight.addListener(onCalChange);

// A DAC full-scale move - a card RANGE switch, or a DAC re-calibration - must NOT change the
// generated LEVEL. The amplitude the user typed is absolute volts; the DDS derives its normalised
// amplitude as Vrms/(fsPeak·rawRms(form)), so the physical output is Vrms only while the kernel
// knows the CURRENT full scale. engine.config.dacFsVoltageAmpl is otherwise refreshed only by
// readConfig() (a start / a settings commit), which a range switch does not run: the worklet kept
// the OLD full scale while the hardware moved to the new one, and the trace jumped by fsNew/fsOld
// on every range change. Push the new scale - and the right-lane
// ratio fsLeft/fsRight, which the same card edit moves - then retune, which is a no-op unless the
// generator is running. The amplitude CEILING is a separate concern, handled by syncAmpMax.
const syncDacFullScale = () => {
  if (!engine) return;   // the card store can resolve a profile before init built the engine
  engine.config.dacFsVoltageAmpl = prefs.getDacFsVoltageAmpl();
  engine.config.rightLaneScale = prefs.dacRightLaneScale();
  engine.retuneGenerator();
};
prefs.dacFsVoltageAmpl.addListener(syncDacFullScale);
prefs.dacFsVoltageAmplRight.addListener(syncDacFullScale);

// ----- generator amplitude minimum (Java AMP_MIN_VRMS) + frequency minimum -----
const AMP_MIN_VRMS = 1e-9;
// Amplitude CEILING (no-clip), in the canonical Vrms the amplitude fields store. The DDS drives
// amplitude = Vrms / (fsPeak · rawRms(form)) (dds-kernel), so digital full scale - the point where
// the waveform starts to clip - is exactly Vrms = fsPeak · rawRms(form). It is therefore
// WAVEFORM-AWARE: a sine tops out at fsPeak/√2, a rectangle at fsPeak, a triangle at fsPeak/√3, a
// dual tone at fsPeak·√((w1²+w2²)/2), and so on. It reads the LIVE DAC full-scale calibration and
// the LIVE form / dual-tone split on every call - never a value snapshotted at construction.
// (Capping at the raw PEAK voltage let a sine entry run √2 (+3 dB) past full scale.) This one
// ceiling backs all three unit views - V, dBV and dBFS (where it is exactly 0 dBFS).
const ampMaxVrms = () => prefs.getDacFsVoltageAmpl()
  * rawRms(prefs.genSignalForm.get(), sfVal('amp1Pct', 50) / 100, sfVal('amp2Pct', 50) / 100);
// The frequency-response sweep and the tune-notch wizard always emit a SINE-family signal
// (Farina sweep / sine), independent of the generator's selected form.
const sweepAmpMaxVrms = () => prefs.getDacFsVoltageAmpl() * rawRms(GenSignalForm.LOG_SWEEP);
const GEN_FREQ_MIN_HZ = 0.01;   // Java GeneratorPane.GEN_FREQ_MIN_HZ
// The averages dial's Off sentinel + preset series live with the FFT tab control (Java
// FftTabControl.AVERAGES_OFF / AVERAGES_SERIES), which also renders them on the tile.
const outRate = () => parseInt($('#outRate').val(), 10) || prefs.current().outputSampleRate || 384000;
const inRate = () => parseInt($('#inRate').val(), 10) || prefs.current().inputSampleRate || 384000;   // FR fields cap at INPUT Nyquist

// NumericStepField controllers, built in init() once i18n has resolved (the unit
// suffixes come from t('unit.*')). Keyed by the input id.
const stepFields = {};
/** Canonical value of a step field by input id (fallback when not yet built). */
const sfVal = (id, dflt) => (stepFields[id] ? stepFields[id].getValue() : (parseFloat($('#' + id).val()) || dflt));
// Set by initStepFields: re-applies the waveform-aware amplitude ceiling. Held here so the
// dual-tone split fields (built later in the same pass) can trigger it from their onChange.
let stepFieldsAmpMaxSync = () => {};

// Two-way binding (Java Bindings.stepField): a field edit writes the pref (the field's own
// onChange) AND an external pref change (preset load, DAC calibration, other generator code)
// pushes back into the field. setValue is silent (no onChange - numeric-step-field.js:504), so
// the value-guarded listener can never feed back into a loop.
function bidiBind(field, pref) {
  if (field) pref.addListener((v) => { if (field.getValue() !== v) field.setValue(v); });
}

/** 1-2-5 decade ladder over [min .. max] inclusive (the scope V/div and t/div
 *  step series), e.g. 1µ, 2µ, 5µ, 10µ, ... 200, 500. */
function ladder125(min, max) {
  const out = [];
  const REL = 1 + 1e-9;
  let decade = Math.pow(10, Math.floor(Math.log10(min) + 1e-9));
  while (decade <= max * REL) {
    for (const m of [1, 2, 5]) {
      const v = m * decade;
      if (v >= min / REL && v <= max * REL) out.push(v);
    }
    decade *= 10;
  }
  return out;
}

// Scope V/div + t/div 1-2-5 ladders (Java ScopeTabControl LIST policy): V/div
// 1 nV ... 500 V, t/div 1 µs ... 1 s - the exact OscParse.VOLT_PER_DIV / TIME_PER_DIV
// step lists (voltsPerDivTargets/timePerDivTargets). Shared by the step fields AND
// the scope view's Ctrl-wheel zoom, so the wheel snaps to the SAME series the field
// arrows walk. (V/div starts at 1 nV to match Java, which the model min already
// permits - V_PER_DIV_MIN = 1e-9.)
const SCOPE_VDIV_SERIES = ladder125(1e-9, 500);
const SCOPE_TDIV_SERIES = ladder125(1e-6, 1);
// The Ctrl-wheel V/div + Ctrl+Shift-wheel t/div zoom step along the same ladders.
scopeView.vDivSeries = SCOPE_VDIV_SERIES;
scopeView.tDivSeries = SCOPE_TDIV_SERIES;

// Build the generator + FR NumericStepFields from the existing `.numfield` DOM.
// Each field owns parsing/formatting/clamping/stepping; its onChange writes the
// bound preference and live-retunes the engine.
function initStepFields() {
  const F = UNIT_FAMILIES;
  const mk = (id, model, onChange) => {
    const input = document.getElementById(id);
    if (!input) return null;
    const f = new NumericStepField(input, model, { onChange, tooltipBase: input.title || '' });
    stepFields[id] = f;
    return f;
  };

  // Frequency: FREQUENCY family, PERCENT policy, [GEN_FREQ_MIN_HZ .. outRate/2].
  // toneHz doubles as the single-tone Frequency AND the dual-tone Frequency 1; it writes the
  // matching pref per form (genDualToneFreq1Hz in dual, else genFrequencyHz) - Java keeps them
  // as distinct fields, so their defaults (1 kHz vs 19 kHz) differ.
  const fTone = mk('toneHz', new NumericStepModel({ family: F.FREQUENCY, min: GEN_FREQ_MIN_HZ, max: outRate() / 2, maxDecimals: 9 }),
    (v) => {
      if (isDualTone($('#signalForm').val())) prefs.genDualToneFreq1Hz.set(v);
      else prefs.genFrequencyHz.set(v);
      engine.config.toneHz = v; engine.retuneGenerator(); genPane.refreshFreqLabel(); genPane.updateDutyLabel();
    });
  fTone.setValue(prefs.genFrequencyHz.get());

  const fTone2 = mk('tone2Hz', new NumericStepModel({ family: F.FREQUENCY, min: GEN_FREQ_MIN_HZ, max: outRate() / 2, maxDecimals: 9 }),
    (v) => { prefs.genDualToneFreq2Hz.set(v); engine.config.tone2Hz = v; engine.retuneGenerator(); genPane.refreshFreqLabel(); });
  fTone2.setValue(prefs.genDualToneFreq2Hz.get());

  // Amplitude: AMPLITUDE family, PERCENT policy. What is STORED is the entered value - the
  // displayed number and its unit - and the store resolves it to V RMS at use, so a dBFS entry
  // still means what was typed after a recalibration. fsAmplSupplier (the live DAC PEAK full
  // scale) enables that dBFS entry - 0 dBFS is a full-scale SINE (AES17), anchored at fsPeak/√2.
  const fAmp = mk('ampDbfs', new NumericStepModel({ family: F.AMPLITUDE, min: AMP_MIN_VRMS, max: ampMaxVrms(), maxDecimals: 5,
    fsAmplSupplier: () => prefs.getDacFsVoltageAmpl() }),
    (v) => {
      // The pair the operator entered - number and unit as ONE write, so nothing
      // downstream can see the new unit against the old number.
      prefs.genAmplitude.set(fAmp.enteredValue());
      engine.config.ampVrms = v; engine.retuneGenerator();
    });
  fAmp.seedPair(prefs.genAmplitude.get());
  // Keep the no-clip ceiling live (Bindings.onChange(... ampField::setMax)): it moves with the DAC
  // full-scale CALIBRATION and with the waveform (and its dual-tone split), so re-apply it on every
  // input that feeds ampMaxVrms(). setMax re-clamps the current value, so switching to a form with
  // a lower headroom (e.g. rectangle -> sine) trims an now-over-scale amplitude down to the new max.
  const syncAmpMax = () => fAmp.setMax(ampMaxVrms());
  prefs.dacFsVoltageAmpl.addListener(syncAmpMax);
  prefs.genSignalForm.addListener(syncAmpMax);
  stepFieldsAmpMaxSync = syncAmpMax;   // the dual-tone split fields call it from their onChange

  // Dither depth: DITHER-policy NumericStepField (whole/fractional bits OR a full-scale-aware dBV
  // VIEW of the same value) - Java GeneratorPane ditherField. fsAmplSupplier = the DAC PEAK
  // full-scale (Vpeak) so the dBV view tracks recalibration. The dBV is the PHYSICAL TPDF level
  // relative to that full-scale - window-invariant, NO FFT-window term: since the analyser's NENBW
  // correction the integrated noise metrics (N, SNR, ...) read the true level, so the entered dBV
  // checks against them with any analysis window. maxBits = 32. The TPDF dither is applied LIVE to
  // the generated signal in the dds worklet (Java PcmQuantizer live-apply) so it shows
  // on the FFT floor exactly where the dBV view sets it - hence the engine push below, mirroring the
  // amplitude field. Seed the ENTERED value BEFORE the change path re-enters (seedPair replays it
  // through commit without firing onChange). On a committed change: persist the entered value,
  // push the depth to the running worklet, then re-annotate the "Dither" caption.
  const fDither = mk('dither', new NumericStepModel({ family: F.DITHER, maxBits: 32,
    fsAmplSupplier: () => prefs.getDacFsVoltageAmpl() }),
    (v) => {
      prefs.genDither.set(fDither.enteredValue());
      engine.config.ditherBits = v; engine.retuneGenerator();
      if (genPane) genPane.updateDitherLabel();
    });
  if (fDither) {
    fDither.seedPair(prefs.genDither.get());
    // Render the "Dither" caption's companion-unit bracket NOW: initStepFields runs AFTER the first
    // applyPrefsToUi -> seedGeneratorControls (which called updateDitherLabel while the field didn't
    // yet exist), so without this the bracket stays empty until the first change. genPane is built
    // before initStepFields, so it's present here.
    if (genPane) genPane.updateDitherLabel();
  }

  // Unified calibration dialog (Java CalibrationDialog): the two per-channel measured/actual-
  // amplitude fields (Left / Right), unit-aware AMPLITUDE step fields (V / mV / µV / dBV + short
  // forms), like the generator amplitude. ONE dialog serves the scope ADC, FFT ADC and generator
  // DAC flows; each row is seeded on open and read (canonical Vrms) on Calibrate.
  mk('calLeftValue', new NumericStepModel({ family: F.AMPLITUDE, min: AMP_MIN_VRMS, max: 1000, maxDecimals: 5 }), () => {});
  mk('calRightValue', new NumericStepModel({ family: F.AMPLITUDE, min: AMP_MIN_VRMS, max: 1000, maxDecimals: 5 }), () => {});

  // FFT THD "Manual fundamental" reference level - unit-aware AMPLITUDE field (accepts dBV).
  // onChange placeholder - FftTabControl.bind() rebinds it to the THD-settings commit path
  // (pref-write + live readConfig / stopped-state recompute - NO restartFft).
  // NO fsAmplSupplier on purpose: this is an ADC-side reference level (external levels routinely
  // far above DAC full scale), so a dBFS figure would be meaningless - the model refuses dBFS here.
  const fManFund = mk('fftManualFund', new NumericStepModel({ family: F.AMPLITUDE, min: AMP_MIN_VRMS, max: 200, maxDecimals: 5 }), () => {});
  if (fManFund) { fManFund.model.setLogDisplay(prefs.fftManualFundDbvDisplay.get()); fManFund.setValue(prefs.fftManualFundVrms.get()); }

  // FFT THD distortion band + harmonic counts - the Java THD tab's four NumericStepFields
  // (FftTabControl.buildThdTab): distMin/distMax FREQUENCY [0 .. inRate/2] 9 dec (:570/:594),
  // maxThd 2..9 / maxCalc 9..50, step 1, 0 dec (:640/:654). onChange is a placeholder here -
  // FftTabControl.bind() rebinds each to the THD-settings commit path (like averages/manualFund).
  const fDistMin = mk('thdDistMin', new NumericStepModel({ family: F.FREQUENCY, min: 0, max: inRate() / 2, maxDecimals: 9 }), () => {});
  if (fDistMin) fDistMin.setValue(prefs.fftDistMinHz.get());
  const fDistMax = mk('thdDistMax', new NumericStepModel({ family: F.FREQUENCY, min: 0, max: inRate() / 2, maxDecimals: 9 }), () => {});
  if (fDistMax) fDistMax.setValue(prefs.fftDistMaxHz.get());
  const fThdMaxH = mk('thdMaxH', new NumericStepModel({ family: F.NONE, min: 2, max: 9, wheelStep: 1, arrowStep: 1, decimals: 0 }), () => {});
  if (fThdMaxH) fThdMaxH.setValue(prefs.fftThdMaxHarmonic.get());
  const fCalcMaxH = mk('thdCalcMaxH', new NumericStepModel({ family: F.NONE, min: 9, max: 50, wheelStep: 1, arrowStep: 1, decimals: 0 }), () => {});
  if (fCalcMaxH) fCalcMaxH.setValue(prefs.fftCalcMaxHarmonic.get());

  // FFT "Averages" - Java averagesField: NumericStepField(UnitFamily.NONE, AVERAGES_OFF=1,
  // POSITIVE_INFINITY, AVERAGES_SERIES {2,4,8,16,32,64,128,∞}, 0 decimals, width 70). LIST policy:
  // the wheel/arrows snap along the series; ∞ is the top entry, NOT a separate checkbox. Typing
  // accepts any count ≥ 1 plus two named tokens: "∞" or any prefix of "Infinity" (i / in / inf ...),
  // which max=Infinity enables, and any prefix of "Off" (o / of / off) -> 1 (numeric-step-field.js
  // commit()).
  // onChange placeholder - FftTabControl.bind() rebinds it to pref-write + live readConfig
  // (an averages change must NOT restart/reset the accumulator).
  const fAverages = mk('averages', new NumericStepModel({ family: F.NONE, min: FFT_AVERAGES_OFF,
    max: Infinity, series: FFT_AVERAGES_SERIES, maxDecimals: 0 }), () => {});
  // 1 renders and parses as "Off" - typed in full or as any prefix (o / of / off),
  // the same shortcut the generator's dither field takes.
  if (fAverages) fAverages.model.setNamedValue(FFT_AVERAGES_OFF, OFF_LABEL);
  if (fAverages) fAverages.setValue(prefs.fftAverages.get());

  // FFT "Stop after N averages" count - Java stopAfterNField: NumericStepField(UnitFamily.NONE,
  // STOP_AFTER_MIN=2, STOP_AFTER_MAX=1_000_000, STOP_AFTER_WHEEL_STEP=100, arrowStep 1, 0 dec).
  // FIXED policy: the wheel jumps in hundreds (the count ranges to a million), arrows step by 1.
  // onChange placeholder - FftTabControl.bind() rebinds it to the pref-write + live readConfig.
  const fStopAfterN = mk('fftStopAfterN', new NumericStepModel({ family: F.NONE, min: 2, max: 1000000,
    wheelStep: 100, arrowStep: 1, decimals: 0 }), () => {});
  if (fStopAfterN) fStopAfterN.setValue(prefs.fftStopAfterN.get());

  // Duty / dual-tone amplitude split: PERCENT family + PERCENT policy.
  const fDuty = mk('duty', new NumericStepModel({ family: F.PERCENT, min: 0.001, max: 99.999, maxDecimals: 3 }),
    () => {
      const duty = (sfVal('duty', 50) || 50) / 100;
      const form = $('#signalForm').val();
      // Write ONLY the active form's duty pref - each form remembers its own (Java dutyField).
      if (form === GenSignalForm.TRIANGLE) prefs.genTriangleDuty.set(duty);
      else if (form === GenSignalForm.RECTANGLE) prefs.genRectangleDuty.set(duty);
      engine.config.rectDuty = duty; engine.config.triDuty = duty;
      engine.retuneGenerator();
      genPane.updateDutyLabel();
    });
  fDuty.setValue(((prefs.genSignalForm.get() === GenSignalForm.TRIANGLE
    ? prefs.genTriangleDuty.get() : prefs.genRectangleDuty.get()) || 0.5) * 100);

  const fAmp1 = mk('amp1Pct', new NumericStepModel({ family: F.PERCENT, min: 0.001, max: 99.999, maxDecimals: 3 }),
    (v) => { genPane.applyDualToneAmpSplit('amp1Pct', v); stepFieldsAmpMaxSync(); });
  fAmp1.setValue(prefs.genDualToneSplitPct.get());
  const fAmp2 = mk('amp2Pct', new NumericStepModel({ family: F.PERCENT, min: 0.001, max: 99.999, maxDecimals: 3 }),
    (v) => { genPane.applyDualToneAmpSplit('amp2Pct', v); stepFieldsAmpMaxSync(); });
  fAmp2.setValue(100 - prefs.genDualToneSplitPct.get());

  // Sweep fields (LINEAR_SWEEP / LOG_SWEEP) - NumericStepField like the scope's, faithful to
  // Java sweep*Field: Start/Stop FREQUENCY [0.01 .. Nyquist] 9 dec; Duration/Fade-in/Fade-out
  // TIME (min 0.001 / 0, 3 dec). Wheel/arrow/keyboard stepping + unit auto-ranging come free.
  // Java GeneratorController sweep-pref bindings (4887ecb): a running Farina (LOG) sweep
  // can't live-edit its pre-rendered buffer, so a param change does a full restart (the
  // restart's readConfig picks the new value up) and skips the live setter + retune.
  const sweepOnChange = (pref, cfgKey) => async (v) => {
    pref.set(v);
    if (await genPane.restartFarinaOnParamChange()) return;
    engine.config[cfgKey] = v; engine.retuneGenerator();
  };
  const fSwStart = mk('sweepStart', new NumericStepModel({ family: F.FREQUENCY, min: GEN_FREQ_MIN_HZ, max: outRate() / 2, maxDecimals: 9 }),
    sweepOnChange(prefs.genSweepFreqStartHz, 'sweepStartHz'));
  if (fSwStart) fSwStart.setValue(prefs.genSweepFreqStartHz.get());
  const fSwStop = mk('sweepStop', new NumericStepModel({ family: F.FREQUENCY, min: GEN_FREQ_MIN_HZ, max: outRate() / 2, maxDecimals: 9 }),
    sweepOnChange(prefs.genSweepFreqEndHz, 'sweepEndHz'));
  if (fSwStop) fSwStop.setValue(prefs.genSweepFreqEndHz.get());
  const fSwDur = mk('sweepDur', new NumericStepModel({ family: F.TIME, min: 0.001, max: 1000000, maxDecimals: 3 }),
    sweepOnChange(prefs.genSweepDurationSec, 'sweepDurationSec'));
  if (fSwDur) fSwDur.setValue(prefs.genSweepDurationSec.get());
  const fSwFi = mk('sweepFadeIn', new NumericStepModel({ family: F.TIME, min: 0, max: 1000000, maxDecimals: 3 }),
    sweepOnChange(prefs.genSweepFadeInSec, 'sweepFadeInSec'));
  if (fSwFi) fSwFi.setValue(prefs.genSweepFadeInSec.get());
  const fSwFo = mk('sweepFadeOut', new NumericStepModel({ family: F.TIME, min: 0, max: 1000000, maxDecimals: 3 }),
    sweepOnChange(prefs.genSweepFadeOutSec, 'sweepFadeOutSec'));
  if (fSwFo) fSwFo.setValue(prefs.genSweepFadeOutSec.get());
  // WAV-export duration - TIME NumericStepField (Java durationField).
  const fGenDur = mk('genDuration', new NumericStepModel({ family: F.TIME, min: 0.001, max: 1000000, maxDecimals: 3 }),
    (v) => prefs.genWavDurationSeconds.set(v));
  if (fGenDur) fGenDur.setValue(prefs.genWavDurationSeconds.get());

  // Two-way bind the generator step fields to their prefs (external changes update the field).
  bidiBind(fTone, prefs.genFrequencyHz);
  bidiBind(fTone2, prefs.genDualToneFreq2Hz);
  // The amplitude and the dither are bound as ENTERED values, so an external write replays the
  // pair and the field shows the unit it was stored in - a canonical writer (a calibration, a
  // preset) stores the base unit, which replays as volts or bits exactly as before.
  if (fAmp) {
    prefs.genAmplitude.addListener((v) => {
      if (!unitValueEquals(fAmp.enteredValue(), v)) fAmp.seedPair(v);
    });
  }
  if (fDither) {
    prefs.genDither.addListener((v) => {
      if (!unitValueEquals(fDither.enteredValue(), v)) fDither.seedPair(v);
    });
  }
  bidiBind(fSwStart, prefs.genSweepFreqStartHz);
  bidiBind(fSwStop, prefs.genSweepFreqEndHz);
  bidiBind(fSwDur, prefs.genSweepDurationSec);
  bidiBind(fSwFi, prefs.genSweepFadeInSec);
  bidiBind(fSwFo, prefs.genSweepFadeOutSec);
  bidiBind(fGenDur, prefs.genWavDurationSeconds);
  // FFT averages - two-way (Java Bindings.stepField): a preset recall pushes the new count
  // (or ∞) back into the field display.
  bidiBind(fAverages, prefs.fftAverages);

  // FreqResp settings fields.
  const fr = (id, model, onChange) => mk(id, model, onChange);
  const fStart = fr('frStart', new NumericStepModel({ family: F.FREQUENCY, min: 1, max: inRate() / 2, maxDecimals: 9 }),
    (v) => prefs.freqRespStartHz.set(v));
  if (fStart) fStart.setValue(prefs.freqRespStartHz.get());
  const fStop = fr('frStop', new NumericStepModel({ family: F.FREQUENCY, min: 1, max: inRate() / 2, maxDecimals: 9 }),
    (v) => prefs.freqRespStopHz.set(v));
  if (fStop) fStop.setValue(prefs.freqRespStopHz.get());
  // fsAmplSupplier enables dBFS entry (as on the generator). NB: unlike the generator this field
  // has no live setMax listener, so its max is frozen at construction - the supplier still reads live.
  const fAmpFr = fr('frAmp', new NumericStepModel({ family: F.AMPLITUDE, min: AMP_MIN_VRMS, max: sweepAmpMaxVrms(), maxDecimals: 5,
    fsAmplSupplier: () => prefs.getDacFsVoltageAmpl() }),
    () => prefs.freqRespAmplitude.set(fAmpFr.enteredValue()));
  // Track the live DAC full-scale calibration, as the generator field does (this one previously
  // froze its ceiling at construction). Replaying the stored value after the ceiling moves leaves
  // the entered text where it stands and re-solves what a dBFS entry resolves to.
  if (fAmpFr) {
    prefs.dacFsVoltageAmpl.addListener(() => {
      fAmpFr.setMax(sweepAmpMaxVrms());
      fAmpFr.seedPair(prefs.freqRespAmplitude.get());
    });
    fAmpFr.seedPair(prefs.freqRespAmplitude.get());
  }
  const fLead = fr('frLeadIn', new NumericStepModel({ family: F.TIME, min: 0.05, max: 1000000, maxDecimals: 3 }),
    (v) => prefs.freqRespLeadInSec.set(v));
  if (fLead) fLead.setValue(prefs.freqRespLeadInSec.get());
  // Sweep points (Java SWEEP_POINT_SERIES): the wheel jumps along the power-of-2
  // presets PLUS a runtime "Nyquist/2" entry (one point per FFT bin to Nyquist =
  // inRate/2), prepended FIRST in wheel order and rendered as a named text value.
  // Free typing covers everything between; max 10 000 000.
  const SWEEP_POINT_SERIES = [8192, 16384, 65536, 131072, 262144, 524288, 1048576, 2097152, 4194304];
  const nyquistPointCount = () => inRate() / 2;
  const sweepPointSeries = () => [nyquistPointCount(), ...SWEEP_POINT_SERIES];
  const fPts = fr('frPoints', new NumericStepModel({ family: F.NONE, min: 8192, max: 10000000,
    series: sweepPointSeries(), maxDecimals: 0 }),
    (v) => prefs.freqRespSweepPoints.set(Math.round(v)));
  if (fPts) {
    fPts.model.setNamedValue(nyquistPointCount(), t('freqResp.settings.points.halfScale'));
    fPts.setValue(prefs.freqRespSweepPoints.get());
  }

  // Audio-format edits move the Nyquist ceiling of the sweep band edges and the
  // sample-rate/2 entry of the sweep-points series - re-pull both on an input-rate
  // change (Java FreqRespTabControl AUDIO_FORMAT_CHANGED listener).
  $('#inRate').on('input-sample-rate-change change', () => {
    const nyquist = inRate() / 2;
    if (fStart) fStart.setMax(nyquist);
    if (fStop) fStop.setMax(nyquist);
    if (fPts) {
      fPts.model.setSeries(sweepPointSeries());
      fPts.model.setNamedValue(nyquistPointCount(), t('freqResp.settings.points.halfScale'));
      fPts.refresh();
    }
  });

  // Two-way bind the FreqResp step fields to their prefs (Java Bindings.stepField /
  // stepFieldInt are two-way): an external write - preset recall, wizard - updates the
  // field display through the same NumericStepField, like the generator fields above.
  bidiBind(fStart, prefs.freqRespStartHz);
  bidiBind(fStop, prefs.freqRespStopHz);
  // The amplitude is bound as the ENTERED value: an external write (preset recall, wizard)
  // replays the pair, so the field shows the unit it was saved in - not a voltage.
  if (fAmpFr) {
    prefs.freqRespAmplitude.addListener((v) => {
      if (!unitValueEquals(fAmpFr.enteredValue(), v)) fAmpFr.seedPair(v);
    });
  }
  bidiBind(fLead, prefs.freqRespLeadInSec);
  bidiBind(fPts, prefs.freqRespSweepPoints);

  // Scope V/div + t/div (Java ScopeTabControl, LIST policy): a strict 1-2-5 ladder.
  // V/div 1 µV ... 500 V, t/div 1 µs ... 1 s. onChange writes the osc* pref the scope
  // view reads next frame. Manual entry between ladder points is still allowed.
  const vDivSeries = SCOPE_VDIV_SERIES;
  const tDivSeries = SCOPE_TDIV_SERIES;
  // Coupled write (Java ScopeTabControl leftScale/rightScale selection listeners):
  // re-derive the channel's offsetFrac from the OLD V/div via preserveCanvasMiddle so
  // the voltage at the canvas vertical centre stays fixed ("zoom around the middle"),
  // then write the new V/div. The offset must be set BEFORE the V/div changes because
  // preserveCanvasMiddle needs the pre-change value.
  const fLV = mk('scopeLeftVdiv', new NumericStepModel({ family: F.VOLTS_PER_DIV, min: 1e-9, max: 500, series: vDivSeries, maxDecimals: 3 }),
    (v) => {
      const oldV = prefs.oscLeftVoltsPerDiv.get();
      prefs.oscLeftOffsetFrac.set(preserveCanvasMiddle(prefs.oscLeftOffsetFrac.get(), oldV, v));
      prefs.oscLeftVoltsPerDiv.set(v); scopePane.syncOffsetScrollbar(); scopePane.refreshScopeTiles(); scopePane.refreshScopeFileMode();
    });
  if (fLV) fLV.setValue(prefs.oscLeftVoltsPerDiv.get());
  const fRV = mk('scopeRightVdiv', new NumericStepModel({ family: F.VOLTS_PER_DIV, min: 1e-9, max: 500, series: vDivSeries, maxDecimals: 3 }),
    (v) => {
      const oldV = prefs.oscRightVoltsPerDiv.get();
      prefs.oscRightOffsetFrac.set(preserveCanvasMiddle(prefs.oscRightOffsetFrac.get(), oldV, v));
      prefs.oscRightVoltsPerDiv.set(v); scopePane.syncOffsetScrollbar(); scopePane.refreshScopeTiles(); scopePane.refreshScopeFileMode();
    });
  if (fRV) fRV.setValue(prefs.oscRightVoltsPerDiv.get());
  const fTd = mk('scopeTdiv', new NumericStepModel({ family: F.TIME_PER_DIV, min: 1e-6, max: 1, series: tDivSeries, maxDecimals: 3 }),
    (v) => { prefs.oscTimePerDiv.set(v); scopePane.refreshScopeTiles(); scopePane.refreshScopeFileMode(); });
  if (fTd) fTd.setValue(prefs.oscTimePerDiv.get());

  // Trigger level (oscTriggerLevelFrac) and position (oscTriggerPositionFrac) have
  // NO numeric field - matching Java's buildTriggerGroup, which exposes neither.
  // Both are set by dragging their on-canvas handle (ScopeView) / wheel-pan.
  // Trigger hysteresis: 0..5 divisions in 0.1-div steps (Java HYST_MAX_DIV / STEP).
  const fTH = mk('scopeTrigHyst', new NumericStepModel({ family: F.DIVISIONS, min: 0, max: 5, wheelStep: 0.1, arrowStep: 0.1, decimals: 1 }),
    (v) => { prefs.oscTriggerHysteresisDiv.set(v); scopePane.refreshScopeTiles(); });
  if (fTH) { fTH.setValue(prefs.oscTriggerHysteresisDiv.get()); fTH.setDisabled(!prefs.oscTriggerHysteresisEnabled.get()); }
  // Save-to duration (s), Java oscSaveDurationSeconds.
  const fSD = mk('scopeSaveDur', new NumericStepModel({ family: F.TIME, min: 0.001, max: 1000000, maxDecimals: 3 }),
    (v) => prefs.oscSaveDurationSeconds.set(v));
  if (fSD) fSD.setValue(prefs.oscSaveDurationSeconds.get());
}

// The scope V/div + t/div field re-sync after auto-setup (refreshScopeFields) moved to
// scope/scope-pane.js (Java ScopePane); app.js drives it through the scopePane instance.

// The dual-tone amplitude-split coupling (applyDualToneAmpSplit) + the duty/dual-tone
// live-retune (retuneDualAndDuty) moved to generator/generator-pane.js (Java GeneratorPane);
// the generator step-field onChanges reach them via the genPane instance.

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
  // .seedFftControls(); a preset recall re-seeds them via the host.applyPrefsToUi callback.
  if (fftTabControl) fftTabControl.seedFftControls();

  // The generator settings controls (#signalForm / #dither / #snap / sweep + .dpd rows)
  // are seeded by GeneratorPane.seedGeneratorControls(); a FFT-preset recall re-seeds them
  // via the host.applyPrefsToUi callback (guarded - genPane is built after this first run).
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

function readConfig() {
  const c = engine.config;
  c.inDeviceId = $('#inSel').val(); c.inRate = parseInt($('#inRate').val(), 10);
  c.outDeviceId = $('#outSel').val(); c.outRate = parseInt($('#outRate').val(), 10);
  c.toneHz = sfVal('toneHz', 1000); c.ampVrms = sfVal('ampDbfs', 0.5);
  c.form = $('#signalForm').val();
  c.snapToBin = $('#snap').is(':checked');   // gates the SINE/DUAL_TONE FFT-bin emit snap
  // Compensated forms carry the loaded .dpd text (per-form slot) for the worklet to apply.
  c.dpdText = (c.form === GenSignalForm.SINE_COMP || c.form === GenSignalForm.DUAL_TONE_COMP)
    ? (prefs.getGenDpd(c.form) || null) : null;
  c.tone2Hz = sfVal('tone2Hz', 1100);
  c.amp1Pct = sfVal('amp1Pct', 50); c.amp2Pct = sfVal('amp2Pct', 50);
  const duty = (sfVal('duty', 50) || 50) / 100;
  c.rectDuty = duty; c.triDuty = duty;
  c.ditherBits = sfVal('dither', 0);   // fractional bits from the DITHER NumericStepField (0 = Off)
  // Sweep params (LINEAR_SWEEP / LOG_SWEEP) - read from the sweep fields.
  c.sweepStartHz = sfVal('sweepStart', 20);
  c.sweepEndHz = sfVal('sweepStop', 20000);
  c.sweepDurationSec = Math.max(0.001, sfVal('sweepDur', 1));
  c.sweepFadeInSec = Math.max(0, sfVal('sweepFadeIn', 0));
  c.sweepFadeOutSec = Math.max(0, sfVal('sweepFadeOut', 0));
  c.sweepLoop = $('#sweepLoop').is(':checked');
  c.fftSize = parseInt($('#fftSize').val(), 10); c.window = $('#window').val();
  c.overlap = $('#overlap').val();
  // ∞ (forever) averaging -> a true cumulative mean (Infinity). The cross-tick
  // accumulator in FftController grows the depth tick by tick (it no longer sizes
  // a buffer to the whole depth), so ∞ needs no finite cap; the stop-after-N
  // auto-stop, available only in ∞ mode, lets the user end the run. Read from the
  // averages NumericStepField (Java averagesField - ∞ is the top of AVERAGES_SERIES).
  c.averages = stepFields.averages ? stepFields.averages.getValue() : (parseInt($('#averages').val(), 10) || 4);
  c.coherent = $('#coherent').is(':checked');
  // JIT warm-up has NO UI field (Java hardwires it - no Settings-tab control); c.warmupMs
  // keeps its backend default (backend.js). Only the web-only thread-pool size is read.
  c.threads = parseInt($('#threads').val(), 10);
  c.fllOn = $('#align').val() === 'fll';
  // Calibration anchors from preferences (DAC full-scale scales the DDS amplitude;
  // harmonicCount widens the THD set per fftCalcMaxHarmonic).
  c.dacFsVoltageAmpl = prefs.dacFsVoltageAmpl.get();
  // Output-lane routing (Java GeneratorController.pushOutputRoutingToPlayback): the lane
  // gate + the right-lane scale (= fsLeft/fsRight). Left stays the mono amplitude reference
  // (scale 1.0), so the DDS amplitude math is unchanged - only the interleave seam gates/scales.
  c.outputChannels = prefs.genOutputChannels.get();
  c.rightLaneScale = prefs.dacRightLaneScale();
  // Java FftAnalyzerWorker:1661 - calcMaxH = max(9, getFftCalcMaxHarmonic()) - 1, i.e. the COUNT of
  // harmonics H2..HN (the fundamental is NOT one of them). The web omitted the -1, so it filled one
  // extra harmonic H(N+1), i.e. the max harmonic to calculate counted the fundamental. The display's
  // label cap (fft-view calcMax = max(9,N)) is the max harmonic NUMBER and stays as-is.
  c.harmonicCount = Math.max(9, prefs.fftCalcMaxHarmonic.get()) - 1;
  c.thdMaxHarmonic = Math.max(2, Math.min(9, prefs.fftThdMaxHarmonic.get()));   // THD sum upper bound (H2..HN)
  c.manualFundEnabled = prefs.fftManualFundEnabled.get();                        // fixed-reference fundamental
  c.manualFundVrms = prefs.fftManualFundVrms.get();
  c.fftFundFromGenerator = prefs.fftFundFromGenerator.get();   // hint analyzer with the gen freq vs auto-detect
  c.snrFreqMin = prefs.fftDistMinEnabled.get() ? prefs.fftDistMinHz.get() : 0;   // THD band (Java distMin/distMax)
  c.snrFreqMax = prefs.fftDistMaxEnabled.get() ? prefs.fftDistMaxHz.get() : 0;
  // Stop-after-N auto-stop + mains suppression (Java FftAnalyzerWorker).
  c.stopAfterNEnabled = prefs.fftStopAfterNEnabled.get();
  c.stopAfterN = prefs.fftStopAfterN.get();
  c.mainsSuppression = prefs.fftMainsSuppression.get();
  // Time-domain discontinuity gate toggle (Java prefs.isFftDetectTimeDiscontinuity), read live
  // per worker dispatch in FftController; kept in sync on toggle by the FFT settings checkbox.
  c.fftDetectTimeDiscontinuity = prefs.fftDetectTimeDiscontinuity.get();
  // Which ADC channel the FFT analyzes (Java FftAnalyzerWorker:1535
  // prefs.getFftChannel(); L -> ch0, R -> ch1) - flows into FftController._wantLeft
  // at setup and is switched live via engine.setFftChannel from the L/R buttons.
  c.channel = prefs.fftChannel.get();
  // Scope peak-voltage anchor + IMD dBV offset (offset-invariant ratios, but the
  // absolute dBV columns need it). The dBV offset threads the ANALYZED channel so the
  // manual-fundamental anchor resolves against the right ADC full-scale (Java
  // FftAnalyzerWorker:2005 getDbvOffsetDb(getFftChannel())). Both channels' offsets are
  // snapshotted so a live L↔R switch (FftController.setFftChannel) re-picks without a
  // full re-read.
  c.adcFsVoltageRms = prefs.adcFsVoltageRms.get();
  c.dbvOffsetDbLeft = prefs.getDbvOffsetDb('L');
  c.dbvOffsetDbRight = prefs.getDbvOffsetDb('R');
  c.dbvOffsetDb = (c.channel === 'R') ? c.dbvOffsetDbRight : c.dbvOffsetDbLeft;
  // Live scope time/div sizes the captured scope window (~3× the displayed span) so
  // the trace doesn't run out before the window edge (Java ScopeView read length).
  c.scopeTimePerDiv = prefs.oscTimePerDiv.get();
}

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
// scope Save (ScopeTabControl) via the injected getter; the FFT result (latestResult) now lives
// in the FFT pane (fftPane.setResult / getResult), set from engine.onResult after the view
// correction is applied here.
let latestScope = null;
/** Wires the three engine data callbacks - called from init's audio-backend block, since the
 *  engine is constructed there (see `engine`). The render-time FFT spectral corrections stay
 *  applied in app.js BEFORE the result is handed to the pane; the pane owns latestResult + the
 *  dirty flag (Java FftPane.controller last result). */
function bindEngineCallbacks() {
  engine.fft.onResult = (r) => {
    // r.channelLeft is stamped in FftController._emit (Java FftAnalyzerWorker:1872
    // stamps it in the worker off wantLeft - the channel it ACTUALLY read). The .frc
    // de-embed + the predistortion cal pick left()/right() off it (fft-view-correction.js).
    fftViewCorrection.apply(r);
    fftPane.setResult(r);
  };
  engine.scope.onScope = (buf, info) => { latestScope = { buf, info }; };
  // Stop-after-N tripped (Java FFT_RECORDING_AUTO_STOPPED -> FftPane.disengageRecord):
  // the engine paused feeding; the pane tears down the FFT consumer and un-lights the Record LED.
  engine.fft.onFftAutoStopped = () => fftPane.onFftAutoStopped();
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
  try { await engine.fft.setRecording(false); readConfig(); await engine.fft.setRecording(true); }
  finally { fftPane.syncFftLed(); busy = false; }
}

// The FFT SETTINGS STRIP (tile-tabs, window / overlap / averages / threads / coherent /
// align / THD band + harmonics, presets, save / load, calibration, screenshot + ADC
// calibrate) lives in fft/fft-tab-control.js (Java FftTabControl); constructed in init's
// fftTabControl step. The FFT PANE machinery (spectrum canvas / Record LED / readout +
// THD/IMD table / render loop) lives in fft/fft-pane.js (Java FftPane); the strip's host
// composite routes getResult / setResult to that pane (the web FFT pane has no freq/mag
// FlatScrollbars - those Java scrollbars were never ported; the FftView navigates itself).

// The scope SETTINGS STRIP (tile-tabs, channel / filter / trigger controls,
// presets, save / load, ADC calibrate) lives in scope/scope-tab-control.js
// (Java ScopeTabControl); constructed in init's modals step. The scope PANE
// machinery below (canvas / scrollbars / record / render loop) is the strip's
// host - the tab-control reaches it through the narrow host object.

// The scope PANE machinery - the file-mode horizontal nav (scopeOnFileBack), the
// SINGLE-mode Start gate (syncTriggerStart), the measurement-table header buttons +
// pop-out window (setMeasTablePopped / makeMeasWindowDraggable), and the vertical /
// horizontal navigation scrollbars (onVertScrollMoved / onHorizScrollMoved /
// offsetFracBounds / syncOffsetScrollbar) - moved to scope/scope-pane.js (Java
// ScopePane). app.js drives them through the scopePane instance (which IS the
// ScopeTabControl.Host); the strip seeds the controls via scopeTabControl
// .seedScopeControls() and reaches the pane through its public methods.

// ----- L/R channel toggles -----
// Scope has NO channel-enable L/R buttons in its header (Java ScopeView): channel
// enable lives in the Left/Right tab "Enabled" checkbox (#scopeLeftEnable /
// #scopeRightEnable, wired above); the header L/R pair is the measurement-channel
// picker (#scopeMeasL / #scopeMeasR), wired below.
// FFT: mutually-exclusive radio - the analyser sees one channel at a time
// (Java fftChannel = L|R): the switch selects which channel the FFT ANALYZES
// (L -> ch0, R -> ch1). Persist the choice AND push it to the engine so the
// running analyzer reads the selected channel and resets its statistics +
// accumulator (Java FftView:511/514 buttons -> setFftChannel -> fftChannelProperty
// subscription -> resetStatistics). The .frc de-embed then picks the matching
// channel off r.channelLeft (fft-view-correction.js), already done.
$('.fft-pane .lr.l, .fft-pane .lr.r').on('click', function () {
  const isLeft = $(this).hasClass('l');
  $('.fft-pane .lr.l, .fft-pane .lr.r').removeClass('on'); $(this).addClass('on');
  prefs.fftChannel.set(isLeft ? 'L' : 'R');
  engine.fft.setFftChannel(isLeft ? 'L' : 'R');
});
// FreqResp L/R: the plot path consumes loadedFrc.left only; keep visual radio.
$('#tab-fr .lr.l, #tab-fr .lr.r').on('click', function () {
  $('#tab-fr .lr.l, #tab-fr .lr.r').removeClass('on'); $(this).addClass('on');
});

// ----- record LED (per-pane capture toggle) -----
// Independent per-pane Record (Java: the scope and FFT panes each hold their own
// SharedCapture reference; the device opens on the first acquire and closes on the
// last release). The scope LED drives setScopeRecording (moved to ScopePane); the FFT
// LED drives setFftRecording (moved to FftPane) - each lights its OWN LED. The
// generator (#genPlay) is separate. app.js reaches the FFT LED sync through
// fftPane.syncFftLed() (restartFft / restartPreservingConfig).

// The scope SETTINGS STRIP (ScopeTabControl) reaches the scope PANE through the
// scopePane instance, which IS the Host (Java ScopePane implements ScopeTabControl.Host):
// requestRedraw / refreshTiles / refreshFields / syncTriggerStart / setTriggerControlsEnabled
// / syncOffsetScrollbar / redrawScrollbars / syncMeasChannelButtons / syncMeasButtons /
// recState / onFileBack / stopCaptureForFileLoad / onSignalFileLoaded are all its public
// methods. The construction passes `host: scopePane` (see init's scopeTabControl step).

// FFT view header buttons (Java FftView autoSetupBtn / maximizeBtn) - were dead.
$('#fftAutoSetup').on('click', () => fftView.autoSetup());
$('#fftMaximize').on('click', () => fftView.maximize());

// The FFT SETTINGS STRIP (FftTabControl) reaches the FFT PANE + shell helpers through this
// narrow host (mirrors Java FftTabControl.Host) - a COMPOSITE of MIXED ownership: getResult /
// setResult route to the FFT pane (which owns latestResult); applyPrefsToUi / readConfig are
// shell helpers; refreshFreqLabel is the generator pane. The spectrum canvas / Record LED /
// readout / render loop live in fft/fft-pane.js (Java FftPane), reached via the fftPane instance.
const fftHost = {
  getResult: () => fftPane.getResult(),                 // the live analyzed spectrum (Save / ADC-calibrate read it) - owned by the FFT pane
  setResult: (r) => fftPane.setResult(r),               // a loaded .fft spectrum -> repaint next frame (FFT pane state)
  showLoadedBanner: (name) => fftPane.showLoadedBanner(name),   // Java FftView.setSourceFilePath - "Loaded: file" blink
  stopFftRecording: () => fftPane.onRecordingStopRequested(),   // Java FFT_RECORDING_STOP_REQUESTED - stop live record before a .fft load clobbers it
  applyPrefsToUi: () => applyPrefsToUi(),               // preset recall re-seeds the main FFT controls
  refreshFreqLabel: () => genPane.refreshFreqLabel(),   // re-snap label after an FFT-length change
  readConfig: () => readConfig(),                       // window / coherent retune the live analyze call
};

// Scope view header button (#scopeAutoSetup) + Save-name field moved to ScopeTabControl.
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
  for (const f of Object.values(stepFields)) f.refresh();   // re-resolve unit suffixes
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
// nav slider) is host.onSignalFileLoaded below.

// The file-mode trigger-control lock (setScopeTriggerControlsEnabled), the loaded-signal
// view orchestration (scopeOnSignalFileLoaded), the blinking loaded-file banner
// (setScopeFileBanner), the file-mode render + scroll-back (renderLoadedScope /
// refreshScopeFileMode / fileMaxBack / syncHorizScrollbar) and the horizontal nav-bar
// visibility (setScopeHScrollVisible) moved to scope/scope-pane.js (Java ScopePane); the
// tab-control reaches them through the scopePane Host methods.

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

// ----- Scope Utility: screenshot + ADC calibrate (Java buildScreenshotGroup +
// ScreenshotDialog) -----
// The camera button opens the screenshot dialog (Java ScreenshotDialog) with the
// full option set: width / height / keep-aspect / preset / comment / format, plus
// the Copy-to-clipboard and Save-as actions. The desktop re-renders each pane
// offscreen at the chosen resolution; the web mirrors that by CLONING the real
// #scopePane DOM (tabs collapsed) into an offscreen container, then PAINTING the
// laid-out clone (HTML chrome at its own px size + the live trace canvas BITMAPS
// scaled to fill) onto a 2d canvas at the requested W×H and stamping the comment.

// Screenshot comment caption top y (px), matching the scope pane's
// screenshotCommentTopPx() - the scope does NOT override AbstractPane's
// DEFAULT_COMMENT_TOP_PX (40), so the caption sits 40 px down (C20c).
const SCOPE_SCREENSHOT_COMMENT_TOP_PX = 40;

// Fallback native size for the screenshot, used only before the live #scopePane has
// a measurable CSS box (e.g. the pane was never shown).
const SHOT_SCOPE_W = 1200;
const SHOT_SCOPE_H = 360;

// ----- scope screenshot via cloned-DOM + manual canvas composite -----
// USER ALGORITHM: build the screenshot from a CLONE of the REAL #scopePane DOM with
// the settings tabs COLLAPSED (clonePaneForShot lays a clone out, attached, at the
// TARGET W×H so the flex column reflows to the requested aspect; the scope-specific
// edits live in scopePrep), then PAINT that laid-out clone onto a 2d <canvas> by
// walking the tree in DOM order and drawing each element off its own
// getBoundingClientRect + getComputedStyle (paintCloneToCanvas). Both clonePaneForShot
// and paintCloneToCanvas are GENERIC - the same pair will capture the FFT and FreqResp
// panes (each with its own prep + live-canvas mapper); composeScopePaneShot is the thin
// scope wrapper.
// Every chrome element (.lr-tools buttons + icons, the tile-tab strip, edge labels,
// slider lines) is drawn at its OWN css-pixel size - it does NOT scale with the target
// and is never multiplied by the display pixel density. ONLY the #scope / #scopeZoomed
// trace canvases scale: their LIVE on-screen bitmaps are blitted into their reflowed
// rect, so whatever is on screen (live, held/frozen, or loaded) is captured EXACTLY and
// the trace fills the requested box. No html2canvas, no SVG image embedding, no network.

// Live scope canvas's CSS box (the on-screen aspect the screenshot defaults to).
function liveScopePaneSize() {
  const pane = document.getElementById('scopePane');
  const w = (pane && pane.clientWidth) || SHOT_SCOPE_W;
  // The pane is shorter on screen while a settings tab is expanded; the collapsed
  // capture is taller. Use the pane's own height when it has one, else the fallback.
  const h = (pane && pane.clientHeight) || SHOT_SCOPE_H;
  return { w, h };
}

/** Scope-specific prep for clonePaneForShot: collapses the expanded settings tab,
 *  removes the pane header / popped-out measurement window / record LED, and drops the
 *  scrollbar canvases (the vertical bar entirely, the horizontal bar -> a 2px black
 *  spacer = the wanted scope↔zoomed gap) so the trace fills the full width with no
 *  reserved gutter. The #scope / #scopeZoomed canvases are KEPT in place - paint maps
 *  them to the matching LIVE canvas by id and blits the live bitmap into their rect. */
function scopePrep(clone) {
  // Collapse the settings tabs: a .tab-panel shows only with .show, so dropping it
  // gives the canvas-wrap (flex:1) the full height. Keep the collapsed tile strip.
  clone.querySelectorAll('.tab-panel.show').forEach((p) => p.classList.remove('show'));
  // The pane header ("Oscilloscope ▼") is chrome the capture doesn't want.
  const hdr = clone.querySelector('.pane-header'); if (hdr) hdr.remove();
  // Hide the floating popped-out measurement window (it overlays absolutely and is
  // not part of the static capture; the in-canvas table is already in the bitmap).
  const win = clone.querySelector('#scopeMeasWindow'); if (win) win.style.display = 'none';
  // The record LED button is live-only chrome - not wanted on the static capture.
  const cLed = clone.querySelector('.led-btn'); if (cLed) cLed.remove();
  // Keep the scrollbars exactly as on screen: the VERTICAL scrollbar stays (its live
  // bitmap/thumb is blitted by id through liveCanvasFor), and the HORIZONTAL scrollbar
  // gutter stays reserved - the hscroll is visibility:hidden until a file is loaded, so
  // its gutter SPACE is always reserved and the bar (+ thumb) is painted only in file
  // mode. The zoomed-wrap keeps its right padding so it lines up under the v-scrollbar.
  // C29b loaded-filename static label, top-right: shown only in file mode. The cloned
  // #scopeLoadedPath already carries the live text; nothing to add.
}

/** Thin scope-specific wrapper over the GENERIC clonePaneForShot + paintCloneToCanvas:
 *  composes the scope-pane screenshot at EXACTLY `outW`×`outH` px (or the live pane's
 *  native CSS size when omitted). Maps each clone <canvas> to its live source by id
 *  (#scope -> live #scope, #scopeZoomed -> live #scopeZoomed). Returns a Promise<canvas>
 *  of exactly that size; renderScopeShot's watermark + caption draw on top. */
async function composeScopePaneShot(outW, outH) {
  const live = liveScopePaneSize();
  const w = outW > 0 ? outW : live.w;   // the file IS exactly this size
  const h = outH > 0 ? outH : live.h;
  // "Enlarge elements" dpr fix: the output file is EXACTLY w×h, but on a magnified screen
  // (125% -> dpr 1.25) the chrome must be drawn at DEVICE size to match the live on-screen
  // look. So lay the clone out SMALLER (w/dpr) - chrome sits at its CSS px there - and
  // paintCloneToCanvas scales the whole paint up by dpr to fill the w×h canvas (chrome
  // ×dpr = device size, the trace fills the rest). At 100% (dpr 1) clone == canvas, no scale.
  const dpr = window.devicePixelRatio || 1;
  const cw = Math.max(1, Math.round(w / dpr));
  const ch = Math.max(1, Math.round(h / dpr));
  const clone = clonePaneForShot(document.getElementById('scopePane'), cw, ch, scopePrep);
  const icons = await preloadCloneIcons(clone);   // .lr-tools SVG icons, decoded for drawImage
  // Map a clone <canvas> to its matching LIVE on-screen bitmap by id.
  const liveCanvasFor = (cloneCanvas) => document.getElementById(cloneCanvas.id);
  return paintCloneToCanvas(clone, w, h, liveCanvasFor, icons);
}

/** Native (on-screen) size of the scope pane, seeded into the W/H fields. */
function shotNativeSize() {
  const s = liveScopePaneSize();
  return { w: s.w, h: s.h };
}

/** Persists the chosen screenshot size to this pane's prefs so the dialog re-seeds it
 *  next time (Save + Copy both call it). Scope uses scopeScreenshotWidth/Height; the FFT /
 *  FreqResp screenshots use their own keys with the same pattern. */
function persistShotSize(w, h) {
  if (!(w > 0) || !(h > 0)) return;
  prefs.scopeScreenshotWidth.set(w);
  prefs.scopeScreenshotHeight.set(h);
  prefs.save();
}

/** Renders the FULL scope pane (collapsed tabs) at {@code w}×{@code h} via the
 *  clone-DOM + manual canvas composite, stamping {@code comment} (when non-blank) at
 *  the pane's screenshotCommentTopPx vertical offset in the top-right - mirroring
 *  ScreenshotDialog's caption. Returns a {@code Promise<Blob>} in {@code mime}. */
async function renderScopeShot(comment, w, h, mime) {
  // The clone is painted directly at the target W×H (same proportions as the
  // composed pane), so the output canvas IS the screenshot - no extra resample.
  const out = await composeScopePaneShot(w, h);
  const ctx = out.getContext('2d');
  // composeScopePaneShot left the paintCloneToCanvas ctx.scale(dpr) on the context; reset
  // to identity so the watermark + comment below are placed in REAL output pixels (else
  // they land at coord×dpr, off-centre and off-screen on a magnified display).
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  // Centered "Phonalyser.web" brand watermark (Java ScreenshotOverlay): font sized so
  // the text WIDTH is ~1/2 of the image width; semi-transparent grey (Java alpha
  // 77/255 ≈ 0.30, faded a further 10%). Centred on the SCOPE VIEW (the live #scope canvas's output rect), not
  // the whole pane, so it sits on the trace. Drawn before the comment (caption on top).
  {
    const WM = 'Phonalyser.web';
    const sr = out.scopeRect;
    const wcx = sr ? sr.left + sr.width / 2 : out.width / 2;
    const wcy = sr ? sr.top + sr.height / 2 : out.height / 2;
    ctx.save();
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    let wmPt = 200;
    ctx.font = `700 ${wmPt}px "Segoe UI", system-ui, sans-serif`;
    const measured = ctx.measureText(WM).width || 1;
    wmPt = Math.max(1, Math.round(wmPt * (out.width * 0.35) / measured));
    ctx.font = `700 ${wmPt}px "Segoe UI", system-ui, sans-serif`;
    ctx.fillStyle = 'rgba(160,160,160,0.243)';
    ctx.fillText(WM, wcx, wcy);
    ctx.restore();
  }
  const text = (comment || '').trim();
  if (text) {
    // Scale the caption + its top offset with the image so it stays legible at any
    // output size; the offset = the pane's screenshotCommentTopPx (40, C20c).
    const native = liveScopePaneSize();
    const sy = h / native.h;
    const pt = Math.max(12, Math.round(16 * sy));
    ctx.font = pt + 'px Consolas, monospace';
    ctx.textAlign = 'right';
    ctx.textBaseline = 'top';
    // ~30px right margin clears the vertical scrollbar (kept in the capture, ~20px wide
    // on the right edge) so the caption never sits under it.
    const x = out.width - 30, y = Math.round(SCOPE_SCREENSHOT_COMMENT_TOP_PX * sy);
    ctx.lineWidth = 3;
    ctx.strokeStyle = 'rgba(0,0,0,0.85)';
    ctx.strokeText(text, x, y);
    ctx.fillStyle = '#F0F0F0';
    ctx.fillText(text, x, y);
  }
  return new Promise((resolve) => out.toBlob(resolve, mime || 'image/png'));
}

// ----- FFT screenshot via the SAME cloned-DOM + canvas composite (Java FFT_SCREENSHOT_REQUESTED
// -> ScreenshotDialog). The generic clonePaneForShot + paintCloneToCanvas pair used by the scope above
// captures the FFT pane too; fftPrep is the FFT-specific prep, the live #spec canvas is mapped by id.
// Replaces the old raw-#spec-PNG-export shortcut so the FFT camera opens the composited dialog. -----
function liveFftPaneSize() {
  const pane = document.getElementById('fftPane');
  const w = (pane && pane.clientWidth) || SHOT_SCOPE_W;
  const h = (pane && pane.clientHeight) || SHOT_SCOPE_H;
  return { w, h };
}

/** FFT-specific prep for clonePaneForShot (mirror of scopePrep): collapse the expanded settings
 *  tab, drop the pane header / record LED / predistortion button (live-only chrome). #spec is KEPT
 *  and mapped to its live bitmap by id. */
function fftPrep(clone) {
  clone.querySelectorAll('.tab-panel.show').forEach((p) => p.classList.remove('show'));
  const hdr = clone.querySelector('.pane-header'); if (hdr) hdr.remove();
  const led = clone.querySelector('.led-btn'); if (led) led.remove();
  const pd = clone.querySelector('#predistBtn'); if (pd) pd.remove();
}

/** Thin FFT wrapper over the GENERIC clonePaneForShot + paintCloneToCanvas (mirror of
 *  composeScopePaneShot): composes the FFT pane at EXACTLY outW×outH, mapping #spec -> live #spec. */
async function composeFftPaneShot(outW, outH) {
  const live = liveFftPaneSize();
  const w = outW > 0 ? outW : live.w;
  const h = outH > 0 ? outH : live.h;
  const dpr = window.devicePixelRatio || 1;
  const cw = Math.max(1, Math.round(w / dpr));
  const ch = Math.max(1, Math.round(h / dpr));
  const clone = clonePaneForShot(document.getElementById('fftPane'), cw, ch, fftPrep);
  const icons = await preloadCloneIcons(clone);
  const liveCanvasFor = (cloneCanvas) => document.getElementById(cloneCanvas.id);
  return paintCloneToCanvas(clone, w, h, liveCanvasFor, icons);
}

/** Native (on-screen) size of the FFT pane, seeded into the dialog's W/H fields. */
function fftNativeSize() {
  const s = liveFftPaneSize();
  return { w: s.w, h: s.h };
}

/** Renders the FULL FFT pane (collapsed tabs) at w×h via the clone-DOM composite, stamping the
 *  brand watermark + caption (mirror of renderScopeShot). Returns Promise<Blob>. */
async function renderFftShot(comment, w, h, mime) {
  const out = await composeFftPaneShot(w, h);
  const ctx = out.getContext('2d');
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  {
    const WM = 'Phonalyser.web';
    const sr = out.scopeRect;
    const wcx = sr ? sr.left + sr.width / 2 : out.width / 2;
    const wcy = sr ? sr.top + sr.height / 2 : out.height / 2;
    ctx.save();
    ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
    let wmPt = 200;
    ctx.font = `700 ${wmPt}px "Segoe UI", system-ui, sans-serif`;
    const measured = ctx.measureText(WM).width || 1;
    wmPt = Math.max(1, Math.round(wmPt * (out.width * 0.35) / measured));
    ctx.font = `700 ${wmPt}px "Segoe UI", system-ui, sans-serif`;
    ctx.fillStyle = 'rgba(160,160,160,0.243)';
    ctx.fillText(WM, wcx, wcy);
    ctx.restore();
  }
  const text = (comment || '').trim();
  if (text) {
    const native = liveFftPaneSize();
    const sy = h / native.h;
    const pt = Math.max(12, Math.round(16 * sy));
    ctx.font = pt + 'px Consolas, monospace';
    ctx.textAlign = 'right'; ctx.textBaseline = 'top';
    const x = out.width - 30, y = Math.round(SCOPE_SCREENSHOT_COMMENT_TOP_PX * sy);
    ctx.lineWidth = 3; ctx.strokeStyle = 'rgba(0,0,0,0.85)';
    ctx.strokeText(text, x, y);
    ctx.fillStyle = '#F0F0F0'; ctx.fillText(text, x, y);
  }
  return new Promise((resolve) => out.toBlob(resolve, mime || 'image/png'));
}

// ----- FreqResp screenshot via the SAME cloned-DOM + canvas composite (Java
// FreqRespTabControl -> screenshotPane.openScreenshotDialog). Reuses the generic
// clonePaneForShot + paintCloneToCanvas pair; frPrep is the FreqResp-specific prep, the
// live #frPlot canvas is mapped by id. Replaces the old raw-#frPlot-PNG download so the
// FreqResp camera opens the composited dialog exactly like the scope / FFT panes. -----
function liveFreqRespPaneSize() {
  const pane = document.getElementById('freqRespPane');
  const w = (pane && pane.clientWidth) || SHOT_SCOPE_W;
  const h = (pane && pane.clientHeight) || SHOT_SCOPE_H;
  return { w, h };
}

/** FreqResp-specific prep for clonePaneForShot (mirror of fftPrep): collapse the expanded
 *  settings tab and drop the pane header. #frPlot is KEPT and mapped to its live bitmap by
 *  id; the frFreqScroll / frMagScroll gutter canvases stay as on screen. */
function frPrep(clone) {
  clone.querySelectorAll('.tab-panel.show').forEach((p) => p.classList.remove('show'));
  const hdr = clone.querySelector('.pane-header'); if (hdr) hdr.remove();
}

/** Thin FreqResp wrapper over the GENERIC clonePaneForShot + paintCloneToCanvas (mirror of
 *  composeFftPaneShot): composes the FreqResp pane at EXACTLY outW×outH, mapping every clone
 *  canvas (#frPlot + scrollbars) to its live source by id. */
async function composeFreqRespPaneShot(outW, outH) {
  const live = liveFreqRespPaneSize();
  const w = outW > 0 ? outW : live.w;
  const h = outH > 0 ? outH : live.h;
  const dpr = window.devicePixelRatio || 1;
  const cw = Math.max(1, Math.round(w / dpr));
  const ch = Math.max(1, Math.round(h / dpr));
  const clone = clonePaneForShot(document.getElementById('freqRespPane'), cw, ch, frPrep);
  const icons = await preloadCloneIcons(clone);
  const liveCanvasFor = (cloneCanvas) => document.getElementById(cloneCanvas.id);
  return paintCloneToCanvas(clone, w, h, liveCanvasFor, icons);
}

/** Native (on-screen) size of the FreqResp pane, seeded into the dialog's W/H fields. */
function freqRespNativeSize() {
  const s = liveFreqRespPaneSize();
  return { w: s.w, h: s.h };
}

/** Renders the FULL FreqResp pane (collapsed tabs) at w×h via the clone-DOM composite,
 *  stamping the brand watermark + caption (mirror of renderFftShot). Returns Promise<Blob>. */
async function renderFreqRespShot(comment, w, h, mime) {
  const out = await composeFreqRespPaneShot(w, h);
  const ctx = out.getContext('2d');
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  {
    const WM = 'Phonalyser.web';
    const sr = out.scopeRect;
    const wcx = sr ? sr.left + sr.width / 2 : out.width / 2;
    const wcy = sr ? sr.top + sr.height / 2 : out.height / 2;
    ctx.save();
    ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
    let wmPt = 200;
    ctx.font = `700 ${wmPt}px "Segoe UI", system-ui, sans-serif`;
    const measured = ctx.measureText(WM).width || 1;
    wmPt = Math.max(1, Math.round(wmPt * (out.width * 0.35) / measured));
    ctx.font = `700 ${wmPt}px "Segoe UI", system-ui, sans-serif`;
    ctx.fillStyle = 'rgba(160,160,160,0.243)';
    ctx.fillText(WM, wcx, wcy);
    ctx.restore();
  }
  const text = (comment || '').trim();
  if (text) {
    const native = liveFreqRespPaneSize();
    const sy = h / native.h;
    const pt = Math.max(12, Math.round(16 * sy));
    ctx.font = pt + 'px Consolas, monospace';
    ctx.textAlign = 'right'; ctx.textBaseline = 'top';
    const x = out.width - 30, y = Math.round(SCOPE_SCREENSHOT_COMMENT_TOP_PX * sy);
    ctx.lineWidth = 3; ctx.strokeStyle = 'rgba(0,0,0,0.85)';
    ctx.strokeText(text, x, y);
    ctx.fillStyle = '#F0F0F0'; ctx.fillText(text, x, y);
  }
  return new Promise((resolve) => out.toBlob(resolve, mime || 'image/png'));
}
// The scope ADC-calibrate handler (#scopeCalibrate) + its enable gate
// (syncCalibrateEnabled) moved to ScopeTabControl; the render loop drives the gate
// via scopeTabControl.syncCalibrateEnabled().

// The FFT Presets / Utility (screenshot + ADC calibrate) / Save / Load / Load-calibration
// handlers moved to fft/fft-tab-control.js (Java FftTabControl): presets recall re-seeds the
// main FFT controls via host.applyPrefsToUi; the loaded .fft spectrum flows back via
// host.setResult; the .frc load mutates the shared fftCorrectionStore, read by both the
// render-time FFT de-embed (fftViewCorrection) and the predistortion engine's calResponseAt.

// ============================ Frequency response ============================
// Takes the engine at construction -> built in init's audio-backend block (see `engine`).
let freqRespPane;

// ============================ Predistortion wizard ============================
let predistModal;

// Restart preserving the (predistortion-mutated) engine.config - does NOT re-read
// the UI, so configureForRun's coherent/∞-averaging settings survive.
async function restartPreservingConfig() {
  if (!engine.running) return;
  // Predistortion drives the full pipeline (generator + FFT analysis + FLL). Bounce
  // both via the fused convenience start/stop so configureForRun's coherent/∞-averaging
  // window takes effect; the engine's internal lifecycles are independent; the
  // controllers own the running state so the FFT view keeps rendering.
  await engine.stop();
  await engine.startGenerator();
  await engine.scope.setRecording(true);
  await engine.fft.setRecording(true);
  $('#genPlay').addClass('playing').attr('title', t('generator.play.stop'));
  genPane.setOnAir(true);   // ...and re-arms the pane's ON-AIR tick (the lane-death consult)
  scopePane.syncScopeLed(); fftPane.syncFftLed();
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
  engine = new AudioEngine({ prefs, qa40xManager, qa40xFinder, loopbackManager,
    netManager: netManagerFacade(() => netManager) });
  fftViewCorrection = new FftViewCorrection(engine.config, fftCorrectionStore);
  fftView = new FftView(document.getElementById('spec'), { prefs, genActive: () => engine.generator.running, correction: fftViewCorrection });
  bindEngineCallbacks();
  freqRespPane = new FreqRespPane(engine, prefs, { saveFile, openFile, bytesToText, showAlert });
  predistHost = new PredistortionHost(engine, prefs, {
    getResult: () => fftPane.getResult(),
    restart: restartPreservingConfig,
    // Read the FFT store LIVE - the predistortion engine reads correctionEntries on demand, so a
    // captured array snapshot would go stale as the calibration rows change.
    get correctionEntries() { return fftCorrectionStore.getEntries(); },
  });
  step('initSelects', initSelects);
  // Generator pane (Java GeneratorPane): the signal-form combo + freq/amp/duty/dual-tone/
  // sweep/dither/snap/.dpd controls + Play/ON-AIR + Save-to + file player. Constructed before
  // applyPrefsToUi so its seedGeneratorControls() runs in the init seed; reaches the SHELL
  // state (the busy guard + engine.generator.running) + readConfig + the FFT align combo through the injected
  // closures, and the generator step fields (built later in initStepFields) via getField.
  step('genPane', () => {
    genPane = new GeneratorPane(engine, prefs, {
      getField: (id) => stepFields[id],
      io: { pickSaveTarget, writeToTarget, saveScopeCapture, readWav, readAiff, decodeFlac },
      WAV_TYPE, formLabel, formIcon, sfVal, outRate,
      isGenRunning: () => engine.generator.running,
      isBusy: () => busy, setBusy: (v) => { busy = v; },
      readConfig, syncFftAlign: () => { if (fftTabControl) fftTabControl.syncAlign(); },
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
  step('initStepFields', initStepFields);   // after applyI18n so unit suffixes resolve
  // Unified ADC/DAC calibration dialog (Java CalibrationDialog): built after initStepFields (its
  // two per-channel fields exist now) so the scope / FFT strips can call openAdc and its bind()
  // can wire the generator #calibrateDac button. The current-device LABEL comes from the prefs
  // dialog's device <select> (recognition patterns match the label, not the deviceId value).
  step('calibrationDialog', () => {
    calibrationDialog = new CalibrationDialog(engine, prefs, deviceStore, {
      getLeftField: () => stepFields.calLeftValue,
      getRightField: () => stepFields.calRightValue,
      inputLabel: inputDeviceLabel,
      outputLabel: () => (prefsDialog ? prefsDialog.outputDeviceLabel() : $('#outSel option:selected').text()),
      // Null for every LOCAL selection, which is what keeps a local calibrate writing where it
      // always did. Asked per calibrate, not held: the seam is built with the Preferences dialog
      // (below), and the selected backend changes under this dialog anyway.
      bench: () => (benchCalibration && benchCalibration.remote() != null ? benchCalibration : null),
      // A DAC calibration written to a BENCH reaches the tone only at the lane's next open - the
      // server converts volts with the card it read there (see the dialog's _applyToLiveTone) - so
      // the dialog re-opens it. The generator pane owns the restart (busy guard + readConfig) and
      // it is a no-op while nothing is playing.
      restartGenerator: () => genPane.restartGenerator(),
      // The shell's one alert surface - the same one the card section's refused copy uses.
      showAlert,
    }).bind();
  });
  // Oscilloscope PANE (Java ScopePane): the trace canvas wiring, the two nav scrollbars,
  // the Record LED, the measurement table + pop-out, the file-mode load/scroll, and the
  // scope branch of the rAF loop (render()). Built BEFORE the scope settings strip, since
  // the strip's host IS this pane (Java ScopePane implements ScopeTabControl.Host). Reaches
  // the shell state (the busy guard + engine.scope.recording) + readConfig + the latest scope frame
  // through the injected closures; the scope V/T/hyst NumericStepFields (from initStepFields)
  // via getField; the ScopeView + tileChips injected too. bind() wires the Record LED +
  // measurement buttons + resize observer.
  step('scopePane', () => {
    scopePane = new ScopePane(engine, prefs, {
      view: scopeView, getField: (id) => stepFields[id], tileChips,
      isScopeRec: () => engine.scope.recording,
      isBusy: () => busy, setBusy: (v) => { busy = v; },
      getLatestScope: () => latestScope, readConfig,
      syncCalibrateGate: () => { if (scopeTabControl) scopeTabControl.syncCalibrateEnabled(); },
    }).bind();
  });
  // Oscilloscope settings strip (Java ScopeTabControl): its handlers + presets +
  // save/load + ADC-calibrate. Built after initStepFields (the scope V/T/hyst
  // NumericStepFields it reaches via getField exist now). Reaches the scope PANE
  // (canvas / scrollbars / record / render loop) through the scopePane Host.
  step('scopeTabControl', () => {
    scopeTabControl = new ScopeTabControl(engine, prefs, {
      host: scopePane, view: scopeView, getField: (id) => stepFields[id],
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
  // strip, since the strip's host routes getResult / setResult to this pane (Java
  // FftTabControl.Host). Reaches the shell state (the busy guard + engine.fft.recording) + readConfig through
  // the injected closures; the FftView + tileChips injected too. bind() wires the Record LED.
  step('fftPane', () => {
    fftPane = new FftPane(engine, prefs, {
      view: fftView, tileChips,
      isFftRec: () => engine.fft.recording,
      isBusy: () => busy, setBusy: (v) => { busy = v; },
      readConfig,
    }).bind();
  });
  // MAIN TAB (Java MultifunctionalTab): the rAF render-frame driver + the 3-pane collapse/sash
  // layout. Built after BOTH panes exist; start() kicks the requestAnimationFrame loop (which
  // drives scopePane.render() + fftPane.render() each frame, each pane self-gated on its OWN
  // record state), and initLayout() wires the collapsible panes + draggable sashes + the
  // pane-weight / collapse-state persistence (it owns NO lifecycle flags - those stay in app.js).
  step('mainTab', () => {
    mainTab = new MainTab({ scopePane, fftPane, prefs });
    mainTab.start();
    mainTab.initLayout();
  });
  // FFT settings strip (Java FftTabControl): its handlers + presets + save/load +
  // calibration + screenshot/ADC-calibrate. Built after initStepFields (the FFT
  // manual-fundamental NumericStepField it reaches via getField exists now). Reaches the
  // FFT PANE (canvas / Record LED / readout / render loop) through the narrow `host` -
  // getResult / setResult route to the fftPane built above.
  step('fftTabControl', () => {
    fftTabControl = new FftTabControl(engine, prefs, {
      host: fftHost, fftView, fftViewCorrection, store: fftCorrectionStore, getField: (id) => stepFields[id],
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
    // Wire the shared screenshot dialog to the scope pane: the generic compositor + dialog
    // live in screenshot.js; this injects the scope-specific render / native-size / persisted
    // size so the SAME dialog will later serve the FFT + FreqResp panes. Constructed here (not
    // at module top) so the shotModal instance above is already live.
    const shotDialog = new ScreenshotDialog({
      modal: shotModal, openBtn: '#scopeShot', renderShot: renderScopeShot, nativeSize: shotNativeSize,
      seedSize: () => ({ w: prefs.scopeScreenshotWidth.get(), h: prefs.scopeScreenshotHeight.get() }),
      persistSize: persistShotSize, saveFile, status: (m) => $('#status').text(m),
    });
    shotDialog.bind();
    // FFT pane shares the SAME dialog: its own renderShot + native-size seed. The chosen
    // size persists PER VIEW independently: the FFT has its own fftScreenshotWidth/Height prefs
    // (the scope has scopeScreenshotWidth/Height, FreqResp freqRespScreenshotWidth/Height); seeds
    // from the live pane size until a size was chosen once.
    shotDialog.addPane({
      openBtn: '#fftShot', renderShot: renderFftShot, nativeSize: fftNativeSize,
      seedSize: () => {
        const w = prefs.fftScreenshotWidth.get(), h = prefs.fftScreenshotHeight.get();
        return (w > 0 && h > 0) ? { w, h } : fftNativeSize();
      },
      persistSize: (w, h) => {
        if (!(w > 0) || !(h > 0)) return;
        prefs.fftScreenshotWidth.set(w); prefs.fftScreenshotHeight.set(h); prefs.save();
      },
    });
    // FreqResp pane shares the SAME dialog (Java FreqRespTabControl -> openScreenshotDialog):
    // its own renderShot + native-size seed, with its OWN freqRespScreenshotWidth/Height keys.
    shotDialog.addPane({
      openBtn: '#frShot', renderShot: renderFreqRespShot, nativeSize: freqRespNativeSize,
      seedSize: () => {
        const w = prefs.freqRespScreenshotWidth.get(), h = prefs.freqRespScreenshotHeight.get();
        return (w > 0 && h > 0) ? { w, h } : freqRespNativeSize();
      },
      persistSize: (w, h) => {
        if (!(w > 0) || !(h > 0)) return;
        prefs.freqRespScreenshotWidth.set(w); prefs.freqRespScreenshotHeight.set(h); prefs.save();
      },
    });
    // Predistortion wizard (Java PredistortionWizardDialog): the live UI half. The host wiring +
    // generator restart stay in app.js (generator-pane concerns) and are injected.
    new PredistortionWizard(engine, prefs, predistHost, {
      modal: predistModal, getResult: () => fftPane.getResult(), saveFile,
      onApply: (applyForm, name) => {
        prefs.genSignalForm.set(applyForm); prefs.setGenDpd(applyForm, name); prefs.save();
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
    // The once-per-RUN offer register: an operator who declined the copy must not be asked again
    // the next time they open Preferences, which is exactly when they would be looking at that
    // device. Held for the app's lifetime, not the dialog's, and never persisted.
    const copyOffers = new CalibrationCopyOffers();
    // The selected backend's value while it names one of a server's backends, else null - the ONE
    // test that decides whether the card combo shows a bench's cards or this machine's (Java
    // BackendKey.remote()).
    const benchValue = () => (remoteBackendOf(prefs.backend.get()) != null ? prefs.backend.get() : null);
    // The catalogue ref behind what the device combo SHOWS. The card seam is handed the option
    // TEXT and the commit below passes a staged device NAME, and the input combo appends a rate
    // to its text - so the one test that knows all three spellings does the matching
    // (namesNetDevice). It used to compare `description || name`, which matched no input device
    // at all once the text carried anything else.
    const benchRef = (input, device) => {
      if (!netManager || !device) return null;
      const refs = input ? netManager.listInputDevices() : netManager.listOutputDevices();
      return refs.find((r) => namesNetDevice(r, device)) || null;
    };
    // The bench's id - the mirror's key half. The connection's own is authoritative (the server
    // names itself in hello); the remembered entry covers the moment before it answered.
    const benchServerId = () => {
      if (!netManager) return null;
      const connection = netManager.connection;
      if (connection && connection.serverId) return connection.serverId;
      const server = netManager.getConnectedServer();
      return server ? server.serverId : null;
    };
    const cardSource = {
      cards: async (input, device) => {
        const value = benchValue();
        if (value == null) return null;   // a LOCAL selection - this machine's cards, unchanged
        const ref = benchRef(input, device);
        const name = ref ? ref.name : device;
        const staged = name ? benchCards.stagedCard(value, input, name) : null;
        return {
          names: await benchCards.list(value, input),
          bound: staged != null ? staged : benchCards.boundCard(benchServerId(), ref, name),
        };
      },
      stage: (input, device, cardName) => {
        const value = benchValue();
        if (value == null) return;
        const ref = benchRef(input, device);
        benchCards.stage(value, input, ref ? ref.name : device, cardName);
      },
      // Spec 4.3's `cal`: what the SERVER stores for the device, which is what a measurement on it
      // is scaled by - null when it has none, and only then does the local card store get a say.
      // UNDEFINED when this bench does not offer the device at all (or none is remote): there is
      // then nothing to copy TO, which is a different answer from "it has none" and the copy
      // offer has to tell them apart (Java tests `ref == null` and `ref.calibration() != null`
      // separately).
      calibration: (input, device) => {
        const ref = benchValue() == null ? null : benchRef(input, device);
        return ref ? ref.calibration : undefined;
      },
      // Asked once per run per bench + direction + device - but only a SETTLED
      // question is remembered, so a wire glitch does not cost the operator the offer.
      mayCopyAsk: (input, device) => {
        const value = benchValue();
        if (value == null) return false;
        const ref = benchRef(input, device);
        return copyOffers.mayAsk(value, input, ref ? ref.name : device);
      },
      settleCopyAsk: (input, device) => {
        const value = benchValue();
        if (value == null) return;
        const ref = benchRef(input, device);
        copyOffers.settle(value, input, ref ? ref.name : device);
      },
      // The YES branch: this machine's WHOLE card becomes the bench's - cards.put + the
      // binding - with its values alone as the fallback when the bench will not take the card.
      // Answers a BenchCards.Copied outcome, which the section turns into what it tells the
      // operator; nothing of this is written into this machine's storage.
      copyCalibration: async (input, device) => {
        const value = benchValue();
        if (value == null) return BenchCards.Copied.NOTHING;
        const ref = benchRef(input, device);
        return benchCards.propagateLocalCard(value, benchServerId(), ref, device, input);
      },
      commit: async (input) => {
        const value = benchValue();
        if (value == null) return [];
        const refused = [];
        for (const [name, cardName] of benchCards.stagedFor(value, input)) {
          const bound = await benchCards.bind(value, benchServerId(), benchRef(input, name), cardName);
          if (!bound) refused.push(cardName);
        }
        return refused;
      },
      clearStaged: () => benchCards.clearStaged(),
    };
    // Where the ADC/DAC calibrate dialog's result is WRITTEN while a server's backend is selected
    // (Java gui/sound/CalibrationStore): the device is plugged into the BENCH, so its full scale
    // is the bench's to keep - spec 4.3 device.setCalibration, under that device's lock. The
    // dialog asks for this seam per calibrate and writes locally whenever it answers null.
    benchCalibration = {
      remote: benchValue,
      // Whether the named selection would be measured with no real calibration in this direction
      // (Java CalibrationStore.isUncalibrated). The BENCH's own answer first - a device on a
      // server is calibrated there, and this machine's card of the same name describes a
      // different exemplar of that model - then this machine's card store. A bench device the
      // catalogue does not carry at all is uncalibrated too: nothing is left to vouch for it.
      uncalibrated: (input, device) => {
        if (benchValue() == null) return deviceStore.isUncalibrated(device, input);
        const ref = benchRef(input, device);
        return ref == null || ref.calibration == null;
      },
      send: async (input, device, fsRmsLeft, fsRmsRight) => {
        const value = benchValue();
        if (value == null) return false;
        return benchCards.calibrate(value, benchRef(input, device), fsRmsLeft, fsRmsRight);
      },
    };
    // Preferences dialog (Java PreferencesDialog): staged audio/L&F/Osc/FFT/FR prefs, commit on OK.
    prefsDialog = new PreferencesDialog(engine, prefs, {
      modal: prefsModal, RATES, stepFields, inRate, outRate,
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
      // An audio-settings change invalidates everything accumulated at the old settings, exactly as
      // a re-calibration does - so the OK path reuses the calibration reset: clear the scope's
      // measurement history and restart the FFT's cross-tick average.
      resetStatistics: onCalChange,
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
 