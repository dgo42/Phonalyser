/*
 * Phonalyser web — the interactive Frequency-Response VIEW (trace canvas · log-freq
 * X / linear-nice dB left-Y / ±180° phase right-Y axes · wheel zoom+pan · crosshair
 * readout · two navigation FlatScrollbars · render-time calibration).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of gui/freqresp/FreqRespView (+ FreqRespFormat). Holds the RAW L/R
 * result slots and a CALIBRATED display copy derived from them at RENDER time —
 * applyCurrentCalibration() clones the arrays and divides by the correction store's
 * entries + the wizard `direct` buffer (skipping a loaded .frc that already baked the
 * division in) and optionally interpolates a mains notch across each harmonic band, so
 * swapping a calibration retraces WITHOUT re-sweeping. Mouse-wheel zoom/pan
 * (Ctrl+Shift = freq zoom, Ctrl = mag zoom, Shift = freq pan, plain = mag pan) and the
 * two scrollbars persist freqRespFreqMinHz/MaxHz/MagTopDb/MagBotDb and fire a
 * range-changed callback so the host can re-sync. Colours / line width / Nyquist
 * fraction come from the freqResp* prefs (the old static plot hardcoded them).
 */

import { FlatScrollbar } from '../widgets/flat-scrollbar.js';
import { interpolate } from './deconvolve.js';
import { evalDb } from '../dsp/riaa.js';
// Lanczos (windowed-sinc) trace reconstruction — one band-limited sample per
// pixel column instead of joining the data points with straight segments, so a
// sparse span (e.g. the few bins across a deep narrow notch null) renders as the
// smooth ROUNDED dip the underlying response actually is, not a straight-segment
// V. Faithful port of FreqRespView's LANCZOS_TRACES path (uses the NaN-aware
// double[] overload — invalid points are treated as missing, not gaps).
import { lanczosNaN, LANCZOS_A, MAX_LANCZOS_DOWNSAMPLE } from '../dsp/lanczos.js';
// Drag-select rectangular zoom + Ctrl+Z undo (Java AbstractMeasurementView's
// installRectZoom base machinery); this view supplies the log-freq / dB
// pixel↔value mappings + clamps (Java FreqRespView zoom overrides).
import { RectZoom } from '../ui/rect-zoom.js';
// Shared axis tick generation + label formatters (Java AbstractMeasurementView) — the adaptive
// log / sub-decade frequency ticks + the fine crosshair frequency readout.
import {
  isSubDecade, isDecadeValue, minSpacing,
  logMajorTicks, logMinorTicks, adaptiveLogLabels,
  niceLinearMajors, subDecadeMinors,
  formatFrequency, formatFreqTick, formatFrequencyFine,
} from '../ui/axis-format.js';

// ----- packed-int colour → CSS hex (matches the fft-view helper) -----
const colorHex = (c) => '#' + (c & 0xffffff).toString(16).padStart(6, '0');

// ----- plot geometry (mirror FreqRespView margins) -----
const MARGIN_LEFT = 56;
const MARGIN_TOP = 4;
const MARGIN_BOTTOM = 18;
const MARGIN_RIGHT_NO_PHASE = 6;
const MARGIN_RIGHT_PHASE = 52;
// Axis tick length (Java AbstractMeasurementView.MAJOR_TICK_LEN) — used to inset the
// left-Y unit caption from the plot's left edge, mirroring drawAxisCaptions.
const MAJOR_TICK_LEN = 6;
// Sub-decade X-axis nice-linear tick target (Java AbstractMeasurementView.SUB_DECADE_TICK_TARGET).
const SUB_DECADE_TICK_TARGET = 12;

// ----- Lanczos trace smoothing (FreqRespView.LANCZOS_TRACES path) -----
/** Master on/off for sinc (Lanczos) trace smoothing. Off = linear segments. */
const LANCZOS_TRACES = true;
/** Linear-amplitude floor that keeps a Lanczos overshoot from driving the
 *  reconstructed magnitude negative (→ log10 of a negative number). */
const LANCZOS_MAG_FLOOR_LIN = 1e-15;

// ----- zoom / pan factors + window limits (FreqRespView constants) -----
const FREQ_ZOOM_FACTOR = 1.25;
const MAG_ZOOM_FACTOR = 1.25;
const FREQ_PAN_FRAC = 0.10;
const MAG_PAN_FRAC = 0.10;
const MAG_TOP_MAX_DB = 20.0;
const MAG_HEADROOM_DB = 20.0;
const MAG_TOP_ZOOM_MAX_DB = 120.0;
const MAG_BOT_MIN_DB = -300.0;
const MAG_DEFAULT_BOT_DB = -150.0;
const FREQ_MIN_FLOOR_HZ = 1.0;

// ----- navigation scrollbars: fixed integer range, value derived via bounds -----
const NAV_RANGE = 1_000_000;

export class FreqRespView {
  /**
   * @param {HTMLCanvasElement} canvas  the #frPlot canvas
   * @param {import('../store/preferences.js').Preferences} prefs Preferences.instance()
   * @param {import('./correction-store.js').FreqRespCorrectionStore} correctionStore loaded .frc store
   * @param {{freqScroll?:HTMLCanvasElement, magScroll?:HTMLCanvasElement,
   *          onRangeChanged?:Function}} deps  (callers may also pass an unused
   *          {@code engine} key — the view now reads the input rate from prefs,
   *          mirroring the desktop, so it no longer stores the engine)
   */
  constructor(canvas, prefs, correctionStore, deps = {}) {
    this.cv = canvas;
    this.g = canvas.getContext('2d');
    this.prefs = prefs;
    this.correctionStore = correctionStore;
    this._onRangeChanged = deps.onRangeChanged || null;

    // RAW captured/loaded results (before calibration) and the display copies with the
    // currently-loaded calibration divided in (Java rawLeftResult / leftResult).
    this.rawLeftResult = null;
    this.rawRightResult = null;
    this.leftResult = null;
    this.rightResult = null;
    this.sourceFilePath = null;

    // Sample rate of the most recent result; clips the crosshair readout at
    // nyquistFraction × sampleRate (falls back to the input-rate pref when no
    // result is loaded — see _inputSampleRate).
    this.lastResultSampleRate = 0;

    // Crosshair cursor state.
    this._mouseX = -1;
    this._mouseY = -1;
    this._mouseInPlot = false;

    // Compare-mode state (FreqRespView CompareDiff + the public min/max scalars). The
    // smoothed (measDb − refDb) array is cached by the (src, reverse, iec, W) tuple and
    // read by drawCompareTrace, the min/max table, and the crosshair Δ readout — so all
    // three agree by construction (Java getCompareDiff). compareSmoothedMin/Max mirror
    // CompareDiff.minDb/maxDb for the table; NaN until the first recomputeCompareAnchor.
    this.compareSmoothedMin = NaN;
    this.compareSmoothedMax = NaN;
    this._compareDiffCache = null;
    this._compareDiffCacheSrc = null;
    this._compareDiffCacheReverse = false;
    this._compareDiffCacheIec = false;
    this._compareDiffCacheWindow = -1;

    canvas.addEventListener('wheel', (e) => this._onWheel(e), { passive: false });
    canvas.addEventListener('mousemove', (e) => this._onMouseMove(e));
    canvas.addEventListener('mouseleave', () => { this._mouseInPlot = false; this.render(); });
    // Drag-select zoom + Ctrl+Z undo (Java FreqRespView: installRectZoom(this, true)
    // — hookMouse, so the machinery wires the drag to the canvas's own mouse events).
    this._rectZoom = new RectZoom(canvas, {
      captureState: () => this._captureZoomState(),
      applyState: (s) => this._applyZoomState(s),
      stateForRect: (sel) => this._zoomStateForRect(sel),
      zoomableArea: () => this._plotRect(),
      repaintOverlay: () => this.render(),
    });

    // Two navigation FlatScrollbars (optional — wired only when the host provides the
    // gutter canvases): freq (log) horizontal + mag (linear) vertical.
    this.freqScroll = deps.freqScroll
      ? new FlatScrollbar(deps.freqScroll, { vertical: false, onChange: (s) => this._onFreqScroll(s) })
      : null;
    this.magScroll = deps.magScroll
      ? new FlatScrollbar(deps.magScroll, { vertical: true, onChange: (s) => this._onMagScroll(s) })
      : null;
    if (this.freqScroll) { this.freqScroll.setMinimum(0); this.freqScroll.setMaximum(NAV_RANGE); }
    if (this.magScroll) { this.magScroll.setMinimum(0); this.magScroll.setMaximum(NAV_RANGE); }
  }

  // ===========================================================================
  // Public API — host pushes results / selection here
  // ===========================================================================

  /** Replaces the left-channel RAW result and re-derives its calibrated copy. */
  setLeftResult(result) {
    this.rawLeftResult = result;
    this.leftResult = this.applyCurrentCalibration(result);
    if (result) this.lastResultSampleRate = result.sampleRate;
    this.render();
  }

  /** Replaces the right-channel RAW result and re-derives its calibrated copy. */
  setRightResult(result) {
    this.rawRightResult = result;
    this.rightResult = this.applyCurrentCalibration(result);
    if (result) this.lastResultSampleRate = result.sampleRate;
    this.render();
  }

