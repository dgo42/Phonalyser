/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.gui.freqresp.TuneNotchWizardDialog —
// the modal wizard that continuously re-runs a fast looping Farina sweep and
// paints the live frequency response into an embedded FreqRespView, so the user
// can tune a notch filter and watch the dip move in real time.
//
// The dialog opens, mirrors the FREQRESP_MEASUREMENT_STARTED interlocks (the
// scope / FFT Record + the file player are stopped and locked, the generator
// Play + sweep buttons disabled), then drives the wave-1 NotchSweepEngine in a
// continuous grab → deconvolve → display loop until the dialog closes. Each
// finished sweep is fed DIRECTLY into the embedded view (never into the main
// FreqResp pane). The first sweep auto-fits the magnitude axis to the measured
// band; from then on the vertical range follows a MAG_AVG_FRAMES-sweep rolling
// average so it doesn't jump frame-to-frame.
//
// The embedded view's range / channel-visibility state is DETACHED from the
// shared FreqResp preferences (see makeDetachedRangePrefs): the wizard drives it
// through a private in-memory copy, so opening / using the wizard never mutates
// (nor zooms) the main FreqResp pane's view. The Java dialog instead shares the
// prefs and snapshots+restores them around the session (savedFreqMin/Max/MagTop/
// Bot in TuneNotchWizardDialog) — the web detaches, so no save/restore is needed.
//
// The wizard is fully AUTONOMOUS (Java TuneNotchWizardDialog): it never needs the
// generator running. Like the FreqResp sweep it publishes
// FREQRESP_MEASUREMENT_STARTED (every consumer — scope / FFT / generator / main
// FreqResp — stops itself), waits for the shared capture + DAC to go idle
// (waitForWorkersIdle, shared with FreqRespHost), then starts its OWN silent
// LOG_SWEEP generator + acquires the shared capture and streams the looping notch
// sweep. On close it stops the generator, releases the capture and publishes
// FREQRESP_MEASUREMENT_STOPPED so the consumers re-enable themselves.
//
// Intended web adaptations of the Java dialog (all device/thread plumbing —
// the analysis and display semantics are ported 1:1):
//   - Java opens its own EXCLUSIVE JavaSound lines; the web's playback path is the
//     shared dds-processor worklet, so the wizard starts the generator itself
//     (engine.startGenerator with the config pointed at a silent LOG_SWEEP) and
//     drives it via the NotchSweepEngine's postGen, restoring the pre-session DDS
//     config + stopping the generator on close (mirror of FreqRespHost.captureAndDeconvolve).
//   - Java's resolveDevice acquires the exclusive device; the web SharedCapture is
//     ref-counted, acquired once by the NotchSweepEngine and released on close.
//   - Java overlaps deconvolution with capture on a single-thread executor; the
//     web is single-threaded, so deconvolveAndPublish runs inline between grabs
//     (the tickStreamingPercent sleeps keep the UI responsive).
//   - Java's paint listeners become a wrap of the view's render() so the target
//     marker + notch readout repaint on EVERY view paint, exactly like SWT
//     addPaintListener.
//   - The overlay geometry mirrors the WEB FreqRespView's margins (56/4/18 and
//     52/6 right) — Java mirrors ITS view's 68/0/28 (+52/0) — so the marker
//     lands on the same log-frequency axis the web trace is drawn on.

import { t } from '../i18n/i18n.js';
import { FreqRespView } from './freqresp-view.js';
import { FreqRespCorrectionStore } from './correction-store.js';
import { computeFromLogSweep, binAlignedFreqs } from './deconvolve.js';
import { makeFreqRespResult, makeStereoResult } from './stereo-result.js';
import { waitForWorkersIdle } from './worker-idle.js';
import { MessageBus } from '../bus/message-bus.js';
import { Events } from '../bus/events.js';
import { GenSignalForm } from '../generator/dds-kernel.js';
import { NumericStepField, NumericStepModel, UNIT_FAMILIES } from '../widgets/numeric-step-field.js';
import {
  NotchSweepEngine, sweepBand, findDeepestNotch, dbAtFrequency,
  powerOfTwoSweepSamples, notchFadeSamples, SWEEP_DURATION_SEC, SWEEP_LEAD_IN_SEC,
} from './notch-sweep-engine.js';

// --- Chart geometry (TuneNotchWizardDialog constants) -------------------------
/** Output-grid point-count ceiling handed to binAlignedFreqs (Java CHART_WIDTH_PX);
 *  a grid-density cap, NOT the canvas pixel width. Kept at 600 so the bin-aligned
 *  grid is never truncated (the notch band spans far fewer bins than this). */
const CHART_WIDTH_PX = 600;
/** Embedded canvas pixel size (CSS #tnPlot) — used only as the pre-layout fallback
 *  for the overlay geometry; the live paints read the real clientWidth/Height. */
const CANVAS_WIDTH_PX = 600;
const CANVAS_HEIGHT_PX = 400;

