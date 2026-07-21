/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Frequency-response (Farina) PANE shell (Java gui/freqresp/FreqRespPane): the layout + play
// orchestration + the header L/R / phase / auto / maximize toggles + the RANGE_CHANGED repaint.
// The heavy lifting lives in its collaborators, mirroring the Java christmas-tree split:
//   - FreqRespController — sweep-timing rules + the measurement lifecycle (runSweep /
//     captureAndDeconvolve + the busy live-meter), publishes FREQRESP_MEASUREMENT_* ;
//   - FreqRespTabControl — the settings strip (dropdowns, RIAA, presets, save/load, calibration);
//   - FreqRespView       — the interactive canvas (axes, zoom/pan, render-time .frc de-embed);
//   - FreqRespWizard     — the 3-page guided calibration flow (reuses the sweep runner + confirm).
// All DSP comes from the ported web/js/freqresp/* modules; this is wiring.

import { FreqRespController } from './freqresp-controller.js';
import { FreqRespView } from './freqresp-view.js';
import { FreqRespTabControl } from './freqresp-tab-control.js';
import { CorrectionStore } from '../common/correction-store.js';
import { FreqRespWizard } from './wizard.js';
import { MessageBus } from '../bus/message-bus.js';
import { Events } from '../bus/events.js';

export class FreqRespPane {
  /**
   * @param {import('../audio/backend.js').AudioEngine} engine the live engine
   * @param {object} prefs Preferences.instance()
   * @param {{saveFile:Function, openFile:Function, bytesToText:Function}} io file-picker glue
   */
  constructor(engine, prefs, io) {
    this.engine = engine;
    this.prefs = prefs;
    this.io = io;
    this.$ = window.jQuery;

    this.canvas = document.getElementById('frPlot');

    // Loaded-correction store (Java CorrectionStore): the .frc entries + wizard `direct`
    // buffer the view divides out at render time. The change callback retraces the view AND
    // rebuilds the calibration-tab rows — both handled by the tab control's onStoreChanged.
    this.correctionStore = new CorrectionStore('FreqResp',
      () => this.tabControl.onStoreChanged());

    // The interactive view owns the canvas: axes, zoom/pan, crosshair, scrollbars, render-time
    // calibration. The L/R radio + RIAA/phase toggles re-derive + repaint.
    this.view = new FreqRespView(this.canvas, prefs, this.correctionStore, {
      engine,
      freqScroll: document.getElementById('frFreqScroll'),
      magScroll: document.getElementById('frMagScroll'),
      onRangeChanged: null,
    });

    // Sweep-timing rules (fftSize/leadIn → derived durationSec) + the measurement lifecycle
    // (runSweep / captureAndDeconvolve + busy meter). Drives the passive view directly.
    this.controller = new FreqRespController(engine, prefs, this.view, this.correctionStore);

    // Settings strip (Java FreqRespTabControl): sweep/FFT/dither dropdowns + derived-duration
    // label, RIAA tab, presets, utility, save/load, and the multi-row calibration loader.
    this.tabControl = new FreqRespTabControl(engine, prefs, this.view, this.correctionStore, io, this.controller);

    // Calibration wizard (Java FreqRespWizardDialog): the 3-page guided flow. Reuses this pane's
    // sweep runner (captureAndDeconvolve, delegated to the controller) + showConfirm; Cancel
    // restores a pre-wizard snapshot of the correction store.
    this.wizard = new FreqRespWizard(this, prefs, this.view, this.correctionStore);

    this.bind();
  }

  status(t) { this.$('#status').text(t); }

