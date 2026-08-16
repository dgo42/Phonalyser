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

package org.edgo.audio.measure.gui.preferences;

import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.ColorDialog;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.FontDialog;
import org.eclipse.swt.graphics.FontData;

import org.edgo.audio.measure.common.Constants;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.AudioDeviceManager;
import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.DeviceChannelMode;
import org.edgo.audio.measure.enums.PersistenceMode;
import org.edgo.audio.measure.gui.MainWindow;
import org.edgo.audio.measure.gui.bind.Bindings;
import org.edgo.audio.measure.gui.scope.gl.GpuSupport;
import org.edgo.audio.measure.bind.Property;
import org.edgo.audio.measure.preferences.AudioDeviceProfile;
import org.edgo.audio.measure.preferences.BackendKey;
import org.edgo.audio.measure.preferences.BackendPrefs;
import org.edgo.audio.measure.preferences.DeviceEndpointConfig;
import org.edgo.audio.measure.preferences.DeviceRange;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.gui.bus.ActiveRange;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.bus.SampleRateChange;
import org.edgo.audio.measure.gui.common.BackendSettingsRegistry;
import org.edgo.audio.measure.gui.common.BackendSettingsUi;
import org.edgo.audio.measure.gui.common.Dialogs;
import org.edgo.audio.measure.gui.common.GuiUtil;
import org.edgo.audio.measure.gui.common.Fonts;
import org.edgo.audio.measure.gui.common.Icon;
import org.edgo.audio.measure.gui.common.IconUtils;
import org.edgo.audio.measure.gui.common.RemoteBackendRegistry;
import org.edgo.audio.measure.gui.common.RemoteBackendUi;
import org.edgo.audio.measure.gui.common.ShellIcons;
import org.edgo.audio.measure.gui.registry.UiRegistry;
import org.edgo.audio.measure.gui.sound.BenchCards;
import org.edgo.audio.measure.gui.sound.CalibrationCopyOffers;
import org.edgo.audio.measure.gui.sound.CalibrationStore;
import org.edgo.audio.measure.gui.widgets.NumericStepField;
import org.edgo.audio.measure.enums.TabOrientation;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.widgets.UnitFamily;
import org.edgo.audio.measure.sound.DeviceRef;

