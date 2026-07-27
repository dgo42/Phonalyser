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

import org.edgo.audio.measure.gui.common.Lanczos;

import lombok.experimental.UtilityClass;

/**
 * Digital-phosphor accumulation and coverage pen for the oscilloscope's dense trace
 * (more than one sample per pixel).  Pure signal math (no SWT, no view state),
 * unit-testable in isolation.
 *
 * <p>{@link #columnCrossings} is the accumulation: in one pass over the displayed
 * window it counts, per pixel, how many consecutive-sample spans cross it — the
 * pixel's dwell time, the DSO "phosphor" brightness.  Each sample pair is SPLIT at
 * every pixel-column boundary it crosses and each sub-span registered in its own
 * column (through a per-column difference array prefix-summed on the fly), so a
 * diagonal reads as a diagonal instead of one full-height block per column.  With
 * sin&nbsp;x/x rails on it also exports, per column, the band-limited value-extent as
 * unrounded row-space "band" ends: {@link #refineExtreme} widens an extreme only
 * where a real sample is a genuine LOCAL extremum (the {@link #isLocalExtremum}
 * guard), so a monotone edge crossing bounds the band and adjacent columns of a
 * split diagonal meet exactly at the shared crossing.
 *
 * <p>{@link #penRasterize} strokes the configured trace width along that band as a
 * true round coverage pen (radius {@code lineWidth/2}): a swept stadium over the
 * column's recorded x-extent, or — for a steep single-traversal column — a tilted
 * {@link #capsuleColumn} so a near-vertical flank's side fringe ramps with y.  The
 * whole rasterisation runs on a supersampled sub-pixel grid (with
 * {@link #fringeDilate} appending the vector stroke's AA fringe) and is box-averaged
 * down ({@link #downsampleBox}), so every blitted pixel carries a true decimal
 * coverage.
 */
@UtilityClass
public class TraceEnvelope {

    /** Sub-sample search step (in samples) for {@link #refineExtreme} — the old ScopeView
     *  {@code RECON_REFINE_STEP}: 0.1 already lands the recovered crest within a fraction of
     *  a canvas pixel, so a finer grid buys nothing. */
    private static final double RECON_REFINE_STEP = 0.1;

    /** Four quarter-pixel horizontal subsample offsets across an output pixel column
     *  {@code [x, x+1)} for the swept-stadium pen ({@link #penRasterize}): probing the round cap
     *  at the column quarters restores the side anti-aliasing that point-sampling the single
     *  column centre destroyed at integral/half pen widths.  Allocated once at class load (read
     *  only), so the pen's hot path still allocates nothing. */
    private static final double[] SUBSAMPLE_OFFSETS = {0.125, 0.375, 0.625, 0.875};

    /** How far a steep column's band row-span ({@code bandBot − bandTop}) may exceed the path's
     *  endpoint {@code |entryY − exitY|} and still be treated as a SINGLE monotone traversal by the
     *  {@link #penRasterize} capsule branch — rows, on the supersampled grid.  The band spans the
     *  sinc-refined value-extent, which widens a monotone flank a little past its raw endpoints;
     *  2.0 rows absorbs that rail widening without admitting a genuine up-then-down (multi-crossing)
     *  column, whose band is far taller than its {@code |entryY − exitY|} and must stay a stadium. */
    private static final double CAPSULE_TRAVERSAL_SLACK = 2.0;

    /** Reconstructs the band-limited curve within ±1 sample of the extreme sample at
     *  {@code idx} and returns the more-extreme of {@code seed} (the raw sample) and
     *  the curve — recovering a crest/trough that drifted between samples. */
    private float refineExtreme(float[] data, int n, int idx, double step, float seed, boolean findMax) {
        float best = seed;
        double lo = Math.max(0.0, idx - 1.0);
        double hi = Math.min(n - 1.0, idx + 1.0);
        for (double pos = lo; pos <= hi; pos += step) {
            float v = (float) Lanczos.lanczos(data, n, pos, 1.0);
            if (findMax ? v > best : v < best) best = v;
        }
        return best;
    }

    /** Consumer of one non-zero per-pixel crossing count from {@link #columnCrossings} —
     *  pixel column {@code x}, row {@code y}, and how many sample spans cover that pixel
     *  ({@code count} &ge; 1).  Keeps the accumulation pure and unit-testable: the renderer
     *  passes a sink that writes {@code alpha[y*width + x]} through its intensity LUT, a
     *  test passes one that records the counts. */
    @FunctionalInterface
    public interface CrossingSink {
        void accept(int x, int y, int count);
    }

