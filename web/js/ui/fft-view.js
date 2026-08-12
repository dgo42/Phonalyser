/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Log/linear-frequency magnitude spectrum on a Canvas2D - white plot to match
// the desktop FftView. Adds the desktop wheel interactions: plain = magnitude
// pan, Shift = frequency pan, Ctrl = magnitude zoom around cursor Y,
// Ctrl+Shift = frequency zoom around cursor X. The view's freq/mag window is the
// bound preference (fftFreqMinHz/MaxHz, fftMagTop/Bottom, fftLogFreqAxis).

import {
  unitIsLog, formatMagnitudeWithUnit, formatMagTick,
  adaptiveLogLabels, logMajorTicks, logMinorTicks,
  niceLinearMajors, niceLinearMinors, isSubDecade, isDecadeValue, labelStep,
  formatFreqTick, formatFreqDecadeTick, formatFrequencyInteger,
} from './axis-format.js';
// Drag-select rectangular zoom + Ctrl+Z undo (Java AbstractMeasurementView's
// installRectZoom base machinery); this view supplies the log-aware freq / dB
// pixel↔value mappings through the injected callbacks (Java FftView overrides).
import { RectZoom } from './rect-zoom.js';
import { uiFont } from './ui-font.js';
// Shared per-tone lobe lift (data-derived floor + lobe extent + log-domain
// stretch) - the SAME mechanism the .frc de-embed uses (fft-compensation.js
// correctToneLobe). Reused here to lift the manual-fundamental lobe to the user
// value at render time, with the manual/peak ratio as the scale instead of 1/H.
import { ToneLobeLift } from '../dsp/tone-lobe-lift.js';
import { MessageBus } from '../bus/message-bus.js';
import { Events } from '../bus/events.js';

// Plot rect margins (Java MARGIN_LEFT/TOP/BOTTOM, right inset 2).
const MARGIN_LEFT = 68, MARGIN_TOP = 0, MARGIN_BOTTOM = 28, MARGIN_RIGHT = 2;
// Java Constants.MAG_FLOOR_DBFS = -300.0 (pan clamp floor; the top is magCeiling()).
const MAG_FLOOR_DBFS = -300;
// Java drawGrid: SUB_DECADE_TICK_TARGET nice-linear majors when a LOG range < 1 decade,
// and the FFT mag axis is built with AxisSpec.linearNice(...,10,5.0) for the dB units.
const SUB_DECADE_TICK_TARGET = 12;
// One shared lobe lift for the manual-fundamental render-time stretch (mirrors
// fft-compensation.js's `const LOBE`, and Java FftView's static LOBE).
const LOBE = new ToneLobeLift();
const FREQ_NICE_TARGET = 10, MAG_DB_NICE_TARGET = 10, MAG_DB_MINOR_STEP = 5.0;

// THD/IMD table overlay origin + gate constants (Java FftView).
// TABLE_TOP_Y = 5 + BTN_H + 6; the desktop header buttons are not painted on the
// web canvas (they live in the HTML toolbar), so the table starts a few px down.
const TABLE_TOP_Y = 33;                 // 5 + BTN_H(22) + 6 - Java FftView.java:112 with
                                        // AbstractMeasurementView.java:79 BTN_H=22; the web
                                        // .lr toolbar is the same 22px at top:5, so 24 (a
                                        // wrongly assumed BTN_H=13) overlapped the table header.
const EXT_LEFT_PAD = 4;                 // Java FftView.EXT_LEFT_PAD - float-window table inset
const SIGNAL_FLOOR_MARGIN_DB = 10.0;    // Java FftView.SIGNAL_FLOOR_MARGIN_DB
const MAX_THD_PCT = 20.0;               // Java FftView.MAX_THD_PCT
const IMD_MAX_ORDER = 5;                // Java ImdResult.MAX_ORDER
// Labels for the distortion table's level unit, in Java FftView.DISTORTION_UNIT_LABELS
// order (DBV, DBFS, DBR). Unit symbols are the same in every language, so they are
// literals rather than i18n keys; DBR - the level minus the reference the table's
// percentages are computed against - shows as the plain "dB".
const DISTORTION_UNIT_LABELS = ['dBV', 'dBFS', 'dB'];
// Floor (V_rms) for the IMD reference level, mirroring the divisor floor in
// imd-analyzer: two muted tones must not divide by zero (Java FftView.MIN_REF_VRMS).
const MIN_REF_VRMS = 1e-12;
// Java FftResult.LOCAL_FLOOR_FLANK_BINS - near-range flank width for the local floor.
const LOCAL_FLOOR_FLANK_BINS = 64;

const clamp01 = (x) => Math.max(0, Math.min(1, x));

/** Java AWT-int colour (0xRRGGBB) -> CSS hex. */
const colorHex = (c) => '#' + (c & 0xffffff).toString(16).padStart(6, '0');

export class FftView {
  constructor(canvas, { prefs = null, topDb = 0, botDb = -200, genActive = () => false, correction = null } = {}) {
    this.cv = canvas; this.g = canvas.getContext('2d');
    this.prefs = prefs;
    this.topDb = topDb; this.botDb = botDb;
    // Java FftView.correctionStore - the loaded .frc de-embed cascade (FftViewCorrection), injected
    // so the IMD path can draw the blue "before-cal" dots at dbFs + sumCalDbAt (drawImdDots:1952-1962).
    // Null in tests that construct FftView without it (the blue IMD pass is then skipped).
    this.correction = correction;
    // Java FftView.isGeneratorActive() - true while the generator is producing a
    // signal. Gates the clock-drift (ΔF / Δf1 / Δf2) rows of the distortion tables.
    this._genActive = genActive;
    // Java FftView publishes FFT_RANGE_CHANGED after a wheel pan / zoom so the pane
    // re-aligns its scrollbar thumbs. The web pane (which owns the scrollbars) sets
    // this hook in its constructor; null when no pane is wired (e.g. tests).
    this.onRangeChanged = null;
    this._last = null;                 // last rendered result, for repaint on wheel
    // Java FftView.tableExtracted (:358) - true while the THD/IMD table is popped out into
    // the tool window. The paint gate (FftView.java:1253-1254) draws the inline table only
    // when "!tableExtracted", so the table is never shown twice. The pane mirrors its own
    // extracted flag here (setTableExtracted -> redraw()).
    this.tableExtracted = false;
    this._nyquist = 192000; this._binSize = 1;
    this._crossX = -1; this._crossY = -1;   // cursor crosshair position (canvas px); -1 = outside
    if (canvas) {
      canvas.addEventListener('wheel', (e) => this._onWheel(e), { passive: false });
      canvas.addEventListener('mousemove', (e) => this._onMove(e));
      canvas.addEventListener('mouseleave', () => { this._crossX = this._crossY = -1; this.applyPrefs(); });
      canvas.style.cursor = 'crosshair';
      // Drag-select zoom + Ctrl+Z undo (Java FftView: installRectZoom(this, true) -
      // hookMouse, so the machinery wires the drag to the canvas's own mouse events).
      this._rectZoom = new RectZoom(canvas, {
        captureState: () => this._captureZoomState(),
        applyState: (s) => this._applyZoomState(s),
        stateForRect: (sel) => this._zoomStateForRect(sel),
        zoomableArea: () => this._zoomableArea(),
        repaintOverlay: () => this.applyPrefs(),
      });
      // Immediate repaint on resize (Java FftView: the SWT FormLayout resizes the canvas and
      // fires a paint event). A ResizeObserver re-sizes the backing store to the new client
      // rect and redraws the last result NOW rather than waiting for the next analysis frame.
      if (typeof ResizeObserver !== 'undefined') {
        this._resizeObs = new ResizeObserver(() => this.applyPrefs());
        this._resizeObs.observe(canvas);
      }
      // Paint the empty graticule up front (Java FftView paints the frame on its first
      // paint event, before any analysis result) so the plot isn't blank until frame 1.
      this.applyPrefs();
    }
  }

  /** Track the cursor for the crosshair/readout overlay (Java onMouseMove - always records
   *  the position and repaints, even before the first result, so the crosshair tracks over
   *  the empty graticule too). */
  _onMove(e) {
    const rect = this.cv.getBoundingClientRect();
    this._crossX = e.clientX - rect.left; this._crossY = e.clientY - rect.top;
    this.applyPrefs();
  }

  /** Re-render after a pref/axis change. Java FftView.onPaint always repaints the
   *  frame (grid + axes), drawing the trace only when a result exists - so this
   *  repaints with the last result, or the empty graticule when there is none. */
  applyPrefs() { this.render(this._last); }

  /** The manual-fundamental dBFS override for {@code r}, mirroring Java's
   *  FftAnalyzer: r.fundamentalTrueDbFs is finite ONLY when manual-fundamental
   *  mode is enabled (the analyzer sets it from fundRefDbFs, which is NaN when
   *  the toggle is off - FftController._fundRefDbFs). The web's static
   *  recomputeStats path does NOT clear a previously-set fundamentalTrueDbFs when
   *  the user switches manual OFF, so the stale value would otherwise pin the red
   *  fundamental dot / dBV column / ceiling at the old manual frequency.
   *  Re-gate on the CURRENT fftManualFundEnabled pref so it tracks the live
   *  on/off state: returns the override when enabled+finite, else NaN (auto). */
  _manualFundDbFs(r) {
    if (!r) return NaN;
    if (this.prefs && this.prefs.fftManualFundEnabled && !this.prefs.fftManualFundEnabled.get()) return NaN;
    return Number.isFinite(r.fundamentalTrueDbFs) ? r.fundamentalTrueDbFs : NaN;
  }

  /** The DISPLAYED fundamental level in dBFS - the manual override when set, else the
   *  measured level (Java FftView.displayedFundDbFs). NaN when there is no fundamental yet.
   *  This is the same reference the THD readouts and the lobe stretch use, which is why the
   *  on-screen peak lands exactly on 0 dBr with no extra work. */
  _displayedFundDbFs(r) {
    if (!r) return NaN;
    const man = this._manualFundDbFs(r);
    return Number.isFinite(man) ? man : r.fundamentalDbFs;
  }

  /** Java FftView.magCeiling: the 0 dBFS full-scale max, raised to the DISPLAYED
   *  fundamental + 20 dB when a signal is present (so a .frc lift above full scale
   *  stays reachable). No result yet -> max(0, persisted mag top) so the pane's clamp
   *  can't shrink a saved > 0 dB zoom before the first result re-raises the ceiling. */
  magCeiling() {
    const r = this._last;
    if (r) {
      const man = this._manualFundDbFs(r);
      const fund = Number.isFinite(man) ? man : r.fundamentalDbFs;
      if (Number.isFinite(fund)) return Math.max(0, fund + 20);
    }
    return Math.max(0, this.prefs ? this.prefs.fftMagTop.get() : 0);
  }

  /** Java FftView.getLastVrms: latest fundamental Vrms (linear × ADC fs voltage), or
   *  null when no analysis - the ADC-calibrate dialog seeds + scales from it. */
  getLastVrms() {
    const r = this._last;
    if (!r || !Number.isFinite(r.fundamentalLinear)) return null;
    // The ANALYZED channel's own ADC full-scale (Java FftView:1168 getAdcFsVoltageRms(getFftChannel())).
    const fs = this.prefs ? this.prefs.getAdcFsVoltageRms(this.prefs.fftChannel.get()) : 0;
    return fs > 0 ? r.fundamentalLinear * fs : null;
  }

