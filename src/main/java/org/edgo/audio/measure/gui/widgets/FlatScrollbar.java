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
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

import java.util.ArrayList;
import java.util.List;

/**
 * Custom Canvas-drawn scrollbar replacement for {@link org.eclipse.swt.widgets.Slider}.
 *
 * <p>Bypasses the Windows 11 "auto-hide / auto-thin scrollbar" theme so the
 * thumb and arrows stay visible at a constant thickness regardless of mouse
 * hover or the system accessibility setting.  Mirrors the part of the Slider
 * API the project uses ({@code minimum / maximum / selection / thumb /
 * increment / pageIncrement / SWT.Selection events}) so call sites can be
 * swapped in without rewiring their logic.
 *
 * <p>Visual style: flat dark track + lighter thumb + always-on arrow
 * triangles at each end, sized to {@link #ARROW_SIZE} pixels along the
 * scrolling axis.  Mouse interaction: arrow click = ±{@code increment},
 * track click outside the thumb = ±{@code pageIncrement}, thumb drag =
 * continuous, hold-on-arrow = 10 Hz auto-repeat after a 300 ms delay
 * (matching {@code Slider}).
 */
public final class FlatScrollbar extends Canvas {

    /** Pixel size of each end-arrow along the scrolling axis. */
    private static final int ARROW_SIZE = 18;

    private final boolean vertical;
    private int minimum       = 0;
    private int maximum       = 100;
    private int selection     = 0;
    private int thumb         = 10;
    private int increment     = 1;
    private int pageIncrement = 10;

    private final Color trackColor;
    private final Color thumbColor;
    private final Color thumbHoverColor;
    private final Color arrowColor;
    private final Color arrowDisabledColor;
    private final Color borderColor;

    /** True while the thumb is being dragged with the mouse. */
    private boolean dragging;
    /** Pointer offset within the thumb at the start of a drag (pixels). */
    private int     dragOffset;
    /** True while the mouse is hovering over the thumb (paints brighter). */
    private boolean thumbHovered;

    /** Auto-repeat scheduling state — non-null when an arrow is held down. */
    private Runnable autoRepeatTask;

    private final List<Listener> selectionListeners = new ArrayList<>();

    public FlatScrollbar(Composite parent, int style) {
        // Drop SWT.NO_BACKGROUND so Control.print() captures the
        // widget correctly when MainWindow's screenshot path prints
        // an offscreen FftPane to a GC.  The paint listener already
        // fills the entire bounds itself, so losing the
        // NO_BACKGROUND optimisation produces no visual difference
        // in interactive use.
        super(parent, SWT.DOUBLE_BUFFERED);
        this.vertical = (style & SWT.VERTICAL) != 0;

        Display d = getDisplay();
        trackColor         = new Color(d,  40,  40,  40);
        thumbColor         = new Color(d, 0xA0, 0xA0, 0xA0);
        thumbHoverColor    = new Color(d, 0xC8, 0xC8, 0xC8);
        arrowColor         = new Color(d, 200, 200, 200);
        arrowDisabledColor = new Color(d,  90,  90,  90);
        borderColor        = new Color(d,  20,  20,  20);

        addPaintListener(this::paint);
        addListener(SWT.MouseDown,        this::onMouseDown);
        addListener(SWT.MouseUp,          this::onMouseUp);
        addListener(SWT.MouseMove,        this::onMouseMove);
        addListener(SWT.MouseDoubleClick, this::onMouseDoubleClick);
        addListener(SWT.MouseExit,        e -> { thumbHovered = false; redraw(); });
        addListener(SWT.MouseWheel,       this::onMouseWheel);

        addDisposeListener(e -> disposeColors());
    }

    // -------------------------------------------------------------------------
    // Public API (mirrors java.awt.Scrollbar / SWT.Slider semantics)
    // -------------------------------------------------------------------------

