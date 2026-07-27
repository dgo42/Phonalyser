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

package org.edgo.audio.measure.gui.scope;

import java.util.Arrays;

import org.eclipse.swt.graphics.Color;

import org.edgo.audio.measure.gui.common.AlphaImageScratch;
import org.edgo.audio.measure.gui.common.MeasurementPainter;

/**
 * Dense-trace digital-phosphor renderer (more than one sample per pixel): rasterises a
 * displayed window as a DSO-style DIGITAL PHOSPHOR — a coverage image where each pixel's
 * brightness is its dwell time — and blits it as one channel-colour-tinted image through
 * a {@link MeasurementPainter}, replacing the vector polyline entirely.  Shared by the
 * main {@link ScopeView} and the condensed {@link ZoomedView} strip: each holds its OWN
 * instance so their pools don't collide.
 *
 * <p>The pure signal math lives in {@link TraceEnvelope} (accumulate → pen → fringe →
 * downsample); this class OWNS the pooled scratch that math streams through — the
 * per-column span accumulator, the coverage buffers (pixel-res + supersampled), the
 * exported band arrays, the count→alpha LUT, and the CPU-blit reuse arrays
 * ({@link AlphaImageScratch}) — so the hot path allocates nothing.  Pure pools: nothing
 * is injected, the renderer just grows its buffers on demand.
 *
 * <p><b>Adaptive device resolution.</b>  {@link #render} reads
 * {@link MeasurementPainter#getPixelScale()} EVERY frame and rasterises at the surface's
 * DEVICE resolution ({@code round(widthPx·scale)} × {@code round(heightPx·scale)}), then
 * blits that device-res buffer into the LOGICAL rectangle — so a HiDPI GPU surface gets
 * crisp 1:1 device texels instead of NanoVG upscaling a logical-res buffer soft.  A
 * per-monitor scale change flows through automatically (read, not cached).  At scale 1
 * every derived value collapses to the logical-resolution path and the output is
 * byte-identical to rasterising at logical size.
 */
public final class PhosphorRenderer {

    /** Digital-phosphor: alpha floor for a hit pixel
     *  (crossing count &ge; 1).  {@code 1.0} (the default) makes EVERY hit pixel full trace
     *  brightness, so the phosphor image matches the sparse anti-aliased sin&nbsp;x/x stroke
     *  across the {@code spp == 1} boundary — no brightness drop when the renderer flips regimes.
     *  A value {@code < 1.0} re-enables true phosphor grading (this floor plus the log knee up to
     *  {@link #DPO_SATURATION_COUNT}) so a one-shot glitch reads fainter than a saturated tone;
     *  {@link #phosphorLut()} short-circuits the log when the floor is 1.0.  Bench knob — parsed
     *  (not a compile-time literal) so the grading branch stays live for the {@code < 1.0} knob. */
    private static final double DPO_SINGLE_HIT_ALPHA = Double.parseDouble("1.0");
    /** Digital-phosphor: dwell (crossing) count that saturates a pixel to full intensity;
     *  a count at or above it maps to alpha 255, so the count→alpha LUT is this many
     *  entries long ({@code +1} for count 0).  Bench-tunable. */
    private static final int    DPO_SATURATION_COUNT = 64;
    /** 8-bit alpha for a fully saturated phosphor pixel. */
    private static final double DPO_ALPHA_MAX = 255.0;
    /** Digital-phosphor supersampling factor: the histogram, band export and coverage pen are
     *  rasterised on a grid of {@code 1/this} DEVICE-px cells ({@code 2} = the maintainer's 0.5 px
     *  histogram) and box-averaged down, so every final pixel carries a true decimal coverage —
     *  REAL anti-aliasing in both axes, including the sub-column X the 1-px band model loses
     *  (halves the diagonal steps and the vertical-segment widening).  {@code 1} = rasterise at
     *  device resolution (no supersampling).  Bench knob — parsed so both branches stay live. */
    private static final int DPO_SUPERSAMPLE = Integer.parseInt("2");

