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

package org.edgo.audio.measure.gui.freqresp;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.edgo.audio.measure.cli.util.FreqRespCalHelper;
import org.edgo.audio.measure.cli.util.FreqRespCalibration;
import org.edgo.audio.measure.cli.util.StereoFreqRespCalibration;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.fft.MathUtil;
import org.edgo.audio.measure.gui.bind.Bindings;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.AbstractFreqDomainView;
import org.edgo.audio.measure.gui.common.SvgPaths;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.preferences.Preferences;
import org.edgo.audio.measure.gui.widgets.BlinkBanner;
import org.edgo.audio.measure.gui.widgets.ToolButton;
import org.edgo.audio.measure.gui.widgets.Toolbar;

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

    /** Horizontal offset of the header-button row past the dB-axis labels.
     *  Keeps the L/R/phase/max tile clear of the numeric tick labels that
     *  the axis renderer paints into the same vertical band. */
    private static final int HEADER_BTN_INSET = 23;

    // --- Colours -------------------------------------------------------------
    // All FreqResp palette colours live in the AbstractMeasurementView
    // palette — accessed via color(ColorRole.X).  syncColors() pushes
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
    /** Display copies, with the currently-loaded calibration divided in (if
     *  any).  {@link #onCalibrationChanged()} keeps these in sync with the
     *  store every time the user loads / clears / wizard-applies a new
     *  calibration. */
    private FreqRespResult leftResult;
    private FreqRespResult rightResult;
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
     *    <li>{@link #drawCompareTrace} — plots
     *        {@code smoothed[i] − anchorDb} per visible freq.</li>
     *    <li>{@link #recomputeCompareAnchor} — copies the scalar
     *        fields into the public-display variables.</li>
     *    <li>The compare-mode crosshair Δ readout — interpolates
     *        {@code smoothed[]} at the cursor.</li>
     *  </ul>
     *  All three consumers therefore agree by construction — one
     *  smoothing pass, one median, one min/max. */
    private static final class CompareDiff {
        /** Already-anchored, W-point moving-averaged
         *  {@code (measDb − refDb − median20to25k)} aligned with the
         *  source result's freq grid.  The median over 20 Hz – 25 kHz
         *  has been subtracted from every value so the curve's central
         *  tendency sits at 0 dB; consumers read these values DIRECTLY
         *  (no further subtraction).  {@code NaN} for any point where
         *  the raw magnitude was non-positive / non-finite. */
        final double[] smoothed;
        /** Min / max of {@link #smoothed} within 20 Hz – 25 kHz, after
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

    /** Min / max of the smoothed diff curve over 20 Hz–25 kHz, mirroring
     *  {@link CompareDiff#minDb} / {@link CompareDiff#maxDb}.  Shown in
     *  the top-left measurement table.  {@code NaN} until the first
     *  {@link #recomputeCompareAnchor} runs. */
    private double compareSmoothedMin = Double.NaN;
    private double compareSmoothedMax = Double.NaN;

    /** Cache slot for {@link CompareDiff}.  Invalidated implicitly when
     *  the source / flag / window tuple changes — replacing the result
     *  via setLeftResult / setRightResult / onCalibrationChanged hands
     *  us a fresh object reference, and changing reverse / iec / window
     *  in the prefs ticks one of the other key components. */
    private CompareDiff    compareDiffCache;
    private FreqRespResult compareDiffCacheSrc;
    private boolean        compareDiffCacheReverse;
    private boolean        compareDiffCacheIec;
    private int            compareDiffCacheWindow;

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

    public FreqRespView(Composite parent) {
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

        allocateFonts();

        // Self-blinking overlay banners (no canvas redraw); positioned in onPaint.
        compareBanner = new BlinkBanner(this);
        compareBanner.setVisible(false);
        sourceBanner  = new BlinkBanner(this);
        sourceBanner.setVisible(false);

        // Header buttons (below): L and R are radio (exactly one channel shown at a
        // time), phase is an independent toggle, max is a push that resets the view.
        // L / R behave as dependent (radio) toggles: clicking either
        // unconditionally activates that channel and deactivates the
        // other.  No early-return when the clicked side is already on —
        // a single normalize pass also corrects a stale "both true" /
        // "both false" state that a hand-edited YAML or older session
        // might have carried in.
        // Normalise on construction: if the persisted state is both true or both false,
        // snap to L-only so the radio invariant holds from the very first paint.
        Preferences pInit = Preferences.instance();
        if (pInit.isFreqRespLeftVisible() == pInit.isFreqRespRightVisible()) {
            pInit.setFreqRespLeftVisible(true);
            pInit.setFreqRespRightVisible(false);
            pInit.save();
        }
        // Header buttons — ToolButton widgets in a Toolbar.  L/R is a radio (channel
        // select); phase keeps its own coloured icon; auto-setup/max are icon pushes.
        chanButtonFont = new Font(getDisplay(), "Consolas", 12, SWT.BOLD);   // same as FFT/scope
        phaseFillGray  = new Color(getDisplay(), 0xE6, 0xE6, 0xE6);          // 90% grey so the icon reads
        headerBar = new Toolbar(this, BTN_W, BTN_H);
        leftBtn  = headerBar.chanButton("L", color(ColorRole.TEXT), color(ColorRole.BUTTON_FRAME),
                color(ColorRole.LEFT_BTN_CHAN),  chanButtonFont, I18n.t("freqresp.button.left.tooltip"),  pInit.isFreqRespLeftVisible(),  "channel");
        rightBtn = headerBar.chanButton("R", color(ColorRole.TEXT), color(ColorRole.BUTTON_FRAME),
                color(ColorRole.RIGHT_BTN_CHAN), chanButtonFont, I18n.t("freqresp.button.right.tooltip"), pInit.isFreqRespRightVisible(), "channel");
        phaseBtn = headerBar.coloredToggle(SvgPaths.PHASE_SINE, 18,
                color(ColorRole.BUTTON_FRAME), phaseFillGray,
                I18n.t("freqresp.button.phase.tooltip"), pInit.isFreqRespPhaseVisible());
        autoSetupBtn = headerBar.pushButton(SvgPaths.ARROWS_TO_CIRCLE, 16,
                rgb(ColorRole.TEXT), rgb(ColorRole.BACKGROUND), color(ColorRole.TEXT),
                I18n.t("freqresp.button.autosetup.tooltip"));
        maxBtn = headerBar.pushButton(SvgPaths.ARROWS_FROM_CIRCLE, 16,
                rgb(ColorRole.TEXT), rgb(ColorRole.BACKGROUND), color(ColorRole.TEXT),
                I18n.t("freqresp.button.maximize.tooltip"));
        Point hbSize = headerBar.computeSize(SWT.DEFAULT, SWT.DEFAULT);
        headerBar.setBounds(MARGIN_LEFT + HEADER_BTN_INSET, BTN_TOP, hbSize.x, hbSize.y);
        headerBar.layout();
        leftBtn.addListener(SWT.Selection, e -> {
            if (leftBtn.isToggled()) {
                Preferences p = Preferences.instance();
                p.setFreqRespLeftVisible(true); p.setFreqRespRightVisible(false); p.save(); redraw();
            }
        });
        rightBtn.addListener(SWT.Selection, e -> {
            if (rightBtn.isToggled()) {
                Preferences p = Preferences.instance();
                p.setFreqRespRightVisible(true); p.setFreqRespLeftVisible(false); p.save(); redraw();
            }
        });
        phaseBtn.addListener(SWT.Selection, e -> {
            Preferences p = Preferences.instance();
            p.setFreqRespPhaseVisible(phaseBtn.isToggled()); p.save(); redraw();
        });
        autoSetupBtn.addListener(SWT.Selection, e -> autoSetupMagnitudeRange());
        maxBtn.addListener(SWT.Selection, e -> resetToDefaultView());

        addPaintListener(this::onPaint);
        addListener(SWT.MouseWheel, this::onMouseWheel);
        addListener(SWT.MouseMove,  this::onMouseMove);
        addListener(SWT.MouseExit,  e -> { mouseInPlot = false; redraw(); });

        // Re-trace whenever the active calibration changes (load, clear, or
        // wizard Apply).  The view divides the raw result by the new
        // calibration to keep what's painted in sync with the store.
        calibrationChangedListener = ignored -> onCalibrationChanged();
        MessageBus.instance().subscribe(Events.FREQRESP_CALIBRATION_CHANGED,
                calibrationChangedListener);
        // Compare-params changed (e.g. the smoothing-window pref): refresh
        // the anchor + min/max table from the new smoothed array, then
        // redraw.  Does NOT alter the view's zoom — see recomputeCompareAnchor.
        compareParamsChangedListener = ignored -> onCompareParamsChanged();
        MessageBus.instance().subscribe(Events.FREQRESP_COMPARE_PARAMS_CHANGED,
                compareParamsChangedListener);

        // RIAA overlay prefs (Show / Reverse / IEC) are bound to their tab
        // checkboxes in the pane; the view simply subscribes to repaint when
        // any of them changes — onPaint reads the live flags, so a redraw is
        // all that's needed.  Show additionally carries the one-shot compare
        // auto-zoom: when Show turns on while Compare is already armed and a
        // measurement exists, the compare trace becomes active for the first
        // time, so fit it once (mirrors the Compare-toggle auto-zoom).
        Preferences prefsBind = Preferences.instance();
        Bindings.onChange(this, prefsBind.freqRespShowRiaaProperty(), show -> {
            if (show && prefsBind.isFreqRespCompareMode() && hasAnyResult()) {
                autoSetupCompare(prefsBind);
            }
            redraw();
        });
        Bindings.onChange(this, prefsBind.freqRespReverseRiaaProperty(), v -> redraw());
        Bindings.onChange(this, prefsBind.freqRespIecAmendmentProperty(), v -> redraw());

        addDisposeListener(e -> {
            if (calibrationChangedListener != null) {
                MessageBus.instance().unsubscribe(Events.FREQRESP_CALIBRATION_CHANGED,
                        calibrationChangedListener);
            }
            if (compareParamsChangedListener != null) {
                MessageBus.instance().unsubscribe(Events.FREQRESP_COMPARE_PARAMS_CHANGED,
                        compareParamsChangedListener);
            }
            disposePalette();
            if (axisFont       != null && !axisFont.isDisposed())       axisFont.dispose();
            if (readoutFont    != null && !readoutFont.isDisposed())    readoutFont.dispose();
            if (chanButtonFont != null && !chanButtonFont.isDisposed()) chanButtonFont.dispose();
            if (phaseFillGray  != null && !phaseFillGray.isDisposed())  phaseFillGray.dispose();
            disposeTraceBuffer();
        });
    }

    private void allocateFonts() {
        FontData fd = getFont().getFontData()[0];
        axisFont    = new Font(getDisplay(), fd.getName(), Math.max(7, fd.getHeight() - 1), SWT.NORMAL);
        readoutFont = new Font(getDisplay(), fd.getName(), Math.max(7, fd.getHeight() - 1), SWT.NORMAL);
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
        Preferences prefs = Preferences.instance();
        int sig = prefs.getFreqRespSignalColor();
        setColor(ColorRole.BACKGROUND,  prefs.getFreqRespBackgroundColor());
        setColor(ColorRole.LEFT_TRACE,  sig);
        setColor(ColorRole.RIGHT_TRACE, sig);
        setColor(ColorRole.PHASE_TRACE, prefs.getFreqRespPhaseColor());
        setColor(ColorRole.RIAA_TRACE,  prefs.getFreqRespReferenceColor());
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
     *  calibration immediately retraces the existing measurement.
     *
     *  <p>When compare mode is on, also re-runs
     *  {@link #autoSetupCompare(Preferences)} so the median anchor and
     *  the min / max table refresh to match the new calibrated data —
     *  otherwise the trace would shift but the 0 dB centreline and
     *  the table would still reflect the old calibration. */
    /** Refreshes the compare-mode anchor + min/max table after the user
     *  changes a parameter that affects the smoothed-diff derivation
     *  (currently the smoothing-window pref).  No-op when compare mode
     *  or RIAA is off, or no result is loaded.  Always redraws so the
     *  newly-smoothed trace appears even when the anchor doesn't
     *  meaningfully move. */
    public void onCompareParamsChanged() {
        Preferences prefs = Preferences.instance();
        if (prefs.isFreqRespCompareMode()
                && prefs.isFreqRespShowRiaa()
                && hasAnyResult()) {
            recomputeCompareAnchor(prefs);
        }
        redraw();
    }

    public void onCalibrationChanged() {
        if (rawLeftResult  != null) this.leftResult  = applyCurrentCalibration(rawLeftResult);
        if (rawRightResult != null) this.rightResult = applyCurrentCalibration(rawRightResult);
        // Refresh the anchor + min/max table so they track the new
        // calibration / colour / smoothing state, but DO NOT touch the
        // freq / magnitude window — only autoSetupCompare is allowed
        // to re-zoom, and that runs solely from the explicit user
        // actions (auto-setup button, compare-mode toggle on, Show RIAA
        // toggled on while compare is already on).  Saving Preferences
        // must never re-zoom the view.
        Preferences prefs = Preferences.instance();
        if (prefs.isFreqRespCompareMode()
                && prefs.isFreqRespShowRiaa()
                && hasAnyResult()) {
            recomputeCompareAnchor(prefs);
        }
        redraw();
    }

    private FreqRespResult applyCurrentCalibration(FreqRespResult raw) {
        if (raw == null) return null;
        // Loaded files already carry the calibration division baked in
        // at save time — applying it again here would double-correct.
        if (raw.isCalibrationApplied()) return raw;
        Preferences prefs = Preferences.instance();
        FreqRespCalibrationStore store = FreqRespCalibrationStore.instance();
        List<FreqRespCalibrationStore.Entry> entries = store.getEntries();
        StereoFreqRespCalibration direct = store.getDirect();
        boolean wantCal   = prefs.isFreqRespApplyCalibration()
                            && (!entries.isEmpty() || direct != null);
        boolean wantNotch = prefs.isFreqRespNotchEnabled();
        if (!wantCal && !wantNotch) return raw;

        double[] freqs    = raw.getFreqs();
        double[] outMag   = raw.getMagLin().clone();
        double[] outPhase = raw.getPhaseRad().clone();
        boolean rChan = raw.getChannel() == Channel.R;

        if (wantCal) {
            // Chain every loaded calibration in order — linear-mag divide,
            // phase subtract — so the final displayed values reflect the
            // composition of all loaded files.
            for (FreqRespCalibrationStore.Entry entry : entries) {
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
            // — i.e. for the typical 48 kHz / 1.1 s capture (M ≈ 131 072
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
     *  when sweep params are missing — e.g. on a result loaded from disk. */
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
        // freqs is ascending — find last index strictly below fLo and
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
     *  (1 Hz → Nyquist horizontal, +20 → −150 dB vertical) and persists
     *  to {@link Preferences}.  Called from the maximize header button. */
    public void resetToDefaultView() {
        Preferences prefs = Preferences.instance();
        prefs.setFreqRespFreqMinHz(FREQ_MIN_FLOOR_HZ);
        prefs.setFreqRespFreqMaxHz(nyquistHz());
        prefs.setFreqRespMagTopDb(MAG_TOP_MAX_DB);
        prefs.setFreqRespMagBotDb(MAG_DEFAULT_BOT_DB);
        prefs.save();
        publishRangeChanged();
        redraw();
    }

    /** Auto-fits the view's frequency and magnitude windows to the
     *  visible result data.  Horizontal range goes to
     *  {@code FREQ_MIN_FLOOR_HZ → nyquistHz()} (i.e. the full band the
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
        Preferences prefs = Preferences.instance();
        if (prefs.isFreqRespCompareMode()
                && prefs.isFreqRespShowRiaa()
                && hasAnyResult()) {
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
        // Minimum vertical window of 2 dB total — when the natural fit
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
        prefs.setFreqRespMagTopDb(newTop);
        prefs.setFreqRespMagBotDb(newBot);
        prefs.save();
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

    // -------------------------------------------------------------------------
    // Paint
    // -------------------------------------------------------------------------

    private void onPaint(PaintEvent e) {
        GC gc = e.gc;
        Rectangle area = getClientArea();

        Preferences prefs = Preferences.instance();
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
                    .withUnit("dB");
            AxisSpec yRightSpec = phaseVisible
                    ? AxisSpec.linear(-180, 180, 8)
                            .withFormat(LabelFormat.PHASE_DEG)
                            .withUnit("φ")
                    : null;
            drawGrid(bgc, plot, xSpec, yLeftSpec, yRightSpec,
                     color(ColorRole.GRID), color(ColorRole.AXIS), color(ColorRole.TEXT), axisFont,
                     4, 2, null);
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
        if (prefs.isFreqRespCompareMode() && hasAnyResult() && prefs.isFreqRespShowRiaa()) {
            drawCompareBanner(gc, plot, prefs);
            drawCompareMeasurementTable(gc);
        } else {
            compareBanner.setVisible(false);
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
        h = (h ^ prefs.getFreqRespCompareSmoothWindow()) * P;
        // Colour prefs — included so a Preferences-dialog OK that
        // changes the signal / phase / reference / background colour
        // invalidates the static-layer paint cache and the trace
        // buffer rebuilds in the new palette on the next paint.
        h = (h ^ prefs.getFreqRespSignalColor())     * P;
        h = (h ^ prefs.getFreqRespPhaseColor())      * P;
        h = (h ^ prefs.getFreqRespReferenceColor())  * P;
        h = (h ^ prefs.getFreqRespBackgroundColor()) * P;
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
        gc.setForeground(color(ColorRole.RIAA_TRACE));
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

    /** Draws the (measured − reference) subtraction trace using the
     *  10-point moving-averaged diff so the rendered curve, the
     *  on-screen min/max table, and the crosshair Δ readout all show
     *  the same numbers.  Vertical anchor comes from
     *  {@link #compareAnchorOffset}, set by
     *  {@link #autoSetupCompare(Preferences)} to the median of the
     *  smoothed diff over 20 Hz–25 kHz so the curve's central value
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
        // Single shared compare state — the smoothed signal-point array
        // is already anchor-shifted (median over 20 Hz–25 kHz subtracted
        // inside getCompareDiff), so we draw smoothed[i] DIRECTLY with
        // no further subtraction.  Same array is read by the min/max
        // table and the crosshair Δ readout for guaranteed agreement.
        CompareDiff diff = getCompareDiff(src, reverse, iec);
        double[] smoothed = diff.smoothed;

        gc.setForeground(color(ColorRole.COMPARE_TRACE));
        gc.setLineWidth(2);
        int prevX = -1, prevY = -1;
        gc.setClipping(plot);
        try {
            for (int i = 0; i < freqs.length; i++) {
                double f = freqs[i];
                if (f < freqMin || f > freqMax) continue;
                double s = smoothed[i];
                if (Double.isNaN(s)) continue;
                int x = freqToX(f, plot, freqMin, freqMax, true);
                int y = dbToY(s, plot, magTop, magBot);
                if (prevX >= 0) gc.drawLine(prevX, prevY, x, y);
                prevX = x; prevY = y;
            }
        } finally {
            gc.setClipping((Rectangle) null);
        }
    }

    /** Returns the cached W-point moving-averaged copy of
     *  {@code (measDb − refDb)} across {@code src}'s full freq grid,
     *  where W is {@link Preferences#getFreqRespCompareSmoothWindow()}.
     *  The same smoothed array drives the drawn compare trace, the
     *  anchor median, and the min/max table — so all three agree.
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
     *  (one value per analyzer sweep point — never per screen pixel),
     *  then derives the anchor median and the relative min / max from
     *  the 20 Hz – 25 kHz subset of that same array.  Every consumer
     *  (drawing, crosshair Δ readout, anchor scalar, min/max table)
     *  pulls from the {@link CompareDiff} that this method caches —
     *  so all four read identical numbers by construction. */
    private CompareDiff getCompareDiff(FreqRespResult src, boolean reverse, boolean iec) {
        int W = Math.max(0, Math.min(100,
                Preferences.instance().getFreqRespCompareSmoothWindow()));
        if (compareDiffCache != null
                && compareDiffCacheSrc == src
                && compareDiffCacheReverse == reverse
                && compareDiffCacheIec == iec
                && compareDiffCacheWindow == W) {
            return compareDiffCache;
        }
        // 1. Raw (measDb − refDb) per signal point.  NaN for any point
        //    whose magnitude is non-positive / non-finite.
        double[] freqs  = src.getFreqs();
        double[] magLin = src.getMagLin();
        int n = freqs.length;
        double[] raw = new double[n];
        for (int i = 0; i < n; i++) {
            double measDb = FreqRespFormat.linToDb(magLin[i]);
            if (!Double.isFinite(measDb)) { raw[i] = Double.NaN; continue; }
            double refDb = RiaaCurve.evalDb(freqs[i], reverse, iec);
            raw[i] = measDb - refDb;
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
        //    advance monotonically (overall O(n)) — for each
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
        // 3. Anchor — value of the smoothed curve at 1 kHz (log-interp
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
        //    20 Hz – 25 kHz.  Real extrema — not symmetric, not
        //    percentile-clipped — so the table reads the actual
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
        compareDiffCache         = diff;
        compareDiffCacheSrc      = src;
        compareDiffCacheReverse  = reverse;
        compareDiffCacheIec      = iec;
        compareDiffCacheWindow   = W;
        return diff;
    }

    /** Compare-mode auto-setup.  Snaps the horizontal range to
     *  20 Hz – 25 kHz, computes a 10-point moving-averaged copy of the
     *  diff curve {@code (measDb − refDb)} over that band (smoothing
     *  used ONLY for this anchor calculation; the drawn trace stays
     *  unsmoothed), takes the median of the smoothed values as the
     *  vertical anchor so the curve's central value reads 0 dB, then
     *  fits the vertical window to the raw diff range with 10 % padding
     *  above and below.  Called from the pane when Compare is toggled
     *  on, or when Show RIAA is toggled on while Compare is already on. */
    public void autoSetupCompare(Preferences prefs) {
        if (!recomputeCompareAnchor(prefs)) return;

        // Vertical window — at least ±1 dB around 0 dB; expand with
        // a 1 dB padding above / below the actual extrema only when
        // the signal exceeds that minimum on the respective side.
        //   • flat trace          → window = [−1, +1]
        //   • max reaches +2.9 dB → top = +2.9 + 1 = +3.9
        //   • min stays at −0.5   → bot stays at −1 (within minimum)
        double newTop = (compareSmoothedMax > 1.0)  ? compareSmoothedMax + 1.0 : 1.0;
        double newBot = (compareSmoothedMin < -1.0) ? compareSmoothedMin - 1.0 : -1.0;
        newTop = Math.min(MAG_TOP_MAX_DB, newTop);
        newBot = Math.max(MAG_BOT_MIN_DB, newBot);

        prefs.setFreqRespFreqMinHz(20.0);
        prefs.setFreqRespFreqMaxHz(25000.0);
        prefs.setFreqRespMagTopDb(newTop);
        prefs.setFreqRespMagBotDb(newBot);
        prefs.save();
        MessageBus.instance().publish(Events.FREQRESP_RANGE_CHANGED);
        redraw();
    }

    /** Recomputes {@link #compareAnchorOffset} and
     *  {@link #compareSmoothedMin} / {@link #compareSmoothedMax} from the
     *  cached smoothed-diff array over 20 Hz–25 kHz.  Does NOT change the
     *  view's freq / mag window — used both by
     *  {@link #autoSetupCompare(Preferences)} (which then also sets the
     *  range) and by the smoothing-window pref change handler (which
     *  must refresh the table + anchor without disturbing the user's
     *  zoom).  Returns true on success, false when no data is available. */
    private boolean recomputeCompareAnchor(Preferences prefs) {
        FreqRespResult src = activeChannelResult(prefs);
        if (src == null) return false;
        // All three public scalars (anchor offset + min + max) are now
        // mirrors of the cached CompareDiff that getCompareDiff produces
        // — so the drawn trace, the crosshair Δ, and this method's
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
     *  corner, below the header buttons — two rows showing the min /
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
        int h = gc.textExtent("X").y + 2;
        // The widget right-aligns + blinks itself; plot.width excludes the phase axis,
        // so spanning the plot keeps it clear of those tick labels (room on the right).
        compareBanner.setFont(readoutFont);
        compareBanner.setText(text);
        compareBanner.setColors(color(ColorRole.BLINK_LIT), color(ColorRole.BLINK_DIM),
                                color(ColorRole.BACKGROUND));
        // drawSourcePath ran first this paint, so if the loaded-file banner is also up
        // (both top-right) drop the compare banner one line below it to avoid overlap.
        int y = sourceBanner.getVisible() ? plot.y + 4 + h + 2 : plot.y + 6;
        compareBanner.setBounds(plot.x, y, plot.width - 6, h);
        compareBanner.setVisible(true);
    }

    private void drawTraces(GC gc, Rectangle plot, double freqMin, double freqMax,
                            double magTop, double magBot) {
        Preferences prefs = Preferences.instance();
        gc.setLineWidth(2);

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
        //
        // Points just outside the visible range are routed to the
        // painter's left / right anchors so a polyline can still be
        // drawn (clipped to the plot rect) when only 0–1 sweep points
        // fall inside the visible window — that's the "very strong
        // zoom" case where the bucket pass alone would produce an
        // empty polyline and the trace would disappear.
        ColumnBucketPainter painter = new ColumnBucketPainter(plot);
        for (int i = 0; i < freqs.length; i++) {
            double f = freqs[i];
            int xAbs = freqToX(f, plot, freqMin, freqMax, true);
            int y    = dbToY(FreqRespFormat.linToDb(magLin[i]), plot, magTop, magBot);
            if (f < freqMin)        painter.setLeftAnchor(xAbs, y);
            else if (f > freqMax)   painter.setRightAnchor(xAbs, y);
            else                    painter.add(xAbs, y);
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
        if (sourceFilePath == null || sourceFilePath.isEmpty()) {
            sourceBanner.setVisible(false);
            return;
        }
        // Full path with a "Loaded:" prefix, in the same dark lit/dim blink pair as the
        // compare banner (scope file-mode style).  The widget right-aligns + left-
        // ellipsises to its width; plot.width excludes the phase axis, so spanning the
        // plot keeps it clear of those tick labels.
        gc.setFont(readoutFont);
        int h = gc.textExtent("X").y + 2;
        sourceBanner.setFont(readoutFont);
        sourceBanner.setText("Loaded: " + sourceFilePath);
        sourceBanner.setColors(color(ColorRole.BLINK_LIT), color(ColorRole.BLINK_DIM),
                               color(ColorRole.BACKGROUND));
        sourceBanner.setBounds(plot.x, plot.y + 4, plot.width - 6, h);
        sourceBanner.setVisible(true);
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

        gc.setForeground(color(ColorRole.CROSSHAIR));
        gc.setLineStyle(SWT.LINE_DOT);
        gc.drawLine(mouseX, plot.y, mouseX, plot.y + plot.height);
        gc.drawLine(plot.x, mouseY, plot.x + plot.width, mouseY);
        gc.setLineStyle(SWT.LINE_SOLID);

        StringBuilder sb = new StringBuilder();
        sb.append("f = ").append(formatFrequencyFine(cursorFreq));
        boolean compareActive = prefs.isFreqRespCompareMode()
                && prefs.isFreqRespShowRiaa()
                && hasAnyResult();
        if (compareActive) {
            // Compare mode draws the smoothed (measured − reference) curve,
            // so the readout interpolates the SAME smoothed array — anything
            // else would disagree with what the user sees on screen.
            FreqRespResult src = activeChannelResult(prefs);
            if (src != null) {
                CompareDiff diff = getCompareDiff(src,
                        prefs.isFreqRespReverseRiaa(), prefs.isFreqRespIecAmendment());
                // diff.smoothed is already anchor-shifted, so the
                // interpolated value IS the Δ readout — no further
                // subtraction.
                double s = interpFromArray(src.getFreqs(), diff.smoothed, cursorFreq);
                if (Double.isFinite(s)) {
                    sb.append('\n').append("Δ = ")
                            .append(FreqRespFormat.formatDbReadout(s));
                }
            }
        } else {
            if (prefs.isFreqRespLeftVisible() && leftResult != null) {
                double db = interpDb(leftResult, cursorFreq);
                sb.append('\n').append("L = ").append(FreqRespFormat.formatDbReadout(db));
            }
            if (prefs.isFreqRespRightVisible() && rightResult != null) {
                double db = interpDb(rightResult, cursorFreq);
                sb.append('\n').append("R = ").append(FreqRespFormat.formatDbReadout(db));
            }
        }
        if (phaseVisible && !compareActive) {
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
    /** Log-freq linear interpolation of {@code smoothed} at 1 kHz —
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
        Preferences prefs = Preferences.instance();
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
    /** Outer-most allowable frequency / magnitude window — zoom-out stops
     *  here so the user can't scroll into territory where no useful data
     *  ever lives.  Horizontal: 0 Hz to Nyquist (sampleRate / 2).
     *  Vertical: +20 dB to −300 dB. */
    private static final double MAG_TOP_MAX_DB  =   20.0;
    /** Upper bound when zooming / panning via the mouse wheel (Ctrl /
     *  Ctrl+Shift / plain wheel).  Wider than the maximize default so
     *  the user can scroll up into the "signal is louder than DAC FS"
     *  range — e.g. when a loaded calibration with sub-unity gain
     *  multiplies the displayed magnitude well past +20 dBFS.  Maximize
     *  still snaps to {@link #MAG_TOP_MAX_DB} (+20 dB) so the default
     *  view stays familiar. */
    private static final double MAG_TOP_ZOOM_MAX_DB = 120.0;
    private static final double MAG_BOT_MIN_DB  = -300.0;
    /** Vertical window the Maximize button snaps to.  Wider zoom-out is
     *  still available via the wheel, but +20 → −150 dB is the useful
     *  default for the FreqResp view — covers the full dynamic range of
     *  any analog device measurement without burning vertical pixels on
     *  the noise floor below −150 dB. */
    private static final double MAG_DEFAULT_BOT_DB = -150.0;
    /** Smallest log-axis floor for the frequency window.  Pure 0 Hz is
     *  unrepresentable on a log axis, so we clamp to a tiny positive
     *  value when the user zooms out fully. */
    private static final double FREQ_MIN_FLOOR_HZ = 1.0;

    /** Returns the maximal analyzed frequency — the active backend's
     *  Nyquist (sampleRate/2) scaled by the user's
     *  {@code freqRespNyquistFraction} pref (96–100 %).  Used as the
     *  right-most zoom-out limit, so the trace and scrollbar both stop
     *  at this fraction of Nyquist instead of going right up to Fs/2
     *  (where the deconvolution kernel's energy rolls off). */
    private double nyquistHz() {
        int sr = lastResultSampleRate > 0 ? lastResultSampleRate
                : Preferences.instance().current().getInputSampleRate();
        double frac = Preferences.instance().getFreqRespNyquistFraction();
        if (!Double.isFinite(frac) || frac <= 0.0) frac = 1.0;
        return Math.max(1.0, sr * 0.5 * frac);
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
        // Clamp the outer magnitude window to [MAG_BOT_MIN_DB, MAG_TOP_ZOOM_MAX_DB].
        // The zoom-mode upper limit is wider than the maximize default
        // so the user can scroll up past +20 dB to inspect signals that
        // a sub-unity calibration multiplies into very high values.
        newTop = Math.min(MAG_TOP_ZOOM_MAX_DB, newTop);
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
