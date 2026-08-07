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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The end-of-stream contract every PCM capture backend leans on.
 *
 * <p>Each backend detects a pulled device its own way - WASAPI reads a FAILED
 * HRESULT, the PortAudio pair watches its delivery, JavaSound catches the throw
 * out of {@code TargetDataLine.read} - and none of those can be reached without
 * the real driver.  What CAN be pinned, and is what they all depend on, is what
 * the common parent promises once one of them notices: the end travels the SAME
 * seam the data travelled ({@link AudioCapture.PcmBatchListener#captureEnded}),
 * it is said exactly once, and saying it never comes back at the thread that
 * noticed.
 *
 * <p>All three matter for the same reason.  The seam is what makes the report
 * climb the layers the way the samples did - capture, ring buffer, reader,
 * worker, UI - instead of jumping sideways.  A dying device fails every
 * transfer it has in flight, so a backend can notice several times in a
 * millisecond and the layers above must hear it once.  And the caller is a
 * driver callback or a realtime capture thread - a consumer that throws there
 * would kill the very thread whose death is being reported.
 */
class AbstractPcmCaptureLossTest {

    private static final int RATE_HZ = 48_000;
    private static final int BITS = 24;
    private static final String DETAIL = "the analyzer was unplugged";

    /** A recorder with no device behind it: the test decides when the "driver"
     *  notices the loss.  It extends the SAME base every real recorder does, so
     *  what is asserted is the production path. */
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
        }

        @Override
        public void stopRecording() {
            recording.set(false);
        }

        @Override
        public void close() {
            recording.set(false);
        }

        /** What a backend's own detection path calls - the enum is what climbs
         *  the layers; the string is for the LOG alone. */
        void loseTheDevice(String logDetail) {
            endCapture(CaptureEndReason.DEVICE_LOST, logDetail);
        }
    }

    /** The ring-buffer feeder's shape: one listener for the data AND the end. */
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
    void theEndReachesTheDataListenerWithTheBackendsOwnWords() {
        TestCapture capture = new TestCapture();
        List<CaptureEndReason> told = new ArrayList<>();
        tellInto(capture, told);
        capture.startRecording();

        capture.loseTheDevice(DETAIL);

        assertEquals(List.of(CaptureEndReason.DEVICE_LOST), told,
                "a capture that has stopped delivering says so ON THE SEAM THE DATA "
                        + "TRAVELLED, as the ENUM the GUI localizes - operator prose "
                        + "never originates below the GUI layer");
        assertFalse(capture.isRecording(),
                "and a capture that is over stops claiming to be recording");
    }

    @Test
    void aDeviceThatFailsEveryTransferIsStillReportedOnce() {
        TestCapture capture = new TestCapture();
        List<CaptureEndReason> told = new ArrayList<>();
        tellInto(capture, told);

        // What a dying device really does: every transfer it had in flight
        // fails, and each one takes the backend down the same path.
        capture.loseTheDevice(DETAIL);
        capture.loseTheDevice("and again");
        capture.loseTheDevice("and again");

        assertEquals(1, told.size(),
                "the layers above are told once - one device went away, not three");
        assertEquals(CaptureEndReason.DEVICE_LOST, told.get(0),
                "and it is the FIRST reason that is kept");
    }

    @Test
    @SuppressWarnings("resource")
    void aConsumerThatThrowsDoesNotComeBackAtTheDriverThread() {
        TestCapture capture = new TestCapture();
        capture.setPcmBatchListener(new AudioCapture.PcmBatchListener() {
            @Override
            public void accept(byte[] pcm, int validBytes) {
                // Only the end matters to this test.
            }

            @Override
            public void captureEnded(CaptureEndReason reason) {
                throw new IllegalStateException("the consumer blew up");
            }
        });

        assertDoesNotThrow(() -> capture.loseTheDevice(DETAIL),
                "this runs on a driver callback or a realtime capture thread: a "
                        + "throw let back out would kill the very thread whose death "
                        + "is being reported");
    }

}
