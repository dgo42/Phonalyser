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

package org.edgo.audio.measure.gui.freqresp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreqRespFormatTest {

    @Test
    void linToDbFiniteAndCorrect() {
        assertEquals(0.0,   FreqRespFormat.linToDb(1.0),  1e-9);
        assertEquals(-20.0, FreqRespFormat.linToDb(0.1),  1e-9);
        assertEquals(20.0,  FreqRespFormat.linToDb(10.0), 1e-9);
    }

    @Test
    void linToDbClampsNonPositive() {
        assertEquals(-300.0, FreqRespFormat.linToDb(0.0));
        assertEquals(-300.0, FreqRespFormat.linToDb(-1.0));
    }

    @Test
    void freqToXFractionMonotonicAndAnchored() {
        double left  = FreqRespFormat.freqToXFraction(20.0,    20.0, 20000.0);
        double mid   = FreqRespFormat.freqToXFraction(632.456, 20.0, 20000.0); // geomean
        double right = FreqRespFormat.freqToXFraction(20000.0, 20.0, 20000.0);
        assertEquals(0.0, left,  1e-9);
        assertEquals(0.5, mid,   1e-3);
        assertEquals(1.0, right, 1e-9);
    }

    @Test
    void xFractionRoundTripsViaFrequency() {
        double f0 = 234.5;
        double frac = FreqRespFormat.freqToXFraction(f0, 20.0, 20000.0);
        double back = FreqRespFormat.xFractionToFreq(frac, 20.0, 20000.0);
        assertEquals(f0, back, 1e-6);
    }

    @Test
    void dbToYFractionInvertsTopAndBottom() {
        assertEquals(0.0,  FreqRespFormat.dbToYFraction(20.0,  20.0, -140.0), 1e-9);
        assertEquals(1.0,  FreqRespFormat.dbToYFraction(-140.0, 20.0, -140.0), 1e-9);
        assertEquals(0.5,  FreqRespFormat.dbToYFraction(-60.0, 20.0, -140.0), 1e-9);
    }

    @Test
    void phaseToYFractionClampsAndCenters() {
        assertEquals(0.0, FreqRespFormat.phaseToYFraction( 180.0), 1e-9);
        assertEquals(1.0, FreqRespFormat.phaseToYFraction(-180.0), 1e-9);
        assertEquals(0.5, FreqRespFormat.phaseToYFraction(   0.0), 1e-9);
        // Out-of-range clamps.
        assertEquals(0.0, FreqRespFormat.phaseToYFraction( 999.0), 1e-9);
        assertEquals(1.0, FreqRespFormat.phaseToYFraction(-999.0), 1e-9);
    }

    @Test
    void formatDbProducesExpectedLabel() {
        assertEquals("0.00 dB",   FreqRespFormat.formatDb(0.0));
        assertEquals("-3.50 dB",  FreqRespFormat.formatDb(-3.5));
        assertEquals("10.00 dB",  FreqRespFormat.formatDb(10.0));
        assertEquals("—",         FreqRespFormat.formatDb(Double.NaN));
    }

    @Test
    void formatPhaseProducesExpectedLabel() {
        assertTrue(FreqRespFormat.formatPhase(180.0).endsWith("°"));
        assertEquals("—", FreqRespFormat.formatPhase(Double.NaN));
    }
}
