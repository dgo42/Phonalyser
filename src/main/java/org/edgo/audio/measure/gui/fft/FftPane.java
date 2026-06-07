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
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.FormAttachment;
import org.eclipse.swt.layout.FormData;
import org.eclipse.swt.layout.FormLayout;
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
import org.edgo.audio.measure.cli.util.FreqRespCalHelper;
import org.edgo.audio.measure.cli.util.StereoFreqRespCalibration;
import org.edgo.audio.measure.enums.AlignGenerator;
import org.edgo.audio.measure.enums.FftMagnitudeUnit;
import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.GenChangeCause;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.enums.MainsSuppression;
import org.edgo.audio.measure.enums.WindowType;
import org.edgo.audio.measure.fft.FftAnalyzer;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.fft.MathUtil;
import org.edgo.audio.measure.gui.MainTab;
import org.edgo.audio.measure.gui.bind.Bindings;
import org.edgo.audio.measure.gui.bind.Property;
import org.edgo.audio.measure.gui.preferences.CalibrationEntry;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.Dialogs;
import org.edgo.audio.measure.gui.common.IconUtils;
import org.edgo.audio.measure.gui.common.SvgPaths;
import org.edgo.audio.measure.gui.generator.NumericStepField;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.interfaces.Stepper;
import org.edgo.audio.measure.gui.preferences.FftPreset;
import org.edgo.audio.measure.gui.preferences.Preferences;
import org.edgo.audio.measure.gui.scope.AdcCalibrationDialog;
import org.edgo.audio.measure.gui.scope.ScreenshotDialog;
import org.edgo.audio.measure.gui.widgets.FlatScrollbar;
import org.edgo.audio.measure.gui.widgets.PaneTitle;
import org.edgo.audio.measure.gui.widgets.TileTabFolder;
import org.edgo.audio.measure.sound.AudioBackend;

import lombok.extern.log4j.Log4j2;

/**
 * Live FFT analysis pane.  Hosts the {@link FftView} canvas, two flat
 * scrollbars (frequency pan / magnitude pan), a magnitude-unit combo in
 * the top-right corner, and a CTabFolder at the bottom with Settings,
 * THD, Presets, Utility, Save-to, and Load-from tabs.
 */
@Log4j2
public final class FftPane {

    /** Powers-of-2 from 2^13 (8192) to 2^22 (4,194,304) with their labels. */
    private static final int[]    FFT_LENGTH_VALUES = {
            8192, 16384, 32768, 65536, 131072, 262144,
            524288, 1048576, 2097152, 4194304
    };
    private static final String[] FFT_LENGTH_LABELS = {
            "8k", "16k", "32k", "64k", "128k", "256k", "512k", "1M", "2M", "4M"
    };
    /** Resolution of the FlatScrollbars (any large integer — slider values
     *  are mapped to fractional positions). */
    private static final int SCROLL_RANGE = 1_000_000;

    /** Indices of the toolbar tabs.  The first {@link #NUM_CUSTOM_TABS} are
     *  custom-rendered (label + optional tile row); the Presets / Utility tabs
     *  in that range simply carry no tiles (centred label), matching the
     *  FreqResp pane.  Save / Load (live-capture only) render with the default
     *  CTabFolder look. */
    private static final int TAB_FFT_SETTINGS = 0;
    private static final int TAB_THD_SETTINGS = 1;
    private static final int TAB_CALIBRATION  = 4;
    private static final int NUM_CUSTOM_TABS  = 5;

    /** Pixel height of the big record-LED button at the right of the top
     *  toolbar — matches the scope's record LED so the two panes look
     *  visually aligned. */
    private static final int RECORD_LED_SIZE = 33;
    /** Pixel height of the utility-row icons (camera screenshot, crosshair
     *  calibrate). */
    private static final int UTILITY_ICON_HEIGHT = 26;

    private final Composite group;
    private PaneTitle title;
    private FftView         view;
    private FlatScrollbar   freqScrollbar;
    private FlatScrollbar   magScrollbar;

    // Image references retained so build methods past the constructor can
    // re-use them; all instances come from the shared IconUtils cache so
    // disposal is handled centrally.
    private final Image cameraIcon;
    private final Image crosshairIcon;
    private final Image pidAutotuneIcon;
    private final Image recordDim;
    private final Image recordLit;
    private Button recordButton;

    // ---- Tab-header tile rendering: the shared {@link TileTabFolder} owns
    //      the renderer, spacer images, collapse, hover tooltips and tile
    //      painting; this pane only feeds it tile content (see fftTilesFor).
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

    /** Subscriber for {@link Events#FFT_RANGE_CHANGED}, stored as a
     *  field so the dispose listener can unsubscribe the SAME instance
     *  (method references compare by identity). */
    private Consumer<Void> rangeChangedListener;
    /** Subscriber for {@link Events#FFT_RECORDING_AUTO_STOPPED} — fires
     *  when the analyser's stop-after-N counter trips so the pane can
     *  flip Record back off and release the shared capture. */
    private Consumer<Void> autoStoppedListener;
    /** Subscriber for {@link Events#FREQRESP_MEASUREMENT_STARTED} — the
     *  Frequency Response pane is about to drive the capture device
     *  exclusively, so this pane must stop any running recording and
     *  gray its Record button so it can't be re-engaged mid-sweep. */
    private Consumer<Void> freqRespStartedListener;
    /** Counterpart to {@link #freqRespStartedListener} — re-enables the
     *  Record button once the sweep finishes (or aborts). */
    private Consumer<Void> freqRespStoppedListener;
    /** Subscriber for {@link Events#GENERATOR_SIGNAL_CHANGED} — used
     *  only to refresh {@link #alignGenCheck}'s enabled state when the
     *  user toggles snap-to-FFT-bin on the generator pane (snap-to-bin
     *  is the missing prerequisite the user needs to satisfy here). */
    private Consumer<GenChangeCause> genChangeListener;

    /** Collapse state + per-child snapshot.  See {@link #setCollapsed(boolean)}. */
    private boolean    collapsed;
    private boolean[]  preCollapseChildVisible;
    private boolean[]  preCollapseChildExclude;

    private Composite             fftCalRowsContainer;
    private ScrolledComposite     fftCalRowsScroll;
    private final List<FftCalRow> fftCalRows = new ArrayList<>();