// --- Continuous-stream loop timing --------------------------------------------
/** Settle poll while the ring fills to one period — re-check this often. */
const SETTLE_POLL_MS = 20;
/** Status / percentage UI refresh cadence (don't spam the UI thread). */
const STATUS_TICK_MS = 30;
/** Brief wait when latestPeriod() is momentarily null after settle. */
const GRAB_RETRY_MS = 10;
const PERCENT_FULL = 100.0;
/** Give-up deadline for the OTHER panes' workers to release the device after the
 *  FREQRESP_MEASUREMENT_STARTED publish (Java DEVICE_RELEASE_TIMEOUT_MS; matches
 *  FreqRespHost's 2000 ms). */
const DEVICE_RELEASE_TIMEOUT_MS = 2000;

// --- Field defaults / ranges ---------------------------------------------------
const FREQ_MIN_HZ = 1.0;
const AMP_MIN_VRMS = 1e-4;
const FREQ_MAX_DECIMALS = 9;
const AMP_MAX_DECIMALS = 5;

// --- Initial / pre-fit magnitude window (dBV) ----------------------------------
const INITIAL_MAG_TOP_DB = 20.0;
const INITIAL_MAG_BOT_DB = -140.0;
/** Padding above max / below min applied to the rolling auto-fit. */
const AUTO_FIT_PAD_DB = 2.0;

// --- Notch readout overlay ------------------------------------------------------
// Java NOTCH_TEXT_X_PX = its view MARGIN_LEFT + 5; the web view's MARGIN_LEFT is 56.
const NOTCH_TEXT_X_PX = 61;
const NOTCH_TEXT_Y_PX = 6;
/** One-pixel white outline drawn around the black readout text. */
const NOTCH_OUTLINE_PX = 1;

// --- Vertical-range averaging + target-frequency marker -------------------------
/** Vertical range is set from the rolling average of this many sweeps'
 *  min/max dB, so the axis doesn't jump frame-to-frame. */
const MAG_AVG_FRAMES = 20;
// Plot margins — mirror the WEB FreqRespView's MARGIN_* so the target marker
// lands on the same log frequency axis the trace is drawn on (Java mirrors its
// own view's 68/0/28 + 52/0; the web view uses 56/4/18 + 52/6).
const VIEW_MARGIN_LEFT = 56;
const VIEW_MARGIN_TOP = 4;
const VIEW_MARGIN_BOTTOM = 18;
const VIEW_MARGIN_RIGHT_PHASE = 52;
const VIEW_MARGIN_RIGHT_NO_PHASE = 6;
/** Target-frequency marker: a 3-px dashed vertical line coloured from green at
 *  TARGET_GREEN_DB (deep notch on target) to red at TARGET_RED_DB (shallow) by
 *  the measured attenuation there. */
const TARGET_LINE_WIDTH_PX = 3;
const TARGET_GREEN_DB = -90.0;
const TARGET_RED_DB = -50.0;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * The Tune-notch wizard dialog (Tools → Tune notch…). Owns ONLY the dialog:
 * the four numeric fields, the embedded live FreqRespView + its overlays, the
 * status line, and the continuous-sweep session lifecycle around the injected
 * collaborators. Faithful port of gui.freqresp.TuneNotchWizardDialog (see the
 * module header for the documented web adaptations).
 */
