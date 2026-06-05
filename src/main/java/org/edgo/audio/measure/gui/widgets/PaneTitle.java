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

import lombok.Getter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Transform;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;

/**
 * Clickable pane title bar.  Owns its collapsed / expanded state, the
 * two text variants, and the leading arrow it paints next to the text
 * (▼ when expanded, ▶ when collapsed).  Paints horizontally by default;
 * when the host container is narrower than the title's horizontal
 * extent (e.g. the pane has been collapsed inside a horizontal
 * SashForm) the same row is rotated 90° counter-clockwise so it
 * remains readable in the narrow strip.
 *
 * <h2>Click behaviour</h2>
 * <p>Each click toggles the internal {@link #collapsed} flag, swaps the
 * painted text + arrow, and publishes {@code Events.paneTitleClick(id)}
 * on the {@link MessageBus} with the NEW collapsed state as the
 * {@code Boolean} payload.  Subscribers (typically the owning pane's
 * host tab) pick their title by ID — each pane uses a distinct
 * {@code PANE_ID_*} constant from {@link Events}.
 *
 * <p>Programmatic state changes (e.g. restoring a saved collapse state
 * at startup) go through {@link #setCollapsed(boolean)}, which updates
 * the displayed text + arrow silently — no bus event fires, since the
 * caller already knows the state it just set.
 *
 * <h2>Why a custom Canvas?</h2>
 * <ul>
 *   <li>On GTK the legacy {@code Group} title is rendered by a
 *       GtkFrame label widget that consumes clicks itself, so they
 *       never reach SWT listeners.  Putting the title in the content
 *       area as a widget the application owns fixes click delivery.</li>
 *   <li>A horizontally-laid Label is unreadable in the narrow
 *       collapsed strip; rotating to vertical fits.</li>
 *   <li>The Unicode triangle glyphs often fall back to monochrome
 *       bitmaps when drawn as text; we render them as antialiased
 *       filled polygons so they look identical on every platform.</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>
 *   Composite group = new Composite(parent, SWT.BORDER);
 *   group.setLayout(new GridLayout(1, false));
 *   PaneTitle title = new PaneTitle(group, Events.PANE_ID_GENERATOR,
 *           I18n.t("generator.title.expanded"),
 *           I18n.t("generator.title.collapsed"),
 *           I18n.t("generator.pane.toggle.tooltip"));
 *
 *   MessageBus.instance().subscribe(
 *           Events.paneTitleClick(Events.PANE_ID_GENERATOR),
 *           (Boolean nowCollapsed) -> onToggleCollapse(nowCollapsed));
 *
 *   // Programmatic (saved-state restore):
 *   title.setCollapsed(true);
 * </pre>
 */
public final class PaneTitle extends Canvas {

    private static final int PAD_X     = 4;
    private static final int PAD_Y     = 2;
    /** Fixed arrow-polygon size in pixels.  Independent of the canvas
     *  font so every pane's arrow renders at the same visual weight. */
    private static final int ARROW_PX  = 12;
    /** Gap (in pixels) between the arrow and the text that follows it. */
    private static final int ARROW_GAP = 4;

    @Getter private final int id;
    private final String expandedText;
    private final String collapsedText;
    private final String clickEventName;
    /** When true, the title behaves as a static label: no arrow is drawn,
     *  clicking does nothing, and the hand cursor is suppressed.  Used by
     *  panes that are not collapsible (e.g. Frequency Response). */
    private boolean staticMode;
    /** True when the title is in its collapsed-pane appearance.
     *  Drives both the displayed text and the leading arrow glyph
     *  (▶ when {@code true}, ▼ when {@code false}). */
    @Getter private boolean collapsed;

    /**
     * Adds a title bar as the first child of {@code container}.  When
     * the container is a legacy {@link Group} its chrome title is also
     * cleared so we don't end up with two visible titles.  Spans every
     * column of the host's {@link GridLayout} (or one column when the
     * host isn't using GridLayout).
     *
     * @param id              identifier published on the bus when the
     *                        title is clicked.  Subscribers route by ID
     *                        — see {@link Events#PANE_ID_GENERATOR} etc.
     * @param expandedText    text painted while expanded.  Any leading
     *                        ▼ / ▶ glyph is stripped — the arrow is
     *                        drawn from the collapsed flag, not from
     *                        the text.
     * @param collapsedText   text painted while collapsed.  Same
     *                        leading-arrow stripping.
     * @param tooltip         hover tooltip; {@code null} to skip.
     */
    public PaneTitle(Composite container, int id,
                     String expandedText, String collapsedText, String tooltip) {
        super(container, SWT.DOUBLE_BUFFERED);
        if (container instanceof Group g) g.setText("");
        this.id             = id;
        this.expandedText   = expandedText;
        this.collapsedText  = collapsedText;
        this.clickEventName = Events.paneTitleClick(id);
        addPaintListener(e -> paintTitle(e.gc));
        if (tooltip != null) setToolTipText(tooltip);
        int cols = (container.getLayout() instanceof GridLayout gl) ? gl.numColumns : 1;
        GridData gd = new GridData(SWT.FILL, SWT.BEGINNING, true, false);
        gd.horizontalSpan = Math.max(1, cols);
        setLayoutData(gd);
        setCursor(getDisplay().getSystemCursor(SWT.CURSOR_HAND));
        addMouseListener(MouseListener.mouseDownAdapter(e -> onClick()));
    }

