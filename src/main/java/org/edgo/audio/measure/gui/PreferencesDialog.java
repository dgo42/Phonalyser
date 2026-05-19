package org.edgo.audio.measure.gui;

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
import org.edgo.audio.measure.sound.AudioBackendType;
import org.edgo.audio.measure.sound.DeviceRef;

import javax.sound.sampled.AudioFormat;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Modal "Preferences" dialog: lets the user pick the audio backend plus a
 * capture and playback device — each with its own independent sample rate
 * and bit depth.  Input and output fields are visually grouped, and every
 * backend remembers its own selections so switching backends preserves the
 * previously chosen values.
 *
 * <p>OK commits all per-backend edits to {@link Preferences} and propagates
 * the chosen backend to {@link AudioBackend}.  Cancel restores every
 * backend's preferences (and the active backend) from a snapshot taken at
 * open, so a canceled session leaves nothing behind.
 */
@Log4j2
public final class PreferencesDialog {

    private static final int[] DEFAULT_SAMPLE_RATES = {
            44100, 48000, 88200, 96000, 176400, 192000,
            352800, 384000, 705600, 768000
    };
    private static final int[] DEFAULT_BIT_DEPTHS = {16, 24, 32};

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

        Preferences prefs = Preferences.instance();

        // Snapshot for Cancel: deep-copy every backend's prefs + the active
        // backend.  Both are mutated during the dialog session (so switching
        // backends mid-session preserves edits), then restored on Cancel.
        AudioBackendType originalBackend = prefs.getBackend();
        Map<AudioBackendType, Preferences.BackendPrefs> snapshots =
                new EnumMap<>(AudioBackendType.class);
        for (AudioBackendType t : AudioBackendType.values()) {
            snapshots.put(t, prefs.prefsFor(t).snapshot());
        }

        // The backend currently shown in the combos.  Used by save/load
        // helpers to know which backend's prefs to read/write.
        AudioBackendType[] active = { originalBackend };