  /** Pushes a whole stereo pair (one sweep) and repaints once. */
  setStereoResult(stereo) {
    this.rawLeftResult = stereo ? stereo.left : null;
    this.rawRightResult = stereo ? stereo.right : null;
    this.leftResult = this.applyCurrentCalibration(this.rawLeftResult);
    this.rightResult = this.applyCurrentCalibration(this.rawRightResult);
    const r = this.rawLeftResult || this.rawRightResult;
    if (r) this.lastResultSampleRate = r.sampleRate;
    this.render();
  }

  /** Re-derives the display copies from the raw results using the calibration
   *  currently active in the store (load / clear / wizard-apply → retrace without a
   *  re-sweep). Mirrors FreqRespView.onCalibrationChanged — also refreshes the compare
   *  anchor + min/max table (but never re-zooms; only autoSetupCompare may re-zoom). */
  onCalibrationChanged() {
    if (this.rawLeftResult) this.leftResult = this.applyCurrentCalibration(this.rawLeftResult);
    if (this.rawRightResult) this.rightResult = this.applyCurrentCalibration(this.rawRightResult);
    const prefs = this.prefs;
    if (prefs.freqRespCompareMode.get() && prefs.freqRespShowRiaa.get() && this.hasAnyResult()) {
      this._recomputeCompareAnchor();
    }
    this.render();
  }

  /** Refreshes the compare anchor + min/max table after a compare param (the smoothing
   *  window pref) changes. No-op when compare / RIAA is off or no result is loaded; always
   *  redraws (FreqRespView.onCompareParamsChanged). */
  onCompareParamsChanged() {
    const prefs = this.prefs;
    if (prefs.freqRespCompareMode.get() && prefs.freqRespShowRiaa.get() && this.hasAnyResult()) {
      this._recomputeCompareAnchor();
    }
    this.render();
  }

  /** Clears both result slots (raw and display). Mirrors FreqRespView.clearResults, which
   *  leaves compareSmoothedMin/Max intact (carry-over) so the compare min/max table keeps its
   *  numbers across a re-sweep — they are overwritten by the next recomputeCompareAnchor. */
  clearResults() {
    this.rawLeftResult = this.rawRightResult = null;
    this.leftResult = this.rightResult = null;
    this.render();
  }

  hasAnyResult() { return this.leftResult != null || this.rightResult != null; }

  /** Read-only accessors used by the Save-to handler — the current calibrated/displayed L/R
   *  result, or null (faithful port of FreqRespView.getLeftResultOrNull / getRightResultOrNull,
   *  which return the `leftResult` / `rightResult` fields). */
  getLeftResultOrNull() { return this.leftResult || null; }
  getRightResultOrNull() { return this.rightResult || null; }

  // ===========================================================================
  // Render-time calibration (FreqRespView.applyCurrentCalibration)
  // ===========================================================================

  /** Returns a CALIBRATED copy of {@code raw}: clones the magnitude/phase arrays and
   *  divides them by every loaded calibration entry + the wizard `direct` buffer (in
   *  order), then optionally interpolates a mains notch across each harmonic band.
   *  A loaded .frc already carries the division (calibrationApplied) → returned
   *  unchanged so it isn't double-corrected. Mirrors FreqRespView.applyCurrentCalibration. */
  applyCurrentCalibration(raw) {
    if (!raw) return null;
    if (raw.calibrationApplied) return raw;
    const prefs = this.prefs;
    const entries = this.correctionStore.getEntries();
    const direct = this.correctionStore.getDirect();
    const wantCal = prefs.freqRespApplyCalibration.get() && (entries.length > 0 || direct != null);
    const wantNotch = prefs.freqRespNotchEnabled.get();
    if (!wantCal && !wantNotch) return raw;

    const freqs = raw.freqs;
    const outMag = raw.magLin.slice();
    const outPhase = raw.phaseRad.slice();
    const rChan = raw.channel === 'R';

    if (wantCal) {
      for (const entry of entries) {
        this._divideByStereoCal(entry.calibration, rChan, freqs, outMag, outPhase);
      }
      if (direct != null) {
        this._divideByStereoCal(direct, rChan, freqs, outMag, outPhase);
      }
    }
    if (wantNotch) {
      // Half-width scales with the deconvolution FFT length so the notch spans a fixed
      // number of bin-widths regardless of sweep duration / sample rate (FreqRespView:
      // halfWidth = 1.2 · sampleRate / fftSize).
      const fftSize = this._deconvFftSize(raw);
      const halfWidthHz = 1.2 * raw.sampleRate / fftSize;
      this._applyMainsNotches(freqs, outMag, outPhase,
        prefs.freqRespNotchBaseHz.get(), raw.sampleRate, halfWidthHz);
    }
    return {
      channel: raw.channel, sampleRate: raw.sampleRate,
      freqs, magLin: outMag, phaseRad: outPhase,
      sweepParams: raw.sweepParams, sourceFilePath: raw.sourceFilePath,
      calibrationApplied: true,
    };
  }

  /** Divides outMag/outPhase in place by the channel-appropriate side of the stereo
   *  calibration, log-frequency-interpolated onto the result grid. */
  _divideByStereoCal(stereo, rChan, freqs, outMag, outPhase) {
    const cal = rChan ? stereo.right : stereo.left;
    if (!cal) return;
    for (let i = 0; i < freqs.length; i++) {
      const [calMag, calPhi] = interpolate(cal, freqs[i]);
      outMag[i] = calMag > 0.0 ? outMag[i] / calMag : 0.0;
      outPhase[i] = outPhase[i] - calPhi;
    }
  }

  /** Recovers the FFT length the deconvolution used so the notch half-width scales
   *  with it: nextPow2(leadIn + sweep + ½-s tail), falling back to nextPow2(2·sr).
   *  Mirrors FreqRespView.deconvFftSize. */
  _deconvFftSize(raw) {
    const sr = raw.sampleRate;
    const p = raw.sweepParams;
    if (!p || !(p.durationSec > 0.0)) return nextPow2(Math.max(1, 2 * sr));
    const total = Math.round((p.leadInSec || 0) * sr)
      + Math.round(p.durationSec * sr)
      + Math.trunc(sr / 2);
    if (total <= 0) return 1 << 17;
    return nextPow2(total);
  }

  /** Walks the harmonics of baseHz up to Nyquist and interpolates the magnitude /
   *  phase across each ±halfWidthHz band (FreqRespView.applyMainsNotches). */
  _applyMainsNotches(freqs, outMag, outPhase, baseHz, sampleRate, halfWidthHz) {
    if (baseHz <= 0 || !freqs || freqs.length < 2 || halfWidthHz <= 0.0) return;
    const nyq = sampleRate * 0.5;
    for (let h = baseHz; h < nyq; h += baseHz) {
      this._interpolateAcrossBand(freqs, outMag, outPhase, h - halfWidthHz, h + halfWidthHz);
    }
  }

  /** Replaces every point in [fLo, fHi] with a linear (in frequency) interpolation of
   *  the magnitude / phase from the two flanking points just outside the band.
   *  No-op when no flanking neighbour exists (FreqRespView.interpolateAcrossBand). */
  _interpolateAcrossBand(freqs, outMag, outPhase, fLo, fHi) {
    let loIdx = -1;
    for (let i = 0; i < freqs.length; i++) {
      if (freqs[i] < fLo) loIdx = i; else break;
    }
    let hiIdx = -1;
    for (let i = freqs.length - 1; i >= 0; i--) {
      if (freqs[i] > fHi) hiIdx = i; else break;
    }
    if (loIdx < 0 || hiIdx < 0 || hiIdx <= loIdx + 1) return;
    const f0 = freqs[loIdx], f1 = freqs[hiIdx];
    if (f1 === f0) return;
    const m0 = outMag[loIdx], m1 = outMag[hiIdx];
    const p0 = outPhase[loIdx], p1 = outPhase[hiIdx];
    for (let i = loIdx + 1; i < hiIdx; i++) {
      const t = (freqs[i] - f0) / (f1 - f0);
      outMag[i] = m0 + t * (m1 - m0);
      outPhase[i] = p0 + t * (p1 - p0);
    }
  }

  // ===========================================================================
  // Coordinate transforms (FreqRespFormat)
  // ===========================================================================

  _freqToXFraction(f, freqMin, freqMax) {
    if (f <= 0 || freqMin <= 0 || freqMax <= 0 || freqMax <= freqMin) return 0.0;
    return (Math.log10(f) - Math.log10(freqMin)) / (Math.log10(freqMax) - Math.log10(freqMin));
  }

  _xFractionToFreq(frac, freqMin, freqMax) {
    if (freqMin <= 0 || freqMax <= 0 || freqMax <= freqMin) return freqMin;
    const logMin = Math.log10(freqMin), logMax = Math.log10(freqMax);
    return Math.pow(10, logMin + frac * (logMax - logMin));
  }

  /** Maximal analysed frequency: the active backend Nyquist scaled by the
   *  freqRespNyquistFraction pref (FreqRespView.nyquistHz). */
  _nyquistHz() {
    const sr = this.lastResultSampleRate > 0 ? this.lastResultSampleRate : this._inputSampleRate();
    let frac = this.prefs.freqRespNyquistFraction.get();
    if (!Number.isFinite(frac) || frac <= 0.0) frac = 1.0;
    return Math.max(1.0, sr * 0.5 * frac);
  }

