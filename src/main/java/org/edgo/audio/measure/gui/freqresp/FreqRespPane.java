package org.edgo.audio.measure.gui.freqresp;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabFolderRenderer;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.FormAttachment;
import org.eclipse.swt.layout.FormData;
import org.eclipse.swt.layout.FormLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.edgo.audio.measure.cli.util.FreqRespCalHelper;
import org.edgo.audio.measure.cli.util.FreqRespCalibration;
import org.edgo.audio.measure.cli.util.StereoFreqRespCalibration;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.Dialogs;
import org.edgo.audio.measure.gui.common.IconUtils;
import org.edgo.audio.measure.gui.common.SvgPaths;
import org.edgo.audio.measure.gui.generator.NumericStepField;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.preferences.Preferences;
import org.edgo.audio.measure.gui.scope.ScreenshotDialog;
import org.edgo.audio.measure.gui.widgets.FlatScrollbar;
import org.edgo.audio.measure.gui.widgets.PaneTitle;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * Host composite for the Frequency Response feature.  Mirrors
 * {@code FftPane}'s layout (PaneTitle + plot row + freq-scrollbar row +
 * toolbar row) so the two analytical panes feel visually consistent.
 *
 * <p>Phase 5 skeleton: scrollbars are live and round-trip through
 * {@link Preferences} + {@link Events#FREQRESP_RANGE_CHANGED}; the seven
 * tabs (Settings / RIAA &amp; IEC / Presets / Utility / Calibration /
 * Save-to / Load-from) are built as empty containers — their content
 * lands in Phases 6, 8, and 9.  The Play and Wizard buttons render with
 * the correct icons but their handlers are stubbed (Phase 7 + Phase 9
 * implement the measurement worker and the calibration wizard).
 */
@Log4j2
public final class FreqRespPane {

    private static final int SCROLL_RANGE = 1_000_000;
    private static final int PLAY_BTN_SIZE   = 48;
    private static final int WIZARD_BTN_SIZE = 48;

    /** Lower bound for the log-frequency scrollbar.  The view clamps
     *  freqRespFreqMinHz to ≥ 1 Hz so the log mapping is well-defined. */
    private static final double FREQ_FLOOR_HZ = 1.0;
    /** Upper bound for the magnitude axis (full dynamic range cap). */
    private static final double MAG_TOP_MAX = 20.0;
    /** Lower bound for the magnitude axis. */
    private static final double MAG_BOT_MIN = -140.0;

    @Getter private final Composite group;
    @Getter private FreqRespView view;

    private FlatScrollbar freqScrollbar;
    private FlatScrollbar magScrollbar;
    private CTabFolder   toolbarTabs;
    private Button       wizardButton;
    private Button       playButton;

    // ---- Tab-header tile rendering (mirrors FftPane's pattern) -----
    /** Tab indices, kept as constants so the renderer / tile builder /
     *  refresh callers don't have to repeat magic numbers. */
    private static final int TAB_FREQRESP_SETTINGS    = 0;
    private static final int TAB_FREQRESP_RIAA        = 1;
    private static final int TAB_FREQRESP_PRESETS     = 2;
    private static final int TAB_FREQRESP_UTILITY     = 3;
    private static final int TAB_FREQRESP_CALIBRATION = 4;
    private static final int TAB_FREQRESP_SAVE        = 5;
    private static final int TAB_FREQRESP_LOAD        = 6;
    private static final int NUM_CUSTOM_TABS          = 7;

    /** Captured tab labels — the CTabItem text is cleared after build
     *  so the renderer can draw the label + tile row itself. */
    private final String[] tabLabels       = new String[NUM_CUSTOM_TABS];
    /** Invisible spacer images on each tab; their width pads the tab to
     *  fit the tile row + label. */
    private final Image[]  tabSpacerImages = new Image[NUM_CUSTOM_TABS];
    /** Per-tile hover rectangles built every paint, consumed by the
     *  mouse-move listener for tooltips. */
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

    /** Daemon worker that runs the sweep + deconvolution off the UI
     *  thread.  Created lazily on the first Play click. */
    private FreqRespAnalyzerWorker worker;

    /** Bus subscriber kept as a field so dispose can unsubscribe the
     *  same instance (method references compare by identity). */
    private Runnable rangeChangedListener;
    private Runnable calibrationChangedListener;

    public FreqRespPane(Composite parent) {
        Display d = parent.getDisplay();
        IconUtils icons = IconUtils.instance();
        Image wandIcon = icons.renderAtHeightColored(d, SvgPaths.WAND, WIZARD_BTN_SIZE);
        // Play button uses the same green LED that the generator pane shows
        // so the visual language is consistent across measurement features.
        Image playIcon = icons.renderAtHeight(d, SvgPaths.PLAY, PLAY_BTN_SIZE,
                new RGB(0x00, 0xAA, 0x00));

        group = new Composite(parent, SWT.BORDER);
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth  = 0; gl.marginHeight = 0; gl.verticalSpacing = 2;
        group.setLayout(gl);

        PaneTitle paneTitle = new PaneTitle(group, Events.PANE_ID_FREQRESP,
                I18n.t("freqResp.title.expanded"),
                I18n.t("freqResp.title.collapsed"),
                I18n.t("freqResp.pane.toggle.tooltip"));
        paneTitle.setStaticMode(true);

        buildPlotRow();
        buildFreqScrollbarRow();
        buildToolbarRow(wandIcon, playIcon);

        // Bus subscriptions.  Range-changed re-aligns the scrollbars after
        // the view's wheel-driven pan / zoom; calibration-changed lets the
        // calibration tab refresh its file-path label without polling.
        rangeChangedListener       = this::syncScrollbars;
        calibrationChangedListener = this::onCalibrationChanged;
        MessageBus bus = MessageBus.instance();
        bus.subscribe(Events.FREQRESP_RANGE_CHANGED,        rangeChangedListener);
        bus.subscribe(Events.FREQRESP_CALIBRATION_CHANGED,  calibrationChangedListener);
        group.addDisposeListener(e -> {
            bus.unsubscribe(Events.FREQRESP_RANGE_CHANGED,        rangeChangedListener);
            bus.unsubscribe(Events.FREQRESP_CALIBRATION_CHANGED,  calibrationChangedListener);
        });

        // Initial scrollbar sync with the persisted pan window.
        syncScrollbars();
    }

    // -------------------------------------------------------------------------
    // Layout
    // -------------------------------------------------------------------------

    private void buildPlotRow() {
        Composite plotRow = new Composite(group, SWT.NONE);
        plotRow.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        plotRow.setLayout(new FormLayout());

        view = new FreqRespView(plotRow);
        magScrollbar = new FlatScrollbar(plotRow, SWT.VERTICAL);
        magScrollbar.setMinimum(0);
        magScrollbar.setMaximum(SCROLL_RANGE);
        magScrollbar.setThumb(SCROLL_RANGE / 4);
        magScrollbar.setSelection(0);
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
    }

    private void buildFreqScrollbarRow() {
        // Horizontal frequency scrollbar wrapped in a FormLayout row so its
        // right edge stops 18 px short of the group right — aligning with
        // the view's right edge (the view ends at the magScrollbar's left
        // edge, magScrollbar is 18 px wide on the right).
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
        freqScrollbar.addSelectionListener(e -> applyFreqScrollbar());
    }

    private void buildToolbarRow(Image wandIcon, Image playIcon) {
        // Toolbar row: CTabFolder (left, grabs space) + Wizard + Play
        // anchored on the right.  Mirrors the scope / FFT toolbar layout
        // so the action buttons always stay accessible.  heightHint set
        // explicitly so the Settings tab's 4-row body has room — without
        // it the CTabFolder gets vertically squeezed and the bottom row
        // (dither / Nyquist combos) clips out of view.
        Composite toolbarRow = new Composite(group, SWT.NONE);
        GridData trGd = new GridData(SWT.FILL, SWT.BEGINNING, true, false);
        trGd.heightHint = 200;
        toolbarRow.setLayoutData(trGd);
        GridLayout trGl = new GridLayout(3, false);
        trGl.marginWidth = 0; trGl.marginHeight = 0; trGl.horizontalSpacing = 4;
        toolbarRow.setLayout(trGl);

        toolbarTabs = new CTabFolder(toolbarRow, SWT.NONE);
        toolbarTabs.setSimple(false);
        toolbarTabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        buildSettingsTab();
        buildRiaaTab();
        buildEmptyTab("freqResp.tab.presets");      // deferred — FreqResp preset POJO clone-out lives outside this plan's scope
        buildUtilityTab();
        buildCalibrationTab();
        buildSaveToTab();
        buildLoadFromTab();
        int activeTab = Math.max(0, Math.min(toolbarTabs.getItemCount() - 1,
                Preferences.instance().getFreqRespActiveTabIndex()));
        if (toolbarTabs.getItemCount() > 0) toolbarTabs.setSelection(activeTab);
        toolbarTabs.addListener(SWT.Selection, e -> {
            Preferences.instance().setFreqRespActiveTabIndex(toolbarTabs.getSelectionIndex());
            Preferences.instance().save();
        });

        // ─── Tab-header tiles ─────────────────────────────────────────────
        // Capture each tab's label, clear the CTabItem text (so CTabFolder's
        // centred-label render doesn't fight the custom one), give the strip
        // enough vertical room for a label + tile row, and seed an invisible
        // spacer image so the tab is wide enough.
        for (int i = 0; i < NUM_CUSTOM_TABS && i < toolbarTabs.getItemCount(); i++) {
            tabLabels[i] = toolbarTabs.getItem(i).getText();
            toolbarTabs.getItem(i).setText("");
        }
        toolbarTabs.setTabHeight(46);
        toolbarTabs.setRenderer(new FreqRespTabHeaderRenderer(toolbarTabs));
        for (int i = 0; i < NUM_CUSTOM_TABS && i < toolbarTabs.getItemCount(); i++) {
            updateTabSpacerImage(i);
        }
        // Paint listener: draw tiles on top of CTabFolder's default chrome
        // and rebuild the per-tile hover regions.
        toolbarTabs.addPaintListener(e -> {
            tabRegions.clear();
            for (int i = 0; i < NUM_CUSTOM_TABS && i < toolbarTabs.getItemCount(); i++) {
                if (!toolbarTabs.getItem(i).isDisposed()) {
                    drawTabTiles(e.gc, toolbarTabs.getItem(i).getBounds(), i);
                }
            }
        });
        // Per-tile hover tooltip — region tips resolved at paint time so
        // they reflect current state without an extra lookup at hover.
        final String[] currentTip = { null };
        final int[]    currentIdx = { -1 };
        toolbarTabs.addMouseMoveListener(e -> {
            String tip = null;
            for (TabRegion r : tabRegions) {
                if (r.bounds.contains(e.x, e.y)) { tip = r.tooltip; break; }
            }
            int hoverIdx = -1;
            for (int i = 0; i < toolbarTabs.getItemCount(); i++) {
                if (!toolbarTabs.getItem(i).isDisposed()
                        && toolbarTabs.getItem(i).getBounds().contains(e.x, e.y)) {
                    hoverIdx = i;
                    break;
                }
            }
            if (hoverIdx != currentIdx[0] || !Objects.equals(currentTip[0], tip)) {
                currentIdx[0] = hoverIdx;
                currentTip[0] = tip;
                toolbarTabs.setToolTipText(tip);
            }
        });
        // Dispose tile resources when the pane goes away.
        group.addDisposeListener(e -> {
            for (Image img : tabSpacerImages) {
                if (img != null && !img.isDisposed()) img.dispose();
            }
            if (tabTileBg   != null && !tabTileBg.isDisposed())   tabTileBg.dispose();
            if (tabTileFg   != null && !tabTileFg.isDisposed())   tabTileFg.dispose();
            if (tabTileFont != null && !tabTileFont.isDisposed()) tabTileFont.dispose();
        });

        // Wizard button (left of Play) — opens the 3-page calibration
        // wizard in Phase 9.  For now the button paints with the wand
        // icon but the click is a no-op log line.
        wizardButton = new Button(toolbarRow, SWT.PUSH);
        if (wandIcon != null) wizardButton.setImage(wandIcon);
        wizardButton.setToolTipText(I18n.t("freqResp.button.wizard.tooltip"));
        GridData wbGd = new GridData(SWT.END, SWT.BEGINNING, false, false);
        wbGd.widthHint  = WIZARD_BTN_SIZE + 8;
        wbGd.heightHint = WIZARD_BTN_SIZE + 8;
        wizardButton.setLayoutData(wbGd);
        wizardButton.addListener(SWT.Selection, e ->
                new FreqRespWizardDialog(group.getShell(), view).open());

        // Play button — kicks off a sweep measurement.  Click is ignored
        // while a measurement is already in flight (the lock helper has
        // disabled it anyway but the listener guards defensively).
        playButton = new Button(toolbarRow, SWT.PUSH);
        if (playIcon != null) playButton.setImage(playIcon);
        playButton.setToolTipText(I18n.t("freqResp.button.play.start"));
        GridData pbGd = new GridData(SWT.END, SWT.BEGINNING, false, false);
        pbGd.widthHint  = PLAY_BTN_SIZE + 8;
        pbGd.heightHint = PLAY_BTN_SIZE + 8;
        playButton.setLayoutData(pbGd);
        playButton.addListener(SWT.Selection, e -> onPlayClicked());
    }

    /** Adds an empty CTabItem with the given i18n key as its label.  Each
     *  tab gets a placeholder Composite so subsequent phases can wire
     *  real content into it. */
    private void buildEmptyTab(String labelKey) {
        CTabItem item = new CTabItem(toolbarTabs, SWT.NONE);
        item.setText(I18n.t(labelKey));
        Composite content = new Composite(toolbarTabs, SWT.NONE);
        content.setLayout(new GridLayout(1, false));
        Label placeholder = new Label(content, SWT.NONE);
        placeholder.setText("");
        item.setControl(content);
    }

    // -------------------------------------------------------------------------
    // Settings tab — sweep parameters
    // -------------------------------------------------------------------------

    /** Power-of-2 sweep-point options offered by the dropdown.  "Sample
     *  rate / 2" and "Manual…" are added at runtime ahead of these. */
    private static final int[] SWEEP_POINT_VALUES = {
            8192, 16384, 65536, 131072, 262144,
            524288, 1048576, 2097152, 4194304
    };
    private static final String[] SWEEP_POINT_LABELS = {
            "8k", "16k", "64k", "128k", "256k", "512k", "1M", "2M", "4M"
    };

    /** Nyquist-fraction options exposed by the dropdown (the spec says
     *  40-50 %, so we offer 0.5 %-spaced steps in that range). */
    private static final double[] NYQUIST_FRAC_VALUES = {
            0.40, 0.42, 0.44, 0.46, 0.48, 0.50
    };

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
        NumericStepField startField = freqField(g, prefs.getFreqRespStartHz());
        startField.setToolTipText(I18n.t("freqResp.settings.start.tooltip"));
        startField.addSelectionListener(e -> {
            prefs.setFreqRespStartHz(Math.max(1.0, startField.getValue()));
            prefs.save();
            refreshTabHeader(TAB_FREQRESP_SETTINGS);
        });

        addLabel(g, I18n.t("freqResp.settings.stop"));
        NumericStepField stopField = freqField(g, prefs.getFreqRespStopHz());
        stopField.setToolTipText(I18n.t("freqResp.settings.stop.tooltip"));
        stopField.addSelectionListener(e -> {
            prefs.setFreqRespStopHz(Math.max(prefs.getFreqRespStartHz() + 1.0,
                    stopField.getValue()));
            prefs.save();
            refreshTabHeader(TAB_FREQRESP_SETTINGS);
        });

        // ---- Row 2: amplitude (Vrms) + duration ----------------------------
        addLabel(g, I18n.t("freqResp.settings.amplitude"));
        NumericStepField ampField = new NumericStepField(g,
                prefs.getFreqRespAmplitudeVrms(),
                this::parseDouble,
                v -> String.format(Locale.ROOT, "%.4f V", v),
                (v, dir) -> v * (1.0 + 0.05 * dir),   // wheel: ±5 %
                (v, dir) -> Math.max(0.0001, v + 0.1 * dir),  // arrows: ±0.1 V
                110);
        ampField.setLayoutData(comboGd());
        ampField.setToolTipText(I18n.t("freqResp.settings.amplitude.tooltip"));
        ampField.addSelectionListener(e -> {
            prefs.setFreqRespAmplitudeVrms(Math.max(0.0001, ampField.getValue()));
            prefs.save();
            refreshTabHeader(TAB_FREQRESP_SETTINGS);
        });

        addLabel(g, I18n.t("freqResp.settings.duration"));
        NumericStepField durationField = new NumericStepField(g,
                prefs.getFreqRespDurationSec(),
                this::parseDouble,
                v -> String.format(Locale.ROOT, "%.1f s", v),
                (v, dir) -> Math.max(0.5, v + 0.5 * dir),     // wheel: ±0.5 s
                (v, dir) -> Math.max(0.5, v + 0.5 * dir),     // arrows: ±0.5 s
                90);
        durationField.setLayoutData(comboGd());
        durationField.setToolTipText(I18n.t("freqResp.settings.duration.tooltip"));
        durationField.addSelectionListener(e -> {
            prefs.setFreqRespDurationSec(Math.max(0.5, durationField.getValue()));
            prefs.save();
            refreshTabHeader(TAB_FREQRESP_SETTINGS);
        });

        // ---- Row 3: sweep points combo + lead-in ---------------------------
        addLabel(g, I18n.t("freqResp.settings.points"));
        Combo pointsCombo = new Combo(g, SWT.READ_ONLY);
        pointsCombo.add(I18n.t("freqResp.settings.points.sampleRateHalf"));
        for (String s : SWEEP_POINT_LABELS) pointsCombo.add(s);
        pointsCombo.add(I18n.t("freqResp.settings.points.manual"));
        pointsCombo.setToolTipText(I18n.t("freqResp.settings.points.tooltip"));
        pointsCombo.setLayoutData(comboGd());
        selectPointsCombo(pointsCombo, prefs.getFreqRespSweepPoints());
        pointsCombo.addListener(SWT.Selection, e -> handlePointsCombo(pointsCombo));

        addLabel(g, I18n.t("freqResp.settings.leadIn"));
        NumericStepField leadInField = new NumericStepField(g,
                prefs.getFreqRespLeadInSec(),
                this::parseDouble,
                v -> String.format(Locale.ROOT, "%.2f s", v),
                (v, dir) -> Math.max(0.05, v + 1.0 * dir),    // wheel: ±1 s
                (v, dir) -> Math.max(0.05, v + 0.5 * dir),    // arrows: ±0.5 s
                90);
        leadInField.setLayoutData(comboGd());
        leadInField.setToolTipText(I18n.t("freqResp.settings.leadIn.tooltip"));
        leadInField.addSelectionListener(e -> {
            prefs.setFreqRespLeadInSec(Math.max(0.05, leadInField.getValue()));
            prefs.save();
        });

        // ---- Row 4: dither + nyquist fraction ------------------------------
        addLabel(g, I18n.t("freqResp.settings.dither"));
        Combo ditherCombo = new Combo(g, SWT.READ_ONLY);
        for (int i = 0; i <= 31; i++) ditherCombo.add(i == 0 ? "Off" : String.valueOf(i));
        ditherCombo.select(Math.max(0, Math.min(31, prefs.getFreqRespDitherBits())));
        ditherCombo.setLayoutData(comboGd());
        ditherCombo.setToolTipText(I18n.t("freqResp.settings.dither.tooltip"));
        ditherCombo.addListener(SWT.Selection, e -> {
            prefs.setFreqRespDitherBits(ditherCombo.getSelectionIndex());
            prefs.save();
        });

        addLabel(g, I18n.t("freqResp.settings.nyquistFrac"));
        Combo nyqCombo = new Combo(g, SWT.READ_ONLY);
        for (double v : NYQUIST_FRAC_VALUES) {
            nyqCombo.add(String.format(Locale.ROOT, "%.0f %%", v * 100));
        }
        int nyqIdx = nearestIndex(NYQUIST_FRAC_VALUES, prefs.getFreqRespNyquistFraction());
        nyqCombo.select(nyqIdx);
        nyqCombo.setLayoutData(comboGd());
        nyqCombo.setToolTipText(I18n.t("freqResp.settings.nyquistFrac.tooltip"));
        nyqCombo.addListener(SWT.Selection, e -> {
            int i = nyqCombo.getSelectionIndex();
            if (i >= 0 && i < NYQUIST_FRAC_VALUES.length) {
                prefs.setFreqRespNyquistFraction(NYQUIST_FRAC_VALUES[i]);
                prefs.save();
                view.redraw();
            }
        });
    }

    private NumericStepField freqField(Composite parent, double initial) {
        NumericStepField f = new NumericStepField(parent, initial,
                this::parseDouble,
                v -> {
                    if (v >= 1000) return String.format(Locale.ROOT, "%.3f kHz", v / 1000);
                    return String.format(Locale.ROOT, "%.2f Hz", v);
                },
                (v, dir) -> v * (1.0 + 0.05 * dir),         // wheel: ±5 %
                (v, dir) -> Math.max(1.0, v + 1.0 * dir),   // arrows: ±1 Hz
                110);
        f.setLayoutData(comboGd());
        return f;
    }

    /** Parses a free-form numeric string like "1000", "1k", "20 kHz", "20Hz",
     *  "0.5", "−6 dBV" → double.  For dBV inputs returns the corresponding
     *  linear voltage so the amplitude field's caller can pass dBV-friendly
     *  text without a separate parser.  Returns {@code null} on failure. */
    private Double parseDouble(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT)
                .replace(',', '.')
                .replace("hz", "").replace(" ", "");
        if (s.isEmpty()) return null;
        boolean dbv = s.endsWith("dbv");
        if (dbv) s = s.substring(0, s.length() - 3);
        double mult = 1.0;
        if (s.endsWith("k")) { mult = 1000.0;     s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("m")) { mult = 1.0e-3; s = s.substring(0, s.length() - 1); }
        try {
            double v = Double.parseDouble(s) * mult;
            return dbv ? Math.pow(10, v / 20.0) : v;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void selectPointsCombo(Combo combo, int currentPoints) {
        for (int i = 0; i < SWEEP_POINT_VALUES.length; i++) {
            if (SWEEP_POINT_VALUES[i] == currentPoints) {
                combo.select(i + 1);  // +1 because "SR/2" is at index 0
                return;
            }
        }
        combo.select(0);  // default to SR/2 when no exact match
    }

    private void handlePointsCombo(Combo combo) {
        Preferences prefs = Preferences.instance();
        int idx = combo.getSelectionIndex();
        int srHalf = prefs.current().getInputSampleRate() / 2;
        if (idx == 0) {
            // Sample rate / 2 — use the active backend's input sample rate.
            prefs.setFreqRespSweepPoints(Math.max(4, srHalf));
        } else if (idx >= 1 && idx <= SWEEP_POINT_VALUES.length) {
            prefs.setFreqRespSweepPoints(SWEEP_POINT_VALUES[idx - 1]);
        } else {
            // "Manual..." — prompt the user for an integer.
            Shell shell = combo.getShell();
            String entered = Dialogs.promptString(shell,
                    I18n.t("freqResp.settings.points.manual.title"),
                    I18n.t("freqResp.settings.points.manual.prompt"),
                    String.valueOf(prefs.getFreqRespSweepPoints()));
            if (entered != null) {
                try {
                    int n = Integer.parseInt(entered.trim());
                    if (n >= 4) prefs.setFreqRespSweepPoints(n);
                } catch (NumberFormatException ignored) {}
            }
            // Re-sync the visible selection to whatever's now in prefs.
            selectPointsCombo(combo, prefs.getFreqRespSweepPoints());
        }
        prefs.save();
        refreshTabHeader(TAB_FREQRESP_SETTINGS);
    }

    private int nearestIndex(double[] values, double target) {
        int best = 0;
        double bestDist = Double.POSITIVE_INFINITY;
        for (int i = 0; i < values.length; i++) {
            double d = Math.abs(values[i] - target);
            if (d < bestDist) { bestDist = d; best = i; }
        }
        return best;
    }

    // -------------------------------------------------------------------------
    // RIAA & IEC tab — chained checkboxes + Compare
    // -------------------------------------------------------------------------

    private Button riaaShowBtn;
    private Button riaaReverseBtn;
    private Button riaaIecBtn;
    private Button riaaCompareBtn;

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

        riaaShowBtn.addListener(SWT.Selection, e -> {
            prefs.setFreqRespShowRiaa(riaaShowBtn.getSelection());
            prefs.save();
            refreshRiaaEnable();
            view.redraw();
            refreshTabHeader(TAB_FREQRESP_RIAA);
        });
        riaaReverseBtn.addListener(SWT.Selection, e -> {
            prefs.setFreqRespReverseRiaa(riaaReverseBtn.getSelection());
            prefs.save();
            refreshRiaaEnable();
            view.redraw();
            refreshTabHeader(TAB_FREQRESP_RIAA);
        });
        riaaIecBtn.addListener(SWT.Selection, e -> {
            prefs.setFreqRespIecAmendment(riaaIecBtn.getSelection());
            prefs.save();
            view.redraw();
            refreshTabHeader(TAB_FREQRESP_RIAA);
        });
        riaaCompareBtn.addListener(SWT.Selection, e -> {
            if (!view.hasAnyResult()) {
                Dialogs.info(g.getShell(), I18n.t("freqResp.tab.riaa"),
                        I18n.t("freqResp.error.compare.noMeasurement"));
                riaaCompareBtn.setSelection(false);
                return;
            }
            boolean enable = riaaCompareBtn.getSelection();
            prefs.setFreqRespCompareMode(enable);
            prefs.save();
            // One-shot auto-zoom on entry only — the user's subsequent pan /
            // zoom must stick instead of being clobbered on every redraw.
            if (enable) view.autozoomCompareIfNeeded(prefs);
            view.redraw();
            refreshTabHeader(TAB_FREQRESP_RIAA);
        });

        refreshRiaaEnable();
    }

    private void refreshRiaaEnable() {
        // Cascade: Show RIAA enables Reverse and IEC (independently); their
        // selection state is preserved across toggling Show so the user can
        // hide and re-show the overlay without losing their settings.
        // Compare requires a measurement to be present.
        boolean show = riaaShowBtn.getSelection();
        riaaReverseBtn.setEnabled(show);
        riaaIecBtn.setEnabled(show);
        riaaCompareBtn.setEnabled(show && view.hasAnyResult());
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
        Image cameraIcon = icons.renderAtHeight(g.getDisplay(), SvgPaths.CAMERA, 16, null);
        Image gaugeIcon  = icons.renderAtHeight(g.getDisplay(), SvgPaths.GAUGE_HIGH, 16, null);

        // Icon-only buttons; the action label lives in the tooltip so the
        // toolbar stays compact, matching the scope's utility row.
        Button screenshotBtn = new Button(g, SWT.PUSH);
        if (cameraIcon != null) screenshotBtn.setImage(cameraIcon);
        screenshotBtn.setToolTipText(I18n.t("freqResp.utility.screenshot.tooltip"));
        screenshotBtn.addListener(SWT.Selection, e -> openScreenshotDialog());

        Button dacCalBtn = new Button(g, SWT.PUSH);
        if (gaugeIcon != null) dacCalBtn.setImage(gaugeIcon);
        dacCalBtn.setToolTipText(I18n.t("freqResp.utility.calibrateDac.tooltip"));
        dacCalBtn.addListener(SWT.Selection, e ->
                log.info("FreqResp DAC-cal clicked (dialog wired in Phase 6 follow-up)"));

        Button adcCalBtn = new Button(g, SWT.PUSH);
        if (gaugeIcon != null) adcCalBtn.setImage(gaugeIcon);
        adcCalBtn.setToolTipText(I18n.t("freqResp.utility.calibrateAdc.tooltip"));
        adcCalBtn.addListener(SWT.Selection, e ->
                log.info("FreqResp ADC-cal clicked (dialog wired in Phase 6 follow-up)"));
    }

    /** Opens the shared {@link ScreenshotDialog} with a renderer that
     *  captures the current view at the requested size via
     *  {@code Control.print(GC)}.  Saves / clipboard-copies the
     *  freq-response chart as the user sees it. */
    private void openScreenshotDialog() {
        ScreenshotDialog dlg = new ScreenshotDialog(
                group.getShell(),
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
                    Preferences.instance().setFreqRespSaveFolder(folder);
                    Preferences.instance().save();
                });
        dlg.open();
    }

    // -------------------------------------------------------------------------
    // Calibration tab — load + clear + active-path display
    // -------------------------------------------------------------------------

    private Text calibrationPathField;

    private void buildCalibrationTab() {
        CTabItem item = new CTabItem(toolbarTabs, SWT.NONE);
        item.setText(I18n.t("freqResp.tab.calibration"));
        Composite g = new Composite(toolbarTabs, SWT.NONE);
        item.setControl(g);
        GridLayout gl = new GridLayout(3, false);
        gl.marginWidth = 8; gl.marginHeight = 6;
        gl.horizontalSpacing = 6;
        g.setLayout(gl);

        calibrationPathField = new Text(g, SWT.BORDER | SWT.READ_ONLY);
        GridData pfGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        pfGd.widthHint = 360;
        calibrationPathField.setLayoutData(pfGd);
        refreshCalibrationPathField();

        Image folderIcon = IconUtils.instance().renderAtHeight(
                g.getDisplay(), SvgPaths.FOLDER_OPEN, 16, null);
        Button loadBtn = new Button(g, SWT.PUSH);
        if (folderIcon != null) loadBtn.setImage(folderIcon);
        loadBtn.setText(I18n.t("freqResp.calibration.load"));
        loadBtn.setToolTipText(I18n.t("freqResp.calibration.load.tooltip"));
        loadBtn.addListener(SWT.Selection, e -> openCalibrationFileDialog());

        Image xmark = IconUtils.instance().renderAtHeight(
                group.getDisplay(), SvgPaths.RECTANGLE_XMARK, 16,
                new RGB(0xC8, 0x28, 0x28));
        Button clearBtn = new Button(g, SWT.PUSH);
        if (xmark != null) clearBtn.setImage(xmark);
        clearBtn.setToolTipText(I18n.t("freqResp.calibration.clear.tooltip"));
        clearBtn.addListener(SWT.Selection, e -> {
            FreqRespCalibrationStore.instance().clearCurrent();
            Preferences.instance().setFreqRespCalibrationPath(null);
            Preferences.instance().save();
            refreshCalibrationPathField();
        });
    }

    private void openCalibrationFileDialog() {
        FileDialog fd = new FileDialog(group.getShell(), SWT.OPEN);
        fd.setText(I18n.t("freqResp.calibration.dialog"));
        fd.setFilterExtensions(new String[]{ "*.frc", "*.csv", "*.*" });
        String memFolder = Preferences.instance().getFreqRespLoadFolder();
        if (memFolder != null) fd.setFilterPath(memFolder);
        String picked = fd.open();
        if (picked == null) return;
        try {
            StereoFreqRespCalibration cal = FreqRespCalHelper.loadCsv(picked);
            FreqRespCalibrationStore.instance().setCurrent(cal, picked);
            Preferences prefs = Preferences.instance();
            prefs.setFreqRespCalibrationPath(picked);
            prefs.setFreqRespLoadFolder(new File(picked).getParent());
            prefs.save();
            refreshCalibrationPathField();
        } catch (Exception ex) {
            log.warn("FreqResp calibration load failed", ex);
            Dialogs.error(group.getShell(),
                    I18n.t("freqResp.calibration.dialog"),
                    I18n.t("freqResp.error.calibration.load").replace("{0}",
                            ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
        }
    }

    /** Re-syncs the calibration path text field with the active calibration.
     *  Called from {@link #onCalibrationChanged} so the field updates whether
     *  the user loaded via the tab, the wizard, or the clear button. */
    private void refreshCalibrationPathField() {
        if (calibrationPathField == null || calibrationPathField.isDisposed()) return;
        String path = FreqRespCalibrationStore.instance().getCurrentPath();
        if (path == null || path.isEmpty()) {
            calibrationPathField.setText(I18n.t("freqResp.calibration.path.none"));
            calibrationPathField.setToolTipText(null);
        } else {
            calibrationPathField.setText(path);
            calibrationPathField.setToolTipText(path);
        }
    }

    // -------------------------------------------------------------------------
    // Save-to tab — write the current measurement to a CSV
    // -------------------------------------------------------------------------

    private void buildSaveToTab() {
        CTabItem item = new CTabItem(toolbarTabs, SWT.NONE);
        item.setText(I18n.t("freqResp.tab.saveTo"));
        Composite g = new Composite(toolbarTabs, SWT.NONE);
        item.setControl(g);
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 6; gl.marginHeight = 4;
        g.setLayout(gl);

        // Single icon-only button: clicking opens the Save-as dialog and
        // writes the current measurement; the action label is in the
        // tooltip.  Matches the user's spec — no path field, no text.
        Image floppyIcon = IconUtils.instance().renderAtHeight(
                g.getDisplay(), SvgPaths.FLOPPY_DISK, 16, null);
        Button saveBtn = new Button(g, SWT.PUSH);
        if (floppyIcon != null) saveBtn.setImage(floppyIcon);
        saveBtn.setToolTipText(I18n.t("freqResp.saveTo.tooltip"));
        saveBtn.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        saveBtn.addListener(SWT.Selection, e -> openSaveDialog());
    }


    private void openSaveDialog() {
        FreqRespResult left  = view.getLeftResultOrNull();
        FreqRespResult right = view.getRightResultOrNull();
        if (left == null && right == null) {
            Dialogs.info(group.getShell(),
                    I18n.t("freqResp.tab.saveTo"),
                    I18n.t("freqResp.saveTo.error.noResult"));
            return;
        }
        FileDialog fd = new FileDialog(group.getShell(), SWT.SAVE);
        fd.setText(I18n.t("freqResp.saveTo.dialog"));
        fd.setFilterExtensions(new String[]{ "*.frc", "*.csv" });
        fd.setOverwrite(true);
        Preferences prefs = Preferences.instance();
        String memFolder = prefs.getFreqRespSaveFolder();
        if (memFolder != null) fd.setFilterPath(memFolder);
        fd.setFileName("freqresp_" + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".frc");
        String picked = fd.open();
        if (picked == null) return;
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
            Dialogs.error(group.getShell(),
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
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 6; gl.marginHeight = 4;
        g.setLayout(gl);

        // Single icon-only button: clicking opens the Open dialog and
        // pushes the result onto the view.  Matches the user's spec —
        // no path field, no text.
        Image folderIcon = IconUtils.instance().renderAtHeight(
                g.getDisplay(), SvgPaths.FOLDER_OPEN, 16, null);
        Button loadBtn = new Button(g, SWT.PUSH);
        if (folderIcon != null) loadBtn.setImage(folderIcon);
        loadBtn.setToolTipText(I18n.t("freqResp.loadFrom.tooltip"));
        loadBtn.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        loadBtn.addListener(SWT.Selection, e -> openLoadDialog());
    }

    /** Opens the load-from file dialog, parses the chosen file, and pushes
     *  the result onto the view.  Detects single- vs. stereo-channel files
     *  via the {@code _r} columns introduced in {@code format_version=5}
     *  and populates both view slots when the file is stereo. */
    private void openLoadDialog() {
        Preferences prefs = Preferences.instance();
        FileDialog fd = new FileDialog(group.getShell(), SWT.OPEN);
        fd.setText(I18n.t("freqResp.loadFrom.dialog"));
        fd.setFilterExtensions(new String[]{ "*.frc", "*.csv", "*.*" });
        if (prefs.getFreqRespLoadFolder() != null) fd.setFilterPath(prefs.getFreqRespLoadFolder());
        String picked = fd.open();
        if (picked == null) return;
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
            prefs.setFreqRespLoadFolder(new File(picked).getParent());
            prefs.save();
        } catch (Exception ex) {
            log.warn("FreqResp load failed", ex);
            Dialogs.error(group.getShell(),
                    I18n.t("freqResp.loadFrom.dialog"),
                    I18n.t("freqResp.error.measurement.load").replace("{0}",
                            ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Play / measurement orchestration
    // -------------------------------------------------------------------------

    /** Handler for the big Play button.  Lazily creates the worker, then
     *  kicks off a sweep.  While the worker is running, the entire pane
     *  (including this button) is locked via {@link #setLocked(boolean)}
     *  and a modal "please wait" shell is shown so the user can't trigger
     *  another action mid-measurement. */
    private void onPlayClicked() {
        if (worker != null && worker.isRunning()) return;
        if (worker == null) {
            worker = new FreqRespAnalyzerWorker(
                    group.getDisplay(), view,
                    () -> { setLocked(false); closeBusyShell(); },
                    msg  -> { showError(msg); closeBusyShell(); });
        }
        setLocked(true);
        openBusyShell();
        worker.start();
    }

    /** Shell shown for the duration of a measurement / deconvolution.
     *  Created on entry, closed by the worker's completion or error path. */
    private Shell busyShell;

    private void openBusyShell() {
        if (busyShell != null && !busyShell.isDisposed()) return;
        Shell parent = group.getShell();
        Shell s = new Shell(parent, SWT.ON_TOP | SWT.TITLE | SWT.BORDER
                | SWT.APPLICATION_MODAL);
        s.setText(I18n.t("freqResp.busy.title"));
        FillLayout fl = new FillLayout();
        fl.marginWidth = 24; fl.marginHeight = 18;
        s.setLayout(fl);
        Label l = new Label(s, SWT.CENTER);
        l.setText(I18n.t("freqResp.busy.message"));
        s.pack();
        Rectangle pb = parent.getBounds();
        Point sz = s.getSize();
        s.setLocation(pb.x + (pb.width  - sz.x) / 2,
                      pb.y + (pb.height - sz.y) / 2);
        s.open();
        // Pump a few events so the OS actually paints the shell before the
        // worker's first sweep call blocks the UI thread.
        Display d = parent.getDisplay();
        for (int i = 0; i < 5; i++) if (!d.readAndDispatch()) break;
        busyShell = s;
    }

    private void closeBusyShell() {
        if (busyShell != null && !busyShell.isDisposed()) busyShell.close();
        busyShell = null;
    }

    private void showError(String message) {
        if (group.isDisposed()) return;
        Dialogs.error(group.getShell(),
                I18n.t("freqResp.error.start.title"),
                message != null ? message : "");
    }

    /** Disables every interactive control under this pane (recursively).
     *  When unlocked, restores enabled state.  Spec literally calls for
     *  "all tab control readonly and the button play too" — disabling the
     *  root {@link Composite} cascades correctly to children on every
     *  platform SWT supports. */
    private void setLocked(boolean locked) {
        if (group.isDisposed()) return;
        group.setEnabled(!locked);
    }

    // -------------------------------------------------------------------------
    // Scrollbar wiring — log-frequency horizontal, linear-dB vertical
    // -------------------------------------------------------------------------

    /** Re-aligns the freq and mag scrollbar thumbs + selections from the
     *  current Preferences pan window.  Called after every pan / zoom
     *  publish on {@link Events#FREQRESP_RANGE_CHANGED}, plus once at
     *  construction. */
    private void syncScrollbars() {
        if (freqScrollbar == null || freqScrollbar.isDisposed()) return;
        if (magScrollbar  == null || magScrollbar.isDisposed())  return;
        Preferences prefs = Preferences.instance();

        // Frequency scrollbar — log scale spans FREQ_FLOOR..Nyquist.
        double nyq = nyquistHz();
        double a   = Math.log10(Math.max(FREQ_FLOOR_HZ, FREQ_FLOOR_HZ));
        double b   = Math.log10(Math.max(a + 1, nyq));
        double lo  = Math.log10(Math.max(FREQ_FLOOR_HZ, prefs.getFreqRespFreqMinHz()));
        double hi  = Math.log10(Math.max(lo + 1e-9, prefs.getFreqRespFreqMaxHz()));
        double visibleF = hi - lo;
        double totalF   = b - a;
        if (totalF > 0) {
            int thumb = (int) Math.max(1, Math.min(SCROLL_RANGE,
                    SCROLL_RANGE * (visibleF / totalF)));
            double pos = (totalF - visibleF) > 0
                    ? (lo - a) / (totalF - visibleF) : 0.0;
            int sel = (int) Math.max(0, Math.min(SCROLL_RANGE - thumb,
                    pos * (SCROLL_RANGE - thumb)));
            freqScrollbar.setThumb(thumb);
            freqScrollbar.setSelection(sel);
            freqScrollbar.setPageIncrement(Math.max(1, thumb / 2));
            freqScrollbar.setIncrement(Math.max(1, thumb / 10));
        }

        // Magnitude scrollbar — linear dB range capped at MAG_TOP_MAX /
        // MAG_BOT_MIN.  Thumb size shows the visible slice's share.
        double visibleM = prefs.getFreqRespMagTopDb() - prefs.getFreqRespMagBotDb();
        double totalM   = MAG_TOP_MAX - MAG_BOT_MIN;
        if (totalM > 0 && visibleM > 0) {
            int thumb = (int) Math.max(1, Math.min(SCROLL_RANGE,
                    SCROLL_RANGE * (visibleM / totalM)));
            double pos = (totalM - visibleM) > 0
                    ? (MAG_TOP_MAX - prefs.getFreqRespMagTopDb()) / (totalM - visibleM)
                    : 0.0;
            int sel = (int) Math.max(0, Math.min(SCROLL_RANGE - thumb,
                    pos * (SCROLL_RANGE - thumb)));
            magScrollbar.setThumb(thumb);
            magScrollbar.setSelection(sel);
            magScrollbar.setPageIncrement(Math.max(1, thumb / 2));
            magScrollbar.setIncrement(Math.max(1, thumb / 10));
        }
    }

    private void applyFreqScrollbar() {
        Preferences prefs = Preferences.instance();
        double nyq = nyquistHz();
        double a   = Math.log10(FREQ_FLOOR_HZ);
        double b   = Math.log10(Math.max(a + 1, nyq));
        double total = b - a;
        double visible = Math.log10(Math.max(FREQ_FLOOR_HZ, prefs.getFreqRespFreqMaxHz()))
                       - Math.log10(Math.max(FREQ_FLOOR_HZ, prefs.getFreqRespFreqMinHz()));
        int sel   = freqScrollbar.getSelection();
        int thumb = freqScrollbar.getThumb();
        double frac = (double) sel / Math.max(1, SCROLL_RANGE - thumb);
        double lo = a + frac * Math.max(0, total - visible);
        prefs.setFreqRespFreqMinHz(Math.pow(10, lo));
        prefs.setFreqRespFreqMaxHz(Math.pow(10, lo + visible));
        prefs.save();
        view.redraw();
    }

    private void applyMagScrollbar() {
        Preferences prefs = Preferences.instance();
        double total   = MAG_TOP_MAX - MAG_BOT_MIN;
        double visible = prefs.getFreqRespMagTopDb() - prefs.getFreqRespMagBotDb();
        int sel   = magScrollbar.getSelection();
        int thumb = magScrollbar.getThumb();
        double frac = (double) sel / Math.max(1, SCROLL_RANGE - thumb);
        double newTop = MAG_TOP_MAX - frac * Math.max(0, total - visible);
        prefs.setFreqRespMagTopDb(newTop);
        prefs.setFreqRespMagBotDb(newTop - visible);
        prefs.save();
        view.redraw();
    }

    private double nyquistHz() {
        int sr = Preferences.instance().current().getInputSampleRate();
        return sr > 0 ? sr / 2.0 : 24000.0;
    }

    // -------------------------------------------------------------------------
    // Bus subscribers
    // -------------------------------------------------------------------------

    private void onCalibrationChanged() {
        refreshCalibrationPathField();
        refreshTabHeader(TAB_FREQRESP_CALIBRATION);
    }

    // =========================================================================
    // Tab-header tile rendering
    // =========================================================================

    /** Lazily allocates the tile-row Color / Font palette.  Cheap to call
     *  on every paint; the colours and font are reused across redraws and
     *  disposed alongside the pane. */
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

    /** Returns the per-tile text strings for the given tab, derived from
     *  the current prefs.  Order = visual order, left to right. */
    private List<String> tileTexts(Preferences prefs, int tabIndex) {
        List<String> out = new ArrayList<>();
        if (tabIndex == TAB_FREQRESP_SETTINGS) {
            out.add(String.format(Locale.US, "%s–%s",
                    formatShortHz(prefs.getFreqRespStartHz()),
                    formatShortHz(prefs.getFreqRespStopHz())));
            out.add(String.format(Locale.US, "%.2fV", prefs.getFreqRespAmplitudeVrms()));
            out.add(formatShortCount(prefs.getFreqRespSweepPoints()) + " pts");
            out.add(String.format(Locale.US, "%.1fs", prefs.getFreqRespDurationSec()));
        } else if (tabIndex == TAB_FREQRESP_RIAA) {
            if (prefs.isFreqRespShowRiaa()) {
                out.add(prefs.isFreqRespReverseRiaa() ? "rec" : "play");
                if (prefs.isFreqRespIecAmendment()) out.add("+IEC");
                if (prefs.isFreqRespCompareMode())  out.add("comp");
            }
        } else if (tabIndex == TAB_FREQRESP_CALIBRATION) {
            if (FreqRespCalibrationStore.instance().getCurrent() != null) out.add("loaded");
        }
        return out;
    }

    /** Renders a single text tile and returns the width it occupied. */
    private int drawTabTextTile(GC gc, int x, int y, int h, int padding,
                                int corner, String text) {
        Point te = gc.textExtent(text);
        int w = te.x + 2 * padding;
        gc.setBackground(tabTileBg);
        gc.fillRoundRectangle(x, y, w, h, corner, corner);
        gc.setForeground(tabTileFg);
        gc.drawText(text, x + padding, y + (h - te.y) / 2, true);
        return w;
    }

    /** Walks the current tile list for {@code tabIndex} and paints them
     *  under the tab's label.  Also rebuilds per-tile hover regions. */
    private void drawTabTiles(GC gc, Rectangle bounds, int tabIndex) {
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) return;
        if (tabIndex < 0 || tabIndex >= NUM_CUSTOM_TABS) return;
        Preferences prefs = Preferences.instance();
        List<String> texts = tileTexts(prefs, tabIndex);
        if (texts.isEmpty()) return;
        ensureTabResources();
        Font prev = gc.getFont();
        gc.setFont(tabTileFont);
        gc.setAntialias(SWT.ON);
        gc.setTextAntialias(SWT.ON);
        final int tileH    = 18;
        final int cornerR  = 4;
        final int hPadding = 4;
        final int gap      = 3;
        boolean selected = (toolbarTabs.getSelectionIndex() == tabIndex);
        int leftInset    = selected ? 11 : 15;
        int y = bounds.y + bounds.height - tileH - 2;
        int x = bounds.x + leftInset;
        for (String t : texts) {
            int w = drawTabTextTile(gc, x, y, tileH, hPadding, cornerR, t);
            tabRegions.add(new TabRegion(new Rectangle(x, y, w, tileH), t));
            x += w + gap;
        }
        gc.setFont(prev);
    }

    /** Total pixel width the tile row needs for the given tab.  Used by
     *  the custom CTabFolderRenderer to pad the tab to fit. */
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

    /** Final tab width = max(tile-row, label) + chrome padding. */
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
            if (b.width == width) return;
            existing.dispose();
        }
        Image img = new Image(d, Math.max(1, width), 1);
        tabSpacerImages[tabIndex] = img;
        toolbarTabs.getItem(tabIndex).setImage(img);
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

    /** Custom CTabFolderRenderer: pads each custom tab to fit its tile row
     *  and re-renders the captured label on top of the default chrome.
     *  Other tabs (currently none in this pane) would render with the
     *  default behaviour. */
    private final class FreqRespTabHeaderRenderer extends CTabFolderRenderer {
        protected FreqRespTabHeaderRenderer(CTabFolder parent) { super(parent); }

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
            Color fg = selected ? toolbarTabs.getSelectionForeground()
                                : toolbarTabs.getForeground();
            if (fg == null) fg = toolbarTabs.getDisplay().getSystemColor(SWT.COLOR_WIDGET_FOREGROUND);
            gc.setForeground(fg);
            Font prev = gc.getFont();
            gc.setFont(toolbarTabs.getFont());
            gc.drawText(label, bounds.x + leftInset, bounds.y + 3, true);
            gc.setFont(prev);
        }
    }
}
