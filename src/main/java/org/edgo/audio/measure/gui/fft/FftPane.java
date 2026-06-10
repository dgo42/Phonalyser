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

import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.FormAttachment;
import org.eclipse.swt.layout.FormData;
import org.eclipse.swt.layout.FormLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.edgo.audio.measure.common.Constants;
import org.edgo.audio.measure.common.FreqRespCorrectionStore;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.gui.MainTab;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.IconUtils;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.preferences.Preferences;
import org.edgo.audio.measure.gui.scope.ScreenshotDialog;
import org.edgo.audio.measure.gui.widgets.FlatScrollbar;
import org.edgo.audio.measure.gui.widgets.PaneTitle;

import lombok.Getter;

/**
 * Live FFT analysis pane.  Hosts the {@link FftView} canvas, two flat
 * scrollbars (frequency pan / magnitude pan), and the {@link FftTabControl}
 * — a self-contained tile-tab folder with the Settings, THD, Presets,
 * Utility, Calibration and Save / Load tabs — plus the record-LED toggle.
 */
public final class FftPane {

    /** Resolution of the FlatScrollbars (any large integer — slider values
     *  are mapped to fractional positions). */
    private static final int SCROLL_RANGE = 1_000_000;

    /** Pixel height of the big record-LED button at the right of the top
     *  toolbar — matches the scope's record LED so the two panes look
     *  visually aligned. */
    private static final int RECORD_LED_SIZE = 33;

    @Getter
    private final Composite group;
    private PaneTitle title;
    @Getter
    private FftView         view;
    private FlatScrollbar   freqScrollbar;
    private FlatScrollbar   magScrollbar;

    // Image references for the record-LED toggle; both instances come from the
    // shared IconUtils cache so disposal is handled centrally.
    private final Image recordDim;
    private final Image recordLit;
    private Button recordButton;

    /** The self-contained tile-tab folder hosting every settings / presets /
     *  calibration / save-load tab; see {@link FftTabControl}.  The pane keeps
     *  only the chart, scrollbars and Record button. */
    private FftTabControl toolbarTabs;

    /** Subscriber for {@link Events#FFT_RANGE_CHANGED}, stored as a
     *  field so the dispose listener can unsubscribe the SAME instance
     *  (method references compare by identity). */
    private Consumer<Void> rangeChangedListener;
    /** Subscriber for {@link Events#FFT_RECORDING_AUTO_STOPPED} — fires
     *  when the analyser's stop-after-N counter trips so the pane can
     *  flip Record back off and release the shared capture. */
    private Consumer<Void> autoStoppedListener;
    /** Subscriber for {@link Events#FFT_RECORDING_STOP_REQUESTED} — the
     *  {@link FftTabControl} asks the pane to stop live recording when a
     *  static spectrum is loaded (the pane owns the Record button + shared
     *  capture reference). */
    private Consumer<Void> recordStopRequestedListener;
    /** Subscriber for {@link Events#FFT_SCREENSHOT_REQUESTED} — the
     *  {@link FftTabControl}'s Utility-tab camera button; the pane owns the
     *  screenshot dialog because it clones the whole pane offscreen. */
    private Consumer<Void> screenshotRequestedListener;
    /** Subscriber for {@link Events#FREQRESP_MEASUREMENT_STARTED} — the
     *  Frequency Response pane is about to drive the capture device
     *  exclusively, so this pane must stop any running recording and
     *  gray its Record button so it can't be re-engaged mid-sweep. */
    private Consumer<Void> freqRespStartedListener;
    /** Counterpart to {@link #freqRespStartedListener} — re-enables the
     *  Record button once the sweep finishes (or aborts). */
    private Consumer<Void> freqRespStoppedListener;

    /** Collapse state + per-child snapshot.  See {@link #setCollapsed(boolean)}. */
    @Getter
    private boolean    collapsed;
    private boolean[]  preCollapseChildVisible;
    private boolean[]  preCollapseChildExclude;