    /**
     * Digital-phosphor accumulation (more than one sample per pixel): in a single pass
     * over the displayed window {@code [dispStart, dispStart+dispCount]} it counts, for
     * every pixel, how many consecutive-sample spans cross it — the pixel's dwell time,
     * which the renderer maps to brightness.  Pure math (no SWT, no view state), streamed
     * so the hot loop allocates nothing.
     *
     * <p>For each consecutive sample pair (both in {@code [0, n)} — a blank / out-of-data
     * sample breaks the chain, exactly as the vector renderer blanked {@code pos∈[0, n−1]}), the
     * segment is SPLIT at every pixel-column boundary it crosses and each sub-span registered in
     * ITS own column, so a diagonal reads as a diagonal rather than one full-height block per
     * column (a staircase at any pen width — the failure of the old whole-span-to-col0 attribution):
     * <ul>
     *   <li>each sample's fractional x is {@code xf = (sampleIdx − dispStart − subSampleOffset)·
     *       pxPerSample} ({@code pxPerSample = width/dispCount}); the pair spans columns
     *       {@code [⌊xf0⌋, ⌊xf1⌋]}, both clamped to {@code [0, width)} — equal in the deep-zoom
     *       regime, {@code col0} and {@code col0+1} across a mid-pair boundary at 1&ndash;3 spp
     *       ({@code pxPerSample < 1} ⇒ at most one boundary, but the loop is written generally);
     *   <li>at each interior boundary {@code b} the crossing value is LINEAR-interpolated,
     *       {@code v_b = v0 + (v1−v0)·(b−xf0)/(xf1−xf0)}; the sub-span for a column runs between its
     *       two boundary/sample values (reusing the pair's already-rounded end rows for the sample
     *       ends), each end via the {@code round(centerY − (value − dcOffset)·vScale)} value→row
     *       transform clamped to {@code [0, height−1]};
     *   <li>each sub-span's inclusive row range {@code [lo, hi]} is registered in the pooled
     *       per-column DIFFERENCE array {@code diff}: {@code diff[lo]++}, {@code diff[hi+1]--} (a
     *       sub-span within one pixel row &rarr; that single row +1 = dwell).  A pair crossing a
     *       boundary counts 1 in EACH column it touches — the per-column dwell the smear destroyed.
     * </ul>
     * Spans are the RAW crossing rows — no line-width dilation here.  Trace width is a
     * coverage-pen job on the exported band ({@link #penRasterize}), not this count pass, which
     * resolves one column at a time; the counts stay the true per-pixel dwell time.
     *
     * <p>With {@code sinc} set, each column's band ends are exported as UNROUNDED row-space floats
     * into {@code bandTop}/{@code bandBot} ({@link #flushRails}): the band spans the column's
     * VALUE-EXTENT — the min/max of every value it received, real samples AND mid-slope boundary
     * crossings — so adjacent columns of a split diagonal meet exactly at the shared crossing and
     * the band connects instead of stair-stepping.  Its extreme SAMPLES are additionally refined by
     * {@link #refineExtreme} and allowed to WIDEN the band only where a sample is itself the extent
     * (a genuine interior crest/trough the clocks let drift between samples); a crossing-defined
     * edge is left unrefined at the crossing.  Both ends run back through the same {@code centerY −
     * (value − dcOffset)·vScale} value→row transform WITHOUT rounding, handed to the pen for a
     * coverage-accurate outline.  With {@code sinc} clear the crossing counts are byte-identical to
     * the raw accumulation and the band is left all-{@link Float#NaN}.
     *
     * <p>Samples arrive in monotonic column order, so the pass streams: when the column
     * advances it prefix-sums the touched rows of {@code diff} into crossing counts, hands
     * each non-zero {@code (x, y, count)} to {@code sink}, then zeroes just the touched range
     * (never the whole array) so the next column starts clean.  When {@code counts} is false the
     * entire count pass is skipped — no {@code diff} writes, no prefix-sum flush, no {@code sink}
     * calls (so {@code diff} may be {@code null}); only the sin&nbsp;x/x band export runs.  The
     * renderer sets it false at the full-brightness floor, where the sink was already a no-op and
     * the pen alone draws the trace.  {@code diff} is caller-pooled
     * and must be at least {@code height + 2} long (the {@code diff[hi+1]--} at {@code hi ==
     * height−1} writes index {@code height}); it must be all-zero on entry and is left
     * all-zero on exit.  {@code bandTop}/{@code bandBot} are caller-pooled row-space band outputs
     * (each at least {@code width} long); this pass fills them {@link Float#NaN} on entry and
     * writes a column's band only when {@code sinc} is set, so a blank column stays NaN.
     */
    public void columnCrossings(float[] data, int n, int dispStart, int dispCount,
                                int width, int height, double subSampleOffset,
                                double centerY, double vScale, double dcOffset,
                                boolean sinc, boolean counts, int[] diff, float[] bandTop, float[] bandBot,
                                float[] bandXLo, float[] bandXHi, float[] bandEntryX,
                                float[] bandEntryY, float[] bandExitX, float[] bandExitY,
                                CrossingSink sink) {
        if (dispCount < 2 || width <= 0 || height <= 0) return;
        for (int x = 0; x < width; x++) {
            bandTop[x] = Float.NaN; bandBot[x] = Float.NaN;
            bandXLo[x] = Float.NaN; bandXHi[x] = Float.NaN;
            bandEntryX[x] = Float.NaN; bandEntryY[x] = Float.NaN;
            bandExitX[x] = Float.NaN; bandExitY[x] = Float.NaN;
        }
        double pxPerSample = (double) width / dispCount;
        int maxRow = height - 1;
        int last = dispStart + dispCount;
        int   curCol = -1;
        int   curLo = 0;
        int   curHi = 0;
        int   curIdxMin = 0;
        int   curIdxMax = 0;
        float curValMin = Float.POSITIVE_INFINITY;   // value-extent of ALL the column receives
        float curValMax = Float.NEGATIVE_INFINITY;   // (samples AND boundary crossings), pre-round
        float curXLo = Float.POSITIVE_INFINITY;      // x-extent of the path within the column
        float curXHi = Float.NEGATIVE_INFINITY;      // (fractional, ⊂ [col, col+1]) — the pen sweeps
        float curEntryX = Float.NaN;                 // path ENTRY point (first sub-span left) and
        float curEntryY = Float.NaN;                 // EXIT point (latest sub-span right), unrounded
        float curExitX = Float.NaN;                  // row space — the tilted-capsule endpoints the
        float curExitY = Float.NaN;                  // pen sweeps for a steep single-traversal column
        boolean curHasSample = false;                // did any REAL sample land in this column?
        boolean prevOk = false;
        int prevRow = 0;
        for (int k = dispStart; k <= last; k++) {
            boolean ok = k >= 0 && k < n;
            int row = ok ? valueToRow(data[k], centerY, vScale, dcOffset, maxRow) : 0;
            if (prevOk && ok) {                                  // pair (k-1, k) — both valid
                // Fractional x of each sample; the segment spans columns [col0, col1] (col1 == col0
                // in the deep-zoom regime, col0+1 across a mid-pair boundary at 1-3 spp).  Splitting
                // the pair at each crossed boundary — instead of dumping its whole span into col0 —
                // is what lets a diagonal anti-alias instead of stair-stepping one full block/column.
                double xf0 = (k - 1 - dispStart - subSampleOffset) * pxPerSample;
                double xf1 = (k     - dispStart - subSampleOffset) * pxPerSample;
                int col0 = clampCol((int) Math.floor(xf0), width);
                int col1 = clampCol((int) Math.floor(xf1), width);
                for (int c = col0; c <= col1; c++) {
                    // Sub-span endpoints: the real sample at each pair end, a LINEAR-interpolated
                    // boundary crossing at each interior column edge (mid-slope, where the band-
                    // limited curve is closest to linear).  Reuse the pair's already-rounded rows
                    // for the sample ends so an unsplit pair stays byte-identical to before.
                    float valL = c == col0 ? data[k - 1] : (float) crossAt(c,     data[k - 1], data[k], xf0, xf1);
                    float valR = c == col1 ? data[k]     : (float) crossAt(c + 1, data[k - 1], data[k], xf0, xf1);
                    int rowL = c == col0 ? prevRow : valueToRow(valL, centerY, vScale, dcOffset, maxRow);
                    int rowR = c == col1 ? row     : valueToRow(valR, centerY, vScale, dcOffset, maxRow);
                    int lo = Math.min(rowL, rowR);               // raw crossing rows (pen adds width)
                    int hi = Math.max(rowL, rowR);               // both already clamped to [0, maxRow]
                    // The sub-span's fractional X endpoints within this column (⊂ [c, c+1]); these are
                    // also the path's entry x (column start) and running exit x (every sub-span).
                    float sxL = (float) Math.max(c, Math.min(c + 1.0, xf0));
                    float sxR = (float) Math.max(c, Math.min(c + 1.0, xf1));
                    if (c != curCol) {                           // column advanced → flush the last
                        if (curCol >= 0) {
                            if (counts) flushColumn(curCol, curLo, curHi, diff, sink);
                            if (sinc) flushRails(curCol, curHasSample, curIdxMin, curIdxMax,
                                    curValMin, curValMax,
                                    curXLo, curXHi, curEntryX, curEntryY, curExitX, curExitY,
                                    data, n, centerY, vScale, dcOffset,
                                    bandTop, bandBot, bandXLo, bandXHi,
                                    bandEntryX, bandEntryY, bandExitX, bandExitY);
                        }
                        curCol = c;
                        curLo = lo;
                        curHi = hi;
                        curValMin = Float.POSITIVE_INFINITY;
                        curValMax = Float.NEGATIVE_INFINITY;
                        curXLo = Float.POSITIVE_INFINITY;
                        curXHi = Float.NEGATIVE_INFINITY;
                        curHasSample = false;
                        // Path ENTRY = the column's first sub-span left point, unrounded row of valL.
                        curEntryX = sxL;
                        curEntryY = (float) (centerY - (valL - dcOffset) * vScale);
                    } else {
                        if (lo < curLo) curLo = lo;
                        if (hi > curHi) curHi = hi;
                    }
                    // Path EXIT = the latest sub-span's right point, unrounded row of valR — refreshed
                    // on every sub-span so the column's final sub-span wins.
                    curExitX = sxR;
                    curExitY = (float) (centerY - (valR - dcOffset) * vScale);
                    // Fold the sub-span's endpoint VALUES into the column's value-extent (this is
                    // what carries the mid-slope crossing so adjacent columns share it exactly).
                    if (valL < curValMin) curValMin = valL;
                    if (valL > curValMax) curValMax = valL;
                    if (valR < curValMin) curValMin = valR;
                    if (valR > curValMax) curValMax = valR;
                    // Fold the sub-span's fractional X-RANGE into the column's x-extent — the path's
                    // true horizontal extent, so the pen sweeps only where the trace actually runs
                    // (a vertical flank renders at its exact fractional x, not the whole column).
                    if (sxL < curXLo) curXLo = sxL;
                    if (sxR > curXHi) curXHi = sxR;
                    // Extreme-index tracking stays sample-only (refinement needs real samples): fold
                    // k-1 into col0 and k into col1.  A shared sample re-folds harmlessly (strict).
                    if (c == col0) {
                        if (!curHasSample) { curIdxMin = k - 1; curIdxMax = k - 1; curHasSample = true; }
                        else {
                            if (data[k - 1] < data[curIdxMin]) curIdxMin = k - 1;
                            if (data[k - 1] > data[curIdxMax]) curIdxMax = k - 1;
                        }
                    }
                    if (c == col1) {
                        if (!curHasSample) { curIdxMin = k; curIdxMax = k; curHasSample = true; }
                        else {
                            if (data[k] < data[curIdxMin]) curIdxMin = k;
                            if (data[k] > data[curIdxMax]) curIdxMax = k;
                        }
                    }
                    if (counts) {
                        diff[lo]++;
                        diff[hi + 1]--;
                    }
                }
            }
            prevOk = ok;
            prevRow = row;
        }
        if (curCol >= 0) {
            if (counts) flushColumn(curCol, curLo, curHi, diff, sink);
            if (sinc) flushRails(curCol, curHasSample, curIdxMin, curIdxMax,
                    curValMin, curValMax,
                    curXLo, curXHi, curEntryX, curEntryY, curExitX, curExitY,
                    data, n, centerY, vScale, dcOffset,
                    bandTop, bandBot, bandXLo, bandXHi,
                    bandEntryX, bandEntryY, bandExitX, bandExitY);
        }
    }

