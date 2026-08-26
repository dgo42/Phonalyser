/*
 * Phonalyser - precision audio measurement workbench.
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

import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.WindowType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The analyser's frame-geometry contract: the full analyze method takes the
 * stated frame spacing and count, the fixed overlaps derive both through
 * {@link FftOverlap#hopSamples}, and the random overlap has no derivable
 * spacing at all - its per-tick drawn hop enters as the stated step.
 *
 * <p>Covers: the enum's spacing derivation (values and the random-mode
 * refusal), the geometry validation, bit-identity between the convenience
 * overload and hand-stated geometry, and the coherent level's independence
 * from the stated spacing.
 */
class FftFrameOffsetsTest {

    private static final int SAMPLE_RATE = 48_000;
    private static final int FFT_SIZE    = 8_192;

    private double[] sine(double freqHz, int samples) {
        double[] signal = new double[samples];
        for (int n = 0; n < samples; n++) {
            signal[n] = 0.5 * Math.sin(2.0 * Math.PI * freqHz * n / SAMPLE_RATE);
        }
        return signal;
    }

    // ── FftOverlap.hopSamples ────────────────────────────────────────────────

    @Test
    void hopSamples_fixedModesDeriveTheRoundedSpacing() {
        assertEquals(FFT_SIZE,          FftOverlap.PCT_0.hopSamples(FFT_SIZE));
        assertEquals(FFT_SIZE / 2,      FftOverlap.PCT_50.hopSamples(FFT_SIZE));
        assertEquals(FFT_SIZE / 4,      FftOverlap.PCT_75.hopSamples(FFT_SIZE));
        assertEquals(FFT_SIZE / 8,      FftOverlap.PCT_87_5.hopSamples(FFT_SIZE));
        assertEquals(FFT_SIZE / 16,     FftOverlap.PCT_93_75.hopSamples(FFT_SIZE));
        // Never collapses to zero, however small the frame.
        assertEquals(1, FftOverlap.PCT_93_75.hopSamples(2));
    }

    @Test
    void hopSamples_randomModeRefuses() {
        assertThrows(IllegalStateException.class, () -> FftOverlap.RAND.hopSamples(FFT_SIZE));
    }

    @Test
    void fromString_neverYieldsTheRandomMode() {
        assertThrows(IllegalArgumentException.class, () -> FftOverlap.fromString("Random"));
        assertThrows(IllegalArgumentException.class, () -> FftOverlap.fromString("RAND"));
    }

    // ── Geometry validation ──────────────────────────────────────────────────

    @Test
    void analyze_rejectsBadGeometry() {
        double[] signal = sine(1_000.0, FFT_SIZE * 2);
        FftAnalyzer analyzer = new FftAnalyzer();

        // The spacing must be positive.
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(
                signal, SAMPLE_RATE, FFT_SIZE, 8, WindowType.HANN, FftOverlap.PCT_0,
                0.0, 0.0, true, Double.NaN, false, Double.NaN, new FftResult(),
                0, 2));
        // At least one frame.
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(
                signal, SAMPLE_RATE, FFT_SIZE, 8, WindowType.HANN, FftOverlap.PCT_0,
                0.0, 0.0, true, Double.NaN, false, Double.NaN, new FftResult(),
                4_096, 0));
        // The last frame must fit the buffer.
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(
                signal, SAMPLE_RATE, FFT_SIZE, 8, WindowType.HANN, FftOverlap.PCT_0,
                0.0, 0.0, true, Double.NaN, false, Double.NaN, new FftResult(),
                FFT_SIZE + 1, 2));
    }

    @Test
    void analyze_conveniencePathRefusesRandomOverlap() {
        double[] signal = sine(1_000.0, FFT_SIZE * 2);
        // No fixed spacing to derive - the caller must state the geometry.
        assertThrows(IllegalStateException.class, () -> new FftAnalyzer().analyze(
                signal, SAMPLE_RATE, FFT_SIZE, 8, WindowType.HANN, FftOverlap.RAND,
                0.0, 0.0, true, Double.NaN, false));
    }

    // ── Stated geometry == the convenience derivation, to the bit ────────────

    @Test
    void statedGeometry_matchesDerivedGeometryExactly() {
        double binWidth = (double) SAMPLE_RATE / FFT_SIZE;
        double freqHz   = 1_000.0 + 0.3 * binWidth;
        double[] signal = sine(freqHz, FFT_SIZE * 3);

        FftResult derived = new FftAnalyzer().analyze(
                signal, SAMPLE_RATE, FFT_SIZE, 8, WindowType.HANN, FftOverlap.PCT_75,
                0.0, 0.0, true, Double.NaN, false);

        int step = FftOverlap.PCT_75.hopSamples(FFT_SIZE);
        int frameCount = (signal.length - FFT_SIZE) / step + 1;
        FftResult stated = new FftAnalyzer().analyze(
                signal, SAMPLE_RATE, FFT_SIZE, 8, WindowType.HANN, FftOverlap.PCT_75,
                0.0, 0.0, true, Double.NaN, false, Double.NaN, new FftResult(),
                step, frameCount);

        assertEquals(derived.frameCount, stated.frameCount);
        assertEquals(derived.stepSamples, stated.stepSamples);
        assertEquals(step, stated.stepSamples);
        assertEquals(derived.fundamentalHzRefined, stated.fundamentalHzRefined, 0.0);
        assertEquals(derived.thdPct, stated.thdPct, 0.0);
        assertArrayEquals(derived.amplitudeDbFs, stated.amplitudeDbFs, 0.0);
    }

    // ── Variable spacing: the de-rotation rides the true positions ───────────

    @Test
    void statedStep_doesNotMoveTheCoherentLevel() {
        double binWidth = (double) SAMPLE_RATE / FFT_SIZE;
        double freqHz   = 1_000.0 + 0.3 * binWidth;
        double[] signal = sine(freqHz, FFT_SIZE * 3);

        // Two different spacings in the random-draw regime cut two different
        // frame sets from the same buffer; the per-frame de-rotation rides
        // each stated spacing, so the coherent fundamental must read the same
        // level either way.  Arithmetic that de-rotated at a spacing other
        // than the one the frames were cut at would mis-rotate frames and
        // partially cancel the tone - 1024 vs 1000 samples differs by more
        // than half a cycle of this fundamental per frame (N / (2*k_f) =
        // 8192 / (2 * 170.967) = 23.96 samples), far outside the tolerance.
        FftResult a = new FftAnalyzer().analyze(
                signal, SAMPLE_RATE, FFT_SIZE, 8, WindowType.HANN, FftOverlap.RAND,
                0.0, 0.0, true, Double.NaN, false, Double.NaN, new FftResult(),
                1_000, 6);
        FftResult b = new FftAnalyzer().analyze(
                signal, SAMPLE_RATE, FFT_SIZE, 8, WindowType.HANN, FftOverlap.RAND,
                0.0, 0.0, true, Double.NaN, false, Double.NaN, new FftResult(),
                1_024, 6);

        assertEquals(freqHz, a.fundamentalHzRefined, binWidth * 0.1,
                "refined Hz must track the tone at a stated spacing");
        assertEquals(a.fundamentalDbFs, b.fundamentalDbFs, 0.05,
                "coherent fundamental level must not depend on the stated spacing");
        assertEquals(1_000, a.stepSamples, "the stated spacing is stamped");
        assertEquals(1_024, b.stepSamples, "the stated spacing is stamped");
    }
}
