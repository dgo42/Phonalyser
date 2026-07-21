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
 * {@link Qa40xLevels} raw ↔ volts math, pinning the maintainer-ruled range
 * semantics (mock bench 2026-07-17): the range label is the usable RMS full scale
 * on BOTH directions — one shared {@code +3} dB peak-vs-RMS term, the vendor's
 * {@code -6} dB input differential term deliberately dropped — plus the DAC
 * peak-volts convention (including the RMS-trap ~3 dB error).
 */
class Qa40xLevelsTest {

    private static final double TOL = 1e-6;

    @Test
    void adcVolts_plusThreeMakesLabelRmsSineFullScale() {
        // A full-scale sample is cal · 10^((dbv+3)/20) volts peak — so a sine of
        // exactly dbv dBV RMS (peak = RMS·√2 ≈ +3.01 dB) spans the whole scale.
        assertEquals(Math.pow(10.0, 3.0 / 20.0), Qa40xLevels.adcVolts(Qa40xLevels.MAXINT, 0, 1.0), TOL);
        assertEquals(Math.pow(10.0, 9.0 / 20.0), Qa40xLevels.adcVolts(Qa40xLevels.MAXINT, 6, 1.0), TOL);
        assertEquals(2.0 * Math.pow(10.0, 9.0 / 20.0), Qa40xLevels.adcVolts(Qa40xLevels.MAXINT, 6, 2.0), TOL);
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
    void inputFullScaleRms_isNearNominalDbv() {
        // 10^(3/20) ≈ √2, so FS RMS ≈ the label as an RMS voltage (within ~0.12 %).
        double fs18 = Qa40xLevels.inputFullScaleRmsVolts(18, 1.0);
        assertEquals(Math.pow(10.0, (18 + 3) / 20.0) / Math.sqrt(2.0), fs18, TOL);
        assertEquals(Math.pow(10.0, 18 / 20.0), fs18, 0.02);
        // The bench case: on the 12 dBV range a +7.9 dBV (2.48 V RMS) tone must FIT.
        assertEquals(true, 2.48 < Qa40xLevels.inputFullScaleRmsVolts(12, 1.0));
    }

    @Test
    void outputFullScaleRms_isNearNominalDbv() {
        double fs18 = Qa40xLevels.outputFullScaleRmsVolts(18, 1.0);
        assertEquals(7.9339039, fs18, 1e-4);
        // 10^(3/20) ≈ √2, so FS RMS ≈ nominal 10^(18/20) = 7.9433 (within ~0.12 %).
        assertEquals(Math.pow(10.0, 18 / 20.0), fs18, 0.02);
    }

    @Test
    void fullScaleRms_isSymmetricAcrossDirections() {
        // Maintainer ruling ("balanced output connected to balanced input — the
        // output should correspond to the input"): with unity cal, a range's
        // input and output full-scale voltages are IDENTICAL, so a loopback at
        // matching labels lands at the same dBFS on both sides.
        for (int dbv : new int[] { 0, 6, 12, 18 }) {
            assertEquals(Qa40xLevels.outputFullScaleRmsVolts(dbv, 1.0),
                         Qa40xLevels.inputFullScaleRmsVolts(dbv, 1.0), TOL);
        }
    }
}
