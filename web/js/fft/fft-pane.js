/*
 * Phonalyser web — the FFT PANE (spectrum canvas · live analyzed-result state · Record LED ·
 * single-tone readout + THD/harmonics table · dual-tone IMD table · the FFT branch of the rAF
 * render loop · the stop-after-N auto-stop un-light path).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of gui/fft/FftPane. Owns the live FFT view wiring (the FftView canvas, the
 * latest analyzed spectrum + its dirty flag, the Record LED, the readout / THD / IMD render)
 * and is the source of truth the FFT SETTINGS strip (FftTabControl) reaches through the narrow
 * host object app.js builds — getResult() / setResult(r) are this pane's methods (Java
 * FftTabControl.Host). render() is the FFT branch of the MAIN rAF loop (which stays in app.js
 * and calls fftPane.render() each frame). Reads engine.fft.recording (the controller owns the
 * recording flag) and reaches the SHELL state it does not own (the shared `busy` re-entrancy
 * guard, the readConfig snapshot) through injected closures. The FftView
 * instance + tileChips are injected too; engine.onResult applies the view correction in app.js
 * before handing the result to this pane's setResult. Like the Java pane, this pane owns the
 * two FlatScrollbars (#fftHScroll frequency-pan / #fftVScroll magnitude-pan) and keeps their
 * thumbs aligned to the persisted pan window (Java FftPane.syncFftPan); the FftView's own canvas
 * wheel/drag navigation still works in parallel and calls back through view.onRangeChanged.
 */
import { FlatScrollbar } from '../widgets/flat-scrollbar.js';
import { MessageBus } from '../bus/message-bus.js';
import { Events } from '../bus/events.js';
import { t } from '../i18n/i18n.js';

// Resolution of the FlatScrollbars (Java FftPane.SCROLL_RANGE) — any large integer; slider
// values map to fractional pan positions. dBFS magnitude floor (Java Constants.MAG_FLOOR_DBFS
// = -300.0, used by FftPane.clampRangesAndSave / syncFftPan / applyMagScrollbar), the lower
// bound of the canonical magnitude range the vertical scrollbar pans over. MUST match the
// FftView wheel-pan floor: a higher pane floor (-200 was used here) let the view pan the
// window below it and then pinned only the BOTTOM on the next clamp, shrinking the span —
// wheel-down turned into a bottom-pinned zoom (#16) and the deep noise floor became
// unreachable (#11).
const SCROLL_RANGE = 1_000_000;
const MAG_FLOOR_DBFS = -300;

