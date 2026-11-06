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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the lobe stretch of {@link ToneLobeLift}: the feet pinned to the
 * noise floor, the peak pulled up to {@code peak·factor}.  The floors and the
 * lobe extent it stands on are tested in {@link FftDspTest}.
 */
class ToneLobeLiftTest {

    /** The stretch pins the feet to the floor and pulls the peak up. */
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
