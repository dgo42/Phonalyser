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

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.edgo.audio.measure.dsp.FreqRespCalHelper;
import org.edgo.audio.measure.dsp.StereoFreqRespCalibration;
import org.edgo.audio.measure.common.FileVersions;
import org.edgo.audio.measure.common.FreqRespCorrectionStore;
import org.edgo.audio.measure.enums.AlignGenerator;
import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.GenChangeCause;
import org.edgo.audio.measure.enums.MainsSuppression;
import org.edgo.audio.measure.enums.WindowType;
import org.edgo.audio.measure.fft.FftAnalyzer;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.fft.MathUtil;
import org.edgo.audio.measure.gui.bind.Bindings;
import org.edgo.audio.measure.bind.Property;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.Dialogs;
import org.edgo.audio.measure.gui.common.IconUtils;
import org.edgo.audio.measure.gui.common.SvgPaths;
import org.edgo.audio.measure.gui.widgets.NumericStepField;
import org.edgo.audio.measure.gui.widgets.UnitFamily;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.preferences.CalibrationEntry;
import org.edgo.audio.measure.preferences.FftPreset;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.gui.scope.AdcCalibrationDialog;
import org.edgo.audio.measure.gui.widgets.TileTabFolder;

import lombok.extern.log4j.Log4j2;

/**
 * Self-contained FFT toolbar control: a {@link TileTabFolder} carrying the
 * Settings, THD, Presets, Utility, Calibration, and (live-capture only)
 * Save-to / Load-from tabs, with all of their preference bindings, preset
 * machinery, {@code .frc} calibration rows and {@code .fft} save / load.
 *
 * <p>The host {@link FftPane} keeps only the chart, scrollbars and Record
 * button; everything tab-related lives here.  The few cross-pane concerns are
 * routed through {@link MessageBus} so this control needs no back-reference to
 * the pane:
 * <ul>
 *   <li>{@link Events#FFT_SCREENSHOT_REQUESTED} — the Utility tab's camera
 *       button; the pane owns the screenshot dialog (it clones itself
 *       offscreen) and subscribes.</li>
 *   <li>{@link Events#FFT_RECORDING_STOP_REQUESTED} — loading a spectrum file
 *       must stop live recording so the static trace isn't overwritten; the
 *       pane owns the Record button and subscribes.</li>
 * </ul>
 * The {@link FftView} is shared with the pane and passed in at construction.
 */
@Log4j2
public final class FftTabControl extends Composite {

    /** Powers-of-2 from 2^13 (8192) to 2^22 (4,194,304) with their labels. */
    private static final int[]    FFT_LENGTH_VALUES = {
            8192, 16384, 32768, 65536, 131072, 262144,
            524288, 1048576, 2097152, 4194304
    };
    private static final String[] FFT_LENGTH_LABELS = {
            "8k", "16k", "32k", "64k", "128k", "256k", "512k", "1M", "2M", "4M"
    };

    /** Indices of the toolbar tabs.  The first {@link #NUM_CUSTOM_TABS} are
     *  custom-rendered (label + optional tile row); the Presets / Utility tabs
     *  in that range simply carry no tiles (centred label), matching the
     *  FreqResp pane.  Save / Load (live-capture only) render with the default
     *  CTabFolder look. */
    private static final int TAB_FFT_SETTINGS = 0;
    private static final int TAB_THD_SETTINGS = 1;
    private static final int TAB_CALIBRATION  = 4;
    private static final int NUM_CUSTOM_TABS  = 5;

    /** Pixel height of the utility-row icons (camera screenshot, crosshair
     *  calibrate). */
    private static final int UTILITY_ICON_HEIGHT = 26;

    /** Averages presets the field's wheel / arrows jump along; ∞ = forever. */
    private static final double[] AVERAGES_SERIES =
            { 2, 4, 8, 16, 32, 64, 128, Double.POSITIVE_INFINITY };
    /** Stop-after-N bounds and wheel step (arrows step by 1). */
    private static final double STOP_AFTER_MIN        = 2;
    private static final double STOP_AFTER_MAX        = 1_000_000;
    private static final double STOP_AFTER_WHEEL_STEP = 100;
    /** Manual-fundamental ceiling (Vrms): declared external levels — e.g. a
     *  power amplifier measured through a divider — can far exceed the DAC
     *  full-scale. */
    private static final double MANUAL_FUND_MAX_VRMS  = 200.0;
    /** Amplitude floor (Vrms) — keeps log-unit (dBV) entry finite. */
    private static final double AMP_MIN_VRMS          = 1e-6;
    /** Harmonic-count bounds: THD measures H2…H9; the calc ceiling feeds the
     *  compensation workflows. */
    private static final double THD_HARM_MIN  = 2;
    private static final double THD_HARM_MAX  = 9;
    private static final double CALC_HARM_MIN = 9;
    private static final double CALC_HARM_MAX = 50;
    // Display precision caps per the numeric-field spec.
    private static final int FREQ_MAX_DECIMALS = 9;
    private static final int AMP_MAX_DECIMALS  = 5;

    private final FftView view;

    /** Loaded {@code .frc} correction store, constructor-injected by
     *  {@link FftPane} (IoC) and shared with the {@link FftView}. */
    private final FreqRespCorrectionStore correctionStore;

    // Image references retained so build methods past the constructor can
    // re-use them; all instances come from the shared IconUtils cache so
    // disposal is handled centrally.
    private final Image cameraIcon;
    private final Image crosshairIcon;

    // ---- Tab-header tile rendering: the shared {@link TileTabFolder} owns
    //      the renderer, spacer images, collapse, hover tooltips and tile
    //      painting; this control only feeds it tile content (see fftTabTiles).
    private TileTabFolder toolbarTabs;

    // Widget references for preset apply/refresh — captured at build time.
    private Combo              fftLengthCombo;
    private Combo              windowCombo;
    private Combo              overlapCombo;
    private NumericStepField   averagesField;
    private Button             stopAfterNEnable;
    private NumericStepField   stopAfterNField;
    private Combo              mainsSuppressionCombo;
    private Button             fundFromGenCheck;
    private Button             logFreqCheck;
    private Button             coherentCheck;
    /** "Align generator" — None / PID / FLL — selects the FFT-side
     *  frequency-alignment loop.  Enabled in the UI only when both
     *  snap-to-FFT-bin and fund-from-generator are on, since the loop
     *  needs a target bin (snap) and a target frequency (fund-from-gen)
     *  to lock onto.  Even when a mode is selected the loop only fires if
     *  the other two are also on — see {@code FftView.applyFrequencyLockLoop}. */
    private Combo              alignGenCombo;
    private Button             distMinEnable;
    private NumericStepField   distMinField;
    private Button             distMaxEnable;
    private NumericStepField   distMaxField;
    private NumericStepField   thdMaxHarmField;
    private NumericStepField   calcMaxHarmField;
    private Button             manualFundEnable;
    private NumericStepField   manualFundField;
    private Combo              presetCombo;
    private Button             presetSaveBtn;
    private Button             presetLoadBtn;
    private Button             presetDeleteBtn;

    /** Subscriber for {@link Events#GENERATOR_SIGNAL_CHANGED} — used
     *  only to refresh {@link #alignGenCombo}'s enabled state when the
     *  user toggles snap-to-FFT-bin on the generator pane (snap-to-bin
     *  is the missing prerequisite the user needs to satisfy here). */
    private Consumer<GenChangeCause> genChangeListener;

    private Composite             fftCalRowsContainer;
    private ScrolledComposite     fftCalRowsScroll;
    private final List<FftCalRow> fftCalRows = new ArrayList<>();

    public FftTabControl(Composite parent, FftView view, boolean liveCapture,
                         FreqRespCorrectionStore correctionStore) {
        super(parent, SWT.NONE);
        this.view = view;
        this.correctionStore = correctionStore;

        IconUtils icons = IconUtils.instance();
        Display d = parent.getDisplay();
        this.cameraIcon    = icons.render(d, SvgPaths.CAMERA,
                (int) Math.round(UTILITY_ICON_HEIGHT * 1.27), UTILITY_ICON_HEIGHT);
        this.crosshairIcon = icons.render(d, SvgPaths.CROSSHAIR,
                UTILITY_ICON_HEIGHT, UTILITY_ICON_HEIGHT);

        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 0; gl.marginHeight = 0;
        setLayout(gl);

        TileTabFolder tabs = new TileTabFolder(this, SWT.NONE);
        // SWT.DEFAULT lets the folder size itself to fit the active tab's
        // content (header + body).  We used to pin this to a hard-coded
        // 168 px but the value drifted out of sync as the Settings / THD
        // layouts evolved, leaving a 24-px gap at the bottom.
        tabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        this.toolbarTabs = tabs;
        // The folder paints a live "tile" summary under the Settings / THD
        // tab labels; feed it this control's tile content + fallback tooltips.
        tabs.setCustomTabs(NUM_CUSTOM_TABS, new TileTabFolder.TileSource() {
            @Override
            public List<TileTabFolder.Tile> tilesFor(int tabIndex) {
                return fftTabTiles(tabIndex);
            }
            @Override
            public String tabTooltip(int tabIndex) {
                String key = tabLabelTooltipKey(tabIndex);
                return key != null ? I18n.t(key) : null;
            }
        });
        buildSettingsTab(tabs);
        buildThdTab(tabs);
        buildPresetsTab(tabs);
        buildUtilityTab(tabs);
        buildCalibrationTab(tabs);
        if (liveCapture) {
            buildSaveToTab(tabs);
            buildLoadFromTab(tabs);
        }
        if (tabs.getItemCount() > 0) tabs.setSelection(0);
        // Capture labels, size the strip / spacer images and wire the
        // paint, hover and collapse listeners now that the tabs exist.
        tabs.init();

        // Snap-to-FFT-bin lives on the generator pane and is the missing
        // prerequisite for "Align generator"; refresh the combo's enabled
        // state whenever the generator signal changes.
        genChangeListener = cause -> updateAlignGenEnabled();
        MessageBus.instance().subscribe(Events.GENERATOR_SIGNAL_CHANGED, genChangeListener);
        // Audio-format edits (Preferences OK, UI thread) move the Nyquist
        // ceiling of the distortion band-edge fields.
        Consumer<Void> audioFormatListener = ignored -> {
            if (isDisposed()) return;
            double nyquist = Preferences.instance().current().getInputSampleRate() / 2.0;
            distMinField.setMax(nyquist);
            distMaxField.setMax(nyquist);
        };
        MessageBus.instance().subscribe(Events.AUDIO_FORMAT_CHANGED, audioFormatListener);
        addDisposeListener(e -> {
            MessageBus.instance().unsubscribe(Events.GENERATOR_SIGNAL_CHANGED, genChangeListener);
            MessageBus.instance().unsubscribe(Events.AUDIO_FORMAT_CHANGED, audioFormatListener);
        });

        wireHelpAnchors();

        // Push the initially-loaded active rows into the store last — the view
        // (built before this control) already holds the same store instance, so
        // the change events this fires find it ready.
        syncFftStoreFromRows();
    }

