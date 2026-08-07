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

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import com.fasterxml.jackson.core.JsonProcessingException;

import org.edgo.audio.measure.net.proto.BinaryFrame;
import org.edgo.audio.measure.net.proto.JsonCodec;
import org.edgo.audio.measure.net.proto.MessageType;
import org.edgo.audio.measure.net.proto.NetMessage;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import static java.util.Collections.synchronizedList;

/**
 * The other end of the wire: a real WebSocket client, in the test JVM, speaking
 * the protocol of spec 4 over a real socket to a real {@link ServerMain}.
 *
 * <p>It is deliberately the TRANSPORT and nothing else - request/response
 * correlation by {@code id} (spec 4.0), the keepalive answer of spec 4.1, and
 * the two inbound streams kept apart the way spec 5 puts them on one socket
 * (control messages as text, audio frames as binary).  What to send and what it
 * means stays in the test: a helper that also knew the protocol would be a
 * second implementation of it, and two implementations agreeing with each other
 * prove nothing.
 *
 * <p><b>Waiting.</b>  Nothing here sleeps for a fixed time.  Every wait is a
 * condition on what has arrived, woken by the reader thread, so the run takes as
 * long as the loopback takes and no longer - and a timeout is a failure with a
 * message, never a silent pass.
 *
 * <p><b>The keepalive.</b>  Answers are on by default, because a client that did
 * not answer would be declared dead 2 s into every test.
 * {@link #stopAnsweringPings()} is how the death of spec 4.1 is provoked on
 * purpose; the pings themselves are kept either way, so a test can assert that
 * the server really did send them and that their ids are negative (spec 4.0).
 */
final class LoopbackClient extends WebSocketClient {

    /** The library's own pong-based detector is off on both ends: liveness is
     *  decided by the 500 ms keepalive of spec 4.1, and a second detector would
     *  only disagree with it. */
    private static final int LIBRARY_KEEPALIVE_OFF = 0;
    /** How long a request may go unanswered before the test fails.  Generous:
     *  it is a loopback socket, so anything approaching this is a hang, not
     *  slowness. */
    private static final long REQUEST_TIMEOUT_MS = 10_000;
    /** Longest a waiter sleeps between re-checks - a backstop against a missed
     *  notify, not the mechanism (see the class comment). */
    private static final long WAKE_INTERVAL_MS = 50;

    private final JsonCodec codec;
    /** Requests waiting for their {@code resp}, keyed by the id of spec 4.0. */
    private final Map<Integer, CompletableFuture<NetMessage>> pending =
            new ConcurrentHashMap<>();
    private final List<BinaryFrame> frames = synchronizedList(new ArrayList<>());
    private final List<NetMessage> events = synchronizedList(new ArrayList<>());
    private final List<NetMessage> serverPings = synchronizedList(new ArrayList<>());
    /** Spec 4.0: "a per-connection monotonically increasing integer". */
    private final AtomicInteger lastRequestId = new AtomicInteger();
    private final AtomicBoolean answerPings = new AtomicBoolean(true);
    private final CountDownLatch closed = new CountDownLatch(1);
    /** Everything a waiter waits on is notified here, by the reader thread. */
    private final Object arrivals = new Object();
    /** The first transport or decode failure, quoted by whatever times out
     *  because of it - an unexplained timeout is the worst test failure there
     *  is. */
    private volatile String fault;

    LoopbackClient(URI server, JsonCodec codec) {
        super(server);
        this.codec = codec;
        setConnectionLostTimeout(LIBRARY_KEEPALIVE_OFF);
    }

    // -------------------------------------------------------------------------
    // Sending
    // -------------------------------------------------------------------------

    /** An empty request of {@code type} carrying the next id - the fields are
     *  the test's to add. */
    NetMessage newRequest(MessageType type) {
        return new NetMessage(type, lastRequestId.incrementAndGet());
    }

