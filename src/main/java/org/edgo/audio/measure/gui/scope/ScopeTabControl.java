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

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
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
import org.edgo.audio.measure.enums.LpfMode;
import org.edgo.audio.measure.enums.MainsSuppression;
import org.edgo.audio.measure.enums.TriggerEdge;
import org.edgo.audio.measure.enums.TriggerMode;
import org.edgo.audio.measure.gui.bind.Bindings;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.AbstractTabControl;
import org.edgo.audio.measure.gui.common.Dialogs;
import org.edgo.audio.measure.gui.common.Icon;
import org.edgo.audio.measure.gui.common.IconUtils;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.sound.SharedCapture;
import org.edgo.audio.measure.gui.sound.SignalBufferReader;
import org.edgo.audio.measure.gui.widgets.NumericStepField;
import org.edgo.audio.measure.gui.widgets.PresetBar;
import org.edgo.audio.measure.gui.widgets.TileTabFolder;
import org.edgo.audio.measure.gui.widgets.UnitFamily;
import org.edgo.audio.measure.preferences.OscPreset;
import org.edgo.audio.measure.preferences.Preferences;

import lombok.extern.log4j.Log4j2;

/**
 * Self-contained oscilloscope toolbar control: a {@link TileTabFolder} carrying
 * the Left / Right channel, Horizontal, Trigger, Presets, Utility, and
 * (live-capture only) Save-to / Open-signal tabs, with all of their preference
 * bindings, the V/T scale selectors, the trigger controls, the preset machinery
 * and the {@code .wav}/{@code .flac} save + open-signal file flows.
 *
 * <p>The host {@link ScopePane} keeps the chart (main + condensed views), the
 * navigation / vertical scrollbars and the Record button.  The cross-boundary
 * concerns are funnelled through {@link Host} — a narrow, <em>per-pane</em>
 * collaborator the pane implements — rather than a global bus, because a scope
 * redraw / view-state recompute must target the one pane whose setting changed
 * (the screenshot renderer constructs a second, offscreen pane that must stay
 * independent).  The pane drives the few widgets it must reach (the scale
 * selectors on a mouse-wheel zoom or auto-setup, the trigger group on a
 * record / file-load switch, the calibrate button on the capture gate) through
 * this control's small public command API.
 */
@Log4j2
public final class ScopeTabControl extends AbstractTabControl {

    /** The {@link ScopePane} operations this control invokes.  Implemented by
     *  the pane and passed in at construction so the tabs can request a chart
     *  redraw, a view-window recompute, or the capture-lifecycle steps a file
     *  load triggers — without the control holding a back-reference to the
     *  whole pane. */
    public interface Host {
        /** A setting changed → redraw both scope canvases and resync the sliders. */
        void requestRedraw();

        /** File mode horizontal zoom: re-centre the view so the sample under the
         *  mouse ({@code mouseFrac}) stays put as the window resizes for the new
         *  t/div.  A loaded file has no trigger, so its zoom moves the view centre. */
        void zoomFileTimeAroundMouse(double mouseFrac, double tDivOld, double tDivNew);

        /** The visible time window changed (t/div, scrub) → recompute the
         *  navigation-slider window and the view back-offsets. */
        void applyViewState();

        /** Stop live capture before an open-signal load swaps the buffer out:
         *  stops the controller and pops the Record button back off.  No-op
         *  when not recording. */
        void stopCaptureForFileLoad();

        /** A signal file finished loading → centre the view on its start, show
         *  the navigation slider (file mode) and apply the view state. */
        void onSignalFileLoaded();

        /** Open the pane's screenshot dialog (the pane clones itself offscreen
         *  to render the snapshot).  Implemented by {@code AbstractPane}. */
        void openScreenshotDialog();
    }

    /** Side length of every small in-group toggle button (px). */
    public  static final int SQUARE_BUTTON = 32;

    /** Vertical / horizontal resolution bounds (canonical V and s per div). */
    private static final double V_PER_DIV_MIN = 1e-9;
    private static final double V_PER_DIV_MAX = 500;
    private static final double T_PER_DIV_MIN = 1e-6;
    private static final double T_PER_DIV_MAX = 1.0;
    /** Trigger hysteresis: 0…5 divisions in 0.1-div steps, one decimal. */
    private static final double HYST_MAX_DIV  = 5;
    private static final double HYST_STEP_DIV = 0.1;
    /** Save-duration field bounds (s). */
    private static final double SAVE_DURATION_MIN_SEC = 0.001;
    private static final double TIME_MAX_SEC          = 1_000_000;
    // Display precision caps per the numeric-field spec.
    private static final int PER_DIV_MAX_DECIMALS = 3;
    private static final int TIME_MAX_DECIMALS    = 3;

    /** Tab indices we paint a custom header + tile row for. */
    private static final int TAB_LEFT       = 0;
    private static final int TAB_RIGHT      = 1;
    private static final int TAB_HORIZONTAL = 2;
    private static final int TAB_TRIGGER    = 3;
    private static final int TAB_PRESETS    = 4;
    private static final int TAB_UTILITY    = 5;
    private static final int TAB_SAVE       = 6;
    private static final int TAB_LOAD       = 7;
    /** Number of tabs that get custom rendering (label + tile row).  The
     *  remaining tabs (utility / save / load) render with the default
     *  CTabFolder rendering but still get tab-level hover tooltips. */
    private static final int NUM_CUSTOM_TABS = 4;

    private final ScopeView         view;
    /** Synchronous file loader for the "Open signal…" feature; {@code null} on
     *  the screenshot-only variant ({@code liveCapture = false}). */
    private final ScopeOpenSignal   loader;
    private final Host              host;

    // Cached references from IconUtils — owned by the shared cache and
    // disposed centrally when the main shell tears down.  (camera / crosshair
    // live on AbstractTabControl.)
    private final Image floppyDiskIcon;
    private final Image folderOpenIcon;

    // Toolbar tab folder: the shared TileTabFolder (held by AbstractTabControl
    // as toolbarTabs) owns the custom renderer, spacer images, tab-body
    // collapse, hover tooltips and tile painting; this control only supplies
    // each custom tab's tile content (see scopeTabTiles), tooltip and padding.