    // -------------------------------------------------------------------------
    // Pane delegation
    // -------------------------------------------------------------------------

    /** Runs {@code r} after each tab-body collapse / expand so the host pane
     *  can re-flow its own layout (the chart above reclaims the freed space). */
    public void setCollapseRelayout(Runnable r) {
        if (toolbarTabs != null) toolbarTabs.setCollapseRelayout(r);
    }

    /** Collapses or expands the toolbar tab body so the screenshot path can
     *  hide the settings tabs before printing.  Delegates to the shared
     *  {@link TileTabFolder}. */
    public void setTabsCollapsed(boolean collapsed) {
        if (toolbarTabs != null) toolbarTabs.setCollapsed(collapsed);
    }

    /** Overlays the tab tile rows into {@code gc} for the screenshot path —
     *  SWT's {@code Control.print()} captures the folder's native chrome but
     *  not the paint listener that draws the tiles, so the snapshot overlays
     *  them by hand.  Delegates to the shared {@link TileTabFolder}. */
    public void paintTilesInto(GC gc, Control origin) {
        if (toolbarTabs != null) toolbarTabs.paintTilesInto(gc, origin);
    }

    /** Tags every interactive tab widget with a {@code "helpAnchor"} so
     *  Ctrl+F1 can resolve the focused control to a specific section of
     *  {@code fft.html}.  Tab tiles are painted (not real widgets); the
     *  pane's chapter-level anchor serves as the fallback. */
    private void wireHelpAnchors() {
        if (fftLengthCombo      != null) fftLengthCombo     .setData("helpAnchor", "fft.html#fft-length");
        if (windowCombo         != null) windowCombo        .setData("helpAnchor", "fft.html#fft-window");
        if (overlapCombo        != null) overlapCombo       .setData("helpAnchor", "fft.html#fft-overlap");
        if (averagesField       != null) averagesField      .setData("helpAnchor", "fft.html#fft-averages");
        if (stopAfterNEnable    != null) stopAfterNEnable   .setData("helpAnchor", "fft.html#fft-stop-after");
        if (stopAfterNField     != null) stopAfterNField    .setData("helpAnchor", "fft.html#fft-stop-after");
        if (fundFromGenCheck    != null) fundFromGenCheck   .setData("helpAnchor", "fft.html#fft-fund-from-gen");
        if (logFreqCheck        != null) logFreqCheck       .setData("helpAnchor", "fft.html#fft-log-freq-axis");
        if (coherentCheck       != null) coherentCheck      .setData("helpAnchor", "fft.html#fft-coherent-avg");
        if (distMinEnable       != null) distMinEnable      .setData("helpAnchor", "fft.html#fft-dist-min");
        if (distMinField        != null) distMinField       .setData("helpAnchor", "fft.html#fft-dist-min");
        if (distMaxEnable       != null) distMaxEnable      .setData("helpAnchor", "fft.html#fft-dist-max");
        if (distMaxField        != null) distMaxField       .setData("helpAnchor", "fft.html#fft-dist-max");
        if (manualFundEnable    != null) manualFundEnable   .setData("helpAnchor", "fft.html#fft-manual-fund");
        if (manualFundField     != null) manualFundField    .setData("helpAnchor", "fft.html#fft-manual-fund");
        if (thdMaxHarmField     != null) thdMaxHarmField    .setData("helpAnchor", "fft.html#fft-max-harmonic-thd");
        if (calcMaxHarmField    != null) calcMaxHarmField   .setData("helpAnchor", "fft.html#fft-max-harmonic-calc");
        if (presetCombo         != null) presetCombo        .setData("helpAnchor", "fft.html#fft-tab-presets");
        if (presetSaveBtn       != null) presetSaveBtn      .setData("helpAnchor", "fft.html#fft-tab-presets");
        if (presetLoadBtn       != null) presetLoadBtn      .setData("helpAnchor", "fft.html#fft-tab-presets");
        if (presetDeleteBtn     != null) presetDeleteBtn    .setData("helpAnchor", "fft.html#fft-tab-presets");
    }

    /** Enables / disables the "Stop after N" checkbox + numeric field
     *  based on the current averages setting.  Stop-after only makes
     *  sense when averages is set to "forever" — a finite N already
     *  implements a moving window so the analysis is continuous and
     *  "stop after K" can't add anything meaningful. */
    private void refreshStopAfterEnable() {
        if (stopAfterNEnable == null || stopAfterNEnable.isDisposed()) return;
        boolean forever = Double.isInfinite(Preferences.instance().getFftAverages());
        stopAfterNEnable.setEnabled(forever);
        stopAfterNField .setEnabled(forever && stopAfterNEnable.getSelection());
    }

    // =========================================================================
    // Settings tab
    // =========================================================================

