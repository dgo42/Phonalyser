package org.edgo.audio.measure.gui.fft;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.apache.commons.lang3.ArrayUtils;
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
import org.edgo.audio.measure.cli.util.FreqRespCalHelper;
import org.edgo.audio.measure.cli.util.FreqRespCalibration;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.FftMagnitudeUnit;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.fft.FftAnalyzer;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.AbstractFreqDomainView;
import org.edgo.audio.measure.gui.common.IconUtils;
import org.edgo.audio.measure.gui.common.SvgPaths;
import org.edgo.audio.measure.gui.generator.FftBinSnap;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.preferences.Preferences;
import org.edgo.audio.measure.gui.sound.SignalBuffer;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;

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
@Log4j2
public final class FftView extends AbstractFreqDomainView {

    // ─── Colours ─────────────────────────────────────────────────────────
    // All FFT palette colours (background, grid, axis, text, crosshair,
    // overlay_bg, left/right btn chan, button frame, reset, spectrum,
    // dim, harmonic_dot, before_cal_dot, freq_resp_response, cal_overlay)
    // live in the AbstractMeasurementView palette — accessed via
    // color(ColorRole.X).  syncFftColors() re-allocates the prefs-driven
    // entries via setColor() (which short-circuits when the RGB hasn't
    // changed, so there's no per-paint allocation cost).

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

    private FftAnalyzerWorker worker = null;

    private long startRender;
    /** Subscription handler held as a field so the same instance is
     *  passed to both {@code subscribe} and {@code unsubscribe} — the
     *  bus removes by reference equality.  The {@code slot} payload is
     *  already a deep-copied snapshot (produced on the worker thread
     *  inside {@code publishResult}); we just store the reference and
     *  redraw — paint code reads {@link #lastResult} directly. */
    private final Consumer<FftAnalyzer.Result> onResultReady = slot -> {
        if (isDisposed()) return;
        if (worker != null) worker.uiGotResult();
        startRender = System.nanoTime();
        lastResult = slot;
        redraw();
        update();
    };

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

    // Static-layer paint cache (traceBuffer Image + fingerprint) now
    // lives in AbstractFreqDomainView.  Mouse-move / crosshair redraws
    // hit the blit path via paintCachedStatic and never re-walk the
    // 64 k spectrum bins.

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
        // Pass prefs-driven BACKGROUND + SPECTRUM through the override
        // map so the base allocates the right colour once; everything
        // else uses the light-theme defaults from AbstractMeasurementView.
        // DIM (#DCDCDC) is FFT-only — supplied here rather than the base
        // because no other view dims spectrum regions.
        super(parent, SWT.DOUBLE_BUFFERED, Map.of(
                ColorRole.BACKGROUND, Preferences.instance().getFftChartBackgroundColor(),
                ColorRole.SPECTRUM,   Preferences.instance().getFftLineColor(),
                ColorRole.DIM,        0xDCDCDC));
        // Chapter-level help anchor: Ctrl+F1 anywhere on the spectrum
        // canvas opens fft.html.  The header buttons / THD table /
        // crosshair aren't separate widgets (everything is custom-
        // painted on this Canvas), so deep widget-level anchors
        // bottom-out at the chapter and the user navigates within.
        setData("helpAnchor", "fft.html");
        Display d = getDisplay();
        worker = new FftAnalyzerWorker(d);
        // Paint-on-result: subscribe to FFT_RESULT_AVAILABLE (published
        // by the worker on the UI thread after each analysis) and force
        // the paint to fire synchronously rather than letting SWT
        // coalesce multiple per-tick redraws into one delayed paint.
        // At typical analysis cadences (1–5 ticks per second) the
        // per-tick paint cost is well under the hop interval, so
        // blocking the UI thread for this paint is negligible.
        MessageBus.instance().subscribe(Events.FFT_RESULT_AVAILABLE, onResultReady);
        // Pick up FFT-only prefs (harmonic dot, freq-resp response,
        // before-cal dot, cal overlay) that aren't part of the base
        // palette — kept as view-local fields below.
        syncFftColors();