    /** Per-column span DIFFERENCE accumulator ({@code int[gridHeight+2]}), pooled and grown on
     *  demand (single-threaded paint).  No per-frame allocation. */
    private int[]     phosphorDiff;
    /** Device-resolution coverage/alpha buffer the finished trace is blitted from. */
    private byte[]    phosphorAlpha;
    /** Supersampled sibling of {@code phosphorAlpha} ({@link #DPO_SUPERSAMPLE} &gt; 1): the
     *  histogram/pen rasterise into this {@code (ss·devW)×(ss·devH)} grid of sub-pixel cells,
     *  box-averaged down into {@code phosphorAlpha} for the blit. */
    private byte[]    phosphorAlphaSs;
    /** Scratch for {@link TraceEnvelope#fringeDilate}'s horizontal pass, pooled beside
     *  {@code phosphorAlphaSs} (same sub-cell dimensions). */
    private byte[]    phosphorFringe;
    /** Per-column band ends exported by {@link TraceEnvelope#columnCrossings} in UNROUNDED row
     *  space (the refined crest/trough), consumed by {@link TraceEnvelope#penRasterize} as the
     *  round coverage pen's centreline.  Pooled {@code float[gridWidth]}, grown in lockstep with
     *  the other phosphor scratch; a blank column is {@link Float#NaN}. */
    private float[]   phosphorBandTop;
    private float[]   phosphorBandBot;
    /** The path's true fractional x-extent within each column ({@code ⊂ [col, col+1]}), recorded
     *  by the accumulation's boundary splitting — the pen sweeps {@code [xLo, xHi]} instead of the
     *  whole column, so vertical flanks stroke at their exact sub-column x.  Pooled like the band. */
    private float[]   phosphorBandXLo;
    private float[]   phosphorBandXHi;
    /** The path's entry/exit points per column (unrounded row space), recorded by the accumulation
     *  — the tilted-capsule endpoints {@link TraceEnvelope#penRasterize} sweeps for a steep
     *  single-traversal column so a near-vertical flank's neighbour fringe ramps with y.  Pooled
     *  like the band; a column with no capsule geometry is {@link Float#NaN}. */
    private float[]   phosphorBandEntryX;
    private float[]   phosphorBandEntryY;
    private float[]   phosphorBandExitX;
    private float[]   phosphorBandExitY;
    /** Count→alpha LUT, built once by {@link #phosphorLut()} from the (compile-time) intensity
     *  constants. */
    private byte[]    phosphorLut;
    /** Caller-owned reuse arrays for the CPU ({@code GcMeasurementPainter}) blit, so a
     *  steady-state frame churns zero arrays; the NanoVG backend ignores it. */
    private final AlphaImageScratch alphaScratch = new AlphaImageScratch();

    /** Previous frame's non-zero GRID-coordinate content box (inclusive; {@code prevX1 < prevX0}
     *  when the last frame drew nothing), cleared at the next frame's start so a steady trace
     *  repaints only its own bounding box instead of the whole buffer.  Because that box held ALL
     *  of last frame's coverage, clearing it leaves the grid all-zero — the exact state the old
     *  whole-buffer fill produced, so the bounded passes stay byte-identical. */
    private int prevX0, prevX1 = -1, prevY0, prevY1;
    /** Grid + device dimensions the pools and the {@link #prevX0} region were last sized for; any
     *  change forces a whole-buffer clear (the packed stride / region coords are otherwise stale). */
    private int prevWSs = -1, prevHSs = -1, prevDevW = -1, prevDevH = -1;
    /** Test seam: force a WHOLE-buffer repaint (full clear + whole-grid passes) instead of the
     *  incremental dirty region, so a test can assert the two flows are byte-identical.  Production
     *  never sets it. */
    boolean fullRepaint;

