package org.edgo.audio.measure.dsp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link MedianFilter}: spike removal and edge preservation. */
class MedianFilterTest {

    @Test
    void removesLoneSpike() {
        float[] x = new float[64];
        for (int i = 0; i < x.length; i++) x[i] = (float) Math.sin(2 * Math.PI * i / 32.0);
        int idx = 20;
        float orig = x[idx];
        x[idx] = 50.0f;                       // huge lone spike
        new MedianFilter(5).process(x, x.length);
        // The spike is gone — back near the smooth waveform value.
        assertTrue(Math.abs(x[idx] - orig) < 0.2, "spike not removed: " + x[idx]);
    }

    @Test
    void preservesSmoothSignal() {
        float[] x = new float[200];
        for (int i = 0; i < x.length; i++) x[i] = (float) Math.sin(2 * Math.PI * i / 50.0);
        float[] ref = x.clone();
        new MedianFilter(5).process(x, x.length);
        // A clean sine (slowly varying vs the window) is barely changed in
        // the interior; the first/last few samples have a clamped (asymmetric)
        // window, which the scope keeps off-screen behind its lookback.
        double maxDev = 0;
        for (int i = 3; i < x.length - 3; i++) maxDev = Math.max(maxDev, Math.abs(x[i] - ref[i]));
        assertTrue(maxDev < 0.03, "smooth signal distorted by " + maxDev);
    }

    @Test
    void evenWindowForcedOdd() {
        // A width-1 / even request still yields a usable filter (no crash).
        float[] x = { 1, 9, 1, 1, 9, 1 };
        new MedianFilter(2).process(x, x.length);   // forced to 3
        assertEquals(1.0f, x[1], 1e-6, "median-3 should reject the 9 at index 1");
    }
}
