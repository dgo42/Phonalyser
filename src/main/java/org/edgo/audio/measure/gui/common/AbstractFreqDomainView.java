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

package org.edgo.audio.measure.gui.common;

import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.edgo.audio.measure.enums.MagnitudeUnit;

import java.util.Map;

/**
 * Shared base for the frequency-domain canvases — FFT and frequency-response.
 *
 * <p>Extends {@link AbstractMeasurementView} with helpers that only make
 * sense for the two log-frequency views.  The oscilloscope and condensed
 * views stay one level up because their time-axis math and ring-buffer
 * data source don't fit the same shapes.
 *
 * <p>What lives here so far:
 *
 * <ul>
 *   <li>{@link ColumnBucketPainter} — the trace-render strategy both
 *       views already use: bucket up to thousands of data points into
 *       at most {@code plot.width} pixel columns, then emit one
 *       vertical envelope line per multi-point column plus a midpoint
 *       polyline connecting column centres.  Cheap and stable at any
 *       sweep length.</li>
 * </ul>
 *
 * <p>Future hooks (deferred from Phase B until they prove safe):
 * coordinate transforms ({@code freqToX} / {@code dbToY}), a paint-cache
 * template method, wheel-zoom handler, and a crosshair-readout
 * scaffold.  All of those have view-specific contributors (FFT carries
 * magnitude-unit + log/linear-freq flags; FreqResp is always
 * log/dB-rel), so they get extracted only once we're sure the abstract
 * surface accommodates both without ugly conditionals.
 */
public abstract class AbstractFreqDomainView extends AbstractMeasurementView {

    /** Offscreen image holding the most recent static-layer render
     *  (grid + axes + traces + overlays).  Reused for crosshair-only or
     *  blink-tick redraws so neither dragging the mouse nor the blink
     *  timer re-walks the trace data.  Owned by this base and disposed
     *  in the subclass's dispose listener via {@link #disposeTraceBuffer}. */
    private Image traceBuffer;
    /** Fingerprint of every input that affects what
     *  {@link #traceBuffer} should look like (data identity, viewport,
     *  prefs).  Computed every paint by the subclass; we only rebuild
     *  when it differs. */
    private long  traceBufferFingerprint;
    /** Canvas width / height the {@link #traceBuffer} was built for —
     *  a resize always forces a rebuild. */
    private int   traceBufferW, traceBufferH;

    /** Forwards to {@link AbstractMeasurementView#AbstractMeasurementView(Composite, int, Map)}
     *  so frequency-domain subclasses (FftView / FreqRespView) can pass
     *  their per-role colour overrides through the palette constructor. */
    protected AbstractFreqDomainView(Composite parent, int style,
                                     Map<ColorRole, Integer> overrides) {
        super(parent, style, overrides);
    }

    /**
     * Template method for the paint-cache pattern shared by FFT and
     * FreqResp: the static layers (grid, axes, trace polylines, overlay
     * curves) get rendered into an offscreen Image whose validity is
     * keyed by a {@code fingerprint} long.  As long as the fingerprint
     * matches the previous paint, this method just blits the cached
     * image — no trace re-walk, no axis re-tick.  When it doesn't
     * match, {@code renderer} is invoked to repopulate the buffer.
     *
     * <p>The subclass computes the fingerprint by hashing in every
     * input that affects pixels: viewport bounds, data-identity hashes,
     * prefs toggles.  Two paints with the same fingerprint MUST produce
     * the same image.
     *
     * @param dstGc        destination GC (the live paint event's GC)
     * @param area         canvas client area
     * @param fingerprint  cache key built by the subclass from its
     *                     data + viewport + prefs
     * @param renderer     called with a fresh GC bound to a new
     *                     buffer Image; expected to paint every
     *                     static layer including the background
     */
    protected final void paintCachedStatic(GC dstGc, Rectangle area,
                                           long fingerprint,
                                           StaticRenderer renderer) {
        if (traceBuffer == null || traceBuffer.isDisposed()
                || traceBufferW != area.width || traceBufferH != area.height
                || traceBufferFingerprint != fingerprint) {
            if (traceBuffer != null && !traceBuffer.isDisposed()) traceBuffer.dispose();
            traceBuffer = new Image(getDisplay(),
                    Math.max(1, area.width), Math.max(1, area.height));
            GC bgc = new GC(traceBuffer);
            try {
                renderer.render(bgc);
            } finally {
                bgc.dispose();
            }
            traceBufferFingerprint = fingerprint;
            traceBufferW = area.width;
            traceBufferH = area.height;
        }
        dstGc.drawImage(traceBuffer, 0, 0);
    }

