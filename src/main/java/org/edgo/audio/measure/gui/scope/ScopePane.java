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

package org.edgo.audio.measure.gui.scope;

import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ControlListener;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.gui.MainTab;
import org.edgo.audio.measure.gui.MainWindow;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.Dialogs;
import org.edgo.audio.measure.gui.common.IconUtils;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.gui.sound.SharedCapture;
import org.edgo.audio.measure.gui.sound.SignalBufferReader;
import org.edgo.audio.measure.gui.widgets.FlatScrollbar;
import org.edgo.audio.measure.gui.widgets.PaneTitle;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * Builds the oscilloscope pane: the {@link Composite} frame, the main
 * {@link ScopeView} + {@link ZoomedView} condensed strip, the navigation /
 * vertical scrollbars, the {@link ScopeTabControl} bottom toolbar, and the
 * Record toggle.  Extracted from {@link MainWindow} so the live pane can stay
 * attached to the {@code SashForm} while a second instance is constructed
 * inside a hidden offscreen Shell for screenshot rendering — that way the
 * screenshot is a real SWT layout at the target dimensions (buttons stay at
 * their native pixel size, the scope view's canvas claims the extra space)
 * instead of a bitmap scale.
 *
 * <p>All of the tab settings (channel / trigger / preset / utility /
 * save-load) live in the self-contained {@link ScopeTabControl}; the pane
 * implements its {@link ScopeTabControl.Host} so the tabs can ask it to
 * redraw, recompute the view window, stop capture before a file load, or open
 * the screenshot dialog without holding a reference to the whole pane.
 */
@Log4j2
public final class ScopePane implements ScopeTabControl.Host {

    /** Side length of the Record toggle (px). */
    public  static final int TOGGLE_BUTTON = 48;
    /** Pixel height of the big record-LED button at the top toolbar. */
    private static final int RECORD_LED_SIZE  = 33;
    /**
     * Minimum peak-to-peak signal amplitude (as a fraction of the ADC's
     * full-scale p-p) required for the calibrate button to be active.
     * Below this the signal occupies too little of the ADC's range and a
     * calibration based on it would be dominated by quantisation /
     * background noise.  Matches the "0.25 FS p-p" gate from the polish
     * spec.
     */
    private static final double CALIBRATE_MIN_VPP_FRACTION = 0.25;
    /** Vertical-offset wheel step size as a fraction of the channel's
     *  full vertical range — 5 % per wheel tick (≈ ½ a division). */
    private static final double WHEEL_OFFSET_STEP_FRAC = 0.05;

    private static final int SCROLLBAR_THICKNESS = 20;
    /** Slider resolution — fixed range; {@link #navSlider}'s selection
     *  is derived from {@link #viewCenterFrames} via the buffer's
     *  capacity.  Sized large so that arrow-click steps of 1/5 div and
     *  page-click steps of 5 divisions stay accurate at any practical
     *  zoom (1000 was too coarse — rounding visibly distorted the step
     *  at moderate zooms).  At one million units per slider, a single
     *  unit is sub-microsecond on most signals. */
    private static final int NAV_RANGE = 1_000_000;
    /** Minimum thumb size as a fraction of the slider's total range —
     *  keeps the thumb visually grabbable (~18-25 px at typical canvas
     *  sizes) even when the visible window is a tiny slice of a huge
     *  ring buffer.  3 % of NAV_RANGE = 30 000 units, ~18 px at a 600 px
     *  track, ~24 px at 800 px. */
    private static final int MIN_SCROLLBAR_THUMB = NAV_RANGE / 33;

    /**
     * Period between scope redraw ticks, in milliseconds.  20 ms (≈ 50 Hz)
     * is fast enough to read as smooth motion and leaves plenty of idle time
     * in the OS message queue between paints, so the FFT worker's
     * {@code display.asyncExec} handoff still gets dispatched even while the
     * scope is recording.  The previous 1 ms value flooded the message pump
     * with WM_TIMER + WM_PAINT pairs and starved every other async work item.
     */
    private static final int REDRAW_PERIOD_MS = 20;
    /**
     * Number of main-view redraws between condensed-view redraws.  The
     * condensed strip walks ~1 s of audio (lots of samples per pixel) and its
     * per-paint work would otherwise halve the main view's cap/s.  Updating it
     * at ~5 Hz is plenty for the human eye and keeps the main trace at full speed.
     */
    private static final int CONDENSED_DECIMATION = 10;

    // Cached references from IconUtils — owned by the shared cache and
    // disposed centrally when the main shell tears down.
    private final Image recordDim;
    private final Image recordLit;

    @Getter
    private final Composite              group;
    private PaneTitle                    title;
    @Getter
    private final ScopeView              view;
    @Getter
    private final ZoomedView             condensed;
    @Getter
    private final Composite              toolbar;
    @Getter
    private final Button                 recordButton;
    /** The self-contained tile-tab folder hosting every channel / trigger /
     *  preset / utility / save-load tab; see {@link ScopeTabControl}. */
    private ScopeTabControl              tabControl;

    /** Subscriber for {@link Events#SCOPE_AUTO_SETUP}.  Held as a field
     *  so the dispose listener can unsubscribe — bound only on the live
     *  pane so the offscreen screenshot-renderer doesn't respond to user
     *  clicks on the live pane.  {@code null} on the offscreen variant. */
    private Consumer<Void>               autoSetupListener;
    /** {@link Events#FREQRESP_MEASUREMENT_STARTED} subscriber — stops a
     *  running scope capture and disables the Record button so the
     *  Frequency Response sweep can use the device exclusively. */
    private Consumer<Void>               freqRespStartedListener;
    /** {@link Events#FREQRESP_MEASUREMENT_STOPPED} subscriber — re-enables
     *  the Record button once the sweep finishes. */
    private Consumer<Void>               freqRespStoppedListener;

    /** Horizontal navigation slider sitting above the condensed strip. */
    private FlatScrollbar                navSlider;
    /** Linux-only fixed-height Label that takes the navSlider's slot in
     *  record mode.  GTK honours heightHint on a Label but not on an
     *  invisible Canvas (FlatScrollbar), so the spacer keeps the gap
     *  above the condensed strip uniform with file mode.  Unused on
     *  Windows / macOS where the navSlider's own heightHint already
     *  works correctly. */
    private Label                        navSliderSpacer;
    /** Vertical scrollbar in the 20 px right gap.  Always visible.  Maps
     *  [0, NAV_RANGE] selection to the measurement channel's offset
     *  fraction in [0, 1] (top of grid → bottom of grid).  In live mode
     *  the horizontal navSlider is hidden — only this one stays active. */
    private FlatScrollbar                vertSlider;
    /**
     * Primary state for "where in the signal the view is centred".
     * Absolute (possibly fractional) frame index of the sample under
     * the canvas centre.  Sentinel: any negative value means "follow
     * latest" (used in live record mode).  The slider position and
     * {@link ScopeView#viewBackOffsetFrames} are derived from
     * this — changing t/div recomputes those without touching
     * {@code viewCenterFrames}, so the centred frame stays fixed
     * across zooms.  Kept as a {@code double} so repeated zooms don't
     * accumulate rounding error.
     */
    private double viewCenterFrames = -1.0;
    private final GridData               condensedGd;
    /** Controller owning the scope's capture-device handshake — the Record
     *  state ({@code isCapturing}), the live buffer reference and the
     *  ACQUIRE/RELEASE bus round-trip.  The pane wires the views around
     *  it: buffer attach + measurement thread + redraw timer on start,
     *  frozen-snapshot re-attach on stop.  Stays idle on the
     *  screenshot-only pane variant ({@code liveCapture = false}). */
    private final ScopeController controller = new ScopeController();
    /** Main-view redraws elapsed since the last condensed-view redraw. */
    private int                          redrawCounter;
    /**
     * Synchronous file loader for the "Open signal…" feature.  Owned by the
     * pane (cleared on dispose / record-start) and shared with the tab
     * control's Open-signal tab, which drives it.  {@code null} on the
     * screenshot-only variant.
     */
    private final ScopeOpenSignal        loader;
    /** Collapse state + per-child snapshot.  See {@link #setCollapsed(boolean)}. */
    @Getter
    private boolean    collapsed;
    private boolean[]  preCollapseChildVisible;
    private boolean[]  preCollapseChildExclude;

    /**
     * Constructs the pane and all its children.
     * @param parent       host composite for the pane's Group.
     * @param liveCapture  when {@code true}, wires the Record toggle to the
     *                     pane's own capture lifecycle (showing an error
     *                     MessageBox on capture-open failure) and stops it on
     *                     Group dispose.  The screenshot pane variant
     *                     passes {@code false} — its camera / calibrate
     *                     buttons still exist for layout fidelity but are
     *                     never clicked.
     */
    public ScopePane(Composite parent, boolean liveCapture) {
        IconUtils icons = IconUtils.instance();
        Display d = parent.getDisplay();
        this.recordDim      = icons.createRecordLed(d, 200,  40,  40, false, RECORD_LED_SIZE);
        this.recordLit      = icons.createRecordLed(d, 255,   0,   0, true,  RECORD_LED_SIZE);
        // Composite + SWT.BORDER instead of Group — on GTK the Group's
        // GtkFrame label widget consumes title-bar clicks, breaking the
        // collapse UX.  Visual frame is preserved by SWT.BORDER.
        group = new Composite(parent, SWT.BORDER);
        group.setLayout(paneLayout());
        // Clickable Label inside the content area replaces the Group's
        // native chrome title — GTK's GtkFrame label widget consumes
        // clicks on the chrome title before any SWT listener (or even
        // Display.addFilter) can see them.
        title = new PaneTitle(group, Events.PANE_ID_SCOPE,
                I18n.t("scope.title.expanded"),
                I18n.t("scope.title.collapsed"),
                I18n.t("scope.pane.toggle.tooltip"));

        view = new ScopeView(group);
        // Column 0 of the 2-col paneLayout — leaves column 1 free for the
        // vertical scrollbar that follows.
        view.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        // ----- Right-gap vertical scrollbar (column 1 of the same row) -----
        vertSlider = new FlatScrollbar(group, SWT.VERTICAL);
        vertSlider.setMinimum(0);
        vertSlider.setMaximum(NAV_RANGE);
        vertSlider.setThumb(Math.max(1, NAV_RANGE / 10));
        vertSlider.setIncrement(NAV_RANGE / 100);
        vertSlider.setPageIncrement(NAV_RANGE / 10);
        vertSlider.setToolTipText(I18n.t("scope.vertSlider.tooltip"));
        GridData vsGd = new GridData(SWT.FILL, SWT.FILL, false, true);
        vsGd.widthHint = SCROLLBAR_THICKNESS;
        vertSlider.setLayoutData(vsGd);
        vertSlider.addListener(SWT.Selection, e -> onVertSliderMoved());
        // Initial selection from the prefs.
        syncVertSliderFromPrefs();

        // Navigation slider between the main view and the condensed
        // strip.  Selection = how far back from the live writePos the
        // scope view should anchor.  Rightmost = follow latest.  Hidden
        // in live record mode; visible & scrubbing in file mode.
        navSlider = new FlatScrollbar(group, SWT.HORIZONTAL);
        navSlider.setMinimum(0);
        navSlider.setMaximum(NAV_RANGE);
        navSlider.setThumb(Math.max(1, NAV_RANGE / 20));
        navSlider.setSelection(NAV_RANGE);
        navSlider.setToolTipText(I18n.t("scope.navSlider.tooltip"));
        // Span both columns so the slider sits below the canvas AND the
        // vertical-scrollbar gutter, removing the need for a column-1
        // spacer Composite (GTK gives empty Composites a non-zero
        // intrinsic height that disrespects heightHint and inflates the
        // gap above the condensed strip).
        GridData navGd = new GridData(SWT.FILL, SWT.FILL, true, false);
        navGd.heightHint     = SCROLLBAR_THICKNESS;
        navGd.horizontalSpan = 2;
        // Initial state on Linux: collapse the FlatScrollbar's row and
        // show a Label spacer in its place — GTK honours heightHint on
        // Labels but inflates the row when given an invisible Canvas.
        // On Windows / macOS the navSlider's own heightHint is honoured,
        // so no spacer is needed and exclude stays false.
        boolean linux = System.getProperty("os.name", "").toLowerCase().contains("linux");
        navGd.exclude = linux;
        navSlider.setLayoutData(navGd);
        navSlider.setVisible(false);
        navSlider.addListener(SWT.Selection, e -> onNavSliderMoved());

        if (linux) {
            navSliderSpacer = new Label(group, SWT.NONE);
            GridData spacerGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
            spacerGd.heightHint     = SCROLLBAR_THICKNESS;
            spacerGd.horizontalSpan = 2;
            // Initially visible (record mode) — toggled inside
            // setNavSliderVisible() when a file is loaded.
            navSliderSpacer.setLayoutData(spacerGd);
        }

        // Condensed overview strip just above the toolbar.  Its heightHint is
        // recomputed on every pane resize so the strip stays roughly 1.2 of
        // 11.2 divisions tall (the area above the toolbar).  Also spans both
        // columns for the same reason as the nav slider above.
        condensed = new ZoomedView(group);
        condensedGd = new GridData(SWT.FILL, SWT.FILL, true, false);
        condensedGd.horizontalSpan = 2;
        condensed.setLayoutData(condensedGd);

        // Toolbar area: the ScopeTabControl on the left (fills) + Record toggle
        // button on the right (fixed).  Each former toolbar Group is a tab
        // inside the control's folder; only one is visible at a time so the
        // pane stays compact on narrow windows.
        toolbar = new Composite(group, SWT.NONE);
        GridData toolbarGd = new GridData(SWT.FILL, SWT.END, true, false);
        toolbarGd.horizontalSpan = 2;
        toolbar.setLayoutData(toolbarGd);
        GridLayout toolbarLayout = new GridLayout(2, false);
        toolbarLayout.marginWidth   = 0;
        toolbarLayout.marginHeight  = 0;
        toolbarLayout.horizontalSpacing = 6;
        toolbar.setLayout(toolbarLayout);

        // The synchronous file loader is shared with the tab control's
        // Open-signal tab (which drives it) and the Record button (which
        // clears it on record start), so it must exist before the tab control.
        loader = liveCapture ? new ScopeOpenSignal(view, condensed) : null;

        // The tile-tab folder + every channel / trigger / preset / utility /
        // save-load tab live in a self-contained control; the pane keeps only
        // the chart, sliders and Record button.  Cross-boundary concerns are
        // routed back here through ScopeTabControl.Host (implemented below).
        tabControl = new ScopeTabControl(toolbar, view, loader, this, liveCapture);
        tabControl.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        // Collapsing the tab body keeps the Record button reachable and
        // re-flows the scope view into the freed space.
        tabControl.setCollapseRelayout(this::onToolbarTabsCollapsed);

        // Flush layout caches up the parent chain so the toolbar's
        // GridLayout picks up the new (taller) tab-folder preferred size.
        toolbar.layout(true, true);
        group.layout(true, true);

        recordButton = addToggleButton(toolbar, recordDim, recordLit);
        recordButton.setToolTipText(I18n.t("scope.record.tooltip"));
        // addToggleButton sets grabExcessHorizontalSpace=true to push
        // the record LED to the far right of an old single-row toolbar.
        // In the new two-cell layout the tab control already grabs the
        // horizontal slack — clearing the flag and pinning to SWT.END
        // keeps Record button width fixed and right-anchored.
        Object recGd = recordButton.getLayoutData();
        if (recGd instanceof GridData rgd) {
            rgd.grabExcessHorizontalSpace = false;
            rgd.horizontalAlignment       = SWT.END;
            // Anchor to the top of the toolbar row so the Record button sits
            // at the tab-strip level — stays clickable when the tab content
            // is collapsed (and visually nestled next to the tab strip).
            rgd.verticalAlignment         = SWT.BEGINNING;
        }

        // The wheel handler steps the tab control's scale selectors, so it is
        // installed after the tab control exists.
        installScopeViewWheelHandler();

        if (liveCapture) {
            wireLiveCaptureLifecycle();
        }

        // Keep the Utility-tab calibrate button's gate honest as recording
        // starts / stops, the file-mode flag flips, and the measured Vpp
        // moves above / below the 25 % threshold.
        scheduleCalibrateButtonRefresh();
        installCondensedAutoResize();
        wireHelpAnchors();
    }

    /** Live-pane capture wiring: Record button, Auto-Setup bus subscription,
     *  and a dispose listener that unsubscribes and stops live capture.
     *  Called only when {@code liveCapture} is true — the
     *  screenshot pane mirrors the Record-button visual state externally
     *  via {@link #setRecordingState} but never opens an audio device of
     *  its own.  The "Reconstructed beat" generator-form subscription lives
     *  inside {@link ScopeTabControl} (the checkbox is its widget). */
    private void wireLiveCaptureLifecycle() {
        wireRecordButton();
        autoSetupListener        = ignored -> performAutoSetup();
        freqRespStartedListener  = ignored -> onFreqRespMeasurementStarted();
        freqRespStoppedListener  = ignored -> onFreqRespMeasurementStopped();
        MessageBus bus = MessageBus.instance();
        bus.subscribe(Events.SCOPE_AUTO_SETUP,             autoSetupListener);
        bus.subscribe(Events.FREQRESP_MEASUREMENT_STARTED, freqRespStartedListener);
        bus.subscribe(Events.FREQRESP_MEASUREMENT_STOPPED, freqRespStoppedListener);
        group.addDisposeListener(e -> {
            MessageBus bus2 = MessageBus.instance();
            bus2.unsubscribe(Events.SCOPE_AUTO_SETUP,             autoSetupListener);
            bus2.unsubscribe(Events.FREQRESP_MEASUREMENT_STARTED, freqRespStartedListener);
            bus2.unsubscribe(Events.FREQRESP_MEASUREMENT_STOPPED, freqRespStoppedListener);
            loader.clear();
            stopCapture();
        });
    }

    /** Stops a running capture and grays the Record button — fired by the
     *  Frequency Response pane via {@link Events#FREQRESP_MEASUREMENT_STARTED}
     *  so the FreqResp analyzer can take exclusive control of the device. */
    private void onFreqRespMeasurementStarted() {
        if (recordButton == null || recordButton.isDisposed()) return;
        if (recordButton.getSelection()) {
            recordButton.setSelection(false);
            stopCapture();
            recordButton.setImage(recordDim);
        }
        recordButton.setEnabled(false);
    }

    /** Counterpart that re-enables the Record button after the sweep
     *  finishes (or aborts). */
    private void onFreqRespMeasurementStopped() {
        if (recordButton == null || recordButton.isDisposed()) return;
        recordButton.setEnabled(true);
    }

    /** Auto-sizes the condensed strip on every Group resize.  Target
     *  height is 0.9 of one main-scope division (main has 10 divs):
     *  condensed / (main + condensed) = 0.9 / 10.9. */
    private void installCondensedAutoResize() {
        group.addControlListener(ControlListener.controlResizedAdapter(e -> {
            int paneH    = group.getSize().y;
            int toolbarH = toolbar.getSize().y;
            int avail    = Math.max(0, paneH - toolbarH);
            int desired  = Math.max(15, (int) Math.round(avail * 0.9 / 10.9));
            if (condensedGd.heightHint != desired) {
                condensedGd.heightHint = desired;
                group.layout(true, true);
            }
        }));
    }

    /** Tags the pane-level chrome (the group + Record button) with a
     *  {@code "helpAnchor"} so Ctrl+F1 can resolve the focused control to a
     *  section of {@code oscilloscope.html}.  The tab widgets anchor
     *  themselves inside {@link ScopeTabControl}; this chapter-level anchor
     *  is the focus-walk fallback. */
    private void wireHelpAnchors() {
        group.setData("helpAnchor", "oscilloscope.html");
        if (recordButton != null) recordButton.setData("helpAnchor", "oscilloscope.html");
    }

    /**
     * Wires the Record toggle to the pane's capture lifecycle: pressing it
     * starts capture; on failure the button snaps back to off and a MessageBox
     * shows the human-readable reason from {@link #getLastStartError()}.
     */
    private void wireRecordButton() {
        recordButton.addListener(SWT.Selection, e -> {
            if (recordButton.getSelection()) {
                // Starting a fresh recording — wipe any loaded
                // openSignal file so the live capture buffer takes over.
                if (loader != null && loader.isLoaded()) {
                    loader.clear();
                    tabControl.clearOpenSignalPath();
                    view.setFilePath(null);
                }
                // Live record: trace follows latest, so the user can't
                // scrub the buffer (it would freeze the trace and break
                // trigger).  Disable the slider and lock the centre
                // state to "follow latest".
                viewCenterFrames = -1.0;
                if (navSlider != null && !navSlider.isDisposed()) {
                    navSlider.setSelection(navSlider.getMaximum() - navSlider.getThumb());
                }
                setNavSliderVisible(false);
                // Re-enable the trigger group — it applies again to a live
                // capture (the control re-applies the "Start iff Single" rule).
                tabControl.setTriggerControlsEnabled(true);
                view.setViewBackOffsetFrames(0);
                condensed.setViewBackOffsetFrames(0);
                startCapture();
                if (!isCapturing()) {
                    recordButton.setSelection(false);
                    recordButton.setImage(recordDim);
                    String err = getLastStartError();
                    if (err != null) {
                        Dialogs.error(group.getShell(), I18n.t("scope.record.error.start"), err);
                    }
                }
            } else {
                stopCapture();
            }
            refreshCalibrateButtonEnabled();
        });
    }

    /**
     * Visually mirrors a foreign Record-button state on this pane without
     * driving the controller — used by the screenshot pane so its rendering
     * shows the same Record-LED state the user sees on the live pane.
     */
    public void setRecordingState(boolean recording) {
        recordButton.setSelection(recording);
        recordButton.setImage(recording ? recordLit : recordDim);
        tabControl.setCalibrateEnabled(recording);
    }

    /** Forces the toolbar tab body collapsed (only headers visible) or
     *  expanded.  Used by the screenshot renderer to show just the tab
     *  headers; delegates to {@link ScopeTabControl}. */
    public void setToolbarTabsCollapsed(boolean wantCollapsed) {
        if (tabControl != null) tabControl.setTabsCollapsed(wantCollapsed);
    }

    /** Re-flow after the tab body collapses / expands: keep the Record button
     *  anchored to the top of the toolbar row so it stays reachable, then
     *  cascade the layout toolbar → group so the scope view reclaims / yields
     *  the freed vertical space.  The control has already applied the
     *  strip-only height to the folder's layout data. */
    private void onToolbarTabsCollapsed() {
        if (recordButton != null && !recordButton.isDisposed()) {
            Object rd = recordButton.getLayoutData();
            if (rd instanceof GridData rgd) {
                rgd.exclude = false;
                rgd.verticalAlignment = SWT.BEGINNING;
            }
            recordButton.setVisible(true);
        }
        if (toolbar != null && !toolbar.isDisposed()) toolbar.layout(true, true);
        if (group   != null && !group.isDisposed())   group.layout(true, true);
    }

    // -------------------------------------------------------------------------
    // Pane-owned dialogs + ScopeTabControl.Host
    // -------------------------------------------------------------------------

    /** {@link ScopeTabControl.Host}: opens the screenshot dialog for this
     *  pane (the Utility-tab camera button asks for it).  Initial
     *  width/height come from preferences (last-used) and fall back to the
     *  pane's current pixel size; the chosen size + folder are persisted
     *  back on Copy or Save. */
    @Override
    public void openScreenshot() {
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

    /** {@link ScopeTabControl.Host}: stop live capture before an open-signal
     *  load swaps the buffer out from under it.  No-op when not recording. */
    @Override
    public void stopCaptureForFileLoad() {
        if (isCapturing()) {
            stopCapture();
            recordButton.setSelection(false);
            recordButton.setImage(recordDim);
        }
    }

    /** {@link ScopeTabControl.Host}: a signal file finished loading — centre
     *  the view on its start, show the navigation slider (file mode) and
     *  apply the view state. */
    @Override
    public void onSignalFileLoaded() {
        // Centre the view on the start of the loaded signal so the first
        // frames are visible.  Slider is enabled only in file mode — in live
        // record it's disabled so the user can't scroll away from the live
        // tip and break trigger.
        SignalBufferReader reader = view.getReader();
        if (reader != null) {
            int displaySamples = ScopeFormat.displaySamplesFor(
                    Preferences.instance().getOscTimePerDiv(), reader.getSampleRate());
            viewCenterFrames = displaySamples / 2.0;
        }
        setNavSliderVisible(true);
        applyViewState();
    }

    /** Renders this pane into a fresh image at {@code (targetW, targetH)}
     *  by constructing a brand-new {@link ScopePane} inside a
     *  hidden offscreen Shell, copying the live buffer and measurement
     *  state into it, and printing the result.  Because the chrome is
     *  laid out by SWT (rather than bitmap-scaled), toolbar buttons stay
     *  at their native pixel size with extra space distributed by the
     *  layout. */
    private Image renderOffscreen(Display d, int targetW, int targetH) {
        targetW = Math.max(1, targetW);
        targetH = Math.max(1, targetH);
        // Hidden Shell sized to the target.  On GTK: setSize BEFORE
        // setLocation — calling setLocation before the shell has a real
        // size causes some WMs to ignore the negative offset and place
        // it at (0,0) anyway.
        Shell offscreen = new Shell(d, SWT.NO_TRIM);
        offscreen.setLayout(new FillLayout());
        ScopePane shotPane = new ScopePane(offscreen, false);
        Image output = new Image(d, targetW, targetH);
        try {
            if (view != null && !view.isDisposed()) {
                // Carbon-copy the exact frame on screen (works while stopped /
                // frozen).  The frozen frame drives the trace; the buffer is
                // still attached so "has signal" is true and the measurement
                // table renders (its values come from copyMeasurementsFrom).
                ScopeView.RenderedFrame snap = view.getRenderedFrameSnapshot();
                if (snap != null) shotPane.getView().setFrozenFrame(snap);
                SignalBufferReader reader = view.getReader();
                if (reader != null) {
                    shotPane.getView().setBuffer(reader);
                    shotPane.getCondensed().setBuffer(reader);
                }
                // copyMeasurementsFrom AFTER setBuffer (which clears latest).
                shotPane.getView().copyMeasurementsFrom(view);
            }
            // Show only the tab headers in the screenshot (collapse the
            // settings body), matching the FFT pane's screenshot behaviour.
            shotPane.setToolbarTabsCollapsed(true);
            shotPane.setRecordingState(isCapturing());

            offscreen.setSize(targetW, targetH);
            offscreen.setLocation(-10000, -10000);
            offscreen.open();
            while (d.readAndDispatch()) { /* drain */ }
            shotPane.getGroup().update();

            GC outGc = new GC(output);
            try {
                shotPane.getGroup().print(outGc);
            } finally {
                outGc.dispose();
            }
            offscreen.setVisible(false);
        } finally {
            offscreen.dispose();
        }
        return output;
    }

    /** Hides / shows every child except the title Label so the pane can
     *  collapse to its title bar (or restore).  Snapshots each child's
     *  pre-collapse {@code visible} / {@code GridData.exclude} on the way
     *  down so per-child state (e.g. the navSlider's mutually-exclusive
     *  height spacer) survives the round-trip.  Pure pane-internal — the
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

    /**
     * Auto-setup: picks a t/div that fits ~1.5 periods on screen and a V/div
     * that makes the signal span ~0.75 of the vertical range, then resets
     * all offsets and the trigger level to centre.  Same V/div is applied
     * to both channels (driven by the measurement channel's Vpp).  Wired
     * to the Auto-Setup button in {@link ScopeView}'s header via
     * {@link Events#SCOPE_AUTO_SETUP}.
     *
     * <p>No-op when the measurement worker has not published a frequency
     * or Vpp yet (capture not running, or the warm-up window is still
     * in effect).
     */
    private void performAutoSetup() {
        if (view == null || view.isDisposed()) return;
        // Refuse to auto-setup when the scope isn't actively recording.
        // Otherwise the requestRedraw() at the end of this method pulls
        // whatever the shared SignalBuffer happens to hold (e.g. when
        // the FFT pane is recording and the audio device is open) and
        // paints it into the scope view — which looks like the scope
        // captured a signal even though the user never pressed Record.
        if (!isCapturing()) return;
        Preferences prefs = Preferences.instance();
        double freq = view.getLastFrequencyHz();
        double vpp  = view.getLastVpp();
        // In dual-tone mode the carrier crosses 0 many times per beat
        // envelope cycle.  Auto-setup picks the LOWER of the carrier
        // frequency and |F1-F2| so the time scale covers at least one
        // full beat envelope — picking the carrier alone would render
        // a packed wall of cycles with no visible envelope.
        double scaleHz = freq;
        if (prefs.getGenSignalForm() == GenSignalForm.DUAL_TONE) {
            double beatHz = Math.abs(prefs.getGenDualToneFreq2Hz()
                                   - prefs.getGenDualToneFreq1Hz());
            if (beatHz > 0 && (!Double.isFinite(scaleHz) || beatHz < scaleHz)) {
                scaleHz = beatHz;
            }
        }
        if (Double.isFinite(scaleHz) && scaleHz > 0) {
            double period = 1.0 / scaleHz;
            double targetTDiv = period * 1.5 / ScopeView.DIVISIONS_X;
            double newTDiv    = ScopeFormat.ceilToStep(targetTDiv, OscParse.timePerDivTargets());
            tabControl.setTimePerDiv(newTDiv);
        }
        if (Double.isFinite(vpp) && vpp > 0) {
            double targetVDiv = vpp / (ScopeView.DIVISIONS_Y * 0.75);
            double newVDiv    = ScopeFormat.ceilToStep(targetVDiv, OscParse.voltsPerDivTargets());
            tabControl.setLeftVoltsPerDiv(newVDiv);
            tabControl.setRightVoltsPerDiv(newVDiv);
        }
        // Reset all offsets and the trigger level to "0" (frac = 0.5 = centre).
        prefs.setOscLeftOffsetFrac     (0.5);
        prefs.setOscRightOffsetFrac    (0.5);
        prefs.setOscTriggerPositionFrac(0.5);
        prefs.setOscTriggerLevelFrac   (0.5);
        prefs.save();
        requestRedraw();
    }

    /**
     * Slider-moved handler.  In live record mode the slider is disabled
     * (so this should only fire from programmatic syncs we trigger
     * ourselves — those are ignored because they don't move the user's
     * intended centre).  In file mode the user is actively scrubbing;
     * translate the slider position into a {@link #viewCenterFrames}
     * value and let {@link #applyViewState} push everything else.
     */
    private void onNavSliderMoved() {
        if (!view.isFileMode()) return;     // live mode: ignore stray events
        SignalBufferReader reader = view.getReader();
        if (reader == null) return;
        int displaySamples = ScopeFormat.displaySamplesFor(Preferences.instance().getOscTimePerDiv(), reader.getSampleRate());
        long writePos = reader.getWritePos();
        long oldest   = Math.max(0L, writePos - reader.getCapacity());
        long minCenter = oldest   + displaySamples / 2;
        long maxCenter = writePos - displaySamples / 2;
        int sel    = navSlider.getSelection();
        int maxSel = navSlider.getMaximum() - navSlider.getThumb();
        if (maxSel <= 0 || maxCenter < minCenter) {
            // No scroll room — view is showing all of the resident data.
            viewCenterFrames = (writePos + oldest) / 2.0;
        } else {
            double frac = sel / (double) maxSel;
            // Double-precise — sub-frame fraction is preserved so
            // subsequent zooms don't drift.
            viewCenterFrames = minCenter + frac * (maxCenter - minCenter);
        }
        applyViewState();
    }

    /**
     * {@link ScopeTabControl.Host}: single source of truth for everything
     * that depends on the view's centre.  Reads {@link #viewCenterFrames}
     * (the absolute frame under the canvas centre — {@code -1} for
     * follow-latest) and derives slider thumb size, slider position, the
     * main view's back-offset and the condensed view's back-offset.  Always
     * ends with a redraw.
     */
    @Override
    public void applyViewState() {
        SignalBufferReader reader = view.getReader();
        if (reader == null) {
            view.setViewBackOffsetFrames(0);
            condensed.setViewBackOffsetFrames(0);
            requestRedraw();
            return;
        }
        int displaySamples = ScopeFormat.displaySamplesFor(Preferences.instance().getOscTimePerDiv(), reader.getSampleRate());
        long writePos = reader.getWritePos();
        long resident = Math.min(writePos, reader.getCapacity());

        // Thumb size = fraction of the buffer the main view is showing.
        // Floor at MIN_SCROLLBAR_THUMB so the thumb stays grabbable even
        // when the visible window is a tiny slice of a huge buffer.
        int thumb;
        if (resident <= 0) {
            thumb = NAV_RANGE;
        } else {
            double frac = Math.min(1.0, displaySamples / (double) resident);
            thumb = Math.max(MIN_SCROLLBAR_THUMB,
                    Math.min(NAV_RANGE, (int) Math.round(frac * NAV_RANGE)));
        }
        if (navSlider.getThumb() != thumb) navSlider.setThumb(thumb);
        int maxSel = navSlider.getMaximum() - thumb;

        // Valid centre range — left edge can't go below 0, right edge
        // can't go past writePos (no future frames exist).  In the
        // live ring-wrap case the oldest still-resident frame is
        // writePos − capacity.
        long oldest    = Math.max(0L, writePos - reader.getCapacity());
        long minCenter = oldest   + displaySamples / 2;
        long maxCenter = writePos - displaySamples / 2;

        // Arrow click moves 1/5 of a division, page click (empty area
        // of the scrollbar track) moves 5 divisions — both at the
        // current t/div.  displaySamples = 10 × t/div, so 1/5 div =
        // displaySamples/50 frames and 5 div = displaySamples/2 frames.
        // Convert frames to slider-units via the centre-range / max-sel
        // ratio.
        long centerRange = Math.max(0L, maxCenter - minCenter);
        if (centerRange > 0 && maxSel > 0) {
            double unitsPerFrame = (double) maxSel / centerRange;
            int arrowStep = Math.max(1, (int) Math.round((displaySamples / 50.0) * unitsPerFrame));
            int pageStep  = Math.max(1, (int) Math.round((displaySamples /  2.0) * unitsPerFrame));
            if (navSlider.getIncrement()     != arrowStep) navSlider.setIncrement(arrowStep);
            if (navSlider.getPageIncrement() != pageStep)  navSlider.setPageIncrement(pageStep);
        }

        long mainOffset;
        if (viewCenterFrames < 0 || maxCenter < minCenter) {
            // Follow latest: view ends at writePos, slider pinned to rightmost.
            mainOffset = 0;
            if (maxSel > 0) navSlider.setSelection(maxSel);
        } else {
            double clampedCenter = Math.max((double) minCenter,
                                            Math.min((double) maxCenter, viewCenterFrames));
            // Round once at the boundary to integer frames for the
            // back-offset math; viewCenterFrames itself keeps its
            // double precision so repeated zooms don't drift.
            long viewEndAbs = (long) Math.round(clampedCenter + displaySamples / 2.0);
            mainOffset      = Math.max(0L, writePos - viewEndAbs);
            // Sync slider position to the (possibly clamped) centre.
            if (maxSel > 0) {
                double range      = Math.max(1.0, (double) (maxCenter - minCenter));
                double centerFrac = (clampedCenter - minCenter) / range;
                int newSel = (int) Math.round(centerFrac * maxSel);
                newSel = Math.max(0, Math.min(maxSel, newSel));
                if (navSlider.getSelection() != newSel) navSlider.setSelection(newSel);
            }
        }
        view.setViewBackOffsetFrames(mainOffset);

        // Condensed view: 1 s window centred on the same frame as the
        // main view's middle.  Clamped so we don't request frames past
        // writePos or before any resident sample.
        long mainCenter       = writePos - mainOffset - displaySamples / 2;
        long condensedViewEnd = mainCenter + reader.getSampleRate() / 2;
        condensedViewEnd = Math.min(condensedViewEnd, writePos);
        condensedViewEnd = Math.max(condensedViewEnd, Math.min(writePos, oldest + reader.getSampleRate()));
        condensed.setViewBackOffsetFrames(Math.max(0L, writePos - condensedViewEnd));
        requestRedraw();
    }

    /**
     * {@link ScopeTabControl.Host}: forces both scope canvases to repaint.
     * Required for file-mode (openSignal) sessions where there's no periodic
     * capture redraw timer to pick up UI changes (V/div, t/div, slider, AC,
     * channel toggles).  Cheap no-op during live recording — the controller's
     * 1 ms redraw timer would have repainted on its own next tick.
     */
    @Override
    public void requestRedraw() {
        // Keep the vertical scrollbar in sync with the measurement-channel
        // offset on every redraw — covers cases where the channel was
        // changed inside ScopeView (L/R click) without the pane explicitly
        // seeing it.
        syncVertSliderFromPrefs();
        if (!view.isDisposed())      view.redraw();
        if (!condensed.isDisposed()) condensed.redraw();
    }

    /** Wires the four-mode mouse-wheel handler on the scope canvas.
     *  Matched to the mental model of a hardware scope's "position" /
     *  "scale" knobs:
     *  <ul>
     *    <li>plain wheel        → vertical offset (everything in sync)</li>
     *    <li>Shift + wheel      → horizontal offset (time scroll / trig pos)</li>
     *    <li>Ctrl  + wheel      → vertical scale (V/div) on both channels</li>
     *    <li>Shift + Ctrl + wheel → horizontal scale (t/div)</li>
     *  </ul>
     *  The two scale branches step the tab control's V/T selectors; the two
     *  offset branches move pane-owned navigation state. */
    private void installScopeViewWheelHandler() {
        view.addListener(SWT.MouseVerticalWheel, e -> {
            if (e.count == 0) return;
            int dir = Integer.signum(e.count);   // wheel up = +1
            boolean ctrl  = (e.stateMask & SWT.CTRL)  != 0;
            boolean shift = (e.stateMask & SWT.SHIFT) != 0;
            Rectangle area = view.getClientArea();
            if (ctrl && shift) {
                // Shift + Ctrl + wheel: t/div zoom around the mouse X
                // (wheel up → step DOWN in t/div since smaller t/div is
                // finer time resolution).
                tabControl.stepTimePerDivAround(-dir, e.x, area.width);
            } else if (ctrl) {
                // Ctrl + wheel: V/div zoom around the mouse Y (wheel up →
                // step DOWN in V/div).
                tabControl.stepVoltsPerDivAround(-dir, e.y, area.height);
            } else if (shift) {
                // Shift + wheel: horizontal offset.
                stepHorizontalOffset(dir);
            } else {
                // Plain wheel: vertical offset (everything).
                stepMeasurementChannelOffset(dir);
            }
            e.doit = false;
        });
    }

    /** Nudges both channel offsets by one wheel tick.  Wheel up (dir = +1)
     *  moves the signal UP on screen, which means the offset fraction
     *  DECREASES (lower offsetFrac = trace anchored higher on the grid;
     *  see the {@code maxV / minV} math in
     *  {@link ScopeView#drawEdgeLabels}).  Trigger level stays put
     *  — moving it together with the channels was unintuitive (trigger
     *  marker drifting with the trace rather than the user's intent). */
    private void stepMeasurementChannelOffset(int dir) {
        Preferences prefs = Preferences.instance();
        double delta = -dir * WHEEL_OFFSET_STEP_FRAC;
        prefs.setOscLeftOffsetFrac  (prefs.getOscLeftOffsetFrac()  + delta);
        prefs.setOscRightOffsetFrac (prefs.getOscRightOffsetFrac() + delta);
        prefs.save();
        syncVertSliderFromPrefs();
        requestRedraw();
    }

    /** Shifts the horizontal view position by one wheel tick.  In file mode
     *  this scrolls {@link #viewCenterFrames}; in live record mode it nudges
     *  the trigger position fraction (which is the only horizontal-offset
     *  knob meaningful while the trace tracks the latest writePos).
     *  {@code viewCenterFrames} is clamped to the buffer's valid centre
     *  range on every step so it can't drift past the limits — otherwise
     *  scrolling past either end would silently stash an out-of-range
     *  value and the next several wheel ticks would appear to "do nothing"
     *  while the value walks back into range. */
    private void stepHorizontalOffset(int dir) {
        Preferences prefs = Preferences.instance();
        if (view.isFileMode()) {
            SignalBufferReader reader = view.getReader();
            if (reader == null) return;
            int displaySamples = ScopeFormat.displaySamplesFor(
                    prefs.getOscTimePerDiv(), reader.getSampleRate());
            long writePos  = reader.getWritePos();
            long oldest    = Math.max(0L, writePos - reader.getCapacity());
            double minCenter = oldest   + displaySamples / 2.0;
            double maxCenter = writePos - displaySamples / 2.0;
            if (maxCenter < minCenter) return;   // window bigger than buffer
            // 1/5 of the visible window per tick — matches the nav slider's
            // small-step convention.  Wheel up = move backward in time.
            double stepFrames = displaySamples / 5.0;
            double cur = (viewCenterFrames < 0) ? maxCenter : viewCenterFrames;
            double next = cur - dir * stepFrames;
            if (next < minCenter) next = minCenter;
            if (next > maxCenter) next = maxCenter;
            if (next == viewCenterFrames) return;
            viewCenterFrames = next;
            applyViewState();
        } else {
            double cur = prefs.getOscTriggerPositionFrac();
            double next = ScopeFormat.clamp01(cur + dir * WHEEL_OFFSET_STEP_FRAC);
            if (next == cur) return;
            prefs.setOscTriggerPositionFrac(next);
            prefs.save();
            requestRedraw();
        }
    }

    /** Shows or hides the horizontal navigation slider.
     *  <p>On <strong>Linux</strong> the row swaps between the
     *  {@link FlatScrollbar} (file mode) and a fixed-height {@link Label}
     *  spacer (record mode) — GTK honours heightHint on Labels but
     *  inflates an invisible Canvas's row beyond the hint.  Either way
     *  the gap above the condensed strip stays at SCROLLBAR_THICKNESS.
     *  <p>On <strong>Windows / macOS</strong> the scrollbar's own
     *  heightHint is honoured directly, so no spacer is needed. */
    private void setNavSliderVisible(boolean visible) {
        if (navSlider == null || navSlider.isDisposed()) return;
        if (navSlider.getVisible() == visible) return;
        navSlider.setVisible(visible);
        if (navSliderSpacer != null && !navSliderSpacer.isDisposed()) {
            // Linux path: swap which child has exclude=false.
            ((GridData) navSlider.getLayoutData()).exclude        = !visible;
            ((GridData) navSliderSpacer.getLayoutData()).exclude  =  visible;
            group.layout(true, true);
        }
    }

    /** V/div of the measurement channel, falling back to the only-visible
     *  channel if the measurement channel is disabled. */
    private double measurementChannelVPDiv() {
        Preferences prefs = Preferences.instance();
        Channel selected = prefs.getOscMeasurementChannel();
        boolean showL = prefs.isOscLeftChannelEnabled();
        boolean showR = prefs.isOscRightChannelEnabled();
        if (selected == Channel.R && showR) return prefs.getOscRightVoltsPerDiv();
        if (selected == Channel.L && showL) return prefs.getOscLeftVoltsPerDiv();
        if (showR)                                  return prefs.getOscRightVoltsPerDiv();
        return prefs.getOscLeftVoltsPerDiv();
    }

    /** Returns the {@code [minOffsetFrac, maxOffsetFrac]} the vertical
     *  scrollbar should cover.  Extended past the usual [0, 1] so the
     *  trace can move all the way until either ±FS is at the grid centre.
     *  At wide V/div (the entire ±FS span fits on screen) the range
     *  collapses back to exactly [0, 1] — there's nothing to scroll. */
    private double[] offsetFracBounds() {
        double vpdiv = measurementChannelVPDiv();
        double fs    = Preferences.instance().getAdcFsVoltageRms() * Math.sqrt(2.0);
        double half  = (vpdiv <= 0 || fs <= 0)
                ? 0.5
                : fs / (ScopeView.DIVISIONS_Y * vpdiv);
        if (half < 0.5) half = 0.5;
        return new double[] { 0.5 - half, 0.5 + half };
    }

    /** Maps the vertical slider's selection to an offsetFrac inside the
     *  scale-dependent bounds returned by {@link #offsetFracBounds}, then
     *  slides BOTH channel offsets by the same delta.  Trigger level is
     *  not moved — its position is the user's intent, not a side-effect
     *  of trace panning.  Thumb at TOP = signal up (low offsetFrac =
     *  trace anchored toward top of grid); thumb at BOTTOM = signal down. */
    private void onVertSliderMoved() {
        if (vertSlider == null || vertSlider.isDisposed()) return;
        Preferences prefs = Preferences.instance();
        int sel = vertSlider.getSelection();
        int maxSel = vertSlider.getMaximum() - vertSlider.getThumb();
        double[] bounds = offsetFracBounds();
        double lo = bounds[0], hi = bounds[1];
        double frac = (maxSel <= 0) ? (lo + hi) / 2.0
                                    : lo + (sel / (double) maxSel) * (hi - lo);
        Channel ref = prefs.getOscMeasurementChannel();
        double prevRef = (ref == Channel.L)
                ? prefs.getOscLeftOffsetFrac()
                : prefs.getOscRightOffsetFrac();
        if (prevRef == frac) return;
        double delta = frac - prevRef;
        // No clampFrac here — offsetFrac is intentionally allowed past
        // [0, 1] so the user can place ±FS at grid centre.  Rendering
        // honours the extended range (see drawTrace centerY math).
        prefs.setOscLeftOffsetFrac  (prefs.getOscLeftOffsetFrac()  + delta);
        prefs.setOscRightOffsetFrac (prefs.getOscRightOffsetFrac() + delta);
        prefs.save();
        requestRedraw();
    }

    /** Reads the current measurement-channel offset from prefs and pushes
     *  the slider's thumb size + selection so it lines up with the new
     *  scale-dependent bounds.  Thumb fraction = visible offset window
     *  (always 1.0 in offsetFrac units) divided by total bound width —
     *  small V/div → small thumb (lots of scroll room), large V/div →
     *  full-width thumb (no scrolling needed). */
    private void syncVertSliderFromPrefs() {
        if (vertSlider == null || vertSlider.isDisposed()) return;
        Preferences prefs = Preferences.instance();
        double[] bounds = offsetFracBounds();
        double lo = bounds[0], hi = bounds[1];
        double span = hi - lo;
        int thumb;
        if (span <= 0) {
            thumb = NAV_RANGE;
        } else {
            double thumbFrac = Math.min(1.0, 1.0 / span);
            thumb = Math.max(MIN_SCROLLBAR_THUMB,
                    Math.min(NAV_RANGE, (int) Math.round(thumbFrac * NAV_RANGE)));
        }
        if (vertSlider.getThumb() != thumb) vertSlider.setThumb(thumb);

        // Increment / page-increment in slider units.  1 div = 1/Y of an
        // offsetFrac unit (the whole grid spans Y divisions), so 1/5 div =
        // 1/(5·Y), 5 div = 5/Y.  Converted to slider units via the
        // selection-range / span ratio — NOT NAV_RANGE / span, since the
        // usable selection range is (maximum − thumb), not maximum.  At
        // small spans (≈ 2), thumb is ~50 % of NAV_RANGE and the bug
        // factor was 2× — that's why arrow/page clicks visually jumped
        // 0.4 div / 10 div instead of 1/5 div / 5 div.
        int maxSelForSteps = NAV_RANGE - thumb;
        if (span > 0 && maxSelForSteps > 0) {
            double unitsPerOffset = maxSelForSteps / span;
            int arrowStep = Math.max(1,
                    (int) Math.round(unitsPerOffset / (5.0 * ScopeView.DIVISIONS_Y)));
            int pageStep  = Math.max(arrowStep,
                    (int) Math.round(unitsPerOffset * 5.0 / ScopeView.DIVISIONS_Y));
            if (vertSlider.getIncrement()     != arrowStep) vertSlider.setIncrement(arrowStep);
            if (vertSlider.getPageIncrement() != pageStep)  vertSlider.setPageIncrement(pageStep);
        }

        Channel selected = prefs.getOscMeasurementChannel();
        double frac = (selected == Channel.R)
                ? prefs.getOscRightOffsetFrac()
                : prefs.getOscLeftOffsetFrac();
        // Clamp to current bounds — guards against a leftover offset from
        // a different V/div pushing the slider off the rails.
        if (frac < lo) frac = lo;
        if (frac > hi) frac = hi;
        int maxSel = vertSlider.getMaximum() - vertSlider.getThumb();
        int sel = (span <= 0 || maxSel <= 0)
                ? 0
                : (int) Math.round((frac - lo) / span * maxSel);
        vertSlider.setSelection(sel);
    }

    /** Updates the tab control's calibrate button enabled state based on all
     *  three gating conditions: capture running, not in file mode, and the
     *  current measurement-channel Vpp ≥ {@link #CALIBRATE_MIN_VPP_FRACTION}
     *  of the ADC full-scale Vpp.  Called periodically from
     *  {@link #scheduleCalibrateButtonRefresh} while recording. */
    private void refreshCalibrateButtonEnabled() {
        if (tabControl == null) return;
        boolean running = isCapturing();
        boolean fileMode = view.isFileMode();
        boolean enable = running && !fileMode;
        if (enable) {
            double vpp = view.getLastVpp();
            double fsVpp = 2.0 * Preferences.instance().getAdcFsVoltageRms() * Math.sqrt(2.0);
            enable = !Double.isNaN(vpp) && fsVpp > 0.0
                  && (vpp / fsVpp) >= CALIBRATE_MIN_VPP_FRACTION;
        }
        tabControl.setCalibrateEnabled(enable);
    }

    /** Polls the calibrate-button gate every ~250 ms.  Self-cancels when
     *  the pane is disposed; runs forever otherwise (the gate cost is
     *  trivial — a volatile read + one float compare).  Also re-syncs
     *  the vertical scrollbar so an L/R measurement-channel switch made
     *  inside the canvas is reflected in the slider position. */
    private void scheduleCalibrateButtonRefresh() {
        if (group == null || group.isDisposed()) return;
        group.getDisplay().timerExec(250, () -> {
            if (group == null || group.isDisposed()) return;
            refreshCalibrateButtonEnabled();
            syncVertSliderFromPrefs();
            scheduleCalibrateButtonRefresh();
        });
    }

    // -------------------------------------------------------------------------
    // Capture lifecycle (inlined from the former ScopeController)
    //
    // Owns the UI-side Record state: the scopeLive flag, the periodic view
    // redraws, and the stop-with-frozen-snapshot behaviour.  The audio device
    // itself is owned by SharedCapture — this just acquires / releases a
    // reference for the duration of the scope's Record state, so the scope and
    // the FFT pane share the same device via the SharedCapture refcount.
    // -------------------------------------------------------------------------

    /** {@code true} while the scope's own Record button is on.  Does NOT
     *  reflect the shared capture device — the FFT pane can hold it open via
     *  {@code SharedCapture} while this stays {@code false}. */
    public boolean isCapturing() {
        return controller.isCapturing();
    }

    /** Human-readable description of the last {@link #startCapture()} failure
     *  (or {@code null} if it succeeded / wasn't attempted). */
    private String getLastStartError() {
        return controller.getLastStartError();
    }

    /** Acquires the shared capture via the controller, attaches both views
     *  to the live buffer, and starts the measurement worker + redraw
     *  timer.  Bails out if the device fails to open — the reason is then
     *  available via {@link #getLastStartError()}. */
    public synchronized void startCapture() {
        SignalBufferReader buf = controller.acquireCapture();
        if (buf == null) return;
        // Attach the views to the live buffer only when the scope's own Record
        // button is on.  If the FFT pane held the device open first, the views
        // stay detached until the user explicitly starts the scope — otherwise
        // paint events would draw what the audio thread writes while the
        // scope isn't capturing.
        view.setBuffer(buf);
        condensed.setBuffer(buf);
        // Measurement compute runs on its own daemon thread so the SWT paint
        // thread doesn't block on the Goertzel scan at high sample rates.
        // Started after the buffer is wired so the worker sees it.
        view.startMeasurementThread();
        scheduleRedraw();
    }

    /** Stops the capture but keeps a frozen snapshot of the last captured
     *  frame attached to both views.  A subsequent {@link #startCapture()}
     *  replaces the snapshot with a fresh live buffer. */
    public synchronized void stopCapture() {
        if (!controller.isCapturing()) return;
        // Stop the measurement worker first so it doesn't try to read from the
        // buffer while capture is being torn down.
        if (!view.isDisposed()) view.stopMeasurementThread();
        // Snapshot the live buffer so the scope keeps showing the LAST captured
        // frame after stop.  If the views stayed attached to the shared buffer,
        // any continued writes (because the FFT pane is still recording) would
        // visually resume the trace whenever a paint event fires.
        SignalBufferReader frozen = controller.releaseCapture();
        if (frozen != null) {
            if (!view.isDisposed())      view.setBuffer(frozen);
            if (!condensed.isDisposed()) condensed.setBuffer(frozen);
        }
    }

    /** Schedules the next paint pass via {@link Display#timerExec}.  The
     *  callback paints the main scope view <em>synchronously</em>
     *  ({@code redraw() + update()}) and only then schedules the next timer, so
     *  the {@value #REDRAW_PERIOD_MS} ms period is measured from end-of-paint —
     *  the message pump truly idles between scope paints, giving the FFT view's
     *  pending paint (and the FFT worker's {@code asyncExec} handoff) a chance
     *  to dispatch.  Without the synchronous {@code update()}, a paint longer
     *  than the period leaves the next timer permanently armed and back-to-back
     *  scope paints starve every other UI work item. */
    private void scheduleRedraw() {
        if (!controller.isCapturing()) return;
        view.getDisplay().timerExec(REDRAW_PERIOD_MS, () -> {
            if (!controller.isCapturing()) return;
            if (!view.isDisposed()) {
                view.redraw();
                view.update();
            }
            if (redrawCounter++ >= CONDENSED_DECIMATION) {
                redrawCounter = 0;
                if (!condensed.isDisposed()) condensed.redraw();
            }
            scheduleRedraw();
        });
    }

    // -------------------------------------------------------------------------
    // Layout helpers
    // -------------------------------------------------------------------------

    private GridLayout paneLayout() {
        // 2 columns: column 0 holds the scope canvas and every other row;
        // column 1 is the 20-px-wide right gap for the vertical scrollbar.
        // Margins are 0 so the content sits flush against the SWT.BORDER.
        // verticalSpacing = 2 gives a small breathing gap between the
        // PaneTitle header Label and the scope canvas below it (also
        // applies between every other row — minor side effect, acceptable).
        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth  = 0;
        gl.marginHeight = 0;
        gl.verticalSpacing = 2;
        gl.horizontalSpacing = 0;
        return gl;
    }

    private Button addToggleButton(Composite t, Image dim, Image lit) {
        Button btn = new Button(t, SWT.TOGGLE);
        btn.setImage(dim);
        GridData gd = new GridData(SWT.END, SWT.END, true, false);
        gd.widthHint  = TOGGLE_BUTTON;
        gd.heightHint = TOGGLE_BUTTON;
        btn.setLayoutData(gd);
        btn.addListener(SWT.Selection,
                e -> btn.setImage(btn.getSelection() ? lit : dim));
        return btn;
    }
}
