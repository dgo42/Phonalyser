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
import org.eclipse.swt.layout.FormData;
import org.eclipse.swt.widgets.Composite;

/**
 * A transparent overlay banner: paints one line of OUTLINED text — a halo around
 * the glyphs in the {@code outline} colour, so it reads over any plot content — and
 * blinks that text between a "lit" and a "dim" colour every 500&nbsp;ms on its OWN
 * {@code Display.timerExec} loop.  Only this widget repaints, never the parent
 * canvas (the previous overlay-text banner forced a full plot + signal redraw twice
 * a second just to flip the colour).
 *
 * <p>Created {@code SWT.NO_BACKGROUND | SWT.TRANSPARENT} so the plot shows through
 * around the text.  Show it when the banner is relevant and hide it otherwise — the
 * blink loop starts on {@link SWT#Show} and stops the instant the widget is hidden
 * or disposed.  Size/position with the inherited {@code setBounds}/{@code setLocation}
 * (or a layout via {@link #computeSize}), tooltip with {@code setToolTipText}, font
 * with {@code setFont}; set the text with {@link #setText} and the three colours with
 * {@link #setColors(Color, Color, Color)}.
 */
public final class BlinkBanner extends TransparentComposite {

    private static final int BLINK_MS = 500;
    /** 1-px inset so the ±1 outline halo never clips at the widget edge. */
    private static final int PAD = 1;

    private String  text = "";
    private Color   lit;
    private Color   dim;
    private Color   outline;
    private boolean showingLit = true;
    private boolean blinking = false;          // started on SWT.Show, stopped on SWT.Hide
    private boolean blinkScheduled;

    public BlinkBanner(Composite parent) {
        super(parent);
        addPaintListener(this::onPaint);
        addListener(SWT.Resize, e -> redraw());   // re-fit (re-ellipsise) to the new width
        addListener(SWT.Show,   e -> startBlink());
        addListener(SWT.Hide,   e -> { blinking = false; });
    }

    public void setText(String text) {
        this.text = (text == null) ? "" : text;
        redraw();
    }

    /** The blink pair ({@code lit}/{@code dim}) and the {@code outline} halo colour.
     *  All owned by the caller (the view's palette) — NOT disposed here.  Applies the
     *  current phase immediately and (re)starts the blink if visible. */
    public void setColors(Color lit, Color dim, Color outline) {
        this.lit = lit;
        this.dim = dim;
        this.outline = outline;
        redraw();
    }

    @Override
    public Point computeSize(int wHint, int hHint, boolean changed) {
        if (text.isEmpty()) {
            return new Point(0, 0);
        }
        GC gc = new GC(this);
        gc.setFont(getFont());
        Point ext = gc.textExtent(text);
        gc.dispose();
        return new Point(ext.x + 2 * PAD, ext.y + 2 * PAD);
    }

    /**
     * Right-anchors this banner inside its parent — {@code rightInset} px from
     * the parent's right border — sized to its text but never extending left
     * past {@code leftInset} px from the parent's left border.  A
     * {@code BlinkBanner} paints transparently yet still captures clicks across
     * its whole width, so a banner that overlays a button row (the views float
     * these over their header buttons) MUST be clamped left of those buttons
     * via {@code leftInset} or it silently swallows their clicks; the text is
     * left-ellipsised to whatever width remains.
     *
     * @param leftInset  px from the parent's left border the banner may not cross
     * @param rightInset px from the parent's right border the banner is pinned to
     * @param y          the banner's top y
     * @param height     the banner's height
     */
    public void alignRight(int leftInset, int rightInset, int y, int height) {
        int rightEdge = getParent().getClientArea().width - rightInset;
        int natW      = computeSize(SWT.DEFAULT, SWT.DEFAULT).x;
        int width     = Math.max(0, Math.min(natW, rightEdge - leftInset));
        setBounds(rightEdge - width, y, width, height);
    }

    /**
     * The {@code FormLayout} counterpart of {@link #alignRight}: for a banner
     * right-anchored by its {@link FormData}, pins the FormData width to the
     * banner's text — clamped to {@code maxWidth} — so the transparent widget no
     * longer spans, and captures clicks/tooltips across, more than the text
     * occupies (a full-width banner sits over the axis / readout table beside
     * it).  Call after {@link #setText}, before {@code requestLayout}; a no-op
     * unless the layout data is a {@link FormData}.  Beyond {@code maxWidth} the
     * text left-ellipsises.
     *
     * @param maxWidth the widest the banner may grow leftward from its right anchor
     */
    public void fitFormDataWidth(int maxWidth) {
        if (getLayoutData() instanceof FormData fd) {
            fd.width = Math.max(0, Math.min(computeSize(SWT.DEFAULT, SWT.DEFAULT).x, maxWidth));
        }
    }

    private void onPaint(PaintEvent e) {
        if (text.isEmpty()) {
            return;
        }
        GC gc = e.gc;
        gc.setFont(getFont());
        int w = getClientArea().width;
        String shown = fitRight(gc, text, w - 2 * PAD);   // left-ellipsise to fit the width
        Point ext = gc.textExtent(shown);
        int x = w - ext.x - PAD;                          // right-aligned within the widget
        // The colours belong to the caller's palette; setColor() disposes one
        // when the user recolours, so a banner shown across that edit could be
        // holding a disposed Color until the host re-pushes the new one.  Guard
        // every use so a stale reference simply skips a layer instead of
        // throwing — the halo / text reappears on the next setColors().
        if (outline != null && !outline.isDisposed()) {   // 8-offset halo for contrast over the plot
            gc.setForeground(outline);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx != 0 || dy != 0) {
                        gc.drawText(shown, x + dx, PAD + dy, true);
                    }
                }
            }
        }
        Color c = showingLit ? lit : dim;
        if (c != null && !c.isDisposed()) {
            gc.setForeground(c);
            gc.drawText(shown, x, PAD, true);
        }
    }

    /** Left-ellipsises {@code s} (prefixing "…") until it fits {@code maxWidth} px
     *  in the GC's current font; returned unchanged when it already fits. */
    private String fitRight(GC gc, String s, int maxWidth) {
        if (gc.textExtent(s).x <= maxWidth) {
            return s;
        }
        String t = s;
        while (t.length() > 1 && gc.textExtent("…" + t).x > maxWidth) {
            t = t.substring(1);
        }
        return "…" + t;
    }

    /** Starts the blink loop on {@link SWT#Show}.  Begins lit. */
    private void startBlink() {
        showingLit = true;
        blinking = true;
        redraw();
        scheduleBlink();
    }

    /** Schedules the next 500 ms toggle.  Gated on {@link #blinking} (set by Show/Hide),
     *  NOT {@code isVisible()} — so a transient hidden state during layout can't kill the
     *  loop; only an actual {@link SWT#Hide} stops it. */
    private void scheduleBlink() {
        if (blinkScheduled || isDisposed() || !blinking) {
            return;
        }
        blinkScheduled = true;
        getDisplay().timerExec(BLINK_MS, () -> {
            blinkScheduled = false;
            if (isDisposed() || !blinking) {
                return;
            }
            showingLit = !showingLit;
            redraw();
            scheduleBlink();
        });
    }

}
