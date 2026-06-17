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

import java.util.function.Consumer;

import org.eclipse.swt.SWT;
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
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.edgo.audio.measure.cli.util.StereoCaptureProgress;
import org.edgo.audio.measure.common.FreqRespCorrectionStore;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.AbstractPane;
import org.edgo.audio.measure.gui.common.AbstractTabControl;
import org.edgo.audio.measure.gui.common.Dialogs;
import org.edgo.audio.measure.gui.common.IconUtils;
import org.edgo.audio.measure.gui.common.ScreenshotDialog;
import org.edgo.audio.measure.gui.common.SvgPaths;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.widgets.FlatScrollbar;
import org.edgo.audio.measure.gui.widgets.PaneTitle;
import org.edgo.audio.measure.preferences.Preferences;

import lombok.Getter;

/**
 * Host composite for the Frequency Response feature.  Mirrors {@code FftPane}'s
 * layout (PaneTitle + plot row + freq-scrollbar row + toolbar row) so the
 * analytical panes feel visually consistent.
 *
 * <p>The pane keeps the chart ({@link FreqRespView}), the frequency /
 * magnitude scrollbars, and the Wizard + Play action buttons that drive the
 * measurement worker.  Every tab — Settings / RIAA &amp; IEC / Presets /
 * Utility / Calibration / Save-to / Load-from — lives in the self-contained
 * {@link FreqRespTabControl}; the pane only calls
 * {@link FreqRespTabControl#refreshRiaaEnable()} when a fresh measurement
 * lands.  Scrollbars round-trip through {@link Preferences} +
 * {@link Events#FREQRESP_RANGE_CHANGED}.
 */
public final class FreqRespPane extends AbstractPane {

    private static final int SCROLL_RANGE = 1_000_000;

    /** Lower bound for the log-frequency scrollbar.  The view clamps
     *  freqRespFreqMinHz to ≥ 1 Hz so the log mapping is well-defined. */
    private static final double FREQ_FLOOR_HZ = 1.0;
    /** Lower bound for the magnitude axis — matches the view's wheel
     *  zoom-out limit (MAG_BOT_MIN_DB) so the scrollbar can reach the
     *  same outer edge the wheel allows. */
    private static final double MAG_BOT_MIN = -300.0;
    /** Fixed height of the toolbar row while the tab body is expanded;
     *  released to strip-only height when the tabs are collapsed. */
    private static final int TOOLBAR_ROW_HEIGHT = 200;

    @Getter private FreqRespView view;

    private FlatScrollbar freqScrollbar;
    private FlatScrollbar magScrollbar;
    /** The self-contained tile-tab folder hosting every settings / presets /
     *  calibration / save-load tab; see {@link FreqRespTabControl}. */
    private FreqRespTabControl tabControl;
    private Button       wizardButton;
    private Button       playButton;

    /** Controller owning the measurement worker's lifecycle and the
     *  sweep-timing rules; the pane only issues commands and renders the
     *  bus events back. */
    private final FreqRespController controller;

    /** Bus subscribers kept as fields so dispose can unsubscribe the
     *  same instances (method references compare by identity). */
    private Consumer<Void> rangeChangedListener;
    private Consumer<Void> measurementStoppedListener;
    private Consumer<String> measurementFailedListener;

    /** Calibration-correction store the pane owns (IoC) and constructor-injects
     *  into the view and tab control it builds, so both see the same entries.
     *  Created before the builds run — it has no dependency on either. */
    private final FreqRespCorrectionStore correctionStore;

