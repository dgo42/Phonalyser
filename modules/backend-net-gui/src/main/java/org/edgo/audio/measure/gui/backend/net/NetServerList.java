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

package org.edgo.audio.measure.gui.backend.net;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.edgo.audio.measure.net.client.NetPreferences;
import org.edgo.audio.measure.net.client.NetServerEntry;
import org.edgo.audio.measure.net.proto.Beacon;
import org.edgo.audio.measure.net.proto.NetProto;

/**
 * What the server list SHOWS: every Phonalyser server this installation knows -
 * heard on the discovery group (spec 2.1) or typed in by the operator - and
 * which of them answered recently enough to still count as live.
 *
 * <p><b>The remembered servers are not kept here.</b>  They live in
 * {@link NetPreferences}' edit copy, which is the whole point of the staging
 * rule: a server added or removed in this list reaches the file only when the
 * Preferences dialog is closed with OK, exactly like the QA40x's ranges.  This
 * type adds the one thing that is NOT persisted - when each server was last
 * heard from - and answers the question the widget asks: what to draw, and which
 * rows are grey.
 *
 * <p><b>Time comes in as an argument.</b>  Expiry is a comparison against a
 * moment, and a moment is exactly what a widget has when it repaints and a test
 * has when it decides what to prove; reading a clock inside would make the
 * offline rule untestable without sleeping through it.
 *
 * <p>No SWT, no sockets: the dialog feeds it beacons and asks it for rows.
 */
public final class NetServerList {

    /** One row of the list: the server as last known, and whether its beacons
     *  are still arriving.  A manual entry on a routed subnet is normally
     *  offline - discovery does not cross a router - which is what makes it
     *  worth typing in rather than a fault to report. */
    public record Row(NetServerEntry server, boolean online) {
    }

    private final NetPreferences preferences;
    /** Server id -&gt; the moment its last beacon arrived.  Absent = never heard
     *  this session, which is what a remembered server starts as. */
    private final Map<String, Long> lastHeard = new HashMap<>();

    public NetServerList(NetPreferences preferences) {
        this.preferences = preferences;
    }

    /**
     * One beacon (spec 2.1) and the address it came from: the server is
     * remembered - or refreshed if it was already known - and counts as live
     * from {@code nowMs}.
     *
     * <p>A server that was typed in by the operator and is now also being heard
     * stays MANUAL: discovery finding it does not make it disposable, and the
     * operator who typed it in is the only one who may take it out again.
     */
    public void heard(Beacon beacon, String host, long nowMs) {
        String serverId = beacon.serverId();
        if (serverId == null || serverId.isEmpty()) {
            return;                 // nothing to key it by - spec 2.1 requires one
        }
        NetServerEntry known = preferences.getEditServers().get(serverId);
        preferences.putEditServer(new NetServerEntry(serverId, beacon.name(), host,
                beacon.port(), known != null && known.manual()));
        lastHeard.put(serverId, nowMs);
    }

    /** Remembers {@code server} as the operator typed it in.  Staged, like every
     *  other change here - a server that was only PROBED is an edit; the one a
     *  session is actually running on is remembered live instead
     *  ({@code NetPreferences.putServer}). */
    public void remember(NetServerEntry server) {
        preferences.putEditServer(server);
    }

    /**
     * Whether {@code selected} is a row a Connect would do anything with: there
     * has to BE a selection, and it must not already be the server this
     * installation is talking to - a row that is already connected leaves
     * Connect disabled.
     *
     * <p>It lives here rather than in the widget because it is the same rule in
     * two places - the Connect button's enablement and the double-click that
     * connects a row - and a rule spelled out twice is a rule that will
     * eventually disagree with itself.
     *
     * @param selected  the row the operator has selected, or null for none
     * @param connected the server the session is on, or null when there is none
     */
    public boolean connectable(NetServerEntry selected, NetServerEntry connected) {
        return selected != null
                && (connected == null || !connected.serverId().equals(selected.serverId()));
    }

    /** A unicast liveness answer ({@code GET /info}) for {@code serverId}: the
     *  server counts as live from {@code nowMs}, exactly as a beacon would make
     *  it - the manual entry's substitute for the multicast that does not cross
     *  its router. */
    public void alive(String serverId, long nowMs) {
        lastHeard.put(serverId, nowMs);
    }

    /** Forgets {@code serverId} - staged, so the Preferences dialog's Cancel
     *  brings it back. */
    public void forget(String serverId) {
        preferences.removeEditServer(serverId);
        lastHeard.remove(serverId);
    }

    /**
     * Every known server as of {@code nowMs}, in the order they were remembered.
     * A server is live while its last beacon is younger than
     * {@link NetProto#BEACON_EXPIRY_MS} - four missed announcements, the same
     * threshold the peer table and the keepalive use.
     */
    public List<Row> rows(long nowMs) {
        List<Row> rows = new ArrayList<>();
        for (NetServerEntry server : preferences.getEditServers().values()) {
            Long heard = lastHeard.get(server.serverId());
            rows.add(new Row(server,
                    heard != null && nowMs - heard < NetProto.BEACON_EXPIRY_MS));
        }
        return rows;
    }
}
