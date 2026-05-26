package org.edgo.audio.measure.gui.common;

import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;

import java.util.Arrays;
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

    protected AbstractFreqDomainView(Composite parent, int style) {
        super(parent, style);
    }

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
    protected final int freqToX(double f, Rectangle plot,
                                double freqMin, double freqMax, boolean logFreq) {
        if (logFreq) {
            double safeMin = Math.max(1, freqMin);
            double safeMax = Math.max(safeMin + 1, freqMax);
            double lo = Math.log10(safeMin);
            double hi = Math.log10(safeMax);
            double t  = (Math.log10(Math.max(1, f)) - lo) / (hi - lo);
            return plot.x + (int) Math.round(t * plot.width);
        }
        double t = (f - freqMin) / (freqMax - freqMin);
        return plot.x + (int) Math.round(t * plot.width);
    }

    /** Inverse of {@link #freqToX}: maps an absolute canvas X pixel
     *  back to the corresponding frequency in Hz. */
    protected final double xToFreq(int x, Rectangle plot,
                                   double freqMin, double freqMax, boolean logFreq) {
        double t = (double) (x - plot.x) / plot.width;
        if (logFreq) {
            double safeMin = Math.max(1, freqMin);
            double safeMax = Math.max(safeMin + 1, freqMax);
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
            double safeMax = Math.max(safeMin + 1, freqMax);
            double lo = Math.log10(safeMin);
            double hi = Math.log10(safeMax);
            return Math.pow(10, lo + frac * (hi - lo));
        }
        return freqMin + frac * (freqMax - freqMin);
    }

    /** Maps a dB value to its Y pixel inside {@code plot}.  At
     *  {@code db == magTop} returns {@code plot.y}; at
     *  {@code db == magBot} returns {@code plot.y + plot.height}.
     *
     *  <p>Linear-dB form used by FreqResp and by FFT for its dB-rel
     *  magnitude unit.  FFT's other units (V, V/sqrt(Hz), dBFS) keep
     *  their own per-view magToY because the fraction step is
     *  unit-aware. */
    protected final int dbToY(double db, Rectangle plot,
                              double magTop, double magBot) {
        double t = (magTop - db) / (magTop - magBot);
        return plot.y + (int) Math.round(t * plot.height);
    }

    // (Zoom / pan stay per-view: FFT's magnitude zoom branches between
    // linear dB and log V / V·Hz^-½ on the FftMagnitudeUnit enum, which
    // FreqResp never sees.  Forcing both views through a common
    // template would either push view-specific branching into this base
    // or hide it behind a strategy that adds more glue than it saves.
    // The coordinate transforms above are the part that genuinely
    // overlaps; the wheel-handler dispatch stays in the subclass.)

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

    /**
     * Helper for rendering a long sequence of data points as a polyline
     * + envelope bars at no more than one drawLine per pixel column.
     *
     * <p>Usage:
     * <pre>
     *   ColumnBucketPainter p = new ColumnBucketPainter(plot);
     *   for (...) p.add(xPixel, yPixel);
     *   p.drawTo(gc);
     * </pre>
     *
     * <p>Both call sites in FftView and FreqRespView had ~30 lines of
     * the same bucketing logic open-coded.  Sharing it via this helper
     * keeps the rendering identical (and any future tuning, e.g. line
     * width or anti-aliasing, lands in one place).
     */
    public static final class ColumnBucketPainter {

        private final Rectangle plot;
        private final int[] yMins, yMaxs, cnts;
        /** Optional anchor points just outside the plot rect.  When set,
         *  the polyline pass extends from {@code leftAnchor*} into the
         *  first column and from the last column out to {@code
         *  rightAnchor*}.  The GC clip in {@link #drawTo} hides the
         *  out-of-rect portion of each anchor line.  Used by FFT to
         *  reach the canvas edges with one extra bin's worth of data. */
        private int leftAnchorX  = Integer.MIN_VALUE, leftAnchorY  = 0;
        private int rightAnchorX = Integer.MIN_VALUE, rightAnchorY = 0;

        public ColumnBucketPainter(Rectangle plot) {
            this.plot  = plot;
            int w      = Math.max(1, plot.width);
            this.yMins = new int[w];
            this.yMaxs = new int[w];
            this.cnts  = new int[w];
            Arrays.fill(yMins, Integer.MAX_VALUE);
            Arrays.fill(yMaxs, Integer.MIN_VALUE);
        }

        /** Records one data point.  Coordinates are absolute canvas
         *  pixels (the same coordinate system the caller is about to
         *  paint with).  Points outside the plot rect are silently
         *  dropped — the caller doesn't need to filter ahead of time. */
        public void add(int xAbs, int yAbs) {
            int x = xAbs - plot.x;
            if (x < 0 || x >= cnts.length) return;
            if (yAbs < yMins[x]) yMins[x] = yAbs;
            if (yAbs > yMaxs[x]) yMaxs[x] = yAbs;
            cnts[x]++;
        }

        /** Sets the polyline's left edge anchor — a point at
         *  {@code xAbs < plot.x} the first column connects back to.
         *  Calling repeatedly keeps the rightmost candidate (the
         *  one closest to the visible range), which is the bin that
         *  most accurately reaches the left edge. */
        public void setLeftAnchor(int xAbs, int yAbs) {
            if (xAbs > leftAnchorX) { leftAnchorX = xAbs; leftAnchorY = yAbs; }
        }

        /** Sets the polyline's right edge anchor — a point at
         *  {@code xAbs >= plot.x + plot.width} the last column connects
         *  out to.  Calling repeatedly keeps the leftmost candidate. */
        public void setRightAnchor(int xAbs, int yAbs) {
            if (rightAnchorX == Integer.MIN_VALUE || xAbs < rightAnchorX) {
                rightAnchorX = xAbs; rightAnchorY = yAbs;
            }
        }

        /** Emits the accumulated points as drawLine calls on {@code gc}.
         *  The GC's clip is temporarily set to the plot rect so segments
         *  that would otherwise spill past the chart edge are cut
         *  cleanly.  Caller is responsible for setting foreground / line
         *  width / line style before calling. */
        public void drawTo(GC gc) {
            Rectangle prevClip = gc.getClipping();
            gc.setClipping(plot);
            try {
                // Pass 1: vertical envelope bars for columns where
                // multiple data points collapsed onto the same X pixel.
                for (int x = 0; x < cnts.length; x++) {
                    if (cnts[x] == 0) continue;
                    if (yMins[x] != yMaxs[x]) {
                        int px = plot.x + x;
                        gc.drawLine(px, yMins[x], px, yMaxs[x]);
                    }
                }
                // Pass 2: polyline through column midpoints — bridges
                // gaps between sparse columns so the trace reads as one
                // continuous line.  Left / right anchors (when set)
                // prepend / append a segment that reaches past the
                // plot edge; the GC clip above hides the out-of-rect
                // portion.
                int prevX = leftAnchorX, prevY = leftAnchorY;
                for (int x = 0; x < cnts.length; x++) {
                    if (cnts[x] == 0) continue;
                    int midY = (yMins[x] + yMaxs[x]) >>> 1;
                    int px   = plot.x + x;
                    if (prevX != Integer.MIN_VALUE) gc.drawLine(prevX, prevY, px, midY);
                    prevX = px; prevY = midY;
                }
                if (rightAnchorX != Integer.MIN_VALUE && prevX != Integer.MIN_VALUE) {
                    gc.drawLine(prevX, prevY, rightAnchorX, rightAnchorY);
                }
            } finally {
                gc.setClipping(prevClip);
            }
        }
    }
}
