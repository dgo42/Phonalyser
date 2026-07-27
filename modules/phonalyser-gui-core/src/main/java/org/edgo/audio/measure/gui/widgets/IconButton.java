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
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;

/**
 * Owner-drawn icon button — a {@link Canvas} that paints a centred
 * {@link Image} and fires {@code SWT.Selection} on click.
 *
 * <p>Used instead of a native SWT {@code Button} for icon-only buttons because
 * macOS native buttons cap their content height below our icon size and clip
 * the icon on top (the Record LED renders as a half-dome).  A {@code Canvas}
 * has no native bezel, so the full icon shows on every platform — the same
 * reason the step caret is an {@link IconStepLabel}.
 *
 * <p>Pass {@code SWT.TOGGLE} for a latching button (e.g. Record): it tracks a
 * selected state exposed via {@link #getSelection()} / {@link #setSelection},
 * mirroring the slice of {@code Button} API the panes use; the caller swaps the
 * image on the selection event exactly as before.  {@code SWT.PUSH} (or no
 * style bit) gives a momentary button.
 */
public final class IconButton extends Canvas {

    private final boolean toggle;
    private Image         image;
    private boolean       selected;

    public IconButton(Composite parent, int style) {
        super(parent, SWT.NONE);
        this.toggle = (style & SWT.TOGGLE) != 0;
        setBackground(parent.getBackground());

        addPaintListener(e -> {
            Rectangle ca = getClientArea();
            e.gc.setBackground(getBackground());
            e.gc.fillRectangle(ca);
            if (image != null && !image.isDisposed()) {
                Rectangle ib = image.getBounds();
                e.gc.drawImage(image,
                        Math.max(0, (ca.width  - ib.width)  / 2),
                        Math.max(0, (ca.height - ib.height) / 2));
            }
        });
        addListener(SWT.MouseDown, e -> {
            if (e.button != 1 || !isEnabled()) return;
            if (toggle) {
                selected = !selected;
                redraw();
            }
            Event sel = new Event();
            sel.widget = this;
            notifyListeners(SWT.Selection, sel);
        });
    }

    /** Sets the icon to paint (centred).  The caller owns the image lifecycle. */
    public void setImage(Image image) {
        this.image = image;
        redraw();
    }

    /** Current latched state (always {@code false} for a non-toggle button). */
    public boolean getSelection() {
        return selected;
    }

    /** Sets the latched state (no-op for a non-toggle button). */
    public void setSelection(boolean selected) {
        if (!toggle) return;
        this.selected = selected;
        redraw();
    }

    @Override
    public Point computeSize(int wHint, int hHint, boolean changed) {
        int w = wHint != SWT.DEFAULT ? wHint : (image != null ? image.getBounds().width  : 0);
        int h = hHint != SWT.DEFAULT ? hHint : (image != null ? image.getBounds().height : 0);
        return new Point(Math.max(w, 1), Math.max(h, 1));
    }

    @Override
    protected void checkSubclass() {
        // SWT forbids subclassing of most widgets by default — opt back in.
    }
}