  /** Java FftView.autoSetup: one decade either side of the fundamental (or the full
   *  IMD dot family in dual-tone), magnitude from peak+20 dB down to floor−20 dB.
   *  Range is stored canonically in dBFS (unit conversion is draw-time only). */
  autoSetup() {
    const r = this._last, p = this.prefs;
    if (!r || !p) return;
    const binSize = r.binW > 0 ? r.binW : 1;
    const nyq = r.amplitudeDbFs.length * r.binW;
    let lo, hi;
    const imd = r.imd;
    if (imd) {
      let minHz = Math.min(imd.f1Hz, imd.f2Hz), maxHz = Math.max(imd.f1Hz, imd.f2Hz);
      const diff = Math.abs(imd.f2Hz - imd.f1Hz);
      if (diff > 0) minHz = Math.min(minHz, diff);
      const dnL = imd.dnLHz || [], dnH = imd.dnHHz || [];
      for (let k = 2; k < Math.max(dnL.length, dnH.length); k++) {
        const dl = dnL[k], dh = dnH[k];
        if (Number.isFinite(dl) && dl > 0) { minHz = Math.min(minHz, dl); maxHz = Math.max(maxHz, dl); }
        if (Number.isFinite(dh) && dh > 0) { minHz = Math.min(minHz, dh); maxHz = Math.max(maxHz, dh); }
      }
      lo = Math.max(binSize, Math.pow(10, Math.log10(minHz) - 0.1));
      hi = Math.min(nyq, Math.pow(10, Math.log10(maxHz) + 0.1));
    } else {
      const f0 = r.fundamentalHzRefined;
      if (!(Number.isFinite(f0) && f0 > 0)) { lo = 0; hi = 0; }
      else { lo = Math.max(binSize, f0 / 10); hi = Math.min(nyq, f0 * 10); }
    }
    if (hi > lo) { p.fftFreqMinHz.set(lo); p.fftFreqMaxHz.set(hi); }

    if (Number.isFinite(r.fundamentalDbFs) && Number.isFinite(r.avgNoiseFloorDbFs)) {
      let peak = r.fundamentalDbFs;
      if (imd) {
        if (Number.isFinite(imd.f1DbFs)) peak = Math.max(peak, imd.f1DbFs);
        if (Number.isFinite(imd.f2DbFs)) peak = Math.max(peak, imd.f2DbFs);
      }
      let top = peak + 20, bot = r.avgNoiseFloorDbFs - 20;
      if (top !== bot) {
        if (top < bot) { const s = top; top = bot; bot = s; }
        p.fftMagTop.set(top); p.fftMagBottom.set(bot);
      }
    }
    this.applyPrefs();
    if (this.onRangeChanged) this.onRangeChanged();
  }

  /** Java FftView.maximize: full band [binSize, Nyquist]; ceiling +20 dBFS (or
   *  fundamental+20 when the tone sits above 0), floor = noiseFloor−30 dB. */
  maximize() {
    const r = this._last, p = this.prefs;
    if (!p) return;
    const nyq = r ? r.amplitudeDbFs.length * r.binW : this._nyquist;
    const binSize = (r && r.binW > 0) ? r.binW : this._binSize;
    const lo = Math.max(binSize, 0);
    if (nyq > lo) { p.fftFreqMinHz.set(lo); p.fftFreqMaxHz.set(nyq); }
    const fund = r ? r.fundamentalDbFs : NaN;
    let top = Number.isFinite(fund) ? Math.max(20, fund + 20) : 20;
    let bot = (r && Number.isFinite(r.avgNoiseFloorDbFs)) ? r.avgNoiseFloorDbFs - 30 : -150;
    if (top !== bot) {
      if (top < bot) { const s = top; top = bot; bot = s; }
      p.fftMagTop.set(top); p.fftMagBottom.set(bot);
    }
    this.applyPrefs();
    if (this.onRangeChanged) this.onRangeChanged();
  }

  _onWheel(e) {
    // Java FftView.onMouseWheel gates only on e.count==0 - pan/zoom work before the
    // first result (currentBinSize/currentNyquist fall back). Require only prefs.
    if (!this.prefs) return;
    e.preventDefault();
    const dir = e.deltaY < 0 ? 1 : -1;     // wheel up = +1 (DOM deltaY is +down)
    const rect = this.cv.getBoundingClientRect();
    const mx = e.clientX - rect.left, my = e.clientY - rect.top;
    const W = this.cv.clientWidth || 1200, H = this.cv.clientHeight || 440;
    const plotX = MARGIN_LEFT, plotW = (W - MARGIN_RIGHT) - MARGIN_LEFT;
    const plotY = MARGIN_TOP, plotH = (H - MARGIN_BOTTOM) - MARGIN_TOP;
    if (e.ctrlKey && e.shiftKey) this._zoomFreqAround(dir, clamp01((mx - plotX) / plotW));
    else if (e.ctrlKey) this._zoomMagAround(dir, clamp01((my - plotY) / plotH));
    else if (e.shiftKey) this._panFreq(dir);
    else this._panMag(dir);
    this.applyPrefs();
    // Java FftView publishes FFT_RANGE_CHANGED -> FftPane.syncFftPan re-aligns the
    // scrollbar thumbs to the new pan window.
    if (this.onRangeChanged) this.onRangeChanged();
  }

  _zoomMagAround(dir, frac) {
    const p = this.prefs;
    const top = p.fftMagTop.get(), bot = p.fftMagBottom.get();
    if (top - bot <= 0) return;   // Java zoomMagnitudeAroundCursor (:3032)
    const scale = dir > 0 ? 0.8 : 1.25;
    const anchor = top - frac * (top - bot);
    const newSpan = (top - bot) * scale;
    p.fftMagTop.set(anchor + frac * newSpan);
    p.fftMagBottom.set(anchor - (1 - frac) * newSpan);
  }

  _zoomFreqAround(dir, frac) {
    const p = this.prefs;
    const fMin = p.fftFreqMinHz.get(), fMax = p.fftFreqMaxHz.get();
    if (fMax - fMin <= 0) return;   // Java zoomFrequencyAroundCursor (:3051)
    const scale = dir > 0 ? 0.8 : 1.25;
    if (p.fftLogFreqAxis.get()) {
      const lo = Math.log10(Math.max(1, fMin)), hi = Math.log10(Math.max(lo + 1, fMax));
      const span = hi - lo, anchor = lo + frac * span, newSpan = span * scale;
      p.fftFreqMinHz.set(Math.pow(10, anchor - frac * newSpan));
      p.fftFreqMaxHz.set(Math.pow(10, anchor + (1 - frac) * newSpan));
    } else {
      const span = fMax - fMin, anchor = fMin + frac * span, newSpan = span * scale;
      let newMin = anchor - frac * newSpan, newMax = anchor + (1 - frac) * newSpan;
      if (newMin < 0) { newMax -= newMin; newMin = 0; }
      p.fftFreqMinHz.set(newMin); p.fftFreqMaxHz.set(newMax);
    }
  }

  // Java FftView.panMagnitude (:3093): clip the step at the dBFS ceiling
  // (magCeiling(), ≥ 0, raised by a lifted signal) and the dBFS floor
  // (Constants.MAG_FLOOR_DBFS = -300) so neither bound overruns its limit.
  _panMag(dir) {
    const p = this.prefs;
    const top = p.fftMagTop.get(), bot = p.fftMagBottom.get();
    const maxTp = this.magCeiling();
    const minBt = MAG_FLOOR_DBFS;
    let step = (top - bot) * 0.1 * dir;
    if (step > 0 && top + step > maxTp) step = maxTp - top;
    else if (step < 0 && bot + step < minBt) step = minBt - bot;
    p.fftMagTop.set(top + step); p.fftMagBottom.set(bot + step);
  }

  _panFreq(dir) {
    const p = this.prefs;
    const fMin = p.fftFreqMinHz.get(), fMax = p.fftFreqMaxHz.get();
    const nyq = this._nyquist, binSize = this._binSize;
    if (p.fftLogFreqAxis.get()) {
      const lo = Math.log10(Math.max(1, fMin)), hi = Math.log10(Math.max(lo + 1, fMax));
      let step = (hi - lo) * 0.1 * dir;
      const loMin = Math.log10(Math.max(1, binSize)), hiMax = Math.log10(nyq);
      if (step > 0 && hi + step > hiMax) step = hiMax - hi;
      if (step < 0 && lo + step < loMin) step = loMin - lo;
      p.fftFreqMinHz.set(Math.pow(10, lo + step)); p.fftFreqMaxHz.set(Math.pow(10, hi + step));
    } else {
      let step = (fMax - fMin) * 0.1 * dir;
      if (step > 0 && fMax + step > nyq) step = nyq - fMax;
      if (step < 0 && fMin + step < binSize) step = binSize - fMin;
      p.fftFreqMinHz.set(fMin + step); p.fftFreqMaxHz.set(fMax + step);
    }
  }

  // ───────────── Rectangular zoom (base machinery in rect-zoom.js) ─────────────
  // Faithful port of the Java FftView zoom overrides (commit 73f6c1f).

  /** Selections live inside the plot area, between the axis-label margins
   *  (Java FftView.zoomableArea). */
  _zoomableArea() {
    const W = this.cv.clientWidth || 1200, H = this.cv.clientHeight || 440;
    return { x: MARGIN_LEFT, y: MARGIN_TOP,
      w: W - MARGIN_LEFT - 1, h: H - MARGIN_TOP - MARGIN_BOTTOM };
  }

  /** X = displayed frequency window (Hz), Y = the magnitude window in CANONICAL
   *  dBFS - the unit the range prefs store for every display unit (Java
   *  FftView.captureZoomState). */
  _captureZoomState() {
    const p = this.prefs;
    if (!p) return null;
    return { xMin: p.fftFreqMinHz.get(), xMax: p.fftFreqMaxHz.get(),
      yMin: [p.fftMagBottom.get()], yMax: [p.fftMagTop.get()] };
  }

  /** Applies through the canonical range-change protocol; the pane's
   *  onRangeChanged (syncFftPan) clamps to [binSize, Nyquist] × [floor, ceiling]
   *  and realigns the scrollbars (Java FftView.applyZoomState). */
  _applyZoomState(s) {
    const p = this.prefs;
    if (!p) return false;
    p.fftFreqMinHz.set(s.xMin);
    p.fftFreqMaxHz.set(s.xMax);
    p.fftMagBottom.set(s.yMin[0]);
    p.fftMagTop.set(s.yMax[0]);
    if (p.save) p.save();
    this.applyPrefs();
    if (this.onRangeChanged) this.onRangeChanged();
    return true;
  }