    /** Step selectors for V/div (left + right) and t/div — fields so the
     *  mouse-wheel zoom on the main scope (driven by the pane) can step them. */
    private NumericStepField             leftScale;
    private NumericStepField             rightScale;
    private NumericStepField             timeScale;
    private NumericStepField             hysteresisSel;
    private Button                       hysteresisEnable;
    /** "Reconstructed beat" overlay toggle — enabled only when the
     *  generator is in DUAL_TONE mode; field-promoted so the
     *  generator-form listener can flip its enabled state without
     *  rebuilding the tab. */
    private Button                       reconstructedBeatBtn;
    /** Per-channel and trigger control widgets — fields so the preset
     *  load path can push saved values back into the UI without having to
     *  rebuild the toolbar. */
    private Button leftToggle, rightToggle;
    private Button leftAc, rightAc;
    private Button leftSinc, rightSinc;
    private Combo  leftMains, rightMains;
    private Combo  leftLpf, rightLpf;
    private Button chL, chR;
    private Button edgeRise, edgeFall;
    private Button modeAuto, modeNormal, modeSingle;
    /** Reference to the trigger toolbar tab content so it can be enabled/disabled
     *  when switching between live record and file (openSignal) modes. */
    private Composite                    triggerGroup;
    /** "Start" button on the Trigger toolbar tab — enabled iff Single
     *  mode is selected.  Promoted to a field so {@link #syncTriggerStart}
     *  can re-apply the rule after a bulk-enable. */
    private Button                       triggerStartBtn;
    /** Calibrate button — gated by the pane's capture state via
     *  {@link #setCalibrateEnabled(boolean)}. */
    private Button                       calibrateButton;
    /** Read-only text field showing the currently loaded openSignal file path. */
    private Text                         openSignalPathField;

    /** Subscriber for {@link Events#GENERATOR_SIGNAL_CHANGED} — keeps the
     *  "Reconstructed beat" checkbox enabled only in DUAL_TONE mode.  Bound
     *  only on the live pane; {@code null} on the screenshot variant. */
    private Consumer<GenChangeCause>     genChangeListener;
    /** {@link Events#SCOPE_SINGLE_DISARMED} subscriber — pops the trigger Start
     *  toggle back out when a SINGLE shot fires.  Live pane only. */
    private Consumer<Void>               singleDisarmedListener;

    public ScopeTabControl(Composite parent, ScopeView view, ScopeOpenSignal loader,
                           Host host, boolean liveCapture) {
        super(parent, SWT.NONE);
        this.view   = view;
        this.loader = loader;
        this.host   = host;

        Display d = parent.getDisplay();
        this.floppyDiskIcon = IconUtils.icon(d, Icon.FLOPPY_DISK);
        this.folderOpenIcon = IconUtils.icon(d, Icon.FOLDER_OPEN);

        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 0; gl.marginHeight = 0;
        setLayout(gl);

        // CTabFolder (not native TabFolder) so the tab strip stays on a
        // single row even when the toolbar gets narrow — native TabFolder
        // on Win32 wraps the strip to a second row when the labels don't
        // fit, doubling the toolbar height.  CTabFolder shrinks tab labels
        // and shows scroll-chevrons instead.
        TileTabFolder tabs = new TileTabFolder(this, SWT.NONE);
        tabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        this.toolbarTabs = tabs;
        // Feed the folder this control's per-tab tile content, fallback tooltip
        // and extra padding (the channel tabs are tighter than the default).
        tabs.setCustomTabs(NUM_CUSTOM_TABS, new TileTabFolder.TileSource() {
            @Override
            public List<TileTabFolder.Tile> tilesFor(int tabIndex) {
                return scopeTabTiles(tabIndex);
            }
            @Override
            public String tabTooltip(int tabIndex) {
                String key = tabLabelTooltipKey(tabIndex);
                return key != null ? I18n.t(key) : null;
            }
            @Override
            public int extraPadding(int tabIndex) {
                return extraTabPadding(tabIndex);
            }
        });

        buildLeftGroup(tabs);
        buildRightGroup(tabs);
        buildHorizontalGroup(tabs);
        buildTriggerGroup(tabs);
        buildPresetsGroup(tabs);
        buildScreenshotGroup(tabs, cameraIcon, crosshairIcon);
        if (liveCapture) {
            buildScopeSaveToGroup(tabs);
            buildScopeOpenSignalGroup(tabs);
        }

        // Select the first tab BEFORE TileTabFolder.init() sets the strip
        // height — CTabFolder's preferred-size calculation includes the
        // selected tab's body in the total, and we want that body height
        // baked in when the first layout pass uses the taller strip height
        // (otherwise the body gets squeezed to 0 px on the first paint).
        if (tabs.getItemCount() > 0) tabs.setSelection(0);
        // Capture labels, size the strip / spacer images and wire the paint,
        // hover and collapse listeners now that the tabs exist.
        tabs.init();

        // Track generator-form changes so the "Reconstructed beat" checkbox
        // stays enabled only in DUAL_TONE mode without requiring a rebuild.
        // Only the live pane reacts — the offscreen screenshot variant keeps
        // its static state.
        if (liveCapture) {
            genChangeListener = cause -> {
                if (isDisposed()) return;
                getDisplay().asyncExec(() -> {
                    if (isDisposed()) return;
                    syncReconstructedBeatEnabled();
                    // A real generator change (not a sub-mHz FLL trim) invalidates the
                    // running scope statistics — drop them so avg/min/max start fresh.
                    if (cause == GenChangeCause.USER_INPUT) view.resetMeasurementHistory();
                });
            };
            MessageBus.instance().subscribe(Events.GENERATOR_SIGNAL_CHANGED, genChangeListener);
            singleDisarmedListener = ignored -> {
                if (!isDisposed()) getDisplay().asyncExec(() -> {
                    if (triggerStartBtn != null && !triggerStartBtn.isDisposed()) {
                        triggerStartBtn.setSelection(false);
                    }
                });
            };
            MessageBus.instance().subscribe(Events.SCOPE_SINGLE_DISARMED, singleDisarmedListener);
        }
        addDisposeListener(e -> {
            if (genChangeListener != null) {
                MessageBus.instance().unsubscribe(Events.GENERATOR_SIGNAL_CHANGED, genChangeListener);
            }
            if (singleDisarmedListener != null) {
                MessageBus.instance().unsubscribe(Events.SCOPE_SINGLE_DISARMED, singleDisarmedListener);
            }
        });

        wireHelpAnchors();
    }

    // -------------------------------------------------------------------------
    // Pane command API + delegation
    // -------------------------------------------------------------------------

