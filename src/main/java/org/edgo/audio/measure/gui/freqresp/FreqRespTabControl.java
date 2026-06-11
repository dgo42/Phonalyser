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

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.edgo.audio.measure.bind.Property;
import org.edgo.audio.measure.common.FreqRespCorrectionStore;
import org.edgo.audio.measure.dsp.FreqRespCalHelper;
import org.edgo.audio.measure.dsp.FreqRespCalibration;
import org.edgo.audio.measure.dsp.StereoFreqRespCalibration;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.gui.bind.Bindings;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.Dialogs;
import org.edgo.audio.measure.gui.common.IconUtils;
import org.edgo.audio.measure.gui.common.SvgPaths;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.scope.ScreenshotDialog;
import org.edgo.audio.measure.gui.widgets.NumericStepField;
import org.edgo.audio.measure.gui.widgets.TileTabFolder;
import org.edgo.audio.measure.gui.widgets.UnitFamily;
import org.edgo.audio.measure.preferences.CalibrationEntry;
import org.edgo.audio.measure.preferences.FreqRespPreset;
import org.edgo.audio.measure.preferences.Preferences;

import lombok.extern.log4j.Log4j2;

/**
 * Self-contained Frequency Response toolbar control: a {@link TileTabFolder}
 * carrying the Settings, RIAA &amp; IEC, Presets, Utility, Calibration, Save-to
 * and Load-from tabs, with all of their preference bindings, the preset
 * machinery, the multi-row {@code .frc} calibration loader, and the
 * measurement save / load file flows.
 *
 * <p>The host {@link FreqRespPane} keeps the chart ({@link FreqRespView}), the
 * frequency / magnitude scrollbars, and the Wizard + Play action buttons (which
 * drive the measurement worker).  The {@link FreqRespView} is shared and passed
 * in at construction; the only cross-boundary calls are {@link #refreshRiaaEnable()}
 * (the pane invokes it when a fresh measurement lands so Compare becomes
 * available) and the collapse relayout the pane wires via
 * {@link #setCollapseRelayout(Runnable)}.  Everything else flows through the
 * shared view, {@link Preferences}, and the {@link MessageBus}.
 */
@Log4j2
public final class FreqRespTabControl extends Composite {

    /** Tab indices, kept as constants so the tile builder / refresh callers
     *  don't have to repeat magic numbers. */
    private static final int TAB_FREQRESP_SETTINGS    = 0;
    private static final int TAB_FREQRESP_RIAA        = 1;
    private static final int TAB_FREQRESP_PRESETS     = 2;
    private static final int TAB_FREQRESP_UTILITY     = 3;
    private static final int TAB_FREQRESP_CALIBRATION = 4;
    private static final int TAB_FREQRESP_SAVE        = 5;
    private static final int TAB_FREQRESP_LOAD        = 6;
    private static final int NUM_CUSTOM_TABS          = 7;

    /** Power-of-2 sweep-point presets the points field's wheel jumps along;
     *  the runtime "sample rate / 2" entry is merged in by
     *  {@link #sweepPointSeries()}.  Manual entry between or beyond the
     *  presets is allowed. */
    private static final double[] SWEEP_POINT_SERIES = {
            8192, 16384, 65536, 131072, 262144,
            524288, 1048576, 2097152, 4194304
    };
    private static final double SWEEP_POINTS_MIN = 8192;
    private static final double SWEEP_POINTS_MAX = 10_000_000;
    /** Sweep frequency floor — sub-hertz sweep limits are degenerate. */
    private static final double FREQ_MIN_HZ      = 1.0;
    /** Amplitude floor (Vrms) — the pre-rework field's clamp, kept. */
    private static final double AMP_MIN_VRMS     = 1e-4;
    /** Lead-in floor — the pre-rework field's clamp, kept. */
    private static final double LEAD_IN_MIN_SEC  = 0.05;
    private static final double TIME_MAX_SEC     = 1_000_000;
    // Display precision caps per the numeric-field spec.
    private static final int FREQ_MAX_DECIMALS = 9;
    private static final int AMP_MAX_DECIMALS  = 5;
    private static final int TIME_MAX_DECIMALS = 3;

    /** Deconvolution FFT length offered by the FFT-size combo.  Every
     *  entry is a power of 2; the larger the size, the longer the
     *  sweep, the finer the freq resolution. */
    private static final int[] FFT_SIZE_VALUES = {
            1 << 16, 1 << 17, 1 << 18, 1 << 19, 1 << 20,
            1 << 21, 1 << 22, 1 << 23, 1 << 24
    };
    private static final String[] FFT_SIZE_LABELS = {
            "64k", "128k", "256k", "512k", "1M", "2M", "4M", "8M", "16M"
    };

    private final FreqRespView view;

    /** Loaded {@code .frc} correction store, constructor-injected by
     *  {@link FreqRespPane} (IoC) and shared with the {@link FreqRespView}. */
    private final FreqRespCorrectionStore correctionStore;

    // ---- Tab-header tiles: the shared TileTabFolder owns the renderer,
    //      spacer images, tab-body collapse, hover tooltips and tile
    //      painting; this control only supplies tile content (freqRespTabTiles).
    private TileTabFolder toolbarTabs;

    /** Updated whenever the FFT-size combo or the lead-in field changes
     *  — caption is {@code "FFT size (D.Ds)"} where D.D is the derived
     *  sweep duration in seconds. */
    private Label fftSizeLabel;

    private Combo  freqRespPresetCombo;
    private Button freqRespPresetSaveBtn;
    private Button freqRespPresetLoadBtn;
    private Button freqRespPresetDeleteBtn;

    private Button riaaShowBtn;
    private Button riaaReverseBtn;
    private Button riaaIecBtn;
    private Button riaaCompareBtn;

    /** Container Composite for the dynamic calibration-row list. */
    private Composite calRowsContainer;
    /** Scrolled wrapper around {@link #calRowsContainer} so a long list
     *  of rows scrolls vertically instead of overflowing the tab. */
    private ScrolledComposite calRowsScroll;
    /** One entry per visible row, in display order. */
    private final List<CalRow> calRows = new ArrayList<>();
    /** Re-entrancy guard so the store's change event doesn't trigger a
     *  UI rebuild for changes the control initiated itself. */
    private boolean calMutationInFlight;

    /** Path field shared between the Save-to and Load-from tabs.  Kept
     *  as a field so the file-dialog handler can update the displayed
     *  text from anywhere. */
    private Text saveToPathField;
    private Text loadFromPathField;

    /** Bus subscriber kept as a field so dispose can unsubscribe the
     *  same instance (method references compare by identity). */
    private Consumer<Void> calibrationChangedListener;

    public FreqRespTabControl(Composite parent, FreqRespView view,
                              FreqRespCorrectionStore correctionStore) {
        super(parent, SWT.NONE);
        this.view = view;
        this.correctionStore = correctionStore;

        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 0; gl.marginHeight = 0;
        setLayout(gl);

        toolbarTabs = new TileTabFolder(this, SWT.NONE);
        toolbarTabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        // Each tile's chip text doubles as its own hover tooltip.
        toolbarTabs.setCustomTabs(NUM_CUSTOM_TABS, new TileTabFolder.TileSource() {
            @Override
            public List<TileTabFolder.Tile> tilesFor(int tabIndex) {
                return freqRespTabTiles(tabIndex);
            }
        });
        buildSettingsTab();
        buildRiaaTab();
        buildPresetsTab();
        buildUtilityTab();
        buildCalibrationTab();
        buildSaveToTab();
        buildLoadFromTab();
        Preferences prefs = Preferences.instance();
        int activeTab = Math.max(0, Math.min(toolbarTabs.getItemCount() - 1,
                prefs.getFreqRespActiveTabIndex()));
        if (toolbarTabs.getItemCount() > 0) toolbarTabs.setSelection(activeTab);
        toolbarTabs.addListener(SWT.Selection, e ->
                prefs.setFreqRespActiveTabIndex(toolbarTabs.getSelectionIndex()));
        // Capture labels, size the strip / spacer images and wire the paint,
        // hover and collapse listeners now that the tabs exist.
        toolbarTabs.init();

        // The calibration tab refreshes its row UI + tile when the store
        // changes out-of-band (e.g. the wizard's Apply step), without polling.
        calibrationChangedListener = ignored -> onCalibrationChanged();
        MessageBus.instance().subscribe(Events.FREQRESP_CALIBRATION_CHANGED, calibrationChangedListener);
        addDisposeListener(e ->
                MessageBus.instance().unsubscribe(Events.FREQRESP_CALIBRATION_CHANGED, calibrationChangedListener));

        // Push the initially-loaded active rows into the store last — the view
        // (built before this control) already holds the same store instance, so
        // the change events this fires find it ready.
        syncStoreFromRows();
    }

