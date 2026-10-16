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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.ObjDoubleConsumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Text;
import org.edgo.audio.measure.bind.Property;
import org.edgo.audio.measure.dsp.FreqRespCalHelper;
import org.edgo.audio.measure.dsp.FreqRespCalibration;
import org.edgo.audio.measure.dsp.StereoFreqRespCalibration;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.FilterResponse;
import org.edgo.audio.measure.enums.FilterType;
import org.edgo.audio.measure.enums.OutputChannels;
import org.edgo.audio.measure.enums.UnevenMode;
import org.edgo.audio.measure.gui.bind.Bindings;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.AbstractCalibrationTabControl;
import org.edgo.audio.measure.gui.common.CorrectionStore;
import org.edgo.audio.measure.gui.common.Dialogs;
import org.edgo.audio.measure.gui.common.Icon;
import org.edgo.audio.measure.gui.common.IconUtils;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.widgets.NumericStepField;
import org.edgo.audio.measure.gui.widgets.PresetBar;
import org.edgo.audio.measure.gui.widgets.TileTabFolder;
import org.edgo.audio.measure.gui.widgets.UnitFamily;
import org.edgo.audio.measure.gui.widgets.UnitValue;
import org.edgo.audio.measure.preferences.BackendPrefs;
import org.edgo.audio.measure.preferences.FreqRespFilterTypeParams;
import org.edgo.audio.measure.preferences.FreqRespPreset;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.DeviceCalibration;
import org.edgo.audio.measure.sound.DeviceRef;

import lombok.extern.log4j.Log4j2;

/**
 * Self-contained Frequency Response toolbar control: a {@link TileTabFolder}
 * carrying the Settings, RIAA &amp; IEC, Presets, Utility, Calibration, Save-to
 * and Load-from tabs, with all of their preference bindings, the preset
 * machinery, the multi-row {@code .frc} calibration loader, and the
 * measurement save / load file flows.
 *
 * <p>The host {@link FreqRespPane} keeps the chart ({@link FreqRespView}), the
 * frequency / magnitude scrollbars, and the Wizard + Play action buttons (which
 * drive the measurement worker).  The {@link FreqRespView} is shared and passed
 * in at construction; the only cross-boundary calls are {@link #refreshRiaaEnable()}
 * (the pane invokes it when a fresh measurement lands so Compare becomes
 * available) and the collapse relayout the pane wires via
 * {@link #setCollapseRelayout(Runnable)}.  The Save-to button calls the owning
 * pane's save (the pane is passed in at construction), and the Save-to path
 * field follows the saved-path preference the save writes.
 * Everything else flows through the shared view, {@link Preferences}, and the
 * {@link MessageBus}.
 */
@Log4j2
public final class FreqRespTabControl extends AbstractCalibrationTabControl<FreqRespPane> {

    /** Tab indices, kept as constants so the tile builder / refresh callers
     *  don't have to repeat magic numbers. */
    private static final int TAB_FREQRESP_SETTINGS    = 0;
    private static final int TAB_FREQRESP_RIAA        = 1;
    private static final int TAB_FREQRESP_FILTERS     = 2;
    private static final int TAB_FREQRESP_UNEVENNESS  = 3;
    private static final int TAB_FREQRESP_PRESETS     = 4;
    private static final int TAB_FREQRESP_UTILITY     = 5;
    private static final int TAB_FREQRESP_CALIBRATION = 6;
    private static final int TAB_FREQRESP_SAVE        = 7;
    private static final int TAB_FREQRESP_LOAD        = 8;
    private static final int NUM_CUSTOM_TABS          = 9;

    /** Message-key prefix of the calibration tab's texts
     *  ({@code freqResp.calibration.*}). */
    private static final String CALIBRATION_KEY_PREFIX = "freqResp";

    /** Power-of-2 sweep-point presets the points field's wheel jumps along;
     *  the runtime "sample rate / 2" entry is merged in by
     *  {@link #sweepPointSeries()}.  Manual entry between or beyond the
     *  presets is allowed. */
    private static final double[] SWEEP_POINT_SERIES = {
            8192, 16384, 32768, 65536, 131072, 262144,
            524288, 1048576, 2097152, 4194304
    };
    private static final double SWEEP_POINTS_MIN = 8192;
    private static final double SWEEP_POINTS_MAX = 10_000_000;
    // Frequency / amplitude / lead-in floors and the frequency / amplitude
    // decimals come from FreqRespConstants - shared with the tune-notch
    // wizard's fields and the analyzer's validation.
    private static final double TIME_MAX_SEC     = 1_000_000;
    private static final int TIME_MAX_DECIMALS = 3;

    // ---- Filters / Unevenness field bounds -------------------------------
    /** dB field range shared by ripple / stopband-attenuation fields - the
     *  ripple floor (0.001) and a generous attenuation ceiling. */
    private static final double FILTER_DB_MIN      = 0.001;
    private static final double FILTER_DB_MAX      = 200.0;
    private static final int    FILTER_DB_DECIMALS = 3;
    /** Filter order - Bessel polynomial stays well-conditioned to 20. */
    private static final double FILTER_ORDER_MIN   = 1;
    private static final double FILTER_ORDER_MAX   = 20;
    /** Band-pass / notch quality factor. */
    private static final double FILTER_Q_MIN       = 0.1;
    private static final double FILTER_Q_MAX       = 100.0;
    private static final int    FILTER_Q_DECIMALS  = 3;
    /** Unevenness tolerance range per the plan (default 3 dB). */
    private static final double UNEVEN_DB_MIN      = 0.001;
    private static final double UNEVEN_DB_MAX      = 20.0;

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
    /** Heaps below this are 32-bit-class: combo entries above
     *  {@link #SMALL_HEAP_MAX_FFT_SIZE} are not offered.  1.5 GiB sits
     *  between the 32-bit ceiling (~1.2-1.4 GB usable) and any serious
     *  64-bit {@code -Xmx}. */
    private static final long SMALL_HEAP_BYTES = 1_610_612_736L;
    /** Largest deconvolution FFT size offered on a small heap: both channels
     *  deconvolve in parallel over buffers padded to 2× the size at 40 B per
     *  padded sample, so 8M already needs ~1.3 GB of transient buffers while
     *  the 4M default (~0.9 GB peak with the capture and sweep reference)
     *  still fits a 32-bit JVM. */
    private static final int SMALL_HEAP_MAX_FFT_SIZE = 1 << 22;

    private final FreqRespView view;

    // Tab-header tiles: the shared TileTabFolder (held by AbstractTabControl as
    // toolbarTabs) owns the renderer, spacer images, tab-body collapse, hover
    // tooltips and tile painting; this control only supplies tile content
    // (freqRespTabTiles).

    /** Updated whenever the FFT-size combo or the lead-in field changes
     *  - caption is {@code "FFT size (D.Ds)"} where D.D is the derived
     *  sweep duration in seconds. */
    private Label fftSizeLabel;

    private Button riaaShowBtn;
    private Button riaaReverseBtn;
    private Button riaaIecBtn;
    private Button riaaCompareBtn;

    // ---- Filters tab widgets (gated by filterShowBtn) --------------------
    private Button filterShowBtn;
    private Button filterCompareBtn;
    private Combo  filterTypeCombo;
    private Combo  filterResponseCombo;
    private Button filterMode1Radio;
    private Button filterMode2Radio;
    private NumericStepField filterRippleField;
    private NumericStepField filterStopAttenField;
    private NumericStepField filterCenterField;
    private NumericStepField filterPassField;
    private NumericStepField filterStopField;
    private Label  filterRippleLabel;
    private Label  filterStopAttenLabel;
    private Label  filterCenterLabel;
    private Label  filterPassLabel;
    private Label  filterStopLabel;
    private Label  filterOrderPassLabel;
    private Label  filterOrderRippleLabel;
    private Label  filterOrderLabel;
    private Label  filterQLabel;
    private NumericStepField filterOrderPassField;
    private NumericStepField filterOrderRippleField;
    private NumericStepField filterOrderField;
    private NumericStepField filterQField;
    /** Bordered Specification group holding the mode radios + the two shared
     *  parameter rows (a single 6-column grid of fixed stacked cells). */
    private Group  filterSpecGroup;
    /** The fixed stacked cells of the 6-column spec grid, in creation order.
     *  Each cell occupies one grid cell for good and overlays its Mode-1 and
     *  Mode-2 control (only one shown); this keeps every field pinned to its
     *  column in BOTH modes with no exclusion-reflow. */
    private final List<SpecCell> specCells = new ArrayList<>();
    /** Per-type parameter picture in the tab's third column (outside the group),
     *  spanning both header + group rows; hidden in By-order mode. */
    private Label  filterPictureLabel;
    /** Native-size Mode-1 pictures keyed by filter type; disposed with the tab. */
    private final Map<FilterType, Image> filterPictures = new LinkedHashMap<>();
    /** Re-entrancy guard: {@code true} while {@link #loadFilterParams} is
     *  seeding the widgets from the per-type params map, so the widgets'
     *  own change listeners don't write the just-loaded values straight
     *  back into the map. */
    private boolean filterParamsLoading;

    // ---- Unevenness tab widgets ------------------------------------------
    private Button unevenOffRadio;
    private Button unevenNotchCheck;
    private Button unevenPmRadio;
    private Button unevenRangeRadio;
    private NumericStepField unevenDbField;
    private NumericStepField unevenStartField;
    private NumericStepField unevenStopField;

    /** Path fields of the Save-to and Load-from tabs.  The Save-to field
     *  follows the saved-path preference; the Load-from field is written by
     *  the file-dialog handler. */
    private Text saveToPathField;
    private Text loadFromPathField;

    /** The combo's offered subset of {@link #FFT_SIZE_VALUES} /
     *  {@link #FFT_SIZE_LABELS} - truncated at
     *  {@link #SMALL_HEAP_MAX_FFT_SIZE} on a small heap, the full list
     *  otherwise. */
    private final int[]    offeredFftSizes;
    private final String[] offeredFftSizeLabels;

    /** {@code owner} is the pane that builds this control: the Save-to button
     *  saves the view's curves through it, and the Screenshot button opens its
     *  screenshot dialog, which clones the whole pane (title + plot +
     *  collapsed strip) offscreen.  The calibration tab (the base) follows
     *  the FreqResp calibration list and fills {@code correctionStore}, which
     *  the view divides by. */
    public FreqRespTabControl(Composite parent, FreqRespView view,
                              CorrectionStore correctionStore,
                              FreqRespPane owner) {
        super(parent, SWT.NONE, owner, correctionStore,
                Preferences.instance().getFreqRespCalibrations(),
                Preferences.instance().freqRespLoadFolderProperty(),
                CALIBRATION_KEY_PREFIX, false);
        this.view = view;
        int sizeCap = Runtime.getRuntime().maxMemory() < SMALL_HEAP_BYTES
                ? SMALL_HEAP_MAX_FFT_SIZE : Integer.MAX_VALUE;
        int offered = 0;
        while (offered < FFT_SIZE_VALUES.length && FFT_SIZE_VALUES[offered] <= sizeCap) {
            offered++;
        }
        this.offeredFftSizes      = Arrays.copyOf(FFT_SIZE_VALUES, offered);
        this.offeredFftSizeLabels = Arrays.copyOf(FFT_SIZE_LABELS, offered);

        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 0; gl.marginHeight = 0;
        setLayout(gl);

        toolbarTabs = new TileTabFolder(this, SWT.NONE);
        toolbarTabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        // Each tile's chip text doubles as its own hover tooltip.
        toolbarTabs.setCustomTabs(NUM_CUSTOM_TABS, new TileTabFolder.TileSource() {
            @Override
            public List<TileTabFolder.Tile> tilesFor(int tabIndex) {
                return freqRespTabTiles(tabIndex);
            }
            @Override
            public int extraPadding(int tabIndex) {
                // Compact headers - the widget default (28 px) leaves a wide
                // empty band at each tab's right edge; match the scope's 7 px.
                return 7;
            }
        });
        buildSettingsTab();
        buildRiaaTab();
        buildFiltersTab();
        buildUnevennessTab();
        buildPresetsTab();
        buildUtilityTab();
        buildCalibrationTab();
        buildSaveToTab();
        buildLoadFromTab();
        Preferences prefs = Preferences.instance();
        int activeTab = Math.max(0, Math.min(toolbarTabs.getItemCount() - 1,
                prefs.getFreqRespActiveTabIndex()));
        if (toolbarTabs.getItemCount() > 0) toolbarTabs.setSelection(activeTab);
        toolbarTabs.addListener(SWT.Selection, e ->
                prefs.setFreqRespActiveTabIndex(toolbarTabs.getSelectionIndex()));
        // Capture labels, size the strip / spacer images and wire the paint,
        // hover and collapse listeners now that the tabs exist.
        toolbarTabs.init();

        // Follow the calibration list and push the initially-loaded active rows
        // into the store last - the view (built before this control) already
        // holds the same store instance, so the view re-derive at the end of
        // the sync finds it ready.
        followCalibrations();
    }