  /** Log-aware on X (the axis flag decides); Y maps the pixel fraction linearly
   *  onto the dBFS prefs - the wheel-zoom convention, correct for every display
   *  unit since linear-in-dB equals log-in-V (Java FftView.zoomStateForRect -
   *  mirrors the paint's axis preparation exactly: floor fMin at 0, stretch
   *  sub-1-Hz spans to 1 Hz, then the log floor). */
  _zoomStateForRect(sel) {
    const p = this.prefs;
    if (!p) return null;
    const plot = this._zoomableArea();
    if (!plot || plot.w <= 0 || plot.h <= 0) return null;
    const logFreq = p.fftLogFreqAxis.get();
    let fMin = Math.max(0, p.fftFreqMinHz.get());
    const fMax = Math.max(fMin + 1, p.fftFreqMaxHz.get());
    if (logFreq && fMin < 1) fMin = 1;      // the paint floors log fMin at 1 Hz
    const top = p.fftMagTop.get();
    const span = top - p.fftMagBottom.get();
    const newTop = top - (sel.y - plot.y) / plot.h * span;
    const newBot = top - (sel.y + sel.h - plot.y) / plot.h * span;
    return {
      xMin: this._xToFreq(sel.x, plot, fMin, fMax, logFreq),
      xMax: this._xToFreq(sel.x + sel.w, plot, fMin, fMax, logFreq),
      yMin: [newBot], yMax: [newTop],
    };
  }

  /* Java AbstractFreqDomainView.xToFreq (:218) - exact inverse of _freqToX:
   * LOG: safeMin = max(1, fMin), safeMax = max(fMax, safeMin·1.0000001),
   * f = 10^(lo + t·(hi−lo)); LINEAR: f = fMin + t·(fMax−fMin). */
  _xToFreq(xPx, plot, fMin, fMax, logFreq) {
    const t = (xPx - plot.x) / plot.w;
    if (logFreq) {
      const safeMin = Math.max(1, fMin);
      const safeMax = Math.max(fMax, safeMin * 1.0000001);
      const lo = Math.log10(safeMin), hi = Math.log10(safeMax);
      return Math.pow(10, lo + t * (hi - lo));
    }
    return fMin + t * (fMax - fMin);
  }

  /** Java FftView.magUnitLabel - the axis unit caption (i18n unit.mag.*). */
  _magAxisLabel(unit) {
    return unit === 'V' ? 'V' : unit === 'V_SQRT_HZ' ? 'V/√Hz'
      : unit === 'DBV' ? 'dBV' : unit === 'DBR' ? 'dBr' : 'dBFS';
  }

  /* Java AbstractFreqDomainView.freqToX (:183) - LOG: safeMin=max(1,freqMin),
   * safeMax=max(freqMax, safeMin*1.0000001), t=(log10(max(1,f))-lo)/(hi-lo);
   * LINEAR: t=(f-freqMin)/(freqMax-freqMin); both -> plot.x + round(t*plot.width). */
  _freqToX(f, plot, fMin, fMax, logFreq) {
    let t;
    if (logFreq) {
      const safeMin = Math.max(1, fMin);
      const safeMax = Math.max(fMax, safeMin * 1.0000001);
      const lo = Math.log10(safeMin), hi = Math.log10(safeMax);
      t = (Math.log10(Math.max(1, f)) - lo) / (hi - lo);
    } else {
      t = (f - fMin) / (fMax - fMin);
    }
    return plot.x + Math.round(t * plot.width);
  }

  /* Java AbstractFreqDomainView.magToYFraction (:273) - log units (V/V√Hz) map
   * on a log magnitude axis; dB units stay linear in dB. top->0, bot->1. */
  _magToYFraction(v, top, bot, unit) {
    if (unitIsLog(unit)) {
      const vL = (v <= 0) ? -Infinity : Math.log10(v);
      const topL = (top <= 0) ? -30 : Math.log10(top);
      const botL = (bot <= 0) ? -30 : Math.log10(bot);
      if (topL <= botL) return 0;
      return (topL - vL) / (topL - botL);
    }
    return (top - v) / (top - bot);
  }

  /* Java magToY (:287) - clamped to the plot. */
  _magToY(v, plot, top, bot, unit) {
    let t = this._magToYFraction(v, top, bot, unit);
    if (t < 0) t = 0; if (t > 1) t = 1;
    return plot.y + Math.round(t * plot.height);
  }

  /* Java magToYTrace (:300) - NO clamp (the GC clip cuts an over-range flank flush). */
  _magToYTrace(v, plot, top, bot, unit) {
    const t = this._magToYFraction(v, top, bot, unit);
    return plot.y + Math.round(t * plot.height);
  }

  /* Java yToMag (:308) - inverse of magToY for the crosshair readout. */
  _yToMag(yPx, plot, top, bot, unit) {
    let t = (plot.height <= 0) ? 0 : (yPx - plot.y) / plot.height;
    if (t < 0) t = 0; if (t > 1) t = 1;
    if (unitIsLog(unit)) {
      const topL = (top <= 0) ? -30 : Math.log10(top);
      const botL = (bot <= 0) ? -30 : Math.log10(bot);
      return Math.pow(10, topL - t * (topL - botL));
    }
    return top - t * (top - bot);
  }

