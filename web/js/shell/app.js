/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// App entry: wires the native-styled Bootstrap/jQuery chrome to the live FFT
// engine and renders the scope + spectrum Canvas views. First runnable slice;
// the remaining blueprint modules layer on around this engine.

import { AudioEngine } from '../audio/backend.js';
import { FftViewCorrection } from '../fft/fft-view-correction.js';
import { DacCalibrationDialog } from '../generator/dac-calibration-dialog.js';
import { FftView } from '../ui/fft-view.js';
import { ScopeView } from '../ui/scope-view.js';
import { GenSignalForm, isDualTone } from '../generator/dds-kernel.js';
import { Preferences } from '../store/preferences.js';
import { t, initBase, setLocale } from '../i18n/i18n.js';
import { LOCALES } from '../i18n/locales.js';
import { WavWriter, AiffWriter, readWav, readAiff } from '../io/wav.js';
import { saveScopeCapture, saveStreaming, findFullPeriodWindow, formatForName } from '../io/scope-capture.js';
import { encodeFlac, decodeFlac } from '../io/flac.js';
import { saveSpectrum, loadSpectrum } from '../io/fft-spectrum.js';
import { loadFrc } from '../io/frc.js';
import { saveFile, openFile, bytesToText, pickSaveTarget, writeToTarget } from '../io/file-picker.js';
import { FreqRespHost } from './freqresp-host.js';
import { ScreenshotDialog } from './screenshot.js';
import { clonePaneForShot, preloadCloneIcons, paintCloneToCanvas } from './screenshot.js';
import { PredistortionHost } from './predistortion-host.js';
import { PredistortionWizard } from './predistortion-wizard.js';
import { PreferencesDialog } from './preferences-dialog.js';
import { MainTab } from './main-tab.js';
import { ScopePane } from '../scope/scope-pane.js';
import { ScopeTabControl } from '../scope/scope-tab-control.js';
import { preserveCanvasMiddle } from '../scope/scope-format.js';
import { FftPane } from '../fft/fft-pane.js';
import { FftTabControl } from '../fft/fft-tab-control.js';
import { GeneratorPane } from '../generator/generator-pane.js';
import { PredistortionEngine } from '../predistortion/engine.js';
import { writeHarmonicDpd, writeIntermodDpd } from '../io/dpd.js';
import { NumericStepField, NumericStepModel, UNIT_FAMILIES } from '../widgets/numeric-step-field.js';

const $ = window.jQuery;
const prefs = Preferences.instance();  // load() runs in the constructor
const RATES = [8000, 11025, 16000, 22050, 32000, 44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000, 705600, 768000];
// GenSignalForm token → localized display label for the #signalForm select.
const formLabel = (form) => t(`generator.signalForm.${form}`);
// GenSignalForm token → per-form waveform pictogram (web/assets/icons/signal-<kebab>.svg).
const formIcon = (form) => `assets/icons/signal-${form.toLowerCase().replace(/_/g, '-')}.svg`;

const engine = new AudioEngine();
// Render-time FFT spectral corrections (.frc de-embed + mains + IMD) — applied in the VIEW path
// (engine.onResult below), NOT in the engine; the coherent accumulator stays raw.
const fftViewCorrection = new FftViewCorrection(engine.config);
const fftView = new FftView(document.getElementById('spec'), { prefs, genActive: () => genRunning });
const scopeView = new ScopeView(document.getElementById('scope'), { prefs });
let prefsModal, aboutModal, dacCalModal;
let shotModal;
let confirmModal;
let prefsDialog;   // the Preferences dialog (constructed in init's modals step)
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
// loopback level (DAC), making the accumulated running statistics inconsistent —
// clear the scope measurement history on a calibration change (Java ScopeView
// adcFsVoltageRms / dacFsVoltageAmpl onChange → clearMeasurementHistory). Java
// FftView wires the SAME two prefs to resetStatistics(), so the FFT cross-tick
// average must be restarted too — else it keeps folding pre-calibration frames at
// the old scale into the displayed spectrum. Reset the accumulator while recording.
const onCalChange = () => {
  scopeView._clearMeasurementHistory();
  if (fftRec) engine.resetAnalyses();
};
prefs.adcFsVoltageRms.addListener(onCalChange);
prefs.dacFsVoltageAmpl.addListener(onCalChange);

// ----- generator amplitude minimum (Java AMP_MIN_VRMS) + frequency minimum -----
const AMP_MIN_VRMS = 1e-9;
const GEN_FREQ_MIN_HZ = 0.01;   // Java GeneratorPane.GEN_FREQ_MIN_HZ
// FFT averages stepper presets — Java FftTabControl.AVERAGES_SERIES { 2, 4, 8, 16, 32, 64, 128, ∞ }.
const FFT_AVERAGES_SERIES = [2, 4, 8, 16, 32, 64, 128, Infinity];
const outRate = () => parseInt($('#outRate').val(), 10) || prefs.current().outputSampleRate || 384000;
const inRate = () => parseInt($('#inRate').val(), 10) || prefs.current().inputSampleRate || 384000;   // FR fields cap at INPUT Nyquist

// NumericStepField controllers, built in init() once i18n has resolved (the unit
// suffixes come from t('unit.*')). Keyed by the input id.
const stepFields = {};
/** Canonical value of a step field by input id (fallback when not yet built). */
const sfVal = (id, dflt) => (stepFields[id] ? stepFields[id].getValue() : (parseFloat($('#' + id).val()) || dflt));

// Two-way binding (Java Bindings.stepField): a field edit writes the pref (the field's own
// onChange) AND an external pref change (preset load, DAC calibration, other generator code)
// pushes back into the field. setValue is silent (no onChange — numeric-step-field.js:504), so
// the value-guarded listener can never feed back into a loop.
function bidiBind(field, pref) {
  if (field) pref.addListener((v) => { if (field.getValue() !== v) field.setValue(v); });
}

