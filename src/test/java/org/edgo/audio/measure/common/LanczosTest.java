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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link Lanczos} — the band-limited reconstruction kernel shared
 * by the scope trace renderer ({@code float[]} overload) and the
 * frequency-domain views ({@code double[]} overload).  A regression here
 * shows up as wrong amplitudes, beat-envelope aliasing, or visible
 * shrinkage at slow time/div.
 */
class LanczosTest {

    @Test
    void sinc_atZero_returns1() {
        assertEquals(1.0, Lanczos.sinc(0.0), 1e-15);
    }

    @Test
    void sinc_atIntegers_returns0() {
        // sin(πn)/(πn) = 0 for n ∈ ℤ⁺.
        for (int n = 1; n <= 8; n++) {
            assertEquals(0.0, Lanczos.sinc(n), 1e-13,
                    "sinc(" + n + ") should be 0");
            assertEquals(0.0, Lanczos.sinc(-n), 1e-13);
        }
    }

    @Test
    void sinc_smallX_taylorMatchesAnalytic() {
        // For |x| < 0.1 the Taylor branch should match sin(πx)/(πx) to
        // within a few ULP.
        double[] xs = {1e-3, 1e-5, 0.05, 0.099};
        for (double x : xs) {
            double pix = Math.PI * x;
            double analytic = Math.sin(pix) / pix;
            assertEquals(analytic, Lanczos.sinc(x), 1e-14,
                    "Taylor branch mismatch at x=" + x);
        }
    }

    @Test
    void lanczos_atSamplePosition_returnsSampleValue() {
        // At integer t, the kernel weights collapse so the output equals
        // data[t] (modulo edge-clamping effects when t is near the rails).
        int n = 128;
        float[] data = new float[n];
        for (int i = 0; i < n; i++) data[i] = (float) Math.sin(2 * Math.PI * i / 16.0);

        // Centre sample.
        double recon = Lanczos.lanczos(data, n, 64, 1.0);
        assertEquals(data[64], recon, 1e-9);

        // Another well-inside index.
        recon = Lanczos.lanczos(data, n, 80, 1.0);
        assertEquals(data[80], recon, 1e-9);
    }

    @Test
    void lanczos_dcSignal_passesThroughUnchanged() {
        // A constant signal must come out as the same constant at any
        // fractional position — Σw = 1 guarantee.  Tolerance allows
        // for the float→double widening artefact at ~1e-7.
        int n = 128;
        float[] data = new float[n];
        for (int i = 0; i < n; i++) data[i] = 0.42f;
        double expected = (double) 0.42f;   // match the storage precision

        double[] positions = {32.0, 32.25, 32.5, 32.75, 64.0, 64.999};
        for (double t : positions) {
            double v = Lanczos.lanczos(data, n, t, 1.0);
            assertEquals(expected, v, 1e-6, "DC passthrough failed at t=" + t);
        }
    }

    @Test
    void lanczos_recoversSineExactlyAtHalfSamples() {
        // Band-limited sine well below Nyquist: reconstruction at
        // half-sample positions should match the analytic value.  Pick a
        // frequency that's well below Nyquist/2 so the kernel doesn't
        // need to suppress aliasing.
        int n = 256;
        float[] data = new float[n];
        double f = 1.0 / 32.0;   // 1 cycle per 32 samples → Nyquist/16
        for (int i = 0; i < n; i++) {
            data[i] = (float) Math.sin(2 * Math.PI * f * i);
        }
        // Far enough from the rails for the kernel to have full context.
        double t = 100.5;
        double recon   = Lanczos.lanczos(data, n, t, 1.0);
        double analytic = Math.sin(2 * Math.PI * f * t);
        assertEquals(analytic, recon, 1e-3,
                "band-limited sine reconstruction off by " + Math.abs(recon - analytic));
    }

    @Test
    void lanczos_scaledKernel_dcStillPassThrough() {
        // With scale > 1 the kernel widens.  Σw=1 still holds, so a
        // constant signal still reconstructs to the constant.
        int n = 256;
        float[] data = new float[n];
        for (int i = 0; i < n; i++) data[i] = 1.5f;

        double recon = Lanczos.lanczos(data, n, 128.0, 3.0);
        assertEquals(1.5, recon, 1e-9);
    }

    @Test
    void lanczosDouble_cleanData_matchesFloatOverload() {
        // On fully valid data the NaN-aware double[] overload must agree
        // with the float[] one (Σw = 1 makes the renormalization a no-op).
        int n = 256;
        float[]  f = new float[n];
        double[] d = new double[n];
        for (int i = 0; i < n; i++) {
            f[i] = (float) Math.sin(2 * Math.PI * i / 32.0);
            d[i] = f[i];   // same float-precision values in both arrays
        }
        double[] positions = {100.0, 100.25, 100.5, 127.999};
        for (double t : positions) {
            assertEquals(Lanczos.lanczos(f, n, t, 1.0), Lanczos.lanczos(d, n, t, 1.0),
                    1e-12, "overload mismatch at t=" + t);
        }
    }

    @Test
    void lanczosDouble_nanEdges_doNotPoisonValidCore() {
        // Freq-domain compare traces carry NaN outside the swept band.  A DC
        // core flanked by NaN must reconstruct to the constant right up to the
        // first / last valid sample — NaN taps are skipped and renormalized
        // out, not propagated.
        int n = 64;
        double[] data = new double[n];
        for (int i = 0; i < n; i++) {
            data[i] = (i < 10 || i >= 54) ? Double.NaN : 0.42;
        }
        // Valid-core positions whose ±LANCZOS_A kernel reaches into the NaN
        // flanks (10 and 53 are the outermost valid samples).
        double[] positions = {10.0, 12.5, 32.0, 50.5, 53.0};
        for (double t : positions) {
            assertEquals(0.42, Lanczos.lanczos(data, n, t, 1.0), 1e-9,
                    "NaN flank poisoned reconstruction at t=" + t);
        }
    }

    @Test
    void lanczosDouble_nanCenter_returnsNaN() {
        // A genuine gap (NaN center sample) must stay a gap so the painter
        // drops the pixel — same semantics as the linear per-point feed.
        int n = 64;
        double[] data = new double[n];
        for (int i = 0; i < n; i++) data[i] = (i == 32) ? Double.NaN : 1.0;

        assertEquals(Double.NaN, Lanczos.lanczos(data, n, 32.25, 1.0));
    }

    @Test
    void lanczosDouble_arrayEdge_keepsFullGain() {
        // Where the kernel truncates at the array ends the renormalization
        // keeps DC gain at 1 — a flat trace stays flat to the very last
        // sample instead of drooping toward zero.
        int n = 64;
        double[] data = new double[n];
        for (int i = 0; i < n; i++) data[i] = 2.5;

        assertEquals(2.5, Lanczos.lanczos(data, n, 0.0, 1.0), 1e-9);
        assertEquals(2.5, Lanczos.lanczos(data, n, n - 1.001, 1.0), 1e-9);
    }
}
