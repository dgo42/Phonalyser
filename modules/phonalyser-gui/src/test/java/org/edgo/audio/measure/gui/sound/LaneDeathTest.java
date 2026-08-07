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

import java.util.function.BooleanSupplier;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.gui.generator.GeneratorController;
import org.edgo.audio.measure.preferences.BackendKey;
import org.edgo.audio.measure.preferences.BackendPrefs;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.CaptureEndReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What happens when a LOCAL lane dies under the thread that drives it -
 * asserted on the CONSULT model: device loss travels bottom-up through the
 * layer that owns it, never sideways over the bus.  The playback side records
 * WHY in the lane (the pane polls {@code takePlaybackEndedFromBelow} on its
 * visual sync points); the capture side finishes the shared ring buffer, every
 * reader answers terminally, and the first consumer to claim the reason shows
 * the one operator report.
 *
 * <p>And what happens when a lane does NOT die.  A lane can also simply stop
 * coming back - a WASAPI device pulled out from under an open stream leaves its
 * render thread inside the driver - and a controller that treats "the thread is
 * still alive" as "it is shutting down" then refuses every later start for the
 * life of the process.  The two tests in the middle are that pair: the wedged
 * lane must be recoverable, and the lane given up on must not reach into the
 * one started over it.
 *
 * <p>Driven headless against {@link DyingLaneStub}, through the ordinary
 * {@code AudioBackend} lookup, so the path is production's.
 */
class LaneDeathTest {

    private static final int RATE_HZ = 48_000;
    private static final int BIT_DEPTH = 24;
    private static final double TONE_HZ = 1_000.0;
    private static final double AMPLITUDE_VRMS = 0.1;
    /** How long a report may take to arrive before it counts as never. */
    private static final long AWAIT_MS = 10_000;

    private DyingLaneStub stub;
    private GeneratorController controller;
    private BackendKey previousActive;
    private BackendKey previousSelection;
    private GenSignalForm previousForm;
    private double previousFrequencyHz;

    @BeforeEach
    void armTheDyingBackend() {
        Preferences prefs = Preferences.instance();
        // The controller and the shared capture both write through the live
        // singleton; transient mode keeps the developer's files out of it.
        prefs.setTransientMode(true);
        previousSelection = prefs.getSelectedBackend();
        previousForm = prefs.getGenSignalForm();
        previousFrequencyHz = prefs.getGenFrequencyHz();

        AudioBackend audio = AudioBackend.instance();
        previousActive = audio.activeKey();
        stub = assertInstanceOf(DyingLaneStub.class,
                audio.manager(AudioBackendType.JAVASOUND),
                "the JAVASOUND slot went to the real backend - this test would then open "
                        + "a sound card instead of the lane it means to kill");
        audio.setActive(AudioBackendType.JAVASOUND);
        prefs.setSelectedBackend(BackendKey.of(AudioBackendType.JAVASOUND));

        BackendPrefs backend = prefs.current();
        backend.setOutputDeviceName(DyingLaneStub.OUTPUT_NAME);
        backend.setOutputSampleRate(RATE_HZ);
        backend.setOutputBitDepth(BIT_DEPTH);
        backend.setInputDeviceName(DyingLaneStub.INPUT_NAME);
        backend.setInputSampleRate(RATE_HZ);
        backend.setInputBitDepth(BIT_DEPTH);
        prefs.setGenSignalForm(GenSignalForm.SINE);
        prefs.setGenFrequencyHz(TONE_HZ);
        prefs.setGenAmplitudeVrms(AMPLITUDE_VRMS);
    }

    @AfterEach
    void disarm() {
        if (controller != null) {
            controller.shutdown();
            controller = null;
        }
        AudioBackend.instance().setActive(previousActive);
        Preferences prefs = Preferences.instance();
        if (previousSelection != null) {
            prefs.setSelectedBackend(previousSelection);
        }
        prefs.setGenSignalForm(previousForm);
        prefs.setGenFrequencyHz(previousFrequencyHz);
    }

