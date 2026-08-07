/*
 * Phonalyser web - the oscilloscope PANE (trace canvas · vertical-offset + horizontal-nav
 * FlatScrollbars · Record LED · measurement table + pop-out window · file-mode load/scroll ·
 * the scope branch of the rAF render loop).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of gui/scope/ScopePane. Owns the live scope view wiring (the ScopeView canvas,
 * the two navigation scrollbars, the Record LED, the measurement-table render + pop-out window,
 * the file-mode loaded-signal state) and IS the Host the scope SETTINGS strip (ScopeTabControl)
 * reaches it through - mirroring Java ScopePane implements ScopeTabControl.Host. The former
 * scopeHost closures (requestRedraw / refreshTiles / refreshFields / syncTriggerStart /
 * setTriggerControlsEnabled / syncOffsetScrollbar / redrawScrollbars / syncMeasChannelButtons /
 * syncMeasButtons / recState / onFileBack / stopCaptureForFileLoad / onSignalFileLoaded) are now
 * the pane's public methods. render() is the scope branch of the MAIN rAF loop (which stays in
 * app.js for now and calls scopePane.render() each frame). Reads engine.scope.recording (the
 * controller owns the recording flag) and reaches the SHELL state it does not own (the shared
 * `busy` re-entrancy guard, the readConfig snapshot, the latest live scope frame) through
 * injected closures. The scope V/div + t/div + hysteresis NumericStepFields are
 * built in app.js's initStepFields and reached here via the injected getField; the ScopeView,
 * the FlatScrollbar widget class (imported directly), and tileChips are injected too.
 */
import { t } from '../i18n/i18n.js';
import { makeDraggable } from '../ui/draggable.js';
import { isDualTone } from '../generator/dds-kernel.js';
import { MessageBus } from '../bus/message-bus.js';
import { Events, GenChangeCause } from '../bus/events.js';
import { FlatScrollbar } from '../widgets/flat-scrollbar.js';
import { offsetMoveHalfRange } from './scope-format.js';
import { DIVISIONS_Y } from './scope-nav.js';
import { HistogramView } from './histogram-view.js';
import { backendDisplayName } from '../audio/audio-backend-type.js';

// ----- scope navigation scrollbars (Java ScopePane vertSlider / navSlider) -----
// Fixed integer slider range; the offsetFrac / horizontal-pan value is derived
// from the selection via the bounds (Java NAV_RANGE = 1_000_000).
const NAV_RANGE = 1_000_000;
const MIN_SCROLLBAR_THUMB = Math.round(NAV_RANGE / 33);
// Condensed-overview redraw decimation (Java CONDENSED_DECIMATION = 10): the
// zoomed view walks ~1 s of audio per paint, so update it at a fraction of the
// main trace's cap/s.
const SCOPE_ZOOM_DECIMATION = 10;
// Delay before a USER generator change drops the held trigger anchor (Java
// ScopeTabControl.GEN_CLEAR_DELAY_MS) - covers the DAC -> loopback -> ADC ->
// capture-buffer latency so the reset lands after the OLD signal has flushed out
// of the display path. (Java also wipes the GPU phosphor afterglow here; the web
// Canvas2D scope has no persistence - that part is skipped.)
const GEN_CLEAR_DELAY_MS = 250;

export class ScopePane {
  /**
   * @param engine the AudioEngine (scope record lifecycle; live measurement / zoomed-window reads).
   * @param prefs  Preferences.
   * @param deps   {view, getField, tileChips, isScopeRec, isBusy, setBusy,
   *                getLatestScope, readConfig, syncCalibrateGate}
   *   - view: the ScopeView (Java holds `view` as a field) - the trace canvas painter,
   *       single-armed / file-mode state, latest measurement, auto-setup.
   *   - getField: (id) => the scope NumericStepField (built in app.js initStepFields) - the
   *       hysteresis field re-gate in setTriggerControlsEnabled.
   *   - tileChips: (...vals) => the `.tile` chip-span HTML (shared with the FFT tiles).
   *   - isScopeRec: () => engine.scope.recording - the controller owns the flag; the pane only reads it.
   *   - isBusy / setBusy: the shared async re-entrancy guard accessors (Record serializes with it).
   *   - getLatestScope: () => the latest live scope frame {buf, info} (set by engine.onScope in app.js).
   *   - readConfig: () => snapshot the live UI into engine.config before a record start.
   *   - syncCalibrateGate: () => re-gate the ScopeTabControl Calibrate button each live frame
   *       (the tab-control is built AFTER the pane, so app.js defers to it through this closure).
   */
  constructor(engine, prefs, { view, getField, tileChips, isScopeRec,
    isBusy, setBusy, getLatestScope, readConfig, syncCalibrateGate }) {
    this.engine = engine;
    this.prefs = prefs;
    this.view = view;
    this._getField = getField;
    this._tileChips = tileChips;
    this._isScopeRec = isScopeRec;
    this._isBusy = isBusy;
    this._setBusy = setBusy;
    this._getLatestScope = getLatestScope;
    this._readConfig = readConfig;
    this._syncCalibrateGate = syncCalibrateGate;

    // ----- scope navigation scrollbars (Java ScopePane vertSlider / navSlider) -----
    // Vertical: thumb at TOP = signal up (low offsetFrac = trace anchored toward the
    // grid top), thumb at BOTTOM = signal down - maps selection ∈ [0, NAV_RANGE] to
    // offsetFrac ∈ [lo, hi]. Horizontal: selection ∈ [0, NAV_RANGE] = how far back
    // from the latest the view is scrolled; rightmost = follow latest (file mode only).
    this.scopeVScroll = new FlatScrollbar(document.getElementById('scopeVScroll'),
      { vertical: true, onChange: (sel) => this.onVertScrollMoved(sel) });
    this.scopeVScroll.setMinimum(0); this.scopeVScroll.setMaximum(NAV_RANGE);
    this.scopeVScroll.setThumb(Math.max(1, Math.round(NAV_RANGE / 10)));
    this.scopeVScroll.setIncrement(Math.round(NAV_RANGE / 100));
    this.scopeVScroll.setPageIncrement(Math.round(NAV_RANGE / 10));

    this.scopeHScroll = new FlatScrollbar(document.getElementById('scopeHScroll'),
      { vertical: false, onChange: (sel) => this.onHorizScrollMoved(sel) });
    this.scopeHScroll.setMinimum(0); this.scopeHScroll.setMaximum(NAV_RANGE);
    this.scopeHScroll.setThumb(Math.max(1, Math.round(NAV_RANGE / 20)));
    this.scopeHScroll.setSelection(NAV_RANGE);

    this.scopeZoomDecim = 0;

    // ----- file-mode loaded-signal state (Java ScopePane navSlider / loaded buffer) -----
    this.scopeFileBack = 0;   // view back-offset in frames for the loaded signal
    this.loadedScope = null;  // {left, right, frames, sampleRate} of the loaded file (file mode)

    // ----- measurement-table pop-out state -----
    this.measTablePopped = false;
    // Reused measurement-table row/cell nodes (built once by ensureMeasTableRows).
    this.measTableCells = null;

    // Auto-fit-once-measured latch for the live scope (set false each frame in render()).
    this.scopeAutoPending = false;

    // Debug/e2e hook, mirroring FftPane: the help-screenshot capture has to reach the live
    // histogram accumulators to pose the window with a known distribution.
    if (typeof window !== 'undefined') window.__scopePane = this;

    // FreqResp measurement lifecycle (Java ScopePane freqRespStarted/StoppedListener):
    // the sweep needs the capture device exclusively - stop a running capture and gray
    // the Record LED on STARTED, re-enable it on STOPPED.
    const bus = MessageBus.instance();
    bus.subscribe(Events.FREQRESP_MEASUREMENT_STARTED, () => this.onFreqRespMeasurementStarted());
    bus.subscribe(Events.FREQRESP_MEASUREMENT_STOPPED, () => this.onFreqRespMeasurementStopped());
    // The controller stopped the capture on its own - today when a device reopen fails to
    // re-acquire (ScopeController.reattach). The pane owns the Record LED, so it has to reconcile:
    // without this the trace froze while the LED stayed lit, i.e. the scope LOOKED like it was
    // still running.
    // ...and also when the capture ENDED FROM BELOW (device lost / delivery
    // stalled). When the controller CLAIMED that report, this pane owns the failed operation, so
    // this pane tells the operator ONCE, in their language (Java ScopePane's
    // recordingStoppedListener). Null reason = a programmatic stop, or the FFT pane claimed it.
    bus.subscribe(Events.SCOPE_RECORDING_STOPPED, () => {
      this.syncScopeLed();
      const reason = this.engine.scope.captureEndReason();
      if (reason) this.showCaptureEndedAlert(reason);
    });
    // The INPUT device died mid-capture (unplugged, or grabbed exclusively) - the capture source
    // publishes the error, the shell shows the alert, and the scope must actually STOP: keeping
    // the consumer on the dead line left the trace drawing a flat line with cap/s still ticking -
    // a measurement of nothing presented as a measurement. Stop via the
    // ENGINE unconditionally, exactly as onFreqRespMeasurementStarted does - the stop releases the
    // shared-capture ref, so the dead device line closes on the last release.
    bus.subscribe(Events.AUDIO_DEVICE_ERROR, async (p) => {
      if (p && p.direction === 'input') {
        await this.engine.scope.setRecording(false);
        this.syncScopeLed();
      }
    });

    // Wire the osc-meas measurement stream (owned by engine.scope): the pane has the prefs,
    // so it supplies the per-batch publish PARAMS provider; the worker's publishes are
    // pushed straight into the view (its publish contract, off the render thread). The
    // controller starts/stops the worker + its own gapless ring reader with the recording.
    this.engine.scope.setMeasParamsProvider(() => this._measParams());
    this.engine.scope.setMeasResultSink((r) => { this.view.publishMeasurement(r); this.renderHistogram(); });
    // The histogram plot: its own view, fed a SNAPSHOT of the selected channel's distribution and
    // that channel's peak volts read at PAINT time - so a recalibration relabels the axis without
    // disturbing a single collected count.
    this._histogramView = new HistogramView(document.getElementById('scopeHist'), {
      snapshot: () => this.engine.scope.histogramSnapshot(this.prefs.oscHistogramChannel.get()),
      peakVolts: () => this.prefs.getAdcPeakVolts(this.prefs.oscHistogramChannel.get()),
      barCount: () => this.prefs.oscHistogramBins.get(),
      // Java HistogramView's own ColorRole map: black plot, 0x3C3C3C grid + axis, 0xF0F0F0 text.
      palette: () => ({
        background: '#000000', grid: '#3c3c3c', axis: '#3c3c3c', text: '#f0f0f0',
        bar: this.prefs.oscHistogramChannel.get() === 'R'
          ? `#${this.prefs.oscRightChannelColor.get().toString(16).padStart(6, '0')}`
          : `#${this.prefs.oscLeftChannelColor.get().toString(16).padStart(6, '0')}`,
      }),
    });
    // A view-side clearMeasurementHistory (stats reset / channel switch) also resets the
    // live worker's stream (spec §5) - the view can't reach the controller, so route through
    // the pane.
    // A stats reset / channel switch also restarts the amplitude distribution: the same reasoning
    // as the statistics themselves - a channel change means the counts describe a different signal.
    this.view.onClearMeasurement = () => {
      this.engine.scope.resetMeasurement();
      this.engine.scope.resetHistograms();
      this.renderHistogram();
    };
    // Generator start/stop IS a signal change, so the distribution restarts with it - averaging
    // before and after together would be wrong (Java publishes GENERATOR_SIGNAL_CHANGED / USER_INPUT
    // on a real transition for exactly this).
    MessageBus.instance().subscribe(Events.GENERATOR_SIGNAL_CHANGED, () => {
      this.engine.scope.resetHistograms();
      this.renderHistogram();
    });
  }

