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
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.edgo.audio.measure.gui.common.IconUtils;

/**
 * Click-able SVG-icon "arrow" label used by {@link NumericStepField}'s
 * step buttons and {@code SignalFormCombo}'s drop caret.
 * Replaces the native {@code SWT.ARROW | UP/DOWN} Button which renders
 * inconsistently across GTK / win32.
 *
 * <p>Implemented as a {@link Canvas} subclass with a custom paint
 * listener rather than a {@code Label} / {@code CLabel} — both of the
 * latter have GTK quirks with image alignment and image-swap repaint
 * that produced vanishing icons.  Drawing manually with
 * {@code GC#drawImage} is identical on every platform.
 *
 * <p>The widget has:
 * <ul>
 *   <li>no border, no rounded corners — sits flush on the parent's
 *       background (we explicitly copy the parent's background colour);</li>
 *   <li>two pre-rendered icons (normal + pressed); the pressed size can be
 *       larger <em>or</em> smaller than the normal size, or equal to
 *       disable press animation entirely;</li>
 *   <li>mouse-down / mouse-up / mouse-exit listeners that switch between
 *       the two images via a redraw flag.</li>
 * </ul>
 *
 * <p>The {@link Image} instances are cached by {@link IconUtils} and
 * disposed when the main shell tears down — not here.
 *
 * <p>Click semantics (step, toggle popup, …) are left to the caller —
 * add another {@code SWT.MouseDown} or {@code SWT.MouseUp} listener.
 */
public final class IconStepLabel extends Canvas {

    private final Image   normal;
    private final Image   pressed;
    private       boolean isPressed;

    /**
     * Builds the arrow.  {@code normalPx} / {@code pressedPx} are
     * <em>widths</em> — height is derived from the icon's bounding-box
     * aspect ratio (these spinner / caret icons are wider than tall).
     * Pass the same value for both to disable press animation.
     */
    public IconStepLabel(Composite parent, String svgResource,
                         int normalPx, int pressedPx, RGB tint) {
        super(parent, SWT.NO_BACKGROUND);
        Display d = parent.getDisplay();
        IconUtils icons = IconUtils.instance();
        this.normal  = icons.renderAtWidth(d, svgResource, normalPx, tint);
        this.pressed = (normalPx == pressedPx)
                ? this.normal
                : icons.renderAtWidth(d, svgResource, pressedPx, tint);
        setBackground(parent.getBackground());

        addPaintListener(e -> {
            Image img = isPressed ? pressed : normal;
            Rectangle ib = img.getBounds();
            Rectangle cb = getClientArea();
            // Wipe the cell first so a smaller image swapped in over a
            // larger one doesn't leave fragments.
            e.gc.setBackground(getBackground());
            e.gc.fillRectangle(cb);
            int x = Math.max(0, (cb.width  - ib.width)  / 2);
            int y = Math.max(0, (cb.height - ib.height) / 2);
            e.gc.drawImage(img, x, y);
        });

        if (normal != pressed) {
            addListener(SWT.MouseDown, e -> {
                if (e.button != 1 || isDisposed()) return;
                isPressed = true;
                redraw();
            });
            Listener restore = e -> {
                if (isDisposed()) return;
                isPressed = false;
                redraw();
            };
            addListener(SWT.MouseUp,   restore);
            addListener(SWT.MouseExit, restore);
        }
    }

    @Override
    protected void checkSubclass() {
        // SWT forbids subclassing of most widgets by default — opt back in.
    }
}
