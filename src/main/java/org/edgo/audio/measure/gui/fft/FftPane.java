package org.edgo.audio.measure.gui.fft;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabFolderRenderer;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.Point;
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
import org.edgo.audio.measure.enums.FftMagnitudeUnit;
import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.WindowType;
import org.edgo.audio.measure.fft.FftAnalyzer;
import org.edgo.audio.measure.gui.MainTab;
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
import org.edgo.audio.measure.gui.sound.SignalBuffer;
import org.edgo.audio.measure.gui.widgets.FlatScrollbar;
import org.edgo.audio.measure.gui.widgets.PaneTitle;
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
    /** Human-readable labels for {@link WindowType} values
     *  (index-aligned with {@code WindowType.values()}). */
    private static final String[] WINDOW_LABELS = {
            "Rectangular",
            "Hann",
            "Blackman-Harris 4",
            "Blackman-Harris 7",
            "Flat top",
            "Dolph-Chebyshev 150",
            "Dolph-Chebyshev 200"
    };
    /** Resolution of the FlatScrollbars (any large integer — slider values
     *  are mapped to fractional positions). */
    private static final int SCROLL_RANGE = 1_000_000;

    /** Indices of the toolbar tabs we paint custom tile rows for. */
    private static final int TAB_FFT_SETTINGS = 0;
    private static final int TAB_THD_SETTINGS = 1;
    private static final int NUM_CUSTOM_TABS  = 2;

    /** Short labels for the human-readable Window combo — also used as
     *  the compact text in the FFT-settings tab's tile row. */
    private static final String[] WINDOW_SHORT_LABELS = {
            "Rect", "Hann", "BH4", "BH7", "FT", "DC150", "DC200"
    };

    private final Composite group;
    private PaneTitle title;
    private FftView         view;
    private FlatScrollbar   freqScrollbar;
    private FlatScrollbar   magScrollbar;

    /** Pixel height of the big record-LED button at the right of the top
     *  toolbar — matches the scope's record LED so the two panes look
     *  visually aligned. */
    private static final int RECORD_LED_SIZE = 33;
    /** Pixel height of the utility-row icons (camera screenshot, crosshair
     *  calibrate). */
    private static final int UTILITY_ICON_HEIGHT = 26;

    // Image references retained so build methods past the constructor can
    // re-use them; all instances come from the shared IconUtils cache so
    // disposal is handled centrally.
    private final Image cameraIcon;
    private final Image crosshairIcon;
    private final Image recordDim;
    private final Image recordLit;
    private Button recordButton;

    // ---- Tab-header tile rendering (mirrors the scope's pattern) -----
    private CTabFolder toolbarTabs;
    /** GridData of the toolbar CTabFolder, captured so the public
     *  {@link #setTabsCollapsed(boolean)} can toggle the tab body. */
    private GridData   toolbarTabsGd;
    private final String[] tabLabels = new String[NUM_CUSTOM_TABS];
    private final Image[]  tabSpacerImages = new Image[NUM_CUSTOM_TABS];
    private final List<TabRegion> tabRegions = new ArrayList<>();
    private Color tabTileBg;
    private Color tabTileFg;
    private Font  tabTileFont;

    /** A painted sub-region of a tab header (a tile) with its hover tip. */
    private static final class TabRegion {
        final Rectangle bounds;
        final String    tooltip;
        TabRegion(Rectangle b, String tip) { bounds = b; tooltip = tip; }
    }

    // Widget references for preset apply/refresh — captured at build time.
    private Combo              fftLengthCombo;
    private Combo              windowCombo;
    private Combo              overlapCombo;
    private NumericStepField   averagesField;
    private Button             stopAfterNEnable;
    private NumericStepField   stopAfterNField;
    private Button             fundFromGenCheck;
    private Button             logFreqCheck;
    private Button             coherentCheck;
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

    /** Set true once {@link Events#CAPTURE_ACQUIRE} has succeeded,
     *  false after release.  Prevents an extra release on a
     *  disposed-without-record path. */
    private boolean captureHeld;
    /** Subscriber for {@link Events#FFT_RANGE_CHANGED}, stored as a
     *  field so the dispose listener can unsubscribe the SAME instance
     *  (method references compare by identity). */
    private Runnable rangeChangedListener;
    /** Subscriber for {@link Events#FFT_RECORDING_AUTO_STOPPED} — fires
     *  when the analyser's stop-after-N counter trips so the pane can
     *  flip Record back off and release the shared capture. */
    private Runnable autoStoppedListener;

    public FftPane(Composite parent,
                   boolean liveCapture) {
        // FFT-length changes, capture acquire / release, and the
        // generator-running query all flow through the MessageBus — no
        // callback parameters needed for those concerns.  The shared
        // buffer is fetched on each record-on click via
        // Events.CAPTURE_ACQUIRE and stored in capturedBuffer for the
        // controller to poll.
        IconUtils icons = IconUtils.instance();
        Display d = parent.getDisplay();
        this.cameraIcon    = icons.render(d, SvgPaths.CAMERA,
                (int) Math.round(UTILITY_ICON_HEIGHT * 1.27), UTILITY_ICON_HEIGHT);
        this.crosshairIcon = icons.render(d, SvgPaths.CROSSHAIR,
                UTILITY_ICON_HEIGHT, UTILITY_ICON_HEIGHT);
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

        CTabFolder tabs = new CTabFolder(toolbarRow, SWT.NONE);
        tabs.setSimple(false);
        final GridData tabsGd = new GridData(SWT.FILL, SWT.FILL, true, true);
        // SWT.DEFAULT lets the CTabFolder size itself to fit the
        // active tab's content (header + body).  We used to pin this
        // to a hard-coded 168 px but the value drifted out of sync
        // as the Settings / THD layouts evolved, leaving a 24-px
        // gap at the bottom.
        tabs.setLayoutData(tabsGd);
        this.toolbarTabs = tabs;
        tabs.setRenderer(new FftTabHeaderRenderer(tabs));
        buildSettingsTab(tabs);
        buildThdTab(tabs);
        buildPresetsTab(tabs);
        buildUtilityTab(tabs);
        if (liveCapture) {
            buildSaveToTab(tabs);
            buildLoadFromTab(tabs);
        }
        if (tabs.getItemCount() > 0) tabs.setSelection(0);
        // Capture the i18n tab text for the 2 custom-rendered tabs, then
        // clear the CTabItem text so CTabFolder's default centred-label
        // rendering doesn't fight the renderer's top-aligned text.
        for (int i = 0; i < NUM_CUSTOM_TABS && i < tabs.getItemCount(); i++) {
            tabLabels[i] = tabs.getItem(i).getText();
            tabs.getItem(i).setText("");
        }
        // Force the tab strip taller so the label + tile row both fit.
        tabs.setTabHeight(46);
        for (int i = 0; i < NUM_CUSTOM_TABS && i < tabs.getItemCount(); i++) {
            updateTabSpacerImage(i);
        }

        // ── Tab-strip collapse: double-click on the tab strip (or
        // press Enter when the folder has focus) hides the tab body
        // so just the tab-header row stays visible.  Lets the user
        // reclaim vertical space for the chart.  Implementation
        // matches the oscilloscope pane: CTabFolder.computeSize(hHint)
        // treats hHint as the CLIENT-area height and adds the strip
        // trim on top, so heightHint=0 yields exactly "strip only" —
        // anything larger leaves a sliver of empty body underneath.
        // Capture the layout-data so the public
        // {@link #setTabsCollapsed(boolean)} method can apply the
        // collapse / expand recipe — used by the screenshot path to
        // hide the tab body before rendering.
        this.toolbarTabsGd      = tabsGd;
        final boolean[] collapsed = { false };
        Runnable toggleCollapse = () -> {
            collapsed[0] = !collapsed[0];
            setTabsCollapsed(collapsed[0]);
        };
        tabs.addListener(SWT.MouseDoubleClick, e -> {
            // Use clientArea.y as the divider: above that line is the
            // tab-strip, below is the body.  Only the strip triggers
            // toggle so body widgets keep their own double-click
            // semantics.
            Rectangle ca = tabs.getClientArea();
            if (e.y < ca.y) toggleCollapse.run();
        });
        tabs.addListener(SWT.KeyDown, e -> {
            if (e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR) {
                toggleCollapse.run();
                e.doit = false;
            }
        });
        // Paint listener: draw tiles on top of CTabFolder's default tab
        // strip + rebuild tabRegions for the hover-tooltip lookup below.
        tabs.addPaintListener(e -> {
            tabRegions.clear();
            for (int i = 0; i < NUM_CUSTOM_TABS && i < tabs.getItemCount(); i++) {
                if (!tabs.getItem(i).isDisposed()) {
                    drawTabTiles(e.gc, tabs.getItem(i).getBounds(), i);
                }
            }
        });
        // Dynamic per-region hover tooltip — region tips resolved at
        // paint time so they reflect current state without per-state
        // lookup at hover.  Only changes when the hovered tab/region
        // actually changes so the OS hover delay isn't reset on every
        // mouse-move pixel.
        final String[] currentTip = { null };
        final int[]    currentIdx = { -1 };
        tabs.addMouseMoveListener(e -> {
            String tip = null;
            for (TabRegion r : tabRegions) {
                if (r.bounds.contains(e.x, e.y)) { tip = r.tooltip; break; }
            }
            int hoverIdx = -1;
            for (int i = 0; i < tabs.getItemCount(); i++) {
                if (!tabs.getItem(i).isDisposed()
                        && tabs.getItem(i).getBounds().contains(e.x, e.y)) {
                    hoverIdx = i;
                    break;
                }
            }
            if (tip == null && hoverIdx >= 0) {
                String key = tabLabelTooltipKey(hoverIdx);
                if (key != null) tip = I18n.t(key);
            }
            if (hoverIdx != currentIdx[0] || !Objects.equals(currentTip[0], tip)) {
                currentTip[0] = tip;
                currentIdx[0] = hoverIdx;
                if (hoverIdx >= 0) {
                    tabs.getItem(hoverIdx).setToolTipText(tip);
                }
            }
        });
        tabs.addListener(SWT.MouseExit, e -> {
            if (currentIdx[0] >= 0 && currentIdx[0] < tabs.getItemCount()
                    && !tabs.getItem(currentIdx[0]).isDisposed()) {
                tabs.getItem(currentIdx[0]).setToolTipText(null);
            }
            currentTip[0] = null;
            currentIdx[0] = -1;
        });
        // Dispose tile-resource bundle when the tab folder goes away.
        tabs.addDisposeListener(e -> {
            for (Image img : tabSpacerImages) if (img != null && !img.isDisposed()) img.dispose();
            if (tabTileBg   != null) tabTileBg.dispose();
            if (tabTileFg   != null) tabTileFg.dispose();
            if (tabTileFont != null) tabTileFont.dispose();
        });

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
        rangeChangedListener  = this::syncFftPan;
        autoStoppedListener   = this::disengageRecord;
        MessageBus bus = MessageBus.instance();
        bus.subscribe(Events.FFT_RANGE_CHANGED,           rangeChangedListener);
        bus.subscribe(Events.FFT_RECORDING_AUTO_STOPPED,  autoStoppedListener);

        recordButton.addListener(SWT.Selection, e -> {
            boolean on = recordButton.getSelection();
            if (on) {
                // Pressing the FFT record button acquires a reference on
                // the shared audio capture device — without flipping the
                // scope's Record-LED.  Scope and FFT consume from the
                // SAME SignalBuffer; whichever pane the user records,
                // the device stays open until the last reference is
                // released.
                SignalBuffer buf = MessageBus.instance().request(Events.CAPTURE_ACQUIRE);
                if (buf == null) {
                    recordButton.setSelection(false);
                    return;
                }
                captureHeld = true;
                recordButton.setImage(recordLit);
                view.setBuffer(buf);
                view.start();
            } else {
                recordOff();
            }
        });

        group.addDisposeListener(e -> {
            MessageBus bus2 = MessageBus.instance();
            bus2.unsubscribe(Events.FFT_RANGE_CHANGED,          rangeChangedListener);
            bus2.unsubscribe(Events.FFT_RECORDING_AUTO_STOPPED, autoStoppedListener);
            view.stop();
            view.setBuffer(null);
            if (captureHeld) {
                bus2.publish(Events.CAPTURE_RELEASE);
                captureHeld = false;
            }
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

    /** Paints the per-tab tile row into {@code gc}, translated to the
     *  group's coordinate space.  Used by the screenshot path: SWT's
     *  {@code Control.print()} captures the CTabFolder's native chrome
     *  but doesn't fire the user-registered PaintListener that draws
     *  the tiles, so the screenshot has to overlay them manually. */
    public void paintTabTilesInto(GC gc) {
        if (toolbarTabs == null || toolbarTabs.isDisposed()) return;
        int dx = 0, dy = 0;
        Control c = toolbarTabs;
        while (c != null && c != group) {
            dx += c.getLocation().x;
            dy += c.getLocation().y;
            c = c.getParent();
        }
        for (int i = 0; i < NUM_CUSTOM_TABS && i < toolbarTabs.getItemCount(); i++) {
            CTabItem item = toolbarTabs.getItem(i);
            if (item.isDisposed()) continue;
            Rectangle b = item.getBounds();
            Rectangle abs = new Rectangle(b.x + dx, b.y + dy, b.width, b.height);
            drawTabTiles(gc, abs, i);
        }
    }

    /** Collapses or expands the toolbar CTabFolder's tab body so the
     *  screenshot path can hide the settings tabs before printing.
     *  Same recipe used by the double-click / Enter-key handlers in
     *  the toolbar — see the inline {@code toggleCollapse} runnable. */
    public void setTabsCollapsed(boolean collapsed) {
        if (toolbarTabsGd == null || group == null || group.isDisposed()) return;
        if (collapsed) {
            // heightHint=0 — same recipe the scope uses.
            // CTabFolder.computeSize(0) returns "client area = 0",
            // which the widget translates into strip + chrome only.
            // The body composite is positioned below the strip and
            // gets clipped to the widget bounds, so no body content
            // shows.  Anything > 0 starts allocating client area and
            // the first body row leaks back into view.
            toolbarTabsGd.heightHint              = 0;
            toolbarTabsGd.verticalAlignment       = SWT.BEGINNING;
            toolbarTabsGd.grabExcessVerticalSpace = false;
        } else {
            // SWT.DEFAULT lets the CTabFolder size itself to fit the
            // current tab content.  Hard-coding heightHint=168 left
            // a 24-px gap at the bottom once content was simplified
            // (Stop-after row collapsed to a single RowLayout
            // composite), since the prefs were sized for the larger
            // earlier layout.
            toolbarTabsGd.heightHint              = SWT.DEFAULT;
            toolbarTabsGd.verticalAlignment       = SWT.FILL;
            toolbarTabsGd.grabExcessVerticalSpace = true;
        }
        // Keep the Record button visible while the tabs are collapsed —
        // it's the only interactive control on the toolbar row and the
        // user expects it to stay accessible.  The 48-px button still
        // sets the row height, leaving a sliver of empty space below
        // the 24-px tab strip when collapsed; accepted as the lesser
        // evil compared to the button disappearing.
        group.layout(true, true);
    }

    /** Invoked by the FFT controller on the UI thread when the
     *  stop-after-N counter fires.  Disengages record mode so the
     *  user sees the Record button switch off and the shared capture
     *  refcount drops (which lets the audio device close if no other
     *  pane is holding it). */
    private void disengageRecord() {
        recordOff();
    }

    /** Turns the Record button OFF — stops the worker, releases the
     *  shared capture, restores the dim icon.  Called from the user's
     *  Record-button click (off path) and from {@link #disengageRecord}
     *  when the analyser's stop-after-N counter trips. */
    private void recordOff() {
        if (recordButton.isDisposed()) return;
        view.stop();
        view.setBuffer(null);
        if (captureHeld) {
            MessageBus.instance().publish(Events.CAPTURE_RELEASE);
            captureHeld = false;
        }
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
     *  the live controller's last {@link FftAnalyzer.Result} into it, so
     *  the spectrum + THD table render the same data the user is
     *  currently seeing. */
    private Image renderOffscreen(Display d, int targetW, int targetH) {
        targetW = Math.max(1, targetW);
        targetH = Math.max(1, targetH);
        Shell offscreen = new Shell(d, SWT.NO_TRIM);
        offscreen.setLayout(new FillLayout());
        FftPane fftPane = new FftPane(offscreen, false);
        Image output = new Image(d, targetW, targetH);
        try {
            fftPane.getView().setLastResult(view.getLastResult());
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

    /** Collapse state + per-child snapshot.  See {@link #setCollapsed(boolean)}. */
    private boolean    collapsed;
    private boolean[]  preCollapseChildVisible;
    private boolean[]  preCollapseChildExclude;

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
        int lenIdx = indexOfInt(FFT_LENGTH_VALUES, prefs.getFftLength());
        fftLengthCombo.select(lenIdx < 0 ? 3 : lenIdx);
        fftLengthCombo.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        fftLengthCombo.addListener(SWT.Selection, e -> {
            int i = fftLengthCombo.getSelectionIndex();
            if (i >= 0) {
                prefs.setFftLength(FFT_LENGTH_VALUES[i]);
                prefs.save();
                refreshTabHeader(TAB_FFT_SETTINGS);
                // Generator's bin snap is anchored to fftLength via
                // sampleRate / fftLength — broadcast the change so the
                // generator pane can re-snap its running tone onto a
                // fresh bin.  The new length is already in Preferences;
                // subscribers read it from there.
                MessageBus.instance().publish(Events.FFT_LENGTH_CHANGED);
                view.resetStatistics();
            }
        });

        addLabel(g, I18n.t("fft.settings.window"));
        windowCombo = new Combo(g, SWT.READ_ONLY);
        windowCombo.setItems(WINDOW_LABELS);
        windowCombo.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        WindowType currWin =
                enumOr(WindowType.class, prefs.getFftWindow(), WindowType.HANN);
        windowCombo.select(currWin.ordinal());
        windowCombo.addListener(SWT.Selection, e -> {
            int i = windowCombo.getSelectionIndex();
            if (i >= 0) {
                prefs.setFftWindow(WindowType.values()[i].name());
                prefs.save();
                refreshTabHeader(TAB_FFT_SETTINGS);
                view.resetStatistics();
            }
        });

        addLabel(g, I18n.t("fft.settings.overlap"));
        overlapCombo = new Combo(g, SWT.READ_ONLY);
        for (FftOverlap ov : FftOverlap.values()) overlapCombo.add(ov.label);
        overlapCombo.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        FftOverlap currOv = enumOr(FftOverlap.class, prefs.getFftOverlap(), FftOverlap.PCT_0);
        overlapCombo.select(currOv.ordinal());
        overlapCombo.addListener(SWT.Selection, e -> {
            int i = overlapCombo.getSelectionIndex();
            if (i >= 0) {
                prefs.setFftOverlap(FftOverlap.values()[i].name());
                prefs.save();
                refreshTabHeader(TAB_FFT_SETTINGS);
                view.resetStatistics();
            }
        });

        addLabel(g, I18n.t("fft.settings.averages"));
        // Cycling stepper: wheel / arrow keys snap to the next /
        // previous preset (2, 4, 8, 16, 32, ∞) while manual typing
        // still accepts any positive integer.
        Stepper avgCycle = FftPane::stepAveragesCycle;
        averagesField = new NumericStepField(g,
                Math.max(1, prefs.getFftAverages()),
                FftPane::parseAveragesNumeric,
                FftPane::formatAverages,
                avgCycle,
                avgCycle,
                70);
        // No strict-numeric filter here — the field accepts the special
        // "∞" / "forever" token that the cycle stepper produces, and the
        // parser rejects anything else on commit.  With the filter on,
        // setText("∞") was being blocked by the digits-only regex and
        // the field silently fell back to the previous finite value.
        averagesField.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        averagesField.addSelectionListener(e -> {
            prefs.setFftAverages(Math.max(1, averagesField.getValue()));
            prefs.save();
            refreshTabHeader(TAB_FFT_SETTINGS);
            refreshStopAfterEnable();
            view.resetStatistics();
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
        stopAfterNEnable.setSelection(prefs.isFftStopAfterNEnabled());
        stopAfterNEnable.setToolTipText(I18n.t("fft.settings.stopAfterN.tooltip"));
        stopAfterNField = new NumericStepField(stopRow,
                prefs.getFftStopAfterN(),
                txt -> parseIntStrict(txt),
                v -> Long.toString((long) Math.round(v)),
                (v, dir) -> Math.max(1, v + dir),
                (v, dir) -> Math.max(1, v + dir),
                70);
        stopAfterNField.enableStrictNumericInput(false);
        // Initial enabled state reflects both "stop-after" toggle AND
        // the rule that the row is only meaningful when averages is
        // "forever" (a finite N already implements a moving window).
        refreshStopAfterEnable();
        stopAfterNEnable.addListener(SWT.Selection, e -> {
            prefs.setFftStopAfterNEnabled(stopAfterNEnable.getSelection());
            stopAfterNField.setEnabled(stopAfterNEnable.getSelection()
                    && Double.isInfinite(prefs.getFftAverages()));
            prefs.save();
            view.resetStatistics();
        });
        stopAfterNField.addSelectionListener(e -> {
            prefs.setFftStopAfterN((int) Math.round(stopAfterNField.getValue()));
            prefs.save();
            view.resetStatistics();
        });

        // Three boolean knobs packed onto two rows so the tab content
        // fits inside the typical FFT-pane height (the previous 3-rows-
        // of-spans-4 layout pushed the third checkbox below the visible
        // CTabFolder client area).
        // Row: Get fundamental from generator (span 2) | Log freq (span 2)
        fundFromGenCheck = new Button(g, SWT.CHECK);
        fundFromGenCheck.setText(I18n.t("fft.settings.fundFromGen"));
        fundFromGenCheck.setSelection(prefs.isFftFundFromGenerator());
        GridData fgGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        fgGd.horizontalSpan = 2;
        fundFromGenCheck.setLayoutData(fgGd);
        fundFromGenCheck.addListener(SWT.Selection, e -> {
            prefs.setFftFundFromGenerator(fundFromGenCheck.getSelection());
            prefs.save();
        });

        logFreqCheck = new Button(g, SWT.CHECK);
        logFreqCheck.setText(I18n.t("fft.settings.logFreq"));
        logFreqCheck.setSelection(prefs.isFftLogFreqAxis());
        GridData lgGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        lgGd.horizontalSpan = 2;
        logFreqCheck.setLayoutData(lgGd);
        logFreqCheck.addListener(SWT.Selection, e -> {
            prefs.setFftLogFreqAxis(logFreqCheck.getSelection());
            prefs.save();
            view.redraw();
        });

        // Row: Coherent averaging (spans full width).
        coherentCheck = new Button(g, SWT.CHECK);
        coherentCheck.setText(I18n.t("fft.thd.coherent"));
        coherentCheck.setSelection(prefs.isFftCoherentAveraging());
        GridData cohGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        cohGd.horizontalSpan = 4;
        coherentCheck.setLayoutData(cohGd);
        coherentCheck.addListener(SWT.Selection, e -> {
            prefs.setFftCoherentAveraging(coherentCheck.getSelection());
            prefs.save();
            refreshTabHeader(TAB_FFT_SETTINGS);
            view.resetStatistics();
        });
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
        distMinEnable.setSelection(prefs.isFftDistMinEnabled());
        distMinField = new NumericStepField(g,
                prefs.getFftDistMinHz(),
                FftPane::parseDoubleStrict,
                v -> String.format("%.1f", v),
                (v, dir) -> Math.max(0, v + 50 * dir),
                (v, dir) -> Math.max(0, v + dir),
                90);
        distMinField.enableStrictNumericInput(false);
        GridData distMinFieldGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        distMinFieldGd.horizontalSpan = 3;
        distMinField.setLayoutData(distMinFieldGd);
        distMinField.setEnabled(distMinEnable.getSelection());
        distMinEnable.addListener(SWT.Selection, e -> {
            prefs.setFftDistMinEnabled(distMinEnable.getSelection());
            distMinField.setEnabled(distMinEnable.getSelection());
            prefs.save();
            refreshTabHeader(TAB_THD_SETTINGS);
            if (view != null && !view.isDisposed()) view.redraw();
        });
        distMinField.addSelectionListener(e -> {
            prefs.setFftDistMinHz(distMinField.getValue());
            prefs.save();
            refreshTabHeader(TAB_THD_SETTINGS);
            if (view != null && !view.isDisposed()) view.redraw();
        });

        // Row: distortion low-pass.
        distMaxEnable = new Button(g, SWT.CHECK);
        distMaxEnable.setText(I18n.t("fft.thd.distMax"));
        distMaxEnable.setSelection(prefs.isFftDistMaxEnabled());
        distMaxField = new NumericStepField(g,
                prefs.getFftDistMaxHz(),
                FftPane::parseDoubleStrict,
                v -> String.format("%.1f", v),
                (v, dir) -> Math.max(0, v + 50 * dir),
                (v, dir) -> Math.max(0, v + dir),
                90);
        distMaxField.enableStrictNumericInput(false);
        GridData distMaxFieldGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        distMaxFieldGd.horizontalSpan = 3;
        distMaxField.setLayoutData(distMaxFieldGd);
        distMaxField.setEnabled(distMaxEnable.getSelection());
        distMaxEnable.addListener(SWT.Selection, e -> {
            prefs.setFftDistMaxEnabled(distMaxEnable.getSelection());
            distMaxField.setEnabled(distMaxEnable.getSelection());
            prefs.save();
            refreshTabHeader(TAB_THD_SETTINGS);
            if (view != null && !view.isDisposed()) view.redraw();
        });
        distMaxField.addSelectionListener(e -> {
            prefs.setFftDistMaxHz(distMaxField.getValue());
            prefs.save();
            refreshTabHeader(TAB_THD_SETTINGS);
            if (view != null && !view.isDisposed()) view.redraw();
        });

        // Row: Manual fundamental — moved one row UP so it sits above
        // the harmonic-count row.  Checkbox text replaces the
        // separate Label here too.
        manualFundEnable = new Button(g, SWT.CHECK);
        manualFundEnable.setText(I18n.t("fft.thd.manualFund"));
        manualFundEnable.setSelection(prefs.isFftManualFundEnabled());
        // Single field that accepts the value AND a unit suffix —
        // e.g. "1.5 V", "1500 mV", "-3.5 dBV".  The internal value is
        // stored in the canonical mV scale (small magnitudes won't lose
        // precision); the formatter re-renders in the user's last unit.
        manualFundField = new NumericStepField(g,
                prefs.getFftManualFundVrms(),
                FftPane::parseAmplitudeWithUnit,
                v -> formatAmplitudeWithUnit(v, Preferences.instance().getFftManualFundUnit()),
                (v, dir) -> v * (1.0 + 0.05 * dir),
                (v, dir) -> v * (1.0 + 0.01 * dir),
                110);
        manualFundField.enableStrictNumericInput(true);   // allow trailing unit suffix
        GridData manualFundFieldGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        manualFundFieldGd.horizontalSpan = 3;
        manualFundField.setLayoutData(manualFundFieldGd);
        manualFundField.setEnabled(manualFundEnable.getSelection());
        manualFundEnable.addListener(SWT.Selection, e -> {
            prefs.setFftManualFundEnabled(manualFundEnable.getSelection());
            manualFundField.setEnabled(manualFundEnable.getSelection());
            prefs.save();
            refreshTabHeader(TAB_THD_SETTINGS);
        });

        addLabel(g, I18n.t("fft.thd.maxThd"));
        thdMaxHarmField = new NumericStepField(g,
                prefs.getFftThdMaxHarmonic(),
                FftPane::parseIntStrict,
                v -> Long.toString((long) Math.round(v)),
                (v, dir) -> Math.min(9, Math.max(2, v + dir)),
                (v, dir) -> Math.min(9, Math.max(2, v + dir)),
                60);
        thdMaxHarmField.enableStrictNumericInput(false);
        thdMaxHarmField.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        thdMaxHarmField.addSelectionListener(e -> {
            prefs.setFftThdMaxHarmonic((int) Math.round(thdMaxHarmField.getValue()));
            prefs.save();
            refreshTabHeader(TAB_THD_SETTINGS);
        });

        addLabel(g, I18n.t("fft.thd.maxCalc"));
        // Minimum 9 — the THD overlay table is laid out for the H2..H9
        // range and shrinking below that leaves empty rows on the
        // bottom that look like a rendering bug.  Value N means "calc
        // up to HN" (N − 1 harmonics in the 2..N range).
        calcMaxHarmField = new NumericStepField(g,
                prefs.getFftCalcMaxHarmonic(),
                FftPane::parseIntStrict,
                v -> Long.toString((long) Math.round(v)),
                (v, dir) -> Math.min(50, Math.max(9, v + dir)),
                (v, dir) -> Math.min(50, Math.max(9, v + dir)),
                60);
        calcMaxHarmField.enableStrictNumericInput(false);
        calcMaxHarmField.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        calcMaxHarmField.addSelectionListener(e -> {
            int n = Math.max(9, (int) Math.round(calcMaxHarmField.getValue()));
            prefs.setFftCalcMaxHarmonic(n);
            prefs.save();
            // External THD window's row count tracks this value — resize
            // it so the harmonic rows aren't clipped (or excess empty
            // rows hidden) after a change.
            if (view != null && !view.isDisposed()) view.resizeExternalShellToContent();
        });
        manualFundField.addSelectionListener(e -> {
            prefs.setFftManualFundVrms(manualFundField.getValue());
            prefs.save();
        });
        new Label(g, SWT.NONE);   // fill row
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
            presetSaveBtn.setEnabled(!presetsEqual(existing, captureCurrentFftPreset()));
            presetLoadBtn.setEnabled(true);
            presetDeleteBtn.setEnabled(true);
        }
    }

    private static boolean presetsEqual(FftPreset a, FftPreset b) {
        return a.getChannel() == b.getChannel()
            && eq(a.getMagUnit(), b.getMagUnit())
            && a.isLogFreqAxis()       == b.isLogFreqAxis()
            && Double.compare(a.getFreqMinHz(),    b.getFreqMinHz())    == 0
            && Double.compare(a.getFreqMaxHz(),    b.getFreqMaxHz())    == 0
            && Double.compare(a.getMagTop(),       b.getMagTop())       == 0
            && Double.compare(a.getMagBottom(),    b.getMagBottom())    == 0
            && a.getFftLength()        == b.getFftLength()
            && Double.compare(a.getAverages(),     b.getAverages())     == 0
            && a.isStopAfterNEnabled() == b.isStopAfterNEnabled()
            && a.getStopAfterN()       == b.getStopAfterN()
            && a.isFundFromGenerator() == b.isFundFromGenerator()
            && eq(a.getWindow(),  b.getWindow())
            && eq(a.getOverlap(), b.getOverlap())
            && a.isCoherentAveraging() == b.isCoherentAveraging()
            && Double.compare(a.getDistMinHz(), b.getDistMinHz()) == 0
            && Double.compare(a.getDistMaxHz(), b.getDistMaxHz()) == 0
            && a.isDistMinEnabled() == b.isDistMinEnabled()
            && a.isDistMaxEnabled() == b.isDistMaxEnabled()
            && a.getThdMaxHarmonic()  == b.getThdMaxHarmonic()
            && a.getCalcMaxHarmonic() == b.getCalcMaxHarmonic()
            && Double.compare(a.getManualFundVrms(), b.getManualFundVrms()) == 0
            && eq(a.getManualFundUnit(), b.getManualFundUnit())
            && a.isManualFundEnabled() == b.isManualFundEnabled();
    }

    private static boolean eq(String a, String b) {
        return (a == null) ? b == null : a.equals(b);
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
        p.setCalcMaxHarmonic(prefs.getFftCalcMaxHarmonic());
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
        refreshTabHeader(TAB_FFT_SETTINGS);
        refreshTabHeader(TAB_THD_SETTINGS);
        view.redraw();
    }

    private void syncWidgetsFromPrefs() {
        Preferences prefs = Preferences.instance();
        view.refreshFromPrefs();
        if (fftLengthCombo != null && !fftLengthCombo.isDisposed()) {
            int i = indexOfInt(FFT_LENGTH_VALUES, prefs.getFftLength());
            if (i >= 0) fftLengthCombo.select(i);
        }
        if (averagesField != null && !averagesField.isDisposed()) {
            averagesField.setValue(Math.max(1, prefs.getFftAverages()));
        }
        if (windowCombo  != null && !windowCombo .isDisposed()) {
            WindowType wt = enumOr(WindowType.class,
                    prefs.getFftWindow(), WindowType.HANN);
            windowCombo.select(wt.ordinal());
        }
        if (overlapCombo != null && !overlapCombo.isDisposed()) {
            FftOverlap ov = enumOr(FftOverlap.class, prefs.getFftOverlap(), FftOverlap.PCT_0);
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
        GridLayout gl = new GridLayout(2, false);
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
    }

    // =========================================================================
    // Save / Load CSV
    // =========================================================================

    private void buildSaveToTab(CTabFolder folder) {
        Composite g = groupCell(folder, I18n.t("fft.tab.save"));
        GridLayout gl = new GridLayout(3, false);
        gl.marginWidth = 6; gl.marginHeight = 4; gl.horizontalSpacing = 6;
        g.setLayout(gl);
        Preferences prefs = Preferences.instance();

        Text pathField = new Text(g, SWT.BORDER | SWT.READ_ONLY);
        String saved = prefs.getFftSavePath();
        pathField.setText(saved == null ? "" : saved);
        if (saved != null && !saved.isEmpty()) pathField.setToolTipText(saved);
        pathField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button browse = new Button(g, SWT.PUSH);
        browse.setText("…");
        browse.setToolTipText(I18n.t("fft.save.browse.tooltip"));
        browse.addListener(SWT.Selection, e -> {
            FileDialog d = new FileDialog(group.getShell(), SWT.SAVE);
            d.setFilterExtensions(new String[]{"*.csv"});
            d.setFilterNames(new String[]{"CSV (frequency_hz;magnitude_dBV;phase_deg)"});
            d.setOverwrite(true);
            if (prefs.getFftSaveFolder() != null) d.setFilterPath(prefs.getFftSaveFolder());
            String chosen = d.open();
            if (chosen != null) {
                pathField.setText(chosen);
                pathField.setToolTipText(chosen);
                prefs.setFftSavePath(chosen);
                prefs.setFftSaveFolder(Paths.get(chosen).getParent() == null ? null
                        : Paths.get(chosen).getParent().toString());
                prefs.save();
            }
        });

        Button save = new Button(g, SWT.PUSH);
        save.setText(I18n.t("fft.save.write"));
        save.setToolTipText(I18n.t("fft.save.write.tooltip"));
        save.addListener(SWT.Selection, e -> {
            String path = pathField.getText().trim();
            if (path.isEmpty()) {
                showError(I18n.t("fft.save.pickFirst.title"), I18n.t("fft.save.pickFirst.message"));
                return;
            }
            FftAnalyzer.Result r = view.getLastResult();
            if (r == null) {
                showError(I18n.t("fft.save.error.title"), I18n.t("fft.save.noData"));
                return;
            }
            try {
                writeSpectrumCsv(Paths.get(path), r);
            } catch (IOException ex) {
                showError(I18n.t("fft.save.error.title"),
                        I18n.t("fft.save.error.message", path, ex.getMessage()));
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

        Button browse = new Button(g, SWT.PUSH);
        browse.setText("…");
        browse.setToolTipText(I18n.t("fft.load.browse.tooltip"));
        browse.addListener(SWT.Selection, e -> {
            FileDialog d = new FileDialog(group.getShell(), SWT.OPEN);
            d.setFilterExtensions(new String[]{"*.csv"});
            d.setFilterNames(new String[]{"CSV (frequency_hz;magnitude_dBV;phase_deg)"});
            if (prefs.getFftLoadFolder() != null) d.setFilterPath(prefs.getFftLoadFolder());
            String chosen = d.open();
            if (chosen != null) {
                pathField.setText(chosen);
                pathField.setToolTipText(chosen);
                prefs.setFftLoadPath(chosen);
                prefs.setFftLoadFolder(Paths.get(chosen).getParent() == null ? null
                        : Paths.get(chosen).getParent().toString());
                prefs.save();
                // Load CSV path persisted; rendering from CSV is a follow-up
                // task — for now we just remember the file location.
                showError(I18n.t("fft.load.notImplemented.title"),
                        I18n.t("fft.load.notImplemented.message"));
            }
        });
    }

    private void writeSpectrumCsv(Path file, FftAnalyzer.Result r) throws IOException {
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
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
        FftAnalyzer.Result r = view.getLastResult();
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
        FftAnalyzer.Result r = view.getLastResult();
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
    /** Maximum sensible top-of-axis value for a magnitude unit.
     *  Driven by the ADC full-scale calibration so the user can never
     *  scroll above what the hardware could produce. */
    private static double magMaxFor(FftMagnitudeUnit unit, double adcFsVrms) {
        double fs = Math.max(1e-12, adcFsVrms);
        switch (unit) {
            case DBFS:      return 0;                              // 0 dBFS = ADC clipping
            case DBV: {
                double dbvFs = 20 * Math.log10(fs);
                return Math.ceil((dbvFs + 5) / 10.0) * 10;         // round up to 10 dB
            }
            case V:         return Math.ceil(fs + 1);              // a bit above FS
            case V_SQRT_HZ: return Math.max(1e-3, fs);
            default:        return 0;
        }
    }

    /** Minimum sensible bottom-of-axis value for a magnitude unit.
     *  Linear-amplitude axes (V, V/√Hz) bottom out at 1 fV which is
     *  well below the thermal noise floor of any realistic ADC — the
     *  user can still zoom in tight thanks to the ratio-based span
     *  rule in {@link #clampRangesAndSave}. */
    private static double magMinFor(FftMagnitudeUnit unit, double adcFsVrms) {
        switch (unit) {
            case DBFS:      return -300;
            case DBV:       return -300 + 20 * Math.log10(Math.max(1e-12, adcFsVrms));
            case V:
            case V_SQRT_HZ: return 1e-15;   // 1 fV
            default:        return -300;
        }
    }

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
        FftMagnitudeUnit unit = FftMagnitudeUnit.fromName(prefs.getFftMagUnit());
        double fs    = prefs.getAdcFsVoltageRms();
        double maxTp = magMaxFor(unit, fs);
        double minBt = magMinFor(unit, fs);
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
        FftMagnitudeUnit unit = FftMagnitudeUnit.fromName(prefs.getFftMagUnit());
        double fs    = prefs.getAdcFsVoltageRms();
        double maxTp = magMaxFor(unit, fs);
        double minBt = magMinFor(unit, fs);
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
        FftMagnitudeUnit unit = FftMagnitudeUnit.fromName(prefs.getFftMagUnit());
        double fs    = prefs.getAdcFsVoltageRms();
        double maxTp = magMaxFor(unit, fs);
        double minBt = magMinFor(unit, fs);
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
    // Tab-header tile rendering (state-aware tiles under each custom tab)
    // =========================================================================

    /** Custom renderer: widens the FFT-settings / THD-settings tabs so
     *  their tile rows fit, and paints the label top-aligned so the tile
     *  row can sit cleanly underneath.  All other tabs (Presets, Utility,
     *  Save, Load) render with the default behaviour. */
    private final class FftTabHeaderRenderer extends CTabFolderRenderer {
        protected FftTabHeaderRenderer(CTabFolder parent) { super(parent); }

        @Override
        protected Point computeSize(int part, int state, GC gc, int wHint, int hHint) {
            Point p = super.computeSize(part, state, gc, wHint, hHint);
            if (part >= 0 && part < NUM_CUSTOM_TABS) {
                ensureTabResources();
                int required = computeRequiredTabWidth(gc, part);
                p.x = Math.max(p.x, required);
            }
            return p;
        }

        @Override
        protected void draw(int part, int state, Rectangle bounds, GC gc) {
            super.draw(part, state, bounds, gc);
            if (part < 0 || part >= NUM_CUSTOM_TABS) return;
            String label = tabLabels[part];
            if (label == null || label.isEmpty()) return;
            boolean selected = (state & SWT.SELECTED) != 0;
            int leftInset = selected ? 11 : 15;
            Color fg = selected ? toolbarTabs.getSelectionForeground() : toolbarTabs.getForeground();
            if (fg == null) fg = toolbarTabs.getDisplay().getSystemColor(SWT.COLOR_WIDGET_FOREGROUND);
            gc.setForeground(fg);
            Font prev = gc.getFont();
            gc.setFont(toolbarTabs.getFont());
            gc.drawText(label, bounds.x + leftInset, bounds.y + 3, true);
            gc.setFont(prev);
        }
    }

    /** Forces the CTabFolder to recompute a custom tab's width and repaint
     *  so a related pref toggle visibly resizes the tab in real time. */
    private void refreshTabHeader(int tabIndex) {
        if (toolbarTabs == null || toolbarTabs.isDisposed()) return;
        if (tabIndex < 0 || tabIndex >= NUM_CUSTOM_TABS) return;
        updateTabSpacerImage(tabIndex);
        toolbarTabs.layout(true, true);
        toolbarTabs.redraw();
    }

    /** Recreates the invisible spacer image on a custom CTabItem so the
     *  tab is always wide enough to hold the current state's tile row. */
    private void updateTabSpacerImage(int tabIndex) {
        if (toolbarTabs == null || toolbarTabs.isDisposed()) return;
        if (tabIndex < 0 || tabIndex >= NUM_CUSTOM_TABS) return;
        if (toolbarTabs.getItemCount() <= tabIndex) return;
        Display d = toolbarTabs.getDisplay();
        ensureTabResources();
        int width;
        GC gc = new GC(toolbarTabs);
        try {
            width = Math.max(50, computeRequiredTabWidth(gc, tabIndex));
        } finally { gc.dispose(); }
        Image existing = tabSpacerImages[tabIndex];
        if (existing != null && !existing.isDisposed()) {
            Rectangle b = existing.getBounds();
            if (b.width == width && b.height == 1) return;
            existing.dispose();
        }
        PaletteData palette = new PaletteData(0xFF0000, 0x00FF00, 0x0000FF);
        ImageData id = new ImageData(width, 1, 24, palette);
        id.alphaData = new byte[width];      // fully transparent
        Image img = new Image(d, id);
        tabSpacerImages[tabIndex] = img;
        toolbarTabs.getItem(tabIndex).setImage(img);
    }

    /** Minimum tab width = max(label, tile row) + extra padding (a healthy
     *  20 px so a long tile chain never under-allocates and clips the
     *  rightmost tile). */
    private int computeRequiredTabWidth(GC gc, int tabIndex) {
        if (tabIndex < 0 || tabIndex >= NUM_CUSTOM_TABS) return 0;
        Font prev = gc.getFont();
        gc.setFont(tabTileFont);
        int tilesW = computeTileRowWidth(gc, tabIndex);
        gc.setFont(toolbarTabs.getFont());
        String label = tabLabels[tabIndex];
        int labelW = (label != null && !label.isEmpty()) ? gc.textExtent(label).x + 14 : 0;
        gc.setFont(prev);
        return Math.max(tilesW, labelW) + 28;
    }

    private int computeTileRowWidth(GC gc, int tabIndex) {
        Preferences prefs = Preferences.instance();
        final int hPadding = 4;
        final int gap      = 3;
        int total = 0;
        for (String text : tileTexts(prefs, tabIndex)) {
            total += gc.textExtent(text).x + 2 * hPadding + gap;
        }
        return Math.max(0, total - gap);
    }

    /** Returns the per-tile text strings for the given tab, derived from
     *  the current prefs.  Order = visual order, left to right. */
    private static List<String> tileTexts(Preferences prefs, int tabIndex) {
        List<String> out = new ArrayList<>();
        if (tabIndex == TAB_FFT_SETTINGS) {
            out.add(shortFftLength(prefs.getFftLength()));
            out.add(shortWindow(prefs.getFftWindow()));
            out.add(shortOverlap(prefs.getFftOverlap()));
            out.add(formatAverages(prefs.getFftAverages()) + "×");
            out.add(prefs.isFftCoherentAveraging() ? "coh" : "inc");
        } else if (tabIndex == TAB_THD_SETTINGS) {
            out.add(shortDistRange(prefs));
            out.add("H" + prefs.getFftThdMaxHarmonic());
            if (prefs.isFftManualFundEnabled()) {
                out.add("manF");
            }
        }
        return out;
    }

    /** Paints a row of state-aware tiles under each custom tab's label. */
    private void drawTabTiles(GC gc, Rectangle bounds, int tabIndex) {
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) return;
        if (tabIndex < 0 || tabIndex >= NUM_CUSTOM_TABS) return;
        Preferences prefs = Preferences.instance();
        ensureTabResources();
        Font prev = gc.getFont();
        gc.setFont(tabTileFont);
        gc.setAntialias(SWT.ON);
        gc.setTextAntialias(SWT.ON);
        final int tileH    = 22;
        final int cornerR  = 4;
        final int hPadding = 4;
        final int gap      = 3;
        boolean selected = (toolbarTabs.getSelectionIndex() == tabIndex);
        int leftInset    = selected ? 11 : 15;
        int y = bounds.y + bounds.height - tileH - 2;
        int x = bounds.x + leftInset;

        if (tabIndex == TAB_FFT_SETTINGS) {
            int w1 = drawTabTextTile(gc, x, y, tileH, hPadding, cornerR,
                    shortFftLength(prefs.getFftLength()));
            addTabRegion(x, y, w1, tileH, I18n.t("fft.tile.length", prefs.getFftLength()));
            x += w1 + gap;
            int w2 = drawTabTextTile(gc, x, y, tileH, hPadding, cornerR,
                    shortWindow(prefs.getFftWindow()));
            addTabRegion(x, y, w2, tileH, I18n.t("fft.tile.window", prettyWindow(prefs.getFftWindow())));
            x += w2 + gap;
            int w3 = drawTabTextTile(gc, x, y, tileH, hPadding, cornerR,
                    shortOverlap(prefs.getFftOverlap()));
            addTabRegion(x, y, w3, tileH, I18n.t("fft.tile.overlap", prettyOverlap(prefs.getFftOverlap())));
            x += w3 + gap;
            int w4 = drawTabTextTile(gc, x, y, tileH, hPadding, cornerR,
                    formatAverages(prefs.getFftAverages()) + "×");
            addTabRegion(x, y, w4, tileH,
                    I18n.t("fft.tile.averages", formatAverages(prefs.getFftAverages())));
            x += w4 + gap;
            int w5 = drawTabTextTile(gc, x, y, tileH, hPadding, cornerR,
                    prefs.isFftCoherentAveraging() ? "coh" : "inc");
            addTabRegion(x, y, w5, tileH, I18n.t(prefs.isFftCoherentAveraging()
                    ? "fft.tile.coh" : "fft.tile.inc"));
        } else if (tabIndex == TAB_THD_SETTINGS) {
            String dr = shortDistRange(prefs);
            int w1 = drawTabTextTile(gc, x, y, tileH, hPadding, cornerR, dr);
            addTabRegion(x, y, w1, tileH, I18n.t("fft.tile.distRange", dr));
            x += w1 + gap;
            int w2 = drawTabTextTile(gc, x, y, tileH, hPadding, cornerR,
                    "H" + prefs.getFftThdMaxHarmonic());
            addTabRegion(x, y, w2, tileH, I18n.t("fft.tile.thdMaxHarmonic", prefs.getFftThdMaxHarmonic()));
            x += w2 + gap;
            if (prefs.isFftManualFundEnabled()) {
                int w3 = drawTabTextTile(gc, x, y, tileH, hPadding, cornerR, "manF");
                addTabRegion(x, y, w3, tileH, I18n.t("fft.tile.manualFund"));
            }
        }
        gc.setFont(prev);
    }

    private int drawTabTextTile(GC gc, int x, int y, int h, int padding, int corner, String text) {
        Point te = gc.textExtent(text);
        int w = te.x + 2 * padding;
        gc.setBackground(tabTileBg);
        gc.fillRoundRectangle(x, y, w, h, corner, corner);
        gc.setForeground(tabTileFg);
        gc.drawText(text, x + padding, y + (h - te.y) / 2, true);
        return w;
    }

    private void addTabRegion(int x, int y, int w, int h, String tooltip) {
        if (tooltip == null || w <= 0 || h <= 0) return;
        tabRegions.add(new TabRegion(new Rectangle(x, y, w, h), tooltip));
    }

    private void ensureTabResources() {
        if (toolbarTabs == null) return;
        Display d = toolbarTabs.getDisplay();
        if (tabTileBg   == null) tabTileBg   = new Color(d, 0xE0, 0xE0, 0xE0);
        if (tabTileFg   == null) tabTileFg   = new Color(d, 0x20, 0x20, 0x20);
        if (tabTileFont == null) {
            FontData[] fd = toolbarTabs.getFont().getFontData();
            for (FontData f : fd) f.setHeight(10);
            tabTileFont = new Font(d, fd);
        }
    }

    /** Tab-level hover tooltip key — fallback when no tile is hovered. */
    private static String tabLabelTooltipKey(int tabIndex) {
        switch (tabIndex) {
            case TAB_FFT_SETTINGS: return "fft.tab.settings.tooltip";
            case TAB_THD_SETTINGS: return "fft.tab.thd.tooltip";
            default:               return null;
        }
    }

    // ---- Short / pretty labels used in tile rows -----------------------

    private static String shortFftLength(int n) {
        if (n >= 1 << 20) return (n >> 20) + "M";
        if (n >= 1 << 10) return (n >> 10) + "k";
        return Integer.toString(n);
    }

    private static String shortWindow(String name) {
        try {
            WindowType wt = WindowType.valueOf(name);
            return WINDOW_SHORT_LABELS[wt.ordinal()];
        } catch (IllegalArgumentException e) { return "Hann"; }
    }

    private static String prettyWindow(String name) {
        try {
            WindowType wt = WindowType.valueOf(name);
            return WINDOW_LABELS[wt.ordinal()];
        } catch (IllegalArgumentException e) { return "Hann"; }
    }

    private static String shortOverlap(String name) {
        try {
            return FftOverlap.valueOf(name).label;
        } catch (IllegalArgumentException e) { return "0%"; }
    }

    private static String prettyOverlap(String name) {
        return shortOverlap(name);
    }

    private static String shortDistRange(Preferences prefs) {
        if (!prefs.isFftDistMinEnabled() && !prefs.isFftDistMaxEnabled()) return "all";
        String lo = prefs.isFftDistMinEnabled() ? shortHz(prefs.getFftDistMinHz()) : "0";
        String hi = prefs.isFftDistMaxEnabled() ? shortHz(prefs.getFftDistMaxHz()) : "∞";
        return lo + "-" + hi;
    }

    private static String shortHz(double f) {
        if (f >= 1000) return ((int) Math.round(f / 1000)) + "k";
        return Long.toString(Math.round(f));
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

    private static void addLabel(Composite parent, String text) {
        Label l = new Label(parent, SWT.NONE);
        l.setText(text);
        l.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
    }


    private static int indexOfInt(int[] arr, int value) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == value) return i;
        return -1;
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, String name, E fallback) {
        if (name == null) return fallback;
        try { return Enum.valueOf(type, name); }
        catch (IllegalArgumentException e) { return fallback; }
    }

    /** Parses an amplitude with an embedded unit suffix.  Accepts trailing
     *  {@code mV}, {@code V} (or no suffix), {@code dBV}, {@code dBFS}.
     *  Side-effect: stores the chosen unit into Preferences so the
     *  formatter re-renders the value in the same unit the user typed.
     *  Canonical return value is volts (linear). */
    private static Double parseAmplitudeWithUnit(String s) {
        if (s == null) return null;
        String t = s.trim().replace(',', '.');
        if (t.isEmpty()) return null;
        // Identify trailing unit suffix.
        String unit = "V";
        String numText = t;
        Matcher m = Pattern
                .compile("([+-]?[0-9]*\\.?[0-9]+(?:[eE][+-]?[0-9]+)?)\\s*([a-zA-Z]*)$")
                .matcher(t);
        if (!m.matches()) return null;
        numText = m.group(1);
        String u = m.group(2);
        if (!u.isEmpty()) {
            if      (u.equalsIgnoreCase("mV"))   unit = "mV";
            else if (u.equalsIgnoreCase("V"))    unit = "V";
            else if (u.equalsIgnoreCase("dBV"))  unit = "dBV";
            else if (u.equalsIgnoreCase("dBFS")) unit = "dBFS";
            else return null;
        }
        double raw;
        try { raw = Double.parseDouble(numText); }
        catch (NumberFormatException ex) { return null; }
        double v;
        switch (unit) {
            case "mV":   v = raw * 0.001; break;
            case "V":    v = raw;         break;
            case "dBV":
            case "dBFS": v = Math.pow(10, raw / 20.0); break;
            default:     v = raw;
        }
        Preferences.instance().setFftManualFundUnit(unit);
        return v;
    }

    private static String formatAmplitudeWithUnit(double vrms, String unit) {
        if (unit == null) unit = "V";
        switch (unit) {
            case "mV":   return String.format("%.3f mV",  vrms * 1000);
            case "dBV":  return String.format("%+.3f dBV", 20 * Math.log10(Math.max(1e-30, vrms)));
            case "dBFS": return String.format("%+.3f dBFS",20 * Math.log10(Math.max(1e-30, vrms)));
            case "V":
            default:     return String.format("%.4f V", vrms);
        }
    }

    /** Averages presets used by the cycling stepper (wheel / arrows). */
    private static final double[] AVERAGES_PRESETS = { 2, 4, 8, 16, 32, Double.POSITIVE_INFINITY };

    /** Wheel / arrow stepper for the averages field — snaps to the next
     *  / previous preset rather than ±1.  Manual typing in the field is
     *  unaffected because parsing goes through {@link #parseAveragesNumeric}. */
    private static double stepAveragesCycle(double current, int dir) {
        if (dir > 0) {
            for (double t : AVERAGES_PRESETS) if (t > current + 1e-9) return t;
            return Double.POSITIVE_INFINITY;
        }
        double best = 2;
        for (double t : AVERAGES_PRESETS) {
            if (Double.isInfinite(t)) break;
            if (t < current - 1e-9) best = t; else break;
        }
        return best;
    }

    /** Parses an averages-field value — accepts any positive integer
     *  AND the special tokens {@code "∞"} / {@code "forever"} → +Infinity. */
    private static Double parseAveragesNumeric(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        if (t.equals("∞") || t.equalsIgnoreCase("forever") || t.equalsIgnoreCase("inf")) {
            return Double.POSITIVE_INFINITY;
        }
        try {
            long n = Long.parseLong(t);
            return n >= 1 ? (double) n : null;
        } catch (NumberFormatException e) { return null; }
    }

    /** Formats an averages value — {@code +Infinity} → {@code "∞"},
     *  finite values → plain integer string. */
    private static String formatAverages(double v) {
        if (Double.isInfinite(v)) return "∞";
        return Long.toString((long) Math.round(v));
    }

    private static Double parseDoubleStrict(String s) {
        try { return Double.parseDouble(s.trim().replace(',', '.')); }
        catch (NumberFormatException e) { return null; }
    }

    private static Double parseIntStrict(String s) {
        try { return (double) Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return null; }
    }
}
