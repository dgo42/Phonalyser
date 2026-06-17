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
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.FormAttachment;
import org.eclipse.swt.layout.FormData;
import org.eclipse.swt.layout.FormLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.edgo.audio.measure.common.Constants;
import org.edgo.audio.measure.common.FreqRespCorrectionStore;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.AbstractPane;
import org.edgo.audio.measure.gui.common.AbstractTabControl;
import org.edgo.audio.measure.gui.common.IconUtils;
import org.edgo.audio.measure.gui.common.SvgPaths;
import org.edgo.audio.measure.gui.fft.predistortion.PredistortionWizardDialog;
import org.edgo.audio.measure.gui.generator.GeneratorController;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.widgets.FlatScrollbar;
import org.edgo.audio.measure.gui.widgets.PaneTitle;
import org.edgo.audio.measure.preferences.Preferences;

import lombok.Getter;

/**
 * Live FFT analysis pane.  Hosts the {@link FftView} canvas, two flat
 * scrollbars (frequency pan / magnitude pan), and the {@link FftTabControl}
 * — a self-contained tile-tab folder with the Settings, THD, Presets,
 * Utility, Calibration and Save / Load tabs — plus the record-LED toggle.
 */
public final class FftPane extends AbstractPane {

    /** Resolution of the FlatScrollbars (any large integer — slider values
     *  are mapped to fractional positions). */
    private static final int SCROLL_RANGE = 1_000_000;
    /** Screenshot comment caption top (px) — under the FFT averages/percent/unit overlay. */
    private static final int SCREENSHOT_COMMENT_TOP_PX = 70;

    @Getter
    private FftView         view;
    /** Controller owning the analyser worker, the frequency-lock loops and
     *  the .fft file round-trip; injected into the view + tab control.
     *  The live pane receives the app-lifetime instance (built in
     *  {@code UIEngines}, survives content rebuilds — the averaging
     *  accumulator keeps counting through a language / font change); the
     *  offscreen screenshot clone builds its own idle instance. */
    private final FftController controller;
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

    /**
     * Constructs the live pane around the injected app-lifetime engine —
     * the Record toggle drives {@code controller}; when it is already
     * recording (this is a rebuilt pane after a language / font change)
     * the Record LED lights up and the view simply picks up the worker's
     * next published result.
     */
    public FftPane(Composite parent, GeneratorController genController, FftController controller,
                   FreqRespCorrectionStore correctionStore) {
        this(parent, true, genController, controller, correctionStore);
    }

    /**
     * Offscreen screenshot variant: builds its own silent correction store
     * and an idle controller (worker never started), so constructing it
     * fires no bus events and opens no audio device.
     */
    public FftPane(Composite parent) {
        this(parent, false, null, null, null);
    }

