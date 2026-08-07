/*
 * Phonalyser web - the interactive Frequency-Response VIEW (trace canvas · log-freq
 * X / linear-nice dB left-Y / ±180° phase right-Y axes · wheel zoom+pan · crosshair
 * readout · two navigation FlatScrollbars · render-time calibration).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of gui/freqresp/FreqRespView (+ FreqRespFormat). Holds the RAW L/R
 * result slots and a CALIBRATED display copy derived from them at RENDER time -
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
// Ideal-filter reference overlay (Java FreqRespView filter seam) - the FilterDesign
// magnitude curve + the FilterType/Response/UnevenMode enums the reference + unevenness
// pipelines read. Faithful port of org.edgo.audio.measure.gui.freqresp.FreqRespView.
import { FilterDesign } from '../dsp/filter-design.js';
import { FilterType, FilterResponse, hasRipple, UnevenMode } from '../dsp/filter-types.js';
import { t } from '../i18n/i18n.js';
// Lanczos (windowed-sinc) trace reconstruction - one band-limited sample per
// pixel column instead of joining the data points with straight segments, so a
// sparse span (e.g. the few bins across a deep narrow notch null) renders as the
// smooth ROUNDED dip the underlying response actually is, not a straight-segment
// V. Faithful port of FreqRespView's LANCZOS_TRACES path (uses the NaN-aware
// double[] overload - invalid points are treated as missing, not gaps).
import { lanczosNaN, LANCZOS_A, MAX_LANCZOS_DOWNSAMPLE } from '../dsp/lanczos.js';
// Drag-select rectangular zoom + Ctrl+Z undo (Java AbstractMeasurementView's
// installRectZoom base machinery); this view supplies the log-freq / dB
// pixel↔value mappings + clamps (Java FreqRespView zoom overrides).
import { RectZoom } from '../ui/rect-zoom.js';
// Shared axis tick generation + label formatters (Java AbstractMeasurementView) - the adaptive
// log / sub-decade frequency ticks + the fine crosshair frequency readout.
import {
  isSubDecade, isDecadeValue, labelStep,
  logMajorTicks, logMinorTicks, adaptiveLogLabels,
  niceLinearMajors, subDecadeMinors,
  formatFreqTick, formatFreqDecadeTick, formatFrequencyFine, formatDb,
} from '../ui/axis-format.js';

// ----- packed-int colour -> CSS hex (matches the fft-view helper) -----
const colorHex = (c) => '#' + (c & 0xffffff).toString(16).padStart(6, '0');

// ----- plot geometry (mirror FreqRespView margins) -----
const MARGIN_LEFT = 56;
const MARGIN_TOP = 4;
const MARGIN_BOTTOM = 18;
const MARGIN_RIGHT_NO_PHASE = 6;
const MARGIN_RIGHT_PHASE = 52;
// Axis tick length (Java AbstractMeasurementView.MAJOR_TICK_LEN) - used to inset the
// left-Y unit caption from the plot's left edge, mirroring drawAxisCaptions.
const MAJOR_TICK_LEN = 6;
// Sub-decade X-axis nice-linear tick target (Java AbstractMeasurementView.SUB_DECADE_TICK_TARGET).
const SUB_DECADE_TICK_TARGET = 12;

// ----- Lanczos trace smoothing (FreqRespView.LANCZOS_TRACES path) -----
/** Master on/off for sinc (Lanczos) trace smoothing. Off = linear segments. */
const LANCZOS_TRACES = true;
/** Linear-amplitude floor that keeps a Lanczos overshoot from driving the
 *  reconstructed magnitude negative (-> log10 of a negative number). */
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

// ----- reference / annotation trace colours (Java AbstractMeasurementView palette) -----
// COMPARE_TRACE (0x1B5E20) is the desktop's dark-green diff trace; the web port has
// historically drawn the RIAA compare trace in magenta (#c000c0) - kept as-is here (a
// pre-existing web divergence, not re-touched). FILTER_TRACE (0x8E24AA "lila") is the
// ideal-filter overlay / compare colour + the unevenness vertical annotations.
const COMPARE_TRACE_COLOR = '#c000c0';
const FILTER_TRACE_COLOR = '#8e24aa';

// ----- canvas overlay tables: anchored below the header button row -----
// Java puts BOTH overlay tables just under its header buttons, at an ABSOLUTE
// y = BTN_TOP + BTN_H + 6 from the widget top - the compare table
// (FreqRespView:1772) and the unevenness readout (:2167) share that anchor.
// Java's row is BTN_TOP=4 / BTN_H=22; here it is the .lr-tools DOM overlay
// (top:5px, 22px buttons + 1px borders = 24 high), which the canvas cannot
// measure - hence the mirrored constants. Anchoring to plot.y (=MARGIN_TOP=4)
// instead put the text at y=10, straight through the L/R buttons.
const BTN_TOP = 5;
const BTN_H = 24;
const OVERLAY_TABLE_TOP = BTN_TOP + BTN_H + 6;   // 35 px from the canvas top

// ----- unevenness (response-flatness) analysis constants (Java FreqRespView) -----
/** Fraction of Nyquist above which the measured curve is IGNORED for all
 *  unevenness analysis (weak deconvolved signal buried in noise). */
const NYQUIST_ANALYSIS_FRACTION = 0.95;
/** Floating-average window (grid points) for the LP/HP/BP peak walk + corner anchor. */
const UNEVEN_SMOOTH_POINTS = 9;
/** Running-median window (points) for NOTCH-mode analysis (depth-preserving despike). */
const NOTCH_DESPIKE_POINTS = 3;
/** Audio-band bounds (Hz) for the INITIAL extremum search. */
const AUDIO_SEARCH_MIN_HZ = 20.0;
const AUDIO_SEARCH_MAX_HZ = 20000.0;
/** Displayed NOTCH null depth (dB below the plateau) when the design carries no
 *  user stopband-attenuation spec (Mode 2, design-by-order). */
const NOTCH_DISPLAY_FLOOR_DB = 120.0;
/** Half-width (px) of the short green crossing tick drawn at each Mode-B boundary. */
const UNEVEN_TICK_HALF_PX = 20;
/** Two Mode-B crossing levels within this many dB are treated as equal. */
const UNEVEN_LEVEL_EPS_DB = 0.05;
/** Symmetric vertical pad (dB) for the compare-mode auto-zoom (autoSetupCompare). */
const COMPARE_ZOOM_PAD_DB = 2.0;

