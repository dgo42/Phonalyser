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

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.fasterxml.jackson.core.JsonProcessingException;

import org.edgo.audio.measure.net.proto.ErrorCode;
import org.edgo.audio.measure.net.proto.JsonCodec;
import org.edgo.audio.measure.net.proto.MessageType;
import org.edgo.audio.measure.net.proto.NetError;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.net.proto.NetMessage;
import org.edgo.audio.measure.net.proto.NetProto;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import lombok.Setter;

import static java.util.Collections.synchronizedList;

/**
 * The far end of the socket, as much of it as EVERY test needs: a peer on an
 * ephemeral loopback port that answers the {@code hello} of spec 1, records
 * everything else the client sent, and can speak first.
 *
 * <p><b>Why the far end is a peer and not the server.</b>  This module is the
 * pure CLIENT half of the bridge, and its whole contract with the other half is
 * the wire - JSON envelopes and binary frames.  So the thing a client test needs
 * on the other side of the socket is a peer that speaks that wire, exactly as a
 * database client's tests need a socket that speaks the database's protocol and
 * not the database's Java classes.  Two of them live here: a peer that answers
 * nothing ({@link ScriptedServer}, for the handshake and keepalive rules, which
 * only a peer that goes QUIET can produce) and a peer that plays a whole bench
 * ({@link MockBench}).  What they share is this: binding, the handshake, the
 * record of what arrived, and the waiting.
 *
 * <p><b>Waiting.</b>  Nothing here sleeps for a fixed time.  Every wait is a
 * condition on what has arrived, woken by the reader thread, and a timeout is a
 * failure with a message rather than a silent pass.
 */
abstract class WirePeer extends WebSocketServer {

    /** The library's own pong-based detector is off on both ends: liveness is
     *  decided by the 500 ms keepalive of spec 4.1, and a second detector would
     *  only disagree with it. */
    private static final int LIBRARY_KEEPALIVE_OFF = 0;
    /** Bind to an ephemeral port: a fixed one would fail the moment the developer
     *  has a Phonalyser server of their own running. */
    private static final int ANY_PORT = 0;
    private static final String LOOPBACK = "127.0.0.1";
    private static final String WS_SCHEME = "ws://";
    /** What the handshake answers as the peer's own build. */
    private static final String SERVER_APP = "1.2.0";
    /** Longest a waiter sleeps between re-checks - a backstop against a missed
     *  notify, not the mechanism. */
    private static final long WAKE_INTERVAL_MS = 50;

    protected final JsonCodec codec;

    /** The operator-visible name this peer answers {@code hello} with. */
    private final String serverName;
    /** The capability tokens of spec 4.1 this peer advertises. */
    private final List<String> caps;
    /** Everything the client sent that was not the handshake. */
    private final List<NetMessage> received = synchronizedList(new ArrayList<>());
    /** The handle each control connection's {@code hello} was answered with
     *  (spec 4.1) - what a {@code capture.attach} names its session by. */
    private final Map<WebSocket, String> clientIds = new ConcurrentHashMap<>();
    private final CountDownLatch listening = new CountDownLatch(1);
    /** Everything a waiter waits on is notified here, by the reader thread. */
    private final Object arrivals = new Object();

    /** The CONTROL connection that greeted most recently; null until one does.
     *  Set at {@code hello} and not at the upgrade, because that is what makes
     *  a socket the control connection (spec 4) - a peer that pushed to
     *  whichever socket connected last would send its events down a capture's
     *  data connection the moment one was dialled, which is exactly the mixing
     *  spec 4.7 forbids. */
    private volatile WebSocket lastClient;
    /** The version this peer claims to have CHOSEN in its handshake (spec 1).
     *  Settable so a test can be a peer that answers a version the client never
     *  offered - a correct server cannot, and a client that took it at its word
     *  would run the session in a dialect it does not speak. */
    @Setter
    private volatile int helloProto = NetProto.PROTO_VERSION;
    /** Whether this peer answers {@code hello} at all.  Settable so a test can be
     *  the host that completes the WebSocket handshake and then says nothing -
     *  which a connect attempt must not let hold its caller for the request
     *  timeout. */
    @Setter
    private volatile boolean answerHello = true;