  /** Fallback sample rate for the Nyquist ceiling / crosshair clip when no result is
   *  loaded yet — the user's REQUESTED input-rate pref (Java nyquistHz + drawCrosshair
   *  both read {@code prefs.current().getInputSampleRate()}). Must NOT read
   *  {@code engine.config.inRate}: SharedCapture re-pins that to the rate Web Audio
   *  actually negotiated (often the OS-capped 48 kHz), which would shrink the zoom-out
   *  ceiling below the rate the user set — diverging from the desktop, which clamps to
   *  the requested rate. */
  _inputSampleRate() {
    return this.prefs.current().inputSampleRate || 48000;
  }

  // ===========================================================================
  // Paint
  // ===========================================================================

  render() {
    const g = this.g, cv = this.cv, prefs = this.prefs;
    const W = cv.clientWidth || 1200, H = cv.clientHeight || 440;
    // HiDPI backing store (mirror of FftView.render #27): backing px = CSS px ×
    // devicePixelRatio, then setTransform(dpr,…) so 1 CSS px == dpr device px and the text /
    // grid / traces stay crisp at native resolution instead of being drawn at CSS size then
    // upscaled by the browser (the blur vs the FFT view). All drawing below is in CSS-px
    // (W, H) coordinates.
    const dpr = (typeof window !== 'undefined' && window.devicePixelRatio) || 1;
    const bw = Math.round(W * dpr), bh = Math.round(H * dpr);
    if (cv.width !== bw) cv.width = bw;
    if (cv.height !== bh) cv.height = bh;
    if (g.setTransform) g.setTransform(dpr, 0, 0, dpr, 0, 0);

    const phaseVisible = prefs.freqRespPhaseVisible.get();
    const rightMargin = phaseVisible ? MARGIN_RIGHT_PHASE : MARGIN_RIGHT_NO_PHASE;
    const plot = {
      x: MARGIN_LEFT, y: MARGIN_TOP,
      w: Math.max(1, W - MARGIN_LEFT - rightMargin),
      h: Math.max(1, H - MARGIN_TOP - MARGIN_BOTTOM),
    };

    let freqMin = prefs.freqRespFreqMinHz.get();
    let freqMax = prefs.freqRespFreqMaxHz.get();
    if (freqMin <= 0) freqMin = 1.0;
    const magTop = prefs.freqRespMagTopDb.get();
    const magBot = prefs.freqRespMagBotDb.get();

    g.fillStyle = colorHex(prefs.freqRespBackgroundColor.get());
    g.fillRect(0, 0, W, H);

    this._drawGrid(g, plot, freqMin, freqMax, magTop, magBot, phaseVisible);
    // Compare mode draws the (measured − reference) subtraction curve instead of the
    // raw traces + RIAA overlay (FreqRespView.onPaint branch).
    const compareActive = prefs.freqRespCompareMode.get() && this.hasAnyResult() && prefs.freqRespShowRiaa.get();
    // Clip the DATA drawing to the plot rect (Java setClipping(plot)) so a trace / RIAA curve
    // that runs off the top or bottom of the dB range is cut at the frame (keeping its true
    // slope) instead of spilling over the border or flattening against the edge. Grid, axis
    // labels and the crosshair readout draw OUTSIDE this clip.
    g.save();
    g.beginPath();
    g.rect(plot.x, plot.y, plot.w, plot.h);
    g.clip();
    if (compareActive) {
      this._drawCompareTrace(g, plot, freqMin, freqMax, magTop, magBot);
    } else {
      this._drawTraces(g, plot, freqMin, freqMax, magTop, magBot, phaseVisible);
      if (prefs.freqRespShowRiaa.get()) {
        this._drawRiaaOverlay(g, plot, freqMin, freqMax, magTop, magBot);
      }
    }
    g.restore();
    // The compare min/max table is an overlay (not trace data) — drawn unclipped, as before.
    if (compareActive) this._drawCompareMeasurementTable(g, plot);
    if (this._mouseInPlot) {
      this._drawCrosshair(g, plot, freqMin, freqMax, magTop, magBot, phaseVisible);
    }
    // Rect-zoom rubber band + focused-view accent border — LAST, over the whole
    // canvas (Java onPaint ends with drawRectZoomOverlay(gc, canvas.width, canvas.height)).
    if (this._rectZoom) this._rectZoom.drawOverlay(g, W, H);
  }

  // ----- grid + axes (AxisSpec.log freq / linearNice dB / linear ±180° phase) -----
  _drawGrid(g, plot, freqMin, freqMax, magTop, magBot, phaseVisible) {
    const xOf = (f) => plot.x + this._freqToXFraction(f, freqMin, freqMax) * plot.w;
    const yOf = (d) => plot.y + (magTop - d) / (magTop - magBot) * plot.h;
    g.font = '11px "Segoe UI", sans-serif';
    g.textBaseline = 'middle';
    g.lineWidth = 1;

    // dB gridlines on a "nice" step (linearNice: ~10 divisions, multiple of 5 dB).
    const dbStep = niceDbStep(magTop - magBot, 10, 5.0);
    g.strokeStyle = '#e6e6e6'; g.fillStyle = '#333';
    for (let d = Math.ceil(magBot / dbStep) * dbStep; d <= magTop; d += dbStep) {
      const yy = yOf(d);
      g.beginPath(); g.moveTo(plot.x, yy); g.lineTo(plot.x + plot.w, yy); g.stroke();
      g.textAlign = 'right'; g.fillText(formatDbBare(d), plot.x - 6, yy);
    }

    // Frequency gridlines + labels (Java AbstractMeasurementView majorTicks/minorTicks +
    // drawGrid). Wide log (≥1 decade): 1..9×10ⁿ grid with adaptiveLogLabels decade-thinning.
    // Sub-decade zoom: nice-linear majors/minors + step-aware Hz labels, so a ~9 Hz window
    // shows 998…1007 Hz instead of a lone "1 kHz".
    g.textAlign = 'center'; g.textBaseline = 'top';
    const wideLog = !isSubDecade(freqMin, freqMax);
    const xMajors = wideLog ? logMajorTicks(freqMin, freqMax)
                            : niceLinearMajors(freqMin, freqMax, SUB_DECADE_TICK_TARGET);
    const xMinors = wideLog ? logMinorTicks(freqMin, freqMax)
                            : subDecadeMinors(freqMin, freqMax);
    for (const f of xMinors) {
      if (f < freqMin || f > freqMax) continue;
      const xx = xOf(f);
      g.strokeStyle = '#eee';
      g.beginPath(); g.moveTo(xx, plot.y); g.lineTo(xx, plot.y + plot.h); g.stroke();
    }
    for (const f of xMajors) {
      if (f < freqMin || f > freqMax) continue;
      const xx = xOf(f);
      g.strokeStyle = '#cfcfcf';
      g.beginPath(); g.moveTo(xx, plot.y); g.lineTo(xx, plot.y + plot.h); g.stroke();
    }
    // Labels: adaptive-thinned decades (wide) or the nice-linear set (sub-decade), a step-aware
    // formatter, and a pixel-aware two-pass overlap-skip (round decades placed first so they are
    // never thinned away). Java drawGrid label loop.
    const labelPositions = wideLog ? adaptiveLogLabels(freqMin, freqMax) : xMajors;
    const fineStep = wideLog ? 0 : minSpacing(labelPositions);
    g.fillStyle = '#333';
    const gap = g.measureText('0').width;
    const labelY = plot.y + plot.h + 2;
    const boxes = labelPositions.map((v) => {
      const s = fineStep > 0 ? formatFreqTick(v, fineStep) : formatFrequency(v);
      const sw = g.measureText(s).width;
      const cx = xOf(v);
      return { v, s, cx, l: cx - sw / 2, r: cx + sw / 2, done: false };
    });
    const placed = [];
    for (let pass = 0; pass < 2; pass++) {
      for (const b of boxes) {
        if (b.done || (pass === 0) !== isDecadeValue(b.v)) continue;
        if (b.v < freqMin || b.v > freqMax) continue;
        let clash = false;
        for (const o of placed) { if (b.l < o.r + gap && b.r + gap > o.l) { clash = true; break; } }
        if (clash) continue;
        g.fillText(b.s, b.cx, labelY);
        placed.push(b); b.done = true;
      }
    }

    // Right-hand ±180° phase axis ticks (8 divisions) when the phase trace is shown.
    if (phaseVisible) {
      const xr = plot.x + plot.w;
      g.strokeStyle = '#eee'; g.fillStyle = '#333';
      g.textAlign = 'left'; g.textBaseline = 'middle';
      for (let deg = -180; deg <= 180; deg += 45) {
        const yy = plot.y + (180 - deg) / 360 * plot.h;
        g.beginPath(); g.moveTo(xr, yy); g.lineTo(xr + 4, yy); g.stroke();
        g.fillText(deg + '°', xr + 6, yy);
      }
    }

    g.strokeStyle = '#999';
    g.strokeRect(plot.x, plot.y, plot.w, plot.h);
    // Left-Y unit caption "dB" — faithful port of AbstractMeasurementView.drawAxisCaptions
    // (:800). Java places it RIGHT-ALIGNED in the left margin just outside the plot's left
    // edge (tx = plot.x − MAJOR_TICK_LEN − textWidth − 4), in the top margin when there's
    // room above the plot else flush inside the top edge (capTy). This keeps it next to the
    // Y tick labels — NOT in the far top-left corner (the old (4,4) put it directly under the
    // .lr-tools toolbar overlay; issue #18). The toolbar itself is a DOM overlay whose
    // remaining overlap is a CSS-layer concern — see notesForCss.
    const lineH = 12;                       // Java gc.getFontMetrics().getHeight()
    const capTy = (plot.y >= lineH) ? plot.y - lineH : plot.y + 2;
    const capTx = plot.x - MAJOR_TICK_LEN - g.measureText('dB').width - 4;
    g.fillStyle = '#333'; g.textAlign = 'left'; g.textBaseline = 'top';
    g.fillText('dB', capTx, capTy);
  }

