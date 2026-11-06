/*
 * Phonalyser - precision audio measurement workbench.
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntToDoubleFunction;
import java.util.function.Supplier;

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
import org.edgo.audio.measure.common.Constants;
import org.edgo.audio.measure.common.SpectrumStatistics;
import org.edgo.audio.measure.dsp.FftDsp;
import org.edgo.audio.measure.dsp.FreqRespCalHelper;
import org.edgo.audio.measure.dsp.FreqRespCalibration;
import org.edgo.audio.measure.dsp.InverseCascade;
import org.edgo.audio.measure.dsp.SpectralDiscontinuityDetector;
import org.edgo.audio.measure.dsp.ToneLobeLift;
import org.edgo.audio.measure.enums.AlignGenerator;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.FundamentalPhaseAlignment;
import org.edgo.audio.measure.enums.GenChangeCause;
import org.edgo.audio.measure.enums.MagnitudeUnit;
import org.edgo.audio.measure.enums.WindowType;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.fft.ImdResult;
import org.edgo.audio.measure.fft.ReadoutSettings;
import org.edgo.audio.measure.fft.SpectralLine;
import org.edgo.audio.measure.gui.bind.Bindings;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.AbstractFreqDomainView;
import org.edgo.audio.measure.gui.common.CorrectionStore;
import org.edgo.audio.measure.gui.common.GuiUtil;
import org.edgo.audio.measure.gui.common.DebugSwitches;
import org.edgo.audio.measure.gui.common.Fonts;
import org.edgo.audio.measure.gui.common.GcMeasurementPainter;
import org.edgo.audio.measure.gui.common.Icon;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.widgets.BlinkBanner;
import org.edgo.audio.measure.gui.widgets.ToolButton;
import org.edgo.audio.measure.gui.widgets.ToolWindow;
import org.edgo.audio.measure.gui.widgets.Toolbar;
import org.edgo.audio.measure.gui.widgets.TransparentComposite;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.DeviceRef;

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
 * {@link org.edgo.audio.measure.fft.FftAnalyzer#analyze}, and publishes the latest
 * {@link FftResult} for paint to read.  The owning
 * {@link FftPane} drives the lifecycle ({@link #start} / {@link #stop} /
 * {@link #resetStatistics}) and feeds settings via {@code Preferences}.
 * The view calls its pane directly (the scrollbar re-sync after a pan /
 * zoom).
 */
@Log4j2
public final class FftView extends AbstractFreqDomainView {

    /** Dots of a dual-tone frame: F1, F2, then the lower and the upper
     *  product of each order 2..{@link ImdResult#MAX_ORDER}. */
    private static final int IMD_LINES = 2 + 2 * (ImdResult.MAX_ORDER - 1);
    /** How far (dB) the debug overlay draws the noise gate's lines above each
     *  band's band-top, so they stand clear of the noise tops. */
    private static final double GATE2_LINE_OFFSET_DB = 0.0;
    /** Nanoseconds per millisecond - the render-step timings are stated in ms. */
    private static final double NANOS_PER_MS = 1_000_000.0;
    /** How long the alignment magnitude-drift hint keeps blinking. */
    private static final long ALIGN_DRIFT_WARN_NANOS = 20_000_000_000L;

    // ─── Plot region constants ───────────────────────────────────────────
    /** Pixel margins around the plotting area inside the canvas.
     *  Sized for the widest expected axis label - SI-prefix V values
     *  like "100 mV" / "31.6 µV" - plus a few pixels of breathing room
     *  between the label and the plot frame. */
    private static final int MARGIN_LEFT   = 68;
    /** Zero top margin - the unit caption is drawn INSIDE the plot
     *  area at the top, so the chart frame sits flush against the
     *  pane title (no gap). */
    private static final int MARGIN_TOP    = 0;
    private static final int MARGIN_BOTTOM = 28;
    /** THD table overlay y offset from top of canvas. */
    private static final int TABLE_TOP_Y = 5 + BTN_H + 6;

    /** Left padding (px) for the THD table inside the external window. */
    private static final int EXT_LEFT_PAD = 4;

    /** Units the distortion table's absolute-level cells can render in - a SUBSET of
     *  {@link MagnitudeUnit}: the linear units say nothing about a cell that already
     *  prints a percentage beside the level.  {@code DBR} is the level minus the
     *  reference the table's percentages are computed against. */
    private static final MagnitudeUnit[] DISTORTION_UNITS = {
            MagnitudeUnit.DBV, MagnitudeUnit.DBFS, MagnitudeUnit.DBR };
    /** Labels for {@link #DISTORTION_UNITS}, in that order.  Unit symbols are the same
     *  in every language, so they are literals rather than i18n keys. */
    private static final String[] DISTORTION_UNIT_LABELS = { "dBV", "dBFS", "dB" };
    /** Width (px) of the distortion-unit combo. */
    private static final int DISTORTION_COMBO_W = 36;
    /** Gap (px) from the header bar's right edge - the button row's own spacing
     *  plus the tuning offset that sets the combo apart from the flat buttons. */
    private static final int DISTORTION_COMBO_GAP = 4;
    /** Top inset (px) of the distortion-unit combo, measured like the header
     *  bar's: a native drop-down sits slightly higher than the flat buttons so
     *  their visual centres line up. */
    private static final int DISTORTION_COMBO_TOP = 5;
    /** Floor (full-scale magnitude) under a tone level read in dB, mirroring
     *  the divisor floor in ImdAnalyzer: two muted tones must not divide by
     *  zero, and a muted tone must not read as minus infinity. */
    private static final double MIN_REF_MAG = 1e-12;

    /** A spectrum has a "real" signal only when its measured fundamental sits at
     *  least this far (dB) above the LOCAL noise floor (the grass in its near
     *  range).  Below it, F and the harmonics are just grass (indistinguishable,
     *  F sometimes below H2), so the THD/IMD table and the F/Hn dots are hidden. */
    private static final double SIGNAL_FLOOR_MARGIN_DB = 10.0;
    /** How far below the noise floor (dB) auto setup puts the magnitude
     *  axis bottom - the floor is a band-top, the upper edge of the grass,
     *  so the grass itself stays in view below it. */
    private static final double AUTO_SETUP_BELOW_FLOOR_DB = 30.0;
    /** How far below the noise floor (dB) maximize puts the magnitude axis
     *  bottom - deeper than auto setup, for the widest view. */
    private static final double MAXIMIZE_BELOW_FLOOR_DB = 40.0;

    /** A measured THD above this (%) means the "fundamental" is not a clean tone
     *  but the top of a broadband hump (speech) or noise - the harmonics are as
     *  strong as the fundamental.  The THD/IMD table + F/Hn dots are then hidden
     *  even if the fundamental clears the local floor. */
    private static final double MAX_THD_PCT = 20.0;

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

    /** Controller owning the analyser worker (which runs the frequency-lock
     *  loops) and the IMD analysis - the view issues commands / queries and
     *  renders.
     *  Assigned exactly once in the constructor; NOT {@code final} because
     *  the field-initializer lambda below ({@code onGenChangeForFll}) reads
     *  it and javac's definite-assignment analysis rejects blank-final reads
     *  in initializers. */
    private FftController controller;
    /** The pane that owns this view: the scrollbar re-sync after a pan /
     *  zoom. */
    private final FftPane pane;

    /** Snapshot overrides for the averages count and fill %.  An offscreen
     *  screenshot clone has its OWN idle controller, so without these it would
     *  always read 0 - the screenshot of a live measurement would lose its
     *  "N average(s)" / "NN%" readout.  {@link #copySnapshotFrom} copies the
     *  live pane's current values here; {@code <0} means "not a snapshot, use
     *  the live controller". */
    private int    snapshotCompleted = -1;
    private double snapshotFill      = -1.0;

    /** Loaded {@code .frc} corrections divided out of the spectrum and drawn
     *  as the overlay - the store of the {@link FftController} this view is
     *  built with, the same instance the calibration tab fills. */
    private final CorrectionStore correctionStore;

    /** Stage-3 instrumentation for {@link DebugSwitches#SHOW_FFT_ANALYZE_TIME}:
     *  {@code startRender} is stamped as a fresh result ARRIVES in
     *  {@link #onResultReady}, before the post-average pipeline runs, so the
     *  total covers the whole UI-side path from arrival to the first paint.
     *  {@code gotFftResult} arms that line and every step line, and the
     *  end-of-paint total disarms it again, so only the FIRST repaint of each
     *  result is timed - later repaints are pans / zooms, not result
     *  rendering. */
    private long startRender;
    private boolean gotFftResult = false;

    /** Start of the render step currently being timed - re-stamped by
     *  {@link #logRenderStep} so each step is measured from the end of the one
     *  before it, and again when the first paint of a fresh result begins (the
     *  wait for the render tick in between is not rendering work). */
    private long renderStepMark;

    /** Set by {@link #onResultReady} when a fresh result is ready to paint;
     *  consumed by {@link #renderRealtimeFrame} on the main event loop's render
     *  tick.  The FFT data hand-off stays async (worker -> UI thread), but the
     *  PAINT is loop-driven so the FFT view refreshes on the same cadence as the
     *  scope and neither view can starve the other behind its events. */
    private boolean fftDirty;

    /** Calculates the measurements (THD / SNR / N / fundamental / harmonics,
     *  all units + %) of every rendered spectrum - live frame, average or
     *  loaded file - so every readout matches the plot.  One instance, used on
     *  the UI thread only, so its reused buffers are never shared. */
    private final SpectrumStatistics statistics = new SpectrumStatistics();

    /** Sticky table-mode selector - {@code true} draws the IMD table,
     *  {@code false} draws the THD table.  Updated only inside
     *  {@link #onResultReady} (which fires only when the FFT worker is
     *  actually producing results), so the user's last-recorded mode
     *  persists between sessions and across generator form changes
     *  while the analyser is paused.  Initial value matches the saved
     *  generator form so the chosen table appears immediately on first
     *  paint, before the first analysis result arrives. */
    private boolean tableModeIsImd =
            Preferences.instance().getGenSignalForm().isDualTone();

    /** Subscriber for {@link Events#GENERATOR_SIGNAL_CHANGED} - held as
     *  a field so dispose can unsubscribe by reference.  On USER_INPUT
     *  (any user change to the generated signal - form, frequency /
     *  dual-tone frequencies, amplitude / balance, duty) both the locked
     *  alignment and the averaged spectrum are stale (they were measured
     *  for the old signal), so reset the FLL(s) AND wipe the FFT
     *  statistics including the retained lastResult readout.
     *  Ignores FLL_TRIM (that's our own trim coming back round the bus). */
    private final Consumer<GenChangeCause> onGenChangeForFll = cause -> {
        if (cause == GenChangeCause.USER_INPUT) {
            // A STOPPED analyser is holding a FROZEN result the operator is
            // reading - the averages count and the measurement table's delta-F
            // row among it.  Nothing on the generator side may wipe that: there
            // is no running average that could blend the old tone with the new,
            // and the next start resets the statistics anyway.  Switching the
            // generator off is itself such a change (the controller publishes on
            // its stop), which is how merely ending a measurement erased the
            // measurement.
            // this-qualified: controller is a blank final at initializer
            // time (assigned in the ctor); a simple-name read here fails
            // javac's definite-assignment analysis.
            if (!this.controller.isRecording()) return;
            this.controller.resetFrequencyLock();
            // Signal-change variant: the worker also skips the DAC-buffer
            // drain after re-anchoring, so the fresh accumulator and the
            // FLL's first measurement never see the old tone.
            this.controller.resetStatisticsAfterSignalChange();
            clearRetainedResultsAfterReset();
            syncExternalShell();
            if (!isDisposed()) redraw();
        }
    };


    /** A committed audio-format change moves the capture rate the response is
     *  sampled at, and the first frame at the new rate arrives from a restarted
     *  recording - so the response is dropped here and built by that frame. */
    private final Consumer<Object> onAudioFormatChanged = ignored -> this.calInv = null;

    /** A finished analysis from the worker, handed over by the pane on the
     *  UI thread.  The {@code slot} stays the worker's and is only read here:
     *  it goes back to the pool after this call, and the worker thread may be
     *  reading it at the same time.  So it is copied into {@link #lastResult}
     *  first, and the post-average pipeline runs on that copy, which paint
     *  reads directly. */
    void onResultReady(FftResult slot) {
        if (isDisposed()) return;
        // Every result that arrives here belongs to the current collection: a
        // slot parked across a reset or a change of the generated signal is
        // dropped by the worker at its hand-off, not handed over.
        this.controller.resultConsumed();
        if (isRunning()) {
            // Stage-3 timing starts with the arrival: the post-average pipeline
            // below is part of what it costs to put a fresh result on screen, and
            // the step lines are armed by the same flag the total is.
            startRender = System.nanoTime();
            renderStepMark = startRender;
            gotFftResult = true;
            // In-place refresh of the displayed snapshot: a deepCopy per
            // displayed frame is ~64 MB of garbage at paint rate at fftSize
            // 4 M - copyFrom reuses the existing arrays when sizes match
            // (first frame / size change still allocates).
            if (this.lastResult == null) {
                this.lastResult = slot.deepCopy();
            } else {
                this.lastResult.copyFrom(slot);
            }
            this.lastResultLive = true;
            logRenderStep("3.1 result copy");
            // Post-average pipeline (recompute -> .frc -> dBV), run ONCE
            // here on the DISPLAYED frame (post throttle + coalescing), on the
            // view's own copy, so it holds the finished spectrum + readouts,
            // the dual-tone IMD products among them, and the worker's result
            // stays as the worker produced it.
            finalizeResult(this.lastResult);
            syncDataButtons();
            // Live data clears any stale banner: the loaded-CSV path
            // (persistent) or a warning whose timer has already run out.
            clearBannerIfStale();
            // Sticky table mode: re-evaluated on every recorded result
            // and held between recordings.  Tied to the generator form
            // because that's the only signal that lets us pick between
            // THD (single tone) and IMD (two tones) - an external two-
            // tone source through a paused generator stays on whichever
            // mode was last selected the previous time recording ran.
            boolean prevTableModeIsImd = tableModeIsImd;
            Preferences prefs = Preferences.instance();
            tableModeIsImd = prefs.getGenSignalForm().isDualTone();
            // Mode just flipped (THD ↔ IMD): refresh the extracted window's
            // title + size.  It's set at create time and on form-change, but
            // tableModeIsImd only updates here - one analysis cycle later - so
            // without this the title keeps the old mode (e.g. "IMD" while
            // showing THD).
            if (tableModeIsImd != prevTableModeIsImd) syncExternalShell();
            // Flag the new result; the main event loop's realtime render tick
            // repaints it (see renderRealtimeFrame) instead of painting inline,
            // so scope and FFT share one cadence and neither starves the other.
            fftDirty = true;
        }
    }

    /** Capture re-sync notification.  Payload is the i18n message-key, so one
     *  message presents distinct warnings - a ring overrun
     *  ({@code fft.warning.overrun}) or a signal discontinuity
     *  ({@code fft.warning.discontinuity}).  Raises a PERSISTENT blinking banner
     *  (no fixed timer): the worker emits no result on a re-sync analysis cycle
     *  and re-anchors, so the next FFT result is the first fully-reloaded window -
     *  whereupon {@link #onResultReady}'s {@link #clearBannerIfStale()} drops it.
     *  Also cleared on a statistics reset and on Record stop.  UI thread: the
     *  worker's warning arrives through the controller and the pane. */
    void showCaptureResync(String messageKey) {
        if (messageKey == null || isDisposed() || !isRunning()) return;
        setBanner(() -> I18n.t(messageKey), () -> I18n.t(messageKey + ".tip"),
                ColorRole.WARNING_LIT, ColorRole.WARNING_DIM, 0L);
        redraw();
    }

    /** The fundamental level(s) moved by more than the analyser worker's
     *  drift threshold while the frequency lock pulled the generator onto the
     *  bin grid - the running average still contains pre-alignment frames, so
     *  hint at a statistics reset with a 20 s blinking banner.  {@code deltaDb}
     *  is the largest per-tone delta. */
    void onAlignMagDrift(double deltaDb) {
        if (isDisposed() || !isRunning()) return;
        String delta = String.format(Locale.US, "%.1f", deltaDb);
        setBanner(() -> I18n.t("fft.warning.alignDrift"),
                () -> I18n.t("fft.warning.alignDrift.tip", delta),
                ColorRole.WARNING_LIT, ColorRole.WARNING_DIM,
                System.nanoTime() + ALIGN_DRIFT_WARN_NANOS);
        redraw();
    }

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
    private Combo      distortionUnitCombo;       // unit of the table's absolute-level cells
    private TransparentComposite dataSpacer;      // wide gap before the data trio
    private boolean    dataButtonsShown = true;   // sync gate; the trio starts hidden (no data)
    private boolean    externalShown    = true;   // sync gate for externalBtn (needs the table visible)

    // ─── Single blinking status banner (top-right) ───────────────────────
    // A self-painting, self-blinking BlinkBanner widget (gui.widgets) overlays the
    // plot top-right: the loaded-spectrum path (persistent until live data arrives),
    // the frame-rejection warning (a sticky timer a fresh rejection restarts), or a
    // capture re-sync / overrun warning (persistent until the buffer reloads - the
    // next result - or a reset / stop).  The widget blinks itself - no canvas
    // redraw - so only the expiry lives here.
    private BlinkBanner banner;
    /** Expiry ({@code System.nanoTime}); {@code 0} = persistent (no timer). */
    private long        bannerUntilNanos;
    /** The banner's text and tooltip as key + arguments resolved on demand, not
     *  as translated strings, so a language switch shows the same message again
     *  in the new language; {@code null} before the first banner, and a
     *  {@code null} tooltip shows none. */
    private Supplier<String> bannerText;
    private Supplier<String> bannerTooltip;

    // ─── External tool window ────────────────────────────────────────────
    private boolean    tableExtracted;
    private ToolWindow distortionWindow;   // extracted THD/IMD table window (when extracted)

