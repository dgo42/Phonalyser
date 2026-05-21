package org.edgo.audio.measure.gui.freqresp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreqRespAxisTicksTest {

    @Test
    void freqMajorContainsDecadeAnchors() {
        double[] major = FreqRespAxisTicks.freqMajor(20.0, 20000.0);
        // Decade anchors should always be present: 100, 1k, 10k.
        boolean has100  = false, has1k = false, has10k = false;
        for (double v : major) {
            if (Math.abs(v - 100)   < 0.1) has100  = true;
            if (Math.abs(v - 1000)  < 0.1) has1k   = true;
            if (Math.abs(v - 10000) < 0.1) has10k  = true;
        }
        assertTrue(has100,  "major freq ticks must include 100 Hz");
        assertTrue(has1k,   "major freq ticks must include 1 kHz");
        assertTrue(has10k,  "major freq ticks must include 10 kHz");
    }

    @Test
    void freqMinorExcludesMajorAndStaysInRange() {
        double[] major = FreqRespAxisTicks.freqMajor(20.0, 20000.0);
        double[] minor = FreqRespAxisTicks.freqMinor(20.0, 20000.0);
        for (double m : minor) {
            assertTrue(m >= 20.0 && m <= 20000.0, "minor tick in range: " + m);
            for (double M : major) {
                assertTrue(Math.abs(m - M) > 1e-9, "minor must not coincide with major: " + m);
            }
        }
    }

    @Test
    void magMajorHonoursLinearStep() {
        double[] major = FreqRespAxisTicks.magMajor(-140.0, 20.0);
        assertTrue(major.length >= 5, "wide dB range should yield several major ticks");
        // Each step should be a multiple of 10 dB for the niceLinear default
        // when the range is 160 dB / ~10 ticks = 16 dB → rounds to 20 dB step.
        for (int i = 1; i < major.length; i++) {
            double step = major[i] - major[i - 1];
            assertTrue(step >= 10.0 && step <= 50.0, "reasonable dB step, got " + step);
        }
    }

    @Test
    void magMinorIsSubsetOfFiveDbGrid() {
        double[] minor = FreqRespAxisTicks.magMinor(-50.0, 20.0);
        for (double v : minor) {
            double mod5 = v - Math.round(v / 5.0) * 5.0;
            assertTrue(Math.abs(mod5) < 1e-6, "minor tick should land on a 5 dB grid: " + v);
        }
    }

    @Test
    void phaseMajorCovers180Symmetric() {
        double[] phase = FreqRespAxisTicks.phaseMajor();
        assertEquals(9, phase.length, "expected 9 phase ticks (−180..+180 in 45° steps)");
        assertEquals(-180.0, phase[0]);
        assertEquals(   0.0, phase[4]);
        assertEquals( 180.0, phase[8]);
    }
}