    /** Applies the shared {@code round(centerY − (value − dcOffset)·vScale)} value→row transform
     *  and clamps to {@code [0, height−1]} — the same mapping the vector trace uses for Y. */
    private int valueToRow(float value, double centerY, double vScale, double dcOffset, int maxRow) {
        long r = Math.round(centerY - (value - dcOffset) * vScale);
        return (int) Math.max(0L, Math.min(maxRow, r));
    }

    /** Clamps a raw pixel column into {@code [0, width)}. */
    private int clampCol(int col, int width) {
        return col < 0 ? 0 : col >= width ? width - 1 : col;
    }

    /** Whether the sample at {@code idx} is a LOCAL extremum against its neighbour samples —
     *  the precondition for {@link #refineExtreme}: only an interior crest/trough has its true
     *  peak within ±1 sample; a monotone edge sample's window would annex the neighbouring
     *  column's curve.  An array-edge sample (no two neighbours) is never refined. */
    private boolean isLocalExtremum(float[] data, int n, int idx, boolean findMax) {
        if (idx <= 0 || idx >= n - 1) return false;
        return findMax
                ? data[idx] >= data[idx - 1] && data[idx] >= data[idx + 1]
                : data[idx] <= data[idx - 1] && data[idx] <= data[idx + 1];
    }

