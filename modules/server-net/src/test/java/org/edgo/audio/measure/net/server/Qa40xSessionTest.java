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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The QA40x extension of spec 4.6, message by message, against the stub analyzer
 * this module's tests already measure with ({@link StubDeviceManager}, which
 * implements the same {@code Qa40xControl} contract the driver does).
 *
 * <p>What is proved here is the SHAPE of the payloads and the rule about who may
 * change what: the reads answer whatever the analyzer says, the writes reach it,
 * and both are refused with the code spec 4.2 names when the connection has no
 * business issuing them.  Every field name is read from {@link NetFields} rather
 * than typed, for the reason that class exists - a one-character drift is a
 * silent no-op on the wire, not a compile error.
 */
class Qa40xSessionTest {

    private static final String SERVER_NAME = "Bench QA403";
    private static final String CLIENT_NAME = "Developer's laptop";
    private static final String DEVICE_NAME = "QA403";
    private static final int HELLO_ID = 1;
    private static final int REQUEST_ID = 2;
    private static final int SECOND_REQUEST_ID = 3;
    private static final int THIRD_REQUEST_ID = 4;
    private static final int DEVICE_INDEX = 0;
    /** A position the stub analyzer really has, and not the one it starts on. */
    private static final int OTHER_INPUT_DBV = 42;
    private static final int OTHER_OUTPUT_DBV = -12;
    /** A position no analyzer has - what a BAD_REQUEST is proved with. */
    private static final int IMPOSSIBLE_DBV = 7;
    private static final double CAL_DELTA = 1e-9;

    private final ServerConfig config =
            new ServerConfig(new String[] {"--name", SERVER_NAME});
    private final LockRegistry locks = new LockRegistry();
    private final JsonCodec codec = new JsonCodec();
    private final DeviceCatalog catalog = new DeviceCatalog(AudioBackend.instance(), locks,
            codec, List.of(AudioBackendType.QA40X), new StubCardStore().getPrefs());
    private final FakeChannel channel = new FakeChannel();
    private final FakeWorker worker = new FakeWorker();
    private final ClientSession session = session(channel);

    @BeforeEach
    void greetTheServer() {
        session.onMessage(new NetMessage(MessageType.HELLO, HELLO_ID)
                .put(NetFields.PROTO, NetProto.PROTO_VERSION)
                .put(NetFields.NAME, CLIENT_NAME));
        analyzer().setI2sEnabled(false);
        analyzer().setInputRange(StubDeviceManager.DEFAULT_INPUT_DBV);
        analyzer().setOutputRange(StubDeviceManager.DEFAULT_OUTPUT_DBV);
    }

    @Test
    void theCapabilityTokenIsClaimedByAServerThatReallyHasAnAnalyzer() {
        JsonNode caps = channel.responseTo(HELLO_ID).getData().path(NetFields.CAPS);

        boolean claimed = false;
        for (JsonNode token : caps) {
            claimed |= NetFields.CAP_QA40X.equals(token.asText());
        }
        assertTrue(claimed, "spec 4.6 is served, and spec 4.1's token is the promise "
                + "a client sends qa40x.* on: " + caps);
    }

    @Test
    void infoAnswersTheAnalyzersIdentityAndTelemetry() {
        session.onMessage(new NetMessage(MessageType.QA40X_INFO, REQUEST_ID));

        JsonNode data = okData(REQUEST_ID);
        assertEquals(StubDeviceManager.FIRMWARE,
                data.path(NetFields.FIRMWARE_VERSION).asText());
        assertEquals(StubDeviceManager.SERIAL, data.path(NetFields.SERIAL_NUMBER).asText());
        assertFalse(data.path(NetFields.USB_VOLTAGE).asText().isEmpty());
        assertFalse(data.path(NetFields.USB_CURRENT).asText().isEmpty());
        assertFalse(data.path(NetFields.TEMPERATURE).asText().isEmpty());
        assertFalse(data.path(NetFields.CAPABILITY).asText().isEmpty());
        assertFalse(data.path(NetFields.CAPABILITY2).asText().isEmpty());
        assertFalse(data.path(NetFields.ISO_CURRENT).asText().isEmpty(),
                "the QA403 has no ISO supply, and spec 4.6 still carries the field - "
                        + "the value says 'unavailable', the field does not vanish");
    }

    @Test
    void infoIsReadOnlyAndNeedsNoLock() {
        session.onMessage(new NetMessage(MessageType.QA40X_INFO, REQUEST_ID));

        assertTrue(channel.responseTo(REQUEST_ID).isOk(),
                "spec 4.6 marks the reads read-only: a client may look at the bench's "
                        + "telemetry while somebody else is measuring on it");
    }

