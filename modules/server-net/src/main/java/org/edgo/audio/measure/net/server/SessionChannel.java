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
 * <p>ONE interface for both planes of spec 4, because a control connection and
 * a capture's data connection are the same transport: what differs is only who
 * holds one and what they write.  {@link ClientSession} holds the control
 * connection and writes messages; a {@link CaptureStream} holds its own data
 * connection and writes frames.  Neither writes the other's kind - a binary
 * frame on the control connection is a protocol error (spec 4.7).
 *
 * <p>The channel takes a whole {@link NetMessage} rather than text, so the
 * serialisation stays in one place and a test can assert on the message
 * instead of on its JSON.
 */
public interface SessionChannel {

    /** Sends one control message.  Never throws for a closed connection -
     *  liveness is the keepalive's business, not the sender's. */
    void send(NetMessage message);

    /** Sends one audio frame, as binary rather than text, on the data
     *  connection of the capture it belongs to (spec 4.7 - the control
     *  connection never carries one). */
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
     *
     * <p>Asked of a DATA connection, so what it answers is about that capture's
     * own bytes and nothing else.
     */
    boolean isSendBacklogged();

    /** Closes the transport with a human-readable reason.  On a data connection
     *  this is the orderly close of spec 4.7 - the far end must be able to tell
     *  it from a drop, so the implementation marks it as this end's. */
    void close(String reason);
}