  /** @param result the FftResult-shaped object emitted by AudioEngine.onResult, or
   *  {@code null}/undefined to paint just the empty graticule (background, dBV
   *  y-axis, log-frequency x-axis, grid + frame). Java FftView.onPaint always paints
   *  the frame and draws the spectrum trace / dots / table only when a result exists. */
  render(result) {
    this._last = result || null;
    // dBr reference = the DISPLAYED fundamental level (manual override when set, else the
    // measured level); re-stamped every paint so the DBR axis + cursor readouts pin the
    // fundamental to 0 dBr. Non-finite (no fundamental yet) leaves the previous reference
    // intact (Java FftView.onPaint -> prefs.setFftDbrRefDbFs).
    if (this.prefs) {
      const dbrRef = this._displayedFundDbFs(result);
      if (Number.isFinite(dbrRef)) this.prefs.fftDbrRefDbFs = dbrRef;
    }
    const mag = result ? result.amplitudeDbFs : null;
    const binW = result ? result.binW : this._binSize;
    const p = this.prefs;
    // Live Nyquist / bin size for the pan clipping (kept from the last result when empty).
    if (mag) { this._nyquist = mag.length * binW; this._binSize = binW > 0 ? binW : 1; }
    const g = this.g;
    // ---- HiDPI backing store: backing px = CSS px × devicePixelRatio,
    // then a setTransform(dpr,...) so 1 CSS px == dpr device px and the trace /
    // grid stroke crisp at native resolution instead of being drawn small then
    // CSS-scaled. All drawing below is in CSS-px (W, H) coordinates.
    const W = this.cv.clientWidth || 1200, H = this.cv.clientHeight || 440;
    const dpr = (typeof window !== 'undefined' && window.devicePixelRatio) || 1;
    const bw = Math.round(W * dpr), bh = Math.round(H * dpr);
    if (this.cv.width !== bw) this.cv.width = bw;
    if (this.cv.height !== bh) this.cv.height = bh;
    // setTransform is absent on the headless test's mock 2D context; the real
    // CanvasRenderingContext2D always has it (maps 1 CSS px -> dpr device px).
    if (g.setTransform) g.setTransform(dpr, 0, 0, dpr, 0, 0);
    const margin = MARGIN_LEFT;
    const logAxis = p ? p.fftLogFreqAxis.get() : true;
    // Java FftView.onPaint (:1192-1200): freqMin=max(0,fftFreqMinHz),
    // freqMax=max(freqMin+1,fftFreqMaxHz); on log freq a sub-1-Hz min lifts to 1.
    let fMin = p ? Math.max(0, p.fftFreqMinHz.get()) : 10;
    const fMax = p ? Math.max(fMin + 1, p.fftFreqMaxHz.get()) : this._nyquist;
    if (logAxis && fMin < 1) fMin = 1;
    const magUnit = p ? p.fftMagUnit.get() : 'DBFS';
    const magLog = unitIsLog(magUnit);
    // The ANALYZED channel - every dBFS->display conversion below de-references with THIS
    // channel's ADC dBV offset (Java FftView threads prefs.getFftChannel() through every
    // convertFromDbFs / getDbvOffsetDb call site).
    const ch = p ? p.fftChannel.get() : 'L';
    // Java FftView (:1197-1198): magBot/magTop = prefs.convertFromDbFs(getFftMag*, unit)
    // - the 2-arity form, so V/√Hz uses the cached binBwSqrt. The per-bin TRACE uses the
    // result's binBwSqrt (convertFromDbFs(dbFs, unit, r.binBwSqrt), :2290). The axis is
    // LOG-VOLTAGE for V/V√Hz (magToYFraction maps log(v)). Dots/crosshair use cv() too.
    const cv = (dbFs) => p ? p.convertFromDbFs(dbFs, magUnit, null, ch) : dbFs;
    const magBot = cv(p ? p.fftMagBottom.get() : this.botDb);
    const magTop = cv(p ? p.fftMagTop.get() : this.topDb);
    // Java plot rect: x=MARGIN_LEFT, y=MARGIN_TOP, width=W-MARGIN_LEFT-rightMargin(1),
    // height=H-MARGIN_TOP-MARGIN_BOTTOM. (Full-height plot, no magic insets.)
    const plot = {
      x: MARGIN_LEFT, y: MARGIN_TOP,
      width: Math.max(1, W - MARGIN_LEFT - 1),
      height: Math.max(1, H - MARGIN_TOP - MARGIN_BOTTOM),
    };
    // x(f) / y(v) used by the trace / dots / crosshair downstream - Java freqToX /
    // magToY. y() takes a value already in the DISPLAY unit (cv(dbFs)).
    const x = (f) => this._freqToX(f, plot, fMin, fMax, logAxis);
    const y = (v) => this._magToY(v, plot, magTop, magBot, magUnit);

    g.fillStyle = p ? colorHex(p.fftChartBackgroundColor.get()) : '#ffffff'; g.fillRect(0, 0, W, H);
    // Distortion-band shading (Java drawDistortionBands): grey the spectrum OUTSIDE the active
    // HP/LP THD-integration window so the excluded bands are visible.
    if (p) {
      g.fillStyle = '#dcdcdc';
      if (p.fftDistMinEnabled.get()) {
        const hp = p.fftDistMinHz.get();
        if (hp > fMin) { const xR = x(Math.min(hp, fMax)); if (xR > plot.x) g.fillRect(plot.x, plot.y, xR - plot.x, plot.height); }
      }
      if (p.fftDistMaxEnabled.get()) {
        const lp = p.fftDistMaxHz.get();
        if (lp < fMax) { const xL = x(Math.max(lp, fMin)), xR = plot.x + plot.width; if (xR > xL) g.fillRect(xL, plot.y, xR - xL, plot.height); }
      }
    }
    // ---- Grid + tick labels - faithful Java drawGrid port. ----
    this._drawGrid(g, plot, { fMin, fMax, logAxis, magTop, magBot, magUnit, magLog });

    // No result yet (0 averages): the empty graticule above is the whole frame. Java
    // still paints the crosshair over it (drawCrosshair runs whenever the pointer is in
    // the plot, independent of lastResult) - only the trace / dots / table need a result.
    if (!result) {
      this._drawCrosshair({ W, H, plot, margin, logAxis, fMin, fMax, magTop, magBot, magUnit, mag: null, binW, result: null });
      // Rect-zoom overlay draws LAST, over the empty graticule too (Java onPaint
      // ends with drawRectZoomOverlay on every paint).
      if (this._rectZoom) this._rectZoom.drawOverlay(g, W, H);
      return;
    }

    // trace - per-pixel-column min/max envelope (Java ColumnBucketPainter): a polyline through
    // each column's midpoint + a vertical bar across dense (multi-bin) columns, so troughs /
    // noise-floor structure between peaks survive (a max-only trace reads too high). The final
    // column is flushed too - the old max-only loop dropped the rightmost column.
    g.strokeStyle = p ? colorHex(p.fftLineColor.get()) : '#1a5fb4'; g.lineWidth = p ? p.fftLineWidth.get() : 1;
    // Java drawSpectrum / binYTrace (:2288): per-bin dBFS -> convertFromDbFs(unit) ->
    // magToYTrace (NO clamp; the GC clip cuts an over-range flank flush). Column min/max
    // are compared in raw dBFS (every unit conversion is monotonic in dBFS), then the
    // two column extremes converted + Y-mapped - Java ColumnBucketPainter envelope.
    const binYTrace = (dbFs) => this._magToYTrace(
      p ? p.convertFromDbFs(dbFs, magUnit, result.binBwSqrt, ch) : dbFs, plot, magTop, magBot, magUnit);
    const yClip = (yPx) => Math.max(plot.y, Math.min(plot.y + plot.height, yPx));
    const bars = [];
    const mids = [];
    let lastPx = -1, colMin = 300, colMax = -300, colCnt = 0;
    const flushCol = (px) => {
      // Java: maxDb is the higher level (smaller Y, towards top); the envelope bar
      // spans the column min/max, the midpoint trace runs through the column centre.
      // Bars mirror Java ColumnBucketPainter.drawTo:1353-1356: multi-bin columns only,
      // Y clamped to the plot (rounding + the collapsed-bar skip happen at draw time,
      // in DEVICE pixels - see pass 1 below).
      if (colCnt >= 2) bars.push([px, yClip(binYTrace(colMax)), yClip(binYTrace(colMin))]);
      mids.push([px, binYTrace((colMin + colMax) / 2)]);
    };
    // Manual-fundamental: lift the fundamental's WHOLE main lobe to the user
    // value at RENDER TIME (Java FftView.drawSpectrum :2234-2258). Visual only -
    // the stored spectrum (amplitudeDbFs / re / im) is NEVER touched, so THD/SNR
    // stay on the raw measured peak (the table uses the manual value via
    // _manualFundDbFs). It is the SAME ToneLobeLift.stretch the .frc de-embed
    // uses, with the manual/peak ratio as the scale. Gated on the manual value
    // being finite - NOT on whether a cal is loaded: a cal + manual compose, the
    // stretch reads the already-de-embedded spectrum and lifts it to the manual
    // level (Java's gate is on fundamentalTrueDbFs finite, independent of cal).
    let lobeLo = -1, lobeHi = -1, floorLin = 0, liftFactor = 1, peakMagLin = 0;
    const manFundDbFs = this._manualFundDbFs(result);
    if (Number.isFinite(manFundDbFs) && Number.isFinite(result.fundamentalDbFs)
        && Number.isFinite(result.fundamentalHzRefined) && binW > 0) {
      const peak = Math.round(result.fundamentalHzRefined / binW);
      const half = mag.length - 1;
      if (peak >= 1 && peak <= half) {
        const magLin = (k) => Math.pow(10, mag[k] / 20);   // dBFS -> linear (Java mag lambda)
        floorLin = LOBE.localFloor(magLin, peak, half);
        const edges = LOBE.lobeBins(magLin, peak, half, floorLin);
        lobeLo = edges[0]; lobeHi = edges[1];
        peakMagLin = magLin(peak);
        liftFactor = Math.pow(10, (manFundDbFs - mag[peak]) / 20);   // manual dBFS / measured peak
      }
    }
    g.save();
    g.beginPath(); g.rect(plot.x, plot.y, plot.width, plot.height); g.clip();
    for (let k = 1; k < mag.length; k++) {
      const f = k * binW; if (f < fMin || f > fMax) continue;
      const px = Math.round(x(f)); if (px < plot.x) continue;
      if (px !== lastPx && lastPx >= 0) { flushCol(lastPx); colMin = 300; colMax = -300; colCnt = 0; }
      // Per-bin dBFS feeding the column envelope: RAW value, except inside the
      // manual-fundamental lobe, where it is stretched up to the user level
      // (Java :2299-2302). mag (= result.amplitudeDbFs) is never mutated.
      let dbK = mag[k];
      if (lobeLo >= 0 && k >= lobeLo && k <= lobeHi) {
        const nm = LOBE.stretch(Math.pow(10, dbK / 20), floorLin, peakMagLin, liftFactor);
        dbK = nm > 1e-15 ? 20 * Math.log10(nm) : -300;
      }
      if (dbK < colMin) colMin = dbK;
      if (dbK > colMax) colMax = dbK;
      colCnt++; lastPx = px;
    }
    if (lastPx >= 0) flushCol(lastPx);
    // Pass 1 (Java ColumnBucketPainter.drawTo:1346-1359): the vertical envelope bars are
    // drawn AA-OFF at integer NATIVE-pixel coords in Java - fully OPAQUE columns, the
    // solid fill of the noise band. Two canvas pitfalls both re-created the striping:
    // a 1-px STROKE centered on integer x straddles two half-intensity columns, and -
    // even with fillRect - CSS-px integers land on FRACTIONAL device pixels under a
    // fractional devicePixelRatio (Windows 125/150 % scaling), anti-aliasing again.
    // So the bars are drawn with the transform RESET, snapped to the DEVICE-pixel grid:
    // each CSS column [bx−w/2, bx+w/2) maps to [round((bx−w/2)·dpr), round((bx+w/2)·dpr))
    // - adjacent columns tile EXACTLY (col n's right edge == col n+1's left edge) at any
    // dpr, so the band is gapless and opaque like Java's native-pixel bars.
    g.fillStyle = g.strokeStyle;
    const barW = Math.max(1, g.lineWidth);
    if (g.setTransform) {
      g.setTransform(1, 0, 0, 1, 0, 0);
      for (const [bx, lo, hi] of bars) {
        const x0 = Math.round((bx - barW / 2) * dpr);
        const x1 = Math.round((bx + barW / 2) * dpr);
        const y0 = Math.round(lo * dpr);
        const y1 = Math.round(hi * dpr);
        if (y0 === y1) continue;   // Java :1356 - collapsed bar (native px) skipped
        g.fillRect(x0, y0, Math.max(1, x1 - x0), y1 - y0);
      }
      g.setTransform(dpr, 0, 0, dpr, 0, 0);   // back to CSS-px space for pass 2
    } else {
      // Headless mock context (no setTransform): plain CSS-px fills, dpr is 1 there.
      for (const [bx, lo, hi] of bars) g.fillRect(bx, Math.round(lo), 1, Math.max(1, Math.round(hi) - Math.round(lo)));
    }
    // Pass 2 (Java :1360-1384): the midpoint polyline as ONE anti-aliased sub-pixel path,
    // stroked OVER the opaque bars - the Java pass order, so it blends into the band.
    g.beginPath();
    let started = false;
    for (const [mx, my] of mids) { started ? g.lineTo(mx, my) : (g.moveTo(mx, my), started = true); }
    g.stroke();
    g.restore();

    const imd = result.imd;
    // Single-tone fundamental + harmonic red dots + F / H2..Hn labels (Java drawHarmonicDots /
    // drawHarmonicLabel). Fundamental dB uses the de-embedded true level when finite.
    if (!imd && result.harmonicHz) {
      const dotColor = p ? colorHex(p.fftHarmonicDotColor.get()) : '#ff0000';
      const dotR = Math.max(2, (p ? p.fftHarmonicDotDiameter.get() : 9) / 2);
      const calcMax = p ? Math.max(9, p.fftCalcMaxHarmonic.get()) : 9;
      // Java drawHarmonicDots / dotScreenPos (:1809): dBFS -> convertFromDbFs(unit),
      // drop when the y-fraction is off-plot (t<0||t>1), then magToY.
      const dotAt = (fHz, d, label) => {
        if (!(fHz >= fMin && fHz <= fMax) || !Number.isFinite(d)) return;
        const v = cv(d), t = this._magToYFraction(v, magTop, magBot, magUnit);
        if (t < 0 || t > 1) return;
        const dx = x(fHz), dy = y(v);
        g.fillStyle = dotColor; g.beginPath(); g.arc(dx, dy, dotR, 0, 2 * Math.PI); g.fill();
        if (label) this._dotLabel(g, label, dx, dy - dotR - 4);
      };
      // Before-cal blue dots (Java BEFORE_CAL_DOT): the pre de-embed peak levels, painted UNDER
      // the red corrected dots so the .frc correction gap is visible. [0]=freqs, [1]=dBFS.
      const pre = result.preCorrectionPeaks;
      if (pre && pre[0] && pre[1]) {
        g.fillStyle = p ? colorHex(p.fftBeforeCalDotColor.get()) : '#000080';
        for (let i = 0, n = Math.min(pre[0].length, pre[1].length); i < n; i++) {
          const pf = pre[0][i], pd = pre[1][i];
          if (pf >= fMin && pf <= fMax && Number.isFinite(pd)) {
            const v = cv(pd), t = this._magToYFraction(v, magTop, magBot, magUnit);
            if (t < 0 || t > 1) continue;
            g.beginPath(); g.arc(x(pf), y(v), dotR, 0, 2 * Math.PI); g.fill();
          }
        }
      }
      // Java drawHarmonicDots (:1349): the fundamental dot sits on the DISPLAYED
      // peak - the manual fundamental (fundamentalTrueDbFs) when set, else the
      // measured level - ALWAYS at the auto-detected r.fundamentalHzRefined (NOT
      // the manual frequency). _manualFundDbFs re-gates on the live manual on/off
      // pref so switching manual OFF drops back to the auto level (or the dot
      // disappears when no fundamental) instead of pinning the stale override;
      // switching ON restores the dot at the manual level.
      const fHz = result.fundamentalHzRefined || 0;
      const man = this._manualFundDbFs(result);
      const fDb = Number.isFinite(man) ? man : result.fundamentalDbFs;
      // Manual-fundamental original-height blue dot (WEB addition - the Java fix is parallel-ongoing;
      // the committed Java draws blue dots only under a .frc de-embed). With manual fundamental set
      // and NO cal (preCorrectionPeaks unset), the red F dot rises to the manual value and the lobe
      // is stretched up to meet it - mark the ORIGINAL measured height with a blue dot
      // (BEFORE_CAL_DOT colour), painted UNDER the red dot, mirroring the de-embed's before/after
      // pair. When a cal IS loaded the pre-correction blue dots above already cover it.
      if (Number.isFinite(man) && !(pre && pre[0] && pre[1])
          && Number.isFinite(result.fundamentalDbFs) && fHz >= fMin && fHz <= fMax) {
        const bv = cv(result.fundamentalDbFs), bt = this._magToYFraction(bv, magTop, magBot, magUnit);
        if (bt >= 0 && bt <= 1) {
          g.fillStyle = p ? colorHex(p.fftBeforeCalDotColor.get()) : '#000080';
          g.beginPath(); g.arc(x(fHz), y(bv), dotR, 0, 2 * Math.PI); g.fill();
        }
      }
      dotAt(fHz, fDb, 'F ' + this._fmtFreq(fHz));
      const hCount = Math.min(result.harmonicHz.length, result.harmonicDbFs.length, result.harmonicCount);
      for (let i = 0; i < hCount; i++) {
        const n = i + 2;
        dotAt(result.harmonicHz[i], result.harmonicDbFs[i], n <= calcMax ? ('H' + n) : null);
      }
    }
    if (imd) {
      // Dual-tone: F1/F2 + per-order lower/upper intermod products d2L..dnH (Java drawImdDots).
      // Product levels are dBV -> de-reference to the dBFS axis with the dBV offset. Drop dots
      // outside the freq/mag window (no clamping, matching Java). Same marker styling as the
      // single-tone path - HARMONIC_DOT colour + harmonicDotDiameter for every dot (Java
      // drawImdDots:1965 / plotDotAt:2088-2090).
      // IMD dBV products de-reference to dBFS with the ANALYZED channel's ADC offset
      // (Java FftController:237 imdAnalyzer.analyze(..., getDbvOffsetDb(getFftChannel()))).
      const refDbV = p ? p.getDbvOffsetDb(p.fftChannel.get()) : 0;
      const dotColor = p ? colorHex(p.fftHarmonicDotColor.get()) : '#ff0000';
      const dotR = Math.max(2, (p ? p.fftHarmonicDotDiameter.get() : 9) / 2);
      // Java plotDotAt: fill the dot in HARMONIC_DOT; the fill colour is set immediately
      // before each arc (like the single-tone dotAt) so _dotLabel's halo strokeStyle /
      // fillStyle can't clobber later dots. No vertical stem - Java draws none.
      const imdDot = (fHz, dbfs) => {
        if (!(fHz >= fMin && fHz <= fMax) || !Number.isFinite(dbfs)) return;
        const v = cv(dbfs), t = this._magToYFraction(v, magTop, magBot, magUnit);
        if (t < 0 || t > 1) return;
        g.fillStyle = dotColor; g.beginPath(); g.arc(x(fHz), y(v), dotR, 0, 2 * Math.PI); g.fill();
      };
      // Blue "before-cal" dots FIRST, under the red post-cal dots, so the .frc correction gap is
      // visible - one per IMD dot position (F1, F2, dnL[2..5], dnH[2..5]) at dbFs + sumCalDbAt,
      // only when a calibration file is loaded (Java drawImdDots:1948-1962). Same freq/finite/mag
      // gates as imdDot; channel pick off result.channelLeft (matching the de-embed, apply()).
      if (this.correction && this.correction.store.getEntries().length) {
        const wantLeft = result.channelLeft;
        g.fillStyle = p ? colorHex(p.fftBeforeCalDotColor.get()) : '#000080';
        const preDot = (fHz, dbfs) => {
          if (!(fHz >= fMin && fHz <= fMax) || !Number.isFinite(dbfs)) return;
          const preDbFs = dbfs + this.correction.sumCalDbAt(wantLeft, fHz);
          const v = cv(preDbFs), t = this._magToYFraction(v, magTop, magBot, magUnit);
          if (t < 0 || t > 1) return;
          g.beginPath(); g.arc(x(fHz), y(v), dotR, 0, 2 * Math.PI); g.fill();
        };
        preDot(imd.f1Hz, imd.f1DbFs);
        preDot(imd.f2Hz, imd.f2DbFs);
        if (imd.dnLHz) {
          for (let k = 2; k < imd.dnLHz.length; k++) {
            preDot(imd.dnLHz[k], imd.dnLDbV[k] - refDbV);
            preDot(imd.dnHHz[k], imd.dnHDbV[k] - refDbV);
          }
        }
      }
      imdDot(imd.f1Hz, imd.f1DbFs);
      imdDot(imd.f2Hz, imd.f2DbFs);
      if (imd.dnLHz) {
        for (let k = 2; k < imd.dnLHz.length; k++) {
          imdDot(imd.dnLHz[k], imd.dnLDbV[k] - refDbV);
          imdDot(imd.dnHHz[k], imd.dnHDbV[k] - refDbV);
        }
      }
      // F1 / F2 labels - "F1 <freq>" / "F2 <freq>" with overlap avoidance (Java
      // drawImdDots:1976-2031). Each label is anchored just above its own dot; when the two
      // label boxes overlap the lower dot's label slides into the gap between the higher
      // label's bottom and the lower dot if it fits, else stacks one line above the higher
      // label. Both labels stay strictly above their own dots and clamp into the plot
      // horizontally. _dotLabel draws the halo text (baseline bottom, centred on cx).
      const dotPos = (fHz, dbfs) => {
        if (!(fHz >= fMin && fHz <= fMax) || !Number.isFinite(dbfs)) return null;
        const v = cv(dbfs), t = this._magToYFraction(v, magTop, magBot, magUnit);
        if (t < 0 || t > 1) return null;
        return { x: x(fHz), y: y(v) };
      };
      const pos1 = dotPos(imd.f1Hz, imd.f1DbFs);
      const pos2 = dotPos(imd.f2Hz, imd.f2DbFs);
      if (pos1 || pos2) {
        const t1 = 'F1 ' + this._fmtFreq(imd.f1Hz);
        const t2 = 'F2 ' + this._fmtFreq(imd.f2Hz);
        // Match _dotLabel's font so measureText widths align with the rendered text.
        g.font = '10px "Segoe UI", sans-serif';
        const w1 = g.measureText(t1).width, w2 = g.measureText(t2).width;
        const LBL_H = 12;         // Java ext.y - 10px label height (font + descent)
        const margin = 4;         // dot-to-label vertical gap (Java)
        const gap = 2;            // label-to-label vertical gap (Java)
        let ly1 = pos1 ? pos1.y - margin - LBL_H : 0;   // label TOP y
        let ly2 = pos2 ? pos2.y - margin - LBL_H : 0;
        if (pos1 && pos2) {
          const bot1 = ly1 + LBL_H, bot2 = ly2 + LBL_H;
          const overlap = !(bot1 + gap <= ly2 || bot2 + gap <= ly1);
          if (overlap) {
            if (pos1.y <= pos2.y) {
              const candTop = bot1 + gap;
              ly2 = (candTop + LBL_H + margin <= pos2.y) ? candTop : ly1 - gap - LBL_H;
            } else {
              const candTop = bot2 + gap;
              ly1 = (candTop + LBL_H + margin <= pos1.y) ? candTop : ly2 - gap - LBL_H;
            }
          }
        }
        const pR = plot.x + plot.width;
        // Java clamps the label's LEFT edge into [plot.x, plot.x+width-ext.x]; _dotLabel
        // centres on cx, so pass the clamped box centre. It also clamps the top to plot.y.
        if (pos1) {
          const lx = Math.max(plot.x, Math.min(pR - w1, pos1.x - w1 / 2));
          this._dotLabel(g, t1, lx + w1 / 2, Math.max(plot.y, ly1) + LBL_H);
        }
        if (pos2) {
          const lx = Math.max(plot.x, Math.min(pR - w2, pos2.x - w2 / 2));
          this._dotLabel(g, t2, lx + w2 / 2, Math.max(plot.y, ly2) + LBL_H);
        }
      }
      // dnL / dnH labels keep the simple above-dot placement (Java drawHarmonicLabel).
      if (imd.dnLHz) {
        for (let k = 2; k < imd.dnLHz.length; k++) {
          const lp = dotPos(imd.dnLHz[k], imd.dnLDbV[k] - refDbV);
          if (lp) this._dotLabel(g, 'd' + k + 'L', lp.x, lp.y - dotR - 4);
          const hp = dotPos(imd.dnHHz[k], imd.dnHDbV[k] - refDbV);
          if (hp) this._dotLabel(g, 'd' + k + 'H', hp.x, hp.y - dotR - 4);
        }
      }
    }

    // THD / IMD measurement table - painted ON the canvas (Java FftView paint
    // switch, drawDistortionTable / drawImdTable). Gated on the distortion-table
    // pref AND "!tableExtracted" AND a distinguishable signal (Java FftView.java:1253-1254:
    // "if (lastResult != null && Preferences.instance().isFftDistortionTableVisible()
    //      && !tableExtracted && hasDistinguishableSignal(lastResult))" - when the table is
    // extracted into the tool window it is NOT drawn on the spectrum); dual-tone -> IMD
    // table, else THD table.
    if (p && p.fftDistortionTableVisible.get() && !this.tableExtracted
        && this._hasDistinguishableSignal(result)) {
      const xLeft = MARGIN_LEFT + 6;
      if (imd) this.drawImdTable(g, imd, result, xLeft, TABLE_TOP_Y);
      else this.drawDistortionTable(g, result, magUnit, xLeft, TABLE_TOP_Y);
    }

    // Crosshair + cursor readout - Java drawCrosshair, drawn whenever the cursor is
    // inside the plot (with or without a result; the |m| line is omitted when none).
    this._drawCrosshair({ W, H, plot, margin, logAxis, fMin, fMax, magTop, magBot, magUnit, mag, binW, result });
    // Rect-zoom rubber band + focused-view accent border - LAST (Java onPaint
    // calls drawRectZoomOverlay after the crosshair).
    if (this._rectZoom) this._rectZoom.drawOverlay(g, W, H);
  }

