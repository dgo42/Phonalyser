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

import java.util.List;

import org.edgo.audio.measure.net.proto.JsonCodec;
import org.edgo.audio.measure.net.proto.NetMessage;
import org.java_websocket.WebSocket;

/**
 * A peer that answers the handshake of spec 1 and then does only what a test
 * tells it to.
 *
 * <p><b>Why a bench cannot play this part.</b>  Spec 4.1's keepalive is an
 * agreement between two sides, and the client's half of it - four unanswered
 * pings and the session is dead - can only be provoked by a peer that STOPS
 * ANSWERING while the socket stays up.  A correct server never does that, and
 * killing one instead closes the transport, which is a different ending with a
 * different reason.  So this peer answers {@code hello} (without which the
 * connection never opens at all), records everything the client sends, and is
 * deliberately deaf to the rest.
 *
 * <p>Its {@code caps} are deliberately empty: a token is a promise, and this peer
 * serves nothing - a client that hard-required one would fail here, which is
 * exactly what it must not do.
 *
 * <p>It also speaks in the other direction: {@link #push(NetMessage)} sends the
 * server-initiated {@code ping} of spec 4.0, whose id is NEGATIVE, so a test can
 * see whether the client answers it with the {@code resp} the contract requires.
 */
final class ScriptedServer extends WirePeer {

    /** What the handshake answers as the peer's own name. */
    private static final String SERVER_NAME = "Scripted bench";

    ScriptedServer(JsonCodec codec) {
        super(codec, SERVER_NAME, List.of());
    }

    /** Nothing is answered: what this peer does about a ping is the whole point
     *  of it. */
    @Override
    protected NetMessage answer(WebSocket conn, NetMessage request) {
        return null;
    }
}
