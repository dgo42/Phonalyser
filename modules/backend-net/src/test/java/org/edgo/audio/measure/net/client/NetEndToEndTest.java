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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import com.fasterxml.jackson.databind.JsonNode;

import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.enums.OutputChannels;
import org.edgo.audio.measure.net.proto.JsonCodec;
import org.edgo.audio.measure.net.proto.MessageType;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.net.proto.NetMessage;
import org.edgo.audio.measure.net.proto.NetProto;
import org.edgo.audio.measure.sound.DeviceCalibration;
import org.edgo.audio.measure.sound.DeviceRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ONE session on a bench, start to finish: a client arrives, finds the bench's
 * backends, streams its ADC, drives its DDS, recalibrates a device on it, then
 * dies of a stopped heartbeat - and the bench is still a bench afterwards.
 *
 * <p><b>Why it is one long test and not eight.</b>  Every piece of this already
 * has its own test; what none of them can show is the SESSION - that a lock taken
 * in one stage is still the lock a later stage rides, that a calibration written
 * in the middle reaches a client that arrives afterwards, and above all that a
 * client dying with a stream running and a generator playing leaves the bench
 * with nothing held.  Split into independent tests, each would set up the state
 * it wanted and prove exactly the thing that cannot go wrong that way.
 *
 * <p><b>What is real.</b>  The client is the shipping {@code NetConnection} /
 * {@code NetDeviceManager} / {@code NetPcmCapture}, the socket is a real one, and
 * the far end is {@link MockBench} - a peer that speaks the wire, which is this
 * module's entire contract with the other half.  The one thing hand-cranked is
 * the CLIENT's own keepalive clock ({@link FakeTicker}), so spec 4.1's four
 * unanswered pings happen in no time at all.
 *
 * <p><b>Whose death this is.</b>  The original ran this stage the other way round
 * - the server noticed a client that had stopped answering and killed the socket.
 * That is the SERVER's half of spec 4.1 and it is the server module's to prove.
 * Here the bench simply goes quiet with the socket still up, which is the one
 * condition the client's own half exists for, and what is asserted is everything
 * the CLIENT then does: it declares the session dead, stops the stream, tells
 * whoever subscribed exactly once, and leaves nothing behind on either end.
 *
 * <p><b>Nothing sleeps.</b>  Every wait is a condition on what arrived, what the
 * bench was told, or what the far end gave back.
 */
class NetEndToEndTest {

    private static final String SERVER_NAME = "Bench QA403";
    private static final String CLIENT_NAME = "Developer's laptop";
    private static final String OBSERVER_NAME = "Bench tablet";
    private static final String LATE_NAME = "Developer's phone";
    private static final String CLIENT_APP = "Phonalyser desktop test";
    /** How long any wait may take before it counts as a hang. */
    private static final long AWAIT_MS = 10_000;
    /** What the teardown gives the peer to wind its accept loop down.  Short on
     *  purpose: nothing is asserted after it, and the socket library waits out
     *  this whole budget whenever a client went away without a close handshake. */
    private static final long SHUTDOWN_MS = 200;

    private static final int RATE_HZ = MockBench.RATE_48K;
    private static final int BITS = MockBench.BITS_24;
    /** 24-bit stereo: three bytes a sample, two samples a frame. */
    private static final int FRAME_BYTES = 6;
    /** One second of audio in the batches spec 5 describes (~10-100 ms). */
    private static final int BATCHES = 10;
    private static final int BATCH_BYTES = RATE_HZ / BATCHES * FRAME_BYTES;
    private static final int SECOND_BYTES = BATCHES * BATCH_BYTES;
    /** Makes every batch's content differ from every other's, so a dropped,
     *  duplicated or reordered one fails on the bytes and not just on a count. */
    private static final int BATCH_SEED_STEP = 31;

