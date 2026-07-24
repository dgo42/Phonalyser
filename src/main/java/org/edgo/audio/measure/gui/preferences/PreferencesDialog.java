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
import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.enums.DeviceChannelMode;
import org.edgo.audio.measure.enums.PersistenceMode;
import org.edgo.audio.measure.gui.MainWindow;
import org.edgo.audio.measure.gui.bind.Bindings;
import org.edgo.audio.measure.gui.scope.gl.GpuSupport;
import org.edgo.audio.measure.bind.Property;
import org.edgo.audio.measure.preferences.AudioDeviceProfile;
import org.edgo.audio.measure.preferences.BackendPrefs;
import org.edgo.audio.measure.preferences.DeviceEndpointConfig;
import org.edgo.audio.measure.preferences.DeviceRange;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.gui.bus.ActiveRange;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.bus.SampleRateChange;
import org.edgo.audio.measure.gui.common.Dialogs;
import org.edgo.audio.measure.gui.common.Fonts;
import org.edgo.audio.measure.gui.common.Icon;
import org.edgo.audio.measure.gui.common.IconUtils;
import org.edgo.audio.measure.gui.common.ShellIcons;
import org.edgo.audio.measure.gui.registry.UiRegistry;
import org.edgo.audio.measure.gui.widgets.NumericStepField;
import org.edgo.audio.measure.enums.TabOrientation;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.widgets.UnitFamily;
import org.edgo.audio.measure.sound.DeviceRef;

