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

import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.WindowType;
import org.junit.jupiter.api.Test;

/**
 * Pins the meaning of {@code fundamentalDbFs}: it is referenced to the signal's
 * PEAK amplitude, not its RMS.
 *
 * <p>The two conventions differ by exactly 20·log10(√2) = 3.0103 dB, which is
 * small enough to look like a calibration discrepancy and large enough to make
 * every absolute level assertion wrong.  The analyser's normalisation -
 * 1/Σw with the single-sided doubling, and no 1/√2 anywhere on the path -
 * yields peak amplitude; this test states that as an executable fact rather
 * than as a comment somewhere.
 *
 * <p>It exists because an integration test now asserts an absolute level in
 * dBFS against a value derived on paper.  If this convention ever changes, the
 * failure should surface HERE, in one line of arithmetic with no hardware in
 * sight, instead of as a 3 dB miss in a measurement scenario where it would
 * read like a broken analyzer.
 *
 * <p>No mock, no device, no display: a coherent (integer-bin) tone of known
 * peak amplitude goes in, an exact number comes out.
 */
class FftDbFsConventionTest {

    private static final int SAMPLE_RATE = 48_000;
    private static final int FFT_SIZE    = 8_192;
    private static final int HARMONICS   = 8;
    /** Peak amplitude of the synthetic tone, as a fraction of full scale. */
    private static final double PEAK_FRACTION = 0.5;
    /** Generous next to the exactness claimed: a coherent tone in an exact bin
     *  leaves the window's scalloping and leakage out of it entirely. */
    private static final double TOLERANCE_DB = 0.02;

    @Test
    void fundamentalDbFsIsReferencedToPeakAmplitude() {
        FftResult result = analyzeCoherentTone(PEAK_FRACTION);

        // PEAK convention: 0.5 peak -> 20*log10(0.5) = -6.0206 dBFS.
        // RMS convention would instead give -9.0309 dBFS, and the assertion
        // below is far tighter than the 3.0103 dB between them.
        double expectedPeakDbFs = 20.0 * Math.log10(PEAK_FRACTION);
        assertEquals(expectedPeakDbFs, result.fundamentalDbFs, TOLERANCE_DB,
                "fundamentalDbFs must be peak-referenced; an rms reference would "
                        + "read " + (expectedPeakDbFs - 20.0 * Math.log10(Math.sqrt(2.0)))
                        + " dBFS instead");
    }

    @Test
    void aFullScaleToneReadsZeroDbFs() {
        // The anchor of the whole scale: full-scale peak IS 0 dBFS.  Stated
        // separately because it is the one point where the two conventions are
        // told apart by eye - an rms reference would put full scale at
        // -3.01 dBFS, which no one would call "full scale".
        FftResult result = analyzeCoherentTone(1.0);

        assertEquals(0.0, result.fundamentalDbFs, TOLERANCE_DB,
                "a full-scale peak sine must read 0 dBFS");
    }

    @Test
    void halvingTheAmplitudeCostsSixDecibels() {
        // Independent of any reference: the SCALE must be amplitude-based
        // (20·log10), not power-based (10·log10), or every derived tolerance
        // is out by a factor of two.
        double loud  = analyzeCoherentTone(PEAK_FRACTION).fundamentalDbFs;
        double quiet = analyzeCoherentTone(PEAK_FRACTION / 2.0).fundamentalDbFs;

        assertEquals(20.0 * Math.log10(2.0), loud - quiet, TOLERANCE_DB,
                "halving the amplitude must cost 6.02 dB, not 3.01 dB");
    }

    /** A sine at an exact bin centre, so no window leakage or scalloping
     *  stands between the amplitude that went in and the level that comes
     *  out. */
    private FftResult analyzeCoherentTone(double peakFraction) {
        double binWidth  = (double) SAMPLE_RATE / FFT_SIZE;
        double exactFreq = Math.round(1_000.0 / binWidth) * binWidth;

        double[] signal = new double[FFT_SIZE * 2];
        for (int n = 0; n < signal.length; n++) {
            signal[n] = peakFraction * Math.sin(2.0 * Math.PI * exactFreq * n / SAMPLE_RATE);
        }
        return new FftAnalyzer().analyze(signal, SAMPLE_RATE, FFT_SIZE, HARMONICS,
                WindowType.HANN, FftOverlap.PCT_0, 0.0, 0.0, true, Double.NaN, false);
    }
}
