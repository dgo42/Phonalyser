package org.edgo.audio.measure.gui.freqresp;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Cursor;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.edgo.audio.measure.cli.util.FreqRespCalHelper;
import org.edgo.audio.measure.cli.util.FreqRespCalibration;
import org.edgo.audio.measure.cli.util.StereoFreqRespCalibration;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.AbstractFreqDomainView;
import org.edgo.audio.measure.gui.common.IconUtils;
import org.edgo.audio.measure.gui.common.SvgPaths;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.preferences.Preferences;

import lombok.extern.log4j.Log4j2;

/**
 * Canvas widget that paints the Frequency Response trace, grid, axes,
 * crosshair cursor, and a row of four header buttons (L toggle, R toggle,
 * Phase toggle, Maximize).  Mirrors {@code FftView}'s architecture:
 *
 * <ul>
 *   <li>Mouse wheel zoom / pan: {@code Ctrl+Shift} = freq zoom around the
 *       cursor, {@code Ctrl} = magnitude zoom, {@code Shift} = freq pan,
 *       plain wheel = magnitude pan.  Every change persists to
 *       {@link Preferences} and publishes
 *       {@link Events#FREQRESP_RANGE_CHANGED} so the host pane's
 *       scrollbars can re-sync.</li>
 *   <li>Crosshair cursor with floating L / R magnitude (in dB) and phase
 *       (when the phase trace is visible) readout under the pointer.  The
 *       max-frequency readout is clipped at
 *       {@link Preferences#getFreqRespNyquistFraction()} × sample rate so
 *       the user doesn't see meaningless numbers near Nyquist.</li>
 *   <li>Header buttons painted directly on the canvas (no SWT controls),
 *       mirroring the FFT view's button row so click hit-tests are exact
 *       even when widgets overlap the trace.</li>
 * </ul>
 *
 * <p>This Phase-4 skeleton holds the L / R result slots, paints traces
 * end-to-end when results are present, and wires the zoom / pan + crosshair
 * logic so the host pane can build scrollbars against it now.  The
 * measurement worker (Phase 7) feeds the result slots later.
 */
@Log4j2
public final class FreqRespView extends AbstractFreqDomainView {

    // --- Plot geometry -------------------------------------------------------
    private static final int MARGIN_LEFT     = 68;
    // Top / right margins are 0 by design — the L/R/phase/max buttons
    // overlay the top of the plot area, no axis ticks live on the right
    // edge unless the phase axis is visible.  When phase IS visible, the
    // right margin grows so the phase tick labels have somewhere to live.
    private static final int MARGIN_TOP      = 0;
    private static final int MARGIN_BOTTOM   = 28;
    private static final int MARGIN_RIGHT_NO_PHASE = 0;
    private static final int MARGIN_RIGHT_PHASE    = 52;

    // --- Header button geometry ----------------------------------------------
    private static final int BTN_H   = 22;
    private static final int BTN_W   = 22;
    private static final int BTN_GAP = 2;
    private static final int BTN_TOP = 4;
    /** Horizontal offset of the header-button row past the dB-axis labels.
     *  Keeps the L/R/phase/max tile clear of the numeric tick labels that
     *  the axis renderer paints into the same vertical band. */
    private static final int HEADER_BTN_INSET = 23;

    // --- Colours -------------------------------------------------------------
    private final Color background;
    private final Color gridColor;
    private final Color axisColor;
    private final Color textColor;
    private final Color leftTraceColor;
    private final Color rightTraceColor;
    private final Color phaseTraceColor;
    private final Color riaaTraceColor;
    private final Color compareTraceColor;
    /** Pair of dark greys used by the blinking compare-banner and source-path
     *  overlays.  Mirrors the scope's near-white/pale-grey blink, but darker
     *  per spec so it reads cleanly against the white plot background. */
    private final Color blinkLitColor;
    private final Color blinkDimColor;
    private final Color crosshairColor;
    private final Color overlayBgColor;
    private final Color buttonFrameColor;
    private final Color buttonActiveColor;

    // --- Fonts ---------------------------------------------------------------
    private Font axisFont;
    private Font readoutFont;

    // --- State ---------------------------------------------------------------
    /** The captured (or loaded) result for each channel before any
     *  calibration is divided out.  Kept so calibration changes can rebuild
     *  the displayed trace without losing the original measurement. */
    private FreqRespResult rawLeftResult;
    private FreqRespResult rawRightResult;
    /** Display copies, with the currently-loaded calibration divided in (if
     *  any).  {@link #onCalibrationChanged()} keeps these in sync with the
     *  store every time the user loads / clears / wizard-applies a new
     *  calibration. */
    private FreqRespResult leftResult;
    private FreqRespResult rightResult;
    private String         sourceFilePath;
    /** Bus-handler reference kept so we can unsubscribe symmetrically. */
    private Runnable       calibrationChangedListener;

    private int    mouseX = -1;
    private int    mouseY = -1;
    private boolean mouseInPlot;

    /** Sample rate of the most recently received result; used to clip the
     *  crosshair readout at {@code nyquistFraction × sampleRate}.  Falls
     *  back to the active backend prefs' sample rate when no result has
     *  been received yet. */
    private int lastResultSampleRate;

    // --- Header button rects (recomputed every paint) -----------------------
    private final Rectangle btnL    = new Rectangle(0, 0, 0, 0);
    private final Rectangle btnR    = new Rectangle(0, 0, 0, 0);
    private final Rectangle btnPhase = new Rectangle(0, 0, 0, 0);
    private final Rectangle btnMax  = new Rectangle(0, 0, 0, 0);

    /** Lazily-loaded phase-toggle icon (two-tone sine), reused across paints. */
    private Image phaseIcon;
    /** Lazily-loaded maximize-button icon (arrows from circle). */
    private Image maxIcon;

    // Static-layer paint cache (traceBuffer Image + fingerprint) now
    // lives in AbstractFreqDomainView.  Call paintCachedStatic(...) from
    // onPaint; the base owns disposal via disposeTraceBuffer().

