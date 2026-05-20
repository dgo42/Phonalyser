package org.edgo.audio.measure.gui.scope;

import lombok.extern.log4j.Log4j2;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.edgo.audio.measure.gui.i18n.I18n;

import java.util.Locale;
import java.util.function.DoubleConsumer;

/**
 * Modal dialog that takes the user-entered <em>actual</em> signal amplitude
 * (Vrms, in mV / V / dBV) and emits it through a {@link DoubleConsumer} as
 * volts-RMS.  The owner ({@link MainWindow#openAdcCalibrationDialog()}) is
 * responsible for using the returned value to rescale the ADC full-scale
 * voltage so the scope's displayed Vrms matches the entered value.
 *
 * <p>The dialog also shows the scope's currently-measured Vrms so the user
 * can sanity-check the ratio before committing.  All three unit labels
 * mean RMS: mV → mV-RMS, V → V-RMS, dBV → 10^(value/20) V-RMS.
 */
@Log4j2
public final class AdcCalibrationDialog {

    private final Shell dialog;

    public AdcCalibrationDialog(Shell parent, double currentVrms, DoubleConsumer onCalibrate) {
        dialog = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        dialog.setText(I18n.t("calibrate.title"));
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth  = 16;
        gl.marginHeight = 16;
        gl.verticalSpacing = 10;
        dialog.setLayout(gl);

        // Read-only line showing what the scope currently measures, so the
        // user has both numbers (entered + current) in front of them.
        Label currentLbl = new Label(dialog, SWT.NONE);
        currentLbl.setText(I18n.t("calibrate.current", formatVoltage(currentVrms)));
        currentLbl.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label inputLbl = new Label(dialog, SWT.NONE);
        inputLbl.setText(I18n.t("calibrate.input"));
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
        valueField.setText(String.format(Locale.ROOT, "%.4f", currentVrms));
        valueField.setToolTipText(I18n.t("calibrate.input.tooltip"));

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
                MessageBox mb = new MessageBox(dialog, SWT.ICON_ERROR | SWT.OK);
                mb.setText(I18n.t("calibrate.error.title"));
                mb.setMessage(I18n.t("calibrate.error.message"));
                mb.open();
                return;
            }
            onCalibrate.accept(vrms);
            log.info("ADC calibration: actual {} → scope was reading {} V RMS",
                    formatVoltage(vrms), formatVoltage(currentVrms));
            dialog.close();
        });
    }

    /** Opens the dialog modally and pumps the parent display loop until closed. */
    public void open() {
        dialog.pack();
        // Centre over the parent shell.
        Shell parent = (Shell) dialog.getParent();
        int x = parent.getLocation().x + (parent.getSize().x - dialog.getSize().x) / 2;
        int y = parent.getLocation().y + (parent.getSize().y - dialog.getSize().y) / 2;
        dialog.setLocation(x, y);
        dialog.open();
        while (!dialog.isDisposed()) {
            if (!dialog.getDisplay().readAndDispatch()) dialog.getDisplay().sleep();
        }
    }

    /**
     * Parses {@code valueStr} as a positive number and converts it to volts
     * RMS using {@code unit}.  Accepts both decimal point and comma as the
     * fraction separator.  Returns {@code null} on a parse failure.
     */
    private static Double parseAsVrms(String valueStr, String unit) {
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

    private static String formatVoltage(double v) {
        double a = Math.abs(v);
        if (a >= 1.0)  return String.format(Locale.ROOT, "%.4f V",  v);
        if (a >= 1e-3) return String.format(Locale.ROOT, "%.3f mV", v * 1e3);
        if (a >= 1e-6) return String.format(Locale.ROOT, "%.2f µV", v * 1e6);
        return String.format(Locale.ROOT, "%.3g V", v);
    }
}
