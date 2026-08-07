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

import java.util.function.IntSupplier;

import lombok.RequiredArgsConstructor;

/**
 * Where this server can actually be reached: the port its front BOUND, as
 * opposed to the one {@link ServerConfig} was asked for.
 *
 * <p>The two differ exactly when the operator asked for an ephemeral port
 * ({@code --port 0}, and the loopback tests do) - and the configured value is
 * then {@code 0}, which is not an address anything can dial.  Every discovery
 * channel quotes the port a client is expected to open a connection on: the
 * beacon payload (spec 2.1), the {@code /servers} self row (spec 2.2) and
 * {@code GET /info} (spec 3).  Announcing port 0 in all three would leave a
 * running server nobody can reach.
 *
 * <p>A supplier rather than a number because a bound port does not exist until
 * the front is listening, and this value object is assembled in the composition
 * root before that happens.  Reading it is what makes it current - there is
 * nothing here to keep in sync.
 */
@RequiredArgsConstructor
public final class BoundPorts {

    private final IntSupplier bound;

    /** The port in force right now - HTTP and WebSocket alike, since one
     *  listener serves both (spec 2.1, 2.2, 3). */
    public int port() {
        return bound.getAsInt();
    }
}
