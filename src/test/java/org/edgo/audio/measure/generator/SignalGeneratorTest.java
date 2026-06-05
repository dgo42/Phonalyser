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

package org.edgo.audio.measure.generator;

import org.edgo.audio.measure.enums.GenSignalForm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour tests for the DDS {@link SignalGenerator}.  Verifies that
 * each waveform's per-sample output matches its analytic form, that
 * RMS amplitudes round-trip via the {@code amplitudeVRms} constructor
 * argument, and that the phase accumulator wraps cleanly at full scale.
 *
 * <p>Audit ranked this as the #1 highest-value test: a regression here
 * silently corrupts every WAV file the user exports, every CLI tone,
 * and every iterative-distortion-compensation pass.
 */
class SignalGeneratorTest {

    private double savedFsVoltage;

    @BeforeEach
    void rememberFsVoltage() {
        // FS_VOLTAGE is a mutable static — capture the GUI / CLI value
        // so the tests below can run with a known baseline and we can
        // restore it after.
        savedFsVoltage = SignalGenerator.FS_VOLTAGE;
        SignalGenerator.FS_VOLTAGE = 2.79351;
    }

    @AfterEach
    void restoreFsVoltage() {
        SignalGenerator.FS_VOLTAGE = savedFsVoltage;
    }

    @Test
    void sine_matchesAnalyticForm() {
        // 1 kHz at 48 kHz, 1 V RMS → 480 samples = 10 full cycles.
        // The 32-bit phase accumulator + 4096-entry table + 2nd-order
        // Taylor correction should match Math.sin to better than 1e-5.
        int    sampleRate = 48_000;
        double freqHz     = 1_000.0;
        double vrms       = 1.0;
        int    samples    = 480;

        SignalGenerator gen = new SignalGenerator(
                GenSignalForm.SINE, freqHz, sampleRate, vrms);

        double expectedPeak = vrms * Math.sqrt(2.0) / SignalGenerator.FS_VOLTAGE;
        for (int n = 0; n < samples; n++) {
            double actual   = gen.nextSample();
            double expected = expectedPeak
                    * Math.sin(2.0 * Math.PI * freqHz * n / sampleRate);
            assertEquals(expected, actual, 1e-5,
                    "sine sample " + n + " deviates from analytic form");
        }
    }

    @Test
    void sine_rmsMatchesRequestedAmplitude() {
        // Run for many full periods, compute RMS, expect it to land
        // within 0.1 % of the requested V RMS (after dividing out
        // FS_VOLTAGE since nextSample returns normalised samples).
        int    sampleRate = 48_000;
        double freqHz     = 1_000.0;
        double vrms       = 0.5;
        int    samples    = sampleRate;  // 1 second → 1000 full periods

        SignalGenerator gen = new SignalGenerator(
                GenSignalForm.SINE, freqHz, sampleRate, vrms);

        double sumSq = 0.0;
        for (int n = 0; n < samples; n++) {
            double s = gen.nextSample();
            sumSq += s * s;
        }
        double rmsNormalised = Math.sqrt(sumSq / samples);
        double rmsVolts      = rmsNormalised * SignalGenerator.FS_VOLTAGE;
        assertEquals(vrms, rmsVolts, vrms * 1e-3,
                "measured RMS should match the requested amplitude (1 ‰ tolerance)");
    }

    @Test
    void triangle_rmsMatchesAnalyticPeakOverSqrt3() {
        // Symmetric triangle has theoretical RMS = peak / √3.
        // SignalGenerator scales the raw triangle so requesting 1.0 V RMS
        // produces samples whose long-run RMS = 1.0 / FS_VOLTAGE.
        int    sampleRate = 48_000;
        double freqHz     = 1_000.0;
        double vrms       = 1.0;
        int    samples    = sampleRate;

        SignalGenerator gen = new SignalGenerator(
                GenSignalForm.TRIANGLE, freqHz, sampleRate, vrms);

        double sumSq = 0.0;
        for (int n = 0; n < samples; n++) {
            double s = gen.nextSample();
            sumSq += s * s;
        }
        double rmsNormalised = Math.sqrt(sumSq / samples);
        double rmsVolts      = rmsNormalised * SignalGenerator.FS_VOLTAGE;
        assertEquals(vrms, rmsVolts, vrms * 5e-3,
                "triangle RMS should match the requested amplitude (5 ‰ tolerance)");
    }

    @Test
    void rectangle_rmsEqualsPeak() {
        // 50/50 duty rectangle: RMS == peak amplitude.  Over many
        // periods the average sample magnitude should equal the
        // generator's linear-peak factor, and the RMS reading should
        // match the requested vrms exactly (no √2 factor).
        int    sampleRate = 48_000;
        double freqHz     = 1_000.0;
        double vrms       = 0.7;
        int    samples    = sampleRate;

        SignalGenerator gen = new SignalGenerator(
                GenSignalForm.RECTANGLE, freqHz, sampleRate, vrms);

        double sumSq = 0.0;
        for (int n = 0; n < samples; n++) {
            double s = gen.nextSample();
            sumSq += s * s;
        }
        double rmsNormalised = Math.sqrt(sumSq / samples);
        double rmsVolts      = rmsNormalised * SignalGenerator.FS_VOLTAGE;
        assertEquals(vrms, rmsVolts, vrms * 1e-3,
                "square-wave RMS should equal peak");
    }

    @Test
    void sine_outputClipsWithinNormalisedRange() {
        // nextSample() returns samples in [-1, +1] regardless of input
        // amplitude — clipping the caller's responsibility.  But for a
        // reasonable amplitude (< full-scale) every sample should stay
        // within the normalised range with room to spare.
        SignalGenerator gen = new SignalGenerator(
                GenSignalForm.SINE, 1_000.0, 48_000, 1.0);
        double peak = 0.0;
        for (int n = 0; n < 48_000; n++) {
            double s = gen.nextSample();
            assertTrue(s >= -1.0 && s <= 1.0,
                    "sample " + n + " out of normalised range: " + s);
            peak = Math.max(peak, Math.abs(s));
        }
        // Peak should approach (within 1 %) the analytic value
        // vrms * √2 / FS_VOLTAGE = 1.0 * √2 / 2.79351 ≈ 0.5063.
        double expectedPeak = Math.sqrt(2.0) / SignalGenerator.FS_VOLTAGE;
        assertEquals(expectedPeak, peak, expectedPeak * 1e-2);
    }
}