    /** What the BENCH's card store says about its own devices before anybody
     *  connects - deliberately not 1.0, so a client running on the fallback full
     *  scale instead of the bench's own is caught by the value it reports. */
    private static final double IN_FS_LEFT = 1.5;
    private static final double IN_FS_RIGHT = 1.75;
    private static final double OUT_FS_LEFT = 2.0;
    private static final double OUT_FS_RIGHT = 2.5;
    /** What the client recalibrates the bench's input to, mid-session. */
    private static final double NEW_FS_LEFT = 0.316;
    private static final double NEW_FS_RIGHT = 0.318;

    private static final double TONE_HZ = 1_000.0;
    private static final double AMPLITUDE_VRMS = 0.5;
    private static final double DITHER_BITS = 1.0;
    private static final double SWEEP_F0_HZ = 20.0;
    private static final double SWEEP_F1_HZ = 2_000.0;
    private static final int SWEEP_SAMPLES = RATE_HZ / 10;
    private static final double HZ_TOLERANCE = 1e-9;
    private static final double EPS = 1e-9;
    /** The tick that DECLARES the death: the count is checked before the next
     *  ping goes out, so the four unanswered ones are noticed on the fifth. */
    private static final int TICKS_TO_DEATH = NetProto.MAX_MISSED_PINGS + 1;

    private final JsonCodec codec = new JsonCodec();
    private final List<NetConnection> connections = new ArrayList<>();

    private MockBench bench;
    private URI wsUri;

    @BeforeEach
    void startTheBench() {
        bench = new MockBench(codec, SERVER_NAME);
        // Spec 4.3 v1.1: the calibration lives where the device is plugged in,
        // so it is on the bench before any client exists.
        bench.storeCalibration(MockBench.FIRST_INPUT, true, IN_FS_LEFT, IN_FS_RIGHT);
        bench.storeCalibration(MockBench.OUTPUT, false, OUT_FS_LEFT, OUT_FS_RIGHT);
        wsUri = bench.listen(AWAIT_MS);
    }

    @AfterEach
    void stopTheBench() {
        for (NetConnection connection : connections) {
            connection.close(NetCloseReason.BYE);
        }
        bench.shutDown(SHUTDOWN_MS);
    }