    /** Linear value of the segment {@code (xf0,v0)→(xf1,v1)} at integer column boundary {@code b}
     *  (with {@code xf0 < b < xf1}): {@code v0 + (v1−v0)·(b−xf0)/(xf1−xf0)}. */
    private double crossAt(int b, float v0, float v1, double xf0, double xf1) {
        return v0 + (v1 - v0) * ((b - xf0) / (xf1 - xf0));
    }

    /** Prefix-sums {@code diff[lo..hi]} into per-row crossing counts, emits each non-zero
     *  {@code (col, y, count)} to {@code sink}, and zeroes the touched range — {@code [lo, hi]}
     *  plus {@code diff[hi+1]} (where the topmost span's decrement landed) — so {@code diff}
     *  is clean for the next column. */
    private void flushColumn(int col, int lo, int hi, int[] diff, CrossingSink sink) {
        int running = 0;
        for (int y = lo; y <= hi; y++) {
            running += diff[y];
            if (running > 0) sink.accept(col, y, running);
            diff[y] = 0;
        }
        diff[hi + 1] = 0;
    }

    /** Exports a just-flushed column's band ends for {@link #penRasterize} to stroke, as unrounded
     *  row-space floats through the SAME {@code centerY − (value − dcOffset)·vScale} value→row
     *  transform as the accumulation but WITHOUT rounding.  The band spans the column's VALUE-EXTENT
     *  ({@code valExtMin}/{@code valExtMax} — every value it received, samples AND boundary
     *  crossings), so a split diagonal's adjacent columns meet exactly at the shared crossing.  The
     *  extreme samples ({@code idxMin}/{@code idxMax}) are refined against the full data by
     *  {@link #refineExtreme} and widen the band only where a sample is itself the extent (a genuine
     *  interior crest/trough between clocks); a crossing-defined edge stays at the crossing. */
    private void flushRails(int col, boolean hasSample, int idxMin, int idxMax,
                            float valExtMin, float valExtMax,
                            float xLo, float xHi, float entryX, float entryY, float exitX, float exitY,
                            float[] data, int n, double centerY,
                            double vScale, double dcOffset, float[] bandTop, float[] bandBot,
                            float[] bandXLo, float[] bandXHi,
                            float[] bandEntryX, float[] bandEntryY, float[] bandExitX, float[] bandExitY) {
        // The band spans the column's value-extent (samples AND boundary crossings).  The refined
        // sample crest/trough may WIDEN it only where the extreme SAMPLE is itself that extent
        // (data[idx] == valExt*, a genuine interior peak the clocks let drift between samples);
        // where a mid-slope boundary crossing is the extent instead (a monotone edge split across
        // the seam), the crossing bounds the band — so adjacent columns meet EXACTLY at the shared
        // crossing value and the diagonal connects rather than stair-steps.  valExt* already
        // includes data[idx], so the guard is true only on equality (no crossing beyond the sample);
        // an unsplit column is then bit-identical to refining the raw extreme as before.
        // A MIDDLE column of a pair crossing several cell boundaries holds NO real sample —
        // idxMin/idxMax would be stale from an earlier column, so refinement is skipped and the
        // boundary crossings alone bound the band.
        // Refinement also requires the extreme sample to be a LOCAL extremum against its
        // neighbour samples: on a monotone flank the column's top sample sits at the cell edge
        // and the ±1-sample search window would ANNEX the adjacent column's higher curve —
        // ballooning the band by up to a sample's slope whenever a sample lands on a boundary
        // (the sporadic per-stripe artifacts on dense tones).  A true interior crest/trough
        // (both neighbours below/above) still refines to the drifted inter-sample peak.
        float maxVal = valExtMax;
        float minVal = valExtMin;
        if (hasSample) {
            if (data[idxMax] >= valExtMax && isLocalExtremum(data, n, idxMax, true)) {
                maxVal = Math.max(refineExtreme(data, n, idxMax, RECON_REFINE_STEP,
                        data[idxMax], true), valExtMax);
            }
            if (data[idxMin] <= valExtMin && isLocalExtremum(data, n, idxMin, false)) {
                minVal = Math.min(refineExtreme(data, n, idxMin, RECON_REFINE_STEP,
                        data[idxMin], false), valExtMin);
            }
        }
        bandTop[col] = (float) (centerY - (maxVal - dcOffset) * vScale);
        bandBot[col] = (float) (centerY - (minVal - dcOffset) * vScale);
        // The path's true horizontal extent within the column — the pen sweeps [xLo, xHi]
        // instead of the whole column, so a vertical flank strokes at its exact fractional x.
        bandXLo[col] = xLo;
        bandXHi[col] = xHi;
        // The path's ENTRY/EXIT points (unrounded row space) — the tilted-capsule endpoints the
        // steep-column pen branch sweeps so a near-vertical flank's neighbour fringe RAMPS with y.
        bandEntryX[col] = entryX;
        bandEntryY[col] = entryY;
        bandExitX[col]  = exitX;
        bandExitY[col]  = exitY;
    }

