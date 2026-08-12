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
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import com.fasterxml.jackson.databind.JsonNode;

import org.edgo.audio.measure.dsp.FftBinSnap;
import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.enums.DeviceFailureReason;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.enums.OutputChannels;
import org.edgo.audio.measure.net.proto.BinaryFrame;
import org.edgo.audio.measure.net.proto.ErrorCode;
import org.edgo.audio.measure.net.proto.JsonCodec;
import org.edgo.audio.measure.net.proto.MessageType;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.net.proto.NetMessage;
import org.edgo.audio.measure.sound.AudioCapture;
import org.edgo.audio.measure.sound.AudioDeviceManager;
import org.edgo.audio.measure.sound.CaptureEndReason;
import org.edgo.audio.measure.sound.DeviceCalibration;
import org.edgo.audio.measure.sound.DeviceRef;
import org.edgo.audio.measure.sound.MarkedCapture;
import org.edgo.audio.measure.sound.RemoteGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CLIENT half of the bridge against a peer that speaks the wire: a real
 * {@link NetConnection} / {@link NetDeviceManager} / {@link NetPcmCapture} on one
 * end of a real socket, and {@link MockBench} on the other.
 *
 * <p><b>Why the far end is a mock and not the server.</b>  This module depends on
 * the wire format and on nothing else - the server is not here in any scope,
 * tests included - so what a test can put on the other end of the socket is a
 * peer that speaks JSON and binary frames, exactly as a database client's tests
 * speak the database's protocol rather than linking its engine.  The mock is not
 * a mirror of the client: it builds every answer from the spec's own field names
 * and knows nothing about the code under test, and what is asserted here is what
 * the CLIENT does with what arrives.
 *
 * <p><b>What that costs, stated once.</b>  Anything that is the SERVER's own
 * decision is no longer proved by this file: whether a bench really drains its
 * send queue before it marks a sweep, whether its backpressure policy drops
 * audio it could have sent, and what its DDS actually renders.  Those belong to
 * the server module's own tests.  What survives is every promise the client
 * makes: what it puts on the wire, what it believes about what comes back, and
 * what it stops when the bench goes wrong.
 *
 * <p><b>The hostile frames are the point.</b>  A GAP, a jumped packet counter and
 * a marker landing exactly between two named batches are frames a healthy server
 * never sends - the first needs a client slow enough to overrun a queue, the
 * second is impossible over intact TCP.  Here they cross the SAME socket the
 * audio does and enter the client's own demux, rather than being handed to it
 * through a back door.
 *
 * <p><b>No test sleeps.</b>  The client's own keepalive gets a {@link FakeTicker}
 * that is never advanced, and every wait is a condition on what arrived.
 */
class NetLoopbackTest {

    private static final String SERVER_NAME = "Bench loopback";
    private static final String CLIENT_NAME = "Developer's laptop";
    private static final String OBSERVER_NAME = "Bench tablet";
    private static final String CLIENT_APP = "Phonalyser desktop test";
    /** How long any wait on the loopback may take before it counts as a hang. */
    private static final long AWAIT_MS = 10_000;
    /** What the teardown gives the peer to wind its accept loop down.  Short on
     *  purpose: nothing is asserted after it, and the socket library waits out
     *  this whole budget whenever a client went away without a close handshake -
     *  which is most of these tests, and which would otherwise be ten idle
     *  seconds each. */
    private static final long SHUTDOWN_MS = 200;

    private static final int RATE_HZ = MockBench.RATE_48K;
    private static final int BITS = MockBench.BITS_24;
    /** 24-bit stereo: three bytes a sample, two samples a frame. */
    private static final int FRAME_BYTES = 6;
    /** One second of audio, delivered the way a backend delivers it: batches of
     *  100 ms, the upper end of the "~10 - 100 ms" spec 5 names. */
    private static final int BATCHES = 10;
    private static final int BATCH_BYTES = RATE_HZ / BATCHES * FRAME_BYTES;
    private static final int SECOND_BYTES = BATCHES * BATCH_BYTES;
    /** Makes every batch's content differ from every other's, so a dropped,
     *  duplicated or reordered one fails on the bytes and not just on a count. */
    private static final int BATCH_SEED_STEP = 31;
    /** A short recognisable payload for the single-frame tests. */
    private static final byte[] ONE_FRAME_PCM = {1, 2, 3, 4, 5, 6};
    /** What the bench says died, in the {@code detail} of spec 4.3. */
    private static final String DEVICE_FAILURE = "Bench input: the interface was unplugged";
    /** What a GAP frame confesses: 100 ms of stereo frames at 48 kHz. */
    private static final long LOST_FRAMES = RATE_HZ / BATCHES;
    /** How far a packet counter jumps in the test that proves a jump is fatal. */
    private static final long COUNTER_JUMP = 5;