    // -------------------------------------------------------------------------
    // Pane delegation
    // -------------------------------------------------------------------------

    /** Runs {@code r} after each tab-body collapse / expand so the host pane
     *  can release / restore the toolbar row's height pin and re-flow the
     *  chart into the freed space. */
    public void setCollapseRelayout(Runnable r) {
        if (toolbarTabs != null) toolbarTabs.setCollapseRelayout(r);
    }

    /** True when the tab body is collapsed to just the strip. */
    public boolean isTabsCollapsed() {
        return toolbarTabs != null && toolbarTabs.isCollapsed();
    }

    /** Re-runs the RIAA enable cascade.  Public because the pane calls it when
     *  a fresh measurement completes (or a file loads) so Compare picks up the
     *  newly-available result. */
    public void refreshRiaaEnable() {
        // Cascade: Show RIAA enables Reverse and IEC (independently); their
        // selection state is preserved across toggling Show so the user can
        // hide and re-show the overlay without losing their settings.
        // Compare requires a measurement to be present.
        if (riaaShowBtn == null || riaaShowBtn.isDisposed()) return;
        boolean show = riaaShowBtn.getSelection();
        riaaReverseBtn.setEnabled(show);
        riaaIecBtn.setEnabled(show);
        riaaCompareBtn.setEnabled(show && view.hasAnyResult());
    }

    // =========================================================================
    // Tab-header tile rendering
    // =========================================================================

    /** Builds the live tile row for a tab from the current preferences, in
     *  visual order (left to right).  Each tile's short chip text doubles as
     *  its hover tooltip; the {@link TileTabFolder} measures and paints them.
     *  The Utility / Save / Load tabs intentionally carry no tiles (the file
     *  path lives in the tab body, not the header). */
    private List<TileTabFolder.Tile> freqRespTabTiles(int tabIndex) {
        Preferences prefs = Preferences.instance();
        List<TileTabFolder.Tile> tiles = new ArrayList<>();
        if (tabIndex == TAB_FREQRESP_SETTINGS) {
            tiles.add(tile(String.format(Locale.US, "%s–%s",
                    formatShortHz(prefs.getFreqRespStartHz()),
                    formatShortHz(prefs.getFreqRespStopHz()))));
            tiles.add(tile(String.format(Locale.US, "%.2fV", prefs.getFreqRespAmplitudeVrms())));
            tiles.add(tile(formatShortCount(prefs.getFreqRespSweepPoints()) + " pts"));
            // FFT size tile (replaces the old sweep-duration tile, which is
            // now derived from FFT size and shown in the settings-tab label).
            tiles.add(tile(formatFftSize(prefs.getFreqRespFftSize())));
        } else if (tabIndex == TAB_FREQRESP_RIAA) {
            if (prefs.isFreqRespShowRiaa()) {
                tiles.add(tile(prefs.isFreqRespReverseRiaa() ? "rec" : "play"));
                if (prefs.isFreqRespIecAmendment()) tiles.add(tile("+IEC"));
                if (prefs.isFreqRespCompareMode())  tiles.add(tile("comp"));
            }
        } else if (tabIndex == TAB_FREQRESP_CALIBRATION) {
            int n = correctionStore.getEntries().size();
            if (n == 1)      tiles.add(tile(I18n.t("calibration.tile.loaded")));
            else if (n  > 1) tiles.add(tile(I18n.t("calibration.tile.loadedN", n)));
        } else if (tabIndex == TAB_FREQRESP_PRESETS) {
            // Show the number of saved presets when there are any — a hint
            // that there's something to load.
            int n = prefs.getFreqRespPresets().size();
            if (n > 0) tiles.add(tile(n + " saved"));
        } else if (tabIndex == TAB_FREQRESP_UTILITY) {
            // No header tile — the Utility actions (screenshot, DAC/ADC cal)
            // leave no per-run state; branch kept so the constant is used.
        }
        return tiles;
    }

    /** A text tile whose chip text doubles as its hover tooltip. */
    private TileTabFolder.Tile tile(String text) {
        return TileTabFolder.Tile.text(text, text);
    }

    /** Short Hz format used in the Settings tile row: 1500 → "1.5k",
     *  20000 → "20k", 8 → "8". */
    private String formatShortHz(double hz) {
        if (hz >= 1000.0) {
            double k = hz / 1000.0;
            if (k >= 100.0) return String.format(Locale.US, "%.0fk", k);
            if (k >= 10.0)  return String.format(Locale.US, "%.0fk", k);
            return String.format(Locale.US, "%.1fk", k).replace(".0k", "k");
        }
        if (hz == Math.floor(hz)) return String.format(Locale.US, "%.0f", hz);
        return String.format(Locale.US, "%.1f", hz);
    }

    /** Short integer format used in the Settings tile row: 65536 → "65k",
     *  1048576 → "1M". */
    private String formatShortCount(int n) {
        if (n >= 1_000_000) return String.format(Locale.US, "%.0fM", n / 1_000_000.0);
        if (n >= 1000)      return String.format(Locale.US, "%.0fk", n / 1000.0);
        return Integer.toString(n);
    }

    /** Pretty-prints an FFT size as a power-of-2 abbreviation matching
     *  the {@link #FFT_SIZE_LABELS} combo entries: 65536 → "64k",
     *  524288 → "512k", 16777216 → "16M".  Falls back to
     *  {@link #formatShortCount} for non-power-of-2 values. */
    private String formatFftSize(int n) {
        for (int i = 0; i < FFT_SIZE_VALUES.length; i++) {
            if (FFT_SIZE_VALUES[i] == n) return FFT_SIZE_LABELS[i];
        }
        return formatShortCount(n);
    }

    // -------------------------------------------------------------------------
    // Settings tab — sweep parameters
    // -------------------------------------------------------------------------