/** 1-2-5 decade ladder over [min .. max] inclusive (the scope V/div and t/div
 *  step series), e.g. 1µ, 2µ, 5µ, 10µ, … 200, 500. */
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
// 1 µV … 500 V, t/div 1 µs … 1 s. Shared by the step fields AND the scope view's
// Ctrl-wheel zoom, so the wheel snaps to the SAME series the field arrows walk.
const SCOPE_VDIV_SERIES = ladder125(1e-6, 500);
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
  // matching pref per form (genDualToneFreq1Hz in dual, else genFrequencyHz) — Java keeps them
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

  // Amplitude: AMPLITUDE family, PERCENT policy, canonical V RMS (the headline
  // fix — no dBFS). dBV display is sticky + persisted via genAmplitudeDbvDisplay.
  const fAmp = mk('ampDbfs', new NumericStepModel({ family: F.AMPLITUDE, min: AMP_MIN_VRMS, max: prefs.dacFsVoltageAmpl.get(), maxDecimals: 5 }),
    (v) => {
      prefs.genAmplitudeVrms.set(v);
      prefs.genAmplitudeDbvDisplay.set(fAmp.model.isLogDisplay());
      engine.config.ampVrms = v; engine.retuneGenerator();
    });
  fAmp.model.setLogDisplay(prefs.genAmplitudeDbvDisplay.get());
  fAmp.setValue(prefs.genAmplitudeVrms.get());
  // Track DAC full-scale → amplitude ceiling (Bindings.onChange(... ampField::setMax)).
  prefs.dacFsVoltageAmpl.addListener((fs) => fAmp.setMax(fs));

  // DAC-calibration dialog: the measured-amplitude field is a unit-aware AMPLITUDE step field
  // (V / mV / µV / dBV + short forms), like the generator amplitude — replaces the old plain
  // input + unit <select>. Seeded on open, read on Calibrate.
  mk('dacCalValue', new NumericStepModel({ family: F.AMPLITUDE, min: AMP_MIN_VRMS, max: 1000, maxDecimals: 5 }), () => {});

  // FFT THD "Manual fundamental" reference level — unit-aware AMPLITUDE field (accepts dBV), g28.
  // onChange placeholder — FftTabControl.bind() rebinds it to the THD-settings commit path
  // (pref-write + live readConfig / stopped-state recompute, #24 — NO restartFft).
  const fManFund = mk('fftManualFund', new NumericStepModel({ family: F.AMPLITUDE, min: AMP_MIN_VRMS, max: 200, maxDecimals: 5 }), () => {});
  if (fManFund) { fManFund.model.setLogDisplay(prefs.fftManualFundDbvDisplay.get()); fManFund.setValue(prefs.fftManualFundVrms.get()); }

  // FFT THD distortion band + harmonic counts (#4) — the Java THD tab's four NumericStepFields
  // (FftTabControl.buildThdTab): distMin/distMax FREQUENCY [0 .. inRate/2] 9 dec (:570/:594),
  // maxThd 2..9 / maxCalc 9..50, step 1, 0 dec (:640/:654). onChange is a placeholder here —
  // FftTabControl.bind() rebinds each to the THD-settings commit path (like averages/manualFund).
  const fDistMin = mk('thdDistMin', new NumericStepModel({ family: F.FREQUENCY, min: 0, max: inRate() / 2, maxDecimals: 9 }), () => {});
  if (fDistMin) fDistMin.setValue(prefs.fftDistMinHz.get());
  const fDistMax = mk('thdDistMax', new NumericStepModel({ family: F.FREQUENCY, min: 0, max: inRate() / 2, maxDecimals: 9 }), () => {});
  if (fDistMax) fDistMax.setValue(prefs.fftDistMaxHz.get());
  const fThdMaxH = mk('thdMaxH', new NumericStepModel({ family: F.NONE, min: 2, max: 9, wheelStep: 1, arrowStep: 1, decimals: 0 }), () => {});
  if (fThdMaxH) fThdMaxH.setValue(prefs.fftThdMaxHarmonic.get());
  const fCalcMaxH = mk('thdCalcMaxH', new NumericStepModel({ family: F.NONE, min: 9, max: 50, wheelStep: 1, arrowStep: 1, decimals: 0 }), () => {});
  if (fCalcMaxH) fCalcMaxH.setValue(prefs.fftCalcMaxHarmonic.get());

  // FFT "Averages" — Java averagesField: NumericStepField(UnitFamily.NONE, AVERAGES_SERIES[0]=2,
  // POSITIVE_INFINITY, AVERAGES_SERIES {2,4,8,16,32,64,128,∞}, 0 decimals, width 70). LIST policy:
  // the wheel/arrows snap along the series; ∞ is the top entry (typed as "∞"/"inf"), NOT a separate
  // checkbox. max=Infinity lets the model accept the ∞ token (numeric-step-field.js commit()).
  // onChange placeholder — FftTabControl.bind() rebinds it to pref-write + live readConfig
  // (#7/#26: an averages change must NOT restart/reset the accumulator).
  const fAverages = mk('averages', new NumericStepModel({ family: F.NONE, min: FFT_AVERAGES_SERIES[0],
    max: Infinity, series: FFT_AVERAGES_SERIES, maxDecimals: 0 }), () => {});
  if (fAverages) fAverages.setValue(prefs.fftAverages.get());

  // Duty / dual-tone amplitude split: PERCENT family + PERCENT policy.
  const fDuty = mk('duty', new NumericStepModel({ family: F.PERCENT, min: 0.001, max: 99.999, maxDecimals: 3 }),
    () => {
      const duty = (sfVal('duty', 50) || 50) / 100;
      const form = $('#signalForm').val();
      // Write ONLY the active form's duty pref — each form remembers its own (Java dutyField).
      if (form === GenSignalForm.TRIANGLE) prefs.genTriangleDuty.set(duty);
      else if (form === GenSignalForm.RECTANGLE) prefs.genRectangleDuty.set(duty);
      engine.config.rectDuty = duty; engine.config.triDuty = duty;
      engine.retuneGenerator();
      genPane.updateDutyLabel();
    });
  fDuty.setValue(((prefs.genSignalForm.get() === GenSignalForm.TRIANGLE
    ? prefs.genTriangleDuty.get() : prefs.genRectangleDuty.get()) || 0.5) * 100);

  const fAmp1 = mk('amp1Pct', new NumericStepModel({ family: F.PERCENT, min: 0.001, max: 99.999, maxDecimals: 3 }),
    (v) => genPane.applyDualToneAmpSplit('amp1Pct', v));
  fAmp1.setValue(prefs.genDualToneSplitPct.get());
  const fAmp2 = mk('amp2Pct', new NumericStepModel({ family: F.PERCENT, min: 0.001, max: 99.999, maxDecimals: 3 }),
    (v) => genPane.applyDualToneAmpSplit('amp2Pct', v));
  fAmp2.setValue(100 - prefs.genDualToneSplitPct.get());

  // Sweep fields (LINEAR_SWEEP / LOG_SWEEP) — NumericStepField like the scope's, faithful to
  // Java sweep*Field: Start/Stop FREQUENCY [0.01 .. Nyquist] 9 dec; Duration/Fade-in/Fade-out
  // TIME (min 0.001 / 0, 3 dec). Wheel/arrow/keyboard stepping + unit auto-ranging come free.
  const sweepOnChange = (pref, cfgKey) => (v) => { pref.set(v); engine.config[cfgKey] = v; engine.retuneGenerator(); };
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
  // WAV-export duration — TIME NumericStepField (Java durationField).
  const fGenDur = mk('genDuration', new NumericStepModel({ family: F.TIME, min: 0.001, max: 1000000, maxDecimals: 3 }),
    (v) => prefs.genWavDurationSeconds.set(v));
  if (fGenDur) fGenDur.setValue(prefs.genWavDurationSeconds.get());

  // Two-way bind the generator step fields to their prefs (external changes update the field).
  bidiBind(fTone, prefs.genFrequencyHz);
  bidiBind(fTone2, prefs.genDualToneFreq2Hz);
  bidiBind(fAmp, prefs.genAmplitudeVrms);
  bidiBind(fSwStart, prefs.genSweepFreqStartHz);
  bidiBind(fSwStop, prefs.genSweepFreqEndHz);
  bidiBind(fSwDur, prefs.genSweepDurationSec);
  bidiBind(fSwFi, prefs.genSweepFadeInSec);
  bidiBind(fSwFo, prefs.genSweepFadeOutSec);
  bidiBind(fGenDur, prefs.genWavDurationSeconds);
  // FFT averages — two-way (Java Bindings.stepField): a preset recall pushes the new count
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
  const fAmpFr = fr('frAmp', new NumericStepModel({ family: F.AMPLITUDE, min: AMP_MIN_VRMS, max: prefs.dacFsVoltageAmpl.get(), maxDecimals: 5 }),
    (v) => { prefs.freqRespAmplitudeVrms.set(v); prefs.freqRespAmplitudeDbvDisplay.set(fAmpFr.model.isLogDisplay()); });
  if (fAmpFr) { fAmpFr.model.setLogDisplay(prefs.freqRespAmplitudeDbvDisplay.get()); fAmpFr.setValue(prefs.freqRespAmplitudeVrms.get()); }
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
  // sample-rate/2 entry of the sweep-points series — re-pull both on an input-rate
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
  // stepFieldInt are two-way): an external write — preset recall, wizard — updates the
  // field display through the same NumericStepField, like the generator fields above.
  bidiBind(fStart, prefs.freqRespStartHz);
  bidiBind(fStop, prefs.freqRespStopHz);
  bidiBind(fAmpFr, prefs.freqRespAmplitudeVrms);
  bidiBind(fLead, prefs.freqRespLeadInSec);
  bidiBind(fPts, prefs.freqRespSweepPoints);

  // Scope V/div + t/div (Java ScopeTabControl, LIST policy): a strict 1-2-5 ladder.
  // V/div 1 µV … 500 V, t/div 1 µs … 1 s. onChange writes the osc* pref the scope
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
  // NO numeric field — matching Java's buildTriggerGroup, which exposes neither.
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
// SWT mnemonics ('&') and the literal "..." suffix from the desktop bundles are
// stripped so the web chrome reads cleanly.
function applyI18n() {
  document.querySelectorAll('[data-i18n]').forEach((el) => {
    el.textContent = t(el.getAttribute('data-i18n')).replace(/&/g, '').replace(/\.\.\.$/, '…');
  });
  document.querySelectorAll('[data-i18n-title]').forEach((el) => {
    el.title = t(el.getAttribute('data-i18n-title')).replace(/&/g, '');
  });
  document.querySelectorAll('[data-i18n-placeholder]').forEach((el) => {
    el.placeholder = t(el.getAttribute('data-i18n-placeholder')).replace(/&/g, '');
  });
  // Custom-file "Browse" button text lives in a ::after pseudo-element; localize it
  // through a CSS custom property the stylesheet reads (item 3).
  document.querySelectorAll('[data-i18n-browse]').forEach((el) => {
    el.style.setProperty('--browse-label', '"' + t(el.getAttribute('data-i18n-browse')).replace(/&/g, '') + '"');
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
  for (const l of LOCALES) {
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
  // via the host.applyPrefsToUi callback (guarded — genPane is built after this first run).
  if (genPane) genPane.seedGeneratorControls();

  const be = prefs.current();
  if (be.inputSampleRate) $('#inRate').val(String(be.inputSampleRate));
  if (be.outputSampleRate) $('#outRate').val(String(be.outputSampleRate));
  $('#adcFsVrms').val(prefs.adcFsVoltageRms.get());
  $('#dacFsAmpl').val(prefs.dacFsVoltageAmpl.get());
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

  // Calibration anchors (recompute dbvOffsetDb on ADC change; both feed engine.config).
  $('#adcFsVrms').on('change', () => { prefs.setAdcFsVoltageRms(parseFloat($('#adcFsVrms').val())); });
  $('#dacFsAmpl').on('change', () => { prefs.setDacFsVoltageAmpl(parseFloat($('#dacFsAmpl').val())); });
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
  c.ditherBits = parseInt($('#dither').val(), 10) || 0;
  // Sweep params (LINEAR_SWEEP / LOG_SWEEP) — read from the sweep fields.
  c.sweepStartHz = sfVal('sweepStart', 20);
  c.sweepEndHz = sfVal('sweepStop', 20000);
  c.sweepDurationSec = Math.max(0.001, sfVal('sweepDur', 1));
  c.sweepFadeInSec = Math.max(0, sfVal('sweepFadeIn', 0));
  c.sweepFadeOutSec = Math.max(0, sfVal('sweepFadeOut', 0));
  c.sweepLoop = $('#sweepLoop').is(':checked');
  c.fftSize = parseInt($('#fftSize').val(), 10); c.window = $('#window').val();
  c.overlap = $('#overlap').val();
  // ∞ (forever) averaging → a true cumulative mean (Infinity). The cross-tick
  // accumulator in FftController grows the depth tick by tick (it no longer sizes
  // a buffer to the whole depth), so ∞ needs no finite cap; the stop-after-N
  // auto-stop, available only in ∞ mode, lets the user end the run. Read from the
  // averages NumericStepField (Java averagesField — ∞ is the top of AVERAGES_SERIES).
  c.averages = stepFields.averages ? stepFields.averages.getValue() : (parseInt($('#averages').val(), 10) || 4);
  c.coherent = $('#coherent').is(':checked');
  // JIT warm-up has NO UI field (Java hardwires it — no Settings-tab control); c.warmupMs
  // keeps its backend default (backend.js). Only the web-only thread-pool size is read.
  c.threads = parseInt($('#threads').val(), 10);
  c.fllOn = $('#align').val() === 'fll';
  // Calibration anchors from preferences (DAC full-scale scales the DDS amplitude;
  // harmonicCount widens the THD set per fftCalcMaxHarmonic).
  c.dacFsVoltageAmpl = prefs.dacFsVoltageAmpl.get();
  // Java FftAnalyzerWorker:1661 — calcMaxH = max(9, getFftCalcMaxHarmonic()) - 1, i.e. the COUNT of
  // harmonics H2..HN (the fundamental is NOT one of them). The web omitted the -1, so it filled one
  // extra harmonic H(N+1) (#19: "max harmonic to calculate included the fundamental"). The display's
  // label cap (fft-view calcMax = max(9,N)) is the max harmonic NUMBER and stays as-is.
  c.harmonicCount = Math.max(9, prefs.fftCalcMaxHarmonic.get()) - 1;
  c.thdMaxHarmonic = Math.max(2, Math.min(9, prefs.fftThdMaxHarmonic.get()));   // g27: THD sum upper bound (H2..HN)
  c.manualFundEnabled = prefs.fftManualFundEnabled.get();                        // g28: fixed-reference fundamental
  c.manualFundVrms = prefs.fftManualFundVrms.get();
  c.fftFundFromGenerator = prefs.fftFundFromGenerator.get();   // hint analyzer with the gen freq vs auto-detect
  c.snrFreqMin = prefs.fftDistMinEnabled.get() ? prefs.fftDistMinHz.get() : 0;   // THD band (Java distMin/distMax)
  c.snrFreqMax = prefs.fftDistMaxEnabled.get() ? prefs.fftDistMaxHz.get() : 0;
  // Stop-after-N auto-stop + mains suppression (Java FftAnalyzerWorker).
  c.stopAfterNEnabled = prefs.fftStopAfterNEnabled.get();
  c.stopAfterN = prefs.fftStopAfterN.get();
  c.mainsSuppression = prefs.fftMainsSuppression.get();
  // Which ADC channel the FFT analyzes (Java FftAnalyzerWorker:1535
  // prefs.getFftChannel(); L → ch0, R → ch1) — flows into FftController._wantLeft
  // at setup and is switched live via engine.setFftChannel from the L/R buttons.
  c.channel = prefs.fftChannel.get();
  // Scope peak-voltage anchor + IMD dBV offset (offset-invariant ratios, but the
  // absolute dBV columns need it).
  c.adcFsVoltageRms = prefs.adcFsVoltageRms.get();
  c.dbvOffsetDb = prefs.dbvOffsetDb;
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
// The render-time FFT spectral corrections stay applied in app.js BEFORE the result is handed to
// the pane; the pane owns latestResult + the dirty flag (Java FftPane.controller last result).
engine.onResult = (r) => {
  // r.channelLeft is stamped in FftController._emit (Java FftAnalyzerWorker:1872
  // stamps it in the worker off wantLeft — the channel it ACTUALLY read). The .frc
  // de-embed + the predistortion cal pick left()/right() off it (fft-view-correction.js).
  fftViewCorrection.apply(r);
  fftPane.setResult(r);
};
engine.onScope = (buf, info) => { latestScope = { buf, info }; };
// Stop-after-N tripped (Java FFT_RECORDING_AUTO_STOPPED → FftPane.disengageRecord):
// the engine paused feeding; the pane tears down the FFT consumer and un-lights the Record LED.
engine.onFftAutoStopped = () => fftPane.onFftAutoStopped();
// The MAIN rAF render loop (renderLoop: scopePane.render() + fftPane.render() each frame, each
// pane self-gated on its OWN record state) moved to shell/main-tab.js (Java MultifunctionalTab);
// app.js kicks it via mainTab.start() in init (after both panes are constructed).

// The scope resize → redraw (ResizeObserver: redrawScopeOnResize) moved to
// scope/scope-pane.js (Java ScopePane); the pane installs its own observer in bind().

// The generator pane (signal-form combo, freq / amp / duty labels, dither combo, snap,
// dual-tone split, sweep, .dpd corrections row, Play / ON-AIR, Save-to, file player) and
// restartGenerator() moved to generator/generator-pane.js (Java GeneratorPane); constructed
// as the genPane init step (before applyPrefsToUi so its seed runs in the init seed). app.js
// reaches its cross-pane methods (syncFormUI / restartGenerator / refreshFreqLabel /
// buildFormCombo) via the genPane instance, and the shared lifecycle flags flow the other
// way through injected closures.

// A structural FFT change re-acquires ONLY the FFT consumer — neither disturbs the other two
// lifecycles (Java: an FFT-length change is FftController's concern, a form change is
// GeneratorController's, and the scope keeps running throughout).
async function restartFft() {
  if (busy || !fftRec) return;
  busy = true;
  try { await engine.setFftRecording(false); readConfig(); fftRec = await engine.setFftRecording(true); }
  finally { fftPane.syncFftLed(); busy = false; }
}

// The FFT SETTINGS STRIP (tile-tabs, window / overlap / averages / threads / coherent /
// align / THD band + harmonics, presets, save / load, calibration, screenshot + ADC
// calibrate) lives in fft/fft-tab-control.js (Java FftTabControl); constructed in init's
// fftTabControl step. The FFT PANE machinery (spectrum canvas / Record LED / readout +
// THD/IMD table / render loop) lives in fft/fft-pane.js (Java FftPane); the strip's host
// composite routes getResult / setResult to that pane (the web FFT pane has no freq/mag
// FlatScrollbars — those Java scrollbars were never ported; the FftView navigates itself).

// FreqResp tile-tabs — same toggle behavior; owns the Settings / RIAA-IEC / Presets /
// Utility / Calibration / Save-to / Load-from drop-down panels.
$('#frTabs .tab').on('click', function () {
  const panel = $(this).data('panel');
  const wasOpen = $(this).hasClass('active') && panel && $('#' + panel).hasClass('show');
  $('#frTabs .tab').removeClass('active'); $(this).addClass('active');
  $('#frSettings, #frRiaaPanel, #frPresetsPanel, #frUtility, #frCalPanel, #frSavePanel, #frLoadPanel').removeClass('show');
  if (panel && !wasOpen) $('#' + panel).addClass('show');
});

// The scope SETTINGS STRIP (tile-tabs, channel / filter / trigger controls,
// presets, save / load, ADC calibrate) lives in scope/scope-tab-control.js
// (Java ScopeTabControl); constructed in init's modals step. The scope PANE
// machinery below (canvas / scrollbars / record / render loop) is the strip's
// host — the tab-control reaches it through the narrow host object.

// The scope PANE machinery — the file-mode horizontal nav (scopeOnFileBack), the
// SINGLE-mode Start gate (syncTriggerStart), the measurement-table header buttons +
// pop-out window (setMeasTablePopped / makeMeasWindowDraggable), and the vertical /
// horizontal navigation scrollbars (onVertScrollMoved / onHorizScrollMoved /
// offsetFracBounds / syncOffsetScrollbar) — moved to scope/scope-pane.js (Java
// ScopePane). app.js drives them through the scopePane instance (which IS the
// ScopeTabControl.Host); the strip seeds the controls via scopeTabControl
// .seedScopeControls() and reaches the pane through its public methods.

// ----- L/R channel toggles -----
// Scope has NO channel-enable L/R buttons in its header (Java ScopeView): channel
// enable lives in the Left/Right tab "Enabled" checkbox (#scopeLeftEnable /
// #scopeRightEnable, wired above); the header L/R pair is the measurement-channel
// picker (#scopeMeasL / #scopeMeasR), wired below.
// FFT: mutually-exclusive radio — the analyser sees one channel at a time
// (Java fftChannel = L|R): the switch selects which channel the FFT ANALYZES
// (L → ch0, R → ch1). Persist the choice AND push it to the engine so the
// running analyzer reads the selected channel and resets its statistics +
// accumulator (Java FftView:511/514 buttons → setFftChannel → fftChannelProperty
// subscription → resetStatistics). The .frc de-embed then picks the matching
// channel off r.channelLeft (fft-view-correction.js), already done.
$('.fft-pane .lr.l, .fft-pane .lr.r').on('click', function () {
  const isLeft = $(this).hasClass('l');
  $('.fft-pane .lr.l, .fft-pane .lr.r').removeClass('on'); $(this).addClass('on');
  prefs.fftChannel.set(isLeft ? 'L' : 'R');
  engine.setFftChannel(isLeft ? 'L' : 'R');
});
// FreqResp L/R: the plot path consumes loadedFrc.left only; keep visual radio.
$('#tab-fr .lr.l, #tab-fr .lr.r').on('click', function () {
  $('#tab-fr .lr.l, #tab-fr .lr.r').removeClass('on'); $(this).addClass('on');
});

// ----- record LED (per-pane capture toggle) -----
// Independent per-pane Record (Java: the scope and FFT panes each hold their own
// SharedCapture reference; the device opens on the first acquire and closes on the
// last release). The scope LED drives setScopeRecording (moved to ScopePane); the FFT
// LED drives setFftRecording (moved to FftPane) — each lights its OWN LED. The
// generator (#genPlay) is separate. app.js reaches the FFT LED sync through
// fftPane.syncFftLed() (restartFft / restartPreservingConfig).

// The scope SETTINGS STRIP (ScopeTabControl) reaches the scope PANE through the
// scopePane instance, which IS the Host (Java ScopePane implements ScopeTabControl.Host):
// requestRedraw / refreshTiles / refreshFields / syncTriggerStart / setTriggerControlsEnabled
// / syncOffsetScrollbar / redrawScrollbars / syncMeasChannelButtons / syncMeasButtons /
// recState / onFileBack / stopCaptureForFileLoad / onSignalFileLoaded are all its public
// methods. The construction passes `host: scopePane` (see init's scopeTabControl step).

// FFT view header buttons (Java FftView autoSetupBtn / maximizeBtn) — were dead.
$('#fftAutoSetup').on('click', () => fftView.autoSetup());
$('#fftMaximize').on('click', () => fftView.maximize());

// The FFT SETTINGS STRIP (FftTabControl) reaches the FFT PANE + shell helpers through this
// narrow host (mirrors Java FftTabControl.Host) — a COMPOSITE of MIXED ownership: getResult /
// setResult route to the FFT pane (which owns latestResult); applyPrefsToUi / readConfig are
// shell helpers; refreshFreqLabel is the generator pane. The spectrum canvas / Record LED /
// readout / render loop live in fft/fft-pane.js (Java FftPane), reached via the fftPane instance.
const fftHost = {
  getResult: () => fftPane.getResult(),                 // the live analyzed spectrum (Save / ADC-calibrate read it) — owned by the FFT pane
  setResult: (r) => fftPane.setResult(r),               // a loaded .fft spectrum → repaint next frame (FFT pane state)
  stopFftRecording: () => fftPane.onRecordingStopRequested(),   // Java FFT_RECORDING_STOP_REQUESTED — stop live record before a .fft load clobbers it
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
function openHelp(page) {
  const supported = ['en', 'de', 'uk'];
  const loc = (document.documentElement.lang || 'en').slice(0, 2).toLowerCase();
  const lang = supported.includes(loc) ? loc : 'en';
  const w = 783, h = 712;
  const appX = window.screenX != null ? window.screenX : (window.screenLeft || 0);
  const appY = window.screenY != null ? window.screenY : (window.screenTop || 0);
  const left = appX + window.outerWidth - 13;   // help top-LEFT corner aligned to app top-RIGHT corner - 13 pixel gap
  const top = Math.max(0, appY);
  const features = `popup=yes,width=${w},height=${h},left=${left},top=${top}`;
  const win = window.open(`help/${lang}/${page || 'index.html'}`, 'phonalyser-help', features);
  if (win) win.focus();
}

// Which help page matches the current context — for Ctrl+F1. Read-only DOM inspection at
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
// startup checks, rebuild index, tip-of-day) are omitted: the web auto-updates via the
// service worker and the search index is built at build time.
$('#helpShow').on('click', () => openHelp());
$('#helpShowActive').on('click', () => openHelp(helpContextPage()));
$('#helpReport').on('click', () => window.open('https://github.com/dgo42/Phonalyser/issues/new', '_blank', 'noopener'));
$('#helpAbout').on('click', () => {
  $('#aboutVersion').text(($('.menu-ver').text() || '').replace(/·.*$/, '').trim());
  aboutModal.show();
});
// F1 → help contents; Ctrl+F1 → contextual help for the active pane / tab. preventDefault
// so the browser's own F1 help doesn't also fire.
document.addEventListener('keydown', (e) => {
  if (e.key === 'F1') {
    e.preventDefault();
    openHelp(e.ctrlKey ? helpContextPage() : 'index.html');
  }
});

// ----- shared lifecycle flags (the single source of truth for all three panes) -----
// Three independent lifecycles now (Java: generator / scope record / FFT record):
// #genPlay (in GeneratorPane) starts/stops the DDS generator; the per-pane Record LEDs
// drive setScopeRecording / setFftRecording. `busy` is the shared re-entrancy guard —
// startGenerator/stopGenerator and the consumer acquire/release are async
// (open/close AudioContexts); a second click mid-transition races and can tear
// down a half-built audio graph (STATUS_BREAKPOINT). Ignore clicks until settled.
// GeneratorPane reads/writes genRunning + busy through the injected closures below.
let genRunning = false, scopeRec = false, fftRec = false, busy = false;

// ----- Language submenu: switch locale, persist, re-render the chrome -----
$('#langMenu').on('click', '[data-lang]', async (ev) => {
  const tag = ev.currentTarget.getAttribute('data-lang');
  if (tag === prefs.uiLanguage.get()) return;
  prefs.uiLanguage.set(tag);
  await setLocale(tag);
  buildLanguageMenu();   // move the check mark to the new active locale
  applyI18n();
  // Re-resolve the dynamically-built signal-form labels (hidden select + combo).
  fill($('#signalForm'), Object.values(GenSignalForm), $('#signalForm').val(), formLabel);
  genPane.buildFormCombo();
  for (const f of Object.values(stepFields)) f.refresh();   // re-resolve unit suffixes
  genPane.refreshFreqLabel(); if (fftTabControl) fftTabControl.refreshFftTiles(); freqRespHost.refreshFftLabel();
});

// ============================ File I/O wiring ============================

const WAV_TYPE = [{ description: 'Audio (WAV / AIFF / FLAC)', accept: 'audio/wav', extensions: ['.wav', '.aiff', '.aif', '.flac'] }];
const FFT_TYPE = [{ description: 'FFT spectrum', accept: 'text/plain', extensions: ['.fft'] }];
const FRC_TYPE = [{ description: 'Filter calibration', accept: 'text/plain', extensions: ['.frc'] }];

// The generator "Save to…" (DdsKernel render + dither + STEREO WAV/AIFF/FLAC) and the
// "Load from…" file player (decode + engine DAC playback lane, loop, play/stop) moved to
// generator/generator-pane.js (Java GeneratorPane); they reach the io decode/save helpers +
// WAV_TYPE through the injected `io` / WAV_TYPE deps.

// ----- Calibrate DAC (Java DacCalibrationDialog + openDacCalibrationDialog) -----
// DAC full-scale calibration dialog: generator/dac-calibration-dialog.js (constructed in init's
// modals step once dacCalModal is live).

// The scope "Save to…" / "Load signal…" flows moved to ScopeTabControl; the
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
// screenshotCommentTopPx() — the scope does NOT override AbstractPane's
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
// and paintCloneToCanvas are GENERIC — the same pair will capture the FFT and FreqResp
// panes (each with its own prep + live-canvas mapper); composeScopePaneShot is the thin
// scope wrapper.
// Every chrome element (.lr-tools buttons + icons, the tile-tab strip, edge labels,
// slider lines) is drawn at its OWN css-pixel size — it does NOT scale with the target
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
 *  scrollbar canvases (the vertical bar entirely, the horizontal bar → a 2px black
 *  spacer = the wanted scope↔zoomed gap) so the trace fills the full width with no
 *  reserved gutter. The #scope / #scopeZoomed canvases are KEPT in place — paint maps
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
  // The record LED button is live-only chrome — not wanted on the static capture.
  const cLed = clone.querySelector('.led-btn'); if (cLed) cLed.remove();
  // Keep the scrollbars exactly as on screen: the VERTICAL scrollbar stays (its live
  // bitmap/thumb is blitted by id through liveCanvasFor), and the HORIZONTAL scrollbar
  // gutter stays reserved — the hscroll is visibility:hidden until a file is loaded, so
  // its gutter SPACE is always reserved and the bar (+ thumb) is painted only in file
  // mode. The zoomed-wrap keeps its right padding so it lines up under the v-scrollbar.
  // C29b loaded-filename static label, top-right: shown only in file mode. The cloned
  // #scopeLoadedPath already carries the live text; nothing to add.
}

/** Thin scope-specific wrapper over the GENERIC clonePaneForShot + paintCloneToCanvas:
 *  composes the scope-pane screenshot at EXACTLY `outW`×`outH` px (or the live pane's
 *  native CSS size when omitted). Maps each clone <canvas> to its live source by id
 *  (#scope → live #scope, #scopeZoomed → live #scopeZoomed). Returns a Promise<canvas>
 *  of exactly that size; renderScopeShot's watermark + caption draw on top. */
async function composeScopePaneShot(outW, outH) {
  const live = liveScopePaneSize();
  const w = outW > 0 ? outW : live.w;   // the file IS exactly this size
  const h = outH > 0 ? outH : live.h;
  // "Enlarge elements" dpr fix: the output file is EXACTLY w×h, but on a magnified screen
  // (125% → dpr 1.25) the chrome must be drawn at DEVICE size to match the live on-screen
  // look. So lay the clone out SMALLER (w/dpr) — chrome sits at its CSS px there — and
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
 *  next time (Save + Copy both call it). Scope uses oscScreenshotWidth/Height; the FFT /
 *  FreqResp screenshots will use their own keys with the same pattern. */
function persistShotSize(w, h) {
  if (!(w > 0) || !(h > 0)) return;
  prefs.oscScreenshotWidth.set(w);
  prefs.oscScreenshotHeight.set(h);
  prefs.save();
}

/** Renders the FULL scope pane (collapsed tabs) at {@code w}×{@code h} via the
 *  clone-DOM + manual canvas composite, stamping {@code comment} (when non-blank) at
 *  the pane's screenshotCommentTopPx vertical offset in the top-right — mirroring
 *  ScreenshotDialog's caption. Returns a {@code Promise<Blob>} in {@code mime}. */
async function renderScopeShot(comment, w, h, mime) {
  // The clone is painted directly at the target W×H (same proportions as the
  // composed pane), so the output canvas IS the screenshot — no extra resample.
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

// ----- FFT screenshot via the SAME cloned-DOM + canvas composite (#28; Java FFT_SCREENSHOT_REQUESTED
// → ScreenshotDialog). The generic clonePaneForShot + paintCloneToCanvas pair used by the scope above
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
 *  composeScopePaneShot): composes the FFT pane at EXACTLY outW×outH, mapping #spec → live #spec. */
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
// The scope ADC-calibrate handler (#scopeCalibrate) + its enable gate
// (syncCalibrateEnabled) moved to ScopeTabControl; the render loop drives the gate
// via scopeTabControl.syncCalibrateEnabled().

// The FFT Presets / Utility (screenshot + ADC calibrate) / Save / Load / Load-calibration
// handlers moved to fft/fft-tab-control.js (Java FftTabControl): presets recall re-seeds the
// main FFT controls via host.applyPrefsToUi; the loaded .fft spectrum flows back via
// host.setResult; the .frc load pushes into the shared frcStore + fftViewCorrection.

// Loaded .frc store, shared by the render-time FFT de-embed AND the predistortion
// engine's calResponseAt (PredistortionHost.correctionEntries shape). Filled by the
// FftTabControl "Load calibration…" handler (frcStore injected into the control).
const frcStore = [];

// ============================ Frequency response ============================
const freqRespHost = new FreqRespHost(engine, prefs, { saveFile, openFile, bytesToText });

// ============================ Predistortion wizard ============================
let predistModal;

// Restart preserving the (predistortion-mutated) engine.config — does NOT re-read
// the UI, so configureForRun's coherent/∞-averaging settings survive.
async function restartPreservingConfig() {
  if (!engine.running) return;
  // Predistortion drives the full pipeline (generator + FFT analysis + FLL). Bounce
  // both via the fused convenience start/stop so configureForRun's coherent/∞-averaging
  // window takes effect; the engine's internal lifecycles are independent, the app
  // record flags follow so the FFT view keeps rendering.
  await engine.stop();
  await engine.startGenerator();
  await engine.setScopeRecording(true);
  await engine.setFftRecording(true);
  genRunning = true; scopeRec = true; fftRec = true;
  $('#genPlay').addClass('playing').attr('title', t('generator.play.stop'));
  $('#onAir').addClass('live');
  scopePane.syncScopeLed(); fftPane.syncFftLed();
}

const predistHost = new PredistortionHost(engine, prefs, {
  getResult: () => fftPane.getResult(),
  restart: restartPreservingConfig,
  correctionEntries: frcStore,
});





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
    'Phonalyser.web requires a Chromium-based browser — Google Chrome, Microsoft Edge, Opera or Brave. '
    + 'It relies on Chromium-only audio APIs (frame-accurate capture-rate probing, AudioWorklet) that this browser '
    + 'does not provide, so it cannot run here. Please open this page in Chrome or Edge.');
  box.appendChild(h); box.appendChild(p);
  const el = document.createElement('div'); el.id = 'unsupportedBrowser'; el.setAttribute('role', 'alertdialog');
  el.appendChild(box); document.body.appendChild(el);
}

// ----- bootstrap: i18n must resolve before first paint of the chrome -----
async function init() {
  // Each setup step is ISOLATED + logged: one failing step can no longer abort the
  // rest (which previously left the device scan un-run → "nothing works", silently).
  // The failing step's name + error surface in the console so it's pinpointable.
  const step = (name, fn) => { try { return fn(); } catch (e) { console.error('init step failed:', name, e); } };
  await initBase();
  try { await setLocale(prefs.uiLanguage.get()); } catch (e) { console.error('init step failed: setLocale', e); }
  // Refuse to run on a non-Chromium engine: show the warning and stop before any device/UI setup.
  if (!isChromium()) { showUnsupportedBrowserOverlay(); return; }
  step('initSelects', initSelects);
  // Generator pane (Java GeneratorPane): the signal-form combo + freq/amp/duty/dual-tone/
  // sweep/dither/snap/.dpd controls + Play/ON-AIR + Save-to + file player. Constructed before
  // applyPrefsToUi so its seedGeneratorControls() runs in the init seed; reaches the SHELL
  // lifecycle flags (genRunning + busy) + readConfig + the FFT align combo through the injected
  // closures, and the generator step fields (built later in initStepFields) via getField.
  step('genPane', () => {
    genPane = new GeneratorPane(engine, prefs, {
      getField: (id) => stepFields[id],
      io: { pickSaveTarget, writeToTarget, saveScopeCapture, readWav, readAiff, decodeFlac },
      WAV_TYPE, formLabel, formIcon, sfVal, outRate,
      isGenRunning: () => genRunning, setGenRunning: (v) => { genRunning = v; },
      isBusy: () => busy, setBusy: (v) => { busy = v; },
      readConfig, syncFftAlign: () => { if (fftTabControl) fftTabControl.syncAlign(); },
    });
  });
  step('applyPrefsToUi', applyPrefsToUi);
  step('bindPrefs', bindPrefs);
  step('genPaneBind', () => genPane.bind());   // generator handlers (was the generator part of bindPrefs)
  step('buildFormCombo', () => genPane.buildFormCombo());
  step('applyI18n', applyI18n);
  step('refreshFftLabel', () => freqRespHost.refreshFftLabel());   // localized after the bundle is loaded
  step('freqRespSeed', () => freqRespHost.seedTabs());   // builds the cal rows / preset list / RIAA enable (t() needs the bundle)
  step('initStepFields', initStepFields);   // after applyI18n so unit suffixes resolve
  // Oscilloscope PANE (Java ScopePane): the trace canvas wiring, the two nav scrollbars,
  // the Record LED, the measurement table + pop-out, the file-mode load/scroll, and the
  // scope branch of the rAF loop (render()). Built BEFORE the scope settings strip, since
  // the strip's host IS this pane (Java ScopePane implements ScopeTabControl.Host). Reaches
  // the SHELL lifecycle flags (scopeRec + busy) + readConfig + the latest scope frame
  // through the injected closures; the scope V/T/hyst NumericStepFields (from initStepFields)
  // via getField; the ScopeView + tileChips injected too. bind() wires the Record LED +
  // measurement buttons + resize observer.
  step('scopePane', () => {
    scopePane = new ScopePane(engine, prefs, {
      view: scopeView, getField: (id) => stepFields[id], tileChips,
      isScopeRec: () => scopeRec, setScopeRec: (v) => { scopeRec = v; },
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
    }).bind();
  });
  step('seedScopeControls', () => scopeTabControl.seedScopeControls());
  step('refreshOscPresetList', () => scopeTabControl.refreshOscPresetList());
  step('syncTriggerStart', () => scopePane.syncTriggerStart());
  // FFT PANE (Java FftPane): the spectrum-view wiring, the Record LED, the readout / THD /
  // IMD render, and the FFT branch of the rAF loop (render()). Built BEFORE the FFT settings
  // strip, since the strip's host routes getResult / setResult to this pane (Java
  // FftTabControl.Host). Reaches the SHELL lifecycle flags (fftRec + busy) + readConfig through
  // the injected closures; the FftView + tileChips injected too. bind() wires the Record LED.
  step('fftPane', () => {
    fftPane = new FftPane(engine, prefs, {
      view: fftView, tileChips,
      isFftRec: () => fftRec, setFftRec: (v) => { fftRec = v; },
      isBusy: () => busy, setBusy: (v) => { busy = v; },
      readConfig,
    }).bind();
  });
  // MAIN TAB (Java MultifunctionalTab): the rAF render-frame driver + the 3-pane collapse/sash
  // layout. Built after BOTH panes exist; start() kicks the requestAnimationFrame loop (which
  // drives scopePane.render() + fftPane.render() each frame, each pane self-gated on its OWN
  // record state), and initLayout() wires the collapsible panes + draggable sashes + the
  // pane-weight / collapse-state persistence (it owns NO lifecycle flags — those stay in app.js).
  step('mainTab', () => {
    mainTab = new MainTab({ scopePane, fftPane, prefs });
    mainTab.start();
    mainTab.initLayout();
  });
  // FFT settings strip (Java FftTabControl): its handlers + presets + save/load +
  // calibration + screenshot/ADC-calibrate. Built after initStepFields (the FFT
  // manual-fundamental NumericStepField it reaches via getField exists now). Reaches the
  // FFT PANE (canvas / Record LED / readout / render loop) through the narrow `host` —
  // getResult / setResult route to the fftPane built above.
  step('fftTabControl', () => {
    fftTabControl = new FftTabControl(engine, prefs, {
      host: fftHost, fftView, fftViewCorrection, frcStore, getField: (id) => stepFields[id],
      io: { saveFile, openFile, bytesToText, loadFrc, saveSpectrum, loadSpectrum, FFT_TYPE, FRC_TYPE },
      restartFft, showConfirm, setStatus: (m) => $('#status').text(m), tileChips,
    }).bind();
  });
  step('seedFftControls', () => fftTabControl.seedFftControls());
  step('refreshFftPresetList', () => fftTabControl.refreshFftPresetList());
  step('fftSeed', () => { genPane.refreshFreqLabel(); genPane.syncFormUI(); });   // generator label + form-gated UI follow the seeded FFT controls
  step('modals', () => {
    prefsModal = new window.bootstrap.Modal(document.getElementById('prefsModal'));
    aboutModal = new window.bootstrap.Modal(document.getElementById('aboutModal'));
    predistModal = new window.bootstrap.Modal(document.getElementById('predistModal'), { backdrop: 'static', keyboard: false });   // #1: a real modal — no click-away / Esc dismiss (a running tuning must not be lost to a stray click)
    // #6/#10: the Java wizard Shell is user-movable AND user-resizable (SWT.DIALOG_TRIM |
    // SWT.RESIZE, PredistortionWizardDialog:138) and packs to its content (:162) — a modest
    // ~470px-wide box, not a full-screen dialog. Bootstrap modals give us neither, so we add
    // both by hand:
    //   - MOVE:   header-drag translates the .modal-dialog (composes with Bootstrap centering).
    //   - RESIZE: the CSS `resize: both` affordance lives on .modal-CONTENT, not .modal-dialog —
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
        content.style.width = ''; content.style.height = '';   // drop any user resize → re-pack on reopen
      });
    })();
    dacCalModal = new window.bootstrap.Modal(document.getElementById('dacCalModal'));
    shotModal = new window.bootstrap.Modal(document.getElementById('shotModal'));
    confirmModal = new window.bootstrap.Modal(document.getElementById('confirmModal'));
    // Wire the shared screenshot dialog to the scope pane: the generic compositor + dialog
    // live in screenshot.js; this injects the scope-specific render / native-size / persisted
    // size so the SAME dialog will later serve the FFT + FreqResp panes. Constructed here (not
    // at module top) so the shotModal instance above is already live.
    const shotDialog = new ScreenshotDialog({
      modal: shotModal, openBtn: '#scopeShot', renderShot: renderScopeShot, nativeSize: shotNativeSize,
      seedSize: () => ({ w: prefs.oscScreenshotWidth.get(), h: prefs.oscScreenshotHeight.get() }),
      persistSize: persistShotSize, saveFile, status: (m) => $('#status').text(m),
    });
    shotDialog.bind();
    // FFT pane shares the SAME dialog (#28): its own renderShot + native-size seed. The chosen
    // size persists PER VIEW (#28 follow-up): the FFT uses the Java-parity screenshotWidth/Height
    // prefs (the scope has its own oscScreenshotWidth/Height above); seeds from the live pane
    // size until a size was chosen once.
    shotDialog.addPane({
      openBtn: '#fftShot', renderShot: renderFftShot, nativeSize: fftNativeSize,
      seedSize: () => {
        const w = prefs.screenshotWidth.get(), h = prefs.screenshotHeight.get();
        return (w > 0 && h > 0) ? { w, h } : fftNativeSize();
      },
      persistSize: (w, h) => {
        if (!(w > 0) || !(h > 0)) return;
        prefs.screenshotWidth.set(w); prefs.screenshotHeight.set(h); prefs.save();
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
    // DAC full-scale calibration dialog (Java DacCalibrationDialog).
    new DacCalibrationDialog(engine, prefs, { modal: dacCalModal, getField: () => stepFields.dacCalValue }).bind();
    // Preferences dialog (Java PreferencesDialog): staged audio/L&F/Osc/FFT/FR prefs, commit on OK.
    prefsDialog = new PreferencesDialog(engine, prefs, {
      modal: prefsModal, RATES, stepFields, inRate, outRate, restartGenerator: () => genPane.restartGenerator(),
      isBusy: () => busy, setBusy: (v) => { busy = v; }, fftView,
    }).bind();
    prefsDialog.applyLookAndFeel();   // main-tab orientation + small icons + UI font from the saved prefs
  });
  step('freqResp', () => freqRespHost.plot());
  prefsDialog.scan();       // auto-enumerate audio devices on load (no Preferences dialog needed)
}
init().catch(e => console.error('init failed', e));
 