  // ----- the L/R magnitude + phase traces (FreqRespView.drawTraces) -----
  _drawTraces(g, plot, freqMin, freqMax, magTop, magBot, phaseVisible) {
    const prefs = this.prefs;
    const lw = prefs.freqRespLineWidth.get();
    const sigColor = colorHex(prefs.freqRespSignalColor.get());
    const phaseColor = colorHex(prefs.freqRespPhaseColor.get());
    const leftVis = prefs.freqRespLeftVisible.get();
    const rightVis = prefs.freqRespRightVisible.get();

    if (leftVis && this.leftResult) this._paintMag(g, this.leftResult, plot, freqMin, freqMax, magTop, magBot, sigColor, lw);
    if (rightVis && this.rightResult) this._paintMag(g, this.rightResult, plot, freqMin, freqMax, magTop, magBot, sigColor, lw);
    if (phaseVisible) {
      if (leftVis && this.leftResult) this._paintPhase(g, this.leftResult, plot, freqMin, freqMax, phaseColor, lw);
      if (rightVis && this.rightResult) this._paintPhase(g, this.rightResult, plot, freqMin, freqMax, phaseColor, lw);
    }
  }

  _paintMag(g, result, plot, freqMin, freqMax, magTop, magBot, color, lw) {
    const freqs = result.freqs, mag = result.magLin;
    if (!freqs || !mag) return;
    // True (unclamped) Y — the render-time plot-rect clip cuts an out-of-range trace at the
    // frame keeping its slope (Java setClipping), instead of flattening it against the edge.
    const yOf = (d) => plot.y + (magTop - d) / (magTop - magBot) * plot.h;
    // toDb reconstructs in LINEAR magnitude (matching Java paintTrace: Lanczos runs
    // on magLin, then linToDb with a floor so overshoot can't log a negative), so a
    // deep narrow null recovers its smooth rounded shape rather than a straight V.
    const toY = (v) => yOf(20 * Math.log10(Math.max(LANCZOS_MAG_FLOOR_LIN, v)));
    this._paintDataTrace(g, plot, freqMin, freqMax, color, lw, [], freqs, mag, toY);
  }

  _paintPhase(g, result, plot, freqMin, freqMax, color, lw) {
    const freqs = result.freqs, phaseRad = result.phaseRad;
    if (!freqs || !phaseRad) return;
    const xOf = (f) => plot.x + this._freqToXFraction(f, freqMin, freqMax) * plot.w;
    const yOf = (deg) => plot.y + (180 - Math.max(-180, Math.min(180, deg))) / 360 * plot.h;
    const dash = [1, 2];
    const scale = this._lanczosScale(freqs, freqMin, freqMax, plot.w);
    if (scale <= 0) {
      // Sparse-fallback / smoothing-off: straight segments through the data points.
      g.strokeStyle = color; g.lineWidth = lw; g.setLineDash(dash);
      g.beginPath();
      let started = false;
      for (let i = 0; i < freqs.length; i++) {
        const f = freqs[i];
        if (f < freqMin || f > freqMax) continue;
        const deg = phaseRad[i] * 180 / Math.PI;
        const xx = xOf(f), yy = yOf(deg);
        started ? g.lineTo(xx, yy) : (g.moveTo(xx, yy), started = true);
      }
      g.stroke();
      g.setLineDash([]);
      return;
    }
    // Lanczos with a LOCAL phase unwrap so the kernel never rings across a ±180°
    // wrap; re-wrapped at draw time so the wrap still shows as a clean vertical
    // jump with smooth curves either side. Only the visible span (+ kernel
    // padding) is unwrapped (Java FreqRespView.paintPhase).
    const pad = Math.ceil(LANCZOS_A * scale) + 1;
    const from = Math.max(0, this._indexBelow(freqs, freqMin) - pad);
    const to = Math.min(freqs.length - 1, this._indexBelow(freqs, freqMax) + pad);
    const len = to - from + 1;
    const uw = new Float64Array(len);
    uw[0] = phaseRad[from];
    for (let k = 1; k < len; k++) {
      uw[k] = uw[k - 1] + wrapToPi(phaseRad[from + k] - phaseRad[from + k - 1]);
    }
    this._paintPolyline(g, plot, color, lw, dash, plot.w,
      (i) => plot.x + i,
      (i) => {
        const f = this._xFractionToFreq(i / plot.w, freqMin, freqMax);
        const rad = wrapToPi(lanczosNaN(uw, len, this._fracIndex(freqs, f) - from, scale));
        return yOf(rad * 180 / Math.PI);
      });
  }

  // ----- Lanczos trace reconstruction (FreqRespView LANCZOS_TRACES path) -----

  /** Lanczos downsample factor for the visible data span, or 0 to fall back to the
   *  linear per-point feed (smoothing off, or the span carries more than
   *  MAX_LANCZOS_DOWNSAMPLE samples per pixel — too dense to upsample).
   *  Mirrors FreqRespView.lanczosScale. */
  _lanczosScale(freqs, freqMin, freqMax, width) {
    if (!LANCZOS_TRACES || !freqs || freqs.length < 2 || width < 2) return 0;
    const lo = this._indexBelow(freqs, freqMin);
    const hi = this._indexBelow(freqs, freqMax);
    const samplesPerPx = Math.max(1, hi - lo) / width;
    return samplesPerPx <= MAX_LANCZOS_DOWNSAMPLE ? Math.max(1.0, samplesPerPx) : 0;
  }

  /** Largest index i with freqs[i] <= f (binary search), clamped to range
   *  (FreqRespView.indexBelow). */
  _indexBelow(freqs, f) {
    let lo = 0, hi = freqs.length - 1;
    if (f <= freqs[0]) return 0;
    if (f >= freqs[hi]) return hi;
    while (hi - lo > 1) {
      const mid = (lo + hi) >>> 1;
      if (freqs[mid] <= f) lo = mid; else hi = mid;
    }
    return lo;
  }

  /** Fractional data index for frequency f, interpolated in log-freq so the index
   *  advances uniformly along the log axis the kernel reconstructs against
   *  (FreqRespView.fracIndex). */
  _fracIndex(freqs, f) {
    const lo = this._indexBelow(freqs, f);
    if (lo >= freqs.length - 1) return freqs.length - 1;
    const f0 = freqs[lo], f1 = freqs[lo + 1];
    if (f1 <= f0 || f <= f0) return lo;
    return lo + (Math.log(f) - Math.log(f0)) / (Math.log(f1) - Math.log(f0));
  }

  /** Draws one data-driven magnitude trace: a per-pixel Lanczos reconstruction of
   *  `data` (aligned with `freqs`) when smoothing is on and the span is sparse
   *  enough, else the linear per-point feed. `toY` maps a reconstructed-or-raw
   *  sample value to its Y (Java FreqRespView.paintDataTrace). */
  _paintDataTrace(g, plot, freqMin, freqMax, color, lw, dash, freqs, data, toY) {
    const scale = this._lanczosScale(freqs, freqMin, freqMax, plot.w);
    const n = freqs.length;
    if (scale > 0) {
      this._paintPolyline(g, plot, color, lw, dash, plot.w,
        (i) => plot.x + i,
        (i) => toY(lanczosNaN(data, n,
          this._fracIndex(freqs, this._xFractionToFreq(i / plot.w, freqMin, freqMax)), scale)));
    } else {
      const xOf = (f) => plot.x + this._freqToXFraction(f, freqMin, freqMax) * plot.w;
      this._paintPolyline(g, plot, color, lw, dash, n,
        (i) => xOf(freqs[i]),
        (i) => toY(data[i]));
    }
  }

  /** Strokes a polyline of `count` points (x = xAt(i), y = yAt(i)); a NaN y breaks
   *  the path into a gap (Java AbstractMeasurementView.paintPolylineImpl gap rule).
   *  The web strokes every point directly — the desktop's per-column bucketing is a
   *  fill-rate optimisation, not a visual difference at these point counts. */
  _paintPolyline(g, plot, color, lw, dash, count, xAt, yAt) {
    if (count < 2) return;
    g.strokeStyle = color; g.lineWidth = lw; g.setLineDash(dash || []);
    g.beginPath();
    let started = false;
    for (let i = 0; i < count; i++) {
      const y = yAt(i);
      if (Number.isNaN(y)) { started = false; continue; }   // genuine gap
      const x = xAt(i);
      started ? g.lineTo(x, y) : (g.moveTo(x, y), started = true);
    }
    g.stroke();
    if (dash && dash.length) g.setLineDash([]);
  }

