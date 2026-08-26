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
 * The analyser's per-frame geometry contract: the full analyze method takes
 * every frame's start position ({@code frameOffsets}), the fixed overlaps
 * derive a uniform array through {@link FftOverlap#hopSamples}, and the
 * random overlap has no derivable spacing at all.
 *
 * <p>Covers: the enum's spacing derivation (values and the random-mode
 * refusal), the geometry validation, bit-identity between the convenience
 * overload and hand-stated uniform offsets, and a variable-spacing analysis
 * whose de-rotation must ride the true frame positions.
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
    void analyze_rejectsBadOffsetGeometry() {
        double[] signal = sine(1_000.0, FFT_SIZE * 2);
        FftAnalyzer analyzer = new FftAnalyzer();

        // First frame must start the buffer.
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(
                signal, SAMPLE_RATE, FFT_SIZE, 8, WindowType.HANN, FftOverlap.PCT_0,
                0.0, 0.0, true, Double.NaN, false, Double.NaN, new FftResult(),
                new int[]{ 100, 4_196 }));
        // Strictly increasing.
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(
                signal, SAMPLE_RATE, FFT_SIZE, 8, WindowType.HANN, FftOverlap.PCT_0,
                0.0, 0.0, true, Double.NaN, false, Double.NaN, new FftResult(),
                new int[]{ 0, 4_096, 4_096 }));
        // Last frame must fit the buffer.
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(
                signal, SAMPLE_RATE, FFT_SIZE, 8, WindowType.HANN, FftOverlap.PCT_0,
                0.0, 0.0, true, Double.NaN, false, Double.NaN, new FftResult(),
                new int[]{ 0, FFT_SIZE + 1 }));
    }

    @Test
    void analyze_conveniencePathRefusesRandomOverlap() {
        double[] signal = sine(1_000.0, FFT_SIZE * 2);
        // No fixed spacing to derive - the caller must state the geometry.
        assertThrows(IllegalStateException.class, () -> new FftAnalyzer().analyze(
                signal, SAMPLE_RATE, FFT_SIZE, 8, WindowType.HANN, FftOverlap.RAND,
                0.0, 0.0, true, Double.NaN, false));
    }

    // ── Uniform offsets == the convenience derivation, to the bit ────────────

    @Test
    void statedUniformOffsets_matchDerivedGeometryExactly() {
        double binWidth = (double) SAMPLE_RATE / FFT_SIZE;
        double freqHz   = 1_000.0 + 0.3 * binWidth;
        double[] signal = sine(freqHz, FFT_SIZE * 3);

        FftResult derived = new FftAnalyzer().analyze(
                signal, SAMPLE_RATE, FFT_SIZE, 8, WindowType.HANN, FftOverlap.PCT_75,
                0.0, 0.0, true, Double.NaN, false);

        int step = FftOverlap.PCT_75.hopSamples(FFT_SIZE);
        int frameCount = (signal.length - FFT_SIZE) / step + 1;
        int[] offsets = new int[frameCount];
        for (int f = 0; f < frameCount; f++) offsets[f] = f * step;
        FftResult stated = new FftAnalyzer().analyze(
                signal, SAMPLE_RATE, FFT_SIZE, 8, WindowType.HANN, FftOverlap.PCT_75,
                0.0, 0.0, true, Double.NaN, false, Double.NaN, new FftResult(), offsets);

        assertEquals(derived.frameCount, stated.frameCount);
        assertEquals(derived.stepSamples, stated.stepSamples);
        assertEquals(step, stated.stepSamples);
        assertEquals(derived.fundamentalHzRefined, stated.fundamentalHzRefined, 0.0);
        assertEquals(derived.thdPct, stated.thdPct, 0.0);
        assertArrayEquals(derived.amplitudeDbFs, stated.amplitudeDbFs, 0.0);
    }

    // ── Variable spacing: the de-rotation rides the true positions ───────────

    @Test
    void variableOffsets_coherentAverageStaysOnTone() {
        double binWidth = (double) SAMPLE_RATE / FFT_SIZE;
        double freqHz   = 1_000.0 + 0.3 * binWidth;
        double[] signal = sine(freqHz, FFT_SIZE * 3);

        // This is an EQUALITY probe, not a "wrong spacing reads lower" probe,
        // and that is forced by the API rather than chosen: the offsets array
        // both CUTS each frame and positions it for de-rotation, so every
        // array that survives validation describes its own frames
        // consistently.  On a continuous tone any valid geometry therefore
        // reads the fundamental at full level, and there is no such thing as
        // an offsets array that is wrong about itself.  The discriminating
        // power has to come from choosing the offsets ADVERSARIALLY instead.
        //
        // Both runs cover 6 frames of the same buffer and end at the same last
        // offset, so both carry the same mean step of 1000 samples.  The four
        // interior frames of the second run sit 24 samples off that mean grid,
        // and 24 samples is half a cycle of this fundamental:
        // N / (2*k_f) = 8192 / (2 * 170.967) = 23.96.  Arithmetic that fell
        // back on the mean step for the per-frame de-rotation would therefore
        // rotate four of the six frames by ~180 degrees, leaving a coherent
        // sum of |1-1-1-1-1+1| / 6 - roughly 9.5 dB down, far outside the
        // tolerance below.  Riding each frame's true position instead puts the
        // two readings on top of each other.
        int[] uniformGrid = { 0, 1_000, 2_000, 3_000, 4_000, 5_000 };
        int[] adversarial = { 0, 1_024, 1_976, 3_024, 3_976, 5_000 };

        FftResult varied = new FftAnalyzer().analyze(
                signal, SAMPLE_RATE, FFT_SIZE, 8, WindowType.HANN, FftOverlap.RAND,
                0.0, 0.0, true, Double.NaN, false, Double.NaN, new FftResult(),
                adversarial);
        FftResult uniform = new FftAnalyzer().analyze(
                signal, SAMPLE_RATE, FFT_SIZE, 8, WindowType.HANN, FftOverlap.RAND,
                0.0, 0.0, true, Double.NaN, false, Double.NaN, new FftResult(),
                uniformGrid);

        assertEquals(freqHz, varied.fundamentalHzRefined, binWidth * 0.1,
                "refined Hz must track the tone across unequal frame spacings");
        assertEquals(uniform.fundamentalDbFs, varied.fundamentalDbFs, 0.05,
                "coherent fundamental level must not depend on the spacing pattern");
        // Pins the construction itself: if the offsets above are ever edited
        // into a different mean step, the probe stops being adversarial and
        // this says so instead of quietly going blind.
        assertEquals(1_000, varied.stepSamples,
                "the adversarial offsets must carry the same mean step as the grid");
    }
}
