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

package org.edgo.audio.measure.common;

import lombok.experimental.UtilityClass;

/**
 * Lanczos-windowed sinc reconstruction kernel.  Originally the oscilloscope's
 * waveform renderer; now shared so the frequency-domain views can reconstruct
 * a band-limited trace from their sample arrays too.  Decoupled from any view
 * so the kernel math is unit-testable and the per-scale kernel cache is shared
 * once across every caller.
 *
 * <p>{@link #lanczos(float[], int, double, double)} (and its {@code double[]}
 * overload) reconstructs the trace at any fractional sample-domain position
 * {@code t}, with the kernel widened by {@code scale} so it acts as a low-pass
 * filter at the output rate (kills the energy between the output Nyquist and the
 * input Nyquist that would otherwise fold into beat envelopes).  Phase
 * resolution and downsample limits are tuned for an audio sample rate cap of
 * ~768 kHz drawn on an SWT canvas.
 */
@UtilityClass
public class Lanczos {

    /**
     * Kernel half-width in samples.  Windowed-sinc with this many lobes
     * each side gives a stop-band ≥ 80 dB down — well below visible
     * envelope artifacts on a 14-bit canvas.
     */
    public static final int LANCZOS_A = 16;

    /**
     * Largest downsample factor for which the scaled Lanczos kernel is
     * still cheap enough to evaluate per pixel.  Beyond this the renderer
     * falls back to per-column min/max bars.  Also determines the buffer
     * padding needed for full kernel coverage at the pane edges.
     */
    public static final int MAX_LANCZOS_DOWNSAMPLE = 5;

    /** Buffer padding (each side) so the widest kernel still has real context. */
    public static final int LANCZOS_PADDING = LANCZOS_A * MAX_LANCZOS_DOWNSAMPLE;

    /** Phase resolution for the cached kernel tables (≈ 0.001 sample precision). */
    private static final int LANCZOS_PHASES = 1024;

    /**
     * Two-slot table cache: one slot is permanently kept for {@code scale == 1}
     * (used by trigger refinement and every fast-time/div render), the other
     * holds whatever non-unit scale was last requested.  Two slots avoid
     * thrashing because each frame issues at most those two scales.
     */
    private static volatile double[][] cachedKernelScale1;
    private static volatile double[][] cachedKernelOther;
    private static volatile double     cachedKernelOtherScale = Double.NaN;

    /**
     * Lanczos sinc reconstruction of {@code data} at fractional position
     * {@code t}, using a precomputed phase table for {@code scale} — the
     * inner loop is a plain table lookup with zero {@code Math.sin} calls.
     *
     * @param data    sample buffer
     * @param n       valid length of {@code data}
     * @param t       sample-domain position (may be fractional)
     * @param scale   downsample factor; 1 = classic Whittaker–Shannon, &gt;1
     *                widens the kernel to act as an anti-aliasing low-pass
     */
    public double lanczos(float[] data, int n, double t, double scale) {
        double[][] table = getKernelTable(scale);
        int halfWidth = (int) Math.ceil(LANCZOS_A * scale);
        int center = (int) Math.floor(t);
        double frac = t - center;
        int phase = (int) (frac * LANCZOS_PHASES);
        if (phase < 0) phase = 0;
        if (phase >= LANCZOS_PHASES) phase = LANCZOS_PHASES - 1;
        double[] w = table[phase];
        int iLo = Math.max(0, center - halfWidth + 1);
        int iHi = Math.min(n - 1, center + halfWidth);
        int centerClamped = (center < 0) ? 0 : (center >= n ? n - 1 : center);
        double baseline = data[centerClamped];
        double sumWeights = 0.0;
        double sumDeltas  = 0.0;
        for (int i = iLo; i <= iHi; i++) {
            int j = i - center + halfWidth - 1;
            double wj = w[j];
            sumWeights += wj;
            sumDeltas  += (data[i] - baseline) * wj;
        }
        return baseline * sumWeights + sumDeltas;
    }

