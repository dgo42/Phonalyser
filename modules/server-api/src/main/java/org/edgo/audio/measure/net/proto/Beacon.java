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

/**
 * The discovery datagram of spec 2.1, one JSON object per multicast packet:
 *
 * <pre>
 * { "phonalyser": 1, "proto": 1, "serverId": "b7e0...-uuid", "name": "Bench QA403",
 *   "app": "1.2.0", "port": 8377 }
 * </pre>
 *
 * <p>ONE port: the same listener answers the HTTP endpoints of spec 3 and
 * upgrades the WebSocket of spec 4, so a client that heard this datagram opens
 * {@code ws://host:port/} and {@code http://host:port/info} on it.
 *
 * <p>{@code phonalyser} is the marker that separates our datagrams from
 * whatever else shares the group; a receiver drops anything whose value is not
 * {@link NetProto#BEACON_MAGIC} (a foreign or truncated packet decodes to 0).
 *
 * <p>There is deliberately NO address field: the receiver takes the server's IP
 * from the datagram source, because a multi-homed host cannot know which of its
 * addresses the listener can reach.
 */
public record Beacon(int phonalyser, int proto, String serverId, String name,
        String app, int port) {

    /** The beacon a server sends for itself: marker and protocol version are
     *  not the caller's business. */
    public static Beacon of(String serverId, String name, String app, int port) {
        return new Beacon(NetProto.BEACON_MAGIC, NetProto.PROTO_VERSION, serverId,
                name, app, port);
    }
}
