package org.edgo.audio.measure.dsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Tests for the shared time-domain discontinuity detector — with emphasis on the
 * HIGH-frequency case that motivated the sinusoid-recurrence predictor: at
 * 20 kHz / 384 kHz a plain second-difference threshold rides on the tone's own
 * curvature (~0.5·A) and misses every glitch smaller than a full-peak drop,
 * while the recurrence prediction nulls the tone at any frequency and keeps the
 * threshold on the noise floor.
 */
class TimeDiscontinuityDetectorTest {

    private static final int    N         = 8192;
    private static final double W_20K     = 2 * Math.PI / 19.2;   // 20 kHz @ 384 kHz
    private static final double W_2K      = 2 * Math.PI / 192.0;  // 2 kHz @ 384 kHz
    private static final double NOISE_RMS = 1e-4;                 // ~-80 dB of full scale
    private static final int    MERGE     = 384;                  // 1 ms @ 384 kHz

    private final TimeDiscontinuityDetector detector = new TimeDiscontinuityDetector();

    /** Sine with amplitude 1 + deterministic Gaussian noise (realistic floor). */
    private float[] noisySine(double w, double phase) {
        Random rnd = new Random(42);
        float[] d = new float[N];
        for (int i = 0; i < N; i++) {
            d[i] = (float) (Math.sin(w * i + phase) + NOISE_RMS * rnd.nextGaussian());
        }
        return d;
    }

    @Test
    void cleanTone_neverFires_atLowAndHighFrequency() {
        for (double w : new double[] { W_2K, W_20K }) {
            float[] d = noisySine(w, 0.3);
            assertEquals(-1.0, detector.findDiscontinuity(d, 2, N, true, MERGE, Double.NaN), 1e-12);
            assertEquals(-1.0, detector.findDiscontinuity(d, 2, N, true, MERGE, w), 1e-12);
        }
    }

    @Test
    void highFrequencyGapNearZeroCrossing_detected() {
        // The regression: a 60-sample dropout starting just past a zero crossing
        // of a 20 kHz tone.  Entry step ≈ 0.2·A — far below the old Δ² curvature
        // threshold (~0.54·A at this f/fs), invisible to the scope while the
        // FFT's spectral gates flagged it.  The recurrence predictor must fire.
        float[] d = noisySine(W_20K, 0.0);
        int gapStart = 3841;                     // ≈ 200 full periods + 1 sample past the crossing
        int gapEnd   = gapStart + 60;
        for (int i = gapStart; i < gapEnd; i++) d[i] = 0f;

        double selfEst = detector.findDiscontinuity(d, 2, N, true, MERGE, Double.NaN);
        double exact   = detector.findDiscontinuity(d, 2, N, true, MERGE, W_20K);
        assertEquals(gapStart - 1, selfEst, 1e-9, "self-estimated recurrence");
        assertEquals(gapStart - 1, exact,   1e-9, "known-fundamental recurrence");
    }

    @Test
    void highFrequencyPhaseSplice_detected() {
        // ADC-side cutoff: 30 samples removed mid-stream — the waveform splices
        // to a later phase with a modest level step but a broken recurrence.
        Random rnd = new Random(7);
        float[] d = new float[N];
        int splice = 4000;
        int cut    = 30;
        for (int i = 0; i < N; i++) {
            int k = (i < splice) ? i : i + cut;
            d[i] = (float) (Math.sin(W_20K * k) + NOISE_RMS * rnd.nextGaussian());
        }
        assertTrue(detector.findDiscontinuity(d, 2, N, true, MERGE, W_20K) >= 0);
        assertTrue(detector.findDiscontinuity(d, 2, N, true, MERGE, Double.NaN) >= 0);
    }

    @Test
    void doubleOverload_matchesFloatVerdict() {
        Random rnd = new Random(42);
        double[] d = new double[N];
        for (int i = 0; i < N; i++) {
            d[i] = Math.sin(W_20K * i) + NOISE_RMS * rnd.nextGaussian();
        }
        assertFalse(detector.detect(d, N, W_20K), "clean tone");
        for (int i = 3841; i < 3901; i++) d[i] = 0.0;
        assertTrue(detector.detect(d, N, W_20K), "dropped-sample gap");
    }

    @Test
    void flatSignal_returnsNothing() {
        float[] f = new float[512];
        for (int i = 0; i < f.length; i++) f[i] = 0.25f;
        assertEquals(-1.0, detector.findDiscontinuity(f, 2, f.length, true, MERGE, Double.NaN), 1e-12);
        double[] dd = new double[512];
        for (int i = 0; i < dd.length; i++) dd[i] = 0.25;
        assertFalse(detector.detect(dd, dd.length, Double.NaN));
    }
}