    /** Sets the collapsed flag silently — no bus event fires.  Use to
     *  restore a saved state at startup.  Triggers a re-layout (the row
     *  resizes when the text grows / shrinks) and a redraw. */
    public void setCollapsed(boolean wantCollapsed) {
        if (collapsed == wantCollapsed) return;
        collapsed = wantCollapsed;
        relayoutAndRedraw();
    }

    /** Switches the title into a non-interactive label: arrow is hidden,
     *  clicks are ignored, and the hand cursor is dropped.  Use for panes
     *  that should not be collapsible. */
    public void setStaticMode(boolean staticMode) {
        this.staticMode = staticMode;
        setCursor(staticMode ? null : getDisplay().getSystemCursor(SWT.CURSOR_HAND));
        redraw();
    }

    private void onClick() {
        if (staticMode) return;
        collapsed = !collapsed;
        relayoutAndRedraw();
        MessageBus.instance().publish(clickEventName, collapsed);
    }

    private void relayoutAndRedraw() {
        if (getParent() != null && !getParent().isDisposed()) {
            getParent().layout(true);
        }
        redraw();
    }

    @Override
    public Point computeSize(int wHint, int hHint, boolean changed) {
        GC gc = new GC(this);
        try {
            gc.setFont(getFont());
            Point ext = gc.textExtent(currentText());
            int contentW = ARROW_PX + ARROW_GAP + ext.x;
            int hNeed = contentW + 2 * PAD_X;
            int vNeed = Math.max(ARROW_PX, ext.y) + 2 * PAD_Y;
            if (wHint != SWT.DEFAULT && wHint >= 0 && wHint < hNeed) {
                // Narrow strip — rotated text: width is the line-height,
                // height is the horizontal extent of the row.
                return new Point(vNeed, hNeed);
            }
            return new Point(hNeed, vNeed);
        } finally {
            gc.dispose();
        }
    }

    @Override
    protected void checkSubclass() {
        // SWT forbids subclassing of most widgets by default — opt back in.
    }

    private String currentText() {
        return collapsed ? collapsedText : expandedText;
    }

    private void paintTitle(GC gc) {
        gc.setFont(getFont());
        gc.setForeground(getForeground());
        gc.setBackground(getBackground());
        // Antialias both shape and text — matters most on the rotated
        // path where unfiltered glyph rasterisation along a non-axis-
        // aligned baseline produces visibly jagged edges.
        gc.setAntialias(SWT.ON);
        gc.setTextAntialias(SWT.ON);

        String text = currentText();
        char arrow = collapsed ? '▶' : '▼';
        boolean drawArrow = !staticMode;

        Point textExt = gc.textExtent(text);
        int gap = text.isEmpty() ? 0 : (drawArrow ? ARROW_GAP : 0);
        int arrowW = drawArrow ? ARROW_PX : 0;
        int contentW = arrowW + gap + textExt.x;
        int contentH = Math.max(drawArrow ? ARROW_PX : textExt.y, textExt.y);

        Point sz = getSize();
        if (sz.x < contentW + 2 * PAD_X) {
            // Rotated path: lay out arrow + text horizontally on a local
            // x-axis, then rotate -90° around the canvas centre so the
            // row appears reading bottom-to-top.
            Transform tr = new Transform(getDisplay());
            try {
                tr.translate(sz.x / 2f, sz.y / 2f);
                tr.rotate(-90);
                tr.translate(-contentW / 2f, -contentH / 2f);
                gc.setTransform(tr);
                if (drawArrow) drawArrow(gc, 0, (contentH - ARROW_PX) / 2, arrow);
                if (!text.isEmpty()) {
                    gc.drawText(text, arrowW + gap, (contentH - textExt.y) / 2, true);
                }
                gc.setTransform(null);
            } finally {
                tr.dispose();
            }
        } else {
            int x = PAD_X;
            int yTop = PAD_Y;
            if (drawArrow) drawArrow(gc, x, yTop + (contentH - ARROW_PX) / 2, arrow);
            if (!text.isEmpty()) {
                gc.drawText(text, x + arrowW + gap, yTop + (contentH - textExt.y) / 2, true);
            }
        }
    }

    /** Draws a filled triangle (▶ right-pointing or ▼ down-pointing) in
     *  the current foreground colour, sized to fit in an {@link #ARROW_PX}
     *  square with top-left at {@code (x, y)}. */
    private static void drawArrow(GC gc, int x, int y, char glyph) {
        gc.setBackground(gc.getForeground());  // fillPolygon uses background colour
        int[] pts;
        if (glyph == '▼') {
            pts = new int[]{ x, y, x + ARROW_PX, y, x + ARROW_PX / 2, y + ARROW_PX };
        } else {
            pts = new int[]{ x, y, x, y + ARROW_PX, x + ARROW_PX, y + ARROW_PX / 2 };
        }
        gc.fillPolygon(pts);
    }
}
