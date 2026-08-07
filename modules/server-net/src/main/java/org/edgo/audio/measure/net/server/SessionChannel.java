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

import org.edgo.audio.measure.net.proto.BinaryFrame;
import org.edgo.audio.measure.net.proto.NetMessage;

/**
 * One connection's way out - the only thing {@link ClientSession} may do to the
 * transport.  Keeping it this narrow is what lets the session logic (handshake,
 * keepalive, teardown) be tested without a socket, and it keeps every
 * WebSocket type inside {@link WsFront} and {@link WsSessionChannel}.
 *
 * <p>The channel takes a whole {@link NetMessage} rather than text, so the
 * serialisation stays in one place and a test can assert on the message
 * instead of on its JSON.
 */
public interface SessionChannel {

    /** Sends one control message.  Never throws for a closed connection -
     *  liveness is the keepalive's business, not the sender's. */
    void send(NetMessage message);

    /** Sends one audio frame - spec 5 puts them on the SAME socket as the
     *  control messages, as binary rather than text, which is what keeps a PCM
     *  batch and the {@code capture.stop} that ends it in one order. */
    void send(BinaryFrame frame);

    /**
     * True while the transport still holds bytes it has not written out.
     *
     * <p>This is the ONLY backpressure the audio lane has.  {@link #send} never
     * blocks and never refuses - the WebSocket library queues what it cannot
     * write, without a bound - so a stream that kept handing it batches while a
     * client stopped reading would grow the server's heap until it was gone.
     * {@link CaptureStream} stops feeding the socket while this is true and lets
     * its own BOUNDED queue take the strain, which turns an unbounded leak into
     * the honest GAP frame of spec 5.
     */
    boolean isSendBacklogged();

    /** Closes the transport with a human-readable reason. */
    void close(String reason);
}
