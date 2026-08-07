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

import com.fasterxml.jackson.databind.JsonNode;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.net.proto.ErrorCode;
import org.edgo.audio.measure.net.proto.JsonCodec;
import org.edgo.audio.measure.net.proto.MessageType;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.net.proto.NetMessage;
import org.edgo.audio.measure.net.proto.NetProto;
import org.edgo.audio.measure.sound.AudioBackend;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The backend listing and selection of spec 4.3, on a bench that serves TWO
 * backends - because that is the whole point: the server has no global active
 * backend, so which one a client is looking at is a property of its own
 * connection and of nothing else.
 *
 * <p>The two stub backends are registered through the ordinary service loader
 * ({@link StubDeviceManagerProvider}, {@link StubSoundCardProvider}), so the
 * enumeration under test is the production one.
 */
class BackendSelectionTest {

    private static final String CLIENT_NAME = "Developer's laptop";
    private static final String OTHER_CLIENT_NAME = "Bench tablet";
    private static final int HELLO_ID = 1;
    private static final int DEVICE_INDEX = 0;
    private static final int CAPTURE_RATE_HZ = 48_000;
    private static final int CAPTURE_BITS = 24;
    /** The stub bench: two capture devices and one playback device. */
    private static final int STUB_DEVICE_COUNT = 3;
    /** A backend this build SHIPS but this server does not serve.  server-net
     *  depends on every local backend module - it is the machine the hardware is
     *  wired to - so no backend is ever missing from the class path; the served
     *  list alone decides, and this one is not on it. */
    private static final AudioBackendType UNSERVED_BACKEND = AudioBackendType.WASAPI;
    /** Exactly which backends a server may name, spelled out instead of derived
     *  from {@code values()}: a new enum constant must be a deliberate addition
     *  here rather than something the assertion absorbs in silence - which is how
     *  the client's own net carrier reached the wire once already. */
    private static final List<String> SERVER_BACKENDS = List.of(
            AudioBackendType.WASAPI.name(), AudioBackendType.WDMKS.name(),
            AudioBackendType.COREAUDIO.name(), AudioBackendType.JAVASOUND.name(),
            AudioBackendType.QA40X.name());

    private final ServerConfig config = new ServerConfig(new String[0]);
    private final LockRegistry locks = new LockRegistry();
    private final JsonCodec codec = new JsonCodec();
    private final DeviceCatalog catalog = new DeviceCatalog(AudioBackend.instance(), locks,
            codec, List.of(AudioBackendType.QA40X, AudioBackendType.JAVASOUND),
            new StubCardStore().getPrefs());
    private final FakeChannel channel = new FakeChannel();
    private final ClientSession session = sessionOn(channel);

    /** Request ids: spec 4.0 asks only that they be distinct and rising, and
     *  these scenarios are too long to name every step a constant. */
    private int lastId = HELLO_ID;

    @Test
    void theBackendListFlagsEveryBackendAndCarriesNoDeviceDetail() {
        greet(session, CLIENT_NAME);
        int id = nextId();

        session.onMessage(new NetMessage(MessageType.BACKEND_LIST, id));

        JsonNode backends = channel.responseTo(id).getData().path(NetFields.BACKENDS);
        assertEquals(SERVER_BACKENDS, names(backends),
                "every backend this build knows of is named - one the server "
                        + "cannot serve must say so, not vanish - and nothing else is");
        JsonNode analyzer = entry(backends, AudioBackendType.QA40X);
        assertEquals(AudioBackendType.QA40X.getDisplayName(),
                analyzer.path(NetFields.DISPLAY_NAME).asText(),
                "the client shows the server's own wording, not the enum constant");
        assertTrue(analyzer.path(NetFields.AVAILABLE).asBoolean());
        assertTrue(analyzer.path(NetFields.HAS_BIT_DEPTH).asBoolean(),
                "the stub output offers two sample widths, which IS a depth choice");
        assertTrue(analyzer.path(NetFields.DEVICES).isMissingNode(),
                "spec 4.3: backend.list carries NO device detail - that is what "
                        + "makes it the fast call a combo can be built from");

        JsonNode unserved = entry(backends, UNSERVED_BACKEND);
        assertTrue(unserved.path(NetFields.AVAILABLE).asBoolean(),
                "the server carries every local backend module, so available is "
                        + "true even for one this server was not given to serve");
        assertFalse(unserved.path(NetFields.OPERATIONAL).asBoolean(),
                "operational is a separate flag from available - that pair is what "
                        + "says CoreAudio ships on Windows and cannot run there, and "
                        + "here that this build has WASAPI but this server is not "
                        + "serving it");
        assertFalse(unserved.path(NetFields.HAS_BIT_DEPTH).asBoolean(),
                "a backend with no devices has no sample width to choose");
    }