  /** Java FftView.drawCrosshair - dashed grey cross + an f / y / |m| readout box at the
   *  cursor. Drawn over the empty graticule too (Java paints it whenever the pointer is
   *  inside the plot, independent of lastResult); the |m| bin level is shown only when a
   *  result/spectrum exists. */
  _drawCrosshair({ W, H, plot, logAxis, fMin, fMax, magTop, magBot, magUnit, mag, binW, result }) {
    const g = this.g;
    const cx = this._crossX, cy = this._crossY;
    const pL = plot.x, pR = plot.x + plot.width, pT = plot.y, pB = plot.y + plot.height;
    if (!(cx >= pL && cx <= pR && cy >= pT && cy <= pB)) return;
    g.save();
    g.strokeStyle = '#888'; g.lineWidth = 1; g.setLineDash([2, 3]);
    g.beginPath(); g.moveTo(cx, pT); g.lineTo(cx, pB); g.moveTo(pL, cy); g.lineTo(pR, cy); g.stroke();
    g.setLineDash([]);
    // Java drawCrosshair (:2818): f = xToFreq(crossX); y = formatMagnitudeWithUnit(
    // yToMag(crossY,...)); |m| = formatMagnitudeWithUnit(convertFromDbFs(bin dBFS, unit, binBwSqrt)).
    const t = (cx - plot.x) / plot.width;
    let fAt;
    if (logAxis) {
      const safeMin = Math.max(1, fMin), safeMax = Math.max(fMax, safeMin * 1.0000001);
      const lo = Math.log10(safeMin), hi = Math.log10(safeMax);
      fAt = Math.pow(10, lo + t * (hi - lo));
    } else {
      fAt = fMin + t * (fMax - fMin);
    }
    const yMag = this._yToMag(cy, plot, magTop, magBot, magUnit);
    const lines = [
      'f = ' + this._fmtFreqFine(fAt),
      'y = ' + formatMagnitudeWithUnit(yMag, magUnit),
    ];
    // Java appends |m| (in the DISPLAY unit) only when a spectrum is present.
    if (mag && result && binW > 0) {
      const bin = Math.round(fAt / binW);
      if (bin >= 0 && bin < mag.length) {
        // Java drawCrosshair (:2828): the fundamental bin reads its manual-override
        // level so |m| matches the displayed (lobe-lifted) trace - but only while
        // manual mode is enabled (_manualFundDbFs re-gates on the live pref so a
        // stale override doesn't override the auto level after switch-off).
        let dbFs = mag[bin];
        const man = this._manualFundDbFs(result);
        if (Number.isFinite(man)
            && Number.isFinite(result.fundamentalHzRefined)
            && bin === Math.round(result.fundamentalHzRefined / binW)) {
          dbFs = man;
        }
        const v = this.prefs ? this.prefs.convertFromDbFs(dbFs, magUnit, result.binBwSqrt, this.prefs.fftChannel.get()) : dbFs;
        lines.push('|m| = ' + formatMagnitudeWithUnit(v, magUnit));
      }
    }
    g.font = '11px Consolas, monospace'; g.textBaseline = 'top';
    const bw = 140, bh = lines.length * 14 + 6;
    let bx = cx + 12, by = cy + 12;
    if (bx + bw > pR) bx = cx - 12 - bw;
    if (by + bh > pB) by = cy - 12 - bh;
    g.fillStyle = 'rgba(255,255,255,0.85)'; g.fillRect(bx, by, bw, bh);
    g.strokeStyle = '#888'; g.strokeRect(bx, by, bw, bh);
    g.fillStyle = '#222'; g.textAlign = 'left';
    lines.forEach((ln, i) => g.fillText(ln, bx + 5, by + 4 + i * 14));
    g.restore();
  }

