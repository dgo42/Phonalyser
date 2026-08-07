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
 * The client half of discovery (spec 2.1): it listens on the multicast group for
 * the beacons every server announces itself with, and can ask them all to speak
 * up at once.
 *
 * <p><b>The address comes from the datagram, not from the payload.</b>  Spec 2.1
 * leaves the address out of the beacon on purpose - "multi-homed hosts lie" -
 * so the source address of the packet is what the listener is told, and it is
 * the only address the receiver is known to be able to reach the server at.
 *
 * <p><b>The probe.</b>  Spec 2.1 lets a client send the single byte {@code '?'}
 * to the group and have every server answer immediately and unicast, which is
 * what makes a "Scan" button instant instead of a wait for the next 500 ms
 * announcement.  The answer is an ordinary beacon and arrives through the same
 * receive loop.
 *
 * <p><b>Failure is not fatal.</b>  A host with multicast switched off costs
 * discovery and nothing else: a server can still be reached by an address the
 * operator typed in, which is exactly what the manual server list is for.  So
 * every fault here is logged and swallowed rather than thrown at whoever opened
 * the dialog.
 *
 * <p>Expiry - marking a server offline after four missed beacons - is
 * deliberately NOT here: what "offline" means on screen belongs to the list that
 * shows it, and this type only reports what it hears, when it hears it.
 */
@Log4j2
@RequiredArgsConstructor
public final class BeaconListener {

    /** What a heard server is reported to.  Called on the receive thread. */
    @FunctionalInterface
    public interface Listener {

        /** One beacon (spec 2.1) and the address it arrived FROM. */
        void onBeacon(Beacon beacon, String host);
    }

    /** Receive buffer: a beacon is a small JSON object and the probe is one
     *  byte, so anything larger than this is not ours. */
    private static final int DATAGRAM_BYTES = 2048;
    private static final String RECEIVER_THREAD = "net-discovery";

    private final JsonCodec codec;
    private final Listener listener;

    /** The joined socket; null until {@link #start()} succeeded, and closing it
     *  is what ends the receive loop blocked inside it. */
    private volatile MulticastSocket socket;
    /** The interfaces the group was actually joined on - where {@link #probe()}
     *  must ask, because a probe sent on one adapter reaches only the servers
     *  behind that adapter.  Empty when the join fell back to the kernel's
     *  single default. */
    private volatile List<NetworkInterface> memberships = List.of();
    private volatile boolean running;

    /** Joins the discovery group and starts listening.  Idempotent in the sense
     *  that matters: a listener that could not join simply hears nothing.
     *
     *  <p>The group is joined on EVERY candidate interface
     *  ({@link NetInterfaces#multicastCandidates()}): membership is
     *  per-interface, and the kernel's single default pick is exactly how a
     *  bench behind the "wrong" adapter - the physical LAN on a machine whose
     *  VMware host adapters win the multicast route, or a VM's bridged leg -
     *  was never heard. */
    public void start() {
        if (socket != null) {
            return;
        }
        try {
            MulticastSocket opened = new MulticastSocket(null);
            // Before the bind, and needed: a Phonalyser server on this very
            // machine already holds the port, and both must hear the group.
            opened.setReuseAddress(true);
            opened.bind(new InetSocketAddress(NetProto.BEACON_PORT));
            opened.setTimeToLive(NetProto.BEACON_TTL);
            InetSocketAddress group = new InetSocketAddress(group(), NetProto.BEACON_PORT);
            List<NetworkInterface> joined = new ArrayList<>();
            for (NetworkInterface nic : NetInterfaces.multicastCandidates()) {
                try {
                    opened.joinGroup(group, nic);
                    joined.add(nic);
                } catch (IOException | RuntimeException e) {
                    // An adapter that refuses the join simply is not listened on.
                    if (log.isDebugEnabled()) {
                        log.debug("net discovery: no join on {}: {}", nic.getName(),
                                e.toString());
                    }
                }
            }
            if (joined.isEmpty()) {
                // No candidate took the join - fall back to the kernel's choice
                // rather than hearing nothing at all.
                opened.joinGroup(group, null);
            }
            memberships = List.copyOf(joined);
            socket = opened;
            running = true;
        } catch (IOException | RuntimeException e) {
            if (log.isWarnEnabled()) {
                log.warn("net discovery: off - cannot join {}:{} ({})",
                        NetProto.BEACON_GROUP, NetProto.BEACON_PORT, e.toString());
            }
            return;
        }
        Thread receiver = new Thread(this::receiveLoop, RECEIVER_THREAD);
        receiver.setDaemon(true);
        receiver.start();
        if (log.isInfoEnabled()) {
            log.info("net discovery: listening on {}:{} via {} interface(s)",
                    NetProto.BEACON_GROUP, NetProto.BEACON_PORT,
                    memberships.isEmpty() ? "the default" : memberships.size());
        }
    }

