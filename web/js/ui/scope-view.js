/*
 * Phonalyser web - precision audio measurement workbench (browser port).
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
import { compute, withoutTimes,
         reconstructBeatSignal,
         MeasurementStats, forVolts, forTime, forFreq, forPct }
  from '../scope/signal-measurements.js';
import { OscMeasCompute } from '../scope/osc-meas-compute.js';
import { SineFit } from '../dsp/sine-fit.js';
import { LowPassFilter, MedianFilter } from '../dsp/lpf.js';
import { mainsFilterOf } from '../dsp/mains/factory.js';
// Pan/zoom engine + the pure nav-math helpers + the trigger-slider enum - the single
// home for every horizontal/vertical move/zoom transform (Java ScopeNav / ScopeFormat /
// OscSliderId, OscTriggerMode/Edge). The wheel + on-canvas-slider handlers delegate here
// instead of re-deriving the math inline.
import { ScopeNav, DIVISIONS_X as NAV_DIVISIONS_X, DIVISIONS_Y as NAV_DIVISIONS_Y } from '../scope/scope-nav.js';
import { clamp01 as navClamp01 } from '../scope/scope-format.js';
import { OscSliderId, TriggerMode, TriggerEdge, TriggerType, PersistenceMode,
         effectiveSeconds as persistenceSeconds } from '../scope/scope-enums.js';
// GPU display persistence ("digital phosphor") - faithful port of
// org.edgo.audio.measure.gui.scope.gl.ScopePhosphor (engine) + the kind-decision
// state machine PhosphorGate. The view owns one of each and, per live frame with
// persistence active, routes the TRACE phase through the decayed WebGL accumulation
// buffer and composites BACKDROP + phosphor + OVERLAY (Java compositeToScreen).
import { ScopePhosphor, PhosphorGate } from '../scope/scope-phosphor.js';
// Dense-trace DIGITAL PHOSPHOR (DPO) renderer - faithful port of Java
// gui.scope.PhosphorRenderer + TraceEnvelope. THE dense renderer for the MAIN scope
// trace: Java ScopeView.drawTrace routes every > 1 sample/px window through
// drawDigitalPhosphor, so the web does the same (the condensed ZoomedView strip keeps its
// own per-column min/max bars - Java ZoomedView has no phosphor). Canvas2DAlphaPainter is
// the web replacement for MeasurementPainter.drawAlphaImage + AlphaImageScratch.
import { PhosphorRenderer, Canvas2DAlphaPainter } from '../scope/phosphor-renderer.js';
// Drag-select rectangular zoom + Ctrl+Z undo (Java AbstractMeasurementView's
// installRectZoom base machinery); the scope supplies the time/volts mappings +
// trigger preservation (Java ScopeView zoom overrides) and forwards its own
// pointer funnel (hookMouse = false, like Java's installRectZoom(this, false)).
import { RectZoom } from './rect-zoom.js';

// Grid divisions (the ½-div wheel step + the V/div ladder math now live in ScopeNav).
const DIVISIONS_X = NAV_DIVISIONS_X;
const DIVISIONS_Y = NAV_DIVISIONS_Y;
// One horizontal wheel tick = ½ division (Java ScopeController.HALF_DIV).
const HALF_DIV = 0.5;
const REL_EPS = 1e-9;
const clamp01 = navClamp01;

// ScopeView render-consumption constants (mirroring the Java ScopeView).
const SCOPE_HF_LPF_ORDER = 8;        // LowPassFilter order for LpfMode.HZ_80
// Sub-sample step for the sin(x)/x peak refinement in the envelope path (Java
// ScopeView.RECON_REFINE_STEP).
const RECON_REFINE_STEP = 0.1;
const MAINS_NOTCH_BW_HZ = 2.0;       // −3 dB notch width for the scope mains comb
// Residual-view fit constants (Java ScopeView RESIDUAL_*). The best-fit tone is
// subtracted from the displayed slice; these bound the least-squares fit window,
// the phase-slope frequency polish, and the dual-tone beat/refit requirements.
const RESIDUAL_MIN_CYCLES = 8;
const RESIDUAL_FIT_MAX_SAMPLES = 65536;
const RESIDUAL_MIN_FIT_SAMPLES = 256;
const RESIDUAL_POLISH_MAX_HZ = 1.0;
const RESIDUAL_POLISH_ITERS = 2;
const RESIDUAL_POLISH_MIN_STEP_HZ = 1e-6;
const RESIDUAL_MIN_BEAT_CYCLES = 2;
const RESIDUAL_DUAL_REFIT_ROUNDS = 2;
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
// How recently the osc-meas worker must have published for the render thread to REUSE its
// result (this.latest) instead of recomputing the synchronous fallback. Generous vs the
// worker's ~100 ms publish cadence so a couple of missed ticks don't drop the table to the
// short displayed-span compute; long enough that a stopped/absent worker stream (headless
// render, node tests) times out and the injected-window fallback engages instead.
const LIVE_MEAS_FRESH_MS = 500;
const NS_PER_MS = 1e6;
// Depth of the whole-period Vmean history ring (Java ScopeMeasurementWorker.MEAS_HISTORY_CAP).
const MEAS_HISTORY_CAP = 1024;
// Minimum averaging window for AC DC removal - never average less than this even
// when the measurement-average pref is shorter (Java ScopeView.AC_DC_MIN_AVG_NANOS).
const AC_DC_MIN_AVG_SEC = 0.5;
// Minimum glitch-mode collection time before the cumulative rate is shown - below
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
// web NumericStepModels in app.js initStepFields carry the same min values) -
// the rect zoom clamps its t/div + V/div writes to the same floors, or the
// fields' re-sync would desync pref and display.
const T_PER_DIV_MIN = 1e-6;
const V_PER_DIV_MIN = 1e-9;

/** Packs an int RGB (0xRRGGBB, as the channel-colour prefs store it) into a
 *  CSS '#rrggbb' string (Java color() role lookup -> hex). */
function colorHex(rgb) {
  return '#' + (rgb & 0xffffff).toString(16).padStart(6, '0');
}

