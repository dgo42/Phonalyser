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
import java.util.Map;

import org.edgo.audio.measure.net.proto.Beacon;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.net.proto.NetProto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The peer table of spec 2.2, aged by a clock the test turns by hand - a server
 * that is switched off says nothing, so the ONLY thing that removes it from the
 * table is time, and "wait two and a half seconds and look" would be both slow
 * and flaky.
 */
class PeerTableTest {

    private static final String SERVER_NAME = "Bench QA403";
    private static final String PEER_ID = "b7e0-uuid";
    private static final String PEER_NAME = "Bench sound card";
    private static final String PEER_HOST = "192.168.1.40";
    private static final String PEER_APP = "1.1.0";
    private static final long HEARD_AT_MS = 10_000L;
    /** One heard peer plus this server itself. */
    private static final int WITH_ONE_PEER = 2;
    private static final int SELF_ONLY = 1;
    private static final int SELF = 0;
    private static final int FIRST_PEER = 1;

    private final ServerConfig config =
            new ServerConfig(new String[] {"--name", SERVER_NAME});
    /** This server binds nothing here, so the port it announces is the one it
     *  was configured with. */
    private final BoundPorts ports = new BoundPorts(config::getPort);
    private long nowMs = HEARD_AT_MS;
    private final PeerTable peers = new PeerTable(config, ports, () -> nowMs);

    @Test
    void thisServerIsTheFirstRowAndSaysSo() {
        List<Map<String, Object>> servers = peers.servers();

        assertEquals(SELF_ONLY, servers.size());
        Map<String, Object> self = servers.get(SELF);
        assertEquals(config.getServerId(), self.get(NetFields.SERVER_ID));
        assertEquals(SERVER_NAME, self.get(NetFields.NAME));
        assertEquals(config.getPort(), self.get(NetFields.PORT));
        assertEquals(NetProto.PROTO_VERSION, self.get(NetFields.PROTO));
        assertEquals(true, self.get(NetFields.SELF),
                "spec 2.2: the table a web client reads includes the server it "
                        + "read it from");
    }

    @Test
    void aHeardServerIsListedWithTheAddressItsDatagramCameFrom() {
        peers.seen(peer(), PEER_HOST);

        List<Map<String, Object>> servers = peers.servers();

        assertEquals(WITH_ONE_PEER, servers.size());
        Map<String, Object> heard = servers.get(FIRST_PEER);
        assertEquals(PEER_ID, heard.get(NetFields.SERVER_ID));
        assertEquals(PEER_NAME, heard.get(NetFields.NAME));
        assertEquals(PEER_HOST, heard.get(NetFields.HOST),
                "spec 2.1: the payload carries no address because a multi-homed "
                        + "host cannot know which of its own the listener reaches");
        assertEquals(PEER_APP, heard.get(NetFields.APP));
        assertEquals(false, heard.get(NetFields.SELF));
    }

    @Test
    void aPeerSurvivesUntilFourBeaconsHaveBeenMissedAndThenGoes() {
        peers.seen(peer(), PEER_HOST);

        nowMs = HEARD_AT_MS + NetProto.BEACON_EXPIRY_MS;
        assertEquals(WITH_ONE_PEER, peers.servers().size(),
                "at exactly four missed intervals it is still there");

        nowMs = HEARD_AT_MS + NetProto.BEACON_EXPIRY_MS + 1;
        assertEquals(SELF_ONLY, peers.servers().size(),
                "a server that was switched off never says so - expiry is the "
                        + "only thing that removes it");
    }

    @Test
    void aFreshBeaconKeepsAPeerAlive() {
        peers.seen(peer(), PEER_HOST);
        nowMs = HEARD_AT_MS + NetProto.BEACON_EXPIRY_MS;

        peers.seen(peer(), PEER_HOST);
        nowMs = nowMs + NetProto.BEACON_EXPIRY_MS;

        assertEquals(WITH_ONE_PEER, peers.servers().size());
    }

    @Test
    void ourOwnBeaconComingBackThroughLoopbackIsNotAPeer() {
        peers.seen(new Beacon(NetProto.BEACON_MAGIC, NetProto.PROTO_VERSION,
                config.getServerId(), SERVER_NAME, config.getApp(),
                config.getPort()), PEER_HOST);

        List<Map<String, Object>> servers = peers.servers();

        assertEquals(SELF_ONLY, servers.size(),
                "one truth about ourselves, and it comes from the configuration");
        assertTrue((Boolean) servers.get(SELF).get(NetFields.SELF));
        assertFalse(PEER_HOST.equals(servers.get(SELF).get(NetFields.HOST)),
                "not from whichever interface our own datagram left by");
    }

    private Beacon peer() {
        return new Beacon(NetProto.BEACON_MAGIC, NetProto.PROTO_VERSION, PEER_ID,
                PEER_NAME, PEER_APP, NetProto.DEFAULT_PORT);
    }
}