    /** Latest published analysis result - a deep copy taken on the
     *  worker thread inside {@code publishResult} before it crossed
     *  the bus.  {@code null} before the first successful analysis cycle.
     *  Mutating this snapshot is safe: the worker no longer holds a
     *  reference, so paint code and the pane's Save-CSV / screenshot
     *  paths can read or even mutate it without coordinating with the
     *  analyser.  Lombok provides a public {@code getLastResult}
     *  accessor; the offscreen screenshot pane injects this (the dual-tone
     *  IMD products ride along in {@link FftResult#imd}) via
     *  {@link #copySnapshotFrom(FftView)} without running the analysis
     *  loop. */
    @Getter
    private FftResult lastResult;
    /** Whether {@link #lastResult} came from the live capture - corrected by
     *  the store's active rows, so a stopped one follows a change of them -
     *  rather than from a loaded file, whose data stands as it was saved. */
    private boolean lastResultLive;

    // Static-layer paint cache (traceBuffer Image + fingerprint) now
    // lives in AbstractFreqDomainView.  Mouse-move / crosshair redraws
    // hit the blit path via paintCachedStatic and never re-walk the
    // 64 k spectrum bins.

    /** Cached calibration cascade-sum curve + its key - see
     *  {@link #calCascadeSumDb}.  Invalidation matches the static-layer
     *  fingerprint's calEntries semantics (entry identity + channel). */
    private double[] calOverlaySumDb;
    private List<CorrectionStore.Entry> calOverlayKeyEntries;
    private boolean calOverlayKeyLeft;

    /** The de-embedding response of every loaded cal row, cascaded into ONE
     *  per-bin complex reciprocal ({@code {invRe, invIm}}, bin
     *  {@code 0 .. fftSize/2}) for the analysed channel, and held until an
     *  input moves: the loaded rows ({@link #onCalibrationChanged}), the analysed
     *  channel or the FFT length (both funnel through
     *  {@link #resetStatistics}), the capture rate (a fresh recording), and the
     *  frame's own tone bins ({@link #calInverseFits}).  {@code null} means
     *  "build on the next frame"; the correction, the red dots and the
     *  rejected-block overlay all read this one cascade. */
    private InverseCascade calInv;

    /** {@code true} while a tone-only row is loaded - only then does
     *  {@link #calInv} depend on the frame's tones, and only then are the tone
     *  bins below compared. */
    private boolean calInvToneKeys;

    /** The tone bins {@link #calInv} was built on, slot for slot
     *  ({@link FreqRespCalHelper#toneBins}): a tone-only row's value stands at
     *  exactly those tones, so a frame that moved any of them needs the
     *  response built again. */
    private int[] calInvToneBins;

    /** The offscreen screenshot copy's view - silent and deaf on the bus, see
     *  the constructor. */
    private final boolean offScreen;

