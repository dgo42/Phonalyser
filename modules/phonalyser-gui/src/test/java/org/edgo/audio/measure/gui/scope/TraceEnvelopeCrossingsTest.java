/*
 * Phonalyser - precision audio measurement workbench.
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * Pure-math tests for the digital-phosphor accumulation ({@link
 * TraceEnvelope#columnCrossings}) and its round coverage pen ({@link
 * TraceEnvelope#penRasterize}) - driven through the pure {@link
 * TraceEnvelope.CrossingSink} seam and the packed alpha buffer (no painter, no SWT).
 *
 * <p>Every count fixture uses the identity value->row transform {@code centerY = 0,
 * vScale = −1, dcOffset = 0}, so {@code row = round(value)} and a sample of value
 * {@code v} lands on pixel row {@code v}, making the expected per-row crossing counts
 * exact and readable.  The pen fixtures build {@code bandTop}/{@code bandBot} directly in
 * row space (a {@link Float#NaN} column is blank), so the disk geometry and its
 * anti-aliasing are asserted as exact 8-bit coverage.
 */
class TraceEnvelopeCrossingsTest {

    private static final double CENTER_Y = 0.0;
    private static final double V_SCALE  = -1.0;   // row == round(value)
    private static final double DC       = 0.0;

    /** Runs the accumulation (sin&nbsp;x/x rails off) into a {@code [height][width]} grid of
     *  per-pixel crossing counts (0 where the sink never fired) - allocation is fine in tests. */
    private int[][] crossings(float[] data, int dispStart, int dispCount, int width, int height,
                              double subSampleOffset) {
        int[]   diff    = new int[height + 2];
        float[] bandTop = new float[width];
        float[] bandBot = new float[width];
        float[] bandXLo = new float[width];
        float[] bandXHi = new float[width];
        float[] entryX  = new float[width];
        float[] entryY  = new float[width];
        float[] exitX   = new float[width];
        float[] exitY   = new float[width];
        int[][] grid    = new int[height][width];
        TraceEnvelope.columnCrossings(data, data.length, dispStart, dispCount, width, height,
                subSampleOffset, CENTER_Y, V_SCALE, DC, false, true, diff, bandTop, bandBot,
                bandXLo, bandXHi, entryX, entryY, exitX, exitY, (x, y, count) -> grid[y][x] = count);
        return grid;
    }

    /** Runs {@link TraceEnvelope#penRasterize} with FULL-COLUMN x-extents (the classic stadium
     *  fixtures) into a fresh {@code [height][width]} unsigned alpha grid - allocation is fine
     *  in tests. */
    private int[][] pen(float[] bandTop, float[] bandBot, int width, int height,
                        float lineWidth, int alpha255) {
        float[] xLo = new float[width];
        float[] xHi = new float[width];
        for (int c = 0; c < width; c++) { xLo[c] = c; xHi[c] = c + 1f; }
        return pen(bandTop, bandBot, xLo, xHi, width, height, lineWidth, alpha255);
    }

    /** Runs {@link TraceEnvelope#penRasterize} with explicit per-column x-extents and NO capsule
     *  geometry (NaN entry/exit -> the classic four-subsample stadium path). */
    private int[][] pen(float[] bandTop, float[] bandBot, float[] bandXLo, float[] bandXHi,
                        int width, int height, float lineWidth, int alpha255) {
        return pen(bandTop, bandBot, bandXLo, bandXHi, nanRow(width), nanRow(width),
                nanRow(width), nanRow(width), width, height, lineWidth, alpha255);
    }

