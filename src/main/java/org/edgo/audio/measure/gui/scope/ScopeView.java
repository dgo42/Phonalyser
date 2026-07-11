/*
 * Phonalyser — precision audio measurement workbench.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.edgo.audio.measure.gui.scope;

import java.util.Arrays;
import java.util.Map;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Cursor;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.LineAttributes;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Scrollable;
import org.edgo.audio.measure.common.Lanczos;
import org.edgo.audio.measure.dsp.LowPassFilter;
import org.edgo.audio.measure.dsp.MainsFilters;
import org.edgo.audio.measure.dsp.MainsTimeFilter;
import org.edgo.audio.measure.dsp.MedianFilter;
import org.edgo.audio.measure.dsp.SineFit;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.enums.LpfMode;
import org.edgo.audio.measure.enums.MainsSuppression;
import org.edgo.audio.measure.enums.OscSliderId;
import org.edgo.audio.measure.dsp.TimeDiscontinuityDetector;
import org.edgo.audio.measure.enums.TriggerEdge;
import org.edgo.audio.measure.enums.TriggerMode;
import org.edgo.audio.measure.enums.TriggerType;
import org.edgo.audio.measure.gui.bind.Bindings;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.AbstractMeasurementView;
import org.edgo.audio.measure.gui.common.FftBinSnap;
import org.edgo.audio.measure.gui.common.Fonts;
import org.edgo.audio.measure.gui.common.GcMeasurementPainter;
import org.edgo.audio.measure.gui.common.MeasurementPainter;
import org.edgo.audio.measure.gui.common.Icon;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.scope.gl.GlScopeRenderer;
import org.edgo.audio.measure.gui.sound.SignalBufferReader;
import org.edgo.audio.measure.gui.widgets.BlinkBanner;
import org.edgo.audio.measure.gui.widgets.ToolButton;
import org.edgo.audio.measure.gui.widgets.ToolWindow;
import org.edgo.audio.measure.gui.widgets.Toolbar;
import org.edgo.audio.measure.gui.widgets.TransparentComposite;
import org.edgo.audio.measure.preferences.Preferences;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * Oscilloscope main display.  Renders a black canvas with a 10×10 division
 * grid, a centre cross carrying 1/5-division minor tick marks, and (when a
 * {@link SignalBufferReader} is attached via {@link #setBuffer}) the
 * left/right channel waveforms scaled by the {@link Preferences}-stored
 * volts/division and time/division settings.
 */
@Log4j2
public final class ScopeView extends AbstractMeasurementView implements GlScopeRenderer {

    /** Number of horizontal grid divisions.  Package-private so the pane's
     *  mouse-anchored t/div zoom can compute the time at the mouse using
     *  the same grid the renderer uses. */
    static final         int DIVISIONS_X    = 10;
    /** Number of vertical grid divisions.  Package-private so the pane's
     *  vertical-scrollbar math can size its thumb against the same grid
     *  the renderer uses. */
    static final         int DIVISIONS_Y    = 10;

    /** Sub-ticks per grid division along each cross-hair arm
     *  ({@code CrossHairSpec.subTicksPerDivision}) — a tick COUNT, not a length:
     *  5 gives the classic 0.2-div minor marks of a scope graticule. */
    private final static int TICKS_PER_DIV = 5;
    /** Half-length in px of each cross-hair sub-tick, drawn perpendicular to the
     *  arm ({@code CrossHairSpec.subTickHalfLen}) — distinct from the views' axis
     *  tick lengths, which size the ticks along the chart edges instead. */
    private final static int TICK_HALF_LEN = 4;

    /** How far (px) the trace polyline's end points are pushed OUTSIDE the
     *  canvas: the painter's clip hides the overhang, but the stroke's
     *  CAP_ROUND endpoint then lies beyond the visible area, so the
     *  antialiased trace reaches both edges at full intensity instead of
     *  fading where a cap would sit.  Must exceed the largest line width's
     *  cap radius (5 px wide → 2.5 px). */
    private final static int TRACE_EDGE_OVERHANG_PX = 4;

    // All scope colours live in the AbstractMeasurementView palette
    // (background, grid, text, crosshair, blink lit/dim, left/right
    // trace, left/right channel mid, disabled channel, reset).  The
    // dark-theme overrides + the prefs-driven trace RGBs are passed
    // to super(...) below; the derived mid colours are computed via
    // attenuate(...) and applied via setColor in syncChannelColors().
    /** Cached RGB ints of the channel colours — used to detect pref changes. */
    private int currentLeftRgb  = -1;
    private int currentRightRgb = -1;

    /** Latest-window read cursor over the shared capture (or a wrapped frozen /
     *  file buffer).  The scope reads relative to {@code writePos}, so it never
     *  uses the cursor's read position — it just delegates readLatest /
     *  readEndingAt. */
    @Getter private SignalBufferReader reader;
    private float[] leftBuf  = new float[0];
    private float[] rightBuf = new float[0];

    /** The pan/zoom engine: every horizontal/vertical move/zoom transform + the
     *  viewport mapping.  Stateless (grid divisions + the 1-2-5 ladder), so the
     *  pane and tab control share this one instance for their wheel/drag/control
     *  handlers — keeping all the logic in {@link ScopeNav}, not scattered here. */
    @Getter private final ScopeNav nav =
            new ScopeNav(DIVISIONS_X, DIVISIONS_Y, OscParse.voltsPerDivTargets());

    /** Carbon-copy of the last trace frame this view rendered (the exact
     *  windowed samples + render parameters passed to {@link #renderTraces}).
     *  The screenshot pane reads this so a saved image matches what's on
     *  screen — including a frozen / stopped trace — instead of re-reading
     *  fresh samples from the buffer. */
    private RenderedFrame lastFrame;
    /** Grow-only staging buffers behind {@link #lastFrame} — overwritten
     *  every paint; see {@link #captureFrame}. */
    private float[] capStageL, capStageR;
    /** Grow-only scratch for {@link #reconstructBeatSignal} (output + the
     *  two boxcar cascade stages) — rebuilt every paint while DUAL_TONE. */
    private float[] beatOut, beatTmp, beatAbsLp;
    /** Per-channel grow-only scratch for {@link #computeResidual} — holds the
     *  fit-window slice with the best-fit single tone subtracted, reused across
     *  paints (paint is single-threaded).  Lazily sized to the slice length. */
    private float[] residualScratchL, residualScratchR;
    /** Grow-only FIT-WINDOW-sized scratch for the dual-tone residual (the two
     *  per-tone fits need the tone-subtracted data over the whole fit window,
     *  not just the display slice).  Channels are computed sequentially within
     *  one paint, so a single buffer serves both. */
    private float[] residualFitScratch;
    /** Residual Vpp (volts) over the VISIBLE window, recorded at the last paint
     *  for each channel when its residual pref is on; {@link Double#NaN} when the
     *  residual is off or unavailable.  Read by {@link #autoSetupVpp}. */
    private double lastResidualVppL = Double.NaN;
    private double lastResidualVppR = Double.NaN;
    /** Index in the returned residual scratch that corresponds to the caller's
     *  {@code dispStart} — the scratch is indexed from the fit-window start, so
     *  this is {@code dispStart − sliceFrom}.  Set by {@link #computeResidual}
     *  each call, read by the caller immediately after (single-threaded paint). */
    private int    residualDispStart;
    /** Number of VALID samples in the returned residual scratch (the scratch is
     *  grow-only, so its {@code .length} may exceed this).  Set by
     *  {@link #computeResidual} alongside {@link #residualDispStart}. */
    private int    residualSliceLen;
    /** When non-null this view renders {@code frozenFrame} verbatim and
     *  ignores the live buffer — used by the offscreen screenshot view. */
    private RenderedFrame frozenFrame;

    /** Per-channel mains-hum combs (lazily built for the capture rate),
     *  used when a channel's mains-suppression mode is IIR_COMB.  Re-tuned
     *  occasionally (mains drift is slow) and reset+applied each paint over
     *  the contiguous read window; the displayed span sits well inside the
     *  read's pre-roll so the comb's start transient stays off-screen left. */
    private MainsTimeFilter mainsLeft, mainsRight;
    private MainsSuppression mainsLeftMode = MainsSuppression.NONE;
    private MainsSuppression mainsRightMode = MainsSuppression.NONE;
    private int  mainsCombSampleRate;
    private long mainsTrackNanos;

    /** Per-channel HF low-pass combs that strip switching / RF spikes above
     *  the audio band before display + trigger.  Always applied (both
     *  channels) but a no-op below {@value #SCOPE_HF_LPF_HZ} Hz Nyquist, so
     *  they only do work at high sample rates where such spikes appear. */
    private LowPassFilter hfLpfLeft, hfLpfRight;
    private int  hfLpfSampleRate;
    /** Per-channel median de-spike filters (LpfMode.DESPIKE). */
    private MedianFilter despikeLeft, despikeRight;
    /** Last-logged tracked mains frequency per channel (debounce for the
     *  lock diagnostic); {@link Double#NaN} = not tracking / not logged. */
    private double mainsLoggedHzL = Double.NaN, mainsLoggedHzR = Double.NaN;
    /** Notch −3 dB width (Hz) for the scope mains combs. */
    private static final double MAINS_NOTCH_BW_HZ = 2.0;
    /** Minimum spacing between mains re-tracks (ns); tuning persists across
     *  the per-paint reset, so the comb keeps filtering between tracks. */
    private static final long MAINS_TRACK_PERIOD_NANOS = 200_000_000L;

    /**
     * Absolute writePos (in samples since capture start) of the trigger event
     * currently anchoring the display.  {@code -1} means no trigger captured
     * yet.  Used to hold the same frame across redraws in NORMAL / SINGLE
     * modes — the buffer keeps scrolling forward but the same absolute time
     * stays centred on the pane until a new trigger is taken.
     */
    private long lastTriggerAbsPos = -1;

    /**
     * Sub-sample part of the last trigger position (0..1).  The integer part
     * lives in {@link #lastTriggerAbsPos}; the fraction shifts the rendered
     * signal so the centre pixel lands precisely on the zero crossing of the
     * band-limited reconstruction.
     */
    private double lastTriggerSubSampleOffset = 0.0;

    /**
     * True between pressing the Start button and the next captured trigger
     * in SINGLE mode.  Cleared on capture so subsequent redraws hold the
     * frame instead of re-triggering.
     */
    private boolean singleArmed = false;

    /**
     * SINGLE-mode capture: dedicated buffers holding the frozen frame.
     * Independent of the ring buffer so the trace stays visible
     * indefinitely after the trigger fires — even after the original
     * samples have scrolled out of the live buffer.  {@code singleHeld}
     * gates whether the contents are valid.
     */
    private boolean singleHeld = false;
    /** Scope is stopped on a frozen buffer.  The render path is unchanged — it just
     *  holds the last trigger (step 2) for every mode instead of re-searching /
     *  free-running, so a stopped trace pans/zooms exactly like live, and auto-setup
     *  can scale the vertical off the held measurements. */
    @Getter private boolean frozen = false;
    private float[] capturedLeft;
    private float[] capturedRight;
    private int     capturedLen;
    private int     capturedDispStart;
    private int     capturedDispCount;
    private double  capturedSubSampleOffset;
    /** Trigger position within the captured copy (captured-buffer samples, sub-sample
     *  accurate), or NaN for a trigger-less entry-frame snapshot.  Lets a held frame
     *  position via the (virtual-capable) trigger offset — pan + zoom exactly like the
     *  live trigger-offset model — instead of a separate magnify anchor. */
    private double  capturedTriggerLocal = Double.NaN;
    /** Per-channel DC (normalised) of the captured frame, cached at freeze so
     *  AC-mode held renders subtract a STABLE bias instead of the live, still-
     *  averaging worker mean (which would drift the frozen trace vertically). */
    private double  capturedDcL;
    private double  capturedDcR;
    /** time/div in effect when the frame was frozen — lets {@link
     *  #renderHeldCapturedFrame} re-derive the shown window for the current
     *  time/div so Ctrl+Shift+wheel zoom works on a frozen frame. */
    private double  capturedTimePerDiv;
    /** Sample rate of the live reader when the frame was captured — the held
     *  render path may run with {@link #reader} already null (SINGLE hold after
     *  Stop), so the residual fit reads its rate from here instead. */
    private int     capturedSampleRate;
    /** Absolute start sample + sub-sample offset of the window the last live
     *  (AUTO / hold) paint rendered, so a mode-switch freeze re-grabs the EXACT
     *  frame that was on screen instead of newer samples that arrived since the
     *  last live paint (a visible left/right shift on a sweep / fast time-base).
     *  {@code lastLiveDispStartAbs} is -1 until a live frame has been drawn. */
    private long    lastLiveDispStartAbs = -1;
    private double  lastLiveSubSampleOffset;
    /** Absolute sample sitting at the trigger-position fraction of the last live
     *  window — the anchor a STOPPED scope holds (step 2) when it never triggered
     *  (free-running AUTO, {@link #lastTriggerAbsPos} = -1), so its zoom/pan still
     *  pin to the trigger offset instead of free-running to the buffer's edge.
     *  -1 until a live frame has been drawn. */
    private long    lastLiveAnchorAbs = -1;
    /** The visible widget that receives pointer events + cursor/tooltip and whose
     *  client area sizes the pointer math — {@code this} on the CPU path, the GL
     *  canvas on the GPU path (where this view is hidden). */
    private Control  pointerHost = this;
    /** GPU repaint hook: {@link #redraw()} runs it so every repaint reaches the GL
     *  surface while the view is hidden.  Null on the CPU path. */
    private Runnable glRepaint;
    /** GPU OVERLAY-only repaint hook (rect-zoom rubber band / focus border): the
     *  pane routes it to a phosphor re-composite instead of a reset, so hovering
     *  or dragging a selection never wipes the persistence afterglow.  Null on
     *  the CPU path. */
    private Runnable glOverlayRepaint;
    /** The GPU-drawn header button currently pressed (the mouse was forwarded to it),
     *  so the release goes to the same button.  Null on the CPU path / no press. */
    private ToolButton pressedHeaderButton;
    /** Pane-attached controller — the rect zoom's FILE-mode horizontal part
     *  goes through its {@code viewCenterFrames} + {@code applyViewState}. */
    private ScopeController controller;
    public void attachController(ScopeController controller) { this.controller = controller; }
    /** Frozen-frame magnify state.  {@code heldViewStart} is the captured-buffer
     *  sample shown at the LEFT edge of the held view (may be negative or past the
     *  data — where it is, {@code blankBeyondData} makes {@link #drawTrace} draw
     *  nothing rather than clamp/shift).  When the horizontal scale changes
     *  ({@code lastHeldTimePerDiv} differs), the start is recomputed so the sample
     *  under {@code hoverX} stays put; {@code lastHeldDispCount} is the previous
     *  span used for that. */
    private double  heldViewStart;
    private double  lastHeldTimePerDiv;
    private int     lastHeldDispCount;
    private boolean blankBeyondData;
    /** Canvas X to anchor the next frozen-frame t/div change around, or -1 to
     *  anchor on the screen centre.  The wheel-zoom sets it (cursor); a t/div
     *  FIELD change leaves it -1 so the trace stays centred instead of jumping
     *  to the stale cursor position.  Consumed by {@link #renderHeldCapturedFrame}. */
    private int     heldZoomAnchorX = -1;

    /**
     * Last trigger mode seen by {@link #drawWaveforms} — used to detect
     * mode transitions so a stale SINGLE capture from a previous session
     * doesn't pop back onto the screen the moment the user re-enters
     * SINGLE mode.
     */
    private TriggerMode lastTriggerMode;

    /**
     * Sliding capture-rate readout, in "new frames per second" rather than
     * raw paint rate.  Updated only when {@link #drawWaveforms} renders new
     * content (a fresh trigger event, or AUTO free-run latest samples).
     * Frames that re-render the same frozen content (NORMAL with no new
     * trigger, SINGLE held, blank pane) don't bump the rate — they decay it
     * toward zero based on the age of the last new frame.  Zero means no
     * new frame has arrived recently.
     */
    private long    lastNewFrameNanos;
    /**
     * Number of nanoseconds of captured samples to discard from every
     * measurement-worker read.  Lets the DC mean be computed strictly from
     * samples that arrived after the ADC's startup transient, so the
     * AC-mode trace's history-averaged DC subtraction isn't biased by the
     * settling.
     */
    private double  captureRate;

    /**
     * Refresh period for the cap/s readout string and the measurement-table
     * MeasurementRow[] aggregation.  Limits how often the (relatively expensive)
     * String.format + stats walk runs — the canvas is still redrawn on every
     * paint, just with the cached values.
     */
    private static final long READOUT_THROTTLE_NS = 200_000_000L;
    /** Minimum glitch-mode collection time before the cumulative rate is shown —
     *  below this, count ÷ elapsed is dominated by start-up jitter, so read 0. */
    private static final double GLITCH_RATE_MIN_SECONDS = 1.0;
    private MeasurementRow[]  cachedMeasurementRows;
    /** The worker result the cached rows were built from; the rows rebuild when the
     *  worker posts a NEW result (its own compute rhythm) rather than on a display
     *  timer whose period beat against the capture block rate. */
    private SignalMeasurements lastBuiltMeasResult;
    private String cachedCapsString = "";
    private long   lastCapsBuildNanos;

    /**
     * Reusable {@link LineAttributes} instances — created once and mutated
     * (or read as-is) per paint instead of allocating a fresh wrapper every
     * frame.  At 65+ cap/s the per-paint {@code new LineAttributes(...)}
     * calls were adding ~200 short-lived objects/sec to the young
     * generation; pooling them removes that pressure.
     */
    private final LineAttributes lineAttrsTrace = new LineAttributes(1.0f,
            SWT.CAP_ROUND, SWT.JOIN_ROUND);
    private final LineAttributes lineAttrsBars  = new LineAttributes(1.0f,
            SWT.CAP_FLAT,  SWT.JOIN_BEVEL);

    /** Sub-sample step for the sin(x)/x peak refinement in {@link #drawEnvelope}
     *  (curve reconstructed within ±1 sample of each column's extreme samples). */
    private static final double RECON_REFINE_STEP = 0.1;

    /** Residual mode: minimum number of tone cycles the least-squares fit window
     *  must span, so amplitude/phase are well-conditioned even at a narrow t/div. */
    private static final int    RESIDUAL_MIN_CYCLES     = 8;
    /** Residual mode: cap on the fit-window length (samples) so a very low-frequency
     *  tone at a wide lookback can't blow the fit into a multi-second walk per paint. */
    private static final int    RESIDUAL_FIT_MAX_SAMPLES = 65_536;
    /** Residual mode: floor on the fit-window length (samples); below it the
     *  3-parameter fit is too short to be trustworthy → paint the captured trace. */
    private static final int    RESIDUAL_MIN_FIT_SAMPLES = 256;
    /** Residual mode: sanity cap (Hz) on the per-step frequency polish — a larger
     *  correction than this means the phase-slope estimate is unreliable (noise /
     *  wrong seed), so the polish is abandoned and the seed frequency is kept. */
    private static final double RESIDUAL_POLISH_MAX_HZ   = 1.0;
    /** Residual mode: number of phase-slope polish iterations (cheap; the Goertzel
     *  scan already gave a close seed, so 2 refinements suffice). */
    private static final int    RESIDUAL_POLISH_ITERS    = 2;
    /** Residual mode: stop polishing once a step moves the frequency less than this. */
    private static final double RESIDUAL_POLISH_MIN_STEP_HZ = 1e-6;
    /** Residual mode, dual tone: minimum |F1−F2| beat cycles the fit window must
     *  span — below that the two per-tone fits leak into each other. */
    private static final int    RESIDUAL_MIN_BEAT_CYCLES = 2;
    /** Residual mode, dual tone: alternating refit rounds after the initial
     *  fits.  Per-tone cross-leakage over T is ≈ 1/(π·|F1−F2|·T) first-order,
     *  and every refit SQUARES the remaining leakage — two rounds push even the
     *  2-beat-cycle floor (≈16 %) below the noise, making the residual remnant
     *  independent of the time/div-driven window length. */
    private static final int    RESIDUAL_DUAL_REFIT_ROUNDS = 2;

    /** Per-pixel-column min/max + connector-attach scratch for {@link #drawEnvelope},
     *  grown on demand and reused across both channels (paint is single-threaded). */
    private float[] envMin;
    private float[] envMax;
    private float[] envEntry;
    private float[] envExit;
    /**
     * Cached {@code textExtent} of the static {@link #HEADERS} strings.
     * Populated lazily on first paint with the GC already wearing {@code
     * monoFont}; the headers never change so this stays valid for the rest
     * of the view's life.  Saves ~325 {@code Point} allocations/sec at high
     * cap/s.
     */
    private Point[] headerExtents;

    /** Set by {@link #drawWaveforms} each paint; consumed by {@link #updateCaptureRate}. */
    private boolean lastFrameWasNew;
    /** Glitch-mode capture-rate state: rare events make a per-interval EMA useless, so
     *  the readout is total caught ÷ collection time.  Counting restarts when glitch
     *  mode is entered or a new record begins (detected via {@link #rateSawFrozen}). */
    private long    glitchCountStartNanos;
    private int     glitchCount;
    private boolean rateWasGlitchMode;
    private boolean rateSawFrozen;
    /**
     * How many samples back from the live writePos the view should anchor
     * its right edge.  0 = follow latest (default); positive values let
     * the user scroll back through the ring buffer via the navigation
     * slider in {@link ScopePane}.
     */
    @Getter
    private volatile double viewBackOffsetFrames;
    public void setViewBackOffsetFrames(double v) {
        double clamped = Math.max(0.0, v);
        // Live view: crossing 0 ↔ >0 switches the window model between
        // trigger-anchored and absolute — zoom mementos can't cross it.
        // File mode stays absolute at offset 0, so its history survives.
        if (!fileMode && (this.viewBackOffsetFrames == 0.0) != (clamped == 0.0)) clearZoomHistory();
        this.viewBackOffsetFrames = clamped;
    }
    /** TEMP: paint-profiling frame counter (see {@link #paintCanvas}). */
    private int paintProfileTick;
    /**
     * When true the view is showing a statically-loaded signal (no live
     * capture).  Trigger search makes no sense in this mode — the trace
     * uses the same right-edge anchoring as the navigation bypass so
     * scrolling and t/div changes don't snap to different trigger
     * positions.  Set by {@link ScopeOpenSignal#loadFile} on load and
     * cleared by {@link ScopeOpenSignal#clear}.
     */
    @Getter
    private volatile boolean fileMode;
    public void setFileMode(boolean fileMode) {
        // A mode switch invalidates every stacked zoom state — live mementos are
        // trigger-relative seconds, file mementos absolute frames.  A true→true
        // call is a NEW file loading over the old one: its mementos hold absolute
        // frames of the previous file's timeline, equally meaningless.
        if (fileMode || this.fileMode) clearZoomHistory();
        this.fileMode = fileMode;
    }

    /** Path of the currently-loaded openSignal file ({@code null} = not in
     *  file mode).  Rendered in the canvas top-right corner with a 20 px
     *  gap to the left of the cap/s readout, left-side-truncated with an
     *  ellipsis when it doesn't fit, and blinks between two near-white
     *  greys as a constant visual cue that the displayed signal is a
     *  frozen file capture, not live data. */
    private volatile String filePath;
    public void   setFilePath(String p) {
        this.filePath = (p == null || p.isEmpty()) ? null : p;
        if (!isDisposed()) redraw();
    }
    /** Self-blinking file-path banner widget (replaces the overlay-text label). */
    private BlinkBanner fileBanner;
    /**
     * Cached measurement result.  Recomputed at most once every
     * {@value #MEAS_COMPUTE_PERIOD_NANOS} nanoseconds so the per-paint cost
     * is dominated by the (cheap) table layout rather than the (expensive)
     * Goertzel scan.  Letting the trace render at 60+ FPS while measurements
     * tick at ~10 Hz is the biggest single throughput win.
     */
    /**
     * Background measurement worker.  Owns the compute thread, the
     * latest {@link SignalMeasurements} snapshot, the rolling history
     * ring (for avg / min / max / σ stats), and the per-channel DC mean
     * used by the AC-coupling display.  Paint code reads worker state
     * via accessors and via {@link ScopeMeasurementWorker#walkRecentHistory}
     * / {@link ScopeMeasurementWorker#averagedChannelMean}.
     */
    private final ScopeMeasurementWorker measWorker = new ScopeMeasurementWorker();

    /** Minimum averaging window for AC DC removal — never average less than this even when prefs.avg is shorter. */
    private static final long AC_DC_MIN_AVG_NANOS = 500_000_000L;

    /** Monospace font for the measurement table — created lazily, disposed with the view. */
    private Font monoFont;
    /** Bold, larger font used only for the L / R channel-pick header buttons. */
    private Font chanButtonFont;

    /** Hit-boxes for the header buttons — refreshed every paint, consumed by the mouse listener. */
    private Toolbar    headerBar;       // header buttons (migrated from canvas-draw to widgets)
    private ToolButton leftBtn;
    private ToolButton rightBtn;
    private ToolButton autoSetupBtn;
    private ToolButton tableToggleBtn;
    private ToolButton externalBtn;
    private ToolButton statsToggleBtn;
    private ToolButton resetBtn;
    private TransparentComposite dataSpacer;   // gap before the signal-gated buttons
    /** When true, the measurement-table rows are NOT drawn in this view —
     *  they're hosted in {@link #measurementWindow} instead.  Header
     *  buttons (L, R, Auto-Setup, gauge, external-window, chart, reset)
     *  stay in this view either way. */
    private boolean tableExtracted;
    /** The extracted measurements tool window (non-null only while extracted); it calls back
     *  into {@link #paintMeasurementTable} to draw the live table, and signals close /
     *  stats-reset. */
    private ToolWindow measurementWindow;

    /** Drawn-handle size for the three sliders, in pixels. */
    private static final int SLIDER_TRI_LONG = 10;   // triangle long axis (into the grid)
    private static final int SLIDER_TRI_HALF = 6;    // triangle perpendicular half-extent
    /** Hit-zone half-thickness in the perpendicular direction (extends past the triangle). */
    private static final int SLIDER_GRAB_HALF = 9;