    public FreqRespView(Composite parent) {
        super(parent, SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED);
        Display d = getDisplay();

        background        = new Color(d, 0xFF, 0xFF, 0xFF);
        gridColor         = new Color(d, 0xD0, 0xD0, 0xD0);
        axisColor         = new Color(d, 0x60, 0x60, 0x60);
        textColor         = new Color(d, 0x20, 0x20, 0x20);
        // L / R use the scope's cyan / yellow channel palette so the button
        // colour matches the trace colour and the visual language is
        // consistent across the scope, FFT and freq-resp panes.  Phase is
        // red, RIAA reference green, comparison dark green.
        leftTraceColor    = new Color(d, 0x28, 0xDC, 0xF0);  // cyan
        rightTraceColor   = new Color(d, 0xF0, 0xDC, 0x28);  // yellow
        phaseTraceColor   = new Color(d, 0xD3, 0x2F, 0x2F);  // red
        riaaTraceColor    = new Color(d, 0x38, 0x8E, 0x3C);  // green
        compareTraceColor = new Color(d, 0x1B, 0x5E, 0x20);  // dark green
        // Blink pair for source-path + compare-banner overlays.  Almost
        // black to stand out against the white plot background.
        blinkLitColor     = new Color(d, 0x00, 0x00, 0x00);
        blinkDimColor     = new Color(d, 0x50, 0x50, 0x50);
        crosshairColor    = new Color(d, 0x80, 0x80, 0x80);
        overlayBgColor    = new Color(d, 0xFF, 0xFF, 0xE0);  // pale yellow tooltip
        buttonFrameColor  = new Color(d, 0xA0, 0xA0, 0xA0);
        buttonActiveColor = new Color(d, 0xC0, 0xD8, 0xF0);  // light blue-grey

        allocateFonts();

        // Register the header-row buttons as Hotspots with the shared base.
        // The rects are mutated in place by drawHeaderButtons every paint,
        // so the registry stays in sync automatically.  L and R behave as
        // radio buttons (exactly one channel shown at a time); phase is
        // independent; max is a push that resets the view.
        registerHotspot(btnL, () -> {
            Preferences p = Preferences.instance();
            if (p.isFreqRespLeftVisible()) return;
            p.setFreqRespLeftVisible(true);
            p.setFreqRespRightVisible(false);
            p.save();
            redraw();
        });
        registerHotspot(btnR, () -> {
            Preferences p = Preferences.instance();
            if (p.isFreqRespRightVisible()) return;
            p.setFreqRespRightVisible(true);
            p.setFreqRespLeftVisible(false);
            p.save();
            redraw();
        });
        registerHotspot(btnPhase, () -> {
            Preferences p = Preferences.instance();
            p.setFreqRespPhaseVisible(!p.isFreqRespPhaseVisible());
            p.save();
            redraw();
        });
        registerHotspot(btnMax, this::resetToDefaultView);

        addPaintListener(this::onPaint);
        addListener(SWT.MouseWheel, this::onMouseWheel);
        addListener(SWT.MouseMove,  this::onMouseMove);
        addListener(SWT.MouseDown,  this::onMouseDown);
        addListener(SWT.MouseExit,  e -> { mouseInPlot = false; redraw(); });

        // Re-trace whenever the active calibration changes (load, clear, or
        // wizard Apply).  The view divides the raw result by the new
        // calibration to keep what's painted in sync with the store.
        calibrationChangedListener = this::onCalibrationChanged;
        MessageBus.instance().subscribe(Events.FREQRESP_CALIBRATION_CHANGED,
                calibrationChangedListener);

        addDisposeListener(e -> {
            if (calibrationChangedListener != null) {
                MessageBus.instance().unsubscribe(Events.FREQRESP_CALIBRATION_CHANGED,
                        calibrationChangedListener);
            }
            background.dispose();
            gridColor.dispose();
            axisColor.dispose();
            textColor.dispose();
            leftTraceColor.dispose();
            rightTraceColor.dispose();
            phaseTraceColor.dispose();
            riaaTraceColor.dispose();
            compareTraceColor.dispose();
            blinkLitColor.dispose();
            blinkDimColor.dispose();
            crosshairColor.dispose();
            overlayBgColor.dispose();
            buttonFrameColor.dispose();
            buttonActiveColor.dispose();
            if (axisFont    != null && !axisFont.isDisposed())    axisFont.dispose();
            if (readoutFont != null && !readoutFont.isDisposed()) readoutFont.dispose();
            if (phaseIcon   != null && !phaseIcon.isDisposed())   phaseIcon.dispose();
            if (maxIcon     != null && !maxIcon.isDisposed())     maxIcon.dispose();
            disposeTraceBuffer();
        });
    }

    private void allocateFonts() {
        FontData fd = getFont().getFontData()[0];
        axisFont    = new Font(getDisplay(), fd.getName(), Math.max(7, fd.getHeight() - 1), SWT.NORMAL);
        readoutFont = new Font(getDisplay(), fd.getName(), Math.max(7, fd.getHeight() - 1), SWT.NORMAL);
    }

    // -------------------------------------------------------------------------
    // Public API — host pane and analyzer worker push results in here
    // -------------------------------------------------------------------------

    /** Replaces the left-channel result and triggers a repaint.  The argument
     *  is the raw measurement; the displayed copy is derived by dividing it
     *  by whichever calibration is currently active in
     *  {@link FreqRespCalibrationStore} (when {@code applyCalibration} is on). */
    public void setLeftResult(FreqRespResult result) {
        this.rawLeftResult = result;
        this.leftResult    = applyCurrentCalibration(result);
        if (result != null) lastResultSampleRate = result.getSampleRate();
        redraw();
    }

    /** Replaces the right-channel result and triggers a repaint.  Same
     *  calibration semantics as {@link #setLeftResult(FreqRespResult)}. */
    public void setRightResult(FreqRespResult result) {
        this.rawRightResult = result;
        this.rightResult    = applyCurrentCalibration(result);
        if (result != null) lastResultSampleRate = result.getSampleRate();
        redraw();
    }

    /** Re-derives the displayed left/right results from their raw copies
     *  using the calibration currently active in the store.  Subscribed to
     *  {@code FREQRESP_CALIBRATION_CHANGED} so loading or clearing a
     *  calibration immediately retraces the existing measurement. */
    public void onCalibrationChanged() {
        if (rawLeftResult  != null) this.leftResult  = applyCurrentCalibration(rawLeftResult);
        if (rawRightResult != null) this.rightResult = applyCurrentCalibration(rawRightResult);
        redraw();
    }

    private FreqRespResult applyCurrentCalibration(FreqRespResult raw) {
        if (raw == null) return null;
        if (!Preferences.instance().isFreqRespApplyCalibration()) return raw;
        StereoFreqRespCalibration stereo = FreqRespCalibrationStore.instance().getCurrent();
        if (stereo == null) return raw;
        FreqRespCalibration cal = raw.getChannel() == Channel.R ? stereo.right() : stereo.left();
        if (cal == null) return raw;
        double[] freqs    = raw.getFreqs();
        double[] inMag    = raw.getMagLin();
        double[] inPhase  = raw.getPhaseRad();
        double[] outMag   = new double[inMag.length];
        double[] outPhase = new double[inPhase.length];
        for (int i = 0; i < freqs.length; i++) {
            double[] c = FreqRespCalHelper.interpolate(cal, freqs[i]);
            double calMag = c[0];
            double calPhi = c[1];
            outMag[i]   = calMag > 0.0 ? inMag[i] / calMag : 0.0;
            outPhase[i] = inPhase[i] - calPhi;
        }
        return new FreqRespResult(raw.getChannel(), raw.getSampleRate(),
                freqs, outMag, outPhase, raw.getSweepParams(),
                raw.getSourceFilePath(), true);
    }