export class FftPane {
  /**
   * @param engine the AudioEngine (FFT record lifecycle; the Record LED drives engine.fft.setRecording).
   * @param prefs  Preferences (the dBV offset the harmonic readout columns need).
   * @param deps   {view, tileChips, isFftRec, isBusy, setBusy, readConfig}
   *   - view: the FftView (Java holds `view` as a field) — the spectrum canvas painter.
   *   - tileChips: (...vals) => the `.tile` chip-span HTML (shared with the scope tiles).
   *   - isFftRec: () => engine.fft.recording — the controller owns the flag; the pane only reads it.
   *   - isBusy / setBusy: the shared async re-entrancy guard accessors (Record serializes with it).
   *   - readConfig: () => snapshot the live UI into engine.config before a record start.
   */
  constructor(engine, prefs, { view, tileChips, isFftRec, isBusy, setBusy, readConfig }) {
    this.engine = engine;
    this.prefs = prefs;
    this.view = view;
    this._tileChips = tileChips;
    this._isFftRec = isFftRec;
    this._isBusy = isBusy;
    this._setBusy = setBusy;
    this._readConfig = readConfig;

    // ----- the FFT render state (Java FftPane: the controller's last published result) -----
    // Data callbacks only STORE the latest; render() paints it dirty-checked at ~60 fps so the
    // realtime analyze worker can never saturate the UI (mirrors the desktop render loop).
    this.latestResult = null;
    this.resultDirty = false;
    // THD/IMD float-window extracted flag (Java FftView.tableExtracted) — the in-page floating
    // panel that hosts the distortion table when popped out (#25; Java externalBtn).
    this.tableExtracted = false;

    // ----- frequency / magnitude pan scrollbars (Java FftPane freqScrollbar / magScrollbar) -----
    // Horizontal = frequency pan (log or lin per fftLogFreqAxis); vertical = magnitude pan over
    // the canonical dBFS range [MAG_FLOOR_DBFS, magCeiling()]. Thumb size is proportional to
    // (visible / total). The FftView's wheel/drag pan calls back through onRangeChanged so the
    // thumbs follow a canvas zoom/pan too (Java FFT_RANGE_CHANGED → syncFftPan).
    this.freqScrollbar = new FlatScrollbar(document.getElementById('fftHScroll'),
      { vertical: false, onChange: () => this.applyFreqScrollbar() });
    this.freqScrollbar.setMinimum(0); this.freqScrollbar.setMaximum(SCROLL_RANGE);
    this.freqScrollbar.setThumb(Math.round(SCROLL_RANGE / 4)); this.freqScrollbar.setSelection(0);

    this.magScrollbar = new FlatScrollbar(document.getElementById('fftVScroll'),
      { vertical: true, onChange: () => this.applyMagScrollbar() });
    this.magScrollbar.setMinimum(0); this.magScrollbar.setMaximum(SCROLL_RANGE);
    this.magScrollbar.setThumb(Math.round(SCROLL_RANGE / 4)); this.magScrollbar.setSelection(0);

    // A canvas wheel zoom / pan (or auto-setup / maximize) re-aligns the thumbs.
    this.view.onRangeChanged = () => this.syncFftPan();

    // Debug/e2e hook: nothing else exposes the app's prefs to the page, and the
    // FFT pan/zoom probes must read fftMagTop/Bottom + fftFreqMin/MaxHz live.
    if (typeof window !== 'undefined') window.__fftPane = this;

    // FreqResp measurement lifecycle (Java FftPane freqRespStarted/StoppedListener):
    // the sweep needs the capture device exclusively — stop an in-flight FFT recording
    // and gray the Record LED on STARTED, re-enable it on STOPPED.
    const bus = MessageBus.instance();
    bus.subscribe(Events.FREQRESP_MEASUREMENT_STARTED, () => this.onFreqRespMeasurementStarted());
    bus.subscribe(Events.FREQRESP_MEASUREMENT_STOPPED, () => this.onFreqRespMeasurementStopped());
    // FFT re-sync / overrun warning (Java FftView.onCaptureResync → BlinkBanner): the controller
    // publishes FFT_CAPTURE_RESYNC with an i18n message-key on a signal discontinuity or ring
    // overrun; show the self-blinking banner while recording (cleared on the next fresh result).
    bus.subscribe(Events.FFT_CAPTURE_RESYNC, (key) => this.showWarnBanner(key));
  }

  /** Stops any in-flight FFT recording and grays the Record LED so the user can't kick it
   *  back on mid-sweep (Java FftPane.onFreqRespMeasurementStarted — the Frequency Response
   *  analyzer needs exclusive use of the capture device while it runs). */
  async onFreqRespMeasurementStarted() {
    $('.fft-pane .led-btn').prop('disabled', true);
    // Stop the FFT via the ENGINE unconditionally — NOT gated on the shell record flag.
    // A shell/controller desync (the LED reads off while the controller is still
    // recording) otherwise left the FFT feeding on the sweep — it collected averages of
    // the FreqResp sweep and made the measurement ride its still-open capture. setRecording
    // is a no-op when already off, so this is safe; sync the LED to the
    // engine's real state.
    await this.engine.fft.setRecording(false);
    this.syncFftLed();
  }

  /** Counterpart that re-enables the Record LED once the sweep finishes (or aborts)
   *  (Java FftPane.onFreqRespMeasurementStopped). */
  onFreqRespMeasurementStopped() {
    $('.fft-pane .led-btn').prop('disabled', false);
  }

  // =========================================================================
  // Frequency / magnitude limits + scrollbar sync (Java FftPane)
  // =========================================================================

  /** Bin-size lower bound on the visible freq range, from the latest analysis;
   *  falls back to a 384 kHz / configured-length estimate (Java currentBinSize). */
  currentBinSize() {
    const r = this.latestResult;
    if (r && r.binW > 0) return r.binW;
    const sr = 384000, fftLen = Math.max(8, this.prefs.fftLength.get());
    return sr / fftLen;
  }

  /** Nyquist upper bound on the visible freq range (Java currentNyquist). */
  currentNyquist() {
    const r = this.latestResult;
    if (r && r.binW > 0) return r.amplitudeDbFs.length * r.binW;
    return 192000;
  }