    public FreqRespPane(Composite parent) {
        super(parent);
        Display d = parent.getDisplay();
        IconUtils icons = IconUtils.instance();
        Image wandIcon = icons.renderAtHeightColored(d, SvgPaths.WAND, ACTION_ICON_SIZE);
        // Play button uses the same green LED that the generator pane shows
        // so the visual language is consistent across measurement features.
        Image playIcon = icons.renderAtHeight(d, SvgPaths.PLAY, ACTION_ICON_SIZE,
                new RGB(0x00, 0xAA, 0x00));

        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth  = 0; gl.marginHeight = 0; gl.verticalSpacing = 2;
        group.setLayout(gl);

        PaneTitle paneTitle = new PaneTitle(group, Events.PANE_ID_FREQRESP,
                I18n.t("freqResp.title.expanded"),
                I18n.t("freqResp.title.collapsed"),
                I18n.t("freqResp.pane.toggle.tooltip"));
        paneTitle.setStaticMode(true);

        // Build order matters: the view (created in buildPlotRow) receives the
        // store first; the tab control's constructor then pushes the prefs rows
        // into it, firing FREQRESP_CALIBRATION_CHANGED — which the view handles
        // by re-deriving from the store it already holds.
        correctionStore = new FreqRespCorrectionStore("FreqResp",
                () -> MessageBus.instance().publish(Events.FREQRESP_CALIBRATION_CHANGED));
        controller = new FreqRespController(d::asyncExec);
        buildPlotRow();
        buildFreqScrollbarRow();
        buildToolbarRow(wandIcon, playIcon);

        // Bus subscriptions.  Range-changed re-aligns the scrollbars after the
        // view's wheel-driven pan / zoom (and after a preset load, which the
        // tab control publishes).  STOPPED (always published, on the UI
        // thread) unlocks the pane + closes the busy shell; FAILED carries
        // the error dialog text.  The calibration-changed subscription lives
        // inside FreqRespTabControl (it owns the calibration tab).
        rangeChangedListener = ignored -> syncScrollbars();
        measurementStoppedListener = ignored -> {
            if (group.isDisposed()) return;
            // Unlock first (re-enables the group), then refresh the RIAA
            // enable cascade so Compare picks up the freshly-available
            // measurement (setLocked alone restores each child's prior
            // flag, which kept Compare disabled).
            setLocked(false);
            closeBusyShell();
            tabControl.refreshRiaaEnable();
        };
        measurementFailedListener = this::showError;
        MessageBus bus = MessageBus.instance();
        bus.subscribe(Events.FREQRESP_RANGE_CHANGED, rangeChangedListener);
        bus.subscribe(Events.FREQRESP_MEASUREMENT_STOPPED, measurementStoppedListener);
        bus.subscribe(Events.FREQRESP_MEASUREMENT_FAILED, measurementFailedListener);
        group.addDisposeListener(e -> {
            bus.unsubscribe(Events.FREQRESP_RANGE_CHANGED, rangeChangedListener);
            bus.unsubscribe(Events.FREQRESP_MEASUREMENT_STOPPED, measurementStoppedListener);
            bus.unsubscribe(Events.FREQRESP_MEASUREMENT_FAILED, measurementFailedListener);
            controller.shutdown();
        });

        // Initial scrollbar sync with the persisted pan window.
        syncScrollbars();
    }

    /** Registers this pane's settings tabs in the component registry under
     *  {@code prefix} so automation can select + screenshot each by path —
     *  delegates to {@link FreqRespTabControl#registerTabs}. */
    public void registerTabs(String prefix) {
        if (tabControl != null) tabControl.registerTabs(prefix);
    }

    // -------------------------------------------------------------------------
    // Layout
    // -------------------------------------------------------------------------

    private void buildPlotRow() {
        Composite plotRow = new Composite(group, SWT.NONE);
        plotRow.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        plotRow.setLayout(new FormLayout());

        view = new FreqRespView(plotRow, correctionStore);
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
        // Toolbar row: the FreqRespTabControl (left, grabs space) + Wizard +
        // Play anchored on the right.  Mirrors the scope / FFT toolbar layout
        // so the action buttons always stay accessible.  heightHint set
        // explicitly so the Settings tab's 4-row body has room — without it
        // the tab folder gets vertically squeezed and the bottom row (dither /
        // Nyquist combos) clips out of view.
        Composite toolbarRow = new Composite(group, SWT.NONE);
        GridData trGd = new GridData(SWT.FILL, SWT.BEGINNING, true, false);
        trGd.heightHint = TOOLBAR_ROW_HEIGHT;
        toolbarRow.setLayoutData(trGd);
        GridLayout trGl = new GridLayout(3, false);
        trGl.marginWidth = 0; trGl.marginHeight = 0; trGl.horizontalSpacing = 4;
        toolbarRow.setLayout(trGl);

        tabControl = new FreqRespTabControl(toolbarRow, view, correctionStore);
        tabControl.setScreenshotPane(this);
        tabControl.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        // Strip double-click / Enter collapses the tab body.  The toolbar row
        // is pinned to a fixed height while expanded, so the collapse re-flow
        // releases that pin (restoring it on expand) and re-lays the pane so
        // the plot above reclaims the freed space.
        tabControl.setCollapseRelayout(() -> {
            if (toolbarRow.isDisposed() || group.isDisposed()) return;
            GridData rowGd = (GridData) toolbarRow.getLayoutData();
            rowGd.heightHint = tabControl.isTabsCollapsed() ? SWT.DEFAULT : TOOLBAR_ROW_HEIGHT;
            group.layout(true, true);
        });

        // Wizard button (left of Play) — opens the 3-page calibration wizard.
        wizardButton = createActionButton(toolbarRow, SWT.PUSH);
        if (wandIcon != null) wizardButton.setImage(wandIcon);
        wizardButton.setToolTipText(I18n.t("freqResp.button.wizard.tooltip"));
        wizardButton.addListener(SWT.Selection, e ->
                new FreqRespWizardDialog(group.getShell(), view).open());

        // Play button — kicks off a sweep measurement.  Click is ignored
        // while a measurement is already in flight (the lock helper has
        // disabled it anyway but the listener guards defensively).
        playButton = createActionButton(toolbarRow, SWT.PUSH);
        if (playIcon != null) playButton.setImage(playIcon);
        playButton.setToolTipText(I18n.t("freqResp.button.play.start"));
        playButton.addListener(SWT.Selection, e -> onPlayClicked());
    }

    // -------------------------------------------------------------------------
    // Play / measurement orchestration
    // -------------------------------------------------------------------------

