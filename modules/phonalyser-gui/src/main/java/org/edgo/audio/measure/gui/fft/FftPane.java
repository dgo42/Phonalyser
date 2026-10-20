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

import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.FormAttachment;
import org.eclipse.swt.layout.FormData;
import org.eclipse.swt.layout.FormLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.edgo.audio.measure.common.Constants;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.gui.bind.Bindings;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.AbstractPane;
import org.edgo.audio.measure.gui.common.AbstractTabControl;
import org.edgo.audio.measure.gui.common.CalibrationDialog;
import org.edgo.audio.measure.gui.common.Dialogs;
import org.edgo.audio.measure.gui.common.Icon;
import org.edgo.audio.measure.gui.common.IconUtils;
import org.edgo.audio.measure.gui.fft.predistortion.PredistortionWizardDialog;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.widgets.FlatScrollbar;
import org.edgo.audio.measure.gui.widgets.PaneTitle;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.CaptureEndReason;

import lombok.Getter;

/**
 * Live FFT analysis pane.  Hosts the {@link FftView} canvas, two flat
 * scrollbars (frequency pan / magnitude pan), and the {@link FftTabControl}
 * - a self-contained tile-tab folder with the Settings, THD, Presets,
 * Utility, Calibration and Save / Load tabs - plus the record-LED toggle.
 */
public final class FftPane extends AbstractPane {

    /** Resolution of the FlatScrollbars (any large integer - slider values
     *  are mapped to fractional positions). */
    private static final int SCROLL_RANGE = 1_000_000;
    /** Screenshot comment caption top (px) - under the FFT averages/percent/unit overlay. */
    private static final int SCREENSHOT_COMMENT_TOP_PX = 70;
    /** Smallest magnitude span (dB) the vertical zoom may leave visible.  It
     *  exists only to keep the span positive - the axis mapping divides by it -
     *  so it is deliberately far below any useful measurement: 0.01 dB still
     *  shows ±0.005 dB of ripple across the full plot height, which is the
     *  resolution a flatness or notch-depth check needs.  The dB tick labels
     *  follow the step down to four decimals, so the grid stays readable. */
    private static final double MIN_MAG_SPAN_DB = 0.01;

    @Getter
    private FftView         view;
    /** Controller owning the analyser worker, the frequency-lock loops and
     *  the .fft file round-trip.  Built by this pane, with this pane - the
     *  receiver of the worker's messages - and injected into the view + tab
     *  control.  The offscreen screenshot clone's controller is never
     *  started. */
    private final FftController controller;

    private FlatScrollbar   freqScrollbar;
    private FlatScrollbar   magScrollbar;

    // Image references for the record-LED toggle; both instances come from the
    // shared IconUtils cache so disposal is handled centrally.
    private final Image recordDim;
    private final Image recordLit;
    private Button recordButton;
    /** The predistortion wizard's button - live pane only, {@code null} on the
     *  screenshot copy.  Enabled only while the analyser records: the wizard
     *  measures the running FFT. */
    private Button wizardButton;

    /** The self-contained tile-tab folder hosting every settings / presets /
     *  calibration / save-load tab; see {@link FftTabControl}.  The pane keeps
     *  only the chart, scrollbars and Record button. */
    private FftTabControl toolbarTabs;

    /** Subscriber for {@link Events#FREQRESP_MEASUREMENT_STARTED} - the
     *  Frequency Response pane is about to drive the capture device
     *  exclusively, so this pane must stop any running recording and
     *  gray its Record button so it can't be re-engaged mid-sweep. */
    private Consumer<Void> freqRespStartedListener;
    /** Counterpart to {@link #freqRespStartedListener} - re-enables the
     *  Record button once the sweep finishes (or aborts). */
    private Consumer<Void> freqRespStoppedListener;

    /** Constructs the live pane: it builds its controller, which owns the
     *  analyser worker, and the Record toggle drives it. */
    public FftPane(Composite parent) {
        this(parent, false);
    }