    private void buildSettingsTab() {
        CTabItem item = new CTabItem(toolbarTabs, SWT.NONE);
        item.setText(I18n.t("freqResp.tab.settings"));
        Composite g = new Composite(toolbarTabs, SWT.NONE);
        item.setControl(g);
        GridLayout gl = new GridLayout(4, false);
        gl.marginWidth = 6; gl.marginHeight = 4;
        gl.horizontalSpacing = 6; gl.verticalSpacing = 4;
        g.setLayout(gl);
        Preferences prefs = Preferences.instance();

        // ---- Row 1: start freq + stop freq ----------------------------------
        addLabel(g, I18n.t("freqResp.settings.start"));
        NumericStepField startField = freqField(g);
        startField.setToolTipText(I18n.t("freqResp.settings.start.tooltip"));
        // Two-way bind; the floor clamp (≥ 1 Hz) and the tab-tile refresh ride
        // an onChange on the same pref, so a direct text entry below the floor
        // is corrected in the pref (which echoes back to the field).
        Bindings.stepField(startField, prefs.freqRespStartHzProperty());
        Bindings.onChange(toolbarTabs, prefs.freqRespStartHzProperty(), v -> {
            if (v < 1.0) prefs.setFreqRespStartHz(1.0);
            toolbarTabs.refreshTab(TAB_FREQRESP_SETTINGS);
        });

        addLabel(g, I18n.t("freqResp.settings.stop"));
        NumericStepField stopField = freqField(g);
        stopField.setToolTipText(I18n.t("freqResp.settings.stop.tooltip"));
        // Cross-field clamp: stop must stay at least start + 1.  A plain
        // two-way bind would lose it, so it is re-applied on the pref via
        // onChange (the re-set echoes to the field through the stepField bind).
        Bindings.stepField(stopField, prefs.freqRespStopHzProperty());
        Bindings.onChange(toolbarTabs, prefs.freqRespStopHzProperty(), v -> {
            double floor = prefs.getFreqRespStartHz() + 1.0;
            if (v < floor) prefs.setFreqRespStopHz(floor);
            toolbarTabs.refreshTab(TAB_FREQRESP_SETTINGS);
        });

        // ---- Row 2: amplitude (Vrms) + duration ----------------------------
        addLabel(g, I18n.t("freqResp.settings.amplitude"));
        NumericStepField ampField = new NumericStepField(g, UnitFamily.AMPLITUDE,
                AMP_MIN_VRMS, prefs.getDacFsVoltageRms(), AMP_MAX_DECIMALS, 110);
        ampField.setLayoutData(comboGd());
        ampField.setToolTipText(I18n.t("freqResp.settings.amplitude.tooltip"));
        // Two-way bind; the floor clamp (≥ 0.0001 V) and the tab-tile refresh
        // ride an onChange so a sub-floor text entry is corrected in the pref.
        Bindings.stepField(ampField, prefs.freqRespAmplitudeVrmsProperty());
        Bindings.onChange(toolbarTabs, prefs.freqRespAmplitudeVrmsProperty(), v -> {
            if (v < 0.0001) prefs.setFreqRespAmplitudeVrms(0.0001);
            toolbarTabs.refreshTab(TAB_FREQRESP_SETTINGS);
        });

        // FFT-size combo replaces the old sweep-duration field.  The
        // analyzer picks {@code nextPow2(leadIn + sweep + tail)} as its
        // deconvolution length, so by letting the user pick FFT size
        // directly we can solve back for the sweep duration that lands
        // exactly on that pow2 — no wasted bins, and the label shows
        // the user how long the actual sweep will run.
        fftSizeLabel = new Label(g, SWT.NONE);
        fftSizeLabel.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        Combo fftSizeCombo = new Combo(g, SWT.READ_ONLY);
        for (String s : FFT_SIZE_LABELS) fftSizeCombo.add(s);
        fftSizeCombo.setToolTipText(I18n.t("freqResp.settings.fftSize.tooltip"));
        fftSizeCombo.setLayoutData(comboGd());
        // Index-mapped combo (selection index → FFT_SIZE_VALUES[idx] sample
        // count), so it can't use the ordinal-based Bindings.combo — the
        // hand-wired helper mirrors that contract over the int value array.
        // The derived sweep duration + the label + the tab tile all ride an
        // onChange on the same pref, since they depend on the chosen size.
        bindFftSizeCombo(fftSizeCombo, prefs.freqRespFftSizeProperty());
        Bindings.onChange(toolbarTabs, prefs.freqRespFftSizeProperty(), n -> {
            prefs.setFreqRespDurationSec(deriveDurationSecFromFftSize(n));
            refreshFftSizeLabel();
            toolbarTabs.refreshTab(TAB_FREQRESP_SETTINGS);
        });
        // Initial sync: the YAML may carry a stale durationSec that no
        // longer matches the persisted fftSize (or vice versa).  Re-derive
        // and persist on every pane build so the analyzer + label agree.
        prefs.setFreqRespDurationSec(deriveDurationSecFromFftSize(prefs.getFreqRespFftSize()));
        refreshFftSizeLabel();

        // ---- Row 3: sweep points + lead-in ---------------------------------
        addLabel(g, I18n.t("freqResp.settings.points"));
        // List field replacing the old preset dropdown + "Manual…" prompt:
        // the wheel jumps along the power-of-2 presets (plus the runtime
        // "sample rate / 2" entry), free typing covers everything between.
        NumericStepField pointsField = new NumericStepField(g, UnitFamily.NONE,
                SWEEP_POINTS_MIN, SWEEP_POINTS_MAX, sweepPointSeries(), 0, 110);
        pointsField.setToolTipText(I18n.t("freqResp.settings.points.tooltip"));
        pointsField.setLayoutData(comboGd());
        Bindings.stepFieldInt(pointsField, prefs.freqRespSweepPointsProperty());
        Bindings.onChange(toolbarTabs, prefs.freqRespSweepPointsProperty(),
                v -> toolbarTabs.refreshTab(TAB_FREQRESP_SETTINGS));

        addLabel(g, I18n.t("freqResp.settings.leadIn"));
        NumericStepField leadInField = new NumericStepField(g, UnitFamily.TIME,
                LEAD_IN_MIN_SEC, TIME_MAX_SEC, TIME_MAX_DECIMALS, 90);
        leadInField.setLayoutData(comboGd());
        leadInField.setToolTipText(I18n.t("freqResp.settings.leadIn.tooltip"));
        // Two-way bind; the floor clamp (≥ 0.05 s) and the derived-value
        // coupling ride an onChange.  The derived sweep duration depends on
        // lead-in (lead-in eats into the same FFT window), so a lead-in change
        // ripples through to durationSec and the FFT-size label.
        Bindings.stepField(leadInField, prefs.freqRespLeadInSecProperty());
        Bindings.onChange(toolbarTabs, prefs.freqRespLeadInSecProperty(), v -> {
            if (v < 0.05) {
                prefs.setFreqRespLeadInSec(0.05);
                return;   // the re-set re-enters here with the clamped value
            }
            prefs.setFreqRespDurationSec(deriveDurationSecFromFftSize(prefs.getFreqRespFftSize()));
            refreshFftSizeLabel();
            toolbarTabs.refreshTab(TAB_FREQRESP_SETTINGS);
        });

        // ---- Row 4: dither + nyquist fraction ------------------------------
        addLabel(g, I18n.t("freqResp.settings.dither"));
        Combo ditherCombo = new Combo(g, SWT.READ_ONLY);
        for (int i = 0; i <= 31; i++) ditherCombo.add(i == 0 ? "Off" : String.valueOf(i));
        ditherCombo.setLayoutData(comboGd());
        ditherCombo.setToolTipText(I18n.t("freqResp.settings.dither.tooltip"));
        // Index-mapped combo where the selection index IS the bit count, so
        // it can't use the ordinal-based Bindings.combo (no enum) but needs no
        // value-array indirection either.  No side-effects beyond the pref
        // write, so no onChange.
        bindDitherCombo(ditherCombo, prefs.freqRespDitherBitsProperty());

        // Audio-format edits (Preferences OK, UI thread) move the Nyquist
        // ceiling of the sweep band edges and the sample-rate/2 entry of the
        // sweep-points series — re-pull both from the committed prefs.
        Consumer<Void> audioFormatListener = ignored -> {
            if (isDisposed()) return;
            double nyquist = Preferences.instance().current().getInputSampleRate() / 2.0;
            startField.setMax(nyquist);
            stopField.setMax(nyquist);
            pointsField.setSeries(sweepPointSeries());
        };
        MessageBus.instance().subscribe(Events.AUDIO_FORMAT_CHANGED, audioFormatListener);
        addDisposeListener(e ->
                MessageBus.instance().unsubscribe(Events.AUDIO_FORMAT_CHANGED, audioFormatListener));
    }

    /** Two-way binds the dither {@link Combo} (index == bit count, 0 = Off)
     *  to its {@code Integer} {@link Property}.  Mirrors {@link Bindings#combo}
     *  but for an index-as-value READ_ONLY combo rather than an enum, with the
     *  selection clamped to {@code [0, 31]} on seed and external change. */
    private void bindDitherCombo(Combo combo, Property<Integer> property) {
        combo.select(Math.max(0, Math.min(31, property.get())));
        combo.addListener(SWT.Selection, e -> {
            int i = combo.getSelectionIndex();
            if (i >= 0) {
                property.set(i);
            }
        });
        Consumer<Integer> onChange = v -> {
            int i = Math.max(0, Math.min(31, v));
            if (!combo.isDisposed() && combo.getSelectionIndex() != i) {
                combo.select(i);
            }
        };
        property.addListener(onChange);
        combo.addDisposeListener(e -> property.removeListener(onChange));
    }

    /** Two-way binds the FFT-size {@link Combo} (selection index →
     *  {@link #FFT_SIZE_VALUES}{@code [idx]} sample count) to its
     *  {@code Integer} {@link Property}.  Mirrors {@link Bindings#combo} over
     *  the int value array; falls back to index 0 (64k) when the pref value
     *  matches no entry, per {@link #selectFftSizeCombo}. */
    private void bindFftSizeCombo(Combo combo, Property<Integer> property) {
        selectFftSizeCombo(combo, property.get());
        combo.addListener(SWT.Selection, e -> {
            int idx = combo.getSelectionIndex();
            if (idx >= 0 && idx < FFT_SIZE_VALUES.length) {
                property.set(FFT_SIZE_VALUES[idx]);
            }
        });
        Consumer<Integer> onChange = v -> {
            if (!combo.isDisposed()) {
                selectFftSizeCombo(combo, v);
            }
        };
        property.addListener(onChange);
        combo.addDisposeListener(e -> property.removeListener(onChange));
    }

    private NumericStepField freqField(Composite parent) {
        NumericStepField f = new NumericStepField(parent, UnitFamily.FREQUENCY,
                FREQ_MIN_HZ, Preferences.instance().current().getInputSampleRate() / 2.0,
                FREQ_MAX_DECIMALS, 110);
        f.setLayoutData(comboGd());
        return f;
    }

