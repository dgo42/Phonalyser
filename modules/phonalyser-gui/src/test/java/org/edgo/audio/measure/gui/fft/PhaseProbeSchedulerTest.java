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

package org.edgo.audio.measure.gui.fft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link PhaseProbeScheduler} with injected probe times and phase
 * residuals - it reads no clock, so the whole schedule is exercised here
 * without waiting for any of it.  Covers the interval before a drift rate can
 * be measured, the budget division that sets it afterwards, both ends of the
 * clamp, including the rate of zero that divides into an infinite interval,
 * and the abandoned probe, which moves the next opportunity and nothing else.
 */
class PhaseProbeSchedulerTest {

    /** Mirrors PhaseProbeScheduler.PROBE_PERIOD_INITIAL_SEC. */
    private static final double INITIAL_SEC = 60.0;
    /** Mirrors PhaseProbeScheduler.PROBE_PERIOD_MIN_SEC. */
    private static final double MIN_SEC = 30.0;
    /** Mirrors PhaseProbeScheduler.PROBE_PERIOD_MAX_SEC. */
    private static final double MAX_SEC = 600.0;
    /** Tolerance on a derived interval - the arithmetic is one division. */
    private static final double TOL_SEC = 1e-9;

    private final PhaseProbeScheduler scheduler = new PhaseProbeScheduler();

    @Test
    void baselineProbeIsDueImmediatelyAndLeavesTheInitialPeriod() {
        assertTrue(scheduler.isDue(0.0), "the collection's baseline probe is due at once");
        assertEquals(INITIAL_SEC, scheduler.getPeriodSec(), TOL_SEC);

        scheduler.probeCompleted(0.0, 0.0);

        assertEquals(INITIAL_SEC, scheduler.getPeriodSec(), TOL_SEC,
                "one probe measures no rate, so the fixed first interval stands");
        assertFalse(scheduler.isDue(INITIAL_SEC - 1.0), "not due a second early");
        assertTrue(scheduler.isDue(INITIAL_SEC), "due once the interval has passed");
    }

    @Test
    void periodIsTheBudgetDividedByTheMeasuredDriftRate() {
        // Half a degree in a minute: a degree of budget is worth two minutes.
        scheduler.probeCompleted(0.0, 0.0);
        scheduler.probeCompleted(60.0, 0.5);

        assertEquals(120.0, scheduler.getPeriodSec(), TOL_SEC);
        assertFalse(scheduler.isDue(179.0), "the interval runs from the last probe");
        assertTrue(scheduler.isDue(180.0));
    }

    @Test
    void driftDirectionDoesNotChangeThePeriod() {
        scheduler.probeCompleted(0.0, 0.0);
        scheduler.probeCompleted(60.0, -0.5);

        assertEquals(120.0, scheduler.getPeriodSec(), TOL_SEC,
                "a notch drifting the other way is drifting just as fast");
    }

    @Test
    void fastDriftClampsToTheFloor() {
        scheduler.probeCompleted(0.0, 0.0);
        scheduler.probeCompleted(60.0, 100.0);

        assertEquals(MIN_SEC, scheduler.getPeriodSec(), TOL_SEC,
                "the clamp holds the PERIOD, so a runaway rate cannot crowd out "
                        + "the collection it serves");
    }

    @Test
    void slowDriftClampsToTheCeiling() {
        scheduler.probeCompleted(0.0, 0.0);
        scheduler.probeCompleted(600.0, 0.001);

        assertEquals(MAX_SEC, scheduler.getPeriodSec(), TOL_SEC);
    }

    @Test
    void zeroDriftRateGivesTheCeilingRatherThanInfinity() {
        scheduler.probeCompleted(0.0, 2.0);
        scheduler.probeCompleted(60.0, 2.0);

        assertEquals(MAX_SEC, scheduler.getPeriodSec(), TOL_SEC,
                "a budget divided by a rate of zero must land on the ceiling");
        assertTrue(scheduler.isDue(60.0 + MAX_SEC));
    }

    @Test
    void twoProbesAtTheSameInstantMeasureNoRate() {
        scheduler.probeCompleted(0.0, 0.0);
        scheduler.probeCompleted(0.0, 5.0);

        assertEquals(INITIAL_SEC, scheduler.getPeriodSec(), TOL_SEC,
                "no elapsed time, no rate - the interval in force stands");
    }

    @Test
    void abandonedProbeOnlyMovesTheNextOpportunity() {
        scheduler.probeCompleted(0.0, 0.0);
        scheduler.probeCompleted(60.0, 0.5);
        assertEquals(120.0, scheduler.getPeriodSec(), TOL_SEC);

        scheduler.probeFailed(180.0);

        assertEquals(120.0, scheduler.getPeriodSec(), TOL_SEC,
                "a probe that measured nothing derives no interval");
        assertFalse(scheduler.isDue(180.0 + MIN_SEC - 1.0),
                "the failed attempt holds the next one back by the floor interval");
        assertTrue(scheduler.isDue(180.0 + MIN_SEC),
                "and no longer: the interval in force decides again");
    }

    @Test
    void anAbandonedProbeLeavesTheDriftHistoryUntouched() {
        scheduler.probeCompleted(0.0, 0.0);
        scheduler.probeFailed(10.0);
        // The rate is measured between the two probes that MEASURED, so this
        // pair is a degree in a minute - a minute of interval - and not
        // anything the abandoned attempt in between would have made of it.
        scheduler.probeCompleted(60.0, 1.0);

        assertEquals(60.0, scheduler.getPeriodSec(), TOL_SEC);
        assertTrue(scheduler.isDue(120.0), "a measuring probe clears the hold");
    }

    @Test
    void resetPutsTheScheduleBackToACollectionStart() {
        scheduler.probeCompleted(0.0, 0.0);
        scheduler.probeCompleted(60.0, 100.0);
        assertEquals(MIN_SEC, scheduler.getPeriodSec(), TOL_SEC);

        scheduler.reset();

        assertEquals(INITIAL_SEC, scheduler.getPeriodSec(), TOL_SEC);
        assertTrue(scheduler.isDue(60.0), "the next collection starts with a baseline probe");
    }
}
