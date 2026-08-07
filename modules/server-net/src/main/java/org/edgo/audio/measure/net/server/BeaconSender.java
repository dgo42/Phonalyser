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

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.StandardSocketOptions;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;

import org.edgo.audio.measure.net.proto.Beacon;
import org.edgo.audio.measure.net.proto.JsonCodec;
import org.edgo.audio.measure.net.proto.NetInterfaces;
import org.edgo.audio.measure.net.proto.NetProto;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * Discovery, both halves of it (spec 2): this server announces itself on the
 * multicast group every 500 ms, and it listens on the same group so its
 * {@link PeerTable} can answer {@code GET /servers} for clients that cannot
 * multicast themselves - a browser, above all.
 *
 * <p><b>One socket, two directions.</b>  The group is joined once; the periodic
 * announcement, the probe answer and the peer feed all use that socket.  A
 * second socket on the same port would be a second membership fighting the
 * first for datagrams.
 *
 * <p><b>The probe.</b>  Spec 2.1 lets a client send the single byte {@code '?'}
 * to the group and have every server answer immediately, UNICAST to the probe's
 * source - that is what makes a "Scan" button instant instead of a 500 ms wait.
 * The answer is the ordinary beacon; there is no second payload to keep in sync.
 *
 * <p><b>"And once immediately on any change".</b>  One announcement goes out the
 * moment the server starts.  Nothing else can change it while the server runs:
 * the payload carries only identity and the port (spec 2.1), all fixed at start-up
 * - a device appearing or a lock changing is told to CONNECTED clients through
 * {@code ev.devices.changed}, not to the LAN.
 *
 * <p><b>Failure is not fatal.</b>  A host with multicast switched off, or an
 * interface that refuses the group, costs discovery and nothing else: the server
 * still serves WebSocket and HTTP, and a client can still reach it by address.
 * So every fault here is logged and swallowed rather than thrown at start-up.
 */
@Log4j2
@RequiredArgsConstructor
public final class BeaconSender {

    /** Receive buffer: a beacon is a small JSON object and the probe is one
     *  byte, so anything larger than this is not ours. */
    private static final int DATAGRAM_BYTES = 2048;
    private static final String RECEIVER_THREAD = "net-beacon";

    private final ServerConfig config;
    /** The port the front BOUND.  Spec 2.1 makes the datagram the address a
     *  client dials, so an ephemeral {@code --port 0} announced as 0 would put
     *  a server on the LAN that nobody can reach. */
    private final BoundPorts ports;
    private final JsonCodec codec;
    private final PeerTable peers;
    /** The 500 ms announcement period, injected like every other periodic task in
     *  this module - the same shared scheduler that carries the keepalives. */
    private final Ticker ticker;

    /** The joined socket; null until {@link #start()} succeeded, and the flag
     *  that stops the receive loop when {@link #stop()} closes it.  Read by the
     *  receiver thread and the ticker thread, written by whoever starts or stops
     *  the server. */
    private volatile MulticastSocket socket;
    /** The interfaces the group was joined on - each announcement goes out once
     *  per entry, because a beacon leaves on ONE adapter and a client on the
     *  physical LAN never hears the one that left on a virtual leg.  Empty when
     *  the join fell back to the kernel's single default. */
    private volatile List<NetworkInterface> memberships = List.of();
    private volatile boolean running;

    /** Joins the group, announces this server once, and starts announcing every
     *  {@link NetProto#BEACON_INTERVAL_MS} and listening for peers and probes.
     *
     *  <p>Joined - and announced - on EVERY candidate interface
     *  ({@link NetInterfaces#multicastCandidates()}): membership and sends are
     *  per-adapter, and the kernel's default pick on a multi-homed host (any
     *  machine with VMware/VPN adapters) is how a server one desk away stayed
     *  undiscovered while being perfectly reachable by address. */
    public void start() {
        try {
            MulticastSocket opened = new MulticastSocket(null);
            // Before the bind, and needed: several Phonalyser processes on one
            // developer machine must all be able to hear the group.
            opened.setReuseAddress(true);
            opened.bind(new InetSocketAddress(NetProto.BEACON_PORT));
            opened.setTimeToLive(NetProto.BEACON_TTL);
            InetSocketAddress group = new InetSocketAddress(groupAddress(),
                    NetProto.BEACON_PORT);
            List<NetworkInterface> joined = new ArrayList<>();
            for (NetworkInterface nic : NetInterfaces.multicastCandidates()) {
                try {
                    opened.joinGroup(group, nic);
                    joined.add(nic);
                } catch (IOException | RuntimeException e) {
                    // An adapter that refuses the join is not spoken on.
                    if (log.isDebugEnabled()) {
                        log.debug("net beacon: no join on {}: {}", nic.getName(),
                                e.toString());
                    }
                }
            }
            if (joined.isEmpty()) {
                // No candidate took the join - the kernel's choice is better
                // than announcing nowhere.
                opened.joinGroup(group, null);
            }
            memberships = List.copyOf(joined);
            socket = opened;
            running = true;
        } catch (IOException | RuntimeException e) {
            if (log.isWarnEnabled()) {
                log.warn("net beacon: discovery is off - cannot join {}:{} ({})",
                        NetProto.BEACON_GROUP, NetProto.BEACON_PORT, e.toString());
            }
            return;
        }
        Thread receiver = new Thread(this::receiveLoop, RECEIVER_THREAD);
        receiver.setDaemon(true);
        receiver.start();
        announce();
        ticker.start(NetProto.BEACON_INTERVAL_MS, this::announce);
        if (log.isInfoEnabled()) {
            log.info("net beacon: announcing '{}' on {}:{} every {} ms via {} interface(s)",
                    config.getName(), NetProto.BEACON_GROUP, NetProto.BEACON_PORT,
                    NetProto.BEACON_INTERVAL_MS,
                    memberships.isEmpty() ? "the default" : memberships.size());
        }
    }

