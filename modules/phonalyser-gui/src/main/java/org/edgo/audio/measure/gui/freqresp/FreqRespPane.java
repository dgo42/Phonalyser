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

import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
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
import org.edgo.audio.measure.sound.StereoCaptureProgress;
import org.edgo.audio.measure.gui.bind.Bindings;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.common.AbstractPane;
import org.edgo.audio.measure.gui.common.AbstractTabControl;
import org.edgo.audio.measure.gui.common.CorrectionStore;
import org.edgo.audio.measure.gui.common.GuiUtil;
import org.edgo.audio.measure.gui.common.Dialogs;
import org.edgo.audio.measure.gui.common.Icon;
import org.edgo.audio.measure.gui.common.IconUtils;
import org.edgo.audio.measure.gui.common.ScreenshotDialog;
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
 * measurement worker.  Every tab - Settings / RIAA &amp; IEC / Presets /
 * Utility / Calibration / Save-to / Load-from - lives in the self-contained
 * {@link FreqRespTabControl}; the pane only calls
 * {@link FreqRespTabControl#refreshRiaaEnable()} when a fresh measurement
 * lands.  Scrollbars write the pan window into {@link Preferences} and
 * re-sync from its range properties, the Nyquist fraction and the audio
 * format; the view re-syncs them after every curve-data change.
 */
public final class FreqRespPane extends AbstractPane {

    private static final int SCROLL_RANGE = 1_000_000;

    // The log-frequency scrollbar's lower bound and the magnitude
    // scrollbar's outer edge come from FreqRespConstants - the same limits
    // the view's clamps and wheel zoom-out use, so scrollbar and wheel
    // reach the same extremes.
    /** Fixed height of the toolbar row while the tab body is expanded;
     *  released to strip-only height when the tabs are collapsed.  232
     *  (was 200): the Filters tab is the tallest and its bottom field row
     *  clipped out of view at 200; 232 is the measured fit - every tab
     *  shares this one height. */
    private static final int TOOLBAR_ROW_HEIGHT = 232;

    @Getter private FreqRespView view;

    private FlatScrollbar freqScrollbar;
    private FlatScrollbar magScrollbar;
    /** The self-contained tile-tab folder hosting every settings / presets /
     *  calibration / save-load tab; see {@link FreqRespTabControl}. */
    private FreqRespTabControl tabControl;
    /** The row holding the tab control and the Wizard / Play buttons - the
     *  part of the pane a running sweep locks. */
    private Composite    toolbarRow;
    private Button       wizardButton;
    private Button       playButton;

    /** Controller owning the measurement worker's lifecycle and the
     *  sweep-timing rules.  Created first, with this pane, and handed to the
     *  view at construction; the pane issues the commands and the controller
     *  reports each measurement back through {@link #onMeasurementResult},
     *  {@link #onMeasurementFailed} and {@link #onMeasurementStopped}. */
    private final FreqRespController controller;

    /** The calibration wizard while its modal loop runs, null otherwise - the
     *  measurement reports of a sweep it started reach it through this
     *  field. */
    private FreqRespWizardDialog wizard;

    /** Set when the finished sweep's raw capture touched the converter rail,
     *  cleared when the warning has been shown.  The flag exists because the
     *  result arrives while the modal progress dialog is still up - a dialog opened
     *  there would sit behind it - so the warning waits for the stopped report
     *  that closes the shell.  Written and read on the UI thread: the
     *  controller delivers every measurement report there. */
    private boolean clippedWarningPending;

    /** True from {@link #play} until that run's {@link #onMeasurementStopped}
     *  - the state behind the toolbar row's disabled look (setLocked writes
     *  both). */
    private boolean locked;

    /** Calibration-correction store the pane owns (IoC) and constructor-injects
     *  into the view and tab control it builds, so both see the same entries.
     *  Created before the builds run - it has no dependency on either. */
    private final CorrectionStore correctionStore;

    public FreqRespPane(Composite parent) {
        super(parent);
        Display d = parent.getDisplay();
        Image wandIcon = IconUtils.icon(d, Icon.WAND);
        // Play button uses the same green LED that the generator pane shows
        // so the visual language is consistent across measurement features.
        Image playIcon = IconUtils.icon(d, Icon.PLAY_DARK_BIG);

        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth  = 0; gl.marginHeight = 0; gl.verticalSpacing = 2;
        group.setLayout(gl);

        PaneTitle paneTitle = new PaneTitle(group, Events.PANE_ID_FREQRESP,
                "freqResp.title.expanded",
                "freqResp.title.collapsed",
                "freqResp.pane.toggle.tooltip");
        paneTitle.setStaticMode(true);

        // Build order matters: the store and the controller come first - the
        // view receives both at construction.  The tab control's constructor
        // then pushes the prefs rows into the store and re-derives the view it
        // already holds.  The store publishes no event: the tab control, its
        // only writer, re-derives the view directly.
        correctionStore = new CorrectionStore("FreqResp");
        controller = new FreqRespController(this);
        buildPlotRow();
        buildFreqScrollbarRow();
        buildToolbarRow(wandIcon, playIcon);

        // Scrollbar re-sync.  The pan window lives in the four range
        // properties (the view's wheel pan / zoom and auto-setup write them,
        // and so do these scrollbars).  The frequency extent follows the
        // Nyquist fraction and the input sample rate - a plain field no
        // Property observes, announced by AUDIO_FORMAT_CHANGED.  The magnitude
        // ceiling follows the view's displayed curves, L / R channel and
        // compare state; the view calls syncScrollbars after every change of
        // them.
        Preferences prefs = Preferences.instance();
        Consumer<Double> resync = ignored -> syncScrollbars();
        Bindings.onChange(group, prefs.freqRespFreqMinHzProperty(), resync);
        Bindings.onChange(group, prefs.freqRespFreqMaxHzProperty(), resync);
        Bindings.onChange(group, prefs.freqRespMagTopDbProperty(), resync);
        Bindings.onChange(group, prefs.freqRespMagBotDbProperty(), resync);
        Bindings.onChange(group, prefs.freqRespNyquistFractionProperty(), resync);
        Bindings.onEvent(group, Events.AUDIO_FORMAT_CHANGED, (Void ignored) -> syncScrollbars());

        group.addDisposeListener(e -> {
            // The progress window belongs to the main shell, not to this
            // pane: a content rebuild mid-sweep would leave it open.
            closeProgressDialog();
            controller.shutdown();
        });

        // Initial scrollbar sync with the persisted pan window.
        syncScrollbars();
    }

    /** Registers this pane's settings tabs in the component registry under
     *  {@code prefix} so automation can select + screenshot each by path -
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

        view = new FreqRespView(plotRow, correctionStore, this, controller);
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
        // right edge stops 18 px short of the group right - aligning with
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
        // explicitly so the Settings tab's 4-row body has room - without it
        // the tab folder gets vertically squeezed and the bottom row (dither /
        // Nyquist combos) clips out of view.
        toolbarRow = new Composite(group, SWT.NONE);
        GridData trGd = new GridData(SWT.FILL, SWT.BEGINNING, true, false);
        trGd.heightHint = TOOLBAR_ROW_HEIGHT;
        toolbarRow.setLayoutData(trGd);
        GridLayout trGl = new GridLayout(3, false);
        trGl.marginWidth = 0; trGl.marginHeight = 0; trGl.horizontalSpacing = 4;
        toolbarRow.setLayout(trGl);

        // Strip double-click / Enter collapses the tab body.  The toolbar row
        // is pinned to a fixed height while expanded, so the collapse re-flow
        // (onTabCollapse, reached through the control's owner) releases that
        // pin (restoring it on expand) and re-lays the pane so the plot above
        // reclaims the freed space.
        tabControl = new FreqRespTabControl(toolbarRow, view, correctionStore, this);
        tabControl.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        // Wizard button (left of Play) - opens the two-step calibration wizard,
        // which measures through this pane's play() (lock, progress window
        // with Cancel) and saves through saveMeasurement(), as the Save-to tab
        // does.  open() runs the wizard's modal loop; the field holds the
        // wizard for exactly that long.
        wizardButton = createActionButton(toolbarRow, SWT.PUSH);
        if (wandIcon != null) wizardButton.setImage(wandIcon);
        Bindings.text(wizardButton, "freqResp.button.wizard.tooltip", wizardButton::setToolTipText);
        wizardButton.addListener(SWT.Selection, e -> {
            wizard = new FreqRespWizardDialog(group.getShell(), this);
            wizard.open();
            wizard = null;
        });

        // Play button - kicks off a sweep measurement.  Click is ignored
        // while a measurement is already in flight (the lock helper has
        // disabled it anyway but play() guards defensively).
        playButton = createActionButton(toolbarRow, SWT.PUSH);
        if (playIcon != null) playButton.setImage(playIcon);
        Bindings.text(playButton, "freqResp.button.play.start", playButton::setToolTipText);
        playButton.addListener(SWT.Selection, e -> play());
    }

    // -------------------------------------------------------------------------
    // Play / measurement orchestration
    // -------------------------------------------------------------------------

    /** The Play of the big Play button and of the calibration wizard - locks
     *  the pane, opens the progress dialog, and hands the sweep to the
     *  controller.  Unlock + close follow in {@link #onMeasurementStopped},
     *  the error dialog in {@link #onMeasurementFailed}.
     *  @return false when a run is already in flight or the pane is still
     *          locked by the previous run (nothing locked, no window opened),
     *          true when this call started one */
    boolean play() {
        // The lock is released only by the previous run's stopped report.
        // After a second close of the progress window while a cancel is
        // pending, that report can still wait in the display queue when the
        // worker no longer counts as running; a run started then would have
        // its lock, progress window and meter torn down by the old report.
        if (locked || controller.isMeasurementRunning()) return false;
        // A fresh sweep replaces any loaded file - drop the "Loaded: ..." banner.
        view.setSourceFilePath(null);
        setLocked(true);
        openProgressDialog(controller.expectedMeasurementSeconds());
        // Marshal each capture block's RMS to the SWT UI thread and
        // forward it to the live meter.  The callback fires on the audio
        // capture thread so we MUST asyncExec - touching the meter
        // directly here would deadlock or crash.  The meter shows the channel
        // the view has selected (its L/R radio), read per block so a switch
        // during the sweep takes effect at once.
        Preferences prefs = Preferences.instance();
        int sr = Math.max(1, prefs.current().getInputSampleRate());
        StereoCaptureProgress progress = (totalSamples, rmsLeft, rmsRight) -> {
            double rmsLin = prefs.isFreqRespLeftVisible() ? rmsLeft : rmsRight;
            double tSec = totalSamples / (double) sr;
            GuiUtil.marshal(() -> {
                if (progressMeter != null && !progressMeter.isDisposed()) {
                    progressMeter.appendSample(tSec, rmsLin);
                }
            });
        };
        controller.startMeasurement(progress);
        return true;
    }

    /** The one save of the shown curves, for the Save-to tab and the
     *  calibration wizard alike.
     *  @return the written path, null when nothing was written */
    String saveMeasurement() {
        return view.save();
    }

    /** Shell shown for the duration of a measurement / deconvolution.
     *  Created on entry; closed by {@link #onMeasurementStopped} and by the
     *  pane's dispose (the shell belongs to the main window, not to the
     *  pane), or by the operator's second close while a cancel is pending -
     *  the field then keeps the disposed shell until
     *  {@link #onMeasurementStopped}. */
    private Shell progressDialog;
    /** Live "level vs. time" meter inside {@link #progressDialog}, fed by the
     *  capture-progress callback that the worker passes through to the
     *  analyzer.  Null when no progress dialog is open. */
    private FreqRespLiveMeter progressMeter;

    private void openProgressDialog(double totalDurationSec) {
        if (progressDialog != null && !progressDialog.isDisposed()) return;
        Shell parent = group.getShell();
        Shell s = new Shell(parent, SWT.TITLE | SWT.BORDER
                | SWT.APPLICATION_MODAL);
        s.setText(I18n.t("freqResp.progress.title"));
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 12; gl.marginHeight = 10;
        gl.verticalSpacing = 8;
        s.setLayout(gl);
        Label l = new Label(s, SWT.CENTER);
        l.setText(I18n.t("freqResp.progress.message"));
        l.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Sweep geometry feeds the meter's time -> instantaneous-frequency
        // mapping, which scales the trace smoothing with the period.  The
        // band is the SWEPT one, derived by the rule the worker plays the
        // chirp by, so the estimate follows what is emitted.
        Preferences prefs = Preferences.instance();
        double[] swept = FreqRespConstants.sweptBandHz(prefs.getFreqRespStartHz(),
                prefs.getFreqRespStopHz(), prefs.current().getInputSampleRate(),
                prefs.current().getOutputSampleRate());
        progressMeter = new FreqRespLiveMeter(s, totalDurationSec,
                controller.sweepLeadInSec(), prefs.getFreqRespDurationSec(),
                swept[0], swept[1]);
        GridData mg = new GridData(SWT.FILL, SWT.CENTER, true, false);
        mg.widthHint  = 520;
        mg.heightHint = 100;
        progressMeter.setLayoutData(mg);

        // Cancel is cooperative - the worker's cancel flag ends the sweep, and
        // the normal stopped report closes this shell and unlocks the pane,
        // exactly like a completed run.
        Button cancel = new Button(s, SWT.PUSH);
        cancel.setText(I18n.t("common.cancel"));
        cancel.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
        Runnable requestCancel = () -> {
            if (!cancel.isEnabled()) return;
            cancel.setEnabled(false);   // one shot; the teardown takes a moment
            controller.cancelMeasurement();
        };
        cancel.addListener(SWT.Selection, e -> requestCancel.run());
        // ESC and the window-manager close arrive as SWT.Close.  Letting the
        // shell vanish would leave the sweep playing behind a locked, inert
        // pane - so a user close IS the cancel: vetoed here, and the normal
        // stopped report closes the shell exactly like a completed run.  Once a
        // cancel is already pending, a second close is let through as an
        // escape hatch in case the teardown hangs.
        s.addListener(SWT.Close, e -> {
            if (cancel.isEnabled()) {
                e.doit = false;
                requestCancel.run();
            }
        });

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
        progressDialog = s;
    }

    private void closeProgressDialog() {
        // dispose(), not close(): close() raises SWT.Close, which is the
        // user-close channel that turns into a cancel request above.
        if (progressDialog != null && !progressDialog.isDisposed()) progressDialog.dispose();
        progressDialog = null;
        progressMeter = null;
    }

    // The three measurement reports, called by the controller on the UI
    // thread, result and failed before stopped.  Each is a marshalled call
    // that can arrive after the pane is disposed, hence the guard first.

    /** The finished deconvolution of both channels: the view shows it, the
     *  clipping flag is set for the stopped report, and an open wizard
     *  records it. */
    void onMeasurementResult(StereoFreqRespResult stereo) {
        if (group.isDisposed()) return;
        view.showMeasurement(stereo);
        // The sweep that just finished is the one whose raw capture may have
        // hit the rail.
        clippedWarningPending = stereo.clipped();
        if (wizard != null) wizard.onMeasurementResult(stereo);
    }

    /** The run ended in an error; {@code message} words it. */
    void onMeasurementFailed(String message) {
        if (group.isDisposed()) return;
        showError(message);
    }

    /** The run ended - completed, cancelled or failed. */
    void onMeasurementStopped() {
        if (group.isDisposed()) return;
        // Unlock first (re-enables the toolbar row), then refresh the RIAA
        // enable cascade so Compare picks up the freshly-available
        // measurement (setLocked alone restores each child's prior
        // flag, which kept Compare disabled).
        setLocked(false);
        closeProgressDialog();
        tabControl.refreshRiaaEnable();
        warnIfClipped();
        if (wizard != null) wizard.onMeasurementStopped();
    }

    private void showError(String message) {
        if (group.isDisposed()) return;
        Dialogs.error(group.getShell(),
                I18n.t("freqResp.error.start.title"),
                message != null ? message : "");
    }

    /** Warns once about a sweep whose raw capture reached the converter rail.
     *  Called from {@link #onMeasurementStopped}, i.e. after the modal
     *  progress dialog is gone - the trace is already on screen behind the
     *  dialog, which is the point: the operator sees the curve the warning
     *  is about. */
    private void warnIfClipped() {
        if (!clippedWarningPending) return;
        clippedWarningPending = false;
        if (group.isDisposed()) return;
        Dialogs.warn(group.getShell(),
                I18n.t("freqResp.warning.clipped.title"),
                I18n.t("freqResp.warning.clipped.message"));
    }

    /** Disables every control of the toolbar row (recursively) - the tabs,
     *  the Wizard and the Play button.  When unlocked, restores enabled state.
     *  A running sweep must leave every tab control read-only, the Play
     *  button included - disabling the row's {@link Composite} cascades
     *  correctly to children on every platform SWT supports.  The view stays
     *  enabled: its L / R buttons show which channel the progress meter is
     *  following, and a disabled button never paints its active fill.  The
     *  modal progress dialog keeps input away from it meanwhile. */
    private void setLocked(boolean locked) {
        if (group.isDisposed()) return;
        this.locked = locked;
        toolbarRow.setEnabled(!locked);
    }

    // -------------------------------------------------------------------------
    // Scrollbar wiring - log-frequency horizontal, linear-dB vertical
    // -------------------------------------------------------------------------

    /** Re-aligns the freq and mag scrollbar thumbs + selections from the
     *  current Preferences pan window.  Bound to the four range properties,
     *  the Nyquist fraction and AUDIO_FORMAT_CHANGED; called by the view
     *  after every curve-data change (the magnitude ceiling follows the
     *  displayed curves), plus once at construction.  The bindings also fire
     *  inside the scrollbars' own selection events, so the selection is
     *  rounded, not truncated: a truncated round trip through the
     *  preferences would undo a one-unit arrow step at deep zoom.  A call
     *  while the scrollbars are not built yet (the view's construction) is a
     *  no-op. */
    void syncScrollbars() {
        if (freqScrollbar == null || freqScrollbar.isDisposed()) return;
        if (magScrollbar  == null || magScrollbar.isDisposed())  return;
        Preferences prefs = Preferences.instance();

        // Frequency scrollbar - log scale spans FREQ_FLOOR..Nyquist.
        double nyq = nyquistHz();
        double a   = Math.log10(Math.max(FreqRespConstants.FREQ_MIN_HZ, FreqRespConstants.FREQ_MIN_HZ));
        double b   = Math.log10(Math.max(a + 1, nyq));
        double lo  = Math.log10(Math.max(FreqRespConstants.FREQ_MIN_HZ, prefs.getFreqRespFreqMinHz()));
        double hi  = Math.log10(Math.max(lo + 1e-9, prefs.getFreqRespFreqMaxHz()));
        double visibleF = hi - lo;
        double totalF   = b - a;
        if (totalF > 0) {
            int thumb = (int) Math.max(1, Math.min(SCROLL_RANGE,
                    SCROLL_RANGE * (visibleF / totalF)));
            double pos = (totalF - visibleF) > 0
                    ? (lo - a) / (totalF - visibleF) : 0.0;
            int sel = (int) Math.max(0, Math.min(SCROLL_RANGE - thumb,
                    Math.round(pos * (SCROLL_RANGE - thumb))));
            freqScrollbar.setThumb(thumb);
            freqScrollbar.setSelection(sel);
            freqScrollbar.setPageIncrement(Math.max(1, thumb / 2));
            freqScrollbar.setIncrement(Math.max(1, thumb / 10));
        }

        // Magnitude scrollbar - linear dB range capped at view.magCeilingDb() /
        // FreqRespConstants.MAG_BOT_MIN_DB.  Thumb size shows the visible slice's share.
        double magTopMax = view.magCeilingDb();
        double visibleM = prefs.getFreqRespMagTopDb() - prefs.getFreqRespMagBotDb();
        double totalM   = magTopMax - FreqRespConstants.MAG_BOT_MIN_DB;
        if (totalM > 0 && visibleM > 0) {
            int thumb = (int) Math.max(1, Math.min(SCROLL_RANGE,
                    SCROLL_RANGE * (visibleM / totalM)));
            double pos = (totalM - visibleM) > 0
                    ? (magTopMax - prefs.getFreqRespMagTopDb()) / (totalM - visibleM)
                    : 0.0;
            int sel = (int) Math.max(0, Math.min(SCROLL_RANGE - thumb,
                    Math.round(pos * (SCROLL_RANGE - thumb))));
            magScrollbar.setThumb(thumb);
            magScrollbar.setSelection(sel);
            magScrollbar.setPageIncrement(Math.max(1, thumb / 2));
            magScrollbar.setIncrement(Math.max(1, thumb / 10));
        }
    }

    private void applyFreqScrollbar() {
        Preferences prefs = Preferences.instance();
        double nyq = nyquistHz();
        double a   = Math.log10(FreqRespConstants.FREQ_MIN_HZ);
        double b   = Math.log10(Math.max(a + 1, nyq));
        double total = b - a;
        double visible = Math.log10(Math.max(FreqRespConstants.FREQ_MIN_HZ, prefs.getFreqRespFreqMaxHz()))
                       - Math.log10(Math.max(FreqRespConstants.FREQ_MIN_HZ, prefs.getFreqRespFreqMinHz()));
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
        double total   = magTopMax - FreqRespConstants.MAG_BOT_MIN_DB;
        double visible = prefs.getFreqRespMagTopDb() - prefs.getFreqRespMagBotDb();
        int sel   = magScrollbar.getSelection();
        int thumb = magScrollbar.getThumb();
        double frac = (double) sel / Math.max(1, SCROLL_RANGE - thumb);
        double newTop = magTopMax - frac * Math.max(0, total - visible);
        prefs.setFreqRespMagTopDb(newTop);
        prefs.setFreqRespMagBotDb(newTop - visible);
        view.redraw();
    }

    /** Maximal analyzed frequency for the FreqResp view - Nyquist
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
    protected AbstractTabControl<?> tabStrip() {
        return tabControl;
    }

    @Override
    protected void onTabCollapse() {
        Composite toolbarRow = tabControl.getParent();
        if (toolbarRow.isDisposed() || group.isDisposed()) return;
        GridData rowGd = (GridData) toolbarRow.getLayoutData();
        rowGd.heightHint = tabControl.isTabsCollapsed() ? SWT.DEFAULT : TOOLBAR_ROW_HEIGHT;
        group.layout(true, true);
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

    /** FreqResp's "Loaded: ..." banner occupies the top-right, so the comment
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