  /** The current osc-meas publish parameters read from prefs + the live config/generator
   *  (Java ScopeMeasurementWorker re-reads Preferences each pass). Returns null when the
   *  measurement table is off so the client skips the batch (never backlogs the cursor). */
  _measParams() {
    const p = this.prefs, c = this.engine.config;
    if (!p || !p.oscShowMeasurementTable.get()) return null;
    // Dual flag from the LIVE pref, exactly like Java ScopeMeasurementWorker.measureChannel
    // (dual = prefs.getGenSignalForm().isDualTone()), re-read every publish pass - NOT the
    // engine.config snapshot, which only refreshes on a generator (re)start (readConfig).
    // A form change to DUAL_TONE updates the pref immediately (the dropdown's prefs.set),
    // so with the snapshot the worker measured a stale single tone (the sum-crossing f)
    // until the generator happened to restart; the live pref closes that window.
    // Same rule for peakVolts below: read adcFsVoltageRms from the LIVE pref (Java
    // ScopeMeasurementWorker.java:444 re-reads prefs.getAdcFsVoltageRms() each compute pass),
    // NOT c.adcFsVoltageRms - an ADC calibration rescales the pref mid-capture, and the
    // config snapshot would keep the stale full-scale until the next capture restart (the
    // "reading doesn't move after calibrate -> double-calibration" bug).
    const dual = isDualTone(p.genSignalForm.get());
    const per = (name) => ({
      // NO lpfMode: the display LPF / de-spike never reaches a measured value (Java has no
      // applyHfLowPass / applyChannelHf in ScopeMeasurementWorker).
      mainsMode: p['osc' + name + 'MainsSuppression'].get(),
      dual, f1Hz: this.engine.scope.snapped, f2Hz: this.engine.scope.snapped2,
    });
    return {
      sampleRate: c.inRate,
      // Each channel scales by its OWN ADC full-scale (Java ScopeMeasurementWorker.java:448-9
      // getAdcPeakVolts(Channel.L)/(Channel.R)); LINKED cards give equal L/R peaks. Read
      // LIVE (getAdcPeakVolts reads the pref Property) so an ADC calibration rescales mid-
      // capture without the stale-snapshot double-calibration bug.
      peakVoltsL: p.getAdcPeakVolts('L'), peakVoltsR: p.getAdcPeakVolts('R'),
      avgSeconds: p.oscMeasurementAverageSeconds.get(),
      L: per('Left'), R: per('Right'),
    };
  }

  /** The unified capture-death message (Java ScopePane's recordingStoppedListener / FftPane's
   *  twin): the reason picks the text, the BACKEND is named, and the technical detail lives in
   *  the log alone. Raised through the shell's one alert surface (the web's Dialogs.error). */
  showCaptureEndedAlert(reason) {
    MessageBus.instance().publish(Events.AUDIO_DEVICE_ERROR, {
      direction: 'input',
      reason: reason.name,
      message: t('capture.error.ended.' + reason.name,
        backendDisplayName(this.engine.activeBackend())),
    });
  }

  /** Stops a running capture and grays the Record LED - fired by the Frequency Response
   *  pane via FREQRESP_MEASUREMENT_STARTED so the sweep can take exclusive control of the
   *  device (Java ScopePane.onFreqRespMeasurementStarted). */
  async onFreqRespMeasurementStarted() {
    $('.scope-pane .led-btn').prop('disabled', true);
    // Stop the scope via the ENGINE unconditionally - NOT gated on the shell record flag
    // (see FftPane.onFreqRespMeasurementStarted): a shell/controller desync must not leave
    // the scope consuming the sweep. setRecording is a no-op when already off; sync the
    // LED to the engine's real state.
    await this.engine.scope.setRecording(false);
    this.syncScopeLed();
  }

  /** Counterpart that re-enables the Record LED after the sweep finishes (or aborts)
   *  (Java ScopePane.onFreqRespMeasurementStopped). */
  onFreqRespMeasurementStopped() {
    $('.scope-pane .led-btn').prop('disabled', false);
  }

