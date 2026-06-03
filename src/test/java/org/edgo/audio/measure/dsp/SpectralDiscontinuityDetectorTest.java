package org.edgo.audio.measure.dsp;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link SpectralDiscontinuityDetector} with synthetic coherent
 * spectra (a strong line on a chi-square floor) and checks each gate: clean
 * blocks pass, a broadband glitch (floor gate), a near-carrier pedestal lift
 * (pedestal gate) and a power-collapse stall (power gate) are rejected, and
 * the decisions are amplitude-independent.
 */
class SpectralDiscontinuityDetectorTest {

    private static final int    HALF         = 2048;
    private static final double BIN_WIDTH_HZ = 0.1875;   // → skirt ≈ 47 bins, exclusion 6 bins
    private static final int   TONE  = 200;
    private static final int[] PEAKS = { TONE };

    /** Builds one block.  {@code glitch} lifts the whole floor (broadband);
     *  {@code pedestal} lifts only the bins flanking the tone; {@code stall}
     *  collapses everything. */
    private static double[][] block(Random rng, double amp,
                                    boolean glitch, boolean pedestal, boolean stall) {
        double[] re = new double[HALF];
        double[] im = new double[HALF];
        for (int k = 0; k < HALF; k++) {
            if (stall) {
                re[k] = amp * 1e-6 * rng.nextGaussian();        // lines collapsed
            } else {
                re[k] = amp * 1e-4 * rng.nextGaussian();        // floor
                if (glitch) re[k] += amp * 1e-3 * rng.nextGaussian();  // broadband lift (+20 dB)
            }
        }
        if (!stall) {
            re[TONE] = amp * 1e-1;                              // the line
            if (pedestal) {                                    // lift only the near-carrier bins
                for (int k = TONE - 54; k <= TONE - 7; k++) re[k] = amp * 1e-2 * rng.nextGaussian();
                for (int k = TONE + 7; k <= TONE + 54; k++) re[k] = amp * 1e-2 * rng.nextGaussian();
            }
        }
        return new double[][]{ re, im };
    }

    private static SpectralDiscontinuityDetector fresh() {
        SpectralDiscontinuityDetector d =
                new SpectralDiscontinuityDetector(16, 8, 8, 6.0, 6.0, 10.0, 8);
        d.configure(HALF);
        return d;
    }

    private static SpectralDiscontinuityDetector warmed(Random rng, double amp) {
        SpectralDiscontinuityDetector d = fresh();
        for (int i = 0; i < 24; i++) {
            double[][] b = block(rng, amp, false, false, false);
            d.reject(b[0], b[1], HALF, BIN_WIDTH_HZ, PEAKS);
        }
        return d;
    }

    @Test
    void cleanBlocksPassAfterWarmup() {
        Random rng = new Random(1);
        SpectralDiscontinuityDetector d = warmed(rng, 1.0);
        for (int i = 0; i < 40; i++) {
            double[][] b = block(rng, 1.0, false, false, false);
            assertFalse(d.reject(b[0], b[1], HALF, BIN_WIDTH_HZ, PEAKS), "clean block #" + i + " falsely rejected");
        }
    }

    @Test
    void broadbandGlitchRejected() {
        Random rng = new Random(2);
        SpectralDiscontinuityDetector d = warmed(rng, 1.0);
        double[][] g = block(rng, 1.0, true, false, false);
        assertTrue(d.reject(g[0], g[1], HALF, BIN_WIDTH_HZ, PEAKS), "broadband glitch not rejected");
    }

    @Test
    void nearCarrierPedestalRejected() {
        Random rng = new Random(4);
        SpectralDiscontinuityDetector d = warmed(rng, 1.0);
        double[][] p = block(rng, 1.0, false, true, false);
        assertTrue(d.reject(p[0], p[1], HALF, BIN_WIDTH_HZ, PEAKS), "near-carrier pedestal lift not rejected");
    }

    @Test
    void powerCollapseStallRejected() {
        Random rng = new Random(3);
        SpectralDiscontinuityDetector d = warmed(rng, 1.0);
        double[][] s = block(rng, 1.0, false, false, true);
        assertTrue(d.reject(s[0], s[1], HALF, BIN_WIDTH_HZ, PEAKS), "power-collapse stall not rejected");
    }

    @Test
    void decisionsAreAmplitudeIndependent() {
        double ampA = 1.0, ampB = 1000.0;
        Random ra = new Random(7), rb = new Random(7);
        SpectralDiscontinuityDetector da = warmed(ra, ampA);
        SpectralDiscontinuityDetector db = warmed(rb, ampB);
        for (int i = 0; i < 30; i++) {
            boolean glitch = (i == 8), pedestal = (i == 16), stall = (i == 24);
            double[][] a = block(ra, ampA, glitch, pedestal, stall);
            double[][] b = block(rb, ampB, glitch, pedestal, stall);
            boolean ja = da.reject(a[0], a[1], HALF, BIN_WIDTH_HZ, PEAKS);
            boolean jb = db.reject(b[0], b[1], HALF, BIN_WIDTH_HZ, PEAKS);
            assertTrue(ja == jb, "amplitude changed decision at block #" + i);
        }
    }
}
