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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import org.edgo.audio.measure.net.proto.Beacon;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.net.proto.NetProto;

/**
 * Every Phonalyser server this one can hear, so a client that cannot multicast
 * gets the whole LAN from one reachable address - the peer table of spec 2.2,
 * served as {@code GET /servers}.
 *
 * <p><b>Expiry, not departure.</b>  A server that is switched off says nothing;
 * it simply stops beaconing.  An entry therefore lives for four missed intervals
 * ({@link NetProto#BEACON_EXPIRY_MS}, 2 s) - the same threshold philosophy as the
 * keepalive of spec 4.1 - and is dropped when {@link #servers()} next looks.
 * Time comes in through a supplier so a test can jump those two seconds instead
 * of sleeping them.
 *
 * <p><b>Self is a row, not a peer.</b>  Spec 2.2 says the table includes this
 * server, and it is built from the configuration rather than from our own
 * beacon coming back through multicast loopback: that copy is not guaranteed to
 * arrive at all, and when it does its source address is whichever interface the
 * datagram left by.  Our own {@code serverId} is ignored on the way in for the
 * same reason - one truth about ourselves, and it never expires.
 *
 * <p>The address of a peer is taken from the datagram it arrived in (spec 2.1:
 * "the payload deliberately carries no address - multi-homed hosts lie").
 */
public final class PeerTable {

    /** What {@link #selfHost} answers when the host has no resolvable address -
     *  a client reading {@code /servers} from this very server can still reach
     *  it, and a wrong LAN address would be worse than an obvious one. */
    private static final String LOOPBACK_HOST = "127.0.0.1";

    private final ServerConfig config;
    /** The port the front BOUND - what the self row must publish, because an
     *  ephemeral {@code --port 0} leaves the configured value at 0 and no
     *  client can dial that. */
    private final BoundPorts ports;
    /** Wall clock in milliseconds, injected: the expiry of spec 2.2 is the whole
     *  behaviour here, and a test must be able to reach it without waiting. */
    private final LongSupplier clock;
    /** The address this server publishes for itself, resolved once - an
     *  operator-chosen {@code --bind} when there is one, because that is the
     *  interface it actually listens on. */
    private final String selfHost;

    private final Map<String, Peer> peers = new ConcurrentHashMap<>();

    public PeerTable(ServerConfig config, BoundPorts ports, LongSupplier clock) {
        this.config = config;
        this.ports = ports;
        this.clock = clock;
        this.selfHost = resolveSelfHost(config);
    }

    /**
     * Records a beacon that just arrived from {@code host}.  Our own datagram -
     * multicast loopback hands it back to us - is ignored: {@link #servers()}
     * already knows who we are, from configuration rather than from the network.
     */
    public void seen(Beacon beacon, String host) {
        if (beacon == null || beacon.serverId() == null
                || beacon.serverId().equals(config.getServerId())) {
            return;
        }
        peers.put(beacon.serverId(), new Peer(beacon, host, clock.getAsLong()));
    }

    /**
     * The {@code servers} array of spec 2.2: this server first, then every peer
     * heard within the last {@link NetProto#BEACON_EXPIRY_MS}.  Expired entries
     * are dropped here rather than by a timer - the table is only ever read on
     * an HTTP request, so nothing is gained by a thread that ages it in the dark.
     */
    public List<Map<String, Object>> servers() {
        long now = clock.getAsLong();
        List<Map<String, Object>> servers = new ArrayList<>();
        servers.add(self());
        for (Iterator<Peer> it = peers.values().iterator(); it.hasNext();) {
            Peer peer = it.next();
            if (now - peer.seenAtMs() > NetProto.BEACON_EXPIRY_MS) {
                it.remove();
            } else {
                Beacon heard = peer.beacon();
                servers.add(row(heard.serverId(), heard.name(), peer.host(),
                        heard.port(), heard.app(), heard.proto(), false));
            }
        }
        return servers;
    }

    private Map<String, Object> self() {
        return row(config.getServerId(), config.getName(), selfHost, ports.port(),
                config.getApp(), NetProto.PROTO_VERSION, true);
    }

    /** One entry of spec 2.2, in the field order the spec writes it. */
    private Map<String, Object> row(String serverId, String name, String host,
            int port, String app, int proto, boolean self) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put(NetFields.SERVER_ID, serverId);
        entry.put(NetFields.NAME, name);
        entry.put(NetFields.HOST, host);
        entry.put(NetFields.PORT, port);
        entry.put(NetFields.APP, app);
        entry.put(NetFields.PROTO, proto);
        entry.put(NetFields.SELF, self);
        return entry;
    }

    private String resolveSelfHost(ServerConfig serverConfig) {
        if (serverConfig.getBind() != null) {
            return serverConfig.getBind();
        }
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            return LOOPBACK_HOST;
        }
    }

    /** One heard server: what it said about itself, where it said it from, and
     *  when - the three things an expiring table needs. */
    private record Peer(Beacon beacon, String host, long seenAtMs) {
    }
}
