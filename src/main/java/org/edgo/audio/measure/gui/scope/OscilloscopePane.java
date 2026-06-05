package org.edgo.audio.measure.gui.scope;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabFolderRenderer;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.events.ControlListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowData;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.GenChangeCause;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.enums.LpfMode;
import org.edgo.audio.measure.enums.MainsSuppression;
import org.edgo.audio.measure.enums.TriggerEdge;
import org.edgo.audio.measure.enums.TriggerMode;
import org.edgo.audio.measure.gui.MainTab;
import org.edgo.audio.measure.gui.MainWindow;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.Dialogs;
import org.edgo.audio.measure.gui.common.IconUtils;
import org.edgo.audio.measure.gui.common.SvgPaths;
import org.edgo.audio.measure.gui.generator.NumericStepField;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.preferences.OscPreset;
import org.edgo.audio.measure.gui.preferences.Preferences;
import org.edgo.audio.measure.gui.sound.SharedCapture;
import org.edgo.audio.measure.gui.sound.SignalBufferReader;
import org.edgo.audio.measure.gui.widgets.FlatScrollbar;
import org.edgo.audio.measure.gui.widgets.PaneTitle;
import org.edgo.audio.measure.gui.widgets.StepSelector;
import org.edgo.audio.measure.sound.AudioBackend;

import lombok.extern.log4j.Log4j2;

/**
 * Builds the entire oscilloscope pane (Group frame + main scope view +
 * condensed strip + bottom toolbar with its V/T/Trigger/Screenshot groups
 * and Record toggle).  Extracted from {@link MainWindow} so the live pane
 * can stay attached to the {@link org.eclipse.swt.custom.SashForm} while a
 * second instance is constructed inside a hidden offscreen Shell for
 * screenshot rendering — that way the screenshot is a real SWT layout at
 * the target dimensions (buttons stay at their native pixel size, the scope
 * view's canvas claims the extra space) instead of a bitmap scale.
 *
 * <p>This class owns no audio-capture state and wires no controller: the
 * caller is responsible for attaching an {@link OscilloscopeController} to
 * {@link #getView()} / {@link #getCondensed()} and for hooking the Record
 * toggle (the screenshot pane skips both).
 */
@Log4j2
public final class OscilloscopePane {

    /** Side length of every small in-group toggle button (px). */
    public  static final int SQUARE_BUTTON = 32;
    /** Side length of the Record toggle (px). */
    public  static final int TOGGLE_BUTTON = 48;

    /** Bundle of image resources passed in by the owner (loaded once, shared). */
    /** Pixel height of the big record-LED button at the top toolbar. */
    private static final int RECORD_LED_SIZE  = 33;
    /** Pixel height of the small play-LED inside the Trigger group. */
    private static final int SMALL_LED_SIZE   = 26;
    /** Pixel height of the toolbar utility icons (camera / crosshair). */
    private static final int UTILITY_ICON_HEIGHT = 26;
    /** Pixel height of the file-row glyphs (floppy disk / folder open). */
    private static final int FILE_ICON_HEIGHT = 16;

    // Cached references from IconUtils — owned by the shared cache and
    // disposed centrally when the main shell tears down.
    private final Image recordDim;
    private final Image recordLit;
    private final Image bluePlayLit;
    private final Image cameraIcon;
    private final Image crosshairIcon;
    private final Image floppyDiskIcon;
    private final Image folderOpenIcon;

    private final Composite              group;
    private PaneTitle                    title;
    private final OscilloscopeView       view;
    private final CondensedView          condensed;
    private final Composite              toolbar;
    private final Button                 recordButton;
    /** Calibrate button — active only while capturing. Initialised by {@link #buildScreenshotGroup}. */
    private Button                       calibrateButton;
    /** Reference to the trigger toolbar tab content so it can be enabled/disabled
     *  when switching between live record and file (openSignal) modes. */
    private Composite                    triggerGroup;
    /** "Start" button on the Trigger toolbar tab — enabled iff Single
     *  mode is selected.  Promoted to a field so {@link #syncTriggerStart}
     *  can re-apply the rule after a bulk-enable. */
    private Button                       triggerStartBtn;
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
    /** Read-only text field showing the currently loaded openSignal file path. */
    private Text                         openSignalPathField;
    /** CTabFolder hosting the six toolbar tabs (Vertical / Horizontal / Trigger /
     *  Utility / Save to… / Open signal…).  Field-promoted so the double-click
     *  collapse handler can resize it. */
    private CTabFolder                    toolbarTabs;
    /** True when the user has double-clicked a tab strip to hide the tab
     *  content area — the strip stays visible so tabs can still be switched. */
    private boolean                      toolbarTabsCollapsed;
    /** Lazy-initialized resources shared between the Left and Right tab
     *  headers — both paint identical-style status-tile rows (channel-
     *  active LED, V/div, AC/DC, sinc interpolation) under their tab
     *  labels.  Colour and font are channel-agnostic, so a single set
     *  is enough. */
    private Color                        tabTileLedColor;
    private Color                        tabTileBg;
    private Color                        tabTileFg;
    private Font                         tabTileFont;
    /** Tab indices we paint a custom header + tile row for. */
    private static final int             TAB_LEFT       = 0;
    private static final int             TAB_RIGHT      = 1;
    private static final int             TAB_HORIZONTAL = 2;
    private static final int             TAB_TRIGGER    = 3;
    private static final int             TAB_PRESETS    = 4;
    private static final int             TAB_UTILITY    = 5;
    private static final int             TAB_SAVE       = 6;
    private static final int             TAB_LOAD       = 7;
    /** Number of tabs that get custom rendering (label + tile row).  The
     *  remaining tabs (utility / save / load) render with the default
     *  CTabFolder rendering but still get tab-level hover tooltips. */
    private static final int             NUM_CUSTOM_TABS = 4;
    /** Label texts for the custom-painted tabs — held separately because
     *  each matching CTabItem's own text is cleared to {@code ""} so the
     *  default centred-text rendering is suppressed.  Our custom renderer
     *  paints these labels top-aligned. */
    private final String[]               tabLabels = new String[NUM_CUSTOM_TABS];
    /** Transparent dummy images attached to the custom-painted CTabItems
     *  purely to force CTabFolder to size each tab wide enough for its
     *  tile row.  The CTabFolderRenderer.computeSize override is honoured
     *  by some code paths but bypassed by others, so adding an image is
     *  the reliable lever for tab width.  The images themselves aren't
     *  visually shown — our custom renderer paints the label and the
     *  paint listener draws the tile row over the same area. */
    private final Image[]                tabSpacerImages = new Image[NUM_CUSTOM_TABS];
    /** Painted-region map used to show region-specific tooltips on hover.
     *  Rebuilt on every CTabFolder paint; consumed by the MouseMove
     *  listener that sets the CTabFolder's tooltip text dynamically. */
    private final List<TabRegion> tabRegions = new ArrayList<>();

    /** A painted sub-region of a tab header (a tile) paired with its
     *  fully-resolved hover-tooltip text.  Resolved at paint time so the
     *  text reflects the current state (e.g. "Left channel: AC coupling"
     *  vs "Left channel: DC coupling") and changes when the state changes
     *  without needing a separate per-state lookup at hover time. */
    private static final class TabRegion {
        final org.eclipse.swt.graphics.Rectangle bounds;
        final String tooltip;
        TabRegion(org.eclipse.swt.graphics.Rectangle b, String tip) {
            this.bounds = b;
            this.tooltip = tip;
        }
    }

    /** Horizontal navigation slider sitting above the condensed strip. */
    private FlatScrollbar                navSlider;
    /** Linux-only fixed-height Label that takes the navSlider's slot in
     *  record mode.  GTK honours heightHint on a Label but not on an
     *  invisible Canvas (FlatScrollbar), so the spacer keeps the gap
     *  above the condensed strip uniform with file mode.  Unused on
     *  Windows / macOS where the navSlider's own heightHint already
     *  works correctly. */
    private Label                        navSliderSpacer;
    /** Step selectors for V/div (left + right) and t/div — fields so the
     *  mouse-wheel zoom on the main scope can drive them in sync with
     *  the toolbar dropdowns. */
    private StepSelector                 leftScale;
    private StepSelector                 rightScale;
    private StepSelector                 timeScale;
    private StepSelector                 hysteresisSel;
    private Button                       hysteresisEnable;
    /** "Reconstructed beat" overlay toggle — enabled only when the
     *  generator is in DUAL_TONE mode; field-promoted so the
     *  generator-form listener can flip its enabled state without
     *  rebuilding the tab. */
    private Button                       reconstructedBeatBtn;
    /** Per-channel and trigger control widgets — fields so the preset
     *  load path can push saved values back into the UI without having to
     *  rebuild the toolbar.  All built once in {@link #buildLeftGroup} /
     *  {@link #buildRightGroup} / {@link #buildTriggerGroup}. */
    private Button leftToggle, rightToggle;
    private Button leftAc, rightAc;
    private Button leftSinc, rightSinc;
    private Combo  leftMains, rightMains;
    private Combo  leftLpf, rightLpf;
    private Button chL, chR;
    private Button edgeRise, edgeFall;
    private Button modeAuto, modeNormal, modeSingle;
    /** Preset name combo box — editable so the user can type a new name
     *  and press Save. */
    private Combo presetCombo;
    /** Save / Delete buttons — fields so {@link #refreshPresetButtonState}
     *  can toggle their enablement based on the current combo text and
     *  whether settings differ from the matching saved preset. */
    private Button presetSaveBtn;
    private Button presetLoadBtn;
    private Button presetDeleteBtn;
    /** Vertical scrollbar in the 20 px right gap.  Always visible.  Maps
     *  [0, NAV_RANGE] selection to the measurement channel's offset
     *  fraction in [0, 1] (top of grid → bottom of grid).  In live mode
     *  the horizontal navSlider is hidden — only this one stays active. */
    private FlatScrollbar                vertSlider;
    private static final int             SCROLLBAR_THICKNESS = 20;
    /** Slider resolution — fixed range; {@link #navSlider}'s selection
     *  is derived from {@link #viewCenterFrames} via the buffer's
     *  capacity.  Sized large so that arrow-click steps of 1/5 div and
     *  page-click steps of 5 divisions stay accurate at any practical
     *  zoom (1000 was too coarse — rounding visibly distorted the step
     *  at moderate zooms).  At one million units per slider, a single
     *  unit is sub-microsecond on most signals. */
    private static final int             NAV_RANGE = 1_000_000;
    /** Minimum thumb size as a fraction of the slider's total range —
     *  keeps the thumb visually grabbable (~18-25 px at typical canvas
     *  sizes) even when the visible window is a tiny slice of a huge
     *  ring buffer.  3 % of NAV_RANGE = 30 000 units, ~18 px at a 600 px
     *  track, ~24 px at 800 px. */
    private static final int             MIN_SCROLLBAR_THUMB = NAV_RANGE / 33;
    /**
     * Primary state for "where in the signal the view is centred".
     * Absolute (possibly fractional) frame index of the sample under
     * the canvas centre.  Sentinel: any negative value means "follow
     * latest" (used in live record mode).  The slider position and
     * {@link OscilloscopeView#viewBackOffsetFrames} are derived from
     * this — changing t/div recomputes those without touching
     * {@code viewCenterFrames}, so the centred frame stays fixed
     * across zooms.  Kept as a {@code double} so repeated zooms don't
     * accumulate rounding error.
     */
    private double viewCenterFrames = -1.0;
    private final GridData               condensedGd;
    /**
     * Capture controller — owned and wired by the live pane.  {@code null}
     * on the screenshot-only pane variant ({@code liveCapture = false}),
     * which renders without ever opening an audio device.
     */
    private final OscilloscopeController controller;
    /**
     * Synchronous file loader for the "Open signal…" feature.  Replaces
     * the threaded play model that suffered race conditions when
     * switching between record and file modes.
     */
    private ScopeOpenSignal              loader;


