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

package org.edgo.audio.measure.gui.freqresp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The swept-band rule of {@link FreqRespConstants}: the read band widened
 * by a margin at each end, held inside the floor and the ceiling.
 */
class FreqRespConstantsTest {

    private static final double TOL = 1e-9;

    @Test
    void defaultMarginWidensTheBandAtBothEnds() {
        double[] swept = FreqRespConstants.sweptBandHz(800.0, 1200.0, 48000, 48000);

        assertEquals(800.0 / FreqRespConstants.SWEEP_BAND_MARGIN, swept[0], TOL);
        assertEquals(1200.0 * FreqRespConstants.SWEEP_BAND_MARGIN, swept[1], TOL);
    }

    @Test
    void unitMarginPlaysTheReadBandItself() {
        double[] swept = FreqRespConstants.sweptBandHz(909.1, 1100.0, 48000, 96000, 1.0);

        assertEquals(909.1,  swept[0], TOL);
        assertEquals(1100.0, swept[1], TOL);
    }

    @Test
    void floorAndCeilingHoldWhateverTheMargin() {
        double[] wide = FreqRespConstants.sweptBandHz(1.0, 24000.0, 48000, 96000);
        double[] unit = FreqRespConstants.sweptBandHz(0.5, 30000.0, 48000, 96000, 1.0);

        assertEquals(FreqRespConstants.FREQ_MIN_HZ, wide[0], TOL, "never below the floor");
        assertEquals(24000.0, wide[1], TOL, "never above half the lower rate");
        assertEquals(FreqRespConstants.FREQ_MIN_HZ, unit[0], TOL);
        assertEquals(24000.0, unit[1], TOL);
    }
}
