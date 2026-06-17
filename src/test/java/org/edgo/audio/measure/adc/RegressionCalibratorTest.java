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

package org.edgo.audio.measure.adc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link RegressionCalibrator}.  Feeds a clean sine wave at a
 * known amplitude and frequency, then checks the regression's recovered
 * fundamental, amplitude and residual RMS.  A perfect input should
 * round-trip to ~0 residual; any regression in the golden-section
 * frequency refinement or the LS sine fit shows up as a non-zero
 * residual or a wrong amplitude.
 */
class RegressionCalibratorTest {

    @Test
    void calibrate_cleanSine_recoversFrequencyAndAmplitude() {
        int    sampleRate = 48_000;
        double freqHz     = 1_000.0;
        double amplitude  = 0.5;          // normalised peak
        int    samples    = 1 << 14;      // 16 384 samples → ~340 ms

        // Coherent integer-bin sine so the fit has zero windowing
        // error.  Slight phase offset so the regression's golden-section
        // search has something to find (vs sin(0) starting at the origin).
        double[] sig = new double[samples];
        for (int n = 0; n < samples; n++) {
            sig[n] = (double) (amplitude * Math.sin(2.0 * Math.PI * freqHz * n / sampleRate
                                                    + 0.7));
        }

        RegressionCalibrator cal = new RegressionCalibrator();
        RegressionCalibrator.Result r = cal.calibrate(sig, sampleRate, 16);

        assertEquals(freqHz, r.fundamentalHz, 0.05,
                "fundamental frequency recovered within 50 mHz");
        assertEquals(amplitude, r.amplitude, 1e-4,
                "amplitude recovered within 1e-4");
        assertTrue(r.residualRms < 1e-4,
                "perfect input should yield near-zero residual, got " + r.residualRms);
        // Bookkeeping: bitDepth and codeCount agree.
        assertEquals(16,           r.bitDepth);
        assertEquals(1 << 16,      r.codeCount);
    }

    @Test
    void calibrate_dcOffset_isRecovered() {
        // Sine + DC bias.  The fit's `c` term should land on the DC value.
        int    sampleRate = 48_000;
        double freqHz     = 500.0;
        double amplitude  = 0.3;
        double dcOffset   = 0.05;
        int    samples    = 1 << 14;

        double[] sig = new double[samples];
        for (int n = 0; n < samples; n++) {
            sig[n] = (double) (amplitude * Math.sin(2.0 * Math.PI * freqHz * n / sampleRate)
                              + dcOffset);
        }

        RegressionCalibrator.Result r = new RegressionCalibrator().calibrate(sig, sampleRate, 16);
        assertEquals(dcOffset, r.dcOffset, 1e-5,
                "DC offset recovered within 1e-5");
    }

    @Test
    void calibrate_resultArraysMatchCodeCount() {
        // Smoke check on the result-array sizes: 16-bit ADC → 65 536 codes,
        // 8-bit ADC → 256 codes.  All three arrays (avgErrorLsb,
        // sampleCount, interpolated) must match codeCount exactly.
        int    sampleRate = 48_000;
        int    samples    = 4 * sampleRate;  // 4 s → covers many cycles
        double[] sig = new double[samples];
        for (int n = 0; n < samples; n++) {
            sig[n] = (double) (0.4 * Math.sin(2.0 * Math.PI * 1000.0 * n / sampleRate));
        }

        for (int bits : new int[] { 8, 16 }) {
            RegressionCalibrator.Result r = new RegressionCalibrator().calibrate(sig, sampleRate, bits);
            int expectedCodes = 1 << bits;
            assertEquals(expectedCodes, r.codeCount);
            assertEquals(expectedCodes, r.avgErrorLsb.length);
            assertEquals(expectedCodes, r.sampleCount.length);
            assertEquals(expectedCodes, r.interpolated.length);
        }
    }
}