    /**
     * Constructs the pane and all its children.
     * @param parent       host composite for the pane's Group.
     * @param liveCapture  when {@code true}, creates an
     *                     {@link OscilloscopeController}, wires the Record
     *                     toggle to it (showing an error MessageBox on
     *                     capture-open failure), and stops the controller
     *                     on Group dispose.  The screenshot pane variant
     *                     passes {@code false} — its camera / calibrate
     *                     buttons still exist for layout fidelity but are
     *                     never clicked.
     */
    public OscilloscopePane(Composite parent, boolean liveCapture) {
        IconUtils icons = IconUtils.instance();
        Display d = parent.getDisplay();
        this.recordDim      = icons.createRecordLed(d, 200,  40,  40, false, RECORD_LED_SIZE);
        this.recordLit      = icons.createRecordLed(d, 255,   0,   0, true,  RECORD_LED_SIZE);
        this.bluePlayLit    = icons.createPlayLed  (d,  60, 130, 230, true,  SMALL_LED_SIZE);
        this.cameraIcon     = icons.render(d, SvgPaths.CAMERA,
                (int) Math.round(UTILITY_ICON_HEIGHT * 1.27), UTILITY_ICON_HEIGHT);
        this.crosshairIcon  = icons.render(d, SvgPaths.CROSSHAIR,
                UTILITY_ICON_HEIGHT, UTILITY_ICON_HEIGHT);
        this.floppyDiskIcon = icons.renderAtHeight(d, SvgPaths.FLOPPY_DISK, FILE_ICON_HEIGHT, null);
        this.folderOpenIcon = icons.renderAtHeight(d, SvgPaths.FOLDER_OPEN, FILE_ICON_HEIGHT, null);
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

        view = new OscilloscopeView(group);
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
        installScopeViewWheelHandler();

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
        condensed = new CondensedView(group);
        condensedGd = new GridData(SWT.FILL, SWT.FILL, true, false);
        condensedGd.horizontalSpan = 2;
        condensed.setLayoutData(condensedGd);

        // Toolbar area: CTabFolder on the left (fills) + Record toggle
        // button on the right (fixed).  Each former toolbar Group is now
        // a tab inside the folder; only one is visible at a time so the
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

        // CTabFolder (not native TabFolder) so the tab strip stays on a
        // single row even when the toolbar gets narrow — native TabFolder
        // on Win32 wraps the strip to a second row when the labels don't
        // fit, doubling the toolbar height.  CTabFolder shrinks tab labels
        // and shows scroll-chevrons instead.
        CTabFolder tabs = new CTabFolder(toolbar, SWT.NONE);
        tabs.setSimple(false);
        tabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        this.toolbarTabs = tabs;

        // Custom renderer: gives the channel tabs (Left/Right) extra width
        // to fit the tile row, and top-aligned text instead of vertically
        // centred so the tiles can sit cleanly underneath.
        tabs.setRenderer(new ChannelTabHeaderRenderer(tabs));

        buildLeftGroup(tabs);
        buildRightGroup(tabs);
        buildHorizontalGroup(tabs);
        buildTriggerGroup(tabs, bluePlayLit, view::armSingle);
        buildPresetsGroup(tabs);
        buildScreenshotGroup(tabs, cameraIcon, crosshairIcon);
        if (liveCapture) {
            buildScopeSaveToGroup(tabs);
            buildScopeOpenSignalGroup(tabs);
        }

        // Select the first tab BEFORE setting tab height — CTabFolder's
        // preferred-size calculation includes the selected tab's body in
        // the total, and we want that body height baked in when the first
        // layout pass uses our taller strip height (otherwise the body
        // gets squeezed to 0 px on the first paint until a resize).
        if (tabs.getItemCount() > 0) tabs.setSelection(0);
        tabs.setTabHeight(46);
        // Capture each custom tab's label and clear the CTabItem's own text
        // so the default renderer doesn't draw it.  The custom renderer
        // (ChannelTabHeaderRenderer.draw) paints them top-aligned — no
        // overpaint band needed because nothing is centred underneath.
        for (int i = 0; i < NUM_CUSTOM_TABS && i < tabs.getItemCount(); i++) {
            tabLabels[i] = tabs.getItem(i).getText();
            tabs.getItem(i).setText("");
        }
        // Attach a transparent spacer image to each custom tab so CTabFolder
        // sizes it wide enough for its tile row.
        for (int i = 0; i < NUM_CUSTOM_TABS && i < tabs.getItemCount(); i++) {
            updateTabSpacerImage(i);
        }
        // Flush layout caches up the parent chain so the toolbar's
        // GridLayout picks up the new (taller) CTabFolder preferred size.
        toolbar.layout(true, true);
        group.layout(true, true);

        installTabPaintAndTooltips(tabs);
        installTabCollapseShortcuts(tabs);

        recordButton = addToggleButton(toolbar, recordDim, recordLit);
        recordButton.setToolTipText(I18n.t("scope.record.tooltip"));
        // addToggleButton sets grabExcessHorizontalSpace=true to push
        // the record LED to the far right of an old single-row toolbar.
        // In the new two-cell layout the CTabFolder already grabs the
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

        if (liveCapture) {
            controller = new OscilloscopeController(view, condensed);
            loader     = new ScopeOpenSignal(view, condensed);
            wireLiveCaptureLifecycle();
        } else {
            controller = null;
            loader     = null;
        }

        installCondensedAutoResize();
        wireHelpAnchors();
    }

    /** Live-pane capture wiring: Record button, Auto-Setup bus subscription,
     *  and a dispose listener that unsubscribes and tears the controller
     *  down.  Called only when {@code liveCapture} is true — the
     *  screenshot pane mirrors the Record-button visual state externally
     *  via {@link #setRecordingState} but never opens an audio device of
     *  its own. */
    private void wireLiveCaptureLifecycle() {
        wireRecordButton();
        autoSetupListener        = ignored -> performAutoSetup();
        freqRespStartedListener  = ignored -> onFreqRespMeasurementStarted();
        freqRespStoppedListener  = ignored -> onFreqRespMeasurementStopped();
        // Track generator-form changes so the "Reconstructed beat"
        // checkbox stays enabled only in DUAL_TONE mode without
        // requiring a pane rebuild.
        Consumer<GenChangeCause> beatFormListener = cause -> {
            if (group != null && !group.isDisposed()) {
                group.getDisplay().asyncExec(this::syncReconstructedBeatEnabled);
            }
        };
        MessageBus bus = MessageBus.instance();
        bus.subscribe(Events.SCOPE_AUTO_SETUP,             autoSetupListener);
        bus.subscribe(Events.FREQRESP_MEASUREMENT_STARTED, freqRespStartedListener);
        bus.subscribe(Events.FREQRESP_MEASUREMENT_STOPPED, freqRespStoppedListener);
        bus.subscribe(Events.GENERATOR_SIGNAL_CHANGED,     beatFormListener);
        group.addDisposeListener(e -> {
            MessageBus bus2 = MessageBus.instance();
            bus2.unsubscribe(Events.SCOPE_AUTO_SETUP,             autoSetupListener);
            bus2.unsubscribe(Events.FREQRESP_MEASUREMENT_STARTED, freqRespStartedListener);
            bus2.unsubscribe(Events.FREQRESP_MEASUREMENT_STOPPED, freqRespStoppedListener);
            bus2.unsubscribe(Events.GENERATOR_SIGNAL_CHANGED,     beatFormListener);
            loader.clear();
            controller.stop();
        });
    }

