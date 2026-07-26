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

package org.edgo.audio.measure.sound.qa40x;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import org.edgo.audio.measure.gui.common.Dialogs;
import org.edgo.audio.measure.gui.i18n.I18n;

/**
 * The QA402/QA403 backend's own settings — the dialog behind the per-backend
 * button on the Preferences dialog's Audio tab
 * ({@code AudioDeviceManager#openCustomPreferences}).  These settings exist on
 * no other backend, which is why they live with the backend rather than on the
 * shared Preferences pages.
 *
 * <p>Edits reach the caller only on OK; Cancel returns the seed unchanged.  Not
 * an SWT {@code Dialog} subclass — this app builds its own shells.
 */
public class Qa40xSettingsDialog {

    private static final int DIALOG_MARGIN   = 12;
    private static final int BUTTON_SPACING  = 8;
    /** Wrap width of the provenance note — sets the dialog's width, since the
     *  toggle and the button bar are both narrower. */
    private static final int NOTE_WIDTH_HINT = 380;

    private final Shell parent;
    private final Qa40xDeviceInfo info;

    /** The built shell — non-null between {@link #build} and its disposal. */
    private Shell dialog;
    /** The shell's single child, holding every widget.  Snapshotted for a help
     *  capture rather than the shell: a top-level Shell prints blank on Windows,
     *  while a Composite prints its children. */
    private Composite content;
    /** The value the OK listener hands back out of the modal loop. */
    private boolean accepted;

    public Qa40xSettingsDialog(Shell parent, Qa40xDeviceInfo info) {
        this.parent = parent;
        this.info   = info;
    }

    /** The widget-bearing composite, for a help capture to snapshot; {@code null}
     *  before {@link #showForCapture} or after disposal. */
    public Composite getContent() {
        return content;
    }

    /**
     * Shows the dialog modally, seeded with {@code i2sEnabled}, and returns the
     * value the user accepted — or the seed unchanged when they cancelled.
     */
    public boolean open(boolean i2sEnabled) {
        build(i2sEnabled);
        dialog.pack();
        Dialogs.centerOnParent(dialog);
        dialog.open();
        Display display = parent.getDisplay();
        while (!dialog.isDisposed()) {
            if (!display.readAndDispatch()) {
                display.sleep();
            }
        }
        return accepted;
    }

    /** Shows the built dialog for a help capture: pack, centre and open WITHOUT
     *  the blocking modal loop, so the automation can snapshot
     *  {@link #getContent()} and dispose it.  {@link #open} is the normal
     *  (blocking) entry that returns the user's choice. */
    public Shell showForCapture(boolean i2sEnabled) {
        build(i2sEnabled);
        dialog.pack();
        Dialogs.centerOnParent(dialog);
        dialog.open();
        return dialog;
    }

    /** Builds the shell and its widgets, seeded with {@code i2sEnabled}. */
    private void build(boolean i2sEnabled) {
        dialog = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        dialog.setText(I18n.t("qa40x.settings.title"));
        dialog.setLayout(new FillLayout());
        content = new Composite(dialog, SWT.NONE);
        GridLayout layout = new GridLayout(2, false);
        layout.marginWidth     = DIALOG_MARGIN;
        layout.marginHeight    = DIALOG_MARGIN;
        layout.verticalSpacing = DIALOG_MARGIN;
        content.setLayout(layout);

        // Provenance up front: this port is reverse-engineered, not documented
        // by the vendor, so the reader knows what they are switching on before
        // they switch it on.
        Label note = new Label(content, SWT.WRAP);
        note.setText(I18n.t("qa40x.settings.note"));
        GridData noteData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        noteData.widthHint = NOTE_WIDTH_HINT;
        noteData.horizontalSpan = 2;
        note.setLayoutData(noteData);

        // Identity + live telemetry, read once as the dialog opens (doc §4/§6).
        // Read-only: these are the device's own values, not settings.
        addReadOnlyRow(content, "qa40x.settings.firmwareVersion", info.firmwareVersion());
        addReadOnlyRow(content, "qa40x.settings.serialNumber",    info.serialNumber());
        addReadOnlyRow(content, "qa40x.settings.usbVoltage",      info.usbVoltage());
        addReadOnlyRow(content, "qa40x.settings.usbCurrent",      info.usbCurrent());
        addReadOnlyRow(content, "qa40x.settings.isoCurrent",      info.isoCurrent());
        addReadOnlyRow(content, "qa40x.settings.temperature",     info.temperature());
        addReadOnlyRow(content, "qa40x.settings.capability",      info.capability());
        addReadOnlyRow(content, "qa40x.settings.capability2",     info.capability2());

        Button i2sToggle = new Button(content, SWT.TOGGLE);
        i2sToggle.setText(I18n.t("qa40x.settings.i2s"));
        i2sToggle.setToolTipText(I18n.t("qa40x.settings.i2s.tooltip"));
        i2sToggle.setSelection(i2sEnabled);
        GridData toggleData = new GridData(SWT.LEAD, SWT.CENTER, true, false);
        toggleData.horizontalSpan = 2;
        i2sToggle.setLayoutData(toggleData);

        Composite buttonBar = new Composite(content, SWT.NONE);
        GridData barData = new GridData(SWT.END, SWT.CENTER, true, false);
        barData.horizontalSpan = 2;
        buttonBar.setLayoutData(barData);
        RowLayout buttonLayout = new RowLayout(SWT.HORIZONTAL);
        buttonLayout.spacing = BUTTON_SPACING;
        buttonBar.setLayout(buttonLayout);

        Button okButton     = new Button(buttonBar, SWT.PUSH);
        Button cancelButton = new Button(buttonBar, SWT.PUSH);
        okButton.setText(I18n.t("common.ok"));
        cancelButton.setText(I18n.t("common.cancel"));
        dialog.setDefaultButton(okButton);

        // Seeded so a Cancel — which never fires the OK listener — returns the
        // value the dialog opened with.
        accepted = i2sEnabled;
        okButton.addListener(SWT.Selection, e -> {
            accepted = i2sToggle.getSelection();
            dialog.close();
        });
        cancelButton.addListener(SWT.Selection, e -> dialog.close());
    }

    /** One {@code label → read-only value} row of the device panel.  A read-only
     *  {@link Text} rather than a {@link Label} so the value can be selected and
     *  copied — handy when quoting a serial or a capability word. */
    private void addReadOnlyRow(Composite parentComposite, String labelKey, String value) {
        Label caption = new Label(parentComposite, SWT.NONE);
        caption.setText(I18n.t(labelKey));
        caption.setLayoutData(new GridData(SWT.LEAD, SWT.CENTER, false, false));

        Text field = new Text(parentComposite, SWT.SINGLE | SWT.BORDER | SWT.READ_ONLY);
        field.setText(value);
        field.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    }
}
