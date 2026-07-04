/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Triggered time-domain scope on a Canvas2D. Uses the faithful scope DSP ports:
// half-amplitude-level Schmitt trigger (scope/scope-trigger.find), band-limited
// Lanczos dot reconstruction (dsp/lanczos.lanczos), and the Vpp/Vrms/freq/period/
// duty readout (scope/signal-measurements.compute). In dual-tone mode it triggers
// on the reconstructed |F1-F2| beat envelope and measures with the time-domain
// fields suppressed. Driven per capture chunk, independent of the FFT.

import { find, findGlitch } from '../scope/scope-trigger.js';
import { TimeDiscontinuityDetector } from '../dsp/time-discontinuity.js';
import { lanczos, LANCZOS_PADDING, MAX_LANCZOS_DOWNSAMPLE } from '../dsp/lanczos.js';
import { compute, withFrequency, withoutTimes, refineFrequencyAround, reconstructBeatSignal,
         MeasurementStats, WindowedSignalAccumulator, forVolts, forTime, forFreq, forPct }
  from '../scope/signal-measurements.js';
import { FreqScanClient } from '../scope/freq-scan.js';
import { LowPassFilter, MedianFilter } from '../dsp/lpf.js';
import { mainsFilterOf } from '../dsp/mains/factory.js';
// Pan/zoom engine + the pure nav-math helpers + the trigger-slider enum — the single
// home for every horizontal/vertical move/zoom transform (Java ScopeNav / ScopeFormat /
// OscSliderId, OscTriggerMode/Edge). The wheel + on-canvas-slider handlers delegate here
// instead of re-deriving the math inline.
import { ScopeNav, DIVISIONS_X as NAV_DIVISIONS_X, DIVISIONS_Y as NAV_DIVISIONS_Y } from '../scope/scope-nav.js';
import { clamp01 as navClamp01 } from '../scope/scope-format.js';
import { OscSliderId, TriggerMode, TriggerEdge, TriggerType } from '../scope/scope-enums.js';
// Drag-select rectangular zoom + Ctrl+Z undo (Java AbstractMeasurementView's
// installRectZoom base machinery); the scope supplies the time/volts mappings +
// trigger preservation (Java ScopeView zoom overrides) and forwards its own
// pointer funnel (hookMouse = false, like Java's installRectZoom(this, false)).
import { RectZoom } from './rect-zoom.js';

// Grid divisions (the ½-div wheel step + the V/div ladder math now live in ScopeNav).
const DIVISIONS_X = NAV_DIVISIONS_X;
const DIVISIONS_Y = NAV_DIVISIONS_Y;
const REL_EPS = 1e-9;
const clamp01 = navClamp01;

// ScopeView render-consumption constants (mirroring the Java ScopeView).
const SCOPE_HF_LPF_ORDER = 8;        // LowPassFilter order for LpfMode.HZ_80
// Sub-sample step for the sin(x)/x peak refinement in the envelope path (Java
// ScopeView.RECON_REFINE_STEP).
const RECON_REFINE_STEP = 0.1;
const MAINS_NOTCH_BW_HZ = 2.0;       // −3 dB notch width for the scope mains comb
// Half-width (Hz) of the raw-signal band used to re-pin the comb-located tone's
// frequency (Java ScopeMeasurementWorker.FREQ_REFINE_HALF_HZ): wide enough to
// cover the comb's frequency pull, far narrower than the ≥ ~50 Hz spacing of
// mains harmonics so none competes.
const FREQ_REFINE_HALF_HZ = 2.0;
// Graticule cross-hair (Java CrossHairSpec): 5 sub-ticks per division, ±4 px arms.
const TICKS_PER_DIV = 5;
const TICK_HALF_LEN = 4;
// Trace edge overhang (Java TRACE_EDGE_OVERHANG_PX): anchors pushed outside the
// canvas so the stroke caps render at full edge intensity.
const TRACE_EDGE_OVERHANG_PX = 4;
// Measurement-table rebuild throttle (Java READOUT_THROTTLE_NS = 200 ms).
const READOUT_THROTTLE_MS = 200;
const NS_PER_MS = 1e6;
// Minimum glitch-mode collection time before the cumulative rate is shown — below
// this, count ÷ elapsed is dominated by start-up jitter, so read 0 (Java
// ScopeView.GLITCH_RATE_MIN_SECONDS).
const GLITCH_RATE_MIN_SECONDS = 1.0;

// On-canvas drag-handle geometry (Java ScopeView SLIDER_TRI_LONG / SLIDER_TRI_HALF /
// SLIDER_GRAB_HALF): a triangle that points into the grid from the edge (now drawn as
// a fixed-size CSS border-triangle in the overlay; the triangle's px size lives in
// .scope-ovl-tri-* in app.css), with a hit zone that extends past it in the
// perpendicular direction. These px values still define the canvas-space hit-boxes.
const SLIDER_TRI_LONG = 10;    // triangle long axis (into the grid)
const SLIDER_GRAB_HALF = 9;    // hit-zone half-thickness perpendicular to the edge

// Toolbar-field floors (Java ScopeTabControl.T_PER_DIV_MIN / V_PER_DIV_MIN; the
// web NumericStepModels in app.js initStepFields carry the same min values) —
// the rect zoom clamps its t/div + V/div writes to the same floors, or the
// fields' re-sync would desync pref and display.
const T_PER_DIV_MIN = 1e-6;
const V_PER_DIV_MIN = 1e-9;

/** Packs an int RGB (0xRRGGBB, as the channel-colour prefs store it) into a
 *  CSS '#rrggbb' string (Java color() role lookup → hex). */
function colorHex(rgb) {
  return '#' + (rgb & 0xffffff).toString(16).padStart(6, '0');
}

/** Scales each 8-bit channel of an int RGB by `factor` (Java
 *  AbstractMeasurementView.attenuate) and returns the '#rrggbb' string — used
 *  for the dimmed (0.45) reconstructed-beat colour. */
function attenuateHex(rgb, factor) {
  const clamp8 = (v) => (v < 0 ? 0 : v > 0xff ? 0xff : v);
  const r = clamp8(Math.round(((rgb >> 16) & 0xff) * factor));
  const g = clamp8(Math.round(((rgb >> 8) & 0xff) * factor));
  const b = clamp8(Math.round((rgb & 0xff) * factor));
  return colorHex((r << 16) | (g << 8) | b);
}

/** Linear interpolation of `data` at fractional sample position `pos`, clamped to
 *  the array ends (Java ScopeView.lerpAt). The sin-x/x-off trace branch samples
 *  one interpolated value per pixel column with this. */
function lerpAt(data, n, pos) {
  if (pos <= 0) return data[0];
  if (pos >= n - 1) return data[n - 1];
  const i0 = Math.trunc(pos);
  const f = pos - i0;
  return data[i0] * (1.0 - f) + data[i0 + 1] * f;
}

export class ScopeView {
  constructor(canvas, { prefs = null } = {}) {
    this.cv = canvas; this.g = canvas.getContext('2d');
    this.prefs = prefs;
    // The pan/zoom engine — every horizontal/vertical move + zoom transform (Java
    // ScopeView.nav). Stateless apart from the grid divisions + the V/div ladder, so the
    // wheel + on-canvas-slider handlers share this one instance. The ladder arrives on
    // this.vDivSeries from the shell AFTER construction, so it is wired in lazily via
    // nav() (the engine reads the ladder live each call, so a late assignment is fine).
    this._nav = new ScopeNav(DIVISIONS_X, DIVISIONS_Y, null);
    // CSS-box resize → re-render the current frame so the DPR-scaled backing
    // store is rebuilt at the new size. The live capture loop already repaints
    // every frame, but a LOADED / STOPPED / frozen view has no loop, so without
    // this the browser just stretches the stale backing store (unproportional).
    // Debounced through rAF; observes the CSS box (cv.width changes don't fire it).
    this._resizeObs = (typeof ResizeObserver !== 'undefined') ? new ResizeObserver(() => {
      if (this._resizePending) return;
      this._resizePending = true;
      requestAnimationFrame(() => {
        this._resizePending = false;
        if (this._lastInfo) this.render(this._lastBuf, this._lastInfo);
        else this.renderIdle();   // no live/held/loaded frame yet → keep the empty grid + marks painted at the new size
      });
    }) : null;
    if (this._resizeObs) this._resizeObs.observe(this.cv);
    this._beatScratch = {};   // grow-only reconstructBeatSignal scratch
    // Trigger-mode state machine (Java ScopeView). SINGLE: armed → waiting for
    // one trigger, held → its captured frame stays frozen. NORMAL: holds the
    // last triggered frame when triggers stop. The frozen frame is a snapshot
    // (this._frame) so the rolling ring scrolling out doesn't drift it.
    this._singleArmed = false;
    this._singleHeld = false;
    this._lastTriggerMode = null;
    this._normalFrame = null;   // truthy = a NORMAL frame is held; points at this._frame
    this._frame = null;         // captured snapshot used by _drawHeldFrame
    this._snapBufs = [];        // grow-only reused snapshot buffers (one per chan + beat)
    // Per-channel HF-cleanup + mains filters, lazily (re)built for the live
    // sample rate; reset/applied each render over each channel's copied buffer.
    // Keyed by channel name ('Left'/'Right') so L and R don't clobber each
    // other's filter state / scratch when both are drawn. The measurement pass
    // lazily adds its own 'LeftMeas'/'RightMeas' bags (Java's worker owns its
    // own filters, and the adaptive mains cancellers are stateful).
    this._chanFilt = {
      Left:  { lpf: null, lpfMode: null, lpfRate: 0, mains: null, mainsMode: null, mainsRate: 0, trackT0: 0, buf: null },
      Right: { lpf: null, lpfMode: null, lpfRate: 0, mains: null, mainsMode: null, mainsRate: 0, trackT0: 0, buf: null },
    };
    // File-load mode (Java ScopeView.isFileMode): a static loaded signal free-runs
    // with no trigger search and no trigger overlay. Set by the shell on load.
    // Exposed through the fileMode accessor below (Java setFileMode), which clears
    // the rect-zoom undo history on every mode switch AND on a new file loading
    // over an old one — the stacked mementos belong to the previous timeline.
    this._fileMode = false;
    // Loaded-file name to stamp STATICALLY on the canvas — set ONLY on the
    // offscreen screenshot clone (Java ScopeView.drawFilePath shows the loaded
    // filename top-right in the captured image). null on the live view, whose
    // filename label is the self-blinking DOM banner (#scopeFileBanner); the
    // capture must show it as a non-blinking static label (C29b).
    this.screenshotFilePath = null;
    // Measurement-table rolling stats (Java MeasurementStats per quantity, fed
    // from the measurement channel; rebuilt at READOUT_THROTTLE_MS over
    // oscMeasurementAverageSeconds). measurementRows = 8 formatted rows.
    this._measStats = {
      vpp: new MeasurementStats(), vrms: new MeasurementStats(), vmean: new MeasurementStats(),
      period: new MeasurementStats(), riseTime: new MeasurementStats(), fallTime: new MeasurementStats(),
      frequency: new MeasurementStats(), dutyCycle: new MeasurementStats(),
    };
    // Raw-sample pool feeding Vmean/Vrms/Vpp over a long EFFECTIVE window (=
    // oscMeasurementAverageSeconds), assembled across capture buffers so their σ stops
    // tracking the per-buffer fractional-cycle DC swing. See WindowedSignalAccumulator.
    this._ampPool = new WindowedSignalAccumulator();
    this._poolFilt = {};        // persistent (no-reset) streaming HF-LPF + comb per channel
    this._poolScratch = null;   // reused Float32 copy of the gap (engine buffer not mutated)
    this._lastMeasBuildMs = 0;
    this._lastLiveMeasMs = 0;   // throttle for the decoupled live-window measurement (C24)
    this.measurementRows = null;
    // cap/s readout string cache (Java drawCaptureRate throttles the
    // String.format to READOUT_THROTTLE_NS = 200 ms; drawText still runs every paint).
    this._capsString = '';
    this._lastCapsBuildMs = 0;
    // Actual capture/trigger rate (Java captureRate + lastNewFrameNanos): EMA over
    // the intervals between genuinely-new frames (a fresh trigger / AUTO free-run),
    // decaying toward zero while a frame is held — so a NORMAL/SINGLE hold reads the
    // real trigger rate (~0.2 cap/s when a frame lands every few seconds), never the
    // ~60 paint-loop rate. NOT info.scopeFps, which counts every paint iteration.
    this._captureRate = 0;
    this._lastNewFrameMs = 0;
    // Glitch-mode capture-rate state (Java glitchCountStartNanos / glitchCount /
    // rateWasGlitchMode): rare events make a per-interval EMA useless, so the readout
    // is total caught ÷ collection time. Counting restarts when glitch mode is entered,
    // a new record begins (restartGlitchRate) or the trigger source changes
    // (resetTriggerHold).
    this._glitchCountStartMs = 0;
    this._glitchCount = 0;
    this._rateWasGlitchMode = false;
    // Canvas X to anchor the next held-frame t/div change around, or -1 to anchor on
    // the screen centre (Java ScopeView.heldZoomAnchorX): the wheel-zoom sets it
    // (cursor); a t/div FIELD change leaves it -1 so the trace stays centred instead
    // of jumping to the stale cursor position. Consumed by _drawHeldFrame.
    this._heldZoomAnchorX = -1;
    // On-canvas drag-handle hit-boxes (Java ScopeView offsetSliderBounds /
    // triggerLevelBounds / triggerPosBounds) — refreshed every render in
    // _drawSliders, consumed by the mouse handlers. {x,y,w,h} in canvas px.
    this._offsetBounds = { x: 0, y: 0, w: 0, h: 0 };
    this._triggerLevelBounds = { x: 0, y: 0, w: 0, h: 0 };
    this._triggerPosBounds = { x: 0, y: 0, w: 0, h: 0 };
    this._draggingSlider = null;   // 'OFFSET' | 'TRIGGER_LEVEL' | 'TRIGGER_POSITION' | null
    // DOM overlay layer for the FIXED-size text (cap/s readout + V/time edge
    // labels). These must NOT be baked into the trace bitmap, so a screenshot that
    // stretches the trace canvas leaves the text at its on-screen pixel size. The
    // layer is an absolutely-positioned <div> over the canvas (its parent is the
    // positioned .canvas-wrap); persistent label <div>s are created once and
    // repositioned every render. A DETACHED canvas (the screenshot clone) has no
    // useful parent — its overlay just stays null and nothing is drawn (the clone
    // carries the live layer's already-positioned labels).
    this._overlay = this._buildOverlayLayer(canvas);
    // Drag-select zoom + Ctrl+Z undo (Java ScopeView: installRectZoom(this, false)
    // — the rect zoom feeds off the pointer funnel below, so hookMouse stays off).
    this._rectZoom = canvas ? new RectZoom(canvas, {
      hookMouse: false,
      captureState: () => this._captureZoomState(),
      applyState: (s) => this._applyZoomState(s),
      stateForRect: (sel) => this._zoomStateForRect(sel),
      zoomableArea: () => this._zoomableArea(),
      isBlockedAt: (x, y) => this._isRectZoomBlockedAt(x, y),
      repaintOverlay: () => this._repaintZoomOverlay(),
    }) : null;
    if (canvas) {
      canvas.addEventListener('wheel', (e) => this._onWheel(e), { passive: false });
      canvas.addEventListener('mousedown', (e) => this._onSliderMouseDown(e));
      canvas.addEventListener('dblclick', (e) => this._onSliderDblClick(e));
      // Drag + hover live on the window so a fast drag that leaves the canvas
      // keeps tracking (Java MouseMoveListener on the canvas, but the SWT canvas
      // captures the pointer during a button-down drag — emulate with window).
      window.addEventListener('mousemove', (e) => this._onSliderMouseMove(e));
      window.addEventListener('mouseup', () => this._onSliderMouseUp());
    }
  }

  /** File-load mode accessor (Java ScopeView.setFileMode): a mode switch
   *  invalidates every stacked zoom state — live mementos are trigger-relative
   *  seconds, file mementos absolute frames. A true→true set is a NEW file
   *  loading over the old one: its mementos hold frames of the previous file's
   *  timeline, equally meaningless. */
  get fileMode() { return this._fileMode; }
  set fileMode(v) {
    if ((v || this._fileMode) && this._rectZoom) this._rectZoom.clearHistory();
    this._fileMode = !!v;
  }

