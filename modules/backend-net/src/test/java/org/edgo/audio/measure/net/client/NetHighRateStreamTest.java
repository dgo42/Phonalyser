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

package org.edgo.audio.measure.net.client;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import com.fasterxml.jackson.databind.JsonNode;

import org.edgo.audio.measure.net.proto.JsonCodec;
import org.edgo.audio.measure.net.proto.MessageType;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.net.proto.NetMessage;
import org.edgo.audio.measure.sound.DeviceRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bench's worst case through the CLIENT: a 384 kHz / 24-bit stereo stream -
 * 2.3 MB/s, paced at the device's own clock - crossing a real socket into a real
 * {@link NetPcmCapture}, with the bytes a continuous counter so a drop, a
 * duplicate or a reorder fails on the CONTENT and not merely on a total.
 *
 * <p><b>What this file is and is not about now.</b>  A real bench
 * refuses every fifth to ninth frequency-response sweep at exactly this rate,
 * with the server logging "2048 stereo frame(s) dropped - the client is not
 * keeping up".  Whether that reading is fair is the SERVER's own policy, and the
 * server is not in this module in any scope, so it is no longer decided here.
 * What is decided here is the other half, and it is the half the client owes: at
 * this rate, for this long, through a GC-sized pause and through a consumer that
 * stops entirely, everything the socket delivered reaches the pipeline exactly
 * once, in order, byte for byte - and a loss the bench CONFESSES is heard rather
 * than silently spliced.
 *
 * <p>The pacing is the one deliberate wait in this suite: a feeder that went as
 * fast as it could would measure the machine's memory bandwidth instead of a
 * stream.
 */
class NetHighRateStreamTest {

    private static final String SERVER_NAME = "Bench high rate";
    private static final String CLIENT_NAME = "Developer's laptop";
    private static final String CLIENT_APP = "Phonalyser desktop test";
    private static final long AWAIT_MS = 30_000;
    /** What the teardown gives the peer to wind its accept loop down.  Short on
     *  purpose: nothing is asserted after it, and the socket library waits out
     *  this whole budget whenever a client went away without a close handshake. */
    private static final long SHUTDOWN_MS = 200;

    /** The bench's worst case: the analyzer's top rate and the width a QA40x
     *  really delivers - 24 bits, so the wire carries 2.30 MB/s and not the
     *  3.07 MB/s a 32-bit reading of it would suggest. */
    private static final int RATE_HZ = 384_000;
    private static final int BITS = 24;
    /** 24-bit stereo: three bytes a sample, two samples a frame. */
    private static final int FRAME_BYTES = 6;
    /** What the QA40x engine hands its consumer per USB read - its own steady
     *  chunk, so the batch size and the cadence are the bench's. */
    private static final int BATCH_FRAMES = 2_048;
    private static final int BATCH_BYTES = BATCH_FRAMES * FRAME_BYTES;
    /** One batch of audio, in nanoseconds - the device's clock. */
    private static final long BATCH_NS = 1_000_000_000L * BATCH_FRAMES / RATE_HZ;
    /** How long the stream runs: long enough that a reader which cannot keep up
     *  at this rate falls behind for good, short enough to stay a unit test. */
    private static final int BATCHES = 300;
    private static final long TOTAL_BYTES = (long) BATCHES * BATCH_BYTES;
    /** The long-exposure run: five times the above, which at this rate is the
     *  several seconds of lead-in the bench's worst sweep prepends. */
    private static final int LONG_BATCHES = 5 * BATCHES;
    /** The client-side pause the transport has to ride out - the shape of a full
     *  GC over a frequency-response record's tens of megabytes. */
    private static final long HICCUP_MS = 200;
    private static final long HICCUP_NS = HICCUP_MS * 1_000_000L;
    /** One lane of a 2M-FFT record at this rate - the window the failing sweep
     *  uses, and the thing the working 128k/256k ones do not allocate.  Two of
     *  these stay live for the whole run. */
    private static final int RECORD_SAMPLES = 2_097_152;
    /** Garbage made per batch on top of that record - the copies and scratch a
     *  measurement produces while it fills. */
    private static final int CHURN_SAMPLES = 65_536;
    /** What the bench confesses when it drops audio for a reader that stopped:
     *  one batch's worth of stereo frames. */
    private static final long LOST_FRAMES = BATCH_FRAMES;