    // -------------------------------------------------------------------------
    // Pane delegation
    // -------------------------------------------------------------------------

    /** Registers every settings tab under {@code prefix} so an automation
     *  script can select a tab by path (e.g. {@code prefix + "/riaa"}) - it
     *  expands the strip and selects that tab - then screenshot this control
     *  showing it.  Slugs are language-independent; tabs not built are skipped.
     *  Mirrors {@code ScopeTabControl.registerTabs}. */
    public void registerTabs(String prefix) {
        registerTab(prefix, "settings",    TAB_FREQRESP_SETTINGS);
        registerTab(prefix, "riaa",        TAB_FREQRESP_RIAA);
        registerTab(prefix, "filters",     TAB_FREQRESP_FILTERS);
        registerTab(prefix, "unevenness",  TAB_FREQRESP_UNEVENNESS);
        registerTab(prefix, "presets",     TAB_FREQRESP_PRESETS);
        registerTab(prefix, "utility",     TAB_FREQRESP_UTILITY);
        registerTab(prefix, "calibration", TAB_FREQRESP_CALIBRATION);
        registerTab(prefix, "save",        TAB_FREQRESP_SAVE);
        registerTab(prefix, "load",        TAB_FREQRESP_LOAD);
    }

    /** Re-runs the RIAA enable cascade.  Public because the pane calls it when
     *  a fresh measurement completes (or a file loads) so Compare picks up the
     *  newly-available result. */
    public void refreshRiaaEnable() {
        // Cascade: Show RIAA enables Reverse and IEC (independently); their
        // selection state is preserved across toggling Show so the user can
        // hide and re-show the overlay without losing their settings.
        // Compare requires a measurement to be present.
        if (riaaShowBtn == null || riaaShowBtn.isDisposed()) return;
        boolean show = riaaShowBtn.getSelection();
        riaaReverseBtn.setEnabled(show);
        riaaIecBtn.setEnabled(show);
        riaaCompareBtn.setEnabled(show && view.hasAnyResult());
        // The Filters tab's Compare is likewise gated on a present measurement,
        // so re-run its cascade from the same pane entry point.
        refreshFilterEnable();
    }

    /** Re-runs the Filters enable cascade - every control is gated on the
     *  Show-filter checkbox; ripple fields depend on the response family;
     *  centre / Q on the type; the two Mode radios gate their own field rows;
     *  Compare needs a present measurement.  Public so the pane's
     *  measurement-completed hook (via {@link #refreshRiaaEnable()}) makes
     *  Compare available once a curve exists. */
    public void refreshFilterEnable() {
        if (filterShowBtn == null || filterShowBtn.isDisposed()) return;
        Preferences prefs = Preferences.instance();
        boolean show     = filterShowBtn.getSelection();
        FilterType type  = prefs.getFreqRespFilterType();
        boolean isBpNotch = type == FilterType.BAND_PASS || type == FilterType.NOTCH;
        boolean hasRipple = prefs.getFreqRespFilterResponse().hasRipple();
        boolean mode2     = filterMode2Radio.getSelection();
        boolean mode1     = !mode2;

        filterCompareBtn.setEnabled(show && view.hasAnyResult());
        filterTypeCombo.setEnabled(show);
        filterResponseCombo.setEnabled(show);
        filterMode1Radio.setEnabled(show);
        filterMode2Radio.setEnabled(show);

        // Mode-1 rows - ripple only for the equiripple families, centre only
        // for band-pass / notch.
        filterRippleField.setEnabled(show && mode1 && hasRipple);
        filterStopAttenField.setEnabled(show && mode1);
        filterCenterField.setEnabled(show && mode1 && isBpNotch);
        filterPassField.setEnabled(show && mode1);
        filterStopField.setEnabled(show && mode1);
        // Passband / Stopband labels read Fc/Fs for LP/HP, PB/SB for BP/notch.
        filterPassLabel.setText(I18n.t(isBpNotch
                ? "freqResp.filter.passband.bp" : "freqResp.filter.passband"));
        filterStopLabel.setText(I18n.t(isBpNotch
                ? "freqResp.filter.stopband.bp" : "freqResp.filter.stopband"));
        filterPassLabel.requestLayout();

        // Mode-2 rows - ripple only for the equiripple families, Q only for
        // band-pass / notch.
        filterOrderPassField.setEnabled(show && mode2);
        filterOrderRippleField.setEnabled(show && mode2 && hasRipple);
        filterOrderField.setEnabled(show && mode2);
        filterQField.setEnabled(show && mode2 && isBpNotch);

        // Visibility swap: each fixed spec cell shows its active mode's
        // control and hides the other, so no grayed by-spec fields linger and no
        // by-order fields are missing - without reflowing the columns.
        // The per-type picture shows only in Mode 1.
        applyModeVisibility(mode1);
        refreshFilterPicture(type, mode1);
    }

    /** Shows the active mode's control in every fixed spec cell (the other
     *  mode's overlaid control is hidden - {@link SpecCell#show}); the wrappers
     *  keep their grid cells, so columns stay pinned in both modes,
     *  no exclusion-reflow.  Relays the group so its size follows the content. */
    private void applyModeVisibility(boolean mode1) {
        if (specCells.isEmpty()) return;
        for (SpecCell cell : specCells) cell.show(mode1);
        if (filterSpecGroup != null && !filterSpecGroup.isDisposed()) {
            filterSpecGroup.layout(true, true);
            filterSpecGroup.requestLayout();
        }
    }

    /** Sets {@code visible} on every control (a {@link NumericStepField} is a
     *  composite, so hide the whole field) and mirrors it into
     *  {@code GridData.exclude} so a hidden control claims no grid cell. */
    private void setSpecVisible(boolean visible, Control... controls) {
        for (Control c : controls) {
            if (c == null || c.isDisposed()) continue;
            c.setVisible(visible);
            Object ld = c.getLayoutData();
            if (ld instanceof GridData gd) gd.exclude = !visible;
        }
    }

    /** Swaps the tab-column picture to the one matching {@code type} and shows /
     *  hides it (Mode 1 only).  The picture lives in the tab grid's
     *  third column, so exclude collapses its column and the tab
     *  body relays. */
    private void refreshFilterPicture(FilterType type, boolean mode1) {
        if (filterPictureLabel == null || filterPictureLabel.isDisposed()) return;
        Image img = filterPictures.get(type);
        if (img != null && filterPictureLabel.getImage() != img) {
            filterPictureLabel.setImage(img);
        }
        setSpecVisible(mode1, filterPictureLabel);
        Composite tabBody = filterPictureLabel.getParent();
        if (tabBody != null && !tabBody.isDisposed()) tabBody.layout(true, true);
        filterPictureLabel.requestLayout();
    }

    // =========================================================================
    // Tab-header tile rendering
    // =========================================================================

    /** Builds the live tile row for a tab from the current preferences, in
     *  visual order (left to right).  Each tile's short chip text doubles as
     *  its hover tooltip; the {@link TileTabFolder} measures and paints them.
     *  The Utility / Save / Load tabs intentionally carry no tiles (the file
     *  path lives in the tab body, not the header). */
    private List<TileTabFolder.Tile> freqRespTabTiles(int tabIndex) {
        Preferences prefs = Preferences.instance();
        List<TileTabFolder.Tile> tiles = new ArrayList<>();
        if (tabIndex == TAB_FREQRESP_SETTINGS) {
            String lo = formatShortHz(prefs.getFreqRespStartHz());
            String hi = formatShortHz(prefs.getFreqRespStopHz());
            tiles.add(tile(String.format(Locale.US, "%s-%s", lo, hi),
                    I18n.t("freqResp.tile.range", lo, hi)));
            double amplitudeVrms = AudioBackend.instance().calibrationOrDefault(false)
                    .amplitudeVrms(prefs.getFreqRespAmplitude(), Channel.L);
            tiles.add(tile(String.format(Locale.US, "%.2fV", amplitudeVrms),
                    I18n.t("freqResp.tile.amplitude",
                            String.format(Locale.US, "%.2f V", amplitudeVrms))));
            tiles.add(tile(formatShortCount(prefs.getFreqRespSweepPoints()) + " pts",
                    I18n.t("freqResp.tile.points", prefs.getFreqRespSweepPoints())));
            // FFT size tile (replaces the old sweep-duration tile, which is
            // now derived from FFT size and shown in the settings-tab label).
            tiles.add(tile(formatFftSize(prefs.getFreqRespFftSize()),
                    I18n.t("freqResp.tile.fftSize", formatFftSize(prefs.getFreqRespFftSize()))));
        } else if (tabIndex == TAB_FREQRESP_RIAA) {
            if (prefs.isFreqRespShowRiaa()) {
                tiles.add(prefs.isFreqRespReverseRiaa()
                        ? tile("rec",  I18n.t("freqResp.tile.rec"))
                        : tile("play", I18n.t("freqResp.tile.play")));
                if (prefs.isFreqRespIecAmendment()) tiles.add(tile("+IEC", I18n.t("freqResp.tile.iec")));
                if (prefs.isFreqRespCompareMode())  tiles.add(tile("comp", I18n.t("freqResp.tile.compare")));
            }
        } else if (tabIndex == TAB_FREQRESP_FILTERS) {
            if (prefs.isFreqRespShowFilter()) {
                String typeName = I18n.t(filterTypeKey(prefs.getFreqRespFilterType()));
                tiles.add(tile(typeName, I18n.t("freqResp.tile.filter", typeName)));
                if (prefs.isFreqRespFilterCompare())
                    tiles.add(tile("comp", I18n.t("freqResp.tile.filtercomp")));
            }
        } else if (tabIndex == TAB_FREQRESP_UNEVENNESS) {
            String readout;
            switch (prefs.getFreqRespUnevenMode()) {
                case RANGE:
                    readout = String.format(Locale.US, "%s-%s",
                            formatShortHz(prefs.getFreqRespUnevenStartHz()),
                            formatShortHz(prefs.getFreqRespUnevenStopHz()));
                    break;
                case LEVEL:
                    readout = String.format(Locale.US, "±%s dB",
                            trimNum(prefs.getFreqRespUnevenDb()));
                    break;
                case OFF:
                default:
                    readout = I18n.t("freqResp.uneven.off");
                    break;
            }
            tiles.add(tile(readout, I18n.t("freqResp.tile.uneven")));
        } else if (tabIndex == TAB_FREQRESP_CALIBRATION) {
            TileTabFolder.Tile tile = getCalibrationTile();
            if (tile != null) {
                tiles.add(tile);
            }
        } else if (tabIndex == TAB_FREQRESP_PRESETS) {
            // Show the number of saved presets when there are any - a hint
            // that there's something to load.
            int n = prefs.getFreqRespPresets().size();
            if (n > 0) tiles.add(tile(n + " saved", I18n.t("freqResp.tile.presets", n)));
        } else if (tabIndex == TAB_FREQRESP_UTILITY) {
            // No header tile - the Utility actions (screenshot, DAC/ADC cal)
            // leave no per-run state; branch kept so the constant is used.
        }
        return tiles;
    }

    /** A text tile with an explicit descriptive hover tooltip. */
    private TileTabFolder.Tile tile(String text, String tooltip) {
        return TileTabFolder.Tile.text(text, tooltip);
    }

    /** Short Hz format used in the Settings tile row: 1500 -> "1.5k",
     *  20000 -> "20k", 8 -> "8". */
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

    /** Short integer format used in the Settings tile row: 65536 -> "65k",
     *  1048576 -> "1M". */
    private String formatShortCount(int n) {
        if (n >= 1_000_000) return String.format(Locale.US, "%.0fM", n / 1_000_000.0);
        if (n >= 1000)      return String.format(Locale.US, "%.0fk", n / 1000.0);
        return Integer.toString(n);
    }