    /** Stops announcing and listening.  Idempotent; closing the socket is what
     *  ends the receive loop, which is blocked inside it. */
    public void stop() {
        running = false;
        ticker.stop();
        MulticastSocket open = socket;
        socket = null;
        if (open != null) {
            open.close();
        }
    }

    /** What this server says about itself (spec 2.1) - the datagram's whole
     *  content, and the answer to a probe. */
    public Beacon beacon() {
        return Beacon.of(config.getServerId(), config.getName(), config.getApp(),
                ports.port());
    }

    /** One announcement to the group - once per joined interface, because the
     *  datagram leaves on exactly one adapter per send. */
    private void announce() {
        MulticastSocket open = socket;
        if (open == null) {
            return;
        }
        List<NetworkInterface> vias = memberships;
        if (vias.isEmpty()) {
            send(groupAddress(), NetProto.BEACON_PORT);
            return;
        }
        for (NetworkInterface via : vias) {
            try {
                open.setOption(StandardSocketOptions.IP_MULTICAST_IF, via);
            } catch (IOException | RuntimeException e) {
                if (log.isDebugEnabled()) {
                    log.debug("net beacon: cannot announce via {}: {}", via.getName(),
                            e.toString());
                }
                continue;
            }
            send(groupAddress(), NetProto.BEACON_PORT);
        }
    }

    private void send(InetAddress address, int port) {
        MulticastSocket open = socket;
        if (open == null) {
            return;
        }
        try {
            byte[] payload = codec.write(beacon()).getBytes(StandardCharsets.UTF_8);
            open.send(new DatagramPacket(payload, payload.length, address, port));
        } catch (IOException | RuntimeException e) {
            if (log.isDebugEnabled()) {
                log.debug("net beacon: send to {}:{} failed: {}", address, port,
                        e.toString());
            }
        }
    }

    /**
     * Everything that arrives on the group: another server's beacon, which feeds
     * the peer table, or a client's probe, which is answered at once.
     *
     * <p>Its own thread because the receive blocks, and a daemon one because the
     * loop ends by having its socket closed under it - the {@link IOException}
     * that follows is the stop signal, not a fault.
     */
    private void receiveLoop() {
        byte[] buffer = new byte[DATAGRAM_BYTES];
        while (running) {
            MulticastSocket open = socket;
            if (open == null) {
                return;
            }
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                open.receive(packet);
                accept(packet);
            } catch (IOException e) {
                if (!running || open.isClosed()) {
                    return;                     // the stop signal, not a fault
                }
                if (log.isDebugEnabled()) {
                    log.debug("net beacon: receive failed: {}", e.toString());
                }
            }
        }
    }

    /** One received datagram: the probe of spec 2.1, a beacon, or something that
     *  is not ours at all (the group is shared with whoever else joined it). */
    private void accept(DatagramPacket packet) {
        if (packet.getLength() == 1
                && packet.getData()[packet.getOffset()] == NetProto.BEACON_PROBE) {
            send(packet.getAddress(), packet.getPort());
            return;
        }
        String json = new String(packet.getData(), packet.getOffset(),
                packet.getLength(), StandardCharsets.UTF_8);
        try {
            Beacon heard = codec.read(json, Beacon.class);
            if (heard.phonalyser() == NetProto.BEACON_MAGIC) {
                peers.seen(heard, packet.getAddress().getHostAddress());
            }
        } catch (JsonProcessingException | RuntimeException e) {
            if (log.isDebugEnabled()) {
                log.debug("net beacon: foreign datagram from {} ignored: {}",
                        packet.getAddress(), e.toString());
            }
        }
    }

    private InetAddress groupAddress() {
        try {
            return InetAddress.getByName(NetProto.BEACON_GROUP);
        } catch (IOException e) {
            // The group is a literal dotted quad in NetProto, so this cannot
            // fail for a reason a running server could do anything about.
            throw new IllegalStateException(
                    "the discovery group is not an address: " + NetProto.BEACON_GROUP, e);
        }
    }
}
