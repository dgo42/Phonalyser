package org.edgo.audio.measure.gui.scope;

import java.util.Map;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.LineAttributes;
import org.eclipse.swt.graphics.Path;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.OscSliderId;
import org.edgo.audio.measure.enums.TriggerEdge;
import org.edgo.audio.measure.enums.TriggerMode;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.AbstractMeasurementView;
import org.edgo.audio.measure.gui.common.IconUtils;
import org.edgo.audio.measure.gui.common.SvgPaths;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.preferences.Preferences;
import org.edgo.audio.measure.gui.sound.SignalBuffer;
import org.edgo.audio.measure.sound.AudioBackend;

import lombok.extern.log4j.Log4j2;

/**
 * Oscilloscope main display.  Renders a black canvas with a 10×10 division
 * grid, a centre cross carrying 1/5-division minor tick marks, and (when a
 * {@link SignalBuffer} is attached via {@link #setBuffer(SignalBuffer)}) the
 * left/right channel waveforms scaled by the {@link Preferences}-stored
 * volts/division and time/division settings.
 */
@Log4j2
public final class OscilloscopeView extends AbstractMeasurementView {

    /** Number of horizontal grid divisions.  Package-private so the pane's
     *  mouse-anchored t/div zoom can compute the time at the mouse using
     *  the same grid the renderer uses. */
    static final         int DIVISIONS_X    = 10;
    /** Number of vertical grid divisions.  Package-private so the pane's
     *  vertical-scrollbar math can size its thumb against the same grid
     *  the renderer uses. */
    static final         int DIVISIONS_Y    = 10;
    private static final int TICKS_PER_DIV  = 5;
    private static final int TICK_HALF_LEN  = 3;

    // All scope colours live in the AbstractMeasurementView palette
    // (background, grid, text, crosshair, blink lit/dim, left/right
    // trace, left/right channel mid, disabled channel, reset).  The
    // dark-theme overrides + the prefs-driven trace RGBs are passed
    // to super(...) below; the derived mid colours are computed via
    // attenuate(...) and applied via setColor in syncChannelColors().
    /** Cached RGB ints of the channel colours — used to detect pref changes. */
    private int currentLeftRgb  = -1;
    private int currentRightRgb = -1;

    private SignalBuffer buffer;
    private float[] leftBuf  = new float[0];
    private float[] rightBuf = new float[0];

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

    /** Most-recent reconstructed |F1-F2| beat envelope used by the
     *  dual-tone trigger search.  Stored after each reconstruction so
     *  {@link #drawBeatOverlay} can paint it on top of the live trace
     *  when {@code prefs.isOscShowReconstructedBeat()} is on and the
     *  generator is in DUAL_TONE mode. */
    private float[] debugBeatSignal;

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
    private float[] capturedLeft;
    private float[] capturedRight;
    private int     capturedLen;
    private int     capturedDispStart;
    private int     capturedDispCount;
    private double  capturedSubSampleOffset;

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
    private MeasurementRow[]  cachedMeasurementRows;
    private long   lastMeasurementBuildNanos;
    private String cachedCapsString = "";
    private long   lastCapsBuildNanos;

    /**
     * Reusable {@link LineAttributes} instances — created once and mutated
     * (or read as-is) per paint instead of allocating a fresh wrapper every
     * frame.  At 65+ cap/s the per-paint {@code new LineAttributes(...)}
     * calls were adding ~200 short-lived objects/sec to the young
     * generation; pooling them removes that pressure.
     */
    private final LineAttributes lineAttrsThin  = new LineAttributes(1.0f);
    private final LineAttributes lineAttrsTrace = new LineAttributes(1.0f,
            SWT.CAP_ROUND, SWT.JOIN_ROUND);
    private final LineAttributes lineAttrsBars  = new LineAttributes(1.0f,
            SWT.CAP_FLAT,  SWT.JOIN_BEVEL);
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
    /**
     * How many samples back from the live writePos the view should anchor
     * its right edge.  0 = follow latest (default); positive values let
     * the user scroll back through the ring buffer via the navigation
     * slider in {@link OscilloscopePane}.
     */
    private volatile long viewBackOffsetFrames;
    public long getViewBackOffsetFrames()        { return viewBackOffsetFrames; }
    public void setViewBackOffsetFrames(long v) { this.viewBackOffsetFrames = Math.max(0L, v); }
    /**
     * When true the view is showing a statically-loaded signal (no live
     * capture).  Trigger search makes no sense in this mode — the trace
     * uses the same right-edge anchoring as the navigation bypass so
     * scrolling and t/div changes don't snap to different trigger
     * positions.  Set by {@link ScopeOpenSignal#loadFile} on load and
     * cleared by {@link ScopeOpenSignal#clear}.
     */
    private volatile boolean fileMode;
    public void    setFileMode(boolean v) { this.fileMode = v; }
    public boolean isFileMode()           { return fileMode; }

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
    private final Rectangle leftChanButtonBounds      = new Rectangle(0, 0, 0, 0);
    private final Rectangle rightChanButtonBounds     = new Rectangle(0, 0, 0, 0);
    /** Hit-box for the gauge (show / hide entire measurement table) toggle. */
    private final Rectangle tableToggleBounds         = new Rectangle(0, 0, 0, 0);
    /** Hit-box for the stats-toggle button (chart-column icon). */
    private final Rectangle statsToggleBounds         = new Rectangle(0, 0, 0, 0);
    /** Hit-box for the reset-statistics button (rotate-left icon). */
    private final Rectangle resetButtonBoundsHeader   = new Rectangle(0, 0, 0, 0);
    /** Hit-box for the Auto-Setup button (arrows-to-circle icon). */
    private final Rectangle autoSetupButtonBounds     = new Rectangle(0, 0, 0, 0);
    /** Hit-box for the External-Window button (window-restore icon). */
    private final Rectangle externalWindowButtonBounds = new Rectangle(0, 0, 0, 0);
    /** When true, the measurement-table rows are NOT drawn in this view —
     *  they're hosted in {@link #externalMeasurementShell} instead.  Header
     *  buttons (L, R, Auto-Setup, gauge, external-window, chart, reset)
     *  stay in this view either way. */
    private boolean tableExtracted;
    private Shell externalMeasurementShell;
    private Canvas externalMeasurementCanvas;
    /** Hit-boxes for the stats-toggle + reset buttons when they're painted
     *  inside the extracted tool window (separate from
     *  {@link #statsToggleBounds} / {@link #resetButtonBoundsHeader},
     *  which still belong to the main view's coordinate space). */
    private final Rectangle extStatsToggleBounds = new Rectangle(0, 0, 0, 0);
    private final Rectangle extResetButtonBounds = new Rectangle(0, 0, 0, 0);

    /** Lazily-loaded SVG icons for the header buttons.  Each toggle gets a
     *  light variant (tinted with the overlay colour, used when the button
     *  is outlined/off) and a dark variant (tinted with the canvas
     *  background, used when the button is filled/on).  The reset button
     *  is single-state so it only needs the light variant.  All disposed
     *  with the view. */
    private Image gaugeIconLight;
    private Image gaugeIconDark;
    private Image chartColumnIconLight;
    private Image chartColumnIconDark;
    private Image resetStatIcon;
    private Image autoSetupIcon;
    private Image windowRestoreIconLight;
    private Image windowRestoreIconDark;

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