    /** Hit-boxes for the three sliders — refreshed every paint, consumed by drag handlers. */
    private final Rectangle offsetSliderBounds   = new Rectangle(0, 0, 0, 0);
    private final Rectangle triggerLevelBounds   = new Rectangle(0, 0, 0, 0);
    private final Rectangle triggerPosBounds     = new Rectangle(0, 0, 0, 0);

    /** Hit-boxes for the centre-line voltage labels (top/bottom of vertical
     *  centre line for each channel) — refreshed every paint, consumed by the
     *  hover-tooltip handler.  Width/height = 0 means the label isn't currently
     *  visible (channel disabled). */
    private final Rectangle leftMaxLabelBounds   = new Rectangle(0, 0, 0, 0);
    private final Rectangle leftMinLabelBounds   = new Rectangle(0, 0, 0, 0);
    private final Rectangle rightMaxLabelBounds  = new Rectangle(0, 0, 0, 0);
    private final Rectangle rightMinLabelBounds  = new Rectangle(0, 0, 0, 0);
    /** Hit-boxes for the time labels at the left / right ends of the horizontal centre line. */
    private final Rectangle timeLeftLabelBounds  = new Rectangle(0, 0, 0, 0);
    private final Rectangle timeRightLabelBounds = new Rectangle(0, 0, 0, 0);

    /** Slider currently being dragged ({@code null} ⇒ no drag in progress). */
    private OscSliderId draggingSlider;

    /** Gap (px) between the cap/s readout and the file-path label drawn to its left. */
    private static final int FILE_PATH_GAP_TO_CAPS = 1;
    /** Gap (px) between the right-channel max-voltage label and the file path,
     *  so the blinking text never collides with that channel readout. */
    private static final int FILE_PATH_GAP_TO_RIGHT_LABEL = 8;

    /**
     * Column right-edge x positions for the measurement table.  The
     * parameter column is 60 % of its original 192 px width (= 115 px);
     * every value column is 90 % of its original 80 px width (= 72 px).
     */
    private static final int COL_NAME_X      = 8;
    private static final int COL_CUR_RIGHT   = 150;
    private static final int COL_WIDTH       = 72;
    private static final int COL_AVG_RIGHT   = COL_CUR_RIGHT + COL_WIDTH;
    private static final int COL_MIN_RIGHT   = COL_AVG_RIGHT + COL_WIDTH;
    private static final int COL_MAX_RIGHT   = COL_MIN_RIGHT + COL_WIDTH;
    private static final int COL_SIGMA_RIGHT = COL_MAX_RIGHT + COL_WIDTH;
    private static final String[] HEADERS       = {"cur", "avg", "min", "max", "σ"};
    private static final int[]    HEADER_RIGHTS = {COL_CUR_RIGHT, COL_AVG_RIGHT, COL_MIN_RIGHT, COL_MAX_RIGHT, COL_SIGMA_RIGHT};

    public ScopeView(Composite parent) {
        // Dark-theme overrides + prefs-driven trace colours, all consumed
        // by AbstractMeasurementView in a single per-role allocation
        // pass.  The mid-channel colours (~65 % attenuation of the trace
        // RGBs) are derived after super() returns since attenuate() is
        // an instance method.
        super(parent, SWT.NONE /*SWT.DOUBLE_BUFFERED*/, Map.of(
                ColorRole.BACKGROUND,        0x000000,
                ColorRole.GRID,              0x3C3C3C,
                // Frame and grid share a shade on scope (different roles
                // semantically, same RGB on the dark theme).
                ColorRole.AXIS,              0x3C3C3C,
                ColorRole.TEXT,              0xF0F0F0,
                ColorRole.BLINK_LIT,         0xF0F0F0,
                ColorRole.BLINK_DIM,         0xA0A0A0,
                ColorRole.DISABLED_CHANNEL,  0x828282,
                ColorRole.LEFT_TRACE,        Preferences.instance().getOscLeftChannelColor(),
                ColorRole.RIGHT_TRACE,       Preferences.instance().getOscRightChannelColor()));
        // Chapter-level help anchor: Ctrl+F1 anywhere on the canvas
        // opens oscilloscope.html.
        setData("helpAnchor", "oscilloscope.html");
        // Self-blinking file-path banner (no canvas redraw); positioned in onPaint.
        fileBanner = new BlinkBanner(this);
        fileBanner.setVisible(false);
        // Derived mid (~65 %) channel colours track the LEFT/RIGHT_TRACE
        // entries — set once here, refreshed by syncChannelColors() on
        // every relevant prefs change.
        syncChannelColors();

        // L/R channel-pick buttons — migrated to ToolButton widgets in a top-left Toolbar.
        // Dim "mid" colour unselected, bright trace colour filled when selected.
        chanButtonFont = Fonts.instance().channel(getDisplay());
        headerBar = new Toolbar(this, BTN_W, BTN_H);
        Preferences prefs = Preferences.instance();
        Channel mc = prefs.getOscMeasurementChannel();
        leftBtn  = headerBar.chanButton("L", color(ColorRole.LEFT_CHANNEL_MID), color(ColorRole.LEFT_CHANNEL_MID),
                color(ColorRole.LEFT_TRACE),  chanButtonFont, I18n.t("scope.stats.left.tooltip"),  mc == Channel.L, "channel");
        rightBtn = headerBar.chanButton("R", color(ColorRole.RIGHT_CHANNEL_MID), color(ColorRole.RIGHT_CHANNEL_MID),
                color(ColorRole.RIGHT_TRACE), chanButtonFont, I18n.t("scope.stats.right.tooltip"), mc == Channel.R, "channel");
        // Channel-colour prefs are observable: rebind so a colour change recolours
        // the traces + L/R buttons immediately, not on the next paint.
        Bindings.onChange(this, prefs.oscLeftChannelColorProperty(),  rgb -> { syncChannelColors(); redraw(); });
        Bindings.onChange(this, prefs.oscRightChannelColorProperty(), rgb -> { syncChannelColors(); redraw(); });
        // Auto-setup (always), then the signal-gated gauge / external / stats / reset.
        headerBar.spacer(2);
        autoSetupBtn = headerBar.pushButton(Icon.ARROWS_TO_CIRCLE_LIT, Icon.ARROWS_TO_CIRCLE_DARK,
                color(ColorRole.TEXT), I18n.t("scope.autosetup.tooltip"));
        dataSpacer = headerBar.spacer(2);
        tableToggleBtn = headerBar.toggleButton(Icon.GAUGE_HIGH_LIT, Icon.GAUGE_HIGH_DARK,
                color(ColorRole.TEXT),
                I18n.t("scope.stats.table.tooltip"), prefs.isOscShowMeasurementTable());
        externalBtn = headerBar.toggleButton(Icon.WINDOW_RESTORE_LIT, Icon.WINDOW_RESTORE_DARK,
                color(ColorRole.TEXT),
                I18n.t("scope.external.window.tooltip"), tableExtracted);
        statsToggleBtn = headerBar.toggleButton(Icon.CHART_LIT, Icon.CHART_DARK,
                color(ColorRole.TEXT),
                I18n.t("scope.stats.toggle.tooltip"), prefs.isOscShowStats());
        resetBtn = headerBar.pushButton(Icon.ROTATE_LEFT_RED, Icon.ROTATE_LEFT_DARK,
                color(ColorRole.RESET), I18n.t("scope.stats.reset.tooltip"));
        Point hbSize = headerBar.computeSize(SWT.DEFAULT, SWT.DEFAULT);
        headerBar.setBounds(COL_NAME_X, 5, hbSize.x, hbSize.y);
        headerBar.layout();
        // GTK ignores SWT.NO_BACKGROUND|TRANSPARENT and paints the theme background,
        // so on the CPU path the overlay toolbar shows an opaque (white/grey) strip
        // over the black scope.  Force the strip and its buttons (INHERIT_FORCE) to
        // the scope background so it blends.  Windows/macOS honour NO_BACKGROUND
        // (transparent), and their GPU path hides the widget entirely — leave them.
        if ("gtk".equals(SWT.getPlatform())) {
            headerBar.setBackground(color(ColorRole.BACKGROUND));
            headerBar.setBackgroundMode(SWT.INHERIT_FORCE);
        }
        // L / R measurement-channel pick (radio pair) — the click writes the
        // now-bound pref (auto-saved); the history-clear + repaint side-effect
        // and the mirror-back onto the ToolButtons live in the pref subscriber
        // below, so a preset load drives both too.  ToolButton isn't an SWT
        // Button, so Bindings.check can't bind it directly — the writer stays
        // explicit, the reaction goes through Bindings.onChange.
        leftBtn.addListener(SWT.Selection, e -> {
            if (leftBtn.isToggled()) prefs.setOscMeasurementChannel(Channel.L);
        });
        rightBtn.addListener(SWT.Selection, e -> {
            if (rightBtn.isToggled()) prefs.setOscMeasurementChannel(Channel.R);
        });
        Bindings.onChange(this, prefs.oscMeasurementChannelProperty(), ch -> {
            leftBtn.setToggled(ch == Channel.L);
            rightBtn.setToggled(ch == Channel.R);
            clearMeasurementHistory();
            redraw();
        });
        autoSetupBtn.addListener(SWT.Selection, e -> MessageBus.instance().publish(Events.SCOPE_AUTO_SETUP));
        tableToggleBtn.addListener(SWT.Selection, e ->
                prefs.setOscShowMeasurementTable(tableToggleBtn.isToggled()));
        Bindings.onChange(this, prefs.oscShowMeasurementTableProperty(), show -> {
            tableToggleBtn.setToggled(show);
            syncScopeButtons();   // external/stats/reset follow the table's visibility
            syncToolWindow();
            redraw();
        });
        externalBtn.addListener(SWT.Selection, e -> {
            if (externalBtn.isToggled() != tableExtracted) {
                setTableExtracted(externalBtn.isToggled());
            }
        });
        statsToggleBtn.addListener(SWT.Selection, e ->
                prefs.setOscShowStats(statsToggleBtn.isToggled()));
        Bindings.onChange(this, prefs.oscShowStatsProperty(), stats -> {
            statsToggleBtn.setToggled(stats);
            syncScopeButtons();   // reset follows the stats toggle
            redraw();
        });
        resetBtn.addListener(SWT.Selection, e -> { clearMeasurementHistory(); redraw(); });
        // ADC re-calibration rescales every measured voltage; DAC re-calibration
        // changes the generated (loopback) level — either makes the accumulated
        // running statistics inconsistent, so clear them on a calibration change.
        Bindings.onChange(this, prefs.adcFsVoltageRmsProperty(), v -> { clearMeasurementHistory(); redraw(); });
        Bindings.onChange(this, prefs.dacFsVoltageAmplProperty(), v -> { clearMeasurementHistory(); redraw(); });
        syncScopeButtons();       // apply the initial signal-gated visibility

        setBackground(color(ColorRole.BACKGROUND));
        addPaintListener(this::onPaint);
        addMouseListener(new MouseAdapter() {
            @Override public void mouseDown(MouseEvent ev)        { pointerDown(ev.x, ev.y, ev.button); }
            @Override public void mouseUp(MouseEvent ev)          { pointerUp(); }
            @Override public void mouseDoubleClick(MouseEvent ev) { pointerDoubleClick(ev.x, ev.y, ev.button); }
        });
        addMouseMoveListener(ev -> pointerMove(ev.x, ev.y));
        // Rect zoom feeds off the pointer funnel above (hookMouse=false), so the
        // GL canvas path is covered too; attachGlInput re-installs on the GL host.
        installRectZoom(this, false);
        addDisposeListener(e -> {
            measWorker.stop();
            disposePalette();
            // monoFont / chanButtonFont are shared instances owned by
            // Fonts — never disposed here.
            // Header icons are cached and owned by IconUtils — disposed
            // when the main shell tears down, not here.
            if (measurementWindow != null) {
                measurementWindow.dispose();
            }
        });
    }

    /** Attaches the latest-window read cursor over the capture (or a wrapped
     *  frozen / file buffer).  {@code null} clears the waveform overlay.  Resets
     *  per-capture state, so use this when a NEW capture / file is swapped in. */
    public void setBuffer(SignalBufferReader reader) {
        setBuffer(reader, true);
    }

    /** Freeze-on-stop variant: swaps in the frozen buffer but KEEPS the last
     *  measurement result, DC means and history, so a later repaint (e.g. a GTK
     *  expose, which Windows/macOS don't issue on a stopped GL surface) still
     *  renders the frozen frame WITH its DC compensation and measurement table,
     *  instead of a wiped, un-compensated trace. */
    public void freezeBuffer(SignalBufferReader reader) {
        setBuffer(reader, false);
    }

    private void setBuffer(SignalBufferReader reader, boolean resetMeasurements) {
        SignalBufferReader prevReader = this.reader;   // the live reader, captured before the swap
        boolean wasFrozen = this.frozen;
        this.reader = reader;
        measWorker.setBuffer(reader);
        syncScopeButtons();   // signal presence changed → re-evaluate the gated buttons
        // Always reset the per-paint trigger / cap-rate state.  On a FREEZE this is
        // what stops the last live trigger from being held and re-anchored as the
        // user pans/zooms the stopped trace (which made it jump left/right); on a
        // new capture it's the clean slate it always was.
        this.captureRate       = 0;
        this.lastNewFrameNanos = 0;
        this.cachedCapsString  = "";
        if (resetMeasurements) {
            // New capture / file load: live again — clear the trigger anchor, the
            // frozen hold, and the previous session's measurements (the DC mean is
            // read from the worker until it publishes ~100 ms in).
            this.frozen            = false;
            this.singleHeld        = false;
            this.lastTriggerAbsPos = -1;
            measWorker.clearLatest();
            measWorker.clearHistory();
            this.cachedMeasurementRows = null;
        } else {
            // Freeze on stop: render the frozen buffer through the SAME live path so
            // pan/zoom behave identically to live.  KEEP the trigger anchor (the
            // hold, step 2, re-pins the window on it); clear singleHeld so a stopped
            // scope uses that hold for EVERY mode, not the separate held-frame path.
            // Measurements stay (table + DC-compensated trace).
            this.frozen     = true;
            this.singleHeld = false;
            // The frozen snapshot is a FRESH buffer whose writePos is just its own
            // sample count (≈ capacity), while the kept anchors are in the LIVE
            // buffer's absolute coordinates.  Once the live ring has wrapped (live
            // writePos > capacity) the two diverge by (liveWritePos − snapshotLen) —
            // without this the anchor lands far off the snapshot and the whole trace
            // blanks.  Shift the kept live anchors into the snapshot's coordinates.
            if (!wasFrozen && prevReader != null) {
                long rebase = prevReader.getWritePos() - reader.getWritePos();
                if (rebase > 0) {
                    if (lastTriggerAbsPos    >= 0) lastTriggerAbsPos    -= rebase;
                    if (lastLiveAnchorAbs    >= 0) lastLiveAnchorAbs    -= rebase;
                    if (lastLiveDispStartAbs >= 0) lastLiveDispStartAbs -= rebase;
                }
            }
            // A free-running AUTO trace never set a trigger anchor; fall back to the
            // last live window's trigger-offset point so the stopped scope still has
            // a single fixed reference for hold / zoom / pan (panFrozenHorizontal).
            if (this.lastTriggerAbsPos < 0 && this.lastLiveAnchorAbs >= 0) {
                this.lastTriggerAbsPos        = this.lastLiveAnchorAbs;
                this.lastTriggerSubSampleOffset = 0.0;
            }
        }
    }

    /** Pans a STOPPED (frozen) trace horizontally by one wheel tick = ½ division, by
     *  nudging the (virtual-capable) trigger-position fraction.  The offset moves FREELY
     *  (it may go virtual — the handle pins to the edge, the mark shows the real value);
     *  the RENDER clamps the view to the buffer so the signal never blanks (it parks at
     *  the true buffer edge).  {@code dir} matches the wheel sign.  Returns whether it
     *  moved, so the caller only repaints on a real change. */
    public boolean panFrozenOffset(int dir) {
        if (!frozen || reader == null || lastTriggerAbsPos < 0) return false;
        Preferences p = Preferences.instance();
        double cur  = p.getOscTriggerPositionFrac();
        double next = nav.moveTriggerOffset(cur, dir);
        if (next == cur) return false;
        p.setOscTriggerPositionFrac(next);
        p.save();
        return true;
    }

    /** Pans a LIVE trace horizontally by one wheel tick = ½ division, nudging the
     *  (virtual-capable) trigger offset FREELY like {@link #panFrozenOffset}: trace and
     *  trigger travel together, the trigger may leave the view (handle pins, mark shows
     *  the real value), and the far buffer edge just blanks.  An AUTO free-run view has
     *  no trigger and is right-anchored on the newest, so this has no effect there (the
     *  offset is ignored) — trigger it or stop to scroll.  Returns whether it moved. */
    public boolean panLiveOffset(int dir) {
        if (frozen || reader == null) return false;
        Preferences p = Preferences.instance();
        double cur  = p.getOscTriggerPositionFrac();
        double next = nav.moveTriggerOffset(cur, dir);
        if (next == cur) return false;
        p.setOscTriggerPositionFrac(next);
        p.save();
        return true;
    }

    /** Moves both active channels' vertical offset by one wheel tick (½ div), locked
     *  together and clamped at ±FS/2-at-middle (the {@link ScopeNav#moveVertical} engine).
     *  Wheel up ({@code dir} > 0) moves the signal up.  Returns whether it moved so the
     *  caller (pane) only re-syncs its slider + repaints on a real change. */
    public boolean moveVerticalOffset(int dir) {
        Preferences p = Preferences.instance();
        double peak = p.getAdcFsVoltageRms() * Math.sqrt(2.0);
        double oldL = p.getOscLeftOffsetFrac();
        double oldR = p.getOscRightOffsetFrac();
        double[] off = nav.moveVertical(
                oldL, p.getOscLeftVoltsPerDiv(),  p.isOscLeftChannelEnabled(),
                oldR, p.getOscRightVoltsPerDiv(), p.isOscRightChannelEnabled(),
                dir, peak);
        if (off[0] == oldL && off[1] == oldR) return false;
        p.setOscLeftOffsetFrac(off[0]);
        p.setOscRightOffsetFrac(off[1]);
        p.save();
        return true;
    }

    /** Sets the vertical offset from the pane's vertical-scrollbar thumb fraction
     *  {@code sliderFrac} (0 = bottom of the offset range, 1 = top), mapped through
     *  {@link #offsetFracBounds()} and applied as a delta so both channels move together
     *  (the measurement channel is the reference).  Returns whether it moved. */
    public boolean setVerticalOffsetFromSliderFraction(double sliderFrac) {
        Preferences p = Preferences.instance();
        double[] bounds = offsetFracBounds();
        double frac = bounds[0] + sliderFrac * (bounds[1] - bounds[0]);
        Channel ref = p.getOscMeasurementChannel();
        double prevRef = (ref == Channel.L) ? p.getOscLeftOffsetFrac() : p.getOscRightOffsetFrac();
        if (prevRef == frac) return false;
        double delta = frac - prevRef;
        // No clamp — offsetFrac may run past [0, 1] so the user can place ±FS at grid
        // centre; the render honours the extended range.
        p.setOscLeftOffsetFrac (p.getOscLeftOffsetFrac()  + delta);
        p.setOscRightOffsetFrac(p.getOscRightOffsetFrac() + delta);
        p.save();
        return true;
    }

    /**
     * Returns the most recent Vrms (volts) published by the measurement
     * worker, or {@code null} if no measurement has been computed yet
     * (capture not running or still in the AC warmup window).  Read by the
     * ADC calibration dialog to compute a scale factor from the entered
     * actual amplitude.
     */
    public Double getLastVrms() {
        SignalMeasurements m = measWorker.getLastMeasResult(measChannelIsLeft());
        return (m == null) ? null : m.getVrms();
    }

    /** True when the measurement table's L/R selector points at the left
     *  channel — the worker measures both; the view shows this one. */
    private boolean measChannelIsLeft() {
        return Preferences.instance().getOscMeasurementChannel() == Channel.L;
    }

    /**
     * Returns the latest detected fundamental frequency in Hz, or
     * {@code NaN} if no measurement is available yet (capture not
     * started, signal too short, or period undetectable).  Used by
     * the scope's Save-to flow to truncate the saved file to an
     * integer number of signal periods so the file loops cleanly.
     */
    public double getLastFrequencyHz() {
        SignalMeasurements m = measWorker.getLastMeasResult(measChannelIsLeft());
        return (m == null) ? Double.NaN : m.getFrequency();
    }

    /**
     * Returns the latest peak-to-peak voltage of the measurement
     * channel (in volts), or {@code NaN} if no measurement is yet
     * available.  Used by the pane to gate the calibrate button on
     * "signal occupies at least 25 % of the ADC full-scale p-p" — too
     * small a signal would yield a noisy calibration result.
     */
    public double getLastVpp() {
        SignalMeasurements m = measWorker.getLastMeasResult(measChannelIsLeft());
        return (m == null) ? Double.NaN : m.getVpp();
    }

    /**
     * Vpp (volts) that auto-setup should scale the given channel's vertical to:
     * the RESIDUAL Vpp recorded at the last paint when that channel's residual
     * pref is on and the value is finite, otherwise the captured {@link
     * #getLastVpp}.  Lets a channel showing its residual be scaled to fill the
     * screen off the (small) residual amplitude instead of the (large) tone.
     */
    public double autoSetupVpp(boolean leftChannel) {
        Preferences prefs = Preferences.instance();
        boolean on   = leftChannel ? prefs.isOscLeftResidualEnabled() : prefs.isOscRightResidualEnabled();
        double  vpp  = leftChannel ? lastResidualVppL : lastResidualVppR;
        return (on && Double.isFinite(vpp)) ? vpp : getLastVpp();
    }

    /**
     * Copies the latest measurement snapshot and sliding-window history from
     * {@code source} into this view.  Used by the screenshot renderer so a
     * passive (worker-less) OscilloscopePane instance still draws the
     * measurement table with the live values — without this the screenshot
     * view's {@link #lastMeasResult} stays {@code null} and
     * {@link #drawMeasurements} bails out early.
     */
    public void copyMeasurementsFrom(ScopeView source) {
        if (source == null || source == this) return;
        this.measWorker.snapshotFrom(source.measWorker);
    }

    /**
     * (Re-)builds the channel colour resources from the user-configured
     * preferences.  No-op when the packed RGBs match the currently cached
     * ones, so it's cheap to call on every paint.  The dim variants are a
     * fixed (~¼ brightness) attenuation of the channel colour and are used
     * for the unselected L/R buttons in the measurement-table header.
     */
    private void syncChannelColors() {
        Preferences prefs = Preferences.instance();
        int newL = prefs.getOscLeftChannelColor();
        int newR = prefs.getOscRightChannelColor();
        if (newL != currentLeftRgb) {
            setColor(ColorRole.LEFT_TRACE,        newL);
            setColor(ColorRole.LEFT_CHANNEL_MID,  attenuate(newL, 0.65));
            setColor(ColorRole.LEFT_BEAT,         attenuate(newL, 0.45));
            currentLeftRgb = newL;
            recolorChannelButton(leftBtn, "L", ColorRole.LEFT_CHANNEL_MID, ColorRole.LEFT_TRACE);
        }
        if (newR != currentRightRgb) {
            setColor(ColorRole.RIGHT_TRACE,       newR);
            setColor(ColorRole.RIGHT_CHANNEL_MID, attenuate(newR, 0.65));
            setColor(ColorRole.RIGHT_BEAT,        attenuate(newR, 0.45));
            currentRightRgb = newR;
            recolorChannelButton(rightBtn, "R", ColorRole.RIGHT_CHANNEL_MID, ColorRole.RIGHT_TRACE);
        }
    }

    /**
     * Re-points a channel header button at the freshly-allocated palette
     * colours after {@link #syncChannelColors} disposed the previous ones.
     * Without this the button keeps a reference to a now-disposed
     * {@link Color} and its next paint throws "Graphic is disposed".
     */
    private void recolorChannelButton(ToolButton btn, String label, ColorRole midRole, ColorRole traceRole) {
        if (btn == null) return;
        Color mid = color(midRole);
        btn.setLabel(label, mid);                 // content (label) colour
        btn.setColors(mid, color(traceRole));     // frame (idle) + fill (active)
    }

    /**
     * Arms (or cancels) a single-shot capture in SINGLE trigger mode.  While
     * armed the display stays frozen on the held frame; the next trigger captures
     * a fresh frame, disarms, and publishes {@link Events#SCOPE_SINGLE_DISARMED}
     * so the Start toggle pops back out.  No effect in AUTO / NORMAL modes (those
     * never read {@code singleArmed}).
     */
    public void setSingleArmed(boolean armed) {
        singleArmed = armed;
        if (armed) {
            // Wait for a FRESH trigger — don't anchor on the previous one.
            lastTriggerAbsPos = -1;
        }
    }

    /** Drops the held trigger anchor and captured frame — called when the trigger
     *  SOURCE changes (type / edge / channel) or the generated signal changes: the
     *  old anchor belongs to the old trigger/signal and would keep re-rendering a
     *  stale trace (and re-stamp it into the persistence afterglow on the next
     *  interactive render).  NORMAL / SINGLE then stay blank until the new trigger
     *  fires.  Also restarts the glitch-mode cap/s collection — events caught under
     *  the old trigger/signal (e.g. the generator transition itself, which IS a
     *  discontinuity) don't belong in the new count. */
    public void resetTriggerHold() {
        lastTriggerAbsPos = -1;
        singleHeld = false;
        glitchCountStartNanos = 0;
    }

    private void onPaint(PaintEvent e) {
        Rectangle area = getClientArea();
        int w = area.width;
        int h = area.height;
        if (w <= 0 || h <= 0) return;
        paintCanvas(e.gc, w, h);
        updateCaptureRate();
    }

    /**
     * Renders one full frame of the scope canvas (background, grid, waveforms,
     * measurement table, cap/s readout) to {@code gc} at the requested
     * {@code (w, h)} resolution.  Decoupled from {@link #onPaint} so the
     * screenshot dialog can re-render the same view to an off-screen
     * {@link Image} at an arbitrary size — that's how we keep the trace
     * pixel-accurate when the user picks a screenshot resolution different
     * from the on-screen pane.
     */
    public void paintCanvas(GC gc, int w, int h) {
        paintCanvas(new GcMeasurementPainter(gc), w, h);
    }

