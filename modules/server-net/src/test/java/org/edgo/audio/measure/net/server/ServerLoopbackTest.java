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

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.enums.OutputChannels;
import org.edgo.audio.measure.net.proto.BinaryFrame;
import org.edgo.audio.measure.net.proto.ErrorCode;
import org.edgo.audio.measure.net.proto.FrameType;
import org.edgo.audio.measure.net.proto.JsonCodec;
import org.edgo.audio.measure.net.proto.MessageType;
import org.edgo.audio.measure.net.proto.NetError;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.net.proto.NetMessage;
import org.edgo.audio.measure.net.proto.NetProto;
import org.edgo.audio.measure.sound.AudioBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole bridge, end to end, in one JVM: a real {@link ServerMain} on a real
 * socket, a real {@link LoopbackClient} on the other end of it, and nothing
 * between them but localhost.
 *
 * <p>Every other test in this module drives one collaborator with the rest
 * faked, which is what makes them precise - and is exactly why this one has to
 * exist too: a session that answers correctly to a {@link FakeChannel} says
 * nothing about whether the same bytes survive JSON, a WebSocket frame, a socket
 * and the reverse trip.  What is asserted here is only what CROSSES the wire:
 * the handshake of spec 1, the device and lock vocabulary of spec 4.3, a second
 * of audio arriving byte for byte with unbroken packet numbers (spec 5), the
 * remote generator confirming what it emits (spec 4.5), and the keepalive of
 * spec 4.1 taking a silent client's devices away and telling everyone else.
 *
 * <p><b>The bench is a stub, the server is not.</b>  The two stub providers on
 * this module's test class path register through the same {@code ServiceLoader}
 * contract a real backend module uses, so the server enumerates, opens and
 * streams through its production path - the hardware is the only thing missing,
 * and the test says exactly which bytes it produces.  The server is TOLD to serve
 * both of them: which backends a production server offers is a property of the
 * HOST (a stub loads no {@code libusb} and cannot be an analyzer this host could
 * open), and a bench that appears or vanishes with the machine the tests run on
 * is not a bench to assert against.  Nothing below names one all the same: the
 * test asks {@code devices.list} what is there and measures on that.
 *
 * <p><b>No test sleeps.</b>  Time reaches a session through an injected
 * {@link Ticker}, and this run hands out {@link FakeTicker}s for them, so the
 * 2 s death of spec 4.1 is five method calls instead of two and a half seconds
 * of waiting.  Everything else is waited for as a CONDITION on what arrived,
 * never as a duration.
 */
class ServerLoopbackTest {

    private static final String SERVER_NAME = "Bench loopback";
    private static final String CLIENT_NAME = "Developer's laptop";
    private static final String OBSERVER_NAME = "Bench tablet";
    private static final String CLIENT_APP = "Phonalyser desktop test";
    private static final String LOOPBACK = "127.0.0.1";
    private static final String WS_SCHEME = "ws://";
    private static final String HTTP_SCHEME = "http://";
    private static final String INFO_PATH = "/info";
    /** A fixed port would fail the moment the developer has a server of their
     *  own running, so the OS picks one and the server is asked which. */
    private static final String EPHEMERAL = "0";

    private static final int RATE_HZ = StubDeviceManager.RATE_48K;
    private static final int BITS = StubDeviceManager.BITS_24;
    /** 24-bit stereo: three bytes a sample, two samples a frame. */
    private static final int FRAME_BYTES = 6;
    /** One second of audio, delivered the way a backend delivers it: batches of
     *  100 ms, the upper end of the "~10 - 100 ms" spec 5 names.  Ten of them
     *  also sit comfortably inside the stream's queue bound, so what this test
     *  measures is the transport and not the drop-oldest policy
     *  ({@link CaptureStreamerTest} owns that one). */
    private static final int BATCHES = 10;
    private static final int FRAMES_PER_BATCH = RATE_HZ / BATCHES;
    private static final int BATCH_BYTES = FRAMES_PER_BATCH * FRAME_BYTES;
    private static final int SECOND_BYTES = BATCHES * BATCH_BYTES;
    /** Makes every batch's content differ from every other's, so a dropped,
     *  duplicated or reordered one fails on the bytes and not just on a count. */
    private static final int BATCH_SEED_STEP = 31;