    protected WirePeer(JsonCodec codec, String serverName, List<String> caps) {
        super(new InetSocketAddress(LOOPBACK, ANY_PORT));
        this.codec = codec;
        this.serverName = serverName;
        this.caps = List.copyOf(caps);
        setConnectionLostTimeout(LIBRARY_KEEPALIVE_OFF);
        setReuseAddr(true);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /** Starts listening and answers the address the client should open.
     *
     *  @throws IllegalStateException when the accept loop does not bind within
     *          {@code timeoutMs} - {@link #start()} only spawns a thread */
    final URI listen(long timeoutMs) {
        start();
        try {
            if (!listening.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(
                        "the peer did not bind within " + timeoutMs + " ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while binding", e);
        }
        return URI.create(WS_SCHEME + LOOPBACK + ":" + getPort());
    }

    /** Stops listening; the test's teardown calls it whatever happened. */
    final void shutDown(long timeoutMs) {
        try {
            stop((int) timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public final void onStart() {
        listening.countDown();
    }

    @Override
    public final void onOpen(WebSocket conn, ClientHandshake handshake) {
        opened(conn);
    }

    @Override
    public final void onClose(WebSocket conn, int code, String reason, boolean remote) {
        clientIds.remove(conn);
        closed(conn);
        wake();
    }

    @Override
    public final void onError(WebSocket conn, Exception ex) {
        // A test that hangs says more than one that fails on a socket closing
        // under it during teardown, so nothing here is fatal.
        wake();
    }

    // -------------------------------------------------------------------------
    // The conversation
    // -------------------------------------------------------------------------

    /**
     * Answers {@code hello}, routes a {@code capture.attach}, records the rest,
     * and hands it to the peer.
     *
     * <p>The handshake is answered here because {@link NetConnection#open} sends
     * it before anything else and refuses to go on without it (spec 4.1: hello
     * MUST be first), so no peer of any kind can decline it and still be talked
     * to.  Everything after it is {@link #answer}'s business.
     *
     * <p>Spec 4 makes the FIRST message the plane: {@code hello} means this
     * socket is the control connection, {@code capture.attach} means it is a
     * capture's data connection.  Both are recognised here because both are the
     * transport's business rather than the bench's - what a peer then DOES with
     * an attach is {@link #attached}'s.
     */
    @Override
    public final void onMessage(WebSocket conn, String message) {
        NetMessage request;
        try {
            request = codec.read(message);
        } catch (JsonProcessingException | RuntimeException e) {
            throw new IllegalStateException("the client sent undecodable text: " + message, e);
        }
        if (request.getType() == MessageType.HELLO) {
            clientIds.put(conn, UUID.randomUUID().toString());
            lastClient = conn;
            greeted(conn, request);
            if (answerHello) {
                send(conn, new NetMessage(request.getId(), codec.toNode(hello(conn))));
            }
            return;
        }
        received.add(request);
        wake();
        NetMessage answer = request.getType() == MessageType.CAPTURE_ATTACH
                ? attached(conn, request) : answer(conn, request);
        if (answer != null) {
            send(conn, answer);
        }
        wake();
    }

    /** A connection declared itself a capture's data connection (spec 4.7).  The
     *  default refuses: a peer with no captures has none to attach to. */
    protected NetMessage attached(WebSocket conn, NetMessage attach) {
        Integer id = attach.getId();
        return id == null ? null : new NetMessage(id, new NetError(ErrorCode.BAD_REQUEST,
                "this peer serves no captures to attach to"));
    }

    /** The control connection whose {@code hello} was answered with this handle,
     *  or null - the lookup a {@code capture.attach} is resolved through (spec
     *  4.7).  A stale or invented handle simply names nothing. */
    protected final WebSocket controlOf(String clientId) {
        if (clientId == null) {
            return null;
        }
        for (Map.Entry<WebSocket, String> session : clientIds.entrySet()) {
            if (clientId.equals(session.getValue())) {
                return session.getKey();
            }
        }
        return null;
    }

    /**
     * What this peer answers one request with, or null to record it and stay
     * silent - which is a legitimate answer: spec 4.1's death is four unanswered
     * pings, and only a peer that keeps the socket up while saying nothing can
     * produce it.
     */
    protected abstract NetMessage answer(WebSocket conn, NetMessage request);

    /** A client connected; the default does nothing. */
    protected void opened(WebSocket conn) {
        // Nothing: a peer with no per-session state has nothing to build.
    }

    /** The client introduced itself (spec 4.1's {@code hello}), which is where
     *  its own NAME comes from - the one a {@code DEVICE_LOCKED} error has to
     *  quote on somebody else's screen.  The default does nothing. */
    protected void greeted(WebSocket conn, NetMessage hello) {
        // Nothing: a peer that never refuses anything has no use for the name.
    }

    /** A client went away; the default does nothing. */
    protected void closed(WebSocket conn) {
        // Nothing: a peer with no per-session state has nothing to release.
    }

    /** The {@code hello} response of spec 4.1, including the session handle this
     *  connection's data connections will attach with (spec 4.7). */
    private Map<String, Object> hello(WebSocket conn) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(NetFields.PROTO, helloProto);
        data.put(NetFields.SERVER_ID, UUID.randomUUID().toString());
        data.put(NetFields.NAME, serverName);
        data.put(NetFields.APP, SERVER_APP);
        data.put(NetFields.CLIENT_ID, clientIds.get(conn));
        data.put(NetFields.CAPS, caps);
        return data;
    }

    /**
     * Sends one message to the CONTROL connection of the session that greeted
     * most recently - the server-initiated {@code ping} of spec 4.0 (whose id is
     * NEGATIVE) and the events of spec 4.3 and 4.5.
     *
     * <p>The control connection, and never merely the newest socket: spec 4.7
     * says the planes do not mix, so an event pushed down a capture's data
     * connection is a bench doing the one thing the split exists to prevent -
     * and a client that accepted it would be proving the wrong contract.
     */
    final void push(NetMessage message) {
        send(controlConnection(), message);
    }

    /** The control connection this peer pushes on, still open; null when the
     *  session it belonged to has gone. */
    final WebSocket controlConnection() {
        WebSocket control = lastClient;
        return control != null && clientIds.containsKey(control) ? control : null;
    }

    /** Sends one control message, tolerating a socket that has already gone: a
     *  broadcast races a client's own disconnect, and that race is ordinary. */
    final void send(WebSocket conn, NetMessage message) {
        if (conn == null || !conn.isOpen()) {
            return;
        }
        try {
            conn.send(codec.write(message));
        } catch (RuntimeException e) {
            // The far end closed under the write; nothing here has anybody to
            // tell, and the test's own condition will time out if it mattered.
        }
    }

    /** Sends one binary audio frame of spec 5, already encoded. */
    final void send(WebSocket conn, byte[] frame) {
        if (conn == null || !conn.isOpen()) {
            return;
        }
        try {
            conn.send(frame);
        } catch (RuntimeException e) {
            // See above: a closed socket is not this peer's problem to report.
        }
    }

    // -------------------------------------------------------------------------
    // What arrived, and waiting for it
    // -------------------------------------------------------------------------

    /** Waits for the message of {@code type} carrying {@code id} that the client
     *  sent - "did the client answer our ping, and with which id", a question
     *  only the far end can settle. */
    final NetMessage awaitReceived(MessageType type, Integer id, long timeoutMs) {
        if (!awaitTrue(() -> find(type, id) != null, timeoutMs)) {
            throw new IllegalStateException("no " + type + " with id " + id
                    + " arrived from the client within " + timeoutMs + " ms; it sent "
                    + received);
        }
        return find(type, id);
    }

    /** Waits for the {@code nth} message of {@code type} the client sends (1 =
     *  the first) and answers it - how a test asks what really went on the wire
     *  rather than what the client was asked to put there.
     *
     *  <p>Named apart from {@link #awaitReceived(MessageType, Integer, long)} on
     *  purpose: the two would otherwise overload on {@code int} against
     *  {@code Integer}, and a correlation id written as a literal would silently
     *  become an ordinal. */
    final NetMessage awaitNth(MessageType type, int nth, long timeoutMs) {
        if (!awaitTrue(() -> receivedOf(type).size() >= nth, timeoutMs)) {
            throw new IllegalStateException("the client sent only " + receivedOf(type).size()
                    + " " + type + " message(s) within " + timeoutMs + " ms, not " + nth);
        }
        return receivedOf(type).get(nth - 1);
    }

    /** Every message of {@code type} the client has sent so far, in order. */
    final List<NetMessage> receivedOf(MessageType type) {
        List<NetMessage> found = new ArrayList<>();
        synchronized (received) {
            for (NetMessage message : received) {
                if (message.getType() == type) {
                    found.add(message);
                }
            }
        }
        return found;
    }

    /** Waits for {@code done}, woken by the reader thread on every arrival. */
    final boolean awaitTrue(BooleanSupplier done, long timeoutMs) {
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

    private NetMessage find(MessageType type, Integer id) {
        synchronized (received) {
            for (NetMessage message : received) {
                if (message.getType() == type && id.equals(message.getId())) {
                    return message;
                }
            }
        }
        return null;
    }

    private void wake() {
        synchronized (arrivals) {
            arrivals.notifyAll();
        }
    }
}