    /** The real paint, against the {@link MeasurementPainter} seam so the same code
     *  drives the GC backend (on-screen + screenshots) and a NanoVG backend (GPU). */
    public void paintCanvas(MeasurementPainter gc, int w, int h) {
        long _t0 = System.nanoTime();
        gc.setAdvanced(true);
        syncChannelColors();
        gc.setBackground(color(ColorRole.BACKGROUND));
        gc.fillRectangle(0, 0, w, h);
        // Default readout font each frame — the NanoVG painter keeps font state across
        // frames (a GC resets per paint), so without this a readout that doesn't set its
        // own font would inherit the last one used (e.g. the bold channel-button font).
        if (monoFont == null) monoFont = Fonts.instance().normal(getDisplay());
        gc.setFont(monoFont);
        long _tSetup = System.nanoTime();
        drawScopeGrid(gc, w, h);
        long _tGrid = System.nanoTime();
        drawWaveforms(gc, w, h);
        long _tWave = System.nanoTime();
        drawSliders(gc, w, h);
        long _tSlid = System.nanoTime();
        drawEdgeLabels(gc, w, h);
        long _tEdge = System.nanoTime();
        drawMeasurements(gc);
        long _tMeas = System.nanoTime();
        drawCaptureRate(gc, w);
        drawFilePath(gc, w);
        drawRectZoomOverlay(gc, w, h);
        // TEMP paint profiling — once per ~30 paints, log the per-section ms so we
        // can see where the frame time goes.  Remove once the hot section is fixed.
        long _tEnd = System.nanoTime();
        if (++paintProfileTick >= 30 && log.isWarnEnabled()) {
            paintProfileTick = 0;
            log.warn(String.format(
                "PAINT-PROFILE ms: setup=%.1f grid=%.1f wave=%.1f sliders=%.1f edge=%.1f meas=%.1f caps+path=%.1f TOTAL=%.1f",
                (_tSetup - _t0) / 1e6, (_tGrid - _tSetup) / 1e6, (_tWave - _tGrid) / 1e6,
                (_tSlid - _tWave) / 1e6, (_tEdge - _tSlid) / 1e6, (_tMeas - _tEdge) / 1e6,
                (_tEnd - _tMeas) / 1e6, (_tEnd - _t0) / 1e6));
        }
    }

    /** Renders the scope through a {@link MeasurementPainter} for the GPU surface,
     *  mirroring {@link #onPaint} (paint + capture-rate update) — used as the GL
     *  surface's renderer when the GPU scope is enabled.  Split into
     *  {@link GlScopeRenderer.Phase phases} so the persistence surface can route the
     *  TRACE into a decayed phosphor buffer and draw the BACKDROP + OVERLAY fresh;
     *  surfaces without persistence pass {@link GlScopeRenderer.Phase#ALL}. */
    @Override
    public void renderGl(MeasurementPainter painter, int w, int h, GlScopeRenderer.Phase phase) {
        switch (phase) {
            case BACKDROP -> glBackdrop(painter, w, h);
            case TRACE    -> glTrace(painter, w, h);
            case OVERLAY  -> glOverlay(painter, w, h);
            case ALL      -> { glBackdrop(painter, w, h); glTrace(painter, w, h); glOverlay(painter, w, h); }
        }
    }

    /** Backdrop phase: per-frame painter setup + background + graticule (never persists). */
    private void glBackdrop(MeasurementPainter gc, int w, int h) {
        gc.setAdvanced(true);
        syncChannelColors();
        gc.setBackground(color(ColorRole.BACKGROUND));
        gc.fillRectangle(0, 0, w, h);
        if (monoFont == null) monoFont = Fonts.instance().normal(getDisplay());
        gc.setFont(monoFont);
        drawScopeGrid(gc, w, h);
    }

    /** Trace phase: the waveforms only — the layer the phosphor buffer accumulates.
     *  The cap/s computation lives here (not in the always-run overlay) so expose-only
     *  re-composites — which skip the trace — don't feed stale frames into the rate. */
    private void glTrace(MeasurementPainter gc, int w, int h) {
        gc.setAdvanced(true);
        drawWaveforms(gc, w, h);
        updateCaptureRate();
    }

    /** Overlay phase: sliders, labels, measurement table, header — drawn fresh on top.
     *  Also runs the per-frame side effects (extracted measurement window + cap/s rate). */
    private void glOverlay(MeasurementPainter gc, int w, int h) {
        gc.setAdvanced(true);
        if (monoFont == null) monoFont = Fonts.instance().normal(getDisplay());
        gc.setFont(monoFont);
        drawSliders(gc, w, h);
        drawEdgeLabels(gc, w, h);
        drawMeasurements(gc);
        drawCaptureRate(gc, w);
        drawFilePath(gc, w);
        drawHeaderOverlay(gc);   // GPU only: the toolbar widgets are hidden, draw them via the painter
        drawRectZoomOverlay(gc, w, h);
        // The GPU loop renders through the surface, not redraw(), so the redraw() override
        // that drives the extracted measurement window never fires — do it directly here.
        if (measurementWindow != null) measurementWindow.redraw();
    }

    /** Whether the most recent {@link #drawWaveforms} rendered genuinely new captured
     *  content (vs a held / re-composited frame) — the phosphor surface consults this to
     *  decide whether to decay+accumulate or merely re-composite. */
    @Override
    public boolean isLastFrameNew() { return lastFrameWasNew; }

    /** GPU path only: the header {@link Toolbar} is a hidden child of this view, so
     *  render it into an off-screen image and draw it over the GL trace as a texture
     *  — an SWT control can't overlay a GLCanvas (its buffer swap paints over it). */
    private void drawHeaderOverlay(MeasurementPainter painter) {
        for (Control c : headerBar.getChildren()) {
            if (c instanceof ToolButton btn && btn.getVisible()) {   // honour signal-gated hiding
                Rectangle b = btn.getBounds();
                btn.paintWith(painter, COL_NAME_X + b.x, 5 + b.y);
            }
        }
    }

    /** The visible header {@link ToolButton} under ({@code x}, {@code y}) in the GPU
     *  path (where the buttons are GL-drawn, not real widgets), or null. */
    private ToolButton headerButtonAt(int x, int y) {
        for (Control c : headerBar.getChildren()) {
            if (c instanceof ToolButton btn && btn.getVisible()) {
                Rectangle b = btn.getBounds();
                if (x >= COL_NAME_X + b.x && x < COL_NAME_X + b.x + b.width
                        && y >= 5 + b.y && y < 5 + b.y + b.height) {
                    return btn;
                }
            }
        }
        return null;
    }

    /**
     * Draws the scope's fixed graticule directly, rather than through the shared
     * {@link #drawGrid} in the base view.  That base method is a general-purpose
     * chart grid: it takes arbitrary {@link AxisSpec}s (linear / log / nice
     * scales), optional axis labels, a second Y axis, and edge tick marks, and to
     * serve all that it allocates major/minor tick arrays and maps every line
     * through {@code valueToX} / {@code valueToY} (with per-call scale branching)
     * on each paint.  The oscilloscope needs none of it — a fixed, evenly spaced
     * {@code DIVISIONS_X × DIVISIONS_Y} grid with a centred cross-hair and uniform
     * sub-ticks — so re-using the configurable method spends its setup cost every
     * realtime frame for nothing.  This dedicated version computes the line
     * positions arithmetically, with no allocation and no scale lookup, keeping
     * the hot paint path lean.  It is pixel-for-pixel identical to the
     * {@code drawGrid(...)} call it replaced (same rounding: {@code round(i·w/N)}).
     */
    private void drawScopeGrid(MeasurementPainter gc, int w, int h) {
        // Major grid: DIVISIONS_X+1 vertical and DIVISIONS_Y+1 horizontal lines,
        // evenly spaced edge to edge.
        gc.setForeground(color(ColorRole.GRID));
        for (int i = 0; i <= DIVISIONS_X; i++) {
            int px = (int) Math.round((double) i * w / DIVISIONS_X);
            gc.drawLine(px, 0, px, h);
        }
        for (int i = 0; i <= DIVISIONS_Y; i++) {
            int py = (int) Math.round((double) i * h / DIVISIONS_Y);
            gc.drawLine(0, py, w, py);
        }
        // Centred cross-hair with uniform sub-division ticks along each arm.
        int cx = (int) Math.round(0.5 * w);
        int cy = (int) Math.round(0.5 * h);
        gc.setForeground(color(ColorRole.CROSSHAIR));
        gc.drawLine(cx, 0, cx, h);
        gc.drawLine(0, cy, w, cy);
        if (TICKS_PER_DIV > 0) {
            int totalX = DIVISIONS_X * TICKS_PER_DIV;
            int totalY = DIVISIONS_Y * TICKS_PER_DIV;
            double tickX = (double) w / totalX;
            double tickY = (double) h / totalY;
            int total = Math.max(totalX, totalY);
            for (int i = 0; i <= total; i++) {
                if (i <= totalY) {
                    int py = (int) Math.round(i * tickY);
                    gc.drawLine(cx - TICK_HALF_LEN, py, cx + TICK_HALF_LEN, py);
                }
                if (i <= totalX) {
                    int px = (int) Math.round(i * tickX);
                    gc.drawLine(px, cy - TICK_HALF_LEN, px, cy + TICK_HALF_LEN);
                }
            }
        }
        // Plot frame.
        gc.setForeground(color(ColorRole.AXIS));
        gc.drawRectangle(0, 0, w, h);
    }

    /**
     * Renders the scope view to a new off-screen {@link Image} at the given
     * dimensions.  Caller is responsible for disposing the returned image.
     */
    public Image renderToImage(Display display, int w, int h) {
        Image img = new Image(display, w, h);
        GC gc = new GC(img);
        try {
            paintCanvas(gc, w, h);
        } finally {
            gc.dispose();
        }
        return img;
    }

    /**
     * Updates the cap/s readout based on whether {@link #drawWaveforms}
     * rendered a new frame this paint (fresh trigger event or AUTO free-run
     * latest samples) or just re-rendered the same frozen content
     * (NORMAL with no new trigger, SINGLE held, blank pane).  EMA-smoothed
     * on new frames; decays toward zero on frozen frames so a no-trigger
     * NORMAL pane reads ~0 cap/s instead of the paint rate.
     */
    private void updateCaptureRate() {
        if (frozen) {
            rateSawFrozen = true;   // stopped: freeze the readout at its last value
            return;
        }
        long now = System.nanoTime();
        boolean glitchMode = Preferences.instance().getOscTriggerType() == TriggerType.GLITCH;
        if (glitchMode) {
            // Glitch rate = caught count ÷ collection time.  Events are seconds to
            // minutes apart, so a per-interval EMA would just decay toward 0 between
            // catches; the cumulative rate is the honest figure.
            if (!rateWasGlitchMode || rateSawFrozen || glitchCountStartNanos == 0) {
                glitchCountStartNanos = now;
                glitchCount = 0;
            }
            if (lastFrameWasNew) {
                glitchCount++;
                lastNewFrameNanos = now;
            }
            double elapsed = (now - glitchCountStartNanos) * 1e-9;
            captureRate = (elapsed >= GLITCH_RATE_MIN_SECONDS) ? glitchCount / elapsed : 0.0;
            rateWasGlitchMode = true;
            rateSawFrozen = false;
            return;
        }
        rateWasGlitchMode = false;
        rateSawFrozen = false;
        if (lastFrameWasNew) {
            if (lastNewFrameNanos > 0) {
                double dt = (now - lastNewFrameNanos) * 1e-9;
                if (dt > 0.001 && dt < 1.0) {
                    double instant = 1.0 / dt;
                    captureRate = (captureRate <= 0) ? instant : captureRate * 0.9 + instant * 0.1;
                } else if (dt >= 1.0) {
                    captureRate = 1.0 / dt;
                }
            }
            lastNewFrameNanos = now;
        } else if (lastNewFrameNanos > 0) {
            // No new frame this paint — clamp the displayed rate to the
            // instantaneous "since last new frame" rate so the readout
            // visibly decays toward 0 instead of stalling at the last
            // captured EMA value.
            double age = (now - lastNewFrameNanos) * 1e-9;
            if (age > 0.5) {
                double instant = 1.0 / age;
                if (instant < captureRate) captureRate = instant;
            }
        } else {
            // Never had a new frame — make sure the readout reads 0.
            captureRate = 0;
        }
    }

    /** Pale-grey "123.4 cap/s" readout in the top-right corner. */
    private void drawCaptureRate(MeasurementPainter gc, int w) {
        // Visible for the whole live record — even reading 0.0.  In glitch mode a
        // new frame only arrives per glitch, so this readout IS the glitches/s
        // counter and must not vanish between events.  A STOPPED scope keeps
        // showing the last live value; hidden only in file mode (no capture) and
        // before the first capture ever ran.
        if (reader == null || fileMode) return;
        if (frozen && lastNewFrameNanos <= 0) return;
        gc.setAntialias(SWT.OFF);
        gc.setTextAntialias(SWT.ON);
        gc.setForeground(color(ColorRole.TEXT));
        gc.setBackground(color(ColorRole.BACKGROUND));
        // Throttle the String.format call to ~5 Hz; drawText still runs every
        // paint so the readout never blanks.
        long now = System.nanoTime();
        if (cachedCapsString.isEmpty() || now - lastCapsBuildNanos >= READOUT_THROTTLE_NS) {
            // 3 decimals: in glitch mode the rate is glitches/s and a rare event
            // (one per minutes) reads e.g. 0.008 — one decimal would show 0.0.
            cachedCapsString   = String.format("%.3f cap/s", captureRate);
            lastCapsBuildNanos = now;
        }
        Point ts = gc.textExtent(cachedCapsString);
        drawOutlinedText(gc, cachedCapsString, w - ts.x - 8, 6);
    }

    /**
     * Top-right blinking file-path label, shown only when in file mode.
     * Positioned 20 px to the left of the cap/s readout, on the same row,
     * and left-truncated with a "…" prefix so the file name end stays
     * visible.  The left edge is clamped past the right-channel max-voltage
     * label (near the vertical centre line) to avoid overlap.  Blinks
     * between {@code #FFFFFF} and {@code #AAAAAA} every 500 ms via wall-
     * clock phase, with a 500 ms timer-driven redraw to keep the toggle
     * visible.
     */
    private void drawFilePath(MeasurementPainter gc, int w) {
        String path = filePath;
        if (path == null || !fileMode) { hideFileBanner(); return; }

        // Right edge: cap/s left edge minus the configured gap.  If cap/s
        // isn't yet measured (still 0 cap/s but file mode active), pretend
        // its width is 0 so we still get a stable right edge.
        Point capsExtent = cachedCapsString.isEmpty() ? new Point(0, 0)
                                                       : gc.textExtent(cachedCapsString);
        int capsLeft = w - capsExtent.x - 8;
        int rightEdge = capsLeft - FILE_PATH_GAP_TO_CAPS;

        // Left edge: just past the right-channel max-voltage label (if any).
        Preferences prefs = Preferences.instance();
        int centerX = w / 2;
        int leftEdge = centerX + 4;
        if (prefs.isOscRightChannelEnabled()) {
            double rightVDiv = prefs.getOscRightVoltsPerDiv();
            double off       = prefs.getOscRightOffsetFrac();
            double maxV      = off * DIVISIONS_Y * rightVDiv;
            // Use the same monoFont drawEdgeLabels does so the bound matches
            // the actual rendered label width.
            Font prev = gc.getFont();
            if (monoFont == null) monoFont = Fonts.instance().normal(getDisplay());
            gc.setFont(monoFont);
            int rightLabelWidth = gc.textExtent(ScopeFormat.formatVolts(maxV, rightVDiv)).x;
            gc.setFont(prev);
            leftEdge = centerX + 4 + rightLabelWidth + FILE_PATH_GAP_TO_RIGHT_LABEL;
        }

        int avail = rightEdge - leftEdge;
        if (avail <= 0) { hideFileBanner(); return; }   // canvas too narrow → drop the label

        // Hand the path to the self-blinking widget: it right-aligns + left-ellipsises
        // to its width (the BACKGROUND halo keeps it readable over the trace) and blinks
        // itself — so the canvas no longer repaints twice a second just for the toggle.
        // Size it to the text (right-anchored at rightEdge, never left of leftEdge) so
        // the transparent widget doesn't capture hovers over the trace beside it.
        fileBanner.setFont(getFont());
        fileBanner.setText(path);
        fileBanner.setColors(color(ColorRole.BLINK_LIT), color(ColorRole.BLINK_DIM),
                             color(ColorRole.BACKGROUND));
        fileBanner.alignRight(leftEdge, w - rightEdge, 5, gc.textExtent("X").y + 2);
        fileBanner.setVisible(true);
    }

    /** Hides the file-path banner. */
    private void hideFileBanner() {
        if (fileBanner != null && !fileBanner.isDisposed()) {
            fileBanner.setVisible(false);
        }
    }

    /**
     * Pale-grey measurement table (Vpp / Vrms / Vmean / Tp / f / Duty) in
     * the top-left.  Reads from the live ring buffer so values stay accurate
     * even when a SINGLE held frame is on screen, and uses a measurement
     * window long enough to contain ≥ 1 cycle of any audio-band signal.
     * Avg / min / max / σ are aggregated across a sliding window of the
     * last {@code oscMeasurementAverageSeconds} of measurements.
     */
    private void drawMeasurements(MeasurementPainter gc) {
        // When extracted, the table is rendered into the measurementWindow instead —
        // skip the in-canvas table here.
        if (tableExtracted) return;

        Preferences prefs = Preferences.instance();
        boolean showL = prefs.isOscLeftChannelEnabled();
        boolean showR = prefs.isOscRightChannelEnabled();
        Channel selected = prefs.getOscMeasurementChannel();

        // L / R channel-pick buttons stay visible unconditionally so the
        // measurement channel — and therefore the vertical-offset slider
        // channel — can still be switched when one or both channels are
        // off and even when no live data is available yet.  The gauge,
        // stats toggle, reset, and external-window buttons only appear
        // once a signal is present (recorded or loaded).
        SignalBufferReader b = reader;
        boolean hasSignal = (b != null);

        if (!hasSignal) return;
        if (!prefs.isOscShowMeasurementTable()) return;
        if (!showL && !showR) return;

        MeasurementRow[] rows = prepareMeasurementRows(prefs);
        if (rows == null) return;
        selected = prefs.getOscMeasurementChannel();
        // Main-view call site: the button row sits at y ∈ [5, 27), so the
        // table starts one line below.
        drawMeasurementTableAt(gc, rows, showL, showR, selected, 5 + 22 + 2);
    }

    /** Computes (or returns the throttled cache of) the measurement table's
     *  rows.  Returns {@code null} when there's no signal yet, no live
     *  measurement snapshot, or both channels are disabled.  Side-effect:
     *  auto-flips the selected measurement channel if its source has been
     *  switched off, matching the previous inline logic. */
    private MeasurementRow[] prepareMeasurementRows(Preferences prefs) {
        boolean showL = prefs.isOscLeftChannelEnabled();
        boolean showR = prefs.isOscRightChannelEnabled();
        if (!showL && !showR) return null;
        Channel selected = prefs.getOscMeasurementChannel();
        // No history clear on the auto-switch: the worker keeps BOTH channels'
        // rings, so the newly selected channel's stats are already collected.
        if (selected == Channel.L && !showL && showR) {
            prefs.setOscMeasurementChannel(Channel.R);
            prefs.save();
        } else if (selected == Channel.R && !showR && showL) {
            prefs.setOscMeasurementChannel(Channel.L);
            prefs.save();
        }
        SignalMeasurements cur = measWorker.getLastMeasResult(measChannelIsLeft());
        if (cur == null) return null;
        MeasurementRow[] rows = cachedMeasurementRows;
        // Rebuild when the worker posts a NEW result (each of its compute passes),
        // not on a display timer — the timer's period beat against the capture
        // block arrivals, making the readout update irregularly.
        if (rows == null || cur != lastBuiltMeasResult) {
            long now = System.nanoTime();
            long windowNanos = (long) (prefs.getOscMeasurementAverageSeconds() * 1e9);
            long cutoff = now - windowNanos;
            MeasurementStats vppS   = new MeasurementStats();
            MeasurementStats vRmsS  = new MeasurementStats();
            MeasurementStats vMeanS = new MeasurementStats();
            MeasurementStats tpS    = new MeasurementStats();
            MeasurementStats trS    = new MeasurementStats();
            MeasurementStats tfS    = new MeasurementStats();
            MeasurementStats fS     = new MeasurementStats();
            MeasurementStats dutyS  = new MeasurementStats();
            measWorker.walkRecentHistory(measChannelIsLeft(), cutoff, s -> {
                vppS  .add(s.getVpp());
                vRmsS .add(s.getVrms());
                vMeanS.add(s.getVmean());
                tpS   .add(s.getPeriod());
                trS   .add(s.getRiseTime());
                tfS   .add(s.getFallTime());
                fS    .add(s.getFrequency());
                dutyS .add(s.getDutyCycle());
            });
            rows = new MeasurementRow[] {
                    MeasurementRow.forVolts("Vpp",   cur.getVpp(),       vppS),
                    MeasurementRow.forVolts("Vrms",  cur.getVrms(),      vRmsS),
                    MeasurementRow.forVolts("Vmean", cur.getVmean(),     vMeanS),
                    MeasurementRow.forTime ("Tp",    cur.getPeriod(),    tpS),
                    MeasurementRow.forTime ("Tr",    cur.getRiseTime(),  trS),
                    MeasurementRow.forTime ("Tf",    cur.getFallTime(),  tfS),
                    MeasurementRow.forFreq ("f",     cur.getFrequency(), fS),
                    MeasurementRow.forPct  ("Duty",  cur.getDutyCycle(), dutyS),
            };
            cachedMeasurementRows = rows;
            lastBuiltMeasResult   = cur;
        }
        return rows;
    }

    /** Clears the worker's measurement history AND the view's cached table so a
     *  Reset (or a channel / calibration change) wipes the on-screen avg / min /
     *  max immediately, instead of leaving the stale cached rows up until the
     *  next throttled rebuild. */
    private void clearMeasurementHistory() {
        measWorker.clearHistory();
        cachedMeasurementRows = null;
        lastBuiltMeasResult   = null;
    }

    /** Resets the running measurement statistics (avg / min / max / σ) and repaints
     *  — called when the generator signal changes (USER_INPUT) so the table doesn't
     *  average the old signal's readings into the new one. */
    public void resetMeasurementHistory() {
        if (isDisposed()) return;
        clearMeasurementHistory();
        redraw();
    }

    /** Records the user's intent to keep the table extracted into the tool
     *  window.  The shell's actual open/closed state is derived from this
     *  flag AND {@code oscShowMeasurementTable} via {@link #syncToolWindow},
     *  so hiding the measurement table with the gauge button closes the
     *  shell without forgetting the extracted intent — re-enabling the
     *  measurement table then brings the shell back automatically. */
    private void setTableExtracted(boolean extracted) {
        if (extracted == tableExtracted) return;
        tableExtracted = extracted;
        externalBtn.setToggled(extracted);
        syncScopeButtons();   // stats/reset depend on !tableExtracted
        syncToolWindow();
        redraw();
    }

    /** Shows/hides the signal-gated header buttons (gauge / external / stats / reset and
     *  the gap before them) per the cascade — signal present → table shown → not
     *  extracted → stats on — then re-flows the toolbar.  Driven by {@link #setBuffer}
     *  and the toggle listeners, never by paint. */
    private void syncScopeButtons() {
        Preferences p = Preferences.instance();
        boolean signal    = (reader != null);
        boolean showTable = p.isOscShowMeasurementTable();
        boolean tableVis = signal;
        boolean extVis   = signal && showTable;
        boolean statsVis = extVis && !tableExtracted;
        boolean resetVis = statsVis && p.isOscShowStats();
        dataSpacer.setExcluded(!tableVis);
        tableToggleBtn.setExcluded(!tableVis);
        externalBtn.setExcluded(!extVis);
        statsToggleBtn.setExcluded(!statsVis);
        resetBtn.setExcluded(!resetVis);
        headerBar.reflow();
    }

    /** Brings the {@link #measurementWindow} into sync with the current
     *  {@code tableExtracted} ∧ {@code oscShowMeasurementTable} state:
     *  creates + opens it when both are true, disposes it otherwise. */
    private void syncToolWindow() {
        boolean shouldBeOpen = tableExtracted
                && Preferences.instance().isOscShowMeasurementTable();
        if (shouldBeOpen && measurementWindow == null) {
            createMeasurementWindow();
        } else if (!shouldBeOpen && measurementWindow != null) {
            measurementWindow.dispose();
            measurementWindow = null;
        }
    }

    /** Creates the measurements tool window: pushes its colours + the stats-toggle / reset
     *  button row (each signalling back here), then the rendered table + size + position,
     *  and opens it.  Closing it routes through {@link #setTableExtracted}(false) so the
     *  in-canvas table reappears. */
    private void createMeasurementWindow() {
        ToolWindow w = new ToolWindow(this, color(ColorRole.BACKGROUND), color(ColorRole.TEXT), BTN_W, BTN_H);
        w.setTitle(I18n.t("scope.external.window.title"));
        // Stats-toggle re-enables the σ columns from inside the window; reset clears the
        // running statistics.  Both flow back through redraw().
        w.addButton(Icon.CHART_LIT, Icon.CHART_DARK, true, Preferences.instance().isOscShowStats(),
                color(ColorRole.TEXT), I18n.t("scope.stats.toggle.tooltip"), e -> {
                    Preferences p = Preferences.instance();
                    p.setOscShowStats(!p.isOscShowStats());   // auto-saved; the main
                    // stats-toggle's pref subscriber mirrors the header button + reflows.
                    sizeMeasurementWindow();
                    redraw();
                });
        w.addButton(Icon.ROTATE_LEFT_RED, Icon.ROTATE_LEFT_DARK, false, false,
                color(ColorRole.RESET), I18n.t("scope.stats.reset.tooltip"), e -> {
                    clearMeasurementHistory();
                    redraw();
                });
        w.addCloseListener(e -> setTableExtracted(false));
        w.setPainter(this::paintMeasurementTable);
        measurementWindow = w;
        sizeMeasurementWindow();
        Point ws = w.getSize();
        Rectangle pb = getShell().getBounds();
        w.setLocation(pb.x + pb.width - ws.x - 24, pb.y + 96);
        w.open();
    }