    private static final double TONE_HZ = 1_000.0;
    private static final double TONE_VRMS = 0.5;
    private static final double DAC_FS_VOLTAGE_AMPL = 1.0;
    private static final double NO_DITHER_BITS = 0.0;
    /** Zero-crossing counting over a second of samples resolves about 1 Hz. */
    private static final double TONE_TOLERANCE_HZ = 2.0;
    private static final double EXACT = 0.0;
    /** How long any wait on the loopback may take before it counts as a hang. */
    private static final long AWAIT_MS = 10_000;
    /** Two state pushes: one for the {@code gen.config}, one for the
     *  {@code gen.start} (spec 4.5 - "pushed on every state change"). */
    private static final int STATES_AFTER_START = 2;

    private final JsonCodec codec = new JsonCodec();
    /** This bench's device cards, empty and its own: a {@code device.setCalibration}
     *  PERSISTS (spec 4.3), and a test that drove the singleton would rewrite the
     *  developer's {@code devices.yaml}. */
    private final StubCardStore cards = new StubCardStore();
    /** Every session ticker this server handed out, in creation order. */
    private final List<FakeTicker> sessionTickers = new CopyOnWriteArrayList<>();
    private final List<LoopbackClient> clients = new ArrayList<>();

    private ServerConfig config;
    private ServerMain server;
    private URI wsUri;

    @BeforeEach
    void startTheServer() {
        config = new ServerConfig(new String[] {"--name", SERVER_NAME,
                "--port", EPHEMERAL});
        // The sound card first, so devices.list keeps the order the bench below
        // is read from; the analyzer is served beside it because spec 4.1's
        // qa40x token has to be a promise this server really keeps.
        server = new ServerMain(config, this::sessionTicker,
                List.of(AudioBackendType.JAVASOUND, AudioBackendType.QA40X),
                cards.getPrefs());
        server.start();
        wsUri = URI.create(WS_SCHEME + LOOPBACK + ":" + server.getPort());
    }

    @AfterEach
    void stopTheServer() {
        for (LoopbackClient client : clients) {
            client.close();
        }
        server.stop();
    }

