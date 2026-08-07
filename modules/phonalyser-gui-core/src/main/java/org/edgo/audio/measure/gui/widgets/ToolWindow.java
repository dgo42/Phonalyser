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

package org.edgo.audio.measure.gui.widgets;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.edgo.audio.measure.gui.common.Icon;
import org.edgo.audio.measure.gui.common.ShellIcons;

/**
 * A reusable extracted-table tool window - a {@code DIALOG_TRIM} {@link Shell} hosting a
 * {@code DOUBLE_BUFFERED} {@link Canvas} (with an optional top button {@link Toolbar}).
 * Shared by the FFT view (THD / IMD) and the scope view (measurements), which each
 * otherwise duplicated the same shell / canvas plumbing.
 *
 * <p>Encapsulated, with a strictly one-way data flow:
 * <ul>
 *   <li><b>owner -> window:</b> create it + its colours ({@code ctor}), set its
 *       {@link #setSize size} and {@link #setLocation position}, and register a
 *       {@link #setPainter painter} that draws the table straight into the canvas GC.</li>
 *   <li><b>window -> owner:</b> it signals back the two events the owner must act on -
 *       the window was {@link #addCloseListener closed}, and a {@link #addButton button}
 *       (e.g. reset statistics) was clicked.</li>
 * </ul>
 * Nothing internal (shell, canvas, toolbar) is exposed.
 */
public final class ToolWindow {

    /** Draws the window's content into {@code gc} starting at {@code top} - the y just below
     *  the button row (0 when there are no buttons).  Registered via {@link #setPainter};
     *  the window invokes it on every canvas paint, so the owner draws straight into the GC. */
    @FunctionalInterface
    public interface ContentPainter {
        void paint(GC gc, int top);
    }

    /** Creates the window's content control.  Supplied when the content is a VIEW
     *  in its own right - with its own palette, buttons and paint listener - rather
     *  than something the owner draws through {@link #setPainter}.  The parent is
     *  handed out for exactly this and nothing else; the shell stays private. */
    @FunctionalInterface
    public interface ContentFactory {
        Canvas create(Composite parent);
    }

    private static final int CONTENT_GAP = 2;   // px between the button row and the table
    /** Floor for a resizable window, so it cannot be dragged down to nothing. */
    private static final int MIN_W = 220;
    private static final int MIN_H = 160;

    private final Shell  shell;
    private final Canvas canvas;
    private final int    buttonWidth;
    private final int    buttonHeight;
    private Toolbar        toolbar;
    private ContentPainter painter;

    /** Fixed-size window - the historical behaviour, and what a table of
     *  fixed-pixel columns wants. */
    public ToolWindow(Control owner, Color background, Color text, int buttonWidth, int buttonHeight) {
        this(owner, background, text, buttonWidth, buttonHeight, false);
    }

    /**
     * @param resizable when true the shell gains {@link SWT#RESIZE} and a minimum
     *        size, and the canvas repaints on resize.  Opt-in on purpose: a window
     *        whose painter lays out fixed-pixel columns has nothing to do with the
     *        extra space, and the owner's next {@code setSize} would silently undo
     *        the user's drag.  A window that scales its content - the amplitude
     *        histogram - passes true.
     */
    public ToolWindow(Control owner, Color background, Color text,
                      int buttonWidth, int buttonHeight, boolean resizable) {
        this(owner, background, text, buttonWidth, buttonHeight, resizable,
             parent -> new Canvas(parent, SWT.DOUBLE_BUFFERED));
    }

    /** As above, but hosting a content control the owner builds - see
     *  {@link ContentFactory}. */
    public ToolWindow(Control owner, Color background, Color text,
                      int buttonWidth, int buttonHeight, boolean resizable,
                      ContentFactory content) {
        this.buttonWidth     = buttonWidth;
        this.buttonHeight    = buttonHeight;
        // DIALOG_TRIM = TITLE | CLOSE | BORDER; RESIZE adds the drag border + maximise.
        shell = new Shell(owner.getShell(), SWT.DIALOG_TRIM | (resizable ? SWT.RESIZE : SWT.NONE));
        ShellIcons.apply(shell);
        shell.setLayout(new FillLayout());
        canvas = content.create(shell);
        canvas.setBackground(background);
        canvas.setForeground(text);
        canvas.addPaintListener(this::onPaint);
        if (resizable) {
            shell.setMinimumSize(MIN_W, MIN_H);
            // Windows repaints only the newly exposed strip after a resize, which
            // leaves a content that scales with the window looking torn; force the
            // whole canvas.  Harmless where the size never changes.
            canvas.addListener(SWT.Resize, e -> canvas.redraw());
        }
    }