    /** Subclasses call this from their dispose listener to release the
     *  cached buffer image.  Idempotent; safe to call when the buffer
     *  was never created. */
    protected final void disposeTraceBuffer() {
        if (traceBuffer != null && !traceBuffer.isDisposed()) traceBuffer.dispose();
        traceBuffer = null;
    }

    /** Callback used by {@link #paintCachedStatic} to repopulate the
     *  cache when the fingerprint changes.  Invoked with a GC bound to
     *  a fresh Image; the implementation paints the background and
     *  every static layer (grid, axes, traces, overlays) into it. */
    @FunctionalInterface
    public interface StaticRenderer {
        void render(GC bgc);
    }

    // =========================================================================
    // Coordinate transforms — frequency ↔ X, dB ↔ Y
    //
    // Shared math, single source of truth.  Each call site passes the
    // viewport bounds and the {@code logFreq} flag so this base stays
    // unitless: FFT can pin {@code logFreq = false} when the user
    // chose a linear axis; FreqResp always passes {@code true}.
    // =========================================================================

    /** Maps a frequency to its X pixel inside {@code plot}.  At
     *  {@code f == freqMin} returns {@code plot.x}; at
     *  {@code f == freqMax} returns {@code plot.x + plot.width}.
     *
     *  <p>Out-of-range freqs map to out-of-rect pixels — caller filters
     *  or clips.  Log-axis path uses base-10 (same convention as the
     *  axis-tick generators), and clamps both bounds:
     *  {@code safeMin = max(1, freqMin)} and {@code safeMax =
     *  max(safeMin + 1, freqMax)} so a degenerate viewport doesn't
     *  NaN out. */
    /** Multiplicative guard for a degenerate log-axis viewport — see {@link #safeLogMax}. */
    private static final double LOG_RANGE_MIN_RATIO = 1.0000001;

    /** Substitute log10 value for a non-positive linear-unit range bound
     *  (≡ 10⁻³⁰, far below any representable signal) so a degenerate top/bottom
     *  doesn't NaN the magnitude→Y mapping. */
    private static final double LOG_MAG_FLOOR_DECADES = -30;

    /** Upper bound of the log-axis range with the degenerate-viewport guard
     *  applied: never below the real {@code freqMax} — an additive "+1 Hz"
     *  floor would overshoot freqMax on a sub-1-Hz zoom and compress the trace
     *  away from the right edge (the axis uses freqMax directly).  The tiny
     *  multiplicative bump only guards a {@code freqMin == freqMax} range so
     *  {@code log10(hi/lo)} stays {@code > 0}.  Shared by {@link #freqToX},
     *  {@link #xToFreq} and {@link #xFractionToFreq} so all three use the
     *  identical mapping. */
    private double safeLogMax(double safeMin, double freqMax) {
        return Math.max(freqMax, safeMin * LOG_RANGE_MIN_RATIO);
    }

    protected final int freqToX(double f, Rectangle plot,
                                double freqMin, double freqMax, boolean logFreq) {
        if (logFreq) {
            double safeMin = Math.max(1, freqMin);
            double safeMax = safeLogMax(safeMin, freqMax);
            double lo = Math.log10(safeMin);
            double hi = Math.log10(safeMax);
            double t  = (Math.log10(Math.max(1, f)) - lo) / (hi - lo);
            return plot.x + (int) Math.round(t * plot.width);
        }
        double t = (f - freqMin) / (freqMax - freqMin);
        return plot.x + (int) Math.round(t * plot.width);
    }

