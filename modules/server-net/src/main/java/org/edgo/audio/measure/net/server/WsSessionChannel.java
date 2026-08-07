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

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;

import org.edgo.audio.measure.net.proto.BinaryFrame;
import org.edgo.audio.measure.net.proto.JsonCodec;
import org.edgo.audio.measure.net.proto.NetMessage;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * A {@link SessionChannel} on a real WebSocket connection: control messages go
 * out as text frames on the same socket the audio frames will use (spec 4, 5).
 *
 * <p>A send onto a connection that died between the liveness check and the
 * write is swallowed on purpose.  Whether the peer is still there is decided by
 * the keepalive of spec 4.1 and by the transport's own close callback - a
 * writer that threw would only push that decision onto every call site.
 */
@Log4j2
@RequiredArgsConstructor
public final class WsSessionChannel implements SessionChannel {

    /**
     * How many sends may still be on the wire before the peer counts as behind.
     *
     * <p>ONE, and the reason is {@link CaptureStream}'s use of
     * {@link #isSendBacklogged()}: it asks once per drain pass, BEFORE that pass
     * writes anything, so on a healthy link the previous pass's single frame has
     * normally completed by the time the next batch arrives.  "Anything at all
     * outstanding" would still catch a write that is merely on the wire - at
     * 384 kHz, where batches arrive every 5.3 ms, that would cost a poll on every
     * pass and throttle a link that is keeping up perfectly.
     *
     * <p>A second outstanding send cannot be produced by one healthy write, and a
     * peer that has stopped reading reaches it within two batches - 10 - 200 ms
     * and well under a megabyte still held by Jetty's frame flusher, which is
     * long before the heap notices and well inside the stream's own one-second
     * queue bound.
     */
    private static final int MAX_HEALTHY_IN_FLIGHT_SENDS = 1;

    private final Session session;
    private final JsonCodec codec;

    /** Sends handed to Jetty that it has not finished writing.  Jetty's frame
     *  flusher queues without a bound, so this count is the only thing that can
     *  say the peer is behind before the queue becomes the server's heap.
     *  Atomic: the session's request thread and its audio thread both send. */
    private final AtomicInteger inFlight = new AtomicInteger();

    @Override
    public void send(NetMessage message) {
        if (!session.isOpen()) {
            if (log.isDebugEnabled()) {
                log.debug("net session {}: dropped {} - connection already closed",
                        session.getRemoteSocketAddress(), message.getT());
            }
            return;
        }
        Written written = new Written();
        try {
            session.sendText(codec.write(message), written);
        } catch (RuntimeException e) {
            written.finished();
            if (log.isDebugEnabled()) {
                log.debug("net session {}: send of {} failed: {}",
                        session.getRemoteSocketAddress(), message.getT(), e.toString());
            }
        }
    }

    @Override
    public void send(BinaryFrame frame) {
        if (!session.isOpen()) {
            if (log.isDebugEnabled()) {
                log.debug("net session {}: dropped {} frame {} - connection already closed",
                        session.getRemoteSocketAddress(), frame.type(), frame.packetCounter());
            }
            return;
        }
        Written written = new Written();
        try {
            session.sendBinary(ByteBuffer.wrap(frame.toBytes()), written);
        } catch (RuntimeException e) {
            written.finished();
            if (log.isDebugEnabled()) {
                log.debug("net session {}: send of {} frame {} failed: {}",
                        session.getRemoteSocketAddress(), frame.type(),
                        frame.packetCounter(), e.toString());
            }
        }
    }

    /** The sends Jetty has not written out yet, against the one a healthy link
     *  may still be carrying - the earliest honest sign that the client is not
     *  keeping up, and the only one available before the heap says so. */
    @Override
    public boolean isSendBacklogged() {
        return inFlight.get() > MAX_HEALTHY_IN_FLIGHT_SENDS;
    }

    @Override
    public void close(String reason) {
        session.close(StatusCode.NORMAL, reason, Callback.NOOP);
    }

    /**
     * One send, counted from the moment it is handed over until Jetty has
     * written it - however it ended.  A failed write is a peer that is gone,
     * not a backlog, so it stops counting as one either way.
     *
     * <p>SINGLE-SHOT, and one object per send rather than one shared: the count
     * has two possible finishers - this callback, and the caller's catch for a
     * send that threw instead of ever reaching the flusher - and a send that did
     * both would decrement twice.  The count would then drift negative and
     * {@link #isSendBacklogged()} would answer "no" for the rest of the
     * session's life, silently removing the audio lane's only backpressure.  The
     * latch makes that impossible; the object it costs is a few bytes beside a
     * PCM batch this same call already copied.
     */
    private final class Written implements Callback {

        private final AtomicBoolean counted = new AtomicBoolean();

        private Written() {
            inFlight.incrementAndGet();
        }

        @Override
        public void succeed() {
            finished();
        }

        @Override
        public void fail(Throwable fault) {
            finished();
            if (log.isDebugEnabled()) {
                log.debug("net session {}: a frame could not be written: {}",
                        session.getRemoteSocketAddress(), fault.toString());
            }
        }

        /** This send is over, whoever noticed first. */
        private void finished() {
            if (counted.compareAndSet(false, true)) {
                inFlight.decrementAndGet();
            }
        }
    }
}
