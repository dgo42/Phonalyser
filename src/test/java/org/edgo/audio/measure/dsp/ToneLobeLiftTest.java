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
