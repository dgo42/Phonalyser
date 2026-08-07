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

import com.fasterxml.jackson.core.JsonProcessingException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Discovery datagram conformance (spec 2.1): the exact field names a web or
 * desktop client looks for, tolerance for a foreign packet on the shared group,
 * and the transport constants both ends must agree on.
 */
class BeaconTest {

    private static final String SERVER_ID = "b7e0f0c2-6f0e-4a4a-9a3f-9a0e2b1c4d5e";
    private static final String NAME = "Bench QA403";
    private static final String APP = "1.2.0";

    private final JsonCodec codec = new JsonCodec();

    @Test
    void beaconRoundTrips() throws JsonProcessingException {
        Beacon sent = Beacon.of(SERVER_ID, NAME, APP, NetProto.DEFAULT_PORT);

        Beacon received = codec.read(codec.write(sent), Beacon.class);

        assertEquals(sent, received, "the datagram must survive unchanged");
        assertEquals(NetProto.BEACON_MAGIC, received.phonalyser());
        assertEquals(NetProto.PROTO_VERSION, received.proto());
        assertEquals(SERVER_ID, received.serverId());
        assertEquals(NAME, received.name());
        assertEquals(APP, received.app());
        assertEquals(NetProto.DEFAULT_PORT, received.port());
    }

    @Test
    void encodedFieldNamesAreTheSpecNames() throws JsonProcessingException {
        String json = codec.write(Beacon.of(SERVER_ID, NAME, APP, 8377));

        assertEquals("{\"phonalyser\":1,\"proto\":1,\"serverId\":\"" + SERVER_ID
                + "\",\"name\":\"" + NAME + "\",\"app\":\"" + APP
                + "\",\"port\":8377}", json,
                "the web client reads these names verbatim");
    }

    @Test
    void specSampleDatagramParses() throws JsonProcessingException {
        String datagram = "{ \"phonalyser\": 1, \"proto\": 1, \"serverId\": \"b7e0-uuid\","
                + " \"name\": \"Bench QA403\", \"app\": \"1.2.0\", \"port\": 8377 }";

        Beacon received = codec.read(datagram, Beacon.class);

        assertEquals("b7e0-uuid", received.serverId());
        assertEquals(NAME, received.name());
        assertEquals(8377, received.port());
    }

    @Test
    void unknownFieldsAreIgnored() throws JsonProcessingException {
        String datagram = "{\"phonalyser\":1,\"proto\":1,\"serverId\":\"x\",\"name\":\"y\","
                + "\"app\":\"1.2.0\",\"port\":8377,\"futureField\":[1,2,3]}";

        Beacon received = codec.read(datagram, Beacon.class);

        assertEquals("x", received.serverId(),
                "a newer server's extra field must not break an older client");
    }

    @Test
    void foreignDatagramLacksTheMarker() throws JsonProcessingException {
        Beacon received = codec.read("{\"hello\":\"some other multicast app\"}",
                Beacon.class);

        assertNotEquals(NetProto.BEACON_MAGIC, received.phonalyser(),
                "no phonalyser marker: the receiver drops the packet");
        assertNull(received.serverId());
    }

    @Test
    void transportConstantsMatchTheSpec() {
        assertEquals("239.255.83.77", NetProto.BEACON_GROUP);
        assertEquals(8377, NetProto.BEACON_PORT);
        assertEquals(NetProto.DEFAULT_PORT, NetProto.BEACON_PORT,
                "one number for the whole bridge: the beacon's UDP port IS the "
                        + "server's TCP port, and the two cannot drift because one is "
                        + "defined as the other");
        assertEquals(1, NetProto.BEACON_TTL);
        assertEquals(500, NetProto.BEACON_INTERVAL_MS,
                "the beacon repeats every 500 ms (spec 2.1)");
        assertEquals(2_000, NetProto.BEACON_EXPIRY_MS,
                "four missed intervals expire a peer (spec 2.2)");
        assertEquals(NetProto.MAX_MISSED_PINGS * NetProto.BEACON_INTERVAL_MS,
                NetProto.BEACON_EXPIRY_MS,
                "same threshold philosophy as the keepalive: 4 missed intervals");
        assertEquals(0x3F, NetProto.BEACON_PROBE, "the probe byte is '?'");
        assertEquals(8377, NetProto.DEFAULT_PORT,
                "one port for both planes - the HTTP endpoints of spec 3 and the "
                        + "WebSocket upgrade of spec 4");
        assertEquals(500, NetProto.PING_INTERVAL_MS);
        assertEquals(4, NetProto.MAX_MISSED_PINGS);
        assertEquals(50 * 1024 * 1024, NetProto.MAX_UPLOAD_BYTES);
        assertEquals(1, NetProto.PROTO_VERSION, "PROTO is a single integer, 1");
        assertEquals(1, NetProto.PROTO_MIN_VERSION,
                "v1 is also the oldest version this build serves");
        assertTrue(NetProto.PROTO_MIN_VERSION <= NetProto.PROTO_VERSION,
                "the server's own range must not be empty (spec 1)");
    }
}