    /**
     * ONE port, both planes.  A live control channel and a {@code GET /info}
     * answered on the SAME bound number is the whole of the merge: two ports
     * were two firewall rules, two fields in every beacon, and a browser served
     * the bundle from one of them had to be told the other.
     *
     * <p>The session is greeted BEFORE the HTTP request and asked to answer a
     * ping AFTER it, so what is proved is that they coexist on that port - not
     * merely that each works when the other is idle.
     */
    @Test
    void oneBoundPortAnswersHttpAndCarriesTheControlChannelAtTheSameTime()
            throws Exception {
        LoopbackClient client = open();
        greet(client, CLIENT_NAME);
        int port = server.getPort();

        HttpResponse<String> info = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(HTTP_SCHEME + LOOPBACK + ":" + port
                        + INFO_PATH)).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(HttpURLConnection.HTTP_OK, info.statusCode(),
                "the port the WebSocket was upgraded on serves spec 3 as well");
        JsonNode data = codec.read(info.body()).getRoot();
        assertEquals(config.getServerId(), data.path(NetFields.SERVER_ID).asText());
        assertEquals(port, data.path(NetFields.PORT).asInt(),
                "spec 3: /info hands back the ONE port, which is the one this answer "
                        + "arrived on and the one the open session is running over");
        assertEquals(port, wsUri.getPort());
        assertTrue(client.request(client.newRequest(MessageType.PING)).isOk(),
                "and the session that was open before the HTTP request is still open "
                        + "after it");
    }

    @Test
    void aCaptureSessionRunsOverOneSocketAndTheAudioArrivesByteForByte() throws Exception {
        LoopbackClient client = open();
        NetMessage hello = greet(client, CLIENT_NAME);
        assertEquals(config.getServerId(), hello.getData().path(NetFields.SERVER_ID).asText());
        assertEquals(SERVER_NAME, hello.getData().path(NetFields.NAME).asText());
        assertEquals(config.getApp(), hello.getData().path(NetFields.APP).asText());
        assertEquals(List.of(NetFields.CAP_GEN, NetFields.CAP_FILES, NetFields.CAP_QA40X),
                capsOf(hello),
                "spec 4.1: a cap is a promise the commands behind it work - this bench "
                        + "answers the analyzer surface of spec 4.6 as well");

        Bench bench = benchOf(client);
        assertTrue(acquire(client, bench.input()).isOk(),
                "spec 4.4: streaming requires the input lock");

        NetMessage opened = client.request(bench.input()
                .into(client.newRequest(MessageType.CAPTURE_OPEN))
                .put(NetFields.RATE, RATE_HZ)
                .put(NetFields.BITS, BITS));
        assertTrue(opened.isOk());
        int captureId = opened.getData().path(NetFields.CAPTURE_ID).asInt();
        assertEquals(RATE_HZ, opened.getData().path(NetFields.RATE).asInt(),
                "spec 4.4: the client re-pins to the format actually granted");
        assertEquals(BITS, opened.getData().path(NetFields.BITS).asInt());
        assertEquals(2, opened.getData().path(NetFields.CHANNELS).asInt());
        assertEquals(FRAME_BYTES, opened.getData().path(NetFields.FRAME_BYTES).asInt());

        StubCapture capture = stubBench(bench).getLastCapture();
        assertTrue(client.request(client.newRequest(MessageType.CAPTURE_START)
                .put(NetFields.CAPTURE_ID, captureId)).isOk());
        byte[] recorded = new byte[SECOND_BYTES];
        for (int i = 0; i < BATCHES; i++) {
            byte[] batch = batch(i);
            System.arraycopy(batch, 0, recorded, i * BATCH_BYTES, BATCH_BYTES);
            capture.feed(batch);
        }

        List<BinaryFrame> frames = client.awaitFrames(BATCHES, AWAIT_MS);
        byte[] delivered = new byte[SECOND_BYTES];
        for (int i = 0; i < BATCHES; i++) {
            BinaryFrame frame = frames.get(i);
            assertEquals(FrameType.PCM, frame.type(),
                    "a second of audio the server never had to drop: no GAP frame");
            assertEquals(captureId, frame.streamId(), "spec 5: streamId IS the captureId");
            assertEquals(i, frame.packetCounter(),
                    "spec 5: per stream, starts at 0, +1 per frame - a jump would be "
                            + "transport loss, which the client treats as a protocol error");
            assertEquals(BATCH_BYTES, frame.n(), "PCM n is the payload byte count");
            System.arraycopy(frame.payload(), 0, delivered, i * BATCH_BYTES, BATCH_BYTES);
        }
        assertArrayEquals(recorded, delivered,
                "spec 4.4: the payload is the NATIVE PCM byte stream of the server-side "
                        + "capture, so the client decodes it with the same code path a "
                        + "local device feeds - one wrong byte is a wrong measurement");

        assertTrue(client.request(client.newRequest(MessageType.CAPTURE_STOP)
                .put(NetFields.CAPTURE_ID, captureId)).isOk());
        assertTrue(client.request(client.newRequest(MessageType.CAPTURE_CLOSE)
                .put(NetFields.CAPTURE_ID, captureId)).isOk());
        assertTrue(capture.isClosed(), "spec 4.4: the device line goes back");
        assertTrue(client.request(bench.input()
                .into(client.newRequest(MessageType.DEVICE_RELEASE))).isOk());
    }

    @Test
    void theRemoteGeneratorIsDrivenFromTheClientAndConfirmsWhatItEmits() throws Exception {
        LoopbackClient client = open();
        greet(client, CLIENT_NAME);
        Bench bench = benchOf(client);
        assertTrue(acquire(client, bench.output()).isOk(),
                "spec 4.5: every gen.* needs the output lock");

        NetMessage opened = client.request(bench.output()
                .into(client.newRequest(MessageType.GEN_OPEN))
                .put(NetFields.RATE, RATE_HZ)
                .put(NetFields.BITS, BITS)
                .put(NetFields.DITHER_BITS, NO_DITHER_BITS)
                .put(NetFields.OUTPUT_CHANNELS, OutputChannels.BOTH.name()));
        assertTrue(opened.isOk());
        int genId = opened.getData().path(NetFields.GEN_ID).asInt();
        assertEquals(RATE_HZ, opened.getData().path(NetFields.RATE).asInt());

        assertTrue(client.request(client.newRequest(MessageType.GEN_CONFIG)
                .put(NetFields.GEN_ID, genId)
                .put(NetFields.FORM, GenSignalForm.SINE.name())
                .put(NetFields.FREQUENCY, TONE_HZ)
                .put(NetFields.AMPLITUDE_VRMS, TONE_VRMS)
                .put(NetFields.DAC_FS_VOLTAGE_AMPL, DAC_FS_VOLTAGE_AMPL)).isOk());
        assertTrue(client.request(client.newRequest(MessageType.GEN_START)
                .put(NetFields.GEN_ID, genId)).isOk());

        NetMessage state = client.awaitEvent(MessageType.EV_GEN_STATE, STATES_AFTER_START,
                AWAIT_MS);
        assertEquals(genId, state.getNode(NetFields.GEN_ID).asInt());
        assertTrue(state.getNode(NetFields.RUNNING).asBoolean(),
                "spec 4.5: ev.gen.state is pushed on every state change");
        assertEquals(GenSignalForm.SINE.name(), state.getNode(NetFields.FORM).asText());
        assertEquals(TONE_HZ, state.getNode(NetFields.EMIT_HZ).asDouble(), EXACT,
                "spec 4.5: emitHz is the post-snap, post-trim frequency actually "
                        + "emitted - what the client's hint plumbing feeds from");

        StubPlayback playback = stubBench(bench).getLastPlayback();
        assertTrue(playback.isOpened());
        assertEquals(1, playback.getPlayCount());
        assertEquals(TONE_HZ, playback.measuredHz(RATE_HZ), TONE_TOLERANCE_HZ,
                "the numbers did not just reach the server's commanded state - they "
                        + "reached the DDS, which is the only thing the DAC hears");

        assertTrue(client.request(client.newRequest(MessageType.GEN_STOP)
                .put(NetFields.GEN_ID, genId)).isOk());
        assertTrue(client.request(client.newRequest(MessageType.GEN_CLOSE)
                .put(NetFields.GEN_ID, genId)).isOk());
        assertTrue(playback.isClosed(), "spec 4.5: gen.close gives the output line back");
        assertTrue(client.request(bench.output()
                .into(client.newRequest(MessageType.DEVICE_RELEASE))).isOk());
    }

    @Test
    void aClientThatStopsAnsweringLosesItsDevicesAndEveryOtherClientIsTold()
            throws Exception {
        LoopbackClient dying = open();
        greet(dying, CLIENT_NAME);
        Bench bench = benchOf(dying);
        assertTrue(acquire(dying, bench.input()).isOk());
        NetMessage opened = dying.request(bench.input()
                .into(dying.newRequest(MessageType.CAPTURE_OPEN))
                .put(NetFields.RATE, RATE_HZ)
                .put(NetFields.BITS, BITS));
        assertTrue(opened.isOk());
        StubCapture capture = stubBench(bench).getLastCapture();
        assertTrue(dying.request(dying.newRequest(MessageType.CAPTURE_START)
                .put(NetFields.CAPTURE_ID,
                        opened.getData().path(NetFields.CAPTURE_ID).asInt())).isOk());
        // Taken while this is the only connection, so there is no doubt whose
        // keepalive it is.
        FakeTicker keepalive = theOnlyKeepalive();

        LoopbackClient observer = open();
        greet(observer, OBSERVER_NAME);
        NetError refused = acquire(observer, bench.input()).getError();
        assertTrue(refused.is(ErrorCode.DEVICE_LOCKED));
        assertEquals(CLIENT_NAME, refused.by(), "spec 4.3: the refusal names the holder");
        int told = observer.events(MessageType.EV_DEVICES_CHANGED).size();

        keepalive.advance(NetProto.MAX_MISSED_PINGS);
        dying.awaitServerPings(NetProto.MAX_MISSED_PINGS, AWAIT_MS);
        // The answers went out ahead of this request on the same socket, so the
        // response to it means they have all been counted.
        assertTrue(dying.request(dying.newRequest(MessageType.PING)).isOk());
        assertEquals(-1, dying.serverPings().get(0).getId().intValue(),
                "spec 4.0: server request ids are negative so they cannot collide");
        assertFalse(dying.isHungUp(),
                "spec 4.1 counts CONSECUTIVE unanswered pings, and this client answered "
                        + "every one of them");

        dying.stopAnsweringPings();
        keepalive.advance(NetProto.MAX_MISSED_PINGS + 1);

        assertTrue(dying.awaitHangUp(AWAIT_MS),
                "spec 4.1: four unanswered pings (2 s) and the connection is dead");
        NetMessage changed = observer.awaitEvent(MessageType.EV_DEVICES_CHANGED, told + 1,
                AWAIT_MS);
        assertTrue(lockOf(changed, bench.input()).isNull(),
                "spec 6: the other clients see the device free again");
        assertTrue(capture.isClosed(),
                "and it was free only AFTER the line went back - spec 4.1's teardown in "
                        + "its one order (streams, generator, locks, park), which is why "
                        + "the broadcast arriving is proof the close already happened");
        assertTrue(acquire(observer, bench.input()).isOk(),
                "the device really is available, not merely announced as such");
    }

    /**
     * The client that is simply GONE: its process halted, its socket dropped,
     * with devices in its name.
     *
     * <p>Nothing of the protocol announces it - no {@code bye}, no WebSocket
     * close frame - so the server has only the transport ending to go on.  A
     * lock left behind by such a client is a device no other connection acquires
     * again short of restarting the server, and the clients still connected have
     * to be told it is free rather than discovering it at their next acquire.
     *
     * <p>No clock is turned here, on purpose.  The keepalive of spec 4.1 reaches
     * the same end two seconds later, and advancing it would prove the keepalive
     * a second time
     * ({@link #aClientThatStopsAnsweringLosesItsDevicesAndEveryOtherClientIsTold}
     * owns that path) instead of what this one is about: the socket dying is
     * enough, by itself.
     */
    @Test
    void aClientThatVanishesWithoutAGoodbyeLosesItsLocksAndEveryOtherClientIsTold()
            throws Exception {
        LoopbackClient dying = open();
        greet(dying, CLIENT_NAME);
        Bench bench = benchOf(dying);
        assertTrue(acquire(dying, bench.input()).isOk());
        assertTrue(acquire(dying, bench.output()).isOk(),
                "both directions, so a teardown that gave one back and forgot the "
                        + "other is visible");

        LoopbackClient observer = open();
        greet(observer, OBSERVER_NAME);
        NetError refused = acquire(observer, bench.input()).getError();
        assertTrue(refused.is(ErrorCode.DEVICE_LOCKED));
        assertEquals(CLIENT_NAME, refused.by(), "spec 4.3: the refusal names the holder");
        int told = observer.events(MessageType.EV_DEVICES_CHANGED).size();

        dying.die();

        NetMessage changed = observer.awaitEvent(MessageType.EV_DEVICES_CHANGED, told + 1,
                AWAIT_MS);
        assertTrue(lockOf(changed, bench.input()).isNull(),
                "spec 4.3: every lock change is broadcast, and a connection dying is "
                        + "the change the other clients are waiting for");
        assertTrue(lockOf(changed, bench.output()).isNull(),
                "both of the dead client's locks come back, not merely the first");
        assertTrue(acquire(observer, bench.input()).isOk(),
                "and the devices really are available, not merely announced as such");
        assertTrue(acquire(observer, bench.output()).isOk());
    }

    /**
     * The same teardown reached the orderly way: the client says {@code bye}
     * (spec 4.1), is answered, and its devices are back with everyone else told.
     *
     * <p>It is worth its own test beside the two deaths because the ORDER
     * differs and the paths meet only at the end: here the session tears itself
     * down from its own request thread while the socket is still healthy, and
     * the response has to have gone out before the connection does.
     */
    @Test
    void aClientThatSaysGoodbyeLosesItsLocksAndEveryOtherClientIsTold() throws Exception {
        LoopbackClient leaving = open();
        greet(leaving, CLIENT_NAME);
        Bench bench = benchOf(leaving);
        assertTrue(acquire(leaving, bench.input()).isOk());
        assertTrue(acquire(leaving, bench.output()).isOk());

        LoopbackClient observer = open();
        greet(observer, OBSERVER_NAME);
        assertTrue(acquire(observer, bench.output()).getError().is(ErrorCode.DEVICE_LOCKED));
        int told = observer.events(MessageType.EV_DEVICES_CHANGED).size();

        assertTrue(leaving.request(leaving.newRequest(MessageType.BYE)).isOk(),
                "spec 4.1: the answer goes out before the socket does");
        assertTrue(leaving.awaitHangUp(AWAIT_MS), "and then the server hangs up");

        NetMessage changed = observer.awaitEvent(MessageType.EV_DEVICES_CHANGED, told + 1,
                AWAIT_MS);
        assertTrue(lockOf(changed, bench.input()).isNull());
        assertTrue(lockOf(changed, bench.output()).isNull());
        assertTrue(acquire(observer, bench.input()).isOk());
        assertTrue(acquire(observer, bench.output()).isOk());
    }

    /**
     * Spec 4.3's {@code lock} object in the FIRST payload a client is given, and
     * not only in the events that follow it.
     *
     * <p>A client that arrives after somebody else has taken a device has never
     * seen an {@code ev.devices.changed} for that lock and never will - the
     * change happened before it connected.  If the initial payload left the
     * holder out, its combo would offer a device that is not there to be had,
     * and the operator would learn who has it from a {@code DEVICE_LOCKED}
     * refusal instead of from the list.
     *
     * <p>BOTH initial payloads are asserted, because a client fills its combos
     * from either: the {@code devices.list} array, and the single backend entry
     * {@code backend.select} answers with - which spec 4.3 shapes that way
     * precisely so one round-trip fills them.
     */
    @Test
    void aClientArrivingLaterIsToldWhoHoldsTheDeviceByItsVeryFirstPayload()
            throws Exception {
        LoopbackClient holder = open();
        greet(holder, CLIENT_NAME);
        Bench bench = benchOf(holder);
        assertTrue(acquire(holder, bench.input()).isOk());

        LoopbackClient arriving = open();
        greet(arriving, OBSERVER_NAME);

        NetMessage list = arriving.request(arriving.newRequest(MessageType.DEVICES_LIST));
        assertTrue(list.isOk());
        JsonNode backends = list.getData().path(NetFields.BACKENDS);
        assertEquals(CLIENT_NAME, lockIn(backends, bench.input()).path(NetFields.BY).asText(),
                "spec 4.3: the very first devices.list names the client holding the "
                        + "device, so an arrival that missed every event still shows it "
                        + "as taken");
        assertTrue(lockIn(backends, bench.output()).isNull(),
                "and the device nobody took is free in that same answer");

        NetMessage selected = arriving.request(arriving.newRequest(MessageType.BACKEND_SELECT)
                .put(NetFields.BACKEND, bench.input().backend()));
        assertTrue(selected.isOk());
        assertEquals(CLIENT_NAME,
                lockInEntry(selected.getData(), bench.input()).path(NetFields.BY).asText(),
                "and so does the backend.select entry, which is the one round-trip a "
                        + "desktop client fills its device combos from");
    }

    // -------------------------------------------------------------------------
    // The server under test
    // -------------------------------------------------------------------------

    /**
     * A session clock this test can turn by hand.  The server's own scheduler is
     * ignored on purpose: what it would do - fire every 500 ms - is precisely
     * what makes a keepalive test slow and flaky.
     */
    private Ticker sessionTicker(ScheduledExecutorService unusedScheduler) {
        FakeTicker ticker = new FakeTicker();
        sessionTickers.add(ticker);
        return ticker;
    }

    /**
     * The keepalive of the only connection open so far: the one session ticker
     * started at the 500 ms of spec 4.1.  A generator's position clock runs at
     * 100 ms and only while something plays, so it cannot be mistaken for one.
     */
    private FakeTicker theOnlyKeepalive() {
        List<FakeTicker> started = new ArrayList<>();
        for (FakeTicker ticker : sessionTickers) {
            if (ticker.getPeriodMs() == NetProto.PING_INTERVAL_MS) {
                started.add(ticker);
            }
        }
        assertEquals(1, started.size(), "exactly one connection should be pinging here");
        return started.get(0);
    }

    // -------------------------------------------------------------------------
    // Clients
    // -------------------------------------------------------------------------

    private LoopbackClient open() throws InterruptedException {
        LoopbackClient client = new LoopbackClient(wsUri, codec);
        clients.add(client);
        assertTrue(client.connectBlocking(AWAIT_MS, TimeUnit.MILLISECONDS),
                "the client could not reach the server on " + wsUri);
        return client;
    }

    /** The handshake of spec 1, answered with the version that governs the
     *  session - and, being the first message, the proof that everything after
     *  it is served at all (spec 4.1: hello MUST be first). */
    private NetMessage greet(LoopbackClient client, String name) {
        NetMessage hello = client.request(client.newRequest(MessageType.HELLO)
                .put(NetFields.PROTO, NetProto.PROTO_VERSION)
                .put(NetFields.PROTO_MIN, NetProto.PROTO_MIN_VERSION)
                .put(NetFields.CLIENT, CLIENT_APP)
                .put(NetFields.NAME, name));
        assertTrue(hello.isOk());
        assertEquals(NetProto.PROTO_VERSION, hello.getData().path(NetFields.PROTO).asInt(),
                "spec 1: the response carries the CHOSEN session version");
        return hello;
    }

    private NetMessage acquire(LoopbackClient client, Ref device) {
        return client.request(device.into(client.newRequest(MessageType.DEVICE_ACQUIRE)));
    }

    private List<String> capsOf(NetMessage hello) {
        List<String> caps = new ArrayList<>();
        for (JsonNode token : hello.getData().path(NetFields.CAPS)) {
            caps.add(token.asText());
        }
        return caps;
    }

    // -------------------------------------------------------------------------
    // The bench, as the server describes it
    // -------------------------------------------------------------------------

    /**
     * The first backend {@code devices.list} offers with a device in both
     * directions - which of them that is depends on the host, so the test reads
     * it instead of naming it (see the class comment).
     */
    private Bench benchOf(LoopbackClient client) {
        NetMessage list = client.request(client.newRequest(MessageType.DEVICES_LIST));
        assertTrue(list.isOk());
        for (JsonNode backend : list.getData().path(NetFields.BACKENDS)) {
            Ref input = firstDevice(backend, true);
            Ref output = firstDevice(backend, false);
            if (input != null && output != null) {
                return new Bench(input, output);
            }
        }
        throw new IllegalStateException("this server serves no backend with both an input "
                + "and an output device, so there is nothing to measure with: "
                + list.getData());
    }

    /** The stub bench behind a backend - where the test puts the bytes in and
     *  reads the emitted waveform back out. */
    private StubDeviceManager stubBench(Bench bench) {
        return (StubDeviceManager) AudioBackend.instance()
                .manager(AudioBackendType.valueOf(bench.input().backend()));
    }

    private Ref firstDevice(JsonNode backend, boolean input) {
        for (JsonNode device : backend.path(NetFields.DEVICES)) {
            if (device.path(NetFields.INPUT).asBoolean() == input) {
                return new Ref(backend.path(NetFields.BACKEND).asText(),
                        device.path(NetFields.INDEX).asInt(),
                        device.path(NetFields.NAME).asText(), input);
            }
        }
        return null;
    }

    /** The {@code lock} object spec 4.3 puts on a device, read out of an
     *  {@code ev.devices.changed} - JSON null when the device is free. */
    private JsonNode lockOf(NetMessage event, Ref device) {
        return lockIn(event.getNode(NetFields.BACKENDS), device);
    }

    /** The same lock read out of a {@code backends} ARRAY, whether it arrived as
     *  an event or as the {@code data} of a {@code devices.list} response - one
     *  payload shape, so both are read the same way. */
    private JsonNode lockIn(JsonNode backends, Ref device) {
        for (JsonNode backend : backends) {
            if (device.backend().equals(backend.path(NetFields.BACKEND).asText())) {
                return lockInEntry(backend, device);
            }
        }
        throw new IllegalStateException("the payload has no backend of " + device);
    }

    /** The lock inside ONE backend entry - the shape {@code backend.select}
     *  answers with, and the shape each element of the array above has. */
    private JsonNode lockInEntry(JsonNode backend, Ref device) {
        for (JsonNode entry : backend.path(NetFields.DEVICES)) {
            if (entry.path(NetFields.INDEX).asInt() == device.index()
                    && entry.path(NetFields.INPUT).asBoolean() == device.input()) {
                return entry.path(NetFields.LOCK);
            }
        }
        throw new IllegalStateException("the payload says nothing about " + device);
    }

    /** One capture batch of recognisable PCM - see {@link #BATCH_SEED_STEP}. */
    private byte[] batch(int index) {
        byte[] pcm = new byte[BATCH_BYTES];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (byte) (index * BATCH_SEED_STEP + i);
        }
        return pcm;
    }

    /** A device ref as spec 4.3 puts it on the wire: the four fields every
     *  request names a device with. */
    private record Ref(String backend, int index, String name, boolean input) {

        NetMessage into(NetMessage message) {
            return message.put(NetFields.BACKEND, backend)
                    .put(NetFields.INDEX, index)
                    .put(NetFields.INPUT, input)
                    .put(NetFields.NAME, name);
        }

        @Override
        public String toString() {
            return backend + "[" + index + "] " + (input ? "input" : "output");
        }
    }

    /** One backend's two ends, as this run will use them. */
    private record Bench(Ref input, Ref output) { }
}