    /** A view of the FFT pane.
     *
     * @param parent     the composite the view is drawn in
     * @param pane       the pane that owns the view
     * @param controller the pane's controller
     * @param offScreen  the offscreen screenshot copy's view: it neither
     *                   publishes nor subscribes on the bus and does not react
     *                   to preference changes - it only draws the snapshot it
     *                   is given */
    public FftView(Composite parent, FftPane pane, FftController controller, boolean offScreen) {
        // Pass prefs-driven BACKGROUND + SPECTRUM through the override
        // map so the base allocates the right colour once; everything
        // else uses the light-theme defaults from AbstractMeasurementView.
        // DIM (#DCDCDC) is FFT-only - supplied here rather than the base
        // because no other view dims spectrum regions.
        super(parent, SWT.DOUBLE_BUFFERED, Map.of(
                ColorRole.BACKGROUND, Preferences.instance().getFftChartBackgroundColor(),
                ColorRole.SPECTRUM,   Preferences.instance().getFftLineColor(),
                ColorRole.DIM,        0xDCDCDC,
                // L/R channel buttons share the scope's channel colours (cyan/yellow).
                ColorRole.LEFT_BTN_CHAN,  Preferences.instance().getOscLeftChannelColor(),
                ColorRole.RIGHT_BTN_CHAN, Preferences.instance().getOscRightChannelColor()));
        this.pane            = pane;
        this.controller      = controller;
        this.correctionStore = controller.getCorrectionStore();
        this.offScreen       = offScreen;
        // Chapter-level help anchor: Ctrl+F1 anywhere on the spectrum
        // canvas opens fft.html.  The header buttons / THD table /
        // crosshair aren't separate widgets (everything is custom-
        // painted on this Canvas), so deep widget-level anchors
        // bottom-out at the chapter and the user navigates within.
        setData("helpAnchor", "fft.html");
        Display d = getDisplay();
        if (!offScreen) subscribeLive();
        Preferences viewPrefs = Preferences.instance();
        // Pick up FFT-only prefs (harmonic dot, freq-resp response,
        // before-cal dot, cal overlay) that aren't part of the base
        // palette - kept as view-local fields below.
        syncFftColors();

        // L/R channel buttons - migrated to ToolButton widgets in a top-left Toolbar.
        chanButtonFont = Fonts.instance().channel(d);
        headerBar = new Toolbar(this, BTN_W, BTN_H);
        FormData hbd = new FormData();
        hbd.top  = new FormAttachment(0, 5);
        hbd.left = new FormAttachment(0, MARGIN_LEFT + 4);
        headerBar.setLayoutData(hbd);
        Channel ch = viewPrefs.getFftChannel();
        // No tooltip here: it depends on the combined channel, so showLinked
        // (run by restoreCombinedMaster below) is the one place that sets it.
        leftBtn  = headerBar.chanButton("L", color(ColorRole.TEXT),
                color(ColorRole.BUTTON_FRAME), color(ColorRole.LEFT_BTN_CHAN),
                chanButtonFont, null,  ch == Channel.L, "channel");
        rightBtn = headerBar.chanButton("R", color(ColorRole.TEXT),
                color(ColorRole.BUTTON_FRAME), color(ColorRole.RIGHT_BTN_CHAN),
                chanButtonFont, null, ch == Channel.R, "channel");
        // L/R channel buttons are ToolButton radios (no Bindings helper covers
        // a ToolButton) - each just writes the channel pref (auto-saved); the
        // accumulation restart lives in the fftChannel subscription above, so it
        // fires whoever changes the channel (a preset load too).
        leftBtn.addListener(SWT.Selection, e -> {
            if (leftBtn.isToggled()) viewPrefs.setFftChannel(Channel.L);
        });
        rightBtn.addListener(SWT.Selection, e -> {
            if (rightBtn.isToggled()) viewPrefs.setFftChannel(Channel.R);
        });
        // Combined channel: Shift on the other channel's button folds it into the
        // selected one; a plain press on either button separates them again.
        leftBtn.addListener(SWT.Selection,  e -> { if ((e.stateMask & SWT.SHIFT) != 0) connect(Channel.L); });
        rightBtn.addListener(SWT.Selection, e -> { if ((e.stateMask & SWT.SHIFT) != 0) connect(Channel.R); });
        leftBtn.addListener(SWT.MouseDown,  e -> { if ((e.stateMask & SWT.SHIFT) == 0) disconnect(); });
        rightBtn.addListener(SWT.MouseDown, e -> { if ((e.stateMask & SWT.SHIFT) == 0) disconnect(); });
        restoreCombinedMaster();   // the saved combined channel
        // The L/R tooltips name master and slave while combined: a language
        // switch re-runs the same selection for the combined channel in force.
        Bindings.onChange(this, I18n.localeProperty(),
                l -> showLinked(viewPrefs.getFftCombinedMaster()));
        // Recolour the L/R buttons immediately when the channel colours change.
        bindChannelButtonFills(leftBtn, rightBtn);
        headerBar.spacer(2);
        autoSetupBtn = headerBar.pushButton(Icon.ARROWS_TO_CIRCLE_DARK, Icon.ARROWS_TO_CIRCLE_LIT,
                color(ColorRole.TEXT), null);
        Bindings.text(autoSetupBtn, "fft.autosetup.tooltip", autoSetupBtn::setToolTipText);
        autoSetupBtn.addListener(SWT.Selection, e -> autoSetup());
        maximizeBtn = headerBar.pushButton(Icon.ARROWS_FROM_CIRCLE_DARK, Icon.ARROWS_FROM_CIRCLE_LIT,
                color(ColorRole.TEXT), null);
        Bindings.text(maximizeBtn, "fft.maximize.tooltip", maximizeBtn::setToolTipText);
        maximizeBtn.addListener(SWT.Selection, e -> maximize());
        // Distortion / Reset / External - widgets past a wide spacer, shown only while
        // there's a result (hidden by syncDataButtons() when lastResult is null).
        dataSpacer = headerBar.spacer(2);
        distortionBtn = headerBar.toggleButton(Icon.CHART_DARK, Icon.CHART_LIT,
                color(ColorRole.BUTTON_FRAME),
                null, viewPrefs.isFftDistortionTableVisible());
        Bindings.text(distortionBtn, "fft.distortion.tooltip", distortionBtn::setToolTipText);
        // ToolButton toggle (no Bindings helper covers a ToolButton) - writes
        // the pref (auto-saved); the visibility side-effects live in the
        // fftDistortionTableVisible subscription above so they fire on any
        // change (a preset load too).
        distortionBtn.addListener(SWT.Selection, e ->
                viewPrefs.setFftDistortionTableVisible(distortionBtn.isToggled()));
        resetBtn = headerBar.pushButton(Icon.ROTATE_LEFT_RED, Icon.ROTATE_LEFT_RED,
                color(ColorRole.RESET), null);
        Bindings.text(resetBtn, "fft.reset.tooltip", resetBtn::setToolTipText);
        resetBtn.addListener(SWT.Selection, e -> { resetStatistics(); redraw(); });
        externalBtn = headerBar.toggleButton(Icon.WINDOW_RESTORE_DARK, Icon.WINDOW_RESTORE_LIT,
                color(ColorRole.BUTTON_FRAME),
                null, tableExtracted);
        Bindings.text(externalBtn, "fft.external.tooltip", externalBtn::setToolTipText);
        externalBtn.addListener(SWT.Selection, e -> {
            if (externalBtn.isToggled() != tableExtracted) {
                setTableExtracted(externalBtn.isToggled());
            }
        });
        // The extracted table window's title names THD or IMD by the table
        // mode: a language switch re-runs the same retitle for the mode in force.
        Bindings.onChange(this, I18n.localeProperty(), l -> syncExternalShell());
        // Unit of the distortion table's absolute-level cells.  It keeps the slot
        // right after the external-window button, but hangs off the canvas rather
        // than sitting IN the button row: a native drop-down needs its own inset
        // and width to sit level with the flat buttons, and a RowLayout gives a
        // single child neither.  Anchored to the bar's right edge, so it follows
        // when the row's buttons come and go.  The table is drawn on the canvas,
        // so a unit change only needs a repaint (the extracted window repaints
        // through the same draw methods).
        distortionUnitCombo = new Combo(this, SWT.READ_ONLY);
        distortionUnitCombo.setItems(DISTORTION_UNIT_LABELS);
        FormData ucd = new FormData(DISTORTION_COMBO_W, BTN_H);
        ucd.top  = new FormAttachment(0, DISTORTION_COMBO_TOP);
        ucd.left = new FormAttachment(headerBar, DISTORTION_COMBO_GAP);
        distortionUnitCombo.setLayoutData(ucd);
        distortionUnitCombo.setCursor(d.getSystemCursor(SWT.CURSOR_ARROW));
        Bindings.combo(distortionUnitCombo, viewPrefs.fftDistortionUnitProperty(), DISTORTION_UNITS);
        // The paint pass repaints the extracted window too, so one redraw covers
        // both; the extracted shell is also re-fitted, because the value columns
        // (and so the window's natural width) follow the unit label's width.
        Bindings.onChange(this, viewPrefs.fftDistortionUnitProperty(), v -> {
            resizeExternalShellToContent();
            redraw();
        });
        syncDataButtons();

        setBackground(color(ColorRole.BACKGROUND));
        addPaintListener(this::onPaint);
        addMouseMoveListener(this::onMouseMove);
        setCursor(getDisplay().getSystemCursor(SWT.CURSOR_CROSS));   // plot crosshair cursor, set once
        addListener(SWT.MouseVerticalWheel, this::onMouseWheel);
        installRectZoom(this, true);   // drag-select zoom + Ctrl+Z undo (base machinery)
        addMouseTrackListener(new org.eclipse.swt.events.MouseTrackAdapter() {
            @Override
            public void mouseExit(MouseEvent e) {
                crossX = crossY = -1;
                redraw();
            }
        });
        addDisposeListener(e -> disposeResources());

        // Top-right overlay widgets - children of this Canvas, positioned
        // by FormLayout.  The Canvas paint listener draws underneath;
        // SWT renders child controls on top automatically.
        setLayout(new FormLayout());

        // These children sit on top of the Canvas; without an explicit cursor
        // they inherit the Canvas's crosshair cursor, so give them the normal
        // arrow.  getSystemCursor returns a shared cursor that must not be disposed.
        magUnitCombo = new Combo(this, SWT.READ_ONLY);
        Bindings.items(magUnitCombo, this::magUnitLabels);
        Bindings.text(magUnitCombo, "fft.magUnit.tooltip", magUnitCombo::setToolTipText);
        magUnitCombo.setData("helpAnchor", "fft.html#fft-mag-unit");
        magUnitCombo.setCursor(d.getSystemCursor(SWT.CURSOR_ARROW));
        // Two-way value bind; the unit-change side-effect (convert the visible
        // mag range so it stays on the SAME signal level, then re-align the
        // scrollbars + repaint) lives in the onChange below.  The conversion
        // needs the PREVIOUS unit, which the property listener doesn't carry -
        // so track it in a one-element holder, seeded from the current value
        // and advanced after each conversion.
        Bindings.combo(magUnitCombo, viewPrefs.fftMagUnitProperty(), MagnitudeUnit.values());
        Bindings.onChange(this, viewPrefs.fftMagUnitProperty(), newUnit -> {
            // The magnitude range is stored in canonical dBFS, so switching the unit is
            // a label-only change - the range itself doesn't move.
            fireRangeChanged();
            redraw();
        });

        averagesCountLabel = new Label(this, SWT.NONE);
        showAveragesCount();
        averagesCountLabel.setForeground(d.getSystemColor(SWT.COLOR_DARK_GRAY));
        Bindings.text(averagesCountLabel, "fft.avgCount.tooltip", averagesCountLabel::setToolTipText);
        averagesCountLabel.setData("helpAnchor", "fft.html#fft-averages-count");
        averagesCountLabel.setCursor(d.getSystemCursor(SWT.CURSOR_ARROW));

        fillPercentLabel = new Label(this, SWT.NONE);
        fillPercentLabel.setText("0%");
        fillPercentLabel.setForeground(d.getSystemColor(SWT.COLOR_DARK_GRAY));
        Bindings.text(fillPercentLabel, "fft.fillPercent.tooltip", fillPercentLabel::setToolTipText);
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
        // the text, not the full plot - a full-width transparent banner covers
        // (and shows its tooltip over) the THD/N+D table and dB axis on the left.
        banner.setLayoutData(bnd);
        Bindings.onChange(banner, I18n.localeProperty(), l -> showBannerText());
        Bindings.onChange(this, Fonts.instance().generationProperty(), g -> applyFonts());

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
                // What the analyser says is inside the average, shown as it
                // stands: the window length once a moving average has filled
                // it, climbing without limit under "forever".
                // The cross-cycle accumulator math (forever mode) makes each
                // displayed analysis cycle contribute additional SNR depth -
                // the actual frames-averaged depth is roughly 2× the
                // displayed N because each forever cycle contributes
                // ~2 FFT frames to the running sum.
                showAveragesCount();
            }
            d.timerExec(100, tick[0]);
        };
        d.timerExec(100, tick[0]);
    }

    /** Shows the averages count in the active language.  The readout timer
     *  runs it every ~100 ms, which also carries the label across a language
     *  switch. */
    private void showAveragesCount() {
        String ns = I18n.t("fft.avgCount.label", String.valueOf(getAveragesInWindow()));
        if (!ns.equals(averagesCountLabel.getText())) averagesCountLabel.setText(ns);
    }

    /** Loop-driven realtime repaint: paints a freshly-arrived FFT result (flagged
     *  by {@link #onResultReady}) on the main event loop's render tick, so the FFT
     *  view refreshes on the same cadence as the scope and neither view starves the
     *  other.  No-op while hidden (a non-active top-level tab) or with no pending
     *  result.  Returns {@code true} while recording so the loop keeps the realtime
     *  cadence going even on ticks that produced no new result. */
    public boolean renderRealtimeFrame() {
        if (isDisposed() || !isVisible()) return false;
        if (fftDirty) {
            fftDirty = false;
            redraw();
            update();
        }
        return isRunning();
    }

    /** The live view's bus subscriptions and preference reactions - the
     *  offscreen screenshot copy wires none of them. */
    private void subscribeLive() {
        MessageBus bus = MessageBus.instance();
        // The bus is UI-only - every publisher of this event is on the display
        // thread - so a plain subscription is correct; unsubscribed on dispose.
        bus.subscribe(Events.GENERATOR_SIGNAL_CHANGED, onGenChangeForFll);
        addDisposeListener(e ->
                bus.unsubscribe(Events.GENERATOR_SIGNAL_CHANGED, onGenChangeForFll));
        bus.subscribe(Events.AUDIO_FORMAT_CHANGED, onAudioFormatChanged);
        // Window-function changes reset the running statistics - the control
        // writes the pref, the view reacts to the pref, neither calls the other.
        // Every settings/THD parameter whose side-effect targets the view is
        // wired the same way: the control binds the pref (two-way), the view
        // subscribes to the pref here, and neither calls the other.
        Preferences viewPrefs = Preferences.instance();
        Bindings.onChange(this, viewPrefs.fftWindowProperty(),           w -> resetStatistics());
        // Switching channel restarts the accumulation so it re-captures the new
        // channel from 0 - the L/R ToolButtons write the pref, the view reacts here.
        Bindings.onChange(this, viewPrefs.fftChannelProperty(),          v -> resetStatistics());
        Bindings.onChange(this, viewPrefs.fftCombinedMasterProperty(),   this::applyCombinedMaster);
        // Distortion-table toggle: the external button follows the table's
        // visibility, then refresh the extracted shell + repaint.
        Bindings.onChange(this, viewPrefs.fftDistortionTableVisibleProperty(), v -> {
            syncDataButtons();
            syncExternalShell();
            redraw();
        });
        // FFT length re-snaps the generator's tone (pane publishes the bus
        // event) AND invalidates the accumulator - the view restarts averaging.
        Bindings.onChange(this, viewPrefs.fftLengthProperty(),           v -> resetStatistics());
        // Coherent ↔ incoherent changes the accumulator semantics - restart.
        Bindings.onChange(this, viewPrefs.fftCoherentAveragingProperty(), v -> resetStatistics());
        // ADC re-calibration rescales every measured voltage; DAC re-calibration
        // changes the generated (loopback) signal level - either invalidates the
        // accumulated spectrum, so restart averaging on a calibration change of
        // the device in force.
        Bindings.onEvent(this, Events.ADC_CALIBRATION_CHANGED, (DeviceRef device) -> {
            if (AudioBackend.instance().isActiveDevice(device, true)) resetStatistics();
        });
        Bindings.onEvent(this, Events.DAC_CALIBRATION_CHANGED, (DeviceRef device) -> {
            if (AudioBackend.instance().isActiveDevice(device, false)) resetStatistics();
        });
        // Selecting an active alignment mode (PID / FLL) resets its loop so each
        // session converges fresh; NONE deliberately resets nothing (the
        // generator stays at the stabilized frequency, the converged correction
        // is retained).  Only a real change reaches here (equals-guarded).
        Bindings.onChange(this, viewPrefs.fftAlignGeneratorProperty(),
                mode -> { if (mode != AlignGenerator.NONE) controller.resetFrequencyLock(); });
        // Log-axis remap, distortion-range corners (THD tile) - pure repaints,
        // no statistics reset.
        Bindings.onChange(this, viewPrefs.fftLogFreqAxisProperty(),      v -> redraw());
        // THD settings (HP/LP distortion band, manual fundamental, max harmonics):
        // repaint, and for a displayed STATIC spectrum re-apply them + recompute
        // (the live worker already re-reads them every analysis cycle).
        Bindings.onChange(this, viewPrefs.fftDistMinEnabledProperty(),    v -> onThdSettingChanged());
        Bindings.onChange(this, viewPrefs.fftDistMinHzProperty(),         v -> onThdSettingChanged());
        Bindings.onChange(this, viewPrefs.fftDistMaxEnabledProperty(),    v -> onThdSettingChanged());
        Bindings.onChange(this, viewPrefs.fftDistMaxHzProperty(),         v -> onThdSettingChanged());
        Bindings.onChange(this, viewPrefs.fftManualFundEnabledProperty(), v -> onThdSettingChanged());
        Bindings.onChange(this, viewPrefs.fftManualFundVrmsProperty(),    v -> onThdSettingChanged());
        Bindings.onChange(this, viewPrefs.fftThdMaxHarmonicProperty(),    v -> onThdSettingChanged());
        Bindings.onChange(this, viewPrefs.fftMagUnitProperty(), v -> {
            if (magUnitCombo == null || magUnitCombo.isDisposed()) return;
            magUnitCombo.select(v.ordinal());
        });

        // Calc-up-to-harmonic drives the external THD window's row count.
        Bindings.onChange(this, viewPrefs.fftCalcMaxHarmonicProperty(),  v -> { resizeExternalShellToContent(); onThdSettingChanged(); });
    }

    private void disposeResources() {
        MessageBus bus = MessageBus.instance();
        bus.unsubscribe(Events.AUDIO_FORMAT_CHANGED, onAudioFormatChanged);
        disposePalette();
        // monoFont / monoBoldFont / chanButtonFont are shared instances
        // owned by Fonts - never disposed here.
        // Header icons are cached and owned by IconUtils - disposed
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
        // Keep the SWT Canvas background in sync with the palette entry -
        // base setColor() is a no-op when the RGB hasn't changed, so this
        // is cheap to repeat per paint.
        setBackground(color(ColorRole.BACKGROUND));
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /** Copies the paint-relevant snapshot - last spectrum result (the
     *  dual-tone IMD products included) and the THD/IMD table-mode flag -
     *  from {@code source} into this view.  Used by the screenshot renderer
     *  so a passive (worker-less) clone draws exactly the table and
     *  dots the live view shows; without the table-mode flag the offscreen
     *  view would fall back to the THD table. */
    public void copySnapshotFrom(FftView source) {
        if (source == null || source == this) return;
        this.lastResult     = source.lastResult;
        this.tableModeIsImd = source.tableModeIsImd;
        // Carry the live readouts across so the screenshot shows the real
        // "N average(s)" / "NN%" instead of this idle clone's controller 0.
        this.snapshotCompleted = source.getAveragesInWindow();
        this.snapshotFill      = source.getNextFrameProgress();
    }

    /** One change of the loaded correction rows - the FFT tab control hands
     *  over every change of the store.  While recording, the per-bin response
     *  they divide by is rebuilt at once, on the geometry of the frame on
     *  screen (with no frame yet the next one builds it), and the next frame is
     *  corrected with it.  A stopped capture's result follows the change itself
     *  ({@link #restateStoppedResult}); a loaded file's data stands as it was
     *  saved. */
    public void onCalibrationChanged(CorrectionStore.Change change) {
        if (isDisposed()) return;
        if (this.lastResult != null && this.lastResultLive && !isRunning()) {
            restateStoppedResult();
        } else {
            this.calInv = null;
            if (this.lastResult != null) buildCalInverse(this.lastResult);
        }
        redraw();
    }

    /** Averages currently inside the average (or the snapshot override on an
     *  offscreen screenshot clone) - the readout's own number. */
    public int getAveragesInWindow() {
        return snapshotCompleted >= 0 ? snapshotCompleted : controller.averagesInWindow();
    }

    /** True if the analyser worker is currently running. */
    public boolean isRunning() {
        return controller.isRecording();
    }

    /** Clears the status banner - a re-sync / overrun warning is moot
     *  once recording stopped; the pane calls this alongside
     *  {@code controller.stopRecording()}. */
    public void clearWarningBanner() {
        clearBanner();
    }

    /** True when the audio generator is currently producing a signal.
     *  Resolved via {@link MessageBus} - the generator pane registers a
     *  responder for {@link Events#GENERATOR_RUNNING}.  Defaults to
     *  {@code true} when no responder is registered.  Used by the THD
     *  table to decide whether to draw the generator-anchored Δf row. */
    public boolean isGeneratorActive() {
        return controller.isGeneratorActive();
    }

    /** Fraction (0..1) of the data needed for the *current* FFT frame
     *  that has already been captured.  Drives the "NN%" indicator next
     *  to the magnitude-unit combo. */
    public double getNextFrameProgress() {
        return snapshotFill >= 0 ? snapshotFill : controller.nextFrameProgress();
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
     *  align is turned ON ({@code controller.resetFrequencyLock()}) or
     *  recording is restarted ({@code controller.stopRecording()}). */
    public void resetStatistics() {
        controller.resetStatistics();
        clearRetainedResultsAfterReset();
        // The analysed channel and the FFT length both reach a reset before
        // they reach a frame, and the per-bin response is sampled for both -
        // so it is dropped here and rebuilt by the first frame after.
        calInv = null;
    }

    /** Combines the two inputs into the analysed channel, with {@code slave} -
     *  the other channel - folded into it.  Writes the preference;
     *  {@link #applyCombinedMaster} reacts. */
    private void connect(Channel slave) {
        Preferences p = Preferences.instance();
        Channel master = p.getFftChannel();
        if (slave != master) p.setFftCombinedMaster(master);
    }

    /** Separates the two inputs again; a no-op while they are separate. */
    private void disconnect() {
        Preferences.instance().setFftCombinedMaster(null);
    }

    /** Applies a change of the combined channel: the worker's reader combines
     *  into {@code master} or separates, the slave's button shows the linked
     *  look, and the FFT restarts from the new signal. */
    private void applyCombinedMaster(Channel master) {
        if (master != null) dropReferenceAlignment();
        controller.setCombinedMaster(master);
        showLinked(master);
        resetStatistics();
        redraw();
    }

    /** Shows the combined channel restored from the preferences; the worker
     *  combines its reader from them at every start. */
    private void restoreCombinedMaster() {
        Channel master = Preferences.instance().getFftCombinedMaster();
        if (master != null) dropReferenceAlignment();
        showLinked(master);
    }

    /** Reference-channel alignment reads the second input, which a combined
     *  channel delivers as 0 - falls back to none. */
    private void dropReferenceAlignment() {
        Preferences p = Preferences.instance();
        if (p.getFftFundamentalPhaseAlignment() == FundamentalPhaseAlignment.REFERENCE_CHANNEL) {
            p.setFftFundamentalPhaseAlignment(FundamentalPhaseAlignment.NONE);
        }
    }

    /** The linked look on the slave's button - the one that is not
     *  {@code master} - and on neither while separate; while combined the
     *  tooltips name master and slave, while separate the usual ones stand. */
    private void showLinked(Channel master) {
        leftBtn.setLinked(master == Channel.R);
        rightBtn.setLinked(master == Channel.L);
        leftBtn.setToolTipText(I18n.t(master == null ? "fft.button.left.tooltip"
                : master == Channel.L ? "common.channel.combined.leftMaster" : "common.channel.combined.leftSlave"));
        rightBtn.setToolTipText(I18n.t(master == null ? "fft.button.right.tooltip"
                : master == Channel.R ? "common.channel.combined.rightMaster" : "common.channel.combined.rightSlave"));
    }

    /** View-side companion of a statistics reset: drops the retained
     *  result so the readouts (fundamental, THD, SNR, harmonics) and the
     *  plotted curve clear immediately and repopulate from the fresh
     *  accumulation - otherwise the stale last values linger on screen.
     *  Only while recording: a statically loaded CSV (worker stopped)
     *  must survive an unrelated setting change that also resets.  The
     *  statistics forget what the dropped result's trace was lifted with. */
    private void clearRetainedResultsAfterReset() {
        if (isRunning()) {
            lastResult = null;
            statistics.reset();
            clearBanner();         // drop any re-sync / overrun warning on reset
        }
        syncDataButtons();
        GuiUtil.marshal(() -> { if (!isDisposed()) redraw(); });
    }

    /** Post-average pipeline, run ONCE per DISPLAYED frame on the view's own
     *  copy of the worker's frame - raw or averaged, as the accumulator handed
     *  it over - with the loaded {@code .frc} rows: {@link #runPipeline}.  So
     *  all readouts (THD / SNR / N / fundamental / harmonics, every unit + %)
     *  and the plot derive from one spectrum, computed only on frames the user
     *  actually sees.
     *
     * @param r the view's copy of the frame, as measured
     */
    private void finalizeResult(FftResult r) {
        if (r.amplitudeDbFs == null) return;
        runPipeline(r, readoutSettings(), !correctionStore.getEntries().isEmpty());
    }

    /**
     * The one pipeline every rendered spectrum takes - a live frame, an
     * average, a spectrum read back from a file:
     * <ol>
     *   <li>the harmonic ladder, placed with the settings' harmonic count on
     *       the result's κ ({@link FftResult#coherentKappa}) - the pinned one
     *       while averaging, so the lines do not jitter between bins;</li>
     *   <li>the statistics in one step
     *       ({@link SpectrumStatistics#calculateStat}): every line measured
     *       once, its level as measured (the blue dot) and de-embedded (the
     *       red dot), every readout on the de-embedded levels, and the trace
     *       lifted - the {@code .frc}, the manual fundamental, the clamped
     *       distortion lines - ready to draw and to save; the view's copy
     *       only, never the worker's frame.</li>
     * </ol>
     *
     * @param r        the spectrum as measured, never de-embedded
     * @param settings the readout settings the statistics are calculated with
     * @param deembed  whether the loaded {@code .frc} rows correct it
     */
    private void runPipeline(FftResult r, ReadoutSettings settings, boolean deembed) {
        r.placeHarmonics(settings.harmonicCount(), r.coherentKappa);
        statistics.calculateStat(r, settings, frcFor(r, deembed));
        logRenderStep("3.4 statistics");
    }

    /** The pipeline again on the result it delivered last: the statistics
     *  take the lifts they put on its trace off first
     *  ({@link SpectrumStatistics#recalculateStat}), then calculate with the
     *  settings and the rows as they are now.
     *
     * @param r        the result the pipeline delivered last
     * @param settings the readout settings the statistics are calculated with
     * @param deembed  whether the loaded {@code .frc} rows correct it */
    private void rerunPipeline(FftResult r, ReadoutSettings settings, boolean deembed) {
        r.placeHarmonics(settings.harmonicCount(), r.coherentKappa);
        statistics.recalculateStat(r, settings, frcFor(r, deembed));
    }

    /** The {@code .frc} correction {@code r} is de-embedded with: the held
     *  response, built again when it no longer fits {@code r}
     *  ({@link #calInverseFits}).
     *
     * @param r       the spectrum, its harmonic ladder placed
     * @param deembed whether the loaded {@code .frc} rows correct it
     * @return the correction; {@code null} without de-embedding */
    private InverseCascade frcFor(FftResult r, boolean deembed) {
        if (!deembed) return null;
        if (!calInverseFits(r)) {
            buildCalInverse(r);
            logRenderStep("3.2 .frc response build");
        }
        return calInv;
    }

    /** The readout settings as the preferences state them now, the manual
     *  fundamental converted with the analysed channel's dBV offset.
     *
     * @return the readout settings
     */
    private ReadoutSettings readoutSettings() {
        return ReadoutSettings.fromPreferences(Preferences.instance(),
                AudioBackend.instance().analysedChannelDbvOffsetDb());
    }

    /**
     * Brings a stopped capture's result in line with the loaded rows and the
     * readout settings as they are now.  The held response belongs to the
     * rows before the change, so it is dropped and built anew; the pipeline
     * runs again ({@link #rerunPipeline}), its statistics taking their lifts
     * off the trace first.
     */
    private void restateStoppedResult() {
        calInv = null;
        rerunPipeline(lastResult, readoutSettings(), !correctionStore.getEntries().isEmpty());
    }

    /** Shows a static spectrum loaded from file instead of live data, after
     *  the pipeline a live frame takes - without the {@code .frc} and without
     *  the manual fundamental: the file was saved with both applied.  When the
     *  result carries IMD products ({@link FftResult#imd}) the view switches
     *  to IMD (dual-tone) table mode and renders them; otherwise it shows the
     *  single-tone THD / harmonic table.  The caller must have already stopped
     *  recording (so the worker won't overwrite it).
     *
     * @param r the spectrum read from the file, as the file holds it
     */
    public void displayLoadedResult(FftResult r) {
        lastResult = r;
        lastResultLive = false;
        runPipeline(r, readoutSettings().withoutManualFundamental(), false);
        tableModeIsImd = (r.imd != null);
        syncDataButtons();
        if (!isDisposed()) { redraw(); update(); }
    }

    /** A THD setting (HP/LP band, manual fundamental, max harmonics) changed.
     *  For a displayed STATIC spectrum (not a live capture) the pipeline runs
     *  again with the settings as they are now - a stopped capture's from the
     *  spectrum as measured, with the loaded rows; a loaded file's without the
     *  {@code .frc} and the manual fundamental - then repaints.  During live
     *  capture every displayed frame takes the pipeline with the settings of
     *  its time, so this only repaints. */
    private void onThdSettingChanged() {
        if (isDisposed()) return;
        if (lastResult != null && !controller.isRecording()) {
            if (lastResultLive) {
                restateStoppedResult();
            } else {
                rerunPipeline(lastResult, readoutSettings().withoutManualFundamental(), false);
            }
        }
        redraw();
    }

    /** True when {@code r}'s measured fundamental rises clearly above its own
     *  local noise floor - the band-top beside it its line carries - i.e. it
     *  is a genuine tonal peak, not the top of a broadband hump (speech) or
     *  the max bin of pure noise.  Both levels are the line's raw ones, read on
     *  the spectrum as it arrived, before any de-embedding or lift.  When
     *  false, F and the harmonics are indistinguishable from the surrounding
     *  grass, so the THD/IMD table and the F/Hn dots are hidden.
     *
     * @param r the result
     * @return true for a distinguishable fundamental */
    private boolean hasDistinguishableSignal(FftResult r) {
        if (r == null || r.fundamental == null) return false;
        double level = r.fundamental.rawAmplitudeDbFs();
        if (!Double.isFinite(level)) return false;
        // Absurd THD => the harmonics are as strong as the "fundamental": a
        // broadband hump (speech) or noise, not a tone.
        if (Double.isFinite(r.thdPct) && r.thdPct > MAX_THD_PCT) return false;
        double floor = r.fundamental.bandTopDbFs();
        if (!Double.isFinite(floor)) return true;   // no floor estimate - don't suppress
        return level > floor + SIGNAL_FLOOR_MARGIN_DB;
    }

    /** True when the table is currently in IMD (dual-tone) mode. */
    public boolean isTableModeImd() {
        return tableModeIsImd;
    }

    /** Sets the "Loaded: ..." blinking-banner path (a statically loaded
     *  spectrum), or clears it with {@code null}.  Repaints. */
    public void setSourceFilePath(String path) {
        if (path == null || path.isEmpty()) {
            clearBanner();
        } else {
            setBanner(() -> I18n.t("fft.loaded.prefix", path), null,
                    ColorRole.BLINK_LIT, ColorRole.BLINK_DIM, 0L);
        }
        if (!isDisposed()) redraw();
    }

    /** Shows the status banner - the loaded-spectrum path or the frame-rejection
     *  warning - on the {@link BlinkBanner} widget, which paints and blinks itself
     *  (no canvas redraw).  The widget right-aligns and left-ellipsises the text to
     *  its own width.
     *  @param text       resolves the banner text in the active language
     *  @param tooltip    resolves the tooltip; {@code null} = no tooltip
     *  @param untilNanos {@code 0} = persistent, else a {@code nanoTime} expiry. */
    private void setBanner(Supplier<String> text, Supplier<String> tooltip,
                           ColorRole lit, ColorRole dim, long untilNanos) {
        ensureFonts();
        banner.setFont(monoFont);
        bannerText    = text;
        bannerTooltip = tooltip;
        banner.setColors(color(lit), color(dim), color(ColorRole.BACKGROUND));
        bannerUntilNanos = untilNanos;
        banner.setVisible(true);
        showBannerText();
        if (untilNanos > 0L) {
            scheduleBannerHide(untilNanos);
        }
    }

    /** Shows the banner's text and tooltip in the active language and fits the
     *  banner to the text - when a banner is raised, and again on a language
     *  switch, which re-resolves the same key and arguments. */
    private void showBannerText() {
        if (bannerText == null) return;
        banner.setText(bannerText.get());
        banner.setToolTipText(bannerTooltip == null ? null : bannerTooltip.get());
        // Size the right-anchored banner to its text so it doesn't span the full
        // plot (clamped to the old MARGIN_LEFT..right-6 band), then lay it out.
        banner.fitFormDataWidth(getClientArea().width - MARGIN_LEFT - 6);
        banner.requestLayout();          // re-size to the new text height + re-position
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
     *  paint tick polls the timer - a one-shot {@code timerExec} does.  Guarded so a
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

    /** Re-aligns the owning pane's scrollbars to the (just-mutated) freq /
     *  mag window in {@code Preferences}.  Called after every view-internal
     *  pan / zoom and from {@link #autoSetup()} / {@link #maximize()}. */
    private void fireRangeChanged() {
        if (offScreen) return;
        pane.syncFftPan();
    }

    /** Sets the freq range to one decade either side of the detected
     *  fundamental and the magnitude range so the fundamental sits
     *  10 dB below the top with the noise floor 20 dB below the
     *  bottom.  No-op when no analysis result is published yet. */
    private void autoSetup() {
        if (lastResult == null) return;
        Preferences prefs = Preferences.instance();

        // ── Frequency.  In DUAL_TONE / IMD mode the range needs to
        // span every measurement point we plot - F1, F2, the
        // difference frequency F2−F1, and the per-order dnL/dnH
        // sidebands - so the auto-set window includes the full IMD
        // dot family with a 10 % log-padding either side.  In
        // single-tone (THD) mode keep the existing "one decade either
        // side of the fundamental" behaviour.
        double lo, hi;
        ImdResult imd = lastResult.imd;
        if (imd != null) {
            // Aggregate every dot frequency we'd potentially plot.
            double f1Hz  = imd.f1.frequencyHz();
            double f2Hz  = imd.f2.frequencyHz();
            double minHz = Math.min(f1Hz, f2Hz);
            double maxHz = Math.max(f1Hz, f2Hz);
            double diff  = Math.abs(f2Hz - f1Hz);
            if (diff > 0) minHz = Math.min(minHz, diff);
            for (int k = 2; k <= ImdResult.MAX_ORDER; k++) {
                double dl = imd.dnL[k].frequencyHz();
                double dh = imd.dnH[k].frequencyHz();
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
        // bottom = noise floor − 30 dB.  In DUAL_TONE mode the
        // strongest tone is whichever of F1 / F2 has the higher
        // dBFS - the single-fundamental detector in FftAnalyzer
        // already latches on to that one, so we use both F1/F2
        // dBFS from the IMD slot to pick the maximum explicitly.
        if (Double.isFinite(lastResult.fundamentalDbFs) && Double.isFinite(lastResult.avgNoiseFloorDbFs)) {
            // The fundamental's DISPLAYED dBFS - manual override (converted) when set,
            // else the measured level (already .frc-corrected in place by the pipeline).
            double peakDbFs = displayedFundDbFs();
            if (!Double.isFinite(peakDbFs)) peakDbFs = lastResult.fundamentalDbFs;
            if (imd != null) {
                double f1DbFs = imd.f1.deembeddedAmplitudeDbFs();
                double f2DbFs = imd.f2.deembeddedAmplitudeDbFs();
                if (Double.isFinite(f1DbFs)) peakDbFs = Math.max(peakDbFs, f1DbFs);
                if (Double.isFinite(f2DbFs)) peakDbFs = Math.max(peakDbFs, f2DbFs);
            }
            // Range is stored canonically in dBFS; the unit conversion happens only at
            // draw / readout time, so every unit pans + zooms identically.
            double topDbFs = peakDbFs + 20;
            double botDbFs = lastResult.avgNoiseFloorDbFs - AUTO_SETUP_BELOW_FLOOR_DB;
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
     *  from a fixed +20 dB ceiling down to (noise floor − 40 dB).  The
     *  top is signal-independent - it doesn't track the fundamental -
     *  so the chart layout stays stable across signal changes.  Works
     *  even when no analysis result is published yet (uses fallback
     *  values for the bottom). */
    private void maximize() {
        Preferences prefs = Preferences.instance();

        // Frequency span: 0 (clamped to bin size) -> Nyquist.  Fall back
        // to currentNyquist() when no result is available yet - that
        // helper itself defaults to 192 kHz / 2 = 96 kHz half-rate.
        double nyquist = (lastResult != null) ? lastResult.sampleRate / 2.0 : currentNyquist();
        double lo = Math.max(currentBinSize(), 0.0);
        if (nyquist > lo) {
            prefs.setFftFreqMinHz(lo);
            prefs.setFftFreqMaxHz(nyquist);
        }

        // Fixed +20 dBFS ceiling, noise-floor − 40 dB floor (when
        // present; otherwise default to −150 dBFS bottom to give a
        // sensible empty-chart layout).
        // Default +20 dBFS ceiling, but never below the DISPLAYED (correction-lifted)
        // fundamental + 20 dB, so a notch un-notched above 0 dBFS still fits.
        double fundTop = displayedFundDbFs();
        double topDbFs = Double.isFinite(fundTop) ? Math.max(20.0, fundTop + 20.0) : 20.0;
        double botDbFs = (lastResult != null && Double.isFinite(lastResult.avgNoiseFloorDbFs))
                ? lastResult.avgNoiseFloorDbFs - MAXIMIZE_BELOW_FLOOR_DB
                : -150;
        // Range stored canonically in dBFS - unit conversion happens at draw / readout.
        if (Double.isFinite(topDbFs) && Double.isFinite(botDbFs) && topDbFs != botDbFs) {
            if (topDbFs < botDbFs) { double s = topDbFs; topDbFs = botDbFs; botDbFs = s; }
            prefs.setFftMagTop(topDbFs);
            prefs.setFftMagBottom(botDbFs);
        }
        prefs.save();
        fireRangeChanged();
        redraw();
    }

    /** The fundamental's DISPLAYED level in dBFS - what the spectrum actually draws at
     *  the fundamental: the manual-override dBV (converted) when set, else the measured
     *  level, which the pipeline already corrected in place for the loaded {@code .frc}
     *  calibration.
     *
     * @return the level, {@code NaN} with no result */
    private double displayedFundDbFs() {
        if (lastResult == null || !Double.isFinite(lastResult.fundamentalDbFs)) return Double.NaN;
        if (Double.isFinite(lastResult.fundamentalTrueDbFs)) {
            return lastResult.fundamentalTrueDbFs;               // manual fundamental, already dBFS
        }
        return lastResult.fundamentalDbFs;                       // already .frc-corrected
    }

    /** Magnitude-axis ceiling in dBFS: the 0 dBFS full-scale max, raised when a signal is
     *  present to keep the DISPLAYED fundamental + 20 dB in range - so the axis can exceed
     *  0 dBFS when calibration lifts the signal above full scale.  Used by the pane's
     *  clamp / scrollbar sync / scroll handler so the corrected peak is reachable. */
    public double magCeiling() {
        double fundDbFs = displayedFundDbFs();
        if (Double.isFinite(fundDbFs)) return Math.max(0.0, fundDbFs + 20.0);
        // No analysis yet (e.g. app start): the ceiling must not shrink below
        // the persisted window top - the pane's clamp writes the clamped value
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
        double fs = AudioBackend.instance().calibrationOrDefault(true)
                .fsRms(Preferences.instance().getFftChannel());
        return (fs > 0) ? lastResult.fundamentalLinear * fs : null;
    }

    /** The dBV↔dBFS offset of the analysed channel - the input device's
     *  calibration in force, read ONCE per paint (a per-bin read would resolve
     *  the device per bin) and used by every conversion of that paint. */
    private double paintDbvOffsetDb;

    /** A full-scale magnitude (1 = full scale) as dBFS, floored at
     *  {@link #MIN_REF_MAG} - the tone levels the intermodulation result
     *  carries linearly, read the way every other level of the table is. */
    private double dbFsOfMagnitude(double magFs) {
        return 20.0 * Math.log10(Math.max(MIN_REF_MAG, magFs));
    }

    /**
     * Converts an analyser dBFS magnitude into {@code unit} for display.  Pure
     * constant math, safe to call per bin in paint loops: dBV is a constant
     * offset from dBFS ({@link #paintDbvOffsetDb}), V is the same value read
     * linearly (0&nbsp;dBV = 1&nbsp;V), and V/√Hz additionally divides by
     * √(bin bandwidth).
     *
     * @param binBwSqrt √(bin bandwidth in Hz) of the spectrum being converted -
     *                  carried by file-loaded results ({@code FftResult#binBwSqrt});
     *                  {@code null} uses the preferences' cached live config,
     *                  which is bidi-bound and restarts the FFT on change, so
     *                  it always matches the live measurement
     */
    private double convertFromDbFs(double dbFs, MagnitudeUnit unit, Double binBwSqrt) {
        Preferences prefs = Preferences.instance();
        switch (unit) {
            case DBFS: return dbFs;
            case DBR:  return dbFs - prefs.getFftDbrRefDbFs();
            case DBV:  return dbFs + paintDbvOffsetDb;
            case V:         return Math.pow(10.0, (dbFs + paintDbvOffsetDb) / 20.0);
            case V_SQRT_HZ: return Math.pow(10.0, (dbFs + paintDbvOffsetDb) / 20.0)
                    / (binBwSqrt != null ? binBwSqrt : prefs.getBinBwSqrt());
            default: return dbFs;
        }
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
        // The analysed channel's calibration, once for this paint.
        paintDbvOffsetDb = AudioBackend.instance().analysedChannelDbvOffsetDb();
        // dBr reference = the DISPLAYED fundamental level (manual override when set, else the
        // measured level); re-stamped every paint so the DBR axis + cursor readouts pin the
        // fundamental to 0 dBr.  NaN (no fundamental yet) leaves the previous reference intact.
        double dbrRefDbFs = displayedFundDbFs();
        if (Double.isFinite(dbrRefDbFs)) prefs.setFftDbrRefDbFs(dbrRefDbFs);
        double freqMin = Math.max(0, prefs.getFftFreqMinHz());
        double freqMax = Math.max(freqMin + 1, prefs.getFftFreqMaxHz());
        // The magnitude range is stored canonically in dBFS; convert to the display unit
        // for drawing.
        double magBot   = convertFromDbFs(prefs.getFftMagBottom(), unit, null);
        double magTop   = convertFromDbFs(prefs.getFftMagTop(),    unit, null);
        boolean logFreq = prefs.isFftLogFreqAxis();
        if (logFreq && freqMin < 1) freqMin = 1;


        // ---- Static-layer buffer: blit cached image when nothing
        // affecting the trace / grid / axes has changed.  Cache
        // lifecycle (rebuild / blit) lives in AbstractFreqDomainView;
        // we just hand it a fingerprint and the static-layer painter.
        final double fFreqMin = freqMin;
        long fp = computeTraceFingerprint(lastResult, area, unit, fFreqMin, freqMax,
                magTop, magBot, logFreq);
        // The paint steps are charged from here: between the result's arrival
        // and this paint the frame waits for the render tick, which is not
        // rendering work.  A repaint with no fresh result logs nothing.
        if (gotFftResult) renderStepMark = System.nanoTime();
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
            logRenderStep("3.8 grid + bands");
            // The frame stands on its own; everything from here reads the
            // result, and nothing below tests it again.
            if (lastResult == null) return;
            drawSpectrum(bgc, plot, lastResult, unit, fFreqMin, freqMax, magTop, magBot, logFreq);
            // DEBUG overlay: de-embedded calibration filter response (green line).
            if (DebugSwitches.SHOW_CAL_OVERLAY) {
                drawCalOverlay(bgc, plot, lastResult, unit, fFreqMin, freqMax,
                        magTop, magBot, logFreq);
            }
            logRenderStep("3.9 spectrum trace");
            // Harmonic dots are THD-mode markers - hide them when
            // an IMD result is present (DUAL_TONE generator) so the
            // F1/F2/dnL/dnH annotations don't compete with the
            // single-fundamental-anchored H2..Hn series for the
            // same screen space.
            ImdResult imd = lastResult.imd;
            if (imd == null && hasDistinguishableSignal(lastResult)) {
                drawHarmonicDots(bgc, plot, lastResult, unit, fFreqMin, freqMax,
                        magTop, magBot, logFreq);
            }
            if (imd != null && hasDistinguishableSignal(lastResult)) {
                drawImdDots(bgc, plot, imd, lastResult, unit, fFreqMin, freqMax,
                        magTop, magBot, logFreq);
            }
            // THD / IMD readout table - static per result, so it renders
            // into the cached layer: its ~35 outlined labels (≈315 GDI text
            // calls) used to re-draw on every crosshair mouse-move.
            if (Preferences.instance().isFftDistortionTableVisible()
                    && !tableExtracted && hasDistinguishableSignal(lastResult)) {
                if (tableModeIsImd && imd != null) {
                    drawImdTable(bgc, imd, MARGIN_LEFT + 6, TABLE_TOP_Y);
                } else {
                    drawDistortionTable(bgc, lastResult, unit);
                }
            }
            logRenderStep("3.10 dots + table");
        });

        // ---- Dynamic overlay (drawn live on top of the cached image)
        gc.setAntialias(SWT.ON);
        gc.setTextAntialias(SWT.ON);
        // The THD / IMD table renders inside the cached static layer above -
        // exactly one table; mode is sticky and only flipped inside
        // onResultReady (which fires only when the analyser is actually
        // recording), so the user's last-recorded mode persists while
        // recording is paused even if they toggle the generator form
        // in the meantime.
        // (The top-right status banner is now the self-painting BlinkBanner widget.)
        // Crosshair: only when inside the plot area (the header buttons are child
        // widgets now, so the canvas never receives moves over them).
        if (crossX >= plot.x && crossX < plot.x + plot.width
                && crossY >= plot.y && crossY < plot.y + plot.height) {
            drawCrosshair(gc, plot, lastResult, unit, freqMin, freqMax, magTop, magBot, logFreq);
        }
        drawRectZoomOverlay(gc, area.width, area.height);
        logRenderStep("3.11 dynamic overlay");
        if (log.isWarnEnabled() && gotFftResult && DebugSwitches.SHOW_FFT_ANALYZE_TIME) {
            log.warn("          3. FFT result rendered in {} ms", (double)(System.nanoTime() - startRender) / 1_000_000);
            gotFftResult = false;
        }
    }

    /** Logs one step of the stage-3 breakdown - the named part of putting a
     *  fresh result on screen - and re-stamps {@link #renderStepMark}, so the
     *  next step is measured from the end of this one and the steps add up to
     *  the total the end of the paint logs.  Silent unless the analyse-time
     *  switch is on and a fresh result is being rendered: a repaint that only
     *  moves the crosshair repeats no step, and the elapsed-time formatting is
     *  not paid when nothing reads it. */
    private void logRenderStep(String name) {
        if (!gotFftResult || !DebugSwitches.SHOW_FFT_ANALYZE_TIME || !log.isWarnEnabled()) {
            return;
        }
        long now = System.nanoTime();
        log.warn("          {} took {} ms", name,
                String.format(Locale.US, "%.2f", (now - renderStepMark) / NANOS_PER_MS));
        renderStepMark = now;
    }

    // -------------------------------------------------------------------------
    // Rectangular zoom (base machinery in AbstractMeasurementView)
    // -------------------------------------------------------------------------

    /** X = displayed frequency window (Hz), Y = the magnitude window in
     *  CANONICAL dBFS - the unit the range prefs store for every display unit. */
    @Override
    protected ZoomState captureZoomState() {
        Preferences prefs = Preferences.instance();
        return new ZoomState(prefs.getFftFreqMinHz(), prefs.getFftFreqMaxHz(),
                new double[] { prefs.getFftMagBottom() },
                new double[] { prefs.getFftMagTop() });
    }

    /** Applies through the canonical range-change protocol; the pane's
     *  scrollbar re-sync clamps to [binSize, Nyquist] ×
     *  [floor, ceiling] and realigns the scrollbars. */
    @Override
    protected boolean applyZoomState(ZoomState s) {
        Preferences prefs = Preferences.instance();
        prefs.setFftFreqMinHz(s.xMin());
        prefs.setFftFreqMaxHz(s.xMax());
        prefs.setFftMagBottom(s.yMin()[0]);
        prefs.setFftMagTop(s.yMax()[0]);
        prefs.save();
        fireRangeChanged();
        redraw();
        return true;
    }

    /** Log-aware on X (the axis flag decides); Y maps the pixel fraction
     *  linearly onto the dBFS prefs - the wheel-zoom convention, correct for
     *  every display unit since linear-in-dB equals log-in-V. */
    @Override
    protected ZoomState zoomStateForRect(Rectangle sel) {
        Preferences prefs = Preferences.instance();
        Rectangle plot = zoomableArea();
        if (plot == null || plot.width <= 0 || plot.height <= 0) return null;
        boolean logFreq = prefs.isFftLogFreqAxis();
        // Mirror the paint's axis preparation exactly (same order): floor fMin
        // at 0, stretch sub-1-Hz spans to 1 Hz, then the log floor - otherwise
        // a selection on a stretched axis maps onto the wrong frequencies.
        double fMin = Math.max(0, prefs.getFftFreqMinHz());
        double fMax = Math.max(fMin + 1, prefs.getFftFreqMaxHz());
        if (logFreq && fMin < 1) fMin = 1;      // the paint floors log fMin at 1 Hz
        double top  = prefs.getFftMagTop();
        double span = top - prefs.getFftMagBottom();
        double newTop = top - (sel.y - plot.y) / (double) plot.height * span;
        double newBot = top - (sel.y + sel.height - plot.y) / (double) plot.height * span;
        return new ZoomState(
                xToFreq(sel.x, plot, fMin, fMax, logFreq),
                xToFreq(sel.x + sel.width, plot, fMin, fMax, logFreq),
                new double[] { newBot }, new double[] { newTop });
    }

    /** Selections live inside the plot area (between the axis-label margins). */
    @Override
    protected Rectangle zoomableArea() {
        Rectangle area = getClientArea();
        return new Rectangle(MARGIN_LEFT, MARGIN_TOP,
                area.width - MARGIN_LEFT - 1, area.height - MARGIN_TOP - MARGIN_BOTTOM);
    }

    /** Tints the spectrum area outside the active distortion-HP /
     *  distortion-LP window with a light grey, so the user can see at a
     *  glance which bands are excluded from THD / SNR integration. */
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
                // A filled band, not a trace: fillRectangle takes whole pixels,
                // so the fractional edge is rounded here rather than upstream.
                int xR = (int) Math.round(freqToX(Math.min(hp, freqMax), plot, freqMin, freqMax, logFreq));
                if (xR > xL) gc.fillRectangle(xL, plot.y, xR - xL, plot.height);
            }
        }
        if (lpOn) {
            double lp = prefs.getFftDistMaxHz();
            if (lp < freqMax) {
                int xL = (int) Math.round(freqToX(Math.max(lp, freqMin), plot, freqMin, freqMax, logFreq));
                int xR = plot.x + plot.width;
                if (xR > xL) gc.fillRectangle(xL, plot.y, xR - xL, plot.height);
            }
        }
    }

    /** Draws a small red dot at the fundamental peak and at every
     *  detected harmonic.  Helps the user see at a glance which peaks the
     *  analyser picked up.
     *
     * @param gc      the graphics context
     * @param plot    the plot area
     * @param r       the result whose lines are drawn
     * @param unit    the magnitude unit of the axis
     * @param freqMin the lowest frequency shown, Hz
     * @param freqMax the highest frequency shown, Hz
     * @param magTop  the top of the magnitude axis, in {@code unit}
     * @param magBot  the bottom of the magnitude axis, in {@code unit}
     * @param logFreq whether the frequency axis is logarithmic */
    private void drawHarmonicDots(GC gc, Rectangle plot, FftResult r,
                                  MagnitudeUnit unit,
                                  double freqMin, double freqMax,
                                  double magTop, double magBot,
                                  boolean logFreq) {
        Preferences prefs = Preferences.instance();
        double binBw = r.freqResolution;

        // Each plotDotAt gates on the dot's magnitude vs the visible
        // range and skips drawing when it falls outside - that hides
        // dots cleanly without snapping them to the chart edges.
        // The fundamental dot sits on the DISPLAYED peak: the manual
        // fundamental when set (the statistics stretch the trace's lobe to
        // it), else the de-embedded level.  Its BLUE dot stands on
        // the level as measured wherever the red one stands elsewhere - the
        // de-embedding or the manual fundamental moved it.
        SpectralLine fundamental = r.fundamental;
        double fundDotDbFs = Double.isFinite(r.fundamentalTrueDbFs)
                ? r.fundamentalTrueDbFs : fundamental.deembeddedAmplitudeDbFs();
        // Blue dots painted FIRST so the red dots sit on top.
        gc.setBackground(color(ColorRole.BEFORE_CAL_DOT));
        if (fundamental.rawAmplitudeDbFs() != fundDotDbFs) {
            plotDotAt(gc, plot, fundamental.frequencyHz(), fundamental.rawAmplitudeDbFs(),
                    unit, freqMin, freqMax, magTop, magBot, logFreq);
        }
        List<SpectralLine> harmonics = r.harmonicLines();
        drawMeasuredDots(gc, plot, harmonics, unit, freqMin, freqMax, magTop, magBot, logFreq);
        gc.setBackground(color(ColorRole.HARMONIC_DOT));
        plotDotAt(gc, plot, fundamental.frequencyHz(), fundDotDbFs,
                unit, freqMin, freqMax, magTop, magBot, logFreq);
        for (SpectralLine harmonic : harmonics) {
            plotDotAt(gc, plot, harmonic.frequencyHz(), harmonic.deembeddedAmplitudeDbFs(),
                    unit, freqMin, freqMax, magTop, magBot, logFreq);
        }

        // Hn labels on top of every (corrected) dot - fundamental gets
        // the full "H1 ⟨freq⟩" pair, harmonics get just "Hn".  Only
        // the harmonics within the calc-max-harmonic count are
        // labelled (matches the THD-tab's calcMaxHarmonic pref).
        gc.setForeground(color(ColorRole.HARMONIC_DOT));
        drawHarmonicLabel(gc, plot, "F " + formatFrequency(fundamental.frequencyHz()),
                fundamental.frequencyHz(), fundDotDbFs,
                unit, freqMin, freqMax, magTop, magBot, logFreq);
        int calcMax = Math.max(9, prefs.getFftCalcMaxHarmonic());
        for (int i = 0; i < harmonics.size() && (i + 2) <= calcMax; i++) {
            SpectralLine harmonic = harmonics.get(i);
            drawHarmonicLabel(gc, plot, "H" + (i + 2),
                    harmonic.frequencyHz(), harmonic.deembeddedAmplitudeDbFs(),
                    unit, freqMin, freqMax, magTop, magBot, logFreq);
        }

        // DEBUG overlay: spectral discontinuity reject gates.
        drawDiscontinuityGates(gc, plot, r, unit,
                freqMin, freqMax, magTop, magBot, logFreq, binBw);
    }

    /** DEBUG: overlays the spectral discontinuity detector's three reject gates
     *  - gate-2 floor-reference curve (dark cyan) + reject boundary (cyan),
     *  gate-1 near-carrier pedestal reject line (orange), gate-3 total-power
     *  line + reject band (magenta) - so it's visible what each gate would
     *  reject.  Gate 2 stands on each band's band-top of the DISPLAYED
     *  spectrum, {@link #GATE2_LINE_OFFSET_DB} above it; gates 1 and 3 are
     *  positioned by their dB excess over the detector's floor, added to the
     *  displayed (calibrated) floor, so they land at the right vertical
     *  position whatever calibration the chart applies.  A gate that fired
     *  this block is drawn thicker.
     *
     * @param gc      the graphics context
     * @param plot    the plot area
     * @param r       the displayed result
     * @param unit    the magnitude unit of the axis
     * @param freqMin the lowest frequency shown, Hz
     * @param freqMax the highest frequency shown, Hz
     * @param magTop  the axis top, in {@code unit}
     * @param magBot  the axis bottom, in {@code unit}
     * @param logFreq whether the frequency axis is logarithmic
     * @param binBw   the bin width, Hz */
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

        // Displayed (calibrated) floor per band = the band's band-top in dBFs,
        // the level the detector reads per band.
        double[] floorDisp = new double[nb];
        // Gate 2's anchor per band: the displayed band-top, the offset above it.
        double[] gate2Disp = new double[nb];
        for (int b = 0; b < nb; b++) {
            int lo = Math.max(1, g.bandLo[b]);
            int hi = Math.min(n, g.bandHi[b]);
            floorDisp[b] = bandTopRange(dbfs, lo, hi);
            gate2Disp[b] = floorDisp[b] + GATE2_LINE_OFFSET_DB;
        }

        // Per-band band-tops of the pre-average blocks (the quantity gate 2
        // compares) and the line bands a tone dominates - gate 2 excludes those, so
        // they are skipped in the reference and the block curves alike.
        double[] curLvl = g.bandTopDb;
        boolean[] line  = g.lineBand;

        // gate 2: per-band band-top reference (dark cyan) + reject boundary (cyan).
        double prevXr = Double.NaN, prevYr = 0, prevXb = Double.NaN, prevYb = 0;
        for (int b = 0; b < nb; b++) {
            if (line[b] || Double.isNaN(gate2Disp[b])) {
                prevXr = prevXb = Double.NaN;
                continue;
            }
            double x = freqToX(0.5 * (g.bandLo[b] + g.bandHi[b]) * binBw, plot, freqMin, freqMax, logFreq);
            // The band's reference over the detector's floor, on the same
            // anchor the block curve stands on: the gap to that curve is the
            // band-top over the reference, the lift gate 2 judges.
            double ref = gate2Disp[b] + (g.mref[b] - g.floorDb);
            double yr = gateY(ref, unit, plot, magTop, magBot);
            double yb = gateY(ref + g.scoreThreshDb, unit, plot, magTop, magBot);
            gc.setForeground(display.getSystemColor(SWT.COLOR_DARK_CYAN));
            gc.setLineWidth(3);
            if (!Double.isNaN(prevXr)) drawLineAt(gc, prevXr, prevYr, x, yr);
            gc.setForeground(display.getSystemColor(SWT.COLOR_CYAN));
            gc.setLineWidth(rejScore ? 2 : 1);
            if (!Double.isNaN(prevXb)) drawLineAt(gc, prevXb, prevYb, x, yb);
            prevXr = x; prevYr = yr; prevXb = x; prevYb = yb;
        }

        // gate 1: near-carrier pedestal - reject threshold (dark yellow) AND the
        // rejected block's ACTUAL pedestal (red, thick when it fired), so the
        // excess that tripped it is visible.
        if (g.peakBins != null && !Double.isNaN(g.pedestalThreshDb)) {
            int half = Math.max(24, plot.width / 16);
            double threshExc = rej != null && !Double.isNaN(rej.pedestalThreshDb)
                    ? rej.pedestalThreshDb : g.pedestalThreshDb;
            // Base = the fundamental's flank floor on the displayed trace, the
            // reference the detector measures the pedestal excess against.
            IntToDoubleFunction dispMag = k -> Math.pow(10.0, dbfs[k] / 20.0);
            for (int pk : g.peakBins) {
                double floorLin = FftDsp.pedestalFloor(dispMag, pk, n - 1, binBw, r.windowType);
                if (!(floorLin > 0.0)) continue;
                double cf = 20.0 * Math.log10(floorLin);
                double xc = freqToX(pk * binBw, plot, freqMin, freqMax, logFreq);
                double xL = Math.max(plot.x, xc - half);
                double xR = Math.min(plot.x + plot.width, xc + half);
                gc.setForeground(display.getSystemColor(SWT.COLOR_DARK_YELLOW));
                gc.setLineWidth(1);
                double yT = gateY(cf + threshExc, unit, plot, magTop, magBot);
                drawLineAt(gc, xL, yT, xR, yT);
                if (rej != null && !Double.isNaN(rej.pedestalExcessDb)) {
                    gc.setForeground(display.getSystemColor(SWT.COLOR_RED));
                    gc.setLineWidth(rejPed ? 2 : 1);
                    double yA = gateY(cf + rej.pedestalExcessDb, unit, plot, magTop, magBot);
                    drawLineAt(gc, xL, yA, xR, yA);
                }
            }
        }

        // gate 3: total-power line + reject band (magenta), based on the
        // displayed floor read the way the detector reads its own.
        double medFloor = FftDsp.bandTopOfLevelsDb(floorDisp, line, nb);
        if (!Double.isNaN(medFloor) && g.bins > 0) {
            double perBin = 10.0 * Math.log10(g.bins);
            int xr = plot.x + plot.width;
            gc.setForeground(display.getSystemColor(SWT.COLOR_MAGENTA));
            gc.setLineWidth(rejPow ? 2 : 1);
            double yCur = gateY(medFloor + g.powerDb - perBin - g.floorDb, unit, plot, magTop, magBot);
            drawLineAt(gc, plot.x, yCur, xr, yCur);
            if (g.powerThreshDb > 0) {
                gc.setLineWidth(3);
                gc.setLineStyle(SWT.LINE_DOT);
                double mid = medFloor + g.powerMedDb - perBin - g.floorDb;
                double yLo = gateY(mid - g.powerThreshDb, unit, plot, magTop, magBot);
                double yHi = gateY(mid + g.powerThreshDb, unit, plot, magTop, magBot);
                drawLineAt(gc, plot.x, yLo, xr, yLo);
                drawLineAt(gc, plot.x, yHi, xr, yHi);
                gc.setLineStyle(SWT.LINE_SOLID);
            }
            if (rej != null) {   // rejected block's total power vs the band
                gc.setForeground(display.getSystemColor(SWT.COLOR_RED));
                gc.setLineWidth(rejPow ? 2 : 1);
                double yRej = gateY(medFloor + rej.powerDb - perBin - rej.floorDb, unit, plot, magTop, magBot);
                drawLineAt(gc, plot.x, yRej, xr, yRej);
            }
        }

        // Current accepted block - per-band level curve (grey, context).
        drawLevelCurve(gc, plot, curLvl, line, gate2Disp, g, unit,
                freqMin, freqMax, magTop, magBot, logFreq, binBw, SWT.COLOR_GRAY, false);
        // Full REJECTED FFT (red) at bin resolution - its fundamental rigidly
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
        // current block, firing gate marked «<<» - over-rejection visible at a glance.
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
        // Column-min curve with a SUB-PIXEL Y per column: the bucket index is a
        // whole column (that is what a column is), the level kept in it is not.
        double[] colY = new double[w + 1];
        Arrays.fill(colY, Double.POSITIVE_INFINITY);
        for (int k = kFirst; k <= kLast; k++) {
            double v = convertFromDbFs(plotDbFs[k], unit, null);
            double y = Math.max(plot.y, Math.min(plot.y + plot.height, magToY(v, plot, magTop, magBot, unit)));
            int col = (int) Math.round(freqToX(k * binBw, plot, freqMin, freqMax, logFreq)) - plot.x;
            if (col >= 0 && col <= w && y < colY[col]) colY[col] = y;
        }
        gc.setForeground(getDisplay().getSystemColor(swtColor));
        gc.setLineWidth(1);
        double prevX = Double.NaN, prevY = 0;
        for (int col = 0; col <= w; col++) {
            if (colY[col] == Double.POSITIVE_INFINITY) continue;
            double x = plot.x + col;
            if (!Double.isNaN(prevX)) drawLineAt(gc, prevX, prevY, x, colY[col]);
            prevX = x; prevY = colY[col];
        }
    }

    /** Builds the rejected block's full-resolution dBFs curve, positioned to
     *  ATTACH its fundamental onto the displayed (averaged) fundamental:
     *  <ul>
     *   <li>plain spectrum - a rigid dB shift lands the block's fundamental peak
     *       on the displayed peak (NO stretch); the block's higher single-shot
     *       floor then rides above the average, exposing the near-carrier skirt
     *       that tripped the reject.</li>
     *   <li>.frc - every bin the held calibration cascade corrected is lifted
     *       by the same dB, so the block lands on the corrected scale.</li>
     *   <li>manual-fundamental - the displayed fundamental is itself a
     *       stretched dome, so the block's lobe is stretched the SAME way (same
     *       {@link ToneLobeLift#stretch}) up to the displayed peak.</li>
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
        //    red lands on blue's scale and is directly comparable: every bin the
        //    held cascade corrected is lifted here by the same dB the correction
        //    took off the displayed spectrum there, and a bin no row reached is
        //    left alone.
        if (calInv != null) {
            SpectralLine[] lines = r.toneLines();
            for (int k = kFirst; k <= kLast; k++) {
                out[k] -= FreqRespCalHelper.gainDbAt(lines, calInv, k);
            }
        }

        // 2. Manual fundamental - the statistics lifted the displayed peak to
        //    it, not the block's, so lift red's fundamental lobe to the user
        //    value too.
        boolean manual = Double.isFinite(r.fundamentalTrueDbFs)
                && Double.isFinite(r.fundamentalDbFs) && Double.isFinite(r.fundamentalHzRefined);
        double targetDbFs;
        if (manual) {
            targetDbFs = r.fundamentalTrueDbFs;
            stretchLobe(out, omag, fundBin, half,
                    Math.pow(10.0, (targetDbFs - out[fundBin]) / 20.0), binBw, r.windowType);
        } else {
            double dispPk = Double.NEGATIVE_INFINITY;
            for (int d = -2; d <= 2; d++) {
                int k = fundBin + d;
                if (k >= 1 && k < disp.length) dispPk = Math.max(dispPk, disp[k]);
            }
            targetDbFs = dispPk;
        }

        // 3. Final attach: rigid shift landing red's fundamental peak exactly on
        //    the displayed one (a small residual - the cal already matched it).
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

    /** Stretches one tone lobe in {@code out} by a linear {@code factor} via the
     *  shared {@link ToneLobeLift#stretch} (peak ×factor, wings pinned to the
     *  floor). */
    private void stretchLobe(double[] out, IntToDoubleFunction mag, int toneBin, int half,
                             double factor, double binHz, WindowType window) {
        if (toneBin < 1 || toneBin > half) return;
        double floor   = FftDsp.pedestalFloor(mag, toneBin, half, binHz, window);
        int[]  edges   = FftDsp.lobeBins(mag, toneBin, half, floor, binHz, window);
        double peakMag = mag.applyAsDouble(toneBin);
        for (int k = edges[0]; k <= edges[1]; k++) {
            if (k < 1 || k > half || k >= out.length) continue;
            double nm = ToneLobeLift.stretch(mag.applyAsDouble(k), floor, peakMag, factor);
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

    /** The band-top of {@code a[lo..hi)} - the upper edge of the band's noise,
     *  {@link FftDsp#bandTop} of the bins' magnitudes - in dBFs.
     *
     * @param a  a spectrum, dBFs per bin
     * @param lo the band's first bin
     * @param hi one past the band's last bin
     * @return the band-top, dBFs; NaN when the band is empty */
    private double bandTopRange(double[] a, int lo, int hi) {
        int len = hi - lo;
        if (len <= 0) return Double.NaN;
        double[] magnitudes = new double[len];
        for (int k = lo; k < hi; k++) magnitudes[k - lo] = Math.pow(10.0, a[k] / 20.0);
        return 20.0 * Math.log10(FftDsp.bandTop(magnitudes, len) + 1e-300);
    }

    /** Draws a block's per-band level curve, each band at its {@code anchor}
     *  plus the band's excess over the detector's floor of the block (so it
     *  shares the gate reference's frame); line bands are skipped.
     *
     * @param gc       the graphics context
     * @param plot     the plot area
     * @param lvl      the block's band-top per band in the detector's dB; {@code null} for none
     * @param line     the line bands, skipped
     * @param anchor   where each band's curve stands, dBFs
     * @param g        the gate snapshot whose bands are drawn
     * @param unit     the magnitude unit of the axis
     * @param freqMin  the lowest frequency shown, Hz
     * @param freqMax  the highest frequency shown, Hz
     * @param magTop   the axis top, in {@code unit}
     * @param magBot   the axis bottom, in {@code unit}
     * @param logFreq  whether the frequency axis is logarithmic
     * @param binBw    the bin width, Hz
     * @param swtColor the SWT system colour of the curve
     * @param dashed   whether the curve is dashed */
    private void drawLevelCurve(GC gc, Rectangle plot, double[] lvl, boolean[] line, double[] anchor,
                                SpectralDiscontinuityDetector.Gates g, MagnitudeUnit unit,
                                double freqMin, double freqMax, double magTop, double magBot,
                                boolean logFreq, double binBw, int swtColor, boolean dashed) {
        if (lvl == null) return;
        double floor = g.floorDb;
        gc.setForeground(getDisplay().getSystemColor(swtColor));
        gc.setLineWidth(3);
        gc.setLineStyle(dashed ? SWT.LINE_DASH : SWT.LINE_SOLID);
        double prevX = Double.NaN, prevY = 0;
        for (int b = 0; b < lvl.length; b++) {
            if (line[b] || Double.isNaN(lvl[b]) || Double.isNaN(anchor[b])) {
                prevX = Double.NaN;
                continue;
            }
            double dispDbFs = anchor[b] + (lvl[b] - floor);
            double y = gateY(dispDbFs, unit, plot, magTop, magBot);
            double x = freqToX(0.5 * (g.bandLo[b] + g.bandHi[b]) * binBw, plot, freqMin, freqMax, logFreq);
            if (!Double.isNaN(prevX)) drawLineAt(gc, prevX, prevY, x, y);
            prevX = x; prevY = y;
        }
        gc.setLineStyle(SWT.LINE_SOLID);
    }

    /** {@code drawLine} on fractional coordinates.  The debug gate overlays map
     *  through the same sub-pixel helpers as everything else and round only
     *  here, at the SWT primitive, which has no fractional form - one place
     *  instead of a rounding pair at each of the nine call sites. */
    private void drawLineAt(GC gc, double x0, double y0, double x1, double y1) {
        gc.drawLine((int) Math.round(x0), (int) Math.round(y0),
                    (int) Math.round(x1), (int) Math.round(y1));
    }

    /** A gate level's Y, SUB-PIXEL and clamped to the plot. */
    private double gateY(double dbFs, MagnitudeUnit unit, Rectangle plot, double magTop, double magBot) {
        double v = convertFromDbFs(dbFs, unit, null);
        double y = magToY(v, plot, magTop, magBot, unit);
        return Math.max(plot.y, Math.min(plot.y + plot.height, y));
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
        double v = convertFromDbFs(dbFs, unit, null);
        double t = magToYFraction(v, magTop, magBot, unit);
        if (t < 0 || t > 1) return null;
        // An SWT Point is integer, and what this feeds is the label stacking,
        // which works in whole text rows - so the fractional position is rounded
        // here, once, rather than upstream.
        return new Point((int) Math.round(freqToX(hz, plot, freqMin, freqMax, logFreq)),
                         (int) Math.round(magToY(v, plot, magTop, magBot, unit)));
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
        double v = convertFromDbFs(dbFs, unit, null);
        double t = magToYFraction(v, magTop, magBot, unit);
        if (t < 0 || t > 1) return;
        // Anchored on the dot's FRACTIONAL centre and handed over fractionally:
        // the caption's origin rides the same sub-pixel column and height as
        // the dot it names, through the painter's fractional text primitive.
        double x = freqToX(hz, plot, freqMin, freqMax, logFreq);
        double y = magToY(v, plot, magTop, magBot, unit);
        Point ext = gc.textExtent(text);
        double lx = Math.max(plot.x, Math.min((double) (plot.x + plot.width - ext.x),
                x - ext.x / 2.0));
        double ly = Math.max(plot.y, y - ext.y - 4);
        drawOutlinedText(gc, text, lx, ly);
    }

    /** Paints a BLUE "before-cal" dot at every line's level as measured,
     *  where it differs from the de-embedded one - the red dot painted
     *  afterwards sits on top.  Without de-embedding the two are equal and no
     *  blue dot is drawn.
     *
     * @param gc      the graphics context
     * @param plot    the plot area
     * @param lines   the lines whose measured levels are drawn
     * @param unit    the magnitude unit of the axis
     * @param freqMin the lowest frequency shown, Hz
     * @param freqMax the highest frequency shown, Hz
     * @param magTop  the top of the magnitude axis, in {@code unit}
     * @param magBot  the bottom of the magnitude axis, in {@code unit}
     * @param logFreq whether the frequency axis is logarithmic */
    private void drawMeasuredDots(GC gc, Rectangle plot, List<SpectralLine> lines,
                                  MagnitudeUnit unit,
                                  double freqMin, double freqMax,
                                  double magTop, double magBot,
                                  boolean logFreq) {
        gc.setBackground(color(ColorRole.BEFORE_CAL_DOT));
        for (SpectralLine line : lines) {
            if (line.rawAmplitudeDbFs() == line.deembeddedAmplitudeDbFs()) continue;
            plotDotAt(gc, plot, line.frequencyHz(), line.rawAmplitudeDbFs(),
                    unit, freqMin, freqMax, magTop, magBot, logFreq);
        }
    }

    /** The dual-tone lines in dot order.
     *
     * @param imd the intermodulation readout
     * @return F1, F2, then the lower and the upper product of each order
     *         2..{@link ImdResult#MAX_ORDER} */
    private List<SpectralLine> imdLines(ImdResult imd) {
        List<SpectralLine> lines = new ArrayList<>(IMD_LINES);
        lines.add(imd.f1);
        lines.add(imd.f2);
        for (int k = 2; k <= ImdResult.MAX_ORDER; k++) {
            lines.add(imd.dnL[k]);
            lines.add(imd.dnH[k]);
        }
        return lines;
    }

    /** Paints F1, F2 and the per-order dnL / dnH IMD-product dots +
     *  labels on the spectrum.  Same visual treatment as
     *  {@link #drawHarmonicDots} so the user sees consistent marker
     *  styling across THD and IMD modes - red dots on every measured
     *  spike, blue "before-cal" dots underneath wherever the red dot
     *  stands elsewhere than the level as measured.  Dots that fall outside
     *  the visible mag range are skipped (the {@link #plotDotAt} helper
     *  handles the bounds check).
     *
     * @param gc      the graphics context
     * @param plot    the plot area
     * @param imd     the intermodulation readout whose lines are drawn
     * @param r       the result the readout belongs to
     * @param unit    the magnitude unit of the axis
     * @param freqMin the lowest frequency shown, Hz
     * @param freqMax the highest frequency shown, Hz
     * @param magTop  the top of the magnitude axis, in {@code unit}
     * @param magBot  the bottom of the magnitude axis, in {@code unit}
     * @param logFreq whether the frequency axis is logarithmic */
    private void drawImdDots(GC gc, Rectangle plot, ImdResult imd,
                             FftResult r,
                             MagnitudeUnit unit,
                             double freqMin, double freqMax,
                             double magTop, double magBot,
                             boolean logFreq) {
        // Every red dot's (freq, de-embedded dBFS), in the order of imdLines.
        // The tones stand at their displayed level - each tone's share of the
        // manual fundamental when one is set, where the lobe lift puts its
        // lobe - the products at their de-embedded level.
        List<SpectralLine> lines = imdLines(imd);
        int count = lines.size();
        double[] hz   = new double[count];
        double[] dbFs = new double[count];
        for (int i = 0; i < count; i++) {
            hz[i]   = lines.get(i).frequencyHz();
            dbFs[i] = lines.get(i).deembeddedAmplitudeDbFs();
        }
        dbFs[0] = dbFsOfMagnitude(imd.f1Mag);
        dbFs[1] = dbFsOfMagnitude(imd.f2Mag);

        // Blue dots first so the red dots sit on top: each line's level as
        // measured, wherever its red dot stands elsewhere - the de-embedding
        // or the manual fundamental moved it.
        gc.setBackground(color(ColorRole.BEFORE_CAL_DOT));
        for (int i = 0; i < count; i++) {
            double rawDbFs = lines.get(i).rawAmplitudeDbFs();
            if (rawDbFs != dbFs[i]) {
                plotDotAt(gc, plot, hz[i], rawDbFs,
                        unit, freqMin, freqMax, magTop, magBot, logFreq);
            }
        }

        // Red post-cal dots on every measured spike.
        gc.setBackground(color(ColorRole.HARMONIC_DOT));
        for (int i = 0; i < count; i++) {
            plotDotAt(gc, plot, hz[i], dbFs[i],
                    unit, freqMin, freqMax, magTop, magBot, logFreq);
        }

        // F1 / F2 labels: each anchored just above its own dot, but if
        // the two labels would overlap the lower-dot's label slides up
        // - either into the gap between the higher dot's label and
        // the lower dot (when there's room), or stacked one line above
        // the higher dot's label (when too cramped).  Both labels stay
        // strictly above their own dots so the dot itself is never
        // obscured.
        gc.setForeground(color(ColorRole.HARMONIC_DOT));
        double f1Hz = imd.f1.frequencyHz();
        double f2Hz = imd.f2.frequencyHz();
        Point p1 = dotScreenPos(plot, f1Hz, dbFs[0], unit, freqMin, freqMax,
                magTop, magBot, logFreq);
        Point p2 = dotScreenPos(plot, f2Hz, dbFs[1], unit, freqMin, freqMax,
                magTop, magBot, logFreq);
        if (p1 != null || p2 != null) {
            // Horizontal centring runs off the FRACTIONAL dot column and is
            // handed over fractionally - as the dnL / dnH captions do through
            // drawHarmonicLabel.  The Point above stays integer on purpose: it
            // drives the vertical stacking below, which works in whole text
            // rows.
            double x1f = freqToX(f1Hz, plot, freqMin, freqMax, logFreq);
            double x2f = freqToX(f2Hz, plot, freqMin, freqMax, logFreq);
            String t1 = "F1 " + formatFrequency(f1Hz);
            String t2 = "F2 " + formatFrequency(f2Hz);
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
                        // F2 dot higher than F1 dot - mirror of the above.
                        int candTop = bot2 + gap;
                        int candBot = candTop + ext1.y;
                        ly1 = (candBot + margin <= p1.y)
                                ? candTop
                                : ly2 - gap - ext1.y;
                    }
                }
            }
            if (p1 != null) {
                double lx = Math.max(plot.x, Math.min((double) (plot.x + plot.width - ext1.x),
                        x1f - ext1.x / 2.0));
                drawOutlinedText(gc, t1, lx, (double) Math.max(plot.y, ly1));
            }
            if (p2 != null) {
                double lx = Math.max(plot.x, Math.min((double) (plot.x + plot.width - ext2.x),
                        x2f - ext2.x / 2.0));
                drawOutlinedText(gc, t2, lx, (double) Math.max(plot.y, ly2));
            }
        }

        // dnL (lower sideband) + dnH (upper sideband) labels for
        // n = 2..5, anchored on each product's red dot.
        for (int k = 2; k <= ImdResult.MAX_ORDER; k++) {
            drawHarmonicLabel(gc, plot, "d" + k + "L",
                    imd.dnL[k].frequencyHz(), imd.dnL[k].deembeddedAmplitudeDbFs(),
                    unit, freqMin, freqMax, magTop, magBot, logFreq);
            drawHarmonicLabel(gc, plot, "d" + k + "H",
                    imd.dnH[k].frequencyHz(), imd.dnH[k].deembeddedAmplitudeDbFs(),
                    unit, freqMin, freqMax, magTop, magBot, logFreq);
        }
    }

    private void plotDotAt(GC gc, Rectangle plot, double hz, double dbFs,
                           MagnitudeUnit unit,
                           double freqMin, double freqMax,
                           double magTop, double magBot,
                           boolean logFreq) {
        if (!Double.isFinite(hz) || hz < freqMin || hz > freqMax) return;
        if (!Double.isFinite(dbFs)) return;
        Preferences prefs = Preferences.instance();
        double v = convertFromDbFs(dbFs, unit, null);
        // Drop the dot entirely when its magnitude is outside the
        // visible range - dots clamped to the edge would otherwise
        // appear as misleading markers at the chart's top / bottom.
        double t = magToYFraction(v, magTop, magBot, unit);
        if (t < 0 || t > 1) return;
        // SUB-PIXEL centre, for both the red measured dots and the blue
        // before-cal ones - every marker in this view comes through here.  The
        // integer fillOval quantised the dot to whole pixels and, with an even
        // diameter, seated it half a pixel up and to the left of the value it
        // marks, because the box offset is diameter/2 in integer division.  The
        // float-Path oval is the same primitive the scope's sample dots use;
        // both paint paths into this view already run antialiased, which is
        // what makes a fractional centre visible rather than re-quantised.
        double x = freqToX(hz, plot, freqMin, freqMax, logFreq);
        double y = magToY(v, plot, magTop, magBot, unit);
        int diameter = Math.max(2, prefs.getFftHarmonicDotDiameter());
        new GcMeasurementPainter(gc).fillOvalAt(x, y, diameter);
    }

    /** Mixes every value that affects the cached image into a single
     *  long.  Identity-hash of {@link FftResult} captures
     *  "new analysis result" without copying the arrays; the rest of
     *  the fields are simple primitives.  Two paints with the same
     *  fingerprint always produce the same cached image. */
    /** Static-trace-layer cache key.  Lombok {@code @EqualsAndHashCode} derives
     *  the fingerprint hash from every rendering input - adding an input is
     *  adding a field, with no hand-rolled hash chain to forget it in.  Fields
     *  without a {@code hashCode} override (results) contribute their identity
     *  hash, matching the previous behaviour. */
    @RequiredArgsConstructor
    @EqualsAndHashCode
    private static final class TraceFingerprint {
        private final FftResult result;
        /** Ticks once per published result, so every analysis fingerprints
         *  fresh.  The result's identityHashCode ALONE turned out not to be
         *  reliable in long runs - under sustained GC churn the hash of
         *  consecutive Result objects could repeat for stretches lasting
         *  seconds-to-minutes, causing the static trace cache to blit a stale
         *  image even though the worker was producing fresh results (visible
         *  as a frozen chart with the averages counter still ticking). */
        private final long completedAnalyses;
        /** IMD slot - a form-driven null ↔ ImdResult transition must
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
        // Appearance prefs - when these change the cached trace image would
        // otherwise still show the old colour / width.
        private final double lineWidth;
        private final int harmonicDotDiameter;
        private final int lineColor;
        private final int chartBackgroundColor;
        private final int harmonicDotColor;
        private final int beforeCalDotColor;
        private final int freqRespColor;
        private final int calOverlayColor;
        /** Cal entry list - adding / removing a row (or flipping withNoise)
         *  would otherwise leave the overlay curve stale until the next FFT
         *  result swaps the result reference. */
        private final List<CorrectionStore.Entry> calEntries;
        // THD / IMD readout table inputs - the table renders into the cached
        // layer, so its visibility / mode / extraction state must invalidate
        // the image (its data identity is already covered by result + imd +
        // completedAnalyses above).
        private final boolean tableVisible;
        private final boolean tableImd;
        private final boolean tableIsExtracted;
        /** Unit of the table's per-harmonic / per-product levels.  Independent of
         *  {@link #unit} (the magnitude axis): switching it re-renders the table
         *  alone, and without it here the cached layer would keep the old cells
         *  until something else invalidated the image. */
        private final MagnitudeUnit tableUnit;
        // Manual-fundamental + max-harmonic THD inputs.  Changing them recomputes
        // the displayed STATIC result's THD / harmonics IN PLACE (same result
        // reference), so without them in the key the cached image would not
        // refresh - and they also flip whether the signal clears the noise floor,
        // i.e. whether the table + F/Hn dots are drawn at all.
        private final boolean manualFundEnabled;
        private final double manualFundVrms;
        private final int calcMaxHarmonic;
        private final int thdMaxHarmonic;
    }

    private long computeTraceFingerprint(FftResult r, Rectangle area,
                                         MagnitudeUnit unit,
                                         double freqMin, double freqMax,
                                         double magTop, double magBot,
                                         boolean logFreq) {
        Preferences prefs = Preferences.instance();
        return new TraceFingerprint(
                r, controller.completedAnalyses(), r != null ? r.imd : null,
                area.width, area.height, unit,
                freqMin, freqMax, magTop, magBot, logFreq,
                prefs.isFftDistMinEnabled(), prefs.isFftDistMaxEnabled(),
                prefs.getFftDistMinHz(), prefs.getFftDistMaxHz(),
                prefs.getFftLineWidth(), prefs.getFftHarmonicDotDiameter(),
                prefs.getFftLineColor(), prefs.getFftChartBackgroundColor(),
                prefs.getFftHarmonicDotColor(), prefs.getFftBeforeCalDotColor(),
                prefs.getFftFreqRespColor(), prefs.getFftCalOverlayColor(),
                correctionStore.getEntries(),
                prefs.isFftDistortionTableVisible(), tableModeIsImd, tableExtracted,
                prefs.getFftDistortionUnit(),
                prefs.isFftManualFundEnabled(), prefs.getFftManualFundVrms(),
                prefs.getFftCalcMaxHarmonic(), prefs.getFftThdMaxHarmonic()).hashCode();
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
     * a sample - previously those columns drew a {@code drawPoint} dot
     * with no connection.  Now the trace is one continuous polyline at
     * any zoom level, and stays O(plotWidth) for huge FFTs.
     */
    /** Display label for a magnitude unit - physics-unit symbols held in i18n (same in
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
        double[] drawnDbFs = r.amplitudeDbFs;
        int kFirst = Math.max(1,  (int) Math.floor(freqMin / binBw));
        int kLast  = Math.min(n - 1, (int) Math.ceil (freqMax / binBw));
        if (kLast <= kFirst) return;
        // Expand by one bin on either side so the trace extends ALL
        // the way to plot.x / plot.x + width.  Without this, the
        // first / last visible bins draw at an inset (their freqToX
        // sits a few pixels inside the plot edge) and the chart looks
        // like it has empty strips on the left and right.
        // kPrev starts at 1 - never include the DC bin in iteration;
        // its zero-magnitude clamps to plot.bottom and on LOG scale
        // (where freqToX clamps any f < freqMin to plot.x) would
        // pollute column 0's yMax, drawing a fat triangle at the
        // left edge of the polygon envelope.
        int kPrev = Math.max(1,     kFirst - 1);
        int kNext = Math.min(n - 1, kLast  + 1);

        // Column-bucketed polyline rendering - see ColumnBucketPainter
        // in the shared base.  FFT also tracks one bin JUST outside the
        // visible range on each side so the polyline reaches the chart
        // edges; the helper exposes setLeftAnchor / setRightAnchor for
        // exactly that.
        //
        // The bin walk is COLUMN-batched: the painter only ever keeps a
        // column's min/max, so it suffices to compare raw dBFS per bin
        // (every magnitude-unit conversion is monotonic in dBFS) and run
        // freqToX + the unit conversion on the two extremes of each pixel
        // column.  That cuts the transcendental count from one log10+pow
        // PER BIN (~1 M at fftSize 2 M - a 10-40 ms UI stall per rebuilt
        // frame) to ~4 per pixel column.  The lobe lifts (the tones to the
        // manual fundamental, the clamped lines to their readout) are already
        // in the result: the statistics put them on the trace.
        ColumnBucketPainter painter = new ColumnBucketPainter(plot);
        int    curCol   = Integer.MIN_VALUE;          // column being accumulated
        // The TRUE fractional X of the bin that opened that column.  The column
        // index is what the walk batches on and what the right-boundary lookup
        // needs; this is what the trace is actually drawn through, so a zoomed-in
        // spectrum - where a column holds ONE bin - puts its vertex on the bin's
        // own position instead of on the column's integer edge.
        double curX    = Double.NaN;
        double curMinDb = Double.POSITIVE_INFINITY;   // its raw-dBFS extremes
        double curMaxDb = Double.NEGATIVE_INFINITY;
        int    curBins  = 0;
        double fBound   = Double.NEGATIVE_INFINITY;   // f where x rounds to the next column
        for (int k = kPrev; k <= kNext; k++) {
            double f = k * binBw;
            double binDbFs = drawnDbFs[k];
            // Route by frequency, not pixel: on LOG scale freqToX clamps any
            // sub-freqMin bin to plot.x, so an x-based test would silently
            // add off-screen bins to column 0 with their own y values -
            // that's the triangle artefact.  The two off-range anchor bins
            // take the per-bin path (there are at most two of them).
            if (f < freqMin) {
                painter.setLeftAnchor(freqToX(f, plot, freqMin, freqMax, logFreq),
                        binYTrace(prefs, r, binDbFs, plot, unit, magTop, magBot));
                continue;
            }
            if (f > freqMax) {
                painter.setRightAnchor(freqToX(f, plot, freqMin, freqMax, logFreq),
                        binYTrace(prefs, r, binDbFs, plot, unit, magTop, magBot));
                continue;
            }
            if (f >= fBound) {
                // Column crossing: flush the finished column, then locate the
                // new one (one log10) and its right boundary (one pow).
                flushSpectrumColumn(painter, prefs, r, plot, unit, magTop, magBot,
                        curX, curMinDb, curMaxDb, curBins);
                curX    = freqToX(f, plot, freqMin, freqMax, logFreq);
                curCol   = (int) Math.round(curX);
                curMinDb = binDbFs;
                curMaxDb = binDbFs;
                curBins  = 1;
                fBound = columnRightBoundaryFreq(curCol, plot, freqMin, freqMax, logFreq);
                if (fBound <= f) fBound = Math.nextUp(f);   // degenerate viewport guard
            } else {
                if (binDbFs < curMinDb) curMinDb = binDbFs;
                if (binDbFs > curMaxDb) curMaxDb = binDbFs;
                curBins++;
            }
        }
        flushSpectrumColumn(painter, prefs, r, plot, unit, magTop, magBot,
                curX, curMinDb, curMaxDb, curBins);
        painter.drawTo(gc);
    }

    /** Emits one accumulated spectrum column to the painter: the column's
     *  min/max raw-dBFS extremes converted to the display unit and Y.  A
     *  single-bin column adds one point (no envelope bar - matching the
     *  per-bin path's count semantics); a multi-bin column adds both
     *  extremes. */
    private void flushSpectrumColumn(ColumnBucketPainter painter, Preferences prefs,
                                     FftResult r, Rectangle plot, MagnitudeUnit unit,
                                     double magTop, double magBot,
                                     double xAbs, double minDb, double maxDb, int bins) {
        if (bins <= 0) return;
        painter.add(xAbs, binYTrace(prefs, r, maxDb, plot, unit, magTop, magBot));
        if (bins > 1) {
            painter.add(xAbs, binYTrace(prefs, r, minDb, plot, unit, magTop, magBot));
        }
    }

    /** dBFS -> display-unit -> trace Y for one spectrum value, SUB-PIXEL.
     *  {@code ColumnBucketPainter} keeps its Y as a double so the midpoint
     *  polyline strokes between pixel rows; handing it a rounded value would
     *  stair-step the trace for nothing. */
    private double binYTrace(Preferences prefs, FftResult r, double binDbFs, Rectangle plot,
                             MagnitudeUnit unit, double magTop, double magBot) {
        double v = convertFromDbFs(binDbFs, unit, r.binBwSqrt);
        return magToYTrace(v, plot, magTop, magBot, unit);
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
        List<CorrectionStore.Entry> entries =
                correctionStore.getEntries();
        if (entries.isEmpty()) return;
        Preferences prefs = Preferences.instance();
        // Anchor at H2 in THD mode, or at d2L (the order-2 lower IMD product) in
        // IMD mode - H2 isn't marked there.
        double anchorFreq, anchorDbFs;
        ImdResult imd = r.imd;
        if (imd != null && Double.isFinite(imd.dnL[2].deembeddedAmplitudeDbFs())) {
            anchorFreq = imd.dnL[2].frequencyHz();
            anchorDbFs = imd.dnL[2].deembeddedAmplitudeDbFs();
        } else if (r.harmonicCount > 0 && r.harmonicBins != null
                && r.harmonicBins.length > 0 && r.harmonicBins[0] > 0) {
            anchorFreq = r.harmonicHz[0];
            anchorDbFs = r.harmonics[0].deembeddedAmplitudeDbFs();
        } else {
            return;
        }
        if (!(anchorFreq > 0.0) || !Double.isFinite(anchorDbFs)) return;

        // Match the cal channel to whichever channel the FFT is
        // currently analysing - using .left() unconditionally would
        // mis-compensate the signal when the user picks the right
        // channel (their L/R cal curves are not identical).
        boolean wantLeft = prefs.getFftChannel() == Channel.L;
        double sumDbAtAnchor = 0.0;
        for (CorrectionStore.Entry e : entries) {
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
        // The cascade sum depends only on (entries, channel) - not on the
        // result or viewport - so it is cached across rebuilds: the per-point
        // log10 over every entry (~400 k at two 192 k-point cals) used to
        // rerun on every rebuilt frame.
        double[] sumDbCurve = calCascadeSumDb(entries, wantLeft, first);

        gc.setForeground(color(ColorRole.CAL_OVERLAY));
        setTraceLineAttributes(gc, (float) prefs.getFftLineWidth(), SWT.LINE_SOLID);
        // Column-batched walk, same scheme as drawSpectrum: cal points come
        // in ascending frequency, the conversion is monotonic in dB, so only
        // each pixel column's two dB extremes are converted and emitted.
        ColumnBucketPainter painter = new ColumnBucketPainter(plot);
        int    curCol   = Integer.MIN_VALUE;   // batching key
        double curX    = Double.NaN;          // what the trace is drawn through
        double curMinDb = Double.POSITIVE_INFINITY;
        double curMaxDb = Double.NEGATIVE_INFINITY;
        int    curPts   = 0;
        double fBound   = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < freqs.length; i++) {
            double f = freqs[i];
            if (!(f > 0.0)) continue;
            double dbFs = -sumDbCurve[i] + offset;
            if (f < freqMin) {
                painter.setLeftAnchor(freqToX(f, plot, freqMin, freqMax, logFreq),
                        magToY(convertFromDbFs(dbFs, unit, null), plot, magTop, magBot, unit));
                continue;
            }
            if (f > freqMax) {
                painter.setRightAnchor(freqToX(f, plot, freqMin, freqMax, logFreq),
                        magToY(convertFromDbFs(dbFs, unit, null), plot, magTop, magBot, unit));
                continue;
            }
            if (f >= fBound) {
                flushCalColumn(painter, prefs, plot, unit, magTop, magBot,
                        curX, curMinDb, curMaxDb, curPts);
                curX    = freqToX(f, plot, freqMin, freqMax, logFreq);
                curCol   = (int) Math.round(curX);
                curMinDb = dbFs;
                curMaxDb = dbFs;
                curPts   = 1;
                fBound = columnRightBoundaryFreq(curCol, plot, freqMin, freqMax, logFreq);
                if (fBound <= f) fBound = Math.nextUp(f);
            } else {
                if (dbFs < curMinDb) curMinDb = dbFs;
                if (dbFs > curMaxDb) curMaxDb = dbFs;
                curPts++;
            }
        }
        flushCalColumn(painter, prefs, plot, unit, magTop, magBot,
                curX, curMinDb, curMaxDb, curPts);
        painter.drawTo(gc);
    }

    /** Cached cascade-sum curve (dB per cal point of the FIRST entry's
     *  frequency grid) - see {@link #drawCalOverlay}.  Keyed on the entry
     *  list content (entries compare by identity) and the analysed
     *  channel. */
    private double[] calCascadeSumDb(List<CorrectionStore.Entry> entries,
                                     boolean wantLeft, FreqRespCalibration first) {
        if (calOverlaySumDb != null && wantLeft == calOverlayKeyLeft
                && entries.equals(calOverlayKeyEntries)) {
            return calOverlaySumDb;
        }
        double[] freqs = first.freqs;
        double[] sum = new double[freqs.length];
        for (int i = 0; i < freqs.length; i++) {
            double f = freqs[i];
            if (!(f > 0.0)) continue;       // skipped by the draw loop too
            double s = 0.0;
            for (CorrectionStore.Entry e : entries) {
                FreqRespCalibration cal = wantLeft
                        ? e.getCalibration().left()
                        : e.getCalibration().right();
                double m = (cal == first)
                        ? cal.magLin[i]
                        : FreqRespCalHelper.interpolate(cal, f)[0];
                s += (m > 0.0) ? 20.0 * Math.log10(m) : -300.0;
            }
            sum[i] = s;
        }
        calOverlaySumDb     = sum;
        calOverlayKeyEntries = entries;
        calOverlayKeyLeft    = wantLeft;
        return sum;
    }

    /** Samples the cascade of every loaded cal row on {@code r}'s grid, for the
     *  channel {@code r} was analysed on, and keeps it with the inputs it was
     *  built from.  The rows split into the two groups the cascade takes: one
     *  divides every bin, the other only the tone lobes.  Half a million
     *  log-domain interpolations at a 2^20-point FFT go into this, which is why
     *  the answer is held rather than derived per displayed frame.
     *
     * @param r the spectrum, its harmonic ladder placed */
    private void buildCalInverse(FftResult r) {
        List<FreqRespCalibration> withNoise = new ArrayList<>();
        List<FreqRespCalibration> toneOnly  = new ArrayList<>();
        for (CorrectionStore.Entry e : correctionStore.getEntries()) {
            FreqRespCalibration cal = r.channelLeft
                    ? e.getCalibration().left()
                    : e.getCalibration().right();
            if (e.isWithNoise()) {
                withNoise.add(cal);
            } else {
                toneOnly.add(cal);
            }
        }
        calInv = FreqRespCalHelper.cascadeInverse(withNoise, toneOnly,
                FreqRespCalHelper.toneFrequencies(r), r.fftSize, r.sampleRate);
        calInvToneKeys = !toneOnly.isEmpty();
        calInvToneBins = FreqRespCalHelper.toneBins(r);
    }

    /** Whether the held response still fits {@code r}: its tone bins, while a
     *  tone-only row is loaded - that row's one value per tone stands at
     *  exactly those tones.  With only with-noise rows the response depends on
     *  no tone, so the tone bins are not compared and no frame costs their
     *  recompute.
     *
     * @param r the spectrum, its harmonic ladder placed
     * @return {@code true} when the held response is valid for {@code r} */
    private boolean calInverseFits(FftResult r) {
        if (calInv == null) return false;
        if (!calInvToneKeys) return true;
        return Arrays.equals(calInvToneBins, FreqRespCalHelper.toneBins(r));
    }


    /** Emits one accumulated cal-overlay column - min/max dB extremes
     *  converted to the display unit and Y (single-point columns add one
     *  point, matching the per-point path's count semantics). */
    private void flushCalColumn(ColumnBucketPainter painter, Preferences prefs,
                                Rectangle plot, MagnitudeUnit unit,
                                double magTop, double magBot,
                                double xAbs, double minDb, double maxDb, int pts) {
        if (pts <= 0) return;
        painter.add(xAbs, magToY(convertFromDbFs(maxDb, unit, null), plot, magTop, magBot, unit));
        if (pts > 1) {
            painter.add(xAbs, magToY(convertFromDbFs(minDb, unit, null), plot, magTop, magBot, unit));
        }
    }

    // =========================================================================
    // Header buttons
    // =========================================================================

    /** Shows the data-only buttons (distortion / reset / external + their spacer) when a
     *  result is present, hides them otherwise - re-laying out {@link #headerBar}.  Driven
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
        // The unit selector belongs to the table, so it shows on the same
        // condition.  It is not part of the row, so it is only hidden - the
        // reflow below re-lays the canvas and slides it to the bar's new edge.
        distortionUnitCombo.setVisible(extVisible);
        headerBar.reflow();   // re-size the bar (its width changed), then re-flow the row
    }

    // =========================================================================
    // THD overlay table
    // =========================================================================

    /**
     * Draws the THD info table -
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
     *    <li>Two Δf lines (one per tone) - only when "Get fundamental
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
        drawCentred(gc, String.format("F1: %.4f Hz   %.2f dBFS   %.2f dBV",
                imd.f1.frequencyHz(), imd.f1.deembeddedAmplitudeDbFs(),
                convertFromDbFs(dbFsOfMagnitude(imd.f1Mag), MagnitudeUnit.DBV, null)), centreX, y);
        y += lineH;
        drawCentred(gc, String.format("F2: %.4f Hz   %.2f dBFS   %.2f dBV",
                imd.f2.frequencyHz(), imd.f2.deembeddedAmplitudeDbFs(),
                convertFromDbFs(dbFsOfMagnitude(imd.f2Mag), MagnitudeUnit.DBV, null)), centreX, y);
        y += lineH;

        // ── Span line (re-uses the THD format). ──
        drawCentred(gc, formatSpan(lastResult), centreX, y);
        y += lineH;

        // ── Δf1 / Δf2 (per-tone clock drift).  Only meaningful when
        // the generator is running AND fund-from-gen is on - same gate
        // the THD table uses for its ΔF row.
        boolean genActive = isGeneratorActive();
        if (genActive && prefs.isFftFundFromGenerator()) {
            int sr = lastResult.sampleRate;
            // Against what is EMITTED, not what these preferences would compute:
            // a bench a network away snapped on its own lane's rate and holds
            // the frequency-lock trims, and a Δf measured against the local
            // guess reports that difference as a drift of the hardware's clock.
            double[] emitted = MessageBus.instance()
                    .request(Events.GENERATOR_EMITTED_HZ, sr);
            if (emitted != null) {
                // While a tone's frequency lock tracks, the frequency it steers
                // on: its readings averaged over two FFT lengths of samples.
                double f1Hz = Double.isFinite(lastResult.fllAveragedHz)
                        ? lastResult.fllAveragedHz : imd.f1.frequencyHz();
                double f2Hz = Double.isFinite(lastResult.fllAveraged2Hz)
                        ? lastResult.fllAveraged2Hz : imd.f2.frequencyHz();
                drawCentred(gc, formatDeltaF("Δf1", emitted[0], f1Hz, sr), centreX, y);
                y += lineH;
                drawCentred(gc, formatDeltaF("Δf2", emitted[1], f2Hz, sr), centreX, y);
                y += lineH;
            }
        }
        gc.setFont(monoFont);
        y += 2;

        // Level unit + reference, shared by the metric AND product rows.  The
        // products and the tone magnitudes are full-scale relative, the scale
        // the unit selector works from.  The relative unit measures against
        // |F1| + |F2|, the divisor ImdAnalyzer uses for every product percentage.
        double dbvOff    = paintDbvOffsetDb;
        MagnitudeUnit tableUnit = prefs.getFftDistortionUnit();
        String unitLabel = distortionUnitLabel(tableUnit);
        double refDbFs   = dbFsOfMagnitude(imd.f1Mag + imd.f2Mag);

        // Metric and product rows as ONE measured column set - see
        // drawColumnRows.  The metric rows carry the same level+percent cell
        // as the dnL/dnH product rows.
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{
                "IMDpwr:", ratioLevelPctText(imd.imdPwrPct, tableUnit, refDbFs, dbvOff, unitLabel),
                "TD+N:",   ratioLevelPctText(imd.tdnPct, tableUnit, refDbFs, dbvOff, unitLabel)});
        rows.add(new String[]{
                "DFD2:", ratioLevelPctText(imd.dfd2Pct, tableUnit, refDbFs, dbvOff, unitLabel),
                "DFD3:", ratioLevelPctText(imd.dfd3Pct, tableUnit, refDbFs, dbvOff, unitLabel)});
        rows.add(null);   // small gap between the metric and product blocks
        for (int k = 2; k <= ImdResult.MAX_ORDER; k++) {
            rows.add(new String[]{
                    String.format("d%dL:", k),
                    imdRowText(levelInDistortionUnit(imd.dnL[k].deembeddedAmplitudeDbFs(),
                            refDbFs, tableUnit, dbvOff), imd.dnLPct[k], unitLabel),
                    String.format("d%dH:", k),
                    imdRowText(levelInDistortionUnit(imd.dnH[k].deembeddedAmplitudeDbFs(),
                            refDbFs, tableUnit, dbvOff), imd.dnHPct[k], unitLabel)});
        }
        drawColumnRows(gc, rows, xLeft, y, lineH);
    }

    /** Percent-only cell - "---" when the figure is {@code NaN} (a product
     *  outside the measurable range, or a ratio without a level). */
    private String imdPctText(double pct) {
        return Double.isFinite(pct) ? String.format("%.8f %%", pct) : "---";
    }

    /** IMD-table dnL/dnH cell (level in the selected unit + percent) - "---" for a
     *  product whose frequency lies outside the measurable range at this sample rate. */
    private String imdRowText(double level, double pct, String unitLabel) {
        return Double.isFinite(level)
                ? String.format("%8.2f %s  %.8f %%", level, unitLabel, pct)
                : "     ---";
    }

    /** Formats one Δf line for the IMD table, mirroring the THD
     *  path's "ΔF: %+.6f Hz (%+.2f ppm)  Δosc: %+.2f Hz @ 22.5792 MHz"
     *  shape so the user reads the same numbers in both modes. */
    private String formatDeltaF(String label, double expectedHz, double measuredHz, int sampleRate) {
        double delta = measuredHz - expectedHz;
        double ppm   = (expectedHz > 0) ? 1e6 * delta / expectedHz : Double.NaN;
        double osc;
        String oscName;
        if (sampleRate > 0 && sampleRate % 11025 == 0) {
            osc = 22.5792e6; oscName = "22.5792 MHz";
        } else if (sampleRate > 0 && sampleRate % 8000 == 0) {
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

        // Table width = harmonic-row layout span (xLeft -> xLeft + 64·charW).
        // Centre line drawn at xLeft + 32·charW.
        int tableW = 64 * charW;
        int centreX = xLeft + tableW / 2;

        // ── Centred header (bold) ─────────────────────────────────────
        // The row label names what the sum in this result actually holds.
        int thdMaxH = r.thdHarmonics > 0 ? r.thdHarmonics + 1 : prefs.getFftThdMaxHarmonic();
        gc.setFont(monoBoldFont);
        // dBV column: the manual fundamental (fundamentalTrueDbFs) when set,
        // else the measured fundamental - both lifted to dBV by the same global
        // ADC offset every other bin uses.  The dBFS column stays the measured
        // level.
        double dbvOffsetDb = paintDbvOffsetDb;
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
        // for the 48k family).
        // Only shown when "Get fundamental from generator" is enabled
        // - without that anchor the row would be meaningless.
        if (includeClockRow) {
            // Suppress the row when the generator isn't running - the
            // "expected" frequency would otherwise be a stale value
            // and the ΔF / Δosc readout would be meaningless.
            boolean genActive = isGeneratorActive();
            if (genActive && prefs.isFftFundFromGenerator()) {
                // Compare against what the generator is EMITTING - the
                // engine that owns it answers post-snap (a remote server
                // post-trim as well; the local lane states its snapped
                // nominal, the trim being the analyser's own).
                // The field holds the raw user value (e.g. 1000 Hz)
                // while the tone sits on the nearest bin (e.g.
                // 999.987 Hz), and a bench a network away snapped it
                // on its own lane's rate: either residual read here
                // would be reported as a drift of the hardware's
                // clock, which is exactly what this row must not do.
                double[] emitted = MessageBus.instance()
                        .request(Events.GENERATOR_EMITTED_HZ, r.sampleRate);
                double expected = emitted == null ? 0 : emitted[0];
                // While the frequency lock tracks, the frequency it steers on:
                // its readings averaged over two FFT lengths of samples.
                double measuredHz = Double.isFinite(r.fllAveragedHz)
                        ? r.fllAveragedHz : r.fundamentalHzRefined;
                if (expected > 0 && Double.isFinite(measuredHz)) {
                    double delta = measuredHz - expected;
                    double ppm   = 1e6 * delta / expected;
                    double osc;
                    String oscName;
                    if (r.sampleRate > 0 && r.sampleRate % 11025 == 0) {
                        osc = 22.5792e6; oscName = "22.5792 MHz";
                    } else if (r.sampleRate > 0 && r.sampleRate % 8000 == 0) {
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

        // Unit of the absolute-level cells, and the reference the relative unit
        // measures against: the fundamental this table already reports in its header
        // (manual override when set), which is the very level the analyzer divides by
        // for the harmonic percentages beside them.
        MagnitudeUnit tableUnit = prefs.getFftDistortionUnit();
        String unitLabel = distortionUnitLabel(tableUnit);
        double refDbFs   = fundDbV - dbvOffsetDb;

        // Metric and harmonic rows as ONE measured column set - see
        // drawColumnRows.  The identical cell format keeps the level and
        // percent sub-columns aligned inside the value columns.
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{
                "N+D:",  fmtDb(r.awThdNDb)  + " A",
                String.format("THD H2..%d:", thdMaxH),
                ratioLevelPctText(r.thdPct, tableUnit, refDbFs, dbvOffsetDb, unitLabel)});
        rows.add(new String[]{
                "N:",    fmtUnit(levelInDistortionUnit(noiseDbFs(r), refDbFs, tableUnit, dbvOffsetDb), unitLabel),
                "THD+N:", ratioLevelPctText(thdNPct(r), tableUnit, refDbFs, dbvOffsetDb, unitLabel)});
        rows.add(new String[]{
                "SNR:",  fmtDb(r.snrDb),
                "ENOB:", String.format("%.1f bits", (r.enobSinadDb - 1.76) / 6.02)});
        rows.add(null);   // small gap between the metric and harmonic blocks
        List<SpectralLine> harmonics = r.harmonicLines();
        int harmCount = harmonics.size();
        for (int i = 0; i < harmCount; i += 2) {
            String l = String.format("H%d:", i + 2);
            String lv = String.format("%8.2f %s  %.8f %%",
                    levelInDistortionUnit(harmonics.get(i).deembeddedAmplitudeDbFs(),
                            refDbFs, tableUnit, dbvOffsetDb),
                    unitLabel, r.harmonicPct[i]);
            String rkey = "", rval = "";
            if (i + 1 < harmCount) {
                rkey = String.format("H%d:", i + 3);
                rval = String.format("%8.2f %s  %.8f %%",
                        levelInDistortionUnit(harmonics.get(i + 1).deembeddedAmplitudeDbFs(),
                                refDbFs, tableUnit, dbvOffsetDb),
                        unitLabel, r.harmonicPct[i + 1]);
            }
            rows.add(new String[]{ l, lv, rkey, rval });
        }
        return drawColumnRows(gc, rows, xLeft, y, lineH);
    }

    /** Draws {@code rows} as four measured columns (key, value, key, value):
     *  each column's x offset comes from the widest cell it actually holds
     *  plus a fixed gap (one character after a key column, three between the
     *  table halves), so the columns sit flush regardless of how the mono
     *  "M" cell relates to real digit / percent glyph widths.  All rows are
     *  measured together, so every block of a table shares the same offsets.
     *  A {@code null} row advances y by a small block gap; an empty right
     *  key skips the right half of its row.  Returns the y below the last
     *  row. */
    private int drawColumnRows(GC gc, List<String[]> rows, int xLeft, int y, int lineH) {
        int[] w = new int[4];
        for (String[] row : rows) {
            if (row == null) continue;
            for (int c = 0; c < row.length; c++) {
                if (row[c] != null && !row[c].isEmpty()) {
                    w[c] = Math.max(w[c], gc.textExtent(row[c]).x);
                }
            }
        }
        int charW = gc.textExtent("M").x;
        int xValL = xLeft + w[0] + charW;
        int xKeyR = xValL + w[1] + 3 * charW;
        int xValR = xKeyR + w[2] + charW;
        for (String[] row : rows) {
            if (row == null) { y += 2; continue; }
            drawOutlinedText(gc, row[0], xLeft, y);
            drawOutlinedText(gc, row[1], xValL, y);
            if (row[2] != null && !row[2].isEmpty()) {
                drawOutlinedText(gc, row[2], xKeyR, y);
                drawOutlinedText(gc, row[3], xValR, y);
            }
            y += lineH;
        }
        return y;
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
     *  (mono / mono-bold) - callers don't have to pre-measure. */
    private void drawCentred(GC gc, String text, int centreX, int y) {
        Point ext = gc.textExtent(text);
        drawOutlinedText(gc, text, centreX - ext.x / 2, y);
    }

    /** Fixed-unit cell for the ratio rows (N+D, SNR): both are dB ratios against
     *  the fundamental, not levels, so the level-unit selector does not apply
     *  to them. */
    private String fmtDb(double v) {
        return Double.isFinite(v) ? String.format("%8.2f dB", v) : "-";
    }

    /** Level cell in the table's selected unit (the N row).  Same 8-wide
     *  level field as every level+percent cell, so the decimal points align
     *  down the whole value column. */
    private String fmtUnit(double v, String unitLabel) {
        return Double.isFinite(v) ? String.format("%8.2f %s", v, unitLabel) : "-";
    }

    /** The integrated noise floor in dBFS; the row converts it to the table's
     *  selected unit like every harmonic / product level. */
    private double noiseDbFs(FftResult r) {
        if (r.noisePower <= 0) return Double.NaN;
        return 10 * Math.log10(r.noisePower);
    }

    /** A percentage ratio as dB ({@code 20·log10(pct/100)}); NaN when the
     *  percentage carries no level (zero / negative / NaN). */
    private double ratioDb(double pct) {
        return pct > 0 ? 20.0 * Math.log10(pct / 100.0) : Double.NaN;
    }

    /** Summary-ratio cell (THD H2..n, THD+N, IMDpwr, TD+N, DFD2, DFD3): the
     *  ratio as a level in the selected unit plus the percentage - the same two
     *  columns the harmonic / product rows carry.  The level anchors the ratio
     *  at {@code refDbFs}, the very reference the percentage divides by, so the
     *  dB unit reads {@code 20·log10(pct/100)} exactly.  Falls back to the bare
     *  percentage cell when the level is not representable. */
    private String ratioLevelPctText(double pct, MagnitudeUnit unit,
                                     double refDbFs, double dbvOff, String unitLabel) {
        double level = levelInDistortionUnit(refDbFs + ratioDb(pct), refDbFs, unit, dbvOff);
        return Double.isFinite(level)
                ? String.format("%8.2f %s  %.8f %%", level, unitLabel, pct)
                : imdPctText(pct);
    }

    /** An absolute level, stored in dBFS, rendered in the table's selected unit:
     *  dBV adds the analysed channel's ADC offset, dBFS is the measured value
     *  itself, and dB subtracts {@code refDbFs} - the SAME reference the row's
     *  percentage divides by, so the two columns always agree
     *  ({@code dB == 20·log10(pct/100)}). */
    private double levelInDistortionUnit(double dbFs, double refDbFs,
                                         MagnitudeUnit unit, double dbvOff) {
        switch (unit) {
            case DBFS: return dbFs;
            case DBR:  return dbFs - refDbFs;
            default:   return dbFs + dbvOff;
        }
    }

    /** Label for a distortion-table unit - the plain "dB" for the relative one. */
    private String distortionUnitLabel(MagnitudeUnit unit) {
        switch (unit) {
            case DBFS: return DISTORTION_UNIT_LABELS[1];
            case DBR:  return DISTORTION_UNIT_LABELS[2];
            default:   return DISTORTION_UNIT_LABELS[0];
        }
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
        // Magnitude at the cursor's height on the vertical axis (where the
        // pointer is), distinct from the bin level |m| below.
        sb.append('\n').append("y = ").append(formatMagnitudeWithUnit(
                yToMag(crossY, plot, magTop, magBot, unit), unit));
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
                double v = convertFromDbFs(dbFs, unit, r.binBwSqrt);
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
     *  by the FFT pane when "Max harmonic to calculate" changes - the
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
        // External window hosts the extracted distortion table - THD or IMD,
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
            // Table mode may have flipped (THD ↔ IMD) while open - retitle + refit.
            distortionWindow.setTitle(externalTitle());
            resizeExternalShellToContent();
        }
    }

    /** Title for the extracted table window - "IMD ..." in dual-tone mode,
     *  "THD ..." otherwise.  Every locale keeps the acronym literal, so a
     *  THD->IMD swap localises correctly without a separate message key. */
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
        // Size to content (harmonic-row width + table height; no button row - the reset /
        // distortion / external toggles stay in the main FFT view), then place it at the
        // parent shell's top-right.
        Point contentSz = computeExternalContentSize();
        w.setSize(contentSz.x, contentSz.y);
        Point ws = w.getSize();
        Rectangle pb = getShell().getBounds();
        w.setLocation(pb.x + pb.width - ws.x - 24, pb.y + 96);
        w.open();
    }

    /** Computes the natural client size of the extracted THD table -
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
                // (MAX_ORDER−1) dnL/dnH rows.  Width mirrors drawColumnRows'
                // measured layout: widest keys + two worst-case value cells +
                // the fixed gaps (1 char after each key, 3 between halves).
                int charW = gc.textExtent("M").x;
                String unitLabel = distortionUnitLabel(prefs.getFftDistortionUnit());
                String worstVal = String.format("%8.2f %s  %.8f %%", -9999.99,
                        unitLabel, 99.99999999);
                int contentW = EXT_LEFT_PAD
                        + gc.textExtent("IMDpwr:").x + charW
                        + gc.textExtent(worstVal).x + 3 * charW
                        + gc.textExtent("TD+N:").x + charW
                        + gc.textExtent(worstVal).x + 16;
                boolean clk = isGeneratorActive()
                        && prefs.isFftFundFromGenerator();
                int rows = 2 + 1 + (clk ? 2 : 0) + 2 + (ImdResult.MAX_ORDER - 1);
                int contentH = EXT_LEFT_PAD + rows * lineH + 8;
                return new Point(contentW, contentH);
            }
            // Width mirrors drawColumnRows' measured layout: widest keys +
            // two worst-case value cells + the fixed gaps (1 char after each
            // key, 3 between the halves).
            int charW = gc.textExtent("M").x;
            String unitLabel = distortionUnitLabel(prefs.getFftDistortionUnit());
            String worstVal = String.format("%8.2f %s  %.8f %%", -9999.99,
                    unitLabel, 99.99999999);
            String keyRWorst = String.format("THD H2..%d:", (int) prefs.getFftThdMaxHarmonic());
            int contentW = EXT_LEFT_PAD
                    + gc.textExtent("H10:").x + charW
                    + gc.textExtent(worstVal).x + 3 * charW
                    + gc.textExtent(keyRWorst).x + charW
                    + gc.textExtent(worstVal).x + 16;
            // Rows (no top button row in the external window):
            //   header + span + (clock?) + gap + 3 metric rows + gap + harm pairs
            // Pref value N means "compute up to HN" -> N − 1 harmonics
            // (H2..HN) -> ceil((N − 1) / 2) row pairs.
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

    /** {@link ToolWindow.ContentPainter} for the extracted distortion window - draws the THD
     *  or IMD table straight into the window's GC, inset from its top-left so the keys don't
     *  touch the border ({@code top} is 0 here: no button row, those toggles stay in the
     *  main FFT view). */
    private void paintDistortion(GC gc, int top) {
        if (lastResult == null) return;
        gc.setAntialias(SWT.ON);
        gc.setTextAntialias(SWT.ON);
        ensureFonts();
        int y = top + EXT_LEFT_PAD;
        ImdResult imd = lastResult.imd;
        if (tableModeIsImd && imd != null) {
            drawImdTable(gc, imd, EXT_LEFT_PAD, y);
        } else {
            MagnitudeUnit unit = Preferences.instance().getFftMagUnit();
            drawDistortionTable(gc, lastResult, unit, EXT_LEFT_PAD, y, true);
        }
    }

    // =========================================================================
    // Mouse handlers
    // =========================================================================

    /**
     * Mouse-wheel handling - mirrors the oscilloscope view's UX:
     *   Ctrl + wheel         -> magnitude (vertical) zoom around mouse Y
     *   Ctrl + Shift + wheel -> frequency (horizontal) zoom around mouse X
     *   Shift + wheel        -> horizontal pan (frequency)
     *   plain wheel          -> vertical pan (magnitude)
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
     *  factor - otherwise an additive pan on a log axis visibly
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
        // Range is dBFS (linear in dB) for every unit - pan additively, clipping at the
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
     *  span - without this, panning past either edge looked like a
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
        Preferences prefs = Preferences.instance();
        int sr = prefs.current().getInputSampleRate();
        int fftLen = Math.max(8, prefs.getFftLength());
        return (double) sr / fftLen;
    }

    /** Nyquist upper bound on the visible freq range (Hz). */
    private double currentNyquist() {
        double sr = 0;
        if (lastResult != null) {
            sr = lastResult.sampleRate / 2.0;
        } else {
            sr = Preferences.instance().current().getInputSampleRate();
        }
        return sr;
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
    // AbstractFreqDomainView base, and every one of them is SUB-PIXEL: there is
    // no integer variant to pick the wrong one of.  A consumer that needs a whole
    // pixel - an SWT primitive with integer coordinates, a column bucket key -
    // rounds at its own call, where the loss is visible.

    // =========================================================================
    // Resource lazy initialisation
    // =========================================================================

    private void ensureFonts() {
        // Shared, centrally configured fonts - owned by Fonts, never
        // disposed here.
        Fonts fonts = Fonts.instance();
        Display d = getDisplay();
        if (monoFont == null)       monoFont       = fonts.normal(d);
        if (monoBoldFont == null)   monoBoldFont   = fonts.bold(d);
        if (chanButtonFont == null) chanButtonFont = fonts.channel(d);
    }

    /** Takes the current shared fonts: gives the L/R buttons and the banner
     *  the new ones, drops the cached static layer drawn with the old axis
     *  font, re-fits the extracted table window and repaints. */
    private void applyFonts() {
        monoFont       = null;
        monoBoldFont   = null;
        chanButtonFont = null;
        ensureFonts();
        leftBtn.setFont(chanButtonFont);
        rightBtn.setFont(chanButtonFont);
        leftBtn.redraw();
        rightBtn.redraw();
        banner.setFont(monoFont);
        showBannerText();
        disposeTraceBuffer();
        syncExternalShell();
        redraw();
    }

}
