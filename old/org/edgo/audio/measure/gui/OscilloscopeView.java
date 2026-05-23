package org.edgo.audio.measure.gui;

import lombok.extern.log4j.Log4j2;
import org.edgo.audio.measure.sound.CsjsoundRecorder;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.LineAttributes;
import org.eclipse.swt.graphics.Path;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;

/**
 * Oscilloscope main display.  Renders a black canvas with a 10×10 division
 * grid, a centre cross carrying 1/5-division minor tick marks, and (when a
 * {@link SignalBuffer} is attached via {@link #setBuffer(SignalBuffer)}) the
 * left/right channel waveforms scaled by the {@link Preferences}-stored
 * volts/division and time/division settings.
 */
@Log4j2
public final class OscilloscopeView extends Canvas {

    private static final int DIVISIONS_X    = 10;
    private static final int DIVISIONS_Y    = 10;
    private static final int TICKS_PER_DIV  = 5;
    private static final int TICK_HALF_LEN  = 3;

    private final Color background;
    private final Color gridColor;
    private final Color crossColor;
    private Color leftChannelColor;
    private Color rightChannelColor;
    private final Color overlayColor;     // light grey for cap/s and measurement readouts
    private final Color resetColor;       // red glyph for the reset-stats button
    private Color leftChannelDim;         // 25 % version of leftChannelColor  (unselected L/R button)
    private Color rightChannelDim;        // 25 % version of rightChannelColor (unselected L/R button)
    private Color leftChannelMid;         // 65 % version of leftChannelColor  (inactive offset triangle)
    private Color rightChannelMid;        // 65 % version of rightChannelColor (inactive offset triangle)
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
    private double  captureRate;
    /** Set by {@link #drawWaveforms} each paint; consumed by {@link #updateCaptureRate}. */
    private boolean lastFrameWasNew;
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
     * Latest computed measurements, owned by the measurement worker thread.
     * Read from the SWT thread in {@link #drawMeasurements}; written from
     * {@link #measurementLoop}.  {@code volatile} is sufficient because each
     * read either uses the freshest snapshot or skips display.
     */
    private volatile SignalMeasurements lastMeasResult;
    /**
     * Latest per-channel DC mean in normalised sample units ([-1, +1]),
     * published by the measurement worker.  Read by the paint thread to
     * remove the DC bias when AC coupling is on — same source as the
     * measurement table's {@code Vmean}, so what the user sees on screen
     * matches what the table reports.  Stay at 0 until the worker has run
     * at least once after capture starts.
     */
    private volatile double lastLeftMeanNormalized;
    private volatile double lastRightMeanNormalized;
    private static final long  MEAS_COMPUTE_PERIOD_NANOS = 100_000_000L;  // 100 ms = 10 Hz

    /**
     * Cap on samples used by per-paint, per-sample-rate work — measurement
     * window, trigger lookback, AC-offset estimation.  At low sample rates
     * the cap is never hit (sampleRate &lt; cap) and behaviour matches the
     * old "use the whole one-second history" approach; at 192 / 384 kHz it
     * bounds the per-paint cost so the SWT thread isn't blocked for hundreds
     * of milliseconds doing Goertzel scans or ring-buffer modulo loops.
     * 96 000 samples is one second at 96 kHz / quarter-second at 384 kHz.
     */
    private static final int MEAS_MAX_SAMPLES = 96_000;

    /**
     * Worker thread that computes signal measurements off the SWT thread.
     * Started by {@link #startMeasurementThread()} when capture begins and
     * stopped by {@link #stopMeasurementThread()} when capture ends.  All
     * reads of {@link #buffer} on the worker thread are safe because
     * {@link SignalBuffer#readLatest} is synchronised.
     */
    private Thread          measThread;
    private volatile boolean measThreadRunning;

    /** Guards multi-field updates to the measurement history ring. */
    private final Object measHistoryLock = new Object();

    /**
     * Dedicated buffers for the measurement read.  Sized at first use to
     * one second of audio (any rate up to ~200 kHz) so the period detector
     * and Goertzel scan see at least one full cycle for any audio-band
     * signal.  Kept separate from {@link #leftBuf}/{@link #rightBuf} so a
     * SINGLE held frame doesn't disturb live-signal measurements.
     */
    private float[] measLeftBuf;
    private float[] measRightBuf;

    /**
     * Circular history of measurement snapshots and their nano-timestamps —
     * fed once per paint and trimmed by age to the user-configured averaging
     * window for the avg / min / max / σ columns.  Sized for ~33 seconds at
     * 30 FPS so the window can extend up to ~30 s without ring overflow.
     */
    private static final int MEAS_HISTORY_CAP = 1024;
    private final SignalMeasurements[] measHistory = new SignalMeasurements[MEAS_HISTORY_CAP];
    private final long[] measHistoryTime = new long[MEAS_HISTORY_CAP];
    /**
     * Per-channel DC means stored alongside each entry of {@link #measHistory}
     * in normalised sample units.  The AC-mode display offset and AC trigger
     * level shift average over these to smooth out per-tick jitter; using a
     * 500 ms-or-longer window keeps the trace from drifting visibly between
     * worker ticks while still tracking slow drift in the input DC bias.
     */
    private final double[] meanHistoryLeftNorm  = new double[MEAS_HISTORY_CAP];
    private final double[] meanHistoryRightNorm = new double[MEAS_HISTORY_CAP];
    /** Minimum averaging window for AC DC removal — never average less than this even when prefs.avg is shorter. */
    private static final long AC_DC_MIN_AVG_NANOS = 500_000_000L;
    private int measHistoryWrite;
    private int measHistorySize;

    /** Monospace font for the measurement table — created lazily, disposed with the view. */
    private Font monoFont;

    /** Larger bold font used only for the red reset glyph (≈1.5× the table font). */
    private Font resetFont;

    /** Hit-boxes for the three header buttons — refreshed every paint, consumed by the mouse listener. */
    private final Rectangle leftChanButtonBounds  = new Rectangle(0, 0, 0, 0);
    private final Rectangle rightChanButtonBounds = new Rectangle(0, 0, 0, 0);

    /** Drawn-handle size for the three sliders, in pixels. */
    private static final int SLIDER_TRI_LONG = 10;   // triangle long axis (into the grid)
    private static final int SLIDER_TRI_HALF = 6;    // triangle perpendicular half-extent
    /** Hit-zone half-thickness in the perpendicular direction (extends past the triangle). */
    private static final int SLIDER_GRAB_HALF = 9;

    /** Hit-boxes for the three sliders — refreshed every paint, consumed by drag handlers. */
    private final Rectangle offsetSliderBounds   = new Rectangle(0, 0, 0, 0);
    private final Rectangle triggerLevelBounds   = new Rectangle(0, 0, 0, 0);
    private final Rectangle triggerPosBounds     = new Rectangle(0, 0, 0, 0);

    /** Slider currently being dragged ({@code null} ⇒ no drag in progress). */
    private SliderId draggingSlider;

    private enum SliderId {
        OFFSET, TRIGGER_LEVEL, TRIGGER_POSITION;
        private SliderId() {}
    }

    public OscilloscopeView(Composite parent) {
        super(parent, SWT.DOUBLE_BUFFERED);
        background        = new Color(getDisplay(),   0,   0,   0);
        gridColor         = new Color(getDisplay(),  60,  60,  60);
        crossColor        = new Color(getDisplay(), 110, 110, 110);
        overlayColor      = new Color(getDisplay(), 235, 235, 235);   // bright grey readouts
        resetColor        = new Color(getDisplay(), 220,  60,  60);   // red reset glyph
        disabledChannel   = new Color(getDisplay(), 130, 130, 130);   // grey when channel not captured
        // Channel colours are user-configurable in Preferences — initialise
        // them from the current settings and re-create on change.
        syncChannelColors();

        setBackground(background);
        addPaintListener(this::onPaint);
        addMouseListener(new org.eclipse.swt.events.MouseAdapter() {
            @Override
            public void mouseDown(org.eclipse.swt.events.MouseEvent ev) {
                if (ev.button != 1) return;
                if (resetButtonBounds.contains(ev.x, ev.y)) {
                    synchronized (measHistoryLock) {
                        measHistoryWrite = 0;
                        measHistorySize  = 0;
                    }
                    redraw();
                    return;
                }
                Preferences prefs = Preferences.instance();
                if (leftChanButtonBounds.contains(ev.x, ev.y)
                        && prefs.isOscLeftChannelEnabled()
                        && prefs.getOscMeasurementChannel() != TriggerChannel.L) {
                    prefs.setOscMeasurementChannel(TriggerChannel.L);
                    prefs.save();
                    synchronized (measHistoryLock) {
                        measHistoryWrite = 0;
                        measHistorySize  = 0;
                    }
                    redraw();
                    return;
                }
                if (rightChanButtonBounds.contains(ev.x, ev.y)
                        && prefs.isOscRightChannelEnabled()
                        && prefs.getOscMeasurementChannel() != TriggerChannel.R) {
                    prefs.setOscMeasurementChannel(TriggerChannel.R);
                    prefs.save();
                    synchronized (measHistoryLock) {
                        measHistoryWrite = 0;
                        measHistorySize  = 0;
                    }
                    redraw();
                    return;
                }
                // Slider hit detection — first match wins.  The handle is
                // grabbed and the slider value is updated immediately so a
                // click without a drag still moves the slider.
                if (offsetSliderBounds.contains(ev.x, ev.y)) {
                    draggingSlider = SliderId.OFFSET;
                } else if (triggerLevelBounds.contains(ev.x, ev.y)) {
                    draggingSlider = SliderId.TRIGGER_LEVEL;
                } else if (triggerPosBounds.contains(ev.x, ev.y)) {
                    draggingSlider = SliderId.TRIGGER_POSITION;
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
                    if (prefs.getOscMeasurementChannel() == TriggerChannel.L) {
                        prefs.setOscLeftOffsetFrac(0.5);
                    } else {
                        prefs.setOscRightOffsetFrac(0.5);
                    }
                    changed = true;
                } else if (triggerLevelBounds.contains(ev.x, ev.y)) {
                    prefs.setOscTriggerLevelFrac(0.5);
                    changed = true;
                } else if (triggerPosBounds.contains(ev.x, ev.y)) {
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
            if (offsetSliderBounds.contains(ev.x, ev.y)
                    || triggerLevelBounds.contains(ev.x, ev.y)) {
                cursorId = SWT.CURSOR_SIZENS;
            } else if (triggerPosBounds.contains(ev.x, ev.y)) {
                cursorId = SWT.CURSOR_SIZEWE;
            } else {
                cursorId = SWT.CURSOR_ARROW;
            }
            org.eclipse.swt.graphics.Cursor c = getDisplay().getSystemCursor(cursorId);
            if (getCursor() != c) setCursor(c);
        });
        addDisposeListener(e -> {
            background.dispose();
            gridColor.dispose();
            crossColor.dispose();
            leftChannelColor.dispose();
            rightChannelColor.dispose();
            overlayColor.dispose();
            resetColor.dispose();
            leftChannelDim.dispose();
            rightChannelDim.dispose();
            if (leftChannelMid  != null) leftChannelMid.dispose();
            if (rightChannelMid != null) rightChannelMid.dispose();
            disabledChannel.dispose();
            if (monoFont != null) monoFont.dispose();
            if (resetFont != null) resetFont.dispose();
        });
    }

    /** Attaches the live ring buffer.  {@code null} clears the waveform overlay. */
    public void setBuffer(SignalBuffer buffer) {
        this.buffer = buffer;
    }

    /** Returns the currently-attached ring buffer (or {@code null} if none). */
    public SignalBuffer getBuffer() {
        return buffer;
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
        // Snapshot under source's lock, write under ours.  Two distinct locks
        // so we don't accidentally deadlock if these views were ever live in
        // parallel (the screenshot use case doesn't need that, but the cost
        // of being safe is one extra synchronized block).
        SignalMeasurements snap = source.lastMeasResult;
        double leftMeanSnap  = source.lastLeftMeanNormalized;
        double rightMeanSnap = source.lastRightMeanNormalized;
        synchronized (source.measHistoryLock) {
            int cap = MEAS_HISTORY_CAP;
            SignalMeasurements[] hist = new SignalMeasurements[cap];
            long[]               t    = new long[cap];
            double[]             ml   = new double[cap];
            double[]             mr   = new double[cap];
            System.arraycopy(source.measHistory,         0, hist, 0, cap);
            System.arraycopy(source.measHistoryTime,     0, t,    0, cap);
            System.arraycopy(source.meanHistoryLeftNorm, 0, ml,   0, cap);
            System.arraycopy(source.meanHistoryRightNorm,0, mr,   0, cap);
            int w = source.measHistoryWrite;
            int s = source.measHistorySize;
            synchronized (this.measHistoryLock) {
                System.arraycopy(hist, 0, this.measHistory,         0, cap);
                System.arraycopy(t,    0, this.measHistoryTime,     0, cap);
                System.arraycopy(ml,   0, this.meanHistoryLeftNorm, 0, cap);
                System.arraycopy(mr,   0, this.meanHistoryRightNorm,0, cap);
                this.measHistoryWrite = w;
                this.measHistorySize  = s;
            }
        }
        this.lastMeasResult            = snap;
        this.lastLeftMeanNormalized    = leftMeanSnap;
        this.lastRightMeanNormalized   = rightMeanSnap;
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
            if (leftChannelDim   != null) leftChannelDim.dispose();
            if (leftChannelMid   != null) leftChannelMid.dispose();
            leftChannelColor = newColor(newL);
            leftChannelDim   = newColor(dimRgb(newL));
            leftChannelMid   = newColor(midRgb(newL));
            currentLeftRgb = newL;
        }
        if (newR != currentRightRgb) {
            if (rightChannelColor != null) rightChannelColor.dispose();
            if (rightChannelDim   != null) rightChannelDim.dispose();
            if (rightChannelMid   != null) rightChannelMid.dispose();
            rightChannelColor = newColor(newR);
            rightChannelDim   = newColor(dimRgb(newR));
            rightChannelMid   = newColor(midRgb(newR));
            currentRightRgb = newR;
        }
    }

    private Color newColor(int rgb) {
        return new Color(getDisplay(), (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }

    /** Returns {@code rgb} with each channel scaled to ≈¼ brightness. */
    private int dimRgb(int rgb) {
        int r = ((rgb >> 16) & 0xFF) / 4 + 5;
        int g = ((rgb >>  8) & 0xFF) / 4 + 5;
        int b = ( rgb        & 0xFF) / 4 + 5;
        return (r << 16) | (g << 8) | b;
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
        String text = String.format("%.1f cap/s", captureRate);
        Point ts = gc.textExtent(text);
        gc.drawText(text, w - ts.x - 8, 6, true);
    }

    /**
     * Pale-grey measurement table (Vpp / Vrms / Vmean / Tp / f / Duty) in
     * the top-left.  Reads from the live ring buffer so values stay accurate
     * even when a SINGLE held frame is on screen, and uses a measurement
     * window long enough to contain ≥ 1 cycle of any audio-band signal.
     * Avg / min / max / σ are aggregated across a sliding window of the
     * last {@code oscMeasurementAverageSeconds} of measurements.
     */
    private void drawMeasurements(GC gc) {
        SignalBuffer b = buffer;
        if (b == null) return;
        Preferences prefs = Preferences.instance();
        boolean showL = prefs.isOscLeftChannelEnabled();
        boolean showR = prefs.isOscRightChannelEnabled();
        if (!showL && !showR) return;

        // Auto-flip the selected measurement channel if its source channel has
        // been switched off — preserves "one channel always selected".
        TriggerChannel selected = prefs.getOscMeasurementChannel();
        if (selected == TriggerChannel.L && !showL && showR) {
            selected = TriggerChannel.R;
            prefs.setOscMeasurementChannel(selected);
            prefs.save();
            synchronized (measHistoryLock) {
                measHistoryWrite = 0;
                measHistorySize  = 0;
            }
        } else if (selected == TriggerChannel.R && !showR && showL) {
            selected = TriggerChannel.L;
            prefs.setOscMeasurementChannel(selected);
            prefs.save();
            synchronized (measHistoryLock) {
                measHistoryWrite = 0;
                measHistorySize  = 0;
            }
        }

        // Measurements are computed off the SWT thread by the worker started
        // in startMeasurementThread().  Paint just reads the latest snapshot.
        SignalMeasurements cur = lastMeasResult;
        if (cur == null) return;

        long windowNanos = (long) (prefs.getOscMeasurementAverageSeconds() * 1e9);
        long cutoff = System.nanoTime() - windowNanos;
        StatsBuilder vppS   = new StatsBuilder();
        StatsBuilder vrmsS  = new StatsBuilder();
        StatsBuilder vmeanS = new StatsBuilder();
        StatsBuilder tpS    = new StatsBuilder();
        StatsBuilder fS     = new StatsBuilder();
        StatsBuilder dutyS  = new StatsBuilder();
        // Take a brief lock so the history isn't being mutated mid-walk by
        // the worker thread.  The walk only spans up to MEAS_HISTORY_CAP
        // entries (~6 µs) so the worker rarely waits.
        synchronized (measHistoryLock) {
            for (int i = 0; i < measHistorySize; i++) {
                int idx = (measHistoryWrite - 1 - i + MEAS_HISTORY_CAP) % MEAS_HISTORY_CAP;
                if (measHistoryTime[idx] < cutoff) break;
                SignalMeasurements s = measHistory[idx];
                vppS  .add(s.getVpp());
                vrmsS .add(s.getVrms());
                vmeanS.add(s.getVmean());
                tpS   .add(s.getPeriod());
                fS    .add(s.getFrequency());
                dutyS .add(s.getDutyCycle());
            }
        }

        Row[] rows = {
                Row.forVolts("Vpp",   cur.getVpp(),       vppS),
                Row.forVolts("Vrms",  cur.getVrms(),      vrmsS),
                Row.forVolts("Vmean", cur.getVmean(),     vmeanS),
                Row.forTime ("Tp",    cur.getPeriod(),    tpS),
                Row.forFreq ("f",     cur.getFrequency(), fS),
                Row.forPct  ("Duty",  cur.getDutyCycle(), dutyS),
        };
        drawMeasurementTable(gc, rows, showL, showR, selected);
    }

    /**
     * Starts the background measurement worker.  Idempotent — calling while
     * the worker is already running is a no-op.  Invoked by
     * {@link OscilloscopeController#start()} once a fresh {@link SignalBuffer}
     * has been attached via {@link #setBuffer(SignalBuffer)}.
     */
    public void startMeasurementThread() {
        if (measThreadRunning) return;
        measThreadRunning = true;
        // Clear stale state from previous capture so the first paint after
        // start doesn't show measurements from the previous session.
        lastMeasResult = null;
        synchronized (measHistoryLock) {
            measHistoryWrite = 0;
            measHistorySize  = 0;
        }
        Thread t = new Thread(this::measurementLoop, "osc-measurement");
        t.setDaemon(true);
        t.start();
        measThread = t;
    }

    /**
     * Stops the background measurement worker and waits up to 2 s for it to
     * exit.  Called from {@link OscilloscopeController#stop()}.
     */
    public void stopMeasurementThread() {
        measThreadRunning = false;
        Thread t = measThread;
        if (t != null) {
            try { t.join(2000); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            measThread = null;
        }
    }

    /**
     * Measurement worker loop: sleeps for {@code MEAS_COMPUTE_PERIOD_NANOS}
     * between probes, reads the latest samples from the {@link SignalBuffer},
     * runs {@link SignalMeasurements#compute}, and stores the result for the
     * SWT thread to pick up at next paint.  Runs at fixed cadence (drift-
     * compensated) so the avg / min / max / σ stats are evenly spaced even
     * if a single compute occasionally overruns the period.
     */
    private void measurementLoop() {
        long nextWake = System.nanoTime() + MEAS_COMPUTE_PERIOD_NANOS;
        while (measThreadRunning) {
            long now = System.nanoTime();
            long sleepNs = nextWake - now;
            if (sleepNs > 0) {
                try {
                    Thread.sleep(sleepNs / 1_000_000L, (int) (sleepNs % 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (!measThreadRunning) return;
            try {
                computeMeasurementOnce();
            } catch (RuntimeException ex) {
                log.warn("measurement loop iteration failed: {}", ex.toString());
            }
            nextWake += MEAS_COMPUTE_PERIOD_NANOS;
            // Drift compensation: if a compute overran badly, snap forward so
            // we don't busy-loop trying to catch up.
            long lag = System.nanoTime() - nextWake;
            if (lag > MEAS_COMPUTE_PERIOD_NANOS) {
                nextWake = System.nanoTime() + MEAS_COMPUTE_PERIOD_NANOS;
            }
        }
    }

    /** Runs one measurement pass on the worker thread and updates the cache. */
    private void computeMeasurementOnce() {
        SignalBuffer b = buffer;
        if (b == null) return;
        Preferences prefs = Preferences.instance();
        TriggerChannel selected = prefs.getOscMeasurementChannel();
        int sampleRate = b.getSampleRate();
        int measN = Math.min(sampleRate, MEAS_MAX_SAMPLES);
        if (measLeftBuf == null || measLeftBuf.length < measN) {
            measLeftBuf  = new float[measN];
            measRightBuf = new float[measN];
        }
        // Always read both channels — even when only one is the measurement
        // selection — so we can publish a fresh per-channel DC mean for the
        // paint thread's AC-mode trace offset and AC-mode trigger-level shift.
        int avail = b.readLatest(measN, measLeftBuf, measRightBuf);
        if (avail < 64) return;
        double peakVolts = CsjsoundRecorder.adcFsVoltageRms * Math.sqrt(2.0);
        double leftMean  = sampleMean(measLeftBuf,  avail);
        double rightMean = sampleMean(measRightBuf, avail);
        float[] data = (selected == TriggerChannel.L) ? measLeftBuf : measRightBuf;
        SignalMeasurements result = SignalMeasurements.compute(data, avail, sampleRate, peakVolts);
        long now = System.nanoTime();
        synchronized (measHistoryLock) {
            measHistory[measHistoryWrite] = result;
            measHistoryTime[measHistoryWrite] = now;
            meanHistoryLeftNorm [measHistoryWrite] = leftMean;
            meanHistoryRightNorm[measHistoryWrite] = rightMean;
            measHistoryWrite = (measHistoryWrite + 1) % MEAS_HISTORY_CAP;
            if (measHistorySize < MEAS_HISTORY_CAP) measHistorySize++;
        }
        lastLeftMeanNormalized  = leftMean;
        lastRightMeanNormalized = rightMean;
        lastMeasResult = result;
    }

    /** Arithmetic mean of {@code data[0..n)}.  Returns 0 for empty inputs. */
    private double sampleMean(float[] data, int n) {
        if (data == null || n <= 0) return 0.0;
        double sum = 0;
        for (int i = 0; i < n; i++) sum += data[i];
        return sum / n;
    }

    /**
     * Returns the time-windowed average of one channel's recent DC means.
     * Walks {@link #meanHistoryLeftNorm} / {@link #meanHistoryRightNorm}
     * backwards in time from the most recent worker tick until the entry's
     * timestamp falls outside {@code windowNanos} ago.  Falls back to the
     * latest single-tick value when the history doesn't span the window
     * (the first few hundred ms after capture starts).
     */
    private double averagedChannelMean(boolean leftChannel, long windowNanos) {
        long cutoff = System.nanoTime() - windowNanos;
        double sum = 0;
        int count = 0;
        synchronized (measHistoryLock) {
            for (int i = 0; i < measHistorySize; i++) {
                int idx = (measHistoryWrite - 1 - i + MEAS_HISTORY_CAP) % MEAS_HISTORY_CAP;
                if (measHistoryTime[idx] < cutoff) break;
                sum += leftChannel ? meanHistoryLeftNorm[idx] : meanHistoryRightNorm[idx];
                count++;
            }
        }
        if (count > 0) return sum / count;
        // History too short for the requested window — use the latest snapshot
        // rather than 0 so AC removal isn't suddenly off-zero for half a second.
        return leftChannel ? lastLeftMeanNormalized : lastRightMeanNormalized;
    }

    /**
     * Convenience wrapper: averaging window taken from the user's measurement
     * preference, but never shorter than {@link #AC_DC_MIN_AVG_NANOS} so the
     * AC trace and trigger don't visibly wobble between worker ticks.
     */
    private double acDcMean(boolean leftChannel) {
        long windowNanos = Math.max(AC_DC_MIN_AVG_NANOS,
                (long) (Preferences.instance().getOscMeasurementAverageSeconds() * 1e9));
        return averagedChannelMean(leftChannel, windowNanos);
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
    /** Glyph used as the "reset measurement stats" button in the parameter-column header. */
    private static final String RESET_GLYPH = "↻";

    /**
     * Pixel-space hit-box of the reset glyph drawn each frame at the
     * parameter-column header.  Refreshed every paint; consumed by a
     * mouse-down listener registered in the constructor.
     */
    private final Rectangle resetButtonBounds = new Rectangle(0, 0, 0, 0);

    private void drawMeasurementTable(GC gc, Row[] rows,
                                      boolean showL, boolean showR, TriggerChannel selected) {
        if (monoFont == null) {
            monoFont = new Font(getDisplay(), "Consolas", 9, SWT.NORMAL);
        }
        if (resetFont == null) {
            // ~1.5× the table font and bold so the strokes are roughly 2 px.
            resetFont = new Font(getDisplay(), "Consolas", 14, SWT.BOLD);
        }
        Font prevFont = gc.getFont();
        gc.setFont(monoFont);
        gc.setAntialias(SWT.OFF);
        gc.setTextAntialias(SWT.ON);
        gc.setForeground(overlayColor);
        gc.setBackground(background);

        int lineH = gc.textExtent("M").y + 1;
        int y = 6;
        int chanBtnW = 18;
        int chanBtnH = Math.max(lineH, 16);

        // Header row: red reset glyph + L/R channel buttons + column titles.
        drawResetGlyph(gc, COL_NAME_X, y - 2, chanBtnH + 2);
        int lbX = COL_NAME_X + chanBtnH + 6;
        int rbX = lbX + chanBtnW + 2;
        drawChannelButton(gc, lbX, y - 1, chanBtnW, chanBtnH, "L", showL,
                selected == TriggerChannel.L, leftChannelColor, leftChannelDim);
        drawChannelButton(gc, rbX, y - 1, chanBtnW, chanBtnH, "R", showR,
                selected == TriggerChannel.R, rightChannelColor, rightChannelDim);
        leftChanButtonBounds .x = lbX; leftChanButtonBounds .y = y - 1;
        leftChanButtonBounds .width = chanBtnW; leftChanButtonBounds .height = chanBtnH;
        rightChanButtonBounds.x = rbX; rightChanButtonBounds.y = y - 1;
        rightChanButtonBounds.width = chanBtnW; rightChanButtonBounds.height = chanBtnH;

        gc.setFont(monoFont);
        gc.setForeground(overlayColor);
        for (int i = 0; i < HEADERS.length; i++) {
            drawRightAligned(gc, HEADERS[i], HEADER_RIGHTS[i], y);
        }
        y += lineH;
        for (Row r : rows) {
            gc.drawText(r.name, COL_NAME_X, y, true);
            drawRightAligned(gc, r.cur,   COL_CUR_RIGHT,  y);
            drawRightAligned(gc, r.avg,   COL_AVG_RIGHT,  y);
            drawRightAligned(gc, r.min,   COL_MIN_RIGHT,  y);
            drawRightAligned(gc, r.max,   COL_MAX_RIGHT,  y);
            drawRightAligned(gc, r.sigma, COL_SIGMA_RIGHT, y);
            y += lineH;
        }
        gc.setFont(prevFont);
    }

    /**
     * Red ↻ in 1.5×-bold so it visually reads as a thicker (~2 px) stroke.
     * Hit-box recorded into {@link #resetButtonBounds} for the mouse listener.
     */
    private void drawResetGlyph(GC gc, int x, int y, int targetSize) {
        gc.setFont(resetFont);
        gc.setForeground(resetColor);
        Point ts = gc.textExtent(RESET_GLYPH);
        gc.drawText(RESET_GLYPH, x, y, true);
        resetButtonBounds.x = x - 2;
        resetButtonBounds.y = y - 2;
        resetButtonBounds.width  = Math.max(ts.x, targetSize) + 4;
        resetButtonBounds.height = Math.max(ts.y, targetSize) + 4;
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
            gc.setLineAttributes(new LineAttributes(1.0f));
            gc.drawRectangle(x, y, w - 1, h - 1);
            gc.setForeground(paint);
        }
        Point ts = gc.textExtent(label);
        gc.drawText(label, x + (w - ts.x) / 2, y + (h - ts.y) / 2, true);
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
        TriggerChannel measCh = prefs.getOscMeasurementChannel();
        TriggerChannel trigCh = prefs.getOscTriggerChannel();
        double leftVDiv  = OscParse.voltsPerDiv(prefs.getOscLeftVoltsPerDivIdx());
        double rightVDiv = OscParse.voltsPerDiv(prefs.getOscRightVoltsPerDivIdx());
        double timePerDiv = OscParse.timePerDiv(prefs.getOscTimePerDivIdx());
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
        Color levelColor = (trigCh == TriggerChannel.L) ? leftChannelColor : rightChannelColor;
        double trigOffsetFrac = clamp01((trigCh == TriggerChannel.L)
                ? prefs.getOscLeftOffsetFrac()
                : prefs.getOscRightOffsetFrac());
        double trigVDiv  = (trigCh == TriggerChannel.L) ? leftVDiv : rightVDiv;
        double levelVolts = (trigOffsetFrac - levelFrac) * DIVISIONS_Y * trigVDiv;
        String levelStr = formatVolts(levelVolts);
        Point lvs = gc.textExtent(levelStr);

        int maxOffsetLabelW = 0;
        if (showL) {
            String s = formatVolts((0.5 - clamp01(prefs.getOscLeftOffsetFrac()))
                    * DIVISIONS_Y * leftVDiv);
            maxOffsetLabelW = Math.max(maxOffsetLabelW, gc.textExtent(s).x);
        }
        if (showR) {
            String s = formatVolts((0.5 - clamp01(prefs.getOscRightOffsetFrac()))
                    * DIVISIONS_Y * rightVDiv);
            maxOffsetLabelW = Math.max(maxOffsetLabelW, gc.textExtent(s).x);
        }
        int offsetLineRightEnd = w - SLIDER_TRI_LONG - 4 - lvs.x - 4;
        int levelLineStartX    = SLIDER_TRI_LONG + 4 + maxOffsetLabelW + 4;

        // ----- Trigger level: dotted horizontal cross-hair + handle on right
        // edge.  The dotted line is broken on both sides — short of the
        // value label on the right, and short of the offset labels on the
        // left — so neither overlay sits on top of the dotted track.
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

        // ----- Trigger position: long-dashed vertical cross-hair + handle on bottom edge.
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
        // Trigger-position value: signed time offset from screen centre.
        // Anchored above the voltage labels at the bottom of the centre-cross
        // so the two never collide regardless of triggerPosFrac.
        double windowTime = timePerDiv * DIVISIONS_X;
        double posSeconds = (posFrac - 0.5) * windowTime;
        String posStr = formatSeconds(posSeconds);
        Point pps = gc.textExtent(posStr);
        // Voltage labels at the centre-vertical / bottom edge sit at y ≈ h-12,
        // so the trigger-position label tops out above them.
        int posLabelY = h - SLIDER_TRI_LONG - 4 - pps.y - pps.y;
        int posLabelX = posX - pps.x / 2;
        if (posLabelX < 2) posLabelX = 2;
        if (posLabelX + pps.x > w - 2) posLabelX = w - 2 - pps.x;
        gc.setForeground(overlayColor);
        gc.drawText(posStr, posLabelX, posLabelY, true);

        // ----- Channel offsets: both visible channels get a full set of
        // marker elements (dashed zero-line, triangle, value label).  Only
        // the triangle's brightness distinguishes active (drag-enabled) from
        // inactive (read-only) — line and label stay at full brightness on
        // both so the position info is clearly readable for both channels.
        // Inactive is drawn first so any overlap at the same Y is won by the
        // active triangle and label.
        boolean activeIsL = (measCh == TriggerChannel.L);
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
        String label    = formatVolts((0.5 - offsetFrac) * DIVISIONS_Y * vDiv);
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
        double timePerDiv = OscParse.timePerDiv(prefs.getOscTimePerDivIdx());
        double leftVDiv   = OscParse.voltsPerDiv(prefs.getOscLeftVoltsPerDivIdx());
        double rightVDiv  = OscParse.voltsPerDiv(prefs.getOscRightVoltsPerDivIdx());
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
        gc.drawText(leftTimeStr,  4,             centerY - lts.y - 2, true);
        gc.drawText(rightTimeStr, w - rts.x - 4, centerY - rts.y - 2, true);

        // ----- Channel min / max on the vertical centre line × top / bottom edges.
        // Left channel labels sit just left of the vertical centre, right
        // channel just right of it; both at the top (max) and bottom (min) of
        // the grid, in each channel's colour.
        if (showL) {
            double off = clamp01(prefs.getOscLeftOffsetFrac());
            double maxV = off * DIVISIONS_Y * leftVDiv;
            double minV = -(1 - off) * DIVISIONS_Y * leftVDiv;
            String maxStr = formatVolts(maxV);
            String minStr = formatVolts(minV);
            Point mxs = gc.textExtent(maxStr);
            Point mns = gc.textExtent(minStr);
            gc.setForeground(leftChannelColor);
            gc.drawText(maxStr, centerX - mxs.x - 4, 2,                  true);
            gc.drawText(minStr, centerX - mns.x - 4, h - mns.y - 2,      true);
        }
        if (showR) {
            double off = clamp01(prefs.getOscRightOffsetFrac());
            double maxV = off * DIVISIONS_Y * rightVDiv;
            double minV = -(1 - off) * DIVISIONS_Y * rightVDiv;
            String maxStr = formatVolts(maxV);
            String minStr = formatVolts(minV);
            Point mns = gc.textExtent(minStr);
            gc.setForeground(rightChannelColor);
            gc.drawText(maxStr, centerX + 4, 2,                  true);
            gc.drawText(minStr, centerX + 4, h - mns.y - 2,      true);
        }

        gc.setFont(prevFont);
    }

    /** Volts with auto-prefix (V / mV / μV), 2 significant decimals. */
    private String formatVolts(double v) {
        double a = Math.abs(v);
        if (a >= 1.0)       return String.format(java.util.Locale.ROOT, "%.2f V",  v);
        if (a >= 1e-3)      return String.format(java.util.Locale.ROOT, "%.1f mV", v * 1e3);
        if (a >= 1e-6)      return String.format(java.util.Locale.ROOT, "%.1f µV", v * 1e6);
        if (a == 0)         return "0 V";
        return String.format(java.util.Locale.ROOT, "%.2g V", v);
    }

    /** Seconds with auto-prefix (s / ms / μs / ns), 2 significant decimals. */
    private String formatSeconds(double t) {
        double a = Math.abs(t);
        if (a >= 1.0)       return String.format(java.util.Locale.ROOT, "%.2f s",  t);
        if (a >= 1e-3)      return String.format(java.util.Locale.ROOT, "%.2f ms", t * 1e3);
        if (a >= 1e-6)      return String.format(java.util.Locale.ROOT, "%.1f µs", t * 1e6);
        if (a >= 1e-9)      return String.format(java.util.Locale.ROOT, "%.1f ns", t * 1e9);
        if (a == 0)         return "0 s";
        return String.format(java.util.Locale.ROOT, "%.2g s", t);
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
                if (prefs.getOscMeasurementChannel() == TriggerChannel.L) {
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
        double timePerDiv = OscParse.timePerDiv(prefs.getOscTimePerDivIdx());
        double leftVDiv   = OscParse.voltsPerDiv(prefs.getOscLeftVoltsPerDivIdx());
        double rightVDiv  = OscParse.voltsPerDiv(prefs.getOscRightVoltsPerDivIdx());
        boolean showL = prefs.isOscLeftChannelEnabled();
        boolean showR = prefs.isOscRightChannelEnabled();
        if (!showL && !showR) return;

        TriggerChannel triggerCh   = prefs.getOscTriggerChannel();
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
        boolean sincForRender = prefs.isOscSincInterpEnabled();
        boolean acL = prefs.isOscLeftAcMode();
        boolean acR = prefs.isOscRightAcMode();
        if (triggerMode == TriggerMode.SINGLE && singleHeld && !singleArmed) {
            double[] dc = computeAcOffsetsForCaptured(buffer, acL, acR);
            renderTraces(gc, w, h, capturedLeft, capturedRight, capturedLen,
                         capturedDispStart, capturedSubSampleOffset, capturedDispCount,
                         showL, showR, leftVDiv, rightVDiv, sincForRender, dc[0], dc[1]);
            return;
        }

        SignalBuffer b = buffer;
        if (b == null) {
            // No live source, but a captured SINGLE frame may still be drawable.
            if (singleHeld) {
                double[] dc = computeAcOffsetsForCaptured(null, acL, acR);
                renderTraces(gc, w, h, capturedLeft, capturedRight, capturedLen,
                             capturedDispStart, capturedSubSampleOffset, capturedDispCount,
                             showL, showR, leftVDiv, rightVDiv, sincForRender, dc[0], dc[1]);
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
        int extraLookback = Math.min(b.getSampleRate(), MEAS_MAX_SAMPLES);
        int wanted = 2 * displaySamples + 2 * LANCZOS_PADDING + extraLookback;
        // When SINGLE is armed, capture both channels so toggling L/R after
        // the freeze still shows real data instead of a stale buffer.
        boolean armedSingle = (triggerMode == TriggerMode.SINGLE) && singleArmed;
        boolean needL = showL || triggerCh == TriggerChannel.L || armedSingle;
        boolean needR = showR || triggerCh == TriggerChannel.R || armedSingle;
        if (leftBuf.length < wanted) {
            leftBuf  = new float[wanted];
            rightBuf = new float[wanted];
        }
        int available = b.readLatest(wanted,
                needL ? leftBuf  : null,
                needR ? rightBuf : null);
        if (available < 2) return;

        long latestAbs   = b.getWritePos();
        long bufStartAbs = latestAbs - available;

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
        float[] triggerData = (triggerCh == TriggerChannel.L) ? leftBuf : rightBuf;
        boolean rising = (triggerEdge == TriggerEdge.RISE);
        boolean sincEnabled = prefs.isOscSincInterpEnabled();
        // Trigger level comes from the user-controlled slider (right edge of
        // the canvas).  The slider value is a fraction of canvas height; the
        // corresponding normalised sample value depends on the trigger
        // channel's V/div and its current vertical offset (the channel's
        // zero-line lives at offsetFrac × h on screen).
        double triggerVDiv = (triggerCh == TriggerChannel.L) ? leftVDiv : rightVDiv;
        double triggerPeakVolts = CsjsoundRecorder.adcFsVoltageRms * Math.sqrt(2.0);
        double pixelsPerDivY = (double) h / DIVISIONS_Y;
        double vScaleTrig = triggerPeakVolts / triggerVDiv * pixelsPerDivY;
        double triggerCenterY = h * ((triggerCh == TriggerChannel.L)
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
        boolean acTrig = (triggerCh == TriggerChannel.L) ? acL : acR;
        // AC trigger shift reads the worker-published, ≥ 500 ms-averaged DC
        // mean for the trigger channel — same number the measurement
        // table's Vmean is derived from, so the dashed level line and the
        // comparator both fire at exactly the voltage the user sees.
        double trigDcOffset = acTrig ? acDcMean(triggerCh == TriggerChannel.L) : 0.0;
        float effectiveTriggerLevel = (float) (triggerLevel + trigDcOffset);
        // Hysteresis in normalised sample units (data[] is in [-1, +1]; multiply
        // by peakVolts to get volts).  The trigger channel's V/div sets the
        // div-to-volts conversion.
        float triggerHysteresis = (float) (prefs.getOscTriggerHysteresisDiv()
                * triggerVDiv / triggerPeakVolts);
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
            // AC DC removal uses the worker-published averaged means rather
            // than the captured frame's own mean, so the frozen trace and
            // the live measurement-table Vmean agree.
            double dcL = acL ? acDcMean(true)  : 0.0;
            double dcR = acR ? acDcMean(false) : 0.0;
            renderTraces(gc, w, h, capturedLeft, capturedRight, capturedLen,
                         capturedDispStart, capturedSubSampleOffset, capturedDispCount,
                         showL, showR, leftVDiv, rightVDiv, sincForRender, dcL, dcR);
            return;
        }

        // AC DC removal: pull the ≥ 500 ms-averaged channel means published
        // by the measurement worker.  Same source as the measurement-table
        // Vmean, so what the user sees on the trace matches the table.
        double dcL = acL ? acDcMean(true)  : 0.0;
        double dcR = acR ? acDcMean(false) : 0.0;
        renderTraces(gc, w, h, leftBuf, rightBuf, available,
                     dispStart, subSampleOffset, dispCount,
                     showL, showR, leftVDiv, rightVDiv, sincForRender, dcL, dcR);
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
                              double leftVDiv, double rightVDiv, boolean sincEnabled,
                              double dcOffsetL, double dcOffsetR) {
        if (dispCount < 2) return;
        Preferences prefs = Preferences.instance();
        // Per-channel vertical centre: offsetFrac in [0, 1] maps to the
        // Y coordinate where the channel's zero crossing renders.  0.5 ≡
        // canvas centre (the historical default).
        double leftCenterY  = h * prefs.getOscLeftOffsetFrac();
        double rightCenterY = h * prefs.getOscRightOffsetFrac();
        double pixelsPerDivY = (double) h / DIVISIONS_Y;
        double peakVolts     = CsjsoundRecorder.adcFsVoltageRms * Math.sqrt(2.0);
        float  lineWidth     = (float) prefs.getOscLineWidth();
        int    dotDiameter   = prefs.getOscDotDiameter();

        gc.setAntialias(SWT.ON);
        gc.setLineAttributes(new LineAttributes(lineWidth, SWT.CAP_ROUND, SWT.JOIN_ROUND));
        if (showL) {
            double vScale = peakVolts / leftVDiv * pixelsPerDivY;
            drawTrace(gc, dataLeft,  dataLen, dispStart, subSampleOffset, dispCount,
                      w, leftCenterY, vScale, leftChannelColor, sincEnabled, dcOffsetL, dotDiameter);
        }
        if (showR) {
            double vScale = peakVolts / rightVDiv * pixelsPerDivY;
            drawTrace(gc, dataRight, dataLen, dispStart, subSampleOffset, dispCount,
                      w, rightCenterY, vScale, rightChannelColor, sincEnabled, dcOffsetR, dotDiameter);
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
        if (lastMeasResult != null) {
            return new double[] {
                    acL ? acDcMean(true)  : 0.0,
                    acR ? acDcMean(false) : 0.0
            };
        }
        // No measurement worker output yet — fall back to the captured frame.
        double dcL = acL ? sampleMean(capturedLeft,  capturedLen) : 0.0;
        double dcR = acR ? sampleMean(capturedRight, capturedLen) : 0.0;
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
    private float lanczos(float[] data, int n, double t, double scale) {
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
        double sum = 0.0;
        for (int i = iLo; i <= iHi; i++) {
            int j = i - center + halfWidth - 1;       // tap index ∈ [0, 2·halfWidth)
            sum += data[i] * w[j];
        }
        return (float) sum;                            // weights already include 1/scale
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
                if (Math.abs(x) < 1e-9) {
                    row[j] = invScale;
                } else if (Math.abs(x) < LANCZOS_A) {
                    double pix  = Math.PI * x;
                    double pixA = pix / LANCZOS_A;
                    row[j] = (Math.sin(pix) / pix) * (Math.sin(pixA) / pixA) * invScale;
                } else {
                    row[j] = 0.0;
                }
            }
        }
        return table;
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
                    // Extend the path a few pixels past each canvas edge so the
                    // stroke (with antialiased CAP_ROUND caps) lands inside the
                    // canvas at full intensity instead of leaving a thin
                    // empty band at the left and right edges.  SWT clips the
                    // overshoot to the canvas naturally.
                    // 4 px each side covers the configured-max 5 px line
                    // width (so the antialiased cap radius lands outside the
                    // canvas) with a small safety margin.
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
            gc.setLineAttributes(new LineAttributes(1.0f, SWT.CAP_FLAT, SWT.JOIN_BEVEL));
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
            float val = lanczos(data, n, m, 1.0);
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
