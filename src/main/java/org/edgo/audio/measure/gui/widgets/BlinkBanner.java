package org.edgo.audio.measure.gui.widgets;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
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
        if (outline != null) {                            // 8-offset halo for contrast over the plot
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
        if (c != null) {
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