export class TuneNotchWizard {
  /**
   * @param {import('../audio/backend.js').AudioEngine} engine the live engine
   *   (postGen playback path + the ref-counted shared capture)
   * @param {import('../store/preferences.js').Preferences} prefs Preferences.instance()
   * @param {{modal: object}} deps
   *   - modal: the bootstrap Modal instance for #tuneNotchModal
   */
  constructor(engine, prefs, { modal }) {
    this.engine = engine;
    this.prefs = prefs;
    this.modal = modal;
    this.$ = window.jQuery;

    // The embedded view runs ISOLATED on a DETACHED preferences COPY (faithful
    // port of the desktop dialog, which hands the view Preferences.copyForDialog()):
    // every range / auto-fit / zoom / channel-visibility edit stays in the copy and
    // the shared main-pane FreqResp view is NEVER touched — no snapshot/restore
    // dance. The copy is a full, real Preferences (all Property objects intact), so
    // the view behaves exactly as with the live prefs; its save() is a no-op
    // (transient), so nothing persists. The tune-notch fields ALSO edit this copy;
    // only the four tune-notch* params are written back to the real prefs on close
    // (open() re-seeds them from real; _stopSweepLoop writes them back — Java's
    // copyForDialog line 760-764 seed + saveDialogPrefs write-back).
    this.viewPrefs = prefs.copyForDialog();
    // The tune-notch view NEVER shows the phase trace, independent of how the main
    // FreqResp is configured — force it off on the detached copy (the main pane's
    // freqRespPhaseVisible pref is untouched). Java sets the same on its dialog copy.
    this.viewPrefs.freqRespPhaseVisible.set(false);

    /** Empty, silent correction store for the embedded view — the notch
     *  session never loads / saves calibrations. */
    this.correctionStore = new FreqRespCorrectionStore('TuneNotch', null);

    this.canvas = document.getElementById('tnPlot');
    /** @type {FreqRespView|null} built once in bind() (the modal DOM is static) */
    this.view = null;
    this.startField = null;
    this.stopField = null;
    this.ampField = null;
    this.targetField = null;

    /** Number of grabs started, shown in the status line (Java sweepCount). */
    this._sweepCount = 0;

    // Notch readout, painted as an overlay on the embedded view.
    this._notchHz = 0;
    this._notchDb = 0;
    this._notchValid = false;

    /** Latest published result, read by the target-marker overlay to colour the
     *  marker by the attenuation at the target frequency. */
    this._latestResult = null;

    // Rolling MAG_AVG_FRAMES-sweep min/max dB ring for the AVERAGED vertical
    // range so it doesn't jump frame-to-frame.
    this._magRingMin = new Float64Array(MAG_AVG_FRAMES);
    this._magRingMax = new Float64Array(MAG_AVG_FRAMES);
    this._magRingPos = 0;
    this._magRingCount = 0;

    // Field values mirrored from the onChange listeners so the async sweep loop
    // reads a consistent value per iteration (Java's volatile mirror).
    this._curStartHz = 0;
    this._curStopHz = 0;
    this._curAmpVrms = 0;
    this._curTargetHz = 0;

    /** Drives the continuous sweep loop; cleared on close (Java `running`). */
    this._running = false;
    this._open = false;
    /** Guards {@link _teardownSession} so it runs once per session (the loop's
     *  abnormal-exit finally and the close path may both reach it). */
    this._teardownDone = true;
    /** The in-flight sweep loop, awaited on close (Java sweepThread.join). */
    this._loopPromise = null;
    /** The continuous play+capture session; closed once on dialog close. */
    this._notchEngine = null;
    /** True once the wizard started its OWN generator — the close path stops it
     *  and restores the pre-session DDS config (the captureAndDeconvolve pattern). */
    this._genStarted = false;
    /** Snapshot of the shared DDS config fields the session overwrites, restored
     *  on close so the user's generator settings survive the notch session. */
    this._savedGenConfig = null;
  }

  /** Wires the Tools-menu launcher, the modal close handler, the four
   *  NumericStepFields, and the embedded view + its paint overlays. Call once
   *  after the modal instance is live (i18n already loaded). */
  bind() {
    const $ = this.$;
    const F = UNIT_FAMILIES;
    const prefs = this.prefs;

    // Embedded chart (Java buildChart): a private FreqRespView over #tnPlot with
    // a silent empty correction store; no scrollbars, header controls are simply
    // not present (Java setHeaderControlsVisible(false)). The overlays are hooked
    // by WRAPPING render() — the web equivalent of the two SWT paint listeners
    // (target marker first, the notch readout text last so it stays legible).
    // The view renders through viewPrefs (its range / visibility detached from the
    // main pane), not the shared prefs — so wizard zoom/range never touches it.
    this.view = new FreqRespView(this.canvas, this.viewPrefs, this.correctionStore, { engine: this.engine });
    const baseRender = this.view.render.bind(this.view);
    this.view.render = () => { baseRender(); this._paintOverlays(); };

    // Fields row (Java buildFieldsRow): start / stop / amplitude / target.
    // Ranges: frequency [FREQ_MIN_HZ .. input Nyquist], amplitude
    // [AMP_MIN_VRMS .. DAC full-scale]; maxima re-pulled at each open().
    const mkField = (id, model, onChange) =>
      new NumericStepField(document.getElementById(id), model, { onChange });
    const nyquist = this._nyquistHz();
    this.startField = mkField('tnStart',
      new NumericStepModel({ family: F.FREQUENCY, min: FREQ_MIN_HZ, max: nyquist, maxDecimals: FREQ_MAX_DECIMALS }),
      (v) => {
        this._curStartHz = v;
        this.viewPrefs.tuneNotchStartHz.set(v);   // edit the COPY; written back to real on close
        this._applyFreqAxis();
        this._retuneEngineBand();
      });
    this.stopField = mkField('tnStop',
      new NumericStepModel({ family: F.FREQUENCY, min: FREQ_MIN_HZ, max: nyquist, maxDecimals: FREQ_MAX_DECIMALS }),
      (v) => {
        this._curStopHz = v;
        this.viewPrefs.tuneNotchStopHz.set(v);   // edit the COPY; written back to real on close
        this._applyFreqAxis();
        this._retuneEngineBand();
      });
    this.ampField = mkField('tnAmp',
      new NumericStepModel({ family: F.AMPLITUDE, min: AMP_MIN_VRMS, max: prefs.dacFsVoltageAmpl.get(), maxDecimals: AMP_MAX_DECIMALS }),
      (v) => {
        this._curAmpVrms = v;
        this.viewPrefs.tuneNotchAmplitudeVrms.set(v);   // edit the COPY; written back to real on close
      });
    this.targetField = mkField('tnTarget',
      new NumericStepModel({ family: F.FREQUENCY, min: FREQ_MIN_HZ, max: nyquist, maxDecimals: FREQ_MAX_DECIMALS }),
      (v) => {
        this._curTargetHz = v;
        this.viewPrefs.tuneNotchTargetHz.set(v);   // edit the COPY; written back to real on close
        if (this.view) this.view.render();
      });

    // Tools-menu launcher (Java MainWindow tuneNotchItem → openTuneNotchDialog).
    $('#menuTuneNotch').on('click', () => this.open());

    // The first paint needs the modal VISIBLE (the view sizes off clientWidth);
    // repaint once the fade-in lands.
    $('#tuneNotchModal').on('shown.bs.modal', () => { if (this.view) this.view.render(); });
    // Dialog close (Java SWT.Close → stopSweepLoop): stop the loop, close the
    // engine, restore the DDS + the shared view prefs, unlock the panes.
    $('#tuneNotchModal').on('hidden.bs.modal', () => { this._stopSweepLoop(); });

    return this;
  }