  /** Builds the DOM overlay layer that carries the FIXED-size text (cap/s readout
   *  + V/time edge labels) over the canvas. The layer is an absolutely-positioned
   *  <div> inside the canvas's parent (.canvas-wrap, a positioned containing block),
   *  inset to overlap the canvas exactly; pointer-events:none so it never steals the
   *  slider drags. Returns null for a detached / parentless canvas (the screenshot
   *  clone), where no live layer exists to build into. */
  _buildOverlayLayer(canvas) {
    if (!canvas || !canvas.parentNode || typeof document === 'undefined') return null;
    const layer = document.createElement('div');
    layer.className = 'scope-overlay';
    const make = (cls) => {
      const el = document.createElement('div');
      el.className = 'scope-ovl-label ' + cls;
      layer.appendChild(el);
      return el;
    };
    // A slider line is a black UNDERLAY dashed line with the bright COLOURED dashed
    // core on top (faithful to the canvas two-stroke outline), plus a CSS-border
    // triangle HANDLE at the line's edge. The line core/underlay + handle are FIXED
    // px thick/sized but positioned by PERCENT so they track the resizable view (and
    // a stretched screenshot leaves them at their on-screen pixel size).
    const makeSlider = (lineCls, handleCls) => {
      const under = document.createElement('div');
      under.className = 'scope-ovl-slider scope-ovl-sliderline ' + lineCls + ' scope-ovl-sliderunder';
      const core = document.createElement('div');
      core.className = 'scope-ovl-slider scope-ovl-sliderline ' + lineCls + ' scope-ovl-slidercore';
      const handle = document.createElement('div');
      handle.className = 'scope-ovl-slider scope-ovl-sliderhandle ' + handleCls;
      // Underlay below the core (appended first) so the colour dominates on overlap.
      layer.appendChild(under);
      layer.appendChild(core);
      layer.appendChild(handle);
      return { under, core, handle };
    };
    // cap/s readout (top-right), the two time edge labels (left/right of the
    // horizontal centre), and the four per-channel V edge labels (top/bottom of the
    // vertical centre, one pair per channel). Each is created once and repositioned
    // every render; unused channel labels are simply hidden. The trigger-level /
    // trigger-position / per-channel-offset SLIDERS (dashed line + triangle handle)
    // are FIXED-size DOM overlays too — the canvas keeps only trace + grid + ticks.
    const ovl = {
      layer,
      caps: make('scope-ovl-caps'),
      timeLeft: make('scope-ovl-time'),
      timeRight: make('scope-ovl-time'),
      vTop: { L: make('scope-ovl-volt'), R: make('scope-ovl-volt') },
      vBot: { L: make('scope-ovl-volt'), R: make('scope-ovl-volt') },
      trigLevel: makeSlider('scope-ovl-hline', 'scope-ovl-tri-left'),
      trigPos: makeSlider('scope-ovl-vline', 'scope-ovl-tri-up'),
      offset: {
        L: makeSlider('scope-ovl-hline', 'scope-ovl-tri-right'),
        R: makeSlider('scope-ovl-hline', 'scope-ovl-tri-right'),
      },
    };
    canvas.parentNode.appendChild(layer);
    return ovl;
  }

  /** Hides every slider line + handle (file mode hides the trigger sliders; the
   *  blank-return paths hide them all alongside the labels). */
  _hideSlider(s) {
    if (!s) return;
    s.under.style.display = 'none';
    s.core.style.display = 'none';
    s.handle.style.display = 'none';
  }

  /** Positions one slider's dashed line + triangle handle from edge-fraction `frac`
   *  (0..1), in the requested orientation, all by PERCENT with FIXED px thickness.
   *  `clipStart`/`clipEnd` are the fixed-px insets that stop a horizontal line short
   *  of the opposite-edge handle (the C36 clip); ignored for the vertical line. */
  _positionSlider(s, orient, frac, colorHex, clipStart, clipEnd) {
    const pct = (clamp01(frac) * 100) + '%';
    s.under.style.display = '';
    s.core.style.display = '';
    s.handle.style.display = '';
    // Drive the colour through a custom property: the line core uses it for its one
    // dashed border, and the CSS triangle uses it for ONLY its single solid border
    // (the other three stay literally `transparent`, so the grip is a triangle, not a
    // filled box). Setting style.borderColor directly would recolour the transparent
    // sides and turn the handle into a box.
    s.core.style.setProperty('--slider-color', colorHex);
    s.handle.style.setProperty('--slider-color', colorHex);
    if (orient === 'h') {
      // Horizontal line at top:frac; clipped to clear the opposite-edge handles.
      for (const el of [s.under, s.core]) {
        el.style.top = pct;
        el.style.left = clipStart + 'px';
        el.style.right = clipEnd + 'px';
        el.style.bottom = 'auto';
        el.style.height = '0';
        el.style.width = 'auto';
      }
      // Triangle handle sits at the line edge (right edge for tri-left, left for
      // tri-right) — the border-color above paints the triangle in the line colour.
      s.handle.style.top = pct;
    } else {
      // Vertical line at left:frac, full height.
      for (const el of [s.under, s.core]) {
        el.style.left = pct;
        el.style.top = '0';
        el.style.bottom = '0';
        el.style.right = 'auto';
        el.style.width = '0';
        el.style.height = 'auto';
      }
      s.handle.style.left = pct;
    }
  }

  /** Hides every overlay label AND every slider line/handle (no enabled channels /
   *  no-prefs blank-return paths, where neither labels nor sliders are drawn). */
  _clearOverlayLabels() {
    const o = this._overlay;
    if (!o) return;
    for (const el of [o.caps, o.timeLeft, o.timeRight, o.vTop.L, o.vTop.R, o.vBot.L, o.vBot.R]) {
      el.style.display = 'none';
    }
    this._hideSlider(o.trigLevel);
    this._hideSlider(o.trigPos);
    this._hideSlider(o.offset.L);
    this._hideSlider(o.offset.R);
  }

  /** Positions one edge label: sets its text, colour, tooltip and edge anchor from
   *  the `pos` spec (which of left/right and top/bottom it pins to, as fractions of
   *  the canvas box). Hidden when `text` is empty. */
  _setOverlayLabel(el, text, hex, tip, pos) {
    if (!text) { el.style.display = 'none'; return; }
    el.style.display = '';
    if (el.textContent !== text) el.textContent = text;
    el.style.color = hex;
    if (el.title !== tip) el.title = tip || '';
    const s = el.style;
    s.left = pos.left != null ? pos.left : 'auto';
    s.right = pos.right != null ? pos.right : 'auto';
    s.top = pos.top != null ? pos.top : 'auto';
    s.bottom = pos.bottom != null ? pos.bottom : 'auto';
    s.transform = pos.transform || 'none';
  }

  /** Arms (or cancels) a SINGLE-shot capture (Java ScopeView.setSingleArmed).
   *  While armed the trace freezes on the held frame; the next qualified
   *  trigger captures a fresh frame and disarms (the shell pops the Start
   *  toggle back out). No effect outside SINGLE mode. */
  setSingleArmed(armed) {
    this._singleArmed = armed;
  }

  /** True iff a SINGLE shot is currently armed (waiting for its trigger). */
  isSingleArmed() {
    return this._singleArmed;
  }

  /** Drops the held trigger anchor and captured frame (Java ScopeView.resetTriggerHold)
   *  — called when the trigger SOURCE changes (type / edge / channel) or the generated
   *  signal changes: the old anchor belongs to the old trigger/signal and would keep
   *  re-rendering a stale trace. NORMAL / SINGLE then stay blank until the new trigger
   *  fires. Also restarts the glitch-mode cap/s collection — events caught under the
   *  old trigger/signal (e.g. the generator transition itself, which IS a
   *  discontinuity) don't belong in the new count. */
  resetTriggerHold() {
    this._singleHeld = false;
    this._normalFrame = null;
    this._frame = null;
    this._glitchCountStartMs = 0;
  }

  /** Restarts the glitch-mode cap/s collection (Java rateSawFrozen: a stop/restart
   *  boundary must not fold the stopped gap into the cumulative rate). Called by the
   *  pane on record start. */
  restartGlitchRate() {
    this._glitchCountStartMs = 0;
  }

  /** The wheel-zoom anchors the next held-frame t/div change around the cursor; a
   *  t/div field change leaves it unset so the trace stays centred (Java
   *  ScopeView.setHeldZoomAnchorForNextScale / heldZoomAnchorX). */
  setHeldZoomAnchorForNextScale(canvasX) {
    this._heldZoomAnchorX = canvasX;
  }

  /** The pan/zoom engine with its V/div ladder kept in sync with this.vDivSeries (set
   *  by the shell after construction). The engine reads the ladder live, so refreshing
   *  the field here is enough — no rebuild. */
  nav() {
    if (this._nav.vDivLadder !== this.vDivSeries) this._nav.vDivLadder = this.vDivSeries || null;
    return this._nav;
  }

  // ----- wheel interactions (ScopePane.installScopeViewWheelHandler) -----
  // plain = vertical PAN (offset), Shift = horizontal PAN (trigger pos),
  // Ctrl = V/div zoom around cursor Y, Ctrl+Shift = t/div zoom around cursor X.
  _onWheel(e) {
    if (!this.prefs) return;
    e.preventDefault();
    const dir = e.deltaY < 0 ? 1 : -1;     // wheel up = +1
    const rect = this.cv.getBoundingClientRect();
    const my = e.clientY - rect.top, mx = e.clientX - rect.left;
    const H = this.cv.clientHeight || 240, W = this.cv.clientWidth || 1200;
    if (e.ctrlKey && e.shiftKey) {
      // Anchor a held-frame re-centre on the cursor (Java ScopePane wheel handler:
      // view.setHeldZoomAnchorForNextScale(e.x) when frozen); a t/div FIELD change
      // leaves the anchor unset so the held trace stays centred.
      if (this._singleHeld || this._normalFrame) this.setHeldZoomAnchorForNextScale(mx);
      this._zoomTimeAround(-dir, mx, W);
    }
    else if (e.ctrlKey) this._zoomVoltsAround(-dir, my, H);
    else if (e.shiftKey) this._panHorizontal(dir);
    else this._panVertical(dir);
    // A V/div or t/div zoom changed the settings the tab tiles + numeric fields
    // mirror; let the shell re-sync them (Java refreshTab on the pref change).
    if (this.onSettingsChanged) this.onSettingsChanged();
  }

  _panVertical(dir) {
    // Plain wheel: vertical offset on BOTH channels together (Java
    // ScopePane.stepMeasurementChannelOffset → ScopeNav.moveVertical). Wheel up
    // (dir = +1) moves the signal UP, so the offset fraction DECREASES; the engine
    // encodes that sign and clamps both channels at ±FS/2-at-middle. Trigger level is
    // intentionally NOT moved. An off-1-2-5 (manual) channel keeps its proportion.
    const p = this.prefs;
    const peak = p.adcFsVoltageRms.get() * Math.SQRT2;
    const oldL = p.oscLeftOffsetFrac.get(), oldR = p.oscRightOffsetFrac.get();
    const off = this.nav().moveVertical(
      oldL, p.oscLeftVoltsPerDiv.get(), p.oscLeftChannelEnabled.get(),
      oldR, p.oscRightVoltsPerDiv.get(), p.oscRightChannelEnabled.get(),
      dir, peak);
    if (off[0] === oldL && off[1] === oldR) return;
    p.oscLeftOffsetFrac.set(off[0]);
    p.oscRightOffsetFrac.set(off[1]);
  }

  _panHorizontal(dir) {
    const p = this.prefs;
    // File mode: Shift+wheel scrolls the read window THROUGH the loaded buffer by
    // changing the view back-offset (Java ScopePane.stepHorizontalOffset file branch:
    // stepFrames = displaySamples/5, wheel up = move backward in time → back grows).
    // The displayed signal + time marks + measured values all come from the moved
    // window, not just a relabelled axis.
    if (this.fileMode) {
      const geom = this._fileGeom;
      if (!geom || !this.onFileBack) return;
      const stepFrames = Math.max(1, Math.round(geom.windowSamples / 5));
      this.onFileBack(geom.back + dir * stepFrames);
      return;
    }
    // Live: ½-div trigger-offset move via the engine; UNCLAMPED so it may go VIRTUAL
    // (the handle pins to the L/R edge while the time-offset mark shows the real value).
    // The read spans ~2 screens around the trigger; drawTrace blanks any edge the buffer
    // can't fill (Java ScopePane.stepHorizontalOffset live branch → ScopeNav.moveTriggerOffset).
    const cur = p.oscTriggerPositionFrac.get();
    const next = this.nav().moveTriggerOffset(cur, dir);
    if (next === cur) return;
    p.oscTriggerPositionFrac.set(next);
  }

  // Snaps `v` to the next entry up/down the 1-2-5 series the V/div + t/div step
  // FIELDS use (NumericStepModel LIST policy / _listJump), so the Ctrl-wheel zoom
  // walks the SAME ladder as the field arrows rather than a continuous step. The
  // series is supplied by the shell (this.vDivSeries / this.tDivSeries); when one
  // value already sits on the ladder it jumps to the neighbour, otherwise to the
  // nearest ladder entry strictly above/below. Falls back to the value unchanged
  // when no series is wired (no continuous fallback — snapping is the contract).
  _stepSeries(series, v, up) {
    if (!series || !series.length || !(v > 0)) return v;
    for (let i = 0; i < series.length; i++) {
      if (Math.abs(series[i] - v) <= Math.max(Math.abs(series[i]), Math.abs(v)) * REL_EPS) {
        const next = i + (up ? 1 : -1);
        return (next < 0 || next >= series.length) ? v : series[next];
      }
    }
    if (up) {
      let best = Infinity;
      for (const s of series) if (s > v * (1 + REL_EPS) && s < best) best = s;
      return Number.isFinite(best) ? best : v;
    }
    let best = -Infinity;
    for (const s of series) if (s < v * (1 - REL_EPS) && s > best) best = s;
    return Number.isFinite(best) ? best : v;
  }

  _zoomVoltsAround(dir, mouseY, H) {
    // Ctrl+wheel: V/div zoom anchored at the mouse Y — both channels' V/div are coupled
    // (the 1-2-5 proportional rule) and each offset is re-anchored so the voltage under
    // the cursor stays put (Java ScopeTabControl.stepVoltsPerDivAround → ScopeNav.zoomVertical,
    // zoom-out capped at the FS-fills-height ceiling). dir < 0 = finer (smaller V/div).
    const p = this.prefs, anchorFrac = mouseY / H;
    const leftEn = p.oscLeftChannelEnabled.get(), rightEn = p.oscRightChannelEnabled.get();
    if (!leftEn && !rightEn) return;
    const leftOld = p.oscLeftVoltsPerDiv.get(), rightOld = p.oscRightVoltsPerDiv.get();
    const peak = p.adcFsVoltageRms.get() * Math.SQRT2;
    const r = this.nav().zoomVertical(
      leftOld, p.oscLeftOffsetFrac.get(), leftEn,
      rightOld, p.oscRightOffsetFrac.get(), rightEn,
      dir, anchorFrac, peak);
    if (leftEn && r[0] !== leftOld) { p.oscLeftVoltsPerDiv.set(r[0]); p.oscLeftOffsetFrac.set(r[2]); }
    if (rightEn && r[1] !== rightOld) { p.oscRightVoltsPerDiv.set(r[1]); p.oscRightOffsetFrac.set(r[3]); }
  }

  _zoomTimeAround(dir, mouseX, W) {
    const p = this.prefs;
    const tDivOld = p.oscTimePerDiv.get(), posOld = p.oscTriggerPositionFrac.get();
    const tDivNew = this._stepSeries(this.tDivSeries, tDivOld, dir > 0);
    if (Math.abs(tDivNew - tDivOld) <= tDivOld * REL_EPS) return;
    const mouseFrac = mouseX / W;
    p.oscTimePerDiv.set(tDivNew);
    // File mode: the displayed window is positioned by the view back-offset (not by
    // triggerPositionFrac), so mirror the live zoom-around-cursor by re-anchoring the
    // back-offset to keep the SAME loaded-buffer frame under the mouse after the zoom.
    // bufFrameAtMouse = startSample + mouseFrac·windowSamples; solve for the new
    // back so startSample' = bufFrameAtMouse − mouseFrac·windowSamples'.
    if (this.fileMode) {
      const geom = this._fileGeom;
      if (geom && this.onFileBack) {
        const wsNew = geom.windowSamples * (tDivNew / tDivOld);
        const rightPad = Math.min(LANCZOS_PADDING, Math.max(0, geom.available - wsNew));
        const rightEdgeAnchorNew = Math.max(0, geom.available - rightPad - wsNew);
        const bufFrameAtMouse = geom.startSample + mouseFrac * geom.windowSamples;
        const startNew = bufFrameAtMouse - mouseFrac * wsNew;
        this.onFileBack(Math.round(rightEdgeAnchorNew - startNew));
      }
      return;
    }
    // Live: anchor the sample under the mouse via the engine, using the ACTUAL
    // displaySamples ratio (not the raw t/div ratio, which round-diverges at fine time
    // bases). UNCLAMPED so the offset may carry off-screen (the handle pins to the edge,
    // the time mark shows the real value) — Java ScopeTabControl.stepTimePerDivAround →
    // ScopeNav.zoomTriggerOffset(aroundMouse=true). The t/div change only re-derives the
    // window width; the trigger anchor is preserved, not the view centre.
    const sr = (this._lastInfo && this._lastInfo.inRate) || 0;
    if (sr > 0) {
      const dispOld = Math.max(2, Math.round(tDivOld * DIVISIONS_X * sr));
      const dispNew = Math.max(2, Math.round(tDivNew * DIVISIONS_X * sr));
      p.oscTriggerPositionFrac.set(this.nav().zoomTriggerOffset(posOld, dispOld, dispNew, mouseFrac, true));
    } else {
      p.oscTriggerPositionFrac.set(mouseFrac - (mouseFrac - posOld) * (tDivOld / tDivNew));
    }
  }

  /** Java ScopeController.performAutoSetup: t/div ≈ ceil₁₂₅(period·1.5/DIVISIONS_X),
   *  V/div ≈ ceil₁₂₅(Vpp/(DIVISIONS_Y·0.75)), both channels centred, trigger to mid.
   *  Reads the last measurement (this.latest) — call once a frame is available. */
  autoSetup() {
    const p = this.prefs, m = this.latest;
    if (!p || !m) return;
    const ceil125 = (t) => {
      if (!(t > 0)) return t;
      const dec = Math.pow(10, Math.floor(Math.log10(t))), mm = t / dec;
      return (mm <= 1 ? 1 : mm <= 2 ? 2 : mm <= 5 ? 5 : 10) * dec;
    };
    if (Number.isFinite(m.frequency) && m.frequency > 0) {
      p.oscTimePerDiv.set(ceil125((1 / m.frequency) * 1.5 / DIVISIONS_X));
    }
    if (Number.isFinite(m.vpp) && m.vpp > 0) {
      const vDiv = ceil125(m.vpp / (DIVISIONS_Y * 0.75));
      p.oscLeftVoltsPerDiv.set(vDiv); p.oscRightVoltsPerDiv.set(vDiv);
    }
    // Recenter each DC-coupled channel on its DC mean so a small AC signal on a large
    // DC pedestal lands mid-screen instead of off an edge (Java ScopeController.
    // autoSetupOffsetFrac, ScopeView.java:1220-1228): DC-coupled → 0.5 + meanV/(DIVISIONS_Y·vDiv);
    // AC-coupled → 0.5 (DC already removed from the trace). Vpp is max−min so the pedestal
    // already cancelled in the V/div above — only the offset needs the mean.
    const meanV = Number.isFinite(m.vmean) ? m.vmean : 0;   // measured DC mean, volts
    const offFor = (ac, vd) => (ac || !(vd > 0)) ? 0.5 : 0.5 + meanV / (DIVISIONS_Y * vd);
    p.oscLeftOffsetFrac.set(offFor(p.oscLeftAcMode.get(), p.oscLeftVoltsPerDiv.get()));
    p.oscRightOffsetFrac.set(offFor(p.oscRightAcMode.get(), p.oscRightVoltsPerDiv.get()));
    p.oscTriggerPositionFrac.set(0.5); p.oscTriggerLevelFrac.set(0.5);
  }