    /** Stops a running capture and grays the Record button — fired by the
     *  Frequency Response pane via {@link Events#FREQRESP_MEASUREMENT_STARTED}
     *  so the FreqResp analyzer can take exclusive control of the device. */
    private void onFreqRespMeasurementStarted() {
        if (recordButton == null || recordButton.isDisposed()) return;
        if (recordButton.getSelection()) {
            recordButton.setSelection(false);
            controller.stop();
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

    /** Tags every interactive toolbar widget with a {@code "helpAnchor"}
     *  so Ctrl+F1 can resolve the focused control to a specific section
     *  of {@code oscilloscope.html}.  Tab tiles are painted (not real
     *  widgets), but each tab body inherits the chapter-level anchor
     *  via the focus-walk fallback. */
    private void wireHelpAnchors() {
        group.setData("helpAnchor", "oscilloscope.html");
        if (leftToggle      != null) leftToggle     .setData("helpAnchor", "oscilloscope.html#scope-channel-toggle");
        if (rightToggle     != null) rightToggle    .setData("helpAnchor", "oscilloscope.html#scope-channel-toggle");
        if (leftScale       != null) leftScale      .setData("helpAnchor", "oscilloscope.html#scope-vdiv");
        if (rightScale      != null) rightScale     .setData("helpAnchor", "oscilloscope.html#scope-vdiv");
        if (leftAc          != null) leftAc         .setData("helpAnchor", "oscilloscope.html#scope-acdc");
        if (rightAc         != null) rightAc        .setData("helpAnchor", "oscilloscope.html#scope-acdc");
        if (leftSinc        != null) leftSinc       .setData("helpAnchor", "oscilloscope.html#scope-sinc-interp");
        if (rightSinc       != null) rightSinc      .setData("helpAnchor", "oscilloscope.html#scope-sinc-interp");
        if (timeScale       != null) timeScale      .setData("helpAnchor", "oscilloscope.html#scope-tdiv");
        if (chL             != null) chL            .setData("helpAnchor", "oscilloscope.html#scope-trigger-channel");
        if (chR             != null) chR            .setData("helpAnchor", "oscilloscope.html#scope-trigger-channel");
        if (edgeRise        != null) edgeRise       .setData("helpAnchor", "oscilloscope.html#scope-trigger-edge");
        if (edgeFall        != null) edgeFall       .setData("helpAnchor", "oscilloscope.html#scope-trigger-edge");
        if (modeAuto        != null) modeAuto       .setData("helpAnchor", "oscilloscope.html#scope-trigger-mode");
        if (modeNormal      != null) modeNormal     .setData("helpAnchor", "oscilloscope.html#scope-trigger-mode");
        if (modeSingle      != null) modeSingle     .setData("helpAnchor", "oscilloscope.html#scope-trigger-mode");
        if (hysteresisEnable!= null) hysteresisEnable.setData("helpAnchor", "oscilloscope.html#scope-trigger-hysteresis");
        if (hysteresisSel   != null) hysteresisSel  .setData("helpAnchor", "oscilloscope.html#scope-trigger-hysteresis");
        if (triggerGroup    != null) triggerGroup   .setData("helpAnchor", "oscilloscope.html#scope-tab-trigger");
        if (presetCombo     != null) presetCombo    .setData("helpAnchor", "oscilloscope.html#scope-tab-presets");
        if (presetSaveBtn   != null) presetSaveBtn  .setData("helpAnchor", "oscilloscope.html#scope-tab-presets");
        if (presetLoadBtn   != null) presetLoadBtn  .setData("helpAnchor", "oscilloscope.html#scope-tab-presets");
        if (presetDeleteBtn != null) presetDeleteBtn.setData("helpAnchor", "oscilloscope.html#scope-tab-presets");
        if (recordButton    != null) recordButton   .setData("helpAnchor", "oscilloscope.html");
    }

    /**
     * Wires the Record toggle to {@link #controller}: pressing it starts
     * capture; on failure the button snaps back to off and a MessageBox
     * shows the human-readable reason from
     * {@link OscilloscopeController#getLastStartError()}.
     */
    private void wireRecordButton() {
        recordButton.addListener(SWT.Selection, e -> {
            if (recordButton.getSelection()) {
                // Starting a fresh recording — wipe any loaded
                // openSignal file so the live capture buffer takes over.
                if (loader != null && loader.isLoaded()) {
                    loader.clear();
                    if (openSignalPathField != null && !openSignalPathField.isDisposed()) {
                        openSignalPathField.setText("");
                        openSignalPathField.setToolTipText("");
                    }
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
                // Re-enable the trigger group — it applies again to a
                // live capture.  setSubtreeEnabled bulk-enables every
                // descendant, including the Start button; re-apply the
                // "Start is enabled iff Single mode" rule afterwards
                // so it doesn't stay clickable in Auto / Normal modes.
                setSubtreeEnabled(triggerGroup, true);
                syncTriggerStart();
                view.setViewBackOffsetFrames(0);
                condensed.setViewBackOffsetFrames(0);
                controller.start();
                if (!controller.isRunning()) {
                    recordButton.setSelection(false);
                    recordButton.setImage(recordDim);
                    String err = controller.getLastStartError();
                    if (err != null) {
                        Dialogs.error(group.getShell(), I18n.t("scope.record.error.start"), err);
                    }
                }
            } else {
                controller.stop();
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
        if (calibrateButton != null && !calibrateButton.isDisposed()) {
            calibrateButton.setEnabled(recording);
        }
    }

    public Composite              getGroup()        { return group; }
    public OscilloscopeView       getView()         { return view; }
    public CondensedView          getCondensed()    { return condensed; }
    public Composite              getToolbar()      { return toolbar; }
    public Button                 getRecordButton() { return recordButton; }
    /** Returns the capture controller for the live pane, or {@code null} on the screenshot-only variant. */
    public OscilloscopeController getController()   { return controller; }

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

    /** Opens the ADC-calibration dialog.  Reads the current Vrms from
     *  the pane's own scope view (no cross-pane fallback — the FFT pane
     *  has its own calibrate button on the FFT side); aborts with an
     *  info MessageBox when no live Vrms is available.  Rescales
     *  {@code AudioBackend.getAdcFsVoltageRms()} so the displayed Vrms
     *  matches the entered value, and persists the new calibration. */
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

    /** Renders this pane into a fresh image at {@code (targetW, targetH)}
     *  by constructing a brand-new {@link OscilloscopePane} inside a
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
        OscilloscopePane shotPane = new OscilloscopePane(offscreen, false);
        Image output = new Image(d, targetW, targetH);
        try {
            if (view != null && !view.isDisposed()) {
                // Carbon-copy the exact frame on screen (works while stopped /
                // frozen).  The frozen frame drives the trace; the buffer is
                // still attached so "has signal" is true and the measurement
                // table renders (its values come from copyMeasurementsFrom).
                OscilloscopeView.RenderedFrame snap = view.getRenderedFrameSnapshot();
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
            shotPane.setRecordingState(controller != null && controller.isRunning());

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

    /** True when this pane is collapsed to just its title bar. */
    public boolean isCollapsed() { return collapsed; }

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

    /** Collapse state + per-child snapshot.  See {@link #setCollapsed(boolean)}. */
    private boolean    collapsed;
    private boolean[]  preCollapseChildVisible;
    private boolean[]  preCollapseChildExclude;

    /** Custom CTabFolderRenderer for the toolbar tabs.  For the custom-painted
     *  tab indices (Left / Right / Horizontal / Trigger):
     *  <ul>
     *    <li>{@code computeSize} is widened so the status-tile row that
     *        {@link #drawTabTiles} paints underneath the label fits inside
     *        the tab.</li>
     *    <li>The tab label is redrawn top-aligned (default is vertically
     *        centred) so the tiles can sit cleanly underneath without
     *        overlapping the text.</li>
     *  </ul>
     *  All other tabs render with the default behaviour. */
    private final class ChannelTabHeaderRenderer extends CTabFolderRenderer {
        protected ChannelTabHeaderRenderer(CTabFolder parent) {
            super(parent);
        }

        @Override
        protected org.eclipse.swt.graphics.Point computeSize(int part, int state, GC gc, int wHint, int hHint) {
            org.eclipse.swt.graphics.Point p = super.computeSize(part, state, gc, wHint, hHint);
            if (part >= 0 && part < NUM_CUSTOM_TABS) {
                ensureTabResources();
                int required = computeRequiredTabWidth(gc, part);
                p.x = Math.max(p.x, required);
            }
            return p;
        }

        @Override
        protected void draw(int part, int state, org.eclipse.swt.graphics.Rectangle bounds, GC gc) {
            super.draw(part, state, bounds, gc);
            if (part < 0 || part >= NUM_CUSTOM_TABS) return;

            // The CTabItem's own text is "" (we cleared it after
            // construction) so super.draw painted just the tab background
            // + curves — no centred label to erase.  Paint our top-aligned
            // label straight onto the existing background; no overpaint
            // band needed.
            String label = tabLabels[part];
            if (label == null || label.isEmpty()) return;
            boolean selected = (state & SWT.SELECTED) != 0;
            int leftInset = selected ? 11 : 15;
            Color fg = selected ? toolbarTabs.getSelectionForeground() : toolbarTabs.getForeground();
            if (fg == null) fg = toolbarTabs.getDisplay().getSystemColor(SWT.COLOR_WIDGET_FOREGROUND);
            gc.setForeground(fg);

            Font prevFont = gc.getFont();
            gc.setFont(toolbarTabs.getFont());
            gc.drawText(label, bounds.x + leftInset, bounds.y + 3, true);
            gc.setFont(prevFont);
        }
    }

    /** Forces the CTabFolder to recompute a custom tab's width and repaint
     *  so that toggling a related preference visibly resizes the tab on
     *  the spot.  Width is controlled via a transparent image attached
     *  to the CTabItem (see {@link #updateTabSpacerImage}). */
    private void refreshTabHeader(int tabIndex) {
        if (toolbarTabs == null || toolbarTabs.isDisposed()) return;
        if (tabIndex < 0 || tabIndex >= NUM_CUSTOM_TABS) return;
        updateTabSpacerImage(tabIndex);
        toolbarTabs.layout(true, true);
        toolbarTabs.redraw();
    }

    /** Resizes / recreates the invisible spacer image attached to a custom
     *  {@link CTabItem} so the tab is always wide enough to hold the
     *  current state's tile row. */
    private void updateTabSpacerImage(int tabIndex) {
        if (toolbarTabs == null || toolbarTabs.isDisposed()) return;
        if (tabIndex < 0 || tabIndex >= NUM_CUSTOM_TABS) return;
        if (toolbarTabs.getItemCount() <= tabIndex) return;
        Display d = toolbarTabs.getDisplay();
        ensureTabResources();

        int width;
        GC gc = new GC(toolbarTabs);
        try {
            // The CTabItem's text is "" (we cleared it so the default
            // renderer wouldn't draw a centred label), so the tab's
            // natural width = imageWidth + trim.  Size the spacer image
            // to fit the wider of (tile row, label) plus the per-tab
            // extra so the renderer's top-aligned label has room and the
            // tile row fits underneath.
            width = Math.max(50, computeRequiredTabWidth(gc, tabIndex));
        } finally {
            gc.dispose();
        }

        Image existing = tabSpacerImages[tabIndex];
        if (existing != null && !existing.isDisposed()) {
            org.eclipse.swt.graphics.Rectangle b = existing.getBounds();
            if (b.width == width && b.height == 1) return;     // no change needed
            existing.dispose();
        }
        // Build a transparent 1-px-tall image of the required width.
        org.eclipse.swt.graphics.PaletteData palette =
                new org.eclipse.swt.graphics.PaletteData(0xFF0000, 0x00FF00, 0x0000FF);
        org.eclipse.swt.graphics.ImageData id =
                new org.eclipse.swt.graphics.ImageData(width, 1, 24, palette);
        id.alphaData = new byte[width];                      // all zeros → fully transparent
        Image img = new Image(d, id);
        tabSpacerImages[tabIndex] = img;
        toolbarTabs.getItem(tabIndex).setImage(img);
    }

    /** Returns the minimum tab width needed to fit BOTH the label (drawn
     *  by the renderer at the top, using the tab's normal font) AND the
     *  tile row (drawn by the paint listener at the bottom, using the
     *  smaller tab-tile font), plus any per-tab extra padding the user
     *  asked for.  Used by the spacer-image sizer and the renderer's
     *  computeSize so the tab always accommodates the wider of the two
     *  rows — for tabs like "Horizontal" the label is the bigger driver,
     *  for tabs like "Left" the tile row is. */
    private int computeRequiredTabWidth(GC gc, int tabIndex) {
        if (tabIndex < 0 || tabIndex >= NUM_CUSTOM_TABS) return 0;
        Font prev = gc.getFont();
        gc.setFont(tabTileFont);
        int tilesW = computeTileRowWidth(gc, tabIndex);
        gc.setFont(toolbarTabs.getFont());
        String label = tabLabels[tabIndex];
        // Label drawn at bounds.x + 12 (selected leftInset) — reserve 14 px
        // total (left inset + tiny right margin) on top of the label width.
        int labelW = (label != null && !label.isEmpty()) ? gc.textExtent(label).x + 14 : 0;
        gc.setFont(prev);
        return Math.max(tilesW, labelW) + extraTabPadding(tabIndex);
    }

    /** Per-tab additional padding the user has dialled in.  Applied on top
     *  of {@code max(tilesW, labelW)} in {@link #computeRequiredTabWidth}. */
    private int extraTabPadding(int tabIndex) {
        switch (tabIndex) {
            case TAB_LEFT:
            case TAB_RIGHT:      return 7;
            case TAB_HORIZONTAL: return 20;
            case TAB_TRIGGER:    return 7;
            default:             return 0;
        }
    }

    /** Returns the total pixel width occupied by a tab's tile row, given
     *  the current preferences.  The GC passed in must already have
     *  {@link #tabTileFont} selected so the text widths match what
     *  {@link #drawTabTiles} will paint. */
    private int computeTileRowWidth(GC gc, int tabIndex) {
        Preferences prefs = Preferences.instance();
        final int tileH    = 22;
        final int hPadding = 4;
        final int gap      = 3;
        int total = 0;
        switch (tabIndex) {
            case TAB_LEFT:
            case TAB_RIGHT: {
                boolean isLeft  = (tabIndex == TAB_LEFT);
                boolean enabled = isLeft ? prefs.isOscLeftChannelEnabled() : prefs.isOscRightChannelEnabled();
                double  vDiv    = isLeft ? prefs.getOscLeftVoltsPerDiv()   : prefs.getOscRightVoltsPerDiv();
                boolean acMode  = isLeft ? prefs.isOscLeftAcMode()         : prefs.isOscRightAcMode();
                boolean sinc    = isLeft ? prefs.isOscLeftSincInterpEnabled()
                                         : prefs.isOscRightSincInterpEnabled();
                if (enabled) total += tileH + gap;
                total += gc.textExtent(ScopeFormat.shortVoltsPerDiv(vDiv)).x + 2 * hPadding + gap;
                total += gc.textExtent(acMode ? "ac" : "dc").x + 2 * hPadding + gap;
                total += gc.textExtent(sinc ? "sin" : "lin").x + 2 * hPadding;
                break;
            }
            case TAB_HORIZONTAL: {
                total += gc.textExtent(ScopeFormat.shortTimePerDiv(prefs.getOscTimePerDiv())).x + 2 * hPadding;
                break;
            }
            case TAB_TRIGGER: {
                total += gc.textExtent(triggerChannelLabel()).x + 2 * hPadding + gap;
                total += gc.textExtent(triggerEdgeLabel()).x    + 2 * hPadding + gap;
                total += gc.textExtent(triggerModeLabel()).x    + 2 * hPadding;
                if (prefs.isOscTriggerHysteresisEnabled()) {
                    total += gap + gc.textExtent(triggerHysteresisLabel()).x + 2 * hPadding;
                }
                break;
            }
        }
        return total;
    }

    /** Paints a row of status tiles under a tab's label.  Drawn by the
     *  {@link CTabFolder} paint listener AFTER the default tab rendering
     *  so the tiles overlay the lower part of the tab.  All tiles share
     *  a 22-px height, 2-px corner radius; widths follow each tile's
     *  content.  Per-tab tile contents:
     *  <ul>
     *    <li>Left / Right: red LED (if channel active), V/div, ac/dc, sin/lin.</li>
     *    <li>Horizontal: time/div (e.g. {@code "20m"}, {@code "50u"}).</li>
     *    <li>Trigger: channel ({@code L}/{@code R}), edge ({@code ↑}/{@code ↓}),
     *        mode ({@code A}/{@code N}/{@code S}).</li>
     *  </ul> */
    /** Installs the paint, mouse-move, mouse-exit and dispose listeners
     *  that turn the CTabFolder header into a hover-tooltipped tile strip.
     *  Paint draws the per-tab tile row; mouse-move re-resolves the
     *  tooltip on movement; mouse-exit clears it; dispose tears down the
     *  paint resources.  Extracted from the constructor so the wiring
     *  reads as one call instead of 70 inline lines. */
    private void installTabPaintAndTooltips(CTabFolder tabs) {
        // Paint listener fires AFTER CTabFolder's internal renderer has
        // drawn the default tab strip; we use it to add the tile row under
        // each custom-tab's label.  Also rebuilds the painted-region map
        // (label + each tile) so the MouseMove listener below can show a
        // region-specific tooltip on hover.
        tabs.addPaintListener(e -> {
            tabRegions.clear();
            for (int i = 0; i < NUM_CUSTOM_TABS && i < tabs.getItemCount(); i++) {
                if (!tabs.getItem(i).isDisposed()) {
                    drawTabTiles(e.gc, tabs.getItem(i).getBounds(), i);
                }
            }
        });
        // MouseMove → dynamic tooltip lookup against tabRegions.  Set
        // the tooltip on the CTabItem the cursor is hovering, because
        // CTabFolder's built-in tab-tooltip mechanism takes priority over
        // the widget-level setToolTipText (the latter is suppressed while
        // the cursor is inside a tab).  Only change the text when the
        // resolved region actually changes so the OS-native hover delay
        // isn't reset on every pixel of movement.
        final String[] currentTip = { null };
        final int[]    currentIdx = { -1 };
        tabs.addMouseMoveListener(e -> {
            // First check tile regions (state-aware tooltips, pre-resolved
            // at paint time).
            String tip = null;
            for (TabRegion r : tabRegions) {
                if (r.bounds.contains(e.x, e.y)) { tip = r.tooltip; break; }
            }
            // Find the hovered tab; if no tile matched, fall back to the
            // tab-level tooltip so hovering anywhere on the tab header
            // (curve, margin, label) still shows the tab description.
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
        tabs.addDisposeListener(e -> {
            if (tabTileLedColor != null) tabTileLedColor.dispose();
            if (tabTileBg       != null) tabTileBg      .dispose();
            if (tabTileFg       != null) tabTileFg      .dispose();
            if (tabTileFont     != null) tabTileFont    .dispose();
            for (Image img : tabSpacerImages) {
                if (img != null) img.dispose();
            }
        });
    }

    /** Installs the double-click and Enter-key handlers on the tab strip
     *  that toggle the tab CONTENT area's visibility.  Strip stays so the
     *  user can still swap tabs; only the content area collapses. */
    private void installTabCollapseShortcuts(CTabFolder tabs) {
        // Double-click on the tab strip — detect "strip" by checking the
        // click y is above the tab folder's client area (which starts
        // below the tab header row).
        tabs.addListener(SWT.MouseDoubleClick, e -> {
            Rectangle ca = tabs.getClientArea();
            if (e.y < ca.y) toggleToolbarTabsCollapse();
        });
        // Keyboard navigation when the CTabFolder has focus:
        //   ←/→        cycle to previous / next tab (CTabFolder built-in)
        //   Enter      same as double-click on the tab strip
        // ARROW_LEFT / ARROW_RIGHT navigation is left to CTabFolder's
        // built-in traversal — it already moves the selection by exactly
        // one tab.  Layering our own handler on top double-stepped (the
        // traversal moved by 1 and the KeyDown handler moved by 1 more).
        // Only Enter is handled here, to toggle the tab content area.
        tabs.addListener(SWT.KeyDown, e -> {
            if (e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR) {
                toggleToolbarTabsCollapse();
                e.doit = false;
            }
        });
    }

    private void drawTabTiles(GC gc, Rectangle bounds, int tabIndex) {
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) return;
        if (tabIndex < 0 || tabIndex >= NUM_CUSTOM_TABS) return;
        Preferences prefs = Preferences.instance();
        ensureTabResources();
        Font prevFont = gc.getFont();
        gc.setFont(tabTileFont);
        gc.setAntialias(SWT.ON);
        gc.setTextAntialias(SWT.ON);

        final int tileH    = 22;
        final int cornerR  = 4;    // arc-diameter (= 2-px radius)
        final int hPadding = 4;    // horizontal padding inside each text tile
        final int gap      = 3;    // gap between tiles

        // Inset matches ChannelTabHeaderRenderer.draw — line up the tile
        // row's left edge with the label above it, accounting for the
        // wider curve on the selected tab.
        boolean selected = (toolbarTabs.getSelectionIndex() == tabIndex);
        int leftInset    = selected ? 11 : 15;
        int y = bounds.y + bounds.height - tileH - 2;   // 2-px bottom margin
        int x = bounds.x + leftInset;

        switch (tabIndex) {
            case TAB_LEFT:
            case TAB_RIGHT: {
                boolean isLeft  = (tabIndex == TAB_LEFT);
                boolean enabled = isLeft ? prefs.isOscLeftChannelEnabled() : prefs.isOscRightChannelEnabled();
                double  vDiv    = isLeft ? prefs.getOscLeftVoltsPerDiv()   : prefs.getOscRightVoltsPerDiv();
                boolean acMode  = isLeft ? prefs.isOscLeftAcMode()         : prefs.isOscRightAcMode();
                boolean sinc    = isLeft ? prefs.isOscLeftSincInterpEnabled()
                                         : prefs.isOscRightSincInterpEnabled();
                String chName = I18n.t(isLeft ? "scope.tab.left" : "scope.tab.right");
                if (enabled) {
                    final int ledD = 16;
                    int ledInset = (tileH - ledD) / 2;
                    gc.setBackground(tabTileBg);
                    gc.fillRoundRectangle(x, y, tileH, tileH, cornerR, cornerR);
                    gc.setBackground(tabTileLedColor);
                    gc.fillOval(x + ledInset, y + ledInset, ledD, ledD);
                    addTabRegion(x, y, tileH, tileH, I18n.t("scope.tile.led.active", chName));
                    x += tileH + gap;
                }
                int wv = drawTabTextTile(gc, x, y, tileH, hPadding, cornerR, ScopeFormat.shortVoltsPerDiv(vDiv));
                addTabRegion(x, y, wv, tileH,
                        I18n.t("scope.tile.scale", chName, OscParse.formatVoltsPerDiv(vDiv)));
                x += wv + gap;
                int wc = drawTabTextTile(gc, x, y, tileH, hPadding, cornerR, acMode ? "ac" : "dc");
                addTabRegion(x, y, wc, tileH,
                        I18n.t(acMode ? "scope.tile.coupling.ac" : "scope.tile.coupling.dc", chName));
                x += wc + gap;
                int ws = drawTabTextTile(gc, x, y, tileH, hPadding, cornerR, sinc ? "sin" : "lin");
                addTabRegion(x, y, ws, tileH,
                        I18n.t(sinc ? "scope.tile.interp.sin" : "scope.tile.interp.lin", chName));
                break;
            }
            case TAB_HORIZONTAL: {
                int wt = drawTabTextTile(gc, x, y, tileH, hPadding, cornerR,
                                         ScopeFormat.shortTimePerDiv(prefs.getOscTimePerDiv()));
                addTabRegion(x, y, wt, tileH,
                        I18n.t("scope.tile.time", OscParse.formatTimePerDiv(prefs.getOscTimePerDiv())));
                break;
            }
            case TAB_TRIGGER: {
                int wch = drawTabTextTile(gc, x, y, tileH, hPadding, cornerR, triggerChannelLabel());
                String trigCh = I18n.t(prefs.getOscTriggerChannel() == Channel.L
                                ? "scope.tab.left" : "scope.tab.right");
                addTabRegion(x, y, wch, tileH, I18n.t("scope.tile.trigger.channel", trigCh));
                x += wch + gap;
                int wed = drawTabTextTile(gc, x, y, tileH, hPadding, cornerR, triggerEdgeLabel());
                addTabRegion(x, y, wed, tileH,
                        I18n.t(prefs.getOscTriggerEdge() == TriggerEdge.RISE
                                ? "scope.tile.trigger.edge.rise"
                                : "scope.tile.trigger.edge.fall"));
                x += wed + gap;
                int wmd = drawTabTextTile(gc, x, y, tileH, hPadding, cornerR, triggerModeLabel());
                String modeKey;
                switch (prefs.getOscTriggerMode()) {
                    case AUTO:   modeKey = "scope.tile.trigger.mode.auto";   break;
                    case NORMAL: modeKey = "scope.tile.trigger.mode.normal"; break;
                    case SINGLE: modeKey = "scope.tile.trigger.mode.single"; break;
                    default:     modeKey = null;
                }
                if (modeKey != null) {
                    addTabRegion(x, y, wmd, tileH, I18n.t(modeKey));
                }
                x += wmd + gap;
                if (prefs.isOscTriggerHysteresisEnabled()) {
                    int why = drawTabTextTile(gc, x, y, tileH, hPadding, cornerR,
                            triggerHysteresisLabel());
                    addTabRegion(x, y, why, tileH, I18n.t("scope.trigger.hysteresis.tooltip"));
                }
                break;
            }
        }

        gc.setFont(prevFont);
    }

    /** Draws a single rounded-rectangle text tile and returns its width. */
    private int drawTabTextTile(GC gc, int x, int y, int h, int padding, int corner, String text) {
        org.eclipse.swt.graphics.Point te = gc.textExtent(text);
        int w = te.x + 2 * padding;
        gc.setBackground(tabTileBg);
        gc.fillRoundRectangle(x, y, w, h, corner, corner);
        gc.setForeground(tabTileFg);
        gc.drawText(text, x + padding, y + (h - te.y) / 2, true);
        return w;
    }

    /** Lazily creates the colour / font resources the tab tiles need.
     *  Resources are disposed when the parent CTabFolder is disposed. */
    private void ensureTabResources() {
        Display d = toolbarTabs.getDisplay();
        if (tabTileLedColor == null) tabTileLedColor = new Color(d, 0xFF, 0x20, 0x20);
        if (tabTileBg       == null) tabTileBg       = new Color(d, 0xE0, 0xE0, 0xE0);
        if (tabTileFg       == null) tabTileFg       = new Color(d, 0x20, 0x20, 0x20);
        if (tabTileFont     == null) {
            org.eclipse.swt.graphics.FontData[] fd = toolbarTabs.getFont().getFontData();
            for (org.eclipse.swt.graphics.FontData f : fd) f.setHeight(10);
            tabTileFont = new Font(d, fd);
        }
    }

    /** Trigger-tile labels — read straight from the current preferences. */
    private static String triggerChannelLabel() {
        return Preferences.instance().getOscTriggerChannel() == Channel.L ? "L" : "R";
    }
    private static String triggerEdgeLabel() {
        return Preferences.instance().getOscTriggerEdge() == TriggerEdge.RISE ? "↑" : "↓";
    }
    private static String triggerModeLabel() {
        switch (Preferences.instance().getOscTriggerMode()) {
            case AUTO:   return "A";
            case NORMAL: return "N";
            case SINGLE: return "S";
            default:     return "?";
        }
    }
    /** "H 0.5" — short label for the trigger hysteresis tile (only drawn
     *  when hysteresis is enabled).  Formatted with {@link Locale#ROOT} so
     *  the decimal separator is a period across all platforms. */
    private static String triggerHysteresisLabel() {
        return String.format(Locale.ROOT, "H %.1f",
                Preferences.instance().getOscTriggerHysteresisDiv());
    }
    /** Records a painted region for hover-tooltip dispatch.  Called by
     *  {@link #drawTabTiles} once per drawn tile, and by the paint listener
     *  once per tab for the label area. */
    private void addTabRegion(int x, int y, int w, int h, String tooltip) {
        if (tooltip == null || w <= 0 || h <= 0) return;
        tabRegions.add(new TabRegion(new org.eclipse.swt.graphics.Rectangle(x, y, w, h), tooltip));
    }

    /** i18n key for the tab-level hover tooltip — used as a fallback in
     *  the MouseMove listener when the cursor isn't over any tile. */
    private static String tabLabelTooltipKey(int tabIndex) {
        switch (tabIndex) {
            case TAB_LEFT:       return "scope.tab.left.tooltip";
            case TAB_RIGHT:      return "scope.tab.right.tooltip";
            case TAB_HORIZONTAL: return "scope.tab.horizontal.tooltip";
            case TAB_TRIGGER:    return "scope.tab.trigger.tooltip";
            case TAB_PRESETS:    return "scope.tab.presets.tooltip";
            case TAB_UTILITY:    return "scope.tab.utility.tooltip";
            case TAB_SAVE:       return "scope.tab.save.tooltip";
            case TAB_LOAD:       return "scope.tab.load.tooltip";
            default:             return null;
        }
    }

    /** Formats a volts-per-division value as a compact label (no "V/div"
     *  suffix, SI prefix u / m / none).  E.g. {@code 0.000050 → "50u"},
     *  {@code 0.020 → "20m"}, {@code 0.5 → "500m"}, {@code 1 → "1"}. */
    /** Toggles whether the toolbar CTabFolder shows its tab content area.
     *  When collapsed, only the tab strip is visible — clicking a tab still
     *  switches the active tab, but the controls underneath are hidden and
     *  the freed vertical space is given back to the oscilloscope view
     *  above.  The Record toggle button is also hidden while collapsed so
     *  the toolbar can shrink all the way to the tab-strip height (a tall
     *  Record LED would otherwise keep the row at ~60 px regardless of
     *  what the CTabFolder reports). */
    /** Forces the toolbar tab body collapsed (only headers visible) or
     *  expanded.  Used by the screenshot renderer to show just the tab
     *  headers; no-op when already in the requested state. */
    public void setToolbarTabsCollapsed(boolean wantCollapsed) {
        if (toolbarTabsCollapsed != wantCollapsed) toggleToolbarTabsCollapse();
    }

    private void toggleToolbarTabsCollapse() {
        if (toolbarTabs == null || toolbarTabs.isDisposed()) return;
        toolbarTabsCollapsed = !toolbarTabsCollapsed;

        // 1) CTabFolder height.  CTabFolder.computeSize(hHint) treats hHint
        //    as the CLIENT-AREA height and adds the strip trim on top, so
        //    passing heightHint=0 yields a total height of just the strip
        //    overhead (~tab header row).  Anything larger leaves a visible
        //    sliver of empty content area below the strip.
        GridData tabsGd = (GridData) toolbarTabs.getLayoutData();
        if (toolbarTabsCollapsed) {
            tabsGd.heightHint              = 0;
            tabsGd.verticalAlignment       = SWT.BEGINNING;
            tabsGd.grabExcessVerticalSpace = false;
        } else {
            tabsGd.heightHint              = SWT.DEFAULT;
            tabsGd.verticalAlignment       = SWT.FILL;
            tabsGd.grabExcessVerticalSpace = true;
        }

        // The Record button stays visible (and in the layout) at all times —
        // capture control needs to be reachable even when the tab content
        // is collapsed.  It now anchors to the top of the toolbar row so
        // it sits at the tab-strip level rather than centred against the
        // (now-shorter) row when collapsed.
        if (recordButton != null && !recordButton.isDisposed()) {
            Object rd = recordButton.getLayoutData();
            if (rd instanceof GridData rgd) {
                rgd.exclude = false;
                rgd.verticalAlignment = SWT.BEGINNING;
            }
            recordButton.setVisible(true);
        }

        // Cascade the layout pass: toolbar → group so the oscilloscope
        // view re-flows into / out of the freed vertical space.
        toolbar.layout(true, true);
        group.layout(true, true);
    }

    // -------------------------------------------------------------------------
    // Toolbar group builders
    // -------------------------------------------------------------------------

    private void buildLeftGroup(CTabFolder folder) {
        Composite g = groupCell(folder, I18n.t("scope.tab.left"));
        g.setLayout(rowLayoutHorizontal(6));

        Preferences prefs = Preferences.instance();

        leftToggle = squareToggle(g, "L");
        leftToggle.setToolTipText(I18n.t("scope.left.toggle.tooltip"));
        leftToggle.setSelection(prefs.isOscLeftChannelEnabled());
        leftToggle.addListener(SWT.Selection, e -> {
            prefs.setOscLeftChannelEnabled(leftToggle.getSelection());
            prefs.save();
            requestRedraw();
            refreshTabHeader(TAB_LEFT);
        });

        leftScale = new StepSelector(g, OscParse.voltsPerDivTargets(),
                prefs.getOscLeftVoltsPerDiv(), 90,
                OscParse::tryParseStepInput, OscParse::formatVoltsPerDiv);
        leftScale.setToolTipText(I18n.t("scope.left.scale.tooltip"));
        leftScale.addSelectionListener(e -> {
            double oldV = prefs.getOscLeftVoltsPerDiv();
            double newV = leftScale.getValue();
            // Keep the voltage at the canvas vertical centre fixed when
            // the V/div changes — feels like "zoom around the middle"
            // rather than "rescale everything from y=0".
            prefs.setOscLeftOffsetFrac(
                    ScopeFormat.preserveCanvasMiddle(prefs.getOscLeftOffsetFrac(), oldV, newV));
            prefs.setOscLeftVoltsPerDiv(newV);
            prefs.save();
            requestRedraw();
            refreshTabHeader(TAB_LEFT);
        });

        leftAc = squareToggle(g, "AC");
        leftAc.setToolTipText(I18n.t("scope.left.ac.tooltip"));
        leftAc.setSelection(prefs.isOscLeftAcMode());
        leftAc.addListener(SWT.Selection, e -> {
            prefs.setOscLeftAcMode(leftAc.getSelection());
            prefs.save();
            requestRedraw();
            refreshTabHeader(TAB_LEFT);
        });

        Image sincImg = createSincFractionImage(g.getDisplay());
        leftSinc = new Button(g, SWT.CHECK);
        leftSinc.setImage(sincImg);
        leftSinc.setToolTipText(I18n.t("scope.sinc.tooltip"));
        leftSinc.setSelection(prefs.isOscLeftSincInterpEnabled());
        leftSinc.addListener(SWT.Selection, e -> {
            prefs.setOscLeftSincInterpEnabled(leftSinc.getSelection());
            prefs.save();
            requestRedraw();
            refreshTabHeader(TAB_LEFT);
        });
        leftSinc.addDisposeListener(e -> sincImg.dispose());

        new Label(g, SWT.NONE).setText(I18n.t("scope.mains.label"));
        leftMains = new Combo(g, SWT.READ_ONLY);
        leftMains.setItems(MainsSuppression.LABELS);
        leftMains.setToolTipText(I18n.t("scope.mains.tooltip"));
        leftMains.select(prefs.getOscLeftMainsSuppression().ordinal());
        leftMains.addListener(SWT.Selection, e -> {
            int i = leftMains.getSelectionIndex();
            if (i >= 0 && i < MainsSuppression.values().length) {
                prefs.setOscLeftMainsSuppression(MainsSuppression.values()[i]);
                prefs.save();
                requestRedraw();
                refreshTabHeader(TAB_LEFT);
            }
        });

        new Label(g, SWT.NONE).setText(I18n.t("scope.lpf.label"));
        leftLpf = new Combo(g, SWT.READ_ONLY);
        leftLpf.setItems(LpfMode.LABELS);
        leftLpf.setToolTipText(I18n.t("scope.lpf.tooltip"));
        leftLpf.select(prefs.getOscLeftLpf().ordinal());
        leftLpf.addListener(SWT.Selection, e -> {
            int i = leftLpf.getSelectionIndex();
            if (i >= 0 && i < LpfMode.values().length) {
                prefs.setOscLeftLpf(LpfMode.values()[i]);
                prefs.save();
                requestRedraw();
                refreshTabHeader(TAB_LEFT);
            }
        });
    }

    private void buildRightGroup(CTabFolder folder) {
        Composite g = groupCell(folder, I18n.t("scope.tab.right"));
        g.setLayout(rowLayoutHorizontal(6));

        Preferences prefs = Preferences.instance();

        rightToggle = squareToggle(g, "R");
        rightToggle.setToolTipText(I18n.t("scope.right.toggle.tooltip"));
        rightToggle.setSelection(prefs.isOscRightChannelEnabled());
        rightToggle.addListener(SWT.Selection, e -> {
            prefs.setOscRightChannelEnabled(rightToggle.getSelection());
            prefs.save();
            requestRedraw();
            refreshTabHeader(TAB_RIGHT);
        });

        rightScale = new StepSelector(g, OscParse.voltsPerDivTargets(),
                prefs.getOscRightVoltsPerDiv(), 90,
                OscParse::tryParseStepInput, OscParse::formatVoltsPerDiv);
        rightScale.setToolTipText(I18n.t("scope.right.scale.tooltip"));
        rightScale.addSelectionListener(e -> {
            double oldV = prefs.getOscRightVoltsPerDiv();
            double newV = rightScale.getValue();
            prefs.setOscRightOffsetFrac(
                    ScopeFormat.preserveCanvasMiddle(prefs.getOscRightOffsetFrac(), oldV, newV));
            prefs.setOscRightVoltsPerDiv(newV);
            prefs.save();
            requestRedraw();
            refreshTabHeader(TAB_RIGHT);
        });

        rightAc = squareToggle(g, "AC");
        rightAc.setToolTipText(I18n.t("scope.right.ac.tooltip"));
        rightAc.setSelection(prefs.isOscRightAcMode());
        rightAc.addListener(SWT.Selection, e -> {
            prefs.setOscRightAcMode(rightAc.getSelection());
            prefs.save();
            requestRedraw();
            refreshTabHeader(TAB_RIGHT);
        });

        Image sincImg = createSincFractionImage(g.getDisplay());
        rightSinc = new Button(g, SWT.CHECK);
        rightSinc.setImage(sincImg);
        rightSinc.setToolTipText(I18n.t("scope.sinc.tooltip"));
        rightSinc.setSelection(prefs.isOscRightSincInterpEnabled());
        rightSinc.addListener(SWT.Selection, e -> {
            prefs.setOscRightSincInterpEnabled(rightSinc.getSelection());
            prefs.save();
            requestRedraw();
            refreshTabHeader(TAB_RIGHT);
        });
        rightSinc.addDisposeListener(e -> sincImg.dispose());

        new Label(g, SWT.NONE).setText(I18n.t("scope.mains.label"));
        rightMains = new Combo(g, SWT.READ_ONLY);
        rightMains.setItems(MainsSuppression.LABELS);
        rightMains.setToolTipText(I18n.t("scope.mains.tooltip"));
        rightMains.select(prefs.getOscRightMainsSuppression().ordinal());
        rightMains.addListener(SWT.Selection, e -> {
            int i = rightMains.getSelectionIndex();
            if (i >= 0 && i < MainsSuppression.values().length) {
                prefs.setOscRightMainsSuppression(MainsSuppression.values()[i]);
                prefs.save();
                requestRedraw();
                refreshTabHeader(TAB_RIGHT);
            }
        });

        new Label(g, SWT.NONE).setText(I18n.t("scope.lpf.label"));
        rightLpf = new Combo(g, SWT.READ_ONLY);
        rightLpf.setItems(LpfMode.LABELS);
        rightLpf.setToolTipText(I18n.t("scope.lpf.tooltip"));
        rightLpf.select(prefs.getOscRightLpf().ordinal());
        rightLpf.addListener(SWT.Selection, e -> {
            int i = rightLpf.getSelectionIndex();
            if (i >= 0 && i < LpfMode.values().length) {
                prefs.setOscRightLpf(LpfMode.values()[i]);
                prefs.save();
                requestRedraw();
                refreshTabHeader(TAB_RIGHT);
            }
        });
    }

    private void buildHorizontalGroup(CTabFolder folder) {
        Composite g = groupCell(folder, "Horizontal");
        g.setLayout(rowLayoutHorizontal(6));

        Preferences prefs = Preferences.instance();
        timeScale = new StepSelector(g, OscParse.timePerDivTargets(),
                prefs.getOscTimePerDiv(), 90,
                OscParse::tryParseStepInput, OscParse::formatTimePerDiv);
        timeScale.setToolTipText(I18n.t("scope.time.scale.tooltip"));
        timeScale.addSelectionListener(e -> {
            // viewCenterFrames is the primary state and does NOT change
            // on t/div — only the window's width changes.  applyViewState
            // re-derives slider thumb + position so the centred frame
            // stays put.  In live-record mode viewCenterFrames is -1
            // (follow-latest) so the trace continues tracking writePos.
            prefs.setOscTimePerDiv(timeScale.getValue());
            prefs.save();
            applyViewState();
            refreshTabHeader(TAB_HORIZONTAL);
        });

        // Zero-width, SQUARE_BUTTON-tall spacer so the row measures at the
        // same 32-px height as the V-group's button row.
        Composite rowSpacer = new Composite(g, SWT.NONE);
        rowSpacer.setLayoutData(new RowData(0, SQUARE_BUTTON));
    }

    private void buildTriggerGroup(CTabFolder folder, Image bluePlayLit, Runnable onSingleStart) {
        Composite g = groupCell(folder, "Trigger");
        triggerGroup = g;
        g.setLayout(rowLayoutHorizontal(10));

        Preferences prefs = Preferences.instance();

        Composite chSet = new Composite(g, SWT.NONE);
        chSet.setLayout(flushRowLayoutHorizontal(2));
        chL = squareToggle(chSet, "L");
        chR = squareToggle(chSet, "R");
        chL.setToolTipText(I18n.t("scope.trigger.channel.left.tooltip"));
        chR.setToolTipText(I18n.t("scope.trigger.channel.right.tooltip"));
        chL.setSelection(prefs.getOscTriggerChannel() == Channel.L);
        chR.setSelection(prefs.getOscTriggerChannel() == Channel.R);
        makeDependentGroup(chL, chR);
        chL.addListener(SWT.Selection,
                e -> { if (chL.getSelection()) { prefs.setOscTriggerChannel(Channel.L); prefs.save(); refreshTabHeader(TAB_TRIGGER); } });
        chR.addListener(SWT.Selection,
                e -> { if (chR.getSelection()) { prefs.setOscTriggerChannel(Channel.R); prefs.save(); refreshTabHeader(TAB_TRIGGER); } });

        Composite edgeSet = new Composite(g, SWT.NONE);
        edgeSet.setLayout(flushRowLayoutHorizontal(2));
        edgeRise = squareToggle(edgeSet, "↑");
        edgeFall = squareToggle(edgeSet, "↓");
        edgeRise.setToolTipText(I18n.t("scope.trigger.edge.rise.tooltip"));
        edgeFall.setToolTipText(I18n.t("scope.trigger.edge.fall.tooltip"));
        edgeRise.setSelection(prefs.getOscTriggerEdge() == TriggerEdge.RISE);
        edgeFall.setSelection(prefs.getOscTriggerEdge() == TriggerEdge.FALL);
        makeDependentGroup(edgeRise, edgeFall);
        edgeRise.addListener(SWT.Selection,
                e -> { if (edgeRise.getSelection()) { prefs.setOscTriggerEdge(TriggerEdge.RISE); prefs.save(); refreshTabHeader(TAB_TRIGGER); } });
        edgeFall.addListener(SWT.Selection,
                e -> { if (edgeFall.getSelection()) { prefs.setOscTriggerEdge(TriggerEdge.FALL); prefs.save(); refreshTabHeader(TAB_TRIGGER); } });

        Composite modeSet = new Composite(g, SWT.NONE);
        modeSet.setLayout(flushRowLayoutHorizontal(2));
        modeAuto   = squareToggle(modeSet, "A");
        modeNormal = squareToggle(modeSet, "N");
        modeSingle = squareToggle(modeSet, "S");
        modeAuto  .setToolTipText(I18n.t("scope.trigger.auto"));
        modeNormal.setToolTipText(I18n.t("scope.trigger.normal"));
        modeSingle.setToolTipText(I18n.t("scope.trigger.single"));
        modeAuto  .setSelection(prefs.getOscTriggerMode() == TriggerMode.AUTO);
        modeNormal.setSelection(prefs.getOscTriggerMode() == TriggerMode.NORMAL);
        modeSingle.setSelection(prefs.getOscTriggerMode() == TriggerMode.SINGLE);
        makeDependentGroup(modeAuto, modeNormal, modeSingle);
        modeAuto.addListener(SWT.Selection,
                e -> { if (modeAuto.getSelection())   { prefs.setOscTriggerMode(TriggerMode.AUTO);   prefs.save(); refreshTabHeader(TAB_TRIGGER); } });
        modeNormal.addListener(SWT.Selection,
                e -> { if (modeNormal.getSelection()) { prefs.setOscTriggerMode(TriggerMode.NORMAL); prefs.save(); refreshTabHeader(TAB_TRIGGER); } });
        modeSingle.addListener(SWT.Selection,
                e -> { if (modeSingle.getSelection()) { prefs.setOscTriggerMode(TriggerMode.SINGLE); prefs.save(); refreshTabHeader(TAB_TRIGGER); } });

        triggerStartBtn = new Button(g, SWT.PUSH);
        Image triggerPlayIcon = IconUtils.instance().renderAtHeight(g.getDisplay(),
                SvgPaths.PLAY, SQUARE_BUTTON - 12, null);
        triggerStartBtn.setImage(triggerPlayIcon);
        // Image is cached and owned by IconUtils — disposed when the
        // main shell tears down, not on button dispose.
        triggerStartBtn.setToolTipText(I18n.t("scope.trigger.start.tooltip"));
        triggerStartBtn.setLayoutData(new RowData(SQUARE_BUTTON, SQUARE_BUTTON));
        triggerStartBtn.setEnabled(modeSingle.getSelection());
        triggerStartBtn.addListener(SWT.Selection, e -> onSingleStart.run());

        modeAuto  .addListener(SWT.Selection, e -> syncTriggerStart());
        modeNormal.addListener(SWT.Selection, e -> syncTriggerStart());
        modeSingle.addListener(SWT.Selection, e -> syncTriggerStart());

        // Hysteresis selector — 0.1-div steps from 0.0 (disabled) to 5.0 div.
        // Moved out of the global Preferences dialog so it lives alongside
        // the other trigger controls.  Checkbox gates whether hysteresis is
        // applied at all; selector value is preserved when toggled off.
        Composite hystSet = new Composite(g, SWT.NONE);
        hystSet.setLayout(flushRowLayoutHorizontal(4));

        // Checkbox carries its own "Hysteresis" text so clicking the
        // label toggles the checkbox itself instead of doing nothing
        // (or accidentally focussing the numeric field next to it).
        hysteresisEnable = new Button(hystSet, SWT.CHECK);
        hysteresisEnable.setText(I18n.t("scope.trigger.hysteresis"));
        hysteresisEnable.setSelection(prefs.isOscTriggerHysteresisEnabled());
        hysteresisEnable.setToolTipText(I18n.t("scope.trigger.hysteresis.tooltip"));

        String[] hystValues = ScopeFormat.hysteresisDivSteps();
        hysteresisSel = new StepSelector(hystSet, hystValues,
                ScopeFormat.nearestIndex(hystValues, prefs.getOscTriggerHysteresisDiv()), 50);
        hysteresisSel.setToolTipText(I18n.t("scope.trigger.hysteresis.tooltip"));
        hysteresisSel.setEnabled(prefs.isOscTriggerHysteresisEnabled());
        hysteresisSel.addSelectionListener(e -> {
            prefs.setOscTriggerHysteresisDiv(Double.parseDouble(hysteresisSel.getSelectedValue()));
            prefs.save();
            refreshTabHeader(TAB_TRIGGER);
        });
        hysteresisEnable.addListener(SWT.Selection, e -> {
            boolean on = hysteresisEnable.getSelection();
            prefs.setOscTriggerHysteresisEnabled(on);
            prefs.save();
            hysteresisSel.setEnabled(on);
            refreshTabHeader(TAB_TRIGGER);
        });

        // "Reconstructed beat" overlay toggle — paints the |F1-F2|
        // envelope on top of the live trace in the trigger channel's
        // colour.  Active only in DUAL_TONE; disabled (greyed) on
        // every other waveform.  Subscribed to
        // GENERATOR_SIGNAL_CHANGED below so toggling the generator
        // form live updates the enabled state.
        reconstructedBeatBtn = new Button(g, SWT.CHECK);
        reconstructedBeatBtn.setText(I18n.t("scope.trigger.reconstructedBeat"));
        reconstructedBeatBtn.setToolTipText(I18n.t("scope.trigger.reconstructedBeat.tooltip"));
        reconstructedBeatBtn.setSelection(prefs.isOscShowReconstructedBeat());
        reconstructedBeatBtn.setEnabled(isGeneratorDualTone());
        reconstructedBeatBtn.addListener(SWT.Selection, e -> {
            prefs.setOscShowReconstructedBeat(reconstructedBeatBtn.getSelection());
            prefs.save();
            requestRedraw();
        });
    }

    /** True when the generator is currently in {@code DUAL_TONE} form
     *  (drives the "Reconstructed beat" checkbox's enabled state). */
    private boolean isGeneratorDualTone() {
        return Preferences.instance().getGenSignalForm() == GenSignalForm.DUAL_TONE;
    }

    /** Re-evaluates {@link #reconstructedBeatBtn}'s enabled state from
     *  the current generator form.  Called on every
     *  {@link Events#GENERATOR_SIGNAL_CHANGED} so toggling the form
     *  combo greys / un-greys the checkbox without restarting the
     *  pane. */
    private void syncReconstructedBeatEnabled() {
        if (reconstructedBeatBtn == null || reconstructedBeatBtn.isDisposed()) return;
        reconstructedBeatBtn.setEnabled(isGeneratorDualTone());
    }

    /**
     * Presets tab — editable name combo + Save / Load buttons.  Save reads
     * the current values of every preset-tracked control into a new
     * {@link OscPreset} and writes it under the typed name (or
     * overwrites if the name already exists).  Load reads back the named
     * preset and pushes every value into the UI controls and prefs.
     */
    private void buildPresetsGroup(CTabFolder folder) {
        Composite g = groupCell(folder, I18n.t("scope.tab.presets"));
        GridLayout gl = new GridLayout(4, false);
        gl.marginWidth = 6; gl.marginHeight = 4;
        gl.horizontalSpacing = 6;
        g.setLayout(gl);

        Preferences prefs = Preferences.instance();
        presetCombo = new Combo(g, SWT.DROP_DOWN);  // editable so user can type a new name
        presetCombo.setToolTipText(I18n.t("scope.presets.combo.tooltip"));
        GridData comboGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        comboGd.widthHint = 180;
        presetCombo.setLayoutData(comboGd);
        for (String name : prefs.getOscPresets().keySet()) presetCombo.add(name);
        presetCombo.addListener(SWT.Modify,    e -> refreshPresetButtonState());
        presetCombo.addListener(SWT.Selection, e -> refreshPresetButtonState());

        presetSaveBtn = new Button(g, SWT.PUSH);
        presetSaveBtn.setText(I18n.t("scope.presets.save"));
        presetSaveBtn.setToolTipText(I18n.t("scope.presets.save.tooltip"));
        presetSaveBtn.addListener(SWT.Selection, e -> {
            String name = presetCombo.getText().trim();
            if (name.isEmpty()) return;
            // Existing preset → ask before overwriting.
            if (prefs.getOscPresets().containsKey(name) && !confirmOverwritePreset(name)) return;
            prefs.putOscPreset(name, captureCurrentOscPreset());
            if (presetCombo.indexOf(name) < 0) presetCombo.add(name);
            presetCombo.setText(name);
            refreshPresetButtonState();
        });

        presetLoadBtn = new Button(g, SWT.PUSH);
        presetLoadBtn.setText(I18n.t("scope.presets.load"));
        presetLoadBtn.setToolTipText(I18n.t("scope.presets.load.tooltip"));
        presetLoadBtn.addListener(SWT.Selection, e -> {
            String name = presetCombo.getText().trim();
            if (name.isEmpty()) return;
            OscPreset p = prefs.getOscPresets().get(name);
            if (p != null) {
                applyOscPreset(p);
                refreshPresetButtonState();
            }
        });

        presetDeleteBtn = new Button(g, SWT.PUSH);
        presetDeleteBtn.setText(I18n.t("scope.presets.delete"));
        presetDeleteBtn.setToolTipText(I18n.t("scope.presets.delete.tooltip"));
        presetDeleteBtn.addListener(SWT.Selection, e -> {
            String name = presetCombo.getText().trim();
            if (name.isEmpty() || !prefs.getOscPresets().containsKey(name)) return;
            if (!confirmDeletePreset(name)) return;
            prefs.removeOscPreset(name);
            int idx = presetCombo.indexOf(name);
            if (idx >= 0) presetCombo.remove(idx);
            presetCombo.setText("");
            refreshPresetButtonState();
        });

        // Initial state + low-frequency poll so the Save button reacts to
        // setting changes made via the canvas / other tabs while this tab
        // is visible.  Polling stops automatically when the combo is
        // disposed (timerExec is a no-op on disposed widgets).
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

    /** Recomputes the enable state of Save / Delete from the current combo
     *  text + matching preset (if any) + current control values.  Safe to
     *  call before {@link #presetCombo} is built (no-op). */
    private void refreshPresetButtonState() {
        if (presetCombo == null || presetCombo.isDisposed()) return;
        if (presetSaveBtn   == null || presetSaveBtn  .isDisposed()) return;
        if (presetLoadBtn   == null || presetLoadBtn  .isDisposed()) return;
        if (presetDeleteBtn == null || presetDeleteBtn.isDisposed()) return;
        String name = presetCombo.getText().trim();
        if (name.isEmpty()) {
            presetSaveBtn  .setEnabled(false);
            presetLoadBtn  .setEnabled(false);
            presetDeleteBtn.setEnabled(false);
            return;
        }
        OscPreset existing = Preferences.instance().getOscPresets().get(name);
        if (existing == null) {
            // New name — Save is the way to create it; nothing to load/delete.
            presetSaveBtn  .setEnabled(true);
            presetLoadBtn  .setEnabled(false);
            presetDeleteBtn.setEnabled(false);
        } else {
            // Existing — Save only if current settings differ; Load + Delete always.
            presetSaveBtn  .setEnabled(!existing.equals(captureCurrentOscPreset()));
            presetLoadBtn  .setEnabled(true);
            presetDeleteBtn.setEnabled(true);
        }
    }

    /** Yes/No confirmation prompt for overwriting an existing preset.
     *  Returns true when the user picks Yes. */
    private boolean confirmOverwritePreset(String name) {
        return Dialogs.confirm(group.getShell(),
                I18n.t("scope.presets.overwrite.title"),
                I18n.t("scope.presets.overwrite.message", name)) == SWT.YES;
    }

    /** Yes/No confirmation prompt for deleting a preset. */
    private boolean confirmDeletePreset(String name) {
        return Dialogs.confirm(group.getShell(),
                I18n.t("scope.presets.delete.title"),
                I18n.t("scope.presets.delete.message", name)) == SWT.YES;
    }

    /** Reads every preset-tracked value from the live {@link Preferences}
     *  into a new {@link OscPreset}. */
    private OscPreset captureCurrentOscPreset() {
        Preferences prefs = Preferences.instance();
        OscPreset p = new OscPreset();
        p.setLeftChannelEnabled(prefs.isOscLeftChannelEnabled());
        p.setRightChannelEnabled(prefs.isOscRightChannelEnabled());
        p.setLeftAcMode(prefs.isOscLeftAcMode());
        p.setRightAcMode(prefs.isOscRightAcMode());
        p.setLeftSincInterpEnabled(prefs.isOscLeftSincInterpEnabled());
        p.setRightSincInterpEnabled(prefs.isOscRightSincInterpEnabled());
        p.setLeftMainsSuppression(prefs.getOscLeftMainsSuppression());
        p.setRightMainsSuppression(prefs.getOscRightMainsSuppression());
        p.setLeftLpf(prefs.getOscLeftLpf());
        p.setRightLpf(prefs.getOscRightLpf());
        p.setLeftVoltsPerDiv(prefs.getOscLeftVoltsPerDiv());
        p.setRightVoltsPerDiv(prefs.getOscRightVoltsPerDiv());
        p.setLeftOffsetFrac(prefs.getOscLeftOffsetFrac());
        p.setRightOffsetFrac(prefs.getOscRightOffsetFrac());
        p.setTimePerDiv(prefs.getOscTimePerDiv());
        p.setTriggerPositionFrac(prefs.getOscTriggerPositionFrac());
        p.setTriggerChannel(prefs.getOscTriggerChannel());
        p.setTriggerEdge(prefs.getOscTriggerEdge());
        p.setTriggerMode(prefs.getOscTriggerMode());
        p.setTriggerLevelFrac(prefs.getOscTriggerLevelFrac());
        return p;
    }

    /**
     * Auto-setup: picks a t/div that fits ~1.5 periods on screen and a V/div
     * that makes the signal span ~0.75 of the vertical range, then resets
     * all offsets and the trigger level to centre.  Same V/div is applied
     * to both channels (driven by the measurement channel's Vpp).  Wired
     * to the Auto-Setup button in {@link OscilloscopeView}'s header.
     *
     * <p>No-op when the measurement worker has not published a frequency
     * or Vpp yet (capture not running, or the warm-up window is still
     * in effect).
     */
    /** Re-applies the "Start button enabled iff Single mode is selected"
     *  rule.  Called from the trigger-mode radio listeners and after a
     *  bulk-enable of {@link #triggerGroup} restores Record mode. */
    private void syncTriggerStart() {
        if (triggerStartBtn == null || triggerStartBtn.isDisposed()) return;
        triggerStartBtn.setEnabled(modeSingle != null && modeSingle.getSelection());
    }

    private void performAutoSetup() {
        if (view == null || view.isDisposed()) return;
        // Refuse to auto-setup when the scope isn't actively recording.
        // Otherwise the requestRedraw() at the end of this method pulls
        // whatever the shared SignalBuffer happens to hold (e.g. when
        // the FFT pane is recording and the audio device is open) and
        // paints it into the scope view — which looks like the scope
        // captured a signal even though the user never pressed Record.
        if (controller == null || !controller.isRunning()) return;
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
            double targetTDiv = period * 1.5 / OscilloscopeView.DIVISIONS_X;
            double newTDiv    = ScopeFormat.ceilToStep(targetTDiv, OscParse.timePerDivTargets());
            if (timeScale != null && !timeScale.isDisposed()) timeScale.setValue(newTDiv);
        }
        if (Double.isFinite(vpp) && vpp > 0) {
            double targetVDiv = vpp / (OscilloscopeView.DIVISIONS_Y * 0.75);
            double newVDiv    = ScopeFormat.ceilToStep(targetVDiv, OscParse.voltsPerDivTargets());
            if (leftScale  != null && !leftScale .isDisposed()) leftScale .setValue(newVDiv);
            if (rightScale != null && !rightScale.isDisposed()) rightScale.setValue(newVDiv);
        }
        // Reset all offsets and the trigger level to "0" (frac = 0.5 = centre).
        prefs.setOscLeftOffsetFrac     (0.5);
        prefs.setOscRightOffsetFrac    (0.5);
        prefs.setOscTriggerPositionFrac(0.5);
        prefs.setOscTriggerLevelFrac   (0.5);
        prefs.save();
        requestRedraw();
    }


    /** Pushes every preset value into the live {@link Preferences} AND the
     *  UI widgets.  {@code StepSelector.setValue} fires its selection
     *  listener (which writes vDiv/tDiv + adjusts offsetFrac via
     *  preserveCanvasMiddle), so we re-write the offsetFracs / triggerPos
     *  AFTER the scale updates so the preset values win.  Button widgets
     *  use {@code setSelection} which does NOT fire listeners, so we update
     *  prefs explicitly for those. */
    private void applyOscPreset(OscPreset p) {
        Preferences prefs = Preferences.instance();
        // Scales — fire listeners that adjust offsetFrac; we'll overwrite below.
        if (leftScale  != null && !leftScale .isDisposed()) leftScale .setValue(p.getLeftVoltsPerDiv());
        if (rightScale != null && !rightScale.isDisposed()) rightScale.setValue(p.getRightVoltsPerDiv());
        if (timeScale  != null && !timeScale .isDisposed()) timeScale .setValue(p.getTimePerDiv());
        // Channel toggles + AC + sinc.
        prefs.setOscLeftChannelEnabled (p.isLeftChannelEnabled());
        prefs.setOscRightChannelEnabled(p.isRightChannelEnabled());
        prefs.setOscLeftAcMode  (p.isLeftAcMode());
        prefs.setOscRightAcMode (p.isRightAcMode());
        prefs.setOscLeftSincInterpEnabled (p.isLeftSincInterpEnabled());
        prefs.setOscRightSincInterpEnabled(p.isRightSincInterpEnabled());
        prefs.setOscLeftMainsSuppression (p.getLeftMainsSuppression());
        prefs.setOscRightMainsSuppression(p.getRightMainsSuppression());
        prefs.setOscLeftLpf (p.getLeftLpf());
        prefs.setOscRightLpf(p.getRightLpf());
        if (leftToggle  != null && !leftToggle .isDisposed()) leftToggle .setSelection(p.isLeftChannelEnabled());
        if (rightToggle != null && !rightToggle.isDisposed()) rightToggle.setSelection(p.isRightChannelEnabled());
        if (leftAc      != null && !leftAc     .isDisposed()) leftAc     .setSelection(p.isLeftAcMode());
        if (rightAc     != null && !rightAc    .isDisposed()) rightAc    .setSelection(p.isRightAcMode());
        if (leftSinc    != null && !leftSinc   .isDisposed()) leftSinc   .setSelection(p.isLeftSincInterpEnabled());
        if (rightSinc   != null && !rightSinc  .isDisposed()) rightSinc  .setSelection(p.isRightSincInterpEnabled());
        if (leftMains   != null && !leftMains  .isDisposed()) leftMains  .select(p.getLeftMainsSuppression().ordinal());
        if (rightMains  != null && !rightMains .isDisposed()) rightMains .select(p.getRightMainsSuppression().ordinal());
        if (leftLpf     != null && !leftLpf    .isDisposed()) leftLpf    .select(p.getLeftLpf().ordinal());
        if (rightLpf    != null && !rightLpf   .isDisposed()) rightLpf   .select(p.getRightLpf().ordinal());
        // Trigger channel / edge / mode radio groups.
        prefs.setOscTriggerChannel(p.getTriggerChannel());
        prefs.setOscTriggerEdge   (p.getTriggerEdge());
        prefs.setOscTriggerMode   (p.getTriggerMode());
        if (chL        != null && !chL       .isDisposed()) chL       .setSelection(p.getTriggerChannel() == Channel.L);
        if (chR        != null && !chR       .isDisposed()) chR       .setSelection(p.getTriggerChannel() == Channel.R);
        if (edgeRise   != null && !edgeRise  .isDisposed()) edgeRise  .setSelection(p.getTriggerEdge()    == TriggerEdge.RISE);
        if (edgeFall   != null && !edgeFall  .isDisposed()) edgeFall  .setSelection(p.getTriggerEdge()    == TriggerEdge.FALL);
        if (modeAuto   != null && !modeAuto  .isDisposed()) modeAuto  .setSelection(p.getTriggerMode()    == TriggerMode.AUTO);
        if (modeNormal != null && !modeNormal.isDisposed()) modeNormal.setSelection(p.getTriggerMode()    == TriggerMode.NORMAL);
        if (modeSingle != null && !modeSingle.isDisposed()) modeSingle.setSelection(p.getTriggerMode()    == TriggerMode.SINGLE);
        syncTriggerStart();
        // Fractions — overwrite the values the scale listeners would have
        // clobbered.  Goes last so the preset wins.
        prefs.setOscLeftOffsetFrac     (p.getLeftOffsetFrac());
        prefs.setOscRightOffsetFrac    (p.getRightOffsetFrac());
        prefs.setOscTriggerPositionFrac(p.getTriggerPositionFrac());
        prefs.setOscTriggerLevelFrac   (p.getTriggerLevelFrac());
        prefs.save();
        refreshTabHeader(TAB_LEFT);
        refreshTabHeader(TAB_RIGHT);
        refreshTabHeader(TAB_HORIZONTAL);
        refreshTabHeader(TAB_TRIGGER);
        requestRedraw();
    }

    /**
     * Two-button group: screenshot (camera) + ADC calibration (crosshair).
     * The group title is omitted; both buttons sit on the same row, the
     * camera button keeps its 1.27 : 1 aspect (SVG-driven), the crosshair
     * button is square (h = w = SQUARE_BUTTON).  Calibrate starts disabled
     * and is enabled / disabled in {@link #setRecordingState(boolean)}.
     */
    private void buildScreenshotGroup(CTabFolder folder, Image cameraIcon, Image crosshairIcon) {
        Composite g = groupCell(folder, "Utility");
        g.setLayout(rowLayoutHorizontal(6));
        Button shotBtn = new Button(g, SWT.PUSH);
        shotBtn.setImage(cameraIcon);
        shotBtn.setToolTipText(I18n.t("scope.screenshot.tooltip"));
        int shotW = (int) Math.round(SQUARE_BUTTON * 1.27);
        shotBtn.setLayoutData(new RowData(shotW, SQUARE_BUTTON));
        shotBtn.addListener(SWT.Selection, e -> openScreenshotDialog());

        calibrateButton = new Button(g, SWT.PUSH);
        calibrateButton.setImage(crosshairIcon);
        calibrateButton.setToolTipText(I18n.t("scope.calibrate.tooltip"));
        calibrateButton.setLayoutData(new RowData(SQUARE_BUTTON, SQUARE_BUTTON));
        calibrateButton.setEnabled(false);
        calibrateButton.addListener(SWT.Selection, e -> openCalibrationDialog());
        // Periodic gate poll — keeps the button state honest as the
        // recording starts / stops, the file-mode flag flips, and the
        // measured Vpp moves above / below the 25 % threshold.
        scheduleCalibrateButtonRefresh();
    }

    /**
     * Second-row "Save to…" group: path text + browse (folder-open) +
     * duration step field + save (floppy-disk).  Clear button removed
     * — the user's spec calls for a minimal three-control group.
     */
    private void buildScopeSaveToGroup(CTabFolder folder) {
        Preferences prefs = Preferences.instance();
        Composite g = groupCell(folder, "Save to…");
        GridLayout gl = new GridLayout(4, false);
        gl.marginWidth = 6; gl.marginHeight = 4;
        gl.horizontalSpacing = 4;
        g.setLayout(gl);

        Text pathField = new Text(g, SWT.BORDER | SWT.READ_ONLY);
        String savedSavePath = prefs.getOscSavePath();
        pathField.setText(savedSavePath != null ? savedSavePath : "");
        if (savedSavePath != null && !savedSavePath.isEmpty()) {
            pathField.setToolTipText(savedSavePath);
        }
        pathField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button browse = new Button(g, SWT.PUSH);
        browse.setImage(folderOpenIcon);
        browse.setToolTipText(I18n.t("scope.save.browse.tooltip"));
        browse.addListener(SWT.Selection, e -> openScopeSaveBrowse(pathField));

        NumericStepField durField = new NumericStepField(
                g,
                Math.max(0.001, prefs.getOscSaveDurationSeconds()),
                ScopeFormat::parseSeconds,
                ScopeFormat::formatSecondsTrimmed,
                /* wheel: ±5 % */ (v, dir) -> Math.max(0.001, v * (1.0 + 0.05 * dir)),
                /* arrows: ±1 s */ (v, dir) -> Math.max(0.001, v + dir),
                54);     // 60% of the previous 90 px width
        durField.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        durField.setToolTipText(I18n.t("scope.save.duration.tooltip"));
        durField.addSelectionListener(e -> {
            prefs.setOscSaveDurationSeconds(durField.getValue());
            prefs.save();
        });

        Button save = new Button(g, SWT.PUSH);
        save.setImage(floppyDiskIcon);
        save.setToolTipText(I18n.t("scope.save.tooltip"));
        save.addListener(SWT.Selection, e -> doScopeSave(pathField.getText(), durField.getValue()));
    }

    /**
     * Second-row "Open signal…" group: read-only path text + single
     * browse button.  Picking a file in the dialog immediately decodes
     * it into a fresh buffer (no streaming thread, no measurement-
     * worker coordination), stops live recording if running, and
     * attaches the static waveform to the scope views.
     */
    private void buildScopeOpenSignalGroup(CTabFolder folder) {
        Preferences prefs = Preferences.instance();
        Composite g = groupCell(folder, "Load signal…");
        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 6; gl.marginHeight = 4;
        gl.horizontalSpacing = 4;
        g.setLayout(gl);

        openSignalPathField = new Text(g, SWT.BORDER | SWT.READ_ONLY);
        String savedPath = prefs.getOscPlayFromPath();
        openSignalPathField.setText(savedPath != null ? savedPath : "");
        if (savedPath != null && !savedPath.isEmpty()) {
            openSignalPathField.setToolTipText(savedPath);
        }
        openSignalPathField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button browse = new Button(g, SWT.PUSH);
        browse.setImage(folderOpenIcon);
        browse.setToolTipText(I18n.t("scope.openSignal.browse.tooltip"));
        browse.addListener(SWT.Selection, e -> doOpenSignalBrowse());
    }

    /**
     * Opens the file picker; on a successful pick stops the live
     * recording (if any) and loads the file synchronously via
     * {@link ScopeOpenSignal#loadFile}.  Surfaces decode failure as a
     * MessageBox.  No-op when the user cancels.
     */
    private void doOpenSignalBrowse() {
        Preferences prefs = Preferences.instance();
        FileDialog fd = new FileDialog(group.getShell(), SWT.OPEN);
        fd.setText(I18n.t("scope.openSignal.dialog"));
        fd.setFilterExtensions(new String[] { "*.wav;*.flac;*.aiff;*.aif", "*.wav", "*.flac", "*.aiff;*.aif", "*.*" });
        fd.setFilterNames     (new String[] {
                "All audio (*.wav, *.flac, *.aiff, *.aif)",
                "WAV files (*.wav)", "FLAC files (*.flac)",
                "AIFF files (*.aiff, *.aif)", "All files" });
        if (prefs.getOscPlayFromFolder() != null) fd.setFilterPath(prefs.getOscPlayFromFolder());
        String picked = fd.open();
        if (picked == null) return;

        // Open-signal and Record share the scope buffer — stop the live
        // capture before swapping the buffer out from under it.
        if (controller != null && controller.isRunning()) {
            controller.stop();
            recordButton.setSelection(false);
            recordButton.setImage(recordDim);
            if (calibrateButton != null && !calibrateButton.isDisposed()) {
                calibrateButton.setEnabled(false);
            }
        }

        File pickedFile = new File(picked);
        boolean ok = (loader != null) && loader.loadFile(pickedFile);
        if (ok) {
            openSignalPathField.setText(picked);
            openSignalPathField.setToolTipText(picked);
            prefs.setOscPlayFromPath(picked);
            File parent = pickedFile.getParentFile();
            if (parent != null) prefs.setOscPlayFromFolder(parent.getAbsolutePath());
            prefs.save();
            // Centre the view on the start of the loaded signal so the
            // first frames are visible.  Slider is enabled only in file
            // mode — in live record it's disabled so user can't scroll
            // away from the live tip and break trigger.
            SignalBufferReader reader = view.getReader();
            if (reader != null) {
                int displaySamples = ScopeFormat.displaySamplesFor(prefs.getOscTimePerDiv(), reader.getSampleRate());
                viewCenterFrames = displaySamples / 2.0;
            }
            setNavSliderVisible(true);
            // File mode: trigger has no meaning, disable the whole
            // trigger group so the user can't accidentally toggle
            // settings that don't apply to a static signal.  SWT's
            // Group.setEnabled doesn't always grey out nested toggle
            // children visually on Windows — walk the tree and disable
            // each control explicitly.
            setSubtreeEnabled(triggerGroup, false);
            applyViewState();
            view.setFilePath(picked);
        } else {
            String err = (loader != null) ? loader.getLastError() : "Loader not available.";
            Dialogs.error(group.getShell(),
                    I18n.t("scope.openSignal.error"),
                    err != null ? err : "Unknown error opening the file.");
        }
    }

    /**
     * Minimum peak-to-peak signal amplitude (as a fraction of the ADC's
     * full-scale p-p) required for the calibrate button to be active.
     * Below this the signal occupies too little of the ADC's range and a
     * calibration based on it would be dominated by quantisation /
     * background noise.  Matches the "0.25 FS p-p" gate from the polish
     * spec.
     */
    private static final double CALIBRATE_MIN_VPP_FRACTION = 0.25;

    /** Updates {@link #calibrateButton}'s enabled state based on all
     *  three gating conditions: capture running, not in file mode, and
     *  the current measurement-channel Vpp ≥ {@link #CALIBRATE_MIN_VPP_FRACTION}
     *  of the ADC full-scale Vpp.  Called periodically from
     *  {@link #scheduleCalibrateButtonRefresh} while recording. */
    private void refreshCalibrateButtonEnabled() {
        if (calibrateButton == null || calibrateButton.isDisposed()) return;
        boolean running = (controller != null) && controller.isRunning();
        boolean fileMode = view.isFileMode();
        boolean enable = running && !fileMode;
        if (enable) {
            double vpp = view.getLastVpp();
            double fsVpp = 2.0 * AudioBackend.getAdcFsVoltageRms() * Math.sqrt(2.0);
            enable = !Double.isNaN(vpp) && fsVpp > 0.0
                  && (vpp / fsVpp) >= CALIBRATE_MIN_VPP_FRACTION;
        }
        if (calibrateButton.getEnabled() != enable) {
            calibrateButton.setEnabled(enable);
        }
    }

    /** Polls the calibrate-button gate every ~250 ms.  Self-cancels when
     *  the button is disposed; runs forever otherwise (the gate cost is
     *  trivial — a volatile read + one float compare).  Also re-syncs
     *  the vertical scrollbar so an L/R measurement-channel switch made
     *  inside the canvas is reflected in the slider position. */
    private void scheduleCalibrateButtonRefresh() {
        if (calibrateButton == null || calibrateButton.isDisposed()) return;
        calibrateButton.getDisplay().timerExec(250, () -> {
            if (calibrateButton == null || calibrateButton.isDisposed()) return;
            refreshCalibrateButtonEnabled();
            syncVertSliderFromPrefs();
            scheduleCalibrateButtonRefresh();
        });
    }

    /**
     * Maps the nav slider's current selection to a back-offset (in
     * frames) and pushes it to both views.  Slider rightmost (selection
     * = max − thumb) maps to offset 0 — follow the latest writePos.
     * Leftmost (selection = 0) maps to offset = buffer capacity —
     * showing the oldest sample still resident in the ring.  No-op when
     * no buffer is attached yet.
     */
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
     * Single source of truth for everything that depends on the view's
     * centre.  Reads {@link #viewCenterFrames} (the absolute frame
     * under the canvas centre — {@code -1} for follow-latest) and
     * derives slider thumb size, slider position, the main view's
     * back-offset and the condensed view's back-offset.  Always ends
     * with a redraw.
     */
    private void applyViewState() {
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
     * Walks the widget tree rooted at {@code root} and toggles
     * {@link Control#setEnabled(boolean)} on every descendant.  SWT's
     * Group.setEnabled doesn't always propagate the visual disabled
     * state to nested toggle buttons on Windows; explicit per-control
     * setEnabled does.  No-op if root is null / disposed.
     */
    private void setSubtreeEnabled(Control root, boolean enabled) {
        if (root == null || root.isDisposed()) return;
        root.setEnabled(enabled);
        if (root instanceof Composite) {
            for (Control child : ((Composite) root).getChildren()) {
                setSubtreeEnabled(child, enabled);
            }
        }
    }

    /**
     * Forces both scope canvases to repaint.  Required for file-mode
     * (openSignal) sessions where there's no periodic capture redraw
     * timer to pick up UI changes (V/div, t/div, slider, AC, channel
     * toggles).  Cheap no-op during live recording — the controller's
     * 1 ms redraw timer would have repainted on its own next tick.
     */
    private void requestRedraw() {
        // Keep the vertical scrollbar in sync with the measurement-channel
        // offset on every redraw — covers cases where the channel was
        // changed inside OscilloscopeView (L/R click) without the pane
        // explicitly seeing it.
        syncVertSliderFromPrefs();
        if (!view.isDisposed())      view.redraw();
        if (!condensed.isDisposed()) condensed.redraw();
    }

    /**
     * Steps both visible channels' V/div selectors by {@code delta} (negative
     * = zoom in / smaller V/div, positive = zoom out).  Each call drives the
     * value-mode StepSelector to the next standard 1-2-5-10 value above /
     * below the current free-form value; the existing selection listener
     * then persists the result into Preferences.
     */
    private void stepVoltsPerDiv(int delta) {
        Preferences prefs = Preferences.instance();
        if (leftScale != null && !leftScale.isDisposed() && prefs.isOscLeftChannelEnabled()) {
            leftScale.setValue(ScopeFormat.nextTargetFrom(prefs.getOscLeftVoltsPerDiv(),
                    OscParse.voltsPerDivTargets(), delta));
        }
        if (rightScale != null && !rightScale.isDisposed() && prefs.isOscRightChannelEnabled()) {
            rightScale.setValue(ScopeFormat.nextTargetFrom(prefs.getOscRightVoltsPerDiv(),
                    OscParse.voltsPerDivTargets(), delta));
        }
    }

    /** Steps the t/div selector by {@code delta}.  Same routing as {@link #stepVoltsPerDiv}. */
    private void stepTimePerDiv(int delta) {
        if (timeScale == null || timeScale.isDisposed()) return;
        timeScale.setValue(ScopeFormat.nextTargetFrom(Preferences.instance().getOscTimePerDiv(),
                OscParse.timePerDivTargets(), delta));
    }

    /** V/div zoom anchored at mouse Y: after the zoom, the same voltage that
     *  was under the mouse before the zoom is still under the mouse.  Per
     *  channel — each channel's V/div + offsetFrac are adjusted using that
     *  channel's voltage-at-mouse, since the two channels can have different
     *  scales.  Note: the {@code *Scale} selection listeners overwrite
     *  offsetFrac with {@code ScopeFormat.preserveCanvasMiddle(...)} (center-anchored
     *  zoom).  We re-apply the mouse-anchored offsetFrac AFTER
     *  {@code setValue(...)} so our value wins and the redraw scheduled by
     *  the listener picks it up. */
    /** Wires the four-mode mouse-wheel handler on the scope canvas.
     *  Matched to the mental model of a hardware scope's "position" /
     *  "scale" knobs:
     *  <ul>
     *    <li>plain wheel        → vertical offset (everything in sync)</li>
     *    <li>Shift + wheel      → horizontal offset (time scroll / trig pos)</li>
     *    <li>Ctrl  + wheel      → vertical scale (V/div) on both channels</li>
     *    <li>Shift + Ctrl + wheel → horizontal scale (t/div)</li>
     *  </ul> */
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
                stepTimePerDivAround(-dir, e.x, area.width);
            } else if (ctrl) {
                // Ctrl + wheel: V/div zoom around the mouse Y (wheel up →
                // step DOWN in V/div).
                stepVoltsPerDivAround(-dir, e.y, area.height);
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

    private void stepVoltsPerDivAround(int delta, int mouseY, int height) {
        if (height <= 0) { stepVoltsPerDiv(delta); return; }
        Preferences prefs = Preferences.instance();
        double mouseFrac = (double) mouseY / height;
        if (leftScale != null && !leftScale.isDisposed() && prefs.isOscLeftChannelEnabled()) {
            double vDivOld = prefs.getOscLeftVoltsPerDiv();
            double vDivNew = ScopeFormat.nextTargetFrom(vDivOld, OscParse.voltsPerDivTargets(), delta);
            if (vDivNew != vDivOld) {
                double offOld = prefs.getOscLeftOffsetFrac();
                double vAtMouse = (offOld - mouseFrac) * OscilloscopeView.DIVISIONS_Y * vDivOld;
                leftScale.setValue(vDivNew);
                prefs.setOscLeftOffsetFrac(
                        mouseFrac + vAtMouse / (OscilloscopeView.DIVISIONS_Y * vDivNew));
                prefs.save();
                requestRedraw();
            }
        }
        if (rightScale != null && !rightScale.isDisposed() && prefs.isOscRightChannelEnabled()) {
            double vDivOld = prefs.getOscRightVoltsPerDiv();
            double vDivNew = ScopeFormat.nextTargetFrom(vDivOld, OscParse.voltsPerDivTargets(), delta);
            if (vDivNew != vDivOld) {
                double offOld = prefs.getOscRightOffsetFrac();
                double vAtMouse = (offOld - mouseFrac) * OscilloscopeView.DIVISIONS_Y * vDivOld;
                rightScale.setValue(vDivNew);
                prefs.setOscRightOffsetFrac(
                        mouseFrac + vAtMouse / (OscilloscopeView.DIVISIONS_Y * vDivNew));
                prefs.save();
                requestRedraw();
            }
        }
    }

    /** t/div zoom anchored at mouse X: after the zoom, the same frame that
     *  was under the mouse before the zoom is still under the mouse.  In
     *  trigger mode the horizontal anchor is {@code triggerPositionFrac}
     *  (the trigger sample is placed at {@code triggerPosFrac × w}), so
     *  the frame at the mouse is
     *  {@code lastTriggerAbsPos + (mouseFrac - posFrac) × displaySamples}.
     *  Solving for the new {@code posFrac} (with displaySamples ∝ t/div)
     *  gives the formula below.  Clamped to [0, 1] because the renderer
     *  clamps too, so an off-screen trigger would lose the anchor on the
     *  next zoom step. */
    private void stepTimePerDivAround(int delta, int mouseX, int width) {
        if (width <= 0 || timeScale == null || timeScale.isDisposed()) {
            stepTimePerDiv(delta);
            return;
        }
        Preferences prefs = Preferences.instance();
        double tDivOld = prefs.getOscTimePerDiv();
        double tDivNew = ScopeFormat.nextTargetFrom(tDivOld, OscParse.timePerDivTargets(), delta);
        if (tDivNew == tDivOld) return;
        double mouseFrac = (double) mouseX / width;
        double posOld    = prefs.getOscTriggerPositionFrac();
        double posNew    = mouseFrac - (mouseFrac - posOld) * (tDivOld / tDivNew);
        if (posNew < 0.0) posNew = 0.0;
        if (posNew > 1.0) posNew = 1.0;
        prefs.setOscTriggerPositionFrac(posNew);
        timeScale.setValue(tDivNew);
    }

    /** Vertical-offset wheel step size as a fraction of the channel's
     *  full vertical range — 5 % per wheel tick (≈ ½ a division). */
    private static final double WHEEL_OFFSET_STEP_FRAC = 0.05;

    /** Nudges both channel offsets by one wheel tick.  Wheel up (dir = +1)
     *  moves the signal UP on screen, which means the offset fraction
     *  DECREASES (lower offsetFrac = trace anchored higher on the grid;
     *  see the {@code maxV / minV} math in
     *  {@link OscilloscopeView#drawEdgeLabels}).  Trigger level stays put
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
        if (view.isFileMode()) {
            SignalBufferReader reader = view.getReader();
            if (reader == null) return;
            int displaySamples = ScopeFormat.displaySamplesFor(
                    Preferences.instance().getOscTimePerDiv(), reader.getSampleRate());
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
            Preferences prefs = Preferences.instance();
            double cur = prefs.getOscTriggerPositionFrac();
            double next = ScopeFormat.clamp01(cur + dir * WHEEL_OFFSET_STEP_FRAC);
            if (next == cur) return;
            prefs.setOscTriggerPositionFrac(next);
            prefs.save();
            requestRedraw();
        }
    }

    /** Returns the offsetFrac value that, after the V/div changes from
     *  {@code oldVpdiv} to {@code newVpdiv}, places the same voltage at
     *  the canvas vertical centre as before.
     *
     *  <p>Math: voltage at the canvas middle = {@code (offsetFrac − 0.5) ·
     *  Y · V/div}.  Holding that voltage constant across the V/div
     *  transition gives
     *  {@code newOffsetFrac = (oldOffsetFrac − 0.5) · oldV / newV + 0.5}. */
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
        double fs    = AudioBackend.getAdcFsVoltageRms() * Math.sqrt(2.0);
        double half  = (vpdiv <= 0 || fs <= 0)
                ? 0.5
                : fs / (OscilloscopeView.DIVISIONS_Y * vpdiv);
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
                    (int) Math.round(unitsPerOffset / (5.0 * OscilloscopeView.DIVISIONS_Y)));
            int pageStep  = Math.max(arrowStep,
                    (int) Math.round(unitsPerOffset * 5.0 / OscilloscopeView.DIVISIONS_Y));
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


    /** Opens a Save-As dialog for the scope output file. */
    private void openScopeSaveBrowse(Text pathField) {
        Preferences prefs = Preferences.instance();
        FileDialog fd = new FileDialog(group.getShell(), SWT.SAVE);
        fd.setText(I18n.t("scope.save.dialog"));
        fd.setFilterExtensions(new String[] { "*.wav", "*.flac", "*.aiff;*.aif", "*.*" });
        fd.setFilterNames     (new String[] {
                "WAV files (*.wav)", "FLAC files (*.flac)",
                "AIFF files (*.aiff, *.aif)", "All files" });
        fd.setOverwrite(true);
        if (prefs.getOscSaveFolder() != null) fd.setFilterPath(prefs.getOscSaveFolder());
        fd.setFileName("scope.wav");
        String picked = fd.open();
        if (picked == null) return;
        String lower = picked.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".wav") || lower.endsWith(".flac")
              || lower.endsWith(".aiff") || lower.endsWith(".aif"))) {
            int idx = fd.getFilterIndex();
            String ext = (idx == 1) ? ".flac" : (idx == 2) ? ".aiff" : ".wav";
            picked += ext;
        }
        pathField.setText(picked);
        pathField.setToolTipText(picked);
        prefs.setOscSavePath(picked);
        File parent = new File(picked).getParentFile();
        if (parent != null) prefs.setOscSaveFolder(parent.getAbsolutePath());
        prefs.save();
    }

    /** Performs the scope-buffer save, surfacing errors as a MessageBox. */
    private void doScopeSave(String path, double durationSeconds) {
        if (path == null || path.isEmpty()) {
            Dialogs.info(group.getShell(),
                    I18n.t("scope.save.dialog"),
                    I18n.t("scope.save.pickFirst"));
            return;
        }
        Preferences prefs = Preferences.instance();
        int sampleRate = prefs.current().getInputSampleRate();
        int bitDepth   = prefs.current().getInputBitDepth();
        // A request longer than the capture ring can hold (and not a loaded
        // file) ⇒ record FORWARD to disk in real time (streaming) instead of
        // dumping the ring.  Otherwise dump the most-recent N seconds instantly.
        SignalBufferReader reader = view.getReader();
        long requestedFrames = Math.max(1L, Math.round(durationSeconds * (double) sampleRate));
        if (!view.isFileMode() && (reader == null || requestedFrames > reader.getCapacity())) {
            startStreamingSave(path, sampleRate, bitDepth, requestedFrames);
            return;
        }
        // Pass the latest detected fundamental so ScopeFileSaver can
        // snap the file length to whole signal periods.  NaN = scope
        // hasn't produced a measurement yet (saves raw duration).
        double freqHz = view.getLastFrequencyHz();
        try {
            long bytes = ScopeFileSaver.save(reader,
                    new File(path), sampleRate, bitDepth, durationSeconds, freqHz);
            log.info("Scope saved: {} ({} bytes)", path, bytes);
        } catch (Exception ex) {
            log.warn("Scope save failed", ex);
            Dialogs.error(group.getShell(),
                    I18n.t("scope.save.error"),
                    I18n.t("scope.save.error.message", path, ex.getMessage()));
        }
    }

    /**
     * Records {@code totalFrames} of LIVE capture straight to {@code path} in
     * real time (for captures longer than the ring buffer), showing a modal
     * progress dialog with a Cancel.  Acquires its own capture reference for
     * the duration (opening the device if the scope isn't already recording),
     * streams on a daemon thread via {@link StereoPcmIo#saveStreaming}, and
     * releases when done or cancelled.
     */
    private void startStreamingSave(String path, int sampleRate, int bitDepth, long totalFrames) {
        SignalBufferReader reader = MessageBus.instance().request(Events.CAPTURE_ACQUIRE);
        if (reader == null) {
            Dialogs.error(group.getShell(), I18n.t("scope.save.error"),
                    I18n.t("scope.save.error.message", path,
                            SharedCapture.instance().getLastStartError()));
            return;
        }
        double totalSeconds = totalFrames / (double) sampleRate;

        // Floating tool window: on-top so it stays visible, but NOT modal — the
        // user can keep watching the scope / FFT while it records.
        Shell dlg = new Shell(group.getShell(), SWT.TOOL | SWT.TITLE | SWT.CLOSE | SWT.ON_TOP);
        dlg.setText(I18n.t("scope.save.recording.title"));
        dlg.setLayout(new GridLayout(1, false));
        Label lbl = new Label(dlg, SWT.NONE);
        lbl.setText(I18n.t("scope.save.recording.progress", "0.0",
                String.format(Locale.ROOT, "%.1f", totalSeconds)));
        GridData lblGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        lblGd.widthHint = 300;
        lbl.setLayoutData(lblGd);
        ProgressBar bar = new ProgressBar(dlg, SWT.HORIZONTAL | SWT.SMOOTH);
        bar.setMinimum(0);
        bar.setMaximum(1000);
        bar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button cancelBtn = new Button(dlg, SWT.PUSH);
        cancelBtn.setText(I18n.t("common.cancel"));
        cancelBtn.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
        AtomicBoolean cancelled = new AtomicBoolean(false);
        cancelBtn.addListener(SWT.Selection, e -> cancelled.set(true));
        dlg.addListener(SWT.Close, e -> cancelled.set(true));
        dlg.pack();
        // Centre on the parent window.
        Rectangle pb = group.getShell().getBounds();
        Point ds = dlg.getSize();
        dlg.setLocation(pb.x + (pb.width - ds.x) / 2, pb.y + (pb.height - ds.y) / 2);
        dlg.open();

        Display display = group.getDisplay();
        Thread t = new Thread(() -> {
            Exception err = null;
            try {
                StereoPcmIo.saveStreaming(reader, new File(path), sampleRate, bitDepth, totalFrames,
                        cancelled::get,
                        written -> {
                            if (display.isDisposed()) return;
                            display.asyncExec(() -> {
                                if (bar.isDisposed()) return;
                                bar.setSelection((int) Math.min(1000L, 1000L * written / totalFrames));
                                lbl.setText(I18n.t("scope.save.recording.progress",
                                        String.format(Locale.ROOT, "%.1f", written / (double) sampleRate),
                                        String.format(Locale.ROOT, "%.1f", totalSeconds)));
                            });
                        });
            } catch (Exception ex) {
                err = ex;
                log.warn("Scope stream-record failed", ex);
            } finally {
                MessageBus.instance().publish(Events.CAPTURE_RELEASE);
            }
            final Exception fe = err;
            if (display.isDisposed()) return;
            display.asyncExec(() -> {
                if (!dlg.isDisposed()) dlg.close();
                if (fe != null) {
                    Dialogs.error(group.getShell(), I18n.t("scope.save.error"),
                            I18n.t("scope.save.error.message", path, fe.getMessage()));
                }
            });
        }, "scope-stream-record");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Paints a small image of {@code sin x / x} as a stacked fraction —
     * "sin x" over a horizontal bar over "x".  Used as the label for the
     * Vertical-group checkbox that toggles Lanczos sinc reconstruction.
     */
    private Image createSincFractionImage(Display display) {
        int w = 40, h = 28;
        Image img = new Image(display, w, h);
        GC gc = new GC(img);
        gc.setAntialias(SWT.ON);
        gc.setTextAntialias(SWT.ON);
        gc.setBackground(display.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
        gc.fillRectangle(0, 0, w, h);
        gc.setForeground(display.getSystemColor(SWT.COLOR_WIDGET_FOREGROUND));

        Font font = new Font(display, "Serif", 9, SWT.ITALIC);
        gc.setFont(font);
        Point topSize = gc.textExtent("sin x");
        Point botSize = gc.textExtent("x");
        int barY = h / 2;
        int inset = 3;
        gc.drawText("sin x", (w - topSize.x) / 2, barY - topSize.y - 1, true);
        gc.drawLine(inset, barY, w - 1 - inset, barY);
        gc.drawText("x", (w - botSize.x) / 2, barY + 1, true);
        font.dispose();
        gc.dispose();
        return img;
    }

    // -------------------------------------------------------------------------
    // Layout / widget helpers (duplicated from MainWindow because the
    // oscilloscope pane has its own complete tree of widgets and doesn't
    // share state with the Generator / FFT panes).
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

    private RowLayout rowLayoutHorizontal(int spacing) {
        RowLayout rl = new RowLayout(SWT.HORIZONTAL);
        rl.spacing = spacing;
        rl.center  = true;
        // GTK pads native buttons more than win32; without wrap=false the
        // second button on tight toolbar rows drops onto a new line and
        // the parent Group grows vertically to fit (see Linux layout bug
        // in OscilloscopePane screenshot / calibrate group).
        rl.wrap    = false;
        return rl;
    }

    private RowLayout flushRowLayoutHorizontal(int spacing) {
        RowLayout rl = new RowLayout(SWT.HORIZONTAL);
        rl.spacing = spacing;
        rl.center  = true;
        rl.wrap    = false;
        rl.marginLeft   = 0;
        rl.marginRight  = 0;
        rl.marginTop    = 0;
        rl.marginBottom = 0;
        return rl;
    }

    /** Builds a tab in {@code folder} with the given title and returns the
     *  content Composite (used as the {@link CTabItem#setControl} target).
     *  The content composite has no explicit layoutData — a CTabFolder
     *  always sizes its tab content to fill the available area, so the
     *  build*Group methods just call {@code setLayout(rowLayoutHorizontal(…))}
     *  on the returned composite and parent their children there. */
    private Composite groupCell(CTabFolder folder, String title) {
        Composite c = new Composite(folder, SWT.NONE);
        CTabItem item = new CTabItem(folder, SWT.NONE);
        item.setText(title);
        item.setControl(c);
        return c;
    }

    private Button squareToggle(Composite parent, String label) {
        Button b = new Button(parent, SWT.TOGGLE);
        b.setText(label);
        b.setLayoutData(new RowData(SQUARE_BUTTON, SQUARE_BUTTON));
        return b;
    }

    private void makeDependentGroup(Button... buttons) {
        for (Button b : buttons) {
            b.addListener(SWT.Selection, e -> {
                if (!b.getSelection()) {
                    b.setSelection(true);
                    return;
                }
                for (Button other : buttons) {
                    if (other != b) other.setSelection(false);
                }
            });
        }
    }
}