  /** Opens the wizard: seeds the fields from prefs, snapshots + repoints the
   *  shared view prefs at the notch session, shows the modal and starts the
   *  continuous sweep loop (Java open()). */
  open() {
    if (this._open) return;
    const prefs = this.prefs;

    // Re-pull the field maxima (the input rate / DAC full-scale may have
    // changed since bind), then seed the values + the loop-visible mirrors.
    const nyquist = this._nyquistHz();
    this.startField.setMax(nyquist);
    this.stopField.setMax(nyquist);
    this.targetField.setMax(nyquist);
    this.ampField.setMax(prefs.dacFsVoltageAmpl.get());
    // Re-seed the dialog COPY's tune-notch params from the shared prefs (Java
    // copyForDialog lines 760-764) so this session starts from the persisted values;
    // the fields edit the copy, and _stopSweepLoop writes them back to real on close.
    this.viewPrefs.tuneNotchStartHz.set(prefs.tuneNotchStartHz.get());
    this.viewPrefs.tuneNotchStopHz.set(prefs.tuneNotchStopHz.get());
    this.viewPrefs.tuneNotchAmplitudeVrms.set(prefs.tuneNotchAmplitudeVrms.get());
    this.viewPrefs.tuneNotchTargetHz.set(prefs.tuneNotchTargetHz.get());
    this.startField.setValue(this.viewPrefs.tuneNotchStartHz.get());
    this.stopField.setValue(this.viewPrefs.tuneNotchStopHz.get());
    this.ampField.setValue(this.viewPrefs.tuneNotchAmplitudeVrms.get());
    this.targetField.setValue(this.viewPrefs.tuneNotchTargetHz.get());
    this._curStartHz = this.startField.getValue();
    this._curStopHz = this.stopField.getValue();
    this._curAmpVrms = this.ampField.getValue();
    this._curTargetHz = this.targetField.getValue();

    // Seed the DETACHED view prefs for the notch session (R channel, [start,stop]
    // axis, wide initial window) BEFORE the first paint so it renders correctly
    // from the very first frame. No shared prefs are touched, so there's nothing
    // to snapshot / restore around the session.
    this._applySessionViewPrefs();

    this._open = true;
    this._latestResult = null;
    this._notchValid = false;
    this.view.clearResults();
    this.$('#tnStatus').text('');   // Java: the status label starts empty
    this.modal.show();
    this._startSweepLoop();
  }

  // ---------------------------------------------------------------------------
  // View-pref management (shared with the main FreqResp pane)
  // ---------------------------------------------------------------------------

  /** Seeds the DETACHED view prefs for the notch session: show the R
   *  (measurement / ch1) channel, set the frequency axis to [start, stop], and
   *  a wide initial magnitude window until the first sweep auto-fits it. Writes
   *  only viewPrefs (the private in-memory copy) — the main pane is untouched. */
  _applySessionViewPrefs() {
    const prefs = this.viewPrefs;
    prefs.freqRespRightVisible.set(true);
    prefs.freqRespLeftVisible.set(false);
    prefs.freqRespFreqMinHz.set(this._curStartHz);
    prefs.freqRespFreqMaxHz.set(this._curStopHz);
    prefs.freqRespMagTopDb.set(INITIAL_MAG_TOP_DB);
    prefs.freqRespMagBotDb.set(INITIAL_MAG_BOT_DB);
    prefs.save();
  }

  /** Re-anchors the (detached) chart's frequency axis to the current [start, stop]. */
  _applyFreqAxis() {
    const prefs = this.viewPrefs;
    prefs.freqRespFreqMinHz.set(this._curStartHz);
    prefs.freqRespFreqMaxHz.set(this._curStopHz);
    prefs.save();
    if (this.view) this.view.render();
  }

  /** Live-retunes the streamed sweep to the current [start, stop] without
   *  restarting the device — no-op until the engine is running. */
  _retuneEngineBand() {
    const e = this._notchEngine;
    if (!e) return;
    const b = sweepBand(this._curStartHz, this._curStopHz);
    e.setBand(b[0], b[1]);
  }

