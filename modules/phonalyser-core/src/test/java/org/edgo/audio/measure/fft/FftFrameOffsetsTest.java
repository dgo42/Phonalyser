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
 * The analyser's frame-geometry contract: the full analyze method analyses
 * the one frame at the buffer's start and takes the stated frame spacing for
 * its statistics, the fixed overlaps derive that spacing through
 * {@link FftOverlap#hopSamples}, and the random overlap has no derivable
 * spacing at all - its per-cycle drawn hop enters as the stated step.
 *
 * <p>Covers: the enum's spacing derivation (values and the random-mode
 * refusal), the geometry validation, and the frame's independence from the
 * stated spacing.
 */
class FftFrameOffsetsTest {

    private static final int SAMPLE_RATE = 48_000;
    private static final int FFT_SIZE    = 8_192;
    /** The readout settings every analysis here runs with: H2..H9 evaluated
     *  and summed, no distortion band, no manual fundamental. */
    private static final ReadoutSettings SETTINGS = new ReadoutSettings(8, 8, 0.0, 0.0, Double.NaN);

    /** A half-scale sine.
     *
     * @param freqHz  its frequency, Hz
     * @param samples the samples wanted
     * @return the sine at {@link #SAMPLE_RATE} */
    private double[] sine(double freqHz, int samples) {
        double[] signal = new double[samples];
        for (int n = 0; n < samples; n++) {
            signal[n] = 0.5 * Math.sin(2.0 * Math.PI * freqHz * n / SAMPLE_RATE);
        }
        return signal;
    }

    // ── FftOverlap.hopSamples ────────────────────────────────────────────────

    /** Every fixed overlap derives its spacing, rounded, and never zero. */
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

    /** The random overlap has no spacing to derive. */
    @Test
    void hopSamples_randomModeRefuses() {
        assertThrows(IllegalStateException.class, () -> FftOverlap.RAND.hopSamples(FFT_SIZE));
    }

    /** No string names the random overlap. */
    @Test
    void fromString_neverYieldsTheRandomMode() {
        assertThrows(IllegalArgumentException.class, () -> FftOverlap.fromString("Random"));
        assertThrows(IllegalArgumentException.class, () -> FftOverlap.fromString("RAND"));
    }

    // ── Geometry validation ──────────────────────────────────────────────────

    /** A spacing that is not positive and a buffer shorter than the frame
     *  are refused. */
    @Test
    void analyze_rejectsBadGeometry() {
        double[] signal = sine(1_000.0, FFT_SIZE * 2);
        FftAnalyzer analyzer = new FftAnalyzer();

        // The spacing must be positive.
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(
                signal, SAMPLE_RATE, FFT_SIZE, SETTINGS, WindowType.HANN, FftOverlap.PCT_0,
                true, Double.NaN, new FftResult(), 0));
        // The frame must fit the buffer.
        double[] short1 = sine(1_000.0, FFT_SIZE - 1);
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(
                short1, SAMPLE_RATE, FFT_SIZE, SETTINGS, WindowType.HANN, FftOverlap.PCT_0,
                true, Double.NaN, new FftResult(), 4_096));
    }

    // ── Variable spacing: the frame does not depend on it ────────────────────

    /** The frame analysed is the one at the buffer's start whatever spacing
     *  is stated; the spacing is only stamped. */
    @Test
    void statedStep_doesNotMoveTheFrame() {
        double binWidth = (double) SAMPLE_RATE / FFT_SIZE;
        double freqHz   = 1_000.0 + 0.3 * binWidth;
        double[] signal = sine(freqHz, FFT_SIZE * 3);

        // The stated spacing enters the statistics alone - how many frames
        // share a sample.  The frame analysed is the one at the buffer's
        // start whatever the spacing, so its spectrum reads the same.
        FftResult a = new FftAnalyzer().analyze(
                signal, SAMPLE_RATE, FFT_SIZE, SETTINGS, WindowType.HANN, FftOverlap.RAND,
                true, Double.NaN, new FftResult(), 1_000);
        FftResult b = new FftAnalyzer().analyze(
                signal, SAMPLE_RATE, FFT_SIZE, SETTINGS, WindowType.HANN, FftOverlap.RAND,
                true, Double.NaN, new FftResult(), 1_024);

        assertEquals(freqHz, a.fundamentalHzRefined, binWidth * 0.1,
                "refined Hz must track the tone");
        assertArrayEquals(a.amplitudeDbFs, b.amplitudeDbFs, 0.0,
                "the frame's spectrum must not depend on the stated spacing");
        assertEquals(1_000, a.stepSamples, "the stated spacing is stamped");
        assertEquals(1_024, b.stepSamples, "the stated spacing is stamped");
    }
}