    /** Sweep-point series for the points field: the power-of-2 presets plus
     *  the current "sample rate / 2" entry (the model sorts its copy). */
    private double[] sweepPointSeries() {
        double[] s = new double[SWEEP_POINT_SERIES.length + 1];
        System.arraycopy(SWEEP_POINT_SERIES, 0, s, 0, SWEEP_POINT_SERIES.length);
        s[SWEEP_POINT_SERIES.length] =
                Preferences.instance().current().getInputSampleRate() / 2.0;
        return s;
    }

    /** Selects the combo row whose FFT-size value matches the given
     *  number of samples.  Falls back to the smallest entry (64k) when
     *  the prefs value doesn't line up — should be impossible because
     *  the load-time snap rounds non-pow2 values to the next legal
     *  one, but defensive anyway. */
    private void selectFftSizeCombo(Combo combo, int currentFftSize) {
        for (int i = 0; i < FFT_SIZE_VALUES.length; i++) {
            if (FFT_SIZE_VALUES[i] == currentFftSize) { combo.select(i); return; }
        }
        combo.select(0);
    }

    /** Derives the sweep duration (in seconds) that pairs with the
     *  chosen FFT size so the analyzer's
     *  {@code nextPow2(leadIn + sweep + tail)} lands exactly on
     *  {@code fftSize}.  Clamped to ≥ 0.5 s so a small FFT size with a
     *  long lead-in doesn't produce a negative or unworkable sweep. */
    private double deriveDurationSecFromFftSize(int fftSize) {
        Preferences prefs = Preferences.instance();
        int sr = Math.max(1, prefs.current().getInputSampleRate());
        long leadIn = Math.round(prefs.getFreqRespLeadInSec() * sr);
        long tail   = sr / 2L;
        long sweep  = fftSize - leadIn - tail;
        if (sweep < (long) Math.round(0.5 * sr)) {
            sweep = (long) Math.round(0.5 * sr);
        }
        return sweep / (double) sr;
    }

    /** Updates the FFT-size label's caption to "FFT size (D.Ds)" where
     *  D.D is the current derived sweep duration. */
    private void refreshFftSizeLabel() {
        if (fftSizeLabel == null || fftSizeLabel.isDisposed()) return;
        double dur = Preferences.instance().getFreqRespDurationSec();
        fftSizeLabel.setText(I18n.t("freqResp.settings.fftSize")
                + " (" + String.format(Locale.ROOT, "%.1f", dur) + "s)");
        fftSizeLabel.requestLayout();
    }

    // -------------------------------------------------------------------------
    // RIAA & IEC tab — chained checkboxes + Compare
    // -------------------------------------------------------------------------

    private void buildRiaaTab() {
        CTabItem item = new CTabItem(toolbarTabs, SWT.NONE);
        item.setText(I18n.t("freqResp.tab.riaa"));
        Composite g = new Composite(toolbarTabs, SWT.NONE);
        item.setControl(g);
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 8; gl.marginHeight = 6;
        gl.verticalSpacing = 4;
        g.setLayout(gl);
        Preferences prefs = Preferences.instance();

        riaaShowBtn = checkbox(g, "freqResp.riaa.show",    "freqResp.riaa.show.tooltip",
                prefs.isFreqRespShowRiaa());
        riaaReverseBtn = checkbox(g, "freqResp.riaa.reverse", "freqResp.riaa.reverse.tooltip",
                prefs.isFreqRespReverseRiaa());
        riaaIecBtn = checkbox(g, "freqResp.riaa.iec", "freqResp.riaa.iec.tooltip",
                prefs.isFreqRespIecAmendment());
        riaaCompareBtn = checkbox(g, "freqResp.riaa.compare", "freqResp.riaa.compare.tooltip",
                prefs.isFreqRespCompareMode());

        // Show / Reverse / IEC are two-way bound to their prefs; the view
        // subscribes to each (redraw, plus Show's one-shot compare auto-zoom)
        // in its own constructor.  Only the pane-local effects stay here: the
        // enable cascade (Show gates Reverse / IEC / Compare; Reverse re-runs
        // it too) and the RIAA tab-tile refresh.  IEC gates nothing, so it
        // skips refreshRiaaEnable, exactly as the old listener did.
        Bindings.check(riaaShowBtn,    prefs.freqRespShowRiaaProperty());
        Bindings.check(riaaReverseBtn, prefs.freqRespReverseRiaaProperty());
        Bindings.check(riaaIecBtn,     prefs.freqRespIecAmendmentProperty());
        Bindings.onChange(toolbarTabs, prefs.freqRespShowRiaaProperty(), v -> {
            refreshRiaaEnable();
            toolbarTabs.refreshTab(TAB_FREQRESP_RIAA);
        });
        Bindings.onChange(toolbarTabs, prefs.freqRespReverseRiaaProperty(), v -> {
            refreshRiaaEnable();
            toolbarTabs.refreshTab(TAB_FREQRESP_RIAA);
        });
        Bindings.onChange(toolbarTabs, prefs.freqRespIecAmendmentProperty(),
                v -> toolbarTabs.refreshTab(TAB_FREQRESP_RIAA));

        // Compare is two-way bound; its pane-local effects (the no-measurement
        // veto, the one-shot auto-zoom on entry, the view redraw and the tab-
        // tile refresh) ride an onChange.  The view doesn't subscribe to the
        // compare-mode pref itself, so the redraw stays here.
        Bindings.check(riaaCompareBtn, prefs.freqRespCompareModeProperty());
        Bindings.onChange(toolbarTabs, prefs.freqRespCompareModeProperty(), enable -> {
            if (enable && !view.hasAnyResult()) {
                Dialogs.info(g.getShell(), I18n.t("freqResp.tab.riaa"),
                        I18n.t("freqResp.error.compare.noMeasurement"));
                // Veto: roll the pref back, which echoes through the bind to
                // uncheck the box.  The re-entry sees enable == false and just
                // redraws (a no-op repaint, compare was already off).
                prefs.setFreqRespCompareMode(false);
                return;
            }
            // One-shot auto-zoom on entry only — the user's subsequent pan /
            // zoom must stick instead of being clobbered on every redraw.
            if (enable) view.autoSetupCompare(prefs);
            view.redraw();
            toolbarTabs.refreshTab(TAB_FREQRESP_RIAA);
        });

        refreshRiaaEnable();
    }