  bind() {
    const $ = this.$;
    // The L/R radio (.lr.l/.lr.r) reflects the persisted channel-visible state.
    $('#tab-fr .lr.l').toggleClass('on', this.prefs.freqRespLeftVisible.get());
    $('#tab-fr .lr.r').toggleClass('on', this.prefs.freqRespRightVisible.get());

    // The settings strip (dropdowns, RIAA, presets, utility, save/load, calibration) is bound by
    // the tab control. Its i18n-dependent parts run from seedTabs() in app.js's init.
    this.tabControl.bind();

    // L/R header radio (index.html .lr.l / .lr.r): selects which channel the view shows.
    // Mutually exclusive; persists freqRespLeftVisible / RightVisible.
    $('#tab-fr .lr.l').on('click', () => {
      this.prefs.freqRespLeftVisible.set(true); this.prefs.freqRespRightVisible.set(false); this.prefs.save();
      $('#tab-fr .lr.l').addClass('on'); $('#tab-fr .lr.r').removeClass('on');
      this.view.render();
    });
    $('#tab-fr .lr.r').on('click', () => {
      this.prefs.freqRespRightVisible.set(true); this.prefs.freqRespLeftVisible.set(false); this.prefs.save();
      $('#tab-fr .lr.r').addClass('on'); $('#tab-fr .lr.l').removeClass('on');
      this.view.render();
    });
    // Header phase / auto-setup / maximize icons (Java .lr-tools: phase toggle →
    // freqRespPhaseVisible, auto-setup → autoSetupMagnitudeRange, maximize → resetToDefaultView).
    $('#tab-fr .lr.ico').eq(0).toggleClass('on', this.prefs.freqRespPhaseVisible.get());
    $('#tab-fr .lr.ico').eq(0).on('click', () => {
      const on = !this.prefs.freqRespPhaseVisible.get();
      this.prefs.freqRespPhaseVisible.set(on);
      $('#tab-fr .lr.ico').eq(0).toggleClass('on', on);
      this.view.render();
    });
    $('#tab-fr .lr.ico').eq(1).on('click', () => this.view.autoSetupMagnitudeRange());
    $('#tab-fr .lr.ico').eq(2).on('click', () => this.view.resetToDefaultView());

    // Both the visible strip Play (#frRunStrip) and the legacy hidden #frRun drive the same sweep
    // on the controller; the strip is the one the user sees, #frRun stays for the e2e smoke.
    $('#frRunStrip, #frRun').on('click', () => this.controller.runSweep());
    // Wizard button (Java FreqRespPane wizard launch).
    $('#tab-fr .fft-tool-btn').on('click', () => this.wizard.open());

    // FreqResp range changed (Java FreqRespPane.java:145,158: rangeChangedListener ->
    // syncScrollbars()). Published by the Preferences dialog OK path after the Nyquist-fraction
    // freq-window clamp so the view re-clamps its max frequency and re-syncs its scrollbars.
    MessageBus.instance().subscribe(Events.FREQRESP_RANGE_CHANGED, () => {
      this.view.syncScrollbars();
      this.view.render();
    });
    // STOPPED → re-run the RIAA enable cascade (Java FreqRespPane.java:159): a successful sweep
    // makes Compare available, a failed / aborted one leaves it off. The measurement publishes
    // STOPPED from the controller's finally on BOTH paths.
    MessageBus.instance().subscribe(Events.FREQRESP_MEASUREMENT_STOPPED, () => this.tabControl.refreshRiaaEnable());
  }

  /** Builds the i18n-dependent tab UI (calibration rows, preset list, RIAA enable). Called from
   *  app.js's init AFTER the i18n bundle is loaded so t() resolves real strings. */
  seedTabs() { this.tabControl.seedTabs(); }

  /** "FFT size (D.Ds)" derived-duration label (delegated; app.js's init calls it after i18n). */
  refreshFftLabel() { this.tabControl.refreshFftLabel(); }

  /** The set of cal-store hashes referenced by the calibration rows (app.js prune union). */
  getCalHashes() { return this.tabControl.getCalHashes(); }

  /** The cal-restore promise app.js awaits (with the FFT pane's) before pruneCals (issue 2.3). */
  get _calRestore() { return this.tabControl._calRestore; }

  /** Bootstrap confirm modal (the wizard's Cancel-with-unsaved prompt calls pane.showConfirm).
   *  Single impl lives on the tab control (presets use it too); delegated here. */
  showConfirm(title, message) { return this.tabControl.showConfirm(title, message); }

  /** True while a sweep is running (the wizard reads pane.running before starting a leg). */
  get running() { return this.controller.running; }

  /** Runs one stereo loopback sweep + deconvolution (the wizard reuses the controller's runner). */
  captureAndDeconvolve(applyDirect) { return this.controller.captureAndDeconvolve(applyDirect); }

  /** Repaints the interactive view (kept for app.js's init `freqResp` step + any external repaint
   *  trigger). The view owns the canvas now. */
  plot() {
    this.view.render();
    this.view.syncScrollbars();
  }
}
