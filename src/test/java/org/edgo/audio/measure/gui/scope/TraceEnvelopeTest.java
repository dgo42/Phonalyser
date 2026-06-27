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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/** Pure-math tests for the trace min/max envelope decimation + connector geometry. */
class TraceEnvelopeTest {

    // ---- connector attach geometry ----------------------------------------

    @Test
    void connectorAttach_monotonicStepBridgesExitToEntry() {
        // falling step [1.2,1.35] -> [0.7,1.12]: leave prev at its bottom, enter cur at its top
        float[][] a = attach(new float[]{1.2f, 0.7f}, new float[]{1.35f, 1.12f});
        assertEquals(1.2f,  a[EXIT][0],  1e-6f);   // prev min
        assertEquals(1.12f, a[ENTRY][1], 1e-6f);   // cur max
        // rising step [0.7,1.12] -> [1.2,1.35]: leave prev at its top, enter cur at its bottom
        float[][] b = attach(new float[]{0.7f, 1.2f}, new float[]{1.12f, 1.35f});
        assertEquals(1.12f, b[EXIT][0],  1e-6f);   // prev max
        assertEquals(1.2f,  b[ENTRY][1], 1e-6f);   // cur min
    }

    @Test
    void connectorAttach_cleanPeakRoutesThroughMax() {
        // column 1 entirely above both neighbours -> the spike is traced to its tip (max-to-max)
        float[][] a = attach(new float[]{0f, 0.8f, 0f}, new float[]{0.1f, 1.0f, 0.1f});
        assertEquals(0.1f, a[EXIT][0],  1e-6f);   // segment 1 start: x's max
        assertEquals(1.0f, a[ENTRY][1], 1e-6f);   // segment 1 end:   peak max
        assertEquals(1.0f, a[EXIT][1],  1e-6f);   // segment 3 start: peak max
        assertEquals(0.1f, a[ENTRY][2], 1e-6f);   // segment 3 end:   x+2's max
    }

    @Test
    void connectorAttach_cleanTroughRoutesThroughMin() {
        // mirror of the peak: column 1 entirely below both neighbours -> min-to-min
        float[][] a = attach(new float[]{0.9f, 0f, 0.9f}, new float[]{1.0f, 0.2f, 1.0f});
        assertEquals(0.9f, a[EXIT][0],  1e-6f);
        assertEquals(0f,   a[ENTRY][1], 1e-6f);   // trough min
        assertEquals(0f,   a[EXIT][1],  1e-6f);   // trough min
        assertEquals(0.9f, a[ENTRY][2], 1e-6f);
    }

    @Test
    void connectorAttach_overlapOrTouchHasNoConnector() {
        // overlapping ranges -> bars already meet, no bridge
        float[][] a = attach(new float[]{0f, 1f}, new float[]{2f, 3f});
        assertTrue(Float.isNaN(a[EXIT][0]));
        assertTrue(Float.isNaN(a[ENTRY][1]));
        // the user's noisy example [1.15,1.4] & [0.5,1.25] overlap -> no connector
        float[][] b = attach(new float[]{1.15f, 0.5f}, new float[]{1.4f, 1.25f});
        assertTrue(Float.isNaN(b[EXIT][0]));
        // merely touching (prevMin == curMax) -> still no bridge
        float[][] c = attach(new float[]{2f, 1f}, new float[]{3f, 2f});
        assertTrue(Float.isNaN(c[EXIT][0]));
    }

    @Test
    void connectorAttach_blankColumnBreaksTheConnector() {
        // a NaN column has no bars and nothing bridges across it
        float[][] a = attach(new float[]{0f, Float.NaN, 0f}, new float[]{0.1f, Float.NaN, 0.1f});
        assertTrue(Float.isNaN(a[EXIT][0]));    // right neighbour is blank
        assertTrue(Float.isNaN(a[ENTRY][2]));   // left neighbour is blank
    }

    @Test
    void connectorAttach_singleColumnHasNoConnectors() {
        float[][] a = attach(new float[]{0.5f}, new float[]{1.0f});
        assertTrue(Float.isNaN(a[ENTRY][0]));
        assertTrue(Float.isNaN(a[EXIT][0]));
    }

    private static final int ENTRY = 0;
    private static final int EXIT = 1;