    public OscilloscopeView(Composite parent) {
        // Dark-theme overrides + prefs-driven trace colours, all consumed
        // by AbstractMeasurementView in a single per-role allocation
        // pass.  The mid-channel colours (~65 % attenuation of the trace
        // RGBs) are derived after super() returns since attenuate() is
        // an instance method.
        super(parent, SWT.DOUBLE_BUFFERED, Map.of(
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
        // Derived mid (~65 %) channel colours track the LEFT/RIGHT_TRACE
        // entries — set once here, refreshed by syncChannelColors() on
        // every relevant prefs change.
        syncChannelColors();

        // Register the header-row buttons as Hotspots with the shared base.
        // The base owns the rect-by-reference list and the lookup helper;
        // the mouseDown handler below simply consults hotspotAt(...) instead
        // of running through six explicit if/else branches.  Each rect is
        // mutated in place by the paint code, so registration only happens
        // once here.
        registerHotspot(leftChanButtonBounds, () -> {
            Preferences p = Preferences.instance();
            if (p.getOscMeasurementChannel() == Channel.L) return;
            p.setOscMeasurementChannel(Channel.L);
            p.save();
            measWorker.clearHistory();
            redraw();
        }, "scope.stats.left.tooltip");
        registerHotspot(rightChanButtonBounds, () -> {
            Preferences p = Preferences.instance();
            if (p.getOscMeasurementChannel() == Channel.R) return;
            p.setOscMeasurementChannel(Channel.R);
            p.save();
            measWorker.clearHistory();
            redraw();
        }, "scope.stats.right.tooltip");
        registerHotspot(autoSetupButtonBounds,
                () -> MessageBus.instance().publish(Events.SCOPE_AUTO_SETUP),
                "scope.autosetup.tooltip");
        registerHotspot(externalWindowButtonBounds,
                () -> setTableExtracted(!tableExtracted),
                "scope.external.window.tooltip");
        registerHotspot(tableToggleBounds, () -> {
            Preferences p = Preferences.instance();
            p.setOscShowMeasurementTable(!p.isOscShowMeasurementTable());
            p.save();
            syncExternalShell();
            redraw();
        }, "scope.stats.table.tooltip");
        registerHotspot(statsToggleBounds, () -> {
            Preferences p = Preferences.instance();
            p.setOscShowStats(!p.isOscShowStats());
            p.save();
            redraw();
        }, "scope.stats.toggle.tooltip");
        registerHotspot(resetButtonBoundsHeader, () -> {
            measWorker.clearHistory();
            redraw();
        }, "scope.stats.reset.tooltip");

        setBackground(color(ColorRole.BACKGROUND));
        addPaintListener(this::onPaint);
        addMouseListener(new org.eclipse.swt.events.MouseAdapter() {
            @Override
            public void mouseDown(org.eclipse.swt.events.MouseEvent ev) {
                if (ev.button != 1) return;
                // All header-row buttons (L/R, Auto-Setup, Table, External,
                // Stats, Reset) are registered as Hotspots with the shared
                // base — dispatch them through the registry instead of
                // running through six explicit if/else branches.
                Hotspot hot = hotspotAt(ev.x, ev.y);
                if (hot != null) { hot.onClick.run(); return; }
                // Slider hit detection — first match wins.  The handle is
                // grabbed and the slider value is updated immediately so a
                // click without a drag still moves the slider.
                if (offsetSliderBounds.contains(ev.x, ev.y)) {
                    draggingSlider = OscSliderId.OFFSET;
                } else if (!fileMode && triggerLevelBounds.contains(ev.x, ev.y)) {
                    // Trigger has no meaning on a static loaded signal.
                    draggingSlider = OscSliderId.TRIGGER_LEVEL;
                } else if (!fileMode && triggerPosBounds.contains(ev.x, ev.y)) {
                    draggingSlider = OscSliderId.TRIGGER_POSITION;
                }
                if (draggingSlider != null) {
                    updateSliderFromMouse(ev.x, ev.y);
                }
            }

            @Override
            public void mouseUp(org.eclipse.swt.events.MouseEvent ev) {
                if (draggingSlider != null) {
                    Preferences.instance().save();
                    draggingSlider = null;
                }
            }

            @Override
            public void mouseDoubleClick(org.eclipse.swt.events.MouseEvent ev) {
                if (ev.button != 1) return;
                // Double-click on any slider handle resets it to centre.
                // The preceding mouseDown will have started a drag and moved
                // the slider to the cursor's exact position; the reset
                // overwrites that so the user sees a clean snap to 0.5.
                Preferences prefs = Preferences.instance();
                boolean changed = false;
                if (offsetSliderBounds.contains(ev.x, ev.y)) {
                    if (prefs.getOscMeasurementChannel() == Channel.L) {
                        prefs.setOscLeftOffsetFrac(0.5);
                    } else {
                        prefs.setOscRightOffsetFrac(0.5);
                    }
                    changed = true;
                } else if (!fileMode && triggerLevelBounds.contains(ev.x, ev.y)) {
                    prefs.setOscTriggerLevelFrac(0.5);
                    changed = true;
                } else if (!fileMode && triggerPosBounds.contains(ev.x, ev.y)) {
                    prefs.setOscTriggerPositionFrac(0.5);
                    changed = true;
                }
                if (changed) {
                    draggingSlider = null;
                    prefs.save();
                    redraw();
                }
            }
        });
        addMouseMoveListener(ev -> {
            if (draggingSlider != null) {
                updateSliderFromMouse(ev.x, ev.y);
                return;
            }
            // Hover: switch to a vertical-resize cursor over the up/down
            // sliders (offset, trigger level) and a horizontal-resize cursor
            // over the left/right slider (trigger position).
            int cursorId;
            String tip;
            if (offsetSliderBounds.contains(ev.x, ev.y)) {
                cursorId = SWT.CURSOR_SIZENS;
                tip = I18n.t("scope.offset.slider.tooltip");
            } else if (!fileMode && triggerLevelBounds.contains(ev.x, ev.y)) {
                cursorId = SWT.CURSOR_SIZENS;
                tip = I18n.t("scope.trigger.level.tooltip");
            } else if (!fileMode && triggerPosBounds.contains(ev.x, ev.y)) {
                cursorId = SWT.CURSOR_SIZEWE;
                tip = I18n.t("scope.trigger.position.tooltip");
            } else if (hotspotAt(ev.x, ev.y) != null) {
                // All header-row buttons go through the base's hotspot
                // registry — one branch instead of seven if/elses.  The
                // tooltip key comes from the matched hotspot.
                Hotspot hot = hotspotAt(ev.x, ev.y);
                cursorId = SWT.CURSOR_HAND;
                tip = hot.tooltipKey != null ? I18n.t(hot.tooltipKey) : null;
            } else if (leftMaxLabelBounds.contains(ev.x, ev.y)
                    || leftMinLabelBounds.contains(ev.x, ev.y)
                    || rightMaxLabelBounds.contains(ev.x, ev.y)
                    || rightMinLabelBounds.contains(ev.x, ev.y)) {
                cursorId = SWT.CURSOR_ARROW;
                tip = I18n.t("scope.voltage.label.tooltip");
            } else if (timeLeftLabelBounds.contains(ev.x, ev.y)
                    || timeRightLabelBounds.contains(ev.x, ev.y)) {
                cursorId = SWT.CURSOR_ARROW;
                tip = I18n.t("scope.time.label.tooltip");
            } else {
                cursorId = SWT.CURSOR_ARROW;
                tip = null;
            }
            org.eclipse.swt.graphics.Cursor c = getDisplay().getSystemCursor(cursorId);
            if (getCursor() != c) setCursor(c);
            // Avoid resetting the same string each move — it can flicker
            // the tooltip on some platforms.
            String current = getToolTipText();
            if (tip == null) {
                if (current != null) setToolTipText(null);
            } else if (!tip.equals(current)) {
                setToolTipText(tip);
            }
        });
        addDisposeListener(e -> {
            measWorker.stop();
            disposePalette();
            if (monoFont != null) monoFont.dispose();
            if (chanButtonFont != null) chanButtonFont.dispose();
            // Header icons are cached and owned by IconUtils — disposed
            // when the main shell tears down, not here.
            if (externalMeasurementShell != null && !externalMeasurementShell.isDisposed()) {
                externalMeasurementShell.dispose();
            }
        });
    }

    /** Attaches the live ring buffer.  {@code null} clears the waveform overlay. */
    public void setBuffer(SignalBuffer buffer) {
        this.buffer = buffer;
        measWorker.setBuffer(buffer);
        // Reset per-capture state so a previous session's values aren't
        // displayed on the first few paints of a new capture (notably the
        // DC mean, which is read straight from the worker until it
        // publishes a fresh tick ~100 ms in).
        measWorker.clearLatest();
        this.captureRate             = 0;
        this.lastNewFrameNanos       = 0;
        this.lastTriggerAbsPos       = -1;
        this.cachedMeasurementRows   = null;
        this.cachedCapsString        = "";
        measWorker.clearHistory();
    }

    /** Returns the currently-attached ring buffer (or {@code null} if none). */
    public SignalBuffer getBuffer() {
        return buffer;
    }

    /**
     * Returns the most recent Vrms (volts) published by the measurement
     * worker, or {@code null} if no measurement has been computed yet
     * (capture not running or still in the AC warmup window).  Read by the
     * ADC calibration dialog to compute a scale factor from the entered
     * actual amplitude.
     */
    public Double getLastVrms() {
        SignalMeasurements m = measWorker.getLastMeasResult();
        return (m == null) ? null : m.getVrms();
    }

    /**
     * Returns the latest detected fundamental frequency in Hz, or
     * {@code NaN} if no measurement is available yet (capture not
     * started, signal too short, or period undetectable).  Used by
     * the scope's Save-to flow to truncate the saved file to an
     * integer number of signal periods so the file loops cleanly.
     */
    public double getLastFrequencyHz() {
        SignalMeasurements m = measWorker.getLastMeasResult();
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
        SignalMeasurements m = measWorker.getLastMeasResult();
        return (m == null) ? Double.NaN : m.getVpp();
    }

    /**
     * Copies the latest measurement snapshot and sliding-window history from
     * {@code source} into this view.  Used by the screenshot renderer so a
     * passive (worker-less) OscilloscopePane instance still draws the
     * measurement table with the live values — without this the screenshot
     * view's {@link #lastMeasResult} stays {@code null} and
     * {@link #drawMeasurements} bails out early.
     */
    public void copyMeasurementsFrom(OscilloscopeView source) {
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
        }
        if (newR != currentRightRgb) {
            setColor(ColorRole.RIGHT_TRACE,       newR);
            setColor(ColorRole.RIGHT_CHANNEL_MID, attenuate(newR, 0.65));
            setColor(ColorRole.RIGHT_BEAT,        attenuate(newR, 0.45));
            currentRightRgb = newR;
        }
    }

    /**
     * Arms a single-shot capture in SINGLE trigger mode.  The next trigger
     * event freezes the display on that frame until {@code armSingle} is
     * called again.  No-op effect in AUTO / NORMAL modes (those modes never
     * read {@code singleArmed}).
     */
    public void armSingle() {
        singleArmed = true;
        singleHeld = false;            // forget the previous capture so the live trace
                                       // keeps showing while we wait for the next trigger
        lastTriggerAbsPos = -1;        // wait for a fresh trigger, don't keep holding the previous one
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
        gc.setAdvanced(true);
        syncChannelColors();
        gc.setBackground(color(ColorRole.BACKGROUND));
        gc.fillRectangle(0, 0, w, h);
        // Scope grid: linear 10×10, no axis labels, centred cross-hair
        // with TICKS_PER_DIV sub-ticks along each arm.  The base method
        // does all the geometry; we just feed it the scope's range and
        // colour palette.
        drawGrid(gc,
                 new Rectangle(0, 0, w, h),
                 AxisSpec.linear(0, DIVISIONS_X, DIVISIONS_X),
                 AxisSpec.linear(0, DIVISIONS_Y, DIVISIONS_Y),
                 null,
                 color(ColorRole.GRID), color(ColorRole.AXIS),
                 null, null,
                 0, 0,
                 new CrossHairSpec(0.5, 0.5, color(ColorRole.CROSSHAIR),
                                   TICKS_PER_DIV, TICK_HALF_LEN,
                                   DIVISIONS_X, DIVISIONS_Y));
        drawWaveforms(gc, w, h);
        drawSliders(gc, w, h);
        drawEdgeLabels(gc, w, h);
        drawMeasurements(gc);
        drawCaptureRate(gc, w);
        drawFilePath(gc, w);
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
        long now = System.nanoTime();
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
    private void drawCaptureRate(GC gc, int w) {
        if (buffer == null || captureRate <= 0) return;
        gc.setAntialias(SWT.OFF);
        gc.setTextAntialias(SWT.ON);
        gc.setForeground(color(ColorRole.TEXT));
        gc.setBackground(color(ColorRole.BACKGROUND));
        // Throttle the String.format call to ~5 Hz; drawText still runs every
        // paint so the readout never blanks.
        long now = System.nanoTime();
        if (cachedCapsString.isEmpty() || now - lastCapsBuildNanos >= READOUT_THROTTLE_NS) {
            cachedCapsString   = String.format("%.1f cap/s", captureRate);
            lastCapsBuildNanos = now;
        }
        Point ts = gc.textExtent(cachedCapsString);
        drawOutlinedText(gc, cachedCapsString, w - ts.x - 8, 6);
    }

    /** Gap (px) between the cap/s readout and the file-path label drawn to its left. */
    private static final int FILE_PATH_GAP_TO_CAPS = 1;
    /** Gap (px) between the right-channel max-voltage label and the file path,
     *  so the blinking text never collides with that channel readout. */
    private static final int FILE_PATH_GAP_TO_RIGHT_LABEL = 8;

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
    private void drawFilePath(GC gc, int w) {
        String path = filePath;
        if (path == null || !fileMode) return;

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
            if (monoFont == null) monoFont = new Font(getDisplay(), "Consolas", 9, SWT.NORMAL);
            gc.setFont(monoFont);
            int rightLabelWidth = gc.textExtent(ScopeFormat.formatVolts(maxV, rightVDiv)).x;
            gc.setFont(prev);
            leftEdge = centerX + 4 + rightLabelWidth + FILE_PATH_GAP_TO_RIGHT_LABEL;
        }

        int avail = rightEdge - leftEdge;
        if (avail <= 0) return;   // canvas too narrow → drop the label

        // Left-side ellipsis truncation: prepend "…" then drop characters
        // after it until the string fits.  Linear scan is fine for typical
        // path lengths (~100 chars).
        String shown = path;
        if (gc.textExtent(shown).x > avail) {
            shown = "…" + path;
            while (shown.length() > 1 && gc.textExtent(shown).x > avail) {
                shown = "…" + shown.substring(2);
            }
            if (shown.equals("…")) return;   // not even an ellipsis fits
        }

        Point sExt = gc.textExtent(shown);
        boolean lit = ((System.currentTimeMillis() / 500L) % 2L) == 0L;
        gc.setForeground(lit ? color(ColorRole.BLINK_LIT) : color(ColorRole.BLINK_DIM));
        gc.setBackground(color(ColorRole.BACKGROUND));
        drawOutlinedText(gc, shown, rightEdge - sExt.x, 6);

        scheduleFilePathBlinkRedraw();
    }

    /** Wall-clock-phase blink doesn't repaint on its own — schedule a
     *  redraw every 500 ms while file mode is active so the user sees the
     *  blink even when nothing else triggers a paint. */
    private void scheduleFilePathBlinkRedraw() {
        if (filePathBlinkRedrawScheduled) return;
        filePathBlinkRedrawScheduled = true;
        getDisplay().timerExec(500, () -> {
            filePathBlinkRedrawScheduled = false;
            if (isDisposed()) return;
            if (filePath != null && fileMode) redraw();
        });
    }
    private boolean filePathBlinkRedrawScheduled;

    /**
     * Pale-grey measurement table (Vpp / Vrms / Vmean / Tp / f / Duty) in
     * the top-left.  Reads from the live ring buffer so values stay accurate
     * even when a SINGLE held frame is on screen, and uses a measurement
     * window long enough to contain ≥ 1 cycle of any audio-band signal.
     * Avg / min / max / σ are aggregated across a sliding window of the
     * last {@code oscMeasurementAverageSeconds} of measurements.
     */
    private void drawMeasurements(GC gc) {
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
        SignalBuffer b = buffer;
        boolean hasSignal = (b != null);
        drawMeasurementHeaderButtons(gc, showL, showR, selected, hasSignal);

        if (!hasSignal) return;
        if (!prefs.isOscShowMeasurementTable()) return;
        if (!showL && !showR) return;

        MeasurementRow[] rows = prepareMeasurementRows(prefs);
        if (rows == null) return;
        // When extracted, the rows are drawn by externalMeasurementCanvas's
        // paint listener — skip the in-canvas table here.  We still want
        // the row build above so the external canvas has fresh data via
        // cachedMeasurementRows / via prepareMeasurementRows on its own.
        if (tableExtracted) return;
        selected = prefs.getOscMeasurementChannel();
        drawMeasurementTable(gc, rows, showL, showR, selected);
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
        if (selected == Channel.L && !showL && showR) {
            prefs.setOscMeasurementChannel(Channel.R);
            prefs.save();
            measWorker.clearHistory();
        } else if (selected == Channel.R && !showR && showL) {
            prefs.setOscMeasurementChannel(Channel.L);
            prefs.save();
            measWorker.clearHistory();
        }
        SignalMeasurements cur = measWorker.getLastMeasResult();
        if (cur == null) return null;
        long now = System.nanoTime();
        MeasurementRow[] rows = cachedMeasurementRows;
        if (rows == null || now - lastMeasurementBuildNanos >= READOUT_THROTTLE_NS) {
            long windowNanos = (long) (prefs.getOscMeasurementAverageSeconds() * 1e9);
            long cutoff = now - windowNanos;
            MeasurementStats vppS   = new MeasurementStats();
            MeasurementStats vrmsS  = new MeasurementStats();
            MeasurementStats vmeanS = new MeasurementStats();
            MeasurementStats tpS    = new MeasurementStats();
            MeasurementStats trS    = new MeasurementStats();
            MeasurementStats tfS    = new MeasurementStats();
            MeasurementStats fS     = new MeasurementStats();
            MeasurementStats dutyS  = new MeasurementStats();
            measWorker.walkRecentHistory(cutoff, s -> {
                vppS  .add(s.getVpp());
                vrmsS .add(s.getVrms());
                vmeanS.add(s.getVmean());
                tpS   .add(s.getPeriod());
                trS   .add(s.getRiseTime());
                tfS   .add(s.getFallTime());
                fS    .add(s.getFrequency());
                dutyS .add(s.getDutyCycle());
            });
            rows = new MeasurementRow[] {
                    MeasurementRow.forVolts("Vpp",   cur.getVpp(),       vppS),
                    MeasurementRow.forVolts("Vrms",  cur.getVrms(),      vrmsS),
                    MeasurementRow.forVolts("Vmean", cur.getVmean(),     vmeanS),
                    MeasurementRow.forTime ("Tp",    cur.getPeriod(),    tpS),
                    MeasurementRow.forTime ("Tr",    cur.getRiseTime(),  trS),
                    MeasurementRow.forTime ("Tf",    cur.getFallTime(),  tfS),
                    MeasurementRow.forFreq ("f",     cur.getFrequency(), fS),
                    MeasurementRow.forPct  ("Duty",  cur.getDutyCycle(), dutyS),
            };
            cachedMeasurementRows     = rows;
            lastMeasurementBuildNanos = now;
        }
        return rows;
    }

    /** Records the user's intent to keep the table extracted into the tool
     *  window.  The shell's actual open/closed state is derived from this
     *  flag AND {@code oscShowMeasurementTable} via {@link #syncExternalShell},
     *  so hiding the measurement table with the gauge button closes the
     *  shell without forgetting the extracted intent — re-enabling the
     *  measurement table then brings the shell back automatically. */
    private void setTableExtracted(boolean extracted) {
        if (extracted == tableExtracted) return;
        tableExtracted = extracted;
        syncExternalShell();
        redraw();
    }

    /** Brings the external shell into sync with the current
     *  {@code tableExtracted} ∧ {@code oscShowMeasurementTable} state:
     *  opens it when both are true, disposes it otherwise. */
    private void syncExternalShell() {
        boolean shouldBeOpen = tableExtracted
                && Preferences.instance().isOscShowMeasurementTable();
        boolean isOpen = externalMeasurementShell != null
                && !externalMeasurementShell.isDisposed();
        if (shouldBeOpen && !isOpen) {
            createExternalMeasurementShell();
        } else if (!shouldBeOpen && isOpen) {
            externalMeasurementShell.dispose();
            externalMeasurementShell  = null;
            externalMeasurementCanvas = null;
        }
    }

    /** Builds the tool window with a regular (non-tool) trim so the close
     *  button has the standard right-edge padding, and without
     *  {@code SWT.RESIZE} since we auto-size to the table content.  The
     *  single child Canvas paints the stats/reset buttons (when stats are
     *  enabled) followed by the measurement table.  Closing the shell
     *  routes back through {@link #setTableExtracted}(false) so the
     *  in-canvas table reappears. */
    private void createExternalMeasurementShell() {
        Shell parent = getShell();
        // SWT.DIALOG_TRIM = TITLE | CLOSE | BORDER — gives the standard
        // Windows title bar (matching Paint.NET-style tool windows) with
        // proper right-edge inset on the close button.  No resize.
        Shell s = new Shell(parent, SWT.DIALOG_TRIM);
        s.setText(I18n.t("scope.external.window.title"));
        s.setLayout(new org.eclipse.swt.layout.FillLayout());
        Canvas c = new Canvas(s, SWT.DOUBLE_BUFFERED);
        c.setBackground(color(ColorRole.BACKGROUND));
        c.addPaintListener(this::paintExternalMeasurementTable);
        c.addMouseListener(new org.eclipse.swt.events.MouseAdapter() {
            @Override
            public void mouseDown(org.eclipse.swt.events.MouseEvent ev) {
                if (ev.button != 1) return;
                Preferences prefs = Preferences.instance();
                if (extStatsToggleBounds.contains(ev.x, ev.y)) {
                    prefs.setOscShowStats(!prefs.isOscShowStats());
                    prefs.save();
                    // Resize the tool window to match the new column count
                    // before redrawing so the trim adapts in lock-step.
                    resizeExternalShellToContent();
                    redraw();   // mirrors to external canvas via our redraw() override
                    return;
                }
                if (extResetButtonBounds.contains(ev.x, ev.y)) {
                    measWorker.clearHistory();
                    redraw();
                }
            }
        });
        c.addMouseMoveListener(ev -> {
            int cursorId = SWT.CURSOR_ARROW;
            String tip   = null;
            if (extStatsToggleBounds.contains(ev.x, ev.y)) {
                cursorId = SWT.CURSOR_HAND;
                tip = I18n.t("scope.stats.toggle.tooltip");
            } else if (extResetButtonBounds.contains(ev.x, ev.y)) {
                cursorId = SWT.CURSOR_HAND;
                tip = I18n.t("scope.stats.reset.tooltip");
            }
            c.setCursor(getDisplay().getSystemCursor(cursorId));
            c.setToolTipText(tip);
        });

        externalMeasurementShell  = s;
        externalMeasurementCanvas = c;
        resizeExternalShellToContent();
        org.eclipse.swt.graphics.Rectangle pb = parent.getBounds();
        s.setLocation(pb.x + pb.width - s.getSize().x - 24, pb.y + 96);
        s.addListener(SWT.Close, e -> {
            // Don't let the framework auto-dispose — we want
            // setTableExtracted to clear the state and redraw the main view.
            e.doit = false;
            setTableExtracted(false);
        });
        s.open();
    }

    /** Recomputes the tool window's size from the current stats-visibility
     *  state and applies it.  Width tracks the rightmost visible column
     *  (only {@code cur} when stats are off, all five when on).  Called
     *  when the shell is first built and whenever the stats toggle is
     *  flipped inside the tool window. */
    private void resizeExternalShellToContent() {
        Shell s = externalMeasurementShell;
        if (s == null || s.isDisposed()) return;
        boolean showStats = Preferences.instance().isOscShowStats();
        int lineH    = estimateMeasurementLineHeight();
        int tableH   = lineH * 9 + 6;
        int contentH = 5 + 22 + 2 + tableH;
        int rightmostColumn = showStats ? COL_SIGMA_RIGHT : COL_CUR_RIGHT;
        int contentW = rightmostColumn + 12;
        org.eclipse.swt.graphics.Rectangle trim = s.computeTrim(0, 0, contentW, contentH);
        s.setSize(trim.width, trim.height);
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

    /** Paint listener for {@link #externalMeasurementCanvas} — draws the
     *  stats-toggle + reset buttons at the top (when stats are on) and
     *  the measurement table immediately below.  Reads from the shared
     *  cached rows so updates from the measurement worker propagate
     *  identically to both places. */
    private void paintExternalMeasurementTable(org.eclipse.swt.events.PaintEvent ev) {
        Preferences prefs = Preferences.instance();
        if (buffer == null) return;
        if (!prefs.isOscShowMeasurementTable()) return;
        MeasurementRow[] rows = prepareMeasurementRows(prefs);
        if (rows == null) return;
        boolean showL = prefs.isOscLeftChannelEnabled();
        boolean showR = prefs.isOscRightChannelEnabled();
        Channel selected = prefs.getOscMeasurementChannel();

        ensureIconButtonResources();
        boolean showStats = prefs.isOscShowStats();
        final int btnW = 22, btnH = 22;
        final int by = 5;
        int sbX = COL_NAME_X;
        // Stats-toggle is always present inside the tool window so the
        // user can re-enable stats; reset only when stats are on.
        drawIconToggleButton(ev.gc, sbX, by, btnW, btnH,
                chartColumnIconLight, chartColumnIconDark, showStats);
        setBounds(extStatsToggleBounds, sbX, by, btnW, btnH);
        if (showStats) {
            int rsX = sbX + btnW + 2;
            drawIconPushButton(ev.gc, rsX, by, btnW, btnH, resetStatIcon);
            setBounds(extResetButtonBounds, rsX, by, btnW, btnH);
        } else {
            extResetButtonBounds.width = 0; extResetButtonBounds.height = 0;
        }
        drawMeasurementTableAt(ev.gc, rows, showL, showR, selected, by + btnH + 2);
    }

    @Override
    public void redraw() {
        super.redraw();
        // Mirror the redraw to the extracted table window so its data
        // tracks the main view in lock-step.
        if (externalMeasurementCanvas != null && !externalMeasurementCanvas.isDisposed()) {
            externalMeasurementCanvas.redraw();
        }
    }

    /**
     * Starts the background measurement worker.  Idempotent — calling while
     * the worker is already running is a no-op.  Invoked by
     * {@link OscilloscopeController#start()} once a fresh {@link SignalBuffer}
     * has been attached via {@link #setBuffer(SignalBuffer)}.
     */
    public void startMeasurementThread() {
        measWorker.start();
    }

    /**
     * Stops the background measurement worker and waits up to 2 s for it to
     * exit.  Called from {@link OscilloscopeController#stop()}.
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

    /** Draws the row of header buttons at the top of the measurement-table
     *  region: L / R channel selectors (always visible, always clickable —
     *  they also drive the vertical-offset slider's channel), a gauge
     *  toggle for the whole table, and — only when the table is shown —
     *  the chart-column stats toggle and the rotate-left reset-statistics
     *  button.  Hit-boxes for each visible button are recorded into the
     *  matching {@code *Bounds} field; hidden buttons have their bounds
     *  cleared so stale clicks don't fire. */
    private void drawMeasurementHeaderButtons(GC gc, boolean showL, boolean showR,
                                              Channel selected, boolean hasSignal) {
        if (monoFont == null) {
            monoFont = new Font(getDisplay(), "Consolas", 9, SWT.NORMAL);
        }
        if (chanButtonFont == null) {
            chanButtonFont = new Font(getDisplay(), "Consolas", 12, SWT.BOLD);
        }
        ensureIconButtonResources();
        Font prevFont = gc.getFont();
        gc.setAntialias(SWT.OFF);
        gc.setTextAntialias(SWT.ON);
        gc.setForeground(color(ColorRole.TEXT));
        gc.setBackground(color(ColorRole.BACKGROUND));

        final int y = 5;
        final int btnW = 22;
        final int btnH = 22;

        // 1) L / R channel-pick buttons — always visible.  They also pick
        //    which channel the vertical-offset slider controls, so they
        //    must stay clickable even when the channel is disabled.  Use
        //    a larger bold font so the L / R glyph reads at a glance.
        gc.setFont(chanButtonFont);
        int lbX = COL_NAME_X;
        int rbX = lbX + btnW + 2;
        drawChannelButton(gc, lbX, y, btnW, btnH, "L", true,
                selected == Channel.L, color(ColorRole.LEFT_TRACE), color(ColorRole.LEFT_CHANNEL_MID));
        drawChannelButton(gc, rbX, y, btnW, btnH, "R", true,
                selected == Channel.R, color(ColorRole.RIGHT_TRACE), color(ColorRole.RIGHT_CHANNEL_MID));
        setBounds(leftChanButtonBounds,  lbX, y, btnW, btnH);
        setBounds(rightChanButtonBounds, rbX, y, btnW, btnH);
        gc.setFont(monoFont);

        // 2) Auto-Setup — always visible.  When no signal is present the
        //    click is a no-op (handled by the callback), but keeping the
        //    button always shown means its position never shifts.
        int asX = rbX + btnW + 6;
        if (autoSetupIcon != null) {
            drawIconPushButton(gc, asX, y, btnW, btnH, autoSetupIcon);
            setBounds(autoSetupButtonBounds, asX, y, btnW, btnH);
        } else {
            autoSetupButtonBounds.width = 0; autoSetupButtonBounds.height = 0;
        }

        // 3) Gauge — independent toggle for showing / hiding the whole
        //    measurement table.  Only visible once a signal is present
        //    (recording in progress or a file loaded) — there's nothing
        //    to measure otherwise.  Same 6 px gap from the previous
        //    button as the R → Auto-Setup gap.
        int gbX = asX + btnW + 6;
        boolean showTable = Preferences.instance().isOscShowMeasurementTable();
        if (hasSignal) {
            drawIconToggleButton(gc, gbX, y, btnW, btnH,
                    gaugeIconLight, gaugeIconDark, showTable);
            setBounds(tableToggleBounds, gbX, y, btnW, btnH);
        } else {
            tableToggleBounds.width = 0; tableToggleBounds.height = 0;
        }

        // 4) External-window — toggles whether the table rows live in this
        //    view or in a separate tool window.  Visible only when the
        //    measurement table itself is on.
        int ewX = gbX + btnW + 2;
        if (hasSignal && showTable && windowRestoreIconLight != null) {
            drawIconToggleButton(gc, ewX, y, btnW, btnH,
                    windowRestoreIconLight, windowRestoreIconDark, tableExtracted);
            setBounds(externalWindowButtonBounds, ewX, y, btnW, btnH);
        } else {
            externalWindowButtonBounds.width = 0; externalWindowButtonBounds.height = 0;
        }

        // 5) Stats-toggle (chart-column) — visible when there's a signal
        //    AND the measurement table is shown AND the table is NOT
        //    extracted (when extracted, this button moves to the tool
        //    window — see paintExternalMeasurementTable).
        // 6) Reset-statistics (rotate-left, red) — same gating as stats
        //    toggle, plus only when statistics are enabled.
        boolean showStats = Preferences.instance().isOscShowStats();
        if (hasSignal && showTable && !tableExtracted) {
            int sbX = ewX + btnW + 2;
            drawIconToggleButton(gc, sbX, y, btnW, btnH,
                    chartColumnIconLight, chartColumnIconDark, showStats);
            setBounds(statsToggleBounds, sbX, y, btnW, btnH);

            if (showStats) {
                int rsX = sbX + btnW + 2;
                drawIconPushButton(gc, rsX, y, btnW, btnH, resetStatIcon);
                setBounds(resetButtonBoundsHeader, rsX, y, btnW, btnH);
            } else {
                resetButtonBoundsHeader.width = 0; resetButtonBoundsHeader.height = 0;
            }
        } else {
            statsToggleBounds.width = 0;       statsToggleBounds.height = 0;
            resetButtonBoundsHeader.width = 0; resetButtonBoundsHeader.height = 0;
        }

        gc.setFont(prevFont);
    }

    private static void setBounds(Rectangle r, int x, int y, int w, int h) {
        r.x = x; r.y = y; r.width = w; r.height = h;
    }

    private void drawMeasurementTable(GC gc, MeasurementRow[] rows,
                                      boolean showL, boolean showR, Channel selected) {
        // Main-view call site: the button row sits at y ∈ [5, 27), so the
        // table starts one line below.
        drawMeasurementTableAt(gc, rows, showL, showR, selected, 5 + 22 + 2);
    }

    /** Same as {@link #drawMeasurementTable} but the caller controls the
     *  starting y position.  The extracted tool window reuses this with a
     *  smaller offset (or a button-row offset when the stats/reset
     *  buttons live above the table). */
    private void drawMeasurementTableAt(GC gc, MeasurementRow[] rows,
                                        boolean showL, boolean showR, Channel selected,
                                        int startY) {
        if (monoFont == null) {
            monoFont = new Font(getDisplay(), "Consolas", 9, SWT.NORMAL);
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
     * Draws an L or R channel-select button.
     *
     * <ul>
     *   <li>Channel not captured ⇒ grey outline.  Click is ignored by the
     *       mouse handler.</li>
     *   <li>Captured + selected ⇒ filled with the bright channel colour,
     *       black letter — high contrast so the active channel pops.</li>
     *   <li>Captured + not selected ⇒ outlined with the dim channel colour;
     *       letter in the same dim colour.</li>
     * </ul>
     */
    private void drawChannelButton(GC gc, int x, int y, int w, int h,
                                   String label, boolean enabled, boolean selected,
                                   Color bright, Color dim) {
        Color paint = !enabled ? color(ColorRole.DISABLED_CHANNEL) : (selected ? bright : dim);
        if (enabled && selected) {
            gc.setBackground(paint);
            gc.fillRectangle(x, y, w, h);
            gc.setForeground(color(ColorRole.BACKGROUND));
        } else {
            gc.setForeground(paint);
            gc.setLineAttributes(lineAttrsThin);
            gc.drawRectangle(x, y, w - 1, h - 1);
            gc.setForeground(paint);
        }
        Point ts = gc.textExtent(label);
        gc.drawText(label, x + (w - ts.x) / 2, y + (h - ts.y) / 2, true);
    }

    /**
     * Draws the stats-toggle button: a small square with three white bars
     * of increasing height (a 3-bar histogram glyph) painted directly as
     * filled rectangles — no font dependency.  Outlined when stats are
     * shown; filled white with black bars when stats are hidden (the
     * "pressed" appearance, consistent with the L/R buttons' selected
     * state).
     */
    /** Lazily renders the SVG header-button icons in both a light tint (used
     *  when the button is outlined / off) and a dark tint (used when the
     *  button is filled / on).  Disposed with the view. */
    private void ensureIconButtonResources() {
        if (gaugeIconLight != null) return;
        Display d = getDisplay();
        RGB text = rgb(ColorRole.TEXT);
        RGB background  = rgb(ColorRole.BACKGROUND);
        RGB reset   = rgb(ColorRole.RESET);
        IconUtils icons = IconUtils.instance();
        gaugeIconLight       = icons.renderAtHeight(d, SvgPaths.GAUGE_HIGH,         16, text);
        gaugeIconDark        = icons.renderAtHeight(d, SvgPaths.GAUGE_HIGH,         16, background);
        chartColumnIconLight = icons.renderAtHeight(d, SvgPaths.CHART_COLUMN,       16, text);
        chartColumnIconDark  = icons.renderAtHeight(d, SvgPaths.CHART_COLUMN,       16, background);
        // Reset icon has no frame so it can use the extra 2 px of room.
        resetStatIcon       = icons.renderAtHeight(d, SvgPaths.ROTATE_LEFT,        18, reset);
        autoSetupIcon          = icons.renderAtHeight(d, SvgPaths.ARROWS_TO_CIRCLE,   16, text);
        windowRestoreIconLight = icons.renderAtHeight(d, SvgPaths.WINDOW_RESTORE,     16, text);
        windowRestoreIconDark  = icons.renderAtHeight(d, SvgPaths.WINDOW_RESTORE,     16, background);
    }

    /** Draws a toggleable icon button (outlined when {@code on} is false,
     *  filled with the overlay colour when {@code on} is true).  Uses the
     *  light icon variant on the outlined background and the dark variant
     *  on the filled background so the glyph stays readable in both states. */
    private void drawIconToggleButton(GC gc, int x, int y, int w, int h,
                                      Image iconLight, Image iconDark, boolean on) {
        if (on) {
            gc.setBackground(color(ColorRole.TEXT));
            gc.fillRectangle(x, y, w, h);
            drawCenteredIcon(gc, iconDark != null ? iconDark : iconLight, x, y, w, h);
        } else {
            gc.setForeground(color(ColorRole.TEXT));
            gc.setLineAttributes(lineAttrsThin);
            gc.drawRectangle(x, y, w - 1, h - 1);
            drawCenteredIcon(gc, iconLight, x, y, w, h);
        }
    }

    /** Draws a single-state push button — just the centred icon, no frame.
     *  Used for the reset-stats button so the red rotate-left glyph reads
     *  the same way the old red ↻ text glyph did. */
    private void drawIconPushButton(GC gc, int x, int y, int w, int h, Image iconLight) {
        drawCenteredIcon(gc, iconLight, x, y, w, h);
    }




    /**
     * Draws the three pane-edge sliders (signal offset on the left, trigger
     * level on the right, trigger position on the bottom) and the dashed
     * cross-hairs that mark the trigger level and position inside the grid.
     * Hit-boxes are refreshed each paint so the drag handler picks up the
     * latest geometry after a resize or a V/div change moves a handle.
     */
    private void drawSliders(GC gc, int w, int h) {
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
            monoFont = new Font(getDisplay(), "Consolas", 9, SWT.NORMAL);
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
        double levelFrac = ScopeFormat.clamp01(prefs.getOscTriggerLevelFrac());
        int levelY = (int) Math.round(levelFrac * h);
        Color levelColor = (trigCh == Channel.L) ? color(ColorRole.LEFT_TRACE) : color(ColorRole.RIGHT_TRACE);
        // Trigger-channel offset is intentionally NOT clamped to [0, 1] —
        // the trigger-level voltage label needs to track the channel offset
        // out to the extended ±FS-at-centre range the vertical scrollbar
        // can reach.
        double trigOffsetFrac = (trigCh == Channel.L)
                ? prefs.getOscLeftOffsetFrac()
                : prefs.getOscRightOffsetFrac();
        double trigVDiv  = (trigCh == Channel.L) ? leftVDiv : rightVDiv;
        double levelVolts = (trigOffsetFrac - levelFrac) * DIVISIONS_Y * trigVDiv;
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
            double posFrac = ScopeFormat.clamp01(prefs.getOscTriggerPositionFrac());
            int posX = (int) Math.round(posFrac * w);
            drawOutlinedDashedLine(gc, posX, 0, posX, h - 1, LONG_DASH, color(ColorRole.TEXT));
            drawUpPointingTriangle(gc, posX, h - 1, color(ColorRole.TEXT));
            triggerPosBounds.x      = posX - SLIDER_GRAB_HALF;
            triggerPosBounds.y      = h - SLIDER_TRI_LONG - 2;
            triggerPosBounds.width  = 2 * SLIDER_GRAB_HALF;
            triggerPosBounds.height = SLIDER_TRI_LONG + 4;
            double windowTime = timePerDiv * DIVISIONS_X;
            double posSeconds = (posFrac - 0.5) * windowTime;
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
        double peakVolts = AudioBackend.getAdcFsVoltageRms() * Math.sqrt(2.0);
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
    private void drawFullScaleLines(GC gc, int h, int w, int[] dash,
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
    private void drawOffsetTrack(GC gc, int h, int[] lineDash,
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
    private void drawRightPointingTriangle(GC gc, int xBase, int y, Color color) {
        int[] poly = {
                xBase,                      y - SLIDER_TRI_HALF,
                xBase + SLIDER_TRI_LONG,    y,
                xBase,                      y + SLIDER_TRI_HALF
        };
        drawOutlinedFilledPolygon(gc, poly, color);
    }

    /** Filled triangle whose tip points into the grid from the right edge. */
    private void drawLeftPointingTriangle(GC gc, int xBase, int y, Color color) {
        int[] poly = {
                xBase,                      y - SLIDER_TRI_HALF,
                xBase - SLIDER_TRI_LONG,    y,
                xBase,                      y + SLIDER_TRI_HALF
        };
        drawOutlinedFilledPolygon(gc, poly, color);
    }

    /** Filled triangle whose tip points up into the grid from the bottom edge. */
    private void drawUpPointingTriangle(GC gc, int x, int yBase, Color color) {
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
    private void drawEdgeLabels(GC gc, int w, int h) {
        Preferences prefs = Preferences.instance();
        double timePerDiv = prefs.getOscTimePerDiv();
        double leftVDiv   = prefs.getOscLeftVoltsPerDiv();
        double rightVDiv  = prefs.getOscRightVoltsPerDiv();
        boolean showL = prefs.isOscLeftChannelEnabled();
        boolean showR = prefs.isOscRightChannelEnabled();

        if (monoFont == null) {
            monoFont = new Font(getDisplay(), "Consolas", 9, SWT.NORMAL);
        }
        Font prevFont = gc.getFont();
        gc.setFont(monoFont);
        gc.setBackground(color(ColorRole.BACKGROUND));
        gc.setTextAntialias(SWT.ON);

        int centerX = w / 2;
        int centerY = h / 2;

        // ----- Time on the horizontal centre line × left / right edges.
        double windowTime = timePerDiv * DIVISIONS_X;
        double posFrac    = ScopeFormat.clamp01(prefs.getOscTriggerPositionFrac());
        double leftTime   = -posFrac * windowTime;
        double rightTime  = (1 - posFrac) * windowTime;
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
    private void updateSliderFromMouse(int mouseX, int mouseY) {
        Rectangle area = getClientArea();
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
                prefs.setOscTriggerPositionFrac(ScopeFormat.clamp01((double) mouseX / w));
                break;
        }
        redraw();
    }

    private void drawWaveforms(GC gc, int w, int h) {
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

        // Drop any leftover SINGLE state on entry to SINGLE mode so the live
        // trace inherited from AUTO / NORMAL keeps showing — a previously
        // captured frame only re-appears after the user arms a new shot.
        if (lastTriggerMode != triggerMode) {
            if (triggerMode == TriggerMode.SINGLE) {
                singleHeld  = false;
                singleArmed = false;
            }
            lastTriggerMode = triggerMode;
        }

        // Default this paint to "frozen content"; branches below flip the
        // flag when they render a genuinely new frame.
        lastFrameWasNew = false;

        // SINGLE held + not currently waiting for a new trigger: render the
        // dedicated captured-frame buffers and skip the live data flow
        // entirely.  The captured frame is independent of the ring buffer,
        // so it stays visible indefinitely (even after capture stops).
        boolean sincL = prefs.isOscLeftSincInterpEnabled();
        boolean sincR = prefs.isOscRightSincInterpEnabled();
        boolean acL = prefs.isOscLeftAcMode();
        boolean acR = prefs.isOscRightAcMode();
        if (triggerMode == TriggerMode.SINGLE && singleHeld && !singleArmed) {
            double[] dc = computeAcOffsetsForCaptured(buffer, acL, acR);
            renderTraces(gc, w, h, capturedLeft, capturedRight, capturedLen,
                         capturedDispStart, capturedSubSampleOffset, capturedDispCount,
                         showL, showR, leftVDiv, rightVDiv, sincL, sincR, dc[0], dc[1]);
            return;
        }

        SignalBuffer b = buffer;
        if (b == null) {
            // No live source, but a captured SINGLE frame may still be drawable.
            if (singleHeld) {
                double[] dc = computeAcOffsetsForCaptured(null, acL, acR);
                renderTraces(gc, w, h, capturedLeft, capturedRight, capturedLen,
                             capturedDispStart, capturedSubSampleOffset, capturedDispCount,
                             showL, showR, leftVDiv, rightVDiv, sincL, sincR, dc[0], dc[1]);
            }
            return;
        }

        double windowSeconds = timePerDiv * DIVISIONS_X;
        int displaySamples = (int) Math.round(windowSeconds * b.getSampleRate());
        if (displaySamples < 2) return;

        // Trigger position slider: 0 = trigger at left edge of display,
        // 0.5 = centred, 1 = right edge.  Computed early because both the
        // read size and the trigger search range depend on it.
        double triggerPosFrac = ScopeFormat.clamp01(prefs.getOscTriggerPositionFrac());

        // Read window must span at least one full display window before the
        // trigger AND one full display window after, so the trigger position
        // slider can be dragged from edge to edge without the display falling
        // off the buffer.  Plus ScopeLanczos.LANCZOS_PADDING on each side for the sinc
        // kernel, plus an extra lookback so the trigger search reliably
        // contains a rising edge even for low-frequency signals.
        int extraLookback = Math.min(b.getSampleRate(), ScopeMeasurementWorker.MEAS_MAX_SAMPLES);
        int wanted = 2 * displaySamples + 2 * ScopeLanczos.LANCZOS_PADDING + extraLookback;
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
        long viewEndAbs  = latestAbs - viewBackOffsetFrames;
        int available = b.readEndingAt(viewEndAbs, wanted,
                needL ? leftBuf  : null,
                needR ? rightBuf : null);
        if (available < 2) return;

        long bufStartAbs = viewEndAbs - available;

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
            int rightPadN = Math.min(ScopeLanczos.LANCZOS_PADDING, Math.max(0, available - displaySamples));
            int dispEndN  = available - rightPadN;
            int dispStartN = Math.max(0, dispEndN - displaySamples);
            int dispCountN = dispEndN - dispStartN;
            if (dispCountN < 2) return;
            double dcLn = acL ? acDcMean(true)  : 0.0;
            double dcRn = acR ? acDcMean(false) : 0.0;
            renderTraces(gc, w, h, leftBuf, rightBuf, available,
                         dispStartN, 0.0, dispCountN,
                         showL, showR, leftVDiv, rightVDiv,
                         sincL, sincR, dcLn, dcRn);
            return;
        }

        // Trigger search range: keep the resulting display window
        // [windowLeftT, windowLeftT + displaySamples) plus ScopeLanczos.LANCZOS_PADDING on
        // each side fully inside the read buffer.  The split is asymmetric
        // when the trigger-position slider isn't centred — at triggerPosFrac
        // = 0 the display extends one full window to the right of the
        // trigger, so the trigger has to lie at least that far back from
        // `available`; symmetric reasoning bounds the left side at
        // triggerPosFrac = 1.
        int leftHalf  = (int) Math.ceil(displaySamples * triggerPosFrac);
        int rightHalf = (int) Math.ceil(displaySamples * (1.0 - triggerPosFrac));
        int searchFrom = Math.max(1, ScopeLanczos.LANCZOS_PADDING + leftHalf + 1);
        int searchTo   = available - rightHalf - ScopeLanczos.LANCZOS_PADDING;
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
        double triggerPeakVolts = AudioBackend.getAdcFsVoltageRms() * Math.sqrt(2.0);
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
        debugBeatSignal = null;
        if ("DUAL_TONE".equalsIgnoreCase(prefs.getGenSignalForm())) {
            double f1 = prefs.getGenDualToneFreq1Hz();
            double f2 = prefs.getGenDualToneFreq2Hz();
            int sr = b.getSampleRate();
            if (f1 > 0 && f2 > 0 && sr > 0 && Math.abs(f2 - f1) > 0) {
                effectiveData = reconstructBeatSignal(triggerData, available, sr, f1, f2);
                effectiveSinc = false;
                // Cache the reconstruction for the overlay painter.
                // The overlay only renders when the user has the
                // "Reconstructed beat" checkbox on — gated inside
                // drawBeatOverlay so the trigger path keeps using
                // the reconstruction independently.
                debugBeatSignal = effectiveData;
            }
        }
        double triggerFrac = (searchTo > searchFrom)
                ? ScopeTrigger.find(effectiveData, available, searchFrom, searchTo,
                              effectiveTriggerLevel, rising, effectiveSinc, triggerHysteresis)
                : -1.0;
        boolean foundTrigger = (triggerFrac >= 0);

        int dispStart = 0;
        int dispCount = displaySamples;
        double subSampleOffset = 0.0;
        boolean haveFrame = false;

        // 1. Anchor the display on the latest trigger in any mode — that's
        //    what gives a stable triggered trace.  The window's left-edge
        //    sample position is `triggerFrac − displaySamples/2.0`, so the
        //    centre pixel maps exactly to triggerFrac.  Splitting that
        //    fractional edge into floor (dispStart) + fractional
        //    (subSampleOffset) keeps odd-length windows correctly centred —
        //    without the floating-point split, odd `displaySamples` produces
        //    a 0.5-sample bias.
        //
        //    Capture-to-frozen is a separate concern: only fires when SINGLE
        //    is currently armed (set by the Start button).
        if (foundTrigger) {
            int triggerInt = (int) Math.floor(triggerFrac);
            double triggerFracOffset = triggerFrac - triggerInt;
            double windowLeftT = triggerFrac - displaySamples * triggerPosFrac;
            dispStart = (int) Math.floor(windowLeftT);
            subSampleOffset = windowLeftT - dispStart;
            // Defensive clamp so the renderer never indexes past `available`
            // — the search-range bounds should guarantee this, but rounding
            // through subSampleOffset can shave a sample either way.
            if (dispStart < 0) {
                dispCount = Math.max(0, dispCount + dispStart);
                dispStart = 0;
            }
            if (dispStart + dispCount > available) {
                dispCount = available - dispStart;
            }
            lastTriggerAbsPos = bufStartAbs + triggerInt;
            lastTriggerSubSampleOffset = triggerFracOffset;
            // Every triggered paint counts as a new frame so cap/s tracks
            // the scope's actual paint rate, independent of the audio
            // backend's hardware dispatch rate.  Previously this was
            // gated on whether the trigger position had moved since the
            // last paint — but the trigger position only advances when a
            // fresh capture chunk arrives, which made cap/s appear
            // capped at the buffer-dispatch rate (~20 Hz on WASAPI's
            // 50 ms buffer vs. ~50-100 Hz on WDM-KS) even though the
            // SWT timer is already redrawing at the platform's native
            // ~64 Hz.  The metric now matches user perception of how
            // responsive the scope feels.
            lastFrameWasNew = true;
            if (triggerMode == TriggerMode.SINGLE && singleArmed) {
                captureSingleFrame(dispStart, displaySamples, available, subSampleOffset);
                singleArmed = false;
            }
            haveFrame = true;
        }

        // 2. Otherwise hold the previously triggered frame if it's still
        //    in-buffer.  Recompute dispStart / subSampleOffset for the
        //    current displaySamples (e.g. after the user changed time/div).
        if (!haveFrame && lastTriggerAbsPos >= 0) {
            double localTriggerFrac = (lastTriggerAbsPos - bufStartAbs) + lastTriggerSubSampleOffset;
            double windowLeftT = localTriggerFrac - displaySamples * triggerPosFrac;
            int holdStart = (int) Math.floor(windowLeftT);
            if (holdStart >= ScopeLanczos.LANCZOS_PADDING
                    && holdStart + displaySamples + ScopeLanczos.LANCZOS_PADDING <= available) {
                dispStart = holdStart;
                subSampleOffset = windowLeftT - holdStart;
                haveFrame = true;
            }
        }

        // 3. Last resort — AUTO and SINGLE free-run on the latest samples
        //    when no trigger has been seen (or the held one fell out of
        //    buffer).  This is what keeps the live trace visible after the
        //    user switches into SINGLE mode but before pressing Start, and
        //    while SINGLE is armed but the trigger hasn't fired yet.
        //    NORMAL deliberately leaves the pane blank in that case.
        if (!haveFrame) {
            if (triggerMode == TriggerMode.AUTO || triggerMode == TriggerMode.SINGLE) {
                int rightPad = Math.min(ScopeLanczos.LANCZOS_PADDING, Math.max(0, available - displaySamples));
                int dispEnd  = available - rightPad;
                dispStart = Math.max(0, dispEnd - displaySamples);
                dispCount = dispEnd - dispStart;
                subSampleOffset = 0.0;
                // Free-run: every paint shows the latest samples, so every
                // paint is a fresh frame.
                lastFrameWasNew = true;
            } else {
                return;
            }
        }
        if (dispCount < 2) return;

        // SINGLE-not-armed = frozen.  The very first redraw after entering
        // SINGLE (or after Record starts in SINGLE) reaches here; snapshot
        // the live frame into the captured-frame arrays and render that.
        // Subsequent redraws are intercepted by the top-of-method shortcut
        // and render the same snapshot without re-reading the ring buffer.
        // SINGLE-armed bypasses this and renders the live trace until the
        // trigger fires (at which point step 1 above does the capture).
        if (triggerMode == TriggerMode.SINGLE && !singleArmed) {
            if (!singleHeld) {
                captureSingleFrame(dispStart, displaySamples, available, subSampleOffset);
            }
            // SINGLE held: the captured frame is a frozen snapshot; subtract
            // its own DC so the frozen trace stays centred regardless of any
            // drift in the live data after the freeze.
            double dcL = acL ? ScopeFormat.windowMean(capturedLeft,  0, capturedLen) : 0.0;
            double dcR = acR ? ScopeFormat.windowMean(capturedRight, 0, capturedLen) : 0.0;
            renderTraces(gc, w, h, capturedLeft, capturedRight, capturedLen,
                         capturedDispStart, capturedSubSampleOffset, capturedDispCount,
                         showL, showR, leftVDiv, rightVDiv, sincL, sincR, dcL, dcR);
            return;
        }

        // AC DC removal: worker-published, ≥500 ms-averaged channel means.
        // Returns 0 during the AC_WARMUP_NANOS window after capture start
        // (worker hasn't published yet), so the trace shows the raw signal
        // — including any ADC settling transient — during that period.
        // After warmup the worker publishes a transient-free mean and the
        // trace snaps to centred.
        double dcL = acL ? acDcMean(true)  : 0.0;
        double dcR = acR ? acDcMean(false) : 0.0;
        renderTraces(gc, w, h, leftBuf, rightBuf, available,
                     dispStart, subSampleOffset, dispCount,
                     showL, showR, leftVDiv, rightVDiv, sincL, sincR, dcL, dcR);
        // Overlay the reconstructed |F1-F2| beat envelope on top of
        // the live trace.  Gated on DUAL_TONE form AND the user's
        // "Reconstructed beat" checkbox inside drawBeatOverlay.
        drawBeatOverlay(gc, w, h, dispStart, subSampleOffset, dispCount,
                available, triggerCh, leftVDiv, rightVDiv);
    }

    /** Paints the most-recently reconstructed |F1-F2| beat envelope
     *  ({@link #debugBeatSignal}) on top of the trigger channel's
     *  trace.  The overlay uses the trigger channel's V/div and
     *  offset so it sits in the same voltage domain as the trace,
     *  and the trigger channel's trace colour at 80 % brightness
     *  ("20 % darker") so it's visually paired with the channel
     *  driving the trigger.  Renders only when the generator is in
     *  DUAL_TONE mode (which is also the only condition under which
     *  {@link #debugBeatSignal} is non-null) AND the user has the
     *  "Reconstructed beat" checkbox enabled in the Trigger tab. */
    private void drawBeatOverlay(GC gc, int w, int h,
                                 int dispStart, double subSampleOffset, int dispCount,
                                 int dataLen, Channel triggerCh,
                                 double leftVDiv, double rightVDiv) {
        float[] beat = debugBeatSignal;
        if (beat == null || dispCount < 2 || w <= 0) return;
        Preferences prefs = Preferences.instance();
        if (!prefs.isOscShowReconstructedBeat()) return;
        double centerY = h * ((triggerCh == Channel.L)
                ? prefs.getOscLeftOffsetFrac() : prefs.getOscRightOffsetFrac());
        double vDiv    = (triggerCh == Channel.L) ? leftVDiv : rightVDiv;
        double pixelsPerDivY = (double) h / DIVISIONS_Y;
        double peakVolts     = AudioBackend.getAdcFsVoltageRms() * Math.sqrt(2.0);
        double vScale        = peakVolts / vDiv * pixelsPerDivY;
        Color beatColor = (triggerCh == Channel.L)
                ? color(ColorRole.LEFT_BEAT)
                : color(ColorRole.RIGHT_BEAT);
        drawTrace(gc, beat, dataLen, dispStart, subSampleOffset, dispCount,
                w, centerY, vScale, beatColor,
                /* sincEnabled = */ false, /* dcOffset = */ 0.0, /* dotDiameter = */ 0);
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
        float[] out = new float[available];
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
        float[] tmp = new float[available];
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

        float[] absLp = new float[available];
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
        for (int i = halfL; i < available - halfL; i++) {
            double ac = absLp[i] - dc;
            iSum += ac * Math.cos(omega * i);
            qSum += ac * Math.sin(omega * i);
        }
        double phase    = Math.atan2(qSum, iSum);
        double omegaMod = omega / 2.0;
        double phaseMod = phase  / 2.0;
        for (int i = 0; i < available; i++) {
            out[i] = (float) (rawPeak * Math.cos(omegaMod * i - phaseMod));
        }
        return out;
    }

    /**
     * Copies the samples around the just-found trigger into the captured-frame
     * arrays so they persist independently of the ring buffer.  Saves up to
     * two display windows centred on the trigger (i.e. {@code 2·displaySamples
     * + 2·ScopeLanczos.LANCZOS_PADDING}) so the renderer has at least half-a-window of
     * context on each side — that way the sinc kernel has full data even at
     * the pane edges, and a late Start that fires the trigger close to the
     * buffer end still shows the full screen.  When the ring buffer doesn't
     * yet hold the full ideal range (start of capture, very slow time/div)
     * we save whatever's available and clamp the in-capture display offset.
     */
    private void captureSingleFrame(int dispStart, int displaySamples,
                                    int available, double subSampleOffset) {
        int extra = displaySamples / 2;     // half a screen extra on each side → 2 screens total
        int idealStart = dispStart - ScopeLanczos.LANCZOS_PADDING - extra;
        int idealEnd   = dispStart + displaySamples + ScopeLanczos.LANCZOS_PADDING + extra;
        int srcStart = Math.max(0, idealStart);
        int srcEnd   = Math.min(available, idealEnd);
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
        singleHeld              = true;
    }

    /**
     * Renders the two channels from a (data, dispStart, subSampleOffset,
     * dispCount) tuple — shared by the live-data and captured-frame paths.
     * The sub-sample offset shifts the rendered signal by a fraction of a
     * sample so the trigger lands precisely on the centre pixel.
     */
    private void renderTraces(GC gc, int w, int h,
                              float[] dataLeft, float[] dataRight, int dataLen,
                              int dispStart, double subSampleOffset, int dispCount,
                              boolean showL, boolean showR,
                              double leftVDiv, double rightVDiv,
                              boolean sincEnabledL, boolean sincEnabledR,
                              double dcOffsetL, double dcOffsetR) {
        if (dispCount < 2) return;
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
        double peakVolts     = AudioBackend.getAdcFsVoltageRms() * Math.sqrt(2.0);
        float  lineWidth     = (float) prefs.getOscLineWidth();
        int    dotDiameter   = prefs.getOscDotDiameter();

        gc.setAntialias(SWT.ON);
        lineAttrsTrace.width = lineWidth;
        gc.setLineAttributes(lineAttrsTrace);
        if (showL) {
            double vScale = peakVolts / leftVDiv * pixelsPerDivY;
            drawTrace(gc, dataLeft,  dataLen, dispStart, subSampleOffset, dispCount,
                      w, leftCenterY, vScale, color(ColorRole.LEFT_TRACE), sincEnabledL, dcOffsetL, dotDiameter);
        }
        if (showR) {
            double vScale = peakVolts / rightVDiv * pixelsPerDivY;
            drawTrace(gc, dataRight, dataLen, dispStart, subSampleOffset, dispCount,
                      w, rightCenterY, vScale, color(ColorRole.RIGHT_TRACE), sincEnabledR, dcOffsetR, dotDiameter);
        }
    }

    /**
     * Returns {@code {dcL, dcR}} to subtract when rendering the captured
     * SINGLE frame in AC mode.  Prefers the worker-published, ≥ 500 ms-
     * averaged per-channel mean (same source as the measurement-table
     * Vmean) so the frozen trace and the table agree on the bias.  Falls
     * back to the captured frame's own mean only when the worker has never
     * run — i.e. {@link #lastMeasResult} is {@code null} (capture stopped
     * before the worker fired once).
     */
    private double[] computeAcOffsetsForCaptured(SignalBuffer b, boolean acL, boolean acR) {
        if (measWorker.getLastMeasResult() != null) {
            return new double[] {
                    acL ? acDcMean(true)  : 0.0,
                    acR ? acDcMean(false) : 0.0
            };
        }
        // No measurement worker output yet — fall back to the captured frame.
        double dcL = acL ? ScopeMeasurementWorker.sampleMean(capturedLeft,  capturedLen) : 0.0;
        double dcR = acR ? ScopeMeasurementWorker.sampleMean(capturedRight, capturedLen) : 0.0;
        return new double[] { dcL, dcR };
    }



    /**
     * Draws a waveform.  When {@code samplesPerPx ≤ ScopeLanczos.MAX_LANCZOS_DOWNSAMPLE}
     * the signal is band-limit-reconstructed via a Lanczos-windowed sinc kernel
     * scaled to the output rate (no aliasing into beat envelopes).  Above that
     * threshold per-column min/max bars take over.  A 5-px filled dot is
     * overlaid at each sample when sample spacing exceeds 10 px.
     *
     * <p>{@code subSampleOffset} (range [0, 1)) shifts the rendered signal by
     * a fraction of a sample in the input-sample direction — used by the
     * trigger logic to anchor the display centre on the band-limited zero
     * crossing instead of the nearest raw sample.
     */
    private void drawTrace(GC gc, float[] data, int n,
                                  int dispStart, double subSampleOffset, int dispCount,
                                  int width, double centerY, double vScale, Color color,
                                  boolean sincEnabled, double dcOffset, int dotDiameter) {
        if (dispCount < 2 || width <= 0) return;
        gc.setForeground(color);
        double samplesPerPx = (double) dispCount / width;
        double pxPerSample = (double) width / dispCount;
        if (samplesPerPx <= ScopeLanczos.MAX_LANCZOS_DOWNSAMPLE) {
            double scale = Math.max(1.0, samplesPerPx);
            Path path = new Path(gc.getDevice());
            try {
                if (sincEnabled) {
                    // Extend the path a few pixels past each canvas edge so
                    // the stroke (with antialiased CAP_ROUND caps) lands inside
                    // the canvas at full intensity instead of leaving a thin
                    // empty band at the left and right edges.  SWT clips the
                    // overshoot to the canvas naturally.  4 px each side covers
                    // the configured-max 5 px line width with a small safety
                    // margin.
                    final int padPixels = 4;
                    for (int x = -padPixels; x < width + padPixels; x++) {
                        double t = dispStart + subSampleOffset + x * samplesPerPx;
                        float yp = (float) (centerY - (ScopeLanczos.lanczos(data, n, t, scale) - dcOffset) * vScale);
                        if (x == -padPixels) path.moveTo(x, yp);
                        else                  path.lineTo(x, yp);
                    }
                } else {
                    // Linear path: extend by a couple of samples past each end
                    // so the straight-line segments cover the full canvas, not
                    // only the displayed-window samples.  Clamps to valid
                    // sample indices for the edges of the data buffer.
                    double dotShift = subSampleOffset * pxPerSample;
                    int padSamples = 2;
                    for (int i = -padSamples; i < dispCount + padSamples; i++) {
                        int dataIdx = dispStart + i;
                        if (dataIdx < 0)    dataIdx = 0;
                        if (dataIdx >= n)   dataIdx = n - 1;
                        float xp = (float) (i * pxPerSample - dotShift);
                        float yp = (float) (centerY - (data[dataIdx] - dcOffset) * vScale);
                        if (i == -padSamples) path.moveTo(xp, yp);
                        else                  path.lineTo(xp, yp);
                    }
                }
                gc.drawPath(path);
            } finally {
                path.dispose();
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
            // Per-column min/max bars at slow time/div.  Vertical 1-px-wide
            // bars don't benefit from AA or the Lanczos-branch CAP_ROUND /
            // JOIN_ROUND thick stroke, and the per-segment GDI overhead
            // dominates a per-paint cost otherwise.  Save the caller's AA
            // and line-attributes state, set the fast defaults, draw, then
            // restore — without the restore the next channel's Lanczos curve
            // would inherit the 1-px flat-cap stroke and look thinner.
            int dispLimit = Math.min(dispStart + dispCount, n);
            int prevAntialias = gc.getAntialias();
            LineAttributes prevLineAttributes = gc.getLineAttributes();
            gc.setAntialias(SWT.OFF);
            gc.setLineAttributes(lineAttrsBars);
            try {
                for (int x = 0; x < width; x++) {
                    int startIdx = dispStart + (int) (x * samplesPerPx + subSampleOffset);
                    int endIdx   = dispStart + (int) ((x + 1) * samplesPerPx + subSampleOffset);
                    if (endIdx <= startIdx) endIdx = startIdx + 1;
                    if (endIdx > dispLimit) endIdx = dispLimit;
                    if (startIdx < dispStart) startIdx = dispStart;
                    // Sub-sample offset or float rounding can push startIdx past
                    // the end of the read; without this clamp we get an
                    // ArrayIndexOutOfBoundsException on the data load below.
                    if (startIdx >= dispLimit) startIdx = dispLimit - 1;
                    if (startIdx < 0) continue;
                    float min = data[startIdx];
                    float max = data[startIdx];
                    for (int i = startIdx + 1; i < endIdx; i++) {
                        float v = data[i];
                        if (v < min) min = v;
                        if (v > max) max = v;
                    }
                    int yMin = (int) Math.round(centerY - (min - dcOffset) * vScale);
                    int yMax = (int) Math.round(centerY - (max - dcOffset) * vScale);
                    gc.drawLine(x, Math.min(yMin, yMax), x, Math.max(yMin, yMax));
                }
            } finally {
                gc.setAntialias(prevAntialias);
                gc.setLineAttributes(prevLineAttributes);
            }
        }
    }


    @Override
    protected void checkSubclass() {
        // Allow subclassing — Canvas is on SWT's restricted list otherwise.
    }
}