    private Button checkbox(Composite parent, String labelKey, String tipKey, boolean initial) {
        Button b = new Button(parent, SWT.CHECK);
        b.setText(I18n.t(labelKey));
        b.setToolTipText(I18n.t(tipKey));
        b.setSelection(initial);
        b.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, true, false));
        return b;
    }

    // -------------------------------------------------------------------------
    // Presets tab — named snapshots of every FreqResp Settings + RIAA pref
    // -------------------------------------------------------------------------

    private void buildPresetsTab() {
        CTabItem item = new CTabItem(toolbarTabs, SWT.NONE);
        item.setText(I18n.t("freqResp.tab.presets"));
        Composite g = new Composite(toolbarTabs, SWT.NONE);
        item.setControl(g);
        GridLayout gl = new GridLayout(4, false);
        gl.marginWidth = 6; gl.marginHeight = 4; gl.horizontalSpacing = 6;
        g.setLayout(gl);
        Preferences prefs = Preferences.instance();

        freqRespPresetCombo = new Combo(g, SWT.DROP_DOWN);
        freqRespPresetCombo.setToolTipText(I18n.t("freqResp.presets.combo.tooltip"));
        GridData comboGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        comboGd.widthHint = 180;
        freqRespPresetCombo.setLayoutData(comboGd);
        for (String name : prefs.getFreqRespPresets().keySet()) freqRespPresetCombo.add(name);
        freqRespPresetCombo.addListener(SWT.Modify,    e -> refreshFreqRespPresetButtonState());
        freqRespPresetCombo.addListener(SWT.Selection, e -> refreshFreqRespPresetButtonState());

        freqRespPresetSaveBtn = new Button(g, SWT.PUSH);
        freqRespPresetSaveBtn.setText(I18n.t("freqResp.presets.save"));
        freqRespPresetSaveBtn.setToolTipText(I18n.t("freqResp.presets.save.tooltip"));
        freqRespPresetSaveBtn.addListener(SWT.Selection, e -> {
            String name = freqRespPresetCombo.getText().trim();
            if (name.isEmpty()) return;
            if (prefs.getFreqRespPresets().containsKey(name)
                    && !confirmFreqRespPresetOverwrite(name)) return;
            prefs.putFreqRespPreset(name, captureCurrentFreqRespPreset());
            if (freqRespPresetCombo.indexOf(name) < 0) freqRespPresetCombo.add(name);
            freqRespPresetCombo.setText(name);
            refreshFreqRespPresetButtonState();
            toolbarTabs.refreshTab(TAB_FREQRESP_PRESETS);
        });

        freqRespPresetLoadBtn = new Button(g, SWT.PUSH);
        freqRespPresetLoadBtn.setText(I18n.t("freqResp.presets.load"));
        freqRespPresetLoadBtn.setToolTipText(I18n.t("freqResp.presets.load.tooltip"));
        freqRespPresetLoadBtn.addListener(SWT.Selection, e -> {
            String name = freqRespPresetCombo.getText().trim();
            if (name.isEmpty()) return;
            FreqRespPreset p = prefs.getFreqRespPresets().get(name);
            if (p != null) {
                applyFreqRespPreset(p);
                refreshFreqRespPresetButtonState();
            }
        });

        freqRespPresetDeleteBtn = new Button(g, SWT.PUSH);
        freqRespPresetDeleteBtn.setText(I18n.t("freqResp.presets.delete"));
        freqRespPresetDeleteBtn.setToolTipText(I18n.t("freqResp.presets.delete.tooltip"));
        freqRespPresetDeleteBtn.addListener(SWT.Selection, e -> {
            String name = freqRespPresetCombo.getText().trim();
            if (name.isEmpty() || !prefs.getFreqRespPresets().containsKey(name)) return;
            if (!confirmFreqRespPresetDelete(name)) return;
            prefs.removeFreqRespPreset(name);
            int idx = freqRespPresetCombo.indexOf(name);
            if (idx >= 0) freqRespPresetCombo.remove(idx);
            freqRespPresetCombo.setText("");
            refreshFreqRespPresetButtonState();
            toolbarTabs.refreshTab(TAB_FREQRESP_PRESETS);
        });

        refreshFreqRespPresetButtonState();
        // Re-tick the Save-button enable state periodically so it grays
        // out as soon as the user makes the live settings match the
        // named preset's snapshot.  Matches FftPane's same idiom.
        Display display = g.getDisplay();
        Runnable[] tick = { null };
        tick[0] = () -> {
            if (freqRespPresetCombo == null || freqRespPresetCombo.isDisposed()) return;
            refreshFreqRespPresetButtonState();
            display.timerExec(500, tick[0]);
        };
        display.timerExec(500, tick[0]);
    }

    private FreqRespPreset captureCurrentFreqRespPreset() {
        Preferences prefs = Preferences.instance();
        FreqRespPreset p = new FreqRespPreset();
        p.setStartHz(prefs.getFreqRespStartHz());
        p.setStopHz(prefs.getFreqRespStopHz());
        p.setAmplitudeVrms(prefs.getFreqRespAmplitudeVrms());
        p.setSweepPoints(prefs.getFreqRespSweepPoints());
        p.setFftSize(prefs.getFreqRespFftSize());
        p.setLeadInSec(prefs.getFreqRespLeadInSec());
        p.setDitherBits(prefs.getFreqRespDitherBits());
        p.setShowRiaa(prefs.isFreqRespShowRiaa());
        p.setReverseRiaa(prefs.isFreqRespReverseRiaa());
        p.setIecAmendment(prefs.isFreqRespIecAmendment());
        p.setCompareMode(prefs.isFreqRespCompareMode());
        return p;
    }

    private void applyFreqRespPreset(FreqRespPreset p) {
        Preferences prefs = Preferences.instance();
        prefs.setFreqRespStartHz(p.getStartHz());
        prefs.setFreqRespStopHz(p.getStopHz());
        prefs.setFreqRespAmplitudeVrms(p.getAmplitudeVrms());
        prefs.setFreqRespSweepPoints(p.getSweepPoints());
        prefs.setFreqRespFftSize(p.getFftSize());
        prefs.setFreqRespLeadInSec(p.getLeadInSec());
        prefs.setFreqRespDitherBits(p.getDitherBits());
        prefs.setFreqRespShowRiaa(p.isShowRiaa());
        prefs.setFreqRespReverseRiaa(p.isReverseRiaa());
        prefs.setFreqRespIecAmendment(p.isIecAmendment());
        prefs.setFreqRespCompareMode(p.isCompareMode());
        // Re-derive the derived sweep duration from the loaded FFT size
        // + lead-in so the analyzer + Settings-tab label both reflect
        // the preset's values.
        prefs.setFreqRespDurationSec(deriveDurationSecFromFftSize(p.getFftSize()));
        prefs.save();
        // Every Settings / RIAA widget is two-way bound to these prefs, so the
        // setters above already pushed the preset into the live widgets.  Only
        // the derived label + tab tiles + the view's range-driven redraw still
        // need an explicit nudge.
        refreshFftSizeLabel();
        toolbarTabs.refreshTab(TAB_FREQRESP_SETTINGS);
        toolbarTabs.refreshTab(TAB_FREQRESP_RIAA);
        MessageBus.instance().publish(Events.FREQRESP_RANGE_CHANGED);
        view.redraw();
    }

    private void refreshFreqRespPresetButtonState() {
        if (freqRespPresetCombo == null || freqRespPresetCombo.isDisposed()) return;
        if (freqRespPresetSaveBtn == null || freqRespPresetSaveBtn.isDisposed()) return;
        if (freqRespPresetLoadBtn == null || freqRespPresetLoadBtn.isDisposed()) return;
        if (freqRespPresetDeleteBtn == null || freqRespPresetDeleteBtn.isDisposed()) return;
        String name = freqRespPresetCombo.getText().trim();
        if (name.isEmpty()) {
            freqRespPresetSaveBtn.setEnabled(false);
            freqRespPresetLoadBtn.setEnabled(false);
            freqRespPresetDeleteBtn.setEnabled(false);
            return;
        }
        FreqRespPreset existing = Preferences.instance().getFreqRespPresets().get(name);
        if (existing == null) {
            freqRespPresetSaveBtn.setEnabled(true);
            freqRespPresetLoadBtn.setEnabled(false);
            freqRespPresetDeleteBtn.setEnabled(false);
        } else {
            freqRespPresetSaveBtn.setEnabled(!existing.equals(captureCurrentFreqRespPreset()));
            freqRespPresetLoadBtn.setEnabled(true);
            freqRespPresetDeleteBtn.setEnabled(true);
        }
    }

    private boolean confirmFreqRespPresetOverwrite(String name) {
        return Dialogs.confirm(getShell(),
                I18n.t("freqResp.presets.overwrite.title"),
                I18n.t("freqResp.presets.overwrite.message").replace("{0}", name)) == SWT.YES;
    }

    private boolean confirmFreqRespPresetDelete(String name) {
        return Dialogs.confirm(getShell(),
                I18n.t("freqResp.presets.delete.title"),
                I18n.t("freqResp.presets.delete.message").replace("{0}", name)) == SWT.YES;
    }

    // -------------------------------------------------------------------------
    // Utility tab — Screenshot + DAC + ADC calibration buttons (stubbed)
    // -------------------------------------------------------------------------

    private void buildUtilityTab() {
        CTabItem item = new CTabItem(toolbarTabs, SWT.NONE);
        item.setText(I18n.t("freqResp.tab.utility"));
        Composite g = new Composite(toolbarTabs, SWT.NONE);
        item.setControl(g);
        GridLayout gl = new GridLayout(3, false);
        gl.marginWidth = 8; gl.marginHeight = 6;
        gl.horizontalSpacing = 6;
        g.setLayout(gl);

        IconUtils icons = IconUtils.instance();
        Image cameraIcon    = icons.renderAtHeight(g.getDisplay(), SvgPaths.CAMERA,    16, null);
        Image crosshairIcon = icons.renderAtHeight(g.getDisplay(), SvgPaths.CROSSHAIR, 16, null);

        // Icon-only buttons; the action label lives in the tooltip so the
        // toolbar stays compact, matching the scope's utility row.  All
        // three buttons are pinned at 30 px tall per UI spec so the row
        // reads as a uniform tool tray instead of three differently-sized
        // chips.
        Button screenshotBtn = new Button(g, SWT.PUSH);
        if (cameraIcon != null) screenshotBtn.setImage(cameraIcon);
        screenshotBtn.setToolTipText(I18n.t("freqResp.utility.screenshot.tooltip"));
        screenshotBtn.setLayoutData(utilityButtonGd());
        screenshotBtn.addListener(SWT.Selection, e -> openScreenshotDialog());

        // ADC / DAC calibration both use the crosshair icon — the
        // tooltips disambiguate which one.  Matches the scope / FFT
        // "crosshair = calibrate" convention.
        Button dacCalBtn = new Button(g, SWT.PUSH);
        if (crosshairIcon != null) dacCalBtn.setImage(crosshairIcon);
        dacCalBtn.setToolTipText(I18n.t("freqResp.utility.calibrateDac.tooltip"));
        dacCalBtn.setLayoutData(utilityButtonGd());
        dacCalBtn.addListener(SWT.Selection, e ->
                log.info("FreqResp DAC-cal clicked (dialog wired in Phase 6 follow-up)"));

        Button adcCalBtn = new Button(g, SWT.PUSH);
        if (crosshairIcon != null) adcCalBtn.setImage(crosshairIcon);
        adcCalBtn.setToolTipText(I18n.t("freqResp.utility.calibrateAdc.tooltip"));
        adcCalBtn.setLayoutData(utilityButtonGd());
        adcCalBtn.addListener(SWT.Selection, e ->
                log.info("FreqResp ADC-cal clicked (dialog wired in Phase 6 follow-up)"));
    }

    /** Layout data for the Utility-tab buttons: 30 px tall so the row
     *  reads as a uniform tool tray regardless of the icon's intrinsic
     *  size. */
    private GridData utilityButtonGd() {
        GridData gd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        gd.heightHint = 30;
        gd.widthHint  = 30;
        return gd;
    }

    /** Opens the shared {@link ScreenshotDialog} with a renderer that
     *  captures the current view at the requested size via
     *  {@code Control.print(GC)}.  Saves / clipboard-copies the
     *  freq-response chart as the user sees it. */
    private void openScreenshotDialog() {
        ScreenshotDialog dlg = new ScreenshotDialog(
                getShell(),
                view.getSize().x, view.getSize().y,
                view.getSize().x, view.getSize().y,
                Preferences.instance().getFreqRespSaveFolder(),
                (display, w, h) -> {
                    Image img = new Image(display, w, h);
                    GC gc = new GC(img);
                    try { view.print(gc); }
                    finally { gc.dispose(); }
                    return img;
                },
                null,
                folder -> {
                    Preferences prefs = Preferences.instance();
                    prefs.setFreqRespSaveFolder(folder);
                    prefs.save();
                });
        dlg.open();
    }

    // -------------------------------------------------------------------------
    // Calibration tab — multi-row load + clear + add + remove
    // -------------------------------------------------------------------------

    /** Per-row widget bundle + the loaded calibration (when any). */
    private static final class CalRow {
        Composite                composite;
        Text                     pathField;
        /** "Active" toggle — two-way bound to {@code entry.active()}; the
         *  calibration is only pushed into the store when this is checked AND
         *  a file is loaded. */
        Button                   activeCheck;
        StereoFreqRespCalibration calibration;
        /** Source of truth for path + Active; lives in
         *  {@code Preferences.getFreqRespCalibrations()}.  With-noise is
         *  unused by this pane. */
        CalibrationEntry         entry;

        private CalRow() {
        }
    }

    private void buildCalibrationTab() {
        CTabItem item = new CTabItem(toolbarTabs, SWT.NONE);
        item.setText(I18n.t("freqResp.tab.calibration"));

        // ScrolledComposite wraps the rows so the tab can grow vertically
        // beyond the available height — a long list of calibrations
        // scrolls instead of overflowing.
        calRowsScroll = new ScrolledComposite(toolbarTabs, SWT.V_SCROLL);
        calRowsScroll.setExpandHorizontal(true);
        calRowsScroll.setExpandVertical(true);
        item.setControl(calRowsScroll);

        calRowsContainer = new Composite(calRowsScroll, SWT.NONE);
        calRowsScroll.setContent(calRowsContainer);
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 8; gl.marginHeight = 6;
        gl.verticalSpacing = 4;
        calRowsContainer.setLayout(gl);

        // Build the rows from prefs (always at least row 0) and load any
        // referenced .frc files into each row.  The store itself is populated
        // from these rows by the syncStoreFromRows() call at the end of the
        // constructor, after every tab is built.
        Preferences prefs = Preferences.instance();
        List<CalibrationEntry> cals = prefs.getFreqRespCalibrations();
        if (cals.isEmpty()) {
            prefs.addFreqRespCalibration(new CalibrationEntry());  // row 0 always present
        }
        for (CalibrationEntry entry : cals) {
            CalRow r = createRowUi(entry);
            String p = entry.getPath();
            if (p != null && !p.isEmpty()) loadFileIntoRow(r, p, false);
            updateCalRowEnable(r);
        }
    }

    /** Builds and appends a fresh row to the calibration tab.  The row
     *  starts empty (no path, no calibration).  Every row has 6 grid
     *  cells (path, active, load, clear, add, remove) — for row 0 the
     *  remove button is invisible so its grid cell stays reserved,
     *  keeping the load/clear/add columns vertically aligned across
     *  all rows. */
    private CalRow createRowUi(CalibrationEntry entry) {
        boolean isRow0 = calRows.isEmpty();

        Composite row = new Composite(calRowsContainer, SWT.NONE);
        row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout rl = new GridLayout(6, false);
        rl.marginWidth = 0; rl.marginHeight = 0;
        rl.horizontalSpacing = 6;
        row.setLayout(rl);

        Text pathField = new Text(row, SWT.BORDER | SWT.READ_ONLY);
        GridData pgd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        pgd.widthHint = 320;
        pathField.setLayoutData(pgd);
        pathField.setText(I18n.t("freqResp.calibration.path.none"));
        pathField.setToolTipText(I18n.t("freqResp.calibration.path.tooltip"));

        Button activeCheck = new Button(row, SWT.CHECK);
        activeCheck.setText(I18n.t("fft.calibration.active"));
        activeCheck.setToolTipText(I18n.t("fft.calibration.active.tooltip"));

        Image folderIcon = IconUtils.instance().renderAtHeight(
                row.getDisplay(), SvgPaths.FOLDER_OPEN, 16, null);
        Button loadBtn = new Button(row, SWT.PUSH);
        if (folderIcon != null) loadBtn.setImage(folderIcon);
        loadBtn.setToolTipText(I18n.t("freqResp.calibration.load.tooltip"));

        Image xmark = IconUtils.instance().renderAtHeight(
                row.getDisplay(), SvgPaths.RECTANGLE_XMARK, 16,
                new RGB(0xC8, 0x28, 0x28));
        Button clearBtn = new Button(row, SWT.PUSH);
        if (xmark != null) clearBtn.setImage(xmark);
        clearBtn.setToolTipText(I18n.t("freqResp.calibration.clear.tooltip"));

        Image plus = IconUtils.instance().renderAtHeight(
                row.getDisplay(), SvgPaths.PLUS, 16,
                new RGB(0x28, 0x90, 0x28));
        Button addBtn = new Button(row, SWT.PUSH);
        if (plus != null) addBtn.setImage(plus);
        addBtn.setToolTipText(I18n.t("freqResp.calibration.add.tooltip"));

        // Minus icon: render to a square 16×16 canvas so the SVG's thin
        // horizontal bar sits centered with transparent padding above
        // and below — otherwise renderAtHeight derives width from the
        // path bounding box (which is wide-and-thin) and we'd get a
        // stretched red rectangle.
        Image minus = IconUtils.instance().render(
                row.getDisplay(), SvgPaths.MINUS, 16, 16,
                new RGB(0xC8, 0x28, 0x28));
        Button removeBtn = new Button(row, SWT.PUSH);
        if (minus != null) removeBtn.setImage(minus);
        removeBtn.setToolTipText(I18n.t("freqResp.calibration.remove.tooltip"));
        if (isRow0) {
            // Invisible-but-present placeholder so the column widths of
            // load/clear/add stay identical across all rows.
            removeBtn.setVisible(false);
        }

        CalRow r = new CalRow();
        r.composite   = row;
        r.pathField   = pathField;
        r.activeCheck = activeCheck;
        r.entry       = entry;
        calRows.add(r);
        updateCalRowEnable(r);

        Bindings.check(activeCheck, entry.active());
        Bindings.onChange(activeCheck, entry.active(), v -> {
            updateCalRowEnable(r);
            syncStoreFromRows();
        });

        loadBtn.addListener(SWT.Selection, e -> userLoadInRow(r));
        clearBtn.addListener(SWT.Selection, e -> userClearRow(r));
        addBtn.addListener(SWT.Selection, e -> userAddRow());
        if (!isRow0) {
            removeBtn.addListener(SWT.Selection, e -> userRemoveRow(r));
        }

        relayoutCalRows();
        return r;
    }

    /** Keeps the row's "Active" checkbox enabled state in sync with
     *  the file-loaded state — disabled until a calibration is loaded
     *  into the row. */
    private void updateCalRowEnable(CalRow r) {
        if (r.activeCheck == null || r.activeCheck.isDisposed()) return;
        boolean fileLoaded = r.entry.getPath() != null && r.calibration != null;
        r.activeCheck.setEnabled(fileLoaded);
    }

    /** Re-runs the rows container's layout AND tells the surrounding
     *  ScrolledComposite to recompute its scroll extent so a newly
     *  added row participates in the V_SCROLL bar. */
    private void relayoutCalRows() {
        if (calRowsContainer != null && !calRowsContainer.isDisposed()) {
            calRowsContainer.layout(true, true);
        }
        if (calRowsScroll != null && !calRowsScroll.isDisposed() && calRowsContainer != null) {
            calRowsScroll.setMinSize(calRowsContainer.computeSize(SWT.DEFAULT, SWT.DEFAULT));
        }
    }

    private void userLoadInRow(CalRow r) {
        Preferences prefs = Preferences.instance();
        FileDialog fd = new FileDialog(getShell(), SWT.OPEN);
        fd.setText(I18n.t("freqResp.calibration.dialog"));
        fd.setFilterExtensions(new String[]{ "*.frc", "*.csv", "*.*" });
        String memFolder = prefs.getFreqRespLoadFolder();
        if (memFolder != null) fd.setFilterPath(memFolder);
        String picked = fd.open();
        if (picked == null) return;
        if (!loadFileIntoRow(r, picked, true)) return;
        prefs.setFreqRespLoadFolder(new File(picked).getParent());
        syncStoreFromRows();
        prefs.save();
    }

    private void userClearRow(CalRow r) {
        r.calibration = null;
        r.entry.setPath(null);
        r.pathField.setText(I18n.t("freqResp.calibration.path.none"));
        r.pathField.setToolTipText(null);
        // Clearing the file disables Active in the UI but we keep the
        // entry's Active flag so re-loading a file re-engages the row
        // without the user having to re-tick the box — matches the FFT pane.
        updateCalRowEnable(r);
        syncStoreFromRows();
        Preferences.instance().save();
    }

    private void userAddRow() {
        CalibrationEntry entry = new CalibrationEntry();
        Preferences.instance().addFreqRespCalibration(entry);
        createRowUi(entry);
    }

    private void userRemoveRow(CalRow r) {
        if (calRows.size() <= 1) return;
        int idx = calRows.indexOf(r);
        if (idx <= 0) return; // never remove row 0
        calRows.remove(idx);
        Preferences.instance().removeFreqRespCalibration(r.entry);
        r.composite.dispose();
        relayoutCalRows();
        syncStoreFromRows();
    }

    /** Reads a calibration file from disk and writes the result into
     *  {@code r}.  When {@code showErrors} is true, file-load failures
     *  pop a modal dialog; otherwise they're logged silently (used at
     *  startup so a missing file doesn't block the whole pane). */
    private boolean loadFileIntoRow(CalRow r, String picked, boolean showErrors) {
        try {
            StereoFreqRespCalibration cal = FreqRespCalHelper.loadCsv(picked);
            r.calibration = cal;
            r.entry.setPath(picked);
            r.pathField.setText(picked);
            r.pathField.setToolTipText(picked);
            updateCalRowEnable(r);
            return true;
        } catch (Exception ex) {
            log.warn("FreqResp calibration load failed: {}", picked, ex);
            if (showErrors) {
                Dialogs.error(getShell(),
                        I18n.t("freqResp.calibration.dialog"),
                        I18n.t("freqResp.error.calibration.load").replace("{0}",
                                ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
            }
            return false;
        }
    }

    /** Pushes the current row state into the {@link FreqRespCorrectionStore}.
     *  Empty rows are skipped so the store holds only entries the view
     *  should divide by. */
    private void syncStoreFromRows() {
        calMutationInFlight = true;
        try {
            correctionStore.clearAll();
            for (CalRow r : calRows) {
                // Only push rows the user has explicitly activated; an
                // unticked row is a "loaded but parked" calibration the
                // user can re-engage with one click without re-browsing.
                if (r.calibration != null && r.entry.getPath() != null && r.entry.active().get()) {
                    correctionStore.addEntry(r.calibration, r.entry.getPath());
                }
            }
        } finally {
            calMutationInFlight = false;
        }
    }

    /** Rebuilds the row UI from the store.  Used when an external source
     *  (e.g. the wizard's Apply step) replaces the calibration entries
     *  out-of-band — drops any user-added empty rows, leaving exactly
     *  one row per loaded entry (with row 0 always present). */
    private void rebuildRowsFromStore() {
        if (calRowsContainer == null || calRowsContainer.isDisposed()) return;
        List<FreqRespCorrectionStore.Entry> entries = correctionStore.getEntries();
        // No-op when the store's loaded entries already line up with
        // the loaded rows in the UI (in the same order).  Skipping
        // here preserves user-added empty rows when the bus event is
        // unrelated to the entries list — e.g. the wizard's setDirect
        // fires the same event but doesn't touch entries.
        if (loadedRowsMatch(entries)) return;
        for (CalRow r : calRows) {
            if (r.composite != null && !r.composite.isDisposed()) r.composite.dispose();
        }
        calRows.clear();
        Preferences prefs = Preferences.instance();
        prefs.getFreqRespCalibrations().clear();
        int rowCount = Math.max(1, entries.size());
        for (int i = 0; i < rowCount; i++) {
            CalibrationEntry entry = (i < entries.size())
                    ? new CalibrationEntry(entries.get(i).getPath(), true, false)
                    : new CalibrationEntry();
            prefs.addFreqRespCalibration(entry);
            CalRow r = createRowUi(entry);
            if (i < entries.size()) {
                FreqRespCorrectionStore.Entry e = entries.get(i);
                r.calibration = e.getCalibration();
                r.pathField.setText(e.getPath());
                r.pathField.setToolTipText(e.getPath());
                updateCalRowEnable(r);
            }
        }
        relayoutCalRows();
        prefs.save();
    }

    /** True when the loaded subset of {@link #calRows} (skipping empty
     *  rows) is identical, in order, to {@code entries}. */
    private boolean loadedRowsMatch(List<FreqRespCorrectionStore.Entry> entries) {
        int j = 0;
        for (CalRow r : calRows) {
            if (r.calibration == null) continue;
            if (j >= entries.size()) return false;
            FreqRespCorrectionStore.Entry e = entries.get(j);
            if (r.calibration != e.getCalibration()) return false;
            if (r.entry.getPath() == null || !r.entry.getPath().equals(e.getPath())) return false;
            j++;
        }
        return j == entries.size();
    }

    // -------------------------------------------------------------------------
    // Save-to tab — write the current measurement to a CSV
    // -------------------------------------------------------------------------

    private void buildSaveToTab() {
        CTabItem item = new CTabItem(toolbarTabs, SWT.NONE);
        item.setText(I18n.t("freqResp.tab.saveTo"));
        Composite g = new Composite(toolbarTabs, SWT.NONE);
        item.setControl(g);
        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 6; gl.marginHeight = 4; gl.horizontalSpacing = 6;
        g.setLayout(gl);
        Preferences prefs = Preferences.instance();

        // Layout: [pathField (read-only display of last save)] [save].
        // The save button now ALWAYS opens the file-picker before
        // writing — the previous separate "browse" button was redundant
        // since browse-then-save was the only useful sequence.
        saveToPathField = new Text(g, SWT.BORDER | SWT.READ_ONLY);
        String savedPath = prefs.getFreqRespSavePath();
        saveToPathField.setText(savedPath == null ? "" : savedPath);
        saveToPathField.setToolTipText(savedPath != null && !savedPath.isEmpty() ? savedPath : I18n.t("freqResp.saveTo.path.tooltip"));
        saveToPathField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Image floppyIcon = IconUtils.instance().renderAtHeight(
                g.getDisplay(), SvgPaths.FLOPPY_DISK, 16, null);
        Button saveBtn = new Button(g, SWT.PUSH);
        if (floppyIcon != null) saveBtn.setImage(floppyIcon);
        saveBtn.setToolTipText(I18n.t("freqResp.saveTo.tooltip"));
        saveBtn.addListener(SWT.Selection, e -> openSaveDialog());
    }

    private void openSaveDialog() {
        FreqRespResult left  = view.getLeftResultOrNull();
        FreqRespResult right = view.getRightResultOrNull();
        if (left == null && right == null) {
            Dialogs.info(getShell(),
                    I18n.t("freqResp.tab.saveTo"),
                    I18n.t("freqResp.saveTo.error.noResult"));
            return;
        }
        Preferences prefs = Preferences.instance();
        // Always open the Save-as dialog so the user explicitly picks
        // (or confirms) the destination on every click — the
        // dedicated "browse" button was removed because pick-and-save
        // is the only useful sequence here.  The picker is pre-filled
        // with the last saved path so the typical "save to the same
        // file again" case is just two clicks.
        FileDialog fd = new FileDialog(getShell(), SWT.SAVE);
        fd.setText(I18n.t("freqResp.saveTo.dialog"));
        fd.setFilterExtensions(new String[]{ "*.frc", "*.csv" });
        fd.setOverwrite(true);
        String memFolder = prefs.getFreqRespSaveFolder();
        if (memFolder != null) fd.setFilterPath(memFolder);
        String lastPath = (saveToPathField != null && !saveToPathField.isDisposed())
                ? saveToPathField.getText().trim() : "";
        if (!lastPath.isEmpty()) {
            fd.setFileName(new File(lastPath).getName());
        } else {
            fd.setFileName("freqresp_" + LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".frc");
        }
        String picked = fd.open();
        if (picked == null) return;
        if (saveToPathField != null && !saveToPathField.isDisposed()) {
            saveToPathField.setText(picked);
            saveToPathField.setToolTipText(picked);
        }
        prefs.setFreqRespSavePath(picked);
        toolbarTabs.refreshTab(TAB_FREQRESP_SAVE);
        try {
            // Both channels are always written; the new file format is
            // strict stereo (5 columns: f, mag_L_dB, mag_R_dB, phase_L_deg,
            // phase_R_deg).  When the user has hidden one channel in the
            // view, we duplicate the visible channel into the missing
            // slot so the file stays self-consistent on round-trip.
            FreqRespResult primary = left != null ? left : right;
            FreqRespResult other   = left != null ? right : left;
            if (other == null) other = primary;
            FreqRespCalibration calL = new FreqRespCalibration(
                    primary.getFreqs(), primary.getMagLin(), primary.getPhaseRad());
            FreqRespCalibration calR = new FreqRespCalibration(
                    other.getFreqs(),   other.getMagLin(),   other.getPhaseRad());
            FreqRespSweepParams p = primary.getSweepParams();
            FreqRespCalHelper.saveCsv(
                    new StereoFreqRespCalibration(calL, calR),
                    picked, primary.getSampleRate(),
                    p.getStartHz(), p.getStopHz(), p.getSweepPoints(),
                    p.getAmplitudeVrms());

            prefs.setFreqRespSaveFolder(new File(picked).getParent());
            prefs.save();
            log.info("FreqResp measurement saved to {}", picked);
        } catch (Exception ex) {
            log.warn("FreqResp save failed", ex);
            Dialogs.error(getShell(),
                    I18n.t("freqResp.saveTo.dialog"),
                    I18n.t("freqResp.error.measurement.save").replace("{0}",
                            ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
        }
    }

    // -------------------------------------------------------------------------
    // Load-from tab — display a saved measurement on the view
    // -------------------------------------------------------------------------

    private void buildLoadFromTab() {
        CTabItem item = new CTabItem(toolbarTabs, SWT.NONE);
        item.setText(I18n.t("freqResp.tab.loadFrom"));
        Composite g = new Composite(toolbarTabs, SWT.NONE);
        item.setControl(g);
        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 6; gl.marginHeight = 4; gl.horizontalSpacing = 6;
        g.setLayout(gl);
        Preferences prefs = Preferences.instance();

        // Layout: [pathField (read-only display of last load)] [load].
        // The load button now ALWAYS opens the file-picker before
        // reading — the previous separate "browse" button was
        // redundant since browse-then-load was the only useful
        // sequence.
        loadFromPathField = new Text(g, SWT.BORDER | SWT.READ_ONLY);
        String savedPath = prefs.getFreqRespLoadPath();
        loadFromPathField.setText(savedPath == null ? "" : savedPath);
        loadFromPathField.setToolTipText(savedPath != null && !savedPath.isEmpty() ? savedPath : I18n.t("freqResp.loadFrom.path.tooltip"));
        loadFromPathField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Image folderIcon = IconUtils.instance().renderAtHeight(
                g.getDisplay(), SvgPaths.FOLDER_OPEN, 16, null);
        Button loadBtn = new Button(g, SWT.PUSH);
        if (folderIcon != null) loadBtn.setImage(folderIcon);
        loadBtn.setToolTipText(I18n.t("freqResp.loadFrom.tooltip"));
        loadBtn.addListener(SWT.Selection, e -> openLoadDialog());
    }

    /** Opens an Open file dialog, then loads the chosen file into the
     *  view.  Always prompts — the dedicated "browse" button was
     *  removed because browse-then-load was the only useful sequence.
     *  The picker is pre-filled with the last loaded path so re-load
     *  is one extra click. */
    private void openLoadDialog() {
        Preferences prefs = Preferences.instance();
        FileDialog fd = new FileDialog(getShell(), SWT.OPEN);
        fd.setText(I18n.t("freqResp.loadFrom.dialog"));
        fd.setFilterExtensions(new String[]{ "*.frc", "*.csv", "*.*" });
        if (prefs.getFreqRespLoadFolder() != null) fd.setFilterPath(prefs.getFreqRespLoadFolder());
        String lastPath = (loadFromPathField != null && !loadFromPathField.isDisposed())
                ? loadFromPathField.getText().trim() : "";
        if (!lastPath.isEmpty()) fd.setFileName(new File(lastPath).getName());
        String picked = fd.open();
        if (picked == null) return;
        if (loadFromPathField != null && !loadFromPathField.isDisposed()) {
            loadFromPathField.setText(picked);
            loadFromPathField.setToolTipText(picked);
        }
        prefs.setFreqRespLoadPath(picked);
        toolbarTabs.refreshTab(TAB_FREQRESP_LOAD);
        try {
            StereoFreqRespCalibration st = FreqRespCalHelper.loadCsv(picked);
            FreqRespCalibration cL = st.left();
            FreqRespCalibration cR = st.right();
            FreqRespSweepParams params = new FreqRespSweepParams(
                    cL.freqs[0], cL.freqs[cL.freqs.length - 1],
                    cL.freqs.length, 0.0, 0.0, 0.0, 0);
            int sr = prefs.current().getInputSampleRate();
            view.setLeftResult(new FreqRespResult(
                    Channel.L, sr, cL.freqs, cL.magLin, cL.phaseRad,
                    params, picked, true));
            view.setRightResult(new FreqRespResult(
                    Channel.R, sr, cR.freqs, cR.magLin, cR.phaseRad,
                    params, picked, true));
            view.setSourceFilePath(picked);
            // A loaded measurement counts as "has result" — refresh the RIAA
            // enable cascade so Compare becomes available (when Show is on).
            refreshRiaaEnable();
            // Auto-fit the freq / magnitude window to the freshly-loaded curve,
            // as if the header auto-setup button were pressed.
            view.autoSetupMagnitudeRange();
            prefs.setFreqRespLoadFolder(new File(picked).getParent());
            prefs.save();
        } catch (Exception ex) {
            log.warn("FreqResp load failed", ex);
            Dialogs.error(getShell(),
                    I18n.t("freqResp.loadFrom.dialog"),
                    I18n.t("freqResp.error.measurement.load").replace("{0}",
                            ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
        }
    }

    // -------------------------------------------------------------------------
    // Bus subscriber + helpers
    // -------------------------------------------------------------------------

    private void onCalibrationChanged() {
        // Skip the rebuild when this control initiated the store mutation
        // itself — calRows already matches what we just wrote.  Other
        // sources (e.g. wizard Apply, wizard Cancel-restore) take the
        // rebuildRowsFromStore path so the UI catches up.
        if (!calMutationInFlight) {
            rebuildRowsFromStore();
        }
        toolbarTabs.refreshTab(TAB_FREQRESP_CALIBRATION);
    }

    private GridData comboGd() {
        GridData gd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        gd.widthHint = 120;
        return gd;
    }

    private void addLabel(Composite parent, String text) {
        Label l = new Label(parent, SWT.NONE);
        l.setText(text);
        l.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
    }
}