    /**
     * Rasterises the whole displayed window as a DSO-style DIGITAL PHOSPHOR — a coverage image
     * where each pixel's brightness is its dwell time (how many consecutive-sample spans cross it)
     * — and blits it as one channel-colour-tinted image, replacing the vector polyline entirely.
     * By default every hit pixel is full trace brightness ({@link #DPO_SINGLE_HIT_ALPHA} = 1.0), so
     * the image matches the sparse anti-aliased sin&nbsp;x/x stroke across the {@code spp == 1}
     * boundary; lowering the floor re-enables phosphor dwell grading (a one-shot glitch then reads
     * fainter than a saturated tone).
     *
     * <p>The pure accumulation is {@link TraceEnvelope#columnCrossings}: it inverts the
     * per-column bucket mapping for X and applies the same {@code centerY − (value −
     * dcOffset)·vScale} value→pixel transform for Y as every other trace path, streaming
     * one {@code (x, y, count)} per touched pixel.  At the full-brightness floor
     * ({@link #DPO_SINGLE_HIT_ALPHA} &ge; 1.0) the accumulation runs for its band export ONLY — the
     * whole count pass is skipped ({@code counts == false}: no diff writes, no flush, no sink, and
     * the diff accumulator is never even allocated), since packing hard-255 interior rows would
     * erase the pen's anti-aliased fringes and the pen alone rasterises the trace; under phosphor
     * grading ({@code < 1.0}) the sink writes each pixel's alpha through the precomputed count→alpha
     * LUT into the pooled coverage buffer and the pen lays its dimmer outline over it.  The finished
     * buffer is blitted via {@link MeasurementPainter#drawAlphaImage} so the GL persistence
     * (phosphor FBO) and the GC screenshot path inherit it automatically.  {@code columnCrossings}
     * runs with sin&nbsp;x/x rails ON, so each column also exports its band-limited crest/trough as
     * unrounded row-space floats.  The configured pref trace width is then stroked by
     * {@link TraceEnvelope#penRasterize}: a true round coverage pen of EXACTLY the fractional
     * {@code lineWidth}, swept along the exported band and composited OVER the count interior via
     * max — continuous width, anti-aliasing in both axes, and round caps into blank columns.
     *
     * <p><b>Resolution.</b>  The grid is {@code (devW·ss)×(devH·ss)} where {@code devW =
     * round(widthPx·pixelScale)} / {@code devH = round(heightPx·pixelScale)} are the surface's
     * DEVICE pixels and {@code ss = }{@link #DPO_SUPERSAMPLE} — 0.5-device-px sub-cells at the
     * default 2.  The value→row transform scales by {@code sy = devH·ss/heightPx} (applied to
     * {@code centerY}, {@code vScale} and the pen {@code lineWidth}); the horizontal mapping falls
     * out of passing the wider grid width to {@code columnCrossings}.  The supersampled grid is
     * box-averaged down ({@link TraceEnvelope#downsampleBox}) to {@code devW×devH} and drawn into
     * the LOGICAL {@code widthPx×heightPx} rect.  At {@code pixelScale == 1}: {@code devW == widthPx},
     * {@code sy == ss}, {@code drawW == imgW} — every value equals the logical-resolution path, so
     * the output is byte-identical.  Pooled scratch only, grown on demand — the hot path allocates
     * nothing.
     *
     * <p><b>Dirty region.</b>  Only the trace's content bounding box is touched per frame.  After
     * the band export the box is {first..last non-NaN column} × {extreme band rows}, grown by the
     * pen's reach and the fringe radius; the frame-start clear zeroes just the PREVIOUS frame's box
     * ({@link #clearRegion}, whole-buffer only on a size change / first use), and the pen sweep,
     * {@link TraceEnvelope#fringeDilate} and {@link TraceEnvelope#downsampleBox} run only over that
     * box.  Because the previous box held ALL of last frame's non-zero coverage, clearing it leaves
     * both buffers all-zero — the exact state the old whole-buffer fill produced — so everything
     * outside the current box is provably 0 in both the old and new flow and the blit is
     * byte-identical, at a fraction of the per-frame work (the dominant win at HiDPI device scale).
     */
    public void render(MeasurementPainter gc, float[] data, int n,
                       int dispStart, double subSampleOffset, int dispCount,
                       int widthPx, int heightPx, double centerY, double vScale,
                       double dcOffset, float lineWidth, Color color) {
        if (widthPx <= 0 || heightPx <= 0) return;
        // Adaptive: read the surface's device scale EVERY frame (a per-monitor change flows
        // through automatically) and rasterise at DEVICE resolution, then blit into the LOGICAL
        // rect for crisp 1:1 texels on HiDPI.  At scale 1 all of the below collapses to the
        // logical-resolution path — byte-identical.
        float pixelScale = gc.getPixelScale();
        int devW = Math.max(1, Math.round(widthPx  * pixelScale));
        int devH = Math.max(1, Math.round(heightPx * pixelScale));
        int ss  = Math.max(1, DPO_SUPERSAMPLE);
        int wSs = devW * ss;
        int hSs = devH * ss;
        // Vertical value→row transform scale from LOGICAL px into the (devH·ss) grid rows; the X
        // mapping falls out of passing wSs to columnCrossings as before.  sy == ss at scale 1.
        double sy = (double) hSs / heightPx;
        // At the full-brightness floor (DPO_SINGLE_HIT_ALPHA ≥ 1.0) the count pass is a no-op — the
        // sink writes nothing and the pen alone draws the trace — so we skip the ENTIRE count pass
        // (no diff writes, no prefix-sum flush) AND don't size/zero the diff accumulator; only under
        // phosphor grading (< 1.0), where the graded interior IS the dwell image, is it needed.
        boolean counts = DPO_SINGLE_HIT_ALPHA < 1.0;
        if (counts && (phosphorDiff == null || phosphorDiff.length < hSs + 2)) {
            phosphorDiff = new int[hSs + 2];
        }
        if (phosphorBandTop == null || phosphorBandTop.length < wSs) {
            phosphorBandTop = new float[wSs];
            phosphorBandBot = new float[wSs];
            phosphorBandXLo = new float[wSs];
            phosphorBandXHi = new float[wSs];
            phosphorBandEntryX = new float[wSs];
            phosphorBandEntryY = new float[wSs];
            phosphorBandExitX = new float[wSs];
            phosphorBandExitY = new float[wSs];
        }
        int pixels = devW * devH;
        if (phosphorAlpha == null || phosphorAlpha.length < pixels) {
            phosphorAlpha = new byte[pixels];
        }
        byte[] gridBuf;
        if (ss > 1) {
            int cells = wSs * hSs;
            if (phosphorAlphaSs == null || phosphorAlphaSs.length < cells) {
                phosphorAlphaSs = new byte[cells];
            }
            gridBuf = phosphorAlphaSs;
        } else {
            gridBuf = phosphorAlpha;   // no supersampling → the pen writes the device buffer directly
        }
        // DIRTY-REGION FRAME CLEAR.  A raster-dimension change (or the test's full-repaint seam)
        // invalidates the stored box → whole-buffer fill; otherwise clear ONLY the previous frame's
        // content box.  That box held ALL of last frame's non-zero coverage, so clearing it leaves
        // BOTH buffers all-zero — the exact state the old unconditional fill produced.
        boolean dimsChanged = wSs != prevWSs || hSs != prevHSs || devW != prevDevW || devH != prevDevH;
        if (dimsChanged || fullRepaint) {
            Arrays.fill(phosphorAlpha, 0, pixels, (byte) 0);
            if (ss > 1) Arrays.fill(gridBuf, 0, wSs * hSs, (byte) 0);
        } else if (prevX1 >= prevX0) {
            clearRegion(gridBuf, wSs, prevX0, prevX1, prevY0, prevY1);
            if (ss > 1) clearRegion(phosphorAlpha, devW, prevX0 / ss, prevX1 / ss, prevY0 / ss, prevY1 / ss);
        }
        prevWSs = wSs; prevHSs = hSs; prevDevW = devW; prevDevH = devH;
        byte[] grid  = gridBuf;
        byte[] lut   = phosphorLut();
        int lutMax   = lut.length - 1;
        int w        = wSs;
        // sin x/x rails ON (the maintainer's standing order): each column exports its band-limited
        // crest/trough as unrounded row-space floats for the coverage pen.  Under phosphor grading
        // (< 1.0) the graded interior IS the dwell image, so the sink packs counts→LUT and the pen
        // lays its dimmer outline over it via max; at the full-brightness floor (counts == false)
        // packing hard 255 would erase the pen's AA fringe, so the count pass is skipped entirely and
        // the sink is a no-op — the pen alone rasterises the trace.  The band export runs either way.
        TraceEnvelope.CrossingSink sink = counts
                ? (x, y, count) -> grid[y * w + x] = lut[Math.min(count, lutMax)]
                : (x, y, count) -> { };                                      // pen-only at flat fill
        TraceEnvelope.columnCrossings(data, n, dispStart, dispCount, wSs, hSs,
                subSampleOffset, centerY * sy, vScale * sy, dcOffset, true, counts, phosphorDiff,
                phosphorBandTop, phosphorBandBot, phosphorBandXLo, phosphorBandXHi,
                phosphorBandEntryX, phosphorBandEntryY, phosphorBandExitX, phosphorBandExitY, sink);
        // Content bounding box from the exported bands: first/last non-NaN column and the extreme
        // band rows, grown by the pen's reach and the fringe radius.  Everything outside is provably
        // 0 after pen+fringe, so the bounded passes (and the next frame's clear) can skip it.
        int colLo = wSs, colHi = -1;
        float rowMin = Float.POSITIVE_INFINITY, rowMax = Float.NEGATIVE_INFINITY;
        for (int x = 0; x < wSs; x++) {
            float top = phosphorBandTop[x];
            if (Float.isNaN(top)) continue;
            if (colLo == wSs) colLo = x;
            colHi = x;
            float bot = phosphorBandBot[x];
            if (top < rowMin) rowMin = top;
            if (bot < rowMin) rowMin = bot;
            if (top > rowMax) rowMax = top;
            if (bot > rowMax) rowMax = bot;
        }
        int penColLo, penColHi, rgX0, rgX1, rgY0, rgY1;
        if (fullRepaint) {                              // test seam → whole-grid passes
            penColLo = 0; penColHi = wSs - 1;
            rgX0 = 0; rgX1 = wSs - 1; rgY0 = 0; rgY1 = hSs - 1;
        } else if (colHi < colLo) {                     // blank frame → nothing to stroke, empty box
            penColLo = 0; penColHi = -1;
            rgX0 = 0; rgX1 = -1; rgY0 = 0; rgY1 = -1;
        } else {
            int penReach = (int) Math.ceil(lineWidth * sy * 0.5) + 1;   // matches penRasterize's reach
            int margin   = penReach + (ss > 1 ? ss : 0);                // + the fringe radius
            penColLo = colLo; penColHi = colHi;
            rgX0 = Math.max(0, colLo - margin);
            rgX1 = Math.min(wSs - 1, colHi + margin);
            rgY0 = Math.max(0, (int) Math.floor(rowMin) - margin);
            rgY1 = Math.min(hSs - 1, (int) Math.ceil(rowMax) + margin);
        }
        if (penColHi >= penColLo) {
            // Stroke the trace width as a true round swept-stadium coverage pen of EXACTLY the
            // fractional pref lineWidth (scaled to the supersampled DEVICE grid) over the exported
            // band, composited via max — continuous width + AA + round caps.  The outer sweep is
            // bounded to the content columns ±reach (identical output — the interior skips NaN cols).
            int outlineAlpha = (int) Math.round(DPO_SINGLE_HIT_ALPHA * DPO_ALPHA_MAX);
            TraceEnvelope.penRasterize(phosphorBandTop, phosphorBandBot, phosphorBandXLo,
                    phosphorBandXHi, phosphorBandEntryX, phosphorBandEntryY, phosphorBandExitX,
                    phosphorBandExitY, wSs, hSs, (float) (lineWidth * sy), outlineAlpha,
                    penColLo, penColHi, grid);
            if (ss > 1) {
                // The vector stroke's AA fringe, appended on the sub-cell grid (interior stays full
                // brightness — a tent convolution dimmed thin strokes, bench-rejected), then exact
                // box area coverage down to device pixels.  Both passes run only over the content box.
                int cells = wSs * hSs;
                if (phosphorFringe == null || phosphorFringe.length < cells) {
                    phosphorFringe = new byte[cells];
                }
                TraceEnvelope.fringeDilate(grid, wSs, hSs, ss, phosphorFringe, rgX0, rgX1, rgY0, rgY1);
                TraceEnvelope.downsampleBox(grid, wSs, ss, phosphorAlpha, devW, devH,
                        rgX0 / ss, rgX1 / ss, rgY0 / ss, rgY1 / ss);
            }
        }
        prevX0 = rgX0; prevX1 = rgX1; prevY0 = rgY0; prevY1 = rgY1;
        // Device-res coverage buffer (devW×devH) blitted into the LOGICAL widthPx×heightPx rect.
        gc.drawAlphaImage(phosphorAlpha, devW, devH, 0, 0, widthPx, heightPx, color, alphaScratch);
    }