    private void buildSettingsTab(CTabFolder folder) {
        Composite g = groupCell(folder, I18n.t("fft.tab.settings"));
        GridLayout gl = new GridLayout(4, false);
        gl.marginWidth = 6; gl.marginHeight = 4;
        gl.horizontalSpacing = 2; gl.verticalSpacing = 4;
        g.setLayout(gl);
        Preferences prefs = Preferences.instance();

        addLabel(g, I18n.t("fft.settings.length"));
        fftLengthCombo = new Combo(g, SWT.READ_ONLY);
        fftLengthCombo.setItems(FFT_LENGTH_LABELS);
        fftLengthCombo.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        fftLengthCombo.setToolTipText(I18n.t("fft.settings.length.tooltip"));
        // Index-mapped combo: the selection index maps through FFT_LENGTH_VALUES
        // to the actual length, so it can't use the ordinal-based Bindings.combo.
        // The two-way mirror is hand-wired but follows the same contract: seed
        // from the pref, write the pref on input, re-select on an external change
        // (preset load).  Default index 3 (64k) when the saved length is unknown.
        bindFftLengthCombo(prefs.fftLengthProperty());
        // Pane-local effect — refresh the FFT-settings tab tile.  The view's own
        // resetStatistics is driven by the view subscribing to the same pref.
        Bindings.onChange(toolbarTabs, prefs.fftLengthProperty(),
                v -> toolbarTabs.refreshTab(TAB_FFT_SETTINGS));
        // Generator's bin snap is anchored to fftLength via sampleRate /
        // fftLength — broadcast the change so the generator pane can re-snap its
        // running tone onto a fresh bin.  The new length is already in
        // Preferences; subscribers read it from there.
        Bindings.onChange(toolbarTabs, prefs.fftLengthProperty(),
                v -> MessageBus.instance().publish(Events.FFT_LENGTH_CHANGED));

        addLabel(g, I18n.t("fft.settings.window"));
        windowCombo = new Combo(g, SWT.READ_ONLY);
        for (WindowType w : WindowType.values()) windowCombo.add(I18n.t(w.labelKey()));
        windowCombo.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        windowCombo.setToolTipText(I18n.t("fft.settings.window.tooltip"));
        Bindings.combo(windowCombo, prefs.fftWindowProperty(), WindowType.values());
        Bindings.onChange(toolbarTabs, prefs.fftWindowProperty(), w -> toolbarTabs.refreshTab(TAB_FFT_SETTINGS));

        addLabel(g, I18n.t("fft.settings.overlap"));
        overlapCombo = new Combo(g, SWT.READ_ONLY);
        for (FftOverlap ov : FftOverlap.values()) overlapCombo.add(ov.label);
        overlapCombo.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        overlapCombo.setToolTipText(I18n.t("fft.settings.overlap.tooltip"));
        Bindings.combo(overlapCombo, prefs.fftOverlapProperty(), FftOverlap.values());
        // Overlap only changes the hop, not the spectrum/accumulator — refresh
        // the tab tile but DON'T reset the average; the worker adapts next tick.
        Bindings.onChange(toolbarTabs, prefs.fftOverlapProperty(),
                v -> toolbarTabs.refreshTab(TAB_FFT_SETTINGS));

        addLabel(g, I18n.t("fft.settings.averages"));
        // List stepper: wheel / arrow keys snap to the next / previous
        // preset (2 … 128, ∞) while manual typing still accepts any
        // count ≥ 2 (and the ∞ / inf token, since max is unbounded).
        averagesField = new NumericStepField(g, UnitFamily.NONE,
                AVERAGES_SERIES[0], Double.POSITIVE_INFINITY, AVERAGES_SERIES, 0, 70);
        averagesField.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        averagesField.setToolTipText(I18n.t("fft.settings.averages.tooltip"));
        Bindings.stepField(averagesField, prefs.fftAveragesProperty());
        // Pane-local effects only — the tab tile and the stop-after gate.  No
        // reset here: the worker resets the average only on a ring↔∞ switch or a
        // smaller ring (a larger ring keeps the depth).
        Bindings.onChange(toolbarTabs, prefs.fftAveragesProperty(), v -> {
            toolbarTabs.refreshTab(TAB_FFT_SETTINGS);
            refreshStopAfterEnable();
        });

        // Stop-after row spans all 4 outer-grid columns and hosts its
        // own RowLayout so the checkbox + label + field don't widen
        // the outer column-1 / column-0 cells (which previously left
        // empty strips after the FFT-length combo and the
        // FFT-length-label cell).  Checkbox carries its own text now —
        // a separate Label was creating an extra gap.
        Composite stopRow = new Composite(g, SWT.NONE);
        GridData stopRowGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        stopRowGd.horizontalSpan = 4;
        stopRow.setLayoutData(stopRowGd);
        RowLayout stopRowLayout = new RowLayout(SWT.HORIZONTAL);
        stopRowLayout.marginTop    = 0; stopRowLayout.marginBottom = 0;
        stopRowLayout.marginLeft   = 0; stopRowLayout.marginRight  = 0;
        stopRowLayout.spacing      = 4;
        stopRowLayout.center       = true;
        stopRow.setLayout(stopRowLayout);

        stopAfterNEnable = new Button(stopRow, SWT.CHECK);
        stopAfterNEnable.setText(I18n.t("fft.settings.stopAfterN"));
        stopAfterNEnable.setToolTipText(I18n.t("fft.settings.stopAfterN.tooltip"));
        Bindings.check(stopAfterNEnable, prefs.fftStopAfterNEnabledProperty());
        stopAfterNField = new NumericStepField(stopRow, UnitFamily.NONE,
                STOP_AFTER_MIN, STOP_AFTER_MAX, STOP_AFTER_WHEEL_STEP, 1, 0, 70);
        stopAfterNField.setToolTipText(I18n.t("fft.settings.stopAfterN.tooltip"));
        Bindings.stepFieldInt(stopAfterNField, prefs.fftStopAfterNProperty());
        // Initial enabled state reflects both "stop-after" toggle AND the rule
        // that the row is only meaningful when averages is "forever" (a finite N
        // already implements a moving window).  The companion field's enabled
        // state depends on BOTH the toggle and averages==forever, so the enable
        // recompute is centralized in refreshStopAfterEnable() — driven here off
        // the toggle pref and from the averages listener above.  Don't reset the
        // average: the worker stops as soon as the collected count reaches N.
        refreshStopAfterEnable();
        Bindings.onChange(toolbarTabs, prefs.fftStopAfterNEnabledProperty(),
                v -> refreshStopAfterEnable());

        // Mains-suppression selector shares the stop-after row.  Pre-filters
        // the captured signal (50/60 Hz + harmonics) before averaging; tracks
        // the mains frequency live while recording.
        new Label(stopRow, SWT.NONE).setText(I18n.t("fft.settings.mainsSuppression"));
        mainsSuppressionCombo = new Combo(stopRow, SWT.READ_ONLY);
        mainsSuppressionCombo.setItems(MainsSuppression.LABELS);
        mainsSuppressionCombo.setToolTipText(I18n.t("fft.settings.mainsSuppression.tooltip"));
        Bindings.combo(mainsSuppressionCombo, prefs.fftMainsSuppressionProperty(),
                MainsSuppression.values());

        // Four boolean knobs packed onto two rows so the tab content
        // fits inside the typical FFT-pane height (the previous 3-rows-
        // of-spans-4 layout pushed the third checkbox below the visible
        // CTabFolder client area).
        // Row: Get fundamental from generator (span 2) | Align generator (span 2)
        fundFromGenCheck = new Button(g, SWT.CHECK);
        fundFromGenCheck.setText(I18n.t("fft.settings.fundFromGen"));
        GridData fgGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        fgGd.horizontalSpan = 2;
        fundFromGenCheck.setLayoutData(fgGd);
        fundFromGenCheck.setToolTipText(I18n.t("fft.settings.fundFromGen.tooltip"));
        Bindings.check(fundFromGenCheck, prefs.fftFundFromGeneratorProperty());

        // "Align generator" — None / FLL — only meaningful when both
        // snap-to-bin (provides a target bin) and fund-from-generator (provides a
        // target frequency) are on, so the combo is disabled otherwise.  Even when
        // a mode is selected the loop only fires if the other two are also on — see
        // FftView.applyFrequencyLockLoop.
        Label algLbl = new Label(g, SWT.NONE);
        algLbl.setText(I18n.t("fft.settings.alignGen"));
        algLbl.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        alignGenCombo = new Combo(g, SWT.DROP_DOWN | SWT.READ_ONLY);
        for (AlignGenerator ag : AlignGenerator.values()) {
            alignGenCombo.add(ag.label);
        }
        alignGenCombo.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        alignGenCombo.setToolTipText(I18n.t("fft.settings.alignGen.tooltip"));
        // Selecting an active mode resets its loop so each session converges
        // fresh; NONE deliberately holds everything — that side-effect lives in
        // the view, which subscribes to this same pref.  Here it's a plain
        // two-way value bind.
        Bindings.combo(alignGenCombo, prefs.fftAlignGeneratorProperty(), AlignGenerator.values());

        // Row: Coherent averaging (span 2) | Log freq (span 2)
        coherentCheck = new Button(g, SWT.CHECK);
        coherentCheck.setText(I18n.t("fft.thd.coherent"));
        GridData cohGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        cohGd.horizontalSpan = 2;
        coherentCheck.setLayoutData(cohGd);
        coherentCheck.setToolTipText(I18n.t("fft.thd.coherent.tooltip"));
        Bindings.check(coherentCheck, prefs.fftCoherentAveragingProperty());
        // Pane-local tab-tile refresh; the accumulator reset (coherent ↔
        // incoherent changes its semantics) lives in the view, which subscribes
        // to this same pref.
        Bindings.onChange(toolbarTabs, prefs.fftCoherentAveragingProperty(),
                v -> toolbarTabs.refreshTab(TAB_FFT_SETTINGS));

        logFreqCheck = new Button(g, SWT.CHECK);
        logFreqCheck.setText(I18n.t("fft.settings.logFreq"));
        GridData lgGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        lgGd.horizontalSpan = 2;
        logFreqCheck.setLayoutData(lgGd);
        logFreqCheck.setToolTipText(I18n.t("fft.settings.logFreq.tooltip"));
        // Pure repaint side-effect (axis remap) lives in the view, which
        // subscribes to this same pref — a plain two-way value bind here.
        Bindings.check(logFreqCheck, prefs.fftLogFreqAxisProperty());

        // Initial enable state + recompute it when fund-from-generator flips
        // (subscribed to the pref, so a preset load drives it too); snap-to-bin
        // changes arrive via the GENERATOR_SIGNAL_CHANGED bus subscriber, which
        // also calls updateAlignGenEnabled().
        updateAlignGenEnabled();
        Bindings.onChange(toolbarTabs, prefs.fftFundFromGeneratorProperty(),
                v -> updateAlignGenEnabled());
    }

    /** Enables {@link #alignGenCombo} only when both prerequisites
     *  (snap-to-FFT-bin and fund-from-generator) are checked.  Called
     *  from local listeners and from the {@code GENERATOR_SIGNAL_CHANGED}
     *  bus subscriber (which fires when the user toggles snap-to-bin
     *  on the generator pane). */
    private void updateAlignGenEnabled() {
        if (alignGenCombo == null || alignGenCombo.isDisposed()) return;
        Preferences prefs = Preferences.instance();
        alignGenCombo.setEnabled(prefs.isGenSnapToFftBin() && prefs.isFftFundFromGenerator());
    }

    /** Two-way mirror for the index-mapped FFT-length combo and its
     *  {@code Integer} length {@link Preferences} property.  The combo items
     *  are {@link #FFT_LENGTH_LABELS} aligned with {@link #FFT_LENGTH_VALUES};
     *  the selection index maps through the latter to the actual length (so it
     *  can't use the ordinal-based {@link Bindings#combo}).  Seeds index 3
     *  (64k) when the saved length isn't one of the presets, writes the mapped
     *  length on user input, and re-selects the index when the length changes
     *  elsewhere (a preset load). */
    private void bindFftLengthCombo(Property<Integer> property) {
        int seed = indexOfInt(FFT_LENGTH_VALUES, property.get());
        fftLengthCombo.select(seed < 0 ? 3 : seed);
        fftLengthCombo.addListener(SWT.Selection, e -> {
            int i = fftLengthCombo.getSelectionIndex();
            if (i >= 0) {
                property.set(FFT_LENGTH_VALUES[i]);
            }
        });
        Consumer<Integer> onChange = v -> {
            if (fftLengthCombo.isDisposed()) return;
            int i = indexOfInt(FFT_LENGTH_VALUES, v);
            if (i >= 0 && fftLengthCombo.getSelectionIndex() != i) {
                fftLengthCombo.select(i);
            }
        };
        property.addListener(onChange);
        fftLengthCombo.addDisposeListener(e -> property.removeListener(onChange));
    }

    // =========================================================================
    // THD tab
    // =========================================================================

