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

package org.edgo.audio.measure.sound.qa40x;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link Qa40xLevels} raw ↔ volts math, pinning the range semantics of the
 * REAL device (bench 2026-07-20, doc §6 cheat-sheet — supersedes the
 * 2026-07-17 mock ruling): the OUTPUT label is genuine per-leg RMS dBV, while
 * the INPUT "N dBV" label is a Vpp-differential (dBFS) clip reference — the
 * input's true RMS full scale sits 9 dB below its label ({@code +3} peak/RMS
 * and {@code +6} differential ×2) — plus the DAC peak-volts convention
 * (including the RMS-trap ~3 dB error).
 */
class Qa40xLevelsTest {

    private static final double TOL = 1e-6;

    @Test
    void adcVolts_fullScaleIsHalfTheVppDifferentialClip() {
        // The "N dBV" input label is the Vpp-DIFFERENTIAL clip: 10^(N/20) is
        // peak-to-peak, so a full-scale sample (±MAXINT) is its half — the
        // peak — and a full-scale sine reads ≈ (N − 9) dBV RMS (doc §6).
        assertEquals(0.5, Qa40xLevels.adcVolts(Qa40xLevels.MAXINT, 0, 1.0), TOL);
        assertEquals(Math.pow(10.0, 6.0 / 20.0) / 2.0, Qa40xLevels.adcVolts(Qa40xLevels.MAXINT, 6, 1.0), TOL);
        assertEquals(Math.pow(10.0, 6.0 / 20.0), Qa40xLevels.adcVolts(Qa40xLevels.MAXINT, 6, 2.0), TOL);
    }

    @Test
    void adcVolts_scalesLinearlyWithRaw() {
        double full = Qa40xLevels.adcVolts(Qa40xLevels.MAXINT, 6, 1.0);
        double half = Qa40xLevels.adcVolts(Qa40xLevels.MAXINT / 2, 6, 1.0);
        assertEquals(full / 2.0, half, 1e-3);
    }

    @Test
    void dacInt32_usesNegativeOutputPlusThreeExponent() {
        // Independent re-derivation of the -(dbv+3) exponent at mid-scale (no saturation).
        long expected = Math.round(Math.pow(10.0, -(18 + 3) / 20.0) * Qa40xLevels.MAXINT);
        assertEquals((int) expected, Qa40xLevels.dacInt32(1.0, 18, 1.0));
    }

    @Test
    void dacInt32_rmsTrapIsAboutThreeDbLow() {
        double vrms = 1.0;
        int correct = Qa40xLevels.dacInt32(Math.sqrt(2.0) * vrms, 18, 1.0);   // PEAK — correct
        int trap    = Qa40xLevels.dacInt32(vrms, 18, 1.0);                    // RMS — the ~3 dB trap
        double errDb = 20.0 * Math.log10((double) correct / trap);
        assertEquals(3.0103, errDb, 0.01);
    }

    @Test
    void dacInt32_saturatesToMaxint() {
        assertEquals(Qa40xLevels.MAXINT, Qa40xLevels.dacInt32(1000.0, 18, 1.0));
        assertEquals(-Qa40xLevels.MAXINT, Qa40xLevels.dacInt32(-1000.0, 18, 1.0));
    }

    @Test
    void inputFullScaleRms_isNineDbBelowTheLabel() {
        // The input label is a Vpp-differential (dBFS) reference: RMS full
        // scale = 10^(N/20)/(2√2), i.e. a constant 9.03 dB below the label
        // (doc §6; supersedes the mock-era "label = RMS" bench case).
        double fs18 = Qa40xLevels.inputFullScaleRmsVolts(18, 1.0);
        assertEquals(Math.pow(10.0, 18 / 20.0) / (2.0 * Math.sqrt(2.0)), fs18, TOL);
        assertEquals(Math.pow(10.0, (18 - 9.0) / 20.0), fs18, 0.02);
    }

    @Test
    void outputFullScaleRms_isNearNominalDbv() {
        double fs18 = Qa40xLevels.outputFullScaleRmsVolts(18, 1.0);
        assertEquals(7.9339039, fs18, 1e-4);
        // 10^(3/20) ≈ √2, so FS RMS ≈ nominal 10^(18/20) = 7.9433 (within ~0.12 %).
        assertEquals(Math.pow(10.0, 18 / 20.0), fs18, 0.02);
    }

    @Test
    void fullScaleRms_inputSitsNineDbBelowOutputAtMatchingLabels() {
        // The mock-era symmetry ruling is superseded (doc §6, bench
        // 2026-07-20): a digital mock loopback shows no differential
        // doubling, so it could not surface the input's 9 dB offset.  At
        // matching labels the output full scale is 2·10^(3/20) (= +9.03 dB)
        // above the input full scale — one √2 (peak/RMS) plus one ×2
        // (differential), for every range.
        for (int dbv : new int[] { 0, 6, 12, 18 }) {
            double ratio = Qa40xLevels.outputFullScaleRmsVolts(dbv, 1.0)
                         / Qa40xLevels.inputFullScaleRmsVolts(dbv, 1.0);
            assertEquals(2.0 * Math.pow(10.0, 3.0 / 20.0), ratio, TOL);
        }
    }
}
