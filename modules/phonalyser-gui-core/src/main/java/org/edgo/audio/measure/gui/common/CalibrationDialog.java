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

package org.edgo.audio.measure.gui.common;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.widgets.NumericStepField;
import org.edgo.audio.measure.gui.widgets.UnitFamily;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.ObjDoubleConsumer;

/**
 * Shared modal voltage-calibration dialog for the ADC (scope / FFT input) and
 * the DAC (generator output) - the two flows share one shape, only their window
 * title, prompt wording and log tag differ, so those are passed in as
 * {@link Texts} rather than duplicated across two classes.
 *
 * <p>One shape, always two rows - a Left row and a Right row, each
 * {@code [channel label] [NumericStepField]} (no reference/readout voltage
 * column).  Each field takes a user-entered Vrms (nV / µV / mV / V, no dBV) and
 * emits it as canonical volts-RMS through an {@link ObjDoubleConsumer} tagged
 * with the row's {@link Channel}; the owner rescales that channel's full-scale.
 *
 * <p>A {@code null} seed disables its row and leaves the field <em>blank</em>
 * (the ADC's non-measured channel - no value, not the clamp-to-min "1 nV"
 * artifact).  A non-null seed prefills the field.  On OK only enabled rows whose
 * field carries a valid value fire.
 */
@Log4j2
public final class CalibrationDialog {

    /** Field range: 1 nV ... 1000 V, µV precision at volts scale. */
    private static final double MIN_VRMS = 1e-9;
    private static final double MAX_VRMS = 1000.0;
    private static final int    MAX_DECIMALS = 6;
    private static final int    FIELD_WIDTH = 120;

    /**
     * The per-dialog wording that distinguishes the ADC from the DAC flow.
     * The first three are {@link I18n} keys resolved by the dialog; the last is
     * a literal tag for the log line.
     *
     * @param titleKey         shell-title key
     * @param inputPromptKey   key for the prompt above the rows
     * @param fieldTooltipKey  key for each input field's tooltip
     * @param logTag           short literal tag for the commit log line ("ADC" / "DAC")
     */
    public record Texts(String titleKey, String inputPromptKey,
                        String fieldTooltipKey, String logTag) { }

    private final Shell dialog;
    /** The single content composite holding every widget - the snapshot target
     *  for a help capture, since a top-level Shell prints blank on Windows (a
     *  Composite prints its children). */
    @Getter
    private final Composite content;

    /** Two-row (per-channel) calibration: a Left row and a Right row, each
     *  seeded with that channel's reference Vrms (a {@code null} disables the
     *  row and leaves its field blank); on OK every enabled+valid row fires
     *  {@code onCalibrate} tagged with its {@link Channel}.  {@code viewOnly}
     *  shows the seeded values read-only with OK disabled - a device-provided
     *  card (QA40x) whose built-in full-scale cannot be overwritten. */
    public CalibrationDialog(Shell parent, Texts texts, Double referenceLeftVrms, Double referenceRightVrms,
                             boolean viewOnly, ObjDoubleConsumer<Channel> onCalibrate) {
        this.dialog  = newShell(parent, texts.titleKey());
        this.content = newContent();

        Label inputLbl = new Label(content, SWT.NONE);
        inputLbl.setText(I18n.t(texts.inputPromptKey()));
        inputLbl.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Composite grid = newGrid();
        List<Row> rows = new ArrayList<>();
        rows.add(buildRow(grid, texts, Channel.L, I18n.t("common.channel.left"),  referenceLeftVrms));
        rows.add(buildRow(grid, texts, Channel.R, I18n.t("common.channel.right"), referenceRightVrms));

        Runnable[] onOk = new Runnable[1];
        Button ok = buildButtonRow(texts, () -> onOk[0].run());
        onOk[0] = () -> {
            List<Row> committed = new ArrayList<>();
            for (Row row : rows) {
                if (!row.field.isEnabled() || row.field.isBlank()) continue;   // disabled / blank - skip
                double vrms = row.field.getValue();
                if (!isPositiveFinite(vrms)) {
                    showError();
                    return;                                      // reject the whole submit
                }
                row.parsedVrms = vrms;
                committed.add(row);
            }
            for (Row row : committed) {
                onCalibrate.accept(row.channel, row.parsedVrms);
                log.info("{} calibration [{}]: entered {} vs reference {} V RMS",
                        texts.logTag(), row.channel, formatVoltage(row.parsedVrms), formatVoltage(row.reference));
            }
            dialog.close();
        };
        dialog.setDefaultButton(ok);

        if (viewOnly) {
            // Device-provided calibration (a QA40x): the rows show the card's
            // built-in full-scale, but nothing can be committed - read-only.
            ok.setEnabled(false);
            for (Row r : rows) r.field.setEnabled(false);
        }
    }