    /**
     * The output lane: an {@code Error} out of the render thread must clear the
     * running state AND record WHY in the lane for the pane's poll.
     *
     * <p>Both assertions fail on the code as it was.  With {@code catch
     * (Exception)} the Error never reaches the handler at all - the thread dies,
     * the default uncaught handler writes a line to the log, nothing is recorded
     * and nothing is cleared - so the Play button stays lit and every later Play
     * is refused as "already running".
     */
    @Test
    void anOutputLaneThatDiesClearsTheRunningStateAndRecordsWhy() {
        controller = new GeneratorController();
        controller.start();
        assertNull(controller.getLastStartError());
        assertTrue(controller.isRunning(), "the tone is up before the device is pulled");

        stub.killPlayback();

        Throwable[] died = new Throwable[1];
        awaitTrue(() -> (died[0] = controller.takePlaybackEndedFromBelow()) != null,
                "the render thread died and nothing was recorded: the pane's poll is "
                        + "the one road a local lane death reaches the operator by");
        assertTrue(String.valueOf(died[0].getMessage()).contains(DyingLaneStub.DEATH),
                "and the operator is shown what the device actually said");
        assertFalse(controller.isRunning(),
                "a lit Play button over a line that no longer exists is the one thing "
                        + "the operator must not see");
        assertNull(controller.takePlaybackEndedFromBelow(),
                "the claim is once-only - one dead lane, one report");
    }

    /**
     * A lane that will not come back must stop refusing every later start.
     *
     * <p>The stub's render loop parks, which is what an unplugged WASAPI device
     * does to a real one: the thread is alive and will never leave the driver.
     * {@code stop()} asks it to go and waits its two seconds; the question is what
     * happens next.  While the thread was KEPT as the reason to refuse, the answer
     * was "The previous playback is still shutting down - try again in a moment"
     * to this start and to every start after it, for the life of the process,
     * because the thread it was waiting for was never going to exit.
     */
    @Test
    void aWedgedPlaybackThreadStopsRefusingEveryLaterStart() {
        controller = new GeneratorController();
        controller.start();
        assertNull(controller.getLastStartError());
        assertTrue(controller.isRunning(), "the tone is up before the lane wedges");

        controller.stop();
        assertFalse(controller.isRunning());

        controller.start();
        assertNull(controller.getLastStartError(),
                "a play thread stuck in the driver blocked every future start");
        assertTrue(controller.isRunning(), "a dead lane has to be recoverable");
    }

    /**
     * ...and the lane that was given up on must not take its replacement with it.
     *
     * <p>This is what makes abandoning one safe.  The wedged thread is still in
     * there, and when the driver finally lets it go it runs the same failure
     * handler it always did - against a controller that is now playing a
     * DIFFERENT lane.  Unguarded that handler clears {@code running}, nulls the
     * generator and sets the stop flag it finds, which is the live session's: the
     * tone the operator started stops at the next block, silently, minutes after
     * the device it has nothing to do with was unplugged.
     */
    @Test
    void anAbandonedLaneCannotSilenceTheToneStartedOverIt() {
        controller = new GeneratorController();
        controller.start();
        assertTrue(controller.isRunning());
        int abandoned = stub.openedPlaybacks() - 1;

        controller.stop();          // wedged - given up on after the two seconds
        controller.start();
        assertTrue(controller.isRunning(), "the replacement tone is up");

        stub.killPlayback(abandoned);
        // close() runs in that thread's finally, AFTER its failure handler - so
        // once it is closed, whatever the death was going to do has been done.
        awaitTrue(() -> stub.playbackClosed(abandoned),
                "the abandoned lane never finished dying");

        assertTrue(controller.isRunning(),
                "the abandoned lane reached into the running session on its way out");
        assertNull(controller.takePlaybackEndedFromBelow(),
                "and recorded a death under a tone that is playing - the stale "
                        + "session's report must be dropped, not claimed by the live one");
    }

