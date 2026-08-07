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

package org.edgo.audio.measure.net.server;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.net.proto.ErrorCode;
import org.edgo.audio.measure.net.proto.JsonCodec;
import org.edgo.audio.measure.net.proto.MessageType;
import org.edgo.audio.measure.net.proto.NetError;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.net.proto.NetMessage;
import org.edgo.audio.measure.net.proto.NetProto;
import org.edgo.audio.measure.preferences.AudioDeviceProfile;
import org.edgo.audio.measure.preferences.DeviceEndpointConfig;
import org.edgo.audio.measure.preferences.DeviceRange;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.DeviceCalibration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One connection's whole life, driven without a socket and without a clock: the
 * handshake of spec 1, the dispatch and error vocabulary of spec 4.0 - 4.3, and
 * the keepalive of spec 4.1 - four unanswered pings and the devices come back.
 *
 * <p>The keepalive tests are the reason the session takes an injected
 * {@link Ticker}: "wait 2.5 seconds and see" would be both slow and flaky,
 * while {@link FakeTicker#advance(int)} makes the death sequence exact.
 */
class ClientSessionTest {

    private static final String SERVER_NAME = "Bench QA403";
    private static final String CLIENT_NAME = "Developer's laptop";
    private static final String OTHER_CLIENT_NAME = "Bench tablet";
    private static final int HELLO_ID = 1;
    private static final int REQUEST_ID = 2;
    private static final int SECOND_REQUEST_ID = 3;
    private static final int THIRD_REQUEST_ID = 4;
    private static final int FOURTH_REQUEST_ID = 5;
    private static final int DEVICE_INDEX = 0;
    /** The stub bench's first input, which every device ref here names - the
     *  name validation of spec 4.3 is part of every acquire. */
    private static final String DEVICE_NAME = "QA403";
    /** The bench's OTHER input - a second device of the same backend, which is
     *  what makes "holds a lock" and "holds THIS device's lock" two questions. */
    private static final int SECOND_DEVICE_INDEX = 1;
    private static final String SECOND_DEVICE_NAME = StubDeviceManager.SECOND_INPUT;
    private static final int CAPTURE_RATE_HZ = 48_000;
    private static final int CAPTURE_BITS = 24;
    /** 24-bit stereo, two frames - enough to be one batch. */
    private static final int CAPTURE_BATCH_BYTES = 12;
    /** The two full scales a calibration test writes, deliberately unequal so a
     *  handler that crossed the channels fails on the values. */
    private static final double FS_RMS_LEFT = 1.234;
    private static final double FS_RMS_RIGHT = 2.345;
    private static final double EPS = 1e-9;
    /** A card that says its full scales come from the device itself. */
    private static final String DEVICE_PROVIDED_CARD = "Bench analyzer";
    /** An ordinary card of this bench's, for the binding suite - its calibration
     *  is not the device's, so it is the case that says nothing about the
     *  {@code calibrationFromDevice} exception either way. */
    private static final String BINDABLE_CARD = "Bench ADC";
    /** An input range the stub bench really has and does NOT start on, so a full
     *  scale that failed to follow the attenuator is visible. */
    private static final int OTHER_INPUT_DBV = StubDeviceManager.INPUT_RANGES_DBV[1];
    /** The two ranges' full scales, one row each - far apart, for the same
     *  reason. */
    private static final double LOW_RANGE_FS = 1.0;
    private static final double HIGH_RANGE_FS = 8.0;
    /** The two rows of a card that arrives by {@code cards.put} - plain labels, so
     *  nothing here can be mistaken for a QA40x attenuator position. */
    private static final String PUT_RANGE_LABEL = "default";
    private static final String OTHER_PUT_RANGE_LABEL = "attenuated";

    private final ServerConfig config =
            new ServerConfig(new String[] {"--name", SERVER_NAME});
    private final LockRegistry locks = new LockRegistry();
    private final JsonCodec codec = new JsonCodec();
    /** This bench's device cards - empty unless a test binds one. */
    private final StubCardStore cards = new StubCardStore();
    private final DeviceCatalog catalog = new DeviceCatalog(AudioBackend.instance(), locks,
            codec, List.of(AudioBackendType.QA40X), cards.getPrefs());
    private final FakeChannel channel = new FakeChannel();
    private final FakeTicker ticker = new FakeTicker();
    /** The session's own request thread, hand-cranked - the one the teardown of
     *  spec 4.1 has to run on. */
    private final FakeWorker worker = new FakeWorker();
    /** The GENERATOR's own clock, held here so a teardown test can make the
     *  generator's close fail the way a native one does. */
    private final FakeTicker genTicker = new FakeTicker();
    private final ClientSession session = sessionOn(channel, ticker, worker, genTicker);

    @BeforeEach
    void startTheKeepalive() {
        session.start();
        // The bench records the range it is switched to in its card, exactly as
        // the real analyzer's manager does - into THIS test's store, never the
        // developer's.  The manager is the process-wide one every test class
        // shares, so it is unhooked again below.
        manager().setCards(cards.getPrefs());
    }

    @AfterEach
    void unhookTheCardStore() {
        manager().setCards(null);
    }

    @Test
    void helloIsAnsweredWithTheChosenVersionAndTheServersIdentity() {
        greet(session, CLIENT_NAME);

        NetMessage response = channel.responseTo(HELLO_ID);
        assertNotNull(response);
        assertTrue(response.isOk());
        assertEquals(NetProto.PROTO_VERSION,
                response.getData().path(NetFields.PROTO).asInt(),
                "the response carries the CHOSEN session version (spec 1)");
        assertEquals(config.getServerId(),
                response.getData().path(NetFields.SERVER_ID).asText());
        assertEquals(SERVER_NAME, response.getData().path(NetFields.NAME).asText());
        assertEquals(config.getApp(), response.getData().path(NetFields.APP).asText());
        assertEquals(List.of(NetFields.CAP_GEN, NetFields.CAP_FILES, NetFields.CAP_QA40X),
                capsOf(response),
                "a cap is a promise the commands behind it work: this bench's "
                        + "backend answers the analyzer surface of spec 4.6, so the "
                        + "token is claimed beside the generator and the uploads");
        assertEquals(NetProto.PROTO_VERSION, session.getProto());
        assertEquals(CLIENT_NAME, session.getClientName(),
                "the client name is what a DEVICE_LOCKED error will name");
    }

    @Test
    void aNewerClientIsServedAtTheHighestVersionInBothRanges() {
        int future = NetProto.PROTO_VERSION + 1;

        session.onMessage(new NetMessage(MessageType.HELLO, HELLO_ID)
                .put(NetFields.PROTO, future)
                .put(NetFields.PROTO_MIN, NetProto.PROTO_MIN_VERSION)
                .put(NetFields.NAME, CLIENT_NAME));

        assertTrue(channel.responseTo(HELLO_ID).isOk(),
                "a v" + future + " client whose range reaches down to v"
                        + NetProto.PROTO_MIN_VERSION + " must be served, not refused");
        assertEquals(NetProto.PROTO_VERSION, session.getProto());
        assertEquals(0, channel.getCloseCount());
    }

    @Test
    void aClientThatCannotSpeakOurVersionIsRejectedAndClosed() {
        int future = NetProto.PROTO_VERSION + 1;

        session.onMessage(new NetMessage(MessageType.HELLO, HELLO_ID)
                .put(NetFields.PROTO, future)
                .put(NetFields.PROTO_MIN, future)
                .put(NetFields.NAME, CLIENT_NAME));

        NetError error = channel.responseTo(HELLO_ID).getError();
        assertTrue(error.is(ErrorCode.PROTO_MISMATCH));
        assertTrue(error.message().contains(future + ".." + future),
                "the message names the client's range");
        assertTrue(error.message().contains(
                NetProto.PROTO_MIN_VERSION + ".." + NetProto.PROTO_VERSION),
                "and the server's");
        assertTrue(session.isClosed(), "no session is possible, so it ends here");
        assertEquals(1, channel.getCloseCount());
    }

    @Test
    void helloWithoutAProtoIsABadRequest() {
        session.onMessage(new NetMessage(MessageType.HELLO, HELLO_ID)
                .put(NetFields.NAME, CLIENT_NAME));

        assertTrue(channel.responseTo(HELLO_ID).getError().is(ErrorCode.BAD_REQUEST));
    }

    @Test
    void nothingIsServedBeforeHello() {
        session.onMessage(new NetMessage(MessageType.DEVICE_ACQUIRE, REQUEST_ID));

        assertTrue(channel.responseTo(REQUEST_ID).getError().is(ErrorCode.BAD_REQUEST),
                "hello MUST be first (spec 4.1)");
        assertEquals(0, locks.size());
    }

    @Test
    void aSecondHelloIsRejected() {
        greet(session, CLIENT_NAME);

        session.onMessage(new NetMessage(MessageType.HELLO, REQUEST_ID)
                .put(NetFields.PROTO, NetProto.PROTO_VERSION));

        assertTrue(channel.responseTo(REQUEST_ID).getError().is(ErrorCode.BAD_REQUEST));
        assertFalse(session.isClosed(), "a stray hello is an error, not a hang-up");
    }

    @Test
    void anUnknownMessageTypeIsAnsweredUnsupported() throws JsonProcessingException {
        greet(session, CLIENT_NAME);

        session.onMessage(codec.read(
                "{\"t\":\"gen.warpFactor\",\"id\":" + REQUEST_ID + "}"));

        NetError error = channel.responseTo(REQUEST_ID).getError();
        assertTrue(error.is(ErrorCode.UNSUPPORTED), "the spec 1 forward-compat answer");
        assertTrue(error.message().contains("gen.warpFactor"),
                "and it quotes what it did not understand");
    }

    @Test
    void aHandlerThatFailsIsAnsweredInternalAndTheSessionSurvives() {
        greet(session, CLIENT_NAME);
        channel.failOnce();

        session.onMessage(deviceRef(MessageType.DEVICE_RELEASE, REQUEST_ID, true));

        assertTrue(channel.responseTo(REQUEST_ID).getError().is(ErrorCode.INTERNAL),
                "spec 4.2 names INTERNAL for anything unexpected on the server");
        assertFalse(session.isClosed(),
                "one bad call must not cost the client its connection and every lock");
    }

    @Test
    void aClientPingIsAnsweredWithAnEmptyPayload() {
        greet(session, CLIENT_NAME);

        session.onMessage(new NetMessage(MessageType.PING, REQUEST_ID));

        NetMessage response = channel.responseTo(REQUEST_ID);
        assertTrue(response.isOk());
        assertEquals(0, response.getData().size());
    }

    @Test
    void aPingBeforeHelloIsAnsweredToo() {
        session.onMessage(new NetMessage(MessageType.PING, REQUEST_ID));

        assertTrue(channel.responseTo(REQUEST_ID).isOk(),
                "spec 4.1 marks only hello as MUST-be-first, and the server's own "
                        + "pings start at the open - so the client's may too");
    }

    @Test
    void theServerPingsEveryPeriodWithNegativeIds() {
        greet(session, CLIENT_NAME);

        ticker.advance(3);

        assertEquals(NetProto.PING_INTERVAL_MS, ticker.getPeriodMs(),
                "500 ms, both directions (spec 4.1)");
        List<Integer> ids = new ArrayList<>();
        for (NetMessage ping : channel.pings()) {
            ids.add(ping.getId());
        }
        assertEquals(List.of(-1, -2, -3), ids,
                "server request ids are negative so they cannot collide (spec 4.0)");
    }

    @Test
    void fourUnansweredPingsTearTheSessionDownAndFreeItsDevices() {
        greet(session, CLIENT_NAME);
        acquire(session, REQUEST_ID, true);
        acquire(session, SECOND_REQUEST_ID, false);
        assertEquals(2, locks.size());

        ticker.advance(NetProto.MAX_MISSED_PINGS);
        assertFalse(session.isClosed(),
                "four pings are out, but the deadline is only reached at the next tick");

        ticker.advance(1);

        assertTrue(session.isClosed());
        assertEquals(0, locks.size(), "every lock comes back (spec 4.1 teardown)");
        assertTrue(ticker.isStopped(), "and the keepalive stops with the session");
        assertEquals(1, channel.getCloseCount());
        assertNotNull(channel.getCloseReason());
    }

    /**
     * The bench failure, in one test.  The server log: "net capture 1: 47784
     * stereo frame(s) dropped - the client is not keeping up", 36924 more
     * 200 ms later, then "4 pings unanswered - connection dead".  A browser
     * delivers WebSocket messages IN ORDER, so a client that has fallen behind on
     * the audio lane has the server's ping queued behind thousands of binary
     * frames and answers it seconds late however healthy it is.  Counting ping
     * ANSWERS alone therefore measured the client's intake rate and killed the one
     * that was working hardest.
     *
     * <p>Its own pings are on a timer of its own, unaffected by that backlog - so
     * any frame from the client is what proves it is alive.
     */
    @Test
    void aClientDrowningInAudioStaysAliveWhileItIsStillTalking() {
        greet(session, CLIENT_NAME);
        acquire(session, REQUEST_ID, true);

        // Four periods in which it answers NOTHING - it is chewing through the
        // capture backlog - but goes on sending its own keepalive.
        for (int period = 0; period < NetProto.MAX_MISSED_PINGS + 3; period++) {
            ticker.advance(1);
            session.onMessage(new NetMessage(MessageType.PING, REQUEST_ID + period));
        }

        assertFalse(session.isClosed(),
                "it never answered a single ping, and it is plainly alive - the "
                        + "session must not be killed for being busy");
        assertEquals(1, locks.size(), "so it keeps the device it is capturing from");
    }

    /** The other half of the same rule: silence is still death, at the same
     *  deadline spec 4.1 promises the clients waiting for those devices. */
    @Test
    void aClientThatSaysNothingAtAllIsStillDeadAtTwoSeconds() {
        greet(session, CLIENT_NAME);
        acquire(session, REQUEST_ID, true);

        ticker.advance(NetProto.MAX_MISSED_PINGS);
        assertFalse(session.isClosed(), "the deadline is reached at the next tick");

        ticker.advance(1);

        assertTrue(session.isClosed());
        assertEquals(0, locks.size(), "and every lock comes back");
    }

    @Test
    void theWholeTeardownRunsOnTheSessionsOwnThread() {
        greet(session, CLIENT_NAME);
        acquire(session, REQUEST_ID, true);
        acquire(session, SECOND_REQUEST_ID, false);
        worker.hold();

        ticker.advance(NetProto.MAX_MISSED_PINGS + 1);

        assertTrue(session.isClosed(), "the keepalive declared the client dead");
        assertEquals(2, locks.size(),
                "but it declared it on the scheduler EVERY session shares: freeing "
                        + "the locks there broadcasts the device as available while "
                        + "this connection's play thread is still inside the render "
                        + "loop and its line still open, and the next client's open "
                        + "either fails outright or gets silent zeros");

        worker.release();

        assertEquals(0, locks.size(),
                "spec 4.1's teardown in its one order - streams, generator, locks, "
                        + "park - all of it on the session's own thread");
    }

    /**
     * The bench's stuck lock, in the form it arrived in: a close that faulted
     * NATIVELY.
     *
     * <p>A device pulled out from under a teardown does not throw an exception -
     * JNA raises an Error out of the faulted invocation, and an Error walked
     * straight past the guard around each teardown step.  With it went the
     * release AND the park, so the bench was left with "DEVICE_LOCKED ... held by
     * &lt;client&gt;" for a client that had already been restarted, and an
     * analyzer still sitting at its sensitive range.  A lock left behind is a
     * device no other connection ever acquires again short of restarting the
     * server, so it comes back whatever the close did.
     */
    @Test
    void aCloseThatFaultsNativelyStillGivesTheLocksBack() {
        greet(session, CLIENT_NAME);
        acquire(session, REQUEST_ID, true);
        assertEquals(1, locks.size());
        // The generator's teardown stops its state ticker BEFORE any of its own
        // guarded steps, so this is a close that reaches the session whole.
        genTicker.faultOnNextStop();

        session.onMessage(new NetMessage(MessageType.BYE, SECOND_REQUEST_ID));

        assertTrue(session.isClosed());
        assertEquals(0, locks.size(),
                "the locks come back even from a close that blew up - anything else "
                        + "strands the device for the life of the server process");
    }

    /**
     * And when the teardown never finishes at all.
     *
     * <p>A session's teardown runs on that session's ONE thread, which is where
     * its device calls run too - so a client that died while its request was
     * inside a libusb transfer on an analyzer that is no longer there leaves the
     * teardown queued behind a call that may never return.  Nothing then releases
     * the locks, ever.  After the watchdog they come back without it: only the
     * locks, because closing the line and parking the analyzer are device calls
     * and the device is what is stuck.
     */
    @Test
    void aTeardownThatNeverFinishesSurrendersItsLocksToTheWatchdog() {
        greet(session, CLIENT_NAME);
        acquire(session, REQUEST_ID, true);
        acquire(session, SECOND_REQUEST_ID, false);
        worker.hold();                    // the session's thread is stuck in a driver call

        ticker.advance(NetProto.MAX_MISSED_PINGS + 1);
        assertTrue(session.isClosed(), "the keepalive declared the client dead");
        assertEquals(2, locks.size(),
                "and the teardown that gives the devices back is queued behind the "
                        + "call that is not returning");

        ticker.advance(1);                // the watchdog period elapses

        assertEquals(0, locks.size(),
                "so the bench gets its devices back without it - a wedged device may "
                        + "cost a measurement, never every later connection's access "
                        + "to it");
    }

    @Test
    void anAnswerToOnePingKeepsTheSessionAlive() {
        greet(session, CLIENT_NAME);
        ticker.advance(NetProto.MAX_MISSED_PINGS);

        List<NetMessage> pings = channel.pings();
        session.onMessage(new NetMessage(pings.get(pings.size() - 1).getId()));
        ticker.advance(NetProto.MAX_MISSED_PINGS);

        assertFalse(session.isClosed(), "the counter is CONSECUTIVE misses, not a total");
        ticker.advance(1);
        assertTrue(session.isClosed(), "and it starts counting again from the answer");
    }

    /**
     * A LATE answer to an old ping is still the client speaking, and that is what
     * liveness is measured by
     * (see {@link #aClientDrowningInAudioStaysAliveWhileItIsStillTalking}).
     *
     * <p>It used to be read the other way - the id was old, so the answer "said
     * nothing about the four pings sent since" and the session died anyway.  That
     * reading is exactly what killed a healthy client: a client behind on
     * the audio lane answers every ping LATE, by an id that is always stale by the
     * time it arrives, while being perfectly alive.  The arrival is the evidence;
     * the id only ever mattered for deciding which ping had been cleared, and no
     * client can fake having sent a frame.
     */
    @Test
    void aLateAnswerToAnOldPingIsStillTheClientSpeaking() {
        greet(session, CLIENT_NAME);
        ticker.advance(1);
        int firstPingId = channel.pings().get(0).getId();
        ticker.advance(NetProto.MAX_MISSED_PINGS - 1);

        session.onMessage(new NetMessage(firstPingId));

        ticker.advance(NetProto.MAX_MISSED_PINGS);
        assertFalse(session.isClosed(),
                "it was heard from, so the count starts again from there");
        ticker.advance(1);
        assertTrue(session.isClosed(),
                "and having gone quiet since, it dies at the same 2 s (spec 4.1)");
    }

    @Test
    void byeAnswersThenReleasesEverythingAndCloses() {
        greet(session, CLIENT_NAME);
        acquire(session, REQUEST_ID, true);

        session.onMessage(new NetMessage(MessageType.BYE, SECOND_REQUEST_ID));

        assertTrue(channel.responseTo(SECOND_REQUEST_ID).isOk(),
                "the answer goes out before the socket does");
        assertTrue(session.isClosed());
        assertEquals(0, locks.size());
        assertTrue(ticker.isStopped());
    }

    @Test
    void aDeviceIsLockedAgainstTheSecondClientAndTheRefusalNamesTheHolder() {
        greet(session, CLIENT_NAME);
        FakeChannel otherChannel = new FakeChannel();
        ClientSession other = sessionOn(otherChannel, new FakeTicker(), new FakeWorker());
        other.start();
        greet(other, OTHER_CLIENT_NAME);

        acquire(session, REQUEST_ID, true);
        other.onMessage(deviceRef(MessageType.DEVICE_ACQUIRE, REQUEST_ID, true));

        NetError error = otherChannel.responseTo(REQUEST_ID).getError();
        assertTrue(error.is(ErrorCode.DEVICE_LOCKED));
        assertEquals(CLIENT_NAME, error.by(), "the operator is told WHO has the device");
        assertEquals(1, locks.size());
    }

    @Test
    void theDeviceListIsAnsweredWithEveryBackendAndItsDevices() {
        greet(session, CLIENT_NAME);

        session.onMessage(new NetMessage(MessageType.DEVICES_LIST, REQUEST_ID));

        JsonNode backends = channel.responseTo(REQUEST_ID).getData().path(NetFields.BACKENDS);
        assertEquals(1, backends.size());
        assertEquals(AudioBackendType.QA40X.name(),
                backends.get(0).path(NetFields.BACKEND).asText());
        assertTrue(backends.get(0).path(NetFields.DEVICES).size() > 0);
    }

    @Test
    void aCaptureOnADeviceThisConnectionDidNotAcquireIsNotLocked() {
        greet(session, CLIENT_NAME);

        session.onMessage(captureOpen(REQUEST_ID));

        assertTrue(channel.responseTo(REQUEST_ID).getError().is(ErrorCode.NOT_LOCKED),
                "spec 4.4: streaming requires the input lock");
    }

    @Test
    void theCaptureLifecycleRunsOverTheControlChannelAndTheFramesOverTheSameSocket() {
        greet(session, CLIENT_NAME);
        acquire(session, REQUEST_ID, true);

        session.onMessage(captureOpen(SECOND_REQUEST_ID));
        int captureId = channel.responseTo(SECOND_REQUEST_ID).getData()
                .path(NetFields.CAPTURE_ID).asInt();
        session.onMessage(new NetMessage(MessageType.CAPTURE_START, THIRD_REQUEST_ID)
                .put(NetFields.CAPTURE_ID, captureId));
        StubCapture capture = ((StubDeviceManager) AudioBackend.instance()
                .manager(AudioBackendType.QA40X)).getLastCapture();
        capture.feed(new byte[CAPTURE_BATCH_BYTES]);

        assertTrue(channel.responseTo(THIRD_REQUEST_ID).isOk());
        assertEquals(1, channel.getFrames().size(),
                "spec 5: the audio frames go out on the SAME socket as the control messages");
        assertEquals(captureId, channel.getFrames().get(0).streamId());

        session.onMessage(new NetMessage(MessageType.CAPTURE_CLOSE, FOURTH_REQUEST_ID)
                .put(NetFields.CAPTURE_ID, captureId));
        assertTrue(channel.responseTo(FOURTH_REQUEST_ID).isOk());
        assertTrue(capture.isClosed());
    }

    @Test
    void theAnalyzerIsParkedWhenTheLastConnectionHoldingItIsGone() {
        greet(session, CLIENT_NAME);
        acquire(session, REQUEST_ID, true);
        FakeChannel otherChannel = new FakeChannel();
        ClientSession other = sessionOn(otherChannel, new FakeTicker(), new FakeWorker());
        other.start();
        greet(other, OTHER_CLIENT_NAME);
        other.onMessage(deviceRef(MessageType.DEVICE_ACQUIRE, SECOND_REQUEST_ID, false));
        int parked = manager().getShutdownCount();

        ticker.advance(NetProto.MAX_MISSED_PINGS + 1);

        assertTrue(session.isClosed());
        assertEquals(parked, manager().getShutdownCount(),
                "the other client is still driving the output - parking would stop "
                        + "its measurement, which is the fault the park prevents");

        other.onMessage(new NetMessage(MessageType.BYE, THIRD_REQUEST_ID));

        assertEquals(parked + 1, manager().getShutdownCount(),
                "spec 4.1 teardown: park hardware (QA40x attenuator safe) - a client "
                        + "measuring at 0 dBV that vanished must not leave the input "
                        + "at maximum sensitivity");
    }

    @Test
    void releasingADeviceThisConnectionNeverHeldIsNotLocked() {
        greet(session, CLIENT_NAME);

        session.onMessage(deviceRef(MessageType.DEVICE_RELEASE, REQUEST_ID, true));

        assertTrue(channel.responseTo(REQUEST_ID).getError().is(ErrorCode.NOT_LOCKED));
    }

    @Test
    void calibratingADeviceThisConnectionDidNotAcquireIsNotLocked() {
        greet(session, CLIENT_NAME);

        session.onMessage(setCalibration(REQUEST_ID, FS_RMS_LEFT, FS_RMS_RIGHT));

        assertTrue(channel.responseTo(REQUEST_ID).getError().is(ErrorCode.NOT_LOCKED),
                "spec 4.3: a calibration changes what every measurement on that "
                        + "device MEANS, so it needs the device - the same rule the "
                        + "analyzer's range writes follow");
        assertNull(catalog.calibration(new DeviceLock(AudioBackendType.QA40X,
                DEVICE_INDEX, true), DEVICE_NAME), "and nothing was written");
    }

    @Test
    void aCalibrationUnderTheLockIsStoredAndBroadcastToEveryClient() {
        greet(session, CLIENT_NAME);
        acquire(session, REQUEST_ID, true);
        // The ev.devices.changed fan-out is subscribed to the registry's change
        // list (WsFront does exactly this), so counting fires here IS the
        // broadcast path a lock change uses.
        AtomicInteger broadcasts = new AtomicInteger();
        locks.addChangeListener(broadcasts::incrementAndGet);

        session.onMessage(setCalibration(SECOND_REQUEST_ID, FS_RMS_LEFT, FS_RMS_RIGHT));

        assertTrue(channel.responseTo(SECOND_REQUEST_ID).isOk());
        DeviceCalibration stored = catalog.calibration(
                new DeviceLock(AudioBackendType.QA40X, DEVICE_INDEX, true), DEVICE_NAME);
        assertNotNull(stored, "the server's own card now carries it");
        assertEquals(FS_RMS_LEFT, stored.fsRmsLeft(), EPS);
        assertEquals(FS_RMS_RIGHT, stored.fsRmsRight(), EPS);
        assertEquals(1, broadcasts.get(),
                "every client's cal view refreshes without asking (spec 4.3)");
    }

    // ── device.setCard / cards.list - spec 4.3 ───────────────────────────────

    @Test
    void bindingACardOnADeviceThisConnectionDidNotAcquireIsNotLocked() {
        cards.card(BINDABLE_CARD, DEVICE_NAME, true, FS_RMS_LEFT, FS_RMS_RIGHT, false);
        greet(session, CLIENT_NAME);

        session.onMessage(setCard(REQUEST_ID, BINDABLE_CARD));

        assertTrue(channel.responseTo(REQUEST_ID).getError().is(ErrorCode.NOT_LOCKED),
                "spec 4.3: the binding decides which calibration is in force, so it "
                        + "needs the device - the same rule the calibration write follows");
        assertNull(cards.getPrefs().boundCardName(DEVICE_NAME),
                "and no BINDING was recorded - the card in force stays the match "
                        + "rule's answer, untouched by the refused write");
    }

    @Test
    void bindingACardOnAnIndexThatMovedIsStale() {
        cards.card(BINDABLE_CARD, DEVICE_NAME, true, FS_RMS_LEFT, FS_RMS_RIGHT, false);
        greet(session, CLIENT_NAME);
        acquire(session, REQUEST_ID, true);

        session.onMessage(new NetMessage(MessageType.DEVICE_SET_CARD, SECOND_REQUEST_ID)
                .put(NetFields.BACKEND, AudioBackendType.QA40X.name())
                .put(NetFields.INDEX, DEVICE_INDEX)
                .put(NetFields.INPUT, true)
                .put(NetFields.NAME, "Some other device")
                .put(NetFields.CARD, BINDABLE_CARD));

        assertTrue(channel.responseTo(SECOND_REQUEST_ID).getError().is(ErrorCode.DEVICE_STALE),
                "a binding accepted for whatever device now sits at that index would "
                        + "mis-scale every later measurement on it, silently");
        assertNull(cards.getPrefs().boundCardName(DEVICE_NAME), "nothing recorded");
    }

    @Test
    void bindingACardTheServerDoesNotHaveIsABadRequest() {
        greet(session, CLIENT_NAME);
        acquire(session, REQUEST_ID, true);

        session.onMessage(setCard(SECOND_REQUEST_ID, "No Such Card"));

        assertTrue(channel.responseTo(SECOND_REQUEST_ID).getError().is(ErrorCode.BAD_REQUEST),
                "a binding may only name a card that exists - a typo would quietly "
                        + "uncalibrate the device");
        assertNull(catalog.boundCard(DEVICE_NAME), "and NOTHING is recorded");
    }

    @Test
    void aCardBindingUnderTheLockIsStoredAndBroadcastToEveryClient() {
        cards.card(BINDABLE_CARD, DEVICE_NAME, true, FS_RMS_LEFT, FS_RMS_RIGHT, false);
        greet(session, CLIENT_NAME);
        acquire(session, REQUEST_ID, true);
        AtomicInteger broadcasts = new AtomicInteger();
        locks.addChangeListener(broadcasts::incrementAndGet);

        session.onMessage(setCard(SECOND_REQUEST_ID, BINDABLE_CARD));

        assertTrue(channel.responseTo(SECOND_REQUEST_ID).isOk(),
                "spec 4.3: response data none - a bare ack");
        assertEquals(BINDABLE_CARD, catalog.boundCard(DEVICE_NAME),
                "the choice is the server's from then on");
        assertEquals(1, broadcasts.get(),
                "every client's card and cal view refreshes without asking");
    }

    /** The QA402-vs-QA403 pick is the case the message exists for, and those cards
     *  are exactly the ones whose values come from the analyzer - so unlike
     *  {@code device.setCalibration}, this is accepted for them. */
    @Test
    void aDeviceCalibratedCardIsStillBindableOverTheWire() {
        cards.card(DEVICE_PROVIDED_CARD, DEVICE_NAME, true, FS_RMS_LEFT, FS_RMS_RIGHT, true);
        greet(session, CLIENT_NAME);
        acquire(session, REQUEST_ID, true);

        session.onMessage(setCard(SECOND_REQUEST_ID, DEVICE_PROVIDED_CARD));

        assertTrue(channel.responseTo(SECOND_REQUEST_ID).isOk(),
                "binding CHOOSES a card, it writes no values into one");
        assertEquals(DEVICE_PROVIDED_CARD, catalog.boundCard(DEVICE_NAME));
    }

    // ── cards.put - spec 4.3 v1.1 ────────────────────────────────────────────

    /**
     * A client hands the bench a whole card: it is stored, whole, and NOTHING is
     * in force differently until a {@code device.setCard} binds it - hence no lock
     * and no broadcast, which is the pair of properties that make this verb safe
     * to send before the operator has taken anything.
     */
    @Test
    void aCardIsPutOnTheBenchWithoutALockAndWithoutABroadcast() {
        greet(session, CLIENT_NAME);
        AtomicInteger broadcasts = new AtomicInteger();
        locks.addChangeListener(broadcasts::incrementAndGet);

        session.onMessage(cardsPut(REQUEST_ID, BINDABLE_CARD, false));

        assertTrue(channel.responseTo(REQUEST_ID).isOk(),
                "no lock: a card nothing is bound to is in force nowhere");
        AudioDeviceProfile stored = cards.getPrefs().findAudioDeviceProfile(BINDABLE_CARD);
        assertNotNull(stored, "the bench stores it where the device is plugged in");
        assertEquals(2, stored.getInput().getRanges().size(),
                "WHOLE: the range table is what makes the values mean something");
        assertEquals(PUT_RANGE_LABEL, stored.getInput().getActiveRange());
        assertEquals(LOW_RANGE_FS, stored.getInput().getRanges().get(0).getFsLeft(), EPS);
        assertEquals(0, broadcasts.get(),
                "no device's cal moved, so no client has anything to re-read");
    }

    @Test
    void aCardNameTheBenchAlreadyHasIsABadRequest() {
        cards.card(BINDABLE_CARD, DEVICE_NAME, true, FS_RMS_LEFT, FS_RMS_RIGHT, false);
        greet(session, CLIENT_NAME);

        session.onMessage(cardsPut(REQUEST_ID, BINDABLE_CARD, false));

        assertTrue(channel.responseTo(REQUEST_ID).getError().is(ErrorCode.BAD_REQUEST),
                "cards.put creates - a silent overwrite would discard whatever "
                        + "somebody else calibrated on this bench");
        assertEquals(FS_RMS_LEFT, cards.getPrefs()
                .deviceCalibration(DEVICE_NAME, true).fsRmsLeft(), EPS,
                "and the card that was here is untouched");
    }

    @Test
    void aDeviceCalibratedCardCannotBeInventedByAClient() {
        greet(session, CLIENT_NAME);

        session.onMessage(cardsPut(REQUEST_ID, DEVICE_PROVIDED_CARD, true));

        assertTrue(channel.responseTo(REQUEST_ID).getError().is(ErrorCode.BAD_REQUEST),
                "an analyzer's own values are read where the analyzer is plugged in");
        assertNull(cards.getPrefs().findAudioDeviceProfile(DEVICE_PROVIDED_CARD));
    }

    /**
     * The fallback when the operator declines to author a card, over the wire:
     * the first calibration written to a device the bench has no card for
     * creates one by itself - which is why "create an empty card" needs no verb of
     * its own.
     */
    @Test
    void aCalibrationOnADeviceWithNoCardCreatesTheCardOnTheBench() {
        greet(session, CLIENT_NAME);
        acquire(session, REQUEST_ID, true);
        assertNull(catalog.boundCard(DEVICE_NAME), "the bench starts with no card");

        session.onMessage(setCalibration(SECOND_REQUEST_ID, FS_RMS_LEFT, FS_RMS_RIGHT));

        assertTrue(channel.responseTo(SECOND_REQUEST_ID).isOk());
        assertNotNull(catalog.boundCard(DEVICE_NAME),
                "the write created the card it needed, on the bench");
        assertEquals(FS_RMS_LEFT, catalog.calibration(
                new DeviceLock(AudioBackendType.QA40X, DEVICE_INDEX, true), DEVICE_NAME)
                .fsRmsLeft(), EPS);
    }

    // ── device.setActiveRange - spec 4.3 v1.1 ────────────────────────────────

    /** Which row is active IS the full scale in force, so the write is gated and
     *  broadcast exactly like a calibration - and the {@code cal} every client
     *  reads afterwards is the NEW row's. */
    @Test
    void movingTheActiveRangeChangesTheFullScaleInForceAndTellsEveryClient() {
        cards.rangedCard(BINDABLE_CARD, DEVICE_NAME, true, PUT_RANGE_LABEL, LOW_RANGE_FS,
                OTHER_PUT_RANGE_LABEL, HIGH_RANGE_FS);
        greet(session, CLIENT_NAME);
        acquire(session, REQUEST_ID, true);
        AtomicInteger broadcasts = new AtomicInteger();
        locks.addChangeListener(broadcasts::incrementAndGet);

        session.onMessage(setActiveRange(SECOND_REQUEST_ID, OTHER_PUT_RANGE_LABEL, null));

        assertTrue(channel.responseTo(SECOND_REQUEST_ID).isOk());
        assertEquals(HIGH_RANGE_FS, catalog.calibration(
                new DeviceLock(AudioBackendType.QA40X, DEVICE_INDEX, true), DEVICE_NAME)
                .fsRmsLeft(), EPS, "the row the operator chose is the one in force");
        assertEquals(1, broadcasts.get());
    }

    @Test
    void movingTheActiveRangeWithoutTheLockIsNotLocked() {
        cards.rangedCard(BINDABLE_CARD, DEVICE_NAME, true, PUT_RANGE_LABEL, LOW_RANGE_FS,
                OTHER_PUT_RANGE_LABEL, HIGH_RANGE_FS);
        greet(session, CLIENT_NAME);

        session.onMessage(setActiveRange(REQUEST_ID, OTHER_PUT_RANGE_LABEL, null));

        assertTrue(channel.responseTo(REQUEST_ID).getError().is(ErrorCode.NOT_LOCKED),
                "it changes what every measurement on that device means");
        assertEquals(LOW_RANGE_FS, catalog.calibration(
                new DeviceLock(AudioBackendType.QA40X, DEVICE_INDEX, true), DEVICE_NAME)
                .fsRmsLeft(), EPS);
    }

    @Test
    void aRangeTheCardDoesNotHaveIsABadRequest() {
        cards.rangedCard(BINDABLE_CARD, DEVICE_NAME, true, PUT_RANGE_LABEL, LOW_RANGE_FS,
                OTHER_PUT_RANGE_LABEL, HIGH_RANGE_FS);
        greet(session, CLIENT_NAME);
        acquire(session, REQUEST_ID, true);

        session.onMessage(setActiveRange(SECOND_REQUEST_ID, "no such row", null));

        assertTrue(channel.responseTo(SECOND_REQUEST_ID).getError().is(ErrorCode.BAD_REQUEST),
                "a marker pointing at nothing would fall back to the first row and "
                        + "mis-scale everything measured after it");
        assertEquals(LOW_RANGE_FS, catalog.calibration(
                new DeviceLock(AudioBackendType.QA40X, DEVICE_INDEX, true), DEVICE_NAME)
                .fsRmsLeft(), EPS);
    }

    @Test
    void aChannelScopeThisBuildDoesNotKnowIsRefusedRatherThanGuessed() {
        cards.rangedCard(BINDABLE_CARD, DEVICE_NAME, true, PUT_RANGE_LABEL, LOW_RANGE_FS,
                OTHER_PUT_RANGE_LABEL, HIGH_RANGE_FS);
        greet(session, CLIENT_NAME);
        acquire(session, REQUEST_ID, true);

        session.onMessage(setActiveRange(SECOND_REQUEST_ID, OTHER_PUT_RANGE_LABEL, "middle"));

        assertTrue(channel.responseTo(SECOND_REQUEST_ID).getError().is(ErrorCode.BAD_REQUEST),
                "guessing which channel to move is how one channel ends up scaled "
                        + "against another channel's range");
    }

    @Test
    void theCardListNeedsNoLockAndSaysWhatEachCardCanServe() {
        cards.card(BINDABLE_CARD, DEVICE_NAME, true, FS_RMS_LEFT, FS_RMS_RIGHT, false);
        greet(session, CLIENT_NAME);

        session.onMessage(new NetMessage(MessageType.CARDS_LIST, REQUEST_ID));

        NetMessage answer = channel.responseTo(REQUEST_ID);
        assertTrue(answer.isOk(), "choosing is a decision made BEFORE taking anything, "
                + "so needing the device to see the choices would be backwards");
        JsonNode offered = answer.getData().path(NetFields.CARDS);
        assertEquals(1, offered.size());
        assertEquals(BINDABLE_CARD, offered.get(0).path(NetFields.NAME).asText());
        assertTrue(offered.get(0).path(NetFields.INPUT).asBoolean(),
                "the card holds an input row");
        assertFalse(offered.get(0).path(NetFields.OUTPUT).asBoolean(),
                "and none for the output, which the chooser must not offer it for");
        assertNull(cards.getPrefs().boundCardName(DEVICE_NAME),
                "reading the chooser binds nothing");
    }

    /**
     * The lock spec 4.3 asks for is the lock on THAT device, not on any device of
     * the backend - which is spec 4.6's weaker rule for a backend's own settings.
     * A client holding one device and calibrating another is exactly what a
     * client-side lock hunt that tries inputs first produces for an OUTPUT write,
     * and it must be refused rather than written onto a device nobody holds.
     */
    @Test
    void holdingANOTHERDeviceOfTheSameBackendIsStillNotLocked() {
        greet(session, CLIENT_NAME);
        session.onMessage(new NetMessage(MessageType.DEVICE_ACQUIRE, REQUEST_ID)
                .put(NetFields.BACKEND, AudioBackendType.QA40X.name())
                .put(NetFields.INDEX, SECOND_DEVICE_INDEX)
                .put(NetFields.INPUT, true)
                .put(NetFields.NAME, SECOND_DEVICE_NAME));
        assertTrue(channel.responseTo(REQUEST_ID).isOk(), "the other device was taken");

        session.onMessage(setCalibration(SECOND_REQUEST_ID, FS_RMS_LEFT, FS_RMS_RIGHT));

        assertTrue(channel.responseTo(SECOND_REQUEST_ID).getError().is(ErrorCode.NOT_LOCKED),
                "one device's lock says nothing about what another device measures");
        assertNull(catalog.calibration(new DeviceLock(AudioBackendType.QA40X,
                DEVICE_INDEX, true), DEVICE_NAME), "and nothing was written");
    }

    @Test
    void aCalibrationMissingOneChannelIsABadRequest() {
        greet(session, CLIENT_NAME);
        acquire(session, REQUEST_ID, true);

        session.onMessage(deviceRef(MessageType.DEVICE_SET_CALIBRATION, SECOND_REQUEST_ID,
                true).put(NetFields.FS_RMS_LEFT, FS_RMS_LEFT));

        assertTrue(channel.responseTo(SECOND_REQUEST_ID).getError().is(ErrorCode.BAD_REQUEST),
                "half a calibration would leave the other channel at whatever the "
                        + "card happened to hold");
    }

    @Test
    void aDeviceThatReadsItsOwnCalibrationRefusesTheWrite() {
        cards.card(DEVICE_PROVIDED_CARD, DEVICE_NAME, true, FS_RMS_LEFT, FS_RMS_RIGHT, true);
        greet(session, CLIENT_NAME);
        acquire(session, REQUEST_ID, true);

        session.onMessage(setCalibration(SECOND_REQUEST_ID, 9.0, 9.0));

        assertTrue(channel.responseTo(SECOND_REQUEST_ID).getError().is(ErrorCode.BAD_REQUEST),
                "the QA40x's full scales come from its EEPROM (spec 4.3)");
        assertEquals(FS_RMS_LEFT, catalog.calibration(new DeviceLock(
                AudioBackendType.QA40X, DEVICE_INDEX, true), DEVICE_NAME).fsRmsLeft(), EPS);
    }

    /**
     * The range in force IS the device's full scale, so moving the attenuator has
     * to reach every client the same way a lock change does (spec 4.3's
     * {@code ev.devices.changed}).
     *
     * <p>The analyzer's card carries one calibrated row per attenuator position
     * and {@code cal} reports whichever is active.  Without the broadcast a client
     * would go on scaling by the range the analyzer has LEFT until something
     * unrelated refreshed its catalogue - the numbers stop meaning volts and
     * nothing on screen says so.
     */
    @Test
    void aRangeChangeTellsEveryClientTheFullScaleNowInForce() {
        cards.rangedCard(StubDeviceManager.CARD_NAME, DEVICE_NAME, true,
                rangeLabel(StubDeviceManager.DEFAULT_INPUT_DBV), LOW_RANGE_FS,
                rangeLabel(OTHER_INPUT_DBV), HIGH_RANGE_FS);
        greet(session, CLIENT_NAME);
        acquire(session, REQUEST_ID, true);
        catalog.scan();                       // the enumeration the broadcast overlays
        AtomicInteger broadcasts = new AtomicInteger();
        locks.addChangeListener(broadcasts::incrementAndGet);

        session.onMessage(new NetMessage(MessageType.QA40X_SET_INPUT_RANGE,
                SECOND_REQUEST_ID).put(NetFields.DBV, OTHER_INPUT_DBV));

        assertTrue(channel.responseTo(SECOND_REQUEST_ID).isOk());
        assertEquals(1, broadcasts.get(),
                "spec 4.3's one fan-out: the attenuator moved, so every client's "
                        + "cal view has to be refreshed");
        JsonNode cal = catalog.lastScan().get(0).path(NetFields.DEVICES).get(0)
                .path(NetFields.CAL);
        assertEquals(HIGH_RANGE_FS, cal.path(NetFields.FS_RMS_LEFT).asDouble(), EPS,
                "and the payload carries the NEW range's full scale, read fresh from "
                        + "the card at build time without touching the hardware");
    }

    @Test
    void aCalibrationForANameThatMovedIsStale() {
        greet(session, CLIENT_NAME);
        acquire(session, REQUEST_ID, true);

        session.onMessage(new NetMessage(MessageType.DEVICE_SET_CALIBRATION,
                SECOND_REQUEST_ID)
                .put(NetFields.BACKEND, AudioBackendType.QA40X.name())
                .put(NetFields.INDEX, DEVICE_INDEX)
                .put(NetFields.INPUT, true)
                .put(NetFields.NAME, "Some other card")
                .put(NetFields.FS_RMS_LEFT, FS_RMS_LEFT)
                .put(NetFields.FS_RMS_RIGHT, FS_RMS_RIGHT));

        assertTrue(channel.responseTo(SECOND_REQUEST_ID).getError().is(ErrorCode.DEVICE_STALE),
                "a calibration written onto whatever device now sits at that index "
                        + "would mis-scale every later measurement on it, silently");
    }

    @Test
    void anIncompleteDeviceRefIsABadRequest() {
        greet(session, CLIENT_NAME);

        session.onMessage(new NetMessage(MessageType.DEVICE_ACQUIRE, REQUEST_ID)
                .put(NetFields.BACKEND, AudioBackendType.QA40X.name()));

        assertTrue(channel.responseTo(REQUEST_ID).getError().is(ErrorCode.BAD_REQUEST));
        assertEquals(0, locks.size());
    }

    /** A whole connection on {@code target}, wired the way {@code WsFront} wires
     *  one.  Its streamer and its generator are here so the session has the
     *  collaborators its teardown closes; {@link CaptureStreamerTest} and
     *  {@link GeneratorSessionTest} exercise what they do. */
    private ClientSession sessionOn(FakeChannel target, FakeTicker keepalive,
            FakeWorker requests) {
        return sessionOn(target, keepalive, requests, new FakeTicker());
    }

    /** The same, with the generator's own clock supplied - the seam a teardown
     *  test needs to make the generator's close fault. */
    private ClientSession sessionOn(FakeChannel target, FakeTicker keepalive,
            FakeWorker requests, FakeTicker generatorClock) {
        Qa40xGuard qa40x = new Qa40xGuard(AudioBackend.instance(), locks);
        CaptureStreamer captures = new CaptureStreamer(AudioBackend.instance(), catalog,
                codec, target, new FakeWorker(), qa40x);
        GeneratorSession generator = new GeneratorSession(AudioBackend.instance(), catalog,
                captures, new FileStore(), qa40x, codec, target, requests,
                generatorClock, () -> 0L);
        return new ClientSession(config, locks, qa40x, catalog, captures, generator,
                new Qa40xSession(AudioBackend.instance(), codec,
                        List.of(AudioBackendType.QA40X)), codec, target,
                keepalive, requests);
    }

    /** The capability tokens a hello response advertises (spec 4.1). */
    private List<String> capsOf(NetMessage response) {
        List<String> caps = new ArrayList<>();
        for (JsonNode token : response.getData().path(NetFields.CAPS)) {
            caps.add(token.asText());
        }
        return caps;
    }

    /** The stub bench, for the park count spec 4.1's teardown owes the hardware. */
    private StubDeviceManager manager() {
        return (StubDeviceManager) AudioBackend.instance().manager(AudioBackendType.QA40X);
    }

    private void greet(ClientSession target, String name) {
        target.onMessage(new NetMessage(MessageType.HELLO, HELLO_ID)
                .put(NetFields.PROTO, NetProto.PROTO_VERSION)
                .put(NetFields.PROTO_MIN, NetProto.PROTO_MIN_VERSION)
                .put(NetFields.CLIENT, "Phonalyser desktop test")
                .put(NetFields.NAME, name));
    }

    private void acquire(ClientSession target, int id, boolean input) {
        target.onMessage(deviceRef(MessageType.DEVICE_ACQUIRE, id, input));
    }

    private NetMessage captureOpen(int id) {
        return deviceRef(MessageType.CAPTURE_OPEN, id, true)
                .put(NetFields.RATE, CAPTURE_RATE_HZ)
                .put(NetFields.BITS, CAPTURE_BITS);
    }

    /** How the bench labels a range row in its card - the stub spells it the way
     *  {@code Qa40xProtocol} does. */
    private String rangeLabel(int dbv) {
        return dbv + StubDeviceManager.RANGE_LABEL_SUFFIX;
    }

    private NetMessage setCard(int id, String card) {
        return deviceRef(MessageType.DEVICE_SET_CARD, id, true).put(NetFields.CARD, card);
    }

    /** {@code cards.put} carrying a two-row LINKED card of {@code name} that
     *  recognises this bench's device - the shape a client propagates. */
    private NetMessage cardsPut(int id, String name, boolean fromDevice) {
        AudioDeviceProfile card = new AudioDeviceProfile();
        card.setName(name);
        card.getMatch().add(DEVICE_NAME);
        DeviceEndpointConfig endpoint = card.getInput();
        endpoint.setCalibrationFromDevice(fromDevice);
        endpoint.getRanges().add(range(PUT_RANGE_LABEL, LOW_RANGE_FS));
        endpoint.getRanges().add(range(OTHER_PUT_RANGE_LABEL, HIGH_RANGE_FS));
        endpoint.setActiveRange(PUT_RANGE_LABEL);
        return new NetMessage(MessageType.CARDS_PUT, id)
                .put(NetFields.CONTENT, codec.toNode(cards.getPrefs().cardToMap(card)));
    }

    private DeviceRange range(String label, double fs) {
        DeviceRange row = new DeviceRange();
        row.setLabel(label);
        row.setFsLeft(fs);
        row.setFsRight(fs);
        row.setCalibrated(true);
        return row;
    }

    private NetMessage setActiveRange(int id, String label, String channel) {
        NetMessage request = deviceRef(MessageType.DEVICE_SET_ACTIVE_RANGE, id, true)
                .put(NetFields.RANGE, label);
        return channel == null ? request : request.put(NetFields.CHANNEL, channel);
    }

    private NetMessage setCalibration(int id, double fsRmsLeft, double fsRmsRight) {
        return deviceRef(MessageType.DEVICE_SET_CALIBRATION, id, true)
                .put(NetFields.FS_RMS_LEFT, fsRmsLeft)
                .put(NetFields.FS_RMS_RIGHT, fsRmsRight);
    }

    private NetMessage deviceRef(MessageType type, int id, boolean input) {
        return new NetMessage(type, id)
                .put(NetFields.BACKEND, AudioBackendType.QA40X.name())
                .put(NetFields.INDEX, DEVICE_INDEX)
                .put(NetFields.INPUT, input)
                .put(NetFields.NAME, DEVICE_NAME);
    }
}
