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

package org.edgo.audio.measure.dsp;

import org.junit.jupiter.api.Test;

import java.util.function.IntToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the shared lobe DSP: the robust noise-floor estimate (median + k·MAD,
 * tolerant of a wide lobe inside the band), the noise-bounded lobe extent, and
 * the power-domain lift that tapers the lobe's feet onto the noise.
 */
class ToneLobeLiftTest {

    private static final int PEAK = 1000;

    /** Noise pattern with an exact median (10) and MAD (2): values cycle
     *  8,10,12 so the ceiling is median + 4·MAD = 18. */
    private static IntToDoubleFunction noise() {
        return k -> 8.0 + 2.0 * (Math.abs(k - PEAK) % 3);
    }

    @Test
    void noiseFloorIsMedianPlusKMad() {
        ToneLobeLift lobe = new ToneLobeLift(5, 60, 200);
        assertEquals(18.0, lobe.localFloor(noise(), PEAK, 4000), 1e-9);
    }

    @Test
    void noiseFloorIgnoresAWideLobeInsideTheBand() {
        // A fat lobe (±12 bins at 5000) sits inside the flank band; median/MAD
        // must shrug it off and still report the noise ceiling, not the lobe.
        IntToDoubleFunction mag = k -> {
            int d = Math.abs(k - PEAK);
            return d <= 12 ? 5000.0 : 8.0 + 2.0 * (d % 3);
        };
        double ceiling = new ToneLobeLift(5, 60, 200).localFloor(mag, PEAK, 4000);
        assertTrue(ceiling < 50.0, "noise ceiling must ignore the lobe, was " + ceiling);
    }

    @Test
    void lineFloorReadsTheBandTopWithin25HzOutsideTheLobe() {
        // 1 Hz bins and a noise bandwidth of 2 bins: the lobe is excluded out
        // to 4 x 2 = 8 bins, the window reaches 25 bins.  The lobe inside the
        // exclusion and the huge level beyond 25 Hz must both stay out, which
        // leaves the 8,10,12 pattern of d = 9..25: 12 x 8, 12 x 10, 10 x 12,
        // median 10, MAD 2, band top 18.
        IntToDoubleFunction mag = k -> {
            int d = Math.abs(k - PEAK);
            if (d <= 8) return 5000.0;
            return d <= 25 ? 8.0 + 2.0 * (d % 3) : 1.0e6;
        };
        ToneLobeLift lobe = new ToneLobeLift();
        assertEquals(18.0, lobe.lineFloor(mag, PEAK, 4000, 1.0, 2.0), 1e-9);
        // The window is set in Hz: at 0.5 Hz bins it reaches 50 bins and takes
        // the huge level in.
        assertTrue(lobe.lineFloor(mag, PEAK, 4000, 0.5, 2.0) > 1000.0);
    }

    @Test
    void lineFloorSpanIs25HzOrTheExclusionPlusSixteenBins() {
        ToneLobeLift lobe = new ToneLobeLift();
        // 0.25 Hz bins: 25 Hz is 100 bins, far beyond 4 x 2 + 16 = 24
        assertEquals(100, lobe.lineFloorSpanBins(0.25, 2.0));
        // 5 Hz bins: 25 Hz is 5 bins, so the exclusion of ceil(4 x 2.6) = 11
        // plus 16 floor bins sets the span
        assertEquals(27, lobe.lineFloorSpanBins(5.0, 2.6));
        // a noise bandwidth under one bin counts as one: 4 + 16
        assertEquals(20, lobe.lineFloorSpanBins(5.0, 0.5));
        // the span lineFloor reads: at 1 Hz bins and 2 bins of noise bandwidth
        // the window reaches 25 bins, as the band-top test above relies on
        assertEquals(25, lobe.lineFloorSpanBins(1.0, 2.0));
    }

    @Test
    void lobeExtendsOutToTheNoiseFloor() {
        // A dome descending ×0.5/bin; with the floor at 10 it stands above the
        // noise out to d=6 (1000·0.5^6 = 15.6 > 10; 0.5^7 = 7.8 < 10).
        IntToDoubleFunction mag = k -> Math.max(8.0, 1000.0 * Math.pow(0.5, Math.abs(k - PEAK)));
        int[] e = new ToneLobeLift(5, 60, 200).lobeBins(mag, PEAK, 4000, 10.0);
        assertEquals(PEAK - 6, e[0]);
        assertEquals(PEAK + 6, e[1]);
    }

    @Test
    void stretchPinsFeetToFloorAndStretchesThePeak() {
        ToneLobeLift lobe = new ToneLobeLift();
        double floor = 10.0, peak = 1000.0, factor = 5.0;
        // The foot (at the floor) stays exactly put.
        assertEquals(floor, lobe.stretch(floor, floor, peak, factor), 1e-9);
        // The peak is pulled up to peak·factor.
        assertEquals(peak * factor, lobe.stretch(peak, floor, peak, factor), 1e-6);
        // A mid bin scales by factor^t, t = ln(mag/floor)/ln(peak/floor).
        double mid = 100.0;
        double t = Math.log(mid / floor) / Math.log(peak / floor);
        assertEquals(mid * Math.pow(factor, t), lobe.stretch(mid, floor, peak, factor), 1e-9);
        // A tone buried at/below the floor is plainly scaled.
        assertEquals(50.0, lobe.stretch(10.0, 20.0, 15.0, 5.0), 1e-9);
    }
}
