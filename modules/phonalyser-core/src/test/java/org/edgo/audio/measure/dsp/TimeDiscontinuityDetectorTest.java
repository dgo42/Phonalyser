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

    private static final int    N          = 8192;
    private static final double W_20K      = 2 * Math.PI / 19.2;   // 20 kHz @ 384 kHz
    private static final double W_2K       = 2 * Math.PI / 192.0;  // 2 kHz @ 384 kHz
    private static final double W_1K       = 2 * Math.PI / 384.0;  // 1 kHz @ 384 kHz
    private static final double NOISE_RMS  = 1e-4;                 // ~-80 dB of full scale
    private static final int    MERGE      = 384;                  // 1 ms @ 384 kHz
    private static final double NEAR_FS    = 0.999;               // near full-scale tone amplitude
    private static final double SLIP       = 50e-9 * 384_000.0;   // 50 ns capture-timing slip ≈ 0.0192 sample
    private static final int    SLIP_EVERY = 200;                 // one ± slip per this many samples (isolated)
    private static final double EVENT_FRAC = 0.2;                 // maintainer's minimum real-event deviation (×A)

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

    /** Near-FS sine whose sample times carry repeated, isolated ±50 ns sub-sample
     *  slips — the physical spur the amplitude floor must reject.  Each slip leaks
     *  a recurrence residual {@code e ≈ 2·cos ω · A·ω·δt} that grows with tone
     *  frequency; noise-referenced alone it over-fires above a few kHz. */
    private float[] slippedSine(double w) {
        Random rnd = new Random(42);
        float[] d = new float[N];
        for (int i = 0; i < N; i++) {
            double slip = (i % SLIP_EVERY == 0) ? ((i % (2 * SLIP_EVERY) == 0) ? SLIP : -SLIP) : 0.0;
            d[i] = (float) (NEAR_FS * Math.sin(w * (i + slip)) + NOISE_RMS * rnd.nextGaussian());
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

    @Test
    void timingSlips_neverFire_atHighFrequency() {
        // Near-FS 20 kHz tone with repeated 50 ns capture-timing slips.  The slip
        // residual (measured max |e| ≈ 0.012·A) clears the noise-referenced 8×mean
        // (≈2e-3), so a relative-only threshold over-fires — but it stays far below
        // the amplitude floor (0.05·A ≈ 0.05), so the floored detector is silent.
        float[] d = slippedSine(W_20K);
        assertEquals(-1.0, detector.findDiscontinuity(d, 2, N, true, MERGE, W_20K),      1e-12, "known-fundamental");
        assertEquals(-1.0, detector.findDiscontinuity(d, 2, N, true, MERGE, Double.NaN), 1e-12, "self-estimated");
        double[] dd = new double[N];
        for (int i = 0; i < N; i++) dd[i] = d[i];
        assertFalse(detector.detect(dd, N, W_20K), "double overload");
    }

    @Test
    void realEvent_minimumDeviation_fires_overSlips() {
        // The maintainer's smallest real event: a 0.2·A deviation spanning 3 samples,
        // riding on the same slip-laden 20 kHz tone.  Its recurrence error is ≈ 0.2·A
        // (12 dB above the 0.05·A floor), so it fires while the slips do not.
        float[] d = slippedSine(W_20K);
        int event = 4000;
        for (int i = event; i < event + 3; i++) d[i] += (float) (EVENT_FRAC * NEAR_FS);
        assertTrue(detector.findDiscontinuity(d, 2, N, true, MERGE, W_20K)      >= 0, "known-fundamental");
        assertTrue(detector.findDiscontinuity(d, 2, N, true, MERGE, Double.NaN) >= 0, "self-estimated");
    }

    @Test
    void zeroPause_fires_atLowAndHighFrequency() {
        // Zero-pause dropout: 30 samples forced to 0 V mid-tone.  The drop/recovery
        // recurrence error reaches ≈ A (measured ≈ 0.98·A), decades above the floor,
        // at BOTH 1 kHz and 20 kHz — the floor never masks a real dropout.
        for (double w : new double[] { W_1K, W_20K }) {
            float[] d = noisySine(w, 0.0);
            int pause = 2000;
            for (int i = pause; i < pause + 30; i++) d[i] = 0f;
            assertTrue(detector.findDiscontinuity(d, 2, N, true, MERGE, w)          >= 0, "known @ " + w);
            assertTrue(detector.findDiscontinuity(d, 2, N, true, MERGE, Double.NaN) >= 0, "self  @ " + w);
        }
    }

    @Test
    void pureNoise_relativeTermGoverns_floorNegligible() {
        // No tone → amplitude floor collapses to 0.05·√2·σ ≈ 0.07·σ, ~two decades
        // below the relative term 8·mean|e| ≈ 9·σ, so the relative term is the
        // threshold and behaviour matches the pre-floor detector.
        final double sigma = 1e-3;
        Random rnd = new Random(99);
        float[] d = new float[N];
        for (int i = 0; i < N; i++) d[i] = (float) (sigma * rnd.nextGaussian());
        assertEquals(-1.0, detector.findDiscontinuity(d, 2, N, true, MERGE, Double.NaN), 1e-12, "clean noise");
        // A 2σ deviation is ~28× the floor yet ~4× below the relative threshold: it
        // must NOT fire — proving the relative term, not the floor, governs here.
        d[4000] += (float) (2 * sigma);
        assertEquals(-1.0, detector.findDiscontinuity(d, 2, N, true, MERGE, Double.NaN), 1e-12, "sub-relative deviation");
        // A full-scale spike is decades above the relative threshold — still fires.
        d[4000] += 1.0f;
        assertTrue(detector.findDiscontinuity(d, 2, N, true, MERGE, Double.NaN) >= 0, "large spike");
    }
}