    /** Pretty-prints an FFT size as a power-of-2 abbreviation matching
     *  the {@link #FFT_SIZE_LABELS} combo entries: 65536 -> "64k",
     *  524288 -> "512k", 16777216 -> "16M".  Falls back to
     *  {@link #formatShortCount} for non-power-of-2 values. */
    private String formatFftSize(int n) {
        for (int i = 0; i < FFT_SIZE_VALUES.length; i++) {
            if (FFT_SIZE_VALUES[i] == n) return FFT_SIZE_LABELS[i];
        }
        return formatShortCount(n);
    }

    /** i18n key for a filter type's display name (Filters-tile label). */
    private String filterTypeKey(FilterType type) {
        switch (type) {
            case HIGH_PASS: return "freqResp.filter.type.highpass";
            case BAND_PASS: return "freqResp.filter.type.bandpass";
            case NOTCH:     return "freqResp.filter.type.notch";
            case LOW_PASS:
            default:        return "freqResp.filter.type.lowpass";
        }
    }

    /** Compact dB value for the Unevenness tile: 3.0 -> "3", 1.5 -> "1.5". */
    private String trimNum(double v) {
        if (v == Math.floor(v)) return String.format(Locale.US, "%.0f", v);
        return String.format(Locale.US, "%s", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    // -------------------------------------------------------------------------
    // Settings tab - sweep parameters
    // -------------------------------------------------------------------------

    private void buildSettingsTab() {
        Composite g = groupCell(toolbarTabs, "freqResp.tab.settings");
        GridLayout gl = new GridLayout(4, false);
        gl.marginWidth = 6; gl.marginHeight = 4;
        gl.horizontalSpacing = 6; gl.verticalSpacing = 4;
        g.setLayout(gl);
        Preferences prefs = Preferences.instance();

        // ---- Row 1: start freq + stop freq ----------------------------------
        // The stored band comes down to the ceiling BEFORE the fields are built,
        // so the two-way binds below seed an already-legal value.
        clampSweepBandToCeiling();
        addLabel(g, "freqResp.settings.start");
        NumericStepField startField = freqField(g);
        startField.setMax(sweepBandCeilingHz());
        Bindings.text(startField, "freqResp.settings.start.tooltip", startField::setToolTipText);
        // Two-way bind; the floor clamp (≥ 1 Hz) and the tab-tile refresh ride
        // an onChange on the same pref, so a direct text entry below the floor
        // is corrected in the pref (which echoes back to the field).
        Bindings.stepField(startField, prefs.freqRespStartHzProperty());
        Bindings.onChange(toolbarTabs, prefs.freqRespStartHzProperty(), v -> {
            if (v < 1.0) prefs.setFreqRespStartHz(1.0);
            toolbarTabs.refreshTab(TAB_FREQRESP_SETTINGS);
        });

        addLabel(g, "freqResp.settings.stop");
        NumericStepField stopField = freqField(g);
        stopField.setMax(sweepBandCeilingHz());
        Bindings.text(stopField, "freqResp.settings.stop.tooltip", stopField::setToolTipText);
        // Cross-field clamp: stop must stay at least start + 1.  A plain
        // two-way bind would lose it, so it is re-applied on the pref via
        // onChange (the re-set echoes to the field through the stepField bind).
        Bindings.stepField(stopField, prefs.freqRespStopHzProperty());
        Bindings.onChange(toolbarTabs, prefs.freqRespStopHzProperty(), v -> {
            double floor = prefs.getFreqRespStartHz() + 1.0;
            if (v < floor) prefs.setFreqRespStopHz(floor);
            toolbarTabs.refreshTab(TAB_FREQRESP_SETTINGS);
        });

        // ---- Row 2: amplitude (Vrms) + duration ----------------------------
        addLabel(g, "freqResp.settings.amplitude");
        // No-clip ceiling for the sweep stimulus: the sweep is a sine, so full
        // scale sits at fsPeak·rawRms(sweep) = fsPeak/√2 - V, dBV and dBFS all
        // trim to it (0 dBFS is exactly the top).  The field holds V RMS, so
        // capping it at the PEAK full scale would have allowed 3 dB of clipping.
        // The sweep is rendered on both lanes alike, against the LEFT lane's
        // full scale.
        AudioBackend audio = AudioBackend.instance();
        NumericStepField ampField = new NumericStepField(g, UnitFamily.AMPLITUDE,
                FreqRespConstants.AMP_MIN_VRMS,
                audio.calibrationOrDefault(false).fsRms(Channel.L), FreqRespConstants.AMP_MAX_DECIMALS,
                () -> audio.calibrationOrDefault(false).peakVolts(Channel.L), 110);
        ampField.setLayoutData(comboGd());
        // Follow a DAC recalibration - the ceiling was previously read once, at
        // construction, and never moved again.  Replaying the stored pair after
        // it leaves the entered text where it stands and re-solves what a
        // full-scale-relative entry resolves to.
        Bindings.onEvent(toolbarTabs, Events.DAC_CALIBRATION_CHANGED, (DeviceRef device) -> {
            if (!audio.isActiveDevice(device, false)) return;
            ampField.setMax(audio.calibrationOrDefault(false).fsRms(Channel.L));
            ampField.seedPair(prefs.getFreqRespAmplitude());
        });
        Bindings.text(ampField, "freqResp.settings.amplitude.tooltip", ampField::setToolTipText);
        // The field carries the pair the operator entered; the store resolves it
        // to Vrms at use.  Seed first, then wire, so the seed cannot re-enter
        // the pref write.
        ampField.seedPair(prefs.getFreqRespAmplitude());
        ampField.addSelectionListener(e -> prefs.setFreqRespAmplitude(ampField.enteredValue()));
        // The floor clamp and the tab-tile refresh ride the ONE entered value,
        // so an edit runs them once.  The floor is written in the unit the
        // operator is working in - clamping must not silently move them back to
        // volts.
        Bindings.onChange(toolbarTabs, prefs.freqRespAmplitudeProperty(), v -> {
            DeviceCalibration dac = audio.calibrationOrDefault(false);
            if (dac.amplitudeVrms(v, Channel.L) < FreqRespConstants.AMP_MIN_VRMS) {
                prefs.setFreqRespAmplitude(
                        dac.amplitudeIn(FreqRespConstants.AMP_MIN_VRMS, v.unit(), Channel.L));
            }
            toolbarTabs.refreshTab(TAB_FREQRESP_SETTINGS);
        });

        // FFT-size combo replaces the old sweep-duration field.  The
        // analyzer picks {@code nextPow2(leadIn + sweep + tail)} as its
        // deconvolution length, so by letting the user pick FFT size
        // directly we can solve back for the sweep duration that lands
        // exactly on that pow2 - no wasted bins, and the label shows
        // the user how long the actual sweep will run.
        fftSizeLabel = new Label(g, SWT.NONE);
        fftSizeLabel.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        Combo fftSizeCombo = new Combo(g, SWT.READ_ONLY);
        for (String s : offeredFftSizeLabels) fftSizeCombo.add(s);
        // The heap cap is fixed for the process; only the language moves
        // the tooltip.
        Bindings.text(fftSizeCombo, () -> {
            String sizeTip = I18n.t("freqResp.settings.fftSize.tooltip");
            if (offeredFftSizes.length < FFT_SIZE_VALUES.length) {
                sizeTip += "\n" + I18n.t("freqResp.settings.fftSize.heapCapped");
            }
            return sizeTip;
        }, fftSizeCombo::setToolTipText);
        fftSizeCombo.setLayoutData(comboFillGd());
        // Index-mapped combo (selection index -> FFT_SIZE_VALUES[idx] sample
        // count), so it can't use the ordinal-based Bindings.combo - the
        // hand-wired helper mirrors that contract over the int value array.
        // The sweep duration is DERIVED by the controller's own fftSize /
        // leadIn subscriptions; the tab only renders - the label shows the
        // whole capture the chosen size holds, the tile the chosen size.
        bindFftSizeCombo(fftSizeCombo, prefs.freqRespFftSizeProperty());
        Bindings.onChange(toolbarTabs, prefs.freqRespFftSizeProperty(), n -> {
            refreshFftSizeLabel();
            toolbarTabs.refreshTab(TAB_FREQRESP_SETTINGS);
        });
        Bindings.onChange(toolbarTabs, prefs.freqRespDurationSecProperty(), v -> {
            refreshFftSizeLabel();
            toolbarTabs.refreshTab(TAB_FREQRESP_SETTINGS);
        });
        Bindings.onChange(fftSizeLabel, I18n.localeProperty(), l -> refreshFftSizeLabel());
        refreshFftSizeLabel();

        // ---- Row 3: sweep points + lead-in ---------------------------------
        addLabel(g, "freqResp.settings.points");
        // List field replacing the old preset dropdown + "Manual..." prompt:
        // the wheel jumps along the power-of-2 presets (plus the runtime
        // "Nyquist/2" entry, shown as text), free typing covers everything
        // between.
        NumericStepField pointsField = new NumericStepField(g, UnitFamily.NONE,
                SWEEP_POINTS_MIN, SWEEP_POINTS_MAX, sweepPointSeries(), 0, 110);
        showHalfScalePoints(pointsField);
        Bindings.onChange(pointsField, I18n.localeProperty(), l -> showHalfScalePoints(pointsField));
        Bindings.text(pointsField, "freqResp.settings.points.tooltip", pointsField::setToolTipText);
        pointsField.setLayoutData(comboGd());
        Bindings.stepFieldInt(pointsField, prefs.freqRespSweepPointsProperty());
        Bindings.onChange(toolbarTabs, prefs.freqRespSweepPointsProperty(),
                v -> toolbarTabs.refreshTab(TAB_FREQRESP_SETTINGS));

        addLabel(g, "freqResp.settings.leadIn");
        NumericStepField leadInField = new NumericStepField(g, UnitFamily.TIME,
                FreqRespConstants.LEAD_IN_MIN_SEC, TIME_MAX_SEC, TIME_MAX_DECIMALS, 90);
        leadInField.setLayoutData(comboGd());
        Bindings.text(leadInField, "freqResp.settings.leadIn.tooltip", leadInField::setToolTipText);
        // Two-way bind.  The ≥ 0.05 s floor clamp and the derived-duration
        // coupling (lead-in eats into the same FFT window) are the
        // controller's leadIn subscription; the label follows the derived
        // durationSec pref via the onChange above.
        Bindings.stepField(leadInField, prefs.freqRespLeadInSecProperty());

        // ---- Row 4: dither + nyquist fraction ------------------------------
        addLabel(g, "freqResp.settings.dither");
        Combo ditherCombo = new Combo(g, SWT.READ_ONLY);
        for (int i = 0; i <= 31; i++) ditherCombo.add(i == 0 ? "Off" : String.valueOf(i));
        ditherCombo.setLayoutData(comboFillGd());
        Bindings.text(ditherCombo, "freqResp.settings.dither.tooltip", ditherCombo::setToolTipText);
        // Index-mapped combo where the selection index IS the bit count, so
        // it can't use the ordinal-based Bindings.combo (no enum) but needs no
        // value-array indirection either.  No side-effects beyond the pref
        // write, so no onChange.
        bindDitherCombo(ditherCombo, prefs.freqRespDitherBitsProperty());

        // Output-lane gate: which DAC channel(s) the sweep drives.  Both capture
        // channels are still deconvolved; the view's L/R buttons pick which
        // trace shows.  Ordinal-bound to OutputChannels {BOTH, LEFT, RIGHT},
        // mirroring the generator pane's combo.
        addLabel(g, "freqResp.settings.outputChannel");
        Combo outputChannelCombo = new Combo(g, SWT.READ_ONLY);
        Bindings.items(outputChannelCombo, () -> new String[]{
                I18n.t("common.channel.both"),
                I18n.t("common.channel.left"),
                I18n.t("common.channel.right") });
        Bindings.text(outputChannelCombo, "freqResp.settings.outputChannel.tooltip",
                outputChannelCombo::setToolTipText);
        outputChannelCombo.setLayoutData(comboFillGd());
        Bindings.combo(outputChannelCombo, prefs.freqRespOutputChannelsProperty(),
                OutputChannels.values());

        // Audio-format edits (Preferences OK, UI thread) move the Nyquist
        // ceiling of the sweep band edges and the sample-rate/2 entry of the
        // sweep-points series - re-pull both from the committed prefs.
        Consumer<Void> audioFormatListener = ignored -> {
            if (isDisposed()) return;
            double ceiling = sweepBandCeilingHz();
            startField.setMax(ceiling);
            stopField.setMax(ceiling);
            clampSweepBandToCeiling();
            pointsField.setSeries(sweepPointSeries());
            showHalfScalePoints(pointsField);
        };
        MessageBus.instance().subscribe(Events.AUDIO_FORMAT_CHANGED, audioFormatListener);
        addDisposeListener(e ->
                MessageBus.instance().unsubscribe(Events.AUDIO_FORMAT_CHANGED, audioFormatListener));
    }

    /** Two-way binds the dither {@link Combo} (index == bit count, 0 = Off)
     *  to its {@code Integer} {@link Property}.  Mirrors {@link Bindings#combo}
     *  but for an index-as-value READ_ONLY combo rather than an enum, with the
     *  selection clamped to {@code [0, 31]} on seed and external change. */
    private void bindDitherCombo(Combo combo, Property<Integer> property) {
        combo.select(Math.max(0, Math.min(31, property.get())));
        combo.addListener(SWT.Selection, e -> {
            int i = combo.getSelectionIndex();
            if (i >= 0) {
                property.set(i);
            }
        });
        Consumer<Integer> onChange = v -> {
            int i = Math.max(0, Math.min(31, v));
            if (!combo.isDisposed() && combo.getSelectionIndex() != i) {
                combo.select(i);
            }
        };
        property.addListener(onChange);
        combo.addDisposeListener(e -> property.removeListener(onChange));
    }

    /** Two-way binds the FFT-size {@link Combo} (selection index ->
     *  {@link #FFT_SIZE_VALUES}{@code [idx]} sample count) to its
     *  {@code Integer} {@link Property}.  Mirrors {@link Bindings#combo} over
     *  the int value array; falls back to index 0 (64k) when the pref value
     *  matches no entry, per {@link #selectFftSizeCombo}. */
    private void bindFftSizeCombo(Combo combo, Property<Integer> property) {
        int max = offeredFftSizes[offeredFftSizes.length - 1];
        if (property.get() > max) {
            // Persisted on a larger-heap run (or hand-edited): the parallel
            // deconvolution buffers cannot fit this JVM - clamp and persist.
            log.info("FreqResp FFT size {} exceeds the heap-capped maximum {} - clamped",
                    property.get(), max);
            property.set(max);
        }
        selectFftSizeCombo(combo, property.get());
        combo.addListener(SWT.Selection, e -> {
            int idx = combo.getSelectionIndex();
            if (idx >= 0 && idx < offeredFftSizes.length) {
                property.set(offeredFftSizes[idx]);
            }
        });
        Consumer<Integer> onChange = v -> {
            if (combo.isDisposed()) return;
            if (v > max) {
                property.set(max);   // preset from a larger-heap run - re-fires clamped
                return;
            }
            selectFftSizeCombo(combo, v);
        };
        property.addListener(onChange);
        combo.addDisposeListener(e -> property.removeListener(onChange));
    }

    private NumericStepField freqField(Composite parent) {
        NumericStepField f = new NumericStepField(parent, UnitFamily.FREQUENCY,
                FreqRespConstants.FREQ_MIN_HZ,
                Preferences.instance().current().getInputSampleRate() / 2.0,
                FreqRespConstants.FREQ_MAX_DECIMALS, 110);
        f.setLayoutData(comboGd());
        return f;
    }

    /** Sweep-point series for the points field in wheel order: the
     *  {@link #nyquistPointCount()} entry FIRST (matching the old dropdown),
     *  then the ascending power-of-2 presets. */
    private double[] sweepPointSeries() {
        double[] s = new double[SWEEP_POINT_SERIES.length + 1];
        s[0] = nyquistPointCount();
        System.arraycopy(SWEEP_POINT_SERIES, 0, s, 1, SWEEP_POINT_SERIES.length);
        return s;
    }

    /** Ceiling for the SWEPT BAND's two edges: the lower of the two configured
     *  Nyquist limits.  The band has to be both emittable and observable, and
     *  the DAC and the ADC carry their own configured rates - above the DAC's
     *  the chirp is not what leaves the card, above the ADC's it is not what
     *  comes back.  The other frequency fields on these tabs (the post-measure
     *  filters, the unevenness range) work on the measured curve alone, so they
     *  keep the analysis Nyquist that {@link #freqField} gives them. */
    private double sweepBandCeilingHz() {
        BackendPrefs backend = Preferences.instance().current();
        return Math.min(backend.getInputSampleRate(), backend.getOutputSampleRate()) / 2.0;
    }

    /** Brings the two stored swept-band edges down to {@link #sweepBandCeilingHz}.
     *
     *  <p>Setting the edge fields' maximum is not enough on its own: a field
     *  clamps what it DISPLAYS and writes back to the preference only when the
     *  displayed value actually moved, so a stored or preset-applied band above
     *  the ceiling stays in the preference while the field shows the ceiling -
     *  and the measurement reads the preference, not the field.  That gap would
     *  emit a chirp sweeping past the DAC's Nyquist while dividing it by a
     *  reference that does not alias. */
    private void clampSweepBandToCeiling() {
        double ceiling = sweepBandCeilingHz();
        Preferences p = Preferences.instance();
        if (p.getFreqRespStartHz() > ceiling) p.setFreqRespStartHz(ceiling);
        if (p.getFreqRespStopHz()  > ceiling) p.setFreqRespStopHz(ceiling);
    }

    /** The rate-derived sweep-points entry - one point per FFT bin up to
     *  Nyquist (sample rate / 2 points), rendered as the "Nyquist/2" label. */
    private double nyquistPointCount() {
        return Preferences.instance().current().getInputSampleRate() / 2.0;
    }

    /** Declares the points field's {@link #nyquistPointCount()} entry with its
     *  translated name - the one rule for the build, an audio-format edit
     *  (the count moves) and a language switch (the name moves). */
    private void showHalfScalePoints(NumericStepField pointsField) {
        pointsField.setNamedValue(nyquistPointCount(),
                I18n.t("freqResp.settings.points.halfScale"));
    }

    /** Selects the combo row whose FFT-size value matches the given
     *  number of samples.  Falls back to the smallest entry (64k) when
     *  the prefs value doesn't line up - should be impossible because
     *  the load-time snap rounds non-pow2 values to the next legal
     *  one, but defensive anyway. */
    private void selectFftSizeCombo(Combo combo, int currentFftSize) {
        for (int i = 0; i < offeredFftSizes.length; i++) {
            if (offeredFftSizes[i] == currentFftSize) { combo.select(i); return; }
        }
        combo.select(0);
    }

    /** Updates the FFT-size label's caption to "FFT size (D.Ds)" where
     *  D.D is the whole capture the chosen size holds at the capture rate -
     *  lead-in, sweep and tail together, which is what the measurement
     *  takes; the sweep alone is the derived remainder and is not shown. */
    private void refreshFftSizeLabel() {
        if (fftSizeLabel == null || fftSizeLabel.isDisposed()) return;
        Preferences prefs = Preferences.instance();
        double dur = prefs.getFreqRespFftSize()
                / (double) Math.max(1, prefs.current().getInputSampleRate());
        fftSizeLabel.setText(I18n.t("freqResp.settings.fftSize")
                + " (" + String.format(Locale.ROOT, "%.1f", dur) + "s)");
        fftSizeLabel.requestLayout();
    }

    // -------------------------------------------------------------------------
    // RIAA & IEC tab - chained checkboxes + Compare
    // -------------------------------------------------------------------------

    private void buildRiaaTab() {
        Composite g = groupCell(toolbarTabs, "freqResp.tab.riaa");
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

        // Show / Reverse / IEC are two-way bound to their prefs; the view
        // subscribes to each (redraw, plus Show's one-shot compare auto-zoom)
        // in its own constructor.  Only the pane-local effects stay here: the
        // enable cascade (Show gates Reverse / IEC / Compare; Reverse re-runs
        // it too) and the RIAA tab-tile refresh.  IEC gates nothing, so it
        // skips refreshRiaaEnable, exactly as the old listener did.
        Bindings.check(riaaShowBtn,    prefs.freqRespShowRiaaProperty());
        Bindings.check(riaaReverseBtn, prefs.freqRespReverseRiaaProperty());
        Bindings.check(riaaIecBtn,     prefs.freqRespIecAmendmentProperty());
        Bindings.onChange(toolbarTabs, prefs.freqRespShowRiaaProperty(), v -> {
            // Only one reference curve can be active - enabling RIAA turns the
            // filter overlay off (symmetric with the filter-show handler in
            // buildFiltersTab).
            if (v) prefs.setFreqRespShowFilter(false);
            refreshRiaaEnable();
            toolbarTabs.refreshTab(TAB_FREQRESP_RIAA);
        });
        Bindings.onChange(toolbarTabs, prefs.freqRespReverseRiaaProperty(), v -> {
            refreshRiaaEnable();
            toolbarTabs.refreshTab(TAB_FREQRESP_RIAA);
        });
        Bindings.onChange(toolbarTabs, prefs.freqRespIecAmendmentProperty(),
                v -> toolbarTabs.refreshTab(TAB_FREQRESP_RIAA));

        // Compare is two-way bound; its pane-local effects (the no-measurement
        // veto, the one-shot auto-zoom on entry, the view redraw and the tab-
        // tile refresh) ride an onChange.  The view doesn't subscribe to the
        // compare-mode pref itself, so the redraw stays here.
        Bindings.check(riaaCompareBtn, prefs.freqRespCompareModeProperty());
        Bindings.onChange(toolbarTabs, prefs.freqRespCompareModeProperty(), enable -> {
            if (enable && !view.hasAnyResult()) {
                Dialogs.info(g.getShell(), I18n.t("freqResp.tab.riaa"),
                        I18n.t("freqResp.error.compare.noMeasurement"));
                // Veto: roll the pref back, which echoes through the bind to
                // uncheck the box.  The re-entry sees enable == false and runs
                // the exit auto-fit - a no-op here, since the veto only fires
                // when there is no measurement to fit.
                prefs.setFreqRespCompareMode(false);
                return;
            }
            // One-shot auto-zoom on entry only - the user's subsequent pan /
            // zoom must stick instead of being clobbered on every redraw.  On
            // exit, refit to the measured curve (the compare window - ±pad
            // around 0 dB - is meaningless for absolute levels).
            if (enable) view.autoSetupCompare(prefs);
            else       view.autoSetupMagnitudeRange();
            view.redraw();
            toolbarTabs.refreshTab(TAB_FREQRESP_RIAA);
        });

        refreshRiaaEnable();
    }

    private Button checkbox(Composite parent, String labelKey, String tipKey, boolean initial) {
        Button b = new Button(parent, SWT.CHECK);
        Bindings.text(b, labelKey, b::setText);
        Bindings.text(b, tipKey, b::setToolTipText);
        b.setSelection(initial);
        b.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, true, false));
        return b;
    }