    /** Frequency at which {@link #freqToX} first rounds PAST the pixel
     *  column containing {@code xAbs} — the half-pixel right boundary,
     *  inverted through the same safeMin/safeLogMax mapping.  Lets a
     *  column-batched trace walk advance per ascending-frequency point
     *  with one compare instead of one {@code freqToX} (log10) per point;
     *  see {@code FftView.drawSpectrum}. */
    protected final double columnRightBoundaryFreq(int xAbs, Rectangle plot,
                                                   double freqMin, double freqMax,
                                                   boolean logFreq) {
        double tB = (xAbs - plot.x + 0.5) / Math.max(1, plot.width);
        if (logFreq) {
            double safeMin = Math.max(1, freqMin);
            double safeMax = safeLogMax(safeMin, freqMax);
            double lo = Math.log10(safeMin);
            return Math.pow(10.0, lo + tB * (Math.log10(safeMax) - lo));
        }
        return freqMin + tB * (freqMax - freqMin);
    }

    /** Inverse of {@link #freqToX}: maps an absolute canvas X pixel
     *  back to the corresponding frequency in Hz. */
    protected final double xToFreq(int x, Rectangle plot,
                                   double freqMin, double freqMax, boolean logFreq) {
        double t = (double) (x - plot.x) / plot.width;
        if (logFreq) {
            double safeMin = Math.max(1, freqMin);
            double safeMax = safeLogMax(safeMin, freqMax);
            double lo = Math.log10(safeMin);
            double hi = Math.log10(safeMax);
            return Math.pow(10, lo + t * (hi - lo));
        }
        return freqMin + t * (freqMax - freqMin);
    }

    /** Convenience: like {@link #xToFreq} but takes a normalised
     *  fraction in {@code [0, 1]} across the viewport instead of an
     *  absolute pixel.  Equivalent to
     *  {@code xToFreq(plot.x + (int)(frac*plot.width), ...)} but
     *  avoids the round-trip when the caller already has the fraction. */
    protected final double xFractionToFreq(double frac,
                                           double freqMin, double freqMax, boolean logFreq) {
        if (logFreq) {
            double safeMin = Math.max(1, freqMin);
            double safeMax = safeLogMax(safeMin, freqMax);
            double lo = Math.log10(safeMin);
            double hi = Math.log10(safeMax);
            return Math.pow(10, lo + frac * (hi - lo));
        }
        return freqMin + frac * (freqMax - freqMin);
    }

    /** Maps a dB value to its Y pixel inside {@code plot}.  At
     *  {@code db == magTop} returns {@code plot.y}; at
     *  {@code db == magBot} returns {@code plot.y + plot.height}.
     *  No clamp — an over-range value runs off the plot edge and the trace painter's
     *  GC clip cuts it flush.  Linear-dB form used by FreqResp and by FFT's dB-rel unit. */
    protected final int dbToY(double db, Rectangle plot,
                              double magTop, double magBot) {
        double t = (magTop - db) / (magTop - magBot);
        return plot.y + (int) Math.round(t * plot.height);
    }

    /** Sub-pixel ({@code double}) counterpart of {@link #dbToY} for the Lanczos-smoothed
     *  traces — keeps the curve off the integer pixel grid so it strokes between rows.
     *  Returns {@code NaN} for {@code NaN} input (propagates a gap to the painter). */
    protected final double dbToYf(double db, Rectangle plot,
                                  double magTop, double magBot) {
        return plot.y + ((magTop - db) / (magTop - magBot)) * plot.height;
    }