import javax.sound.sampled.AudioFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Modal "Preferences" dialog: lets the user pick the audio backend plus a
 * capture and playback device — each with its own independent sample rate
 * and bit depth.  Input and output fields are visually grouped, and every
 * backend remembers its own selections so switching backends preserves the
 * previously chosen values.
 *
 * <p>The dialog edits a detached working copy obtained from
 * {@link Preferences#copyForDialog()}; no live state is mutated until OK.
 * OK hands the working copy to {@link Preferences#applyFromDialog(Preferences)},
 * which commits every edit (including the chosen backend) and persists.
 * Cancel just closes — nothing live was touched, so there is nothing to
 * restore.
 */
@Log4j2
public final class PreferencesDialog {

    private static final int[] DEFAULT_SAMPLE_RATES = {
            8000, 11025, 16000, 22050, 44100, 48000, 88200, 
            96000, 176400, 192000, 352800, 384000, 705600, 768000
    };
    private static final int[] DEFAULT_BIT_DEPTHS = {16, 24, 32};

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

    /** Scope measurement-average window (s): 0.5…100 in 0.5-s steps. */
    private static final double MEAS_AVG_MIN_SEC  = 0.5;
    private static final double MEAS_AVG_MAX_SEC  = 100;
    private static final double MEAS_AVG_STEP_SEC = 0.5;
    /** Trace line widths (px): 1…5 in 0.5-px steps, one decimal shown. */
    private static final double LINE_WIDTH_MIN_PX  = 1;
    private static final double LINE_WIDTH_MAX_PX  = 5;
    private static final double LINE_WIDTH_STEP_PX = 0.5;
    /** Dot diameters (px): 3…12 in 1-px steps. */
    private static final double DOT_DIAM_MIN_PX = 3;
    private static final double DOT_DIAM_MAX_PX = 12;
    /** Manual persistence time (s): 0.1…60 in 0.5-s steps, one decimal shown. */
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
    /** Total dialog height (px), OUTER — title bar + border included.  The shell
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
    /** Outer V-scroll wrapping the Audio tab's content, so the tallest tab
     *  scrolls instead of growing the dialog past {@link #SHELL_OUTER_HEIGHT_PX}.
     *  Its min size tracks the audio content (refreshed on every card/range
     *  rebuild); the other tabs stay unwrapped. */
    private ScrolledComposite audioScroll;
    private Composite audioTab;

    @Setter
    private MainWindow mainWindow;

    public PreferencesDialog(Shell parent) {
        this.parent = parent;
    }

    public Shell open() { return open(null); }

    /**
     * Opens the dialog and returns its shell.  When {@code onClose} is
     * non-null it is invoked (on the SWT UI thread) after the dialog's shell
     * is disposed — used by the menu listener to restart the oscilloscope
     * capture that was paused while the dialog was up.  The returned shell is
     * a handle for callers that drive the dialog programmatically (e.g. the
     * help-screenshot automation, which closes it when done); interactive
     * callers ignore it.
     */
    public Shell open(Runnable onClose) {
        Shell dialog = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        ShellIcons.apply(dialog);
        if (onClose != null) dialog.addDisposeListener(e -> onClose.run());
        dialog.setText(I18n.t("preferences.title"));
        GridLayout outer = new GridLayout(1, false);
        outer.marginWidth  = 12;
        outer.marginHeight = 12;
        outer.verticalSpacing = 8;
        dialog.setLayout(outer);

        // Detached working copy: EVERY control edits this and nothing live.
        // OK commits it via applyFromDialog(); Cancel just drops it.  The
        // backend currently shown in the combos is edit.getBackend() — the
        // single source of truth, no separate active-backend tracking.
        edit = Preferences.instance().copyForDialog();

        // --- Tab folder: Look & Feel + Audio + Oscilloscope + FFT -----------
        // A fixed content width makes the dialog the same size in every language: the field
        // column is compact (right-aligned), so even the widest translation fits within this,
        // and shorter ones simply leave more room between label and field.
        TabFolder tabs = new TabFolder(dialog, SWT.TOP);
        GridData tabsData = new GridData(SWT.FILL, SWT.FILL, true, true);
        tabsData.widthHint  = DIALOG_WIDTH_PX;
        tabs.setLayoutData(tabsData);

        // --- Look & Feel tab (first) ---------------------------------------
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

        // UI fonts (normal / bold) — applied via a shell recreate on OK,
        // driven by MainWindow's dialog-close compare (like the language
        // switch).  The channel-button font is centralised in Fonts but
        // deliberately not user-editable here.
        gridLabel(lookFeelTab,I18n.t("preferences.lookAndFeel.fontNormal"));
        buildFontRow(lookFeelTab, edit.uiFontNormalProperty(),
                I18n.t("preferences.lookAndFeel.fontNormal.tooltip"));
        gridLabel(lookFeelTab,I18n.t("preferences.lookAndFeel.fontBold"));
        buildFontRow(lookFeelTab, edit.uiFontBoldProperty(),
                I18n.t("preferences.lookAndFeel.fontBold.tooltip"));

        TabItem audioTabItem = new TabItem(tabs, SWT.NONE);
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
        GridLayout backendLayout = new GridLayout(2, false);
        backendLayout.marginWidth = 0;
        backendLayout.marginHeight = 0;
        backendRow.setLayout(backendLayout);
        gridLabel(backendRow,I18n.t("preferences.backend"));
        Combo backendCombo = new Combo(backendRow, SWT.READ_ONLY);
        // Only list backends that work on the current OS — WASAPI / WDM-KS
        // are Windows-only.  The order in the combo is preserved so
        // selectionIndex matches availableBackends below.
        List<AudioBackendType> availableBackends = new ArrayList<>();
        for (AudioBackendType type : AudioBackendType.values()) {
            if (type.isAvailable()) {
                availableBackends.add(type);
                backendCombo.add(type.getDisplayName());
            }
        }
        int selectedIdx = availableBackends.indexOf(edit.getBackend());
        backendCombo.select(selectedIdx >= 0 ? selectedIdx : 0);
        backendCombo.setLayoutData(comboData());

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

        // Measurement averaging window — 0.5 s steps, bound to the working copy
        // (OK commits via applyFromDialog; Cancel drops the copy).
        gridLabel(oscTab,I18n.t("preferences.measAvg"));
        NumericStepField avgSecondsSel = new NumericStepField(oscTab, UnitFamily.SECONDS,
                MEAS_AVG_MIN_SEC, MEAS_AVG_MAX_SEC, MEAS_AVG_STEP_SEC, MEAS_AVG_STEP_SEC, 1, 90);
        avgSecondsSel.setLayoutData(comboData());
        avgSecondsSel.setToolTipText(I18n.t("preferences.measAvg.tooltip"));
        Bindings.stepField(avgSecondsSel, edit.oscMeasurementAverageSecondsProperty());

        // Trace line width — 0.5 px increments from 1.0 to 5.0.
        gridLabel(oscTab,I18n.t("preferences.lineWidth"));
        NumericStepField lineWidthSel = new NumericStepField(oscTab, UnitFamily.PIXEL,
                LINE_WIDTH_MIN_PX, LINE_WIDTH_MAX_PX, LINE_WIDTH_STEP_PX, LINE_WIDTH_STEP_PX, 1, 90);
        lineWidthSel.setLayoutData(comboData());
        lineWidthSel.setToolTipText(I18n.t("preferences.lineWidth.tooltip"));
        Bindings.stepField(lineWidthSel, edit.oscLineWidthProperty());

        // Sample-dot diameter — 1 px increments from 3 to 12.
        gridLabel(oscTab,I18n.t("preferences.dotDiameter"));
        NumericStepField dotDiameterSel = new NumericStepField(oscTab, UnitFamily.PIXEL,
                DOT_DIAM_MIN_PX, DOT_DIAM_MAX_PX, 1, 1, 0, 90);
        dotDiameterSel.setLayoutData(comboData());
        dotDiameterSel.setToolTipText(I18n.t("preferences.dotDiameter.tooltip"));
        Bindings.stepFieldInt(dotDiameterSel, edit.oscDotDiameterProperty());

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

        // Per-channel trace colour — button background reflects the picked
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

        // FFT trace line width — same 1.0..5.0 / 0.5 step grid as the scope.
        gridLabel(fftTab,I18n.t("preferences.fft.lineWidth"));
        NumericStepField fftLineWidthSel = new NumericStepField(fftTab, UnitFamily.PIXEL,
                LINE_WIDTH_MIN_PX, LINE_WIDTH_MAX_PX, LINE_WIDTH_STEP_PX, LINE_WIDTH_STEP_PX, 1, 90);
        fftLineWidthSel.setLayoutData(comboData());
        fftLineWidthSel.setToolTipText(I18n.t("preferences.fft.lineWidth.tooltip"));
        Bindings.stepField(fftLineWidthSel, edit.fftLineWidthProperty());

        // Harmonic dot diameter — 1 px increments, same range as scope sample dot.
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

        // Before-calibration dot colour — painted next to the
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

        // Calibration overlay colour — the mirrored cascade of all loaded
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

        // FreqResp trace line width — same 1.0..5.0 / 0.5 step grid as the
        // scope and FFT traces.
        gridLabel(freqRespTab,I18n.t("preferences.freqResp.lineWidth"));
        NumericStepField freqRespLineWidthSel = new NumericStepField(freqRespTab, UnitFamily.PIXEL,
                LINE_WIDTH_MIN_PX, LINE_WIDTH_MAX_PX, LINE_WIDTH_STEP_PX, LINE_WIDTH_STEP_PX, 1, 90);
        freqRespLineWidthSel.setLayoutData(comboData());
        freqRespLineWidthSel.setToolTipText(I18n.t("preferences.freqResp.lineWidth.tooltip"));
        Bindings.stepField(freqRespLineWidthSel, edit.freqRespLineWidthProperty());

        // Maximal analyzed frequency as % of Nyquist.  Pref value is a
        // fraction in [0.83, 1.00]; the field displays it as a percent.
        // 100 % = strict Nyquist; the user usually wants 83–99 % to avoid
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
        // percent — it reads the live input sample rate (display-only) and the
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
        // frequency (50 / 60 Hz mains) before drawing — removes the
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
        // automation script can select a tab and snapshot the dialog by path —
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
        // its two rate combos kept equal.  The coupling is a MessageBus round-trip:
        // each rate combo announces its pick with PREFS_SAMPLE_RATE_CHANGED (wired
        // below and re-emitted at the end of refreshDevices), the owning subscriber
        // compares the pair and answers with PREFS_SAMPLE_RATE_SET, and here we align
        // the OTHER combo to it.  A programmatic Combo.select fires no SWT.Selection,
        // so this correction never re-announces — the round-trip ends.  Subscribed
        // BEFORE the first refreshDevices() so an entry sync lands, and dropped on
        // dispose so the bus retains no widgets.
        MessageBus rateBus = MessageBus.instance();
        Consumer<SampleRateChange> rateSetListener = set -> {
            if (set == null || dialog.isDisposed()) return;
            if (edit.getBackend() != set.backend()) return;
            selectRateItem(set.input() ? inputRateCombo : outputRateCombo, set.sampleRateHz());
        };
        rateBus.subscribe(Events.PREFS_SAMPLE_RATE_SET, rateSetListener);
        dialog.addDisposeListener(e -> rateBus.unsubscribe(Events.PREFS_SAMPLE_RATE_SET, rateSetListener));

        refreshDevices();
        // The Audio tab is now fully built and populated — set the V-scroll's
        // min size from its content so the scrollbar appears under the height
        // cap.  (Subsequent card/range rebuilds refresh it again.)
        refreshAudioScrollMinSize();
        backendCombo.addListener(SWT.Selection, e -> {
            // Persist the outgoing backend's UI state into the working copy
            // before switching, so toggling back later restores what the user
            // just chose.  capture() targets edit.current() (the OLD backend),
            // then setBackend() makes current() the NEW backend.
            captureUiToActive();
            edit.setBackend(availableBackends.get(backendCombo.getSelectionIndex()));
            refreshDevices();
        });
        // A device pick must land in the working copy BEFORE the card section
        // re-resolves — CardSection.refresh() reads the device name off `edit`,
        // so without this capture it would re-resolve against the OLD device and
        // keep the previous card selected.
        inputCombo.addListener (SWT.Selection, e -> { refreshInputRatesAndDepths();  captureUiToActive(); inputCard.onDeviceChanged();  });
        outputCombo.addListener(SWT.Selection, e -> { refreshOutputRatesAndDepths(); captureUiToActive(); outputCard.onDeviceChanged(); });
        // Announce each rate pick on the bus so a rate-constraint subscriber can
        // mirror it onto the other direction (see the PREFS_SAMPLE_RATE_SET
        // subscription above).  Unguarded by backend — a backend with no such
        // subscriber simply gets no answer back.
        inputRateCombo.addListener (SWT.Selection, e -> publishRateChange(true,  inputRateCombo));
        outputRateCombo.addListener(SWT.Selection, e -> publishRateChange(false, outputRateCombo));

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
        // must bounce (live streams) or rebuild (panes).
        String audioBefore = audioConfigFingerprint(edit);
        String fontsBefore = edit.getUiFontNormal() + "/" + edit.getUiFontBold();
        BackendPrefs backend = edit.current();
        ActiveRange inRangeBefore  = activeRange(edit, backend, true);
        ActiveRange outRangeBefore = activeRange(edit, backend, false);

        boolean gpuBefore  = edit.isUseGpuAcceleration();

        okButton.addListener(SWT.Selection, e -> {

            // Fold every control's value into the working copy `edit`.
            // --- Per-backend device / rate / depth for the shown backend.
            captureUiToActive();

            String fontsAfter = edit.getUiFontNormal() + "/" + edit.getUiFontBold();
            // A committed audio-config change bounces the live streams: STOP them
            // now — on the current panes and the OLD backend, before the commit
            // and the pane rebuild below — and restart after setActive (guarded by
            // needStartAudio near the end of this handler).
            boolean needStartAudio = false;
            if (!audioConfigFingerprint(edit).equals(audioBefore)) {
                mainWindow.beforeApplyBackendChanges();
                needStartAudio = true;
            }
            // The GPU toggle, like fonts, takes effect by rebuilding the panes — the
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
            // --- Nyquist percent → fraction + freq-window clamp, into edit.
            applyNyquistToEdit();
            // (orientation, small icons, strong-tone rel-dB, compare-smoothing
            //  window, notch enable + base Hz already live on `edit` via their
            //  two-way binds.)
            BackendPrefs bp = edit.current();
            // Working-copy active range, one per direction (null when the selected
            // device has no card profile).  Recomputed here BEFORE the commit and,
            // for each direction that differs from the open-time snapshot
            // (inRangeBefore / outRangeBefore), published AFTER the commit so
            // subscribers read committed state — ranges reach the device only on OK
            // (maintainer order).
            ActiveRange inRangeAfter  = activeRange(edit, bp, true);
            ActiveRange outRangeAfter = activeRange(edit, bp, false);
            log.info("Preferences saved: backend={}, in={} @ {} Hz / {} bits, out={} @ {} Hz / {} bits",
                    edit.getBackend(),
                    bp.getInputDeviceName()  != null ? bp.getInputDeviceName()  : "<none>",
                    bp.getInputSampleRate(),  bp.getInputBitDepth(),
                    bp.getOutputDeviceName() != null ? bp.getOutputDeviceName() : "<none>",
                    bp.getOutputSampleRate(), bp.getOutputBitDepth());
            // Single hand-off: commit the whole working copy to the live
            // singleton (which also persists once).
            Preferences.instance().applyFromDialog(edit);
            // Resolve per-card FS for the just-committed selection so the dBV
            // axis and generator scale update immediately on OK (legacy scalars
            // when the device is unbound).
            Preferences committed = Preferences.instance();
            BackendPrefs committedBp = committed.current();
            committed.applyInputDeviceProfile(committedBp.getInputDeviceName());
            committed.applyOutputDeviceProfile(committedBp.getOutputDeviceName());
            // Activate the chosen backend on the live AudioBackend — kept in the
            // dialog so Preferences stays free of the sound/hardware layer.
            AudioBackend.instance().setActive(edit.getBackend());
            // Fire the FreqResp refresh events once so the pane / view re-sync
            // to the committed state (range / scrollbars, smoothing table,
            // notch + colours).
            MessageBus bus = MessageBus.instance();
            bus.publish(Events.FREQRESP_RANGE_CHANGED);
            bus.publish(Events.FREQRESP_COMPARE_PARAMS_CHANGED);
            bus.publish(Events.FREQRESP_CALIBRATION_CHANGED);
            // Backend / device / rate edits move the Nyquist-derived field
            // bounds — let the panes re-pull them from the committed prefs.
            bus.publish(Events.AUDIO_FORMAT_CHANGED);
            if (inRangeBefore != null && inRangeAfter != null && !inRangeBefore.equals(inRangeAfter)) bus.publish(Events.DEVICE_ACTIVE_RANGE_CHANGED, inRangeAfter);
            if (outRangeBefore != null && outRangeAfter != null && !outRangeBefore.equals(outRangeAfter)) bus.publish(Events.DEVICE_ACTIVE_RANGE_CHANGED, outRangeAfter);

            if (needStartAudio) {
                mainWindow.afterApplyBackendChanges();
            }
            dialog.close();
        });

        cancelButton.addListener(SWT.Selection, e -> {
            // The dialog edited a detached working copy — nothing live was
            // touched, so there is nothing to roll back.
            dialog.close();
        });

        // pack() sizes the shell to its content width (driven by the tab
        // folder's 700 px width hint); force the OUTER height to the fixed
        // 480 px so the window is the same compact height in every language,
        // authoritative over whatever the tallest tab's content would ask for.
        dialog.pack();
        dialog.setSize(dialog.getSize().x, SHELL_OUTER_HEIGHT_PX);
        Dialogs.centerOnParent(dialog);
        dialog.open();
        return dialog;
    }

    /**
     * The working copy's active-range label for one direction, wrapped as an
     * {@link ActiveRange}, or {@code null} when the direction's selected
     * device has no card profile.  Used both to publish the range after the OK
     * commit and — with the other direction — as part of {@link
     * #audioConfigFingerprint}.  The payload is generic on purpose: direction +
     * label only, no card identity — the subscriber consults committed state
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

    // The percent → fraction conversion + freq-window clamp run on OK,
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
        DeviceRef dev = pickedDevice(inputCombo, devices.inputs);
        BackendPrefs bp = edit.current();
        TreeSet<Integer> rates  = (dev != null)
                ? ratesOf(AudioBackend.instance().listSupportedInputFormats(edit.getBackend(), dev))
                : null;
        TreeSet<Integer> depths = (dev != null)
                ? depthsOf(AudioBackend.instance().listSupportedInputFormats(edit.getBackend(), dev))
                : null;
        populateIntCombo(inputRateCombo,  fallback(rates,  DEFAULT_SAMPLE_RATES), " Hz",   bp.getInputSampleRate());
        populateIntCombo(inputDepthCombo, fallback(depths, DEFAULT_BIT_DEPTHS),   " bits", bp.getInputBitDepth());
    }

    // Mirror of {@code refreshInputRatesAndDepths} for the output side.
    private void refreshOutputRatesAndDepths() {
        DeviceRef dev = pickedDevice(outputCombo, devices.outputs);
        BackendPrefs bp = edit.current();
        TreeSet<Integer> rates  = (dev != null)
                ? ratesOf(AudioBackend.instance().listSupportedOutputFormats(edit.getBackend(), dev))
                : null;
        TreeSet<Integer> depths = (dev != null)
                ? depthsOf(AudioBackend.instance().listSupportedOutputFormats(edit.getBackend(), dev))
                : null;
        populateIntCombo(outputRateCombo,  fallback(rates,  DEFAULT_SAMPLE_RATES), " Hz",   bp.getOutputSampleRate());
        populateIntCombo(outputDepthCombo, fallback(depths, DEFAULT_BIT_DEPTHS),   " bits", bp.getOutputBitDepth());
    }

    // Captures the current UI state into the prefs of {@code active[0]} so
    // a backend switch (or OK) doesn't lose the user's edits.  Devices
    // are stored by name (string) — the saved name is matched back to a
    // live DeviceRef on dialog open via populateDeviceCombo.
    private void captureUiToActive() {
        BackendPrefs bp = edit.current();
        bp.setInputDeviceName (nameOf(pickedDevice(inputCombo,  devices.inputs)));
        bp.setOutputDeviceName(nameOf(pickedDevice(outputCombo, devices.outputs)));
        int idx;
        if ((idx = inputRateCombo.getSelectionIndex())  >= 0) bp.setInputSampleRate (parseLeadingInt(inputRateCombo.getItem(idx)));
        if ((idx = inputDepthCombo.getSelectionIndex()) >= 0) bp.setInputBitDepth   (parseLeadingInt(inputDepthCombo.getItem(idx)));
        if ((idx = outputRateCombo.getSelectionIndex()) >= 0) bp.setOutputSampleRate(parseLeadingInt(outputRateCombo.getItem(idx)));
        if ((idx = outputDepthCombo.getSelectionIndex())>= 0) bp.setOutputBitDepth  (parseLeadingInt(outputDepthCombo.getItem(idx)));
    }

    private void refreshDevices() {
        // Enumerate the chosen backend's hardware WITHOUT activating it —
        // the type-parameterised overloads resolve the manager by
        // edit.getBackend() and never touch the live `active` field.
        AudioBackendType type = edit.getBackend();
        devices.inputs  = AudioBackend.instance().listInputDevices(type);
        devices.outputs = AudioBackend.instance().listOutputDevices(type);
        BackendPrefs bp = edit.current();
        populateDeviceCombo(inputCombo,  devices.inputs,  bp.getInputDeviceName());
        populateDeviceCombo(outputCombo, devices.outputs, bp.getOutputDeviceName());
        refreshInputRatesAndDepths();
        refreshOutputRatesAndDepths();
        // Announce the input rate after a (re)populate so a rate-constraint
        // subscriber can mirror it onto the output combo — a backend switch (or
        // dialog open) lands already-coupled.  Sent UNCONDITIONALLY (total
        // decoupling): the dialog holds no device knowledge; the payload carries
        // the edited backend and a subscriber that doesn't constrain it simply
        // ignores the event.  The PREFS_SAMPLE_RATE_SET subscription in open()
        // applies any answer.
        publishRateChange(true, inputRateCombo);
        // Repopulate the card combo + range table for the (possibly new)
        // backend / device — the sections are built before refreshDevices()
        // first runs, so they are already present here.
        if (inputCard  != null) inputCard.refresh();
        if (outputCard != null) outputCard.refresh();
    }

    /** Announces one direction's chosen sample rate on the bus so a rate-constraint
     *  subscriber (today {@code Qa40xRateConstraint}) can mirror it onto the other
     *  direction.  Device-agnostic — it carries the edited backend AND the resolved
     *  card name so a subscriber can key off whichever it constrains; a no-op when
     *  the combo has no selection. */
    private void publishRateChange(boolean input, Combo rateCombo) {
        int idx = rateCombo.getSelectionIndex();
        if (idx < 0) return;
        MessageBus.instance().publish(Events.PREFS_SAMPLE_RATE_CHANGED,
                new SampleRateChange(input, parseLeadingInt(rateCombo.getItem(idx)),
                        edit.getBackend(), cardNameFor(input)));
    }

    /** The resolved card name for a direction's selected device, or {@code null}
     *  when the device maps to no card — carried on the rate-change payload so a
     *  card-scoped constraint can match it. */
    private String cardNameFor(boolean input) {
        BackendPrefs bp = edit.current();
        String dev = input ? bp.getInputDeviceName() : bp.getOutputDeviceName();
        if (dev == null) return null;
        AudioDeviceProfile card = edit.resolveDeviceProfile(dev);
        return card == null ? null : card.getName();
    }

    /** Programmatically selects {@code combo}'s item whose leading integer equals
     *  {@code hz} (a no-op when that rate isn't offered).  {@code Combo.select}
     *  fires no SWT.Selection, so the rate coupling that calls this never recurses. */
    private void selectRateItem(Combo combo, int hz) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (parseLeadingInt(combo.getItem(i)) == hz) {
                combo.select(i);
                return;
            }
        }
    }

    /** Re-derives the Audio tab's V-scroll min size from its content's
     *  preferred size, so the scrollbar appears exactly when the content
     *  (which grows/shrinks as a card is selected → its range table shows /
     *  hides) exceeds the tab viewport at the fixed
     *  {@link #SHELL_OUTER_HEIGHT_PX} window height.  Called after the initial
     *  device refresh and from every card/range rebuild. */
    private void refreshAudioScrollMinSize() {
        if (audioScroll == null || audioScroll.isDisposed() || audioTab == null || audioTab.isDisposed()) return;
        audioTab.layout(true, true);
        audioScroll.setMinSize(audioTab.computeSize(SWT.DEFAULT, SWT.DEFAULT));
    }

    /** Layout for a value field (combo / numeric / colour / font row): a fixed width so every
     *  field on every tab is the same size, FILL so the control occupies it exactly, no grab.
     *  The label column grabs the slack (see {@link #gridLabel}), so the fixed-width field
     *  column is pushed to the dialog's right edge — all fields end up right-aligned. */
    private GridData comboData() {
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, false, false);
        gd.widthHint = FIELD_WIDTH_PX;
        return gd;
    }

    /** A left-column label whose cell grabs the horizontal slack, pushing the fixed-width
     *  value column to the dialog's right edge. */
    private void gridLabel(Composite parent, String text) {
        Label l = new Label(parent, SWT.NONE);
        l.setText(text);
        l.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, true, false));
    }

    /** One settings row laid out like the field rows: a left-column label (grabbing the slack)
     *  and a right-aligned bare checkbox in the value column (instead of a checkbox-with-text
     *  floating in column 2).  Returns the checkbox so the caller can bind / enable it. */
    private Button buildCheckRow(Composite parent, String labelKey, String tooltipKey) {
        Label label = new Label(parent, SWT.NONE);
        label.setText(I18n.t(labelKey));
        label.setToolTipText(I18n.t(tooltipKey));
        label.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, true, false));
        Button box = new Button(parent, SWT.CHECK);
        box.setToolTipText(I18n.t(tooltipKey));
        box.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        return box;
    }

    /** One Look&Feel font row: a read-only text showing the current spec
     *  ("Consolas 9 bold") plus a … button opening the system FontDialog
     *  seeded with it.  A picked font is written straight to the dialog's
     *  detached {@code edit} property — OK commits, Cancel drops it. */
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
        browse.setText("…");
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
     *  Falls back to {@code "(— Hz)"} when no input device is picked
     *  yet (sampleRate ≤ 0). */
    private String formatMaxFreqLabel(double nyqFraction, int sampleRate) {
        String base = I18n.t("preferences.freqResp.maxFreqPctNyquist");
        String hz;
        if (sampleRate <= 0) {
            hz = "— Hz";
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
        // leak — the dispose listener fires once, after the last setBackground.
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
            combo.add(d.displayName());
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

    private TreeSet<Integer> fallback(TreeSet<Integer> probed, int[] defaults) {
        if (probed != null && !probed.isEmpty()) return probed;
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
     *  The value is never edited in this dialog — the crosshair Calibrate flows
     *  own the real full-scale voltage. */
    private static final double RANGE_FS_MIN_V = 1e-9;

    /** One editable range-table row: the label text + backing model.  The
     *  full-scale value is NOT edited here — it is produced solely by the
     *  crosshair Calibrate flows and persists on {@link DeviceRange}. */
    private static final class RangeRow {
        Composite        composite;
        /** The LEFT-channel "active range" radio (drives {@code activeRange}).  In
         *  LINKED mode this is the sole radio.  Each row sits in its own Composite,
         *  so the radios do NOT auto-exclude — {@link CardSection#userSetActive}
         *  clears the siblings by hand. */
        Button           activeRadio;
        /** The RIGHT-channel active-range radio (drives {@code activeRangeRight}),
         *  present only in INDEPENDENT mode; {@code null} in LINKED mode. */
        Button           activeRadioRight;
        Text             labelField;
        /** INDEPENDENT-mode mirror of {@link #labelField} in the Right group —
         *  both show/edit the SAME range label (synced on rename); {@code null}
         *  in LINKED / MONO mode. */
        Text             labelFieldRight;
        /** Source of truth for label + FS — an element of the endpoint's range list. */
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
        /** Combo index → profile; {@code null} only at the last index (New card…).
         *  Rebuilt with the combo in {@link #refresh()}. */
        private final List<AudioDeviceProfile> comboProfiles = new ArrayList<>();
        /** Logical name of the card the combo currently shows, or null when no
         *  card is selected — the value a cancelled "New card…" reverts to. */
        private String selectedName;
        /** Device name the "no card assigned" prompt was last shown for, so a
         *  passive refresh / rebuild never re-asks for the same device within
         *  this dialog session. */
        private String lastPromptedDevice;

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
            return input ? p.getInput() : p.getOutput();
        }

        /** Re-derives the combo, its preselection, and the range table from the
         *  working copy + the selected device.  The combo lists only profiles whose
         *  endpoint for THIS direction has at least one range (the seeded known cards
         *  included) — an output-only card never clutters the input combo and vice
         *  versa.  The preselection is the unified lookup
         *  ({@link Preferences#resolveDeviceProfile}): the card whose {@code match}
         *  list has the longest entry that is a substring of the device name, or no
         *  selection (empty combo, no range table) when none matches.  A device with
         *  no correlated card therefore never keeps showing the previous card — the
         *  combo VISIBLY clears; the offer-to-create prompt lives in
         *  {@link #onDeviceChanged}, not here, so a passive refresh / rebuild never
         *  pops a dialog.  A matched card is pre-selected and its ranges show, but its
         *  device name is bound onto {@code match} only when the user confirms it
         *  (selecting the combo entry → {@link #onCardSelected} → {@link #bindAlias});
         *  the user sees it and can change it first. */
        private AudioDeviceProfile refresh() {
            if (cardCombo.isDisposed()) return null;
            String dev = deviceName();

            cardCombo.removeAll();
            comboProfiles.clear();
            for (AudioDeviceProfile p : edit.getAudioDeviceProfiles()) {
                if (endpointOf(p).getRanges().isEmpty()) continue;   // no range this direction — hide
                cardCombo.add(p.getName());
                comboProfiles.add(p);
            }

            AudioDeviceProfile pick = dev != null ? edit.resolveDeviceProfile(dev) : null;
            cardCombo.add(I18n.t("preferences.audio.card.new"));
            comboProfiles.add(null);                                  // last = New card…

            // A resolved / suggested card is selected; anything else (unknown
            // device, or no device) leaves the combo empty with no range table.
            selectByProfile(pick);
            rebuildTable();
            return pick;
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
            if (dev == null || pick != null) return;       // unbound, or a card correlated
            if (dev.equals(lastPromptedDevice)) return;    // already offered for this device
            lastPromptedDevice = dev;
            int answer = Dialogs.confirm(parent,
                    I18n.t("preferences.audio.card.noCardAssigned.title"),
                    I18n.t("preferences.audio.card.noCardAssigned.message", dev));
            if (answer == SWT.YES) createNewCard(dev);
            // NO — leave the section unbound (refresh() already cleared it).
        }

        /** Selects the combo entry for {@code p} without firing the
         *  user-selection handler ({@code combo.select} is silent).  A null
         *  profile — or one absent from the combo — leaves the combo with no
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

        /** The card combo's user-selection handler — the ask-flow. */
        private void onCardSelected() {
            int idx = cardCombo.getSelectionIndex();
            if (idx < 0) return;
            String dev = deviceName();
            if (idx == comboProfiles.size() - 1) {          // New card…
                createNewCard(dev);
                return;
            }
            AudioDeviceProfile p = comboProfiles.get(idx);   // always an existing card
            bindAlias(p, dev);
            selectedName = p.getName();
            rebuildTable();
        }

        /** "New card…" — open the card create dialog prefilled with the
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
                    currentCardNames(), null, inputSeedFs(), outputSeedFs());
            AudioDeviceProfile p = dlg.open();
            if (p == null) {
                refresh();                                 // cancelled — restore the pre-dialog state
                return;
            }
            edit.putAudioDeviceProfile(p);
            String name = p.getName();
            // Rebuild the combo to list the new card, then select it explicitly
            // (a match-based resolve would also find it when its list carries the
            //  device name, but a device-less create has nothing to resolve by —
            //  pin the selection either way).
            refresh();
            selectByProfile(edit.findAudioDeviceProfile(name));
            rebuildTable();
        }

        /** Opens the card edit dialog on the currently selected card and replaces
         *  it in the working copy on OK, re-keying cleanly on a rename (remove the
         *  old name, put under the new). */
        /** Builds (but does not open) the card editor on the currently selected
         *  card, or null when nothing is selected — shared by the interactive
         *  {@link #editSelectedCard()} and the help-capture hook
         *  {@link PreferencesDialog#openInputCardEditorForCapture()}. */
        private CardEditorDialog editorForSelected() {
            if (selectedName == null) return null;
            AudioDeviceProfile live = edit.findAudioDeviceProfile(selectedName);
            if (live == null) return null;
            return new CardEditorDialog(parent, live,
                    CardEditorDialog.Capability.of(live),
                    currentCardNames(), live.getName(), inputSeedFs(), outputSeedFs());
        }

        private void editSelectedCard() {
            CardEditorDialog dlg = editorForSelected();
            if (dlg == null) return;
            AudioDeviceProfile p = dlg.open();
            if (p == null) return;                         // cancelled — nothing changed
            if (!p.getName().equalsIgnoreCase(selectedName)) {
                edit.removeAudioDeviceProfile(selectedName);   // rename — drop the old key
            }
            edit.putAudioDeviceProfile(p);
            refresh();
            selectByProfile(edit.findAudioDeviceProfile(p.getName()));
            rebuildTable();
        }

        /** The logical names of every card currently in the working copy — the
         *  card-editor's name-uniqueness check. */
        private List<String> currentCardNames() {
            List<String> names = new ArrayList<>();
            for (AudioDeviceProfile p : edit.getAudioDeviceProfiles()) names.add(p.getName());
            return names;
        }

        /** Full-scale (V RMS) a freshly enabled input range is seeded with. */
        private double inputSeedFs() {
            return edit.getAdcFsVoltageRms();
        }

        /** Full-scale (V RMS) a freshly enabled output range is seeded with — the
         *  DAC's global amplitude scalar converted to RMS (the on-disk convention). */
        private double outputSeedFs() {
            return edit.getDacFsVoltageAmpl() / Constants.SQRT2;
        }

        /** Binds card {@code p} to the current device name by APPENDING it to the
         *  card's {@code match} list — only when no existing entry already matches
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

        /** Rebuilds the range table from the selected card's endpoint (hidden
         *  when no card is selected). */
        private void rebuildTable() {
            for (RangeRow r : rows) {
                if (!r.composite.isDisposed()) r.composite.dispose();
            }
            rows.clear();
            AudioDeviceProfile p = selectedName != null ? edit.findAudioDeviceProfile(selectedName) : null;
            boolean show = p != null;
            if (!editBtn.isDisposed()) editBtn.setEnabled(show);   // edit only a real, selected card
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
            // relayoutTable() re-lays the shell and refreshes the Audio tab's
            // V-scroll min size, so the ranges label + table showing / hiding
            // (exclude toggled) tracks the new content height.
            relayoutTable();
        }

        /** Builds one range row: active radio(s), editable unique label, and
         *  add / remove icon buttons (row 0's remove is hidden, matching the
         *  FftCalRow convention so columns line up).  In INDEPENDENT mode the row
         *  is TWO mirrored groups sharing one range label — [Left] radio + label
         *  field, [Right] radio + mirrored label field — with the "Left"/"Right"
         *  captions carried by every row but visible only on row 0 (an invisible
         *  widget still reserves its cell, so the columns line up across rows
         *  even though each row is its own Composite).  The full-scale value is
         *  deliberately NOT editable here — the crosshair Calibrate flows own it;
         *  the row only names the range and marks the active one(s). */
        private void createRangeRowUi(DeviceEndpointConfig ep, DeviceRange range) {
            boolean isRow0 = rows.isEmpty();
            boolean independent = ep.getChannels() == DeviceChannelMode.INDEPENDENT;
            // A device-provided endpoint (QA40x) owns its labels + range set: the
            // label fields are read-only and the add / remove buttons are hidden,
            // but the active-range radios stay usable (the ranges are switchable).
            boolean deviceProvided = ep.isCalibrationFromDevice();

            // NO_RADIO_GROUP: in INDEPENDENT mode the row holds the Left AND the
            // Right active radio, which are two independent one-of-N columns —
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
            // The freed FS column now falls to the label — let it grab the slack.
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
            // selecting it — bounce back a click that would leave the column with
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
            // A device-provided endpoint owns its labels + range set — only the
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
            // auto-excludes another — this column's exclusivity is by hand.
            for (RangeRow other : rows) {
                if (other != r && !other.activeRadio.isDisposed()) other.activeRadio.setSelection(false);
            }
            commit();
        }

        /** RIGHT-column counterpart of {@link #userSetActive} (INDEPENDENT mode):
         *  drives {@code activeRangeRight} and clears the sibling right radios.
         *  The LEFT radios are untouched — the two columns are independent. */
        private void userSetActiveRight(DeviceEndpointConfig ep, RangeRow r) {
            ep.setActiveRangeRight(r.range.getLabel());
            for (RangeRow other : rows) {
                if (other != r && other.activeRadioRight != null && !other.activeRadioRight.isDisposed()) {
                    other.activeRadioRight.setSelection(false);
                }
            }
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
            if (!rangesContainer.isDisposed()) rangesContainer.layout(true, true);
            // A row was added / removed → the Audio tab's preferred height moved;
            // re-lay the shell and refresh the tab's V-scroll min size so its
            // on-demand scrollbar tracks the new content.
            if (!rangesContainer.isDisposed()) rangesContainer.getShell().layout(true, true);
            refreshAudioScrollMinSize();
        }
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
        // count — NO fixed-height viewport and NO multi-row reservation, so
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
     *  depend on — backend, the active backend's per-direction device / sample
     *  rate / bit depth, and each direction's active card range.  Snapshotted
     *  when the dialog opens and recomputed on OK: any difference bounces the
     *  running playback / capture. */
    private String audioConfigFingerprint(Preferences prefs) {
        BackendPrefs bp = prefs.current();
        ActiveRange inRangeChange  = activeRange(prefs, bp, true);
        ActiveRange outRangeChange = activeRange(prefs, bp, false);
        return prefs.getBackend() + "|"
                + bp.getInputDeviceName()  + "|" + bp.getInputSampleRate()  + "|" + bp.getInputBitDepth() + "|" + inRangeChange + "|"
                + bp.getOutputDeviceName() + "|" + bp.getOutputSampleRate() + "|" + bp.getOutputBitDepth() + "|" + outRangeChange;
    }

    private static final class DeviceListState {
        List<DeviceRef> inputs  = List.of();
        List<DeviceRef> outputs = List.of();
    }
}