  /** @param buf  the R channel (ch1, the measured/primary channel) as a
   *  Float32Array. @param info {scopeFps, period, inRate, snapped, peakVolts,
   *  dualTone, f1Hz, f2Hz, bufL, bufR}. info.bufL / info.bufR carry BOTH
   *  channels (Float32Array) per the scope DATA CONTRACT; when absent (file
   *  load) `buf` is drawn for both channels. */
  render(buf, info) {
    const g = this.g;
    this._lastBuf = buf; this._lastInfo = info;   // remembered so the ResizeObserver can repaint a loaded/frozen view at the new size
    // CSS box drives the size for an attached canvas; a DETACHED canvas (the
    // offscreen screenshot clone) reports clientWidth/Height = 0, so fall back to
    // its pre-set backing store (sized by the caller to match the LIVE scope
    // canvas exactly, C20d) before the last-ditch default.
    const W = this.cv.clientWidth || this.cv.width || 1200;
    const H = this.cv.clientHeight || this.cv.height || 240;
    // Work in LIVE (CSS / virtual) pixels only — the backing store equals the CSS box,
    // so the display magnification (devicePixelRatio) is NEVER applied. Drawing, the
    // mouse hit-boxes (getBoundingClientRect) and the screenshot capture then all share
    // one consistent pixel space.
    if (this.cv.width !== W) this.cv.width = W;
    if (this.cv.height !== H) this.cv.height = H;
    g.setTransform(1, 0, 0, 1, 0, 0);
    g.fillStyle = '#000'; g.fillRect(0, 0, W, H);
    this._drawGraticule(g, W, H);

    // ACTUAL filled sample count (Java `available` = readEndingAt return), not the
    // grow-only buffer length — the captured window is ~3× the displayed span but the
    // ring may not have filled it all yet; drawing the unfilled tail would smear
    // stale / zero samples into the trace. File load carries no info.available, so the
    // whole decoded buffer is the signal (buf.length).
    const available = (info.available != null) ? info.available : buf.length;
    const sampleRate = info.inRate;
    const peakVolts = info.peakVolts > 0 ? info.peakVolts : 1.0;
    const p = this.prefs;
    const peakV = p ? p.adcFsVoltageRms.get() * Math.SQRT2 : 1.0;

    // ----- per-channel descriptors (Java drawWaveforms: showL/showR, dcL/dcR,
    // acL/acR, sincL/sincR) — built only for enabled channels. Each channel
    // reads its own buffer (info.bufL/bufR; `buf` fallback), V/div, offset, AC,
    // sinc, colour, and the HF+mains filtered span over which its DC mean is
    // measured. Blank-return when neither channel is enabled (Java line 1940).
    const showL = !p || p.oscLeftChannelEnabled.get();
    const showR = !p || p.oscRightChannelEnabled.get();
    if (p && !showL && !showR) {
      this.latest = null; this.measurementRows = null; this._clearOverlayLabels();
      if (this._rectZoom) this._rectZoom.drawOverlay(g, W, H);   // Java paints the zoom layer on every paint
      return;
    }

    const searchFrom = LANCZOS_PADDING + 1;
    const searchTo = available - LANCZOS_PADDING;
    const bufL = info.bufL || buf, bufR = info.bufR || buf;
    const descByName = {};
    const descriptors = [];
    const buildDesc = (name, enabled, raw) => {
      if (p && !enabled) return;
      const lc = name;   // 'Left' | 'Right'
      const colorInt = p ? p['osc' + lc + 'ChannelColor'].get()
                         : (lc === 'Left' ? 0x00d7ff : 0xffd700);
      const procBuf = this._applyChannelFilters(raw, available, sampleRate, lc,
        undefined, info.absStart || 0);
      let sum = 0;
      for (let i = searchFrom; i < searchTo; i++) sum += procBuf[i];
      const dcMean = sum / Math.max(1, searchTo - searchFrom);
      const ac = p ? p['osc' + lc + 'AcMode'].get() : false;
      const d = {
        name: lc,
        ch: lc === 'Left' ? 'L' : 'R',
        raw, procBuf,
        vDiv: (p ? p['osc' + lc + 'VoltsPerDiv'].get() : 0.1) || 0.1,
        offsetFrac: p ? p['osc' + lc + 'OffsetFrac'].get() : 0.5,
        ac, dcMean, dcOff: ac ? dcMean : 0,
        sinc: p ? p['osc' + lc + 'SincInterpEnabled'].get() : true,
        colorInt, hex: colorHex(colorInt),
      };
      descByName[d.ch] = d;
      descriptors.push(d);
    };
    buildDesc('Left', showL, bufL);
    buildDesc('Right', showR, bufR);

    // ----- trigger channel (Java line 1942: triggerCh = oscTriggerChannel,
    // independent of the measurement/active channel). Falls back to an enabled
    // channel when the configured trigger channel is disabled.
    let trigCh = p ? p.oscTriggerChannel.get() : 'L';
    let trigDesc = descByName[trigCh] || descriptors[0];
    trigCh = trigDesc.ch;

    // Trigger mode (AUTO / NORMAL / SINGLE) — drives whether a no-trigger frame
    // free-runs (AUTO), holds the last frame (NORMAL) or stays frozen until a
    // single armed shot fires (SINGLE).
    const mode = p ? p.oscTriggerMode.get() : TriggerMode.AUTO;
    if (this._lastTriggerMode !== mode) {
      // A mode switch disarms SINGLE. No transition should ever blank the screen:
      // seed the held frame from the last live frame (this._frame, kept current
      // every live paint by _snapshotFrame) so SINGLE and NORMAL immediately show
      // the last trace + all overlays until a real trigger fires — Java keeps
      // showing the last trace, it never blanks on the switch (C19 / C23). AUTO
      // free-runs, so it needs no seed and clears the held state.
      this._singleArmed = false;
      this._singleHeld = false;
      this._normalFrame = null;
      if (mode === TriggerMode.SINGLE) {
        this._singleHeld = !!this._frame;        // seeded hold iff a live frame exists
      } else if (mode === TriggerMode.NORMAL) {
        this._normalFrame = this._frame;         // seeded hold iff a live frame exists
      } else {
        this._frame = null;                      // AUTO free-runs — no held frame
      }
      this._lastTriggerMode = mode;
    }

    // Dual-tone → trigger/draw the reconstructed slow beat modulator (off the
    // trigger channel's filtered signal); otherwise trigger on it directly.
    const dual = info.dualTone && info.f1Hz > 0 && info.f2Hz > 0 && info.f1Hz !== info.f2Hz;
    const trigBuf = dual
      ? reconstructBeatSignal(trigDesc.procBuf, available, sampleRate, info.f1Hz, info.f2Hz, this._beatScratch)
      : trigDesc.procBuf;

    // ----- live-window measurement (C24): measure the long fixed measurement
    // window (info.measBufL/R, Java ScopeMeasurementWorker's MEAS_MAX_SAMPLES read
    // from the ring) CONTINUOUSLY here, BEFORE any trigger-mode hold/blank branch
    // returns — so the measurement table keeps updating in NORMAL-hold and
    // SINGLE-armed even while the TRACE is frozen, exactly like Java's worker
    // measures the ring independent of the trigger/display. Throttled to
    // READOUT_THROTTLE_MS so the full-window compute cost stays bounded. The
    // displayed-span fallback (file mode / no live window) stays in the trace
    // path below, where `trig`/`show` are known.
    // Fold the contiguous capture gap into the amplitude pool EVERY paint (not throttled)
    // so Vmean/Vrms/Vpp see every captured sample once; the throttled readout below reads
    // the pooled long-window value.
    this._feedMeasurementPool(info, sampleRate);
    const liveMeas = this._measureLiveWindow(info, descByName, descriptors, sampleRate, peakVolts, dual);

    const tDiv = p ? p.oscTimePerDiv.get() : info.period / sampleRate;
    const posFrac = p ? clamp01(p.oscTriggerPositionFrac.get()) : 0.5;
    // Java: displaySamples = round(windowSeconds·sampleRate), bail if < 2. No
    // max(8,…)/min(…,available−2·PADDING) clamp — windowSamples is the literal
    // display window; the dispCount clamp against `available` happens per branch.
    const windowSamples = Math.round(tDiv * DIVISIONS_X * sampleRate);
    if (windowSamples < 2) {
      this.latest = null; this.measurementRows = null;
      if (this._rectZoom) this._rectZoom.drawOverlay(g, W, H);
      return;
    }

    // Trigger level — Java: levelFrac=oscTriggerLevelFrac, the screen-Y of the
    // dashed level line on the TRIGGER channel; the normalised sample at that Y
    // is (offsetFrac − levelFrac)·vDiv·DIVISIONS_Y / peakV. AC-coupled trigger
    // channel: the trace has the DC removed, so add the bias back into the
    // threshold. Dual-tone triggers on the band-limited beat envelope.
    const rising = dual ? true : (p ? p.oscTriggerEdge.get() !== TriggerEdge.FALL : true);
    // The trigger THRESHOLD uses the RAW (virtual-capable) level fraction (Java
    // drawWaveforms reads getOscTriggerLevelFrac unclamped): a rect zoom that
    // excludes the trigger voltage parks the fraction outside [0,1], and the
    // trigger must keep firing at the true (possibly off-screen) threshold. Only
    // the DRAWN slider position clamps to the canvas edge.
    const levelFracRaw = p ? p.oscTriggerLevelFrac.get() : 0.5;
    const levelFrac = clamp01(levelFracRaw);
    const tVDiv = trigDesc.vDiv, tOffsetFrac = trigDesc.offsetFrac;
    const tAc = trigDesc.ac, tDcOff = trigDesc.dcOff;
    const baseLevel = (tOffsetFrac - levelFracRaw) * tVDiv * DIVISIONS_Y / peakV;
    const level = dual ? 0.0 : baseLevel + (tAc ? tDcOff : 0);
    const hysteresis = (p && p.oscTriggerHysteresisEnabled.get())
      ? p.oscTriggerHysteresisDiv.get() * tVDiv / peakV : 0.0;
    const beatHz = dual ? Math.abs(info.f2Hz - info.f1Hz) : 0;
    // Dual-tone holdoff = sr/|F1−F2| (the scope-trigger minSpacing overload): the
    // reconstructed modulator is at (F1−F2)/2, so one cycle spans 2·sr/beat samples and
    // this half-period holdoff admits exactly one trigger per beat envelope cycle,
    // stopping the anchor jumping between adjacent crossings (Java drawWaveforms dual-tone).
    const minSpacing = beatHz > 0 ? sampleRate / beatHz : 0;

    // Trigger search range (Java ScopeView.java ~2075-2078): leftHalf/rightHalf
    // from displaySamples (= windowSamples here) so the displayed window stays
    // inside the buffer; searchFrom = max(1, PADDING+leftHalf+1), searchTo =
    // available − rightHalf − PADDING; the search runs only when searchTo >
    // searchFrom (no fallback widening). File mode bypasses the trigger entirely.
    const leftHalf = Math.ceil(windowSamples * posFrac);
    const rightHalf = Math.ceil(windowSamples * (1 - posFrac));
    const trigFrom = Math.max(1, LANCZOS_PADDING + leftHalf + 1);
    const trigTo = available - rightHalf - LANCZOS_PADDING;
    // GLITCH type: discontinuity trigger — fires where the signal breaks the
    // sinusoid-recurrence prediction (level steps AND slope splices, anywhere on the
    // wave, regardless of direction). ↑ anchors the display on the glitch's start,
    // ↓ on its end. Level / hysteresis / sinc refine don't apply. The measured
    // frequency pins the recurrence exactly; it applies only when the measurement
    // channel IS the trigger channel — otherwise the detector self-estimates from the
    // window. (Java drawWaveforms GLITCH branch → ScopeTrigger.findGlitch.)
    const glitchMode = !!p && p.oscTriggerType.get() === TriggerType.GLITCH;
    let foundFrac = -1;
    if (!this.fileMode && trigTo > trigFrom) {
      if (glitchMode) {
        const m = this.latest;
        const measHz = (m && p.oscMeasurementChannel.get() === trigCh) ? m.frequency : NaN;
        const omega = (measHz > 0 && measHz < sampleRate / 2.0)
          ? 2.0 * Math.PI * measHz / sampleRate : NaN;
        foundFrac = findGlitch(trigBuf, trigFrom, trigTo, rising,
          Math.round(sampleRate * TimeDiscontinuityDetector.MERGE_SECONDS), omega);
      } else {
        foundFrac = find(trigBuf, available, trigFrom, trigTo, level, rising,
          trigDesc.sinc && !dual, hysteresis, minSpacing);
      }
    }
    const foundTrigger = foundFrac >= 0;

    // Java navigation/file-mode bypass (drawWaveforms ~2052-2065): a static
    // loaded signal (and, in the desktop, a scrolled-back view) bypasses the
    // trigger and right-edge-anchors the latest displaySamples window, with
    // subSampleOffset = 0. Right-edge anchoring is stable across t/div changes.
    const rightEdgeAnchor = () => {
      const rightPad = Math.min(LANCZOS_PADDING, Math.max(0, available - windowSamples));
      const dispEnd = available - rightPad;
      return Math.max(0, dispEnd - windowSamples);   // dispStart; subSampleOffset = 0
    };

    // ----- trigger-mode frame selection (Java drawWaveforms steps 1-3) -----
    // dispCount is the drawn window length. It equals windowSamples for the live
    // trigger paths; FILE MODE clamps it to the part of the loaded buffer actually
    // on screen (Java drawWaveforms file branch dispCountN = dispEndN − dispStartN),
    // so a buffer SHORTER than the requested window keeps samplesPerPx small enough
    // to stay on the band-limited Lanczos branch instead of falling onto the
    // per-column min/max bars (which staircases a smooth sine).
    let trig, capture = false, startSample, dispCount = windowSamples;
    if (!p) {
      // No-prefs file-load autoscale fallback: free-run from trigFrom, startSample = trig.
      trig = foundTrigger ? foundFrac : trigFrom;
      startSample = trig;
    } else if (this.fileMode) {
      // File mode bypass: right-edge anchor, no trigger, no capture. A non-zero
      // info.viewBackOffsetFrames scrolls the anchored window backwards from the
      // latest sample (Java ScopePane navSlider in file mode), clamped to [0, start].
      const back = Math.max(0, Math.round(info.viewBackOffsetFrames || 0));
      startSample = Math.max(0, rightEdgeAnchor() - back);
      // Clamp the drawn span to the buffer's right edge (Java dispEndN), so when the
      // loaded signal is shorter than the requested window the trace is reconstructed
      // at full resolution over the real samples instead of decimating into min/max
      // bars (the staircase). available ≥ windowSamples → dispCount == windowSamples.
      const rightPad = Math.min(LANCZOS_PADDING, Math.max(0, available - windowSamples));
      dispCount = Math.max(0, Math.min(windowSamples, (available - rightPad) - startSample));
      trig = startSample + posFrac * windowSamples;   // for the measurement span below
      // Stash the window geometry so the wheel handlers (file-mode horizontal pan +
      // t/div zoom-around-cursor) and the rect zoom can re-anchor the back-offset
      // against the loaded buffer without re-deriving it (C34). dispCount is the
      // span actually mapped across the canvas (short files clamp it), so the rect
      // zoom's pixel↔frame mapping matches the drawn trace exactly.
      this._fileGeom = { available, windowSamples, startSample, back, dispCount };
    } else if (mode === TriggerMode.SINGLE) {
      if (this._singleArmed && foundTrigger) {
        trig = foundFrac; capture = true;
        this._singleArmed = false;
        if (this.onSingleDisarmed) this.onSingleDisarmed();   // pop the Start toggle
        startSample = trig - posFrac * windowSamples;
      } else if (this._singleHeld) {
        // Armed-waiting (or disarmed-held): keep the LAST captured frame + ALL its
        // overlays (Java drawWaveforms: armed+no-trigger → renderHeldCapturedFrame).
        // _drawHeldFrame preserves this.latest (so autoSetup works while armed) and
        // leaves this.measurementRows intact; only a fresh armed trigger (above)
        // replaces the frame.
        this._drawHeldFrame(g, W, H); return;
      } else if (this._frame) {
        // Armed, no fresh capture yet, but a last live frame exists (e.g. seeded
        // on the Auto→SINGLE switch): draw it with all overlays rather than
        // blanking the canvas (C19) — Java holds the last trace until the shot fires.
        this._drawHeldFrame(g, W, H); return;
      } else {
        // Armed but no frame ever rendered: nothing to draw, but do NOT blank the
        // measured state — preserve this.latest / this.measurementRows so autoSetup
        // and the DOM table keep working while we wait for the first trigger.
        // The cap/s readout stays visible on the blank pane (Java paintCanvas draws
        // it every paint) — in glitch mode it IS the glitches/s counter.
        this._updateCaptureRate(false);
        this._drawCaptureRate();
        if (this._rectZoom) this._rectZoom.drawOverlay(g, W, H);
        return;
      }
    } else if (mode === TriggerMode.NORMAL) {
      if (foundTrigger) { trig = foundFrac; capture = true; startSample = trig - posFrac * windowSamples; }
      else if (this._normalFrame) { this._drawHeldFrame(g, W, H); return; }   // hold last
      // Never triggered AND no seeded frame → blank the TRACE only. Do NOT wipe
      // the measured state: _measureLiveWindow already refreshed this.latest /
      // measurementRows this paint, and the table must keep updating (C24). The
      // cap/s readout stays visible on the blank pane (whole-record visibility).
      else {
        this._updateCaptureRate(false); this._drawCaptureRate();
        if (this._rectZoom) this._rectZoom.drawOverlay(g, W, H);
        return;
      }
    } else {
      // AUTO: anchor on the trigger when found (centred via posFrac); otherwise
      // free-run on the latest samples — Java step 3 right-edge-anchors (NOT
      // trigFrom), with subSampleOffset = 0.
      if (foundTrigger) { trig = foundFrac; startSample = trig - posFrac * windowSamples; }
      else { startSample = rightEdgeAnchor(); trig = startSample + posFrac * windowSamples; }
    }

    const period = info.period;
    const show = Math.max(8, Math.min(available - Math.ceil(trig) - LANCZOS_PADDING, Math.round(period * 4)));   // measurement span
    // Java drawTrace: width = canvas width; samplesPerPx = dispCount/width,
    // pxPerSample = width/dispCount. cols IS the canvas width (one polyline
    // point per pixel column), never capped to the sample count.
    const cols = W;
    const samplesPerPx = dispCount / cols;
    const pxPerSample = cols / dispCount;

    // Each channel: its own colour / V/div / offset / AC / sinc. In dual-tone
    // the trigger channel draws the beat-modulated `trigBuf`; the other channel
    // draws its own filtered signal. (No-prefs autoscale fallback per channel.)
    let autoMm = 0;
    if (!p) {
      autoMm = 1e-9;
      for (let i = 0; i < show; i++) { const idx = Math.floor(trig) + i; if (idx >= 0 && idx < available) autoMm = Math.max(autoMm, Math.abs(buf[idx])); }
    }
    for (const d of descriptors) {
      const drawBuf = (dual && d === trigDesc) ? trigBuf : d.procBuf;
      const sampleToY = p
        ? (s) => H * (d.offsetFrac - ((s - d.dcOff) * peakV) / (d.vDiv * DIVISIONS_Y))
        : (s) => H / 2 - s / autoMm * (H * 0.45);
      this._drawTrace(g, W, H, drawBuf, available, startSample, dispCount,
                      cols, samplesPerPx, pxPerSample, sampleToY, d.sinc, d.hex);
    }

    // Reconstructed-beat overlay — gated on dual-tone AND the user's checkbox
    // (Java drawBeatOverlay). Drawn in the trigger channel's colour at 0.45
    // brightness (Java attenuate(triggerColour, 0.45)), in the trigger channel's
    // vertical mapping.
    if (dual && p && p.oscShowReconstructedBeat.get()) {
      const beatHex = attenuateHex(trigDesc.colorInt, 0.45);
      // Java drawBeatOverlay: dcOffset = 0.0 (beat is already zero-centred),
      // sincEnabled = false, dotDiameter = 0.
      const sampleToY = (s) => H * (trigDesc.offsetFrac - (s * peakV) / (trigDesc.vDiv * DIVISIONS_Y));
      this._drawTrace(g, W, H, trigBuf, available, startSample, dispCount,
                      cols, samplesPerPx, pxPerSample, sampleToY, false, beatHex, 0);
    }

    // ----- on-canvas sliders (Java drawSliders): dashed trigger-level line +
    // draggable triangle handle on the right edge, dashed trigger-position cursor
    // + handle on the bottom edge, and the active measurement channel's vertical-
    // offset track + handle on the left edge. Trigger sliders hidden in file mode.
    if (p) this._drawSliders(g, W, H, levelFrac, posFrac, descByName);

    // ----- per-channel edge labels (Java drawEdgeLabels): left/right time on
    // the horizontal centre line + per-channel grid min/max on the vertical
    // centre line, each in its channel colour.
    if (p) this._drawEdgeLabels(descriptors, tDiv, posFrac);
    else this._clearOverlayLabels();

    // ----- measurements (Vpp/Vrms/freq/period/duty). The live measurement window
    // (info.measBufL/R) was already measured + accumulated in _measureLiveWindow
    // above (C24, decoupled from the trigger so the table keeps updating while the
    // trace is frozen); reuse that result for the snapshot. Only the displayed-span
    // fallback (file mode / no live window) is computed here, where `trig`/`show`
    // are known — and only when the live window wasn't available.
    let meas = liveMeas;
    if (!meas) {
      let measCh = p ? p.oscMeasurementChannel.get() : trigCh;
      let measDesc = descByName[measCh];
      if (p && !measDesc) {   // selected channel disabled → flip + persist + clear history
        measDesc = descriptors[0];
        p.oscMeasurementChannel.set(measDesc.ch);
        if (p.save) p.save();   // Java prepareMeasurementRows persists the flip
        this._clearMeasurementHistory();
      }
      if (!measDesc) measDesc = trigDesc;
      const mStart = Math.max(0, Math.floor(trig));
      const mLen = Math.min(available - mStart, show);
      const span = measDesc.procBuf.subarray(mStart, mStart + mLen);
      meas = compute(span, mLen, sampleRate, peakVolts);
      if (dual) {
        // Beat view: single-value period/freq/duty are meaningless. Re-measure the
        // (real) tone frequency off the span and drop the time-domain fields.
        const f = refineFrequencyAround(span, mLen, sampleRate, info.f1Hz, info.f1Hz * 0.25);
        meas = (f > 0) ? withFrequency(withoutTimes(meas), f) : withoutTimes(meas);
      }
      this._accumulateMeasurements(meas, dual);
    }

    // Top-right overlay carries ONLY the cap/s readout (Java drawCaptureRate:
    // "%.1f cap/s" at w − textWidth − 8, y≈6). No measured-value text on the
    // canvas — those live in the DOM measurement table (Java drawMeasurements).
    // Reaching here means this paint produced a genuinely-new frame (every
    // held-frame branch returned earlier), so advance the rate as "new".
    this._updateCaptureRate(true);
    this._drawCaptureRate();
    this._drawStaticFilePath(g, W);

    // Snapshot everything _drawHeldFrame needs to repaint the exact frame from
    // BOTH channels, independent of the rolling ring. Captured EVERY live paint
    // (into grow-only reused buffers — bounded per-frame cost, no per-paint
    // allocation churn) so a held frame is always available: a SINGLE/NORMAL
    // trigger freezes it (capture=true), AND an Auto→SINGLE switch can seed the
    // held frame from the last live Auto frame (C19) instead of blanking.
    this._snapshotFrame(descriptors, trigDesc, trigBuf, dual, p, available,
                        startSample, dispCount, cols, samplesPerPx, pxPerSample, peakV, meas);
    if (capture) {
      if (mode === TriggerMode.SINGLE) this._singleHeld = true; else this._normalFrame = this._frame;
    }

    // Rect-zoom rubber band + focused-view accent border — LAST (Java paintCanvas
    // ends with drawRectZoomOverlay).
    if (this._rectZoom) this._rectZoom.drawOverlay(g, W, H);

    // expose the latest (measurement-channel) measurement so the shell can fill
    // the scope tiles / autoSetup / calibrate.
    this.latest = meas;
  }