  // ----- scope tiles: each tab's KEY SETTINGS (Java ScopeTabControl.scopeTabTiles),
  // NOT live measured values. Left/Right -> led(if enabled) + V/div + ac|dc + sin|lin;
  // Horizontal -> t/div; Trigger -> channel L|R + edge ↑|↓ + mode A|N|S + (H x.x if hyst).
  // Driven from the osc* prefs that back the scope controls, refreshed whenever one
  // of them changes (mirrors Java's toolbarTabs.refreshTab on every binding change).
  // shortSi / shortVoltsPerDiv / shortTimePerDiv mirror Java ScopeFormat.
  shortNum(v) {
    const iv = Math.round(v);
    if (Math.abs(v - iv) < 1e-4) return String(iv);
    return v.toFixed(1);
  }
  shortSi(v) {
    if (v <= 0) return '0';
    if (v >= 1.0) return this.shortNum(v);
    if (v >= 1e-3) return this.shortNum(v * 1e3) + 'm';
    if (v >= 1e-6) return this.shortNum(v * 1e6) + 'u';
    return this.shortNum(v * 1e9) + 'n';
  }
  shortTimePerDiv(v) {
    if (v > 0 && v < 1e-6) return this.shortNum(v * 1e9) + 'n';
    return this.shortSi(v);
  }
  // Per-chip titled tile span (Java scopeTabTiles attaches a tooltip to every tile via
  // the scope.tile.* keys); [text, title] pairs, null entries dropped.
  titledChips(...pairs) {
    return pairs.filter(p => p && p[0] != null && p[0] !== '')
      .map(([txt, title]) => `<span class="tile"${title ? ` title="${title}"` : ''}>${txt}</span>`).join('');
  }
  refreshScopeTiles() {
    const prefs = this.prefs;
    const $tiles = $('#scopeTabs .tab .t-sub');
    // LED chip only when the channel is enabled (Java scopeTabTiles gates on enabled); the LED
    // tile carries the scope.tile.led.active tooltip.
    const led = (on, chName) => on
      ? `<span class="tile" title="${t('scope.tile.led.active', chName)}"><span class="led-sm"></span></span>` : '';
    const chTile = (isLeft) => {
      const chName = t(isLeft ? 'scope.tab.left' : 'scope.tab.right');
      const on = isLeft ? prefs.oscLeftChannelEnabled.get() : prefs.oscRightChannelEnabled.get();
      const vDiv = isLeft ? prefs.oscLeftVoltsPerDiv.get() : prefs.oscRightVoltsPerDiv.get();
      const ac = isLeft ? prefs.oscLeftAcMode.get() : prefs.oscRightAcMode.get();
      const sinc = isLeft ? prefs.oscLeftSincInterpEnabled.get() : prefs.oscRightSincInterpEnabled.get();
      const residual = isLeft ? prefs.oscLeftResidualEnabled.get() : prefs.oscRightResidualEnabled.get();
      return led(on, chName) + this.titledChips(
        [this.shortSi(vDiv), t('scope.tile.scale', chName, this.shortSi(vDiv) + 'V')],
        [ac ? 'ac' : 'dc', t(ac ? 'scope.tile.coupling.ac' : 'scope.tile.coupling.dc', chName)],
        [sinc ? 'sin' : 'lin', t(sinc ? 'scope.tile.interp.sin' : 'scope.tile.interp.lin', chName)],
        // Residual tile only when the channel's residual view is on (Java scopeTabTiles
        // adds it conditionally as the LAST chip).
        residual ? ['res', t('scope.tile.residual', chName)] : null);
    };
    $tiles.eq(0).html(chTile(true));
    $tiles.eq(1).html(chTile(false));
    const td = prefs.oscTimePerDiv.get();
    $tiles.eq(2).html(this.titledChips(
      [this.shortTimePerDiv(td), t('scope.tile.time', this.shortTimePerDiv(td) + 's')]));
    // Trigger: channel / edge / (G when glitch type) / mode / hysteresis (only when
    // enabled). The "G" tile mirrors Java scopeTabTiles: shown only when the trigger
    // type is GLITCH (EDGE is the default and gets no tile).
    const edge = prefs.oscTriggerEdge.get() === 'FALL' ? '↓' : '↑';
    const modeMap = { AUTO: 'A', NORMAL: 'N', SINGLE: 'S' };
    const mode = modeMap[prefs.oscTriggerMode.get()] || '?';
    const trigCh = t(prefs.oscTriggerChannel.get() === 'L' ? 'scope.tab.left' : 'scope.tab.right');
    const modeKeyMap = { AUTO: 'scope.tile.trigger.mode.auto', NORMAL: 'scope.tile.trigger.mode.normal', SINGLE: 'scope.tile.trigger.mode.single' };
    const modeKey = modeKeyMap[prefs.oscTriggerMode.get()];
    const hystOn = prefs.oscTriggerHysteresisEnabled.get();
    const glitch = prefs.oscTriggerType.get() === 'GLITCH';
    $tiles.eq(3).html(this.titledChips(
      [prefs.oscTriggerChannel.get(), t('scope.tile.trigger.channel', trigCh)],
      [edge, t(prefs.oscTriggerEdge.get() === 'FALL' ? 'scope.tile.trigger.edge.fall' : 'scope.tile.trigger.edge.rise')],
      glitch ? ['G', t('scope.tile.trigger.type.glitch')] : null,
      [mode, modeKey ? t(modeKey) : null],
      hystOn ? ['H ' + prefs.oscTriggerHysteresisDiv.get().toFixed(1), t('scope.trigger.hysteresis.tooltip')] : null));
    // Presets: "N saved" tile only when there are saved presets (Java ScopeTabControl
    // scopeTabTiles TAB_PRESETS - a hint that there's something to load).
    const nPresets = prefs.oscPresets.size;
    $tiles.eq(4).html(nPresets > 0 ? this.titledChips([nPresets + ' saved', t('scope.tile.presets', nPresets)]) : '');
  }

  /** Re-sync the scope V/div + t/div fields after auto-setup mutates the prefs. */
  refreshScopeFields() {
    const prefs = this.prefs;
    const getField = (id) => this._getField(id);
    if (getField('scopeLeftVdiv')) getField('scopeLeftVdiv').setValue(prefs.oscLeftVoltsPerDiv.get());
    if (getField('scopeRightVdiv')) getField('scopeRightVdiv').setValue(prefs.oscRightVoltsPerDiv.get());
    if (getField('scopeTdiv')) getField('scopeTdiv').setValue(prefs.oscTimePerDiv.get());
    this.syncOffsetScrollbar();
  }

  // 8-row × 5-column measurement table (Java ScopeView.drawMeasurementTable). Driven
  // each render frame from scopeView.measurementRows (already formatted strings). Gated
  // on (a channel enabled) && oscShowMeasurementTable; the avg/min/max/σ columns hide
  // when oscShowStats is off. Auto-flips oscMeasurementChannel off a disabled channel.
  renderMeasurementTable() {
    const prefs = this.prefs;
    const scopeView = this.view;
    const $table = $('#scopeMeasTable');
    const anyChannel = prefs.oscLeftChannelEnabled.get() || prefs.oscRightChannelEnabled.get();
    const showTable = anyChannel && prefs.oscShowMeasurementTable.get();
    if (!showTable) { $table.prop('hidden', true); return; }
    // Auto-flip the measurement channel when its source is disabled (Java prepareMeasurementRows).
    const measCh = prefs.oscMeasurementChannel.get();
    if (measCh === 'L' && !prefs.oscLeftChannelEnabled.get() && prefs.oscRightChannelEnabled.get()) {
      prefs.oscMeasurementChannel.set('R'); prefs.save(); scopeView._clearMeasurementHistory();
    } else if (measCh === 'R' && !prefs.oscRightChannelEnabled.get() && prefs.oscLeftChannelEnabled.get()) {
      prefs.oscMeasurementChannel.set('L'); prefs.save(); scopeView._clearMeasurementHistory();
    }
    this.syncMeasChannelButtons();
    const rows = scopeView.measurementRows;
    if (!rows) { $table.prop('hidden', true); return; }
    const showStats = prefs.oscShowStats.get();
    $table.prop('hidden', false);
    $table.find('thead .mt-stat').toggle(showStats);
    // Build the 8×(name+cur+4 stat) row/cell nodes ONCE, then update textContent
    // in place every frame (the table is rendered ~60 fps but its content changes
    // only every READOUT_THROTTLE_MS). Re-emitting innerHTML each frame churned
    // ~48 fresh DOM nodes per paint - detached nodes accumulated under GC pressure.
    this.ensureMeasTableRows(rows.length);
    for (let i = 0; i < rows.length; i++) {
      const r = rows[i], cells = this.measTableCells[i];
      this.setText(cells.name, r.name);
      this.setText(cells.cur, r.cur);
      this.setText(cells.avg, r.avg);
      this.setText(cells.min, r.min);
      this.setText(cells.max, r.max);
      this.setText(cells.sigma, r.sigma);
      cells.statHidden(!showStats);
    }
  }

