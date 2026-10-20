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

import org.edgo.audio.measure.enums.WindowType;
import org.junit.jupiter.api.Test;

import java.util.function.IntToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the shared lobe DSP: the robust noise-floor estimate (median + k·MAD,
 * tolerant of a wide lobe inside the band, its flanks starting past the
 * window's main lobe and set in Hz), the noise-bounded lobe extent and its cap
 * at the flank band's end, and the power-domain lift that tapers the lobe's
 * feet onto the noise.
 */
class ToneLobeLiftTest {

    private static final int PEAK = 1000;
    /** 3 Hz bins: each 180 Hz flank band of the local floor is 60 bins. */
    private static final double BIN_HZ = 3.0;
    /** HANN: main lobe 2 bins plus the 2-bin leakage guard, so each flank
     *  starts at 5 bins from the peak. */
    private static final WindowType WINDOW = WindowType.HANN;

    /** Noise pattern with an exact median (10) and MAD (2): values cycle
     *  8,10,12 so the ceiling is median + 4·MAD = 18. */
    private static IntToDoubleFunction noise() {
        return k -> 8.0 + 2.0 * (Math.abs(k - PEAK) % 3);
    }

    @Test
    void noiseFloorIsMedianPlusKMad() {
        assertEquals(18.0, ToneLobeLift.localFloor(noise(), PEAK, 4000, BIN_HZ, WINDOW), 1e-9);
    }

    @Test
    void noiseFloorIgnoresAWideLobeInsideTheBand() {
        // A fat lobe (±12 bins at 5000) sits inside the flank band; median/MAD
        // must shrug it off and still report the noise ceiling, not the lobe.
        IntToDoubleFunction mag = k -> {
            int d = Math.abs(k - PEAK);
            return d <= 12 ? 5000.0 : 8.0 + 2.0 * (d % 3);
        };
        double ceiling = ToneLobeLift.localFloor(mag, PEAK, 4000, BIN_HZ, WINDOW);
        assertTrue(ceiling < 50.0, "noise ceiling must ignore the lobe, was " + ceiling);
    }

    @Test
    void noiseFloorBandIsSetInHz() {
        // The 8,10,12 noise out to 64 bins, a huge level beyond.  At 3 Hz bins
        // the 180 Hz flank is d = 5..64 and reads the noise ceiling 18; at
        // 1.5 Hz bins the same 180 Hz is 120 bins, half of them the huge level.
        IntToDoubleFunction mag = k -> {
            int d = Math.abs(k - PEAK);
            return d <= 64 ? 8.0 + 2.0 * (d % 3) : 1.0e6;
        };
        assertEquals(18.0, ToneLobeLift.localFloor(mag, PEAK, 4000, BIN_HZ, WINDOW), 1e-9);
        assertTrue(ToneLobeLift.localFloor(mag, PEAK, 4000, BIN_HZ / 2.0, WINDOW) > 1000.0);
    }

    @Test
    void flankStartsPastTheWindowsMainLobe() {
        // 18 Hz bins: each 180 Hz flank is 10 bins.  A lobe at 5000 out to
        // d = 14 fills HANN's whole flank d = 5..14 (main lobe 2 + guard 2),
        // while KB38's flank starts past its 13-bin main lobe plus the guard,
        // at d = 16, on the noise alone: 6 x 8, 8 x 10, 6 x 12, band top 18.
        IntToDoubleFunction mag = k -> {
            int d = Math.abs(k - PEAK);
            return d <= 14 ? 5000.0 : 8.0 + 2.0 * (d % 3);
        };
        assertEquals(18.0, ToneLobeLift.localFloor(mag, PEAK, 4000, 18.0, WindowType.KB38), 1e-9);
        assertEquals(5000.0, ToneLobeLift.localFloor(mag, PEAK, 4000, 18.0, WindowType.HANN), 1e-9);
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
        assertEquals(18.0, ToneLobeLift.lineFloor(mag, PEAK, 4000, 1.0, 2.0), 1e-9);
        // The window is set in Hz: at 0.5 Hz bins it reaches 50 bins and takes
        // the huge level in.
        assertTrue(ToneLobeLift.lineFloor(mag, PEAK, 4000, 0.5, 2.0) > 1000.0);
    }

    @Test
    void lobeExtendsOutToTheNoiseFloor() {
        // A dome descending ×0.5/bin; with the floor at 10 it stands above the
        // noise out to d=6 (1000·0.5^6 = 15.6 > 10; 0.5^7 = 7.8 < 10).
        IntToDoubleFunction mag = k -> Math.max(8.0, 1000.0 * Math.pow(0.5, Math.abs(k - PEAK)));
        int[] e = ToneLobeLift.lobeBins(mag, PEAK, 4000, 10.0, BIN_HZ, WINDOW);
        assertEquals(PEAK - 6, e[0]);
        assertEquals(PEAK + 6, e[1]);
    }

    @Test
    void lobeWalkEndsAtTheFlankBandsOuterEnd() {
        // A lobe that never drops to the floor is cut where the floor's flank
        // band ends - main lobe + guard + 180 Hz: 4 + 60 bins at 3 Hz bins
        // with HANN, 4 + 120 at 1.5 Hz, 15 + 60 with KB38.
        IntToDoubleFunction mag = k -> 1000.0;
        int[] e = ToneLobeLift.lobeBins(mag, PEAK, 4000, 10.0, BIN_HZ, WINDOW);
        assertEquals(PEAK - 64, e[0]);
        assertEquals(PEAK + 64, e[1]);
        assertEquals(PEAK + 124, ToneLobeLift.lobeBins(mag, PEAK, 4000, 10.0, BIN_HZ / 2.0, WINDOW)[1]);
        assertEquals(PEAK + 75, ToneLobeLift.lobeBins(mag, PEAK, 4000, 10.0, BIN_HZ, WindowType.KB38)[1]);
    }

    @Test
    void stretchPinsFeetToFloorAndStretchesThePeak() {
        double floor = 10.0, peak = 1000.0, factor = 5.0;
        // The foot (at the floor) stays exactly put.
        assertEquals(floor, ToneLobeLift.stretch(floor, floor, peak, factor), 1e-9);
        // The peak is pulled up to peak·factor.
        assertEquals(peak * factor, ToneLobeLift.stretch(peak, floor, peak, factor), 1e-6);
        // A mid bin scales by factor^t, t = ln(mag/floor)/ln(peak/floor).
        double mid = 100.0;
        double t = Math.log(mid / floor) / Math.log(peak / floor);
        assertEquals(mid * Math.pow(factor, t), ToneLobeLift.stretch(mid, floor, peak, factor), 1e-9);
        // A tone buried at/below the floor is plainly scaled.
        assertEquals(50.0, ToneLobeLift.stretch(10.0, 20.0, 15.0, 5.0), 1e-9);
    }
}