import javax.sound.sampled.AudioFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Modal "Preferences" dialog: lets the user pick the audio backend plus a
 * capture and playback device - each with its own independent sample rate
 * and bit depth.  Input and output fields are visually grouped, and every
 * backend remembers its own selections so switching backends preserves the
 * previously chosen values.
 *
 * <p>The dialog edits a detached working copy obtained from
 * {@link Preferences#copyForDialog()}; no live state is mutated until OK.
 * OK hands the working copy to {@link Preferences#applyFromDialog(Preferences)},
 * which commits every edit (including the chosen backend) and persists.
 * Cancel just closes.
 *
 * <p><b>One exception, and it is restored rather than avoided.</b>  A backend on
 * a Phonalyser server has no device list until its session has been pointed at it
 * ({@code backend.select}), so showing one in the combos really does move the
 * live routing.  {@link #restoreRemoteRouting} puts it back on dispose whenever
 * OK did not commit, which is what keeps Cancel's promise.
 */
@Log4j2
public final class PreferencesDialog {

    private static final int[] DEFAULT_SAMPLE_RATES = {
            8000, 11025, 16000, 22050, 44100, 48000, 88200, 
            96000, 176400, 192000, 352800, 384000, 705600, 768000
    };
    private static final int[] DEFAULT_BIT_DEPTHS = {16, 24, 32};

    /** Factory-default ADC full-scale RMS voltage, used for new created card until the user calibrates. */
    private static final double DEFAULT_ADC_FS_VRMS = 1.0;

    /** Factory-default DAC full-scale RMS voltage, used for new created card until the user calibrates. */
    private static final double DEFAULT_DAC_FS_AMPL = 1.0 / Constants.SQRT2;

    /** Lower bound (in % of Nyquist) of the FreqResp "Maximal analysed
     *  frequency" field.  The field clamps to this on every edit, both
     *  via wheel/arrow steppers and after manual text entry. */
    private static final double FREQRESP_MAX_FREQ_PCT_MIN  = 83.0;
    /** Upper bound (in % of Nyquist) of the same field.  100 % = strict
     *  Nyquist (sampleRate / 2). */
    private static final double FREQRESP_MAX_FREQ_PCT_MAX  = 100.0;
    /** Wheel / arrow step size (in % points) for the same field. */
    private static final double FREQRESP_MAX_FREQ_PCT_STEP =   0.5;

    /** Lower bound (in points) of the FreqResp compare-mode smoothing
     *  window field.  0 = no smoothing. */
    private static final int    FREQRESP_SMOOTH_W_MIN  =   0;
    /** Upper bound (in points) of the same field. */
    private static final int    FREQRESP_SMOOTH_W_MAX  = 100;
    /** Wheel / arrow step size (in points) for the same field. */
    private static final int    FREQRESP_SMOOTH_W_STEP =   1;

    /** Scope measurement-average window (s): 0.5...100 in 0.5-s steps. */
    private static final double MEAS_AVG_MIN_SEC  = 0.5;
    private static final double MEAS_AVG_MAX_SEC  = 100;
    private static final double MEAS_AVG_STEP_SEC = 0.5;
    /** Trace line widths (px): 1...5 in 0.5-px steps, one decimal shown. */
    private static final double LINE_WIDTH_MIN_PX  = 1;
    private static final double LINE_WIDTH_MAX_PX  = 5;
    private static final double LINE_WIDTH_STEP_PX = 0.5;
    /** Dot diameters (px): 3...12 in 1-px steps. */
    private static final double DOT_DIAM_MIN_PX = 3;
    private static final double DOT_DIAM_MAX_PX = 12;
    /** Amplitude-histogram bars: 10...200 in 5-bar steps. */
    private static final double HIST_BINS_MIN  =  10;
    private static final double HIST_BINS_MAX  = 200;
    private static final double HIST_BINS_STEP =   5;
    /** Manual persistence time (s): 0.1...60 in 0.5-s steps, one decimal shown. */
    private static final double PERSIST_MANUAL_MIN_SEC  = 0.1;
    private static final double PERSIST_MANUAL_MAX_SEC  = 60;
    private static final double PERSIST_MANUAL_STEP_SEC = 0.5;
    /** Value-field column width (px): compact + right-aligned, so the wide labels of long
     *  translations have room without widening the dialog. */
    private static final int    FIELD_WIDTH_PX  = 180;
    /** Uniform dialog width (px): the dialog is forced to at least this, so it is the same
     *  size in every language (a longer translation no longer makes it wider).  Generous
     *  enough that the current languages all fit; a longer future one just stays a touch wider. */
    private static final int    DIALOG_WIDTH_PX = 640;
    /** Total dialog height (px), OUTER - title bar + border included.  The shell
     *  is forced to exactly this after pack(), so the window is the same compact
     *  height in every language.  With the FS field gone and the ranges packing
     *  to their actual row count, the typical one-card Audio tab fits inside it;
     *  a taller state scrolls inside the Audio tab's own V-scroll (see
     *  {@link #audioScroll}) instead of growing the window. */
    private static final int    SHELL_OUTER_HEIGHT_PX = 480;
    /** Multi-tone detect threshold (dB): wheel ±10 dB, arrows ±1 dB. */
    private static final double STRONG_TONE_MIN_DB   = 10;
    private static final double STRONG_TONE_MAX_DB   = 140;
    private static final double STRONG_TONE_WHEEL_DB = 10;

    private final Shell parent;

    // Dialog-session state, populated in open().  Lifted to fields so the
    // refresh / capture / apply helpers below open() can be plain private
    // methods (the SWT listeners and OK handler invoke them).  The dialog is
    // modal and short-lived, so a single live instance at a time is fine.
    private Preferences edit;
    /** The backend picker, and the model behind it: which backends are
     *  selectable (local ones, plus a connected server's) and what each item's
     *  {@link BackendKey} is - a remote item's key names the server too, which is
     *  what keeps two benches' device / rate / depth settings apart. */
    private Combo backendCombo;
    private final BackendChoices choices = new BackendChoices();
    /** The per-backend settings button, shown only for a backend that has any. */
    private Button customPrefsButton;
    private Combo inputCombo;
    private Combo inputRateCombo;
    private Combo inputDepthCombo;
    private Combo outputCombo;
    private Combo outputRateCombo;
    private Combo outputDepthCombo;
    private DeviceListState devices;
    private Property<Double> nyqWork;
    private CardSection inputCard;
    private CardSection outputCard;
    /** Where a calibration write goes - used here for the operator-confirmed copy
     *  of a local card's values onto a bench that has none.  Built on
     *  the WORKING COPY: the values offered are the ones the dialog is showing. */
    private CalibrationStore calibrationStore;
    /** One local-backend {@link AudioDeviceManager#setup()} in flight at a time -
     *  see {@link #setupShownBackendInBackground()}. */
    private final AtomicBoolean backendSetupRunning = new AtomicBoolean();
    /** The one thread every device scan runs on - see {@link #refreshDevices()}.
     *  SERIAL on purpose: an enumeration walks the driver's own device
     *  collection and a format probe opens the device, so two of them must not
     *  ask one manager at the same time.  Daemon, and shut down with the shell,
     *  so a scan still out cannot hold the application open. */
    private final ExecutorService scanner = Executors.newSingleThreadExecutor(job -> {
        Thread worker = new Thread(job, "prefs-device-scan");
        worker.setDaemon(true);
        return worker;
    });
    /** Which scan is the current one - bumped by every {@link #refreshDevices()}
     *  and captured by the scan it starts.  {@link #scanner} is serial, so an
     *  operator switching through five backends would otherwise leave the fifth
     *  one's scan queued behind four answers nobody can use any more, each of them
     *  seconds of driver I/O; a queued scan whose generation has moved on gives up
     *  before it enumerates anything.  Read from the scan thread, written from the
     *  display thread - hence atomic. */
    private final AtomicInteger scanGeneration = new AtomicInteger();
    /** The bench's card list and the device->card binding on it -
     *  what the card combo becomes for a remote selection.  Built on the working
     *  copy so the local mirror of a pick is committed by the same OK. */
    private BenchCards benchCards;
    /** Outer V-scroll wrapping the Audio tab's content, so the tallest tab
     *  scrolls instead of growing the dialog past {@link #SHELL_OUTER_HEIGHT_PX}.
     *  Its min size tracks the audio content (refreshed on every card/range
     *  rebuild); the other tabs stay unwrapped. */
    private ScrolledComposite audioScroll;
    private Composite audioTab;
    /** True once OK has committed this session.  Cancel and the window's X leave
     *  it false, which is what tells {@link #restoreRemoteRouting} to put the
     *  remote bench back where the application had it. */
    private boolean okCommitted;
    /** The working copy as the dialog OPENED on it - what the OK handler compares
     *  against to decide what its commit must bounce (the live streams), which
     *  moved active range it must publish, and whether the SELECTION changed at
     *  all (the uncalibrated warning).  Re-taken when the open-time device fill
     *  lands: that fill resolves the saved device names against what is really
     *  enumerated, and a saved device that is gone falling back to another one is
     *  the state the dialog opened IN, not an edit the operator made. */
    private OpenState openState;
    /** True until the fill of the selection the dialog opened on has landed.
     *  Every later fill - for a backend the operator picked, or a rescan - leaves
     *  {@link #openState} alone, or OK would compare its commit against itself
     *  and neither bounce the streams nor warn about the new selection. */
    private boolean openStatePending = true;

    @Setter
    private MainWindow mainWindow;

    public PreferencesDialog(Shell parent) {
        this.parent = parent;
    }

    public Shell open() { return open(null); }

    /**
     * Opens the dialog and returns its shell.  When {@code onClose} is
     * non-null it is invoked (on the SWT UI thread) after the dialog's shell
     * is disposed - used by the menu listener to restart the oscilloscope
     * capture that was paused while the dialog was up.  The returned shell is
     * a handle for callers that drive the dialog programmatically (e.g. the
     * help-screenshot automation, which closes it when done); interactive
     * callers ignore it.
     */
    public Shell open(Runnable onClose) {
        Shell dialog = new Shell(parent, SWT.RESIZE | SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        ShellIcons.apply(dialog);
        // The selection the application is actually RUNNING on.  Browsing the
        // combo re-points the remote bench - a remote backend's device list only
        // exists once backend.select has been sent, so there is nothing to fill
        // the combos from until it has - and that is the one live thing this
        // dialog cannot avoid touching.  Cancel therefore has something to restore
        // after all, and this is it.  Registered BEFORE the caller's own close
        // callback, because that callback restarts the oscilloscope's capture and
        // it must open its device on the bench the application is on, not on one
        // that was only being looked at.
        BackendKey liveBackend = Preferences.instance().getSelectedBackend();
        dialog.addDisposeListener(e -> restoreRemoteRouting(liveBackend));
        if (onClose != null) dialog.addDisposeListener(e -> onClose.run());
        // No new scan is accepted once the dialog is gone; one still running is
        // left to finish (it is bounded by the driver) and its fill is dropped by
        // the disposed-widget guard in fillDevices.
        dialog.addDisposeListener(e -> scanner.shutdown());
        dialog.setText(I18n.t("preferences.title"));
        GridLayout outer = new GridLayout(1, false);
        outer.marginWidth  = 12;
        outer.marginHeight = 12;
        outer.verticalSpacing = 8;
        dialog.setLayout(outer);

        // Detached working copy: EVERY control edits this and nothing live.
        // OK commits it via applyFromDialog(); Cancel just drops it.  The
        // backend currently shown in the combos is edit.getSelectedBackend() -
        // the single source of truth, no separate active-backend tracking.
        edit = Preferences.instance().copyForDialog();
        calibrationStore = new CalibrationStore(edit);
        benchCards = new BenchCards(edit, calibrationStore);
        // Component-owned blocks live on their owners, not in the working copy,
        // so give them the same session: seed their edit values now, commit them
        // beside applyFromDialog() on OK, leave them alone on Cancel.
        Preferences.instance().beginCustomPreferencesEdit();
        // Same session for the backends' own settings panels: what one of them
        // accepted takes effect on OK and is dropped on Cancel, whether it is
        // stored in this file or written to the bench it belongs to.
        BackendSettingsRegistry.instance().beginEdit();

        // --- Tab folder: Look & Feel + Audio + Oscilloscope + FFT -----------
        // A fixed content width makes the dialog the same size in every language: the field
        // column is compact (right-aligned), so even the widest translation fits within this,
        // and shorter ones simply leave more room between label and field.
        TabFolder tabs = new TabFolder(dialog, SWT.TOP);
        GridData tabsData = new GridData(SWT.FILL, SWT.FILL, true, true);
        tabsData.widthHint  = DIALOG_WIDTH_PX;
        tabs.setLayoutData(tabsData);

        // --- Look & Feel tab ------------------------------------------------
        // (Built first, but the AUDIO tab below inserts itself at index 0 -
        // Audio is the leading tab.)
        TabItem lookFeelTabItem = new TabItem(tabs, SWT.NONE);
        lookFeelTabItem.setText(I18n.t("preferences.tab.lookAndFeel"));
        Composite lookFeelTab = new Composite(tabs, SWT.NONE);
        GridLayout lfLayout = new GridLayout(2, false);
        lfLayout.marginWidth  = 8;
        lfLayout.marginHeight = 8;
        lfLayout.verticalSpacing = 8;
        lookFeelTab.setLayout(lfLayout);
        lookFeelTabItem.setControl(lookFeelTab);

        gridLabel(lookFeelTab,I18n.t("preferences.lookAndFeel.tabOrientation"));
        Combo orientationCombo = new Combo(lookFeelTab, SWT.READ_ONLY);
        orientationCombo.add(I18n.t("preferences.lookAndFeel.tabOrientation.top"));
        orientationCombo.add(I18n.t("preferences.lookAndFeel.tabOrientation.left"));
        // Combo item order {TOP, LEFT} matches the TabOrientation ordinals, so a
        // plain ordinal bind is correct.  The shell recreate that applies the new
        // layout is driven by MainWindow's dialog-close callback (it compares the
        // pref before/after), not from here; Cancel rolls the pref back below.
        Bindings.combo(orientationCombo, edit.tabOrientationProperty(), TabOrientation.values());
        orientationCombo.setToolTipText(I18n.t("preferences.lookAndFeel.tabOrientation.tooltip"));
        orientationCombo.setLayoutData(comboData());

        Button smallIconsBtn = buildCheckRow(lookFeelTab,
                "preferences.lookAndFeel.smallIcons", "preferences.lookAndFeel.smallIcons.tooltip");
        Bindings.check(smallIconsBtn, edit.smallIconsInMainTabProperty());

        Button showTipsBtn = buildCheckRow(lookFeelTab,
                "preferences.lookAndFeel.showTipsAtStartup", "preferences.lookAndFeel.showTipsAtStartup.tooltip");
        Bindings.check(showTipsBtn, edit.showTipsAtStartupProperty());

        Button useGpuBtn = buildCheckRow(lookFeelTab,
                "preferences.lookAndFeel.useGpuAcceleration", "preferences.lookAndFeel.useGpuAcceleration.tooltip");
        Bindings.check(useGpuBtn, edit.useGpuAccelerationProperty());
        useGpuBtn.setEnabled(GpuSupport.instance().isAvailable());   // greyed out when no GPU is available

        // UI fonts (normal / bold) - applied via a shell recreate on OK,
        // driven by MainWindow's dialog-close compare (like the language
        // switch).  The channel-button font is centralised in Fonts but
        // deliberately not user-editable here.
        gridLabel(lookFeelTab,I18n.t("preferences.lookAndFeel.fontNormal"));
        buildFontRow(lookFeelTab, edit.uiFontNormalProperty(),
                I18n.t("preferences.lookAndFeel.fontNormal.tooltip"));
        gridLabel(lookFeelTab,I18n.t("preferences.lookAndFeel.fontBold"));
        buildFontRow(lookFeelTab, edit.uiFontBoldProperty(),
                I18n.t("preferences.lookAndFeel.fontBold.tooltip"));

        TabItem audioTabItem = new TabItem(tabs, SWT.NONE, 0);   // Audio FIRST
        audioTabItem.setText(I18n.t("preferences.tab.audio"));
        // The Audio tab is the tallest (two device groups, each with a card
        // combo + range table).  Wrap ONLY it in a V-scroll so the folder's
        // height cap makes IT scroll while the other tabs still fit unwrapped.
        audioScroll = new ScrolledComposite(tabs, SWT.V_SCROLL);
        audioScroll.setExpandHorizontal(true);
        audioScroll.setExpandVertical(true);
        // On-demand vertical scrollbar: shown ONLY when the content is taller
        // than the viewport.  With the FS field gone and the ranges packing to
        // their actual row count, the typical one-card state fits 480 px with
        // no scrollbar; a taller state (many ranges) scrolls here.
        audioScroll.setAlwaysShowScrollBars(false);
        audioTabItem.setControl(audioScroll);
        audioTab = new Composite(audioScroll, SWT.NONE);
        audioScroll.setContent(audioTab);
        GridLayout audioLayout = new GridLayout(1, false);
        audioLayout.marginWidth  = 8;
        audioLayout.marginHeight = 8;
        audioLayout.verticalSpacing = 8;
        audioTab.setLayout(audioLayout);

        // --- Backend row ---------------------------------------------------
        Composite backendRow = new Composite(audioTab, SWT.NONE);
        backendRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        // Five columns: scan | servers | label | per-backend settings | combo.
        // Creation order IS render order in this row - nothing re-parents it or
        // sets a tab list.  The two bench-independent actions sit on the LEFT
        // (scan first, then the server list); the per-backend settings button
        // sits between the label and the combo, and the combo closes the row on
        // the RIGHT.
        // The settings cell stays empty (and takes no width) for a backend with
        // no settings of its own, which is every backend except the QA40x today;
        // the servers cell does the same in a build that ships no net UI.
        GridLayout backendLayout = new GridLayout(5, false);
        backendLayout.marginWidth = 0;
        backendLayout.marginHeight = 0;
        backendRow.setLayout(backendLayout);
        // Re-enumerate on demand.  Hardware is asked afresh every time the
        // combos are filled, so for a local backend this is simply that; a
        // remote one is asked for its catalogue again - a read-only preview.
        Button scanButton = new Button(backendRow, SWT.PUSH);
        scanButton.setText(I18n.t("preferences.devices.scan"));
        scanButton.setToolTipText(I18n.t("preferences.devices.scan.tooltip"));
        scanButton.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
        scanButton.addListener(SWT.Selection, e -> rescanDevices());
        // Servers - permanent: a bench is chosen BEFORE any of its backends can
        // be, so the button cannot belong to the selection the way the
        // per-backend settings button does.
        Button serversButton = new Button(backendRow, SWT.PUSH);
        serversButton.setText(I18n.t("preferences.backend.servers"));
        serversButton.setToolTipText(I18n.t("preferences.backend.servers.tooltip"));
        serversButton.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
        serversButton.addListener(SWT.Selection, e -> {
            RemoteBackendUi remote = RemoteBackendRegistry.instance().getUi();
            if (remote == null) return;
            remote.openServerList(dialog);
            // Connecting ADDS that server's backends to the combo and
            // disconnecting takes them away again, so the list is re-composed
            // from whatever is reachable now - never from what it showed before.
            refreshBackendCombo();
        });
        hideUnless(serversButton, RemoteBackendRegistry.instance().getUi() != null);
        gridLabel(backendRow,I18n.t("preferences.backend"));

        customPrefsButton = new Button(backendRow, SWT.PUSH);
        customPrefsButton.setToolTipText(I18n.t("preferences.backend.customPrefs.tooltip"));
        customPrefsButton.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
        customPrefsButton.addListener(SWT.Selection, e -> {
            // Keyed on what the bench IS, and handed WHICH bench it is: a QA403 on
            // a server has the same settings as one wired to this machine, and the
            // panel reads them from wherever the selection says.
            BackendKey selection = edit.getSelectedBackend();
            BackendSettingsUi settings =
                    BackendSettingsRegistry.instance().forBackend(selection.type());
            if (settings != null) {
                settings.open(dialog, selection);
            }
            // A backend's own settings can change what it OFFERS on the output:
            // the QA40x's front-panel I2S port swaps the depth list to its
            // 16 / 32-bit frame widths while it is on, and back to the analyzer's
            // 24 when it is off.  Re-read that list and fold the resulting
            // selection into the working copy, so a depth the backend no longer
            // offers cannot survive in the prefs.
            refreshOutputRatesAndDepths();
            captureUiToActive();
        });
        backendCombo = new Combo(backendRow, SWT.READ_ONLY);
        backendCombo.setLayoutData(comboData());
        populateBackendCombo();
        refreshCustomPrefsButton(customPrefsButton);
        // A bench can also go away without anybody asking - the server is switched
        // off, the network drops.  Its entries are then no longer selectable, and a
        // combo left on one would go on EDITING a backend no device can be opened
        // on, so the list is re-composed exactly as it is when the server dialog
        // closes.  Published on the connection's own thread, hence the marshal;
        // dropped on dispose so the bus retains no widgets.
        MessageBus remoteBus = MessageBus.instance();
        Consumer<Void> remoteBackendsListener = ignored ->
                GuiUtil.marshal(dialog, this::refreshBackendCombo);
        remoteBus.subscribe(Events.REMOTE_BACKENDS_CHANGED, remoteBackendsListener);
        dialog.addDisposeListener(e ->
                remoteBus.unsubscribe(Events.REMOTE_BACKENDS_CHANGED, remoteBackendsListener));

        // --- Input group ---------------------------------------------------
        Group inputGroup = new Group(audioTab, SWT.NONE);
        inputGroup.setText(I18n.t("preferences.input"));
        inputGroup.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        inputGroup.setLayout(new GridLayout(2, false));
        gridLabel(inputGroup,I18n.t("preferences.device"));
        inputCombo = new Combo(inputGroup, SWT.READ_ONLY);
        inputCombo.setLayoutData(comboData());
        gridLabel(inputGroup,I18n.t("preferences.sampleRate"));
        inputRateCombo = new Combo(inputGroup, SWT.READ_ONLY);
        inputRateCombo.setLayoutData(comboData());
        gridLabel(inputGroup,I18n.t("preferences.bitDepth"));
        inputDepthCombo = new Combo(inputGroup, SWT.READ_ONLY);
        inputDepthCombo.setLayoutData(comboData());
        inputCard = buildCardSection(inputGroup, true);

        // --- Output group --------------------------------------------------
        Group outputGroup = new Group(audioTab, SWT.NONE);
        outputGroup.setText(I18n.t("preferences.output"));
        outputGroup.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        outputGroup.setLayout(new GridLayout(2, false));
        gridLabel(outputGroup,I18n.t("preferences.device"));
        outputCombo = new Combo(outputGroup, SWT.READ_ONLY);
        outputCombo.setLayoutData(comboData());
        gridLabel(outputGroup,I18n.t("preferences.sampleRate"));
        outputRateCombo = new Combo(outputGroup, SWT.READ_ONLY);
        outputRateCombo.setLayoutData(comboData());
        gridLabel(outputGroup,I18n.t("preferences.bitDepth"));
        outputDepthCombo = new Combo(outputGroup, SWT.READ_ONLY);
        outputDepthCombo.setLayoutData(comboData());
        outputCard = buildCardSection(outputGroup, false);

        // --- Oscilloscope tab ----------------------------------------------
        TabItem oscTabItem = new TabItem(tabs, SWT.NONE);
        oscTabItem.setText(I18n.t("preferences.tab.oscilloscope"));
        Composite oscTab = new Composite(tabs, SWT.NONE);
        GridLayout oscLayout = new GridLayout(2, false);
        oscLayout.marginWidth  = 8;
        oscLayout.marginHeight = 8;
        oscLayout.verticalSpacing = 8;
        oscTab.setLayout(oscLayout);
        oscTabItem.setControl(oscTab);

        // Measurement averaging window - 0.5 s steps, bound to the working copy
        // (OK commits via applyFromDialog; Cancel drops the copy).
        gridLabel(oscTab,I18n.t("preferences.measAvg"));
        NumericStepField avgSecondsSel = new NumericStepField(oscTab, UnitFamily.SECONDS,
                MEAS_AVG_MIN_SEC, MEAS_AVG_MAX_SEC, MEAS_AVG_STEP_SEC, MEAS_AVG_STEP_SEC, 1, 90);
        avgSecondsSel.setLayoutData(comboData());
        avgSecondsSel.setToolTipText(I18n.t("preferences.measAvg.tooltip"));
        Bindings.stepField(avgSecondsSel, edit.oscMeasurementAverageSecondsProperty());

        // Trace line width - 0.5 px increments from 1.0 to 5.0.
        gridLabel(oscTab,I18n.t("preferences.lineWidth"));
        NumericStepField lineWidthSel = new NumericStepField(oscTab, UnitFamily.PIXEL,
                LINE_WIDTH_MIN_PX, LINE_WIDTH_MAX_PX, LINE_WIDTH_STEP_PX, LINE_WIDTH_STEP_PX, 1, 90);
        lineWidthSel.setLayoutData(comboData());
        lineWidthSel.setToolTipText(I18n.t("preferences.lineWidth.tooltip"));
        Bindings.stepField(lineWidthSel, edit.oscLineWidthProperty());

        // Sample-dot diameter - 1 px increments from 3 to 12.
        gridLabel(oscTab,I18n.t("preferences.dotDiameter"));
        NumericStepField dotDiameterSel = new NumericStepField(oscTab, UnitFamily.PIXEL,
                DOT_DIAM_MIN_PX, DOT_DIAM_MAX_PX, 1, 1, 0, 90);
        dotDiameterSel.setLayoutData(comboData());
        dotDiameterSel.setToolTipText(I18n.t("preferences.dotDiameter.tooltip"));
        Bindings.stepFieldInt(dotDiameterSel, edit.oscDotDiameterProperty());

        // Amplitude-histogram bars - display resolution only: the accumulator
        // bins far finer and is aggregated down to this many bars over the
        // occupied span, so a change re-draws the data already collected
        // instead of discarding it.
        gridLabel(oscTab,I18n.t("preferences.scope.histogramBins"));
        NumericStepField histogramBinsSel = new NumericStepField(oscTab, UnitFamily.NONE,
                HIST_BINS_MIN, HIST_BINS_MAX, HIST_BINS_STEP, HIST_BINS_STEP, 0, 90);
        histogramBinsSel.setLayoutData(comboData());
        Bindings.stepFieldInt(histogramBinsSel, edit.oscHistogramBinsProperty());

        // Display persistence ("digital phosphor", GPU path only): a preset decay time
        // plus a manual-seconds field that's enabled only when the mode is "Manual".
        gridLabel(oscTab,I18n.t("preferences.persistence"));
        Combo persistenceCombo = new Combo(oscTab, SWT.READ_ONLY);
        for (String label : PersistenceMode.LABELS) {
            persistenceCombo.add(label);
        }
        Bindings.combo(persistenceCombo, edit.oscPersistenceModeProperty(), PersistenceMode.values());
        persistenceCombo.setToolTipText(I18n.t("preferences.persistence.tooltip"));
        persistenceCombo.setLayoutData(comboData());

        gridLabel(oscTab,I18n.t("preferences.persistence.manual"));
        NumericStepField persistenceManualSel = new NumericStepField(oscTab, UnitFamily.SECONDS,
                PERSIST_MANUAL_MIN_SEC, PERSIST_MANUAL_MAX_SEC, PERSIST_MANUAL_STEP_SEC, PERSIST_MANUAL_STEP_SEC, 1, 90);
        persistenceManualSel.setLayoutData(comboData());
        persistenceManualSel.setToolTipText(I18n.t("preferences.persistence.manual.tooltip"));
        Bindings.stepField(persistenceManualSel, edit.oscPersistenceManualSecondsProperty());
        persistenceManualSel.setEnabled(edit.getOscPersistenceMode() == PersistenceMode.MANUAL);
        Bindings.onChange(persistenceManualSel, edit.oscPersistenceModeProperty(),
                m -> persistenceManualSel.setEnabled(m == PersistenceMode.MANUAL));

        // Per-channel trace colour - button background reflects the picked
        // colour, held in a local holder for the dialog session; click opens a
        // ColorDialog and updates only the holder.  The live pref is written on
        // OK (see okButton handler), so Cancel discards by simply not applying.
        gridLabel(oscTab,I18n.t("preferences.leftColor"));
        Button leftColorBtn = new Button(oscTab, SWT.PUSH);
        leftColorBtn.setLayoutData(comboData());
        int[] leftRgbHolder  = { edit.getOscLeftChannelColor()  };
        int[] rightRgbHolder = { edit.getOscRightChannelColor() };
        applyButtonColor(leftColorBtn,  leftRgbHolder[0]);
        leftColorBtn.addListener(SWT.Selection, e -> {
            ColorDialog dlg = new ColorDialog(dialog);
            dlg.setRGB(unpackRgb(leftRgbHolder[0]));
            RGB picked = dlg.open();
            if (picked != null) {
                leftRgbHolder[0] = packRgb(picked);
                applyButtonColor(leftColorBtn, leftRgbHolder[0]);
            }
        });

        gridLabel(oscTab,I18n.t("preferences.rightColor"));
        Button rightColorBtn = new Button(oscTab, SWT.PUSH);
        rightColorBtn.setLayoutData(comboData());
        applyButtonColor(rightColorBtn, rightRgbHolder[0]);
        rightColorBtn.addListener(SWT.Selection, e -> {
            ColorDialog dlg = new ColorDialog(dialog);
            dlg.setRGB(unpackRgb(rightRgbHolder[0]));
            RGB picked = dlg.open();
            if (picked != null) {
                rightRgbHolder[0] = packRgb(picked);
                applyButtonColor(rightColorBtn, rightRgbHolder[0]);
            }
        });

        // --- FFT tab -------------------------------------------------------
        TabItem fftTabItem = new TabItem(tabs, SWT.NONE);
        fftTabItem.setText(I18n.t("preferences.tab.fft"));
        Composite fftTab = new Composite(tabs, SWT.NONE);
        GridLayout fftLayout = new GridLayout(2, false);
        fftLayout.marginWidth  = 8;
        fftLayout.marginHeight = 8;
        fftLayout.verticalSpacing = 8;
        fftTab.setLayout(fftLayout);
        fftTabItem.setControl(fftTab);

        // FFT trace line width - same 1.0..5.0 / 0.5 step grid as the scope.
        gridLabel(fftTab,I18n.t("preferences.fft.lineWidth"));
        NumericStepField fftLineWidthSel = new NumericStepField(fftTab, UnitFamily.PIXEL,
                LINE_WIDTH_MIN_PX, LINE_WIDTH_MAX_PX, LINE_WIDTH_STEP_PX, LINE_WIDTH_STEP_PX, 1, 90);
        fftLineWidthSel.setLayoutData(comboData());
        fftLineWidthSel.setToolTipText(I18n.t("preferences.fft.lineWidth.tooltip"));
        Bindings.stepField(fftLineWidthSel, edit.fftLineWidthProperty());

        // Harmonic dot diameter - 1 px increments, same range as scope sample dot.
        gridLabel(fftTab,I18n.t("preferences.fft.dotDiameter"));
        NumericStepField fftDotDiameterSel = new NumericStepField(fftTab, UnitFamily.PIXEL,
                DOT_DIAM_MIN_PX, DOT_DIAM_MAX_PX, 1, 1, 0, 90);
        fftDotDiameterSel.setLayoutData(comboData());
        fftDotDiameterSel.setToolTipText(I18n.t("preferences.fft.dotDiameter.tooltip"));
        Bindings.stepFieldInt(fftDotDiameterSel, edit.fftHarmonicDotDiameterProperty());

        // Multi-tone detect threshold (dB below the strongest peak): for
        // cross-tick coherent averaging a spectral peak is treated as a separate
        // tone only if within this many dB of the strongest.  Lower it so a
        // tone's harmonics / IMD products aren't taken as independent tones
        // (which would route the average onto the per-tone multi-tone path).
        gridLabel(fftTab,I18n.t("preferences.fft.strongToneRelDb"));
        NumericStepField fftStrongToneRelDbField = new NumericStepField(fftTab, UnitFamily.DECIBEL,
                STRONG_TONE_MIN_DB, STRONG_TONE_MAX_DB, STRONG_TONE_WHEEL_DB, 1, 1, 90);
        fftStrongToneRelDbField.setLayoutData(comboData());
        fftStrongToneRelDbField.setToolTipText(I18n.t("preferences.fft.strongToneRelDb.tooltip"));
        Bindings.stepField(fftStrongToneRelDbField, edit.fftStrongToneRelDbProperty());

        // Spectrum line colour.
        int[] fftLineColorHolder = { edit.getFftLineColor() };
        gridLabel(fftTab,I18n.t("preferences.fft.lineColor"));
        Button fftLineColorBtn = new Button(fftTab, SWT.PUSH);
        fftLineColorBtn.setLayoutData(comboData());
        applyButtonColor(fftLineColorBtn, fftLineColorHolder[0]);
        fftLineColorBtn.addListener(SWT.Selection, e -> {
            ColorDialog dlg = new ColorDialog(dialog);
            dlg.setRGB(unpackRgb(fftLineColorHolder[0]));
            RGB picked = dlg.open();
            if (picked != null) {
                fftLineColorHolder[0] = packRgb(picked);
                applyButtonColor(fftLineColorBtn, fftLineColorHolder[0]);
            }
        });

        // Chart background colour.
        int[] fftBgColorHolder = { edit.getFftChartBackgroundColor() };
        gridLabel(fftTab,I18n.t("preferences.fft.bgColor"));
        Button fftBgColorBtn = new Button(fftTab, SWT.PUSH);
        fftBgColorBtn.setLayoutData(comboData());
        applyButtonColor(fftBgColorBtn, fftBgColorHolder[0]);
        fftBgColorBtn.addListener(SWT.Selection, e -> {
            ColorDialog dlg = new ColorDialog(dialog);
            dlg.setRGB(unpackRgb(fftBgColorHolder[0]));
            RGB picked = dlg.open();
            if (picked != null) {
                fftBgColorHolder[0] = packRgb(picked);
                applyButtonColor(fftBgColorBtn, fftBgColorHolder[0]);
            }
        });

        // Harmonic dot colour.
        int[] fftDotColorHolder = { edit.getFftHarmonicDotColor() };
        gridLabel(fftTab,I18n.t("preferences.fft.dotColor"));
        Button fftDotColorBtn = new Button(fftTab, SWT.PUSH);
        fftDotColorBtn.setLayoutData(comboData());
        applyButtonColor(fftDotColorBtn, fftDotColorHolder[0]);
        fftDotColorBtn.addListener(SWT.Selection, e -> {
            ColorDialog dlg = new ColorDialog(dialog);
            dlg.setRGB(unpackRgb(fftDotColorHolder[0]));
            RGB picked = dlg.open();
            if (picked != null) {
                fftDotColorHolder[0] = packRgb(picked);
                applyButtonColor(fftDotColorBtn, fftDotColorHolder[0]);
            }
        });

        // Frequency response line colour.
        int[] fftFreqRespColorHolder = { edit.getFftFreqRespColor() };
        gridLabel(fftTab,I18n.t("preferences.fft.filterColor"));
        Button fftFreqRespColorBtn = new Button(fftTab, SWT.PUSH);
        fftFreqRespColorBtn.setLayoutData(comboData());
        applyButtonColor(fftFreqRespColorBtn, fftFreqRespColorHolder[0]);
        fftFreqRespColorBtn.addListener(SWT.Selection, e -> {
            ColorDialog dlg = new ColorDialog(dialog);
            dlg.setRGB(unpackRgb(fftFreqRespColorHolder[0]));
            RGB picked = dlg.open();
            if (picked != null) {
                fftFreqRespColorHolder[0] = packRgb(picked);
                applyButtonColor(fftFreqRespColorBtn, fftFreqRespColorHolder[0]);
            }
        });

        // Before-calibration dot colour - painted next to the
        // (red) corrected fundamental / harmonic dots so the user
        // can see how much the loaded .frc shifted each peak.
        int[] fftBeforeCalDotColorHolder = { edit.getFftBeforeCalDotColor() };
        gridLabel(fftTab,I18n.t("preferences.fft.beforeCalDotColor"));
        Button fftBeforeCalDotColorBtn = new Button(fftTab, SWT.PUSH);
        fftBeforeCalDotColorBtn.setLayoutData(comboData());
        applyButtonColor(fftBeforeCalDotColorBtn, fftBeforeCalDotColorHolder[0]);
        fftBeforeCalDotColorBtn.addListener(SWT.Selection, e -> {
            ColorDialog dlg = new ColorDialog(dialog);
            dlg.setRGB(unpackRgb(fftBeforeCalDotColorHolder[0]));
            RGB picked = dlg.open();
            if (picked != null) {
                fftBeforeCalDotColorHolder[0] = packRgb(picked);
                applyButtonColor(fftBeforeCalDotColorBtn, fftBeforeCalDotColorHolder[0]);
            }
        });

        // Calibration overlay colour - the mirrored cascade of all loaded
        // .frc files, drawn parallel to the FFT spectrum so the user can
        // see at a glance which bands the calibration is lifting or cutting.
        int[] fftCalOverlayColorHolder = { edit.getFftCalOverlayColor() };
        gridLabel(fftTab,I18n.t("preferences.fft.calOverlayColor"));
        Button fftCalOverlayColorBtn = new Button(fftTab, SWT.PUSH);
        fftCalOverlayColorBtn.setLayoutData(comboData());
        applyButtonColor(fftCalOverlayColorBtn, fftCalOverlayColorHolder[0]);
        fftCalOverlayColorBtn.addListener(SWT.Selection, e -> {
            ColorDialog dlg = new ColorDialog(dialog);
            dlg.setRGB(unpackRgb(fftCalOverlayColorHolder[0]));
            RGB picked = dlg.open();
            if (picked != null) {
                fftCalOverlayColorHolder[0] = packRgb(picked);
                applyButtonColor(fftCalOverlayColorBtn, fftCalOverlayColorHolder[0]);
            }
        });

        // --- Frequency response tab ----------------------------------------
        TabItem freqRespTabItem = new TabItem(tabs, SWT.NONE);
        freqRespTabItem.setText(I18n.t("preferences.tab.freqResp"));
        Composite freqRespTab = new Composite(tabs, SWT.NONE);
        GridLayout freqRespLayout = new GridLayout(2, false);
        freqRespLayout.marginWidth  = 8;
        freqRespLayout.marginHeight = 8;
        freqRespLayout.verticalSpacing = 8;
        freqRespTab.setLayout(freqRespLayout);
        freqRespTabItem.setControl(freqRespTab);

        // FreqResp trace line width - same 1.0..5.0 / 0.5 step grid as the
        // scope and FFT traces.
        gridLabel(freqRespTab,I18n.t("preferences.freqResp.lineWidth"));
        NumericStepField freqRespLineWidthSel = new NumericStepField(freqRespTab, UnitFamily.PIXEL,
                LINE_WIDTH_MIN_PX, LINE_WIDTH_MAX_PX, LINE_WIDTH_STEP_PX, LINE_WIDTH_STEP_PX, 1, 90);
        freqRespLineWidthSel.setLayoutData(comboData());
        freqRespLineWidthSel.setToolTipText(I18n.t("preferences.freqResp.lineWidth.tooltip"));
        Bindings.stepField(freqRespLineWidthSel, edit.freqRespLineWidthProperty());

        // Maximal analyzed frequency as % of Nyquist.  Pref value is a
        // fraction in [0.83, 1.00]; the field displays it as a percent.
        // 100 % = strict Nyquist; the user usually wants 83-99 % to avoid
        // the deconvolution kernel's roll-off right at Fs/2.  The label
        // also shows the resulting frequency in Hz so the user sees the
        // concrete band, not just an abstract percent.
        Label nyqLabel = new Label(freqRespTab, SWT.NONE);
        nyqLabel.setText(formatMaxFreqLabel(
                edit.getFreqRespNyquistFraction(),
                edit.current().getInputSampleRate()));
        NumericStepField nyqField = new NumericStepField(freqRespTab, UnitFamily.PERCENT,
                FREQRESP_MAX_FREQ_PCT_MIN, FREQRESP_MAX_FREQ_PCT_MAX,
                FREQRESP_MAX_FREQ_PCT_STEP, 1, 1, 90);
        nyqField.setLayoutData(comboData());
        nyqField.setToolTipText(I18n.t("preferences.freqResp.maxFreqPctNyquist.tooltip"));
        // Working copy: the field edits a working percent; nothing touches the
        // live pref until OK.  The selection listener only refreshes the (Hz)
        // label so the user still sees the concrete band track the typed
        // percent - it reads the live input sample rate (display-only) and the
        // working percent, never a live pref.  On OK the apply below writes the
        // fraction, clamps the freq window down if the new max moved below the
        // current right edge, and the OK handler fires FREQRESP_RANGE_CHANGED.
        nyqWork = new Property<>(edit.getFreqRespNyquistFraction() * 100.0);
        Bindings.stepField(nyqField, nyqWork);
        nyqField.addSelectionListener(e -> {
            double pct  = Math.max(FREQRESP_MAX_FREQ_PCT_MIN,
                    Math.min(FREQRESP_MAX_FREQ_PCT_MAX, nyqField.getValue()));
            int sr = edit.current().getInputSampleRate();
            // Refresh the label so the (Hz) suffix tracks the typed percent.
            // layout() so the now-longer-or-shorter text doesn't get clipped.
            nyqLabel.setText(formatMaxFreqLabel(pct / 100.0, sr));
            nyqLabel.requestLayout();
        });

        // Compare-mode smoothing window (points).  Used by the FreqResp
        // view's getSmoothedDiffDb to draw the (measured − reference)
        // curve and to compute the anchor / min-max table that the
        // auto-setup snaps to.  Live-applied: every edit publishes
        // FREQRESP_COMPARE_PARAMS_CHANGED so the view re-derives the
        // smoothed array and refreshes the table without disturbing
        // the current zoom.
        gridLabel(freqRespTab,I18n.t("preferences.freqResp.compareSmoothWindow"));
        NumericStepField smoothField = new NumericStepField(freqRespTab, UnitFamily.NONE,
                FREQRESP_SMOOTH_W_MIN, FREQRESP_SMOOTH_W_MAX,
                FREQRESP_SMOOTH_W_STEP, FREQRESP_SMOOTH_W_STEP, 0, 90);
        smoothField.setLayoutData(comboData());
        smoothField.setToolTipText(I18n.t("preferences.freqResp.compareSmoothWindow.tooltip"));
        // Working copy: the field clamps to [0,100] and rounds to an int itself;
        // it edits a working value applied on OK.  The view refresh
        // (FREQRESP_COMPARE_PARAMS_CHANGED) fires once from the OK handler, not
        // live, so there is no preview.
        Bindings.stepFieldInt(smoothField, edit.freqRespCompareSmoothWindowProperty());

        // Industrial-noise notch filter.  When enabled, the FreqResp view
        // linearly interpolates across each harmonic of the chosen base
        // frequency (50 / 60 Hz mains) before drawing - removes the
        // mains-hum spikes without re-running the measurement.  Live-applied
        // via FREQRESP_CALIBRATION_CHANGED so the view re-derives the
        // displayed copy and redraws.
        Button notchEnableBtn = buildCheckRow(freqRespTab,
                "preferences.freqResp.notch.enable", "preferences.freqResp.notch.enable.tooltip");

        gridLabel(freqRespTab,I18n.t("preferences.freqResp.notch.baseHz"));
        Combo notchBaseCombo = new Combo(freqRespTab, SWT.READ_ONLY);
        notchBaseCombo.add("50 Hz");
        notchBaseCombo.add("60 Hz");
        notchBaseCombo.select(edit.getFreqRespNotchBaseHz() == 60 ? 1 : 0);
        notchBaseCombo.setToolTipText(I18n.t("preferences.freqResp.notch.baseHz.tooltip"));
        notchBaseCombo.setLayoutData(comboData());

        // Working copy: the enable flag and the base-Hz combo edit working
        // values applied on OK.  The view refresh (FREQRESP_CALIBRATION_CHANGED)
        // fires once from the OK handler, not live, so there is no preview.
        Bindings.check(notchEnableBtn, edit.freqRespNotchEnabledProperty());
        notchBaseCombo.addListener(SWT.Selection, e ->
                edit.setFreqRespNotchBaseHz(notchBaseCombo.getSelectionIndex() == 1 ? 60 : 50));

        // ── Chart colour pickers.  Pattern mirrors the FFT tab's colour
        // buttons: each holder array carries the picked RGB through the dialog
        // session as a working copy.  Each button shows its current colour as
        // the background; clicking opens the system ColorDialog and updates only
        // the holder.  The live pref is written on OK; Cancel discards by not
        // applying.  The FreqResp view reads these prefs every paint, and the
        // OK handler fires FREQRESP_CALIBRATION_CHANGED so the next redraw uses
        // the committed colours.
        int[] freqRespSignalColorHolder     = { edit.getFreqRespSignalColor() };
        int[] freqRespPhaseColorHolder      = { edit.getFreqRespPhaseColor() };
        int[] freqRespReferenceColorHolder  = { edit.getFreqRespReferenceColor() };
        int[] freqRespBackgroundColorHolder = { edit.getFreqRespBackgroundColor() };

        gridLabel(freqRespTab,I18n.t("preferences.freqResp.signalColor"));
        Button signalColorBtn = new Button(freqRespTab, SWT.PUSH);
        signalColorBtn.setLayoutData(comboData());
        applyButtonColor(signalColorBtn, freqRespSignalColorHolder[0]);
        signalColorBtn.addListener(SWT.Selection, e -> {
            ColorDialog dlg = new ColorDialog(dialog);
            dlg.setRGB(unpackRgb(freqRespSignalColorHolder[0]));
            RGB picked = dlg.open();
            if (picked != null) {
                freqRespSignalColorHolder[0] = packRgb(picked);
                applyButtonColor(signalColorBtn, freqRespSignalColorHolder[0]);
            }
        });

        gridLabel(freqRespTab,I18n.t("preferences.freqResp.phaseColor"));
        Button phaseColorBtn = new Button(freqRespTab, SWT.PUSH);
        phaseColorBtn.setLayoutData(comboData());
        applyButtonColor(phaseColorBtn, freqRespPhaseColorHolder[0]);
        phaseColorBtn.addListener(SWT.Selection, e -> {
            ColorDialog dlg = new ColorDialog(dialog);
            dlg.setRGB(unpackRgb(freqRespPhaseColorHolder[0]));
            RGB picked = dlg.open();
            if (picked != null) {
                freqRespPhaseColorHolder[0] = packRgb(picked);
                applyButtonColor(phaseColorBtn, freqRespPhaseColorHolder[0]);
            }
        });

        gridLabel(freqRespTab,I18n.t("preferences.freqResp.referenceColor"));
        Button refColorBtn = new Button(freqRespTab, SWT.PUSH);
        refColorBtn.setLayoutData(comboData());
        applyButtonColor(refColorBtn, freqRespReferenceColorHolder[0]);
        refColorBtn.addListener(SWT.Selection, e -> {
            ColorDialog dlg = new ColorDialog(dialog);
            dlg.setRGB(unpackRgb(freqRespReferenceColorHolder[0]));
            RGB picked = dlg.open();
            if (picked != null) {
                freqRespReferenceColorHolder[0] = packRgb(picked);
                applyButtonColor(refColorBtn, freqRespReferenceColorHolder[0]);
            }
        });

        gridLabel(freqRespTab,I18n.t("preferences.freqResp.backgroundColor"));
        Button bgColorBtn = new Button(freqRespTab, SWT.PUSH);
        bgColorBtn.setLayoutData(comboData());
        applyButtonColor(bgColorBtn, freqRespBackgroundColorHolder[0]);
        bgColorBtn.addListener(SWT.Selection, e -> {
            ColorDialog dlg = new ColorDialog(dialog);
            dlg.setRGB(unpackRgb(freqRespBackgroundColorHolder[0]));
            RGB picked = dlg.open();
            if (picked != null) {
                freqRespBackgroundColorHolder[0] = packRgb(picked);
                applyButtonColor(bgColorBtn, freqRespBackgroundColorHolder[0]);
            }
        });

        // Register the tab folder (the screenshot target) and each tab so an
        // automation script can select a tab and snapshot the dialog by path -
        // the same self-registration the measurement panes use for their
        // settings tabs.  Used by the help-screenshot capture.
        UiRegistry reg = UiRegistry.instance();
        reg.register("preferences", tabs);
        reg.register("preferences/tabs/lookfeel")    .onActivate(() -> tabs.setSelection(lookFeelTabItem));
        reg.register("preferences/tabs/audio")       .onActivate(() -> tabs.setSelection(audioTabItem));
        reg.register("preferences/tabs/oscilloscope").onActivate(() -> tabs.setSelection(oscTabItem));
        reg.register("preferences/tabs/fft")         .onActivate(() -> tabs.setSelection(fftTabItem));
        reg.register("preferences/tabs/freqresp")    .onActivate(() -> tabs.setSelection(freqRespTabItem));

        // --- Device list state + refresh logic -----------------------------
        devices = new DeviceListState();

        // A backend with ONE shared sample-rate clock (QA40x: reg 9, doc §10) needs
        // its two rate combos kept equal; a backend that is one digital format in
        // both directions (the loopback) needs the bit depth kept equal too.  The
        // coupling is a MessageBus round-trip: each rate / depth combo announces its
        // pick with PREFS_SAMPLE_RATE_CHANGED (wired
        // below and re-emitted at the end of refreshDevices), the owning subscriber
        // compares the pair and answers with PREFS_SAMPLE_RATE_SET, and here we align
        // the OTHER combo to it.  A programmatic Combo.select fires no SWT.Selection,
        // so this correction never re-announces - the round-trip ends.  Subscribed
        // BEFORE the first refreshDevices() so an entry sync lands, and dropped on
        // dispose so the bus retains no widgets.
        MessageBus rateBus = MessageBus.instance();
        Consumer<SampleRateChange> rateSetListener = set -> {
            if (set == null || dialog.isDisposed()) return;
            if (!edit.getSelectedBackend().equals(set.backend())) return;
            selectComboItem(set.input() ? inputRateCombo : outputRateCombo, set.sampleRateHz());
            // Only when the answer NAMES a depth: a constraint that couples the
            // clock alone leaves the depth combos where the operator put them.
            if (set.bitDepth() != SampleRateChange.NO_BIT_DEPTH) {
                selectComboItem(set.input() ? inputDepthCombo : outputDepthCombo, set.bitDepth());
            }
        };
        rateBus.subscribe(Events.PREFS_SAMPLE_RATE_SET, rateSetListener);
        dialog.addDisposeListener(e -> rateBus.unsubscribe(Events.PREFS_SAMPLE_RATE_SET, rateSetListener));

        // Through applyBackendSelection, not straight into refreshDevices: a
        // selection restored from the preferences may be a bench on a server,
        // whose devices are PREVIEWED read-only (devices.list) - never committed
        // here.  Opening this dialog must not touch the live session (staging
        // rule); the commit happens on OK.
        applyBackendSelection(edit.getSelectedBackend());
        // And ask the connected server for its backend list again, WITHOUT
        // waiting on the wire: the dialog opens on the cached composition, the
        // fresh one lands through the REMOTE_BACKENDS_CHANGED subscription armed
        // above - opening the dialog rescans local and remote in parallel.
        refreshRemoteEntries();
        // The Audio tab is now fully built and populated - set the V-scroll's
        // min size from its content so the scrollbar appears under the height
        // cap.  (Subsequent card/range rebuilds refresh it again.)
        refreshAudioScrollMinSize();
        backendCombo.addListener(SWT.Selection, e -> onBackendPicked());
        // A device pick must land in the working copy BEFORE the card section
        // re-resolves - CardSection.refresh() reads the device name off `edit`,
        // so without this capture it would re-resolve against the OLD device and
        // keep the previous card selected.
        inputCombo.addListener (SWT.Selection, e -> {
            if (revertLockedDevicePick(inputCombo, devices.inputs, edit.current().getInputDeviceName())) return;
            refreshInputRatesAndDepths();  captureUiToActive(); inputCard.onDeviceChanged();
        });
        outputCombo.addListener(SWT.Selection, e -> {
            if (revertLockedDevicePick(outputCombo, devices.outputs, edit.current().getOutputDeviceName())) return;
            refreshOutputRatesAndDepths(); captureUiToActive(); outputCard.onDeviceChanged();
        });
        // Announce each rate / depth pick on the bus so a format-constraint
        // subscriber can mirror it onto the other direction (see the
        // PREFS_SAMPLE_RATE_SET subscription above).  Unguarded by backend - a
        // backend with no such subscriber simply gets no answer back.  The depth
        // combos announce the SAME event: a backend whose two directions are one
        // digital format has no second round-trip to make of it.
        inputRateCombo.addListener  (SWT.Selection, e -> publishFormatChange(true));
        outputRateCombo.addListener (SWT.Selection, e -> publishFormatChange(false));
        inputDepthCombo.addListener (SWT.Selection, e -> publishFormatChange(true));
        outputDepthCombo.addListener(SWT.Selection, e -> publishFormatChange(false));

        // --- OK / Cancel ----------------------------------------------------
        Composite buttonBar = new Composite(dialog, SWT.NONE);
        buttonBar.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));
        RowLayout rowLayout = new RowLayout(SWT.HORIZONTAL);
        rowLayout.spacing = 8;
        buttonBar.setLayout(rowLayout);

        Button okButton     = new Button(buttonBar, SWT.PUSH);
        Button cancelButton = new Button(buttonBar, SWT.PUSH);
        okButton.setText(I18n.t("common.ok"));
        cancelButton.setText(I18n.t("common.cancel"));
        dialog.setDefaultButton(okButton);

        // Audio-config / font / GPU snapshot of the working copy taken when the
        // dialog opens; the OK handler recomputes each to decide what the commit
        // must bounce (live streams) or rebuild (panes).  The audio half is taken
        // here for an OK pressed while the first device scan is still out (the
        // combos are empty then, so there is nothing to have edited) and re-taken
        // by that scan's fill - see the openState field.
        openState = takeOpenState();
        String fontsBefore = edit.getUiFontNormal() + "/" + edit.getUiFontBold();

        boolean gpuBefore  = edit.isUseGpuAcceleration();

        okButton.addListener(SWT.Selection, e -> {

            // Fold every control's value into the working copy `edit`.
            // --- Per-backend device / rate / depth for the shown backend.
            captureUiToActive();

            String fontsAfter = edit.getUiFontNormal() + "/" + edit.getUiFontBold();
            // A committed audio-config change bounces the live streams: STOP them
            // now - on the current panes and the OLD backend, before the commit
            // and the pane rebuild below - and restart after setActive (guarded by
            // needStartAudio near the end of this handler).
            boolean needStartAudio = false;
            if (!audioConfigFingerprint(edit).equals(openState.audioConfig())) {
                mainWindow.beforeApplyBackendChanges();
                needStartAudio = true;
            }
            // The GPU toggle, like fonts, takes effect by rebuilding the panes - the
            // scope pane chooses its surface (GL vs GC) at construction.
            if (!fontsAfter.equals(fontsBefore) || edit.isUseGpuAcceleration() != gpuBefore) {
                mainWindow.rebuildContent();
            }

            // --- Colour holders into edit.
            edit.setOscLeftChannelColor         (leftRgbHolder[0]);
            edit.setOscRightChannelColor        (rightRgbHolder[0]);
            edit.setFftLineColor                (fftLineColorHolder[0]);
            edit.setFftChartBackgroundColor     (fftBgColorHolder[0]);
            edit.setFftHarmonicDotColor         (fftDotColorHolder[0]);
            edit.setFftFreqRespColor            (fftFreqRespColorHolder[0]);
            edit.setFftBeforeCalDotColor        (fftBeforeCalDotColorHolder[0]);
            edit.setFftCalOverlayColor          (fftCalOverlayColorHolder[0]);
            edit.setFreqRespSignalColor         (freqRespSignalColorHolder[0]);
            edit.setFreqRespPhaseColor          (freqRespPhaseColorHolder[0]);
            edit.setFreqRespReferenceColor      (freqRespReferenceColorHolder[0]);
            edit.setFreqRespBackgroundColor     (freqRespBackgroundColorHolder[0]);
            // --- Nyquist percent -> fraction + freq-window clamp, into edit.
            applyNyquistToEdit();
            // (orientation, small icons, strong-tone rel-dB, compare-smoothing
            //  window, notch enable + base Hz already live on `edit` via their
            //  two-way binds.)
            BackendPrefs bp = edit.current();
            // Working-copy active range, one per direction (null when the selected
            // device has no card profile).  Recomputed here BEFORE the commit and,
            // for each direction that differs from the open-time openState,
            // published AFTER the commit so
            // subscribers read committed state - ranges reach the device only on OK.
            ActiveRange inRangeAfter  = activeRange(edit, bp, true);
            ActiveRange outRangeAfter = activeRange(edit, bp, false);
            log.info("Preferences saved: backend={}, in={} @ {} Hz / {} bits, out={} @ {} Hz / {} bits",
                    edit.getSelectedBackend().key(),
                    bp.getInputDeviceName()  != null ? bp.getInputDeviceName()  : "<none>",
                    bp.getInputSampleRate(),  bp.getInputBitDepth(),
                    bp.getOutputDeviceName() != null ? bp.getOutputDeviceName() : "<none>",
                    bp.getOutputSampleRate(), bp.getOutputBitDepth());
            // A remote selection is COMMITTED here - the one moment the staged
            // combo choice may touch the live session (spec 4.3 backend.select).
            // Idempotent: re-affirming the bench the session is already on is a
            // no-op, so a plain OK never bounces the generator lane.  Done
            // BEFORE the settings panels flush, whose locked writes must land
            // on the backend the session has actually selected.
            BackendKey committedKey = edit.getSelectedBackend();
            // Each wire step below carries its OWN wait rather than the whole
            // commit sharing one.  That is deliberate and it is not a style
            // choice: this sequence interleaves round trips with local work that
            // MUST stay on the display thread - applyFromDialog fires the
            // Property listeners the panes bind their widgets to, and those
            // listeners call combo.select / checkBox.setSelection with no
            // marshalling of their own.  Run on a worker they would throw
            // invalid-thread-access.  So the waits go where the wire is, and
            // nowhere else.
            if (committedKey.remote()) {
                if (routeRemote(dialog, committedKey)) {
                    // The card choices staged on this bench, now that it IS the
                    // committed one, under the apply-on-OK rule.  Before
                    // applyFromDialog below, so the local mirror each accepted
                    // write leaves on the working copy is committed with it.
                    commitStagedCardBindings(committedKey);
                } else {
                    Dialogs.error(dialog, I18n.t("net.servers.error.title"),
                            I18n.t("preferences.backend.remoteUnreachable"));
                }
            }
            // Single hand-off: commit the whole working copy to the live
            // singleton (which also persists once).
            // Component-owned blocks first: applyFromDialog() ends in save(),
            // so committing after it would write the previous values and leave
            // the new ones on disk only after some later save.
            Preferences.instance().commitCustomPreferencesEdit();
            // And the settings panels, whose pending values may live on a bench
            // rather than in this file (see BackendSettingsUi.commitEdit) - each
            // of those carries its own wait.
            BackendSettingsRegistry.instance().commitEdit();
            Preferences.instance().applyFromDialog(edit);
            // Resolve per-card FS for the just-committed selection so the dBV
            // axis and generator scale update immediately on OK (legacy scalars
            // when the device is unbound).
            Preferences committed = Preferences.instance();
            BackendPrefs committedBp = committed.current();
            applyCommittedProfile(committed, devices.inputs,
                    committedBp.getInputDeviceName(), true);
            applyCommittedProfile(committed, devices.outputs,
                    committedBp.getOutputDeviceName(), false);
            // Activate the chosen selection - BOTH its levels - on the live
            // AudioBackend; kept in the dialog so Preferences stays free of the
            // sound/hardware layer.  DIRECT on the OK path: the teardown of the
            // carrier being LEFT is bounded by the wire timeouts plus the
            // carrier-switch settle.
            AudioBackend.instance().setActive(edit.getSelectedBackend());
            // Fire the FreqResp refresh events once so the pane / view re-sync
            // to the committed state (range / scrollbars, smoothing table,
            // notch + colours).
            MessageBus bus = MessageBus.instance();
            bus.publish(Events.FREQRESP_RANGE_CHANGED);
            bus.publish(Events.FREQRESP_COMPARE_PARAMS_CHANGED);
            bus.publish(Events.FREQRESP_CALIBRATION_CHANGED);
            // Backend / device / rate edits move the Nyquist-derived field
            // bounds - let the panes re-pull them from the committed prefs.
            bus.publish(Events.AUDIO_FORMAT_CHANGED);
            ActiveRange inRangeBefore  = openState.inputRange();
            ActiveRange outRangeBefore = openState.outputRange();
            if (inRangeBefore != null && inRangeAfter != null && !inRangeBefore.equals(inRangeAfter)) bus.publish(Events.DEVICE_ACTIVE_RANGE_CHANGED, inRangeAfter);
            if (outRangeBefore != null && outRangeAfter != null && !outRangeBefore.equals(outRangeAfter)) bus.publish(Events.DEVICE_ACTIVE_RANGE_CHANGED, outRangeAfter);

            if (needStartAudio) {
                mainWindow.afterApplyBackendChanges();
            }
            // The operator must be told when what they just committed
            // has no calibration behind it - LAST, so the commit is complete and
            // the streams are already back up while the warning is on screen.
            boolean selectionChanged =
                    !edit.getSelectedBackend().key().equals(openState.backendKey())
                    || !Objects.equals(committedBp.getInputDeviceName(),  openState.inputDevice())
                    || !Objects.equals(committedBp.getOutputDeviceName(), openState.outputDevice());
            if (selectionChanged) {
                warnIfUncalibrated(committed, committedBp);
            }
            // Committed: the bench the combo is on IS the live one now, so the
            // dispose handler must not point it back at the old selection.
            okCommitted = true;
            dialog.close();
        });

        cancelButton.addListener(SWT.Selection, e -> {
            // The dialog edited a detached working copy, so there is nothing to
            // roll back - except the remote bench's routing, which browsing the
            // combo really did move (see restoreRemoteRouting, run on dispose).
            dialog.close();
        });

        // pack() sizes the shell to its content width (driven by the tab
        // folder's 700 px width hint); force the OUTER height to the fixed
        // 480 px so the window is the same compact height in every language,
        // authoritative over whatever the tallest tab's content would ask for.
        dialog.setMinimumSize(DIALOG_WIDTH_PX, SHELL_OUTER_HEIGHT_PX);
        // Audio is the DEFAULT tab.  Explicit:
        // the index-0 insertion alone leaves the first-CREATED item (Look &
        // Feel) selected - SWT keeps the selected ITEM, not the index, when
        // an insertion shifts it.
        tabs.setSelection(0);
        dialog.pack();
        dialog.setSize(dialog.getSize().x, SHELL_OUTER_HEIGHT_PX);
        Dialogs.centerOnParent(dialog);
        dialog.open();
        return dialog;
    }

    /**
     * Pushes one direction's per-channel full-scales for the just-committed
     * selection, resolving the committed device NAME back to the REF it was
     * enumerated as.
     *
     * <p>The ref is what carries the calibration of a device on a Phonalyser
     * server (spec 4.3 {@code cal}) - calibration lives where the device is
     * connected, and this machine's card store knows nothing about that bench -
     * so a name alone would silently fall back to a local card that merely
     * happens to share the name.  A name the enumeration no longer offers still
     * gets the name lookup, which is all there ever was for it.
     */
    private void applyCommittedProfile(Preferences committed, List<DeviceRef> refs,
            String deviceName, boolean input) {
        DeviceRef ref = refFor(refs, deviceName);
        if (ref != null) {
            committed.applyDeviceProfile(ref, input);
            return;
        }
        if (input) {
            committed.applyInputDeviceProfile(deviceName);
        } else {
            committed.applyOutputDeviceProfile(deviceName);
        }
    }

    /**
     * Sends every card choice staged on the committed bench (spec 4.3
     * {@code device.setCard}) - the OK half of the card chooser, and the only
     * place a binding leaves this machine.
     *
     * <p>ONE error dialog for the whole commit, like the uncalibrated warning: a
     * bench that refuses one binding is refusing them for one reason (the device
     * is locked by somebody else, or it dropped the card), and two modal dialogs
     * in a row on OK would say the same thing twice.  A refusal is reported and
     * the commit continues, exactly as an unreachable bench does above - the rest
     * of the dialog's work is not the binding's to undo.
     *
     * <p>Each binding carries its own wait (see {@code BenchCards.bind}), so this
     * stays on the display thread and may raise its dialog directly.
     *
     * <p>ONE walk, not one per direction: a card describes a physical box and the
     * bench keys the binding on the device NAME (spec 4.3), so a device listed
     * under one name in both directions - the QA40x - has exactly one choice to
     * send.  The staged active-range moves ride the same walk and the same error
     * dialog, being the same kind of write: what the operator changed about the
     * bench's card, sent on OK and nowhere else.
     */
    private void commitStagedCardBindings(BackendKey committed) {
        List<String> refused = new ArrayList<>(
                benchCards.commitStagedBindings(committed, devices.inputs, devices.outputs));
        refused.addAll(benchCards.commitStagedRanges(committed, devices.inputs,
                devices.outputs));
        if (refused.isEmpty()) return;
        Dialogs.error(parent, I18n.t("net.servers.error.title"),
                I18n.t("preferences.audio.card.bindFailed", String.join(", ", refused)));
    }

    /** The enumerated ref {@code deviceName} was listed as, or null when the name
     *  is unset or the enumeration no longer offers it.  The ref is what carries a
     *  server-side calibration (spec 4.3 {@code cal}), so every question about the
     *  selected device's calibration starts here. */
    private DeviceRef refFor(List<DeviceRef> refs, String deviceName) {
        if (deviceName == null) return null;
        for (DeviceRef ref : refs) {
            if (deviceName.equals(ref.name())) return ref;
        }
        return null;
    }

    /**
     * ONE warning per OK when the committed selection would measure without a
     * calibration behind it: the operator has to know that this device/card is
     * uncalibrated and that V, dBV and everything derived from them are not
     * accurate.  A device with a built-in calibration, such as the QA40x, is
     * exempt.
     *
     * <p>Both directions are tested but only one dialog is raised, naming the
     * device(s) it applies to; the exemptions and the precedence are
     * {@link Preferences#isUncalibrated}'s, so this cannot disagree with what the
     * selection is actually measured against.
     */
    private void warnIfUncalibrated(Preferences committed, BackendPrefs bp) {
        List<String> uncalibrated = new ArrayList<>();
        if (isUncalibrated(committed, devices.inputs, bp.getInputDeviceName(), true)) {
            uncalibrated.add(bp.getInputDeviceName());
        }
        if (isUncalibrated(committed, devices.outputs, bp.getOutputDeviceName(), false)) {
            uncalibrated.add(bp.getOutputDeviceName());
        }
        if (uncalibrated.isEmpty()) return;
        if (log.isInfoEnabled()) {
            log.info("Uncalibrated selection committed: {}", uncalibrated);
        }
        Dialogs.warn(parent, I18n.t("preferences.audio.uncalibrated.title"),
                I18n.t("preferences.audio.uncalibrated.message", String.join(", ", uncalibrated)));
    }

    /** One direction's half of {@link #warnIfUncalibrated}: the ref's answer when
     *  the enumeration still offers the device, the name-only one otherwise.  No
     *  device selected is nothing to warn about. */
    private boolean isUncalibrated(Preferences committed, List<DeviceRef> refs,
            String deviceName, boolean input) {
        if (deviceName == null) return false;
        DeviceRef ref = refFor(refs, deviceName);
        return ref != null ? committed.isUncalibrated(ref, input)
                           : committed.isUncalibrated(deviceName, input);
    }

    /**
     * The working copy's active-range label for one direction, wrapped as an
     * {@link ActiveRange}, or {@code null} when the direction's selected
     * device has no card profile.  Used both to publish the range after the OK
     * commit and - with the other direction - as part of {@link
     * #audioConfigFingerprint}.  The payload is generic on purpose: direction +
     * label only, no card identity - the subscriber consults committed state
     * itself.
     */
    private ActiveRange activeRange(Preferences prefs, BackendPrefs bp, boolean input) {
        String dev = input ? bp.getInputDeviceName() : bp.getOutputDeviceName();
        if (dev == null) return null;
        AudioDeviceProfile profile = prefs.resolveDeviceProfile(dev);
        if (profile == null) return null;
        DeviceEndpointConfig endPoint = input ? profile.getInput() : profile.getOutput();
        String newLabel = endPoint == null ? null : endPoint.getActiveRange();
        if (newLabel == null) return null;
        return new ActiveRange(input, newLabel);
    }

    /** Capture support (help screenshots): builds the card editor on the Audio
     *  tab's INPUT card (resolved from the seeded {@code devices.yaml}) and shows
     *  it non-modally, returning it so the automation can snapshot + dispose it.
     *  Requires the dialog to be open; null when no input card is selected.
     *  Mirrors the pencil button's edit flow without committing a result. */
    public CardEditorDialog openInputCardEditorForCapture() {
        CardEditorDialog dlg = inputCard.editorForSelected();
        if (dlg != null) dlg.showForCapture();
        return dlg;
    }

    // The percent -> fraction conversion + freq-window clamp run on OK,
    // straight into the working copy.  Held in a local so the OK handler
    // can invoke it before applyFromDialog().
    private void applyNyquistToEdit() {
        double pct  = Math.max(FREQRESP_MAX_FREQ_PCT_MIN,
                Math.min(FREQRESP_MAX_FREQ_PCT_MAX, nyqWork.get()));
        double frac = pct / 100.0;
        edit.setFreqRespNyquistFraction(frac);
        int sr = edit.current().getInputSampleRate();
        double maxBand = (sr > 0 ? sr * 0.5 : 24000.0) * frac;
        if (edit.getFreqRespFreqMaxHz() > maxBand) {
            edit.setFreqRespFreqMaxHz(maxBand);
            if (edit.getFreqRespFreqMinHz() > maxBand) {
                edit.setFreqRespFreqMinHz(Math.max(1.0, maxBand * 0.5));
            }
        }
    }

    // Repopulate the input-side rate / depth combos from the picked input
    // device's own capabilities.  Falls back to defaults when no device
    // is picked or the driver reports nothing.
    private void refreshInputRatesAndDepths() {
        showInputFormats(probeInputFormats(edit.getSelectedBackend().carrier(),
                pickedDevice(inputCombo, devices.inputs)));
    }

    // Mirror of {@code refreshInputRatesAndDepths} for the output side.
    private void refreshOutputRatesAndDepths() {
        showOutputFormats(probeOutputFormats(edit.getSelectedBackend().carrier(),
                pickedDevice(outputCombo, devices.outputs)));
    }

    /** Asks {@code dev} which input formats it supports - a fixed rate x depth
     *  grid run against the driver, i.e. real device I/O, which is why the scan
     *  path asks it off the display thread.  An empty list for "no device", read
     *  by {@link #showInputFormats} exactly like a probe that found nothing. */
    private List<AudioFormat> probeInputFormats(AudioBackendType carrier, DeviceRef dev) {
        return dev == null ? List.of()
                : AudioBackend.instance().listSupportedInputFormats(carrier, dev);
    }

    /** Output twin of {@link #probeInputFormats}. */
    private List<AudioFormat> probeOutputFormats(AudioBackendType carrier, DeviceRef dev) {
        return dev == null ? List.of()
                : AudioBackend.instance().listSupportedOutputFormats(carrier, dev);
    }

    /** Fills the input rate + depth combos from probed {@code formats}, keeping
     *  the working copy's values selected where they are still offered. */
    private void showInputFormats(List<AudioFormat> formats) {
        BackendPrefs bp = edit.current();
        // Every remote selection rides on AudioBackendType.NET (see the note in
        // refreshCustomPrefsButton), so the carrier is what says whether the
        // device is on another machine - and a bench's "no rates" must stay "no
        // rates" rather than becoming the built-in table.
        boolean remote = edit.getSelectedBackend().carrier() == AudioBackendType.NET;
        populateIntCombo(inputRateCombo,  fallback(ratesOf(formats),  DEFAULT_SAMPLE_RATES, remote), " Hz",   bp.getInputSampleRate());
        populateIntCombo(inputDepthCombo, fallback(depthsOf(formats), DEFAULT_BIT_DEPTHS, remote),   " bits", bp.getInputBitDepth());
    }

    /** Output twin of {@link #showInputFormats}. */
    private void showOutputFormats(List<AudioFormat> formats) {
        BackendPrefs bp = edit.current();
        boolean remote = edit.getSelectedBackend().carrier() == AudioBackendType.NET;
        populateIntCombo(outputRateCombo,  fallback(ratesOf(formats),  DEFAULT_SAMPLE_RATES, remote), " Hz",   bp.getOutputSampleRate());
        populateIntCombo(outputDepthCombo, fallback(depthsOf(formats), DEFAULT_BIT_DEPTHS, remote),   " bits", bp.getOutputBitDepth());
    }

    // Captures the current UI state into the prefs of {@code active[0]} so
    // a backend switch (or OK) doesn't lose the user's edits.  Devices
    // are stored by name (string) - the saved name is matched back to a
    // live DeviceRef on dialog open via populateDeviceCombo.
    private void captureUiToActive() {
        captureDeviceNamesToActive();
        BackendPrefs bp = edit.current();
        int idx;
        if ((idx = inputRateCombo.getSelectionIndex())  >= 0) bp.setInputSampleRate (parseLeadingInt(inputRateCombo.getItem(idx)));
        if ((idx = inputDepthCombo.getSelectionIndex()) >= 0) bp.setInputBitDepth   (parseLeadingInt(inputDepthCombo.getItem(idx)));
        if ((idx = outputRateCombo.getSelectionIndex()) >= 0) bp.setOutputSampleRate(parseLeadingInt(outputRateCombo.getItem(idx)));
        if ((idx = outputDepthCombo.getSelectionIndex())>= 0) bp.setOutputBitDepth  (parseLeadingInt(outputDepthCombo.getItem(idx)));
    }

    /** Writes the two device combos' picks into the working copy - the ONLY part
     *  of {@link #captureUiToActive()} that {@link #refreshDevices()} may run,
     *  because the rate / depth combos are repopulated after it and their old
     *  selection belongs to the backend being left. */
    private void captureDeviceNamesToActive() {
        BackendPrefs bp = edit.current();
        bp.setInputDeviceName (nameOf(pickedDevice(inputCombo,  devices.inputs)));
        bp.setOutputDeviceName(nameOf(pickedDevice(outputCombo, devices.outputs)));
    }

    /** Shows the per-backend settings button only for a backend that has
     *  settings of its own - labelled with that backend's name ("QA40x
     *  properties"); hidden, it also gives up its grid cell so the backend row
     *  keeps its layout. */
    private void refreshCustomPrefsButton(Button button) {
        // A backend HAS its own settings exactly when some module registered a UI
        // for it.  The audio layer is not asked - it does not know about panels.
        //
        // The question is asked of what the selected bench IS, never of the
        // carrier it is reached through: every remote selection rides on
        // AudioBackendType.NET, so asking the carrier would hide the QA40x panel
        // for a QA403 on a server - a backend whose settings exist and are
        // reachable (net protocol 4.6) - and would offer the net carrier's own
        // panel, which does not exist, for every other remote backend.
        //
        // Registering a service is not the same as HAVING a panel: a backend whose
        // UI layer exists only to arm its bus listeners registers one all the same
        // (start() is the seam for that), and it answers hasCustomPreferences()
        // false - a button that opened nothing would be worse than no button.
        AudioBackendType shown = edit.getSelectedBackend().type();
        BackendSettingsUi panel = BackendSettingsRegistry.instance().forBackend(shown);
        boolean hasSettings = panel != null && panel.hasCustomPreferences();
        // A bench report says this button reappears after a switch away and back,
        // which no path here explains - so every decision names itself once, and
        // one reproduction with debug on says which path really touched it.
        if (log.isDebugEnabled()) {
            log.debug("customPrefs button: shown backend {}, panel {}, hasCustomPreferences {} "
                            + "-> {} (visible before: {})",
                    shown, panel == null ? "none" : panel.getClass().getSimpleName(),
                    panel != null && panel.hasCustomPreferences(), hasSettings ? "SHOW" : "HIDE",
                    button.isDisposed() ? "disposed" : Boolean.toString(button.getVisible()));
        }
        if (hasSettings) {
            button.setText(I18n.t("preferences.backend.customPrefs",
                    shown.getDisplayName()));
        }
        hideUnless(button, hasSettings);
    }

    /** Shows {@code control} when {@code shown}, and otherwise hides it AND
     *  takes its grid cell away, so the row it sits in closes up instead of
     *  keeping a gap where a button would have been. */
    private void hideUnless(Control control, boolean shown) {
        // Both halves or neither: a caller that moved visibility without the grid
        // exclude would leave the row holding a gap, and one that moved exclude
        // without visibility would leave a control drawn outside its cell.  The
        // pair is logged with the CALLER so a control that reappears names the
        // path that showed it.
        if (log.isDebugEnabled()) {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            log.debug("hideUnless({}) on {} - layoutData {}, called from {}",
                    shown, control.getClass().getSimpleName(),
                    control.getLayoutData() == null ? "none"
                            : control.getLayoutData().getClass().getSimpleName(),
                    stack.length > 2 ? stack[2] : "?");
        }
        control.setVisible(shown);
        if (control.getLayoutData() instanceof GridData gd) {
            gd.exclude = !shown;
        }
        control.getParent().layout(true, true);
    }

    /**
     * Fills the backend combo from {@link BackendChoices} - the local backends
     * this OS can open, then one entry per backend of the connected server - and
     * moves the working copy onto whatever the model resolved the selection to.
     *
     * <p>That move is the disconnect fallback: a selection the list no longer
     * offers cannot be shown, and the dialog EDITS what it shows.
     */
    private void populateBackendCombo() {
        RemoteBackendUi remote = RemoteBackendRegistry.instance().getUi();
        choices.rebuild(remote == null ? List.of() : remote.entries());
        backendCombo.removeAll();
        for (String label : choices.labels()) {
            backendCombo.add(label);
        }
        BackendKey wanted = edit.getSelectedBackend();
        // Null only in the degenerate case where there is nothing to offer AND
        // nothing saved to keep - then there is no selection to move onto either.
        BackendKey shown = choices.resolve(wanted);
        if (shown != null && !shown.equals(wanted)) {
            log.info("Backend {} is not reachable from here; falling back to {}",
                    wanted.key(), shown.key());
            edit.setSelectedBackend(shown);
        }
        backendCombo.select(choices.indexOf(shown));
    }

    /** Re-composes the combo after the server list was open - a connection may
     *  have added a bench's backends, and a disconnect taken them away - and
     *  re-reads the devices of whatever selection survived that. */
    private void refreshBackendCombo() {
        populateBackendCombo();
        applyBackendSelection(edit.getSelectedBackend());
    }

    /** The operator picked an entry.  A remote one has to be COMMITTED on the
     *  server first ({@code backend.select}); a bench that cannot be reached any
     *  more takes its entries out of the combo instead of leaving a selection
     *  behind that no device can be opened on. */
    private void onBackendPicked() {
        int index = backendCombo.getSelectionIndex();
        if (index < 0) return;
        // Persist the outgoing backend's UI state into the working copy before
        // switching, so toggling back later restores what the user just chose.
        // capture() targets edit.current() (the OLD backend), then the selection
        // below makes current() the NEW one.
        captureUiToActive();
        // A picked backend ends the open-time state whether or not the fill of
        // the selection the dialog opened on has landed yet: from here every fill
        // carries an EDIT, and OK has to see it (see openState).
        openStatePending = false;
        if (!applyBackendSelection(choices.at(index))) {
            Dialogs.error(backendCombo.getShell(), I18n.t("net.servers.error.title"),
                    I18n.t("preferences.backend.remoteUnreachable"));
            refreshBackendCombo();
            return;
        }
        // The bench is now showing its devices: the give-this-device-a-card offer
        // belongs to this user-driven moment, not to the passive refresh that
        // filled the combos.
        if (inputCard  != null) inputCard.offerBenchCard();
        if (outputCard != null) outputCard.offerBenchCard();
        setupShownBackendInBackground();
    }

    /**
     * Runs the SHOWN local backend's {@link AudioDeviceManager#setup()} off the
     * UI thread and repopulates the card sections when it answered true.
     *
     * <p>Why here: a device attached AFTER start-up missed the launch sweep
     * ({@code AudioBackend.setupInBackground()}), and nothing else opens it until
     * the first capture - so a QA40x picked in this dialog showed no card even
     * though {@code setup()} would have read the calibration page and created it
     * (the manager's create-or-refresh).  The
     * two user-driven discovery moments - a backend pick and a device scan - are
     * exactly when the operator expects the hardware to be looked at.
     *
     * <p>A remote selection is NOT set up from here: its devices live on the
     * server, whose own start-up sweep owns them.  A live session is safe:
     * {@code setup()} refuses rather than parking a measurement, and a manager
     * with no device-side state answers cheaply - this is not QA40x-specific
     * wiring, just the one manager reacting to its own hardware.
     */
    private void setupShownBackendInBackground() {
        BackendKey shown = edit.getSelectedBackend();
        if (shown.remote() || !backendSetupRunning.compareAndSet(false, true)) {
            return;
        }
        Display display = backendCombo.getDisplay();
        Thread worker = new Thread(() -> {
            boolean cardMayHaveChanged;
            try {
                cardMayHaveChanged = AudioBackend.instance().manager(shown.type()).setup();
            } catch (RuntimeException ex) {
                log.warn("Backend setup on selection failed for {}: {}", shown.key(), ex.toString());
                cardMayHaveChanged = false;
            } finally {
                backendSetupRunning.set(false);
            }
            if (!cardMayHaveChanged || display.isDisposed()) {
                return;
            }
            display.asyncExec(() -> {
                // Still the same dialog, still showing the backend that was set up?
                if (backendCombo == null || backendCombo.isDisposed()
                        || !edit.getSelectedBackend().equals(shown)) {
                    return;
                }
                // The manager wrote the device-read card into the LIVE store, but
                // this dialog resolves cards off its detached working copy - and
                // OK replaces the live list with that copy wholesale, so without
                // this import the fresh card is invisible now and ERASED on OK
                // (the first-time-selection bug).
                importDeviceAuthoredCards();
                // FORCED: the import replaced cards under the sections, which is
                // a store change and not a selection change.
                if (inputCard  != null) inputCard.refreshForced();
                if (outputCard != null) outputCard.refreshForced();
            });
        }, "backend-setup");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Brings the working copy's card store up to date with what a backend's
     * {@code setup()} just wrote into the LIVE store: a card the copy has no
     * name for is added, and a device-authored card
     * ({@code calibrationFromDevice}) is replaced - the device owns those
     * values, the same rule the seed merge applies.  User-authored cards the
     * copy already holds are left exactly as the operator may have edited them
     * in this dialog.
     */
    private void importDeviceAuthoredCards() {
        for (AudioDeviceProfile live : Preferences.instance().getAudioDeviceProfiles()) {
            boolean deviceAuthored = live.getInput().isCalibrationFromDevice()
                    || live.getOutput().isCalibrationFromDevice();
            if (deviceAuthored || edit.findAudioDeviceProfile(live.getName()) == null) {
                edit.putAudioDeviceProfile(live);   // getAudioDeviceProfiles hands out copies
            }
        }
    }

    /** COMMITS the net backend onto {@code key} (spec 4.3 backend.select) -
     *  {@code false} when the server behind it is gone or refused.  OK-time
     *  only; idempotent for the selection the session is already on.  DIRECT on
     *  the OK path, bounded by the wire timeouts; a dead wire is a refusal, not
     *  a throw into the commit. */
    private boolean routeRemote(Shell dialog, BackendKey key) {
        RemoteBackendUi remote = RemoteBackendRegistry.instance().getUi();
        if (remote == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(remote.select(key));
        } catch (RuntimeException ex) {
            log.warn("backend.select failed for {}: {}", key.key(), ex.toString());
            return false;
        }
    }

    /**
     * Fills the device catalogue for {@code key} WITHOUT committing anything -
     * the staged read every pre-OK path uses ({@link RemoteBackendUi#preview}).
     * {@code false} when the server is gone or does not serve that backend.
     *
     * <p><b>Greyed, not covered by a window.</b>  This is a {@code devices.list}
     * round trip and it fires on every combo pick, on every Scan and on the
     * dialog's own open - a modal "please wait" flashing up each time would be
     * worse than what it replaces.  So the backend combo goes insensitive for the
     * duration instead: the operator sees the pick land and the control go
     * quiet, the rest of the window keeps painting, and a second pick cannot be
     * made on top of the read that is already out.
     */
    private boolean previewRemote(BackendKey key) {
        RemoteBackendUi remote = RemoteBackendRegistry.instance().getUi();
        if (remote == null) {
            return false;
        }
        return Boolean.TRUE.equals(whileBackendComboLocked(() -> remote.preview(key)));
    }

    /** Runs {@code work} with the backend combo greyed - see
     *  {@link #previewRemote}.  DIRECT on the caller's thread, bounded by the
     *  wire timeouts.  The combo is put back however the work ended, including
     *  when it threw: a control left insensitive is a dialog the operator
     *  cannot use again. */
    private <T> T whileBackendComboLocked(Supplier<T> work) {
        boolean relock = backendCombo != null && !backendCombo.isDisposed()
                && backendCombo.isEnabled();
        if (relock) {
            backendCombo.setEnabled(false);
        }
        try {
            return work.get();
        } finally {
            if (relock && !backendCombo.isDisposed()) {
                backendCombo.setEnabled(true);
            }
        }
    }

    /**
     * Puts the device CATALOGUE back on the selection the application is running
     * on, when this dialog was closed WITHOUT committing.
     *
     * <p>Browsing the combo never touches the live session any more - it only
     * previews, which re-points the manager's catalogue snapshot.  That snapshot
     * is process-wide state all the same: left on the browsed backend, the next
     * enumeration after a Cancel would list bench B's devices for the bench A
     * the preferences still name.  So the preview is simply run once more, at
     * the live selection.
     *
     * <p>A local live selection needs nothing: the net manager is not what the
     * modules are opening devices through, and the next remote selection
     * previews itself.
     */
    private void restoreRemoteRouting(BackendKey liveBackend) {
        if (okCommitted || liveBackend == null || !liveBackend.remote()
                || liveBackend.equals(edit.getSelectedBackend())) {
            return;
        }
        log.info("Preferences cancelled: previewing back at {}", liveBackend.key());
        previewRemote(liveBackend);
    }

    /**
     * Shows {@code key}: the working copy moves onto it, the per-backend settings
     * button follows, and the device combos are filled from it.
     *
     * <p><b>A remote bench is PREVIEWED, never committed.</b>  Everything in this
     * dialog stages until OK, and spec 4.3's {@code backend.select} is no
     * exception: committing it here re-points the process-wide manager, whose
     * take-over starts by closing the generator lane - which is how merely
     * OPENING this dialog used to make the running signal disappear.  The devices
     * come from a plain {@code devices.list} read instead, and every selection
     * that does not come from a click - the one restored from the preferences on
     * open, the one surviving a re-compose after the server list closed - is
     * previewed the same way, so the combos fill without a click either.  The OK
     * handler is where the shown selection becomes the committed one.
     *
     * <p>The selected backend's own settings UI is then told what is shown
     * ({@link BackendSettingsUi#onSelected}), BEFORE the combos and cards fill,
     * and is handed the WORKING COPY to write into: the QA40x uses that moment to
     * mirror the bench's calibration and ranges into its device card, which the
     * card section is about to read.
     *
     * @return false when a remote bench refused the enumeration or could not be
     *         reached, in which case the caller must not leave the combo on it
     */
    private boolean applyBackendSelection(BackendKey key) {
        if (key == null) {
            // Nothing to show: no backend is offered AND none was saved (see
            // populateBackendCombo's degenerate case).  The guard belongs HERE -
            // both the selection write below and the enumeration further down
            // dereference the key, so testing it afterwards was a dead clause.
            return true;
        }
        edit.setSelectedBackend(key);
        refreshCustomPrefsButton(customPrefsButton);
        boolean reachable = !key.remote() || previewRemote(key);
        if (reachable) {
            BackendSettingsUi settings =
                    BackendSettingsRegistry.instance().forBackend(key.type());
            if (settings != null) {
                // The WORKING COPY, not the live singleton: whatever the panel
                // aligns has to be visible to the card section three lines down
                // and has to die with a Cancel, like every other edit here.
                settings.onSelected(key, edit);
            }
        }
        refreshDevices();
        return reachable;
    }

    /** "Scan devices": re-reads what is reachable, local AND remote.  The COMBO
     *  is re-composed first - scanning is also how a just-plugged analyzer's
     *  backend appears in the list and an unplugged one leaves it - then the
     *  surviving selection's devices are re-read through
     *  {@code applyBackendSelection} (a read-only preview for a remote one, so a
     *  scan mid-measurement bounces nothing).  The connected server's backend
     *  list refreshes on the side; its answer re-composes the combo again only
     *  if the bench actually changed. */
    private void rescanDevices() {
        refreshRemoteEntries();
        // The operator's scan is the explicit "I distrust the list" gesture, so
        // rebuild the shown backend's enumeration first - for a snapshot backend
        // (PortAudio) this is the ONLY way a card plugged in since start-up can
        // appear at all.  Backends whose lists are live answer false and the
        // re-population below simply reads the current truth.
        AudioBackend.instance().refreshDeviceLists(edit.getSelectedBackend().carrier());
        captureUiToActive();
        populateBackendCombo();
        if (!applyBackendSelection(edit.getSelectedBackend())) {
            Dialogs.error(backendCombo.getShell(), I18n.t("net.servers.error.title"),
                    I18n.t("preferences.backend.remoteUnreachable"));
            refreshBackendCombo();
            return;
        }
        // A scan is the other user-driven discovery moment: a just-plugged
        // analyzer should get its card created now, not at the first capture.
        setupShownBackendInBackground();
    }

    /** Kicks the connected server's backend-list refresh - non-blocking; the
     *  {@code REMOTE_BACKENDS_CHANGED} subscription re-composes the combo when
     *  (and only when) the answer differs. */
    private void refreshRemoteEntries() {
        RemoteBackendUi remote = RemoteBackendRegistry.instance().getUi();
        if (remote != null) {
            remote.refreshEntries();
        }
    }

    /**
     * Re-reads the shown selection's devices and fills the combos, the rate /
     * depth lists and the card sections from what came back.
     *
     * <p><b>The dialog is shown FIRST and the hardware is asked after.</b>  A
     * LOCAL enumeration plus the two format probes are seconds of real device
     * I/O - a driver's device walk, and a rate x depth grid opened against the
     * picked device - and running them here left the operator waiting on a window
     * that had not painted yet, whether they were opening the dialog or only
     * switching a backend inside it.  The scan therefore runs on {@link #scanner}
     * and the answer lands in ONE {@link Display#asyncExec} (see
     * {@link #fillDevices}), which is dropped when the dialog has been closed
     * meanwhile or the selection has moved on since.
     *
     * <p><b>Only the last selection is really scanned.</b>  The scanner is serial,
     * so five backends switched through in a row would queue five full scans and
     * the one the operator is actually looking at would wait behind four
     * throw-away answers.  Each call therefore opens a new generation
     * ({@link #startScanGeneration()}) and a queued scan that finds its own
     * superseded ({@link #scanSuperseded}) gives up before touching a driver.  The
     * fill-time guard remains the second gate: a scan can also be overtaken while
     * it is already running, and that answer must not land either.
     *
     * <p>A REMOTE selection stays direct: its catalogue and its per-device
     * formats came with the {@code devices.list} the preview above just read
     * (spec 4.3 inlines them), so there is nothing left to wait for - and the
     * user-driven card offers that run right after a bench pick need the fill to
     * have happened.
     */
    private void refreshDevices() {
        // The chosen backend's hardware WITHOUT activating it - the
        // type-parameterised overloads resolve the manager by the shown
        // selection's CARRIER and never touch the live active selection.
        BackendKey shown = edit.getSelectedBackend();
        BackendPrefs bp = edit.current();
        String inputName  = bp.getInputDeviceName();
        String outputName = bp.getOutputDeviceName();
        // Bumped for a remote selection too: a bench picked after a local backend
        // is what makes the local scan still sitting in the queue pointless.
        int generation = startScanGeneration();
        if (shown.remote()) {
            fillDevices(shown, scanDevices(shown.carrier(), inputName, outputName));
            return;
        }
        // Resolved HERE, on the display thread: a disposed widget no longer
        // answers getDisplay(), and the worker must not be the one to find out.
        Display display = backendCombo.getDisplay();
        scanner.execute(() -> {
            if (scanSuperseded(generation)) {
                return;         // another selection is shown - this answer is waste
            }
            DeviceScan scan;
            try {
                scan = scanDevices(shown.carrier(), inputName, outputName);
            } catch (RuntimeException ex) {
                // A driver that throws leaves the combos as they are - and says
                // so, which a swallowed worker exception would not.
                if (log.isWarnEnabled()) {
                    log.warn("Device scan failed for {}: {}", shown.key(), ex.toString());
                }
                return;
            }
            if (!display.isDisposed()) {
                display.asyncExec(() -> fillDevices(shown, scan));
            }
        });
    }

    /** Opens a new scan generation and answers it - every {@link #refreshDevices}
     *  call supersedes the ones before it, whether or not their scan has run
     *  yet. */
    int startScanGeneration() {
        return scanGeneration.incrementAndGet();
    }

    /** Whether the scan started at {@code generation} has been overtaken by a
     *  newer selection.  Asked by a queued scan on ENTRY, so a switched-through
     *  backend costs a comparison instead of a driver enumeration; the fill-time
     *  selection guard then catches the scan that was overtaken mid-flight. */
    boolean scanSuperseded(int generation) {
        return generation != scanGeneration.get();
    }

    /**
     * The device I/O half of {@link #refreshDevices}: {@code carrier}'s two
     * device lists plus the formats of the device each combo will preselect -
     * the one carrying the saved name, else the first offered, which is
     * {@link #populateDeviceCombo}'s own rule asked before the combo has it.
     *
     * <p>Touches no widget and writes no field, which is what lets it run on
     * {@link #scanner}.
     */
    private DeviceScan scanDevices(AudioBackendType carrier, String inputName, String outputName) {
        List<DeviceRef> inputs  = AudioBackend.instance().listInputDevices(carrier);
        List<DeviceRef> outputs = AudioBackend.instance().listOutputDevices(carrier);
        return new DeviceScan(inputs, outputs,
                probeInputFormats(carrier, preferredOrFirst(inputs, inputName)),
                probeOutputFormats(carrier, preferredOrFirst(outputs, outputName)));
    }

    /** The device a combo will preselect for {@code preferredName}: the one that
     *  carries the name, else the first offered - {@link #populateDeviceCombo}'s
     *  rule, so the scan probes the device the fill is about to show. */
    private DeviceRef preferredOrFirst(List<DeviceRef> refs, String preferredName) {
        DeviceRef named = refFor(refs, preferredName);
        if (named != null) return named;
        return refs.isEmpty() ? null : refs.get(0);
    }

    /**
     * The UI half of {@link #refreshDevices}: everything the scan brought back,
     * applied in one go on the display thread.
     *
     * <p>DROPPED when the dialog is gone - a fill into disposed widgets, or into
     * a working copy Cancel has already discarded, must not happen - and dropped
     * when the shown selection is no longer the one that was scanned, which would
     * otherwise put one backend's devices under another one's name.
     */
    private void fillDevices(BackendKey scanned, DeviceScan scan) {
        if (backendCombo == null || backendCombo.isDisposed()
                || !edit.getSelectedBackend().equals(scanned)) {
            return;
        }
        devices.inputs  = scan.inputs();
        devices.outputs = scan.outputs();
        BackendPrefs bp = edit.current();
        populateDeviceCombo(inputCombo,  devices.inputs,  bp.getInputDeviceName());
        populateDeviceCombo(outputCombo, devices.outputs, bp.getOutputDeviceName());
        // The combos now show a device the working copy does not name yet:
        // populateDeviceCombo only moves the SWT selection (it falls back to the
        // first device when the saved name matches nothing), and everything below
        // reads the device back off `edit` - the card resolution in
        // CardSection.refresh() and the card name on the rate-change payload.
        // On the FIRST selection of a backend its BackendPrefs is a fresh empty
        // one, so without this write the card combo and its range table stayed
        // dead until OK ran captureUiToActive() - card and ranges appeared only
        // after Preferences OK.
        captureDeviceNamesToActive();
        showInputFormats(scan.inputFormats());
        showOutputFormats(scan.outputFormats());
        // Announce the input format after a (re)populate so a format-constraint
        // subscriber can mirror it onto the output combos - a backend switch (or
        // dialog open) lands already-coupled.  Sent UNCONDITIONALLY (total
        // decoupling): the dialog holds no device knowledge; the payload carries
        // the edited backend and a subscriber that doesn't constrain it simply
        // ignores the event.  The PREFS_SAMPLE_RATE_SET subscription in open()
        // applies any answer.
        publishFormatChange(true);
        // Repopulate the card combo + range table for the (possibly new)
        // backend / device - the sections are built before the first fill can
        // land, so they are already present here.
        if (inputCard  != null) inputCard.refresh();
        if (outputCard != null) outputCard.refresh();
        // The selection the dialog opened on is only fully resolved now - see
        // openState for why OK compares against THIS and not against the saved
        // names it started from.
        if (openStatePending) {
            openStatePending = false;
            openState = takeOpenState();
        }
    }

    /** Announces one direction's chosen sample rate AND bit depth on the bus so a
     *  format-constraint subscriber ({@code Qa40xRateConstraint},
     *  {@code LoopbackFormatConstraint}) can mirror what it constrains onto the
     *  other direction.  Device-agnostic - it carries the edited backend AND the
     *  resolved card name so a subscriber can key off whichever it constrains; a
     *  no-op when the rate combo has no selection, and the depth rides as
     *  {@code NO_BIT_DEPTH} when that combo has none. */
    private void publishFormatChange(boolean input) {
        Combo rateCombo = input ? inputRateCombo : outputRateCombo;
        int idx = rateCombo.getSelectionIndex();
        if (idx < 0) return;
        MessageBus.instance().publish(Events.PREFS_SAMPLE_RATE_CHANGED,
                new SampleRateChange(input, parseLeadingInt(rateCombo.getItem(idx)),
                        selectedComboValue(input ? inputDepthCombo : outputDepthCombo),
                        edit.getSelectedBackend(), cardNameFor(input)));
    }

    /** The leading integer of {@code combo}'s selected item, or
     *  {@link SampleRateChange#NO_BIT_DEPTH} when nothing is selected. */
    private int selectedComboValue(Combo combo) {
        int idx = combo.getSelectionIndex();
        return idx < 0 ? SampleRateChange.NO_BIT_DEPTH : parseLeadingInt(combo.getItem(idx));
    }

    /** The resolved card name for a direction's selected device, or {@code null}
     *  when the device maps to no card - carried on the rate-change payload so a
     *  card-scoped constraint can match it. */
    private String cardNameFor(boolean input) {
        BackendPrefs bp = edit.current();
        String dev = input ? bp.getInputDeviceName() : bp.getOutputDeviceName();
        if (dev == null) return null;
        AudioDeviceProfile card = edit.resolveDeviceProfile(dev);
        return card == null ? null : card.getName();
    }

    /** Programmatically selects {@code combo}'s item whose leading integer equals
     *  {@code value} - a rate in hertz, or a depth in bits (a no-op when that
     *  value isn't offered).  {@code Combo.select}
     *  fires no SWT.Selection, so the format coupling that calls this never recurses. */
    private void selectComboItem(Combo combo, int value) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (parseLeadingInt(combo.getItem(i)) == value) {
                combo.select(i);
                return;
            }
        }
    }

    /** Re-derives the Audio tab's V-scroll min size from its content's
     *  preferred size, so the scrollbar appears exactly when the content
     *  (which grows/shrinks as a card is selected -> its range table shows /
     *  hides) exceeds the tab viewport at the fixed
     *  {@link #SHELL_OUTER_HEIGHT_PX} window height.  Called after the initial
     *  device refresh and from every card/range rebuild. */
    private void refreshAudioScrollMinSize() {
        if (audioScroll == null || audioScroll.isDisposed() || audioTab == null || audioTab.isDisposed()) return;
        audioTab.layout(true, true);
        audioScroll.setMinSize(audioTab.computeSize(SWT.DEFAULT, SWT.DEFAULT));
    }

    /** One tab-level relayout per UI turn, however many range tables asked for
     *  it: the full Audio-tab layout + min-size computeSize costs hundreds of
     *  milliseconds, and a backend switch rebuilds BOTH directions' tables. */
    private void scheduleAudioRelayout() {
        if (audioRelayoutPending || audioTab == null || audioTab.isDisposed()) return;
        audioRelayoutPending = true;
        audioTab.getDisplay().asyncExec(() -> {
            audioRelayoutPending = false;
            refreshAudioScrollMinSize();
        });
    }

    private boolean audioRelayoutPending;

    /** Layout for a value field (combo / numeric / colour / font row): a fixed width so every
     *  field on every tab is the same size, FILL so the control occupies it exactly, no grab.
     *  The label column grabs the slack (see {@link #gridLabel}), so the fixed-width field
     *  column is pushed to the dialog's right edge - all fields end up right-aligned. */
    /** The value column's cell: anchored at the label's right edge and GRABBING
     *  the horizontal slack, so widening the (resizable) dialog widens the
     *  fields in place instead of carrying them to the right edge.
     *  {@code widthHint} stays the base width at the initial size. */
    private GridData comboData() {
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.widthHint = FIELD_WIDTH_PX;
        return gd;
    }

    /** A left-column label on a fixed-width cell - the slack belongs to the
     *  VALUE column (see {@link #comboData()}), keeping every field anchored
     *  left and growing with the dialog. */
    private void gridLabel(Composite parent, String text) {
        Label l = new Label(parent, SWT.NONE);
        l.setText(text);
        l.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
    }

    /** One settings row laid out like the field rows: a left-column label (grabbing the slack)
     *  and a bare checkbox at the value column's LEFT edge - the same anchor
     *  the fields sit on (see {@link #comboData()}), so nothing drifts right
     *  when the dialog widens.  Returns the checkbox so the caller can bind /
     *  enable it. */
    private Button buildCheckRow(Composite parent, String labelKey, String tooltipKey) {
        Label label = new Label(parent, SWT.NONE);
        label.setText(I18n.t(labelKey));
        label.setToolTipText(I18n.t(tooltipKey));
        label.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        Button box = new Button(parent, SWT.CHECK);
        box.setToolTipText(I18n.t(tooltipKey));
        box.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, true, false));
        return box;
    }

    /** One Look&Feel font row: a read-only text showing the current spec
     *  ("Consolas 9 bold") plus a ... button opening the system FontDialog
     *  seeded with it.  A picked font is written straight to the dialog's
     *  detached {@code edit} property - OK commits, Cancel drops it. */
    private void buildFontRow(Composite parent, Property<String> fontSpec, String tooltip) {
        Composite row = new Composite(parent, SWT.NONE);
        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 0; gl.marginHeight = 0; gl.horizontalSpacing = 4;
        row.setLayout(gl);
        row.setLayoutData(comboData());

        Fonts fonts = Fonts.instance();
        Text preview = new Text(row, SWT.BORDER | SWT.READ_ONLY);
        preview.setText(fonts.describe(fontSpec.get()));
        preview.setToolTipText(tooltip);
        preview.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button browse = new Button(row, SWT.PUSH);
        browse.setText("...");
        browse.setToolTipText(tooltip);
        browse.addListener(SWT.Selection, e -> {
            FontDialog fd = new FontDialog(row.getShell());
            fd.setText(tooltip);
            fd.setFontList(new FontData[]{ fonts.toFontData(fontSpec.get()) });
            FontData picked = fd.open();
            if (picked == null) return;
            fontSpec.set(fonts.toSpec(picked));
            preview.setText(fonts.describe(fontSpec.get()));
        });
    }

    /** Formats the FreqResp "Maximal analyzed frequency" label as
     *  {@code "Maximal analyzed frequency, % Nyquist (24000 Hz)"}.  The
     *  bracketed Hz is {@code nyqFraction · sampleRate / 2} so the user
     *  sees the absolute upper band at the current input sample rate.
     *  Falls back to {@code "(- Hz)"} when no input device is picked
     *  yet (sampleRate ≤ 0). */
    private String formatMaxFreqLabel(double nyqFraction, int sampleRate) {
        String base = I18n.t("preferences.freqResp.maxFreqPctNyquist");
        String hz;
        if (sampleRate <= 0) {
            hz = "- Hz";
        } else {
            double f = sampleRate * 0.5 * nyqFraction;
            if (f >= 1000.0) {
                hz = String.format(Locale.ROOT, "%.2f kHz", f / 1000.0);
            } else {
                hz = String.format(Locale.ROOT, "%.0f Hz", f);
            }
        }
        return base + " (" + hz + ")";
    }

    /** Recolours the button's swatch and replaces its label with a fresh hex string. */
    private void applyButtonColor(Button btn, int rgbInt) {
        Color old = btn.getBackground();
        Color c = new Color(btn.getDisplay(), unpackRgb(rgbInt));
        btn.setBackground(c);
        btn.setText(String.format("#%06X", rgbInt & 0xFFFFFF));
        // Dispose the previous swatch lazily on widget dispose so we don't
        // leak - the dispose listener fires once, after the last setBackground.
        Color disposeOld = old;
        btn.addDisposeListener(e -> {
            if (disposeOld != null && !disposeOld.isDisposed()) disposeOld.dispose();
            if (!c.isDisposed()) c.dispose();
        });
    }

    private RGB unpackRgb(int rgbInt) {
        return new RGB((rgbInt >> 16) & 0xFF, (rgbInt >> 8) & 0xFF, rgbInt & 0xFF);
    }

    private int packRgb(RGB rgb) {
        return ((rgb.red & 0xFF) << 16) | ((rgb.green & 0xFF) << 8) | (rgb.blue & 0xFF);
    }

    /**
     * Populates {@code combo} with the {@code displayName()} of every device,
     * preselecting the one whose {@code name()} matches {@code preferredName}
     * (case-sensitive).  Falls back to the first device when the saved name
     * doesn't match anything currently enumerated.
     */
    private void populateDeviceCombo(Combo combo, List<DeviceRef> devices,
                                     String preferredName) {
        combo.removeAll();
        int selectIdx = -1;
        for (int i = 0; i < devices.size(); i++) {
            DeviceRef d = devices.get(i);
            combo.add(deviceLabel(d));
            if (preferredName != null && preferredName.equals(d.name())) {
                selectIdx = i;
            }
        }
        if (selectIdx < 0 && !devices.isEmpty()) {
            selectIdx = 0;
        }
        if (selectIdx >= 0) {
            combo.select(selectIdx);
        }
    }

    /**
     * Refuses the pick of a device another client is measuring on: SILENTLY
     * snaps the combo back to the selection it had before the pick (the
     * working copy still holds it - {@code captureUiToActive} runs after this
     * guard), so the click simply does nothing.  The web build greys such
     * entries out; a native SWT combo can neither disable nor grey one item -
     * Win32's dropdown-list has no per-item state without owner-drawing, which
     * SWT does not expose - so the label carries the "in use by" suffix and the
     * selection refuses to land.  Nothing is beeped: the pick is a no-op, not
     * an error worth a sound.
     *
     * @return true when the pick was refused and reverted - the caller's
     *         handler must stop, nothing changed
     */
    private boolean revertLockedDevicePick(Combo combo, List<DeviceRef> list, String storedName) {
        int idx = combo.getSelectionIndex();
        if (list == null || idx < 0 || idx >= list.size()) {
            return false;
        }
        RemoteBackendUi remote = RemoteBackendRegistry.instance().getUi();
        if (remote == null || remote.lockedBy(list.get(idx)) == null) {
            return false;               // free, ours, or a local backend
        }
        int back = 0;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name().equals(storedName)) {
                back = i;
                break;
            }
        }
        combo.select(back);
        combo.setVisibleItemCount(combo.getItemCount());
        return true;
    }

    /** What the device combo shows for {@code device}: its display name, and -
     *  for a bench on a Phonalyser server - who is measuring on it when another
     *  client holds its lock.  Spec 4.3 sends that with every device so a client
     *  can show it; without it a taken device looks free and the operator finds
     *  out only when the open is refused.  A local device has no such state and
     *  reads exactly as before. */
    private String deviceLabel(DeviceRef device) {
        RemoteBackendUi remote = RemoteBackendRegistry.instance().getUi();
        String by = remote == null ? null : remote.lockedBy(device);
        return by == null ? device.displayName()
                : I18n.t("preferences.device.lockedBy", device.displayName(), by);
    }

    /** Returns the device's {@code name()} or {@code null} if {@code device} is null. */
    private String nameOf(DeviceRef device) {
        return device != null ? device.name() : null;
    }

    private DeviceRef pickedDevice(Combo combo, List<DeviceRef> devices) {
        int idx = combo.getSelectionIndex();
        return (idx >= 0 && idx < devices.size()) ? devices.get(idx) : null;
    }

    private TreeSet<Integer> ratesOf(List<AudioFormat> formats) {
        TreeSet<Integer> out = new TreeSet<>();
        for (AudioFormat f : formats) {
            if (f.getSampleRate() > 0.0f) {
                out.add(Math.round(f.getSampleRate()));
            }
        }
        return out;
    }

    private TreeSet<Integer> depthsOf(List<AudioFormat> formats) {
        TreeSet<Integer> out = new TreeSet<>();
        for (AudioFormat f : formats) {
            if (f.getSampleSizeInBits() > 0) {
                out.add(f.getSampleSizeInBits());
            }
        }
        return out;
    }

    /**
     * The probed values, or the built-in candidate table when the probe found
     * nothing - but ONLY for a device on this machine.
     *
     * <p><b>A REMOTE DEVICE NEVER GETS THE FALLBACK.</b> For a local backend an
     * empty probe means "this build could not ask", and offering the standard
     * table lets the operator try a rate the device may well accept. For a
     * device on a Phonalyser server it means something else entirely: the bench
     * answered, and its answer was "none". Filling the combo with 8 kHz to
     * 768 kHz there would invent capabilities for hardware on another machine
     * and offer rates the bench has already said it cannot open. A bench that
     * reports no rates therefore shows no rates - the same thing the browser
     * client does, and the honest one.
     */
    private TreeSet<Integer> fallback(TreeSet<Integer> probed, int[] defaults, boolean remote) {
        if (probed != null && !probed.isEmpty()) return probed;
        if (remote) return new TreeSet<>();
        TreeSet<Integer> out = new TreeSet<>();
        for (int v : defaults) out.add(v);
        return out;
    }

    /**
     * Replaces the items of an integer-valued combo with {@code values} (each
     * suffixed with {@code unit}), preserving {@code preferred} as the
     * selected entry when it appears in the new list, otherwise selecting the
     * first item.
     */
    private void populateIntCombo(Combo combo, TreeSet<Integer> values,
                                  String unit, int preferred) {
        combo.removeAll();
        int selectIdx = -1;
        int i = 0;
        for (int v : values) {
            combo.add(v + unit);
            if (v == preferred) selectIdx = i;
            i++;
        }
        if (selectIdx < 0 && combo.getItemCount() > 0) selectIdx = 0;
        if (selectIdx >= 0) combo.select(selectIdx);
    }

    /** Parses the leading non-negative integer out of a combo item label like {@code "44100 Hz"}. */
    private int parseLeadingInt(String label) {
        int end = 0;
        while (end < label.length() && Character.isDigit(label.charAt(end))) end++;
        return end > 0 ? Integer.parseInt(label.substring(0, end)) : 0;
    }

    // === Per-card calibration profile section ==============================
    // Appended inside each direction Group (input / output): a "Card" combo
    // that binds the direction's device to a physical-card profile, plus a
    // range table editing that card's per-attenuator/gain full-scale voltages.
    // EVERYTHING here mutates the detached `edit` working copy (never the live
    // singleton, never save()): edit.putAudioDeviceProfile() replaces by name
    // on the copy, whose save() is inert while detached.  OK commits the whole
    // copy via applyFromDialog(); the resolved FS is pushed by the sibling
    // resolution call there through setAdcFsVoltageRms / setDacFsVoltageAmpl.

    /** Full-scale voltage floor used to seed a freshly added range when the
     *  endpoint has no prior range to copy from: a small positive value so the
     *  dBV display stays finite (mirrors {@code AMP_MIN_VRMS} in FftTabControl).
     *  The value is never edited in this dialog - the crosshair Calibrate flows
     *  own the real full-scale voltage. */
    private static final double RANGE_FS_MIN_V = 1e-9;

    /** One editable range-table row: the label text + backing model.  The
     *  full-scale value is NOT edited here - it is produced solely by the
     *  crosshair Calibrate flows and persists on {@link DeviceRange}. */
    private static final class RangeRow {
        Composite        composite;
        /** The LEFT-channel "active range" radio (drives {@code activeRange}).  In
         *  LINKED mode this is the sole radio.  Each row sits in its own Composite,
         *  so the radios do NOT auto-exclude - {@link CardSection#userSetActive}
         *  clears the siblings by hand. */
        Button           activeRadio;
        /** The RIGHT-channel active-range radio (drives {@code activeRangeRight}),
         *  present only in INDEPENDENT mode; {@code null} in LINKED mode. */
        Button           activeRadioRight;
        Text             labelField;
        /** INDEPENDENT-mode mirror of {@link #labelField} in the Right group -
         *  both show/edit the SAME range label (synced on rename); {@code null}
         *  in LINKED / MONO mode. */
        Text             labelFieldRight;
        /** Source of truth for label + FS - an element of the endpoint's range list. */
        DeviceRange      range;

        private RangeRow() {
        }
    }

    /** The card-profile UI + behaviour for ONE direction (input or output).
     *  Holds the direction's widgets and the working-copy edits its handlers
     *  make; {@link #refresh()} re-derives the whole section from `edit` and
     *  the currently selected device on every backend / device change. */
    private final class CardSection {
        private final boolean input;
        private final Combo cardCombo;
        private final Button editBtn;
        private final Label rangesLabel;
        private final Composite rangesContainer;
        private final List<RangeRow> rows = new ArrayList<>();
        /** Combo index -> profile; {@code null} only at the last index (New card...).
         *  Rebuilt with the combo in {@link #refresh()}.  EMPTY while
         *  {@link #remoteCards} - a bench's cards have no local profile behind
         *  them, and resolving one by name is the collision the
         *  calibration-follows-the-device rule forbids. */
        private final List<AudioDeviceProfile> comboProfiles = new ArrayList<>();
        /** Combo index -> card name, whichever store the list came from - what the
         *  selection handler reads.  Parallel to the combo's own items. */
        private final List<String> comboNames = new ArrayList<>();
        /** True when the combo is showing the BENCH's cards rather than this
         *  machine's, i.e. the selected backend is remote. */
        private boolean remoteCards;
        /** Logical name of the card the combo currently shows, or null when no
         *  card is selected - the value a cancelled "New card..." reverts to. */
        private String selectedName;
        /** Device name the "no card assigned" prompt was last shown for, so a
         *  passive refresh / rebuild never re-asks for the same device within
         *  this dialog session. */
        private String lastPromptedDevice;
        /** {@link PreferencesDialog#cardRenderIdentity} of what the section is
         *  showing, or null when it has never been rendered - or was last
         *  rendered for a BENCH, whose card list is re-read from the wire on
         *  every refresh. */
        private String renderedIdentity;

        private CardSection(Combo cardCombo, Button editBtn, Label rangesLabel,
                            Composite rangesContainer, boolean input) {
            this.cardCombo       = cardCombo;
            this.editBtn         = editBtn;
            this.rangesLabel     = rangesLabel;
            this.rangesContainer = rangesContainer;
            this.input           = input;
            cardCombo.addListener(SWT.Selection, e -> onCardSelected());
            editBtn.addListener(SWT.Selection, e -> editSelectedCard());
        }

        /** Current device name for this direction, straight off the working copy. */
        private String deviceName() {
            BackendPrefs bp = edit.current();
            return input ? bp.getInputDeviceName() : bp.getOutputDeviceName();
        }

        private DeviceEndpointConfig endpointOf(AudioDeviceProfile p) {
            return PreferencesDialog.this.endpointOf(p, input);
        }

        /** Re-derives the combo, its preselection, and the range table from the
         *  working copy + the selected device.  The combo lists only profiles whose
         *  endpoint for THIS direction has at least one range (the seeded known cards
         *  included) - an output-only card never clutters the input combo and vice
         *  versa.  The preselection is the unified lookup
         *  ({@link Preferences#resolveDeviceProfile}): the card whose {@code match}
         *  list has the longest entry that is a substring of the device name, or no
         *  selection (empty combo, no range table) when none matches.  A device with
         *  no correlated card therefore never keeps showing the previous card - the
         *  combo VISIBLY clears; the offer-to-create prompt lives in
         *  {@link #onDeviceChanged}, not here, so a passive refresh / rebuild never
         *  pops a dialog.  A matched card is pre-selected and its ranges show, but its
         *  device name is bound onto {@code match} only when the user confirms it
         *  (selecting the combo entry -> {@link #onCardSelected} -> {@link #bindAlias});
         *  the user sees it and can change it first.
         *
         *  <p>A refresh that would reproduce what the section already shows does
         *  nothing at all - the identity of the render is resolved first and
         *  compared with the last one
         *  ({@link PreferencesDialog#cardRenderIdentity}).
         *  {@link #refreshForced()} is for the callers that changed something
         *  that identity cannot see. */
        private AudioDeviceProfile refresh() {
            if (cardCombo.isDisposed()) return null;
            String dev = deviceName();
            BackendKey bench = edit.getSelectedBackend();
            boolean remote = bench != null && bench.remote();
            // What a rebuild WOULD show, resolved before a single widget is
            // touched: when it is what the section already shows, the teardown,
            // the row creation and the relayout below would only reproduce it -
            // and that trio is what a backend switch spends its seconds on.  A
            // BENCH section takes no such shortcut: its list is a cards.list round
            // trip whose answer IS the refresh.
            List<AudioDeviceProfile> listable = remote ? List.of() : listableCards();
            AudioDeviceProfile pick = !remote && dev != null ? edit.resolveDeviceProfile(dev) : null;
            String identity = remote ? null : cardRenderIdentity(input, dev, listable, pick);
            if (identity != null && identity.equals(renderedIdentity)) {
                return pick;
            }
            // Dropped for the whole rebuild, so a section interrupted half-way
            // through one is never taken for a rendered one.
            renderedIdentity = null;

            cardCombo.removeAll();
            comboProfiles.clear();
            comboNames.clear();
            remoteCards = remote;
            if (remoteCards) {
                return refreshBenchCards(bench, dev);
            }
            for (AudioDeviceProfile p : listable) {
                cardCombo.add(p.getName());
                comboProfiles.add(p);
                comboNames.add(p.getName());
            }

            cardCombo.add(I18n.t("preferences.audio.card.new"));
            comboProfiles.add(null);                                  // last = New card...
            comboNames.add(null);

            // A resolved / suggested card is selected; anything else (unknown
            // device, or no device) leaves the combo empty with no range table.
            selectByProfile(pick);
            rebuildTable();
            renderedIdentity = identity;
            return pick;
        }

        /** {@link #refresh()} with the skip disarmed, for a caller that changed
         *  something the identity does not carry: the card store under the
         *  section (a create, an edit, a device-authored import), or the combo
         *  itself - a cancelled "New card..." leaves it sitting on an entry no
         *  model value corresponds to. */
        private AudioDeviceProfile refreshForced() {
            renderedIdentity = null;
            return refresh();
        }

        /** The cards the combo lists: every profile in the working copy whose
         *  endpoint for THIS direction carries at least one range - an
         *  output-only card never clutters the input combo and vice versa. */
        private List<AudioDeviceProfile> listableCards() {
            List<AudioDeviceProfile> out = new ArrayList<>();
            for (AudioDeviceProfile p : edit.getAudioDeviceProfiles()) {
                if (!endpointOf(p).getRanges().isEmpty()) out.add(p);
            }
            return out;
        }

        /**
         * The same combo over the BENCH's cards (spec 4.3 {@code cards.list}) -
         * the card a device uses is the user's choice, and for a device on a
         * Phonalyser server the cards to choose from are that server's: its store
         * owns the calibration of everything plugged into it.
         *
         * <p>No "New card...": a card is created and calibrated where the device
         * is.  The range table below is NOT keyed on this combo for the same
         * reason - showing a local card's ranges under a bench card that merely
         * shares its name is exactly the collision this dialog is careful not to
         * make anywhere else - it is keyed on the DEVICE and shows only what the
         * device itself supplied (see {@link #rangeCard()}).
         *
         * @return null always - a bench device resolves to no local card, which is
         *         also what stops {@link #onDeviceChanged} offering to create one
         */
        private AudioDeviceProfile refreshBenchCards(BackendKey bench, String dev) {
            for (String name : benchCards.list(bench, input)) {
                cardCombo.add(name);
                comboNames.add(name);
            }
            // The STAGED pick first: while the dialog lives, the combo shows what
            // the operator chose, even though nothing has been sent yet.  It is
            // keyed on the device NAME, so both combos of a one-name-two-directions
            // box (the QA40x) show the one pick that will be sent for it.
            String staged = benchCards.stagedCard(bench, dev);
            selectByName(staged != null ? staged
                    : benchCards.boundCard(bench, refFor(refs(), dev), dev));
            rebuildTable();
            return null;
        }

        /** This direction's enumerated devices - the list the ref behind the
         *  selected name comes from. */
        private List<DeviceRef> refs() {
            return input ? devices.inputs : devices.outputs;
        }

        /** A user-driven device change on this direction's device combo:
         *  re-resolve the card (visibly switching to the correlated one, or
         *  clearing when there is none) and, when resolve AND suggest both fail
         *  for a real device, offer to create a card via the house confirm dialog.
         *  The offer fires once per device name per dialog session (guarded by
         *  {@link #lastPromptedDevice}) so it never spams on repeated switches. */
        private void onDeviceChanged() {
            AudioDeviceProfile pick = refresh();
            String dev = deviceName();
            if (dev == null) return;
            if (remoteCards) {
                // A device on a bench: its card is created and calibrated THERE,
                // never here - a local one made for it could only ever be found
                // again by a name collision.
                offerBenchCard();
                return;
            }
            if (pick != null) return;                      // a local card correlated
            if (dev.equals(lastPromptedDevice)) return;    // already offered for this device
            lastPromptedDevice = dev;
            int answer = Dialogs.confirm(parent,
                    I18n.t("preferences.audio.card.noCardAssigned.title"),
                    I18n.t("preferences.audio.card.noCardAssigned.message", dev));
            if (answer == SWT.YES) createNewCard(dev);
            // NO - leave the section unbound (refresh() already cleared it).
        }

        /**
         * A device on a BENCH that has no calibration of its own, whose name this
         * machine does have a calibrated card for, is offered - once, explicitly -
         * to be copied to the bench.  The copy is never silent: the operator is
         * asked before the client's values go up.
         *
         * <p>Only from a user-driven selection, never from {@link #refresh()}: a
         * passive rebuild popping a dialog is the thing this file's card prompt
         * already refuses to do.  The offer is silent about a device the bench has
         * calibrated itself, about one this machine cannot calibrate either, and
         * about a {@code calibrationFromDevice} card - a QA40x's values are the
         * analyzer's and neither side may overwrite them.
         *
         * <p>Accepting sends spec 4.3's {@code device.setCalibration}, which is the
         * bench's store from then on; declining is remembered for the run
         * ({@link CalibrationCopyOffers}), so neither switching device and back nor
         * reopening this dialog asks again.
         */
        private void offerBenchCard() {
            BackendKey bench = edit.getSelectedBackend();
            String dev = deviceName();
            if (bench == null || !bench.remote() || dev == null || mainWindow == null) return;
            DeviceRef ref = refFor(refs(), dev);
            if (ref == null || ref.calibration() != null) return;   // the bench has its own
            AudioDeviceProfile local = edit.resolveDeviceProfile(dev);
            boolean copyable = local != null && !endpointOf(local).isCalibrationFromDevice()
                    && edit.deviceCalibration(dev, input) != null;
            // ONE offer per bench + direction + device per run, whichever of the two
            // it turns out to be: they are the same question - "this bench device
            // has no calibration; shall we give it one?" - and an operator who said
            // no must not be asked the other way round straight after.
            if (!copyable && ref.boundCard() != null) return;       // a card IS in force there
            if (!mainWindow.getCalibrationCopyOffers().mayAsk(bench, input, dev)) return;
            boolean answered = copyable
                    ? copyLocalCardToBench(bench, ref, dev, local)
                    : createBenchCard(bench, ref, dev);
            // ONLY a settled question is remembered.  An attempt that never reached
            // the bench - a wire glitch, a device somebody took a second ago - is
            // not an answer, and burning the one offer on it would leave the device
            // uncalibrated for the whole run with no way back but a restart.
            if (answered) {
                mainWindow.getCalibrationCopyOffers().settle(bench, input, dev);
            }
        }

        /**
         * The copy offer's YES branch: the whole local card goes up (spec 4.3
         * {@code cards.put} + {@code device.setCard}), and its full scales alone go
         * up when the bench will not take the card - either way the device is
         * calibrated where it is plugged in, and nothing was written here.
         *
         * <p>Every outcome is REPORTED, because they are not the same thing to an
         * operator who confirmed "copy card X": the card and its binding, or only
         * the numbers (into whichever card that bench resolves for the device), or
         * a card that is there but bound to nothing.  The no-silent-copy rule cuts
         * both ways - what actually happened has to be as visible as the offer was.
         *
         * @return whether the question was ANSWERED (a decline, or something that
         *         reached the bench) - see {@link CalibrationCopyOffers#settle}
         */
        private boolean copyLocalCardToBench(BackendKey bench, DeviceRef ref, String dev,
                AudioDeviceProfile local) {
            int answer = Dialogs.confirm(parent,
                    I18n.t("preferences.audio.card.copyCalibration.title"),
                    I18n.t("preferences.audio.card.copyCalibration.message", dev, local.getName()));
            if (answer != SWT.YES) return true;             // declined - asked and answered
            BenchCards.Copied copied = benchCards.propagateLocalCard(bench, ref, dev, input);
            switch (copied) {
                case CARD -> { }                            // whole card, bound: nothing to say
                case VALUES_ONLY -> Dialogs.info(parent,
                        I18n.t("preferences.audio.card.copyCalibration.title"),
                        I18n.t("preferences.audio.card.copyCalibration.valuesOnly",
                                local.getName()));
                case CARD_UNBOUND -> Dialogs.error(parent, I18n.t("net.servers.error.title"),
                        I18n.t("preferences.audio.card.bindFailed", local.getName()));
                default -> {
                    Dialogs.error(parent, I18n.t("net.servers.error.title"),
                            I18n.t("preferences.audio.card.copyCalibration.failed", dev));
                    return false;                           // nothing landed - ask again later
                }
            }
            refresh();   // the bench's card list - and its binding - just moved
            return true;
        }

        /**
         * NEITHER side has a card for this bench device, so the operator
         * is asked to make one - in the same card editor a local card is made in,
         * but the result is stored on the BENCH ({@code cards.put}) and bound there
         * ({@code device.setCard}), never in this installation's store.
         *
         * <p>Declining is not a dead end: the next calibration written to this
         * device creates a bare card on the bench by itself
         * ({@code device.setCalibration} creates on write) - the create-an-empty-card
         * fallback, with no second protocol verb needed for it.
         */
        private boolean createBenchCard(BackendKey bench, DeviceRef ref, String dev) {
            int answer = Dialogs.confirm(parent,
                    I18n.t("preferences.audio.card.noCardAssigned.title"),
                    I18n.t("preferences.audio.card.noCardAssigned.message", dev));
            if (answer != SWT.YES) return true;            // declined - asked and answered
            AudioDeviceProfile seed = new AudioDeviceProfile();
            seed.setName(edit.normalizeDeviceName(dev));
            seed.getMatch().add(dev);
            CardEditorDialog dlg = new CardEditorDialog(parent, seed,
                    input ? CardEditorDialog.Capability.INPUT_ONLY
                          : CardEditorDialog.Capability.OUTPUT_ONLY,
                    benchCardNames(), null, DEFAULT_ADC_FS_VRMS, DEFAULT_DAC_FS_AMPL);
            AudioDeviceProfile card = dlg.open();
            if (card == null) return true;                 // cancelled - nothing anywhere
            BenchCards.Copied made = benchCards.createAndBind(bench, ref, card);
            if (made == BenchCards.Copied.NOTHING) {
                Dialogs.error(parent, I18n.t("net.servers.error.title"),
                        I18n.t("preferences.audio.card.bindFailed", card.getName()));
                return false;                              // the bench took nothing
            }
            if (made == BenchCards.Copied.CARD_UNBOUND) {
                // The card IS on the bench - say so plainly rather than let the
                // operator author it a second time and hit the name next run.
                Dialogs.error(parent, I18n.t("net.servers.error.title"),
                        I18n.t("preferences.audio.card.bindFailed", card.getName()));
            }
            refresh();
            return true;
        }

        /** The card names the BENCH already has, for the editor's uniqueness check
         *  - the combo's own list, which is exactly what {@code cards.list} just
         *  answered.  The bench re-checks anyway; this only spares the operator a
         *  round trip to be told. */
        private List<String> benchCardNames() {
            List<String> names = new ArrayList<>();
            for (String name : comboNames) {
                if (name != null) names.add(name);
            }
            return names;
        }

        /** Selects the combo entry for {@code p} without firing the
         *  user-selection handler ({@code combo.select} is silent).  A null
         *  profile - or one absent from the combo - leaves the combo with no
         *  selection (empty text). */
        private void selectByProfile(AudioDeviceProfile p) {
            int idx = -1;
            if (p != null) {
                for (int i = 0; i < comboProfiles.size() - 1; i++) {
                    if (comboProfiles.get(i) != null && comboProfiles.get(i).getName().equals(p.getName())) {
                        idx = i;
                        break;
                    }
                }
            }
            if (idx < 0) {
                cardCombo.deselectAll();
                selectedName = null;
            } else {
                cardCombo.select(idx);
                selectedName = comboProfiles.get(idx).getName();
            }
        }

        /** {@link #selectByProfile} by card NAME - what a bench card list has to
         *  select by, since there is no local profile to match against.  A name
         *  the list does not offer (a binding to a card the bench has since
         *  dropped) leaves the combo empty, which is the truth. */
        private void selectByName(String cardName) {
            int idx = cardName == null ? -1 : comboNames.indexOf(cardName);
            if (idx < 0) {
                cardCombo.deselectAll();
                selectedName = null;
            } else {
                cardCombo.select(idx);
                selectedName = cardName;
            }
        }

        /** The card combo's user-selection handler - the ask-flow.  This IS the
         *  chooser: what the user picks here is SAVED as the card that device
         *  uses, and from then on it beats any name-match guess. */
        private void onCardSelected() {
            int idx = cardCombo.getSelectionIndex();
            if (idx < 0) return;
            String dev = deviceName();
            if (remoteCards) {
                bindBenchCard(dev, comboNames.get(idx));
                return;
            }
            if (idx == comboProfiles.size() - 1) {          // New card...
                createNewCard(dev);
                return;
            }
            AudioDeviceProfile p = comboProfiles.get(idx);   // always an existing card
            bindAlias(p, dev);
            bindCard(dev, p.getName());
            selectedName = p.getName();
            rebuildTable();
            // The section now shows the PICK.  The binding makes the resolve
            // follow it, but only when there was a device to bind it to, so the
            // next refresh re-derives instead of skipping on a stale identity.
            renderedIdentity = null;
        }

        /** Records the user's card choice for a LOCAL device - the saved binding
         *  {@link Preferences#resolveDeviceProfile} consults before its match
         *  rule, so a card that merely recognises the name can no longer overrule
         *  the one the operator picked.  Committed with the rest of
         *  the working copy on OK. */
        private void bindCard(String dev, String cardName) {
            if (dev == null) return;
            edit.bindDeviceToCard(edit.deviceBindingKey(null, dev), cardName);
        }

        /** The same choice for a device on a BENCH: STAGED, like every other edit
         *  in this dialog ("open prefs dialog any changes should be first applied
         *  when clicked ok!").  The binding is stored on the server (spec 4.3
         *  {@code device.setCard}) by {@link #commitStagedBindings} on OK; Cancel
         *  drops the working copy and nothing was ever sent - which matters more
         *  here than anywhere else in this dialog, because a write that had
         *  already left for another machine could not be taken back. */
        private void bindBenchCard(String dev, String cardName) {
            BackendKey bench = edit.getSelectedBackend();
            if (dev == null || bench == null) return;
            benchCards.stage(bench, input, dev, cardName);
            selectedName = cardName;
        }

        /** "New card..." - open the card create dialog prefilled with the
         *  normalized device name and the triggering device name as the first
         *  {@code match} entry, then create the returned profile in `edit` (the
         *  dialog seeds a "default" range per chosen direction from the current
         *  global scalar).  The dialog's editable match list already carries the
         *  device name, so no separate bind step is needed. */
        private void createNewCard(String dev) {
            AudioDeviceProfile seed = new AudioDeviceProfile();
            seed.setName(dev != null ? edit.normalizeDeviceName(dev) : "");
            if (dev != null) seed.getMatch().add(dev);
            CardEditorDialog dlg = new CardEditorDialog(parent, seed,
                    input ? CardEditorDialog.Capability.INPUT_ONLY : CardEditorDialog.Capability.OUTPUT_ONLY,
                    currentCardNames(), null, DEFAULT_ADC_FS_VRMS, DEFAULT_DAC_FS_AMPL);
            AudioDeviceProfile p = dlg.open();
            if (p == null) {
                // Cancelled - restore the pre-dialog state.  FORCED: the model is
                // unchanged, but the combo is sitting on "New card...".
                refreshForced();
                return;
            }
            edit.putAudioDeviceProfile(p);
            String name = p.getName();
            // Creating a card FOR this device is choosing it - bound
            // before the rebuild, so the resolve below already sees the choice.
            bindCard(dev, name);
            // Rebuild the combo to list the new card, then select it explicitly
            // (a match-based resolve would also find it when its list carries the
            //  device name, but a device-less create has nothing to resolve by -
            //  pin the selection either way).
            refreshForced();
            selectByProfile(edit.findAudioDeviceProfile(name));
            rebuildTable();
            // The pin can show a different card than the resolve inside the
            // refresh recorded, so the next refresh must not skip on it.
            renderedIdentity = null;
        }

        /** Opens the card edit dialog on the currently selected card and replaces
         *  it in the working copy on OK, re-keying cleanly on a rename (remove the
         *  old name, put under the new). */
        /** Builds (but does not open) the card editor on the currently selected
         *  card, or null when nothing is selected - shared by the interactive
         *  {@link #editSelectedCard()} and the help-capture hook
         *  {@link PreferencesDialog#openInputCardEditorForCapture()}. */
        private CardEditorDialog editorForSelected() {
            if (selectedName == null) return null;
            AudioDeviceProfile live = edit.findAudioDeviceProfile(selectedName);
            if (live == null) return null;
            return new CardEditorDialog(parent, live,
                    CardEditorDialog.Capability.of(live),
                    currentCardNames(), live.getName(), DEFAULT_ADC_FS_VRMS, DEFAULT_DAC_FS_AMPL);
        }

        private void editSelectedCard() {
            CardEditorDialog dlg = editorForSelected();
            if (dlg == null) return;
            AudioDeviceProfile p = dlg.open();
            if (p == null) return;                         // cancelled - nothing changed
            if (!p.getName().equalsIgnoreCase(selectedName)) {
                edit.removeAudioDeviceProfile(selectedName);   // rename - drop the old key
            }
            edit.putAudioDeviceProfile(p);
            refreshForced();                               // the card itself changed
            selectByProfile(edit.findAudioDeviceProfile(p.getName()));
            rebuildTable();
            // The pin can show a different card than the resolve inside the
            // refresh recorded, so the next refresh must not skip on it.
            renderedIdentity = null;
        }

        /** The logical names of every card currently in the working copy - the
         *  card-editor's name-uniqueness check. */
        private List<String> currentCardNames() {
            List<String> names = new ArrayList<>();
            for (AudioDeviceProfile p : edit.getAudioDeviceProfiles()) names.add(p.getName());
            return names;
        }

        /** Binds card {@code p} to the current device name by APPENDING it to the
         *  card's {@code match} list - only when no existing entry already matches
         *  it ({@link AudioDeviceProfile#bindDeviceName}).  Overlaps between cards
         *  now resolve by longest-match, so no exclusive unbind of the name from
         *  other cards is done; the card-editor list is where the user prunes. */
        private void bindAlias(AudioDeviceProfile p, String dev) {
            if (dev == null) return;
            AudioDeviceProfile live = edit.findAudioDeviceProfile(p.getName());
            if (live == null) return;
            if (live.bindDeviceName(dev)) {
                edit.putAudioDeviceProfile(live);
            }
        }

        /** Rebuilds the range table from the card that carries this direction's
         *  ranges (hidden when there is none - see {@link #rangeCard()}). */
        private void rebuildTable() {
            // Every row is disposed and built again below, so the container is
            // frozen for the whole population and painted ONCE at the end instead
            // of once per widget destroyed and once per widget created.
            // try/finally: a container left with its redraw off would never paint
            // again.
            rangesContainer.setRedraw(false);
            try {
                for (RangeRow r : rows) {
                    if (!r.composite.isDisposed()) r.composite.dispose();
                }
                rows.clear();
                AudioDeviceProfile p = rangeCard();
                boolean show = p != null;
                // Edit only a real, selected LOCAL card: a bench's card is created
                // and calibrated where the device is, and the pencil edits this
                // machine's store.
                if (!editBtn.isDisposed()) editBtn.setEnabled(show && !remoteCards);
                rangesLabel.setVisible(show);
                ((GridData) rangesLabel.getLayoutData()).exclude = !show;
                rangesContainer.setVisible(show);
                ((GridData) rangesContainer.getLayoutData()).exclude = !show;
                if (show) {
                    DeviceEndpointConfig ep = endpointOf(p);
                    for (DeviceRange range : ep.getRanges()) {
                        createRangeRowUi(ep, range);
                    }
                }
            } finally {
                rangesContainer.setRedraw(true);
            }
            // ONE layout pass for the finished table: relayoutTable() re-lays the
            // shell and refreshes the Audio tab's V-scroll min size, so the ranges
            // label + table showing / hiding (exclude toggled) tracks the new
            // content height.
            relayoutTable();
        }

        /**
         * The card whose ranges this direction may show, or null when there are
         * none to show.
         *
         * <p><b>The question is what the CARD carries, not whether the bench is
         * remote.</b>  It used to be the latter, and that single boolean is why a
         * QA403 on a Phonalyser server showed its card and not one attenuator
         * position - while the whole chain that produces them (spec 4.6's
         * {@code qa40x.ranges} + {@code qa40x.calibration}, rendered into a
         * {@code calibrationFromDevice} card by the QA40x settings UI the moment
         * the selection is shown) was already in place and simply thrown away.
         *
         * <p>What the remote branch still refuses is the COLLISION the old guard
         * was really about: a bench card is looked up by the DEVICE it belongs to
         * (the same {@code resolveDeviceProfile} the rate constraint and the
         * active-range commit already use), never by the bench card's NAME, and
         * only a {@code calibrationFromDevice} endpoint is shown - a card the
         * device itself supplied.  An ordinary local card that merely shares a
         * name with one of the server's can therefore never put its own ranges,
         * and its own full scales, under a device calibrated on another machine.
         *
         * <p><b>For a device-calibrated card the WORKING COPY outranks the
         * bench's {@code cards.list} parse.</b>  Both describe the same analyzer,
         * but the {@code cards.list} answer is a detached throwaway: a range
         * radio moving it moves nothing anyone reads, while the analyzer's
         * settings panel commits its range deltas from the working-copy card it
         * rendered at selection time.  Rendering that same object is what makes
         * a radio click and the OK-time comparison see one card.
         */
        private AudioDeviceProfile rangeCard() {
            AudioDeviceProfile p;
            if (remoteCards) {
                String dev = deviceName();
                // The device-provided card FIRST - the one the QA40x settings
                // panel rendered into the working copy from the analyzer's own
                // factors, resolved by the DEVICE it belongs to, never by a bench
                // card's NAME.  It outranks the bench's cards.list copy because
                // the panel's commit compares exactly this object against the
                // bench's in-force positions: the range radios must move the card
                // the OK reads, or a click lands on a throwaway parse, the
                // comparison sees no change, and no range write ever leaves the
                // dialog.
                p = dev == null ? null : edit.resolveDeviceProfile(dev);
                if (p != null && endpointOf(p).isCalibrationFromDevice()) {
                    return endpointOf(p).getRanges().isEmpty() ? null : p;
                }
                // Else the BENCH's own card (spec 4.3 v1.1: cards.list carries
                // each card's content).  It is the card actually in force there,
                // rows and active marker included, so the table shows the truth
                // for an ordinary bench card - and its radios have something real
                // to move (device.setActiveRange).  An ordinary LOCAL card that
                // merely shares a bench card's name is never rendered here.
                BackendKey bench = edit.getSelectedBackend();
                DeviceRef ref = dev == null ? null : refFor(refs(), dev);
                p = bench == null ? null
                        : benchCards.card(bench, benchCards.boundCard(bench, ref, dev));
            } else {
                p = selectedName == null ? null : edit.findAudioDeviceProfile(selectedName);
            }
            return p != null && !endpointOf(p).getRanges().isEmpty() ? p : null;
        }

        /** Builds one range row: active radio(s), editable unique label, and
         *  add / remove icon buttons (row 0's remove is hidden, matching the
         *  FftCalRow convention so columns line up).  In INDEPENDENT mode the row
         *  is TWO mirrored groups sharing one range label - [Left] radio + label
         *  field, [Right] radio + mirrored label field - with the "Left"/"Right"
         *  captions carried by every row but visible only on row 0 (an invisible
         *  widget still reserves its cell, so the columns line up across rows
         *  even though each row is its own Composite).  The full-scale value is
         *  deliberately NOT editable here - the crosshair Calibrate flows own it;
         *  the row only names the range and marks the active one(s). */
        private void createRangeRowUi(DeviceEndpointConfig ep, DeviceRange range) {
            boolean isRow0 = rows.isEmpty();
            boolean independent = ep.getChannels() == DeviceChannelMode.INDEPENDENT;
            // A device-provided endpoint (QA40x) owns its labels + range set: the
            // label fields are read-only and the add / remove buttons are hidden,
            // but the active-range radios stay usable (the ranges are switchable).
            // A BENCH card is the same case for a different reason - its shape is
            // the bench's to edit, in front of the device; from here the operator
            // may only choose which of its rows is in force.
            boolean deviceProvided = ep.isCalibrationFromDevice() || remoteCards;

            // NO_RADIO_GROUP: in INDEPENDENT mode the row holds the Left AND the
            // Right active radio, which are two independent one-of-N columns -
            // SWT would otherwise auto-exclude them against each other, so all
            // selection rules stay in the explicit per-column code below.
            Composite row = new Composite(rangesContainer, SWT.NO_RADIO_GROUP);
            row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            GridLayout rl = new GridLayout(independent ? 8 : 4, false);
            rl.marginWidth = 0; rl.marginHeight = 0; rl.horizontalSpacing = 6;
            row.setLayout(rl);

            if (independent) createSideCaption(row, "scope.tab.left", isRow0);

            Button activeRadio = new Button(row, SWT.RADIO);
            activeRadio.setToolTipText(I18n.t("preferences.audio.range.active.tooltip"));
            activeRadio.setSelection(range.getLabel().equals(ep.getActiveRange()));
            activeRadio.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, false, false));

            Text labelField = new Text(row, SWT.BORDER);
            // The freed FS column now falls to the label - let it grab the slack.
            labelField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            labelField.setText(range.displayLabelOrKey());
            labelField.setToolTipText(I18n.t("preferences.audio.range.label.tooltip"));
            labelField.setEditable(!deviceProvided);

            Button activeRadioRight = null;
            Text labelFieldRight = null;
            if (independent) {
                createSideCaption(row, "scope.tab.right", isRow0);

                activeRadioRight = new Button(row, SWT.RADIO);
                activeRadioRight.setToolTipText(I18n.t("preferences.audio.range.active.tooltip"));
                activeRadioRight.setSelection(range.getLabel().equals(ep.getActiveRangeRight()));
                activeRadioRight.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, false, false));

                labelFieldRight = new Text(row, SWT.BORDER);
                labelFieldRight.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
                labelFieldRight.setText(range.displayLabelOrKey());
                labelFieldRight.setToolTipText(I18n.t("preferences.audio.range.label.tooltip"));
                labelFieldRight.setEditable(!deviceProvided);
            }

            Image plus = IconUtils.icon(row.getDisplay(), Icon.PLUS);
            Button addBtn = new Button(row, SWT.PUSH);
            if (plus != null) addBtn.setImage(plus);
            GridData addGd = new GridData(SWT.CENTER, SWT.CENTER, false, false);
            addGd.heightHint = IconUtils.FILE_BUTTON_HEIGHT;
            addBtn.setLayoutData(addGd);
            addBtn.setToolTipText(I18n.t("preferences.audio.range.add.tooltip"));
            if (deviceProvided) addBtn.setVisible(false);

            Image minus = IconUtils.icon(row.getDisplay(), Icon.MINUS);
            Button removeBtn = new Button(row, SWT.PUSH);
            if (minus != null) removeBtn.setImage(minus);
            GridData remGd = new GridData(SWT.CENTER, SWT.CENTER, false, false);
            remGd.heightHint = IconUtils.FILE_BUTTON_HEIGHT;
            removeBtn.setLayoutData(remGd);
            removeBtn.setToolTipText(I18n.t("preferences.audio.range.remove.tooltip"));
            if (isRow0 || deviceProvided) removeBtn.setVisible(false);

            RangeRow r = new RangeRow();
            r.composite        = row;
            r.activeRadio      = activeRadio;
            r.activeRadioRight = activeRadioRight;
            r.labelField       = labelField;
            r.labelFieldRight  = labelFieldRight;
            r.range            = range;
            rows.add(r);

            // The row is NO_RADIO_GROUP, so a click TOGGLES the radio instead of
            // selecting it - bounce back a click that would leave the column with
            // nothing selected (no model change), and only act on a real pick.
            activeRadio.addListener(SWT.Selection, e -> {
                if (!activeRadio.getSelection()) { activeRadio.setSelection(true); return; }
                userSetActive(ep, r);
            });
            if (activeRadioRight != null) {
                Button rr = activeRadioRight;
                rr.addListener(SWT.Selection, e -> {
                    if (!rr.getSelection()) { rr.setSelection(true); return; }
                    userSetActiveRight(ep, r);
                });
            }
            // A device-provided endpoint owns its labels + range set - only the
            // active-range radios above stay live; rename / add / remove are off.
            if (!deviceProvided) {
                labelField.addListener(SWT.FocusOut,   e -> userRenameRange(ep, r, labelField));
                if (labelFieldRight != null) {
                    Text lfr = labelFieldRight;
                    lfr.addListener(SWT.FocusOut, e -> userRenameRange(ep, r, lfr));
                }
                addBtn.addListener(SWT.Selection,    e -> userAddRange(ep));
                if (!isRow0) removeBtn.addListener(SWT.Selection, e -> userRemoveRange(ep, r));
            }
        }

        /** One "Left"/"Right" group caption cell of an INDEPENDENT range row.
         *  Every row carries both captions so the cell always occupies its
         *  column, but only row 0 shows the text (mock: captions appear once,
         *  above-less, at the start of each group). */
        private void createSideCaption(Composite row, String i18nKey, boolean isRow0) {
            Label caption = new Label(row, SWT.NONE);
            caption.setText(I18n.t(i18nKey));
            caption.setLayoutData(new GridData(SWT.LEAD, SWT.CENTER, false, false));
            caption.setVisible(isRow0);
        }

        private void userSetActive(DeviceEndpointConfig ep, RangeRow r) {
            ep.setActiveRange(r.range.getLabel());
            // Each row is its own NO_RADIO_GROUP Composite, so no radio ever
            // auto-excludes another - this column's exclusivity is by hand.
            for (RangeRow other : rows) {
                if (other != r && !other.activeRadio.isDisposed()) other.activeRadio.setSelection(false);
            }
            stageBenchRange(ep, r.range.getLabel(), Channel.L);
            commit();
        }

        /**
         * A range move on a BENCH card is staged for the bench (spec 4.3
         * {@code device.setActiveRange}), like every other edit in this dialog -
         * the working copy it was just made in is a cache of the bench's card, not
         * this machine's store, so OK is what makes it real and Cancel leaves the
         * bench exactly as it was.
         *
         * <p>A {@code calibrationFromDevice} endpoint is deliberately NOT staged
         * here: a QA40x's attenuator is the DEVICE's own state and spec 4.6 owns
         * it.  That path is committed by the analyzer's own settings panel, which
         * compares the staged position against the one the bench reported in
         * force and sends {@code qa40x.setInputRange} /
         * {@code qa40x.setOutputRange} on this dialog's OK; staging it here as
         * well would move the same attenuator twice.
         */
        private void stageBenchRange(DeviceEndpointConfig ep, String label, Channel side) {
            BackendKey bench = edit.getSelectedBackend();
            String dev = deviceName();
            if (!remoteCards || bench == null || dev == null
                    || ep.isCalibrationFromDevice()) {
                return;
            }
            benchCards.stageActiveRange(bench, input, dev, label,
                    ep.getChannels() == DeviceChannelMode.INDEPENDENT ? side : null);
        }

        /** RIGHT-column counterpart of {@link #userSetActive} (INDEPENDENT mode):
         *  drives {@code activeRangeRight} and clears the sibling right radios.
         *  The LEFT radios are untouched - the two columns are independent. */
        private void userSetActiveRight(DeviceEndpointConfig ep, RangeRow r) {
            ep.setActiveRangeRight(r.range.getLabel());
            for (RangeRow other : rows) {
                if (other != r && other.activeRadioRight != null && !other.activeRadioRight.isDisposed()) {
                    other.activeRadioRight.setSelection(false);
                }
            }
            stageBenchRange(ep, r.range.getLabel(), Channel.R);
            commit();
        }

        /** Commits an edited label into the model, keeping labels unique per
         *  endpoint (a collision gets a numeric suffix) and following the active
         *  selection if this row was active.  {@code edited} is the field the
         *  user typed in; the row's OTHER label field (the INDEPENDENT-mode
         *  mirror) is synced to the committed label. */
        private void userRenameRange(DeviceEndpointConfig ep, RangeRow r, Text edited) {
            String wanted = edited.getText().trim();
            if (wanted.isEmpty()) { syncLabelFields(r, r.range.getLabel()); return; }
            String unique = uniqueLabel(ep, wanted, r.range);
            boolean wasActive      = r.range.getLabel().equals(ep.getActiveRange());
            boolean wasActiveRight = r.range.getLabel().equals(ep.getActiveRangeRight());
            r.range.setLabel(unique);
            syncLabelFields(r, unique);
            if (wasActive)      ep.setActiveRange(unique);
            if (wasActiveRight) ep.setActiveRangeRight(unique);
            commit();
        }

        /** Shows {@code label} in both of the row's label fields (they mirror
         *  one range label; no-op on the missing mirror in LINKED / MONO mode). */
        private void syncLabelFields(RangeRow r, String label) {
            if (!r.labelField.isDisposed() && !r.labelField.getText().equals(label)) {
                r.labelField.setText(label);
            }
            if (r.labelFieldRight != null && !r.labelFieldRight.isDisposed()
                    && !r.labelFieldRight.getText().equals(label)) {
                r.labelFieldRight.setText(label);
            }
        }

        private void userAddRange(DeviceEndpointConfig ep) {
            DeviceRange range = new DeviceRange();
            range.setLabel(uniqueLabel(ep, I18n.t("preferences.audio.range.default"), null));
            double last = ep.getRanges().isEmpty()
                    ? RANGE_FS_MIN_V
                    : ep.getRanges().get(ep.getRanges().size() - 1).getFsLeft();
            range.setFsLeft(last);
            range.setFsRight(last);
            ep.getRanges().add(range);
            createRangeRowUi(ep, range);
            relayoutTable();
            commit();
        }

        private void userRemoveRange(DeviceEndpointConfig ep, RangeRow r) {
            if (rows.size() <= 1) return;
            int idx = rows.indexOf(r);
            if (idx <= 0) return;
            rows.remove(idx);
            ep.getRanges().remove(r.range);
            if (r.range.getLabel().equals(ep.getActiveRange()) && !ep.getRanges().isEmpty()) {
                ep.setActiveRange(ep.getRanges().get(0).getLabel());
            }
            if (r.range.getLabel().equals(ep.getActiveRangeRight()) && !ep.getRanges().isEmpty()) {
                ep.setActiveRangeRight(ep.getRanges().get(0).getLabel());
            }
            r.composite.dispose();
            relayoutTable();
            commit();
            rebuildTable();      // re-sync the active radios to the new active row
        }

        /** Writes the selected card back into the working copy (replace-by-name;
         *  its save() is inert while detached). */
        private void commit() {
            if (selectedName == null) return;
            AudioDeviceProfile p = edit.findAudioDeviceProfile(selectedName);
            if (p != null) edit.putAudioDeviceProfile(p);
        }

        /** Returns {@code wanted} or a suffixed variant unique among the
         *  endpoint's range labels (excluding {@code self}). */
        private String uniqueLabel(DeviceEndpointConfig ep, String wanted, DeviceRange self) {
            String candidate = wanted;
            int n = 2;
            while (labelTaken(ep, candidate, self)) {
                candidate = wanted + " " + n++;
            }
            return candidate;
        }

        private boolean labelTaken(DeviceEndpointConfig ep, String label, DeviceRange self) {
            for (DeviceRange dr : ep.getRanges()) {
                if (dr != self && label.equals(dr.getLabel())) return true;
            }
            return false;
        }

        private void relayoutTable() {
            // The rows changed - lay out the table's own container now (cheap,
            // local), and let the EXPENSIVE tab-level pass (full Audio-tab layout
            // + scroll min-size computeSize) run ONCE per UI turn however many
            // sections rebuilt: a backend switch rebuilds both directions, and
            // paying that pass per section is what a switch used to spend most
            // of its card time on.  A shell-wide layout is not needed at all -
            // the moved content lives entirely inside the scrolled Audio tab.
            if (!rangesContainer.isDisposed()) rangesContainer.layout(true, true);
            scheduleAudioRelayout();
        }
    }

    /**
     * Everything one direction of a card section would put on screen, as one
     * string: the device it shows, the cards its combo would list, the one it
     * would preselect, and that card's range rows exactly as a row renders them -
     * the row key and the displayed label, the active marker(s), the channel mode
     * and whether the device owns the set.
     *
     * <p>The full-scale volts are deliberately NOT in it: no row renders one, so
     * a calibration write must not buy a table rebuild.
     *
     * <p><b>Values in, string out</b> - it reads no widget, no field and no
     * working copy.  That is what lets {@link CardSection#refresh()} ask, BEFORE
     * it tears anything down, whether a rebuild would only reproduce what is
     * already on screen (the teardown + rebuild + relayout being what a backend
     * switch spent its seconds on), and what lets the rule be proved without a
     * display.
     *
     * @param input    which direction's endpoint of each card is rendered
     * @param dev      the device name the section shows, or null when there is none
     * @param listable the cards the combo would list, in combo order
     * @param pick     the card the combo would preselect, or null for none
     */
    String cardRenderIdentity(boolean input, String dev, List<AudioDeviceProfile> listable,
            AudioDeviceProfile pick) {
        String picked = pick == null ? null : pick.getName();
        StringBuilder id = new StringBuilder().append(dev);
        for (AudioDeviceProfile p : listable) {
            id.append('|').append(p.getName());
            // The preselected card is the one whose ranges the table shows, and
            // selectByProfile picks it out of this very list.
            if (p.getName().equals(picked)) {
                appendRangeIdentity(id, endpointOf(p, input));
            }
        }
        return id.append("|=").append(picked).toString();
    }

    /** One endpoint's range table as it is rendered - see
     *  {@link #cardRenderIdentity}. */
    private void appendRangeIdentity(StringBuilder id, DeviceEndpointConfig ep) {
        id.append('[').append(ep.getChannels()).append(ep.isCalibrationFromDevice())
                .append(ep.getActiveRange()).append('/').append(ep.getActiveRangeRight());
        for (DeviceRange range : ep.getRanges()) {
            id.append('/').append(range.getLabel())
                    .append('=').append(range.displayLabelOrKey());
        }
        id.append(']');
    }

    /** One direction's endpoint block of a card - the whole difference between
     *  the input and the output section. */
    private DeviceEndpointConfig endpointOf(AudioDeviceProfile p, boolean input) {
        return input ? p.getInput() : p.getOutput();
    }

    /** Builds the per-card profile section inside a direction Group: a "Card"
     *  combo row (combo + edit-card pencil button) and a range table, both
     *  spanning the group's two columns. */
    private CardSection buildCardSection(Group group, boolean input) {
        gridLabel(group, I18n.t("preferences.audio.card"));
        // The combo shares the field column with a small pencil button that opens
        // the card create / edit dialog on the selected card.
        Composite cardRow = new Composite(group, SWT.NONE);
        cardRow.setLayoutData(comboData());
        GridLayout crl = new GridLayout(2, false);
        crl.marginWidth = 0; crl.marginHeight = 0; crl.horizontalSpacing = 4;
        cardRow.setLayout(crl);
        Combo cardCombo = new Combo(cardRow, SWT.READ_ONLY);
        cardCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        cardCombo.setToolTipText(I18n.t("preferences.audio.card.tooltip"));
        Button editBtn = new Button(cardRow, SWT.PUSH);
        Image pencil = IconUtils.icon(cardRow.getDisplay(), Icon.PENCIL);
        if (pencil != null) editBtn.setImage(pencil);
        GridData editGd = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        editGd.heightHint = cardCombo.computeSize(SWT.DEFAULT, SWT.DEFAULT).y;
        editBtn.setLayoutData(editGd);
        editBtn.setToolTipText(I18n.t("preferences.audio.card.edit.tooltip"));

        Label rangesLabel = new Label(group, SWT.NONE);
        rangesLabel.setText(I18n.t("preferences.audio.ranges"));
        GridData rlgd = new GridData(SWT.LEFT, SWT.CENTER, true, false);
        rlgd.horizontalSpan = 2;
        rangesLabel.setLayoutData(rlgd);

        // The range rows live in a plain container that packs to its actual row
        // count - NO fixed-height viewport and NO multi-row reservation, so
        // there is never reserved empty space under the rows.  When the whole
        // Audio tab overflows 480 px it scrolls in the tab's own V-scroll
        // (see {@link #audioScroll}), so this section needs no scroll of its own.
        Composite rangesContainer = new Composite(group, SWT.NONE);
        GridData sgd = new GridData(SWT.FILL, SWT.TOP, true, false);
        sgd.horizontalSpan = 2;
        rangesContainer.setLayoutData(sgd);
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 4; gl.marginHeight = 4; gl.verticalSpacing = 4;
        rangesContainer.setLayout(gl);

        return new CardSection(cardCombo, editBtn, rangesLabel, rangesContainer, input);
    }

    /** Fingerprint of the working copy's audio configuration the running streams
     *  depend on - backend, the active backend's per-direction device / sample
     *  rate / bit depth, and each direction's active card range.  Snapshotted
     *  when the dialog opens and recomputed on OK: any difference bounces the
     *  running playback / capture. */
    private String audioConfigFingerprint(Preferences prefs) {
        BackendPrefs bp = prefs.current();
        ActiveRange inRangeChange  = activeRange(prefs, bp, true);
        ActiveRange outRangeChange = activeRange(prefs, bp, false);
        return prefs.getSelectedBackend().key() + "|"
                + bp.getInputDeviceName()  + "|" + bp.getInputSampleRate()  + "|" + bp.getInputBitDepth() + "|" + inRangeChange + "|"
                + bp.getOutputDeviceName() + "|" + bp.getOutputSampleRate() + "|" + bp.getOutputBitDepth() + "|" + outRangeChange;
    }

    /** Reads the working copy's current audio selection into an {@link OpenState}
     *  - see that field for when it is taken. */
    private OpenState takeOpenState() {
        BackendPrefs bp = edit.current();
        // The device names as VALUES, not the BackendPrefs object: switching
        // backends switches which object current() returns.
        return new OpenState(audioConfigFingerprint(edit), edit.getSelectedBackend().key(),
                bp.getInputDeviceName(), bp.getOutputDeviceName(),
                activeRange(edit, bp, true), activeRange(edit, bp, false));
    }

    /** The audio selection the dialog opened on - see {@link #openState}. */
    private record OpenState(String audioConfig, String backendKey, String inputDevice,
            String outputDevice, ActiveRange inputRange, ActiveRange outputRange) {
    }

    private static final class DeviceListState {
        List<DeviceRef> inputs  = List.of();
        List<DeviceRef> outputs = List.of();
    }

    /** One device scan's whole answer: what the two combos will list, and the
     *  formats of the device each of them will preselect.  Everything the fill
     *  needs, so the scan can be read where the device I/O belongs - off the
     *  display thread - and the fill touches no driver at all. */
    private record DeviceScan(List<DeviceRef> inputs, List<DeviceRef> outputs,
            List<AudioFormat> inputFormats, List<AudioFormat> outputFormats) {
    }
}
