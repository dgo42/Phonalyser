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

/**
 * Behaviour tests for {@link RiaaCurve}.  Spot-checks the magnitude in dB at
 * canonical RIAA frequencies and verifies the algebraic invariants (1 kHz =
 * 0 dB; record = −playback; record · playback = identity even with IEC).
 *
 * <p>Reference values for the standard 75 / 318 / 3180 µs RIAA playback curve
 * (no IEC) are taken from the published RIAA table and rounded to two
 * decimals.  Tolerance is generous (0.05 dB) to absorb the time-constant
 * tweaks that creep into formulations like 318.31 vs 318.
 */
class RiaaCurveTest {

    private static final double DB_TOL = 0.05;

    @Test
    void referenceFrequencyIsAlwaysZero_dB() {
        // All four flag combinations should pin the 1 kHz reading at 0 dB.
        assertEquals(0.0, RiaaCurve.evalDb(1000.0, false, false), 1e-9);
        assertEquals(0.0, RiaaCurve.evalDb(1000.0, true,  false), 1e-9);
        assertEquals(0.0, RiaaCurve.evalDb(1000.0, false, true ), 1e-9);
        assertEquals(0.0, RiaaCurve.evalDb(1000.0, true,  true ), 1e-9);
    }

    @Test
    void playbackMatchesPublishedTable_noIec() {
        // reverse=false (default) => playback / decode curve.  Standard RIAA
        // playback values without the IEC amendment.
        assertEquals(+19.27, RiaaCurve.evalDb(20.0,    false, false), DB_TOL);
        assertEquals(+13.09, RiaaCurve.evalDb(100.0,   false, false), 0.10);
        assertEquals(  0.00, RiaaCurve.evalDb(1000.0,  false, false), 1e-9);
        assertEquals(-13.73, RiaaCurve.evalDb(10000.0, false, false), DB_TOL);
        assertEquals(-19.62, RiaaCurve.evalDb(20000.0, false, false), DB_TOL);
    }

    @Test
    void recordIsNegativeOfPlayback_atEveryFreq() {
        // playback (reverse=false) and record (reverse=true) are mirror
        // images: their sum must be exactly 0 dB at every frequency.
        double[] testHz = { 5, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000 };
        for (double f : testHz) {
            double play = RiaaCurve.evalDb(f, false, false);
            double rec  = RiaaCurve.evalDb(f, true,  false);
            assertEquals(play, -rec, 1e-9,
                    "Record + playback should sum to 0 dB at " + f + " Hz");
        }
    }

    @Test
    void recordIsNegativeOfPlayback_withIec() {
        // Even with IEC, record-with-IEC must be the algebraic inverse of
        // playback-with-IEC so a record→playback chain is flat.
        double[] testHz = { 5, 20, 50, 100, 1000, 10000, 20000 };
        for (double f : testHz) {
            double play = RiaaCurve.evalDb(f, false, true);
            double rec  = RiaaCurve.evalDb(f, true,  true);
            assertEquals(play, -rec, 1e-9,
                    "IEC chain should still be invertible at " + f + " Hz");
        }
    }

    @Test
    void iecAddsSubsonicAttenuationToPlayback() {
        // The IEC subsonic high-pass corner is at ~20 Hz.  Playback-with-IEC
        // at 20 Hz should read about 3 dB lower than playback-without-IEC,
        // and the difference should grow as frequency drops.
        double playback20    = RiaaCurve.evalDb(20.0, false, false);
        double playback20Iec = RiaaCurve.evalDb(20.0, false, true);
        double delta20       = playback20 - playback20Iec;
        assertTrue(delta20 > 2.5 && delta20 < 3.5,
                "IEC should attenuate ~3 dB at 20 Hz on the playback curve; got " + delta20);

        double playback5     = RiaaCurve.evalDb(5.0, false, false);
        double playback5Iec  = RiaaCurve.evalDb(5.0, false, true);
        double delta5        = playback5 - playback5Iec;
        assertTrue(delta5 > delta20,
                "IEC attenuation should grow toward lower frequencies");
    }

    @Test
    void iecLeavesMidAndTrebleAlone() {
        // At 1 kHz and above, the IEC high-pass has settled to ~0 dB; the
        // playback curve should read the same with or without IEC to within
        // a small tolerance.
        for (double f : new double[]{ 1000, 5000, 10000, 20000 }) {
            double noIec  = RiaaCurve.evalDb(f, false, false);
            double withIec = RiaaCurve.evalDb(f, false, true);
            assertEquals(noIec, withIec, 0.2,
                    "IEC should not shift the curve at " + f + " Hz");
        }
    }

    @Test
    void clampsNonPositiveFrequenciesWithoutThrowing() {
        // Defensive — the view may probe the curve at 0 or even negative
        // values when the mouse is left of the y-axis.  No NaN / Infinity
        // leaking out.
        double v0 = RiaaCurve.evalDb(0.0,  false, false);
        double vN = RiaaCurve.evalDb(-1.0, false, false);
        assertTrue(Double.isFinite(v0), "evalDb(0) should be finite, got " + v0);
        assertTrue(Double.isFinite(vN), "evalDb(<0) should be finite, got " + vN);
    }
}