  // ----- RIAA reference overlay (FreqRespView.drawRiaaOverlay) -----
  _drawRiaaOverlay(g, plot, freqMin, freqMax, magTop, magBot) {
    const prefs = this.prefs;
    const rev = prefs.freqRespReverseRiaa.get(), iec = prefs.freqRespIecAmendment.get();
    const anchorDb = this._riaaAnchorDb();
    const color = colorHex(prefs.freqRespReferenceColor.get());
    const lw = prefs.freqRespLineWidth.get();
    const yOf = (d) => plot.y + (magTop - d) / (magTop - magBot) * plot.h;   // unclamped; clipped at the frame
    g.strokeStyle = color; g.lineWidth = lw; g.setLineDash([4, 4]);
    g.beginPath();
    const step = 5;
    let started = false;
    for (let px = 0; px <= plot.w; px += step) {
      const f = this._xFractionToFreq(px / plot.w, freqMin, freqMax);
      const yy = yOf(anchorDb + evalDb(f, rev, iec));
      const xx = plot.x + px;
      started ? g.lineTo(xx, yy) : (g.moveTo(xx, yy), started = true);
    }
    g.stroke();
    g.setLineDash([]);
  }

  /** dB at 1 kHz of the active trace (L if visible, else R, else 0). */
  _riaaAnchorDb() {
    const anchor = this._activeChannelResult();
    if (!anchor) return 0.0;
    const db = this._interpDb(anchor, 1000.0);
    return Number.isFinite(db) ? db : 0.0;
  }

  _activeChannelResult() {
    const prefs = this.prefs;
    if (prefs.freqRespLeftVisible.get() && this.leftResult) return this.leftResult;
    if (prefs.freqRespRightVisible.get() && this.rightResult) return this.rightResult;
    if (this.leftResult) return this.leftResult;
    return this.rightResult;
  }

  // ===========================================================================
  // Compare mode — measured − RIAA reference (FreqRespView.getCompareDiff +
  // drawCompareTrace + drawCompareMeasurementTable + autoSetupCompare)
  // ===========================================================================

  /** Single source of truth for compare mode (FreqRespView.getCompareDiff). Builds the
   *  smoothed (measDb − refDb) array at the SIGNAL-POINT level, anchor-subtracts at 1 kHz
   *  so the curve reads 0 dB there, then derives the relative min/max over 20 Hz–25 kHz.
   *  Cached by the (src, reverse, iec, W) tuple so the trace, crosshair Δ, and table all
   *  read identical numbers. Returns {smoothed, minDb, maxDb}. */
  _getCompareDiff(src, reverse, iec) {
    const W = Math.max(0, Math.min(100, this.prefs.freqRespCompareSmoothWindow.get()));
    if (this._compareDiffCache
        && this._compareDiffCacheSrc === src
        && this._compareDiffCacheReverse === reverse
        && this._compareDiffCacheIec === iec
        && this._compareDiffCacheWindow === W) {
      return this._compareDiffCache;
    }
    const freqs = src.freqs, magLin = src.magLin;
    const n = freqs.length;
    // 1. Raw (measDb − refDb) per signal point. A non-positive (notch-null) magnitude
    //    maps to a finite -300 dB (mirror FreqRespFormat.linToDb) so it is INCLUDED in
    //    the running mean and drawn, rather than excised as a gap. The isFinite guard now
    //    never trips but is kept defensively.
    const raw = new Float64Array(n);
    for (let i = 0; i < n; i++) {
      const measDb = magLin[i] > 0 ? 20 * Math.log10(magLin[i]) : -300;
      if (!Number.isFinite(measDb)) { raw[i] = NaN; continue; }
      raw[i] = measDb - evalDb(freqs[i], reverse, iec);
    }
    // 2. Sliding mean in LOG-FREQUENCY space (1/W-octave window). W=0 disables smoothing.
    let smoothed;
    if (W <= 0) {
      smoothed = raw;
    } else {
      smoothed = new Float64Array(n);
      const logF = new Float64Array(n);
      for (let i = 0; i < n; i++) logF[i] = Math.log10(freqs[i]);
      const halfLog = Math.log10(2.0) / (2.0 * W);
      let sum = 0.0, cnt = 0, lo = 0, hi = -1;
      for (let i = 0; i < n; i++) {
        const targetHi = logF[i] + halfLog;
        const targetLo = logF[i] - halfLog;
        while (hi + 1 < n && logF[hi + 1] <= targetHi) {
          hi++;
          const v = raw[hi];
          if (!Number.isNaN(v)) { sum += v; cnt++; }
        }
        while (lo < n && logF[lo] < targetLo) {
          const v = raw[lo];
          if (!Number.isNaN(v)) { sum -= v; cnt--; }
          lo++;
        }
        smoothed[i] = cnt > 0 ? sum / cnt : NaN;
      }
    }
    // 3. Anchor: subtract the smoothed value at 1 kHz so the curve passes through 0 dB
    //    there. Drawing, min/max, crosshair Δ all read smoothed[i] DIRECTLY afterwards.
    const anchor = interpFromArray(freqs, smoothed, 1000.0);
    if (Number.isFinite(anchor) && anchor !== 0.0) {
      if (smoothed === raw) smoothed = Float64Array.from(raw);
      for (let i = 0; i < n; i++) {
        if (Number.isFinite(smoothed[i])) smoothed[i] -= anchor;
      }
    }
    // 4. Real min/max over the anchored array, restricted to 20 Hz–25 kHz.
    const fLo = 20.0, fHi = 25000.0;
    let minDb = NaN, maxDb = NaN;
    for (let i = 0; i < n; i++) {
      const f = freqs[i];
      if (f < fLo || f > fHi) continue;
      const v = smoothed[i];
      if (!Number.isFinite(v)) continue;
      if (Number.isNaN(minDb) || v < minDb) minDb = v;
      if (Number.isNaN(maxDb) || v > maxDb) maxDb = v;
    }
    const diff = { smoothed, minDb, maxDb };
    this._compareDiffCache = diff;
    this._compareDiffCacheSrc = src;
    this._compareDiffCacheReverse = reverse;
    this._compareDiffCacheIec = iec;
    this._compareDiffCacheWindow = W;
    return diff;
  }

  /** Draws the smoothed (measured − reference) compare trace. The smoothed array is
   *  already anchor-shifted so it's plotted DIRECTLY (FreqRespView.drawCompareTrace). */
  _drawCompareTrace(g, plot, freqMin, freqMax, magTop, magBot) {
    const prefs = this.prefs;
    const src = this._activeChannelResult();
    if (!src) return;
    const reverse = prefs.freqRespReverseRiaa.get(), iec = prefs.freqRespIecAmendment.get();
    const smoothed = this._getCompareDiff(src, reverse, iec).smoothed;
    const freqs = src.freqs;
    const xOf = (f) => plot.x + this._freqToXFraction(f, freqMin, freqMax) * plot.w;
    const yOf = (d) => plot.y + (magTop - d) / (magTop - magBot) * plot.h;   // unclamped; clipped at the frame
    g.strokeStyle = '#c000c0'; g.lineWidth = prefs.freqRespLineWidth.get(); g.setLineDash([]);
    g.beginPath();
    let started = false;
    for (let i = 0; i < freqs.length; i++) {
      const f = freqs[i];
      if (f < freqMin || f > freqMax) continue;
      const v = smoothed[i];
      if (!Number.isFinite(v)) { started = false; continue; }   // NaN → gap
      const xx = xOf(f), yy = yOf(v);
      started ? g.lineTo(xx, yy) : (g.moveTo(xx, yy), started = true);
    }
    g.stroke();
  }

  /** Top-left two-row min/max table of the smoothed diff curve (FreqRespView.drawCompareMeasurementTable). */
  _drawCompareMeasurementTable(g, plot) {
    if (Number.isNaN(this.compareSmoothedMin) || Number.isNaN(this.compareSmoothedMax)) return;
    g.font = '11px "Segoe UI", sans-serif';
    g.textBaseline = 'top'; g.textAlign = 'left';
    g.fillStyle = '#222';
    const x = plot.x + 6, y = plot.y + 6, lineH = 14;
    g.fillText('max: ' + formatDbReadout(this.compareSmoothedMax), x, y);
    g.fillText('min: ' + formatDbReadout(this.compareSmoothedMin), x, y + lineH);
  }

  /** Recomputes compareSmoothedMin/Max from the cached smoothed-diff array (does NOT
   *  change the freq/mag window). Returns true on success (FreqRespView.recomputeCompareAnchor). */
  _recomputeCompareAnchor() {
    const prefs = this.prefs;
    const src = this._activeChannelResult();
    if (!src) return false;
    const diff = this._getCompareDiff(src, prefs.freqRespReverseRiaa.get(), prefs.freqRespIecAmendment.get());
    if (Number.isNaN(diff.minDb) || Number.isNaN(diff.maxDb)) return false;
    this.compareSmoothedMin = diff.minDb;
    this.compareSmoothedMax = diff.maxDb;
    return true;
  }

  /** Compare-mode auto-setup: snaps the horizontal range to 20 Hz–25 kHz and fits the
   *  vertical window to the smoothed-diff envelope with headroom above (FreqRespView.autoSetupCompare). */
  autoSetupCompare() {
    const prefs = this.prefs;
    if (!this._recomputeCompareAnchor()) return;
    let newTop = this.compareSmoothedMax + MAG_HEADROOM_DB;
    let newBot = (this.compareSmoothedMin < -1.0) ? this.compareSmoothedMin - 1.0 : -1.0;
    newTop = Math.min(MAG_TOP_ZOOM_MAX_DB, newTop);
    newBot = Math.max(MAG_BOT_MIN_DB, newBot);
    prefs.freqRespFreqMinHz.set(20.0);
    prefs.freqRespFreqMaxHz.set(25000.0);
    prefs.freqRespMagTopDb.set(newTop);
    prefs.freqRespMagBotDb.set(newBot);
    prefs.save();
    this._publishRangeChanged();
    this.render();
  }