    /** Builds + shows the DAC-predistortion wizard for a help capture and
     *  returns it, so an automation script can drive it (start,
     *  screenshot).  The pane owns the wizard's collaborators, so it
     *  constructs the wizard here rather than exposing them. */
    public PredistortionWizardDialog openPredistortionForCapture() {
        return openPredistortionWizard();
    }

    /** Switches the FFT to coherent averaging, then builds and shows the
     *  DAC-predistortion wizard.  The wizard measures the coherent average's
     *  phases, which an incoherent average does not carry, so opening it is
     *  what selects the averaging it needs. */
    private PredistortionWizardDialog openPredistortionWizard() {
        Preferences.instance().setFftCoherentAveraging(true);
        PredistortionWizardDialog d = new PredistortionWizardDialog(
                getGroup().getShell(), controller, view);
        d.buildAndShow();
        return d;
    }

    /** Builds + shows the ADC calibration dialog in its two-row form for a help
     *  capture (analyzed channel prefilled, the other blank/disabled) without a
     *  live measurement, returning it so an automation script can screenshot and
     *  dispose it.  Delegates to the toolbar {@link FftTabControl}. */
    public CalibrationDialog openAdcCalibrationForCapture() {
        return toolbarTabs == null ? null : toolbarTabs.openAdcCalibrationForCapture();
    }

