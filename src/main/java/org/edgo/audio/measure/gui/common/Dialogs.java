package org.edgo.audio.measure.gui.common;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * Small helpers for the recurring {@code new MessageBox(parent, …); setText;
 * setMessage; open;} pattern.  Centralises styling and call-site shape so
 * the pane code says <em>what</em> kind of dialog it wants, not <em>how</em>
 * to build one.
 *
 * <p>Each helper opens the dialog synchronously and returns the SWT result
 * code from {@link MessageBox#open()} (e.g. {@link SWT#OK}, {@link SWT#YES},
 * {@link SWT#NO}).  The {@link #error} and {@link #info} variants return the
 * code without naming it, since OK is the only option.
 */
public final class Dialogs {

    private Dialogs() {}

    /** Modal error dialog: ICON_ERROR + OK.  Both title and message are
     *  shown verbatim — caller is responsible for {@code I18n.t(...)}
     *  lookup. */
    public static int error(Shell parent, String title, String message) {
        return show(parent, SWT.ICON_ERROR | SWT.OK, title, message);
    }

    /** Modal info dialog: ICON_INFORMATION + OK. */
    public static int info(Shell parent, String title, String message) {
        return show(parent, SWT.ICON_INFORMATION | SWT.OK, title, message);
    }

    /** Modal yes/no dialog: ICON_QUESTION + YES + NO.  Returns
     *  {@link SWT#YES} or {@link SWT#NO}. */
    public static int confirm(Shell parent, String title, String message) {
        return show(parent, SWT.ICON_QUESTION | SWT.YES | SWT.NO, title, message);
    }

    private static int show(Shell parent, int style, String title, String message) {
        MessageBox mb = new MessageBox(parent, style);
        if (title   != null) mb.setText(title);
        if (message != null) mb.setMessage(message);
        return mb.open();
    }

    /**
     * Modal single-line text-input dialog.  Returns the entered text on
     * OK or {@code null} on Cancel / close.  {@code initialValue} pre-fills
     * the text field and is selected so the user can overtype immediately.
     *
     * <p>Built by hand (no SWT InputDialog widget exists) so the look
     * matches the rest of the app — small Shell with a label, a Text, and
     * OK / Cancel buttons.
     */
    public static String promptString(Shell parent, String title,
                                      String prompt, String initialValue) {
        Shell dialog = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        if (title != null) dialog.setText(title);
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth  = 16;
        gl.marginHeight = 12;
        gl.verticalSpacing = 8;
        dialog.setLayout(gl);

        if (prompt != null) {
            Label promptLabel = new Label(dialog, SWT.WRAP);
            promptLabel.setText(prompt);
            promptLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        }

        Text field = new Text(dialog, SWT.BORDER | SWT.SINGLE);
        if (initialValue != null) {
            field.setText(initialValue);
            field.selectAll();
        }
        GridData fd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        fd.widthHint = 220;
        field.setLayoutData(fd);

        Composite buttons = new Composite(dialog, SWT.NONE);
        GridLayout bL = new GridLayout(2, true);
        bL.marginWidth = 0; bL.marginHeight = 0;
        buttons.setLayout(bL);
        buttons.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));

        Button cancel = new Button(buttons, SWT.PUSH);
        cancel.setText("Cancel");
        cancel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button ok = new Button(buttons, SWT.PUSH);
        ok.setText("OK");
        ok.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        dialog.setDefaultButton(ok);

        final String[] result = { null };
        ok.addListener(SWT.Selection, e -> {
            result[0] = field.getText();
            dialog.close();
        });
        cancel.addListener(SWT.Selection, e -> dialog.close());

        dialog.pack();
        if (parent != null) {
            int x = parent.getLocation().x + (parent.getSize().x - dialog.getSize().x) / 2;
            int y = parent.getLocation().y + (parent.getSize().y - dialog.getSize().y) / 2;
            dialog.setLocation(x, y);
        }
        dialog.open();
        Display d = dialog.getDisplay();
        while (!dialog.isDisposed()) {
            if (!d.readAndDispatch()) d.sleep();
        }
        return result[0];
    }
}