  // ----- crosshair (FreqRespView.drawCrosshair) -----
  _drawCrosshair(g, plot, freqMin, freqMax, magTop, magBot, phaseVisible) {
    if (this._mouseX < plot.x || this._mouseX > plot.x + plot.w) return;
    if (this._mouseY < plot.y || this._mouseY > plot.y + plot.h) return;
    const prefs = this.prefs;
    const frac = (this._mouseX - plot.x) / plot.w;
    let cursorFreq = this._xFractionToFreq(frac, freqMin, freqMax);
    const sr = this.lastResultSampleRate > 0 ? this.lastResultSampleRate : this._inputSampleRate();
    const maxFreq = prefs.freqRespNyquistFraction.get() * sr;
    if (cursorFreq > maxFreq && maxFreq > 0) cursorFreq = maxFreq;

    g.strokeStyle = '#888'; g.lineWidth = 1; g.setLineDash([1, 3]);
    g.beginPath(); g.moveTo(this._mouseX, plot.y); g.lineTo(this._mouseX, plot.y + plot.h); g.stroke();
    g.beginPath(); g.moveTo(plot.x, this._mouseY); g.lineTo(plot.x + plot.w, this._mouseY); g.stroke();
    g.setLineDash([]);

    const lines = [];
    lines.push('f = ' + formatFrequencyFine(cursorFreq));
    let magFrac = (this._mouseY - plot.y) / plot.h;
    if (magFrac < 0) magFrac = 0; if (magFrac > 1) magFrac = 1;
    lines.push('y = ' + formatDbReadout(magTop - magFrac * (magTop - magBot)));
    const compareActive = prefs.freqRespCompareMode.get() && prefs.freqRespShowRiaa.get() && this.hasAnyResult();
    if (compareActive) {
      // Compare mode draws the smoothed (measured − reference) curve — interpolate the
      // SAME anchor-shifted array so the Δ readout agrees with what's on screen.
      const src = this._activeChannelResult();
      if (src) {
        const diff = this._getCompareDiff(src, prefs.freqRespReverseRiaa.get(), prefs.freqRespIecAmendment.get());
        const s = interpFromArray(src.freqs, diff.smoothed, cursorFreq);
        if (Number.isFinite(s)) lines.push('Δ = ' + formatDbReadout(s));
      }
    } else {
      if (prefs.freqRespLeftVisible.get() && this.leftResult) {
        lines.push('L = ' + formatDbReadout(this._interpDb(this.leftResult, cursorFreq)));
      }
      if (prefs.freqRespRightVisible.get() && this.rightResult) {
        lines.push('R = ' + formatDbReadout(this._interpDb(this.rightResult, cursorFreq)));
      }
    }
    if (phaseVisible && !compareActive) {
      const phaseSrc = (prefs.freqRespLeftVisible.get() && this.leftResult) ? this.leftResult
        : (prefs.freqRespRightVisible.get() && this.rightResult) ? this.rightResult : null;
      if (phaseSrc) {
        const phaseRad = this._interpPhase(phaseSrc, cursorFreq);
        lines.push('φ = ' + formatPhaseReadout(phaseRad * 180 / Math.PI));
      }
    }
    this._drawReadoutBox(g, lines, this._mouseX + 12, this._mouseY + 12, plot);
  }

  _drawReadoutBox(g, lines, x, y, plot) {
    g.font = '11px "Segoe UI", sans-serif';
    g.textBaseline = 'top'; g.textAlign = 'left';
    const lh = 14, pad = 4;
    let w = 0;
    for (const l of lines) w = Math.max(w, g.measureText(l).width);
    const bw = w + pad * 2, bh = lines.length * lh + pad * 2;
    if (x + bw > plot.x + plot.w) x -= bw + 24;
    if (y + bh > plot.y + plot.h) y -= bh + 24;
    g.fillStyle = 'rgba(255,255,255,0.92)';
    g.strokeStyle = '#999'; g.lineWidth = 1;
    g.fillRect(x, y, bw, bh); g.strokeRect(x + 0.5, y + 0.5, bw, bh);
    g.fillStyle = '#222';
    for (let i = 0; i < lines.length; i++) g.fillText(lines[i], x + pad, y + pad + i * lh);
  }

  /** Log-frequency linear-dB interpolation of a result's magnitude (FreqRespView.interpDb). */
  _interpDb(r, f) {
    const freqs = r.freqs, mag = r.magLin;
    if (!freqs || !mag || freqs.length < 2) return NaN;
    if (f < freqs[0] || f > freqs[freqs.length - 1]) return NaN;
    let lo = 0, hi = freqs.length - 1;
    while (hi - lo > 1) { const mid = (lo + hi) >>> 1; if (freqs[mid] <= f) lo = mid; else hi = mid; }
    const t = (Math.log(f) - Math.log(freqs[lo])) / (Math.log(freqs[hi]) - Math.log(freqs[lo]));
    const db0 = mag[lo] > 0 ? 20 * Math.log10(mag[lo]) : -300;
    const db1 = mag[hi] > 0 ? 20 * Math.log10(mag[hi]) : -300;
    return db0 + t * (db1 - db0);
  }

  _interpPhase(r, f) {
    const freqs = r.freqs, p = r.phaseRad;
    if (!freqs || !p || freqs.length < 2) return NaN;
    if (f < freqs[0] || f > freqs[freqs.length - 1]) return NaN;
    let lo = 0, hi = freqs.length - 1;
    while (hi - lo > 1) { const mid = (lo + hi) >>> 1; if (freqs[mid] <= f) lo = mid; else hi = mid; }
    const t = (Math.log(f) - Math.log(freqs[lo])) / (Math.log(freqs[hi]) - Math.log(freqs[lo]));
    return p[lo] + t * (p[hi] - p[lo]);
  }

  // ===========================================================================
  // Mouse handling
  // ===========================================================================

  _onMouseMove(e) {
    const rect = this.cv.getBoundingClientRect();
    this._mouseX = e.clientX - rect.left;
    this._mouseY = e.clientY - rect.top;
    const W = this.cv.clientWidth || 1200, H = this.cv.clientHeight || 440;
    const rightMargin = this.prefs.freqRespPhaseVisible.get() ? MARGIN_RIGHT_PHASE : MARGIN_RIGHT_NO_PHASE;
    this._mouseInPlot = this._mouseX >= MARGIN_LEFT && this._mouseX <= W - rightMargin
      && this._mouseY >= MARGIN_TOP && this._mouseY <= H - MARGIN_BOTTOM;
    this.render();
  }

  _onWheel(e) {
    e.preventDefault();
    const ctrl = e.ctrlKey || e.metaKey;
    const shift = e.shiftKey;
    const dir = e.deltaY < 0 ? 1 : -1;
    if (ctrl && shift) this._zoomFrequencyAroundCursor(dir);
    else if (ctrl) this._zoomMagnitudeAroundCursor(dir);
    else if (shift) this._panFrequency(dir);
    else this._panMagnitude(dir);
  }

  // ===========================================================================
  // Zoom / pan (FreqRespView zoom + pan methods, faithfully)
  // ===========================================================================

  _plotRect() {
    const W = this.cv.clientWidth || 1200, H = this.cv.clientHeight || 440;
    if (W < 10 || H < 10) return null;
    const rightMargin = this.prefs.freqRespPhaseVisible.get() ? MARGIN_RIGHT_PHASE : MARGIN_RIGHT_NO_PHASE;
    return { x: MARGIN_LEFT, y: MARGIN_TOP, w: Math.max(1, W - MARGIN_LEFT - rightMargin), h: Math.max(1, H - MARGIN_TOP - MARGIN_BOTTOM) };
  }

  // ───────────── Rectangular zoom (base machinery in rect-zoom.js) ─────────────
  // Faithful port of the Java FreqRespView zoom overrides (commit 73f6c1f).
  // Selections live inside the plot area — zoomableArea = _plotRect().

  /** X = displayed frequency window (always log), Y = the magnitude-dB window.
   *  The fixed ±180° phase axis is not zoom state (Java FreqRespView.captureZoomState). */
  _captureZoomState() {
    const p = this.prefs;
    return { xMin: p.freqRespFreqMinHz.get(), xMax: p.freqRespFreqMaxHz.get(),
      yMin: [p.freqRespMagBotDb.get()], yMax: [p.freqRespMagTopDb.get()] };
  }

  /** Applies through the canonical range-change protocol, clamped like the wheel
   *  zoom; a restore that clamps degenerate (e.g. the Nyquist ceiling dropped
   *  below the stored window) returns false so undo skips the dead entry
   *  (Java FreqRespView.applyZoomState). */
  _applyZoomState(s) {
    const p = this.prefs;
    const fMin = Math.max(FREQ_MIN_FLOOR_HZ, s.xMin);
    const fMax = Math.min(this._nyquistHz(), s.xMax);
    const top = Math.min(MAG_TOP_ZOOM_MAX_DB, s.yMax[0]);
    const bot = Math.max(MAG_BOT_MIN_DB, s.yMin[0]);
    if (fMax <= fMin || top <= bot) return false;   // degenerate after clamping
    p.freqRespFreqMinHz.set(fMin);
    p.freqRespFreqMaxHz.set(fMax);
    p.freqRespMagTopDb.set(top);
    p.freqRespMagBotDb.set(bot);
    p.save();
    this._publishRangeChanged();
    this.render();
    return true;
  }