    public void setMinimum(int v) {
        if (v >= maximum) return;
        minimum = v;
        clampSelection();
        redraw();
    }
    public void setMaximum(int v) {
        if (v <= minimum) return;
        maximum = v;
        clampSelection();
        redraw();
    }
    public void setThumb(int v) {
        thumb = Math.max(1, Math.min(maximum - minimum, v));
        clampSelection();
        redraw();
    }
    public void setSelection(int v) {
        int clamped = clamp(v, minimum, maximum - thumb);
        if (clamped == selection) return;
        selection = clamped;
        redraw();
    }
    public void setIncrement(int v)     { increment     = Math.max(1, v); }
    public void setPageIncrement(int v) { pageIncrement = Math.max(1, v); }

    public int getMinimum()       { return minimum; }
    public int getMaximum()       { return maximum; }
    public int getSelection()     { return selection; }
    public int getThumb()         { return thumb; }
    public int getIncrement()     { return increment; }
    public int getPageIncrement() { return pageIncrement; }

    /** Registers a {@link SWT#Selection} listener — fired whenever the user
     *  changes the selection via arrow click, track click, drag, or wheel. */
    public void addSelectionListener(Listener l) {
        if (l != null) selectionListeners.add(l);
    }

    @Override
    public Point computeSize(int wHint, int hHint, boolean changed) {
        // Always at least ARROW_SIZE in the scrolling axis × 2 (one arrow
        // each end) + a tiny track stub; otherwise honour the layout hints.
        int axis = vertical ? hHint : wHint;
        int perp = vertical ? wHint : hHint;
        int axisDefault = ARROW_SIZE * 2 + 8;
        int perpDefault = 20;
        int w = vertical ? (perp == SWT.DEFAULT ? perpDefault : perp)
                         : (axis == SWT.DEFAULT ? axisDefault : axis);
        int h = vertical ? (axis == SWT.DEFAULT ? axisDefault : axis)
                         : (perp == SWT.DEFAULT ? perpDefault : perp);
        return new Point(w, h);
    }

    // -------------------------------------------------------------------------
    // Paint
    // -------------------------------------------------------------------------

    private void paint(PaintEvent e) {
        GC gc = e.gc;
        Point sz = getSize();
        int w = sz.x;
        int h = sz.y;
        gc.setAntialias(SWT.ON);

        // Background track.
        gc.setBackground(trackColor);
        gc.fillRectangle(0, 0, w, h);
        // 1 px border so the bar reads as a distinct surface from the
        // dark canvas it usually sits next to.
        gc.setForeground(borderColor);
        gc.drawRectangle(0, 0, w - 1, h - 1);

        // Thumb rectangle.
        int[] tr = thumbRect();
        Color tc = thumbHovered || dragging ? thumbHoverColor : thumbColor;
        gc.setBackground(tc);
        gc.fillRoundRectangle(tr[0], tr[1], tr[2], tr[3], 4, 4);

        // Arrows at each end.
        boolean atStart = selection <= minimum;
        boolean atEnd   = selection >= maximum - thumb;
        gc.setBackground(atStart ? arrowDisabledColor : arrowColor);
        drawArrow(gc, 0, 0, w, h, true);
        gc.setBackground(atEnd ? arrowDisabledColor : arrowColor);
        drawArrow(gc, 0, 0, w, h, false);
    }

    /** Returns {@code [x, y, width, height]} of the thumb rectangle. */
    private int[] thumbRect() {
        Point sz = getSize();
        int w = sz.x;
        int h = sz.y;
        int trackPx = trackPixels();
        if (trackPx <= 0) return new int[] { 0, 0, 0, 0 };
        int range = Math.max(1, maximum - minimum);
        // Thumb pixel size proportional to thumb / range, floored so it
        // stays grabbable at every zoom.
        int thumbPx = Math.max(20, (int) Math.round((double) thumb / range * trackPx));
        thumbPx = Math.min(trackPx, thumbPx);
        int travelPx = trackPx - thumbPx;
        int maxSel = maximum - thumb - minimum;
        int posPx = maxSel <= 0
                ? 0
                : (int) Math.round((selection - minimum) / (double) maxSel * travelPx);
        if (vertical) {
            return new int[] { 1, ARROW_SIZE + posPx, w - 2, thumbPx };
        } else {
            return new int[] { ARROW_SIZE + posPx, 1, thumbPx, h - 2 };
        }
    }