    /** Sends {@code message} and answers with the server's {@code resp} to it. */
    NetMessage request(NetMessage message) {
        Integer id = message.getId();
        CompletableFuture<NetMessage> answer = new CompletableFuture<>();
        pending.put(id, answer);
        try {
            send(codec.write(message));
            return answer.get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for " + message.getT(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("no answer to " + message.getT() + " within "
                    + REQUEST_TIMEOUT_MS + " ms" + because(), e);
        } finally {
            pending.remove(id);
        }
    }

    /** Stops answering the server's pings - four of them and the connection is
     *  dead (spec 4.1), which is the point. */
    void stopAnsweringPings() {
        answerPings.set(false);
    }

    /**
     * The client PROCESS dying: the socket goes, with no {@code bye} and no
     * WebSocket close frame behind it.
     *
     * <p>That is what a halted or killed client leaves the server - the desktop
     * quits through {@code Runtime.halt}, and a machine that sleeps or loses its
     * link says even less.  Nothing of the protocol announces it, so the server
     * has only the transport ending to go on, and the devices in that client's
     * name have to come back from that alone.
     */
    void die() {
        try {
            getSocket().close();
        } catch (IOException e) {
            throw new IllegalStateException("the client's socket could not be dropped", e);
        }
    }

    // -------------------------------------------------------------------------
    // Receiving
    // -------------------------------------------------------------------------

    @Override
    public void onOpen(ServerHandshake handshake) {
        // Nothing to do: the protocol's own handshake is `hello`, which the test
        // sends when it is ready to.
    }

    @Override
    public void onMessage(String text) {
        NetMessage message;
        try {
            message = codec.read(text);
        } catch (JsonProcessingException | RuntimeException e) {
            fault = "undecodable message from the server: " + text;
            wake();
            return;
        }
        MessageType type = message.getType();
        Integer id = message.getId();
        if (type == MessageType.RESP) {
            CompletableFuture<NetMessage> answer = id == null ? null : pending.remove(id);
            if (answer != null) {
                answer.complete(message);
            }
        } else if (type == MessageType.PING) {
            // The answer goes out BEFORE the ping is recorded, so a test that
            // waits for the Nth ping knows the Nth pong is already on the wire
            // ahead of whatever it sends next.
            if (answerPings.get() && id != null) {
                send(codec.write(new NetMessage(id)));
            }
            serverPings.add(message);
        } else {
            events.add(message);
        }
        wake();
    }

    @Override
    public void onMessage(ByteBuffer binary) {
        byte[] raw = new byte[binary.remaining()];
        binary.get(raw);
        frames.add(BinaryFrame.parse(raw));
        wake();
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        closed.countDown();
        wake();
    }

    @Override
    public void onError(Exception e) {
        fault = "transport error: " + e;
        wake();
    }

    // -------------------------------------------------------------------------
    // What arrived, and waiting for it
    // -------------------------------------------------------------------------

    /** Waits for {@code count} binary frames (spec 5) and answers them in
     *  arrival order. */
    List<BinaryFrame> awaitFrames(int count, long timeoutMs) {
        if (!await(() -> frames.size() >= count, timeoutMs)) {
            throw new IllegalStateException("only " + frames.size() + " of " + count
                    + " binary frame(s) arrived within " + timeoutMs + " ms" + because());
        }
        return List.copyOf(frames);
    }

    /** Waits until {@code count} events of {@code type} have arrived and answers
     *  the newest one - the shape every push in spec 4.3 and 4.5 is read with,
     *  since a session sees several of each. */
    NetMessage awaitEvent(MessageType type, int count, long timeoutMs) {
        if (!await(() -> events(type).size() >= count, timeoutMs)) {
            throw new IllegalStateException("only " + events(type).size() + " of " + count
                    + " " + type + " event(s) arrived within " + timeoutMs + " ms" + because());
        }
        List<NetMessage> found = events(type);
        return found.get(found.size() - 1);
    }

    /** Waits until the server has sent {@code count} pings - which also means
     *  this client has already answered them (see {@link #onMessage(String)}). */
    void awaitServerPings(int count, long timeoutMs) {
        if (!await(() -> serverPings.size() >= count, timeoutMs)) {
            throw new IllegalStateException("only " + serverPings.size() + " of " + count
                    + " server ping(s) arrived within " + timeoutMs + " ms" + because());
        }
    }

    /** Every server-initiated event of one type so far, in order. */
    List<NetMessage> events(MessageType type) {
        List<NetMessage> found = new ArrayList<>();
        synchronized (events) {
            for (NetMessage message : events) {
                if (message.getType() == type) {
                    found.add(message);
                }
            }
        }
        return found;
    }

    /** The server's pings so far - spec 4.1's 500 ms heartbeat, with the
     *  negative ids of spec 4.0. */
    List<NetMessage> serverPings() {
        return List.copyOf(serverPings);
    }

    /** True once the server has hung up on this client. */
    boolean isHungUp() {
        return closed.getCount() == 0;
    }

    /** Waits for the server to hang up - the visible end of the teardown of
     *  spec 4.1. */
    boolean awaitHangUp(long timeoutMs) {
        try {
            return closed.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Waits for {@code done}, woken by the reader thread on every arrival.
     *
     * <p>The condition is evaluated while holding {@link #arrivals} and reaches
     * for the message lists; the reader takes those lists first and only wakes
     * afterwards, so the two never take the pair in opposite orders.
     */
    private boolean await(BooleanSupplier done, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (arrivals) {
            while (!done.getAsBoolean()) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return false;
                }
                try {
                    arrivals.wait(Math.min(remaining, WAKE_INTERVAL_MS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    private void wake() {
        synchronized (arrivals) {
            arrivals.notifyAll();
        }
    }

    /** What went wrong before the wait ran out, if anything did. */
    private String because() {
        String known = fault;
        return known == null ? "" : " - " + known;
    }
}