        // Register the header-row buttons as Hotspots with the shared base.
        // The base owns the rect-by-reference list + lookup helper; the
        // mouseDown handler below dispatches via hotspotAt(...) instead of
        // running through seven explicit if/else branches.
        registerHotspot(leftChanButtonBounds, () -> {
            Preferences p = Preferences.instance();
            p.setFftChannel(Channel.L); p.save(); redraw();
        });
        registerHotspot(rightChanButtonBounds, () -> {
            Preferences p = Preferences.instance();
            p.setFftChannel(Channel.R); p.save(); redraw();
        });
        registerHotspot(autoSetupButtonBounds, this::autoSetup);
        registerHotspot(maximizeButtonBounds, this::maximize);
        registerHotspot(distortionButtonBounds, () -> {
            Preferences p = Preferences.instance();
            p.setFftDistortionTableVisible(!p.isFftDistortionTableVisible());
            p.save();
            syncExternalShell();
            redraw();
        });
        registerHotspot(externalButtonBounds, () -> setTableExtracted(!tableExtracted));
        registerHotspot(resetButtonBounds, () -> { resetStatistics(); redraw(); });

        setBackground(color(ColorRole.BACKGROUND));
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
        magUnitCombo.setItems(FftMagnitudeUnit.labels());
        magUnitCombo.setToolTipText(I18n.t("fft.magUnit.tooltip"));
        magUnitCombo.setData("helpAnchor", "fft.html#fft-mag-unit");
        int magIdx = ArrayUtils.indexOf(FftMagnitudeUnit.names(), Preferences.instance().getFftMagUnit());
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
        FftMagnitudeUnit newUnit = FftMagnitudeUnit.fromName(FftMagnitudeUnit.names()[i]);
        double fs = prefs.getAdcFsVoltageRms();
        if (!(fs > 0)) fs = 1.0;
        double fsDbv = 20 * Math.log10(fs);
        double[] conv = FftFormat.convertMagRange(prefs.getFftMagTop(), prefs.getFftMagBottom(),
                oldUnit, newUnit, fsDbv);
        prefs.setFftMagTop(conv[0]);
        prefs.setFftMagBottom(conv[1]);
        prefs.setFftMagUnit(FftMagnitudeUnit.names()[i]);
        prefs.save();
        fireRangeChanged();
        redraw();
    }

    /** Re-selects the magnitude-unit combo from the current Preferences
     *  value.  Called by the pane's syncWidgetsFromPrefs after a preset
     *  load or screenshot-pane wire-up. */
    public void refreshFromPrefs() {
        if (magUnitCombo == null || magUnitCombo.isDisposed()) return;
        int i = ArrayUtils.indexOf(FftMagnitudeUnit.names(), Preferences.instance().getFftMagUnit());
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
                // Tick-based count (consistent across cycled and forever
                // modes — both increment by exactly one per analysis).
                // For finite N: capped at N once the moving window is
                // full.  For "forever": climbs without limit.
                // The cross-tick accumulator math (forever mode) makes
                // each displayed tick contribute additional SNR depth —
                // the actual frames-averaged depth is roughly 2× the
                // displayed N because each forever tick contributes
                // ~2 FFT frames to the running sum.
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

    private void disposeResources() {
        MessageBus.instance().unsubscribe(Events.FFT_RESULT_AVAILABLE, onResultReady);
        worker.stop();
        disposePalette();
        if (monoFont       != null) monoFont.dispose();
        if (monoBoldFont   != null) monoBoldFont.dispose();
        if (chanButtonFont != null) chanButtonFont.dispose();
        // Header icons are cached and owned by IconUtils — disposed
        // when the main shell tears down, not here.
        if (externalShell != null && !externalShell.isDisposed()) externalShell.dispose();
        disposeTraceBuffer();
    }

    /** (Re-)allocates the user-configurable FFT colours from
     *  {@link Preferences} when the packed-RGB values change.  Cheap on
     *  repeat calls when nothing changed (an integer compare per slot).
     *  Called from the constructor and from each paint so live edits in
     *  the Preferences dialog take effect on the next redraw. */
    private void syncFftColors() {
        Preferences prefs = Preferences.instance();
        setColor(ColorRole.BACKGROUND,         prefs.getFftChartBackgroundColor());
        setColor(ColorRole.SPECTRUM,           prefs.getFftLineColor());
        setColor(ColorRole.HARMONIC_DOT,       prefs.getFftHarmonicDotColor());
        setColor(ColorRole.BEFORE_CAL_DOT,     prefs.getFftBeforeCalDotColor());
        setColor(ColorRole.FREQ_RESP_RESPONSE, prefs.getFftFreqRespColor());
        setColor(ColorRole.CAL_OVERLAY,        prefs.getFftCalOverlayColor());
        // Keep the SWT Canvas background in sync with the palette entry —
        // base setColor() is a no-op when the RGB hasn't changed, so this
        // is cheap to repeat per paint.
        setBackground(color(ColorRole.BACKGROUND));
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

    /** Latest published analysis result — a deep copy taken on the
     *  worker thread inside {@code publishResult} before it crossed
     *  the bus.  {@code null} before the first successful tick.
     *  Mutating this snapshot is safe: the worker no longer holds a
     *  reference, so paint code and the pane's Save-CSV / screenshot
     *  paths can read or even mutate it without coordinating with the
     *  analyser.  Lombok provides public {@code getLastResult} /
     *  {@code setLastResult} accessors — used by the offscreen
     *  screenshot pane to inject a snapshot without running the
     *  analysis loop. */
    @Getter @Setter
    private FftAnalyzer.Result lastResult;

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
        if (lastResult == null) return;
        Preferences prefs = Preferences.instance();
        FftMagnitudeUnit unit = FftMagnitudeUnit.fromName(prefs.getFftMagUnit());

        // ── Frequency: one decade either side of the fundamental
        // (×0.1 .. ×10), clamped to bin size / Nyquist.
        double f0 = lastResult.fundamentalHzRefined;
        if (Double.isFinite(f0) && f0 > 0) {
            double lo = Math.max(currentBinSize(), f0 / 10.0);
            double hi = Math.min(lastResult.sampleRate / 2.0,  f0 * 10.0);
            if (hi > lo) {
                prefs.setFftFreqMinHz(lo);
                prefs.setFftFreqMaxHz(hi);
            }
        }

        // ── Magnitude: top = fundamental + 10 dB, bottom = noise
        // floor − 20 dB.  Translated to the active unit so the user's
        // view shows the spectrum sitting comfortably between the
        // strongest line and ~20 dB below the noise grass.
        if (Double.isFinite(lastResult.fundamentalDbFs) && Double.isFinite(lastResult.avgNoiseFloorDbFs)) {
            double topDbFs = lastResult.fundamentalDbFs   + 10;
            double botDbFs = lastResult.avgNoiseFloorDbFs - 20;
            double calRefDbV = Double.isNaN(lastResult.fundRefDbV) ? 0
                    : (lastResult.fundRefDbV - lastResult.fundamentalDbFs);
            // The fundamental is rendered at the manual-override dBV
            // when the user supplied one, so the auto-setup ceiling
            // tracks that value (+10 dB headroom) instead of the
            // calibrated peak; the noise floor stays on the calibrated
            // anchor since non-fundamental bins aren't affected.
            double topRefDbV = (Double.isFinite(lastResult.fundamentalTrueDbV)
                    && Double.isFinite(lastResult.fundamentalDbFs))
                    ? lastResult.fundamentalTrueDbV - lastResult.fundamentalDbFs
                    : calRefDbV;
            double top = unit.convertFromDbFs(topDbFs, topRefDbV,  lastResult.freqResolution);
            double bot = unit.convertFromDbFs(botDbFs, calRefDbV, lastResult.freqResolution);
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
        Preferences prefs = Preferences.instance();
        FftMagnitudeUnit unit = FftMagnitudeUnit.fromName(prefs.getFftMagUnit());

        // Frequency span: 0 (clamped to bin size) → Nyquist.  Fall back
        // to currentNyquist() when no result is available yet — that
        // helper itself defaults to 192 kHz / 2 = 96 kHz half-rate.
        double nyquist = (lastResult != null) ? lastResult.sampleRate / 2.0 : currentNyquist();
        double lo = Math.max(currentBinSize(), 0.0);
        if (nyquist > lo) {
            prefs.setFftFreqMinHz(lo);
            prefs.setFftFreqMaxHz(nyquist);
        }

        // Fixed +20 dBFS ceiling, noise-floor − 30 dB floor (when
        // present; otherwise default to −150 dBFS bottom to give a
        // sensible empty-chart layout).
        double topDbFs = 20;
        double botDbFs = (lastResult != null && Double.isFinite(lastResult.avgNoiseFloorDbFs))
                ? lastResult.avgNoiseFloorDbFs - 30
                : -150;
        double calRefDbV = 0;
        double binBw = 1.0;
        if (lastResult != null) {
            calRefDbV = Double.isNaN(lastResult.fundRefDbV) ? 0
                    : (lastResult.fundRefDbV - lastResult.fundamentalDbFs);
            binBw = lastResult.freqResolution;
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
        return (lastResult == null) ? Double.NaN : lastResult.fundamentalHzRefined;
    }

    /** Latest fundamental Vrms (linear, calibrated by ADC fs voltage), or
     *  {@code null} when no analysis. */
    public Double getLastVrms() {
        if (lastResult == null || Double.isNaN(lastResult.fundamentalLinear)) return null;
        double fs = Preferences.instance().getAdcFsVoltageRms();
        return (fs > 0) ? lastResult.fundamentalLinear * fs : null;
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


        // ---- Static-layer buffer: blit cached image when nothing
        // affecting the trace / grid / axes has changed.  Cache
        // lifecycle (rebuild / blit) lives in AbstractFreqDomainView;
        // we just hand it a fingerprint and the static-layer painter.
        final double fFreqMin = freqMin;
        long fp = computeTraceFingerprint(lastResult, area, unit, fFreqMin, freqMax,
                magTop, magBot, logFreq);
        paintCachedStatic(gc, area, fp, bgc -> {
            bgc.setBackground(color(ColorRole.BACKGROUND));
            bgc.fillRectangle(0, 0, area.width, area.height);
            bgc.setAntialias(SWT.ON);
            bgc.setTextAntialias(SWT.ON);
            boolean magLog = FftAxisTicks.isMagLog(unit);
            AxisSpec xSpec = (logFreq
                    ? AxisSpec.log(fFreqMin, freqMax)
                    : AxisSpec.linearNice(fFreqMin, freqMax, 10, 0))
                    .withFormat(logFreq ? LabelFormat.FREQ : LabelFormat.FREQ_INT);
            AxisSpec ySpec = (magLog
                    ? AxisSpec.log(magBot, magTop)
                    : AxisSpec.linearNice(magBot, magTop, 10, 5.0))
                    .withFormat(magLog ? LabelFormat.VOLTS_SI : LabelFormat.DB)
                    .withUnit(unit.getLabel());
            drawGrid(bgc, plot, xSpec, ySpec, null,
                     color(ColorRole.GRID), color(ColorRole.AXIS), color(ColorRole.TEXT), monoFont,
                     4, 2, null);
            drawDistortionBands(bgc, plot, fFreqMin, freqMax, logFreq);
            if (lastResult != null) {
                drawSpectrum(bgc, plot, lastResult, unit, fFreqMin, freqMax, magTop, magBot, logFreq);
                drawCalOverlay(bgc, plot, lastResult, unit, fFreqMin, freqMax,
                        magTop, magBot, logFreq);
                drawHarmonicDots(bgc, plot, lastResult, unit, fFreqMin, freqMax,
                        magTop, magBot, logFreq);
            }
        });

        // ---- Dynamic overlay (drawn live on top of the cached image)
        gc.setAntialias(SWT.ON);
        gc.setTextAntialias(SWT.ON);
        drawHeaderButtons(gc, lastResult != null);
        if (lastResult != null && prefs.isFftDistortionTableVisible() && !tableExtracted) {
            drawDistortionTable(gc, lastResult, unit);
        }
        // Crosshair: only when inside the plot area AND not over any
        // header button (so the crosshair / floating readout don't
        // visually fight with the buttons that occupy the top-left).
        if (crossX >= plot.x && crossX < plot.x + plot.width
                && crossY >= plot.y && crossY < plot.y + plot.height
                && !pointerOverButton(crossX, crossY)) {
            drawCrosshair(gc, plot, lastResult, unit, freqMin, freqMax, magTop, magBot, logFreq);
        }
        log.warn("FFT result rendered in {} ms", (double)(System.nanoTime() - startRender) / 1_000_000);
        startRender = System.nanoTime();
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
        gc.setBackground(color(ColorRole.DIM));
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
        double calRefDbV = Double.isNaN(r.fundRefDbV) ? 0
                : (r.fundRefDbV - r.fundamentalDbFs);
        // Only the fundamental dot lifts to the manual-override dBV;
        // harmonics stay on the calibrated anchor.
        double fundRefDbV = (Double.isFinite(r.fundamentalTrueDbV)
                && Double.isFinite(r.fundamentalDbFs))
                ? r.fundamentalTrueDbV - r.fundamentalDbFs
                : calRefDbV;
        double binBw = r.freqResolution;

        // "Before-cal" dots — painted FIRST so the corrected (red)
        // dots sit on top.  Pulled from the SAME r snapshot used for
        // the post-cal dots so both share one tick's harmonicHz
        // values — the snapshot was deep-copied at publish time, so
        // BLUE and RED can never end up from different ticks.
        double[][] before = (r != null) ? r.preCorrectionPeaks : null;
        if (before != null && before.length == 2
                && before[0] != null && before[1] != null) {
            gc.setBackground(color(ColorRole.BEFORE_CAL_DOT));
            int n = Math.min(before[0].length, before[1].length);
            for (int i = 0; i < n; i++) {
                double anchor = (i == 0) ? fundRefDbV : calRefDbV;
                plotDotAt(gc, plot, before[0][i], before[1][i],
                        unit, freqMin, freqMax, magTop, magBot, logFreq, anchor, binBw);
            }
        }

        // Each plotDotAt gates on the dot's magnitude vs the visible
        // range and skips drawing when it falls outside — that hides
        // dots cleanly without snapping them to the chart edges.
        gc.setBackground(color(ColorRole.HARMONIC_DOT));
        plotDotAt(gc, plot, r.fundamentalHzRefined, r.fundamentalDbFs,
                unit, freqMin, freqMax, magTop, magBot, logFreq, fundRefDbV, binBw);
        if (r.harmonicHz != null && r.harmonicDbFs != null) {
            int n = Math.min(r.harmonicHz.length, r.harmonicDbFs.length);
            for (int i = 0; i < n; i++) {
                plotDotAt(gc, plot, r.harmonicHz[i], r.harmonicDbFs[i],
                        unit, freqMin, freqMax, magTop, magBot, logFreq, calRefDbV, binBw);
            }
        }

        // Hn labels on top of every (corrected) dot — fundamental gets
        // the full "H1 ⟨freq⟩" pair, harmonics get just "Hn".  Only
        // the harmonics within the calc-max-harmonic count are
        // labelled (matches the THD-tab's calcMaxHarmonic pref).
        gc.setForeground(color(ColorRole.HARMONIC_DOT));
        drawHarmonicLabel(gc, plot, "H1 " + formatFrequency(r.fundamentalHzRefined),
                r.fundamentalHzRefined, r.fundamentalDbFs,
                unit, freqMin, freqMax, magTop, magBot, logFreq, fundRefDbV, binBw);
        if (r.harmonicHz != null && r.harmonicDbFs != null) {
            int n = Math.min(r.harmonicHz.length, r.harmonicDbFs.length);
            int calcMax = Preferences.instance().getFftCalcMaxHarmonic();
            for (int i = 0; i < n && (i + 2) <= calcMax; i++) {
                drawHarmonicLabel(gc, plot, "H" + (i + 2),
                        r.harmonicHz[i], r.harmonicDbFs[i],
                        unit, freqMin, freqMax, magTop, magBot, logFreq, calRefDbV, binBw);
            }
        }
    }

    /** Paints a small label centred horizontally on the dot's column
     *  and just above the dot itself.  No-op when the dot would fall
     *  outside the visible plot. */
    private void drawHarmonicLabel(GC gc, Rectangle plot, String text,
                                   double hz, double dbFs, FftMagnitudeUnit unit,
                                   double freqMin, double freqMax,
                                   double magTop, double magBot,
                                   boolean logFreq, double refDbV, double binBw) {
        if (!Double.isFinite(hz) || hz < freqMin || hz > freqMax) return;
        if (!Double.isFinite(dbFs)) return;
        double v = unit.convertFromDbFs(dbFs, refDbV, binBw);
        double t = FftFormat.magToYFraction(v, magTop, magBot, unit);
        if (t < 0 || t > 1) return;
        int x = freqToX(hz, plot, freqMin, freqMax, logFreq);
        int y = magToY(v, plot, magTop, magBot, unit);
        Point ext = gc.textExtent(text);
        int lx = Math.max(plot.x, Math.min(plot.x + plot.width - ext.x, x - ext.x / 2));
        int ly = Math.max(plot.y, y - ext.y - 4);
        gc.drawText(text, lx, ly, true);
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
        double t = FftFormat.magToYFraction(v, magTop, magBot, unit);
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
        // completedAnalyses ticks once per published result, so adding
        // it here guarantees a fresh fingerprint every analysis.  Using
        // identityHashCode(r) ALONE turned out not to be reliable in
        // long runs — under sustained GC churn the hash of consecutive
        // Result objects could repeat for stretches lasting seconds-
        // to-minutes, causing the static trace cache to blit a stale
        // image even though the worker was producing fresh results
        // (visible as a frozen chart with the averages counter / fill-%
        // still ticking).
        long h = (r == null) ? 0 : System.identityHashCode(r);
        h = 31 * h + worker.getCompletedAnalyses();
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
        h = 31 * h + prefs.getFftBeforeCalDotColor();
        h = 31 * h + prefs.getFftFreqRespColor();
        h = 31 * h + prefs.getFftCalOverlayColor();
        // Cal entry list contents — adding / removing a row would
        // otherwise leave the overlay curve stale until the next FFT
        // result swaps the result reference.
        for (FftCalibrationStore.Entry e : FftCalibrationStore.instance().getEntries()) {
            h = 31 * h + System.identityHashCode(e.getCalibration());
        }
        return h;
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
        gc.setForeground(color(ColorRole.SPECTRUM));
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

        // Column-bucketed polyline rendering — see ColumnBucketPainter
        // in the shared base.  FFT also tracks one bin JUST outside the
        // visible range on each side so the polyline reaches the chart
        // edges; the helper exposes setLeftAnchor / setRightAnchor for
        // exactly that.
        ColumnBucketPainter painter = new ColumnBucketPainter(plot);
        int W = plot.width;
        for (int k = kPrev; k <= kNext; k++) {
            double f = k * binBw;
            int xAbs = freqToX(f, plot, freqMin, freqMax, logFreq);
            int x = xAbs - plot.x;
            double binRefDbV = (k == fundBin && Double.isFinite(fundRefDbVOverride))
                    ? fundRefDbVOverride
                    : refDbV;
            double v = unit.convertFromDbFs(r.amplitudeDbFs[k], binRefDbV, binBw);
            int y = magToY(v, plot, magTop, magBot, unit);
            if (x < 0)         painter.setLeftAnchor(xAbs, y);
            else if (x >= W)   painter.setRightAnchor(xAbs, y);
            else               painter.add(xAbs, y);
        }
        painter.drawTo(gc);
    }

    /** Paints the cascade of every loaded FFT calibration as an inverted
     *  overlay curve parallel to the spectrum.  Anchored at H2 so the
     *  user can read it as "this is how much the calibration is lifting
     *  or cutting each band relative to the second harmonic".  No-op
     *  when no calibration is loaded or when H2 wasn't detected.
     *
     *  <p>Combined magnitude in dB is the per-frequency sum across all
     *  loaded cals (cascade); the curve plots {@code -sumDb + offset}
     *  where {@code offset = h2DbFs + sumDb(h2Freq)}. */
    private void drawCalOverlay(GC gc, Rectangle plot, FftAnalyzer.Result r,
                                FftMagnitudeUnit unit,
                                double freqMin, double freqMax,
                                double magTop, double magBot,
                                boolean logFreq) {
        List<FftCalibrationStore.Entry> entries =
                FftCalibrationStore.instance().getEntries();
        if (entries.isEmpty()) return;
        if (r.harmonicCount == 0 || r.harmonicBins == null
                || r.harmonicBins.length == 0 || r.harmonicBins[0] <= 0) return;
        double h2Freq = r.harmonicHz[0];
        double h2DbFs = r.harmonicDbFs[0];
        if (!(h2Freq > 0.0) || !Double.isFinite(h2DbFs)) return;

        // Match the cal channel to whichever channel the FFT is
        // currently analysing — using .left() unconditionally would
        // mis-compensate the signal when the user picks the right
        // channel (their L/R cal curves are not identical).
        boolean wantLeft = Preferences.instance().getFftChannel() == Channel.L;
        double sumDbAtH2 = 0.0;
        for (FftCalibrationStore.Entry e : entries) {
            FreqRespCalibration cal = wantLeft
                    ? e.getCalibration().left()
                    : e.getCalibration().right();
            double m = FreqRespCalHelper.interpolate(cal, h2Freq)[0];
            sumDbAtH2 += (m > 0.0) ? 20.0 * Math.log10(m) : -300.0;
        }
        double offset = h2DbFs + sumDbAtH2;

        FreqRespCalibration first = wantLeft
                ? entries.get(0).getCalibration().left()
                : entries.get(0).getCalibration().right();
        double[] freqs = first.freqs;
        double calRefDbV = Double.isNaN(r.fundRefDbV) ? 0
                : (r.fundRefDbV - r.fundamentalDbFs);
        double binBw = r.freqResolution;

        gc.setForeground(color(ColorRole.CAL_OVERLAY));
        gc.setLineWidth((int) Math.max(1, Math.round(Preferences.instance().getFftLineWidth())));
        ColumnBucketPainter painter = new ColumnBucketPainter(plot);
        int W = plot.width;
        for (int i = 0; i < freqs.length; i++) {
            double f = freqs[i];
            if (!(f > 0.0)) continue;
            double sumDb = 0.0;
            for (FftCalibrationStore.Entry e : entries) {
                FreqRespCalibration cal = wantLeft
                        ? e.getCalibration().left()
                        : e.getCalibration().right();
                double m = (cal == first)
                        ? cal.magLin[i]
                        : FreqRespCalHelper.interpolate(cal, f)[0];
                sumDb += (m > 0.0) ? 20.0 * Math.log10(m) : -300.0;
            }
            double dbFs = -sumDb + offset;
            double v = unit.convertFromDbFs(dbFs, calRefDbV, binBw);
            int xAbs = freqToX(f, plot, freqMin, freqMax, logFreq);
            int x = xAbs - plot.x;
            int y = magToY(v, plot, magTop, magBot, unit);
            if (x < 0)         painter.setLeftAnchor(xAbs, y);
            else if (x >= W)   painter.setRightAnchor(xAbs, y);
            else               painter.add(xAbs, y);
        }
        painter.drawTo(gc);
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
        drawChannelButton(gc, x, y, BTN_W, BTN_H, "L", isLeft, color(ColorRole.LEFT_BTN_CHAN));
        setBounds(leftChanButtonBounds, x, y, BTN_W, BTN_H);
        x += BTN_W + BTN_GAP_SMALL;
        drawChannelButton(gc, x, y, BTN_W, BTN_H, "R", isRight, color(ColorRole.RIGHT_BTN_CHAN));
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
     *  they don't visually fight with the button icons.  Delegates to
     *  the shared {@link #hotspotAt} so any new button registered in
     *  the constructor is picked up automatically. */
    private boolean pointerOverButton(int px, int py) {
        return hotspotAt(px, py) != null;
    }

    private void drawChannelButton(GC gc, int x, int y, int w, int h,
                                   String label, boolean selected, Color tint) {
        if (selected) {
            gc.setBackground(tint);
            gc.fillRoundRectangle(x, y, w, h, 4, 4);
            gc.setForeground(color(ColorRole.TEXT));
        } else {
            gc.setForeground(color(ColorRole.BUTTON_FRAME));
            gc.drawRoundRectangle(x, y, w, h, 4, 4);
            gc.setForeground(color(ColorRole.TEXT));
        }
        Point ext = gc.textExtent(label);
        gc.drawText(label, x + (w - ext.x) / 2, y + (h - ext.y) / 2, true);
    }

    private void drawIconToggleButton(GC gc, int x, int y, int w, int h,
                                      Image lightIcon, Image darkIcon, boolean on) {
        if (on) {
            gc.setBackground(color(ColorRole.BUTTON_FRAME));
            gc.fillRoundRectangle(x, y, w, h, 4, 4);
            drawCenteredIcon(gc, (darkIcon != null) ? darkIcon : lightIcon, x, y, w, h);
        } else {
            gc.setForeground(color(ColorRole.BUTTON_FRAME));
            gc.drawRoundRectangle(x, y, w, h, 4, 4);
            drawCenteredIcon(gc, lightIcon, x, y, w, h);
        }
    }

    private void drawIconPushButton(GC gc, int x, int y, int w, int h, Image icon) {
        drawCenteredIcon(gc, icon, x, y, w, h);
    }

    private void setBounds(Rectangle r, int x, int y, int w, int h) {
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
        gc.setForeground(color(ColorRole.TEXT));
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
                double expected = FftBinSnap.snapIfEnabled(
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
        if (mValR < 0) gc.setForeground(color(ColorRole.TEXT));
    }

    /** Two key/value pairs at independent key-column widths.  The
     *  left value is drawn at {@code x + lKeyW}, the right key at
     *  {@code rightColX}, and the right value at
     *  {@code rightColX + rKeyW}.  An empty right key skips the
     *  right column. */
    private void drawKv(GC gc, int x, int y, int lKeyW,
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
    private String formatSpan(FftAnalyzer.Result r) {
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
    private void drawCentred(GC gc, String text, int centreX, int y) {
        Point ext = gc.textExtent(text);
        gc.drawText(text, centreX - ext.x / 2, y, true);
    }

    private String fmtDb(double v) {
        return Double.isFinite(v) ? String.format("%7.2f dBV", v) : "—";
    }

    private double noiseDb(FftAnalyzer.Result r) {
        if (r.noisePower <= 0 || !Double.isFinite(r.fundRefDbV)) return Double.NaN;
        return 10 * Math.log10(r.noisePower) + (r.fundRefDbV - r.fundamentalDbFs);
    }

    private double thdNPct(FftAnalyzer.Result r) {
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
        gc.setForeground(color(ColorRole.CROSSHAIR));
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
                sb.append('\n').append("|m| = ").append(formatMagnitudeWithUnit(v, unit));
            }
        }
        drawReadoutBox(gc, sb.toString(), crossX + 12, crossY + 12, plot,
                monoFont, color(ColorRole.OVERLAY_BG), color(ColorRole.BUTTON_FRAME), color(ColorRole.TEXT));
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
        c.setBackground(color(ColorRole.BACKGROUND));
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
        if (lastResult == null) return;
        GC gc = ev.gc;
        gc.setAntialias(SWT.ON);
        gc.setTextAntialias(SWT.ON);
        ensureFonts();
        // Draw table with a small inset from the top-left so the keys
        // don't touch the window border.  No reset button here — the
        // button stays in the main FFT view.
        FftMagnitudeUnit unit = FftMagnitudeUnit.fromName(Preferences.instance().getFftMagUnit());
        drawDistortionTable(gc, lastResult, unit, EXT_LEFT_PAD, EXT_LEFT_PAD, true);
    }

    // =========================================================================
    // Mouse handlers
    // =========================================================================

    private void onMouseDown(MouseEvent ev) {
        if (ev.button != 1) return;
        // All header-row buttons (L/R, Auto-Setup, Maximize, Distortion,
        // External, Reset) are registered as Hotspots with the shared
        // base — dispatch them through the registry.
        Hotspot hot = hotspotAt(ev.x, ev.y);
        if (hot != null) hot.onClick.run();
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
        if (FftAxisTicks.isMagLog(unit)) {
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
        if (FftAxisTicks.isMagLog(unit)) {
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
    private double[] magLimits(FftMagnitudeUnit unit, double adcFsVrms) {
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
        if (lastResult != null && lastResult.fftSize > 0) return (double) lastResult.sampleRate / lastResult.fftSize;
        int sr = 384_000;
        int fftLen = Math.max(8, Preferences.instance().getFftLength());
        return (double) sr / fftLen;
    }

    /** Nyquist upper bound on the visible freq range (Hz). */
    private double currentNyquist() {
        if (lastResult != null) return lastResult.sampleRate / 2.0;
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

    // freqToX / xToFreq live on the shared AbstractFreqDomainView base
    // (same body, same idiom); the magToY below stays per-view because
    // it routes through FftFormat.magToYFraction for unit-aware (V /
    // V/sqrt(Hz) / dBFS / dBV) scaling that FreqResp doesn't need.

    private int magToY(double v, Rectangle plot, double top, double bot, FftMagnitudeUnit unit) {
        double t = FftFormat.magToYFraction(v, top, bot, unit);
        if (t < 0) t = 0;
        if (t > 1) t = 1;
        return plot.y + (int) Math.round(t * plot.height);
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