    /** Sets a file-path overlay label (drawn at the top-left of the trace
     *  area, mirroring the scope view's loaded-file indicator).  Pass
     *  {@code null} to clear. */
    public void setSourceFilePath(String path) {
        this.sourceFilePath = path;
        redraw();
    }

    /** Clears both result slots (raw and displayed). */
    public void clearResults() {
        this.rawLeftResult  = null;
        this.rawRightResult = null;
        this.leftResult     = null;
        this.rightResult    = null;
        redraw();
    }

    /** True when at least one channel has a measurement loaded — used by
     *  the host pane to enable the Compare checkbox. */
    public boolean hasAnyResult() {
        return leftResult != null || rightResult != null;
    }

    /** Read-only accessor used by the host pane's Save-to handler. */
    public FreqRespResult getLeftResultOrNull() {
        return leftResult;
    }

    /** Read-only accessor used by the host pane's Save-to handler. */
    public FreqRespResult getRightResultOrNull() {
        return rightResult;
    }

    /** Snaps the view to the default frequency / magnitude window
     *  (0 → sampleRate/2 horizontal, +20 → −140 dB vertical) and persists
     *  to {@link Preferences}.  Called from the maximize header button. */
    public void resetToDefaultView() {
        Preferences prefs = Preferences.instance();
        prefs.setFreqRespFreqMinHz(FREQ_MIN_FLOOR_HZ);
        prefs.setFreqRespFreqMaxHz(nyquistHz());
        prefs.setFreqRespMagTopDb(MAG_TOP_MAX_DB);
        prefs.setFreqRespMagBotDb(MAG_BOT_MIN_DB);
        prefs.save();
        publishRangeChanged();
        redraw();
    }

    // -------------------------------------------------------------------------
    // Paint
    // -------------------------------------------------------------------------

    private void onPaint(PaintEvent e) {
        GC gc = e.gc;
        Rectangle area = getClientArea();

        Preferences prefs = Preferences.instance();
        boolean phaseVisible = prefs.isFreqRespPhaseVisible();
        int rightMargin = phaseVisible ? MARGIN_RIGHT_PHASE : MARGIN_RIGHT_NO_PHASE;

        Rectangle plot = new Rectangle(
                MARGIN_LEFT,
                MARGIN_TOP,
                Math.max(1, area.width  - MARGIN_LEFT - rightMargin),
                Math.max(1, area.height - MARGIN_TOP  - MARGIN_BOTTOM));

        double freqMin = prefs.getFreqRespFreqMinHz();
        double freqMax = prefs.getFreqRespFreqMaxHz();
        if (freqMin <= 0) freqMin = 1.0;
        double magTop  = prefs.getFreqRespMagTopDb();
        double magBot  = prefs.getFreqRespMagBotDb();

        // Static layers (grid + axes + traces + RIAA + compare) are cached
        // into a backing image so crosshair / blink redraws don't re-walk
        // the trace.  Cache lifecycle (rebuild / blit) lives in the shared
        // AbstractFreqDomainView; we just hand it a fingerprint and the
        // static-layer painter.
        final double fFreqMin = freqMin;
        long fp = computeFingerprint(area, prefs, phaseVisible,
                fFreqMin, freqMax, magTop, magBot);
        paintCachedStatic(gc, area, fp, bgc -> {
            bgc.setBackground(background);
            bgc.fillRectangle(0, 0, area.width, area.height);
            bgc.setAntialias(SWT.ON);
            bgc.setTextAntialias(SWT.ON);
            drawGrid(bgc, plot, fFreqMin, freqMax, magTop, magBot);
            drawAxes(bgc, plot, fFreqMin, freqMax, magTop, magBot, phaseVisible);
            if (prefs.isFreqRespCompareMode() && hasAnyResult() && prefs.isFreqRespShowRiaa()) {
                drawCompareTrace(bgc, plot, fFreqMin, freqMax, magTop, magBot, prefs);
            } else {
                drawTraces(bgc, plot, fFreqMin, freqMax, magTop, magBot);
                if (prefs.isFreqRespShowRiaa()) {
                    drawRiaaOverlay(bgc, plot, fFreqMin, freqMax, magTop, magBot, prefs);
                }
            }
        });

        // Dynamic overlays — never cached because they change per frame.
        gc.setAntialias(SWT.ON);
        gc.setTextAntialias(SWT.ON);
        drawSourcePath(gc, plot);
        drawHeaderButtons(gc, area, prefs, phaseVisible);
        if (prefs.isFreqRespCompareMode() && hasAnyResult() && prefs.isFreqRespShowRiaa()) {
            drawCompareBanner(gc, plot, prefs);
        }
        if (mouseInPlot) {
            drawCrosshair(gc, plot, freqMin, freqMax, magTop, magBot, phaseVisible);
        }
    }

    /** Builds a 64-bit fingerprint that changes whenever any input to the
     *  static-layer rendering changes.  When the fingerprint matches, the
     *  cached trace buffer is blitted instead of re-rendered. */
    private long computeFingerprint(Rectangle area, Preferences prefs,
                                    boolean phaseVisible,
                                    double freqMin, double freqMax,
                                    double magTop, double magBot) {
        long h = 1469598103934665603L;
        long P = 1099511628211L;
        h = (h ^ area.width)  * P;
        h = (h ^ area.height) * P;
        h = (h ^ Double.doubleToLongBits(freqMin)) * P;
        h = (h ^ Double.doubleToLongBits(freqMax)) * P;
        h = (h ^ Double.doubleToLongBits(magTop))  * P;
        h = (h ^ Double.doubleToLongBits(magBot))  * P;
        h = (h ^ (phaseVisible ? 1 : 0)) * P;
        h = (h ^ (prefs.isFreqRespLeftVisible()  ? 1 : 0)) * P;
        h = (h ^ (prefs.isFreqRespRightVisible() ? 1 : 0)) * P;
        h = (h ^ (prefs.isFreqRespShowRiaa()     ? 1 : 0)) * P;
        h = (h ^ (prefs.isFreqRespReverseRiaa()  ? 1 : 0)) * P;
        h = (h ^ (prefs.isFreqRespIecAmendment() ? 1 : 0)) * P;
        h = (h ^ (prefs.isFreqRespCompareMode()  ? 1 : 0)) * P;
        h = (h ^ System.identityHashCode(leftResult))  * P;
        h = (h ^ System.identityHashCode(rightResult)) * P;
        return h;
    }