    /** The tone the generator tests command - a round number that is deliberately
     *  NOT on the bin grid below, so the snap has something to move. */
    private static final double TONE_HZ = 1_000.0;
    /** The analyzer grid the bench is told about (spec 4.5's {@code gen.fftGrid}). */
    private static final int FFT_SIZE = 16_384;
    /** An absolute corrected frequency, as spec 4.5 defines a trim. */
    private static final double TRIMMED_HZ = 1_000.5;
    private static final double AMPLITUDE_VRMS = 0.5;
    private static final double DITHER_BITS = 1.0;
    /** The sweep the mark test starts - any real chirp will do; what is asserted
     *  is WHERE its mark lands in the byte stream. */
    private static final double SWEEP_F0_HZ = 20.0;
    private static final double SWEEP_F1_HZ = 2_000.0;
    private static final int SWEEP_SAMPLES = RATE_HZ / 10;
    /** The silence the lead-in test commands - a tenth of a second. */
    private static final int LEAD_IN_SAMPLES = RATE_HZ / 10;
    private static final int FADE_SAMPLES = 480;
    /** How many batches are handed over back-to-back before that sweep starts, so
     *  the mark has a stream with real history behind it to land after. */
    private static final int STUFFED_BATCHES = 8;
    /** Frequencies cross the wire as JSON doubles, so they come back exact -
     *  this only guards the decimal round-trip. */
    private static final double HZ_TOLERANCE = 1e-9;
    /** The two full scales the calibration test writes to the bench, unequal so a
     *  payload that crossed the channels fails on the values. */
    private static final double FS_RMS_LEFT = 1.234;
    private static final double FS_RMS_RIGHT = 2.345;
    private static final double EPS = 1e-9;
    /** A rate the bench GRANTS instead of the one asked for - a real analyzer
     *  really does have the last word (spec 4.4), and a bench that always
     *  granted the request could not show whether the client re-pins at all. */
    private static final int GRANTED_RATE_HZ = MockBench.RATE_96K;

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
            connection.close(NetCloseReason.BYE);
        }
        bench.shutDown(SHUTDOWN_MS);
    }

    @Test
    void theCatalogueComesFromTheServerAndTheAudioArrivesByteForByte() throws Exception {
        NetDeviceManager manager = connect(CLIENT_NAME);
        List<DeviceRef> inputs = manager.listInputDevices();
        assertEquals(2, inputs.size(),
                "spec 4.3: one object per device AND direction, and this bench has two "
                        + "inputs");
        assertEquals(1, manager.listOutputDevices().size());
        DeviceRef device = inputs.get(0);
        assertEquals(MockBench.FIRST_INPUT, device.name());
        assertEquals(AudioBackendType.NET, device.carrier(),
                "the ref ROUTES at the net manager, never at the LOCAL manager of the "
                        + "same name - a bench across the room is not this machine's USB");
        assertEquals(MockBench.REMOTE_BACKEND, device.backend().name(),
                "while what it IS is the bench's TRUE backend type - the dual-level "
                        + "answer type-specific control keys on, local or remote alike");
        assertEquals(MockBench.REMOTE_BACKEND, ((NetDeviceRef) device).remoteBackend(),
                "and the raw wire text it arrived as is kept - a server may know a "
                        + "backend this build does not");
        assertFalse(manager.listSupportedFormats(device, false).isEmpty(),
                "spec 4.3 inlines the formats with the device, to avoid a round-trip");
        assertFalse(manager.hasBitDepth(device),
                "spec 4.3's per-device flag, straight off devices.list: this bench offers "
                        + "one sample width, so offering a depth selector for it would be "
                        + "a lie the client has no other way of knowing");

        NetPcmCapture capture = (NetPcmCapture) manager.openCapture(device, RATE_HZ, BITS);
        List<byte[]> delivered = new ArrayList<>();
        CountDownLatch batches = new CountDownLatch(BATCHES);
        capture.setPcmBatchListener((pcm, validBytes) -> {
            synchronized (delivered) {
                delivered.add(Arrays.copyOf(pcm, validBytes));
            }
            batches.countDown();
        });
        capture.open();
        assertEquals(RATE_HZ, Math.round(capture.getFormat().getSampleRate()),
                "spec 4.4: the client re-pins to the format actually granted");
        assertEquals(BITS, capture.getFormat().getSampleSizeInBits());
        capture.startRecording();

        MockBench.Capture stream = bench.getLastCapture();
        assertNotNull(stream, "the client opened no stream on the bench");
        byte[] recorded = new byte[SECOND_BYTES];
        for (int i = 0; i < BATCHES; i++) {
            byte[] batch = batch(i);
            System.arraycopy(batch, 0, recorded, i * BATCH_BYTES, BATCH_BYTES);
            stream.feed(batch);
        }

        assertTrue(batches.await(AWAIT_MS, TimeUnit.MILLISECONDS),
                "only " + delivered.size() + " of " + BATCHES + " batches arrived");
        assertArrayEquals(recorded, joined(delivered),
                "spec 4.4: the payload is the NATIVE PCM byte stream of the server-side "
                        + "capture, so the client decodes it with the same code path a "
                        + "local device feeds - one wrong byte is a wrong measurement");

        capture.stopRecording();
        capture.close();
        assertTrue(stream.isClosed(), "spec 4.4: the stream is closed on the bench");
        assertFalse(bench.isLocked(MockBench.FIRST_INPUT, true),
                "and the device line goes back with it: a lock left behind is a device "
                        + "no other client can ever take again");
    }

    /**
     * Spec 4.7: the audio arrives on the capture's OWN connection, and the
     * control connection carries none of it.
     *
     * <p>That is the whole point of the split, and it is measured from the
     * bench's side because the client cannot see which socket a frame came in
     * on: this bench sends every frame on the socket the {@code capture.attach}
     * arrived on, so a client that never dialled one would receive nothing at
     * all - and a client that started the stream before attaching would be
     * refused {@code NOT_ATTACHED} first.
     */
    @Test
    void theCaptureDialsItsOwnDataConnectionAndTheAudioArrivesOnIt() {
        NetDeviceManager manager = connect(CLIENT_NAME);
        List<byte[]> delivered = new ArrayList<>();
        CountDownLatch batches = new CountDownLatch(1);
        NetPcmCapture capture = (NetPcmCapture) manager.openCapture(
                manager.listInputDevices().get(0), RATE_HZ, BITS);
        capture.setPcmBatchListener((pcm, validBytes) -> {
            synchronized (delivered) {
                delivered.add(Arrays.copyOf(pcm, validBytes));
            }
            batches.countDown();
        });

        capture.open();

        assertEquals(1, bench.sessions(),
                "spec 4: a data connection is not a session - only hello makes one");
        capture.startRecording();
        bench.getLastCapture().feed(batch(0));
        awaitTrue(() -> batches.getCount() == 0,
                "the audio did not arrive on the data connection");
        assertArrayEquals(batch(0), delivered.get(0));

        capture.close();
        assertTrue(manager.getConnection().isOpen(),
                "spec 4.7: closing a capture closes ITS socket and leaves the session "
                        + "running - the control connection outlives every capture on it");
    }

    /**
     * Spec 4.7: an attach the bench refuses is the failure of the
     * {@code capture.open} it belongs to - not a socket error, and not a session
     * that quietly has no audio.
     *
     * <p>And it must cost nothing: the capture is closed on the bench and the
     * device lock goes back, exactly as a refused open does.
     */
    @Test
    void anAttachTheBenchRefusesIsReportedAsTheCaptureOpenFailing() {
        NetDeviceManager manager = connect(CLIENT_NAME);
        bench.setRefuseAttach(true);
        NetPcmCapture capture = (NetPcmCapture) manager.openCapture(
                manager.listInputDevices().get(0), RATE_HZ, BITS);

        assertThrows(IllegalStateException.class, capture::open,
                "the operator is told the capture could not be opened, not that a "
                        + "socket could not be dialled");

        awaitTrue(() -> !bench.isLocked(MockBench.FIRST_INPUT, true),
                "an open that failed must cost nothing - the lock goes back");
        assertTrue(manager.getConnection().isOpen(),
                "and the SESSION survives: a refused attach is answered and its socket "
                        + "closed by the bench, which is expected at both ends");
    }

    /**
     * Spec 4.1's death rule: a data connection that DROPS kills the whole
     * session, both ends.
     *
     * <p>The control connection is perfectly healthy here and answering pings,
     * so nothing else would ever notice that the audio of a running measurement
     * has stopped arriving - the client would sit in front of a frozen scope
     * believing it was measuring.
     */
    @Test
    void aDataConnectionThatDropsEndsTheWholeSession() {
        NetDeviceManager manager = connect(CLIENT_NAME);
        NetPcmCapture capture = openFirstInput(manager);

        bench.getLastCapture().dropDataConnection();

        awaitTrue(() -> !manager.getConnection().isOpen(),
                "spec 4.1: one connection dropping is the session dead");
        awaitTrue(() -> !capture.isRecording(),
                "and the stream stops with it, so no pane goes on drawing a "
                        + "measurement of nothing");
    }

    /**
     * The other half of that rule, and the one a local mark cannot decide: an
     * ORDERLY close of a data connection ends the capture and nothing else -
     * even when this end had not marked the socket first.
     *
     * <p>Spec 4.7 puts the answer on the wire rather than in local state for
     * exactly this reason: the bench closes the socket as it handles a
     * {@code capture.close}, and its {@code resp} travels on the OTHER
     * connection, so the close can arrive first.  A client that required its own
     * mark to have been set in time would end the whole session over an ordinary
     * teardown - and the operator would lose the bench for closing a stream.
     */
    @Test
    void anOrderlyCloseOfADataConnectionEndsNoSession() {
        NetDeviceManager manager = connect(CLIENT_NAME);
        NetPcmCapture capture = openFirstInput(manager);

        bench.getLastCapture().closeDataConnectionNormally();

        assertTrue(manager.getConnection().isOpen(),
                "a NORMAL close is the ordinary end of a stream, not spec 4.1's drop - "
                        + "and no mark of ours was needed to know it");
        capture.close();
        assertTrue(manager.getConnection().isOpen(),
                "and the session is still there afterwards, which is what lets the next "
                        + "measurement reuse it");
    }

    /**
     * Spec 4.7: the planes do not mix, so a control message arriving on a data
     * connection is a protocol error and the session ends.
     *
     * <p>A client that simply HANDLED it would be back where the split started:
     * control traffic queued behind whatever audio that socket is carrying, and
     * a peer that has lost track of which connection it is writing to is not one
     * to keep measuring with (the same rule a malformed frame gets).
     */
    @Test
    void aControlMessageOnADataConnectionEndsTheSession() {
        NetDeviceManager manager = connect(CLIENT_NAME);
        openFirstInput(manager);

        bench.getLastCapture().pushTextOnDataConnection(
                new NetMessage(MessageType.EV_DEVICE_ERROR)
                        .put(NetFields.DIRECTION, NetFields.INPUT)
                        .put(NetFields.DETAIL, DEVICE_FAILURE));

        awaitTrue(() -> !manager.getConnection().isOpen(),
                "the planes do not mix: text after the attach ends the session");
    }

    /**
     * Spec 4.4: "the granted rate may differ (device reality), client re-pins" -
     * so the format a module reads off the capture is the BENCH's answer and not
     * the request.
     *
     * <p>A client that kept reporting what it asked for would have every
     * downstream frequency axis, every snap and every sweep sample count computed
     * against a rate the ADC is not running at, with nothing anywhere to
     * contradict it.
     */
    @Test
    void theClientRePinsToTheRateTheBenchGranted() {
        bench.setGrantRate(GRANTED_RATE_HZ);
        NetDeviceManager manager = connect(CLIENT_NAME);
        NetPcmCapture capture = (NetPcmCapture) manager.openCapture(
                manager.listInputDevices().get(0), RATE_HZ, BITS);

        capture.open();

        assertEquals(GRANTED_RATE_HZ, Math.round(capture.getFormat().getSampleRate()),
                "the bench granted " + GRANTED_RATE_HZ + " Hz and the client asked for "
                        + RATE_HZ + " - what a module reads must be the bench's answer");
        assertEquals(BITS, capture.getFormat().getSampleSizeInBits(),
                "the sample WIDTH is another matter: it is baked into the decoder, so a "
                        + "stream of a different width is refused rather than re-pinned");
        capture.close();
    }

    /**
     * Spec 4.3 v1.1: a device arrives with the calibration the BENCH stores for
     * it, and a client holding the device may write it - after which the
     * catalogue this manager holds carries the new values without asking.
     */
    @Test
    void aCalibrationWrittenToTheBenchComesBackWithEveryDeviceListing() throws Exception {
        NetDeviceManager manager = connect(CLIENT_NAME);
        List<DeviceRef> before = manager.listInputDevices();
        NetDeviceRef device = (NetDeviceRef) before.get(0);
        assertNull(device.calibration(),
                "the bench has no card for it yet, so cal is null and the client "
                        + "falls back to its own defaults");
        NetConnection session = manager.getConnection();
        assertTrue(session.request(device.into(session.newRequest(
                MessageType.DEVICE_ACQUIRE))).isOk(), "the device must be taken first");
        // Registered AFTER the manager's own subscription, so the cache has been
        // rebuilt by the time this fires (the listeners run in order).
        CountDownLatch changed = new CountDownLatch(1);
        session.addEventListener(event -> {
            if (event.getType() == MessageType.EV_DEVICES_CHANGED) {
                changed.countDown();
            }
        });

        NetMessage written = session.request(device.into(
                session.newRequest(MessageType.DEVICE_SET_CALIBRATION))
                .put(NetFields.FS_RMS_LEFT, FS_RMS_LEFT)
                .put(NetFields.FS_RMS_RIGHT, FS_RMS_RIGHT));

        assertTrue(written.isOk(), "the bench refused the write: " + written);
        assertEquals(FS_RMS_LEFT, bench.storedLeft(MockBench.FIRST_INPUT, true), EPS,
                "the value really crossed the wire and reached the far end's own store");
        assertTrue(changed.await(AWAIT_MS, TimeUnit.MILLISECONDS),
                "spec 4.3: the write broadcasts, so every client's cal view refreshes");
        List<DeviceRef> after = manager.listInputDevices();
        assertEquals(before, after,
                "a calibration is not an identity: the refs must still compare equal "
                        + "across the rebuild, or the lock this session holds on that "
                        + "very device would be orphaned by its own write");
        DeviceCalibration cached = ((NetDeviceRef) after.get(0)).calibration();
        assertNotNull(cached, "and the values arrived with the event, unasked");
        assertEquals(FS_RMS_LEFT, cached.fsRmsLeft(), EPS);
        assertEquals(FS_RMS_RIGHT, cached.fsRmsRight(), EPS);
        JsonNode listed = session.request(session.newRequest(MessageType.DEVICES_LIST))
                .getData().path(NetFields.BACKENDS).path(0).path(NetFields.DEVICES).path(0)
                .path(NetFields.CAL);
        assertEquals(FS_RMS_LEFT, listed.path(NetFields.FS_RMS_LEFT).asDouble(), EPS,
                "and devices.list answers the stored values to anyone who asks - the "
                        + "next client arrives already calibrated");
        assertEquals(FS_RMS_RIGHT, listed.path(NetFields.FS_RMS_RIGHT).asDouble(), EPS);
    }

    /**
     * The bench says whose the full scales ARE, and the client carries the
     * answer on the device it hands out.
     *
     * <p>It is what makes a Calibrate dialog open read-only for a remote analyzer
     * instead of taking a typed value the bench can only answer
     * {@code BAD_REQUEST} to - after the operator has already measured.
     */
    @Test
    void aDeviceSaysWhetherItsFullScalesAreItsOwn() throws Exception {
        bench.deviceOwnedCalibration(MockBench.FIRST_INPUT, true);
        NetDeviceManager manager = connect(CLIENT_NAME);

        List<DeviceRef> inputs = manager.listInputDevices();

        assertTrue(((NetDeviceRef) inputs.get(0)).calFromDevice(),
                "the analyzer reads them from its own EEPROM and neither side may "
                        + "write them");
        assertFalse(((NetDeviceRef) inputs.get(1)).calFromDevice(),
                "an ordinary bench device stays calibratable - the flag is per "
                        + "device and direction, not per bench");
    }

    /**
     * A {@code cal} that MOVED on the bench is not merely cached - the client is
     * told, so whatever is measuring on that device can rescale.
     *
     * <p>This is another operator recalibrating (or re-ranging, or re-binding) the
     * very device this client is streaming from: what every reading MEANS changes,
     * and a client that waited for its next device open would go on showing volts
     * computed against a full scale the bench no longer holds.
     */
    @Test
    void aCalibrationThatMovesOnTheBenchIsReportedAndNotJustCached() throws Exception {
        NetDeviceManager manager = connect(CLIENT_NAME);
        Faults faults = new Faults();
        manager.setFaultListener(faults);
        assertNull(((NetDeviceRef) manager.listInputDevices().get(0)).calibration());

        bench.recalibrate(MockBench.FIRST_INPUT, true, FS_RMS_LEFT, FS_RMS_RIGHT);

        assertTrue(waitFor(() -> !faults.recalibrated.isEmpty()),
                "the broadcast carried a new cal for a device this client knows - "
                        + "nothing else is going to tell it");
        DeviceRef reported = faults.recalibrated.get(0);
        assertEquals(MockBench.FIRST_INPUT, reported.name());
        assertEquals(FS_RMS_LEFT, reported.calibration().fsRmsLeft(), EPS,
                "and it is reported with the NEW values, so the listener needs no "
                        + "second look at the catalogue");

        // The SAME payload again - a lock change or a hot-plug re-sends it whole.
        bench.recalibrate(MockBench.FIRST_INPUT, true, FS_RMS_LEFT, FS_RMS_RIGHT);
        bench.detach(MockBench.SECOND_INPUT, true);

        assertTrue(waitFor(() -> manager.listInputDevices().size() == 1),
                "the second broadcast was processed");
        assertEquals(1, faults.recalibrated.size(),
                "a calibration that did NOT move is not a change - re-applying it on "
                        + "every event would rescale a running measurement for nothing");
    }

    /**
     * The load-bearing branch of the per-device lock: a write on a device this
     * session ALREADY holds rides that lock and gives nothing back.
     *
     * <p>Spec 4.3 makes a release "also close any open stream/generator on it" -
     * which the bench here does - and the far end answers a repeat acquire by the
     * same session with success, so a write that took and released around itself
     * would tear down the very capture the operator is calibrating against,
     * orderly enough that no {@code ev.device.error} follows and the panes just
     * freeze.
     *
     * <p>The catalogue is deliberately REBUILT in between.  Every ref the manager
     * hands out afterwards is a new object, and the lock register is keyed by ref:
     * this is what makes the calibration a ref carries excluded from its identity
     * load-bearing rather than a nicety.
     */
    @Test
    void aWriteOnAHeldDeviceRidesThatLockAndLeavesTheStreamRunning() throws Exception {
        NetDeviceManager manager = connect(CLIENT_NAME);
        DeviceRef device = manager.listInputDevices().get(0);
        NetPcmCapture capture = (NetPcmCapture) manager.openCapture(device, RATE_HZ, BITS);
        CountDownLatch delivered = new CountDownLatch(1);
        capture.setPcmBatchListener((pcm, validBytes) -> delivered.countDown());
        capture.open();                      // the input lock lands in the register
        capture.startRecording();
        MockBench.Capture stream = bench.getLastCapture();
        assertNotNull(stream, "the client opened no stream on the bench");
        // Registered AFTER the manager's own subscription, so the cache has been
        // rebuilt by the time this fires (the listeners run in order).
        CountDownLatch changed = new CountDownLatch(1);
        manager.getConnection().addEventListener(event -> {
            if (event.getType() == MessageType.EV_DEVICES_CHANGED) {
                changed.countDown();
            }
        });
        // Somebody else takes a device of the same bench: a lock change, which
        // spec 4.3 broadcasts to every session as the full devices.list payload.
        NetDeviceManager other = connect(OBSERVER_NAME);
        NetConnection otherSession = other.getConnection();
        NetDeviceRef otherOutput = (NetDeviceRef) other.listOutputDevices().get(0);
        assertTrue(otherSession.request(otherOutput.into(
                otherSession.newRequest(MessageType.DEVICE_ACQUIRE))).isOk());
        assertTrue(changed.await(AWAIT_MS, TimeUnit.MILLISECONDS),
                "the catalogue was never rebuilt, so this proves nothing");

        NetDeviceRef rebuilt = (NetDeviceRef) manager.listInputDevices().get(0);
        assertNotSame(device, rebuilt, "the ref really is a new object");
        NetConnection session = manager.getConnection();
        NetMessage written = manager.withDeviceLock(rebuilt, () -> session.request(
                rebuilt.into(session.newRequest(MessageType.DEVICE_SET_CALIBRATION))
                        .put(NetFields.FS_RMS_LEFT, FS_RMS_LEFT)
                        .put(NetFields.FS_RMS_RIGHT, FS_RMS_RIGHT)));

        assertNotNull(written, "no lock could be taken, so the write never ran");
        assertTrue(written.isOk(), "the bench refused the write: " + written);
        assertFalse(stream.isClosed(),
                "the write gave back a lock it was only borrowing, and the bench closed "
                        + "the capture with it");
        stream.feed(batch(0));
        assertTrue(delivered.await(AWAIT_MS, TimeUnit.MILLISECONDS),
                "and the stream stopped delivering");
        capture.close();
    }

    /**
     * The first selection of a bench fills its device combos, with nothing asked
     * of the server afterwards - spec 4.3 shapes {@code backend.select}'s answer
     * as the selected backend's whole {@code devices.list} entry precisely so that
     * "one round-trip fills the device combos".
     *
     * <p>The session is closed before anything is listed, which is what makes the
     * assertion mean something: a manager that still fetched its own
     * {@code devices.list} on first use would answer nothing here - and that fetch
     * was a window for an {@code ev.devices.changed} to land in and decide what
     * the operator saw (a QA403's combos stayed empty until the bench was
     * selected a second time).
     */
    @Test
    void theSelectItselfFillsTheCombos() {
        NetConnection connection = open(CLIENT_NAME);
        NetDeviceManager manager = new NetDeviceManager();

        manager.connect(connection, select(connection, servedBackend(connection)));
        connection.close(NetCloseReason.BYE);

        assertEquals(2, manager.listInputDevices().size(),
                "the bench's inputs came with the selection, so there is nothing left "
                        + "to ask a server that is no longer there");
        assertEquals(1, manager.listOutputDevices().size());
    }

    /**
     * Spec 4.3: {@code ev.devices.changed} carries the FULL {@code devices.list}
     * payload, so the catalogue is rebuilt from it as it comes - including a
     * payload that no longer names this backend, and one that names no backend at
     * all.  A client that second-guessed the event would go on offering devices
     * nothing can be opened on.
     */
    @Test
    void theEventIsTakenAsItComesAndCanEmptyTheCombos() {
        NetDeviceManager manager = connect(CLIENT_NAME);
        assertFalse(manager.listInputDevices().isEmpty(), "the selection filled them");

        // A payload that DOES name backends and not this one: the bench saying
        // this backend is no longer served.
        bench.pushDevicesChanged(codec.toNode(List.of(Map.of(
                NetFields.BACKEND, AudioBackendType.NET.name(),
                NetFields.DEVICES, List.of()))));

        awaitTrue(() -> manager.listInputDevices().isEmpty(),
                "the selected backend is not in a list that names others, so it really "
                        + "is gone and the combos must not go on offering it");
    }

    /** And the degenerate case of the same rule: a server with nothing left to
     *  serve says so with an empty array, which is a statement and not a message
     *  to be ignored. */
    @Test
    void aServerThatServesNothingEmptiesTheCombosToo() {
        NetDeviceManager manager = connect(CLIENT_NAME);
        assertFalse(manager.listInputDevices().isEmpty(), "the selection filled them");

        bench.pushDevicesChanged(codec.toNode(List.of()));

        awaitTrue(() -> manager.listInputDevices().isEmpty(),
                "spec 4.3: the event carries the full devices.list payload, so an empty "
                        + "one is a bench with no backends left");
    }

    /**
     * Spec 4.3's catalogue refresh, asked the question only this client can
     * answer: is the device I am MEASURING on still in it?
     *
     * <p>The bench detaches one input the client does not hold and then the one
     * it does, in that order and on the one socket, so a client that reported
     * every disappearance would have named the wrong device first.  Before this,
     * the event was swallowed whole - the list was replaced at DEBUG level and
     * an empty one accepted in silence - and the bench proof was a server that
     * logged its QA40x session discarded, broadcast the change, and watched the
     * desktop go on generating and sweeping against nothing.
     */
    @Test
    void aHeldDeviceLeavingTheCatalogueIsReportedAndAnUnheldOneIsNot() {
        NetDeviceManager manager = connect(CLIENT_NAME);
        Faults faults = subscribe(manager);
        openFirstInput(manager);                       // takes the FIRST input's lock

        bench.detach(MockBench.SECOND_INPUT, true);    // nobody here holds it
        bench.detach(MockBench.FIRST_INPUT, true);     // and this one is in use

        awaitTrue(() -> faults.gone.contains(MockBench.FIRST_INPUT),
                "a device this client holds left the bench and nothing was said");
        assertEquals(List.of(MockBench.FIRST_INPUT), List.copyOf(faults.gone),
                "and ONLY that one: a device nobody here opened is the bench's own "
                        + "business, not a failure to stop a measurement over");
    }

    /**
     * Spec 4.3's lock, given back on the wire when this backend lets a server go
     * - while the session is still there to receive it.
     *
     * <p>The generator's device always went back with its lane; a CAPTURE's lock
     * was only dropped from the client's register, and could not be given back
     * afterwards either, because the session reference was cleared first and a
     * release with no session sends nothing.  The device then stayed taken for
     * the rest of the session: no other client - and no later selection of this
     * one - could open it.
     */
    @Test
    void disconnectingGivesEveryHeldLockBackWhileTheSessionCanStillCarryIt() {
        NetDeviceManager manager = connect(CLIENT_NAME);
        openFirstInput(manager);
        awaitTrue(() -> bench.isLocked(MockBench.FIRST_INPUT, true),
                "the capture takes the input lock spec 4.4 requires");

        manager.disconnect();

        assertFalse(bench.isLocked(MockBench.FIRST_INPUT, true),
                "a lock left behind is a device no other client can take until the "
                        + "session dies");
    }

    /**
     * The same teardown reached the way a BACKEND SWITCH reaches it:
     * {@code AudioBackend.setActive} calls {@link AudioDeviceManager#shutdown()}
     * on the carrier being left, and the SPI's default is a no-op because an
     * ordinary sound card's handles die with the process.  A bench's do not -
     * they live on another machine - so without the override, moving to a local
     * backend left the far end holding this client's locks and running its
     * generator lane.  Called through the INTERFACE on purpose: that is the only
     * thing production calls, and a missing override passes every other test.
     */
    @Test
    void switchingAwayFromTheBenchTearsItDownThroughTheSpisShutdown() {
        NetDeviceManager manager = connect(CLIENT_NAME);
        openFirstInput(manager);
        AudioDeviceManager spi = manager;

        spi.shutdown();

        assertFalse(bench.isLocked(MockBench.FIRST_INPUT, true),
                "the bench must not go on holding a DAC or an ADC for a client that "
                        + "has moved to local hardware");
        assertTrue(manager.listInputDevices().isEmpty(),
                "and nothing may go on offering devices that can no longer be opened");
    }

    @Test
    void aGapIsForwardedAsADiscontinuityAndTheStreamGoesOn() {
        NetDeviceManager manager = connect(CLIENT_NAME);
        Faults faults = subscribe(manager);
        NetPcmCapture capture = openFirstInput(manager);
        AtomicInteger dispatched = new AtomicInteger();
        capture.setPcmBatchListener((pcm, validBytes) -> dispatched.incrementAndGet());
        MockBench.Capture stream = bench.getLastCapture();

        stream.gap(LOST_FRAMES);
        awaitTrue(() -> faults.lost.get() == LOST_FRAMES,
                "spec 5: a GAP is the server's explicit confession, so the client can "
                        + "reset its averaging instead of silently splicing");
        assertNull(faults.detail.get(), "a GAP is the HONEST path, not a stream failure");

        stream.feed(ONE_FRAME_PCM);
        awaitTrue(() -> dispatched.get() == 1,
                "spec 5 counts a GAP as a frame of its own, so the PCM after it is in "
                        + "sequence and the stream carries on");
        assertTrue(capture.isRecording());

        capture.close();
    }

    /**
     * Spec 5's MARKER, as the sweep-bounded assembly of spec 6 reads it: the
     * frame is delivered ONCE, and the position it carries is the byte boundary
     * it fell on - "the first PCM byte AFTER this frame is aligned with sweep
     * output sample 0".
     *
     * <p>Everything the assembler was handed before that count belongs to
     * whatever the bench was doing beforehand.  A boundary reported one batch
     * early or late would have the record start on the wrong sample and the
     * deconvolution smear.
     */
    @Test
    void aMarkerIsDeliveredOnceAtTheByteBoundaryItFellOn() {
        NetDeviceManager manager = connect(CLIENT_NAME);
        NetPcmCapture capture = openFirstInput(manager);
        AtomicInteger dispatched = new AtomicInteger();
        capture.setPcmBatchListener((pcm, validBytes) -> dispatched.incrementAndGet());
        List<long[]> marks = new ArrayList<>();
        capture.setMarkerListener((kind, pcmBytes) -> {
            synchronized (marks) {
                marks.add(new long[] {kind, pcmBytes});
            }
        });
        MockBench.Capture stream = bench.getLastCapture();

        // One batch BEFORE the sweep, then the mark, then the record's first.
        stream.feed(ONE_FRAME_PCM);
        stream.mark(BinaryFrame.MARKER_SWEEP_START);
        stream.feed(ONE_FRAME_PCM);

        awaitTrue(() -> dispatched.get() == 2,
                "spec 5 counts a MARKER as a frame of its own, so the PCM after it "
                        + "is in sequence and the stream carries on");
        synchronized (marks) {
            assertEquals(1, marks.size(), "one frame, one call");
            assertEquals(BinaryFrame.MARKER_SWEEP_START, marks.get(0)[0],
                    "the kind is passed through unread, so a kind this build does not "
                            + "know still reaches the caller");
            assertEquals(ONE_FRAME_PCM.length, marks.get(0)[1],
                    "the boundary is what the pipeline had already been given - the "
                            + "batch before the sweep, and not the one after it");
        }
        assertTrue(capture.isRecording());

        capture.close();
    }

    /**
     * Byte positions are PER-OPEN: a reopened stream counts from zero again.
     *
     * <p>A consumer that cached a position across a reopen would cut its record
     * at a boundary belonging to a measurement that is already over - the record
     * would be the right length, full of signal, and taken from the wrong place.
     */
    @Test
    void theByteCountAMarkerIsReportedAgainstRestartsWithEveryOpen() {
        NetDeviceManager manager = connect(CLIENT_NAME);
        DeviceRef device = manager.listInputDevices().get(0);
        AtomicLong markedAt = new AtomicLong(-1);

        NetPcmCapture first = (NetPcmCapture) manager.openCapture(device, RATE_HZ, BITS);
        first.setPcmBatchListener((pcm, validBytes) -> { });
        first.setMarkerListener((kind, pcmBytes) -> markedAt.set(pcmBytes));
        first.open();
        first.startRecording();
        MockBench.Capture firstStream = bench.getLastCapture();
        firstStream.feed(batch(0));
        firstStream.mark(BinaryFrame.MARKER_SWEEP_START);
        awaitTrue(() -> markedAt.get() == BATCH_BYTES,
                "the first stream counted the batch it delivered");
        first.close();

        NetPcmCapture second = (NetPcmCapture) manager.openCapture(device, RATE_HZ, BITS);
        second.setPcmBatchListener((pcm, validBytes) -> { });
        second.setMarkerListener((kind, pcmBytes) -> markedAt.set(pcmBytes));
        second.open();
        second.startRecording();
        MockBench.Capture secondStream = bench.getLastCapture();
        assertNotSame(firstStream, secondStream, "the bench opened a second stream");
        secondStream.mark(BinaryFrame.MARKER_SWEEP_START);

        awaitTrue(() -> markedAt.get() == 0,
                "the second open starts its count at zero: a mark on its first frame is "
                        + "at byte 0, not at " + BATCH_BYTES + " carried over from a "
                        + "measurement that is already over");
        second.close();
    }

    /**
     * The client's half of the promise the whole sweep record rests on: the
     * position it reports is what the PIPELINE had been given when the mark
     * arrived - every batch it had already handed over, and none it had not.
     *
     * <p>The stream is deliberately stuffed with 0.8 s of audio and the client's
     * own reader is held inside the batch listener while the sweep is started
     * from another thread, so the frames really are queued in the socket when the
     * mark is written behind them.  What is asserted is that the client counted
     * every one of them before reporting the boundary.
     *
     * <p><b>What is no longer proved here:</b> whether a real server drains its
     * own send queue before it marks.  That promise is the server's, and this
     * module cannot see it; the mock writes the mark after the audio because the
     * spec says a bench must.
     */
    @Test
    void theMarkIsReportedAfterEveryByteTheClientHadAlreadyBeenGiven() throws Exception {
        NetDeviceManager manager = connect(CLIENT_NAME);
        DeviceRef input = manager.listInputDevices().get(0);
        NetPcmCapture capture = (NetPcmCapture) manager.openCapture(input, RATE_HZ, BITS);
        AtomicLong markedAt = new AtomicLong(-1);
        CountDownLatch marked = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        AtomicInteger delivered = new AtomicInteger();
        capture.setPcmBatchListener((pcm, validBytes) -> {
            delivered.incrementAndGet();
            await(released);
        });
        capture.setMarkerListener((kind, pcmBytes) -> {
            markedAt.set(pcmBytes);
            marked.countDown();
        });
        capture.open();
        capture.startRecording();
        MockBench.Capture stream = bench.getLastCapture();
        assertNotNull(stream, "the client opened no stream on the bench");

        DeviceRef output = manager.listOutputDevices().get(0);
        manager.openGenerator(output, RATE_HZ, BITS, DITHER_BITS, OutputChannels.BOTH);
        manager.setForm(GenSignalForm.LOG_SWEEP);
        manager.setSweepFreqStart(SWEEP_F0_HZ);
        manager.setSweepFreqEnd(SWEEP_F1_HZ);
        manager.setSweepDurationSamples(SWEEP_SAMPLES);
        for (int i = 0; i < STUFFED_BATCHES; i++) {
            stream.feed(batch(i));
        }

        Thread starter = new Thread(manager::startGenerator, "test-gen-start");
        starter.start();
        awaitParked(starter);
        int held = delivered.get();
        released.countDown();

        assertTrue(marked.await(AWAIT_MS, TimeUnit.MILLISECONDS),
                "spec 4.5: starting a sweep injects a sweepStart marker into this "
                        + "connection's open capture streams - none reached the assembler");
        assertTrue(held <= 1,
                "the consumer was not actually held (" + held + " of " + STUFFED_BATCHES
                        + " batches through): the reader blocks INSIDE the first batch, so "
                        + "anything more means the frames were not queued behind it and "
                        + "this proves nothing");
        assertEquals((long) STUFFED_BATCHES * BATCH_BYTES, markedAt.get(),
                "the mark fell AFTER every byte the client had already been handed, so "
                        + "sample 0 of the sweep really is the next byte and not one a "
                        + "batch earlier");

        starter.join(AWAIT_MS);
        manager.closeGenerator();
        capture.close();
    }

    /** Waits until {@code t} is parked - for the generator start, that means its
     *  request is on the wire and the thread is waiting for an answer only the
     *  released reader can deliver.  A condition, not a sleep. */
    private void awaitParked(Thread t) {
        long deadline = System.currentTimeMillis() + AWAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            Thread.State state = t.getState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new IllegalStateException(t.getName() + " never got as far as waiting for "
                + "the bench's answer");
    }

    /** Spins until {@code condition} holds or the wire budget runs out - for what
     *  a broadcast makes true on the READER thread, which no latch of the test's
     *  own is registered on. */
    private boolean waitFor(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + AWAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.onSpinWait();
        }
        return condition.getAsBoolean();
    }

    /** Blocks this thread on {@code gate}, restoring the interrupt flag rather
     *  than swallowing it - the capture's reader thread runs this. */
    private void await(CountDownLatch gate) {
        try {
            gate.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The other half of what a record assembler has to hear: a GAP goes to it AS
     * WELL as to the bench's fault hub, and the two are different questions.
     *
     * <p>The hub surfaces the loss and has the running analyzers reset their
     * averaging, which is what a stream that carries on needs.  A sweep record
     * being cut out of that same stream cannot carry on - spliced across a hole
     * it deconvolves into a response nothing has - so it is told at the byte
     * position the hole falls on and fails its own measurement.
     */
    @Test
    void aGapReachesTheRecordAssemblerAndTheFaultHubBoth() {
        NetDeviceManager manager = connect(CLIENT_NAME);
        Faults faults = subscribe(manager);
        NetPcmCapture capture = openFirstInput(manager);
        capture.setPcmBatchListener((pcm, validBytes) -> { });
        Marks marks = new Marks();
        capture.setMarkerListener(marks);
        MockBench.Capture stream = bench.getLastCapture();

        stream.feed(ONE_FRAME_PCM);
        stream.gap(LOST_FRAMES);

        awaitTrue(() -> marks.lost.get() == LOST_FRAMES,
                "the assembler hears the confession it has to fail on");
        assertEquals(ONE_FRAME_PCM.length, marks.at.get(),
                "at the byte boundary the hole falls on, in the same bytes the batches "
                        + "carry");
        awaitTrue(() -> faults.lost.get() == LOST_FRAMES,
                "and the hub still hears it: a scope on the same stream resets its "
                        + "averaging rather than splicing");
        assertTrue(capture.isRecording(), "a GAP is the honest path, not a broken stream");

        capture.close();
    }

    @Test
    void aStreamNobodyIsAssemblingCountsItsMarkersAndDropsThem() {
        NetDeviceManager manager = connect(CLIENT_NAME);
        Faults faults = subscribe(manager);
        NetPcmCapture capture = openFirstInput(manager);
        AtomicInteger dispatched = new AtomicInteger();
        capture.setPcmBatchListener((pcm, validBytes) -> dispatched.incrementAndGet());
        MockBench.Capture stream = bench.getLastCapture();

        stream.mark(BinaryFrame.MARKER_SWEEP_START);
        stream.unknownFrame();
        stream.feed(ONE_FRAME_PCM);

        awaitTrue(() -> dispatched.get() == 1,
                "a scope or an FFT has no use for the mark, and a frame type this build "
                        + "does not know is skipped whole (spec 1) - an unsubscribed "
                        + "stream must go on exactly as it did before the seam existed");
        assertNull(faults.detail.get(),
                "and both were COUNTED: spec 5's counter runs over frames of EVERY type, "
                        + "so a skipped one that did not advance it would make the next "
                        + "frame read as transport loss");
        assertTrue(capture.isRecording());

        capture.close();
    }

    @Test
    void aJumpedPacketCounterStopsTheStream() {
        NetDeviceManager manager = connect(CLIENT_NAME);
        Faults faults = subscribe(manager);
        NetPcmCapture capture = openFirstInput(manager);
        AtomicInteger dispatched = new AtomicInteger();
        AtomicReference<CaptureEndReason> ended = new AtomicReference<>();
        capture.setPcmBatchListener(new AudioCapture.PcmBatchListener() {
            @Override public void accept(byte[] pcm, int validBytes) {
                dispatched.incrementAndGet();
            }
            @Override public void captureEnded(CaptureEndReason reason) {
                ended.set(reason);
            }
        });
        MockBench.Capture stream = bench.getLastCapture();

        stream.feed(ONE_FRAME_PCM);
        awaitTrue(() -> dispatched.get() == 1, "the first frame never arrived");

        stream.feedAt(COUNTER_JUMP, ONE_FRAME_PCM);
        awaitTrue(() -> ended.get() != null,
                "spec 5: intact TCP neither loses nor reorders, so a counter jump is a "
                        + "protocol error the client ends the stream on - terminally, on "
                        + "the same seam the data travelled");
        assertFalse(capture.isRecording(), "and the stream really did stop");
        assertEquals(1, dispatched.get(), "the jumped frame is not measurement data");
        assertNull(faults.detail.get(),
                "and nothing goes sideways any more: the consumers above learn from "
                        + "the data seam, not from a session-level fault fan-out");

        stream.feed(ONE_FRAME_PCM);
        awaitTrue(() -> bench.receivedOf(MessageType.CAPTURE_STOP).size() == 1,
                "a broken stream tells the bench to stop sending");
        assertEquals(1, dispatched.get(),
                "a broken stream stays broken - nothing after the jump reaches the "
                        + "pipeline");

        capture.close();
    }

    @Test
    void whatTheServerQueuedBeforeTheStopIsCountedAndDropped() {
        NetDeviceManager manager = connect(CLIENT_NAME);
        Faults faults = subscribe(manager);
        NetPcmCapture capture = openFirstInput(manager);
        AtomicInteger dispatched = new AtomicInteger();
        capture.setPcmBatchListener((pcm, validBytes) -> dispatched.incrementAndGet());
        AtomicLong markedAt = new AtomicLong(-1);
        capture.setMarkerListener((kind, pcmBytes) -> markedAt.set(pcmBytes));
        MockBench.Capture stream = bench.getLastCapture();

        stream.feed(ONE_FRAME_PCM);
        awaitTrue(() -> dispatched.get() == 1, "the first frame never arrived");

        capture.stopRecording();
        stream.feed(batch(0));
        stream.mark(BinaryFrame.MARKER_SWEEP_START);
        awaitTrue(() -> markedAt.get() >= 0, "the marker behind the dropped batch is gone");
        assertEquals(1, dispatched.get(),
                "spec 4.4's stop is answered by the server's REQUEST worker while its "
                        + "audio worker is still draining queued batches, so frames DO "
                        + "arrive after stopRecording() returned - and every local backend "
                        + "joins its consume thread there, so none of them may reach the "
                        + "module");
        assertEquals(ONE_FRAME_PCM.length, markedAt.get(),
                "and the batch that never reached the pipeline is not in the count "
                        + "either: a position is what was DELIVERED, so a dropped batch "
                        + "that moved it would put sample 0 a whole batch too late");
        assertNull(faults.detail.get(),
                "but both frames were COUNTED: dropping them from the counter would read "
                        + "as transport loss the moment the stream resumed");

        capture.startRecording();
        stream.feed(ONE_FRAME_PCM);
        awaitTrue(() -> dispatched.get() == 2,
                "and the counter is where the stop left it (spec 4.4: \"counters keep "
                        + "their values\"), so the stream simply carries on");
        assertNull(faults.detail.get());

        capture.close();
    }

    @Test
    void aDeviceErrorFromTheBenchStopsTheStreamAndReachesTheOperator() {
        NetDeviceManager manager = connect(CLIENT_NAME);
        Faults faults = subscribe(manager);
        NetPcmCapture capture = openFirstInput(manager);

        bench.pushDeviceError(NetFields.INPUT, DEVICE_FAILURE);

        awaitTrue(() -> DEVICE_FAILURE.equals(faults.detail.get()),
                "spec 4.3: the client surfaces it exactly like a local device error - "
                        + "message + stop the affected modules");
        assertEquals(NetFields.INPUT, faults.direction.get());
        assertTrue(manager.getConnection().isOpen(),
                "spec 4.7: the event arrived on the CONTROL connection - the same event "
                        + "pushed down the capture's data connection is a protocol error "
                        + "that ends the session, so a session still open is the proof it "
                        + "took the right socket");
        awaitTrue(() -> !capture.isRecording(),
                "and the stream really did stop: the server closed its side, so a client "
                        + "that stayed 'live' would show a frozen scope with no audio and "
                        + "no explanation");

        capture.close();
    }

    /** And the mirror rule: an OUTPUT lane failing is not this stream's business.
     *  A capture that stopped on it would take the operator's measurement down
     *  for a fault on the other half of the bench. */
    @Test
    void anOutputLaneFailureDoesNotStopACapture() {
        NetDeviceManager manager = connect(CLIENT_NAME);
        Faults faults = subscribe(manager);
        NetPcmCapture capture = openFirstInput(manager);

        bench.pushDeviceError(NetFields.OUTPUT, DEVICE_FAILURE);

        awaitTrue(() -> DEVICE_FAILURE.equals(faults.detail.get()),
                "the operator is still told - a dead DAC is a dead DAC");
        assertTrue(capture.isRecording(),
                "but the ADC is still an ADC: the input stream carries on");

        capture.close();
    }

    @Test
    void aSessionThatDiesStopsTheStreamAndLeavesTheTellingToTheSession() {
        NetDeviceManager manager = connect(CLIENT_NAME);
        NetConnection connection = manager.getConnection();
        Faults faults = subscribe(manager);
        NetPcmCapture capture = openFirstInput(manager);

        connection.close(NetCloseReason.KEEPALIVE_TIMEOUT);

        assertFalse(capture.isRecording(),
                "spec 4.1: \"Client: stop all modules\" - a capture whose connection died "
                        + "is silent, and silence is what a working bench with no signal "
                        + "looks like too");
        assertNull(faults.detail.get(),
                "but the stream does not REPORT it: the end of a session belongs to "
                        + "whoever owns the session - it has to be told once whether three "
                        + "streams were open on it or none at all, and a failure reported "
                        + "twice is a failure nobody trusts");

        capture.close();
    }

    /**
     * A settings write (spec 4.6) while this client is measuring: the lock it
     * needs is the one its own capture is already holding, so it must be USED and
     * not taken and given back.
     *
     * <p>The bench answers a repeat {@code device.acquire} by the same session
     * with success, so the wire cannot tell the client it already had it; the
     * release that followed hit spec 4.3's "also closes any open stream/generator
     * on it" and tore down the running capture - orderly, so no
     * {@code ev.device.error} followed and the panes simply froze.
     */
    @Test
    void aSettingsWriteRunsUnderTheLockTheStreamAlreadyHolds() {
        NetDeviceManager manager = connect(CLIENT_NAME);
        Faults faults = subscribe(manager);
        NetPcmCapture capture = openFirstInput(manager);
        MockBench.Capture stream = bench.getLastCapture();
        assertNotNull(stream);

        assertEquals(Boolean.TRUE, manager.withDeviceLock(() -> Boolean.TRUE),
                "the write must be attempted - the client holds the device");

        assertFalse(stream.isClosed(),
                "the bench's stream is still open: a settings write may not close the "
                        + "capture the operator is measuring with");
        assertEquals(0, bench.receivedOf(MessageType.DEVICE_RELEASE).size(),
                "and nothing was given back: the release is what would have closed it");
        assertTrue(capture.isRecording(), "and the stream is still running");
        assertNull(faults.detail.get(), "with nothing to report");

        capture.close();
        assertTrue(stream.isClosed(), "and the ordinary close still gives the line back");
    }

    /** With nothing of this bench held, the same write takes a device for the one
     *  request and gives it straight back - a client that kept it would leave the
     *  bench unusable to everyone else. */
    @Test
    void aSettingsWriteWithNoStreamTakesAndReturnsADevice() {
        NetDeviceManager manager = connect(CLIENT_NAME);
        NetDeviceManager observer = connect(OBSERVER_NAME);
        DeviceRef contended = observer.listInputDevices().get(0);

        assertEquals(Boolean.TRUE, manager.withDeviceLock(() -> Boolean.TRUE));

        assertFalse(bench.isLocked(MockBench.FIRST_INPUT, true),
                "the device the write borrowed was given straight back");
        NetPcmCapture other = (NetPcmCapture) observer.openCapture(contended, RATE_HZ, BITS);
        other.open();
        other.close();
    }

    @Test
    void aDeviceAnotherClientHoldsIsRefusedByNameAndTheOtherClientIsTold()
            throws Exception {
        NetDeviceManager holder = connect(CLIENT_NAME);
        NetDeviceManager observer = connect(OBSERVER_NAME);
        List<DeviceRef> before = observer.listInputDevices();
        assertFalse(before.isEmpty());
        CountDownLatch changed = new CountDownLatch(1);
        // Registered AFTER the manager's own subscription, so the cache has been
        // rebuilt by the time this fires (the listeners run in order).
        observer.getConnection().addEventListener(event -> {
            if (event.getType() == MessageType.EV_DEVICES_CHANGED) {
                changed.countDown();
            }
        });

        NetPcmCapture taken = openFirstInput(holder);

        assertTrue(changed.await(AWAIT_MS, TimeUnit.MILLISECONDS),
                "spec 4.3: every lock change is broadcast to ALL sessions");
        assertEquals(before, observer.listInputDevices(),
                "the event carries the full devices.list payload, so the catalogue is "
                        + "REBUILT from it - a client that misread the event would show "
                        + "an empty device combo the moment somebody else took a device");
        assertEquals(CLIENT_NAME, observer.lockedBy(observer.listInputDevices().get(0)),
                "and the holder's name came with it, so a taken device can be SHOWN as "
                        + "taken instead of being discovered as a refusal at open time");

        DeviceRef contended = observer.listInputDevices().get(0);
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> observer.openCapture(contended, RATE_HZ, BITS).open());
        assertTrue(refused.getMessage().contains(ErrorCode.DEVICE_LOCKED.name()),
                "spec 4.2: the refusal carries its code - " + refused.getMessage());
        assertTrue(refused.getMessage().contains(CLIENT_NAME),
                "spec 4.3: and it names the holder, because \"in use by Developer's laptop\" "
                        + "is actionable and \"cannot open device\" is not - "
                        + refused.getMessage());
        assertEquals(DeviceFailureReason.DEVICE_IN_USE, observer.classifyFailure(refused),
                "and the refusal keeps a REASON: spec 4.2 sends no reason field with a "
                        + "code-only refusal, so the code is the answer - a client that "
                        + "mapped none told the operator \"reason unknown\" about a device "
                        + "somebody else is plainly holding");

        taken.close();
    }

    // -------------------------------------------------------------------------
    // The remote generator - spec 4.5
    // -------------------------------------------------------------------------

    /**
     * The generator commands of spec 4.5 across the wire and back: the lane the
     * client opened carries the format, the dither depth and the lane gate it was
     * given, and the frequency the client reports is the one the BENCH computed
     * for the grid the client sent it.
     *
     * <p>That last equality is the whole point of {@code gen.fftGrid} - the far
     * end snaps with "the same math as the client-side snap, so both compute
     * identical values" - and it is asserted against {@link FftBinSnap} run on
     * the test's OWN constants, while the bench snaps on the grid it was actually
     * TOLD.  So a client that never put the analyzer's FFT size (or the snap flag)
     * on the wire gets a different number back and fails here.  The nominal is
     * asserted to be a different value, so a bench that ignored the grid could not
     * pass by echoing what it was told.
     */
    @Test
    void theGeneratorRunsOnTheBenchAtTheFrequencyBothEndsCompute() {
        NetDeviceManager manager = connect(CLIENT_NAME);
        DeviceRef output = manager.listOutputDevices().get(0);

        manager.openGenerator(output, RATE_HZ, BITS, DITHER_BITS, OutputChannels.LEFT);
        manager.setForm(GenSignalForm.SINE);
        manager.setFrequency(TONE_HZ);
        manager.setAmplitudeVrms(AMPLITUDE_VRMS);
        manager.fftGrid(FFT_SIZE, true);
        manager.startGenerator();

        MockBench.Generator lane = bench.getLastGenerator();
        assertNotNull(lane, "the client opened no generator on the bench");
        assertEquals(RATE_HZ, lane.getRate());
        assertEquals(BITS, lane.getBits());
        assertEquals(DITHER_BITS, lane.getDitherBits(),
                "spec 4.5 fixes the dither depth at gen.open - it is the LINE's, not a "
                        + "live parameter");
        assertEquals(OutputChannels.LEFT.name(), lane.getOutputChannels(),
                "the lane gate travelled with the open, so the bench drives the side "
                        + "the operator selected");
        ObjectNodeView commanded = new ObjectNodeView(lane.getConfigAtStart());
        assertEquals(GenSignalForm.SINE.name(), commanded.text(NetFields.FORM),
                "and the waveform reached the bench before it was told to emit");
        assertEquals(AMPLITUDE_VRMS, commanded.number(NetFields.AMPLITUDE_VRMS), EPS,
                "spec 4.5: the amplitude is FINAL - the client already did the "
                        + "calibration, so what crosses the wire is volts RMS");

        double snapped = FftBinSnap.snapIfEnabled(GenSignalForm.SINE, RATE_HZ, FFT_SIZE,
                true, TONE_HZ);
        awaitTrue(() -> manager.state().running(), "the bench never said it was emitting");
        RemoteGenerator.State state = manager.state();
        assertEquals(snapped, state.emitHz(), HZ_TOLERANCE,
                "spec 4.5: emitHz is post-snap, and gen.fftGrid promises the two ends "
                        + "compute it identically - which they can only do if the grid "
                        + "really crossed the wire");
        assertNotEquals(TONE_HZ, state.emitHz(),
                "a bench that had ignored the grid would report the nominal back, and "
                        + "the client would point its analyzer at a line that is not there");
        assertEquals(0.0, state.emit2Hz(),
                "spec 4.5: 0 means this waveform emits no such tone - a single tone has "
                        + "no second one, and a client must not draw a hint for it");

        manager.stopGenerator();
        manager.closeGenerator();
        assertTrue(lane.isClosed(), "spec 4.5: gen.close gives the output line back");
        assertFalse(bench.isLocked(MockBench.OUTPUT, false),
                "and the device lock goes with it");
    }

    /**
     * A tone asked for on a DAC another client is driving: the open is refused
     * before any lane exists, and the refusal carries both halves of an answer
     * the operator can act on - WHY (the device is taken) and BY WHOM.
     *
     * <p>Spec 4.5 requires the output lock for every {@code gen.*}, so the refusal
     * arrives at {@code device.acquire}, as a code-only {@code DEVICE_LOCKED} with
     * no {@code reason} field of spec 4.2 beside it.  Read without mapping the
     * code that is {@code UNKNOWN}, and the pane above renders "the bench refused
     * to open the output device: reason unknown" for the one refusal a bench with
     * two clients meets daily.
     */
    @Test
    void aGeneratorOnADacAnotherClientHoldsIsRefusedAsInUseAndNamesTheHolder() {
        NetDeviceManager holder = connect(CLIENT_NAME);
        NetDeviceManager observer = connect(OBSERVER_NAME);
        holder.openGenerator(holder.listOutputDevices().get(0), RATE_HZ, BITS,
                DITHER_BITS, OutputChannels.BOTH);
        DeviceRef contended = observer.listOutputDevices().get(0);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> observer.openGenerator(contended, RATE_HZ, BITS, DITHER_BITS,
                        OutputChannels.BOTH));

        assertEquals(DeviceFailureReason.DEVICE_IN_USE, observer.classifyFailure(refused),
                "the protocol's own error code says the device is taken, and that is "
                        + "what the operator has to be told: " + refused.getMessage());
        assertTrue(refused.getMessage().contains(CLIENT_NAME),
                "spec 4.3: and the holder travels with it - " + refused.getMessage());
        assertFalse(observer.isGeneratorOpen(),
                "a refused open leaves no lane behind on this side either");

        holder.closeGenerator();
    }

    /**
     * Everything a sweep needs reaches the bench BEFORE it is told to emit - and
     * the lead-in above all.
     *
     * <p>This is the alignment the whole frequency-response record rests on.  The
     * analyzer deconvolves {@code Y/X} against a reference it builds with exactly
     * {@code leadInSamples} of silence in front of the chirp; if the bench were
     * told a different lead-in - or none, because the client dropped the field
     * after the lane was already open - Y would sit against X displaced by the
     * difference: no sample lost, nothing to confess, and the transfer function
     * rippling by an amount that GROWS with the lead-in.  That is a wobble with no
     * fault anywhere, which is why every field is pinned by what the far end
     * received rather than by what the client was asked to send.
     *
     * <p>The pushes deliberately arrive AFTER {@code gen.open}, which is when a
     * client really configures a sweep, and the configuration is read as it stood
     * when {@code gen.start} arrived - the only moment at which "in time to
     * matter" means anything.
     *
     * <p><b>What is no longer proved here:</b> that the bench's rendered chirp is
     * the analyzer's reference waveform apart from a gain.  Rendering is the
     * server's, and it is the server module's to prove.
     */
    @Test
    void everySweepFieldReachesTheBenchBeforeItIsToldToEmit() {
        NetDeviceManager manager = connect(CLIENT_NAME);
        DeviceRef output = manager.listOutputDevices().get(0);
        manager.openGenerator(output, RATE_HZ, BITS, DITHER_BITS, OutputChannels.BOTH);

        // Everything the sweep needs, pushed onto an ALREADY OPEN lane.
        manager.setForm(GenSignalForm.LOG_SWEEP);
        manager.setSweepFreqStart(SWEEP_F0_HZ);
        manager.setSweepFreqEnd(SWEEP_F1_HZ);
        manager.setSweepDurationSamples(SWEEP_SAMPLES);
        manager.setSweepLeadInSamples(LEAD_IN_SAMPLES);
        manager.setSweepFadeInSamples(FADE_SAMPLES);
        manager.setSweepFadeOutSamples(FADE_SAMPLES);
        manager.setSweepLoop(false);
        manager.setAmplitudeVrms(AMPLITUDE_VRMS);
        manager.startGenerator();

        MockBench.Generator lane = bench.getLastGenerator();
        assertNotNull(lane, "the client opened no generator on the bench");
        ObjectNodeView commanded = new ObjectNodeView(lane.getConfigAtStart());
        assertEquals(GenSignalForm.LOG_SWEEP.name(), commanded.text(NetFields.FORM));
        ObjectNodeView sweep = commanded.block(NetFields.SWEEP);
        assertEquals(SWEEP_F0_HZ, sweep.number(NetFields.F0), EPS);
        assertEquals(SWEEP_F1_HZ, sweep.number(NetFields.F1), EPS);
        assertEquals(SWEEP_SAMPLES, sweep.number(NetFields.DURATION_SAMPLES), EPS);
        assertEquals(LEAD_IN_SAMPLES, sweep.number(NetFields.LEAD_IN_SAMPLES), EPS,
                "the lead-in the analyzer's reference is built with must be the one the "
                        + "bench emits, or the record sits displaced by the difference");
        assertEquals(FADE_SAMPLES, sweep.number(NetFields.FADE_IN_SAMPLES), EPS,
                "and the per-side fade, or the start/stop leakage stops cancelling in "
                        + "the Y/X division and ripples across the whole band");
        assertEquals(FADE_SAMPLES, sweep.number(NetFields.FADE_OUT_SAMPLES), EPS);
        assertFalse(sweep.flag(NetFields.LOOP),
                "a looping sweep would restart underneath the record being cut from it");

        assertEquals(1, bench.receivedOf(MessageType.GEN_OPEN).size(),
                "one lane, opened once");
        assertTrue(bench.receivedOf(MessageType.GEN_CONFIG).size() > 1,
                "the fields are pushed one at a time as the operator sets them (spec "
                        + "4.5's partial update), which is exactly why the LAST one has "
                        + "to have landed before the start");

        manager.closeGenerator();
    }

    /** Spec 4.5's partial update, from the client's side: a setter sends ONLY the
     *  field it changed.  Re-sending a sweep's duration re-renders the chirp and
     *  restarts it at sample 0, underneath whoever is recording it. */
    @Test
    void aGeneratorSetterSendsOnlyTheFieldItChanged() {
        NetDeviceManager manager = connect(CLIENT_NAME);
        manager.openGenerator(manager.listOutputDevices().get(0), RATE_HZ, BITS,
                DITHER_BITS, OutputChannels.BOTH);

        manager.setSweepLeadInSamples(LEAD_IN_SAMPLES);

        NetMessage pushed = bench.awaitNth(MessageType.GEN_CONFIG, 1, AWAIT_MS);
        JsonNode sweep = pushed.getNode(NetFields.SWEEP);
        assertEquals(LEAD_IN_SAMPLES, sweep.path(NetFields.LEAD_IN_SAMPLES).asInt());
        assertTrue(sweep.path(NetFields.DURATION_SAMPLES).isMissingNode(),
                "the duration was never set on this lane, and a setter that sent the "
                        + "whole block would carry a zero for it - which is a re-render "
                        + "of a chirp with no length at all");
        assertTrue(pushed.getNode(NetFields.FREQUENCY).isMissingNode(),
                "nor may a sweep field drag the tone frequency along with it");

        manager.closeGenerator();
    }

    /**
     * The state readback contract: {@link RemoteGenerator#state()} answers the
     * last {@code ev.gen.state} the bench PUSHED, and nothing else.
     *
     * <p>A command is not a state - the reader may be one push behind, which is
     * by design, because the alternative is a client reporting a frequency the
     * hardware never emitted.  So: nothing is claimed before the bench has
     * spoken, a command the bench never saw moves nothing, and a trim shows up
     * only once the bench has confirmed it (the trim of spec 4.5 is an ABSOLUTE
     * corrected frequency, so the confirmation is exactly the value sent).
     */
    @Test
    void theGeneratorStateIsWhateverTheBenchLastPushed() throws Exception {
        NetDeviceManager manager = connect(CLIENT_NAME);
        assertEquals(RemoteGenerator.State.IDLE, manager.state(),
                "the bench has not spoken, so there is nothing to report");

        manager.setFrequency(TONE_HZ);
        assertEquals(RemoteGenerator.State.IDLE, manager.state(),
                "a command sent with no generator open reached no bench, and a client "
                        + "that answered from what it commanded would report a tone "
                        + "nothing is emitting");

        DeviceRef output = manager.listOutputDevices().get(0);
        manager.openGenerator(output, RATE_HZ, BITS, DITHER_BITS, OutputChannels.BOTH);
        manager.setForm(GenSignalForm.SINE);
        manager.setFrequency(TONE_HZ);
        manager.startGenerator();
        awaitTrue(() -> manager.state().running(), "the bench never said it was emitting");

        // Registered AFTER the manager's own subscription, so its cache is
        // already rebuilt when this fires (the listeners run in order).
        CountDownLatch confirmed = new CountDownLatch(1);
        manager.getConnection().addEventListener(event -> {
            Double emitted = event.optDouble(NetFields.EMIT_HZ);
            if (event.getType() == MessageType.EV_GEN_STATE && emitted != null
                    && Math.abs(emitted - TRIMMED_HZ) < HZ_TOLERANCE) {
                confirmed.countDown();
            }
        });

        manager.trim(TRIMMED_HZ);

        assertTrue(confirmed.await(AWAIT_MS, TimeUnit.MILLISECONDS),
                "the bench never confirmed the trim");
        awaitTrue(() -> Math.abs(manager.state().emitHz() - TRIMMED_HZ) < HZ_TOLERANCE,
                "spec 4.5: the frequency-lock loop's actuator lives on the bench, and "
                        + "what the client reads back is what the bench says it emits");

        manager.closeGenerator();
        assertEquals(RemoteGenerator.State.IDLE, manager.state(),
                "a closed generator emits nothing, and a stale 'running' would leave the "
                        + "Play button claiming a tone that stopped");
    }

    /** A state for a generator this manager does not hold is not its state: a
     *  lane it has already closed, or one another session opened, must not move
     *  what this pane reports. */
    @Test
    void aStateForAnotherGeneratorIsNotTaken() {
        NetDeviceManager manager = connect(CLIENT_NAME);
        DeviceRef output = manager.listOutputDevices().get(0);
        manager.openGenerator(output, RATE_HZ, BITS, DITHER_BITS, OutputChannels.BOTH);
        manager.setForm(GenSignalForm.SINE);
        manager.setFrequency(TONE_HZ);
        manager.startGenerator();
        awaitTrue(() -> manager.state().running(), "the bench never said it was emitting");
        int otherLane = bench.getLastGenerator().getId() + 1;

        bench.push(new NetMessage(MessageType.EV_GEN_STATE)
                .put(NetFields.GEN_ID, otherLane)
                .put(NetFields.RUNNING, false)
                .put(NetFields.EMIT_HZ, 0.0));
        settle(manager);

        assertTrue(manager.state().running(),
                "a state addressed to another lane stopped this one: the pane would show "
                        + "a Play button for a tone that is still emitting");
        assertEquals(TONE_HZ, manager.state().emitHz(), HZ_TOLERANCE,
                "and it would report a frequency nothing is emitting");
        manager.closeGenerator();
    }

    /** One request out and back on the same socket, so an event pushed just
     *  before it has certainly been processed by the client's reader thread by
     *  the time this returns - the only honest way to assert that something did
     *  NOT happen. */
    private void settle(NetDeviceManager manager) {
        NetConnection session = manager.getConnection();
        assertTrue(session.request(session.newRequest(MessageType.DEVICES_LIST)).isOk());
    }

    // -------------------------------------------------------------------------
    // The client under test
    // -------------------------------------------------------------------------

    /**
     * One connected client: a session on the bench, and a device manager pointed
     * at the backend that bench actually serves - through the same two calls the
     * server-list dialog makes, {@code devices.list} then {@code backend.select},
     * with the manager filled from the select's own answer (spec 4.3: "one
     * round-trip fills the device combos").
     *
     * <p>Nothing names that backend: which one a host can operate is the host's
     * business, so it is read off the wire.
     */
    private NetDeviceManager connect(String clientName) {
        NetConnection connection = open(clientName);
        NetDeviceManager manager = new NetDeviceManager();
        manager.connect(connection, select(connection, servedBackend(connection)));
        return manager;
    }

    /** One session, through the shipping handshake.  Its own keepalive ticker is
     *  a {@link FakeTicker} nobody advances - nothing here is about the
     *  heartbeat. */
    private NetConnection open(String clientName) {
        NetConnection connection = new NetConnection(wsUri, clientName, CLIENT_APP,
                codec, new FakeTicker());
        connections.add(connection);
        connection.open(AWAIT_MS);
        return connection;
    }

    /** Spec 4.3's {@code backend.select}: the session commits to one backend and
     *  is answered with that backend's whole entry. */
    private JsonNode select(NetConnection connection, String backend) {
        NetMessage answer = connection.request(
                connection.newRequest(MessageType.BACKEND_SELECT)
                        .put(NetFields.BACKEND, backend));
        assertTrue(answer.isOk(), "the bench refused backend.select: " + answer);
        return answer.getData();
    }

    /** The first backend of {@code devices.list} that has any device at all. */
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

    /** Subscribes to everything that can go wrong on this bench - what the
     *  server-list dialog does once it has connected one. */
    private Faults subscribe(NetDeviceManager manager) {
        Faults faults = new Faults();
        manager.setFaultListener(faults);
        return faults;
    }

    /** An open, recording capture on the bench's first input - the state the
     *  frame tests start from. */
    private NetPcmCapture openFirstInput(NetDeviceManager manager) {
        NetPcmCapture capture = (NetPcmCapture) manager.openCapture(
                manager.listInputDevices().get(0), RATE_HZ, BITS);
        capture.open();
        capture.startRecording();
        return capture;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Spins until {@code condition} holds - a condition, never a sleep.  Every
     *  frame now crosses a real socket, so what used to be a synchronous call
     *  into the client is an arrival to be waited for. */
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
            for (byte[] batch : batches) {
                System.arraycopy(batch, 0, all, at, batch.length);
                at += batch.length;
            }
        }
        assertEquals(SECOND_BYTES, at, "the pipeline received a different number of bytes");
        return all;
    }

    /** A JSON object read by field name, so an assertion on what the bench was
     *  told reads as the field it is about and not as a path expression. */
    private record ObjectNodeView(JsonNode node) {

        private String text(String field) {
            return node.path(field).asText(null);
        }

        private double number(String field) {
            return node.path(field).asDouble();
        }

        private boolean flag(String field) {
            return node.path(field).asBoolean();
        }

        private ObjectNodeView block(String field) {
            return new ObjectNodeView(node.path(field));
        }
    }

    /** What a sweep-bounded measurement subscribes to on ONE stream - the marks
     *  and the losses that bound and break its record.  Atomics for the same
     *  reason {@link Faults} uses them: written on the connection's reader
     *  thread, read on the test's. */
    private static final class Marks implements MarkedCapture.Listener {

        /** How many stereo frames the last GAP confessed; -1 = none yet. */
        private final AtomicLong lost = new AtomicLong(-1);
        /** Where it fell, in PCM bytes already delivered. */
        private final AtomicLong at = new AtomicLong(-1);

        @Override
        public void marker(int markerKind, long pcmBytesDelivered) {
            at.set(pcmBytesDelivered);
        }

        @Override
        public void gap(long lostFrames, long pcmBytesDelivered) {
            at.set(pcmBytesDelivered);
            lost.set(lostFrames);
        }
    }

    /**
     * The glue that is not built yet, reduced to what a test can assert: the one
     * subscription the whole bench's faults arrive on (spec 4.1, 4.3 and 5).
     *
     * <p>The fields are atomics because every one of them is written on the
     * connection's reader thread and read on the test's.
     */
    private static final class Faults implements NetFaultListener {

        /** How many stereo frames the last GAP confessed; -1 = no GAP yet. */
        private final AtomicLong lost = new AtomicLong(-1);
        private final AtomicReference<String> direction = new AtomicReference<>();
        private final AtomicReference<String> detail = new AtomicReference<>();
        /** Every device reported as having left the bench while this client held
         *  it, in order.  A LIST and not a last-one: what a wrong implementation
         *  gets wrong is reporting devices it should not have. */
        private final List<String> gone = new CopyOnWriteArrayList<>();
        /** Every device reported as RECALIBRATED by the bench, in order, with the
         *  values it was reported with - a list for the same reason {@link #gone}
         *  is one: reporting a device whose calibration did NOT move is the
         *  failure worth catching. */
        private final List<DeviceRef> recalibrated = new CopyOnWriteArrayList<>();

        @Override
        public void gap(long lostFrames) {
            lost.set(lostFrames);
        }

        @Override
        public void deviceError(String direction, String detail) {
            this.direction.set(direction);
            this.detail.set(detail);
        }

        @Override
        public void deviceGone(DeviceRef device) {
            gone.add(device.name());
        }

        @Override
        public void calibrationChanged(DeviceRef device) {
            recalibrated.add(device);
        }
    }
}
