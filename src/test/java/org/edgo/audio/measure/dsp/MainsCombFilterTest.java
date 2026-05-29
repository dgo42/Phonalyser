package org.edgo.audio.measure.dsp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MainsCombFilter}: 50/60 Hz auto-detection, harmonic
 * rejection depth, and preservation of an off-grid test tone.
 */
class MainsCombFilterTest {

    private static final int FS = 48000;

    /** Goertzel power |X(f)|² over a plain (unwindowed) block — the
     *  measurement yardstick for attenuation checks. */
    private static double power(float[] x, int len, double freqHz) {
        double omega = 2.0 * Math.PI * freqHz / FS;
        double coeff = 2.0 * Math.cos(omega);
        double s1 = 0.0, s2 = 0.0;
        for (int i = 0; i < len; i++) {
            double s = x[i] + coeff * s1 - s2;
            s2 = s1;
            s1 = s;
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2;
    }

    private static double dbDrop(float[] before, float[] after, int off, int len, double f) {
        // Measure on the tail [off, off+len) so the comb's startup
        // transient doesn't pollute the ratio.
        float[] bTail = new float[len];
        float[] aTail = new float[len];
        System.arraycopy(before, off, bTail, 0, len);
        System.arraycopy(after,  off, aTail, 0, len);
        double pb = power(bTail, len, f);
        double pa = power(aTail, len, f);
        return 10.0 * Math.log10((pb + 1e-30) / (pa + 1e-30));
    }

    /** Builds f0 + 2f0 + 3f0 hum plus an off-grid test tone. */
    private static float[] hummySignal(int n, double f0, double testHz) {
        float[] s = new float[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) FS;
            s[i] = (float) (
                    0.10 * Math.sin(2 * Math.PI * f0       * t)
                  + 0.05 * Math.sin(2 * Math.PI * 2 * f0   * t)
                  + 0.03 * Math.sin(2 * Math.PI * 3 * f0   * t)
                  + 0.50 * Math.sin(2 * Math.PI * testHz   * t));
        }
        return s;
    }

    @Test
    void tracksFiftyHz() {
        MainsCombFilter f = new MainsCombFilter(FS, 2.0);
        float[] sig = hummySignal(FS, 50.0, 1037.0);   // 1 s
        double f0 = f.track(sig, sig.length);
        assertEquals(50.0, f0, 0.1);
        assertTrue(f.isTuned());
    }

    @Test
    void autoDetectsSixtyHz() {
        MainsCombFilter f = new MainsCombFilter(FS, 2.0);
        float[] sig = hummySignal(FS, 60.0, 1037.0);
        double f0 = f.track(sig, sig.length);
        assertEquals(60.0, f0, 0.1);
    }

    @Test
    void rejectsHarmonicsKeepsTestTone() {
        MainsCombFilter f = new MainsCombFilter(FS, 2.0);
        int n = 2 * FS;                                // 2 s
        float[] clean = hummySignal(n, 50.0, 1037.0);
        f.track(clean, FS);                            // track on first second
        float[] filtered = clean.clone();
        f.process(filtered, n);

        // Measure on the 2nd second (transient settled).
        int off = FS, len = FS;
        assertTrue(dbDrop(clean, filtered, off, len, 50.0)  > 30.0, "50 Hz not rejected");
        assertTrue(dbDrop(clean, filtered, off, len, 100.0) > 25.0, "100 Hz not rejected");
        assertTrue(dbDrop(clean, filtered, off, len, 150.0) > 20.0, "150 Hz not rejected");
        // Off-grid 1037 Hz tone must survive nearly untouched.
        double testDrop = dbDrop(clean, filtered, off, len, 1037.0);
        assertTrue(Math.abs(testDrop) < 1.0, "1037 Hz altered by " + testDrop + " dB");
    }

    @Test
    void preservesDcWhileRejectingHum() {
        MainsCombFilter f = new MainsCombFilter(FS, 2.0);
        int n = 2 * FS;
        // 50 Hz hum family riding on a +0.30 DC level, plus an off-grid tone.
        float[] clean = new float[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) FS;
            clean[i] = (float) (0.30
                    + 0.10 * Math.sin(2 * Math.PI * 50  * t)
                    + 0.05 * Math.sin(2 * Math.PI * 100 * t)
                    + 0.50 * Math.sin(2 * Math.PI * 1037 * t));
        }
        f.track(clean, FS);
        float[] filtered = clean.clone();
        f.reset();
        f.processPreservingDc(filtered, n);

        int off = FS, len = FS;
        // Hum removed...
        assertTrue(dbDrop(clean, filtered, off, len, 50.0)  > 30.0, "50 Hz not rejected");
        assertTrue(dbDrop(clean, filtered, off, len, 100.0) > 25.0, "100 Hz not rejected");
        // ...DC preserved (mean of the tail ≈ 0.30, not 0)...
        float[] tail = new float[len];
        System.arraycopy(filtered, off, tail, 0, len);
        double mean = 0;
        for (float v : tail) mean += v;
        mean /= len;
        assertEquals(0.30, mean, 0.005, "DC level not preserved");
        // ...and the off-grid tone survives.
        assertTrue(Math.abs(dbDrop(clean, filtered, off, len, 1037.0)) < 1.0, "1037 Hz altered");
    }

    @Test
    void locksOnDominantSecondHarmonic() {
        // Rectifier/diode-bridge hum: 50 Hz fundamental is weak, the 100 Hz
        // 2nd harmonic dominates.  Detection must still lock to f0 = 50 and
        // the comb must strip BOTH lines plus the off-grid tone must survive.
        MainsCombFilter f = new MainsCombFilter(FS, 2.0);
        int n = 2 * FS;
        float[] clean = new float[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) FS;
            clean[i] = (float) (
                    0.01 * Math.sin(2 * Math.PI * 50  * t)   // weak fundamental
                  + 0.15 * Math.sin(2 * Math.PI * 100 * t)   // dominant 2nd harmonic
                  + 0.50 * Math.sin(2 * Math.PI * 1037 * t));
        }
        double f0 = f.track(clean, FS);
        assertEquals(50.0, f0, 0.2);
        float[] filtered = clean.clone();
        f.reset();
        f.process(filtered, n);
        int off = FS, len = FS;
        assertTrue(dbDrop(clean, filtered, off, len, 50.0)  > 20.0, "50 Hz not rejected");
        assertTrue(dbDrop(clean, filtered, off, len, 100.0) > 25.0, "100 Hz not rejected");
        assertTrue(Math.abs(dbDrop(clean, filtered, off, len, 1037.0)) < 1.0, "1037 Hz altered");
    }

    @Test
    void untunedIsPassThrough() {
        MainsCombFilter f = new MainsCombFilter(FS, 2.0);
        assertFalse(f.isTuned());
        float[] sig = hummySignal(FS, 50.0, 1037.0);
        float[] copy = sig.clone();
        f.process(copy, copy.length);                  // no track() first
        for (int i = 0; i < sig.length; i++) {
            assertEquals(sig[i], copy[i], 0.0f);
        }
    }
}
