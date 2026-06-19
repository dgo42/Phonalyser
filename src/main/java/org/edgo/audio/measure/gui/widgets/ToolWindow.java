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
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.edgo.audio.measure.gui.common.Icon;

/**
 * A reusable extracted-table tool window — a {@code DIALOG_TRIM} {@link Shell} hosting a
 * {@code DOUBLE_BUFFERED} {@link Canvas} (with an optional top button {@link Toolbar}).
 * Shared by the FFT view (THD / IMD) and the scope view (measurements), which each
 * otherwise duplicated the same shell / canvas plumbing.
 *
 * <p>Encapsulated, with a strictly one-way data flow:
 * <ul>
 *   <li><b>owner → window:</b> create it + its colours ({@code ctor}), set its
 *       {@link #setSize size} and {@link #setLocation position}, and register a
 *       {@link #setPainter painter} that draws the table straight into the canvas GC.</li>
 *   <li><b>window → owner:</b> it signals back the two events the owner must act on —
 *       the window was {@link #addCloseListener closed}, and a {@link #addButton button}
 *       (e.g. reset statistics) was clicked.</li>
 * </ul>
 * Nothing internal (shell, canvas, toolbar) is exposed.
 */
public final class ToolWindow {

    /** Draws the window's content into {@code gc} starting at {@code top} — the y just below
     *  the button row (0 when there are no buttons).  Registered via {@link #setPainter};
     *  the window invokes it on every canvas paint, so the owner draws straight into the GC. */
    @FunctionalInterface
    public interface ContentPainter {
        void paint(GC gc, int top);
    }

    private static final int CONTENT_GAP = 2;   // px between the button row and the table

    private final Shell  shell;
    private final Canvas canvas;
    private final int    buttonWidth;
    private final int    buttonHeight;
    private Toolbar        toolbar;
    private ContentPainter painter;

    public ToolWindow(Control owner, Color background, Color text, int buttonWidth, int buttonHeight) {
        this.buttonWidth     = buttonWidth;
        this.buttonHeight    = buttonHeight;
        // DIALOG_TRIM = TITLE | CLOSE | BORDER, no resize — the owner sizes it explicitly.
        shell = new Shell(owner.getShell(), SWT.DIALOG_TRIM);
        shell.setLayout(new FillLayout());
        canvas = new Canvas(shell, SWT.DOUBLE_BUFFERED);
        canvas.setBackground(background);
        canvas.setForeground(text);
        canvas.addPaintListener(this::onPaint);
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
        if (toolbar == null) {
            toolbar = new Toolbar(canvas, buttonWidth, buttonHeight);
            toolbar.setLocation(0, 0);
        }
        ToolButton b = toggle
                ? toolbar.toggleButton(normal, active, accent, tooltip, on)
                : toolbar.pushButton(normal, active, accent, tooltip);
        b.addListener(SWT.Selection, onClick);
        toolbar.setSize(toolbar.computeSize(SWT.DEFAULT, SWT.DEFAULT));
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

    /** Repaints the table — call from the owner's {@code redraw} to track the main view. */
    public void redraw() {
        if (!canvas.isDisposed()) {
            canvas.redraw();
        }
    }

    /** The shell's current outer size — the owner reads it back to place the window. */
    public Point getSize() {
        return shell.getSize();
    }

    public void dispose() {
        if (!shell.isDisposed()) {
            shell.dispose();
        }
    }
}