  /** Black-background graticule: 10×10 grid plus a centre cross-hair with
   *  TICKS_PER_DIV sub-ticks per division (Java AbstractMeasurementView.drawGrid
   *  + CrossHairSpec). Grid = ColorRole.GRID (0x3C3C3C), cross-hair =
   *  ColorRole.CROSSHAIR (0x909090), frame = ColorRole.AXIS (0x3C3C3C). */
  _drawGraticule(g, W, H) {
    g.lineWidth = 1;
    // Grid lines at every division INCLUDING the two edges (linearTicks(0,10,10)
    // = 11 ticks 0..10; valueToX = round(i/10·W)).
    g.strokeStyle = '#3c3c3c';
    g.beginPath();
    for (let i = 0; i <= DIVISIONS_X; i++) { const gx = Math.round(i / DIVISIONS_X * W); g.moveTo(gx + 0.5, 0); g.lineTo(gx + 0.5, H); }
    for (let i = 0; i <= DIVISIONS_Y; i++) { const gy = Math.round(i / DIVISIONS_Y * H); g.moveTo(0, gy + 0.5); g.lineTo(W, gy + 0.5); }
    g.stroke();
    // Centre cross-hair (xFrac/yFrac = 0.5) + sub-ticks i = 0..total INCLUSIVE.
    const cx = Math.round(0.5 * W), cy = Math.round(0.5 * H);
    g.strokeStyle = '#909090';
    g.beginPath(); g.moveTo(cx + 0.5, 0); g.lineTo(cx + 0.5, H); g.moveTo(0, cy + 0.5); g.lineTo(W, cy + 0.5); g.stroke();
    g.beginPath();
    const tickX = (W / DIVISIONS_X) / TICKS_PER_DIV;
    const tickY = (H / DIVISIONS_Y) / TICKS_PER_DIV;
    const totalX = DIVISIONS_X * TICKS_PER_DIV;
    const totalY = DIVISIONS_Y * TICKS_PER_DIV;
    const total = Math.max(totalX, totalY);
    for (let i = 0; i <= total; i++) {
      if (i <= totalY) { const py = Math.round(i * tickY); g.moveTo(cx - TICK_HALF_LEN + 0.5, py + 0.5); g.lineTo(cx + TICK_HALF_LEN + 0.5, py + 0.5); }
      if (i <= totalX) { const px = Math.round(i * tickX); g.moveTo(px + 0.5, cy - TICK_HALF_LEN + 0.5); g.lineTo(px + 0.5, cy + TICK_HALF_LEN + 0.5); }
    }
    g.stroke();
    // Plot frame (Java drawRectangle(0,0,W,H) in ColorRole.AXIS).
    g.strokeStyle = '#3c3c3c';
    g.strokeRect(0.5, 0.5, W - 1, H - 1);
  }

  /** Draws one channel's trace (Java drawTrace): band-limited Lanczos / linear
   *  polyline with full-intensity edge overhang when samplesPerPx is small,
   *  a CONNECTED per-column min/max envelope (sinc: spp>1; linear: spp>MAX_LANCZOS_DOWNSAMPLE)
   *  above that, and filled per-sample dots when sample spacing exceeds 10 px.
   *  `blankBeyondData` (Java field, true only on the held-frame magnify path) blanks
   *  any envelope column whose data runs past the buffer instead of clamping it. */
  _drawTrace(g, W, H, buf, n, startSample, windowSamples, cols, samplesPerPx, pxPerSample, sampleToY, sinc, hex, dotDiameterOverride, blankBeyondData = false) {
    // Java drawTrace works in (dispStart, subSampleOffset, dispCount). The web
    // carries a single float startSample (= windowLeftT = dispStart+subSample);
    // split it back exactly as Java's caller did (floor / fraction).
    const width = cols;   // = canvas width (Java `width`)
    const dispStart = Math.floor(startSample);
    const subSampleOffset = startSample - dispStart;
    const dispCount = Math.round(windowSamples);
    if (dispCount < 2 || width <= 0) return;
    g.strokeStyle = hex; g.fillStyle = hex;
    // One smooth point per pixel column while at most one sample per pixel (sinc) or
    // <= MAX_LANCZOS_DOWNSAMPLE (linear); above that a CONNECTED per-column min/max
    // envelope takes over (Java ScopeView.drawTrace `envelope` gate). The sinc envelope
    // path reconstructs around each column's extremes; a single point/column would
    // low-pass narrow pulses and the noise band away.
    const envelope = sinc ? (samplesPerPx > 1.0) : (samplesPerPx > MAX_LANCZOS_DOWNSAMPLE);
    if (!envelope) {
      // Java lineAttrsTrace: oscLineWidth, CAP_ROUND, JOIN_ROUND.
      g.lineWidth = this.prefs ? this.prefs.oscLineWidth.get() : 2.0;
      g.lineCap = 'round'; g.lineJoin = 'round';
      g.beginPath();
      if (sinc) {
        // Java sinc: scale = max(1, samplesPerPx); one reconstructed point per
        // pixel column + an anchor TRACE_EDGE_OVERHANG_PX outside each edge.
        const scale = Math.max(1.0, samplesPerPx);
        for (let i = 0; i <= width + 1; i++) {
          const x = this._sincTraceX(i, width);
          const v = lanczos(buf, n, dispStart + subSampleOffset + this._sincTraceX(i, width) * samplesPerPx, scale);
          const y = sampleToY(v);
          i === 0 ? g.moveTo(x, y) : g.lineTo(x, y);
        }
      } else {
        // Java linear (sin x/x off): ONE linearly-interpolated point per pixel
        // column — the same per-column structure as the sinc branch (sincTraceX),
        // straight-line interpolation (lerpAt) instead of Lanczos. A shallow ramp
        // renders as a smooth sub-pixel polyline instead of one-point-per-sample
        // stair-steps. (Java drawTrace lerpAt branch.)
        for (let i = 0; i <= width + 1; i++) {
          const x = this._sincTraceX(i, width);
          const v = lerpAt(buf, n, dispStart + subSampleOffset + this._sincTraceX(i, width) * samplesPerPx);
          const y = sampleToY(v);
          i === 0 ? g.moveTo(x, y) : g.lineTo(x, y);
        }
      }
      g.stroke();
      // High-zoom per-sample dots (Java pxPerSample > 10).
      const dotDiameter = (dotDiameterOverride !== undefined) ? dotDiameterOverride
        : (this.prefs ? this.prefs.oscDotDiameter.get() : 5);
      if (pxPerSample > 10.0 && dotDiameter > 0) {
        const dotShift = subSampleOffset * pxPerSample;
        const half = Math.trunc(dotDiameter / 2);
        const iStart = Math.floor(dotShift / pxPerSample);
        const iEnd = Math.ceil((width + dotShift) / pxPerSample);
        for (let i = iStart; i <= iEnd; i++) {
          const dataIdx = dispStart + i;
          if (dataIdx < 0 || dataIdx >= n) continue;
          const sx = Math.round(i * pxPerSample - dotShift);
          if (sx < 0 || sx >= width) continue;
          const sy = Math.round(sampleToY(buf[dataIdx]));
          // Java fillOval(sx-half, sy-half, dotDiameter, dotDiameter): an oval
          // inscribed in that box → centre (sx-half+dotDiameter/2, …), radius dotDiameter/2.
          const r = dotDiameter / 2;
          g.beginPath(); g.arc(sx - half + r, sy - half + r, r, 0, 2 * Math.PI); g.fill();
        }
      }
    } else {
      this._drawEnvelope(g, buf, n, dispStart, subSampleOffset, dispCount,
                         width, samplesPerPx, sampleToY, sinc, blankBeyondData);
    }
  }

  /** Draws a CONNECTED per-column min/max envelope (more than one sample per pixel) —
   *  each column is a [min, max] vertical bar with a bridging connector across disjoint
   *  adjacent columns (TraceEnvelope#connectorAttach) so the trace stays continuous and
   *  isolated spikes trace to their tip. Linear takes the raw-sample min/max; sin(x)/x
   *  additionally reconstructs the band-limited curve around each column's extreme samples
   *  (TraceEnvelope#reconstructedColumns) to recover a peak the (clock-drifting) samples
   *  missed. Faithful port of Java ScopeView.drawEnvelope + TraceEnvelope.
   *  Java lineAttrsBars: 1 px, CAP_FLAT, JOIN_BEVEL, antialias OFF. */
  _drawEnvelope(g, data, n, dispStart, subSampleOffset, dispCount, width, samplesPerPx, sampleToY, sinc, blankBeyondData) {
    const dispLimit = Math.min(dispStart + dispCount, n);
    if (!this._envMin || this._envMin.length < width) {
      this._envMin = new Float32Array(width);
      this._envMax = new Float32Array(width);
      this._envEntry = new Float32Array(width);
      this._envExit = new Float32Array(width);
    }
    const envMin = this._envMin, envMax = this._envMax;
    const envEntry = this._envEntry, envExit = this._envExit;
    if (sinc) {
      this._reconstructedColumns(data, n, dispStart, subSampleOffset, width,
                                 samplesPerPx, RECON_REFINE_STEP, blankBeyondData, dispLimit, envMin, envMax);
    } else {
      this._rawColumns(data, n, dispStart, subSampleOffset, width,
                       samplesPerPx, blankBeyondData, dispLimit, envMin, envMax);
    }
    // Where each column's connector attaches to its bar — peak/trough-aware, so an
    // isolated spike is traced to its tip (TraceEnvelope#connectorAttach).
    this._connectorAttach(envMin, envMax, width, envEntry, envExit);
    g.lineWidth = 1; g.lineCap = 'butt'; g.lineJoin = 'bevel';
    g.beginPath();
    for (let x = 0; x < width; x++) {
      const cMin = envMin[x];
      if (Number.isNaN(cMin)) continue;
      const cMax = envMax[x];
      const yOfMin = Math.round(sampleToY(cMin));   // bottom (larger y)
      const yOfMax = Math.round(sampleToY(cMax));   // top (smaller y)
      g.moveTo(x + 0.5, yOfMax); g.lineTo(x + 0.5, yOfMin);   // the bar
      // Connector from the previous column (drawn iff both ends are set — the
      // boundary is disjoint and neither column is blank).
      const exitPrev = x > 0 ? envExit[x - 1] : NaN;
      if (!Number.isNaN(exitPrev) && !Number.isNaN(envEntry[x])) {
        const yExit = Math.round(sampleToY(exitPrev));
        const yEntry = Math.round(sampleToY(envEntry[x]));
        g.moveTo((x - 1) + 0.5, yExit); g.lineTo(x + 0.5, yEntry);
      }
    }
    g.stroke();
  }

