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

import org.edgo.audio.measure.common.Lanczos;

import lombok.experimental.UtilityClass;

/**
 * Per-pixel-column min/max envelope decimation for the oscilloscope trace, with
 * the gap-bridging connector geometry.  Pure signal math (no SWT, no view state),
 * unit-testable in isolation.
 *
 * <p>At more than one sample per pixel a single point per column either
 * stair-steps or low-passes narrow pulses and the noise band away.  Instead each
 * column is reduced to a {@code [min, max]} vertical bar; where two adjacent
 * columns' ranges are <em>disjoint</em> a connector bridges them
 * ({@link #connectorAttach}) so the trace stays continuous, while overlapping
 * ranges need none (their bars already meet in Y).  The bridge follows the true
 * signal path — a clean isolated peak (or trough) is traced to its tip rather than
 * clipped, so peak-to-peak is never attenuated.
 *
 * <p>Linear takes the column min/max straight from the raw samples
 * ({@link #rawColumns}).  sin(x)/x ({@link #reconstructedColumns}) takes the same
 * raw min/max, then <em>refines around the extreme samples only</em> — reconstructs
 * the band-limited curve within ±1 sample of the min and max samples — so a narrow
 * pulse / transient whose true crest falls between samples is recovered at full
 * height, at any zoom, without the cost of reconstructing the whole column.
 */
@UtilityClass
public class TraceEnvelope {

    /**
     * Fills {@code outMin[x]}/{@code outMax[x]} with the min/max of the raw
     * samples that pixel column {@code x} covers, for {@code x} in
     * {@code [0, width)}.  A column with no drawable sample is set to
     * {@link Float#NaN} (the caller skips it and any connector to it).  The
     * column-to-sample mapping mirrors the historical per-column-bar renderer
     * exactly: {@code dispStart + (int)(x·samplesPerPx + subSampleOffset)} with
     * {@code (int)} truncation, the half-open {@code [startIdx, endIdx)} bucket,
     * and the {@code blankBeyondData} past-the-end skip.
     */
    public void rawColumns(float[] data, int n, int dispStart, double subSampleOffset,
                           int width, double samplesPerPx, boolean blankBeyondData,
                           int dispLimit, float[] outMin, float[] outMax) {
        for (int x = 0; x < width; x++) {
            int startIdx = dispStart + (int) (x * samplesPerPx + subSampleOffset);
            int endIdx   = dispStart + (int) ((x + 1) * samplesPerPx + subSampleOffset);
            if (endIdx <= startIdx) endIdx = startIdx + 1;
            if (endIdx > dispLimit)   endIdx = dispLimit;
            if (startIdx < dispStart) startIdx = dispStart;
            if (startIdx >= dispLimit) {
                if (blankBeyondData) { outMin[x] = Float.NaN; outMax[x] = Float.NaN; continue; }
                startIdx = dispLimit - 1;
            }
            if (startIdx < 0) { outMin[x] = Float.NaN; outMax[x] = Float.NaN; continue; }
            float min = data[startIdx];
            float max = data[startIdx];
            for (int i = startIdx + 1; i < endIdx; i++) {
                float v = data[i];
                if (v < min) min = v;
                if (v > max) max = v;
            }
            outMin[x] = min;
            outMax[x] = max;
        }
    }

    /**
     * Like {@link #rawColumns} but, for sin(x)/x, RECOVERS the true peak/trough by
     * reconstructing the band-limited curve within ±1 sample of the extreme samples.
     * With the DAC/ADC clocks drifting, the samples crawl around a peak, so the two
     * highest can both fall in a dip and read e.g. 1.87 V where the real crest is
     * 2 V — the reconstructed curve there reads ≈2 V (≈1.98 V).  Only the
     * neighbourhood of the raw min and max is reconstructed (a few evals/column),
     * not the whole column: the curve's extreme sits within a sample of the sampled
     * extreme, and the raw value is the floor (the reconstruction only widens it).
     */
    public void reconstructedColumns(float[] data, int n, int dispStart, double subSampleOffset,
                                     int width, double samplesPerPx, double refineStep,
                                     boolean blankBeyondData, int dispLimit,
                                     float[] outMin, float[] outMax) {
        for (int x = 0; x < width; x++) {
            int startIdx = dispStart + (int) (x * samplesPerPx + subSampleOffset);
            int endIdx   = dispStart + (int) ((x + 1) * samplesPerPx + subSampleOffset);
            if (endIdx <= startIdx) endIdx = startIdx + 1;
            if (endIdx > dispLimit)   endIdx = dispLimit;
            if (startIdx < dispStart) startIdx = dispStart;
            if (startIdx >= dispLimit) {
                if (blankBeyondData) { outMin[x] = Float.NaN; outMax[x] = Float.NaN; continue; }
                startIdx = dispLimit - 1;
            }
            if (startIdx < 0) { outMin[x] = Float.NaN; outMax[x] = Float.NaN; continue; }
            float min = data[startIdx]; int idxMin = startIdx;
            float max = data[startIdx]; int idxMax = startIdx;
            for (int i = startIdx + 1; i < endIdx; i++) {
                float v = data[i];
                if (v < min) { min = v; idxMin = i; }
                if (v > max) { max = v; idxMax = i; }
            }
            outMax[x] = refineExtreme(data, n, idxMax, refineStep, max, true);
            outMin[x] = refineExtreme(data, n, idxMin, refineStep, min, false);
        }
    }

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