    private void onPaint(PaintEvent e) {
        if (painter != null) {
            painter.paint(e.gc, contentTop());
        }
    }

    private int contentTop() {
        return (toolbar != null) ? buttonHeight + CONTENT_GAP : 0;
    }

    public void setTitle(String title) {
        shell.setText(title);
    }

    /** Owner sets the table's content size; the window adds the button row + window trim. */
    public void setSize(int contentWidth, int contentHeight) {
        Rectangle trim = shell.computeTrim(0, 0, contentWidth, contentTop() + contentHeight);
        shell.setSize(trim.width, trim.height);
    }

    /** Owner places the window (e.g. beside the main view). */
    public void setLocation(int x, int y) {
        shell.setLocation(x, y);
    }

    /** Owner registers how to draw the content (its table renderer); takes effect on the
     *  next {@link #redraw()}. */
    public void setPainter(ContentPainter painter) {
        this.painter = painter;
    }

    /** Owner reacts to the user closing the window (un-extract). */
    public void addCloseListener(Listener onClose) {
        shell.addListener(SWT.Close, e -> { e.doit = false; onClose.handleEvent(e); });
    }

    /** Adds a top-row button (e.g. the scope's stats-toggle / reset).  A toggle uses the
     *  window's text colour for its icon; a push uses {@code accent} (e.g. red reset).
     *  {@code onClick} ({@link SWT#Selection}) signals the owner to act. */
    public void addButton(Icon normal, Icon active, boolean toggle, boolean on,
                          Color accent, String tooltip, Listener onClick) {
        ToolButton b = toggle
                ? toolbar().toggleButton(normal, active, accent, tooltip, on)
                : toolbar().pushButton(normal, active, accent, tooltip);
        b.addListener(SWT.Selection, onClick);
        toolbar.setSize(toolbar.computeSize(SWT.DEFAULT, SWT.DEFAULT));
    }

    /**
     * Adds a top-row L/R-style channel button and HANDS THE HANDLE BACK - unlike
     * {@link #addButton}, because the owner has to keep driving it: a channel
     * switched off in the main view must grey and block its button here too, which
     * only the owner knows about.  Buttons sharing a {@code group} behave as a
     * radio set; use a name of your own rather than another view's, since the
     * grouping is resolved among siblings of one parent.
     */
    public ToolButton addChanButton(String label, Color textColor, Color frame, Color fill,
                                    Font font, String tooltip, boolean selected, String group,
                                    Listener onClick) {
        ToolButton b = toolbar().chanButton(label, textColor, frame, fill, font, tooltip, selected, group);
        b.addListener(SWT.Selection, onClick);
        toolbar.setSize(toolbar.computeSize(SWT.DEFAULT, SWT.DEFAULT));
        return b;
    }

    /** The button row, created on first use so a window without buttons has none
     *  (and {@link #contentTop()} stays 0). */
    private Toolbar toolbar() {
        if (toolbar == null) {
            toolbar = new Toolbar(canvas, buttonWidth, buttonHeight);
            toolbar.setLocation(0, 0);
        }
        return toolbar;
    }

    /**
     * The area the painter may draw in: the canvas client area with the button row
     * already removed from the top.  For a content that scales with the window -
     * where {@code gc.getClipping()} is the damage rectangle, not the drawing
     * surface, and is the wrong thing to lay out against.
     */
    public Rectangle getContentArea() {
        Rectangle r = canvas.getClientArea();
        int top = contentTop();
        return new Rectangle(r.x, r.y + top, r.width, Math.max(0, r.height - top));
    }

    public void open() {
        if (!shell.getVisible()) {
            shell.open();
        }
    }

    public void close() {
        if (!shell.isDisposed()) {
            shell.setVisible(false);
        }
    }

    public boolean isOpen() {
        return !shell.isDisposed() && shell.getVisible();
    }

    /** Repaints the table - call from the owner's {@code redraw} to track the main view. */
    public void redraw() {
        if (!canvas.isDisposed()) {
            canvas.redraw();
        }
    }

    /** The shell's current outer size - the owner reads it back to place the window. */
    public Point getSize() {
        return shell.getSize();
    }

    public void dispose() {
        if (!shell.isDisposed()) {
            shell.dispose();
        }
    }
}
