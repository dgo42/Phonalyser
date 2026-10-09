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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.WindowType;
import org.junit.jupiter.api.Test;

/**
 * The capture average against the frame analysis it is built on: the frames
 * of a capture summed to the analysed frame's level, and the fundamental's
 * fractional bin re-pinned from the phase slope over the frames.
 */
class CaptureAverageTest {

    private static final int SAMPLE_RATE = 48_000;
    private static final int FFT_SIZE    = 8_192;

    private double[] sine(double freqHz, int samples) {
        double[] s = new double[samples];
        for (int n = 0; n < samples; n++) {
            s[n] = 0.5 * Math.sin(2.0 * Math.PI * freqHz * n / SAMPLE_RATE);
        }
        return s;
    }

    private CaptureAverage captureAverage() {
        return new CaptureAverage(new FftAnalyzer(), new CoherentAccumulator());
    }

    @Test
    void theFramesOfACaptureAverageToTheAnalysedFramesLevel() {
        double freqHz   = (double) SAMPLE_RATE / FFT_SIZE * 100;    // bin 100
        double[] signal = sine(freqHz, FFT_SIZE * 4);
        FftResult one = new FftAnalyzer().analyze(signal, SAMPLE_RATE, FFT_SIZE, 8,
                WindowType.HANN, FftOverlap.PCT_0, 0.0, 0.0, true, Double.NaN, false, Double.NaN);
        FftResult avg = captureAverage().analyze(signal, SAMPLE_RATE, FFT_SIZE, 8,
                WindowType.HANN, FftOverlap.PCT_0, 0.0, 0.0, true, Double.NaN, false, Double.NaN);
        assertEquals(1, one.frameCount, "the analyser holds one frame");
        assertEquals(4, avg.frameCount, "the average holds the capture's frames");
        assertEquals(one.fundamentalBin, avg.fundamentalBin, "the same fundamental bin");
        assertEquals(one.fundamentalDbFs, avg.fundamentalDbFs, 0.01,
                "a line on a bin sums to its own level");
        assertEquals(one.amplitudeDbFs[100], avg.amplitudeDbFs[100], 0.01, "the fundamental's bin");
        // Identical frames: the window's leakage far from the tone sums to
        // itself as well.
        assertEquals(one.amplitudeDbFs[200], avg.amplitudeDbFs[200], 0.01, "a bin of leakage only");
    }

    @Test
    void theIncoherentAverageHoldsTheLevelToo() {
        double freqHz   = (double) SAMPLE_RATE / FFT_SIZE * 100;
        double[] signal = sine(freqHz, FFT_SIZE * 3);
        FftResult one = new FftAnalyzer().analyze(signal, SAMPLE_RATE, FFT_SIZE, 8,
                WindowType.HANN, FftOverlap.PCT_50, 0.0, 0.0, false, Double.NaN, false, Double.NaN);
        FftResult avg = captureAverage().analyze(signal, SAMPLE_RATE, FFT_SIZE, 8,
                WindowType.HANN, FftOverlap.PCT_50, 0.0, 0.0, false, Double.NaN, false, Double.NaN);
        assertEquals(5, avg.frameCount, "three frames' worth at half overlap");
        assertEquals(one.fundamentalDbFs, avg.fundamentalDbFs, 0.01, "the power mean of one level");
        assertEquals(0.0, avg.im[100], 0.0, "the incoherent convention: magnitude in re, im zero");
    }

    @Test
    void thePhaseSlopeOverTheFramesRePinsTheFundamental() {
        double binWidth = (double) SAMPLE_RATE / FFT_SIZE;
        double freqHz   = 1_000.0 + 0.37 * binWidth;
        int    frames   = CoherentAccumulator.KAPPA_MEAS_FRAMES + 4;
        int    hop      = FftOverlap.PCT_75.hopSamples(FFT_SIZE);
        double[] signal = sine(freqHz, FFT_SIZE + (frames - 1) * hop);
        FftResult one = new FftAnalyzer().analyze(signal, SAMPLE_RATE, FFT_SIZE, 8,
                WindowType.HANN, FftOverlap.PCT_75, 0.0, 0.0, true, Double.NaN, false, Double.NaN);
        FftResult avg = captureAverage().analyze(signal, SAMPLE_RATE, FFT_SIZE, 8,
                WindowType.HANN, FftOverlap.PCT_75, 0.0, 0.0, true, Double.NaN, false, Double.NaN);
        double oneErr = Math.abs(one.fundamentalHzRefined - freqHz) / binWidth;
        double avgErr = Math.abs(avg.fundamentalHzRefined - freqHz) / binWidth;
        assertTrue(avgErr < 1e-3, "re-pinned within a thousandth of a bin, off by " + avgErr);
        assertTrue(avgErr < oneErr, "tighter than the one-frame parabola (" + oneErr + " bin)");
        assertEquals(2 * avg.fundamentalHzRefined, avg.harmonicHz[0], 1e-9,
                "the ladder stands on the re-pinned fundamental");
        assertEquals(frames, avg.frameCount, "every frame held");
    }
}
