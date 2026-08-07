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

package org.edgo.audio.measure.gui.sound;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.preferences.BackendKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The once-per-run rule of the give-this-bench-device-a-card offer - and, since
 * the fix round, WHEN it is spent.
 *
 * <p>Asking is not settling.  The offer is deliberately not persisted (a bench
 * that gets calibrated properly next week should be offered again), so burning it
 * on something that was never an answer - a wire glitch, a device another client
 * took a second earlier - costs the operator that device for the whole run, with
 * only an app restart to get it back.
 */
class CalibrationCopyOffersTest {

    private static final String DEVICE = "QA403";
    private static final String OTHER_DEVICE = "Cosmos ADC";

    private final BackendKey bench = BackendKey.of("bench-1", AudioBackendType.QA40X);
    private final BackendKey otherBench = BackendKey.of("bench-2", AudioBackendType.QA40X);
    private final CalibrationCopyOffers offers = new CalibrationCopyOffers();

    @Test
    void anUnsettledQuestionMayBeAskedAgain() {
        assertTrue(offers.mayAsk(bench, true, DEVICE));

        // Asked, attempted - and nothing reached the bench, so it was never
        // answered.
        assertTrue(offers.mayAsk(bench, true, DEVICE),
                "a peek is not an answer: the next device change may offer again");
    }

    @Test
    void aSettledQuestionIsNotPutTwice() {
        offers.settle(bench, true, DEVICE);

        assertFalse(offers.mayAsk(bench, true, DEVICE),
                "asked and answered - an operator who said no must not be asked "
                        + "again the moment they switch device and back");
    }

    @Test
    void settlingIsIdempotent() {
        offers.settle(bench, true, DEVICE);
        offers.settle(bench, true, DEVICE);

        assertFalse(offers.mayAsk(bench, true, DEVICE));
    }

    /** Keyed per bench, DIRECTION and device: a duplex device listed under one
     *  name is two uncalibrated endpoints, and settling one must not spend the
     *  offer the other is owed. */
    @Test
    void eachBenchDirectionAndDeviceIsItsOwnQuestion() {
        offers.settle(bench, true, DEVICE);

        assertTrue(offers.mayAsk(bench, false, DEVICE), "the output half is its own");
        assertTrue(offers.mayAsk(bench, true, OTHER_DEVICE), "another device is its own");
        assertTrue(offers.mayAsk(otherBench, true, DEVICE), "another bench is its own");
    }
}
