package org.edgo.audio.measure.gui.common;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared base for the project's measurement canvases — oscilloscope, FFT,
 * frequency response, and the condensed multi-view.
 *
 * <p>The base intentionally stays out of paint: each subclass owns its own
 * colour palette, fonts, axis math, and button rendering.  What lives here
 * is the boilerplate that was being copy-pasted across every view:
 *
 * <ul>
 *   <li>A {@link Hotspot} registry — each view registers its clickable
 *       header buttons (rect + click action + tooltip key) once at
 *       construction.  The rect is consulted by reference every mouse
 *       event, so subclasses can mutate it in place from their paint
 *       code (set width/height to zero to hide a conditionally-visible
 *       button).</li>
 *   <li>{@link #hotspotAt(int, int)} — the lookup the subclass's
 *       mouse listeners call to resolve hover state and click dispatch.
 *       Always returns the first visible match in registration order.</li>
 * </ul>
 *
 * <p>This is deliberately minimal: no abstract methods, no mouse
 * listeners installed by the base.  Subclasses keep their existing
 * {@code onMouseMove} / {@code onMouseDown} handlers and just consult
 * {@link #hotspotAt} from inside them.  That avoids fighting the scope's
 * slider machinery and the FFT's distortion-table cursor logic, which
 * both layer their own per-pixel decisions on top of the simple
 * button-or-not test the base performs.
 */
public abstract class AbstractMeasurementView extends Canvas {

    private final List<Hotspot> hotspots = new ArrayList<>();

    protected AbstractMeasurementView(Composite parent, int style) {
        super(parent, style);
    }

    /**
     * Registers a clickable hot-spot.  The owning view typically calls
     * this once per button at construction, passing a {@link Rectangle}
     * field it also mutates from its paint code.  Sharing the rectangle
     * by reference means the hotspot stays in sync with the painted
     * button position automatically — no second registration step
     * needed when the layout changes.
     *
     * <p>To hide a hotspot (e.g. a conditionally-visible toggle), set
     * its bounds {@code width} or {@code height} to zero.  The
     * {@link #hotspotAt} lookup skips zero-sized rects.
     *
     * @param bounds      hit-test rectangle — kept by reference; mutate
     *                    in place to relocate / hide
     * @param onClick     fired by {@link #hotspotAt} consumers when the
     *                    user left-clicks inside {@code bounds}
     * @param tooltipKey  i18n key for the hover tooltip, or {@code null}
     *                    for no tooltip
     */
    protected final void registerHotspot(Rectangle bounds, Runnable onClick, String tooltipKey) {
        hotspots.add(new Hotspot(bounds, onClick, tooltipKey));
    }

    /** Overload for hot-spots with no tooltip. */
    protected final void registerHotspot(Rectangle bounds, Runnable onClick) {
        registerHotspot(bounds, onClick, null);
    }

    /**
     * Returns the hotspot covering {@code (x, y)} or {@code null} when
     * no visible hotspot matches.  Visibility is determined by the
     * bounds rectangle's size — zero-width or zero-height rects are
     * treated as hidden.
     *
     * <p>Iteration order is registration order; the first match wins.
     */
    protected final Hotspot hotspotAt(int x, int y) {
        for (Hotspot h : hotspots) {
            Rectangle b = h.bounds;
            if (b != null && b.width > 0 && b.height > 0 && b.contains(x, y)) {
                return h;
            }
        }
        return null;
    }

    /** Blits {@code icon} centred in the rectangle
     *  {@code (x, y, w, h)}.  No-op when {@code icon} is {@code null} or
     *  disposed.  Shared by every view that paints SVG glyphs inside a
     *  header button frame — the centring math was open-coded in three
     *  places before this helper. */
    protected final void drawCenteredIcon(GC gc, Image icon, int x, int y, int w, int h) {
        if (icon == null || icon.isDisposed()) return;
        Rectangle ib = icon.getBounds();
        gc.drawImage(icon, x + (w - ib.width) / 2, y + (h - ib.height) / 2);
    }

    /** Allocates a fresh SWT {@link Color} from a packed 24-bit
     *  {@code 0xRRGGBB} int.  Subclasses use this as the single point of
     *  RGB → Color conversion instead of open-coding the same unpack in
     *  every {@code syncColors} method.  The caller owns the returned
     *  Color and is responsible for disposing it. */
    protected final Color newColor(int packedRgb) {
        return new Color(getDisplay(),
                (packedRgb >> 16) & 0xFF,
                (packedRgb >>  8) & 0xFF,
                 packedRgb        & 0xFF);
    }

    /** Convenience: returns the system "hand" cursor when a hotspot is
     *  under {@code (x, y)}, the system "arrow" cursor otherwise.
     *  Subclasses use this from {@code onMouseMove} to keep the hover
     *  cursor consistent across all three views.  Returns the bare
     *  cursor ID (SWT constant) so the subclass can layer its own
     *  decisions (e.g. resize cursors over sliders) on top. */
    protected final int hoverCursorId(int x, int y) {
        return (hotspotAt(x, y) != null) ? SWT.CURSOR_HAND : SWT.CURSOR_ARROW;
    }

    /** Immutable view of a registered hot-spot.  The {@code bounds}
     *  field is mutated by the owning view, so callers must treat the
     *  rectangle as read-only on every query. */
    public static final class Hotspot {
        public final Rectangle bounds;
        public final Runnable  onClick;
        public final String    tooltipKey;

        Hotspot(Rectangle bounds, Runnable onClick, String tooltipKey) {
            this.bounds     = bounds;
            this.onClick    = onClick;
            this.tooltipKey = tooltipKey;
        }
    }
}
