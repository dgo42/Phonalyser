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

package org.edgo.audio.measure.sound;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The delivery deadline - the loss signal for the backends whose device tells
 * them nothing at all when it goes.
 *
 * <p>Two of them have no other: WDM-KS leaves a capture stream nominally ACTIVE
 * after the card is pulled, so {@code Pa_IsStreamActive} never fires; a
 * JavaSound capture line answers 0 for ever without ever throwing or reporting
 * end-of-stream.  Pulling a USB card on either one leaves the generator and the
 * scope running - nothing stops, and nothing anywhere reacts.
 *
 * <p>What is asserted here is the DISCRIMINATION, because that is what makes a
 * clock a legitimate answer rather than a guess: it fires for a started stream
 * that has gone quiet, and it cannot fire for a stop of our own, for a backend
 * that never opted in, or for a stream that is still delivering.  Whether a real
 * driver goes quiet on a real unplug is a bench question, not a JUnit one.
 */
class CaptureDeliveryDeadlineTest {

    private static final int RATE_HZ = 48_000;
    private static final int BITS = 24;
    /** How stale the ARM is let become before a block is delivered, so that a
     *  check against half of it can only pass if the DELIVERY re-armed it. */
    private static final long REARM_GAP_MS = 40L;
    /** Zero: every check is already past it, so the test spends no wall clock
     *  proving what the two-second production value means. */
    private static final long ALREADY_PAST_NANOS = 0L;

    /** A recorder with no device behind it, extending the SAME base every real
     *  recorder does - so what is asserted is the production path. */
    private static final class TestCapture extends AbstractPcmCapture {

        TestCapture() {
            super(RATE_HZ, BITS);
        }

        @Override
        public void open() {
            // Nothing to open - this capture has no device.
        }

        @Override
        public void startRecording() {
            recording.set(true);
            armDeliveryDeadline();
        }

        /** Starts WITHOUT opting in - what the QA40x and net captures do, since
         *  their far end tells them when a lane dies. */
        void startWithoutWatching() {
            recording.set(true);
        }

        @Override
        public void stopRecording() {
            recording.set(false);
        }

        @Override
        public void close() {
            recording.set(false);
        }

        void deliverOneBlock() {
            dispatch(new byte[frameSize], frameSize);
        }

        boolean stalled(long deadlineNanos) {
            return deliveryStalled(deadlineNanos);
        }
    }

    /** Registers a data listener that only cares about the END - the seam the
     *  loss now travels ({@link AudioCapture.PcmBatchListener#captureEnded}):
     *  the same listener that receives every block is told there will be no
     *  more, so the report climbs the layers the way the samples did. */
    private void tellInto(TestCapture capture, List<CaptureEndReason> told) {
        capture.setPcmBatchListener(new AudioCapture.PcmBatchListener() {
            @Override
            public void accept(byte[] pcm, int validBytes) {
                // Only the end matters to these tests.
            }

            @Override
            public void captureEnded(CaptureEndReason reason) {
                told.add(reason);
            }
        });
    }

    @Test
    void aStartedCaptureThatDeliversNothingIsReportedLost() {
        TestCapture capture = new TestCapture();
        List<CaptureEndReason> told = new ArrayList<>();
        tellInto(capture, told);
        capture.startRecording();

        assertTrue(capture.stalled(ALREADY_PAST_NANOS),
                "a started capture owes a block every audio period, and silence is "
                        + "delivered as blocks of zeroes - nothing at all is a stream that "
                        + "has stopped, not a quiet input");
        assertEquals(1, told.size(), "and whoever is measuring on it is told");
        assertFalse(capture.isRecording(),
                "the loop that noticed must also end: a capture that is over stops claiming "
                        + "to be recording");
    }

    @Test
    void aBlockThatArrivesRearmsTheDeadline() throws InterruptedException {
        TestCapture capture = new TestCapture();
        List<CaptureEndReason> told = new ArrayList<>();
        tellInto(capture, told);
        capture.startRecording();

        // Let the ARM go stale by more than the deadline the check will use, so
        // the only thing that can save this capture is the delivery itself.
        Thread.sleep(REARM_GAP_MS);
        capture.deliverOneBlock();

        assertFalse(capture.stalled(REARM_GAP_MS / 2 * 1_000_000L),
                "a capture that just delivered is plainly alive - the deadline runs from "
                        + "the last BLOCK, not from the start of the stream, or every "
                        + "capture would be declared lost two seconds after it opened");
        assertEquals(List.of(), told, "and nobody is told anything");
    }

    @Test
    void ourOwnStopIsNeverMistakenForALoss() {
        TestCapture capture = new TestCapture();
        List<CaptureEndReason> told = new ArrayList<>();
        tellInto(capture, told);
        capture.startRecording();
        capture.stopRecording();

        assertFalse(capture.stalled(ALREADY_PAST_NANOS),
                "stopRecording clears the recording flag before it touches the device, and "
                        + "that flag is what separates a stop we asked for from a device that "
                        + "went away - a stop must never raise a device-lost dialog");
        assertEquals(List.of(), told, "nobody is told: nothing was lost");
    }

    @Test
    void aBackendThatDidNotOptInIsNeverSuspected() {
        TestCapture capture = new TestCapture();
        List<CaptureEndReason> told = new ArrayList<>();
        tellInto(capture, told);
        capture.startWithoutWatching();

        assertFalse(capture.stalled(ALREADY_PAST_NANOS),
                "the deadline is opt-in: a backend whose far end reports a dead lane (the "
                        + "QA40x engine, the net session) has a better answer than a clock, "
                        + "and must keep behaving exactly as it did");
        assertEquals(List.of(), told, "nobody is told");
    }

    @Test
    void theLossIsStillReportedOnlyOnce() {
        TestCapture capture = new TestCapture();
        List<CaptureEndReason> told = new ArrayList<>();
        tellInto(capture, told);
        capture.startRecording();

        assertTrue(capture.stalled(ALREADY_PAST_NANOS));
        capture.startRecording();          // as if the loop went round again
        capture.stalled(ALREADY_PAST_NANOS);

        assertEquals(1, told.size(),
                "one device went away - the common parent says so once however many "
                        + "times a backend notices");
    }
}