    /** Handler for the big Play button — locks the pane, opens the busy
     *  shell, and hands the sweep to the controller.  Unlock + close ride
     *  the {@link Events#FREQRESP_MEASUREMENT_STOPPED} subscription, the
     *  error dialog rides {@link Events#FREQRESP_MEASUREMENT_FAILED}. */
    private void onPlayClicked() {
        if (controller.isMeasurementRunning()) return;
        // A fresh sweep replaces any loaded file — drop the "Loaded: …" banner.
        view.setSourceFilePath(null);
        setLocked(true);
        openBusyShell(controller.expectedMeasurementSeconds());
        // Marshal each capture block's RMS to the SWT UI thread and
        // forward it to the live meter.  The callback fires on the audio
        // capture thread so we MUST asyncExec — touching the meter
        // directly here would deadlock or crash.
        int sr = Math.max(1, Preferences.instance().current().getInputSampleRate());
        Display d = group.getDisplay();
        StereoCaptureProgress progress = (totalSamples, rmsLin) -> {
            double tSec = totalSamples / (double) sr;
            d.asyncExec(() -> {
                if (busyMeter != null && !busyMeter.isDisposed()) {
                    busyMeter.appendSample(tSec, rmsLin);
                }
            });
        };
        controller.startMeasurement(progress);
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

        // Sweep geometry feeds the meter's time → instantaneous-frequency
        // mapping, which scales the trace smoothing with the period.
        Preferences prefs = Preferences.instance();
        busyMeter = new FreqRespLiveMeter(s, totalDurationSec,
                prefs.getFreqRespLeadInSec(), prefs.getFreqRespDurationSec(),
                prefs.getFreqRespStartHz(), prefs.getFreqRespStopHz());
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

        // Magnitude scrollbar — linear dB range capped at view.magCeilingDb() /
        // MAG_BOT_MIN.  Thumb size shows the visible slice's share.
        double magTopMax = view.magCeilingDb();
        double visibleM = prefs.getFreqRespMagTopDb() - prefs.getFreqRespMagBotDb();
        double totalM   = magTopMax - MAG_BOT_MIN;
        if (totalM > 0 && visibleM > 0) {
            int thumb = (int) Math.max(1, Math.min(SCROLL_RANGE,
                    SCROLL_RANGE * (visibleM / totalM)));
            double pos = (totalM - visibleM) > 0
                    ? (magTopMax - prefs.getFreqRespMagTopDb()) / (totalM - visibleM)
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
        view.redraw();
    }

    private void applyMagScrollbar() {
        Preferences prefs = Preferences.instance();
        double magTopMax = view.magCeilingDb();
        double total   = magTopMax - MAG_BOT_MIN;
        double visible = prefs.getFreqRespMagTopDb() - prefs.getFreqRespMagBotDb();
        int sel   = magScrollbar.getSelection();
        int thumb = magScrollbar.getThumb();
        double frac = (double) sel / Math.max(1, SCROLL_RANGE - thumb);
        double newTop = magTopMax - frac * Math.max(0, total - visible);
        prefs.setFreqRespMagTopDb(newTop);
        prefs.setFreqRespMagBotDb(newTop - visible);
        view.redraw();
    }

    /** Maximal analyzed frequency for the FreqResp view — Nyquist
     *  (sampleRate/2) scaled by {@code freqRespNyquistFraction}.  Caps
     *  the scrollbar's outer travel at the same value the view uses for
     *  zoom-out. */
    private double nyquistHz() {
        Preferences prefs = Preferences.instance();
        int sr = prefs.current().getInputSampleRate();
        double base = sr > 0 ? sr / 2.0 : 24000.0;
        double frac = prefs.getFreqRespNyquistFraction();
        if (!Double.isFinite(frac) || frac <= 0.0) frac = 1.0;
        return base * frac;
    }

    @Override
    protected AbstractTabControl tabStrip() {
        return tabControl;
    }

    /** Builds the offscreen FreqResp clone for {@link #renderOffscreen}: fresh
     *  pane with the live view's displayed snapshot copied in.  The tab body is
     *  collapsed by {@link #renderOffscreen} after the clone's first expanded
     *  layout, not here. */
    @Override
    protected AbstractPane createSnapshotClone(Composite parent) {
        FreqRespPane clone = new FreqRespPane(parent);
        clone.getView().copySnapshotFrom(view);
        return clone;
    }

    // FreqResp shares the common screenshot-size preference (persisted like the
    // other panes) but keeps its own save folder.

    /** FreqResp's "Loaded: …" banner occupies the top-right, so the comment
     *  caption sits a line lower than the default to clear it. */
    @Override
    protected int screenshotCommentTopPx() {
        return 55;
    }

    @Override
    protected String screenshotFolder() {
        return Preferences.instance().getFreqRespSaveFolder();
    }

    @Override
    protected ScreenshotDialog.FolderCommit screenshotFolderCommit() {
        return folder -> {
            Preferences p = Preferences.instance();
            p.setFreqRespSaveFolder(folder);
            p.save();
        };
    }
}
