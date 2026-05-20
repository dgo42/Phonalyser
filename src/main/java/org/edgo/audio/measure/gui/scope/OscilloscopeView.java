package org.edgo.audio.measure.gui.scope;

import java.util.Locale;

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
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.preferences.Preferences;
import org.edgo.audio.measure.gui.common.IconUtils;
import org.edgo.audio.measure.gui.common.SvgPaths;
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
public final class OscilloscopeView extends Canvas {

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

    private final Color background;
    private final Color gridColor;
    private final Color crossColor;
    private Color leftChannelColor;
    private Color rightChannelColor;
    private final Color overlayColor;     // light grey for cap/s and measurement readouts
    private final Color resetColor;       // red glyph for the reset-stats button
    private Color leftChannelMid;         // 65 % version of leftChannelColor  (inactive offset triangle + unselected L/R button)
    private Color rightChannelMid;        // 65 % version of rightChannelColor (inactive offset triangle + unselected L/R button)
    private final Color disabledChannel;  // grey for a channel that isn't currently captured
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
     * Row[] aggregation.  Limits how often the (relatively expensive)
     * String.format + stats walk runs — the canvas is still redrawn on every
     * paint, just with the cached values.
     */
    private static final long READOUT_THROTTLE_NS = 200_000_000L;
    private Row[]  cachedMeasurementRows;
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
    /** Pre-allocated near-white colours for the file-path blink — disposed
     *  in {@link #disposeColors()} together with the rest of the palette. */
    private Color filePathBlinkLitColor;
    private Color filePathBlinkDimColor;
    /** Trigger absolute position counted by the most recent capture-rate update — lets us tell a re-rendered hold-frame from a fresh trigger event. */
    private long    countedTriggerAbsPos = -1;

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
    private Image rotateLeftIcon;
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
        super(parent, SWT.DOUBLE_BUFFERED);
        // Chapter-level help anchor: Ctrl+F1 anywhere on the canvas
        // opens oscilloscope.html.  The view's header buttons /
        // measurement table aren't separate widgets (everything is
        // custom-painted on this single Canvas), so the deep anchors
        // documented in the help map to canvas regions but only the
        // chapter is reachable via the focus-walk shortcut.
        setData("helpAnchor", "oscilloscope.html");
        background        = new Color(getDisplay(),   0,   0,   0);
        gridColor         = new Color(getDisplay(),  60,  60,  60);
        crossColor        = new Color(getDisplay(), 110, 110, 110);
        overlayColor      = new Color(getDisplay(), 235, 235, 235);   // bright grey readouts
        resetColor        = new Color(getDisplay(), 220,  60,  60);   // red reset glyph
        disabledChannel   = new Color(getDisplay(), 130, 130, 130);   // grey when channel not captured
        filePathBlinkLitColor = new Color(getDisplay(), 0xFF, 0xFF, 0xFF);   // #FFFFFF
        filePathBlinkDimColor = new Color(getDisplay(), 0xAA, 0xAA, 0xAA);   // #AAAAAA
        // Channel colours are user-configurable in Preferences — initialise
        // them from the current settings and re-create on change.
        syncChannelColors();