  /** TraceEnvelope.rawColumns: min/max of the raw samples each pixel column covers;
   *  a column with no drawable sample is set to NaN. */
  _rawColumns(data, n, dispStart, subSampleOffset, width, samplesPerPx, blankBeyondData, dispLimit, outMin, outMax) {
    for (let x = 0; x < width; x++) {
      let startIdx = dispStart + Math.trunc(x * samplesPerPx + subSampleOffset);
      let endIdx = dispStart + Math.trunc((x + 1) * samplesPerPx + subSampleOffset);
      if (endIdx <= startIdx) endIdx = startIdx + 1;
      if (endIdx > dispLimit) endIdx = dispLimit;
      if (startIdx < dispStart) startIdx = dispStart;
      if (startIdx >= dispLimit) {
        if (blankBeyondData) { outMin[x] = NaN; outMax[x] = NaN; continue; }
        startIdx = dispLimit - 1;
      }
      if (startIdx < 0) { outMin[x] = NaN; outMax[x] = NaN; continue; }
      let min = data[startIdx], max = data[startIdx];
      for (let i = startIdx + 1; i < endIdx; i++) {
        const v = data[i];
        if (v < min) min = v;
        if (v > max) max = v;
      }
      outMin[x] = min;
      outMax[x] = max;
    }
  }

  /** TraceEnvelope.reconstructedColumns: like _rawColumns but, for sin(x)/x, recovers
   *  the true peak/trough by reconstructing the band-limited curve within ±1 sample of
   *  the extreme samples (_refineExtreme). */
  _reconstructedColumns(data, n, dispStart, subSampleOffset, width, samplesPerPx, refineStep, blankBeyondData, dispLimit, outMin, outMax) {
    for (let x = 0; x < width; x++) {
      let startIdx = dispStart + Math.trunc(x * samplesPerPx + subSampleOffset);
      let endIdx = dispStart + Math.trunc((x + 1) * samplesPerPx + subSampleOffset);
      if (endIdx <= startIdx) endIdx = startIdx + 1;
      if (endIdx > dispLimit) endIdx = dispLimit;
      if (startIdx < dispStart) startIdx = dispStart;
      if (startIdx >= dispLimit) {
        if (blankBeyondData) { outMin[x] = NaN; outMax[x] = NaN; continue; }
        startIdx = dispLimit - 1;
      }
      if (startIdx < 0) { outMin[x] = NaN; outMax[x] = NaN; continue; }
      let min = data[startIdx], idxMin = startIdx;
      let max = data[startIdx], idxMax = startIdx;
      for (let i = startIdx + 1; i < endIdx; i++) {
        const v = data[i];
        if (v < min) { min = v; idxMin = i; }
        if (v > max) { max = v; idxMax = i; }
      }
      outMax[x] = this._refineExtreme(data, n, idxMax, refineStep, max, true);
      outMin[x] = this._refineExtreme(data, n, idxMin, refineStep, min, false);
    }
  }

  /** TraceEnvelope.refineExtreme: reconstructs the band-limited curve within ±1 sample
   *  of the extreme sample at idx and returns the more-extreme of seed (the raw sample)
   *  and the curve — recovering a crest/trough that drifted between samples. */
  _refineExtreme(data, n, idx, step, seed, findMax) {
    let best = seed;
    const lo = Math.max(0.0, idx - 1.0);
    const hi = Math.min(n - 1.0, idx + 1.0);
    for (let pos = lo; pos <= hi; pos += step) {
      const v = lanczos(data, n, pos, 1.0);
      if (findMax ? v > best : v < best) best = v;
    }
    return best;
  }

  /** TraceEnvelope.connectorAttach: per-column entry/exit attach Y (peak/trough/monotonic
   *  rules) for the bridging connector across disjoint adjacent columns. NaN when that side
   *  has no connector (blank/missing neighbour or an overlapping range). */
  _connectorAttach(colMin, colMax, width, entryAttach, exitAttach) {
    for (let x = 0; x < width; x++) {
      const cMin = colMin[x];
      if (Number.isNaN(cMin)) { entryAttach[x] = NaN; exitAttach[x] = NaN; continue; }
      const cMax = colMax[x];
      const lValid = x > 0 && !Number.isNaN(colMin[x - 1]);
      const rValid = x < width - 1 && !Number.isNaN(colMin[x + 1]);
      // Clean peak / trough: this column's whole range is above / below BOTH neighbours.
      const peak = lValid && rValid && colMax[x - 1] < cMin && colMax[x + 1] < cMin;
      const trough = lValid && rValid && colMin[x - 1] > cMax && colMin[x + 1] > cMax;

      if (!lValid || !this._disjoint(colMin, colMax, x - 1, x)) {
        entryAttach[x] = NaN;
      } else if (peak) {
        entryAttach[x] = cMax;
      } else if (trough) {
        entryAttach[x] = cMin;
      } else if (cMin > colMax[x - 1]) {     // this column above the left one (rising in) → enter at bottom
        entryAttach[x] = cMin;
      } else {                                // this column below the left one (falling in) → enter at top
        entryAttach[x] = cMax;
      }

      if (!rValid || !this._disjoint(colMin, colMax, x, x + 1)) {
        exitAttach[x] = NaN;
      } else if (peak) {
        exitAttach[x] = cMax;
      } else if (trough) {
        exitAttach[x] = cMin;
      } else if (colMin[x + 1] > cMax) {      // right column above (rising out) → leave at top
        exitAttach[x] = cMax;
      } else {                                // right column below (falling out) → leave at bottom
        exitAttach[x] = cMin;
      }
    }
  }

  /** Two columns' ranges share no Y overlap (one bar is entirely above the other). */
  _disjoint(colMin, colMax, a, b) {
    return colMax[a] < colMin[b] || colMax[b] < colMin[a];
  }

  /** X position for sinc-trace point i of width+2 (Java sincTraceX): first/last
   *  map TRACE_EDGE_OVERHANG_PX outside the canvas, the rest one per column. */
  _sincTraceX(i, width) {
    if (i === 0) return -TRACE_EDGE_OVERHANG_PX;
    if (i === width + 1) return width - 1 + TRACE_EDGE_OVERHANG_PX;
    return i - 1;
  }

  /** On-canvas sliders (faithful port of Java ScopeView.drawSliders): dashed
   *  trigger-level line + LEFT-pointing triangle handle on the right edge, dashed
   *  trigger-position cursor + UP-pointing triangle handle on the bottom edge, and
   *  the active measurement channel's vertical-offset track + RIGHT-pointing
   *  triangle handle on the left edge. Each handle registers a hit-box the mouse
   *  handlers consult; the trigger sliders are hidden (and their hit-boxes cleared)
   *  in file mode, exactly as the Java does. */
  _drawSliders(g, W, H, levelFrac, posFrac, descByName) {
    const p = this.prefs;
    const o = this._overlay;

    // Handle footprint along an edge (Java SLIDER_TRI_LONG + 4) — used to stop each
    // slider line short of the OPPOSITE edge's marker/handle so the dashed track
    // never overlaps the other slider's triangle (Java drawSliders insets via
    // levelLineStartX / offsetLineRightEnd). Kept as a FIXED px inset in the DOM so
    // the clip tracks the on-screen handle size, not the stretched canvas.
    const HANDLE_INSET = SLIDER_TRI_LONG + 4;

    // ----- Trigger level: dashed horizontal line + LEFT-pointing handle on the
    // RIGHT edge (always bright yellow). The line stops short of the LEFT edge so it
    // clears the offset-channel handles there (Java levelLineStartX). Hidden in file
    // mode. The hit-box is still set in CANVAS px so dragging is unchanged.
    if (!this.fileMode) {
      const levelY = Math.round(levelFrac * H);
      if (o) this._positionSlider(o.trigLevel, 'h', levelFrac, '#ffff00', HANDLE_INSET, 0);
      this._triggerLevelBounds = { x: W - SLIDER_TRI_LONG - 2, y: levelY - SLIDER_GRAB_HALF,
        w: SLIDER_TRI_LONG + 4, h: 2 * SLIDER_GRAB_HALF };
    } else {
      if (o) this._hideSlider(o.trigLevel);
      this._triggerLevelBounds = { x: -1, y: -1, w: 0, h: 0 };
    }

    // ----- Trigger position: dashed vertical cursor + UP-pointing handle on the
    // BOTTOM edge (bright white #ffffff). Hidden in file mode.
    if (!this.fileMode) {
      const posX = Math.round(posFrac * W);
      if (o) this._positionSlider(o.trigPos, 'v', posFrac, '#ffffff', 0, 0);
      this._triggerPosBounds = { x: posX - SLIDER_GRAB_HALF, y: H - SLIDER_TRI_LONG - 2,
        w: 2 * SLIDER_GRAB_HALF, h: SLIDER_TRI_LONG + 4 };
    } else {
      if (o) this._hideSlider(o.trigPos);
      this._triggerPosBounds = { x: -1, y: -1, w: 0, h: 0 };
    }

    // ----- Channel offsets: each enabled channel's zero-line + RIGHT-pointing handle
    // on the LEFT edge (Java drawSliders: drawOffsetTrack per channel). The ACTIVE
    // (measurement) channel's line is its full trace colour and its handle is
    // draggable (registers the hit-box); every OTHER channel's line is a darker
    // (~0.5 attenuated) variant of its trace colour. The line is clipped at BOTH ends
    // so it clears its own left handle and the trigger-level handle on the right
    // (Java offsetLineRightEnd / C36 clip).
    const measCh = p ? p.oscMeasurementChannel.get() : 'L';
    const activeDesc = descByName[measCh] || descByName.L || descByName.R;
    const placeOffset = (ch, d, hex, isActive) => {
      const offsetY = Math.round(clamp01(d.offsetFrac) * H);
      if (o) {
        const s = o.offset[ch];
        this._positionSlider(s, 'h', clamp01(d.offsetFrac), hex, HANDLE_INSET, HANDLE_INSET);
        // Active channel paints above the inactive one on exact-overlap (Java draws
        // inactive first, active last): lift the active line/handle a stacking level.
        const z = isActive ? '1' : '';
        s.core.style.zIndex = z; s.handle.style.zIndex = z;
      }
      if (isActive) {
        this._offsetBounds = { x: 0, y: offsetY - SLIDER_GRAB_HALF,
          w: SLIDER_TRI_LONG + 4, h: 2 * SLIDER_GRAB_HALF };
      }
    };
    if (o) { this._hideSlider(o.offset.L); this._hideSlider(o.offset.R); }
    if (activeDesc) {
      for (const ch of ['L', 'R']) {
        const d = descByName[ch];
        if (!d) continue;
        const isActive = d === activeDesc;
        placeOffset(ch, d, isActive ? d.hex : attenuateHex(d.colorInt, 0.5), isActive);
      }
    } else {
      this._offsetBounds = { x: -1, y: -1, w: 0, h: 0 };
    }
  }

  /** True iff canvas-space (mx,my) lies inside a {x,y,w,h} hit-box. */
  _inBounds(b, mx, my) {
    return b.w > 0 && b.h > 0 && mx >= b.x && mx < b.x + b.w && my >= b.y && my < b.y + b.h;
  }

  /** Maps a window mouse event to canvas-space pixel coordinates (CSS px == the
   *  backing store px since render() sizes the canvas to its CSS box). */
  _mouseCanvasXY(e) {
    const rect = this.cv.getBoundingClientRect();
    return { x: e.clientX - rect.left, y: e.clientY - rect.top };
  }

  /** mousedown: grab whichever handle is under the cursor (first match wins, Java
   *  order: offset → trigger-level → trigger-position) and move it immediately so a
   *  click without a drag still snaps the slider to the cursor. */
  _onSliderMouseDown(e) {
    if (e.button !== 0 || !this.prefs) return;
    const { x, y } = this._mouseCanvasXY(e);
    if (this._inBounds(this._offsetBounds, x, y)) this._draggingSlider = OscSliderId.OFFSET;
    else if (!this.fileMode && this._inBounds(this._triggerLevelBounds, x, y)) this._draggingSlider = OscSliderId.TRIGGER_LEVEL;
    else if (!this.fileMode && this._inBounds(this._triggerPosBounds, x, y)) {
      this._draggingSlider = OscSliderId.TRIGGER_POSITION;
    } else {
      // No slider grabbed: focus the view and possibly start a rect-zoom
      // selection drag (Java ScopeView.pointerDown → rectZoomPointerDown).
      if (this._rectZoom && this._rectZoom.pointerDown(x, y)) e.preventDefault();
      return;
    }
    e.preventDefault();
    this._updateSliderFromMouse(x, y);
  }

  /** mousemove: while dragging, drive the grabbed slider; otherwise update the
   *  cursor to a resize arrow over a handle (Java MouseMoveListener). */
  _onSliderMouseMove(e) {
    if (!this.prefs) return;
    if (this._draggingSlider) {
      const { x, y } = this._mouseCanvasXY(e);
      this._updateSliderFromMouse(x, y);
      return;
    }
    const { x, y } = this._mouseCanvasXY(e);
    if (this._rectZoom) {
      this._rectZoom.pointerMove(x, y);   // rubber-band update (no-op unless dragging)
      // Mid-selection the hover hit-tests stay quiet — no cursor flips or
      // tooltips while the rubber band crosses sliders (Java pointerMove).
      if (this._rectZoom.isDragActive()) return;
    }
    let cursor = '';
    let tip = '';
    if (this._inBounds(this._offsetBounds, x, y)) {
      cursor = 'ns-resize';
      const ch = this.prefs.oscMeasurementChannel.get();
      tip = (ch === 'R' ? 'Right' : 'Left') + ' channel vertical offset — drag, double-click to centre';
    } else if (!this.fileMode && this._inBounds(this._triggerLevelBounds, x, y)) {
      cursor = 'ns-resize';
      tip = 'Trigger level — drag, double-click to centre';
    } else if (!this.fileMode && this._inBounds(this._triggerPosBounds, x, y)) {
      cursor = 'ew-resize';
      tip = 'Trigger position / time offset — drag, double-click to centre';
    }
    // V/time edge-marker tooltips now live on the DOM overlay labels' own `title`
    // attribute (the labels are real DOM elements), so no canvas hit-box is needed.
    if (this.cv.style.cursor !== cursor) this.cv.style.cursor = cursor;
    if (this.cv.title !== tip) this.cv.title = tip;
  }

  /** mouseup: commit a rect-zoom selection, else end the slider drag and persist
   *  (Java ScopeView.pointerUp: rectZoomPointerUp() first, then the slider). */
  _onSliderMouseUp() {
    if (this._rectZoom && this._rectZoom.pointerUp()) return;
    if (!this._draggingSlider) return;
    this._draggingSlider = null;
    if (this.prefs && this.prefs.save) this.prefs.save();
  }

  /** dblclick on a handle resets it to centre (Java mouseDoubleClick → 0.5). */
  _onSliderDblClick(e) {
    if (e.button !== 0 || !this.prefs) return;
    const { x, y } = this._mouseCanvasXY(e);
    const p = this.prefs;
    let changed = false;
    if (this._inBounds(this._offsetBounds, x, y)) {
      (p.oscMeasurementChannel.get() === 'L' ? p.oscLeftOffsetFrac : p.oscRightOffsetFrac).set(0.5);
      changed = true;
    } else if (!this.fileMode && this._inBounds(this._triggerLevelBounds, x, y)) {
      p.oscTriggerLevelFrac.set(0.5); changed = true;
    } else if (!this.fileMode && this._inBounds(this._triggerPosBounds, x, y)) {
      p.oscTriggerPositionFrac.set(0.5); changed = true;
    }
    if (changed) {
      this._draggingSlider = null;
      if (p.save) p.save();
      if (this.onSettingsChanged) this.onSettingsChanged();
    }
  }

  /** Writes the grabbed slider's pref from the canvas-space mouse position (Java
   *  updateSliderFromMouse): OFFSET ← my/H on the measurement channel and TRIGGER_LEVEL
   *  ← my/H are clamped to [0,1]; TRIGGER_POSITION is absolute mx/W, clamped to [0,1]
   *  (grabbing a virtual, edge-pinned handle recaptures it to the cursor). */
  _updateSliderFromMouse(mx, my) {
    const W = this.cv.clientWidth || this.cv.width, H = this.cv.clientHeight || this.cv.height;
    if (W <= 0 || H <= 0 || !this._draggingSlider) return;
    const p = this.prefs;
    if (this._draggingSlider === OscSliderId.OFFSET) {
      const frac = clamp01(my / H);
      (p.oscMeasurementChannel.get() === 'L' ? p.oscLeftOffsetFrac : p.oscRightOffsetFrac).set(frac);
    } else if (this._draggingSlider === OscSliderId.TRIGGER_LEVEL) {
      p.oscTriggerLevelFrac.set(clamp01(my / H));
    } else if (this._draggingSlider === OscSliderId.TRIGGER_POSITION) {
      // Absolute: the line follows the cursor (centred under it), clamped to [0,1] —
      // so grabbing a virtual (edge-pinned) handle recaptures it to the cursor, and
      // the handle can never be pushed off the view (Java updateSliderFromMouse
      // TRIGGER_POSITION branch, spec rev).
      p.oscTriggerPositionFrac.set(clamp01(mx / W));
    }
    // Mirror the change onto the tab fields/tiles (Java refreshTab on the pref change).
    if (this.onSettingsChanged) this.onSettingsChanged();
  }