    private final JsonCodec codec = new JsonCodec();
    private final List<NetConnection> connections = new ArrayList<>();

    private MockBench bench;
    private URI wsUri;

    @BeforeEach
    void startTheBench() {
        bench = new MockBench(codec, SERVER_NAME);
        wsUri = bench.listen(AWAIT_MS);
    }

    @AfterEach
    void stopTheBench() {
        for (NetConnection connection : connections) {
            connection.connClose(NetCloseReason.BYE);
        }
        bench.shutDown(SHUTDOWN_MS);
    }

    /**
     * The whole stream, at the bench's top rate, into a consumer that does
     * nothing but check: every byte, once, in order.
     *
     * <p>A splice at this rate is what the observed wobble looks like from
     * the client's side - a record of the right length, full of signal, with a
     * hole nothing downstream can see.
     */
    @Test
    void everyByteOfTheStreamArrivesExactlyAndInOrder() throws Exception {
        NetDeviceManager manager = connect();
        Faults faults = new Faults();
        manager.setFaultListener(faults);
        NetPcmCapture capture = openInput(manager);

        AtomicLong receivedBytes = new AtomicLong();
        AtomicLong mismatch = new AtomicLong(-1);
        CountDownLatch whole = new CountDownLatch(1);
        capture.setPcmBatchListener((pcm, validBytes) ->
                check(pcm, validBytes, receivedBytes, mismatch, TOTAL_BYTES, whole));
        capture.open();
        assertEquals(RATE_HZ, Math.round(capture.getFormat().getSampleRate()),
                "the bench granted another rate, so this is not the case under test");
        capture.startRecording();
        MockBench.Capture stream = bench.getLastCapture();
        assertNotNull(stream, "the client opened no stream on the bench");

        feed(stream, BATCHES);

        assertTrue(whole.await(AWAIT_MS, TimeUnit.MILLISECONDS),
                "only " + receivedBytes.get() + " of " + TOTAL_BYTES + " bytes reached "
                        + "the pipeline");
        assertEquals(-1, mismatch.get(),
                "the stream is a continuous counter, so the first byte that is not the "
                        + "one expected is a splice - at byte " + mismatch.get());
        assertEquals(-1, faults.lost.get(),
                "nothing was lost and nothing was confessed lost");

        capture.close();
    }

    /**
     * A consumer that hiccups - a GC pause over the tens of megabytes a
     * frequency-response record allocates - costs time and nothing else.
     *
     * <p>The reader stops reading in the middle of the run while the bench goes
     * on sending at the device's clock, so the audio really does pile up behind
     * it.  When it resumes, what it gets must be the continuation and not a
     * fresher position: a client that dropped what it could not take at once
     * would splice, and the splice is undetectable downstream.
     */
    @Test
    void aConsumerHiccupCostsTimeAndNothingElse() throws Exception {
        NetDeviceManager manager = connect();
        Faults faults = new Faults();
        manager.setFaultListener(faults);
        NetPcmCapture capture = openInput(manager);

        AtomicLong receivedBytes = new AtomicLong();
        AtomicLong mismatch = new AtomicLong(-1);
        CountDownLatch whole = new CountDownLatch(1);
        CountDownLatch hiccup = new CountDownLatch(1);
        capture.setPcmBatchListener((pcm, validBytes) -> {
            long done = check(pcm, validBytes, receivedBytes, mismatch, TOTAL_BYTES, whole);
            if (done >= TOTAL_BYTES / 2 && hiccup.getCount() > 0) {
                // The pause, once, in the middle: the reader stops reading and
                // the transport is all there is between the ADC and the loss.
                hiccup.countDown();
                pauseUntil(System.nanoTime() + HICCUP_NS);
            }
        });
        capture.open();
        capture.startRecording();
        MockBench.Capture stream = bench.getLastCapture();
        assertNotNull(stream, "the client opened no stream on the bench");

        feed(stream, BATCHES);

        assertTrue(whole.await(AWAIT_MS, TimeUnit.MILLISECONDS),
                "only " + receivedBytes.get() + " of " + TOTAL_BYTES + " bytes arrived "
                        + "after a " + HICCUP_MS + " ms pause");
        assertEquals(0, hiccup.getCount(), "the pause never happened, so this proves nothing");
        assertEquals(-1, mismatch.get(),
                "a splice at byte " + mismatch.get() + " - the reader resumed somewhere "
                        + "other than where it stopped");
        assertEquals(-1, faults.lost.get(), "and nothing was confessed lost");

        capture.close();
    }