        setBackground(background);
        addPaintListener(this::onPaint);
        addMouseListener(new org.eclipse.swt.events.MouseAdapter() {
            @Override
            public void mouseDown(org.eclipse.swt.events.MouseEvent ev) {
                if (ev.button != 1) return;
                Preferences prefs = Preferences.instance();
                // L / R channel-pick buttons — always clickable so the
                // measurement channel (and the vertical-offset slider's
                // channel) can be flipped even when the target channel
                // is currently disabled.
                if (leftChanButtonBounds.contains(ev.x, ev.y)
                        && prefs.getOscMeasurementChannel() != Channel.L) {
                    prefs.setOscMeasurementChannel(Channel.L);
                    prefs.save();
                    measWorker.clearHistory();
                    redraw();
                    return;
                }
                if (rightChanButtonBounds.contains(ev.x, ev.y)
                        && prefs.getOscMeasurementChannel() != Channel.R) {
                    prefs.setOscMeasurementChannel(Channel.R);
                    prefs.save();
                    measWorker.clearHistory();
                    redraw();
                    return;
                }
                // Auto-Setup — broadcast on the bus.  The scope pane
                // subscribes and re-fits the vertical / horizontal scales.
                if (autoSetupButtonBounds.contains(ev.x, ev.y)) {
                    MessageBus.instance().publish(Events.SCOPE_AUTO_SETUP);
                    return;
                }
                // External-Window — toggle the table-extract tool window.
                if (externalWindowButtonBounds.contains(ev.x, ev.y)) {
                    setTableExtracted(!tableExtracted);
                    return;
                }
                // Gauge — toggle whole measurement-table visibility.  The
                // "extracted" intent is preserved across this toggle:
                // hiding the table closes the tool window, showing it
                // again brings the tool window back (because
                // syncExternalShell reads both flags).
                if (tableToggleBounds.contains(ev.x, ev.y)) {
                    prefs.setOscShowMeasurementTable(!prefs.isOscShowMeasurementTable());
                    prefs.save();
                    syncExternalShell();
                    redraw();
                    return;
                }
                if (statsToggleBounds.contains(ev.x, ev.y)) {
                    prefs.setOscShowStats(!prefs.isOscShowStats());
                    prefs.save();
                    redraw();
                    return;
                }
                if (resetButtonBoundsHeader.contains(ev.x, ev.y)) {
                    measWorker.clearHistory();
                    redraw();
                    return;
                }
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
            } else if (resetButtonBoundsHeader.contains(ev.x, ev.y)) {
                cursorId = SWT.CURSOR_HAND;
                tip = I18n.t("scope.stats.reset.tooltip");
            } else if (leftChanButtonBounds.contains(ev.x, ev.y)) {
                cursorId = SWT.CURSOR_HAND;
                tip = I18n.t("scope.stats.left.tooltip");
            } else if (rightChanButtonBounds.contains(ev.x, ev.y)) {
                cursorId = SWT.CURSOR_HAND;
                tip = I18n.t("scope.stats.right.tooltip");
            } else if (autoSetupButtonBounds.contains(ev.x, ev.y)) {
                cursorId = SWT.CURSOR_HAND;
                tip = I18n.t("scope.autosetup.tooltip");
            } else if (externalWindowButtonBounds.contains(ev.x, ev.y)) {
                cursorId = SWT.CURSOR_HAND;
                tip = I18n.t("scope.external.window.tooltip");
            } else if (tableToggleBounds.contains(ev.x, ev.y)) {
                cursorId = SWT.CURSOR_HAND;
                tip = I18n.t("scope.stats.table.tooltip");
            } else if (statsToggleBounds.contains(ev.x, ev.y)) {
                cursorId = SWT.CURSOR_HAND;
                tip = I18n.t("scope.stats.toggle.tooltip");
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
            background.dispose();
            gridColor.dispose();
            crossColor.dispose();
            leftChannelColor.dispose();
            rightChannelColor.dispose();
            overlayColor.dispose();
            resetColor.dispose();
            if (leftChannelMid  != null) leftChannelMid.dispose();
            if (rightChannelMid != null) rightChannelMid.dispose();
            disabledChannel.dispose();
            if (filePathBlinkLitColor != null) filePathBlinkLitColor.dispose();
            if (filePathBlinkDimColor != null) filePathBlinkDimColor.dispose();
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
        this.countedTriggerAbsPos    = -1;
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
            if (leftChannelColor != null) leftChannelColor.dispose();
            if (leftChannelMid   != null) leftChannelMid.dispose();
            leftChannelColor = newColor(newL);
            leftChannelMid   = newColor(midRgb(newL));
            currentLeftRgb = newL;
        }
        if (newR != currentRightRgb) {
            if (rightChannelColor != null) rightChannelColor.dispose();
            if (rightChannelMid   != null) rightChannelMid.dispose();
            rightChannelColor = newColor(newR);
            rightChannelMid   = newColor(midRgb(newR));
            currentRightRgb = newR;
        }
    }

    private Color newColor(int rgb) {
        return new Color(getDisplay(), (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }


    /** Returns {@code rgb} with each channel scaled to ≈⅔ brightness — still
     *  clearly visible, but visibly less prominent than the fully-bright
     *  active variant. */
    private int midRgb(int rgb) {
        int r = (int) Math.round(((rgb >> 16) & 0xFF) * 0.65);
        int g = (int) Math.round(((rgb >>  8) & 0xFF) * 0.65);
        int b = (int) Math.round(( rgb        & 0xFF) * 0.65);
        return (r << 16) | (g << 8) | b;
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
        gc.setBackground(background);
        gc.fillRectangle(0, 0, w, h);
        drawGrid(gc, w, h);
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
        gc.setForeground(overlayColor);
        gc.setBackground(background);
        // Throttle the String.format call to ~5 Hz; drawText still runs every
        // paint so the readout never blanks.
        long now = System.nanoTime();
        if (cachedCapsString.isEmpty() || now - lastCapsBuildNanos >= READOUT_THROTTLE_NS) {
            cachedCapsString   = String.format("%.1f cap/s", captureRate);
            lastCapsBuildNanos = now;
        }
        Point ts = gc.textExtent(cachedCapsString);
        gc.drawText(cachedCapsString, w - ts.x - 8, 6, true);
    }

    /** Gap (px) between the cap/s readout and the file-path label drawn to its left. */
    private static final int FILE_PATH_GAP_TO_CAPS = 20;
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
            int rightLabelWidth = gc.textExtent(formatVolts(maxV, rightVDiv)).x;
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
        gc.setForeground(lit ? filePathBlinkLitColor : filePathBlinkDimColor);
        gc.setBackground(background);
        gc.drawText(shown, rightEdge - sExt.x, 6, true);

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

        Row[] rows = prepareMeasurementRows(prefs);
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
    private Row[] prepareMeasurementRows(Preferences prefs) {
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
        Row[] rows = cachedMeasurementRows;
        if (rows == null || now - lastMeasurementBuildNanos >= READOUT_THROTTLE_NS) {
            long windowNanos = (long) (prefs.getOscMeasurementAverageSeconds() * 1e9);
            long cutoff = now - windowNanos;
            StatsBuilder vppS   = new StatsBuilder();
            StatsBuilder vrmsS  = new StatsBuilder();
            StatsBuilder vmeanS = new StatsBuilder();
            StatsBuilder tpS    = new StatsBuilder();
            StatsBuilder trS    = new StatsBuilder();
            StatsBuilder tfS    = new StatsBuilder();
            StatsBuilder fS     = new StatsBuilder();
            StatsBuilder dutyS  = new StatsBuilder();
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
            rows = new Row[] {
                    Row.forVolts("Vpp",   cur.getVpp(),       vppS),
                    Row.forVolts("Vrms",  cur.getVrms(),      vrmsS),
                    Row.forVolts("Vmean", cur.getVmean(),     vmeanS),
                    Row.forTime ("Tp",    cur.getPeriod(),    tpS),
                    Row.forTime ("Tr",    cur.getRiseTime(),  trS),
                    Row.forTime ("Tf",    cur.getFallTime(),  tfS),
                    Row.forFreq ("f",     cur.getFrequency(), fS),
                    Row.forPct  ("Duty",  cur.getDutyCycle(), dutyS),
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
        c.setBackground(background);
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
        Row[] rows = prepareMeasurementRows(prefs);
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
            drawIconPushButton(ev.gc, rsX, by, btnW, btnH, rotateLeftIcon);
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
        gc.setForeground(overlayColor);
        gc.setBackground(background);

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
                selected == Channel.L, leftChannelColor, leftChannelMid);
        drawChannelButton(gc, rbX, y, btnW, btnH, "R", true,
                selected == Channel.R, rightChannelColor, rightChannelMid);
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
                drawIconPushButton(gc, rsX, y, btnW, btnH, rotateLeftIcon);
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

    private void drawMeasurementTable(GC gc, Row[] rows,
                                      boolean showL, boolean showR, Channel selected) {
        // Main-view call site: the button row sits at y ∈ [5, 27), so the
        // table starts one line below.
        drawMeasurementTableAt(gc, rows, showL, showR, selected, 5 + 22 + 2);
    }

    /** Same as {@link #drawMeasurementTable} but the caller controls the
     *  starting y position.  The extracted tool window reuses this with a
     *  smaller offset (or a button-row offset when the stats/reset
     *  buttons live above the table). */
    private void drawMeasurementTableAt(GC gc, Row[] rows,
                                        boolean showL, boolean showR, Channel selected,
                                        int startY) {
        if (monoFont == null) {
            monoFont = new Font(getDisplay(), "Consolas", 9, SWT.NORMAL);
        }
        Font prevFont = gc.getFont();
        gc.setFont(monoFont);
        gc.setAntialias(SWT.OFF);
        gc.setTextAntialias(SWT.ON);
        gc.setForeground(overlayColor);
        gc.setBackground(background);

        int lineH = gc.textExtent("M").y + 1;
        int y = startY;

        boolean showStats = Preferences.instance().isOscShowStats();

        gc.setFont(monoFont);
        gc.setForeground(overlayColor);
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
            gc.drawText(HEADERS[i], HEADER_RIGHTS[i] - headerExtents[i].x, y, true);
        }
        y += lineH;
        for (Row r : rows) {
            gc.drawText(r.name, COL_NAME_X, y, true);
            drawRightAligned(gc, r.cur,   COL_CUR_RIGHT,  y);
            if (showStats) {
                drawRightAligned(gc, r.avg,   COL_AVG_RIGHT,  y);
                drawRightAligned(gc, r.min,   COL_MIN_RIGHT,  y);
                drawRightAligned(gc, r.max,   COL_MAX_RIGHT,  y);
                drawRightAligned(gc, r.sigma, COL_SIGMA_RIGHT, y);
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
        Color paint = !enabled ? disabledChannel : (selected ? bright : dim);
        if (enabled && selected) {
            gc.setBackground(paint);
            gc.fillRectangle(x, y, w, h);
            gc.setForeground(background);
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
        RGB light = new RGB(235, 235, 235);     // matches overlayColor
        RGB dark  = new RGB(  0,   0,   0);     // matches canvas background
        RGB red   = new RGB(220,  60,  60);     // matches resetColor — red ↻ legacy
        IconUtils icons = IconUtils.instance();
        gaugeIconLight       = icons.renderAtHeight(d, SvgPaths.GAUGE_HIGH,         16, light);
        gaugeIconDark        = icons.renderAtHeight(d, SvgPaths.GAUGE_HIGH,         16, dark);
        chartColumnIconLight = icons.renderAtHeight(d, SvgPaths.CHART_COLUMN,       16, light);
        chartColumnIconDark  = icons.renderAtHeight(d, SvgPaths.CHART_COLUMN,       16, dark);
        // Reset icon has no frame so it can use the extra 2 px of room.
        rotateLeftIcon       = icons.renderAtHeight(d, SvgPaths.ROTATE_LEFT,        18, red);
        autoSetupIcon          = icons.renderAtHeight(d, SvgPaths.ARROWS_TO_CIRCLE,   16, light);
        windowRestoreIconLight = icons.renderAtHeight(d, SvgPaths.WINDOW_RESTORE,     16, light);
        windowRestoreIconDark  = icons.renderAtHeight(d, SvgPaths.WINDOW_RESTORE,     16, dark);
    }

    /** Draws a toggleable icon button (outlined when {@code on} is false,
     *  filled with the overlay colour when {@code on} is true).  Uses the
     *  light icon variant on the outlined background and the dark variant
     *  on the filled background so the glyph stays readable in both states. */
    private void drawIconToggleButton(GC gc, int x, int y, int w, int h,
                                      Image iconLight, Image iconDark, boolean on) {
        if (on) {
            gc.setBackground(overlayColor);
            gc.fillRectangle(x, y, w, h);
            drawCenteredIcon(gc, iconDark != null ? iconDark : iconLight, x, y, w, h);
        } else {
            gc.setForeground(overlayColor);
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

    private void drawCenteredIcon(GC gc, Image icon, int x, int y, int w, int h) {
        if (icon == null || icon.isDisposed()) return;
        org.eclipse.swt.graphics.Rectangle ib = icon.getBounds();
        int ix = x + (w - ib.width) / 2;
        int iy = y + (h - ib.height) / 2;
        gc.drawImage(icon, ix, iy);
    }

    private void drawRightAligned(GC gc, String s, int rightX, int y) {
        Point ts = gc.textExtent(s);
        gc.drawText(s, rightX - ts.x, y, true);
    }

    /**
     * One formatted row of the measurement table.  Picks a unit prefix based
     * on the current value's magnitude and renders cur / avg / min / max / σ
     * in that same prefix so the column entries line up.
     */
    private static final class Row {
        final String name;
        final String cur, avg, min, max, sigma;

        private Row(String name, String cur, String avg, String min, String max, String sigma) {
            this.name = name; this.cur = cur; this.avg = avg; this.min = min; this.max = max; this.sigma = sigma;
        }

        static Row forVolts(String base, double curVal, StatsBuilder s) {
            String unit; double scale;
            double m = peakMag(curVal, s);
            if (m >= 1)     { unit = "V";  scale = 1; }
            else if (m >= 1e-3) { unit = "mV"; scale = 1e3; }
            else if (m >= 1e-6) { unit = "μV"; scale = 1e6; }
            else                 { unit = "V";  scale = 1; }
            return scaled(base + ", " + unit, scale, curVal, s);
        }

        static Row forTime(String base, double curVal, StatsBuilder s) {
            String unit; double scale;
            double m = peakMag(curVal, s);
            if (m >= 1)     { unit = "s";  scale = 1; }
            else if (m >= 1e-3) { unit = "ms"; scale = 1e3; }
            else if (m >= 1e-6) { unit = "μs"; scale = 1e6; }
            else                 { unit = "s";  scale = 1; }
            return scaled(base + ", " + unit, scale, curVal, s);
        }

        static Row forFreq(String base, double curVal, StatsBuilder s) {
            // Hz with adaptive decimal places: cap the total digit count at
            // 7 (excluding the decimal point) so the column never overflows.
            return new Row(base + ", Hz",
                    fmtFreq(curVal),
                    fmtFreq(s.getMean()),
                    fmtFreq(s.getMin()),
                    fmtFreq(s.getMax()),
                    fmtFreq(s.getSigma()));
        }

        /**
         * Frequency formatter capped at 7 significant digits (excluding the
         * decimal point).  Below 100 kHz the readout keeps 0.01 Hz
         * resolution; between 100 kHz and 1 MHz it drops to 0.1 Hz; at or
         * above 1 MHz it rounds to whole hertz.  Magnitudes above ~10 MHz
         * shouldn't occur (the broad-band scan caps at sampleRate/4 ≤ ~250 kHz
         * at the highest supported sample rate), so the formatter falls back
         * to "%.0f" without further guards.
         */
        private static String fmtFreq(double v) {
            if (Double.isNaN(v)) return "---";
            double a = Math.abs(v);
            if (a >= 1_000_000.0) return String.format("%.0f", v);  // "1234567"
            if (a >= 100_000.0)   return String.format("%.1f", v);  // "999999.9"
            return String.format("%.2f", v);                        // "99999.99"
        }

        static Row forPct(String base, double curVal, StatsBuilder s) {
            // Duty cycle is stored as fraction [0,1]; show as percent.
            return scaled(base + ", %", 100, curVal, s);
        }

        private static double peakMag(double cur, StatsBuilder s) {
            double a = Double.isNaN(cur) ? 0 : Math.abs(cur);
            if (!Double.isNaN(s.getMean())) a = Math.max(a, Math.abs(s.getMean()));
            if (!Double.isNaN(s.getMax()))  a = Math.max(a, Math.abs(s.getMax()));
            if (!Double.isNaN(s.getMin()))  a = Math.max(a, Math.abs(s.getMin()));
            return a;
        }

        private static Row scaled(String name, double scale, double cur, StatsBuilder s) {
            return new Row(name,
                    fmt(cur * scale),
                    fmt(s.getMean()  * scale),
                    fmt(s.getMin()   * scale),
                    fmt(s.getMax()   * scale),
                    fmt(s.getSigma() * scale));
        }

        private static String fmt(double v) {
            if (Double.isNaN(v)) return "---";
            double a = Math.abs(v);
            if (a == 0)   return "0.000";
            if (a >= 1000) return String.format("%.1f", v);
            if (a >= 100)  return String.format("%.2f", v);
            return String.format("%.3f", v);
        }
    }

    /**
     * Online avg / min / max / variance accumulator (Welford).  Skips
     * {@link Double#NaN} inputs so missing-cycle samples don't poison the
     * window stats.
     */
    private static final class StatsBuilder {
        private int count;
        private double mean;
        private double m2;
        private double min =  Double.POSITIVE_INFINITY;
        private double max =  Double.NEGATIVE_INFINITY;

        void add(double v) {
            if (Double.isNaN(v)) return;
            count++;
            double delta = v - mean;
            mean += delta / count;
            m2 += delta * (v - mean);
            if (v < min) min = v;
            if (v > max) max = v;
        }
        double getMean()  { return count > 0 ? mean : Double.NaN; }
        double getMin()   { return count > 0 ? min  : Double.NaN; }
        double getMax()   { return count > 0 ? max  : Double.NaN; }
        double getSigma() { return count > 1 ? Math.sqrt(m2 / (count - 1)) : Double.NaN; }
    }

    private void drawGrid(GC gc, int w, int h) {
        double divW = (double) w / DIVISIONS_X;
        double divH = (double) h / DIVISIONS_Y;
        gc.setForeground(gridColor);
        for (int i = 0; i <= DIVISIONS_X; i++) {
            int x = (int) Math.round(i * divW);
            gc.drawLine(x, 0, x, h - 1);
        }
        for (int i = 0; i <= DIVISIONS_Y; i++) {
            int y = (int) Math.round(i * divH);
            gc.drawLine(0, y, w - 1, y);
        }

        int cx = (int) Math.round(DIVISIONS_X / 2.0 * divW);
        int cy = (int) Math.round(DIVISIONS_Y / 2.0 * divH);
        gc.setForeground(crossColor);
        gc.drawLine(cx, 0, cx, h - 1);
        gc.drawLine(0, cy, w - 1, cy);

        double tickX = divW / TICKS_PER_DIV;
        double tickY = divH / TICKS_PER_DIV;
        int totalTicks = DIVISIONS_X * TICKS_PER_DIV;
        for (int i = 0; i <= totalTicks; i++) {
            int x = (int) Math.round(i * tickX);
            int y = (int) Math.round(i * tickY);
            gc.drawLine(cx - TICK_HALF_LEN, y, cx + TICK_HALF_LEN, y);
            gc.drawLine(x, cy - TICK_HALF_LEN, x, cy + TICK_HALF_LEN);
        }
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
        gc.setBackground(background);
        gc.setTextAntialias(SWT.ON);

        // ----- Pre-compute opposite-side label widths so each horizontal
        // slider line can stop short of the OTHER side's label envelope.
        // Trigger-level label width bounds the offset-line right end; the
        // widest of the two channels' offset labels bounds the trigger-level
        // line left end.  Using the worst-case width yields a uniform stop
        // even at slider-Y positions where the lines would not actually
        // overlap, but the result reads as a clean broken track.
        double levelFrac = clamp01(prefs.getOscTriggerLevelFrac());
        int levelY = (int) Math.round(levelFrac * h);
        Color levelColor = (trigCh == Channel.L) ? leftChannelColor : rightChannelColor;
        // Trigger-channel offset is intentionally NOT clamped to [0, 1] —
        // the trigger-level voltage label needs to track the channel offset
        // out to the extended ±FS-at-centre range the vertical scrollbar
        // can reach.
        double trigOffsetFrac = (trigCh == Channel.L)
                ? prefs.getOscLeftOffsetFrac()
                : prefs.getOscRightOffsetFrac();
        double trigVDiv  = (trigCh == Channel.L) ? leftVDiv : rightVDiv;
        double levelVolts = (trigOffsetFrac - levelFrac) * DIVISIONS_Y * trigVDiv;
        String levelStr = formatVolts(levelVolts, trigVDiv);
        Point lvs = gc.textExtent(levelStr);

        int maxOffsetLabelW = 0;
        if (showL) {
            // No clamp01 — the offset-marker voltage label needs to honour
            // the extended offset range too (mirrors the trigger-level
            // label above).
            String s = formatVolts((0.5 - prefs.getOscLeftOffsetFrac())
                    * DIVISIONS_Y * leftVDiv, leftVDiv);
            maxOffsetLabelW = Math.max(maxOffsetLabelW, gc.textExtent(s).x);
        }
        if (showR) {
            String s = formatVolts((0.5 - prefs.getOscRightOffsetFrac())
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
            gc.setForeground(levelColor);
            gc.setLineWidth(1);
            gc.setLineDash(LONG_DASH);
            if (levelLineStartX < levelLineEnd) {
                gc.drawLine(levelLineStartX, levelY, levelLineEnd, levelY);
            }
            gc.setLineDash(null);
            drawLeftPointingTriangle(gc, w - 1, levelY, levelColor);
            triggerLevelBounds.x      = w - SLIDER_TRI_LONG - 2;
            triggerLevelBounds.y      = levelY - SLIDER_GRAB_HALF;
            triggerLevelBounds.width  = SLIDER_TRI_LONG + 4;
            triggerLevelBounds.height = 2 * SLIDER_GRAB_HALF;
            gc.setForeground(levelColor);
            gc.drawText(levelStr, levelLabelX, levelLabelY, true);
        } else {
            // Clear hit-test bounds so a leftover position from before
            // the mode switch can't intercept clicks.
            triggerLevelBounds.x = -1; triggerLevelBounds.y = -1;
            triggerLevelBounds.width = 0; triggerLevelBounds.height = 0;
        }

        // ----- Trigger position: long-dashed vertical cross-hair + handle on bottom edge.
        // Same fileMode-hide treatment for the trigger-position slider.
        if (!fileMode) {
            double posFrac = clamp01(prefs.getOscTriggerPositionFrac());
            int posX = (int) Math.round(posFrac * w);
            gc.setForeground(overlayColor);
            gc.setLineDash(LONG_DASH);
            gc.drawLine(posX, 0, posX, h - 1);
            gc.setLineDash(null);
            drawUpPointingTriangle(gc, posX, h - 1, overlayColor);
            triggerPosBounds.x      = posX - SLIDER_GRAB_HALF;
            triggerPosBounds.y      = h - SLIDER_TRI_LONG - 2;
            triggerPosBounds.width  = 2 * SLIDER_GRAB_HALF;
            triggerPosBounds.height = SLIDER_TRI_LONG + 4;
            double windowTime = timePerDiv * DIVISIONS_X;
            double posSeconds = (posFrac - 0.5) * windowTime;
            String posStr = formatSeconds(posSeconds);
            Point pps = gc.textExtent(posStr);
            int posLabelY = h - SLIDER_TRI_LONG - 4 - pps.y - pps.y;
            int posLabelX = posX - pps.x / 2;
            if (posLabelX < 2) posLabelX = 2;
            if (posLabelX + pps.x > w - 2) posLabelX = w - 2 - pps.x;
            gc.setForeground(overlayColor);
            gc.drawText(posStr, posLabelX, posLabelY, true);
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
                    clamp01(prefs.getOscLeftOffsetFrac()), leftVDiv,
                    peakVolts, pixelsPerDivY, leftChannelMid);
        }
        if (showR) {
            drawFullScaleLines(gc, h, w, FS_DASH,
                    clamp01(prefs.getOscRightOffsetFrac()), rightVDiv,
                    peakVolts, pixelsPerDivY, rightChannelMid);
        }

        boolean activeIsL = (measCh == Channel.L);
        boolean activeEnabled = activeIsL ? showL : showR;
        boolean otherEnabled  = activeIsL ? showR : showL;
        if (otherEnabled) {
            boolean otherIsL = !activeIsL;
            drawOffsetTrack(gc, h, DASH,
                    clamp01(otherIsL ? prefs.getOscLeftOffsetFrac()
                                     : prefs.getOscRightOffsetFrac()),
                    otherIsL ? leftVDiv : rightVDiv,
                    otherIsL ? leftChannelColor : rightChannelColor,
                    otherIsL ? leftChannelMid   : rightChannelMid,
                    false, offsetLineRightEnd);
        }
        if (activeEnabled) {
            Color c = activeIsL ? leftChannelColor : rightChannelColor;
            drawOffsetTrack(gc, h, DASH,
                    clamp01(activeIsL ? prefs.getOscLeftOffsetFrac()
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
        String label    = formatVolts((0.5 - offsetFrac) * DIVISIONS_Y * vDiv, vDiv);
        Point ts        = gc.textExtent(label);
        int labelX      = SLIDER_TRI_LONG + 4;
        int labelY      = offsetY - ts.y / 2;
        int lineStartX  = labelX + ts.x + 4;

        gc.setForeground(lineLabelColor);
        gc.setLineDash(lineDash);
        if (lineStartX < lineRightEnd) {
            gc.drawLine(lineStartX, offsetY, lineRightEnd, offsetY);
        }
        gc.setLineDash(null);

        drawRightPointingTriangle(gc, 0, offsetY, triangleColor);

        gc.setForeground(lineLabelColor);
        gc.drawText(label, labelX, labelY, true);

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
        gc.setBackground(color);
        gc.fillPolygon(poly);
    }

    /** Filled triangle whose tip points into the grid from the right edge. */
    private void drawLeftPointingTriangle(GC gc, int xBase, int y, Color color) {
        int[] poly = {
                xBase,                      y - SLIDER_TRI_HALF,
                xBase - SLIDER_TRI_LONG,    y,
                xBase,                      y + SLIDER_TRI_HALF
        };
        gc.setBackground(color);
        gc.fillPolygon(poly);
    }

    /** Filled triangle whose tip points up into the grid from the bottom edge. */
    private void drawUpPointingTriangle(GC gc, int x, int yBase, Color color) {
        int[] poly = {
                x - SLIDER_TRI_HALF,        yBase,
                x,                          yBase - SLIDER_TRI_LONG,
                x + SLIDER_TRI_HALF,        yBase
        };
        gc.setBackground(color);
        gc.fillPolygon(poly);
    }

    private double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
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
        gc.setBackground(background);
        gc.setTextAntialias(SWT.ON);

        int centerX = w / 2;
        int centerY = h / 2;

        // ----- Time on the horizontal centre line × left / right edges.
        double windowTime = timePerDiv * DIVISIONS_X;
        double posFrac    = clamp01(prefs.getOscTriggerPositionFrac());
        double leftTime   = -posFrac * windowTime;
        double rightTime  = (1 - posFrac) * windowTime;
        String leftTimeStr  = formatSeconds(leftTime);
        String rightTimeStr = formatSeconds(rightTime);
        Point lts = gc.textExtent(leftTimeStr);
        Point rts = gc.textExtent(rightTimeStr);
        gc.setForeground(overlayColor);
        int leftTimeX  = 4;
        int leftTimeY  = centerY - lts.y - 2;
        int rightTimeX = w - rts.x - 4;
        int rightTimeY = centerY - rts.y - 2;
        gc.drawText(leftTimeStr,  leftTimeX,  leftTimeY,  true);
        gc.drawText(rightTimeStr, rightTimeX, rightTimeY, true);
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
            String maxStr = formatVolts(maxV, leftVDiv);
            String minStr = formatVolts(minV, leftVDiv);
            Point mxs = gc.textExtent(maxStr);
            Point mns = gc.textExtent(minStr);
            gc.setForeground(leftChannelColor);
            int maxX = centerX - mxs.x - 4;
            int minX = centerX - mns.x - 4;
            int minY = h - mns.y - 2;
            gc.drawText(maxStr, maxX, 2,    true);
            gc.drawText(minStr, minX, minY, true);
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
            String maxStr = formatVolts(maxV, rightVDiv);
            String minStr = formatVolts(minV, rightVDiv);
            Point mxs = gc.textExtent(maxStr);
            Point mns = gc.textExtent(minStr);
            gc.setForeground(rightChannelColor);
            int maxX = centerX + 4;
            int minX = centerX + 4;
            int minY = h - mns.y - 2;
            gc.drawText(maxStr, maxX, 2,    true);
            gc.drawText(minStr, minX, minY, true);
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

    /** Volts with auto-prefix AND auto-precision based on {@code vpdiv}.  The
     *  display resolves to ≈ {@code vpdiv / 10} (one 1/10-div tick), so as
     *  the user picks a narrower V/div the label gains decimal places to
     *  keep the readout meaningful.  Used for the channel edge labels and
     *  the trigger-level voltage label. */
    private String formatVolts(double v, double vpdiv) {
        double a = Math.abs(v);
        String unit;
        double scaledV;
        double scaledRes;
        if (a >= 1.0)        { unit = "V";  scaledV = v;       scaledRes = vpdiv * 0.1; }
        else if (a >= 1e-3)  { unit = "mV"; scaledV = v * 1e3; scaledRes = vpdiv * 0.1 * 1e3; }
        else if (a >= 1e-6)  { unit = "µV"; scaledV = v * 1e6; scaledRes = vpdiv * 0.1 * 1e6; }
        else if (a == 0)     return "0 V";
        else                 return String.format(Locale.ROOT, "%.2g V", v);
        int dp;
        if (scaledRes >= 1.0)    dp = 1;
        else if (scaledRes > 0)  dp = (int) Math.ceil(-Math.log10(scaledRes)) + 1;
        else                     dp = 3;
        if (dp < 1) dp = 1;
        if (dp > 6) dp = 6;
        return String.format(Locale.ROOT, "%." + dp + "f " + unit, scaledV);
    }

    /** Seconds with auto-prefix (s / ms / μs / ns), 2 significant decimals. */
    private String formatSeconds(double t) {
        double a = Math.abs(t);
        if (a >= 1.0)       return String.format(Locale.ROOT, "%.2f s",  t);
        if (a >= 1e-3)      return String.format(Locale.ROOT, "%.2f ms", t * 1e3);
        if (a >= 1e-6)      return String.format(Locale.ROOT, "%.1f µs", t * 1e6);
        if (a >= 1e-9)      return String.format(Locale.ROOT, "%.1f ns", t * 1e9);
        if (a == 0)         return "0 s";
        return String.format(Locale.ROOT, "%.2g s", t);
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
                double frac = clamp01((double) mouseY / h);
                if (prefs.getOscMeasurementChannel() == Channel.L) {
                    prefs.setOscLeftOffsetFrac(frac);
                } else {
                    prefs.setOscRightOffsetFrac(frac);
                }
                break;
            }
            case TRIGGER_LEVEL:
                prefs.setOscTriggerLevelFrac(clamp01((double) mouseY / h));
                break;
            case TRIGGER_POSITION:
                prefs.setOscTriggerPositionFrac(clamp01((double) mouseX / w));
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
        double triggerPosFrac = clamp01(prefs.getOscTriggerPositionFrac());

        // Read window must span at least one full display window before the
        // trigger AND one full display window after, so the trigger position
        // slider can be dragged from edge to edge without the display falling
        // off the buffer.  Plus LANCZOS_PADDING on each side for the sinc
        // kernel, plus an extra lookback so the trigger search reliably
        // contains a rising edge even for low-frequency signals.
        int extraLookback = Math.min(b.getSampleRate(), ScopeMeasurementWorker.MEAS_MAX_SAMPLES);
        int wanted = 2 * displaySamples + 2 * LANCZOS_PADDING + extraLookback;
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
            int rightPadN = Math.min(LANCZOS_PADDING, Math.max(0, available - displaySamples));
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
        // [windowLeftT, windowLeftT + displaySamples) plus LANCZOS_PADDING on
        // each side fully inside the read buffer.  The split is asymmetric
        // when the trigger-position slider isn't centred — at triggerPosFrac
        // = 0 the display extends one full window to the right of the
        // trigger, so the trigger has to lie at least that far back from
        // `available`; symmetric reasoning bounds the left side at
        // triggerPosFrac = 1.
        int leftHalf  = (int) Math.ceil(displaySamples * triggerPosFrac);
        int rightHalf = (int) Math.ceil(displaySamples * (1.0 - triggerPosFrac));
        int searchFrom = Math.max(1, LANCZOS_PADDING + leftHalf + 1);
        int searchTo   = available - rightHalf - LANCZOS_PADDING;
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
        double triggerFrac = (searchTo > searchFrom)
                ? findTrigger(triggerData, available, searchFrom, searchTo,
                              effectiveTriggerLevel, rising, sincEnabled, triggerHysteresis)
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
            // New triggered frame only if the trigger event itself is new —
            // re-anchoring on the same absolute sample produces an identical
            // pixel output and shouldn't count as a fresh capture.
            if (lastTriggerAbsPos != countedTriggerAbsPos) {
                lastFrameWasNew = true;
                countedTriggerAbsPos = lastTriggerAbsPos;
            }
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
            if (holdStart >= LANCZOS_PADDING
                    && holdStart + displaySamples + LANCZOS_PADDING <= available) {
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
                int rightPad = Math.min(LANCZOS_PADDING, Math.max(0, available - displaySamples));
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
            double dcL = acL ? windowMean(capturedLeft,  0, capturedLen) : 0.0;
            double dcR = acR ? windowMean(capturedRight, 0, capturedLen) : 0.0;
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
    }

    /**
     * Copies the samples around the just-found trigger into the captured-frame
     * arrays so they persist independently of the ring buffer.  Saves up to
     * two display windows centred on the trigger (i.e. {@code 2·displaySamples
     * + 2·LANCZOS_PADDING}) so the renderer has at least half-a-window of
     * context on each side — that way the sinc kernel has full data even at
     * the pane edges, and a late Start that fires the trigger close to the
     * buffer end still shows the full screen.  When the ring buffer doesn't
     * yet hold the full ideal range (start of capture, very slow time/div)
     * we save whatever's available and clamp the in-capture display offset.
     */
    private void captureSingleFrame(int dispStart, int displaySamples,
                                    int available, double subSampleOffset) {
        int extra = displaySamples / 2;     // half a screen extra on each side → 2 screens total
        int idealStart = dispStart - LANCZOS_PADDING - extra;
        int idealEnd   = dispStart + displaySamples + LANCZOS_PADDING + extra;
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
                      w, leftCenterY, vScale, leftChannelColor, sincEnabledL, dcOffsetL, dotDiameter);
        }
        if (showR) {
            double vScale = peakVolts / rightVDiv * pixelsPerDivY;
            drawTrace(gc, dataRight, dataLen, dispStart, subSampleOffset, dispCount,
                      w, rightCenterY, vScale, rightChannelColor, sincEnabledR, dcOffsetR, dotDiameter);
        }
    }

    /** Mean of {@code data} over {@code [start, start+count)} — used for AC-mode DC removal. */
    private double windowMean(float[] data, int start, int count) {
        if (data == null || count <= 0) return 0.0;
        double s = 0;
        for (int i = 0; i < count; i++) s += data[start + i];
        return s / count;
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
     * Half-width (in samples per unit kernel scale) of the Lanczos window.
     * A=16 gives a much sharper transition band (~-45 dB stop-band) than
     * the more usual A=8 (~-25 dB), at the cost of 2× more sample reads per
     * output point.  Worth it: with A=8 enough energy near Nyquist leaks
     * through the filter to alias into visible beat-envelope artifacts on
     * dense high-frequency traces.
     */
    private static final int LANCZOS_A = 16;

    /**
     * Largest downsample factor for which the scaled Lanczos kernel is still
     * cheap enough to evaluate per pixel.  Beyond this we fall back to
     * per-column min/max bars.  Also determines the buffer padding needed
     * for full kernel coverage at the pane edges.
     */
    private static final int MAX_LANCZOS_DOWNSAMPLE = 5;

    /** Buffer padding (each side) so the widest kernel still has real context. */
    private static final int LANCZOS_PADDING = LANCZOS_A * MAX_LANCZOS_DOWNSAMPLE;

    /**
     * Lanczos-windowed sinc reconstruction of {@code data} at the (fractional)
     * sample-domain position {@code t}, with a kernel scaled to act as a
     * low-pass filter at the output rate ({@code scale = max(1, samplesPerPx)}).
     * For {@code scale = 1} this is the classic Whittaker–Shannon reconstruction
     * Σ x[i]·sinc(t-i); for {@code scale > 1} the kernel widens to
     * {@code sinc((t-i)/scale)} which kills the energy between the output
     * Nyquist and the input Nyquist — without that, downsampling to one
     * value per pixel would fold high-frequency content into low-frequency
     * beat envelopes.
     */
    /**
     * Lanczos sinc reconstruction at {@code t}, using a precomputed phase
     * table for whichever {@code scale} is currently in play — the inner
     * loop is now a plain table lookup with zero {@code Math.sin} calls,
     * which is the dominant per-pixel cost.  Works for any {@code scale},
     * so traces at slow time/div (scale &gt; 1, kernel widened for
     * anti-aliasing) benefit too, not just the {@code scale == 1} fast path.
     */
    private double lanczos(float[] data, int n, double t, double scale) {
        double[][] table = getKernelTable(scale);
        int halfWidth = (int) Math.ceil(LANCZOS_A * scale);
        int center = (int) Math.floor(t);
        double frac = t - center;
        int phase = (int) (frac * LANCZOS_PHASES);
        if (phase < 0) phase = 0;
        if (phase >= LANCZOS_PHASES) phase = LANCZOS_PHASES - 1;
        double[] w = table[phase];
        int iLo = Math.max(0, center - halfWidth + 1);
        int iHi = Math.min(n - 1, center + halfWidth);
        // Subtract a baseline from each sample before weighting so the
        // accumulated sum reflects DIFFERENCES between samples, not their
        // absolute magnitude.  Critical at extreme V/div: with samples
        // sitting near ±FS, the absolute values are ~1.0 in float; the
        // ~1e-7 float epsilon multiplied by a ~3e5 vScale becomes a
        // visible pixel deflection.  Working with (data[i] − baseline)
        // keeps the summed quantity small (just inter-sample deltas) and
        // the kernel is mathematically equivalent because the original
        // formula Σ data[i]·w[j] = baseline·Σw[j] + Σ(data[i]−baseline)·w[j].
        int centerClamped = (center < 0) ? 0 : (center >= n ? n - 1 : center);
        double baseline = data[centerClamped];
        double sumWeights = 0.0;
        double sumDeltas  = 0.0;
        for (int i = iLo; i <= iHi; i++) {
            int j = i - center + halfWidth - 1;       // tap index ∈ [0, 2·halfWidth)
            double wj = w[j];
            sumWeights += wj;
            sumDeltas  += (data[i] - baseline) * wj;
        }
        // Kernel rows are normalised to Σw = 1 (unit DC gain) for every
        // scale, so this evaluates to baseline + Σ(data[i] − baseline)·w[j]
        // — exactly the band-limited reconstruction at t with no implicit
        // amplitude scaling.  sumWeights is kept in the formula so the
        // edge-clamped case (where the kernel partially overhangs the
        // buffer) still returns a sensible value: the clamped Σw ≠ 1, and
        // multiplying baseline by it matches what the missing samples
        // would have contributed if they equalled baseline.
        return baseline * sumWeights + sumDeltas;
    }

    /** Phase resolution for the cached Lanczos kernel tables (≈ 0.001 sample precision). */
    private static final int LANCZOS_PHASES = 1024;

    /**
     * Two-slot table cache: one slot is permanently kept for {@code scale == 1}
     * (used by {@link #refineCrossing} and every fast-time/div render), and
     * the other slot holds whatever non-unit scale was last requested.  Two
     * slots are enough to avoid thrashing because each frame issues at most
     * those two scales — trigger refinement at 1.0 plus the current draw
     * scale.
     */
    private static volatile double[][] cachedKernelScale1;
    private static volatile double[][] cachedKernelOther;
    private static volatile double     cachedKernelOtherScale = Double.NaN;

    private double[][] getKernelTable(double scale) {
        if (scale == 1.0) {
            double[][] t = cachedKernelScale1;
            if (t != null) return t;
            synchronized (OscilloscopeView.class) {
                if (cachedKernelScale1 == null) cachedKernelScale1 = buildKernelTable(1.0);
                return cachedKernelScale1;
            }
        }
        double[][] t = cachedKernelOther;
        double s = cachedKernelOtherScale;
        if (t != null && s == scale) return t;
        synchronized (OscilloscopeView.class) {
            if (cachedKernelOther == null || cachedKernelOtherScale != scale) {
                cachedKernelOther = buildKernelTable(scale);
                cachedKernelOtherScale = scale;
            }
            return cachedKernelOther;
        }
    }

    /**
     * Pre-bakes the Lanczos kernel for {@code scale} into a
     * {@code [LANCZOS_PHASES][2·halfWidth]} table.  Each weight already
     * includes the 1/scale gain normalization so {@link #lanczos} just sums.
     *
     * <p>Two precision tricks live here for the "everything near full scale"
     * regime that turned up at 200 µV/div on a 2 V RMS signal:
     * <ul>
     *   <li>Each row is computed via {@link #sinc(double)} which uses a
     *       Taylor series for {@code |x| < 0.1} — the regime where the
     *       sin-x/x form has the worst conditioning (subtracting two nearly
     *       equal quantities of order {@code πx}).</li>
     *   <li>Every row is normalised at the end so {@code Σw = 1/scale}
     *       exactly (the nominal gain for a properly scaled Lanczos
     *       kernel).  Removes the few-ULP residual that the analytic form
     *       leaves behind — small in isolation but amplified by the
     *       3·10⁵ vScale at narrow V/div.</li>
     * </ul>
     */
    private double[][] buildKernelTable(double scale) {
        int halfWidth = (int) Math.ceil(LANCZOS_A * scale);
        int taps = 2 * halfWidth;
        double invScale = 1.0 / scale;
        double[][] table = new double[LANCZOS_PHASES][taps];
        for (int p = 0; p < LANCZOS_PHASES; p++) {
            double frac = (double) p / LANCZOS_PHASES;
            double[] row = table[p];
            for (int j = 0; j < taps; j++) {
                // tap j ↔ sample offset (j − halfWidth + 1) from the kernel
                // centre; x = (frac − offset) / scale.
                double x = (frac + (halfWidth - 1) - j) * invScale;
                if (Math.abs(x) >= LANCZOS_A) {
                    row[j] = 0.0;
                } else {
                    // Windowed sinc.  sinc(x) handles the |x| ≈ 0 case
                    // with a high-precision Taylor expansion; the outer
                    // sin(πx)/(πx) factor is the band-limit, the inner
                    // sin(πx/A)/(πx/A) is the Lanczos window.
                    row[j] = sinc(x) * sinc(x / LANCZOS_A) * invScale;
                }
            }
            // Row-normalise to unit DC gain (Σw = 1) regardless of the
            // ULP-scale drift from the sin / sinc math AND regardless of
            // the {@code scale} value.  The analytic {@code *invScale}
            // factor above is the textbook continuous-domain low-pass
            // gain for {@code scale > 1} (it makes Σw ≈ 1/scale before
            // normalisation), so dividing by the observed sum and
            // multiplying by 1 gets us exactly unit gain at all scales.
            // Without this normalisation the trace shrank by a factor
            // of {@code scale} once samplesPerPx > 1 — visible as a 2×
            // shrink at 1 ms/div and 4× at 2 ms/div on the SWT canvas.
            double s = 0.0;
            for (double v : row) s += v;
            if (s != 0.0) {
                double k = 1.0 / s;
                for (int j = 0; j < taps; j++) row[j] *= k;
            }
        }
        return table;
    }

    /**
     * {@code sinc(x) = sin(πx) / (πx)} with full double precision near 0.
     *
     * <p>For {@code |x| < 0.1}, evaluating {@code Math.sin(πx) / (πx)}
     * suffers from the same conditioning loss as a Taylor truncation: the
     * numerator and denominator are both near {@code πx}, so the relative
     * precision of the quotient hinges on the absolute precision of
     * {@code Math.sin(πx) − πx} which is only ~ULP of {@code πx}.  An
     * 8th-order Taylor series in {@code u = (πx)²} gives the same answer
     * to within {@code 1e-18} for {@code |x| < 0.1} without that
     * cancellation — well below machine epsilon.  For larger {@code |x|}
     * we fall back to the standard form where the cancellation is
     * benign.
     */
    private double sinc(double x) {
        if (x == 0.0) return 1.0;
        double pix = Math.PI * x;
        if (Math.abs(x) < 0.1) {
            double u = pix * pix;
            // 1 − u/6 + u²/120 − u³/5040 + u⁴/362880 + …
            // Horner form for fewer rounding ops:
            return 1.0 + u * (-1.0 / 6.0
                       + u * ( 1.0 / 120.0
                       + u * (-1.0 / 5040.0
                       + u * ( 1.0 / 362880.0
                       + u * (-1.0 / 39916800.0)))));
        }
        return Math.sin(pix) / pix;
    }


    /**
     * Draws a waveform.  When {@code samplesPerPx ≤ MAX_LANCZOS_DOWNSAMPLE}
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
        if (samplesPerPx <= MAX_LANCZOS_DOWNSAMPLE) {
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
                        float yp = (float) (centerY - (lanczos(data, n, t, scale) - dcOffset) * vScale);
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

    /**
     * Schmitt-style trigger scan: walks {@code data[from..to)} forward
     * tracking armed / fired state with lower and upper thresholds at
     * {@code level ± hysteresis}.  A rising trigger fires only when the signal
     * has first dropped below {@code level − hysteresis} (LOW) and then
     * crosses above {@code level + hysteresis} (HIGH); the trigger position
     * is the {@code level}-crossing inside that transition, refined either by
     * {@link #lanczos} bisection ({@code sincRefine}) or linear interpolation.
     * Returns the rightmost qualified trigger's fractional sample index, or
     * {@code -1.0} if none was found.  With {@code hysteresis == 0} the two
     * thresholds collapse onto {@code level}, recovering the previous
     * single-sample-bracket behaviour.
     */
    private double findTrigger(float[] data, int n, int from, int to,
                                      float level, boolean rising, boolean sincRefine,
                                      float hysteresis) {
        float lo = level - hysteresis;
        float hi = level + hysteresis;
        // Determine the incoming Schmitt state by walking back from `from`
        // until we find a sample firmly outside the dead-band.  Falling back
        // to the opposite-of-fire-direction lets a clean signal that starts
        // inside the dead-band still produce a first trigger.
        int state = 0;     // -1 = LOW, +1 = HIGH, 0 = unknown
        for (int j = from - 1; j >= 0; j--) {
            if (data[j] <= lo) { state = -1; break; }
            if (data[j] >= hi) { state = +1; break; }
        }
        if (state == 0) state = rising ? -1 : +1;

        double lastTrigger = -1.0;
        double pendingCrossing = -1.0;     // most-recent `level` crossing while still in the opposite state
        float prev = data[from - 1];
        for (int i = from; i < to; i++) {
            float curr = data[i];
            if (rising) {
                if (prev < level && curr >= level) {
                    pendingCrossing = sincRefine
                            ? refineCrossing(data, n, i - 1, i, level, true)
                            : linearCrossing(prev, curr, i - 1, level);
                }
                if (curr <= lo) {
                    state = -1;
                } else if (curr >= hi) {
                    if (state == -1 && pendingCrossing >= 0) {
                        lastTrigger = pendingCrossing;
                    }
                    state = +1;
                    pendingCrossing = -1.0;
                }
            } else {
                if (prev > level && curr <= level) {
                    pendingCrossing = sincRefine
                            ? refineCrossing(data, n, i - 1, i, level, false)
                            : linearCrossing(prev, curr, i - 1, level);
                }
                if (curr >= hi) {
                    state = +1;
                } else if (curr <= lo) {
                    if (state == +1 && pendingCrossing >= 0) {
                        lastTrigger = pendingCrossing;
                    }
                    state = -1;
                    pendingCrossing = -1.0;
                }
            }
            prev = curr;
        }
        return lastTrigger;
    }

    /** Linear interpolation of the {@code level}-crossing between samples at indices {@code prevIdx} and {@code prevIdx + 1}. */
    private double linearCrossing(float prev, float curr, int prevIdx, float level) {
        float denom = curr - prev;
        if (denom == 0) return prevIdx;
        return prevIdx + (level - prev) / denom;
    }

    /**
     * Bisects the sinc-interpolated signal between {@code a} and {@code b}
     * to find the precise crossing of {@code level}.  10 iterations give
     * sub-millisample precision (2⁻¹⁰ ≈ 0.001 sample).  Uses the unit-scale
     * Lanczos kernel — i.e. the band-limited reconstruction at the input
     * sample rate, which is what the renderer draws when {@code samplesPerPx ≤ 1}.
     */
    private double refineCrossing(float[] data, int n, double a, double b,
                                         float level, boolean rising) {
        for (int iter = 0; iter < 10; iter++) {
            double m = 0.5 * (a + b);
            double val = lanczos(data, n, m, 1.0);
            boolean atRightSide = rising ? (val >= level) : (val <= level);
            if (atRightSide) b = m;
            else             a = m;
        }
        return 0.5 * (a + b);
    }

    @Override
    protected void checkSubclass() {
        // Allow subclassing — Canvas is on SWT's restricted list otherwise.
    }
}