    private int trackPixels() {
        Point sz = getSize();
        return (vertical ? sz.y : sz.x) - 2 * ARROW_SIZE;
    }

    /** Paints a triangular arrow into the head ({@code start = true}) or
     *  tail ({@code start = false}) arrow cell.  Triangles point along the
     *  scrolling axis: ◀▶ for horizontal, ▲▼ for vertical. */
    private void drawArrow(GC gc, int x0, int y0, int w, int h, boolean start) {
        int cx, cy, half;
        int[] pts;
        if (vertical) {
            cx = w / 2;
            cy = start ? ARROW_SIZE / 2 : h - ARROW_SIZE / 2;
            half = ARROW_SIZE / 3;
            pts = start
                    ? new int[] { cx, cy - half, cx - half, cy + half, cx + half, cy + half }
                    : new int[] { cx - half, cy - half, cx + half, cy - half, cx, cy + half };
        } else {
            cx = start ? ARROW_SIZE / 2 : w - ARROW_SIZE / 2;
            cy = h / 2;
            half = ARROW_SIZE / 3;
            pts = start
                    ? new int[] { cx + half, cy - half, cx + half, cy + half, cx - half, cy }
                    : new int[] { cx - half, cy - half, cx - half, cy + half, cx + half, cy };
        }
        gc.fillPolygon(pts);
    }

    // -------------------------------------------------------------------------
    // Mouse
    // -------------------------------------------------------------------------

    private void onMouseDown(Event e) {
        if (e.button != 1) return;
        Point sz = getSize();
        int axisPx     = vertical ? e.y : e.x;
        int trackEnd   = (vertical ? sz.y : sz.x) - ARROW_SIZE;

        if (axisPx < ARROW_SIZE) {
            // Start arrow → step backward, auto-repeat while held.
            stepBy(-increment);
            startAutoRepeat(-increment);
            return;
        }
        if (axisPx >= trackEnd) {
            // End arrow → step forward, auto-repeat.
            stepBy(+increment);
            startAutoRepeat(+increment);
            return;
        }
        int[] tr = thumbRect();
        int thumbStart = vertical ? tr[1] : tr[0];
        int thumbEnd   = thumbStart + (vertical ? tr[3] : tr[2]);
        if (axisPx >= thumbStart && axisPx < thumbEnd) {
            dragging  = true;
            dragOffset = axisPx - thumbStart;
        } else if (axisPx < thumbStart) {
            stepBy(-pageIncrement);
        } else {
            stepBy(+pageIncrement);
        }
    }

    private void onMouseUp(Event e) {
        dragging = false;
        stopAutoRepeat();
    }

    /** Double-click on the thumb recentres the slider — selection lands
     *  at the midpoint of its valid range, so the caller's
     *  {@link SWT#Selection} handler resets whatever offset the slider
     *  represents to its "centred" value. */
    private void onMouseDoubleClick(Event e) {
        if (e.button != 1) return;
        int[] tr = thumbRect();
        int thumbStart = vertical ? tr[1] : tr[0];
        int thumbSize  = vertical ? tr[3] : tr[2];
        int axisPx     = vertical ? e.y : e.x;
        if (axisPx < thumbStart || axisPx >= thumbStart + thumbSize) return;
        int maxSel = maximum - thumb - minimum;
        if (maxSel <= 0) return;
        int center = minimum + maxSel / 2;
        if (center == selection) return;
        selection = center;
        redraw();
        update();
        fire();
    }