    /**
     * Shared mapping kernel: fractional position 0..1 of {@code v} within the
     * {@code [bot, top]} range, where 0 = top and 1 = bot.  Linear-amplitude units
     * (V, V/√Hz) map on a log axis; dB units stay linear.  No clamping — callers clip
     * after they know the plot geometry.
     */
    protected final double magToYFraction(double v, double top, double bot, MagnitudeUnit unit) {
        if (unit.isLog()) {
            double vL   = (v   <= 0) ? Double.NEGATIVE_INFINITY : Math.log10(v);
            double topL = (top <= 0) ? LOG_MAG_FLOOR_DECADES : Math.log10(top);
            double botL = (bot <= 0) ? LOG_MAG_FLOOR_DECADES : Math.log10(bot);
            if (topL <= botL) return 0;
            return (topL - vL) / (topL - botL);
        }
        return (top - v) / (top - bot);
    }

    /** Unit-aware counterpart of {@link #dbToY} for FFT's V / V√Hz / dBFS / dBV axes,
     *  via {@link #magToYFraction}.  Clamps to the plot rect; markers that must
     *  drop when off-range test the fraction themselves before calling. */
    protected final int magToY(double v, Rectangle plot, double top, double bot, MagnitudeUnit unit) {
        double t = magToYFraction(v, top, bot, unit);
        if (t < 0) t = 0;
        if (t > 1) t = 1;
        return plot.y + (int) Math.round(t * plot.height);
    }

    /** Like {@link #magToY} but for the spectrum trace: NO clamp, so an over-range peak's
     *  flank is drawn to its true (off-screen) endpoint and the painter's GC clip cuts it
     *  flush at the edge — keeping the correct slope, instead of pinning to the edge
     *  (shifting flat-top) or to a fixed off-edge point (wrong slope).  Mirrors
     *  {@link #dbToY}; the trace's {@code v} is always &gt; 0 so the log V/V√Hz fraction
     *  never reaches ±∞ here. */
    protected final int magToYTrace(double v, Rectangle plot, double top, double bot, MagnitudeUnit unit) {
        double t = magToYFraction(v, top, bot, unit);
        return plot.y + (int) Math.round(t * plot.height);
    }

    /**
     * Paints a multi-line readout box anchored at {@code (x, y)} inside
     * {@code plot}.  The box auto-flips to the opposite side of the
     * anchor when it would otherwise spill past the right or bottom edge,
     * so the caller can just pass {@code mouseX + 12, mouseY + 12} and
     * the box stays inside the chart.
     *
     * <p>Lines are split on {@code '\n'}.  Single-line text works fine —
     * the loop runs once.  The font + colour palette is supplied by the
     * subclass so each view's theme stays its own; the base does not
     * own those resources.
     *
     * @param gc          paint GC
     * @param text        readout content — newlines split into rows
     * @param x           anchor X (typically {@code mouseX + 12})
     * @param y           anchor Y (typically {@code mouseY + 12})
     * @param plot        plot rect — used for the auto-flip clamp
     * @param font        text font
     * @param overlayBg   fill colour for the box body
     * @param frame       stroke colour for the box border
     * @param textColor   foreground for the text rows
     */
    protected final void drawReadoutBox(GC gc, String text,
                                        int x, int y, Rectangle plot,
                                        Font font, Color overlayBg, Color frame, Color textColor) {
        gc.setFont(font);
        String[] lines = text.split("\n");
        int lineH = gc.getFontMetrics().getHeight();
        int textW = 0;
        for (String l : lines) textW = Math.max(textW, gc.textExtent(l).x);
        int boxW = textW + 12;
        int boxH = lines.length * lineH + 8;
        if (x + boxW > plot.x + plot.width)  x -= boxW + 24;
        if (y + boxH > plot.y + plot.height) y -= boxH + 24;
        gc.setBackground(overlayBg);
        gc.fillRectangle(x, y, boxW, boxH);
        gc.setForeground(frame);
        gc.drawRectangle(x, y, boxW, boxH);
        gc.setForeground(textColor);
        for (int i = 0; i < lines.length; i++) {
            gc.drawText(lines[i], x + 6, y + 4 + i * lineH, true);
        }
    }

}
