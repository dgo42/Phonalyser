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

/**
 * One remembered Phonalyser server: enough to reach it again without waiting for
 * a beacon, and enough to show it in the server list before it has answered.
 *
 * <p><b>Why it is remembered at all.</b>  Discovery (spec 2.1) only covers the
 * local segment, so a bench on a routed subnet is reached by an address the
 * operator typed in - and typing it again after every restart is what this
 * record exists to avoid.  A discovered server is remembered too, so its name
 * can be shown greyed-out rather than vanishing while it is switched off.
 *
 * <p>The id is the server's installation UUID (spec 2.1), which is what a
 * remembered server is keyed by: an address changes with the DHCP lease, the id
 * does not.  {@code manual} separates the two kinds - a manual entry stays in the
 * list until the operator removes it, a discovered one is only as good as the
 * last beacon.
 *
 * <p>ONE port: a server serves its HTTP endpoints and its WebSocket upgrade on
 * the same one, so this is both the address {@code /info} is polled at and the
 * one the control channel is dialled on.
 *
 * @param serverId the server's installation UUID
 * @param name     its operator-visible name, as last heard
 * @param host     the address it was last reached at
 * @param port     its bound port, as advertised
 * @param manual   {@code true} when the operator typed this server in rather
 *                 than discovery finding it
 */
public record NetServerEntry(String serverId, String name, String host, int port,
        boolean manual) {
}