  /** Log-domain on X, linear-dB on Y — the crosshair / wheel-zoom mappings.
   *  Returns null for a selection that clamps to a degenerate range, so the
   *  base cancels the zoom instead of pushing a no-op undo entry
   *  (Java FreqRespView.zoomStateForRect). */
  _zoomStateForRect(sel) {
    const p = this.prefs;
    const plot = this._plotRect();
    if (!plot) return null;
    const fMin = Math.max(1.0, p.freqRespFreqMinHz.get());
    const fMax = p.freqRespFreqMaxHz.get();
    const top = p.freqRespMagTopDb.get();
    const span = top - p.freqRespMagBotDb.get();
    const newTop = Math.min(MAG_TOP_ZOOM_MAX_DB,
      top - (sel.y - plot.y) / plot.h * span);
    const newBot = Math.max(MAG_BOT_MIN_DB,
      top - (sel.y + sel.h - plot.y) / plot.h * span);
    const newFMin = Math.max(FREQ_MIN_FLOOR_HZ,
      this._xFractionToFreq((sel.x - plot.x) / plot.w, fMin, fMax));
    const newFMax = Math.min(this._nyquistHz(),
      this._xFractionToFreq((sel.x + sel.w - plot.x) / plot.w, fMin, fMax));
    if (newFMax <= newFMin || newTop <= newBot) return null;
    return { xMin: newFMin, xMax: newFMax, yMin: [newBot], yMax: [newTop] };
  }

  _zoomFrequencyAroundCursor(dir) {
    const prefs = this.prefs;
    let fMin = prefs.freqRespFreqMinHz.get(), fMax = prefs.freqRespFreqMaxHz.get();
    if (fMin <= 0) fMin = 1.0;
    const plot = this._plotRect(); if (!plot) return;
    const frac = (this._mouseX - plot.x) / plot.w;
    const cursorF = this._xFractionToFreq(frac, fMin, fMax);
    const scale = (dir > 0) ? 1.0 / FREQ_ZOOM_FACTOR : FREQ_ZOOM_FACTOR;
    let newMin = cursorF / Math.pow(fMax / fMin, frac * scale);
    let newMax = cursorF * Math.pow(fMax / fMin, (1.0 - frac) * scale);
    if (newMin >= newMax) return;
    const nyq = this._nyquistHz();
    newMin = Math.max(FREQ_MIN_FLOOR_HZ, newMin);
    newMax = Math.min(nyq, newMax);
    if (newMin >= newMax) return;
    prefs.freqRespFreqMinHz.set(newMin); prefs.freqRespFreqMaxHz.set(newMax); prefs.save();
    this._publishRangeChanged(); this.render();
  }

  _zoomMagnitudeAroundCursor(dir) {
    const prefs = this.prefs;
    const magTop = prefs.freqRespMagTopDb.get(), magBot = prefs.freqRespMagBotDb.get();
    const plot = this._plotRect(); if (!plot) return;
    const frac = (this._mouseY - plot.y) / plot.h;
    const cursorDb = magTop - frac * (magTop - magBot);
    const scale = (dir > 0) ? 1.0 / MAG_ZOOM_FACTOR : MAG_ZOOM_FACTOR;
    let newTop = cursorDb + (magTop - cursorDb) * scale;
    let newBot = cursorDb + (magBot - cursorDb) * scale;
    if (newTop <= newBot) return;
    newTop = Math.min(MAG_TOP_ZOOM_MAX_DB, newTop);
    newBot = Math.max(MAG_BOT_MIN_DB, newBot);
    if (newTop <= newBot) return;
    prefs.freqRespMagTopDb.set(newTop); prefs.freqRespMagBotDb.set(newBot); prefs.save();
    this._publishRangeChanged(); this.render();
  }

  _panFrequency(dir) {
    const prefs = this.prefs;
    let fMin = prefs.freqRespFreqMinHz.get(), fMax = prefs.freqRespFreqMaxHz.get();
    if (fMin <= 0) fMin = 1.0;
    const logSpan = Math.log10(fMax) - Math.log10(fMin);
    const delta = logSpan * FREQ_PAN_FRAC * (-dir);
    let newMin = Math.pow(10, Math.log10(fMin) + delta);
    let newMax = Math.pow(10, Math.log10(fMax) + delta);
    if (newMin <= 0 || newMax <= newMin) return;
    const nyq = this._nyquistHz();
    if (newMin < FREQ_MIN_FLOOR_HZ) { const s = FREQ_MIN_FLOOR_HZ / newMin; newMin *= s; newMax *= s; }
    if (newMax > nyq) { const s = nyq / newMax; newMin *= s; newMax *= s; }
    prefs.freqRespFreqMinHz.set(newMin); prefs.freqRespFreqMaxHz.set(newMax); prefs.save();
    this._publishRangeChanged(); this.render();
  }

  _panMagnitude(dir) {
    const prefs = this.prefs;
    let magTop = prefs.freqRespMagTopDb.get(), magBot = prefs.freqRespMagBotDb.get();
    const span = magTop - magBot;
    const delta = span * MAG_PAN_FRAC * dir;
    let newTop = magTop + delta, newBot = magBot + delta;
    if (newTop > MAG_TOP_ZOOM_MAX_DB) { const s = MAG_TOP_ZOOM_MAX_DB - newTop; newTop += s; newBot += s; }
    if (newBot < MAG_BOT_MIN_DB) { const s = MAG_BOT_MIN_DB - newBot; newTop += s; newBot += s; }
    prefs.freqRespMagTopDb.set(newTop); prefs.freqRespMagBotDb.set(newBot); prefs.save();
    this._publishRangeChanged(); this.render();
  }

  /** Snaps to the default frequency / magnitude window (FreqRespView.resetToDefaultView). */
  resetToDefaultView() {
    const prefs = this.prefs;
    prefs.freqRespFreqMinHz.set(FREQ_MIN_FLOOR_HZ);
    prefs.freqRespFreqMaxHz.set(this._nyquistHz());
    prefs.freqRespMagTopDb.set(this._softMagTopDb(MAG_TOP_MAX_DB, FREQ_MIN_FLOOR_HZ, this._nyquistHz()));
    prefs.freqRespMagBotDb.set(MAG_DEFAULT_BOT_DB);
    prefs.save();
    this._publishRangeChanged(); this.render();
  }

  /** Auto-fits the magnitude window to the visible result data (FreqRespView.autoSetupMagnitudeRange).
   *  In compare mode it dispatches to autoSetupCompare so the header auto-setup button always
   *  fits whichever curve the user is currently looking at. */
  autoSetupMagnitudeRange() {
    const prefs = this.prefs;
    if (prefs.freqRespCompareMode.get() && prefs.freqRespShowRiaa.get() && this.hasAnyResult()) {
      this.autoSetupCompare();
      return;
    }
    const fHi = this._nyquistHz();
    let minDb = Infinity, maxDb = -Infinity;
    if (prefs.freqRespLeftVisible.get() && this.leftResult) {
      const ext = this._magExtremaInBand(this.leftResult, 0.0, fHi);
      if (ext) { minDb = Math.min(minDb, ext[0]); maxDb = Math.max(maxDb, ext[1]); }
    }
    if (prefs.freqRespRightVisible.get() && this.rightResult) {
      const ext = this._magExtremaInBand(this.rightResult, 0.0, fHi);
      if (ext) { minDb = Math.min(minDb, ext[0]); maxDb = Math.max(maxDb, ext[1]); }
    }
    if (!Number.isFinite(minDb) || !Number.isFinite(maxDb) || maxDb <= minDb) return;
    const span = maxDb - minDb, pad = 0.10 * span;
    let newTop = maxDb + pad, newBot = minDb - pad;
    const MIN_SPAN = 2.0;
    if (newTop - newBot < MIN_SPAN) {
      const mid = 0.5 * (maxDb + minDb);
      newTop = mid + 0.5 * MIN_SPAN; newBot = mid - 0.5 * MIN_SPAN;
    }
    newTop = Math.min(MAG_TOP_ZOOM_MAX_DB, newTop);
    newBot = Math.max(MAG_BOT_MIN_DB, newBot);
    prefs.freqRespFreqMinHz.set(FREQ_MIN_FLOOR_HZ);
    prefs.freqRespFreqMaxHz.set(fHi);
    prefs.freqRespMagTopDb.set(this._softMagTopDb(newTop, FREQ_MIN_FLOOR_HZ, fHi));
    prefs.freqRespMagBotDb.set(newBot);
    prefs.save();
    this._publishRangeChanged(); this.render();
  }

  _magExtremaInBand(r, fLo, fHi) {
    const freqs = r.freqs, mag = r.magLin;
    if (!freqs || !mag) return null;
    let minDb = Infinity, maxDb = -Infinity;
    for (let i = 0; i < freqs.length; i++) {
      const f = freqs[i];
      if (f < fLo || f > fHi) continue;
      const m = mag[i];
      if (m <= 0) continue;
      const db = 20 * Math.log10(m);
      if (db < minDb) minDb = db;
      if (db > maxDb) maxDb = db;
    }
    if (!Number.isFinite(minDb) || !Number.isFinite(maxDb)) return null;
    return [minDb, maxDb];
  }