  /** Clamps the persisted freq + mag window to the current hardware limits
   *  (Java FftPane.clampRangesAndSave). */
  clampRanges() {
    const prefs = this.prefs;
    const binSize = this.currentBinSize(), nyq = this.currentNyquist();
    let fMin = prefs.fftFreqMinHz.get(), fMax = prefs.fftFreqMaxHz.get();
    if (fMin < binSize) fMin = binSize;
    if (fMax > nyq) fMax = nyq;
    if (fMax - fMin < binSize) fMax = Math.min(nyq, fMin + binSize);
    prefs.fftFreqMinHz.set(fMin); prefs.fftFreqMaxHz.set(fMax);
    const maxTp = this.view.magCeiling(), minBt = MAG_FLOOR_DBFS;
    let mTop = prefs.fftMagTop.get(), mBot = prefs.fftMagBottom.get();
    if (mTop > maxTp) mTop = maxTp;
    if (mBot < minBt) mBot = minBt;
    if (mTop - mBot < 1) mBot = mTop - 1;
    prefs.fftMagTop.set(mTop); prefs.fftMagBottom.set(mBot);
  }

  /** Re-aligns both scrollbar thumbs + selections to the persisted FFT pan
   *  window (Java FftPane.syncFftPan). Thumb size ∝ (visible / total); selection
   *  positions the visible slice. No-op when the scrollbar canvases are absent. */
  syncFftPan() {
    if (!this.freqScrollbar || !this.magScrollbar) return;
    this.clampRanges();
    const prefs = this.prefs;

    // ---- Frequency scrollbar (log or lin space).
    const binSize = this.currentBinSize(), nyq = this.currentNyquist();
    const fMin = prefs.fftFreqMinHz.get(), fMax = prefs.fftFreqMaxHz.get();
    const logFreq = prefs.fftLogFreqAxis.get();
    let visible, total, scrollPos;
    if (logFreq) {
      const a = Math.log10(Math.max(1, binSize));
      const b = Math.log10(Math.max(a + 1, nyq));
      const lo = Math.log10(Math.max(1, fMin));
      const hi = Math.log10(Math.max(lo + 1e-9, fMax));
      visible = hi - lo; total = b - a; scrollPos = (lo - a) / Math.max(1e-9, total - visible);
    } else {
      visible = fMax - fMin; total = nyq; scrollPos = fMin / Math.max(1e-9, total - visible);
    }
    if (visible >= total) {
      this.freqScrollbar.setThumb(SCROLL_RANGE); this.freqScrollbar.setSelection(0);
    } else {
      const thumb = Math.trunc(Math.max(SCROLL_RANGE / 100, Math.min(SCROLL_RANGE - 1, visible / total * SCROLL_RANGE)));
      const sel = Math.trunc(Math.max(0, Math.min(SCROLL_RANGE - thumb, scrollPos * (SCROLL_RANGE - thumb))));
      this.freqScrollbar.setThumb(thumb); this.freqScrollbar.setSelection(sel);
      this.freqScrollbar.setPageIncrement(Math.max(1, Math.trunc(thumb / 2)));
      this.freqScrollbar.setIncrement(Math.max(1, Math.trunc(thumb / 10)));
    }

    // ---- Magnitude scrollbar (canonical dBFS range — linear for every unit).
    const maxTp = this.view.magCeiling(), minBt = MAG_FLOOR_DBFS;
    const mTop = prefs.fftMagTop.get(), mBot = prefs.fftMagBottom.get();
    const magVis = mTop - mBot, magTot = maxTp - minBt;
    if (magVis >= magTot) {
      this.magScrollbar.setThumb(SCROLL_RANGE); this.magScrollbar.setSelection(0);
    } else {
      const thumb = Math.trunc(Math.max(SCROLL_RANGE / 100, Math.min(SCROLL_RANGE - 1, magVis / magTot * SCROLL_RANGE)));
      const pos = (maxTp - mTop) / Math.max(1e-9, magTot - magVis);   // slider 0 → top, max → bottom
      const sel = Math.trunc(Math.max(0, Math.min(SCROLL_RANGE - thumb, pos * (SCROLL_RANGE - thumb))));
      this.magScrollbar.setThumb(thumb); this.magScrollbar.setSelection(sel);
      this.magScrollbar.setPageIncrement(Math.max(1, Math.trunc(thumb / 2)));
      this.magScrollbar.setIncrement(Math.max(1, Math.trunc(thumb / 10)));
    }
  }