    private void buildThdTab(CTabFolder folder) {
        Composite g = groupCell(folder, I18n.t("fft.tab.thd"));
        GridLayout gl = new GridLayout(4, false);
        gl.marginWidth = 6; gl.marginHeight = 4;
        gl.horizontalSpacing = 6; gl.verticalSpacing = 4;
        g.setLayout(gl);
        Preferences prefs = Preferences.instance();

        // Row: distortion high-pass.  Checkbox text replaces the
        // separate Label so the field can sit immediately to the right
        // of the checkbox (no gap from a missing-text cell).  Field
        // spans the remaining 3 columns of the 4-column grid so the
        // row layout matches the other rows.
        distMinEnable = new Button(g, SWT.CHECK);
        distMinEnable.setText(I18n.t("fft.thd.distMin"));
        distMinEnable.setToolTipText(I18n.t("fft.thd.distMin.tooltip"));
        distMinField = new NumericStepField(g, UnitFamily.FREQUENCY,
                0, Preferences.instance().current().getInputSampleRate() / 2.0,
                FREQ_MAX_DECIMALS, 90);
        GridData distMinFieldGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        distMinFieldGd.horizontalSpan = 3;
        distMinField.setLayoutData(distMinFieldGd);
        distMinField.setToolTipText(I18n.t("fft.thd.distMin.tooltip"));
        distMinField.setEnabled(prefs.isFftDistMinEnabled());
        Bindings.check(distMinEnable, prefs.fftDistMinEnabledProperty());
        Bindings.stepField(distMinField, prefs.fftDistMinHzProperty());
        // Toggling also enables/disables the companion field, and the toggle
        // feeds the THD tile (shortDistRange).  The redraw side-effect lives in
        // the view, which subscribes to both these prefs.
        Bindings.onChange(toolbarTabs, prefs.fftDistMinEnabledProperty(), v -> {
            distMinField.setEnabled(v);
            toolbarTabs.refreshTab(TAB_THD_SETTINGS);
        });
        Bindings.onChange(toolbarTabs, prefs.fftDistMinHzProperty(),
                v -> toolbarTabs.refreshTab(TAB_THD_SETTINGS));

        // Row: distortion low-pass.
        distMaxEnable = new Button(g, SWT.CHECK);
        distMaxEnable.setText(I18n.t("fft.thd.distMax"));
        distMaxEnable.setToolTipText(I18n.t("fft.thd.distMax.tooltip"));
        distMaxField = new NumericStepField(g, UnitFamily.FREQUENCY,
                0, Preferences.instance().current().getInputSampleRate() / 2.0,
                FREQ_MAX_DECIMALS, 90);
        GridData distMaxFieldGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        distMaxFieldGd.horizontalSpan = 3;
        distMaxField.setLayoutData(distMaxFieldGd);
        distMaxField.setToolTipText(I18n.t("fft.thd.distMax.tooltip"));
        distMaxField.setEnabled(prefs.isFftDistMaxEnabled());
        Bindings.check(distMaxEnable, prefs.fftDistMaxEnabledProperty());
        Bindings.stepField(distMaxField, prefs.fftDistMaxHzProperty());
        // Mirror of distMin: toggling drives the companion field + THD tile;
        // the redraw lives in the view, subscribed to both prefs.
        Bindings.onChange(toolbarTabs, prefs.fftDistMaxEnabledProperty(), v -> {
            distMaxField.setEnabled(v);
            toolbarTabs.refreshTab(TAB_THD_SETTINGS);
        });
        Bindings.onChange(toolbarTabs, prefs.fftDistMaxHzProperty(),
                v -> toolbarTabs.refreshTab(TAB_THD_SETTINGS));

        // Row: Manual fundamental — moved one row UP so it sits above
        // the harmonic-count row.  Checkbox text replaces the
        // separate Label here too.
        manualFundEnable = new Button(g, SWT.CHECK);
        manualFundEnable.setText(I18n.t("fft.thd.manualFund"));
        manualFundEnable.setToolTipText(I18n.t("fft.thd.manualFund.tooltip"));
        // Single field that accepts the value AND a unit suffix —
        // e.g. "1.5 V", "1500 mV", "-3.5 dBV".  The value is canonical Vrms;
        // unit parsing / display switching is the field's own concern.  The
        // 200 V ceiling covers declared external levels (amplifier output
        // measured through a divider), far above the DAC full-scale.
        manualFundField = new NumericStepField(g, UnitFamily.AMPLITUDE,
                AMP_MIN_VRMS, MANUAL_FUND_MAX_VRMS, AMP_MAX_DECIMALS, 110);
        GridData manualFundFieldGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        manualFundFieldGd.horizontalSpan = 3;
        manualFundField.setLayoutData(manualFundFieldGd);
        manualFundField.setToolTipText(I18n.t("fft.thd.manualFund.tooltip"));
        manualFundField.setEnabled(prefs.isFftManualFundEnabled());
        Bindings.check(manualFundEnable, prefs.fftManualFundEnabledProperty());
        // Toggling also enables/disables the companion field and drives the
        // 'manF' THD tile (shown only when enabled).  No view redraw/reset.
        Bindings.onChange(toolbarTabs, prefs.fftManualFundEnabledProperty(), v -> {
            manualFundField.setEnabled(v);
            toolbarTabs.refreshTab(TAB_THD_SETTINGS);
        });

        addLabel(g, I18n.t("fft.thd.maxThd"));
        thdMaxHarmField = new NumericStepField(g, UnitFamily.NONE,
                THD_HARM_MIN, THD_HARM_MAX, 1, 1, 0, 60);
        thdMaxHarmField.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        thdMaxHarmField.setToolTipText(I18n.t("fft.thd.maxThd.tooltip"));
        Bindings.stepFieldInt(thdMaxHarmField, prefs.fftThdMaxHarmonicProperty());
        // Drives the 'H{n}' THD tile.  No view redraw/reset.
        Bindings.onChange(toolbarTabs, prefs.fftThdMaxHarmonicProperty(),
                v -> toolbarTabs.refreshTab(TAB_THD_SETTINGS));

        addLabel(g, I18n.t("fft.thd.maxCalc"));
        // Minimum 9 — the THD overlay table is laid out for the H2..H9
        // range and shrinking below that leaves empty rows on the
        // bottom that look like a rendering bug.  Value N means "calc
        // up to HN" (N − 1 harmonics in the 2..N range).
        calcMaxHarmField = new NumericStepField(g, UnitFamily.NONE,
                CALC_HARM_MIN, CALC_HARM_MAX, 1, 1, 0, 60);
        calcMaxHarmField.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        calcMaxHarmField.setToolTipText(I18n.t("fft.thd.maxCalc.tooltip"));
        // The external THD window's row count tracks this value (resize so the
        // harmonic rows aren't clipped) — that side-effect lives in the view,
        // subscribed to this same pref.  The min-9 floor at the old write site
        // was redundant: every reader of getFftCalcMaxHarmonic() already wraps
        // it in Math.max(9, ...), so a plain two-way value bind is faithful.
        Bindings.stepFieldInt(calcMaxHarmField, prefs.fftCalcMaxHarmonicProperty());
        // Manual-fundamental value field — registered here, LATE (after the
        // calc-max field is built), matching the original ordering.  Unit-aware
        // parser/formatter is unchanged; this only mirrors the committed value.
        Bindings.stepField(manualFundField, prefs.fftManualFundVrmsProperty());
        // dBV display choice: seed from the persisted pref, persist the user's
        // typed unit, and follow external writes (preset load) — setLogDisplay
        // is idempotent and fires no listener, so the loop terminates.
        manualFundField.setLogDisplay(prefs.isFftManualFundDbvDisplay());
        manualFundField.addSelectionListener(e ->
                prefs.setFftManualFundDbvDisplay(manualFundField.isLogDisplay()));
        Bindings.onChange(manualFundField, prefs.fftManualFundDbvDisplayProperty(),
                v -> manualFundField.setLogDisplay(v));
        new Label(g, SWT.NONE);   // fill row
        // (The previous global "Calibrate with noise" checkbox moved to
        // the Load-calibration tab — it's now a per-row "With noise"
        // flag, set independently per loaded .frc file.)
    }

    // =========================================================================
    // Presets tab
    // =========================================================================

    private void buildPresetsTab(CTabFolder folder) {
        Composite g = groupCell(folder, I18n.t("fft.tab.presets"));
        GridLayout gl = new GridLayout(4, false);
        gl.marginWidth = 6; gl.marginHeight = 4; gl.horizontalSpacing = 6;
        g.setLayout(gl);
        Preferences prefs = Preferences.instance();

        presetCombo = new Combo(g, SWT.DROP_DOWN);
        presetCombo.setToolTipText(I18n.t("fft.presets.combo.tooltip"));
        GridData comboGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        comboGd.widthHint = 180;
        presetCombo.setLayoutData(comboGd);
        for (String name : prefs.getFftPresets().keySet()) presetCombo.add(name);
        presetCombo.addListener(SWT.Modify,    e -> refreshPresetButtonState());
        presetCombo.addListener(SWT.Selection, e -> refreshPresetButtonState());

        presetSaveBtn = new Button(g, SWT.PUSH);
        presetSaveBtn.setText(I18n.t("fft.presets.save"));
        presetSaveBtn.setToolTipText(I18n.t("fft.presets.save.tooltip"));
        presetSaveBtn.addListener(SWT.Selection, e -> {
            String name = presetCombo.getText().trim();
            if (name.isEmpty()) return;
            if (prefs.getFftPresets().containsKey(name) && !confirmOverwritePreset(name)) return;
            prefs.putFftPreset(name, captureCurrentFftPreset());
            if (presetCombo.indexOf(name) < 0) presetCombo.add(name);
            presetCombo.setText(name);
            refreshPresetButtonState();
        });

        presetLoadBtn = new Button(g, SWT.PUSH);
        presetLoadBtn.setText(I18n.t("fft.presets.load"));
        presetLoadBtn.setToolTipText(I18n.t("fft.presets.load.tooltip"));
        presetLoadBtn.addListener(SWT.Selection, e -> {
            String name = presetCombo.getText().trim();
            if (name.isEmpty()) return;
            FftPreset p = prefs.getFftPresets().get(name);
            if (p != null) {
                applyFftPreset(p);
                refreshPresetButtonState();
            }
        });

        presetDeleteBtn = new Button(g, SWT.PUSH);
        presetDeleteBtn.setText(I18n.t("fft.presets.delete"));
        presetDeleteBtn.setToolTipText(I18n.t("fft.presets.delete.tooltip"));
        presetDeleteBtn.addListener(SWT.Selection, e -> {
            String name = presetCombo.getText().trim();
            if (name.isEmpty() || !prefs.getFftPresets().containsKey(name)) return;
            if (!confirmDeletePreset(name)) return;
            prefs.removeFftPreset(name);
            int idx = presetCombo.indexOf(name);
            if (idx >= 0) presetCombo.remove(idx);
            presetCombo.setText("");
            refreshPresetButtonState();
        });

        refreshPresetButtonState();
        Display display = g.getDisplay();
        Runnable[] tick = { null };
        tick[0] = () -> {
            if (presetCombo == null || presetCombo.isDisposed()) return;
            refreshPresetButtonState();
            display.timerExec(500, tick[0]);
        };
        display.timerExec(500, tick[0]);
    }