    /** Registers each settings tab in the component registry under
     *  {@code prefix} (e.g. {@code "multifunctional/scope/tabs"}) so automation
     *  can select a tab by path — {@code activate} expands the tab body and
     *  selects that tab — then screenshot this tab control showing it.  Slugs
     *  are language-independent; tabs not built are skipped. */
    public void registerTabs(String prefix) {
        registerTab(prefix, "left",       TAB_LEFT);
        registerTab(prefix, "right",      TAB_RIGHT);
        registerTab(prefix, "horizontal", TAB_HORIZONTAL);
        registerTab(prefix, "trigger",    TAB_TRIGGER);
        registerTab(prefix, "presets",    TAB_PRESETS);
        registerTab(prefix, "utility",    TAB_UTILITY);
        registerTab(prefix, "save",       TAB_SAVE);
        registerTab(prefix, "load",       TAB_LOAD);
    }


    /** Sets the t/div selector (fires its listener → pref write + view-state
     *  recompute).  Used by the pane's auto-setup. */
    public void setTimePerDiv(double v) {
        if (timeScale != null && !timeScale.isDisposed()) timeScale.setValue(v);
    }

    /** Sets the left V/div selector (fires its listener → pref write + redraw). */
    public void setLeftVoltsPerDiv(double v) {
        if (leftScale != null && !leftScale.isDisposed()) leftScale.setValue(v);
    }

    /** Sets the right V/div selector (fires its listener → pref write + redraw). */
    public void setRightVoltsPerDiv(double v) {
        if (rightScale != null && !rightScale.isDisposed()) rightScale.setValue(v);
    }

    /** Enables / disables the whole trigger tab (record vs file mode).  When
     *  enabling, the blanket subtree-enable above turns ON every control, so
     *  re-apply the per-control gates it clobbered: Start only in Single mode,
     *  the hysteresis selector only when hysteresis is on, and Reconstructed
     *  beat only when the generator is in dual-tone form. */
    public void setTriggerControlsEnabled(boolean enabled) {
        setSubtreeEnabled(triggerGroup, enabled);
        if (enabled) {
            syncTriggerStart();
            syncReconstructedBeatEnabled();
            if (hysteresisSel != null && !hysteresisSel.isDisposed()) {
                hysteresisSel.setEnabled(Preferences.instance().isOscTriggerHysteresisEnabled());
            }
        }
    }

    /** Clears the open-signal path field — called when live recording starts
     *  and the loaded file is discarded. */
    public void clearOpenSignalPath() {
        if (openSignalPathField != null && !openSignalPathField.isDisposed()) {
            openSignalPathField.setText("");
            openSignalPathField.setToolTipText("");
        }
    }

    /** Drives the Utility-tab calibrate button's enabled state — the pane owns
     *  the capture gate that decides {@code enable}. */
    public void setCalibrateEnabled(boolean enable) {
        if (calibrateButton != null && !calibrateButton.isDisposed()
                && calibrateButton.getEnabled() != enable) {
            calibrateButton.setEnabled(enable);
        }
    }

    /** Tags every interactive toolbar widget with a {@code "helpAnchor"} so
     *  Ctrl+F1 can resolve the focused control to a specific section of
     *  {@code oscilloscope.html}.  Tab tiles are painted (not real widgets);
     *  the pane's chapter-level anchor on its group is the fallback. */
    private void wireHelpAnchors() {
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
        // Preset bar widgets carry their own help anchor (set by PresetBar).
    }

    // -------------------------------------------------------------------------
    // Tab tiles
    // -------------------------------------------------------------------------

