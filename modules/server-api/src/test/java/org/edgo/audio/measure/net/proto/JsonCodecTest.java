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

package org.edgo.audio.measure.net.proto;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import org.edgo.audio.measure.enums.DeviceFailureReason;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Control-channel conformance: the three envelope shapes of spec 4.0, the error
 * object of spec 4.2, the two forward-compatibility rules of spec 1 (unknown
 * fields ignored, unknown {@code t} tolerated), and the PAYLOAD field sets of
 * spec 4.3 - 4.5 named from {@link NetFields}.
 *
 * <p>The partial-update tests are the load-bearing ones: {@code gen.config}
 * applies only the fields actually present, so a decoder that turns an absent
 * number into 0 would silently reset the generator.
 *
 * <p>The payload tests assert the exact JSON TEXT, not just the round trip: the
 * web client is written independently and matches these names character by
 * character, so a renamed constant must fail here rather than at runtime.
 */
class JsonCodecTest {

    private static final int ID = 42;
    private static final int SERVER_PING_ID = -1;

    private final JsonCodec codec = new JsonCodec();

    @Test
    void everyMessageTypeRoundTripsItsWireName() throws JsonProcessingException {
        for (MessageType type : MessageType.values()) {
            if (type == MessageType.UNKNOWN) {
                continue;                       // not a wire value by definition
            }
            NetMessage decoded = codec.read(codec.write(new NetMessage(type, ID)));

            assertEquals(type, decoded.getType(), "round trip of " + type);
            assertEquals(type.getWire(), decoded.getT(), "wire text of " + type);
            assertEquals(ID, decoded.getId(), "id of " + type);
        }
    }

    @Test
    void helloCarriesTheClientsWholeVersionRange() throws JsonProcessingException {
        // Spec 1: the range, not a single number - protoMin is what lets a v2
        // client be served at v1 instead of being told PROTO_MISMATCH.
        NetMessage sent = new NetMessage(MessageType.HELLO, ID)
                .put(NetFields.PROTO, NetProto.PROTO_VERSION)
                .put(NetFields.PROTO_MIN, NetProto.PROTO_MIN_VERSION)
                .put(NetFields.CLIENT, "Phonalyser desktop 1.2.0")
                .put(NetFields.NAME, "Developer's laptop");

        NetMessage received = codec.read(codec.write(sent));

        assertEquals(MessageType.HELLO, received.getType());
        assertEquals(NetProto.PROTO_VERSION, received.optInt(NetFields.PROTO));
        assertEquals(NetProto.PROTO_MIN_VERSION, received.optInt(NetFields.PROTO_MIN));
        assertEquals("Phonalyser desktop 1.2.0", received.optString(NetFields.CLIENT));
        assertEquals("Developer's laptop", received.optString(NetFields.NAME));
    }

    @Test
    void helloResponseCarriesTheChosenVersionAndCaps() throws JsonProcessingException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(NetFields.PROTO, NetProto.PROTO_VERSION);
        data.put(NetFields.SERVER_ID, "b7e0-uuid");
        data.put(NetFields.NAME, "Bench QA403");
        data.put(NetFields.APP, "1.2.0");
        data.put(NetFields.CAPS, List.of(NetFields.CAP_QA40X, NetFields.CAP_GEN,
                NetFields.CAP_FILES));

        String json = codec.write(new NetMessage(ID, codec.toNode(data)));