export class FreqRespView {
  /**
   * @param {HTMLCanvasElement} canvas  the #frPlot canvas
   * @param {import('../store/preferences.js').Preferences} prefs Preferences.instance()
   * @param {import('../common/correction-store.js').CorrectionStore} correctionStore loaded .frc store
   * @param {{freqScroll?:HTMLCanvasElement, magScroll?:HTMLCanvasElement,
   *          onRangeChanged?:Function}} deps  (callers may also pass an unused
   *          {@code engine} key - the view now reads the input rate from prefs,
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
    // result is loaded - see _inputSampleRate).
    this.lastResultSampleRate = 0;

    // Crosshair cursor state.
    this._mouseX = -1;
    this._mouseY = -1;
    this._mouseInPlot = false;

    // Compare-mode state (FreqRespView CompareDiff + the public min/max scalars). The
    // smoothed (measDb − refDb) array is cached by the (src, reverse, iec, W) tuple and
    // read by drawCompareTrace, the min/max table, and the crosshair Δ readout - so all
    // three agree by construction (Java getCompareDiff). compareSmoothedMin/Max mirror
    // CompareDiff.minDb/maxDb for the table; NaN until the first recomputeCompareAnchor.
    this.compareSmoothedMin = NaN;
    this.compareSmoothedMax = NaN;
    this._compareDiffCache = null;
    this._compareDiffCacheSrc = null;
    this._compareDiffCacheReverse = false;
    this._compareDiffCacheIec = false;
    this._compareDiffCacheWindow = -1;
    // Reference-source identity baked into the compare cache (Java compareDiffCacheFilterSrc /
    // compareDiffCacheFilter): true while the ideal-filter curve is the active reference, plus the
    // FilterDesign identity, so a source swap (RIAA ↔ filter) or filter-param nudge rebuilds the diff.
    this._compareDiffCacheFilterSrc = false;
    this._compareDiffCacheFilter = null;

    // Ideal-filter reference state (Java filterDesign / filterParams / filterAnchorCache).
    // filterDesign is null while the current filter params are invalid -> reference unavailable.
    this._filterDesign = null;
    this._filterParams = null;              // last FilterParams snapshot (see _refreshFilterDesign)
    this._filterAnchorCache = NaN;          // corner-point anchor (dB); NaN forces recompute
    this._filterAnchorCacheDesign = null;   // the filterDesign identity the anchor was computed against

    // Unevenness readout state (Java unevenLoHz/HiHz/PlusDb/ThresholdDb/ExtremumDb).
    this._unevenLoHz = NaN;
    this._unevenHiHz = NaN;
    this._unevenPlusDb = NaN;
    this._unevenThresholdDb = NaN;
    this._unevenExtremumDb = NaN;

    this.isolated = !!deps.isolated;

    canvas.addEventListener('wheel', (e) => this._onWheel(e), { passive: false });
    canvas.addEventListener('mousemove', (e) => this._onMouseMove(e));
    canvas.addEventListener('mouseleave', () => { this._mouseInPlot = false; this.render(); });
    // Drag-select zoom + Ctrl+Z undo (Java FreqRespView: installRectZoom(this, true)
    // - hookMouse, so the machinery wires the drag to the canvas's own mouse events).
    this._rectZoom = new RectZoom(canvas, {
      captureState: () => this._captureZoomState(),
      applyState: (s) => this._applyZoomState(s),
      stateForRect: (sel) => this._zoomStateForRect(sel),
      zoomableArea: () => this._plotRect(),
      repaintOverlay: () => this.render(),
    });

    // Two navigation FlatScrollbars (optional - wired only when the host provides the
    // gutter canvases): freq (log) horizontal + mag (linear) vertical.
    this.freqScroll = deps.freqScroll
      ? new FlatScrollbar(deps.freqScroll, { vertical: false, onChange: (s) => this._onFreqScroll(s) })
      : null;
    this.magScroll = deps.magScroll
      ? new FlatScrollbar(deps.magScroll, { vertical: true, onChange: (s) => this._onMagScroll(s) })
      : null;
    if (this.freqScroll) { this.freqScroll.setMinimum(0); this.freqScroll.setMaximum(NAV_RANGE); }
    if (this.magScroll) { this.magScroll.setMinimum(0); this.magScroll.setMaximum(NAV_RANGE); }

    // Ideal-filter reference + unevenness pref subscriptions (Java FreqRespView
    // Bindings.onChange block). Show carries the one-shot compare auto-zoom (fit once
    // when the filter compare trace first becomes active). The per-type scalar params
    // live in the Preferences params map (FilterParams.of re-reads it each paint), so
    // the filter caches self-validate without a per-scalar subscription here.
    prefs.freqRespShowFilter.addListener((show) => {
      this._invalidateFilterReference();
      if (show && prefs.freqRespFilterCompare.get() && this.hasAnyResult()) {
        this.autoSetupCompare();
      }
      this.render();
    });
    prefs.freqRespFilterCompare.addListener(() => this.render());
    prefs.freqRespFilterType.addListener(() => { this._invalidateFilterReference(); this.render(); });
    prefs.freqRespFilterResponse.addListener(() => { this._invalidateFilterReference(); this.render(); });
    prefs.freqRespUnevenMode.addListener(() => { this._recomputeUnevenness(); this.render(); });
    prefs.freqRespUnevenNotch.addListener(() => { this._recomputeUnevenness(); this.render(); });
    prefs.freqRespUnevenDb.addListener(() => { this._recomputeUnevenness(); this.render(); });
    prefs.freqRespUnevenStartHz.addListener(() => { this._recomputeUnevenness(); this.render(); });
    prefs.freqRespUnevenStopHz.addListener(() => { this._recomputeUnevenness(); this.render(); });
  }

  // ===========================================================================
  // Public API - host pushes results / selection here
  // ===========================================================================

  /** Replaces the left-channel RAW result and re-derives its calibrated copy. */
  setLeftResult(result) {
    this.rawLeftResult = result;
    this.leftResult = this.applyCurrentCalibration(result);
    if (result) this.lastResultSampleRate = result.sampleRate;
    this._invalidateFilterAnchor();  // new curve -> re-anchor the filter overlay
    this._recomputeUnevenness();     // new curve -> refresh the flatness readout
    this.render();
  }

  /** Replaces the right-channel RAW result and re-derives its calibrated copy. */
  setRightResult(result) {
    this.rawRightResult = result;
    this.rightResult = this.applyCurrentCalibration(result);
    if (result) this.lastResultSampleRate = result.sampleRate;
    this._invalidateFilterAnchor();  // new curve -> re-anchor the filter overlay
    this._recomputeUnevenness();     // new curve -> refresh the flatness readout
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
    this._invalidateFilterAnchor();  // new curve -> re-anchor the filter overlay
    this._recomputeUnevenness();     // new curve -> refresh the flatness readout
    this.render();
  }

  /** Re-derives the display copies from the raw results using the calibration
   *  currently active in the store (load / clear / wizard-apply -> retrace without a
   *  re-sweep). Mirrors FreqRespView.onCalibrationChanged - also refreshes the compare
   *  anchor + min/max table (but never re-zooms; only autoSetupCompare may re-zoom). */
  onCalibrationChanged() {
    if (this.rawLeftResult) this.leftResult = this.applyCurrentCalibration(this.rawLeftResult);
    if (this.rawRightResult) this.rightResult = this.applyCurrentCalibration(this.rawRightResult);
    this._invalidateFilterAnchor();  // calibrated curve changed -> re-anchor the filter overlay
    this._recomputeUnevenness();     // calibrated curve changed -> refresh the readout
    if (this._compareActive()) {
      this._recomputeCompareAnchor();
    }
    this.render();
  }

  /** Refreshes the compare anchor + min/max table after a compare param (the smoothing
   *  window pref) changes. No-op when compare / RIAA is off or no result is loaded; always
   *  redraws (FreqRespView.onCompareParamsChanged). */
  onCompareParamsChanged() {
    if (this._compareActive()) {
      this._recomputeCompareAnchor();
    }
    this.render();
  }

  /** Clears both result slots (raw and display). Mirrors FreqRespView.clearResults, which
   *  leaves compareSmoothedMin/Max intact (carry-over) so the compare min/max table keeps its
   *  numbers across a re-sweep - they are overwritten by the next recomputeCompareAnchor. */
  clearResults() {
    this.rawLeftResult = this.rawRightResult = null;
    this.leftResult = this.rightResult = null;
    this._invalidateFilterAnchor();  // no curve -> drop the cached filter anchor
    this._recomputeUnevenness();     // no curve -> clear the flatness readout
    this.render();
  }

  hasAnyResult() { return this.leftResult != null || this.rightResult != null; }