    /** Zeroes the inclusive {@code [x0, x1] × [y0, y1]} box of a packed {@code stride}-wide byte
     *  buffer, one {@link Arrays#fill} per row — the dirty-region frame clear (both the supersampled
     *  grid and the device-res blit buffer).  Callers guarantee {@code x0 ≤ x1} and {@code y0 ≤ y1}. */
    private void clearRegion(byte[] buf, int stride, int x0, int x1, int y0, int y1) {
        for (int y = y0; y <= y1; y++) {
            int base = y * stride;
            Arrays.fill(buf, base + x0, base + x1 + 1, (byte) 0);
        }
    }

    /** Lazily builds the digital-phosphor count→alpha LUT once from the (compile-time)
     *  intensity constants, indexed by {@code min(count, }{@link #DPO_SATURATION_COUNT}{@code )}:
     *  {@code alpha(0) = 0}; else {@link #DPO_SINGLE_HIT_ALPHA} {@code + (1 −
     *  DPO_SINGLE_HIT_ALPHA)·min(1, ln(1+count)/ln(1+DPO_SATURATION_COUNT))}, scaled to
     *  8-bit.  Precomputed so the hot loop never evaluates {@code ln()}. */
    private byte[] phosphorLut() {
        if (phosphorLut == null) {
            byte[] lut = new byte[DPO_SATURATION_COUNT + 1];
            if (DPO_SINGLE_HIT_ALPHA >= 1.0) {
                // Floor at full brightness → every hit is full alpha; no log grading needed.
                Arrays.fill(lut, 1, lut.length, (byte) Math.round(DPO_ALPHA_MAX));
            } else {
                double denom = Math.log(1.0 + DPO_SATURATION_COUNT);
                for (int c = 1; c <= DPO_SATURATION_COUNT; c++) {
                    double norm = Math.min(1.0, Math.log(1.0 + c) / denom);
                    double a = DPO_SINGLE_HIT_ALPHA + (1.0 - DPO_SINGLE_HIT_ALPHA) * norm;
                    lut[c] = (byte) Math.round(a * DPO_ALPHA_MAX);
                }
            }
            phosphorLut = lut;   // lut[0] left 0 → count 0 fully transparent
        }
        return phosphorLut;
    }
}