    /**
     * The input lane: the batch callback is a thread boundary too, and its
     * failure used to be swallowed by {@code AbstractPcmCapture.dispatch} - one
     * log line, then silence, while the scope and the FFT went on drawing.
     */
    @Test
    void anInputLaneThatDiesFinishesTheStreamAndReportsOnce() {
        SharedCapture capture = SharedCapture.instance();
        SignalBufferReader reader = capture.acquire();
        assertNotNull(reader, "the stub input could not be opened: "
                + capture.getLastStartError());
        DyingLaneStub.DyingCapture line = stub.getLastCapture();
        assertNotNull(line, "the capture went to another manager than the stub");
        try {
            line.feedALyingBatch();

            // The stream is over, and the READER can say so.  It could not work
            // that out for itself - from its side a dead lane and a silent one are
            // both "no new samples" - so the side holding the card says it once,
            // into the buffer they share.
            assertTrue(reader.isFinished(),
                    "a reader over a dead lane still answered as though the stream "
                            + "were live: silence and a dead device look identical "
                            + "from the reading end");
            assertNotNull(reader.getFinishedReason(), "and it says what happened");

            // The ONE operator report: the first consumer claims the reason,
            // and a second batch off the same dead device adds nothing.
            CaptureEndReason claimed = reader.takeFinishedReasonForReport();
            assertNotNull(claimed, "the first consumer to consult claims the report");
            line.feedALyingBatch();
            assertNull(reader.takeFinishedReasonForReport(),
                    "one dead device must not produce a second report - however many "
                            + "batches fail and however many panes are reading");
        } finally {
            capture.release();
        }
        // The other half of a dead lane: the reference count has to come back to
        // zero.  Stuck above it the device is never closed and isCapturing()
        // answers true forever.
        assertFalse(capture.isCapturing(),
                "the dead input lane left SharedCapture holding a reference");
    }

    /**
     * A fresh Record after a lane death must open a NEW device, not re-attach to
     * the dead one - even while a holder that has not stopped yet keeps the
     * reference count above zero.
     *
     * <p>That holder is the whole difficulty.  Two panes record; the input dies;
     * one of them runs its stop and the other has not yet.  The count is still 1,
     * so the "already open, just take another reference" path was taken and handed
     * out a cursor over a ring nothing writes to any more - a flat trace and a
     * spectrum of silence, presented as a measurement.
     */
    @Test
    void recordingAgainAfterALaneDeathOpensAFreshDeviceRatherThanTheDeadOne() {
        SharedCapture capture = SharedCapture.instance();
        SignalBufferReader stillHeld = capture.acquire();
        assertNotNull(stillHeld, capture.getLastStartError());
        DyingLaneStub.DyingCapture dead = stub.getLastCapture();
        assertNotNull(dead);

        dead.feedALyingBatch();
        assertTrue(stillHeld.isFinished(), "the lane is dead");
        assertFalse(capture.isCapturing(),
                "a reference held over a finished stream is not a recording");

        // The second pane never released - the count is still 1.
        SignalBufferReader fresh = capture.acquire();
        assertNotNull(fresh, "the re-open failed: " + capture.getLastStartError());
        assertFalse(fresh.isFinished(),
                "Record after a device failure re-attached to the DEAD stream");
        assertNotSame(dead, stub.getLastCapture(),
                "no new device was opened - the reader came off the old lane");
        assertTrue(capture.isCapturing());

        // NOW the late holder finally runs its stop.  It owes exactly one release
        // and it must take exactly one - the reference it really acquired.  Drop
        // the count to zero on its behalf and this closes the device under the
        // pane that just started recording; refuse its release and the device is
        // never given back at all.
        capture.release();
        assertTrue(capture.isCapturing(),
                "the holder of the DEAD lane released and took the live one down "
                        + "with it - the pane that just started Record is now "
                        + "measuring a closed device");

        capture.release();
        assertFalse(capture.isCapturing(), "and the last release does give it back");

        // The count is genuinely at zero rather than negative: a further release
        // is a no-op and the next acquire still opens.
        capture.release();
        SignalBufferReader again = capture.acquire();
        assertNotNull(again, "the count went negative and locked the device out");
        capture.release();
        assertFalse(capture.isCapturing());
    }

    /** Spins until {@code condition} holds - the report crosses a thread. */
    private void awaitTrue(BooleanSupplier condition, String what) {
        long deadline = System.currentTimeMillis() + AWAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError(what);
    }
}