    // -------------------------------------------------------------------------
    // RIAA overlay + comparison
    // -------------------------------------------------------------------------

    /** Paints the configured RIAA reference curve as a dashed green trace
     *  over the measured response.  Aligned at 1 kHz to the measured
     *  curve's 1 kHz value (or 0 dB if no measurement is loaded). */
    private void drawRiaaOverlay(GC gc, Rectangle plot, double freqMin, double freqMax,
                                 double magTop, double magBot, Preferences prefs) {
        double anchorDb = riaaAnchorDb(prefs);
        gc.setForeground(riaaTraceColor);
        gc.setLineStyle(SWT.LINE_DASH);
        gc.setLineWidth(2);
        int prevX = -1, prevY = -1;
        // Sample finely — 5 px steps across the plot width — so curvature
        // stays smooth even on a wide screen.  SWT clipping is set to the
        // plot rect so off-axis segments are cut cleanly at the edge with
        // no gap (the user's spec).
        gc.setClipping(plot);
        try {
            for (int x = plot.x; x <= plot.x + plot.width; x += 5) {
                double frac = (x - plot.x) / (double) plot.width;
                double f = FreqRespFormat.xFractionToFreq(frac, freqMin, freqMax);
                double db = anchorDb + RiaaCurve.evalDb(
                        f,
                        prefs.isFreqRespReverseRiaa(),
                        prefs.isFreqRespIecAmendment());
                int y = dbToY(db, plot, magTop, magBot);
                if (prevX >= 0) gc.drawLine(prevX, prevY, x, y);
                prevX = x; prevY = y;
            }
        } finally {
            gc.setClipping((Rectangle) null);
        }
        gc.setLineStyle(SWT.LINE_SOLID);
    }

    /** Returns the magnitude in dB at 1 kHz of the active trace.  Prefers
     *  whichever channel the user currently has visible; with both visible
     *  L wins.  Falls back to 0 dB when no measurement is loaded. */
    private double riaaAnchorDb(Preferences prefs) {
        FreqRespResult anchor = activeChannelResult(prefs);
        if (anchor == null) return 0.0;
        double db = interpDb(anchor, 1000.0);
        return Double.isFinite(db) ? db : 0.0;
    }

    /** The result for the channel the RIAA overlay / comparison should
     *  anchor against — left if visible, else right if visible, else
     *  whichever non-null. */
    private FreqRespResult activeChannelResult(Preferences prefs) {
        if (prefs.isFreqRespLeftVisible()  && leftResult  != null) return leftResult;
        if (prefs.isFreqRespRightVisible() && rightResult != null) return rightResult;
        if (leftResult  != null) return leftResult;
        return rightResult;
    }

    /** Draws the (measured − reference) subtraction trace, anchored so the
     *  curve reads 0 dB at 1 kHz.  Uses whichever scrollbar-controlled range
     *  is currently in Preferences; the one-shot auto-zoom on first entry
     *  is done by {@link #autozoomCompareIfNeeded(Preferences)} from the
     *  compare-mode entry path. */
    private void drawCompareTrace(GC gc, Rectangle plot,
                                  double freqMin, double freqMax,
                                  double magTop, double magBot,
                                  Preferences prefs) {
        FreqRespResult src = activeChannelResult(prefs);
        if (src == null) return;
        double[] freqs  = src.getFreqs();
        double[] magLin = src.getMagLin();
        boolean reverse = prefs.isFreqRespReverseRiaa();
        boolean iec     = prefs.isFreqRespIecAmendment();

        // Anchor: shift the difference series vertically so it reads exactly
        // 0 dB at 1 kHz, per spec.
        double measAt1k = interpDb(src, 1000.0);
        if (!Double.isFinite(measAt1k)) measAt1k = 0.0;

        gc.setForeground(compareTraceColor);
        gc.setLineWidth(2);
        int prevX = -1, prevY = -1;
        gc.setClipping(plot);
        try {
            for (int i = 0; i < freqs.length; i++) {
                double f = freqs[i];
                if (f < freqMin || f > freqMax) continue;
                double measDb = FreqRespFormat.linToDb(magLin[i]);
                double refDb  = RiaaCurve.evalDb(f, reverse, iec);
                double diffDb = (measDb - measAt1k) - refDb;
                int x = freqToX(f, plot, freqMin, freqMax, true);
                int y = dbToY(diffDb, plot, magTop, magBot);
                if (prevX >= 0) gc.drawLine(prevX, prevY, x, y);
                prevX = x; prevY = y;
            }
        } finally {
            gc.setClipping((Rectangle) null);
        }
    }

    /** Computes and applies a 2 Hz–25 kHz / ±2 dB-around-extrema window to
     *  Preferences when comparison mode is first entered.  Called once per
     *  entry by the pane (not on every redraw), so subsequent pan / zoom
     *  the user does with the scrollbars and wheel stays sticky. */
    public void autozoomCompareIfNeeded(Preferences prefs) {
        FreqRespResult src = activeChannelResult(prefs);
        if (src == null) return;
        double[] freqs  = src.getFreqs();
        double[] magLin = src.getMagLin();
        boolean reverse = prefs.isFreqRespReverseRiaa();
        boolean iec     = prefs.isFreqRespIecAmendment();

        double measAt1k = interpDb(src, 1000.0);
        if (!Double.isFinite(measAt1k)) measAt1k = 0.0;

        final double fLo = 2.0, fHi = 25000.0;
        double diffMin = Double.POSITIVE_INFINITY, diffMax = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < freqs.length; i++) {
            double f = freqs[i];
            if (f < fLo || f > fHi) continue;
            double measDb = FreqRespFormat.linToDb(magLin[i]);
            double refDb  = RiaaCurve.evalDb(f, reverse, iec);
            double diffDb = (measDb - measAt1k) - refDb;
            if (!Double.isFinite(diffDb)) continue;
            if (diffDb < diffMin) diffMin = diffDb;
            if (diffDb > diffMax) diffMax = diffDb;
        }
        double pad = 2.0;
        double top = Double.isFinite(diffMax) ? diffMax + pad :  pad;
        double bot = Double.isFinite(diffMin) ? diffMin - pad : -pad;
        if (top - bot < 2 * pad) { double mid = 0.5 * (top + bot); top = mid + pad; bot = mid - pad; }