  /** Java AbstractMeasurementView.formatFrequencyFine (:1069) - 4 decimals,
   *  kHz at 10 kHz. */
  _fmtFreqFine(f) {
    if (!Number.isFinite(f) || f <= 0) return '-';
    if (f >= 10000) return (f / 1000).toFixed(4) + ' kHz';
    return f.toFixed(4) + ' Hz';
  }

  /* ---- Faithful Java AbstractMeasurementView.drawGrid port (:584). ---------
   * X axis: log freq -> AxisSpec.log (decade majors + 2..9×10ⁿ minors, sub-decade
   *   falls back to nice-linear), LabelFormat.FREQ; linear freq -> AxisSpec.linearNice
   *   (niceLinearMajors targetCount 10), LabelFormat.FREQ_INT.
   * Y axis (magnitude): log units (V/V√Hz) -> AxisSpec.log + LabelFormat.VOLTS_SI
   *   (label round 1·2·3·5·7×10ⁿ via adaptiveLogLabels, thinned by decade count);
   *   dB units -> AxisSpec.linearNice(...,10,5.0) + LabelFormat.DB ("%.1f").
   * The magnitude axis bounds are in DISPLAY units (magTop/magBot, log-voltage for
   * V/V√Hz). */
  _drawGrid(g, plot, { fMin, fMax, logAxis, magTop, magBot, magUnit, magLog }) {
    const pL = plot.x, pR = plot.x + plot.width, pT = plot.y, pB = plot.y + plot.height;
    // ---- X (frequency) tick positions. ----
    let xMajors, xMinors;
    if (logAxis) {
      if (isSubDecade(fMin, fMax)) {
        xMajors = niceLinearMajors(fMin, fMax, SUB_DECADE_TICK_TARGET);
        xMinors = [];   // sub-decade minors are subdivisions; FREQ axis rarely sub-decade - keep majors only
      } else {
        xMajors = logMajorTicks(fMin, fMax);
        xMinors = logMinorTicks(fMin, fMax);
      }
    } else {
      xMajors = niceLinearMajors(fMin, fMax, FREQ_NICE_TARGET);
      xMinors = [];
    }
    // ---- Y (magnitude) tick positions. ----
    let yMajors, yMinors;
    if (magLog) {
      if (isSubDecade(magBot, magTop)) {
        yMajors = niceLinearMajors(magBot, magTop, SUB_DECADE_TICK_TARGET);
        yMinors = [];
      } else {
        yMajors = logMajorTicks(magBot, magTop);
        yMinors = logMinorTicks(magBot, magTop);
      }
    } else {
      yMajors = niceLinearMajors(magBot, magTop, MAG_DB_NICE_TARGET);
      yMinors = niceLinearMinors(magBot, magTop, MAG_DB_MINOR_STEP);
    }

    const fx = (f) => this._freqToX(f, plot, fMin, fMax, logAxis);
    const fy = (v) => this._magToY(v, plot, magTop, magBot, magUnit);

    // ---- Grid lines. ----
    g.lineWidth = 1;
    g.strokeStyle = '#e6e6e6';   // minors (Java GRID, lighter for minors via the same colour)
    g.beginPath();
    for (const v of xMinors) { const px = fx(v); g.moveTo(px + 0.5, pT); g.lineTo(px + 0.5, pB); }
    for (const v of yMinors) { const py = fy(v); g.moveTo(pL, py + 0.5); g.lineTo(pR, py + 0.5); }
    g.stroke();
    g.strokeStyle = '#cfcfcf';   // majors
    g.beginPath();
    for (const v of xMajors) { const px = fx(v); g.moveTo(px + 0.5, pT); g.lineTo(px + 0.5, pB); }
    for (const v of yMajors) { const py = fy(v); g.moveTo(pL, py + 0.5); g.lineTo(pR, py + 0.5); }
    g.stroke();

    // ---- Frame. ----
    g.strokeStyle = '#999';
    g.strokeRect(pL + 0.5, pT + 0.5, plot.width, plot.height);

    // ---- X tick labels (Java drawGrid :717). ----
    g.font = '11px "Segoe UI", sans-serif';
    g.fillStyle = '#333'; g.textAlign = 'center'; g.textBaseline = 'top';
    // wideLog: log axis spanning >= one decade -> adaptiveLogLabels; else the majors.
    const wideLog = logAxis && !isSubDecade(fMin, fMax);
    const xLabelPositions = wideLog ? adaptiveLogLabels(fMin, fMax) : xMajors;
    // Step-aware formats (FREQ, DB) size their decimals from the tick step, so fine zooms read
    // 1.005 kHz / 1.010 kHz instead of several identical "1 kHz"; a wide log axis has no single
    // step and keeps the decade formatter (Java drawGrid -> labelStep).
    const fineStep = labelStep(xLabelPositions, logAxis, fMin, fMax);
    // Java pixel-aware placement: place decade majors first, then fill the rest,
    // skipping any whose label box would touch one already placed.
    const gap = g.measureText('0').width;
    const items = xLabelPositions.map((v) => {
      const str = logAxis
        ? (fineStep > 0 ? formatFreqTick(v, fineStep) : formatFreqDecadeTick(v))
        : formatFrequencyInteger(v);
      const sw = g.measureText(str).width;
      const cxp = fx(v);
      return { v, str, left: cxp - sw / 2, right: cxp - sw / 2 + sw };
    });
    const placed = [];
    const drawn = new Array(items.length).fill(false);
    for (let pass = 0; pass < 2; pass++) {
      for (let i = 0; i < items.length; i++) {
        const it = items[i];
        if (drawn[i] || (pass === 0) !== isDecadeValue(it.v)) continue;
        let clash = false;
        for (const o of placed) { if (it.left < o[1] + gap && it.right + gap > o[0]) { clash = true; break; } }
        if (clash) continue;
        g.fillText(it.str, (it.left + it.right) / 2, pB + 4);
        placed.push([it.left, it.right]); drawn[i] = true;
      }
    }
    // ---- Y tick labels (Java drawGrid :766). ----
    g.textAlign = 'right'; g.textBaseline = 'middle';
    const yWideLog = magLog && !isSubDecade(magBot, magTop);
    const yLabelPositions = yWideLog ? adaptiveLogLabels(magBot, magTop) : yMajors;
    const yStep = labelStep(yLabelPositions, magLog, magBot, magTop);
    const fh = 12;   // font height for the overlap skip
    let lastPy = -1e9;
    for (const v of yLabelPositions) {
      const py = fy(v);
      if (Math.abs(py - lastPy) < fh) continue;
      g.fillStyle = '#333';
      g.fillText(formatMagTick(v, magUnit, yStep), pL - 6, py);
      lastPy = py;
    }
    // ---- Magnitude unit caption (top-left of the Y axis). ----
    g.textAlign = 'left'; g.textBaseline = 'top';
    g.fillStyle = '#333';
    g.fillText(this._magAxisLabel(magUnit), 4, pT + 2);
  }

  /** Formats a frequency for a harmonic-dot label (e.g. "1.00 kHz"). */
  _fmtFreq(f) {
    if (!(f > 0)) return '-';
    return f >= 1000 ? (f / 1000).toFixed(2) + ' kHz' : f.toFixed(f < 10 ? 2 : 0) + ' Hz';
  }

  /** Draws a halo-outlined label centred above (cx, cy) - Java drawOutlinedText. */
  _dotLabel(g, text, cx, cy) {
    const yy = Math.max(11, cy);
    g.font = '10px "Segoe UI", sans-serif'; g.textAlign = 'center'; g.textBaseline = 'bottom';
    g.lineJoin = 'round'; g.lineWidth = 3; g.strokeStyle = '#fff'; g.strokeText(text, cx, yy);
    g.fillStyle = '#222'; g.fillText(text, cx, yy);
  }

  // ───────────────────────────── THD / IMD tables ─────────────────────────────
  // Faithful Canvas2D port of Java FftView.drawDistortionTable / drawImdTable /
  // drawKv / drawCentred. The desktop draws into a GC with a mono font; here the
  // column widths are N·charW where charW = measureText('M') of the same mono font.
  //
  // The Java FftView has a WINDOW_RESTORE toggle (externalBtn) that extracts this
  // THD/IMD table into a separate OS ToolWindow whose ContentPainter is paintDistortion
  // (drawDistortionTable / drawImdTable). The web ports that rendering as
  // paintDistortionInto(g, top) + distortionContentSize(g) so the pane
  // can paint the SAME table into a separate float-window canvas; inline rendering keeps
  // calling drawDistortionTable / drawImdTable directly. The distortion-table visibility
  // + reset buttons that DO map are wired in fft-pane.js (they need the engine for the
  // reset).

  /** Java FftView.hasDistinguishableSignal - the fundamental must clear the LOCAL
   *  noise floor by SIGNAL_FLOOR_MARGIN_DB and THD must be sane, else the table /
   *  dots are grass and are hidden. */
  _hasDistinguishableSignal(r) {
    if (r == null || !Number.isFinite(r.fundamentalDbFs)) return false;
    if (Number.isFinite(r.thdPct) && r.thdPct > MAX_THD_PCT) return false;
    const floor = this._localNoiseFloorDbFs(r);
    if (!Number.isFinite(floor)) return true;   // no floor estimate - don't suppress
    return r.fundamentalDbFs > floor + SIGNAL_FLOOR_MARGIN_DB;
  }