    /**
     * Strokes a true round coverage pen of EXACTLY {@code lineWidth} pixels (the live fractional
     * trace-width pref — no rounding) along a per-column band and composites it into the packed
     * {@code width×height} alpha buffer {@code out} ({@code out[y·width + x]}, unsigned 0..255)
     * via max — so it lays OVER an already-packed interior without erasing it.  Pure math, no
     * allocation.
     *
     * <p><b>Swept-stadium model.</b>  {@code bandTop[x]}/{@code bandBot[x]} are the column's band
     * ends in UNROUNDED row space ({@code bandTop ≤ bandBot} for the usual {@code vScale > 0}); a
     * {@link Float#NaN} column is blank and contributes nothing.  With {@code h = lineWidth/2} the
     * pen is a disk of radius {@code h} swept along the path's TRUE horizontal extent within the
     * source column — {@code [bandXLo[c], bandXHi[c]]}, the fractional x-range the accumulation
     * recorded (falling back to the full column when NaN) — a stadium (the Minkowski sum of the
     * swept band with the disk).  Its coverage of an output pixel {@code (x, j)} is estimated by
     * FOUR quarter-pixel horizontal subsamples ({@link #SUBSAMPLE_OFFSETS})
     * {@code u ∈ {x+0.125, x+0.375, x+0.625, x+0.875}}:
     * <ul>
     *   <li>{@code d(u)} = distance from {@code u} to {@code [xLo, xHi]} (0 inside, else the gap to
     *       the nearer edge); a subsample with {@code d > h} is off the sweep and counts 0;
     *   <li>else the disk half-height there is {@code vy = √(h² − d²)}, so the swept band spans
     *       {@code [bandTop[c] − vy, bandBot[c] + vy]} and the subsample's vertical coverage is
     *       {@code clamp(overlap of [j, j+1) with that interval, 0, 1)} (AA across rows);
     *   <li>the column's contribution is {@code alpha255 · mean(the four subsample coverages)}
     *       (skipped subsamples counting 0), and {@code out[x,j] = max(out, round(·))}.
     * </ul>
     * Every source column {@code c} with {@code |c − x| ≤ ⌈h⌉ + 1} can reach output column
     * {@code x}; the rows visited are bounded by the widest subsample interval, clamped to
     * {@code [0, height)}; a degenerate/blank contribution adds nothing.  Max-combining across
     * source columns lays each column's stadium over the others.  {@code alpha255} is the
     * single-hit/outline alpha (255 at the full-brightness floor, scaled down when phosphor
     * grading is on).
     *
     * <p>The four-subsample horizontal probe is what restores the side anti-aliasing: point-sampling
     * one column centre made the horizontal coverage {@code h − |dx| + 0.5} land on exactly 0 or 1
     * at integral/half pen widths — NO side fringe at widths 1, 2, 3.  A horizontal hairline band
     * ({@code bandTop == bandBot}) centred in a row at {@code lineWidth = 1} lights that row EXACTLY
     * 255 ({@code d = 0} for all four subsamples of its own column, {@code vy = 0.5}, interval
     * height 1 centred), matching the sparse AA stroke.  A vertical flank strokes at its exact
     * fractional x with the disk-profile side fringes — the recorded {@code [xLo, xHi]} extent is
     * what the old full-column sweep lacked (it rendered one-column bands ~1&nbsp;px wide with
     * cell-quantised sides at any supersampling factor).
     *
     * <p><b>Steep-column capsule branch.</b>  A near-vertical flank's x DRIFTS with y, but the
     * column's {@code [xLo, xHi]} is a single flat span, so the swept stadium above gives the
     * neighbour a constant plateau of side fringe over the whole flank — wrong, the coverage should
     * RAMP as the true path crosses the column boundary.  When a source column is {@code steep}
     * ({@code bandBot − bandTop > bandXHi − bandXLo}) AND a {@code singleTraversal}
     * ({@code (bandBot − bandTop) − |entryY − exitY| ≤ }{@link #CAPSULE_TRAVERSAL_SLACK}) — i.e. one
     * monotone pass, not an up-then-down band — its path entry/exit points ({@code bandEntryX/Y},
     * {@code bandExitX/Y}) drive {@link #capsuleColumn} instead: the disk is swept along the TILTED
     * segment {@code (entryX,entryY)→(exitX,exitY)}, so per row the neighbour's coverage ramps with
     * the drifting x.  A truly vertical stroke ({@code entryX == exitX}) keeps a constant fringe; a
     * {@link Float#NaN} entry/exit (no capsule geometry recorded — full-column stadium fixtures) or
     * a non-steep / multi-crossing column falls through to the four-subsample stadium unchanged.
     *
     * <p><b>Column bounds.</b>  {@code bandColLo}/{@code bandColHi} are the first/last non-NaN band
     * columns (an empty band is {@code bandColLo &gt; bandColHi} — nothing is stroked).  A source
     * column with a band lives only in {@code [bandColLo, bandColHi]}, and a source {@code c}
     * reaches output {@code x} only when {@code |c − x| ≤ reach}, so an output column outside
     * {@code [bandColLo − reach, bandColHi + reach]} sees none but NaN sources and stays 0.  The
     * outer loop is bounded to that range — identical output to sweeping every column (the interior
     * already {@code continue}s on NaN sources), just without the wasted NaN scans.
     */
    void penRasterize(float[] bandTop, float[] bandBot, float[] bandXLo, float[] bandXHi,
                      float[] bandEntryX, float[] bandEntryY, float[] bandExitX, float[] bandExitY,
                      int width, int height, float lineWidth, int alpha255,
                      int bandColLo, int bandColHi, byte[] out) {
        if (width <= 0 || height <= 0) return;
        if (bandColLo > bandColHi) return;    // no non-NaN band this frame — nothing to stroke
        double h = lineWidth * 0.5;
        double hSq = h * h;
        int reach = (int) Math.ceil(h) + 1;   // source columns each side that can reach out[x] (|c−x| ≤ ⌈h⌉+1)
        int maxRow = height - 1;
        int xStart = Math.max(0, bandColLo - reach);
        int xEnd   = Math.min(width - 1, bandColHi + reach);
        for (int x = xStart; x <= xEnd; x++) {
            for (int dx = -reach; dx <= reach; dx++) {
                int c = x + dx;
                if (c < 0 || c >= width) continue;
                float top = bandTop[c];
                float bot = bandBot[c];
                if (Float.isNaN(top) || Float.isNaN(bot)) continue;
                // The pen sweeps the path's TRUE x-extent within the column, not the whole
                // column width — a vertical flank strokes at its exact fractional x.
                double sxLo = bandXLo[c];
                double sxHi = bandXHi[c];
                if (Double.isNaN(sxLo)) { sxLo = c; sxHi = c + 1.0; }   // no extent recorded → full column
                // Steep single-traversal column → tilted round-capped CAPSULE (the maintainer's
                // per-row ramp) instead of the flat-plateau stadium.  NaN entry/exit (no capsule
                // geometry — e.g. full-column stadium fixtures) or a non-steep / multi-crossing
                // (band far taller than |ΔY|) column falls through to the stadium below, unchanged.
                float entryY = bandEntryY[c];
                float exitY  = bandExitY[c];
                if (!Float.isNaN(entryY) && !Float.isNaN(exitY)
                        && (bot - top) > (sxHi - sxLo)
                        && (bot - top) - Math.abs(entryY - exitY) <= CAPSULE_TRAVERSAL_SLACK) {
                    capsuleColumn(bandEntryX[c], entryY, bandExitX[c], exitY,
                            x, h, hSq, height, alpha255, width, out);
                    continue;
                }
                // Stadium sweep: probe the pen (disk radius h) swept along source column c's full
                // width [c, c+1) at four quarter-pixel positions of the output pixel [x, x+1).  Each
                // subsample a distance d from [c, c+1) sees the disk half-height √(h²−d²) (or is
                // skipped when d > h), and the swept band there is [top−vy, bot+vy].
                double vy0 = subsampleVy(x + SUBSAMPLE_OFFSETS[0], sxLo, sxHi, h, hSq);
                double vy1 = subsampleVy(x + SUBSAMPLE_OFFSETS[1], sxLo, sxHi, h, hSq);
                double vy2 = subsampleVy(x + SUBSAMPLE_OFFSETS[2], sxLo, sxHi, h, hSq);
                double vy3 = subsampleVy(x + SUBSAMPLE_OFFSETS[3], sxLo, sxHi, h, hSq);
                double vyMax = Math.max(Math.max(vy0, vy1), Math.max(vy2, vy3));
                if (vyMax < 0.0) continue;                      // all four subsamples off the sweep
                int jLo = (int) Math.floor(top - vyMax);        // widest interval bounds the rows
                int jHi = (int) Math.ceil(bot + vyMax) - 1;
                if (jLo < 0) jLo = 0;
                if (jHi > maxRow) jHi = maxRow;
                for (int j = jLo; j <= jHi; j++) {
                    double cov = subsampleVcov(vy0, top, bot, j) + subsampleVcov(vy1, top, bot, j)
                               + subsampleVcov(vy2, top, bot, j) + subsampleVcov(vy3, top, bot, j);
                    int a = (int) Math.round(alpha255 * cov / SUBSAMPLE_OFFSETS.length);  // mean of 4
                    if (a <= 0) continue;
                    int idx = j * width + x;
                    if ((out[idx] & 0xFF) < a) out[idx] = (byte) a;   // max-combine across source cols
                }
            }
        }
    }