  /** Read-only accessors used by the Save-to handler - the current calibrated/displayed L/R
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
   *  A loaded .frc already carries the division (calibrationApplied) -> returned
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
   *  loaded yet - the user's REQUESTED input-rate pref (Java nyquistHz + drawCrosshair
   *  both read {@code prefs.current().getInputSampleRate()}). Must NOT read
   *  {@code engine.config.inRate}: SharedCapture re-pins that to the rate Web Audio
   *  actually negotiated (often the OS-capped 48 kHz), which would shrink the zoom-out
   *  ceiling below the rate the user set - diverging from the desktop, which clamps to
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
    // HiDPI backing store (mirror of FftView.render): backing px = CSS px ×
    // devicePixelRatio, then setTransform(dpr,...) so 1 CSS px == dpr device px and the text /
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
    // raw traces + reference overlay (FreqRespView.onPaint branch, via the reference seam).
    const compareActive = this._compareActive();
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
      if (this._referenceActive()) {
        this._drawReferenceOverlay(g, plot, freqMin, freqMax, magTop, magBot);
      }
    }
    g.restore();
    // Overlay tables (not trace data) - drawn unclipped. The unevenness table stacks
    // below the compare table when both are visible; the unevenness annotations are a
    // dynamic overlay clipped to the plot. Faithful to FreqRespView.onPaint, the in-plot
    // tables are NOT suppressed while extracted (unlike FftView) - the desktop draws them
    // in both the plot and the window; the external window mirrors the same fields.
    if (compareActive) this._drawCompareMeasurementTable(g, plot);
    this._drawUnevennessTable(g, plot, compareActive);
    this._drawUnevennessAnnotations(g, plot, freqMin, freqMax, magTop, magBot);
    if (this._mouseInPlot) {
      this._drawCrosshair(g, plot, freqMin, freqMax, magTop, magBot, phaseVisible);
    }
    // Rect-zoom rubber band + focused-view accent border - LAST, over the whole
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
      // Step-aware dB label (Java LabelFormat.DB -> formatDb): one decimal while the ticks are
      // 1 dB apart or coarser, growing only when a zoom goes below that.
      g.textAlign = 'right'; g.fillText(formatDb(d, dbStep), plot.x - 6, yy);
    }

    // Frequency gridlines + labels (Java AbstractMeasurementView majorTicks/minorTicks +
    // drawGrid). Wide log (≥1 decade): 1..9×10ⁿ grid with adaptiveLogLabels decade-thinning.
    // Sub-decade zoom: nice-linear majors/minors + step-aware Hz labels, so a ~9 Hz window
    // shows 998...1007 Hz instead of a lone "1 kHz".
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
    const fineStep = labelStep(labelPositions, true, freqMin, freqMax);
    g.fillStyle = '#333';
    const gap = g.measureText('0').width;
    const labelY = plot.y + plot.h + 2;
    const boxes = labelPositions.map((v) => {
      // A wide log axis has no single step: each decade multiple renders with just the decimals
      // its own decade needs ("20 Hz", "1 kHz"), not a fixed %.2f (Java applyLabelFormat FREQ).
      const s = fineStep > 0 ? formatFreqTick(v, fineStep) : formatFreqDecadeTick(v);
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
    // Left-Y unit caption "dB" - faithful port of AbstractMeasurementView.drawAxisCaptions
    // (:800). Java places it RIGHT-ALIGNED in the left margin just outside the plot's left
    // edge (tx = plot.x − MAJOR_TICK_LEN − textWidth − 4), in the top margin when there's
    // room above the plot else flush inside the top edge (capTy). This keeps it next to the
    // Y tick labels - NOT in the far top-left corner (the old (4,4) put it directly under the
    // .lr-tools toolbar overlay). The toolbar itself is a DOM overlay whose
    // remaining overlap is a CSS-layer concern - see notesForCss.
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
    // True (unclamped) Y - the render-time plot-rect clip cuts an out-of-range trace at the
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
   *  MAX_LANCZOS_DOWNSAMPLE samples per pixel - too dense to upsample).
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
   *  The web strokes every point directly - the desktop's per-column bucketing is a
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

  // ===========================================================================
  // Reference-curve seam - ONE source of the overlaid / compared reference.
  //
  // Only one reference can be active at a time (the tab enforces the mutual
  // exclusion between Show-RIAA and Show-Filter), so the RIAA and ideal-filter
  // curves share ONE pipeline: the overlay draw, the compare diff, the compare
  // readout, and the crosshair Δ all pull the reference dB from _referenceDb()
  // and its colour from _referenceColorRole() (FreqRespView reference seam).
  // ===========================================================================

  /** true while a reference curve (RIAA or ideal filter) is enabled and available.
   *  The filter source additionally requires a valid filterDesign (invalid params
   *  => unavailable => no overlay). Mirrors FreqRespView.referenceActive. */
  _referenceActive() {
    const prefs = this.prefs;
    if (prefs.freqRespShowRiaa.get()) return true;
    if (prefs.freqRespShowFilter.get()) { this._refreshFilterDesign(); return this._filterDesign != null; }
    return false;
  }

  /** true while the ACTIVE reference source's compare pref is on - RIAA uses
   *  freqRespCompareMode, the filter uses freqRespFilterCompare (FreqRespView.referenceCompareOn). */
  _referenceCompareOn() {
    const prefs = this.prefs;
    if (prefs.freqRespShowRiaa.get()) return prefs.freqRespCompareMode.get();
    if (prefs.freqRespShowFilter.get()) return prefs.freqRespFilterCompare.get();
    return false;
  }

  /** The CSS colour for the active reference's overlay / compare trace - the RIAA
   *  reference-color pref for RIAA, FILTER_TRACE (lila) for the ideal filter
   *  (FreqRespView.referenceColorRole -> RIAA_TRACE / FILTER_TRACE). */
  _referenceColorRole() {
    return this.prefs.freqRespShowFilter.get()
      ? FILTER_TRACE_COLOR : colorHex(this.prefs.freqRespReferenceColor.get());
  }

  /** true when the compare (diff) trace + table + Δ readout should be drawn: the
   *  active reference is available, its compare pref is on, and a measurement is
   *  loaded. The single gate for every compare-mode branch (FreqRespView.compareActive). */
  _compareActive() {
    return this._referenceCompareOn() && this._referenceActive() && this.hasAnyResult();
  }

  /** The active reference's magnitude in dB at fHz. RIAA uses the analytic curve
   *  (normalised to 0 dB at 1 kHz). The ideal filter uses its natural
   *  flooredFilterEvalDb (passband ≈ 0 dB) - NOT referenced to 1 kHz. In both
   *  cases the VIEW adds the measured-@1kHz anchorDb. NaN when no reference is
   *  active / available (FreqRespView.referenceDb). */
  _referenceDb(fHz) {
    const prefs = this.prefs;
    if (prefs.freqRespShowRiaa.get()) {
      return evalDb(fHz, prefs.freqRespReverseRiaa.get(), prefs.freqRespIecAmendment.get());
    }
    if (prefs.freqRespShowFilter.get()) {
      this._refreshFilterDesign();
      if (this._filterDesign == null) return NaN;
      return this._flooredFilterEvalDb(fHz);
    }
    return NaN;
  }

  /** The ideal filter's magnitude in dB at fHz, with a leakage FLOOR added to the
   *  NOTCH null so the (mathematically −∞) tip rounds asymptotically into −A in
   *  POWER - no flat-bottomed plateau (FreqRespView.flooredFilterEvalDb). Precondition:
   *  _filterDesign is non-null. */
  _flooredFilterEvalDb(fHz) {
    let db = this._filterDesign.evalDb(fHz);
    if (this._filterDesign.type === FilterType.NOTCH) {
      const atten = this._filterDesign.stopAttenDb;
      const floorDb = Number.isFinite(atten) ? atten : NOTCH_DISPLAY_FLOOR_DB;
      const powEval = Math.pow(10.0, db / 10.0);
      const powFloor = Math.pow(10.0, -floorDb / 10.0);
      db = 10.0 * Math.log10(powEval + powFloor);
    }
    return db;
  }

  /** (Re)builds _filterDesign from the current filter prefs when the param snapshot
   *  has changed. Invalid params leave _filterDesign == null (FreqRespView.refreshFilterDesign). */
  _refreshFilterDesign() {
    const cur = FilterParams.of(this.prefs);
    if (cur.equals(this._filterParams)) return;
    this._filterParams = cur;
    this._filterDesign = cur.build();
  }