    @Test
    void oneSessionFromTheHandshakeToAKeepaliveDeath() throws Exception {
        // ------------------------------------------------------------------
        // 1  The handshake, the bench's backends, and the calibration it keeps
        // ------------------------------------------------------------------
        FakeTicker heartbeat = new FakeTicker();
        NetConnection client = open(CLIENT_NAME, heartbeat);
        assertEquals(NetProto.PROTO_VERSION, client.getProto(),
                "spec 1: the session runs at the version the SERVER chose, which is "
                        + "what the hello answer carries");
        assertEquals(SERVER_NAME, client.getServerName(),
                "and the operator's own name for the bench came with it");
        assertNotNull(client.getServerId(), "spec 2.1: a client keys its remembered "
                + "servers on the installation UUID, never on the address");
        assertTrue(client.getCaps().contains(NetFields.CAP_GEN),
                "spec 4.1: the capability tokens came with it too - a token is a promise, "
                        + "and this bench promised the remote generator");
        assertEquals(NetProto.PING_INTERVAL_MS, heartbeat.getPeriodMs(),
                "spec 4.1: the keepalive starts with the session, at 500 ms");

        String served = servedBackend(client);
        assertTrue(listedBackends(client).contains(served),
                "spec 4.3: backend.list is what a client offers as selectable entries, so "
                        + "a backend it can select must appear there - " + served);
        NetDeviceManager manager = new NetDeviceManager();
        manager.connect(client, select(client, served));
        Faults faults = new Faults();
        manager.setFaultListener(faults);

        NetDeviceRef input = (NetDeviceRef) manager.listInputDevices().get(0);
        assertEquals(MockBench.FIRST_INPUT, input.name());
        DeviceCalibration stored = input.calibration();
        assertNotNull(stored, "spec 4.3 v1.1: the selection itself carries what the "
                + "BENCH's card store holds for its own devices");
        assertEquals(IN_FS_LEFT, stored.fsRmsLeft(), EPS);
        assertEquals(IN_FS_RIGHT, stored.fsRmsRight(), EPS);
        assertEquals(OUT_FS_LEFT,
                ((NetDeviceRef) manager.listOutputDevices().get(0)).calibration().fsRmsLeft(),
                EPS, "in both directions: an output card is the DAC's own full scale");

        // ------------------------------------------------------------------
        // 2  The input stream: the lock, the granted format, and the bytes
        // ------------------------------------------------------------------
        NetPcmCapture capture = (NetPcmCapture) manager.openCapture(input, RATE_HZ, BITS);
        List<byte[]> received = new ArrayList<>();
        AtomicLong receivedBytes = new AtomicLong();
        CountDownLatch second = new CountDownLatch(BATCHES);
        capture.setPcmBatchListener((pcm, validBytes) -> {
            synchronized (received) {
                received.add(Arrays.copyOf(pcm, validBytes));
            }
            receivedBytes.addAndGet(validBytes);
            second.countDown();
        });
        AtomicLong markedAt = new AtomicLong(-1);
        CountDownLatch marked = new CountDownLatch(1);
        capture.setMarkerListener((kind, pcmBytes) -> {
            markedAt.set(pcmBytes);
            marked.countDown();
        });

        capture.open();
        assertEquals(RATE_HZ, Math.round(capture.getFormat().getSampleRate()),
                "spec 4.4: the client re-pins to the format the bench GRANTED");
        assertEquals(BITS, capture.getFormat().getSampleSizeInBits());
        assertTrue(bench.isLocked(MockBench.FIRST_INPUT, true),
                "spec 4.4: a capture requires the input lock, and the open took it");
        capture.startRecording();
        MockBench.Capture adc = bench.getLastCapture();
        assertNotNull(adc, "the client opened no stream on the bench");

        byte[] captured = new byte[SECOND_BYTES];
        for (int i = 0; i < BATCHES; i++) {
            byte[] one = batch(i);
            System.arraycopy(one, 0, captured, i * BATCH_BYTES, BATCH_BYTES);
            adc.feed(one);
        }
        assertTrue(second.await(AWAIT_MS, TimeUnit.MILLISECONDS),
                "only " + received.size() + " of " + BATCHES + " batches arrived");
        assertArrayEquals(captured, joined(received),
                "spec 4.4 puts the bench's NATIVE PCM on the wire so the client decodes "
                        + "it with the same code path a local device feeds - one wrong "
                        + "byte is a wrong measurement");

        // ------------------------------------------------------------------
        // 3  The generator on the bench: its lane, its commands, its state
        // ------------------------------------------------------------------
        DeviceRef output = manager.listOutputDevices().get(0);
        assertEquals(RATE_HZ, manager.openGenerator(output, RATE_HZ, BITS, DITHER_BITS,
                OutputChannels.BOTH), "spec 4.5: gen.open answers the rate it granted");
        MockBench.Generator dac = bench.getLastGenerator();
        assertNotNull(dac, "the client opened no generator on the bench");
        assertEquals(BITS, dac.getBits());
        assertEquals(DITHER_BITS, dac.getDitherBits(),
                "spec 4.5 fixes the dither depth at gen.open - it is the LINE's");
        assertTrue(bench.isLocked(MockBench.OUTPUT, false),
                "spec 4.5: all gen.* require the output-device lock, and the open took it");

        manager.setForm(GenSignalForm.SINE);
        manager.setFrequency(TONE_HZ);
        manager.setAmplitudeVrms(AMPLITUDE_VRMS);
        manager.startGenerator();
        awaitTrue(() -> manager.state().running(),
                "spec 4.5: ev.gen.state is pushed on every state change, and the client "
                        + "believes nothing else about what is emitting");
        assertEquals(TONE_HZ, manager.state().emitHz(), HZ_TOLERANCE,
                "and the frequency it reports is the one the bench says it emits");
        assertEquals(AMPLITUDE_VRMS,
                dac.getConfigAtStart().path(NetFields.AMPLITUDE_VRMS).asDouble(), EPS,
                "spec 4.5: the amplitude is final - the client did the calibration "
                        + "arithmetic, so what crosses is volts RMS and not a fraction");

        // The sweep, and the mark it puts into the stream that is already open.
        manager.stopGenerator();
        manager.setForm(GenSignalForm.LOG_SWEEP);
        manager.setSweepFreqStart(SWEEP_F0_HZ);
        manager.setSweepFreqEnd(SWEEP_F1_HZ);
        manager.setSweepDurationSamples(SWEEP_SAMPLES);
        manager.setSweepLeadInSamples(0);
        manager.setSweepLoop(false);
        long beforeTheSweep = receivedBytes.get();

        manager.startGenerator();

        assertTrue(marked.await(AWAIT_MS, TimeUnit.MILLISECONDS),
                "spec 4.5: starting a sweep injects a sweepStart marker into this "
                        + "connection's open capture streams");
        assertEquals(beforeTheSweep, markedAt.get(),
                "spec 5: the mark falls at the byte boundary of everything the client had "
                        + "already been given, so the first byte after it is the sweep's "
                        + "sample 0 - a record cut anywhere else is shifted in time");

        // ------------------------------------------------------------------
        // 4  Recalibrating the bench's device, on the lock the stream holds
        // ------------------------------------------------------------------
        CountDownLatch changed = new CountDownLatch(1);
        // Registered AFTER the manager's own subscription, so its cache has been
        // rebuilt by the time this fires (the listeners run in order).
        client.addEventListener(event -> {
            if (event.getType() == MessageType.EV_DEVICES_CHANGED) {
                changed.countDown();
            }
        });

        NetMessage written = manager.withDeviceLock(input, () -> client.request(
                input.into(client.newRequest(MessageType.DEVICE_SET_CALIBRATION))
                        .put(NetFields.FS_RMS_LEFT, NEW_FS_LEFT)
                        .put(NetFields.FS_RMS_RIGHT, NEW_FS_RIGHT)));

        assertNotNull(written, "no lock could be taken, so the write never ran");
        assertTrue(written.isOk(), "the bench refused the write: " + written);
        assertTrue(changed.await(AWAIT_MS, TimeUnit.MILLISECONDS),
                "spec 4.3: the write broadcasts, so every client's cal view refreshes");
        assertTrue(capture.isRecording(),
                "and the stream it rode is still running: a write that took and gave "
                        + "back the lock would have closed the capture underneath it");
        assertFalse(adc.isClosed(), "on the bench as well as on this side");
        DeviceCalibration refreshed = ((NetDeviceRef) manager.listInputDevices().get(0))
                .calibration();
        assertEquals(NEW_FS_LEFT, refreshed.fsRmsLeft(), EPS);
        assertEquals(NEW_FS_RIGHT, refreshed.fsRmsRight(), EPS);

        NetConnection observer = open(OBSERVER_NAME, new FakeTicker());
        NetDeviceManager observing = new NetDeviceManager();
        observing.connect(observer, select(observer, served));
        NetDeviceRef observed = (NetDeviceRef) observing.listInputDevices().get(0);
        assertEquals(NEW_FS_LEFT, observed.calibration().fsRmsLeft(), EPS,
                "the rule in one line: a client that connects afterwards arrives "
                        + "already calibrated, because the value lives where the device "
                        + "is plugged in");
        assertEquals(CLIENT_NAME, observing.lockedBy(observed),
                "and it sees who is measuring on it, by name");
        assertEquals(2, bench.sessions(), "two sessions, as the bench counts them");

        // ------------------------------------------------------------------
        // 5  The heartbeat stops: the CLIENT declares its own session dead
        // ------------------------------------------------------------------
        AtomicReference<NetCloseReason> ended = new AtomicReference<>();
        AtomicInteger endings = new AtomicInteger();
        client.addCloseListener(reason -> {
            ended.set(reason);
            endings.incrementAndGet();
        });
        // The bench keeps the socket up and stops answering - the one condition
        // spec 4.1's client half exists for, and one no correct server produces.
        bench.setDeaf(true);

        heartbeat.advance(TICKS_TO_DEATH);

        assertEquals(NetCloseReason.KEEPALIVE_TIMEOUT, ended.get(),
                "spec 4.1: four consecutive unanswered pings - 2 s of silence - and the "
                        + "session is dead, and the modules must be told WHY while their "
                        + "measurement can still be stopped");
        assertEquals(1, endings.get(),
                "once, and only once: a failure reported twice is a failure nobody trusts");
        assertFalse(client.isOpen());
        assertTrue(heartbeat.isStopped(), "a dead session stops pinging");
        assertFalse(capture.isRecording(),
                "and the capture stopped with it, rather than showing a frozen scope");
        assertNull(faults.detail.get(),
                "without a DEVICE error: the session's death is the session's to tell, "
                        + "once, whether three streams were open on it or none at all");
        assertThrows(IllegalStateException.class,
                () -> client.request(client.newRequest(MessageType.DEVICES_LIST)),
                "a request on an ended session fails at once rather than waiting out a "
                        + "timeout the bench will never answer");

        // The client really did drop its socket - which is how the far end learns
        // to free what that session held, and the observer proves it did.
        awaitTrue(() -> bench.sessions() == 1, "the dead client's socket is still open");
        awaitTrue(() -> !bench.isLocked(MockBench.FIRST_INPUT, true),
                "spec 4.1: the dead session's locks are released, so the input the dead "
                        + "client was streaming can be taken by somebody else");
        awaitTrue(() -> !bench.isLocked(MockBench.OUTPUT, false),
                "and the output its generator held");
        DeviceRef otherInput = observing.listInputDevices().get(0);
        DeviceRef otherOutput = observing.listOutputDevices().get(0);
        assertTrue(acquire(observer, otherInput));
        assertTrue(acquire(observer, otherOutput));
        release(observer, otherInput);
        release(observer, otherOutput);

        // ------------------------------------------------------------------
        // 6  The bench is still a bench
        // ------------------------------------------------------------------
        bench.setDeaf(false);
        NetConnection late = open(LATE_NAME, new FakeTicker());
        assertEquals(NetProto.PROTO_VERSION, late.getProto(),
                "a fresh handshake, on the same bench, after all of that");
        NetDeviceManager arriving = new NetDeviceManager();
        arriving.connect(late, select(late, served));
        DeviceRef lateInput = arriving.listInputDevices().get(0);
        assertEquals(MockBench.FIRST_INPUT, lateInput.name());
        assertEquals(NEW_FS_LEFT, ((NetDeviceRef) lateInput).calibration().fsRmsLeft(), EPS,
                "carrying the calibration the whole scenario wrote");
        assertTrue(acquire(late, lateInput),
                "and the device is takeable: nothing this session did left the bench "
                        + "holding anything");

        assertEquals(0, faults.gaps.get(),
                "and nothing was ever lost on the way: the bench confessed no gap");
    }