    /** {@link ToolWindow.ContentPainter} for the measurements window — draws the live table
     *  straight into the window's GC at {@code top} (just below its button row). */
    private void paintMeasurementTable(GC gc, int top) {
        Preferences prefs = Preferences.instance();
        if (reader == null || !prefs.isOscShowMeasurementTable()) return;
        MeasurementRow[] rows = prepareMeasurementRows(prefs);
        if (rows == null) return;
        drawMeasurementTableAt(new GcMeasurementPainter(gc), rows, prefs.isOscLeftChannelEnabled(),
                prefs.isOscRightChannelEnabled(), prefs.getOscMeasurementChannel(), top);
    }

    /** Sizes {@link #measurementWindow} to the current column count (the σ columns appear
     *  / disappear with the stats toggle). */
    private void sizeMeasurementWindow() {
        if (measurementWindow == null) return;
        boolean showStats = Preferences.instance().isOscShowStats();
        int tableW = (showStats ? COL_SIGMA_RIGHT : COL_CUR_RIGHT) + 12;
        int tableH = estimateMeasurementLineHeight() * 9 + 6;
        measurementWindow.setSize(tableW, tableH);
    }

    /** Returns an estimate of the per-row pixel height of the measurement
     *  table.  Used during shell sizing before any paint has happened, so
     *  no live GC is available; uses the cached {@link #monoFont} when
     *  ready and falls back to a constant otherwise.  The actual paint
     *  reads {@code gc.textExtent("M").y + 1} which matches this. */
    private int estimateMeasurementLineHeight() {
        if (monoFont == null) return 12;
        GC tmp = new GC(this);
        try {
            tmp.setFont(monoFont);
            return tmp.textExtent("M").y + 1;
        } finally {
            tmp.dispose();
        }
    }

    @Override
    public void redraw() {
        super.redraw();
        if (glRepaint != null) glRepaint.run();   // GPU: render the surface (the view is hidden)
        // Mirror to the extracted tool window so it tracks the main view in lock-step — its
        // canvas repaints through the painter registered in createMeasurementWindow().
        if (measurementWindow != null) {
            measurementWindow.redraw();
        }
    }

    /** GPU: overlay-only repaints (rubber band, focus border) re-composite the
     *  phosphor instead of resetting it — see {@link #glOverlayRepaint}. */
    @Override
    protected void requestZoomOverlayRepaint() {
        if (glOverlayRepaint != null) {
            super.redraw();               // keep the (hidden) canvas + tool window consistent
            glOverlayRepaint.run();
            if (measurementWindow != null) measurementWindow.redraw();
        } else {
            redraw();
        }
    }

    /**
     * Starts the background measurement worker.  Idempotent — calling while
     * the worker is already running is a no-op.  Invoked by
     * {@link ScopePane#startCapture()} once a fresh
     * {@link SignalBufferReader} has been attached via {@link #setBuffer}.
     */
    public void startMeasurementThread() {
        measWorker.start();
    }

    /**
     * Stops the background measurement worker and waits up to 2 s for it to
     * exit.  Called from {@link ScopePane#stopCapture()}.
     */
    public void stopMeasurementThread() {
        measWorker.stop();
    }

    /**
     * Convenience wrapper: averaging window taken from the user's measurement
     * preference, but never shorter than {@link #AC_DC_MIN_AVG_NANOS} so the
     * AC trace and trigger don't visibly wobble between worker ticks.
     */
    private double acDcMean(boolean leftChannel) {
        long windowNanos = Math.max(AC_DC_MIN_AVG_NANOS,
                (long) (Preferences.instance().getOscMeasurementAverageSeconds() * 1e9));
        return measWorker.averagedChannelMean(leftChannel, windowNanos);
    }

    /**
     * Auto-setup vertical offset fraction for the {@code left} (else right)
     * channel at the given {@code vDiv}.  A DC-coupled channel (AC mode off) is
     * centred on its measured DC mean so a DC-biased signal lands mid-screen
     * instead of clipped off an edge; an AC-coupled channel — DC already removed
     * from its trace — stays centred at 0&nbsp;V.  The screen-centre voltage is
     * {@code (offsetFrac - 0.5) * DIVISIONS_Y * vDiv}, so centring on
     * {@code meanV} gives {@code offsetFrac = 0.5 + meanV / (DIVISIONS_Y * vDiv)}.
     * The result is intentionally <em>not</em> clamped to {@code [0, 1]}: a DC
     * bias larger than half the screen height pushes the 0&nbsp;V line off-grid,
     * which is exactly what centring such a signal requires (and the trace
     * renderer already supports offsets outside the grid).
     */
    public double autoSetupOffsetFrac(boolean left, double vDiv) {
        Preferences prefs = Preferences.instance();
        boolean acMode = left ? prefs.isOscLeftAcMode() : prefs.isOscRightAcMode();
        if (acMode || !(vDiv > 0)) return 0.5;
        double peakVolts = prefs.getAdcFsVoltageRms() * Math.sqrt(2.0);
        double meanV = acDcMean(left) * peakVolts;
        if (!Double.isFinite(meanV)) return 0.5;
        return 0.5 + meanV / (DIVISIONS_Y * vDiv);
    }

    /** V/div of the measurement channel, falling back to the only-visible
     *  channel if the measurement channel is disabled. */
    private double measurementChannelVPDiv() {
        Preferences prefs = Preferences.instance();
        Channel selected = prefs.getOscMeasurementChannel();
        boolean showL = prefs.isOscLeftChannelEnabled();
        boolean showR = prefs.isOscRightChannelEnabled();
        if (selected == Channel.R && showR) return prefs.getOscRightVoltsPerDiv();
        if (selected == Channel.L && showL) return prefs.getOscLeftVoltsPerDiv();
        if (showR)                          return prefs.getOscRightVoltsPerDiv();
        return prefs.getOscLeftVoltsPerDiv();
    }

    /** Returns the {@code [minOffsetFrac, maxOffsetFrac]} the vertical scrollbar
     *  should cover.  Extended past the usual {@code [0, 1]} so the trace can
     *  move all the way until either ±FS is at the grid centre.  At wide V/div
     *  (the entire ±FS span fits on screen) the range collapses back to exactly
     *  {@code [0, 1]} — there's nothing to scroll. */
    public double[] offsetFracBounds() {
        double vpdiv = measurementChannelVPDiv();
        double fs    = Preferences.instance().getAdcFsVoltageRms() * Math.sqrt(2.0);
        double half  = ScopeFormat.offsetMoveHalfRange(vpdiv, fs, DIVISIONS_Y);
        return new double[] { 0.5 - half, 0.5 + half };
    }

    /**
     * Applies per-channel mains-hum suppression to {@link #leftBuf} /
     * {@link #rightBuf} in place (DC-preserving) when a channel's mode is
     * {@code IIR_COMB}.  Combs are rebuilt on a sample-rate change; the
     * mains frequency is re-tracked at most every
     * {@link #MAINS_TRACK_PERIOD_NANOS} (drift is slow) and the tuning
     * persists across the per-paint {@code reset()}, so filtering continues
     * between tracks.  Called once per paint right after the read window is
     * filled, so trigger, draw and SINGLE capture all see the filtered data.
     */
    /** Snapshot of one rendered trace frame — the windowed samples plus the
     *  parameters {@link #renderTraces} needs — so a screenshot reproduces
     *  exactly what was on screen.  Vertical offset / line width / dot size
     *  are intentionally NOT stored: they come from the (global) Preferences
     *  the offscreen view shares, so they match automatically. */
    public static final class RenderedFrame {
        final float[] left, right;
        final int     len, dispStart, dispCount;
        final double  subSampleOffset;
        final boolean showL, showR, sincL, sincR;
        final double  leftVDiv, rightVDiv, dcL, dcR;
        RenderedFrame(float[] left, float[] right, int len, int dispStart, int dispCount,
                      double subSampleOffset, boolean showL, boolean showR,
                      boolean sincL, boolean sincR, double leftVDiv, double rightVDiv,
                      double dcL, double dcR) {
            this.left = left; this.right = right; this.len = len;
            this.dispStart = dispStart; this.dispCount = dispCount;
            this.subSampleOffset = subSampleOffset;
            this.showL = showL; this.showR = showR; this.sincL = sincL; this.sincR = sincR;
            this.leftVDiv = leftVDiv; this.rightVDiv = rightVDiv; this.dcL = dcL; this.dcR = dcR;
        }
    }

    /** Returns a carbon-copy of the most recently rendered frame, or
     *  {@code null} if nothing has been drawn yet.  Used by the screenshot
     *  pane so the image matches the (possibly frozen) on-screen trace.
     *  Materialises an OWNING copy here: {@link #captureFrame} stages into
     *  grow-only buffers that the next paint overwrites, so the per-paint
     *  cost is a memcpy instead of ~75 MB/s of fresh allocations, and only
     *  the rare screenshot pays for stable arrays. */
    public RenderedFrame getRenderedFrameSnapshot() {
        RenderedFrame f = lastFrame;
        if (f == null) return null;
        return new RenderedFrame(
                f.left  != null ? Arrays.copyOf(f.left,  f.len) : null,
                f.right != null ? Arrays.copyOf(f.right, f.len) : null,
                f.len, f.dispStart, f.dispCount, f.subSampleOffset,
                f.showL, f.showR, f.sincL, f.sincR,
                f.leftVDiv, f.rightVDiv, f.dcL, f.dcR);
    }

    /** Makes this view render {@code f} verbatim instead of reading the live
     *  buffer (offscreen screenshot view). */
    public void setFrozenFrame(RenderedFrame f) {
        this.frozenFrame = f;
    }

    /** Copies just the displayed window (plus Lanczos padding) of the data
     *  {@link #renderTraces} is about to draw into {@link #lastFrame}, so the
     *  snapshot is small yet sufficient to re-render an identical trace. */
    private void captureFrame(float[] dataLeft, float[] dataRight, int dataLen,
                              int dispStart, double subSampleOffset, int dispCount,
                              boolean showL, boolean showR, double leftVDiv, double rightVDiv,
                              boolean sincL, boolean sincR, double dcL, double dcR) {
        int pad = Lanczos.LANCZOS_PADDING;
        // Clamp BOTH ends into [0, dataLen]: a virtual trigger offset / strong zoom-out
        // can drive dispStart far past the buffer, and arraycopy rejects a srcPos beyond
        // the array even with length 0.  When the window is fully off-buffer this yields
        // n = 0 → an empty frame → the renderer draws nothing (blank), per spec.
        int lo  = Math.max(0, Math.min(dataLen, dispStart - pad));
        int hi  = Math.max(0, Math.min(dataLen, dispStart + dispCount + pad));
        int n   = Math.max(0, hi - lo);
        // Stage into grow-only buffers — the snapshot getter materialises an
        // owning copy when (rarely) asked, so the per-paint cost is a memcpy
        // rather than two fresh multi-100k float[] every frame.
        float[] l = null;
        float[] r = null;
        if (showL && dataLeft != null) {
            if (capStageL == null || capStageL.length < n) capStageL = new float[n];
            System.arraycopy(dataLeft, lo, capStageL, 0, n);
            l = capStageL;
        }
        if (showR && dataRight != null) {
            if (capStageR == null || capStageR.length < n) capStageR = new float[n];
            System.arraycopy(dataRight, lo, capStageR, 0, n);
            r = capStageR;
        }
        lastFrame = new RenderedFrame(l, r, n, dispStart - lo, dispCount, subSampleOffset,
                showL, showR, sincL, sincR, leftVDiv, rightVDiv, dcL, dcR);
    }

    /** Applies the per-channel HF low-pass (80 kHz) to {@link #leftBuf} /
     *  {@link #rightBuf} in place, stripping switching / RF spikes above the
     *  audio band.  Always on for both channels (re-read overlapping windows
     *  ⇒ reset each paint), but a no-op below its Nyquist gate. */
    private void applyHfLowPass(int sampleRate, int available,
                                boolean needL, boolean needR) {
        Preferences prefs = Preferences.instance();
        LpfMode lm = prefs.getOscLeftLpf();
        LpfMode rm = prefs.getOscRightLpf();
        if (lm == LpfMode.NONE && rm == LpfMode.NONE) return;
        if (hfLpfSampleRate != sampleRate) {
            hfLpfLeft = null; hfLpfRight = null;
            hfLpfSampleRate = sampleRate;
        }
        if (needL) applyChannelHf(lm, true,  sampleRate, leftBuf,  available);
        if (needR) applyChannelHf(rm, false, sampleRate, rightBuf, available);
    }

    /** Applies one channel's selected HF cleanup ({@link LpfMode}) to
     *  {@code buf} in place: an 80 kHz Chebyshev low-pass, a median
     *  de-spike, or nothing.  Filters are built lazily; the LPF (IIR) is
     *  reset each call since the scope re-reads overlapping windows, while
     *  the median is memoryless. */
    private void applyChannelHf(LpfMode mode, boolean left, int sampleRate, float[] buf, int len) {
        if (mode == LpfMode.HZ_80) {
            LowPassFilter f = left ? hfLpfLeft : hfLpfRight;
            if (f == null) {
                f = new LowPassFilter(sampleRate, mode.cutoffHz, ScopeMeasurementWorker.SCOPE_HF_LPF_ORDER);
                if (left) hfLpfLeft = f; else hfLpfRight = f;
                log.info("Scope LPF [{}]: {} Hz, active={} (Nyquist {} Hz)",
                        left ? "L" : "R", mode.cutoffHz, f.isActive(), sampleRate / 2);
            }
            if (f.isActive()) { f.reset(); f.process(buf, len); }
        } else if (mode == LpfMode.DESPIKE) {
            MedianFilter m = left ? despikeLeft : despikeRight;
            if (m == null) {
                m = new MedianFilter(mode.window);
                if (left) despikeLeft = m; else despikeRight = m;
            }
            m.process(buf, len);
        }
    }

    private void applyMainsSuppression(int sampleRate, int available,
                                       boolean needL, boolean needR, long absStart) {
        Preferences prefs = Preferences.instance();
        MainsSuppression modeL = prefs.getOscLeftMainsSuppression();
        MainsSuppression modeR = prefs.getOscRightMainsSuppression();
        boolean mainsL = needL && modeL != MainsSuppression.NONE;
        boolean mainsR = needR && modeR != MainsSuppression.NONE;
        if (!mainsL && !mainsR) return;
        if (mainsCombSampleRate != sampleRate) {
            mainsLeft = mainsRight = null;          // rebuild both at the new rate
            mainsLeftMode = mainsRightMode = MainsSuppression.NONE;
            mainsCombSampleRate = sampleRate;
            mainsTrackNanos = 0;
        }
        long now = System.nanoTime();
        boolean retrack = (now - mainsTrackNanos) >= MAINS_TRACK_PERIOD_NANOS;
        if (retrack) mainsTrackNanos = now;
        // An untuned filter (just enabled, or no mains line found yet) is
        // re-tracked every paint until it locks — independent of the throttle
        // and of System.nanoTime()'s arbitrary origin — so enabling a channel
        // mid-run takes effect immediately rather than after one throttle gap.
        if (mainsL) {
            if (mainsLeft == null || mainsLeftMode != modeL) {
                mainsLeft = MainsFilters.of(modeL, sampleRate, MAINS_NOTCH_BW_HZ);
                mainsLeftMode = modeL;
            }
            if (retrack || !mainsLeft.isTuned()) logMainsLock("L", mainsLeft.track(leftBuf, available));
            // The comb keeps no cross-paint state worth holding — reset it so
            // each paint filters the contiguous window fresh; the adaptive
            // filters KEEP their converged template / weights across paints.
            if (modeL == MainsSuppression.IIR_COMB) mainsLeft.reset();
            mainsLeft.processPreservingDc(leftBuf, available, absStart);
        }
        if (mainsR) {
            if (mainsRight == null || mainsRightMode != modeR) {
                mainsRight = MainsFilters.of(modeR, sampleRate, MAINS_NOTCH_BW_HZ);
                mainsRightMode = modeR;
            }
            if (retrack || !mainsRight.isTuned()) logMainsLock("R", mainsRight.track(rightBuf, available));
            if (modeR == MainsSuppression.IIR_COMB) mainsRight.reset();
            mainsRight.processPreservingDc(rightBuf, available, absStart);
        }
    }

    /** One-line diagnostic, debounced to lock-state changes, so the log
     *  shows whether the scope's mains comb actually detected a 50/60 Hz
     *  line (and to what frequency) — a quick way to tell a detection miss
     *  from a merely-invisible-on-a-linear-trace suppression. */
    private void logMainsLock(String ch, double trackedHz) {
        double last = "L".equals(ch) ? mainsLoggedHzL : mainsLoggedHzR;
        boolean changed = Double.isNaN(trackedHz) != Double.isNaN(last)
                || (!Double.isNaN(trackedHz) && Math.abs(trackedHz - last) > 0.5);
        if (!changed) return;
        if ("L".equals(ch)) mainsLoggedHzL = trackedHz; else mainsLoggedHzR = trackedHz;
        if (Double.isNaN(trackedHz)) {
            log.info("Scope mains comb [{}]: no mains line detected (suppression idle)", ch);
        } else {
            log.info("Scope mains comb [{}]: locked {} Hz", ch, trackedHz);
        }
    }

    /** Renders the measurement table into {@code gc} from {@code startY} down.  Shared by the
     *  in-canvas table (main-view paint, below the header button row) and the extracted tool
     *  window's painter (below its stats/reset row). */
    private void drawMeasurementTableAt(MeasurementPainter gc, MeasurementRow[] rows,
                                        boolean showL, boolean showR, Channel selected,
                                        int startY) {
        if (monoFont == null) {
            monoFont = Fonts.instance().normal(getDisplay());
        }
        Font prevFont = gc.getFont();
        gc.setFont(monoFont);
        gc.setAntialias(SWT.OFF);
        gc.setTextAntialias(SWT.ON);
        gc.setForeground(color(ColorRole.TEXT));
        gc.setBackground(color(ColorRole.BACKGROUND));

        int lineH = gc.textExtent("M").y + 1;
        int y = startY;

        boolean showStats = Preferences.instance().isOscShowStats();

        gc.setFont(monoFont);
        gc.setForeground(color(ColorRole.TEXT));
        // First paint: cache textExtent of each header now that the GC is
        // wearing monoFont.  Headers never change, so this stays valid.
        if (headerExtents == null) {
            headerExtents = new Point[HEADERS.length];
            for (int i = 0; i < HEADERS.length; i++) {
                headerExtents[i] = gc.textExtent(HEADERS[i]);
            }
        }
        // When the stats toggle is off, draw only the "cur" header/column;
        // avg / min / max / σ are hidden.  Background worker keeps
        // computing them so the row[] cache is still populated — toggling
        // back on shows up-to-date numbers immediately.
        int headerCols = showStats ? HEADERS.length : 1;
        for (int i = 0; i < headerCols; i++) {
            drawOutlinedText(gc, HEADERS[i], HEADER_RIGHTS[i] - headerExtents[i].x, y);
        }
        y += lineH;
        for (MeasurementRow r : rows) {
            drawOutlinedText(gc, r.name, COL_NAME_X, y);
            drawOutlinedRightAligned(gc, r.cur,   COL_CUR_RIGHT,  y);
            if (showStats) {
                drawOutlinedRightAligned(gc, r.avg,   COL_AVG_RIGHT,  y);
                drawOutlinedRightAligned(gc, r.min,   COL_MIN_RIGHT,  y);
                drawOutlinedRightAligned(gc, r.max,   COL_MAX_RIGHT,  y);
                drawOutlinedRightAligned(gc, r.sigma, COL_SIGMA_RIGHT, y);
            }
            y += lineH;
        }
        gc.setFont(prevFont);
    }

    /**
     * Draws the three pane-edge sliders (signal offset on the left, trigger
     * level on the right, trigger position on the bottom) and the dashed
     * cross-hairs that mark the trigger level and position inside the grid.
     * Hit-boxes are refreshed each paint so the drag handler picks up the
     * latest geometry after a resize or a V/div change moves a handle.
     */
    private void drawSliders(MeasurementPainter gc, int w, int h) {
        Preferences prefs = Preferences.instance();
        boolean showL = prefs.isOscLeftChannelEnabled();
        boolean showR = prefs.isOscRightChannelEnabled();
        Channel measCh = prefs.getOscMeasurementChannel();
        Channel trigCh = prefs.getOscTriggerChannel();
        double leftVDiv  = prefs.getOscLeftVoltsPerDiv();
        double rightVDiv = prefs.getOscRightVoltsPerDiv();
        double timePerDiv = prefs.getOscTimePerDiv();
        int prevLineWidth = gc.getLineWidth();
        int[] prevDash = gc.getLineDash();

        // Line dash patterns:
        //   long-dash (trigger sliders) — long pen-on + short gap, clearly
        //   visible across the whole pane even at low contrast against the
        //   waveform;
        //   short-dash (offset slider) — half-length dashes, visually distinct
        //   from the trigger lines so the two cross-hairs can be told apart
        //   at a glance even when they overlap.
        final int[] LONG_DASH  = { 10, 4 };
        final int[] DASH       = { 4, 4 };

        if (monoFont == null) {
            monoFont = Fonts.instance().normal(getDisplay());
        }
        Font prevFont = gc.getFont();
        gc.setFont(monoFont);
        gc.setBackground(color(ColorRole.BACKGROUND));
        gc.setTextAntialias(SWT.ON);

        // ----- Pre-compute opposite-side label widths so each horizontal
        // slider line can stop short of the OTHER side's label envelope.
        // Trigger-level label width bounds the offset-line right end; the
        // widest of the two channels' offset labels bounds the trigger-level
        // line left end.  Using the worst-case width yields a uniform stop
        // even at slider-Y positions where the lines would not actually
        // overlap, but the result reads as a clean broken track.
        // Draw position clamps to the canvas edge, but the VOLTAGE label below
        // uses the raw (virtual-capable) fraction — a vertical zoom can park
        // the level outside the view, and the label must state the threshold
        // the trigger actually fires at, not the edge voltage under the
        // pinned handle.
        double levelFracRaw = prefs.getOscTriggerLevelFrac();
        double levelFrac = ScopeFormat.clamp01(levelFracRaw);
        int levelY = (int) Math.round(levelFrac * h);
        // Trigger-level marker is ALWAYS yellow — a fixed scope-trigger colour,
        // independent of which channel is the trigger source or its trace colour.
        Color levelColor = getDisplay().getSystemColor(SWT.COLOR_YELLOW);
        // Trigger-channel offset is intentionally NOT clamped to [0, 1] —
        // the trigger-level voltage label needs to track the channel offset
        // out to the extended ±FS-at-centre range the vertical scrollbar
        // can reach.
        double trigOffsetFrac = (trigCh == Channel.L)
                ? prefs.getOscLeftOffsetFrac()
                : prefs.getOscRightOffsetFrac();
        double trigVDiv  = (trigCh == Channel.L) ? leftVDiv : rightVDiv;
        double levelVolts = (trigOffsetFrac - levelFracRaw) * DIVISIONS_Y * trigVDiv;
        String levelStr = ScopeFormat.formatVolts(levelVolts, trigVDiv);
        Point lvs = gc.textExtent(levelStr);

        int maxOffsetLabelW = 0;
        if (showL) {
            // No clamp01 — the offset-marker voltage label needs to honour
            // the extended offset range too (mirrors the trigger-level
            // label above).
            String s = ScopeFormat.formatVolts((0.5 - prefs.getOscLeftOffsetFrac())
                    * DIVISIONS_Y * leftVDiv, leftVDiv);
            maxOffsetLabelW = Math.max(maxOffsetLabelW, gc.textExtent(s).x);
        }
        if (showR) {
            String s = ScopeFormat.formatVolts((0.5 - prefs.getOscRightOffsetFrac())
                    * DIVISIONS_Y * rightVDiv, rightVDiv);
            maxOffsetLabelW = Math.max(maxOffsetLabelW, gc.textExtent(s).x);
        }
        int offsetLineRightEnd = w - SLIDER_TRI_LONG - 4 - lvs.x - 4;
        int levelLineStartX    = SLIDER_TRI_LONG + 4 + maxOffsetLabelW + 4;

        // ----- Trigger level: dotted horizontal cross-hair + handle on right
        // edge.  The dotted line is broken on both sides — short of the
        // value label on the right, and short of the offset labels on the
        // left — so neither overlay sits on top of the dotted track.
        // Hidden entirely in file mode — trigger has no meaning on a
        // static loaded signal, and an inactive slider just clutters
        // the canvas.
        if (!fileMode) {
            int levelLabelX  = w - SLIDER_TRI_LONG - 4 - lvs.x;
            int levelLabelY  = levelY - lvs.y / 2;
            int levelLineEnd = levelLabelX - 4;
            if (levelLineStartX < levelLineEnd) {
                drawOutlinedDashedLine(gc, levelLineStartX, levelY,
                        levelLineEnd, levelY, LONG_DASH, levelColor);
            }
            drawLeftPointingTriangle(gc, w - 1, levelY, levelColor);
            triggerLevelBounds.x      = w - SLIDER_TRI_LONG - 2;
            triggerLevelBounds.y      = levelY - SLIDER_GRAB_HALF;
            triggerLevelBounds.width  = SLIDER_TRI_LONG + 4;
            triggerLevelBounds.height = 2 * SLIDER_GRAB_HALF;
            gc.setForeground(levelColor);
            drawOutlinedText(gc, levelStr, levelLabelX, levelLabelY);
        } else {
            // Clear hit-test bounds so a leftover position from before
            // the mode switch can't intercept clicks.
            triggerLevelBounds.x = -1; triggerLevelBounds.y = -1;
            triggerLevelBounds.width = 0; triggerLevelBounds.height = 0;
        }

        // ----- Trigger position: long-dashed vertical cross-hair + handle on bottom edge.
        // Same fileMode-hide treatment for the trigger-position slider.
        if (!fileMode) {
            // The offset can be virtual (pan/zoom carried it off-screen): pin the
            // line + handle to the clamped on-screen position so it stays grabbable,
            // but the time-offset label shows the REAL offset, however far outside.
            double posReal = prefs.getOscTriggerPositionFrac();
            double posFrac = ScopeFormat.clamp01(posReal);
            int posX = (int) Math.round(posFrac * w);
            drawOutlinedDashedLine(gc, posX, 0, posX, h - 1, LONG_DASH, color(ColorRole.TEXT));
            drawUpPointingTriangle(gc, posX, h - 1, color(ColorRole.TEXT));
            triggerPosBounds.x      = posX - SLIDER_GRAB_HALF;
            triggerPosBounds.y      = h - SLIDER_TRI_LONG - 2;
            triggerPosBounds.width  = 2 * SLIDER_GRAB_HALF;
            triggerPosBounds.height = SLIDER_TRI_LONG + 4;
            double windowTime = timePerDiv * DIVISIONS_X;
            double posSeconds = (posReal - 0.5) * windowTime;
            String posStr = ScopeFormat.formatSeconds(posSeconds);
            Point pps = gc.textExtent(posStr);
            int posLabelY = h - SLIDER_TRI_LONG - 4 - pps.y - pps.y;
            int posLabelX = posX - pps.x / 2;
            if (posLabelX < 2) posLabelX = 2;
            if (posLabelX + pps.x > w - 2) posLabelX = w - 2 - pps.x;
            gc.setForeground(color(ColorRole.TEXT));
            drawOutlinedText(gc, posStr, posLabelX, posLabelY);
        } else {
            triggerPosBounds.x = -1; triggerPosBounds.y = -1;
            triggerPosBounds.width = 0; triggerPosBounds.height = 0;
        }

        // ----- Channel offsets: both visible channels get a full set of
        // marker elements (dashed zero-line, triangle, value label).  Only
        // the triangle's brightness distinguishes active (drag-enabled) from
        // inactive (read-only) — line and label stay at full brightness on
        // both so the position info is clearly readable for both channels.
        // Inactive is drawn first so any overlap at the same Y is won by the
        // active triangle and label.
        // ----- Full-scale boundary lines: two dashed horizontal lines per
        // enabled channel at ±FS volts relative to that channel's offset.
        // Visible only at V/div settings where ±FS lands within the canvas
        // (typically ≥ 1 V/div); off-screen lines are naturally clipped.
        // Drawn before the offset track so the brighter offset zero-line
        // and triangle stay on top when they overlap.
        double peakVolts = prefs.getAdcFsVoltageRms() * Math.sqrt(2.0);
        double pixelsPerDivY = (double) h / DIVISIONS_Y;
        final int[] FS_DASH = { 2, 6 };
        if (showL) {
            drawFullScaleLines(gc, h, w, FS_DASH,
                    ScopeFormat.clamp01(prefs.getOscLeftOffsetFrac()), leftVDiv,
                    peakVolts, pixelsPerDivY, color(ColorRole.LEFT_CHANNEL_MID));
        }
        if (showR) {
            drawFullScaleLines(gc, h, w, FS_DASH,
                    ScopeFormat.clamp01(prefs.getOscRightOffsetFrac()), rightVDiv,
                    peakVolts, pixelsPerDivY, color(ColorRole.RIGHT_CHANNEL_MID));
        }

        boolean activeIsL = (measCh == Channel.L);
        boolean activeEnabled = activeIsL ? showL : showR;
        boolean otherEnabled  = activeIsL ? showR : showL;
        if (otherEnabled) {
            boolean otherIsL = !activeIsL;
            drawOffsetTrack(gc, h, DASH,
                    ScopeFormat.clamp01(otherIsL ? prefs.getOscLeftOffsetFrac()
                                     : prefs.getOscRightOffsetFrac()),
                    otherIsL ? leftVDiv : rightVDiv,
                    otherIsL ? color(ColorRole.LEFT_TRACE) : color(ColorRole.RIGHT_TRACE),
                    otherIsL ? color(ColorRole.LEFT_CHANNEL_MID)   : color(ColorRole.RIGHT_CHANNEL_MID),
                    false, offsetLineRightEnd);
        }
        if (activeEnabled) {
            Color c = activeIsL ? color(ColorRole.LEFT_TRACE) : color(ColorRole.RIGHT_TRACE);
            drawOffsetTrack(gc, h, DASH,
                    ScopeFormat.clamp01(activeIsL ? prefs.getOscLeftOffsetFrac()
                                      : prefs.getOscRightOffsetFrac()),
                    activeIsL ? leftVDiv : rightVDiv,
                    c, c, true, offsetLineRightEnd);
        } else {
            offsetSliderBounds.x = offsetSliderBounds.y = -1;
            offsetSliderBounds.width = offsetSliderBounds.height = 0;
        }

        gc.setFont(prevFont);
        gc.setLineWidth(prevLineWidth);
        if (prevDash != null) gc.setLineDash(prevDash); else gc.setLineDash(null);
    }