  /** Sets el.textContent only when it actually changed (avoids needless layout). */
  setText(el, v) { const s = v == null ? '' : String(v); if (el.textContent !== s) el.textContent = s; }

  /** Builds (once) `count` reusable <tr> rows of name+cur+avg/min/max/σ cells into
   *  the measurement table body and caches their cell nodes; no-op once built. The
   *  stat cells carry .mt-stat so the thead toggle CSS still applies; we hide them
   *  per-row via inline display so the avg/min/max/σ columns collapse with the
   *  stats toggle exactly as the old innerHTML branch did. */
  ensureMeasTableRows(count) {
    if (this.measTableCells && this.measTableCells.length === count) return;
    const tbody = document.querySelector('#scopeMeasTable tbody');
    tbody.textContent = '';
    this.measTableCells = [];
    for (let i = 0; i < count; i++) {
      const tr = document.createElement('tr');
      const mk = (cls) => { const td = document.createElement('td'); td.className = cls; tr.appendChild(td); return td; };
      const name = mk('mt-name');
      const cur = mk('mt-num mt-cur');
      const avg = mk('mt-num mt-stat');
      const min = mk('mt-num mt-stat');
      const max = mk('mt-num mt-stat');
      const sigma = mk('mt-num mt-stat');
      const stats = [avg, min, max, sigma];
      this.measTableCells.push({
        name, cur, avg, min, max, sigma,
        statHidden: (hide) => { const d = hide ? 'none' : ''; for (const c of stats) if (c.style.display !== d) c.style.display = d; },
      });
      tbody.appendChild(tr);
    }
  }

  /** Mirrors the L/R measurement-channel pick buttons + the gauge/stats toggle pressed
   *  states onto the prefs, and applies Java's signal-gated visibility cascade
   *  (syncScopeButtons): table-toggle shown when a signal is present; L/R picker, stats,
   *  reset, pop-out shown only when the table is on. This IS the host.syncMeasButtons()
   *  seam (Java ScopeTabControl.Host) - its original app.js name was syncScopeMeasButtons. */
  syncMeasButtons() {
    const prefs = this.prefs;
    // Gate on the presence of a reader (Java syncScopeButtons: reader != null), i.e.
    // live recording OR a loaded file - NOT the volatile per-frame scopeView.latest,
    // which goes null on a frozen / held frame and would mis-hide the buttons.
    const signal = this._isScopeRec() || this.view.fileMode;
    const showTable = prefs.oscShowMeasurementTable.get();
    const showStats = prefs.oscShowStats.get();
    $('#scopeTableToggle').toggle(signal).toggleClass('on', showTable);
    // The histogram toggle carries the SAME signal gate as the gauge (Java syncScopeButtons puts
    // histogramBtn and tableToggleBtn both behind tableVis = signal): with no signal there is no
    // distribution to show, so the button only appears once the scope runs or a file is loaded.
    $('#scopeHistogram').toggle(signal);
    const tableVis = signal && showTable;
    // L/R measurement-channel picker stays visible UNCONDITIONALLY (Java ScopeView:
    // "L/R channel-pick buttons stay visible unconditionally"); only the gauge / pop-out
    // / stats / reset are signal+table gated.
    $('#scopeStatsToggle, #scopeTablePop').toggle(tableVis);
    // Reset stays visible whenever the table is on so the stats toggle never hides /
    // covers it - both the stats-toggle and reset buttons remain reachable together.
    $('#scopeStatsReset').toggle(tableVis);
    $('#scopeStatsToggle').toggleClass('on', showStats);
    $('#scopeTablePop').toggleClass('on', this.measTablePopped);
    // Mirror the window's own stats toggle (Java createMeasurementWindow stats button).
    $('#scopeWinStatsToggle').toggleClass('on', showStats);
    this.syncMeasChannelButtons();
  }
  syncMeasChannelButtons() {
    const ch = this.prefs.oscMeasurementChannel.get();
    $('#scopeMeasL').toggleClass('on', ch === 'L');
    $('#scopeMeasR').toggleClass('on', ch === 'R');
  }

  // ----- the scope branch of the rAF render loop (Java MultifunctionalTab.renderRealtimeFrame
  // scope half) - the MAIN rAF loop stays in app.js and calls this each frame. Gated on the
  // scope's OWN record state (the FFT renders independently); when the record stops the view
  // freezes on its last frame, and an idle (never-captured) scope keeps the empty grid painted.
  render() {
    const engine = this.engine;
    const prefs = this.prefs;
    const scopeView = this.view;
    const latestScope = this._getLatestScope();
    if (this._isScopeRec()) {
      try {
        // Keep the engine's captured scope-window length tracking the live t/div so a
        // zoom-out re-sizes the ~3× window (Java sizes leftBuf from t/div each paint).
        engine.config.scopeTimePerDiv = prefs.oscTimePerDiv.get();
        if (latestScope) {
          // The long-window Vrms/Vmean/Tp/f/Duty measurement now runs in the osc-meas Web
          // Worker off its OWN gapless ring reader (engine.scope, wired in the constructor)
          // - publishing into the view via publishMeasurement on its ~100 ms cadence, off the
          // render thread. So the render loop no longer reads a measurement window here; it
          // just consumes the worker's latest publish (this.view.latest). The synchronous
          // displayed-span / injected-window fallbacks stay inside the view.
          // Generator running? (Java drawBeatOverlays gates the reconstructed-beat overlay
          // on MessageBus.request(GENERATOR_RUNNING) - the view can't reach the bus, so the
          // pane threads the flag in through the render info.)
          latestScope.info.generatorRunning =
            MessageBus.instance().request(Events.GENERATOR_RUNNING) === true;
          scopeView.render(latestScope.buf, latestScope.info);
          // Condensed overview, decimated ~5 Hz (Java CONDENSED_DECIMATION) so its
          // 1 s walk doesn't halve the main trace's cap/s.
          if (this.scopeZoomDecim++ >= SCOPE_ZOOM_DECIMATION) {
            this.scopeZoomDecim = 0;
            // The overview reads its OWN fixed 1 s window straight from the shared
            // ring (Java ZoomedView), so its horizontal scale is constant and does
            // NOT change with the main t/div. Fall back to the scope window only if
            // the ring read isn't available yet.
            const zw = engine.readZoomedWindow();
            if (zw) scopeView.renderZoomed(document.getElementById('scopeZoomed'), zw.bufR, zw);
            else scopeView.renderZoomed(document.getElementById('scopeZoomed'), latestScope.buf, latestScope.info);
          }
          // Keep the vertical scrollbar tracking the offset (a wheel/canvas pan moves
          // the pref without going through the scrollbar) - Java requestRedraw ->
          // syncVertSliderFromPrefs.
          this.syncOffsetScrollbar(); this.scopeVScroll.redraw();
          this.renderMeasurementTable(); this.syncMeasButtons();
          if (this.scopeAutoPending && scopeView.latest) { scopeView.autoSetup(); this.refreshScopeFields(); this.refreshScopeTiles(); this.scopeAutoPending = false; }   // auto-fit once measured (V/div + t/div changed -> re-tile)
          this._syncCalibrateGate();
        }
      } catch (e) { console.error('scope render error', e); }     // isolated: must not kill the FFT render
    } else if (!scopeView.fileMode && scopeView.renderFrozen()) {
      // Stopped on a captured frame (Java ScopeView.freezeBuffer): replay the FROZEN
      // snapshot every frame so the last trace - beat overlay included - stays on
      // screen instead of leaving stale pixels, and so a V/div / offset drag re-scales
      // it live. The zoomed overview has no live ring while stopped -> its idle grid.
      try {
        scopeView.renderZoomedIdle(document.getElementById('scopeZoomed'));
      } catch (e) { console.error('scope frozen render error', e); }
    } else if (!scopeView.fileMode && !latestScope) {
      // Not recording, not showing a loaded file, and nothing ever captured: keep the
      // empty grid + V/time marks + sliders painted so the scope is ALWAYS visible (Java
      // paints the grid at all times). Once recording starts latestScope is set and the
      // live branch takes over; after a stop the last captured frame stays frozen.
      try {
        scopeView.renderIdle();
        scopeView.renderZoomedIdle(document.getElementById('scopeZoomed'));
      } catch (e) { console.error('scope idle render error', e); }
    }
  }