        // --- Tab folder: Audio + Oscilloscope -----------------------------
        TabFolder tabs = new TabFolder(dialog, SWT.TOP);
        tabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

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
        List<AudioBackendType> availableBackends = new java.util.ArrayList<>();
        for (AudioBackendType type : AudioBackendType.values()) {
            if (type.isAvailable()) {
                availableBackends.add(type);
                backendCombo.add(type.name());
            }
        }
        int originalIdx = availableBackends.indexOf(originalBackend);
        backendCombo.select(originalIdx >= 0 ? originalIdx : 0);
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
                nearestIndex(AVG_TIME_VALUES, prefs.getOscMeasurementAverageSeconds()), 90);
        avgSecondsSel.setLayoutData(comboData());
        avgSecondsSel.setToolTipText(I18n.t("preferences.measAvg.tooltip"));

        // Trace line width — 1.5 px increments from 1.0 to 5.0.
        String[] LINE_WIDTH_VALUES = {"1.0", "1.5", "2.0", "2.5", "3.0", "3.5", "4.0", "4.5", "5.0"};
        new Label(oscTab, SWT.NONE).setText(I18n.t("preferences.lineWidth"));
        StepSelector lineWidthSel = new StepSelector(oscTab, LINE_WIDTH_VALUES,
                nearestIndex(LINE_WIDTH_VALUES, prefs.getOscLineWidth()), 90);
        lineWidthSel.setLayoutData(comboData());
        lineWidthSel.setToolTipText(I18n.t("preferences.lineWidth.tooltip"));

        // Sample-dot diameter — 1 px increments from 3 to 12.
        String[] DOT_DIAM_VALUES = {"3", "4", "5", "6", "7", "8", "9", "10", "11", "12"};
        new Label(oscTab, SWT.NONE).setText(I18n.t("preferences.dotDiameter"));
        StepSelector dotDiameterSel = new StepSelector(oscTab, DOT_DIAM_VALUES,
                nearestIndex(DOT_DIAM_VALUES, prefs.getOscDotDiameter()), 90);
        dotDiameterSel.setLayoutData(comboData());
        dotDiameterSel.setToolTipText(I18n.t("preferences.dotDiameter.tooltip"));

        // Per-channel trace colour — button background reflects the current
        // colour; click opens a ColorDialog and updates the pref live (Cancel
        // does not roll the colour back yet — that'd require explicit snapshotting).
        new Label(oscTab, SWT.NONE).setText(I18n.t("preferences.leftColor"));
        Button leftColorBtn = new Button(oscTab, SWT.PUSH);
        leftColorBtn.setLayoutData(comboData());
        int[] leftRgbHolder  = { prefs.getOscLeftChannelColor()  };
        int[] rightRgbHolder = { prefs.getOscRightChannelColor() };
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
                nearestIndex(FFT_LINE_WIDTH_VALUES, prefs.getFftLineWidth()), 90);
        fftLineWidthSel.setLayoutData(comboData());
        fftLineWidthSel.setToolTipText(I18n.t("preferences.fft.lineWidth.tooltip"));

        // Harmonic dot diameter — 1 px increments, same range as scope sample dot.
        String[] FFT_DOT_DIAM_VALUES = {"3", "4", "5", "6", "7", "8", "9", "10", "11", "12"};
        new Label(fftTab, SWT.NONE).setText(I18n.t("preferences.fft.dotDiameter"));
        StepSelector fftDotDiameterSel = new StepSelector(fftTab, FFT_DOT_DIAM_VALUES,
                nearestIndex(FFT_DOT_DIAM_VALUES, prefs.getFftHarmonicDotDiameter()), 90);
        fftDotDiameterSel.setLayoutData(comboData());
        fftDotDiameterSel.setToolTipText(I18n.t("preferences.fft.dotDiameter.tooltip"));

        // Spectrum line colour.
        int[] fftLineColorHolder = { prefs.getFftLineColor() };
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
        int[] fftBgColorHolder = { prefs.getFftChartBackgroundColor() };
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
        int[] fftDotColorHolder = { prefs.getFftHarmonicDotColor() };
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

        // Filter response line colour.
        int[] fftFilterColorHolder = { prefs.getFftFilterResponseColor() };
        new Label(fftTab, SWT.NONE).setText(I18n.t("preferences.fft.filterColor"));
        Button fftFilterColorBtn = new Button(fftTab, SWT.PUSH);
        fftFilterColorBtn.setLayoutData(comboData());
        applyButtonColor(fftFilterColorBtn, fftFilterColorHolder[0]);
        fftFilterColorBtn.addListener(SWT.Selection, e -> {
            ColorDialog dlg = new ColorDialog(dialog);
            dlg.setRGB(unpackRgb(fftFilterColorHolder[0]));
            RGB picked = dlg.open();
            if (picked != null) {
                fftFilterColorHolder[0] = packRgb(picked);
                applyButtonColor(fftFilterColorBtn, fftFilterColorHolder[0]);
            }
        });

        // --- Device list state + refresh logic -----------------------------
        DeviceListState devices = new DeviceListState();

        // Repopulate the input-side rate / depth combos from the picked input
        // device's own capabilities.  Falls back to defaults when no device
        // is picked or the driver reports nothing.
        Runnable refreshInputRatesAndDepths = () -> {
            DeviceRef dev = pickedDevice(inputCombo, devices.inputs);
            Preferences.BackendPrefs bp = prefs.prefsFor(active[0]);
            TreeSet<Integer> rates  = (dev != null)
                    ? ratesOf(AudioBackend.instance().listSupportedInputFormats(dev))
                    : null;
            TreeSet<Integer> depths = (dev != null)
                    ? depthsOf(AudioBackend.instance().listSupportedInputFormats(dev))
                    : null;
            populateIntCombo(inputRateCombo,  fallback(rates,  DEFAULT_SAMPLE_RATES), " Hz",   bp.getInputSampleRate());
            populateIntCombo(inputDepthCombo, fallback(depths, DEFAULT_BIT_DEPTHS),   " bits", bp.getInputBitDepth());
        };

        // Mirror of {@code refreshInputRatesAndDepths} for the output side.
        Runnable refreshOutputRatesAndDepths = () -> {
            DeviceRef dev = pickedDevice(outputCombo, devices.outputs);
            Preferences.BackendPrefs bp = prefs.prefsFor(active[0]);
            TreeSet<Integer> rates  = (dev != null)
                    ? ratesOf(AudioBackend.instance().listSupportedOutputFormats(dev))
                    : null;
            TreeSet<Integer> depths = (dev != null)
                    ? depthsOf(AudioBackend.instance().listSupportedOutputFormats(dev))
                    : null;
            populateIntCombo(outputRateCombo,  fallback(rates,  DEFAULT_SAMPLE_RATES), " Hz",   bp.getOutputSampleRate());
            populateIntCombo(outputDepthCombo, fallback(depths, DEFAULT_BIT_DEPTHS),   " bits", bp.getOutputBitDepth());
        };

        // Captures the current UI state into the prefs of {@code active[0]} so
        // a backend switch (or OK) doesn't lose the user's edits.  Devices
        // are stored by name (string) — the saved name is matched back to a
        // live DeviceRef on dialog open via populateDeviceCombo.
        Runnable captureUiToActive = () -> {
            Preferences.BackendPrefs bp = prefs.prefsFor(active[0]);
            bp.setInputDeviceName (nameOf(pickedDevice(inputCombo,  devices.inputs)));
            bp.setOutputDeviceName(nameOf(pickedDevice(outputCombo, devices.outputs)));
            int idx;
            if ((idx = inputRateCombo.getSelectionIndex())  >= 0) bp.setInputSampleRate (parseLeadingInt(inputRateCombo.getItem(idx)));
            if ((idx = inputDepthCombo.getSelectionIndex()) >= 0) bp.setInputBitDepth   (parseLeadingInt(inputDepthCombo.getItem(idx)));
            if ((idx = outputRateCombo.getSelectionIndex()) >= 0) bp.setOutputSampleRate(parseLeadingInt(outputRateCombo.getItem(idx)));
            if ((idx = outputDepthCombo.getSelectionIndex())>= 0) bp.setOutputBitDepth  (parseLeadingInt(outputDepthCombo.getItem(idx)));
        };

        Runnable refreshDevices = () -> {
            // Switch the active backend so list*Devices dispatch to the right
            // manager during this dialog session.  Reverted on Cancel; finalised
            // on OK.
            AudioBackend.instance().setActive(active[0]);
            devices.inputs  = AudioBackend.instance().listInputDevices();
            devices.outputs = AudioBackend.instance().listOutputDevices();
            Preferences.BackendPrefs bp = prefs.prefsFor(active[0]);
            populateDeviceCombo(inputCombo,  devices.inputs,  bp.getInputDeviceName());
            populateDeviceCombo(outputCombo, devices.outputs, bp.getOutputDeviceName());
            refreshInputRatesAndDepths.run();
            refreshOutputRatesAndDepths.run();
        };

        refreshDevices.run();
        backendCombo.addListener(SWT.Selection, e -> {
            // Persist the outgoing backend's UI state before switching, so
            // toggling back later restores what the user just chose.
            captureUiToActive.run();
            active[0] = availableBackends.get(backendCombo.getSelectionIndex());
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
            captureUiToActive.run();
            prefs.setOscMeasurementAverageSeconds(Double.parseDouble(avgSecondsSel.getSelectedValue()));
            prefs.setOscLineWidth                (Double.parseDouble(lineWidthSel.getSelectedValue()));
            prefs.setOscDotDiameter              (Integer.parseInt(dotDiameterSel.getSelectedValue()));
            prefs.setOscLeftChannelColor         (leftRgbHolder[0]);
            prefs.setOscRightChannelColor        (rightRgbHolder[0]);
            prefs.setFftLineWidth                (Double.parseDouble(fftLineWidthSel.getSelectedValue()));
            prefs.setFftHarmonicDotDiameter      (Integer.parseInt(fftDotDiameterSel.getSelectedValue()));
            prefs.setFftLineColor                (fftLineColorHolder[0]);
            prefs.setFftChartBackgroundColor     (fftBgColorHolder[0]);
            prefs.setFftHarmonicDotColor         (fftDotColorHolder[0]);
            prefs.setFftFilterResponseColor      (fftFilterColorHolder[0]);
            prefs.setBackend(active[0]);
            AudioBackend.instance().setActive(active[0]);
            Preferences.BackendPrefs bp = prefs.current();
            log.info("Preferences saved: backend={}, in={} @ {} Hz / {} bits, out={} @ {} Hz / {} bits",
                    prefs.getBackend(),
                    bp.getInputDeviceName()  != null ? bp.getInputDeviceName()  : "<none>",
                    bp.getInputSampleRate(),  bp.getInputBitDepth(),
                    bp.getOutputDeviceName() != null ? bp.getOutputDeviceName() : "<none>",
                    bp.getOutputSampleRate(), bp.getOutputBitDepth());
            prefs.save();
            dialog.close();
        });

        cancelButton.addListener(SWT.Selection, e -> {
            // Roll every backend's prefs back to what they were at open, so
            // mid-session edits across backends are fully discarded.
            for (Map.Entry<AudioBackendType, Preferences.BackendPrefs> entry : snapshots.entrySet()) {
                prefs.prefsFor(entry.getKey()).copyFrom(entry.getValue());
            }
            prefs.setBackend(originalBackend);
            AudioBackend.instance().setActive(originalBackend);
            dialog.close();
        });

        dialog.pack();
        dialog.open();
    }

    private GridData comboData() {
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.widthHint = 360;
        return gd;
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
        btn.addDisposeListener(e -> { if (disposeOld != null && !disposeOld.isDisposed()) disposeOld.dispose(); });
        btn.addDisposeListener(e -> { if (!c.isDisposed()) c.dispose(); });
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