    private void refreshPresetButtonState() {
        if (presetCombo == null || presetCombo.isDisposed()) return;
        if (presetSaveBtn == null || presetSaveBtn.isDisposed()) return;
        if (presetLoadBtn == null || presetLoadBtn.isDisposed()) return;
        if (presetDeleteBtn == null || presetDeleteBtn.isDisposed()) return;
        String name = presetCombo.getText().trim();
        if (name.isEmpty()) {
            presetSaveBtn.setEnabled(false);
            presetLoadBtn.setEnabled(false);
            presetDeleteBtn.setEnabled(false);
            return;
        }
        FftPreset existing = Preferences.instance().getFftPresets().get(name);
        if (existing == null) {
            presetSaveBtn.setEnabled(true);
            presetLoadBtn.setEnabled(false);
            presetDeleteBtn.setEnabled(false);
        } else {
            presetSaveBtn.setEnabled(!existing.equals(captureCurrentFftPreset()));
            presetLoadBtn.setEnabled(true);
            presetDeleteBtn.setEnabled(true);
        }
    }

    private boolean confirmOverwritePreset(String name) {
        return Dialogs.confirm(getShell(),
                I18n.t("fft.presets.overwrite.title"),
                I18n.t("fft.presets.overwrite.message", name)) == SWT.YES;
    }

    private boolean confirmDeletePreset(String name) {
        return Dialogs.confirm(getShell(),
                I18n.t("fft.presets.delete.title"),
                I18n.t("fft.presets.delete.message", name)) == SWT.YES;
    }

    private FftPreset captureCurrentFftPreset() {
        Preferences prefs = Preferences.instance();
        FftPreset p = new FftPreset();
        p.setChannel(prefs.getFftChannel());
        p.setMagUnit(prefs.getFftMagUnit());
        p.setLogFreqAxis(prefs.isFftLogFreqAxis());
        p.setFreqMinHz(prefs.getFftFreqMinHz());
        p.setFreqMaxHz(prefs.getFftFreqMaxHz());
        p.setMagTop(prefs.getFftMagTop());
        p.setMagBottom(prefs.getFftMagBottom());
        p.setFftLength(prefs.getFftLength());
        p.setAverages(prefs.getFftAverages());
        p.setStopAfterNEnabled(prefs.isFftStopAfterNEnabled());
        p.setStopAfterN(prefs.getFftStopAfterN());
        p.setFundFromGenerator(prefs.isFftFundFromGenerator());
        p.setWindow(prefs.getFftWindow());
        p.setOverlap(prefs.getFftOverlap());
        p.setCoherentAveraging(prefs.isFftCoherentAveraging());
        p.setDistMinHz(prefs.getFftDistMinHz());
        p.setDistMaxHz(prefs.getFftDistMaxHz());
        p.setDistMinEnabled(prefs.isFftDistMinEnabled());
        p.setDistMaxEnabled(prefs.isFftDistMaxEnabled());
        p.setThdMaxHarmonic(prefs.getFftThdMaxHarmonic());
        p.setCalcMaxHarmonic(Math.max(9, prefs.getFftCalcMaxHarmonic()));
        p.setManualFundVrms(prefs.getFftManualFundVrms());
        p.setManualFundDbvDisplay(prefs.isFftManualFundDbvDisplay());
        p.setManualFundEnabled(prefs.isFftManualFundEnabled());
        return p;
    }

    private void applyFftPreset(FftPreset p) {
        Preferences prefs = Preferences.instance();
        prefs.setFftChannel(p.getChannel());
        prefs.setFftMagUnit(p.getMagUnit());
        prefs.setFftLogFreqAxis(p.isLogFreqAxis());
        prefs.setFftFreqMinHz(p.getFreqMinHz());
        prefs.setFftFreqMaxHz(p.getFreqMaxHz());
        prefs.setFftMagTop(p.getMagTop());
        prefs.setFftMagBottom(p.getMagBottom());
        prefs.setFftLength(p.getFftLength());
        prefs.setFftAverages(p.getAverages());
        prefs.setFftStopAfterNEnabled(p.isStopAfterNEnabled());
        prefs.setFftStopAfterN(p.getStopAfterN());
        prefs.setFftFundFromGenerator(p.isFundFromGenerator());
        prefs.setFftWindow(p.getWindow());
        prefs.setFftOverlap(p.getOverlap());
        prefs.setFftCoherentAveraging(p.isCoherentAveraging());
        prefs.setFftDistMinHz(p.getDistMinHz());
        prefs.setFftDistMaxHz(p.getDistMaxHz());
        prefs.setFftDistMinEnabled(p.isDistMinEnabled());
        prefs.setFftDistMaxEnabled(p.isDistMaxEnabled());
        prefs.setFftThdMaxHarmonic(p.getThdMaxHarmonic());
        prefs.setFftCalcMaxHarmonic(p.getCalcMaxHarmonic());
        prefs.setFftManualFundVrms(p.getManualFundVrms());
        prefs.setFftManualFundDbvDisplay(p.isManualFundDbvDisplay());
        prefs.setFftManualFundEnabled(p.isManualFundEnabled());
        prefs.save();
        // Every settings / THD widget is two-way bound to these prefs, so the
        // setters above already pushed the preset into the live widgets (companion
        // field enable-states ride along on their own pref listeners).  Only the
        // tab tiles and the chart still need a nudge.
        toolbarTabs.refreshTab(TAB_FFT_SETTINGS);
        toolbarTabs.refreshTab(TAB_THD_SETTINGS);
        view.redraw();
    }

    // =========================================================================
    // Utility tab
    // =========================================================================

    private void buildUtilityTab(CTabFolder folder) {
        Composite g = groupCell(folder, I18n.t("fft.tab.utility"));
        GridLayout gl = new GridLayout(4, false);
        gl.marginWidth = 6; gl.marginHeight = 4; gl.horizontalSpacing = 6;
        g.setLayout(gl);

        Button shotBtn = new Button(g, SWT.PUSH);
        shotBtn.setImage(cameraIcon);
        shotBtn.setToolTipText(I18n.t("fft.utility.screenshot.tooltip"));
        // The screenshot dialog clones the whole pane offscreen, so the pane
        // owns it; ask it to open via the bus.
        shotBtn.addListener(SWT.Selection, e -> MessageBus.instance().publish(Events.FFT_SCREENSHOT_REQUESTED));

        Button calBtn = new Button(g, SWT.PUSH);
        calBtn.setImage(crosshairIcon);
        calBtn.setToolTipText(I18n.t("fft.utility.calibrate.tooltip"));
        calBtn.addListener(SWT.Selection, e -> openCalibrationDialog());
    }

    /** Opens the ADC-calibration dialog using this pane's fundamental
     *  Vrms (no fallback — the scope pane has its own calibrate button).
     *  Aborts with an info MessageBox when no live Vrms is available. */
    private void openCalibrationDialog() {
        if (isDisposed()) return;
        Shell parent = getShell();
        Double currentVrms = (view == null) ? null : view.getLastVrms();
        if (currentVrms == null || currentVrms <= 0 || Double.isNaN(currentVrms)) {
            Dialogs.info(parent, I18n.t("calibrate.title"), I18n.t("calibrate.error.noVrms"));
            return;
        }
        final double measuredVrms = currentVrms;
        new AdcCalibrationDialog(parent, measuredVrms, actualVrms -> {
            Preferences prefs = Preferences.instance();
            double scale = actualVrms / measuredVrms;
            double newFs = prefs.getAdcFsVoltageRms() * scale;
            prefs.setAdcFsVoltageRms(newFs);
            prefs.save();
        }).open();
    }

    // =========================================================================
    // Save / Load CSV
    // =========================================================================

