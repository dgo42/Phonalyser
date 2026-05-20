package org.edgo.audio.measure.gui.fft;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.FormAttachment;
import org.eclipse.swt.layout.FormData;
import org.eclipse.swt.layout.FormLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.FftMagnitudeUnit;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.fft.FftAnalyzer;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.IconUtils;
import org.edgo.audio.measure.gui.common.SvgPaths;
import org.edgo.audio.measure.gui.generator.GeneratorController;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.preferences.Preferences;
import org.edgo.audio.measure.gui.sound.SignalBuffer;

/**
 * Live FFT canvas.  Paints a black-on-white spectrum trace (one channel
 * at a time), a THD info table overlay, a crosshair cursor with a freq /
 * magnitude readout, and a row of header buttons modelled on the
 * oscilloscope view.
 *
 * <p>Owns the analysis pipeline itself: a daemon worker thread reads the
 * shared {@link SignalBuffer} (supplied via {@link #setBuffer}), runs
 * {@link FftAnalyzer#analyze}, and publishes the latest
 * {@link FftAnalyzer.Result} for paint to read.  The owning
 * {@link FftPane} drives the lifecycle ({@link #start} / {@link #stop} /
 * {@link #resetStatistics}) and feeds settings via {@code Preferences}.
 * View → pane communication is purely via {@link MessageBus} broadcasts
 * ({@link Events#FFT_RANGE_CHANGED}, {@link Events#FFT_RECORDING_AUTO_STOPPED}).
 */
public final class FftView extends Canvas {

    // ─── Colours ─────────────────────────────────────────────────────────
    /** User-configurable; re-allocated by {@link #syncFftColors} when the
     *  corresponding pref changes. */
    private Color background;
    private final Color gridColor;
    private final Color axisColor;
    private final Color textColor;
    /** User-configurable spectrum trace colour. */
    private Color spectrumColor;
    private final Color crosshairColor;
    private final Color leftChanColor;
    private final Color rightChanColor;
    private final Color overlayBgColor;
    private final Color buttonFrameColor;
    private final Color resetColor;
    /** Light-grey overlay used to dim the parts of the spectrum that
     *  lie outside an active distortion HP / LP window — matches the
     *  CLI FFT chart's {@code ChartStyle.DIM_COLOR}. */
    private final Color dimColor;
    /** Dot colour for fundamental + harmonic markers; user-configurable. */
    private Color harmonicDotColor;
    /** User-configurable filter / cal-overlay response trace colour.
     *  Allocated even though the FFT view doesn't yet draw a filter
     *  response trace — kept in sync so it's ready when that layer is
     *  added. */
    private Color filterResponseColor;
    /** Cached packed-RGB values of the last allocated FFT colors; used by
     *  {@link #syncFftColors} to skip reallocation when prefs match. */
    private int currentBgRgb        = -1;
    private int currentSpectrumRgb  = -1;
    private int currentDotRgb       = -1;
    private int currentFilterRgb    = -1;

    // ─── Fonts ────────────────────────────────────────────────────────────
    private Font monoFont;
    private Font monoBoldFont;
    private Font chanButtonFont;

    // ─── Icons (lazy) ─────────────────────────────────────────────────────
    private Image chartColumnIconLight;
    private Image chartColumnIconDark;
    private Image rotateLeftIcon;
    private Image autoSetupIcon;
    private Image maximizeIcon;
    private Image windowRestoreIconLight;
    private Image windowRestoreIconDark;

    // ─── Top-right overlay widgets ───────────────────────────────────────
    /** Magnitude unit selector (V / V·√Hz / dBV / dBFS). */
    private Combo magUnitCombo;
    /** "NN%" indicator showing the progress of the current FFT frame's
     *  hop (or full-window fill for the first frame). */
    private Label fillPercentLabel;
    /** Count of completed analyses since the last reset / record-start. */
    private Label averagesCountLabel;

    /** Magnitude-unit option names (Preferences value form). */
    private static final String[] MAG_UNIT_NAMES = {
            FftMagnitudeUnit.V       .name(),
            FftMagnitudeUnit.V_SQRT_HZ.name(),
            FftMagnitudeUnit.DBV     .name(),
            FftMagnitudeUnit.DBFS    .name()
    };
    /** Magnitude-unit option labels (display text). */
    private static final String[] MAG_UNIT_LABELS = {
            FftMagnitudeUnit.V       .getLabel(),
            FftMagnitudeUnit.V_SQRT_HZ.getLabel(),
            FftMagnitudeUnit.DBV     .getLabel(),
            FftMagnitudeUnit.DBFS    .getLabel()
    };

    // ─── Analyser pipeline ─────────────────────────────────────────────
    /** Owns the FFT worker thread, the {@link FftAnalyzer} instance, and
     *  every piece of analysis state that the paint side merely consumes.
     *  Constructed in {@link #FftView} with a {@link Runnable} that fires
     *  a redraw on the UI thread after every new result. */
    private final FftAnalyzerWorker worker;

    // ─── Mouse / crosshair tracking ──────────────────────────────────────
    private int crossX = -1, crossY = -1;

    // ─── Hit-test rectangles for header buttons (refreshed every paint) ──
    private final Rectangle leftChanButtonBounds     = new Rectangle(0, 0, 0, 0);
    private final Rectangle rightChanButtonBounds    = new Rectangle(0, 0, 0, 0);
    private final Rectangle autoSetupButtonBounds    = new Rectangle(0, 0, 0, 0);
    private final Rectangle maximizeButtonBounds     = new Rectangle(0, 0, 0, 0);
    private final Rectangle distortionButtonBounds   = new Rectangle(0, 0, 0, 0);
    private final Rectangle resetButtonBounds        = new Rectangle(0, 0, 0, 0);
    private final Rectangle externalButtonBounds     = new Rectangle(0, 0, 0, 0);

    // ─── External tool window ────────────────────────────────────────────
    private boolean tableExtracted;
    private Shell   externalShell;
    private Canvas  externalCanvas;

    // ─── Spectrum / grid offscreen buffer ───────────────────────────────
    /** Cached rendering of the static layers (background + grid +
     *  spectrum + phase + axes).  Rebuilt only when the underlying
     *  state actually changes — mouse-move / crosshair redraws blit
     *  this image and paint the overlay directly on top, so they cost
     *  ~one drawImage instead of re-walking 64 k spectrum bins. */
    private Image    traceBuffer;
    /** Fingerprint of the data + view parameters used to build
     *  {@link #traceBuffer}.  Compared against the live state every
     *  paint; a mismatch triggers a rebuild. */
    private long     traceBufferFingerprint;
    /** Canvas width / height the {@link #traceBuffer} was built for. */
    private int      traceBufferW, traceBufferH;

    // ─── Plot region constants ───────────────────────────────────────────
    /** Pixel margins around the plotting area inside the canvas.
     *  Sized for the widest expected axis label — SI-prefix V values
     *  like "100 mV" / "31.6 µV" — plus a few pixels of breathing room
     *  between the label and the plot frame. */
    private static final int MARGIN_LEFT   = 68;
    /** Zero top margin — the unit caption is drawn INSIDE the plot
     *  area at the top, so the chart frame sits flush against the
     *  pane title (no gap). */
    private static final int MARGIN_TOP    = 0;
    private static final int MARGIN_BOTTOM = 28;
    /** Header-button row metrics. */
    private static final int BTN_W = 22;
    private static final int BTN_H = 22;
    private static final int BTN_GAP_LARGE = 6;
    private static final int BTN_GAP_SMALL = 2;
    /** THD table overlay y offset from top of canvas. */
    private static final int TABLE_TOP_Y = 5 + BTN_H + 6;