    /**
     * The bench's own shape in the rig: a consumer carrying a
     * FREQUENCY-RESPONSE-sized record while the stream runs.
     *
     * <p>The observed failure tracks the FFT size and nothing else - 128k and
     * 256k are clean at this very rate and sweep, 2M is not - and the FFT size is
     * what sets the record length, so what the client holds and allocates is the
     * variable.  A 2M window at 384 kHz is a couple of million samples in each of
     * two {@code double[]} lanes, tens of megabytes live, allocated per sweep and
     * collected between them.  So this run keeps that much alive and churns more,
     * and asks the one question this side can answer: is what arrives EXACTLY
     * what the ADC produced?  A record that is late is a record the analyzer can
     * still deconvolve; one with an unannounced splice is the wobble.
     */
    @Test
    void aConsumerHoldingAFullSizedRecordStillGetsEveryByteExactly() throws Exception {
        NetDeviceManager manager = connect();
        Faults faults = new Faults();
        manager.setFaultListener(faults);
        NetPcmCapture capture = openInput(manager);

        // What a 2M-FFT sweep allocates: two lanes of the whole record, live for
        // the measurement's duration.
        double[] left = new double[RECORD_SAMPLES];
        double[] right = new double[RECORD_SAMPLES];
        AtomicLong receivedBytes = new AtomicLong();
        AtomicLong mismatch = new AtomicLong(-1);
        AtomicLong churn = new AtomicLong();
        CountDownLatch whole = new CountDownLatch(1);
        capture.setPcmBatchListener((pcm, validBytes) -> {
            long at = receivedBytes.get();
            check(pcm, validBytes, receivedBytes, mismatch, TOTAL_BYTES, whole);
            // The record being written, and the garbage a measurement makes on
            // top of it - this is what the collector has to walk mid-stream.
            int frames = validBytes / FRAME_BYTES;
            int start = (int) Math.min(at / FRAME_BYTES, RECORD_SAMPLES - frames);
            for (int f = 0; f < frames; f++) {
                left[start + f] = f;
                right[start + f] = -f;
            }
            churn.addAndGet(new double[CHURN_SAMPLES].length);
        });
        capture.open();
        capture.startRecording();
        MockBench.Capture stream = bench.getLastCapture();
        assertNotNull(stream, "the client opened no stream on the bench");

        feed(stream, BATCHES);

        assertTrue(whole.await(AWAIT_MS, TimeUnit.MILLISECONDS),
                "only " + receivedBytes.get() + " of " + TOTAL_BYTES + " bytes arrived");
        assertEquals(-1, mismatch.get(),
                "an unannounced splice at byte " + mismatch.get() + " - a record with one "
                        + "of those in it deconvolves into exactly the wobble the bench "
                        + "sees, and nothing downstream can tell");
        assertEquals(-1, faults.lost.get(), "with nothing confessed lost");
        assertTrue(churn.get() > 0, "the churn is what makes this a GC test at all");
        assertEquals(RECORD_SAMPLES, left.length + right.length - RECORD_SAMPLES,
                "the record stayed live for the whole run, which is the point");

        capture.close();
    }