    /** Runs {@link TraceEnvelope#penRasterize} with explicit per-column x-extents AND capsule
     *  entry/exit points (the steep-column branch). */
    private int[][] pen(float[] bandTop, float[] bandBot, float[] bandXLo, float[] bandXHi,
                        float[] bandEntryX, float[] bandEntryY, float[] bandExitX, float[] bandExitY,
                        int width, int height, float lineWidth, int alpha255) {
        byte[]  out  = new byte[width * height];
        TraceEnvelope.penRasterize(bandTop, bandBot, bandXLo, bandXHi, bandEntryX, bandEntryY,
                bandExitX, bandExitY, width, height, lineWidth, alpha255, 0, width - 1, out);
        int[][] grid = new int[height][width];
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) grid[y][x] = out[y * width + x] & 0xFF;
        return grid;
    }

    /** A blank ({@link Float#NaN}) band row of the given width - the pen's no-op column marker. */
    private float[] nanRow(int width) {
        float[] a = new float[width];
        Arrays.fill(a, Float.NaN);
        return a;
    }

    @Test
    void columnCrossings_monotoneRampPairIncrementsExactlyItsSpanRows() {
        // One pair (values 0 -> 4) in one column spans pixel rows 0..4 (5 rows) -> each +1;
        // the rows above the span stay 0.  "A ramp pair spanning k pixels lights exactly
        // those k rows once."
        float[] data = {0f, 4f};
        int[][] g = crossings(data, 0, 2, 1, 8, 0.0);
        int[] col = new int[8];
        for (int y = 0; y < 8; y++) col[y] = g[y][0];
        assertArrayEquals(new int[] {1, 1, 1, 1, 1, 0, 0, 0}, col);
    }

    @Test
    void columnCrossings_flatPairDwellsOnItsSingleRow() {
        // A pair within one pixel row (values 3 -> 3) registers that single row once - the
        // dwell case (diff[3]++, diff[4]--).
        float[] data = {3f, 3f};
        int[][] g = crossings(data, 0, 2, 1, 8, 0.0);
        int[] col = new int[8];
        for (int y = 0; y < 8; y++) col[y] = g[y][0];
        assertArrayEquals(new int[] {0, 0, 0, 1, 0, 0, 0, 0}, col);
    }

    @Test
    void columnCrossings_trianglePeakAccumulatesInteriorTwiceRailsOnce() {
        // A triangle's two segments landing in ONE column (values 0 -> 2 -> 4 = the up- and
        // down-stroke around the apex): spans [0,2] and [2,4] overlap only at the apex row 2,
        // which both cross (count 2); each far rail row is crossed by a single span (count 1).
        // This is the diff-array overlap accumulation - interior 2, rails 1.
        float[] data = {0f, 2f, 4f};
        int[][] g = crossings(data, 0, 3, 1, 6, 0.0);
        int[] col = new int[6];
        for (int y = 0; y < 6; y++) col[y] = g[y][0];
        assertArrayEquals(new int[] {1, 1, 2, 1, 1, 0}, col);
    }

    @Test
    void columnCrossings_outOfDataSampleBreaksThePairChain() {
        // dispStart = −1 puts the first window sample OUT of data (index −1), so the phantom
        // pair (−1, 0) - which would land in column 0 - must NOT register; only the valid pair
        // (0, 1), landing in column 1 (values 4 -> 0 => span rows 0..4), fires.  Column 0 staying
        // all-zero proves the gap broke the chain.
        float[] data = {4f, 0f};
        int[][] g = crossings(data, -1, 3, 3, 6, 0.0);
        int[] col0 = new int[6];
        int[] col1 = new int[6];
        for (int y = 0; y < 6; y++) { col0[y] = g[y][0]; col1[y] = g[y][1]; }
        assertArrayEquals(new int[] {0, 0, 0, 0, 0, 0}, col0);   // no span across the gap
        assertArrayEquals(new int[] {1, 1, 1, 1, 1, 0}, col1);   // the valid pair's span
    }

    @Test
    void columnCrossings_sincExportsRefinedCrestToBand() {
        // A smooth raised-cosine arch whose true crest sits at index 20.5 - BETWEEN samples 20
        // and 21, which flank it symmetrically at value 27.07.  The whole arch lands in column 0.
        // With the identity transform bandTop is the value->row of the REFINED max: sin x/x
        // reconstruction lifts it PAST the sampled crest (row 27) toward the true peak (value 30),
        // exported UNROUNDED - the float the coverage pen strokes.  bandBot carries the refined
        // trough (arch floor, value 10).  This keeps flushRails' refined-value computation covered.
        int n = 41;
        float[] data = new float[n];
        for (int i = 0; i < n; i++) {
            double h = 0.5 * (1.0 + Math.cos(2 * Math.PI * (i - 20.5) / 4.0));  // arch, peak at 20.5
            if (Math.abs(i - 20.5) > 2.0) h = 0.0;                             // single clean hump
            data[i] = (float) (10.0 + 20.0 * h);
        }
        int[]   diff    = new int[42];
        float[] bandTop = new float[1];
        float[] bandBot = new float[1];
        float[] bandXLo = new float[1];
        float[] bandXHi = new float[1];
        float[] entryX  = new float[1];
        float[] entryY  = new float[1];
        float[] exitX   = new float[1];
        float[] exitY   = new float[1];
        TraceEnvelope.columnCrossings(data, n, 0, n - 1, 1, 40, 0.0, CENTER_Y, V_SCALE, DC,
                true, true, diff, bandTop, bandBot, bandXLo, bandXHi, entryX, entryY, exitX, exitY,
                (x, y, count) -> { });
        assertTrue(bandTop[0] > 27.5f);   // refined crest lifted past the sampled rail (row 27)
        assertTrue(bandBot[0] < 11f);     // refined trough at the arch floor (value 10)
    }

    /** Runs the accumulation with the sin&nbsp;x/x rails ON, filling the caller's {@code bandTop}/
     *  {@code bandBot} and returning the per-pixel crossing-count grid - for the split-at-boundary
     *  fixtures that assert both the dwell split and the connected band. */
    private int[][] crossingsSinc(float[] data, int dispStart, int dispCount, int width, int height,
                                  double subSampleOffset, float[] bandTop, float[] bandBot) {
        int[]   diff    = new int[height + 2];
        float[] bandXLo = new float[width];
        float[] bandXHi = new float[width];
        float[] entryX  = new float[width];
        float[] entryY  = new float[width];
        float[] exitX   = new float[width];
        float[] exitY   = new float[width];
        int[][] grid    = new int[height][width];
        TraceEnvelope.columnCrossings(data, data.length, dispStart, dispCount, width, height,
                subSampleOffset, CENTER_Y, V_SCALE, DC, true, true, diff, bandTop, bandBot,
                bandXLo, bandXHi, entryX, entryY, exitX, exitY, (x, y, count) -> grid[y][x] = count);
        return grid;
    }

    @Test
    void columnCrossings_rampCrossingBoundaryMidPairSplitsBothColumnsAndBandsConnect() {
        // (1) A single falling ramp pair (30 -> 20) at spp = 1.25 (pxPerSample = 0.8).  With
        // dispStart = −1 the phantom pair (−1,0) is broken, leaving only pair (0,1): xf0 = 0.8,
        // xf1 = 1.6, so it straddles the column-0/column-1 boundary at b = 1.  The linear crossing
        // value is 30 + (20−30)·(1−0.8)/(1.6−0.8) = 27.5 (row 28).  The OLD whole-span-to-col0 rule
        // put the entire [row 20, row 30] block in column 0 (a staircase); the split gives column 0
        // ONLY its sub-span [row 28, row 30] and column 1 the rest [row 20, row 28].
        float[] bandTop = new float[4];
        float[] bandBot = new float[4];
        int[][] g = crossingsSinc(new float[] {30f, 20f}, -1, 5, 4, 36, 0.0, bandTop, bandBot);
        // Column 0 receives its sub-span (rows 28..30) and NOTHING below the crossing (no staircase).
        assertEquals(1, g[30][0]);
        assertEquals(1, g[29][0]);
        assertEquals(1, g[28][0]);
        assertEquals(0, g[27][0]);            // above the crossing row - not column 0's sub-span
        assertEquals(0, g[24][0]);            // OLD full-block would have lit this; the split does not
        assertEquals(0, g[20][0]);
        // Column 1 receives the rest (rows 20..28) and nothing above the crossing.
        assertEquals(1, g[28][1]);
        assertEquals(1, g[24][1]);
        assertEquals(1, g[20][1]);
        assertEquals(0, g[29][1]);
        assertEquals(0, g[30][1]);
        // The bands connect: column 0's bottom == column 1's top == the interpolated crossing row.
        assertEquals(27.5f, bandBot[0], 1e-4f);
        assertEquals(27.5f, bandTop[1], 1e-4f);
        assertEquals(bandBot[0], bandTop[1], 1e-4f);
        // Outer ends reach AT LEAST the real samples; a sample-defined extent may be
        // WIDENED by the sinc rail (refineExtreme) - on this 2-sample fixture the
        // reconstruction overshoots the step, which is the rail doing its job.
        assertTrue(bandTop[0] >= 30f - 1e-4f);
        assertTrue(bandBot[1] <= 20f + 1e-4f);
    }

    @Test
    void columnCrossings_pairFullyInsideOneColumnUnchangedBySincToggle() {
        // (2) A pair that stays inside one column (values 0 -> 4 at pxPerSample = 0.5, col0 == col1)
        // must be byte-identical whether the rails are on or off - the split loop degenerates to the
        // single-column path.  Same span the existing sinc-off fixture asserts.
        float[] data = {0f, 4f};
        int[][] off = crossings(data, 0, 2, 1, 8, 0.0);
        float[] bandTop = new float[1];
        float[] bandBot = new float[1];
        int[][] on = crossingsSinc(data, 0, 2, 1, 8, 0.0, bandTop, bandBot);
        int[] colOff = new int[8];
        int[] colOn  = new int[8];
        for (int y = 0; y < 8; y++) { colOff[y] = off[y][0]; colOn[y] = on[y][0]; }
        assertArrayEquals(new int[] {1, 1, 1, 1, 1, 0, 0, 0}, colOff);
        assertArrayEquals(colOff, colOn);     // rails toggle never touches the dwell counts
    }

    @Test
    void columnCrossings_steepPulseEdgeSplitsRiseAcrossTheBoundaryNoFullBlock() {
        // (3) A steep rising edge (0 -> 12) crossing the boundary mid-pair (same 0.8 px/sample, b = 1,
        // crossing = 0 + 12·0.25 = 3.0 -> row 3).  The left column takes the partial rise to the
        // crossing (rows 0..3), the right column the rest (rows 3..12) - no full-height 0..12 block
        // in EITHER column, and the crossing row is shared by both.
        float[] bandTop = new float[4];
        float[] bandBot = new float[4];
        int[][] g = crossingsSinc(new float[] {0f, 12f}, -1, 5, 4, 20, 0.0, bandTop, bandBot);
        assertEquals(1, g[0][0]);             // left column: partial rise 0..3
        assertEquals(1, g[3][0]);
        assertEquals(0, g[4][0]);             // stops at the crossing - no full-height block
        assertEquals(0, g[12][0]);
        assertEquals(1, g[12][1]);            // right column: the rest 3..12
        assertEquals(1, g[3][1]);
        assertEquals(0, g[2][1]);             // starts at the crossing - no full-height block
        assertEquals(0, g[0][1]);
        assertEquals(3f, bandTop[0], 1e-4f);  // rising mirror: left top == right bottom == crossing
        assertEquals(3f, bandBot[1], 1e-4f);
    }

    @Test
    void columnCrossings_bandEndFromMidSlopeCrossingIsTheLinearValueUnrefined() {
        // (4) In the falling-ramp split of (1), column 0's only extent below its sample is the
        // mid-slope crossing (no local sample trough sits inside column 0 - the neighbour trough 20
        // lives in column 1).  Its band bottom must be the LINEAR crossing value 27.5 EXACTLY,
        // NOT the refineExtreme over-reach toward the neighbour sample (20) that the ±1-sample
        // window would otherwise pull in - the crossing bounds the band, unrefined.
        float[] bandTop = new float[4];
        float[] bandBot = new float[4];
        crossingsSinc(new float[] {30f, 20f}, -1, 5, 4, 36, 0.0, bandTop, bandBot);
        assertEquals(27.5f, bandBot[0], 1e-4f);   // the interpolated crossing, unrefined
        assertTrue(bandBot[0] > 21f);              // decisively NOT pulled to the neighbour trough (20)
    }

    @Test
    void columnCrossings_countsOffMatchesCountsOnForBandXExtentEntryExit() {
        // Count-skip at flat fill.  At the full-brightness floor the renderer runs the
        // accumulation with counts == false - NO diff writes, NO prefix-sum flush, NO sink calls -
        // because the sink was already a no-op there.  Everything the pen consumes (band ends,
        // x-extent, entry/exit geometry) is computed OUTSIDE the count path, so it must be
        // byte-identical to the counts == true pass.  A multi-tone fixture across several columns
        // exercises splitting, rails and the capsule geometry - not a degenerate single column.
        int n = 200;
        float[] data = new float[n];
        for (int i = 0; i < n; i++) {
            data[i] = (float) (12.0 * Math.sin(i * 0.37) + 3.0 * Math.sin(i * 0.09));
        }
        int width = 12, height = 48, dispStart = 5, dispCount = 180;
        double sso = 0.3;

        float[] onTop = new float[width], onBot = new float[width];
        float[] onXLo = new float[width], onXHi = new float[width];
        float[] onEX = new float[width], onEY = new float[width], onXX = new float[width], onXY = new float[width];
        int[] diff = new int[height + 2];
        TraceEnvelope.columnCrossings(data, n, dispStart, dispCount, width, height, sso,
                CENTER_Y, V_SCALE, DC, true, true, diff, onTop, onBot, onXLo, onXHi,
                onEX, onEY, onXX, onXY, (x, y, c) -> { });

        float[] offTop = new float[width], offBot = new float[width];
        float[] offXLo = new float[width], offXHi = new float[width];
        float[] offEX = new float[width], offEY = new float[width], offXX = new float[width], offXY = new float[width];
        // counts == false -> diff is never touched (pass null to prove it), and the sink must never fire.
        TraceEnvelope.columnCrossings(data, n, dispStart, dispCount, width, height, sso,
                CENTER_Y, V_SCALE, DC, true, false, null, offTop, offBot, offXLo, offXHi,
                offEX, offEY, offXX, offXY, (x, y, c) -> fail("sink must not fire when counts == false"));

        assertArrayEquals(onTop, offTop, 0f, "band top");
        assertArrayEquals(onBot, offBot, 0f, "band bottom");
        assertArrayEquals(onXLo, offXLo, 0f, "x-extent lo");
        assertArrayEquals(onXHi, offXHi, 0f, "x-extent hi");
        assertArrayEquals(onEX, offEX, 0f, "entry x");
        assertArrayEquals(onEY, offEY, 0f, "entry y");
        assertArrayEquals(onXX, offXX, 0f, "exit x");
        assertArrayEquals(onXY, offXY, 0f, "exit y");
    }

    @Test
    void penRasterize_hairlineInRowCentreLightsThatRowFull_boundaryStraddleSplitsTwoHalves() {
        // (1) STADIUM model.  A hairline band (top == bot == 2.5) centred in row 2, width 1
        // (h = 0.5): its OWN column's four subsamples all sit inside [c, c+1) so d = 0,
        // vy = √0.25 = 0.5, swept band [2.0, 3.0] -> row 2 covered 1.0 -> 255, rows 1 and 3 -> 0.
        // The NaN neighbour columns now catch the round cap's SIDE FRINGE (the pen sweeps the
        // column's full width): for output column 0, source column 1, the subsamples at 0.625/0.875
        // have d = 0.375/0.125, vy = √(0.25−d²) = 0.3307/0.4841 -> hairline row-2 coverage 2·vy =
        // 0.6614/0.9682, the other two off the sweep -> mean (0+0+0.6614+0.9682)/4 = 0.4074 ->
        // 255·0.4074 = 103.9 -> 104 (symmetric on the right).  See test (3) for the fringe geometry.
        int[][] centred = pen(new float[] {Float.NaN, 2.5f, Float.NaN},
                              new float[] {Float.NaN, 2.5f, Float.NaN}, 3, 6, 1f, 255);
        assertEquals(255, centred[2][1]);
        assertEquals(0, centred[1][1]);
        assertEquals(0, centred[3][1]);
        assertEquals(104, centred[2][0]);   // stadium side fringe into the blank neighbour (was 0)
        assertEquals(104, centred[2][2]);

        // A hairline straddling the row-1/row-2 boundary (own column, d = 0, vy = 0.5, band
        // [1.5, 2.5]) -> two half-covered rows (0.5 each -> 127.5 -> 128).
        int[][] straddle = pen(new float[] {2.0f}, new float[] {2.0f}, 1, 6, 1f, 255);
        assertEquals(128, straddle[1][0]);
        assertEquals(128, straddle[2][0]);
        assertEquals(0, straddle[0][0]);
        assertEquals(0, straddle[3][0]);
    }

    @Test
    void penRasterize_width2HairlineSpansExactlyTwoPixelsWithAaBoundaryRows() {
        // (2) STADIUM model.  A width-2 hairline (h = 1.0) centred in row 2: its OWN column's four
        // subsamples all have d = 0, vy = √1 = 1.0, swept band [1.5, 3.5] -> rows 1 and 3 half-covered
        // (0.5 -> 128), row 2 full (1.0 -> 255).  Total vertical alpha mass across the own column
        // 0.5 + 1 + 0.5 = 2.0 px exactly (511/255 ≈ 2.004 in 8-bit; tolerance 0.02).
        int[][] g = pen(new float[] {2.5f}, new float[] {2.5f}, 1, 6, 2f, 255);
        assertEquals(128, g[1][0]);
        assertEquals(255, g[2][0]);
        assertEquals(128, g[3][0]);
        assertEquals(0, g[0][0]);
        assertEquals(0, g[4][0]);
        double mass = (g[1][0] + g[2][0] + g[3][0]) / 255.0;
        assertEquals(2.0, mass, 0.02);   // 511/255 ≈ 2.004 - two pixels of coverage
    }

    @Test
    void penRasterize_oneColumnHairlineLightsOwnRowFullAndFirstNeighbourSideFringe() {
        // (3) STADIUM model - the round cap's horizontal AA (the reason for the four subsamples).
        // A one-column hairline (top == bot == 5.5, NaN elsewhere) centred in row 5, width 1
        // (h = 0.5).  The OWN column (5) row 5 is full: d = 0 -> vy = 0.5 -> band [5, 6] -> 255.
        // The FIRST neighbour (column 6) sees source column 5 through subsamples at
        // u = 6.125/6.375/6.625/6.875 -> d = 0.125/0.375/0.625/0.875; only d ≤ h = 0.5 survive, so
        // vy = √(0.25−d²) = 0.4841/0.3307/-/-.  For the hairline the whole [5.5−vy, 5.5+vy] falls in
        // row 5, so the row-5 coverage per subsample is 2·vy = 0.9682/0.6614/0/0 -> mean 1.6297/4 =
        // 0.4074 -> 255·0.4074 = 103.9 -> 104.  The SECOND neighbour (column 7) has every subsample
        // d ≥ 1.125 > h -> 0.
        int width = 10, height = 10;
        float[] top = nanRow(width);
        float[] bot = nanRow(width);
        top[5] = 5.5f; bot[5] = 5.5f;
        int[][] g = pen(top, bot, width, height, 1f, 255);
        assertEquals(255, g[5][5]);   // own column, own row - full
        assertEquals(104, g[5][6]);   // first neighbour (right) - round-cap side fringe
        assertEquals(104, g[5][4]);   // first neighbour (left) - symmetric
        assertEquals(0, g[5][7]);     // second neighbour - beyond the cap
        assertEquals(0, g[5][3]);
    }

    @Test
    void penRasterize_roundCapDecaysIntoBlankNeighboursAndIsZeroBeyondReach() {
        // (4) STADIUM model, width 3 (h = 1.5, reach ⌈1.5⌉+1 = 3).  A single lit hairline column
        // (row 5.5) beside blank (NaN) columns.  The own column's disk (all subsamples d = 0,
        // vy = 1.5) fills its band [4, 7] -> rows 4,5,6 at 255.  The round cap decays outward:
        //  - column 6 (first neighbour): row 5 = 255 (all four subsamples' bands still cover row 5);
        //    row 4 = mean of the four (vy−0.5) overlaps 0.9948/0.9524/0.8636/0.7183 = 0.8823 ->
        //    255·0.8823 = 224.98 -> 225 (< 255);
        //  - column 7 (second neighbour): row 5 - subsamples d = 1.125/1.375 survive (vy =
        //    0.9922/0.5995, both bands cover row 5 fully -> 1.0), d = 1.625/1.875 skip -> mean
        //    (1+1+0+0)/4 = 0.5 -> 255·0.5 = 127.5 -> 128;
        //  - column 8 (third): every subsample d ≥ 2.125 > h -> 0; column 9 is beyond the reach loop.
        int width = 10, height = 10;
        float[] top = nanRow(width);
        float[] bot = nanRow(width);
        top[5] = 5.5f; bot[5] = 5.5f;
        int[][] g = pen(top, bot, width, height, 3f, 255);
        assertEquals(255, g[5][5]);   // source column, centre row
        assertEquals(255, g[4][5]);   // source column, cap fills its band [4,7]
        assertEquals(255, g[5][6]);   // first neighbour, cap centre still full
        assertEquals(225, g[4][6]);   // first neighbour, edge row decayed (< 255)
        assertEquals(128, g[5][7]);   // second neighbour, further decayed
        assertEquals(0, g[5][8]);     // third column: cap decayed to nothing
        for (int y = 0; y < height; y++) assertEquals(0, g[y][9]);   // beyond reach -> untouched
    }

    @Test
    void penRasterize_fractionalBandCoversRowRawRoundingWouldMiss() {
        // (5) STADIUM model - the own column (dx = 0) reduces to a clean vertical AA, since all four
        // subsamples share d = 0 (vy = 0.5) so the mean equals the single-column coverage.  A refined
        // crest carried as an UNROUNDED float (row 2.9), width 1 -> swept band [2.4, 3.4]: an integer
        // rail would round to row 3 and light row 3 only, but the pen covers row 2 as well (0.6
        // overlap -> 153) - the row the raw rounding would have missed, now stroked with coverage.
        int[][] g = pen(new float[] {2.9f}, new float[] {2.9f}, 1, 6, 1f, 255);
        assertEquals(153, g[2][0]);   // covered by the fractional band (0.6 of the row)
        assertEquals(102, g[3][0]);   // 0.4 of row 3
    }

    @Test
    void penRasterize_fractionalXExtentStrokesVerticalAtItsTrueSubColumnPosition() {
        // A vertical band in column 1 whose path x-extent is the single fractional position
        // x = 1.25 (xLo == xHi), width 2 (h = 1).  The pen sweeps a DISK at x = 1.25, not the
        // whole column - so the side fringes are ASYMMETRIC per the true distance, which the
        // old full-column sweep could not produce at any supersampling factor.
        // Interior row 5 (band [2, 8] covers it fully at any surviving subsample, vcov = 1):
        //  - column 1 (own): u = 1.125/1.375/1.625/1.875 -> d = 0.125/0.125/0.375/0.625 ≤ 1,
        //    all four survive -> mean 1.0 -> 255;
        //  - column 0 (left): u = 0.125/0.375/0.625/0.875 -> d = 1.125/0.875/0.625/0.375 ->
        //    three survive (d ≤ 1) -> mean 0.75 -> 191;
        //  - column 2 (right): u = 2.125/2.375/2.625/2.875 -> d = 0.875/1.125/1.375/1.625 ->
        //    ONE survives -> mean 0.25 -> 64.  Closer side brighter - true sub-column position.
        int width = 4, height = 12;
        float[] top = nanRow(width);
        float[] bot = nanRow(width);
        float[] xLo = nanRow(width);
        float[] xHi = nanRow(width);
        top[1] = 2f; bot[1] = 8f;
        xLo[1] = 1.25f; xHi[1] = 1.25f;
        int[][] g = pen(top, bot, xLo, xHi, width, height, 2f, 255);
        assertEquals(255, g[5][1]);   // own column, interior row
        assertEquals(191, g[5][0]);   // left neighbour: 3 of 4 subsamples within pen reach
        assertEquals(64,  g[5][2]);   // right neighbour: 1 of 4 - asymmetry = fractional x works
        assertEquals(0,   g[5][3]);   // beyond the pen
    }

    @Test
    void penRasterize_steepFlankNeighbourFringeRampsWithDriftingX() {
        // (capsule 1) THE DRIFTING RAMP.  A single near-vertical traversal in column 1 whose x
        // drifts exactly one column over 8 rows: entry (1.0, 0) -> exit (2.0, 8), width 2 (h = 1).
        // steep (band 8 rows > x-extent 2−1 = 1) AND singleTraversal (8 − |0−8| = 0 ≤ 2) -> CAPSULE.
        // Per body row j: t = (j+0.5)/8, xAt = 1 + t, hw = 1.  RIGHT neighbour (column 2) horizontal
        // coverage = min(xAt+1, 3) − max(xAt−1, 2) = xAt − 1 = (j+0.5)/8, ramping 0->1 in 1/8 steps;
        // LEFT (column 0) = 1 − max(xAt−1, 0) = 1 − (xAt−1) = 2 − xAt = 1 − (j+0.5)/8, the mirror;
        // own column 1 is fully covered (a radius-1 disk centred in [1,2] always spans it).
        int width = 3, height = 10;
        float[] top = nanRow(width), bot = nanRow(width);
        float[] xLo = nanRow(width), xHi = nanRow(width);
        float[] eX = nanRow(width), eY = nanRow(width), xX = nanRow(width), xY = nanRow(width);
        top[1] = 0f; bot[1] = 8f; xLo[1] = 1.0f; xHi[1] = 2.0f;
        eX[1] = 1.0f; eY[1] = 0f; xX[1] = 2.0f; xY[1] = 8f;
        int[][] g = pen(top, bot, xLo, xHi, eX, eY, xX, xY, width, height, 2f, 255);
        // Right neighbour ramps UP 0->1: round(255·(j+0.5)/8) = 16, 80, 143, 207, 239 at rows 0,2,4,6,7.
        assertEquals(16,  g[0][2], 1);
        assertEquals(80,  g[2][2], 1);
        assertEquals(143, g[4][2], 1);
        assertEquals(207, g[6][2], 1);
        assertEquals(239, g[7][2], 1);
        // Left neighbour ramps DOWN 1->0 (the mirror): round(255·(1−(j+0.5)/8)) = 239,175,112,48,16.
        assertEquals(239, g[0][0], 1);
        assertEquals(175, g[2][0], 1);
        assertEquals(112, g[4][0], 1);
        assertEquals(48,  g[6][0], 1);
        assertEquals(16,  g[7][0], 1);
        // Own column fully covered along the flank body.
        assertEquals(255, g[3][1]);
        assertEquals(255, g[5][1]);
    }

    @Test
    void penRasterize_verticalFlankKeepsConstantFringeCapsDecayAtEnds() {
        // (capsule 2) A perfectly VERTICAL traversal: entryX == exitX == 1.5 in column 1,
        // entry (1.5, 0) -> exit (1.5, 8), width 2 (h = 1).  steep (8 > 0) AND single (8−8 = 0 ≤ 2)
        // -> CAPSULE with xAt = 1.5 CONSTANT.  Left neighbour (column 0) coverage = min(2.5, 1) −
        // max(0.5, 0) = 1 − 0.5 = 0.5 -> 128, the SAME on every flank-body row (rows 2..4) - a
        // vertical stroke keeps its flat fringe, not tapered.  Past the flank the round cap shrinks:
        // row 8 (yc 8.5, dy 0.5, hw √0.75 = 0.866) -> coverage 1 − (1.5−0.866) = 0.366 -> 93 (< 128).
        int width = 3, height = 10;
        float[] top = nanRow(width), bot = nanRow(width);
        float[] xLo = nanRow(width), xHi = nanRow(width);
        float[] eX = nanRow(width), eY = nanRow(width), xX = nanRow(width), xY = nanRow(width);
        top[1] = 0f; bot[1] = 8f; xLo[1] = 1.5f; xHi[1] = 1.5f;
        eX[1] = 1.5f; eY[1] = 0f; xX[1] = 1.5f; xY[1] = 8f;
        int[][] g = pen(top, bot, xLo, xHi, eX, eY, xX, xY, width, height, 2f, 255);
        assertEquals(128, g[2][0]);          // constant side fringe over the flank body ...
        assertEquals(128, g[3][0]);
        assertEquals(128, g[4][0]);          // ... three equal rows - untapered
        assertEquals(255, g[3][1]);          // own column fully covered
        assertEquals(93,  g[8][0]);          // round cap decays past the flank end
        assertTrue(g[8][0] < g[3][0]);       // the cap is dimmer than the body plateau
    }

    @Test
    void penRasterize_multiCrossingColumnFallsBackToStadiumNotCapsule() {
        // (capsule 3) A TALL band (rows 0..20) whose entry/exit sit near the MIDDLE (9 and 11): an
        // up-then-down column, not one traversal.  (20−0) − |9−11| = 18 > 2 -> NOT singleTraversal,
        // so the capsule is rejected and the four-subsample STADIUM fills the whole band.  Row 0 -
        // far above exitY = 11 - is fully covered (255), a value the capsule could never produce
        // (it only paints rows within [min−h, max+h] = [8, 12], leaving row 0 at 0).
        int width = 5, height = 24;
        float[] top = nanRow(width), bot = nanRow(width);
        float[] xLo = nanRow(width), xHi = nanRow(width);
        float[] eX = nanRow(width), eY = nanRow(width), xX = nanRow(width), xY = nanRow(width);
        top[2] = 0f; bot[2] = 20f; xLo[2] = 2f; xHi[2] = 3f;   // full-column x-extent
        eX[2] = 2.5f; eY[2] = 9f; xX[2] = 2.5f; xY[2] = 11f;
        int[][] g = pen(top, bot, xLo, xHi, eX, eY, xX, xY, width, height, 2f, 255);
        assertEquals(255, g[0][2]);    // stadium fills the whole band; the capsule would leave row 0 at 0
        assertEquals(255, g[20][2]);   // and the far bottom rail - again beyond the capsule's reach
    }

    @Test
    void downsampleBox_averagesEachSsBlockIntoOneDecimalPixel() {
        // 4×4 source at ss = 2 -> 2×2 destination.  Each output pixel is the rounded mean of its
        // 2×2 sub-cell block: (255·4)/4 = 255; (255+0+0+0+2)/4 = 64; (128+128+64+64+2)/4 = 96;
        // all-zero block -> 0.
        byte[] src = {
                (byte) 255, (byte) 255,   (byte) 255, 0,
                (byte) 255, (byte) 255,   0,          0,
                (byte) 128, (byte) 128,   0,          0,
                (byte) 64,  (byte) 64,    0,          0,
        };
        byte[] dst = new byte[4];
        TraceEnvelope.downsampleBox(src, 4, 2, dst, 2, 2, 0, 1, 0, 1);
        assertEquals(255, dst[0] & 0xFF);
        assertEquals(64,  dst[1] & 0xFF);
        assertEquals(96,  dst[2] & 0xFF);
        assertEquals(0,   dst[3] & 0xFF);
    }

    @Test
    void fringeDilate_appendsRampOutsideAndKeepsInteriorFullBrightness() {
        // Radius 2 (= 1 px at ss = 2), cone weights (3−|d|)/3: 255 · 2/3 = 170 one cell out,
        // 255 · 1/3 = 85 two cells out.  A saturated 2-cell-wide vertical bar (the 1-px stroke
        // core) keeps its 255 interior (max with itself) and grows symmetric graded fringes -
        // the vector stroke's appended-fringe semantics, NOT an energy-conserving blur (a tent
        // dimmed the core to ~191).  After the box downsample an ALIGNED core
        // reads 128 | 255 | 128 - a fringe on BOTH sides at every grid alignment.
        int w = 8, h = 6;
        byte[] grid = new byte[w * h];
        for (int y = 0; y < h; y++) { grid[y * w + 4] = (byte) 255; grid[y * w + 5] = (byte) 255; }
        byte[] scratch = new byte[w * h];
        TraceEnvelope.fringeDilate(grid, w, h, 2, scratch, 0, w - 1, 0, h - 1);
        assertEquals(255, grid[2 * w + 4] & 0xFF);   // interior untouched
        assertEquals(255, grid[2 * w + 5] & 0xFF);
        assertEquals(170, grid[2 * w + 3] & 0xFF);   // one cell out: 2/3
        assertEquals(85,  grid[2 * w + 2] & 0xFF);   // two cells out: 1/3
        assertEquals(170, grid[2 * w + 6] & 0xFF);   // symmetric right side
        assertEquals(85,  grid[2 * w + 7] & 0xFF);
        byte[] dst = new byte[(w / 2) * (h / 2)];
        TraceEnvelope.downsampleBox(grid, w, 2, dst, w / 2, h / 2, 0, w / 2 - 1, 0, h / 2 - 1);
        assertEquals(128, dst[1 * (w / 2) + 1] & 0xFF);   // (85+170)/2 - left fringe pixel
        assertEquals(255, dst[1 * (w / 2) + 2] & 0xFF);   // aligned core stays full brightness
        assertEquals(128, dst[1 * (w / 2) + 3] & 0xFF);   // right fringe pixel - both sides ramp
    }
}