    /**
     * Draws two horizontal dashed lines — one at +FS volts and one at −FS
     * volts — relative to the channel's offset.  Moves with the channel's
     * vertical-offset slider.  Visible only when the V/div is coarse
     * enough to put ±FS inside the canvas; otherwise SWT's natural clip
     * culls the off-screen ends.
     */
    private void drawFullScaleLines(MeasurementPainter gc, int h, int w, int[] dash,
                                    double offsetFrac, double vDiv,
                                    double peakVolts, double pixelsPerDivY,
                                    Color color) {
        double centerY = offsetFrac * h;
        double vScale  = peakVolts / vDiv * pixelsPerDivY;
        int yTop = (int) Math.round(centerY - vScale);
        int yBot = (int) Math.round(centerY + vScale);
        gc.setForeground(color);
        gc.setLineDash(dash);
        if (yTop >= 0 && yTop < h) gc.drawLine(0, yTop, w - 1, yTop);
        if (yBot >= 0 && yBot < h) gc.drawLine(0, yBot, w - 1, yBot);
        gc.setLineDash(null);
    }

    /**
     * Draws one offset slider — dashed zero-line, triangle handle on the left
     * edge, and a value label between them.  The line starts AFTER the label
     * so the label sits cleanly on the background instead of on top of the
     * dashed track.  When {@code isActive} is {@code true} the
     * {@link #offsetSliderBounds} hit-zone is registered so this channel's
     * handle becomes draggable; otherwise the triangle is a passive indicator
     * (typically rendered with {@code triangleColor} as the channel's mid
     * variant while {@code lineLabelColor} stays full-bright).
     */
    private void drawOffsetTrack(MeasurementPainter gc, int h, int[] lineDash,
                                  double offsetFrac, double vDiv,
                                  Color lineLabelColor, Color triangleColor,
                                  boolean isActive, int lineRightEnd) {
        int offsetY     = (int) Math.round(offsetFrac * h);
        String label    = ScopeFormat.formatVolts((0.5 - offsetFrac) * DIVISIONS_Y * vDiv, vDiv);
        Point ts        = gc.textExtent(label);
        int labelX      = SLIDER_TRI_LONG + 4;
        int labelY      = offsetY - ts.y / 2;
        int lineStartX  = labelX + ts.x + 4;

        if (lineStartX < lineRightEnd) {
            drawOutlinedDashedLine(gc, lineStartX, offsetY, lineRightEnd, offsetY,
                    lineDash, lineLabelColor);
        }

        drawRightPointingTriangle(gc, 0, offsetY, triangleColor);

        gc.setForeground(lineLabelColor);
        drawOutlinedText(gc, label, labelX, labelY);

        if (isActive) {
            offsetSliderBounds.x      = 0;
            offsetSliderBounds.y      = offsetY - SLIDER_GRAB_HALF;
            offsetSliderBounds.width  = SLIDER_TRI_LONG + 4;
            offsetSliderBounds.height = 2 * SLIDER_GRAB_HALF;
        }
    }

    /** Filled triangle whose tip points into the grid from the left edge. */
    private void drawRightPointingTriangle(MeasurementPainter gc, int xBase, int y, Color color) {
        int[] poly = {
                xBase,                      y - SLIDER_TRI_HALF,
                xBase + SLIDER_TRI_LONG,    y,
                xBase,                      y + SLIDER_TRI_HALF
        };
        drawOutlinedFilledPolygon(gc, poly, color);
    }

    /** Filled triangle whose tip points into the grid from the right edge. */
    private void drawLeftPointingTriangle(MeasurementPainter gc, int xBase, int y, Color color) {
        int[] poly = {
                xBase,                      y - SLIDER_TRI_HALF,
                xBase - SLIDER_TRI_LONG,    y,
                xBase,                      y + SLIDER_TRI_HALF
        };
        drawOutlinedFilledPolygon(gc, poly, color);
    }

    /** Filled triangle whose tip points up into the grid from the bottom edge. */
    private void drawUpPointingTriangle(MeasurementPainter gc, int x, int yBase, Color color) {
        int[] poly = {
                x - SLIDER_TRI_HALF,        yBase,
                x,                          yBase - SLIDER_TRI_LONG,
                x + SLIDER_TRI_HALF,        yBase
        };
        drawOutlinedFilledPolygon(gc, poly, color);
    }

    /**
     * Renders absolute-unit labels along the centre cross-hair:
     * <ul>
     *   <li>Time at the horizontal centre line × left/right grid edges (just
     *       above the line — negative on the left, positive on the right).</li>
     *   <li>Channel min / max at the vertical centre line × top/bottom grid
     *       edges — left channel just left of the centre, right channel just
     *       right of it, each in its own colour.</li>
     * </ul>
     * Keeps the absolute-value readouts on the centre cross-hair so they
     * don't fight the measurement table for corner space.
     */
    private void drawEdgeLabels(MeasurementPainter gc, int w, int h) {
        Preferences prefs = Preferences.instance();
        double timePerDiv = prefs.getOscTimePerDiv();
        double leftVDiv   = prefs.getOscLeftVoltsPerDiv();
        double rightVDiv  = prefs.getOscRightVoltsPerDiv();
        boolean showL = prefs.isOscLeftChannelEnabled();
        boolean showR = prefs.isOscRightChannelEnabled();

        if (monoFont == null) {
            monoFont = Fonts.instance().normal(getDisplay());
        }
        Font prevFont = gc.getFont();
        gc.setFont(monoFont);
        gc.setBackground(color(ColorRole.BACKGROUND));
        gc.setTextAntialias(SWT.ON);

        int centerX = w / 2;
        int centerY = h / 2;

        // ----- Time on the horizontal centre line × left / right edges.
        double windowTime = timePerDiv * DIVISIONS_X;
        double leftTime;
        double rightTime;
        if (fileMode && reader != null) {
            // Loaded file: no trigger — the edge marks show the ABSOLUTE time within
            // the file (seconds from its first sample), from the right-anchored view
            // position (viewBackOffsetFrames back from the tip).
            int sr = reader.getSampleRate();
            double viewRightAbs = (double) reader.getWritePos() - viewBackOffsetFrames;
            // Window width in EXACT double samples (timePerDiv × 10 div × rate) —
            // the int-rounded displaySamplesFor would bias the left mark at rates
            // where the window isn't a whole sample count (e.g. 88.2 @ 44.1 kHz).
            double viewLeftAbs  = viewRightAbs - windowTime * sr;
            leftTime  = viewLeftAbs  / sr;
            rightTime = viewRightAbs / sr;
        } else {
            // Live / frozen: times are relative to the trigger (t = 0).  Use the REAL
            // (virtual-capable) offset so the marks keep counting when a pan/zoom has
            // carried the trigger off-screen.
            double posReal = prefs.getOscTriggerPositionFrac();
            leftTime  = -posReal * windowTime;
            rightTime = (1 - posReal) * windowTime;
        }
        String leftTimeStr  = ScopeFormat.formatSeconds(leftTime);
        String rightTimeStr = ScopeFormat.formatSeconds(rightTime);
        Point lts = gc.textExtent(leftTimeStr);
        Point rts = gc.textExtent(rightTimeStr);
        gc.setForeground(color(ColorRole.TEXT));
        int leftTimeX  = 4;
        int leftTimeY  = centerY - lts.y - 2;
        int rightTimeX = w - rts.x - 4;
        int rightTimeY = centerY - rts.y - 2;
        drawOutlinedText(gc, leftTimeStr,  leftTimeX,  leftTimeY);
        drawOutlinedText(gc, rightTimeStr, rightTimeX, rightTimeY);
        timeLeftLabelBounds .x = leftTimeX;  timeLeftLabelBounds .y = leftTimeY;
        timeLeftLabelBounds .width = lts.x;  timeLeftLabelBounds .height = lts.y;
        timeRightLabelBounds.x = rightTimeX; timeRightLabelBounds.y = rightTimeY;
        timeRightLabelBounds.width = rts.x;  timeRightLabelBounds.height = rts.y;

        // ----- Channel min / max on the vertical centre line × top / bottom edges.
        // Left channel labels sit just left of the vertical centre, right
        // channel just right of it; both at the top (max) and bottom (min) of
        // the grid, in each channel's colour.
        if (showL) {
            // offsetFrac may run past [0, 1] when the user has scrolled
            // the trace toward the ±FS-at-centre extremes — render the
            // labels honestly so the user sees the actual top/bottom
            // grid voltages.  Precision tracks V/div so a narrow scale
            // doesn't round meaningful digits away.
            double off = prefs.getOscLeftOffsetFrac();
            double maxV = off * DIVISIONS_Y * leftVDiv;
            double minV = -(1 - off) * DIVISIONS_Y * leftVDiv;
            String maxStr = ScopeFormat.formatVolts(maxV, leftVDiv);
            String minStr = ScopeFormat.formatVolts(minV, leftVDiv);
            Point mxs = gc.textExtent(maxStr);
            Point mns = gc.textExtent(minStr);
            gc.setForeground(color(ColorRole.LEFT_TRACE));
            int maxX = centerX - mxs.x - 4;
            int minX = centerX - mns.x - 4;
            int minY = h - mns.y - 2;
            drawOutlinedText(gc, maxStr, maxX, 2);
            drawOutlinedText(gc, minStr, minX, minY);
            leftMaxLabelBounds.x = maxX; leftMaxLabelBounds.y = 2;
            leftMaxLabelBounds.width = mxs.x; leftMaxLabelBounds.height = mxs.y;
            leftMinLabelBounds.x = minX; leftMinLabelBounds.y = minY;
            leftMinLabelBounds.width = mns.x; leftMinLabelBounds.height = mns.y;
        } else {
            leftMaxLabelBounds.width = 0; leftMaxLabelBounds.height = 0;
            leftMinLabelBounds.width = 0; leftMinLabelBounds.height = 0;
        }
        if (showR) {
            double off = prefs.getOscRightOffsetFrac();
            double maxV = off * DIVISIONS_Y * rightVDiv;
            double minV = -(1 - off) * DIVISIONS_Y * rightVDiv;
            String maxStr = ScopeFormat.formatVolts(maxV, rightVDiv);
            String minStr = ScopeFormat.formatVolts(minV, rightVDiv);
            Point mxs = gc.textExtent(maxStr);
            Point mns = gc.textExtent(minStr);
            gc.setForeground(color(ColorRole.RIGHT_TRACE));
            int maxX = centerX + 4;
            int minX = centerX + 4;
            int minY = h - mns.y - 2;
            drawOutlinedText(gc, maxStr, maxX, 2);
            drawOutlinedText(gc, minStr, minX, minY);
            rightMaxLabelBounds.x = maxX; rightMaxLabelBounds.y = 2;
            rightMaxLabelBounds.width = mxs.x; rightMaxLabelBounds.height = mxs.y;
            rightMinLabelBounds.x = minX; rightMinLabelBounds.y = minY;
            rightMinLabelBounds.width = mns.x; rightMinLabelBounds.height = mns.y;
        } else {
            rightMaxLabelBounds.width = 0; rightMaxLabelBounds.height = 0;
            rightMinLabelBounds.width = 0; rightMinLabelBounds.height = 0;
        }

        gc.setFont(prevFont);
    }

    /**
     * Maps a mouse position to the active slider's fraction and stores it
     * back into preferences (without saving — the disk save happens on
     * mouseUp so we don't hammer the YAML file during a drag).
     */
    /** Pointer pressed at ({@code x}, {@code y}) in the visible scope canvas —
     *  grabs a slider handle / header button under the cursor, else starts a
     *  rect-zoom selection drag (which also focuses the view).  Public so the
     *  GPU path's GL canvas can forward to it (this view is hidden then;
     *  coordinates are canvas-local and the hit-test bounds were laid out at
     *  the canvas size). */
    public void pointerDown(int x, int y, int button) {
        if (button != 1) return;
        ToolButton hb = headerButtonAt(x, y);   // GPU path: GL-drawn buttons forward here
        if (hb != null) {
            hb.notifyListeners(SWT.MouseDown, new Event());
            pressedHeaderButton = hb;
            redraw();
            return;
        }
        if (offsetSliderBounds.contains(x, y)) {
            draggingSlider = OscSliderId.OFFSET;
        } else if (!fileMode && triggerLevelBounds.contains(x, y)) {
            draggingSlider = OscSliderId.TRIGGER_LEVEL;
        } else if (!fileMode && triggerPosBounds.contains(x, y)) {
            draggingSlider = OscSliderId.TRIGGER_POSITION;
        }
        if (draggingSlider != null) {
            updateSliderFromMouse(x, y);
        } else {
            // No slider grabbed: focus the view and possibly start a rect-zoom drag.
            rectZoomPointerDown(x, y, button);
        }
    }

    /** Pointer released — commits a rect-zoom selection, else ends a slider
     *  drag and persists the new value. */
    public void pointerUp() {
        if (pressedHeaderButton != null) {
            pressedHeaderButton.notifyListeners(SWT.MouseUp, new Event());
            pressedHeaderButton = null;
            redraw();
            return;
        }
        if (rectZoomPointerUp()) return;
        if (draggingSlider != null) {
            Preferences.instance().save();
            draggingSlider = null;
        }
    }

    /** Double-click — resets the slider handle under the cursor to centre (0.5). */
    public void pointerDoubleClick(int x, int y, int button) {
        if (button != 1) return;
        Preferences prefs = Preferences.instance();
        boolean changed = false;
        if (offsetSliderBounds.contains(x, y)) {
            if (prefs.getOscMeasurementChannel() == Channel.L) {
                prefs.setOscLeftOffsetFrac(0.5);
            } else {
                prefs.setOscRightOffsetFrac(0.5);
            }
            changed = true;
        } else if (!fileMode && triggerLevelBounds.contains(x, y)) {
            prefs.setOscTriggerLevelFrac(0.5);
            changed = true;
        } else if (!fileMode && triggerPosBounds.contains(x, y)) {
            prefs.setOscTriggerPositionFrac(0.5);
            changed = true;
        }
        if (changed) {
            draggingSlider = null;
            prefs.save();
            redraw();
        }
    }

    /** The wheel-zoom anchors the next frozen-frame t/div change around the
     *  cursor; a t/div field change leaves it unset so the trace stays centred.
     *  See {@link #heldZoomAnchorX}. */
    public void setHeldZoomAnchorForNextScale(int canvasX) {
        heldZoomAnchorX = canvasX;
    }

    // -------------------------------------------------------------------------
    // Rectangular zoom (base machinery in AbstractMeasurementView)
    // -------------------------------------------------------------------------

    /** X = the displayed time window — trigger-relative SECONDS on the
     *  trigger-anchored live/frozen view, absolute FRAMES whenever the render
     *  places the window absolutely (file mode OR scrolled back from the live
     *  tip; same condition as the render's nav branch).  Mode/model switches
     *  clear the undo stack, so mementos never cross the two.  Y = each
     *  channel's displayed voltage window; both round-trip losslessly to
     *  t/div + trigger frac + V/div + offsetFrac. */
    @Override
    protected ZoomState captureZoomState() {
        Preferences prefs = Preferences.instance();
        double window = prefs.getOscTimePerDiv() * DIVISIONS_X;
        double xMin;
        double xMax;
        if (isAbsoluteWindow()) {
            SignalBufferReader r = reader;
            if (r == null || controller == null) return null;
            xMax = r.getWritePos() - getViewBackOffsetFrames();
            xMin = xMax - window * r.getSampleRate();
        } else {
            double p = prefs.getOscTriggerPositionFrac();
            xMin = -p * window;
            xMax = (1 - p) * window;
        }
        double lOff  = prefs.getOscLeftOffsetFrac();
        double lVdiv = prefs.getOscLeftVoltsPerDiv();
        double rOff  = prefs.getOscRightOffsetFrac();
        double rVdiv = prefs.getOscRightVoltsPerDiv();
        return new ZoomState(xMin, xMax,
                new double[] { (lOff - 1) * DIVISIONS_Y * lVdiv, (rOff - 1) * DIVISIONS_Y * rVdiv },
                new double[] {  lOff      * DIVISIONS_Y * lVdiv,  rOff      * DIVISIONS_Y * rVdiv });
    }

    @Override
    protected boolean applyZoomState(ZoomState s) {
        Preferences prefs = Preferences.instance();
        // The trigger level is stored as a SCREEN fraction — after the vertical
        // ranges change, the same fraction is a different VOLTAGE, so the
        // trigger would re-fire at a different waveform point and shift the
        // trigger-anchored window sideways.  Hold the trigger voltage invariant:
        // volts under the OLD trigger-channel mapping → fraction under the NEW.
        boolean trigLeft = prefs.getOscTriggerChannel() == Channel.L;
        double  oldOff   = trigLeft ? prefs.getOscLeftOffsetFrac()  : prefs.getOscRightOffsetFrac();
        double  oldVdiv  = trigLeft ? prefs.getOscLeftVoltsPerDiv() : prefs.getOscRightVoltsPerDiv();
        double  levelVolts = (oldOff - prefs.getOscTriggerLevelFrac()) * DIVISIONS_Y * oldVdiv;
        applyChannelVoltageRange(prefs, true,  s.yMin()[0], s.yMax()[0]);
        applyChannelVoltageRange(prefs, false, s.yMin()[1], s.yMax()[1]);
        double newOff  = trigLeft ? prefs.getOscLeftOffsetFrac()  : prefs.getOscRightOffsetFrac();
        double newVdiv = trigLeft ? prefs.getOscLeftVoltsPerDiv() : prefs.getOscRightVoltsPerDiv();
        if (newVdiv > 0) {
            // Virtual-capable like the trigger offset: a zoom that excludes the
            // trigger voltage parks the level outside [0,1] — honest, undoable.
            prefs.setOscTriggerLevelFrac(newOff - levelVolts / (DIVISIONS_Y * newVdiv));
        }
        double span = s.xMax() - s.xMin();
        if (span > 0) {
            // The toolbar t/div field clamps at T_PER_DIV_MIN — writing below it
            // would let the field's reverse binding desync pref and display, so
            // the zoom obeys the same floor.  When the floor engages, the
            // trigger branch keeps the selection's left edge (p pins xMin);
            // the absolute branch widens around the selection's centre.
            if (isAbsoluteWindow()) {
                SignalBufferReader r = reader;
                if (r != null && controller != null) {
                    // Centre first: the t/div binding chain ends in applyViewState,
                    // which must already see the new centre.  The explicit call
                    // after covers an unchanged t/div (Property.set no-ops).
                    controller.setViewCenterFrames((s.xMin() + s.xMax()) / 2.0);
                    prefs.setOscTimePerDiv(Math.max(ScopeTabControl.T_PER_DIV_MIN,
                            span / r.getSampleRate() / DIVISIONS_X));
                    controller.applyViewState();
                }
            } else {
                double tDiv = Math.max(ScopeTabControl.T_PER_DIV_MIN, span / DIVISIONS_X);
                if (controller != null) {
                    // The view is trigger-anchored here (backOffset == 0), but a
                    // stale scroll centre from an earlier excursion would make the
                    // t/div binding's applyViewState re-derive a back-offset
                    // against the advanced writePos and flip the render into the
                    // absolute nav branch mid-commit — pin the centre to
                    // follow-latest, which is what the screen actually shows.
                    controller.setViewCenterFrames(-1.0);
                }
                seedHeldZoomAnchor(s, tDiv, prefs);
                prefs.setOscTimePerDiv(tDiv);
                prefs.setOscTriggerPositionFrac(-s.xMin() / (tDiv * DIVISIONS_X));   // virtual-capable
            }
        }
        prefs.save();
        redraw();
        return true;
    }

    /** Trigger-less held frame (entry snapshot): its renderer ignores the
     *  trigger-position pref and anchors a t/div change at {@link
     *  #heldZoomAnchorX} — seed it with the fixed point of the window change,
     *  {@code anchor/width = (newXMin - curXMin) / (curSpan - newSpan)}, so
     *  both a committed selection and its Ctrl+Z inverse land exactly (a
     *  linear map and its inverse share the fixed point).  Skipped when the
     *  t/div pref won't change (nothing would consume the anchor). */
    private void seedHeldZoomAnchor(ZoomState s, double tDiv, Preferences prefs) {
        if (!singleHeld || !Double.isNaN(capturedTriggerLocal)) return;
        if (tDiv == prefs.getOscTimePerDiv()) return;
        ZoomState cur = captureZoomState();
        Rectangle area = zoomableArea();
        if (cur == null || area == null || area.width <= 0) return;
        double denom = (cur.xMax() - cur.xMin()) - tDiv * DIVISIONS_X;
        if (denom == 0) return;
        int anchor = (int) Math.round(area.width * (s.xMin() - cur.xMin()) / denom);
        heldZoomAnchorX = Math.max(0, Math.min(area.width, anchor));
    }

    /** Whether the render places the time window ABSOLUTELY (right edge =
     *  writePos − backOffset) rather than trigger-anchored — the exact
     *  condition of the paint's nav branch, which the zoom's X model must
     *  match. */
    private boolean isAbsoluteWindow() {
        return fileMode || getViewBackOffsetFrames() > 0;
    }