  // ───────────── Rectangular zoom (base machinery in rect-zoom.js) ─────────────
  // Faithful port of the Java ScopeView zoom overrides (commit 73f6c1f). The one
  // web-model difference: the web LIVE view has no scroll-back (a back-offset > 0
  // exists only in file mode), so Java's isAbsoluteWindow() == fileMode here and
  // the live backOffset 0↔>0 history clear has no web counterpart.

  /** The scope plots edge-to-edge (no axis margins) — the zoomable area is the
   *  full canvas (Java ScopeView inherits the full-client-area default). */
  _zoomableArea() {
    const W = this.cv.clientWidth || this.cv.width || 1200;
    const H = this.cv.clientHeight || this.cv.height || 240;
    return { x: 0, y: 0, w: W, h: H };
  }

  /** Slider handles stay grabbable — a selection drag can't start on them (Java
   *  ScopeView.isRectZoomBlockedAt; the header buttons + edge labels of the Java
   *  list are DOM elements here — the toolbar sits outside the canvas and the
   *  overlay labels are pointer-events:none — so only the three canvas hit-boxes
   *  need blocking). */
  _isRectZoomBlockedAt(x, y) {
    return this._inBounds(this._offsetBounds, x, y)
      || this._inBounds(this._triggerLevelBounds, x, y)
      || this._inBounds(this._triggerPosBounds, x, y);
  }

  /** Overlay-only repaint (rubber band / focus border): Canvas2D just redraws the
   *  current frame (Java requestZoomOverlayRepaint default; the GL phosphor
   *  re-composite override is GPU-only and intentionally skipped on the web). */
  _repaintZoomOverlay() {
    if (this._lastInfo) this.render(this._lastBuf, this._lastInfo);
    else this.renderIdle();
  }

  /** X = the displayed time window — trigger-relative SECONDS on the live/frozen
   *  view, absolute loaded-buffer FRAMES in file mode (the web's absolute-window
   *  model; mode switches clear the undo stack, so mementos never cross the two).
   *  Y = each channel's displayed voltage window; both round-trip losslessly to
   *  t/div + trigger frac + V/div + offsetFrac (Java ScopeView.captureZoomState). */
  _captureZoomState() {
    const p = this.prefs;
    if (!p) return null;
    const windowSec = p.oscTimePerDiv.get() * DIVISIONS_X;
    let xMin, xMax;
    if (this.fileMode) {
      const geom = this._fileGeom;
      if (!geom || !this.onFileBack) return null;
      // The canvas maps [startSample, startSample + dispCount] edge-to-edge
      // (render() sets samplesPerPx = dispCount / width).
      xMin = geom.startSample;
      xMax = geom.startSample + geom.dispCount;
    } else {
      const pos = p.oscTriggerPositionFrac.get();   // raw — virtual-capable
      xMin = -pos * windowSec;
      xMax = (1 - pos) * windowSec;
    }
    const lOff = p.oscLeftOffsetFrac.get(), lVdiv = p.oscLeftVoltsPerDiv.get();
    const rOff = p.oscRightOffsetFrac.get(), rVdiv = p.oscRightVoltsPerDiv.get();
    return { xMin, xMax,
      yMin: [(lOff - 1) * DIVISIONS_Y * lVdiv, (rOff - 1) * DIVISIONS_Y * rVdiv],
      yMax: [lOff * DIVISIONS_Y * lVdiv, rOff * DIVISIONS_Y * rVdiv] };
  }

  /** Applies a zoom memento (Java ScopeView.applyZoomState): per-channel V/div +
   *  offset from the voltage windows, the trigger LEVEL held invariant as a
   *  VOLTAGE (the pref is a screen fraction — remapped through the old→new
   *  trigger-channel mapping so the trigger keeps firing on the same waveform
   *  point; virtual-capable, a zoom may park it outside [0,1]), and the time
   *  window as t/div + trigger frac (live) or t/div + back-offset (file). */
  _applyZoomState(s) {
    const p = this.prefs;
    if (!p) return false;
    const trigLeft = p.oscTriggerChannel.get() === 'L';
    const oldOff = trigLeft ? p.oscLeftOffsetFrac.get() : p.oscRightOffsetFrac.get();
    const oldVdiv = trigLeft ? p.oscLeftVoltsPerDiv.get() : p.oscRightVoltsPerDiv.get();
    const levelVolts = (oldOff - p.oscTriggerLevelFrac.get()) * DIVISIONS_Y * oldVdiv;
    this._applyChannelVoltageRange(true, s.yMin[0], s.yMax[0]);
    this._applyChannelVoltageRange(false, s.yMin[1], s.yMax[1]);
    const newOff = trigLeft ? p.oscLeftOffsetFrac.get() : p.oscRightOffsetFrac.get();
    const newVdiv = trigLeft ? p.oscLeftVoltsPerDiv.get() : p.oscRightVoltsPerDiv.get();
    if (newVdiv > 0) {
      // Virtual-capable like the trigger offset: a zoom that excludes the
      // trigger voltage parks the level outside [0,1] — honest, undoable.
      p.oscTriggerLevelFrac.set(newOff - levelVolts / (DIVISIONS_Y * newVdiv));
    }
    const span = s.xMax - s.xMin;
    if (span > 0) {
      if (this.fileMode) {
        // Absolute branch (Java: controller.setViewCenterFrames + applyViewState):
        // when the t/div floor engages, the window widens around the selection's
        // CENTRE. The web drives the pane's back-offset through onFileBack, the
        // same path as the file-mode wheel zoom.
        const geom = this._fileGeom;
        const sr = (this._lastInfo && this._lastInfo.inRate) || 0;
        if (geom && this.onFileBack && sr > 0) {
          const tDivNew = Math.max(T_PER_DIV_MIN, span / sr / DIVISIONS_X);
          p.oscTimePerDiv.set(tDivNew);   // BEFORE onFileBack — the pane's clamp reads the new t/div
          const wsNew = Math.round(tDivNew * DIVISIONS_X * sr);
          const rightPad = Math.min(LANCZOS_PADDING, Math.max(0, geom.available - wsNew));
          const rightEdgeAnchorNew = Math.max(0, geom.available - rightPad - wsNew);
          const startNew = (s.xMin + s.xMax) / 2 - wsNew / 2;
          this.onFileBack(Math.round(rightEdgeAnchorNew - startNew));
        }
      } else {
        // Trigger-anchored branch: when the t/div floor engages, p pins the
        // selection's LEFT edge (Java). A held (trigger-less rendered) frame
        // anchors at the fixed point of the window change instead.
        const tDivNew = Math.max(T_PER_DIV_MIN, span / DIVISIONS_X);
        this._seedHeldZoomAnchor(s, tDivNew);
        p.oscTimePerDiv.set(tDivNew);
        p.oscTriggerPositionFrac.set(-s.xMin / (tDivNew * DIVISIONS_X));   // virtual-capable
      }
    }
    if (p.save) p.save();
    // Mirror onto the tab fields + tiles and repaint a frozen/file view (the Java
    // V/div reverse bindings + refreshTab + redraw → web onSettingsChanged →
    // host.refreshFields/refreshTiles/requestRedraw).
    if (this.onSettingsChanged) this.onSettingsChanged();
    else this._repaintZoomOverlay();
    return true;
  }

  /** Voltage window → V/div + offsetFrac for one channel: vDiv = span/10,
   *  offsetFrac = top/span (the zero line's screen fraction), clamped at the
   *  toolbar V/div field's floor (Java ScopeView.applyChannelVoltageRange). */
  _applyChannelVoltageRange(left, bottomV, topV) {
    const p = this.prefs;
    const span = topV - bottomV;
    if (span <= 0) return;
    const vDiv = Math.max(V_PER_DIV_MIN, span / DIVISIONS_Y);
    const off = topV / (vDiv * DIVISIONS_Y);
    if (left) {
      p.oscLeftOffsetFrac.set(off);
      p.oscLeftVoltsPerDiv.set(vDiv);
    } else {
      p.oscRightOffsetFrac.set(off);
      p.oscRightVoltsPerDiv.set(vDiv);
    }
  }

  /** Held frame (frozen snapshot): its renderer (_drawHeldFrame) ignores the
   *  trigger-position pref and anchors a t/div change at _heldZoomAnchorX — seed
   *  it with the FIXED POINT of the window change, anchor/width =
   *  (newXMin − curXMin) / (curSpan − newSpan), so both a committed selection and
   *  its Ctrl+Z inverse land exactly (a linear map and its inverse share the
   *  fixed point). Skipped when the t/div pref won't change (nothing would
   *  consume the anchor). Java ScopeView.seedHeldZoomAnchor — the web held-frame
   *  renderer uses this model for NORMAL holds too, so both hold kinds seed. */
  _seedHeldZoomAnchor(s, tDiv) {
    if (!this._singleHeld && !this._normalFrame) return;
    if (tDiv === this.prefs.oscTimePerDiv.get()) return;
    const cur = this._captureZoomState();
    const area = this._zoomableArea();
    if (!cur || !area || area.w <= 0) return;
    const denom = (cur.xMax - cur.xMin) - tDiv * DIVISIONS_X;
    if (denom === 0) return;
    const anchor = Math.round(area.w * (s.xMin - cur.xMin) / denom);
    this._heldZoomAnchorX = Math.max(0, Math.min(area.w, anchor));
  }

  /** The scope plots edge-to-edge, so the selection maps linearly onto the
   *  current window on both axes — per channel vertically, since the rect is
   *  screen-space and applies to every scale alike (Java ScopeView.zoomStateForRect). */
  _zoomStateForRect(sel) {
    const area = this._zoomableArea();
    if (!area || area.w <= 0 || area.h <= 0) return null;
    const cur = this._captureZoomState();
    if (!cur) return null;
    const fx0 = (sel.x - area.x) / area.w;
    const fx1 = (sel.x + sel.w - area.x) / area.w;
    const xSpan = cur.xMax - cur.xMin;
    const fy0 = (sel.y - area.y) / area.h;
    const fy1 = (sel.y + sel.h - area.y) / area.h;
    const nyMin = [0, 0];
    const nyMax = [0, 0];
    for (let ch = 0; ch < 2; ch++) {
      const top = cur.yMax[ch];
      const vSpan = top - cur.yMin[ch];
      nyMax[ch] = top - fy0 * vSpan;
      nyMin[ch] = top - fy1 * vSpan;
    }
    return { xMin: cur.xMin + fx0 * xSpan, xMax: cur.xMin + fx1 * xSpan,
      yMin: nyMin, yMax: nyMax };
  }

  /** Edge labels (Java drawEdgeLabels): absolute time at the left/right of the
   *  horizontal centre line, and each enabled channel's grid min/max voltage at
   *  the top/bottom of the vertical centre line in its own colour. Now positioned
   *  as FIXED-size DOM <div>s in the overlay layer (not painted on the canvas) so a
   *  stretched screenshot leaves the text at its on-screen pixel size. Anchored to
   *  the canvas EDGES as percentages so they land at the same edge positions
   *  regardless of the canvas pixel size. */
  _drawEdgeLabels(descriptors, tDiv, posFrac) {
    const o = this._overlay;
    if (!o) return;
    const windowTime = tDiv * DIVISIONS_X;
    const fmtS = (s) => Math.abs(s) >= 1 ? s.toFixed(3) + ' s'
      : Math.abs(s) >= 1e-3 ? (s * 1e3).toFixed(3) + ' ms' : (s * 1e6).toFixed(3) + ' µs';
    const fmtV = (v) => Math.abs(v) >= 1 ? v.toFixed(3) + ' V'
      : Math.abs(v) >= 1e-3 ? (v * 1e3).toFixed(2) + ' mV' : (v * 1e6).toFixed(1) + ' µV';
    // Time labels straddle the horizontal centre line (top:50% lifted 4px above the
    // line via translateY(-100%-4px)). Left edge = left-anchored, right edge =
    // right-anchored. Pale grey, like the old #bbb canvas text.
    const leftStr = fmtS(-posFrac * windowTime);
    const rightStr = fmtS((1 - posFrac) * windowTime);
    const timeY = { top: '50%', transform: 'translateY(calc(-100% - 4px))' };
    this._setOverlayLabel(o.timeLeft, leftStr, '#bbb', this._timeTip(leftStr, true),
      { left: '4px', ...timeY });
    this._setOverlayLabel(o.timeRight, rightStr, '#bbb', this._timeTip(rightStr, false),
      { right: '4px', ...timeY });
    // Per-channel V labels straddle the vertical centre line; L channel sits to the
    // LEFT of centre (right-anchored at 50%), R channel to the RIGHT (left-anchored
    // at 50%). Top label near the top edge, bottom label near the bottom edge.
    const seen = { L: false, R: false };
    for (const d of descriptors) {
      const maxV = d.offsetFrac * DIVISIONS_Y * d.vDiv;
      const minV = -(1 - d.offsetFrac) * DIVISIONS_Y * d.vDiv;
      const chName = d.ch === 'L' ? 'Left' : 'Right';
      const side = d.ch === 'L'
        ? { right: 'calc(50% + 4px)' }
        : { left: 'calc(50% + 4px)' };
      this._setOverlayLabel(o.vTop[d.ch], fmtV(maxV), d.hex, this._voltTip(chName, true),
        { ...side, top: '2px' });
      this._setOverlayLabel(o.vBot[d.ch], fmtV(minV), d.hex, this._voltTip(chName, false),
        { ...side, bottom: '4px' });
      seen[d.ch] = true;
    }
    if (!seen.L) { o.vTop.L.style.display = 'none'; o.vBot.L.style.display = 'none'; }
    if (!seen.R) { o.vTop.R.style.display = 'none'; o.vBot.R.style.display = 'none'; }
  }

  /** Tooltip for a time edge marker (left = window start time, right = end). */
  _timeTip(str, isLeft) {
    return (isLeft ? 'Window start time: ' : 'Window end time: ') + str
      + ' (relative to the trigger)';
  }

  /** Tooltip for a per-channel voltage edge marker (top = grid max, bottom = min). */
  _voltTip(chName, isTop) {
    return chName + ' channel ' + (isTop ? 'top-of-grid' : 'bottom-of-grid') + ' voltage';
  }

  /** Measures the long fixed live measurement window (info.measBufL/R, Java
   *  ScopeMeasurementWorker's MEAS_MAX_SAMPLES ring read) CONTINUOUSLY, decoupled
   *  from the trigger/display (C24): called every render BEFORE any trigger-mode
   *  hold/blank branch, so the measurement table keeps updating in NORMAL-hold and
   *  SINGLE-armed even while the TRACE is frozen — exactly like Java's worker
   *  measures the ring independent of the display. Resolves the measurement channel
   *  (auto-flip if disabled), measures the FILTERED span, accumulates the rolling
   *  stats, and sets this.latest. The full-window compute is throttled to
   *  READOUT_THROTTLE_MS so per-paint cost stays bounded (the 8192-sample window is
   *  not recomputed every paint); between computes this.latest / measurementRows are
   *  left intact. Returns the measurement (so the live-render path can snapshot it
   *  into the held frame), or null when no live window is available (file mode) so
   *  the caller falls back to the displayed-span measurement.
   *  @returns {object|null} */
  _measureLiveWindow(info, descByName, descriptors, sampleRate, peakVolts, dual) {
    const p = this.prefs;
    let measCh = p ? p.oscMeasurementChannel.get() : (descriptors[0] && descriptors[0].ch);
    let measDesc = descByName[measCh];
    if (p && !measDesc && descriptors.length) {   // selected channel disabled → flip + persist + clear history
      measDesc = descriptors[0];
      p.oscMeasurementChannel.set(measDesc.ch);
      if (p.save) p.save();   // Java prepareMeasurementRows persists the flip
      this._clearMeasurementHistory();
    }
    if (!measDesc) measDesc = descriptors[0];
    if (!measDesc) return null;
    measCh = measDesc.ch;
    const measRaw = (measCh === 'R') ? info.measBufR : info.measBufL;
    if (!measRaw || !(info.measAvailable >= 64)) return null;   // no live window (file mode) → caller falls back
    // Throttle the full-window compute: between ticks keep the last result so the
    // table stays current without recomputing 8192 samples every paint.
    const nowMs = performance.now();
    if (this.latest && (nowMs - this._lastLiveMeasMs) < READOUT_THROTTLE_MS) return this.latest;
    this._lastLiveMeasMs = nowMs;
    const mLen = info.measAvailable;
    if (!this._measScratch || this._measScratch.length < mLen) this._measScratch = new Float32Array(mLen);
    const span = this._applyChannelFilters(measRaw, mLen, sampleRate, measDesc.name, this._measScratch,
      info.measAbsStart || 0, measDesc.name + 'Meas');
    const mainsMode = p ? p['osc' + measDesc.name + 'MainsSuppression'].get() : 'NONE';
    // The comb's delay lines start zeroed each pass, so its head is an
    // un-suppressed pass-through that would skew Vpp/Vrms: measure the settled
    // TAIL instead — ≈3 time-constants in, capped so at least half the window
    // remains (Java ScopeMeasurementWorker computeMeasurementOnce).
    let mData = span, mN = mLen;
    if (mainsMode === 'IIR_COMB') {
      const settle = Math.trunc(3.0 * sampleRate / (Math.PI * MAINS_NOTCH_BW_HZ));
      const from = Math.min(settle, Math.trunc(mLen / 2));
      if (from > 0) { mData = span.subarray(from); mN = mLen - from; }
    }
    let meas = compute(mData, mN, sampleRate, peakVolts, false);   // fast: broad-band scan runs off-thread
    if (dual) {
      // Beat view: single-value period/freq/duty are meaningless. Re-measure the
      // (real) tone frequency off the span and drop the time-domain fields.
      let f = refineFrequencyAround(span, mLen, sampleRate, info.f1Hz, info.f1Hz * 0.25);
      // Never derive frequency from the mains-canceller output: re-pin the
      // filtered-signal seed on the RAW window in a narrow ±2 Hz band (the
      // canceller notches can sit within a few Hz of the tone and pull it).
      if (mainsMode !== 'NONE' && f > 0) {
        const precise = refineFrequencyAround(measRaw, mLen, sampleRate, f, FREQ_REFINE_HALF_HZ);
        if (precise > 0) f = precise;
      }
      meas = (f > 0) ? withFrequency(withoutTimes(meas), f) : withoutTimes(meas);
    } else {
      if (Number.isNaN(meas.frequency)) {
        // Weak / noisy single tone: the cheap crossing-based search returned no frequency.
        // Fold in the latest off-thread broad-band scan and kick a fresh one (Java
        // ScopeMeasurementWorker) — only f / period lag, every other readout stays live.
        if (Number.isFinite(this._asyncFreq)) meas = withFrequency(meas, this._asyncFreq);
        if (!this._freqScan) this._freqScan = new FreqScanClient((hz) => { this._asyncFreq = hz; });
        this._freqScan.submit(mData, mN, sampleRate, peakVolts);
      }
      if (mainsMode !== 'NONE' && Number.isFinite(meas.frequency)) {
        // Re-pin the canceller-located tone on the RAW signal, free of the comb's
        // notch bias, in a narrow band around the seed (Java ScopeMeasurementWorker
        // two-step: the canceller only finds WHICH peak is the fundamental; the
        // precise frequency always comes from the raw window).
        const precise = refineFrequencyAround(measRaw, mLen, sampleRate, meas.frequency, FREQ_REFINE_HALF_HZ);
        if (Number.isFinite(precise)) meas = withFrequency(meas, precise);
      }
    }
    // Override the short-window amplitude with the POOLED long-window value. The pool is
    // fed the CONTIGUOUS capture gap every paint (_feedMeasurementPool), so Vmean/Vrms/Vpp
    // here are over the measurement-average window — every sample once — and their σ no
    // longer tracks the per-buffer fractional-cycle DC swing. Time-domain fields keep
    // their per-buffer estimate (already stable).
    const pooled = this._ampPool.pool(nowMs * NS_PER_MS, p ? p.oscMeasurementAverageSeconds.get() : 0, peakVolts);
    if (pooled) { meas.vpp = pooled.vpp; meas.vrms = pooled.vrms; meas.vmean = pooled.vmean; }
    this._accumulateMeasurements(meas, dual);
    this.latest = meas;
    return meas;
  }

