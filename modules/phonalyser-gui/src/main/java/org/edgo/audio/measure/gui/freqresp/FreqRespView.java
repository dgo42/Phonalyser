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

package org.edgo.audio.measure.gui.freqresp;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.DoubleUnaryOperator;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.LineAttributes;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.edgo.audio.measure.dsp.FilterDesign;
import org.edgo.audio.measure.dsp.FreqRespCalHelper;
import org.edgo.audio.measure.dsp.FreqRespCalibration;
import org.edgo.audio.measure.dsp.StereoFreqRespCalibration;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.FilterResponse;
import org.edgo.audio.measure.enums.FilterType;
import org.edgo.audio.measure.enums.UnevenMode;
import org.edgo.audio.measure.fft.MathUtil;
import org.edgo.audio.measure.gui.bind.Bindings;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.AbstractFreqDomainView;
import org.edgo.audio.measure.gui.common.AbstractMeasurementView.ColorRole;
import org.edgo.audio.measure.gui.common.CorrectionStore;
import org.edgo.audio.measure.gui.common.Fonts;
import org.edgo.audio.measure.gui.common.Icon;
import org.edgo.audio.measure.gui.common.Lanczos;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.widgets.BlinkBanner;
import org.edgo.audio.measure.gui.widgets.ToolButton;
import org.edgo.audio.measure.gui.widgets.Toolbar;
import org.edgo.audio.measure.preferences.FreqRespFilterTypeParams;
import org.edgo.audio.measure.preferences.Preferences;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
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
    // Top / right margins are 0 by design - the L/R/phase/max buttons
    // overlay the top of the plot area, no axis ticks live on the right
    // edge unless the phase axis is visible.  When phase IS visible, the
    // right margin grows so the phase tick labels have somewhere to live.
    private static final int MARGIN_TOP      = 0;
    private static final int MARGIN_BOTTOM   = 28;
    private static final int MARGIN_RIGHT_NO_PHASE = 0;
    private static final int MARGIN_RIGHT_PHASE    = 52;

    /** Horizontal offset of the header-button row past the dB-axis labels.
     *  Keeps the L/R/phase/max tile clear of the numeric tick labels that
     *  the axis renderer paints into the same vertical band. */
    private static final int HEADER_BTN_INSET = 23;
    /** Gap kept between the header buttons' right edge and an overlay banner.
     *  The banners are transparent but still capture clicks across their full
     *  width, so they must never extend left over the L/R/phase/max buttons. */
    private static final int BANNER_BTN_GAP  = 8;

    // --- Colours -------------------------------------------------------------
    // All FreqResp palette colours live in the AbstractMeasurementView
    // palette - accessed via color(ColorRole.X).  syncColors() pushes
    // the prefs-driven entries (background + L/R trace + phase + RIAA)
    // through setColor() on every paint; the base short-circuits when
    // the RGB hasn't changed, so there's no allocation cost per redraw.

    // --- Fonts ---------------------------------------------------------------
    private Font axisFont;
    private Font readoutFont;
    private Font chanButtonFont;
    private Color phaseFillGray;

    // --- State ---------------------------------------------------------------
    /** The captured (or loaded) result for each channel before any
     *  calibration is divided out.  Kept so calibration changes can rebuild
     *  the displayed trace without losing the original measurement. */
    private FreqRespResult rawLeftResult;
    private FreqRespResult rawRightResult;

    /** Loaded {@code .frc} correction store, constructor-injected by
     *  {@link FreqRespPane} (IoC) and shared with the calibration tab.
     *  The wizard reads it through the getter. */
    @Getter
    private final CorrectionStore correctionStore;
    /** Display copies, with the currently-loaded calibration divided in (if
     *  any).  {@link #onCalibrationChanged()} keeps these in sync with the
     *  store every time the user loads / clears / wizard-applies a new
     *  calibration. */
    private FreqRespResult leftResult;
    private FreqRespResult rightResult;
    /** Source file path of the loaded measurement, or null for a live sweep. */
    @Getter
    private String         sourceFilePath;
    /** Self-blinking overlay banner widgets (compare-mode + loaded-file path). */
    private BlinkBanner    compareBanner;
    private BlinkBanner    sourceBanner;
    /** Bus-handler reference kept so we can unsubscribe symmetrically. */
    private Consumer<Void> calibrationChangedListener;
    /** Bus-handler reference kept so we can unsubscribe symmetrically. */
    private Consumer<Void> compareParamsChangedListener;

    private int    mouseX = -1;
    private int    mouseY = -1;
    private boolean mouseInPlot;

    /** Sample rate of the most recently received result; used to clip the
     *  crosshair readout at {@code nyquistFraction × sampleRate}.  Falls
     *  back to the active backend prefs' sample rate when no result has
     *  been received yet. */
    private int lastResultSampleRate;

    /** Bundle of every scalar + array the compare mode renders from.
     *  Built once per (source, reverse, iec, smoothing-window) tuple by
     *  {@link #getCompareDiff} and read by:
     *  <ul>
     *    <li>{@link #drawCompareTrace} - plots
     *        {@code smoothed[i] − anchorDb} per visible freq.</li>
     *    <li>{@link #recomputeCompareAnchor} - copies the scalar
     *        fields into the public-display variables.</li>
     *    <li>The compare-mode crosshair Δ readout - interpolates
     *        {@code smoothed[]} at the cursor.</li>
     *  </ul>
     *  All three consumers therefore agree by construction - one
     *  smoothing pass, one median, one min/max. */
    private static final class CompareDiff {
        /** Already-anchored, W-point moving-averaged
         *  {@code (measDb − refDb − median20to25k)} aligned with the
         *  source result's freq grid.  The median over 20 Hz - 25 kHz
         *  has been subtracted from every value so the curve's central
         *  tendency sits at 0 dB; consumers read these values DIRECTLY
         *  (no further subtraction).  {@code NaN} for any point where
         *  the raw magnitude was non-positive / non-finite. */
        final double[] smoothed;
        /** Min / max of {@link #smoothed} within 20 Hz - 25 kHz, after
         *  the median has been subtracted.  When the median is well
         *  estimated {@code minDb ≈ −maxDb}.  {@code NaN} when no
         *  finite point falls in the band. */
        final double   minDb;
        final double   maxDb;
        CompareDiff(double[] smoothed, double minDb, double maxDb) {
            this.smoothed = smoothed;
            this.minDb    = minDb;
            this.maxDb    = maxDb;
        }
    }

    /** Min / max of the smoothed diff curve over 20 Hz-25 kHz, mirroring
     *  {@link CompareDiff#minDb} / {@link CompareDiff#maxDb}.  Shown in
     *  the top-left measurement table.  {@code NaN} until the first
     *  {@link #recomputeCompareAnchor} runs. */
    private double compareSmoothedMin = Double.NaN;
    private double compareSmoothedMax = Double.NaN;

    /** Cache slot for {@link CompareDiff}.  Invalidated implicitly when
     *  the source / flag / window tuple changes - replacing the result
     *  via setLeftResult / setRightResult / onCalibrationChanged hands
     *  us a fresh object reference, and changing reverse / iec / window
     *  in the prefs ticks one of the other key components. */
    private CompareDiff    compareDiffCache;
    private FreqRespResult compareDiffCacheSrc;
    private boolean        compareDiffCacheReverse;
    private boolean        compareDiffCacheIec;
    private int            compareDiffCacheWindow;
    /** Reference-source identity baked into the compare cache: {@code true}
     *  while the RIAA curve is the active reference, {@code false} while the
     *  ideal-filter curve is.  Extends the cache key so a source swap (RIAA ↔
     *  filter, both anchored at 1 kHz) rebuilds the diff. */
    private boolean        compareDiffCacheFilterSrc;
    /** Filter-param tuple baked into the compare cache - a rebuilt
     *  {@link FilterDesign} changes reference; comparing its identity is
     *  enough since {@link #filterDesign} is only rebuilt when a param moves. */
    private FilterDesign   compareDiffCacheFilter;

    /** Cached ideal-filter magnitude curve, rebuilt by
     *  {@link #refreshFilterDesign()} whenever any filter pref changes.
     *  {@code null} when the current filter params are invalid - the
     *  reference is then unavailable and the overlay / compare is skipped. */
    private FilterDesign   filterDesign;
    /** Filter-param snapshot the current {@link #filterDesign} was built from;
     *  a differing snapshot triggers a rebuild.  {@code null} forces the
     *  first build. */
    private FilterParams   filterParams;

    /** Cached filter-overlay anchor (dB) - the level the ideal-filter curve is
     *  aligned to on the measured signal (uniform corner-point anchoring,
     *  so the curve passes through the measured trace at the corner frequency).
     *  {@code NaN} forces a
     *  recompute; invalidated by {@link #invalidateFilterAnchor()} whenever the
     *  measurement or the active channel changes.  The filter-param axis is
     *  self-validating: the anchor is recomputed when {@link #filterDesign}'s
     *  identity differs from {@link #filterAnchorCacheDesign} (a param edit
     *  rebuilds the design), so a param change routed only through a redraw
     *  re-anchors with no invalidation call. */
    private double         filterAnchorCache = Double.NaN;
    /** The {@link #filterDesign} identity {@link #filterAnchorCache} was computed
     *  against; a differing (rebuilt) design forces a re-anchor. */
    private FilterDesign   filterAnchorCacheDesign;

    /** Unevenness readout state - the boundary frequencies + the dB figure
     *  computed by {@link #recomputeUnevenness()} from the active channel's
     *  smoothed, Nyquist-capped dB, for the in-canvas table.
     *  {@code NaN} until the first successful compute (no
     *  result, or no usable point).  The dB figure's meaning is
     *  mode-dependent: Mode A stores the entered unevenness UNIPOLAR
     *  (no ±); Mode B stores the HALF-span {@code (max−min)/2} rendered with ±. */
    private double unevenLoHz   = Double.NaN;
    private double unevenHiHz   = Double.NaN;
    private double unevenPlusDb  = Double.NaN;
    /** Mode-A threshold level (dB): {@code peak − unevenDb} for a passband
     *  (LP/HP/BP) curve, {@code min + unevenDb} for a NOTCH curve.  The green
     *  dotted horizontal annotation line sits here; {@code NaN} in Mode B or
     *  when no Mode-A result exists. */
    private double unevenThresholdDb = Double.NaN;
    /** The analysis' reference extremum level (dB).  LEVEL: the peak for the
     *  normal walk, the interior minimum for the notch walk.  RANGE: the
     *  highest point in the range, or the lowest when the Notch checkbox is
     *  on.  A second green dotted horizontal annotation line sits here;
     *  {@code NaN} in OFF or when no analysis result exists. */
    private double unevenExtremumDb = Double.NaN;

    /** When {@code true} this is a detached instance (the Tune-notch wizard's
     *  embedded chart): it reads/writes only its injected {@link #prefs} and
     *  never persists to disk nor publishes {@link Events#FREQRESP_RANGE_CHANGED},
     *  so its channel select / pan / zoom / auto-fit can't disturb the shared
     *  main-pane view. */
    private boolean isolated = false;
    /** Preference source for ALL of this view's state - range, channel
     *  visibility, colours, line width.  The main pane passes the global
     *  {@link Preferences#instance()}; an isolated instance gets a detached
     *  {@link Preferences#copyForDialog()} copy so its edits stay local. */
    private Preferences prefs;

    // --- Header buttons (migrated from canvas-draw to widgets) ---------------
    private Toolbar    headerBar;
    private ToolButton leftBtn;
    private ToolButton rightBtn;
    private ToolButton phaseBtn;
    private ToolButton autoSetupBtn;
    private ToolButton maxBtn;

    // Static-layer paint cache (traceBuffer Image + fingerprint) now
    // lives in AbstractFreqDomainView.  Call paintCachedStatic(...) from
    // onPaint; the base owns disposal via disposeTraceBuffer().

    /** Connected view for the main FreqResp pane: bound to the global
     *  {@link Preferences#instance()}, persisting + publishing range changes
     *  like any other pane. */
    public FreqRespView(Composite parent, CorrectionStore correctionStore) {
        this(parent, correctionStore, false, Preferences.instance());
    }

    /** Full constructor.  {@code isolated == true} with a detached {@code prefs}
     *  (a {@link Preferences#copyForDialog()} copy) makes this a self-contained
     *  embedded chart - the Tune-notch wizard's use: it drives only its own copy,
     *  never saves to disk, and never publishes {@link Events#FREQRESP_RANGE_CHANGED},
     *  so nothing it does touches the shared main-pane view. */
    public FreqRespView(Composite parent, CorrectionStore correctionStore, boolean isolated, Preferences prefs) {
        // Push prefs-driven entries (background, L/R trace, phase, RIAA)
        // through the super override map so the base allocates each
        // colour exactly once.  Common entries (grid, axis, text,
        // crosshair, overlay_bg, button_frame, blink lit/dim, L/R btn
        // chan, compare_trace, button_active) use the AbstractMeasurementView
        // light-theme defaults.
        super(parent, SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED, Map.of(
                ColorRole.BACKGROUND,  Preferences.instance().getFreqRespBackgroundColor(),
                ColorRole.LEFT_TRACE,  Preferences.instance().getFreqRespSignalColor(),
                ColorRole.RIGHT_TRACE, Preferences.instance().getFreqRespSignalColor(),
                ColorRole.PHASE_TRACE, Preferences.instance().getFreqRespPhaseColor(),
                ColorRole.RIAA_TRACE,  Preferences.instance().getFreqRespReferenceColor(),
                // L/R channel buttons share the scope's channel colours (cyan/yellow).
                ColorRole.LEFT_BTN_CHAN,  Preferences.instance().getOscLeftChannelColor(),
                ColorRole.RIGHT_BTN_CHAN, Preferences.instance().getOscRightChannelColor()));
        this.correctionStore = correctionStore;
        this.isolated = isolated;
        this.prefs = prefs;

        axisFont    = Fonts.instance().normal(getDisplay());
        readoutFont = Fonts.instance().normal(getDisplay());

        // Self-blinking overlay banners.  Font + colours are configured ONCE
        // here (the palette already holds the prefs-driven BACKGROUND from the
        // super() map); afterwards each show just sets the text + visibility.
        // A later background recolour disposes the old palette Color, but
        // BlinkBanner guards against that - it simply drops the halo (the text
        // still draws) rather than crashing.
        compareBanner = new BlinkBanner(this);
        configureBanner(compareBanner);
        sourceBanner  = new BlinkBanner(this);
        configureBanner(sourceBanner);

        // Header buttons (below): L and R are radio (exactly one channel shown at a
        // time), phase is an independent toggle, max is a push that resets the view.
        // L / R behave as dependent (radio) toggles: clicking either
        // unconditionally activates that channel and deactivates the
        // other.  No early-return when the clicked side is already on -
        // a single normalize pass also corrects a stale "both true" /
        // "both false" state that a hand-edited YAML or older session
        // might have carried in.
        // Normalise on construction: if the persisted state is both true or both false,
        // snap to L-only so the radio invariant holds from the very first paint.
        if (prefs.isFreqRespLeftVisible() == prefs.isFreqRespRightVisible()) {
            prefs.setFreqRespLeftVisible(true);
            prefs.setFreqRespRightVisible(false);
            if (!isolated) prefs.save();
        }
        // Header buttons - ToolButton widgets in a Toolbar.  L/R is a radio (channel
        // select); phase keeps its own coloured icon; auto-setup/max are icon pushes.
        chanButtonFont = Fonts.instance().channel(getDisplay());   // same as FFT/scope
        phaseFillGray  = new Color(getDisplay(), 0xE6, 0xE6, 0xE6);          // 90% grey so the icon reads
        headerBar = new Toolbar(this, BTN_W, BTN_H);
        leftBtn  = headerBar.chanButton("L", color(ColorRole.TEXT), color(ColorRole.BUTTON_FRAME),
                color(ColorRole.LEFT_BTN_CHAN),  chanButtonFont, I18n.t("freqResp.button.left.tooltip"),  prefs.isFreqRespLeftVisible(),  "channel");
        rightBtn = headerBar.chanButton("R", color(ColorRole.TEXT), color(ColorRole.BUTTON_FRAME),
                color(ColorRole.RIGHT_BTN_CHAN), chanButtonFont, I18n.t("freqResp.button.right.tooltip"), prefs.isFreqRespRightVisible(), "channel");
        phaseBtn = headerBar.toggleButton(Icon.PHASE_SINE, Icon.PHASE_SINE,
                phaseFillGray,
                I18n.t("freqResp.button.phase.tooltip"), prefs.isFreqRespPhaseVisible());
        autoSetupBtn = headerBar.pushButton(Icon.ARROWS_TO_CIRCLE_DARK, Icon.ARROWS_TO_CIRCLE_LIT,
                color(ColorRole.TEXT), I18n.t("freqResp.button.autosetup.tooltip"));
        maxBtn = headerBar.pushButton(Icon.ARROWS_FROM_CIRCLE_DARK, Icon.ARROWS_FROM_CIRCLE_LIT,
                color(ColorRole.TEXT), I18n.t("freqResp.button.maximize.tooltip"));
        Point hbSize = headerBar.computeSize(SWT.DEFAULT, SWT.DEFAULT);
        headerBar.setBounds(MARGIN_LEFT + HEADER_BTN_INSET, BTN_TOP, hbSize.x, hbSize.y);
        headerBar.layout();
        leftBtn.addListener(SWT.Selection, e -> {
            if (leftBtn.isToggled()) {
                prefs.setFreqRespLeftVisible(true);
                prefs.setFreqRespRightVisible(false);
                if (!isolated) prefs.save();
                invalidateFilterAnchor();// active channel switched -> re-anchor the filter overlay
                recomputeUnevenness();   // active channel switched -> refresh the readout
                redraw();
            }
        });
        rightBtn.addListener(SWT.Selection, e -> {
            if (rightBtn.isToggled()) {
                prefs.setFreqRespRightVisible(true);
                prefs.setFreqRespLeftVisible(false);
                if (!isolated) prefs.save();
                invalidateFilterAnchor();// active channel switched -> re-anchor the filter overlay
                recomputeUnevenness();   // active channel switched -> refresh the readout
                redraw();
            }
        });
        // Recolour the L/R buttons immediately when the channel colours change.
        bindChannelButtonFills(leftBtn, rightBtn);
        phaseBtn.addListener(SWT.Selection, e -> {
            prefs.setFreqRespPhaseVisible(phaseBtn.isToggled()); 
            if (!isolated) prefs.save();
            repositionBanners();   // the right margin (phase axis) moved
            redraw();
        });
        autoSetupBtn.addListener(SWT.Selection, e -> autoSetupMagnitudeRange());
        maxBtn.addListener(SWT.Selection, e -> resetToDefaultView());

        addPaintListener(this::onPaint);
        // The banners are right-anchored, so re-anchor them when the canvas
        // resizes (they're event-driven, not repositioned per paint).
        addListener(SWT.Resize, e -> repositionBanners());
        addListener(SWT.MouseWheel, this::onMouseWheel);
        addListener(SWT.MouseMove,  this::onMouseMove);
        addListener(SWT.MouseExit,  e -> { mouseInPlot = false; redraw(); });
        installRectZoom(this, true);   // drag-select zoom + Ctrl+Z undo (base machinery)

        // Re-trace whenever the active calibration changes (load, clear, or
        // wizard Apply).  The view divides the raw result by the new
        // calibration to keep what's painted in sync with the store.
        MessageBus bus = MessageBus.instance();
        calibrationChangedListener = ignored -> onCalibrationChanged();
        bus.subscribe(Events.FREQRESP_CALIBRATION_CHANGED,
                calibrationChangedListener);
        // Compare-params changed (e.g. the smoothing-window pref): refresh
        // the anchor + min/max table from the new smoothed array, then
        // redraw.  Does NOT alter the view's zoom - see recomputeCompareAnchor.
        compareParamsChangedListener = ignored -> onCompareParamsChanged();
        bus.subscribe(Events.FREQRESP_COMPARE_PARAMS_CHANGED,
                compareParamsChangedListener);

        // RIAA overlay prefs (Show / Reverse / IEC) are bound to their tab
        // checkboxes in the pane; the view simply subscribes to repaint when
        // any of them changes - onPaint reads the live flags, so a redraw is
        // all that's needed.  Show additionally carries the one-shot compare
        // auto-zoom: when Show turns on while Compare is already armed and a
        // measurement exists, the compare trace becomes active for the first
        // time, so fit it once (mirrors the Compare-toggle auto-zoom).
        Bindings.onChange(this, prefs.freqRespShowRiaaProperty(), show -> {
            if (show && prefs.isFreqRespCompareMode() && hasAnyResult()) {
                autoSetupCompare(prefs);
            }
            updateCompareBanner();   // Show gates the compare banner
            redraw();                // RIAA overlay / compare trace changed
        });
        // Compare mode + Reverse / IEC drive the compare banner's visibility /
        // text AND the RIAA/compare trace; refresh the banner + repaint the
        // canvas when any of them changes.
        Bindings.onChange(this, prefs.freqRespCompareModeProperty(), v -> { updateCompareBanner(); redraw(); });
        Bindings.onChange(this, prefs.freqRespReverseRiaaProperty(), v -> { updateCompareBanner(); redraw(); });
        Bindings.onChange(this, prefs.freqRespIecAmendmentProperty(), v -> { updateCompareBanner(); redraw(); });

        // Ideal-filter reference - mirror the RIAA subscriptions.  Show carries
        // the same one-shot compare auto-zoom (fit once when the filter compare
        // trace first becomes active); Filter-compare toggles the compare trace.
        // The filter TYPE / RESPONSE selectors still own their own prefs, so the
        // view subscribes to them.  The per-type scalar params live in the
        // Preferences params map (the single source of truth) - the Filters tab
        // writes an edited entry and calls this view's redraw() directly, and
        // FilterParams.of() re-reads that map on the next paint, so every filter
        // cache self-validates without a per-scalar subscription here.
        Bindings.onChange(this, prefs.freqRespShowFilterProperty(), show -> {
            invalidateFilterReference();
            if (show && prefs.isFreqRespFilterCompare() && hasAnyResult()) {
                autoSetupCompare(prefs);
            }
            redraw();
        });
        Bindings.onChange(this, prefs.freqRespFilterCompareProperty(), v -> redraw());
        Bindings.onChange(this, prefs.freqRespFilterTypeProperty(),        v -> { invalidateFilterReference(); redraw(); });
        Bindings.onChange(this, prefs.freqRespFilterResponseProperty(),    v -> { invalidateFilterReference(); redraw(); });

        // Unevenness prefs - any change re-walks the current curve + repaints.
        Bindings.onChange(this, prefs.freqRespUnevenModeProperty(),    v -> { recomputeUnevenness(); redraw(); });
        Bindings.onChange(this, prefs.freqRespUnevenNotchProperty(),   v -> { recomputeUnevenness(); redraw(); });
        Bindings.onChange(this, prefs.freqRespUnevenDbProperty(),      v -> { recomputeUnevenness(); redraw(); });
        Bindings.onChange(this, prefs.freqRespUnevenStartHzProperty(), v -> { recomputeUnevenness(); redraw(); });
        Bindings.onChange(this, prefs.freqRespUnevenStopHzProperty(),  v -> { recomputeUnevenness(); redraw(); });

        addDisposeListener(e -> {
            if (calibrationChangedListener != null) {
                bus.unsubscribe(Events.FREQRESP_CALIBRATION_CHANGED,
                        calibrationChangedListener);
            }
            if (compareParamsChangedListener != null) {
                bus.unsubscribe(Events.FREQRESP_COMPARE_PARAMS_CHANGED,
                        compareParamsChangedListener);
            }
            disposePalette();
            // chanButtonFont / axisFont / readoutFont are shared instances
            // owned by Fonts - never disposed here.
            if (phaseFillGray  != null && !phaseFillGray.isDisposed())  phaseFillGray.dispose();
            disposeTraceBuffer();
        });
    }


    /** (Re-)allocates the user-configurable colours from
     *  {@link Preferences} when the packed-RGB value of any slot has
     *  changed.  Cheap on repeat calls (one int compare per slot when
     *  nothing moved).  Called from the constructor (first paint) and
     *  from {@link #onPaint} so an OK from the Preferences dialog
     *  takes effect on the next redraw without an explicit subscription.
     *
     *  <p>The L and R trace fields both point at {@link #color(ColorRole.LEFT_TRACE)};
     *  with the radio-style L/R toggle, only one channel is ever shown
     *  at a time so a single user-chosen colour covers both. */
    private void syncColors() {
        int sig = prefs.getFreqRespSignalColor();
        setColor(ColorRole.BACKGROUND,  prefs.getFreqRespBackgroundColor());
        setColor(ColorRole.LEFT_TRACE,  sig);
        setColor(ColorRole.RIGHT_TRACE, sig);
        setColor(ColorRole.PHASE_TRACE, prefs.getFreqRespPhaseColor());
        setColor(ColorRole.RIAA_TRACE,  prefs.getFreqRespReferenceColor());
    }

    // -------------------------------------------------------------------------
    // Public API - host pane and analyzer worker push results in here
    // -------------------------------------------------------------------------

    /** Replaces the left-channel result and triggers a repaint.  The argument
     *  is the raw measurement; the displayed copy is derived by dividing it
     *  by whichever calibration is currently active in
     *  {@link CorrectionStore} (when {@code applyCalibration} is on). */
    public void setLeftResult(FreqRespResult result) {
        this.rawLeftResult = result;
        this.leftResult    = applyCurrentCalibration(result);
        if (result != null) lastResultSampleRate = result.getSampleRate();
        updateCompareBanner();   // hasAnyResult changed -> may show/hide compare
        invalidateFilterAnchor();// new curve -> re-anchor the filter overlay
        recomputeUnevenness();   // new curve -> refresh the flatness readout
        redraw();                // new trace
    }

    /** Replaces the right-channel result and triggers a repaint.  Same
     *  calibration semantics as {@link #setLeftResult(FreqRespResult)}. */
    public void setRightResult(FreqRespResult result) {
        this.rawRightResult = result;
        this.rightResult    = applyCurrentCalibration(result);
        if (result != null) lastResultSampleRate = result.getSampleRate();
        updateCompareBanner();   // hasAnyResult changed -> may show/hide compare
        invalidateFilterAnchor();// new curve -> re-anchor the filter overlay
        recomputeUnevenness();   // new curve -> refresh the flatness readout
        redraw();                // new trace
    }

    /** Re-derives the displayed left/right results from their raw copies
     *  using the calibration currently active in the store.  Subscribed to
     *  {@code FREQRESP_CALIBRATION_CHANGED} so loading or clearing a
     *  calibration immediately retraces the existing measurement.
     *
     *  <p>When compare mode is on, also re-runs
     *  {@link #autoSetupCompare(Preferences)} so the median anchor and
     *  the min / max table refresh to match the new calibrated data -
     *  otherwise the trace would shift but the 0 dB centreline and
     *  the table would still reflect the old calibration. */
    /** Refreshes the compare-mode anchor + min/max table after the user
     *  changes a parameter that affects the smoothed-diff derivation
     *  (currently the smoothing-window pref).  No-op when compare mode
     *  or RIAA is off, or no result is loaded.  Always redraws so the
     *  newly-smoothed trace appears even when the anchor doesn't
     *  meaningfully move. */
    public void onCompareParamsChanged() {
        if (compareActive()) {
            recomputeCompareAnchor(prefs);
        }
        redraw();
    }

    public void onCalibrationChanged() {
        if (rawLeftResult  != null) this.leftResult  = applyCurrentCalibration(rawLeftResult);
        if (rawRightResult != null) this.rightResult = applyCurrentCalibration(rawRightResult);
        invalidateFilterAnchor();// calibrated curve changed -> re-anchor the filter overlay
        recomputeUnevenness();   // calibrated curve changed -> refresh the readout
        // Refresh the anchor + min/max table so they track the new
        // calibration / colour / smoothing state, but DO NOT touch the
        // freq / magnitude window - only autoSetupCompare is allowed
        // to re-zoom, and that runs solely from the explicit user
        // actions (auto-setup button, compare-mode toggle on, Show RIAA
        // toggled on while compare is already on).  Saving Preferences
        // must never re-zoom the view.
        if (compareActive()) {
            recomputeCompareAnchor(prefs);
        }
        redraw();
    }

    private FreqRespResult applyCurrentCalibration(FreqRespResult raw) {
        if (raw == null) return null;
        // Loaded files already carry the calibration division baked in
        // at save time - applying it again here would double-correct.
        if (raw.isCalibrationApplied()) return raw;
        List<CorrectionStore.Entry> entries = correctionStore.getEntries();
        StereoFreqRespCalibration direct = correctionStore.getDirect();
        boolean wantCal   = prefs.isFreqRespApplyCalibration()
                            && (!entries.isEmpty() || direct != null);
        boolean wantNotch = prefs.isFreqRespNotchEnabled();
        if (!wantCal && !wantNotch) return raw;

        double[] freqs    = raw.getFreqs();
        double[] outMag   = raw.getMagLin().clone();
        double[] outPhase = raw.getPhaseRad().clone();
        boolean rChan = raw.getChannel() == Channel.R;

        if (wantCal) {
            // Chain every loaded calibration in order - linear-mag divide,
            // phase subtract - so the final displayed values reflect the
            // composition of all loaded files.
            for (CorrectionStore.Entry entry : entries) {
                divideByStereoCal(entry.getCalibration(), rChan, freqs, outMag, outPhase);
            }
            // Plus the wizard's transient page-1 calibration (when set) so
            // page-2 / save-stage displays subtract the loopback without
            // requiring a file in the entries list.
            if (direct != null) {
                divideByStereoCal(direct, rChan, freqs, outMag, outPhase);
            }
        }
        if (wantNotch) {
            // Half-width scales with the deconvolution's FFT length so the
            // notch always spans a fixed number of bin-widths regardless
            // of sweep duration / sample rate.  Formula per spec:
            //   halfWidth = 3 · fftSize / sampleRate
            // - i.e. for the typical 48 kHz / 1.1 s capture (M ≈ 131 072
            // bins, ~0.37 Hz/bin) the notch covers ±8 Hz around each
            // harmonic.
            int    fftSize     = deconvFftSize(raw);
            double halfWidthHz = 1.2 * (double) raw.getSampleRate() / fftSize;
            applyMainsNotches(freqs, outMag, outPhase,
                    prefs.getFreqRespNotchBaseHz(), raw.getSampleRate(), halfWidthHz);
        }
        return new FreqRespResult(raw.getChannel(), raw.getSampleRate(),
                freqs, outMag, outPhase, raw.getSweepParams(),
                raw.getSourceFilePath(), true);
    }

    /** Recovers the FFT length the deconvolution used for this measurement
     *  so the notch half-width can scale with it.  Mirrors the analyzer's
     *  capture-length math: lead-in + sweep + ½-second tail, rounded up
     *  to the next power of two.  Falls back to {@code nextPow2(2 · sr)}
     *  when sweep params are missing - e.g. on a result loaded from disk. */
    private int deconvFftSize(FreqRespResult raw) {
        int sr = raw.getSampleRate();
        FreqRespSweepParams p = raw.getSweepParams();
        if (p == null || p.getDurationSec() <= 0.0) {
            return MathUtil.nextPow2(Math.max(1, 2 * sr));
        }
        long total = (long) Math.round(p.getLeadInSec()   * sr)
                   + (long) Math.round(p.getDurationSec() * sr)
                   + sr / 2;
        if (total <= 0 || total > Integer.MAX_VALUE / 2) return 1 << 17;
        return MathUtil.nextPow2((int) total);
    }

    /** Spectral notch: walks the harmonics of {@code baseHz} (50 or 60 Hz)
     *  up to Nyquist and linearly interpolates the magnitude / phase
     *  arrays across each {@code ±halfWidthHz} band.  Harmonics that
     *  fall outside the freq array's range are skipped silently. */
    private void applyMainsNotches(double[] freqs, double[] outMag, double[] outPhase,
                                   int baseHz, int sampleRate, double halfWidthHz) {
        if (baseHz <= 0 || freqs == null || freqs.length < 2 || halfWidthHz <= 0.0) return;
        double nyq = sampleRate * 0.5;
        for (int h = baseHz; h < nyq; h += baseHz) {
            interpolateAcrossBand(freqs, outMag, outPhase,
                    h - halfWidthHz, h + halfWidthHz);
        }
    }

    /** Replaces every point in {@code freqs[]} whose frequency lies in
     *  {@code [fLo, fHi]} with a linear interpolation, in frequency, of
     *  the magnitude and phase from the two points immediately outside
     *  the band.  No-op when the band falls outside the array or no
     *  flanking neighbour exists. */
    private void interpolateAcrossBand(double[] freqs, double[] outMag, double[] outPhase,
                                       double fLo, double fHi) {
        // freqs is ascending - find last index strictly below fLo and
        // first index strictly above fHi.  Both must exist for the
        // interpolation to have anchors.
        int loIdx = -1;
        for (int i = 0; i < freqs.length; i++) {
            if (freqs[i] < fLo) loIdx = i;
            else break;
        }
        int hiIdx = -1;
        for (int i = freqs.length - 1; i >= 0; i--) {
            if (freqs[i] > fHi) hiIdx = i;
            else break;
        }
        if (loIdx < 0 || hiIdx < 0 || hiIdx <= loIdx + 1) return;
        double f0 = freqs[loIdx], f1 = freqs[hiIdx];
        if (f1 == f0) return;
        double m0 = outMag[loIdx],   m1 = outMag[hiIdx];
        double p0 = outPhase[loIdx], p1 = outPhase[hiIdx];
        for (int i = loIdx + 1; i < hiIdx; i++) {
            double t = (freqs[i] - f0) / (f1 - f0);
            outMag[i]   = m0 + t * (m1 - m0);
            outPhase[i] = p0 + t * (p1 - p0);
        }
    }

    /** Divides the {@code outMag}/{@code outPhase} buffers in place by
     *  the channel-appropriate side of {@code stereo}, using log-frequency
     *  interpolation onto the result's freq grid. */
    private void divideByStereoCal(StereoFreqRespCalibration stereo, boolean rChan,
                                   double[] freqs, double[] outMag, double[] outPhase) {
        FreqRespCalibration cal = rChan ? stereo.right() : stereo.left();
        if (cal == null) return;
        for (int i = 0; i < freqs.length; i++) {
            double[] c = FreqRespCalHelper.interpolate(cal, freqs[i]);
            double calMag = c[0];
            double calPhi = c[1];
            outMag[i]   = calMag > 0.0 ? outMag[i] / calMag : 0.0;
            outPhase[i] = outPhase[i] - calPhi;
        }
    }

    /** Sets a file-path overlay label (drawn at the top-left of the trace
     *  area, mirroring the scope view's loaded-file indicator).  Pass
     *  {@code null} to clear. */
    public void setSourceFilePath(String path) {
        this.sourceFilePath = path;
        showSourceBanner();   // shows for a non-null path, hides for null; repaints
    }

    /** Clears both result slots (raw and displayed). */
    public void clearResults() {
        this.rawLeftResult  = null;
        this.rawRightResult = null;
        this.leftResult     = null;
        this.rightResult    = null;
        updateCompareBanner();   // no result -> hide the compare banner
        invalidateFilterAnchor();// no curve -> drop the cached filter anchor
        recomputeUnevenness();   // no curve -> clear the flatness readout
        redraw();
    }

    /** True when at least one channel has a measurement loaded - used by
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

    /** Copies {@code src}'s displayed state into this view - for the offscreen
     *  screenshot clone.  The already-calibrated results are fed in: this view's
     *  correction store is empty, so {@link #applyCurrentCalibration} is identity
     *  and the clone paints exactly what {@code src} shows (compare / RIAA still
     *  apply at paint time from the shared preferences). */
    public void copySnapshotFrom(FreqRespView src) {
        if (src == null || src == this) return;
        setSourceFilePath(src.getSourceFilePath());
        setLeftResult(src.getLeftResultOrNull());
        setRightResult(src.getRightResultOrNull());
    }

    /** Snaps the view to the default frequency / magnitude window
     *  (1 Hz -> Nyquist horizontal, +20 -> −150 dB vertical) and persists
     *  to {@link Preferences}.  Called from the maximize header button. */
    public void resetToDefaultView() {
        prefs.setFreqRespFreqMinHz(FREQ_MIN_FLOOR_HZ);
        prefs.setFreqRespFreqMaxHz(nyquistHz());
        // Default +20 dB ceiling, but never below the corrected signal - a notch
        // un-notched well above 0 dB must still fit with headroom, not clip.
        prefs.setFreqRespMagTopDb(softMagTopDb(MAG_TOP_MAX_DB, FREQ_MIN_FLOOR_HZ, nyquistHz()));
        prefs.setFreqRespMagBotDb(MAG_DEFAULT_BOT_DB);
        if (!isolated) prefs.save();
        publishRangeChanged();
        redraw();
    }

    /** Auto-fits the view's frequency and magnitude windows to the
     *  visible result data.  Horizontal range goes to
     *  {@code FREQ_MIN_FLOOR_HZ -> nyquistHz()} (i.e. the full band the
     *  user has allowed via the Max-freq-%-Nyquist pref).  Vertical
     *  range is taken from the magnitude min/max across whichever
     *  channels are shown, padded by 10 % above and below.  No-op when
     *  no visible channel has any usable data in the band.
     *
     *  <p>When compare mode is active the call is dispatched to
     *  {@link #autoSetupCompare(Preferences)} instead, so the header
     *  auto-setup button always fits whichever curve the user is
     *  currently looking at. */
    public void autoSetupMagnitudeRange() {
        if (compareActive()) {
            autoSetupCompare(prefs);
            return;
        }
        double fHi = nyquistHz();
        double minDb = Double.POSITIVE_INFINITY;
        double maxDb = Double.NEGATIVE_INFINITY;
        if (prefs.isFreqRespLeftVisible()  && leftResult  != null) {
            double[] ext = magExtremaInBand(leftResult,  0.0, fHi);
            if (ext != null) { minDb = Math.min(minDb, ext[0]); maxDb = Math.max(maxDb, ext[1]); }
        }
        if (prefs.isFreqRespRightVisible() && rightResult != null) {
            double[] ext = magExtremaInBand(rightResult, 0.0, fHi);
            if (ext != null) { minDb = Math.min(minDb, ext[0]); maxDb = Math.max(maxDb, ext[1]); }
        }
        if (!Double.isFinite(minDb) || !Double.isFinite(maxDb) || maxDb <= minDb) {
            return;
        }
        double span = maxDb - minDb;
        double pad  = 0.10 * span;
        double newTop = maxDb + pad;
        double newBot = minDb - pad;
        // Minimum vertical window of 2 dB total - when the natural fit
        // is tighter than that (e.g. a near-flat loopback measurement),
        // centre the 2 dB window on the SIGNAL midpoint so the trace
        // sits vertically centred instead of getting stuck near one
        // edge of an off-centre default range.
        final double MIN_SPAN = 2.0;
        if (newTop - newBot < MIN_SPAN) {
            double mid = 0.5 * (maxDb + minDb);
            newTop = mid + 0.5 * MIN_SPAN;
            newBot = mid - 0.5 * MIN_SPAN;
        }
        newTop = Math.min(MAG_TOP_ZOOM_MAX_DB, newTop);
        newBot = Math.max(MAG_BOT_MIN_DB, newBot);
        prefs.setFreqRespFreqMinHz(FREQ_MIN_FLOOR_HZ);
        prefs.setFreqRespFreqMaxHz(fHi);
        // Bake the +20 dB headroom above the corrected peak into the STORED top - autosetup
        // is one of the two fit actions allowed to set it; scroll/zoom stay free above it.
        prefs.setFreqRespMagTopDb(softMagTopDb(newTop, FREQ_MIN_FLOOR_HZ, fHi));
        prefs.setFreqRespMagBotDb(newBot);
        if (!isolated) prefs.save();
        publishRangeChanged();
        redraw();
    }

    /** Returns {@code [minDb, maxDb]} of the magnitudes in {@code r}
     *  whose frequency lies in {@code [fLo, fHi]}.  Returns
     *  {@code null} when no usable point falls in the band. */
    private double[] magExtremaInBand(FreqRespResult r, double fLo, double fHi) {
        double[] freqs = r.getFreqs();
        double[] mag   = r.getMagLin();
        if (freqs == null || mag == null) return null;
        double minDb = Double.POSITIVE_INFINITY;
        double maxDb = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < freqs.length; i++) {
            double f = freqs[i];
            if (f < fLo || f > fHi) continue;
            double m = mag[i];
            if (m <= 0.0) continue;
            double db = 20.0 * Math.log10(m);
            if (db < minDb) minDb = db;
            if (db > maxDb) maxDb = db;
        }
        if (!Double.isFinite(minDb) || !Double.isFinite(maxDb)) return null;
        return new double[] { minDb, maxDb };
    }

    /** Returns {@code topPref}, raised as needed to keep ≥ {@link #MAG_HEADROOM_DB}
     *  of headroom above the highest DISPLAYED (correction-applied) trace point in
     *  the visible band {@code [fLo, fHi]}, so a corrected peak - e.g. a notch
     *  un-notched tens of dB above 0 dBFS - is never clipped at the ceiling.  No
     *  visible trace => {@code topPref} unchanged.  The Maximize button
     *  ({@link #resetToDefaultView}) and Auto-setup
     *  ({@link #autoSetupMagnitudeRange}) PERSIST the returned value via
     *  {@code setFreqRespMagTopDb}; only {@link #magCeilingDb()} uses it as a
     *  transient scrollbar bound. */
    private double softMagTopDb(double topPref, double fLo, double fHi) {
        // Compare mode draws the diff curve, not the raw traces - keep the headroom
        // above the compared-signal peak (compareSmoothedMax), not leftResult/right.
        if (compareActive()) {
            return Double.isFinite(compareSmoothedMax)
                    ? Math.max(topPref, compareSmoothedMax + MAG_HEADROOM_DB) : topPref;
        }
        double maxDb = Double.NEGATIVE_INFINITY;
        if (prefs.isFreqRespLeftVisible()  && leftResult  != null) {
            double[] ext = magExtremaInBand(leftResult,  fLo, fHi);
            if (ext != null) maxDb = Math.max(maxDb, ext[1]);
        }
        if (prefs.isFreqRespRightVisible() && rightResult != null) {
            double[] ext = magExtremaInBand(rightResult, fLo, fHi);
            if (ext != null) maxDb = Math.max(maxDb, ext[1]);
        }
        return Double.isFinite(maxDb) ? Math.max(topPref, maxDb + MAG_HEADROOM_DB) : topPref;
    }

    /** Magnitude scrollbar ceiling (dB): the +20 dB default raised, when a signal is
     *  present, to keep the corrected peak + 20 dB reachable - so the pane's vertical
     *  scrollbar can represent a correction-lifted signal above the default. */
    public double magCeilingDb() {
        return softMagTopDb(MAG_TOP_MAX_DB, FREQ_MIN_FLOOR_HZ, nyquistHz());
    }

    /** Shows / hides the header button row (L / R / phase / auto-setup / max).
     *  The Tune-notch wizard embeds this view as a bare chart and hides the
     *  controls. */
    public void setHeaderControlsVisible(boolean v) {
        if (headerBar != null) {
            headerBar.setVisible(v);
        }
    }

    /** Keeps the header bar visible but exposes ONLY the L / R channel-select
     *  buttons, excluding phase / auto-setup / maximize.  The
     *  Tune-notch wizard uses this so the user can toggle which measured
     *  channel (default R) the embedded chart shows via the same radio buttons
     *  the main pane has, without the rest of the pane's controls. */
    public void showChannelButtonsOnly() {
        if (headerBar == null) return;
        phaseBtn.setExcluded(true);
        autoSetupBtn.setExcluded(true);
        maxBtn.setExcluded(true);
        headerBar.setVisible(true);
        headerBar.reflow();
        // The bar's preferred width shrank to just the two channel buttons -
        // re-fit its absolute bounds so no empty band captures clicks.
        Point hbSize = headerBar.computeSize(SWT.DEFAULT, SWT.DEFAULT);
        headerBar.setBounds(MARGIN_LEFT + HEADER_BTN_INSET, BTN_TOP, hbSize.x, hbSize.y);
    }

    // -------------------------------------------------------------------------
    // Paint
    // -------------------------------------------------------------------------

    private void onPaint(PaintEvent e) {
        GC gc = e.gc;
        Rectangle area = getClientArea();

        // Pick up any colour edits the user committed via OK in the
        // Preferences dialog (also fingerprinted into the static-layer
        // cache below so the trace buffer rebuilds when a colour moves).
        syncColors();
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
            bgc.setBackground(color(ColorRole.BACKGROUND));
            bgc.fillRectangle(0, 0, area.width, area.height);
            bgc.setAntialias(SWT.ON);
            bgc.setTextAntialias(SWT.ON);
            AxisSpec xSpec = AxisSpec.log(fFreqMin, freqMax)
                    .withFormat(LabelFormat.FREQ);
            AxisSpec yLeftSpec = AxisSpec.linearNice(magBot, magTop, 10, 5.0)
                    .withFormat(LabelFormat.DB)
                    .withUnit(I18n.t("unit.db"));
            AxisSpec yRightSpec = phaseVisible
                    ? AxisSpec.linear(-180, 180, 8)
                            .withFormat(LabelFormat.PHASE_DEG)
                            .withUnit("φ")
                    : null;
            drawGrid(bgc, plot, xSpec, yLeftSpec, yRightSpec,
                     color(ColorRole.GRID), color(ColorRole.AXIS), color(ColorRole.TEXT), axisFont,
                     MAJOR_TICK_LEN, MINOR_TICK_LEN, null);
            if (compareActive()) {
                drawCompareTrace(bgc, plot, fFreqMin, freqMax, magTop, magBot, prefs);
            } else {
                drawTraces(bgc, plot, fFreqMin, freqMax, magTop, magBot);
                if (referenceActive()) {
                    drawReferenceOverlay(bgc, plot, fFreqMin, freqMax, magTop, magBot, prefs);
                }
            }
        });

        // Dynamic overlays - never cached because they change per frame.  The
        // banners are self-painting widgets driven entirely by events
        // (showSourceBanner / updateCompareBanner / repositionBanners), so
        // onPaint doesn't touch them; only the compare + unevenness tables are
        // drawn here.  The unevenness table stacks below the compare table when
        // both are visible.
        gc.setAntialias(SWT.ON);
        gc.setTextAntialias(SWT.ON);
        boolean compareTableVisible = compareActive();
        if (compareTableVisible) {
            drawCompareMeasurementTable(gc);
        }
        drawUnevennessTable(gc, compareTableVisible);
        drawUnevennessAnnotations(gc, plot, freqMin, freqMax, magTop, magBot);
        if (mouseInPlot) {
            drawCrosshair(gc, plot, freqMin, freqMax, magTop, magBot, phaseVisible);
        }
        Rectangle canvas = getClientArea();
        drawRectZoomOverlay(gc, canvas.width, canvas.height);
    }

    // -------------------------------------------------------------------------
    // Rectangular zoom (base machinery in AbstractMeasurementView)
    // -------------------------------------------------------------------------

    /** X = displayed frequency window (always log), Y = the magnitude-dB
     *  window.  The fixed ±180° phase axis is not zoom state. */
    @Override
    protected ZoomState captureZoomState() {
        return new ZoomState(prefs.getFreqRespFreqMinHz(), prefs.getFreqRespFreqMaxHz(),
                new double[] { prefs.getFreqRespMagBotDb() },
                new double[] { prefs.getFreqRespMagTopDb() });
    }

    /** Applies through the canonical range-change protocol, clamped like the
     *  wheel zoom; the pane's FREQRESP_RANGE_CHANGED subscriber re-syncs the
     *  scrollbars. */
    @Override
    protected boolean applyZoomState(ZoomState s) {
        double fMin = Math.max(FREQ_MIN_FLOOR_HZ, s.xMin());
        double fMax = Math.min(nyquistHz(), s.xMax());
        double top  = Math.min(MAG_TOP_ZOOM_MAX_DB, s.yMax()[0]);
        double bot  = Math.max(MAG_BOT_MIN_DB, s.yMin()[0]);
        if (fMax <= fMin || top <= bot) return false;   // degenerate after clamping
        prefs.setFreqRespFreqMinHz(fMin);
        prefs.setFreqRespFreqMaxHz(fMax);
        prefs.setFreqRespMagTopDb(top);
        prefs.setFreqRespMagBotDb(bot);
        if (!isolated) prefs.save();
        publishRangeChanged();
        redraw();
        return true;
    }

    /** Log-domain on X, linear-dB on Y - the crosshair / wheel-zoom mappings.
     *  Returns {@code null} for a selection that clamps to a degenerate range
     *  (e.g. the Nyquist ceiling dropped below the displayed window), so the
     *  base cancels the zoom instead of pushing a no-op undo entry. */
    @Override
    protected ZoomState zoomStateForRect(Rectangle sel) {
        Rectangle plot = zoomableArea();
        if (plot == null) return null;
        double fMin = Math.max(1.0, prefs.getFreqRespFreqMinHz());
        double fMax = prefs.getFreqRespFreqMaxHz();
        double top  = prefs.getFreqRespMagTopDb();
        double span = top - prefs.getFreqRespMagBotDb();
        double newTop  = Math.min(MAG_TOP_ZOOM_MAX_DB,
                top - (sel.y - plot.y) / (double) plot.height * span);
        double newBot  = Math.max(MAG_BOT_MIN_DB,
                top - (sel.y + sel.height - plot.y) / (double) plot.height * span);
        double newFMin = Math.max(FREQ_MIN_FLOOR_HZ, xToFreq(sel.x, plot, fMin, fMax, true));
        double newFMax = Math.min(nyquistHz(), xToFreq(sel.x + sel.width, plot, fMin, fMax, true));
        if (newFMax <= newFMin || newTop <= newBot) return null;
        return new ZoomState(newFMin, newFMax,
                new double[] { newBot }, new double[] { newTop });
    }

    /** Selections live inside the plot area (between the axis-label margins). */
    @Override
    protected Rectangle zoomableArea() {
        Rectangle area = getClientArea();
        if (area.width < 10 || area.height < 10) return null;
        int rightMargin = prefs.isFreqRespPhaseVisible() ? MARGIN_RIGHT_PHASE : MARGIN_RIGHT_NO_PHASE;
        return new Rectangle(
                MARGIN_LEFT,
                MARGIN_TOP,
                Math.max(1, area.width  - MARGIN_LEFT - rightMargin),
                Math.max(1, area.height - MARGIN_TOP  - MARGIN_BOTTOM));

    }

    /** Static-trace-layer cache key.  Lombok {@code @EqualsAndHashCode} derives
     *  the fingerprint hash from every rendering input - adding an input is
     *  adding a field, with no hand-rolled hash chain to forget it in.  The
     *  result fields contribute their identity hash (no {@code hashCode}
     *  override), matching the previous behaviour. */
    @RequiredArgsConstructor
    @EqualsAndHashCode
    private static final class TraceFingerprint {
        private final int width;
        private final int height;
        private final double freqMin;
        private final double freqMax;
        private final double magTop;
        private final double magBot;
        private final boolean phaseVisible;
        private final boolean leftVisible;
        private final boolean rightVisible;
        private final boolean showRiaa;
        private final boolean reverseRiaa;
        private final boolean iecAmendment;
        private final boolean compareMode;
        private final int compareSmoothWindow;
        // Ideal-filter reference - the source flag, its compare flag, and the
        // full param tuple (FilterParams equality folds in every filter pref),
        // so a source swap or any filter-param nudge rebuilds the static layer.
        private final boolean showFilter;
        private final boolean filterCompare;
        private final FilterParams filterParams;
        // Appearance prefs - included so a Preferences-dialog OK that changes
        // the width / signal / phase / reference / background invalidates the
        // static-layer cache and the trace rebuilds in the new look.
        private final double lineWidth;
        private final int signalColor;
        private final int phaseColor;
        private final int referenceColor;
        private final int backgroundColor;
        private final FreqRespResult leftResult;
        private final FreqRespResult rightResult;
    }

    /** Builds the fingerprint hash that changes whenever any input to the
     *  static-layer rendering changes.  When the fingerprint matches, the
     *  cached trace buffer is blitted instead of re-rendered. */
    private long computeFingerprint(Rectangle area, Preferences prefs,
                                    boolean phaseVisible,
                                    double freqMin, double freqMax,
                                    double magTop, double magBot) {
        return new TraceFingerprint(
                area.width, area.height,
                freqMin, freqMax, magTop, magBot, phaseVisible,
                prefs.isFreqRespLeftVisible(), prefs.isFreqRespRightVisible(),
                prefs.isFreqRespShowRiaa(), prefs.isFreqRespReverseRiaa(),
                prefs.isFreqRespIecAmendment(), prefs.isFreqRespCompareMode(),
                prefs.getFreqRespCompareSmoothWindow(),
                prefs.isFreqRespShowFilter(), prefs.isFreqRespFilterCompare(),
                FilterParams.of(prefs),
                prefs.getFreqRespLineWidth(),
                prefs.getFreqRespSignalColor(), prefs.getFreqRespPhaseColor(),
                prefs.getFreqRespReferenceColor(), prefs.getFreqRespBackgroundColor(),
                leftResult, rightResult).hashCode();
    }

    // -------------------------------------------------------------------------
    // Reference-curve seam - ONE source of the overlaid / compared reference.
    //
    // Only one reference can be active at a time (the tab enforces the mutual
    // exclusion between Show-RIAA and Show-Filter), so the RIAA and ideal-filter
    // curves share ONE pipeline: the overlay draw, the compare diff, the compare
    // readout, and the crosshair Δ all pull the reference dB from referenceDb()
    // and its colour from referenceColorRole().  Adding a second reference kind
    // means teaching only these seam methods, not duplicating the pipeline.
    // -------------------------------------------------------------------------

    /** {@code true} while a reference curve (RIAA or ideal filter) is enabled
     *  and available.  The filter source additionally requires a valid
     *  {@link #filterDesign} (invalid params => unavailable => no overlay). */
    private boolean referenceActive() {
        if (prefs.isFreqRespShowRiaa()) return true;
        if (prefs.isFreqRespShowFilter()) { refreshFilterDesign(); return filterDesign != null; }
        return false;
    }

    /** {@code true} while the ACTIVE reference source's compare pref is on -
     *  RIAA uses {@code freqRespCompareMode}, the filter uses
     *  {@code freqRespFilterCompare}. */
    private boolean referenceCompareOn() {
        if (prefs.isFreqRespShowRiaa())   return prefs.isFreqRespCompareMode();
        if (prefs.isFreqRespShowFilter()) return prefs.isFreqRespFilterCompare();
        return false;
    }

    /** The colour role for the active reference's overlay / compare trace -
     *  {@link ColorRole#RIAA_TRACE} for RIAA, {@link ColorRole#FILTER_TRACE}
     *  for the ideal filter. */
    private ColorRole referenceColorRole() {
        return prefs.isFreqRespShowFilter() ? ColorRole.FILTER_TRACE : ColorRole.RIAA_TRACE;
    }

    /** {@code true} when the compare (diff) trace + table + Δ readout should be
     *  drawn: the active reference is available, its compare pref is on, and a
     *  measurement is loaded.  The single gate for every compare-mode branch. */
    private boolean compareActive() {
        return referenceCompareOn() && referenceActive() && hasAnyResult();
    }

    /** The active reference's magnitude in dB at {@code fHz}.  RIAA uses the
     *  analytic {@link RiaaCurve}, normalised to 0 dB at 1 kHz.  The ideal
     *  filter uses its natural {@code filterDesign.evalDb(f)} (passband ≈ 0 dB)
     *  - NOT referenced to 1 kHz, because 1 kHz can sit deep in the stopband
     *  (a notch centred at 1 kHz, a high-pass above it, a band-pass away from
     *  it), where {@code evalDb(1000)} is large-negative or −∞ and would shove
     *  the whole overlay hundreds of dB off-screen.  In both cases the VIEW
     *  adds the measured-@1kHz {@code anchorDb}, aligning the reference's
     *  passband to the measured level.  Returns {@code NaN} when no reference
     *  is active / available so callers skip the point. */
    private double referenceDb(double fHz) {
        if (prefs.isFreqRespShowRiaa()) {
            return RiaaCurve.evalDb(fHz, prefs.isFreqRespReverseRiaa(), prefs.isFreqRespIecAmendment());
        }
        if (prefs.isFreqRespShowFilter()) {
            refreshFilterDesign();
            if (filterDesign == null) return Double.NaN;
            return flooredFilterEvalDb(fHz);
        }
        return Double.NaN;
    }

    /** The ideal filter's magnitude in dB at {@code fHz}, with a leakage FLOOR
     *  added to the NOTCH null.  A jw-axis zero is ALWAYS a sharp V - a
     *  hard clamp would draw a flat-bottomed plateau, which is physically wrong.
     *  Instead the ideal (mathematically −∞) null is summed in POWER with a
     *  constant leakage floor {@code 10^(−A/10)}:
     *  <pre>evalWithFloorDb = 10·log10( 10^(evalDb/10) + 10^(−A/10) )</pre>
     *  so the tip rounds asymptotically into −A (like a real finite-rejection
     *  notch), the skirts stay asymptotically unchanged (where {@code 10^(evalDb/10)}
     *  dominates the floor term), and there is NO flat segment.  {@code A} is the
     *  user's {@code stopAttenDb} (Mode 1) or {@link #NOTCH_DISPLAY_FLOOR_DB}
     *  (Mode 2, no attenuation spec).  This is the SINGLE eval seam: the dashed
     *  overlay ({@link #referenceDb}) AND the plateau / corner anchor
     *  ({@link #computeFilterAnchorDb}) both use it, so the floor is never
     *  duplicated.  For Inverse-Chebyshev / Elliptic the true stopband floor
     *  already sits near −A, so the leakage term barely reshapes it; it only
     *  bounds the monotone families' unbounded null.
     *
     *  <p>Precondition: {@link #filterDesign} is non-null (every caller refreshes
     *  it and null-checks first). */
    private double flooredFilterEvalDb(double fHz) {
        double db = filterDesign.evalDb(fHz);
        if (filterDesign.getType() == FilterType.NOTCH) {
            double atten = filterDesign.getStopAttenDb();
            double floorDb = Double.isFinite(atten) ? atten : NOTCH_DISPLAY_FLOOR_DB;
            double powEval  = Math.pow(10.0, db       / 10.0);
            double powFloor = Math.pow(10.0, -floorDb / 10.0);
            db = 10.0 * Math.log10(powEval + powFloor);
        }
        return db;
    }

    /** (Re)builds {@link #filterDesign} from the current filter prefs when the
     *  param snapshot has changed.  Invalid params (thrown by the factory, or
     *  a null result) leave {@code filterDesign == null} so the reference is
     *  treated as unavailable. */
    private void refreshFilterDesign() {
        FilterParams cur = FilterParams.of(prefs);
        if (cur.equals(filterParams)) return;
        filterParams = cur;
        filterDesign = cur.build();
    }

    /** Forces the ideal-filter curve + compare diff to rebuild on the next
     *  paint after a filter param changed: drops the cached snapshot so
     *  {@link #refreshFilterDesign()} rebuilds {@link #filterDesign}, and
     *  invalidates the compare-diff cache so a param change while comparing
     *  re-subtracts against the new curve.  Also refreshes the compare anchor
     *  + min/max table when the filter is the compared reference. */
    private void invalidateFilterReference() {
        filterParams     = null;
        compareDiffCache = null;
        invalidateFilterAnchor();   // fc / bandwidth moved -> the anchor region moved
        if (prefs.isFreqRespShowFilter() && prefs.isFreqRespFilterCompare() && hasAnyResult()) {
            recomputeCompareAnchor(prefs);
        }
    }

    /** Immutable snapshot of every filter pref that shapes the ideal-filter
     *  curve.  Equality drives the {@link #filterDesign} rebuild and extends
     *  the compare-diff + static-layer cache keys.  {@code build()} maps the
     *  active mode (spec vs order) to the matching {@link FilterDesign} factory. */
    @RequiredArgsConstructor
    @EqualsAndHashCode
    private static final class FilterParams {
        private final FilterType type;
        private final FilterResponse response;
        private final boolean modeOrder;
        private final double rippleDb;
        private final double stopAttenDb;
        private final double centerHz;
        private final double passHz;
        private final double stopHz;
        private final double orderPassHz;
        private final double orderRippleDb;
        private final int    order;
        private final double q;

        private static FilterParams of(Preferences p) {
            // The per-type params map is the single source of truth for every
            // scalar; the type + response selectors stay their own prefs.  This
            // snapshot's equality drives every filter cache, so a map entry
            // edited by the tab is picked up on the next paint with no
            // invalidation call (self-validating, per the refactor).
            FilterType type = p.getFreqRespFilterType();
            FreqRespFilterTypeParams fp = p.getFreqRespFilterParams(type);
            return new FilterParams(
                    type, p.getFreqRespFilterResponse(),
                    fp.isModeOrder(),
                    fp.getRippleDb(), fp.getStopAttenDb(),
                    fp.getCenterHz(), fp.getPassHz(),
                    fp.getStopHz(),
                    fp.getOrderPassHz(), fp.getOrderRippleDb(),
                    fp.getOrder(), fp.getQ());
        }

        /** Builds the design for the active mode, or {@code null} when the
         *  factory rejects the params (out-of-range spec, degenerate band). */
        private FilterDesign build() {
            try {
                return modeOrder
                        ? FilterDesign.ofOrder(type, response, order, orderRippleDb, orderPassHz, q)
                        : FilterDesign.ofSpec(type, response, rippleDb, stopAttenDb,
                                centerHz, passHz, stopHz);
            } catch (RuntimeException ex) {
                return null;
            }
        }
    }

    /** Paints the active reference curve (RIAA or ideal filter) as a dashed
     *  trace over the measured response, in the reference's colour.  Aligned
     *  at 1 kHz to the measured curve's 1 kHz value (or 0 dB if no measurement
     *  is loaded). */
    private void drawReferenceOverlay(GC gc, Rectangle plot, double freqMin, double freqMax,
                                      double magTop, double magBot, Preferences prefs) {
        double anchorDb = referenceAnchorDb(prefs);
        // The reference is analytic, so sample it deterministically at a
        // resolution the drawn shape can't depend on: one sample PER
        // PIXEL column, PLUS the filter's exact critical frequencies (BP/NOTCH
        // center, LP/HP pass edge) forced in as extra samples so the null /
        // corner is always rendered at its TRUE value regardless of view width.
        // Each sample carries the frequency the reference is evaluated at:
        // per-pixel samples use the pixel's frequency, critical samples the
        // EXACT critical frequency (fc), so the null's depth is evalDb(fc) - a
        // width-independent constant - not evalDb(pixel-rounded-fc), which would
        // still swing tens of dB on a 1-px resize.  ColumnBucketPainter merges
        // same-column samples by min/max, so a critical sample sharing a pixel
        // column with a shallow neighbour still surfaces the true null depth.
        // paintPolyline clips to `plot`.
        RefSamples s = referenceSampleColumns(plot, freqMin, freqMax, prefs);
        paintPolyline(gc, plot, color(referenceColorRole()), SWT.LINE_DASH,
                (float) prefs.getFreqRespLineWidth(), s.xs.length,
                i -> s.xs[i],
                i -> dbToYf(anchorDb + referenceDb(s.fs[i]), plot, magTop, magBot));
    }

    /** Parallel {@code (column, frequency)} sample arrays for the reference
     *  overlay: {@link #xs} is the absolute canvas x column, {@link #fs} the
     *  exact frequency the reference is evaluated at for that sample.  Same
     *  length; index-aligned. */
    private static final class RefSamples {
        final int[]    xs;
        final double[] fs;
        RefSamples(int[] xs, double[] fs) {
            this.xs = xs;
            this.fs = fs;
        }
    }

    /** Builds the reference-overlay sample list: one sample per pixel
     *  column of {@code plot} (frequency = that pixel's frequency) PLUS the
     *  active filter's critical frequencies as extra samples carrying the EXACT
     *  critical frequency (only when the ideal filter is the reference and its
     *  design is valid - RIAA has none).  The critical samples are appended, not
     *  de-duplicated: {@link ColumnBucketPainter} merges same-column samples by
     *  min/max, so a critical sharing a pixel column still surfaces the true
     *  null depth {@code evalDb(fc)}.  Because that depth is a width-independent
     *  constant, the notch null / filter corner renders at its true value no
     *  matter how the pane is resized. */
    private RefSamples referenceSampleColumns(Rectangle plot, double freqMin, double freqMax,
                                              Preferences prefs) {
        int width = Math.max(1, plot.width);
        // Extra critical-frequency samples (filter reference only) - the EXACT
        // fc, so the y-eval hits the true corner / null, not a pixel-rounded one.
        double[] crit = new double[0];
        if (prefs.isFreqRespShowFilter()) {
            refreshFilterDesign();
            if (filterDesign != null) {
                crit = filterDesign.criticalFrequenciesHz();
            }
        }
        int      n  = width + 1 + crit.length;
        int[]    xs = new int[n];
        double[] fs = new double[n];
        int m = 0;
        for (int x = plot.x; x <= plot.x + width; x++) {
            xs[m] = x;
            fs[m] = FreqRespFormat.xFractionToFreq((double) (x - plot.x) / width, freqMin, freqMax);
            m++;
        }
        for (double fCrit : crit) {
            double frac = FreqRespFormat.freqToXFraction(fCrit, freqMin, freqMax);
            int x = plot.x + (int) Math.round(frac * width);
            // Clamp the column to the plot span; off-screen criticals fold onto
            // an edge column but keep their exact frequency for the y-eval.
            xs[m] = Math.max(plot.x, Math.min(plot.x + width, x));
            fs[m] = fCrit;
            m++;
        }
        return new RefSamples(xs, fs);
    }

    /** Vertical alignment level (dB) for the active reference overlay:
     *  RIAA keeps the measured-@1 kHz anchor; the ideal filter uses corner-point
     *  anchoring for LP/HP/BP (so the ideal curve passes exactly through the
     *  measured trace at the filter's corner frequency) and PLATEAU anchoring for
     *  NOTCH (the measured plateau outside the null band).  With no
     *  measurement both fall back to 0 dB so the curve sits at its natural
     *  passband level. */
    private double referenceAnchorDb(Preferences prefs) {
        return prefs.isFreqRespShowFilter() ? filterAnchorDb(prefs) : riaaAnchorDb(prefs);
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

    /** Alignment level (dB) for the ideal-filter overlay: the ideal curve is
     *  drawn at {@code anchor + flooredEvalDb(f)} (passband ≈ 0 dB).  For LP/HP/BP
     *  the anchor is chosen so the curve passes EXACTLY through the measured trace
     *  at the filter's corner frequency -
     *  {@code anchor = measuredSmoothedDb(fCorner) − flooredFilterEvalDb(fCorner)}
     *  - the LP/HP pass edge, or (BP) the lower band edge, then upper, then centre
     *  as fallbacks.  For NOTCH the anchor is the measured PLATEAU mean outside the
     *  ideal's shoulder band.  See {@link #computeFilterAnchorDb}.  Falls
     *  back to 0 dB when no measurement is loaded, no filter design is available,
     *  or no anchor point qualifies.  Cached in
     *  {@link #filterAnchorCache}; the cache key is the {@link #filterDesign}
     *  identity, and every measurement / channel / filter-param change already
     *  invalidates it (the smoothed curve is a pure function of the measurement,
     *  so no extra invalidation is needed). */
    private double filterAnchorDb(Preferences prefs) {
        // A param edit rebuilds filterDesign (refreshFilterDesign, called below);
        // recompute the anchor when that identity moves even if the cached value
        // is still finite, so a param change picked up only via a redraw
        // re-anchors without an explicit invalidation call.
        refreshFilterDesign();
        if (Double.isFinite(filterAnchorCache) && filterAnchorCacheDesign == filterDesign) {
            return filterAnchorCache;
        }
        filterAnchorCache       = computeFilterAnchorDb(prefs);
        filterAnchorCacheDesign = filterDesign;
        return filterAnchorCache;
    }

    private double computeFilterAnchorDb(Preferences prefs) {
        FreqRespResult r = activeChannelResult(prefs);
        refreshFilterDesign();
        if (r == null || filterDesign == null) return 0.0;
        double[] freqs = r.getFreqs();
        if (freqs == null || freqs.length < 2) return 0.0;
        double[] sdb = floatingAvgCappedDb(r);

        // Corner-point anchoring: the ideal curve is aligned so it passes
        // exactly through the measured trace at the filter's ANCHOR point.
        // NOTCH anchors ONLY at the middle position - the notch point fc,
        // where the leakage-floored eval is a finite −A, so the ideal TIP
        // pins to the measured tip.  The other types use the design corners
        // (FilterDesign.cornerFrequenciesHz): LP/HP -> the pass edge; BP -> the
        // passband edges - tried in order, then the centre fc as the final
        // fallback.  An anchor "works" when both the measured value
        // (interpolated on the floating-average (9-point), capped curve,
        // inside range + below the Nyquist cap) AND the floored filter eval
        // are finite.
        double[] corners;
        if (filterDesign.getType() == FilterType.NOTCH) {
            corners = new double[] { filterDesign.getFcHz() };
        } else {
            double[] designCorners = filterDesign.cornerFrequenciesHz();
            corners = Arrays.copyOf(designCorners, designCorners.length + 1);
            corners[designCorners.length] = filterDesign.getFcHz();   // centre fallback
        }
        for (double fCorner : corners) {
            double meas = interpFromArray(freqs, sdb, fCorner);
            if (!Double.isFinite(meas)) continue;
            double eval = flooredFilterEvalDb(fCorner);
            if (!Double.isFinite(eval)) continue;
            return meas - eval;
        }
        return 0.0;
    }

    /** Drops the cached per-type filter anchor so the next overlay paint
     *  recomputes it.  Called wherever the measurement, active channel, or a
     *  filter param changes. */
    private void invalidateFilterAnchor() {
        filterAnchorCache       = Double.NaN;
        filterAnchorCacheDesign = null;
    }

    /** The result for the channel the RIAA overlay / comparison should
     *  anchor against - left if visible, else right if visible, else
     *  whichever non-null. */
    private FreqRespResult activeChannelResult(Preferences prefs) {
        if (prefs.isFreqRespLeftVisible()  && leftResult  != null) return leftResult;
        if (prefs.isFreqRespRightVisible() && rightResult != null) return rightResult;
        if (leftResult  != null) return leftResult;
        return rightResult;
    }

    /** Draws the (measured − reference) subtraction trace using the
     *  10-point moving-averaged diff so the rendered curve, the
     *  on-screen min/max table, and the crosshair Δ readout all show
     *  the same numbers.  Vertical anchor comes from
     *  {@link #compareAnchorOffset}, set by
     *  {@link #autoSetupCompare(Preferences)} to the median of the
     *  smoothed diff over 20 Hz-25 kHz so the curve's central value
     *  reads 0 dB.  Uses whichever scrollbar-controlled range is
     *  currently in Preferences. */
    private void drawCompareTrace(GC gc, Rectangle plot,
                                  double freqMin, double freqMax,
                                  double magTop, double magBot,
                                  Preferences prefs) {
        FreqRespResult src = activeChannelResult(prefs);
        if (src == null) return;
        double[] freqs  = src.getFreqs();
        boolean reverse = prefs.isFreqRespReverseRiaa();
        boolean iec     = prefs.isFreqRespIecAmendment();
        // Single shared compare state - the smoothed signal-point array
        // is already anchor-shifted (median over 20 Hz-25 kHz subtracted
        // inside getCompareDiff), so we draw smoothed[i] DIRECTLY with
        // no further subtraction.  Same array is read by the min/max
        // table and the crosshair Δ readout for guaranteed agreement.
        double[] smoothed = getCompareDiff(src, reverse, iec).smoothed;
        // Lanczos-smoothed where the span is sparse, else linear.  NaN smoothed
        // values (no valid point in the smoothing window) render as gaps; the
        // NaN-aware double[] kernel skips them as taps, so valid points draw all
        // the way up to a gap instead of blanking a kernel-width around it.
        // Solid trace in the active reference's colour: dark-green COMPARE_TRACE
        // for RIAA, FILTER_TRACE when the ideal filter is the reference.  The
        // filter uses its own trace role; RIAA keeps the distinct compare green.
        Color compareColor = prefs.isFreqRespShowFilter()
                ? color(referenceColorRole()) : color(ColorRole.COMPARE_TRACE);
        paintDataTrace(gc, plot, freqMin, freqMax, compareColor, SWT.LINE_SOLID,
                freqs, smoothed, v -> dbToYf(v, plot, magTop, magBot));
    }

    /** Returns the cached W-point moving-averaged copy of
     *  {@code (measDb − refDb)} across {@code src}'s full freq grid,
     *  where W is {@link Preferences#getFreqRespCompareSmoothWindow()}.
     *  The same smoothed array drives the drawn compare trace, the
     *  anchor median, and the min/max table - so all three agree.
     *
     *  <p>Smoothing is applied here, after the subtraction, so the
     *  on-screen curve reads the same numbers as the table.  In normal
     *  (non-compare) display mode the L/R traces are drawn unsmoothed;
     *  this helper is only ever called from {@link #drawCompareTrace}
     *  and {@link #recomputeCompareAnchor}.
     *
     *  <p>Samples whose magnitude is non-positive or non-finite yield
     *  NaN.  The cache key is the (source, reverse, iec, window) tuple. */
    /** Single source of truth for everything compare-mode.  Builds the
     *  smoothed {@code (measDb − refDb)} array at the SIGNAL-POINT level
     *  (one value per analyzer sweep point - never per screen pixel),
     *  then derives the anchor median and the relative min / max from
     *  the 20 Hz - 25 kHz subset of that same array.  Every consumer
     *  (drawing, crosshair Δ readout, anchor scalar, min/max table)
     *  pulls from the {@link CompareDiff} that this method caches -
     *  so all four read identical numbers by construction. */
    private CompareDiff getCompareDiff(FreqRespResult src, boolean reverse, boolean iec) {
        int W = Math.max(0, Math.min(100,
                prefs.getFreqRespCompareSmoothWindow()));
        // The reference source (RIAA vs ideal filter) and the filter's full
        // param tuple are part of the key: swapping source or nudging a filter
        // param yields a different diff even though src/reverse/iec/window are
        // unchanged.  filterDesign identity captures the whole filter tuple
        // (it is only rebuilt when a param moves - see refreshFilterDesign).
        boolean filterSrc = prefs.isFreqRespShowFilter();
        if (filterSrc) refreshFilterDesign();
        if (compareDiffCache != null
                && compareDiffCacheSrc == src
                && compareDiffCacheReverse == reverse
                && compareDiffCacheIec == iec
                && compareDiffCacheWindow == W
                && compareDiffCacheFilterSrc == filterSrc
                && compareDiffCacheFilter == filterDesign) {
            return compareDiffCache;
        }
        // 1. Raw (measDb − refDb) per signal point.  NaN for any point
        //    whose magnitude is non-positive / non-finite, or where the
        //    reference itself is unavailable.
        double[] freqs  = src.getFreqs();
        double[] magLin = src.getMagLin();
        int n = freqs.length;
        double[] raw = new double[n];
        for (int i = 0; i < n; i++) {
            double measDb = FreqRespFormat.linToDb(magLin[i]);
            if (!Double.isFinite(measDb)) { raw[i] = Double.NaN; continue; }
            double refDb = referenceDb(freqs[i]);
            raw[i] = Double.isFinite(refDb) ? measDb - refDb : Double.NaN;
        }
        // 2. Sliding mean in LOG-FREQUENCY space (1/W-octave window).
        //    The previous index-based window was useless on a 192 k-
        //    point log sweep: 20 points at 30 Hz covered ~0.04 Hz,
        //    way narrower than the visible noise oscillations
        //    (~1.7 Hz period at 30 Hz).  Switching to 1/W-octave
        //    makes the window proportional to the local frequency,
        //    so a 1/W-octave half-decade is the same physical
        //    bandwidth at every frequency.
        //
        //    W = 0 disables smoothing entirely; W ≥ 1 means
        //    "smooth over a 1/W-octave window".  Two pointers
        //    advance monotonically (overall O(n)) - for each
        //    sample i we extend hi while logF[hi+1] ≤ logF[i] +
        //    half, and advance lo while logF[lo] < logF[i] −
        //    half, maintaining a running sum / count.
        double[] smoothed;
        if (W <= 0) {
            smoothed = raw;
        } else {
            smoothed = new double[n];
            double[] logF = new double[n];
            for (int i = 0; i < n; i++) logF[i] = Math.log10(freqs[i]);
            // Half-width of a 1/W-octave window in log10 units.
            double halfLog = Math.log10(2.0) / (2.0 * W);
            double sum = 0.0;
            int    cnt = 0;
            int    lo  = 0;
            int    hi  = -1;
            for (int i = 0; i < n; i++) {
                double targetHi = logF[i] + halfLog;
                double targetLo = logF[i] - halfLog;
                while (hi + 1 < n && logF[hi + 1] <= targetHi) {
                    hi++;
                    double v = raw[hi];
                    if (!Double.isNaN(v)) { sum += v; cnt++; }
                }
                while (lo < n && logF[lo] < targetLo) {
                    double v = raw[lo];
                    if (!Double.isNaN(v)) { sum -= v; cnt--; }
                    lo++;
                }
                smoothed[i] = cnt > 0 ? sum / cnt : Double.NaN;
            }
        }
        // 3. Anchor - value of the smoothed curve at 1 kHz (log-interp
        //    between the two surrounding signal points).  Subtract from
        //    every smoothed value so the array we hand back passes
        //    through 0 dB at exactly 1 kHz.  Drawing, min/max, crosshair
        //    Δ and auto-setup all read smoothed[i] DIRECTLY afterwards.
        double anchor = valueAt1kHz(freqs, smoothed);
        if (Double.isFinite(anchor) && anchor != 0.0) {
            if (smoothed == raw) smoothed = raw.clone();
            for (int i = 0; i < n; i++) {
                if (Double.isFinite(smoothed[i])) smoothed[i] -= anchor;
            }
        }
        // 4. min / max over the anchored smoothed array, restricted to
        //    20 Hz - 25 kHz.  Real extrema - not symmetric, not
        //    percentile-clipped - so the table reads the actual
        //    deviation envelope.
        final double fLo = 20.0, fHi = 25000.0;
        double minDb = Double.NaN, maxDb = Double.NaN;
        for (int i = 0; i < n; i++) {
            double f = freqs[i];
            if (f < fLo || f > fHi) continue;
            double v = smoothed[i];
            if (!Double.isFinite(v)) continue;
            if (Double.isNaN(minDb) || v < minDb) minDb = v;
            if (Double.isNaN(maxDb) || v > maxDb) maxDb = v;
        }
        CompareDiff diff = new CompareDiff(smoothed, minDb, maxDb);
        compareDiffCache          = diff;
        compareDiffCacheSrc       = src;
        compareDiffCacheReverse   = reverse;
        compareDiffCacheIec       = iec;
        compareDiffCacheWindow    = W;
        compareDiffCacheFilterSrc = filterSrc;
        compareDiffCacheFilter    = filterDesign;
        return diff;
    }

    /** Compare-mode auto-setup.  Snaps the horizontal range to
     *  20 Hz - 25 kHz, computes a 10-point moving-averaged copy of the
     *  diff curve {@code (measDb − refDb)} over that band (smoothing
     *  used ONLY for this anchor calculation; the drawn trace stays
     *  unsmoothed), takes the median of the smoothed values as the
     *  vertical anchor so the curve's central value reads 0 dB, then
     *  fits the vertical window to the diff extrema with
     *  {@link #COMPARE_ZOOM_PAD_DB} of margin above and below.  Called
     *  from the pane when Compare is toggled on (RIAA or filter), or
     *  when Show RIAA is toggled on while Compare is already on. */
    public void autoSetupCompare(Preferences prefs) {
        if (!recomputeCompareAnchor(prefs)) return;

        // Vertical window - hug the diff extrema with a symmetric
        // COMPARE_ZOOM_PAD_DB margin in both directions.
        double newTop = compareSmoothedMax + COMPARE_ZOOM_PAD_DB;
        double newBot = compareSmoothedMin - COMPARE_ZOOM_PAD_DB;
        newTop = Math.min(MAG_TOP_ZOOM_MAX_DB, newTop);
        newBot = Math.max(MAG_BOT_MIN_DB, newBot);

        prefs.setFreqRespFreqMinHz(20.0);
        prefs.setFreqRespFreqMaxHz(25000.0);
        prefs.setFreqRespMagTopDb(newTop);
        prefs.setFreqRespMagBotDb(newBot);
        if (!isolated) prefs.save();
        publishRangeChanged();
        redraw();
    }

    /** Recomputes {@link #compareAnchorOffset} and
     *  {@link #compareSmoothedMin} / {@link #compareSmoothedMax} from the
     *  cached smoothed-diff array over 20 Hz-25 kHz.  Does NOT change the
     *  view's freq / mag window - used both by
     *  {@link #autoSetupCompare(Preferences)} (which then also sets the
     *  range) and by the smoothing-window pref change handler (which
     *  must refresh the table + anchor without disturbing the user's
     *  zoom).  Returns true on success, false when no data is available. */
    private boolean recomputeCompareAnchor(Preferences prefs) {
        FreqRespResult src = activeChannelResult(prefs);
        if (src == null) return false;
        // All three public scalars (anchor offset + min + max) are now
        // mirrors of the cached CompareDiff that getCompareDiff produces
        // - so the drawn trace, the crosshair Δ, and this method's
        // outputs are guaranteed to agree.
        CompareDiff diff = getCompareDiff(src,
                prefs.isFreqRespReverseRiaa(),
                prefs.isFreqRespIecAmendment());
        if (Double.isNaN(diff.minDb) || Double.isNaN(diff.maxDb)) return false;
        compareSmoothedMin  = diff.minDb;
        compareSmoothedMax  = diff.maxDb;
        return true;
    }

    /** Blinks a tooltip-style banner ("Comparison [reverse] RIAA [IEC] with
     *  measured") above the trace area while comparison mode is on.  Blink
     *  state flips every 500 ms; only the banner rect is redrawn so the
     *  trace cache stays valid. */
    /** Paints the compare-mode measurement table in the top-left
     *  corner, below the header buttons - two rows showing the min /
     *  max of the smoothed diff curve that fed the anchor (median)
     *  computation.  Analogous to FftView's THD table layout. */
    private void drawCompareMeasurementTable(GC gc) {
        if (Double.isNaN(compareSmoothedMin) || Double.isNaN(compareSmoothedMax)) return;
        gc.setFont(readoutFont);
        gc.setForeground(color(ColorRole.TEXT));
        int x     = MARGIN_LEFT + 6;
        int y     = BTN_TOP + BTN_H + 6;
        int lineH = gc.getFontMetrics().getHeight();
        drawOutlinedText(gc, "max: " + FreqRespFormat.formatDbReadout(compareSmoothedMax),
                x, y);
        drawOutlinedText(gc, "min: " + FreqRespFormat.formatDbReadout(compareSmoothedMin),
                x, y + lineH);
    }

    // -------------------------------------------------------------------------
    // Unevenness (response-flatness) readout
    // -------------------------------------------------------------------------

    /** Recomputes the unevenness readout into {@link #unevenLoHz} /
     *  {@link #unevenHiHz} / {@link #unevenPlusDb} from the active channel, using
     *  the curve appropriate to each mode: Mode B + the notch Mode-A walk
     *  read the despiked-raw curve ({@link #despikedCappedDb}); the peak Mode-A walk
     *  reads the floating-average curve ({@link #floatingAvgCappedDb}).  Both are
     *  Nyquist-capped.
     *
     *  <p>{@link UnevenMode#OFF}: clears all readout fields to {@code NaN} so
     *  nothing draws - no table, no annotations.
     *
     *  <p>{@link UnevenMode#LEVEL}: the {@link Preferences#isFreqRespUnevenNotch()
     *  Notch} checkbox picks the walk explicitly (no shape classification).  Peak
     *  walk (unchecked): find the highest point and walk out both ways while the
     *  value stays within {@code unevenDb} of the peak.  Notch walk (checked): walk
     *  out from the interior minimum while within {@code unevenDb} of it.  Report the
     *  OUTERMOST still-inside frequencies and the entered {@code unevenDb} UNIPOLAR
     *  (no ±).
     *
     *  <p>{@link UnevenMode#RANGE}: over [startHz, stopHz] (the stop clamped to
     *  the analysis Nyquist cap) take min/max and report the HALF-span
     *  {@code (max−min)/2} with ± and the requested range echoed.
     *
     *  <p>Sets all fields to {@code NaN} when the mode is OFF or no usable data
     *  exists. */
    private void recomputeUnevenness() {
        unevenLoHz  = Double.NaN;
        unevenHiHz  = Double.NaN;
        unevenPlusDb = Double.NaN;
        unevenThresholdDb = Double.NaN;
        unevenExtremumDb  = Double.NaN;
        UnevenMode mode = prefs.getFreqRespUnevenMode();
        if (mode == UnevenMode.OFF) return;
        FreqRespResult r = activeChannelResult(prefs);
        if (r == null) return;
        double[] freqs = r.getFreqs();
        double[] mag   = r.getMagLin();
        if (freqs == null || mag == null || freqs.length < 2) return;
        int n = freqs.length;

        if (mode == UnevenMode.RANGE) {
            // Mode B - min/max + crossing levels read the
            // DESPIKED-RAW curve (raw dB + 3-point median + Nyquist cap), NOT a
            // floating average: a fixed-N mean is a constant-Hz window (~3.3 Hz at
            // the FFT-bin grid), but a narrow user range (bench: 997-1010 Hz) may be
            // shorter than even 9 grid points, and any mean over it collapses to one
            // value (phantom full-width line at the average).  The median curve
            // preserves the true span, so Mode-B reads the wall depth as measured.
            double[] db   = despikedCappedDb(r);
            // Clamp the user range to the analysis Nyquist cap so the
            // range min/max can't reach into the weak-signal region.
            double cap    = analysisNyquistCapHz(r);
            double startHz = prefs.getFreqRespUnevenStartHz();
            double stopHz  = Math.min(prefs.getFreqRespUnevenStopHz(), cap);
            double minDb = Double.POSITIVE_INFINITY;
            double maxDb = Double.NEGATIVE_INFINITY;
            double loUsed = Double.NaN;
            double hiUsed = Double.NaN;
            for (int i = 0; i < n; i++) {
                double f = freqs[i];
                if (f < startHz || f > stopHz) continue;
                double d = db[i];
                if (!Double.isFinite(d)) continue;
                if (d < minDb) minDb = d;
                if (d > maxDb) maxDb = d;
                if (Double.isNaN(loUsed)) loUsed = f;   // first finite in-range
                hiUsed = f;                             // last finite in-range
            }
            if (!Double.isFinite(minDb) || !Double.isFinite(maxDb)) return;
            // Report the boundaries actually covered by finite (below-cap) data
            // so the Mode-B annotation ticks land on real samples, not on the
            // NaN'd top of the band.
            unevenLoHz   = loUsed;
            unevenHiHz   = hiUsed;
            unevenPlusDb = 0.5 * (maxDb - minDb);   // half-span, i18n key carries the ±
            // The Notch checkbox applies in RANGE mode too: it picks the range's
            // extremum of interest for the second green line - the lowest point
            // when checked, the highest otherwise.
            unevenExtremumDb = prefs.isFreqRespUnevenNotch() ? minDb : maxDb;
            return;
        }

        // LEVEL mode - the Notch checkbox picks the walk EXPLICITLY,
        // replacing any shape classification.  Checked: walk from the interior
        // minimum outward through the stopband (db ≤ min + unevenDb).  Unchecked:
        // walk from the peak outward through the passband (db ≥ peak − unevenDb).
        // The INITIAL extremum is searched only within the audio band,
        // while the walk itself runs the full array under the cap.
        double unevenDb = prefs.getFreqRespUnevenDb();

        if (prefs.isFreqRespUnevenNotch()) {
            // NOTCH walk runs on the RAW dB curve with ONLY a 3-point running
            // MEDIAN despike: ANY sliding-mean smoothing - even 1/48-oct -
            // flattens a high-Q null by tens of dB (bench: smoothed min −67.5 vs
            // true −81.5), so the min / threshold / boundary walks would sit on the
            // wrong depth AND the green threshold + lila verticals derived from that
            // curve wouldn't intersect the drawn RAW trace.  The median rejects lone
            // spikes without touching the null depth, so every geometry point below
            // comes from ONE curve that visually matches the blue trace.  Still
            // Nyquist-capped and audio-band-seeded.
            double[] ndb = despikedCappedDb(r);
            int minIdx = audioBandExtremumIdx(freqs, ndb, false);   // interior min in [20, 20k]
            if (minIdx < 0) return;
            double minDb  = ndb[minIdx];
            double ceilDb = minDb + unevenDb;
            // Walk out from the minimum while inside the stopband; a NaN (capped
            // / no-data) point ends the walk so a boundary never lands in the
            // ignored region.  The walk may cross out of the audio band.
            int lo = minIdx;
            for (int i = minIdx; i >= 0; i--) {
                if (!Double.isFinite(ndb[i]) || ndb[i] > ceilDb) break;
                lo = i;
            }
            int hi = minIdx;
            for (int i = minIdx; i < n; i++) {
                if (!Double.isFinite(ndb[i]) || ndb[i] > ceilDb) break;
                hi = i;
            }
            unevenLoHz        = freqs[lo];
            unevenHiHz        = freqs[hi];
            unevenPlusDb      = unevenDb;
            unevenThresholdDb = ceilDb;
            unevenExtremumDb  = minDb;
            return;
        }

        // PEAK walk runs on the FLOATING-AVERAGE (9-point), Nyquist-capped curve:
        // noise tamed, constant-Hz window, index-aligned.  Peak
        // find (audio band only), then walk out both ways while db ≥ peak − unevenDb
        // (walk may leave the audio band).
        double[] db = floatingAvgCappedDb(r);
        int peakIdx = audioBandExtremumIdx(freqs, db, true);
        if (peakIdx < 0) return;
        double peakDb = db[peakIdx];
        double floorDb = peakDb - unevenDb;
        int lo = peakIdx;
        for (int i = peakIdx; i >= 0; i--) {
            if (!Double.isFinite(db[i]) || db[i] < floorDb) break;
            lo = i;
        }
        int hi = peakIdx;
        for (int i = peakIdx; i < n; i++) {
            if (!Double.isFinite(db[i]) || db[i] < floorDb) break;
            hi = i;
        }
        unevenLoHz        = freqs[lo];
        unevenHiHz        = freqs[hi];
        unevenPlusDb      = unevenDb;
        unevenThresholdDb = floorDb;
        unevenExtremumDb  = peakDb;
    }

    /** Index of the extreme finite value of {@code db} - the MAX when
     *  {@code wantMax}, else the MIN - searched ONLY within the audio band
     *  [{@link #AUDIO_SEARCH_MIN_HZ}, {@link #AUDIO_SEARCH_MAX_HZ}].
     *  {@code freqs} is ascending and index-aligned with {@code db}; NaN entries
     *  are skipped.  Returns {@code -1} when the band holds no finite point. */
    private int audioBandExtremumIdx(double[] freqs, double[] db, boolean wantMax) {
        int    bestIdx = -1;
        double best    = wantMax ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        for (int i = 0; i < db.length; i++) {
            double f = freqs[i];
            if (f < AUDIO_SEARCH_MIN_HZ || f > AUDIO_SEARCH_MAX_HZ) continue;
            double d = db[i];
            if (!Double.isFinite(d)) continue;
            if (wantMax ? d > best : d < best) { best = d; bestIdx = i; }
        }
        return bestIdx;
    }

    // --- Measured-curve analysis constants ------------------------------------
    /** Fraction of Nyquist ({@code sampleRate/2}) above which the measured
     *  curve is IGNORED for all unevenness analysis: the top few
     *  percent carry a weak deconvolved signal buried in noise, so a spike
     *  there must not drive the peak/min find, the Mode-A walk,
     *  or the Mode-B crossing levels.  Both analysis curves
     *  ({@link #floatingAvgCappedDb(FreqRespResult)} and
     *  {@link #despikedCappedDb(FreqRespResult)}) set every point above
     *  {@code this × sampleRate/2} to NaN so those consumers skip it. */
    private static final double NYQUIST_ANALYSIS_FRACTION = 0.95;
    /** Floating-average window (grid points) for the LP/HP/BP peak walk +
     *  corner-anchor analysis.  The measurement grid is FFT-bin-
     *  spaced (linear in Hz), so a fixed point count is a CONSTANT-Hz window
     *  everywhere - unlike an octave fraction, which balloons to ≈2.3 kHz at
     *  20 kHz (1/6 oct) and swamps a narrow feature.  Centred ±4, NaN-aware,
     *  end-clamped. */
    private static final int    UNEVEN_SMOOTH_POINTS          = 9;
    /** Running-median window (points) for NOTCH-mode analysis: NO
     *  sliding-mean smoothing is applied - even a 1/48-octave mean flattens a
     *  high-Q null by tens of dB (bench: min −67.5 vs true −81.5), so the min /
     *  threshold / boundary walks would sit on the wrong depth and the derived
     *  green/lila annotation lines wouldn't intersect the drawn RAW trace.  A
     *  3-point running median instead rejects lone despike spikes while leaving
     *  the true null depth untouched, so every notch geometry point comes from
     *  ONE curve that visually matches the blue trace. */
    private static final int    NOTCH_DESPIKE_POINTS          = 3;
    /** Audio-band bounds (Hz) for the INITIAL extremum search: the peak
     *  (peak walk) and the interior minimum (notch-walk seed) are located ONLY
     *  within [{@link #AUDIO_SEARCH_MIN_HZ},
     *  {@link #AUDIO_SEARCH_MAX_HZ}] (clipped to the data range), so a reverse-RIAA
     *  or otherwise rising response can't seed off a Nyquist-noise ridge and
     *  put its peak at 143-182 kHz.  The subsequent boundary WALK may run beyond
     *  the audio band (still under the 0.95-Nyquist cap + NaN guard). */
    private static final double AUDIO_SEARCH_MIN_HZ =    20.0;
    private static final double AUDIO_SEARCH_MAX_HZ = 20000.0;
    /** Displayed NOTCH null depth (dB below the plateau) when the design carries
     *  no user stopband-attenuation spec (Mode 2, design-by-order - {@link
     *  FilterDesign#NO_STOP_ATTEN_SPEC}).  The ideal monotone-family null is
     *  mathematically −∞ (clamped −1000 in {@link FilterDesign#evalDb}); this
     *  renders it at a realistic, resolution-independent depth instead. */
    private static final double NOTCH_DISPLAY_FLOOR_DB = 120.0;

    /** The passband analysis curve for the LP/HP/BP peak walk +
     *  corner-anchor interpolation: the RAW dB of
     *  {@code r} put through a {@link #UNEVEN_SMOOTH_POINTS}-point FLOATING AVERAGE
     *  (centred running mean over a fixed number of GRID points, not an octave
     *  fraction), then every point at or above
     *  {@link #analysisNyquistCapHz(FreqRespResult)} forced to {@code NaN}.  The
     *  measurement grid is FFT-bin-spaced (linear in Hz), so a fixed point count is
     *  a constant-Hz window everywhere - an octave fraction ballooned to ≈2.3 kHz
     *  at 20 kHz and swamped narrow features.  The mean tames noise spikes so
     *  cross-points and extrema sit on what was measured; the Nyquist cap drops the
     *  weak-signal, huge-noise top of the band.  NaN entries are skipped by the
     *  extrema / median / walk loops, so the array stays index-aligned with
     *  {@code r.getFreqs()} - callers keep their frequency lookup by the same
     *  index.  (NOTCH analysis + Mode-B deliberately do NOT use this - any mean
     *  flattens a high-Q null / collapses a narrow range; see
     *  {@link #despikedCappedDb}.) */
    private double[] floatingAvgCappedDb(FreqRespResult r) {
        double[] freqs = r.getFreqs();
        double[] mag   = r.getMagLin();
        int n = freqs.length;
        double[] raw = new double[n];
        for (int i = 0; i < n; i++) raw[i] = FreqRespFormat.linToDb(mag[i]);
        double[] s = runningMean(raw, UNEVEN_SMOOTH_POINTS);
        double cap = analysisNyquistCapHz(r);
        for (int i = 0; i < n; i++) {
            if (freqs[i] >= cap) s[i] = Double.NaN;
        }
        return s;
    }

    /** The depth-preserving despiked analysis curve: the
     *  RAW dB of {@code r} with ONLY a {@link #NOTCH_DESPIKE_POINTS}-point running
     *  MEDIAN despike - no sliding mean, which would flatten a high-Q null by tens
     *  of dB.  The median rejects lone spikes while leaving the true null depth
     *  untouched, so the min / threshold / boundary walks + the green/lila
     *  annotation geometry all sit on ONE curve that visually matches the drawn RAW
     *  trace.  Serves BOTH notch Mode-A (interior-min walk) AND all of Mode-B
     *  (range min/max + start/stop crossing levels): a fixed-N mean would collapse
     *  a narrow Mode-B range to a single value (bench: 997-1010 Hz on a notch
     *  averaged to one level), so Mode-B reads this raw-median curve directly.
     *  Same analysis-Nyquist cap + index alignment as
     *  {@link #floatingAvgCappedDb(FreqRespResult)}. */
    private double[] despikedCappedDb(FreqRespResult r) {
        double[] freqs = r.getFreqs();
        double[] mag   = r.getMagLin();
        int n = freqs.length;
        double[] raw = new double[n];
        for (int i = 0; i < n; i++) raw[i] = FreqRespFormat.linToDb(mag[i]);
        double[] s = runningMedian(raw, NOTCH_DESPIKE_POINTS);
        double cap = analysisNyquistCapHz(r);
        for (int i = 0; i < n; i++) {
            if (freqs[i] >= cap) s[i] = Double.NaN;
        }
        return s;
    }

    /** {@code window}-point (odd) running median of {@code v}, centred on each
     *  index and clamped at the ends.  Non-finite taps are dropped from the
     *  window; a position with no finite tap yields NaN.  Rejects lone spikes
     *  without shifting a genuine extremum - unlike a mean, the deep sample at a
     *  high-Q null survives.  Index-aligned with {@code v}. */
    private double[] runningMedian(double[] v, int window) {
        int n = v.length;
        int half = window / 2;
        double[] out = new double[n];
        double[] buf = new double[window];
        for (int i = 0; i < n; i++) {
            int cnt = 0;
            for (int j = Math.max(0, i - half); j <= Math.min(n - 1, i + half); j++) {
                if (Double.isFinite(v[j])) buf[cnt++] = v[j];
            }
            if (cnt == 0) { out[i] = Double.NaN; continue; }
            double[] w = Arrays.copyOf(buf, cnt);
            Arrays.sort(w);
            out[i] = (cnt % 2 == 1) ? w[cnt / 2] : 0.5 * (w[cnt / 2 - 1] + w[cnt / 2]);
        }
        return out;
    }

    /** {@code window}-point (odd) centred running MEAN of {@code v}, clamped at
     *  the ends.  Non-finite taps are dropped from the window; a
     *  position with no finite tap yields NaN.  Because the measurement grid is
     *  FFT-bin-spaced (linear in Hz), a fixed tap count is a constant-Hz window at
     *  every frequency - unlike the log-freq octave-fraction smoother it replaced.
     *  Sibling of {@link #runningMedian} (same window/NaN/clamp shape, mean instead
     *  of median).  Index-aligned with {@code v}. */
    private double[] runningMean(double[] v, int window) {
        int n = v.length;
        int half = window / 2;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double sum = 0.0;
            int    cnt = 0;
            for (int j = Math.max(0, i - half); j <= Math.min(n - 1, i + half); j++) {
                if (Double.isFinite(v[j])) { sum += v[j]; cnt++; }
            }
            out[i] = cnt > 0 ? sum / cnt : Double.NaN;
        }
        return out;
    }

    /** {@link #NYQUIST_ANALYSIS_FRACTION} × the measured curve's Nyquist
     *  ({@code sampleRate/2}); analysis frequencies at or above this are
     *  ignored.  Independent of the user's display-zoom Nyquist
     *  pref - this cap is fixed so the readout never depends on how far the
     *  user zoomed out.
     *
     *  <p>Loaded {@code .frc} results carry the rate read back from the file's
     *  own {@code sample_rate_hz} header comment (the load path calls
     *  {@code FreqRespCalHelper.readSampleRateHz}), so the cap normally
     *  reflects the TRUE capture rate - a 384 kHz notch file opened on a
     *  48 kHz device analyzes over its full span.  Only a legacy headerless
     *  file still gets the live backend's rate stamped, so keep the
     *  data-derived guard: when {@code sr <= 0}, or the grid top runs past the
     *  stamped {@code sr/2} (the grid proves the stamped rate wrong for this
     *  data), fall back to the highest finite grid frequency, so even such a
     *  file is analyzed over its real span. */
    private double analysisNyquistCapHz(FreqRespResult r) {
        int sr = r.getSampleRate() > 0 ? r.getSampleRate()
                : prefs.current().getInputSampleRate();
        double gridTop = highestFiniteFreqHz(r);
        double nyqCap  = NYQUIST_ANALYSIS_FRACTION * sr * 0.5;
        // Data-derived fallback: no usable rate, or the grid extends beyond the
        // stamped Nyquist -> trust the grid's own top instead of the wrong rate.
        if (sr <= 0 || (Double.isFinite(gridTop) && gridTop > sr * 0.5)) {
            return Double.isFinite(gridTop)
                    ? NYQUIST_ANALYSIS_FRACTION * gridTop : nyqCap;
        }
        return nyqCap;
    }

    /** The highest frequency in {@code r}'s grid backed by a finite, positive
     *  magnitude - the real top of the loaded/measured span, used to derive the
     *  analysis cap when the stamped sample rate is absent or wrong for the data
     *  (see {@link #analysisNyquistCapHz}).  {@code NaN} when no such point
     *  exists. */
    private double highestFiniteFreqHz(FreqRespResult r) {
        double[] freqs = r.getFreqs();
        double[] mag   = r.getMagLin();
        if (freqs == null || mag == null) return Double.NaN;
        for (int i = freqs.length - 1; i >= 0; i--) {
            if (Double.isFinite(mag[i]) && mag[i] > 0.0 && Double.isFinite(freqs[i])) {
                return freqs[i];
            }
        }
        return Double.NaN;
    }

    /** Builds the unevenness readout string for the active mode, or {@code null}
     *  when no result exists. */
    private String unevennessReadout() {
        if (!Double.isFinite(unevenPlusDb)) return null;
        String db = FreqRespFormat.formatDbReadout(unevenPlusDb);
        String lo = FreqRespFormat.formatHzReadout(unevenLoHz);
        String hi = FreqRespFormat.formatHzReadout(unevenHiHz);
        return prefs.getFreqRespUnevenMode() == UnevenMode.RANGE
                ? I18n.t("freqResp.uneven.readout.range", db, lo, hi)
                : I18n.t("freqResp.uneven.readout", lo, hi, db);
    }

    /** Paints the unevenness readout as an outlined-text overlay, below the
     *  header buttons.  When the compare table is visible it stacks below it
     *  (two lines), otherwise it takes the compare table's slot.  Drawn
     *  whenever a result exists. */
    private void drawUnevennessTable(GC gc, boolean belowCompareTable) {
        String text = unevennessReadout();
        if (text == null) return;
        gc.setFont(readoutFont);
        gc.setForeground(color(ColorRole.TEXT));
        int x     = MARGIN_LEFT + 6;
        int lineH = gc.getFontMetrics().getHeight();
        int y     = BTN_TOP + BTN_H + 6 + (belowCompareTable ? 2 * lineH + 4 : 0);
        drawOutlinedText(gc, text, x, y);
    }

    // --- Unevenness plot annotations (dynamic overlay) -----------------------
    /** Half-width (px) of the short green crossing tick drawn at each Mode-B
     *  boundary; the full tick is {@code 2 × this} = 40 px. */
    private static final int    UNEVEN_TICK_HALF_PX = 20;
    /** Two Mode-B crossing levels within this many dB are treated as equal, so
     *  a single full-width green line replaces the two ticks. */
    private static final double UNEVEN_LEVEL_EPS_DB = 0.05;

    /** Draws the LEVEL / RANGE unevenness annotations in the dynamic overlay
     *  layer, only while a Mode readout is active (mode != OFF and a
     *  measurement exists => {@link #unevenPlusDb} is finite).  GREEN = the RIAA
     *  trace colour; LILA = the FILTER trace colour.  All geometry goes through
     *  the live freq->x / dbToYf transforms and is clipped to {@code plot}.
     *
     *  <p>Every line - the GREEN horizontals AND the LILA verticals - is dotted
     *  at the line width of the RIAA / filter curves ({@link #setTraceLineAttributes}),
     *  so the annotations read as the same family of overlays.
     *
     *  <ul>
     *    <li><b>LEVEL</b>: a GREEN horizontal at the threshold level
     *        ({@link #unevenThresholdDb}) and a second GREEN horizontal at the
     *        walk's reference extremum ({@link #unevenExtremumDb} - the peak, or
     *        the notch minimum), both between the two boundary frequencies, plus a
     *        LILA vertical at EACH boundary spanning the plot.</li>
     *    <li><b>RANGE</b>: LILA verticals at the stored (Nyquist-capped) start +
     *        stop; a GREEN horizontal at the range's extremum
     *        ({@link #unevenExtremumDb}) between the boundaries; and, independent
     *        of it, where each vertical crosses the DESPIKED-RAW, capped curve,
     *        a 40 px GREEN horizontal tick - or, if both
     *        crossing levels are equal (±{@link #UNEVEN_LEVEL_EPS_DB}), one
     *        full-plot-width GREEN horizontal at that level.</li>
     *  </ul> */
    private void drawUnevennessAnnotations(GC gc, Rectangle plot,
                                           double freqMin, double freqMax,
                                           double magTop, double magBot) {
        if (!Double.isFinite(unevenPlusDb)) return;
        Color green = color(ColorRole.RIAA_TRACE);
        Color lila  = color(ColorRole.FILTER_TRACE);
        Rectangle prevClip = gc.getClipping();
        gc.setClipping(plot);
        LineAttributes prevAttrs = gc.getLineAttributes();
        // Every annotation line - green horizontals AND lila verticals - is
        // dotted at the same line width as the RIAA / filter curves (reuse the
        // base's pooled dotted stroke), so the annotations read as the same
        // family of overlays.
        setTraceLineAttributes(gc, (float) prefs.getFreqRespLineWidth(), SWT.LINE_DOT);

        if (prefs.getFreqRespUnevenMode() == UnevenMode.RANGE) {
            drawUnevennessModeB(gc, plot, freqMin, freqMax, magTop, magBot, green, lila);
        } else {
            drawUnevennessModeA(gc, plot, freqMin, freqMax, magTop, magBot, green, lila);
        }

        gc.setLineAttributes(prevAttrs);
        gc.setClipping(prevClip);
    }

    private void drawUnevennessModeA(GC gc, Rectangle plot,
                                     double freqMin, double freqMax,
                                     double magTop, double magBot,
                                     Color green, Color lila) {
        if (!Double.isFinite(unevenLoHz) || !Double.isFinite(unevenHiHz)
                || !Double.isFinite(unevenThresholdDb)) {
            return;
        }
        int xLo = freqToX(unevenLoHz, plot, freqMin, freqMax, true);
        int xHi = freqToX(unevenHiHz, plot, freqMin, freqMax, true);
        int yTh = (int) Math.round(dbToYf(unevenThresholdDb, plot, magTop, magBot));
        // Green horizontal at the threshold, between the two boundaries.  The
        // dotted stroke + line width are set once by the caller.
        gc.setForeground(green);
        gc.drawLine(xLo, yTh, xHi, yTh);
        // Second green horizontal at the walk's reference extremum (the peak, or
        // the notch minimum), same span - shows the extremum level itself.
        if (Double.isFinite(unevenExtremumDb)) {
            int yEx = (int) Math.round(dbToYf(unevenExtremumDb, plot, magTop, magBot));
            gc.drawLine(xLo, yEx, xHi, yEx);
        }
        // Lila verticals at each boundary, full plot height.
        gc.setForeground(lila);
        gc.drawLine(xLo, plot.y, xLo, plot.y + plot.height);
        gc.drawLine(xHi, plot.y, xHi, plot.y + plot.height);
    }

    private void drawUnevennessModeB(GC gc, Rectangle plot,
                                     double freqMin, double freqMax,
                                     double magTop, double magBot,
                                     Color green, Color lila) {
        // Boundaries follow the stored range - the stop is already clamped to the
        // analysis Nyquist cap by recomputeUnevenness.
        if (!Double.isFinite(unevenLoHz) || !Double.isFinite(unevenHiHz)) return;
        double startHz = unevenLoHz;
        double stopHz  = unevenHiHz;
        int xStart = freqToX(startHz, plot, freqMin, freqMax, true);
        int xStop  = freqToX(stopHz,  plot, freqMin, freqMax, true);
        // Lila verticals at start + stop, full plot height.  The dotted stroke +
        // line width are set once by the caller.
        gc.setForeground(lila);
        gc.drawLine(xStart, plot.y, xStart, plot.y + plot.height);
        gc.drawLine(xStop,  plot.y, xStop,  plot.y + plot.height);
        // Second green horizontal - INDEPENDENT of the crossing-level logic
        // below: the range's extremum of interest (highest point, or lowest
        // with the Notch checkbox on) between the two boundaries.
        if (Double.isFinite(unevenExtremumDb)) {
            gc.setForeground(green);
            int yExt = (int) Math.round(dbToYf(unevenExtremumDb, plot, magTop, magBot));
            gc.drawLine(xStart, yExt, xStop, yExt);
        }
        // Crossing levels where each vertical meets the DESPIKED-RAW, capped
        // curve - the same curve Mode-B min/max reads, so the ticks land
        // on the real wall depth, not on a mean that collapsed the narrow range.
        FreqRespResult src = activeChannelResult(prefs);
        if (src == null) return;
        double[] freqs = src.getFreqs();
        double[] sdb   = despikedCappedDb(src);
        double dbStart = interpFromArray(freqs, sdb, startHz);
        double dbStop  = interpFromArray(freqs, sdb, stopHz);
        if (!Double.isFinite(dbStart) || !Double.isFinite(dbStop)) return;
        gc.setForeground(green);
        if (Math.abs(dbStart - dbStop) <= UNEVEN_LEVEL_EPS_DB) {
            // Equal levels -> one full-width green horizontal at that level.
            int y = (int) Math.round(dbToYf(0.5 * (dbStart + dbStop), plot, magTop, magBot));
            gc.drawLine(plot.x, y, plot.x + plot.width, y);
        } else {
            int yStart = (int) Math.round(dbToYf(dbStart, plot, magTop, magBot));
            int yStop  = (int) Math.round(dbToYf(dbStop,  plot, magTop, magBot));
            gc.drawLine(xStart - UNEVEN_TICK_HALF_PX, yStart, xStart + UNEVEN_TICK_HALF_PX, yStart);
            gc.drawLine(xStop  - UNEVEN_TICK_HALF_PX, yStop,  xStop  + UNEVEN_TICK_HALF_PX, yStop);
        }
    }

    // -------------------------------------------------------------------------
    // Overlay banners (loaded-file path + comparison mode).  Self-painting /
    // self-blinking BlinkBanner widgets: font + colours are set once in
    // configureBanner() at construction, so each show just sets text + makes the
    // widget visible - onPaint never touches them.  Each is sized to its text at
    // the plot's top-right but clamped clear of the header buttons, re-anchored
    // only when the text or geometry (resize / phase-axis) changes.
    // -------------------------------------------------------------------------

    /** One-time banner setup: font + the blink / outline palette colours.  A
     *  later background recolour disposes the old outline Color, but BlinkBanner
     *  guards against that (it drops the halo, the text still draws), so the
     *  colours never need re-pushing. */
    private void configureBanner(BlinkBanner b) {
        b.setFont(readoutFont);
        b.setColors(color(ColorRole.BLINK_LIT), color(ColorRole.BLINK_DIM),
                    color(ColorRole.BACKGROUND));
        b.setVisible(false);
    }

    /** Shows the "Loaded: ..." banner for the current {@link #sourceFilePath}, or
     *  hides it when the path is cleared.  A pure overlay -> no canvas repaint. */
    private void showSourceBanner() {
        if (sourceFilePath == null || sourceFilePath.isEmpty()) {
            hideSourceBanner();
            return;
        }
        sourceBanner.setText(I18n.t("fft.loaded.prefix", sourceFilePath));
        sourceBanner.setVisible(true);
        repositionBanners();
    }

    /** Hides the loaded-file banner (e.g. when a new measurement starts). */
    private void hideSourceBanner() {
        sourceBanner.setVisible(false);
        repositionBanners();   // compare banner moves up into the freed line
    }

    /** Shows the compare banner with its current text when compare mode is
     *  active over a measurement + RIAA overlay, else hides it.  Banner only;
     *  the caller repaints the canvas for the compare trace / table. */
    private void updateCompareBanner() {
        boolean show = prefs.isFreqRespCompareMode() && hasAnyResult()
                && prefs.isFreqRespShowRiaa();
        if (!show) {
            compareBanner.setVisible(false);
            repositionBanners();
            return;
        }
        compareBanner.setText(I18n.t("freqResp.compare.banner")
                .replace("{0}", prefs.isFreqRespReverseRiaa()
                        ? I18n.t("freqResp.compare.banner.reverse") : "")
                .replace("{1}", prefs.isFreqRespIecAmendment()
                        ? I18n.t("freqResp.compare.banner.iec") : ""));
        compareBanner.setVisible(true);
        repositionBanners();
    }

    /** Anchors the visible banners at the plot's top-right, each sized to just
     *  its text but never extending left past the header buttons (clamped via
     *  {@link BlinkBanner#alignRight}).  The width tracks the text, so this runs
     *  on show/hide (text set) + resize + phase toggle - never per paint. */
    private void repositionBanners() {
        if (!sourceBanner.getVisible() && !compareBanner.getVisible()) {
            return;
        }
        Rectangle plot = zoomableArea();
        if (plot == null) {
            return;
        }
        GC gc = new GC(this);
        gc.setFont(readoutFont);
        int h = gc.textExtent("X").y + 2;
        gc.dispose();
        int leftInset  = headerBar.getBounds().x + headerBar.getBounds().width + BANNER_BTN_GAP;
        int rightInset = getClientArea().width - (plot.x + plot.width) + 6;
        if (sourceBanner.getVisible()) {
            sourceBanner.alignRight(leftInset, rightInset, plot.y + 4, h);
        }
        if (compareBanner.getVisible()) {
            int y = sourceBanner.getVisible() ? plot.y + 4 + h + 2 : plot.y + 6;
            compareBanner.alignRight(leftInset, rightInset, y, h);
        }
    }

    private void drawTraces(GC gc, Rectangle plot, double freqMin, double freqMax,
                            double magTop, double magBot) {
        if (prefs.isFreqRespLeftVisible() && leftResult != null) {
            paintTrace(gc, leftResult, plot, freqMin, freqMax, magTop, magBot,
                    color(ColorRole.LEFT_TRACE));
        }
        if (prefs.isFreqRespRightVisible() && rightResult != null) {
            paintTrace(gc, rightResult, plot, freqMin, freqMax, magTop, magBot,
                    color(ColorRole.RIGHT_TRACE));
        }
        if (prefs.isFreqRespPhaseVisible()) {
            if (prefs.isFreqRespLeftVisible() && leftResult != null) {
                paintPhase(gc, leftResult, plot, freqMin, freqMax, color(ColorRole.PHASE_TRACE));
            }
            if (prefs.isFreqRespRightVisible() && rightResult != null) {
                paintPhase(gc, rightResult, plot, freqMin, freqMax, color(ColorRole.PHASE_TRACE));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Lanczos trace smoothing - reconstruct one band-limited sample per pixel
    // (sinx/x) instead of joining the data points with straight segments.  A
    // hardcoded switch so it can be A/B-compared by flipping + rebuilding.
    // -------------------------------------------------------------------------

    /** Master on/off for sinc (Lanczos) trace smoothing.  Off = linear segments. */
    private static final boolean LANCZOS_TRACES = true;

    /** Linear-amplitude floor that keeps a Lanczos overshoot from driving the
     *  reconstructed magnitude negative (-> {@code log10} of a negative number). */
    private static final double LANCZOS_MAG_FLOOR_LIN = 1e-15;

    /** Lanczos downsample factor for the visible data span, or 0 to fall back to the
     *  linear per-point feed (smoothing off, or the span carries more than
     *  {@link Lanczos#MAX_LANCZOS_DOWNSAMPLE} samples per pixel - too dense to upsample). */
    private double lanczosScale(double[] freqs, double freqMin, double freqMax, int width) {
        if (!LANCZOS_TRACES || freqs == null || freqs.length < 2 || width < 2) return 0;
        int lo = indexBelow(freqs, freqMin);
        int hi = indexBelow(freqs, freqMax);
        double samplesPerPx = Math.max(1, hi - lo) / (double) width;
        return samplesPerPx <= Lanczos.MAX_LANCZOS_DOWNSAMPLE ? Math.max(1.0, samplesPerPx) : 0;
    }

    /** Largest index {@code i} with {@code freqs[i] <= f} (binary search), clamped to range. */
    private int indexBelow(double[] freqs, double f) {
        int lo = 0, hi = freqs.length - 1;
        if (f <= freqs[0])  return 0;
        if (f >= freqs[hi]) return hi;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (freqs[mid] <= f) lo = mid; else hi = mid;
        }
        return lo;
    }

    /** Fractional data index for frequency {@code f}, interpolated in log-freq so the
     *  index advances uniformly along the log axis the kernel reconstructs against. */
    private double fracIndex(double[] freqs, double f) {
        int lo = indexBelow(freqs, f);
        if (lo >= freqs.length - 1) return freqs.length - 1;
        double f0 = freqs[lo], f1 = freqs[lo + 1];
        if (f1 <= f0 || f <= f0) return lo;
        return lo + (Math.log(f) - Math.log(f0)) / (Math.log(f1) - Math.log(f0));
    }

    /** Draws one data-driven trace through {@link #paintPolyline}: a per-pixel Lanczos
     *  reconstruction of {@code data} (aligned with {@code freqs}) when smoothing is on and
     *  the span is sparse enough, else the linear per-point feed.  {@code toY} maps a
     *  reconstructed-or-raw sample value to its sub-pixel Y (or {@code NaN} to drop it). */
    private void paintDataTrace(GC gc, Rectangle plot, double freqMin, double freqMax,
                                Color color, int lineStyle, double[] freqs, double[] data,
                                DoubleUnaryOperator toY) {
        double scale = lanczosScale(freqs, freqMin, freqMax, plot.width);
        int n = freqs.length;
        float lw = (float) prefs.getFreqRespLineWidth();
        if (scale > 0) {
            paintPolyline(gc, plot, color, lineStyle, lw, plot.width,
                    i -> plot.x + i,
                    i -> toY.applyAsDouble(Lanczos.lanczos(data, n,
                            fracIndex(freqs, xToFreq(plot.x + i, plot, freqMin, freqMax, true)), scale)));
        } else {
            paintPolyline(gc, plot, color, lineStyle, lw, n,
                    i -> freqToX(freqs[i], plot, freqMin, freqMax, true),
                    i -> toY.applyAsDouble(data[i]));
        }
    }

    private void paintTrace(GC gc, FreqRespResult result, Rectangle plot,
                            double freqMin, double freqMax,
                            double magTop, double magBot, Color color) {
        double[] freqs  = result.getFreqs();
        double[] magLin = result.getMagLin();
        if (freqs == null || magLin == null) return;
        paintDataTrace(gc, plot, freqMin, freqMax, color, SWT.LINE_SOLID, freqs, magLin,
                v -> dbToYf(FreqRespFormat.linToDb(Math.max(LANCZOS_MAG_FLOOR_LIN, v)), plot, magTop, magBot));
    }

    private void paintPhase(GC gc, FreqRespResult result, Rectangle plot,
                            double freqMin, double freqMax, Color color) {
        double[] freqs    = result.getFreqs();
        double[] phaseRad = result.getPhaseRad();
        if (freqs == null || phaseRad == null) return;
        double scale = lanczosScale(freqs, freqMin, freqMax, plot.width);
        float lw = (float) prefs.getFreqRespLineWidth();
        if (scale <= 0) {
            paintPolyline(gc, plot, color, SWT.LINE_DOT, lw, freqs.length,
                    i -> freqToX(freqs[i], plot, freqMin, freqMax, true),
                    i -> phaseToYf(Math.toDegrees(phaseRad[i]), plot));
            return;
        }
        // Lanczos with a LOCAL phase unwrap so the kernel never rings across a ±180° wrap;
        // re-wrapped at draw time so the wrap still shows as a clean vertical jump with
        // smooth curves either side.  Only the visible span (+ kernel padding) is unwrapped
        // - cheap, no per-result cache.
        int pad  = (int) Math.ceil(Lanczos.LANCZOS_A * scale) + 1;
        int from = Math.max(0, indexBelow(freqs, freqMin) - pad);
        int to   = Math.min(freqs.length - 1, indexBelow(freqs, freqMax) + pad);
        int len  = to - from + 1;
        double[] uw = new double[len];
        uw[0] = phaseRad[from];
        for (int k = 1; k < len; k++) {
            uw[k] = uw[k - 1] + wrapToPi(phaseRad[from + k] - phaseRad[from + k - 1]);
        }
        paintPolyline(gc, plot, color, SWT.LINE_DOT, lw, plot.width,
                i -> plot.x + i,
                i -> phaseToYf(Math.toDegrees(wrapToPi(Lanczos.lanczos(uw, len,
                        fracIndex(freqs, xToFreq(plot.x + i, plot, freqMin, freqMax, true)) - from, scale))), plot));
    }

    /** Wraps a radian angle to (−π, π] - used to unwrap the phase before Lanczos and to
     *  re-wrap the reconstructed value for display. */
    private double wrapToPi(double r) {
        double twoPi = 2.0 * Math.PI;
        return r - twoPi * Math.floor((r + Math.PI) / twoPi);
    }



    // -------------------------------------------------------------------------
    // Crosshair
    // -------------------------------------------------------------------------

    private void drawCrosshair(GC gc, Rectangle plot, double freqMin, double freqMax,
                               double magTop, double magBot, boolean phaseVisible) {
        if (mouseX < plot.x || mouseX > plot.x + plot.width) return;
        if (mouseY < plot.y || mouseY > plot.y + plot.height) return;

        double frac = (mouseX - plot.x) / (double) plot.width;
        double cursorFreq = FreqRespFormat.xFractionToFreq(frac, freqMin, freqMax);
        int    sr      = lastResultSampleRate > 0 ? lastResultSampleRate
                : prefs.current().getInputSampleRate();
        double maxFreq = prefs.getFreqRespNyquistFraction() * sr;
        if (cursorFreq > maxFreq && maxFreq > 0) cursorFreq = maxFreq;

        gc.setForeground(color(ColorRole.CROSSHAIR));
        gc.setLineStyle(SWT.LINE_DOT);
        gc.drawLine(mouseX, plot.y, mouseX, plot.y + plot.height);
        gc.drawLine(plot.x, mouseY, plot.x + plot.width, mouseY);
        gc.setLineStyle(SWT.LINE_SOLID);

        StringBuilder sb = new StringBuilder();
        sb.append("f = ").append(formatFrequencyFine(cursorFreq));
        // Magnitude (dB) at the cursor's height on the left axis - the level
        // under the pointer, independent of the traces.
        double magFrac = (mouseY - plot.y) / (double) plot.height;
        if (magFrac < 0) magFrac = 0;
        if (magFrac > 1) magFrac = 1;
        sb.append('\n').append("y = ").append(FreqRespFormat.formatDbReadout(magTop - magFrac * (magTop - magBot)));
        boolean showCompareDelta = compareActive();
        if (showCompareDelta) {
            // Compare mode draws the smoothed (measured − reference) curve,
            // so the readout interpolates the SAME smoothed array - anything
            // else would disagree with what the user sees on screen.
            FreqRespResult src = activeChannelResult(prefs);
            if (src != null) {
                CompareDiff diff = getCompareDiff(src,
                        prefs.isFreqRespReverseRiaa(), prefs.isFreqRespIecAmendment());
                // diff.smoothed is already anchor-shifted, so the
                // interpolated value IS the Δ readout - no further
                // subtraction.
                double s = interpFromArray(src.getFreqs(), diff.smoothed, cursorFreq);
                if (Double.isFinite(s)) {
                    sb.append('\n').append("Δ = ")
                            .append(FreqRespFormat.formatDbReadout(s));
                }
            }
        } else {
            if (prefs.isFreqRespLeftVisible() && leftResult != null) {
                double db = drawnDb(leftResult, cursorFreq);
                sb.append('\n').append("L = ").append(FreqRespFormat.formatDbReadout(db));
            }
            if (prefs.isFreqRespRightVisible() && rightResult != null) {
                double db = drawnDb(rightResult, cursorFreq);
                sb.append('\n').append("R = ").append(FreqRespFormat.formatDbReadout(db));
            }
        }
        if (phaseVisible && !showCompareDelta) {
            FreqRespResult phaseSrc =
                    (prefs.isFreqRespLeftVisible()  && leftResult  != null) ? leftResult
                  : (prefs.isFreqRespRightVisible() && rightResult != null) ? rightResult
                  : null;
            if (phaseSrc != null) {
                double phaseRad = interpPhase(phaseSrc, cursorFreq);
                sb.append('\n').append("φ = ")
                        .append(FreqRespFormat.formatPhaseReadout(Math.toDegrees(phaseRad)));
            }
        }
        drawReadoutBox(gc, sb.toString(), mouseX + 12, mouseY + 12, plot,
                readoutFont, color(ColorRole.OVERLAY_BG), color(ColorRole.BUTTON_FRAME), color(ColorRole.TEXT));
    }

    /**
     * The dB value the TRACE DRAWS for {@code result} at frequency {@code f}:
     * the same per-pixel Lanczos reconstruction the trace painter uses when it
     * is active for the current axis and width, the plain log-linear bin
     * interpolation otherwise.  Every readout that quotes the trace - the
     * crosshair, the tune-notch marker - reads THIS, so a number on screen
     * always matches the curve on screen: taken separately, the bin minimum,
     * the Lanczos dip and the linear interpolation disagree by 2-3 dB at a
     * deep notch - a V the kernel legitimately reconstructs below its bins.
     * {@code NaN} outside the grid.
     */
    public double drawnDb(FreqRespResult r, double f) {
        double[] freqs = r.getFreqs();
        double[] mag   = r.getMagLin();
        if (freqs == null || mag == null || freqs.length < 2) return Double.NaN;
        if (f < freqs[0] || f > freqs[freqs.length - 1]) return Double.NaN;
        Rectangle plot = zoomableArea();
        if (plot != null) {
            double scale = lanczosScale(freqs, prefs.getFreqRespFreqMinHz(),
                    prefs.getFreqRespFreqMaxHz(), plot.width);
            if (scale > 0) {
                double v = Lanczos.lanczos(mag, freqs.length, fracIndex(freqs, f), scale);
                return FreqRespFormat.linToDb(Math.max(LANCZOS_MAG_FLOOR_LIN, v));
            }
        }
        return interpDb(r, f);
    }

    /**
     * The minimum the trace DRAWS for {@code result} across the current
     * frequency axis - scanned on the SAME per-pixel grid the painter uses,
     * so an overlay anchored to it (the tune-notch marker and its auto-fit
     * floor) matches the visible curve exactly: a log-uniform scan of its own
     * misses the razor tip by a fraction of a pixel and leaves the padding
     * below the notch short.  {@code null} before layout or
     * without data; else {@code {frequencyHz, dB}}.
     */
    public double[] drawnMinimum(FreqRespResult r) {
        Rectangle plot = zoomableArea();
        if (plot == null || plot.width < 2) return null;
        double[] freqs = r.getFreqs();
        double[] mag   = r.getMagLin();
        if (freqs == null || mag == null || freqs.length < 2) return null;
        double fMin = prefs.getFreqRespFreqMinHz();
        double fMax = prefs.getFreqRespFreqMaxHz();
        double bestF  = Double.NaN;
        double bestDb = Double.POSITIVE_INFINITY;
        if (lanczosScale(freqs, fMin, fMax, plot.width) > 0) {
            // Lanczos regime: the painter samples one reconstruction value per
            // pixel column - scan exactly those columns.
            for (int i = 0; i < plot.width; i++) {
                double f  = xToFreq(plot.x + i, plot, fMin, fMax, true);
                double db = drawnDb(r, f);
                if (!Double.isNaN(db) && db < bestDb) {
                    bestDb = db;
                    bestF  = f;
                }
            }
        } else {
            // Bins-direct regime (more bins than the kernel will downsample -
            // the tune-notch case at high point counts): the painter draws the
            // RAW bins as polyline vertices, so the drawn tip IS the bin
            // minimum - a pixel-grid sample between vertices reads shallower
            // and leaves the padding below the notch short.
            int lo = indexBelow(freqs, fMin);
            int hi = Math.min(freqs.length - 1, indexBelow(freqs, fMax) + 1);
            for (int i = lo; i <= hi; i++) {
                if (freqs[i] < fMin || freqs[i] > fMax) continue;
                double db = FreqRespFormat.linToDb(Math.max(LANCZOS_MAG_FLOOR_LIN, mag[i]));
                if (db < bestDb) {
                    bestDb = db;
                    bestF  = freqs[i];
                }
            }
        }
        return Double.isNaN(bestF) ? null : new double[] { bestF, bestDb };
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

    /** Log-frequency linear interpolation of an arbitrary value array
     *  aligned 1:1 with a freq grid (e.g. the cached smoothed-diff
     *  array).  Returns {@code NaN} when {@code f} is outside the grid
     *  or when either neighbouring bin is NaN. */
    /** Log-freq linear interpolation of {@code smoothed} at 1 kHz -
     *  the value the compare trace is anchored to so it reads 0 dB at
     *  exactly 1 kHz after subtraction.  Returns NaN when 1 kHz falls
     *  outside {@code freqs} or the bracketing samples are NaN. */
    private double valueAt1kHz(double[] freqs, double[] smoothed) {
        return interpFromArray(freqs, smoothed, 1000.0);
    }

    private double interpFromArray(double[] freqs, double[] vals, double f) {
        if (freqs == null || vals == null || freqs.length < 2) return Double.NaN;
        if (f < freqs[0] || f > freqs[freqs.length - 1]) return Double.NaN;
        int lo = 0, hi = freqs.length - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (freqs[mid] <= f) lo = mid; else hi = mid;
        }
        double v0 = vals[lo];
        // Exact grid hit -> the left sample IS the answer; don't require the right
        // neighbour (it may be NaN at the Nyquist-analysis cap boundary).
        if (freqs[lo] == f) return v0;
        double v1 = vals[hi];
        if (Double.isNaN(v0) || Double.isNaN(v1)) return Double.NaN;
        double t = (Math.log(f) - Math.log(freqs[lo])) / (Math.log(freqs[hi]) - Math.log(freqs[lo]));
        return v0 + t * (v1 - v0);
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
        int rightMargin = prefs.isFreqRespPhaseVisible() ? MARGIN_RIGHT_PHASE : MARGIN_RIGHT_NO_PHASE;
        mouseInPlot = e.x >= MARGIN_LEFT && e.x <= area.width - rightMargin
                  && e.y >= MARGIN_TOP   && e.y <= area.height - MARGIN_BOTTOM;
        redraw();
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
    /** Outer-most allowable frequency / magnitude window - zoom-out stops
     *  here so the user can't scroll into territory where no useful data
     *  ever lives.  Horizontal: 0 Hz to Nyquist (sampleRate / 2).
     *  Vertical: +20 dB to −300 dB. */
    private static final double MAG_TOP_MAX_DB  =   20.0;
    /** Headroom kept above the highest displayed trace point when Maximize /
     *  Auto-setup fit the magnitude top - room for a correction-lifted peak to
     *  breathe without clipping at the ceiling. */
    private static final double MAG_HEADROOM_DB =   20.0;
    /** Symmetric vertical pad (dB) for the compare-mode auto-zoom
     *  ({@link #autoSetupCompare(Preferences)}): the diff curve is a flatness
     *  deviation of a few dB, so the window hugs it with this margin above the
     *  highest and below the lowest point - NOT the generic
     *  {@link #MAG_HEADROOM_DB} marker headroom, which would waste 20 dB on a
     *  ±0.5 dB trace. */
    private static final double COMPARE_ZOOM_PAD_DB = 2.0;
    /** Upper bound when zooming / panning via the mouse wheel (Ctrl /
     *  Ctrl+Shift / plain wheel).  Wider than the maximize default so
     *  the user can scroll up into the "signal is louder than DAC FS"
     *  range - e.g. when a loaded calibration with sub-unity gain
     *  multiplies the displayed magnitude well past +20 dBFS.  Maximize
     *  still snaps to {@link #MAG_TOP_MAX_DB} (+20 dB) so the default
     *  view stays familiar. */
    private static final double MAG_TOP_ZOOM_MAX_DB = 120.0;
    private static final double MAG_BOT_MIN_DB  = -300.0;
    /** Vertical window the Maximize button snaps to.  Wider zoom-out is
     *  still available via the wheel, but +20 -> −150 dB is the useful
     *  default for the FreqResp view - covers the full dynamic range of
     *  any analog device measurement without burning vertical pixels on
     *  the noise floor below −150 dB. */
    private static final double MAG_DEFAULT_BOT_DB = -150.0;
    /** Smallest log-axis floor for the frequency window.  Pure 0 Hz is
     *  unrepresentable on a log axis, so we clamp to a tiny positive
     *  value when the user zooms out fully. */
    private static final double FREQ_MIN_FLOOR_HZ = 1.0;

    /** Returns the maximal analyzed frequency - the active backend's
     *  Nyquist (sampleRate/2) scaled by the user's
     *  {@code freqRespNyquistFraction} pref (96-100 %).  Used as the
     *  right-most zoom-out limit, so the trace and scrollbar both stop
     *  at this fraction of Nyquist instead of going right up to Fs/2
     *  (where the deconvolution kernel's energy rolls off). */
    private double nyquistHz() {
        int sr = lastResultSampleRate > 0 ? lastResultSampleRate
                : prefs.current().getInputSampleRate();
        double frac = prefs.getFreqRespNyquistFraction();
        if (!Double.isFinite(frac) || frac <= 0.0) frac = 1.0;
        return Math.max(1.0, sr * 0.5 * frac);
    }

    private void zoomFrequencyAroundCursor(int dir) {
        double fMin = prefs.getFreqRespFreqMinHz();
        double fMax = prefs.getFreqRespFreqMaxHz();
        if (fMin <= 0) fMin = 1.0;
        Rectangle plot = zoomableArea();
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
        if (!isolated) prefs.save();
        publishRangeChanged();
        redraw();
    }

    private void zoomMagnitudeAroundCursor(int dir) {
        double magTop = prefs.getFreqRespMagTopDb();
        double magBot = prefs.getFreqRespMagBotDb();
        Rectangle plot = zoomableArea();
        if (plot == null) return;
        double frac = (mouseY - plot.y) / (double) plot.height;
        double cursorDb = magTop - frac * (magTop - magBot);

        double scale = (dir > 0) ? 1.0 / MAG_ZOOM_FACTOR : MAG_ZOOM_FACTOR;
        double newTop = cursorDb + (magTop - cursorDb) * scale;
        double newBot = cursorDb + (magBot - cursorDb) * scale;
        if (newTop <= newBot) return;
        // Clamp the outer magnitude window to [MAG_BOT_MIN_DB, MAG_TOP_ZOOM_MAX_DB].
        // The zoom-mode upper limit is wider than the maximize default
        // so the user can scroll up past +20 dB to inspect signals that
        // a sub-unity calibration multiplies into very high values.
        newTop = Math.min(MAG_TOP_ZOOM_MAX_DB, newTop);
        newBot = Math.max(MAG_BOT_MIN_DB, newBot);
        if (newTop <= newBot) return;
        prefs.setFreqRespMagTopDb(newTop);
        prefs.setFreqRespMagBotDb(newBot);
        if (!isolated) prefs.save();
        publishRangeChanged();
        redraw();
    }

    private void panFrequency(int dir) {
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
        if (!isolated) prefs.save();
        publishRangeChanged();
        redraw();
    }

    private void panMagnitude(int dir) {
        double magTop = prefs.getFreqRespMagTopDb();
        double magBot = prefs.getFreqRespMagBotDb();
        double span = magTop - magBot;
        double delta = span * MAG_PAN_FRAC * dir;
        double newTop = magTop + delta;
        double newBot = magBot + delta;
        if (newTop > MAG_TOP_ZOOM_MAX_DB) {
            double shift = MAG_TOP_ZOOM_MAX_DB - newTop;
            newTop += shift; newBot += shift;
        }
        if (newBot < MAG_BOT_MIN_DB) {
            double shift = MAG_BOT_MIN_DB - newBot;
            newTop += shift; newBot += shift;
        }
        prefs.setFreqRespMagTopDb(newTop);
        prefs.setFreqRespMagBotDb(newBot);
        if (!isolated) prefs.save();
        publishRangeChanged();
        redraw();
    }

    private void publishRangeChanged() {
        if (!isolated) MessageBus.instance().publish(Events.FREQRESP_RANGE_CHANGED);
    }

    // -------------------------------------------------------------------------
    // Coordinate transforms
    //
    // freqToX (log-axis) and dbToY now live on AbstractFreqDomainView -
    // FreqResp always passes {@code logFreq=true}.  Only the phase-axis
    // transform stays here because it's view-specific (FFT doesn't paint
    // a phase trace).
    // -------------------------------------------------------------------------

    /** Maps a phase in degrees to its sub-pixel ({@code double}) Y on the φ axis - the
     *  dotted phase trace strokes between integer pixel rows. */
    private double phaseToYf(double deg, Rectangle plot) {
        return plot.y + FreqRespFormat.phaseToYFraction(deg) * plot.height;
    }
}
