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

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.edgo.audio.measure.bind.Property;
import org.edgo.audio.measure.enums.AlignGenerator;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.FundamentalPhaseAlignment;
import org.edgo.audio.measure.enums.GenChangeCause;
import org.edgo.audio.measure.enums.WindowType;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.fft.ImdResult;
import org.edgo.audio.measure.gui.bind.Bindings;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.AbstractCalibrationTabControl;
import org.edgo.audio.measure.gui.common.CalibrationDialog;
import org.edgo.audio.measure.gui.common.Dialogs;
import org.edgo.audio.measure.gui.common.Icon;
import org.edgo.audio.measure.gui.common.IconUtils;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.widgets.NumericStepField;
import org.edgo.audio.measure.gui.widgets.NumericStepModel;
import org.edgo.audio.measure.gui.widgets.PresetBar;
import org.edgo.audio.measure.gui.widgets.TileTabFolder;
import org.edgo.audio.measure.gui.widgets.UnitFamily;
import org.edgo.audio.measure.preferences.FftPreset;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.DeviceCalibration;

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
 *   <li>{@link Events#FFT_SCREENSHOT_REQUESTED} - the Utility tab's camera
 *       button; the pane owns the screenshot dialog (it clones itself
 *       offscreen) and subscribes.</li>
 *   <li>{@link Events#FFT_RECORDING_STOP_REQUESTED} - loading a spectrum file
 *       must stop live recording so the static trace isn't overwritten; the
 *       pane owns the Record button and subscribes.</li>
 * </ul>
 * The {@link FftView} is shared with the pane and passed in at construction.
 */
@Log4j2
public final class FftTabControl extends AbstractCalibrationTabControl<FftPane> {

    /** Message-key prefix of the calibration tab's texts. */
    private static final String CALIBRATION_KEY_PREFIX = "fft";

    /** Powers-of-2 from 2^13 (8192) to 2^22 (4,194,304) with their labels. */
    private static final int[]    FFT_LENGTH_VALUES = {
            8192, 16384, 32768, 65536, 131072, 262144,
            524288, 1048576, 2097152, 4194304
    };
    private static final String[] FFT_LENGTH_LABELS = {
            "8k", "16k", "32k", "64k", "128k", "256k", "512k", "1M", "2M", "4M"
    };
    /** Heaps below this are 32-bit-class: combo entries above
     *  {@link #SMALL_HEAP_MAX_FFT_LENGTH} are not offered.  1.5 GiB sits
     *  between the 32-bit ceiling (~1.2-1.4 GB usable) and any serious
     *  64-bit {@code -Xmx}. */
    private static final long SMALL_HEAP_BYTES = 1_610_612_736L;
    /** Largest FFT length offered on a small heap: the 4M analysis working
     *  set (analyzer scratch, result slots, frame cache, window buffers -
     *  ~1.3 GB at high rates) cannot fit a 32-bit JVM, while 2M (~600 MB)
     *  does. */
    private static final int SMALL_HEAP_MAX_FFT_LENGTH = 2_097_152;

    /** Indices of the toolbar tabs.  The first {@link #NUM_CUSTOM_TABS} are
     *  custom-rendered (label + optional tile row); the Presets / Utility tabs
     *  in that range simply carry no tiles (centred label), matching the
     *  FreqResp pane.  Save / Load (live-capture only) render with the default
     *  CTabFolder look. */
    private static final int TAB_FFT_SETTINGS = 0;
    private static final int TAB_THD_SETTINGS = 1;
    private static final int TAB_PRESETS      = 2;
    private static final int TAB_CALIBRATION  = 4;
    private static final int NUM_CUSTOM_TABS  = 5;

    /** Off is a distinct dial position BELOW 1: both 0 and 1 mean a single
     *  spectrum / no averaging in the engine (the worker only accumulates from
     *  2 up), but on the dial 0 is the Off stop and 1 is a plain single count.
     *  Heads {@link #AVERAGES_SERIES} so the wheel / arrows can reach it; renders
     *  and parses as the shared Off label. */
    private static final double AVERAGES_OFF          = 0;
    /** Averages presets the field's wheel / arrows jump along, from Off up
     *  (Off ↔ 1 ↔ 2 ↔ ...); ∞ = forever. */
    private static final double[] AVERAGES_SERIES =
            { AVERAGES_OFF, 1, 2, 4, 8, 16, 32, 64, 128, Double.POSITIVE_INFINITY };
    /** Stop-after-N bounds and wheel step (arrows step by 1). */
    private static final double STOP_AFTER_MIN        = 2;
    private static final double STOP_AFTER_MAX        = 1_000_000;
    private static final double STOP_AFTER_WHEEL_STEP = 100;
    /** Manual-fundamental ceiling (Vrms): declared external levels - e.g. a
     *  power amplifier measured through a divider - can far exceed the DAC
     *  full-scale. */
    private static final double MANUAL_FUND_MAX_VRMS  = 200.0;
    /** Amplitude floor (Vrms) - keeps log-unit (dBV) entry finite. */
    private static final double AMP_MIN_VRMS          = 1e-9;
    /** Representative measured amplitude (Vrms) prefilled into the analyzed-channel
     *  row when the ADC calibration dialog is opened for a help screenshot, so the
     *  two-row form renders fully without a live measurement. */
    private static final double CAPTURE_ADC_VRMS      = 1.0;
    /** Harmonic-count bounds: THD measures H2...H9; the calc ceiling feeds the
     *  compensation workflows. */
    private static final double THD_HARM_MIN  = 2;
    private static final double THD_HARM_MAX  = 9;
    private static final double CALC_HARM_MIN = 9;
    private static final double CALC_HARM_MAX = 50;
    // Display precision caps per the numeric-field spec.
    private static final int FREQ_MAX_DECIMALS = 9;
    private static final int AMP_MAX_DECIMALS  = 5;

    private final FftView view;

    /** Controller owning the {@code .fft} spectrum file round-trip (and the
     *  analyser lifecycle); constructor-injected by {@link FftPane}. */
    private final FftController controller;

    /** The offscreen screenshot copy's control: it neither publishes nor
     *  subscribes on the bus. */
    private final boolean offScreen;

    // Tab-header tile rendering: the shared TileTabFolder (held by
    // AbstractTabControl as toolbarTabs) owns the renderer, spacer images,
    // collapse, hover tooltips and tile painting; this control only feeds it
    // tile content (see fftTabTiles).

    // Widget references for preset apply/refresh - captured at build time.
    private Combo              fftLengthCombo;
    private Combo              windowCombo;
    private Combo              overlapCombo;
    private NumericStepField   averagesField;
    private Button             stopAfterNEnable;
    private NumericStepField   stopAfterNField;
    private Button             fundFromGenCheck;
    private Button             logFreqCheck;
    private Button             detectTimeDiscCheck;
    private Button             coherentCheck;
    /** "Align generator" - None / PID / FLL - selects the FFT-side
     *  frequency-alignment loop.  Enabled in the UI only when both
     *  snap-to-FFT-bin and fund-from-generator are on, since the loop
     *  needs a target bin (snap) and a target frequency (fund-from-gen)
     *  to lock onto.  Even when a mode is selected the loop only fires if
     *  the other two are also on - see {@code FftAnalyzerWorker.applyFrequencyLock}. */
    private Combo              alignGenCombo;
    private Button             distMinEnable;
    private NumericStepField   distMinField;
    private Button             distMaxEnable;
    private NumericStepField   distMaxField;
    private NumericStepField   thdMaxHarmField;
    private NumericStepField   calcMaxHarmField;
    private Button             manualFundEnable;
    private NumericStepField   manualFundField;

    /** The combo's offered subset of {@link #FFT_LENGTH_VALUES} /
     *  {@link #FFT_LENGTH_LABELS} - truncated at
     *  {@link #SMALL_HEAP_MAX_FFT_LENGTH} on a small heap, the full list
     *  otherwise. */
    private final int[]    offeredFftLengths;
    private final String[] offeredFftLengthLabels;

    public FftTabControl(Composite parent, FftView view, boolean offScreen,
                         FftController controller, FftPane owner) {
        super(parent, SWT.NONE, owner, controller.getCorrectionStore(),
                Preferences.instance().getFftCalibrations(),
                Preferences.instance().fftLoadFolderProperty(),
                CALIBRATION_KEY_PREFIX, true);
        this.view = view;
        this.controller = controller;
        this.offScreen = offScreen;
        int lengthCap = Runtime.getRuntime().maxMemory() < SMALL_HEAP_BYTES
                ? SMALL_HEAP_MAX_FFT_LENGTH : Integer.MAX_VALUE;
        int offered = 0;
        while (offered < FFT_LENGTH_VALUES.length && FFT_LENGTH_VALUES[offered] <= lengthCap) {
            offered++;
        }
        this.offeredFftLengths      = Arrays.copyOf(FFT_LENGTH_VALUES, offered);
        this.offeredFftLengthLabels = Arrays.copyOf(FFT_LENGTH_LABELS, offered);

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
        buildCalibrationTab();
        if (!offScreen) {
            buildSaveToTab(tabs);
            buildLoadFromTab(tabs);
        }
        if (tabs.getItemCount() > 0) tabs.setSelection(0);
        // Capture labels, size the strip / spacer images and wire the
        // paint, hover and collapse listeners now that the tabs exist.
        tabs.init();

        if (!offScreen) subscribeLive();

        wireHelpAnchors();

        followCalibrations();
    }

    /** The live control's bus subscriptions, each unsubscribed on dispose. */
    private void subscribeLive() {
        // Snap-to-FFT-bin lives on the generator pane and is the missing
        // prerequisite for "Align generator"; refresh the combo's enabled
        // state whenever the generator signal changes.  The bus is UI-only -
        // every publisher of this event is on the display thread - so a plain
        // subscription is correct; unsubscribed on dispose below.
        Consumer<GenChangeCause> genChangedListener = cause -> {
            if (isDisposed()) return;
            updateAlignGenEnabled();
        };
        MessageBus.instance().subscribe(Events.GENERATOR_SIGNAL_CHANGED, genChangedListener);
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
            MessageBus.instance().unsubscribe(Events.GENERATOR_SIGNAL_CHANGED, genChangedListener);
            MessageBus.instance().unsubscribe(Events.AUDIO_FORMAT_CHANGED, audioFormatListener);
        });
    }

    // -------------------------------------------------------------------------
    // Pane delegation
    // -------------------------------------------------------------------------

    /** Registers each settings tab in the component registry under
     *  {@code prefix} (e.g. {@code "multifunctional/fft/tabs"}) so automation
     *  can select a tab by path - {@code activate} expands the tab body and
     *  selects that tab - then screenshot this tab control showing it.  Slugs
     *  are language-independent; tabs not built (Save / Load without live
     *  capture) are skipped. */
    public void registerTabs(String prefix) {
        registerTab(prefix, "settings",    0);
        registerTab(prefix, "thd",         1);
        registerTab(prefix, "presets",     2);
        registerTab(prefix, "utility",     3);
        registerTab(prefix, "calibration", 4);
        registerTab(prefix, "save",        5);
        registerTab(prefix, "load",        6);
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
        if (detectTimeDiscCheck != null) detectTimeDiscCheck.setData("helpAnchor", "fft.html#fft-detect-time-discontinuity");
        if (coherentCheck       != null) coherentCheck      .setData("helpAnchor", "fft.html#fft-coherent-avg");
        if (distMinEnable       != null) distMinEnable      .setData("helpAnchor", "fft.html#fft-dist-min");
        if (distMinField        != null) distMinField       .setData("helpAnchor", "fft.html#fft-dist-min");
        if (distMaxEnable       != null) distMaxEnable      .setData("helpAnchor", "fft.html#fft-dist-max");
        if (distMaxField        != null) distMaxField       .setData("helpAnchor", "fft.html#fft-dist-max");
        if (manualFundEnable    != null) manualFundEnable   .setData("helpAnchor", "fft.html#fft-manual-fund");
        if (manualFundField     != null) manualFundField    .setData("helpAnchor", "fft.html#fft-manual-fund");
        if (thdMaxHarmField     != null) thdMaxHarmField    .setData("helpAnchor", "fft.html#fft-max-harmonic-thd");
        if (calcMaxHarmField    != null) calcMaxHarmField   .setData("helpAnchor", "fft.html#fft-max-harmonic-calc");
        // Preset bar widgets carry their own help anchor (set by PresetBar).
    }

    /** Enables / disables the "Stop after N" checkbox + numeric field
     *  based on the current averages setting.  Stop-after only makes
     *  sense when averages is set to "forever" - a finite N already
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

    private void buildSettingsTab(TileTabFolder folder) {
        Composite g = groupCell(folder, "fft.tab.settings");
        GridLayout gl = new GridLayout(4, false);
        gl.marginWidth = 6; gl.marginHeight = 4;
        gl.horizontalSpacing = 2; gl.verticalSpacing = 4;
        g.setLayout(gl);
        Preferences prefs = Preferences.instance();

        addLabel(g, "fft.settings.length");
        fftLengthCombo = new Combo(g, SWT.READ_ONLY);
        fftLengthCombo.setItems(offeredFftLengthLabels);
        fftLengthCombo.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        Bindings.text(fftLengthCombo, () -> {
            String lengthTip = I18n.t("fft.settings.length.tooltip");
            if (offeredFftLengths.length < FFT_LENGTH_VALUES.length) {
                lengthTip += "\n" + I18n.t("fft.settings.length.heapCapped");
            }
            return lengthTip;
        }, fftLengthCombo::setToolTipText);
        // Index-mapped combo: the selection index maps through FFT_LENGTH_VALUES
        // to the actual length, so it can't use the ordinal-based Bindings.combo.
        // The two-way mirror is hand-wired but follows the same contract: seed
        // from the pref, write the pref on input, re-select on an external change
        // (preset load).  Default index 3 (64k) when the saved length is unknown.
        bindFftLengthCombo(prefs.fftLengthProperty());
        // Pane-local effect - refresh the FFT-settings tab tile.  The view's own
        // resetStatistics is driven by the view subscribing to the same pref.
        Bindings.onChange(toolbarTabs, prefs.fftLengthProperty(),
                v -> toolbarTabs.refreshTab(TAB_FFT_SETTINGS));
        // Generator's bin snap is anchored to fftLength via sampleRate /
        // fftLength - broadcast the change so the generator pane can re-snap its
        // running tone onto a fresh bin.  The new length is already in
        // Preferences; subscribers read it from there.
        if (!offScreen) {
            Bindings.onChange(toolbarTabs, prefs.fftLengthProperty(),
                    v -> MessageBus.instance().publish(Events.FFT_LENGTH_CHANGED));
        }

        addLabel(g, "fft.settings.window");
        windowCombo = new Combo(g, SWT.READ_ONLY);
        Bindings.items(windowCombo, () -> Arrays.stream(WindowType.values())
                .map(w -> I18n.t(w.labelKey())).toArray(String[]::new));
        windowCombo.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        Bindings.text(windowCombo, "fft.settings.window.tooltip", windowCombo::setToolTipText);
        Bindings.combo(windowCombo, prefs.fftWindowProperty(), WindowType.values());
        Bindings.onChange(toolbarTabs, prefs.fftWindowProperty(), w -> toolbarTabs.refreshTab(TAB_FFT_SETTINGS));

        addLabel(g, "fft.settings.overlap");
        overlapCombo = new Combo(g, SWT.READ_ONLY);
        for (FftOverlap ov : FftOverlap.values()) overlapCombo.add(ov.label);
        overlapCombo.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        Bindings.text(overlapCombo, "fft.settings.overlap.tooltip", overlapCombo::setToolTipText);
        Bindings.combo(overlapCombo, prefs.fftOverlapProperty(), FftOverlap.values());
        // Overlap only changes the hop, not the spectrum/accumulator - refresh
        // the tab tile but DON'T reset the average; the worker adapts next
        // analysis cycle.
        Bindings.onChange(toolbarTabs, prefs.fftOverlapProperty(),
                v -> toolbarTabs.refreshTab(TAB_FFT_SETTINGS));

        addLabel(g, "fft.settings.averages");
        // List stepper: wheel / arrow keys snap to the next / previous
        // preset (Off, 1, 2 ... 128, ∞) while manual typing still accepts any
        // count ≥ 0 (and the ∞ / inf token, since max is unbounded).
        averagesField = new NumericStepField(g, UnitFamily.NONE,
                AVERAGES_OFF, Double.POSITIVE_INFINITY, AVERAGES_SERIES, 0, 70);
        // 0 renders and parses as "Off" - typed in full or as any prefix
        // (o / of / off), the same shortcut the generator's dither field takes;
        // 1 shows as a plain "1".
        averagesField.setNamedValue(AVERAGES_OFF, NumericStepModel.OFF_LABEL);
        averagesField.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        Bindings.text(averagesField, "fft.settings.averages.tooltip", averagesField::setToolTipText);
        Bindings.stepField(averagesField, prefs.fftAveragesProperty());
        // Pane-local effects only - the tab tile and the stop-after gate.  No
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
        // FFT-length-label cell).  Checkbox carries its own text now -
        // a separate Label was creating an extra gap.
        Composite stopRow = new Composite(g, SWT.NONE);
        GridData stopRowGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        stopRowGd.horizontalSpan = 2;
        stopRow.setLayoutData(stopRowGd);
        RowLayout stopRowLayout = new RowLayout(SWT.HORIZONTAL);
        stopRowLayout.marginTop    = 0; stopRowLayout.marginBottom = 0;
        stopRowLayout.marginLeft   = 0; stopRowLayout.marginRight  = 0;
        stopRowLayout.spacing      = 4;
        stopRowLayout.center       = true;
        stopRow.setLayout(stopRowLayout);

        stopAfterNEnable = new Button(stopRow, SWT.CHECK);
        Bindings.text(stopAfterNEnable, "fft.settings.stopAfterN", stopAfterNEnable::setText);
        Bindings.text(stopAfterNEnable, "fft.settings.stopAfterN.tooltip", stopAfterNEnable::setToolTipText);
        Bindings.check(stopAfterNEnable, prefs.fftStopAfterNEnabledProperty());
        stopAfterNField = new NumericStepField(stopRow, UnitFamily.NONE,
                STOP_AFTER_MIN, STOP_AFTER_MAX, STOP_AFTER_WHEEL_STEP, 1, 0, 70);
        Bindings.text(stopAfterNField, "fft.settings.stopAfterN.tooltip", stopAfterNField::setToolTipText);
        Bindings.stepFieldInt(stopAfterNField, prefs.fftStopAfterNProperty());
        // Initial enabled state reflects both "stop-after" toggle AND the rule
        // that the row is only meaningful when averages is "forever" (a finite N
        // already implements a moving window).  The companion field's enabled
        // state depends on BOTH the toggle and averages==forever, so the enable
        // recompute is centralized in refreshStopAfterEnable() - driven here off
        // the toggle pref and from the averages listener above.  Don't reset the
        // average: the worker stops as soon as the collected count reaches N.
        refreshStopAfterEnable();
        Bindings.onChange(toolbarTabs, prefs.fftStopAfterNEnabledProperty(),
                v -> refreshStopAfterEnable());

        // Fundamental-phase-alignment selector sits in the outer grid's
        // right-hand column pair (col 2 label + col 3 combo) - sharing the
        // stop-after grid row but lining up with Window / Averages above and
        // Align generator below, instead of floating after the stop-after
        // field.  Behind a notch whose centre frequency drifts, the
        // fundamental's phase decoheres from its harmonics - the selected mode
        // gives the coherent average a drift-free time base.  Items are in
        // FundamentalPhaseAlignment order, which is what Bindings.combo maps
        // the selection index onto.  (The mains-suppression preference has no
        // control on this tab; it keeps its stored value.)
        addLabel(g, "fft.settings.phaseAlign");
        Combo phaseAlignCombo = new Combo(g, SWT.READ_ONLY);
        Bindings.items(phaseAlignCombo, () -> new String[] {
                I18n.t("fft.settings.phaseAlign.none"),
                I18n.t("fft.settings.phaseAlign.reference"),
                I18n.t("fft.settings.phaseAlign.pilot") });
        phaseAlignCombo.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        Bindings.text(phaseAlignCombo, "fft.settings.phaseAlign.tooltip", phaseAlignCombo::setToolTipText);
        Bindings.combo(phaseAlignCombo, prefs.fftFundamentalPhaseAlignmentProperty(),
                FundamentalPhaseAlignment.values());
        // Reference-channel alignment reads the second input, which a combined
        // channel delivers as 0: while combined, picking it falls back to none.
        // Runs after the binding's own Selection listener, so the property is
        // set again only after its first notification has finished.
        phaseAlignCombo.addListener(SWT.Selection, e -> {
            if (prefs.getFftCombinedMaster() != null
                    && prefs.getFftFundamentalPhaseAlignment() == FundamentalPhaseAlignment.REFERENCE_CHANNEL) {
                prefs.setFftFundamentalPhaseAlignment(FundamentalPhaseAlignment.NONE);
            }
        });

        // The boolean knobs share their grid rows with the selectors so the tab
        // content stays as close to the typical FFT-pane height as it can (the
        // previous 3-rows-of-spans-4 layout pushed the third checkbox below the
        // visible CTabFolder client area).
        // Row: Get fundamental from generator (span 2) | Align generator
        fundFromGenCheck = new Button(g, SWT.CHECK);
        Bindings.text(fundFromGenCheck, "fft.settings.fundFromGen", fundFromGenCheck::setText);
        GridData fgGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        fgGd.horizontalSpan = 2;
        fundFromGenCheck.setLayoutData(fgGd);
        Bindings.text(fundFromGenCheck, "fft.settings.fundFromGen.tooltip", fundFromGenCheck::setToolTipText);
        Bindings.check(fundFromGenCheck, prefs.fftFundFromGeneratorProperty());

        // "Align generator" - None / FLL - only meaningful when both
        // snap-to-bin (provides a target bin) and fund-from-generator (provides a
        // target frequency) are on, so the combo is disabled otherwise.  Even when
        // a mode is selected the loop only fires if the other two are also on - see
        // FftAnalyzerWorker.applyFrequencyLock.
        Label algLbl = new Label(g, SWT.NONE);
        Bindings.text(algLbl, "fft.settings.alignGen", algLbl::setText);
        algLbl.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        alignGenCombo = new Combo(g, SWT.DROP_DOWN | SWT.READ_ONLY);
        for (AlignGenerator ag : AlignGenerator.values()) {
            alignGenCombo.add(ag.label);
        }
        alignGenCombo.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        Bindings.text(alignGenCombo, "fft.settings.alignGen.tooltip", alignGenCombo::setToolTipText);
        // Selecting an active mode resets its loop so each session converges
        // fresh; NONE deliberately holds everything - that side-effect lives in
        // the view, which subscribes to this same pref.  Here it's a plain
        // two-way value bind.
        Bindings.combo(alignGenCombo, prefs.fftAlignGeneratorProperty(), AlignGenerator.values());

        // Row: Coherent averaging (span 2) | Log freq | Detect time discontinuity
        coherentCheck = new Button(g, SWT.CHECK);
        Bindings.text(coherentCheck, "fft.settings.coherent", coherentCheck::setText);
        GridData cohGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        cohGd.horizontalSpan = 2;
        coherentCheck.setLayoutData(cohGd);
        Bindings.text(coherentCheck, "fft.settings.coherent.tooltip", coherentCheck::setToolTipText);
        Bindings.check(coherentCheck, prefs.fftCoherentAveragingProperty());
        // Pane-local tab-tile refresh; the accumulator reset (coherent ↔
        // incoherent changes its semantics) lives in the view, which subscribes
        // to this same pref.
        Bindings.onChange(toolbarTabs, prefs.fftCoherentAveragingProperty(),
                v -> toolbarTabs.refreshTab(TAB_FFT_SETTINGS));
        // Phase alignment fixes the phases a coherent average adds; an
        // incoherent one adds powers and carries no phase to align - the
        // selector is off and the mode none while coherent averaging is off.
        syncPhaseAlignEnabled(phaseAlignCombo, prefs.isFftCoherentAveraging());
        Bindings.onChange(toolbarTabs, prefs.fftCoherentAveragingProperty(),
                v -> syncPhaseAlignEnabled(phaseAlignCombo, v));

        // Log freq axis and the time-discontinuity gate share this last row -
        // each spans ONE column (not two) so no extra row is added (the tab must
        // stay inside the FFT-pane height).
        logFreqCheck = new Button(g, SWT.CHECK);
        Bindings.text(logFreqCheck, "fft.settings.logFreq", logFreqCheck::setText);
        logFreqCheck.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        Bindings.text(logFreqCheck, "fft.settings.logFreq.tooltip", logFreqCheck::setToolTipText);
        // Pure repaint side-effect (axis remap) lives in the view, which
        // subscribes to this same pref - a plain two-way value bind here.
        Bindings.check(logFreqCheck, prefs.fftLogFreqAxisProperty());

        detectTimeDiscCheck = new Button(g, SWT.CHECK);
        Bindings.text(detectTimeDiscCheck, "fft.settings.detectTimeDiscontinuity", detectTimeDiscCheck::setText);
        detectTimeDiscCheck.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        Bindings.text(detectTimeDiscCheck, "fft.settings.detectTimeDiscontinuity.tooltip",
                detectTimeDiscCheck::setToolTipText);
        // Checked (the default) runs the time-domain discontinuity gate;
        // unchecking it lets a small or non-sinusoidal signal keep producing an
        // FFT - the gate would otherwise reject every such block.  The worker
        // reads this pref directly.
        Bindings.check(detectTimeDiscCheck, prefs.fftDetectTimeDiscontinuityProperty());

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

    /** Enables the phase-alignment selector only under coherent averaging,
     *  and sets the mode to none while it is off. */
    private void syncPhaseAlignEnabled(Combo combo, boolean coherent) {
        if (combo.isDisposed()) return;
        combo.setEnabled(coherent);
        if (!coherent) Preferences.instance().setFftFundamentalPhaseAlignment(FundamentalPhaseAlignment.NONE);
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
        int max = offeredFftLengths[offeredFftLengths.length - 1];
        if (property.get() > max) {
            // Persisted on a larger-heap run (or hand-edited): the working set
            // cannot fit this JVM - clamp and persist, the worker follows the
            // pref.
            log.info("FFT length {} exceeds the heap-capped maximum {} - clamped", property.get(), max);
            property.set(max);
        }
        int seed = indexOfInt(offeredFftLengths, property.get());
        fftLengthCombo.select(seed < 0 ? 3 : seed);
        fftLengthCombo.addListener(SWT.Selection, e -> {
            int i = fftLengthCombo.getSelectionIndex();
            if (i >= 0) {
                property.set(offeredFftLengths[i]);
            }
        });
        Consumer<Integer> onChange = v -> {
            if (fftLengthCombo.isDisposed()) return;
            if (v > max) {
                property.set(max);   // preset from a larger-heap run - re-fires clamped
                return;
            }
            int i = indexOfInt(offeredFftLengths, v);
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

    private void buildThdTab(TileTabFolder folder) {
        Composite g = groupCell(folder, "fft.tab.thd");
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
        Bindings.text(distMinEnable, "fft.thd.distMin", distMinEnable::setText);
        Bindings.text(distMinEnable, "fft.thd.distMin.tooltip", distMinEnable::setToolTipText);
        distMinField = new NumericStepField(g, UnitFamily.FREQUENCY,
                0, Preferences.instance().current().getInputSampleRate() / 2.0,
                FREQ_MAX_DECIMALS, 90);
        GridData distMinFieldGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        distMinFieldGd.horizontalSpan = 3;
        distMinField.setLayoutData(distMinFieldGd);
        Bindings.text(distMinField, "fft.thd.distMin.tooltip", distMinField::setToolTipText);
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
        Bindings.text(distMaxEnable, "fft.thd.distMax", distMaxEnable::setText);
        Bindings.text(distMaxEnable, "fft.thd.distMax.tooltip", distMaxEnable::setToolTipText);
        distMaxField = new NumericStepField(g, UnitFamily.FREQUENCY,
                0, Preferences.instance().current().getInputSampleRate() / 2.0,
                FREQ_MAX_DECIMALS, 90);
        GridData distMaxFieldGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        distMaxFieldGd.horizontalSpan = 3;
        distMaxField.setLayoutData(distMaxFieldGd);
        Bindings.text(distMaxField, "fft.thd.distMax.tooltip", distMaxField::setToolTipText);
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

        // Row: Manual fundamental - moved one row UP so it sits above
        // the harmonic-count row.  Checkbox text replaces the
        // separate Label here too.
        manualFundEnable = new Button(g, SWT.CHECK);
        Bindings.text(manualFundEnable, "fft.thd.manualFund", manualFundEnable::setText);
        Bindings.text(manualFundEnable, "fft.thd.manualFund.tooltip", manualFundEnable::setToolTipText);
        // Single field that accepts the value AND a unit suffix -
        // e.g. "1.5 V", "1500 mV", "-3.5 dBV".  The value is canonical Vrms;
        // unit parsing / display switching is the field's own concern.  The
        // 200 V ceiling covers declared external levels (amplifier output
        // measured through a divider), far above the DAC full-scale.
        manualFundField = new NumericStepField(g, UnitFamily.AMPLITUDE,
                AMP_MIN_VRMS, MANUAL_FUND_MAX_VRMS, AMP_MAX_DECIMALS, 110);
        GridData manualFundFieldGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        manualFundFieldGd.horizontalSpan = 3;
        manualFundField.setLayoutData(manualFundFieldGd);
        Bindings.text(manualFundField, "fft.thd.manualFund.tooltip", manualFundField::setToolTipText);
        manualFundField.setEnabled(prefs.isFftManualFundEnabled());
        Bindings.check(manualFundEnable, prefs.fftManualFundEnabledProperty());
        // Toggling also enables/disables the companion field and drives the
        // 'manF' THD tile (shown only when enabled).  No view redraw/reset.
        Bindings.onChange(toolbarTabs, prefs.fftManualFundEnabledProperty(), v -> {
            manualFundField.setEnabled(v);
            toolbarTabs.refreshTab(TAB_THD_SETTINGS);
        });

        addLabel(g, "fft.thd.maxThd");
        thdMaxHarmField = new NumericStepField(g, UnitFamily.NONE,
                THD_HARM_MIN, THD_HARM_MAX, 1, 1, 0, 60);
        thdMaxHarmField.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        Bindings.text(thdMaxHarmField, "fft.thd.maxThd.tooltip", thdMaxHarmField::setToolTipText);
        Bindings.stepFieldInt(thdMaxHarmField, prefs.fftThdMaxHarmonicProperty());
        // Drives the 'H{n}' THD tile.  No view redraw/reset.
        Bindings.onChange(toolbarTabs, prefs.fftThdMaxHarmonicProperty(),
                v -> toolbarTabs.refreshTab(TAB_THD_SETTINGS));

        addLabel(g, "fft.thd.maxCalc");
        // Minimum 9 - the THD overlay table is laid out for the H2..H9
        // range and shrinking below that leaves empty rows on the
        // bottom that look like a rendering bug.  Value N means "calc
        // up to HN" (N − 1 harmonics in the 2..N range).
        calcMaxHarmField = new NumericStepField(g, UnitFamily.NONE,
                CALC_HARM_MIN, CALC_HARM_MAX, 1, 1, 0, 60);
        calcMaxHarmField.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        Bindings.text(calcMaxHarmField, "fft.thd.maxCalc.tooltip", calcMaxHarmField::setToolTipText);
        // The external THD window's row count tracks this value (resize so the
        // harmonic rows aren't clipped) - that side-effect lives in the view,
        // subscribed to this same pref.  The min-9 floor at the old write site
        // was redundant: every reader of getFftCalcMaxHarmonic() already wraps
        // it in Math.max(9, ...), so a plain two-way value bind is faithful.
        Bindings.stepFieldInt(calcMaxHarmField, prefs.fftCalcMaxHarmonicProperty());
        // Manual-fundamental value field - registered here, LATE (after the
        // calc-max field is built), matching the original ordering.  Unit-aware
        // parser/formatter is unchanged; this only mirrors the committed value.
        Bindings.stepField(manualFundField, prefs.fftManualFundVrmsProperty());
        // dBV display choice: seed from the persisted pref, persist the user's
        // typed unit, and follow external writes (preset load) - setLogDisplay
        // is idempotent and fires no listener, so the loop terminates.
        manualFundField.setLogDisplay(prefs.isFftManualFundDbvDisplay());
        manualFundField.addSelectionListener(e ->
                prefs.setFftManualFundDbvDisplay(manualFundField.isLogDisplay()));
        Bindings.onChange(manualFundField, prefs.fftManualFundDbvDisplayProperty(),
                v -> manualFundField.setLogDisplay(v));
        new Label(g, SWT.NONE);   // fill row
        // (The previous global "Calibrate with noise" checkbox moved to
        // the Load-calibration tab - it's now a per-row "With noise"
        // flag, set independently per loaded .frc file.)
    }

    // =========================================================================
    // Presets tab
    // =========================================================================

    private void buildPresetsTab(TileTabFolder folder) {
        Composite g = groupCell(folder, "fft.tab.presets");
        g.setLayout(new FillLayout());
        new PresetBar<FftPreset>(g, "fft.presets", "fft.html#fft-tab-presets",
                new PresetBar.Store<>() {
                    @Override public Map<String, FftPreset> presets() { return Preferences.instance().getFftPresets(); }
                    @Override public void put(String name, FftPreset p) { Preferences.instance().putFftPreset(name, p); }
                    @Override public void remove(String name) { Preferences.instance().removeFftPreset(name); }
                    @Override public FftPreset captureCurrent() { return captureCurrentFftPreset(); }
                    @Override public void apply(FftPreset p) { applyFftPreset(p); }
                });
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
        // A preset writes the freq / mag window like any other writer of those
        // four prefs, so it announces it like any other writer: the pane's
        // FFT_RANGE_CHANGED subscriber clamps to [binSize, Nyquist] × [floor,
        // ceiling] and re-aligns the scrollbars.  Without this publish a preset
        // was the ONE path that reached the axis unclamped - a stored file with
        // magTop == magBottom (or an inverted or out-of-range pair) went
        // straight to the mapping and blanked the plot.  syncFftPan's own
        // javadoc has always listed "preset apply" among its callers; this is
        // the line that finally makes that true.
        MessageBus.instance().publish(Events.FFT_RANGE_CHANGED);
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

    private void buildUtilityTab(TileTabFolder folder) {
        Composite g = groupCell(folder, "fft.tab.utility");
        GridLayout gl = new GridLayout(4, false);
        gl.marginWidth = 6; gl.marginHeight = 4; gl.horizontalSpacing = 6;
        g.setLayout(gl);

        Button shotBtn = new Button(g, SWT.PUSH);
        shotBtn.setImage(cameraIcon);
        GridData shotGd = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        shotGd.widthHint = IconUtils.ACTION_BUTTON_PX;
        shotGd.heightHint = IconUtils.ACTION_BUTTON_PX;
        shotBtn.setLayoutData(shotGd);
        Bindings.text(shotBtn, "fft.utility.screenshot.tooltip", shotBtn::setToolTipText);
        // The screenshot dialog clones the whole pane offscreen, so the pane
        // owns it; ask it to open via the bus.
        shotBtn.addListener(SWT.Selection, e -> MessageBus.instance().publish(Events.FFT_SCREENSHOT_REQUESTED));

        Button calBtn = new Button(g, SWT.PUSH);
        calBtn.setImage(crosshairIcon);
        GridData calGd = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        calGd.widthHint = IconUtils.ACTION_BUTTON_PX;
        calGd.heightHint = IconUtils.ACTION_BUTTON_PX;
        calBtn.setLayoutData(calGd);
        Bindings.text(calBtn, "fft.utility.calibrate.tooltip", calBtn::setToolTipText);
        calBtn.addListener(SWT.Selection, e -> openCalibrationDialog());
    }

    /** Opens the ADC-calibration dialog using this pane's fundamental
     *  Vrms (no fallback - the scope pane has its own calibrate button).
     *  Aborts with an info MessageBox when no live Vrms is available.
     *
     *  <p>Always the two-row (Left / Right) form, seeded analyzed-channel-only:
     *  the FFT measures one channel ({@link Preferences#getFftChannel()}), so
     *  only that row carries a measured Vrms; the other row is disabled and
     *  blank.  On OK the entered actual Vrms rescales that channel's ADC
     *  full-scale - a bound stereo card writes only that channel (per-channel,
     *  leaving the other untouched), a MONO card or an unbound device writes the
     *  shared both-channels full-scale. */
    private void openCalibrationDialog() {
        if (isDisposed()) return;
        Shell parent = getShell();
        Preferences prefs = Preferences.instance();
        // First, whether the calibration may be written at all.
        if (AudioBackend.instance().isCalibrationFromDevice(true)) {
            // Device-provided (a QA40x, here or on a bench): show the built-in
            // full-scale read-only.
            DeviceCalibration adc = AudioBackend.instance().calibrationOrDefault(true);
            new CalibrationDialog(parent, adcTexts(), adc.fsRms(Channel.L), adc.fsRms(Channel.R),
                    true, actual -> { }).open();
            return;
        }
        Double currentVrms = (view == null) ? null : view.getLastVrms();
        if (currentVrms == null || currentVrms <= 0 || Double.isNaN(currentVrms)) {
            Dialogs.info(parent, I18n.t("calibrate.title"), I18n.t("calibrate.error.noVrms"));
            return;
        }
        final double measuredVrms = currentVrms;
        final Channel measCh = prefs.getFftChannel();
        Double seedL = measCh == Channel.L ? measuredVrms : null;
        Double seedR = measCh == Channel.R ? measuredVrms : null;
        new CalibrationDialog(parent, adcTexts(), seedL, seedR, false, actual -> {
            // The backend the device is on calibrates the analyzed channel from
            // this reading and the entered voltage: this machine's cards, or the
            // bench the device is plugged into (AudioBackend.calibrate).
            boolean stored = AudioBackend.instance().calibrate(true, measCh, measuredVrms,
                    actual.get(measCh));
            // A bench that would not take it moved nothing here - this client's
            // reference is still the bench's: say so rather than let the operator
            // believe the typed value is in force.
            if (!stored) {
                Dialogs.error(parent, I18n.t("calibrate.title"),
                        I18n.t("preferences.audio.card.copyCalibration.failed",
                                prefs.current().getInputDeviceName()));
            }
        }).open();
    }

    /** The ADC-calibration wording (title, prompt keys, log tag) for the shared
     *  {@link CalibrationDialog}. */
    private CalibrationDialog.Texts adcTexts() {
        return new CalibrationDialog.Texts("calibrate.title",
                "calibrate.input", "calibrate.input.tooltip", "ADC");
    }

    /** Capture support (help screenshots): builds the ADC calibration dialog in the
     *  two-row form seeded analyzed-channel-only - the {@link Preferences#getFftChannel()}
     *  row carries a representative Vrms, the other stays blank/disabled - and shows it
     *  non-modally (no live measurement needed), returning it so the automation can
     *  snapshot and dispose it.  Mirrors {@link #openCalibrationDialog}. */
    public CalibrationDialog openAdcCalibrationForCapture() {
        if (isDisposed()) return null;
        Channel measCh = Preferences.instance().getFftChannel();
        Double seedL = measCh == Channel.L ? CAPTURE_ADC_VRMS : null;
        Double seedR = measCh == Channel.R ? CAPTURE_ADC_VRMS : null;
        CalibrationDialog dlg = new CalibrationDialog(getShell(), adcTexts(), seedL, seedR, false, actual -> { });
        dlg.showForCapture();
        return dlg;
    }

    // =========================================================================
    // Save / Load CSV
    // =========================================================================

    private void buildSaveToTab(TileTabFolder folder) {
        Composite g = groupCell(folder, "fft.tab.save");
        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 6; gl.marginHeight = 4; gl.horizontalSpacing = 6;
        g.setLayout(gl);
        Preferences prefs = Preferences.instance();

        Text pathField = new Text(g, SWT.BORDER | SWT.READ_ONLY);
        String saved = prefs.getFftSavePath();
        pathField.setText(saved == null ? "" : saved);
        showPathTooltip(pathField, "fft.save.path.tooltip");
        Bindings.onChange(pathField, I18n.localeProperty(),
                l -> showPathTooltip(pathField, "fft.save.path.tooltip"));
        pathField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Single floppy-disk button collapses the old browse-then-save
        // two-step into one flow: opens the Save-as dialog, persists the
        // chosen path into the field + prefs, and immediately writes the
        // CSV.  Clicking again with a populated path re-uses it without
        // re-prompting (the file dialog still appears so the user can
        // confirm or change the target).
        Image floppyIcon = IconUtils.icon(g.getDisplay(), Icon.FLOPPY_DISK);
        Button save = new Button(g, SWT.PUSH);
        if (floppyIcon != null) save.setImage(floppyIcon);
        GridData saveGd = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        saveGd.heightHint = IconUtils.FILE_BUTTON_HEIGHT;
        save.setLayoutData(saveGd);
        Bindings.text(save, "fft.save.write.tooltip", save::setToolTipText);
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
                ImdResult lastImd = r.imd;
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
            // Seed the dialog with the previously-used file, if any -
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
            showPathTooltip(pathField, "fft.save.path.tooltip");
            prefs.setFftSavePath(chosen);
            Path parent = Paths.get(chosen).getParent();
            prefs.setFftSaveFolder(parent == null ? null : parent.toString());
            prefs.save();
            try {
                controller.saveSpectrum(Paths.get(chosen), r, imd, tone1, tone2);
            } catch (IOException ex) {
                showError(I18n.t("fft.save.error.title"),
                        I18n.t("fft.save.error.message", chosen, ex.getMessage()));
            }
        });
    }

    private void buildLoadFromTab(TileTabFolder folder) {
        Composite g = groupCell(folder, "fft.tab.load");
        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 6; gl.marginHeight = 4; gl.horizontalSpacing = 6;
        g.setLayout(gl);
        Preferences prefs = Preferences.instance();

        Text pathField = new Text(g, SWT.BORDER | SWT.READ_ONLY);
        String saved = prefs.getFftLoadPath();
        pathField.setText(saved == null ? "" : saved);
        showPathTooltip(pathField, "fft.load.path.tooltip");
        Bindings.onChange(pathField, I18n.localeProperty(),
                l -> showPathTooltip(pathField, "fft.load.path.tooltip"));
        pathField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Image folderIcon = IconUtils.icon(g.getDisplay(), Icon.FOLDER_OPEN);
        Button browse = new Button(g, SWT.PUSH);
        if (folderIcon != null) browse.setImage(folderIcon);
        GridData browseGd = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        browseGd.heightHint = IconUtils.FILE_BUTTON_HEIGHT;
        browse.setLayoutData(browseGd);
        Bindings.text(browse, "fft.load.browse.tooltip", browse::setToolTipText);
        browse.addListener(SWT.Selection, e -> {
            FileDialog d = new FileDialog(getShell(), SWT.OPEN);
            d.setFilterExtensions(new String[]{"*.fft", "*.csv", "*.*"});
            d.setFilterNames(new String[]{"Phonalyser FFT spectrum (*.fft)",
                    "CSV (legacy)", "All files (*.*)"});
            if (prefs.getFftLoadFolder() != null) d.setFilterPath(prefs.getFftLoadFolder());
            String chosen = d.open();
            if (chosen != null) {
                pathField.setText(chosen);
                showPathTooltip(pathField, "fft.load.path.tooltip");
                prefs.setFftLoadPath(chosen);
                prefs.setFftLoadFolder(Paths.get(chosen).getParent() == null ? null
                        : Paths.get(chosen).getParent().toString());
                prefs.save();
                loadSpectrumFft(chosen);
            }
        });
    }

    /** Shows the tooltip of a Save-to / Load-from path field for what it
     *  holds: the path itself, or the translation of {@code hintKey} while the
     *  field is empty. */
    private void showPathTooltip(Text pathField, String hintKey) {
        String path = pathField.getText();
        pathField.setToolTipText(path.isEmpty() ? I18n.t(hintKey) : path);
    }

    /** Loads a spectrum {@code .fft} file via the controller, stops live
     *  recording, and shows it as a static trace in the matching THD / IMD
     *  table mode. */
    /** Loads a {@code .fft} spectrum file and displays it (same as the
     *  Load-from tab's button).  Public so a programmatic caller - the
     *  help/video automation - can show a real spectrum without a live
     *  capture. */
    public void loadSpectrum(String path) {
        loadSpectrumFft(path);
    }

    private void loadSpectrumFft(String path) {
        FftController.LoadedSpectrum loaded;
        try {
            loaded = controller.loadSpectrum(path);
        } catch (IOException ex) {
            showError(I18n.t("fft.load.notImplemented.title"),
                    I18n.t("fft.load.error.read", ex.getMessage()));
            return;
        } catch (IllegalArgumentException ex) {
            showError(I18n.t("fft.load.notImplemented.title"), I18n.t("fft.load.error.format"));
            return;
        }
        // Stop live capture so the loaded spectrum isn't overwritten - the pane
        // owns the Record button and shared capture, so request the stop on the
        // bus instead of reaching into it.
        MessageBus.instance().publish(Events.FFT_RECORDING_STOP_REQUESTED);
        view.displayLoadedResult(loaded.result());
        view.setSourceFilePath(path);
    }

    private void showError(String title, String message) {
        Dialogs.error(getShell(), title, message);
    }

    // =========================================================================
    // Tab-header tiles - the shared TileTabFolder measures / paints; this
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
            TileTabFolder.Tile tile = getCalibrationTile();
            if (tile != null) {
                tiles.add(tile);
            }
        } else if (tabIndex == TAB_PRESETS) {
            // Show the number of saved presets when there are any - a hint
            // that there's something to load.
            int n = prefs.getFftPresets().size();
            if (n > 0) tiles.add(TileTabFolder.Tile.text(n + " saved",
                    I18n.t("fft.tile.presets", n)));
        }
        return tiles;
    }

    /** Tab-level hover tooltip key - fallback when no tile is hovered. */
    private String tabLabelTooltipKey(int tabIndex) {
        switch (tabIndex) {
            case TAB_FFT_SETTINGS: return "fft.tab.settings.tooltip";
            case TAB_THD_SETTINGS: return "fft.tab.thd.tooltip";
            default:               return null;
        }
    }

    // =========================================================================
    // Pure text / number parsers + formatters for the FFT settings fields.
    // Folded in from the former FftPaneFormat - used only here.
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

    /** Formats an averages value - {@code +Infinity} -> {@code "∞"},
     *  a single spectrum -> the Off label, finite values -> plain integer string. */
    private String formatAverages(double v) {
        if (Double.isInfinite(v)) return "∞";
        if (v <= AVERAGES_OFF) return NumericStepModel.OFF_LABEL;
        return Long.toString((long) Math.round(v));
    }

    // =========================================================================
    // Load-calibration tab - the rows live in the base class
    // =========================================================================

    /** The store was rebuilt from the rows (the calibration tab lives in the
     *  base): the view rebuilds the per-bin response it divides by, and the
     *  calibration tile counts the store's entries anew. */
    @Override
    protected void onCalibrationsSynced() {
        view.onCalibrationChanged();
        toolbarTabs.refreshTab(TAB_CALIBRATION);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Adds a field label showing the translation of {@code key}. */
    private void addLabel(Composite parent, String key) {
        Label l = new Label(parent, SWT.NONE);
        Bindings.text(l, key, l::setText);
        l.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
    }
}