/** Scales each 8-bit channel of an int RGB by `factor` (Java
 *  AbstractMeasurementView.attenuate) and returns the '#rrggbb' string - used
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
    // The pan/zoom engine - every horizontal/vertical move + zoom transform (Java
    // ScopeView.nav). Stateless apart from the grid divisions + the V/div ladder, so the
    // wheel + on-canvas-slider handlers share this one instance. The ladder arrives on
    // this.vDivSeries from the shell AFTER construction, so it is wired in lazily via
    // nav() (the engine reads the ladder live each call, so a late assignment is fine).
    this._nav = new ScopeNav(DIVISIONS_X, DIVISIONS_Y, null);
    // CSS-box resize -> re-render the current frame so the DPR-scaled backing
    // store is rebuilt at the new size. The live capture loop already repaints
    // every frame, but a LOADED / STOPPED / frozen view has no loop, so without
    // this the browser just stretches the stale backing store (unproportional).
    // Debounced through rAF; observes the CSS box (cv.width changes don't fire it).
    this._resizeObs = (typeof ResizeObserver !== 'undefined') ? new ResizeObserver(() => {
      if (this._resizePending) return;
      this._resizePending = true;
      requestAnimationFrame(() => {
        this._resizePending = false;
        this._repaintAtCurrentSize();
      });
    }) : null;
    if (this._resizeObs) this._resizeObs.observe(this.cv);
    this._beatScratch = {};   // grow-only reconstructBeatSignal scratch
    // Trigger-mode state machine (Java ScopeView). SINGLE: armed -> waiting for
    // one trigger, held -> its captured frame stays frozen. NORMAL: holds the
    // last triggered frame when triggers stop. The frozen frame is a snapshot
    // (this._frame) so the rolling ring scrolling out doesn't drift it.
    this._singleArmed = false;
    this._singleHeld = false;
    this._lastTriggerMode = null;
    this._normalFrame = null;   // truthy = a NORMAL frame is held; points at this._frame
    this._frame = null;         // captured snapshot used by _drawHeldFrame
    // Freeze-on-stop (Java ScopeView.freezeBuffer): when the recording stops the
    // last live frame is kept on screen and replayed through _drawHeldFrame for
    // EVERY trigger mode (not just SINGLE/NORMAL) - Java swaps in the frozen snapshot
    // and renders it through the same held path. renderFrozen() below is the
    // standalone entry the pane calls after a stop / on a resize.
    this._frozen = false;
    this._snapBufs = [];        // grow-only reused snapshot buffers (one per chan + beat)
    this._snapBeatBufs = {};    // grow-only reused per-channel HELD beat-overlay snapshots (keyed by 'L'/'R')
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
    // over an old one - the stacked mementos belong to the previous timeline.
    this._fileMode = false;
    // Loaded-file name to stamp STATICALLY on the canvas - set ONLY on the
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
    // Per-channel latest measurement snapshots (Java ScopeMeasurementWorker.lastMeasLeft /
    // lastMeasRight): the worker measures both channels; the TABLE + this.latest show the
    // selected one, but the residual + AC-DC + glitch paths read whichever channel they need.
    this._lastMeas = { L: null, R: null };
    // Whole-period Vmean history ring (Java ScopeMeasurementWorker meanHistoryLeftNorm /
    // meanHistoryRightNorm + measHistoryTime): every measurement tick publishes each
    // channel's WHOLE-PERIOD Vmean normalized by peak volts ("identical to the table
    // readout"); _acDcMean averages this ring for the AC-coupling DC block, the residual
    // baseline and auto-setup centring.
    this._meanHist = {
      L: { t: new Float64Array(MEAS_HISTORY_CAP), v: new Float64Array(MEAS_HISTORY_CAP), write: 0, size: 0 },
      R: { t: new Float64Array(MEAS_HISTORY_CAP), v: new Float64Array(MEAS_HISTORY_CAP), write: 0, size: 0 },
    };
    this._lastMeanNorm = { L: NaN, R: NaN };   // Java lastLeftMeanNormalized / lastRightMeanNormalized
    // The LIVE measurement stream runs in the osc-meas Web Worker (fed off its own gapless
    // ring reader by OscMeasClient); its publishes arrive via publishMeasurement(). For the
    // SYNCHRONOUS fallback path - a directly-injected measurement window (info.measBufL/R)
    // with no live worker stream (headless render, the node tests) - the same DOM-free
    // compute engine runs the one-shot pipeline right here. wall-clock of the last live
    // publish gates whether render() reuses this.latest or recomputes the fallback.
    this._measEngine = new OscMeasCompute();
    this._lastMeasPublishMs = 0;
    this._measDual = false;   // last render's dual-tone form (publishMeasurement has no info)
    this._lastMeasBuildMs = 0;
    this._lastLiveMeasMs = 0;   // throttle for the decoupled live-window measurement
    // Residual-view scratch (Java ScopeView.residualScratchL/R + residualFitScratch) +
    // the shared slice geometry the last _computeResidual produced (residualDispStart /
    // residualSliceLen) + per-channel VISIBLE-window residual Vpp for auto-setup.
    this._residualScratchL = null;
    this._residualScratchR = null;
    this._residualFitScratch = null;
    this._residualDispStart = 0;
    this._residualSliceLen = 0;
    this._lastResidualVpp = { L: NaN, R: NaN };
    this.measurementRows = null;
    // cap/s readout string cache (Java drawCaptureRate throttles the
    // String.format to READOUT_THROTTLE_NS = 200 ms; drawText still runs every paint).
    this._capsString = '';
    this._lastCapsBuildMs = 0;
    // Actual capture/trigger rate (Java captureRate + lastNewFrameNanos): EMA over
    // the intervals between genuinely-new frames (a fresh trigger / AUTO free-run),
    // decaying toward zero while a frame is held - so a NORMAL/SINGLE hold reads the
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
    // On-canvas drag-handle hit-boxes (Java ScopeView offsetSliderBounds /
    // triggerLevelBounds / triggerPosBounds) - refreshed every render in
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
    // useful parent - its overlay just stays null and nothing is drawn (the clone
    // carries the live layer's already-positioned labels).
    this._overlay = this._buildOverlayLayer(canvas);
    // Drag-select zoom + Ctrl+Z undo (Java ScopeView: installRectZoom(this, false)
    // - the rect zoom feeds off the pointer funnel below, so hookMouse stays off).
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
      // captures the pointer during a button-down drag - emulate with window).
      window.addEventListener('mousemove', (e) => this._onSliderMouseMove(e));
      window.addEventListener('mouseup', () => this._onSliderMouseUp());
    }

    // ----- display persistence ("digital phosphor") -----
    // The engine (WebGL2 RGBA16F accumulation) + the kind-decision gate are the web
    // mirror of Java SwtGlCanvasSurface owning a ScopePhosphor + the kind-override block
    // of ScopePhosphor.render() (:151-187). Both are LAZILY created on the first
    // persistence-active frame (_ensurePhosphor), so an OFF scope - the default - and the
    // headless node tests never touch WebGL. lastFrameWasNew is the web's
    // ScopeView.isLastFrameNew(): true only when a render path drew a genuinely new
    // captured trace (a fresh trigger / AUTO free-run), so REALTIME only accumulates then.
    this._phosphor = null;         // ScopePhosphor (WebGL2 engine); null until first active frame
    this._phosphorGate = null;     // PhosphorGate (pure kind decision); created with the engine
    this._traceScratch = null;     // transparent 2D canvas the TRACE phase draws into
    this._overlayScratch = null;   // transparent 2D canvas the OVERLAY phase draws into
    // Redirect hooks: while a persisted frame is being painted these point the TRACE and
    // OVERLAY draw calls at the scratch canvases instead of the main context, so the
    // backdrop stays on screen, the trace accumulates in the phosphor buffer and the
    // overlay is composited fresh on top (Java's Phase split). null on the OFF path, so
    // every draw hits this.g exactly as before - byte-identical to the pre-phosphor code.
    this._traceG = null;
    this._overlayG = null;
    // Java ScopeView.lastFrameWasNew (:421): defaults false each paint, flipped true by the
    // one render path that drew a genuinely new captured frame. Read by _withPhosphor to
    // gate REALTIME accumulation, and exposed for parity as isLastFrameNew().
    this._lastFrameWasNew = false;
    // ----- dense-trace digital phosphor (DPO) -----
    // ONE renderer + ONE blit painter per view (Java ScopeView holds ONE PhosphorRenderer,
    // reused across channels sequentially in a frame). THE dense renderer for the MAIN scope
    // trace: every > 1 sample/px window rasterises through it (Java ScopeView.drawTrace ->
    // drawDigitalPhosphor). The pooled coverage/offscreen buffers are grown lazily on the first
    // dense render, so a scope kept in the sparse regime and the headless node tests (whose
    // painter has no offscreen canvas) allocate nothing here.
    this._phosphorRenderer = new PhosphorRenderer();
    this._dpoPainter = new Canvas2DAlphaPainter();
  }

  /** File-load mode accessor (Java ScopeView.setFileMode): a mode switch
   *  invalidates every stacked zoom state - live mementos are trigger-relative
   *  seconds, file mementos absolute frames. A true->true set is a NEW file
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
    // Full-scale boundary lines (Java ScopeView.drawFullScaleLines, 2105-2123 /
    // 2162-2175): two dashed horizontals per channel at ±FS volts around its offset.
    // Appended FIRST - before the offset sliders below - so DOM paint order keeps the
    // brighter offset zero-line + triangle ON TOP where they overlap (Java draws the
    // FS lines before the offset track for exactly this reason). Java's FS_DASH {2,6}
    // (2px on, 6px gap) rides a repeating-linear-gradient background set inline.
    const makeFsLine = () => {
      const el = document.createElement('div');
      el.className = 'scope-ovl-fsline';
      el.style.display = 'none';
      layer.appendChild(el);
      return el;
    };
    const fsLine = {
      L: { top: makeFsLine(), bot: makeFsLine() },
      R: { top: makeFsLine(), bot: makeFsLine() },
    };
    // cap/s readout (top-right), the two time edge labels (left/right of the
    // horizontal centre), and the four per-channel V edge labels (top/bottom of the
    // vertical centre, one pair per channel). Each is created once and repositioned
    // every render; unused channel labels are simply hidden. The trigger-level /
    // trigger-position / per-channel-offset SLIDERS (dashed line + triangle handle)
    // are FIXED-size DOM overlays too - the canvas keeps only trace + grid + ticks.
    const ovl = {
      layer,
      fsLine,
      caps: make('scope-ovl-caps'),
      timeLeft: make('scope-ovl-time'),
      timeRight: make('scope-ovl-time'),
      vTop: { L: make('scope-ovl-volt'), R: make('scope-ovl-volt') },
      vBot: { L: make('scope-ovl-volt'), R: make('scope-ovl-volt') },
      // Slider VALUE labels (Java drawSliders drawOutlinedText): the trigger-level voltage
      // (right edge, yellow) and each channel's offset voltage (left edge, channel colour),
      // shown next to their handles.
      trigLevelVal: make('scope-ovl-volt'),
      // Trigger-position time-offset label (Java drawSliders posStr = formatSeconds(
      // (posReal − 0.5)·windowTime)): the REAL (virtual-capable) offset time, above the
      // bottom-edge handle, bright white like the handle.
      trigPosVal: make('scope-ovl-time'),
      offsetVal: { L: make('scope-ovl-volt'), R: make('scope-ovl-volt') },
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
   *  of the opposite-edge handle; ignored for the vertical line. */
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
      // tri-right) - the border-color above paints the triangle in the line colour.
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
    for (const el of [o.caps, o.timeLeft, o.timeRight, o.vTop.L, o.vTop.R, o.vBot.L, o.vBot.R,
      o.trigLevelVal, o.trigPosVal, o.offsetVal.L, o.offsetVal.R]) {
      el.style.display = 'none';
    }
    this._hideSlider(o.trigLevel);
    this._hideSlider(o.trigPos);
    this._hideSlider(o.offset.L);
    this._hideSlider(o.offset.R);
    // Full-scale boundary lines are structural siblings of the offset sliders - hide
    // them on the same blank-return paths (Java skips drawFullScaleLines here too).
    for (const ch of ['L', 'R']) {
      o.fsLine[ch].top.style.display = 'none';
      o.fsLine[ch].bot.style.display = 'none';
    }
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

  /** Voltage string with auto-prefix (V/mV/µV) + V/div-aware precision - faithful port of
   *  ScopeFormat.formatVolts. The resolution derives from vpdiv (one tenth of a division) so
   *  the label carries just enough decimals to distinguish adjacent trace pixels. */
  _fmtVolts(v, vpdiv) {
    const a = Math.abs(v);
    let unit, scaledV, scaledRes;
    if (a >= 1.0)       { unit = 'V';  scaledV = v;       scaledRes = vpdiv * 0.1; }
    else if (a >= 1e-3) { unit = 'mV'; scaledV = v * 1e3; scaledRes = vpdiv * 0.1 * 1e3; }
    else if (a >= 1e-6) { unit = 'µV'; scaledV = v * 1e6; scaledRes = vpdiv * 0.1 * 1e6; }
    else if (a === 0)   return '0 V';
    else                return v.toExponential(1) + ' V';   // Java %.2g fallback (sub-µV non-zero)
    let dp;
    if (scaledRes >= 1.0)   dp = 1;
    else if (scaledRes > 0) dp = Math.ceil(-Math.log10(scaledRes)) + 1;
    else                    dp = 3;
    if (dp < 1) dp = 1;
    if (dp > 6) dp = 6;
    return scaledV.toFixed(dp) + ' ' + unit;
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
   *  - called when the trigger SOURCE changes (type / edge / channel) or the generated
   *  signal changes: the old anchor belongs to the old trigger/signal and would keep
   *  re-rendering a stale trace. NORMAL / SINGLE then stay blank until the new trigger
   *  fires. Also restarts the glitch-mode cap/s collection - events caught under the
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
   *  pane on record start - which is also where the frozen-on-stop hold is dropped so
   *  the fresh live trace takes over. */
  restartGlitchRate() {
    this._glitchCountStartMs = 0;
    this._frozen = false;
  }

  /** Freeze-on-stop (Java ScopeView.freezeBuffer): the recording stopped, so keep the
   *  LAST live frame on screen and replay it through the held-frame path for EVERY
   *  trigger mode. The live paint already snapshotted that frame into this._frame
   *  (_snapshotFrame runs on every non-returning live paint, AUTO free-run included),
   *  carrying its drawn per-channel buffers AND the reconstructed-beat overlays - so
   *  the frozen replay draws the beat exactly as the last live frame did (the beat
   *  gate - dual + Reconstructed-beat - was evaluated at CAPTURE time). No-op when no
   *  live frame was ever captured (nothing to hold; the pane keeps the idle grid). */
  freeze() {
    this._frozen = !!this._frame;
  }

  /** The ResizeObserver's repaint of whatever the view currently shows, at the new
   *  canvas size. Frozen first, ALWAYS: a stopped scope's _lastBuf still points at
   *  the live capture ring (which keeps filling while other modules hold the
   *  device), so feeding it back through render() replaces the frozen trace with
   *  whatever the ADC carries now - and on macOS the overlay scrollbar fading in
   *  under the pointer is enough of a size change to fire this path. The held
   *  frame is the only honest content for a stopped scope; live and loaded views
   *  repaint exactly as before. */
  _repaintAtCurrentSize() {
    if (this.renderFrozen()) return;
    if (this._lastInfo) this.render(this._lastBuf, this._lastInfo);
    else this.renderIdle();   // no live/held/loaded frame yet -> keep the empty grid + marks painted at the new size
  }

  /** Standalone repaint of the frozen (stopped) frame - the pane calls this after a
   *  record stop and on a resize while stopped, since there is no live render loop to
   *  drive _drawHeldFrame. Sizes + clears the canvas (the live render() does this
   *  before delegating to _drawHeldFrame; here we own that step), then replays the
   *  held frame. No-op when not frozen or nothing was captured - the pane falls back
   *  to renderIdle. Returns whether it painted a frame. */
  renderFrozen() {
    if (!this._frozen || !this._frame) return false;
    const W = this.cv.clientWidth || this.cv.width || 1200;
    const H = this.cv.clientHeight || this.cv.height || 240;
    if (this.cv.width !== W) this.cv.width = W;
    if (this.cv.height !== H) this.cv.height = H;
    // A stopped-scope re-render is a COMPOSITE (Java SwtGlCanvasSurface expose/resize
    // path -> ScopePhosphor.Kind.COMPOSITE): the phosphor is frozen, so the wrapper
    // re-composites it (no decay, no accumulate) between the fresh backdrop + overlay.
    // _drawHeldFrame draws the backdrop (fillRect + graticule) on this.g and the trace +
    // overlay through the redirect hooks. OFF path: exactly the direct held render.
    this._withPhosphor(ScopePhosphor.Kind.COMPOSITE, W, H, () => {
      const g = this.g;
      g.setTransform(1, 0, 0, 1, 0, 0);
      g.fillStyle = '#000'; g.fillRect(0, 0, W, H);
      this._drawHeldFrame(g, W, H);
    });
    return true;
  }

  /** The pan/zoom engine with its V/div ladder kept in sync with this.vDivSeries (set
   *  by the shell after construction). The engine reads the ladder live, so refreshing
   *  the field here is enough - no rebuild. */
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
      // Shift + Ctrl + wheel: t/div zoom around the mouse X. A held frame rides the same
      // trigger-offset model as live - _zoomTimeAround updates the trigger-position
      // fraction to keep the sample under the cursor put, so no separate frozen-frame
      // anchor is needed (Java ScopePane wheel handler, ba42b91).
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
    // ScopePane.stepMeasurementChannelOffset -> ScopeNav.moveVertical). Wheel up
    // (dir = +1) moves the signal UP, so the offset fraction DECREASES; the engine
    // encodes that sign and clamps both channels at ±FS/2-at-middle. Trigger level is
    // intentionally NOT moved. An off-1-2-5 (manual) channel keeps its proportion.
    const p = this.prefs;
    // Each channel supplies its OWN ADC full-scale (LINKED cards pass equal L/R peaks) -
    // Java ScopeView.moveVerticalOffset getAdcPeakVolts(Channel.L)/(Channel.R).
    const peakL = p.getAdcPeakVolts('L'), peakR = p.getAdcPeakVolts('R');
    const oldL = p.oscLeftOffsetFrac.get(), oldR = p.oscRightOffsetFrac.get();
    const off = this.nav().moveVertical(
      oldL, p.oscLeftVoltsPerDiv.get(), p.oscLeftChannelEnabled.get(),
      oldR, p.oscRightVoltsPerDiv.get(), p.oscRightChannelEnabled.get(),
      dir, peakL, peakR);
    if (off[0] === oldL && off[1] === oldR) return;
    p.oscLeftOffsetFrac.set(off[0]);
    p.oscRightOffsetFrac.set(off[1]);
  }

  /** Bridges the web's file back-offset model to the Java `viewCenterFrames` nav
   *  engine. The web positions the file window by `back` = frames the window is
   *  scrolled back from the latest-window right-edge anchor; Java positions it by
   *  the absolute centre frame `viewCenterFrames`. They map linearly:
   *    maxCentre = frames − displaySamples/2   (back = 0 => centre = maxCentre)
   *    centre    = maxCentre − back            (back grows => centre decreases)
   *  so `back = maxCentre − centre`. Returns { centre, maxCentre, displaySamples,
   *  frames, sampleRate } or null when the file geometry isn't available yet. */
  _fileNavState() {
    const geom = this._fileGeom, p = this.prefs;
    if (!geom || !this.onFileBack || !p) return null;
    const sr = (this._lastInfo && this._lastInfo.inRate) || 0;
    if (sr <= 0) return null;
    const frames = geom.available;
    const displaySamples = geom.windowSamples;
    const maxCentre = frames - displaySamples / 2;
    return { centre: maxCentre - geom.back, maxCentre, displaySamples, frames, sampleRate: sr,
             samplesPerDiv: p.oscTimePerDiv.get() * sr };
  }

  _panHorizontal(dir) {
    const p = this.prefs;
    // File mode: Shift+wheel scrolls the read window THROUGH the loaded buffer by
    // ½ DIVISION per tick, fractional-exact (Java ScopePane.stepHorizontalOffset file
    // branch -> ScopeController.scrollFileByWheel -> ScopeNav.moveFileCentre with
    // samplesPerDiv = timePerDiv·sampleRate as an EXACT double). Wheel up (dir=+1)
    // scrolls toward OLDER samples: Java moves viewCenterFrames by −dir·½ div, which
    // (centre = maxCentre − back) grows `back`. The step accumulates unrounded so a
    // fine time base (⅕-div-scale ½-div steps of 8.82 samples at 44.1 kHz) never
    // quantises. The displayed signal + time marks + measured values all come from the
    // moved window, not just a relabelled axis.
    if (this.fileMode) {
      const st = this._fileNavState();
      if (!st) return;
      const next = this.nav().moveFileCentre(
        st.centre, -dir * HALF_DIV, st.samplesPerDiv, st.displaySamples, 0, st.frames);
      if (next === st.centre) return;
      this.onFileBack(st.maxCentre - next);   // fractional - onFileBack keeps the sub-sample scroll
      return;
    }
    // Frozen (a SINGLE/NORMAL frame is held): pan the held trace ½ div per tick via the
    // (virtual-capable) trigger offset - the same engine step as live. Every held frame
    // now carries a real anchor (the entry snapshot supplies a synthetic one, so
    // f.triggerLocal is never NaN), so _drawHeldFrame's single trigger-offset branch
    // re-derives the held window from p and scrolls the whole captured buffer (Java
    // ScopePane.stepHorizontalOffset FROZEN branch -> ScopeView.panFrozenOffset).
    if (this._singleHeld || this._normalFrame) {
      if (!this._frame) return;
      const curF = p.oscTriggerPositionFrac.get();
      const nextF = this.nav().moveTriggerOffset(curF, dir);
      if (nextF === curF) return;
      p.oscTriggerPositionFrac.set(nextF);
      return;
    }
    // Live: ½-div trigger-offset move via the engine; UNCLAMPED so it may go VIRTUAL
    // (the handle pins to the L/R edge while the time-offset mark shows the real value).
    // The read spans ~2 screens around the trigger; drawTrace blanks any edge the buffer
    // can't fill (Java ScopePane.stepHorizontalOffset live branch -> ScopeNav.moveTriggerOffset).
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
  // when no series is wired (no continuous fallback - snapping is the contract).
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
    // Ctrl+wheel: V/div zoom anchored at the mouse Y - both channels' V/div are coupled
    // (the 1-2-5 proportional rule) and each offset is re-anchored so the voltage under
    // the cursor stays put (Java ScopeTabControl.stepVoltsPerDivAround -> ScopeNav.zoomVertical,
    // zoom-out capped at the FS-fills-height ceiling). dir < 0 = finer (smaller V/div).
    const p = this.prefs, anchorFrac = mouseY / H;
    const leftEn = p.oscLeftChannelEnabled.get(), rightEn = p.oscRightChannelEnabled.get();
    if (!leftEn && !rightEn) return;
    const leftOld = p.oscLeftVoltsPerDiv.get(), rightOld = p.oscRightVoltsPerDiv.get();
    // Per-channel zoom-out ceiling from each channel's own ADC full-scale (Java
    // ScopeView.stepVoltsPerDivAround -> ScopeNav.zoomVertical two-peak overload).
    const peakL = p.getAdcPeakVolts('L'), peakR = p.getAdcPeakVolts('R');
    const r = this.nav().zoomVertical(
      leftOld, p.oscLeftOffsetFrac.get(), leftEn,
      rightOld, p.oscRightOffsetFrac.get(), rightEn,
      dir, anchorFrac, peakL, peakR);
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
    // triggerPositionFrac). Route through the nav engine exactly like Java
    // ScopeController.zoomFileAroundMouse -> ScopeNav.zoomFileCentre + clampFileCentre:
    // the sample under the pointer stays put as the window resizes dispOld->dispNew, then
    // the centre is clamped into the file - the clamp re-centres once the whole file
    // fills the width (the spec's file zoom-out limit). Work in the absolute-centre
    // model (centre = startSample + displaySamples/2), then convert the clamped centre
    // back to a FRACTIONAL back-offset against the new right-edge anchor.
    if (this.fileMode) {
      const geom = this._fileGeom;
      const sr = (this._lastInfo && this._lastInfo.inRate) || 0;
      if (geom && this.onFileBack && sr > 0) {
        const dispOld = geom.windowSamples;
        const dispNew = Math.round(tDivNew * DIVISIONS_X * sr);
        const frames = geom.available;
        const centreOld = geom.startSample + dispOld / 2;
        const nav = this.nav();
        const next = nav.zoomFileCentre(centreOld, mouseFrac, dispOld, dispNew);
        const centreNew = nav.clampFileCentre(next, dispNew, 0, frames);
        // back = 0 pins the RIGHT edge at `frames` (viewLeft = frames − dispNew), the same
        // no-rightPad anchor the render + _fileNavState + fileMaxBack all use (Java file
        // window right edge = writePos − viewBackOffset). back = rightEdgeAnchor − startNew.
        const rightEdgeAnchorNew = Math.max(0, frames - dispNew);
        const startNew = centreNew - dispNew / 2;
        this.onFileBack(rightEdgeAnchorNew - startNew);   // fractional
      }
      return;
    }
    // Live: anchor the sample under the mouse via the engine, using the ACTUAL
    // displaySamples ratio (not the raw t/div ratio, which round-diverges at fine time
    // bases). UNCLAMPED so the offset may carry off-screen (the handle pins to the edge,
    // the time mark shows the real value) - Java ScopeTabControl.stepTimePerDivAround ->
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
   *  V/div ≈ ceil₁₂₅(Vpp/(DIVISIONS_Y·0.75)) PER CHANNEL (each side off its own residual
   *  Vpp when its residual is on, else its captured Vpp - _autoSetupVpp), each channel
   *  centred on its own DC mean, trigger to mid. Reads the last measurement (this.latest)
   *  for t/div - call once a frame is available. */
  autoSetup() {
    const p = this.prefs, m = this.latest;
    if (!p || !m) return;
    const ceil125 = (t) => {
      if (!(t > 0)) return t;
      const dec = Math.pow(10, Math.floor(Math.log10(t))), mm = t / dec;
      return (mm <= 1 ? 1 : mm <= 2 ? 2 : mm <= 5 ? 5 : 10) * dec;
    };
    // Horizontal scale: fit ~1.5 periods across the width. In dual-tone mode the
    // carrier crosses 0 many times per beat envelope cycle and m.frequency is NaN
    // (cleared by withoutTimes), so pick the LOWER of the carrier and the |F1−F2|
    // BEAT so the time base covers at least one full beat envelope - the carrier
    // alone would render a packed wall of cycles with no visible envelope (Java
    // ScopeController.performAutoSetup dual branch: scaleHz = min(carrier, beat)).
    const info = this._lastInfo;
    const dual = !!(info && info.dualTone && info.f1Hz > 0 && info.f2Hz > 0 && info.f1Hz !== info.f2Hz);
    let scaleHz = m.frequency;
    if (dual) {
      const beatHz = Math.abs(info.f2Hz - info.f1Hz);
      if (beatHz > 0 && (!Number.isFinite(scaleHz) || beatHz < scaleHz)) scaleHz = beatHz;
    }
    if (Number.isFinite(scaleHz) && scaleHz > 0) {
      p.oscTimePerDiv.set(ceil125((1 / scaleHz) * 1.5 / DIVISIONS_X));
    }
    // Per-channel V/div: scale each side off its own residual Vpp when its residual is on,
    // else off the captured Vpp (Java performAutoSetup vppL/vppR - INDEPENDENT). When both
    // residuals are off _autoSetupVpp returns each channel's captured Vpp.
    const vppL = this._autoSetupVpp(true);
    const vppR = this._autoSetupVpp(false);
    if (Number.isFinite(vppL) && vppL > 0) p.oscLeftVoltsPerDiv.set(ceil125(vppL / (DIVISIONS_Y * 0.75)));
    if (Number.isFinite(vppR) && vppR > 0) p.oscRightVoltsPerDiv.set(ceil125(vppR / (DIVISIONS_Y * 0.75)));
    // Recenter each DC-coupled channel on its OWN long-averaged DC mean so a small AC
    // signal on a large DC pedestal lands mid-screen instead of off an edge (Java
    // autoSetupOffsetFrac): DC-coupled -> 0.5 + meanV/(DIVISIONS_Y·vDiv); AC-coupled -> 0.5.
    // Each channel centres on its own DC mean scaled by ITS own ADC full-scale (Java
    // autoSetupOffsetFrac getAdcPeakVolts(left ? L : R)).
    const offFor = (left, ac, vd) => {
      if (ac || !(vd > 0)) return 0.5;
      const meanV = this._acDcMean(left) * p.getAdcPeakVolts(left ? 'L' : 'R');
      return Number.isFinite(meanV) ? 0.5 + meanV / (DIVISIONS_Y * vd) : 0.5;
    };
    p.oscLeftOffsetFrac.set(offFor(true, p.oscLeftAcMode.get(), p.oscLeftVoltsPerDiv.get()));
    p.oscRightOffsetFrac.set(offFor(false, p.oscRightAcMode.get(), p.oscRightVoltsPerDiv.get()));
    p.oscTriggerPositionFrac.set(0.5); p.oscTriggerLevelFrac.set(0.5);
  }

  // -------------------------------------------------------------------------
  // display persistence ("digital phosphor") - the web mirror of the Java
  // SwtGlCanvasSurface.renderFrame -> ScopePhosphor.render split (:98-126, :144-222)
  // -------------------------------------------------------------------------

  /** Resolved persistence from preferences: 0 = off, < 0 = infinite, > 0 = decay
   *  seconds (Java ScopePhosphor.persistenceSeconds :410-413). 0 when there are no
   *  prefs (the no-prefs autoscale-fallback render never persists). */
  _persistSeconds() {
    const p = this.prefs;
    if (!p) return 0;
    return persistenceSeconds(p.oscPersistenceMode.get(), p.oscPersistenceManualSeconds.get());
  }

  /** Snapshots the trigger + geometry settings the {@link PhosphorGate} watches
   *  (Java ScopePhosphor.render :158-167). Trigger-source changes wipe the afterglow
   *  (CLEAR), geometry changes reset it (RESET). */
  _gateInputs() {
    const p = this.prefs;
    return {
      triggerMode: p.oscTriggerMode.get(),
      triggerType: p.oscTriggerType.get(),
      triggerEdge: p.oscTriggerEdge.get(),
      triggerChannel: p.oscTriggerChannel.get(),
      timePerDiv: p.oscTimePerDiv.get(),
      leftVdiv: p.oscLeftVoltsPerDiv.get(),
      rightVdiv: p.oscRightVoltsPerDiv.get(),
      leftOff: p.oscLeftOffsetFrac.get(),
      rightOff: p.oscRightOffsetFrac.get(),
      triggerPos: p.oscTriggerPositionFrac.get(),
      extClearRequested: false,   // the external CLEAR flag lives in the engine (clearPersistence)
    };
  }

  /** Lazily creates the phosphor engine + kind gate on the first persistence-active
   *  frame, so an OFF scope (the default) and the headless node tests never construct
   *  a WebGL context. Returns the engine (its own attach() reports unsupported). */
  _ensurePhosphor() {
    if (!this._phosphor) {
      this._phosphor = new ScopePhosphor();
      this._phosphorGate = new PhosphorGate();
    }
    return this._phosphor;
  }

  /** Ensures a transparent W×H scratch 2D canvas exists on `key`, (re)sizing + clearing
   *  it. Used for the TRACE and OVERLAY phases the phosphor compositor draws separately. */
  _scratchCanvas(key, W, H) {
    let c = this[key];
    if (!c) {
      c = (typeof document !== 'undefined' && document.createElement)
        ? document.createElement('canvas') : null;
      this[key] = c;
    }
    if (!c) return null;
    if (c.width !== W) c.width = W;
    if (c.height !== H) c.height = H;
    const g = c.getContext('2d');
    g.setTransform(1, 0, 0, 1, 0, 0);
    g.clearRect(0, 0, W, H);
    return { canvas: c, g };
  }

  /**
   * Runs one frame body under display persistence when it is active + supported,
   * else runs it straight onto the main canvas (byte-identical to the pre-phosphor
   * path). Faithful to Java SwtGlCanvasSurface.renderFrame (:98-126): resolve the
   * persistence seconds, gate the Kind (PhosphorGate - CLEAR on a trigger-source /
   * external change, RESET on a geometry change), route the TRACE + OVERLAY draws to
   * scratch canvases via the redirect hooks, then composite BACKDROP + phosphor +
   * OVERLAY (Java compositeToScreen :313-324). When persistence is off, unsupported,
   * or the engine's render() returns false, the body has already drawn the whole
   * frame directly onto this.g - nothing more to do (Java's renderWhole fallback).
   *
   * @param {string} kind the caller's requested ScopePhosphor.Kind
   * @param {number} W canvas width in CSS px
   * @param {number} H canvas height in CSS px
   * @param {() => void} paint draws one frame body through the redirect hooks
   */
  _withPhosphor(kind, W, H, paint) {
    const persistSeconds = this._persistSeconds();
    // OFF (or no prefs): the fast path, unchanged. No engine, no redirects, no scratch -
    // the body draws the entire frame onto this.g exactly as it always has.
    if (persistSeconds === 0 || W <= 0 || H <= 0) { paint(); return; }

    const engine = this._ensurePhosphor();
    // attach() decides support up-front (WebGL2 + float colour + complete FBOs). Only when
    // it succeeds do we redirect; a failure keeps the byte-identical direct path so an
    // unsupported browser regresses to exactly today's behaviour.
    if (!engine.attach(W, H)) { paint(); return; }

    const trace = this._scratchCanvas('_traceScratch', W, H);
    const overlay = this._scratchCanvas('_overlayScratch', W, H);
    if (!trace || !overlay) { paint(); return; }   // no 2D scratch (headless) -> direct path

    // Redirect the TRACE + OVERLAY draws to the scratch canvases; the backdrop stays on
    // this.g. The body's early returns (held / blank branches) still leave the trace on
    // the scratch and the backdrop on screen - exactly what a COMPOSITE / CLEAR needs.
    this._traceG = trace.g;
    this._overlayG = overlay.g;
    try {
      paint();
    } finally {
      this._traceG = null;
      this._overlayG = null;
    }

    // Decide the effective Kind from the caller's kind + the watched settings (Java
    // render :168-187). The gate reads the SAME prefs Java watches; the external CLEAR
    // flag is held inside the engine (set by clearPersistence()), so extClearRequested
    // stays false here.
    const decided = this._phosphorGate.decide(kind, this._gateInputs(), persistSeconds);
    const rendered = engine.render(
      decided.kind, trace.canvas, decided.persistSeconds,
      this._lastFrameWasNew, (typeof performance !== 'undefined' ? performance.now() : Date.now()));

    const g = this.g;
    if (rendered) {
      // Composite over the fresh backdrop already on this.g: phosphor trace, then the
      // fresh overlay (Java compositeToScreen: BACKDROP + phosphor + OVERLAY).
      g.setTransform(1, 0, 0, 1, 0, 0);
      g.drawImage(engine.canvas, 0, 0, W, H);
      g.drawImage(overlay.canvas, 0, 0, W, H);
    } else {
      // Post-attach engine failure (should not happen): the backdrop is on this.g but the
      // trace + overlay went to scratch - flatten them straight through so nothing is lost.
      g.setTransform(1, 0, 0, 1, 0, 0);
      g.drawImage(trace.canvas, 0, 0, W, H);
      g.drawImage(overlay.canvas, 0, 0, W, H);
    }
  }

  /** Requests a persistence CLEAR on the next rendered frame WITHOUT re-stamping the
   *  current trace - for signal-affecting changes (a USER generator change) where the
   *  on-screen trace is still anchored on the pre-change event. Java
   *  ScopeController.clearPersistence -> GlScopeSurface.clearPersistence (:238-243,
   *  SwtGlCanvasSurface :92-96). No-op until the engine exists (persistence never used). */
  clearPersistence() {
    if (this._phosphor) this._phosphor.clearPersistence();
  }

  /** Whether the most recent render body drew a genuinely new captured frame (a fresh
   *  trigger / AUTO free-run) rather than a held / re-composited one - Java
   *  ScopeView.isLastFrameNew (:1116). The phosphor wrapper consults it to gate REALTIME
   *  accumulation. */
  isLastFrameNew() { return this._lastFrameWasNew; }

  /** Releases the phosphor engine's GL resources (Java ScopePhosphor.release via
   *  SwtGlCanvasSurface.dispose). The web scope pane has no teardown today (it lives for
   *  the app's lifetime), so nothing calls this yet - provided for parity + a future
   *  teardown. */
  dispose() {
    if (this._phosphor) { this._phosphor.release(); this._phosphor = null; }
    this._phosphorGate = null;
  }

  /** @param buf  the R channel (ch1, the measured/primary channel) as a
   *  Float32Array. @param info {scopeFps, period, inRate, snapped, peakVolts,
   *  dualTone, f1Hz, f2Hz, bufL, bufR}. info.bufL / info.bufR carry BOTH
   *  channels (Float32Array) per the scope DATA CONTRACT; when absent (file
   *  load) `buf` is drawn for both channels. */
  render(buf, info) {
    this._lastBuf = buf; this._lastInfo = info;   // remembered so the ResizeObserver can repaint a loaded/frozen view at the new size
    // CSS box drives the size for an attached canvas; a DETACHED canvas (the
    // offscreen screenshot clone) reports clientWidth/Height = 0, so fall back to
    // its pre-set backing store (sized by the caller to match the LIVE scope
    // canvas exactly, C20d) before the last-ditch default.
    const W = this.cv.clientWidth || this.cv.width || 1200;
    const H = this.cv.clientHeight || this.cv.height || 240;
    // Work in LIVE (CSS / virtual) pixels only - the backing store equals the CSS box,
    // so the display magnification (devicePixelRatio) is NEVER applied. Drawing, the
    // mouse hit-boxes (getBoundingClientRect) and the screenshot capture then all share
    // one consistent pixel space.
    if (this.cv.width !== W) this.cv.width = W;
    if (this.cv.height !== H) this.cv.height = H;
    // REALTIME persistence frame (Java SwtGlCanvasSurface.render -> ScopePhosphor.Kind.REALTIME):
    // the wrapper routes the TRACE phase through the decayed phosphor buffer when persistence
    // is active and supported, and otherwise runs the body straight onto the main canvas -
    // byte-identical to the pre-phosphor path. _renderBody draws the backdrop on this.g and,
    // via the _traceG / _overlayG redirects, the trace + overlay onto the scratch canvases.
    this._withPhosphor(ScopePhosphor.Kind.REALTIME, W, H, () => this._renderBody(buf, info, W, H));
  }

  /** The scope's per-frame body (formerly the tail of {@link render}): backdrop +
   *  trigger-mode frame selection + trace + overlays, drawn through the current
   *  redirect hooks. Sizing and the persistence wrapper live in {@link render}. */
  _renderBody(buf, info, W, H) {
    const g = this.g;
    // A new paint defaults to "held content" (Java ScopeView.lastFrameWasNew reset,
    // :2798); the genuinely-new-frame branch below flips it true.
    this._lastFrameWasNew = false;
    g.setTransform(1, 0, 0, 1, 0, 0);
    g.fillStyle = '#000'; g.fillRect(0, 0, W, H);
    this._drawGraticule(g, W, H);

    // ACTUAL filled sample count (Java `available` = readEndingAt return), not the
    // grow-only buffer length - the captured window is ~3× the displayed span but the
    // ring may not have filled it all yet; drawing the unfilled tail would smear
    // stale / zero samples into the trace. File load carries no info.available, so the
    // whole decoded buffer is the signal (buf.length).
    const available = (info.available != null) ? info.available : buf.length;
    const sampleRate = info.inRate;
    const peakVolts = info.peakVolts > 0 ? info.peakVolts : 1.0;
    const p = this.prefs;

    // ----- per-channel descriptors (Java drawWaveforms: showL/showR, dcL/dcR,
    // acL/acR, sincL/sincR) - built only for enabled channels. Each channel
    // reads its own buffer (info.bufL/bufR; `buf` fallback), V/div, offset, AC,
    // sinc, colour, and the HF+mains filtered span over which its DC mean is
    // measured. Blank-return when neither channel is enabled (Java line 1940).
    const showL = !p || p.oscLeftChannelEnabled.get();
    const showR = !p || p.oscRightChannelEnabled.get();
    if (p && !showL && !showR) {
      this.latest = null; this.measurementRows = null; this._clearOverlayLabels();
      if (this._rectZoom) this._rectZoom.drawOverlay(this._overlayG || g, W, H);   // Java paints the zoom layer on every paint
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
      // AC-coupling DC block: the long-averaged WHOLE-PERIOD Vmean from the
      // measurement ring (Java acDcMean -> averagedChannelMean) - stable across
      // partial cycles. The per-frame window mean is only the pre-first-publish
      // fallback (Java ScopeView falls back to sampleMean the same way).
      const acdc = ac ? this._acDcMean(lc === 'Left') : 0;
      const d = {
        name: lc,
        ch: lc === 'Left' ? 'L' : 'R',
        raw, procBuf,
        vDiv: (p ? p['osc' + lc + 'VoltsPerDiv'].get() : 0.1) || 0.1,
        offsetFrac: p ? p['osc' + lc + 'OffsetFrac'].get() : 0.5,
        ac, dcMean, dcOff: ac ? (Number.isFinite(acdc) ? acdc : dcMean) : 0,
        sinc: p ? p['osc' + lc + 'SincInterpEnabled'].get() : true,
        // This channel's OWN ADC peak-volts (Java getAdcPeakVolts(Channel) - L/R full-scale);
        // the no-prefs autoscale fallback keeps info.peakVolts. Drives every per-channel volts
        // scaling: trace/beat vScale, ±FS lines, trigger threshold, measurement/residual.
        peak: p ? p.getAdcPeakVolts(lc === 'Left' ? 'L' : 'R') : peakVolts,
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

    // Trigger mode (AUTO / NORMAL / SINGLE) - drives whether a no-trigger frame
    // free-runs (AUTO), holds the last frame (NORMAL) or stays frozen until a
    // single armed shot fires (SINGLE).
    const mode = p ? p.oscTriggerMode.get() : TriggerMode.AUTO;
    if (this._lastTriggerMode !== mode) {
      // A mode switch disarms SINGLE. No transition should ever blank the screen:
      // seed the held frame from the last live frame (this._frame, kept current
      // every live paint by _snapshotFrame) so SINGLE and NORMAL immediately show
      // the last trace + all overlays until a real trigger fires - Java keeps
      // showing the last trace, it never blanks on the switch. AUTO
      // free-runs, so it needs no seed and clears the held state.
      this._singleArmed = false;
      this._singleHeld = false;
      this._normalFrame = null;
      if (mode === TriggerMode.SINGLE) {
        this._singleHeld = !!this._frame;        // seeded hold iff a live frame exists
      } else if (mode === TriggerMode.NORMAL) {
        this._normalFrame = this._frame;         // seeded hold iff a live frame exists
      } else {
        this._frame = null;                      // AUTO free-runs - no held frame
      }
      this._lastTriggerMode = mode;
    }

    // Dual-tone -> trigger/draw the reconstructed slow beat modulator (off the
    // trigger channel's filtered signal); otherwise trigger on it directly.
    const dual = info.dualTone && info.f1Hz > 0 && info.f2Hz > 0 && info.f1Hz !== info.f2Hz;
    const trigBuf = dual
      ? reconstructBeatSignal(trigDesc.procBuf, available, sampleRate, info.f1Hz, info.f2Hz, this._beatScratch)
      : trigDesc.procBuf;

    // ----- live-window measurement: measure the long fixed measurement
    // window (info.measBufL/R, Java ScopeMeasurementWorker's MEAS_MAX_SAMPLES read
    // from the ring) CONTINUOUSLY here, BEFORE any trigger-mode hold/blank branch
    // returns - so the measurement table keeps updating in NORMAL-hold and
    // SINGLE-armed even while the TRACE is frozen, exactly like Java's worker
    // measures the ring independent of the trigger/display. Throttled to
    // READOUT_THROTTLE_MS so the full-window compute cost stays bounded. The
    // displayed-span fallback (file mode / no live window) stays in the trace
    // path below, where `trig`/`show` are known.
    const liveMeas = this._measureLiveWindow(info, descByName, descriptors, sampleRate, dual);

    const tDiv = p ? p.oscTimePerDiv.get() : info.period / sampleRate;
    const posFrac = p ? clamp01(p.oscTriggerPositionFrac.get()) : 0.5;
    // Java: displaySamples = round(windowSeconds·sampleRate), bail if < 2. No
    // max(8,...)/min(...,available−2·PADDING) clamp - windowSamples is the literal
    // display window; the dispCount clamp against `available` happens per branch.
    const windowSamples = Math.round(tDiv * DIVISIONS_X * sampleRate);
    if (windowSamples < 2) {
      this.latest = null; this.measurementRows = null;
      if (this._rectZoom) this._rectZoom.drawOverlay(this._overlayG || g, W, H);
      return;
    }

    // Trigger level - Java: levelFrac=oscTriggerLevelFrac, the screen-Y of the
    // dashed level line on the TRIGGER channel; the normalised sample at that Y
    // is (offsetFrac − levelFrac)·vDiv·DIVISIONS_Y / trigger-channel peak. AC-coupled trigger
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
    // Trigger threshold/hysteresis use the TRIGGER channel's OWN full-scale (Java
    // drawWaveforms triggerPeakVolts = getAdcPeakVolts(triggerCh)).
    const tPeak = trigDesc.peak;
    const baseLevel = (tOffsetFrac - levelFracRaw) * tVDiv * DIVISIONS_Y / tPeak;
    // Dual-tone triggers on the reconstructed |F1−F2| beat envelope (trigBuf) but at the
    // USER's trigger level in the same normalised units as the raw trace (Java drawWaveforms:
    // effectiveData = beat, effectiveTriggerLevel = the user level unchanged). The beat is a
    // signed modulator rawPeak·cos(...) spanning [−rawPeak, +rawPeak]; searching it at the
    // user level means an out-of-range level (above the envelope peak / below its trough)
    // finds NO crossing, so NORMAL holds the last frame instead of re-arming.
    // Forcing level = 0 (old behaviour) always crossed the zero-centred beat and re-triggered
    // regardless of where the user parked the level.
    const level = baseLevel + (tAc ? tDcOff : 0);
    const hysteresis = (p && p.oscTriggerHysteresisEnabled.get())
      ? p.oscTriggerHysteresisDiv.get() * tVDiv / tPeak : 0.0;
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
    // GLITCH type: discontinuity trigger - fires where the signal breaks the
    // sinusoid-recurrence prediction (level steps AND slope splices, anywhere on the
    // wave, regardless of direction). ↑ anchors the display on the glitch's start,
    // ↓ on its end. Level / hysteresis / sinc refine don't apply. The measured
    // frequency pins the recurrence exactly; it applies only when the measurement
    // channel IS the trigger channel - otherwise the detector self-estimates from the
    // window. (Java drawWaveforms GLITCH branch -> ScopeTrigger.findGlitch.)
    const glitchMode = !!p && p.oscTriggerType.get() === TriggerType.GLITCH;
    let foundFrac = -1;
    if (!this.fileMode && trigTo > trigFrom) {
      if (glitchMode) {
        // The TRIGGER channel's own measured frequency pins the recurrence exactly - read
        // it unconditionally (the worker measures both channels; Java drawWaveforms GLITCH
        // branch now reads getLastMeasResult(triggerCh == L) regardless of the measurement
        // channel selection). Falls back to the detector's self-estimate when absent.
        const m = this._getLastMeas(trigCh === 'L');
        const measHz = m ? m.frequency : NaN;
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

    // ── Unified positioning (Java drawWaveforms ~3062-3160): pick the absolute
    // anchor + screen offsetFrac for THIS mode, then map with ONE nav.viewport call -
    // the SAME transform for live, frozen(scrolled-back) and file. The window left edge
    // is  viewLeftAbs = anchorAbs − displaySamples·offsetFrac  (ScopeNav.viewLeftAbs);
    // dispStart/subSampleOffset are viewLeft − bufStartAbs floored/fractioned. The web's
    // read buffer starts at absolute sample `absStart` (= Java bufStartAbs = writePos −
    // available), so `absStart` is the bufStartAbs handed to nav.viewport, and the
    // local start `startSample` (= dispStart + subSampleOffset, what _drawTrace splits)
    // is `vp.viewLeftAbs − absStart`. dispCount stays the full windowSamples - the
    // renderer blanks any column the buffer can't fill (Java: off-buffer columns not
    // drawn, the anchor never moves to compensate).
    //
    // offsetFrac is the (virtual-capable) trigger-position fraction posFracReal - NOT
    // the clamped posFrac used for the trigger search - so a Shift+wheel pan can carry
    // the offset off-screen and scroll the trace to the true buffer edge (Java sets
    // offsetFrac = triggerPosFracReal on every live/frozen/trigger path, clamps only the
    // search leftHalf/rightHalf and the DRAWN handle position).
    const posFracReal = p ? p.oscTriggerPositionFrac.get() : 0.5;
    const bufStartAbs = info.absStart || 0;
    const latestAbs = bufStartAbs + available;   // Java writePos for the live tip
    // Java AUTO free-run right-anchor (~3092-3099): rightPad past the newest so the sinc
    // kernel keeps context; anchorAbs = latestAbs − rightPad, offsetFrac = 1.0.
    const autoFreeRunAnchor = () => {
      const rightPad = Math.min(LANCZOS_PADDING, Math.max(0, available - windowSamples));
      return latestAbs - rightPad;   // offsetFrac = 1.0 places this at the right edge
    };

    // ----- trigger-mode frame selection (Java drawWaveforms steps 1-3) -----
    // Sets (anchorAbs, offsetFrac) - or returns early for a hold/blank branch - then the
    // single nav.viewport call below maps them to (startSample, dispCount).
    let trig, capture = false, anchorAbs, offsetFrac, dispCount = windowSamples;
    if (!p) {
      // No-prefs file-load autoscale fallback: free-run from trigFrom, startSample = trig.
      trig = foundTrigger ? foundFrac : trigFrom;
      anchorAbs = bufStartAbs + trig; offsetFrac = 0.0;   // viewLeft = trig (local)
    } else if (this.fileMode) {
      // File / scrolled-back bypass (Java drawWaveforms ~2937-2953): the window is placed
      // ABSOLUTELY - its right edge sits at scrollViewEndAbs (= writePos − viewBackOffset),
      // exactly where the edge time marks point, so every scroll step moves the trace 1:1
      // (sub-sample included). anchor = scrollViewEndAbs, offsetFrac = 1.0 (anchor pins the
      // RIGHT edge). No trigger, no capture. The back-offset is FRACTIONAL (Java
      // ScopeNav.fileViewWindow keeps mainOffset a double so a ½-div wheel step carries a
      // sub-sample scroll into _drawTrace's dispStart/subSampleOffset split). dispCount stays
      // the full windowSamples - the renderer blanks columns the buffer can't fill.
      const back = Math.max(0, info.viewBackOffsetFrames || 0);
      const scrollViewEndAbs = latestAbs - back;   // writePos − viewBackOffsetFrames
      anchorAbs = scrollViewEndAbs; offsetFrac = 1.0;
      // startSample (local viewLeft) for the geometry stash + measurement span below.
      const startSampleFile = (scrollViewEndAbs - windowSamples) - bufStartAbs;
      trig = startSampleFile + posFrac * windowSamples;   // for the measurement span below
      // Stash the window geometry so the wheel handlers (file-mode horizontal pan +
      // t/div zoom-around-cursor) and the rect zoom can re-anchor the back-offset against
      // the loaded buffer without re-deriving it. `back` is the FRACTIONAL accumulator
      // so repeated ½-div steps sum exactly; dispCount is the full window (blanked past the
      // buffer, matching Java), so the rect zoom's pixel↔frame mapping matches the drawn trace.
      this._fileGeom = { available, windowSamples, startSample: startSampleFile, back, dispCount };
    } else if (mode === TriggerMode.SINGLE) {
      if (this._singleArmed && foundTrigger) {
        trig = foundFrac; capture = true;
        this._singleArmed = false;
        if (this.onSingleDisarmed) this.onSingleDisarmed();   // pop the Start toggle
        anchorAbs = bufStartAbs + trig; offsetFrac = posFracReal;   // virtual-capable
      } else if (this._singleHeld) {
        // Armed-waiting (or disarmed-held): keep the LAST captured frame + ALL its
        // overlays (Java drawWaveforms: armed+no-trigger -> renderHeldCapturedFrame).
        // _drawHeldFrame preserves this.latest (so autoSetup works while armed) and
        // leaves this.measurementRows intact; only a fresh armed trigger (above)
        // replaces the frame.
        this._drawHeldFrame(g, W, H); return;
      } else if (this._frame) {
        // Armed, no fresh capture yet, but a last live frame exists (e.g. seeded
        // on the Auto->SINGLE switch): draw it with all overlays rather than
        // blanking the canvas - Java holds the last trace until the shot fires.
        this._drawHeldFrame(g, W, H); return;
      } else {
        // Armed but no frame ever rendered: nothing to draw, but do NOT blank the
        // measured state - preserve this.latest / this.measurementRows so autoSetup
        // and the DOM table keep working while we wait for the first trigger.
        // The cap/s readout stays visible on the blank pane (Java paintCanvas draws
        // it every paint) - in glitch mode it IS the glitches/s counter.
        // Java draws the sliders AFTER drawWaveforms UNCONDITIONALLY (paintCanvas:
        // drawWaveforms -> drawSliders), so a blank-trace paint still registers the
        // three handle hit-boxes - the offset / trigger-level / trigger-position
        // handles stay draggable while a rare (e.g. glitch) trigger hasn't fired.
        if (p) this._drawSliders(g, W, H, levelFrac, posFrac, descByName);
        this._updateCaptureRate(false);
        this._drawCaptureRate();
        if (this._rectZoom) this._rectZoom.drawOverlay(this._overlayG || g, W, H);
        return;
      }
    } else if (mode === TriggerMode.NORMAL) {
      if (foundTrigger) { trig = foundFrac; capture = true; anchorAbs = bufStartAbs + trig; offsetFrac = posFracReal; }
      else if (this._normalFrame) { this._drawHeldFrame(g, W, H); return; }   // hold last
      // Never triggered AND no seeded frame -> blank the TRACE only. Do NOT wipe
      // the measured state: _measureLiveWindow already refreshed this.latest /
      // measurementRows this paint, and the table must keep updating. The
      // cap/s readout stays visible on the blank pane (whole-record visibility).
      // Java draws the sliders after drawWaveforms unconditionally, so the offset /
      // trigger-level / trigger-position handles stay draggable in NORMAL (incl.
      // glitch NORMAL) even before / between triggers.
      else {
        if (p) this._drawSliders(g, W, H, levelFrac, posFrac, descByName);
        this._updateCaptureRate(false); this._drawCaptureRate();
        if (this._rectZoom) this._rectZoom.drawOverlay(this._overlayG || g, W, H);
        return;
      }
    } else {
      // AUTO: anchor on the trigger when found (virtual-capable offset like NORMAL);
      // otherwise free-run right-anchored on the newest sample - Java step 3 sets
      // anchorAbs = latestAbs − rightPad, offsetFrac = 1.0 (NOT trigFrom, NOT posFrac):
      // this view isn't pannable, the newest sample fills to the right edge.
      if (foundTrigger) { trig = foundFrac; anchorAbs = bufStartAbs + trig; offsetFrac = posFracReal; }
      else {
        anchorAbs = autoFreeRunAnchor(); offsetFrac = 1.0;
        trig = (anchorAbs - windowSamples) - bufStartAbs + posFrac * windowSamples;   // measurement span
      }
    }

    // ── ONE viewport mapping for every mode (Java: nav.viewport(anchorAbs, offsetFrac,
    // displaySamples, bufStartAbs)). startSample is the LOCAL fractional viewLeft the rest
    // of render()/_drawTrace consumes (= vp.dispStart + vp.subSampleOffset = viewLeftAbs −
    // bufStartAbs). dispCount is left at the full windowSamples (blanked past the buffer).
    const vp = this.nav().viewport(anchorAbs, offsetFrac, windowSamples, bufStartAbs);
    let startSample = vp.viewLeftAbs - bufStartAbs;

    const period = info.period;
    const show = Math.max(8, Math.min(available - Math.ceil(trig) - LANCZOS_PADDING, Math.round(period * 4)));   // measurement span
    // Java drawTrace: width = canvas width; samplesPerPx = dispCount/width,
    // pxPerSample = width/dispCount. cols IS the canvas width (one polyline
    // point per pixel column), never capped to the sample count.
    const cols = W;
    const samplesPerPx = dispCount / cols;
    const pxPerSample = cols / dispCount;

    // ----- Residual mode (Java renderTraces frozenFrame == null branch): subtract the
    // best-fit tone(s) from the DISPLAYED slice, per channel, on LIVE frames only (a held
    // frame replays its already-substituted snapshot, so no re-fit). Both channels share
    // the SAME slice geometry [sliceFrom, sliceFrom+sliceLen) (it depends only on
    // dispStart/dispCount/pad/available, equal for L and R), so a residual channel and a
    // re-based raw copy of the other channel fit a single (available, startSample) tuple -
    // which the snapshot (_snapshotFrame) can only carry once. Substituting the scratch
    // for BOTH the snapshot and drawTrace bakes the residual into the snapshot; the frozen-
    // replay path then paints it verbatim (residual NOT re-run there), subtracted once.
    // `drawBufs[ch]` overrides each descriptor's draw buffer; when a residual is active the
    // whole draw window re-bases onto (drawAvailable, drawStartSample).
    const drawBufs = {};
    let drawAvailable = available;
    let drawStartSample = startSample;
    let residualSliceFrom = 0;   // procBuf index of drawn-window sample 0 (0 unless residual re-based it)
    if (p && !this.fileMode) {
      const leftResidual = p.oscLeftResidualEnabled.get();
      const rightResidual = p.oscRightResidualEnabled.get();
      if (!leftResidual) this._lastResidualVpp.L = NaN;
      if (!rightResidual) this._lastResidualVpp.R = NaN;
      const dispStartI = Math.floor(startSample);
      const subSampleOffset = startSample - dispStartI;
      const lDesc = descByName.L, rDesc = descByName.R;
      let resL = null, dispL = 0, lenL = 0, resR = null, dispR = 0, lenR = 0;
      if (showL && leftResidual && lDesc) {
        resL = this._computeResidual(lDesc.procBuf, available, dispStartI, dispCount,
          LANCZOS_PADDING, sampleRate, true, lDesc.peak, info);
        dispL = this._residualDispStart; lenL = this._residualSliceLen;
      }
      if (showR && rightResidual && rDesc) {
        resR = this._computeResidual(rDesc.procBuf, available, dispStartI, dispCount,
          LANCZOS_PADDING, sampleRate, false, rDesc.peak, info);
        dispR = this._residualDispStart; lenR = this._residualSliceLen;
      }
      // At least one residual succeeded -> both channels render off the shared slice: the
      // residual channel from its scratch, the other (shown, raw) channel from a plain copy
      // of the same slice so the single (drawAvailable, drawStartSample) tuple is valid for
      // both traces AND the snapshot. (dispL==dispR and lenL==lenR whenever both present.)
      if (resL || resR) {
        const shared = resL ? dispL : dispR;   // dispStart − sliceFrom (equal for both)
        const sliceLen = resL ? lenL : lenR;
        const sliceFrom = dispStartI - shared;
        residualSliceFrom = sliceFrom;
        drawAvailable = sliceLen;
        drawStartSample = shared + subSampleOffset;
        if (showL && lDesc) {
          drawBufs.L = resL ? resL
            : this._copyResidualSlice(lDesc.procBuf, sliceFrom, sliceLen, true);
        }
        if (showR && rDesc) {
          drawBufs.R = resR ? resR
            : this._copyResidualSlice(rDesc.procBuf, sliceFrom, sliceLen, false);
        }
      }
    }

    // Each channel: its own colour / V/div / offset / AC / sinc. EVERY channel
    // draws its own filtered CAPTURED signal - in dual-tone the reconstructed
    // beat is only the TRIGGER source (trigBuf) and the dimmed overlay below,
    // never the trace (Java renderTraces draws leftBuf/rightBuf; drawBeatOverlays
    // draws the envelope on top). Residual mode substitutes the residual buffer
    // (and shared slice geometry) for BOTH channels. (No-prefs autoscale fallback.)
    let autoMm = 0;
    if (!p) {
      autoMm = 1e-9;
      for (let i = 0; i < show; i++) { const idx = Math.floor(trig) + i; if (idx >= 0 && idx < available) autoMm = Math.max(autoMm, Math.abs(buf[idx])); }
    }
    for (const d of descriptors) {
      const drawBuf = (drawBufs[d.ch] !== undefined) ? drawBufs[d.ch] : d.procBuf;
      const sampleToY = p
        ? (s) => H * (d.offsetFrac - ((s - d.dcOff) * d.peak) / (d.vDiv * DIVISIONS_Y))
        : (s) => H / 2 - s / autoMm * (H * 0.45);
      this._drawTrace(g, W, H, drawBuf, drawAvailable, drawStartSample, dispCount,
                      cols, samplesPerPx, pxPerSample, sampleToY, d.sinc, d.hex);
    }

    // ----- Reconstructed-beat overlays (Java drawBeatOverlays): one per VISIBLE channel
    // whose residual pref is OFF, reconstructed from THAT channel's own samples, in its own
    // darkened trace colour at its own V/div + offset. Gated on dual-tone AND the user's
    // "Reconstructed beat" checkbox AND the generator actually running (silent -> no |F1−F2|
    // beat, the reconstruction would trace noise). A channel showing its residual gets none.
    const beatBufs = {};   // per-channel beat aligned to the DRAWN window, for _snapshotFrame (held replay)
    if (dual && p && p.oscShowReconstructedBeat.get() && info.generatorRunning
        && info.f1Hz > 0 && info.f2Hz > 0 && Math.abs(info.f2Hz - info.f1Hz) > 0) {
      for (const d of descriptors) {
        const resOff = d.ch === 'L' ? !p.oscLeftResidualEnabled.get() : !p.oscRightResidualEnabled.get();
        if (!resOff) continue;
        const beat = reconstructBeatSignal(d.procBuf, available, sampleRate, info.f1Hz, info.f2Hz, this._beatScratch);
        const beatHex = attenuateHex(d.colorInt, 0.45);
        // Java: dcOffset = 0.0 (beat is already zero-centred), sincEnabled = false, dotDiameter = 0.
        const sampleToY = (s) => H * (d.offsetFrac - (s * d.peak) / (d.vDiv * DIVISIONS_Y));
        this._drawTrace(g, W, H, beat, available, startSample, dispCount,
                        cols, samplesPerPx, pxPerSample, sampleToY, false, beatHex, 0);
        // Snapshot this channel's beat NOW, into a per-channel grow-only buffer - the
        // shared _beatScratch is reused for the NEXT channel (and every later paint),
        // so a straight reference would alias. Re-base into the DRAWN window
        // (residualSliceFrom, drawAvailable) so it lines up with the snapshotted trace,
        // which _copyResidualSlice re-based the same way when the OTHER channel is a
        // residual. (Java re-reconstructs from raw captured samples in the held path;
        // the web bakes the drawn window into the snapshot, so the beat is baked too.)
        let dst = this._snapBeatBufs[d.ch];
        if (!dst || dst.length < drawAvailable) { dst = new Float32Array(drawAvailable); this._snapBeatBufs[d.ch] = dst; }
        for (let i = 0; i < drawAvailable; i++) {
          const src = residualSliceFrom + i;
          dst[i] = (src >= 0 && src < available) ? beat[src] : 0;
        }
        beatBufs[d.ch] = { ch: d.ch, snap: dst, offsetFrac: d.offsetFrac, vDiv: d.vDiv, hex: beatHex };
      }
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
    // above (decoupled from the trigger so the table keeps updating while the
    // trace is frozen); reuse that result for the snapshot. Only the displayed-span
    // fallback (file mode / no live window) is computed here, where `trig`/`show`
    // are known - and only when the live window wasn't available.
    let meas = liveMeas;
    if (!meas) {
      let measCh = p ? p.oscMeasurementChannel.get() : trigCh;
      let measDesc = descByName[measCh];
      if (p && !measDesc) {   // selected channel disabled -> flip + persist + clear history
        measDesc = descriptors[0];
        p.oscMeasurementChannel.set(measDesc.ch);
        if (p.save) p.save();   // Java prepareMeasurementRows persists the flip
        this._clearMeasurementHistory();
      }
      if (!measDesc) measDesc = trigDesc;
      const mStart = Math.max(0, Math.floor(trig));
      const mLen = Math.min(available - mStart, show);
      const span = measDesc.procBuf.subarray(mStart, mStart + mLen);
      meas = compute(span, mLen, sampleRate, measDesc.peak);
      if (dual) {
        // Dual-tone has two simultaneous fundamentals - a single period/freq/duty
        // has no physical meaning, so the f row reads '---'. Drop the time-domain
        // fields only (Java ScopeMeasurementWorker.measure dual branch: withoutTimes();
        // it never re-adds a single refined frequency - that was a legacy beat-view
        // divergence that latched an intermittent ~19 kHz onto the f row).
        meas = withoutTimes(meas);
      }
      this._accumulateMeasurements(meas, dual);
    }

    // Top-right overlay carries ONLY the cap/s readout (Java drawCaptureRate:
    // "%.1f cap/s" at w − textWidth − 8, y≈6). No measured-value text on the
    // canvas - those live in the DOM measurement table (Java drawMeasurements).
    // Reaching here means this paint produced a genuinely-new frame (every
    // held-frame branch returned earlier), so advance the rate as "new".
    // This is the web ScopeView.lastFrameWasNew = true (Java :3089/:3103): the
    // phosphor wrapper accumulates the trace into the afterglow only on such frames.
    this._lastFrameWasNew = true;
    this._updateCaptureRate(true);
    this._drawCaptureRate();
    this._drawStaticFilePath(g, W);

    // Snapshot everything _drawHeldFrame needs to repaint the exact frame from
    // BOTH channels, independent of the rolling ring. Captured EVERY live paint
    // (into grow-only reused buffers - bounded per-frame cost, no per-paint
    // allocation churn) so a held frame is always available: a SINGLE/NORMAL
    // trigger freezes it (capture=true), AND an Auto->SINGLE switch can seed the
    // held frame from the last live Auto frame instead of blanking.
    // The captured trigger's position in the DRAWN-slice coordinates (residual mode
    // re-bases the slice by startSample − drawStartSample). A free-running AUTO frame
    // has no real trigger, so it stores a SYNTHETIC anchor - the sample under the
    // (virtual-capable) trigger-position fraction - so EVERY held frame positions via
    // the same trigger-offset branch (never NaN), pan + zoom exactly like a real trigger
    // (Java captureSingleFrame: entryStart + entrySub + triggerPosFracReal·displaySamples).
    const capturedTriggerLocal = foundTrigger
      ? (trig - (startSample - drawStartSample))
      : (drawStartSample + posFracReal * dispCount);
    this._snapshotFrame(descriptors, trigDesc, p, drawAvailable,
                        drawStartSample, dispCount, cols, samplesPerPx, pxPerSample, meas,
                        drawBufs, capturedTriggerLocal, beatBufs);
    if (capture) {
      if (mode === TriggerMode.SINGLE) this._singleHeld = true; else this._normalFrame = this._frame;
    }

    // Rect-zoom rubber band + focused-view accent border - LAST (Java paintCanvas
    // ends with drawRectZoomOverlay).
    if (this._rectZoom) this._rectZoom.drawOverlay(this._overlayG || g, W, H);

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

  /** Draws one channel's trace (Java drawTrace): at <= 1 sample/px a band-limited Lanczos /
   *  linear polyline with full-intensity edge overhang, and filled per-sample dots when sample
   *  spacing exceeds 10 px. `blankBeyondData` (Java field, true only on the held-frame magnify
   *  path) blanks any polyline/envelope column whose data runs past the buffer instead of
   *  clamping it.
   *  `dpoEligible` (default true) routes the DENSE (> 1 sample/px) regime through the
   *  digital-phosphor renderer - unconditionally, mirroring Java ScopeView.drawTrace
   *  (`if (samplesPerPx > 1.0) drawDigitalPhosphor`). The condensed/zoomed strip passes false,
   *  so its dense columns fall to the CONNECTED per-column min/max envelope instead (Java
   *  ZoomedView keeps per-column bars - it has no phosphor). */
  _drawTrace(g, W, H, buf, n, startSample, windowSamples, cols, samplesPerPx, pxPerSample, sampleToY, sinc, hex, dotDiameterOverride, blankBeyondData = false, dpoEligible = true) {
    // TRACE phase: when persistence is painting, every waveform (main + held + beat)
    // is drawn into the transparent scratch canvas the phosphor buffer accumulates,
    // NOT the main canvas (Java renderTraceToScratch). _traceG is null otherwise, so
    // the trace draws straight onto the passed context exactly as before.
    if (this._traceG) g = this._traceG;
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
    // <= MAX_LANCZOS_DOWNSAMPLE (linear); above that a CONNECTED per-column min/max envelope
    // takes over. This gate governs the NON-DPO paths only - the dense (> 1 spp) MAIN trace
    // goes to the digital phosphor below (dpoEligible); the envelope now serves the
    // condensed/zoomed strip (dpoEligible = false, Java ZoomedView's per-column bars). The
    // sinc envelope path reconstructs around each column's extremes; a single point/column
    // would low-pass narrow pulses and the noise band away.
    const envelope = sinc ? (samplesPerPx > 1.0) : (samplesPerPx > MAX_LANCZOS_DOWNSAMPLE);
    if (dpoEligible && samplesPerPx > 1.0) {
      // Digital phosphor - THE dense renderer for the MAIN scope trace. Java drawTrace routes
      // EVERY > 1 sample/px window through drawDigitalPhosphor and returns (ScopeView.java:3937),
      // so the web does the same for the dpoEligible (main / held / beat) traces, replacing both
      // the min/max envelope AND the non-sinc mid-zoom polyline in the dense (> 1 spp) regime.
      // The condensed/zoomed strip passes dpoEligible = false and keeps its min/max bars below.
      //
      // The DPO's value->row transform is the SAME linear map as sampleToY, recovered from the
      // closure: sampleToY(s) = centerY − s·vScale with centerY = sampleToY(0) and vScale =
      // sampleToY(0) − sampleToY(1) (dcOffset folded in -> 0). The trace context is at
      // CSS/logical resolution with an identity transform, so pixelScale = 1 and the coverage
      // image blits 1:1. One renderer/painter reused across channels (Java: one per ScopeView).
      const y0 = sampleToY(0), y1 = sampleToY(1);
      const lineWidth = this.prefs ? this.prefs.oscLineWidth.get() : 2.0;
      this._dpoPainter.setTarget(g, 1);
      this._phosphorRenderer.render(this._dpoPainter, buf, n, dispStart, subSampleOffset,
                                    dispCount, width, H, y0, y0 - y1, 0, lineWidth, hex);
    } else if (!envelope) {
      // Java lineAttrsTrace: oscLineWidth, CAP_ROUND, JOIN_ROUND.
      g.lineWidth = this.prefs ? this.prefs.oscLineWidth.get() : 2.0;
      g.lineCap = 'round'; g.lineJoin = 'round';
      g.beginPath();
      // One point per pixel column; sinc reconstructs BETWEEN samples, linear interpolates.
      // A column whose sample position lies OUTSIDE the buffer (pos < 0 || pos > n-1) has no
      // data - LIFT the pen there so the trace draws NOTHING past the captured data end
      // (Java drawTrace: the point provider returns Double.NaN for pos<0||pos>n-1, breaking
      // the SWT Path). Unconditional across every mode - the display window may extend past
      // `available` on a live/frozen/held/file frame, and those columns stay blank. Re-entry
      // starts a fresh subpath with moveTo.
      const scale = sinc ? Math.max(1.0, samplesPerPx) : 0;
      let pen = false;   // true once the current subpath has a point
      for (let i = 0; i <= width + 1; i++) {
        const sx = this._sincTraceX(i, width);
        const pos = dispStart + subSampleOffset + sx * samplesPerPx;
        if (pos < 0 || pos > n - 1) { pen = false; continue; }   // no sample there -> blank
        const v = sinc ? lanczos(buf, n, pos, scale) : lerpAt(buf, n, pos);
        const y = sampleToY(v);
        pen ? g.lineTo(sx, y) : g.moveTo(sx, y);
        pen = true;
      }
      g.stroke();
      // High-zoom per-sample dots (Java pxPerSample > 10). Sample dots only with
      // persistence OFF (Java ScopeView.drawWaveforms dotDiameter gate): the afterglow
      // accumulates the trace history, so dots would pile into opaque blobs on top of it.
      const dotDiameter = (dotDiameterOverride !== undefined) ? dotDiameterOverride
        : (this.prefs
          ? (this.prefs.oscPersistenceMode.get() === PersistenceMode.OFF
            ? this.prefs.oscDotDiameter.get() : 0)
          : 5);
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
          // inscribed in that box -> centre (sx-half+dotDiameter/2, ...), radius dotDiameter/2.
          const r = dotDiameter / 2;
          g.beginPath(); g.arc(sx - half + r, sy - half + r, r, 0, 2 * Math.PI); g.fill();
        }
      }
    } else {
      this._drawEnvelope(g, buf, n, dispStart, subSampleOffset, dispCount,
                         width, samplesPerPx, sampleToY, sinc, blankBeyondData);
    }
  }

  /** Draws a CONNECTED per-column min/max envelope (more than one sample per pixel) -
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
    // Where each column's connector attaches to its bar - peak/trough-aware, so an
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
      // Connector from the previous column (drawn iff both ends are set - the
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
   *  and the curve - recovering a crest/trough that drifted between samples. */
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
      } else if (cMin > colMax[x - 1]) {     // this column above the left one (rising in) -> enter at bottom
        entryAttach[x] = cMin;
      } else {                                // this column below the left one (falling in) -> enter at top
        entryAttach[x] = cMax;
      }

      if (!rValid || !this._disjoint(colMin, colMax, x, x + 1)) {
        exitAttach[x] = NaN;
      } else if (peak) {
        exitAttach[x] = cMax;
      } else if (trough) {
        exitAttach[x] = cMin;
      } else if (colMin[x + 1] > cMax) {      // right column above (rising out) -> leave at top
        exitAttach[x] = cMax;
      } else {                                // right column below (falling out) -> leave at bottom
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
    // OVERLAY phase: composited fresh on top of the persisted trace, so while a
    // persisted frame is painting the sliders draw into the overlay scratch canvas,
    // not the main context (Java Phase.OVERLAY). _overlayG is null otherwise.
    if (this._overlayG) g = this._overlayG;
    const p = this.prefs;
    const o = this._overlay;

    // Handle footprint along an edge (Java SLIDER_TRI_LONG + 4) - used to stop each
    // slider line short of the OPPOSITE edge's marker/handle so the dashed track
    // never overlaps the other slider's triangle (Java drawSliders insets via
    // levelLineStartX / offsetLineRightEnd). Kept as a FIXED px inset in the DOM so
    // the clip tracks the on-screen handle size, not the stretched canvas.
    const HANDLE_INSET = SLIDER_TRI_LONG + 4;
    const LABEL_GAP = 4;   // Java's +4 between a value label and where the dashed line resumes.
    const measCh = p ? p.oscMeasurementChannel.get() : 'L';
    const activeDesc = descByName[measCh] || descByName.L || descByName.R;
    const trigCh = p ? p.oscTriggerChannel.get() : 'L';
    const trigD = descByName[trigCh] || descByName.L || descByName.R;

    // ----- PASS 1: set the VALUE labels first so their measured widths can clip the dashed
    // lines short of them (Java measures textExtent, then stops each line before BOTH its own
    // label and the opposite side's - levelLineStartX / offsetLineRightEnd). All label writes
    // happen before the width reads so there is a single forced reflow per paint, not thrash.
    let trigW = 0; const offW = { L: 0, R: 0 };
    if (o) {
      if (!this.fileMode && trigD) {
        // Trigger-level voltage: UNCLAMPED level + trigger channel's UNCLAMPED offset, so a
        // virtual (off-screen) level still states its real threshold. Yellow, at the right handle.
        const levelFracRaw = p ? p.oscTriggerLevelFrac.get() : levelFrac;
        const levelVolts = (trigD.offsetFrac - levelFracRaw) * DIVISIONS_Y * trigD.vDiv;
        this._setOverlayLabel(o.trigLevelVal, this._fmtVolts(levelVolts, trigD.vDiv), '#ffff00', '',
          { right: (SLIDER_TRI_LONG + 6) + 'px', top: (levelFrac * 100) + '%', transform: 'translateY(-50%)' });
      } else { o.trigLevelVal.style.display = 'none'; }
      o.offsetVal.L.style.display = 'none'; o.offsetVal.R.style.display = 'none';
      if (activeDesc) {
        for (const ch of ['L', 'R']) {
          const d = descByName[ch];
          if (!d) continue;
          // Offset voltage = (0.5 − rawOffsetFrac)·Ydiv·vDiv. FULL channel colour even when
          // inactive (Java drawOffsetTrack dims only the triangle). At the left handle.
          const offsetVolts = (0.5 - d.offsetFrac) * DIVISIONS_Y * d.vDiv;
          this._setOverlayLabel(o.offsetVal[ch], this._fmtVolts(offsetVolts, d.vDiv), d.hex, '',
            { left: (SLIDER_TRI_LONG + 6) + 'px', top: (clamp01(d.offsetFrac) * 100) + '%', transform: 'translateY(-50%)' });
        }
      }
      // Batched width reads (one reflow, after all the writes above).
      if (o.trigLevelVal.style.display !== 'none') trigW = o.trigLevelVal.offsetWidth;
      if (o.offsetVal.L.style.display !== 'none') offW.L = o.offsetVal.L.offsetWidth;
      if (o.offsetVal.R.style.display !== 'none') offW.R = o.offsetVal.R.offsetWidth;
    }
    const maxOffW = Math.max(offW.L, offW.R);
    // A dashed line stops this far from the RIGHT edge to clear the trigger value label.
    const trigRightClip = HANDLE_INSET + trigW + LABEL_GAP;

    // ----- PASS 2: position the dashed lines + handles. Trigger level: LEFT end clears the
    // offset labels (maxOffW), RIGHT end clears its OWN value label (trigW). Always yellow.
    // Hidden in file mode. Hit-box in CANVAS px so dragging is unchanged.
    if (!this.fileMode) {
      const levelY = Math.round(levelFrac * H);
      if (o) this._positionSlider(o.trigLevel, 'h', levelFrac, '#ffff00',
        HANDLE_INSET + maxOffW + LABEL_GAP, trigRightClip);
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
      // Time-offset mark ABOVE the bottom-edge handle (Java drawSliders posStr): the REAL,
      // virtual-capable offset time (posReal, NOT the clamped posFrac the line/handle pin to),
      // so it keeps counting when a pan/zoom carries the trigger off-screen. Centred on the
      // clamped handle x, lifted above the triangle.
      if (o) {
        const posReal = p ? p.oscTriggerPositionFrac.get() : posFrac;
        const windowTime = (p ? p.oscTimePerDiv.get() : 0) * DIVISIONS_X;
        const posSeconds = (posReal - 0.5) * windowTime;
        const fmtS = (s) => Math.abs(s) >= 1 ? s.toFixed(3) + ' s'
          : Math.abs(s) >= 1e-3 ? (s * 1e3).toFixed(3) + ' ms' : (s * 1e6).toFixed(3) + ' µs';
        this._setOverlayLabel(o.trigPosVal, fmtS(posSeconds), '#ffffff',
          'Trigger position offset (relative to the window centre)',
          { left: (posFrac * 100) + '%', bottom: (SLIDER_TRI_LONG + 6) + 'px',
            transform: 'translateX(-50%)' });
      }
    } else {
      if (o) { this._hideSlider(o.trigPos); o.trigPosVal.style.display = 'none'; }
      this._triggerPosBounds = { x: -1, y: -1, w: 0, h: 0 };
    }

    // ----- Full-scale boundary lines (Java ScopeView.drawFullScaleLines, 2105-2123 /
    // 2162-2175): two dashed horizontals per enabled channel at ±FS volts around its
    // offset. peakVolts = adcFsVoltageRms·√2 (live prefs); vScale = peakVolts/vDiv·
    // (H/DIVISIONS_Y); yTop/yBot = centerY ∓ vScale. A y outside [0,H) is hidden -
    // Java's natural clip. Drawn BEFORE the offset track below (via DOM paint order,
    // see _buildOverlayLayer) so the offset zero-line + triangle win on overlap. NOT
    // gated on fileMode (Java draws these in file mode too). The colour-dependent {2,6}
    // dash rides an inline repeating-linear-gradient (2px on, 6px gap); the mid colour
    // matches the offset track (attenuateHex(colorInt, 0.5) = Java *_CHANNEL_MID).
    // The anchor deliberately uses the RAW, virtual-capable offsetFrac (NOT clamp01) so
    // the FS line can reach the canvas middle at the scroll clamp - the nav spec's
    // "±FS/2 reaches the vertical middle" limit, which the offset's wheel/scrollbar bound
    // (0.5 + max(0.5, peakVolts/(DIVISIONS_Y·vDiv))) exceeds 1.0 for at fine V/div. This
    // diverges from Java ScopeView.java:2116, which still clamps (a known twin bug on the
    // Java side). The offset track/slider below stays clamp01 (its
    // on-screen clamping to [0,1] is correct per spec).
    if (o) {
      const pxPerDivY = H / DIVISIONS_Y;
      for (const ch of ['L', 'R']) {
        const fs = o.fsLine[ch];
        const d = descByName[ch];
        if (!d) { fs.top.style.display = 'none'; fs.bot.style.display = 'none'; continue; }
        const mid = attenuateHex(d.colorInt, 0.5);
        const centerY = d.offsetFrac * H;
        // Each channel's ±FS line at its OWN full-scale (Java drawFullScaleLines
        // getAdcPeakVolts(Channel.L)/(Channel.R)).
        const vScale = d.peak / d.vDiv * pxPerDivY;
        const place = (el, y) => {
          if (y < 0 || y >= H) { el.style.display = 'none'; return; }
          el.style.display = '';
          el.style.top = (y / H * 100) + '%';
          el.style.background = `repeating-linear-gradient(90deg, ${mid} 0 2px, transparent 2px 8px)`;
        };
        place(fs.top, centerY - vScale);
        place(fs.bot, centerY + vScale);
      }
    }

    // ----- Channel offset lines: LEFT end clears the offset's OWN value label (offW[ch]),
    // RIGHT end clears the trigger-level label (trigRightClip) - Java drawOffsetTrack lineStartX
    // / offsetLineRightEnd. The ACTIVE (measurement) channel's line is its full trace colour and
    // its handle is draggable (registers the hit-box); every OTHER channel's line is a darker
    // (~0.5 attenuated) variant. The value labels were already set in PASS 1.
    const placeOffset = (ch, d, hex, isActive) => {
      const offsetY = Math.round(clamp01(d.offsetFrac) * H);
      if (o) {
        const s = o.offset[ch];
        this._positionSlider(s, 'h', clamp01(d.offsetFrac), hex, HANDLE_INSET + offW[ch] + LABEL_GAP, trigRightClip);
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
   *  order: offset -> trigger-level -> trigger-position) and move it immediately so a
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
      // selection drag (Java ScopeView.pointerDown -> rectZoomPointerDown).
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
      // Mid-selection the hover hit-tests stay quiet - no cursor flips or
      // tooltips while the rubber band crosses sliders (Java pointerMove).
      if (this._rectZoom.isDragActive()) return;
    }
    let cursor = '';
    let tip = '';
    if (this._inBounds(this._offsetBounds, x, y)) {
      cursor = 'ns-resize';
      const ch = this.prefs.oscMeasurementChannel.get();
      tip = (ch === 'R' ? 'Right' : 'Left') + ' channel vertical offset - drag, double-click to centre';
    } else if (!this.fileMode && this._inBounds(this._triggerLevelBounds, x, y)) {
      cursor = 'ns-resize';
      tip = 'Trigger level - drag, double-click to centre';
    } else if (!this.fileMode && this._inBounds(this._triggerPosBounds, x, y)) {
      cursor = 'ew-resize';
      tip = 'Trigger position / time offset - drag, double-click to centre';
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

  /** dblclick on a handle resets it to centre (Java mouseDoubleClick -> 0.5). */
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
   *  updateSliderFromMouse): OFFSET <- my/H on the measurement channel and TRIGGER_LEVEL
   *  <- my/H are clamped to [0,1]; TRIGGER_POSITION is absolute mx/W, clamped to [0,1]
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
      // Absolute: the line follows the cursor (centred under it), clamped to [0,1] -
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

  /** The scope plots edge-to-edge (no axis margins) - the zoomable area is the
   *  full canvas (Java ScopeView inherits the full-client-area default). */
  _zoomableArea() {
    const W = this.cv.clientWidth || this.cv.width || 1200;
    const H = this.cv.clientHeight || this.cv.height || 240;
    return { x: 0, y: 0, w: W, h: H };
  }

  /** Slider handles stay grabbable - a selection drag can't start on them (Java
   *  ScopeView.isRectZoomBlockedAt; the header buttons + edge labels of the Java
   *  list are DOM elements here - the toolbar sits outside the canvas and the
   *  overlay labels are pointer-events:none - so only the three canvas hit-boxes
   *  need blocking). */
  _isRectZoomBlockedAt(x, y) {
    return this._inBounds(this._offsetBounds, x, y)
      || this._inBounds(this._triggerLevelBounds, x, y)
      || this._inBounds(this._triggerPosBounds, x, y);
  }

  /** Overlay-only repaint (rubber band / focus border): Canvas2D just redraws the
   *  current frame (Java requestZoomOverlayRepaint default; the GL phosphor
   *  re-composite override is GPU-only and intentionally skipped on the web).
   *  Routed through the frozen-first repaint: a rubber band dragged over a
   *  STOPPED scope repaints on every pointer move, and the live ring behind
   *  _lastBuf is not this view's content any more. */
  _repaintZoomOverlay() {
    this._repaintAtCurrentSize();
  }

  /** X = the displayed time window - trigger-relative SECONDS on the live/frozen
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
      const pos = p.oscTriggerPositionFrac.get();   // raw - virtual-capable
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
   *  VOLTAGE (the pref is a screen fraction - remapped through the old->new
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
      // trigger voltage parks the level outside [0,1] - honest, undoable.
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
          p.oscTimePerDiv.set(tDivNew);   // BEFORE onFileBack - the pane's clamp reads the new t/div
          const wsNew = Math.round(tDivNew * DIVISIONS_X * sr);
          // back = 0 pins the right edge at `available` (no rightPad), matching render +
          // _fileNavState + fileMaxBack (Java file window right edge = writePos − back).
          const rightEdgeAnchorNew = Math.max(0, geom.available - wsNew);
          const startNew = (s.xMin + s.xMax) / 2 - wsNew / 2;
          this.onFileBack(Math.round(rightEdgeAnchorNew - startNew));
        }
      } else {
        // Trigger-anchored branch: when the t/div floor engages, p pins the selection's
        // LEFT edge (Java). A held frame rides the SAME trigger-offset model (every held
        // frame carries a real/synthetic anchor now), so setting p re-positions it too -
        // no separate frozen-frame anchor seed (Java ScopeView rect-zoom, ba42b91).
        const tDivNew = Math.max(T_PER_DIV_MIN, span / DIVISIONS_X);
        p.oscTimePerDiv.set(tDivNew);
        p.oscTriggerPositionFrac.set(-s.xMin / (tDivNew * DIVISIONS_X));   // virtual-capable
      }
    }
    if (p.save) p.save();
    // Mirror onto the tab fields + tiles and repaint a frozen/file view (the Java
    // V/div reverse bindings + refreshTab + redraw -> web onSettingsChanged ->
    // host.refreshFields/refreshTiles/requestRedraw).
    if (this.onSettingsChanged) this.onSettingsChanged();
    else this._repaintZoomOverlay();
    return true;
  }

  /** Voltage window -> V/div + offsetFrac for one channel: vDiv = span/10,
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

  /** The scope plots edge-to-edge, so the selection maps linearly onto the
   *  current window on both axes - per channel vertically, since the rect is
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
    // Live/frozen: use the REAL (virtual-capable, UNCLAMPED) trigger offset so the
    // marks keep counting when a pan/zoom carried the trigger off-screen - Java
    // drawEdgeLabels reads prefs.getOscTriggerPositionFrac() (posReal), NOT the
    // clamped posFrac the handle/line are pinned to (drawSliders). File mode has no
    // trigger, so it keeps the passed (clamped) posFrac.
    const p = this.prefs;
    const posReal = (!this.fileMode && p) ? p.oscTriggerPositionFrac.get() : posFrac;
    const leftStr = fmtS(-posReal * windowTime);
    const rightStr = fmtS((1 - posReal) * windowTime);
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

  /** Resolves the measurement channel (auto-flip + persist when the selected one is
   *  disabled) and keeps the current dual-tone form for publishMeasurement, then EITHER
   *  reuses the live osc-meas worker's most-recent publish (this.latest, already published
   *  via publishMeasurement) when it is fresh, OR - when a measurement window is injected
   *  directly with no live worker stream (headless render / node tests) - runs the same
   *  DOM-free compute pipeline SYNCHRONOUSLY over info.measBufL/R and publishes it through
   *  the same contract. Returns the selected-channel measurement (so the live-render path
   *  can snapshot it), or null when neither a fresh live publish nor an injected window is
   *  available (file mode) so the caller falls back to the displayed-span measurement.
   *  @returns {object|null} */
  _measureLiveWindow(info, descByName, descriptors, sampleRate, dual) {
    const p = this.prefs;
    this._measDual = dual;   // publishMeasurement (worker-driven) has no info; snapshot the form
    let measCh = p ? p.oscMeasurementChannel.get() : (descriptors[0] && descriptors[0].ch);
    let measDesc = descByName[measCh];
    if (p && !measDesc && descriptors.length) {   // selected channel disabled -> flip + persist
      // The measurement-channel auto-flip no longer clears history (Java dropped
      // clearHistory on the switch - the worker measures BOTH channels every tick, so
      // the newly-selected channel already has a live history / pool to read).
      measDesc = descriptors[0];
      p.oscMeasurementChannel.set(measDesc.ch);
      if (p.save) p.save();   // Java prepareMeasurementRows persists the flip
    }
    if (!measDesc) measDesc = descriptors[0];
    if (!measDesc) return null;
    measCh = measDesc.ch;

    // Live path: the osc-meas worker publishes both channels off its own gapless ring
    // reader on its ~100 ms cadence (publishMeasurement below). While those publishes are
    // fresh, reuse this.latest - the render thread must NOT recompute (it would overwrite
    // the worker's long-window result with a short displayed-span one).
    const nowMs = performance.now();
    if ((nowMs - this._lastMeasPublishMs) < LIVE_MEAS_FRESH_MS && this.latest) return this.latest;

    // Synchronous fallback: a directly-injected measurement window with no live worker
    // stream. Run the SAME per-channel pipeline the worker runs (OscMeasCompute.measureWindow)
    // over info.measBufL/R and publish it through the same contract, throttled so the
    // full-window compute cost stays bounded (the window isn't recomputed every paint).
    if (!(info.measBufL || info.measBufR) || !(info.measAvailable >= 64)) return null;   // no window -> file-mode fallback
    if (this.latest && (nowMs - this._lastLiveMeasMs) < READOUT_THROTTLE_MS) return this.latest;
    this._lastLiveMeasMs = nowMs;
    const nowNs = nowMs * NS_PER_MS;
    const mLen = info.measAvailable;
    const absStart = info.measAbsStart || 0;
    for (const d of descriptors) {
      const raw = (d.ch === 'R') ? info.measBufR : info.measBufL;
      if (!raw) continue;
      const opts = {
        // No lpfMode: a MEASUREMENT is never HF-filtered (the display path below still is).
        mainsMode: p ? p['osc' + d.name + 'MainsSuppression'].get() : 'NONE',
        dual, f1Hz: info.f1Hz, f2Hz: info.f2Hz,
      };
      const m = this._measEngine.measureWindow(d.ch, raw, mLen, sampleRate, d.peak, absStart, opts);
      if (!m) continue;
      this._pushMeanHist(d.ch, nowNs, m.vmean / d.peak);
      this._lastMeas[d.ch] = m;
    }
    const meas = this._lastMeas[measCh];
    if (!meas) return null;
    this._accumulateMeasurements(meas, dual);
    this.latest = meas;
    return meas;
  }

  /** Consumes one publish from the osc-meas Web Worker (the live measurement stream):
   *  {resultL, resultR, leftMeanNorm, rightMeanNorm}. Reproduces the exact publication
   *  contract the inline path had - stores each channel's snapshot (_lastMeas), pushes each
   *  channel's whole-period normalized Vmean into the mean-history ring (_pushMeanHist), and
   *  for the SELECTED channel accumulates the rolling table stats (_accumulateMeasurements)
   *  + sets this.latest. Stamps the publish wall-clock so _measureLiveWindow reuses this
   *  result instead of recomputing on the render thread. */
  publishMeasurement(r) {
    if (!r) return;
    const p = this.prefs;
    const nowMs = performance.now();
    const nowNs = nowMs * NS_PER_MS;
    if (r.resultL) { this._lastMeas.L = r.resultL; this._pushMeanHist('L', nowNs, r.leftMeanNorm); }
    if (r.resultR) { this._lastMeas.R = r.resultR; this._pushMeanHist('R', nowNs, r.rightMeanNorm); }
    const measCh = p ? p.oscMeasurementChannel.get() : 'L';
    const meas = this._lastMeas[measCh] || this._lastMeas.L || this._lastMeas.R;
    if (!meas) return;
    this._accumulateMeasurements(meas, this._measDual);
    this.latest = meas;
    this._lastMeasPublishMs = nowMs;
  }

  /** The latest per-channel measurement snapshot (Java
   *  ScopeMeasurementWorker.getLastMeasResult(boolean left)). */
  _getLastMeas(left) {
    return this._lastMeas[left ? 'L' : 'R'];
  }

  /** Publishes one measurement tick's WHOLE-PERIOD Vmean (normalized) into the
   *  channel's mean-history ring (Java ScopeMeasurementWorker: meanHistory*Norm[write]
   *  + measHistoryTime[write], write/size ring bookkeeping, lastMeanNormalized). */
  _pushMeanHist(ch, tNs, meanNorm) {
    const h = this._meanHist[ch];
    h.t[h.write] = tNs;
    h.v[h.write] = meanNorm;
    h.write = (h.write + 1) % MEAS_HISTORY_CAP;
    if (h.size < MEAS_HISTORY_CAP) h.size++;
    this._lastMeanNorm[ch] = meanNorm;
  }

  /** Long-averaged normalised DC mean of one channel for the AC-coupling DC block,
   *  the residual baseline and auto-setup centring - faithful port of Java
   *  ScopeView.acDcMean -> ScopeMeasurementWorker.averagedChannelMean: the mean of
   *  the per-tick WHOLE-PERIOD Vmeans (the ring) whose timestamps fall inside
   *  max(AC_DC_MIN_AVG_SEC, measurement-average pref); when the history doesn't
   *  span the window yet, the latest tick's mean ("rather than 0 so AC removal
   *  isn't suddenly off-zero"). NaN before the first publish - the caller falls
   *  back to a per-frame window mean (Java ScopeView via sampleMean). */
  _acDcMean(left) {
    const ch = left ? 'L' : 'R';
    const p = this.prefs;
    const avgSec = Math.max(AC_DC_MIN_AVG_SEC, p ? p.oscMeasurementAverageSeconds.get() : 0);
    const cutoff = performance.now() * NS_PER_MS - avgSec * 1e9;
    const h = this._meanHist[ch];
    let sum = 0, count = 0;
    for (let i = 0; i < h.size; i++) {
      const idx = (h.write - 1 - i + MEAS_HISTORY_CAP) % MEAS_HISTORY_CAP;
      if (h.t[idx] < cutoff) break;
      sum += h.v[idx];
      count++;
    }
    if (count > 0) return sum / count;
    return this._lastMeanNorm[ch];
  }

  /** Vpp (volts) auto-setup should scale the given channel's vertical to (Java
   *  ScopeView.autoSetupVpp): the RESIDUAL Vpp recorded at the last paint when that
   *  channel's residual pref is on and finite, else the channel's captured Vpp. Lets a
   *  channel showing its residual scale to fill the screen off the (small) residual
   *  amplitude instead of the (large) tone. */
  _autoSetupVpp(left) {
    const p = this.prefs;
    const ch = left ? 'L' : 'R';
    const on = p ? (left ? p.oscLeftResidualEnabled.get() : p.oscRightResidualEnabled.get()) : false;
    const vpp = this._lastResidualVpp[ch];
    if (on && Number.isFinite(vpp)) return vpp;
    const m = this._lastMeas[ch];
    return m ? m.vpp : NaN;
  }

  /**
   * Residual view (faithful port of Java ScopeView.computeResidual): subtracts the
   * best-fit single tone (dual-tone: two tones) from the displayed slice of one channel,
   * writing the residual into that channel's scratch. Returns the scratch (whose length
   * may exceed the slice) together with the slice geometry via this._residualDispStart
   * (index of dispStart inside the scratch) / this._residualSliceLen (valid length), or
   * null when the residual can't be computed (no valid frequency, fit window too short,
   * degenerate fit) - the caller then paints the captured trace. Also records
   * this._lastResidualVpp[ch] from the min/max over the VISIBLE window for auto-setup.
   * @returns {Float32Array|null}
   */
  _computeResidual(data, dataLen, dispStart, dispCount, pad, sampleRate, leftChannel, peakVolts, info) {
    const p = this.prefs;
    const ch = leftChannel ? 'L' : 'R';
    // 1. Tone frequencies. Single tone: seed from THIS channel's measured fundamental
    //    (the worker measures both channels). Dual tone: the single-value measured f is
    //    deliberately cleared (two fundamentals), so prefer the worker's per-channel
    //    measured PAIR (dualF1/dualF2) - read off the raw capture, so immune to the
    //    DAC/ADC clock offset (the commanded, FFT-bin-snapped generator values are exact
    //    only in the DAC domain; with independent clocks and no FLL they are ppm-off in
    //    the ADC capture, and over a long fit window that phase drift leaks the
    //    fundamentals into the residual). Fall back to the generator-snapped values
    //    (info.f1Hz/f2Hz) when the worker hasn't published a valid pair yet (cold start /
    //    worker lag) so the residual doesn't go dark waiting. Bail (paint captured) when
    //    nothing usable.
    const dual = !!(info && info.dualTone);
    let f1 = NaN, f2 = NaN, seed;
    if (dual) {
      const m = this._getLastMeas(leftChannel);
      if (m && Number.isFinite(m.dualF1) && Number.isFinite(m.dualF2)
        && m.dualF1 > 0 && m.dualF2 > 0
        && Math.abs(m.dualF2 - m.dualF1) > 0) {
        f1 = m.dualF1;
        f2 = m.dualF2;
      } else {
        f1 = info.f1Hz; f2 = info.f2Hz;
      }
      if (!(f1 > 0) || !(f2 > 0) || !(Math.abs(f2 - f1) > 0)) {
        this._recordResidualVpp(ch, NaN);
        return null;
      }
      seed = Math.min(f1, f2);
    } else {
      const seedMeas = this._getLastMeas(leftChannel);
      seed = seedMeas ? seedMeas.frequency : NaN;
    }
    if (!(seed > 0) || !Number.isFinite(seed) || !(sampleRate > 0)) {
      this._recordResidualVpp(ch, NaN);
      return null;
    }

    // 2. Fit window: start from the padded display slice, grow to cover at least
    //    RESIDUAL_MIN_CYCLES cycles / RESIDUAL_MIN_FIT_SAMPLES - LEFT first (older
    //    lookback samples exist), then right - clamped to the buffer, capped at
    //    RESIDUAL_FIT_MAX_SAMPLES.
    const sliceFrom = Math.max(0, dispStart - pad);
    const sliceTo = Math.min(dataLen, dispStart + dispCount + pad);
    if (sliceTo - sliceFrom < 2) { this._recordResidualVpp(ch, NaN); return null; }
    let needCycles = Math.ceil(RESIDUAL_MIN_CYCLES * sampleRate / seed);
    if (dual) {
      // The two tones are only separable when the window spans several beat cycles -
      // below that the fits leak into each other.
      const needBeat = Math.ceil(RESIDUAL_MIN_BEAT_CYCLES * sampleRate / Math.abs(f2 - f1));
      needCycles = Math.max(needCycles, needBeat);
    }
    const wantFit = Math.min(RESIDUAL_FIT_MAX_SAMPLES,
      Math.max(RESIDUAL_MIN_FIT_SAMPLES, needCycles));
    let fitFrom = sliceFrom;
    let fitTo = sliceTo;
    const deficit = wantFit - (fitTo - fitFrom);
    if (deficit > 0) {
      const growLeft = Math.min(deficit, fitFrom);
      fitFrom -= growLeft;
      const growRight = Math.min(deficit - growLeft, dataLen - fitTo);
      fitTo += growRight;
    }
    let fitLen = fitTo - fitFrom;
    if (fitLen > RESIDUAL_FIT_MAX_SAMPLES) {
      // The display slice is longer than the fit cap. Anchoring the capped fit
      // window at the slice's LEFT edge makes the fitted model extrapolate one-
      // directionally across the whole (up to 10 s) slice, so the residual is clean
      // at the left and its amplitude grows monotonically RIGHTWARD by 2·A·π·δf·t -
      // where δf is the unavoidable sub-Hz frequency error (finite refine precision
      // in the dual path, which has no phase-slope polish; single-tone leftovers in
      // the polish). CENTRE the fit window on the display slice instead so that error
      // is split symmetrically about the middle: the residual is now smallest at the
      // centre and grows equally toward BOTH edges, halving the peak and removing the
      // "cleaned only at the start" asymmetry the user sees at >100 ms/div.
      fitFrom = sliceFrom + Math.trunc((sliceTo - sliceFrom - RESIDUAL_FIT_MAX_SAMPLES) / 2);
      if (fitFrom < 0) fitFrom = 0;
      if (fitFrom > dataLen - RESIDUAL_FIT_MAX_SAMPLES) fitFrom = dataLen - RESIDUAL_FIT_MAX_SAMPLES;
      fitLen = RESIDUAL_FIT_MAX_SAMPLES;
    }
    if (fitLen < RESIDUAL_MIN_FIT_SAMPLES) { this._recordResidualVpp(ch, NaN); return null; }

    // 3. Cheap phase-slope frequency polish (single tone only - in dual mode the
    //    generator frequencies are exact and a second tone breaks the single-sinusoid
    //    phase model): fit each half of the window and read off the extra phase advance
    //    the seed frequency missed. A step larger than the sanity cap means the estimate
    //    is unreliable -> keep f.
    let f = seed;
    for (let iter = 0; !dual && iter < RESIDUAL_POLISH_ITERS; iter++) {
      const half = Math.trunc(fitLen / 2);
      if (half < 2) break;
      const fitA = SineFit.of(data, fitFrom, half, sampleRate, f);
      const fitB = SineFit.of(data, fitFrom + half, half, sampleRate, f);
      const omega = 2.0 * Math.PI * f / sampleRate;
      const deltaPhi = this._wrapToPi(fitB.phaseRadians() - fitA.phaseRadians()
        - this._wrapToPi(omega * half));
      const deltaF = deltaPhi * sampleRate / (2.0 * Math.PI * half);
      const cap = Math.min(RESIDUAL_POLISH_MAX_HZ, sampleRate / (4.0 * half));
      if (Math.abs(deltaF) > cap) break;
      f += deltaF;
      if (Math.abs(deltaF) < RESIDUAL_POLISH_MIN_STEP_HZ) break;
    }

    // 4. Final exact fit at the polished frequency (single tone; the dual branch below
    //    fits its two tones itself).
    let fit = null;
    if (!dual) {
      fit = SineFit.of(data, fitFrom, fitLen, sampleRate, f);
      if (!Number.isFinite(fit.a) || !Number.isFinite(fit.b)) {
        this._recordResidualVpp(ch, NaN);
        return null;
      }
    }

    // 5. Subtract the WHOLE fitted model - tone AND the fit's own DC c - over the display
    //    slice into the scratch, indexed from sliceFrom (kOffset = sliceFrom − fitFrom),
    //    and add back the stable, long-averaged DC (the SAME acDcMean the AC display
    //    offset uses) so the residual baseline is pinned to the Vmean-stable source.
    const sliceLen = sliceTo - sliceFrom;
    let scratch = leftChannel ? this._residualScratchL : this._residualScratchR;
    if (!scratch || scratch.length < sliceLen) {
      scratch = new Float32Array(sliceLen);
      if (leftChannel) this._residualScratchL = scratch; else this._residualScratchR = scratch;
    }
    const dcAvg = this._acDcMean(leftChannel);
    const dcStable = Number.isFinite(dcAvg) ? dcAvg : 0;   // Java lastMeanNormalized defaults 0
    if (dual) {
      // Two tones: alternating Gauss-Seidel refits over the FULL fit window. Each round
      // refits one tone on data with the OTHER tone's latest estimate removed, squaring
      // the remaining cross-leakage - after RESIDUAL_DUAL_REFIT_ROUNDS the remnant is
      // below the noise regardless of how few beat cycles the window holds. The last
      // subtraction removes the whole model + pins the baseline to dcStable exactly like
      // the single-tone path.
      let fs = this._residualFitScratch;
      if (!fs || fs.length < fitLen) { fs = new Float32Array(fitLen); this._residualFitScratch = fs; }
      let fitA = SineFit.of(data, fitFrom, fitLen, sampleRate, f1);
      fitA.subtractSineInto(data, fitFrom, fitLen, 0, fs, 0);   // fs = data − A₀
      let fitB = SineFit.of(fs, 0, fitLen, sampleRate, f2);
      if (!Number.isFinite(fitA.a) || !Number.isFinite(fitA.b)
        || !Number.isFinite(fitB.a) || !Number.isFinite(fitB.b)) {
        this._recordResidualVpp(ch, NaN);
        return null;
      }
      for (let round = 0; round < RESIDUAL_DUAL_REFIT_ROUNDS; round++) {
        fitB.subtractSineInto(data, fitFrom, fitLen, 0, fs, 0);   // fs = data − B
        fitA = SineFit.of(fs, 0, fitLen, sampleRate, f1);
        fitA.subtractSineInto(data, fitFrom, fitLen, 0, fs, 0);   // fs = data − A
        fitB = SineFit.of(fs, 0, fitLen, sampleRate, f2);
      }
      // Final subtraction over the DISPLAY SLICE, evaluated analytically from data
      // exactly like the single-tone path - NOT copied out of the fit scratch: the fit
      // window is capped at RESIDUAL_FIT_MAX_SAMPLES, so at large time/div the slice
      // extends beyond it (bench: 67 200-sample slice vs a 65 536 fit window -> out-of-
      // bounds copy). The fitted sines extrapolate exactly at any k, so the slice tail
      // beyond the fit window subtracts just as cleanly (Java ScopeView.computeResidual).
      fitA.subtractSineInto(data, sliceFrom, sliceLen, sliceFrom - fitFrom, scratch, 0);
      fitB.subtractFullInto(scratch, 0, sliceLen, sliceFrom - fitFrom, dcStable, scratch, 0);
    } else {
      fit.subtractFullInto(data, sliceFrom, sliceLen, sliceFrom - fitFrom, dcStable, scratch, 0);
    }

    // 6. Vpp over the VISIBLE part only (dispStart .. dispStart+dispCount), in volts, for
    //    auto-setup's per-channel vertical scaling.
    const visFrom = Math.max(0, dispStart - sliceFrom);
    const visTo = Math.min(sliceLen, dispStart - sliceFrom + dispCount);
    let vpp = NaN;
    if (visTo > visFrom) {
      let min = scratch[visFrom], max = scratch[visFrom];
      for (let i = visFrom + 1; i < visTo; i++) {
        const v = scratch[i];
        if (v < min) min = v;
        if (v > max) max = v;
      }
      vpp = (max - min) * peakVolts;
    }
    this._recordResidualVpp(ch, vpp);

    // 7. Report where dispStart landed inside the scratch + the valid length, and return
    //    the scratch (whose .length may exceed sliceLen).
    this._residualDispStart = dispStart - sliceFrom;
    this._residualSliceLen = sliceLen;
    return scratch;
  }

  /** Stores the residual Vpp for the given channel (see _computeResidual). */
  _recordResidualVpp(ch, vpp) {
    this._lastResidualVpp[ch] = vpp;
  }

  /** Copies data[sliceFrom .. sliceFrom+sliceLen) into the given channel's residual
   *  scratch (grown as needed) so a shown-but-non-residual channel renders off the SAME
   *  slice geometry as the residual channel (Java ScopeView.copyResidualSlice). Out-of-
   *  range samples are left as zeros (drawTrace blanks columns the buffer can't fill). */
  _copyResidualSlice(data, sliceFrom, sliceLen, leftChannel) {
    let scratch = leftChannel ? this._residualScratchL : this._residualScratchR;
    if (!scratch || scratch.length < sliceLen) {
      scratch = new Float32Array(sliceLen);
      if (leftChannel) this._residualScratchL = scratch; else this._residualScratchR = scratch;
    }
    const from = Math.max(0, sliceFrom);
    const to = Math.min(data.length, sliceFrom + sliceLen);
    const off = from - sliceFrom;
    if (off > 0) scratch.fill(0, 0, Math.min(off, sliceLen));
    if (to > from) scratch.set(data.subarray(from, to), off);
    if (off + (to - from) < sliceLen) scratch.fill(0, off + Math.max(0, to - from), sliceLen);
    return scratch;
  }

  /** Wraps a radian angle into (−π, π] (Java ScopeView.wrapToPi). */
  _wrapToPi(r) {
    const twoPi = 2.0 * Math.PI;
    return r - twoPi * Math.floor((r + Math.PI) / twoPi);
  }

  /** Pushes the measurement channel's per-frame measurement into the rolling
   *  MeasurementStats history and, at most every READOUT_THROTTLE_MS, rebuilds
   *  this.measurementRows (8 rows × {cur,avg,min,max,sigma}) over
   *  oscMeasurementAverageSeconds - Java prepareMeasurementRows + throttle. For
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

  /** Clears the rolling measurement history (Java clearMeasurementHistory) - call
   *  on a measurement-channel switch / reset. Resets ONLY the per-quantity
   *  accumulators (avg/min/max/σ restart from the next frame); the CUR column and
   *  the cached rows stay so the table keeps showing the current value with no
   *  empty flash. The throttle clocks are zeroed so the NEXT frame
   *  immediately recomputes the live window and rebuilds the rows with freshly
   *  restarted stats - matching Java, which keeps the worker's current value and
   *  rebuilds on the next paint. */
  _clearMeasurementHistory() {
    const s = this._measStats;
    for (const k in s) s[k].clear();
    // Reset the whole-period Vmean ring + latest means (Java clearHistory resets the
    // meas/mean history ring) so the AC DC block restarts clean with the capture.
    this._meanHist.L.write = 0; this._meanHist.L.size = 0;
    this._meanHist.R.write = 0; this._meanHist.R.size = 0;
    this._lastMeanNorm = { L: NaN, R: NaN };
    this._lastMeasBuildMs = 0;
    this._lastLiveMeasMs = 0;
    this._lastMeasPublishMs = 0;   // force the next render to recompute / re-read a fresh publish
    // Drop the synchronous-fallback engine's stream state (filters + collection + stale
    // async frequencies) so the injected-window path restarts clean.
    this._measEngine.resetStream();
    // Reset the LIVE osc-meas worker's stream too (spec §5): a stats reset / channel switch
    // restarts its collection window + adaptive filters clean. Injected by the pane, which
    // owns the controller-side client (the view can't reach the engine).
    if (this.onClearMeasurement) this.onClearMeasurement();
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
      // No new frame this paint - clamp the displayed rate to the instantaneous
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
   *  on-screen pixel size. Visible for the whole live record - even reading
   *  0.000: in glitch mode a new frame only arrives per glitch, so this readout
   *  IS the glitches/s counter and must not vanish between events. A STOPPED
   *  scope keeps showing the last value (no repaint runs while stopped); hidden
   *  only in file mode (no capture) and before the first capture ever ran
   *  (`show` = false from renderIdle). 3 decimals: in glitch mode a rare event
   *  (one per minutes) reads e.g. 0.008 - one decimal would show 0.0. */
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
   *  the offscreen capture it must render static - C29b). Only the offscreen
   *  screenshot clone sets this.screenshotFilePath, so the live render never
   *  draws it (the live view shows the self-blinking #scopeFileBanner instead).
   *  Drawn on the row below the cap/s readout, right-aligned, with a dark
   *  outline so it reads over the trace; left-truncated with a "..." prefix when
   *  it doesn't fit. */
  _drawStaticFilePath(g, W) {
    if (this._overlayG) g = this._overlayG;   // OVERLAY phase -> scratch (see _drawSliders)
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
      while (text.length > 1 && g.measureText('...' + text).width > maxW) text = text.slice(1);
      text = '...' + text;
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
   *  grow-only reused buffer so there is no per-paint allocation - the per-frame
   *  cost is a single bounded memcpy per channel. Called every live render so a
   *  held frame is always available to freeze (SINGLE/NORMAL trigger) or to seed
   *  on an Auto->SINGLE switch. */
  _snapshotFrame(descriptors, trigDesc, p, available,
                 startSample, windowSamples, cols, samplesPerPx, pxPerSample, meas,
                 drawBufs, triggerLocal = NaN, beatBufs = null) {
    // `available` is the DRAWN window length (= the residual slice length when residual is
    // active, else the full capture). Each channel snapshots the SUBSTITUTED buffer it was
    // drawn from - the residual scratch when residual is active - so the frozen replay path
    // paints the residual verbatim (no re-fit), exactly like Java moving captureFrame after
    // the residual substitution in renderTraces.
    const copyInto = (slot, src) => {
      let dst = this._snapBufs[slot];
      if (!dst || dst.length < available) { dst = new Float32Array(available); this._snapBufs[slot] = dst; }
      dst.set(src.subarray(0, available));
      return dst;
    };
    // Snapshot what was DRAWN: the residual substitution when active, else the
    // channel's own captured signal - never trigBuf (the beat is trigger-only).
    const drawnBuf = (d) => (drawBufs && drawBufs[d.ch] !== undefined) ? drawBufs[d.ch] : d.procBuf;
    const chans = descriptors.map((d, i) => ({
      ch: d.ch,
      snap: copyInto(i, drawnBuf(d)),
      offsetFrac: d.offsetFrac, vDiv: d.vDiv, dcOff: d.dcOff, sinc: d.sinc, hex: d.hex,
      // Frozen fallback full-scale (no-prefs replay); the live replay re-reads the
      // channel's own getAdcPeakVolts so a calibration change re-scales the held trace.
      peak: d.peak,
    }));
    // Held frames DO carry the reconstructed-beat overlay (Java renderHeldCapturedFrame
    // now runs drawBeatOverlays on the captured samples, gated identically to the live
    // path). The live overlay loop already copied each residual-off channel's beat OUT of
    // the shared _beatScratch into its own _snapBeatBufs slot (re-based to the drawn
    // window), so these references stay valid for the frozen replay exactly like the
    // per-channel trace snapshots. `beats` is the per-channel list drawn in _drawHeldFrame.
    const beats = beatBufs
      ? descriptors.map((d) => beatBufs[d.ch]).filter((b) => b)
      : [];
    this._frame = { chans, beats, available, startSample, windowSamples, cols,
                    samplesPerPx, pxPerSample, meas,
                    tDivAtFreeze: p ? p.oscTimePerDiv.get() : 0,
                    // Captured trigger position in the frame's own sample coordinates
                    // (Java captureSingleFrame capturedTriggerLocal); NaN for a
                    // trigger-less (AUTO free-run) entry snapshot. When finite,
                    // _drawHeldFrame positions the held window via the LIVE trigger
                    // offset so Shift+wheel scrolls the frozen trace (Java
                    // renderHeldCapturedFrame capturedTriggerLocal branch).
                    triggerLocal };
  }

  /** Repaints the frozen NORMAL/SINGLE frame from its captured snapshot (the live
   *  ring keeps scrolling, so the held trace renders from a copy, not the ring),
   *  then re-draws the SAME overlays the live path draws (Java paintCanvas always
   *  runs drawSliders / drawEdgeLabels / drawCaptureRate after drawWaveforms,
   *  whether the frame is live or held): the on-canvas trigger-level / trigger-
   *  position / channel-offset handles, the per-channel V/time EDGE MARKERS, and
   *  the cap/s readout - now showing the ACTUAL (decaying) capture rate. The DOM
   *  measurement table reads this.measurementRows, which is NOT nulled here, so it
   *  stays visible too. No new full-window compute: traces come from the captured
   *  snapshot, overlays from live prefs, cap/s from the new-frame timestamp EMA. */
  /** Paints the static scope chrome - graticule grid + V/time edge marks + trigger /
   *  channel sliders + cap/s - with NO trace, so the grid, controls and marks are
   *  ALWAYS visible even before recording starts or when stopped with no captured
   *  frame (Java ScopeView always paints the empty grid). Sizes the backing store to
   *  the CSS box (live/virtual px) so the canvas is never left blank/default-sized. */
  renderIdle() {
    // Idle = nothing ever captured, so there is no afterglow to persist - the idle grid
    // is drawn straight (no phosphor wrapper); the first live render() seeds the buffer.
    this._lastFrameWasNew = false;
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
    this._drawCaptureRate(false);   // no capture ever ran -> hidden (Java reader == null)
    if (this._rectZoom) this._rectZoom.drawOverlay(this._overlayG || g, W, H);   // zoom layer LAST, on the idle grid too
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
    // A held frame is never a genuinely new capture (Java lastFrameWasNew stays false),
    // so the phosphor wrapper re-composites the afterglow rather than accumulating.
    this._lastFrameWasNew = false;
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
    // Horizontal t/div zoom on a held frame (Java renderHeldCapturedFrame, ba42b91):
    // re-derive the displayed window from the LIVE t/div, then position it via the
    // (virtual-capable) LIVE trigger offset - the SAME single branch as the live model.
    // The entry snapshot carries a SYNTHETIC anchor (f.triggerLocal is never NaN), so it
    // rides the same branch - no separate magnify anchor: a Shift+wheel PAN moves p and
    // scrolls the frozen trace; a t/div FIELD zoom (p unchanged) zooms around the
    // trigger; Ctrl+Shift+wheel updates p to keep the sample under the cursor. NOT
    // clamped - off-buffer edges blank (blankBeyondData below), matching Java.
    const tDivLive = p ? p.oscTimePerDiv.get() : f.tDivAtFreeze;
    const scale = (f.tDivAtFreeze > 0 && tDivLive > 0) ? tDivLive / f.tDivAtFreeze : 1;
    const winSamples = Math.max(2, Math.round(f.windowSamples * scale));
    if (p) {
      const posFrac = p.oscTriggerPositionFrac.get();
      f.heldStart = f.triggerLocal - posFrac * winSamples;
    } else {
      f.heldStart = f.startSample;      // no-prefs replay: frozen position
    }
    const startS = f.heldStart;
    const sPerPx = winSamples / f.cols;
    const pxPerS = f.cols / winSamples;
    const drawSnap = (c) => {
      const vDiv = (c.ch && p) ? liveVDiv(c.ch) : c.vDiv;
      const offsetFrac = (c.ch && p) ? liveOff(c.ch) : c.offsetFrac;
      // Per-channel full-scale: re-read live (a calibration re-scales the held trace),
      // fall back to the frozen snapshot value in the no-prefs replay.
      const peak = (c.ch && p) ? p.getAdcPeakVolts(c.ch) : c.peak;
      const dcOff = c.dcOff || 0;
      const sampleToY = (s) => H * (offsetFrac - ((s - dcOff) * peak) / (vDiv * DIVISIONS_Y));
      this._drawTrace(g, W, H, c.snap, f.available, startS, winSamples,
                      f.cols, sPerPx, pxPerS, sampleToY, c.sinc, c.hex,
                      c.dotOverride, true);   // held magnify -> blankBeyondData (Java renderHeldCapturedFrame)
    };
    for (const c of f.chans) drawSnap(c);
    // Reconstructed-beat overlays on the held frame - one per residual-off channel,
    // in that channel's dimmed colour at its LIVE V/div + offset (so it stays paired
    // with the re-scaled trace), zero dcOff + no sinc (Java drawBeatOverlays: dcOffset
    // 0.0, sincEnabled false, dotDiameter 0), matching the live per-channel beat draw.
    for (const b of (f.beats || [])) {
      drawSnap({ ch: b.ch, snap: b.snap, offsetFrac: b.offsetFrac, vDiv: b.vDiv,
                 dcOff: 0, sinc: false, hex: b.hex, dotOverride: 0 });
    }
    // Overlays - driven by LIVE prefs (Java drawSliders / drawEdgeLabels read
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
    // Rect-zoom rubber band + focused-view accent border - LAST (Java paint order).
    if (this._rectZoom) this._rectZoom.drawOverlay(this._overlayG || g, W, H);
    // Do NOT overwrite this.latest with the stale snapshot meas: _measureLiveWindow
    // refreshed it (and the measurement table) THIS paint, decoupled from the held
    // trace. Fall back to the snapshot's meas only if no live measurement
    // exists yet (e.g. file-mode held frame, which carries its own f.meas).
    if (!this.latest) this.latest = f.meas;
  }

  /**
   * Applies the active channel's HF cleanup (LpfMode) then mains-hum
   * suppression (MainsSuppression) to a copy of {@code buf}, in place on the
   * copy, and returns it. Faithful to ScopeView.applyHfLowPass +
   * applyMainsSuppression: LpfMode.HZ_80 -> 80 kHz Chebyshev LP (a no-op below
   * its Nyquist), DESPIKE -> median; mains is the mode-selected time-domain
   * canceller (dsp/mains/factory: IIR_COMB / SYNC_SUBTRACT / LMS - Java
   * MainsFilters.of). No-op (returns the raw buf) when both are NONE.
   * absStart is the absolute index of buf[0] in the capture stream - the
   * phase-locked cancellers (sync-subtract / LMS) advance their mains phase by
   * its delta across calls; the comb ignores it. stKey selects the filter-state
   * bag: the display pass uses the channel name, the measurement pass its own
   * '<ch>Meas' bag - Java keeps ScopeView's and ScopeMeasurementWorker's
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
    // state across paints - Java applyMainsSuppression resets only IIR_COMB) ---
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
   * (1 s/div × 10 div -> the whole capture overview), and the vertical scale
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
    // Live/virtual pixels only - backing store == CSS box, no devicePixelRatio (matches render()).
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
    // Vertical map: full-scale (no V/div, no offset) - ±1.0 -> ±H/2 (Java vScale).
    const sampleToY = (s) => centerY - s * vScale;
    // The condensed strip always keeps the per-column min/max envelope (dpoEligible = false):
    // Java ZoomedView.drawTrace uses per-column min/max bars (Lanczos below 5 spp) with NO
    // phosphor renderer, so the digital phosphor stays the MAIN scope trace's dense renderer only.
    if (showL) this._drawTrace(g, W, H, bufL, available, dispStart, dispCount, W, samplesPerPx, pxPerSample, sampleToY, true, colorHex(p ? p.oscLeftChannelColor.get() : 0x00d7ff), 0, false, false);
    if (showR) this._drawTrace(g, W, H, bufR, available, dispStart, dispCount, W, samplesPerPx, pxPerSample, sampleToY, true, colorHex(p ? p.oscRightChannelColor.get() : 0xffd700), 0, false, false);
  }
}