  /** Frequency-scrollbar drag → re-derive [fMin, fMax] from the thumb position
   *  (Java FftPane.applyFreqScrollbar). */
  applyFreqScrollbar() {
    const prefs = this.prefs;
    const logFreq = prefs.fftLogFreqAxis.get();
    const binSize = this.currentBinSize(), nyq = this.currentNyquist();
    const visible = logFreq
      ? Math.log10(Math.max(1, prefs.fftFreqMaxHz.get())) - Math.log10(Math.max(1, prefs.fftFreqMinHz.get()))
      : prefs.fftFreqMaxHz.get() - prefs.fftFreqMinHz.get();
    const total = logFreq ? Math.log10(nyq) - Math.log10(Math.max(1, binSize)) : nyq;
    const sel = this.freqScrollbar.getSelection(), thumb = this.freqScrollbar.getThumb();
    const frac = sel / Math.max(1, SCROLL_RANGE - thumb);
    if (logFreq) {
      const a = Math.log10(Math.max(1, binSize));
      const lo = a + frac * Math.max(0, total - visible);
      prefs.fftFreqMinHz.set(Math.pow(10, lo)); prefs.fftFreqMaxHz.set(Math.pow(10, lo + visible));
    } else {
      const newLow = frac * Math.max(0, total - visible);
      prefs.fftFreqMinHz.set(newLow); prefs.fftFreqMaxHz.set(newLow + visible);
    }
    this.clampRanges(); prefs.save(); this.view.applyPrefs();
  }

  /** Magnitude-scrollbar drag → re-derive [magBottom, magTop] from the thumb
   *  position (Java FftPane.applyMagScrollbar). */
  applyMagScrollbar() {
    const prefs = this.prefs;
    const maxTp = this.view.magCeiling(), minBt = MAG_FLOOR_DBFS;
    const visible = prefs.fftMagTop.get() - prefs.fftMagBottom.get();
    const total = maxTp - minBt;
    const sel = this.magScrollbar.getSelection(), thumb = this.magScrollbar.getThumb();
    const frac = sel / Math.max(1, SCROLL_RANGE - thumb);
    const newTop = maxTp - frac * Math.max(0, total - visible);
    prefs.fftMagTop.set(newTop); prefs.fftMagBottom.set(newTop - visible);
    this.clampRanges(); prefs.save(); this.view.applyPrefs();
  }

  // ----- the FFT-PANE host seam (Java FftTabControl.Host) -----
  // getResult() => the live analyzed spectrum (Save / ADC-calibrate read it);
  // setResult(r) => a loaded .fft spectrum → latestResult + resultDirty so the render loop paints it.
  getResult() { return this.latestResult; }
  setResult(r) {
    this.latestResult = r; this.resultDirty = true; this.syncDataButtons();
    this.clearWarnBanner();   // a fresh result means the re-sync recovered (Java clearBannerIfStale)
    // A loaded .fft (or a stopped-state THD recompute) arrives while NOT recording, when the rAF
    // render() branch — gated on the record state — never repaints. Paint it ONCE here so the
    // loaded / recomputed spectrum actually shows (Java FftPane.displayLoadedResult).
    if (!this._isFftRec()) {
      try { this.view.render(r); this.updateTelemetry(r); this.resultDirty = false; }
      catch (e) { console.error('fft loaded render error', e); }
    }
    // Every NEW result refreshes the floated THD/IMD window — live ticks AND the
    // stopped-state THD recompute path (onThdSettingChanged → setResult), which the
    // recording-gated render() branch would never repaint. Java: the ToolWindow's
    // painter re-runs on every view redraw.
    if (this.tableExtracted) { this.syncExternalShell(); this.paintDistortionFloat(); }
  }

  // ----- average-count label (Java FftView averagesCountLabel) -----
  // Java sets it via I18n.t("fft.avgCount.label", n) — the accumulated frame COUNT only, with NO
  // "/target". The web previously showed "framesDone/avgTarget", which the desktop never does
  // (confirmed against the Java app: it reads "2863 average(s)"). Match Java: count only.
  updateTelemetry(r) {
    $('#avgLbl').text(t('fft.avgCount.label', r.framesDone));
  }

