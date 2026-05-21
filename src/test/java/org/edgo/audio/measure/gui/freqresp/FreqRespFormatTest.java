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