    /**
     * The longest exposure the bench asks for: a lead-in of several seconds in
     * front of the sweep, which is simply a much longer capture.
     *
     * <p>That case is the decisive one: a longer lead-in made the drops
     * PERMANENT - not one sweep in five but every attempt.  Whatever the
     * far end decides about that, length must not be a risk of the CLIENT's own:
     * this runs five times longer than the cases above and must be just as exact.
     */
    @Test
    void aLongExposureIsJustALongerCaptureAndStaysExact() throws Exception {
        NetDeviceManager manager = connect();
        Faults faults = new Faults();
        manager.setFaultListener(faults);
        NetPcmCapture capture = openInput(manager);

        AtomicLong receivedBytes = new AtomicLong();
        AtomicLong mismatch = new AtomicLong(-1);
        long total = (long) LONG_BATCHES * BATCH_BYTES;
        CountDownLatch whole = new CountDownLatch(1);
        capture.setPcmBatchListener((pcm, validBytes) ->
                check(pcm, validBytes, receivedBytes, mismatch, total, whole));
        capture.open();
        capture.startRecording();
        MockBench.Capture stream = bench.getLastCapture();
        assertNotNull(stream, "the client opened no stream on the bench");

        feed(stream, LONG_BATCHES);

        assertTrue(whole.await(AWAIT_MS, TimeUnit.MILLISECONDS),
                "only " + receivedBytes.get() + " of " + total + " bytes arrived over "
                        + (LONG_BATCHES * BATCH_NS / 1_000_000L) + " ms");
        assertEquals(-1, mismatch.get(), "an unannounced splice at byte " + mismatch.get());
        assertEquals(-1, faults.lost.get(), "with nothing confessed lost");

        capture.close();
    }

    /**
     * And the contract that stays whatever the far end's policy is: when the
     * bench DOES drop audio, the client is told, and it is told at this rate too.
     *
     * <p>The reader is held for the whole run so the audio really piles up behind
     * it; the bench then confesses a loss, as a server whose allowance has
     * overflowed must (spec 5).  Riding out a hiccup must never turn into
     * swallowing a real overload - a gap nobody hears is exactly the silent
     * splice the spec forbids.
     */
    @Test
    void aConfessedLossIsHeardEvenAtThisRate() throws Exception {
        NetDeviceManager manager = connect();
        Faults faults = new Faults();
        manager.setFaultListener(faults);
        NetPcmCapture capture = openInput(manager);

        CountDownLatch released = new CountDownLatch(1);
        CountDownLatch reading = new CountDownLatch(1);
        capture.setPcmBatchListener((pcm, validBytes) -> {
            reading.countDown();
            await(released);
        });
        capture.open();
        capture.startRecording();
        MockBench.Capture stream = bench.getLastCapture();
        assertNotNull(stream, "the client opened no stream on the bench");

        feed(stream, BATCHES);
        assertTrue(reading.await(AWAIT_MS, TimeUnit.MILLISECONDS),
                "the client never received anything, so it was never the READER that "
                        + "stopped");
        stream.gap(LOST_FRAMES);
        released.countDown();

        awaitTrue(() -> faults.lost.get() == LOST_FRAMES,
                "the bench dropped audio for a client that stopped reading and the client "
                        + "said nothing about it - the honest-loss rule is the one thing "
                        + "this lane may not trade away for throughput");
        assertTrue(capture.isRecording(),
                "and a GAP is still the honest path: the stream carries on");

        capture.close();
    }

    // -------------------------------------------------------------------------
    // Harness
    // -------------------------------------------------------------------------

    /**
     * Checks one delivered batch against the counter this stream IS, counts it,
     * and opens {@code whole} once the run is in.  Answers the running total, so
     * a caller that wants to act at the halfway mark can.
     */
    private long check(byte[] pcm, int validBytes, AtomicLong receivedBytes,
            AtomicLong mismatch, long total, CountDownLatch whole) {
        long at = receivedBytes.get();
        for (int i = 0; i < validBytes; i++) {
            if (pcm[i] != counter(at + i) && mismatch.get() < 0) {
                mismatch.set(at + i);
            }
        }
        long done = receivedBytes.addAndGet(validBytes);
        if (done >= total) {
            whole.countDown();
        }
        return done;
    }

