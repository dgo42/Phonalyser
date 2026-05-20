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
 * Tiny factory for click-able SVG-icon "arrow" labels used by
 * {@link StepSelector}, {@code NumericStepField} and {@code SignalFormCombo}'s
 * drop caret.  Replaces the native {@code SWT.ARROW | UP/DOWN} Button
 * which renders inconsistently across GTK / win32.
 *
 * <p>Implemented as a {@link Canvas} with a custom paint listener rather
 * than a {@code Label} / {@code CLabel} — both of the latter have GTK
 * quirks with image alignment and image-swap repaint that produced
 * vanishing icons.  Drawing manually with {@link org.eclipse.swt.graphics.GC#drawImage}
 * is identical on every platform.
 *
 * <p>The returned widget has:
 * <ul>
 *   <li>no border, no rounded corners — sits flush on the parent's
 *       background (we explicitly copy the parent's background colour);</li>
 *   <li>two pre-rendered icons (normal + pressed); the pressed size can be
 *       larger <em>or</em> smaller than the normal size, or equal to
 *       disable press animation entirely;</li>
 *   <li>mouse-down / mouse-up / mouse-exit listeners that switch between
 *       the two images via a redraw flag — no widget-property gymnastics;</li>
 *   <li>a dispose listener that releases both {@link Image}s.</li>
 * </ul>
 *
 * <p>Click semantics (step, toggle popup, …) are left to the caller — just
 * add another {@code SWT.MouseDown} or {@code SWT.MouseUp} listener.
 */
public final class IconStepLabel {

    private IconStepLabel() {}

    /** Build the arrow.  {@code normalPx} / {@code pressedPx} are
     *  <em>widths</em> — height is derived from the icon's bounding-box
     *  aspect ratio (these spinner / caret icons are wider than tall).
     *  Pass the same value for both to disable press animation. */
    public static Canvas create(Composite parent, String svgResource,
                                int normalPx, int pressedPx, RGB tint) {
        Display d = parent.getDisplay();
        IconUtils icons = IconUtils.instance();
        final Image normal  = icons.renderAtWidth(d, svgResource, normalPx,  tint);
        final Image pressed = (normalPx == pressedPx)
                ? normal
                : icons.renderAtWidth(d, svgResource, pressedPx, tint);

        // Hold the press flag in a single-element array so the paint
        // listener (a lambda) can see the mutating value without us
        // needing a named class.
        final boolean[] isPressed = { false };

        Canvas c = new Canvas(parent, SWT.NO_BACKGROUND);
        c.setBackground(parent.getBackground());

        c.addPaintListener(e -> {
            Image img = isPressed[0] ? pressed : normal;
            Rectangle ib = img.getBounds();
            Rectangle cb = c.getClientArea();
            // Wipe the cell first so a smaller image swapped in over a
            // larger one doesn't leave fragments.
            e.gc.setBackground(c.getBackground());
            e.gc.fillRectangle(cb);
            int x = Math.max(0, (cb.width  - ib.width)  / 2);
            int y = Math.max(0, (cb.height - ib.height) / 2);
            e.gc.drawImage(img, x, y);
        });

        if (normal != pressed) {
            c.addListener(SWT.MouseDown, e -> {
                if (e.button != 1 || c.isDisposed()) return;
                isPressed[0] = true;
                c.redraw();
            });
            Listener restore = e -> {
                if (c.isDisposed()) return;
                isPressed[0] = false;
                c.redraw();
            };
            c.addListener(SWT.MouseUp,   restore);
            c.addListener(SWT.MouseExit, restore);
        }

        // Cached Image instances are owned by IconUtils — disposal happens
        // when the main shell is torn down, not here.
        return c;
    }
}
