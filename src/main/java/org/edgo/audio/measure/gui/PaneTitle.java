package org.edgo.audio.measure.gui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Transform;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;

/**
 * Installs a clickable, custom-painted title bar as the first child of a
 * pane container.  Paints horizontally by default; when the container is
 * narrower than the title's horizontal extent (i.e. the pane has been
 * collapsed on a horizontal SashForm) the same text is rotated 90°
 * counter-clockwise so it remains readable in the narrow strip.
 *
 * <p>Originally a plain {@link org.eclipse.swt.widgets.Label}.  Two
 * platform issues drove the move to a custom Canvas:
 * <ul>
 *   <li>On GTK the legacy {@code Group} title is rendered by a GtkFrame
 *       label widget that consumes clicks itself, so they never reach
 *       SWT listeners.  Putting the title in the content area as a
 *       widget the application owns fixes click delivery.</li>
 *   <li>A horizontally-laid Label is unreadable in the narrow
 *       collapsed strip; rotating to vertical fits.</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 *   Composite group = new Composite(parent, SWT.BORDER);
 *   group.setLayout(new GridLayout(1, false));
 *   PaneTitle.install(group, "▼ Generator",
 *                     I18n.t("generator.pane.toggle.tooltip"),
 *                     this::onToggleCollapse);
 *   // ... add other children to group normally
 * </pre>
 * Update the title text later with {@code PaneTitle.setTitle(group, "▶")}.
 */
public final class PaneTitle {

    private static final String DATA_KEY    = "titleLabel";
    private static final String TEXT_KEY    = "titleText";
    private static final int    PAD_X       = 4;
    private static final int    PAD_Y       = 2;
    /** Fixed arrow-polygon size in pixels.  Independent of the canvas
     *  font so every pane's arrow renders at the same visual weight
     *  (font-driven sizing produced visibly different arrow sizes
     *  between panes despite all using the same default font). */
    private static final int    ARROW_PX    = 12;
    /** Gap (in pixels) between the arrow and the text that follows it. */
    private static final int    ARROW_GAP   = 4;

    private PaneTitle() {}

    /**
     * Installs the title Canvas as the first child of {@code container}.
     * When the container is a legacy {@link Group} its chrome title is
     * also cleared so we don't end up with two visible titles.
     */
    public static Control install(Composite container, String initialText,
                                  String tooltip, Runnable onClick) {
        if (container instanceof Group g) g.setText("");
        Canvas header = new Canvas(container, SWT.DOUBLE_BUFFERED) {
            @Override
            public Point computeSize(int wHint, int hHint, boolean changed) {
                GC gc = new GC(this);
                try {
                    gc.setFont(getFont());
                    String t = textOf(this);
                    Point ext = gc.textExtent(t);
                    int hNeed = ext.x + 2 * PAD_X;     // width needed for horizontal text
                    int vNeed = ext.y + 2 * PAD_Y;     // height of one line of text
                    if (wHint != SWT.DEFAULT && wHint >= 0 && wHint < hNeed) {
                        // Narrow strip — rotated text: width is one
                        // line-height, height is the horizontal extent.
                        return new Point(vNeed, hNeed);
                    }
                    return new Point(hNeed, vNeed);
                } finally {
                    gc.dispose();
                }
            }
        };
        header.setData(TEXT_KEY, initialText);
        header.addPaintListener(e -> paintTitle(e.gc, (Canvas) e.widget));
        if (tooltip != null) header.setToolTipText(tooltip);
        int cols = (container.getLayout() instanceof GridLayout gl) ? gl.numColumns : 1;
        GridData gd = new GridData(SWT.FILL, SWT.BEGINNING, true, false);
        gd.horizontalSpan = Math.max(1, cols);
        header.setLayoutData(gd);
        header.setCursor(container.getDisplay().getSystemCursor(SWT.CURSOR_HAND));
        if (onClick != null) {
            header.addMouseListener(MouseListener.mouseDownAdapter(e -> onClick.run()));
        }
        container.setData(DATA_KEY, header);
        return header;
    }

