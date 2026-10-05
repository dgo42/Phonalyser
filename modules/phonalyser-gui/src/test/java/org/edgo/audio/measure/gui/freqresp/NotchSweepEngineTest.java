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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Geometry tests for {@link NotchSweepEngine} - the loop period and the
 * swept band the tune-notch wizard's continuous sweep is built from.  No
 * device: nothing is acquired until {@code start}, and these tests never
 * call it.
 */
class NotchSweepEngineTest {

    /** A read band around a 1 kHz notch. */
    private static final double READ_LO_HZ = 1000.0 / 1.1;
    private static final double READ_HI_HZ = 1000.0 * 1.1;
    /** A target loop period and a per-seam fade, in seconds, of the kind the
     *  wizard hands over: the engine turns them into a period on its own
     *  capture clock and a band widened by the fade's share of the period. */
    private static final double TARGET_PERIOD_SEC = 2.7;
    private static final double FADE_SEC          = 0.025;

    private static final int OUT_RATE_HZ = 48000;
    private static final int OUT_BITS    = 24;
    private static final int DITHER_BITS = 0;

    @Test
    void loopPeriodIsThePowerOfTwoNearestTheTargetDuration() {
        // A power of two AT THE CAPTURE CLOCK is what makes the
        // deconvolution's transform exactly one period; both input rates
        // have to land on one at the target duration.
        try (NotchSweepEngine at48k  = engine(48000);
             NotchSweepEngine at384k = engine(384000)) {
            int period48k  = at48k.loopPeriodSamples(TARGET_PERIOD_SEC);
            int period384k = at384k.loopPeriodSamples(TARGET_PERIOD_SEC);

            assertEquals(1, Integer.bitCount(period48k),  "48 kHz period is a power of two");
            assertEquals(1, Integer.bitCount(period384k), "384 kHz period is a power of two");
            assertEquals(1 << 17, period48k);
            assertEquals(1 << 20, period384k);
            // Both land near the target, each on its own clock.
            assertEquals(TARGET_PERIOD_SEC, period48k  / 48000.0,  0.1);
            assertEquals(TARGET_PERIOD_SEC, period384k / 384000.0, 0.1);
        }
    }

    @Test
    void sweptBandEnclosesTheReadBandByTheFadeFraction() {
        try (NotchSweepEngine engine = engine(48000)) {
            int period = engine.loopPeriodSamples(TARGET_PERIOD_SEC);
            int fade   = (int) Math.round(FADE_SEC * 48000);
            double[] swept = engine.sweptBand(READ_LO_HZ, READ_HI_HZ, fade / (double) period);

            assertTrue(swept[0] < READ_LO_HZ, "the sweep starts below the band that is read");
            assertTrue(swept[1] > READ_HI_HZ, "the sweep ends above the band that is read");
            // The fade is 25 ms of a 2.73 s period, so the widening is parts
            // per thousand - the read band's outermost bins clear the fade
            // without the sweep wandering off the notch.
            assertEquals(1.0, READ_LO_HZ / swept[0], 0.02);
            assertEquals(1.0, swept[1] / READ_HI_HZ, 0.02);

            // A fade worth a tenth of the period widens the band by a lot
            // more: the margin follows the fade fraction, it is not a fixed
            // number.
            double[] coarse = engine.sweptBand(READ_LO_HZ, READ_HI_HZ, 0.1);
            assertTrue(coarse[0] < swept[0] && coarse[1] > swept[1],
                    "a longer fade has to push the swept band further out");
        }
    }

    /** An engine constructed only to be asked about the loop geometry it
     *  owns. */
    private NotchSweepEngine engine(int captureRateHz) {
        return new NotchSweepEngine(captureRateHz, OUT_RATE_HZ, OUT_BITS, DITHER_BITS);
    }
}