    /** Rasterises ONE steep single-traversal source column as a tilted round-capped CAPSULE into
     *  output column {@code x}: the pen disk (radius {@code h}, {@code hSq == h²}) swept along the
     *  straight path {@code (entryX,entryY)→(exitX,exitY)} rather than the vertical band, so the
     *  neighbour's side coverage RAMPS as the path's x drifts with y (the maintainer's per-row
     *  interpolation) instead of the column's flat plateau.  For each output row {@code j} in
     *  {@code [⌊min(entryY,exitY)−h⌋ … ⌈max(entryY,exitY)+h⌉−1]} clamped to {@code [0, height)}:
     *  <ul>
     *    <li>{@code yc = j + 0.5}; {@code t = (entryY==exitY) ? 0.5 : clamp((yc−entryY)/(exitY−entryY),
     *        0, 1)}; the path x at that row is {@code xAt = entryX + (exitX−entryX)·t};
     *    <li>{@code dy} = distance from {@code yc} to the flank body {@code [min,max](entryY,exitY)}
     *        (0 inside); {@code dy > h} → beyond the round cap, skip; else the cap half-width is
     *        {@code hw = √(h²−dy²)};
     *    <li>the row's horizontal coverage of {@code [x, x+1)} is {@code clamp(min(xAt+hw, x+1) −
     *        max(xAt−hw, x), 0, 1)} — 0 for an output column the swept path never reaches, so the
     *        outer reach loop needs no explicit column bound;
     *    <li>{@code out[x,j] = max(out, round(alpha255·hcov))}.
     *  </ul>
     *  A vertical path ({@code entryX == exitX}) gives a CONSTANT {@code xAt}, hence a constant
     *  per-row fringe — the correct behaviour, deliberately untapered.  Pure, allocation-free. */
    private void capsuleColumn(double entryX, double entryY, double exitX, double exitY,
                               int x, double h, double hSq, int height, int alpha255,
                               int width, byte[] out) {
        double yTop = Math.min(entryY, exitY);
        double yBot = Math.max(entryY, exitY);
        int jLo = (int) Math.floor(yTop - h);
        int jHi = (int) Math.ceil(yBot + h) - 1;
        if (jLo < 0) jLo = 0;
        if (jHi > height - 1) jHi = height - 1;
        double dyDen = exitY - entryY;
        for (int j = jLo; j <= jHi; j++) {
            double yc = j + 0.5;
            double dy = yc < yTop ? yTop - yc : yc > yBot ? yc - yBot : 0.0;
            if (dy > h) continue;                          // beyond the round cap
            double hw = Math.sqrt(hSq - dy * dy);
            double t = dyDen == 0.0 ? 0.5 : (yc - entryY) / dyDen;
            if (t < 0.0) t = 0.0; else if (t > 1.0) t = 1.0;
            double xAt = entryX + (exitX - entryX) * t;
            double lo = Math.max(xAt - hw, x);
            double hi = Math.min(xAt + hw, x + 1.0);
            double hcov = hi - lo;
            if (hcov <= 0.0) continue;                     // this output column is off the swept path
            if (hcov > 1.0) hcov = 1.0;
            int a = (int) Math.round(alpha255 * hcov);
            if (a <= 0) continue;
            int idx = j * width + x;
            if ((out[idx] & 0xFF) < a) out[idx] = (byte) a;   // max-combine across source cols
        }
    }

