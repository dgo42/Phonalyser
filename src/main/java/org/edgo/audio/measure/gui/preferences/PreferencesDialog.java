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

import lombok.extern.log4j.Log4j2;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
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

import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.gui.bind.Bindings;
import org.edgo.audio.measure.bind.Property;
import org.edgo.audio.measure.preferences.BackendPrefs;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.Dialogs;
import org.edgo.audio.measure.gui.generator.NumericStepField;
import org.edgo.audio.measure.enums.TabOrientation;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.widgets.StepSelector;
import org.edgo.audio.measure.sound.DeviceRef;

import javax.sound.sampled.AudioFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

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
            44100, 48000, 88200, 96000, 176400, 192000,
            352800, 384000, 705600, 768000
    };
    private static final int[] DEFAULT_BIT_DEPTHS = {16, 24, 32};

    /** Lower bound (in % of Nyquist) of the FreqResp "Maximal analyzed
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

    private final Shell parent;

    public PreferencesDialog(Shell parent) {
        this.parent = parent;
    }

    public void open() { open(null); }

    /**
     * Opens the dialog.  When {@code onClose} is non-null it is invoked
     * (on the SWT UI thread) after the dialog's shell is disposed — used
     * by the menu listener to restart the oscilloscope capture that was
     * paused while the dialog was up.
     */
    public void open(Runnable onClose) {
        Shell dialog = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
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
        Preferences edit = Preferences.instance().copyForDialog();

        // --- Tab folder: Look & Feel + Audio + Oscilloscope + FFT -----------
        TabFolder tabs = new TabFolder(dialog, SWT.TOP);
        tabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

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

        new Label(lookFeelTab, SWT.NONE).setText(I18n.t("preferences.lookAndFeel.tabOrientation"));
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

        // Spacer label so the checkbox lines up under the combo (column 2).
        new Label(lookFeelTab, SWT.NONE);
        Button smallIconsBtn = new Button(lookFeelTab, SWT.CHECK);
        smallIconsBtn.setText(I18n.t("preferences.lookAndFeel.smallIcons"));
        smallIconsBtn.setToolTipText(I18n.t("preferences.lookAndFeel.smallIcons.tooltip"));
        Bindings.check(smallIconsBtn, edit.smallIconsInMainTabProperty());
        smallIconsBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Footer note so the user knows Layout changes trigger a refresh.
        Label lfNote = new Label(lookFeelTab, SWT.WRAP);
        lfNote.setText(I18n.t("preferences.lookAndFeel.recreateNote"));
        GridData noteGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        noteGd.horizontalSpan = 2;
        lfNote.setLayoutData(noteGd);

        TabItem audioTabItem = new TabItem(tabs, SWT.NONE);
        audioTabItem.setText(I18n.t("preferences.tab.audio"));
        Composite audioTab = new Composite(tabs, SWT.NONE);
        GridLayout audioLayout = new GridLayout(1, false);
        audioLayout.marginWidth  = 8;
        audioLayout.marginHeight = 8;
        audioLayout.verticalSpacing = 8;
        audioTab.setLayout(audioLayout);
        audioTabItem.setControl(audioTab);

        // --- Backend row ---------------------------------------------------
        Composite backendRow = new Composite(audioTab, SWT.NONE);
        backendRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout backendLayout = new GridLayout(2, false);
        backendLayout.marginWidth = 0;
        backendLayout.marginHeight = 0;
        backendRow.setLayout(backendLayout);
        new Label(backendRow, SWT.NONE).setText(I18n.t("preferences.backend"));
        Combo backendCombo = new Combo(backendRow, SWT.READ_ONLY);
        // Only list backends that work on the current OS — WASAPI / WDM-KS
        // are Windows-only.  The order in the combo is preserved so
        // selectionIndex matches availableBackends below.
        List<AudioBackendType> availableBackends = new ArrayList<>();
        for (AudioBackendType type : AudioBackendType.values()) {
            if (type.isAvailable()) {
                availableBackends.add(type);
                backendCombo.add(type.name());
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
        new Label(inputGroup, SWT.NONE).setText(I18n.t("preferences.device"));
        Combo inputCombo = new Combo(inputGroup, SWT.READ_ONLY);
        inputCombo.setLayoutData(comboData());
        new Label(inputGroup, SWT.NONE).setText(I18n.t("preferences.sampleRate"));
        Combo inputRateCombo = new Combo(inputGroup, SWT.READ_ONLY);
        inputRateCombo.setLayoutData(comboData());
        new Label(inputGroup, SWT.NONE).setText(I18n.t("preferences.bitDepth"));
        Combo inputDepthCombo = new Combo(inputGroup, SWT.READ_ONLY);
        inputDepthCombo.setLayoutData(comboData());

        // --- Output group --------------------------------------------------
        Group outputGroup = new Group(audioTab, SWT.NONE);
        outputGroup.setText(I18n.t("preferences.output"));
        outputGroup.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        outputGroup.setLayout(new GridLayout(2, false));
        new Label(outputGroup, SWT.NONE).setText(I18n.t("preferences.device"));
        Combo outputCombo = new Combo(outputGroup, SWT.READ_ONLY);
        outputCombo.setLayoutData(comboData());
        new Label(outputGroup, SWT.NONE).setText(I18n.t("preferences.sampleRate"));
        Combo outputRateCombo = new Combo(outputGroup, SWT.READ_ONLY);
        outputRateCombo.setLayoutData(comboData());
        new Label(outputGroup, SWT.NONE).setText(I18n.t("preferences.bitDepth"));
        Combo outputDepthCombo = new Combo(outputGroup, SWT.READ_ONLY);
        outputDepthCombo.setLayoutData(comboData());

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

        // Measurement averaging window — 0.5 s steps up to 60 s.
        String[] AVG_TIME_VALUES = avgTimeSteps();
        new Label(oscTab, SWT.NONE).setText(I18n.t("preferences.measAvg"));
        StepSelector avgSecondsSel = new StepSelector(oscTab, AVG_TIME_VALUES,
                nearestIndex(AVG_TIME_VALUES, edit.getOscMeasurementAverageSeconds()), 90);
        avgSecondsSel.setLayoutData(comboData());
        avgSecondsSel.setToolTipText(I18n.t("preferences.measAvg.tooltip"));

        // Trace line width — 1.5 px increments from 1.0 to 5.0.
        String[] LINE_WIDTH_VALUES = {"1.0", "1.5", "2.0", "2.5", "3.0", "3.5", "4.0", "4.5", "5.0"};
        new Label(oscTab, SWT.NONE).setText(I18n.t("preferences.lineWidth"));
        StepSelector lineWidthSel = new StepSelector(oscTab, LINE_WIDTH_VALUES,
                nearestIndex(LINE_WIDTH_VALUES, edit.getOscLineWidth()), 90);
        lineWidthSel.setLayoutData(comboData());
        lineWidthSel.setToolTipText(I18n.t("preferences.lineWidth.tooltip"));

        // Sample-dot diameter — 1 px increments from 3 to 12.
        String[] DOT_DIAM_VALUES = {"3", "4", "5", "6", "7", "8", "9", "10", "11", "12"};
        new Label(oscTab, SWT.NONE).setText(I18n.t("preferences.dotDiameter"));
        StepSelector dotDiameterSel = new StepSelector(oscTab, DOT_DIAM_VALUES,
                nearestIndex(DOT_DIAM_VALUES, edit.getOscDotDiameter()), 90);
        dotDiameterSel.setLayoutData(comboData());
        dotDiameterSel.setToolTipText(I18n.t("preferences.dotDiameter.tooltip"));

        // Per-channel trace colour — button background reflects the picked
        // colour, held in a local holder for the dialog session; click opens a
        // ColorDialog and updates only the holder.  The live pref is written on
        // OK (see okButton handler), so Cancel discards by simply not applying.
        new Label(oscTab, SWT.NONE).setText(I18n.t("preferences.leftColor"));
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

        new Label(oscTab, SWT.NONE).setText(I18n.t("preferences.rightColor"));
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
        String[] FFT_LINE_WIDTH_VALUES = {"1.0", "1.5", "2.0", "2.5", "3.0", "3.5", "4.0", "4.5", "5.0"};
        new Label(fftTab, SWT.NONE).setText(I18n.t("preferences.fft.lineWidth"));
        StepSelector fftLineWidthSel = new StepSelector(fftTab, FFT_LINE_WIDTH_VALUES,
                nearestIndex(FFT_LINE_WIDTH_VALUES, edit.getFftLineWidth()), 90);
        fftLineWidthSel.setLayoutData(comboData());
        fftLineWidthSel.setToolTipText(I18n.t("preferences.fft.lineWidth.tooltip"));

        // Harmonic dot diameter — 1 px increments, same range as scope sample dot.
        String[] FFT_DOT_DIAM_VALUES = {"3", "4", "5", "6", "7", "8", "9", "10", "11", "12"};
        new Label(fftTab, SWT.NONE).setText(I18n.t("preferences.fft.dotDiameter"));
        StepSelector fftDotDiameterSel = new StepSelector(fftTab, FFT_DOT_DIAM_VALUES,
                nearestIndex(FFT_DOT_DIAM_VALUES, edit.getFftHarmonicDotDiameter()), 90);
        fftDotDiameterSel.setLayoutData(comboData());
        fftDotDiameterSel.setToolTipText(I18n.t("preferences.fft.dotDiameter.tooltip"));

        // Multi-tone detect threshold (dB below the strongest peak): for
        // cross-tick coherent averaging a spectral peak is treated as a separate
        // tone only if within this many dB of the strongest.  Lower it so a
        // tone's harmonics / IMD products aren't taken as independent tones
        // (which would route the average onto the per-tone multi-tone path).
        new Label(fftTab, SWT.NONE).setText(I18n.t("preferences.fft.strongToneRelDb"));
        NumericStepField fftStrongToneRelDbField = new NumericStepField(fftTab,
                edit.getFftStrongToneRelDb(),
                raw -> {
                    if (raw == null) return null;
                    String s = raw.trim().replace(',', '.').replace("dB", "").trim();
                    if (s.isEmpty()) return null;
                    try { return Math.max(10.0, Math.min(140.0, Double.parseDouble(s))); }
                    catch (NumberFormatException ex) { return null; }
                },
                v -> String.format(Locale.ROOT, "%.0f dB", v),
                (v, dir) -> Math.max(10.0, Math.min(140.0, v + 10.0 * dir)),   // wheel ±10
                (v, dir) -> Math.max(10.0, Math.min(140.0, v +  1.0 * dir)),   // arrows ±1
                90);
        fftStrongToneRelDbField.setLayoutData(comboData());
        fftStrongToneRelDbField.setToolTipText(I18n.t("preferences.fft.strongToneRelDb.tooltip"));
        Bindings.stepField(fftStrongToneRelDbField, edit.fftStrongToneRelDbProperty());

        // Spectrum line colour.
        int[] fftLineColorHolder = { edit.getFftLineColor() };
        new Label(fftTab, SWT.NONE).setText(I18n.t("preferences.fft.lineColor"));
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
        new Label(fftTab, SWT.NONE).setText(I18n.t("preferences.fft.bgColor"));
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
        new Label(fftTab, SWT.NONE).setText(I18n.t("preferences.fft.dotColor"));
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
        new Label(fftTab, SWT.NONE).setText(I18n.t("preferences.fft.filterColor"));
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
        new Label(fftTab, SWT.NONE).setText(I18n.t("preferences.fft.beforeCalDotColor"));
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
        new Label(fftTab, SWT.NONE).setText(I18n.t("preferences.fft.calOverlayColor"));
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
        String[] FREQ_RESP_LINE_WIDTH_VALUES = {"1.0", "1.5", "2.0", "2.5", "3.0", "3.5", "4.0", "4.5", "5.0"};
        new Label(freqRespTab, SWT.NONE).setText(I18n.t("preferences.freqResp.lineWidth"));
        StepSelector freqRespLineWidthSel = new StepSelector(freqRespTab, FREQ_RESP_LINE_WIDTH_VALUES,
                nearestIndex(FREQ_RESP_LINE_WIDTH_VALUES, edit.getFreqRespLineWidth()), 90);
        freqRespLineWidthSel.setLayoutData(comboData());
        freqRespLineWidthSel.setToolTipText(I18n.t("preferences.freqResp.lineWidth.tooltip"));

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
        NumericStepField nyqField = new NumericStepField(freqRespTab,
                edit.getFreqRespNyquistFraction() * 100.0,
                raw -> {
                    if (raw == null) return null;
                    String s = raw.trim().replace(',', '.').replace("%", "").trim();
                    if (s.isEmpty()) return null;
                    try { return Double.parseDouble(s); }
                    catch (NumberFormatException ex) { return null; }
                },
                v -> String.format(Locale.ROOT, "%.1f %%", v),
                (v, dir) -> Math.max(FREQRESP_MAX_FREQ_PCT_MIN,
                        Math.min(FREQRESP_MAX_FREQ_PCT_MAX,
                                v + FREQRESP_MAX_FREQ_PCT_STEP * dir)),    // wheel
                (v, dir) -> Math.max(FREQRESP_MAX_FREQ_PCT_MIN,
                        Math.min(FREQRESP_MAX_FREQ_PCT_MAX,
                                v + FREQRESP_MAX_FREQ_PCT_STEP * dir)),    // arrows
                90);
        nyqField.setLayoutData(comboData());
        nyqField.setToolTipText(I18n.t("preferences.freqResp.maxFreqPctNyquist.tooltip"));
        // Working copy: the field edits a working percent; nothing touches the
        // live pref until OK.  The selection listener only refreshes the (Hz)
        // label so the user still sees the concrete band track the typed
        // percent — it reads the live input sample rate (display-only) and the
        // working percent, never a live pref.  On OK the apply below writes the
        // fraction, clamps the freq window down if the new max moved below the
        // current right edge, and the OK handler fires FREQRESP_RANGE_CHANGED.
        Property<Double> nyqWork = new Property<>(edit.getFreqRespNyquistFraction() * 100.0);
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
        // The percent → fraction conversion + freq-window clamp run on OK,
        // straight into the working copy.  Held in a local so the OK handler
        // can invoke it before applyFromDialog().
        Runnable applyNyquistToEdit = () -> {
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
        };

        // Compare-mode smoothing window (points).  Used by the FreqResp
        // view's getSmoothedDiffDb to draw the (measured − reference)
        // curve and to compute the anchor / min-max table that the
        // auto-setup snaps to.  Live-applied: every edit publishes
        // FREQRESP_COMPARE_PARAMS_CHANGED so the view re-derives the
        // smoothed array and refreshes the table without disturbing
        // the current zoom.
        new Label(freqRespTab, SWT.NONE).setText(I18n.t("preferences.freqResp.compareSmoothWindow"));
        NumericStepField smoothField = new NumericStepField(freqRespTab,
                edit.getFreqRespCompareSmoothWindow(),
                raw -> {
                    if (raw == null) return null;
                    String s = raw.trim().replace(',', '.');
                    if (s.isEmpty()) return null;
                    Double parsed;
                    try { parsed = (double) Integer.parseInt(s); }
                    catch (NumberFormatException ex) {
                        try { parsed = Math.rint(Double.parseDouble(s)); }
                        catch (NumberFormatException ex2) { return null; }
                    }
                    return Math.max(FREQRESP_SMOOTH_W_MIN,
                            Math.min(FREQRESP_SMOOTH_W_MAX, parsed));
                },
                v -> String.format(Locale.ROOT, "%d", (int) Math.round(v)),
                (v, dir) -> Math.max(FREQRESP_SMOOTH_W_MIN,
                        Math.min(FREQRESP_SMOOTH_W_MAX,
                                Math.rint(v) + FREQRESP_SMOOTH_W_STEP * dir)),  // wheel
                (v, dir) -> Math.max(FREQRESP_SMOOTH_W_MIN,
                        Math.min(FREQRESP_SMOOTH_W_MAX,
                                Math.rint(v) + FREQRESP_SMOOTH_W_STEP * dir)),  // arrows
                90);
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
        Label notchEnableSpacer = new Label(freqRespTab, SWT.NONE);  // column 0 placeholder
        notchEnableSpacer.setText("");
        Button notchEnableBtn = new Button(freqRespTab, SWT.CHECK);
        notchEnableBtn.setText(I18n.t("preferences.freqResp.notch.enable"));
        notchEnableBtn.setToolTipText(I18n.t("preferences.freqResp.notch.enable.tooltip"));
        notchEnableBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        new Label(freqRespTab, SWT.NONE).setText(I18n.t("preferences.freqResp.notch.baseHz"));
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

        new Label(freqRespTab, SWT.NONE).setText(I18n.t("preferences.freqResp.signalColor"));
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

        new Label(freqRespTab, SWT.NONE).setText(I18n.t("preferences.freqResp.phaseColor"));
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

        new Label(freqRespTab, SWT.NONE).setText(I18n.t("preferences.freqResp.referenceColor"));
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

        new Label(freqRespTab, SWT.NONE).setText(I18n.t("preferences.freqResp.backgroundColor"));
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

        // --- Device list state + refresh logic -----------------------------
        DeviceListState devices = new DeviceListState();

        // Repopulate the input-side rate / depth combos from the picked input
        // device's own capabilities.  Falls back to defaults when no device
        // is picked or the driver reports nothing.
        Runnable refreshInputRatesAndDepths = () -> {
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
        };

        // Mirror of {@code refreshInputRatesAndDepths} for the output side.
        Runnable refreshOutputRatesAndDepths = () -> {
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
        };

        // Captures the current UI state into the prefs of {@code active[0]} so
        // a backend switch (or OK) doesn't lose the user's edits.  Devices
        // are stored by name (string) — the saved name is matched back to a
        // live DeviceRef on dialog open via populateDeviceCombo.
        Runnable captureUiToActive = () -> {
            BackendPrefs bp = edit.current();
            bp.setInputDeviceName (nameOf(pickedDevice(inputCombo,  devices.inputs)));
            bp.setOutputDeviceName(nameOf(pickedDevice(outputCombo, devices.outputs)));
            int idx;
            if ((idx = inputRateCombo.getSelectionIndex())  >= 0) bp.setInputSampleRate (parseLeadingInt(inputRateCombo.getItem(idx)));
            if ((idx = inputDepthCombo.getSelectionIndex()) >= 0) bp.setInputBitDepth   (parseLeadingInt(inputDepthCombo.getItem(idx)));
            if ((idx = outputRateCombo.getSelectionIndex()) >= 0) bp.setOutputSampleRate(parseLeadingInt(outputRateCombo.getItem(idx)));
            if ((idx = outputDepthCombo.getSelectionIndex())>= 0) bp.setOutputBitDepth  (parseLeadingInt(outputDepthCombo.getItem(idx)));
        };

        Runnable refreshDevices = () -> {
            // Enumerate the chosen backend's hardware WITHOUT activating it —
            // the type-parameterised overloads resolve the manager by
            // edit.getBackend() and never touch the live `active` field.
            AudioBackendType type = edit.getBackend();
            devices.inputs  = AudioBackend.instance().listInputDevices(type);
            devices.outputs = AudioBackend.instance().listOutputDevices(type);
            BackendPrefs bp = edit.current();
            populateDeviceCombo(inputCombo,  devices.inputs,  bp.getInputDeviceName());
            populateDeviceCombo(outputCombo, devices.outputs, bp.getOutputDeviceName());
            refreshInputRatesAndDepths.run();
            refreshOutputRatesAndDepths.run();
        };

        refreshDevices.run();
        backendCombo.addListener(SWT.Selection, e -> {
            // Persist the outgoing backend's UI state into the working copy
            // before switching, so toggling back later restores what the user
            // just chose.  capture() targets edit.current() (the OLD backend),
            // then setBackend() makes current() the NEW backend.
            captureUiToActive.run();
            edit.setBackend(availableBackends.get(backendCombo.getSelectionIndex()));
            refreshDevices.run();
        });
        inputCombo.addListener (SWT.Selection, e -> refreshInputRatesAndDepths.run());
        outputCombo.addListener(SWT.Selection, e -> refreshOutputRatesAndDepths.run());

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

        okButton.addListener(SWT.Selection, e -> {
            // Fold every control's value into the working copy `edit`.
            // --- Per-backend device / rate / depth for the shown backend.
            captureUiToActive.run();
            // --- Step selectors: read straight off the controls into edit.
            edit.setOscMeasurementAverageSeconds(Double.parseDouble(avgSecondsSel.getSelectedValue()));
            edit.setOscLineWidth                (Double.parseDouble(lineWidthSel.getSelectedValue()));
            edit.setOscDotDiameter              (Integer.parseInt(dotDiameterSel.getSelectedValue()));
            edit.setFftLineWidth                (Double.parseDouble(fftLineWidthSel.getSelectedValue()));
            edit.setFftHarmonicDotDiameter      (Integer.parseInt(fftDotDiameterSel.getSelectedValue()));
            edit.setFreqRespLineWidth           (Double.parseDouble(freqRespLineWidthSel.getSelectedValue()));
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
            applyNyquistToEdit.run();
            // (orientation, small icons, strong-tone rel-dB, compare-smoothing
            //  window, notch enable + base Hz already live on `edit` via their
            //  two-way binds.)
            BackendPrefs bp = edit.current();
            log.info("Preferences saved: backend={}, in={} @ {} Hz / {} bits, out={} @ {} Hz / {} bits",
                    edit.getBackend(),
                    bp.getInputDeviceName()  != null ? bp.getInputDeviceName()  : "<none>",
                    bp.getInputSampleRate(),  bp.getInputBitDepth(),
                    bp.getOutputDeviceName() != null ? bp.getOutputDeviceName() : "<none>",
                    bp.getOutputSampleRate(), bp.getOutputBitDepth());
            // Single hand-off: commit the whole working copy to the live
            // singleton (which also persists once).
            Preferences.instance().applyFromDialog(edit);
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
            dialog.close();
        });

        cancelButton.addListener(SWT.Selection, e -> {
            // The dialog edited a detached working copy — nothing live was
            // touched, so there is nothing to roll back.
            dialog.close();
        });

        dialog.pack();
        Dialogs.centerOnParent(dialog);
        dialog.open();
    }

    private GridData comboData() {
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.widthHint = 360;
        return gd;
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

    /**
     * "0.5", "1.0", ... "60.0" — 0.5-second steps for the measurement
     * averaging selector.  Formatted with {@link Locale#ROOT} so the decimal
     * separator is always a period (matching {@link Double#parseDouble}'s
     * input contract regardless of the platform locale).
     */
    private String[] avgTimeSteps() {
        int count = 120;
        String[] out = new String[count];
        for (int i = 0; i < count; i++) out[i] = String.format(Locale.ROOT, "%.1f", 0.5 * (i + 1));
        return out;
    }

    /** Index of the entry in {@code values} whose parsed double is closest to {@code target}. */
    private int nearestIndex(String[] values, double target) {
        int best = 0;
        double bestDist = Double.POSITIVE_INFINITY;
        for (int i = 0; i < values.length; i++) {
            double v = Double.parseDouble(values[i]);
            double d = Math.abs(v - target);
            if (d < bestDist) { bestDist = d; best = i; }
        }
        return best;
    }

    /** Index of the entry closest to an integer target — used by the dot-diameter selector. */
    private int nearestIndex(String[] values, int target) {
        return nearestIndex(values, (double) target);
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

    private static final class DeviceListState {
        List<DeviceRef> inputs  = List.of();
        List<DeviceRef> outputs = List.of();
    }
}