  /** topPref raised to keep ≥ MAG_HEADROOM_DB above the highest displayed point in
   *  [fLo, fHi] (FreqRespView.softMagTopDb). */
  _softMagTopDb(topPref, fLo, fHi) {
    const prefs = this.prefs;
    // Compare mode draws the diff curve, not the raw traces — keep the headroom above the
    // compared-signal peak (compareSmoothedMax), not leftResult/right (FreqRespView.softMagTopDb).
    if (prefs.freqRespCompareMode.get()) {
      return Number.isFinite(this.compareSmoothedMax)
        ? Math.max(topPref, this.compareSmoothedMax + MAG_HEADROOM_DB) : topPref;
    }
    let maxDb = -Infinity;
    if (prefs.freqRespLeftVisible.get() && this.leftResult) {
      const ext = this._magExtremaInBand(this.leftResult, fLo, fHi);
      if (ext) maxDb = Math.max(maxDb, ext[1]);
    }
    if (prefs.freqRespRightVisible.get() && this.rightResult) {
      const ext = this._magExtremaInBand(this.rightResult, fLo, fHi);
      if (ext) maxDb = Math.max(maxDb, ext[1]);
    }
    return Number.isFinite(maxDb) ? Math.max(topPref, maxDb + MAG_HEADROOM_DB) : topPref;
  }

  /** Magnitude scrollbar ceiling (dB): +20 dB default raised to the corrected peak + 20 dB. */
  magCeilingDb() {
    return this._softMagTopDb(MAG_TOP_MAX_DB, FREQ_MIN_FLOOR_HZ, this._nyquistHz());
  }

  // ===========================================================================
  // Navigation scrollbars: freq log [1, Nyquist], mag linear [-300, magCeiling]
  // ===========================================================================

  /** Re-syncs both scrollbar thumbs + selections from the current freq / mag window. */
  syncScrollbars() {
    const prefs = this.prefs;
    if (this.freqScroll) {
      // Log freq scrollbar over [FREQ_MIN_FLOOR_HZ, Nyquist]: thumb = visible decades /
      // total decades, selection = where the visible window sits.
      const loLog = Math.log10(FREQ_MIN_FLOOR_HZ), hiLog = Math.log10(this._nyquistHz());
      const totalLog = Math.max(1e-9, hiLog - loLog);
      let fMin = prefs.freqRespFreqMinHz.get(); if (fMin <= 0) fMin = FREQ_MIN_FLOOR_HZ;
      const fMax = prefs.freqRespFreqMaxHz.get();
      const visLog = Math.max(0, Math.log10(fMax) - Math.log10(fMin));
      const thumb = Math.max(1, Math.min(NAV_RANGE, Math.round(visLog / totalLog * NAV_RANGE)));
      this.freqScroll.setThumb(thumb);
      const maxSel = NAV_RANGE - thumb;
      const startFrac = (Math.log10(fMin) - loLog) / totalLog;
      this.freqScroll.setSelection(Math.round(startFrac * maxSel));
      this.freqScroll.setIncrement(Math.max(1, Math.round(NAV_RANGE / 100)));
      this.freqScroll.setPageIncrement(Math.max(1, Math.round(NAV_RANGE / 10)));
    }
    if (this.magScroll) {
      // Linear mag scrollbar over [MAG_BOT_MIN_DB, magCeiling]: thumb = visible dB /
      // total dB; top selection = thumb at the TOP of the track.
      const ceil = this.magCeilingDb();
      const totalDb = Math.max(1e-9, ceil - MAG_BOT_MIN_DB);
      const magTop = prefs.freqRespMagTopDb.get(), magBot = prefs.freqRespMagBotDb.get();
      const visDb = Math.max(0, magTop - magBot);
      const thumb = Math.max(1, Math.min(NAV_RANGE, Math.round(visDb / totalDb * NAV_RANGE)));
      this.magScroll.setThumb(thumb);
      const maxSel = NAV_RANGE - thumb;
      // selection 0 = thumb at top = window top at the ceiling.
      const startFrac = (ceil - magTop) / totalDb;
      this.magScroll.setSelection(Math.round(startFrac * maxSel));
      this.magScroll.setIncrement(Math.max(1, Math.round(NAV_RANGE / 100)));
      this.magScroll.setPageIncrement(Math.max(1, Math.round(NAV_RANGE / 10)));
    }
  }

  _onFreqScroll(sel) {
    const prefs = this.prefs;
    const loLog = Math.log10(FREQ_MIN_FLOOR_HZ), hiLog = Math.log10(this._nyquistHz());
    const totalLog = Math.max(1e-9, hiLog - loLog);
    let fMin = prefs.freqRespFreqMinHz.get(); if (fMin <= 0) fMin = FREQ_MIN_FLOOR_HZ;
    const fMax = prefs.freqRespFreqMaxHz.get();
    const visLog = Math.max(0, Math.log10(fMax) - Math.log10(fMin));   // keep the span fixed (pan)
    const thumb = this.freqScroll.getThumb();
    const maxSel = NAV_RANGE - thumb;
    const startFrac = maxSel <= 0 ? 0 : sel / maxSel;
    const newLoLog = loLog + startFrac * (totalLog - visLog);
    prefs.freqRespFreqMinHz.set(Math.pow(10, newLoLog));
    prefs.freqRespFreqMaxHz.set(Math.pow(10, newLoLog + visLog));
    prefs.save();
    this._publishRangeChanged(); this.render();
  }

  _onMagScroll(sel) {
    const prefs = this.prefs;
    const ceil = this.magCeilingDb();
    const totalDb = Math.max(1e-9, ceil - MAG_BOT_MIN_DB);
    const magTop = prefs.freqRespMagTopDb.get(), magBot = prefs.freqRespMagBotDb.get();
    const visDb = Math.max(0, magTop - magBot);   // keep the span fixed (pan)
    const thumb = this.magScroll.getThumb();
    const maxSel = NAV_RANGE - thumb;
    const startFrac = maxSel <= 0 ? 0 : sel / maxSel;
    const newTop = ceil - startFrac * (totalDb - visDb);
    prefs.freqRespMagTopDb.set(newTop);
    prefs.freqRespMagBotDb.set(newTop - visDb);
    prefs.save();
    this._publishRangeChanged(); this.render();
  }

  _publishRangeChanged() {
    this.syncScrollbars();
    if (this._onRangeChanged) this._onRangeChanged();
  }
}

// ----- module-private formatters (FreqRespFormat) -----

/** Wraps a radian angle to (−π, π] — used to unwrap the phase before Lanczos and
 *  to re-wrap the reconstructed value for display (FreqRespView.wrapToPi). */
function wrapToPi(r) {
  const twoPi = 2.0 * Math.PI;
  return r - twoPi * Math.floor((r + Math.PI) / twoPi);
}

/** Smallest power of two ≥ x (≥ 1). Mirrors fft.MathUtil.nextPow2. */
function nextPow2(x) {
  if (x <= 1) return 1;
  let p = 1;
  while (p < x) p <<= 1;
  return p;
}

/** Picks a "nice" dB tick step: the multiple of `mult` nearest the target division
 *  count, clamped ≥ `mult` (AxisSpec.linearNice intent). */
function niceDbStep(span, divisions, mult) {
  if (!(span > 0) || divisions < 1) return mult;
  const raw = span / divisions;
  const step = Math.round(raw / mult) * mult;
  return step >= mult ? step : mult;
}

/** Four-significant-digit fixed-notation formatter (FreqRespFormat.formatSignificant). */
function formatSignificant(value, sigDigits) {
  if (value === 0.0) return (0).toFixed(sigDigits - 1);
  const absV = Math.abs(value);
  const magnitude = Math.floor(Math.log10(absV));
  const decimals = Math.max(0, sigDigits - 1 - magnitude);
  return value.toFixed(decimals);
}

function formatDbBare(db) {
  if (!Number.isFinite(db)) return '—';
  return formatSignificant(db, 4);
}

function formatDbReadout(db) {
  if (!Number.isFinite(db)) return '—';
  return formatSignificant(db, 4) + ' dB';
}

function formatPhaseReadout(deg) {
  if (!Number.isFinite(deg)) return '—';
  return formatSignificant(deg, 4) + '°';
}

/** Log-frequency linear interpolation of a value array aligned 1:1 with a freq grid
 *  (e.g. the cached smoothed-diff array). NaN when f is outside the grid or either
 *  bracketing sample is NaN. Mirrors FreqRespView.interpFromArray. */
function interpFromArray(freqs, vals, f) {
  if (!freqs || !vals || freqs.length < 2) return NaN;
  if (f < freqs[0] || f > freqs[freqs.length - 1]) return NaN;
  let lo = 0, hi = freqs.length - 1;
  while (hi - lo > 1) { const mid = (lo + hi) >>> 1; if (freqs[mid] <= f) lo = mid; else hi = mid; }
  const v0 = vals[lo], v1 = vals[hi];
  if (Number.isNaN(v0) || Number.isNaN(v1)) return NaN;
  const t = (Math.log(f) - Math.log(freqs[lo])) / (Math.log(freqs[hi]) - Math.log(freqs[lo]));
  return v0 + t * (v1 - v0);
}
