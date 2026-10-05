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

package org.edgo.audio.measure.gui.delay;

import java.util.Locale;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.OutputChannels;
import org.edgo.audio.measure.gui.common.Dialogs;
import org.edgo.audio.measure.gui.common.ShellIcons;
import org.edgo.audio.measure.gui.freqresp.FreqRespConstants;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.widgets.NumericStepField;
import org.edgo.audio.measure.gui.widgets.UnitFamily;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.DeviceCalibration;

/**
 * The delay tool's window: the instructions, the pulse's amplitude, the
 * output lane(s) and the capture channel, the delay readout,
 * Measure and Close.  Modal and resizable with its packed size as the minimum.
 * Owns a {@link MeasureDelayController}; the fields persist in the
 * preferences on close.
 */
public final class MeasureDelayDialog implements MeasureDelayController.Listener {

    private static final int FIELD_WIDTH_HINT = 110;
    private static final int INTRO_WIDTH_HINT = 560;

    private final Shell parentShell;
    private Shell dialog;
    private MeasureDelayController controller;
    private NumericStepField ampField;
    private Combo outputCombo;
    private Combo inputCombo;
    private Label delayValue;
    private Label statusLabel;
    private Button measureButton;

    public MeasureDelayDialog(Shell parent) {
        this.parentShell = parent;
    }

    /** Builds and shows the dialog, then runs the modal loop until it closes. */
    public void open() {
        build();
        Display d = dialog.getDisplay();
        while (!dialog.isDisposed()) {
            if (!d.readAndDispatch()) d.sleep();
        }
    }

    private void build() {
        Preferences prefs = Preferences.instance();
        dialog = new Shell(parentShell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
        ShellIcons.apply(dialog);
        dialog.setText(I18n.t("measureDelay.title"));
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 12; layout.marginHeight = 12; layout.verticalSpacing = 10;
        dialog.setLayout(layout);
        controller = new MeasureDelayController(dialog, this);

        Label intro = new Label(dialog, SWT.WRAP);
        intro.setText(I18n.t("measureDelay.intro"));
        GridData introData = new GridData(SWT.FILL, SWT.TOP, true, false);
        introData.widthHint = INTRO_WIDTH_HINT;
        intro.setLayoutData(introData);

        buildFields(prefs);
        buildDelayRow();
        buildButtonRow();

        dialog.addListener(SWT.Close, e -> {
            controller.close();
            savePrefs(prefs);
        });
        dialog.pack();
        dialog.setMinimumSize(dialog.getSize());
        Dialogs.centerOnParent(dialog);
        dialog.open();
    }

    private void buildFields(Preferences prefs) {
        Composite row = new Composite(dialog, SWT.NONE);
        row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout gl = new GridLayout(4, false);
        gl.marginWidth = 0; gl.marginHeight = 0;
        gl.horizontalSpacing = 8; gl.verticalSpacing = 6;
        row.setLayout(gl);

        // A rectangle's RMS is its peak, so the no-clip ceiling is the DAC's
        // full-scale peak itself.
        DeviceCalibration dac = AudioBackend.instance().calibrationOrDefault(false);
        addLabel(row, I18n.t("measureDelay.amplitude"));
        ampField = new NumericStepField(row, UnitFamily.AMPLITUDE,
                FreqRespConstants.AMP_MIN_VRMS, dac.peakVolts(Channel.L),
                FreqRespConstants.AMP_MAX_DECIMALS,
                () -> AudioBackend.instance().calibrationOrDefault(false).peakVolts(Channel.L),
                FIELD_WIDTH_HINT);
        ampField.setToolTipText(I18n.t("generator.amplitudeRms.tooltip"));
        ampField.seedPair(prefs.getMeasureDelayAmplitude());

        // Index maps to OutputChannels {BOTH, LEFT, RIGHT} by ordinal, as the
        // generator and tune-notch combos do.
        addLabel(row, I18n.t("measureDelay.outputChannel"));
        outputCombo = new Combo(row, SWT.READ_ONLY);
        outputCombo.add(I18n.t("common.channel.both"));
        outputCombo.add(I18n.t("common.channel.left"));
        outputCombo.add(I18n.t("common.channel.right"));
        outputCombo.select(prefs.getMeasureDelayOutputChannels().ordinal());

        // Index maps to Channel {L, R} by ordinal.
        addLabel(row, I18n.t("measureDelay.inputChannel"));
        inputCombo = new Combo(row, SWT.READ_ONLY);
        inputCombo.add(I18n.t("common.channel.left"));
        inputCombo.add(I18n.t("common.channel.right"));
        inputCombo.select(prefs.getMeasureDelayInputChannel().ordinal());
    }

    private void buildDelayRow() {
        Composite row = new Composite(dialog, SWT.NONE);
        row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 0; gl.marginHeight = 0; gl.horizontalSpacing = 8;
        row.setLayout(gl);
        addLabel(row, I18n.t("measureDelay.delay"));
        delayValue = new Label(row, SWT.NONE);
        delayValue.setText(I18n.t("measureDelay.delay.none"));
        delayValue.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    }

    private void buildButtonRow() {
        Composite row = new Composite(dialog, SWT.NONE);
        row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout gl = new GridLayout(3, false);
        gl.marginWidth = 0; gl.marginHeight = 0; gl.horizontalSpacing = 8;
        row.setLayout(gl);

        statusLabel = new Label(row, SWT.NONE);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        measureButton = new Button(row, SWT.PUSH);
        measureButton.setText(I18n.t("measureDelay.measure"));
        measureButton.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
        measureButton.addListener(SWT.Selection, e -> startMeasurement());

        Button close = new Button(row, SWT.PUSH);
        close.setText(I18n.t("measureDelay.close"));
        close.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
        close.addListener(SWT.Selection, e -> dialog.close());
    }

    private void addLabel(Composite parent, String text) {
        Label l = new Label(parent, SWT.NONE);
        l.setText(text);
        l.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
    }

    private void startMeasurement() {
        measureButton.setEnabled(false);
        delayValue.setText(I18n.t("measureDelay.delay.none"));
        statusLabel.setText(I18n.t("measureDelay.status.measuring"));
        controller.measure(ampField.getValue(), selectedOutput(), selectedInput());
    }

    private OutputChannels selectedOutput() {
        return OutputChannels.values()[Math.max(0, outputCombo.getSelectionIndex())];
    }

    private Channel selectedInput() {
        return Channel.values()[Math.max(0, inputCombo.getSelectionIndex())];
    }

    private void savePrefs(Preferences prefs) {
        prefs.setMeasureDelayAmplitude(ampField.enteredValue());
        prefs.setMeasureDelayOutputChannels(selectedOutput());
        prefs.setMeasureDelayInputChannel(selectedInput());
        prefs.save();
    }

    // -------------------------------------------------------------------------
    // Controller reports (UI thread)
    // -------------------------------------------------------------------------

    @Override
    public void measured(double delayMs) {
        if (dialog.isDisposed()) return;
        delayValue.setText(I18n.t("measureDelay.delay.value",
                String.format(Locale.ROOT, "%.3f", delayMs)));
        statusLabel.setText("");
        measureButton.setEnabled(true);
        dialog.layout(true, true);
    }

    @Override
    public void failed(MeasureDelayController.Failure failure) {
        if (dialog.isDisposed()) return;
        statusLabel.setText(switch (failure) {
            case NO_DEVICE -> I18n.t("freqResp.error.noDevice");
            case NO_RESULT -> I18n.t("measureDelay.status.noEdge");
        });
        measureButton.setEnabled(true);
        dialog.layout(true, true);
    }
}