    private void buildSaveToTab(CTabFolder folder) {
        Composite g = groupCell(folder, I18n.t("fft.tab.save"));
        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 6; gl.marginHeight = 4; gl.horizontalSpacing = 6;
        g.setLayout(gl);
        Preferences prefs = Preferences.instance();

        Text pathField = new Text(g, SWT.BORDER | SWT.READ_ONLY);
        String saved = prefs.getFftSavePath();
        pathField.setText(saved == null ? "" : saved);
        pathField.setToolTipText(saved != null && !saved.isEmpty() ? saved : I18n.t("fft.save.path.tooltip"));
        pathField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Single floppy-disk button collapses the old browse-then-save
        // two-step into one flow: opens the Save-as dialog, persists the
        // chosen path into the field + prefs, and immediately writes the
        // CSV.  Clicking again with a populated path re-uses it without
        // re-prompting (the file dialog still appears so the user can
        // confirm or change the target).
        Image floppyIcon = IconUtils.instance().renderAtHeight(
                g.getDisplay(), SvgPaths.FLOPPY_DISK, 16, null);
        Button save = new Button(g, SWT.PUSH);
        if (floppyIcon != null) save.setImage(floppyIcon);
        save.setToolTipText(I18n.t("fft.save.write.tooltip"));
        save.addListener(SWT.Selection, e -> {
            FftResult r = view.getLastResult();
            if (r == null) {
                showError(I18n.t("fft.save.error.title"), I18n.t("fft.save.noData"));
                return;
            }
            // Capture the current table mode + (for IMD) its two tone
            // frequencies so re-opening the file restores the same view.
            boolean imd = view.isTableModeImd();
            double tone1 = 0.0;
            double tone2 = 0.0;
            if (imd) {
                ImdResult lastImd = view.getLastImd();
                if (lastImd != null) {
                    tone1 = lastImd.f1Hz;
                    tone2 = lastImd.f2Hz;
                } else {
                    Preferences gp = Preferences.instance();
                    tone1 = gp.getGenDualToneFreq1Hz();
                    tone2 = gp.getGenDualToneFreq2Hz();
                }
            }
            FileDialog d = new FileDialog(getShell(), SWT.SAVE);
            d.setFilterExtensions(new String[]{"*.fft"});
            d.setFilterNames(new String[]{"Phonalyser FFT spectrum (*.fft)"});
            d.setOverwrite(true);
            // Seed the dialog with the previously-used file, if any —
            // lets the user write to the same target with one click +
            // OK in the file dialog.
            String prev = pathField.getText().trim();
            if (!prev.isEmpty()) {
                d.setFileName(Paths.get(prev).getFileName().toString());
                Path parent = Paths.get(prev).getParent();
                if (parent != null) d.setFilterPath(parent.toString());
            } else if (prefs.getFftSaveFolder() != null) {
                d.setFilterPath(prefs.getFftSaveFolder());
            }
            String chosen = d.open();
            if (chosen == null) return;
            if (!chosen.toLowerCase().endsWith(".fft")) chosen = chosen + ".fft";
            pathField.setText(chosen);
            pathField.setToolTipText(chosen);
            prefs.setFftSavePath(chosen);
            Path parent = Paths.get(chosen).getParent();
            prefs.setFftSaveFolder(parent == null ? null : parent.toString());
            prefs.save();
            try {
                writeSpectrumFft(Paths.get(chosen), r, imd, tone1, tone2);
            } catch (IOException ex) {
                showError(I18n.t("fft.save.error.title"),
                        I18n.t("fft.save.error.message", chosen, ex.getMessage()));
            }
        });
    }

    private void buildLoadFromTab(CTabFolder folder) {
        Composite g = groupCell(folder, I18n.t("fft.tab.load"));
        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 6; gl.marginHeight = 4; gl.horizontalSpacing = 6;
        g.setLayout(gl);
        Preferences prefs = Preferences.instance();

        Text pathField = new Text(g, SWT.BORDER | SWT.READ_ONLY);
        String saved = prefs.getFftLoadPath();
        pathField.setText(saved == null ? "" : saved);
        pathField.setToolTipText(saved != null && !saved.isEmpty() ? saved : I18n.t("fft.load.path.tooltip"));
        pathField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Image folderIcon = IconUtils.instance().renderAtHeight(
                g.getDisplay(), SvgPaths.FOLDER_OPEN, 16, null);
        Button browse = new Button(g, SWT.PUSH);
        if (folderIcon != null) browse.setImage(folderIcon);
        browse.setToolTipText(I18n.t("fft.load.browse.tooltip"));
        browse.addListener(SWT.Selection, e -> {
            FileDialog d = new FileDialog(getShell(), SWT.OPEN);
            d.setFilterExtensions(new String[]{"*.fft", "*.csv", "*.*"});
            d.setFilterNames(new String[]{"Phonalyser FFT spectrum (*.fft)",
                    "CSV (legacy)", "All files (*.*)"});
            if (prefs.getFftLoadFolder() != null) d.setFilterPath(prefs.getFftLoadFolder());
            String chosen = d.open();
            if (chosen != null) {
                pathField.setText(chosen);
                pathField.setToolTipText(chosen);
                prefs.setFftLoadPath(chosen);
                prefs.setFftLoadFolder(Paths.get(chosen).getParent() == null ? null
                        : Paths.get(chosen).getParent().toString());
                prefs.save();
                loadSpectrumFft(chosen);
            }
        });
    }

    /** Loads a spectrum {@code .fft} file (as written by
     *  {@link #writeSpectrumFft} — {@code frequency_hz;magnitude_dBV;phase_deg}
     *  rows under {@code # mode=} / {@code # tone*_hz=} header comments),
     *  reconstructs an {@link FftResult}, recomputes its fundamental /
     *  harmonic / THD / SNR metrics (and the IMD products when the file was
     *  captured in IMD mode) from the loaded bins, stops live recording, and
     *  shows it as a static trace in the matching THD / IMD table mode. */
    private void loadSpectrumFft(String path) {
        List<double[]> rows = new ArrayList<>();
        boolean modeImd = false;
        double tone1Hz = Double.NaN;
        double tone2Hz = Double.NaN;
        double binBwHz = Double.NaN;
        try {
            for (String line : Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (s.isEmpty()) continue;
                // Capture-mode / tone header comments (see writeSpectrumFft).
                if (s.startsWith("# mode=")) {
                    modeImd = s.substring(s.indexOf('=') + 1).trim().equalsIgnoreCase("IMD");
                    continue;
                }
                if (s.startsWith("# tone1_hz=")) { tone1Hz = parseHeaderHz(s); continue; }
                if (s.startsWith("# tone2_hz=")) { tone2Hz = parseHeaderHz(s); continue; }
                if (s.startsWith("# bin_bw_hz=")) { binBwHz = parseHeaderHz(s); continue; }
                char c0 = s.charAt(0);
                if (c0 != '-' && c0 != '+' && c0 != '.' && !Character.isDigit(c0)) continue; // header / comment
                String[] p = s.split("[;,]");
                if (p.length < 2) continue;
                double f   = Double.parseDouble(p[0].trim());
                double dbv = Double.parseDouble(p[1].trim());
                double ph  = (p.length >= 3) ? Double.parseDouble(p[2].trim()) : 0.0;
                rows.add(new double[]{ f, dbv, ph });
            }
        } catch (IOException | NumberFormatException ex) {
            showError(I18n.t("fft.load.notImplemented.title"),
                    I18n.t("fft.load.error.read", ex.getMessage()));
            return;
        }
        int n = rows.size();
        double freqRes = (n >= 2) ? (rows.get(n - 1)[0] - rows.get(0)[0]) / (n - 1) : 0.0;
        if (n < 4 || !(freqRes > 0)) {
            showError(I18n.t("fft.load.notImplemented.title"), I18n.t("fft.load.error.format"));
            return;
        }

        FftAnalyzer analyzer = new FftAnalyzer();
        FftResult r = new FftResult();
        Preferences prefs = Preferences.instance();
        int harmCount = Math.max(9, prefs.getFftCalcMaxHarmonic());
        r.ensureArrays(n, harmCount);
        r.freqResolution   = freqRes;
        // Old files lack the bin-bandwidth header; there the row spacing IS the
        // bandwidth the file was captured with.
        r.binBwSqrt        = Math.sqrt((binBwHz > 0) ? binBwHz : freqRes);
        r.fftSize          = 2 * (n - 1);
        r.sampleRate       = (int) Math.round(freqRes * r.fftSize);
        r.harmonicCount    = harmCount;
        r.fundamentalTrueDbFs = Double.NaN;
        r.snrFreqMin = 0; r.snrFreqMax = 0;
        // The file stores dBV; dBFS is the result's base scale, so subtract the
        // global ADC offset once on load (the writer adds it back on save, so a
        // round-trip is exact).
        double dbvOffsetDb = prefs.getDbvOffsetDb();
        for (int k = 0; k < n; k++) {
            double dbv = rows.get(k)[1], ph = rows.get(k)[2];
            double dbFs = dbv - dbvOffsetDb;
            r.amplitudeDbFs[k] = dbFs;
            r.phaseDeg[k]      = ph;
            double lin = Math.pow(10.0, dbFs / 20.0);
            double rad = Math.toRadians(ph);
            r.re[k] = lin * Math.cos(rad);
            r.im[k] = lin * Math.sin(rad);
        }
        // Fundamental = strongest bin above ~10 Hz, refined to sub-bin.
        int halfSize = n - 1;
        int minBin = Math.min(halfSize, Math.max(1, (int) Math.ceil(10.0 / freqRes)));
        int fb = minBin;
        for (int k = minBin; k <= halfSize; k++) if (r.amplitudeDbFs[k] > r.amplitudeDbFs[fb]) fb = k;
        r.fundamentalBin       = fb;
        r.fundamentalHz        = fb * freqRes;
        r.fundamentalHzRefined = MathUtil.parabolicBinInterp(r.re, r.im, fb, r.fftSize) * freqRes;
        // Theoretical harmonic positions for recomputeStats to measure at.
        for (int h = 0; h < harmCount; h++) {
            double hz = (h + 2) * r.fundamentalHzRefined;
            int hb = (int) Math.round(hz / freqRes);
            r.harmonicHz[h]   = hz;
            r.harmonicBins[h] = (hb >= 1 && hb <= halfSize) ? hb : -1;
        }
        analyzer.recomputeStats(r);

        // Recompute the IMD products when the file was captured in IMD mode and
        // carries its two tone frequencies: pin the refined tone positions
        // (F1 = lower) so the result reports the saved frequencies, then measure
        // the products from the loaded spectrum.  A non-null result switches the
        // view to the IMD table; otherwise it stays in single-tone THD mode.
        ImdResult imd = null;
        if (modeImd && Double.isFinite(tone1Hz) && Double.isFinite(tone2Hz)
                && tone1Hz > 0 && tone2Hz > 0) {
            r.fundamentalHzRefined  = Math.min(tone1Hz, tone2Hz);
            r.fundamental2HzRefined = Math.max(tone1Hz, tone2Hz);
            imd = new ImdAnalyzer().analyze(r, tone1Hz, tone2Hz, dbvOffsetDb);
        }

        // Stop live capture so the loaded spectrum isn't overwritten — the pane
        // owns the Record button and shared capture, so request the stop on the
        // bus instead of reaching into it.
        MessageBus.instance().publish(Events.FFT_RECORDING_STOP_REQUESTED);
        view.displayLoadedResult(r, imd);
        view.setSourceFilePath(path);
    }

