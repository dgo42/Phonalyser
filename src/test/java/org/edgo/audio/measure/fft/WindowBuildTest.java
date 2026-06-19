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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import org.edgo.audio.measure.enums.WindowType;

/**
 * Sanity-pins {@link FftAnalyzer#buildWindow} for EVERY {@link WindowType}
 * (incl. the HFT / Kaiser-Bessel / deep-Chebyshev additions): correct
 * length, all-finite values, peak ≈ 1, non-trivial coherent gain, and
 * even symmetry — the properties every analysis window must satisfy
 * regardless of its lobe shape.
 */
class WindowBuildTest {

    private static final int N = 4096;

    @Test
    void everyWindow_isFiniteNormalizedAndSymmetric() {
        for (WindowType type : WindowType.values()) {
            checkWindow(type);
        }
    }

    private void checkWindow(WindowType type) {
        double[] w = new FftAnalyzer().buildWindow(N, type);
        assertEquals(N, w.length);

        double max = 0.0;
        double sum = 0.0;
        for (double v : w) {
            assertTrue(Double.isFinite(v), type + ": non-finite sample");
            if (v > max) max = v;
            sum += v;
        }
        // Cosine-sum windows peak within a fraction of a percent of 1;
        // RECT / Chebyshev / HFT / Kaiser are exactly 1 by construction.
        assertTrue(max > 0.99 && max < 1.01, type + ": peak " + max);
        // Coherent gain must be a healthy fraction — a broken build (sign
        // error, wrong normalization) collapses it toward 0.
        double cohGain = sum / N;
        assertTrue(cohGain > 0.01 && cohGain <= 1.0, type + ": cohGain " + cohGain);

        // Even symmetry: symmetric windows mirror about (N−1)/2, periodic
        // ones (FT / HFT) about N/2 — accept either convention.
        double symErr = 0.0, perErr = 0.0;
        for (int n = 1; n < N / 2; n++) {
            symErr = Math.max(symErr, Math.abs(w[n] - w[N - 1 - n]));
            perErr = Math.max(perErr, Math.abs(w[n] - w[N - n]));
        }
        assertTrue(symErr < 1e-9 || perErr < 1e-9,
                type + ": asymmetric (sym " + symErr + ", per " + perErr + ")");
    }

    @Test
    void hft248d_isFlatTop() {
        // The defining flat-top property: a tone BETWEEN bins keeps its
        // amplitude.  Synthesize a sine at bin 100.5, window it, and check
        // the windowed DFT magnitude at the two neighbouring bins against
        // an on-bin tone — the worst-case scalloping must stay within
        // ±0.001 dB (the published flatness is ±0.0001 dB).
        FftAnalyzer analyzer = new FftAnalyzer();
        double[] w = analyzer.buildWindow(N, WindowType.HFT248D);
        double offBin = dftPeakMag(w, 100.5);
        double onBin  = dftPeakMag(w, 100.0);
        double scallopDb = 20.0 * Math.log10(offBin / onBin);
        assertTrue(Math.abs(scallopDb) < 0.001,
                "HFT248D scalloping " + scallopDb + " dB");
    }

    /** Magnitude of the strongest of the two DFT bins flanking a windowed
     *  unit sine at fractional bin {@code f}. */
    private double dftPeakMag(double[] w, double f) {
        int n = w.length;
        double best = 0.0;
        for (int bin = (int) Math.floor(f); bin <= Math.ceil(f); bin++) {
            double re = 0.0, im = 0.0;
            for (int k = 0; k < n; k++) {
                double x = Math.sin(2.0 * Math.PI * f * k / n) * w[k];
                double ph = 2.0 * Math.PI * bin * k / n;
                re += x * Math.cos(ph);
                im -= x * Math.sin(ph);
            }
            best = Math.max(best, Math.hypot(re, im));
        }
        return best;
    }
}