    /** Voltage window → V/div + offsetFrac for one channel: {@code vDiv =
     *  span/10}, {@code offsetFrac = top/span} (the zero line's screen
     *  fraction).  The toolbar V/div fields follow via their reverse binding. */
    private void applyChannelVoltageRange(Preferences prefs, boolean left, double bottomV, double topV) {
        double span = topV - bottomV;
        if (span <= 0) return;
        // Same floor as the toolbar V/div field — see the t/div clamp rationale.
        double vDiv = Math.max(ScopeTabControl.V_PER_DIV_MIN, span / DIVISIONS_Y);
        double off  = topV / (vDiv * DIVISIONS_Y);
        if (left) {
            prefs.setOscLeftOffsetFrac(off);
            prefs.setOscLeftVoltsPerDiv(vDiv);
        } else {
            prefs.setOscRightOffsetFrac(off);
            prefs.setOscRightVoltsPerDiv(vDiv);
        }
    }

    /** The scope plots edge-to-edge (no axis margins), so the selection maps
     *  linearly onto the current window on both axes — per channel vertically,
     *  since the rect is screen-space and applies to every scale alike. */
    @Override
    protected ZoomState zoomStateForRect(Rectangle sel) {
        Rectangle area = zoomableArea();
        if (area == null || area.width <= 0 || area.height <= 0) return null;
        ZoomState cur = captureZoomState();
        if (cur == null) return null;
        double fx0 = (sel.x - area.x) / (double) area.width;
        double fx1 = (sel.x + sel.width - area.x) / (double) area.width;
        double xSpan = cur.xMax() - cur.xMin();
        double fy0 = (sel.y - area.y) / (double) area.height;
        double fy1 = (sel.y + sel.height - area.y) / (double) area.height;
        double[] nyMin = new double[2];
        double[] nyMax = new double[2];
        for (int ch = 0; ch < 2; ch++) {
            double top  = cur.yMax()[ch];
            double span = top - cur.yMin()[ch];
            nyMax[ch] = top - fy0 * span;
            nyMin[ch] = top - fy1 * span;
        }
        return new ZoomState(cur.xMin() + fx0 * xSpan, cur.xMin() + fx1 * xSpan, nyMin, nyMax);
    }

    /** Slider handles, header buttons and the hoverable labels stay clickable —
     *  a selection drag can't start on them. */
    @Override
    protected boolean isRectZoomBlockedAt(int x, int y) {
        return offsetSliderBounds.contains(x, y)
                || triggerLevelBounds.contains(x, y)
                || triggerPosBounds.contains(x, y)
                || headerButtonAt(x, y) != null
                || timeLeftLabelBounds.contains(x, y)
                || timeRightLabelBounds.contains(x, y)
                || leftMaxLabelBounds.contains(x, y)
                || leftMinLabelBounds.contains(x, y)
                || rightMaxLabelBounds.contains(x, y)
                || rightMinLabelBounds.contains(x, y);
    }

    /** Pointer moved — drives a slider drag or a rect-zoom selection drag, else
     *  updates the hover cursor / tooltip on the {@link #pointerHost} (the
     *  visible widget). */
    public void pointerMove(int x, int y) {
        if (draggingSlider != null) {
            updateSliderFromMouse(x, y);
            return;
        }
        rectZoomPointerMove(x, y);   // rubber-band update (no-op unless dragging)
        // Mid-selection the hover hit-tests stay quiet — no cursor flips or
        // tooltips while the rubber band crosses sliders / header buttons.
        if (isRectZoomDragActive()) return;
        int cursorId;
        String tip;
        ToolButton headerBtn = headerButtonAt(x, y);
        if (headerBtn != null) {
            // GPU path: the view is hidden and the header buttons are painted into the
            // GL canvas, so their pointer events (and tooltips) come through here rather
            // than to the real widgets — surface the hovered button's own tooltip.
            cursorId = SWT.CURSOR_HAND;
            tip = headerBtn.getToolTipText();
        } else if (offsetSliderBounds.contains(x, y)) {
            cursorId = SWT.CURSOR_SIZENS;
            tip = I18n.t("scope.offset.slider.tooltip");
        } else if (!fileMode && triggerLevelBounds.contains(x, y)) {
            cursorId = SWT.CURSOR_SIZENS;
            tip = I18n.t("scope.trigger.level.tooltip");
        } else if (!fileMode && triggerPosBounds.contains(x, y)) {
            cursorId = SWT.CURSOR_SIZEWE;
            tip = I18n.t("scope.trigger.position.tooltip");
        } else if (leftMaxLabelBounds.contains(x, y)
                || leftMinLabelBounds.contains(x, y)
                || rightMaxLabelBounds.contains(x, y)
                || rightMinLabelBounds.contains(x, y)) {
            cursorId = SWT.CURSOR_ARROW;
            tip = I18n.t("scope.voltage.label.tooltip");
        } else if (timeLeftLabelBounds.contains(x, y)
                || timeRightLabelBounds.contains(x, y)) {
            cursorId = SWT.CURSOR_ARROW;
            tip = I18n.t("scope.time.label.tooltip");
        } else {
            cursorId = SWT.CURSOR_ARROW;
            tip = null;
        }
        Cursor c = getDisplay().getSystemCursor(cursorId);
        if (pointerHost.getCursor() != c) pointerHost.setCursor(c);
        String current = pointerHost.getToolTipText();
        if (tip == null) {
            if (current != null) pointerHost.setToolTipText(null);
        } else if (!tip.equals(current)) {
            pointerHost.setToolTipText(tip);
        }
    }

    /** Routes pointer input + repaints to the GPU surface: the GL canvas forwards
     *  pointer events here, cursor/tooltip + pointer geometry use {@code host}, and
     *  {@link #redraw()} renders through {@code repaint}.  The CPU path keeps the
     *  {@code this} / direct-redraw defaults. */
    public void attachGlInput(Control host, Runnable repaint, Runnable overlayRepaint) {
        this.pointerHost      = host;
        this.glRepaint        = repaint;
        this.glOverlayRepaint = overlayRepaint;
        // The GL canvas is now the interaction control — re-home the rect-zoom
        // focus/hover tracking (its mouse input still arrives via the pointer
        // funnel the pane forwards, so hookMouse stays off).
        installRectZoom(host, false);
        // GPU path: the header buttons are drawn into the GL canvas, so the real
        // Toolbar widget must be hidden.  On GTK a SWT.NO_BACKGROUND composite still
        // paints the theme (grey) background and a NO_BACKGROUND child's window isn't
        // reliably unmapped when the parent view is hidden — leaving a grey strip
        // behind the GL-drawn buttons.  Hiding it explicitly clears that; the
        // per-button getVisible() flags (and bounds) the overlay reads stay intact.
        if (headerBar != null && !headerBar.isDisposed()) headerBar.setVisible(false);
    }

    private void updateSliderFromMouse(int mouseX, int mouseY) {
        Rectangle area = ((Scrollable) pointerHost).getClientArea();
        int w = area.width;
        int h = area.height;
        if (w <= 0 || h <= 0 || draggingSlider == null) return;
        Preferences prefs = Preferences.instance();
        switch (draggingSlider) {
            case OFFSET: {
                double frac = ScopeFormat.clamp01((double) mouseY / h);
                if (prefs.getOscMeasurementChannel() == Channel.L) {
                    prefs.setOscLeftOffsetFrac(frac);
                } else {
                    prefs.setOscRightOffsetFrac(frac);
                }
                break;
            }
            case TRIGGER_LEVEL:
                prefs.setOscTriggerLevelFrac(ScopeFormat.clamp01((double) mouseY / h));
                break;
            case TRIGGER_POSITION:
                // Absolute: the line follows the cursor (centred under it), clamped to
                // [0,1] — so grabbing a virtual (edge-pinned) handle recaptures it to the
                // cursor, and the handle can never be pushed off the view (spec).
                prefs.setOscTriggerPositionFrac(ScopeFormat.clamp01((double) mouseX / w));
                break;
        }
        redraw();
    }

    /** Whether the window anchored at {@code anchorAbs}/{@code offsetFrac} sits fully
     *  inside the current read buffer (with sinc padding) — a live NORMAL hold tracks
     *  the ring while it does, then falls back to the captured copy. */
    private boolean holdFitsInBuffer(double anchorAbs, double offsetFrac, int displaySamples,
                                     long bufStartAbs, int available) {
        double left = nav.viewLeftAbs(anchorAbs, offsetFrac, displaySamples) - bufStartAbs;
        return left >= Lanczos.LANCZOS_PADDING
            && left + displaySamples + Lanczos.LANCZOS_PADDING <= available;
    }

    private void drawWaveforms(MeasurementPainter gc, int w, int h) {
        // Screenshot view: render the captured frame verbatim (a carbon copy
        // of the live trace), never re-reading the buffer.
        if (frozenFrame != null) {
            RenderedFrame f = frozenFrame;
            // The residual (if any) is already baked into the snapshot arrays, so
            // renderTraces skips the fit on this path — sample rate is unused here.
            renderTraces(gc, w, h, f.left, f.right, f.len, f.dispStart, f.subSampleOffset,
                         f.dispCount, f.showL, f.showR, f.leftVDiv, f.rightVDiv,
                         f.sincL, f.sincR, f.dcL, f.dcR, 0.0);
            return;
        }
        Preferences prefs = Preferences.instance();
        double timePerDiv = prefs.getOscTimePerDiv();
        double leftVDiv   = prefs.getOscLeftVoltsPerDiv();
        double rightVDiv  = prefs.getOscRightVoltsPerDiv();
        boolean showL = prefs.isOscLeftChannelEnabled();
        boolean showR = prefs.isOscRightChannelEnabled();
        if (!showL && !showR) return;

        Channel triggerCh   = prefs.getOscTriggerChannel();
        TriggerEdge    triggerEdge = prefs.getOscTriggerEdge();
        TriggerMode    triggerMode = prefs.getOscTriggerMode();

        // On a trigger-mode switch: AUTO free-runs (drops any frozen frame).
        // Entering NORMAL/SINGLE with nothing frozen yet (e.g. from AUTO) freezes
        // the frame currently on screen (captureEntryFrame, applied after the
        // read) so the trace persists instead of blanking until the new mode's
        // first trigger.  Switching N<->S keeps the already-held frame as-is — NO
        // re-capture, which would jump the trace to a freshly grabbed window.
        boolean captureEntryFrame = false;
        if (lastTriggerMode != triggerMode) {
            singleArmed = false;
            if (triggerMode == TriggerMode.AUTO) singleHeld = false;
            else if (!singleHeld)                captureEntryFrame = true;
            lastTriggerMode = triggerMode;
        }

        // Default this paint to "frozen content"; branches below flip the
        // flag when they render a genuinely new frame.
        lastFrameWasNew = false;

        // SINGLE that isn't armed never runs live: it shows the held frozen
        // frame if there is one (independent of the ring buffer, so it stays
        // visible even after capture stops), otherwise it leaves the pane blank.
        // The trace changes only when the user arms a shot and its trigger fires
        // — it must NOT free-run like AUTO and must NOT auto-capture a fresh frame.
        boolean sincL = prefs.isOscLeftSincInterpEnabled();
        boolean sincR = prefs.isOscRightSincInterpEnabled();
        boolean acL = prefs.isOscLeftAcMode();
        boolean acR = prefs.isOscRightAcMode();
        if (triggerMode == TriggerMode.SINGLE && !singleArmed && !captureEntryFrame) {
            if (singleHeld) {
                renderHeldCapturedFrame(gc, w, h, showL, showR,
                        leftVDiv, rightVDiv, sincL, sincR, acL, acR);
            }
            return;
        }

        SignalBufferReader b = reader;
        if (b == null) {
            // No live source, but a captured SINGLE frame may still be drawable.
            if (singleHeld) {
                renderHeldCapturedFrame(gc, w, h, showL, showR,
                        leftVDiv, rightVDiv, sincL, sincR, acL, acR);
            }
            return;
        }

        double windowSeconds = timePerDiv * DIVISIONS_X;
        int displaySamples = (int) Math.round(windowSeconds * b.getSampleRate());
        if (displaySamples < 2) {
            if (singleHeld) renderHeldCapturedFrame(gc, w, h, showL, showR,
                    leftVDiv, rightVDiv, sincL, sincR, acL, acR);
            return;
        }

        // Trigger position slider: 0 = trigger at left edge of display,
        // 0.5 = centred, 1 = right edge.  Computed early because both the
        // read size and the trigger search range depend on it.  The pref can be
        // driven OUTSIDE [0,1] (a virtual offset) by pan/zoom on a STOPPED scope;
        // the live trigger search + window keep using the clamped value, while
        // the frozen read-follows-view path below uses the real one so the held
        // frame can pan across the whole captured buffer.
        double triggerPosFracReal = prefs.getOscTriggerPositionFrac();
        double triggerPosFrac     = ScopeFormat.clamp01(triggerPosFracReal);

        // Read window must span at least one full display window before the
        // trigger AND one full display window after, so the trigger position
        // slider can be dragged from edge to edge without the display falling
        // off the buffer.  Plus Lanczos.LANCZOS_PADDING on each side for the sinc
        // kernel, plus an extra lookback so the trigger search reliably
        // contains a rising edge even for low-frequency signals.
        int extraLookback = Math.min(b.getSampleRate(), ScopeMeasurementWorker.MEAS_MAX_SAMPLES);
        int wanted = 2 * displaySamples + 2 * Lanczos.LANCZOS_PADDING + extraLookback;
        // When SINGLE is armed, capture both channels so toggling L/R after
        // the freeze still shows real data instead of a stale buffer.
        boolean armedSingle = (triggerMode == TriggerMode.SINGLE) && singleArmed;
        boolean needL = showL || triggerCh == Channel.L || armedSingle;
        boolean needR = showR || triggerCh == Channel.R || armedSingle;
        if (leftBuf.length < wanted) {
            leftBuf  = new float[wanted];
            rightBuf = new float[wanted];
        }
        // When the user has scrolled back via the navigation slider,
        // shift the read's right edge by that many frames.  Offset 0
        // keeps the historical "follow latest" behaviour.
        long latestAbs   = b.getWritePos();
        long viewEndAbs;
        double frozenViewLeftAbs = Double.NaN;
        // File / scrolled-back window right edge (absolute, fractional) — the exact
        // value the edge time marks show; the nav render below anchors the window here.
        double scrollViewEndAbs = (double) latestAbs - viewBackOffsetFrames;
        if (frozen && lastTriggerAbsPos >= 0) {
            // Stopped scope: the read FOLLOWS the held-anchor view so pan/zoom roam
            // the whole captured buffer, not just the live tip.  The trigger sits at
            // the (possibly virtual) triggerPosFracReal; the window's right edge plus
            // sinc pad bounds the read end, clamped to the newest captured sample.
            frozenViewLeftAbs = (lastTriggerAbsPos + lastTriggerSubSampleOffset)
                              - (double) displaySamples * triggerPosFracReal;
            long viewEnd = (long) Math.floor(frozenViewLeftAbs) + displaySamples + Lanczos.LANCZOS_PADDING;
            viewEndAbs = Math.min(latestAbs, viewEnd);
        } else {
            // Read LANCZOS_PADDING past the window's right edge (where that data
            // exists) so the sinc kernel keeps full context at the edge without
            // shifting the drawn window away from scrollViewEndAbs.
            viewEndAbs = Math.min(latestAbs,
                    (long) Math.floor(scrollViewEndAbs) + Lanczos.LANCZOS_PADDING);
        }
        int available = b.readEndingAt(viewEndAbs, wanted,
                needL ? leftBuf  : null,
                needR ? rightBuf : null);
        if (available < 2) {
            // Buffer not ready yet (e.g. right after Record start): keep the
            // held frozen frame on screen instead of blanking, so NORMAL/SINGLE
            // don't wipe the last trace before the first new trigger.
            if (singleHeld) renderHeldCapturedFrame(gc, w, h, showL, showR,
                    leftVDiv, rightVDiv, sincL, sincR, acL, acR);
            return;
        }

        // HF spike removal (80 kHz LPF) then per-channel mains-hum
        // suppression, applied to the raw read window before trigger search,
        // drawing, and SINGLE-frame capture all read leftBuf/rightBuf.  The
        // displayed span sits inside this window's pre-roll, so the combs'
        // start transients stay off-screen left.
        applyHfLowPass(b.getSampleRate(), available, needL, needR);
        long bufStartAbs = viewEndAbs - available;
        applyMainsSuppression(b.getSampleRate(), available, needL, needR, bufStartAbs);

        // Trigger-mode switch into NORMAL/SINGLE: freeze the frame that was on
        // screen so the trace persists across the switch instead of blanking until
        // the new mode's first trigger.
        if (captureEntryFrame) {
            // Re-grab the EXACT window the last live paint showed (by absolute
            // position), NOT the latest — which would be newer samples that
            // arrived since (a visible shift on a sweep / fast time-base).
            int entryStart;
            if (lastLiveDispStartAbs >= 0) {
                entryStart = (int) (lastLiveDispStartAbs - bufStartAbs);
            } else {
                int entryPad = Math.min(Lanczos.LANCZOS_PADDING, Math.max(0, available - displaySamples));
                entryStart = available - entryPad - displaySamples;
            }
            entryStart = Math.max(0, Math.min(entryStart, available - displaySamples));
            captureSingleFrame(entryStart, displaySamples, available,
                    lastLiveDispStartAbs >= 0 ? lastLiveSubSampleOffset : 0.0, Double.NaN);
            if (singleHeld) {
                renderHeldCapturedFrame(gc, w, h, showL, showR,
                        leftVDiv, rightVDiv, sincL, sincR, acL, acR);
            }
            return;
        }

        // Navigation mode: bypass trigger/hold when either
        //   (a) the user has scrolled back from the live tip via the
        //       slider (viewBackOffsetFrames > 0), or
        //   (b) the view is showing a static loaded signal (fileMode).
        // Triggering on either of these produces visible jumps when
        // scrolling or zooming because trigger anchors to a different
        // sample for each search window — the user reads this as
        // "signal jumps left/right".  Right-edge anchoring is stable
        // across both operations.
        if (viewBackOffsetFrames > 0 || fileMode) {
            // The window is placed ABSOLUTELY: its right edge sits at scrollViewEndAbs
            // (= writePos − viewBackOffsetFrames), exactly where the edge time marks
            // point, so every scroll step moves the trace 1:1, sub-sample included.
            // Deriving the window from `available` minus an elastic sinc pad instead
            // ate the first LANCZOS_PADDING samples of scrolling near the file start
            // and then drew the trace that far left of the marks.  dispCount stays the
            // full displaySamples — the renderer blanks columns the buffer can't fill.
            ScopeNav.Viewport vp = nav.viewport(scrollViewEndAbs, 1.0, displaySamples, bufStartAbs);
            if (vp.dispCount() < 2) return;
            double dcLn = acL ? acDcMean(true)  : 0.0;
            double dcRn = acR ? acDcMean(false) : 0.0;
            renderTraces(gc, w, h, leftBuf, rightBuf, available,
                         vp.dispStart(), vp.subSampleOffset(), vp.dispCount(),
                         showL, showR, leftVDiv, rightVDiv,
                         sincL, sincR, dcLn, dcRn, b.getSampleRate());
            return;
        }

        // Trigger search range: keep the resulting display window
        // [windowLeftT, windowLeftT + displaySamples) plus Lanczos.LANCZOS_PADDING on
        // each side fully inside the read buffer.  The split is asymmetric
        // when the trigger-position slider isn't centred — at triggerPosFrac
        // = 0 the display extends one full window to the right of the
        // trigger, so the trigger has to lie at least that far back from
        // `available`; symmetric reasoning bounds the left side at
        // triggerPosFrac = 1.
        int leftHalf  = (int) Math.ceil(displaySamples * triggerPosFrac);
        int rightHalf = (int) Math.ceil(displaySamples * (1.0 - triggerPosFrac));
        int searchFrom = Math.max(1, Lanczos.LANCZOS_PADDING + leftHalf + 1);
        int searchTo   = available - rightHalf - Lanczos.LANCZOS_PADDING;
        float[] triggerData = (triggerCh == Channel.L) ? leftBuf : rightBuf;
        boolean rising = (triggerEdge == TriggerEdge.RISE);
        // Trigger uses the TRIGGER channel's sinc setting for sub-sample
        // interpolation when searching for the zero-crossing.
        boolean sincEnabled = (triggerCh == Channel.L) ? sincL : sincR;
        // Trigger level comes from the user-controlled slider (right edge of
        // the canvas).  The slider value is a fraction of canvas height; the
        // corresponding normalised sample value depends on the trigger
        // channel's V/div and its current vertical offset (the channel's
        // zero-line lives at offsetFrac × h on screen).
        double triggerVDiv = (triggerCh == Channel.L) ? leftVDiv : rightVDiv;
        double triggerPeakVolts = prefs.getAdcFsVoltageRms() * Math.sqrt(2.0);
        double pixelsPerDivY = (double) h / DIVISIONS_Y;
        double vScaleTrig = triggerPeakVolts / triggerVDiv * pixelsPerDivY;
        double triggerCenterY = h * ((triggerCh == Channel.L)
                ? prefs.getOscLeftOffsetFrac() : prefs.getOscRightOffsetFrac());
        double triggerLevelY = h * prefs.getOscTriggerLevelFrac();
        float triggerLevel = (float) ((triggerCenterY - triggerLevelY) / vScaleTrig);
        // AC-coupled trigger channel: the on-screen trace has the DC bias
        // removed, so the trigger-level slider value is the AC voltage at
        // that screen Y.  The raw samples in {@code triggerData} still carry
        // the bias, so to make the comparator fire where the user sees the
        // dashed level line we add the channel's DC bias back into the
        // threshold here.  Without this, a small AC signal sitting on top of
        // a (typical) ~100 µV ADC offset never crosses the user-set zero and
        // the trigger never fires in AC mode.
        boolean acTrig = (triggerCh == Channel.L) ? acL : acR;
        // AC trigger shift: pull the worker-published, ≥500 ms-averaged DC
        // mean.  The worker waits AC_WARMUP_NANOS before its first publish,
        // so during that initial period the value is 0 (history empty +
        // reset lastXMean) and the AC threshold falls back to the literal
        // user-set level.  Once the worker is publishing, the threshold
        // tracks the channel's true DC bias.
        double trigDcOffset = acTrig ? acDcMean(triggerCh == Channel.L) : 0.0;
        float effectiveTriggerLevel = (float) (triggerLevel + trigDcOffset);
        // Hysteresis in normalised sample units (data[] is in [-1, +1]; multiply
        // by peakVolts to get volts).  The trigger channel's V/div sets the
        // div-to-volts conversion.
        float triggerHysteresis = prefs.isOscTriggerHysteresisEnabled()
                ? (float) (prefs.getOscTriggerHysteresisDiv() * triggerVDiv / triggerPeakVolts)
                : 0f;
        // In dual-tone mode the carrier crosses the trigger level many
        // times per |F1-F2| beat cycle; without dedicated handling the
        // anchor would jump between adjacent carrier zeros on every
        // paint and the slow envelope would smear.  Reconstruct an
        // AC-coupled |F1-F2| envelope (in the trigger channel's
        // volts-per-div units), then run the standard trigger search
        // on it.  The user's trigger-level slider and hysteresis stay
        // honoured — the level offsets above or below the envelope's
        // mid-line in the same units the raw trace is drawn in, so
        // moving the slider re-positions where on the beat envelope
        // the trigger fires.  Sub-sample refinement is dropped (the
        // Lanczos refiner is tuned for the raw capture's sample rate,
        // not the band-limited envelope).
        float[] effectiveData = triggerData;
        boolean effectiveSinc = sincEnabled;
        if (prefs.getGenSignalForm().isDualTone()) {
            int sr = b.getSampleRate();
            // Reconstruct from the frequencies the generator actually EMITS:
            // snapped to the FFT-bin grid when "snap generator freq to FFT
            // bin" is on, exactly as GeneratorController snaps them.  Using
            // the raw commanded values makes |F1-F2| slightly off, so the
            // beat overlay drifts out of phase with the capture when snap is on.
            double f1 = FftBinSnap.snapIfEnabled(prefs, GenSignalForm.DUAL_TONE, sr, prefs.getGenDualToneFreq1Hz());
            double f2 = FftBinSnap.snapIfEnabled(prefs, GenSignalForm.DUAL_TONE, sr, prefs.getGenDualToneFreq2Hz());
            if (f1 > 0 && f2 > 0 && sr > 0 && Math.abs(f2 - f1) > 0) {
                effectiveData = reconstructBeatSignal(triggerData, available, sr, f1, f2);
                effectiveSinc = false;
            }
        }
        double triggerFrac;
        if (searchTo <= searchFrom) {
            triggerFrac = -1.0;
        } else if (prefs.getOscTriggerType() == TriggerType.GLITCH) {
            // Discontinuity trigger: fires where the signal breaks the sinusoid-
            // recurrence prediction — level steps AND slope splices, anywhere on
            // the wave, regardless of direction.  ↑ anchors the display on the
            // glitch's start, ↓ on its end.  Level / hysteresis / sinc refine
            // don't apply.  The TRIGGER channel's own measured frequency pins
            // the recurrence exactly (noise-floor baseline at any f) — the
            // worker measures both channels.
            int glitchSr = b.getSampleRate();
            SignalMeasurements meas = measWorker.getLastMeasResult(triggerCh == Channel.L);
            double measHz = (meas != null) ? meas.getFrequency() : Double.NaN;
            double omega = (measHz > 0 && measHz < glitchSr / 2.0)
                    ? 2.0 * Math.PI * measHz / glitchSr : Double.NaN;
            triggerFrac = ScopeTrigger.findGlitch(effectiveData, searchFrom, searchTo, rising,
                    (int) Math.round(glitchSr * TimeDiscontinuityDetector.MERGE_SECONDS), omega);
        } else {
            triggerFrac = ScopeTrigger.find(effectiveData, available, searchFrom, searchTo,
                              effectiveTriggerLevel, rising, effectiveSinc, triggerHysteresis);
        }
        boolean foundTrigger = (triggerFrac >= 0);

        // ---------------------------------------------------------------
        // Unified positioning (ScopeNav): pick the absolute anchor + screen
        // offset for THIS mode, then map it to a render window.  drawTrace blanks
        // any column the buffer can't fill, so a virtual offset or a window
        // hanging off the buffer edge just shows empty space — it never stretches
        // the trace or jumps to the right edge.
        // ---------------------------------------------------------------
        int dispStart;
        int dispCount;
        double subSampleOffset;
        double anchorAbs;
        double offsetFrac;
        if (frozen) {
            if (lastTriggerAbsPos < 0) return;          // nothing to anchor (shouldn't happen post-freeze)
            anchorAbs  = lastTriggerAbsPos + lastTriggerSubSampleOffset;
            offsetFrac = triggerPosFracReal;            // virtual-capable on a stopped scope
        } else if (foundTrigger) {
            // Live trigger: re-anchor on it and remember it for the hold / freeze paths.
            int triggerInt = (int) Math.floor(triggerFrac);
            lastTriggerAbsPos          = bufStartAbs + triggerInt;
            lastTriggerSubSampleOffset = triggerFrac - triggerInt;
            anchorAbs  = bufStartAbs + triggerFrac;
            offsetFrac = triggerPosFracReal;            // virtual-capable: shift+wheel can carry it off-screen
            lastFrameWasNew = true;                     // every triggered paint is a fresh frame (cap/s)
        } else if (triggerMode == TriggerMode.SINGLE && singleArmed) {
            // Armed, still waiting: hold the captured frame or stay blank — never free-run.
            if (singleHeld) renderHeldCapturedFrame(gc, w, h, showL, showR,
                    leftVDiv, rightVDiv, sincL, sincR, acL, acR);
            return;
        } else if (triggerMode == TriggerMode.AUTO) {
            // Free-run (no trigger seen): the fresh stream, right-anchored so the newest
            // sample fills to the right edge — never a blank future half.  There is no
            // trigger to scroll the offset against, so this view isn't pannable; put the
            // trigger level on the signal (AUTO === NORMAL) or stop to scroll history.
            int rightPad = Math.min(Lanczos.LANCZOS_PADDING, Math.max(0, available - displaySamples));
            anchorAbs  = (double) latestAbs - rightPad;
            offsetFrac = 1.0;
            lastFrameWasNew = true;
        } else if (lastTriggerAbsPos >= 0
                && holdFitsInBuffer(lastTriggerAbsPos + lastTriggerSubSampleOffset,
                                    triggerPosFracReal, displaySamples, bufStartAbs, available)) {
            // NORMAL: hold the last trigger while the frame is still in the live ring.
            anchorAbs  = lastTriggerAbsPos + lastTriggerSubSampleOffset;
            offsetFrac = triggerPosFracReal;
        } else if (singleHeld) {
            // NORMAL frame scrolled out of the ring → the frozen captured copy.
            renderHeldCapturedFrame(gc, w, h, showL, showR,
                    leftVDiv, rightVDiv, sincL, sincR, acL, acR);
            return;
        } else {
            return;                                     // NORMAL, never triggered → blank
        }

        ScopeNav.Viewport vp = nav.viewport(anchorAbs, offsetFrac, displaySamples, bufStartAbs);
        dispStart       = vp.dispStart();
        subSampleOffset = vp.subSampleOffset();
        dispCount       = vp.dispCount();
        // The signal follows the offset directly (both trace and trigger travel together);
        // drawTrace blanks any column the buffer can't fill, so scrolling the trigger off
        // the screen just shows the buffered signal on the other side until the true edge.

        // SINGLE / NORMAL persistence: capture the displayed frame (clamped in-buffer)
        // on a fresh trigger so the trace can be held after triggers stop — and disarm
        // SINGLE so its Start toggle pops back out.
        if (foundTrigger && !frozen
                && ((triggerMode == TriggerMode.SINGLE && singleArmed) || triggerMode == TriggerMode.NORMAL)) {
            int capStart = Math.max(0, Math.min(dispStart, available - displaySamples));
            captureSingleFrame(capStart, displaySamples, available, subSampleOffset, triggerFrac);
            if (triggerMode == TriggerMode.SINGLE && singleArmed) {
                singleArmed = false;
                MessageBus.instance().publish(Events.SCOPE_SINGLE_DISARMED);
            }
        }
        if (dispCount < 2) return;

        // AC DC removal: worker-published, ≥500 ms-averaged channel means.
        // Returns 0 during the AC_WARMUP_NANOS window after capture start
        // (worker hasn't published yet), so the trace shows the raw signal
        // — including any ADC settling transient — during that period.
        // After warmup the worker publishes a transient-free mean and the
        // trace snaps to centred.
        double dcL = acL ? acDcMean(true)  : 0.0;
        double dcR = acR ? acDcMean(false) : 0.0;
        // Remember this live window (absolute) so a later mode-switch freeze can
        // re-grab the exact frame on screen now, not newer samples.  Captured only
        // while live (not on frozen re-paints, which would drift the anchor as the
        // user pans/zooms the stopped frame).
        if (!frozen) {
            lastLiveDispStartAbs    = bufStartAbs + dispStart;
            lastLiveSubSampleOffset = subSampleOffset;
            // Anchor at the point ACTUALLY shown at the offset fraction — offsetFrac,
            // not the trigger slider — so an AUTO free-run frame (offsetFrac = 1.0,
            // right-aligned) freezes where it was displayed, not 0.7 div off.
            lastLiveAnchorAbs       = bufStartAbs + dispStart
                                    + Math.round(displaySamples * offsetFrac);
        }
        renderTraces(gc, w, h, leftBuf, rightBuf, available,
                     dispStart, subSampleOffset, dispCount,
                     showL, showR, leftVDiv, rightVDiv, sincL, sincR, dcL, dcR, b.getSampleRate());
        // Overlay the reconstructed |F1-F2| beat envelope on top of
        // the live trace — one per VISIBLE channel, from that channel's
        // own samples, in that channel's darkened colour.  Gated on
        // DUAL_TONE form AND the user's "Reconstructed beat" checkbox
        // inside drawBeatOverlays.
        drawBeatOverlays(gc, w, h, dispStart, subSampleOffset, dispCount,
                available, showL, showR, leftVDiv, rightVDiv, b.getSampleRate());
    }