  /** Java FftResult.localNoiseFloorDbFs - median of two LOCAL_FLOOR_FLANK_BINS-wide
   *  flanks just beyond the fundamental's dynamic skirt. NaN when no usable bins. */
  _localNoiseFloorDbFs(r) {
    const mag = r.amplitudeDbFs;
    if (mag == null || !(r.freqResolution > 0) || r.fundamentalBin <= 0) return NaN;
    const n = mag.length;
    const skirt = Math.max(1, Math.ceil(r.fundamentalDynExclusionHz / r.freqResolution));
    const band = []; let cnt = 0;
    for (let b = r.fundamentalBin - skirt - LOCAL_FLOOR_FLANK_BINS; b < r.fundamentalBin - skirt; b++) {
      if (b >= 1 && b < n && Number.isFinite(mag[b])) band[cnt++] = mag[b];
    }
    for (let b = r.fundamentalBin + skirt + 1; b <= r.fundamentalBin + skirt + LOCAL_FLOOR_FLANK_BINS; b++) {
      if (b >= 1 && b < n && Number.isFinite(mag[b])) band[cnt++] = mag[b];
    }
    if (cnt === 0) return NaN;
    band.length = cnt; band.sort((x, y) => x - y);
    return band[Math.trunc(cnt / 2)];
  }

  /** Java FftView.formatSpan - the noise-integration band on the header span row. */
  _formatSpan(r) {
    const hasLo = r.snrFreqMin > 0, hasHi = r.snrFreqMax > 0;
    if (!hasLo && !hasHi) return 'Span: full';
    const lo = hasLo ? r.snrFreqMin : 0;
    const hi = hasHi ? r.snrFreqMax : r.sampleRate / 2.0;
    return `Span: ${lo.toFixed(0)} .. ${hi.toFixed(0)} Hz`;
  }

  /** Java FftView.fmtDb - "%7.2f dBV" or "-". Fixed-unit: the level-unit selector covers
   *  the per-harmonic / per-product rows only; N+D and SNR are ratios against the
   *  fundamental rather than levels, so a unit switch would not even apply to them. */
  _fmtDb(v) {
    return Number.isFinite(v) ? `${v.toFixed(2)} dBV` : '-';
  }

  /** Java FftView.levelInDistortionUnit - an absolute level, stored in dBFS, rendered in the
   *  table's selected unit: dBV adds the analysed channel's ADC offset, dBFS is the measured
   *  value itself, and dB subtracts {@code refDbFs} - the SAME reference the row's percentage
   *  divides by, so the two columns always agree (dB == 20·log10(pct/100)). */
  _levelInDistortionUnit(dbFs, refDbFs, unit, dbvOff) {
    if (unit === 'DBFS') return dbFs;
    if (unit === 'DBR') return dbFs - refDbFs;
    return dbFs + dbvOff;
  }

  /** Java FftView.distortionUnitLabel - label for a distortion-table unit; the plain "dB"
   *  for the relative one. */
  _distortionUnitLabel(unit) {
    if (unit === 'DBFS') return DISTORTION_UNIT_LABELS[1];
    if (unit === 'DBR') return DISTORTION_UNIT_LABELS[2];
    return DISTORTION_UNIT_LABELS[0];
  }

  /** The distortion table's level unit, or the dBV default when no store is wired. */
  _distortionUnit() {
    return this.prefs ? this.prefs.fftDistortionUnit.get() : 'DBV';
  }

  /** Java FftView.imdPctText - IMD-table percent cell; "---" when the figure is
   *  NaN (product outside the measurable range). */
  _imdPctText(pct) {
    return Number.isFinite(pct) ? `${pct.toFixed(8)} %` : '---';
  }

  /** Java FftView.imdRowText - IMD-table dnL/dnH cell (level in the selected unit +
   *  percent); "---" for a product whose frequency lies outside the measurable range at
   *  this sample rate. */
  _imdRowText(level, pct, unitLabel) {
    return Number.isFinite(level) ? `${level.toFixed(2)} ${unitLabel}  ${pct.toFixed(8)} %` : '     ---';
  }

  /** Java FftView.noiseDb - 10·log10(noisePower) + dbvOffset; NaN when noisePower<=0. */
  _noiseDb(r) {
    if (!(r.noisePower > 0)) return NaN;
    // Analyzed channel's ADC offset (Java FftView:2877 getDbvOffsetDb(getFftChannel())).
    return 10 * Math.log10(r.noisePower) + this.prefs.getDbvOffsetDb(this.prefs.fftChannel.get());
  }

  /** Java FftView.thdNPct - THD+N % from the N+D ratio in dB. */
  _thdNPct(r) {
    if (!Number.isFinite(r.thdNDb)) return NaN;
    return Math.pow(10, r.thdNDb / 20.0) * 100;
  }

  /** What the generator says it is EMITTING at this analyzer's rate - [tone1Hz, tone2Hz], 0.0
   *  for "this waveform emits no such tone", null when nobody answers (Java FftView asks
   *  GENERATOR_EMITTED_HZ instead of recomputing FftBinSnap from the same preferences: a
   *  drift readout derived from what we BELIEVE was commanded measures nothing). */
  _emittedHz(sampleRate) {
    return MessageBus.instance().request(Events.GENERATOR_EMITTED_HZ, sampleRate);
  }

  /** Java FftView.formatDeltaF - per-tone clock-drift line for the IMD table. */
  _formatDeltaF(label, expectedHz, measuredHz, sampleRate) {
    const delta = measuredHz - expectedHz;
    const ppm = expectedHz > 0 ? 1e6 * delta / expectedHz : NaN;
    const o = this._osc(sampleRate);
    if (!Number.isFinite(o.hz)) {
      return `${label}: ${this._signed(delta, 6)} Hz (${this._signed(ppm, 2)} ppm)`;
    }
    const oscDelta = o.hz * delta / expectedHz;
    return `${label}: ${this._signed(delta, 6)} Hz (${this._signed(ppm, 2)} ppm)  Δosc: ${this._signed(oscDelta, 2)} Hz @ ${o.name}`;
  }

  /** Master oscillator the sample-rate family is derived from (Java FftView). */
  _osc(sampleRate) {
    if (sampleRate > 0 && sampleRate % 44100 === 0) return { hz: 22.5792e6, name: '22.5792 MHz' };
    if (sampleRate > 0 && sampleRate % 48000 === 0) return { hz: 24.576e6, name: '24.576 MHz' };
    return { hz: NaN, name: '?' };
  }

  /** Java "%+.Nf" - always-signed fixed-point. */
  _signed(v, digits) {
    if (!Number.isFinite(v)) return 'NaN';
    return (v >= 0 ? '+' : '') + v.toFixed(digits);
  }

  /** Sets the preference-driven UI font + returns its char metrics (Java textExtent("M");
   *  the desktop draws these readouts in uiFontNormal/uiFontBold, not a hard-coded face). */
  _monoMetrics(g, bold) {
    const f = uiFont(bold);
    g.font = f.css;
    return { charW: g.measureText('M').width, lineH: Math.round(f.px + 2) };
  }

  /** Java FftView.drawCentred - text horizontally centred on centreX, top at y. */
  drawCentred(g, text, centreX, y, plain = false) {
    const w = g.measureText(text).width;
    this._outlined(g, text, centreX - w / 2, y, plain);
  }

  /** Java FftView.drawKv - two key/value pairs at independent key-column widths. */
  drawKv(g, x, y, lKeyW, lKey, lVal, rightColX, rKeyW, rKey, rVal, plain = false) {
    this._outlined(g, lKey, x, y, plain);
    this._outlined(g, lVal, x + lKeyW, y, plain);
    if (rKey != null && rKey !== '') {
      this._outlined(g, rKey, rightColX, y, plain);
      this._outlined(g, rVal, rightColX + rKeyW, y, plain);
    }
  }

  /** Java drawOutlinedText for a left-aligned table cell (top baseline).
   *  {@code plain} = the extracted tool-window paint: Java drawOutlinedText
   *  (AbstractMeasurementView.java:224-230) shadows in ColorRole.BACKGROUND then draws
   *  the text in fg - on the ToolWindow's white canvas (ToolWindow.java:77-78, BACKGROUND
   *  = 0xFFFFFF) that shadow is invisible, i.e. plain 0x202020 text on white with NO
   *  halo. The on-graph overlay (over the trace) keeps the web's white halo stroke. */
  _outlined(g, text, x, y, plain = false) {
    g.textAlign = 'left'; g.textBaseline = 'top';
    if (!plain) {
      g.lineJoin = 'round'; g.lineWidth = 3; g.strokeStyle = '#fff'; g.strokeText(text, x, y);
    }
    g.fillStyle = plain ? '#202020' : '#222'; g.fillText(text, x, y);
  }

