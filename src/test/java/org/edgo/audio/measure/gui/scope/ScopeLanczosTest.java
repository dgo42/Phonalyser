package org.edgo.audio.measure.gui.scope;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link ScopeLanczos} — the band-limited reconstruction
 * kernel used by every scope trace render path.  A regression here
 * shows up as wrong amplitudes, beat-envelope aliasing, or visible
 * shrinkage at slow time/div.
 */
class ScopeLanczosTest {

    @Test
    void sinc_atZero_returns1() {
        assertEquals(1.0, ScopeLanczos.sinc(0.0), 1e-15);
    }

    @Test
    void sinc_atIntegers_returns0() {
        // sin(πn)/(πn) = 0 for n ∈ ℤ⁺.
        for (int n = 1; n <= 8; n++) {
            assertEquals(0.0, ScopeLanczos.sinc(n), 1e-13,
                    "sinc(" + n + ") should be 0");
            assertEquals(0.0, ScopeLanczos.sinc(-n), 1e-13);
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
            assertEquals(analytic, ScopeLanczos.sinc(x), 1e-14,
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
        double recon = ScopeLanczos.lanczos(data, n, 64, 1.0);
        assertEquals(data[64], recon, 1e-9);

        // Another well-inside index.
        recon = ScopeLanczos.lanczos(data, n, 80, 1.0);
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
            double v = ScopeLanczos.lanczos(data, n, t, 1.0);
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
        double recon   = ScopeLanczos.lanczos(data, n, t, 1.0);
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

        double recon = ScopeLanczos.lanczos(data, n, 128.0, 3.0);
        assertEquals(1.5, recon, 1e-9);
    }
}