    /** Vertical half-height of the swept round pen (disk radius {@code h}, {@code hSq == h²}) at
     *  horizontal subsample {@code u} over the path's x-extent {@code [xLo, xHi]} within the source
     *  column: {@code √(h²−d²)} where {@code d} is the distance from {@code u} to that interval
     *  (0 when inside).  Returns {@code −1} — a skip marker counting 0 in the four-subsample mean —
     *  when {@code d > h} (the subsample lies off the sweep). */
    private double subsampleVy(double u, double xLo, double xHi, double h, double hSq) {
        double d = u < xLo ? xLo - u : u > xHi ? u - xHi : 0.0;
        if (d > h) return -1.0;
        return Math.sqrt(hSq - d * d);
    }

    /** Vertical coverage of pixel row {@code [j, j+1)} by one subsample's swept band
     *  {@code [top−vy, bot+vy]}, clamped to {@code [0, 1]}; a skipped subsample ({@code vy < 0})
     *  contributes 0. */
    private double subsampleVcov(double vy, double top, double bot, int j) {
        if (vy < 0.0) return 0.0;
        double lo = Math.max(top - vy, j);
        double hi = Math.min(bot + vy, j + 1.0);
        double v = hi - lo;
        return v <= 0.0 ? 0.0 : v > 1.0 ? 1.0 : v;
    }