    /** @param offScreen the offscreen screenshot variant: its controller is
     *                   never started (no audio device opened) and it renders
     *                   from that controller's store.  Silent: neither it nor
     *                   anything it owns publishes or subscribes on the bus,
     *                   so the copy never reaches the live pane. */
    private FftPane(Composite parent, boolean offScreen) {
        super(parent);
        // FFT-length changes, capture acquire / release, and the
        // generator-running query all flow through the MessageBus - no
        // callback parameters needed for those concerns.  The analyser
        // worker acquires and releases its own shared capture on start /
        // stop; the pane just drives the Record button.
        Display d = parent.getDisplay();
        this.recordDim     = IconUtils.icon(d, Icon.RECORD_DARK);
        this.recordLit     = IconUtils.icon(d, Icon.RECORD_LIT);

        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth  = 0; gl.marginHeight = 0; gl.verticalSpacing = 2;
        group.setLayout(gl);
        title = new PaneTitle(group, Events.PANE_ID_FFT,
                "fft.title.expanded",
                "fft.title.collapsed",
                "fft.pane.toggle.tooltip");

        // ---- Plot row: FftView fills the available area, with the vertical
        // magnitude scrollbar pinned to the right edge AND the magnitude-unit
        // combo overlaid in the top-right corner over the FftView.  FormLayout
        // lets us position the combo on top of the canvas in z-order.
        Composite plotRow = new Composite(group, SWT.NONE);
        plotRow.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        plotRow.setLayout(new FormLayout());

        // The pane builds its controller, which owns the correction store and
        // the analyser worker, with this pane as the receiver of the worker's
        // messages.  The offscreen screenshot clone's controller is never
        // started.
        this.controller = new FftController(this);
        view = new FftView(plotRow, this, controller, offScreen);
        magScrollbar = new FlatScrollbar(plotRow, SWT.VERTICAL);
        magScrollbar.setMinimum(0);
        magScrollbar.setMaximum(SCROLL_RANGE);
        magScrollbar.setThumb(SCROLL_RANGE / 4);
        magScrollbar.setSelection(0);
        Bindings.text(magScrollbar, "fft.scrollbar.magnitude.tooltip", magScrollbar::setToolTipText);
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
        // row so its right edge stops 18 px short of the group right -
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
        Bindings.text(freqScrollbar, "fft.scrollbar.frequency.tooltip", freqScrollbar::setToolTipText);
        freqScrollbar.addSelectionListener(e -> applyFreqScrollbar());

        // ---- Toolbar row: the FftTabControl (left, grabs space) + Record
        // toggle anchored on the right.  Mirrors the scope's toolbar layout so
        // the record button always stays accessible.
        Composite toolbarRow = new Composite(group, SWT.NONE);
        toolbarRow.setLayoutData(new GridData(SWT.FILL, SWT.BEGINNING, true, false));
        GridLayout trGl = new GridLayout(offScreen ? 2 : 3, false);
        trGl.marginWidth = 0; trGl.marginHeight = 0; trGl.horizontalSpacing = 4;
        toolbarRow.setLayout(trGl);

        // The tile-tab folder + every settings / presets / calibration /
        // save-load tab live in a self-contained control; the pane keeps only
        // the chart, scrollbars and Record button.  The control calls its
        // owner - this pane - for what the pane owns (stop recording on file
        // load, the screenshot dialog, the scrollbar re-sync).
        // Collapsing the tab body (strip double-click / Enter, or the
        // screenshot path) frees vertical space - the control's owner (this
        // pane) re-flows so the chart above reclaims it.
        toolbarTabs = new FftTabControl(toolbarRow, view, offScreen, controller, this);
        toolbarTabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        // Predistortion-wizard button (live pane only) - left of Record,
        // mirroring the Frequency-Response pane's wizard button.
        if (!offScreen) {
            wizardButton = createActionButton(toolbarRow, SWT.PUSH);
            wizardButton.setImage(IconUtils.icon(d, Icon.WAND));
            Bindings.text(wizardButton, "predistortion.button.wizard.tooltip", wizardButton::setToolTipText);
            wizardButton.addListener(SWT.Selection, e -> openPredistortionWizard());
            wizardButton.setData("helpAnchor", "fft.html#fft-predistortion");
        }

        recordButton = createActionButton(toolbarRow, SWT.TOGGLE);
        recordButton.setSize(new Point(ACTION_BOX_SIZE, ACTION_BOX_SIZE));
        recordButton.setImage(recordDim);
        Bindings.text(recordButton, "fft.record.tooltip", recordButton::setToolTipText);

        // ---- Wiring.  The analyser runs in the controller's worker; the
        // pane drives its recording through the Record button.  The worker's
        // messages arrive through the controller (onResultAvailable ...
        // disengageRecord); the view and the tab control call the pane
        // directly.  Only the FreqResp sweep, another module, reaches the
        // pane over the bus.
        freqRespStartedListener     = ignored -> onFreqRespMeasurementStarted();
        freqRespStoppedListener     = ignored -> onFreqRespMeasurementStopped();
        if (!offScreen) {
            MessageBus bus = MessageBus.instance();
            bus.subscribe(Events.FREQRESP_MEASUREMENT_STARTED,  freqRespStartedListener);
            bus.subscribe(Events.FREQRESP_MEASUREMENT_STOPPED,  freqRespStoppedListener);
        }

        recordButton.addListener(SWT.Selection, e -> {
            if (recordButton.getSelection()) {
                recordOn();
            } else {
                recordOff();
            }
        });
        updateWizardEnabled();

        // The pane owns its controller, so the live pane stops it with
        // itself.  The offscreen clone's controller was never started, and a
        // stop of it would still reset the generator's frequency trim.
        group.addDisposeListener(e -> {
            MessageBus bus2 = MessageBus.instance();
            bus2.unsubscribe(Events.FREQRESP_MEASUREMENT_STARTED,  freqRespStartedListener);
            bus2.unsubscribe(Events.FREQRESP_MEASUREMENT_STOPPED,  freqRespStoppedListener);
            if (!offScreen) controller.shutdown();
        });

        // Re-layout once the event loop spins up.  At constructor exit
        // the CTabFolder's tab body still reports a smaller natural
        // size than it ends up with after the first event-pump (font
        // metrics, native chrome, etc. finalise lazily).  Without a
        // fresh layout the initial render clipped the last row of the
        // Settings tab - a subsequent collapse/expand cycle "fixed"
        // it because the second pass had the right metrics.  Deliberate
        // SAME-thread deferral - not a marshal, so not GuiUtil.marshal
        // (which runs in place on the UI thread).
        group.getDisplay().asyncExec(() -> {
            if (group.isDisposed()) return;
            group.layout(true, true);
            // Startup: nothing has fired a range-change yet, so align the scrollbar
            // thumbs to the pan window restored from Preferences - otherwise they keep
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
     *  cycle - the usual {@code onAnalysisPublished} pipeline that
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
    protected AbstractTabControl<?> tabStrip() {
        return toolbarTabs;
    }

    @Override
    protected void onTabCollapse() {
        if (!group.isDisposed()) group.layout(true, true);
    }

    /** Registers this pane's settings tabs in the component registry under
     *  {@code prefix} so automation can select each by path.  See
     *  {@link FftTabControl#registerTabs}. */
    public void registerTabs(String prefix) {
        if (toolbarTabs != null && !toolbarTabs.isDisposed()) {
            toolbarTabs.registerTabs(prefix);
        }
    }

    /** The controller's call on the UI thread when the analyser stopped on
     *  its own - the stop-after-N counter fired, the capture ended from below
     *  (device lost / delivery stalled), the pilot lines stayed unreadable or
     *  the predistortion run settled.  Disengages record mode so the user
     *  sees the Record button switch off and the shared capture refcount
     *  drops (which lets the audio device close if no other pane is holding
     *  it).  When the worker recorded a capture-end reason, this pane owns
     *  the failed operation, so this pane tells the operator ONCE, in their
     *  language. */
    void disengageRecord() {
        CaptureEndReason reason = controller.captureEndReason();
        recordOff();
        if (reason != null) {
            // The unified stop message: the reason enum picks the text, the
            // BACKEND is named, the technical detail lives in the log alone.
            Dialogs.error(group.getShell(), I18n.t("audio.deviceError.title"),
                    I18n.t("capture.error.ended." + reason.name(),
                            Preferences.instance().getSelectedBackend().type().getDisplayName()));
        }
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

    /** The controller's call when the reference feed did not qualify when the
     *  average started, so the analyser is running it without alignment.  The recording is NOT stopped - an unaligned deep
     *  average is still a valid measurement - the operator is simply told what
     *  the feed measured, so they can fix the wiring or its level and record
     *  again.  The worker sends one refusal per collection; the numbers are
     *  formatted here because the message is the pane's. */
    void onPhaseAlignRefused(FftAnalyzerWorker.PhaseAlignRefusal refusal) {
        if (refusal == null || group.isDisposed()) return;
        Dialogs.error(group.getShell(), I18n.t("fft.phaseAlign.refused.title"),
                I18n.t("fft.phaseAlign.refused.message",
                        String.format("%.2f", refusal.fundamentalHz()),
                        String.format("%.1f", refusal.levelDbFs()),
                        String.format("%.1f", refusal.marginDb())));
    }

    /** The controller's call when the pilot lines the alignment measures the
     *  elapsed samples between FFT frames from stayed
     *  unreadable, so the coherent average has no time base left and the
     *  analyser is ending the recording on its own auto-stop path.  Nothing is
     *  stopped or cleared here - the Record button follows that path as it does
     *  for every auto-stop; this handler only tells the operator, with the
     *  frequency the collection was running at.  The number is formatted here
     *  because the message is the pane's. */
    void onPhaseAlignPilotLost(FftAnalyzerWorker.PilotLost lost) {
        if (lost == null || group.isDisposed()) return;
        Dialogs.error(group.getShell(), I18n.t("fft.phaseAlign.pilotLost.title"),
                I18n.t("fft.phaseAlign.pilotLost.message",
                        String.format("%.2f", lost.fundamentalHz())));
    }

    /** The controller's call with a finished analysis - the view shows it
     *  and copies what it keeps during this call. */
    void onResultAvailable(FftResult slot) {
        view.onResultReady(slot);
    }

    /** The controller's call with a capture re-sync warning, for the view's
     *  banner. */
    void onCaptureResync(String messageKey) {
        view.showCaptureResync(messageKey);
    }

    /** The controller's call when the fundamental level drifted while the
     *  frequency lock aligned the generator - the view's banner hints at a
     *  statistics reset. */
    void onAlignMagDrift(double deltaDb) {
        view.onAlignMagDrift(deltaDb);
    }

    /** Stops a live recording before a static spectrum is loaded - the tab
     *  control's file load; the pane owns the Record button. */
    void stopRecordingForLoad() {
        if (recordButton != null && !recordButton.isDisposed() && recordButton.getSelection()) {
            recordOff();
        }
    }

    /** Turns the Record button ON - starts the analyser worker, which acquires
     *  its own reference on the shared audio capture device (scope + FFT share
     *  the same device; whichever pane records first opens it).  Bails out
     *  silently and un-toggles the button when the acquire fails (no input
     *  device, already-busy device, etc.) - detected via {@link FftView#isRunning}. */
    private void recordOn() {
        if (recordButton == null || recordButton.isDisposed()) return;
        controller.startRecording();
        if (!controller.isRecording()) {
            recordButton.setSelection(false);
            updateWizardEnabled();
            return;
        }
        recordButton.setSelection(true);
        recordButton.setImage(recordLit);
        updateWizardEnabled();
    }

    /** Programmatically engages Record and lights the LED - the
     *  {@code gui.automation} scripts' Record.  On an acquire failure the
     *  button silently un-toggles (no modal dialog in an unattended run);
     *  callers can check {@link #isRecording()}. */
    public void engageRecord() {
        // Automation no-audio gate: a help-screenshot run started with
        // -Dphonalyser.automation.noAudio=true must never open an audio device,
        // so this script-driven Record is a no-op.  A user Record-button click
        // uses the recordButton listener/recordOn() and is unaffected.
        if (Boolean.getBoolean("phonalyser.automation.noAudio")) return;
        recordOn();
    }

    /** True while the FFT analyser is recording. */
    public boolean isRecording() {
        return controller.isRecording();
    }

    /** Loop-driven realtime repaint of the FFT view, called once per frame by the
     *  main event loop's render tick.  Returns {@code true} while the view is live
     *  (recording) so the loop keeps the realtime cadence going. */
    public boolean renderRealtimeFrame() {
        // Pull a parked worker result through BEFORE the paint decision: the
        // worker's own asyncExec drain runs only when the native event queue
        // is empty, and a GL scope's swap traffic can keep it busy for as
        // long as it renders.  This tick rides a timer, which is dispatched
        // regardless of that queue - so the spectrum stays fed however busy
        // the compositor keeps SWT.
        controller.drainPendingResult();
        return view != null && !view.isDisposed() && view.renderRealtimeFrame();
    }

    /** Whether the analyser was recording when {@link #stopCaptureForPrefs()}
     *  stopped it, so {@link #startCaptureForPrefs()} restarts exactly that.
     *  Owned here - the caller does not track the pane's recording state. */
    private boolean recordWasRunningForPrefs;

    /** Stops FFT recording ahead of a Preferences audio-config change,
     *  remembering whether it was running.  Releasing the FFT's shared-capture
     *  reference here is what lets a device / sample-rate change actually take
     *  effect - otherwise the shared device stays open at the OLD parameters and
     *  the new settings silently never apply.  Pair with
     *  {@link #startCaptureForPrefs()} after the new config is committed. */
    public void stopCaptureForPrefs() {
        recordWasRunningForPrefs = recordButton != null
                && !recordButton.isDisposed()
                && recordButton.getSelection();
        if (recordWasRunningForPrefs) recordOff();
    }

    /** Restarts FFT recording on the newly-committed backend if it was running
     *  when {@link #stopCaptureForPrefs()} stopped it. */
    public void startCaptureForPrefs() {
        if (recordWasRunningForPrefs) recordOn();
    }

    /** Turns the Record button OFF - stops the worker (which releases its own
     *  shared-capture reference) and restores the dim icon.  Called from the
     *  user's Record-button click (off path) and from {@link #disengageRecord}
     *  when the analyser's stop-after-N counter trips. */
    private void recordOff() {
        if (recordButton.isDisposed()) return;
        controller.stopRecording();
        view.clearWarningBanner();   // a re-sync / overrun warning is moot once stopped
        recordButton.setSelection(false);
        recordButton.setImage(recordDim);
        updateWizardEnabled();
    }

    /** Enables the predistortion wizard's button exactly while the analyser
     *  records - the wizard measures the running FFT. */
    private void updateWizardEnabled() {
        if (wizardButton != null && !wizardButton.isDisposed()) {
            wizardButton.setEnabled(controller.isRecording());
        }
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
     *  tab) - lets a programmatic caller (help/video automation) show a real
     *  spectrum without a live capture.  Delegates to the tab control, which
     *  owns the {@code .fft} round-trip. */
    public void loadSpectrum(String path) {
        if (toolbarTabs != null) toolbarTabs.loadSpectrum(path);
    }

    /** Builds the offscreen FFT clone for {@link #renderOffscreen}: fresh pane,
     *  live render snapshot copied in, tab body collapsed, pan re-synced. */
    @Override
    protected AbstractPane createSnapshotClone(Composite parent) {
        FftPane clone = new FftPane(parent, true);
        clone.copySnapshotFrom(this);
        clone.refreshFromPrefs();
        return clone;
    }


    /** Copies the live pane's render snapshot (spectrum result, IMD
     *  slot and table mode) into this pane's view - used by the
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
        // No result yet - assume a typical 384 kHz capture and the
        // configured FFT length.  Worst case the user sees the bin-size
        // lower bound jump once after the first analysis publishes.
        Preferences prefs = Preferences.instance();
        int sr = prefs.current().getInputSampleRate();
        int fftLen = Math.max(8, prefs.getFftLength());
        return (double) sr / fftLen;
    }

    /** Nyquist upper bound on the visible freq range. */
    private double currentNyquist() {
        FftResult r = view.getLastResult();
        double sr = 0;
        if (r != null) {
            sr = r.sampleRate / 2.0;
        } else {
            sr = Preferences.instance().current().getInputSampleRate();
        }
        return sr;
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
        // Magnitude range is canonical dBFS for every unit - clamp linearly.
        double maxTp = view.magCeiling();            // dBFS ceiling (≥ 0, raised by a lifted signal)
        double minBt = Constants.MAG_FLOOR_DBFS;     // dBFS floor
        double mTop = prefs.getFftMagTop();
        double mBot = prefs.getFftMagBottom();
        if (mTop > maxTp) mTop = maxTp;
        if (mBot < minBt) mBot = minBt;
        if (mTop - mBot < MIN_MAG_SPAN_DB) mBot = mTop - MIN_MAG_SPAN_DB;
        prefs.setFftMagTop(mTop);
        prefs.setFftMagBottom(mBot);
    }

    /** Re-aligns the frequency / magnitude scrollbar thumbs and
     *  selections to the FFT pan window currently persisted in
     *  {@code Preferences} (freq min/max, mag top/bottom, log-axis
     *  flag).  Thumb size is proportional to (visible / total) so the
     *  user gets immediate visual feedback on how much of the spectrum
     *  is hidden; selection positions the visible slice inside the full
     *  range.  Called whenever the pan window can have shifted -
     *  preset apply (the tab control), autosetup, maximize and pan / zoom
     *  (the view), FFT length change, and after a fresh analysis. */
    void syncFftPan() {
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

        // ---- Magnitude scrollbar (canonical dBFS range - linear for every unit)
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
            // Slider 0 -> top of mag range; slider max -> bottom.
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
        // Canonical dBFS range - pan linearly, identically for every unit.
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