  /** Forces the ideal-filter curve + compare diff to rebuild after a filter param
   *  changed (FreqRespView.invalidateFilterReference). */
  _invalidateFilterReference() {
    this._filterParams = null;
    this._compareDiffCache = null;
    this._invalidateFilterAnchor();   // fc / bandwidth moved -> the anchor region moved
    const prefs = this.prefs;
    if (prefs.freqRespShowFilter.get() && prefs.freqRespFilterCompare.get() && this.hasAnyResult()) {
      this._recomputeCompareAnchor();
    }
  }

  /** Paints the active reference curve (RIAA or ideal filter) as a dashed trace
   *  over the measured response, in the reference's colour. Aligned at 1 kHz to
   *  the measured curve's 1 kHz value (or 0 dB if no measurement). One sample PER
   *  PIXEL column plus the filter's exact critical frequencies forced in as extra
   *  samples so the null / corner renders at its TRUE value regardless of view
   *  width (FreqRespView.drawReferenceOverlay + referenceSampleColumns). */
  _drawReferenceOverlay(g, plot, freqMin, freqMax, magTop, magBot) {
    const anchorDb = this._referenceAnchorDb();
    const color = this._referenceColorRole();
    const lw = this.prefs.freqRespLineWidth.get();
    const yOf = (d) => plot.y + (magTop - d) / (magTop - magBot) * plot.h;   // unclamped; clipped at the frame
    const s = this._referenceSampleColumns(plot, freqMin, freqMax);
    // Build (x, y) samples then order by x. The desktop's ColumnBucketPainter merges
    // same-column samples by min/max so a critical (exact-fc) sample surfaces the TRUE
    // null depth at its column; the web strokes a plain polyline, so ordering by x keeps
    // the trace monotonic left-to-right and the extra exact-fc vertex pulls the path to
    // the true depth within that column (same net shape).
    const pts = new Array(s.xs.length);
    for (let i = 0; i < s.xs.length; i++) {
      pts[i] = { x: s.xs[i], y: yOf(anchorDb + this._referenceDb(s.fs[i])) };
    }
    pts.sort((a, b) => a.x - b.x);
    g.strokeStyle = color; g.lineWidth = lw; g.setLineDash([4, 4]);
    g.beginPath();
    let started = false;
    for (const p of pts) {
      if (Number.isNaN(p.y)) { started = false; continue; }
      started ? g.lineTo(p.x, p.y) : (g.moveTo(p.x, p.y), started = true);
    }
    g.stroke();
    g.setLineDash([]);
  }

  /** Parallel (column, frequency) sample arrays for the reference overlay: one
   *  sample per pixel column (frequency = that pixel's frequency) PLUS the active
   *  filter's critical frequencies as extra samples carrying the EXACT critical
   *  frequency (only when the ideal filter is the reference - RIAA has none). The
   *  critical samples keep their exact frequency for the y-eval so the null / corner
   *  depth is a width-independent constant (FreqRespView.referenceSampleColumns).
   *
   *  <p>The web strokes every sample directly (no ColumnBucketPainter min/max merge),
   *  so a critical sample is emitted as its own path point; at the exact critical
   *  frequency the null depth is drawn true, matching the desktop's surfaced value. */
  _referenceSampleColumns(plot, freqMin, freqMax) {
    const width = Math.max(1, plot.w);
    let crit = [];
    if (this.prefs.freqRespShowFilter.get()) {
      this._refreshFilterDesign();
      if (this._filterDesign != null) crit = this._filterDesign.criticalFrequenciesHz();
    }
    const n = width + 1 + crit.length;
    const xs = new Array(n);
    const fs = new Array(n);
    let m = 0;
    for (let x = 0; x <= width; x++) {
      xs[m] = plot.x + x;
      fs[m] = this._xFractionToFreq(x / width, freqMin, freqMax);
      m++;
    }
    for (const fCrit of crit) {
      const frac = this._freqToXFraction(fCrit, freqMin, freqMax);
      let x = plot.x + Math.round(frac * width);
      xs[m] = Math.max(plot.x, Math.min(plot.x + width, x));
      fs[m] = fCrit;
      m++;
    }
    return { xs, fs };
  }

  /** Vertical alignment level (dB) for the active reference overlay: RIAA keeps
   *  the measured-@1 kHz anchor; the ideal filter uses corner-point anchoring
   *  (FreqRespView.referenceAnchorDb). */
  _referenceAnchorDb() {
    return this.prefs.freqRespShowFilter.get() ? this._filterAnchorDb() : this._riaaAnchorDb();
  }

  /** dB at 1 kHz of the active trace (L if visible, else R, else 0). */
  _riaaAnchorDb() {
    const anchor = this._activeChannelResult();
    if (!anchor) return 0.0;
    const db = this._interpDb(anchor, 1000.0);
    return Number.isFinite(db) ? db : 0.0;
  }

  /** Alignment level (dB) for the ideal-filter overlay: the ideal curve passes
   *  EXACTLY through the measured trace at the filter's corner frequency (NOTCH:
   *  the plateau tip via fcHz). Cached in _filterAnchorCache keyed by the
   *  _filterDesign identity (FreqRespView.filterAnchorDb). */
  _filterAnchorDb() {
    this._refreshFilterDesign();
    if (Number.isFinite(this._filterAnchorCache) && this._filterAnchorCacheDesign === this._filterDesign) {
      return this._filterAnchorCache;
    }
    this._filterAnchorCache = this._computeFilterAnchorDb();
    this._filterAnchorCacheDesign = this._filterDesign;
    return this._filterAnchorCache;
  }

  /** Corner-point anchoring: NOTCH anchors ONLY at fcHz (the leakage-floored tip);
   *  the other types use the design corners then the centre fc as fallback. An
   *  anchor "works" when both the measured value (interpolated on the floating-
   *  average, capped curve) AND the floored filter eval are finite
   *  (FreqRespView.computeFilterAnchorDb). */
  _computeFilterAnchorDb() {
    const r = this._activeChannelResult();
    this._refreshFilterDesign();
    if (r == null || this._filterDesign == null) return 0.0;
    const freqs = r.freqs;
    if (!freqs || freqs.length < 2) return 0.0;
    const sdb = this._floatingAvgCappedDb(r);
    let corners;
    if (this._filterDesign.type === FilterType.NOTCH) {
      corners = [this._filterDesign.fcHz];
    } else {
      const designCorners = this._filterDesign.cornerFrequenciesHz();
      corners = designCorners.slice();
      corners.push(this._filterDesign.fcHz);   // centre fallback
    }
    for (const fCorner of corners) {
      const meas = interpFromArray(freqs, sdb, fCorner);
      if (!Number.isFinite(meas)) continue;
      const evalV = this._flooredFilterEvalDb(fCorner);
      if (!Number.isFinite(evalV)) continue;
      return meas - evalV;
    }
    return 0.0;
  }

  /** Drops the cached per-type filter anchor so the next overlay paint recomputes
   *  it (FreqRespView.invalidateFilterAnchor). */
  _invalidateFilterAnchor() {
    this._filterAnchorCache = NaN;
    this._filterAnchorCacheDesign = null;
  }

  _activeChannelResult() {
    const prefs = this.prefs;
    if (prefs.freqRespLeftVisible.get() && this.leftResult) return this.leftResult;
    if (prefs.freqRespRightVisible.get() && this.rightResult) return this.rightResult;
    if (this.leftResult) return this.leftResult;
    return this.rightResult;
  }

  // ===========================================================================
  // Compare mode - measured − RIAA reference (FreqRespView.getCompareDiff +
  // drawCompareTrace + drawCompareMeasurementTable + autoSetupCompare)
  // ===========================================================================