    public FftView(Composite parent) {
        super(parent, SWT.DOUBLE_BUFFERED);
        // Chapter-level help anchor: Ctrl+F1 anywhere on the spectrum
        // canvas opens fft.html.  The header buttons / THD table /
        // crosshair aren't separate widgets (everything is custom-
        // painted on this Canvas), so deep widget-level anchors
        // bottom-out at the chapter and the user navigates within.
        setData("helpAnchor", "fft.html");
        Display d = getDisplay();
        worker = new FftAnalyzerWorker(d, () -> { if (!isDisposed()) redraw(); });
        gridColor        = new Color(d, 0xDC, 0xDC, 0xDC);
        axisColor        = new Color(d, 0x80, 0x80, 0x80);
        textColor        = new Color(d, 0x20, 0x20, 0x20);
        crosshairColor   = new Color(d, 0x90, 0x90, 0x90);
        leftChanColor    = new Color(d, 0x28, 0xDC, 0xF0);
        rightChanColor   = new Color(d, 0xF0, 0xDC, 0x28);
        overlayBgColor   = new Color(d, 0xF6, 0xF6, 0xF6);
        buttonFrameColor = new Color(d, 0x60, 0x60, 0x60);
        resetColor       = new Color(d, 220,  60,  60);
        dimColor         = new Color(d, 200, 200, 200);
        syncFftColors();
        setBackground(background);
        addPaintListener(this::onPaint);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseDown(MouseEvent ev) { onMouseDown(ev); }
        });
        addMouseMoveListener(this::onMouseMove);
        addListener(SWT.MouseVerticalWheel, this::onMouseWheel);
        addMouseTrackListener(new org.eclipse.swt.events.MouseTrackAdapter() {
            @Override
            public void mouseExit(MouseEvent e) {
                crossX = crossY = -1;
                redraw();
            }
        });
        addDisposeListener(e -> disposeResources());

        // Top-right overlay widgets — children of this Canvas, positioned
        // by FormLayout.  The Canvas paint listener draws underneath;
        // SWT renders child controls on top automatically.
        setLayout(new FormLayout());

        magUnitCombo = new Combo(this, SWT.READ_ONLY);
        magUnitCombo.setItems(MAG_UNIT_LABELS);
        magUnitCombo.setToolTipText(I18n.t("fft.magUnit.tooltip"));
        magUnitCombo.setData("helpAnchor", "fft.html#fft-mag-unit");
        int magIdx = indexOf(MAG_UNIT_NAMES, Preferences.instance().getFftMagUnit());
        magUnitCombo.select(magIdx < 0 ? 2 : magIdx);
        magUnitCombo.addListener(SWT.Selection, e -> onMagUnitChanged());

        averagesCountLabel = new Label(this, SWT.NONE);
        averagesCountLabel.setText("0 average(s)");
        averagesCountLabel.setForeground(d.getSystemColor(SWT.COLOR_DARK_GRAY));
        averagesCountLabel.setToolTipText(I18n.t("fft.avgCount.tooltip"));
        averagesCountLabel.setData("helpAnchor", "fft.html#fft-averages-count");

        fillPercentLabel = new Label(this, SWT.NONE);
        fillPercentLabel.setText("0%");
        fillPercentLabel.setForeground(d.getSystemColor(SWT.COLOR_DARK_GRAY));
        fillPercentLabel.setToolTipText(I18n.t("fft.fillPercent.tooltip"));
        fillPercentLabel.setData("helpAnchor", "fft.html#fft-fill-percent");

        FormData cfd = new FormData();
        cfd.top   = new FormAttachment(0, 4);
        cfd.right = new FormAttachment(100, -2);
        cfd.width = 60;
        magUnitCombo.setLayoutData(cfd);

        FormData acd = new FormData();
        acd.top   = new FormAttachment(0, 6);
        acd.right = new FormAttachment(magUnitCombo, -6);
        acd.width = 90;
        averagesCountLabel.setLayoutData(acd);

        FormData fpd = new FormData();
        fpd.top   = new FormAttachment(0, 6);
        fpd.right = new FormAttachment(averagesCountLabel, -6);
        fpd.width = 36;
        fillPercentLabel.setLayoutData(fpd);

        startFillPercentTimer();
    }

    /** User picked a new magnitude unit.  Convert the current
     *  magTop / magBottom into the new unit so the visible range stays
     *  on the SAME signal level, persist, then publish
     *  {@link Events#FFT_RANGE_CHANGED} so the pane's scrollbars
     *  re-align. */
    private void onMagUnitChanged() {
        int i = magUnitCombo.getSelectionIndex();
        if (i < 0) return;
        Preferences prefs = Preferences.instance();
        FftMagnitudeUnit oldUnit = FftMagnitudeUnit.fromName(prefs.getFftMagUnit());
        FftMagnitudeUnit newUnit = FftMagnitudeUnit.fromName(MAG_UNIT_NAMES[i]);
        double[] conv = convertMagRange(prefs.getFftMagTop(), prefs.getFftMagBottom(),
                oldUnit, newUnit);
        prefs.setFftMagTop(conv[0]);
        prefs.setFftMagBottom(conv[1]);
        prefs.setFftMagUnit(MAG_UNIT_NAMES[i]);
        prefs.save();
        fireRangeChanged();
        redraw();
    }

    /** Re-selects the magnitude-unit combo from the current Preferences
     *  value.  Called by the pane's syncWidgetsFromPrefs after a preset
     *  load or screenshot-pane wire-up. */
    public void refreshFromPrefs() {
        if (magUnitCombo == null || magUnitCombo.isDisposed()) return;
        int i = indexOf(MAG_UNIT_NAMES, Preferences.instance().getFftMagUnit());
        if (i >= 0) magUnitCombo.select(i);
    }

    /** Schedules a recurring ~100 ms timer that updates the fill-%
     *  indicator and the averages count from the live analyser state.
     *  Self-terminates when the canvas is disposed. */
    private void startFillPercentTimer() {
        Display d = getDisplay();
        Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            if (isDisposed() || fillPercentLabel == null || fillPercentLabel.isDisposed()) return;
            double f = getNextFrameProgress();
            int pct = (int) Math.round(f * 100);
            if (pct < 0) pct = 0; else if (pct > 100) pct = 100;
            String txt = pct + "%";
            if (!txt.equals(fillPercentLabel.getText())) fillPercentLabel.setText(txt);
            if (averagesCountLabel != null && !averagesCountLabel.isDisposed()) {
                // First capture has nothing to average against, so it
                // contributes 0 averages.  For a finite N the count is
                // capped at N once the moving window is full.  For
                // "forever" it keeps climbing.
                int done = getCompletedAnalyses();
                int n = Math.max(0, done - 1);
                double avgRaw = Preferences.instance().getFftAverages();
                if (!Double.isInfinite(avgRaw)) {
                    int N = Math.max(1, (int) avgRaw);
                    if (n > N) n = N;
                }
                String ns = n + " average(s)";
                if (!ns.equals(averagesCountLabel.getText())) averagesCountLabel.setText(ns);
            }
            d.timerExec(100, tick[0]);
        };
        d.timerExec(100, tick[0]);
    }

    private static int indexOf(String[] arr, String value) {
        if (value == null) return -1;
        for (int i = 0; i < arr.length; i++) if (value.equals(arr[i])) return i;
        return -1;
    }

    /** Converts a {@code [top, bottom]} pair from one magnitude unit to
     *  another so a unit-combo change preserves the visible signal-on-
     *  the-axis: each dB on the dB axis maps to one factor-of-10^0.05 on
     *  the volts axis (20 dB = ×10), so the user sees the same grid
     *  lines around the same signal levels after the switch. */
    private static double[] convertMagRange(double oldTop, double oldBot,
                                            FftMagnitudeUnit from, FftMagnitudeUnit to) {
        if (from == to) return new double[] {oldTop, oldBot};
        double fs = Preferences.instance().getAdcFsVoltageRms();
        if (!(fs > 0)) fs = 1.0;
        double fsDbv = 20 * Math.log10(fs);
        double topDbv = toDbv(oldTop, from, fsDbv);
        double botDbv = toDbv(oldBot, from, fsDbv);
        double newTop = fromDbv(topDbv, to, fsDbv);
        double newBot = fromDbv(botDbv, to, fsDbv);
        return new double[] {newTop, newBot};
    }

    private static double toDbv(double v, FftMagnitudeUnit unit, double fsDbv) {
        switch (unit) {
            case DBV:       return v;
            case DBFS:      return v + fsDbv;
            case V:         return (v > 0) ? 20 * Math.log10(v) : -300;
            case V_SQRT_HZ: return (v > 0) ? 20 * Math.log10(v) : -300;
            default:        return v;
        }
    }

    private static double fromDbv(double dbv, FftMagnitudeUnit unit, double fsDbv) {
        switch (unit) {
            case DBV:       return dbv;
            case DBFS:      return dbv - fsDbv;
            case V:
            case V_SQRT_HZ: return Math.pow(10, dbv / 20.0);
            default:        return dbv;
        }
    }

    private void disposeResources() {
        if (background          != null) background.dispose();
        gridColor.dispose();
        axisColor.dispose();
        textColor.dispose();
        if (spectrumColor       != null) spectrumColor.dispose();
        crosshairColor.dispose();
        leftChanColor.dispose();
        rightChanColor.dispose();
        overlayBgColor.dispose();
        buttonFrameColor.dispose();
        resetColor.dispose();
        dimColor.dispose();
        if (harmonicDotColor    != null) harmonicDotColor.dispose();
        if (filterResponseColor != null) filterResponseColor.dispose();
        if (monoFont       != null) monoFont.dispose();
        if (monoBoldFont   != null) monoBoldFont.dispose();
        if (chanButtonFont != null) chanButtonFont.dispose();
        // Header icons are cached and owned by IconUtils — disposed
        // when the main shell tears down, not here.
        if (externalShell != null && !externalShell.isDisposed()) externalShell.dispose();
        if (traceBuffer   != null && !traceBuffer  .isDisposed()) traceBuffer  .dispose();
    }

    /** (Re-)allocates the user-configurable FFT colours from
     *  {@link Preferences} when the packed-RGB values change.  Cheap on
     *  repeat calls when nothing changed (an integer compare per slot).
     *  Called from the constructor and from each paint so live edits in
     *  the Preferences dialog take effect on the next redraw. */
    private void syncFftColors() {
        Preferences prefs = Preferences.instance();
        Display d = getDisplay();
        int bg  = prefs.getFftChartBackgroundColor();
        int sp  = prefs.getFftLineColor();
        int dot = prefs.getFftHarmonicDotColor();
        int flt = prefs.getFftFilterResponseColor();
        if (bg  != currentBgRgb) {
            if (background       != null) background.dispose();
            background       = newRgbColor(d, bg);
            currentBgRgb     = bg;
            setBackground(background);
        }
        if (sp  != currentSpectrumRgb) {
            if (spectrumColor    != null) spectrumColor.dispose();
            spectrumColor    = newRgbColor(d, sp);
            currentSpectrumRgb = sp;
        }
        if (dot != currentDotRgb) {
            if (harmonicDotColor != null) harmonicDotColor.dispose();
            harmonicDotColor = newRgbColor(d, dot);
            currentDotRgb    = dot;
        }
        if (flt != currentFilterRgb) {
            if (filterResponseColor != null) filterResponseColor.dispose();
            filterResponseColor = newRgbColor(d, flt);
            currentFilterRgb    = flt;
        }
    }

    private static Color newRgbColor(Display d, int rgb) {
        return new Color(d, (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /** Wires the capture buffer.  Called by {@link FftPane} after a
     *  successful {@link Events#CAPTURE_ACQUIRE}, and again with
     *  {@code null} after {@link Events#CAPTURE_RELEASE}.  Safe to call
     *  while the worker is running — reads are volatile. */
    public void setBuffer(SignalBuffer b) {
        worker.setBuffer(b);
    }

    /** Latest published analysis result.  {@code null} before the first
     *  successful tick.  Used by the pane's Save-CSV / screenshot paths
     *  and by the calibration dialog (via {@link #getLastVrms}). */
    public FftAnalyzer.Result getLastResult() {
        return worker.getLastResult();
    }

    /** Injects a snapshot result without running the analysis loop.
     *  Used by the offscreen screenshot pane so it can render the same
     *  spectrum the live pane is showing. */
    public void setLastResult(FftAnalyzer.Result r) {
        worker.setLastResult(r);
    }

    /** Number of analyses completed since the last reset. */
    public int getCompletedAnalyses() {
        return worker.getCompletedAnalyses();
    }

    /** True if the analyser worker is currently running. */
    public boolean isRunning() {
        return worker.isRunning();
    }

    /** Starts the analyser on a daemon worker thread.  Idempotent. */
    public void start() {
        worker.start();
    }

    /** Stops the analyser and interrupts the worker.  Idempotent. */
    public void stop() {
        worker.stop();
    }

    /** True when the audio generator is currently producing a signal.
     *  Resolved via {@link MessageBus} — the generator pane registers a
     *  responder for {@link Events#GENERATOR_RUNNING}.  Defaults to
     *  {@code true} when no responder is registered.  Used by the THD
     *  table to decide whether to draw the generator-anchored Δf row. */
    public boolean isGeneratorActive() {
        return worker.isGeneratorActive();
    }

    /** Fraction (0..1) of the data needed for the *current* FFT frame
     *  that has already been captured.  Drives the "NN%" indicator next
     *  to the magnitude-unit combo. */
    public double getNextFrameProgress() {
        return worker.getNextFrameProgress();
    }

    /** Clears the completed-analyses counter, drops the retained
     *  spectrum, and resumes the loop if it was paused by stop-after-N.
     *  Forces a redraw so any visible counter refreshes immediately.
     *  Safe to call from any thread. */
    public void resetStatistics() {
        worker.resetStatistics();
        Display d = getDisplay();
        if (d != null && !d.isDisposed()) {
            d.asyncExec(() -> { if (!isDisposed()) redraw(); });
        }
    }

    /** Publishes {@link Events#FFT_RANGE_CHANGED} so the owning pane's
     *  scrollbars re-align to the (just-mutated) freq / mag window in
     *  {@code Preferences}.  Called after every view-internal pan /
     *  zoom and from {@link #autoSetup()} / {@link #maximize()}. */
    private void fireRangeChanged() {
        MessageBus.instance().publish(Events.FFT_RANGE_CHANGED);
    }

    /** Sets the freq range to one decade either side of the detected
     *  fundamental and the magnitude range so the fundamental sits
     *  10 dB below the top with the noise floor 20 dB below the
     *  bottom.  No-op when no analysis result is published yet. */
    private void autoSetup() {
        FftAnalyzer.Result r = worker.getLastResult();
        if (r == null) return;
        Preferences prefs = Preferences.instance();
        FftMagnitudeUnit unit = FftMagnitudeUnit.fromName(prefs.getFftMagUnit());

        // ── Frequency: one decade either side of the fundamental
        // (×0.1 .. ×10), clamped to bin size / Nyquist.
        double f0 = r.fundamentalHzRefined;
        if (Double.isFinite(f0) && f0 > 0) {
            double lo = Math.max(currentBinSize(), f0 / 10.0);
            double hi = Math.min(r.sampleRate / 2.0,  f0 * 10.0);
            if (hi > lo) {
                prefs.setFftFreqMinHz(lo);
                prefs.setFftFreqMaxHz(hi);
            }
        }

        // ── Magnitude: top = fundamental + 10 dB, bottom = noise
        // floor − 20 dB.  Translated to the active unit so the user's
        // view shows the spectrum sitting comfortably between the
        // strongest line and ~20 dB below the noise grass.
        if (Double.isFinite(r.fundamentalDbFs) && Double.isFinite(r.avgNoiseFloorDbFs)) {
            double topDbFs = r.fundamentalDbFs   + 10;
            double botDbFs = r.avgNoiseFloorDbFs - 20;
            double calRefDbV = Double.isNaN(r.fundRefDbV) ? 0
                    : (r.fundRefDbV - r.fundamentalDbFs);
            // The fundamental is rendered at the manual-override dBV
            // when the user supplied one, so the auto-setup ceiling
            // tracks that value (+10 dB headroom) instead of the
            // calibrated peak; the noise floor stays on the calibrated
            // anchor since non-fundamental bins aren't affected.
            double topRefDbV = (Double.isFinite(r.fundamentalTrueDbV)
                    && Double.isFinite(r.fundamentalDbFs))
                    ? r.fundamentalTrueDbV - r.fundamentalDbFs
                    : calRefDbV;
            double top = unit.convertFromDbFs(topDbFs, topRefDbV,  r.freqResolution);
            double bot = unit.convertFromDbFs(botDbFs, calRefDbV, r.freqResolution);
            if (Double.isFinite(top) && Double.isFinite(bot) && top != bot) {
                if (top < bot) { double s = top; top = bot; bot = s; }
                prefs.setFftMagTop(top);
                prefs.setFftMagBottom(bot);
            }
        }
        prefs.save();
        fireRangeChanged();
        redraw();
    }

    /** Maximises the visible spectrum: frequency range spans the full
     *  capture band ([bin size, Nyquist]) and the magnitude axis runs
     *  from a fixed +20 dB ceiling down to (noise floor − 30 dB).  The
     *  top is signal-independent — it doesn't track the fundamental —
     *  so the chart layout stays stable across signal changes.  Works
     *  even when no analysis result is published yet (uses fallback
     *  values for the bottom). */
    private void maximize() {
        FftAnalyzer.Result r = worker.getLastResult();
        Preferences prefs = Preferences.instance();
        FftMagnitudeUnit unit = FftMagnitudeUnit.fromName(prefs.getFftMagUnit());

        // Frequency span: 0 (clamped to bin size) → Nyquist.  Fall back
        // to currentNyquist() when no result is available yet — that
        // helper itself defaults to 192 kHz / 2 = 96 kHz half-rate.
        double nyquist = (r != null) ? r.sampleRate / 2.0 : currentNyquist();
        double lo = Math.max(currentBinSize(), 0.0);
        if (nyquist > lo) {
            prefs.setFftFreqMinHz(lo);
            prefs.setFftFreqMaxHz(nyquist);
        }

        // Fixed +20 dBFS ceiling, noise-floor − 30 dB floor (when
        // present; otherwise default to −150 dBFS bottom to give a
        // sensible empty-chart layout).
        double topDbFs = 20;
        double botDbFs = (r != null && Double.isFinite(r.avgNoiseFloorDbFs))
                ? r.avgNoiseFloorDbFs - 30
                : -150;
        double calRefDbV = 0;
        double binBw = 1.0;
        if (r != null) {
            calRefDbV = Double.isNaN(r.fundRefDbV) ? 0
                    : (r.fundRefDbV - r.fundamentalDbFs);
            binBw = r.freqResolution;
        }
        double top = unit.convertFromDbFs(topDbFs, calRefDbV, binBw);
        double bot = unit.convertFromDbFs(botDbFs, calRefDbV, binBw);
        if (Double.isFinite(top) && Double.isFinite(bot) && top != bot) {
            if (top < bot) { double s = top; top = bot; bot = s; }
            prefs.setFftMagTop(top);
            prefs.setFftMagBottom(bot);
        }
        prefs.save();
        fireRangeChanged();
        redraw();
    }

    /** Latest fundamental frequency in Hz, or {@code NaN} if no analysis yet.
     *  Used by Auto-Setup to fit 1-2 periods into the view. */
    public double getLastFrequencyHz() {
        FftAnalyzer.Result r = worker.getLastResult();
        return (r == null) ? Double.NaN : r.fundamentalHzRefined;
    }

    /** Latest fundamental Vrms (linear, calibrated by ADC fs voltage), or
     *  {@code null} when no analysis. */
    public Double getLastVrms() {
        FftAnalyzer.Result r = worker.getLastResult();
        if (r == null || Double.isNaN(r.fundamentalLinear)) return null;
        double fs = Preferences.instance().getAdcFsVoltageRms();
        return (fs > 0) ? r.fundamentalLinear * fs : null;
    }

    @Override
    public void redraw() {
        super.redraw();
        if (externalCanvas != null && !externalCanvas.isDisposed()) externalCanvas.redraw();
    }

    // =========================================================================
    // Paint pipeline
    // =========================================================================

    private void onPaint(PaintEvent e) {
        GC gc = e.gc;
        ensureFonts();
        ensureIconButtonResources();
        syncFftColors();

        Rectangle area = getClientArea();
        Preferences prefs = Preferences.instance();
        int rightMargin = 1;
        Rectangle plot = new Rectangle(MARGIN_LEFT,
                                       MARGIN_TOP,
                                       Math.max(1, area.width  - MARGIN_LEFT - rightMargin),
                                       Math.max(1, area.height - MARGIN_TOP  - MARGIN_BOTTOM));

        FftMagnitudeUnit unit = FftMagnitudeUnit.fromName(prefs.getFftMagUnit());
        double freqMin = Math.max(0, prefs.getFftFreqMinHz());
        double freqMax = Math.max(freqMin + 1, prefs.getFftFreqMaxHz());
        double magTop  = prefs.getFftMagTop();
        double magBot  = prefs.getFftMagBottom();
        boolean logFreq = prefs.isFftLogFreqAxis();
        if (logFreq && freqMin < 1) freqMin = 1;

        FftAnalyzer.Result r = worker.getLastResult();

        // ---- Static-layer buffer: blit cached image when nothing
        // affecting the trace / grid / axes has changed.  Mouse-move
        // redraws (crosshair tracking) cost only one drawImage instead
        // of re-walking 60 k+ spectrum bins.
        long fp = computeTraceFingerprint(r, area, unit, freqMin, freqMax,
                magTop, magBot, logFreq);
        if (traceBuffer == null || traceBuffer.isDisposed()
                || traceBufferW != area.width || traceBufferH != area.height
                || traceBufferFingerprint != fp) {
            rebuildTraceBuffer(area, plot, r, unit, freqMin, freqMax,
                    magTop, magBot, logFreq);
            traceBufferFingerprint = fp;
            traceBufferW = area.width;
            traceBufferH = area.height;
        }
        gc.drawImage(traceBuffer, 0, 0);

        // ---- Dynamic overlay (drawn live on top of the cached image)
        gc.setAntialias(SWT.ON);
        gc.setTextAntialias(SWT.ON);
        drawHeaderButtons(gc, r != null);
        if (r != null && prefs.isFftDistortionTableVisible() && !tableExtracted) {
            drawDistortionTable(gc, r, unit);
        }
        // Crosshair: only when inside the plot area AND not over any
        // header button (so the crosshair / floating readout don't
        // visually fight with the buttons that occupy the top-left).
        if (crossX >= plot.x && crossX < plot.x + plot.width
                && crossY >= plot.y && crossY < plot.y + plot.height
                && !pointerOverButton(crossX, crossY)) {
            drawCrosshair(gc, plot, r, unit, freqMin, freqMax, magTop, magBot, logFreq);
        }
    }

    /** Renders the static layers (background, grid, spectrum, phase,
     *  axes) into {@link #traceBuffer}.  Called only when the
     *  fingerprint or canvas size changes — the user's mouse-tracking
     *  crosshair fires {@link #redraw()} dozens of times per second but
     *  the fingerprint stays constant so this expensive path is skipped. */
    private void rebuildTraceBuffer(Rectangle area, Rectangle plot,
                                    FftAnalyzer.Result r, FftMagnitudeUnit unit,
                                    double freqMin, double freqMax,
                                    double magTop, double magBot,
                                    boolean logFreq) {
        if (traceBuffer != null && !traceBuffer.isDisposed()) traceBuffer.dispose();
        traceBuffer = new Image(getDisplay(), Math.max(1, area.width), Math.max(1, area.height));
        GC bgc = new GC(traceBuffer);
        try {
            bgc.setBackground(background);
            bgc.fillRectangle(0, 0, area.width, area.height);
            bgc.setAntialias(SWT.ON);
            bgc.setTextAntialias(SWT.ON);
            drawGrid(bgc, plot, freqMin, freqMax, magTop, magBot, logFreq, unit);
            drawDistortionBands(bgc, plot, freqMin, freqMax, logFreq);
            drawAxes(bgc, plot, freqMin, freqMax, magTop, magBot, logFreq, unit);
            if (r != null) {
                drawSpectrum(bgc, plot, r, unit, freqMin, freqMax, magTop, magBot, logFreq);
                drawHarmonicDots(bgc, plot, r, unit, freqMin, freqMax,
                        magTop, magBot, logFreq);
            }
        } finally {
            bgc.dispose();
        }
    }

    /** Tints the spectrum area outside the active distortion-HP /
     *  distortion-LP window with the same light grey the CLI FFT chart
     *  uses, so the user can see at a glance which bands are excluded
     *  from THD / SNR integration. */
    private void drawDistortionBands(GC gc, Rectangle plot,
                                     double freqMin, double freqMax,
                                     boolean logFreq) {
        Preferences prefs = Preferences.instance();
        boolean hpOn = prefs.isFftDistMinEnabled();
        boolean lpOn = prefs.isFftDistMaxEnabled();
        if (!hpOn && !lpOn) return;
        gc.setBackground(dimColor);
        if (hpOn) {
            double hp = prefs.getFftDistMinHz();
            if (hp > freqMin) {
                int xL = plot.x;
                int xR = freqToX(Math.min(hp, freqMax), plot, freqMin, freqMax, logFreq);
                if (xR > xL) gc.fillRectangle(xL, plot.y, xR - xL, plot.height);
            }
        }
        if (lpOn) {
            double lp = prefs.getFftDistMaxHz();
            if (lp < freqMax) {
                int xL = freqToX(Math.max(lp, freqMin), plot, freqMin, freqMax, logFreq);
                int xR = plot.x + plot.width;
                if (xR > xL) gc.fillRectangle(xL, plot.y, xR - xL, plot.height);
            }
        }
    }

    /** Draws a small red dot at the fundamental peak and at every
     *  detected harmonic — same {@code FUND_ANNOTATION_COLOR} the CLI
     *  FFT chart uses.  Helps the user see at a glance which peaks the
     *  analyser picked up. */
    private void drawHarmonicDots(GC gc, Rectangle plot, FftAnalyzer.Result r,
                                  FftMagnitudeUnit unit,
                                  double freqMin, double freqMax,
                                  double magTop, double magBot,
                                  boolean logFreq) {
        gc.setBackground(harmonicDotColor);
        double calRefDbV = Double.isNaN(r.fundRefDbV) ? 0
                : (r.fundRefDbV - r.fundamentalDbFs);
        // Only the fundamental dot lifts to the manual-override dBV;
        // harmonics stay on the calibrated anchor.
        double fundRefDbV = (Double.isFinite(r.fundamentalTrueDbV)
                && Double.isFinite(r.fundamentalDbFs))
                ? r.fundamentalTrueDbV - r.fundamentalDbFs
                : calRefDbV;
        double binBw = r.freqResolution;
        // Each plotDotAt gates on the dot's magnitude vs the visible
        // range and skips drawing when it falls outside — that hides
        // dots cleanly without snapping them to the chart edges.
        plotDotAt(gc, plot, r.fundamentalHzRefined, r.fundamentalDbFs,
                unit, freqMin, freqMax, magTop, magBot, logFreq, fundRefDbV, binBw);
        if (r.harmonicHz != null && r.harmonicDbFs != null) {
            int n = Math.min(r.harmonicHz.length, r.harmonicDbFs.length);
            for (int i = 0; i < n; i++) {
                plotDotAt(gc, plot, r.harmonicHz[i], r.harmonicDbFs[i],
                        unit, freqMin, freqMax, magTop, magBot, logFreq, calRefDbV, binBw);
            }
        }
    }

    private void plotDotAt(GC gc, Rectangle plot, double hz, double dbFs,
                           FftMagnitudeUnit unit,
                           double freqMin, double freqMax,
                           double magTop, double magBot,
                           boolean logFreq, double refDbV, double binBw) {
        if (!Double.isFinite(hz) || hz < freqMin || hz > freqMax) return;
        if (!Double.isFinite(dbFs)) return;
        double v = unit.convertFromDbFs(dbFs, refDbV, binBw);
        // Drop the dot entirely when its magnitude is outside the
        // visible range — dots clamped to the edge would otherwise
        // appear as misleading markers at the chart's top / bottom.
        double t = magToYFraction(v, magTop, magBot, unit);
        if (t < 0 || t > 1) return;
        int x = freqToX(hz, plot, freqMin, freqMax, logFreq);
        int y = magToY(v, plot, magTop, magBot, unit);
        int diameter = Math.max(2, Preferences.instance().getFftHarmonicDotDiameter());
        int radius   = diameter / 2;
        gc.fillOval(x - radius, y - radius, diameter, diameter);
    }

    /** Mixes every value that affects the cached image into a single
     *  long.  Identity-hash of {@link FftAnalyzer.Result} captures
     *  "new analysis result" without copying the arrays; the rest of
     *  the fields are simple primitives.  Two paints with the same
     *  fingerprint always produce the same cached image. */
    private long computeTraceFingerprint(FftAnalyzer.Result r, Rectangle area,
                                         FftMagnitudeUnit unit,
                                         double freqMin, double freqMax,
                                         double magTop, double magBot,
                                         boolean logFreq) {
        Preferences prefs = Preferences.instance();
        long h = (r == null) ? 0 : System.identityHashCode(r);
        h = 31 * h + area.width;
        h = 31 * h + area.height;
        h = 31 * h + unit.ordinal();
        h = 31 * h + Double.hashCode(freqMin);
        h = 31 * h + Double.hashCode(freqMax);
        h = 31 * h + Double.hashCode(magTop);
        h = 31 * h + Double.hashCode(magBot);
        h = 31 * h + (logFreq                       ? 1 : 0);
        h = 31 * h + (prefs.isFftDistMinEnabled()   ? 1 : 0);
        h = 31 * h + (prefs.isFftDistMaxEnabled()   ? 1 : 0);
        h = 31 * h + Double.hashCode(prefs.getFftDistMinHz());
        h = 31 * h + Double.hashCode(prefs.getFftDistMaxHz());
        // Appearance prefs — when these change the cached trace image
        // would otherwise still show the old colour / width.
        h = 31 * h + Double.hashCode(prefs.getFftLineWidth());
        h = 31 * h + prefs.getFftHarmonicDotDiameter();
        h = 31 * h + prefs.getFftLineColor();
        h = 31 * h + prefs.getFftChartBackgroundColor();
        h = 31 * h + prefs.getFftHarmonicDotColor();
        h = 31 * h + prefs.getFftFilterResponseColor();
        return h;
    }

    // =========================================================================
    // Grid + axes
    // =========================================================================

    private void drawGrid(GC gc, Rectangle plot,
                          double freqMin, double freqMax,
                          double magTop, double magBot,
                          boolean logFreq, FftMagnitudeUnit unit) {
        gc.setForeground(gridColor);
        gc.setLineWidth(1);
        // Vertical grid — log axis uses ALL minor ticks (1..9 × 10ⁿ)
        // so the user sees the classic REW-style log grid; linear axis
        // uses the same nice-number ticks the labels use.
        double[] vTicks = logFreq
                ? logFreqTicks(freqMin, freqMax, false)
                : niceLinearTicks(freqMin, freqMax, 10);
        for (double f : vTicks) {
            if (f <= freqMin || f >= freqMax) continue;
            int x = freqToX(f, plot, freqMin, freqMax, logFreq);
            gc.drawLine(x, plot.y, x, plot.y + plot.height);
        }
        // Horizontal grid — log units (V, V/√Hz) get decade ticks
        // (1..9 × 10ⁿ); dB units get nice linear ticks.
        double[] hTicks = isMagLog(unit)
                ? logMagTicks(magBot, magTop, false)
                : niceLinearTicks(magBot, magTop, 10);
        for (double m : hTicks) {
            if (m <= magBot || m >= magTop) continue;
            int y = magToY(m, plot, magTop, magBot, unit);
            gc.drawLine(plot.x, y, plot.x + plot.width, y);
        }
        // Plot frame.
        gc.setForeground(axisColor);
        gc.drawRectangle(plot.x, plot.y, plot.width, plot.height);
    }

    private void drawAxes(GC gc, Rectangle plot,
                          double freqMin, double freqMax,
                          double magTop, double magBot,
                          boolean logFreq, FftMagnitudeUnit unit) {
        gc.setFont(monoFont);
        gc.setForeground(textColor);

        // ── Left magnitude axis: labelled major ticks + short
        // intermediate tick marks for the unlabelled positions.
        // Mirrors the bottom frequency axis layout: major ticks get a
        // 4-px outward mark and a numeric label; minor ticks (1..9 ×
        // 10ⁿ on log, 5 sub-divisions per major step on linear) get a
        // 2-px mark when there's at least 6 px of spacing between
        // neighbours.  Labels drawn FIRST so the unit caption can
        // overpaint them.  dB axes clamp the major step at minimum
        // 5 dB so a tight zoom can't produce 1- or 2-dB majors —
        // matches the "ticks no denser than 5 dB" rule.
        double[] magTicks = isMagLog(unit)
                ? logMagTicks(magBot, magTop, true)
                : niceLinearTicksMinStep(magBot, magTop, 10, 5.0);
        int axisX = plot.x;
        gc.setForeground(axisColor);
        for (double m : magTicks) {
            if (m < magBot || m > magTop) continue;
            int y = magToY(m, plot, magTop, magBot, unit);
            gc.drawLine(axisX - 4, y, axisX, y);
            String s = formatMagnitudeBare(m, unit);
            gc.setForeground(textColor);
            Point ext = gc.textExtent(s);
            gc.drawText(s, plot.x - ext.x - 8, y - ext.y / 2, true);
            gc.setForeground(axisColor);
        }
        // Minor tick marks on the magnitude axis.  Two gates:
        //   • Tick mark only — 6-px minimum spacing from the previous
        //     drawn minor (avoids crowding in dense log decades).
        //   • Tick + label — additional check that the row also clears
        //     the nearest LABELLED tick by one text-height, otherwise
        //     the value text would visually overlap a major label.
        double[] magMinor = magMinorTicks(magBot, magTop, unit);
        int textH = gc.textExtent("M").y;
        int prevMinorY = Integer.MIN_VALUE;
        int prevLabelY = Integer.MIN_VALUE;
        for (double m : magMinor) {
            if (m < magBot || m > magTop) continue;
            int y = magToY(m, plot, magTop, magBot, unit);
            if (Math.abs(y - prevMinorY) < 6) continue;
            gc.drawLine(axisX - 2, y, axisX, y);
            prevMinorY = y;
            // Add a label too when there's room — check distance to
            // both the nearest major label (above + below) and the
            // previous minor label.
            boolean clearOfMajors = true;
            for (double M : magTicks) {
                if (M < magBot || M > magTop) continue;
                int yM = magToY(M, plot, magTop, magBot, unit);
                if (Math.abs(y - yM) < textH + 2) { clearOfMajors = false; break; }
            }
            if (clearOfMajors && Math.abs(y - prevLabelY) >= textH + 2) {
                String s = formatMagnitudeBare(m, unit);
                gc.setForeground(textColor);
                Point ext = gc.textExtent(s);
                gc.drawText(s, plot.x - ext.x - 8, y - ext.y / 2, true);
                gc.setForeground(axisColor);
                prevLabelY = y;
            }
        }
        gc.setForeground(textColor);

        // ── Unit caption (Z-order: drawn LAST so it sits ON TOP of
        //    any axis label that happens to share its row).  Painted
        //    onto a background-coloured rectangle so the overlapped
        //    portion of the underlying tick label is hidden — the
        //    caption is always legible, the tick slides "under" it
        //    when the user scrolls into the top of the range.
        String unitCap = unit.getLabel();
        Point unitExt = gc.textExtent(unitCap);
        int unitY = (MARGIN_TOP > unitExt.y)
                ? plot.y - unitExt.y - 1
                : plot.y + 2;
        int unitX = plot.x - unitExt.x - 4;
        gc.setBackground(background);
        // Extend the over-paint rectangle all the way to the canvas's
        // left edge so an axis label long enough to start at x=0 still
        // gets hidden behind the unit caption when they share a row.
        gc.fillRectangle(0, unitY - 1, unitX + unitExt.x + 2, unitExt.y + 2);
        gc.drawText(unitCap, unitX, unitY, true);

        // ── Bottom frequency axis: labelled major ticks + short
        // intermediate tick marks for the unlabelled positions.  On
        // the log axis the minor ticks (1..9 × 10ⁿ) get a 3-px
        // outward mark whenever there's at least 6 px of space between
        // them; on the linear axis we sub-divide each step into 5 and
        // draw a 2-px outward mark for each sub-position.
        double[] fLabels = freqTickValues(freqMin, freqMax, logFreq);
        int axisY = plot.y + plot.height;
        gc.setForeground(axisColor);
        // Major tick marks + labels.  Right-edge label gets nudged
        // inward so a centred-on-tick label that would otherwise hang
        // past the plot's right side (now flush with the scrollbar)
        // stays fully inside the chart.
        int plotRight = plot.x + plot.width;
        for (double f : fLabels) {
            int x = freqToX(f, plot, freqMin, freqMax, logFreq);
            gc.drawLine(x, axisY, x, axisY + 4);
            String s = logFreq ? formatFrequency(f) : formatFrequencyInteger(f);
            Point ext = gc.textExtent(s);
            int textX = x - ext.x / 2;
            if (textX + ext.x > plotRight) textX = plotRight - ext.x;
            if (textX < plot.x)            textX = plot.x;
            gc.drawText(s, textX, axisY + 4, true);
        }
        // Minor tick marks (no labels) — only emitted when neighbours
        // are far enough apart that they wouldn't overlap the labelled
        // ones visually.
        double[] minor = freqMinorTicks(freqMin, freqMax, logFreq);
        int prevMinorX = Integer.MIN_VALUE;
        for (double f : minor) {
            int x = freqToX(f, plot, freqMin, freqMax, logFreq);
            if (x - prevMinorX < 6) continue;
            gc.drawLine(x, axisY, x, axisY + 2);
            prevMinorX = x;
        }
        gc.setForeground(textColor);
    }

    /** Minor (unlabelled) ticks on the magnitude axis.  dB units
     *  emit positions on a fixed 5-dB grid, with positions that
     *  coincide with a major tick removed — yields regular spacing
     *  regardless of how niceLinearTicks chose the major step.  Log
     *  units (V / V/√Hz) emit every 1..9 × 10ⁿ position minus the
     *  labelled subset, then drop any consecutive pair closer than
     *  5 dB so the column never renders denser than the 5-dB grid. */
    private static double[] magMinorTicks(double magBot, double magTop, FftMagnitudeUnit unit) {
        if (isMagLog(unit)) {
            double[] all   = logMagAllSteps(magBot, magTop);
            double[] major = logMagTicks(magBot, magTop, true);
            return enforceMinDbSpacing(subtract(all, major), unit, 5.0);
        }
        // dB axis: walk the 5-dB grid directly so the pattern stays
        // 5-dB-aligned even when niceLinearTicks chose a 20-dB or
        // 25-dB major step.  Earlier we sub-divided the major step
        // (e.g. step=20 → sub=4) and filtered, which produced
        // irregular kept positions (e.g. 4 dB, 12 dB, 24 dB …) that
        // the user saw as "weird ticks in between" while scrolling.
        double[] major = niceLinearTicks(magBot, magTop, 10);
        double minorStep = 5.0;
        double first = Math.ceil(magBot / minorStep) * minorStep;
        List<Double> out = new ArrayList<>();
        for (double m = first; m <= magTop + 1e-9; m += minorStep) {
            if (m < magBot || m > magTop) continue;
            boolean isMajor = false;
            for (double M : major) {
                if (Math.abs(m - M) < 1e-6) { isMajor = true; break; }
            }
            if (!isMajor) out.add(m);
        }
        double[] arr = new double[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    /** Keeps only positions whose dB-equivalent distance from the
     *  previous kept value is at least {@code minDb}.  Used for log
     *  magnitude units where the dB equivalent of a 1..9 × 10ⁿ
     *  position is {@code 20·log10(v)}. */
    private static double[] enforceMinDbSpacing(double[] positions, FftMagnitudeUnit unit, double minDb) {
        if (positions.length == 0) return positions;
        List<Double> out = new ArrayList<>();
        double prevDb = Double.NEGATIVE_INFINITY;
        for (double v : positions) {
            double db = isMagLog(unit)
                    ? 20 * Math.log10(Math.max(Double.MIN_NORMAL, v))
                    : v;
            if (db - prevDb < minDb) continue;
            out.add(v);
            prevDb = db;
        }
        double[] arr = new double[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    /** Every 1..9 × 10ⁿ position in the visible log-magnitude range,
     *  with no thinning — used by {@link #magMinorTicks} to derive the
     *  unlabelled subset. */
    private static double[] logMagAllSteps(double magBot, double magTop) {
        double lo = Math.min(magBot, magTop);
        double hi = Math.max(magBot, magTop);
        if (lo <= 0) lo = 1e-30;
        if (hi <= lo) hi = lo * 10;
        int loDec = (int) Math.floor(Math.log10(lo));
        int hiDec = (int) Math.ceil (Math.log10(hi));
        List<Double> out = new ArrayList<>();
        for (int d = loDec; d <= hiDec; d++) {
            double base = Math.pow(10, d);
            for (int m = 1; m <= 9; m++) {
                double v = base * m;
                if (v < lo || v > hi) continue;
                out.add(v);
            }
        }
        double[] arr = new double[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    /** Minor (unlabelled) ticks on the frequency axis.  Returns all
     *  positions that aren't already in {@link #freqTickValues} so
     *  callers can draw a short tick mark at each. */
    private static double[] freqMinorTicks(double freqMin, double freqMax, boolean logFreq) {
        if (logFreq) {
            // Every 1..9 × 10ⁿ minus the major-tick subset returned by
            // logFreqTicks(.., labelsOnly=true).
            double[] all   = logFreqTicks(freqMin, freqMax, false);
            double[] major = logFreqTicks(freqMin, freqMax, true);
            return subtract(all, major);
        }
        // Linear: 5 sub-divisions per major step.
        double[] major = niceLinearTicks(freqMin, freqMax, 10);
        if (major.length < 2) return new double[0];
        double step = major[1] - major[0];
        double sub = step / 5.0;
        List<Double> out = new ArrayList<>();
        for (double f = major[0] - 4 * sub; f <= freqMax; f += sub) {
            if (f < freqMin || f > freqMax) continue;
            // Skip the major positions themselves.
            boolean isMajor = false;
            for (double m : major) {
                if (Math.abs(f - m) < sub * 0.5) { isMajor = true; break; }
            }
            if (!isMajor) out.add(f);
        }
        double[] arr = new double[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    /** Returns the elements of {@code all} that are not present in {@code
     *  exclude} (per a small numeric tolerance).  Used to split the log
     *  axis's all-minor-tick list into a labelled subset + unlabelled
     *  remainder. */
    private static double[] subtract(double[] all, double[] exclude) {
        List<Double> out = new ArrayList<>();
        for (double v : all) {
            boolean keep = true;
            for (double e : exclude) {
                if (Math.abs(v - e) < 1e-9 * Math.max(1, Math.abs(v))) { keep = false; break; }
            }
            if (keep) out.add(v);
        }
        double[] arr = new double[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    /** Major-tick values for the linear axis (used by both grid + labels).
     *  Log-axis callers should use {@link #logFreqTicks(double, double, boolean)}
     *  directly so they can distinguish major vs minor ticks. */
    private static double[] freqTickValues(double freqMin, double freqMax, boolean logFreq) {
        if (logFreq) return logFreqTicks(freqMin, freqMax, true);
        return niceLinearTicks(freqMin, freqMax, 10);
    }

    /** REW-style log frequency ticks: at each decade we emit 1×, 2×, 3×,
     *  4×, 5×, 6×, 7×, 8×, 9× × 10ⁿ.  {@code labelsOnly} = true returns
     *  only the values that should get a printed label (auto-thinned when
     *  the pixel density is high).  {@code labelsOnly} = false returns
     *  every minor tick — used by the grid. */
    private static double[] logFreqTicks(double freqMin, double freqMax, boolean labelsOnly) {
        double safeMin = Math.max(1, freqMin);
        double safeMax = Math.max(safeMin + 1, freqMax);
        int loDec = (int) Math.floor(Math.log10(safeMin));
        int hiDec = (int) Math.ceil (Math.log10(safeMax));
        List<Double> out = new ArrayList<>();
        for (int d = loDec; d <= hiDec; d++) {
            double base = Math.pow(10, d);
            for (int m = 1; m <= 9; m++) {
                double f = base * m;
                if (f < safeMin || f > safeMax) continue;
                out.add(f);
            }
        }
        if (!labelsOnly) {
            double[] arr = new double[out.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
            return arr;
        }
        // For labels: pick a subset depending on how many decades are
        // visible.  Few decades → show all 1..9; many → thin to 1,2,5 or
        // just 1 — REW does this dynamically by pixel density.  Use a
        // simple decade-count heuristic.
        int decades = hiDec - loDec;
        int[] keep;
        if (decades <= 1)      keep = new int[] {1,2,3,4,5,6,7,8};
        else if (decades <= 2) keep = new int[] {1,2,3,4,5,6,8};
        else if (decades <= 3) keep = new int[] {1,2,3,5,7};
        else if (decades <= 5) keep = new int[] {1,2,5};
        else                   keep = new int[] {1};
        List<Double> labels = new ArrayList<>();
        for (double f : out) {
            double base = Math.pow(10, Math.floor(Math.log10(f)));
            int   m    = (int) Math.round(f / base);
            for (int k : keep) if (k == m) { labels.add(f); break; }
        }
        double[] arr = new double[labels.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = labels.get(i);
        return arr;
    }

    /** Same as {@link #niceLinearTicks} but rounds the chosen step UP
     *  to at least {@code minStep} so the caller can enforce a
     *  per-axis density floor (e.g. "no ticks closer than 5 dB" on
     *  the magnitude axis).  When the natural step is already ≥
     *  {@code minStep} this returns identical output. */
    private static double[] niceLinearTicksMinStep(double min, double max, int targetCount, double minStep) {
        double[] candidate = niceLinearTicks(min, max, targetCount);
        if (candidate.length < 2) return candidate;
        double step = candidate[1] - candidate[0];
        if (step >= minStep) return candidate;
        // Force step to {@code minStep}, walking the aligned grid.
        double first = Math.ceil(min / minStep) * minStep;
        List<Double> out = new ArrayList<>();
        for (double f = first; f <= max + 1e-9; f += minStep) out.add(f);
        double[] arr = new double[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    /** Returns ~{@code targetCount} round-number tick positions between
     *  {@code min} and {@code max}.  Step is 1 / 2 / 2.5 / 5 × 10ⁿ. */
    private static double[] niceLinearTicks(double min, double max, int targetCount) {
        double range = Math.max(1e-9, max - min);
        double rough = range / Math.max(1, targetCount);
        double pow   = Math.pow(10, Math.floor(Math.log10(rough)));
        double mant  = rough / pow;
        double step;
        if      (mant < 1.5) step = 1     * pow;
        else if (mant < 3)   step = 2     * pow;
        else if (mant < 4)   step = 2.5   * pow;
        else if (mant < 7)   step = 5     * pow;
        else                 step = 10    * pow;
        double first = Math.ceil(min / step) * step;
        List<Double> out = new ArrayList<>();
        for (double f = first; f <= max + step * 1e-9; f += step) out.add(f);
        double[] arr = new double[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    // =========================================================================
    // Spectrum + phase traces
    // =========================================================================

    /**
     * Draws the spectrum trace.  Always uses a per-pixel min/max envelope
     * (collapses bins that map to the same column into one vertical line),
     * but also connects adjacent <em>known</em> columns with a polyline
     * segment so wide-bin regions of the spectrum (low frequencies on a
     * log axis, or any spectrum at high zoom) render as a continuous line
     * rather than scattered vertical bars.
     *
     * <p>This avoids the "vertical-line per bin" artifact at high zoom
     * where each FFT bin spans many pixels but only one pixel column had
     * a sample — previously those columns drew a {@code drawPoint} dot
     * with no connection.  Now the trace is one continuous polyline at
     * any zoom level, and stays O(plotWidth) for huge FFTs.
     */
    private void drawSpectrum(GC gc, Rectangle plot, FftAnalyzer.Result r,
                              FftMagnitudeUnit unit,
                              double freqMin, double freqMax,
                              double magTop, double magBot,
                              boolean logFreq) {
        if (r.amplitudeDbFs == null) return;
        gc.setForeground(spectrumColor);
        gc.setLineWidth((int) Math.max(1, Math.round(Preferences.instance().getFftLineWidth())));
        double binBw   = r.freqResolution;
        double refDbV  = Double.isNaN(r.fundRefDbV) ? 0
                : (r.fundRefDbV - r.fundamentalDbFs);
        // When the user supplied a "manual fundamental" dBV, the
        // fundamental bin alone is lifted to that value (computed
        // here as an alternate refDbV that maps its dBFS → manual dBV);
        // all other bins keep the calibrated anchor above.
        double fundRefDbVOverride = Double.NaN;
        int fundBin = -1;
        if (Double.isFinite(r.fundamentalTrueDbV)
                && Double.isFinite(r.fundamentalDbFs)
                && Double.isFinite(r.fundamentalHzRefined)
                && binBw > 0) {
            fundRefDbVOverride = r.fundamentalTrueDbV - r.fundamentalDbFs;
            fundBin = (int) Math.round(r.fundamentalHzRefined / binBw);
        }
        int n = r.amplitudeDbFs.length;
        int kFirst = Math.max(1,  (int) Math.floor(freqMin / binBw));
        int kLast  = Math.min(n - 1, (int) Math.ceil (freqMax / binBw));
        if (kLast <= kFirst) return;
        // Expand by one bin on either side so the trace extends ALL
        // the way to plot.x / plot.x + width.  Without this, the
        // first / last visible bins draw at an inset (their freqToX
        // sits a few pixels inside the plot edge) and the chart looks
        // like it has empty strips on the left and right.
        int kPrev = Math.max(0,     kFirst - 1);
        int kNext = Math.min(n - 1, kLast  + 1);

        int W = plot.width;
        int[]    yMins = new int[W];
        int[]    yMaxs = new int[W];
        int[]    cnts  = new int[W];
        Arrays.fill(yMins, Integer.MAX_VALUE);
        Arrays.fill(yMaxs, Integer.MIN_VALUE);
        // Anchors for bins JUST outside the visible x-range — used to
        // extend the polyline to the left / right plot edges.  Each
        // pair is (xUnbounded, yClamped); xUnbounded may be negative
        // or > W and the line draw routine handles that fine.
        int leftAnchorX  = Integer.MIN_VALUE, leftAnchorY  = 0;
        int rightAnchorX = Integer.MIN_VALUE, rightAnchorY = 0;
        for (int k = kPrev; k <= kNext; k++) {
            double f = k * binBw;
            int xAbs = freqToX(f, plot, freqMin, freqMax, logFreq);
            int x = xAbs - plot.x;
            double binRefDbV = (k == fundBin && Double.isFinite(fundRefDbVOverride))
                    ? fundRefDbVOverride
                    : refDbV;
            double v = unit.convertFromDbFs(r.amplitudeDbFs[k], binRefDbV, binBw);
            int y = magToY(v, plot, magTop, magBot, unit);
            if (x < 0) {
                // Keep the right-most "before plot" bin — it becomes
                // the polyline's left edge anchor.
                if (xAbs > leftAnchorX) { leftAnchorX = xAbs; leftAnchorY = y; }
            } else if (x >= W) {
                // Keep the left-most "after plot" bin — right anchor.
                if (rightAnchorX == Integer.MIN_VALUE || xAbs < rightAnchorX) {
                    rightAnchorX = xAbs; rightAnchorY = y;
                }
            } else {
                if (y < yMins[x]) yMins[x] = y;
                if (y > yMaxs[x]) yMaxs[x] = y;
                cnts[x]++;
            }
        }
        // Clip the rest of the drawing to the plot rectangle so the
        // polyline segments that anchor on bins JUST outside the
        // visible x-range (used to make the trace reach plot.x /
        // plot.x + width) don't draw past the chart border.  Vertical
        // envelope bars already live inside the plot, so the clip is
        // a no-op for them.
        Rectangle prevClip = gc.getClipping();
        gc.setClipping(plot);
        try {
            // First pass: vertical bars for columns that collapse multiple bins
            // into one pixel (envelope behaviour for zoomed-out views).
            for (int x = 0; x < W; x++) {
                if (cnts[x] == 0) continue;
                if (yMins[x] != yMaxs[x]) {
                    int px = plot.x + x;
                    gc.drawLine(px, yMins[x], px, yMaxs[x]);
                }
            }
            // Second pass: polyline through known column centres — gaps
            // between sparse bins (zoomed-in view, log axis at low freq)
            // get bridged so the trace looks continuous.  Left / right
            // anchors are prepended / appended so the polyline reaches
            // plot.x and plot.x + plot.width; the GC clip above hides
            // the portion of each anchor line that sits past the edge.
            int prevX = leftAnchorX, prevY = leftAnchorY;
            for (int x = 0; x < W; x++) {
                if (cnts[x] == 0) continue;
                int midY = (yMins[x] + yMaxs[x]) >>> 1;
                int px = plot.x + x;
                if (prevX != Integer.MIN_VALUE) gc.drawLine(prevX, prevY, px, midY);
                prevX = px;
                prevY = midY;
            }
            if (rightAnchorX != Integer.MIN_VALUE && prevX != Integer.MIN_VALUE) {
                gc.drawLine(prevX, prevY, rightAnchorX, rightAnchorY);
            }
        } finally {
            gc.setClipping(prevClip);
        }
    }

    // =========================================================================
    // Header buttons
    // =========================================================================

    private void drawHeaderButtons(GC gc, boolean hasData) {
        Preferences prefs = Preferences.instance();
        boolean isLeft  = prefs.getFftChannel() == Channel.L;
        boolean isRight = prefs.getFftChannel() == Channel.R;
        boolean distOn  = prefs.isFftDistortionTableVisible();

        int y = 5;
        int x = MARGIN_LEFT + 4;
        // L / R always visible — toggle the active channel.
        gc.setFont(chanButtonFont);
        drawChannelButton(gc, x, y, BTN_W, BTN_H, "L", isLeft, leftChanColor);
        setBounds(leftChanButtonBounds, x, y, BTN_W, BTN_H);
        x += BTN_W + BTN_GAP_SMALL;
        drawChannelButton(gc, x, y, BTN_W, BTN_H, "R", isRight, rightChanColor);
        setBounds(rightChanButtonBounds, x, y, BTN_W, BTN_H);
        x += BTN_W + BTN_GAP_LARGE;
        gc.setFont(monoFont);

        // Auto-Setup push.
        drawIconPushButton(gc, x, y, BTN_W, BTN_H, autoSetupIcon);
        setBounds(autoSetupButtonBounds, x, y, BTN_W, BTN_H);
        x += BTN_W + BTN_GAP_SMALL;

        // Maximise push — always visible (uses a fixed +20 dB ceiling
        // independent of any measured fundamental, so no live data is
        // required).  The handler reads the noise floor when present
        // and falls back to a default bottom otherwise.
        drawIconPushButton(gc, x, y, BTN_W, BTN_H, maximizeIcon);
        setBounds(maximizeButtonBounds, x, y, BTN_W, BTN_H);
        x += BTN_W + BTN_GAP_LARGE;

        // Distortion / Reset / External only when we have data.  Reset
        // sits immediately after Distortion so the user can re-arm both
        // the FFT averaging buffer and the THD running stats with one
        // click — and it stays in the main view even when the THD table
        // is extracted to the external window.
        if (hasData) {
            drawIconToggleButton(gc, x, y, BTN_W, BTN_H,
                    chartColumnIconLight, chartColumnIconDark, distOn);
            setBounds(distortionButtonBounds, x, y, BTN_W, BTN_H);
            x += BTN_W + BTN_GAP_SMALL;

            drawIconPushButton(gc, x, y, BTN_W, BTN_H, rotateLeftIcon);
            setBounds(resetButtonBounds, x, y, BTN_W, BTN_H);
            x += BTN_W + BTN_GAP_SMALL;

            drawIconToggleButton(gc, x, y, BTN_W, BTN_H,
                    windowRestoreIconLight, windowRestoreIconDark, tableExtracted);
            setBounds(externalButtonBounds, x, y, BTN_W, BTN_H);
        } else {
            distortionButtonBounds.width = 0; distortionButtonBounds.height = 0;
            externalButtonBounds  .width = 0; externalButtonBounds  .height = 0;
            resetButtonBounds     .width = 0; resetButtonBounds     .height = 0;
        }
    }

    /** Returns true when {@code (px, py)} lies inside any header button —
     *  caller uses this to suppress the crosshair / floating readout so
     *  they don't visually fight with the button icons. */
    private boolean pointerOverButton(int px, int py) {
        return leftChanButtonBounds  .contains(px, py)
            || rightChanButtonBounds .contains(px, py)
            || autoSetupButtonBounds .contains(px, py)
            || maximizeButtonBounds  .contains(px, py)
            || distortionButtonBounds.contains(px, py)
            || resetButtonBounds     .contains(px, py)
            || externalButtonBounds  .contains(px, py);
    }

    private void drawChannelButton(GC gc, int x, int y, int w, int h,
                                   String label, boolean selected, Color tint) {
        if (selected) {
            gc.setBackground(tint);
            gc.fillRoundRectangle(x, y, w, h, 4, 4);
            gc.setForeground(textColor);
        } else {
            gc.setForeground(buttonFrameColor);
            gc.drawRoundRectangle(x, y, w, h, 4, 4);
            gc.setForeground(textColor);
        }
        Point ext = gc.textExtent(label);
        gc.drawText(label, x + (w - ext.x) / 2, y + (h - ext.y) / 2, true);
    }

    private void drawIconToggleButton(GC gc, int x, int y, int w, int h,
                                      Image lightIcon, Image darkIcon, boolean on) {
        if (on) {
            gc.setBackground(buttonFrameColor);
            gc.fillRoundRectangle(x, y, w, h, 4, 4);
            Image icon = (darkIcon != null) ? darkIcon : lightIcon;
            if (icon != null) {
                Rectangle ib = icon.getBounds();
                gc.drawImage(icon, x + (w - ib.width) / 2, y + (h - ib.height) / 2);
            }
        } else {
            gc.setForeground(buttonFrameColor);
            gc.drawRoundRectangle(x, y, w, h, 4, 4);
            if (lightIcon != null) {
                Rectangle ib = lightIcon.getBounds();
                gc.drawImage(lightIcon, x + (w - ib.width) / 2, y + (h - ib.height) / 2);
            }
        }
    }

    private void drawIconPushButton(GC gc, int x, int y, int w, int h, Image icon) {
        if (icon == null) return;
        Rectangle ib = icon.getBounds();
        gc.drawImage(icon, x + (w - ib.width) / 2, y + (h - ib.height) / 2);
    }

    private static void setBounds(Rectangle r, int x, int y, int w, int h) {
        r.x = x; r.y = y; r.width = w; r.height = h;
    }

    // =========================================================================
    // THD overlay table
    // =========================================================================

    /**
     * Draws the THD info table in the format the CLI FFT chart uses —
     * a centred header line with f0 / dBFS / dBV, a centred span line,
     * three two-column metric rows (N+D / THD H₂..ₙ, N / THD+N, SNR /
     * ENOB), and harmonics laid out two per row.  THD/THD+N precision
     * is 10⁻⁸ %.  Column positions are derived from monospaced font
     * extents so the H₂..H₉ key column never overlaps the value column.
     */
    private void drawDistortionTable(GC gc, FftAnalyzer.Result r, FftMagnitudeUnit unit) {
        drawDistortionTable(gc, r, unit, MARGIN_LEFT + 6, TABLE_TOP_Y, true);
    }

    /** Renders the THD table.  Caller controls the {@code xLeft} / {@code yTop}
     *  origin so the extracted tool window can place the table at (0,0)
     *  instead of leaving room for the header buttons that are only
     *  drawn in the main FFT view. */
    private void drawDistortionTable(GC gc, FftAnalyzer.Result r, FftMagnitudeUnit unit,
                                     int xLeft, int yTop, boolean includeClockRow) {
        gc.setFont(monoFont);
        gc.setForeground(textColor);
        int y     = yTop;
        int lineH = gc.textExtent("M").y + 1;
        int charW = gc.textExtent("M").x;

        // Table width = harmonic-row layout span (xLeft → xLeft + 64·charW).
        // Centre line drawn at xLeft + 32·charW.
        int tableW = 64 * charW;
        int centreX = xLeft + tableW / 2;

        // ── Centred header (bold) ─────────────────────────────────────
        double thdMaxH = Preferences.instance().getFftThdMaxHarmonic();
        gc.setFont(monoBoldFont);
        // Prefer the user-supplied "manual fundamental" dBV (carried
        // in fundamentalTrueDbV) when it's set — this is the only
        // place the manual override should land.  fundRefDbV stays
        // anchored to the ADC calibration so every other bin
        // (harmonics, noise floor, the spectrum trace) keeps its
        // absolute dBV calibration.
        double fundDbV = Double.isFinite(r.fundamentalTrueDbV)
                ? r.fundamentalTrueDbV
                : (Double.isNaN(r.fundRefDbV) ? Double.NaN : r.fundRefDbV);
        String header = String.format("%.4f Hz   %.2f dBFS   %.2f dBV",
                r.fundamentalHzRefined, r.fundamentalDbFs, fundDbV);
        drawCentred(gc, header, centreX, y);
        y += lineH;
        drawCentred(gc, formatSpan(r), centreX, y);
        y += lineH;
        // ── Clock difference (Δf measured − requested) + crystal
        // oscillator delta translated back to the master oscillator
        // (22.5792 MHz for the 44.1k sample-rate family, 24.576 MHz
        // for the 48k family).  Mirrors the CLI FFT chart layout.
        // Only shown when "Get fundamental from generator" is enabled
        // — without that anchor the row would be meaningless.
        if (includeClockRow) {
            Preferences prefs = Preferences.instance();
            // Suppress the row when the generator isn't running — the
            // "expected" frequency would otherwise be a stale value
            // and the ΔF / Δosc readout would be meaningless.
            boolean genActive = isGeneratorActive();
            if (genActive && prefs.isFftFundFromGenerator()) {
                // Compare against the SNAPPED generator frequency
                // when snap-to-FFT-bin is on — the field holds the
                // raw user value (e.g. 1000 Hz) but the actual emitted
                // tone sits on the nearest bin (e.g. 999.987 Hz).
                // Using the raw value here would make ΔF report the
                // entire snap residual as a clock drift, which is
                // misleading.
                double expected = GeneratorController.snapToFftBinIfEnabled(
                        prefs, GenSignalForm.SINE,
                        r.sampleRate,
                        prefs.getGenFrequencyHz());
                if (expected > 0 && Double.isFinite(r.fundamentalHzRefined)) {
                    double delta = r.fundamentalHzRefined - expected;
                    double ppm   = 1e6 * delta / expected;
                    double osc;
                    String oscName;
                    if (r.sampleRate > 0 && r.sampleRate % 44100 == 0) {
                        osc = 22.5792e6; oscName = "22.5792 MHz";
                    } else if (r.sampleRate > 0 && r.sampleRate % 48000 == 0) {
                        osc = 24.576e6;  oscName = "24.576 MHz";
                    } else {
                        osc = Double.NaN; oscName = "?";
                    }
                    String clock;
                    if (Double.isNaN(osc)) {
                        clock = String.format("ΔF: %+.6f Hz (%+.2f ppm)", delta, ppm);
                    } else {
                        double oscDelta = osc * delta / expected;
                        clock = String.format("ΔF: %+.6f Hz (%+.2f ppm)  Δosc: %+.2f Hz @ %s",
                                delta, ppm, oscDelta, oscName);
                    }
                    drawCentred(gc, clock, centreX, y);
                    y += lineH;
                }
            }
        }
        gc.setFont(monoFont);
        y += 2;

        // Two independent layouts so the metric rows (short keys and
        // values) stay compact while the harmonic rows (wide keys are
        // short but values are ~26 mono chars) get room for their full
        // "%+8.2f dBV  %.8f %%" string without overlapping the right
        // column's key.
        int colGap = 2 * charW;
        // Metric layout — short values like "-78.41 dBV A" or "12.8 bits".
        int mKeyL = 7  * charW;       // "N+D:" / "SNR:" / "ENOB:"
        int mValL = 14 * charW;       // covers "-78.41 dBV A"
        int mKeyR = 12 * charW;       // "THD H2..9:"
        int mValR = 14 * charW;       // covers "0.00035790 %"
        int mRightColX = xLeft + mKeyL + mValL + colGap;

        drawKv(gc, xLeft, y, mKeyL,
                "N+D:",  fmtDb(r.thdNDb)  + " A",
                mRightColX, mKeyR,
                String.format("THD H2..%d:", (int) thdMaxH),
                String.format("%.8f %%", r.thdPct));
        y += lineH;
        drawKv(gc, xLeft, y, mKeyL,
                "N:",    fmtDb(noiseDb(r)),
                mRightColX, mKeyR,
                "THD+N:",     String.format("%.8f %%", thdNPct(r)));
        y += lineH;
        drawKv(gc, xLeft, y, mKeyL,
                "SNR:",  fmtDb(r.snrDb),
                mRightColX, mKeyR,
                "ENOB:", String.format("%.1f bits", (r.sinadDb - 1.76) / 6.02));
        y += lineH + 2;

        // ── Harmonics, 2 per row.  Independent layout: short keys
        // ("H2:" / "H10:") but wide values to fit the ~25-char
        // "%+8.2f dBV  %.8f %%" string.
        int hKey = 5  * charW;         // "H10:" + ":"
        int hVal = 26 * charW;         // covers "-109.72 dBV  0.00031899 %"
        int hRightColX = xLeft + hKey + hVal + colGap;
        int harmCount = (r.harmonicDbFs == null) ? 0 : r.harmonicDbFs.length;
        double dbvOff = Double.isNaN(r.fundRefDbV) ? 0 : (r.fundRefDbV - r.fundamentalDbFs);
        for (int i = 0; i < harmCount; i += 2) {
            String l = String.format("H%d:", i + 2);
            String lv = String.format("%8.2f dBV  %.8f %%",
                    r.harmonicDbFs[i] + dbvOff, r.harmonicPct[i]);
            String rkey = "", rval = "";
            if (i + 1 < harmCount) {
                rkey = String.format("H%d:", i + 3);
                rval = String.format("%8.2f dBV  %.8f %%",
                        r.harmonicDbFs[i + 1] + dbvOff, r.harmonicPct[i + 1]);
            }
            drawKv(gc, xLeft, y, hKey, l, lv, hRightColX, hKey, rkey, rval);
            y += lineH;
        }
        // Suppress unused-variable warning for the metric-row right
        // value width (kept for symmetry / future right-alignment).
        if (mValR < 0) gc.setForeground(textColor);
    }

    /** Two key/value pairs at independent key-column widths.  The
     *  left value is drawn at {@code x + lKeyW}, the right key at
     *  {@code rightColX}, and the right value at
     *  {@code rightColX + rKeyW}.  An empty right key skips the
     *  right column. */
    private static void drawKv(GC gc, int x, int y, int lKeyW,
                               String lKey, String lVal,
                               int rightColX, int rKeyW,
                               String rKey, String rVal) {
        gc.drawText(lKey, x, y, true);
        gc.drawText(lVal, x + lKeyW, y, true);
        if (rKey != null && !rKey.isEmpty()) {
            gc.drawText(rKey, rightColX, y, true);
            gc.drawText(rVal, rightColX + rKeyW, y, true);
        }
    }

    /** Formats the noise-integration band shown on the header span row.
     *  When both bounds are zero (the FftAnalyzer default = no band
     *  limit) the analyser integrates over the full spectrum, so report
     *  "full" rather than the misleading {@code "Span: 0 .. 0 Hz"}.
     *  Otherwise fall back to the actual numeric bounds, treating zero
     *  on one side as "0" or "Nyquist" as appropriate. */
    private static String formatSpan(FftAnalyzer.Result r) {
        boolean hasLo = r.snrFreqMin > 0;
        boolean hasHi = r.snrFreqMax > 0;
        if (!hasLo && !hasHi) return "Span: full";
        double lo = hasLo ? r.snrFreqMin : 0;
        double hi = hasHi ? r.snrFreqMax : r.sampleRate / 2.0;
        return String.format("Span: %.0f .. %.0f Hz", lo, hi);
    }

    /** Draws {@code text} horizontally centred on {@code centreX} with
     *  its top at {@code y} using the current GC font.  Centring is
     *  done via {@link GC#textExtent} so it respects the active font
     *  (mono / mono-bold) — callers don't have to pre-measure. */
    private static void drawCentred(GC gc, String text, int centreX, int y) {
        Point ext = gc.textExtent(text);
        gc.drawText(text, centreX - ext.x / 2, y, true);
    }

    private static String fmtDb(double v) {
        return Double.isFinite(v) ? String.format("%7.2f dBV", v) : "—";
    }

    private static double noiseDb(FftAnalyzer.Result r) {
        if (r.noisePower <= 0 || !Double.isFinite(r.fundRefDbV)) return Double.NaN;
        return 10 * Math.log10(r.noisePower) + (r.fundRefDbV - r.fundamentalDbFs);
    }

    private static double thdNPct(FftAnalyzer.Result r) {
        if (!Double.isFinite(r.thdNDb)) return Double.NaN;
        return Math.pow(10, r.thdNDb / 20.0) * 100;
    }

    // =========================================================================
    // Crosshair + readout
    // =========================================================================

    private void drawCrosshair(GC gc, Rectangle plot, FftAnalyzer.Result r,
                               FftMagnitudeUnit unit,
                               double freqMin, double freqMax,
                               double magTop, double magBot,
                               boolean logFreq) {
        gc.setForeground(crosshairColor);
        gc.setLineStyle(SWT.LINE_DOT);
        gc.drawLine(plot.x, crossY, plot.x + plot.width,  crossY);
        gc.drawLine(crossX, plot.y, crossX,               plot.y + plot.height);
        gc.setLineStyle(SWT.LINE_SOLID);

        // Build readout: f from mouse x, magnitude taken from the
        // spectrum bin nearest that frequency (NOT from the mouse-y, which
        // is unrelated to the actual signal level at that frequency).
        double f = xToFreq(crossX, plot, freqMin, freqMax, logFreq);
        StringBuilder sb = new StringBuilder();
        sb.append("f = ").append(formatFrequencyFine(f));
        if (r != null && r.amplitudeDbFs != null && r.freqResolution > 0) {
            int bin = (int) Math.round(f / r.freqResolution);
            if (bin >= 0 && bin < r.amplitudeDbFs.length) {
                // Calibrated anchor for non-fundamental bins; only the
                // fundamental bin uses the manual-override anchor so the
                // crosshair readout matches what the trace shows.
                double calRefDbV = Double.isNaN(r.fundRefDbV) ? 0
                        : (r.fundRefDbV - r.fundamentalDbFs);
                double refDbV = calRefDbV;
                if (Double.isFinite(r.fundamentalTrueDbV)
                        && Double.isFinite(r.fundamentalHzRefined)
                        && r.freqResolution > 0) {
                    int fb = (int) Math.round(r.fundamentalHzRefined / r.freqResolution);
                    if (bin == fb) refDbV = r.fundamentalTrueDbV - r.fundamentalDbFs;
                }
                double v = unit.convertFromDbFs(r.amplitudeDbFs[bin], refDbV, r.freqResolution);
                sb.append('\n').append("|m| = ").append(formatMagnitude(v, unit));
            }
        }
        String text = sb.toString();
        gc.setFont(monoFont);
        Point ext = gc.textExtent(text);
        int boxX = crossX + 12;
        int boxY = crossY + 12;
        if (boxX + ext.x + 8 > plot.x + plot.width)  boxX = crossX - ext.x - 16;
        if (boxY + ext.y + 6 > plot.y + plot.height) boxY = crossY - ext.y - 18;
        gc.setBackground(overlayBgColor);
        gc.fillRectangle(boxX, boxY, ext.x + 8, ext.y + 6);
        gc.setForeground(buttonFrameColor);
        gc.drawRectangle(boxX, boxY, ext.x + 8, ext.y + 6);
        gc.setForeground(textColor);
        gc.drawText(text, boxX + 4, boxY + 3, true);
    }

    // =========================================================================
    // External tool window for THD table
    // =========================================================================

    /** Re-fits the extracted THD window to whatever
     *  {@link #computeExternalContentSize()} currently returns.  Invoked
     *  by the FFT pane when "Max harmonic to calculate" changes — the
     *  shell was sized at create time and otherwise wouldn't track new
     *  row counts.  No-op when the window isn't open. */
    public void resizeExternalShellToContent() {
        if (externalShell == null || externalShell.isDisposed()) return;
        Point contentSz = computeExternalContentSize();
        Rectangle trim = externalShell.computeTrim(0, 0, contentSz.x, contentSz.y);
        externalShell.setSize(trim.width, trim.height);
    }

    public void setTableExtracted(boolean extracted) {
        if (extracted == tableExtracted) return;
        tableExtracted = extracted;
        syncExternalShell();
        redraw();
    }

    private void syncExternalShell() {
        boolean wantOpen = tableExtracted
                && Preferences.instance().isFftDistortionTableVisible();
        boolean isOpen = externalShell != null && !externalShell.isDisposed();
        if (wantOpen && !isOpen) createExternalShell();
        else if (!wantOpen && isOpen) {
            externalShell.dispose();
            externalShell  = null;
            externalCanvas = null;
        }
    }

    private void createExternalShell() {
        Shell parent = getShell();
        Shell s = new Shell(parent, SWT.DIALOG_TRIM);
        s.setText(I18n.t("fft.external.window.title"));
        s.setLayout(new FillLayout());
        Canvas c = new Canvas(s, SWT.DOUBLE_BUFFERED);
        c.setBackground(background);
        c.addPaintListener(this::paintExternalDistortion);
        externalShell  = s;
        externalCanvas = c;
        // Size to content: harmonic-row width + table-height (no header
        // button row — reset / distortion / external toggles stay in the
        // main FFT view).  computeTrim wraps the title bar + native
        // window borders around the client area.
        Rectangle pb = parent.getBounds();
        Point contentSz = computeExternalContentSize();
        Rectangle trim = s.computeTrim(0, 0, contentSz.x, contentSz.y);
        s.setSize(trim.width, trim.height);
        s.setLocation(pb.x + pb.width - trim.width - 24, pb.y + 96);
        s.addListener(SWT.Close, e -> {
            e.doit = false;
            setTableExtracted(false);
        });
        s.open();
    }

    /** Left padding (px) for the THD table inside the external window. */
    private static final int EXT_LEFT_PAD = 4;

    /** Computes the natural client size of the extracted THD table —
     *  used by {@link #createExternalShell} so the tool window fits its
     *  content exactly (no excess whitespace, no clipping).  Width comes
     *  from the harmonic-row layout (the widest row in the table) and
     *  height counts the fixed rows + dynamic harmonic rows. */
    private Point computeExternalContentSize() {
        ensureFonts();
        GC gc = new GC(this);
        try {
            gc.setFont(monoFont);
            int lineH = gc.textExtent("M").y + 1;
            // Measure the worst-case harmonic row directly — same
            // formatting drawDistortionTable uses (key + lVal + 2-space
            // gap + key + rVal) — and add a generous right padding.
            // Earlier attempts using "38·charW + textExtent(value)"
            // under-counted on systems where the mono "M" cell is a
            // pixel narrower than the actual digit / percent glyphs;
            // measuring the entire row text removes that gap.
            String widestRow = String.format(
                    "H10:  %+8.2f dBV  %.8f %%    H11:  %+8.2f dBV  %.8f %%",
                    -9999.99, 99.99999999, -9999.99, 99.99999999);
            int rowW = gc.textExtent(widestRow).x;
            int contentW = EXT_LEFT_PAD + rowW + 32;
            // Rows (no top button row in the external window):
            //   header + span + (clock?) + gap + 3 metric rows + gap + harm pairs
            // Pref value N means "compute up to HN" → N − 1 harmonics
            // (H2..HN) → ceil((N − 1) / 2) row pairs.
            int maxH = Math.max(9, Preferences.instance().getFftCalcMaxHarmonic());
            int harmRows = ((maxH - 1) + 1) / 2;
            int clockRow = Preferences.instance().isFftFundFromGenerator() ? lineH : 0;
            int contentH = EXT_LEFT_PAD                // top breathing room
                         + lineH                       // header
                         + lineH                       // span
                         + clockRow                    // optional clock + crystal rows
                         + 2                           // gap after header block
                         + 3 * lineH                   // 3 metric rows
                         + 2                           // gap before harmonics
                         + harmRows * lineH
                         + 4;                          // bottom breathing room
            return new Point(contentW, contentH);
        } finally { gc.dispose(); }
    }

    private void paintExternalDistortion(PaintEvent ev) {
        FftAnalyzer.Result r = worker.getLastResult();
        if (r == null) return;
        GC gc = ev.gc;
        gc.setAntialias(SWT.ON);
        gc.setTextAntialias(SWT.ON);
        ensureFonts();
        // Draw table with a small inset from the top-left so the keys
        // don't touch the window border.  No reset button here — the
        // button stays in the main FFT view.
        FftMagnitudeUnit unit = FftMagnitudeUnit.fromName(Preferences.instance().getFftMagUnit());
        drawDistortionTable(gc, r, unit, EXT_LEFT_PAD, EXT_LEFT_PAD, true);
    }

    // =========================================================================
    // Mouse handlers
    // =========================================================================

    private void onMouseDown(MouseEvent ev) {
        if (ev.button != 1) return;
        Preferences prefs = Preferences.instance();
        if (leftChanButtonBounds.contains(ev.x, ev.y)) {
            prefs.setFftChannel(Channel.L); prefs.save(); redraw(); return;
        }
        if (rightChanButtonBounds.contains(ev.x, ev.y)) {
            prefs.setFftChannel(Channel.R); prefs.save(); redraw(); return;
        }
        if (autoSetupButtonBounds.contains(ev.x, ev.y)) {
            autoSetup();
            return;
        }
        if (maximizeButtonBounds.contains(ev.x, ev.y)) {
            maximize();
            return;
        }
        if (distortionButtonBounds.contains(ev.x, ev.y)) {
            prefs.setFftDistortionTableVisible(!prefs.isFftDistortionTableVisible());
            prefs.save();
            syncExternalShell();
            redraw();
            return;
        }
        if (externalButtonBounds.contains(ev.x, ev.y)) {
            setTableExtracted(!tableExtracted);
            return;
        }
        if (resetButtonBounds.contains(ev.x, ev.y)) {
            resetStatistics();
            redraw();
            return;
        }
    }

    /**
     * Mouse-wheel handling — mirrors the oscilloscope view's UX:
     *   Ctrl + wheel         → magnitude (vertical) zoom around mouse Y
     *   Ctrl + Shift + wheel → frequency (horizontal) zoom around mouse X
     *   Shift + wheel        → horizontal pan (frequency)
     *   plain wheel          → vertical pan (magnitude)
     * In every case the value under the cursor stays under the cursor so
     * the zoom feels anchored, matching the scope's wheel behaviour.
     */
    private void onMouseWheel(org.eclipse.swt.widgets.Event e) {
        if (e.count == 0) return;
        int dir = Integer.signum(e.count);   // wheel up = +1
        boolean ctrl  = (e.stateMask & SWT.CTRL)  != 0;
        boolean shift = (e.stateMask & SWT.SHIFT) != 0;
        Rectangle area = getClientArea();
        int rightMargin = 2;
        Rectangle plot = new Rectangle(MARGIN_LEFT, MARGIN_TOP,
                Math.max(1, area.width  - MARGIN_LEFT - rightMargin),
                Math.max(1, area.height - MARGIN_TOP  - MARGIN_BOTTOM));
        if (ctrl && shift) {
            zoomFrequencyAroundCursor(e.x, plot, dir);
        } else if (ctrl) {
            zoomMagnitudeAroundCursor(e.y, plot, dir);
        } else if (shift) {
            panFrequency(dir);
        } else {
            panMagnitude(dir);
        }
        e.doit = false;
    }

    /** Magnitude zoom-around-cursor: keeps the magnitude value under the
     *  pointer fixed while shrinking / growing the visible range by 20 %.
     *  Operates in log space for V / V/√Hz so the zoom feels symmetric
     *  on the log axis. */
    private void zoomMagnitudeAroundCursor(int mouseY, Rectangle plot, int dir) {
        Preferences prefs = Preferences.instance();
        FftMagnitudeUnit unit = FftMagnitudeUnit.fromName(prefs.getFftMagUnit());
        double top = prefs.getFftMagTop();
        double bot = prefs.getFftMagBottom();
        if (top - bot <= 0) return;
        double frac = (double) (mouseY - plot.y) / plot.height;
        if (frac < 0) frac = 0; else if (frac > 1) frac = 1;
        double scale = (dir > 0) ? 0.8 : 1.25;
        double newTop, newBot;
        if (isMagLog(unit)) {
            double lt = Math.log10(Math.max(1e-30, top));
            double lb = Math.log10(Math.max(1e-30, bot));
            if (lt <= lb) return;
            double anchor = lt - frac * (lt - lb);
            double newSpan = (lt - lb) * scale;
            newTop = Math.pow(10, anchor + frac       * newSpan);
            newBot = Math.pow(10, anchor - (1 - frac) * newSpan);
        } else {
            double anchor = top - frac * (top - bot);
            double newSpan = (top - bot) * scale;
            newTop = anchor + frac       * newSpan;
            newBot = anchor - (1 - frac) * newSpan;
        }
        prefs.setFftMagTop(newTop);
        prefs.setFftMagBottom(newBot);
        prefs.save();
        fireRangeChanged();
        redraw();
    }

    /** Frequency zoom-around-cursor (handles log + linear axes). */
    private void zoomFrequencyAroundCursor(int mouseX, Rectangle plot, int dir) {
        Preferences prefs = Preferences.instance();
        double fMin = prefs.getFftFreqMinHz();
        double fMax = prefs.getFftFreqMaxHz();
        boolean logFreq = prefs.isFftLogFreqAxis();
        if (fMax - fMin <= 0) return;
        double frac = (double) (mouseX - plot.x) / plot.width;
        if (frac < 0) frac = 0; else if (frac > 1) frac = 1;
        double scale = (dir > 0) ? 0.8 : 1.25;
        double newMin, newMax;
        if (logFreq) {
            double safeMin = Math.max(1, fMin);
            double safeMax = Math.max(safeMin + 1, fMax);
            double lo = Math.log10(safeMin);
            double hi = Math.log10(safeMax);
            double span = hi - lo;
            double anchor = lo + frac * span;
            double newSpan = span * scale;
            newMin = Math.pow(10, anchor - frac       * newSpan);
            newMax = Math.pow(10, anchor + (1 - frac) * newSpan);
        } else {
            double span = fMax - fMin;
            double anchor = fMin + frac * span;
            double newSpan = span * scale;
            newMin = anchor - frac       * newSpan;
            newMax = anchor + (1 - frac) * newSpan;
            if (newMin < 0) { newMax -= newMin; newMin = 0; }
        }
        prefs.setFftFreqMinHz(newMin);
        prefs.setFftFreqMaxHz(newMax);
        prefs.save();
        fireRangeChanged();
        redraw();
    }

    /** Plain-wheel pan: shifts the magnitude window by ~10 % of its
     *  span without changing the visible span itself.  Operates in log
     *  space for V / V/√Hz so a pan multiplies both bounds by the same
     *  factor — otherwise an additive pan on a log axis visibly
     *  compresses or stretches the range.
     *
     *  <p>Clips the step against the hardware limits (max top, min bot)
     *  so a pan that would push one bound past its limit moves both
     *  bounds by less rather than letting the post-pan clamp collapse
     *  the visible span.  Without this, panning up past +20 dB (the
     *  ADC-FS-derived top limit) used to look like a scale change as
     *  the bottom kept moving but the top stayed pinned. */
    private void panMagnitude(int dir) {
        Preferences prefs = Preferences.instance();
        FftMagnitudeUnit unit = FftMagnitudeUnit.fromName(prefs.getFftMagUnit());
        double top = prefs.getFftMagTop();
        double bot = prefs.getFftMagBottom();
        double[] lim = magLimits(unit, prefs.getAdcFsVoltageRms());
        double maxTp = lim[0], minBt = lim[1];
        if (isMagLog(unit)) {
            double lt = Math.log10(Math.max(Double.MIN_NORMAL, top));
            double lb = Math.log10(Math.max(Double.MIN_NORMAL, bot));
            double step = (lt - lb) * 0.1 * dir;
            double lmaxTp = Math.log10(Math.max(Double.MIN_NORMAL, maxTp));
            double lminBt = Math.log10(Math.max(Double.MIN_NORMAL, minBt));
            if (step > 0 && lt + step > lmaxTp) step = lmaxTp - lt;
            else if (step < 0 && lb + step < lminBt) step = lminBt - lb;
            prefs.setFftMagTop(Math.pow(10, lt + step));
            prefs.setFftMagBottom(Math.pow(10, lb + step));
        } else {
            double step = (top - bot) * 0.1 * dir;
            if (step > 0 && top + step > maxTp) step = maxTp - top;
            else if (step < 0 && bot + step < minBt) step = minBt - bot;
            prefs.setFftMagTop(top + step);
            prefs.setFftMagBottom(bot + step);
        }
        prefs.save();
        fireRangeChanged();
        redraw();
    }

    /** Returns {@code [maxTop, minBot]} for the given magnitude unit
     *  using the active ADC full-scale calibration as the anchor.
     *  Matches {@link org.edgo.audio.measure.gui.fft.FftPane}'s clamp
     *  logic so the pan-step clip here lines up exactly with the
     *  post-pan clamp the pane applies. */
    private static double[] magLimits(FftMagnitudeUnit unit, double adcFsVrms) {
        double fs = (adcFsVrms > 0) ? adcFsVrms : 1.0;
        switch (unit) {
            case DBFS:
                return new double[] { 0, -300 };
            case DBV: {
                double dbvFs = 20 * Math.log10(fs);
                double maxTp = Math.ceil((dbvFs + 5) / 10.0) * 10;
                return new double[] { maxTp, -300 + dbvFs };
            }
            case V:
                return new double[] { Math.ceil(fs + 1), 1e-15 };       // 1 fV floor
            case V_SQRT_HZ:
                return new double[] { Math.max(1e-3, fs), 1e-15 };
            default:
                return new double[] { 0, -300 };
        }
    }

    /** Shift-wheel pan: shifts the frequency window by ~10 % of its span.
     *  Clips the step against the bin-size / Nyquist limits so a pan
     *  that would push one bound past its limit moves both bounds by
     *  less rather than letting the post-pan clamp shrink the visible
     *  span — without this, panning past either edge looked like a
     *  zoom-in. */
    private void panFrequency(int dir) {
        Preferences prefs = Preferences.instance();
        double fMin = prefs.getFftFreqMinHz();
        double fMax = prefs.getFftFreqMaxHz();
        boolean logFreq = prefs.isFftLogFreqAxis();
        double binSize = currentBinSize();
        double nyq     = currentNyquist();
        if (logFreq) {
            double lo = Math.log10(Math.max(1, fMin));
            double hi = Math.log10(Math.max(lo + 1, fMax));
            double step = (hi - lo) * 0.1 * dir;
            double lMin = Math.log10(Math.max(1, binSize));
            double lMax = Math.log10(Math.max(binSize, nyq));
            if (step > 0 && hi + step > lMax) step = lMax - hi;
            else if (step < 0 && lo + step < lMin) step = lMin - lo;
            prefs.setFftFreqMinHz(Math.pow(10, lo + step));
            prefs.setFftFreqMaxHz(Math.pow(10, hi + step));
        } else {
            double step = (fMax - fMin) * 0.1 * dir;
            if (step > 0 && fMax + step > nyq) step = nyq - fMax;
            else if (step < 0 && fMin + step < binSize) step = binSize - fMin;
            prefs.setFftFreqMinHz(fMin + step);
            prefs.setFftFreqMaxHz(fMax + step);
        }
        prefs.save();
        fireRangeChanged();
        redraw();
    }

    /** Bin-size lower bound on the visible freq range (Hz).  Derived
     *  from the most recent analysis result so it tracks the active
     *  FFT length and sample rate.  Falls back to a wide guess when
     *  no result is yet available. */
    private double currentBinSize() {
        FftAnalyzer.Result r = worker.getLastResult();
        if (r != null && r.fftSize > 0) return (double) r.sampleRate / r.fftSize;
        int sr = 384_000;
        int fftLen = Math.max(8, Preferences.instance().getFftLength());
        return (double) sr / fftLen;
    }

    /** Nyquist upper bound on the visible freq range (Hz). */
    private double currentNyquist() {
        FftAnalyzer.Result r = worker.getLastResult();
        if (r != null) return r.sampleRate / 2.0;
        return 192_000;
    }

    private void onMouseMove(MouseEvent ev) {
        crossX = ev.x;
        crossY = ev.y;
        int cursorId = SWT.CURSOR_CROSS;
        String tip = null;
        if (leftChanButtonBounds.contains(ev.x, ev.y)) {
            cursorId = SWT.CURSOR_HAND; tip = I18n.t("fft.button.left.tooltip");
        } else if (rightChanButtonBounds.contains(ev.x, ev.y)) {
            cursorId = SWT.CURSOR_HAND; tip = I18n.t("fft.button.right.tooltip");
        } else if (autoSetupButtonBounds.contains(ev.x, ev.y)) {
            cursorId = SWT.CURSOR_HAND; tip = I18n.t("fft.autosetup.tooltip");
        } else if (maximizeButtonBounds.contains(ev.x, ev.y)) {
            cursorId = SWT.CURSOR_HAND; tip = I18n.t("fft.maximize.tooltip");
        } else if (distortionButtonBounds.contains(ev.x, ev.y)) {
            cursorId = SWT.CURSOR_HAND; tip = I18n.t("fft.distortion.tooltip");
        } else if (resetButtonBounds.contains(ev.x, ev.y)) {
            cursorId = SWT.CURSOR_HAND; tip = I18n.t("fft.reset.tooltip");
        } else if (externalButtonBounds.contains(ev.x, ev.y)) {
            cursorId = SWT.CURSOR_HAND; tip = I18n.t("fft.external.tooltip");
        }
        setCursor(getDisplay().getSystemCursor(cursorId));
        // Only push tooltip changes when the value actually changes —
        // setToolTipText forces a re-show which fires more mouse-move
        // events and would otherwise dim the trace from constant redraws.
        String current = getToolTipText();
        if ((tip == null && current != null)
                || (tip != null && !tip.equals(current))) {
            setToolTipText(tip);
        }
        redraw();
    }

    // =========================================================================
    // Coordinate / formatting helpers
    // =========================================================================

    private int freqToX(double f, Rectangle plot, double freqMin, double freqMax, boolean logFreq) {
        if (logFreq) {
            // Both bounds compared / logged in linear Hz space.  The
            // earlier version mixed log10(freqMin) with raw freqMax
            // inside Math.max, which silently inverted the range when
            // freqMax was small.
            double safeMin = Math.max(1, freqMin);
            double safeMax = Math.max(safeMin + 1, freqMax);
            double lo = Math.log10(safeMin);
            double hi = Math.log10(safeMax);
            double t  = (Math.log10(Math.max(1, f)) - lo) / (hi - lo);
            return plot.x + (int) Math.round(t * plot.width);
        }
        double t = (f - freqMin) / (freqMax - freqMin);
        return plot.x + (int) Math.round(t * plot.width);
    }

    private double xToFreq(int x, Rectangle plot, double freqMin, double freqMax, boolean logFreq) {
        double t = (double) (x - plot.x) / plot.width;
        if (logFreq) {
            double safeMin = Math.max(1, freqMin);
            double safeMax = Math.max(safeMin + 1, freqMax);
            double lo = Math.log10(safeMin);
            double hi = Math.log10(safeMax);
            return Math.pow(10, lo + t * (hi - lo));
        }
        return freqMin + t * (freqMax - freqMin);
    }

    /** Returns true when the magnitude axis should be log-scaled.  V and
     *  V/√Hz are linear amplitudes that span many decades for a typical
     *  audio recording (1 V → 1 µV) so they're displayed on a log axis;
     *  dBV / dBFS are already log domain so the axis stays linear in dB. */
    private static boolean isMagLog(FftMagnitudeUnit unit) {
        return unit == FftMagnitudeUnit.V || unit == FftMagnitudeUnit.V_SQRT_HZ;
    }

    private int magToY(double v, Rectangle plot, double top, double bot, FftMagnitudeUnit unit) {
        double t = magToYFraction(v, top, bot, unit);
        if (t < 0) t = 0;
        if (t > 1) t = 1;
        return plot.y + (int) Math.round(t * plot.height);
    }

    /** Shared mapping kernel: returns the fractional position 0..1 of
     *  {@code v} within the {@code [bot, top]} range, where 0 = top
     *  and 1 = bot.  No clamping. */
    private static double magToYFraction(double v, double top, double bot, FftMagnitudeUnit unit) {
        if (isMagLog(unit)) {
            double vL  = (v   <= 0) ? Double.NEGATIVE_INFINITY : Math.log10(v);
            double topL = (top <= 0) ? -30 : Math.log10(top);
            double botL = (bot <= 0) ? -30 : Math.log10(bot);
            if (topL <= botL) return 0;
            return (topL - vL) / (topL - botL);
        }
        return (top - v) / (top - bot);
    }

    /** Major ticks on a log magnitude axis — emits the 1×10ⁿ decades by
     *  default, with the 2× and 5× intermediates added once the visible
     *  decade count is small enough that they fit without crowding.  Used
     *  by both the grid (every emitted tick gets a line) and labels
     *  (caller passes {@code labelsOnly=true} so only major decade lines
     *  get text). */
    private static double[] logMagTicks(double magBot, double magTop, boolean labelsOnly) {
        double lo = Math.min(magBot, magTop);
        double hi = Math.max(magBot, magTop);
        if (lo <= 0) lo = 1e-30;
        if (hi <= lo) hi = lo * 10;
        int loDec = (int) Math.floor(Math.log10(lo));
        int hiDec = (int) Math.ceil (Math.log10(hi));
        int decades = Math.max(1, hiDec - loDec);
        int[] keep;
        if      (decades <= 3) keep = new int[] {1, 2, 5};
        else if (decades <= 6) keep = new int[] {1, 2};
        else                   keep = new int[] {1};
        List<Double> out = new ArrayList<>();
        for (int d = loDec; d <= hiDec; d++) {
            double base = Math.pow(10, d);
            for (int m : keep) {
                double v = base * m;
                if (v < lo || v > hi) continue;
                out.add(v);
            }
        }
        // If labelsOnly is false we still emit the same set — every tick
        // gets a grid line.  The labelsOnly flag is reserved for future
        // thinning if axes get crowded.
        if (labelsOnly && decades > 8) {
            // Trim to decade-only labels for very tall axes.
            List<Double> labels = new ArrayList<>();
            for (double v : out) {
                double base = Math.pow(10, Math.floor(Math.log10(v)));
                if (Math.abs(v / base - 1) < 1e-9) labels.add(v);
            }
            out = labels;
        }
        double[] arr = new double[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    private static String formatFrequency(double f) {
        if (!Double.isFinite(f) || f <= 0) return "—";
        if (f >= 1000) return String.format("%.2f kHz", f / 1000);
        return String.format("%.1f Hz", f);
    }

    /** Fine-grained formatter for the crosshair readout and the THD-table
     *  fundamental row — shows 4 decimal places so the user can read the
     *  sub-Hz refinement that {@link FftAnalyzer.Result#fundamentalHzRefined}
     *  produces.  Switches to kHz at 10 kHz so the digits stay aligned. */
    private static String formatFrequencyFine(double f) {
        if (!Double.isFinite(f) || f <= 0) return "—";
        if (f >= 10_000) return String.format("%.4f kHz", f / 1000);
        return String.format("%.4f Hz", f);
    }

    /** Integer-only frequency formatter used on the linear axis labels —
     *  no fractional Hz, kHz with 0 / 1 decimals depending on size. */
    private static String formatFrequencyInteger(double f) {
        if (!Double.isFinite(f)) return "—";
        if (f >= 1000) {
            double k = f / 1000;
            if (Math.abs(k - Math.round(k)) < 0.05) return String.format("%d kHz", (long) Math.round(k));
            return String.format("%.1f kHz", k);
        }
        return String.format("%d Hz", (long) Math.round(f));
    }

    private static String formatMagnitude(double v, FftMagnitudeUnit unit) {
        if (!Double.isFinite(v)) return "—";
        switch (unit) {
            case DBFS: return String.format("%.1f dBFS", v);
            case DBV:  return String.format("%.1f dBV",  v);
            case V:    return formatVoltsSi(v) + "V";
            case V_SQRT_HZ: return formatVoltsSi(v) + "V/√Hz";
            default:   return String.format("%g", v);
        }
    }

    /** Same as {@link #formatMagnitude} but without the unit suffix — used
     *  for per-tick axis labels (the unit is drawn once above the axis).
     *  Drops the "+" prefix on positive dB values so the column reads as
     *  "10.0 / 0.0 / -10.0" rather than "+10.0 / +0.0 / -10.0". */
    private static String formatMagnitudeBare(double v, FftMagnitudeUnit unit) {
        if (!Double.isFinite(v)) return "—";
        switch (unit) {
            case DBFS:
            case DBV:  return String.format("%.1f", v);
            case V:
            case V_SQRT_HZ: return formatVoltsSi(v);
            default:   return String.format("%g", v);
        }
    }

    /** SI-prefix formatter for the V / V/√Hz axes.  Keeps axis labels
     *  compact: 1.5 V instead of 1.500e+00, 100 mV instead of 0.1000.
     *  Uses k / (no prefix) / m / µ / n / p / f over the audio dynamic
     *  range; the trailing space is part of the prefix so callers
     *  concatenate the unit suffix directly.  Negative values keep
     *  their sign. */
    private static String formatVoltsSi(double v) {
        if (v == 0) return "0 ";
        double abs = Math.abs(v);
        String prefix;
        double scale;
        if      (abs >= 1e3)  { prefix = "k"; scale = 1e3; }
        else if (abs >= 1)    { prefix = "";  scale = 1;   }
        else if (abs >= 1e-3) { prefix = "m"; scale = 1e-3; }
        else if (abs >= 1e-6) { prefix = "µ"; scale = 1e-6; }
        else if (abs >= 1e-9) { prefix = "n"; scale = 1e-9; }
        else if (abs >= 1e-12){ prefix = "p"; scale = 1e-12; }
        else                  { prefix = "f"; scale = 1e-15; }
        double s = v / scale;
        // Compact mantissa: drop trailing zeros, max 3 sig figs.
        String m;
        if      (Math.abs(s) >= 100) m = String.format("%.0f", s);
        else if (Math.abs(s) >= 10)  m = String.format("%.1f", s);
        else                          m = String.format("%.2f", s);
        // Strip trailing zeros / dot for compactness.
        if (m.contains(".")) {
            m = m.replaceAll("0+$", "");
            if (m.endsWith(".")) m = m.substring(0, m.length() - 1);
        }
        return m + " " + prefix;
    }

    // =========================================================================
    // Resource lazy initialisation
    // =========================================================================

    private void ensureFonts() {
        Display d = getDisplay();
        if (monoFont == null)       monoFont       = new Font(d, "Consolas", 9, SWT.NORMAL);
        if (monoBoldFont == null)   monoBoldFont   = new Font(d, "Consolas", 9, SWT.BOLD);
        if (chanButtonFont == null) chanButtonFont = new Font(d, "Consolas", 12, SWT.BOLD);
    }

    private void ensureIconButtonResources() {
        if (chartColumnIconLight != null) return;
        Display d = getDisplay();
        RGB light = new RGB(0x20, 0x20, 0x20);
        RGB dark  = new RGB(0xFF, 0xFF, 0xFF);
        RGB red   = new RGB(220,   60,  60);
        IconUtils icons = IconUtils.instance();
        chartColumnIconLight    = icons.renderAtHeight(d, SvgPaths.CHART_COLUMN,        16, light);
        chartColumnIconDark     = icons.renderAtHeight(d, SvgPaths.CHART_COLUMN,        16, dark);
        rotateLeftIcon          = icons.renderAtHeight(d, SvgPaths.ROTATE_LEFT,         18, red);
        autoSetupIcon           = icons.renderAtHeight(d, SvgPaths.ARROWS_TO_CIRCLE,    16, light);
        maximizeIcon            = icons.renderAtHeight(d, SvgPaths.ARROWS_FROM_CIRCLE,  16, light);
        windowRestoreIconLight  = icons.renderAtHeight(d, SvgPaths.WINDOW_RESTORE,      16, light);
        windowRestoreIconDark   = icons.renderAtHeight(d, SvgPaths.WINDOW_RESTORE,      16, dark);
    }

}