    /** Parses the value of a {@code # key=value} spectrum-header comment as a
     *  frequency in Hz, returning {@code NaN} when absent or malformed. */
    private double parseHeaderHz(String line) {
        try {
            return Double.parseDouble(line.substring(line.indexOf('=') + 1).trim());
        } catch (NumberFormatException ex) {
            return Double.NaN;
        }
    }

    private void writeSpectrumFft(Path file, FftResult r, boolean imd,
                                 double tone1Hz, double tone2Hz) throws IOException {
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            // Header comments (skipped by the loader's value parser): the capture
            // mode restores the THD or IMD table on re-open, the two tone
            // frequencies let IMD re-measure its products, and the analysis
            // parameters + applied calibrations are informational provenance.
            Preferences prefs = Preferences.instance();
            double dbvOffset = prefs.getDbvOffsetDb();   // dBV = dBFs + global ADC offset
            pw.println("# Phonalyser FFT spectrum");
            pw.println("# format_version=" + FileVersions.FFT_SPECTRUM);
            pw.println("# mode=" + (imd ? "IMD" : "THD"));
            pw.printf("# fft_size=%d%n", r.fftSize);
            pw.printf("# sample_rate_hz=%d%n", r.sampleRate);
            pw.printf("# window=%s%n", r.windowType != null ? r.windowType.name() : "n/a");
            pw.printf("# averaging=%s%n", r.coherentAveraging ? "coherent" : "incoherent");
            pw.printf("# averages=%d%n", r.frameCount);
            if (r.snrFreqMin > 0 || r.snrFreqMax > 0) {
                pw.printf("# snr_freq_min_hz=%.3f%n", r.snrFreqMin);
                pw.printf("# snr_freq_max_hz=%.3f%n", r.snrFreqMax);
            }
            if (Double.isFinite(r.fundamentalTrueDbFs)) {
                pw.printf("# manual_fund_dbv=%.4f%n", r.fundamentalTrueDbFs + dbvOffset);
            }
            pw.printf("# thd_max_harmonic=%d%n", r.harmonicCount);
            if (correctionStore != null) {
                for (FreqRespCorrectionStore.Entry e : correctionStore.getEntries()) {
                    pw.printf("# calibration=%s%s%n",
                            e.getPath(), e.isWithNoise() ? " (withNoise)" : "");
                }
            }
            // Bin bandwidth (Hz) the spectrum was captured with — informational,
            // and the loader derives the V/√Hz scale from it so a re-opened file
            // keeps its own scale instead of the then-live config's.  Live
            // results carry null and write the live-config cache.
            double bwSqrt = (r.binBwSqrt != null) ? r.binBwSqrt : prefs.getBinBwSqrt();
            pw.printf("# bin_bw_hz=%.9f%n", bwSqrt * bwSqrt);
            if (imd) {
                pw.printf("# tone1_hz=%.6f%n", tone1Hz);
                pw.printf("# tone2_hz=%.6f%n", tone2Hz);
            }
            pw.println("frequency_hz;magnitude_dBV;phase_deg");
            for (int k = 0; k < r.amplitudeDbFs.length; k++) {
                double f   = k * r.freqResolution;
                double dbv = r.amplitudeDbFs[k] + dbvOffset;
                double ph  = (r.phaseDeg != null && k < r.phaseDeg.length) ? r.phaseDeg[k] : 0;
                pw.printf("%.6f;%.6f;%.6f%n", f, dbv, ph);
            }
        }
    }

    private void showError(String title, String message) {
        Dialogs.error(getShell(), title, message);
    }

    // =========================================================================
    // Tab-header tiles — the shared TileTabFolder measures / paints; this
    // control only supplies each custom tab's tile content + fallback tooltip.
    // =========================================================================

    /** Builds the live tile row for a custom tab from the current
     *  preferences, in visual order (left to right).  Each tile carries the
     *  short chip text and its state-aware hover tooltip; the
     *  {@link TileTabFolder} measures and paints them. */
    private List<TileTabFolder.Tile> fftTabTiles(int tabIndex) {
        Preferences prefs = Preferences.instance();
        List<TileTabFolder.Tile> tiles = new ArrayList<>();
        if (tabIndex == TAB_FFT_SETTINGS) {
            tiles.add(TileTabFolder.Tile.text(
                    shortFftLength(prefs.getFftLength()),
                    I18n.t("fft.tile.length", prefs.getFftLength())));
            tiles.add(TileTabFolder.Tile.text(
                    prefs.getFftWindow().name(),
                    I18n.t("fft.tile.window", I18n.t(prefs.getFftWindow().labelKey()))));
            tiles.add(TileTabFolder.Tile.text(
                    prefs.getFftOverlap().label,
                    I18n.t("fft.tile.overlap", prefs.getFftOverlap().label)));
            tiles.add(TileTabFolder.Tile.text(
                    formatAverages(prefs.getFftAverages()) + "×",
                    I18n.t("fft.tile.averages", formatAverages(prefs.getFftAverages()))));
            tiles.add(TileTabFolder.Tile.text(
                    prefs.isFftCoherentAveraging() ? "coh" : "inc",
                    I18n.t(prefs.isFftCoherentAveraging() ? "fft.tile.coh" : "fft.tile.inc")));
        } else if (tabIndex == TAB_THD_SETTINGS) {
            String dr = shortDistRange(prefs);
            tiles.add(TileTabFolder.Tile.text(dr, I18n.t("fft.tile.distRange", dr)));
            tiles.add(TileTabFolder.Tile.text(
                    "H" + prefs.getFftThdMaxHarmonic(),
                    I18n.t("fft.tile.thdMaxHarmonic", prefs.getFftThdMaxHarmonic())));
            if (prefs.isFftManualFundEnabled()) {
                tiles.add(TileTabFolder.Tile.text("manF", I18n.t("fft.tile.manualFund")));
            }
        } else if (tabIndex == TAB_CALIBRATION) {
            // "loaded" / "N loaded" when at least one loaded .frc is active —
            // the store only holds active+loaded entries (see
            // syncFftStoreFromRows), mirroring the FreqResp pane's tile.
            int n = correctionStore.getEntries().size();
            if (n == 1) {
                String s = I18n.t("calibration.tile.loaded");
                tiles.add(TileTabFolder.Tile.text(s, s));
            } else if (n > 1) {
                String s = I18n.t("calibration.tile.loadedN", n);
                tiles.add(TileTabFolder.Tile.text(s, s));
            }
        }
        return tiles;
    }

    /** Tab-level hover tooltip key — fallback when no tile is hovered. */
    private String tabLabelTooltipKey(int tabIndex) {
        switch (tabIndex) {
            case TAB_FFT_SETTINGS: return "fft.tab.settings.tooltip";
            case TAB_THD_SETTINGS: return "fft.tab.thd.tooltip";
            default:               return null;
        }
    }

    // =========================================================================
    // Pure text / number parsers + formatters for the FFT settings fields.
    // Folded in from the former FftPaneFormat — used only here.
    // =========================================================================

    /** Averages presets used by the cycling stepper (wheel / arrows). */
    private String shortFftLength(int n) {
        if (n >= 1 << 20) return (n >> 20) + "M";
        if (n >= 1 << 10) return (n >> 10) + "k";
        return Integer.toString(n);
    }

    private String shortDistRange(Preferences prefs) {
        if (!prefs.isFftDistMinEnabled() && !prefs.isFftDistMaxEnabled()) return "all";
        String lo = prefs.isFftDistMinEnabled() ? shortHz(prefs.getFftDistMinHz()) : "0";
        String hi = prefs.isFftDistMaxEnabled() ? shortHz(prefs.getFftDistMaxHz()) : "∞";
        return lo + "-" + hi;
    }

    private String shortHz(double f) {
        if (f >= 1000) return ((int) Math.round(f / 1000)) + "k";
        return Long.toString(Math.round(f));
    }

    private int indexOfInt(int[] arr, int value) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == value) return i;
        return -1;
    }

    /** Formats an averages value — {@code +Infinity} → {@code "∞"},
     *  finite values → plain integer string. */
    private String formatAverages(double v) {
        if (Double.isInfinite(v)) return "∞";
        return Long.toString((long) Math.round(v));
    }

    // =========================================================================
    // Load-calibration tab — multi-row .frc loader (mirrors FreqRespPane).
    // =========================================================================

    private static final class FftCalRow {
        Composite                composite;
        Text                     pathField;
        /** "Active" toggle — two-way bound to {@code entry.active()}; the
         *  calibration is only added to {@link FreqRespCorrectionStore} when this
         *  is checked AND a file is loaded. */
        Button                   activeCheck;
        /** "With noise" toggle — two-way bound to {@code entry.withNoise()};
         *  when checked this row's correction is applied to every FFT bin
         *  (noise floor included).  Only meaningful when active AND a file is
         *  loaded; disabled otherwise. */
        Button                   noiseCheck;
        StereoFreqRespCalibration calibration;
        /** Source of truth for path + Active + With-noise; lives in
         *  {@code Preferences.getFftCalibrations()}. */
        CalibrationEntry         entry;

        private FftCalRow() {
        }
    }

    private void buildCalibrationTab(CTabFolder folder) {
        CTabItem item = new CTabItem(folder, SWT.NONE);
        item.setText(I18n.t("fft.tab.calibration"));

        fftCalRowsScroll = new ScrolledComposite(folder, SWT.V_SCROLL);
        fftCalRowsScroll.setExpandHorizontal(true);
        fftCalRowsScroll.setExpandVertical(true);
        item.setControl(fftCalRowsScroll);

        fftCalRowsContainer = new Composite(fftCalRowsScroll, SWT.NONE);
        fftCalRowsScroll.setContent(fftCalRowsContainer);
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 8; gl.marginHeight = 6;
        gl.verticalSpacing = 4;
        fftCalRowsContainer.setLayout(gl);

        Preferences prefs = Preferences.instance();
        List<CalibrationEntry> cals = prefs.getFftCalibrations();
        if (cals.isEmpty()) {
            prefs.addFftCalibration(new CalibrationEntry());  // row 0 always present
        }
        for (CalibrationEntry entry : cals) {
            FftCalRow r = createFftCalRowUi(entry);
            String p = entry.getPath();
            if (p != null && !p.isEmpty()) loadFileIntoFftCalRow(r, p, false);
            updateFftCalRowEnable(r);
        }
        // The store is populated from these rows by the syncFftStoreFromRows()
        // call at the end of the constructor, after every tab is built.
    }

    /** Builds and appends a fresh row.  7-column grid: pathField,
     *  activeCheck, noiseCheck, load, clear, add, remove.  Remove is
     *  {@code setVisible(false)} on row 0 so its column stays reserved
     *  and the buttons line up vertically across rows.
     *
     *  <p>The two checkboxes feed {@link #syncFftStoreFromRows}:
     *  <ul>
     *    <li>"Active" — entry is only added to the calibration store
     *        when checked AND a file is loaded.  Toggling it
     *        rebuilds the store immediately.</li>
     *    <li>"With noise" — when checked, this row's correction is
     *        applied to every FFT bin (noise floor included);
     *        otherwise only harmonic / dot bins are corrected.  Only
     *        meaningful when Active is on; disabled otherwise.</li>
     *  </ul> */
    private FftCalRow createFftCalRowUi(CalibrationEntry entry) {
        boolean isRow0 = fftCalRows.isEmpty();

        Composite row = new Composite(fftCalRowsContainer, SWT.NONE);
        row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout rl = new GridLayout(7, false);
        rl.marginWidth = 0; rl.marginHeight = 0; rl.horizontalSpacing = 6;
        row.setLayout(rl);

        Text pathField = new Text(row, SWT.BORDER | SWT.READ_ONLY);
        GridData pgd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        pgd.widthHint = 320;
        pathField.setLayoutData(pgd);
        pathField.setText(I18n.t("freqResp.calibration.path.none"));
        pathField.setToolTipText(I18n.t("fft.calibration.path.tooltip"));

        Button activeCheck = new Button(row, SWT.CHECK);
        activeCheck.setText(I18n.t("fft.calibration.active"));
        activeCheck.setToolTipText(I18n.t("fft.calibration.active.tooltip"));

        Button noiseCheck = new Button(row, SWT.CHECK);
        noiseCheck.setText(I18n.t("fft.calibration.withNoise"));
        noiseCheck.setToolTipText(I18n.t("fft.calibration.withNoise.tooltip"));

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

        Image minus = IconUtils.instance().render(
                row.getDisplay(), SvgPaths.MINUS, 16, 16,
                new RGB(0xC8, 0x28, 0x28));
        Button removeBtn = new Button(row, SWT.PUSH);
        if (minus != null) removeBtn.setImage(minus);
        removeBtn.setToolTipText(I18n.t("freqResp.calibration.remove.tooltip"));
        if (isRow0) removeBtn.setVisible(false);

        FftCalRow r = new FftCalRow();
        r.composite   = row;
        r.pathField   = pathField;
        r.activeCheck = activeCheck;
        r.noiseCheck  = noiseCheck;
        r.entry       = entry;
        fftCalRows.add(r);
        updateFftCalRowEnable(r);

        Bindings.check(activeCheck, entry.active());
        Bindings.check(noiseCheck,  entry.withNoise());
        Bindings.onChange(activeCheck, entry.active(), v -> {
            updateFftCalRowEnable(r);
            syncFftStoreFromRows();
        });
        Bindings.onChange(noiseCheck, entry.withNoise(), v -> syncFftStoreFromRows());

        loadBtn.addListener(SWT.Selection,  e -> userLoadInFftCalRow(r));
        clearBtn.addListener(SWT.Selection, e -> userClearFftCalRow(r));
        addBtn.addListener(SWT.Selection,   e -> userAddFftCalRow());
        if (!isRow0) removeBtn.addListener(SWT.Selection, e -> userRemoveFftCalRow(r));

        relayoutFftCalRows();
        return r;
    }

    /** Keeps the row's two checkboxes' enabled state in sync with the
     *  current row state — Active needs a loaded file, With-noise
     *  needs both a file and an active row.  Called from row
     *  creation, file load, file clear, and the Active checkbox
     *  listener. */
    private void updateFftCalRowEnable(FftCalRow r) {
        boolean fileLoaded = r.entry.getPath() != null && r.calibration != null;
        if (r.activeCheck != null && !r.activeCheck.isDisposed()) {
            r.activeCheck.setEnabled(fileLoaded);
        }
        if (r.noiseCheck != null && !r.noiseCheck.isDisposed()) {
            r.noiseCheck.setEnabled(fileLoaded && r.entry.active().get());
        }
    }

    private void relayoutFftCalRows() {
        if (fftCalRowsContainer != null && !fftCalRowsContainer.isDisposed()) {
            fftCalRowsContainer.layout(true, true);
        }
        if (fftCalRowsScroll != null && !fftCalRowsScroll.isDisposed() && fftCalRowsContainer != null) {
            fftCalRowsScroll.setMinSize(fftCalRowsContainer.computeSize(SWT.DEFAULT, SWT.DEFAULT));
        }
    }

    private void userLoadInFftCalRow(FftCalRow r) {
        Preferences prefs = Preferences.instance();
        FileDialog fd = new FileDialog(getShell(), SWT.OPEN);
        fd.setText(I18n.t("freqResp.calibration.dialog"));
        fd.setFilterExtensions(new String[]{ "*.frc", "*.csv", "*.*" });
        String memFolder = prefs.getFftLoadFolder();
        if (memFolder != null) fd.setFilterPath(memFolder);
        String picked = fd.open();
        if (picked == null) return;
        if (!loadFileIntoFftCalRow(r, picked, true)) return;
        String parent = new File(picked).getParent();
        if (parent != null) prefs.setFftLoadFolder(parent);
        syncFftStoreFromRows();
        prefs.save();
    }

    private void userClearFftCalRow(FftCalRow r) {
        r.calibration = null;
        r.entry.setPath(null);
        r.pathField.setText(I18n.t("freqResp.calibration.path.none"));
        r.pathField.setToolTipText(null);
        // Clearing the file forces the two checkboxes back to disabled
        // (their "applies when active && file selected" rule).  Active
        // stays SELECTED in the UI so the row re-applies as soon as
        // the user picks a new file — that's the user's stated intent:
        // "if checkbox active and file not loaded, apply once it loads".
        updateFftCalRowEnable(r);
        syncFftStoreFromRows();
        Preferences.instance().save();
    }

    private void userAddFftCalRow() {
        CalibrationEntry entry = new CalibrationEntry();
        Preferences.instance().addFftCalibration(entry);
        createFftCalRowUi(entry);
    }

    private void userRemoveFftCalRow(FftCalRow r) {
        if (fftCalRows.size() <= 1) return;
        int idx = fftCalRows.indexOf(r);
        if (idx <= 0) return;
        fftCalRows.remove(idx);
        Preferences.instance().removeFftCalibration(r.entry);
        r.composite.dispose();
        relayoutFftCalRows();
        syncFftStoreFromRows();
    }

    private boolean loadFileIntoFftCalRow(FftCalRow r, String picked, boolean showErrors) {
        try {
            StereoFreqRespCalibration cal = FreqRespCalHelper.loadCsv(picked);
            r.calibration = cal;
            r.entry.setPath(picked);
            r.pathField.setText(picked);
            r.pathField.setToolTipText(picked);
            // File just loaded — refresh the checkboxes' enabled state
            // (Active becomes enable-able; With-noise follows Active).
            updateFftCalRowEnable(r);
            return true;
        } catch (Exception ex) {
            log.warn("FFT calibration load failed: {}", picked, ex);
            if (showErrors) {
                Dialogs.error(getShell(),
                        I18n.t("freqResp.calibration.dialog"),
                        I18n.t("freqResp.error.calibration.load").replace("{0}",
                                ex.getMessage() != null ? ex.getMessage()
                                                        : ex.getClass().getSimpleName()));
            }
            return false;
        }
    }

    private void syncFftStoreFromRows() {
        correctionStore.clearAll();
        for (FftCalRow r : fftCalRows) {
            // Only push entries that have a loaded file AND the user
            // has checked "Active" — the per-row With-noise flag rides
            // with the entry into the store.
            if (r.calibration != null && r.entry.getPath() != null && r.entry.active().get()) {
                correctionStore.addEntry(r.calibration, r.entry.getPath(), r.entry.withNoise().get());
            }
        }
        // Reflect the active-loaded count in the Load-calibration tab tile.
        if (toolbarTabs != null) toolbarTabs.refreshTab(TAB_CALIBRATION);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Composite groupCell(CTabFolder folder, String title) {
        Composite c = new Composite(folder, SWT.NONE);
        CTabItem item = new CTabItem(folder, SWT.NONE);
        item.setText(title);
        item.setControl(c);
        return c;
    }

    private void addLabel(Composite parent, String text) {
        Label l = new Label(parent, SWT.NONE);
        l.setText(text);
        l.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
    }
}