    @Test
    void rangesAnswerWhatIsOfferedAndWhatIsInForce() {
        session.onMessage(new NetMessage(MessageType.QA40X_RANGES, REQUEST_ID));

        JsonNode data = okData(REQUEST_ID);
        assertEquals(StubDeviceManager.INPUT_RANGES_DBV.length,
                data.path(NetFields.INPUT_DBV).size());
        assertEquals(StubDeviceManager.OUTPUT_RANGES_DBV.length,
                data.path(NetFields.OUTPUT_DBV).size());
        assertEquals(StubDeviceManager.DEFAULT_INPUT_DBV,
                data.path(NetFields.ACTIVE_INPUT_DBV).asInt());
        assertEquals(StubDeviceManager.DEFAULT_OUTPUT_DBV,
                data.path(NetFields.ACTIVE_OUTPUT_DBV).asInt());
    }

    @Test
    void aRangeChangeReachesTheAnalyzerOnceTheClientHoldsIt() {
        acquire(REQUEST_ID, true);

        session.onMessage(new NetMessage(MessageType.QA40X_SET_INPUT_RANGE,
                SECOND_REQUEST_ID).put(NetFields.DBV, OTHER_INPUT_DBV));
        session.onMessage(new NetMessage(MessageType.QA40X_SET_OUTPUT_RANGE,
                THIRD_REQUEST_ID).put(NetFields.DBV, OTHER_OUTPUT_DBV));

        assertTrue(channel.responseTo(SECOND_REQUEST_ID).isOk());
        assertTrue(channel.responseTo(THIRD_REQUEST_ID).isOk());
        assertEquals(OTHER_INPUT_DBV, analyzer().activeInputRangeDbv());
        assertEquals(OTHER_OUTPUT_DBV, analyzer().activeOutputRangeDbv());
    }

    @Test
    void aRangeChangeWithoutTheLockIsRefused() {
        session.onMessage(new NetMessage(MessageType.QA40X_SET_INPUT_RANGE, REQUEST_ID)
                .put(NetFields.DBV, OTHER_INPUT_DBV));

        assertTrue(channel.responseTo(REQUEST_ID).getError().is(ErrorCode.NOT_LOCKED),
                "spec 4.6: the writes require the QA40x lock - moving the attenuator "
                        + "under another client's running measurement would silently "
                        + "change what that client is measuring");
        assertEquals(StubDeviceManager.DEFAULT_INPUT_DBV, analyzer().activeInputRangeDbv());
    }

    @Test
    void aRangeTheAnalyzerDoesNotHaveIsABadRequest() {
        acquire(REQUEST_ID, true);

        session.onMessage(new NetMessage(MessageType.QA40X_SET_INPUT_RANGE,
                SECOND_REQUEST_ID).put(NetFields.DBV, IMPOSSIBLE_DBV));

        assertTrue(channel.responseTo(SECOND_REQUEST_ID).getError()
                .is(ErrorCode.BAD_REQUEST));
        assertEquals(StubDeviceManager.DEFAULT_INPUT_DBV, analyzer().activeInputRangeDbv());
    }

    @Test
    void aRangeChangeWithoutADbvIsABadRequest() {
        acquire(REQUEST_ID, true);

        session.onMessage(new NetMessage(MessageType.QA40X_SET_OUTPUT_RANGE,
                SECOND_REQUEST_ID));

        assertTrue(channel.responseTo(SECOND_REQUEST_ID).getError()
                .is(ErrorCode.BAD_REQUEST));
    }

    @Test
    void calibrationCarriesOneRowPerRangeWithBothChannels() {
        session.onMessage(new NetMessage(MessageType.QA40X_CALIBRATION, REQUEST_ID));

        JsonNode data = okData(REQUEST_ID);
        JsonNode adc = data.path(NetFields.ADC);
        assertEquals(StubDeviceManager.INPUT_RANGES_DBV.length, adc.size());
        assertEquals(StubDeviceManager.OUTPUT_RANGES_DBV.length,
                data.path(NetFields.DAC).size());
        JsonNode row = adc.get(0);
        assertEquals(StubDeviceManager.INPUT_RANGES_DBV[0], row.path(NetFields.DBV).asInt());
        assertEquals(StubDeviceManager.CAL_LEFT, row.path(NetFields.LEFT).asDouble(),
                CAL_DELTA);
        assertEquals(StubDeviceManager.CAL_RIGHT, row.path(NetFields.RIGHT).asDouble(),
                CAL_DELTA, "left and right are not interchangeable: a card built from "
                        + "swapped factors mis-scales one channel by the ratio between "
                        + "them and nothing says so");
    }