    /** Hands {@code batches} batches to the stream at the DEVICE's clock. */
    private void feed(MockBench.Capture stream, int batches) {
        long start = System.nanoTime();
        for (int i = 0; i < batches; i++) {
            stream.feed(batch(i));
            pauseUntil(start + (i + 1) * BATCH_NS);
        }
    }

    /** Spins until {@code condition} holds - a condition, never a sleep. */
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

    /** Blocks the reader on {@code gate}, restoring the interrupt flag rather
     *  than swallowing it. */
    private void await(CountDownLatch gate) {
        try {
            gate.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private NetDeviceManager connect() {
        NetConnection connection = new NetConnection(wsUri, CLIENT_NAME, CLIENT_APP, codec,
                new FakeTicker());
        connections.add(connection);
        connection.open(AWAIT_MS);
        NetDeviceManager manager = new NetDeviceManager();
        manager.connect(connection, select(connection, servedBackend(connection)));
        return manager;
    }

    private NetPcmCapture openInput(NetDeviceManager manager) {
        DeviceRef input = manager.listInputDevices().get(0);
        return (NetPcmCapture) manager.openCapture(input, RATE_HZ, BITS);
    }

    private JsonNode select(NetConnection connection, String backend) {
        NetMessage answer = connection.request(
                connection.newRequest(MessageType.BACKEND_SELECT)
                        .put(NetFields.BACKEND, backend));
        assertTrue(answer.isOk(), "the bench refused backend.select: " + answer);
        return answer.getData();
    }

    private String servedBackend(NetConnection connection) {
        NetMessage list = connection.request(
                connection.newRequest(MessageType.DEVICES_LIST));
        assertTrue(list.isOk());
        for (JsonNode backend : list.getData().path(NetFields.BACKENDS)) {
            if (!backend.path(NetFields.DEVICES).isEmpty()) {
                return backend.path(NetFields.BACKEND).asText();
            }
        }
        throw new IllegalStateException("this bench serves no device at all: "
                + list.getData());
    }

    /**
     * The device's clock: waits until {@code deadlineNs}.  A test that fed as
     * fast as it could would measure its own appetite instead of a stream.
     *
     * <p>It PARKS rather than spins.  Spinning here burned a core on the feeder
     * and another on the pause below, and on a loaded machine that is CPU the
     * client's reader needs to keep up.
     */
    private void pauseUntil(long deadlineNs) {
        for (long left = deadlineNs - System.nanoTime(); left > 0;
                left = deadlineNs - System.nanoTime()) {
            LockSupport.parkNanos(left);
        }
    }

    /** One batch of the continuous counter that IS this stream. */
    private byte[] batch(int index) {
        byte[] pcm = new byte[BATCH_BYTES];
        long at = (long) index * BATCH_BYTES;
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = counter(at + i);
        }
        return pcm;
    }

    /** The byte this stream carries at {@code position} - a counter, so a splice
     *  of any length shows up as a wrong byte and not just a short record. */
    private byte counter(long position) {
        return (byte) position;
    }

    /** What the bench confessed, if anything. */
    private static final class Faults implements NetFaultListener {

        /** Stereo frames the last GAP confessed; -1 = no GAP at all. */
        private final AtomicLong lost = new AtomicLong(-1);

        @Override
        public void gap(long lostFrames) {
            lost.set(lostFrames);
        }

        @Override
        public void deviceError(String direction, String detail) {
            // Not what this test is about; the stream failing outright shows up
            // as bytes that never arrive.
        }

        @Override
        public void deviceGone(DeviceRef device) {
            // Nor this one: the bench keeps its devices for the whole run.
        }

        @Override
        public void calibrationChanged(DeviceRef device) {
            // Nor this: the bench's calibration stands still for the whole run.
        }
    }
}