    /**
     * Computes, for every column, where the connector to each neighbour attaches
     * to its bar: {@code entryAttach[x]} for the connector arriving from
     * {@code x-1}, {@code exitAttach[x]} for the connector leaving to {@code x+1}
     * (both in data units; {@link Float#NaN} when that side has no connector — a
     * blank/missing neighbour or an overlapping range whose bars already meet).
     * The connector at the boundary {@code (x-1, x)} is then the line
     * {@code (x-1, exitAttach[x-1]) → (x, entryAttach[x])}.
     *
     * <p>Disjoint neighbours are bridged along the true signal path so the trace
     * stays continuous AND its peak-to-peak is never attenuated:
     * <ul>
     *   <li>a <b>clean peak</b> — a column whose whole range sits above BOTH
     *       neighbours — attaches at its {@code max} on both sides, so an isolated
     *       upward spike is traced to its tip instead of clipped to a tick;
     *   <li>a <b>clean trough</b> — whole range below both neighbours — attaches
     *       at its {@code min} (the mirror, downward spikes);
     *   <li>a plain <b>monotonic</b> step attaches exit&nbsp;&rarr;&nbsp;entry:
     *       it leaves the upper bar at its bottom and enters the lower bar at its
     *       top (a rising step is the mirror).
     * </ul>
     */
    public void connectorAttach(float[] colMin, float[] colMax, int width,
                                float[] entryAttach, float[] exitAttach) {
        for (int x = 0; x < width; x++) {
            float cMin = colMin[x];
            if (Float.isNaN(cMin)) { entryAttach[x] = Float.NaN; exitAttach[x] = Float.NaN; continue; }
            float cMax = colMax[x];
            boolean lValid = x > 0 && !Float.isNaN(colMin[x - 1]);
            boolean rValid = x < width - 1 && !Float.isNaN(colMin[x + 1]);
            // Clean peak / trough: this column's whole range is above / below BOTH neighbours.
            boolean peak   = lValid && rValid && colMax[x - 1] < cMin && colMax[x + 1] < cMin;
            boolean trough = lValid && rValid && colMin[x - 1] > cMax && colMin[x + 1] > cMax;

            if (!lValid || !disjoint(colMin, colMax, x - 1, x)) {
                entryAttach[x] = Float.NaN;
            } else if (peak) {
                entryAttach[x] = cMax;
            } else if (trough) {
                entryAttach[x] = cMin;
            } else if (cMin > colMax[x - 1]) {     // this column above the left one (rising in) → enter at bottom
                entryAttach[x] = cMin;
            } else {                                // this column below the left one (falling in) → enter at top
                entryAttach[x] = cMax;
            }

            if (!rValid || !disjoint(colMin, colMax, x, x + 1)) {
                exitAttach[x] = Float.NaN;
            } else if (peak) {
                exitAttach[x] = cMax;
            } else if (trough) {
                exitAttach[x] = cMin;
            } else if (colMin[x + 1] > cMax) {      // right column above (rising out) → leave at top
                exitAttach[x] = cMax;
            } else {                                // right column below (falling out) → leave at bottom
                exitAttach[x] = cMin;
            }
        }
    }

    /** Two columns' ranges share no Y overlap (one bar is entirely above the other). */
    private boolean disjoint(float[] colMin, float[] colMax, int a, int b) {
        return colMax[a] < colMin[b] || colMax[b] < colMin[a];
    }
}