  // ----- the FFT branch of the rAF render loop (Java FftPane.renderRealtimeFrame) — the MAIN
  // rAF loop stays in app.js and calls this each frame. Gated on the FFT's OWN record state (the
  // scope renders independently); when the record stops the view freezes on its last frame.
  render() {
    if (this._isFftRec()) {
      try {
        if (this.resultDirty && this.latestResult) {
          this.view.render(this.latestResult); this.updateTelemetry(this.latestResult);
          // NO range sync on an analysis tick — EVER. Java's FftPane runs syncFftPan (and its
          // clampRangesAndSave) ONLY on FFT_RANGE_CHANGED — published exclusively by user
          // actions: the wheel pan/zoom handlers, autoSetup, maximize and the mag-unit change
          // (FftView.java:587,1084,1125,3041,3077,3107,3142 → FftPane.java:272,283) — plus one
          // startup alignment (FftPane.java:325-332). A result tick never rewrites the range.
          // The earlier "sync on bin-grid change" gate here still fired on the FIRST result of
          // every session / FFT-length change (the grid always "changes" off the idle fallback)
          // and clamped the persisted fftMagTop down to that frame's magCeiling() (=fund+20) —
          // the #12 auto-zoom the user kept seeing (and part of #11's mangled range).
          this.syncDataButtons();   // first result reveals the distortion-toggle + reset + float buttons
          // (The floated THD/IMD window is refreshed in setResult — every new result,
          // live or stopped-state recompute — so no per-frame repaint is needed here.)
          this.resultDirty = false;
        }
      } catch (e) { console.error('fft render error', e); this.resultDirty = false; }
    }
  }

  // Stop-after-N tripped (Java FFT_RECORDING_AUTO_STOPPED → FftPane.disengageRecord):
  // the engine paused feeding; tear down the FFT consumer and un-light the Record LED.
  async onFftAutoStopped() {
    if (!this._isFftRec()) return;
    try { await this.engine.fft.setRecording(false); } finally { this.syncFftLed(); }
  }

  // Loading a static .fft must stop live recording so the loaded trace isn't overwritten by
  // the next live frame (Java FFT_RECORDING_STOP_REQUESTED → the pane owns the Record button
  // and shared capture). Same teardown as the auto-stop subscriber: stop the consumer + LED.
  async onRecordingStopRequested() {
    if (!this._isFftRec()) return;
    try { await this.engine.fft.setRecording(false); } finally { this.syncFftLed(); }
  }

  // ----- distortion-table toggle + reset + FLOAT buttons (Java FftView distortionBtn / resetBtn /
  // externalBtn) -----
  // The Java FftView owns these header widgets; in the web split the buttons live in the FFT
  // pane's HTML toolbar (#fftDistToggle / #fftReset / #fftTablePop) and the pane wires them here
  // because the reset needs the engine (Java FftView.controller.resetStatistics). #fftDistToggle
  // flips the fftDistortionTableVisible pref + repaints (Java distortionBtn →
  // setFftDistortionTableVisible); its pressed state mirrors the pref. #fftReset mirrors Java
  // resetStatistics()+redraw(). #fftTablePop mirrors Java externalBtn (Icon.WINDOW_RESTORE) — it
  // extracts the THD/IMD table into a separate window (Java setTableExtracted → createToolWindow).
  // A detached OS tool window has no browser equivalent, so the web pops it into an in-page
  // floating panel (#fftMeasWindow) and paints the same drawDistortionTable / drawImdTable into
  // its canvas — mirroring the scope measurement-window toggle (ScopePane.setMeasTablePopped).
  bindDataButtons() {
    const prefs = this.prefs;
    // Seed the pressed state from the persisted pref (Java toggleButton initial state).
    $('#fftDistToggle').toggleClass('on', prefs.fftDistortionTableVisible.get());
    $('#fftDistToggle').on('click', () => {
      const on = !prefs.fftDistortionTableVisible.get();
      prefs.fftDistortionTableVisible.set(on); prefs.save();
      $('#fftDistToggle').toggleClass('on', on);
      this.syncDataButtons();          // table-visibility may change which buttons show
      this.syncDistFloat();            // Java syncExternalShell: open requires the table visible
      this.view.applyPrefs();          // repaint the last result with/without the table
    });
    $('#fftReset').on('click', () => {
      this.engine.resetAnalyses();     // Java FftView.controller.resetStatistics()
      this.view.applyPrefs();          // Java redraw()
    });
    // FLOAT toggle (Java externalBtn → setTableExtracted): pop the THD/IMD table into the
    // floating window and back. The header button is #fftTablePop and the window's close is
    // #fftWinClose (index.html); the earlier #fftDistFloat / #fftMeasWinClose ids never existed
    // in the markup, so the float button did nothing (#17). Bind the real ids.
    $('#fftTablePop').on('click', () => this.setTableExtracted(!this.tableExtracted));
    $('#fftWinClose').on('click', () => this.setTableExtracted(false));   // Java ToolWindow close → setTableExtracted(false)
    this.makeMeasWindowDraggable();
  }

