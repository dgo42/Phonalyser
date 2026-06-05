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

package org.edgo.audio.measure.gui.fft;

import org.edgo.audio.measure.enums.FftMagnitudeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link FftFormat} — magnitude-unit conversion and the
 * fractional-y kernel.  Pins the math that drives "what dBFS does this
 * Volts value sit at?" — a regression here silently shifts every chart
 * harmonic by the conversion error.
 */
class FftFormatTest {

    private static final double FS_DBV = 20 * Math.log10(1.7931);   // typical ADC FS in dBV

    @Test
    void toDbv_fromDbv_roundTripIdentity_forDbUnits() {
        double[] values = {-150, -80, -3, 0, +6};
        for (FftMagnitudeUnit u : new FftMagnitudeUnit[] {FftMagnitudeUnit.DBV, FftMagnitudeUnit.DBFS}) {
            for (double v : values) {
                double dbv = FftFormat.toDbv(v, u, FS_DBV);
                double back = FftFormat.fromDbv(dbv, u, FS_DBV);
                assertEquals(v, back, 1e-9,
                        "round-trip mismatch for unit=" + u + " v=" + v);
            }
        }
    }

    @Test
    void toDbv_dbfs_anchoredByFsDbv() {
        // 0 dBFS → fsDbv.  -20 dBFS → fsDbv - 20.
        assertEquals(FS_DBV,      FftFormat.toDbv(0.0,    FftMagnitudeUnit.DBFS, FS_DBV), 1e-9);
        assertEquals(FS_DBV - 20, FftFormat.toDbv(-20.0,  FftMagnitudeUnit.DBFS, FS_DBV), 1e-9);
    }

    @Test
    void convertMagRange_dbfsToDbv_shiftsByFsDbv() {
        double[] conv = FftFormat.convertMagRange(0.0, -120.0,
                FftMagnitudeUnit.DBFS, FftMagnitudeUnit.DBV, FS_DBV);
        // top: 0 dBFS → fsDbv dBV.  bot: -120 dBFS → fsDbv-120 dBV.
        assertEquals(FS_DBV,        conv[0], 1e-9);
        assertEquals(FS_DBV - 120,  conv[1], 1e-9);
    }

    @Test
    void convertMagRange_sameUnit_returnsInput() {
        double[] conv = FftFormat.convertMagRange(-3.0, -80.0,
                FftMagnitudeUnit.DBFS, FftMagnitudeUnit.DBFS, FS_DBV);
        assertEquals(-3.0,  conv[0], 1e-12);
        assertEquals(-80.0, conv[1], 1e-12);
    }

    @Test
    void magToYFraction_linearAxis_endpointsMapTo0And1() {
        // For dBFS axis with top=0, bot=-100: y(0)=0, y(-100)=1, y(-50)=0.5.
        assertEquals(0.0, FftFormat.magToYFraction(   0,  0, -100, FftMagnitudeUnit.DBFS), 1e-12);
        assertEquals(1.0, FftFormat.magToYFraction(-100, 0, -100, FftMagnitudeUnit.DBFS), 1e-12);
        assertEquals(0.5, FftFormat.magToYFraction( -50, 0, -100, FftMagnitudeUnit.DBFS), 1e-12);
    }

    @Test
    void magToYFraction_logAxis_decadeMapsToConstantFraction() {
        // log-axis (V) from top=1 to bot=1e-6 spans 6 decades.  Each
        // decade is exactly 1/6 of the canvas.
        double frac1 = FftFormat.magToYFraction(0.1,  1.0, 1e-6, FftMagnitudeUnit.V);
        double frac2 = FftFormat.magToYFraction(0.01, 1.0, 1e-6, FftMagnitudeUnit.V);
        assertEquals(1.0 / 6, frac1, 1e-9);
        assertEquals(2.0 / 6, frac2, 1e-9);
    }

    @Test
    void magMaxFor_dbfsIsZero() {
        assertEquals(0.0, FftFormat.magMaxFor(FftMagnitudeUnit.DBFS, 1.7931), 1e-9);
    }

    @Test
    void magMinFor_dbfsIs_300() {
        assertEquals(-300.0, FftFormat.magMinFor(FftMagnitudeUnit.DBFS, 1.7931), 1e-9);
    }
}