    @Test
    void theClientsNetCarrierIsNotOneOfTheServersBackends() {
        greet(session, CLIENT_NAME);
        int id = nextId();

        session.onMessage(new NetMessage(MessageType.BACKEND_LIST, id));

        JsonNode backends = channel.responseTo(id).getData().path(NetFields.BACKENDS);
        assertFalse(names(backends).contains(AudioBackendType.NET.name()),
                "NET is how a CLIENT reaches a server, not something a server can "
                        + "serve: listed, it would become a '<server> -> Network' "
                        + "entry in the client's combo that backend.select could "
                        + "only refuse");
    }

    @Test
    void selectingABackendAnswersItsWholeDevicesListEntry() {
        greet(session, CLIENT_NAME);
        int id = nextId();

        select(session, id, AudioBackendType.QA40X);

        JsonNode data = channel.responseTo(id).getData();
        assertEquals(AudioBackendType.QA40X.name(), data.path(NetFields.BACKEND).asText());
        assertEquals(STUB_DEVICE_COUNT, data.path(NetFields.DEVICES).size());
        JsonNode first = data.path(NetFields.DEVICES).get(0);
        assertEquals(StubDeviceManager.FIRST_INPUT, first.path(NetFields.NAME).asText());
        assertTrue(first.path(NetFields.FORMATS).size() > 0,
                "formats inlined: one round-trip fills the device AND the rate combo");
    }

    @Test
    void aBackendThisServerDoesNotServeIsRefused() {
        greet(session, CLIENT_NAME);
        int id = nextId();

        select(session, id, UNSERVED_BACKEND);

        assertTrue(channel.responseTo(id).getError().is(ErrorCode.BAD_REQUEST),
                "a selection that cannot be honoured fails at the selection, not "
                        + "later at an open that looks unrelated");
    }

    @Test
    void aBackendNameThisBuildDoesNotKnowIsRefused() {
        greet(session, CLIENT_NAME);
        int id = nextId();

        session.onMessage(new NetMessage(MessageType.BACKEND_SELECT, id)
                .put(NetFields.BACKEND, "TAPE_LOOP"));

        assertTrue(channel.responseTo(id).getError().is(ErrorCode.BAD_REQUEST));
    }

    @Test
    void aDeviceOnAnotherBackendThanTheSelectedOneIsRefused() {
        greet(session, CLIENT_NAME);
        select(session, nextId(), AudioBackendType.JAVASOUND);
        acquire(session, nextId(), AudioBackendType.QA40X);
        int id = nextId();

        session.onMessage(captureOpen(id, AudioBackendType.QA40X));

        assertTrue(channel.responseTo(id).getError().is(ErrorCode.BACKEND_MISMATCH),
                "spec 4.3: after a selection the device refs must name it");
    }

    @Test
    void aGeneratorOnAnotherBackendThanTheSelectedOneIsRefused() {
        greet(session, CLIENT_NAME);
        select(session, nextId(), AudioBackendType.JAVASOUND);
        int id = nextId();

        session.onMessage(new NetMessage(MessageType.GEN_OPEN, id)
                .put(NetFields.BACKEND, AudioBackendType.QA40X.name())
                .put(NetFields.INDEX, DEVICE_INDEX)
                .put(NetFields.INPUT, false)
                .put(NetFields.NAME, StubDeviceManager.OUTPUT)
                .put(NetFields.RATE, CAPTURE_RATE_HZ)
                .put(NetFields.BITS, CAPTURE_BITS));

        assertTrue(channel.responseTo(id).getError().is(ErrorCode.BACKEND_MISMATCH),
                "spec 4.3 names capture.open AND gen.open as the calls whose device "
                        + "ref must name the selected backend - the generator is not "
                        + "a second, unguarded way onto another backend's hardware");
    }

    @Test
    void withoutASelectionAnyListedDeviceMayStillBeOpened() {
        greet(session, CLIENT_NAME);
        acquire(session, nextId(), AudioBackendType.QA40X);
        int id = nextId();

        session.onMessage(captureOpen(id, AudioBackendType.QA40X));

        assertTrue(channel.responseTo(id).isOk(),
                "the constraint starts at the selection: a client that works "
                        + "straight off devices.list is the walkthrough of spec 6");
    }

    @Test
    void reSelectingSwitchesTheBackendAndTheLocksAlreadyHeldSurviveIt() {
        greet(session, CLIENT_NAME);
        acquire(session, nextId(), AudioBackendType.QA40X);
        select(session, nextId(), AudioBackendType.QA40X);
        int firstOpen = nextId();
        session.onMessage(captureOpen(firstOpen, AudioBackendType.QA40X));
        assertTrue(channel.responseTo(firstOpen).isOk());
        closeCapture(firstOpen);

        select(session, nextId(), AudioBackendType.JAVASOUND);
        int refused = nextId();
        session.onMessage(captureOpen(refused, AudioBackendType.QA40X));

        assertTrue(channel.responseTo(refused).getError().is(ErrorCode.BACKEND_MISMATCH),
                "the selection really switched");
        assertEquals(1, locks.size());
        assertSame(session, locks.owner(
                new DeviceLock(AudioBackendType.QA40X, DEVICE_INDEX, true)),
                "a lock is on a DEVICE, not on a selection - dropping it here would "
                        + "hand a running measurement's input to whoever asked next");

        select(session, nextId(), AudioBackendType.QA40X);
        int reopened = nextId();
        session.onMessage(captureOpen(reopened, AudioBackendType.QA40X));

        assertTrue(channel.responseTo(reopened).isOk(),
                "and switching back needs no fresh device.acquire");
    }