    public FftPane(Composite parent,
                   boolean liveCapture) {
        // FFT-length changes, capture acquire / release, and the
        // generator-running query all flow through the MessageBus — no
        // callback parameters needed for those concerns.  The analyser
        // worker (inside FftView) acquires and releases its own shared
        // capture on start / stop; the pane just drives the Record button.
        IconUtils icons = IconUtils.instance();
        Display d = parent.getDisplay();
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

        // The pane owns the calibration-correction store and injects the same
        // instance into the view and tab control (IoC).  A live pane bridges
        // store changes onto the bus event the worker + tab subscribe to; the
        // offscreen screenshot clone (liveCapture=false) gets a silent store so
        // building its calibration tab fires no events — corrections are still
        // applied because the tab repopulates the store from prefs.
        FreqRespCorrectionStore correctionStore = new FreqRespCorrectionStore("FFT",
                liveCapture ? () -> MessageBus.instance().publish(Events.FFT_CALIBRATION_CHANGED)
                            : null);
        view = new FftView(plotRow);
        view.setCorrectionStore(correctionStore);
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

        // ---- Toolbar row: the FftTabControl (left, grabs space) + Record
        // toggle anchored on the right.  Mirrors the scope's toolbar layout so
        // the record button always stays accessible.
        Composite toolbarRow = new Composite(group, SWT.NONE);
        toolbarRow.setLayoutData(new GridData(SWT.FILL, SWT.BEGINNING, true, false));
        GridLayout trGl = new GridLayout(2, false);
        trGl.marginWidth = 0; trGl.marginHeight = 0; trGl.horizontalSpacing = 4;
        toolbarRow.setLayout(trGl);

        // The tile-tab folder + every settings / presets / calibration /
        // save-load tab live in a self-contained control; the pane keeps only
        // the chart, scrollbars and Record button.  Cross-pane concerns (stop
        // recording on file load, open the screenshot dialog) are routed back
        // here over the MessageBus.
        toolbarTabs = new FftTabControl(toolbarRow, view, liveCapture);
        toolbarTabs.setCorrectionStore(correctionStore);
        toolbarTabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        // Collapsing the tab body (strip double-click / Enter, or the
        // screenshot path) frees vertical space — re-flow the pane so the
        // chart above reclaims it.
        toolbarTabs.setCollapseRelayout(() -> {
            if (!group.isDisposed()) group.layout(true, true);
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
        // MessageBus subscriptions back to the pane:
        //   - FFT_RANGE_CHANGED: view publishes after pan / zoom or
        //     auto-setup / maximize → realign the scrollbars.
        //   - FFT_RECORDING_AUTO_STOPPED: view publishes when its
        //     stop-after-N counter trips → release record state.
        //   - FFT_RECORDING_STOP_REQUESTED / FFT_SCREENSHOT_REQUESTED:
        //     the FftTabControl publishes these for the pane to service.
        rangeChangedListener        = ignored -> syncFftPan();
        autoStoppedListener         = ignored -> disengageRecord();
        recordStopRequestedListener = ignored -> {
            if (recordButton != null && !recordButton.isDisposed() && recordButton.getSelection()) {
                recordOff();
            }
        };
        screenshotRequestedListener = ignored -> openScreenshotDialog();
        freqRespStartedListener     = ignored -> onFreqRespMeasurementStarted();
        freqRespStoppedListener     = ignored -> onFreqRespMeasurementStopped();
        MessageBus bus = MessageBus.instance();
        bus.subscribe(Events.FFT_RANGE_CHANGED,             rangeChangedListener);
        bus.subscribe(Events.FFT_RECORDING_AUTO_STOPPED,    autoStoppedListener);
        bus.subscribe(Events.FFT_RECORDING_STOP_REQUESTED,  recordStopRequestedListener);
        bus.subscribe(Events.FFT_SCREENSHOT_REQUESTED,      screenshotRequestedListener);
        bus.subscribe(Events.FREQRESP_MEASUREMENT_STARTED,  freqRespStartedListener);
        bus.subscribe(Events.FREQRESP_MEASUREMENT_STOPPED,  freqRespStoppedListener);

        recordButton.addListener(SWT.Selection, e -> {
            if (recordButton.getSelection()) recordOn();
            else                              recordOff();
        });

        group.addDisposeListener(e -> {
            MessageBus bus2 = MessageBus.instance();
            bus2.unsubscribe(Events.FFT_RANGE_CHANGED,             rangeChangedListener);
            bus2.unsubscribe(Events.FFT_RECORDING_AUTO_STOPPED,    autoStoppedListener);
            bus2.unsubscribe(Events.FFT_RECORDING_STOP_REQUESTED,  recordStopRequestedListener);
            bus2.unsubscribe(Events.FFT_SCREENSHOT_REQUESTED,      screenshotRequestedListener);
            bus2.unsubscribe(Events.FREQRESP_MEASUREMENT_STARTED,  freqRespStartedListener);
            bus2.unsubscribe(Events.FREQRESP_MEASUREMENT_STOPPED,  freqRespStoppedListener);
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
            if (group.isDisposed()) return;
            group.layout(true, true);
            // Startup: nothing has fired a range-change yet, so align the scrollbar
            // thumbs to the pan window restored from Preferences — otherwise they keep
            // their constructor defaults and a saved zoom shows no scrollbar feedback.
            syncFftPan();
        });

        wireHelpAnchors();
    }

    /** Tags the pane-level toolbar widgets (Record, scrollbars) with a
     *  {@code "helpAnchor"} so Ctrl+F1 can resolve the focused control to a
     *  specific section of {@code fft.html}.  The tab widgets anchor
     *  themselves inside {@link FftTabControl}; the chapter-level anchor on
     *  {@link #group} serves as the fallback when focus lands on something
     *  unattached (e.g. a painted tab tile). */
    private void wireHelpAnchors() {
        group              .setData("helpAnchor", "fft.html");
        if (recordButton        != null) recordButton       .setData("helpAnchor", "fft.html#fft-record-button");
        if (freqScrollbar       != null) freqScrollbar      .setData("helpAnchor", "fft.html#fft-scrollbar-freq");
        if (magScrollbar        != null) magScrollbar       .setData("helpAnchor", "fft.html#fft-scrollbar-mag");
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
     *  them by hand.  Delegates to the {@link FftTabControl}. */
    public void paintTabTilesInto(GC gc) {
        if (toolbarTabs != null && !toolbarTabs.isDisposed()) {
            toolbarTabs.paintTilesInto(gc, group);
        }
    }

    /** Collapses or expands the toolbar tab body so the screenshot path can
     *  hide the settings tabs before printing.  Delegates to the
     *  {@link FftTabControl}. */
    public void setTabsCollapsed(boolean collapsed) {
        if (toolbarTabs != null && !toolbarTabs.isDisposed()) {
            toolbarTabs.setTabsCollapsed(collapsed);
        }
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
        // Magnitude range is canonical dBFS for every unit — clamp linearly.
        double maxTp = view.magCeiling();            // dBFS ceiling (≥ 0, raised by a lifted signal)
        double minBt = Constants.MAG_FLOOR_DBFS;     // dBFS floor
        double mTop = prefs.getFftMagTop();
        double mBot = prefs.getFftMagBottom();
        if (mTop > maxTp) mTop = maxTp;
        if (mBot < minBt) mBot = minBt;
        if (mTop - mBot < 1) mBot = mTop - 1;    // keep ≥ 1 dB visible
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

        // ---- Magnitude scrollbar (canonical dBFS range — linear for every unit)
        double maxTp = view.magCeiling();
        double minBt = Constants.MAG_FLOOR_DBFS;
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
        // Canonical dBFS range — pan linearly, identically for every unit.
        double maxTp = view.magCeiling();
        double minBt = Constants.MAG_FLOOR_DBFS;
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
}