  /** Input Nyquist (the frequency fields' ceiling, Java prefs.current()
   *  .getInputSampleRate() / 2). */
  _nyquistHz() {
    return ((this.engine.config && this.engine.config.inRate)
      || this.prefs.current().inputSampleRate || 384000) / 2;
  }

  // ---------------------------------------------------------------------------
  // Continuous sweep loop
  // ---------------------------------------------------------------------------

  _startSweepLoop() {
    this._running = true;
    this._teardownDone = false;
    this._sweepCount = 0;
    this._magRingPos = 0;
    this._magRingCount = 0;
    // Publish FIRST (Java startSweepLoop, line 448): every consumer runs its stop
    // logic — GeneratorController.stopEngines, Scope/FftPane recorder stop + LED
    // gray, GeneratorPane Play-button visuals. The subscribers' returns only
    // guarantee they ASKED their engines to stop; the idle wait in _sweepLoop
    // below covers the async teardown before we open the device ourselves.
    MessageBus.instance().publish(Events.FREQRESP_MEASUREMENT_STARTED);
    // The main FreqResp Run buttons have no bus subscriber (a separate host owns
    // them) — disable them here for the session; re-enabled in _stopSweepLoop.
    this.$('#frRunStrip, #frRun').prop('disabled', true);

    this._loopPromise = this._sweepLoop();
  }

  /** The continuous session: open once (acquire capture + point the DDS at the
   *  looping sweep), then grab → deconvolve → display until close. Mirrors
   *  TuneNotchWizardDialog#sweepLoop; runs as one async task instead of a
   *  daemon thread. */
  async _sweepLoop() {
    const engine = this.engine;
    const prefs = this.prefs;
    const sampleRate = engine.config.inRate;
    // Read the DAC/ADC voltage references once: they don't change for the
    // lifetime of the session. (Java also reads ditherBits for its DAC dither;
    // the web capture path is float — no dither, intended divergence.)
    const dacFsVrms = prefs.dacFsVoltageAmpl.get();
    const adcFsVrms = prefs.adcFsVoltageRms.get();

    // The loop period MUST be a power of two — see powerOfTwoSweepSamples (the
    // circular-FFT invariant that makes an arbitrary-phase grab safe).
    const sweepSamples = powerOfTwoSweepSamples(sampleRate);
    const fadeSamples = notchFadeSamples(sampleRate);
    // One loop period of the captured stream, in ms — the grab cadence and the
    // percentage's denominator.
    const loopPeriodMs = Math.round((sweepSamples / sampleRate) * 1000.0);

    const notch = new NotchSweepEngine({
      sharedCapture: {
        acquire: () => engine.acquireCaptureReader(),
        release: () => engine.releaseCaptureReader(),
        getLastStartError: () => engine.getLastStartError(),
      },
      postGen: (msg) => engine.postGen(msg),
      sampleRate,
    });
    this._notchEngine = notch;
    // Match the output grid to the deconvolution's FFT bin spacing for ONE
    // captured period: a finer grid facets the trace into a kink + comb.
    const binHz = notch.deconvBinHz(sweepSamples);

    try {
      // Wait for the OTHER panes' workers to release the capture device + DAC after
      // the STARTED publish (Java sweepLoop's waitForOtherWorkersStopped) before we
      // open them ourselves; the subscribers signal-and-return, the OS release lags.
      if (!(await waitForWorkersIdle(engine, DEVICE_RELEASE_TIMEOUT_MS, () => !this._running))
          && this._running) {
        console.warn('TuneNotch: timeout waiting for other workers to release the audio device');
      }
      if (!this._running) return;

      // Open the session ONCE. The web playback path is the shared dds-processor
      // worklet, which only exists while the generator runs and postGen no-ops
      // otherwise — so start OUR OWN generator (silent LOG_SWEEP) before the engine
      // posts the looping sweep, mirroring FreqRespHost.captureAndDeconvolve. The
      // pre-session DDS config is snapshotted here and restored on close.
      const c = engine.config;
      this._savedGenConfig = {
        form: c.form, ampVrms: c.ampVrms,
        sweepStartHz: c.sweepStartHz, sweepEndHz: c.sweepEndHz,
        sweepDurationSec: c.sweepDurationSec, sweepLoop: c.sweepLoop,
        sweepFadeInSec: c.sweepFadeInSec, sweepFadeOutSec: c.sweepFadeOutSec,
      };
      const band = sweepBand(this._curStartHz, this._curStopHz);
      c.form = GenSignalForm.LOG_SWEEP;
      c.ampVrms = 0;   // start silent — notch.start un-mutes with the real amplitude
      c.sweepStartHz = band[0]; c.sweepEndHz = band[1];
      c.sweepDurationSec = sweepSamples / sampleRate;
      c.sweepLoop = true;
      c.sweepFadeInSec = fadeSamples / sampleRate; c.sweepFadeOutSec = fadeSamples / sampleRate;
      const startErr = await engine.startGenerator();
      if (startErr) throw new Error(t(startErr));
      this._genStarted = true;
      // Acquire the shared capture + point the now-running DDS at the looping sweep
      // (posts the real amplitude + loop/fade config); no per-sweep open/close.
      await notch.start(band[0], band[1], this._curAmpVrms, dacFsVrms, sweepSamples, fadeSamples);
      await this._awaitSettle(notch, sweepSamples);
      if (!this._running) return;

      while (this._running) {
        const startHz = this._curStartHz;
        const stopHz = this._curStopHz;
        const ampVrms = this._curAmpVrms;
        // Sample EXACTLY at the FFT bin centers (k·binHz): computeFromLogSweep
        // then reads each bin with fractional offset 0 — no phase-sensitive
        // interpolation BETWEEN bins (which made the trace wiggle frame-to-
        // frame). The view interpolates these stable points for the display.
        const freqs = binAlignedFreqs(startHz, stopHz, binHz, CHART_WIDTH_PX);

        const win = notch.latestPeriod(sweepSamples);
        if (!win) {
          await sleep(GRAB_RETRY_MS);
          continue;
        }
        this._sweepCount++;

        const sweepRef = notch.sweepRef();
        // Deconvolve INLINE (the web is single-threaded — Java overlaps this on
        // a one-thread executor), then idle out the rest of the loop period.
        this._deconvolveAndPublish(win, sweepRef, fadeSamples, freqs, sampleRate,
          startHz, stopHz, ampVrms, adcFsVrms);

        // Spread the percentage across the loop period (progress toward the
        // next result) while waiting for the next grab.
        await this._tickStreamingPercent(loopPeriodMs);
      }
    } catch (e) {
      console.error('TuneNotch streaming sweep failed', e);
      this._running = false;
      if (this._open) this._setStatus(t('freqResp.error.noDevice'));
    } finally {
      // If the loop exited abnormally (error / device release) while the dialog is
      // still open, tear the session down NOW so the capture + generator are freed
      // and STOPPED is published — the dialog stays fully closable either way. On a
      // normal close _stopSweepLoop has already flipped _open false and runs the
      // teardown itself after awaiting this promise (the guard makes it idempotent).
      if (this._open) await this._teardownSession();
    }
  }

