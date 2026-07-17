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

package org.edgo.audio.measure.sound.qa40x;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.bus.SampleRateChange;

import static org.edgo.audio.measure.enums.AudioBackendType.QA40X;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Qa40xRateConstraint} compare/emit logic, exercised headless through the
 * real {@link MessageBus}: a captor on {@link Events#PREFS_SAMPLE_RATE_SET}
 * records what the subscriber publishes when driven with
 * {@link Events#PREFS_SAMPLE_RATE_CHANGED} payloads.  No SWT / Display involved —
 * the subscriber only touches the bus.  Every test starts from a reset baseline
 * (both recorded rates unset) so the shared singleton cannot bleed state between
 * cases.
 */
class Qa40xRateConstraintTest {

    /** A stand-in resolved card name; the subscriber echoes it into every SET. */
    private static final String CARD = "QA403";

    private final MessageBus bus = MessageBus.instance();
    private final List<SampleRateChange> emitted = new ArrayList<>();
    private final Consumer<SampleRateChange> captor = emitted::add;

    @BeforeEach
    void setUp() {
        Qa40xRateConstraint.instance();                     // arm the singleton subscriber
        bus.subscribe(Events.PREFS_SAMPLE_RATE_SET, captor);
        reset();                                            // known baseline: both rates unset
    }

    @AfterEach
    void tearDown() {
        bus.unsubscribe(Events.PREFS_SAMPLE_RATE_SET, captor);
    }

    @Test
    void inputChange_withDifferentOutput_correctsOutput_thenEqualChangeIsNoOp() {
        // Establish an equal pair at 48 kHz (the first change syncs both directions).
        changed(false, 48_000, QA40X);
        emitted.clear();

        // Input jumps to 96 kHz → the OTHER direction (output) must be pulled up.
        changed(true, 96_000, QA40X);
        assertEquals(1, emitted.size(), "exactly one correction for the other direction");
        assertEquals(new SampleRateChange(false, 96_000, QA40X, CARD), emitted.get(0),
                "output is corrected to the input's new rate, card echoed");

        // Both combos now read 96 kHz.  Even a redundant output change to 96 kHz
        // finds the recorded pair already equal, so nothing is published — the
        // round-trip terminates by construction (equal records ⇒ no emit).
        emitted.clear();
        changed(false, 96_000, QA40X);
        assertTrue(emitted.isEmpty(), "equal recorded rates ⇒ no further correction (loop terminates)");
    }

    @Test
    void nonQa40xChange_isIgnoredAndResetsTracking() {
        changed(false, 48_000, QA40X);      // seed an equal QA40x pair
        emitted.clear();

        changed(true, 44_100, AudioBackendType.WASAPI);
        assertTrue(emitted.isEmpty(), "a non-QA40x change publishes nothing");

        // Proof the tracking was reset: with the other direction now unset again,
        // the next QA40x change behaves like a first-ever change and syncs it.
        changed(true, 192_000, QA40X);
        assertEquals(1, emitted.size(), "tracking was reset — the next change syncs the other");
        assertEquals(new SampleRateChange(false, 192_000, QA40X, CARD), emitted.get(0));
    }

    @Test
    void firstChange_withOtherDirectionUnset_syncsOther() {
        // Fresh from reset(): both directions unset.  A single input change must
        // still sync the other direction (unset counts as "differs").
        changed(true, 96_000, QA40X);
        assertEquals(1, emitted.size(), "first-ever change syncs the still-unset direction");
        assertEquals(new SampleRateChange(false, 96_000, QA40X, CARD), emitted.get(0));
    }

    // --- helpers -------------------------------------------------------------

    /** A non-QA40x change is the subscriber's reset primitive (see the ignored-
     *  and-resets case): it forgets both recorded rates.  Reused here to normalise
     *  the shared singleton to a known baseline before each test. */
    private void reset() {
        changed(true, 0, AudioBackendType.WASAPI);
        emitted.clear();
    }

    private void changed(boolean input, int hz, AudioBackendType backend) {
        bus.publish(Events.PREFS_SAMPLE_RATE_CHANGED, new SampleRateChange(input, hz, backend, CARD));
    }
}