    /** Appends the vector stroke's AA fringe to a supersampled coverage grid IN PLACE: a
     *  soft-max CONE dilation — {@code out = max over |d| ≤ radius (both axes, separable) of
     *  in[i+d]·(radius+1−|d|)/(radius+1)} — so every coverage edge grows a ramp of graded
     *  neighbours while the interior stays at FULL brightness (max with itself).  This is the
     *  fringe SEMANTICS of the sparse vector stroke: appended OUTSIDE the geometry, not an
     *  energy-conserving blur (a tent convolution dims a 1-px stroke's core to ~75% and smears
     *  it — bench-rejected).  Runs on the sub-cell grid before the box downsample, radius {@code
     *  ss} sub-cells = 1 px of ramp; {@code scratch} is a caller-pooled buffer at least
     *  {@code w·h} (holds the horizontal pass).  Pure.
     *
     *  <p>{@code x0,x1,y0,y1} bound the WRITTEN region (inclusive, clamped): the caller passes the
     *  content bounding box already grown by {@code radius}, so every cell the cone can light lies
     *  inside it and everything outside is 0 in the (cleared) grid.  The horizontal pass fills
     *  {@code radius} extra rows each side (the vertical pass reads them); reads reach {@code radius}
     *  past the box into cells the caller guarantees are 0.  Passing the whole grid
     *  ({@code 0,w−1,0,h−1}) reproduces the full-grid pass byte-for-byte. */
    void fringeDilate(byte[] grid, int w, int h, int radius, byte[] scratch,
                      int x0, int x1, int y0, int y1) {
        int denom = radius + 1;
        int cx0 = Math.max(0, x0);
        int cx1 = Math.min(w - 1, x1);
        int cy0 = Math.max(0, y0);
        int cy1 = Math.min(h - 1, y1);
        int hy0 = Math.max(0, cy0 - radius);       // horizontal pass feeds the ±radius rows the
        int hy1 = Math.min(h - 1, cy1 + radius);   // vertical pass reads back
        for (int y = hy0; y <= hy1; y++) {         // horizontal max-plus cone pass over [cx0, cx1]
            int base = y * w;
            for (int x = cx0; x <= cx1; x++) {
                int best = 0;
                for (int d = -radius; d <= radius; d++) {
                    int sx = x + d;
                    if (sx < 0 || sx >= w) continue;
                    int v = (grid[base + sx] & 0xFF) * (denom - Math.abs(d)) / denom;
                    if (v > best) best = v;
                }
                scratch[base + x] = (byte) best;
            }
        }
        for (int x = cx0; x <= cx1; x++) {         // vertical pass, back into the grid
            for (int y = cy0; y <= cy1; y++) {
                int best = 0;
                for (int d = -radius; d <= radius; d++) {
                    int sy = y + d;
                    if (sy < 0 || sy >= h) continue;
                    int v = (scratch[sy * w + x] & 0xFF) * (denom - Math.abs(d)) / denom;
                    if (v > best) best = v;
                }
                grid[y * w + x] = (byte) best;
            }
        }
    }

    /** Box-averages a supersampled coverage grid down to pixel resolution: every output pixel
     *  {@code (x, y)} of {@code dst} ({@code dstW×dstH}, packed {@code y·dstW + x}) becomes the
     *  rounded mean of its {@code ss×ss} sub-cell block in {@code src} ({@code srcW == ss·dstW}
     *  cells per row, unsigned 0..255) — exact area coverage, run AFTER {@link #fringeDilate}
     *  so edges carry the appended ramp at every grid alignment.  Pure, allocation-free.
     *
     *  <p>{@code ox0,ox1,oy0,oy1} bound the WRITTEN output pixels (inclusive, clamped): only pixels
     *  whose {@code ss×ss} source block can overlap the fringe content are recomputed; every other
     *  output pixel reads an all-zero block (→ 0) and is left as the caller's cleared 0, so passing
     *  the whole image ({@code 0,dstW−1,0,dstH−1}) reproduces the full downsample byte-for-byte. */
    void downsampleBox(byte[] src, int srcW, int ss, byte[] dst, int dstW, int dstH,
                       int ox0, int ox1, int oy0, int oy1) {
        int cells = ss * ss;
        int half  = cells / 2;
        int cy0 = Math.max(0, oy0);
        int cy1 = Math.min(dstH - 1, oy1);
        int cx0 = Math.max(0, ox0);
        int cx1 = Math.min(dstW - 1, ox1);
        for (int y = cy0; y <= cy1; y++) {
            int srcRow0 = y * ss;
            for (int x = cx0; x <= cx1; x++) {
                int srcCol0 = x * ss;
                int sum = 0;
                for (int sy = 0; sy < ss; sy++) {
                    int base = (srcRow0 + sy) * srcW + srcCol0;
                    for (int sx = 0; sx < ss; sx++) {
                        sum += src[base + sx] & 0xFF;
                    }
                }
                dst[y * dstW + x] = (byte) ((sum + half) / cells);
            }
        }
    }
}