  /** Waits until the ring has buffered one full period (or the session stops),
   *  showing the buffer-fill percentage on the status line. */
  async _awaitSettle(notch, sweepSamples) {
    while (this._running && notch.availableSamples() < sweepSamples) {
      const avail = notch.availableSamples();
      const pct = Math.trunc(Math.min(PERCENT_FULL, (avail * PERCENT_FULL) / sweepSamples));
      this._setStatus(pct + '%');
      await sleep(SETTLE_POLL_MS);
    }
  }

  /** Sleeps ~one loop period before the next grab, updating a rising percentage
   *  (elapsed / loopPeriod) on the status line every STATUS_TICK_MS. */
  async _tickStreamingPercent(loopPeriodMs) {
    const start = performance.now();
    let elapsed = 0;
    while (this._running && elapsed < loopPeriodMs) {
      const pct = Math.trunc(Math.min(PERCENT_FULL, (elapsed * PERCENT_FULL) / Math.max(1, loopPeriodMs)));
      this._setStatus(pct + '%');
      await sleep(Math.min(STATUS_TICK_MS, loopPeriodMs - elapsed));
      elapsed = performance.now() - start;
    }
  }

  /** Deconvolves one grabbed period's L+R channels, builds the stereo result,
   *  and pushes the R channel into the embedded view. The window is one
   *  steady-state period (leadIn 0); |H(f)| is phase-invariant so its alignment
   *  to the sweep-cycle start doesn't matter. Savitzky-Golay smoothing is OFF
   *  (Java's `false` flag): the bin-aligned grid is coarse and SG rounds a
   *  deep narrow null shallow. */
  _deconvolveAndPublish(win, sweepRef, fade, freqs, sampleRate,
    startHz, stopHz, ampVrms, adcFsVrms) {
    if (!this._running) return;
    try {
      const calL = computeFromLogSweep(win.left, sweepRef, 0, sampleRate, freqs, ampVrms, adcFsVrms, fade, false);
      const calR = computeFromLogSweep(win.right, sweepRef, 0, sampleRate, freqs, ampVrms, adcFsVrms, fade, false);
      const sweepParams = {
        startHz, stopHz, sweepPoints: freqs.length,
        durationSec: SWEEP_DURATION_SEC, leadInSec: SWEEP_LEAD_IN_SEC, amplitudeVrms: ampVrms,
      };
      const left = makeFreqRespResult('L', sampleRate, calL.freqs, calL.magLin, calL.phaseRad, sweepParams, null, false);
      const right = makeFreqRespResult('R', sampleRate, calR.freqs, calR.magLin, calR.phaseRad, sweepParams, null, false);
      const stereo = makeStereoResult(left, right);
      if (!this._running) return;
      this._onSweepResult(stereo.right);
    } catch (e) {
      console.error('TuneNotch deconvolution failed', e);
    }
  }