    /** Runs connectorAttach and returns {entryAttach, exitAttach}. */
    private float[][] attach(float[] colMin, float[] colMax) {
        int width = colMin.length;
        float[] entry = new float[width];
        float[] exit = new float[width];
        TraceEnvelope.connectorAttach(colMin, colMax, width, entry, exit);
        return new float[][]{entry, exit};
    }

    // ---- raw column decimation --------------------------------------------

    @Test
    void rawColumns_bucketsSamplesPerColumn() {
        float[] data = {0, 1, 2, 3, 4, 5, 6, 7};
        float[] min = new float[4];
        float[] max = new float[4];
        TraceEnvelope.rawColumns(data, 8, 0, 0.0, 4, 2.0, false, 8, min, max);
        assertArrayEquals(new float[]{0, 2, 4, 6}, min, 1e-6f);
        assertArrayEquals(new float[]{1, 3, 5, 7}, max, 1e-6f);
    }

    @Test
    void rawColumns_blanksColumnsBeyondCapturedData() {
        float[] data = {0, 1, 2, 3, 4, 5, 6, 7};
        float[] min = new float[4];
        float[] max = new float[4];
        // dispLimit = 4 -> only columns covering [0,4) are drawable
        TraceEnvelope.rawColumns(data, 8, 0, 0.0, 4, 2.0, true, 4, min, max);
        assertEquals(0f, min[0]);
        assertEquals(2f, min[1]);
        assertTrue(Float.isNaN(min[2]));
        assertTrue(Float.isNaN(min[3]));
    }

    // ---- sin(x)/x: refine around the extreme samples -----------------------

    @Test
    void reconstructedColumns_constantSignalIsFlat() {
        // dispStart clear of both edges (> the 16-sample kernel half-width) so the
        // kernel is never truncated (the renderer guarantees this via LANCZOS_PADDING).
        float[] data = new float[128];
        Arrays.fill(data, 2.0f);
        float[] min = new float[8];
        float[] max = new float[8];
        TraceEnvelope.reconstructedColumns(data, 128, 40, 0.0, 8, 4.0, 0.1, false, 128, min, max);
        for (int x = 0; x < 8; x++) {
            assertEquals(2.0f, min[x], 1e-3f);
            assertEquals(2.0f, max[x], 1e-3f);
        }
    }

    @Test
    void reconstructedColumns_recoversBetweenSamplePeak() {
        // a sine of period 10 whose crest at x=42.5 and trough at x=47.5 fall BETWEEN
        // samples (the nearest samples reach only ±0.951).  Refining the curve ±1
        // sample around the extreme samples must recover the true ±1.0 — the
        // clock-drift "the peak fell in a hole" case.
        float[] data = new float[128];
        for (int i = 0; i < data.length; i++) data[i] = (float) Math.sin(2.0 * Math.PI * i / 10.0);
        float[] min = new float[1];
        float[] max = new float[1];
        TraceEnvelope.reconstructedColumns(data, 128, 40, 0.0, 1, 10.0, 0.1, false, 128, min, max);
        assertTrue(max[0] >  0.99f, "crest recovered, got " + max[0]);   // > the 0.951 sample max
        assertTrue(min[0] < -0.99f, "trough recovered, got " + min[0]);
    }

    @Test
    void reconstructedColumns_neverBelowRawEnvelope() {
        // the refinement only WIDENS the raw min/max (the raw sample is the floor).
        float[] data = new float[128];
        for (int i = 0; i < data.length; i++) data[i] = (float) Math.sin(2.0 * Math.PI * i / 7.0);
        int width = 8;
        float[] rMin = new float[width], rMax = new float[width];
        float[] eMin = new float[width], eMax = new float[width];
        TraceEnvelope.rawColumns(data, 128, 20, 0.0, width, 4.0, false, 128, rMin, rMax);
        TraceEnvelope.reconstructedColumns(data, 128, 20, 0.0, width, 4.0, 0.1, false, 128, eMin, eMax);
        for (int x = 0; x < width; x++) {
            assertTrue(eMax[x] >= rMax[x], "recon max >= raw max at " + x);
            assertTrue(eMin[x] <= rMin[x], "recon min <= raw min at " + x);
        }
    }

}