    /** Stops listening.  Idempotent; closing the socket is the stop signal the
     *  blocked receive loop gets. */
    public void stop() {
        running = false;
        MulticastSocket open = socket;
        socket = null;
        if (open != null) {
            open.close();
        }
    }

    /** Asks every server on the group to announce itself at once (spec 2.1) -
     *  the single byte {@code '?'}, sent once per joined interface: the question
     *  reaches only the segment it leaves on, so a multi-homed client asks on
     *  all of them.  Silent when discovery could not start: the probe is an
     *  optimisation, not the mechanism. */
    public void probe() {
        MulticastSocket open = socket;
        if (open == null) {
            return;
        }
        byte[] payload = {NetProto.BEACON_PROBE};
        DatagramPacket packet = new DatagramPacket(payload, payload.length, group(),
                NetProto.BEACON_PORT);
        List<NetworkInterface> vias = memberships;
        if (vias.isEmpty()) {
            probeVia(open, packet, null);
            return;
        }
        for (NetworkInterface via : vias) {
            probeVia(open, packet, via);
        }
    }

    /** One probe datagram out of one interface - or out of the kernel's default
     *  when {@code via} is null. */
    private void probeVia(MulticastSocket open, DatagramPacket packet, NetworkInterface via) {
        try {
            if (via != null) {
                open.setOption(StandardSocketOptions.IP_MULTICAST_IF, via);
            }
            open.send(packet);
        } catch (IOException | RuntimeException e) {
            if (log.isDebugEnabled()) {
                log.debug("net discovery: probe not sent via {}: {}",
                        via == null ? "default" : via.getName(), e.toString());
            }
        }
    }

    /**
     * Everything that arrives on the group.  Its own thread because the receive
     * blocks, and a daemon one because the loop ends by having its socket closed
     * under it - the {@link IOException} that follows is the stop signal, not a
     * fault.
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
                    log.debug("net discovery: receive failed: {}", e.toString());
                }
            }
        }
    }

    /**
     * One received datagram: a server's beacon, somebody else's probe coming
     * back off the group, or something that is not ours at all - the group is
     * shared with whoever else joined it, so the {@code phonalyser} marker of
     * spec 2.1 is what a beacon is recognised by.
     *
     * <p>Package-private: the receive loop calls it, and so does a test, which
     * is how the parsing is proved without a network that may not carry
     * multicast at all.
     */
    void accept(DatagramPacket packet) {
        if (packet.getLength() == 1
                && packet.getData()[packet.getOffset()] == NetProto.BEACON_PROBE) {
            return;                             // another client's Scan button
        }
        String json = new String(packet.getData(), packet.getOffset(), packet.getLength(),
                StandardCharsets.UTF_8);
        Beacon heard;
        try {
            heard = codec.read(json, Beacon.class);
        } catch (JsonProcessingException | RuntimeException e) {
            if (log.isDebugEnabled()) {
                log.debug("net discovery: foreign datagram from {} ignored: {}",
                        packet.getAddress(), e.toString());
            }
            return;
        }
        if (heard.phonalyser() != NetProto.BEACON_MAGIC) {
            return;
        }
        try {
            listener.onBeacon(heard, packet.getAddress().getHostAddress());
        } catch (RuntimeException e) {
            if (log.isErrorEnabled()) {
                log.error("net discovery: listener failed (continuing)", e);
            }
        }
    }

    private InetAddress group() {
        try {
            return InetAddress.getByName(NetProto.BEACON_GROUP);
        } catch (IOException e) {
            // The group is a literal dotted quad in the protocol constants, so
            // this cannot fail for a reason a running client could act on.
            throw new IllegalStateException(
                    "the discovery group is not an address: " + NetProto.BEACON_GROUP, e);
        }
    }
}