  /** Feeds a finished sweep into the embedded view, re-fits the magnitude axis
   *  to the band, and refreshes the notch readout (Java onSweepResult). */
  _onSweepResult(right) {
    if (!this._open || !right) return;
    this._latestResult = right;
    this.view.setRightResult(right);
    this._applyAutoMagWindow(right);
    this._computeNotch(right);
    this.view.render();
  }

  /** Finds the deepest notch (sub-bin parabolic refinement, ported in
   *  findDeepestNotch) and stores its frequency / depth for the overlay. */
  _computeNotch(right) {
    const n = findDeepestNotch(right.freqs, right.magLin);
    this._notchValid = n.valid;
    if (n.valid) { this._notchHz = n.hz; this._notchDb = n.db; }
  }

  /** Sets the magnitude axis from the MAG_AVG_FRAMES-sweep rolling AVERAGE of
   *  the per-sweep min/max dB (± AUTO_FIT_PAD_DB), so the vertical range
   *  doesn't jump hard frame-to-frame (Java applyAutoMagWindow). */
  _applyAutoMagWindow(right) {
    const mag = right.magLin;
    if (!mag || mag.length === 0) return;
    let minDb = Infinity;
    let maxDb = -Infinity;
    for (let i = 0; i < mag.length; i++) {
      const m = mag[i];
      if (m <= 0.0) continue;
      const db = 20.0 * Math.log10(m);
      if (db < minDb) minDb = db;
      if (db > maxDb) maxDb = db;
    }
    if (!Number.isFinite(minDb) || !Number.isFinite(maxDb)) return;
    this._magRingMin[this._magRingPos] = minDb;
    this._magRingMax[this._magRingPos] = maxDb;
    this._magRingPos = (this._magRingPos + 1) % MAG_AVG_FRAMES;
    if (this._magRingCount < MAG_AVG_FRAMES) this._magRingCount++;
    let sumMin = 0.0;
    let sumMax = 0.0;
    for (let i = 0; i < this._magRingCount; i++) {
      sumMin += this._magRingMin[i];
      sumMax += this._magRingMax[i];
    }
    const avgMin = sumMin / this._magRingCount;
    const avgMax = sumMax / this._magRingCount;
    const prefs = this.viewPrefs;
    prefs.freqRespMagTopDb.set(avgMax + AUTO_FIT_PAD_DB);
    prefs.freqRespMagBotDb.set(avgMin - AUTO_FIT_PAD_DB);
    prefs.save();
  }

  // ---------------------------------------------------------------------------
  // Status line + paint overlays
  // ---------------------------------------------------------------------------

  /** Updates the status line: "Sweep {0} — {1}" where {0} is the update counter
   *  and {1} is the live percentage (buffer-fill during the initial settle,
   *  then progress toward the next result). Java setStatus. */
  _setStatus(message) {
    this.$('#tnStatus').text(t('tuneNotch.status', this._sweepCount, message));
  }

  /** Overlay pass appended to every view render (the web stand-in for the two
   *  SWT paint listeners): the target marker first, the notch readout text last
   *  so it stays legible on top. */
  _paintOverlays() {
    if (!this._open) return;
    this._paintTarget();
    this._paintNotch();
  }

  /** Paints a TARGET_LINE_WIDTH_PX dashed vertical marker at the target
   *  frequency, coloured by the measured attenuation AT that frequency:
   *  TARGET_GREEN_DB → green, TARGET_RED_DB → red, linear between (a deep notch
   *  landed on the target reads green; off-target or shallow reads red).
   *  Java onTargetPaint. */
  _paintTarget() {
    const res = this._latestResult;
    const target = this._curTargetHz;
    // Read the view's DETACHED range so the marker lands on the same log axis the
    // trace was drawn on (phase-visible delegates through to the shared pref).
    const prefs = this.viewPrefs;
    const fMin = prefs.freqRespFreqMinHz.get();
    const fMax = prefs.freqRespFreqMaxHz.get();
    if (!res || target <= 0.0 || fMin <= 0.0 || fMax <= fMin
      || target < fMin || target > fMax) return;
    const dbAtTarget = dbAtFrequency(res.freqs, res.magLin, target);
    if (!Number.isFinite(dbAtTarget)) return;

    // Plot rectangle — mirrors the web FreqRespView's margins so the marker
    // lands on the same log frequency axis the trace is drawn on.
    const cv = this.canvas;
    const W = cv.clientWidth || CANVAS_WIDTH_PX;
    const H = cv.clientHeight || CANVAS_HEIGHT_PX;
    const rightMargin = prefs.freqRespPhaseVisible.get() ? VIEW_MARGIN_RIGHT_PHASE : VIEW_MARGIN_RIGHT_NO_PHASE;
    const plotW = Math.max(1, W - VIEW_MARGIN_LEFT - rightMargin);
    const plotTop = VIEW_MARGIN_TOP;
    const plotBot = Math.max(plotTop + 1, H - VIEW_MARGIN_BOTTOM);
    const frac = (Math.log(target) - Math.log(fMin)) / (Math.log(fMax) - Math.log(fMin));
    const x = VIEW_MARGIN_LEFT + Math.round(frac * plotW);

    const tt = Math.max(0.0, Math.min(1.0,
      (dbAtTarget - TARGET_GREEN_DB) / (TARGET_RED_DB - TARGET_GREEN_DB)));
    const r = Math.round(255.0 * tt);
    const gr = Math.round(255.0 * (1.0 - tt));

    const g = this.view.g;
    g.save();
    g.strokeStyle = `rgb(${r},${gr},0)`;
    g.lineWidth = TARGET_LINE_WIDTH_PX;
    g.setLineDash([4, 4]);
    g.beginPath();
    g.moveTo(x, plotTop);
    g.lineTo(x, plotBot);
    g.stroke();
    g.restore();
  }