  /** Java FftView.syncDataButtons — show the distortion-toggle + reset + float buttons only while
   *  a live/loaded result is present (lastResult != null), hidden otherwise. The float button
   *  follows the same data gate AND the table-visible pref (Java externalShown = table visible). */
  syncDataButtons() {
    const hasData = this.latestResult != null;
    $('#fftDistToggle').toggle(hasData);   // Java setExcluded(!hasData) — drop from layout
    $('#fftReset').toggle(hasData);
    // Java externalBtn.setExcluded(!extVisible) where extVisible = hasData && table visible.
    const extVisible = hasData && this.prefs.fftDistortionTableVisible.get();
    $('#fftTablePop').toggle(extVisible).toggleClass('on', this.tableExtracted);
  }

  // ----- THD/IMD float window (Java FftView externalBtn / setTableExtracted / syncExternalShell /
  // createToolWindow / paintDistortion) -----
  // tableExtracted mirrors Java FftView.tableExtracted; setTableExtracted opens / closes the
  // in-page float window and repaints. The table is canvas-painted (drawDistortionTable /
  // drawImdTable take a 2D context), so the float window owns its own canvas that paintDistortion
  // draws into each frame from the latest result.

  /** Java FftView.setTableExtracted: flip the extracted flag, open/close the window, repaint. */
  setTableExtracted(extracted) {
    if (extracted === this.tableExtracted) return;   // Java: if (extracted == tableExtracted) return;
    this.tableExtracted = extracted;
    // Java FftView.setTableExtracted (:2862-2865) flips the field and calls redraw();
    // the paint gate (FftView.java:1253-1254) then draws the inline THD/IMD table only
    // when "!tableExtracted" — extracting MOVES the table into the tool window, it is
    // never shown twice. Mirror the flag onto the view and repaint the spectrum.
    this.view.tableExtracted = extracted;
    this.syncExternalShell();
    this.syncDataButtons();   // reflect the pressed state on the toggle
    this.paintDistortionFloat();
    this.view.applyPrefs();   // Java redraw() — hide/show the inline table on the graph
  }

  /** Java FftView.syncExternalShell: the window is open only when extracted AND the distortion
   *  table is visible (the painter has nothing to draw otherwise). */
  syncExternalShell() {
    const wantOpen = this.tableExtracted && this.prefs.fftDistortionTableVisible.get();
    const $win = $('#fftMeasWindow');
    if (!$win.length) return;
    // Java externalTitle(): "IMD Measurements" in dual-tone mode, "THD …" otherwise (the
    // locale keeps the acronym literal, so a THD→IMD swap just substitutes the token).
    const r = this.latestResult;
    const imd = !!(r && r.imd);
    let title = t('fft.external.window.title');
    if (imd) title = title.replace('THD', 'IMD');
    $('#fftMeasWindow .fmw-title').text(title);
    $win.toggleClass('open', !!wantOpen);
  }

  /** If the open float window can't open (table hidden), syncExternalShell closes it; a re-show
   *  of the table re-opens it. Mirrors Java syncExternalShell being called from the pref change. */
  syncDistFloat() {
    this.syncExternalShell();
    this.syncDataButtons();
    this.paintDistortionFloat();
  }

