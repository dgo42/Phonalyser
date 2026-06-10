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

package org.edgo.audio.measure.gui.fft;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntToDoubleFunction;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FormAttachment;
import org.eclipse.swt.layout.FormData;
import org.eclipse.swt.layout.FormLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.edgo.audio.measure.dsp.FreqRespCalHelper;
import org.edgo.audio.measure.dsp.FreqRespCalibration;
import org.edgo.audio.measure.common.Constants;
import org.edgo.audio.measure.common.FreqRespCorrectionStore;
import org.edgo.audio.measure.dsp.MainsCombFilter;
import org.edgo.audio.measure.dsp.SpectralDiscontinuityDetector;
import org.edgo.audio.measure.dsp.ToneLobeLift;
import org.edgo.audio.measure.enums.AlignGenerator;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.MagnitudeUnit;
import org.edgo.audio.measure.enums.GenChangeCause;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.fft.FftAnalyzer;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.gui.bind.Bindings;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.AbstractFreqDomainView;
import org.edgo.audio.measure.gui.common.DebugSwitches;
import org.edgo.audio.measure.gui.common.SvgPaths;
import org.edgo.audio.measure.gui.generator.FftBinSnap;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.gui.widgets.BlinkBanner;
import org.edgo.audio.measure.gui.widgets.ToolButton;
import org.edgo.audio.measure.gui.widgets.ToolWindow;
import org.edgo.audio.measure.gui.widgets.Toolbar;
import org.edgo.audio.measure.gui.widgets.TransparentComposite;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * Live FFT canvas.  Paints a black-on-white spectrum trace (one channel
 * at a time), a THD info table overlay, a crosshair cursor with a freq /
 * magnitude readout, and a row of header buttons modelled on the
 * oscilloscope view.
 *
 * <p>Owns the analysis pipeline itself: a daemon worker thread that acquires
 * its own shared capture, runs
 * {@link FftAnalyzer#analyze}, and publishes the latest
 * {@link FftResult} for paint to read.  The owning
 * {@link FftPane} drives the lifecycle ({@link #start} / {@link #stop} /
 * {@link #resetStatistics}) and feeds settings via {@code Preferences}.
 * View → pane communication is purely via {@link MessageBus} broadcasts
 * ({@link Events#FFT_RANGE_CHANGED}, {@link Events#FFT_RECORDING_AUTO_STOPPED}).
 */
@Log4j2
public final class FftView extends AbstractFreqDomainView {

    /** How long the rejection warning keeps blinking after the last hit. */
    private static final long REJECTION_WARN_NANOS = 10_000_000_000L;

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
    /** THD table overlay y offset from top of canvas. */
    private static final int TABLE_TOP_Y = 5 + BTN_H + 6;

    /** Left padding (px) for the THD table inside the external window. */
    private static final int EXT_LEFT_PAD = 4;

    // ─── Fonts ────────────────────────────────────────────────────────────
    private Font monoFont;
    private Font monoBoldFont;
    private Font chanButtonFont;

    // ─── Top-right overlay widgets ───────────────────────────────────────
    /** Magnitude unit selector (V / V·√Hz / dBV / dBFS). */
    private Combo magUnitCombo;
    /** "NN%" indicator showing the progress of the current FFT frame's
     *  hop (or full-window fill for the first frame). */
    private Label fillPercentLabel;
    /** Count of completed analyses since the last reset / record-start. */
    private Label averagesCountLabel;

    private FftAnalyzerWorker worker = null;

    /** Loaded {@code .frc} corrections divided out of the spectrum and drawn
     *  as the overlay; constructor-injected by {@link FftPane} (IoC) and shared
     *  with the calibration tab so both see the same entries. */
    private final FreqRespCorrectionStore correctionStore;

    /** Stage-3 instrumentation for {@link DebugSwitches#SHOW_FFT_ANALYZE_TIME}:
     *  {@code startRender} is stamped when a fresh result arrives in
     *  {@link #onResultReady} and {@code gotFftResult} arms the end-of-paint
     *  log line, which disarms it again so only the FIRST repaint of each
     *  result is timed — later repaints are pans / zooms, not result
     *  rendering. */
    private long startRender;
    private boolean gotFftResult = false;

    /** Closed-loop integrator that drives the DDS frequency onto the
     *  nearest FFT bin centre.  Active only when snap-to-FFT-bin is on
     *  AND the generator is running AND the worker is running.  Reset
     *  on Record stop and on any user-initiated generator change.
     *
     *  <p>{@link #fll} tracks the SINE / dual-tone-tone-1 correction;
     *  {@link #fll2} tracks the dual-tone tone-2 correction (used only
     *  when the generator form is DUAL_TONE).  Each loop is reset
     *  independently. */
    private FrequencyAligner fll;
    private FrequencyAligner fll2;

    /** Frequency-domain mains rejection for the DISPLAYED frame — the worker
     *  only tracks f0, this divides the comb's cached response out of the shown
     *  spectrum (see {@link MainsCombFilter#applySpectrumCorrection}).  Its OWN
     *  instance (UI-thread-driven, separate from the worker's comb), created
     *  lazily on first use with the result's real sample rate. */
    private MainsCombFilter mainsCorrector;
    /** Shared lobe lift for the manual-fundamental display (same algorithm the
     *  .frc cal uses, so a tone is rescaled identically in both paths). */
    private final ToneLobeLift LOBE = new ToneLobeLift();
    /** Re-derives the measurements (THD / SNR / N / fundamental / harmonics, all
     *  units + %) from the de-hummed spectrum after {@link #mainsCorrector} runs
     *  — the same {@code recomputeStats} the worker uses, so every readout
     *  matches the plot.  Reused so its scratch buffers aren't re-allocated. */
    private final FftAnalyzer mainsStats = new FftAnalyzer();

    /** Sticky table-mode selector — {@code true} draws the IMD table,
     *  {@code false} draws the THD table.  Updated only inside
     *  {@link #onResultReady} (which fires only when the FFT worker is
     *  actually producing results), so the user's last-recorded mode
     *  persists between sessions and across generator form changes
     *  while the analyser is paused.  Initial value matches the saved
     *  generator form so the chosen table appears immediately on first
     *  paint, before the first analysis result arrives. */
    private boolean tableModeIsImd =
            Preferences.instance().getGenSignalForm() == GenSignalForm.DUAL_TONE;

    /** Subscriber for {@link Events#GENERATOR_SIGNAL_CHANGED} — held as
     *  a field so dispose can unsubscribe by reference.  On USER_INPUT
     *  (any user change to the generated signal — form, frequency /
     *  dual-tone frequencies, amplitude / balance, duty) both the locked
     *  alignment and the averaged spectrum are stale (they were measured
     *  for the old signal), so reset the FLL(s) AND wipe the FFT
     *  statistics including the retained lastResult / lastImd readout.
     *  Ignores FLL_TRIM (that's our own trim coming back round the bus). */
    private final Consumer<GenChangeCause> onGenChangeForFll = cause -> {
        if (cause == GenChangeCause.USER_INPUT) {
            resetFrequencyLock();
            lastImd = null;
            resetStatistics();   // accumulator + (while recording) lastResult / lastImd
            syncExternalShell();
            if (!isDisposed()) redraw();
        }
    };
    /** One analyzer instance for the view's lifetime — its internal scratch
     *  buffer (noise-floor quickselect) is only reused this way.  Declared
     *  before {@link #onResultReady}, whose initializer lambda reads it. */
    private final ImdAnalyzer imdAnalyzer = new ImdAnalyzer();

    /** Subscription handler held as a field so the same instance is
     *  passed to both {@code subscribe} and {@code unsubscribe} — the
     *  bus removes by reference equality.  The {@code slot} payload is
     *  already a deep-copied snapshot (produced on the worker thread
     *  inside {@code publishResult}); we just store the reference and
     *  redraw — paint code reads {@link #lastResult} directly. */
    private final Consumer<FftResult> onResultReady = slot -> {
        if (isDisposed()) return;
        if (worker != null) worker.uiGotResult();
        if (isRunning()) {
            // Post-average pipeline (mains → recompute → .frc → dBV), run ONCE
            // here on the DISPLAYED frame (post throttle + coalescing) — before
            // the deep-copy / IMD so both see the finished spectrum + readouts.
            finalizeResult(slot);
            startRender = System.nanoTime();
            gotFftResult = true;
            lastResult = slot.deepCopy();
            syncDataButtons();
            // Frame / phase rejections slow the averaging — raise a sticky
            // (20 s, restarted on each fresh rejection) blinking warning with
            // the full detail in its tooltip.  Otherwise live data clears any
            // stale banner: the loaded-CSV path (persistent) or a warning whose
            // timer has already run out.
            if (slot.rejectedFrames > 0) {
                String detail = String.format(Locale.US, "%.2f", slot.rejectionDetail);
                String tip = I18n.t(slot.rejectionPhaseCoherence
                                ? "fft.warning.rejection.phase"
                                : "fft.warning.rejection.rInvariant",
                        slot.rejectedFrames, slot.rejectionTotalFrames, detail);
                setBanner(I18n.t("fft.warning.rejection"), tip,
                        ColorRole.WARNING_LIT, ColorRole.WARNING_DIM,
                        System.nanoTime() + REJECTION_WARN_NANOS);
            } else {
                clearBannerIfStale();
            }
            // Sticky table mode: re-evaluated on every recorded result
            // and held between recordings.  Tied to the generator form
            // because that's the only signal that lets us pick between
            // THD (single tone) and IMD (two tones) — an external two-
            // tone source through a paused generator stays on whichever
            // mode was last selected the previous time recording ran.
            boolean prevTableModeIsImd = tableModeIsImd;
            Preferences prefs = Preferences.instance();
            tableModeIsImd = prefs.getGenSignalForm() == GenSignalForm.DUAL_TONE;
            lastImd = tableModeIsImd
                    ? imdAnalyzer.analyze(slot,
                            prefs.getGenDualToneFreq1Hz(),
                            prefs.getGenDualToneFreq2Hz(),
                            prefs.getDbvOffsetDb())
                    : null;
            // Mode just flipped (THD ↔ IMD): refresh the extracted window's
            // title + size.  It's set at create time and on form-change, but
            // tableModeIsImd only updates here — one tick later — so without
            // this the title keeps the old mode (e.g. "IMD" while showing THD).
            if (tableModeIsImd != prevTableModeIsImd) syncExternalShell();
            redraw();
            update();
            applyFrequencyLockLoop(slot);
        }
    };

    /** Capture re-sync notification from the worker.  Payload is the i18n
     *  message-key, so one event presents distinct warnings — a ring overrun
     *  ({@code fft.warning.overrun}) vs a signal discontinuity
     *  ({@code fft.warning.discontinuity}).  Raises a PERSISTENT blinking banner
     *  (no fixed timer): the worker emits no result on a re-sync tick and
     *  re-anchors, so the next FFT result is the first fully-reloaded window —
     *  whereupon {@link #onResultReady}'s {@link #clearBannerIfStale()} drops it.
     *  Also cleared on a statistics reset and on Record stop. */
    private final Consumer<String> onCaptureResync = messageKey -> {
        if (isDisposed() || !isRunning() || messageKey == null) return;
        setBanner(I18n.t(messageKey), I18n.t(messageKey + ".tip"),
                ColorRole.WARNING_LIT, ColorRole.WARNING_DIM, 0L);
        redraw();
    };

    // ─── Mouse / crosshair tracking ──────────────────────────────────────
    private int crossX = -1, crossY = -1;

    // ─── Hit-test rectangles for header buttons (refreshed every paint) ──
    private Toolbar    headerBar;       // L/R channel buttons (migrated from canvas-draw to widgets)
    private ToolButton leftBtn;
    private ToolButton rightBtn;
    private ToolButton autoSetupBtn;
    private ToolButton maximizeBtn;
    private ToolButton distortionBtn;
    private ToolButton resetBtn;
    private ToolButton externalBtn;
    private TransparentComposite dataSpacer;      // wide gap before the data trio
    private boolean    dataButtonsShown = true;   // sync gate; the trio starts hidden (no data)
    private boolean    externalShown    = true;   // sync gate for externalBtn (needs the table visible)

    // ─── Single blinking status banner (top-right) ───────────────────────
    // A self-painting, self-blinking BlinkBanner widget (gui.widgets) overlays the
    // plot top-right: the loaded-spectrum path (persistent until live data arrives),
    // the frame-rejection warning (a sticky timer a fresh rejection restarts), or a
    // capture re-sync / overrun warning (persistent until the buffer reloads — the
    // next result — or a reset / stop).  The widget blinks itself — no canvas
    // redraw — so only the expiry lives here.
    private BlinkBanner banner;
    /** Expiry ({@code System.nanoTime}); {@code 0} = persistent (no timer). */
    private long        bannerUntilNanos;

    // ─── External tool window ────────────────────────────────────────────
    private boolean    tableExtracted;
    private ToolWindow distortionWindow;   // extracted THD/IMD table window (when extracted)

    /** Latest published analysis result — a deep copy taken on the
     *  worker thread inside {@code publishResult} before it crossed
     *  the bus.  {@code null} before the first successful tick.
     *  Mutating this snapshot is safe: the worker no longer holds a
     *  reference, so paint code and the pane's Save-CSV / screenshot
     *  paths can read or even mutate it without coordinating with the
     *  analyser.  Lombok provides a public {@code getLastResult}
     *  accessor; the offscreen screenshot pane injects this and the
     *  IMD snapshot via {@link #copySnapshotFrom(FftView)} without
     *  running the analysis loop. */
    @Getter
    private FftResult lastResult;

    /** Latest dual-tone IMD result computed from {@link #lastResult}
     *  whenever the generator form is {@code DUAL_TONE}.  {@code null}
     *  when single-tone — the THD table is drawn in that case
     *  instead.  Recomputed synchronously on the UI thread inside
     *  {@link #onResultReady}; the math (peak-interp + ~24 bin reads)
     *  is microseconds-scale so it doesn't move the paint budget. */
    @Getter
    private ImdResult lastImd;

    // Static-layer paint cache (traceBuffer Image + fingerprint) now
    // lives in AbstractFreqDomainView.  Mouse-move / crosshair redraws
    // hit the blit path via paintCachedStatic and never re-walk the
    // 64 k spectrum bins.

    public FftView(Composite parent, FreqRespCorrectionStore correctionStore) {
        // Pass prefs-driven BACKGROUND + SPECTRUM through the override
        // map so the base allocates the right colour once; everything
        // else uses the light-theme defaults from AbstractMeasurementView.
        // DIM (#DCDCDC) is FFT-only — supplied here rather than the base
        // because no other view dims spectrum regions.
        super(parent, SWT.DOUBLE_BUFFERED, Map.of(
                ColorRole.BACKGROUND, Preferences.instance().getFftChartBackgroundColor(),
                ColorRole.SPECTRUM,   Preferences.instance().getFftLineColor(),
                ColorRole.DIM,        0xDCDCDC,
                // L/R channel buttons share the scope's channel colours (cyan/yellow).
                ColorRole.LEFT_BTN_CHAN,  Preferences.instance().getOscLeftChannelColor(),
                ColorRole.RIGHT_BTN_CHAN, Preferences.instance().getOscRightChannelColor()));
        this.correctionStore = correctionStore;
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
        MessageBus bus = MessageBus.instance();
        bus.subscribe(Events.FFT_RESULT_AVAILABLE, onResultReady);
        bus.subscribe(Events.FFT_CAPTURE_RESYNC, onCaptureResync);
        bus.subscribe(Events.GENERATOR_SIGNAL_CHANGED, onGenChangeForFll);
        // Window-function changes reset the running statistics — the control
        // writes the pref, the view reacts to the pref, neither calls the other.
        // Every settings/THD parameter whose side-effect targets the view is
        // wired the same way: the control binds the pref (two-way), the view
        // subscribes to the pref here, and neither calls the other.
        Preferences viewPrefs = Preferences.instance();
        Bindings.onChange(this, viewPrefs.fftWindowProperty(),           w -> resetStatistics());
        // Switching channel restarts the accumulation so it re-captures the new
        // channel from 0 — the L/R ToolButtons write the pref, the view reacts here.
        Bindings.onChange(this, viewPrefs.fftChannelProperty(),          v -> resetStatistics());
        // Distortion-table toggle: the external button follows the table's
        // visibility, then refresh the extracted shell + repaint.
        Bindings.onChange(this, viewPrefs.fftDistortionTableVisibleProperty(), v -> {
            syncDataButtons();
            syncExternalShell();
            redraw();
        });
        // FFT length re-snaps the generator's tone (pane publishes the bus
        // event) AND invalidates the accumulator — the view restarts averaging.
        Bindings.onChange(this, viewPrefs.fftLengthProperty(),           v -> resetStatistics());
        // Coherent ↔ incoherent changes the accumulator semantics — restart.
        Bindings.onChange(this, viewPrefs.fftCoherentAveragingProperty(), v -> resetStatistics());
        // ADC re-calibration rescales every measured voltage; DAC re-calibration
        // changes the generated (loopback) signal level — either invalidates the
        // accumulated spectrum, so restart averaging on a calibration change.
        Bindings.onChange(this, viewPrefs.adcFsVoltageRmsProperty(),      v -> resetStatistics());
        Bindings.onChange(this, viewPrefs.dacFsVoltageRmsProperty(),      v -> resetStatistics());
        // Selecting an active alignment mode (PID / FLL) resets its loop so each
        // session converges fresh; NONE deliberately resets nothing (the
        // generator stays at the stabilized frequency, the converged correction
        // is retained).  Only a real change reaches here (equals-guarded).
        Bindings.onChange(this, viewPrefs.fftAlignGeneratorProperty(),
                mode -> { if (mode != AlignGenerator.NONE) resetFrequencyLock(); });
        // Log-axis remap, distortion-range corners (THD tile) — pure repaints,
        // no statistics reset.
        Bindings.onChange(this, viewPrefs.fftLogFreqAxisProperty(),      v -> redraw());
        Bindings.onChange(this, viewPrefs.fftDistMinEnabledProperty(),   v -> redraw());
        Bindings.onChange(this, viewPrefs.fftDistMinHzProperty(),        v -> redraw());
        Bindings.onChange(this, viewPrefs.fftDistMaxEnabledProperty(),   v -> redraw());
        Bindings.onChange(this, viewPrefs.fftDistMaxHzProperty(),        v -> redraw());
        Bindings.onChange(this, viewPrefs.fftMagUnitProperty(), v -> {
            if (magUnitCombo == null || magUnitCombo.isDisposed()) return;
            magUnitCombo.select(v.ordinal());
        });

        // Calc-up-to-harmonic drives the external THD window's row count.
        Bindings.onChange(this, viewPrefs.fftCalcMaxHarmonicProperty(),  v -> resizeExternalShellToContent());
        // Pick up FFT-only prefs (harmonic dot, freq-resp response,
        // before-cal dot, cal overlay) that aren't part of the base
        // palette — kept as view-local fields below.
        syncFftColors();

        // L/R channel buttons — migrated to ToolButton widgets in a top-left Toolbar.
        chanButtonFont = new Font(d, "Consolas", 12, SWT.BOLD);
        headerBar = new Toolbar(this, BTN_W, BTN_H);
        FormData hbd = new FormData();
        hbd.top  = new FormAttachment(0, 5);
        hbd.left = new FormAttachment(0, MARGIN_LEFT + 4);
        headerBar.setLayoutData(hbd);
        Channel ch = viewPrefs.getFftChannel();
        leftBtn  = headerBar.chanButton("L", color(ColorRole.TEXT),
                color(ColorRole.BUTTON_FRAME), color(ColorRole.LEFT_BTN_CHAN),
                chanButtonFont, I18n.t("fft.button.left.tooltip"),  ch == Channel.L, "channel");
        rightBtn = headerBar.chanButton("R", color(ColorRole.TEXT),
                color(ColorRole.BUTTON_FRAME), color(ColorRole.RIGHT_BTN_CHAN),
                chanButtonFont, I18n.t("fft.button.right.tooltip"), ch == Channel.R, "channel");
        // L/R channel buttons are ToolButton radios (no Bindings helper covers
        // a ToolButton) — each just writes the channel pref (auto-saved); the
        // accumulation restart lives in the fftChannel subscription above, so it
        // fires whoever changes the channel (a preset load too).
        leftBtn.addListener(SWT.Selection, e -> {
            if (leftBtn.isToggled()) viewPrefs.setFftChannel(Channel.L);
        });
        rightBtn.addListener(SWT.Selection, e -> {
            if (rightBtn.isToggled()) viewPrefs.setFftChannel(Channel.R);
        });
        headerBar.spacer(2);
        autoSetupBtn = headerBar.pushButton(SvgPaths.ARROWS_TO_CIRCLE, 16,
                rgb(ColorRole.TEXT), rgb(ColorRole.BACKGROUND), color(ColorRole.TEXT),
                I18n.t("fft.autosetup.tooltip"));
        autoSetupBtn.addListener(SWT.Selection, e -> autoSetup());
        maximizeBtn = headerBar.pushButton(SvgPaths.ARROWS_FROM_CIRCLE, 16,
                rgb(ColorRole.TEXT), rgb(ColorRole.BACKGROUND), color(ColorRole.TEXT),
                I18n.t("fft.maximize.tooltip"));
        maximizeBtn.addListener(SWT.Selection, e -> maximize());
        // Distortion / Reset / External — widgets past a wide spacer, shown only while
        // there's a result (hidden by syncDataButtons() when lastResult is null).
        dataSpacer = headerBar.spacer(2);
        distortionBtn = headerBar.toggleButton(SvgPaths.CHART_COLUMN, 16,
                rgb(ColorRole.TEXT), rgb(ColorRole.BACKGROUND), color(ColorRole.BUTTON_FRAME),
                I18n.t("fft.distortion.tooltip"), viewPrefs.isFftDistortionTableVisible());
        // ToolButton toggle (no Bindings helper covers a ToolButton) — writes
        // the pref (auto-saved); the visibility side-effects live in the
        // fftDistortionTableVisible subscription above so they fire on any
        // change (a preset load too).
        distortionBtn.addListener(SWT.Selection, e ->
                viewPrefs.setFftDistortionTableVisible(distortionBtn.isToggled()));
        resetBtn = headerBar.pushButton(SvgPaths.ROTATE_LEFT, 18,
                rgb(ColorRole.RESET), rgb(ColorRole.BACKGROUND), color(ColorRole.RESET),
                I18n.t("fft.reset.tooltip"));
        resetBtn.addListener(SWT.Selection, e -> { resetStatistics(); redraw(); });
        externalBtn = headerBar.toggleButton(SvgPaths.WINDOW_RESTORE, 16,
                rgb(ColorRole.TEXT), rgb(ColorRole.BACKGROUND), color(ColorRole.BUTTON_FRAME),
                I18n.t("fft.external.tooltip"), tableExtracted);
        externalBtn.addListener(SWT.Selection, e -> {
            if (externalBtn.isToggled() != tableExtracted) {
                setTableExtracted(externalBtn.isToggled());
            }
        });
        syncDataButtons();

        setBackground(color(ColorRole.BACKGROUND));
        addPaintListener(this::onPaint);
        addMouseMoveListener(this::onMouseMove);
        setCursor(getDisplay().getSystemCursor(SWT.CURSOR_CROSS));   // plot crosshair cursor, set once
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

        // These children sit on top of the Canvas; without an explicit cursor
        // they inherit the Canvas's crosshair cursor, so give them the normal
        // arrow.  getSystemCursor returns a shared cursor that must not be disposed.
        magUnitCombo = new Combo(this, SWT.READ_ONLY);
        magUnitCombo.setItems(magUnitLabels());
        magUnitCombo.setToolTipText(I18n.t("fft.magUnit.tooltip"));
        magUnitCombo.setData("helpAnchor", "fft.html#fft-mag-unit");
        magUnitCombo.setCursor(d.getSystemCursor(SWT.CURSOR_ARROW));
        // Two-way value bind; the unit-change side-effect (convert the visible
        // mag range so it stays on the SAME signal level, then re-align the
        // scrollbars + repaint) lives in the onChange below.  The conversion
        // needs the PREVIOUS unit, which the property listener doesn't carry —
        // so track it in a one-element holder, seeded from the current value
        // and advanced after each conversion.
        Bindings.combo(magUnitCombo, viewPrefs.fftMagUnitProperty(), MagnitudeUnit.values());
        Bindings.onChange(this, viewPrefs.fftMagUnitProperty(), newUnit -> {
            // The magnitude range is stored in canonical dBFS, so switching the unit is
            // a label-only change — the range itself doesn't move.
            fireRangeChanged();
            redraw();
        });

        averagesCountLabel = new Label(this, SWT.NONE);
        averagesCountLabel.setText("0 average(s)");
        averagesCountLabel.setForeground(d.getSystemColor(SWT.COLOR_DARK_GRAY));
        averagesCountLabel.setToolTipText(I18n.t("fft.avgCount.tooltip"));
        averagesCountLabel.setData("helpAnchor", "fft.html#fft-averages-count");
        averagesCountLabel.setCursor(d.getSystemCursor(SWT.CURSOR_ARROW));

        fillPercentLabel = new Label(this, SWT.NONE);
        fillPercentLabel.setText("0%");
        fillPercentLabel.setForeground(d.getSystemColor(SWT.COLOR_DARK_GRAY));
        fillPercentLabel.setToolTipText(I18n.t("fft.fillPercent.tooltip"));
        fillPercentLabel.setData("helpAnchor", "fft.html#fft-fill-percent");
        fillPercentLabel.setCursor(d.getSystemCursor(SWT.CURSOR_ARROW));

        FormData cfd = new FormData();
        cfd.top   = new FormAttachment(0, 4);
        cfd.right = new FormAttachment(100, -2);
        cfd.width = 60;
        magUnitCombo.setLayoutData(cfd);

        FormData acd = new FormData();
        acd.top   = new FormAttachment(0, 6);
        acd.right = new FormAttachment(magUnitCombo, -6);
        acd.width = 110;
        averagesCountLabel.setLayoutData(acd);

        FormData fpd = new FormData();
        fpd.top   = new FormAttachment(0, 6);
        fpd.right = new FormAttachment(averagesCountLabel, -6);
        fpd.width = 36;
        fillPercentLabel.setLayoutData(fpd);

        // Status banner: a transparent self-blinking overlay spanning the plot
        // (top-right), below the control row.  FormData keeps it edge-relative so the
        // FormLayout resizes it with the canvas; the widget re-ellipsises to its
        // width.  FFT has no right-side axis, so the right edge is the canvas edge.
        banner = new BlinkBanner(this);
        banner.setVisible(false);
        FormData bnd = new FormData();
        bnd.top   = new FormAttachment(averagesCountLabel, 4);
        bnd.right = new FormAttachment(100, -6);
        // No left attachment: setBanner pins the width to the text (via
        // BlinkBanner.fitFormDataWidth) so the right-anchored banner spans only
        // the text, not the full plot — a full-width transparent banner covers
        // (and shows its tooltip over) the THD/N+D table and dB axis on the left.
        banner.setLayoutData(bnd);

        startFillPercentTimer();
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
        MessageBus bus = MessageBus.instance();
        bus.unsubscribe(Events.FFT_RESULT_AVAILABLE, onResultReady);
        bus.unsubscribe(Events.FFT_CAPTURE_RESYNC, onCaptureResync);
        bus.unsubscribe(Events.GENERATOR_SIGNAL_CHANGED, onGenChangeForFll);
        worker.stop();
        disposePalette();
        if (monoFont       != null) monoFont.dispose();
        if (monoBoldFont   != null) monoBoldFont.dispose();
        if (chanButtonFont != null) chanButtonFont.dispose();
        // Header icons are cached and owned by IconUtils — disposed
        // when the main shell tears down, not here.
        if (distortionWindow != null) distortionWindow.dispose();
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

    /** Copies the paint-relevant snapshot — last spectrum result, the
     *  dual-tone IMD slot and the THD/IMD table-mode flag — from
     *  {@code source} into this view.  Used by the screenshot renderer
     *  so a passive (worker-less) clone draws exactly the table and
     *  dots the live view shows; without the IMD slot the offscreen
     *  view would fall back to the THD table. */
    public void copySnapshotFrom(FftView source) {
        if (source == null || source == this) return;
        this.lastResult     = source.lastResult;
        this.lastImd        = source.lastImd;
        this.tableModeIsImd = source.tableModeIsImd;
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

    /** Stops the analyser and interrupts the worker.  Idempotent.
     *  Also clears the FLL — the locked clock-drift estimate is only
     *  meaningful while the capture is live; on the next Record start
     *  it ramps up from zero again. */
    public void stop() {
        worker.stop();
        clearBanner();              // a re-sync / overrun warning is moot once stopped
        if (fll != null) fll.reset();
        if (fll2 != null) fll2.reset();
    }

    /** Feeds the latest FFT result into the closed-loop integrator and
     *  publishes the next generator frequency as a trim event.  Gated
     *  on the three prerequisite prefs (snap-to-bin, fund-from-gen,
     *  align-gen-to-freq-diff) AND the generator being actively
     *  emitting; the UI mirrors the first three as a single checkbox
     *  in the FFT settings tab whose enabled state already reflects
     *  the first two prerequisites. */
    private void applyFrequencyLockLoop(FftResult slot) {
        if (slot == null) return;
        Preferences prefs = Preferences.instance();
        if (!prefs.isGenSnapToFftBin())       return;
        if (!prefs.isFftFundFromGenerator())  return;
        AlignGenerator mode = prefs.getFftAlignGenerator();
        if (mode == AlignGenerator.NONE)      return;
        if (!isGeneratorActive())             return;
        // Swap the loop type to match the selected mode (a change resets both loops).
        if (fll == null || fll.getMode() != mode) {
            FrequencyAlignerFactory factory = FrequencyAlignerFactory.instance();
            fll  = factory.create(mode);
            fll2 = factory.create(mode);
        }
        MessageBus bus = MessageBus.instance();
        // Branch on generator form: single-tone runs one loop, dual-tone two
        // independent loops driven by the per-tone detected frequencies in lastImd.
        boolean dualTone = prefs.getGenSignalForm() == GenSignalForm.DUAL_TONE;
        if (dualTone) {
            if (lastImd == null) return;
            double t1 = FftBinSnap.snapIfEnabled(prefs, GenSignalForm.DUAL_TONE,
                    slot.sampleRate, prefs.getGenDualToneFreq1Hz());
            double t2 = FftBinSnap.snapIfEnabled(prefs, GenSignalForm.DUAL_TONE,
                    slot.sampleRate, prefs.getGenDualToneFreq2Hz());
            if (t1 > 0 && Double.isFinite(lastImd.f1Hz)) {
                fll.update(t1, lastImd.f1Hz, slot.samplesAbsStart, slot.writePos, slot.sampleRate, slot.fftSize);
                bus.publish(Events.GENERATOR_FREQ_TRIM,
                        t1 + fll.getCorrection());
            }
            if (t2 > 0 && Double.isFinite(lastImd.f2Hz)) {
                fll2.update(t2, lastImd.f2Hz, slot.samplesAbsStart, slot.writePos, slot.sampleRate, slot.fftSize);
                bus.publish(Events.GENERATOR_FREQ_TRIM_2,
                        t2 + fll2.getCorrection());
            }
        } else {
            if (!Double.isFinite(slot.fundamentalHzRefined)) return;
            double target = FftBinSnap.snapIfEnabled(prefs, GenSignalForm.SINE,
                    slot.sampleRate, prefs.getGenFrequencyHz());
            if (!(target > 0)) return;
            fll.update(target, slot.fundamentalHzRefined, slot.samplesAbsStart, slot.writePos, slot.sampleRate, slot.fftSize);
            bus.publish(Events.GENERATOR_FREQ_TRIM,
                    target + fll.getCorrection());
        }
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
     *  Safe to call from any thread.
     *
     *  <p>Deliberately does <strong>not</strong> touch the FLL.  The
     *  intended workflow is: let alignment converge, switch align OFF
     *  (which holds the generator at the now-stabilized frequency),
     *  then reset statistics to collect a clean average at near-zero
     *  frequency difference.  Resetting the FLL here would throw away
     *  exactly that converged correction.  The FLL is reset only when
     *  align is turned ON ({@link #resetFrequencyLock()}) or the record
     *  is restarted ({@link #stop()}). */
    public void resetStatistics() {
        worker.resetStatistics();
        // Drop the retained result so the readouts (fundamental, THD,
        // SNR, harmonics) and the plotted curve clear immediately and
        // repopulate from the fresh accumulation — otherwise the stale
        // last values linger on screen.  Only while recording: a
        // statically loaded CSV (worker stopped) must survive an
        // unrelated setting change that also calls resetStatistics.
        if (isRunning()) {
            lastResult = null;
            lastImd    = null;
            clearBanner();          // drop any re-sync / overrun warning on reset
        }
        syncDataButtons();
        Display d = getDisplay();
        if (d != null && !d.isDisposed()) {
            d.asyncExec(() -> { if (!isDisposed()) redraw(); });
        }
    }

    /** Resets the frequency-lock loop(s) so alignment converges fresh
     *  from zero.  Called when the user turns "align generator to
     *  frequency difference" ON — kept separate from
     *  {@link #resetStatistics()} so a manual statistics reset keeps
     *  the converged alignment intact. */
    public void resetFrequencyLock() {
        if (fll != null) fll.reset();
        if (fll2 != null) fll2.reset();
    }

    /** Post-average pipeline, run ONCE per DISPLAYED frame (the worker now hands
     *  over only the RAW averaged spectrum + the state below): mains rejection →
     *  recompute every measurement → .frc calibration.  So all
     *  readouts (THD / SNR / N / fundamental / harmonics, every unit + %) and the
     *  plot derive from one de-hummed, calibrated spectrum, computed only on
     *  frames the user actually sees. */
    private void finalizeResult(FftResult r) {
        if (r.amplitudeDbFs == null) return;
        boolean accumulated = !Double.isNaN(r.coherentKappa);

        // 1. Mains rejection — de-hum amplitudeDbFs (dBV is just dBFS + the global
        //    ADC offset, applied at display time, so nothing extra to correct here).
        boolean mainsApplied = false;
        if (r.mainsF0Hz > 0.0 && r.sampleRate > 0) {
            if (mainsCorrector == null) {
                mainsCorrector = new MainsCombFilter(r.sampleRate, MainsCombFilter.DEFAULT_NOTCH_BANDWIDTH_HZ);
            }
            mainsCorrector.applySpectrumCorrection(r.amplitudeDbFs, null, r.freqResolution, r.mainsF0Hz);
            mainsApplied = true;
        }

        // 2. Measurements — averaging always needs a recompute (analyze skipped
        //    the stats while accumulating); a single tick only needs it once mains
        //    has changed the spectrum (analyze already produced the rest).
        if (accumulated || mainsApplied) {
            mainsStats.recomputeStats(r);
        }

        // 3. Frequency-response (.frc) calibration — adjusts spectrum + readouts;
        //    independent of mains, never on the cal (green) line.
        List<FreqRespCorrectionStore.Entry> calEntries = correctionStore.getEntries();
        if (!calEntries.isEmpty()) {
            boolean wantLeft = r.channelLeft;
            r.preCorrectionPeaks = FreqRespCalHelper.capturePreCorrectionPeaks(r);
            for (FreqRespCorrectionStore.Entry e : calEntries) {
                FreqRespCalibration calForChan = wantLeft
                        ? e.getCalibration().left()
                        : e.getCalibration().right();
                FreqRespCalHelper.applyCompensationInPlace(r, calForChan, e.isWithNoise());
            }
            if (accumulated) {
                // Re-derive the BLUE "before-cal" dots on the cumulative spectrum
                // using the PINNED coherent κ (the single-tick fundamentalHzRefined
                // jitters and dances the cal-lookup point).
                int hc = r.harmonicCount;
                double[] preFreqs = new double[1 + hc];
                double[] preDbFs  = new double[1 + hc];
                double stableFundHz = r.coherentKappa * r.freqResolution;
                preFreqs[0] = r.fundamentalHz;
                preDbFs[0]  = r.fundamentalDbFs + sumCalDbAt(calEntries, wantLeft, stableFundHz);
                for (int h = 0; h < hc; h++) {
                    if (r.harmonicBins[h] > 0) {
                        preFreqs[1 + h] = r.harmonicHz[h];
                        double stableHarmHz = stableFundHz * (h + 2);
                        preDbFs[1 + h] = r.harmonicDbFs[h] + sumCalDbAt(calEntries, wantLeft, stableHarmHz);
                    }
                }
                r.preCorrectionPeaks = new double[][] { preFreqs, preDbFs };
            }
        }
    }

    /** Shows a static spectrum loaded from file instead of live data, in
     *  single-tone THD mode.  The caller must have already stopped recording
     *  (so the worker won't overwrite it). */
    public void displayLoadedResult(FftResult r) {
        displayLoadedResult(r, null);
    }

    /** Shows a static loaded spectrum.  When {@code imd} is non-null the view
     *  switches to IMD (dual-tone) table mode and renders that result;
     *  otherwise it shows the single-tone THD / harmonic table.  The THD /
     *  IMD metrics themselves are recomputed by the caller from the loaded
     *  spectrum. */
    public void displayLoadedResult(FftResult r, ImdResult imd) {
        lastResult = r;
        lastImd = imd;
        tableModeIsImd = (imd != null);
        syncDataButtons();
        if (!isDisposed()) { redraw(); update(); }
    }

    /** True when the table is currently in IMD (dual-tone) mode. */
    public boolean isTableModeImd() {
        return tableModeIsImd;
    }

    /** Sets the "Loaded: …" blinking-banner path (a statically loaded
     *  spectrum), or clears it with {@code null}.  Repaints. */
    public void setSourceFilePath(String path) {
        if (path == null || path.isEmpty()) {
            clearBanner();
        } else {
            setBanner(I18n.t("fft.loaded.prefix", path), null,
                    ColorRole.BLINK_LIT, ColorRole.BLINK_DIM, 0L);
        }
        if (!isDisposed()) redraw();
    }

    /** Shows the status banner — the loaded-spectrum path or the frame-rejection
     *  warning — on the {@link BlinkBanner} widget, which paints and blinks itself
     *  (no canvas redraw).  The widget right-aligns and left-ellipsises the text to
     *  its own width.
     *  @param untilNanos {@code 0} = persistent, else a {@code nanoTime} expiry. */
    private void setBanner(String text, String tooltip,
                           ColorRole lit, ColorRole dim, long untilNanos) {
        ensureFonts();
        banner.setFont(monoFont);
        banner.setText(text);
        banner.setToolTipText(tooltip);
        banner.setColors(color(lit), color(dim), color(ColorRole.BACKGROUND));
        bannerUntilNanos = untilNanos;
        banner.setVisible(true);
        // Size the right-anchored banner to its text so it doesn't span the full
        // plot (clamped to the old MARGIN_LEFT..right-6 band), then lay it out.
        banner.fitFormDataWidth(getClientArea().width - MARGIN_LEFT - 6);
        banner.requestLayout();          // re-size to the new text height + re-position
        if (untilNanos > 0L) {
            scheduleBannerHide(untilNanos);
        }
    }

    /** Hides the banner. */
    private void clearBanner() {
        bannerUntilNanos = 0L;
        if (banner != null && !banner.isDisposed()) {
            banner.setVisible(false);
        }
    }

    /** Drops a persistent (loaded-path) banner or one whose timer has run
     *  out; keeps a warning that is still within its sticky window. */
    private void clearBannerIfStale() {
        if (bannerUntilNanos == 0L || System.nanoTime() >= bannerUntilNanos) clearBanner();
    }

    /** Hides the banner once its expiry passes.  The widget blinks itself, so no
     *  paint tick polls the timer — a one-shot {@code timerExec} does.  Guarded so a
     *  newer banner (which moves {@link #bannerUntilNanos}) isn't hidden early. */
    private void scheduleBannerHide(long untilNanos) {
        long delayMs = (untilNanos - System.nanoTime()) / 1_000_000L;
        int  delay   = (int) Math.max(1L, Math.min(delayMs, Integer.MAX_VALUE));
        getDisplay().timerExec(delay, () -> {
            if (!isDisposed() && bannerUntilNanos == untilNanos) {
                clearBanner();
            }
        });
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

        // ── Frequency.  In DUAL_TONE / IMD mode the range needs to
        // span every measurement point we plot — F1, F2, the
        // difference frequency F2−F1, and the per-order dnL/dnH
        // sidebands — so the auto-set window includes the full IMD
        // dot family with a 10 % log-padding either side.  In
        // single-tone (THD) mode keep the existing "one decade either
        // side of the fundamental" behaviour.
        double lo, hi;
        if (lastImd != null) {
            // Aggregate every dot frequency we'd potentially plot.
            double minHz = Math.min(lastImd.f1Hz, lastImd.f2Hz);
            double maxHz = Math.max(lastImd.f1Hz, lastImd.f2Hz);
            double diff  = Math.abs(lastImd.f2Hz - lastImd.f1Hz);
            if (diff > 0) minHz = Math.min(minHz, diff);
            for (int k = 2; k <= ImdResult.MAX_ORDER; k++) {
                double dl = lastImd.dnLHz[k];
                double dh = lastImd.dnHHz[k];
                if (Double.isFinite(dl) && dl > 0) {
                    minHz = Math.min(minHz, dl);
                    maxHz = Math.max(maxHz, dl);
                }
                if (Double.isFinite(dh) && dh > 0) {
                    minHz = Math.min(minHz, dh);
                    maxHz = Math.max(maxHz, dh);
                }
            }
            // 10 % log padding on each side so the outermost dots
            // aren't pinned to the chart edges.
            double padLo = Math.pow(10, Math.log10(minHz) - 0.1);
            double padHi = Math.pow(10, Math.log10(maxHz) + 0.1);
            lo = Math.max(currentBinSize(), padLo);
            hi = Math.min(lastResult.sampleRate / 2.0, padHi);
        } else {
            double f0 = lastResult.fundamentalHzRefined;
            if (!(Double.isFinite(f0) && f0 > 0)) { lo = 0; hi = 0; }
            else {
                lo = Math.max(currentBinSize(), f0 / 10.0);
                hi = Math.min(lastResult.sampleRate / 2.0,  f0 * 10.0);
            }
        }
        if (hi > lo) {
            prefs.setFftFreqMinHz(lo);
            prefs.setFftFreqMaxHz(hi);
        }

        // ── Magnitude: top = strongest detected tone + 10 dB,
        // bottom = noise floor − 20 dB.  In DUAL_TONE mode the
        // strongest tone is whichever of F1 / F2 has the higher
        // dBFS — the single-fundamental detector in FftAnalyzer
        // already latches on to that one, so we use both F1/F2
        // dBFS from the IMD slot to pick the maximum explicitly.
        if (Double.isFinite(lastResult.fundamentalDbFs) && Double.isFinite(lastResult.avgNoiseFloorDbFs)) {
            // The fundamental's DISPLAYED dBFS — manual override (converted) when set,
            // else the measured level (already .frc-corrected in place by the worker).
            double peakDbFs = displayedFundDbFs();
            if (!Double.isFinite(peakDbFs)) peakDbFs = lastResult.fundamentalDbFs;
            if (lastImd != null) {
                double dbvOffsetDb = prefs.getDbvOffsetDb();
                double f1DbFs = lastImd.f1DbV - dbvOffsetDb;
                double f2DbFs = lastImd.f2DbV - dbvOffsetDb;
                if (Double.isFinite(f1DbFs)) peakDbFs = Math.max(peakDbFs, f1DbFs);
                if (Double.isFinite(f2DbFs)) peakDbFs = Math.max(peakDbFs, f2DbFs);
            }
            // Range is stored canonically in dBFS; the unit conversion happens only at
            // draw / readout time, so every unit pans + zooms identically.
            double topDbFs = peakDbFs + 20;
            double botDbFs = lastResult.avgNoiseFloorDbFs - 20;
            if (Double.isFinite(topDbFs) && Double.isFinite(botDbFs) && topDbFs != botDbFs) {
                if (topDbFs < botDbFs) { double s = topDbFs; topDbFs = botDbFs; botDbFs = s; }
                prefs.setFftMagTop(topDbFs);
                prefs.setFftMagBottom(botDbFs);
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
        // Default +20 dBFS ceiling, but never below the DISPLAYED (correction-lifted)
        // fundamental + 20 dB, so a notch un-notched above 0 dBFS still fits.
        double fundTop = displayedFundDbFs();
        double topDbFs = Double.isFinite(fundTop) ? Math.max(20.0, fundTop + 20.0) : 20.0;
        double botDbFs = (lastResult != null && Double.isFinite(lastResult.avgNoiseFloorDbFs))
                ? lastResult.avgNoiseFloorDbFs - 30
                : -150;
        // Range stored canonically in dBFS — unit conversion happens at draw / readout.
        if (Double.isFinite(topDbFs) && Double.isFinite(botDbFs) && topDbFs != botDbFs) {
            if (topDbFs < botDbFs) { double s = topDbFs; topDbFs = botDbFs; botDbFs = s; }
            prefs.setFftMagTop(topDbFs);
            prefs.setFftMagBottom(botDbFs);
        }
        prefs.save();
        fireRangeChanged();
        redraw();
    }

    /** The fundamental's DISPLAYED level in dBFS — what the spectrum actually draws at
     *  the fundamental: the manual-override dBV (converted) when set, else the measured
     *  level, which the worker already corrected in place for the loaded {@code .frc}
     *  calibration.  {@code NaN} with no result. */
    private double displayedFundDbFs() {
        if (lastResult == null || !Double.isFinite(lastResult.fundamentalDbFs)) return Double.NaN;
        if (Double.isFinite(lastResult.fundamentalTrueDbFs)) {
            return lastResult.fundamentalTrueDbFs;               // manual fundamental, already dBFS
        }
        return lastResult.fundamentalDbFs;                       // already .frc-corrected
    }

    /** Magnitude-axis ceiling in dBFS: the 0 dBFS full-scale max, raised when a signal is
     *  present to keep the DISPLAYED fundamental + 20 dB in range — so the axis can exceed
     *  0 dBFS when calibration lifts the signal above full scale.  Used by the pane's
     *  clamp / scrollbar sync / scroll handler so the corrected peak is reachable. */
    public double magCeiling() {
        double fundDbFs = displayedFundDbFs();
        if (Double.isFinite(fundDbFs)) return Math.max(0.0, fundDbFs + 20.0);
        // No analysis yet (e.g. app start): the ceiling must not shrink below
        // the persisted window top — the pane's clamp writes the clamped value
        // back into prefs, which would destroy a saved > 0 dB zoom/scroll
        // position before the first result can re-raise the ceiling.
        return Math.max(0.0, Preferences.instance().getFftMagTop());
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
        if (distortionWindow != null) distortionWindow.redraw();
    }

    // =========================================================================
    // Paint pipeline
    // =========================================================================

    private void onPaint(PaintEvent e) {
        GC gc = e.gc;
        ensureFonts();
        syncFftColors();

        Rectangle area = getClientArea();
        Preferences prefs = Preferences.instance();
        int rightMargin = 1;
        Rectangle plot = new Rectangle(MARGIN_LEFT,
                                       MARGIN_TOP,
                                       Math.max(1, area.width  - MARGIN_LEFT - rightMargin),
                                       Math.max(1, area.height - MARGIN_TOP  - MARGIN_BOTTOM));

        MagnitudeUnit unit = prefs.getFftMagUnit();
        double freqMin = Math.max(0, prefs.getFftFreqMinHz());
        double freqMax = Math.max(freqMin + 1, prefs.getFftFreqMaxHz());
        // The magnitude range is stored canonically in dBFS; convert to the display unit
        // for drawing (Preferences derives the 0-dBFS anchor + bin width from config).
        double magBot   = prefs.convertFromDbFs(prefs.getFftMagBottom(), unit);
        double magTop   = prefs.convertFromDbFs(prefs.getFftMagTop(),    unit);
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
            boolean magLog = unit.isLog();
            AxisSpec xSpec = (logFreq
                    ? AxisSpec.log(fFreqMin, freqMax)
                    : AxisSpec.linearNice(fFreqMin, freqMax, 10, 0))
                    .withFormat(logFreq ? LabelFormat.FREQ : LabelFormat.FREQ_INT);
            AxisSpec ySpec = (magLog
                    ? AxisSpec.log(magBot, magTop)
                    : AxisSpec.linearNice(magBot, magTop, 10, 5.0))
                    .withFormat(magLog ? LabelFormat.VOLTS_SI : LabelFormat.DB)
                    .withUnit(magUnitLabel(unit));
            drawGrid(bgc, plot, xSpec, ySpec, null,
                     color(ColorRole.GRID), color(ColorRole.AXIS), color(ColorRole.TEXT), monoFont,
                     MAJOR_TICK_LEN, MINOR_TICK_LEN, null);
            drawDistortionBands(bgc, plot, fFreqMin, freqMax, logFreq);
            if (lastResult != null) {
                drawSpectrum(bgc, plot, lastResult, unit, fFreqMin, freqMax, magTop, magBot, logFreq);
                drawCalOverlay(bgc, plot, lastResult, unit, fFreqMin, freqMax,
                        magTop, magBot, logFreq);
                // Harmonic dots are THD-mode markers — hide them when
                // an IMD result is present (DUAL_TONE generator) so the
                // F1/F2/dnL/dnH annotations don't compete with the
                // single-fundamental-anchored H2..Hn series for the
                // same screen space.
                if (lastImd == null) {
                    drawHarmonicDots(bgc, plot, lastResult, unit, fFreqMin, freqMax,
                            magTop, magBot, logFreq);
                }
            }
            if (lastImd != null) {
                drawImdDots(bgc, plot, lastImd, lastResult, unit, fFreqMin, freqMax,
                        magTop, magBot, logFreq);
            }
        });

        // ---- Dynamic overlay (drawn live on top of the cached image)
        gc.setAntialias(SWT.ON);
        gc.setTextAntialias(SWT.ON);
        // Exactly one table.  Mode is sticky and only flipped inside
        // onResultReady (which fires only when the analyser is actually
        // recording), so the user's last-recorded mode persists while
        // recording is paused even if they toggle the generator form
        // in the meantime.
        if (lastResult != null && prefs.isFftDistortionTableVisible() && !tableExtracted) {
            if (tableModeIsImd && lastImd != null) {
                drawImdTable(gc, lastImd, MARGIN_LEFT + 6, TABLE_TOP_Y);
            } else {
                drawDistortionTable(gc, lastResult, unit);
            }
        }
        // (The top-right status banner is now the self-painting BlinkBanner widget.)
        // Crosshair: only when inside the plot area (the header buttons are child
        // widgets now, so the canvas never receives moves over them).
        if (crossX >= plot.x && crossX < plot.x + plot.width
                && crossY >= plot.y && crossY < plot.y + plot.height) {
            drawCrosshair(gc, plot, lastResult, unit, freqMin, freqMax, magTop, magBot, logFreq);
        }
        if (log.isWarnEnabled() && gotFftResult && DebugSwitches.SHOW_FFT_ANALYZE_TIME) {
            log.warn("          3. FFT result rendered in {} ms", (double)(System.nanoTime() - startRender) / 1_000_000);
            gotFftResult = false;
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
    private void drawHarmonicDots(GC gc, Rectangle plot, FftResult r,
                                  MagnitudeUnit unit,
                                  double freqMin, double freqMax,
                                  double magTop, double magBot,
                                  boolean logFreq) {
        Preferences prefs = Preferences.instance();
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
                plotDotAt(gc, plot, before[0][i], before[1][i],
                        unit, freqMin, freqMax, magTop, magBot, logFreq);
            }
        }

        // Each plotDotAt gates on the dot's magnitude vs the visible
        // range and skips drawing when it falls outside — that hides
        // dots cleanly without snapping them to the chart edges.
        // The fundamental dot sits on the DISPLAYED peak: the manual
        // fundamental when set (the trace's lobe is stretched to it at
        // draw time), else the measured level.
        double fundDotDbFs = Double.isFinite(r.fundamentalTrueDbFs)
                ? r.fundamentalTrueDbFs : r.fundamentalDbFs;
        gc.setBackground(color(ColorRole.HARMONIC_DOT));
        plotDotAt(gc, plot, r.fundamentalHzRefined, fundDotDbFs,
                unit, freqMin, freqMax, magTop, magBot, logFreq);
        if (r.harmonicHz != null && r.harmonicDbFs != null) {
            int n = Math.min(r.harmonicHz.length, r.harmonicDbFs.length);
            for (int i = 0; i < n; i++) {
                plotDotAt(gc, plot, r.harmonicHz[i], r.harmonicDbFs[i],
                        unit, freqMin, freqMax, magTop, magBot, logFreq);
            }
        }

        // Hn labels on top of every (corrected) dot — fundamental gets
        // the full "H1 ⟨freq⟩" pair, harmonics get just "Hn".  Only
        // the harmonics within the calc-max-harmonic count are
        // labelled (matches the THD-tab's calcMaxHarmonic pref).
        gc.setForeground(color(ColorRole.HARMONIC_DOT));
        drawHarmonicLabel(gc, plot, "F " + formatFrequency(r.fundamentalHzRefined),
                r.fundamentalHzRefined, fundDotDbFs,
                unit, freqMin, freqMax, magTop, magBot, logFreq);
        if (r.harmonicHz != null && r.harmonicDbFs != null) {
            int n = Math.min(r.harmonicHz.length, r.harmonicDbFs.length);
            int calcMax = Math.max(9, prefs.getFftCalcMaxHarmonic());
            for (int i = 0; i < n && (i + 2) <= calcMax; i++) {
                drawHarmonicLabel(gc, plot, "H" + (i + 2),
                        r.harmonicHz[i], r.harmonicDbFs[i],
                        unit, freqMin, freqMax, magTop, magBot, logFreq);
            }
        }

        // DEBUG overlay: mains comb response (red), anchored at H2's level.
        if (r.harmonicDbFs != null && r.harmonicDbFs.length > 0) {
            drawMainsResponse(gc, plot, r, r.harmonicDbFs[0], unit,
                    freqMin, freqMax, magTop, magBot, logFreq);
        }
        // DEBUG overlay: spectral discontinuity reject gates.
        drawDiscontinuityGates(gc, plot, r, unit,
                freqMin, freqMax, magTop, magBot, logFreq, binBw);
    }

    /** DEBUG: draws the mains comb's response as a red curve across the plot,
     *  with its 0 dB (passband) baseline anchored at {@code anchorDbFs} — so the
     *  notch positions are visible against the dots.  Reused by the THD path
     *  (anchored at H2) and the IMD path (anchored at d2L, since H2 isn't marked
     *  there).  No-op unless mains is locked. */
    private void drawMainsResponse(GC gc, Rectangle plot, FftResult r, double anchorDbFs,
                                   MagnitudeUnit unit, double freqMin, double freqMax,
                                   double magTop, double magBot, boolean logFreq) {
        if (!DebugSwitches.SHOW_MAINS_COMB_RESPONSE || !(r.mainsF0Hz > 0.0) || mainsCorrector == null
                || !Double.isFinite(anchorDbFs)) return;
        gc.setForeground(getDisplay().getSystemColor(SWT.COLOR_RED));
        Preferences prefs = Preferences.instance();
        int prevX = -1, prevY = 0;
        for (int x = plot.x; x <= plot.x + plot.width; x++) {
            double f = xToFreq(x, plot, freqMin, freqMax, logFreq);
            if (!(f > 0)) continue;
            double combDb = mainsCorrector.correctionDb(f);
            double v = prefs.convertFromDbFs(anchorDbFs + combDb, unit, r.binBwSqrt);
            int y = magToY(v, plot, magTop, magBot, unit);
            y = Math.max(plot.y, Math.min(plot.y + plot.height, y));
            if (prevX >= 0) gc.drawLine(prevX, prevY, x, y);
            prevX = x;
            prevY = y;
        }
    }

    /** DEBUG: overlays the spectral discontinuity detector's three reject gates
     *  — gate-2 floor-reference curve (dark cyan) + reject boundary (cyan),
     *  gate-1 near-carrier pedestal reject line (orange), gate-3 total-power
     *  line + reject band (magenta) — so it's visible what each gate would
     *  reject.  Every value is positioned by its dB excess over the detector's
     *  floor, added to the DISPLAYED (calibrated) floor, so it lands at the
     *  right vertical position whatever calibration the chart applies.  A gate
     *  that fired this block is drawn thicker. */
	private void drawDiscontinuityGates(GC gc, Rectangle plot, FftResult r,
                                        MagnitudeUnit unit, double freqMin, double freqMax,
                                        double magTop, double magBot, boolean logFreq,
                                        double binBw) {
        SpectralDiscontinuityDetector.Gates g = r.gates;
        if (!DebugSwitches.SHOW_DISCONTINUITY_GATES || g == null
                || binBw <= 0 || r.amplitudeDbFs == null) return;
        Display display = getDisplay();
        double[] dbfs = r.amplitudeDbFs;
        int n = dbfs.length, nb = g.mref.length;
        SpectralDiscontinuityDetector.Gates rej = r.gateRejectGates;   // last rejection's verdict
        boolean rejScore = rej != null && rej.scoreOut;
        boolean rejPed   = rej != null && rej.pedestalOut;
        boolean rejPow   = rej != null && rej.powerOut;

        // Displayed (calibrated) floor per band = median dBFs over the band.
        double[] floorDisp = new double[nb];
        for (int b = 0; b < nb; b++) {
            floorDisp[b] = medianRange(dbfs, Math.max(1, g.bandLo[b]), Math.min(n, g.bandHi[b]));
        }

        // Per-band MEAN power levels of the pre-average blocks (the quantity gate 2
        // compares) and the line bands a tone dominates — gate 2 excludes those, so
        // they are skipped in the reference and the block curves alike.
        double[] curLvl = bandLevels(r.gateBlockDbFs, g);
        boolean[] line  = lineBands(g.mref);

        // gate 2: per-band floor reference (dark cyan) + reject boundary (cyan).
        int prevXr = -1, prevYr = 0, prevXb = -1, prevYb = 0;
        for (int b = 0; b < nb; b++) {
            if (line[b] || Double.isNaN(floorDisp[b])) { prevXr = prevXb = -1; continue; }
            int x = freqToX(0.5 * (g.bandLo[b] + g.bandHi[b]) * binBw, plot, freqMin, freqMax, logFreq);
            double ref = floorDisp[b] + (g.mref[b] - g.floorDb);
            int yr = gateY(ref, unit, plot, magTop, magBot);
            int yb = gateY(ref + g.scoreThreshDb, unit, plot, magTop, magBot);
            gc.setForeground(display.getSystemColor(SWT.COLOR_DARK_CYAN));
            gc.setLineWidth(3);
            if (prevXr >= 0) gc.drawLine(prevXr, prevYr, x, yr);
            gc.setForeground(display.getSystemColor(SWT.COLOR_CYAN));
            gc.setLineWidth(rejScore ? 2 : 1);
            if (prevXb >= 0) gc.drawLine(prevXb, prevYb, x, yb);
            prevXr = x; prevYr = yr; prevXb = x; prevYb = yb;
        }

        // gate 1: near-carrier pedestal — reject threshold (dark yellow) AND the
        // rejected block's ACTUAL pedestal (red, thick when it fired), so the
        // excess that tripped it is visible.
        if (g.peakBins != null && !Double.isNaN(g.pedestalThreshDb)) {
            int half = Math.max(24, plot.width / 16);
            double threshExc = rej != null && !Double.isNaN(rej.pedestalThreshDb)
                    ? rej.pedestalThreshDb : g.pedestalThreshDb;
            for (int pk : g.peakBins) {
                double cf = skirtFloorDbFs(dbfs, pk);
                if (Double.isNaN(cf)) continue;
                int xc = freqToX(pk * binBw, plot, freqMin, freqMax, logFreq);
                int xL = Math.max(plot.x, xc - half), xR = Math.min(plot.x + plot.width, xc + half);
                gc.setForeground(display.getSystemColor(SWT.COLOR_DARK_YELLOW));
                gc.setLineWidth(1);
                int yT = gateY(cf + threshExc, unit, plot, magTop, magBot);
                gc.drawLine(xL, yT, xR, yT);
                if (rej != null && !Double.isNaN(rej.pedestalExcessDb)) {
                    gc.setForeground(display.getSystemColor(SWT.COLOR_RED));
                    gc.setLineWidth(rejPed ? 2 : 1);
                    int yA = gateY(cf + rej.pedestalExcessDb, unit, plot, magTop, magBot);
                    gc.drawLine(xL, yA, xR, yA);
                }
            }
        }

        // gate 3: total-power line + reject band (magenta).
        double medFloor = medianArray(floorDisp);
        if (!Double.isNaN(medFloor) && g.bins > 0) {
            double perBin = 10.0 * Math.log10(g.bins);
            int xr = plot.x + plot.width;
            gc.setForeground(display.getSystemColor(SWT.COLOR_MAGENTA));
            gc.setLineWidth(rejPow ? 2 : 1);
            int yCur = gateY(medFloor + g.powerDb - perBin - g.floorDb, unit, plot, magTop, magBot);
            gc.drawLine(plot.x, yCur, xr, yCur);
            if (g.powerThreshDb > 0) {
                gc.setLineWidth(3);
                gc.setLineStyle(SWT.LINE_DOT);
                double mid = medFloor + g.powerMedDb - perBin - g.floorDb;
                int yLo = gateY(mid - g.powerThreshDb, unit, plot, magTop, magBot);
                int yHi = gateY(mid + g.powerThreshDb, unit, plot, magTop, magBot);
                gc.drawLine(plot.x, yLo, xr, yLo);
                gc.drawLine(plot.x, yHi, xr, yHi);
                gc.setLineStyle(SWT.LINE_SOLID);
            }
            if (rej != null) {   // rejected block's total power vs the band
                gc.setForeground(display.getSystemColor(SWT.COLOR_RED));
                gc.setLineWidth(rejPow ? 2 : 1);
                int yRej = gateY(medFloor + rej.powerDb - perBin - rej.floorDb, unit, plot, magTop, magBot);
                gc.drawLine(plot.x, yRej, xr, yRej);
            }
        }

        // Current accepted block — per-band level curve (grey, context).
        drawLevelCurve(gc, plot, curLvl, line, floorDisp, g, unit,
                freqMin, freqMax, magTop, magBot, logFreq, binBw, SWT.COLOR_GRAY, false);
        // Full REJECTED FFT (red) at bin resolution — its fundamental rigidly
        // shifted ONTO the displayed fundamental (no stretch) so the block's
        // near-carrier skirt reads at its TRUE height against the aligned peak:
        // the pedestal that tripped the reject shows directly.
        int fundBin = (Double.isFinite(r.fundamentalHzRefined) && r.fundamentalHzRefined > 0)
                ? (int) Math.round(r.fundamentalHzRefined / binBw)
                : (g.peakBins != null && g.peakBins.length > 0 ? g.peakBins[0] : -1);
        double[] rejPlot = buildRejectedBlockDbFs(r, fundBin, binBw, freqMin, freqMax);
        drawFullBlock(gc, plot, rejPlot, unit,
                freqMin, freqMax, magTop, magBot, logFreq, binBw, SWT.COLOR_RED);

        // Gate-status readout: value/threshold per gate for the last reject and the
        // current block, firing gate marked «<<» — over-rejection visible at a glance.
        gc.setLineWidth(1);
        if (rej != null) {
            gc.setForeground(display.getSystemColor(SWT.COLOR_RED));
            gc.drawString(gateLine("reject ", rej, rejScore, rejPed, rejPow),
                    plot.x + 8, plot.y + plot.height - 32, true);
        }
        gc.setForeground(display.getSystemColor(SWT.COLOR_DARK_GRAY));
        gc.drawString(gateLine("current", g, g.scoreOut, g.pedestalOut, g.powerOut),
                plot.x + 8, plot.y + plot.height - 16, true);
    }

    /** Draws a precomputed full-resolution block spectrum ({@code plotDbFs},
     *  one dBFs value per bin) as a per-column peak-hold envelope. */
    private void drawFullBlock(GC gc, Rectangle plot, double[] plotDbFs,
                               MagnitudeUnit unit, double freqMin, double freqMax,
                               double magTop, double magBot, boolean logFreq, 
                               double binBw, int swtColor) {
        if (plotDbFs == null || binBw <= 0) return;
        int nblk = plotDbFs.length;
        int kFirst = Math.max(1, (int) Math.floor(freqMin / binBw));
        int kLast  = Math.min(nblk - 1, (int) Math.ceil(freqMax / binBw));
        if (kLast <= kFirst) return;
        int w = plot.width;
        int[] colY = new int[w + 1];
        Arrays.fill(colY, Integer.MAX_VALUE);
        Preferences prefs = Preferences.instance();
        for (int k = kFirst; k <= kLast; k++) {
            double v = prefs.convertFromDbFs(plotDbFs[k], unit);
            int y = Math.max(plot.y, Math.min(plot.y + plot.height, magToY(v, plot, magTop, magBot, unit)));
            int col = freqToX(k * binBw, plot, freqMin, freqMax, logFreq) - plot.x;
            if (col >= 0 && col <= w && y < colY[col]) colY[col] = y;
        }
        gc.setForeground(getDisplay().getSystemColor(swtColor));
        gc.setLineWidth(1);
        int prevX = -1, prevY = 0;
        for (int col = 0; col <= w; col++) {
            if (colY[col] == Integer.MAX_VALUE) continue;
            int x = plot.x + col;
            if (prevX >= 0) gc.drawLine(prevX, prevY, x, colY[col]);
            prevX = x; prevY = colY[col];
        }
    }

    /** Builds the rejected block's full-resolution dBFs curve, positioned to
     *  ATTACH its fundamental onto the displayed (averaged) fundamental:
     *  <ul>
     *   <li>plain spectrum — a rigid dB shift lands the block's fundamental peak
     *       on the displayed peak (NO stretch); the block's higher single-shot
     *       floor then rides above the average, exposing the near-carrier skirt
     *       that tripped the reject.</li>
     *   <li>manual-fundamental or .frc — the displayed fundamental is itself a
     *       stretched dome, so the block's lobe is stretched the SAME way (same
     *       {@link ToneLobeLift#stretch}) up to the displayed peak, its skirt
     *       lifted onto the cal-corrected scale by the cascade gain.</li>
     *  </ul>
     *  Returns {@code null} when no fundamental / block data is available. */
    private double[] buildRejectedBlockDbFs(FftResult r, int fundBin,
                                            double binBw, double freqMin, double freqMax) {
        double[] block = r.gateRejectDbFs, disp = r.amplitudeDbFs;
        if (block == null || disp == null || fundBin < 1 || fundBin >= block.length) return null;
        int half = block.length - 1;
        int kFirst = Math.max(1,    (int) Math.floor(freqMin / binBw));
        int kLast  = Math.min(half, (int) Math.ceil (freqMax / binBw));
        if (kLast <= kFirst) return null;
        double[] out = block.clone();
        final double[] o = out;
        IntToDoubleFunction omag = k -> Math.pow(10.0, o[k] / 20.0);

        // 1. Calibrate the block EXACTLY as the displayed (blue) spectrum is, so
        //    red lands on blue's scale and is directly comparable.  With-noise
        //    divides every bin by |H| at its own frequency; otherwise only each
        //    tone's data-derived lobe is stretched by 1/|H| at the tone (noise
        //    left raw) — mirroring FreqRespCalHelper.applyCompensationInPlace.
        for (FreqRespCorrectionStore.Entry e : correctionStore.getEntries()) {
            FreqRespCalibration c = r.channelLeft ? e.getCalibration().left()
                                                  : e.getCalibration().right();
            if (e.isWithNoise()) {
                for (int k = kFirst; k <= kLast; k++) {
                    double hMag = FreqRespCalHelper.interpolate(c, k * binBw)[0];
                    if (hMag > 0.0) out[k] -= 20.0 * Math.log10(hMag);
                }
            } else {
                stretchToneLobe(out, omag, c, fundBin, binBw, half);
                for (int h = 0; h < r.harmonicCount; h++) {
                    if (r.harmonicBins != null && r.harmonicBins[h] > 0) {
                        stretchToneLobe(out, omag, c, r.harmonicBins[h], binBw, half);
                    }
                }
                if (!Double.isNaN(r.fundamental2HzRefined) && r.fundamental2HzRefined > 0) {
                    stretchToneLobe(out, omag, c,
                            (int) Math.round(r.fundamental2HzRefined / binBw), binBw, half);
                }
            }
        }

        // 2. Manual fundamental — its displayed peak is lifted at draw time (not in
        //    amplitudeDbFs), so lift red's fundamental lobe to the user value too.
        boolean manual = Double.isFinite(r.fundamentalTrueDbFs)
                && Double.isFinite(r.fundamentalDbFs) && Double.isFinite(r.fundamentalHzRefined);
        double targetDbFs;
        if (manual) {
            targetDbFs = r.fundamentalTrueDbFs;
            stretchLobe(out, omag, fundBin, half,
                    Math.pow(10.0, (targetDbFs - out[fundBin]) / 20.0));
        } else {
            double dispPk = Double.NEGATIVE_INFINITY;
            for (int d = -2; d <= 2; d++) {
                int k = fundBin + d;
                if (k >= 1 && k < disp.length) dispPk = Math.max(dispPk, disp[k]);
            }
            targetDbFs = dispPk;
        }

        // 3. Final attach: rigid shift landing red's fundamental peak exactly on
        //    the displayed one (a small residual — the cal already matched it).
        double redPk = Double.NEGATIVE_INFINITY;
        for (int d = -2; d <= 2; d++) {
            int k = fundBin + d;
            if (k >= kFirst && k <= kLast) redPk = Math.max(redPk, out[k]);
        }
        if (Double.isFinite(redPk) && Double.isFinite(targetDbFs)) {
            double off = targetDbFs - redPk;
            for (int k = kFirst; k <= kLast; k++) out[k] += off;
        }
        return out;
    }

    /** Stretches a tone's data-derived main lobe in {@code out} by the cal's
     *  {@code 1/|H|} at the tone frequency — the per-tone correction
     *  {@link FreqRespCalHelper#applyCompensationInPlace} applies when the cal
     *  excludes noise. */
    private void stretchToneLobe(double[] out, IntToDoubleFunction mag, FreqRespCalibration c,
                                 int toneBin, double binBw, int half) {
        if (toneBin < 1 || toneBin > half) return;
        double hMag = FreqRespCalHelper.interpolate(c, toneBin * binBw)[0];
        if (hMag > 0.0) stretchLobe(out, mag, toneBin, half, 1.0 / hMag);
    }

    /** Stretches one tone lobe in {@code out} by a linear {@code factor} via the
     *  shared {@link ToneLobeLift#stretch} (peak ×factor, wings pinned to the
     *  floor). */
    private void stretchLobe(double[] out, IntToDoubleFunction mag, int toneBin, int half,
                             double factor) {
        if (toneBin < 1 || toneBin > half) return;
        double floor   = LOBE.localFloor(mag, toneBin, half);
        int[]  edges   = LOBE.lobeBins(mag, toneBin, half, floor);
        double peakMag = mag.applyAsDouble(toneBin);
        for (int k = edges[0]; k <= edges[1]; k++) {
            if (k < 1 || k > half || k >= out.length) continue;
            double nm = LOBE.stretch(mag.applyAsDouble(k), floor, peakMag, factor);
            out[k] = nm > 1e-15 ? 20.0 * Math.log10(nm) : -300.0;
        }
    }

    /** One-line gate status: value/threshold per gate, firing gate marked. */
    private String gateLine(String tag, SpectralDiscontinuityDetector.Gates g,
                            boolean score, boolean ped, boolean pow) {
        return String.format(Locale.US,
                "%s   floor %+.1f/%.1f%s   pedestal %+.1f/%.1f%s   power %.1f/%.1f%s",
                tag,
                g.scoreDb, g.scoreThreshDb, score ? " <<" : "",
                g.pedestalExcessDb, g.pedestalThreshDb, ped ? " <<" : "",
                Math.abs(g.powerDb - g.powerMedDb), g.powerThreshDb, pow ? " <<" : "");
    }

    /** Per-band MEAN power level (dBFs) of a pre-average block — the quantity
     *  gate 2 compares; {@code null} when the block is absent. */
    private double[] bandLevels(double[] block, SpectralDiscontinuityDetector.Gates g) {
        if (block == null) return null;
        int nb = g.mref.length, n = block.length;
        double[] lvl = new double[nb];
        for (int b = 0; b < nb; b++) {
            int lo = Math.max(1, g.bandLo[b]), hi = Math.min(n, g.bandHi[b]);
            double sum = 0.0; int m = 0;
            for (int k = lo; k < hi; k++) { sum += Math.pow(10.0, block[k] / 10.0); m++; }
            lvl[b] = m > 0 ? 10.0 * Math.log10(sum / m + 1e-300) : Double.NaN;
        }
        return lvl;
    }

    /** Bands whose level towers &gt; 10 dB over the local median — tones, which
     *  gate 2 excludes from its floor comparison. */
    private boolean[] lineBands(double[] lvl) {
        int nb = lvl.length;
        boolean[] line = new boolean[nb];
        for (int b = 0; b < nb; b++) {
            int lo = Math.max(0, b - 3), hi = Math.min(nb, b + 4);
            double[] loc = Arrays.copyOfRange(lvl, lo, hi);
            Arrays.sort(loc);
            line[b] = lvl[b] > loc[loc.length / 2] + 10.0;
        }
        return line;
    }

    /** Median of the non-line band levels — a block's own floor of band means. */
    private double bandFloor(double[] lvl, boolean[] line) {
        double[] c = new double[lvl.length];
        int m = 0;
        for (int b = 0; b < lvl.length; b++) if (!line[b] && !Double.isNaN(lvl[b])) c[m++] = lvl[b];
        if (m == 0) return 0.0;
        c = Arrays.copyOf(c, m);
        Arrays.sort(c);
        return c[m / 2];
    }

    /** Draws a block's per-band level curve, anchored to the displayed floor by
     *  each band's excess over the block's own floor (so it shares the gate
     *  reference's frame); line bands are skipped. */
    private void drawLevelCurve(GC gc, Rectangle plot, double[] lvl, boolean[] line, double[] floorDisp,
                                SpectralDiscontinuityDetector.Gates g, MagnitudeUnit unit,
                                double freqMin, double freqMax, double magTop, double magBot,
                                boolean logFreq, double binBw, int swtColor, boolean dashed) {
        if (lvl == null) return;
        double floor = bandFloor(lvl, line);
        gc.setForeground(getDisplay().getSystemColor(swtColor));
        gc.setLineWidth(3);
        gc.setLineStyle(dashed ? SWT.LINE_DASH : SWT.LINE_SOLID);
        int prevX = -1, prevY = 0;
        for (int b = 0; b < lvl.length; b++) {
            if (line[b] || Double.isNaN(lvl[b]) || Double.isNaN(floorDisp[b])) { prevX = -1; continue; }
            double dispDbFs = floorDisp[b] + (lvl[b] - floor);
            int y = gateY(dispDbFs, unit, plot, magTop, magBot);
            int x = freqToX(0.5 * (g.bandLo[b] + g.bandHi[b]) * binBw, plot, freqMin, freqMax, logFreq);
            if (prevX >= 0) gc.drawLine(prevX, prevY, x, y);
            prevX = x; prevY = y;
        }
        gc.setLineStyle(SWT.LINE_SOLID);
    }

    private int gateY(double dbFs, MagnitudeUnit unit, Rectangle plot, double magTop, double magBot) {
        double v = Preferences.instance().convertFromDbFs(dbFs, unit);
        int y = magToY(v, plot, magTop, magBot, unit);
        return Math.max(plot.y, Math.min(plot.y + plot.height, y));
    }

    /** Median of {@code a[lo..hi)}; NaN when empty. */
    private double medianRange(double[] a, int lo, int hi) {
        int len = hi - lo;
        if (len <= 0) return Double.NaN;
        double[] c = Arrays.copyOfRange(a, lo, hi);
        Arrays.sort(c);
        return c[len / 2];
    }

    /** Displayed noise floor in the skirt flanking bin {@code pk} (main lobe excluded). */
    private double skirtFloorDbFs(double[] a, int pk) {
        final int gap = 7, span = 56;
        double[] buf = new double[2 * span];
        int n = 0;
        for (int k = pk - gap - span; k < pk - gap; k++) if (k >= 1 && k < a.length) buf[n++] = a[k];
        for (int k = pk + gap + 1; k <= pk + gap + span && k < a.length; k++) if (k >= 1) buf[n++] = a[k];
        if (n == 0) return Double.NaN;
        double[] c = Arrays.copyOf(buf, n);
        Arrays.sort(c);
        return c[n / 2];
    }

    /** Median of an array, skipping NaN; NaN when all NaN. */
    private double medianArray(double[] a) {
        double[] c = new double[a.length];
        int n = 0;
        for (double v : a) if (!Double.isNaN(v)) c[n++] = v;
        if (n == 0) return Double.NaN;
        c = Arrays.copyOf(c, n);
        Arrays.sort(c);
        return c[n / 2];
    }

    /** Returns the on-screen (x, y) pixel position of a dot at the
     *  given freq + dBFs, or {@code null} when the dot would fall
     *  outside the visible plot.  Used by the F1 / F2 label-placement
     *  logic to anti-overlap labels while still anchoring each to its
     *  own dot's vertical position. */
    private Point dotScreenPos(Rectangle plot, double hz, double dbFs,
                               MagnitudeUnit unit,
                               double freqMin, double freqMax,
                               double magTop, double magBot,
                               boolean logFreq) {
        if (!Double.isFinite(hz) || hz < freqMin || hz > freqMax) return null;
        if (!Double.isFinite(dbFs)) return null;
        double v = Preferences.instance().convertFromDbFs(dbFs, unit);
        double t = magToYFraction(v, magTop, magBot, unit);
        if (t < 0 || t > 1) return null;
        int x = freqToX(hz, plot, freqMin, freqMax, logFreq);
        int y = magToY(v, plot, magTop, magBot, unit);
        return new Point(x, y);
    }

    /** Paints a small label centred horizontally on the dot's column
     *  and just above the dot itself.  No-op when the dot would fall
     *  outside the visible plot. */
    private void drawHarmonicLabel(GC gc, Rectangle plot, String text,
                                   double hz, double dbFs, MagnitudeUnit unit,
                                   double freqMin, double freqMax,
                                   double magTop, double magBot,
                                   boolean logFreq) {
        if (!Double.isFinite(hz) || hz < freqMin || hz > freqMax) return;
        if (!Double.isFinite(dbFs)) return;
        double v = Preferences.instance().convertFromDbFs(dbFs, unit);
        double t = magToYFraction(v, magTop, magBot, unit);
        if (t < 0 || t > 1) return;
        int x = freqToX(hz, plot, freqMin, freqMax, logFreq);
        int y = magToY(v, plot, magTop, magBot, unit);
        Point ext = gc.textExtent(text);
        int lx = Math.max(plot.x, Math.min(plot.x + plot.width - ext.x, x - ext.x / 2));
        int ly = Math.max(plot.y, y - ext.y - 4);
        drawOutlinedText(gc, text, lx, ly);
    }

    /** Paints F1, F2 and the per-order dnL / dnH IMD-product dots +
     *  labels on the spectrum.  Same visual treatment as
     *  {@link #drawHarmonicDots} so the user sees consistent marker
     *  styling across THD and IMD modes — red dots on every measured
     *  spike, blue "before-cal" dots underneath when one or more
     *  calibration files are loaded.  Dots that fall outside the
     *  visible mag range are skipped (the {@link #plotDotAt} helper
     *  handles the bounds check). */
    private void drawImdDots(GC gc, Rectangle plot, ImdResult imd,
                             FftResult r,
                             MagnitudeUnit unit,
                             double freqMin, double freqMax,
                             double magTop, double magBot,
                             boolean logFreq) {
        if (r == null) return;
        Preferences prefs = Preferences.instance();
        // dBV → dBFs is the fixed global ADC offset (dBFs = dBV − offset) — the
        // same constant for every bin, not a per-result fundamental delta.
        double refDbV = prefs.getDbvOffsetDb();

        // Aggregate every dot's (freq, post-cal dBFs) so the blue
        // pre-cal and red post-cal passes walk the same list.  Order:
        // F1, F2, dnL[2..5], dnH[2..5].
        int count = 2 + 2 * (ImdResult.MAX_ORDER - 1);
        double[] hz   = new double[count];
        double[] dbFs = new double[count];
        hz[0]   = imd.f1Hz;  dbFs[0] = imd.f1DbV - refDbV;
        hz[1]   = imd.f2Hz;  dbFs[1] = imd.f2DbV - refDbV;
        int idx = 2;
        for (int k = 2; k <= ImdResult.MAX_ORDER; k++) {
            hz[idx]   = imd.dnLHz[k];
            dbFs[idx] = imd.dnLDbV[k] - refDbV;
            idx++;
            hz[idx]   = imd.dnHHz[k];
            dbFs[idx] = imd.dnHDbV[k] - refDbV;
            idx++;
        }

        // Blue "before-cal" dots first so the red post-cal dots sit on
        // top.  Only drawn when at least one calibration file is
        // loaded — same gate the THD path uses (preCorrectionPeaks
        // null vs. non-null).
        List<FreqRespCorrectionStore.Entry> calEntries =
                correctionStore.getEntries();
        if (!calEntries.isEmpty()) {
            boolean wantLeft = prefs.getFftChannel() == Channel.L;
            gc.setBackground(color(ColorRole.BEFORE_CAL_DOT));
            for (int i = 0; i < count; i++) {
                double preDbFs = dbFs[i] + sumCalDbAt(calEntries, wantLeft, hz[i]);
                plotDotAt(gc, plot, hz[i], preDbFs,
                        unit, freqMin, freqMax, magTop, magBot, logFreq);
            }
        }

        // Red post-cal dots on every measured spike.
        gc.setBackground(color(ColorRole.HARMONIC_DOT));
        for (int i = 0; i < count; i++) {
            plotDotAt(gc, plot, hz[i], dbFs[i],
                    unit, freqMin, freqMax, magTop, magBot, logFreq);
        }

        // DEBUG overlay: mains comb response (red), anchored at d2L's level
        // (the order-2 lower IMD product — H2 isn't present in IMD mode).
        drawMainsResponse(gc, plot, r, imd.dnLDbV[2] - refDbV, unit,
                freqMin, freqMax, magTop, magBot, logFreq);

        // F1 / F2 labels: each anchored just above its own dot, but if
        // the two labels would overlap the lower-dot's label slides up
        // — either into the gap between the higher dot's label and
        // the lower dot (when there's room), or stacked one line above
        // the higher dot's label (when too cramped).  Both labels stay
        // strictly above their own dots so the dot itself is never
        // obscured.
        gc.setForeground(color(ColorRole.HARMONIC_DOT));
        Point p1 = dotScreenPos(plot, imd.f1Hz, imd.f1DbV - refDbV, unit, freqMin, freqMax,
                magTop, magBot, logFreq);
        Point p2 = dotScreenPos(plot, imd.f2Hz, imd.f2DbV - refDbV, unit, freqMin, freqMax,
                magTop, magBot, logFreq);
        if (p1 != null || p2 != null) {
            String t1 = "F1 " + formatFrequency(imd.f1Hz);
            String t2 = "F2 " + formatFrequency(imd.f2Hz);
            Point ext1 = gc.textExtent(t1);
            Point ext2 = gc.textExtent(t2);
            final int margin = 4;     // dot-to-label vertical gap
            final int gap    = 2;     // label-to-label vertical gap
            int ly1 = (p1 != null) ? p1.y - margin - ext1.y : 0;
            int ly2 = (p2 != null) ? p2.y - margin - ext2.y : 0;
            if (p1 != null && p2 != null) {
                int bot1 = ly1 + ext1.y;
                int bot2 = ly2 + ext2.y;
                boolean overlap = !(bot1 + gap <= ly2 || bot2 + gap <= ly1);
                if (overlap) {
                    if (p1.y <= p2.y) {
                        // F1 at same height or higher than F2: keep F1 label
                        // anchored above F1 dot; try to fit F2 label
                        // between F1's bottom and F2's dot.
                        int candTop = bot1 + gap;
                        int candBot = candTop + ext2.y;
                        ly2 = (candBot + margin <= p2.y)
                                ? candTop
                                : ly1 - gap - ext2.y;
                    } else {
                        // F2 dot higher than F1 dot — mirror of the above.
                        int candTop = bot2 + gap;
                        int candBot = candTop + ext1.y;
                        ly1 = (candBot + margin <= p1.y)
                                ? candTop
                                : ly2 - gap - ext1.y;
                    }
                }
            }
            if (p1 != null) {
                int lx = Math.max(plot.x,
                        Math.min(plot.x + plot.width - ext1.x, p1.x - ext1.x / 2));
                drawOutlinedText(gc, t1, lx, Math.max(plot.y, ly1));
            }
            if (p2 != null) {
                int lx = Math.max(plot.x,
                        Math.min(plot.x + plot.width - ext2.x, p2.x - ext2.x / 2));
                drawOutlinedText(gc, t2, lx, Math.max(plot.y, ly2));
            }
        }

        // dnL (lower sideband) + dnH (upper sideband) labels for
        // n = 2..5.  Frequency anchors are imd.dnLHz / dnHHz; level
        // anchors come from the dnLDbV / dnHDbV arrays converted back
        // to dBFs via refDbV.
        for (int k = 2; k <= ImdResult.MAX_ORDER; k++) {
            double lDbFs = imd.dnLDbV[k] - refDbV;
            double hDbFs = imd.dnHDbV[k] - refDbV;
            drawHarmonicLabel(gc, plot, "d" + k + "L",
                    imd.dnLHz[k], lDbFs, unit, freqMin, freqMax,
                    magTop, magBot, logFreq);
            drawHarmonicLabel(gc, plot, "d" + k + "H",
                    imd.dnHHz[k], hDbFs, unit, freqMin, freqMax,
                    magTop, magBot, logFreq);
        }
    }

    /** Returns the cumulative calibration dB lift at {@code freqHz} —
     *  i.e. how many dB the calibration cascade adds at this freq when
     *  going from raw to corrected.  Used to recover the pre-cal level
     *  (BLUE dot) at a known post-cal level by adding this value back
     *  in.  Empty cal list ⇒ 0 dB.  Instance method (per project
     *  preference) rather than static.  Same dB-cascade math
     *  {@code FftAnalyzerWorker.sumCalDb} uses for the THD-path blue
     *  dots; kept local here so the IMD path doesn't reach into the
     *  worker's private helpers. */
    private double sumCalDbAt(List<FreqRespCorrectionStore.Entry> calEntries,
                              boolean wantLeft, double freqHz) {
        if (!(freqHz > 0)) return 0.0;
        double sum = 0.0;
        for (FreqRespCorrectionStore.Entry e : calEntries) {
            FreqRespCalibration cal = wantLeft
                    ? e.getCalibration().left()
                    : e.getCalibration().right();
            double m = FreqRespCalHelper.interpolate(cal, freqHz)[0];
            sum += (m > 0.0) ? 20.0 * Math.log10(m) : -300.0;
        }
        return sum;
    }

    private void plotDotAt(GC gc, Rectangle plot, double hz, double dbFs,
                           MagnitudeUnit unit,
                           double freqMin, double freqMax,
                           double magTop, double magBot,
                           boolean logFreq) {
        if (!Double.isFinite(hz) || hz < freqMin || hz > freqMax) return;
        if (!Double.isFinite(dbFs)) return;
        Preferences prefs = Preferences.instance();
        double v = prefs.convertFromDbFs(dbFs, unit);
        // Drop the dot entirely when its magnitude is outside the
        // visible range — dots clamped to the edge would otherwise
        // appear as misleading markers at the chart's top / bottom.
        double t = magToYFraction(v, magTop, magBot, unit);
        if (t < 0 || t > 1) return;
        int x = freqToX(hz, plot, freqMin, freqMax, logFreq);
        int y = magToY(v, plot, magTop, magBot, unit);
        int diameter = Math.max(2, prefs.getFftHarmonicDotDiameter());
        int radius   = diameter / 2;
        gc.fillOval(x - radius, y - radius, diameter, diameter);
    }

    /** Mixes every value that affects the cached image into a single
     *  long.  Identity-hash of {@link FftResult} captures
     *  "new analysis result" without copying the arrays; the rest of
     *  the fields are simple primitives.  Two paints with the same
     *  fingerprint always produce the same cached image. */
    /** Static-trace-layer cache key.  Lombok {@code @EqualsAndHashCode} derives
     *  the fingerprint hash from every rendering input — adding an input is
     *  adding a field, with no hand-rolled hash chain to forget it in.  Fields
     *  without a {@code hashCode} override (results) contribute their identity
     *  hash, matching the previous behaviour. */
    @RequiredArgsConstructor
    @EqualsAndHashCode
    private static final class TraceFingerprint {
        private final FftResult result;
        /** Ticks once per published result, so every analysis fingerprints
         *  fresh.  The result's identityHashCode ALONE turned out not to be
         *  reliable in long runs — under sustained GC churn the hash of
         *  consecutive Result objects could repeat for stretches lasting
         *  seconds-to-minutes, causing the static trace cache to blit a stale
         *  image even though the worker was producing fresh results (visible
         *  as a frozen chart with the averages counter still ticking). */
        private final long completedAnalyses;
        /** IMD slot — a form-driven null ↔ ImdResult transition must
         *  invalidate the cached image, otherwise the F1 / F2 / dnL / dnH dots
         *  linger from the previous mode until the next result lands. */
        private final ImdResult imd;
        private final int width;
        private final int height;
        private final MagnitudeUnit unit;
        private final double freqMin;
        private final double freqMax;
        private final double magTop;
        private final double magBot;
        private final boolean logFreq;
        private final boolean distMinEnabled;
        private final boolean distMaxEnabled;
        private final double distMinHz;
        private final double distMaxHz;
        // Appearance prefs — when these change the cached trace image would
        // otherwise still show the old colour / width.
        private final double lineWidth;
        private final int harmonicDotDiameter;
        private final int lineColor;
        private final int chartBackgroundColor;
        private final int harmonicDotColor;
        private final int beforeCalDotColor;
        private final int freqRespColor;
        private final int calOverlayColor;
        /** Cal entry list — adding / removing a row (or flipping withNoise)
         *  would otherwise leave the overlay curve stale until the next FFT
         *  result swaps the result reference. */
        private final List<FreqRespCorrectionStore.Entry> calEntries;
    }

    private long computeTraceFingerprint(FftResult r, Rectangle area,
                                         MagnitudeUnit unit,
                                         double freqMin, double freqMax,
                                         double magTop, double magBot,
                                         boolean logFreq) {
        Preferences prefs = Preferences.instance();
        return new TraceFingerprint(
                r, worker.getCompletedAnalyses(), lastImd,
                area.width, area.height, unit,
                freqMin, freqMax, magTop, magBot, logFreq,
                prefs.isFftDistMinEnabled(), prefs.isFftDistMaxEnabled(),
                prefs.getFftDistMinHz(), prefs.getFftDistMaxHz(),
                prefs.getFftLineWidth(), prefs.getFftHarmonicDotDiameter(),
                prefs.getFftLineColor(), prefs.getFftChartBackgroundColor(),
                prefs.getFftHarmonicDotColor(), prefs.getFftBeforeCalDotColor(),
                prefs.getFftFreqRespColor(), prefs.getFftCalOverlayColor(),
                correctionStore.getEntries()).hashCode();
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
    /** Display label for a magnitude unit — physics-unit symbols held in i18n (same in
     *  every language) so the enum itself stays label-free. */
    private String magUnitLabel(MagnitudeUnit unit) {
        return I18n.t("unit.mag." + unit.name());
    }

    /** Combo items for the magnitude-unit selector, in {@code values()} order
     *  (the combo binds by index). */
    private String[] magUnitLabels() {
        MagnitudeUnit[] all = MagnitudeUnit.values();
        String[] out = new String[all.length];
        for (int i = 0; i < all.length; i++) out[i] = magUnitLabel(all[i]);
        return out;
    }

    private void drawSpectrum(GC gc, Rectangle plot, FftResult r,
                              MagnitudeUnit unit,
                              double freqMin, double freqMax,
                              double magTop, double magBot,
                              boolean logFreq) {
        if (r.amplitudeDbFs == null) return;
        Preferences prefs = Preferences.instance();
        gc.setForeground(color(ColorRole.SPECTRUM));
        setTraceLineAttributes(gc, (float) prefs.getFftLineWidth(), SWT.LINE_SOLID);
        double binBw   = r.freqResolution;
        int n = r.amplitudeDbFs.length;
        // Manual-fundamental: lift the fundamental's WHOLE main lobe to the user
        // value — not just its peak bin (that left a narrow spike on the
        // unlifted lobe) — PROPORTIONALLY above the local noise floor so the
        // wings stay on the floor.  Lobe + floor read from the data via the
        // shared ToneLobeLift; applied as a per-bin dBFS anchor so it works in
        // every magnitude unit.
        int    lobeLo = -1, lobeHi = -1;
        double floorLin = 0.0, liftFactor = 1.0, peakMagLin = 0.0;
        if (Double.isFinite(r.fundamentalTrueDbFs)
                && Double.isFinite(r.fundamentalDbFs)
                && Double.isFinite(r.fundamentalHzRefined)
                && binBw > 0) {
            int peak = (int) Math.round(r.fundamentalHzRefined / binBw);
            int half = n - 1;
            if (peak >= 1 && peak <= half) {
                final double[] dbfs = r.amplitudeDbFs;
                IntToDoubleFunction mag = k -> Math.pow(10.0, dbfs[k] / 20.0);
                floorLin = LOBE.localFloor(mag, peak, half);
                int[] edges = LOBE.lobeBins(mag, peak, half, floorLin);
                lobeLo = edges[0];
                lobeHi = edges[1];
                peakMagLin = mag.applyAsDouble(peak);
                double targetDbFs = r.fundamentalTrueDbFs;   // manual fundamental, already dBFS
                liftFactor = Math.pow(10.0, (targetDbFs - dbfs[peak]) / 20.0);
            }
        }
        int kFirst = Math.max(1,  (int) Math.floor(freqMin / binBw));
        int kLast  = Math.min(n - 1, (int) Math.ceil (freqMax / binBw));
        if (kLast <= kFirst) return;
        // Expand by one bin on either side so the trace extends ALL
        // the way to plot.x / plot.x + width.  Without this, the
        // first / last visible bins draw at an inset (their freqToX
        // sits a few pixels inside the plot edge) and the chart looks
        // like it has empty strips on the left and right.
        // kPrev starts at 1 — never include the DC bin in iteration;
        // its zero-magnitude clamps to plot.bottom and on LOG scale
        // (where freqToX clamps any f < freqMin to plot.x) would
        // pollute column 0's yMax, drawing a fat triangle at the
        // left edge of the polygon envelope.
        int kPrev = Math.max(1,     kFirst - 1);
        int kNext = Math.min(n - 1, kLast  + 1);

        // Column-bucketed polyline rendering — see ColumnBucketPainter
        // in the shared base.  FFT also tracks one bin JUST outside the
        // visible range on each side so the polyline reaches the chart
        // edges; the helper exposes setLeftAnchor / setRightAnchor for
        // exactly that.
        ColumnBucketPainter painter = new ColumnBucketPainter(plot);
        for (int k = kPrev; k <= kNext; k++) {
            double f = k * binBw;
            int xAbs = freqToX(f, plot, freqMin, freqMax, logFreq);
            double binDbFs = r.amplitudeDbFs[k];
            if (lobeLo >= 0 && k >= lobeLo && k <= lobeHi) {
                double m = Math.pow(10.0, binDbFs / 20.0);
                double newMag = LOBE.stretch(m, floorLin, peakMagLin, liftFactor);
                binDbFs = newMag > 1e-15 ? 20.0 * Math.log10(newMag) : -300.0;
            }
            double v = prefs.convertFromDbFs(binDbFs, unit, r.binBwSqrt);
            int y = magToYTrace(v, plot, magTop, magBot, unit);
            // Route by frequency, not pixel: on LOG scale freqToX
            // clamps any sub-freqMin bin to plot.x, so an x-based test
            // would silently add off-screen bins to column 0 with
            // their own y values — that's the triangle artefact.
            if (f < freqMin)        painter.setLeftAnchor(xAbs, y);
            else if (f > freqMax)   painter.setRightAnchor(xAbs, y);
            else                    painter.add(xAbs, y);
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
    private void drawCalOverlay(GC gc, Rectangle plot, FftResult r,
                                MagnitudeUnit unit,
                                double freqMin, double freqMax,
                                double magTop, double magBot,
                                boolean logFreq) {
        List<FreqRespCorrectionStore.Entry> entries =
                correctionStore.getEntries();
        if (entries.isEmpty()) return;
        Preferences prefs = Preferences.instance();
        // Anchor at H2 in THD mode, or at d2L (the order-2 lower IMD product) in
        // IMD mode — H2 isn't marked there.
        double anchorFreq, anchorDbFs;
        if (lastImd != null && lastImd.dnLHz != null && lastImd.dnLHz.length > 2
                && Double.isFinite(lastImd.dnLDbV[2])) {
            double ref = prefs.getDbvOffsetDb();   // dBFs = dBV − global ADC offset
            anchorFreq = lastImd.dnLHz[2];
            anchorDbFs = lastImd.dnLDbV[2] - ref;
        } else if (r.harmonicCount > 0 && r.harmonicBins != null
                && r.harmonicBins.length > 0 && r.harmonicBins[0] > 0) {
            anchorFreq = r.harmonicHz[0];
            anchorDbFs = r.harmonicDbFs[0];
        } else {
            return;
        }
        if (!(anchorFreq > 0.0) || !Double.isFinite(anchorDbFs)) return;

        // Match the cal channel to whichever channel the FFT is
        // currently analysing — using .left() unconditionally would
        // mis-compensate the signal when the user picks the right
        // channel (their L/R cal curves are not identical).
        boolean wantLeft = prefs.getFftChannel() == Channel.L;
        double sumDbAtAnchor = 0.0;
        for (FreqRespCorrectionStore.Entry e : entries) {
            FreqRespCalibration cal = wantLeft
                    ? e.getCalibration().left()
                    : e.getCalibration().right();
            double m = FreqRespCalHelper.interpolate(cal, anchorFreq)[0];
            sumDbAtAnchor += (m > 0.0) ? 20.0 * Math.log10(m) : -300.0;
        }
        double offset = anchorDbFs + sumDbAtAnchor;

        FreqRespCalibration first = wantLeft
                ? entries.get(0).getCalibration().left()
                : entries.get(0).getCalibration().right();
        double[] freqs = first.freqs;

        gc.setForeground(color(ColorRole.CAL_OVERLAY));
        setTraceLineAttributes(gc, (float) prefs.getFftLineWidth(), SWT.LINE_SOLID);
        ColumnBucketPainter painter = new ColumnBucketPainter(plot);
        int W = plot.width;
        for (int i = 0; i < freqs.length; i++) {
            double f = freqs[i];
            if (!(f > 0.0)) continue;
            double sumDb = 0.0;
            for (FreqRespCorrectionStore.Entry e : entries) {
                FreqRespCalibration cal = wantLeft
                        ? e.getCalibration().left()
                        : e.getCalibration().right();
                double m = (cal == first)
                        ? cal.magLin[i]
                        : FreqRespCalHelper.interpolate(cal, f)[0];
                sumDb += (m > 0.0) ? 20.0 * Math.log10(m) : -300.0;
            }
            double dbFs = -sumDb + offset;
            double v = prefs.convertFromDbFs(dbFs, unit);
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

    /** Shows the data-only buttons (distortion / reset / external + their spacer) when a
     *  result is present, hides them otherwise — re-laying out {@link #headerBar}.  Driven
     *  by the result-arrival / reset hooks, never by paint. */
    private void syncDataButtons() {
        boolean hasData = lastResult != null;
        // External only makes sense while the table itself shows (distortion ON).
        boolean extVisible = hasData && Preferences.instance().isFftDistortionTableVisible();
        if (hasData == dataButtonsShown && extVisible == externalShown) {
            return;
        }
        dataButtonsShown = hasData;
        externalShown    = extVisible;
        dataSpacer.setExcluded(!hasData);
        distortionBtn.setExcluded(!hasData);
        resetBtn.setExcluded(!hasData);
        externalBtn.setExcluded(!extVisible);
        headerBar.reflow();   // re-size the bar (its width changed), then re-flow the row
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
    private int drawDistortionTable(GC gc, FftResult r, MagnitudeUnit unit) {
        return drawDistortionTable(gc, r, unit, MARGIN_LEFT + 6, TABLE_TOP_Y, true);
    }

    /** Draws the dual-tone IMD measurement table.  Replaces the THD
     *  table in the same on-canvas region when the generator is in
     *  DUAL_TONE.  Layout (top to bottom):
     *  <ul>
     *    <li>Two centred lines for F1, F2 fundamentals
     *        ({@code <freq>  <dBFS>  <dBV>}).</li>
     *    <li>A centred Span line (same format the THD path uses).</li>
     *    <li>Two Δf lines (one per tone) — only when "Get fundamental
     *        from generator" is checked AND the generator is running.</li>
     *    <li>IMDpwr / TD+N row, DFD2 / DFD3 row.</li>
     *    <li>d2L..d5L (lower sidebands) and d2H..d5H (upper sidebands),
     *        two per row.</li>
     *  </ul> */
    private void drawImdTable(GC gc, ImdResult imd, int xLeft, int yTop) {
        Preferences prefs = Preferences.instance();
        gc.setFont(monoFont);
        gc.setForeground(color(ColorRole.TEXT));
        int y     = yTop;
        int lineH = gc.textExtent("M").y + 1;
        int charW = gc.textExtent("M").x;

        int tableW  = 64 * charW;
        int centreX = xLeft + tableW / 2;

        // ── Centred F1 / F2 headers (bold). ────────────────────────────
        gc.setFont(monoBoldFont);
        double dbvOffsetDb = prefs.getDbvOffsetDb();   // dBFs = dBV − global ADC offset
        drawCentred(gc, String.format("F1: %.4f Hz   %.2f dBFS   %.2f dBV",
                imd.f1Hz, imd.f1DbV - dbvOffsetDb, imd.f1DbV), centreX, y);
        y += lineH;
        drawCentred(gc, String.format("F2: %.4f Hz   %.2f dBFS   %.2f dBV",
                imd.f2Hz, imd.f2DbV - dbvOffsetDb, imd.f2DbV), centreX, y);
        y += lineH;

        // ── Span line (re-uses the THD format from a Result if any). ──
        if (lastResult != null) {
            drawCentred(gc, formatSpan(lastResult), centreX, y);
            y += lineH;
        }

        // ── Δf1 / Δf2 (per-tone clock drift).  Only meaningful when
        // the generator is running AND fund-from-gen is on — same gate
        // the THD table uses for its ΔF row.
        boolean genActive = isGeneratorActive();
        if (genActive && prefs.isFftFundFromGenerator() && lastResult != null) {
            int sr = lastResult.sampleRate;
            double f1Cmd = FftBinSnap.snapIfEnabled(prefs, GenSignalForm.DUAL_TONE, sr,
                    prefs.getGenDualToneFreq1Hz());
            double f2Cmd = FftBinSnap.snapIfEnabled(prefs, GenSignalForm.DUAL_TONE, sr,
                    prefs.getGenDualToneFreq2Hz());
            drawCentred(gc, formatDeltaF("Δf1", f1Cmd, imd.f1Hz, sr), centreX, y);
            y += lineH;
            drawCentred(gc, formatDeltaF("Δf2", f2Cmd, imd.f2Hz, sr), centreX, y);
            y += lineH;
        }
        gc.setFont(monoFont);
        y += 2;

        // ── 2-col metric rows: IMDpwr / TD+N, DFD2 / DFD3. ─────────────
        int colGap = 2 * charW;
        int mKeyL  = 8  * charW;       // "IMDpwr:" / "DFD2:"
        int mValL  = 14 * charW;
        int mKeyR  = 7  * charW;       // "TD+N:" / "DFD3:"
        int mValR  = 14 * charW;
        int mRight = xLeft + mKeyL + mValL + colGap;
        drawKv(gc, xLeft, y, mKeyL,
                "IMDpwr:", String.format("%.8f %%", imd.imdPwrPct),
                mRight, mKeyR,
                "TD+N:",   String.format("%.8f %%", imd.tdnPct));
        y += lineH;
        drawKv(gc, xLeft, y, mKeyL,
                "DFD2:", String.format("%.8f %%", imd.dfd2Pct),
                mRight, mKeyR,
                "DFD3:", String.format("%.8f %%", imd.dfd3Pct));
        y += lineH + 2;

        // ── dnL / dnH rows, two per line. ──────────────────────────────
        int dKey = 5  * charW;         // "d5L:"
        int dVal = 26 * charW;         // " -108.42 dBV  0.00045123 %"
        int dRight = xLeft + dKey + dVal + colGap;
        for (int k = 2; k <= ImdResult.MAX_ORDER; k++) {
            String lKey = String.format("d%dL:", k);
            String lVal = String.format("%8.2f dBV  %.8f %%", imd.dnLDbV[k], imd.dnLPct[k]);
            String rKey = String.format("d%dH:", k);
            String rVal = String.format("%8.2f dBV  %.8f %%", imd.dnHDbV[k], imd.dnHPct[k]);
            drawKv(gc, xLeft, y, dKey, lKey, lVal, dRight, dKey, rKey, rVal);
            y += lineH;
        }
        // Suppress unused-variable warning on mValR (kept for symmetry
        // with the THD table's layout if we later switch to right-
        // alignment).
        if (mValR < 0) gc.setForeground(color(ColorRole.TEXT));
    }

    /** Formats one Δf line for the IMD table, mirroring the THD
     *  path's "ΔF: %+.6f Hz (%+.2f ppm)  Δosc: %+.2f Hz @ 22.5792 MHz"
     *  shape so the user reads the same numbers in both modes. */
    private String formatDeltaF(String label, double expectedHz, double measuredHz, int sampleRate) {
        double delta = measuredHz - expectedHz;
        double ppm   = (expectedHz > 0) ? 1e6 * delta / expectedHz : Double.NaN;
        double osc;
        String oscName;
        if (sampleRate > 0 && sampleRate % 44100 == 0) {
            osc = 22.5792e6; oscName = "22.5792 MHz";
        } else if (sampleRate > 0 && sampleRate % 48000 == 0) {
            osc = 24.576e6;  oscName = "24.576 MHz";
        } else {
            osc = Double.NaN; oscName = "?";
        }
        if (Double.isNaN(osc)) {
            return String.format("%s: %+.6f Hz (%+.2f ppm)", label, delta, ppm);
        }
        double oscDelta = osc * delta / expectedHz;
        return String.format("%s: %+.6f Hz (%+.2f ppm)  Δosc: %+.2f Hz @ %s",
                label, delta, ppm, oscDelta, oscName);
    }

    /** Renders the THD table.  Caller controls the {@code xLeft} / {@code yTop}
     *  origin so the extracted tool window can place the table at (0,0)
     *  instead of leaving room for the header buttons that are only
     *  drawn in the main FFT view. */
    private int drawDistortionTable(GC gc, FftResult r, MagnitudeUnit unit,
                                     int xLeft, int yTop, boolean includeClockRow) {
        Preferences prefs = Preferences.instance();
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
        double thdMaxH = prefs.getFftThdMaxHarmonic();
        gc.setFont(monoBoldFont);
        // dBV column: the manual fundamental (fundamentalTrueDbFs) when set,
        // else the measured fundamental — both lifted to dBV by the same global
        // ADC offset every other bin uses.  The dBFS column stays the measured
        // level.
        double dbvOffsetDb = prefs.getDbvOffsetDb();
        double fundDbV = (Double.isFinite(r.fundamentalTrueDbFs)
                ? r.fundamentalTrueDbFs : r.fundamentalDbFs) + dbvOffsetDb;
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
        int hKey = 4  * charW;         // "H10:" + ":"
        int hVal = 24 * charW;         // covers "-109.72 dBV 0.00031899 %"
        int hRightColX = xLeft + hKey + hVal + colGap;
        int harmCount = (r.harmonicDbFs == null) ? 0 : r.harmonicDbFs.length;
        double dbvOff = prefs.getDbvOffsetDb();   // dBV = dBFs + global ADC offset
        for (int i = 0; i < harmCount; i += 2) {
            String l = String.format("H%d:", i + 2);
            String lv = String.format("%8.2f dBV %.8f %%",
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
        return y;
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
        drawOutlinedText(gc, lKey, x, y);
        drawOutlinedText(gc, lVal, x + lKeyW, y);
        if (rKey != null && !rKey.isEmpty()) {
            drawOutlinedText(gc, rKey, rightColX, y);
            drawOutlinedText(gc, rVal, rightColX + rKeyW, y);
        }
    }

    /** Formats the noise-integration band shown on the header span row.
     *  When both bounds are zero (the FftAnalyzer default = no band
     *  limit) the analyser integrates over the full spectrum, so report
     *  "full" rather than the misleading {@code "Span: 0 .. 0 Hz"}.
     *  Otherwise fall back to the actual numeric bounds, treating zero
     *  on one side as "0" or "Nyquist" as appropriate. */
    private String formatSpan(FftResult r) {
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
        drawOutlinedText(gc, text, centreX - ext.x / 2, y);
    }

    private String fmtDb(double v) {
        return Double.isFinite(v) ? String.format("%7.2f dBV", v) : "—";
    }

    private double noiseDb(FftResult r) {
        if (r.noisePower <= 0) return Double.NaN;
        // 10·log10(noisePower) is the noise floor in dBFS; lift to dBV by the
        // global ADC offset.
        return 10 * Math.log10(r.noisePower) + Preferences.instance().getDbvOffsetDb();
    }

    private double thdNPct(FftResult r) {
        if (!Double.isFinite(r.thdNDb)) return Double.NaN;
        return Math.pow(10, r.thdNDb / 20.0) * 100;
    }

    // =========================================================================
    // Crosshair + readout
    // =========================================================================

    private void drawCrosshair(GC gc, Rectangle plot, FftResult r,
                               MagnitudeUnit unit,
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
                // The fundamental bin reads its manual-override level so the crosshair
                // matches the displayed (lobe-lifted) trace; other bins read straight.
                double dbFs = r.amplitudeDbFs[bin];
                if (Double.isFinite(r.fundamentalTrueDbFs)
                        && Double.isFinite(r.fundamentalHzRefined)
                        && r.freqResolution > 0
                        && bin == (int) Math.round(r.fundamentalHzRefined / r.freqResolution)) {
                    dbFs = r.fundamentalTrueDbFs;   // manual fundamental, already dBFS
                }
                double v = Preferences.instance().convertFromDbFs(dbFs, unit, r.binBwSqrt);
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
        if (distortionWindow == null) return;
        Point contentSz = computeExternalContentSize();
        distortionWindow.setSize(contentSz.x, contentSz.y);
    }

    public void setTableExtracted(boolean extracted) {
        if (extracted == tableExtracted) return;
        tableExtracted = extracted;
        externalBtn.setToggled(extracted);
        syncExternalShell();
        redraw();
    }

    private void syncExternalShell() {
        // External window hosts the extracted distortion table — THD or IMD,
        // matching the main canvas's sticky table mode.  Open it whenever the
        // table is extracted and visible; the painter picks THD vs IMD.
        boolean wantOpen = tableExtracted
                && Preferences.instance().isFftDistortionTableVisible();
        if (wantOpen && distortionWindow == null) {
            createToolWindow();
        } else if (!wantOpen && distortionWindow != null) {
            distortionWindow.dispose();
            distortionWindow = null;
        } else if (wantOpen) {
            // Table mode may have flipped (THD ↔ IMD) while open — retitle + refit.
            distortionWindow.setTitle(externalTitle());
            resizeExternalShellToContent();
        }
    }

    /** Title for the extracted table window — "IMD …" in dual-tone mode,
     *  "THD …" otherwise.  Every locale keeps the acronym literal, so a
     *  THD→IMD swap localises correctly without a separate message key. */
    private String externalTitle() {
        String t = I18n.t("fft.external.window.title");
        return tableModeIsImd ? t.replace("THD", "IMD") : t;
    }

    private void createToolWindow() {
        ToolWindow w = new ToolWindow(this, color(ColorRole.BACKGROUND), color(ColorRole.TEXT), BTN_W, BTN_H);
        w.setTitle(externalTitle());
        w.setPainter(this::paintDistortion);
        w.addCloseListener(e -> setTableExtracted(false));
        distortionWindow = w;
        // Size to content (harmonic-row width + table height; no button row — the reset /
        // distortion / external toggles stay in the main FFT view), then place it at the
        // parent shell's top-right.
        Point contentSz = computeExternalContentSize();
        w.setSize(contentSz.x, contentSz.y);
        Point ws = w.getSize();
        Rectangle pb = getShell().getBounds();
        w.setLocation(pb.x + pb.width - ws.x - 24, pb.y + 96);
        w.open();
    }

    /** Computes the natural client size of the extracted THD table —
     *  used by {@link #createToolWindow} so the tool window fits its
     *  content exactly (no excess whitespace, no clipping).  Width comes
     *  from the harmonic-row layout (the widest row in the table) and
     *  height counts the fixed rows + dynamic harmonic rows. */
    private Point computeExternalContentSize() {
        ensureFonts();
        Preferences prefs = Preferences.instance();
        GC gc = new GC(this);
        try {
            gc.setFont(monoFont);
            int lineH = gc.textExtent("M").y + 1;
            if (tableModeIsImd) {
                // Rows = F1/F2 + span + optional Δf1/Δf2 + 2 metric rows +
                // (MAX_ORDER−1) dnL/dnH rows.  Width is set by a dnL/dnH row:
                // its right-column value is drawn 38·charW from the left
                // (dKey 5 + dVal 26 + colGap 2 + dKey 5, per drawImdTable), so
                // MEASURE the worst-case value — the mono "M" cell under-counts
                // digit width and the trailing % would otherwise clip.
                int charW = gc.textExtent("M").x;
                String worstVal = String.format("%8.2f dBV %.8f %%", -9999.99, 99.99999999);
                int contentW = EXT_LEFT_PAD + 38 * charW + gc.textExtent(worstVal).x + 34;
                boolean clk = isGeneratorActive()
                        && prefs.isFftFundFromGenerator();
                int rows = 2 + 1 + (clk ? 2 : 0) + 2 + (ImdResult.MAX_ORDER - 1);
                int contentH = EXT_LEFT_PAD + rows * lineH + 8;
                return new Point(contentW, contentH);
            }
            // Measure the worst-case harmonic row directly — same
            // formatting drawDistortionTable uses (key + lVal + 2-space
            // gap + key + rVal) — and add a generous right padding.
            // Earlier attempts using "38·charW + textExtent(value)"
            // under-counted on systems where the mono "M" cell is a
            // pixel narrower than the actual digit / percent glyphs;
            // measuring the entire row text removes that gap.
            String widestRow = String.format(
                    "H10: %+8.2f dBV %.8f %%  H11: %+8.2f dBV %.8f %%",
                    -9999.99, 99.99999999, -9999.99, 99.99999999);
            int rowW = gc.textExtent(widestRow).x;
            int contentW = EXT_LEFT_PAD + rowW + 32;
            // Rows (no top button row in the external window):
            //   header + span + (clock?) + gap + 3 metric rows + gap + harm pairs
            // Pref value N means "compute up to HN" → N − 1 harmonics
            // (H2..HN) → ceil((N − 1) / 2) row pairs.
            int maxH = Math.max(9, prefs.getFftCalcMaxHarmonic());
            int harmRows = ((maxH - 1) + 1) / 2;
            int clockRow = prefs.isFftFundFromGenerator() ? lineH : 0;
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

    /** {@link ToolWindow.ContentPainter} for the extracted distortion window — draws the THD
     *  or IMD table straight into the window's GC, inset from its top-left so the keys don't
     *  touch the border ({@code top} is 0 here: no button row, those toggles stay in the
     *  main FFT view). */
    private void paintDistortion(GC gc, int top) {
        if (lastResult == null) return;
        gc.setAntialias(SWT.ON);
        gc.setTextAntialias(SWT.ON);
        ensureFonts();
        int y = top + EXT_LEFT_PAD;
        if (tableModeIsImd && lastImd != null) {
            drawImdTable(gc, lastImd, EXT_LEFT_PAD, y);
        } else {
            MagnitudeUnit unit = Preferences.instance().getFftMagUnit();
            drawDistortionTable(gc, lastResult, unit, EXT_LEFT_PAD, y, true);
        }
    }

    // =========================================================================
    // Mouse handlers
    // =========================================================================

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
     *  The range is canonical dBFS (linear in dB) for every unit, so the zoom
     *  is a plain linear scale. */
    private void zoomMagnitudeAroundCursor(int mouseY, Rectangle plot, int dir) {
        Preferences prefs = Preferences.instance();
        double top = prefs.getFftMagTop();
        double bot = prefs.getFftMagBottom();
        if (top - bot <= 0) return;
        double frac = (double) (mouseY - plot.y) / plot.height;
        if (frac < 0) frac = 0; else if (frac > 1) frac = 1;
        double scale = (dir > 0) ? 0.8 : 1.25;
        double anchor  = top - frac * (top - bot);
        double newSpan = (top - bot) * scale;
        prefs.setFftMagTop(anchor + frac       * newSpan);
        prefs.setFftMagBottom(anchor - (1 - frac) * newSpan);
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
        double top = prefs.getFftMagTop();
        double bot = prefs.getFftMagBottom();
        double maxTp = magCeiling();              // dBFS ceiling (≥ 0, raised by a lifted signal)
        double minBt = Constants.MAG_FLOOR_DBFS;  // dBFS floor
        // Range is dBFS (linear in dB) for every unit — pan additively, clipping at the
        // limits so neither bound overruns.
        double step = (top - bot) * 0.1 * dir;
        if (step > 0 && top + step > maxTp) step = maxTp - top;
        else if (step < 0 && bot + step < minBt) step = minBt - bot;
        prefs.setFftMagTop(top + step);
        prefs.setFftMagBottom(bot + step);
        prefs.save();
        fireRangeChanged();
        redraw();
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
        redraw();
    }

    // =========================================================================
    // Coordinate / formatting helpers
    // =========================================================================

    // freqToX / xToFreq / dbToY / magToY / magToYTrace all live on the shared
    // AbstractFreqDomainView base.

    // =========================================================================
    // Resource lazy initialisation
    // =========================================================================

    private void ensureFonts() {
        Display d = getDisplay();
        if (monoFont == null)       monoFont       = new Font(d, "Consolas", 9, SWT.NORMAL);
        if (monoBoldFont == null)   monoBoldFont   = new Font(d, "Consolas", 9, SWT.BOLD);
        if (chanButtonFont == null) chanButtonFont = new Font(d, "Consolas", 12, SWT.BOLD);
    }

}