    private FftPane(Composite parent, boolean liveCapture, GeneratorController genController,
                    FftController controller, FreqRespCorrectionStore correctionStoreIn) {
        super(parent);
        // FFT-length changes, capture acquire / release, and the
        // generator-running query all flow through the MessageBus — no
        // callback parameters needed for those concerns.  The analyser
        // worker acquires and releases its own shared capture on start /
        // stop; the pane just drives the Record button.
        IconUtils icons = IconUtils.instance();
        Display d = parent.getDisplay();
        this.recordDim     = icons.createRecordLed(d, 200,  40,  40, false, ACTION_ICON_SIZE);
        this.recordLit     = icons.createRecordLed(d, 255,   0,   0, true,  ACTION_ICON_SIZE);

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

        // The live pane receives the app-lifetime correction store +
        // controller (built in UIEngines; the store bridges its changes
        // onto the bus event the worker + tab subscribe to).  The offscreen
        // screenshot clone (liveCapture=false) builds its own SILENT store —
        // so building its calibration tab fires no events; corrections are
        // still applied because the tab repopulates the store from prefs —
        // and an idle controller whose worker is never started.
        FreqRespCorrectionStore correctionStore =
                liveCapture ? correctionStoreIn : new FreqRespCorrectionStore("FFT", null);
        if (!liveCapture) {
            controller = new FftController(new FftAnalyzerWorker(d), correctionStore);
        }
        this.controller = controller;
        view = new FftView(plotRow, correctionStore, controller);
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
        GridLayout trGl = new GridLayout(liveCapture ? 3 : 2, false);
        trGl.marginWidth = 0; trGl.marginHeight = 0; trGl.horizontalSpacing = 4;
        toolbarRow.setLayout(trGl);

        // The tile-tab folder + every settings / presets / calibration /
        // save-load tab live in a self-contained control; the pane keeps only
        // the chart, scrollbars and Record button.  Cross-pane concerns (stop
        // recording on file load, open the screenshot dialog) are routed back
        // here over the MessageBus.
        toolbarTabs = new FftTabControl(toolbarRow, view, liveCapture, correctionStore,
                controller);
        toolbarTabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        // Collapsing the tab body (strip double-click / Enter, or the
        // screenshot path) frees vertical space — re-flow the pane so the
        // chart above reclaims it.
        toolbarTabs.setCollapseRelayout(() -> {
            if (!group.isDisposed()) group.layout(true, true);
        });

        // Predistortion-wizard button (live pane only) — left of Record,
        // mirroring the Frequency-Response pane's wizard button.  Drives the
        // running generator + FFT through the closed-loop predistortion run.
        if (liveCapture && genController != null) {
            FftController wizardController = controller;   // effectively final for the lambda
            Button wizardButton = createActionButton(toolbarRow, SWT.PUSH);
            wizardButton.setImage(icons.renderAtHeightColored(d, SvgPaths.WAND, ACTION_ICON_SIZE));
            wizardButton.setToolTipText(I18n.t("predistortion.button.wizard.tooltip"));
            wizardButton.addListener(SWT.Selection, e ->
                    new PredistortionWizardDialog(group.getShell(), genController, wizardController, view, correctionStore).open());
            wizardButton.setData("helpAnchor", "fft.html#fft-predistortion");
        }

        recordButton = createActionButton(toolbarRow, SWT.TOGGLE);
        recordButton.setImage(recordDim);
        recordButton.setToolTipText(I18n.t("fft.record.tooltip"));

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

        // The injected controller survives content rebuilds — when this is
        // a rebuilt pane the analyser may already be recording: light the
        // Record LED; the averaging accumulator keeps counting and the view
        // picks up the worker's next published result.
        if (this.controller.isRecording()) {
            recordButton.setSelection(true);
            recordButton.setImage(recordLit);
        }

        // The controller deliberately keeps running across a pane teardown
        // (a content rebuild must not stop the analyser or wipe its
        // accumulator); UIEngines shuts it down at application exit.  The
        // offscreen clone's own controller was never started.
        group.addDisposeListener(e -> {
            MessageBus bus2 = MessageBus.instance();
            bus2.unsubscribe(Events.FFT_RANGE_CHANGED,             rangeChangedListener);
            bus2.unsubscribe(Events.FFT_RECORDING_AUTO_STOPPED,    autoStoppedListener);
            bus2.unsubscribe(Events.FFT_RECORDING_STOP_REQUESTED,  recordStopRequestedListener);
            bus2.unsubscribe(Events.FFT_SCREENSHOT_REQUESTED,      screenshotRequestedListener);
            bus2.unsubscribe(Events.FREQRESP_MEASUREMENT_STARTED,  freqRespStartedListener);
            bus2.unsubscribe(Events.FREQRESP_MEASUREMENT_STOPPED,  freqRespStoppedListener);
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

    @Override
    protected AbstractTabControl tabStrip() {
        return toolbarTabs;
    }

    /** Registers this pane's settings tabs in the component registry under
     *  {@code prefix} so automation can select each by path.  See
     *  {@link FftTabControl#registerTabs}. */
    public void registerTabs(String prefix) {
        if (toolbarTabs != null && !toolbarTabs.isDisposed()) {
            toolbarTabs.registerTabs(prefix);
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
        controller.startRecording();
        if (!controller.isRecording()) {
            recordButton.setSelection(false);
            return;
        }
        recordButton.setSelection(true);
        recordButton.setImage(recordLit);
    }

    /** Programmatically engages Record and lights the LED — the
     *  {@code gui.automation} scripts' Record.  On an acquire failure the
     *  button silently un-toggles (no modal dialog in an unattended run);
     *  callers can check {@link #isRecording()}. */
    public void engageRecord() {
        recordOn();
    }

    /** True while the FFT analyser is recording. */
    public boolean isRecording() {
        return controller.isRecording();
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
        controller.stopRecording();
        view.clearWarningBanner();   // a re-sync / overrun warning is moot once stopped
        recordButton.setSelection(false);
        recordButton.setImage(recordDim);
    }

    // -------------------------------------------------------------------------
    // Pane-owned dialogs
    // -------------------------------------------------------------------------

    /** The FFT comment caption sits under the averages / percent / unit overlay. */
    @Override
    protected int screenshotCommentTopPx() {
        return SCREENSHOT_COMMENT_TOP_PX;
    }

    /** Loads and displays a {@code .fft} spectrum file (same as the Load-from
     *  tab) — lets a programmatic caller (help/video automation) show a real
     *  spectrum without a live capture.  Delegates to the tab control, which
     *  owns the {@code .fft} round-trip. */
    public void loadSpectrum(String path) {
        if (toolbarTabs != null) toolbarTabs.loadSpectrum(path);
    }

    /** Builds the offscreen FFT clone for {@link #renderOffscreen}: fresh pane,
     *  live render snapshot copied in, tab body collapsed, pan re-synced. */
    @Override
    protected AbstractPane createSnapshotClone(Composite parent) {
        FftPane clone = new FftPane(parent);
        clone.copySnapshotFrom(this);
        clone.refreshFromPrefs();
        return clone;
    }


    /** Copies the live pane's render snapshot (spectrum result, IMD
     *  slot and table mode) into this pane's view — used by the
     *  offscreen screenshot clone so it draws exactly what the live
     *  pane shows. */
    public void copySnapshotFrom(FftPane source) {
        if (source == null || source == this) return;
        view.copySnapshotFrom(source.view);
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