    /** Paints the reconstructed |F1-F2| beat envelope on top of EACH visible
     *  channel's trace — reconstructed from that channel's own samples, in
     *  that channel's darkened trace colour, at that channel's V/div and
     *  offset, so every dual-tone trace carries its own visually paired
     *  envelope.  Renders only when the generator is in DUAL_TONE mode AND
     *  the user has the "Reconstructed beat" checkbox enabled in the Trigger
     *  tab.  Channels are reconstructed-then-drawn sequentially because
     *  {@link #reconstructBeatSignal} reuses one scratch buffer. */
    private void drawBeatOverlays(MeasurementPainter gc, int w, int h,
                                  int dispStart, double subSampleOffset, int dispCount,
                                  int dataLen, boolean showL, boolean showR,
                                  double leftVDiv, double rightVDiv, int sampleRate) {
        if (dispCount < 2 || w <= 0) return;
        Preferences prefs = Preferences.instance();
        if (!prefs.isOscShowReconstructedBeat()) return;
        if (!prefs.getGenSignalForm().isDualTone() || sampleRate <= 0) return;
        // No overlay when the generator is silent — the captured signal then
        // has no |F1−F2| beat and the reconstruction would trace noise.
        if (!Boolean.TRUE.equals(MessageBus.instance().request(Events.GENERATOR_RUNNING))) {
            return;
        }
        double f1 = FftBinSnap.snapIfEnabled(prefs, GenSignalForm.DUAL_TONE, sampleRate,
                prefs.getGenDualToneFreq1Hz());
        double f2 = FftBinSnap.snapIfEnabled(prefs, GenSignalForm.DUAL_TONE, sampleRate,
                prefs.getGenDualToneFreq2Hz());
        if (!(f1 > 0) || !(f2 > 0) || Math.abs(f2 - f1) <= 0) return;
        double pixelsPerDivY = (double) h / DIVISIONS_Y;
        double peakVolts     = prefs.getAdcFsVoltageRms() * Math.sqrt(2.0);
        float  lineWidth     = (float) prefs.getOscLineWidth();
        // A channel showing its RESIDUAL gets no beat overlay — the envelope
        // belongs to the tones the residual just removed, and at residual
        // zoom levels it would dwarf the trace.
        if (showL && !prefs.isOscLeftResidualEnabled()) {
            float[] beat = reconstructBeatSignal(leftBuf, dataLen, sampleRate, f1, f2);
            drawTrace(gc, beat, dataLen, dispStart, subSampleOffset, dispCount,
                    w, h, h * prefs.getOscLeftOffsetFrac(),
                    peakVolts / leftVDiv * pixelsPerDivY, lineWidth,
                    color(ColorRole.LEFT_BEAT),
                    /* sincEnabled = */ false, /* dcOffset = */ 0.0, /* dotDiameter = */ 0);
        }
        if (showR && !prefs.isOscRightResidualEnabled()) {
            float[] beat = reconstructBeatSignal(rightBuf, dataLen, sampleRate, f1, f2);
            drawTrace(gc, beat, dataLen, dispStart, subSampleOffset, dispCount,
                    w, h, h * prefs.getOscRightOffsetFrac(),
                    peakVolts / rightVDiv * pixelsPerDivY, lineWidth,
                    color(ColorRole.RIGHT_BEAT),
                    /* sincEnabled = */ false, /* dcOffset = */ 0.0, /* dotDiameter = */ 0);
        }
    }

    /** Reconstructs the signed beat modulator of a dual-tone signal,
     *  i.e. the slow {@code cos((F1-F2)/2·t)} factor that envelopes
     *  the high-frequency carrier in {@code sin(F1·t) + sin(F2·t) =
     *  2·sin((F1+F2)/2·t)·cos((F1-F2)/2·t)}.
     *
     *  <p>The reconstruction has three steps:
     *  <ol>
     *    <li>Rectify the input ({@code |data[i]|}) and boxcar-LP it
     *        to extract the abs envelope {@code |cos((F1-F2)/2·t)|}
     *        in the trigger channel's voltage units.  The boxcar's
     *        length is tuned so its main lobe lies above the carrier
     *        residue band but well below the {@code (F1+F2)} carrier
     *        sum, keeping the abs envelope clean.</li>
     *    <li>Walk the abs envelope and flip the sign every time it
     *        dips into a local minimum — those minima are exactly the
     *        modulator's zero crossings, so the sign of {@code cos}
     *        flips there.  A hysteresis-based "in-minimum" flag stops
     *        the same minimum from triggering multiple flips when
     *        ripple causes the envelope to wobble around the
     *        threshold.</li>
     *    <li>Subtract the residual mean so the output centres on
     *        zero — the scope's trigger level slider then offsets
     *        above or below the modulator's mid-line in the same
     *        volts-per-div units as the raw trace, and the user's
     *        slider keeps its physical meaning.</li>
     *  </ol>
     *
     *  <p>The output looks like a clean sine at frequency
     *  {@code (F1-F2)/2} with peak-to-peak ≈ the dual-tone signal's
     *  peak envelope, so an oscilloscope overlay of it traces the
     *  outer envelope of the carrier exactly as the eye expects. */
    private float[] reconstructBeatSignal(float[] data, int available,
                                          int sampleRate,
                                          double f1Hz, double f2Hz) {
        // Grow-only scratch: this runs EVERY paint while the form is
        // DUAL_TONE (it feeds the trigger), and three fresh ~100k+ float[]
        // per paint were ~60-180 MB/s of churn.  The result is consumed
        // within the same paint pass (debugBeatSignal is rebuilt per pass).
        if (beatOut == null || beatOut.length < available) {
            beatOut   = new float[available];
            beatTmp   = new float[available];
            beatAbsLp = new float[available];
        }
        float[] out = beatOut;
        Arrays.fill(out, 0, available, 0f);   // early returns must yield silence
        double beatHz = Math.abs(f2Hz - f1Hz);
        if (!(beatHz > 0) || sampleRate <= 0) return out;
        // L = quarter-beat-period samples — bracketed so a tiny beat
        // doesn't blow past the buffer length and a huge beat doesn't
        // collapse to L = 1.
        int lFromBeat   = (int) Math.round(sampleRate / (4.0 * beatHz));
        int lMin        = Math.max(2,
                (int) Math.round(sampleRate / Math.max(1.0, f1Hz + f2Hz)));
        int lMaxFromBuf = Math.max(2, available / 4);
        int L = Math.max(lMin, Math.min(lFromBeat, lMaxFromBuf));
        if (available <= 2 * L) return out;
        int halfL = L / 2;

        // --- Step 1: rectify + boxcar LP applied TWICE in cascade
        // → smoothed abs envelope (in volts).  A single boxcar has
        // {@code sinc} frequency response; cascading two of the same
        // length gives {@code sinc²}, with much deeper attenuation
        // in the stopband (carrier-residue band) for only a marginal
        // increase in main-lobe width.  Each pass emits at its
        // window centre, so the cascade still has zero net group
        // delay — the reconstructed modulator stays time-aligned
        // with the raw trace.  Boundaries of each pass are filled
        // with the nearest valid value so the next stage's running
        // sum doesn't ingest zero-initialised edge samples.
        float[] tmp = beatTmp;
        double sum = 0.0;
        for (int i = 0; i < L; i++) sum += Math.abs(data[i]);
        for (int i = L; i < available; i++) {
            tmp[i - halfL] = (float) (sum / L);
            sum += Math.abs(data[i]) - Math.abs(data[i - L]);
        }
        float tmpFirst = tmp[halfL];
        float tmpLast  = tmp[available - halfL - 1];
        for (int i = 0; i < halfL; i++) tmp[i] = tmpFirst;
        for (int i = available - halfL; i < available; i++) tmp[i] = tmpLast;

        float[] absLp = beatAbsLp;
        sum = 0.0;
        for (int i = 0; i < L; i++) sum += tmp[i];
        for (int i = L; i < available; i++) {
            absLp[i - halfL] = (float) (sum / L);
            sum += tmp[i] - tmp[i - L];
        }
        float firstValid = absLp[halfL];
        float lastValid  = absLp[available - halfL - 1];
        for (int i = 0; i < halfL; i++) absLp[i] = firstValid;
        for (int i = available - halfL; i < available; i++) absLp[i] = lastValid;

        // --- Step 2: raw-signal peak for amplitude scaling.  The peak
        // ≈ |F1| + |F2| (the two tones add constructively at every
        // envelope maximum), so the reconstructed modulator's peak
        // is matched to it and the cyan overlay touches the yellow
        // signal's outer envelope.
        float rawPeak = 0f;
        for (int i = halfL; i < available - halfL; i++) {
            float a = Math.abs(data[i]);
            if (a > rawPeak) rawPeak = a;
        }
        if (!(rawPeak > 0)) return out;

        // --- Step 3: synchronous detection of the {@code (F1-F2)}
        // component in absLp.  Mathematically,
        //   |cos((F1-F2)/2·t - φ_m)|  expands into Fourier series
        //                              (2/π) + (4/(3π))·cos(2·((F1-F2)/2·t - φ_m)) + ...
        // so the first AC harmonic of absLp sits at frequency
        // {@code (F1-F2)} with phase {@code 2·φ_m}.  Computing the
        // I / Q correlation of {@code absLp - DC} against
        // {@code cos(ωt), sin(ωt)} at {@code ω = 2π·(F1-F2)} gives
        // {@code atan2(Q, I) = 2·φ_m}, so {@code φ_m = phase / 2}.
        // Reconstructing the modulator as a clean sinusoid at the
        // recovered phase eliminates every residual carrier and
        // higher-harmonic ripple — output is a pure
        // {@code cos((F1-F2)/2·t - φ_m)} with peak {@code rawPeak}.
        double dc = 0.0;
        int validN = 0;
        for (int i = halfL; i < available - halfL; i++) {
            dc += absLp[i];
            validN++;
        }
        if (validN <= 0) return out;
        dc /= validN;
        double omega = 2.0 * Math.PI * beatHz / sampleRate;
        double iSum = 0.0;
        double qSum = 0.0;
        // Phase-recurrence oscillator (2 mul + 2 add per sample) instead of
        // Math.cos + Math.sin per sample — this and the synthesis loop below
        // were 300-900k transcendental calls per paint.  Rotation drift over
        // a window is ~n·ulp, far below the correlation's own noise.
        double rotC = Math.cos(omega), rotS = Math.sin(omega);
        double c = Math.cos(omega * halfL), s = Math.sin(omega * halfL);
        for (int i = halfL; i < available - halfL; i++) {
            double ac = absLp[i] - dc;
            iSum += ac * c;
            qSum += ac * s;
            double cNext = c * rotC - s * rotS;
            s = s * rotC + c * rotS;
            c = cNext;
        }
        double phase    = Math.atan2(qSum, iSum);
        double omegaMod = omega / 2.0;
        double phaseMod = phase  / 2.0;
        double rotMc = Math.cos(omegaMod), rotMs = Math.sin(omegaMod);
        double mc = Math.cos(-phaseMod),   ms = Math.sin(-phaseMod);
        for (int i = 0; i < available; i++) {
            out[i] = (float) (rawPeak * mc);
            double mcNext = mc * rotMc - ms * rotMs;
            ms = ms * rotMc + mc * rotMs;
            mc = mcNext;
        }
        return out;
    }

    /**
     * Computes the single-tone RESIDUAL for the displayed slice of {@code data}
     * — {@code residual[i] = data[i] − bestFitSingleTone(i)} with only the
     * sinusoid removed (DC left in the trace; the AC toggle handles DC).
     *
     * <p>The tone's frequency is seeded from the scope's Goertzel-refined
     * measurement ({@link #getLastFrequencyHz}), cheaply polished on the fit
     * window by phase-slope, and its amplitude / phase / DC come from an exact
     * 3-parameter least-squares fit ({@link SineFit}) over the fit window — so
     * the synthesized tone is inherently phase-aligned to the triggered display.
     *
     * <p>The result is written into the per-channel grow-only scratch, indexed
     * from the fit-window start; {@link #residualDispStart} is set to the index
     * that corresponds to the caller's {@code dispStart}.  Also records
     * {@link #lastResidualVppL}/{@link #lastResidualVppR} from the min/max over
     * the VISIBLE window so auto-setup can scale the vertical off the residual.
     *
     * @return the residual scratch, or {@code null} when the residual can't be
     *         computed (no valid frequency, fit window too short, degenerate
     *         fit) — the caller then paints the captured trace.
     */
    private float[] computeResidual(float[] data, int dataLen, int dispStart, int dispCount,
                                    int pad, double sampleRate, boolean leftChannel,
                                    double peakVolts) {
        // 1. Tone frequencies.  Single tone: seed from THIS channel's measured
        //    fundamental (the worker measures both channels).  Dual tone: the
        //    measured f is deliberately cleared (two fundamentals), so take the
        //    two frequencies the generator actually EMITS (FFT-bin-snapped,
        //    same source as the beat reconstruction) — exact, so no polish is
        //    needed.  Bail (paint captured) when nothing usable.
        Preferences prefs = Preferences.instance();
        boolean dual = prefs.getGenSignalForm().isDualTone();
        double f1 = Double.NaN;
        double f2 = Double.NaN;
        double seed;
        if (dual) {
            int sr = (int) Math.round(sampleRate);
            f1 = FftBinSnap.snapIfEnabled(prefs, GenSignalForm.DUAL_TONE, sr,
                    prefs.getGenDualToneFreq1Hz());
            f2 = FftBinSnap.snapIfEnabled(prefs, GenSignalForm.DUAL_TONE, sr,
                    prefs.getGenDualToneFreq2Hz());
            if (!(f1 > 0) || !(f2 > 0) || !(Math.abs(f2 - f1) > 0)) {
                recordResidualVpp(leftChannel, Double.NaN);
                return null;
            }
            seed = Math.min(f1, f2);
        } else {
            SignalMeasurements seedMeas = measWorker.getLastMeasResult(leftChannel);
            seed = (seedMeas == null) ? Double.NaN : seedMeas.getFrequency();
        }
        if (!(seed > 0) || !Double.isFinite(seed) || !(sampleRate > 0)) {
            recordResidualVpp(leftChannel, Double.NaN);
            return null;
        }

        // 2. Fit window: start from the padded display slice, grow to cover at
        //    least RESIDUAL_MIN_CYCLES cycles / RESIDUAL_MIN_FIT_SAMPLES — LEFT
        //    first (older lookback samples exist), then right — clamped to the
        //    buffer and capped at RESIDUAL_FIT_MAX_SAMPLES.
        int sliceFrom = Math.max(0, dispStart - pad);
        int sliceTo   = Math.min(dataLen, dispStart + dispCount + pad);
        if (sliceTo - sliceFrom < 2) { recordResidualVpp(leftChannel, Double.NaN); return null; }
        int needCycles = (int) Math.ceil(RESIDUAL_MIN_CYCLES * sampleRate / seed);
        if (dual) {
            // The two tones are only separable when the window spans several
            // beat cycles — below that the fits leak into each other.
            int needBeat = (int) Math.ceil(
                    RESIDUAL_MIN_BEAT_CYCLES * sampleRate / Math.abs(f2 - f1));
            needCycles = Math.max(needCycles, needBeat);
        }
        int wantFit    = Math.min(RESIDUAL_FIT_MAX_SAMPLES,
                Math.max(RESIDUAL_MIN_FIT_SAMPLES, needCycles));
        int fitFrom = sliceFrom;
        int fitTo   = sliceTo;
        int deficit = wantFit - (fitTo - fitFrom);
        if (deficit > 0) {
            int growLeft = Math.min(deficit, fitFrom);
            fitFrom -= growLeft;
            int growRight = Math.min(deficit - growLeft, dataLen - fitTo);
            fitTo += growRight;
        }
        int fitLen = fitTo - fitFrom;
        if (fitLen > RESIDUAL_FIT_MAX_SAMPLES) fitLen = RESIDUAL_FIT_MAX_SAMPLES;
        if (fitLen < RESIDUAL_MIN_FIT_SAMPLES) { recordResidualVpp(leftChannel, Double.NaN); return null; }

        // 3. Cheap phase-slope frequency polish (single tone only — in dual
        //    mode the generator frequencies are exact and a second tone breaks
        //    the single-sinusoid phase model): fit each half of the window and
        //    read off the extra phase advance the seed frequency missed.  A step
        //    larger than the sanity cap means the estimate is unreliable → keep f.
        double f = seed;
        for (int iter = 0; !dual && iter < RESIDUAL_POLISH_ITERS; iter++) {
            int half = fitLen / 2;
            if (half < 2) break;
            SineFit fitA = SineFit.of(data, fitFrom,        half, sampleRate, f);
            SineFit fitB = SineFit.of(data, fitFrom + half, half, sampleRate, f);
            double omega    = 2.0 * Math.PI * f / sampleRate;
            double deltaPhi = wrapToPi(fitB.phaseRadians() - fitA.phaseRadians()
                                       - wrapToPi(omega * half));
            double deltaF   = deltaPhi * sampleRate / (2.0 * Math.PI * half);
            double cap      = Math.min(RESIDUAL_POLISH_MAX_HZ, sampleRate / (4.0 * half));
            if (Math.abs(deltaF) > cap) break;
            f += deltaF;
            if (Math.abs(deltaF) < RESIDUAL_POLISH_MIN_STEP_HZ) break;
        }

        // 4. Final exact fit at the polished frequency (single tone; the dual
        //    branch below fits its two tones itself).
        SineFit fit = null;
        if (!dual) {
            fit = SineFit.of(data, fitFrom, fitLen, sampleRate, f);
            if (!Double.isFinite(fit.getA()) || !Double.isFinite(fit.getB())) {
                recordResidualVpp(leftChannel, Double.NaN);
                return null;
            }
        }

        // 5. Subtract the WHOLE fitted model — tone AND the fit's own DC c —
        //    over the display slice into the scratch, indexed from sliceFrom
        //    (kOffset = sliceFrom − fitFrom), and add back the stable, long-
        //    averaged DC. Over a non-integer-cycle window {1,sin,cos} are not
        //    orthogonal, so each per-capture fit re-splits the true DC between c
        //    and the windowed mean of a·sin+b·cos; the split moves as the fit
        //    window boundaries walk (trigger sub-sample jitter, rolling buffer),
        //    which drifts the residual baseline frame-to-frame. Removing the full
        //    model makes the residual exactly orthogonal to the constant over the
        //    fit window (its fit-window mean is 0 by construction, independent of
        //    f̂); adding back dcStable — the SAME acDcMean the AC display offset
        //    uses — pins the baseline to the Vmean-stable source. AC on: drawTrace
        //    then subtracts the same dcStable, so the trace sits at ~0. AC off:
        //    dcOffset is 0, so the DC-coupled trace sits at its true, stable DC.
        int sliceLen = sliceTo - sliceFrom;
        float[] scratch = leftChannel ? residualScratchL : residualScratchR;
        if (scratch == null || scratch.length < sliceLen) {
            scratch = new float[sliceLen];
            if (leftChannel) residualScratchL = scratch; else residualScratchR = scratch;
        }
        double dcStable = acDcMean(leftChannel);
        if (dual) {
            // Two tones: alternating Gauss–Seidel refits over the FULL fit
            // window.  Each round refits one tone on data with the OTHER
            // tone's latest estimate removed, squaring the remaining
            // cross-leakage — after RESIDUAL_DUAL_REFIT_ROUNDS the remnant is
            // below the noise regardless of how few beat cycles the window
            // holds (i.e. independent of time/div).  The last subtraction
            // removes the whole model + pins the baseline to dcStable exactly
            // like the single-tone path.
            if (residualFitScratch == null || residualFitScratch.length < fitLen) {
                residualFitScratch = new float[fitLen];
            }
            float[] fs = residualFitScratch;
            SineFit fitA = SineFit.of(data, fitFrom, fitLen, sampleRate, f1);
            fitA.subtractSineInto(data, fitFrom, fitLen, 0, fs, 0);   // fs = data − A₀
            SineFit fitB = SineFit.of(fs, 0, fitLen, sampleRate, f2);
            if (!Double.isFinite(fitA.getA()) || !Double.isFinite(fitA.getB())
                    || !Double.isFinite(fitB.getA()) || !Double.isFinite(fitB.getB())) {
                recordResidualVpp(leftChannel, Double.NaN);
                return null;
            }
            for (int round = 0; round < RESIDUAL_DUAL_REFIT_ROUNDS; round++) {
                fitB.subtractSineInto(data, fitFrom, fitLen, 0, fs, 0);   // fs = data − B
                fitA = SineFit.of(fs, 0, fitLen, sampleRate, f1);
                fitA.subtractSineInto(data, fitFrom, fitLen, 0, fs, 0);   // fs = data − A
                fitB = SineFit.of(fs, 0, fitLen, sampleRate, f2);
            }
            // fs still holds data − A after the last round; remove B fully.
            fitB.subtractFullInto(fs, 0, fitLen, 0, dcStable, fs, 0);
            System.arraycopy(fs, sliceFrom - fitFrom, scratch, 0, sliceLen);
        } else {
            fit.subtractFullInto(data, sliceFrom, sliceLen, sliceFrom - fitFrom, dcStable, scratch, 0);
        }

        // 6. Vpp over the VISIBLE part only (dispStart .. dispStart+dispCount),
        //    expressed in volts, for auto-setup's per-channel vertical scaling.
        int visFrom = Math.max(0, dispStart - sliceFrom);
        int visTo   = Math.min(sliceLen, dispStart - sliceFrom + dispCount);
        double vpp = Double.NaN;
        if (visTo > visFrom) {
            float min = scratch[visFrom], max = scratch[visFrom];
            for (int i = visFrom + 1; i < visTo; i++) {
                float v = scratch[i];
                if (v < min) min = v;
                if (v > max) max = v;
            }
            vpp = (max - min) * peakVolts;
        }
        recordResidualVpp(leftChannel, vpp);

        // 7. Report where dispStart landed inside the scratch + the valid length,
        //    and return the scratch (whose .length may exceed sliceLen).
        residualDispStart = dispStart - sliceFrom;
        residualSliceLen  = sliceLen;
        return scratch;
    }