    private Shell newShell(Shell parent, String titleKey) {
        Shell shell = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        ShellIcons.apply(shell);
        shell.setText(I18n.t(titleKey));
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth  = 0;
        gl.marginHeight = 0;
        shell.setLayout(gl);
        return shell;
    }

    /** The single content composite wrapping every widget, so a help capture can
     *  render the dialog via {@code Control.print} (a top-level Shell prints blank
     *  on Windows).  Carries the dialog padding the shell margins used to hold. */
    private Composite newContent() {
        Composite c = new Composite(dialog, SWT.NONE);
        c.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth  = 16;
        gl.marginHeight = 16;
        gl.verticalSpacing = 10;
        c.setLayout(gl);
        return c;
    }

    /** The shared two-column aligning grid holding every row's channel label and
     *  input field so those columns line up across rows. */
    private Composite newGrid() {
        Composite grid = new Composite(content, SWT.NONE);
        GridLayout gridLayout = new GridLayout(2, false);
        gridLayout.marginWidth = 0;
        gridLayout.marginHeight = 0;
        gridLayout.horizontalSpacing = 8;
        grid.setLayout(gridLayout);
        grid.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return grid;
    }

    /** Builds one row (channel label + value field) inside the shared grid.  A
     *  {@code null} reference disables the field and leaves it blank - that
     *  channel has no measurement/configuration to calibrate against; a non-null
     *  reference prefills the field. */
    private Row buildRow(Composite grid, Texts texts, Channel channel, String rowLabel, Double reference) {
        Label nameLbl = new Label(grid, SWT.NONE);
        nameLbl.setText(rowLabel);
        nameLbl.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        NumericStepField field = new NumericStepField(grid, UnitFamily.VOLTAGE,
                MIN_VRMS, MAX_VRMS, MAX_DECIMALS, FIELD_WIDTH);
        field.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        field.setToolTipText(I18n.t(texts.fieldTooltipKey()));
        if (reference != null) {
            field.setValue(reference);
        } else {
            field.setBlank();
        }
        field.setEnabled(reference != null);

        Row row = new Row();
        row.channel   = channel;
        row.reference = reference;
        row.field     = field;
        return row;
    }

    /** Button row - OK on the right (default), Cancel to its left; {@code onOk}
     *  runs the caller's commit.  Returns the OK button for the shell default. */
    private Button buildButtonRow(Texts texts, Runnable onOk) {
        Composite buttons = new Composite(content, SWT.NONE);
        GridLayout bL = new GridLayout(2, true);
        bL.marginWidth = 0;
        bL.marginHeight = 0;
        buttons.setLayout(bL);
        buttons.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));

        Button cancel = new Button(buttons, SWT.PUSH);
        cancel.setText(I18n.t("common.cancel"));
        cancel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        cancel.addListener(SWT.Selection, e -> dialog.close());

        Button ok = new Button(buttons, SWT.PUSH);
        ok.setText(I18n.t("calibrate.ok"));
        ok.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        ok.addListener(SWT.Selection, e -> onOk.run());
        return ok;
    }

    private void showError() {
        Dialogs.error(dialog, I18n.t("calibrate.error.title"), I18n.t("calibrate.error.message"));
    }

    private boolean isPositiveFinite(double v) {
        return v > 0 && !Double.isNaN(v) && !Double.isInfinite(v);
    }

    /** One channel's field + reference/parsed values in the shared grid. */
    private static final class Row {
        private Channel channel;
        private Double  reference;
        private NumericStepField field;
        private double  parsedVrms;
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

    /** Shows the already-built dialog non-modally for a help capture: pack, centre
     *  and open, WITHOUT the blocking modal loop, so the automation can snapshot
     *  {@link #getContent()} and dispose it.  {@link #open()} is the normal
     *  (blocking) entry. */
    public Shell showForCapture() {
        dialog.pack();
        Dialogs.centerOnParent(dialog);
        dialog.open();
        return dialog;
    }

    private String formatVoltage(double v) {
        double a = Math.abs(v);
        if (a >= 1.0)  return String.format(Locale.ROOT, "%.4f V",  v);
        if (a >= 1e-3) return String.format(Locale.ROOT, "%.3f mV", v * 1e3);
        if (a >= 1e-6) return String.format(Locale.ROOT, "%.2f µV", v * 1e6);
        return String.format(Locale.ROOT, "%.3g V", v);
    }
}
