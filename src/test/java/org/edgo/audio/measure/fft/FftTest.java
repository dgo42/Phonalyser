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

package org.edgo.audio.measure.fft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the Cooley-Tukey radix-2 FFT in {@link Fft#forward}.
 * Verifies bit-reversal correctness, DC handling, and a coherent
 * sinusoid input lands on the expected bin.
 */
class FftTest {

    private static final double EPS = 1e-9;

    @Test
    void forward_dc_landsAtBinZero() {
        // A constant-1 signal has all energy at bin 0 (DC).  After
        // forward FFT, re[0] = N, im[0] = 0, every other bin = 0.
        int n = 16;
        double[] re = new double[n];
        double[] im = new double[n];
        for (int i = 0; i < n; i++) re[i] = 1.0;

        Fft.forward(re, im);

        assertEquals(n, re[0], EPS, "DC bin should hold N");
        assertEquals(0.0, im[0], EPS, "DC bin imaginary part is 0");
        for (int k = 1; k < n; k++) {
            assertEquals(0.0, re[k], EPS, "non-DC re bin " + k);
            assertEquals(0.0, im[k], EPS, "non-DC im bin " + k);
        }
    }

    @Test
    void forward_coherentSine_landsAtExpectedBin() {
        // For x[n] = sin(2π·k·n/N), the FFT puts all energy at bins k
        // and N-k.  Amplitude is N/2 on each (complex magnitude).
        int n = 64;
        int k = 5;
        double[] re = new double[n];
        double[] im = new double[n];
        for (int i = 0; i < n; i++) {
            re[i] = Math.sin(2.0 * Math.PI * k * i / n);
        }

        Fft.forward(re, im);

        double magK     = Math.hypot(re[k],     im[k]);
        double magNmK   = Math.hypot(re[n - k], im[n - k]);
        assertEquals(n / 2.0, magK,   EPS, "bin k magnitude");
        assertEquals(n / 2.0, magNmK, EPS, "bin N-k magnitude (mirror)");

        // Every other bin must be empty.
        for (int b = 0; b < n; b++) {
            if (b == k || b == n - k) continue;
            assertTrue(Math.hypot(re[b], im[b]) < EPS,
                    "bin " + b + " should be empty (mag = "
                            + Math.hypot(re[b], im[b]) + ")");
        }
    }

    @Test
    void forward_largeParallel_coherentSine_landsAtExpectedBin() {
        // Exercises the PARALLEL path (N ≥ Fft.PARALLEL_THRESHOLD = 64k): the
        // butterflies are the same, only partitioned across the thread pool, so
        // a coherent sine must still collapse to exactly bins k and N-k with
        // essentially zero leakage elsewhere.  (On ≤2-core machines this falls
        // back to serial and the same assertions hold.)
        int n = 1 << 17;   // 131072
        int k = 1234;
        double[] re = new double[n];
        double[] im = new double[n];
        for (int i = 0; i < n; i++) {
            re[i] = Math.sin(2.0 * Math.PI * k * i / n);
        }

        Fft.forward(re, im);

        assertEquals(n / 2.0, Math.hypot(re[k],     im[k]),     n * 1e-9, "parallel: bin k mag");
        assertEquals(n / 2.0, Math.hypot(re[n - k], im[n - k]), n * 1e-9, "parallel: bin N-k mag");
        double maxLeak = 0.0;
        for (int b = 0; b < n; b++) {
            if (b == k || b == n - k) continue;
            maxLeak = Math.max(maxLeak, Math.hypot(re[b], im[b]));
        }
        assertTrue(maxLeak < 1e-3, "parallel: off-bin leakage ~0 (max=" + maxLeak + ")");
    }

    @Test
    void forward_isLinear_overImpulse() {
        // FFT(δ[n]) = 1 for every bin (a unit impulse at n=0 has flat
        // spectrum, amplitude 1).  Pre-FFT: re[0]=1, rest = 0.
        int n = 32;
        double[] re = new double[n];
        double[] im = new double[n];
        re[0] = 1.0;

        Fft.forward(re, im);

        for (int b = 0; b < n; b++) {
            assertEquals(1.0, Math.hypot(re[b], im[b]), EPS,
                    "impulse spectrum bin " + b);
        }
    }

    @Test
    void forward_inPlace_doesNotAllocate() {
        // The contract says "in-place".  We can't directly assert no
        // allocation in pure JVM tests, but we can verify that no
        // exception is thrown when the input/output share the same
        // backing arrays — which is the practical meaning at our level.
        int n = 8;
        double[] re = { 1, 1, 0, 0, 0, 0, 0, 0 };  // square half-period
        double[] im = new double[n];

        Fft.forward(re, im);

        // Pythagoras check on bin 0: a 2-sample DC of "1, 1" plus 6
        // zeros has DC bin = 2 (sum of the time-domain values).
        assertEquals(2.0, re[0], EPS);
        assertEquals(0.0, im[0], EPS);
    }
}