  /** Java FftView.paintDistortion (the ToolWindow.ContentPainter): draw the THD or IMD table into
   *  the float window's own canvas, inset by EXT_LEFT_PAD from its top-left (top=0 here — no
   *  button row, those toggles stay in the main view). No-op when the window is closed / absent
   *  or there is no result. */
  paintDistortionFloat() {
    if (!this.tableExtracted) return;
    const cv = document.getElementById('fftMeasWinCanvas');
    const r = this.latestResult;
    if (!cv || !cv.getContext) return;
    const g = cv.getContext('2d');
    // Size the canvas to the TABLE'S NATURAL EXTENT (Java createToolWindow →
    // computeExternalContentSize, FftView.java:2903-2904 — the tool window fits its
    // content exactly, no clipping). The old fixed 360×220 CSS box clipped the right
    // columns (#17). Falls back to the CSS box when there is no result to measure.
    const sz = this.view.distortionContentSize(g);
    if (sz) { cv.style.width = sz.width + 'px'; cv.style.height = sz.height + 'px'; }
    const W = cv.clientWidth || 360, H = cv.clientHeight || 220;
    const dpr = (typeof window !== 'undefined' && window.devicePixelRatio) || 1;
    if (cv.width !== Math.round(W * dpr)) cv.width = Math.round(W * dpr);
    if (cv.height !== Math.round(H * dpr)) cv.height = Math.round(H * dpr);
    if (g.setTransform) g.setTransform(dpr, 0, 0, dpr, 0, 0);
    // Java ToolWindow.java:77-78: canvas.setBackground(background) with background =
    // color(ColorRole.BACKGROUND) = 0xFFFFFF (FftView.createToolWindow :2895,
    // AbstractMeasurementView.java:147) — the tool window is a normal LIGHT window,
    // so fill white, not transparent-over-dark-chrome.
    g.fillStyle = '#ffffff';
    g.fillRect(0, 0, W, H);
    if (r == null) return;   // Java paintDistortion: if (lastResult == null) return;
    const EXT_LEFT_PAD = 4;          // Java FftView.EXT_LEFT_PAD
    const y = 0 + EXT_LEFT_PAD;      // Java: int y = top + EXT_LEFT_PAD; top is 0 (no button row)
    // plain=true: Java draws the tool-window table as plain 0x202020 text on the white
    // canvas (drawOutlinedText's 1-px BACKGROUND shadow is invisible on white —
    // AbstractMeasurementView.java:224-230) — no halo/outline in the float window.
    if (r.imd) {
      // Java: if (tableModeIsImd && lastImd != null) drawImdTable(gc, lastImd, EXT_LEFT_PAD, y);
      this.view.drawImdTable(g, r.imd, r, EXT_LEFT_PAD, y, true);
    } else {
      // Java: MagnitudeUnit unit = getFftMagUnit(); drawDistortionTable(gc, lastResult, unit, EXT_LEFT_PAD, y, true);
      this.view.drawDistortionTable(g, r, this.prefs.fftMagUnit.get(), EXT_LEFT_PAD, y, true);
    }
  }

  /** Drag the float window by its title bar (in-page floating panel; mirrors the scope's
   *  makeMeasWindowDraggable). No-op when the markup isn't present. */
  makeMeasWindowDraggable() {
    const win = document.getElementById('fftMeasWindow');
    if (!win) return;
    const bar = win.querySelector('.fmw-titlebar');
    if (!bar) return;
    let dragging = false, ox = 0, oy = 0;
    bar.addEventListener('mousedown', (e) => {
      if (e.target.closest('button')) return;   // let the title-bar buttons click through
      dragging = true; ox = e.clientX - win.offsetLeft; oy = e.clientY - win.offsetTop; e.preventDefault();
    });
    document.addEventListener('mousemove', (e) => {
      if (!dragging) return;
      win.style.left = (e.clientX - ox) + 'px'; win.style.top = (e.clientY - oy) + 'px';
    });
    document.addEventListener('mouseup', () => { dragging = false; });
  }

