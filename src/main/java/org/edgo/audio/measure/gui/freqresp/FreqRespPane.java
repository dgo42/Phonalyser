package org.edgo.audio.measure.gui.freqresp;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabFolderRenderer;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
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
import org.edgo.audio.measure.cli.util.StereoCaptureProgress;
import org.edgo.audio.measure.cli.util.StereoFreqRespCalibration;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.Dialogs;
import org.edgo.audio.measure.gui.common.IconUtils;
import org.edgo.audio.measure.gui.common.SvgPaths;
import org.edgo.audio.measure.gui.generator.NumericStepField;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.preferences.FreqRespPreset;
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
    /** Lower bound for the magnitude axis — matches the view's wheel
     *  zoom-out limit (MAG_BOT_MIN_DB) so the scrollbar can reach the
     *  same outer edge the wheel allows. */
    private static final double MAG_BOT_MIN = -300.0;

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
    private Consumer<Void> rangeChangedListener;
    private Consumer<Void> calibrationChangedListener;

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
        rangeChangedListener       = ignored -> syncScrollbars();
        calibrationChangedListener = ignored -> onCalibrationChanged();
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
        buildPresetsTab();
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

    // -------------------------------------------------------------------------
    // Presets tab — named snapshots of every FreqResp Settings + RIAA pref
    //
    // Layout mirrors the FFT pane: an editable combo holding every saved
    // preset's name, plus Save / Load / Delete push-buttons.  The buttons
    // self-enable based on whether the name in the combo refers to an
    // existing preset and whether the current settings differ from that
    // preset's snapshot (Save is grey when the on-screen values already
    // match; Load / Delete are grey for an empty / unknown name).
    // -------------------------------------------------------------------------

    private Combo  freqRespPresetCombo;
    private Button freqRespPresetSaveBtn;
    private Button freqRespPresetLoadBtn;
    private Button freqRespPresetDeleteBtn;

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
            refreshTabHeader(TAB_FREQRESP_PRESETS);
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
            refreshTabHeader(TAB_FREQRESP_PRESETS);
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
        // Force a full pane rebuild's worth of side-effects: refresh the
        // settings-tab label + every tile header that depends on these
        // prefs, plus the view's redraw via the range-changed event.
        refreshFftSizeLabel();
        refreshTabHeader(TAB_FREQRESP_SETTINGS);
        refreshTabHeader(TAB_FREQRESP_RIAA);
        MessageBus.instance().publish(Events.FREQRESP_RANGE_CHANGED);
        view.redraw();
    }

    private boolean freqRespPresetsEqual(FreqRespPreset a, FreqRespPreset b) {
        return Double.compare(a.getStartHz(),       b.getStartHz())       == 0
            && Double.compare(a.getStopHz(),        b.getStopHz())        == 0
            && Double.compare(a.getAmplitudeVrms(), b.getAmplitudeVrms()) == 0
            && a.getSweepPoints() == b.getSweepPoints()
            && a.getFftSize()     == b.getFftSize()
            && Double.compare(a.getLeadInSec(),     b.getLeadInSec())     == 0
            && a.getDitherBits()  == b.getDitherBits()
            && a.isShowRiaa()     == b.isShowRiaa()
            && a.isReverseRiaa()  == b.isReverseRiaa()
            && a.isIecAmendment() == b.isIecAmendment()
            && a.isCompareMode()  == b.isCompareMode();
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
            freqRespPresetSaveBtn.setEnabled(!freqRespPresetsEqual(existing, captureCurrentFreqRespPreset()));
            freqRespPresetLoadBtn.setEnabled(true);
            freqRespPresetDeleteBtn.setEnabled(true);
        }
    }

    private boolean confirmFreqRespPresetOverwrite(String name) {
        return Dialogs.confirm(group.getShell(),
                I18n.t("freqResp.presets.overwrite.title"),
                I18n.t("freqResp.presets.overwrite.message").replace("{0}", name)) == SWT.YES;
    }

    private boolean confirmFreqRespPresetDelete(String name) {
        return Dialogs.confirm(group.getShell(),
                I18n.t("freqResp.presets.delete.title"),
                I18n.t("freqResp.presets.delete.message").replace("{0}", name)) == SWT.YES;
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

    /** Updated whenever the FFT-size combo or the lead-in field changes
     *  — caption is {@code "FFT size (D.Ds)"} where D.D is the derived
     *  sweep duration in seconds. */
    private Label fftSizeLabel;

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
        selectFftSizeCombo(fftSizeCombo, prefs.getFreqRespFftSize());
        fftSizeCombo.setToolTipText(I18n.t("freqResp.settings.fftSize.tooltip"));
        fftSizeCombo.setLayoutData(comboGd());
        fftSizeCombo.addListener(SWT.Selection, e -> {
            int idx = fftSizeCombo.getSelectionIndex();
            if (idx < 0 || idx >= FFT_SIZE_VALUES.length) return;
            int n = FFT_SIZE_VALUES[idx];
            prefs.setFreqRespFftSize(n);
            prefs.setFreqRespDurationSec(deriveDurationSecFromFftSize(n));
            prefs.save();
            refreshFftSizeLabel();
            refreshTabHeader(TAB_FREQRESP_SETTINGS);
        });
        // Initial sync: the YAML may carry a stale durationSec that no
        // longer matches the persisted fftSize (or vice versa).  Re-derive
        // and persist on every pane build so the analyzer + label agree.
        prefs.setFreqRespDurationSec(deriveDurationSecFromFftSize(prefs.getFreqRespFftSize()));
        refreshFftSizeLabel();

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
            // The derived sweep duration depends on lead-in (lead-in eats
            // into the same FFT window), so a lead-in change ripples
            // through to durationSec and the FFT-size label.
            prefs.setFreqRespDurationSec(deriveDurationSecFromFftSize(prefs.getFreqRespFftSize()));
            prefs.save();
            refreshFftSizeLabel();
            refreshTabHeader(TAB_FREQRESP_SETTINGS);
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
            boolean show = riaaShowBtn.getSelection();
            prefs.setFreqRespShowRiaa(show);
            prefs.save();
            refreshRiaaEnable();
            // Compare mode only takes effect when both Show RIAA and
            // Compare are on (see drawCompareTrace).  If Compare was
            // already on and the user now turns Show RIAA on, the
            // compare trace becomes active for the first time — run
            // the one-shot auto-zoom so the difference series fits the
            // view, just like toggling Compare itself does.
            if (show && prefs.isFreqRespCompareMode() && view.hasAnyResult()) {
                view.autoSetupCompare(prefs);
            }
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
            if (enable) view.autoSetupCompare(prefs);
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
    // Calibration tab — multi-row load + clear + add + remove
    //
    // Row 0 is the persistent "primary" calibration (mirrors the legacy
    // single-cal pref {@code freqRespCalibrationPath}); rows 1..N are
    // stored in {@code freqRespCalibrationPathsExtra}.  Each row carries:
    // pathField (read-only), Load (folder icon), Clear (red xmark), Add
    // (green plus).  Rows 1..N additionally have a Remove (red minus).
    // The view chains divides through every loaded row in order at draw
    // and save time, so multiple files compose into a single correction.
    // -------------------------------------------------------------------------

    /** Container Composite for the dynamic row list. */
    private Composite calRowsContainer;
    /** Scrolled wrapper around {@link #calRowsContainer} so a long list
     *  of rows scrolls vertically instead of overflowing the tab. */
    private ScrolledComposite calRowsScroll;
    /** One entry per visible row, in display order. */
    private final List<CalRow> calRows = new ArrayList<>();
    /** Re-entrancy guard so the store's change event doesn't trigger a
     *  UI rebuild for changes the pane initiated itself. */
    private boolean calMutationInFlight;

    /** Per-row widget bundle + the loaded calibration (when any). */
    private static final class CalRow {
        Composite                composite;
        Text                     pathField;
        /** "Active" toggle — same semantics as the FFT pane: the
         *  calibration is only pushed into the store when this is
         *  checked AND a file is loaded.  Checking it ahead of loading
         *  is OK; the next loadFileIntoRow re-runs syncStoreFromRows. */
        Button                   activeCheck;
        StereoFreqRespCalibration calibration;
        String                   path; // null when the row is empty
        boolean                  active;
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
        // referenced files into the store.  Suppress the change-listener's
        // rebuild while we're populating ourselves.
        Preferences prefs = Preferences.instance();
        calMutationInFlight = true;
        try {
            // Row 0 — always present.
            CalRow row0 = createRowUi();
            row0.active = prefs.isFreqRespCalibrationActive();
            String p0 = prefs.getFreqRespCalibrationPath();
            if (p0 != null && !p0.isEmpty()) loadFileIntoRow(row0, p0, false);
            updateCalRowEnable(row0);

            // Rows 1..N from the extras list (skipped silently when empty).
            List<String>  extras       = prefs.getFreqRespCalibrationPathsExtra();
            List<Boolean> extrasActive = prefs.getFreqRespCalibrationActiveExtra();
            if (extras != null) {
                for (int i = 0; i < extras.size(); i++) {
                    CalRow rN = createRowUi();
                    rN.active = (extrasActive != null && i < extrasActive.size()) ? extrasActive.get(i) : false;
                    String pn = extras.get(i);
                    if (pn != null && !pn.isEmpty()) loadFileIntoRow(rN, pn, false);
                    updateCalRowEnable(rN);
                }
            }
            // Push the loaded state into the store atomically — one event
            // fires so the view re-derives once.
            FreqRespCalibrationStore.instance().clearAll();
            for (CalRow r : calRows) {
                if (r.calibration != null && r.path != null && r.active) {
                    FreqRespCalibrationStore.instance().addEntry(r.calibration, r.path);
                }
            }
        } finally {
            calMutationInFlight = false;
        }
    }

    /** Builds and appends a fresh row to the calibration tab.  The row
     *  starts empty (no path, no calibration).  Every row has 6 grid
     *  cells (path, active, load, clear, add, remove) — for row 0 the
     *  remove button is invisible so its grid cell stays reserved,
     *  keeping the load/clear/add columns vertically aligned across
     *  all rows. */
    private CalRow createRowUi() {
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
        calRows.add(r);
        updateCalRowEnable(r);

        loadBtn.addListener(SWT.Selection, e -> userLoadInRow(r));
        clearBtn.addListener(SWT.Selection, e -> userClearRow(r));
        addBtn.addListener(SWT.Selection, e -> userAddRow());
        if (!isRow0) {
            removeBtn.addListener(SWT.Selection, e -> userRemoveRow(r));
        }
        activeCheck.addListener(SWT.Selection, e -> {
            r.active = activeCheck.getSelection();
            syncStoreFromRows();
            persistRowsToPrefs();
        });

        relayoutCalRows();
        return r;
    }

    /** Keeps the row's "Active" checkbox enabled state in sync with
     *  the file-loaded state — disabled until a calibration is loaded
     *  into the row. */
    private void updateCalRowEnable(CalRow r) {
        if (r.activeCheck == null || r.activeCheck.isDisposed()) return;
        boolean fileLoaded = r.path != null && r.calibration != null;
        r.activeCheck.setEnabled(fileLoaded);
        r.activeCheck.setSelection(r.active);
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
        FileDialog fd = new FileDialog(group.getShell(), SWT.OPEN);
        fd.setText(I18n.t("freqResp.calibration.dialog"));
        fd.setFilterExtensions(new String[]{ "*.frc", "*.csv", "*.*" });
        String memFolder = Preferences.instance().getFreqRespLoadFolder();
        if (memFolder != null) fd.setFilterPath(memFolder);
        String picked = fd.open();
        if (picked == null) return;
        if (!loadFileIntoRow(r, picked, true)) return;
        Preferences prefs = Preferences.instance();
        prefs.setFreqRespLoadFolder(new File(picked).getParent());
        syncStoreFromRows();
        persistRowsToPrefs();
    }

    private void userClearRow(CalRow r) {
        r.calibration = null;
        r.path        = null;
        r.pathField.setText(I18n.t("freqResp.calibration.path.none"));
        r.pathField.setToolTipText(null);
        // Clearing the file disables Active in the UI but we keep
        // r.active so re-loading a file re-engages the row without
        // the user having to re-tick the box — matches the FFT pane.
        updateCalRowEnable(r);
        syncStoreFromRows();
        persistRowsToPrefs();
    }

    private void userAddRow() {
        createRowUi();
        persistRowsToPrefs();
    }

    private void userRemoveRow(CalRow r) {
        if (calRows.size() <= 1) return;
        int idx = calRows.indexOf(r);
        if (idx <= 0) return; // never remove row 0
        calRows.remove(idx);
        r.composite.dispose();
        relayoutCalRows();
        syncStoreFromRows();
        persistRowsToPrefs();
    }

    /** Reads a calibration file from disk and writes the result into
     *  {@code r}.  When {@code showErrors} is true, file-load failures
     *  pop a modal dialog; otherwise they're logged silently (used at
     *  startup so a missing file doesn't block the whole pane). */
    private boolean loadFileIntoRow(CalRow r, String picked, boolean showErrors) {
        try {
            StereoFreqRespCalibration cal = FreqRespCalHelper.loadCsv(picked);
            r.calibration = cal;
            r.path        = picked;
            r.pathField.setText(picked);
            r.pathField.setToolTipText(picked);
            updateCalRowEnable(r);
            return true;
        } catch (Exception ex) {
            log.warn("FreqResp calibration load failed: {}", picked, ex);
            if (showErrors) {
                Dialogs.error(group.getShell(),
                        I18n.t("freqResp.calibration.dialog"),
                        I18n.t("freqResp.error.calibration.load").replace("{0}",
                                ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
            }
            return false;
        }
    }

    /** Pushes the current row state into the {@link FreqRespCalibrationStore}.
     *  Empty rows are skipped so the store holds only entries the view
     *  should divide by. */
    private void syncStoreFromRows() {
        FreqRespCalibrationStore store = FreqRespCalibrationStore.instance();
        calMutationInFlight = true;
        try {
            store.clearAll();
            for (CalRow r : calRows) {
                // Only push rows the user has explicitly activated; an
                // unticked row is a "loaded but parked" calibration the
                // user can re-engage with one click without re-browsing.
                if (r.calibration != null && r.path != null && r.active) {
                    store.addEntry(r.calibration, r.path);
                }
            }
        } finally {
            calMutationInFlight = false;
        }
    }

    /** Persists every row's path to {@link Preferences}.  Row 0 maps to
     *  the legacy {@code freqRespCalibrationPath} field; rows 1..N to
     *  {@code freqRespCalibrationPathsExtra}.  Empty rows are persisted
     *  as empty strings in the extras list so the row count is preserved
     *  across restarts. */
    private void persistRowsToPrefs() {
        Preferences prefs = Preferences.instance();
        CalRow row0 = calRows.isEmpty() ? null : calRows.get(0);
        prefs.setFreqRespCalibrationPath(row0 == null ? null : row0.path);
        prefs.setFreqRespCalibrationActive(row0 != null && row0.active);
        List<String>  extras       = new ArrayList<>();
        List<Boolean> extrasActive = new ArrayList<>();
        for (int i = 1; i < calRows.size(); i++) {
            CalRow r = calRows.get(i);
            extras.add(r.path == null ? "" : r.path);
            extrasActive.add(r.active);
        }
        prefs.setFreqRespCalibrationPathsExtra(extras);
        prefs.setFreqRespCalibrationActiveExtra(extrasActive);
        prefs.save();
    }

    /** Rebuilds the row UI from the store.  Used when an external source
     *  (e.g. the wizard's Apply step) replaces the calibration entries
     *  out-of-band — drops any user-added empty rows, leaving exactly
     *  one row per loaded entry (with row 0 always present). */
    private void rebuildRowsFromStore() {
        if (calRowsContainer == null || calRowsContainer.isDisposed()) return;
        List<FreqRespCalibrationStore.Entry> entries =
                FreqRespCalibrationStore.instance().getEntries();
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
        int rowCount = Math.max(1, entries.size());
        for (int i = 0; i < rowCount; i++) {
            CalRow r = createRowUi();
            if (i < entries.size()) {
                FreqRespCalibrationStore.Entry e = entries.get(i);
                r.calibration = e.getCalibration();
                r.path        = e.getPath();
                r.pathField.setText(e.getPath());
                r.pathField.setToolTipText(e.getPath());
            }
        }
        relayoutCalRows();
        persistRowsToPrefs();
    }

    /** True when the loaded subset of {@link #calRows} (skipping empty
     *  rows) is identical, in order, to {@code entries}. */
    private boolean loadedRowsMatch(List<FreqRespCalibrationStore.Entry> entries) {
        int j = 0;
        for (CalRow r : calRows) {
            if (r.calibration == null) continue;
            if (j >= entries.size()) return false;
            FreqRespCalibrationStore.Entry e = entries.get(j);
            if (r.calibration != e.getCalibration()) return false;
            if (r.path == null || !r.path.equals(e.getPath())) return false;
            j++;
        }
        return j == entries.size();
    }

    // -------------------------------------------------------------------------
    // Save-to tab — write the current measurement to a CSV
    // -------------------------------------------------------------------------

    /** Path field shared between the Save-to and Load-from tabs.  Kept
     *  as a field so the file-dialog handler can update the displayed
     *  text from anywhere (including dropping a future drag-and-drop
     *  source onto the same widget). */
    private Text saveToPathField;
    private Text loadFromPathField;

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
        if (savedPath != null && !savedPath.isEmpty()) saveToPathField.setToolTipText(savedPath);
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
            Dialogs.info(group.getShell(),
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
        FileDialog fd = new FileDialog(group.getShell(), SWT.SAVE);
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
        refreshTabHeader(TAB_FREQRESP_SAVE);
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
        if (savedPath != null && !savedPath.isEmpty()) loadFromPathField.setToolTipText(savedPath);
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
        FileDialog fd = new FileDialog(group.getShell(), SWT.OPEN);
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
        refreshTabHeader(TAB_FREQRESP_LOAD);
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
        Preferences prefs = Preferences.instance();
        int sr = Math.max(1, prefs.current().getInputSampleRate());
        double totalSec = prefs.getFreqRespLeadInSec()
                        + prefs.getFreqRespDurationSec()
                        + 0.5;  // matches analyzer's tail
        openBusyShell(totalSec);
        // Marshal each capture block's RMS to the SWT UI thread and
        // forward it to the live meter.  The callback fires on the audio
        // capture thread so we MUST asyncExec — touching the meter
        // directly here would deadlock or crash.
        Display d = group.getDisplay();
        StereoCaptureProgress progress = (totalSamples, rmsLin) -> {
            double tSec = totalSamples / (double) sr;
            d.asyncExec(() -> {
                if (busyMeter != null && !busyMeter.isDisposed()) {
                    busyMeter.appendSample(tSec, rmsLin);
                }
            });
        };
        worker.start(progress);
    }

    /** Shell shown for the duration of a measurement / deconvolution.
     *  Created on entry, closed by the worker's completion or error path. */
    private Shell busyShell;
    /** Live "level vs. time" meter inside {@link #busyShell}, fed by the
     *  capture-progress callback that the worker passes through to the
     *  analyzer.  Null when no busy shell is open. */
    private FreqRespLiveMeter busyMeter;

    private void openBusyShell(double totalDurationSec) {
        if (busyShell != null && !busyShell.isDisposed()) return;
        Shell parent = group.getShell();
        Shell s = new Shell(parent, SWT.TITLE | SWT.BORDER
                | SWT.APPLICATION_MODAL);
        s.setText(I18n.t("freqResp.busy.title"));
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 12; gl.marginHeight = 10;
        gl.verticalSpacing = 8;
        s.setLayout(gl);
        Label l = new Label(s, SWT.CENTER);
        l.setText(I18n.t("freqResp.busy.message"));
        l.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        busyMeter = new FreqRespLiveMeter(s, totalDurationSec);
        GridData mg = new GridData(SWT.FILL, SWT.CENTER, true, false);
        mg.widthHint  = 520;
        mg.heightHint = 100;
        busyMeter.setLayoutData(mg);

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
        busyMeter = null;
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

    /** Maximal analyzed frequency for the FreqResp view — Nyquist
     *  (sampleRate/2) scaled by {@code freqRespNyquistFraction}.  Caps
     *  the scrollbar's outer travel at the same value the view uses for
     *  zoom-out. */
    private double nyquistHz() {
        int sr = Preferences.instance().current().getInputSampleRate();
        double base = sr > 0 ? sr / 2.0 : 24000.0;
        double frac = Preferences.instance().getFreqRespNyquistFraction();
        if (!Double.isFinite(frac) || frac <= 0.0) frac = 1.0;
        return base * frac;
    }

    // -------------------------------------------------------------------------
    // Bus subscribers
    // -------------------------------------------------------------------------

    private void onCalibrationChanged() {
        // Skip the rebuild when this pane initiated the store mutation
        // itself — calRows already matches what we just wrote.  Other
        // sources (e.g. wizard Apply, wizard Cancel-restore) take the
        // rebuildRowsFromStore path so the UI catches up.
        if (!calMutationInFlight) {
            rebuildRowsFromStore();
        }
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
            // FFT size tile (replaces the old sweep-duration tile, which
            // is now derived from FFT size and shown in the settings-tab
            // label instead).
            out.add(formatFftSize(prefs.getFreqRespFftSize()));
        } else if (tabIndex == TAB_FREQRESP_RIAA) {
            if (prefs.isFreqRespShowRiaa()) {
                out.add(prefs.isFreqRespReverseRiaa() ? "rec" : "play");
                if (prefs.isFreqRespIecAmendment()) out.add("+IEC");
                if (prefs.isFreqRespCompareMode())  out.add("comp");
            }
        } else if (tabIndex == TAB_FREQRESP_CALIBRATION) {
            int n = FreqRespCalibrationStore.instance().getEntries().size();
            if (n == 1)      out.add("loaded");
            else if (n  > 1) out.add(n + " loaded");
        } else if (tabIndex == TAB_FREQRESP_PRESETS) {
            // Show the number of saved presets when there are any —
            // gives the user a hint that there's something to load.
            int n = prefs.getFreqRespPresets().size();
            if (n > 0) out.add(n + " saved");
        } else if (tabIndex == TAB_FREQRESP_UTILITY) {
            // No persistent state on the Utility tab — the screenshot,
            // DAC-cal and ADC-cal actions don't leave a per-run footprint
            // visible in the tab header.  Branch left empty intentionally
            // so the tile area stays clean; the constant reference here
            // also keeps the switch exhaustive.
        }
        // TAB_FREQRESP_SAVE and TAB_FREQRESP_LOAD intentionally render no
        // tiles — per user spec the file path lives in the tab body's
        // path field, not in the header.  The constants are still used
        // by the refreshTabHeader callers that fire after a successful
        // save / load (cheap no-op when the tile list is empty).
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
            // Y-position: when this tab has tiles painted underneath the
            // label, sit just under the tab's top edge so there's room
            // for the tile row at the bottom.  When there are NO tiles,
            // centre the label vertically inside the tab so it doesn't
            // float alone at the top of a header sized for tile-bearing
            // siblings (CTabFolder enforces a uniform row height across
            // all tabs).
            ensureTabResources();
            int textY;
            Point te = gc.textExtent(label);
            boolean hasTiles = !tileTexts(Preferences.instance(), part).isEmpty();
            if (hasTiles) {
                textY = bounds.y + 3;
            } else {
                textY = bounds.y + Math.max(3, (bounds.height - te.y) / 2);
            }
            gc.drawText(label, bounds.x + leftInset, textY, true);
            gc.setFont(prev);
        }
    }
}