    @Test
    void twoConnectionsHoldDifferentSelectionsOfTheSameServer() {
        greet(session, CLIENT_NAME);
        FakeChannel otherChannel = new FakeChannel();
        ClientSession other = sessionOn(otherChannel);
        other.start();
        greet(other, OTHER_CLIENT_NAME);

        select(session, nextId(), AudioBackendType.QA40X);
        int otherSelect = nextId();
        select(other, otherSelect, AudioBackendType.JAVASOUND);

        assertEquals(AudioBackendType.JAVASOUND.name(), otherChannel.responseTo(otherSelect)
                .getData().path(NetFields.BACKEND).asText());
        acquire(session, nextId(), AudioBackendType.QA40X);
        int mine = nextId();
        session.onMessage(captureOpen(mine, AudioBackendType.QA40X));
        int theirs = nextId();
        other.onMessage(captureOpen(theirs, AudioBackendType.QA40X));

        assertTrue(channel.responseTo(mine).isOk(),
                "my connection selected the analyzer and may use it");
        assertTrue(otherChannel.responseTo(theirs).getError().is(ErrorCode.BACKEND_MISMATCH),
                "the other selected the sound card, and my selection did not move "
                        + "its choice - the server has no global active backend");
    }

    private ClientSession sessionOn(FakeChannel target) {
        Qa40xGuard qa40x = new Qa40xGuard(AudioBackend.instance(), locks);
        CaptureStreamer captures = new CaptureStreamer(AudioBackend.instance(), catalog,
                codec, target, new FakeWorker(), qa40x);
        GeneratorSession generator = new GeneratorSession(AudioBackend.instance(), catalog,
                captures, new FileStore(), qa40x, codec, target, new FakeWorker(),
                new FakeTicker(), () -> 0L);
        return new ClientSession(config, locks, qa40x, catalog, captures, generator,
                new Qa40xSession(AudioBackend.instance(), codec,
                        List.of(AudioBackendType.QA40X, AudioBackendType.JAVASOUND)),
                codec, target,
                new FakeTicker(), new FakeWorker());
    }

    /** The backend names of a {@code backend.list} array, in the order they were
     *  sent - which is the order the client's combo will show. */
    private List<String> names(JsonNode backends) {
        List<String> named = new ArrayList<>();
        for (JsonNode backend : backends) {
            named.add(backend.path(NetFields.BACKEND).asText());
        }
        return named;
    }

    /** The entry of one backend in a {@code backend.list} array. */
    private JsonNode entry(JsonNode backends, AudioBackendType type) {
        for (JsonNode backend : backends) {
            if (type.name().equals(backend.path(NetFields.BACKEND).asText())) {
                return backend;
            }
        }
        throw new AssertionError(type + " is missing from backend.list");
    }

    private int nextId() {
        lastId++;
        return lastId;
    }

    private void greet(ClientSession target, String name) {
        target.onMessage(new NetMessage(MessageType.HELLO, HELLO_ID)
                .put(NetFields.PROTO, NetProto.PROTO_VERSION)
                .put(NetFields.NAME, name));
    }

    private void select(ClientSession target, int id, AudioBackendType type) {
        target.onMessage(new NetMessage(MessageType.BACKEND_SELECT, id)
                .put(NetFields.BACKEND, type.name()));
    }

    private void acquire(ClientSession target, int id, AudioBackendType type) {
        target.onMessage(deviceRef(MessageType.DEVICE_ACQUIRE, id, type));
    }

    private void closeCapture(int openId) {
        int captureId = channel.responseTo(openId).getData()
                .path(NetFields.CAPTURE_ID).asInt();
        session.onMessage(new NetMessage(MessageType.CAPTURE_CLOSE, nextId())
                .put(NetFields.CAPTURE_ID, captureId));
    }

    private NetMessage captureOpen(int id, AudioBackendType type) {
        return deviceRef(MessageType.CAPTURE_OPEN, id, type)
                .put(NetFields.RATE, CAPTURE_RATE_HZ)
                .put(NetFields.BITS, CAPTURE_BITS);
    }

    private NetMessage deviceRef(MessageType type, int id, AudioBackendType backend) {
        return new NetMessage(type, id)
                .put(NetFields.BACKEND, backend.name())
                .put(NetFields.INDEX, DEVICE_INDEX)
                .put(NetFields.INPUT, true)
                .put(NetFields.NAME, StubDeviceManager.FIRST_INPUT);
    }
}
