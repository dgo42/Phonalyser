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
