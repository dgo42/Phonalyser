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

package org.edgo.audio.measure.dsp;

/**
 * Sliding-window median ("de-spike") filter.  For each sample it outputs
 * the median of the {@code window} samples centred on it, which removes
 * impulsive spikes (lone outliers up to {@code window/2} samples wide are
 * simply not the median, so they vanish) while preserving genuine
 * waveform edges — unlike a linear low-pass, it adds no ringing and keeps
 * the underlying shape.  Complements {@link LowPassFilter}: the LPF
 * handles continuous high-frequency content, the median handles
 * impulsive glitches.
 *
 * <p>Memoryless across blocks (each output sample depends only on its
 * neighbourhood in the current block), so — unlike the IIR filters — it
 * needs no per-block reset and produces no edge transient.  Window edges
 * shrink the neighbourhood rather than wrapping.
 */
public final class MedianFilter {

    private final int window;          // odd, ≥ 3
    private float[] in  = new float[0];   // copy of the block's input
    private final float[] sortBuf;        // tiny scratch for one window

    /** @param window median window size; forced odd and clamped to ≥ 3. */
    public MedianFilter(int window) {
        int w = Math.max(3, window);
        if ((w & 1) == 0) w++;          // force odd so the median is well-defined
        this.window  = w;
        this.sortBuf = new float[w];
    }

    /** Replaces each sample of {@code data[0..len)} with the median of the
     *  window centred on it, in place. */
    public void process(float[] data, int len) {
        len = Math.min(len, data.length);
        if (len <= 0) return;
        if (in.length < len) in = new float[len];
        System.arraycopy(data, 0, in, 0, len);
        int half = window / 2;
        for (int i = 0; i < len; i++) {
            int lo = Math.max(0, i - half);
            int hi = Math.min(len - 1, i + half);
            data[i] = median(lo, hi);
        }
    }

    /** Median of {@code in[lo..hi]} via insertion sort into {@link #sortBuf}
     *  (window ≤ a handful of samples, so this is cheap). */
    private float median(int lo, int hi) {
        int n = hi - lo + 1;
        for (int j = 0; j < n; j++) {
            float v = in[lo + j];
            int k = j - 1;
            while (k >= 0 && sortBuf[k] > v) { sortBuf[k + 1] = sortBuf[k]; k--; }
            sortBuf[k + 1] = v;
        }
        return sortBuf[n / 2];
    }
}