  /** Single source of truth for compare mode (FreqRespView.getCompareDiff). Builds the
   *  smoothed (measDb − refDb) array at the SIGNAL-POINT level, anchor-subtracts at 1 kHz
   *  so the curve reads 0 dB there, then derives the relative min/max over 20 Hz-25 kHz.
   *  Cached by the (src, reverse, iec, W) tuple so the trace, crosshair Δ, and table all
   *  read identical numbers. Returns {smoothed, minDb, maxDb}. */
  _getCompareDiff(src, reverse, iec) {
    const W = Math.max(0, Math.min(100, this.prefs.freqRespCompareSmoothWindow.get()));
    // The reference source (RIAA vs ideal filter) and the filter's full param tuple are
    // part of the key: swapping source or nudging a filter param yields a different diff
    // even though src/reverse/iec/window are unchanged. filterDesign identity captures the
    // whole filter tuple (rebuilt only when a param moves - FreqRespView.getCompareDiff).
    const filterSrc = this.prefs.freqRespShowFilter.get();
    if (filterSrc) this._refreshFilterDesign();
    if (this._compareDiffCache
        && this._compareDiffCacheSrc === src
        && this._compareDiffCacheReverse === reverse
        && this._compareDiffCacheIec === iec
        && this._compareDiffCacheWindow === W
        && this._compareDiffCacheFilterSrc === filterSrc
        && this._compareDiffCacheFilter === this._filterDesign) {
      return this._compareDiffCache;
    }
    const freqs = src.freqs, magLin = src.magLin;
    const n = freqs.length;
    // 1. Raw (measDb − refDb) per signal point. A non-positive (notch-null) magnitude
    //    maps to a finite -300 dB (mirror FreqRespFormat.linToDb) so it is INCLUDED in
    //    the running mean and drawn, rather than excised as a gap. NaN where the reference
    //    itself is unavailable.
    const raw = new Float64Array(n);
    for (let i = 0; i < n; i++) {
      const measDb = magLin[i] > 0 ? 20 * Math.log10(magLin[i]) : -300;
      if (!Number.isFinite(measDb)) { raw[i] = NaN; continue; }
      const refDb = this._referenceDb(freqs[i]);
      raw[i] = Number.isFinite(refDb) ? measDb - refDb : NaN;
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
    // 4. Real min/max over the anchored array, restricted to 20 Hz-25 kHz.
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
    this._compareDiffCacheFilterSrc = filterSrc;
    this._compareDiffCacheFilter = this._filterDesign;
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
    // Solid trace in the active reference's colour: FILTER_TRACE (lila) when the ideal
    // filter is the reference, else the web's compare magenta (FreqRespView.drawCompareTrace).
    const compareColor = prefs.freqRespShowFilter.get() ? FILTER_TRACE_COLOR : COMPARE_TRACE_COLOR;
    g.strokeStyle = compareColor; g.lineWidth = prefs.freqRespLineWidth.get(); g.setLineDash([]);
    g.beginPath();
    let started = false;
    for (let i = 0; i < freqs.length; i++) {
      const f = freqs[i];
      if (f < freqMin || f > freqMax) continue;
      const v = smoothed[i];
      if (!Number.isFinite(v)) { started = false; continue; }   // NaN -> gap
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
    const x = plot.x + 6, y = OVERLAY_TABLE_TOP, lineH = 14;
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

  /** Compare-mode auto-setup: snaps the horizontal range to 20 Hz-25 kHz and fits the
   *  vertical window to the smoothed-diff envelope with headroom above (FreqRespView.autoSetupCompare). */
  autoSetupCompare() {
    const prefs = this.prefs;
    if (!this._recomputeCompareAnchor()) return;
    // Hug the diff extrema with a symmetric COMPARE_ZOOM_PAD_DB margin in both
    // directions (FreqRespView.autoSetupCompare) - not the generic 20 dB marker
    // headroom, which would waste 20 dB on a ±0.5 dB trace.
    let newTop = this.compareSmoothedMax + COMPARE_ZOOM_PAD_DB;
    let newBot = this.compareSmoothedMin - COMPARE_ZOOM_PAD_DB;
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

  // ===========================================================================
  // Unevenness (response-flatness) readout (FreqRespView unevenness section)
  // ===========================================================================

  /** Recomputes the unevenness readout into _unevenLoHz / _unevenHiHz / _unevenPlusDb
   *  from the active channel, using the curve appropriate to each mode: Mode B + the
   *  notch Mode-A walk read the despiked-raw curve; the peak Mode-A walk reads the
   *  floating-average curve. Both are Nyquist-capped (FreqRespView.recomputeUnevenness).
   *  Sets all fields to NaN when the mode is OFF or no usable data exists. */
  _recomputeUnevenness() {
    const prefs = this.prefs;
    this._unevenLoHz = NaN;
    this._unevenHiHz = NaN;
    this._unevenPlusDb = NaN;
    this._unevenThresholdDb = NaN;
    this._unevenExtremumDb = NaN;
    const mode = prefs.freqRespUnevenMode.get();
    if (mode === UnevenMode.OFF) return;
    const r = this._activeChannelResult();
    if (r == null) return;
    const freqs = r.freqs, mag = r.magLin;
    if (!freqs || !mag || freqs.length < 2) return;
    const n = freqs.length;

    if (mode === UnevenMode.RANGE) {
      // Mode B - min/max + crossing levels read the DESPIKED-RAW curve (raw dB +
      // 3-point median + Nyquist cap), NOT a floating average, so a narrow user range
      // preserves the true wall depth (FreqRespView.recomputeUnevenness RANGE branch).
      const db = this._despikedCappedDb(r);
      const cap = this._analysisNyquistCapHz(r);
      const startHz = prefs.freqRespUnevenStartHz.get();
      const stopHz = Math.min(prefs.freqRespUnevenStopHz.get(), cap);
      let minDb = Infinity, maxDb = -Infinity;
      let loUsed = NaN, hiUsed = NaN;
      for (let i = 0; i < n; i++) {
        const f = freqs[i];
        if (f < startHz || f > stopHz) continue;
        const d = db[i];
        if (!Number.isFinite(d)) continue;
        if (d < minDb) minDb = d;
        if (d > maxDb) maxDb = d;
        if (Number.isNaN(loUsed)) loUsed = f;   // first finite in-range
        hiUsed = f;                             // last finite in-range
      }
      if (!Number.isFinite(minDb) || !Number.isFinite(maxDb)) return;
      this._unevenLoHz = loUsed;
      this._unevenHiHz = hiUsed;
      this._unevenPlusDb = 0.5 * (maxDb - minDb);   // half-span; i18n key carries the ±
      // The Notch checkbox picks the range's extremum of interest for the second green
      // line - the lowest point when checked, the highest otherwise.
      this._unevenExtremumDb = prefs.freqRespUnevenNotch.get() ? minDb : maxDb;
      return;
    }

    // LEVEL mode - the Notch checkbox picks the walk EXPLICITLY. The INITIAL extremum
    // is searched only within the audio band; the walk itself runs the full array under
    // the cap (FreqRespView.recomputeUnevenness LEVEL branch).
    const unevenDb = prefs.freqRespUnevenDb.get();

    if (prefs.freqRespUnevenNotch.get()) {
      // NOTCH walk runs on the RAW dB curve with ONLY a 3-point running MEDIAN despike
      // (any mean flattens a high-Q null). Still Nyquist-capped and audio-band-seeded.
      const ndb = this._despikedCappedDb(r);
      const minIdx = this._audioBandExtremumIdx(freqs, ndb, false);   // interior min in [20, 20k]
      if (minIdx < 0) return;
      const minDb = ndb[minIdx];
      const ceilDb = minDb + unevenDb;
      let lo = minIdx;
      for (let i = minIdx; i >= 0; i--) {
        if (!Number.isFinite(ndb[i]) || ndb[i] > ceilDb) break;
        lo = i;
      }
      let hi = minIdx;
      for (let i = minIdx; i < n; i++) {
        if (!Number.isFinite(ndb[i]) || ndb[i] > ceilDb) break;
        hi = i;
      }
      this._unevenLoHz = freqs[lo];
      this._unevenHiHz = freqs[hi];
      this._unevenPlusDb = unevenDb;
      this._unevenThresholdDb = ceilDb;
      this._unevenExtremumDb = minDb;
      return;
    }

    // PEAK walk runs on the FLOATING-AVERAGE (9-point), Nyquist-capped curve. Peak find
    // (audio band only), then walk out both ways while db ≥ peak − unevenDb.
    const db = this._floatingAvgCappedDb(r);
    const peakIdx = this._audioBandExtremumIdx(freqs, db, true);
    if (peakIdx < 0) return;
    const peakDb = db[peakIdx];
    const floorDb = peakDb - unevenDb;
    let lo = peakIdx;
    for (let i = peakIdx; i >= 0; i--) {
      if (!Number.isFinite(db[i]) || db[i] < floorDb) break;
      lo = i;
    }
    let hi = peakIdx;
    for (let i = peakIdx; i < n; i++) {
      if (!Number.isFinite(db[i]) || db[i] < floorDb) break;
      hi = i;
    }
    this._unevenLoHz = freqs[lo];
    this._unevenHiHz = freqs[hi];
    this._unevenPlusDb = unevenDb;
    this._unevenThresholdDb = floorDb;
    this._unevenExtremumDb = peakDb;
  }

  /** Index of the extreme finite value of db - MAX when wantMax, else MIN - searched
   *  ONLY within the audio band [AUDIO_SEARCH_MIN_HZ, AUDIO_SEARCH_MAX_HZ]. NaN entries
   *  skipped; -1 when the band holds no finite point (FreqRespView.audioBandExtremumIdx). */
  _audioBandExtremumIdx(freqs, db, wantMax) {
    let bestIdx = -1;
    let best = wantMax ? -Infinity : Infinity;
    for (let i = 0; i < db.length; i++) {
      const f = freqs[i];
      if (f < AUDIO_SEARCH_MIN_HZ || f > AUDIO_SEARCH_MAX_HZ) continue;
      const d = db[i];
      if (!Number.isFinite(d)) continue;
      if (wantMax ? d > best : d < best) { best = d; bestIdx = i; }
    }
    return bestIdx;
  }

  /** The passband analysis curve: raw dB -> 9-point FLOATING AVERAGE -> every point at
   *  or above the analysis Nyquist cap forced to NaN (FreqRespView.floatingAvgCappedDb). */
  _floatingAvgCappedDb(r) {
    const freqs = r.freqs, mag = r.magLin;
    const n = freqs.length;
    const raw = new Float64Array(n);
    for (let i = 0; i < n; i++) raw[i] = linToDb(mag[i]);
    const s = this._runningMean(raw, UNEVEN_SMOOTH_POINTS);
    const cap = this._analysisNyquistCapHz(r);
    for (let i = 0; i < n; i++) {
      if (freqs[i] >= cap) s[i] = NaN;
    }
    return s;
  }

  /** The depth-preserving despiked analysis curve: raw dB -> 3-point running MEDIAN ->
   *  analysis-Nyquist cap. Serves notch Mode-A + all of Mode-B (FreqRespView.despikedCappedDb). */
  _despikedCappedDb(r) {
    const freqs = r.freqs, mag = r.magLin;
    const n = freqs.length;
    const raw = new Float64Array(n);
    for (let i = 0; i < n; i++) raw[i] = linToDb(mag[i]);
    const s = this._runningMedian(raw, NOTCH_DESPIKE_POINTS);
    const cap = this._analysisNyquistCapHz(r);
    for (let i = 0; i < n; i++) {
      if (freqs[i] >= cap) s[i] = NaN;
    }
    return s;
  }

  /** window-point (odd) running median of v, centred + end-clamped. Non-finite taps
   *  dropped; no finite tap -> NaN (FreqRespView.runningMedian). */
  _runningMedian(v, window) {
    const n = v.length;
    const half = window >> 1;
    const out = new Float64Array(n);
    const buf = new Float64Array(window);
    for (let i = 0; i < n; i++) {
      let cnt = 0;
      for (let j = Math.max(0, i - half); j <= Math.min(n - 1, i + half); j++) {
        if (Number.isFinite(v[j])) buf[cnt++] = v[j];
      }
      if (cnt === 0) { out[i] = NaN; continue; }
      const w = buf.slice(0, cnt);
      w.sort((a, b) => a - b);
      out[i] = (cnt % 2 === 1) ? w[(cnt / 2) | 0] : 0.5 * (w[cnt / 2 - 1] + w[cnt / 2]);
    }
    return out;
  }

  /** window-point (odd) centred running MEAN of v, end-clamped. Non-finite taps
   *  dropped; no finite tap -> NaN (FreqRespView.runningMean). */
  _runningMean(v, window) {
    const n = v.length;
    const half = window >> 1;
    const out = new Float64Array(n);
    for (let i = 0; i < n; i++) {
      let sum = 0.0, cnt = 0;
      for (let j = Math.max(0, i - half); j <= Math.min(n - 1, i + half); j++) {
        if (Number.isFinite(v[j])) { sum += v[j]; cnt++; }
      }
      out[i] = cnt > 0 ? sum / cnt : NaN;
    }
    return out;
  }

  /** NYQUIST_ANALYSIS_FRACTION × the measured curve's Nyquist; analysis frequencies
   *  at or above this are ignored. Data-derived fallback when the stamped rate is
   *  absent or wrong for the grid (FreqRespView.analysisNyquistCapHz). */
  _analysisNyquistCapHz(r) {
    const sr = r.sampleRate > 0 ? r.sampleRate : this._inputSampleRate();
    const gridTop = this._highestFiniteFreqHz(r);
    const nyqCap = NYQUIST_ANALYSIS_FRACTION * sr * 0.5;
    if (sr <= 0 || (Number.isFinite(gridTop) && gridTop > sr * 0.5)) {
      return Number.isFinite(gridTop) ? NYQUIST_ANALYSIS_FRACTION * gridTop : nyqCap;
    }
    return nyqCap;
  }

  /** The highest frequency in r's grid backed by a finite, positive magnitude - the
   *  real top of the loaded/measured span. NaN when none (FreqRespView.highestFiniteFreqHz). */
  _highestFiniteFreqHz(r) {
    const freqs = r.freqs, mag = r.magLin;
    if (!freqs || !mag) return NaN;
    for (let i = freqs.length - 1; i >= 0; i--) {
      if (Number.isFinite(mag[i]) && mag[i] > 0.0 && Number.isFinite(freqs[i])) {
        return freqs[i];
      }
    }
    return NaN;
  }

  /** The unevenness readout string for the active mode, or null when no result exists.
   *  Shared by the in-canvas table + the external window (FreqRespView.unevennessReadout). */
  _unevennessReadout() {
    if (!Number.isFinite(this._unevenPlusDb)) return null;
    const db = formatDbReadout(this._unevenPlusDb);
    const lo = formatHzReadout(this._unevenLoHz);
    const hi = formatHzReadout(this._unevenHiHz);
    return this.prefs.freqRespUnevenMode.get() === UnevenMode.RANGE
      ? t('freqResp.uneven.readout.range', db, lo, hi)
      : t('freqResp.uneven.readout', lo, hi, db);
  }

  /** Paints the unevenness readout below the header buttons. When the compare table
   *  is visible it stacks below it (FreqRespView.drawUnevennessTable). */
  _drawUnevennessTable(g, plot, belowCompareTable) {
    const text = this._unevennessReadout();
    if (text == null) return;
    g.font = '11px "Segoe UI", sans-serif';
    g.textBaseline = 'top'; g.textAlign = 'left';
    g.fillStyle = '#222';
    const lineH = 14;
    const x = plot.x + 6;
    const y = OVERLAY_TABLE_TOP + (belowCompareTable ? 2 * lineH + 4 : 0);
    g.fillText(text, x, y);
  }

  /** Draws the LEVEL / RANGE unevenness annotations, only while a Mode readout is
   *  active (_unevenPlusDb finite). GREEN = the reference (RIAA) colour role; LILA =
   *  the FILTER colour; every line dotted at the trace line width
   *  (FreqRespView.drawUnevennessAnnotations). */
  _drawUnevennessAnnotations(g, plot, freqMin, freqMax, magTop, magBot) {
    if (!Number.isFinite(this._unevenPlusDb)) return;
    const green = colorHex(this.prefs.freqRespReferenceColor.get());
    const lila = FILTER_TRACE_COLOR;
    const lw = this.prefs.freqRespLineWidth.get();
    g.save();
    g.beginPath();
    g.rect(plot.x, plot.y, plot.w, plot.h);
    g.clip();
    g.lineWidth = lw;
    g.setLineDash([1, 3]);   // SWT.LINE_DOT
    if (this.prefs.freqRespUnevenMode.get() === UnevenMode.RANGE) {
      this._drawUnevennessModeB(g, plot, freqMin, freqMax, magTop, magBot, green, lila);
    } else {
      this._drawUnevennessModeA(g, plot, freqMin, freqMax, magTop, magBot, green, lila);
    }
    g.setLineDash([]);
    g.restore();
  }

  _drawUnevennessModeA(g, plot, freqMin, freqMax, magTop, magBot, green, lila) {
    if (!Number.isFinite(this._unevenLoHz) || !Number.isFinite(this._unevenHiHz)
        || !Number.isFinite(this._unevenThresholdDb)) {
      return;
    }
    const xLo = this._freqToX(this._unevenLoHz, plot, freqMin, freqMax);
    const xHi = this._freqToX(this._unevenHiHz, plot, freqMin, freqMax);
    const yTh = Math.round(this._dbToY(this._unevenThresholdDb, plot, magTop, magBot));
    // Green horizontal at the threshold, between the two boundaries.
    g.strokeStyle = green;
    g.beginPath(); g.moveTo(xLo, yTh); g.lineTo(xHi, yTh); g.stroke();
    // Second green horizontal at the walk's reference extremum, same span.
    if (Number.isFinite(this._unevenExtremumDb)) {
      const yEx = Math.round(this._dbToY(this._unevenExtremumDb, plot, magTop, magBot));
      g.beginPath(); g.moveTo(xLo, yEx); g.lineTo(xHi, yEx); g.stroke();
    }
    // Lila verticals at each boundary, full plot height.
    g.strokeStyle = lila;
    g.beginPath(); g.moveTo(xLo, plot.y); g.lineTo(xLo, plot.y + plot.h); g.stroke();
    g.beginPath(); g.moveTo(xHi, plot.y); g.lineTo(xHi, plot.y + plot.h); g.stroke();
  }

  _drawUnevennessModeB(g, plot, freqMin, freqMax, magTop, magBot, green, lila) {
    if (!Number.isFinite(this._unevenLoHz) || !Number.isFinite(this._unevenHiHz)) return;
    const startHz = this._unevenLoHz;
    const stopHz = this._unevenHiHz;
    const xStart = this._freqToX(startHz, plot, freqMin, freqMax);
    const xStop = this._freqToX(stopHz, plot, freqMin, freqMax);
    // Lila verticals at start + stop, full plot height.
    g.strokeStyle = lila;
    g.beginPath(); g.moveTo(xStart, plot.y); g.lineTo(xStart, plot.y + plot.h); g.stroke();
    g.beginPath(); g.moveTo(xStop, plot.y); g.lineTo(xStop, plot.y + plot.h); g.stroke();
    // Second green horizontal - the range's extremum of interest between the boundaries.
    if (Number.isFinite(this._unevenExtremumDb)) {
      g.strokeStyle = green;
      const yExt = Math.round(this._dbToY(this._unevenExtremumDb, plot, magTop, magBot));
      g.beginPath(); g.moveTo(xStart, yExt); g.lineTo(xStop, yExt); g.stroke();
    }
    // Crossing levels where each vertical meets the DESPIKED-RAW, capped curve.
    const src = this._activeChannelResult();
    if (src == null) return;
    const freqs = src.freqs;
    const sdb = this._despikedCappedDb(src);
    const dbStart = interpFromArray(freqs, sdb, startHz);
    const dbStop = interpFromArray(freqs, sdb, stopHz);
    if (!Number.isFinite(dbStart) || !Number.isFinite(dbStop)) return;
    g.strokeStyle = green;
    if (Math.abs(dbStart - dbStop) <= UNEVEN_LEVEL_EPS_DB) {
      // Equal levels -> one full-width green horizontal at that level.
      const y = Math.round(this._dbToY(0.5 * (dbStart + dbStop), plot, magTop, magBot));
      g.beginPath(); g.moveTo(plot.x, y); g.lineTo(plot.x + plot.w, y); g.stroke();
    } else {
      const yStart = Math.round(this._dbToY(dbStart, plot, magTop, magBot));
      const yStop = Math.round(this._dbToY(dbStop, plot, magTop, magBot));
      g.beginPath(); g.moveTo(xStart - UNEVEN_TICK_HALF_PX, yStart); g.lineTo(xStart + UNEVEN_TICK_HALF_PX, yStart); g.stroke();
      g.beginPath(); g.moveTo(xStop - UNEVEN_TICK_HALF_PX, yStop); g.lineTo(xStop + UNEVEN_TICK_HALF_PX, yStop); g.stroke();
    }
  }

  /** Integer canvas x for frequency f (Java freqToX with round=true). */
  _freqToX(f, plot, freqMin, freqMax) {
    return plot.x + Math.round(this._freqToXFraction(f, freqMin, freqMax) * plot.w);
  }

  /** Y for a dB value (Java dbToYf) - unclamped; clipped at the frame. */
  _dbToY(d, plot, magTop, magBot) {
    return plot.y + (magTop - d) / (magTop - magBot) * plot.h;
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
    const compareActive = this._compareActive();
    if (compareActive) {
      // Compare mode draws the smoothed (measured − reference) curve - interpolate the
      // SAME anchor-shifted array so the Δ readout agrees with what's on screen.
      const src = this._activeChannelResult();
      if (src) {
        const diff = this._getCompareDiff(src, prefs.freqRespReverseRiaa.get(), prefs.freqRespIecAmendment.get());
        const s = interpFromArray(src.freqs, diff.smoothed, cursorFreq);
        if (Number.isFinite(s)) lines.push('Δ = ' + formatDbReadout(s));
      }
    } else {
      // The crosshair reads the DRAWN curve (Java FreqRespView.drawnDb): while the Lanczos
      // reconstruction is active a deep null is drawn below its neighbouring bins, and a readout
      // interpolated between those bins would disagree with the trace under the cursor.
      if (prefs.freqRespLeftVisible.get() && this.leftResult) {
        lines.push('L = ' + formatDbReadout(this.drawnDb(this.leftResult, cursorFreq)));
      }
      if (prefs.freqRespRightVisible.get() && this.rightResult) {
        lines.push('R = ' + formatDbReadout(this.drawnDb(this.rightResult, cursorFreq)));
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

  /**
   * The magnitude the trace is DRAWN with at frequency `f`, in dB - the per-pixel Lanczos
   * reconstruction while smoothing is active, else the bin interpolation (faithful port of
   * FreqRespView.drawnDb). The crosshair and the tune-notch marker read THIS, not the raw bins:
   * a deep null is drawn as a smooth rounded dip that goes BELOW its neighbouring bins, so a
   * readout taken off the bins disagrees with the curve the operator is looking at.
   *
   * @param {object} r the FreqRespResult
   * @param {number} f frequency (Hz)
   * @returns {number} dB, or NaN outside the measured span
   */
  drawnDb(r, f) {
    const freqs = r && r.freqs, mag = r && r.magLin;
    if (!freqs || !mag || freqs.length < 2) return NaN;
    if (f < freqs[0] || f > freqs[freqs.length - 1]) return NaN;
    const plot = this._plotRect();
    if (plot) {
      const scale = this._lanczosScale(freqs, this.prefs.freqRespFreqMinHz.get(),
        this.prefs.freqRespFreqMaxHz.get(), plot.w);
      if (scale > 0) {
        // The SAME kernel the painter feeds (lanczosNaN, on magLin), so the readout is the
        // drawn value and not a second reconstruction that could differ.
        const v = lanczosNaN(mag, freqs.length, this._fracIndex(freqs, f), scale);
        return 20 * Math.log10(Math.max(LANCZOS_MAG_FLOOR_LIN, v));
      }
    }
    return this._interpDb(r, f);
  }

  /**
   * The lowest point of the DRAWN curve inside the visible window: [freqHz, db], or null when
   * there is nothing to scan (faithful port of FreqRespView.drawnMinimum).
   *
   * Two regimes, because the painter has two: while the Lanczos reconstruction is active it
   * samples one value per pixel COLUMN, so the drawn minimum is found by scanning exactly those
   * columns. Past MAX_LANCZOS_DOWNSAMPLE the painter draws the RAW bins as polyline vertices -
   * there the drawn tip IS the bin minimum, and a pixel-grid sample between two vertices reads
   * shallower - which is how the padding below the notch came out short at high point
   * counts.
   *
   * @param {object} r the FreqRespResult
   * @returns {?number[]} [freqHz, db]
   */
  drawnMinimum(r) {
    const plot = this._plotRect();
    if (!plot || plot.w < 2) return null;
    const freqs = r && r.freqs, mag = r && r.magLin;
    if (!freqs || !mag || freqs.length < 2) return null;
    const fMin = this.prefs.freqRespFreqMinHz.get();
    const fMax = this.prefs.freqRespFreqMaxHz.get();
    let bestF = NaN;
    let bestDb = Infinity;
    if (this._lanczosScale(freqs, fMin, fMax, plot.w) > 0) {
      for (let i = 0; i < plot.w; i++) {
        const f = this._xFractionToFreq(i / plot.w, fMin, fMax);
        const db = this.drawnDb(r, f);
        if (!Number.isNaN(db) && db < bestDb) { bestDb = db; bestF = f; }
      }
    } else {
      const lo = this._indexBelow(freqs, fMin);
      const hi = Math.min(freqs.length - 1, this._indexBelow(freqs, fMax) + 1);
      for (let i = lo; i <= hi; i++) {
        if (freqs[i] < fMin || freqs[i] > fMax) continue;
        const db = 20 * Math.log10(Math.max(LANCZOS_MAG_FLOOR_LIN, mag[i]));
        if (db < bestDb) { bestDb = db; bestF = freqs[i]; }
      }
    }
    return Number.isNaN(bestF) ? null : [bestF, bestDb];
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
  // Selections live inside the plot area - zoomableArea = _plotRect().

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

  /** Log-domain on X, linear-dB on Y - the crosshair / wheel-zoom mappings.
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
    if (this._compareActive()) {
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
    // Compare mode draws the diff curve, not the raw traces - keep the headroom above the
    // compared-signal peak (compareSmoothedMax), not leftResult/right (FreqRespView.softMagTopDb).
    if (this._compareActive()) {
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

/** Wraps a radian angle to (−π, π] - used to unwrap the phase before Lanczos and
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

function formatDbReadout(db) {
  if (!Number.isFinite(db)) return '-';
  return formatSignificant(db, 4) + ' dB';
}

function formatPhaseReadout(deg) {
  if (!Number.isFinite(deg)) return '-';
  return formatSignificant(deg, 4) + '°';
}

/** Linear magnitude -> dB, non-positive -> -300 (FreqRespFormat.linToDb). */
function linToDb(linear) {
  return linear > 0.0 ? 20.0 * Math.log10(linear) : -300.0;
}

/** Compact frequency label for the unevenness readout: sub-kHz values read in whole
 *  Hz ("20 Hz"), ≥ 1 kHz values switch to kHz with up to three significant fraction
 *  digits and no trailing zeros (FreqRespFormat.formatHzReadout). */
function formatHzReadout(hz) {
  if (!Number.isFinite(hz) || hz < 0.0) return '-';
  if (hz < 1000.0) {
    return hz.toFixed(0) + ' Hz';
  }
  let s = (hz / 1000.0).toFixed(3);
  if (s.indexOf('.') >= 0) {
    s = s.replace(/0+$/, '').replace(/\.$/, '');
  }
  return s + ' kHz';
}

/** Log-frequency linear interpolation of a value array aligned 1:1 with a freq grid
 *  (e.g. the cached smoothed-diff array). NaN when f is outside the grid or either
 *  bracketing sample is NaN. Mirrors FreqRespView.interpFromArray. */
function interpFromArray(freqs, vals, f) {
  if (!freqs || !vals || freqs.length < 2) return NaN;
  if (f < freqs[0] || f > freqs[freqs.length - 1]) return NaN;
  let lo = 0, hi = freqs.length - 1;
  while (hi - lo > 1) { const mid = (lo + hi) >>> 1; if (freqs[mid] <= f) lo = mid; else hi = mid; }
  const v0 = vals[lo];
  // Exact grid hit -> the left sample IS the answer; don't require the right neighbour
  // (it may be NaN at the Nyquist-analysis cap boundary). Mirrors FreqRespView.interpFromArray.
  if (freqs[lo] === f) return v0;
  const v1 = vals[hi];
  if (Number.isNaN(v0) || Number.isNaN(v1)) return NaN;
  const frac = (Math.log(f) - Math.log(freqs[lo])) / (Math.log(freqs[hi]) - Math.log(freqs[lo]));
  return v0 + frac * (v1 - v0);
}

/** Immutable snapshot of every filter pref that shapes the ideal-filter curve. Equality
 *  drives the FilterDesign rebuild and extends the compare-diff cache key. build() maps
 *  the active mode (spec vs order) to the matching FilterDesign factory. Faithful port of
 *  FreqRespView.FilterParams. */
class FilterParams {
  constructor(type, response, modeOrder, rippleDb, stopAttenDb, centerHz, passHz, stopHz,
              orderPassHz, orderRippleDb, order, q) {
    this.type = type;
    this.response = response;
    this.modeOrder = modeOrder;
    this.rippleDb = rippleDb;
    this.stopAttenDb = stopAttenDb;
    this.centerHz = centerHz;
    this.passHz = passHz;
    this.stopHz = stopHz;
    this.orderPassHz = orderPassHz;
    this.orderRippleDb = orderRippleDb;
    this.order = order;
    this.q = q;
  }

  /** The per-type params map is the single source of truth for every scalar; the type +
   *  response selectors stay their own prefs (FreqRespView.FilterParams.of). */
  static of(p) {
    const type = p.freqRespFilterType.get();
    const fp = p.getFreqRespFilterParams(type);
    return new FilterParams(
      type, p.freqRespFilterResponse.get(),
      fp.modeOrder,
      fp.rippleDb, fp.stopAttenDb,
      fp.centerHz, fp.passHz,
      fp.stopHz,
      fp.orderPassHz, fp.orderRippleDb,
      fp.order, fp.q);
  }

  /** Value equality across every scalar (Lombok @EqualsAndHashCode in Java). */
  equals(o) {
    return o != null
      && this.type === o.type && this.response === o.response
      && this.modeOrder === o.modeOrder
      && this.rippleDb === o.rippleDb && this.stopAttenDb === o.stopAttenDb
      && this.centerHz === o.centerHz && this.passHz === o.passHz && this.stopHz === o.stopHz
      && this.orderPassHz === o.orderPassHz && this.orderRippleDb === o.orderRippleDb
      && this.order === o.order && this.q === o.q;
  }

  /** Builds the design for the active mode, or null when the factory rejects the params
   *  (out-of-range spec, degenerate band) (FreqRespView.FilterParams.build). */
  build() {
    try {
      return this.modeOrder
        ? FilterDesign.ofOrder(this.type, this.response, this.order, this.orderRippleDb, this.orderPassHz, this.q)
        : FilterDesign.ofSpec(this.type, this.response, this.rippleDb, this.stopAttenDb,
            this.centerHz, this.passHz, this.stopHz);
    } catch (ex) {
      return null;
    }
  }
}