    /**
     * {@code double[]} overload of {@link #lanczos(float[], int, double, double)} —
     * used by the frequency-domain views, whose magnitude / phase arrays are
     * {@code double[]}.  Same kernel, but deliberately NOT a twin of the
     * {@code float[]} overload: frequency-domain arrays carry {@code NaN} for
     * invalid points (unswept regions, non-positive magnitudes), so NaN taps are
     * treated as <em>missing</em> and the remaining taps renormalized — a pixel
     * whose kernel merely touches a NaN region reconstructs from its valid
     * neighbours instead of going NaN.  A NaN <em>center</em> sample still
     * returns NaN, so genuine gaps render as gaps, matching the linear
     * per-point feed.  The renormalization also keeps full gain where the
     * kernel truncates at the array ends.  (The {@code float[]} scope overload
     * stays branch-free: scope buffers cannot contain NaN by construction.)
     */
    public double lanczos(double[] data, int n, double t, double scale) {
        double[][] table = getKernelTable(scale);
        int halfWidth = (int) Math.ceil(LANCZOS_A * scale);
        int center = (int) Math.floor(t);
        double frac = t - center;
        int phase = (int) (frac * LANCZOS_PHASES);
        if (phase < 0) phase = 0;
        if (phase >= LANCZOS_PHASES) phase = LANCZOS_PHASES - 1;
        double[] w = table[phase];
        int iLo = Math.max(0, center - halfWidth + 1);
        int iHi = Math.min(n - 1, center + halfWidth);
        int centerClamped = (center < 0) ? 0 : (center >= n ? n - 1 : center);
        double baseline = data[centerClamped];
        if (Double.isNaN(baseline)) return Double.NaN;   // genuine gap stays a gap
        double sumWeights = 0.0;
        double sumDeltas  = 0.0;
        for (int i = iLo; i <= iHi; i++) {
            double di = data[i];
            if (Double.isNaN(di)) continue;              // missing sample — renormalized out
            double wj = w[i - center + halfWidth - 1];
            sumWeights += wj;
            sumDeltas  += (di - baseline) * wj;
        }
        // Σw = 1 for a full clean kernel, so this equals the float[] overload's
        // baseline·Σw + Σdeltas there; with skipped / truncated taps it is the
        // properly renormalized weighted mean instead.
        return (sumWeights > 0.0) ? baseline + sumDeltas / sumWeights : Double.NaN;
    }

    /**
     * Hot-path kernel-table lookup — no monitor enter / exit so HotSpot's
     * C2 happily inlines this whole chain into the {@link #lanczos} call
     * site at the per-pixel render loop.  Falls back to
     * {@link #buildAndCacheKernelTable(double)} only on a cache miss.
     *
     * <p>Why split: C2 refuses to inline any method whose bytecode contains
     * a {@code monitorenter}, even when the synchronised block is on a
     * cold path that never actually executes.  Folding the synchronisation
     * into a separate method keeps {@code lanczos} inlineable and recovers
     * the ~3× speedup the per-pixel inner loop had pre-extraction.
     */
    private double[][] getKernelTable(double scale) {
        if (scale == 1.0) {
            double[][] t = cachedKernelScale1;
            if (t != null) return t;
        } else {
            double[][] t = cachedKernelOther;
            if (t != null && cachedKernelOtherScale == scale) return t;
        }
        return buildAndCacheKernelTable(scale);
    }

    /** Cache-miss path — the only place the class-level monitor is held. */
    private synchronized double[][] buildAndCacheKernelTable(double scale) {
        if (scale == 1.0) {
            if (cachedKernelScale1 == null) cachedKernelScale1 = buildKernelTable(1.0);
            return cachedKernelScale1;
        }
        if (cachedKernelOther == null || cachedKernelOtherScale != scale) {
            cachedKernelOther = buildKernelTable(scale);
            cachedKernelOtherScale = scale;
        }
        return cachedKernelOther;
    }

    /**
     * Pre-bakes the kernel for {@code scale} into a phase table.  Each row is
     * normalised to unit DC gain ({@code Σw = 1}) regardless of the
     * ULP-level drift the analytic form leaves behind — critical at narrow
     * V/div where small gain errors get multiplied by the ~3·10⁵ vScale
     * factor and become visible as a constant amplitude shrink.
     */
    private double[][] buildKernelTable(double scale) {
        int halfWidth = (int) Math.ceil(LANCZOS_A * scale);
        int taps = 2 * halfWidth;
        double invScale = 1.0 / scale;
        double[][] table = new double[LANCZOS_PHASES][taps];
        for (int p = 0; p < LANCZOS_PHASES; p++) {
            double frac = (double) p / LANCZOS_PHASES;
            double[] row = table[p];
            for (int j = 0; j < taps; j++) {
                double x = (frac + (halfWidth - 1) - j) * invScale;
                if (Math.abs(x) >= LANCZOS_A) {
                    row[j] = 0.0;
                } else {
                    row[j] = sinc(x) * sinc(x / LANCZOS_A) * invScale;
                }
            }
            double s = 0.0;
            for (double v : row) s += v;
            if (s != 0.0) {
                double k = 1.0 / s;
                for (int j = 0; j < taps; j++) row[j] *= k;
            }
        }
        return table;
    }

    /**
     * {@code sinc(x) = sin(πx) / (πx)} with full double precision near 0.
     * For {@code |x| < 0.1} an 8th-order Taylor series in {@code u = (πx)²}
     * avoids the cancellation loss that the {@code sin(πx)/(πx)} form
     * suffers near zero.
     */
    public double sinc(double x) {
        if (x == 0.0) return 1.0;
        double pix = Math.PI * x;
        if (Math.abs(x) < 0.1) {
            double u = pix * pix;
            return 1.0 + u * (-1.0 / 6.0
                       + u * ( 1.0 / 120.0
                       + u * (-1.0 / 5040.0
                       + u * ( 1.0 / 362880.0
                       + u * (-1.0 / 39916800.0)))));
        }
        return Math.sin(pix) / pix;
    }
}
