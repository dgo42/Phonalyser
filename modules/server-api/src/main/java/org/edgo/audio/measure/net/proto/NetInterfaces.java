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

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import lombok.experimental.UtilityClass;

/**
 * Which interfaces discovery (spec 2.1) must speak on.
 *
 * <p>Multicast on a multi-homed host is PER-INTERFACE: a group joined with no
 * interface named exists on whichever single adapter the kernel picks, and a
 * datagram sent without one leaves on that adapter only.  A machine with
 * virtualisation or VPN software installed is multi-homed as a rule - VMware's
 * host adapters routinely win the default multicast route - and that is how a
 * server one desk away goes undiscovered while being perfectly reachable by
 * address.  So both halves of discovery enumerate: the listener joins the
 * group on every candidate, the announcer sends its beacon once per candidate.
 *
 * <p>Shared here, beside the protocol constants the sockets are built from,
 * because the client's listener and the server's announcer must agree on what
 * counts as a candidate - and this module is the one both already depend on.
 */
@UtilityClass
public class NetInterfaces {

    /**
     * Every interface a discovery socket should speak on: up, multicast-capable,
     * not loopback.  Virtual adapters are deliberately INCLUDED - a VM guest on
     * a host-only or bridged network is exactly who must be able to hear the
     * beacon.  Interfaces that cannot answer the probes, and an enumeration that
     * fails outright, are simply left out - an empty answer means "let the
     * kernel choose", which is the pre-enumeration behaviour.
     */
    public List<NetworkInterface> multicastCandidates() {
        List<NetworkInterface> found = new ArrayList<>();
        for (NetworkInterface nic : upNonLoopback()) {
            try {
                if (nic.supportsMulticast()) {
                    found.add(nic);
                }
            } catch (IOException e) {
                // This adapter would not even answer its flags - not a candidate.
            }
        }
        return found;
    }

    /**
     * Every IPv4 address a server bound to all interfaces is reachable at - what
     * the start-up banner prints so the operator can paste a working URL.
     *
     * <p>The same up/non-loopback candidates as {@link #multicastCandidates()},
     * but WITHOUT the multicast requirement: an adapter that cannot carry the
     * discovery beacon (some VPN tunnels) still serves HTTP and WebSocket
     * perfectly well, and the banner must not hide it.  IPv4 only - the bench
     * addresses an operator types are the {@code 192.168.x.x} kind, and a scoped
     * IPv6 in a URL needs bracket-and-zone escaping nobody wants to paste.  An
     * empty answer means enumeration failed; the caller falls back to naming
     * just the port.
     */
    public List<InetAddress> listenAddresses() {
        List<InetAddress> found = new ArrayList<>();
        for (NetworkInterface nic : upNonLoopback()) {
            for (InetAddress addr : Collections.list(nic.getInetAddresses())) {
                if (addr instanceof Inet4Address) {
                    found.add(addr);
                }
            }
        }
        return found;
    }

    /** The one filter both answers above share: every adapter that is up and
     *  not loopback.  Interfaces that will not answer their flags, and an
     *  enumeration that fails outright, are simply left out - an empty answer
     *  means "let the kernel choose" / "name only the ports". */
    private List<NetworkInterface> upNonLoopback() {
        List<NetworkInterface> found = new ArrayList<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                try {
                    if (nic.isUp() && !nic.isLoopback()) {
                        found.add(nic);
                    }
                } catch (IOException e) {
                    // This adapter would not even answer its flags - not a candidate.
                }
            }
        } catch (IOException e) {
            // No enumeration at all: answer empty and let the caller fall back.
        }
        return found;
    }
}