    /** Stores the residual Vpp for the given channel (see {@link #computeResidual}). */
    private void recordResidualVpp(boolean leftChannel, double vpp) {
        if (leftChannel) lastResidualVppL = vpp; else lastResidualVppR = vpp;
    }

    /** Copies {@code data[sliceFrom .. sliceFrom+sliceLen)} into the given
     *  channel's residual scratch (grown as needed) so a shown-but-non-residual
     *  channel renders off the SAME slice geometry as the residual channel — see
     *  {@link #renderTraces}.  Out-of-range samples are left as zeros (drawTrace
     *  blanks columns the buffer can't fill regardless). */
    private float[] copyResidualSlice(float[] data, int sliceFrom, int sliceLen, boolean leftChannel) {
        float[] scratch = leftChannel ? residualScratchL : residualScratchR;
        if (scratch == null || scratch.length < sliceLen) {
            scratch = new float[sliceLen];
            if (leftChannel) residualScratchL = scratch; else residualScratchR = scratch;
        }
        int from = Math.max(0, sliceFrom);
        int to   = Math.min(data.length, sliceFrom + sliceLen);
        int off  = from - sliceFrom;
        if (off > 0) Arrays.fill(scratch, 0, Math.min(off, sliceLen), 0f);
        if (to > from) System.arraycopy(data, from, scratch, off, to - from);
        if (off + (to - from) < sliceLen) Arrays.fill(scratch, off + Math.max(0, to - from), sliceLen, 0f);
        return scratch;
    }

    /** Wraps a radian angle into (−π, π]. */
    private double wrapToPi(double r) {
        double twoPi = 2.0 * Math.PI;
        return r - twoPi * Math.floor((r + Math.PI) / twoPi);
    }

    /**
     * Copies the samples around the just-found trigger into the captured-frame
     * arrays so they persist independently of the ring buffer.  Saves up to
     * two display windows centred on the trigger (i.e. {@code 2·displaySamples
     * + 2·Lanczos.LANCZOS_PADDING}) so the renderer has at least half-a-window of
     * context on each side — that way the sinc kernel has full data even at
     * the pane edges, and a late Start that fires the trigger close to the
     * buffer end still shows the full screen.  When the ring buffer doesn't
     * yet hold the full ideal range (start of capture, very slow time/div)
     * we save whatever's available and clamp the in-capture display offset.
     */
    /** Renders the frozen captured frame (the held SINGLE shot or the last NORMAL
     *  trigger).  AC channels subtract the DC cached at capture ({@link #capturedDcL}
     *  / {@link #capturedDcR}) — NOT the live worker mean — so the frozen trace stays
     *  put instead of drifting as the worker keeps averaging Vmean after the freeze. */
    private void renderHeldCapturedFrame(MeasurementPainter gc, int w, int h,
                                         boolean showL, boolean showR,
                                         double leftVDiv, double rightVDiv,
                                         boolean sincL, boolean sincR,
                                         boolean acL, boolean acR) {
        double dcL = acL ? capturedDcL : 0.0;
        double dcR = acR ? capturedDcR : 0.0;
        // Magnify the frozen snapshot around the cursor.  The span follows the
        // live 1-2-5 time/div (the wheel steps the t/div selector as usual); when
        // that scale changes, recompute the left edge so the captured sample under
        // the cursor stays under it.  NOT clamped to the buffer — where the view
        // runs past the captured data, drawTrace draws nothing (no shift).
        double curTDiv = Preferences.instance().getOscTimePerDiv();
        double scale   = (capturedTimePerDiv > 0 && curTDiv > 0) ? curTDiv / capturedTimePerDiv : 1.0;
        int dispCount  = Math.max(2, (int) Math.round(capturedDispCount * scale));
        if (!Double.isNaN(capturedTriggerLocal)) {
            // Trigger captured → position via the (virtual-capable) trigger offset,
            // exactly like the live trigger-offset model: a pan (p) scrolls the held
            // trace; a t/div FIELD zoom (p unchanged) zooms around the trigger; and
            // ctrl+shift+wheel updates p to keep the sample under the mouse put.  NOT
            // clamped here — off-buffer edges blank; only a MOVE clamps (panLiveOffset).
            double p = Preferences.instance().getOscTriggerPositionFrac();
            heldViewStart = capturedTriggerLocal - p * dispCount;
        } else if (lastHeldTimePerDiv <= 0) {                    // entry frame, not yet anchored
            heldViewStart = capturedDispStart + capturedSubSampleOffset;
        } else if (curTDiv != lastHeldTimePerDiv && lastHeldDispCount > 0) {
            // Trigger-less entry snapshot: wheel zoom keeps the sample under the cursor
            // put; a t/div FIELD change (anchor unset) keeps the SCREEN CENTRE put so
            // the trace doesn't jump off-centre onto the stale cursor position.
            double frac         = (heldZoomAnchorX >= 0 && w > 0)
                    ? ScopeFormat.clamp01((double) heldZoomAnchorX / w) : 0.5;
            heldZoomAnchorX     = -1;                                         // consumed
            double cursorSample = heldViewStart + frac * lastHeldDispCount;   // sample under anchor before zoom
            heldViewStart       = cursorSample - frac * dispCount;            // keep it under the anchor
        }
        lastHeldTimePerDiv = curTDiv;
        lastHeldDispCount  = dispCount;
        int    dispStart       = (int) Math.floor(heldViewStart);
        double subSampleOffset = heldViewStart - dispStart;
        blankBeyondData = true;
        // The held copy may render after Stop with the live reader already gone,
        // so the residual fit reads its rate from the rate captured at freeze.
        renderTraces(gc, w, h, capturedLeft, capturedRight, capturedLen,
                     dispStart, subSampleOffset, dispCount,
                     showL, showR, leftVDiv, rightVDiv, sincL, sincR, dcL, dcR, capturedSampleRate);
        blankBeyondData = false;
    }

    private void captureSingleFrame(int dispStart, int displaySamples,
                                    int available, double subSampleOffset,
                                    double triggerLocal) {
        // Capture the whole available read window (~2 screens plus the trigger-
        // search lookback), not just the displayed slice, so Ctrl+Shift+wheel can
        // zoom OUT on the frozen frame across the captured pre/post-roll instead
        // of running out after a single step.  dispStart is already relative to
        // the read buffer's start (so srcStart == 0).
        int srcStart = 0;
        int srcEnd   = available;
        int len = srcEnd - srcStart;
        int dispStartInCapture = dispStart - srcStart;
        if (len < 2 || dispStartInCapture < 0 || dispStartInCapture + displaySamples > len) return;
        if (capturedLeft == null || capturedLeft.length < len) {
            capturedLeft  = new float[len];
            capturedRight = new float[len];
        }
        System.arraycopy(leftBuf,  srcStart, capturedLeft,  0, len);
        System.arraycopy(rightBuf, srcStart, capturedRight, 0, len);
        capturedLen             = len;
        capturedDispStart       = dispStartInCapture;
        capturedDispCount       = displaySamples;
        capturedSubSampleOffset = subSampleOffset;
        // srcStart == 0, so the read-buffer-local trigger position IS the captured-copy
        // position.  NaN for a trigger-less entry snapshot.
        capturedTriggerLocal    = triggerLocal;
        // Cache each channel's own DC now so AC-mode held renders subtract a
        // stable bias — the live worker mean keeps averaging after the freeze
        // and would otherwise drift the frozen trace up/down.
        capturedDcL             = ScopeFormat.windowMean(capturedLeft,  0, len);
        capturedDcR             = ScopeFormat.windowMean(capturedRight, 0, len);
        capturedTimePerDiv      = Preferences.instance().getOscTimePerDiv();
        capturedSampleRate      = reader != null ? reader.getSampleRate() : 0;
        heldViewStart           = capturedDispStart + capturedSubSampleOffset;
        lastHeldTimePerDiv      = capturedTimePerDiv;
        lastHeldDispCount       = capturedDispCount;
        singleHeld              = true;
    }

    /**
     * Renders the two channels from a (data, dispStart, subSampleOffset,
     * dispCount) tuple — shared by the live-data and captured-frame paths.
     * The sub-sample offset shifts the rendered signal by a fraction of a
     * sample so the trigger lands precisely on the centre pixel.
     */
    private void renderTraces(MeasurementPainter gc, int w, int h,
                              float[] dataLeft, float[] dataRight, int dataLen,
                              int dispStart, double subSampleOffset, int dispCount,
                              boolean showL, boolean showR,
                              double leftVDiv, double rightVDiv,
                              boolean sincEnabledL, boolean sincEnabledR,
                              double dcOffsetL, double dcOffsetR,
                              double sampleRate) {
        if (dispCount < 2) return;
        // Defensive: dispStart/dispCount can run far past the buffer on a held-frame or
        // off-screen-offset zoom, and dataLen must never exceed the channel arrays we
        // were handed.  Clamp it so the snapshot copy (captureFrame) and the sample
        // reads (drawTrace / TraceEnvelope) stay in bounds — both already blank any
        // column the data can't fill, so this only caps the count, never the view.
        if (dataLeft  != null) dataLen = Math.min(dataLen, dataLeft.length);
        if (dataRight != null) dataLen = Math.min(dataLen, dataRight.length);
        Preferences prefs = Preferences.instance();
        // Per-channel vertical centre: offsetFrac maps directly to the Y
        // coordinate where the channel's zero crossing renders.  0.5 ≡
        // canvas centre (historical default); the value can run past
        // [0, 1] when the user has scrolled the trace far enough that
        // the zero-volt baseline is above/below the visible grid (e.g.
        // when ±FS is parked at the centre at narrow V/div).
        double leftCenterY  = h * prefs.getOscLeftOffsetFrac();
        double rightCenterY = h * prefs.getOscRightOffsetFrac();
        double pixelsPerDivY = (double) h / DIVISIONS_Y;
        double peakVolts     = prefs.getAdcFsVoltageRms() * Math.sqrt(2.0);
        float  lineWidth     = (float) prefs.getOscLineWidth();
        int    dotDiameter   = prefs.getOscDotDiameter();

        // Residual mode: subtract the best-fit single tone from the DISPLAYED
        // slice, per channel, when this frame carries live data (frozenFrame ==
        // null).  Both channels share the SAME slice geometry [sliceFrom,sliceTo)
        // (it depends only on dispStart/dispCount/pad/dataLen, equal for L and R),
        // so a residual channel and a re-based raw copy of the other channel fit a
        // single (dataLen, dispStart) tuple — which the snapshot (captureFrame /
        // RenderedFrame) can only carry once.  Substituting the scratch for BOTH
        // captureFrame and drawTrace bakes the residual into the snapshot; the
        // frozen-replay path then paints it verbatim (captureFrame skipped,
        // residual NOT re-run there), so it is subtracted exactly once.
        boolean leftResidual  = prefs.isOscLeftResidualEnabled();
        boolean rightResidual = prefs.isOscRightResidualEnabled();
        float[] drawLeft   = dataLeft;
        float[] drawRight  = dataRight;
        int     drawLen    = dataLen;
        int     drawDisp   = dispStart;
        if (frozenFrame == null) {
            if (!leftResidual)  lastResidualVppL = Double.NaN;
            if (!rightResidual) lastResidualVppR = Double.NaN;
            float[] resL = (showL && leftResidual && dataLeft != null)
                    ? computeResidual(dataLeft, dataLen, dispStart, dispCount,
                            Lanczos.LANCZOS_PADDING, sampleRate, true, peakVolts) : null;
            int     dispL = residualDispStart;
            int     lenL  = residualSliceLen;
            float[] resR = (showR && rightResidual && dataRight != null)
                    ? computeResidual(dataRight, dataLen, dispStart, dispCount,
                            Lanczos.LANCZOS_PADDING, sampleRate, false, peakVolts) : null;
            int     dispR = residualDispStart;
            int     lenR  = residualSliceLen;
            // At least one residual succeeded → both channels render off the shared
            // slice: the residual channel from its scratch, the other (shown, raw)
            // channel from a plain copy of the same slice so the single (drawLen,
            // drawDisp) tuple is valid for both traces AND the snapshot.  (The slice
            // geometry depends only on dispStart/dispCount/pad/dataLen, so dispL==dispR
            // and lenL==lenR whenever both residuals are present.)
            if (resL != null || resR != null) {
                int shared    = (resL != null) ? dispL : dispR;   // dispStart − sliceFrom (equal for both)
                int sliceLen  = (resL != null) ? lenL  : lenR;
                int sliceFrom = dispStart - shared;
                drawLen  = sliceLen;
                drawDisp = shared;
                drawLeft  = resL != null ? resL
                        : (showL && dataLeft  != null ? copyResidualSlice(dataLeft,  sliceFrom, sliceLen, true)  : dataLeft);
                drawRight = resR != null ? resR
                        : (showR && dataRight != null ? copyResidualSlice(dataRight, sliceFrom, sliceLen, false) : dataRight);
            }
        }

        // Snapshot what we're about to draw (live view only) so a screenshot
        // is a carbon copy of the on-screen trace — residual included.
        if (frozenFrame == null) {
            captureFrame(drawLeft, drawRight, drawLen, drawDisp, subSampleOffset, dispCount,
                    showL, showR, leftVDiv, rightVDiv, sincEnabledL, sincEnabledR, dcOffsetL, dcOffsetR);
        }

        gc.setAntialias(SWT.ON);
        lineAttrsTrace.width = lineWidth;
        gc.setLineAttributes(lineAttrsTrace);
        if (showL) {
            double vScale = peakVolts / leftVDiv * pixelsPerDivY;
            drawTrace(gc, drawLeft,  drawLen, drawDisp, subSampleOffset, dispCount,
                      w, h, leftCenterY, vScale, lineWidth, color(ColorRole.LEFT_TRACE), sincEnabledL, dcOffsetL, dotDiameter);
        }
        if (showR) {
            double vScale = peakVolts / rightVDiv * pixelsPerDivY;
            drawTrace(gc, drawRight, drawLen, drawDisp, subSampleOffset, dispCount,
                      w, h, rightCenterY, vScale, lineWidth, color(ColorRole.RIGHT_TRACE), sincEnabledR, dcOffsetR, dotDiameter);
        }
    }



    /** X position for sinc-trace point {@code i} of {@code width + 2}: the first
     *  and last point map {@link #TRACE_EDGE_OVERHANG_PX} outside the canvas (see
     *  the constant's doc), the rest one per pixel column. */
    private int sincTraceX(int i, int width) {
        if (i == 0)         return -TRACE_EDGE_OVERHANG_PX;
        if (i == width + 1) return width - 1 + TRACE_EDGE_OVERHANG_PX;
        return i - 1;
    }

    /** Straight-line interpolation of {@code data} at fractional sample position
     *  {@code pos}, clamped to [0, n-1] — the sin(x)/x-off counterpart to
     *  {@link Lanczos#lanczos}, letting the non-sinc trace stroke one smooth point
     *  per pixel column (see {@link #drawTrace}) instead of per-sample min/max bars. */
    private double lerpAt(float[] data, int n, double pos) {
        if (pos <= 0)       return data[0];
        if (pos >= n - 1)   return data[n - 1];
        int i0   = (int) pos;
        double f = pos - i0;
        return data[i0] * (1.0 - f) + data[i0 + 1] * f;
    }

    /**
     * Draws a waveform.  With sin(x)/x enabled the signal is always
     * band-limit-reconstructed via a Lanczos-windowed sinc kernel scaled to the
     * output rate (no aliasing into beat envelopes), at every time base.  With it
     * off, a linearly-interpolated per-column stroke is used while
     * {@code samplesPerPx ≤ Lanczos.MAX_LANCZOS_DOWNSAMPLE} and per-column min/max
     * bars take over above that.  A 5-px filled dot is overlaid at each sample when
     * sample spacing exceeds 10 px.
     *
     * <p>{@code subSampleOffset} (range [0, 1)) shifts the rendered signal by
     * a fraction of a sample in the input-sample direction — used by the
     * trigger logic to anchor the display centre on the band-limited zero
     * crossing instead of the nearest raw sample.
     */
    private void drawTrace(MeasurementPainter gc, float[] data, int n,
                                  int dispStart, double subSampleOffset, int dispCount,
                                  int width, int height, double centerY, double vScale,
                                  float lineWidth, Color color,
                                  boolean sincEnabled, double dcOffset, int dotDiameter) {
        if (dispCount < 2 || width <= 0) return;
        gc.setForeground(color);
        double samplesPerPx = (double) dispCount / width;
        double pxPerSample = (double) width / dispCount;
        // One smooth point per pixel column while there is at most one sample per
        // pixel — sin(x)/x reconstructs BETWEEN samples, linear interpolates.  Above
        // one sample per pixel a single point per column would either stair-step
        // (linear) or, with the downsampling-scaled sinc kernel, low-pass narrow
        // pulses and the noise band away, so a CONNECTED per-column min/max envelope
        // takes over (drawEnvelope) — sin(x)/x feeds it a dense scale-1 reconstruction
        // so the band-limited pulse overshoot and noise survive the decimation.
        boolean envelope = sincEnabled ? samplesPerPx > 1.0
                                       : samplesPerPx > Lanczos.MAX_LANCZOS_DOWNSAMPLE;
        if (!envelope) {
            // One smooth point per pixel column — this branch is at most ~one sample per
            // pixel (sin x/x) or <= MAX_LANCZOS_DOWNSAMPLE (linear).  Stroke through the
            // shared paintPolyline — the same clipped-Path renderer as the FFT / FreqResp
            // traces.  Sin x/x reconstructs band-limited values BETWEEN samples at scale
            // max(1, spp) (so spp <= 1 here is true Whittaker-Shannon) and still reads its
            // own LANCZOS_PADDING samples, so edge values are accurate; linear interpolates
            // (lerpAt) one point per column.
            Rectangle plot = new Rectangle(0, 0, width, height);
            if (sincEnabled) {
                double scale = Math.max(1.0, samplesPerPx);
                // One reconstructed point per pixel column, plus one anchor point
                // TRACE_EDGE_OVERHANG_PX outside each edge so the stroke caps
                // render beyond the clip (full edge intensity).
                paintPolyline(gc, plot, color, SWT.LINE_SOLID, lineWidth, width + 2,
                        i -> sincTraceX(i, width),
                        i -> {
                            double pos = dispStart + subSampleOffset + sincTraceX(i, width) * samplesPerPx;
                            if (pos < 0 || pos > n - 1) return Double.NaN;   // no sample there → blank the edge overhang
                            return centerY - (Lanczos.lanczos(data, n, pos, scale) - dcOffset) * vScale;
                        });
            } else {
                // Linear (sin x/x off): one linearly-interpolated point per pixel column —
                // the same per-column stroke as the sinc branch, so the trace is a smooth
                // sub-pixel polyline at >1 sample/pixel instead of the per-column min/max
                // bars (one point per SAMPLE, column-bucketed) that stair-step a shallow
                // ramp.  Straight-line interpolation, no band-limiting.
                paintPolyline(gc, plot, color, SWT.LINE_SOLID, lineWidth, width + 2,
                        i -> sincTraceX(i, width),
                        i -> {
                            double pos = dispStart + subSampleOffset + sincTraceX(i, width) * samplesPerPx;
                            if (pos < 0 || pos > n - 1) return Double.NaN;   // no sample there → blank the edge overhang
                            return centerY - (lerpAt(data, n, pos) - dcOffset) * vScale;
                        });
            }
            if (pxPerSample > 10.0) {
                gc.setBackground(color);
                double dotShift = subSampleOffset * pxPerSample;
                int half = dotDiameter / 2;
                // Iterate over every sample index whose pixel position lands
                // inside the canvas — including ones outside the original
                // display window (the trace's sinc curve already extends past
                // it).  Stops at buffer bounds; otherwise the sub-sample
                // shift could leave an obvious sample-dot gap at the edges.
                int iStart = (int) Math.floor(dotShift / pxPerSample);
                int iEnd   = (int) Math.ceil((width + dotShift) / pxPerSample);
                for (int i = iStart; i <= iEnd; i++) {
                    int dataIdx = dispStart + i;
                    if (dataIdx < 0 || dataIdx >= n) continue;
                    int sx = (int) Math.round(i * pxPerSample - dotShift);
                    if (sx < 0 || sx >= width) continue;
                    int sy = (int) Math.round(centerY - (data[dataIdx] - dcOffset) * vScale);
                    gc.fillOval(sx - half, sy - half, dotDiameter, dotDiameter);
                }
            }
        } else {
            drawEnvelope(gc, data, n, dispStart, subSampleOffset, dispCount,
                    width, centerY, vScale, color, sincEnabled, dcOffset);
        }
    }

    /**
     * Draws a CONNECTED per-column min/max envelope (more than one sample per
     * pixel) — each column is a {@code [min, max]} vertical bar with a bridging
     * connector across disjoint adjacent columns ({@link TraceEnvelope#connectorAttach})
     * so the trace stays continuous instead of floating as the old disconnected bars
     * did.  Linear takes the raw-sample min/max; sin(x)/x additionally reconstructs
     * the band-limited curve around each column's extreme samples
     * ({@link TraceEnvelope#reconstructedColumns}) to recover a peak that the
     * (clock-drifting) samples missed.
     *
     * <p>Vertical 1-px bars + connectors don't benefit from AA or the thick
     * round-cap stroke, and the per-segment GDI overhead dominates otherwise, so
     * the caller's AA + line-attributes are saved, the fast {@link #lineAttrsBars}
     * defaults set, and restored after — else the next channel's Lanczos curve
     * inherits the 1-px flat-cap stroke and looks thinner.
     */
    private void drawEnvelope(MeasurementPainter gc, float[] data, int n,
                              int dispStart, double subSampleOffset, int dispCount,
                              int width, double centerY, double vScale, Color color,
                              boolean sincEnabled, double dcOffset) {
        double samplesPerPx = (double) dispCount / width;
        int dispLimit = Math.min(dispStart + dispCount, n);
        if (envMin == null || envMin.length < width) {
            envMin   = new float[width];
            envMax   = new float[width];
            envEntry = new float[width];
            envExit  = new float[width];
        }
        if (sincEnabled) {
            TraceEnvelope.reconstructedColumns(data, n, dispStart, subSampleOffset, width,
                    samplesPerPx, RECON_REFINE_STEP, blankBeyondData, dispLimit, envMin, envMax);
        } else {
            TraceEnvelope.rawColumns(data, n, dispStart, subSampleOffset, width,
                    samplesPerPx, blankBeyondData, dispLimit, envMin, envMax);
        }
        // Where each column's connector attaches to its bar — peak/trough-aware,
        // so an isolated spike is traced to its tip (see TraceEnvelope#connectorAttach).
        TraceEnvelope.connectorAttach(envMin, envMax, width, envEntry, envExit);
        gc.setForeground(color);
        int prevAntialias = gc.getAntialias();
        LineAttributes prevLineAttributes = gc.getLineAttributes();
        gc.setAntialias(SWT.OFF);
        gc.setLineAttributes(lineAttrsBars);
        try {
            for (int x = 0; x < width; x++) {
                float cMin = envMin[x];
                if (Float.isNaN(cMin)) continue;
                float cMax = envMax[x];
                int yOfMin = (int) Math.round(centerY - (cMin - dcOffset) * vScale);   // bottom (larger y)
                int yOfMax = (int) Math.round(centerY - (cMax - dcOffset) * vScale);   // top (smaller y)
                gc.drawLine(x, yOfMax, x, yOfMin);   // the bar
                // Connector from the previous column (drawn iff both ends are set —
                // the boundary is disjoint and neither column is blank).
                float exitPrev = x > 0 ? envExit[x - 1] : Float.NaN;
                if (!Float.isNaN(exitPrev) && !Float.isNaN(envEntry[x])) {
                    int yExit  = (int) Math.round(centerY - (exitPrev    - dcOffset) * vScale);
                    int yEntry = (int) Math.round(centerY - (envEntry[x] - dcOffset) * vScale);
                    gc.drawLine(x - 1, yExit, x, yEntry);
                }
            }
        } finally {
            gc.setAntialias(prevAntialias);
            gc.setLineAttributes(prevLineAttributes);
        }
    }


    @Override
    protected void checkSubclass() {
        // Allow subclassing — Canvas is on SWT's restricted list otherwise.
    }
}
