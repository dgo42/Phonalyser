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

package org.edgo.audio.measure.gui.backend.loopback;

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
import org.edgo.audio.measure.preferences.BackendKey;

import static org.edgo.audio.measure.enums.AudioBackendType.LOOPBACK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LoopbackFormatConstraint} compare/emit logic, exercised headless through
 * the real {@link MessageBus}: a captor on {@link Events#PREFS_SAMPLE_RATE_SET}
 * records what the subscriber publishes when driven with
 * {@link Events#PREFS_SAMPLE_RATE_CHANGED} payloads.  No SWT / Display involved -
 * the subscriber only touches the bus.  Every test starts from a reset baseline
 * (both recorded formats unset) so the shared singleton cannot bleed state between
 * cases.
 */
class LoopbackFormatConstraintTest {

    /** A stand-in resolved card name; the subscriber echoes it into every SET. */
    private static final String CARD = "Phonalyser Loopback";
    private static final BackendKey LOCAL_LOOPBACK = BackendKey.of(LOOPBACK);
    /** The same software bench on a Phonalyser server - one format either way,
     *  which is what the constraint must key on. */
    private static final BackendKey REMOTE_LOOPBACK =
            BackendKey.of("b7e0-bench-uuid", LOOPBACK);
    private static final BackendKey LOCAL_QA40X  = BackendKey.of(AudioBackendType.QA40X);
    private static final BackendKey LOCAL_WASAPI = BackendKey.of(AudioBackendType.WASAPI);

    private final MessageBus bus = MessageBus.instance();
    private final List<SampleRateChange> emitted = new ArrayList<>();
    private final Consumer<SampleRateChange> captor = emitted::add;

    @BeforeEach
    void setUp() {
        LoopbackFormatConstraint.instance();                 // arm the singleton subscriber
        bus.subscribe(Events.PREFS_SAMPLE_RATE_SET, captor);
        reset();                                            // known baseline: nothing recorded
    }

    @AfterEach
    void tearDown() {
        bus.unsubscribe(Events.PREFS_SAMPLE_RATE_SET, captor);
    }

    @Test
    void inputChange_correctsBothFieldsOfTheOtherDirection_thenEqualChangeIsNoOp() {
        // Establish an equal pair (the first change syncs both directions).
        changed(false, 48_000, 24, LOCAL_LOOPBACK);
        emitted.clear();

        // The input jumps to 96 kHz / 16 bits -> the output must follow in BOTH.
        changed(true, 96_000, 16, LOCAL_LOOPBACK);
        assertEquals(1, emitted.size(), "exactly one correction for the other direction");
        assertEquals(new SampleRateChange(false, 96_000, 16, LOCAL_LOOPBACK, CARD), emitted.get(0),
                "output takes the input's rate AND depth, card echoed");

        // Both directions now read 96 kHz / 16 bits.  Even a redundant output
        // change finds the recorded pair already equal, so nothing is published -
        // the round-trip terminates by construction.
        emitted.clear();
        changed(false, 96_000, 16, LOCAL_LOOPBACK);
        assertTrue(emitted.isEmpty(), "equal recorded formats => no further correction");
    }

    @Test
    void depthAloneMovesTheOtherDepth_theRateRidingAlongUnchanged() {
        changed(true, 48_000, 24, LOCAL_LOOPBACK);
        emitted.clear();

        // Only the depth combo moved: the samples ARE shared, so 16 in one
        // direction cannot be 24 in the other.
        changed(true, 48_000, 16, LOCAL_LOOPBACK);
        assertEquals(1, emitted.size(), "a depth-only change still corrects the other direction");
        assertEquals(new SampleRateChange(false, 48_000, 16, LOCAL_LOOPBACK, CARD), emitted.get(0));
    }

    @Test
    void outputChange_correctsTheInput() {
        changed(false, 192_000, 32, LOCAL_LOOPBACK);
        assertEquals(1, emitted.size(), "the other direction is corrected from the output side too");
        assertEquals(new SampleRateChange(true, 192_000, 32, LOCAL_LOOPBACK, CARD), emitted.get(0));
    }

    @Test
    void firstChange_withOtherDirectionUnset_syncsOther() {
        // Fresh from reset(): nothing recorded.  A single input change must still
        // sync the other direction (unset counts as "differs").
        changed(true, 44_100, 20, LOCAL_LOOPBACK);
        assertEquals(1, emitted.size(), "first-ever change syncs the still-unset direction");
        assertEquals(new SampleRateChange(false, 44_100, 20, LOCAL_LOOPBACK, CARD), emitted.get(0));
    }

    @Test
    void foreignBackendChange_isIgnoredOnBothDirections_andResetsTracking() {
        changed(true, 48_000, 24, LOCAL_LOOPBACK);      // seed an equal loopback pair
        emitted.clear();

        // The analyzer's own constraint owns these; this one must not answer them,
        // whichever direction they name.
        changed(true,  96_000, 24, LOCAL_QA40X);
        changed(false, 96_000, 24, LOCAL_QA40X);
        changed(true,  44_100, 16, LOCAL_WASAPI);
        changed(false, 44_100, 16, LOCAL_WASAPI);
        assertTrue(emitted.isEmpty(), "a change on another backend publishes nothing");

        // Proof the tracking was reset: with the pair unset again, the next
        // loopback change behaves like a first-ever change and syncs the other.
        changed(true, 192_000, 32, LOCAL_LOOPBACK);
        assertEquals(1, emitted.size(), "tracking was reset - the next change syncs the other");
        assertEquals(new SampleRateChange(false, 192_000, 32, LOCAL_LOOPBACK, CARD), emitted.get(0));
    }

    @Test
    void switchingBetweenTwoLoopbackBenches_forgetsThePairOfTheOneLeft() {
        // Bench A is tracked at 96 kHz / 24 bits on both directions.
        changed(true, 96_000, 24, LOCAL_LOOPBACK);
        emitted.clear();

        // The operator picks bench B, whose saved input format is the same while
        // its output combos still read something else.  The dialog announces only
        // the INPUT after a repopulate, so a surviving pair from bench A would make
        // this look like "already equal" and leave bench B unequal.
        changed(true, 96_000, 24, REMOTE_LOOPBACK);

        assertEquals(1, emitted.size(),
                "the new bench's own pair starts unset, so its output is corrected");
        assertEquals(new SampleRateChange(false, 96_000, 24, REMOTE_LOOPBACK, CARD), emitted.get(0),
                "and the correction names the bench it belongs to");
    }

    @Test
    void aPayloadWithoutADepth_correctsTheRateAndNamesNoDepth() {
        // The rate-only payload shape the analyzer's constraint answers with: a
        // depth combo with no selection carries NO_BIT_DEPTH, and the correction
        // says nothing about the depth either, so the dialog leaves it alone.
        bus.publish(Events.PREFS_SAMPLE_RATE_CHANGED,
                new SampleRateChange(true, 48_000, LOCAL_LOOPBACK, CARD));
        assertEquals(1, emitted.size());
        assertEquals(SampleRateChange.NO_BIT_DEPTH, emitted.get(0).bitDepth(),
                "no depth in, no depth out");
        assertEquals(48_000, emitted.get(0).sampleRateHz());
    }

    // --- helpers -------------------------------------------------------------

    /** A change on another backend is the subscriber's reset primitive (see the
     *  ignored-and-resets case): it forgets the whole recorded pair.  Reused here
     *  to normalise the shared singleton to a known baseline before each test. */
    private void reset() {
        changed(true, 0, 0, LOCAL_WASAPI);
        emitted.clear();
    }

    private void changed(boolean input, int hz, int bits, BackendKey backend) {
        bus.publish(Events.PREFS_SAMPLE_RATE_CHANGED,
                new SampleRateChange(input, hz, bits, backend, CARD));
    }
}
