package org.edgo.audio.measure.gui.generator;

import lombok.extern.log4j.Log4j2;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.edgo.audio.measure.gui.common.Dialogs;
import org.edgo.audio.measure.gui.i18n.I18n;

import java.util.Locale;
import java.util.function.DoubleConsumer;

/**
 * Modal dialog that takes the user-entered <em>measured</em> output voltage
 * (Vrms, in mV / V / dBV) from the DAC and emits it through a
 * {@link DoubleConsumer} as volts-RMS.  The owner (the generator pane's
 * Calibrate DAC button) takes the returned measurement, divides by the
 * currently-configured amplitude RMS to obtain the FS scaling error, and
 * applies the correction to {@code SignalGenerator.FS_VOLTAGE}.
 *
 * <p>The dialog also shows the currently-configured amplitude RMS so the
 * user has both numbers (configured + measured) in front of them.  All three
 * unit labels mean RMS: mV → mV-RMS, V → V-RMS, dBV → 10^(value/20) V-RMS.
 */
@Log4j2
public final class DacCalibrationDialog {

    private final Shell dialog;

    public DacCalibrationDialog(Shell parent, double configuredVrms, DoubleConsumer onCalibrate) {
        dialog = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        dialog.setText(I18n.t("calibrate.dac.title"));
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth  = 16;
        gl.marginHeight = 16;
        gl.verticalSpacing = 10;
        dialog.setLayout(gl);

        // Read-only line showing the current configured amplitude RMS, so
        // the user has both numbers (configured + measured) in front of them.
        Label currentLbl = new Label(dialog, SWT.NONE);
        currentLbl.setText(I18n.t("calibrate.dac.configured", formatVoltage(configuredVrms)));
        currentLbl.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label inputLbl = new Label(dialog, SWT.NONE);
        inputLbl.setText(I18n.t("calibrate.dac.input"));
        inputLbl.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Composite inputRow = new Composite(dialog, SWT.NONE);
        GridLayout rowLayout = new GridLayout(2, false);
        rowLayout.marginWidth = 0;
        rowLayout.marginHeight = 0;
        inputRow.setLayout(rowLayout);
        inputRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Text valueField = new Text(inputRow, SWT.BORDER | SWT.SINGLE);
        GridData valueGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        valueGd.widthHint = 120;
        valueField.setLayoutData(valueGd);
        valueField.setText(String.format(Locale.ROOT, "%.4f", configuredVrms));
        valueField.setToolTipText(I18n.t("calibrate.dac.input.tooltip"));

        Combo unitCombo = new Combo(inputRow, SWT.READ_ONLY);
        unitCombo.setItems("V", "mV", "dBV");
        unitCombo.select(0);
        unitCombo.setToolTipText(I18n.t("calibrate.unit.tooltip"));

        // Button row — OK on the right (default), Cancel to its left.
        Composite buttons = new Composite(dialog, SWT.NONE);
        GridLayout bL = new GridLayout(2, true);
        bL.marginWidth = 0;
        bL.marginHeight = 0;
        buttons.setLayout(bL);
        buttons.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));

        Button cancel = new Button(buttons, SWT.PUSH);
        cancel.setText(I18n.t("common.cancel"));
        cancel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button ok = new Button(buttons, SWT.PUSH);
        ok.setText(I18n.t("calibrate.ok"));
        ok.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        dialog.setDefaultButton(ok);

        cancel.addListener(SWT.Selection, e -> dialog.close());
        ok.addListener(SWT.Selection, e -> {
            Double vrms = parseAsVrms(valueField.getText(), unitCombo.getText());
            if (vrms == null || vrms <= 0 || Double.isNaN(vrms) || Double.isInfinite(vrms)) {
                Dialogs.error(dialog,
                        I18n.t("calibrate.error.title"),
                        I18n.t("calibrate.error.message"));
                return;
            }
            onCalibrate.accept(vrms);
            log.info("DAC calibration: measured {} for configured {} V RMS",
                    formatVoltage(vrms), formatVoltage(configuredVrms));
            dialog.close();
        });
    }

    /** Opens the dialog modally and pumps the parent display loop until closed. */
    public void open() {
        dialog.pack();
        Dialogs.centerOnParent(dialog);
        dialog.open();
        while (!dialog.isDisposed()) {
            if (!dialog.getDisplay().readAndDispatch()) dialog.getDisplay().sleep();
        }
    }

    private Double parseAsVrms(String valueStr, String unit) {
        if (valueStr == null) return null;
        String trimmed = valueStr.trim().replace(',', '.');
        if (trimmed.isEmpty()) return null;
        double v;
        try {
            v = Double.parseDouble(trimmed);
        } catch (NumberFormatException ex) {
            return null;
        }
        switch (unit) {
            case "mV":  return v / 1000.0;
            case "dBV": return Math.pow(10.0, v / 20.0);
            case "V":
            default:    return v;
        }
    }

    private String formatVoltage(double v) {
        double a = Math.abs(v);
        if (a >= 1.0)  return String.format(Locale.ROOT, "%.4f V",  v);
        if (a >= 1e-3) return String.format(Locale.ROOT, "%.3f mV", v * 1e3);
        if (a >= 1e-6) return String.format(Locale.ROOT, "%.2f µV", v * 1e6);
        return String.format(Locale.ROOT, "%.3g V", v);
    }
}
