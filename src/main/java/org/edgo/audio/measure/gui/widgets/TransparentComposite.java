package org.edgo.audio.measure.gui.widgets;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.RowData;
import org.eclipse.swt.widgets.Composite;

/**
 * A transparent composite that paints nothing — created
 * {@code SWT.NO_BACKGROUND | SWT.TRANSPARENT} so the plot behind it shows through, with
 * SWT's subclass check opted out.  Serves two roles: the shared base for the overlay
 * widgets ({@link ToolButton}, {@link Toolbar}, {@link BlinkBanner}), and — used bare —
 * a do-nothing spacer in a {@link Toolbar} row.
 *
 * <p>Single inheritance is enough to share this boilerplate; there's nothing here for
 * multiple inheritance to add.
 */
public class TransparentComposite extends Composite {

    public TransparentComposite(Composite parent) {
        super(parent, SWT.NO_BACKGROUND | SWT.TRANSPARENT);
    }

    /** RowLayout helper: excludes (or re-includes) this widget from its row and hides
     *  (or shows) it — call then re-flow the parent toolbar. */
    public void setExcluded(boolean excluded) {
        ((RowData) getLayoutData()).exclude = excluded;
        setVisible(!excluded);
    }

    @Override
    protected void checkSubclass() {
        // SWT forbids subclassing of most widgets by default — opt back in.
    }
}