  // ----- scope resize -> redraw (ResizeObserver) -----
  // render()/renderZoomed() resize their DPR-scaled backing store to the canvas's
  // CSS box each paint, so a resize is only corrected when SOMETHING repaints. While
  // recording (scopeRec) the rAF loop repaints every frame, but a FROZEN view (file
  // mode, a stopped/held last frame) has no live loop - so resizing the browser/pane
  // would just stretch the stale bitmap until the next paint. This forces a redraw of
  // the currently-held content on any size change so the trace re-draws proportionally
  // at the new resolution instead of being upscaled.
  redrawScopeOnResize() {
    const engine = this.engine, scopeView = this.view;
    if (scopeView.fileMode && this.loadedScope) { this.renderLoadedScope(); return; }
    // Stopped-with-a-frozen-frame: replay the FROZEN snapshot (Java ScopeView renders
    // the frozen buffer, not the live ring). Feeding the stale live buffer back through
    // the live path would re-run the AUTO free-run against a snapshot whose ring cursor
    // is gone - draw the held frame instead. The zoomed overview has no live ring while
    // stopped, so it drops to its idle grid.
    if (scopeView.renderFrozen()) {
      scopeView.renderZoomedIdle(document.getElementById('scopeZoomed'));
      return;
    }
    // Live: replay the last captured frame through the normal render path (held-frame
    // branches inside render() repaint the frozen trace at the new size).
    const latestScope = this._getLatestScope();
    if (latestScope) {
      scopeView.render(latestScope.buf, latestScope.info);
      const zw = engine.readZoomedWindow();
      if (zw) scopeView.renderZoomed(document.getElementById('scopeZoomed'), zw.bufR, zw);
      else scopeView.renderZoomed(document.getElementById('scopeZoomed'), latestScope.buf, latestScope.info);
    }
  }

  // scopeView.onFileBack: file-mode horizontal navigation from the canvas wheel
  // (Shift+wheel pan, Ctrl+Shift t/div zoom-around-cursor): the scope view computes
  // a target back-offset against the loaded buffer; clamp it to the valid range and
  // re-render through the nav scrollbar (Java ScopePane.stepHorizontalOffset /
  // applyViewState). Wired through the tab-control's host.onFileBack.
  onFileBack(back) {
    if (!this.view.fileMode || !this.loadedScope) return;
    // Keep the back-offset FRACTIONAL end-to-end (Java ScopeNav.fileViewWindow keeps
    // mainOffset a double): a ½-div wheel step is a fractional sample count, and
    // rounding here would quantise the scroll + break the fractional accumulation of
    // repeated steps. The clamp bounds are the buffer edges; the render splits the
    // fraction into dispStart/subSampleOffset for a sub-sample scroll.
    this.scopeFileBack = Math.max(0, Math.min(this.fileMaxBack(), back));
    this.renderLoadedScope();
  }

  /** Start button is active only in SINGLE mode (Java syncTriggerStart). */
  syncTriggerStart() {
    const single = this.prefs.oscTriggerMode.get() === 'SINGLE';
    $('#scopeTrigStart').prop('disabled', !single);
    if (!single && this.view.isSingleArmed()) { this.view.setSingleArmed(false); $('#scopeTrigStart').removeClass('active armed'); }
  }

  // ----- measurement-table header buttons (Java ScopeView header bar) -----
  // (14) gauge -> oscShowMeasurementTable; (15) L/R picker -> oscMeasurementChannel
  // (clearing stats on switch); (16) stats-toggle -> oscShowStats, reset -> clear stats;
  // (17) pop-out -> detach the table into a floating, draggable panel.
  bindMeasButtons() {
    const prefs = this.prefs;
    const scopeView = this.view;
    $('#scopeTableToggle').on('click', () => {
      prefs.oscShowMeasurementTable.set(!prefs.oscShowMeasurementTable.get());
      this.renderMeasurementTable(); this.syncMeasButtons();
    });
    $('#scopeMeasL').on('click', () => {
      if (prefs.oscMeasurementChannel.get() === 'L') return;
      prefs.oscMeasurementChannel.set('L'); scopeView._clearMeasurementHistory();
      this.syncMeasChannelButtons(); this.syncOffsetScrollbar(); this.scopeVScroll.redraw(); this.refreshScopeFileMode();
    });
    $('#scopeMeasR').on('click', () => {
      if (prefs.oscMeasurementChannel.get() === 'R') return;
      prefs.oscMeasurementChannel.set('R'); scopeView._clearMeasurementHistory();
      this.syncMeasChannelButtons(); this.syncOffsetScrollbar(); this.scopeVScroll.redraw(); this.refreshScopeFileMode();
    });
    $('#scopeStatsToggle').on('click', () => {
      prefs.oscShowStats.set(!prefs.oscShowStats.get());
      this.renderMeasurementTable(); this.syncMeasButtons();
    });
    $('#scopeStatsReset').on('click', () => { scopeView._clearMeasurementHistory(); this.renderMeasurementTable(); });

    // Amplitude histogram. The L/R pick sets its channel EXPLICITLY rather than toggling: an L/R
    // pair fires on BOTH buttons - the one going on and the one going off - so a toggle lets the
    // last handler win and the pick always lands on R. This shipped once in Java.
    $('#scopeHistogram').on('click', () => this.setHistogramOpen(!this.prefs.oscShowHistogram.get()));
    $('#scopeHistClose').on('click', () => this.setHistogramOpen(false));
    $('#scopeHistL').on('click', () => { this.prefs.oscHistogramChannel.set('L'); this.renderHistogram(); });
    $('#scopeHistR').on('click', () => { this.prefs.oscHistogramChannel.set('R'); this.renderHistogram(); });
    // Clears ONLY the distribution - not the measurement statistics, which is why the histogram has
    // its own reset and its own channel preference.
    $('#scopeHistReset').on('click', () => {
      this.engine.scope.resetHistograms();
      this.renderHistogram();
    });
    // Re-aggregate on a bar-count change: display resolution only, so the collected micro-bins are
    // re-drawn and never discarded.
    this.prefs.oscHistogramBins.addListener(() => this.renderHistogram());
    this.prefs.oscLeftChannelEnabled.addListener(() => this.syncHistogramButtons());
    this.prefs.oscRightChannelEnabled.addListener(() => this.syncHistogramButtons());
    // Restore the window the pref says was open. Without this the pref stayed true across a reload
    // while the markup came up closed, so the toggle read "open" and its first click only cleared
    // the flag - the window took TWO clicks to come back.
    this.setHistogramOpen(this.prefs.oscShowHistogram.get());
    this.syncMeasButtons();   // apply the signal gate at once: no capture yet => no histogram button

    $('#scopeTablePop').on('click', () => this.setMeasTablePopped(!this.measTablePopped));
    $('#scopeWinClose').on('click', () => this.setMeasTablePopped(false));
    $('#scopeWinStatsToggle').on('click', () => {
      prefs.oscShowStats.set(!prefs.oscShowStats.get());
      this.renderMeasurementTable(); this.syncMeasButtons();
    });
    $('#scopeWinStatsReset').on('click', () => { scopeView._clearMeasurementHistory(); this.renderMeasurementTable(); });
    this.makeMeasWindowDraggable();
    this.makeMeasWindowDraggable('scopeHistWindow');
    // A resize must repaint even while the scope is stopped (no measurement publishes to
    // ride on), else the canvas keeps the pixels it had at the old size.
    const histCanvas = document.getElementById('scopeHist');
    if (histCanvas && typeof ResizeObserver === 'function') {
      new ResizeObserver(() => this.renderHistogram()).observe(histCanvas);
    }
  }