        assertEquals("{\"t\":\"resp\",\"id\":42,\"ok\":true,\"data\":{\"proto\":1,"
                + "\"serverId\":\"b7e0-uuid\",\"name\":\"Bench QA403\","
                + "\"app\":\"1.2.0\",\"caps\":[\"qa40x\",\"gen\",\"files\"]}}", json,
                "spec 4.1 field names and cap tokens, verbatim");
    }

    @Test
    void okResponseCarriesItsData() throws JsonProcessingException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(NetFields.CAPTURE_ID, 3);
        data.put(NetFields.RATE, 48_000);
        data.put(NetFields.BITS, 24);
        data.put(NetFields.CHANNELS, 2);
        data.put(NetFields.FRAME_BYTES, 6);

        String json = codec.write(new NetMessage(ID, codec.toNode(data)));
        NetMessage received = codec.read(json);

        assertEquals("{\"t\":\"resp\",\"id\":42,\"ok\":true,\"data\":{\"captureId\":3,"
                + "\"rate\":48000,\"bits\":24,\"channels\":2,\"frameBytes\":6}}", json,
                "the whole capture.open field set of spec 4.4, verbatim");
        assertEquals(MessageType.RESP, received.getType());
        assertEquals(ID, received.getId());
        assertTrue(received.isOk());
        JsonNode payload = received.getData();
        assertEquals(3, payload.path(NetFields.CAPTURE_ID).asInt());
        assertEquals(48_000, payload.path(NetFields.RATE).asInt());
        assertEquals(24, payload.path(NetFields.BITS).asInt());
        assertEquals(2, payload.path(NetFields.CHANNELS).asInt());
        assertEquals(6, payload.path(NetFields.FRAME_BYTES).asInt());
        assertNull(received.getError(), "a successful response has no error object");
    }

    @Test
    void deviceEntryCarriesItsWholeFieldSet() throws JsonProcessingException {
        Map<String, Object> format = new LinkedHashMap<>();
        format.put(NetFields.RATE, 192_000);
        format.put(NetFields.BITS, 24);
        format.put(NetFields.CHANNELS, 2);
        Map<String, Object> device = new LinkedHashMap<>();
        device.put(NetFields.INDEX, 1);
        device.put(NetFields.NAME, "QA403");
        device.put(NetFields.DESCRIPTION, "QuantAsylum QA403");
        device.put(NetFields.VENDOR, "QuantAsylum");
        device.put(NetFields.INPUT, true);
        device.put(NetFields.OUTPUT, true);
        device.put(NetFields.FORMATS, List.of(format));
        device.put(NetFields.HAS_BIT_DEPTH, false);
        device.put(NetFields.LOCK, Map.of(NetFields.BY, "Developer's laptop"));
        Map<String, Object> backend = new LinkedHashMap<>();
        backend.put(NetFields.BACKEND, "QA40X");
        backend.put(NetFields.DEVICES, List.of(device));
        Map<String, Object> data = Map.of(NetFields.BACKENDS, List.of(backend));

        JsonNode payload = codec.read(codec.write(new NetMessage(ID,
                codec.toNode(data)))).getData();
        JsonNode entry = payload.path(NetFields.BACKENDS).path(0)
                .path(NetFields.DEVICES).path(0);

        assertEquals("QA40X", payload.path(NetFields.BACKENDS).path(0)
                .path(NetFields.BACKEND).asText());
        assertEquals(1, entry.path(NetFields.INDEX).asInt());
        assertEquals("QA403", entry.path(NetFields.NAME).asText());
        assertEquals("QuantAsylum QA403", entry.path(NetFields.DESCRIPTION).asText());
        assertEquals("QuantAsylum", entry.path(NetFields.VENDOR).asText());
        assertTrue(entry.path(NetFields.INPUT).asBoolean());
        assertTrue(entry.path(NetFields.OUTPUT).asBoolean());
        assertFalse(entry.path(NetFields.HAS_BIT_DEPTH).asBoolean());
        assertEquals(192_000, entry.path(NetFields.FORMATS).path(0)
                .path(NetFields.RATE).asInt());
        assertEquals("Developer's laptop", entry.path(NetFields.LOCK)
                .path(NetFields.BY).asText(), "a locked device names its owner");
    }

    @Test
    void deviceEntryCarriesTheServersStoredCalibration() throws JsonProcessingException {
        Map<String, Object> cal = new LinkedHashMap<>();
        cal.put(NetFields.FS_RMS_LEFT, 1.234);
        cal.put(NetFields.FS_RMS_RIGHT, 2.345);
        Map<String, Object> device = new LinkedHashMap<>();
        device.put(NetFields.NAME, "QA403");
        device.put(NetFields.LOCK, null);
        device.put(NetFields.CAL, cal);

        String json = codec.write(new NetMessage(ID, codec.toNode(device)));
        JsonNode entry = codec.read(json).getData();

        assertEquals("{\"t\":\"resp\",\"id\":42,\"ok\":true,\"data\":{\"name\":\"QA403\","
                + "\"lock\":null,\"cal\":{\"fsRmsLeft\":1.234,\"fsRmsRight\":2.345}}}",
                json, "spec 4.3 v1.1 field names, verbatim");
        assertEquals(1.234, entry.path(NetFields.CAL).path(NetFields.FS_RMS_LEFT).asDouble());
        assertEquals(2.345, entry.path(NetFields.CAL).path(NetFields.FS_RMS_RIGHT).asDouble());
    }

    @Test
    void setCalibrationCarriesTheDeviceRefAndBothFullScales()
            throws JsonProcessingException {
        NetMessage sent = new NetMessage(MessageType.DEVICE_SET_CALIBRATION, ID)
                .put(NetFields.BACKEND, "QA40X")
                .put(NetFields.INDEX, 1)
                .put(NetFields.INPUT, true)
                .put(NetFields.NAME, "QA403")
                .put(NetFields.FS_RMS_LEFT, 1.234)
                .put(NetFields.FS_RMS_RIGHT, 2.345);

        String json = codec.write(sent);
        NetMessage received = codec.read(json);

        assertEquals("{\"t\":\"device.setCalibration\",\"id\":42,\"backend\":\"QA40X\","
                + "\"index\":1,\"input\":true,\"name\":\"QA403\","
                + "\"fsRmsLeft\":1.234,\"fsRmsRight\":2.345}", json,
                "the device ref of spec 4.3 plus the two full-scales, verbatim");
        assertEquals(MessageType.DEVICE_SET_CALIBRATION, received.getType());
        assertEquals("QA40X", received.optString(NetFields.BACKEND));
        assertEquals(1, received.optInt(NetFields.INDEX));
        assertEquals(Boolean.TRUE, received.optBoolean(NetFields.INPUT));
        assertEquals(1.234, received.optDouble(NetFields.FS_RMS_LEFT));
        assertEquals(2.345, received.optDouble(NetFields.FS_RMS_RIGHT));
    }

    @Test
    void genConfigCarriesItsSubObjectsVerbatim() throws JsonProcessingException {
        Map<String, Object> sweep = new LinkedHashMap<>();
        sweep.put(NetFields.F0, 20.0);
        sweep.put(NetFields.F1, 20_000.0);
        sweep.put(NetFields.DURATION_SAMPLES, 480_000L);
        sweep.put(NetFields.LEAD_IN_SAMPLES, 4_800L);
        sweep.put(NetFields.LOOP, false);
        Map<String, Object> compensation = new LinkedHashMap<>();
        compensation.put(NetFields.AMP_RATIOS, List.of(1.0, 0.01));
        compensation.put(NetFields.H_NUMS, List.of(1, 2));
        compensation.put(NetFields.PHI_INITS, List.of(0.0, 3.14));

        String json = codec.write(new NetMessage(MessageType.GEN_CONFIG, ID)
                .put(NetFields.GEN_ID, 1)
                .put(NetFields.FREQUENCY, 1_000.0)
                .put(NetFields.SWEEP, codec.toNode(sweep))
                .put(NetFields.COMPENSATION, codec.toNode(compensation)));
        NetMessage received = codec.read(json);

        assertEquals("{\"t\":\"gen.config\",\"id\":42,\"genId\":1,\"frequency\":1000.0,"
                + "\"sweep\":{\"f0\":20.0,\"f1\":20000.0,\"durationSamples\":480000,"
                + "\"leadInSamples\":4800,\"loop\":false},"
                + "\"compensation\":{\"ampRatios\":[1.0,0.01],\"hNums\":[1,2],"
                + "\"phiInits\":[0.0,3.14]}}", json,
                "spec 4.5 field names, verbatim");
        assertEquals(20_000.0, received.getNode(NetFields.SWEEP)
                .path(NetFields.F1).asDouble());
        assertEquals(480_000L, received.getNode(NetFields.SWEEP)
                .path(NetFields.DURATION_SAMPLES).asLong());
        assertEquals(2, received.getNode(NetFields.COMPENSATION)
                .path(NetFields.AMP_RATIOS).size());
        assertFalse(received.has(NetFields.AMPLITUDE_VRMS),
                "a partial update carries nothing it did not set");
    }

    @Test
    void genStateEventCarriesTheEmittedFrequencies() throws JsonProcessingException {
        Map<String, Object> sweep = new LinkedHashMap<>();
        sweep.put(NetFields.ACTIVE, true);
        sweep.put(NetFields.POS_SAMPLES, 12_000L);
        sweep.put(NetFields.DURATION_SAMPLES, 480_000L);
        sweep.put(NetFields.LOOP, true);
        Map<String, Object> file = new LinkedHashMap<>();
        file.put(NetFields.PLAYING, false);
        file.put(NetFields.POS_SAMPLES, 0L);
        file.put(NetFields.FINISHED, false);
        file.put(NetFields.RATE, 48_000);
        file.put(NetFields.BITS, 24);

        NetMessage received = codec.read(codec.write(
                new NetMessage(MessageType.EV_GEN_STATE)
                        .put(NetFields.GEN_ID, 1)
                        .put(NetFields.RUNNING, true)
                        .put(NetFields.FORM, "SINE")
                        .put(NetFields.NOMINAL_HZ, 1_000.0)
                        .put(NetFields.EMIT_HZ, 999.9755859375)
                        .put(NetFields.EMIT2_HZ, 0.0)
                        .put(NetFields.AMPLITUDE_VRMS, 0.5)
                        .put(NetFields.SWEEP, codec.toNode(sweep))
                        .put(NetFields.FILE, codec.toNode(file))));

        assertEquals(MessageType.EV_GEN_STATE, received.getType());
        assertNull(received.getId(), "events carry no id");
        assertEquals(1_000.0, received.optDouble(NetFields.NOMINAL_HZ));
        assertEquals(999.9755859375, received.optDouble(NetFields.EMIT_HZ),
                "the post-snap, post-trim frequency must survive bit-exact");
        assertEquals(12_000L, received.getNode(NetFields.SWEEP)
                .path(NetFields.POS_SAMPLES).asLong());
        assertFalse(received.getNode(NetFields.FILE)
                .path(NetFields.PLAYING).asBoolean());
    }

    @Test
    void wireFieldNamesAreDistinct() throws IllegalAccessException {
        Set<String> seen = new HashSet<>();

        for (Field field : NetFields.class.getFields()) {
            String wire = (String) field.get(null);
            assertTrue(seen.add(wire), "two constants claim the wire name " + wire
                    + " - a shared field is declared once, on purpose");
        }
        assertTrue(seen.contains("emitHz") && seen.contains("hasBitDepth")
                && seen.contains("dacFsVoltageAmpl") && seen.contains("captureId"),
                "the spec's field names live here, not in hand-typed literals");
    }

    @Test
    void okResponseWithoutPayloadStillCarriesAnEmptyDataObject()
            throws JsonProcessingException {
        NetMessage received = codec.read(codec.write(new NetMessage(ID)));

        assertTrue(received.isOk());
        assertTrue(received.getData().isObject(), "ping answers with data {}");
        assertEquals(0, received.getData().size());
    }

    @Test
    void errorResponseCarriesCodeMessageAndLockOwner() throws JsonProcessingException {
        NetError sent = new NetError(ErrorCode.DEVICE_LOCKED,
                "QA403 input is in use", "Developer's laptop");

        NetMessage received = codec.read(codec.write(new NetMessage(ID, sent)));

        assertEquals(MessageType.RESP, received.getType());
        assertFalse(received.isOk());
        NetError error = received.getError();
        assertNotNull(error);
        assertTrue(error.is(ErrorCode.DEVICE_LOCKED));
        assertEquals("DEVICE_LOCKED", error.code(), "the enum name IS the wire text");
        assertEquals("QA403 input is in use", error.message());
        assertEquals("Developer's laptop", error.by());
    }

    @Test
    void errorWithoutOwnerOmitsBy() throws JsonProcessingException {
        String json = codec.write(new NetMessage(ID,
                new NetError(ErrorCode.PROTO_MISMATCH, "server proto 1, client proto 2")));

        NetError error = codec.read(json).getError();

        assertTrue(error.is(ErrorCode.PROTO_MISMATCH));
        assertNull(error.by(), "by is only sent for DEVICE_LOCKED");
        assertFalse(json.contains("\"by\""), "the field is absent, not null");
    }

    /** A device refusal carries WHY across the wire, so the client's operator
     *  reads it in the client's language and never the server's driver text. */
    @Test
    void aDeviceRefusalCarriesItsReason() throws JsonProcessingException {
        NetError sent = new NetError(ErrorCode.DEVICE_ERROR,
                "cannot open QA403 input at 384000 Hz / 32 bit: java.lang.IllegalStateException",
                DeviceFailureReason.FORMAT_UNSUPPORTED);

        NetError received = codec.read(codec.write(new NetMessage(ID, sent))).getError();

        assertTrue(received.is(ErrorCode.DEVICE_ERROR));
        assertEquals("FORMAT_UNSUPPORTED", received.reason(), "the enum name IS the wire text");
        assertEquals(DeviceFailureReason.FORMAT_UNSUPPORTED, received.failureReason());
    }

    /** UNKNOWN says nothing the far side does not already assume, so it is not
     *  sent - and a refusal that was never a device's carries no reason at all. */
    @Test
    void anUnknownOrAbsentReasonStaysOffTheWireAndReadsAsUnknown() throws JsonProcessingException {
        String unknown = codec.write(new NetMessage(ID, new NetError(ErrorCode.DEVICE_ERROR,
                "cannot open QA403 input", DeviceFailureReason.UNKNOWN)));
        String notADevice = codec.write(new NetMessage(ID,
                new NetError(ErrorCode.BAD_REQUEST, "gen.open needs a rate and a bit depth")));

        assertFalse(unknown.contains("\"reason\""), "the field is absent, not \"UNKNOWN\"");
        assertFalse(notADevice.contains("\"reason\""), "a non-device refusal has no reason");
        assertEquals(DeviceFailureReason.UNKNOWN, codec.read(unknown).getError().failureReason());
        assertEquals(DeviceFailureReason.UNKNOWN,
                codec.read(notADevice).getError().failureReason());
    }

    /** Spec 1 forward compatibility: a reason only a newer bench knows must not
     *  break this build - it reads as UNKNOWN and the message still arrives. */
    @Test
    void aReasonFromANewerPeerDegradesToUnknown() throws JsonProcessingException {
        String json = codec.write(new NetMessage(ID, new NetError("DEVICE_ERROR",
                "cannot open QA403 input", null, "DEVICE_ON_FIRE")));

        NetError received = codec.read(json).getError();

        assertEquals("DEVICE_ON_FIRE", received.reason(), "the raw text survives for the log");
        assertEquals(DeviceFailureReason.UNKNOWN, received.failureReason());
    }

    /** {@code ev.device.error} is ALWAYS about a device, so it always names a
     *  reason - UNKNOWN included - beside the server's raw detail. */
    @Test
    void theDeviceErrorEventCarriesTheReasonBesideTheDetail() throws JsonProcessingException {
        NetMessage received = codec.read(codec.write(
                new NetMessage(MessageType.EV_DEVICE_ERROR)
                        .put(NetFields.DIRECTION, NetFields.INPUT)
                        .put(NetFields.DETAIL, "QA403 detached")
                        .put(NetFields.REASON, DeviceFailureReason.DEVICE_DISCONNECTED.name())));

        assertEquals(MessageType.EV_DEVICE_ERROR, received.getType());
        assertEquals("QA403 detached", received.optString(NetFields.DETAIL));
        assertEquals(DeviceFailureReason.DEVICE_DISCONNECTED,
                DeviceFailureReason.fromName(received.optString(NetFields.REASON)));
        assertEquals(DeviceFailureReason.UNKNOWN,
                DeviceFailureReason.fromName(received.optString(NetFields.BY)),
                "an absent field reads as UNKNOWN, never as a throw");
    }

    @Test
    void eventHasNoId() throws JsonProcessingException {
        String json = codec.write(new NetMessage(MessageType.EV_DEVICE_ERROR)
                .put(NetFields.DIRECTION, NetFields.INPUT)
                .put(NetFields.DETAIL, "QA403 detached"));

        NetMessage received = codec.read(json);

        assertEquals(MessageType.EV_DEVICE_ERROR, received.getType());
        assertNull(received.getId(), "events are never answered, so they carry no id");
        assertEquals("input", received.optString(NetFields.DIRECTION));
        assertEquals("QA403 detached", received.optString(NetFields.DETAIL));
    }

    @Test
    void serverPingUsesANegativeId() throws JsonProcessingException {
        NetMessage received = codec.read(
                codec.write(new NetMessage(MessageType.PING, SERVER_PING_ID)));

        assertEquals(MessageType.PING, received.getType());
        assertEquals(SERVER_PING_ID, received.getId(),
                "server request ids are negative so they cannot collide (spec 4.0)");
    }

    @Test
    void unknownTypeIsToleratedAndStaysReadable() throws JsonProcessingException {
        NetMessage received = codec.read("{\"t\":\"gen.warpFactor\",\"id\":7}");

        assertEquals(MessageType.UNKNOWN, received.getType(),
                "an unknown t must decode so the dispatcher can answer UNSUPPORTED");
        assertEquals("gen.warpFactor", received.getT(),
                "the raw type belongs in the error message");
        assertEquals(7, received.getId());
    }

    @Test
    void unknownFieldsAreKeptNotRejected() throws JsonProcessingException {
        NetMessage received = codec.read(
                "{\"t\":\"hello\",\"id\":1,\"proto\":1,\"futureField\":{\"x\":1}}");

        assertEquals(MessageType.HELLO, received.getType());
        assertEquals(1, received.optInt("proto"));
        assertTrue(codec.write(received).contains("futureField"),
                "an unknown field is ignored by the reader, not deleted from the message");
    }

    @Test
    void absentNumberIsNullNotZero() throws JsonProcessingException {
        // gen.config is a partial update: only the present fields are applied.
        NetMessage received = codec.read(
                "{\"t\":\"gen.config\",\"id\":5,\"genId\":1,\"frequency\":0.0}");

        assertEquals(0.0, received.optDouble(NetFields.FREQUENCY),
                "a present zero is a real value");
        assertNull(received.optDouble(NetFields.AMPLITUDE_VRMS),
                "an absent field must not be applied as 0");
        assertFalse(received.has(NetFields.AMPLITUDE_VRMS));
        assertTrue(received.has(NetFields.GEN_ID));
    }

    @Test
    void typedAccessorsRejectTheWrongJsonType() throws JsonProcessingException {
        NetMessage received = codec.read(
                "{\"t\":\"gen.trim\",\"id\":1,\"hz\":\"1000\",\"loop\":true}");

        assertNull(received.optDouble(NetFields.HZ), "a quoted number is not a number");
        assertEquals("1000", received.optString(NetFields.HZ));
        assertEquals(Boolean.TRUE, received.optBoolean(NetFields.LOOP));
        assertNull(received.optBoolean(NetFields.HZ));
    }

    @Test
    void longFieldsSurviveBeyondIntRange() throws JsonProcessingException {
        long durationSamples = 3_000_000_000L;      // > 2^31, a long sweep record

        NetMessage received = codec.read(codec.write(
                new NetMessage(MessageType.GEN_CONFIG, ID)
                        .put(NetFields.DURATION_SAMPLES, durationSamples)));

        assertEquals(durationSamples, received.optLong(NetFields.DURATION_SAMPLES));
    }

    @Test
    void malformedJsonIsRejected() {
        assertThrows(JsonProcessingException.class, () -> codec.read("{\"t\":"));
    }

    @Test
    void nonObjectJsonIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> codec.read("[1,2,3]"));
    }
}