  /** Java FftView.drawDistortionTable - single-tone THD/harmonics table.
   *  {@code plain} - extracted tool-window mode: no halo stroke (see _outlined). */
  drawDistortionTable(g, r, unit, xLeft, yTop, plain = false) {
    const p = this.prefs;
    let m = this._monoMetrics(g, false);
    const charW = m.charW, lineH = m.lineH;
    let y = yTop;
    const tableW = 64 * charW, centreX = xLeft + tableW / 2;

    const thdMaxH = p.fftThdMaxHarmonic.get();
    // Analyzed channel's ADC offset (Java FftView:2807 getDbvOffsetDb(getFftChannel())).
    const dbvOff = p.getDbvOffsetDb(p.fftChannel.get());
    // dBV header column (Java drawDistortionTable :2627): the manual fundamental
    // (fundamentalTrueDbFs) when set, else the measured fundamental, both lifted by
    // the analyzed channel's ADC offset. _manualFundDbFs re-gates on the live manual on/off pref.
    const man = this._manualFundDbFs(r);
    const fundDbV = (Number.isFinite(man) ? man : r.fundamentalDbFs) + dbvOff;
    this._monoMetrics(g, true);
    this.drawCentred(g, `${r.fundamentalHzRefined.toFixed(4)} Hz   ${r.fundamentalDbFs.toFixed(2)} dBFS   ${fundDbV.toFixed(2)} dBV`, centreX, y, plain);
    y += lineH;
    this.drawCentred(g, this._formatSpan(r), centreX, y, plain);
    y += lineH;
    // Clock-drift ΔF / Δosc row - only when the generator is running AND
    // fund-from-generator is on (the row is meaningless otherwise).
    if (this._genActive() && p.fftFundFromGenerator.get()) {
      // No responder -> no expectation -> no row: 0 and "nobody answered" behave identically.
      const emitted = this._emittedHz(r.sampleRate);
      const expected = emitted == null ? 0 : emitted[0];
      if (expected > 0 && Number.isFinite(r.fundamentalHzRefined)) {
        const delta = r.fundamentalHzRefined - expected, ppm = 1e6 * delta / expected;
        const o = this._osc(r.sampleRate);
        const clock = !Number.isFinite(o.hz)
          ? `ΔF: ${this._signed(delta, 6)} Hz (${this._signed(ppm, 2)} ppm)`
          : `ΔF: ${this._signed(delta, 6)} Hz (${this._signed(ppm, 2)} ppm)  Δosc: ${this._signed(o.hz * delta / expected, 2)} Hz @ ${o.name}`;
        this.drawCentred(g, clock, centreX, y, plain);
        y += lineH;
      }
    }
    this._monoMetrics(g, false);
    y += 2;

    // ── Metric rows: N+D / THD, N / THD+N, SNR / ENOB. ──────────────────────
    const colGap = 2 * charW;
    const mKeyL = 7 * charW, mValL = 14 * charW, mKeyR = 12 * charW;
    const mRightColX = xLeft + mKeyL + mValL + colGap;
    this.drawKv(g, xLeft, y, mKeyL, 'N+D:', `${this._fmtDb(r.thdNDb)} A`,
        mRightColX, mKeyR, `THD H2..${Math.round(thdMaxH)}:`, `${r.thdPct.toFixed(8)} %`, plain);
    y += lineH;
    this.drawKv(g, xLeft, y, mKeyL, 'N:', this._fmtDb(this._noiseDb(r)),
        mRightColX, mKeyR, 'THD+N:', `${this._thdNPct(r).toFixed(8)} %`, plain);
    y += lineH;
    this.drawKv(g, xLeft, y, mKeyL, 'SNR:', this._fmtDb(r.snrDb),
        mRightColX, mKeyR, 'ENOB:', `${((r.sinadDb - 1.76) / 6.02).toFixed(1)} bits`, plain);
    y += lineH + 2;

    // ── Harmonics, 2 per row. The % column is r.harmonicPct[i] VERBATIM
    // (referenced to the analyzer's fundamental - NOT recomputed here). ──────
    // Unit of the absolute-level cells, and the reference the relative unit measures
    // against: the fundamental this table already reports in its header (manual override
    // when set), which is the very level the analyzer divides by for the percentages.
    const tableUnit = this._distortionUnit();
    const unitLabel = this._distortionUnitLabel(tableUnit);
    const refDbFs = fundDbV - dbvOff;
    // The unit sits inside the value, so the column follows the label's width - a wider
    // "dBFS" would otherwise push the value into the right-hand key.
    const hKey = 4 * charW, hVal = (21 + unitLabel.length) * charW;
    const hRightColX = xLeft + hKey + hVal + colGap;
    const harmCount = r.harmonicDbFs == null ? 0 : r.harmonicDbFs.length;
    for (let i = 0; i < harmCount; i += 2) {
      const l = `H${i + 2}:`;
      const lLevel = this._levelInDistortionUnit(r.harmonicDbFs[i], refDbFs, tableUnit, dbvOff);
      const lv = `${lLevel.toFixed(2)} ${unitLabel} ${r.harmonicPct[i].toFixed(8)} %`;
      let rkey = '', rval = '';
      if (i + 1 < harmCount) {
        rkey = `H${i + 3}:`;
        const rLevel = this._levelInDistortionUnit(r.harmonicDbFs[i + 1], refDbFs, tableUnit, dbvOff);
        rval = `${rLevel.toFixed(2)} ${unitLabel}  ${r.harmonicPct[i + 1].toFixed(8)} %`;
      }
      this.drawKv(g, xLeft, y, hKey, l, lv, hRightColX, hKey, rkey, rval, plain);
      y += lineH;
    }
    return y;
  }

  /** Java FftView.drawImdTable - dual-tone IMD measurement table.
   *  {@code plain} - extracted tool-window mode: no halo stroke (see _outlined). */
  drawImdTable(g, imd, r, xLeft, yTop, plain = false) {
    const p = this.prefs;
    let m = this._monoMetrics(g, false);
    const charW = m.charW, lineH = m.lineH;
    let y = yTop;
    const tableW = 64 * charW, centreX = xLeft + tableW / 2;

    // ── Centred F1 / F2 headers (bold). ─────────────────────────────────────
    this._monoMetrics(g, true);
    this.drawCentred(g, `F1: ${imd.f1Hz.toFixed(4)} Hz   ${imd.f1DbFs.toFixed(2)} dBFS   ${imd.f1DbV.toFixed(2)} dBV`, centreX, y, plain);
    y += lineH;
    this.drawCentred(g, `F2: ${imd.f2Hz.toFixed(4)} Hz   ${imd.f2DbFs.toFixed(2)} dBFS   ${imd.f2DbV.toFixed(2)} dBV`, centreX, y, plain);
    y += lineH;
    this.drawCentred(g, this._formatSpan(r), centreX, y, plain);
    y += lineH;
    // Δf1 / Δf2 per-tone clock-drift - same gate as the THD ΔF row.
    if (this._genActive() && p.fftFundFromGenerator.get()) {
      const sr = r.sampleRate;
      const emitted = this._emittedHz(sr);
      if (emitted != null) {   // no answer -> the two rows are not drawn at all
        this.drawCentred(g, this._formatDeltaF('Δf1', emitted[0], imd.f1Hz, sr), centreX, y, plain);
        y += lineH;
        this.drawCentred(g, this._formatDeltaF('Δf2', emitted[1], imd.f2Hz, sr), centreX, y, plain);
        y += lineH;
      }
    }
    this._monoMetrics(g, false);
    y += 2;

    // ── IMDpwr / TD+N, DFD2 / DFD3. ─────────────────────────────────────────
    const colGap = 2 * charW;
    const mKeyL = 8 * charW, mValL = 14 * charW, mKeyR = 7 * charW;
    const mRight = xLeft + mKeyL + mValL + colGap;
    this.drawKv(g, xLeft, y, mKeyL, 'IMDpwr:', this._imdPctText(imd.imdPwrPct),
        mRight, mKeyR, 'TD+N:', this._imdPctText(imd.tdnPct), plain);
    y += lineH;
    this.drawKv(g, xLeft, y, mKeyL, 'DFD2:', this._imdPctText(imd.dfd2Pct),
        mRight, mKeyR, 'DFD3:', this._imdPctText(imd.dfd3Pct), plain);
    y += lineH + 2;

    // ── dnL / dnH sidebands, two per row. ───────────────────────────────────
    // The products are stored in dBV; drop the ADC offset to get the measured dBFS the unit
    // selector works from. The relative unit measures against |F1| + |F2|, the divisor
    // imd-analyzer uses for every product percentage, floored like it is there so muted
    // tones cannot divide by zero.
    const dbvOff = p.getDbvOffsetDb(p.fftChannel.get());
    const tableUnit = this._distortionUnit();
    const unitLabel = this._distortionUnitLabel(tableUnit);
    const refDbFs = 20.0 * Math.log10(Math.max(MIN_REF_VRMS, imd.f1Mag + imd.f2Mag)) - dbvOff;
    // " -108.42 dBV  0.00045123 %" - follows the label width, as the THD table's harmonic
    // column does.
    const dKey = 5 * charW, dVal = (23 + unitLabel.length) * charW;
    const dRight = xLeft + dKey + dVal + colGap;
    for (let k = 2; k <= IMD_MAX_ORDER; k++) {
      const lKey = `d${k}L:`;
      const lVal = this._imdRowText(
        this._levelInDistortionUnit(imd.dnLDbV[k] - dbvOff, refDbFs, tableUnit, dbvOff),
        imd.dnLPct[k], unitLabel);
      const rKey = `d${k}H:`;
      const rVal = this._imdRowText(
        this._levelInDistortionUnit(imd.dnHDbV[k] - dbvOff, refDbFs, tableUnit, dbvOff),
        imd.dnHPct[k], unitLabel);
      this.drawKv(g, xLeft, y, dKey, lKey, lVal, dRight, dKey, rKey, rVal, plain);
      y += lineH;
    }
    return y;
  }

  /** Java FftView.paintDistortion (:2975) - the ToolWindow.ContentPainter for the
   *  EXTRACTED distortion window: draws the SAME THD or IMD table straight into the
   *  given 2D context, inset from its top-left by EXT_LEFT_PAD so the keys don't
   *  touch the border (top is 0 here - no button row; the toggles stay in the main
   *  FFT view). The pane owns the float-window canvas + its show/hide
   *  toggle; it calls this to paint the table into that canvas, and inline rendering
   *  keeps using drawDistortionTable / drawImdTable directly. No-op with no result. */
  paintDistortionInto(g, top = 0) {
    const r = this._last;
    if (!r) return;
    const imd = r.imd;
    const y = top + EXT_LEFT_PAD;
    if (imd) {
      this.drawImdTable(g, imd, r, EXT_LEFT_PAD, y, true);
    } else {
      const unit = this.prefs ? this.prefs.fftMagUnit.get() : 'DBFS';
      this.drawDistortionTable(g, r, unit, EXT_LEFT_PAD, y, true);
    }
  }

  /** Java FftView.computeExternalContentSize (:2916) - the natural client size of
   *  the extracted table (width from the widest row, height from the fixed +
   *  dynamic harmonic / sideband rows) so the float window fits its content with no
   *  excess whitespace or clipping. Needs a measuring context with the mono font.
   *  Returns {@code {width, height}} in CSS px, or null with no result. */
  distortionContentSize(g) {
    const r = this._last;
    if (!r) return null;
    const m = this._monoMetrics(g, false);
    const charW = m.charW, lineH = m.lineH;   // Java textExtent("M").y + 1 -> fixed 14 here
    const p = this.prefs;
    // The value columns follow the unit label's width (drawDistortionTable / drawImdTable),
    // so the natural width does too - a wider "dBFS" must not clip the trailing %.
    const unitLabel = this._distortionUnitLabel(this._distortionUnit());
    if (r.imd) {
      // Java: rows = F1/F2 + span + optional Δf1/Δf2 + 2 metric + (MAX_ORDER−1) dnL/dnH.
      const worstVal = `${(-9999.99).toFixed(2)} ${unitLabel} ${(99.99999999).toFixed(8)} %`;
      const contentW = EXT_LEFT_PAD + (35 + unitLabel.length) * charW + g.measureText(worstVal).width + 34;
      const clk = this._genActive() && p && p.fftFundFromGenerator.get();
      const rows = 2 + 1 + (clk ? 2 : 0) + 2 + (IMD_MAX_ORDER - 1);
      const contentH = EXT_LEFT_PAD + rows * lineH + 8;
      return { width: Math.ceil(contentW), height: Math.ceil(contentH) };
    }
    // THD: measure the worst-case harmonic row (Java :2946 - measuring the whole row
    // removes the mono "M"-cell under-count that clipped the trailing %).
    const widestRow = `H10: ${this._signed(-9999.99, 2)} ${unitLabel} ${(99.99999999).toFixed(8)} %  `
      + `H11: ${this._signed(-9999.99, 2)} ${unitLabel} ${(99.99999999).toFixed(8)} %`;
    const rowW = g.measureText(widestRow).width;
    const contentW = EXT_LEFT_PAD + rowW + 32;
    const maxH = Math.max(9, p ? p.fftCalcMaxHarmonic.get() : 9);
    const harmRows = Math.trunc(((maxH - 1) + 1) / 2);
    const clockRow = (p && p.fftFundFromGenerator.get()) ? lineH : 0;
    const contentH = EXT_LEFT_PAD + lineH + lineH + clockRow + 2 + 3 * lineH + 2 + harmRows * lineH + 4;
    return { width: Math.ceil(contentW), height: Math.ceil(contentH) };
  }
}