    private void onMouseMove(Event e) {
        // Hover state for the thumb so the user sees a hit-target highlight.
        int[] tr = thumbRect();
        int thumbStart = vertical ? tr[1] : tr[0];
        int thumbSize  = vertical ? tr[3] : tr[2];
        int axisPx     = vertical ? e.y : e.x;
        boolean nowHovered = axisPx >= thumbStart && axisPx < thumbStart + thumbSize;
        if (nowHovered != thumbHovered) {
            thumbHovered = nowHovered;
            redraw();
        }
        if (!dragging) return;
        int trackPx = trackPixels();
        if (trackPx <= 0) return;
        int range = Math.max(1, maximum - minimum);
        int thumbPx = Math.max(20, (int) Math.round((double) thumb / range * trackPx));
        thumbPx = Math.min(trackPx, thumbPx);
        int travelPx = Math.max(1, trackPx - thumbPx);
        int targetThumbStart = axisPx - dragOffset - ARROW_SIZE;
        if (targetThumbStart < 0)        targetThumbStart = 0;
        if (targetThumbStart > travelPx) targetThumbStart = travelPx;
        int maxSel = maximum - thumb - minimum;
        int newSel = minimum
                + (int) Math.round(targetThumbStart / (double) travelPx * maxSel);
        if (newSel != selection) {
            selection = clamp(newSel, minimum, maximum - thumb);
            redraw();
            // Force the thumb repaint NOW so it tracks the mouse in
            // real-time.  Without this, Windows defers Canvas paint
            // events while the mouse is captured for a drag — the user
            // sees the signal update smoothly (it's a different widget)
            // while the thumb only catches up on MouseUp.
            update();
            fire();
        }
    }

    private void onMouseWheel(Event e) {
        if (e.count == 0) return;
        int dir = Integer.signum(e.count);
        // Wheel up = selection decreases (matches the scope canvas mapping
        // for the vertical scrollbar).
        stepBy(-dir * increment);
        e.doit = false;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void stepBy(int delta) {
        int clamped = clamp(selection + delta, minimum, maximum - thumb);
        if (clamped == selection) return;
        selection = clamped;
        redraw();
        fire();
    }

    private void clampSelection() {
        int clamped = clamp(selection, minimum, maximum - thumb);
        if (clamped != selection) selection = clamped;
    }

    private static int clamp(int v, int lo, int hi) {
        if (hi < lo) return lo;
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    private void startAutoRepeat(int delta) {
        stopAutoRepeat();
        Runnable[] holder = new Runnable[1];
        holder[0] = new Runnable() {
            @Override public void run() {
                if (isDisposed() || autoRepeatTask != holder[0]) return;
                stepBy(delta);
                getDisplay().timerExec(100, holder[0]);   // 10 Hz
            }
        };
        autoRepeatTask = holder[0];
        getDisplay().timerExec(300, holder[0]);            // 300 ms initial delay
    }

    private void stopAutoRepeat() {
        if (autoRepeatTask == null) return;
        Display d = getDisplay();
        if (d != null && !d.isDisposed()) d.timerExec(-1, autoRepeatTask);
        autoRepeatTask = null;
    }

    private void fire() {
        Event ev = new Event();
        ev.widget = this;
        // Use SWT's native listener registry so callers that registered with
        // addListener(SWT.Selection, …) — the standard SWT idiom — get the
        // event.  Also fan out to any addSelectionListener() callers we
        // tracked separately.
        notifyListeners(SWT.Selection, ev);
        for (Listener l : selectionListeners) l.handleEvent(ev);
    }

    private void disposeColors() {
        if (!trackColor.isDisposed())         trackColor.dispose();
        if (!thumbColor.isDisposed())         thumbColor.dispose();
        if (!thumbHoverColor.isDisposed())    thumbHoverColor.dispose();
        if (!arrowColor.isDisposed())         arrowColor.dispose();
        if (!arrowDisabledColor.isDisposed()) arrowDisabledColor.dispose();
        if (!borderColor.isDisposed())        borderColor.dispose();
    }

    @Override
    protected void checkSubclass() {
        // SWT forbids subclassing of most widgets by default — opt back in.
    }
}