  // Pop the measurement table into a titled floating window (Java createMeasurementWindow /
  // ToolWindow). The window carries a title bar + the stats-toggle + reset-stats button row
  // and floats ABOVE the pane divider; the live #scopeMeasTable is moved into it while popped
  // and moved back to the canvas-wrap when closed.
  setMeasTablePopped(popped) {
    this.measTablePopped = popped;
    const $win = $('#scopeMeasWindow');
    const $table = $('#scopeMeasTable');
    if (popped) {
      $('#scopeMeasWindow .smw-body').append($table);
      $win.addClass('open');
    } else {
      $('#scopePane .canvas-wrap').append($table);   // back to the in-canvas slot
      $win.removeClass('open');
    }
    // The window's stats toggle mirrors the header one (same pref).
    $('#scopeWinStatsToggle').toggleClass('on', this.prefs.oscShowStats.get());
    this.renderMeasurementTable();
    this.syncMeasButtons();
  }

  // ----- amplitude histogram: window lifetime + the window's own controls -----
  // The plot itself is HistogramView (its own class, palette, axes and paint); everything here is
  // lifecycle and controls, which is the split Java settled on after drawing it inline was rejected.

  /** Opens / closes the histogram window and persists the state. */
  setHistogramOpen(open) {
    this.prefs.oscShowHistogram.set(!!open);
    $('#scopeHistWindow').toggleClass('open', !!open);
    $('#scopeHistogram').toggleClass('on', !!open);
    if (open) this.renderHistogram();
  }

  /**
   * Repaints the histogram when its window is open. Called on each measurement publish (~100 ms),
   * which is the cadence the distribution actually changes at - a frame-rate repaint would redraw
   * an unchanged snapshot.
   */
  renderHistogram() {
    if (!this.prefs.oscShowHistogram.get() || !this._histogramView) return;
    const canvas = document.getElementById('scopeHist');
    if (canvas) {
      // Track the resizable body, so a dragged window buys resolution instead of stretched pixels.
      const w = Math.max(1, Math.round(canvas.clientWidth));
      const h = Math.max(1, Math.round(canvas.clientHeight));
      if (canvas.width !== w) canvas.width = w;
      if (canvas.height !== h) canvas.height = h;
    }
    this._histogramView.render();
    this.syncHistogramButtons();
  }

  /**
   * Greys the button of a channel the scope has switched off, and if that was the channel on show,
   * moves to the other one and persists the move - the window must never sit on a dead channel.
   */
  syncHistogramButtons() {
    const prefs = this.prefs;
    const leftOn = prefs.oscLeftChannelEnabled.get(), rightOn = prefs.oscRightChannelEnabled.get();
    let channel = prefs.oscHistogramChannel.get();
    if (channel === 'L' && !leftOn && rightOn) { channel = 'R'; prefs.oscHistogramChannel.set('R'); }
    else if (channel === 'R' && !rightOn && leftOn) { channel = 'L'; prefs.oscHistogramChannel.set('L'); }
    $('#scopeHistL').prop('disabled', !leftOn).toggleClass('on', channel === 'L');
    $('#scopeHistR').prop('disabled', !rightOn).toggleClass('on', channel === 'R');
  }

  // Drag the popped-out window by its title bar (in-page floating panel; browsers can't open
  // a native always-on-top window without a popup). The three handlers live in ui/draggable.js
  // since the JSON config editor became the second floating window to need them; this call is
  // the behaviour this method always had - unclamped, so the window can be put anywhere.
  makeMeasWindowDraggable(id = 'scopeMeasWindow') {
    const win = document.getElementById(id);
    if (!win) return;
    makeDraggable(win, win.querySelector('.smw-titlebar'));
  }

  // Vertical offset FlatScrollbar (right gutter) - slides BOTH channels' vertical
  // offset together by the same delta from the active measurement channel (Java
  // ScopePane.onVertSliderMoved). Thumb at TOP = signal up (low offsetFrac); the
  // selection ∈ [0, NAV_RANGE] maps to offsetFrac ∈ [lo, hi].
  onVertScrollMoved(sel) {
    const prefs = this.prefs;
    const b = this.offsetFracBounds();
    const lo = b.lo, hi = b.hi;
    const maxSel = NAV_RANGE - this.scopeVScroll.getThumb();
    const frac = (maxSel <= 0) ? (lo + hi) / 2 : lo + (sel / maxSel) * (hi - lo);
    const ref = prefs.oscMeasurementChannel.get();
    const prevRef = (ref === 'L') ? prefs.oscLeftOffsetFrac.get() : prefs.oscRightOffsetFrac.get();
    if (prevRef === frac) return;
    const delta = frac - prevRef;
    prefs.oscLeftOffsetFrac.set(prefs.oscLeftOffsetFrac.get() + delta);
    prefs.oscRightOffsetFrac.set(prefs.oscRightOffsetFrac.get() + delta);
  }

  // Horizontal navigation FlatScrollbar (Java ScopePane.onNavSliderMoved) - file
  // mode only: selection ∈ [0, NAV_RANGE] sets how far the loaded window is scrolled
  // back from the latest sample. Rightmost = follow latest. Drives a re-render.
  onHorizScrollMoved(sel) {
    if (!this.view.fileMode || !this.loadedScope) return;
    const frames = this.loadedScope.frames;
    const sampleRate = this.loadedScope.sampleRate;
    const displaySamples = Math.round(this.prefs.oscTimePerDiv.get() * 10 * sampleRate);
    const maxBack = Math.max(0, frames - displaySamples);
    const maxSel = NAV_RANGE - this.scopeHScroll.getThumb();
    // Rightmost selection = follow latest (back = 0); leftmost = oldest (back = maxBack).
    const frac = (maxSel <= 0) ? 1 : sel / maxSel;
    this.scopeFileBack = Math.round((1 - frac) * maxBack);
    this.renderLoadedScope();
  }

  /** Offset-scrollbar bounds derived from the active channel's V/div (Java
   *  ScopeView.offsetFracBounds): half = Vfs / (DIVISIONS_Y · V/div), floored at 0.5,
   *  so [0.5−half, 0.5+half]. Small V/div -> wide scroll range, large V/div -> clamped
   *  to [0,1]. */
  offsetFracBounds() {
    const prefs = this.prefs;
    const leftActive = prefs.oscLeftChannelEnabled.get() || !prefs.oscRightChannelEnabled.get();
    const vDiv = leftActive ? prefs.oscLeftVoltsPerDiv.get() : prefs.oscRightVoltsPerDiv.get();
    // Full-scale pairs with the SAME channel whose V/div is used (Java ScopeView.offsetFracBounds
    // getAdcPeakVolts(measurementReferenceChannel())).
    const fs = prefs.getAdcPeakVolts(leftActive ? 'L' : 'R');
    // Half-range = Vfs/(DIVISIONS_Y·V/div), floored at 0.5 (ScopeFormat.offsetMoveHalfRange):
    // small V/div -> wide scroll range, large V/div -> clamped to [0,1].
    const half = offsetMoveHalfRange(vDiv, fs, DIVISIONS_Y);
    return { lo: 0.5 - half, hi: 0.5 + half };
  }

  /** Re-applies the vertical scrollbar's thumb size + selection from the active
   *  V/div (Java ScopePane.syncVertSliderFromPrefs: thumb = visible offset window /
   *  total span; arrow = 1/5 div, page = 5 div). Small V/div -> small thumb (lots of
   *  scroll room); large V/div -> full-width thumb. */
  syncOffsetScrollbar() {
    const prefs = this.prefs;
    const b = this.offsetFracBounds();
    const lo = b.lo, hi = b.hi, span = hi - lo;
    let thumb;
    if (span <= 0) thumb = NAV_RANGE;
    else thumb = Math.max(MIN_SCROLLBAR_THUMB, Math.min(NAV_RANGE, Math.round(Math.min(1, 1 / span) * NAV_RANGE)));
    this.scopeVScroll.setThumb(thumb);
    const maxSelForSteps = NAV_RANGE - thumb;
    if (span > 0 && maxSelForSteps > 0) {
      const unitsPerOffset = maxSelForSteps / span;
      const arrowStep = Math.max(1, Math.round(unitsPerOffset / (5 * DIVISIONS_Y)));
      const pageStep = Math.max(arrowStep, Math.round(unitsPerOffset * 5 / DIVISIONS_Y));
      this.scopeVScroll.setIncrement(arrowStep);
      this.scopeVScroll.setPageIncrement(pageStep);
    }
    const leftActive = prefs.oscLeftChannelEnabled.get() || !prefs.oscRightChannelEnabled.get();
    const offCh = leftActive ? 'oscLeftOffsetFrac' : 'oscRightOffsetFrac';
    let frac = prefs[offCh].get();
    if (frac < lo) frac = lo;
    if (frac > hi) frac = hi;
    const maxSel = NAV_RANGE - thumb;
    const sel = (span <= 0 || maxSel <= 0) ? 0 : Math.round((frac - lo) / span * maxSel);
    this.scopeVScroll.setSelection(sel);
  }