    // -------------------------------------------------------------------------
    // Filters tab - ideal filter overlay vs measured response (mirrors RIAA)
    // -------------------------------------------------------------------------

    /** Tab-body columns: [header/group] [header/group] [picture].
     *  Cols 1-2 hold the header pairs and the group below; col3 the picture. */
    private static final int FILTER_TAB_COLS = 3;

    /** Column count shared by the header grid (row 1 Show|Compare aligned over
     *  row 2 type|combo|response|combo). */
    private static final int FILTER_HEADER_COLS = 4;

    /** Spec parameter grid columns: three label+field pairs per
     *  row, one grid shared by both rows and both modes so fields align
     *  vertically; Mode-1 Row B fills all three pairs (Center|Passband|
     *  Stopband), the shorter mode/row leave trailing pairs empty. */
    private static final int FILTER_SPEC_COLS = 6;

    private void buildFiltersTab() {
        // Three-column tab grid: col1+col2 carry the header pairs and
        // (below, spanning both) the Settings group; col3 carries the per-type
        // picture, native 1:1 and TOP-aligned, spanning both rows and living
        // OUTSIDE the bordered group.
        Composite g = groupCell(toolbarTabs, "freqResp.tab.filters");
        GridLayout gl = new GridLayout(FILTER_TAB_COLS, false);
        gl.marginWidth = 8; gl.marginHeight = 6;
        gl.horizontalSpacing = 12; gl.verticalSpacing = 6;
        g.setLayout(gl);
        Preferences prefs = Preferences.instance();

        // ---- Header grid (cols 1-2 of the tab): row1 (Show | Compare) column-
        //      aligned over row2 (type label+combo | response label+combo).  One
        //      4-column grid so Compare sits over "Filter response" and its
        //      combo.  The pairs pack LEFT and compact - no horizontal grab
        //      between the two columns:
        //        col0: Show          / "Filter type"     label
        //        col1: (Show spans->) / type combo
        //        col2: Compare       / "Filter response" label
        //        col3: (Compare span)/ response combo
        Composite header = new Composite(g, SWT.NONE);
        GridData headerGd = new GridData(SWT.LEFT, SWT.TOP, false, false);
        headerGd.horizontalSpan = 2;
        header.setLayoutData(headerGd);
        GridLayout hl = new GridLayout(FILTER_HEADER_COLS, false);
        hl.marginWidth = 0; hl.marginHeight = 0;
        hl.horizontalSpacing = 6; hl.verticalSpacing = 4;
        header.setLayout(hl);

        // Row 1 - Show in col0-1, Compare in col2-3 (over the response combo).
        filterShowBtn = checkbox(header, "freqResp.filter.show", "freqResp.filter.show.tooltip",
                prefs.isFreqRespShowFilter());
        span(filterShowBtn, 2);
        filterCompareBtn = checkbox(header, "freqResp.filter.compare", "freqResp.filter.compare.tooltip",
                prefs.isFreqRespFilterCompare());
        span(filterCompareBtn, 2);

        // Row 2 - type label+combo (col0-1), response label+combo (col2-3).
        addLabel(header, "freqResp.filter.type");
        filterTypeCombo = enumCombo(header, "freqResp.filter.type.tooltip",
                new String[]{ "freqResp.filter.type.lowpass", "freqResp.filter.type.highpass",
                        "freqResp.filter.type.bandpass", "freqResp.filter.type.notch" },
                prefs.freqRespFilterTypeProperty(), FilterType.values());
        addLabel(header, "freqResp.filter.response");
        filterResponseCombo = enumCombo(header, "freqResp.filter.response.tooltip",
                new String[]{ "freqResp.filter.response.bessel", "freqResp.filter.response.butterworth",
                        "freqResp.filter.response.chebyshev", "freqResp.filter.response.elliptic",
                        "freqResp.filter.response.invchebyshev" },
                prefs.freqRespFilterResponseProperty(), FilterResponse.values());

        // ---- Per-type picture (tab col3), native 1:1, TOP-aligned, spanning
        //      both tab rows so it sits to the right of the header AND the group.
        //      Hidden in By-order mode.  Created before
        //      the group so it takes col3 of the header row; the group below
        //      then spans cols 1-2 of the next row and the picture's rowspan
        //      covers it.
        loadFilterPictures(g.getDisplay());
        filterPictureLabel = new Label(g, SWT.NONE);
        GridData picGd = new GridData(SWT.LEFT, SWT.TOP, false, false);
        picGd.verticalSpan = 2;
        filterPictureLabel.setLayoutData(picGd);

        // ---- Bordered "Settings" group (tab cols 1-2, next row): mode radios +
        //      one 6-column parameter grid of fixed stacked cells.
        //      The group does NOT stretch to the tab width - no FILL / grab, so
        //      it shrinks to its content.
        filterSpecGroup = new Group(g, SWT.NONE);
        Bindings.text(filterSpecGroup, "freqResp.filter.spec", filterSpecGroup::setText);
        GridData groupGd = new GridData(SWT.LEFT, SWT.TOP, false, false);
        groupGd.horizontalSpan = 2;
        filterSpecGroup.setLayoutData(groupGd);
        GridLayout sg = new GridLayout(1, false);
        sg.marginWidth = 6; sg.marginHeight = 4;
        sg.verticalSpacing = 4;
        filterSpecGroup.setLayout(sg);

        // Mode radios.
        Composite modeRow = new Composite(filterSpecGroup, SWT.NONE);
        modeRow.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        GridLayout ml = new GridLayout(2, false);
        ml.marginWidth = 0; ml.marginHeight = 0; ml.horizontalSpacing = 12;
        modeRow.setLayout(ml);
        filterMode1Radio = radioButton(modeRow, "freqResp.filter.mode1", "freqResp.filter.mode1.tooltip");
        filterMode2Radio = radioButton(modeRow, "freqResp.filter.mode2", "freqResp.filter.mode2.tooltip");
        // Mode radio is part of the per-type params (modeOrder): Mode-2 selected
        // means by-order.  SWT fires Selection only on the button that RECEIVED
        // the click (the sibling is deselected silently), so both radios carry
        // the same listener and read the settled state off filterMode2Radio; the
        // seed comes from loadFilterParams.  refreshFilterEnable then swaps the
        // field visibility for the new mode.
        Listener modeListener = e -> {
            if (filterParamsLoading) return;
            putFilterParam(p -> p.setModeOrder(filterMode2Radio.getSelection()));
            refreshFilterEnable();
        };
        filterMode1Radio.addListener(SWT.Selection, modeListener);
        filterMode2Radio.addListener(SWT.Selection, modeListener);

        // ---- One 6-column parameter grid shared by BOTH rows and BOTH modes.
        //      Every cell is a fixed StackLayout wrapper overlaying
        //      that slot's Mode-1 and Mode-2 control; switching modes only flips
        //      the wrapper's top control, so each field stays pinned to its
        //      column in both modes - no exclusion-reflow.  Three label+field
        //      pairs per row; label wrappers size to their widest child, field
        //      wrappers are fixed-width, so columns line up vertically.
        Composite specGrid = new Composite(filterSpecGroup, SWT.NONE);
        specGrid.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));
        GridLayout pg = new GridLayout(FILTER_SPEC_COLS, false);
        pg.marginWidth = 0; pg.marginHeight = 0;
        pg.horizontalSpacing = 6; pg.verticalSpacing = 4;
        specGrid.setLayout(pg);

        // -- Row A --  Mode 1: Ripple | Stopband atten. | (empty)
        //              Mode 2: Passband freq | Ripple    | (empty)
        Composite a1l = specSlot(specGrid, false);
        filterRippleLabel = addLabel(a1l, "freqResp.filter.ripple");
        filterOrderPassLabel = addLabel(a1l, "freqResp.filter.mode2.passfreq");
        registerCell(a1l, filterRippleLabel, filterOrderPassLabel);

        Composite a1f = specSlot(specGrid, true);
        filterRippleField = dbField(a1f, "freqResp.filter.ripple.tooltip");
        onFilterField(filterRippleField, (p, v) -> p.setRippleDb(v));
        filterOrderPassField = freqField(a1f);
        Bindings.text(filterOrderPassField, "freqResp.filter.mode2.passfreq.tooltip",
                filterOrderPassField::setToolTipText);
        onFilterField(filterOrderPassField, (p, v) -> p.setOrderPassHz(v));
        registerCell(a1f, filterRippleField, filterOrderPassField);

        Composite a2l = specSlot(specGrid, false);
        filterStopAttenLabel = addLabel(a2l, "freqResp.filter.stopatten");
        filterOrderRippleLabel = addLabel(a2l, "freqResp.filter.mode2.ripple");
        registerCell(a2l, filterStopAttenLabel, filterOrderRippleLabel);

        Composite a2f = specSlot(specGrid, true);
        filterStopAttenField = dbField(a2f, "freqResp.filter.stopatten.tooltip");
        onFilterField(filterStopAttenField, (p, v) -> p.setStopAttenDb(v));
        filterOrderRippleField = dbField(a2f, "freqResp.filter.mode2.ripple.tooltip");
        onFilterField(filterOrderRippleField, (p, v) -> p.setOrderRippleDb(v));
        registerCell(a2f, filterStopAttenField, filterOrderRippleField);

        // Row-A pair 3 - empty in both modes (spacer slots keep the grid 6 cols).
        registerCell(specSlot(specGrid, false), null, null);
        registerCell(specSlot(specGrid, true), null, null);

        // -- Row B --  Mode 1: Center | Passband | Stopband
        //              Mode 2: Order  | Q        | (empty)
        Composite b1l = specSlot(specGrid, false);
        filterCenterLabel = addLabel(b1l, "freqResp.filter.center");
        filterOrderLabel = addLabel(b1l, "freqResp.filter.order");
        registerCell(b1l, filterCenterLabel, filterOrderLabel);

        Composite b1f = specSlot(specGrid, true);
        filterCenterField = freqField(b1f);
        Bindings.text(filterCenterField, "freqResp.filter.center.tooltip",
                filterCenterField::setToolTipText);
        onFilterField(filterCenterField, (p, v) -> p.setCenterHz(v));
        filterOrderField = new NumericStepField(b1f, UnitFamily.NONE,
                FILTER_ORDER_MIN, FILTER_ORDER_MAX, 0, 110);
        filterOrderField.setLayoutData(comboGd());
        Bindings.text(filterOrderField, "freqResp.filter.order.tooltip",
                filterOrderField::setToolTipText);
        onFilterField(filterOrderField, (p, v) -> p.setOrder((int) Math.round(v)));
        registerCell(b1f, filterCenterField, filterOrderField);

        Composite b2l = specSlot(specGrid, false);
        // The Passband / Stopband captions depend on the filter type, so no
        // fixed key is bound to them: refreshFilterEnable picks and shows
        // the caption, and re-runs on a language switch (below).
        filterPassLabel = new Label(b2l, SWT.NONE);
        filterPassLabel.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        filterQLabel = addLabel(b2l, "freqResp.filter.q");
        registerCell(b2l, filterPassLabel, filterQLabel);

        Composite b2f = specSlot(specGrid, true);
        filterPassField = freqField(b2f);
        Bindings.text(filterPassField, "freqResp.filter.passband.tooltip",
                filterPassField::setToolTipText);
        onFilterField(filterPassField, (p, v) -> p.setPassHz(v));
        filterQField = new NumericStepField(b2f, UnitFamily.NONE,
                FILTER_Q_MIN, FILTER_Q_MAX, FILTER_Q_DECIMALS, 110);
        filterQField.setLayoutData(comboGd());
        Bindings.text(filterQField, "freqResp.filter.q.tooltip", filterQField::setToolTipText);
        onFilterField(filterQField, (p, v) -> p.setQ(v));
        registerCell(b2f, filterPassField, filterQField);

        // Row-B pair 3 - Stopband label+field in Mode 1 only; empty in Mode 2.
        Composite b3l = specSlot(specGrid, false);
        filterStopLabel = new Label(b3l, SWT.NONE);
        filterStopLabel.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        registerCell(b3l, filterStopLabel, null);

        Composite b3f = specSlot(specGrid, true);
        filterStopField = freqField(b3f);
        Bindings.text(filterStopField, "freqResp.filter.stopband.tooltip",
                filterStopField::setToolTipText);
        onFilterField(filterStopField, (p, v) -> p.setStopHz(v));
        registerCell(b3f, filterStopField, null);

        // ---- Bindings: Show / Compare + the enable / redraw side-effects ---
        Bindings.check(filterShowBtn, prefs.freqRespShowFilterProperty());
        Bindings.onChange(toolbarTabs, prefs.freqRespShowFilterProperty(), v -> {
            // Only one reference curve can be active - enabling the filter
            // overlay turns RIAA off (symmetric with the RIAA-show handler).
            if (v) prefs.setFreqRespShowRiaa(false);
            refreshFilterEnable();
            toolbarTabs.refreshTab(TAB_FREQRESP_FILTERS);
        });

        // Compare: same no-measurement veto + one-shot auto-zoom + redraw as the
        // RIAA compare, reusing the generalized view.autoSetupCompare pipeline.
        Bindings.check(filterCompareBtn, prefs.freqRespFilterCompareProperty());
        Bindings.onChange(toolbarTabs, prefs.freqRespFilterCompareProperty(), enable -> {
            if (enable && !view.hasAnyResult()) {
                Dialogs.info(g.getShell(), I18n.t("freqResp.tab.filters"),
                        I18n.t("freqResp.error.compare.noMeasurement"));
                prefs.setFreqRespFilterCompare(false);
                return;
            }
            if (enable) view.autoSetupCompare(prefs);
            else       view.autoSetupMagnitudeRange();   // exit -> refit to the measured curve
            view.redraw();
            toolbarTabs.refreshTab(TAB_FREQRESP_FILTERS);
        });

        // The per-type params map is the single source of truth: seed every
        // widget (fields + mode radio) FROM the current type's map entry at
        // build (defaults on miss), never the other way round, so startup never
        // clobbers the persisted map.  On a Filter-type change, load the new
        // type's entry into the widgets and re-gate + swap labels / picture /
        // mode visibility to match.
        loadFilterParams(prefs.getFreqRespFilterType());
        Bindings.onChange(toolbarTabs, prefs.freqRespFilterTypeProperty(), v -> {
            loadFilterParams(v);
            refreshFilterEnable();
            toolbarTabs.refreshTab(TAB_FREQRESP_FILTERS);
        });
        Bindings.onChange(toolbarTabs, prefs.freqRespFilterResponseProperty(),
                v -> refreshFilterEnable());
        // The cascade is the one place that picks the per-type Passband /
        // Stopband captions, so a language switch re-runs it.
        Bindings.onChange(filterPassLabel, I18n.localeProperty(), l -> refreshFilterEnable());

        // Audio-format edits move the Nyquist ceiling of every Hz field.
        Consumer<Void> audioFormatListener = ignored -> {
            if (isDisposed()) return;
            double nyquist = Preferences.instance().current().getInputSampleRate() / 2.0;
            filterCenterField.setMax(nyquist);
            filterPassField.setMax(nyquist);
            filterStopField.setMax(nyquist);
            filterOrderPassField.setMax(nyquist);
        };
        MessageBus.instance().subscribe(Events.AUDIO_FORMAT_CHANGED, audioFormatListener);
        addDisposeListener(e ->
                MessageBus.instance().unsubscribe(Events.AUDIO_FORMAT_CHANGED, audioFormatListener));

        g.addDisposeListener(e -> disposeFilterPictures());
        refreshFilterEnable();
    }

    /** Sets a {@link GridData} horizontal span on an already-created control,
     *  preserving its left/centre alignment. */
    private void span(Button b, int cols) {
        GridData gd = new GridData(SWT.LEFT, SWT.CENTER, true, false);
        gd.horizontalSpan = cols;
        b.setLayoutData(gd);
    }

    /** Write-through for one filter parameter field: applies {@code mutator} to
     *  the CURRENT filter type's map entry, persists it via
     *  {@link Preferences#putFreqRespFilterParams} (the single source of truth,
     *  which also saves), and notifies the shared view directly (the tab holds
     *  it).  The params map carries no {@link Property} the view could follow,
     *  and a redraw alone is not enough: with the ideal filter as the compared
     *  reference, the compare max / min and the magnitude ceiling derive from
     *  the filter curve and would keep the old filter's values.
     *  {@code getFreqRespFilterParams} hands back the live entry (or a fresh
     *  {@code fromType} default on a miss), so mutating + putting the same key
     *  round-trips exactly one entry. */
    private void putFilterParam(Consumer<FreqRespFilterTypeParams> mutator) {
        Preferences prefs = Preferences.instance();
        FilterType type = prefs.getFreqRespFilterType();
        FreqRespFilterTypeParams p = prefs.getFreqRespFilterParams(type);
        mutator.accept(p);
        prefs.putFreqRespFilterParams(type, p);
        view.onFilterParamsChanged();
    }

    /** Deep copy of a {@link FreqRespFilterTypeParams} so a preset embeds its
     *  OWN snapshot rather than aliasing the live map entry (and vice-versa on
     *  apply).  {@link Preferences#getFreqRespFilterParams} hands back the live
     *  object, so both preset capture and apply copy across the boundary. */
    private FreqRespFilterTypeParams copyFilterParams(FreqRespFilterTypeParams s) {
        FreqRespFilterTypeParams c = new FreqRespFilterTypeParams();
        c.setModeOrder(s.isModeOrder());
        c.setRippleDb(s.getRippleDb());
        c.setStopAttenDb(s.getStopAttenDb());
        c.setCenterHz(s.getCenterHz());
        c.setPassHz(s.getPassHz());
        c.setStopHz(s.getStopHz());
        c.setOrderPassHz(s.getOrderPassHz());
        c.setOrderRippleDb(s.getOrderRippleDb());
        c.setOrder(s.getOrder());
        c.setQ(s.getQ());
        return c;
    }

    /** Wires one {@link NumericStepField} to the per-type params map: on a
     *  committed edit it writes {@code setter}(entry, value) through
     *  {@link #putFilterParam}.  The guard skips writes made while
     *  {@link #loadFilterParams} is seeding the field (a programmatic
     *  {@code setValue} fires the same listener), so a type switch can't write
     *  the just-loaded values back onto the old type. */
    private void onFilterField(NumericStepField field, ObjDoubleConsumer<FreqRespFilterTypeParams> setter) {
        field.addSelectionListener(e -> {
            if (filterParamsLoading) return;
            putFilterParam(p -> setter.accept(p, field.getValue()));
        });
    }

    /** Seeds every filter widget (fields + mode radio) FROM {@code type}'s map
     *  entry - a missing entry falls back to
     *  {@link FreqRespFilterTypeParams#fromType(FilterType)} defaults, valid for
     *  the type's edge semantics.  This is the ONLY direction at build / type
     *  switch: the map is authoritative, so the widgets follow it and never the
     *  reverse.  The re-entrancy guard stops the programmatic {@code setValue} /
     *  {@code setSelection} from writing the values straight back through the
     *  field / radio listeners. */
    private void loadFilterParams(FilterType type) {
        FreqRespFilterTypeParams p = Preferences.instance().getFreqRespFilterParams(type);
        filterParamsLoading = true;
        try {
            filterRippleField.setValue(p.getRippleDb());
            filterStopAttenField.setValue(p.getStopAttenDb());
            filterCenterField.setValue(p.getCenterHz());
            filterPassField.setValue(p.getPassHz());
            filterStopField.setValue(p.getStopHz());
            filterOrderPassField.setValue(p.getOrderPassHz());
            filterOrderRippleField.setValue(p.getOrderRippleDb());
            filterOrderField.setValue(p.getOrder());
            filterQField.setValue(p.getQ());
            filterMode1Radio.setSelection(!p.isModeOrder());
            filterMode2Radio.setSelection(p.isModeOrder());
        } finally {
            filterParamsLoading = false;
        }
    }

    /** Builds a labeled READ_ONLY enum {@link Combo}: items come from
     *  {@code itemKeys} (i18n) in enum order and follow the language, and it
     *  is two-way bound to the enum {@link Property} via {@link Bindings#combo}. */
    private <T extends Enum<T>> Combo enumCombo(Composite parent, String tipKey,
                                                String[] itemKeys, Property<T> property, T[] values) {
        Combo combo = new Combo(parent, SWT.READ_ONLY);
        Bindings.items(combo, () -> Arrays.stream(itemKeys)
                .map(key -> I18n.t(key)).toArray(String[]::new));
        combo.setLayoutData(comboGd());
        Bindings.text(combo, tipKey, combo::setToolTipText);
        Bindings.combo(combo, property, values);
        return combo;
    }

    /** A dB-unit {@link NumericStepField}; the caller wires its per-type
     *  params write-through via {@link #onFilterField}. */
    private NumericStepField dbField(Composite parent, String tipKey) {
        NumericStepField f = new NumericStepField(parent, UnitFamily.DECIBEL,
                FILTER_DB_MIN, FILTER_DB_MAX, FILTER_DB_DECIMALS, 110);
        f.setLayoutData(comboGd());
        Bindings.text(f, tipKey, f::setToolTipText);
        return f;
    }

    private Button radioButton(Composite parent, String labelKey, String tipKey) {
        Button b = new Button(parent, SWT.RADIO);
        Bindings.text(b, labelKey, b::setText);
        Bindings.text(b, tipKey, b::setToolTipText);
        b.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        return b;
    }

    /** Creates one fixed cell of the spec grid: a {@link StackLayout} wrapper
     *  occupying a single grid cell into which the caller creates that slot's
     *  Mode-1 and Mode-2 control (then registers them via
     *  {@link #registerCell}).  {@code field} slots carry the shared field width
     *  hint so every field column lines up; label slots size to their widest
     *  child.  The wrapper never leaves its cell, so the columns don't reflow
     *  when the mode swaps. */
    private Composite specSlot(Composite grid, boolean field) {
        Composite w = new Composite(grid, SWT.NONE);
        w.setLayout(new StackLayout());
        GridData gd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        if (field) gd.widthHint = 120;
        w.setLayoutData(gd);
        return w;
    }

    /** Registers a fixed spec cell so {@link #applyModeVisibility} can flip its
     *  visible child.  Either control may be {@code null} for a mode that leaves
     *  the slot empty. */
    private void registerCell(Composite wrapper, Control mode1, Control mode2) {
        specCells.add(new SpecCell(wrapper, (StackLayout) wrapper.getLayout(), mode1, mode2));
    }

    /** Loads and caches the four per-type parameter pictures from
     *  {@code /imgs/} at their native PNG size (1:1, no scaling).
     *  The tab owns these images and disposes them in
     *  {@link #disposeFilterPictures()}. */
    private void loadFilterPictures(Display display) {
        loadFilterPicture(display, FilterType.LOW_PASS,  "LPF");
        loadFilterPicture(display, FilterType.HIGH_PASS, "HPF");
        loadFilterPicture(display, FilterType.BAND_PASS, "BPF");
        loadFilterPicture(display, FilterType.NOTCH,     "Notch");
    }

    private void loadFilterPicture(Display display, FilterType type, String name) {
        try (InputStream in = getClass().getResourceAsStream("/imgs/" + name + ".png")) {
            if (in == null) {
                log.warn("Filter picture missing: /imgs/{}.png", name);
                return;
            }
            filterPictures.put(type, new Image(display, new ImageData(in)));
        } catch (Exception ex) {
            log.warn("Filter picture load failed: /imgs/{}.png", name, ex);
        }
    }

    private void disposeFilterPictures() {
        for (Image img : filterPictures.values()) {
            if (img != null && !img.isDisposed()) img.dispose();
        }
        filterPictures.clear();
    }

    // -------------------------------------------------------------------------
    // Unevenness tab - response flatness readout mode + parameters
    // -------------------------------------------------------------------------

    private void buildUnevennessTab() {
        Composite g = groupCell(toolbarTabs, "freqResp.tab.unevenness");
        GridLayout gl = new GridLayout(3, false);
        gl.marginWidth = 8; gl.marginHeight = 6;
        gl.horizontalSpacing = 6; gl.verticalSpacing = 4;
        g.setLayout(gl);
        Preferences prefs = Preferences.instance();

        // ---- Row 1: Off mode radio - disables the whole readout - plus the
        // Notch checkbox (explicit notch/peak flatness classification in LEVEL
        // mode); a span-1 filler keeps the 3-column grid rectangular.
        unevenOffRadio = new Button(g, SWT.RADIO);
        Bindings.text(unevenOffRadio, "freqResp.uneven.off", unevenOffRadio::setText);
        Bindings.text(unevenOffRadio, "freqResp.uneven.off.tooltip", unevenOffRadio::setToolTipText);
        unevenOffRadio.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        unevenNotchCheck = new Button(g, SWT.CHECK);
        Bindings.text(unevenNotchCheck, "freqResp.uneven.notch", unevenNotchCheck::setText);
        Bindings.text(unevenNotchCheck, "freqResp.uneven.notch.tooltip",
                unevenNotchCheck::setToolTipText);
        unevenNotchCheck.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        Bindings.check(unevenNotchCheck, prefs.freqRespUnevenNotchProperty());
        Label unevenOffFiller = new Label(g, SWT.NONE);
        GridData unevenOffFillerGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        unevenOffFillerGd.horizontalSpan = 1;
        unevenOffFiller.setLayoutData(unevenOffFillerGd);

        // ---- Row 2: ±dB mode radio (carries its label) + Unevenness field --
        // The mode label lives ON the radio so clicking the text selects the
        // mode, instead of sitting next to it as a separate, dead label.
        unevenPmRadio = new Button(g, SWT.RADIO);
        Bindings.text(unevenPmRadio, "freqResp.uneven.db", unevenPmRadio::setText);
        unevenPmRadio.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        unevenDbField = new NumericStepField(g, UnitFamily.DECIBEL,
                UNEVEN_DB_MIN, UNEVEN_DB_MAX, FILTER_DB_DECIMALS, 110);
        GridData unevenDbGd = comboGd();
        unevenDbGd.horizontalSpan = 2;
        unevenDbField.setLayoutData(unevenDbGd);
        Bindings.text(unevenDbField, "freqResp.uneven.db.tooltip", unevenDbField::setToolTipText);
        Bindings.stepField(unevenDbField, prefs.freqRespUnevenDbProperty());

        // ---- Row 3: range mode radio (carries "Start freq") + fields -------
        unevenRangeRadio = new Button(g, SWT.RADIO);
        Bindings.text(unevenRangeRadio, "freqResp.uneven.start", unevenRangeRadio::setText);
        unevenRangeRadio.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        Composite rangeFields = new Composite(g, SWT.NONE);
        GridData rfGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        rfGd.horizontalSpan = 2;
        rangeFields.setLayoutData(rfGd);
        GridLayout rf = new GridLayout(3, false);
        rf.marginWidth = 0; rf.marginHeight = 0;
        rf.horizontalSpacing = 6; rf.verticalSpacing = 4;
        rangeFields.setLayout(rf);
        unevenStartField = freqField(rangeFields);
        Bindings.text(unevenStartField, "freqResp.uneven.start.tooltip",
                unevenStartField::setToolTipText);
        Bindings.stepField(unevenStartField, prefs.freqRespUnevenStartHzProperty());
        addLabel(rangeFields, "freqResp.uneven.stop");
        unevenStopField = freqField(rangeFields);
        Bindings.text(unevenStopField, "freqResp.uneven.stop.tooltip",
                unevenStopField::setToolTipText);
        Bindings.stepField(unevenStopField, prefs.freqRespUnevenStopHzProperty());
        // Cross-field clamp: stop must stay at least start + 1 (mirrors the
        // Settings tab's start / stop coupling).
        Bindings.onChange(toolbarTabs, prefs.freqRespUnevenStartHzProperty(), v -> {
            double ceil = prefs.getFreqRespUnevenStopHz() - 1.0;
            if (v > ceil) prefs.setFreqRespUnevenStartHz(ceil);
            toolbarTabs.refreshTab(TAB_FREQRESP_UNEVENNESS);
        });
        Bindings.onChange(toolbarTabs, prefs.freqRespUnevenStopHzProperty(), v -> {
            double floor = prefs.getFreqRespUnevenStartHz() + 1.0;
            if (v < floor) prefs.setFreqRespUnevenStopHz(floor);
            toolbarTabs.refreshTab(TAB_FREQRESP_UNEVENNESS);
        });

        // Three-state mode radios bound to the UnevenMode pref; each radio
        // enables only its own mode's fields (Off enables none).
        Map<Button, UnevenMode> modeMap = new LinkedHashMap<>();
        modeMap.put(unevenOffRadio,   UnevenMode.OFF);
        modeMap.put(unevenPmRadio,    UnevenMode.LEVEL);
        modeMap.put(unevenRangeRadio, UnevenMode.RANGE);
        Bindings.radio(modeMap, prefs.freqRespUnevenModeProperty());
        Bindings.onChange(toolbarTabs, prefs.freqRespUnevenModeProperty(), v -> {
            refreshUnevenEnable();
            toolbarTabs.refreshTab(TAB_FREQRESP_UNEVENNESS);
        });
        Bindings.onChange(toolbarTabs, prefs.freqRespUnevenDbProperty(),
                v -> toolbarTabs.refreshTab(TAB_FREQRESP_UNEVENNESS));

        // Audio-format edits move the Nyquist ceiling of the range fields.
        Consumer<Void> audioFormatListener = ignored -> {
            if (isDisposed()) return;
            double nyquist = Preferences.instance().current().getInputSampleRate() / 2.0;
            unevenStartField.setMax(nyquist);
            unevenStopField.setMax(nyquist);
        };
        MessageBus.instance().subscribe(Events.AUDIO_FORMAT_CHANGED, audioFormatListener);
        addDisposeListener(e ->
                MessageBus.instance().unsubscribe(Events.AUDIO_FORMAT_CHANGED, audioFormatListener));

        refreshUnevenEnable();
    }

    /** Each Unevenness mode enables only its own row's fields - the dB field
     *  iff LEVEL, the start / stop fields iff RANGE, the Notch checkbox in both
     *  active modes (it steers the LEVEL walk AND the RANGE extremum line),
     *  none in OFF. */
    private void refreshUnevenEnable() {
        if (unevenDbField == null || unevenDbField.isDisposed()) return;
        UnevenMode mode = Preferences.instance().getFreqRespUnevenMode();
        unevenDbField.setEnabled(mode == UnevenMode.LEVEL);
        if (unevenNotchCheck != null && !unevenNotchCheck.isDisposed()) {
            unevenNotchCheck.setEnabled(mode != UnevenMode.OFF);
        }
        boolean range = mode == UnevenMode.RANGE;
        unevenStartField.setEnabled(range);
        unevenStopField.setEnabled(range);
    }

    // -------------------------------------------------------------------------
    // Presets tab - named snapshots of every FreqResp Settings + RIAA pref
    // -------------------------------------------------------------------------

    private void buildPresetsTab() {
        Composite g = groupCell(toolbarTabs, "freqResp.tab.presets");
        g.setLayout(new FillLayout());
        new PresetBar<FreqRespPreset>(g, "freqResp.presets", null,
                new PresetBar.Store<>() {
                    @Override public Map<String, FreqRespPreset> presets() { return Preferences.instance().getFreqRespPresets(); }
                    @Override public void put(String name, FreqRespPreset p) { Preferences.instance().putFreqRespPreset(name, p); }
                    @Override public void remove(String name) { Preferences.instance().removeFreqRespPreset(name); }
                    @Override public FreqRespPreset captureCurrent() { return captureCurrentFreqRespPreset(); }
                    @Override public void apply(FreqRespPreset p) { applyFreqRespPreset(p); }
                    // Refresh the Presets tab tile so its "N saved" count updates.
                    @Override public void onChanged() { toolbarTabs.refreshTab(TAB_FREQRESP_PRESETS); }
                });
    }

    private FreqRespPreset captureCurrentFreqRespPreset() {
        Preferences prefs = Preferences.instance();
        FreqRespPreset p = new FreqRespPreset();
        p.setStartHz(prefs.getFreqRespStartHz());
        p.setStopHz(prefs.getFreqRespStopHz());
        // The preset carries the pair as entered, so recalling it under another
        // calibration replays what was typed, not a voltage frozen out of it.
        p.setAmplitude(prefs.getFreqRespAmplitude().value());
        p.setAmplitudeUnit(prefs.getFreqRespAmplitude().unit());
        p.setSweepPoints(prefs.getFreqRespSweepPoints());
        p.setFftSize(prefs.getFreqRespFftSize());
        p.setLeadInSec(prefs.getFreqRespLeadInSec());
        p.setDitherBits(prefs.getFreqRespDitherBits());
        p.setShowRiaa(prefs.isFreqRespShowRiaa());
        p.setReverseRiaa(prefs.isFreqRespReverseRiaa());
        p.setIecAmendment(prefs.isFreqRespIecAmendment());
        p.setCompareMode(prefs.isFreqRespCompareMode());
        // Filters - the per-type scalars now live in the params map (single
        // source of truth); the preset embeds ONE copy of the entry for its
        // captured filter type.
        p.setShowFilter(prefs.isFreqRespShowFilter());
        p.setFilterCompare(prefs.isFreqRespFilterCompare());
        p.setFilterType(prefs.getFreqRespFilterType());
        p.setFilterResponse(prefs.getFreqRespFilterResponse());
        p.setFilterParams(copyFilterParams(prefs.getFreqRespFilterParams(prefs.getFreqRespFilterType())));
        // Unevenness
        p.setUnevenMode(prefs.getFreqRespUnevenMode());
        p.setUnevenNotch(prefs.isFreqRespUnevenNotch());
        p.setUnevenDb(prefs.getFreqRespUnevenDb());
        p.setUnevenStartHz(prefs.getFreqRespUnevenStartHz());
        p.setUnevenStopHz(prefs.getFreqRespUnevenStopHz());
        return p;
    }

    private void applyFreqRespPreset(FreqRespPreset p) {
        Preferences prefs = Preferences.instance();
        prefs.setFreqRespStartHz(p.getStartHz());
        prefs.setFreqRespStopHz(p.getStopHz());
        // A preset carries the band it was SAVED with, which may sit above the
        // ceiling the currently configured converters allow - a stored 96 kHz
        // stop on a card since reconfigured to a 48 kHz DAC.  The edge fields
        // would show the ceiling while the preference kept the saved number, and
        // the measurement reads the preference.
        clampSweepBandToCeiling();
        prefs.setFreqRespAmplitude(new UnitValue(p.getAmplitude(), p.getAmplitudeUnit()));
        prefs.setFreqRespSweepPoints(p.getSweepPoints());
        prefs.setFreqRespFftSize(p.getFftSize());
        prefs.setFreqRespLeadInSec(p.getLeadInSec());
        prefs.setFreqRespDitherBits(p.getDitherBits());
        prefs.setFreqRespShowRiaa(p.isShowRiaa());
        prefs.setFreqRespReverseRiaa(p.isReverseRiaa());
        prefs.setFreqRespIecAmendment(p.isIecAmendment());
        prefs.setFreqRespCompareMode(p.isCompareMode());
        // Filters - write the embedded params copy back into the map entry for
        // the preset's filter type; the type combo + filter widgets
        // then reload from the map when the type pref settles below.
        prefs.setFreqRespShowFilter(p.isShowFilter());
        prefs.setFreqRespFilterCompare(p.isFilterCompare());
        prefs.setFreqRespFilterType(p.getFilterType());
        prefs.setFreqRespFilterResponse(p.getFilterResponse());
        prefs.putFreqRespFilterParams(p.getFilterType(), copyFilterParams(p.getFilterParams()));
        // Unevenness
        prefs.setFreqRespUnevenMode(p.getUnevenMode());
        prefs.setFreqRespUnevenNotch(p.isUnevenNotch());
        prefs.setFreqRespUnevenDb(p.getUnevenDb());
        prefs.setFreqRespUnevenStartHz(p.getUnevenStartHz());
        prefs.setFreqRespUnevenStopHz(p.getUnevenStopHz());
        // The fftSize / leadIn writes above already re-derived the sweep
        // duration via the controller's subscriptions.
        prefs.save();
        // Every Settings / RIAA widget is two-way bound to these prefs, so the
        // setters above already pushed the preset into the live widgets.  Only
        // the derived label + tab tiles + the view's range-driven redraw still
        // need an explicit nudge.
        refreshFftSizeLabel();
        // The filter widgets bind to the params map, not to per-scalar prefs, so
        // reload them from the just-written map entry (the type combo's own
        // reload only fires when the type actually changed - this also covers a
        // same-type preset apply).
        loadFilterParams(prefs.getFreqRespFilterType());
        refreshFilterEnable();
        refreshUnevenEnable();
        toolbarTabs.refreshTab(TAB_FREQRESP_SETTINGS);
        toolbarTabs.refreshTab(TAB_FREQRESP_RIAA);
        toolbarTabs.refreshTab(TAB_FREQRESP_FILTERS);
        toolbarTabs.refreshTab(TAB_FREQRESP_UNEVENNESS);
        view.redraw();
    }

    // -------------------------------------------------------------------------
    // Utility tab - Screenshot + DAC + ADC calibration buttons (stubbed)
    // -------------------------------------------------------------------------

    private void buildUtilityTab() {
        Composite g = groupCell(toolbarTabs, "freqResp.tab.utility");
        GridLayout gl = new GridLayout(3, false);
        gl.marginWidth = 8; gl.marginHeight = 6;
        gl.horizontalSpacing = 6;
        g.setLayout(gl);

        // Icon-only buttons; the action label lives in the tooltip so the
        // toolbar stays compact, matching the scope's utility row.  All
        // three buttons are pinned at 30 px tall per UI spec so the row
        // reads as a uniform tool tray instead of three differently-sized
        // chips.  Camera / crosshair come from AbstractTabControl so every
        // pane's utility icons share one size.
        Button shotBtn = new Button(g, SWT.PUSH);
        shotBtn.setImage(cameraIcon);
        GridData shotGd = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        shotGd.widthHint = 43;
        shotGd.heightHint = 43;
        shotBtn.setLayoutData(shotGd);
        Bindings.text(shotBtn, "freqResp.utility.screenshot.tooltip", shotBtn::setToolTipText);
        shotBtn.addListener(SWT.Selection, e -> getOwner().openScreenshotDialog());

        // ADC / DAC calibration both use the crosshair icon - the
        // tooltips disambiguate which one.  Matches the scope / FFT
        // "crosshair = calibrate" convention.
        Button dacCalBtn = new Button(g, SWT.PUSH);
        dacCalBtn.setImage(crosshairIcon);
        GridData dacCalGd = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        dacCalGd.widthHint = 43;
        dacCalGd.heightHint = 43;
        dacCalBtn.setLayoutData(dacCalGd);
        Bindings.text(dacCalBtn, "freqResp.utility.calibrateDac.tooltip", dacCalBtn::setToolTipText);
        dacCalBtn.addListener(SWT.Selection, e ->
                log.info("FreqResp DAC-cal clicked (dialog wired in Phase 6 follow-up)"));

        Button adcCalBtn = new Button(g, SWT.PUSH);
        adcCalBtn.setImage(crosshairIcon);
        GridData adcCalGd = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        adcCalGd.widthHint = 43;
        adcCalGd.heightHint = 43;
        adcCalBtn.setLayoutData(adcCalGd);
        Bindings.text(adcCalBtn, "freqResp.utility.calibrateAdc.tooltip", adcCalBtn::setToolTipText);
        adcCalBtn.addListener(SWT.Selection, e ->
                log.info("FreqResp ADC-cal clicked (dialog wired in Phase 6 follow-up)"));
    }

    // -------------------------------------------------------------------------
    // Calibration tab - multi-row load + clear + add + remove
    // -------------------------------------------------------------------------

    /** One fixed cell of the spec grid: a {@link StackLayout} wrapper that
     *  overlays the Mode-1 and Mode-2 control for a single label- or field-slot.
     *  The wrapper owns the grid cell permanently, so switching modes only flips
     *  which child is on top - the column never reflows.  Either
     *  child may be {@code null} (a slot that a mode leaves empty, e.g. the
     *  Stopband pair that exists only in Mode 1). */
    private static final class SpecCell {
        final Composite   wrapper;
        final StackLayout stack;
        final Control     mode1;
        final Control     mode2;

        private SpecCell(Composite wrapper, StackLayout stack, Control mode1, Control mode2) {
            this.wrapper = wrapper;
            this.stack   = stack;
            this.mode1   = mode1;
            this.mode2   = mode2;
        }

        /** Shows this cell's Mode-1 child when {@code mode1}, else its Mode-2
         *  child; the other is hidden.  A {@code null} child leaves the wrapper
         *  empty (blank spacer) for that mode. */
        void show(boolean mode1) {
            Control top   = mode1 ? this.mode1 : this.mode2;
            Control other = mode1 ? this.mode2 : this.mode1;
            if (other != null && !other.isDisposed()) other.setVisible(false);
            if (top != null && !top.isDisposed()) {
                top.setVisible(true);
                stack.topControl = top;
            } else {
                stack.topControl = null;
            }
            if (!wrapper.isDisposed()) wrapper.layout();
        }
    }

    /** The store was rebuilt from the rows (the calibration tab lives in the
     *  base): the view re-derives its displayed curves, and the calibration
     *  tile counts the store's entries anew. */
    @Override
    protected void onCalibrationsSynced() {
        view.onCalibrationChanged();
        toolbarTabs.refreshTab(TAB_FREQRESP_CALIBRATION);
    }

    // -------------------------------------------------------------------------
    // Save-to tab - write the current measurement to a CSV
    // -------------------------------------------------------------------------

    private void buildSaveToTab() {
        Composite g = groupCell(toolbarTabs, "freqResp.tab.saveTo");
        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 6; gl.marginHeight = 4; gl.horizontalSpacing = 6;
        g.setLayout(gl);
        Preferences prefs = Preferences.instance();

        // Layout: [pathField (read-only display of last save)] [save].
        // The save button now ALWAYS opens the file-picker before
        // writing - the previous separate "browse" button was redundant
        // since browse-then-save was the only useful sequence.
        saveToPathField = new Text(g, SWT.BORDER | SWT.READ_ONLY);
        showFilePath(saveToPathField, prefs.getFreqRespSavePath(), "freqResp.saveTo.path.tooltip");
        saveToPathField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        // Every save - this tab's button and the wizard's - runs the one common
        // save, which writes the saved-path preference on success; the field
        // follows that preference, so both callers leave the same state.
        Bindings.onChange(toolbarTabs, prefs.freqRespSavePathProperty(), v -> {
            showFilePath(saveToPathField, v, "freqResp.saveTo.path.tooltip");
            toolbarTabs.refreshTab(TAB_FREQRESP_SAVE);
        });
        // The hint tooltip an empty path falls back to follows the language.
        Bindings.onChange(saveToPathField, I18n.localeProperty(), l ->
                showFilePath(saveToPathField, prefs.getFreqRespSavePath(), "freqResp.saveTo.path.tooltip"));

        Image floppyIcon = IconUtils.icon(g.getDisplay(), Icon.FLOPPY_DISK);
        Button saveBtn = new Button(g, SWT.PUSH);
        if (floppyIcon != null) saveBtn.setImage(floppyIcon);
        GridData saveGd = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        saveGd.heightHint = IconUtils.FILE_BUTTON_HEIGHT;
        saveBtn.setLayoutData(saveGd);
        Bindings.text(saveBtn, "freqResp.saveTo.tooltip", saveBtn::setToolTipText);
        /* Enabled whenever no save runs - otherwise never gated.  Saving a
         * measured response to a file needs only a measurement, which
         * the pane's save checks - it ends in the controller's save, which
         * shows that info itself (freqResp.saveTo.error.noResult).
         * A curve's fitness to serve as an ADC calibration is a different
         * question and is already gated where that use happens: writing a .frc
         * is unconditional, so ANY measured response can be saved.  A saved
         * file an item of the calibration list references reaches the rows
         * through that list's saved-file notification. */
        saveBtn.addListener(SWT.Selection, e -> {
            // Off while the save runs, so a second press cannot start a
            // second save.
            saveBtn.setEnabled(false);
            try {
                getOwner().saveMeasurement();
            } finally {
                if (!saveBtn.isDisposed()) saveBtn.setEnabled(true);
            }
        });
    }

    /** Shows {@code path} in the Save-to or Load-from path field - the one
     *  display rule for the initial fill, the saved-path binding, a file pick
     *  and a language switch: {@code null} shows as empty text, and an empty path
     *  falls back to the hint tooltip {@code hintKey}. */
    private void showFilePath(Text field, String path, String hintKey) {
        boolean hasPath = path != null && !path.isEmpty();
        field.setText(path == null ? "" : path);
        field.setToolTipText(hasPath ? path : I18n.t(hintKey));
    }

    // -------------------------------------------------------------------------
    // Load-from tab - display a saved measurement on the view
    // -------------------------------------------------------------------------

    private void buildLoadFromTab() {
        Composite g = groupCell(toolbarTabs, "freqResp.tab.loadFrom");
        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 6; gl.marginHeight = 4; gl.horizontalSpacing = 6;
        g.setLayout(gl);
        Preferences prefs = Preferences.instance();

        // Layout: [pathField (read-only display of last load)] [load].
        // The load button now ALWAYS opens the file-picker before
        // reading - the previous separate "browse" button was
        // redundant since browse-then-load was the only useful
        // sequence.
        loadFromPathField = new Text(g, SWT.BORDER | SWT.READ_ONLY);
        showFilePath(loadFromPathField, prefs.getFreqRespLoadPath(), "freqResp.loadFrom.path.tooltip");
        loadFromPathField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        // The field shows the load-path preference (the load dialog writes
        // both); the hint tooltip an empty path falls back to follows the
        // language.
        Bindings.onChange(loadFromPathField, I18n.localeProperty(), l ->
                showFilePath(loadFromPathField, prefs.getFreqRespLoadPath(), "freqResp.loadFrom.path.tooltip"));

        Image folderIcon = IconUtils.icon(g.getDisplay(), Icon.FOLDER_OPEN);
        Button loadBtn = new Button(g, SWT.PUSH);
        if (folderIcon != null) loadBtn.setImage(folderIcon);
        GridData loadGd = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        loadGd.heightHint = IconUtils.FILE_BUTTON_HEIGHT;
        loadBtn.setLayoutData(loadGd);
        Bindings.text(loadBtn, "freqResp.loadFrom.tooltip", loadBtn::setToolTipText);
        loadBtn.addListener(SWT.Selection, e -> openLoadDialog());
    }

    /** Opens an Open file dialog, then loads the chosen file into the
     *  view.  Always prompts - the dedicated "browse" button was
     *  removed because browse-then-load was the only useful sequence.
     *  The picker is pre-filled with the last loaded path so re-load
     *  is one extra click. */
    private void openLoadDialog() {
        Preferences prefs = Preferences.instance();
        FileDialog fd = new FileDialog(getShell(), SWT.OPEN);
        fd.setText(I18n.t("freqResp.loadFrom.dialog"));
        fd.setFilterExtensions(new String[]{ "*.frc", "*.csv", "*.*" });
        if (prefs.getFreqRespLoadFolder() != null) fd.setFilterPath(prefs.getFreqRespLoadFolder());
        String lastPath = (loadFromPathField != null && !loadFromPathField.isDisposed())
                ? loadFromPathField.getText().trim() : "";
        if (!lastPath.isEmpty()) fd.setFileName(new File(lastPath).getName());
        String picked = fd.open();
        if (picked == null) return;
        if (loadFromPathField != null && !loadFromPathField.isDisposed()) {
            showFilePath(loadFromPathField, picked, "freqResp.loadFrom.path.tooltip");
        }
        prefs.setFreqRespLoadPath(picked);
        toolbarTabs.refreshTab(TAB_FREQRESP_LOAD);
        try {
            StereoFreqRespCalibration st = FreqRespCalHelper.loadFrc(picked);
            FreqRespCalibration cL = st.left();
            FreqRespCalibration cR = st.right();
            FreqRespSweepParams params = new FreqRespSweepParams(
                    cL.freqs[0], cL.freqs[cL.freqs.length - 1],
                    cL.freqs.length, 0.0, 0.0, 0.0, 0);
            // Nyquist for a loaded file comes from the file's own
            // sample_rate_hz header comment; only legacy headerless files
            // fall back to the live input rate (the view's data-derived cap
            // still guards those).
            int fileSr = readSampleRateHz(picked);
            int sr = fileSr > 0 ? fileSr : prefs.current().getInputSampleRate();
            view.setLeftResult(new FreqRespResult(
                    Channel.L, sr, cL.freqs, cL.magLin, cL.phaseRad,
                    params, picked, true));
            view.setRightResult(new FreqRespResult(
                    Channel.R, sr, cR.freqs, cR.magLin, cR.phaseRad,
                    params, picked, true));
            view.refreshFromCurves();
            view.setSourceFilePath(picked);
            // A loaded measurement counts as "has result" - refresh the RIAA
            // enable cascade so Compare becomes available (when Show is on).
            refreshRiaaEnable();
            // Auto-fit the freq / magnitude window to the freshly-loaded curve,
            // as if the header auto-setup button were pressed.
            view.autoSetupMagnitudeRange();
            prefs.setFreqRespLoadFolder(new File(picked).getParent());
            prefs.save();
        } catch (Exception ex) {
            log.warn("FreqResp load failed", ex);
            Dialogs.error(getShell(),
                    I18n.t("freqResp.loadFrom.dialog"),
                    I18n.t("freqResp.error.measurement.load").replace("{0}",
                            ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
        }
    }

    /**
     * Reads the capture sample rate recorded in a {@code .frc} file's leading
     * {@code # sample_rate_hz=} header comment.  Only the header block is
     * scanned - the scan stops at the first non-comment line.  Returns
     * {@code 0} when the header is absent or unparseable (a legacy file), so
     * the caller falls back to the live input rate.
     */
    private int readSampleRateHz(String path) throws IOException {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (!line.startsWith("#")) break;   // header block ended
                int eq = line.indexOf('=');
                if (eq > 0 && "sample_rate_hz".equals(line.substring(1, eq).trim())) {
                    try {
                        return Integer.parseInt(line.substring(eq + 1).trim());
                    } catch (NumberFormatException e) {
                        return 0;   // garbled header - treat as absent
                    }
                }
            }
        }
        return 0;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private GridData comboGd() {
        GridData gd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        gd.widthHint = 120;
        return gd;
    }

    /** Grid data for a Settings-tab {@link Combo} so it renders the SAME width
     *  as the {@link NumericStepField}s that share its grid column.  A READ_ONLY
     *  Combo adds its drop-down button ON TOP of a {@code widthHint} (unlike the
     *  bordered step-field composite, which treats the hint as its total width),
     *  so a matching {@code widthHint} makes the combo ~one button wider.
     *  Instead FILL the column with no hint: the step-field hints pin the column
     *  to {@link #comboGd}'s width and the combo stretches to exactly that. */
    private GridData comboFillGd() {
        return new GridData(SWT.FILL, SWT.CENTER, false, false);
    }

    /** A caption {@link Label} showing the translation of {@code key}, which
     *  follows the language. */
    private Label addLabel(Composite parent, String key) {
        Label l = new Label(parent, SWT.NONE);
        Bindings.text(l, key, l::setText);
        l.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        return l;
    }
}