    /**
     * Updates the title text on a container that's been through
     * {@link #install}.  Falls back to {@code group.setText(text)} when
     * the container is a Group but no header was installed — keeps any
     * legacy call site working unchanged.  Triggers a re-layout so the
     * row resizes when the text grows / shrinks AND a redraw so the
     * new text is painted immediately.
     */
    public static void setTitle(Composite container, String text) {
        if (container == null || container.isDisposed()) return;
        Object d = container.getData(DATA_KEY);
        if (d instanceof Canvas c && !c.isDisposed()) {
            c.setData(TEXT_KEY, text);
            if (c.getParent() != null && !c.getParent().isDisposed()) {
                c.getParent().layout(true);
            }
            c.redraw();
        } else if (container instanceof Group g) {
            g.setText(text);
        }
    }

    private static String textOf(Canvas c) {
        Object t = c.getData(TEXT_KEY);
        return (t instanceof String s) ? s : "";
    }

    private static void paintTitle(GC gc, Canvas c) {
        String raw = textOf(c);
        if (raw.isEmpty()) return;
        gc.setFont(c.getFont());
        gc.setForeground(c.getForeground());
        gc.setBackground(c.getBackground());
        // Antialias both shape and text drawing — matters most for the
        // rotated path, where unfiltered glyph rasterisation along a
        // non-axis-aligned baseline produces visibly jagged edges.  Also
        // makes our filled-polygon arrow glyphs smooth.
        gc.setAntialias(SWT.ON);
        gc.setTextAntialias(SWT.ON);

        // Split the leading ▼ / ▶ off from the rest of the title.  The
        // built-in font glyphs for these triangles are often rendered as
        // monochrome bitmaps (ignoring setTextAntialias), so we draw the
        // arrow as a filled polygon instead.
        char arrow = 0;
        String text = raw;
        if (text.length() > 0) {
            char first = text.charAt(0);
            if (first == '▶' || first == '▼') {
                arrow = first;
                text = text.substring(1).stripLeading();
            }
        }

        Point textExt = gc.textExtent(text);
        // Triangle size is a fixed constant — not derived from the font —
        // so every pane's arrow renders identically regardless of any
        // font-metric quirks on a given canvas.
        int arrowSize = (arrow != 0) ? ARROW_PX : 0;
        int gap = (arrow != 0 && !text.isEmpty()) ? ARROW_GAP : 0;
        int contentW = arrowSize + gap + textExt.x;
        int contentH = Math.max(arrowSize, textExt.y);

        Point sz = c.getSize();
        if (sz.x < contentW + 2 * PAD_X) {
            // Rotated path: lay out the arrow + text horizontally on a
            // local x-axis, then rotate -90° around the canvas centre so
            // the row appears reading bottom-to-top.
            Transform tr = new Transform(c.getDisplay());
            try {
                tr.translate(sz.x / 2f, sz.y / 2f);
                tr.rotate(-90);
                tr.translate(-contentW / 2f, -contentH / 2f);
                gc.setTransform(tr);
                int x = 0;
                if (arrow != 0) {
                    drawArrow(gc, x, (contentH - arrowSize) / 2, arrowSize, arrow);
                    x += arrowSize + gap;
                }
                if (!text.isEmpty()) {
                    gc.drawText(text, x, (contentH - textExt.y) / 2, true);
                }
                gc.setTransform(null);
            } finally {
                tr.dispose();
            }
        } else {
            int x = PAD_X;
            int yTop = PAD_Y;
            if (arrow != 0) {
                drawArrow(gc, x, yTop + (contentH - arrowSize) / 2, arrowSize, arrow);
                x += arrowSize + gap;
            }
            if (!text.isEmpty()) {
                gc.drawText(text, x, yTop + (contentH - textExt.y) / 2, true);
            }
        }
    }

    /** Draws a filled triangle (▶ right-pointing or ▼ down-pointing)
     *  in the current foreground colour, sized to fit in a {@code size}
     *  square with top-left at {@code (x, y)}.  Filled-polygon path is
     *  used (not {@link GC#drawText}) so the glyph is antialiased on
     *  every platform — the Unicode triangle characters frequently fall
     *  back to a monochrome bitmap when drawn as text. */
    private static void drawArrow(GC gc, int x, int y, int size, char glyph) {
        gc.setBackground(gc.getForeground());  // fillPolygon uses background colour
        int[] pts;
        if (glyph == '▼') {
            // ▼ Down-pointing triangle
            pts = new int[]{ x, y, x + size, y, x + size / 2, y + size };
        } else {
            // ▶ Right-pointing triangle (default)
            pts = new int[]{ x, y, x, y + size, x + size, y + size / 2 };
        }
        gc.fillPolygon(pts);
    }
}