  /** redrawScrollbars host method (Java ScopePane both sliders): repaint both nav bars
   *  once the flex layout has sized the gutter canvases. */
  redrawScrollbars() { this.scopeVScroll.redraw(); this.scopeHScroll.redraw(); }

  // ----- record LED (per-pane capture toggle) -----
  // Independent per-pane Record (Java: the scope and FFT panes each hold their own
  // SharedCapture reference; the device opens on the first acquire and closes on the
  // last release). The scope LED drives setScopeRecording and lights its OWN LED.
  // `busy` is the shared re-entrancy guard - a second click mid-transition races and
  // can tear down a half-built audio graph (STATUS_BREAKPOINT). Ignore clicks until settled.
  bindRecordLed() {
    const engine = this.engine, scopeView = this.view;
    $('.scope-pane .led-btn').on('click', async () => {
      if (this._isBusy()) return;
      this._setBusy(true);
      const want = !this._isScopeRec();
      if (want) {
        this._readConfig();   // pull live config (rate, calibration, tone). NO auto-setup on record - Java fires auto-setup ONLY from its manual button; auto-firing here reset the user's trigger level + channel offset on every Record.
        // Leaving file mode: re-arm the trigger controls, clear the loaded-file banner,
        // drop the loaded signal and hide the horizontal nav scrollbar (live record has
        // no horizontal scroll-back - the trace follows the latest writePos).
        scopeView.fileMode = false;
        this.loadedScope = null; this.scopeFileBack = 0;
        this.setScopeHScrollVisible(false);
        this.setScopeTriggerControlsEnabled(true);
        $('#scopeLoadedPath').val('').attr('title', '');   // readonly last-loaded-file field
        this.setScopeFileBanner(null);
        // Record (re)start: restart the glitch-mode cap/s collection so the stopped
        // gap isn't folded into the cumulative rate (Java rateSawFrozen restart).
        scopeView.restartGlitchRate();
      }
      try { await engine.scope.setRecording(want); }   // the controller reconciles _scopeOn: false if the device failed to open
      finally {
        // On STOP, freeze the last live frame so the stopped trace stays on screen
        // (Java ScopeView.freezeBuffer) instead of leaving stale pixels - render()
        // below repaints it every frame while stopped. On START the view already
        // dropped the hold (restartGlitchRate). Only after a real stop (engine no
        // longer recording) and not into file mode.
        if (!this._isScopeRec() && !scopeView.fileMode) { scopeView.freeze(); this.redrawScopeOnResize(); }
        this.syncScopeLed(); this._setBusy(false);
      }
    });
  }
  syncScopeLed() { $('.scope-pane .led-btn').toggleClass('rec', this._isScopeRec()); }

  // ----- the scope SETTINGS strip (ScopeTabControl) host seam -----
  // Java ScopeController.redrawViews: repaint a FROZEN (stopped) or file-mode view -
  // the live render loop repaints every frame anyway, so nothing to do there. Required
  // so a setting change (trigger type/edge/channel, V/div, ...) shows on an idle view;
  // in particular a resetTriggerHold followed by this blanks the stale held trace.
  requestRedraw() {
    if (this.view.fileMode) { this.refreshScopeFileMode(); return; }
    if (!this._isScopeRec()) this.redrawScopeOnResize();
  }
  // Java toolbarTabs.refreshTab.
  refreshTiles() { this.refreshScopeTiles(); }
  // re-sync V/T fields after a canvas zoom / auto-setup.
  refreshFields() { this.refreshScopeFields(); }
  // recState() => scopeRec (the live signal gate the tab-control's Calibrate uses).
  recState() { return this._isScopeRec(); }
  // Open-signal and Record share the scope buffer: stop the live capture before
  // swapping the buffer out (Java host.stopCaptureForFileLoad). No-op if not recording.
  async stopCaptureForFileLoad() {
    if (this._isScopeRec()) {
      try { await this.engine.scope.setRecording(false); } catch (e) { /* ignore */ }
      this.syncScopeLed();
      // Java ScopeController.openSignalFile publishes this after a programmatic stop
      // so the pane pops its Record toggle; the web LED is synced inline above, the
      // event is published for any other listener (parity with the Java bus contract).
      MessageBus.instance().publish(Events.SCOPE_RECORDING_STOPPED);
    }
  }

  /** Enables / disables the whole trigger group (Java setSubtreeEnabled(triggerGroup))
   *  - used to lock the trigger controls in file mode (a static signal has no trigger). */
  setScopeTriggerControlsEnabled(on) {
    const prefs = this.prefs;
    // Channel/Edge/Type/Mode are toggle button GROUPS - disable their inner buttons,
    // not the wrapping <div>.
    $('#scopeTrigCh .sq-toggle, #scopeTrigEdge .sq-toggle, #scopeTrigType .sq-toggle, '
      + '#scopeTrigMode .sq-toggle, '
      + '#scopeTrigHyst, #scopeTrigHystEn, #scopeTrigBeat, #scopeTrigStart').prop('disabled', !on);
    // Re-apply the per-control gates the blanket enable clobbered: Start only in
    // Single mode, Reconstructed beat only in dual-tone, the hysteresis selector
    // only when hysteresis is on, and G only outside AUTO mode (Java
    // setTriggerControlsEnabled + the typeGlitch AUTO gate).
    if (on) {
      this.syncTriggerStart();
      this.syncGlitchTypeEnabled();
      $('#scopeTrigBeat').prop('disabled', !isDualTone($('#signalForm').val()));
      const fTH = this._getField('scopeTrigHyst');
      if (fTH) fTH.setDisabled(!prefs.oscTriggerHysteresisEnabled.get());
    }
  }

  /** G stays disabled while the trigger mode is AUTO (Java: glitch in AUTO makes no
   *  sense - free-run repaints at the render rate, so a caught glitch frame would be
   *  overwritten immediately). */
  syncGlitchTypeEnabled() {
    $('#scopeTrigType .sq-toggle[data-value="GLITCH"]')
      .prop('disabled', this.prefs.oscTriggerMode.get() === 'AUTO');
  }
  // ScopeTabControl.Host.setTriggerControlsEnabled(on).
  setTriggerControlsEnabled(on) { this.setScopeTriggerControlsEnabled(on); }

  /** Re-gates the Reconstructed-beat checkbox from the live generator form - enabled
   *  only in dual-tone (Java ScopeTabControl.syncReconstructedBeatEnabled, fired on
   *  GENERATOR_SIGNAL_CHANGED). Driven by the GENERATOR_SIGNAL_CHANGED bus subscriber in
   *  bind() so toggling the form live greys / un-greys the checkbox without a record
   *  restart (previously it only re-gated on record-start / file exit via
   *  setScopeTriggerControlsEnabled). */
  syncReconstructedBeatEnabled() {
    // The whole trigger group is locked in file mode (a static signal has no trigger);
    // don't re-enable the checkbox there.
    if (this.view.fileMode) return;
    $('#scopeTrigBeat').prop('disabled', !isDualTone($('#signalForm').val()));
  }

