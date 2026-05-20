package org.edgo.audio.measure.gui.common;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;

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
}