    public FftPane(Composite parent,
                   boolean liveCapture) {
        // FFT-length changes, capture acquire / release, and the
        // generator-running query all flow through the MessageBus — no
        // callback parameters needed for those concerns.  The analyser
        // worker (inside FftView) acquires and releases its own shared
        // capture on start / stop; the pane just drives the Record button.
        IconUtils icons = IconUtils.instance();
        Display d = parent.getDisplay();
        this.cameraIcon    = icons.render(d, SvgPaths.CAMERA,
                (int) Math.round(UTILITY_ICON_HEIGHT * 1.27), UTILITY_ICON_HEIGHT);
        this.crosshairIcon = icons.render(d, SvgPaths.CROSSHAIR,
                UTILITY_ICON_HEIGHT, UTILITY_ICON_HEIGHT);
        // Stroke-based, two-colour illustration — render with native
        // per-element stroke/fill (the monochrome path-fill renderer would
        // flatten its open curves into a black silhouette).
        this.pidAutotuneIcon = icons.renderAtHeightColored(d, SvgPaths.PID_AUTOTUNE,
                UTILITY_ICON_HEIGHT);
        this.recordDim     = icons.createRecordLed(d, 200,  40,  40, false, RECORD_LED_SIZE);
        this.recordLit     = icons.createRecordLed(d, 255,   0,   0, true,  RECORD_LED_SIZE);

        group = new Composite(parent, SWT.BORDER);
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth  = 0; gl.marginHeight = 0; gl.verticalSpacing = 2;
        group.setLayout(gl);
        title = new PaneTitle(group, Events.PANE_ID_FFT,
                I18n.t("fft.title.expanded"),
                I18n.t("fft.title.collapsed"),
                I18n.t("fft.pane.toggle.tooltip"));

        // ---- Plot row: FftView fills the available area, with the vertical
        // magnitude scrollbar pinned to the right edge AND the magnitude-unit
        // combo overlaid in the top-right corner over the FftView.  FormLayout
        // lets us position the combo on top of the canvas in z-order.
        Composite plotRow = new Composite(group, SWT.NONE);
        plotRow.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        plotRow.setLayout(new FormLayout());

        view = new FftView(plotRow);
        magScrollbar = new FlatScrollbar(plotRow, SWT.VERTICAL);
        magScrollbar.setMinimum(0);
        magScrollbar.setMaximum(SCROLL_RANGE);
        magScrollbar.setThumb(SCROLL_RANGE / 4);
        magScrollbar.setSelection(0);
        magScrollbar.setToolTipText(I18n.t("fft.scrollbar.magnitude.tooltip"));
        magScrollbar.addSelectionListener(e -> applyMagScrollbar());

        FormData vfd = new FormData();
        vfd.top    = new FormAttachment(0, 0);
        vfd.left   = new FormAttachment(0, 0);
        vfd.right  = new FormAttachment(magScrollbar, 0);
        vfd.bottom = new FormAttachment(100, 0);
        view.setLayoutData(vfd);

        FormData sbd = new FormData();
        sbd.top    = new FormAttachment(0, 0);
        sbd.right  = new FormAttachment(100, 0);
        sbd.bottom = new FormAttachment(100, 0);
        sbd.width  = 18;
        magScrollbar.setLayoutData(sbd);

        // ---- Horizontal frequency scrollbar.  Wrapped in a FormLayout
        // row so its right edge stops 18 px short of the group right —
        // aligning exactly with the FFT view's right edge (the view
        // ends at the magScrollbar's left edge, magScrollbar is 18 px
        // wide on the right).  Without this wrap the horizontal bar
        // ran the full group width, ~18 px wider than the chart.
        Composite freqRow = new Composite(group, SWT.NONE);
        GridData freqRowGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        freqRowGd.heightHint = 18;
        freqRow.setLayoutData(freqRowGd);
        freqRow.setLayout(new FormLayout());

        freqScrollbar = new FlatScrollbar(freqRow, SWT.HORIZONTAL);
        FormData fsbFd = new FormData();
        fsbFd.left   = new FormAttachment(0, 0);
        fsbFd.right  = new FormAttachment(100, -18);
        fsbFd.top    = new FormAttachment(0, 0);
        fsbFd.bottom = new FormAttachment(100, 0);
        freqScrollbar.setLayoutData(fsbFd);
        freqScrollbar.setMinimum(0);
        freqScrollbar.setMaximum(SCROLL_RANGE);
        freqScrollbar.setThumb(SCROLL_RANGE / 4);
        freqScrollbar.setSelection(0);
        freqScrollbar.setToolTipText(I18n.t("fft.scrollbar.frequency.tooltip"));
        freqScrollbar.addSelectionListener(e -> applyFreqScrollbar());

        // ---- Toolbar row: CTabFolder (left, grabs space) + Record toggle
        // anchored on the right.  Mirrors the scope's toolbar layout so the
        // record button always stays accessible.
        Composite toolbarRow = new Composite(group, SWT.NONE);
        toolbarRow.setLayoutData(new GridData(SWT.FILL, SWT.BEGINNING, true, false));
        GridLayout trGl = new GridLayout(2, false);
        trGl.marginWidth = 0; trGl.marginHeight = 0; trGl.horizontalSpacing = 4;
        toolbarRow.setLayout(trGl);

        TileTabFolder tabs = new TileTabFolder(toolbarRow, SWT.NONE);
        // SWT.DEFAULT lets the folder size itself to fit the active tab's
        // content (header + body).  We used to pin this to a hard-coded
        // 168 px but the value drifted out of sync as the Settings / THD
        // layouts evolved, leaving a 24-px gap at the bottom.
        tabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        this.toolbarTabs = tabs;
        // The folder paints a live "tile" summary under the Settings / THD
        // tab labels; feed it this pane's tile content + fallback tooltips.
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
        // Collapsing the tab body (strip double-click / Enter, or the
        // screenshot path) frees vertical space — re-flow the pane so the
        // chart above reclaims it.
        tabs.setCollapseRelayout(() -> {
            if (!group.isDisposed()) group.layout(true, true);
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

        recordButton = new Button(toolbarRow, SWT.TOGGLE);
        recordButton.setImage(recordDim);
        recordButton.setToolTipText(I18n.t("fft.record.tooltip"));
        GridData rbGd = new GridData(SWT.END, SWT.BEGINNING, false, false);
        rbGd.widthHint  = 48;
        rbGd.heightHint = 48;
        recordButton.setLayoutData(rbGd);

        // ---- View wiring.  The analyser lives inside FftView itself
        // (was previously a separate FftController); the pane just
        // drives the lifecycle (start / stop / setBuffer / reset).
        //
        // Two MessageBus subscriptions back to FftView:
        //   - FFT_RANGE_CHANGED: view publishes after pan / zoom or
        //     auto-setup / maximize → realign the scrollbars.
        //   - FFT_RECORDING_AUTO_STOPPED: view publishes when its
        //     stop-after-N counter trips → release record state.
        rangeChangedListener      = ignored -> syncFftPan();
        autoStoppedListener       = ignored -> disengageRecord();
        freqRespStartedListener   = ignored -> onFreqRespMeasurementStarted();
        freqRespStoppedListener   = ignored -> onFreqRespMeasurementStopped();
        genChangeListener         = cause   -> updateAlignGenEnabled();
        MessageBus bus = MessageBus.instance();
        bus.subscribe(Events.FFT_RANGE_CHANGED,             rangeChangedListener);
        bus.subscribe(Events.FFT_RECORDING_AUTO_STOPPED,    autoStoppedListener);
        bus.subscribe(Events.FREQRESP_MEASUREMENT_STARTED,  freqRespStartedListener);
        bus.subscribe(Events.FREQRESP_MEASUREMENT_STOPPED,  freqRespStoppedListener);
        bus.subscribe(Events.GENERATOR_SIGNAL_CHANGED,      genChangeListener);

        recordButton.addListener(SWT.Selection, e -> {
            if (recordButton.getSelection()) recordOn();
            else                              recordOff();
        });

        group.addDisposeListener(e -> {
            MessageBus bus2 = MessageBus.instance();
            bus2.unsubscribe(Events.FFT_RANGE_CHANGED,             rangeChangedListener);
            bus2.unsubscribe(Events.FFT_RECORDING_AUTO_STOPPED,    autoStoppedListener);
            bus2.unsubscribe(Events.FREQRESP_MEASUREMENT_STARTED,  freqRespStartedListener);
            bus2.unsubscribe(Events.FREQRESP_MEASUREMENT_STOPPED,  freqRespStoppedListener);
            bus2.unsubscribe(Events.GENERATOR_SIGNAL_CHANGED,      genChangeListener);
            view.stop();   // the worker releases its shared-capture reference
        });

        // Re-layout once the event loop spins up.  At constructor exit
        // the CTabFolder's tab body still reports a smaller natural
        // size than it ends up with after the first event-pump (font
        // metrics, native chrome, etc. finalise lazily).  Without a
        // fresh layout the initial render clipped the last row of the
        // Settings tab — a subsequent collapse/expand cycle "fixed"
        // it because the second pass had the right metrics.
        group.getDisplay().asyncExec(() -> {
            if (!group.isDisposed()) group.layout(true, true);
        });

        wireHelpAnchors();
    }

    /** Tags every interactive toolbar widget with a {@code "helpAnchor"}
     *  so Ctrl+F1 can resolve the focused control to a specific section
     *  of {@code fft.html}.  Tab tiles are painted (not real widgets);
     *  the chapter-level anchor on {@link #group} serves as the
     *  fallback when focus lands on something unattached. */
    private void wireHelpAnchors() {
        group              .setData("helpAnchor", "fft.html");
        if (recordButton        != null) recordButton       .setData("helpAnchor", "fft.html#fft-record-button");
        if (freqScrollbar       != null) freqScrollbar      .setData("helpAnchor", "fft.html#fft-scrollbar-freq");
        if (magScrollbar        != null) magScrollbar       .setData("helpAnchor", "fft.html#fft-scrollbar-mag");
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

    /** Forces the scrollbar thumb / position and the view to refresh
     *  from the current Preferences.  Needed by the screenshot path
     *  because the offscreen pane's controller never runs an analysis
     *  tick — the usual {@code onAnalysisPublished} pipeline that
     *  drives {@link #syncFftPan()} doesn't fire there.
     *  Call after {@code controller.setLastResult} and
     *  {@code setTabsCollapsed} so the snapshot is fully laid out. */
    public void refreshFromPrefs() {
        if (view != null && !view.isDisposed()) {
            syncFftPan();
            view.redraw();
        }
    }

    /** Overlays the tab tile rows into {@code gc} for the screenshot path —
     *  SWT's {@code Control.print()} captures the folder's native chrome but
     *  not the paint listener that draws the tiles, so the snapshot overlays
     *  them by hand.  Delegates to the shared {@link TileTabFolder}. */
    public void paintTabTilesInto(GC gc) {
        if (toolbarTabs != null) toolbarTabs.paintTilesInto(gc, group);
    }

    /** Collapses or expands the toolbar tab body so the screenshot path can
     *  hide the settings tabs before printing.  Delegates to the shared
     *  {@link TileTabFolder}. */
    public void setTabsCollapsed(boolean collapsed) {
        if (toolbarTabs != null) toolbarTabs.setCollapsed(collapsed);
    }

    /** Invoked by the FFT controller on the UI thread when the
     *  stop-after-N counter fires.  Disengages record mode so the
     *  user sees the Record button switch off and the shared capture
     *  refcount drops (which lets the audio device close if no other
     *  pane is holding it). */
    private void disengageRecord() {
        recordOff();
    }

    /** {@link Events#FREQRESP_MEASUREMENT_STARTED} handler: stops any
     *  in-flight FFT recording and grays the Record button so the user
     *  can't kick it back on mid-sweep.  The Frequency Response analyzer
     *  needs exclusive use of the capture device while it runs. */
    private void onFreqRespMeasurementStarted() {
        if (recordButton == null || recordButton.isDisposed()) return;
        if (recordButton.getSelection()) recordOff();
        recordButton.setEnabled(false);
    }

    /** Counterpart that re-enables the Record button once the sweep
     *  finishes (or aborts). */
    private void onFreqRespMeasurementStopped() {
        if (recordButton == null || recordButton.isDisposed()) return;
        recordButton.setEnabled(true);
    }

    /** Turns the Record button ON — starts the analyser worker, which acquires
     *  its own reference on the shared audio capture device (scope + FFT share
     *  the same device; whichever pane records first opens it).  Bails out
     *  silently and un-toggles the button when the acquire fails (no input
     *  device, already-busy device, etc.) — detected via {@link FftView#isRunning}. */
    private void recordOn() {
        if (recordButton == null || recordButton.isDisposed()) return;
        view.start();
        if (!view.isRunning()) {
            recordButton.setSelection(false);
            return;
        }
        recordButton.setSelection(true);
        recordButton.setImage(recordLit);
    }

    /** Pauses FFT recording for the lifetime of a modal dialog (e.g.
     *  Preferences).  Mirrors the oscilloscope's pause-around-dialog
     *  contract: returns a {@link Runnable} that restores the previous
     *  recording state.  Crucial for sample-rate / device changes —
     *  without releasing the FFT's capture reference here, the shared
     *  audio device stays open at the OLD parameters and the user's
     *  new settings would silently never take effect. */
    public Runnable pauseForDialog() {
        boolean wasRecording = recordButton != null
                && !recordButton.isDisposed()
                && recordButton.getSelection();
        if (wasRecording) recordOff();
        return () -> {
            if (wasRecording) recordOn();
        };
    }

    /** Turns the Record button OFF — stops the worker (which releases its own
     *  shared-capture reference) and restores the dim icon.  Called from the
     *  user's Record-button click (off path) and from {@link #disengageRecord}
     *  when the analyser's stop-after-N counter trips. */
    private void recordOff() {
        if (recordButton.isDisposed()) return;
        view.stop();
        recordButton.setSelection(false);
        recordButton.setImage(recordDim);
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

    public Composite getGroup() { return group; }
    public FftView   getView()  { return view; }

    // -------------------------------------------------------------------------
    // Pane-owned dialogs
    // -------------------------------------------------------------------------

    /** Opens the screenshot dialog for this pane.  Initial width/height
     *  come from preferences (last-used) and fall back to the pane's
     *  current pixel size; the chosen size + folder are persisted back
     *  on Copy or Save. */
    private void openScreenshotDialog() {
        if (group == null || group.isDisposed()) return;
        Rectangle b = group.getBounds();
        Preferences prefs = Preferences.instance();
        new ScreenshotDialog(
                group.getShell(),
                prefs.getScreenshotWidth(),  prefs.getScreenshotHeight(),
                b.width, b.height,
                prefs.getScreenshotFolder(),
                this::renderOffscreen,
                (w, h) -> {
                    prefs.setScreenshotWidth(w);
                    prefs.setScreenshotHeight(h);
                    prefs.save();
                },
                folder -> {
                    prefs.setScreenshotFolder(folder);
                    prefs.save();
                }
        ).open();
    }

    /** Opens the ADC-calibration dialog using this pane's fundamental
     *  Vrms (no fallback — the scope pane has its own calibrate button).
     *  Aborts with an info MessageBox when no live Vrms is available. */
    private void openCalibrationDialog() {
        Shell parent = (group == null || group.isDisposed()) ? null : group.getShell();
        if (parent == null) return;
        Double currentVrms = (view == null) ? null : view.getLastVrms();
        if (currentVrms == null || currentVrms <= 0 || Double.isNaN(currentVrms)) {
            Dialogs.info(parent, I18n.t("calibrate.title"), I18n.t("calibrate.error.noVrms"));
            return;
        }
        final double measuredVrms = currentVrms;
        new AdcCalibrationDialog(parent, measuredVrms, actualVrms -> {
            double scale = actualVrms / measuredVrms;
            double newFs = AudioBackend.getAdcFsVoltageRms() * scale;
            AudioBackend.setAdcFsVoltageRms(newFs);
            Preferences.instance().setAdcFsVoltageRms(newFs);
            Preferences.instance().save();
        }).open();
    }

    /** Renders this FFT pane offscreen at the requested dimensions with
     *  its toolbar tab body collapsed.  Builds a fresh {@link FftPane} in
     *  a hidden Shell (no live capture, no controller worker) and copies
     *  the live view's render snapshot into it, so the spectrum and the
     *  THD / IMD table render the same data the user is currently
     *  seeing. */
    private Image renderOffscreen(Display d, int targetW, int targetH) {
        targetW = Math.max(1, targetW);
        targetH = Math.max(1, targetH);
        Shell offscreen = new Shell(d, SWT.NO_TRIM);
        offscreen.setLayout(new FillLayout());
        FftPane fftPane = new FftPane(offscreen, false);
        Image output = new Image(d, targetW, targetH);
        try {
            fftPane.copySnapshotFrom(this);
            fftPane.setTabsCollapsed(true);
            fftPane.refreshFromPrefs();

            offscreen.setSize(targetW, targetH);
            offscreen.setLocation(-10000, -10000);
            offscreen.open();
            while (d.readAndDispatch()) { /* drain */ }
            fftPane.getGroup().redraw();
            while (d.readAndDispatch()) { /* drain */ }
            fftPane.getGroup().update();

            GC outGc = new GC(output);
            try {
                fftPane.getGroup().print(outGc);
                fftPane.paintTabTilesInto(outGc);
            } finally {
                outGc.dispose();
            }
            offscreen.setVisible(false);
        } finally {
            offscreen.dispose();
        }
        return output;
    }

    /** Copies the live pane's render snapshot (spectrum result, IMD
     *  slot and table mode) into this pane's view — used by the
     *  offscreen screenshot clone so it draws exactly what the live
     *  pane shows. */
    public void copySnapshotFrom(FftPane source) {
        if (source == null || source == this) return;
        view.copySnapshotFrom(source.view);
    }

    /** True when this pane is collapsed to just its title bar. */
    public boolean isCollapsed() { return collapsed; }

    /** Hides / shows every child except the title Label so the pane can
     *  collapse to its title bar (or restore).  Pure pane-internal — the
     *  parent {@code SashForm}'s weights are owned by {@link MainTab}. */
    public void setCollapsed(boolean wantCollapsed) {
        if (collapsed == wantCollapsed) return;
        if (group == null || group.isDisposed()) return;
        collapsed = wantCollapsed;
        Control[] children = group.getChildren();
        if (collapsed) {
            preCollapseChildVisible = new boolean[children.length];
            preCollapseChildExclude = new boolean[children.length];
            for (int i = 0; i < children.length; i++) {
                if (children[i] == title) continue;
                preCollapseChildVisible[i] = children[i].getVisible();
                if (children[i].getLayoutData() instanceof GridData gd) {
                    preCollapseChildExclude[i] = gd.exclude;
                    gd.exclude = true;
                }
                children[i].setVisible(false);
            }
            title.setCollapsed(true);
        } else {
            if (preCollapseChildVisible != null
                    && preCollapseChildVisible.length == children.length) {
                for (int i = 0; i < children.length; i++) {
                    if (children[i] == title) continue;
                    children[i].setVisible(preCollapseChildVisible[i]);
                    if (children[i].getLayoutData() instanceof GridData gd) {
                        gd.exclude = preCollapseChildExclude[i];
                    }
                }
                preCollapseChildVisible = null;
                preCollapseChildExclude = null;
            }
            title.setCollapsed(false);
        }
        group.layout(true);
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
        Bindings.combo(windowCombo, prefs.fftWindowProperty(), WindowType.values());
        Bindings.onChange(toolbarTabs, prefs.fftWindowProperty(), w -> toolbarTabs.refreshTab(TAB_FFT_SETTINGS));

        addLabel(g, I18n.t("fft.settings.overlap"));
        overlapCombo = new Combo(g, SWT.READ_ONLY);
        for (FftOverlap ov : FftOverlap.values()) overlapCombo.add(ov.label);
        overlapCombo.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        Bindings.combo(overlapCombo, prefs.fftOverlapProperty(), FftOverlap.values());
        // Overlap only changes the hop, not the spectrum/accumulator — refresh
        // the tab tile but DON'T reset the average; the worker adapts next tick.
        Bindings.onChange(toolbarTabs, prefs.fftOverlapProperty(),
                v -> toolbarTabs.refreshTab(TAB_FFT_SETTINGS));

        addLabel(g, I18n.t("fft.settings.averages"));
        // Cycling stepper: wheel / arrow keys snap to the next /
        // previous preset (2, 4, 8, 16, 32, ∞) while manual typing
        // still accepts any positive integer.
        Stepper avgCycle = FftPaneFormat::stepAveragesCycle;
        averagesField = new NumericStepField(g,
                Math.max(1, prefs.getFftAverages()),
                FftPaneFormat::parseAveragesNumeric,
                FftPaneFormat::formatAverages,
                avgCycle,
                avgCycle,
                70);
        // No strict-numeric filter here — the field accepts the special
        // "∞" / "forever" token that the cycle stepper produces, and the
        // parser rejects anything else on commit.  With the filter on,
        // setText("∞") was being blocked by the digits-only regex and
        // the field silently fell back to the previous finite value.
        averagesField.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
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
        stopAfterNField = new NumericStepField(stopRow,
                prefs.getFftStopAfterN(),
                txt -> FftPaneFormat.parseIntStrict(txt),
                v -> Long.toString((long) Math.round(v)),
                (v, dir) -> Math.max(1, v + dir),
                (v, dir) -> Math.max(1, v + dir),
                70);
        stopAfterNField.enableStrictNumericInput(false);
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
        Bindings.check(fundFromGenCheck, prefs.fftFundFromGeneratorProperty());

        // "Align generator" — None / PID / FLL — only meaningful when both
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
        int seed = FftPaneFormat.indexOfInt(FFT_LENGTH_VALUES, property.get());
        fftLengthCombo.select(seed < 0 ? 3 : seed);
        fftLengthCombo.addListener(SWT.Selection, e -> {
            int i = fftLengthCombo.getSelectionIndex();
            if (i >= 0) {
                property.set(FFT_LENGTH_VALUES[i]);
            }
        });
        Consumer<Integer> onChange = v -> {
            if (fftLengthCombo.isDisposed()) return;
            int i = FftPaneFormat.indexOfInt(FFT_LENGTH_VALUES, v);
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
        distMinField = new NumericStepField(g,
                prefs.getFftDistMinHz(),
                FftPaneFormat::parseDoubleStrict,
                v -> String.format("%.1f", v),
                (v, dir) -> Math.max(0, v + 50 * dir),
                (v, dir) -> Math.max(0, v + dir),
                90);
        distMinField.enableStrictNumericInput(false);
        GridData distMinFieldGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        distMinFieldGd.horizontalSpan = 3;
        distMinField.setLayoutData(distMinFieldGd);
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
        distMaxField = new NumericStepField(g,
                prefs.getFftDistMaxHz(),
                FftPaneFormat::parseDoubleStrict,
                v -> String.format("%.1f", v),
                (v, dir) -> Math.max(0, v + 50 * dir),
                (v, dir) -> Math.max(0, v + dir),
                90);
        distMaxField.enableStrictNumericInput(false);
        GridData distMaxFieldGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        distMaxFieldGd.horizontalSpan = 3;
        distMaxField.setLayoutData(distMaxFieldGd);
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
        // Single field that accepts the value AND a unit suffix —
        // e.g. "1.5 V", "1500 mV", "-3.5 dBV".  The internal value is
        // stored in the canonical mV scale (small magnitudes won't lose
        // precision); the formatter re-renders in the user's last unit.
        manualFundField = new NumericStepField(g,
                prefs.getFftManualFundVrms(),
                FftPaneFormat::parseAmplitudeWithUnit,
                v -> FftPaneFormat.formatAmplitudeWithUnit(v, Preferences.instance().getFftManualFundUnit().display),
                (v, dir) -> v * (1.0 + 0.05 * dir),
                (v, dir) -> v * (1.0 + 0.01 * dir),
                110);
        manualFundField.enableStrictNumericInput(true);   // allow trailing unit suffix
        GridData manualFundFieldGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        manualFundFieldGd.horizontalSpan = 3;
        manualFundField.setLayoutData(manualFundFieldGd);
        manualFundField.setEnabled(prefs.isFftManualFundEnabled());
        Bindings.check(manualFundEnable, prefs.fftManualFundEnabledProperty());
        // Toggling also enables/disables the companion field and drives the
        // 'manF' THD tile (shown only when enabled).  No view redraw/reset.
        Bindings.onChange(toolbarTabs, prefs.fftManualFundEnabledProperty(), v -> {
            manualFundField.setEnabled(v);
            toolbarTabs.refreshTab(TAB_THD_SETTINGS);
        });

        addLabel(g, I18n.t("fft.thd.maxThd"));
        thdMaxHarmField = new NumericStepField(g,
                prefs.getFftThdMaxHarmonic(),
                FftPaneFormat::parseIntStrict,
                v -> Long.toString((long) Math.round(v)),
                (v, dir) -> Math.min(9, Math.max(2, v + dir)),
                (v, dir) -> Math.min(9, Math.max(2, v + dir)),
                60);
        thdMaxHarmField.enableStrictNumericInput(false);
        thdMaxHarmField.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        Bindings.stepFieldInt(thdMaxHarmField, prefs.fftThdMaxHarmonicProperty());
        // Drives the 'H{n}' THD tile.  No view redraw/reset.
        Bindings.onChange(toolbarTabs, prefs.fftThdMaxHarmonicProperty(),
                v -> toolbarTabs.refreshTab(TAB_THD_SETTINGS));

        addLabel(g, I18n.t("fft.thd.maxCalc"));
        // Minimum 9 — the THD overlay table is laid out for the H2..H9
        // range and shrinking below that leaves empty rows on the
        // bottom that look like a rendering bug.  Value N means "calc
        // up to HN" (N − 1 harmonics in the 2..N range).
        calcMaxHarmField = new NumericStepField(g,
                prefs.getFftCalcMaxHarmonic(),
                FftPaneFormat::parseIntStrict,
                v -> Long.toString((long) Math.round(v)),
                (v, dir) -> Math.min(50, Math.max(9, v + dir)),
                (v, dir) -> Math.min(50, Math.max(9, v + dir)),
                60);
        calcMaxHarmField.enableStrictNumericInput(false);
        calcMaxHarmField.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
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
        return Dialogs.confirm(group.getShell(),
                I18n.t("fft.presets.overwrite.title"),
                I18n.t("fft.presets.overwrite.message", name)) == SWT.YES;
    }

    private boolean confirmDeletePreset(String name) {
        return Dialogs.confirm(group.getShell(),
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
        p.setManualFundUnit(prefs.getFftManualFundUnit());
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
        prefs.setFftManualFundUnit(p.getManualFundUnit());
        prefs.setFftManualFundEnabled(p.isManualFundEnabled());
        prefs.save();
        // Push values into the live widgets so the UI reflects the preset.
        syncWidgetsFromPrefs();
        toolbarTabs.refreshTab(TAB_FFT_SETTINGS);
        toolbarTabs.refreshTab(TAB_THD_SETTINGS);
        view.redraw();
    }

    private void syncWidgetsFromPrefs() {
        Preferences prefs = Preferences.instance();
        view.refreshFromPrefs();
        if (fftLengthCombo != null && !fftLengthCombo.isDisposed()) {
            int i = FftPaneFormat.indexOfInt(FFT_LENGTH_VALUES, prefs.getFftLength());
            if (i >= 0) fftLengthCombo.select(i);
        }
        if (averagesField != null && !averagesField.isDisposed()) {
            averagesField.setValue(Math.max(1, prefs.getFftAverages()));
        }
        if (windowCombo  != null && !windowCombo .isDisposed()) {
            WindowType wt = prefs.getFftWindow();
            windowCombo.select(wt.ordinal());
        }
        if (mainsSuppressionCombo != null && !mainsSuppressionCombo.isDisposed()) {
            MainsSuppression ms = prefs.getFftMainsSuppression();
            mainsSuppressionCombo.select(ms.ordinal());
        }
        if (overlapCombo != null && !overlapCombo.isDisposed()) {
            FftOverlap ov = prefs.getFftOverlap();
            overlapCombo.select(ov.ordinal());
        }
        if (stopAfterNEnable != null && !stopAfterNEnable.isDisposed()) {
            stopAfterNEnable.setSelection(prefs.isFftStopAfterNEnabled());
            stopAfterNField.setEnabled(prefs.isFftStopAfterNEnabled());
        }
        if (stopAfterNField != null && !stopAfterNField.isDisposed()) stopAfterNField.setValue(prefs.getFftStopAfterN());
        if (fundFromGenCheck != null && !fundFromGenCheck.isDisposed()) fundFromGenCheck.setSelection(prefs.isFftFundFromGenerator());
        if (logFreqCheck != null && !logFreqCheck.isDisposed()) logFreqCheck.setSelection(prefs.isFftLogFreqAxis());
        if (coherentCheck != null && !coherentCheck.isDisposed()) coherentCheck.setSelection(prefs.isFftCoherentAveraging());
        if (distMinEnable != null && !distMinEnable.isDisposed()) {
            distMinEnable.setSelection(prefs.isFftDistMinEnabled());
            distMinField.setEnabled(prefs.isFftDistMinEnabled());
        }
        if (distMinField != null && !distMinField.isDisposed()) distMinField.setValue(prefs.getFftDistMinHz());
        if (distMaxEnable != null && !distMaxEnable.isDisposed()) {
            distMaxEnable.setSelection(prefs.isFftDistMaxEnabled());
            distMaxField.setEnabled(prefs.isFftDistMaxEnabled());
        }
        if (distMaxField != null && !distMaxField.isDisposed()) distMaxField.setValue(prefs.getFftDistMaxHz());
        if (thdMaxHarmField != null && !thdMaxHarmField.isDisposed()) thdMaxHarmField.setValue(prefs.getFftThdMaxHarmonic());
        if (calcMaxHarmField != null && !calcMaxHarmField.isDisposed()) calcMaxHarmField.setValue(prefs.getFftCalcMaxHarmonic());
        if (manualFundEnable != null && !manualFundEnable.isDisposed()) {
            manualFundEnable.setSelection(prefs.isFftManualFundEnabled());
            manualFundField.setEnabled(prefs.isFftManualFundEnabled());
        }
        if (manualFundField != null && !manualFundField.isDisposed())
            manualFundField.setValue(prefs.getFftManualFundVrms());
    }

    // =========================================================================
    // Utility tab
    // =========================================================================

    private void buildUtilityTab(CTabFolder folder) {
        Composite g = groupCell(folder, I18n.t("fft.tab.utility"));
        GridLayout gl = new GridLayout(3, false);
        gl.marginWidth = 6; gl.marginHeight = 4; gl.horizontalSpacing = 6;
        g.setLayout(gl);

        Button shotBtn = new Button(g, SWT.PUSH);
        shotBtn.setImage(cameraIcon);
        shotBtn.setToolTipText(I18n.t("fft.utility.screenshot.tooltip"));
        shotBtn.addListener(SWT.Selection, e -> openScreenshotDialog());

        Button calBtn = new Button(g, SWT.PUSH);
        calBtn.setImage(crosshairIcon);
        calBtn.setToolTipText(I18n.t("fft.utility.calibrate.tooltip"));
        calBtn.addListener(SWT.Selection, e -> openCalibrationDialog());

        Button autotuneBtn = new Button(g, SWT.PUSH);
        autotuneBtn.setImage(pidAutotuneIcon);
        autotuneBtn.setToolTipText(I18n.t("fft.utility.autotune.tooltip"));
        autotuneBtn.addListener(SWT.Selection, e -> openPidAutotuneDialog());
    }

    /** Opens the relay-feedback PID autotune wizard for the generator
     *  frequency-lock loop.  Requires a live single-tone recording with
     *  snap-to-bin enabled (the loop the wizard tunes); otherwise it just
     *  reports what's missing rather than running against an idle loop. */
    private void openPidAutotuneDialog() {
        Shell parent = (group == null || group.isDisposed()) ? null : group.getShell();
        if (parent == null) return;
        Preferences prefs = Preferences.instance();
        boolean sine = prefs.getGenSignalForm() == GenSignalForm.SINE;
        if (!view.isRunning() || !view.isGeneratorActive() || !sine
                || !prefs.isGenSnapToFftBin() || !prefs.isFftFundFromGenerator()
                || prefs.getFftAlignGenerator() != AlignGenerator.PID) {   // tunes the PID gains
            Dialogs.info(parent, I18n.t("fft.autotune.title"), I18n.t("fft.autotune.notReady"));
            return;
        }
        new PidAutotuneDialog(parent, view).open();
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
        if (saved != null && !saved.isEmpty()) pathField.setToolTipText(saved);
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
            FileDialog d = new FileDialog(group.getShell(), SWT.SAVE);
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
        if (saved != null && !saved.isEmpty()) pathField.setToolTipText(saved);
        pathField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Image folderIcon = IconUtils.instance().renderAtHeight(
                g.getDisplay(), SvgPaths.FOLDER_OPEN, 16, null);
        Button browse = new Button(g, SWT.PUSH);
        if (folderIcon != null) browse.setImage(folderIcon);
        browse.setToolTipText(I18n.t("fft.load.browse.tooltip"));
        browse.addListener(SWT.Selection, e -> {
            FileDialog d = new FileDialog(group.getShell(), SWT.OPEN);
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
        int harmCount = Math.max(9, Preferences.instance().getFftCalcMaxHarmonic());
        r.ensureArrays(n, harmCount);
        r.amplitudeDbV     = new double[n];
        r.freqResolution   = freqRes;
        r.fftSize          = 2 * (n - 1);
        r.sampleRate       = (int) Math.round(freqRes * r.fftSize);
        r.harmonicCount    = harmCount;
        r.fundamentalTrueDbV = Double.NaN;
        r.snrFreqMin = 0; r.snrFreqMax = 0;
        for (int k = 0; k < n; k++) {
            double dbv = rows.get(k)[1], ph = rows.get(k)[2];
            r.amplitudeDbFs[k] = dbv;
            r.amplitudeDbV[k]  = dbv;
            r.phaseDeg[k]      = ph;
            double lin = Math.pow(10.0, dbv / 20.0);
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
        // The file already stores dBV, so anchor the dBV scale with a zero
        // offset (fundRefDbV − fundamentalDbFs = 0 ⇒ shown dBV == stored).
        r.fundRefDbV = r.fundamentalDbFs;

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
            imd = ImdAnalyzer.analyze(r, tone1Hz, tone2Hz);
        }

        // Stop live capture so the loaded spectrum isn't overwritten.
        if (recordButton != null && !recordButton.isDisposed() && recordButton.getSelection()) {
            recordOff();
        }
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
            // Header comments (skipped by the loader's value parser): record the
            // capture mode so re-opening restores the THD or IMD table, plus the
            // two tone frequencies IMD needs to re-measure its products.
            pw.println("# Phonalyser FFT spectrum");
            pw.println("# mode=" + (imd ? "IMD" : "THD"));
            if (imd) {
                pw.printf("# tone1_hz=%.6f%n", tone1Hz);
                pw.printf("# tone2_hz=%.6f%n", tone2Hz);
            }
            pw.println("frequency_hz;magnitude_dBV;phase_deg");
            double dbvOffset = Double.isNaN(r.fundRefDbV) ? 0.0 : (r.fundRefDbV - r.fundamentalDbFs);
            for (int k = 0; k < r.amplitudeDbFs.length; k++) {
                double f   = k * r.freqResolution;
                double dbv = r.amplitudeDbFs[k] + dbvOffset;
                double ph  = (r.phaseDeg != null && k < r.phaseDeg.length) ? r.phaseDeg[k] : 0;
                pw.printf("%.6f;%.6f;%.6f%n", f, dbv, ph);
            }
        }
    }

    private void showError(String title, String message) {
        Dialogs.error(group.getShell(), title, message);
    }

    // =========================================================================
    // Frequency / magnitude limits + scrollbar sync
    // =========================================================================

    /** Bin-size lower bound on the visible freq range, derived from the
     *  most recent analysis.  Falls back to a conservative estimate when
     *  there's no result yet. */
    private double currentBinSize() {
        FftResult r = view.getLastResult();
        if (r != null && r.fftSize > 0) return (double) r.sampleRate / r.fftSize;
        // No result yet — assume a typical 384 kHz capture and the
        // configured FFT length.  Worst case the user sees the bin-size
        // lower bound jump once after the first analysis publishes.
        int sr = 384_000;
        int fftLen = Math.max(8, Preferences.instance().getFftLength());
        return (double) sr / fftLen;
    }

    /** Nyquist upper bound on the visible freq range. */
    private double currentNyquist() {
        FftResult r = view.getLastResult();
        if (r != null) return r.sampleRate / 2.0;
        return 192_000;
    }

    /** Converts a {@code [top, bottom]} pair from one magnitude unit
     *  to another so a unit-combo change preserves the visible signal-
     *  level range.  Conversion is anchored at the ADC full-scale
     *  voltage: each dB on the dB axis maps to one factor-of-10^0.05
     *  on the volts axis (20 dB = ×10), so the user sees the same grid
     *  lines around the same signal levels after the switch.
     *  Returns {@code new double[] {newTop, newBot}}. */
    /** Clamps the persisted freq + mag window to the current hardware
     *  limits.  Called after every wheel zoom, scrollbar move, and
     *  controller publish so an extreme value can never escape. */
    private void clampRangesAndSave() {
        Preferences prefs = Preferences.instance();
        double binSize = currentBinSize();
        double nyq     = currentNyquist();
        double fMin    = prefs.getFftFreqMinHz();
        double fMax    = prefs.getFftFreqMaxHz();
        if (fMin < binSize)         fMin = binSize;
        if (fMax > nyq)             fMax = nyq;
        if (fMax - fMin < binSize)  fMax = Math.min(nyq, fMin + binSize);
        prefs.setFftFreqMinHz(fMin);
        prefs.setFftFreqMaxHz(fMax);
        FftMagnitudeUnit unit = prefs.getFftMagUnit();
        double fs    = prefs.getAdcFsVoltageRms();
        double maxTp = FftFormat.magMaxFor(unit, fs);
        double minBt = FftFormat.magMinFor(unit, fs);
        double mTop = prefs.getFftMagTop();
        double mBot = prefs.getFftMagBottom();
        if (mTop > maxTp) mTop = maxTp;
        if (mBot < minBt) mBot = minBt;
        // Minimum visible range: 1 unit on a linear (dB) axis; at least
        // a factor of 1.001 on a log axis (V / V/√Hz) so the log mapping
        // stays well-defined while still allowing the user to zoom in
        // very tight.  Without the log branch the "+1" rule pinned
        // zoom-in at the magMinFor floor.
        boolean magLog = (unit == FftMagnitudeUnit.V || unit == FftMagnitudeUnit.V_SQRT_HZ);
        if (magLog) {
            if (mTop / Math.max(Double.MIN_NORMAL, mBot) < 1.001) {
                mBot = mTop / 1.001;
            }
        } else if (mTop - mBot < 1) {
            mBot = mTop - 1;
        }
        prefs.setFftMagTop(mTop);
        prefs.setFftMagBottom(mBot);
    }

    /** Re-aligns the frequency / magnitude scrollbar thumbs and
     *  selections to the FFT pan window currently persisted in
     *  {@code Preferences} (freq min/max, mag top/bottom, log-axis
     *  flag).  Thumb size is proportional to (visible / total) so the
     *  user gets immediate visual feedback on how much of the spectrum
     *  is hidden; selection positions the visible slice inside the full
     *  range.  Called whenever the pan window can have shifted —
     *  preset apply, autosetup, maximize, range-change from the FFT
     *  view, FFT length change, and after a fresh analysis. */
    private void syncFftPan() {
        if (freqScrollbar == null || freqScrollbar.isDisposed()) return;
        if (magScrollbar  == null || magScrollbar .isDisposed()) return;
        clampRangesAndSave();
        Preferences prefs = Preferences.instance();

        // ---- Frequency scrollbar (log or lin space)
        double binSize = currentBinSize();
        double nyq     = currentNyquist();
        double fMin    = prefs.getFftFreqMinHz();
        double fMax    = prefs.getFftFreqMaxHz();
        boolean logFreq = prefs.isFftLogFreqAxis();
        double visible, total, scrollPos;
        if (logFreq) {
            double a = Math.log10(Math.max(1, binSize));
            double b = Math.log10(Math.max(a + 1, nyq));
            double lo = Math.log10(Math.max(1, fMin));
            double hi = Math.log10(Math.max(lo + 1e-9, fMax));
            visible = hi - lo;
            total   = b - a;
            scrollPos = (lo - a) / Math.max(1e-9, total - visible);
        } else {
            visible = fMax - fMin;
            total   = nyq - 0;
            scrollPos = (fMin - 0) / Math.max(1e-9, total - visible);
        }
        if (visible >= total) {
            freqScrollbar.setThumb(SCROLL_RANGE);
            freqScrollbar.setSelection(0);
        } else {
            int thumb = (int) Math.max(SCROLL_RANGE / 100,
                    Math.min(SCROLL_RANGE - 1, visible / total * SCROLL_RANGE));
            int sel   = (int) Math.max(0,
                    Math.min(SCROLL_RANGE - thumb, scrollPos * (SCROLL_RANGE - thumb)));
            freqScrollbar.setThumb(thumb);
            freqScrollbar.setSelection(sel);
            // Arrow click = ~10 % of the visible page; track click =
            // one full page.  Default of 1 / 10 against the
            // 1-million-unit range was so tiny that the user's clicks
            // looked like no-ops.
            freqScrollbar.setPageIncrement(Math.max(1, thumb / 2));
            freqScrollbar.setIncrement(Math.max(1, thumb / 10));
        }

        // ---- Magnitude scrollbar
        FftMagnitudeUnit unit = prefs.getFftMagUnit();
        double fs    = prefs.getAdcFsVoltageRms();
        double maxTp = FftFormat.magMaxFor(unit, fs);
        double minBt = FftFormat.magMinFor(unit, fs);
        double mTop  = prefs.getFftMagTop();
        double mBot  = prefs.getFftMagBottom();
        double magVis = mTop - mBot;
        double magTot = maxTp - minBt;
        if (magVis >= magTot) {
            magScrollbar.setThumb(SCROLL_RANGE);
            magScrollbar.setSelection(0);
        } else {
            int thumb = (int) Math.max(SCROLL_RANGE / 100,
                    Math.min(SCROLL_RANGE - 1, magVis / magTot * SCROLL_RANGE));
            // Slider 0 → top of mag range; slider max → bottom.
            double pos = (maxTp - mTop) / Math.max(1e-9, magTot - magVis);
            int sel   = (int) Math.max(0,
                    Math.min(SCROLL_RANGE - thumb, pos * (SCROLL_RANGE - thumb)));
            magScrollbar.setThumb(thumb);
            magScrollbar.setSelection(sel);
            magScrollbar.setPageIncrement(Math.max(1, thumb / 2));
            magScrollbar.setIncrement(Math.max(1, thumb / 10));
        }
    }

    private void applyFreqScrollbar() {
        Preferences prefs = Preferences.instance();
        boolean logFreq = prefs.isFftLogFreqAxis();
        double binSize = currentBinSize();
        double nyq     = currentNyquist();
        double visible = logFreq
                ? Math.log10(Math.max(1, prefs.getFftFreqMaxHz())) - Math.log10(Math.max(1, prefs.getFftFreqMinHz()))
                : prefs.getFftFreqMaxHz() - prefs.getFftFreqMinHz();
        double total = logFreq
                ? Math.log10(nyq) - Math.log10(Math.max(1, binSize))
                : nyq;
        int sel   = freqScrollbar.getSelection();
        int thumb = freqScrollbar.getThumb();
        double frac = (double) sel / Math.max(1, SCROLL_RANGE - thumb);
        double newLow;
        if (logFreq) {
            double a = Math.log10(Math.max(1, binSize));
            double lo = a + frac * Math.max(0, total - visible);
            newLow = Math.pow(10, lo);
            prefs.setFftFreqMinHz(newLow);
            prefs.setFftFreqMaxHz(Math.pow(10, lo + visible));
        } else {
            newLow = frac * Math.max(0, total - visible);
            prefs.setFftFreqMinHz(newLow);
            prefs.setFftFreqMaxHz(newLow + visible);
        }
        clampRangesAndSave();
        prefs.save();
        view.redraw();
    }

    private void applyMagScrollbar() {
        Preferences prefs = Preferences.instance();
        FftMagnitudeUnit unit = prefs.getFftMagUnit();
        double fs    = prefs.getAdcFsVoltageRms();
        double maxTp = FftFormat.magMaxFor(unit, fs);
        double minBt = FftFormat.magMinFor(unit, fs);
        double visible = prefs.getFftMagTop() - prefs.getFftMagBottom();
        double total   = maxTp - minBt;
        int sel   = magScrollbar.getSelection();
        int thumb = magScrollbar.getThumb();
        double frac = (double) sel / Math.max(1, SCROLL_RANGE - thumb);
        double newTop = maxTp - frac * Math.max(0, total - visible);
        prefs.setFftMagTop(newTop);
        prefs.setFftMagBottom(newTop - visible);
        clampRangesAndSave();
        prefs.save();
        view.redraw();
    }

    // =========================================================================
    // Tab-header tiles — the shared TileTabFolder measures / paints; this
    // pane only supplies each custom tab's tile content + fallback tooltip.
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
                    FftPaneFormat.shortFftLength(prefs.getFftLength()),
                    I18n.t("fft.tile.length", prefs.getFftLength())));
            tiles.add(TileTabFolder.Tile.text(
                    prefs.getFftWindow().name(),
                    I18n.t("fft.tile.window", I18n.t(prefs.getFftWindow().labelKey()))));
            tiles.add(TileTabFolder.Tile.text(
                    prefs.getFftOverlap().label,
                    I18n.t("fft.tile.overlap", prefs.getFftOverlap().label)));
            tiles.add(TileTabFolder.Tile.text(
                    FftPaneFormat.formatAverages(prefs.getFftAverages()) + "×",
                    I18n.t("fft.tile.averages", FftPaneFormat.formatAverages(prefs.getFftAverages()))));
            tiles.add(TileTabFolder.Tile.text(
                    prefs.isFftCoherentAveraging() ? "coh" : "inc",
                    I18n.t(prefs.isFftCoherentAveraging() ? "fft.tile.coh" : "fft.tile.inc")));
        } else if (tabIndex == TAB_THD_SETTINGS) {
            String dr = FftPaneFormat.shortDistRange(prefs);
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
            int n = FftCalibrationStore.instance().getEntries().size();
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
    // Helpers
    // =========================================================================

    // =========================================================================
    // Load-calibration tab — multi-row .frc loader (mirrors FreqRespPane).
    // =========================================================================

    private static final class FftCalRow {
        Composite                composite;
        Text                     pathField;
        /** "Active" toggle — two-way bound to {@code entry.active()}; the
         *  calibration is only added to {@link FftCalibrationStore} when this
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
        FftCalibrationStore.instance().clearAll();
        for (FftCalRow r : fftCalRows) {
            if (r.calibration != null && r.entry.getPath() != null && r.entry.active().get()) {
                FftCalibrationStore.instance().addEntry(r.calibration, r.entry.getPath(),
                        r.entry.withNoise().get());
            }
        }
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
        FileDialog fd = new FileDialog(group.getShell(), SWT.OPEN);
        fd.setText(I18n.t("freqResp.calibration.dialog"));
        fd.setFilterExtensions(new String[]{ "*.frc", "*.csv", "*.*" });
        String memFolder = Preferences.instance().getFftLoadFolder();
        if (memFolder != null) fd.setFilterPath(memFolder);
        String picked = fd.open();
        if (picked == null) return;
        if (!loadFileIntoFftCalRow(r, picked, true)) return;
        Preferences prefs = Preferences.instance();
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
                Dialogs.error(group.getShell(),
                        I18n.t("freqResp.calibration.dialog"),
                        I18n.t("freqResp.error.calibration.load").replace("{0}",
                                ex.getMessage() != null ? ex.getMessage()
                                                        : ex.getClass().getSimpleName()));
            }
            return false;
        }
    }

    private void syncFftStoreFromRows() {
        FftCalibrationStore store = FftCalibrationStore.instance();
        store.clearAll();
        for (FftCalRow r : fftCalRows) {
            // Only push entries that have a loaded file AND the user
            // has checked "Active" — the per-row With-noise flag rides
            // with the entry into the store.
            if (r.calibration != null && r.entry.getPath() != null && r.entry.active().get()) {
                store.addEntry(r.calibration, r.entry.getPath(), r.entry.withNoise().get());
            }
        }
        // Reflect the active-loaded count in the Load-calibration tab tile.
        if (toolbarTabs != null) toolbarTabs.refreshTab(TAB_CALIBRATION);
    }

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
