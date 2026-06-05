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

import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.WindowType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end smoke test for {@link FftAnalyzer}: feed a pure sine at a
 * known frequency, verify the analyser locks onto the correct bin and
 * returns a sensible {@link FftResult}.
 *
 * <p>The audit recommended this as the foundational FFT test — it
 * exercises the windowing, FFT, peak detection, fractional-bin
 * refinement and metric computation paths in one shot.  A regression
 * in any of those layers shows up here as a wrong fundamental bin or
 * a wildly off frequency.
 */
class FftAnalyzerSmokeTest {

    @Test
    void pureSine_returnsPeakAtCorrectBin() {
        int    sampleRate = 48_000;
        int    fftSize    = 8_192;
        double freqHz     = 1_000.0;
        // Generate a coherent sine (exact integer-bin frequency) so the
        // analyser's fractional-bin refinement should return ~exactly
        // the integer bin, no leakage spread.
        double binWidth = (double) sampleRate / fftSize;
        int    expectedBin = (int) Math.round(freqHz / binWidth);
        double exactFreq   = expectedBin * binWidth;

        int    samples    = fftSize * 2;
        float[] signal    = new float[samples];
        for (int n = 0; n < samples; n++) {
            signal[n] = (float) (0.5 * Math.sin(2.0 * Math.PI * exactFreq * n / sampleRate));
        }

        FftAnalyzer analyzer = new FftAnalyzer();
        FftResult r = analyzer.analyze(
                signal, sampleRate, fftSize, 8,            // harmonics
                WindowType.HANN, FftOverlap.PCT_0,
                0.0, 0.0, true, Double.NaN, false);        // logSummary=false

        assertEquals(expectedBin, r.fundamentalBin,
                "integer-bin sine should land exactly on its bin");
        assertEquals(exactFreq, r.fundamentalHz, 1e-9,
                "fundamentalHz should match the integer-bin frequency");
        // Refined Hz should agree to sub-Hz precision — this is the
        // payoff of the phase-difference k_f refinement.
        assertEquals(exactFreq, r.fundamentalHzRefined, 0.5,
                "refined Hz within ~½ bin of the integer-bin frequency");
        // Sanity: the result carries sample-rate and FFT size verbatim.
        assertEquals(sampleRate, r.sampleRate);
        assertEquals(fftSize,    r.fftSize);
        assertTrue(r.frameCount >= 1, "at least one frame analysed");
    }

    @Test
    void offBinSine_refinedFrequencyTracks() {
        // Non-integer bin frequency — the integer bin will pick up the
        // closest bin, but the refined Hz should track much more
        // precisely (sub-bin accuracy).
        int    sampleRate = 48_000;
        int    fftSize    = 8_192;
        double binWidth   = (double) sampleRate / fftSize;
        double freqHz     = 1_000.0 + 0.3 * binWidth;   // 30 % into a bin

        int    samples    = fftSize * 3;
        float[] signal    = new float[samples];
        for (int n = 0; n < samples; n++) {
            signal[n] = (float) (0.5 * Math.sin(2.0 * Math.PI * freqHz * n / sampleRate));
        }

        FftAnalyzer analyzer = new FftAnalyzer();
        FftResult r = analyzer.analyze(
                signal, sampleRate, fftSize, 8,
                WindowType.HANN, FftOverlap.PCT_0,
                0.0, 0.0, true, Double.NaN, false);

        // Integer-bin pick can be one bin off due to window leakage; we
        // only care that the REFINED frequency is close to the truth.
        assertEquals(freqHz, r.fundamentalHzRefined, binWidth * 0.1,
                "refined Hz should track non-integer-bin sine to ~0.1 bin");
    }
}
