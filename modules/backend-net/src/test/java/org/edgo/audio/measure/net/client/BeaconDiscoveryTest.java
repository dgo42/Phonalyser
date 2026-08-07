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

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.edgo.audio.measure.net.proto.Beacon;
import org.edgo.audio.measure.net.proto.JsonCodec;
import org.edgo.audio.measure.net.proto.NetProto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Discovery's parsing contract (spec 2.1), driven through the seam
 * {@link BeaconListener} declares for it.
 *
 * <p><b>Why no socket.</b>  Joining a multicast group is exactly the part of
 * discovery that a build host may refuse - and the class treats that refusal as
 * "discovery is off", not as a failure, because a server can still be reached by
 * an address the operator typed in.  A test that needed the group would therefore
 * be testing the host, and would be skipped on the machines it matters on.  What
 * is worth proving is what the receive loop DOES with a datagram, so the packets
 * are handed to {@code accept} directly: everything above the socket is the
 * shipping code.
 *
 * <p><b>The buffer is reused on purpose.</b>  The real loop receives every
 * datagram into ONE array and sets the length per packet, so a beacon that
 * followed a longer one would be read with the previous packet's tail still in
 * the buffer if the offset and length were ignored.  That is the failure this
 * fixture is shaped to catch, not an incidental detail of it.
 */
class BeaconDiscoveryTest {

    private static final String SERVER_ID = "b7e0c4d2-0000-4000-8000-000000000001";
    /** Long enough that the SHORTER beacon after it cannot hide a stale tail. */
    private static final String LONG_NAME = "Bench QA403 in the far corner of the lab";
    private static final String SHORT_NAME = "Bench B";
    private static final String APP = "1.2.0";
    /** Spec 2.1: the payload carries no address, so this is what the listener
     *  must report - and it deliberately differs from anything in the JSON. */
    private static final String SOURCE_HOST = "192.168.1.40";
    private static final String OTHER_HOST = "192.168.1.41";
    /** The receive buffer of the real loop. */
    private static final int DATAGRAM_BYTES = 2048;
    /** A datagram from whatever else shares the group. */
    private static final String FOREIGN = "not JSON at all";
    /** Valid JSON, right shape, but not ours - the marker is what decides. */
    private static final String UNMARKED =
            "{\"proto\":1,\"serverId\":\"x\",\"name\":\"Someone else\"}";

    private final JsonCodec codec = new JsonCodec();
    private final List<Heard> heard = new ArrayList<>();
    private final BeaconListener listener =
            new BeaconListener(codec, (beacon, host) -> heard.add(new Heard(beacon, host)));

    /** The reused receive buffer and the packet that wraps it - the real loop's
     *  arrangement, not a fresh array per datagram. */
    private final byte[] buffer = new byte[DATAGRAM_BYTES];
    private final DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

    @Test
    void aBeaconIsReportedWithTheAddressItCameFromAndNotOneFromThePayload()
            throws Exception {
        deliver(beaconJson(LONG_NAME), SOURCE_HOST);

        assertEquals(1, heard.size(), "the beacon was not recognised");
        Heard first = heard.get(0);
        assertEquals(SERVER_ID, first.beacon().serverId(),
                "clients key their remembered-server list on the id, never on the IP");
        assertEquals(LONG_NAME, first.beacon().name());
        assertEquals(NetProto.DEFAULT_PORT, first.beacon().port());
        assertEquals(SOURCE_HOST, first.host(),
                "spec 2.1: the receiver takes the server's IP from the datagram SOURCE, "
                        + "because the payload deliberately carries none - a multi-homed "
                        + "host cannot know which of its addresses the listener can reach");
    }

    @Test
    void aShorterBeaconAfterALongerOneIsNotReadWithTheStaleTail() throws Exception {
        deliver(beaconJson(LONG_NAME), SOURCE_HOST);
        deliver(beaconJson(SHORT_NAME), OTHER_HOST);

        assertEquals(2, heard.size());
        Heard second = heard.get(1);
        assertEquals(SHORT_NAME, second.beacon().name(),
                "the loop receives every datagram into ONE buffer, so a beacon must be "
                        + "read at the packet's offset and LENGTH - otherwise the previous, "
                        + "longer packet's tail is still there and the JSON is garbage");
        assertEquals(OTHER_HOST, second.host(),
                "and two servers on one group must not be merged into one entry");
    }

    @Test
    void theProbeComingBackOffTheGroupIsNotAServer() throws Exception {
        deliver(new byte[] {NetProto.BEACON_PROBE}, SOURCE_HOST);

        assertTrue(heard.isEmpty(),
                "spec 2.1's probe is sent TO the group, so a client hears its own - and "
                        + "another client's Scan button is not a bench to connect to");
    }

    @Test
    void aDatagramThatIsNotOursIsIgnoredRatherThanThrown() throws Exception {
        deliver(FOREIGN.getBytes(StandardCharsets.UTF_8), SOURCE_HOST);
        deliver(UNMARKED.getBytes(StandardCharsets.UTF_8), SOURCE_HOST);

        assertTrue(heard.isEmpty(),
                "the group is shared with whoever else joined it, and the phonalyser "
                        + "marker is what a beacon is recognised by - a foreign datagram "
                        + "must not kill the receive loop");
    }

    @Test
    void probingBeforeDiscoveryStartedIsSilent() {
        assertDoesNotThrow(listener::probe,
                "the probe is an optimisation, not the mechanism: a host that cannot "
                        + "multicast still reaches a server the operator typed in");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** One beacon of spec 2.1, as the server puts it on the group. */
    private byte[] beaconJson(String name) throws Exception {
        return codec.write(Beacon.of(SERVER_ID, name, APP, NetProto.DEFAULT_PORT))
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Delivers one datagram exactly as the receive loop would: into the SHARED
     *  buffer, with the length set and the sender's address attached. */
    private void deliver(byte[] payload, String from) throws Exception {
        System.arraycopy(payload, 0, buffer, 0, payload.length);
        packet.setLength(payload.length);
        packet.setAddress(InetAddress.getByName(from));
        listener.accept(packet);
    }

    /** One reported beacon and the address it arrived from. */
    private record Heard(Beacon beacon, String host) {
    }
}