  /** Pushes the measurement channel's per-frame measurement into the rolling
   *  MeasurementStats history and, at most every READOUT_THROTTLE_MS, rebuilds
   *  this.measurementRows (8 rows × {cur,avg,min,max,sigma}) over
   *  oscMeasurementAverageSeconds — Java prepareMeasurementRows + throttle. For
   *  dual-tone the time-domain rows (Tp/Tr/Tf/Duty) read '---' (NaN inputs). */
  _accumulateMeasurements(meas, dual) {
    const p = this.prefs;
    if (!p) { this.measurementRows = null; return; }
    const tableOn = p.oscShowMeasurementTable.get();
    if (!tableOn) { this.measurementRows = null; return; }
    const nowMs = performance.now();
    const tNs = nowMs * NS_PER_MS;
    const s = this._measStats;
    s.vpp.push(tNs, meas.vpp);
    s.vrms.push(tNs, meas.vrms);
    s.vmean.push(tNs, meas.vmean);
    s.period.push(tNs, meas.period);
    s.riseTime.push(tNs, meas.riseTime);
    s.fallTime.push(tNs, meas.fallTime);
    s.frequency.push(tNs, meas.frequency);
    s.dutyCycle.push(tNs, meas.dutyCycle);
    if (this.measurementRows && nowMs - this._lastMeasBuildMs < READOUT_THROTTLE_MS) return;
    const win = p.oscMeasurementAverageSeconds.get();
    this.measurementRows = [
      forVolts('Vpp', meas.vpp, s.vpp.rebuild(win)),
      forVolts('Vrms', meas.vrms, s.vrms.rebuild(win)),
      forVolts('Vmean', meas.vmean, s.vmean.rebuild(win)),
      forTime('Tp', meas.period, s.period.rebuild(win)),
      forTime('Tr', meas.riseTime, s.riseTime.rebuild(win)),
      forTime('Tf', meas.fallTime, s.fallTime.rebuild(win)),
      forFreq('f', meas.frequency, s.frequency.rebuild(win)),
      forPct('Duty', meas.dutyCycle, s.dutyCycle.rebuild(win)),
    ];
    this._lastMeasBuildMs = nowMs;
  }

  /** Clears the rolling measurement history (Java clearMeasurementHistory) — call
   *  on a measurement-channel switch / reset. Resets ONLY the per-quantity
   *  accumulators (avg/min/max/σ restart from the next frame); the CUR column and
   *  the cached rows stay so the table keeps showing the current value with no
   *  empty flash (C27). The throttle clocks are zeroed so the NEXT frame
   *  immediately recomputes the live window and rebuilds the rows with freshly
   *  restarted stats — matching Java, which keeps the worker's current value and
   *  rebuilds on the next paint. */
  _clearMeasurementHistory() {
    const s = this._measStats;
    for (const k in s) s[k].clear();
    this.resetMeasurementPool();   // drop pool + streaming filters so a channel switch restarts clean
    this._lastMeasBuildMs = 0;
    this._lastLiveMeasMs = 0;
    this._asyncFreq = NaN;         // drop the stale off-thread frequency (Java asyncFrequency reset)
  }

  /** Drops the amplitude pool + its streaming filter state (cursor overrun / channel
   *  switch / reset) so accumulation restarts clean from the next contiguous gap. */
  resetMeasurementPool() {
    this._ampPool.clear();
    this._poolFilt = {};
  }

  /** Folds the contiguous capture gap (every sample since the last paint, from the engine's
   *  measurement cursor) into the amplitude pool, streaming-filtered with PERSISTENT (never
   *  reset) HF-LPF + mains comb — so Vmean/Vrms/Vpp are taken over a long EFFECTIVE window
   *  (= oscMeasurementAverageSeconds) and their σ stops tracking the per-buffer DC swing.
   *  Runs every paint (contiguous), independent of the throttled readout. */
  _feedMeasurementPool(info, sampleRate) {
    const p = this.prefs;
    if (!p || !p.oscShowMeasurementTable.get()) return;
    const len = info.measGapLen | 0;
    if (len <= 0) return;
    const right = p.oscMeasurementChannel.get() === 'R';
    const gap = right ? info.measGapR : info.measGapL;
    if (!gap) return;
    // Filter prefs + state are keyed by the channel NAME ('Left'/'Right'), matching
    // _applyChannelFilters; only the gap buffers are keyed L/R.
    const filtered = this._streamFilterGap(gap, len, sampleRate, right ? 'Right' : 'Left');
    this._ampPool.add(performance.now() * NS_PER_MS, filtered, len);
  }

  /** Streaming HF-LPF + mains comb over the pool's contiguous gap with PERSISTENT state
   *  (no per-call reset — the gaps are contiguous so filter state carries across paints,
   *  unlike the display path which reprocesses overlapping windows and resets each time).
   *  Mirrors _applyChannelFilters' mode selection. Returns the filtered gap (reused scratch);
   *  the engine's gap buffer is never mutated. */
  _streamFilterGap(gap, len, sampleRate, ch) {
    const p = this.prefs;
    let out = this._poolScratch;
    if (!out || out.length < len) { out = new Float32Array(len); this._poolScratch = out; }
    out.set(gap.subarray(0, len));
    const lpfMode = p['osc' + ch + 'Lpf'].get();
    const mainsMode = p['osc' + ch + 'MainsSuppression'].get();
    if (lpfMode === 'NONE' && mainsMode === 'NONE') return out;
    const st = this._poolFilt[ch] || (this._poolFilt[ch] = {});
    if (lpfMode === 'HZ_80') {
      if (!st.lpf || st.lpfMode !== 'HZ_80' || st.lpfRate !== sampleRate) {
        st.lpf = new LowPassFilter(sampleRate, 80000.0, SCOPE_HF_LPF_ORDER);
        st.lpfMode = 'HZ_80'; st.lpfRate = sampleRate;
      }
      if (st.lpf.isActive()) st.lpf.process(out, len);   // NO reset → continuous stream
    } else if (lpfMode === 'DESPIKE') {
      if (!st.lpf || st.lpfMode !== 'DESPIKE') { st.lpf = new MedianFilter(7); st.lpfMode = 'DESPIKE'; }
      st.lpf.process(out, len);
    } else { st.lpf = null; st.lpfMode = null; }
    if (mainsMode !== 'NONE') {
      if (!st.mains || st.mainsRate !== sampleRate || st.mainsMode !== mainsMode) {
        st.mains = mainsFilterOf(mainsMode, sampleRate, MAINS_NOTCH_BW_HZ);
        st.mainsRate = sampleRate; st.mainsMode = mainsMode; st.trackT0 = 0; st.absPos = 0;
      }
      const now = performance.now();
      if ((now - st.trackT0) >= 200 || !st.mains.isTuned()) { st.mains.track(out, len); st.trackT0 = now; }
      // NO reset → continuous canceller. The gaps are contiguous, so a running
      // sample counter gives the phase-locked cancellers their absStart deltas.
      st.mains.processPreservingDc(out, len, st.absPos);
      st.absPos += len;
    } else { st.mains = null; st.mainsMode = null; }
    return out;
  }

  /** Advances the actual capture rate from genuinely-new-frame timestamps (Java
   *  ScopeView.updateCaptureRate). `newFrame` = a fresh trigger / AUTO free-run this
   *  paint; a held frame passes false so the displayed rate decays toward zero
   *  instead of stalling at the last EMA value or showing the paint-loop rate. */
  _updateCaptureRate(newFrame) {
    const now = performance.now();
    // Glitch mode (Java updateCaptureRate GLITCH branch): rate = caught count ÷
    // collection time. Events are seconds to minutes apart, so a per-interval EMA
    // would just decay toward 0 between catches; the cumulative rate is the honest
    // figure. Counting restarts on glitch-mode entry, a record restart
    // (restartGlitchRate) and a trigger-source change (resetTriggerHold).
    const glitchMode = !!this.prefs && !this.fileMode
      && this.prefs.oscTriggerType.get() === TriggerType.GLITCH;
    if (glitchMode) {
      if (!this._rateWasGlitchMode || this._glitchCountStartMs === 0) {
        this._glitchCountStartMs = now;
        this._glitchCount = 0;
      }
      if (newFrame) {
        this._glitchCount++;
        this._lastNewFrameMs = now;
      }
      const elapsed = (now - this._glitchCountStartMs) / 1000;
      this._captureRate = (elapsed >= GLITCH_RATE_MIN_SECONDS) ? this._glitchCount / elapsed : 0.0;
      this._rateWasGlitchMode = true;
      return;
    }
    this._rateWasGlitchMode = false;
    if (newFrame) {
      if (this._lastNewFrameMs > 0) {
        const dt = (now - this._lastNewFrameMs) / 1000;
        if (dt > 0.001 && dt < 1.0) {
          const instant = 1.0 / dt;
          this._captureRate = (this._captureRate <= 0) ? instant : this._captureRate * 0.9 + instant * 0.1;
        } else if (dt >= 1.0) {
          this._captureRate = 1.0 / dt;
        }
      }
      this._lastNewFrameMs = now;
    } else if (this._lastNewFrameMs > 0) {
      // No new frame this paint — clamp the displayed rate to the instantaneous
      // "since last new frame" rate so a held pane visibly decays toward 0.
      const age = (now - this._lastNewFrameMs) / 1000;
      if (age > 0.5) {
        const instant = 1.0 / age;
        if (instant < this._captureRate) this._captureRate = instant;
      }
    } else {
      this._captureRate = 0;
    }
  }

  /** Near-white "123.456 cap/s" readout in the top-right corner (Java
   *  ScopeView.drawCaptureRate). A FIXED-size DOM label in the overlay layer
   *  (not painted on the canvas) so a stretched screenshot leaves it at its
   *  on-screen pixel size. Visible for the whole live record — even reading
   *  0.000: in glitch mode a new frame only arrives per glitch, so this readout
   *  IS the glitches/s counter and must not vanish between events. A STOPPED
   *  scope keeps showing the last value (no repaint runs while stopped); hidden
   *  only in file mode (no capture) and before the first capture ever ran
   *  (`show` = false from renderIdle). 3 decimals: in glitch mode a rare event
   *  (one per minutes) reads e.g. 0.008 — one decimal would show 0.0. */
  _drawCaptureRate(show = true) {
    const o = this._overlay;
    if (!o) return;
    if (!show || this.fileMode) { o.caps.style.display = 'none'; return; }
    const rate = this._captureRate;
    // Throttle the formatted string to ~5 Hz (Java READOUT_THROTTLE_NS = 200 ms).
    const nowMs = performance.now();
    if (this._capsString === '' || nowMs - this._lastCapsBuildMs >= READOUT_THROTTLE_MS) {
      this._capsString = `${rate.toFixed(3)} cap/s`;
      this._lastCapsBuildMs = nowMs;
    }
    this._setOverlayLabel(o.caps, this._capsString, '#f0f0f0', '', { right: '8px', top: '4px' });
  }

  /** Stamps the loaded-file name STATICALLY in the top-right corner of the
   *  CAPTURED image (Java ScopeView.drawFilePath, which paints the loaded
   *  filename top-right; on the live canvas it blinks via the BlinkBanner, in
   *  the offscreen capture it must render static — C29b). Only the offscreen
   *  screenshot clone sets this.screenshotFilePath, so the live render never
   *  draws it (the live view shows the self-blinking #scopeFileBanner instead).
   *  Drawn on the row below the cap/s readout, right-aligned, with a dark
   *  outline so it reads over the trace; left-truncated with a "…" prefix when
   *  it doesn't fit. */
  _drawStaticFilePath(g, W) {
    const path = this.screenshotFilePath;
    if (!path || !this.fileMode) return;
    g.save();
    g.font = '600 12px system-ui, sans-serif';
    g.textBaseline = 'alphabetic';
    g.textAlign = 'right';
    // Left-truncate with an ellipsis prefix so the file-name END stays visible
    // (Java drawFilePath left-side-truncates), keeping the label within ~half
    // the canvas width.
    const maxW = W * 0.5;
    let text = path;
    if (g.measureText(text).width > maxW) {
      while (text.length > 1 && g.measureText('…' + text).width > maxW) text = text.slice(1);
      text = '…' + text;
    }
    const x = W - 8;
    const y = 30;   // one row below the cap/s readout (drawn at y = 16)
    g.lineWidth = 3; g.lineJoin = 'round';
    g.strokeStyle = '#000';
    g.strokeText(text, x, y);
    g.fillStyle = '#f0f0f0';   // static near-white (Java BLINK_LIT, no blink)
    g.fillText(text, x, y);
    g.restore();
  }

  /** Snapshots the just-rendered live frame into this._frame for _drawHeldFrame,
   *  copying each channel's (and the optional beat overlay's) samples into a
   *  grow-only reused buffer so there is no per-paint allocation — the per-frame
   *  cost is a single bounded memcpy per channel. Called every live render so a
   *  held frame is always available to freeze (SINGLE/NORMAL trigger) or to seed
   *  on an Auto→SINGLE switch (C19). */
  _snapshotFrame(descriptors, trigDesc, trigBuf, dual, p, available,
                 startSample, windowSamples, cols, samplesPerPx, pxPerSample, peakV, meas) {
    const copyInto = (slot, src) => {
      let dst = this._snapBufs[slot];
      if (!dst || dst.length < available) { dst = new Float32Array(available); this._snapBufs[slot] = dst; }
      dst.set(src.subarray(0, available));
      return dst;
    };
    const chans = descriptors.map((d, i) => ({
      ch: d.ch,
      snap: copyInto(i, (dual && d === trigDesc) ? trigBuf : d.procBuf),
      offsetFrac: d.offsetFrac, vDiv: d.vDiv, dcOff: d.dcOff, sinc: d.sinc, hex: d.hex,
    }));
    const beat = (dual && p && p.oscShowReconstructedBeat.get())
      ? { snap: copyInto(descriptors.length, trigBuf), offsetFrac: trigDesc.offsetFrac,
          vDiv: trigDesc.vDiv, dcOff: 0.0, sinc: false, beat: true,
          hex: attenuateHex(trigDesc.colorInt, 0.45) }
      : null;
    this._frame = { chans, beat, available, startSample, windowSamples, cols,
                    samplesPerPx, pxPerSample, peakV, meas,
                    tDivAtFreeze: p ? p.oscTimePerDiv.get() : 0 };
  }

