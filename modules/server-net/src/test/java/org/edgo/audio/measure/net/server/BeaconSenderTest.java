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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;

import org.edgo.audio.measure.net.proto.Beacon;
import org.edgo.audio.measure.net.proto.JsonCodec;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.net.proto.NetProto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What a server puts in the air (spec 2.1) - asserted on the payload rather than
 * on a socket, because multicast loopback is a property of the machine the tests
 * happen to run on and this is a property of the protocol.
 *
 * <p>The second test closes the discovery loop: the datagram this sender writes
 * is fed to a {@link PeerTable} through the same codec a listening server uses,
 * so an encode and a decode that disagreed would fail here and not on a bench.
 */
class BeaconSenderTest {

    private static final String SERVER_NAME = "Bench QA403";
    private static final String PORT = "8500";
    private static final String SENDER_HOST = "192.168.1.41";
    private static final long NOW_MS = 10_000L;
    /** The listener's own row plus the server it just heard. */
    private static final int WITH_THE_SENDER = 2;
    private static final int FIRST_PEER = 1;

    private final JsonCodec codec = new JsonCodec();

    /** Where each configuration keeps its identity, so the sender and the
     *  listener below really are two INSTALLATIONS: spec 2.1 mints the
     *  {@code serverId} once per installation, and a peer table drops a beacon
     *  carrying its own id. */
    private Path installations;
    private ServerConfig config;
    private BeaconSender sender;

    @BeforeEach
    void buildTheSender(@TempDir Path tempDir) {
        installations = tempDir;
        config = new ServerConfig(new String[] {"--name", SERVER_NAME, "--port", PORT},
                idFile("sender"));
        // This sender binds nothing, so it announces the port it was given.
        BoundPorts ports = new BoundPorts(config::getPort);
        sender = new BeaconSender(config, ports, codec,
                new PeerTable(config, ports, () -> NOW_MS), new FakeTicker());
    }

    @Test
    void theDatagramIsThisServersIdentityAndItsOnePort() {
        Beacon announced = sender.beacon();

        assertEquals(NetProto.BEACON_MAGIC, announced.phonalyser());
        assertEquals(NetProto.PROTO_VERSION, announced.proto());
        assertEquals(config.getServerId(), announced.serverId());
        assertEquals(SERVER_NAME, announced.name());
        assertEquals(config.getApp(), announced.app());
        assertEquals(Integer.parseInt(PORT), announced.port(),
                "the operator's chosen port, not the default - a client that found "
                        + "this server by beacon opens its control channel on it, and "
                        + "polls /info on the same number");
    }

    @Test
    void aServerThatHearsThisDatagramListsTheSenderAsAPeer()
            throws JsonProcessingException {
        ServerConfig listener = new ServerConfig(new String[] {"--name", "Bench tablet"},
                idFile("listener"));
        PeerTable heard = new PeerTable(listener,
                new BoundPorts(listener::getPort), () -> NOW_MS);

        heard.seen(codec.read(codec.write(sender.beacon()), Beacon.class), SENDER_HOST);

        List<Map<String, Object>> servers = heard.servers();
        assertEquals(WITH_THE_SENDER, servers.size());
        Map<String, Object> peer = servers.get(FIRST_PEER);
        assertEquals(config.getServerId(), peer.get(NetFields.SERVER_ID));
        assertEquals(SERVER_NAME, peer.get(NetFields.NAME));
        assertEquals(SENDER_HOST, peer.get(NetFields.HOST));
        assertEquals(Integer.parseInt(PORT), peer.get(NetFields.PORT),
                "the port a client will open its control channel on");
        assertEquals(NetProto.PROTO_VERSION, peer.get(NetFields.PROTO));
    }

    /** One installation's identity file, under this test's own directory. */
    private Path idFile(String installation) {
        return installations.resolve(installation).resolve("server-id");
    }
}