        prefs.setFreqRespFreqMinHz(fLo);
        prefs.setFreqRespFreqMaxHz(fHi);
        prefs.setFreqRespMagTopDb(top);
        prefs.setFreqRespMagBotDb(bot);
        prefs.save();
        MessageBus.instance().publish(Events.FREQRESP_RANGE_CHANGED);
        redraw();
    }

    /** Blinks a tooltip-style banner ("Comparison [reverse] RIAA [IEC] with
     *  measured") above the trace area while comparison mode is on.  Blink
     *  state flips every 500 ms; only the banner rect is redrawn so the
     *  trace cache stays valid. */
    /** Paints the centred comparison-mode banner.  No background box; just
     *  text in the blink-lit / blink-dim alternation so it matches the
     *  scope's filemode label style.  Caller decides whether to show. */
    private void drawCompareBanner(GC gc, Rectangle plot, Preferences prefs) {
        String text = I18n.t("freqResp.compare.banner")
                .replace("{0}", prefs.isFreqRespReverseRiaa()
                        ? I18n.t("freqResp.compare.banner.reverse") : "")
                .replace("{1}", prefs.isFreqRespIecAmendment()
                        ? I18n.t("freqResp.compare.banner.iec") : "");
        gc.setFont(readoutFont);
        Point ext = gc.textExtent(text);
        // Right-aligned, padded so the phase-axis tick labels (when shown)
        // have room on the right.  Mirrors drawSourcePath's positioning.
        int x = plot.x + plot.width - ext.x - 6;
        int y = plot.y + 6;
        boolean lit = blinkLit();
        gc.setForeground(lit ? blinkLitColor : blinkDimColor);
        gc.drawText(text, x, y, true);
        scheduleBlinkRedraw();
    }

    /** Wall-clock-phase blink toggle used by both the compare banner and
     *  the source-path label.  Computed per paint so we never store stale
     *  state. */
    private boolean blinkLit() {
        return ((System.currentTimeMillis() / 500L) % 2L) == 0L;
    }

    /** Schedules a redraw 500 ms from now so the blink toggle stays visible
     *  even when no other event triggers a repaint.  Coalesced so multiple
     *  call sites in one paint don't queue multiple timers. */
    private void scheduleBlinkRedraw() {
        if (blinkRedrawScheduled || isDisposed()) return;
        blinkRedrawScheduled = true;
        getDisplay().timerExec(500, () -> {
            blinkRedrawScheduled = false;
            if (isDisposed()) return;
            redraw();
        });
    }
    private boolean blinkRedrawScheduled;

    private void drawGrid(GC gc, Rectangle plot, double freqMin, double freqMax,
                          double magTop, double magBot) {
        gc.setForeground(gridColor);
        gc.setLineWidth(1);

        for (double f : FreqRespAxisTicks.freqMinor(freqMin, freqMax)) {
            int x = freqToX(f, plot, freqMin, freqMax, true);
            gc.drawLine(x, plot.y, x, plot.y + plot.height);
        }
        for (double f : FreqRespAxisTicks.freqMajor(freqMin, freqMax)) {
            int x = freqToX(f, plot, freqMin, freqMax, true);
            gc.drawLine(x, plot.y, x, plot.y + plot.height);
        }

        for (double db : FreqRespAxisTicks.magMinor(magBot, magTop)) {
            int y = dbToY(db, plot, magTop, magBot);
            gc.drawLine(plot.x, y, plot.x + plot.width, y);
        }
        for (double db : FreqRespAxisTicks.magMajor(magBot, magTop)) {
            int y = dbToY(db, plot, magTop, magBot);
            gc.drawLine(plot.x, y, plot.x + plot.width, y);
        }
    }

    private void drawAxes(GC gc, Rectangle plot, double freqMin, double freqMax,
                          double magTop, double magBot, boolean phaseVisible) {
        gc.setForeground(axisColor);
        gc.setLineWidth(1);
        gc.setFont(axisFont);

        gc.drawRectangle(plot.x, plot.y, plot.width, plot.height);

        for (double f : FreqRespAxisTicks.freqMajor(freqMin, freqMax)) {
            int x = freqToX(f, plot, freqMin, freqMax, true);
            gc.drawLine(x, plot.y + plot.height, x, plot.y + plot.height + 4);
            String label = FreqRespFormat.formatFrequency(f);
            Point ext = gc.textExtent(label);
            int lx = Math.max(plot.x, Math.min(plot.x + plot.width - ext.x, x - ext.x / 2));
            gc.setForeground(textColor);
            gc.drawText(label, lx, plot.y + plot.height + 6, true);
            gc.setForeground(axisColor);
        }

        for (double db : FreqRespAxisTicks.magMajor(magBot, magTop)) {
            int y = dbToY(db, plot, magTop, magBot);
            gc.drawLine(plot.x - 4, y, plot.x, y);
            String label = FreqRespFormat.formatDbBare(db);
            Point ext = gc.textExtent(label);
            gc.setForeground(textColor);
            gc.drawText(label, plot.x - 6 - ext.x, y - ext.y / 2, true);
            gc.setForeground(axisColor);
        }
        gc.setForeground(textColor);
        gc.drawText("dB", plot.x - 36, plot.y - 14, true);

        if (phaseVisible) {
            int axisX = plot.x + plot.width;
            for (double deg : FreqRespAxisTicks.phaseMajor()) {
                int y = phaseToY(deg, plot);
                gc.setForeground(axisColor);
                gc.drawLine(axisX, y, axisX + 4, y);
                String label = String.format("%d°", (int) Math.round(deg));
                gc.setForeground(textColor);
                gc.drawText(label, axisX + 6, y - gc.textExtent(label).y / 2, true);
            }
            gc.setForeground(textColor);
            gc.drawText("φ", axisX + 24, plot.y - 14, true);
        }
    }

    private void drawTraces(GC gc, Rectangle plot, double freqMin, double freqMax,
                            double magTop, double magBot) {
        Preferences prefs = Preferences.instance();
        gc.setLineWidth(2);

        if (prefs.isFreqRespLeftVisible() && leftResult != null) {
            paintTrace(gc, leftResult, plot, freqMin, freqMax, magTop, magBot,
                    leftTraceColor);
        }
        if (prefs.isFreqRespRightVisible() && rightResult != null) {
            paintTrace(gc, rightResult, plot, freqMin, freqMax, magTop, magBot,
                    rightTraceColor);
        }
        if (prefs.isFreqRespPhaseVisible()) {
            if (prefs.isFreqRespLeftVisible() && leftResult != null) {
                paintPhase(gc, leftResult, plot, freqMin, freqMax, phaseTraceColor);
            }
            if (prefs.isFreqRespRightVisible() && rightResult != null) {
                paintPhase(gc, rightResult, plot, freqMin, freqMax, phaseTraceColor);
            }
        }
    }

    private void paintTrace(GC gc, FreqRespResult result, Rectangle plot,
                            double freqMin, double freqMax,
                            double magTop, double magBot, Color color) {
        gc.setForeground(color);
        double[] freqs  = result.getFreqs();
        double[] magLin = result.getMagLin();
        if (freqs == null || magLin == null || freqs.length < 2) return;

        // Column-bucketed polyline rendering — see ColumnBucketPainter
        // in the shared base.  One drawLine per pixel column instead of
        // one per data point, so a 65 k-point sweep renders in
        // ~plot.width draws instead of 65 k.
        ColumnBucketPainter painter = new ColumnBucketPainter(plot);
        for (int i = 0; i < freqs.length; i++) {
            double f = freqs[i];
            if (f < freqMin || f > freqMax) continue;
            painter.add(
                    freqToX(f, plot, freqMin, freqMax, true),
                    dbToY(FreqRespFormat.linToDb(magLin[i]), plot, magTop, magBot));
        }
        painter.drawTo(gc);
    }

    private void paintPhase(GC gc, FreqRespResult result, Rectangle plot,
                            double freqMin, double freqMax, Color color) {
        gc.setForeground(color);
        gc.setLineStyle(SWT.LINE_DOT);
        double[] freqs    = result.getFreqs();
        double[] phaseRad = result.getPhaseRad();
        if (freqs == null || phaseRad == null || freqs.length < 2) return;

        ColumnBucketPainter painter = new ColumnBucketPainter(plot);
        for (int i = 0; i < freqs.length; i++) {
            double f = freqs[i];
            if (f < freqMin || f > freqMax) continue;
            painter.add(
                    freqToX(f, plot, freqMin, freqMax, true),
                    phaseToY(Math.toDegrees(phaseRad[i]), plot));
        }
        painter.drawTo(gc);
        gc.setLineStyle(SWT.LINE_SOLID);
    }

    private void drawSourcePath(GC gc, Rectangle plot) {
        if (sourceFilePath == null || sourceFilePath.isEmpty()) return;
        // Full path with a "Loaded:" prefix, blinking in the same dark
        // lit/dim pair as the compare banner so it matches the scope's
        // file-mode label style.  Right-aligned with a small pad so the
        // phase-axis tick labels (when shown) don't collide with it.
        String label = "Loaded: " + sourceFilePath;
        gc.setFont(readoutFont);
        int avail = plot.width - 12;
        if (gc.textExtent(label).x > avail) {
            // Left-side ellipsis: prefer to keep the file name visible
            // when the canvas is too narrow for the full path.
            String shown = "…" + sourceFilePath;
            while (shown.length() > 1 && gc.textExtent("Loaded: " + shown).x > avail) {
                shown = "…" + shown.substring(2);
            }
            label = "Loaded: " + shown;
        }
        Point ext = gc.textExtent(label);
        boolean lit = blinkLit();
        gc.setForeground(lit ? blinkLitColor : blinkDimColor);
        int x = plot.x + plot.width - ext.x - 6;
        gc.drawText(label, x, plot.y + 4, true);
        scheduleBlinkRedraw();
    }

    // -------------------------------------------------------------------------
    // Header buttons
    // -------------------------------------------------------------------------

    private void drawHeaderButtons(GC gc, Rectangle area, Preferences prefs, boolean phaseVisible) {
        // All four header buttons are grouped on the left in a contiguous
        // row (L, R, phase, maximize), offset by HEADER_BTN_INSET past the
        // dB-axis labels so they don't sit on top of the numeric ticks.
        int x = MARGIN_LEFT + HEADER_BTN_INSET;
        int y = BTN_TOP;

        layoutButton(btnL,     x, y); x += BTN_W + BTN_GAP;
        layoutButton(btnR,     x, y); x += BTN_W + BTN_GAP;
        layoutButton(btnPhase, x, y); x += BTN_W + BTN_GAP;
        layoutButton(btnMax,   x, y);

        // Toggle buttons follow the FftView style: selected → fill with the
        // channel's trace tint and draw label in textColor; unselected →
        // frame only.  Maximize is an icon push button.
        paintToggleButton(gc, btnL,     "L", prefs.isFreqRespLeftVisible(),  leftTraceColor);
        paintToggleButton(gc, btnR,     "R", prefs.isFreqRespRightVisible(), rightTraceColor);
        paintPhaseButton(gc, btnPhase, phaseVisible);
        paintMaximizeButton(gc, btnMax);
    }

    /** Maximize button: a square frame with the arrows-from-circle icon
     *  centred inside.  Push button, never reads as selected. */
    private void paintMaximizeButton(GC gc, Rectangle r) {
        gc.setForeground(buttonFrameColor);
        gc.drawRectangle(r.x, r.y, r.width - 1, r.height - 1);
        Image icon = maximizeIcon();
        if (icon != null) {
            Rectangle ib = icon.getBounds();
            gc.drawImage(icon,
                    r.x + (r.width  - ib.width)  / 2,
                    r.y + (r.height - ib.height) / 2);
        }
    }

    private Image maximizeIcon() {
        if (maxIcon == null) {
            maxIcon = IconUtils.instance().renderAtHeight(
                    getDisplay(), SvgPaths.ARROWS_FROM_CIRCLE, BTN_H - 8,
                    new RGB(0x20, 0x20, 0x20));
        }
        return maxIcon;
    }

    private void paintPhaseButton(GC gc, Rectangle r, boolean active) {
        if (active) {
            gc.setBackground(phaseTraceColor);
            gc.fillRectangle(r.x, r.y, r.width, r.height);
        } else {
            gc.setForeground(buttonFrameColor);
            gc.drawRectangle(r.x, r.y, r.width - 1, r.height - 1);
        }
        Image icon = phaseIcon();
        if (icon != null) {
            Rectangle ib = icon.getBounds();
            gc.drawImage(icon,
                    r.x + (r.width  - ib.width)  / 2,
                    r.y + (r.height - ib.height) / 2);
        } else {
            gc.setFont(getFont());
            gc.setForeground(textColor);
            Point ext = gc.textExtent("φ");
            gc.drawText("φ",
                    r.x + (r.width  - ext.x) / 2,
                    r.y + (r.height - ext.y) / 2,
                    true);
        }
    }

    private Image phaseIcon() {
        if (phaseIcon == null) {
            phaseIcon = IconUtils.instance().renderAtHeightColored(
                    getDisplay(), SvgPaths.PHASE_SINE, BTN_H - 8);
        }
        return phaseIcon;
    }

    private void layoutButton(Rectangle r, int x, int y) {
        r.x = x; r.y = y; r.width = BTN_W; r.height = BTN_H;
    }

    /** Channel toggle button styled like FftView: selected → tinted fill,
     *  unselected → frame only.  Label is centred in {@code textColor}. */
    private void paintToggleButton(GC gc, Rectangle r, String label,
                                   boolean active, Color tint) {
        if (active) {
            gc.setBackground(tint);
            gc.fillRectangle(r.x, r.y, r.width, r.height);
            gc.setForeground(textColor);
        } else {
            gc.setForeground(buttonFrameColor);
            gc.drawRectangle(r.x, r.y, r.width - 1, r.height - 1);
            gc.setForeground(textColor);
        }
        gc.setFont(getFont());
        Point ext = gc.textExtent(label);
        gc.drawText(label,
                r.x + (r.width  - ext.x) / 2,
                r.y + (r.height - ext.y) / 2,
                true);
    }

    // -------------------------------------------------------------------------
    // Crosshair
    // -------------------------------------------------------------------------

    private void drawCrosshair(GC gc, Rectangle plot, double freqMin, double freqMax,
                               double magTop, double magBot, boolean phaseVisible) {
        if (mouseX < plot.x || mouseX > plot.x + plot.width) return;
        if (mouseY < plot.y || mouseY > plot.y + plot.height) return;

        Preferences prefs = Preferences.instance();
        double frac = (mouseX - plot.x) / (double) plot.width;
        double cursorFreq = FreqRespFormat.xFractionToFreq(frac, freqMin, freqMax);
        int    sr      = lastResultSampleRate > 0 ? lastResultSampleRate
                : prefs.current().getInputSampleRate();
        double maxFreq = prefs.getFreqRespNyquistFraction() * sr;
        if (cursorFreq > maxFreq && maxFreq > 0) cursorFreq = maxFreq;

        gc.setForeground(crosshairColor);
        gc.setLineStyle(SWT.LINE_DOT);
        gc.drawLine(mouseX, plot.y, mouseX, plot.y + plot.height);
        gc.drawLine(plot.x, mouseY, plot.x + plot.width, mouseY);
        gc.setLineStyle(SWT.LINE_SOLID);

        StringBuilder sb = new StringBuilder();
        sb.append("f = ").append(FreqRespFormat.formatFrequencyFine(cursorFreq));
        if (prefs.isFreqRespLeftVisible() && leftResult != null) {
            double db = interpDb(leftResult, cursorFreq);
            sb.append('\n').append("L = ").append(FreqRespFormat.formatDb(db));
        }
        if (prefs.isFreqRespRightVisible() && rightResult != null) {
            double db = interpDb(rightResult, cursorFreq);
            sb.append('\n').append("R = ").append(FreqRespFormat.formatDb(db));
        }
        if (phaseVisible) {
            FreqRespResult phaseSrc =
                    (prefs.isFreqRespLeftVisible()  && leftResult  != null) ? leftResult
                  : (prefs.isFreqRespRightVisible() && rightResult != null) ? rightResult
                  : null;
            if (phaseSrc != null) {
                double phaseRad = interpPhase(phaseSrc, cursorFreq);
                sb.append('\n').append("φ = ").append(FreqRespFormat.formatPhase(Math.toDegrees(phaseRad)));
            }
        }
        drawReadoutBox(gc, sb.toString(), mouseX + 12, mouseY + 12, plot,
                readoutFont, overlayBgColor, buttonFrameColor, textColor);
    }

    /** Log-frequency linear-dB interpolation of a result's magnitude at a
     *  given frequency.  Returns {@code NaN} when the cursor is outside the
     *  measured band. */
    private double interpDb(FreqRespResult r, double f) {
        double[] freqs = r.getFreqs();
        double[] mag   = r.getMagLin();
        if (freqs == null || mag == null || freqs.length < 2) return Double.NaN;
        if (f < freqs[0] || f > freqs[freqs.length - 1]) return Double.NaN;
        int lo = 0, hi = freqs.length - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (freqs[mid] <= f) lo = mid; else hi = mid;
        }
        double t = (Math.log(f) - Math.log(freqs[lo])) / (Math.log(freqs[hi]) - Math.log(freqs[lo]));
        double db0 = FreqRespFormat.linToDb(mag[lo]);
        double db1 = FreqRespFormat.linToDb(mag[hi]);
        return db0 + t * (db1 - db0);
    }

    private double interpPhase(FreqRespResult r, double f) {
        double[] freqs = r.getFreqs();
        double[] p     = r.getPhaseRad();
        if (freqs == null || p == null || freqs.length < 2) return Double.NaN;
        if (f < freqs[0] || f > freqs[freqs.length - 1]) return Double.NaN;
        int lo = 0, hi = freqs.length - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (freqs[mid] <= f) lo = mid; else hi = mid;
        }
        double t = (Math.log(f) - Math.log(freqs[lo])) / (Math.log(freqs[hi]) - Math.log(freqs[lo]));
        return p[lo] + t * (p[hi] - p[lo]);
    }

    // -------------------------------------------------------------------------
    // Mouse handling
    // -------------------------------------------------------------------------

    private void onMouseMove(Event e) {
        mouseX = e.x;
        mouseY = e.y;
        Rectangle area = getClientArea();
        Preferences prefs = Preferences.instance();
        int rightMargin = prefs.isFreqRespPhaseVisible() ? MARGIN_RIGHT_PHASE : MARGIN_RIGHT_NO_PHASE;
        mouseInPlot = e.x >= MARGIN_LEFT && e.x <= area.width - rightMargin
                  && e.y >= MARGIN_TOP   && e.y <= area.height - MARGIN_BOTTOM;
        // Hand cursor over any header button (matches scope / FFT idiom).
        // Goes through the shared base's hotspot registry.
        Cursor c = getDisplay().getSystemCursor(hoverCursorId(e.x, e.y));
        if (getCursor() != c) setCursor(c);
        redraw();
    }

    private void onMouseDown(Event e) {
        if (e.button != 1) return;
        // All header buttons are registered as Hotspots with the shared
        // base — dispatch via the registry.  Per-button radio / toggle /
        // push semantics live inside the registered Runnables, each of
        // which already calls redraw() on its own.
        Hotspot hot = hotspotAt(e.x, e.y);
        if (hot != null) hot.onClick.run();
    }

    private void onMouseWheel(Event e) {
        boolean ctrl  = (e.stateMask & SWT.MOD1) != 0;
        boolean shift = (e.stateMask & SWT.SHIFT) != 0;
        int dir = (e.count > 0) ? 1 : -1;
        if (ctrl && shift)       zoomFrequencyAroundCursor(dir);
        else if (ctrl)           zoomMagnitudeAroundCursor(dir);
        else if (shift)          panFrequency(dir);
        else                     panMagnitude(dir);
    }

    // -------------------------------------------------------------------------
    // Zoom / pan
    // -------------------------------------------------------------------------

    private static final double FREQ_ZOOM_FACTOR = 1.25;
    private static final double MAG_ZOOM_FACTOR  = 1.25;
    private static final double FREQ_PAN_FRAC    = 0.10;
    private static final double MAG_PAN_FRAC     = 0.10;
    /** Outer-most allowable frequency / magnitude window — zoom-out stops
     *  here so the user can't scroll into territory where no useful data
     *  ever lives.  Horizontal: 0 Hz to Nyquist (sampleRate / 2).
     *  Vertical: +20 dB to −300 dB. */
    private static final double MAG_TOP_MAX_DB  =   20.0;
    private static final double MAG_BOT_MIN_DB  = -300.0;
    /** Smallest log-axis floor for the frequency window.  Pure 0 Hz is
     *  unrepresentable on a log axis, so we clamp to a tiny positive
     *  value when the user zooms out fully. */
    private static final double FREQ_MIN_FLOOR_HZ = 1.0;

    /** Returns the active backend's Nyquist frequency, used as the
     *  right-most zoom-out limit. */
    private double nyquistHz() {
        int sr = lastResultSampleRate > 0 ? lastResultSampleRate
                : Preferences.instance().current().getInputSampleRate();
        return Math.max(1.0, sr / 2.0);
    }

    private void zoomFrequencyAroundCursor(int dir) {
        Preferences prefs = Preferences.instance();
        double fMin = prefs.getFreqRespFreqMinHz();
        double fMax = prefs.getFreqRespFreqMaxHz();
        if (fMin <= 0) fMin = 1.0;
        Rectangle plot = plotRect(prefs);
        if (plot == null) return;
        double frac = (mouseX - plot.x) / (double) plot.width;
        double cursorF = FreqRespFormat.xFractionToFreq(frac, fMin, fMax);

        double scale = (dir > 0) ? 1.0 / FREQ_ZOOM_FACTOR : FREQ_ZOOM_FACTOR;
        double newMin = cursorF / Math.pow(fMax / fMin, frac * scale);
        double newMax = cursorF * Math.pow(fMax / fMin, (1.0 - frac) * scale);
        if (newMin >= newMax) return;
        // Clamp the outer window to [FREQ_MIN_FLOOR_HZ, Nyquist].
        double nyq = nyquistHz();
        newMin = Math.max(FREQ_MIN_FLOOR_HZ, newMin);
        newMax = Math.min(nyq, newMax);
        if (newMin >= newMax) return;
        prefs.setFreqRespFreqMinHz(newMin);
        prefs.setFreqRespFreqMaxHz(newMax);
        prefs.save();
        publishRangeChanged();
        redraw();
    }

    private void zoomMagnitudeAroundCursor(int dir) {
        Preferences prefs = Preferences.instance();
        double magTop = prefs.getFreqRespMagTopDb();
        double magBot = prefs.getFreqRespMagBotDb();
        Rectangle plot = plotRect(prefs);
        if (plot == null) return;
        double frac = (mouseY - plot.y) / (double) plot.height;
        double cursorDb = magTop - frac * (magTop - magBot);

        double scale = (dir > 0) ? 1.0 / MAG_ZOOM_FACTOR : MAG_ZOOM_FACTOR;
        double newTop = cursorDb + (magTop - cursorDb) * scale;
        double newBot = cursorDb + (magBot - cursorDb) * scale;
        if (newTop <= newBot) return;
        // Clamp the outer magnitude window to [MAG_BOT_MIN_DB, MAG_TOP_MAX_DB].
        newTop = Math.min(MAG_TOP_MAX_DB, newTop);
        newBot = Math.max(MAG_BOT_MIN_DB, newBot);
        if (newTop <= newBot) return;
        prefs.setFreqRespMagTopDb(newTop);
        prefs.setFreqRespMagBotDb(newBot);
        prefs.save();
        publishRangeChanged();
        redraw();
    }

    private void panFrequency(int dir) {
        Preferences prefs = Preferences.instance();
        double fMin = prefs.getFreqRespFreqMinHz();
        double fMax = prefs.getFreqRespFreqMaxHz();
        if (fMin <= 0) fMin = 1.0;
        double logSpan = Math.log10(fMax) - Math.log10(fMin);
        double delta = logSpan * FREQ_PAN_FRAC * (-dir);
        double newMin = Math.pow(10, Math.log10(fMin) + delta);
        double newMax = Math.pow(10, Math.log10(fMax) + delta);
        if (newMin <= 0 || newMax <= newMin) return;
        // Pan stops at the outer limits without changing the visible span.
        double nyq = nyquistHz();
        if (newMin < FREQ_MIN_FLOOR_HZ) {
            double shift = FREQ_MIN_FLOOR_HZ / newMin;
            newMin *= shift; newMax *= shift;
        }
        if (newMax > nyq) {
            double shift = nyq / newMax;
            newMin *= shift; newMax *= shift;
        }
        prefs.setFreqRespFreqMinHz(newMin);
        prefs.setFreqRespFreqMaxHz(newMax);
        prefs.save();
        publishRangeChanged();
        redraw();
    }

    private void panMagnitude(int dir) {
        Preferences prefs = Preferences.instance();
        double magTop = prefs.getFreqRespMagTopDb();
        double magBot = prefs.getFreqRespMagBotDb();
        double span = magTop - magBot;
        double delta = span * MAG_PAN_FRAC * dir;
        double newTop = magTop + delta;
        double newBot = magBot + delta;
        if (newTop > MAG_TOP_MAX_DB) {
            double shift = MAG_TOP_MAX_DB - newTop;
            newTop += shift; newBot += shift;
        }
        if (newBot < MAG_BOT_MIN_DB) {
            double shift = MAG_BOT_MIN_DB - newBot;
            newTop += shift; newBot += shift;
        }
        prefs.setFreqRespMagTopDb(newTop);
        prefs.setFreqRespMagBotDb(newBot);
        prefs.save();
        publishRangeChanged();
        redraw();
    }

    private Rectangle plotRect(Preferences prefs) {
        Rectangle area = getClientArea();
        if (area.width < 10 || area.height < 10) return null;
        int rightMargin = prefs.isFreqRespPhaseVisible() ? MARGIN_RIGHT_PHASE : MARGIN_RIGHT_NO_PHASE;
        return new Rectangle(
                MARGIN_LEFT,
                MARGIN_TOP,
                Math.max(1, area.width  - MARGIN_LEFT - rightMargin),
                Math.max(1, area.height - MARGIN_TOP  - MARGIN_BOTTOM));
    }

    private void publishRangeChanged() {
        MessageBus.instance().publish(Events.FREQRESP_RANGE_CHANGED);
    }

    // -------------------------------------------------------------------------
    // Coordinate transforms
    //
    // freqToX (log-axis) and dbToY now live on AbstractFreqDomainView —
    // FreqResp always passes {@code logFreq=true}.  Only the phase-axis
    // transform stays here because it's view-specific (FFT doesn't paint
    // a phase trace).
    // -------------------------------------------------------------------------

    private int phaseToY(double deg, Rectangle plot) {
        double frac = FreqRespFormat.phaseToYFraction(deg);
        return plot.y + (int) Math.round(frac * plot.height);
    }
}