  /** Repaints the frozen NORMAL/SINGLE frame from its captured snapshot (the live
   *  ring keeps scrolling, so the held trace renders from a copy, not the ring),
   *  then re-draws the SAME overlays the live path draws (Java paintCanvas always
   *  runs drawSliders / drawEdgeLabels / drawCaptureRate after drawWaveforms,
   *  whether the frame is live or held): the on-canvas trigger-level / trigger-
   *  position / channel-offset handles, the per-channel V/time EDGE MARKERS, and
   *  the cap/s readout — now showing the ACTUAL (decaying) capture rate. The DOM
   *  measurement table reads this.measurementRows, which is NOT nulled here, so it
   *  stays visible too. No new full-window compute: traces come from the captured
   *  snapshot, overlays from live prefs, cap/s from the new-frame timestamp EMA. */
  /** Paints the static scope chrome — graticule grid + V/time edge marks + trigger /
   *  channel sliders + cap/s — with NO trace, so the grid, controls and marks are
   *  ALWAYS visible even before recording starts or when stopped with no captured
   *  frame (Java ScopeView always paints the empty grid). Sizes the backing store to
   *  the CSS box (live/virtual px) so the canvas is never left blank/default-sized. */
  renderIdle() {
    const g = this.g;
    const W = this.cv.clientWidth || this.cv.width || 1200;
    const H = this.cv.clientHeight || this.cv.height || 240;
    if (this.cv.width !== W) this.cv.width = W;
    if (this.cv.height !== H) this.cv.height = H;
    g.setTransform(1, 0, 0, 1, 0, 0);
    g.fillStyle = '#000'; g.fillRect(0, 0, W, H);
    this._drawGraticule(g, W, H);
    const p = this.prefs;
    if (p) {
      const descByName = {};
      const descriptors = [];
      for (const ch of ['L', 'R']) {
        const lc = ch === 'L' ? 'Left' : 'Right';
        if (!p['osc' + lc + 'ChannelEnabled'].get()) continue;
        const d = { ch,
          offsetFrac: p['osc' + lc + 'OffsetFrac'].get(),
          vDiv: p['osc' + lc + 'VoltsPerDiv'].get() || 0.1,
          hex: colorHex(p['osc' + lc + 'ChannelColor'].get()) };
        descByName[ch] = d; descriptors.push(d);
      }
      const posFrac = clamp01(p.oscTriggerPositionFrac.get());
      this._drawSliders(g, W, H, clamp01(p.oscTriggerLevelFrac.get()), posFrac, descByName);
      this._drawEdgeLabels(descriptors, p.oscTimePerDiv.get(), posFrac);
    } else {
      this._clearOverlayLabels();
    }
    this._updateCaptureRate(false);
    this._drawCaptureRate(false);   // no capture ever ran → hidden (Java reader == null)
    if (this._rectZoom) this._rectZoom.drawOverlay(g, W, H);   // zoom layer LAST, on the idle grid too
    this._lastBuf = null; this._lastInfo = null;   // mark idle so the ResizeObserver repaints via renderIdle
  }

  /** Draws the empty zoomed-overview grid (no trace), so the zoomed view is ALWAYS
   *  visible like the main scope even before recording (Java ZoomedView paints its
   *  grid at all times). Mirrors renderZoomed's grid block. */
  renderZoomedIdle(canvas) {
    if (!canvas) return;
    const g = canvas.getContext('2d');
    const W = canvas.clientWidth || canvas.width || 1200;
    const H = canvas.clientHeight || canvas.height || 60;
    if (canvas.width !== W) canvas.width = W;
    if (canvas.height !== H) canvas.height = H;
    g.setTransform(1, 0, 0, 1, 0, 0);
    if (W <= 0 || H <= 0) return;
    g.fillStyle = '#000'; g.fillRect(0, 0, W, H);
    g.lineWidth = 1;
    g.strokeStyle = '#3c3c3c';
    g.beginPath();
    for (let i = 0; i <= DIVISIONS_X; i++) { const gx = Math.round(i / DIVISIONS_X * W); g.moveTo(gx + 0.5, 0); g.lineTo(gx + 0.5, H); }
    g.moveTo(0, 0.5); g.lineTo(W - 1, 0.5);
    g.moveTo(0, H - 0.5); g.lineTo(W - 1, H - 0.5);
    g.stroke();
    g.strokeStyle = '#6e6e6e';
    const cy = Math.round(H / 2);
    g.beginPath(); g.moveTo(0, cy + 0.5); g.lineTo(W - 1, cy + 0.5); g.stroke();
  }

  _drawHeldFrame(g, W, H) {
    const f = this._frame;
    if (!f) { this.latest = null; return; }
    this._drawGraticule(g, W, H);
    // Vertical mapping is taken LIVE from prefs (Java renderHeldCapturedFrame is
    // handed the current V/div, and renderTraces reads oscLeft/RightOffsetFrac
    // live): only the SAMPLES and the horizontal window are frozen, so a Ctrl-wheel
    // V/div zoom (and an offset drag) re-scales the held trace immediately instead
    // of a no-op. The cached snapshot dcOff / sinc / colour stay frozen (Java
    // caches capturedDcL/R at freeze time); the beat overlay has no live V/div of
    // its own, so it keeps the trigger channel's snapshot scale.
    const p = this.prefs;
    const liveVDiv = (ch) => p ? (p['osc' + (ch === 'L' ? 'Left' : 'Right') + 'VoltsPerDiv'].get() || 0.1) : null;
    const liveOff = (ch) => p ? p['osc' + (ch === 'L' ? 'Left' : 'Right') + 'OffsetFrac'].get() : null;
    // Horizontal t/div zoom on a FROZEN frame (Java renderHeldCapturedFrame with the
    // heldViewStart / lastHeldTimePerDiv / lastHeldDispCount model, 4887ecb): re-derive
    // the displayed window from the LIVE t/div. The held view position persists on the
    // frame object (f is replaced on every live paint, so a fresh capture re-enters the
    // not-yet-anchored branch, like Java's entry frame). A WHEEL zoom keeps the sample
    // under the CURSOR put (this._heldZoomAnchorX, set by the wheel handler); a t/div
    // FIELD change leaves the anchor unset so the SCREEN CENTRE stays put instead of
    // jumping to the stale cursor position. NOT clamped — off-buffer edges blank
    // (blankBeyondData below), matching Java.
    const tDivLive = p ? p.oscTimePerDiv.get() : f.tDivAtFreeze;
    const scale = (f.tDivAtFreeze > 0 && tDivLive > 0) ? tDivLive / f.tDivAtFreeze : 1;
    const winSamples = Math.max(2, Math.round(f.windowSamples * scale));
    if (!(f.heldTDiv > 0)) {                                  // entry frame, not yet anchored
      f.heldStart = f.startSample;
    } else if (tDivLive !== f.heldTDiv && f.heldWin > 0) {
      const frac = (this._heldZoomAnchorX >= 0 && W > 0)
        ? clamp01(this._heldZoomAnchorX / W) : 0.5;
      this._heldZoomAnchorX = -1;                             // consumed
      const anchorSample = f.heldStart + frac * f.heldWin;    // sample under anchor before zoom
      f.heldStart = anchorSample - frac * winSamples;         // keep it under the anchor
    }
    f.heldTDiv = tDivLive;
    f.heldWin = winSamples;
    const startS = f.heldStart;
    const sPerPx = winSamples / f.cols;
    const pxPerS = f.cols / winSamples;
    const drawSnap = (c) => {
      const vDiv = (!c.beat && c.ch && p) ? liveVDiv(c.ch) : c.vDiv;
      const offsetFrac = (!c.beat && c.ch && p) ? liveOff(c.ch) : c.offsetFrac;
      const sampleToY = (s) => H * (offsetFrac - ((s - c.dcOff) * f.peakV) / (vDiv * DIVISIONS_Y));
      this._drawTrace(g, W, H, c.snap, f.available, startS, winSamples,
                      f.cols, sPerPx, pxPerS, sampleToY, c.sinc, c.hex,
                      c.beat ? 0 : undefined, true);   // held magnify → blankBeyondData (Java renderHeldCapturedFrame)
    };
    for (const c of f.chans) drawSnap(c);
    if (f.beat) drawSnap(f.beat);
    // Overlays — driven by LIVE prefs (Java drawSliders / drawEdgeLabels read
    // Preferences, not the snapshot), so dragging a handle on a frozen trace updates
    // the prefs and re-triggers the next live frame, and the edge markers track the
    // current V/div · t/div. Built once and shared by both helpers (item: reuse).
    if (p) {
      const descByName = {};
      const descriptors = [];
      for (const ch of ['L', 'R']) {
        const lc = ch === 'L' ? 'Left' : 'Right';
        if (!p['osc' + lc + 'ChannelEnabled'].get()) continue;
        const d = {
          ch,
          offsetFrac: p['osc' + lc + 'OffsetFrac'].get(),
          vDiv: p['osc' + lc + 'VoltsPerDiv'].get() || 0.1,
          hex: colorHex(p['osc' + lc + 'ChannelColor'].get()),
        };
        descByName[ch] = d;
        descriptors.push(d);
      }
      const posFrac = clamp01(p.oscTriggerPositionFrac.get());
      this._drawSliders(g, W, H, clamp01(p.oscTriggerLevelFrac.get()), posFrac, descByName);
      this._drawEdgeLabels(descriptors, p.oscTimePerDiv.get(), posFrac);
    } else {
      this._clearOverlayLabels();
    }
    // Held frame: no new trigger this paint, so the cap/s rate decays toward 0
    // (passing false), then renders the actual capture/trigger rate.
    this._updateCaptureRate(false);
    this._drawCaptureRate();
    this._drawStaticFilePath(g, W);
    // Rect-zoom rubber band + focused-view accent border — LAST (Java paint order).
    if (this._rectZoom) this._rectZoom.drawOverlay(g, W, H);
    // Do NOT overwrite this.latest with the stale snapshot meas: _measureLiveWindow
    // refreshed it (and the measurement table) THIS paint, decoupled from the held
    // trace (C24). Fall back to the snapshot's meas only if no live measurement
    // exists yet (e.g. file-mode held frame, which carries its own f.meas).
    if (!this.latest) this.latest = f.meas;
  }

  /**
   * Applies the active channel's HF cleanup (LpfMode) then mains-hum
   * suppression (MainsSuppression) to a copy of {@code buf}, in place on the
   * copy, and returns it. Faithful to ScopeView.applyHfLowPass +
   * applyMainsSuppression: LpfMode.HZ_80 → 80 kHz Chebyshev LP (a no-op below
   * its Nyquist), DESPIKE → median; mains is the mode-selected time-domain
   * canceller (dsp/mains/factory: IIR_COMB / SYNC_SUBTRACT / LMS — Java
   * MainsFilters.of). No-op (returns the raw buf) when both are NONE.
   * absStart is the absolute index of buf[0] in the capture stream — the
   * phase-locked cancellers (sync-subtract / LMS) advance their mains phase by
   * its delta across calls; the comb ignores it. stKey selects the filter-state
   * bag: the display pass uses the channel name, the measurement pass its own
   * '<ch>Meas' bag — Java keeps ScopeView's and ScopeMeasurementWorker's
   * filters separate, and the adaptive cancellers are stateful so the two
   * windows must not interleave through one instance.
   * @param {Float32Array} buf
   * @param {number} len
   * @param {number} sampleRate
   * @param {'Left'|'Right'} ch
   * @param {Float32Array} [outScratch]
   * @param {number} [absStart=0]
   * @param {string} [stKey=ch]
   * @returns {Float32Array}
   */
  _applyChannelFilters(buf, len, sampleRate, ch, outScratch, absStart = 0, stKey = ch) {
    const p = this.prefs;
    if (!p) return buf;
    const lpfMode = p['osc' + ch + 'Lpf'].get();
    const mainsMode = p['osc' + ch + 'MainsSuppression'].get();
    if (lpfMode === 'NONE' && mainsMode === 'NONE') return buf;

    const st = this._chanFilt[stKey] || (this._chanFilt[stKey] =
      { lpf: null, lpfMode: null, lpfRate: 0, mains: null, mainsMode: null, mainsRate: 0, trackT0: 0, buf: null });
    // The measurement pass passes its OWN scratch so it doesn't clobber the
    // display descriptor's procBuf (= st.buf), which the freeze-frame capture
    // still reads after the measurement runs.
    let out;
    if (outScratch && outScratch.length >= len) {
      out = outScratch;
    } else {
      if (!st.buf || st.buf.length < len) st.buf = new Float32Array(len);
      out = st.buf;
    }
    out.set(buf.subarray(0, len));

    // --- HF cleanup ---
    if (lpfMode === 'HZ_80') {
      if (!st.lpf || st.lpfMode !== 'HZ_80' || st.lpfRate !== sampleRate) {
        st.lpf = new LowPassFilter(sampleRate, 80000.0, SCOPE_HF_LPF_ORDER);
        st.lpfMode = 'HZ_80'; st.lpfRate = sampleRate;
      }
      if (st.lpf.isActive()) { st.lpf.reset(); st.lpf.process(out, len); }
    } else if (lpfMode === 'DESPIKE') {
      if (!st.lpf || st.lpfMode !== 'DESPIKE') {
        st.lpf = new MedianFilter(7); st.lpfMode = 'DESPIKE';   // LpfMode.DESPIKE.window
      }
      st.lpf.process(out, len);
    } else {
      st.lpf = null; st.lpfMode = null;
    }

    // --- mains-hum suppression (mode-selected canceller; re-tracked occasionally.
    // Comb: reset+applied each paint over the contiguous window so its start
    // transient stays off-screen left; the adaptive cancellers keep their learned
    // state across paints — Java applyMainsSuppression resets only IIR_COMB) ---
    if (mainsMode !== 'NONE') {
      if (!st.mains || st.mainsRate !== sampleRate || st.mainsMode !== mainsMode) {
        st.mains = mainsFilterOf(mainsMode, sampleRate, MAINS_NOTCH_BW_HZ);
        st.mainsRate = sampleRate; st.mainsMode = mainsMode; st.trackT0 = 0;
      }
      const now = performance.now();
      const retrack = (now - st.trackT0) >= 200;   // MAINS_TRACK_PERIOD (~200 ms)
      if (retrack || !st.mains.isTuned()) { st.mains.track(out, len); st.trackT0 = now; }
      if (mainsMode === 'IIR_COMB') st.mains.reset();
      st.mains.processPreservingDc(out, len, absStart);
    } else {
      st.mains = null; st.mainsMode = null;
    }
    return out;
  }

  /**
   * Renders the condensed overview strip below the main view (faithful port of
   * Java ZoomedView). The full pixel width maps to exactly ONE SECOND of audio
   * (1 s/div × 10 div → the whole capture overview), and the vertical scale
   * auto-fills the strip: ±1.0 normalised sample fills the full height,
   * regardless of the main view's V/div. Right-edge-anchored on the latest
   * samples. Draws both enabled channels in their trace colours; no triggers,
   * sliders, labels or measurements.
   * @param {HTMLCanvasElement} canvas the overview canvas
   * @param {Float32Array} buf the R channel (fallback for both)
   * @param {{inRate:number, available?:number, bufL?:Float32Array, bufR?:Float32Array}} info
   */
  renderZoomed(canvas, buf, info) {
    if (!canvas) return;
    const g = canvas.getContext('2d');
    // Attached canvas: CSS box; detached clone (screenshot): its pre-set backing
    // store, sized to match the LIVE zoomed canvas (C20d). Default last.
    const W = canvas.clientWidth || canvas.width || 1200;
    const H = canvas.clientHeight || canvas.height || 60;
    // Live/virtual pixels only — backing store == CSS box, no devicePixelRatio (matches render()).
    if (canvas.width !== W) canvas.width = W;
    if (canvas.height !== H) canvas.height = H;
    g.setTransform(1, 0, 0, 1, 0, 0);
    if (W <= 0 || H <= 0) return;

    // ----- background + grid (Java ZoomedView.drawGrid): 10 vertical divs +
    // top/bottom frame + a centre cross-hair line.
    g.fillStyle = '#000'; g.fillRect(0, 0, W, H);
    g.lineWidth = 1;
    g.strokeStyle = '#3c3c3c';
    g.beginPath();
    for (let i = 0; i <= DIVISIONS_X; i++) { const gx = Math.round(i / DIVISIONS_X * W); g.moveTo(gx + 0.5, 0); g.lineTo(gx + 0.5, H); }
    g.moveTo(0, 0.5); g.lineTo(W - 1, 0.5);
    g.moveTo(0, H - 0.5); g.lineTo(W - 1, H - 0.5);
    g.stroke();
    g.strokeStyle = '#6e6e6e';
    const cy = Math.round(H / 2);
    g.beginPath(); g.moveTo(0, cy + 0.5); g.lineTo(W - 1, cy + 0.5); g.stroke();

    const p = this.prefs;
    const showL = !p || p.oscLeftChannelEnabled.get();
    const showR = !p || p.oscRightChannelEnabled.get();
    if (p && !showL && !showR) return;

    const sampleRate = info.inRate;
    // FIXED span = exactly 1 second (Java ZoomedView), independent of the main
    // scope's t/div. Live record passes its own 1 s ring read via
    // info.displaySamples (engine.readZoomedWindow); file mode falls back to
    // sampleRate over the decoded buffer.
    const displaySamples = (info.displaySamples != null) ? info.displaySamples : sampleRate;
    if (displaySamples < 2) return;
    const available = (info.available != null) ? info.available : buf.length;
    if (available < 2) return;

    // Right-edge anchor the latest `displaySamples` (Java readEndingAt at
    // writePos − backOffset, here the latest available samples; padding kept
    // for the sinc kernel near the edges).
    const rightPad = Math.min(LANCZOS_PADDING, Math.max(0, available - displaySamples));
    const dispEnd = available - rightPad;
    const dispStart = Math.max(0, dispEnd - displaySamples);
    const dispCount = dispEnd - dispStart;
    if (dispCount < 2) return;

    const bufL = info.bufL || buf, bufR = info.bufR || buf;
    const centerY = H / 2.0;
    const vScale = H / 2.0;   // ±1.0 fills the full strip vertically
    const samplesPerPx = dispCount / W;
    const pxPerSample = W / dispCount;
    // Vertical map: full-scale (no V/div, no offset) — ±1.0 → ±H/2 (Java vScale).
    const sampleToY = (s) => centerY - s * vScale;
    if (showL) this._drawTrace(g, W, H, bufL, available, dispStart, dispCount, W, samplesPerPx, pxPerSample, sampleToY, true, colorHex(p ? p.oscLeftChannelColor.get() : 0x00d7ff), 0);
    if (showR) this._drawTrace(g, W, H, bufR, available, dispStart, dispCount, W, samplesPerPx, pxPerSample, sampleToY, true, colorHex(p ? p.oscRightChannelColor.get() : 0xffd700), 0);
  }
}