    /** Builds the live tile row for a custom tab from the current
     *  preferences, in visual order (left to right).  Each tile carries its
     *  short chip text (or an LED dot) plus its state-aware hover tooltip;
     *  the {@link TileTabFolder} measures and paints them. */
    private List<TileTabFolder.Tile> scopeTabTiles(int tabIndex) {
        Preferences prefs = Preferences.instance();
        List<TileTabFolder.Tile> tiles = new ArrayList<>();
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
                    tiles.add(TileTabFolder.Tile.led(I18n.t("scope.tile.led.active", chName)));
                }
                tiles.add(TileTabFolder.Tile.text(ScopeFormat.shortVoltsPerDiv(vDiv),
                        I18n.t("scope.tile.scale", chName, OscParse.formatVoltsPerDiv(vDiv))));
                tiles.add(TileTabFolder.Tile.text(acMode ? "ac" : "dc",
                        I18n.t(acMode ? "scope.tile.coupling.ac" : "scope.tile.coupling.dc", chName)));
                tiles.add(TileTabFolder.Tile.text(sinc ? "sin" : "lin",
                        I18n.t(sinc ? "scope.tile.interp.sin" : "scope.tile.interp.lin", chName)));
                break;
            }
            case TAB_HORIZONTAL:
                tiles.add(TileTabFolder.Tile.text(
                        ScopeFormat.shortTimePerDiv(prefs.getOscTimePerDiv()),
                        I18n.t("scope.tile.time", OscParse.formatTimePerDiv(prefs.getOscTimePerDiv()))));
                break;
            case TAB_TRIGGER: {
                String trigCh = I18n.t(prefs.getOscTriggerChannel() == Channel.L
                        ? "scope.tab.left" : "scope.tab.right");
                tiles.add(TileTabFolder.Tile.text(triggerChannelLabel(),
                        I18n.t("scope.tile.trigger.channel", trigCh)));
                tiles.add(TileTabFolder.Tile.text(triggerEdgeLabel(),
                        I18n.t(prefs.getOscTriggerEdge() == TriggerEdge.RISE
                                ? "scope.tile.trigger.edge.rise"
                                : "scope.tile.trigger.edge.fall")));
                String modeKey;
                switch (prefs.getOscTriggerMode()) {
                    case AUTO:   modeKey = "scope.tile.trigger.mode.auto";   break;
                    case NORMAL: modeKey = "scope.tile.trigger.mode.normal"; break;
                    case SINGLE: modeKey = "scope.tile.trigger.mode.single"; break;
                    default:     modeKey = null;
                }
                tiles.add(TileTabFolder.Tile.text(triggerModeLabel(),
                        modeKey != null ? I18n.t(modeKey) : null));
                if (prefs.isOscTriggerHysteresisEnabled()) {
                    tiles.add(TileTabFolder.Tile.text(triggerHysteresisLabel(),
                            I18n.t("scope.trigger.hysteresis.tooltip")));
                }
                break;
            }
            case TAB_PRESETS: {
                // Show the number of saved presets when there are any — a hint
                // that there's something to load.
                int n = prefs.getOscPresets().size();
                if (n > 0) tiles.add(TileTabFolder.Tile.text(n + " saved",
                        I18n.t("scope.tile.presets", n)));
                break;
            }
        }
        return tiles;
    }

    /** Per-tab additional padding the user has dialled in.  Applied on top
     *  of {@code max(tilesW, labelW)}. */
    private int extraTabPadding(int tabIndex) {
        switch (tabIndex) {
            case TAB_LEFT:
            case TAB_RIGHT:      return 7;
            case TAB_HORIZONTAL: return 20;
            case TAB_TRIGGER:    return 7;
            default:             return 0;
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

    // -------------------------------------------------------------------------
    // Toolbar group builders
    // -------------------------------------------------------------------------

    private void buildLeftGroup(CTabFolder folder) {
        Composite g = groupCell(folder, I18n.t("scope.tab.left"));
        g.setLayout(rowLayoutHorizontal(6));

        Preferences prefs = Preferences.instance();

        leftToggle = squareToggle(g, "L");
        leftToggle.setToolTipText(I18n.t("scope.left.toggle.tooltip"));
        Bindings.check(leftToggle, prefs.oscLeftChannelEnabledProperty());
        Bindings.onChange(toolbarTabs, prefs.oscLeftChannelEnabledProperty(), v -> {
            host.requestRedraw();
            toolbarTabs.refreshTab(TAB_LEFT);
        });

        leftScale = new NumericStepField(g, UnitFamily.VOLTS_PER_DIV,
                V_PER_DIV_MIN, V_PER_DIV_MAX, OscParse.voltsPerDivTargets(),
                PER_DIV_MAX_DECIMALS, 90);
        leftScale.setValue(prefs.getOscLeftVoltsPerDiv());
        leftScale.setToolTipText(I18n.t("scope.left.scale.tooltip"));
        // Coupled write: the offset fraction is re-derived from the OLD V/div
        // (preserveCanvasMiddle needs the pre-change value), so this can't use
        // Bindings.stepSelector — the offset must be set before V/div changes
        // and no helper models that pairing.  Both prefs are now bound and
        // auto-save, so the explicit save() is retired; the preset-load path
        // keeps the selector in sync via leftScale.setValue(...).
        leftScale.addSelectionListener(e -> {
            double oldV = prefs.getOscLeftVoltsPerDiv();
            double newV = leftScale.getValue();
            // Keep the voltage at the canvas vertical centre fixed when
            // the V/div changes — feels like "zoom around the middle"
            // rather than "rescale everything from y=0".
            prefs.setOscLeftOffsetFrac(
                    ScopeFormat.preserveCanvasMiddle(prefs.getOscLeftOffsetFrac(), oldV, newV));
            prefs.setOscLeftVoltsPerDiv(newV);
            host.requestRedraw();
            toolbarTabs.refreshTab(TAB_LEFT);
        });
        // Wheel / arrows / typing on this field change ONLY this channel (via the
        // selection listener above) — coupling both channels is the ctrl+wheel
        // scope-zoom gesture alone, not the per-channel V/div field.

        leftAc = squareToggle(g, "AC");
        leftAc.setToolTipText(I18n.t("scope.left.ac.tooltip"));
        Bindings.check(leftAc, prefs.oscLeftAcModeProperty());
        Bindings.onChange(toolbarTabs, prefs.oscLeftAcModeProperty(), v -> {
            host.requestRedraw();
            toolbarTabs.refreshTab(TAB_LEFT);
        });

        Image sincImg = createSincFractionImage(g.getDisplay());
        leftSinc = new Button(g, SWT.CHECK);
        leftSinc.setImage(sincImg);
        leftSinc.setToolTipText(I18n.t("scope.sinc.tooltip"));
        Bindings.check(leftSinc, prefs.oscLeftSincInterpEnabledProperty());
        Bindings.onChange(toolbarTabs, prefs.oscLeftSincInterpEnabledProperty(), v -> {
            host.requestRedraw();
            toolbarTabs.refreshTab(TAB_LEFT);
        });
        leftSinc.addDisposeListener(e -> sincImg.dispose());

        new Label(g, SWT.NONE).setText(I18n.t("scope.mains.label"));
        leftMains = new Combo(g, SWT.READ_ONLY);
        leftMains.setItems(MainsSuppression.LABELS);
        leftMains.setToolTipText(I18n.t("scope.mains.tooltip"));
        Bindings.combo(leftMains, prefs.oscLeftMainsSuppressionProperty(), MainsSuppression.values());
        Bindings.onChange(toolbarTabs, prefs.oscLeftMainsSuppressionProperty(), v -> {
            host.requestRedraw();
            toolbarTabs.refreshTab(TAB_LEFT);
        });

        new Label(g, SWT.NONE).setText(I18n.t("scope.lpf.label"));
        leftLpf = new Combo(g, SWT.READ_ONLY);
        leftLpf.setItems(LpfMode.LABELS);
        leftLpf.setToolTipText(I18n.t("scope.lpf.tooltip"));
        Bindings.combo(leftLpf, prefs.oscLeftLpfProperty(), LpfMode.values());
        Bindings.onChange(toolbarTabs, prefs.oscLeftLpfProperty(), v -> {
            host.requestRedraw();
            toolbarTabs.refreshTab(TAB_LEFT);
        });
    }

    private void buildRightGroup(CTabFolder folder) {
        Composite g = groupCell(folder, I18n.t("scope.tab.right"));
        g.setLayout(rowLayoutHorizontal(6));

        Preferences prefs = Preferences.instance();

        rightToggle = squareToggle(g, "R");
        rightToggle.setToolTipText(I18n.t("scope.right.toggle.tooltip"));
        Bindings.check(rightToggle, prefs.oscRightChannelEnabledProperty());
        Bindings.onChange(toolbarTabs, prefs.oscRightChannelEnabledProperty(), v -> {
            host.requestRedraw();
            toolbarTabs.refreshTab(TAB_RIGHT);
        });

        rightScale = new NumericStepField(g, UnitFamily.VOLTS_PER_DIV,
                V_PER_DIV_MIN, V_PER_DIV_MAX, OscParse.voltsPerDivTargets(),
                PER_DIV_MAX_DECIMALS, 90);
        rightScale.setValue(prefs.getOscRightVoltsPerDiv());
        rightScale.setToolTipText(I18n.t("scope.right.scale.tooltip"));
        // Coupled write (see leftScale): offset re-derived from the OLD V/div,
        // so no Bindings helper fits.  Both prefs are bound + auto-save, so the
        // explicit save() is retired.
        rightScale.addSelectionListener(e -> {
            double oldV = prefs.getOscRightVoltsPerDiv();
            double newV = rightScale.getValue();
            prefs.setOscRightOffsetFrac(
                    ScopeFormat.preserveCanvasMiddle(prefs.getOscRightOffsetFrac(), oldV, newV));
            prefs.setOscRightVoltsPerDiv(newV);
            host.requestRedraw();
            toolbarTabs.refreshTab(TAB_RIGHT);
        });
        // Wheel / arrows / typing on this field change ONLY this channel (selection
        // listener above) — coupling is the ctrl+wheel scope zoom alone.

        rightAc = squareToggle(g, "AC");
        rightAc.setToolTipText(I18n.t("scope.right.ac.tooltip"));
        Bindings.check(rightAc, prefs.oscRightAcModeProperty());
        Bindings.onChange(toolbarTabs, prefs.oscRightAcModeProperty(), v -> {
            host.requestRedraw();
            toolbarTabs.refreshTab(TAB_RIGHT);
        });

        Image sincImg = createSincFractionImage(g.getDisplay());
        rightSinc = new Button(g, SWT.CHECK);
        rightSinc.setImage(sincImg);
        rightSinc.setToolTipText(I18n.t("scope.sinc.tooltip"));
        Bindings.check(rightSinc, prefs.oscRightSincInterpEnabledProperty());
        Bindings.onChange(toolbarTabs, prefs.oscRightSincInterpEnabledProperty(), v -> {
            host.requestRedraw();
            toolbarTabs.refreshTab(TAB_RIGHT);
        });
        rightSinc.addDisposeListener(e -> sincImg.dispose());

        new Label(g, SWT.NONE).setText(I18n.t("scope.mains.label"));
        rightMains = new Combo(g, SWT.READ_ONLY);
        rightMains.setItems(MainsSuppression.LABELS);
        rightMains.setToolTipText(I18n.t("scope.mains.tooltip"));
        Bindings.combo(rightMains, prefs.oscRightMainsSuppressionProperty(), MainsSuppression.values());
        Bindings.onChange(toolbarTabs, prefs.oscRightMainsSuppressionProperty(), v -> {
            host.requestRedraw();
            toolbarTabs.refreshTab(TAB_RIGHT);
        });

        new Label(g, SWT.NONE).setText(I18n.t("scope.lpf.label"));
        rightLpf = new Combo(g, SWT.READ_ONLY);
        rightLpf.setItems(LpfMode.LABELS);
        rightLpf.setToolTipText(I18n.t("scope.lpf.tooltip"));
        Bindings.combo(rightLpf, prefs.oscRightLpfProperty(), LpfMode.values());
        Bindings.onChange(toolbarTabs, prefs.oscRightLpfProperty(), v -> {
            host.requestRedraw();
            toolbarTabs.refreshTab(TAB_RIGHT);
        });
    }

    private void buildHorizontalGroup(CTabFolder folder) {
        Composite g = groupCell(folder, "Horizontal");
        g.setLayout(rowLayoutHorizontal(6));

        Preferences prefs = Preferences.instance();
        timeScale = new NumericStepField(g, UnitFamily.TIME_PER_DIV,
                T_PER_DIV_MIN, T_PER_DIV_MAX, OscParse.timePerDivTargets(),
                PER_DIV_MAX_DECIMALS, 90);
        timeScale.setToolTipText(I18n.t("scope.time.scale.tooltip"));
        Bindings.stepField(timeScale, prefs.oscTimePerDivProperty());
        // viewCenterFrames is the primary state and does NOT change on
        // t/div — only the window's width changes.  applyViewState
        // re-derives slider thumb + position so the centred frame stays
        // put (and ends in requestRedraw).  In live-record mode
        // viewCenterFrames is -1 (follow-latest) so the trace continues
        // tracking writePos.
        Bindings.onChange(toolbarTabs, prefs.oscTimePerDivProperty(), v -> {
            host.applyViewState();
            toolbarTabs.refreshTab(TAB_HORIZONTAL);
        });

        // Zero-width, SQUARE_BUTTON-tall spacer so the row measures at the
        // same 32-px height as the V-group's button row.
        Composite rowSpacer = new Composite(g, SWT.NONE);
        rowSpacer.setLayoutData(new RowData(0, SQUARE_BUTTON));
    }

    private void buildTriggerGroup(CTabFolder folder) {
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
        makeDependentGroup(chL, chR);
        Map<Button, Channel> chMap = new LinkedHashMap<>();
        chMap.put(chL, Channel.L);
        chMap.put(chR, Channel.R);
        Bindings.radio(chMap, prefs.oscTriggerChannelProperty());
        Bindings.onChange(toolbarTabs, prefs.oscTriggerChannelProperty(), v -> toolbarTabs.refreshTab(TAB_TRIGGER));

        Composite edgeSet = new Composite(g, SWT.NONE);
        edgeSet.setLayout(flushRowLayoutHorizontal(2));
        edgeRise = squareToggle(edgeSet, "↑");
        edgeFall = squareToggle(edgeSet, "↓");
        edgeRise.setToolTipText(I18n.t("scope.trigger.edge.rise.tooltip"));
        edgeFall.setToolTipText(I18n.t("scope.trigger.edge.fall.tooltip"));
        makeDependentGroup(edgeRise, edgeFall);
        Map<Button, TriggerEdge> edgeMap = new LinkedHashMap<>();
        edgeMap.put(edgeRise, TriggerEdge.RISE);
        edgeMap.put(edgeFall, TriggerEdge.FALL);
        Bindings.radio(edgeMap, prefs.oscTriggerEdgeProperty());
        Bindings.onChange(toolbarTabs, prefs.oscTriggerEdgeProperty(), v -> toolbarTabs.refreshTab(TAB_TRIGGER));

        Composite modeSet = new Composite(g, SWT.NONE);
        modeSet.setLayout(flushRowLayoutHorizontal(2));
        modeAuto   = squareToggle(modeSet, "A");
        modeNormal = squareToggle(modeSet, "N");
        modeSingle = squareToggle(modeSet, "S");
        modeAuto  .setToolTipText(I18n.t("scope.trigger.auto"));
        modeNormal.setToolTipText(I18n.t("scope.trigger.normal"));
        modeSingle.setToolTipText(I18n.t("scope.trigger.single"));
        makeDependentGroup(modeAuto, modeNormal, modeSingle);
        Map<Button, TriggerMode> modeMap = new LinkedHashMap<>();
        modeMap.put(modeAuto,   TriggerMode.AUTO);
        modeMap.put(modeNormal, TriggerMode.NORMAL);
        modeMap.put(modeSingle, TriggerMode.SINGLE);
        Bindings.radio(modeMap, prefs.oscTriggerModeProperty());
        Bindings.onChange(toolbarTabs, prefs.oscTriggerModeProperty(), v -> toolbarTabs.refreshTab(TAB_TRIGGER));

        triggerStartBtn = new Button(g, SWT.TOGGLE);
        Image triggerPlayIcon = IconUtils.icon(g.getDisplay(), Icon.PLAY_DARK_SMALL);
        triggerStartBtn.setImage(triggerPlayIcon);
        // Image is cached and owned by IconUtils — disposed when the
        // main shell tears down, not on button dispose.
        triggerStartBtn.setToolTipText(I18n.t("scope.trigger.start.tooltip"));
        triggerStartBtn.setLayoutData(new RowData(SQUARE_BUTTON, SQUARE_BUTTON));
        triggerStartBtn.setEnabled(modeSingle.getSelection());
        // Toggle: pressed = a single shot is armed (display frozen, waiting);
        // the view pops it back out via SCOPE_SINGLE_DISARMED when the trigger
        // fires.  Un-pressing it manually cancels the pending shot.
        triggerStartBtn.addListener(SWT.Selection,
                e -> view.setSingleArmed(triggerStartBtn.getSelection()));

        // Registered AFTER the mode radio binding so the button selections
        // are already updated when syncTriggerStart reads modeSingle's state.
        Bindings.onChange(toolbarTabs, prefs.oscTriggerModeProperty(), v -> syncTriggerStart());

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
        hysteresisEnable.setToolTipText(I18n.t("scope.trigger.hysteresis.tooltip"));

        hysteresisSel = new NumericStepField(hystSet, UnitFamily.DIVISIONS,
                0, HYST_MAX_DIV, HYST_STEP_DIV, HYST_STEP_DIV, 1, 50);
        hysteresisSel.setValue(prefs.getOscTriggerHysteresisDiv());
        hysteresisSel.setToolTipText(I18n.t("scope.trigger.hysteresis.tooltip"));
        hysteresisSel.setEnabled(prefs.isOscTriggerHysteresisEnabled());
        hysteresisSel.addSelectionListener(e -> {
            prefs.setOscTriggerHysteresisDiv(hysteresisSel.getValue());
            toolbarTabs.refreshTab(TAB_TRIGGER);
        });
        Bindings.check(hysteresisEnable, prefs.oscTriggerHysteresisEnabledProperty());
        Bindings.onChange(toolbarTabs, prefs.oscTriggerHysteresisEnabledProperty(), on -> {
            hysteresisSel.setEnabled(on);
            toolbarTabs.refreshTab(TAB_TRIGGER);
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
        // Enabled state follows the generator form (DUAL_TONE), driven by
        // syncReconstructedBeatEnabled on GENERATOR_SIGNAL_CHANGED — not a
        // Preferences binding.  Only the checked value binds to the pref.
        reconstructedBeatBtn.setEnabled(isGeneratorDualTone());
        Bindings.check(reconstructedBeatBtn, prefs.oscShowReconstructedBeatProperty());
        Bindings.onChange(toolbarTabs, prefs.oscShowReconstructedBeatProperty(), v -> host.requestRedraw());
    }

    /** True when the generator is currently in {@code DUAL_TONE} form
     *  (drives the "Reconstructed beat" checkbox's enabled state). */
    private boolean isGeneratorDualTone() {
        return Preferences.instance().getGenSignalForm().isDualTone();
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
        g.setLayout(new FillLayout());
        new PresetBar<OscPreset>(g, "scope.presets", "oscilloscope.html#scope-tab-presets",
                new PresetBar.Store<>() {
                    @Override public Map<String, OscPreset> presets() { return Preferences.instance().getOscPresets(); }
                    @Override public void put(String name, OscPreset p) { Preferences.instance().putOscPreset(name, p); }
                    @Override public void remove(String name) { Preferences.instance().removeOscPreset(name); }
                    @Override public OscPreset captureCurrent() { return captureCurrentOscPreset(); }
                    @Override public void apply(OscPreset p) { applyOscPreset(p); }
                });
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

    /** Pushes every preset value into the live {@link Preferences} AND the
     *  UI widgets.  {@code NumericStepField.setValue} fires its selection
     *  listener (which writes vDiv/tDiv + adjusts offsetFrac via
     *  preserveCanvasMiddle), so we re-write the offsetFracs / triggerPos
     *  AFTER the scale updates so the preset values win.  Button widgets
     *  use {@code setSelection} which does NOT fire listeners, so we update
     *  prefs explicitly for those. */
    private void applyOscPreset(OscPreset p) {
        Preferences prefs = Preferences.instance();
        // The V/T scale selectors carry their own coupled-offset logic and are
        // NOT pref-bound, so setValue() is how their widgets update — it also
        // fires the listener that clobbers offsetFrac (overwritten below).
        if (leftScale  != null && !leftScale .isDisposed()) leftScale .setValue(p.getLeftVoltsPerDiv());
        if (rightScale != null && !rightScale.isDisposed()) rightScale.setValue(p.getRightVoltsPerDiv());
        if (timeScale  != null && !timeScale .isDisposed()) timeScale .setValue(p.getTimePerDiv());
        // Everything else is two-way bound (Bindings.check / combo / radio):
        // writing the preference propagates into its widget (and fires the
        // tab-tile / redraw listeners), so we only touch prefs here — no manual
        // widget mirroring.
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
        prefs.setOscTriggerChannel(p.getTriggerChannel());
        prefs.setOscTriggerEdge   (p.getTriggerEdge());
        prefs.setOscTriggerMode   (p.getTriggerMode());
        syncTriggerStart();
        // Fractions — overwrite the values the scale listeners would have
        // clobbered.  Goes last so the preset wins.
        prefs.setOscLeftOffsetFrac     (p.getLeftOffsetFrac());
        prefs.setOscRightOffsetFrac    (p.getRightOffsetFrac());
        prefs.setOscTriggerPositionFrac(p.getTriggerPositionFrac());
        prefs.setOscTriggerLevelFrac   (p.getTriggerLevelFrac());
        prefs.save();
        toolbarTabs.refreshTab(TAB_LEFT);
        toolbarTabs.refreshTab(TAB_RIGHT);
        toolbarTabs.refreshTab(TAB_HORIZONTAL);
        toolbarTabs.refreshTab(TAB_TRIGGER);
        host.requestRedraw();
    }

    /** Re-applies the "Start button enabled iff Single mode is selected"
     *  rule.  Called from the trigger-mode radio listeners and after a
     *  bulk-enable of {@link #triggerGroup} restores Record mode. */
    private void syncTriggerStart() {
        if (triggerStartBtn == null || triggerStartBtn.isDisposed()) return;
        boolean single = modeSingle != null && modeSingle.getSelection();
        triggerStartBtn.setEnabled(single);
        // Leaving SINGLE mode cancels any pending shot and pops the toggle out.
        if (!single && triggerStartBtn.getSelection()) {
            triggerStartBtn.setSelection(false);
            view.setSingleArmed(false);
        }
    }

    /**
     * Two-button group: screenshot (camera) + ADC calibration (crosshair).
     * The group title is omitted; both buttons sit on the same row, the
     * camera button keeps its 1.27 : 1 aspect (SVG-driven), the crosshair
     * button is square (h = w = SQUARE_BUTTON).  Calibrate starts disabled
     * and is gated by the pane via {@link #setCalibrateEnabled(boolean)}.
     */
    private void buildScreenshotGroup(CTabFolder folder, Image cameraIcon, Image crosshairIcon) {
        Composite g = groupCell(folder, "Utility");
        g.setLayout(rowLayoutHorizontal(6));
        Button shotBtn = new Button(g, SWT.PUSH);
        shotBtn.setImage(cameraIcon);
        shotBtn.setToolTipText(I18n.t("scope.screenshot.tooltip"));
        shotBtn.setLayoutData(new RowData(IconUtils.ACTION_BUTTON_PX, IconUtils.ACTION_BUTTON_PX));
        // The screenshot dialog clones the whole pane offscreen, so the pane
        // owns it; ask the host to open it.
        shotBtn.addListener(SWT.Selection, e -> host.openScreenshotDialog());

        calibrateButton = new Button(g, SWT.PUSH);
        calibrateButton.setImage(crosshairIcon);
        calibrateButton.setToolTipText(I18n.t("scope.calibrate.tooltip"));
        calibrateButton.setLayoutData(new RowData(IconUtils.ACTION_BUTTON_PX, IconUtils.ACTION_BUTTON_PX));
        calibrateButton.setEnabled(false);
        calibrateButton.addListener(SWT.Selection, e -> openCalibrationDialog());
    }

    /** Opens the ADC-calibration dialog.  Reads the current Vrms from
     *  the pane's own scope view (no cross-pane fallback — the FFT pane
     *  has its own calibrate button on the FFT side); aborts with an
     *  info MessageBox when no live Vrms is available.  Rescales
     *  {@code Preferences#getAdcFsVoltageRms()} so the displayed Vrms
     *  matches the entered value, and persists the new calibration. */
    private void openCalibrationDialog() {
        if (isDisposed()) return;
        Shell parent = getShell();
        Double currentVrms = (view == null) ? null : view.getLastVrms();
        if (currentVrms == null || currentVrms <= 0 || Double.isNaN(currentVrms)) {
            Dialogs.info(parent, I18n.t("calibrate.title"), I18n.t("calibrate.error.noVrms"));
            return;
        }
        final double measuredVrms = currentVrms;
        new AdcCalibrationDialog(parent, measuredVrms, actualVrms -> {
            Preferences prefs = Preferences.instance();
            double scale = actualVrms / measuredVrms;
            double newFs = prefs.getAdcFsVoltageRms() * scale;
            prefs.setAdcFsVoltageRms(newFs);
            prefs.save();
        }).open();
    }

    /**
     * Second-row "Save to…" group: path text + browse (folder-open) +
     * duration step field + save (floppy-disk).  Clear button removed
     * — the user's spec calls for a minimal three-control group.
     */
    private void buildScopeSaveToGroup(CTabFolder folder) {
        Preferences prefs = Preferences.instance();
        Composite g = groupCell(folder, I18n.t("scope.save.title"));
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
        GridData browseGd = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        browseGd.heightHint = IconUtils.FILE_BUTTON_HEIGHT;
        browse.setLayoutData(browseGd);
        browse.setToolTipText(I18n.t("scope.save.browse.tooltip"));
        browse.addListener(SWT.Selection, e -> openScopeSaveBrowse(pathField));

        NumericStepField durField = new NumericStepField(g, UnitFamily.TIME,
                SAVE_DURATION_MIN_SEC, TIME_MAX_SEC, TIME_MAX_DECIMALS,
                54);     // 60% of the previous 90 px width
        durField.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        durField.setToolTipText(I18n.t("scope.save.duration.tooltip"));
        Bindings.stepField(durField, prefs.oscSaveDurationSecondsProperty());

        Button save = new Button(g, SWT.PUSH);
        save.setImage(floppyDiskIcon);
        GridData saveGd = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        saveGd.heightHint = IconUtils.FILE_BUTTON_HEIGHT;
        save.setLayoutData(saveGd);
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
        Composite g = groupCell(folder, I18n.t("scope.openSignal.title"));
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
        GridData browseGd = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        browseGd.heightHint = IconUtils.FILE_BUTTON_HEIGHT;
        browse.setLayoutData(browseGd);
        browse.setToolTipText(I18n.t("scope.openSignal.browse.tooltip"));
        browse.addListener(SWT.Selection, e -> doOpenSignalBrowse());
    }

    /**
     * Opens the file picker; on a successful pick stops the live
     * recording (if any, via the host) and loads the file synchronously
     * via {@link ScopeOpenSignal#loadFile}.  Surfaces decode failure as a
     * MessageBox.  No-op when the user cancels.
     */
    private void doOpenSignalBrowse() {
        Preferences prefs = Preferences.instance();
        FileDialog fd = new FileDialog(getShell(), SWT.OPEN);
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
        // capture before swapping the buffer out from under it (the pane
        // owns the controller + Record button), and drop the calibrate gate.
        host.stopCaptureForFileLoad();
        setCalibrateEnabled(false);

        File pickedFile = new File(picked);
        boolean ok = (loader != null) && loader.loadFile(pickedFile);
        if (ok) {
            openSignalPathField.setText(picked);
            openSignalPathField.setToolTipText(picked);
            prefs.setOscPlayFromPath(picked);
            File parent = pickedFile.getParentFile();
            if (parent != null) prefs.setOscPlayFromFolder(parent.getAbsolutePath());
            prefs.save();
            // File mode: trigger has no meaning, disable the whole
            // trigger group so the user can't accidentally toggle
            // settings that don't apply to a static signal.
            setSubtreeEnabled(triggerGroup, false);
            // Centre the view on the start of the loaded signal, show the
            // nav slider (file mode) and apply the view state — pane state.
            host.onSignalFileLoaded();
            view.setFilePath(picked);
        } else {
            String err = (loader != null) ? loader.getLastError() : I18n.t("scope.openSignal.loaderUnavailable");
            Dialogs.error(getShell(),
                    I18n.t("scope.openSignal.error"),
                    err != null ? err : I18n.t("common.error.fileOpenUnknown"));
        }
    }

    /** Opens a Save-As dialog for the scope output file. */
    private void openScopeSaveBrowse(Text pathField) {
        Preferences prefs = Preferences.instance();
        FileDialog fd = new FileDialog(getShell(), SWT.SAVE);
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
            Dialogs.info(getShell(),
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
            Dialogs.error(getShell(),
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
            Dialogs.error(getShell(), I18n.t("scope.save.error"),
                    I18n.t("scope.save.error.message", path,
                            SharedCapture.instance().getLastStartError()));
            return;
        }
        double totalSeconds = totalFrames / (double) sampleRate;

        // Floating tool window: on-top so it stays visible, but NOT modal — the
        // user can keep watching the scope / FFT while it records.
        Shell dlg = new Shell(getShell(), SWT.TOOL | SWT.TITLE | SWT.CLOSE | SWT.ON_TOP);
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
        Rectangle pb = getShell().getBounds();
        Point ds = dlg.getSize();
        dlg.setLocation(pb.x + (pb.width - ds.x) / 2, pb.y + (pb.height - ds.y) / 2);
        dlg.open();

        Display display = getDisplay();
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
                    Dialogs.error(getShell(), I18n.t("scope.save.error"),
                            I18n.t("scope.save.error.message", path, fe.getMessage()));
                }
            });
        }, "scope-stream-record");
        t.setDaemon(true);
        t.start();
    }

    // -------------------------------------------------------------------------
    // V/T scale stepping (driven by the pane's mouse-wheel handler)
    // -------------------------------------------------------------------------

    /** Steps the t/div selector by {@code delta} (negative = zoom in / smaller
     *  t/div, positive = zoom out); the field's selection listener persists it. */
    private void stepTimePerDiv(int delta) {
        if (timeScale == null || timeScale.isDisposed()) return;
        timeScale.step(delta);
    }

    /** V/div zoom anchored at mouse Y: after the zoom, the same voltage that
     *  was under the mouse before the zoom is still under the mouse.  Per
     *  channel — each channel's V/div + offsetFrac are adjusted using that
     *  channel's voltage-at-mouse, since the two channels can have different
     *  scales.  The V/div change itself is one step of the field's OWN value
     *  list ({@link NumericStepField#step}) — so the wheel walks the same
     *  1-2-5-10 ladder the field's arrows do, with no separate float-target
     *  calculation that could stall on rounding.  Note: the {@code *Scale}
     *  selection listener overwrites offsetFrac with
     *  {@code ScopeFormat.preserveCanvasMiddle(...)} (center-anchored zoom) as
     *  it fires inside {@code step(...)}; we re-apply the mouse-anchored
     *  offsetFrac AFTER, so our value wins and the redraw the listener
     *  scheduled picks it up. */
    public void stepVoltsPerDivAround(int delta, int mouseY, int height) {
        Preferences prefs = Preferences.instance();
        boolean leftEn  = leftScale  != null && !leftScale.isDisposed()  && prefs.isOscLeftChannelEnabled();
        boolean rightEn = rightScale != null && !rightScale.isDisposed() && prefs.isOscRightChannelEnabled();
        if (!leftEn && !rightEn) return;
        double leftOld  = leftEn  ? leftScale.getValue()  : 0.0;
        double rightOld = rightEn ? rightScale.getValue() : 0.0;
        // Anchor the voltage under the mouse Y (ctrl+wheel) or the canvas middle (the
        // field control passes height<=0).  The engine couples both channels' V/div
        // and re-anchors each offset; we push its results into the fields + prefs.
        double anchorFrac = (height > 0) ? (double) mouseY / height : 0.5;
        double peak = prefs.getAdcFsVoltageRms() * Math.sqrt(2.0);
        double[] r = view.getNav().zoomVertical(
                leftOld,  prefs.getOscLeftOffsetFrac(),  leftEn,
                rightOld, prefs.getOscRightOffsetFrac(), rightEn,
                delta, anchorFrac, peak);
        boolean changed = false;
        if (leftEn && r[0] != leftOld) {
            leftScale.setValue(r[0]);                  // listener centre-anchors; override below
            prefs.setOscLeftOffsetFrac(r[2]);
            changed = true;
        }
        if (rightEn && r[1] != rightOld) {
            rightScale.setValue(r[1]);
            prefs.setOscRightOffsetFrac(r[3]);
            changed = true;
        }
        if (changed) {
            prefs.save();
            host.requestRedraw();
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
    public void stepTimePerDivAround(int delta, int mouseX, int width) {
        if (width <= 0 || timeScale == null || timeScale.isDisposed()) {
            stepTimePerDiv(delta);
            return;
        }
        Preferences prefs = Preferences.instance();
        double tDivOld   = timeScale.getValue();
        double mouseFrac = (double) mouseX / width;
        timeScale.step(delta);
        double tDivNew   = timeScale.getValue();
        if (tDivNew == tDivOld) return;
        if (view.isFileMode()) {
            // A loaded file has no trigger: zoom around the mouse by re-centring the view.
            host.zoomFileTimeAroundMouse(mouseFrac, tDivOld, tDivNew);
            return;
        }
        if (view.getReader() == null) { host.requestRedraw(); return; }
        int sr      = view.getReader().getSampleRate();
        int dispOld = ScopeFormat.displaySamplesFor(tDivOld, sr);
        int dispNew = ScopeFormat.displaySamplesFor(tDivNew, sr);
        // Anchor on the REAL trigger offset (live + frozen both render the real,
        // virtual-capable value now): the sample under the mouse stays put while the
        // offset may carry off-screen — the handle pins to the edge, the time mark shows
        // the real value.  Uses the actual displaySamples ratio so the anchor is exact
        // even at fine time bases where the t/div ratio would round-divergently drift.
        double posOld = prefs.getOscTriggerPositionFrac();
        double posNew = view.getNav().zoomTriggerOffset(posOld, dispOld, dispNew, mouseFrac, true);
        prefs.setOscTriggerPositionFrac(posNew);
        host.requestRedraw();   // repaint a frozen / file-mode view too (the live render loop is idle then)
    }

    // -------------------------------------------------------------------------
    // Layout / widget helpers (duplicated from MainWindow because the
    // oscilloscope pane has its own complete tree of widgets and doesn't
    // share state with the Generator / FFT panes).
    // -------------------------------------------------------------------------

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