    // -------------------------------------------------------------------------
    // The client under test
    // -------------------------------------------------------------------------

    /** One connected client, through the shipping handshake, with the keepalive
     *  clock the caller wants to hold. */
    private NetConnection open(String clientName, FakeTicker keepalive) {
        NetConnection connection = new NetConnection(wsUri, clientName, CLIENT_APP, codec,
                keepalive);
        connections.add(connection);
        connection.open(AWAIT_MS);
        return connection;
    }

    /** Spec 4.3's {@code backend.select}: the session commits to one backend and
     *  is answered with that backend's whole entry, devices and all. */
    private JsonNode select(NetConnection connection, String backend) {
        NetMessage answer = connection.request(
                connection.newRequest(MessageType.BACKEND_SELECT)
                        .put(NetFields.BACKEND, backend));
        assertTrue(answer.isOk(), "the bench refused backend.select: " + answer);
        return answer.getData();
    }

    /** The backends spec 4.3's {@code backend.list} offers as selectable. */
    private List<String> listedBackends(NetConnection connection) {
        NetMessage answer = connection.request(
                connection.newRequest(MessageType.BACKEND_LIST));
        assertTrue(answer.isOk(), "the bench refused backend.list: " + answer);
        List<String> names = new ArrayList<>();
        for (JsonNode backend : answer.getData().path(NetFields.BACKENDS)) {
            names.add(backend.path(NetFields.BACKEND).asText());
        }
        return names;
    }

