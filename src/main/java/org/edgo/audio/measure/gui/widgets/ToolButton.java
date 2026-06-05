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
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;

import org.edgo.audio.measure.gui.common.ColorUtil;
import org.edgo.audio.measure.gui.common.IconUtils;

import lombok.Getter;
import lombok.Setter;

/**
 * A transparent, self-painting tool button — the widget replacement for the views'
 * canvas-drawn, Hotspot-hit-tested buttons.  Shows a centred icon (rendered INTERNALLY
 * in a light variant for the normal background and a dark variant for the filled one)
 * or a text label, inside an optional rounded frame.
 *
 * <p>Pressing inverts it — fill + dark content — for feedback; a {@link #setToggle(boolean)
 * toggle} button latches that look, its state flipping on mouse-down.  Fires an
 * {@link SWT#Selection} event on click; add a handler with
 * {@code addListener(SWT.Selection, …)} and read {@link #isToggled()} from it.  The
 * cursor is {@link SWT#CURSOR_HAND}; set the tooltip with the inherited
 * {@code setToolTipText}.  Created {@code SWT.NO_BACKGROUND | SWT.TRANSPARENT} so the
 * plot shows through the corners.
 */
public final class ToolButton extends TransparentComposite {

    private static final int CORNER_RADIUS = 4;

    private Image   lightIcon;       // content on the normal (framed/transparent) background
    private Image   darkIcon;        // content on the filled (active) background
    private String  label;           // text content, used when no icon is set
    private Color   contentColor;    // label colour (same in both states)
    private Color   frameColor;      // normal-state rounded frame
    private Color   fillColor;       // active-state fill
    private boolean drawFrame = true;
    /** Latching toggle (keeps the pressed look) vs momentary push. */
    @Setter private boolean toggle;
    @Getter private boolean toggled;
    private boolean pressed;         // mouse held down on the button
    /** Radio group NAME — same-named sibling {@code ToolButton}s (same parent) are
     *  mutually exclusive: clicking one selects it and clears the rest, and each change
     *  fires {@link SWT#Selection} (so a radio handler should act only when
     *  {@link #isToggled()}).  {@code null} = standalone. */
    @Getter @Setter private String group;

    public ToolButton(Composite parent) {
        super(parent);
        setCursor(getDisplay().getSystemCursor(SWT.CURSOR_HAND));
        addPaintListener(this::onPaint);
        addListener(SWT.MouseDown, e -> {
            pressed = true;
            if (group != null) {
                for (Control c : getParent().getChildren()) {   // radio: clear same-named siblings
                    if (c instanceof ToolButton b && group.equals(b.group)) {
                        b.setToggled(b == this);   // each setToggled fires Selection if it changed
                    }
                }
            } else if (toggle) {
                setToggled(!toggled);          // latch on press; fires Selection on change
            } else {
                notifyListeners(SWT.Selection, new Event());   // momentary push: no toggle state
            }
            redraw();
        });
        addListener(SWT.MouseUp, e -> {
            pressed = false;
            redraw();
        });
        addListener(SWT.MouseExit, e -> {
            if (pressed) {                 // mouse left while held → drop the press feedback
                pressed = false;
                redraw();
            }
        });
    }

    private void onPaint(PaintEvent e) {
        GC gc = e.gc;
        Rectangle b = getClientArea();
        int rw = b.width - 1;
        int rh = b.height - 1;
        boolean active = toggled || pressed;
        // Active label + border auto-contrast to absolute black/white by the fill's
        // brightness — theme-independent (the FFT's light bg and the scope's black bg alike).
        Color contrast = active
                ? getDisplay().getSystemColor(ColorUtil.isDark(fillColor) ? SWT.COLOR_WHITE : SWT.COLOR_BLACK)
                : null;
        if (active) {
            if (fillColor != null) {
                gc.setBackground(fillColor);
                gc.fillRoundRectangle(0, 0, rw, rh, CORNER_RADIUS, CORNER_RADIUS);
            }
            if (label != null) {   // outline label buttons; icon buttons invert via the dark variant
                gc.setForeground(contrast);
                gc.drawRoundRectangle(0, 0, rw, rh, CORNER_RADIUS, CORNER_RADIUS);
            }
        } else if (drawFrame && frameColor != null) {
            gc.setForeground(frameColor);
            gc.drawRoundRectangle(0, 0, rw, rh, CORNER_RADIUS, CORNER_RADIUS);
        }
        Image icon = (active && darkIcon != null) ? darkIcon : lightIcon;
        if (icon != null && !icon.isDisposed()) {
            Rectangle ib = icon.getBounds();
            gc.drawImage(icon, (b.width - ib.width) / 2, (b.height - ib.height) / 2);
        } else if (label != null) {
            Color lc = active ? contrast : contentColor;
            if (lc != null) {
                gc.setForeground(lc);
                Point ext = gc.textExtent(label);
                gc.drawText(label, (b.width - ext.x) / 2, (b.height - ext.y) / 2, true);
            }
        }
    }

    /** Renders the icon INTERNALLY: {@code svgPath} at {@code iconHeight} px in the
     *  {@code normal} (normal-background) and {@code active} (filled-background) RGBs.
     *  The images come from {@link IconUtils}' shared cache — borrowed, NEVER disposed here
     *  (IconUtils owns them and disposes the whole cache at shell teardown).  Disposing them
     *  per-button would blank the same icon on every other button sharing it. */
    public void setIcon(String svgPath, int iconHeight, RGB normal, RGB active) {
        IconUtils icons = IconUtils.instance();
        lightIcon = icons.renderAtHeight(getDisplay(), svgPath, iconHeight, normal);
        darkIcon  = icons.renderAtHeight(getDisplay(), svgPath, iconHeight, active);
        label = null;
        redraw();
    }

    /** Renders ONE colour-preserving icon (the SVG's own colours), shown unchanged in
     *  both states — for toggles whose glyph must not invert (e.g. the phase button).
     *  Borrowed from {@link IconUtils}' shared cache, never disposed here. */
    public void setColoredIcon(String svgPath, int iconHeight) {
        lightIcon = IconUtils.instance().renderAtHeightColored(getDisplay(), svgPath, iconHeight);
        darkIcon  = lightIcon;   // same image in both states
        label = null;
        redraw();
    }

    /** Text content (e.g. an L/R channel button); colour is constant in both states. */
    public void setLabel(String text, Color color) {
        this.label = text;
        this.contentColor = color;
        redraw();
    }

    /** Frame colour (normal state) and fill colour (active state).  The active label +
     *  border auto-contrast to black/white by the fill's brightness — no invert needed. */
    public void setColors(Color frame, Color fill) {
        this.frameColor = frame;
        this.fillColor  = fill;
        redraw();
    }

    public void setDrawFrame(boolean v) {
        this.drawFrame = v;
        redraw();
    }

    public void setToggled(boolean v) {
        if (this.toggled != v) {
            this.toggled = v;
            redraw();
            notifyListeners(SWT.Selection, new Event());   // notify on ANY toggle change
        }
    }
}