  /** Paints the deepest-notch readout in the plot's top-left corner: black text
   *  with a one-pixel white outline so it reads on either a light or dark
   *  trace. Java onNotchPaint ("%.4f Hz   %.3f dB"). */
  _paintNotch() {
    if (!this._notchValid) return;
    const s = `${this._notchHz.toFixed(4)} ${t('unit.hz')}   ${this._notchDb.toFixed(3)} ${t('unit.db')}`;
    const g = this.view.g;
    g.save();
    g.font = '11px "Segoe UI", sans-serif';
    g.textAlign = 'left';
    g.textBaseline = 'top';
    g.fillStyle = '#fff';
    for (let dx = -NOTCH_OUTLINE_PX; dx <= NOTCH_OUTLINE_PX; dx++) {
      for (let dy = -NOTCH_OUTLINE_PX; dy <= NOTCH_OUTLINE_PX; dy++) {
        if (dx === 0 && dy === 0) continue;
        g.fillText(s, NOTCH_TEXT_X_PX + dx, NOTCH_TEXT_Y_PX + dy);
      }
    }
    g.fillStyle = '#000';
    g.fillText(s, NOTCH_TEXT_X_PX, NOTCH_TEXT_Y_PX);
    g.restore();
  }

  // ---------------------------------------------------------------------------
  // Close / teardown (Java stopSweepLoop)
  // ---------------------------------------------------------------------------

  async _stopSweepLoop() {
    this._open = false;
    this._running = false;
    // Persist the edited tune-notch params from the dialog COPY back into the shared
    // prefs (Java saveDialogPrefs) — the ONLY values the session writes back; the copy's
    // view range / channel / phase edits are dropped, leaving the main pane untouched.
    const p = this.prefs, v = this.viewPrefs;
    p.tuneNotchStartHz.set(v.tuneNotchStartHz.get());
    p.tuneNotchStopHz.set(v.tuneNotchStopHz.get());
    p.tuneNotchAmplitudeVrms.set(v.tuneNotchAmplitudeVrms.get());
    p.tuneNotchTargetHz.set(v.tuneNotchTargetHz.get());
    p.save();
    // Wait for the in-flight loop iteration to wind down (Java sweepThread
    // .join; the loop's sleeps are ≤ STATUS_TICK_MS so this is quick). Its
    // finally sees _open === false and defers the teardown to us.
    if (this._loopPromise) {
      try { await this._loopPromise; } catch (_) { /* logged in the loop */ }
      this._loopPromise = null;
    }
    await this._teardownSession();
    // No shared view prefs to restore — the wizard's range lives in viewPrefs.
    this._latestResult = null;
    this._notchValid = false;
  }

  /** Idempotent session teardown (Java stopSweepLoop's device release + STOPPED
   *  publish): releases the shared capture, stops the wizard's OWN generator and
   *  restores the pre-session DDS config, publishes FREQRESP_MEASUREMENT_STOPPED
   *  so the scope / FFT / generator panes re-enable themselves, and frees the main
   *  FreqResp Run buttons (which have no bus subscriber). Safe to call from both the
   *  close path and the loop's abnormal-exit finally — the guard runs the body once. */
  async _teardownSession() {
    if (this._teardownDone) return;
    this._teardownDone = true;
    // Release the shared capture reference held for the whole session.
    const notch = this._notchEngine;
    this._notchEngine = null;
    if (notch) {
      try { await notch.close(); } catch (e) { console.error('TuneNotch close failed', e); }
    }
    // Stop OUR OWN generator + restore the pre-session DDS config (mirror of
    // FreqRespHost.captureAndDeconvolve's finally).
    if (this._genStarted) {
      this._genStarted = false;
      try { await this.engine.stopGenerator(); } catch (e) { console.error('TuneNotch stopGenerator failed', e); }
    }
    if (this._savedGenConfig) {
      Object.assign(this.engine.config, this._savedGenConfig);
      this._savedGenConfig = null;
      this.engine._computeAnalysisFreqs();   // re-derive snapped/binW off the restored form
    }
    // FREQRESP_MEASUREMENT_STOPPED: the scope / FFT / generator panes re-enable
    // their LEDs + Play buttons on this event (their own subscriptions).
    MessageBus.instance().publish(Events.FREQRESP_MEASUREMENT_STOPPED);
    this.$('#frRunStrip, #frRun').prop('disabled', false);
  }
}