  // ----- record LED (per-pane capture toggle) -----
  // Independent per-pane Record (Java: the scope and FFT panes each hold their own
  // SharedCapture reference; the device opens on the first acquire and closes on the
  // last release). The FFT LED drives setFftRecording and lights its OWN LED.
  // `busy` is the shared re-entrancy guard — a second click mid-transition races and
  // can tear down a half-built audio graph (STATUS_BREAKPOINT). Ignore clicks until settled.
  bindRecordLed() {
    const engine = this.engine;
    $('.fft-pane .led-btn').on('click', async () => {
      if (this._isBusy()) return;
      this._setBusy(true);
      const want = !this._isFftRec();
      if (want) this._readConfig();                      // FFT geometry from the live UI
      try { await engine.fft.setRecording(want); }   // the controller reconciles _fftOn: false if the device failed to open
      finally { this.syncFftLed(); this._setBusy(false); }
    });
  }
  syncFftLed() {
    const rec = this._isFftRec();
    if (!rec) this.clearWarnBanner();   // no discontinuity warning while stopped (Java clears on Record stop)
    if (rec) this.clearLoadedBanner();  // live recording replaces a loaded static spectrum (Java clearBannerIfStale)
    $('.fft-pane .led-btn').toggleClass('rec', rec);
    // Predistortion-wizard button — live pane only (Java FftPane:246: created only when
    // liveCapture && genController != null). The web always has a generator controller, so
    // gate on live-capturing: enable only while the FFT is recording.
    $('#predistBtn').prop('disabled', !rec);
  }

  /** Shows the blinking discontinuity / overrun warning (Java FftView.onCaptureResync): only
   *  while recording; the message + hover tip come from the published i18n key. */
  showWarnBanner(key) {
    if (!this._isFftRec() || !key) return;
    const el = document.getElementById('fftWarnBanner');
    if (!el) return;
    el.textContent = t(key);
    el.title = t(key + '.tip');
    el.hidden = false;
  }

  /** Hides the discontinuity / overrun warning banner (Java clearBanner / clearBannerIfStale). */
  clearWarnBanner() {
    const el = document.getElementById('fftWarnBanner');
    if (el) el.hidden = true;
  }

  /** Shows the "Loaded: file" blinking indicator while a static .fft is displayed (Java
   *  FftView.setSourceFilePath). Cleared when live recording resumes (syncFftLed). */
  showLoadedBanner(name) {
    const el = document.getElementById('fftLoadedBanner');
    if (!el || !name) return;
    el.textContent = t('fft.loaded.prefix', name);
    el.hidden = false;
  }

  /** Hides the loaded-spectrum indicator. */
  clearLoadedBanner() {
    const el = document.getElementById('fftLoadedBanner');
    if (el) el.hidden = true;
  }

  // ----- fill-% / averages indicator (Java FftAnalyzerWorker.getNextFrameProgress) -----
  // The cross-tick accumulator FFTs ~1 frame per tick, so the data-collection state the
  // user sees is "how full is the next frame" — a 0→100% sweep per hop. Polled on a
  // ~100 ms timer (decoupled from the rAF spectrum render, which only fires on a fresh
  // result): the #pctLbl shows the live fill %, "—" when overrun, blank when stopped.
  startFillTimer() {
    if (this._fillTimer) return;
    this._fillTimer = setInterval(() => {
      // Java FftView.startFillPercentTimer (FftView.java:644-653): the ~100 ms timer reads
      //   double f = getNextFrameProgress();
      //   int pct = (int) Math.round(f * 100);
      //   if (pct < 0) pct = 0; else if (pct > 100) pct = 100;
      //   String txt = pct + "%";
      // The label is seeded "0%" and NEVER blanks — overrun's negative progress just clamps
      // to 0%, it is not shown as "—". Mirror that exactly (the earlier web "—" was a divergence).
      if (!this._isFftRec()) { $('#pctLbl').text('0%'); return; }
      const f = this.engine.nextFrameProgress();
      let pct = Math.round(f * 100);
      if (pct < 0) pct = 0; else if (pct > 100) pct = 100;
      const txt = pct + '%';
      if ($('#pctLbl').text() !== txt) $('#pctLbl').text(txt);   // Java: setText only on change
    }, 100);
  }

  // Wire the record LED + the fill-% timer (Java FftPane constructor wiring). The FFT
  // view header buttons (#fftAutoSetup / #fftMaximize) stay in app.js next to fftView.
  bind() {
    this.bindRecordLed();
    this.bindDataButtons();
    this.syncDataButtons();   // no result yet → distortion-toggle + reset start hidden
    this.startFillTimer();
    // Startup: align the thumbs to the pan window restored from prefs — otherwise they
    // keep their constructor defaults and a saved zoom shows no scrollbar feedback (Java
    // FftPane constructor asyncExec → syncFftPan). Deferred so the flex layout has sized
    // the scrollbar canvases (their backing store reads clientWidth/clientHeight).
    requestAnimationFrame(() => this.syncFftPan());
    return this;
  }
}