    @Test
    void settingsWithNoFieldReadsAndWithOneWrites() {
        session.onMessage(new NetMessage(MessageType.QA40X_SETTINGS, REQUEST_ID));
        assertFalse(okData(REQUEST_ID).path(NetFields.I2S_ENABLED).asBoolean(),
                "a get is the read-only half of spec 4.6's one settings message");

        acquire(SECOND_REQUEST_ID, false);
        session.onMessage(new NetMessage(MessageType.QA40X_SETTINGS, THIRD_REQUEST_ID)
                .put(NetFields.I2S_ENABLED, true));

        assertTrue(okData(THIRD_REQUEST_ID).path(NetFields.I2S_ENABLED).asBoolean(),
                "the answer is the state now in force, so the client never has to "
                        + "guess whether its write took");
        assertTrue(analyzer().isI2sEnabled(),
                "and it really reached the analyzer - the front-panel port moves the "
                        + "supported output sample widths");
    }

    @Test
    void switchingTheI2sPortWithoutTheLockIsRefused() {
        session.onMessage(new NetMessage(MessageType.QA40X_SETTINGS, REQUEST_ID)
                .put(NetFields.I2S_ENABLED, true));

        assertTrue(channel.responseTo(REQUEST_ID).getError().is(ErrorCode.NOT_LOCKED));
        assertFalse(analyzer().isI2sEnabled());
    }

    @Test
    void everyMessageOfSpec46IsServedRatherThanUnsupported() {
        acquire(REQUEST_ID, true);
        int id = SECOND_REQUEST_ID;
        for (MessageType type : List.of(MessageType.QA40X_INFO, MessageType.QA40X_RANGES,
                MessageType.QA40X_CALIBRATION, MessageType.QA40X_SETTINGS,
                MessageType.QA40X_SET_INPUT_RANGE, MessageType.QA40X_SET_OUTPUT_RANGE)) {
            int requestId = id++;
            NetMessage request = new NetMessage(type, requestId);
            if (type == MessageType.QA40X_SET_INPUT_RANGE) {
                request.put(NetFields.DBV, StubDeviceManager.DEFAULT_INPUT_DBV);
            } else if (type == MessageType.QA40X_SET_OUTPUT_RANGE) {
                request.put(NetFields.DBV, StubDeviceManager.DEFAULT_OUTPUT_DBV);
            }
            session.onMessage(request);
            NetMessage response = channel.responseTo(requestId);
            assertTrue(response.isOk(), type + " was refused: " + response);
        }
    }

    // -------------------------------------------------------------------------
    // The bench under test
    // -------------------------------------------------------------------------

    /** A whole connection on {@code target}, wired the way {@code WsFront} wires
     *  one - the analyzer commands go through the real dispatch, lock check
     *  included, because that check is half of what spec 4.6 says. */
    private ClientSession session(FakeChannel target) {
        Qa40xGuard qa40x = new Qa40xGuard(AudioBackend.instance(), locks);
        CaptureStreamer captures = new CaptureStreamer(AudioBackend.instance(), catalog,
                codec, target, new FakeWorker(), qa40x);
        GeneratorSession generator = new GeneratorSession(AudioBackend.instance(), catalog,
                captures, new FileStore(), qa40x, codec, target, worker,
                new FakeTicker(), () -> 0L);
        return new ClientSession(config, locks, qa40x, catalog, captures, generator,
                new Qa40xSession(AudioBackend.instance(), codec,
                        List.of(AudioBackendType.QA40X)), codec, target,
                new FakeTicker(), worker);
    }

    /** The stub analyzer behind the QA40X backend - where a write has to land. */
    private StubDeviceManager analyzer() {
        return (StubDeviceManager) AudioBackend.instance().manager(AudioBackendType.QA40X);
    }

    private void acquire(int id, boolean input) {
        session.onMessage(new NetMessage(MessageType.DEVICE_ACQUIRE, id)
                .put(NetFields.BACKEND, AudioBackendType.QA40X.name())
                .put(NetFields.INDEX, DEVICE_INDEX)
                .put(NetFields.INPUT, input)
                .put(NetFields.NAME, DEVICE_NAME));
        assertTrue(channel.responseTo(id).isOk(), "the acquire this test needs failed");
    }

    private JsonNode okData(int id) {
        NetMessage response = channel.responseTo(id);
        assertTrue(response.isOk(), "refused: " + response);
        return response.getData();
    }
}