  // A signal file finished loading (the tab-control decoded the picked file -> float
  // channels) -> centre the view on its start, show the nav slider (file mode) and
  // apply the view state (Java ScopePane.onSignalFileLoaded). The capture stop +
  // the decode itself live in ScopeTabControl; this is the pane-side view state.
  onSignalFileLoaded(decoded) {
    const scopeView = this.view;
    const { left, right, frames, sampleRate, name } = decoded;
    this.setScopeFileBanner(name);   // Java ScopeView.setFilePath -> blinking top-right banner
    // Stash the decoded signal so the horizontal nav scrollbar can re-render a
    // scrolled-back window (Java ScopePane.onSignalFileLoaded -> applyViewState).
    this.loadedScope = { left, right, frames, sampleRate };
    this.setScopeHScrollVisible(true);           // file mode -> show the horizontal scrollbar
    // First render the loaded buffer once so scopeView.latest holds a fresh
    // measurement, THEN auto-fit V/div + t/div + offsets to it and force the
    // redraw the auto-setup needs to take effect (autoSetup mutates prefs but
    // doesn't repaint - file mode has no live render loop). Reset the rolling
    // measurement statistics so the loaded signal starts from a clean history,
    // and position the view at the BEGINNING of the loaded buffer (Java
    // ScopePane.onSignalFileLoaded centres on the start = oldest window =
    // back == fileMaxBack()).
    this.scopeFileBack = this.fileMaxBack();   // position at the BEGINNING (oldest window) FIRST
    this.renderLoadedScope();             // render + measure the DISPLAYED window -> scopeView.latest
    scopeView.autoSetup();           // auto-fit V/div + t/div + offsets to what is actually shown
    this.refreshScopeFields();
    scopeView._clearMeasurementHistory();   // clean rolling stats AFTER the fit
    this.scopeFileBack = this.fileMaxBack();   // autoSetup changed t/div -> window size changed -> re-pin to start
    this.renderLoadedScope();             // repaint with the fitted settings
    this.refreshScopeTiles();
    this.renderMeasurementTable(); this.syncMeasButtons();
  }

  // Shows / hides the self-blinking loaded-file banner over the scope canvas
  // (Java ScopeView.drawFilePath + BlinkBanner): top-right under the cap/s readout,
  // shown only in file mode, blinking #FFFFFF↔#AAAAAA every 500 ms. Pass null/'' to hide.
  setScopeFileBanner(path) {
    const el = document.getElementById('scopeFileBanner');
    if (!el) return;
    if (path) { el.textContent = path; el.title = path; el.hidden = false; }
    else { el.textContent = ''; el.title = ''; el.hidden = true; }
  }

  // Renders the loaded (file-mode) signal at the current horizontal scroll-back
  // offset into both the main and zoomed views, and re-syncs the horizontal nav
  // scrollbar thumb to the visible fraction (Java ScopePane.applyViewState).
  renderLoadedScope() {
    if (!this.loadedScope) return;
    const scopeView = this.view, prefs = this.prefs;
    const { left, right, frames, sampleRate } = this.loadedScope;
    const info = { scopeFps: 0, period: sampleRate / 1000, inRate: sampleRate, snapped: 0,
      peakVolts: prefs.adcFsVoltageRms.get() * Math.SQRT2, bufL: left, bufR: right,
      viewBackOffsetFrames: this.scopeFileBack };
    scopeView.render(left, info);
    scopeView.renderZoomed(document.getElementById('scopeZoomed'), right, info);
    this.syncHorizScrollbar();
  }

  /** Re-renders the loaded signal after a setting change (V/div, t/div, offset,
   *  channel toggle) - file mode has no live render loop, so the change needs an
   *  explicit repaint (Java ScopePane.requestRedraw). No-op outside file mode. */
  refreshScopeFileMode() {
    if (this.view.fileMode && this.loadedScope) { this.scopeFileBack = Math.min(this.scopeFileBack, this.fileMaxBack()); this.renderLoadedScope(); }
  }

  /** Largest valid horizontal back-offset for the loaded signal at the current
   *  t/div (frames − the displayed window). */
  fileMaxBack() {
    if (!this.loadedScope) return 0;
    const displaySamples = Math.round(this.prefs.oscTimePerDiv.get() * 10 * this.loadedScope.sampleRate);
    return Math.max(0, this.loadedScope.frames - displaySamples);
  }

  /** Re-applies the horizontal scrollbar's thumb size (= visible fraction of the
   *  loaded signal) and selection from scopeFileBack (Java ScopePane.applyViewState
   *  navSlider branch). */
  syncHorizScrollbar() {
    if (!this.loadedScope) return;
    const { frames, sampleRate } = this.loadedScope;
    const displaySamples = Math.round(this.prefs.oscTimePerDiv.get() * 10 * sampleRate);
    let thumb;
    if (frames <= 0) thumb = NAV_RANGE;
    else thumb = Math.max(MIN_SCROLLBAR_THUMB, Math.min(NAV_RANGE, Math.round(Math.min(1, displaySamples / frames) * NAV_RANGE)));
    this.scopeHScroll.setThumb(thumb);
    const maxBack = Math.max(0, frames - displaySamples);
    const maxSel = NAV_RANGE - thumb;
    // back = 0 -> rightmost (latest); back = maxBack -> leftmost (oldest).
    const sel = (maxBack <= 0 || maxSel <= 0) ? maxSel : Math.round((1 - this.scopeFileBack / maxBack) * maxSel);
    this.scopeHScroll.setSelection(Math.max(0, Math.min(maxSel, sel)));
  }

  /** Shows / hides the horizontal nav scrollbar gap (Java ScopePane.setNavSliderVisible
   *  - visible only in file mode). */
  setScopeHScrollVisible(visible) {
    $('#scopeHScroll').toggleClass('show', !!visible);
    if (visible) this.scopeHScroll.redraw();
  }

  // ----- scope resize -> redraw (ResizeObserver) -----
  // While actively recording the rAF loop already repaints at the new size; only force
  // the redraw when no live loop is driving it (idempotent if it does). Installed here so
  // the pane owns its own canvases' resize handling.
  installResizeObserver() {
    if (typeof ResizeObserver === 'undefined') return;
    const scopeResizeObs = new ResizeObserver(() => {
      if (!this._isScopeRec()) this.redrawScopeOnResize();
    });
    const scopeEl = document.getElementById('scope');
    const zoomEl = document.getElementById('scopeZoomed');
    if (scopeEl) scopeResizeObs.observe(scopeEl);
    if (zoomEl) scopeResizeObs.observe(zoomEl);
  }

  // Wire the record LED + the measurement-table header buttons + the resize observer
  // (Java ScopePane constructor wiring). The initial trigger-Start gate is the
  // syncTriggerStart init step in app.js, which calls scopePane.syncTriggerStart().
  bind() {
    this.bindRecordLed();
    this.bindMeasButtons();
    this.installResizeObserver();
    // SCOPE-side reactions to a generator signal change (Java ScopeTabControl
    // genChangeListener on GENERATOR_SIGNAL_CHANGED): re-gate the Reconstructed-beat
    // checkbox on every change, and on a real USER_INPUT change drop the rolling
    // measurement statistics so avg/min/max start fresh on the new signal (a sub-Hz
    // FLL trim keeps them). Subscribed in the scope layer - the generator never touches
    // the scope's checkbox.
    MessageBus.instance().subscribe(Events.GENERATOR_SIGNAL_CHANGED, (cause) => {
      this.syncReconstructedBeatEnabled();
      if (cause === GenChangeCause.USER_INPUT) {
        this.view._clearMeasurementHistory();
        this.renderMeasurementTable();
        // A real generator change also invalidates the held trigger anchor AND the
        // persistence afterglow (it shows the OLD signal): the signal transition itself is
        // a discontinuity - the glitch trigger fires on it and NORMAL would hold that
        // transition frame forever, and with a rare glitch trigger the afterglow barely
        // decays. Both are DELAYED so they land after the change has flushed through the
        // DAC -> loopback -> ADC path (Java ScopeTabControl genChangeListener ->
        // timerExec(GEN_CLEAR_DELAY_MS) -> resetTriggerHold + controller.clearPersistence).
        setTimeout(() => {
          this.view.resetTriggerHold();
          this.view.clearPersistence();
          this.requestRedraw();
        }, GEN_CLEAR_DELAY_MS);
      }
    });
    return this;
  }
}