    /** The first backend of {@code devices.list} that has any device at all -
     *  which one a host can operate is the host's business, so it is read off
     *  the wire and never named here. */
    private String servedBackend(NetConnection connection) {
        NetMessage list = connection.request(
                connection.newRequest(MessageType.DEVICES_LIST));
        assertTrue(list.isOk());
        for (JsonNode backend : list.getData().path(NetFields.BACKENDS)) {
            if (!backend.path(NetFields.DEVICES).isEmpty()) {
                return backend.path(NetFields.BACKEND).asText();
            }
        }
        throw new IllegalStateException("this bench serves no device at all, so there "
                + "is nothing to measure with: " + list.getData());
    }

    /** Spec 4.3's exclusive lock, asked for by name: true when the bench granted
     *  it, which is what "the dead client's locks were freed" looks like from
     *  another session. */
    private boolean acquire(NetConnection connection, DeviceRef device) {
        NetDeviceRef ref = (NetDeviceRef) device;
        return connection.request(
                ref.into(connection.newRequest(MessageType.DEVICE_ACQUIRE))).isOk();
    }

    private void release(NetConnection connection, DeviceRef device) {
        NetDeviceRef ref = (NetDeviceRef) device;
        connection.request(ref.into(connection.newRequest(MessageType.DEVICE_RELEASE)));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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

    /** One capture batch of recognisable PCM - see {@link #BATCH_SEED_STEP}. */
    private byte[] batch(int index) {
        byte[] pcm = new byte[BATCH_BYTES];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (byte) (index * BATCH_SEED_STEP + i);
        }
        return pcm;
    }

    /** Everything the pipeline received, in arrival order, as one record. */
    private byte[] joined(List<byte[]> batches) {
        byte[] all = new byte[SECOND_BYTES];
        int at = 0;
        synchronized (batches) {
            for (byte[] one : batches) {
                System.arraycopy(one, 0, all, at, one.length);
                at += one.length;
            }
        }
        assertEquals(SECOND_BYTES, at, "the pipeline received a different number of bytes");
        return all;
    }

    /** Everything that can go wrong on this bench, as the one subscription the
     *  whole client's faults arrive on (spec 4.1, 4.3 and 5). */
    private static final class Faults implements NetFaultListener {

        private final AtomicInteger gaps = new AtomicInteger();
        private final AtomicReference<String> detail = new AtomicReference<>();

        @Override
        public void gap(long lostFrames) {
            gaps.incrementAndGet();
        }

        @Override
        public void deviceError(String direction, String detail) {
            this.detail.set(detail);
        }

        @Override
        public void deviceGone(DeviceRef device) {
            // A device leaving the catalogue is NetLoopbackTest's question.
        }

        @Override
        public void calibrationChanged(DeviceRef device) {
            // So is a calibration that moved on the bench.
        }
    }
}
