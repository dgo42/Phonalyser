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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SineFit}.  Synthesizes a tone with a known amplitude,
 * phase, DC and a small harmonic, then checks that the exact 3-parameter fit
 * recovers the fundamental and that {@code subtractSineInto} removes only the
 * sinusoid — leaving the harmonic and DC intact.
 */
class SineFitTest {

    private static final int    SAMPLE_RATE = 48_000;
    private static final double FREQ_HZ     = 997.0;   // non-integer cycles over the window
    private static final double AMPLITUDE   = 0.5;
    private static final double PHASE_RAD   = 1.1;
    private static final double DC          = 0.02;
    private static final double H3_AMP      = 0.0005;  // 3rd-harmonic contaminant

    /** Ideal fundamental at sample index {@code k} (from window start). */
    private double fundamental(double k) {
        return AMPLITUDE * Math.sin(2.0 * Math.PI * FREQ_HZ * k / SAMPLE_RATE + PHASE_RAD);
    }

    /** 3rd-harmonic contaminant at sample index {@code k}. */
    private double harmonic3(double k) {
        return H3_AMP * Math.sin(2.0 * Math.PI * 3.0 * FREQ_HZ * k / SAMPLE_RATE);
    }

    @Test
    void of_doubleArray_recoversAmplitudePhaseDc_andSuppressesFundamental() {
        int n = 4096;
        double[] sig = new double[n];
        for (int k = 0; k < n; k++) {
            sig[k] = fundamental(k) + DC + harmonic3(k);
        }

        SineFit fit = SineFit.of(sig, SAMPLE_RATE, FREQ_HZ);

        assertEquals(AMPLITUDE, fit.amplitude(), AMPLITUDE * 1e-4,
                "amplitude recovered within 1e-4 relative");
        assertEquals(DC, fit.getC(), 1e-6,
                "DC recovered within 1e-6 absolute");
        assertEquals(PHASE_RAD, fit.phaseRadians(), 1e-4,
                "phase recovered within 1e-4 rad");

        // Subtract the fitted fundamental; the residual should be H3 + DC only.
        // Re-fitting the residual's fundamental must yield a near-zero amplitude
        // (fundamental suppressed by >60 dB relative to 0.5).
        float[] src = new float[n];
        float[] res = new float[n];
        for (int k = 0; k < n; k++) src[k] = (float) sig[k];
        fit.subtractSineInto(src, 0, n, 0.0, res, 0);

        SineFit refit = SineFit.of(res, 0, n, SAMPLE_RATE, FREQ_HZ);
        assertTrue(refit.amplitude() < 5e-6,
                "fundamental in residual suppressed >60 dB, got " + refit.amplitude());
    }

    @Test
    void of_floatOverload_fromOffset_subtractsSineAndPreservesDc() {
        int from = 137;
        int len  = 4096;
        int total = from + len + 137;   // tone written into a larger buffer

        float[] buf = new float[total];
        for (int i = 0; i < total; i++) {
            int k = i - from;   // k=0 at index `from`
            buf[i] = (float) (fundamental(k) + DC + harmonic3(k));
        }

        SineFit fit = SineFit.of(buf, from, len, SAMPLE_RATE, FREQ_HZ);
        assertEquals(AMPLITUDE, fit.amplitude(), AMPLITUDE * 1e-4);
        assertEquals(DC, fit.getC(), 1e-4);

        // subtractSineInto over the same window (kOffset=0 → k aligns with `from`)
        float[] res = new float[total];
        fit.subtractSineInto(buf, from, len, 0.0, res, from);

        // Residual must equal harmonic + DC (fundamental gone, DC preserved).
        for (int i = 0; i < len; i++) {
            double expected = harmonic3(i) + DC;
            assertEquals(expected, res[from + i], 1e-3,
                    "residual = harmonic + DC at window index " + i);
        }
    }

    @Test
    void subtractSineInto_kOffset_alignsWithTone() {
        int from = 137;
        int len  = 4096;
        int total = from + len + 137;

        float[] buf = new float[total];
        for (int i = 0; i < total; i++) {
            int k = i - from;
            buf[i] = (float) (fundamental(k) + DC + harmonic3(k));
        }

        SineFit fit = SineFit.of(buf, from, len, SAMPLE_RATE, FREQ_HZ);

        // Subtract only the second half, seeding the recurrence at kOffset=len/2.
        int half = len / 2;
        float[] res = new float[total];
        fit.subtractSineInto(buf, from + half, len - half, half, res, from + half);

        for (int i = half; i < len; i++) {
            double expected = harmonic3(i) + DC;
            assertEquals(expected, res[from + i], 1e-3,
                    "kOffset-aligned residual = harmonic + DC at window index " + i);
        }
    }
}
