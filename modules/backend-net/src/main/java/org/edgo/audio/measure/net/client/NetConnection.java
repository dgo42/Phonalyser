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

import java.net.Proxy;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import org.edgo.audio.measure.enums.DeviceFailureReason;
import org.edgo.audio.measure.net.proto.BinaryFrame;
import org.edgo.audio.measure.net.proto.ErrorCode;
import org.edgo.audio.measure.net.proto.JsonCodec;
import org.edgo.audio.measure.net.proto.MessageType;
import org.edgo.audio.measure.net.proto.NetError;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.net.proto.NetMessage;
import org.edgo.audio.measure.net.proto.NetProto;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ServerHandshake;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * One session with a headless Phonalyser server, for its whole life: the
 * {@code hello} of spec 1, the request/response correlation of spec 4.0, the
 * keepalive of spec 4.1 and the binary-frame demux of spec 5.
 *
 * <p><b>What it owns.</b>  Every SOCKET of the session, the id space, the
 * requests still waiting for their {@code resp}, the keepalive counters and the
 * routing table from {@code streamId} to the capture that asked for it.  What
 * the messages MEAN is not here: the device catalogue belongs to
 * {@link NetDeviceManager} and a stream's own vocabulary to
 * {@link NetPcmCapture}, so this type never has to grow a case for a message a
 * later phase adds.
 *
 * <p><b>Two planes, one url (spec 4).</b>  The control connection is dialled by
 * {@link #open(long)} and declares itself with {@code hello}; every open capture
 * adds a data connection of its own, dialled by {@link #attachData(int)} and
 * declared with {@code capture.attach}.  The frames arrive on those and are
 * demuxed by {@code streamId} exactly as they were when one socket carried
 * everything - which is the point of leaving the header alone.  What changed is
 * that a ping answer no longer queues behind a megabyte of PCM, so the keepalive
 * of spec 4.1 measures liveness again.
 *
 * <p><b>Keepalive.</b>  The client pings every 500 ms and counts the unanswered
 * ones; four in a row (2 s of silence) and the connection is dead - spec 4.1's
 * rule, run against the SERVER by the same arithmetic the server runs against
 * the client.  It also answers the server's pings, which arrive as requests with
 * NEGATIVE ids (spec 4.0), with the ordinary {@code resp} envelope.  Time
 * arrives through an injected {@link Ticker}, never from a clock read or a
 * sleep, so the whole death sequence is five method calls in a test.
 *
 * <p><b>Threading.</b>  Everything that arrives is decoded and delivered on the
 * transport's reader thread - the same contract a local backend's capture
 * thread has, which is why a remote PCM batch can reach the pipeline through
 * {@code dispatch} exactly as a local one does.  A listener must therefore
 * consume without blocking, and MUST NOT call {@link #request(NetMessage)}: the
 * answer it would wait for can only be delivered by the very thread it is
 * blocking.  {@link #send(NetMessage)} is the way out of a listener - it queues
 * and returns.
 */
@Log4j2
public final class NetConnection {

    /** Where the binary frames of one capture stream are delivered - the demux
     *  target keyed on the {@code streamId} of spec 5. */
    @FunctionalInterface
    public interface FrameListener {

        /** One frame off the socket, on the transport's reader thread. */
        void onFrame(BinaryFrame frame);
    }

    /** The library runs its own pong-based connection-lost detector.  Liveness
     *  is decided by the 500 ms keepalive of spec 4.1 instead, so the second,
     *  slower detector is switched off rather than left to disagree. */
    private static final int LIBRARY_KEEPALIVE_OFF = 0;
    /** No close code to report: this end ended the session itself (the operator's
     *  Disconnect, the keepalive verdict), so no socket reported one. */
    private static final int NO_CLOSE_CODE = -1;
    /** How long a request may go unanswered before the caller is told the server
     *  is not answering.  Generous on purpose: an exclusive-mode device open on
     *  the far end really can take seconds, and the keepalive - not this - is
     *  what notices a dead peer. */
    private static final long REQUEST_TIMEOUT_MS = 10_000;
    /** How long a UI-driven ask may hold the operator.  An operator reads ~1 s
     *  as the app working and 10 s as the app hung, so the dialog-path asks
     *  (backend/device/card lists, select, preview) bound HERE, while device
     *  operations (an exclusive-mode open really can take seconds) keep the
     *  generous {@link #REQUEST_TIMEOUT_MS}. */
    public static final long UI_REQUEST_TIMEOUT_MS = 1_000;

    private final URI server;
    /** The user-visible client name of spec 4.1 - what a {@code DEVICE_LOCKED}
     *  error names as the holder on somebody else's screen. */
    private final String clientName;
    /** The application-and-version string of spec 4.1's {@code client} field.
     *  Injected: the version belongs to the application, not to a backend. */
    private final String clientApp;
    @Getter
    private final JsonCodec codec;
    /** The 500 ms keepalive clock of spec 4.1. */
    private final Ticker ticker;
    /** The HTTP proxy to tunnel this session through, or null for a direct
     *  connection.  Decided by the caller - which of the operator's two copies
     *  of the setting applies depends on whether this dial is a button they
     *  just pressed or a retry running unattended, and that is not something a
     *  connection can know. */
    @Getter
    private final Proxy proxy;
    private final Transport transport;

    /** Requests waiting for their {@code resp}, keyed by the id of spec 4.0. */
    private final Map<Integer, CompletableFuture<NetMessage>> pending =
            new ConcurrentHashMap<>();
    private final Map<Integer, FrameListener> streams = new ConcurrentHashMap<>();
    /** The data connections of spec 4.7, one per open capture - held so the end
     *  of the session can close them all, and so none outlives the socket that
     *  carries the requests that opened them. */
    private final List<DataConnection> dataConnections = new CopyOnWriteArrayList<>();
    private final List<Consumer<NetMessage>> eventListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<NetCloseReason>> closeListeners = new CopyOnWriteArrayList<>();
    /** Spec 4.0: "a per-connection monotonically increasing integer". */
    private final AtomicInteger lastRequestId = new AtomicInteger();
    /** Sequence number of the NEWEST client ping the server answered.  Spec 4.1
     *  counts CONSECUTIVE unanswered pings, which is {@link #pingCounter} minus
     *  this - so an answer that arrived after four later pings went unanswered
     *  cannot resurrect a connection that is already 2 s behind. */
    private final AtomicInteger lastAnsweredPing = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** How many pings have been sent.  Touched by the keepalive task alone,
     *  which the ticker never runs twice at once. */
    private int pingCounter;
    /** The negotiated session version (spec 1); 0 until {@code hello} succeeded. */
    @Getter
    private volatile int proto;
    /** The server's installation UUID (spec 2.1) - what a remembered-server list
     *  keys on, never the address. */
    @Getter
    private volatile String serverId;
    /** This session's own handle, answered by {@code hello} (spec 4.1), and the
     *  ticket every data connection attaches with (spec 4.7).
     *
     *  <p>A SECRET: it is never logged and never shown, because anyone holding
     *  it could attach to this session's captures.  Deliberately NOT exposed -
     *  the one thing that spends it is {@link #attachData(int)}, which is in
     *  here with it. */
    private volatile String clientId;
    /** The operator-configured server name, for the window title and the combo. */
    @Getter
    private volatile String serverName;
    /** The capability tokens of spec 4.1.  A token is a promise, its absence is
     *  not a refusal: this build must never HARD-require one, because a server
     *  that serves an extension without advertising it yet is a server we still
     *  talk to. */
    @Getter
    private volatile List<String> caps = List.of();

    /** A session dialled DIRECTLY - the LAN case, and every test's. */
    public NetConnection(URI server, String clientName, String clientApp, JsonCodec codec,
            Ticker ticker) {
        this(server, clientName, clientApp, codec, ticker, null);
    }

    /** The same session dialled through {@code proxy}, or directly when it is
     *  null: a bench behind a routed subnet is reached through the HTTP proxy
     *  the operator configured, and {@code ws://} tunnels through one by the
     *  same CONNECT the browser uses. */
    public NetConnection(URI server, String clientName, String clientApp, JsonCodec codec,
            Ticker ticker, Proxy proxy) {
        this.server = server;
        this.clientName = clientName;
        this.clientApp = clientApp;
        this.codec = codec;
        this.ticker = ticker;
        this.proxy = proxy;
        this.transport = new Transport(server);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Connects and completes the handshake of spec 1: the client offers the
     * range {@code [protoMin..proto]}, the server answers with the CHOSEN
     * version that governs the session, and the keepalive starts.
     *
     * <p>Nothing is sent before the answer arrives - {@code hello} MUST be first
     * (spec 4.1) - and a refusal closes the connection here rather than leaving
     * a socket open that no request may use.
     *
     * <p><b>{@code timeoutMs} bounds the WHOLE handshake</b>, socket and
     * {@code hello} together, not each of them: this runs on the UI thread from a
     * button, and a host that completes the WebSocket handshake and then says
     * nothing must not hold the dialog for the request timeout - which is
     * generous by design (see {@link #REQUEST_TIMEOUT_MS}) and ten times longer
     * than a connect attempt is allowed to feel.
     *
     * @throws NoAnswerException when nothing answered within {@code timeoutMs} -
     *         typed, so a caller can tell the dead address apart from a refusal
     *         and word the two differently for the operator
     * @throws IllegalStateException when the server answered but refuses the
     *         session; the message carries the error code of spec 4.2, because
     *         "cannot connect" and "your protocol is too old" are different
     *         problems for the operator
     */
    public void open(long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        // Before the dial and nowhere else: the library reads it when it opens
        // the socket, and a proxy set afterwards would apply to a connection
        // that is already direct.
        if (proxy != null) {
            transport.setProxy(proxy);
        }
        try {
            if (!transport.connectBlocking(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new NoAnswerException(
                        "no Phonalyser server answered at " + server);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while connecting to " + server, e);
        }
        NetMessage hello = request(newRequest(MessageType.HELLO)
                .put(NetFields.PROTO, NetProto.PROTO_VERSION)
                .put(NetFields.PROTO_MIN, NetProto.PROTO_MIN_VERSION)
                .put(NetFields.CLIENT, clientApp)
                .put(NetFields.NAME, clientName), remainingMs(deadline));
        if (!hello.isOk()) {
            NetError error = hello.getError();
            connClose(NetCloseReason.HANDSHAKE_REFUSED);
            throw new IllegalStateException("the server at " + server
                    + " refused the session: " + (error == null
                            ? "no reason given" : error.code() + " - " + error.message()));
        }
        JsonNode data = hello.getData();
        // Spec 1: the chosen version governs the WHOLE session and binds both
        // sides, so one outside the range this client offered is not a session
        // to run at a version it cannot speak - it is a mismatch.  An absent
        // field reads as 0 and is caught by the same test, which is the point of
        // testing the value rather than its presence.
        int chosen = data.path(NetFields.PROTO).asInt();
        if (chosen < NetProto.PROTO_MIN_VERSION || chosen > NetProto.PROTO_VERSION) {
            connClose(NetCloseReason.HANDSHAKE_REFUSED);
            throw new IllegalStateException(ErrorCode.PROTO_MISMATCH + ": the server at "
                    + server + " chose proto " + chosen + ", and this client speaks "
                    + NetProto.PROTO_MIN_VERSION + ".." + NetProto.PROTO_VERSION);
        }
        proto = chosen;
        serverId = text(data, NetFields.SERVER_ID);
        serverName = text(data, NetFields.NAME);
        clientId = text(data, NetFields.CLIENT_ID);
        caps = capsOf(data);
        ticker.start(NetProto.PING_INTERVAL_MS, this::keepaliveTick);
        if (log.isInfoEnabled()) {
            log.info("net client: connected to '{}' ({}) at {}, proto {}, caps {}",
                    serverName, serverId, server, proto, caps);
        }
    }

    /** True while the session is usable: the handshake succeeded, nothing has
     *  closed it, and the socket is still there. */
    public boolean isOpen() {
        return !closed.get() && transport.isOpen();
    }

    /**
     * Ends the session and tells everyone waiting.  Idempotent and callable from
     * ANY thread - the keepalive declaring the server dead, the transport's own
     * close callback and the operator pressing Disconnect all land here, in any
     * order, and none of them waits for the others.
     *
     * <p>{@link NetCloseReason#BYE} sends the {@code bye} of spec 4.1 first, so
     * the server releases this connection's locks at once instead of waiting for
     * its own keepalive to notice.  It is not waited for: the answer would
     * arrive on a reader thread this method may itself be running on.
     *
     * <p>Every request still waiting fails HERE rather than timing out ten
     * seconds later, because a caller blocked on a device open must learn that
     * the bench is gone while its measurement can still be stopped.
     */
    public void connClose(NetCloseReason reason) {
        connClose(reason, NO_CLOSE_CODE);
    }

    /**
     * The same close, carrying the WebSocket close code the socket reported.
     *
     * <p>The reason says WHAT this end concluded; the code says what the wire
     * saw, and the two answer different questions after a bench disappears
     * mid-measurement.  {@code 1000} is an orderly close by the server,
     * {@code 1006} an abnormal drop with no close frame at all - a pulled cable,
     * a killed process, a machine that went to sleep - and the protocol and
     * policy codes name a refusal.  Without it every death read alike in the
     * log.
     */
    public void connClose(NetCloseReason reason, int closeCode) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ticker.stop();
        if (reason == NetCloseReason.BYE && transport.isOpen()) {
            sendRaw(new NetMessage(MessageType.BYE, lastRequestId.incrementAndGet()));
        }
        transport.close();
        // Every socket of the session goes with it (spec 4.1), marked as this
        // end's so a close that is merely the tail of THIS teardown cannot
        // report the session as dying a second time.
        for (DataConnection data : dataConnections) {
            data.close();
        }
        dataConnections.clear();
        IllegalStateException ended = new IllegalStateException(
                "the connection to " + server + " ended: " + reason.getDetail());
        for (Integer id : List.copyOf(pending.keySet())) {
            CompletableFuture<NetMessage> answer = pending.remove(id);
            if (answer != null) {
                answer.completeExceptionally(ended);
            }
        }
        streams.clear();
        for (Consumer<NetCloseReason> listener : closeListeners) {
            notifyClosed(listener, reason);
        }
        if (log.isInfoEnabled()) {
            if (closeCode == NO_CLOSE_CODE) {
                log.info("net client: session with {} ended - {}", server, reason.getDetail());
            } else {
                log.info("net client: session with {} ended - {} (close code {})",
                        server, reason.getDetail(), closeCode);
            }
        }
    }

    /**
     * Spec 4.7: dials this capture's DATA connection and attaches it.
     *
     * <p>Called between {@code capture.open} - which is where the
     * {@code captureId} comes from - and {@code capture.start}, which the server
     * refuses {@code NOT_ATTACHED} until this has returned.  The socket goes to
     * the SAME url as the control connection and through the same proxy: spec 4
     * gives the two planes one address on purpose, so there is no second setting
     * that could drift out of step with the one the operator configured.
     *
     * <p>A refusal or a dial that fails closes whatever came up and throws, so
     * the caller can report it as what it is - a capture that could not be
     * opened, not a socket that could not be dialled.
     *
     * @throws IllegalStateException when the socket does not come up, the server
     *         refuses the attach, or this session never got its handle
     */
    public DataConnection attachData(int captureId) {
        String handle = clientId;
        if (handle == null) {
            throw new IllegalStateException("this session has no clientId - the server's "
                    + "hello did not carry one, so no capture can be attached (spec 4.1)");
        }
        DataConnection data = new DataConnection(server, captureId);
        if (proxy != null) {
            // Before THIS socket is dialled: the library reads the proxy when it
            // opens a connection, so it has to be set on the one that has not
            // been opened yet.  The control socket's own proxy was set before
            // its dial, in open(), and setting it again here would reach a
            // connection that is already up.
            data.setProxy(proxy);
        }
        try {
            if (!data.connectBlocking(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("the data connection for capture " + captureId
                        + " could not be opened to " + server);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            data.close();
            throw new IllegalStateException("interrupted while opening the data connection "
                    + "for capture " + captureId, e);
        }
        NetMessage answer;
        try {
            answer = requestOn(data, newRequest(MessageType.CAPTURE_ATTACH)
                    .put(NetFields.CLIENT_ID, handle)
                    .put(NetFields.CAPTURE_ID, captureId), REQUEST_TIMEOUT_MS);
        } catch (RuntimeException e) {
            data.close();
            throw e;
        }
        if (!answer.isOk()) {
            // The server answers the refusal and then closes the socket itself
            // (spec 4.7); closing it here too is idempotent and is what marks
            // the close as expected, so it cannot read as a drop.
            data.close();
            throw refusal("cannot attach the data connection of capture " + captureId, answer);
        }
        // The attach is answered, so this socket has said everything it will
        // ever say (spec 4.7).  From here any text on it is a protocol error.
        data.attached();
        dataConnections.add(data);
        if (log.isDebugEnabled()) {
            log.debug("net client: capture {} attached its data connection to {}",
                    captureId, server);
        }
        return data;
    }

    // -------------------------------------------------------------------------
    // Sending
    // -------------------------------------------------------------------------

    /** An empty request of {@code type} carrying the next id (spec 4.0); the
     *  fields are the caller's to add. */
    public NetMessage newRequest(MessageType type) {
        return new NetMessage(type, lastRequestId.incrementAndGet());
    }

    /**
     * Sends {@code message} and WAITS for the server's answer - a {@code resp},
     * successful or not: a refusal is an answer, not a failure, and the caller
     * reads its code from {@link NetMessage#getError()}.
     *
     * @throws IllegalStateException when the connection ended while waiting, or
     *         the server did not answer at all
     */
    public NetMessage request(NetMessage message) {
        return request(message, REQUEST_TIMEOUT_MS);
    }

    /** The UI's ask: {@link #request(NetMessage)} bounded at
     *  {@link #UI_REQUEST_TIMEOUT_MS}, so a dialog never waits out the
     *  device-operation budget on a server that is not answering. */
    public NetMessage uiRequest(NetMessage message) {
        return request(message, UI_REQUEST_TIMEOUT_MS);
    }

    /** The same wait with an explicit budget - the handshake of
     *  {@link #open(long)} may not outlive the connect timeout its caller asked
     *  for.  Private: every other request is a device operation, and those get
     *  the generous {@link #REQUEST_TIMEOUT_MS} on purpose. */
    private NetMessage request(NetMessage message, long timeoutMs) {
        return requestOn(transport, message, timeoutMs);
    }

    /** The same wait, on a named socket: a {@code capture.attach} is answered on
     *  the DATA connection it arrived on (spec 4.0, 4.7), not on the control
     *  one, so the write has to be aimed even though the correlation is the
     *  session's own {@link #pending} table either way. */
    private NetMessage requestOn(WebSocketClient socket, NetMessage message, long timeoutMs) {
        try {
            return sendOn(socket, message).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            abandon(message);
            throw new IllegalStateException(
                    "interrupted waiting for the answer to " + message.getT(), e);
        } catch (ExecutionException e) {
            // Already completed - whoever failed it removed it on the way.
            throw new IllegalStateException(message.getT() + " failed: "
                    + e.getCause().getMessage(), e.getCause());
        } catch (TimeoutException e) {
            abandon(message);
            throw new IllegalStateException("the server did not answer " + message.getT()
                    + " within " + timeoutMs + " ms", e);
        }
    }

    /** What is left of a deadline, in milliseconds and never below one: a budget
     *  already spent still has to be WAITED on, or a socket that connected right
     *  at the limit would report "no answer" before the answer could arrive. */
    private long remainingMs(long deadlineNanos) {
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
    }

    /**
     * Sends {@code message} and answers with the future its {@code resp} will
     * complete.  It never blocks, which is what makes it the only way to send
     * from a listener (see the class comment on threading).
     */
    public CompletableFuture<NetMessage> send(NetMessage message) {
        return sendOn(transport, message);
    }

    /** The same, on a named socket.  The {@link #pending} table is the SESSION's
     *  and not a socket's, because the ids are: spec 4.0 makes them a
     *  per-connection monotonic integer and this client draws every one of them
     *  from one counter, so an answer correlates wherever it comes back. */
    private CompletableFuture<NetMessage> sendOn(WebSocketClient socket, NetMessage message) {
        Integer id = message.getId();
        if (id == null) {
            throw new IllegalArgumentException(
                    "spec 4.0: a request needs an id to be answered - " + message);
        }
        CompletableFuture<NetMessage> answer = new CompletableFuture<>();
        pending.put(id, answer);
        // AFTER the put, never before: a close that runs in between must find
        // this entry and fail it, or the caller waits for an answer that the
        // ended session can no longer bring.
        if (closed.get()) {
            failPending(id, "the connection to " + server + " is closed");
            return answer;
        }
        try {
            socket.send(codec.write(message));
        } catch (RuntimeException e) {
            failPending(id, "cannot send " + message.getT() + ": " + e);
        }
        return answer;
    }

    /**
     * A refused {@code resp} turned into the exception the SPI's caller catches,
     * with the code and the holder of spec 4.2 in the message - "in use by Developer's
     * laptop" is actionable, "cannot open device" is not.
     *
     * <p>It lives here rather than in each caller because every one of them
     * refuses the same way: a capture that cannot be opened, a generator whose
     * DAC somebody else is driving, a device that cannot be taken.  Two spellings
     * of the same refusal would read as two different faults on screen.
     */
    public Refusal refusal(String what, NetMessage answer) {
        NetError error = answer.getError();
        if (error == null) {
            return new Refusal(what, DeviceFailureReason.UNKNOWN);
        }
        return new Refusal(what + ": " + error.code() + " - "
                + error.message() + (error.by() == null ? "" : " (held by "
                        + error.by() + ")"), reasonOf(error));
    }

    /**
     * WHY a refusal refused, in the vocabulary the operator is told in.
     *
     * <p>Spec 4.2's optional {@code reason} wins whenever the far end sent one:
     * it is the reading of the backend that owns the driver, made where the
     * driver is.  Without it the CODE is still an answer - it is this protocol's
     * own vocabulary, decided by the same two builds, not a driver's English that
     * no client may parse - and dropping it is what left the operator with
     * "reason unknown" for a device another client is plainly holding: every
     * code-only refusal ({@code DEVICE_LOCKED}, {@code DEVICE_STALE} and their
     * kin) carries no {@code reason} field at all, so the read is {@code UNKNOWN}
     * before this maps it.
     *
     * <p>Only codes that state a fact about the DEVICE are mapped.  A malformed
     * request, an unknown message type, a lock this session never took or a
     * server-side fault say nothing about the hardware, and inventing a device
     * reason for them would be a worse answer than {@code UNKNOWN}.
     */
    private DeviceFailureReason reasonOf(NetError error) {
        DeviceFailureReason sent = error.failureReason();
        if (sent != DeviceFailureReason.UNKNOWN) {
            return sent;
        }
        if (error.is(ErrorCode.DEVICE_LOCKED)) {
            return DeviceFailureReason.DEVICE_IN_USE;
        }
        if (error.is(ErrorCode.DEVICE_STALE)) {
            return DeviceFailureReason.DEVICE_NOT_FOUND;
        }
        return DeviceFailureReason.UNKNOWN;
    }

    /**
     * A refusal that also carries WHY, when the far end said why.
     *
     * <p>It is an {@link IllegalStateException} exactly as this client's
     * refusals have always been - every catch upstream is untouched - with the
     * far end's {@link DeviceFailureReason} beside the text.  That is what lets
     * {@code NetDeviceManager.classifyFailure} answer for a REMOTE device
     * without reading the server's English back: the classification was already
     * done, on the bench, by the backend that owns the driver, and this only
     * carries it the last hop to the operator's language.
     */
    public static final class Refusal extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        /** The far end's reading of its own failure, or the one its protocol
         *  error CODE states; {@code UNKNOWN} when neither says anything - a
         *  fault that was never a device's. */
        @Getter
        private final DeviceFailureReason reason;

        Refusal(String message, DeviceFailureReason reason) {
            super(message);
            this.reason = (reason == null) ? DeviceFailureReason.UNKNOWN : reason;
        }
    }

    // -------------------------------------------------------------------------
    // Listeners
    // -------------------------------------------------------------------------

    /** Routes the binary frames of {@code streamId} (spec 5) to {@code listener}
     *  until it is removed.  A second registration on the same id replaces the
     *  first - the server hands an id out once at a time. */
    public void addStreamListener(int streamId, FrameListener listener) {
        streams.put(streamId, listener);
    }

    /** Stops routing {@code streamId}; frames for it are dropped from here on,
     *  which is what a closed stream's late frames deserve. */
    public void removeStreamListener(int streamId) {
        streams.remove(streamId);
    }

    /**
     * The base URL of this bench's HTTP side - {@code http://host:port}, derived
     * from the very URI this session was dialled on.
     *
     * <p>One port serves both: the server's front end multiplexes the WebSocket
     * session and the REST endpoints of spec §3 ({@code /info}, {@code /health},
     * {@code /files}) onto a single listener, so the session's own host and port
     * ARE the upload address and no second setting can drift out of step with it.
     * The scheme is the only thing that changes.
     */
    public URI httpBase() {
        return URI.create("http://" + server.getHost() + ":" + server.getPort());
    }

    /** Subscribes to the server-initiated events of spec 4.3 and 4.5
     *  ({@code ev.*}) - they carry no id and are never answered. */
    public void addEventListener(Consumer<NetMessage> listener) {
        eventListeners.add(listener);
    }

    public void removeEventListener(Consumer<NetMessage> listener) {
        eventListeners.remove(listener);
    }

    /** Subscribes to the end of the session, whichever end it turns out to be.
     *  Fired exactly once. */
    public void addCloseListener(Consumer<NetCloseReason> listener) {
        closeListeners.add(listener);
    }

    /** Unsubscribes: a capture that closed in the ordinary way has nothing left
     *  to be told, and a subscription it could not take back would outlive it for
     *  the rest of the session. */
    public void removeCloseListener(Consumer<NetCloseReason> listener) {
        closeListeners.remove(listener);
    }

    // -------------------------------------------------------------------------
    // Receiving
    // -------------------------------------------------------------------------

    /**
     * One control message off the socket.
     *
     * <p>Package-private because the transport hands it in - and because a test
     * delivers the messages a real server cannot be made to produce on demand.
     */
    void onText(String text) {
        NetMessage message;
        try {
            message = codec.read(text);
        } catch (JsonProcessingException | RuntimeException e) {
            if (log.isWarnEnabled()) {
                log.warn("net client: undecodable message from {} dropped: {}",
                        server, e.toString());
            }
            return;
        }
        MessageType type = message.getType();
        Integer id = message.getId();
        if (type == MessageType.RESP) {
            CompletableFuture<NetMessage> answer = id == null ? null : pending.remove(id);
            if (answer != null) {
                answer.complete(message);
            }
            return;
        }
        if (type == MessageType.PING) {
            // Spec 4.0: the server's own requests carry NEGATIVE ids so they
            // cannot collide with ours, and are answered with the same resp
            // envelope a client request gets.
            if (id != null) {
                sendRaw(new NetMessage(id));
            }
            return;
        }
        if (id != null) {
            // Spec 1: "Unknown message types answer error UNSUPPORTED", and the
            // rule is written for BOTH sides.  Spec 4.0 says an id makes this a
            // REQUEST, so a v2 server asking something this build never heard of
            // would otherwise wait for an answer that never comes - the whole
            // point of negotiating a version is that the older peer stays
            // predictable.
            sendRaw(new NetMessage(id, new NetError(ErrorCode.UNSUPPORTED,
                    "this client does not answer '" + message.getT() + "'")));
            return;
        }
        for (Consumer<NetMessage> listener : eventListeners) {
            notifyEvent(listener, message);
        }
    }

    /**
     * One binary audio message off the socket (spec 5), routed to the stream it
     * names.  A frame for a stream nobody is listening to is dropped: a close
     * races the frames already in flight, and that race is ordinary.
     *
     * <p>Package-private for the same two reasons as {@link #onText(String)} -
     * and here the second one carries the weight: a GAP and a jumped packet
     * counter are exactly the frames a healthy server never sends.
     */
    void onBinary(byte[] message) {
        BinaryFrame frame;
        try {
            frame = BinaryFrame.parse(message);
        } catch (RuntimeException e) {
            // NOT dropped: spec 5 counts every frame, so the next one would trip
            // the receiving stream's counter check and be reported as transport
            // loss - a malformed frame told as the wrong fault, on a stream that
            // would meanwhile have gone on splicing.  A peer whose framing is
            // broken is not one to keep measuring with, so the session ends here
            // and every stream on it stops with one honest reason.
            if (log.isErrorEnabled()) {
                log.error("net client: malformed binary frame from {}: {}", server,
                        e.toString());
            }
            connClose(NetCloseReason.PROTOCOL_ERROR);
            return;
        }
        FrameListener listener = streams.get(frame.streamId());
        if (listener == null) {
            if (log.isDebugEnabled()) {
                log.debug("net client: frame for stream {} arrived after it closed",
                        frame.streamId());
            }
            return;
        }
        try {
            listener.onFrame(frame);
        } catch (Throwable e) {
            // A consumer that fails must not kill the reader thread, which
            // would freeze every stream on this connection - an Error from a
            // native decoder least of all.
            if (log.isErrorEnabled()) {
                log.error("net client: stream {} consumer failed (continuing)",
                        frame.streamId(), e);
            }
        }
    }

    /**
     * One keepalive period.  The count is checked BEFORE the next ping goes out,
     * so four unanswered pings - 2 s of silence, spec 4.1 - end the session half
     * a period later, and the modules are told while their measurement can still
     * be stopped.
     */
    private void keepaliveTick() {
        if (closed.get()) {
            return;
        }
        try {
            int unanswered = pingCounter - lastAnsweredPing.get();
            if (unanswered >= NetProto.MAX_MISSED_PINGS) {
                if (log.isWarnEnabled()) {
                    log.warn("net client: {} pings to {} unanswered - the server is gone",
                            unanswered, server);
                }
                connClose(NetCloseReason.KEEPALIVE_TIMEOUT);
                return;
            }
            int sequence = ++pingCounter;
            send(newRequest(MessageType.PING))
                    .thenRun(() -> lastAnsweredPing.accumulateAndGet(sequence, Math::max));
        } catch (RuntimeException e) {
            if (log.isWarnEnabled()) {
                log.warn("net client: keepalive to {} failed: {}", server, e.toString());
            }
            connClose(NetCloseReason.TRANSPORT_ERROR);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** A message that correlates with nothing - a ping answer, a farewell.
     *  Failure is logged and swallowed: both are sent on a socket that may
     *  already be going away, and neither has anybody left to tell. */
    private void sendRaw(NetMessage message) {
        try {
            transport.send(codec.write(message));
        } catch (RuntimeException e) {
            if (log.isDebugEnabled()) {
                log.debug("net client: {} not sent to {}: {}", message.getT(), server,
                        e.toString());
            }
        }
    }

    /** Forgets a request whose caller has stopped waiting for the answer.  The
     *  entry would otherwise sit in {@link #pending} for the rest of the
     *  session, holding a future nobody will ever read - and a late answer would
     *  find it and complete it, which is a wakeup with no waiter. */
    private void abandon(NetMessage message) {
        Integer id = message.getId();
        if (id != null) {
            pending.remove(id);
        }
    }

    private void failPending(Integer id, String reason) {
        CompletableFuture<NetMessage> answer = pending.remove(id);
        if (answer != null) {
            answer.completeExceptionally(new IllegalStateException(reason));
        }
    }

    /** THROWABLE, here and in the two fan-outs beside it: this is the reader
     *  thread's boundary into other people's code, and an Error escaping it kills
     *  the thread that carries every stream, every answer and the keepalive - a
     *  session that is dead while the client still believes it is measuring. */
    private void notifyEvent(Consumer<NetMessage> listener, NetMessage event) {
        try {
            listener.accept(event);
        } catch (Throwable e) {
            if (log.isErrorEnabled()) {
                log.error("net client: {} listener failed (continuing)", event.getT(), e);
            }
        }
    }

    private void notifyClosed(Consumer<NetCloseReason> listener, NetCloseReason reason) {
        try {
            listener.accept(reason);
        } catch (Throwable e) {
            if (log.isErrorEnabled()) {
                log.error("net client: close listener failed (continuing)", e);
            }
        }
    }

    private List<String> capsOf(JsonNode data) {
        List<String> tokens = new ArrayList<>();
        for (JsonNode token : data.path(NetFields.CAPS)) {
            tokens.add(token.asText());
        }
        return List.copyOf(tokens);
    }

    private String text(JsonNode owner, String field) {
        JsonNode node = owner.path(field);
        return node.isTextual() ? node.asText() : null;
    }

    /**
     * One capture's data connection (spec 4.7): a second socket to the same url,
     * carrying that capture's binary frames and nothing else after its
     * {@code capture.attach} has been answered.
     *
     * <p><b>Drop or orderly close.</b>  Spec 4.1 kills the whole session when a
     * connection DROPS, and spec 4.7 exempts the close that ends a capture in
     * the ordinary way - so this end has to know which it is looking at, and the
     * answer is on the wire: an orderly close carries the NORMAL status code,
     * and only an abnormal one ({@code 1006} and its kin - a socket that died
     * without a close frame) is a drop.  That is what makes the decision
     * race-free: the bench closes this socket as it handles a
     * {@code capture.close}, and its {@code resp} travels on the OTHER
     * connection, so the close can arrive first - a rule that depended on this
     * end having marked the socket in time would end the session over an
     * ordinary teardown.
     *
     * <p>{@link #expectClose()} is kept as the cheap local answer for the case
     * this end already knows about, not as the correctness rule.  It is
     * deliberately this connection's own flag and not the capture's "I am
     * closing" state - that one is raised by a plain {@code capture.stop} as
     * well, and a stop leaves this socket open.
     *
     * <p>Its frames go into the session's demux ({@link #onBinary(byte[])}), so
     * a capture is routed by {@code streamId} exactly as it was when one socket
     * carried everything.  The only TEXT it may ever carry is the answer to its
     * own attach.
     */
    public final class DataConnection extends WebSocketClient {

        /** The capture this socket carries - for the log lines, which must be
         *  able to name which of a session's streams lost its transport. */
        private final int captureId;
        /** Whether THIS end knows the connection is ending - see the class
         *  comment.  Atomic because the mark is raised on the caller's thread
         *  and read on the socket's reader thread. */
        private final AtomicBoolean closing = new AtomicBoolean();
        /** Whether the attach has been answered.  Before it, one text message is
         *  expected on this socket; after it, none ever again (spec 4.7). */
        private final AtomicBoolean attached = new AtomicBoolean();

        private DataConnection(URI address, int captureId) {
            super(address);
            this.captureId = captureId;
            setConnectionLostTimeout(LIBRARY_KEEPALIVE_OFF);
        }

        /** The attach was answered: this socket carries nothing but audio now. */
        void attached() {
            attached.set(true);
        }

        /** The close is COMING, from the far end, because this client asked for
         *  something that ends the capture ({@code capture.close},
         *  {@code device.release}) or was told the lane failed.  An optimisation
         *  only - the status code decides (see the class comment). */
        public void expectClose() {
            closing.set(true);
        }

        @Override
        public void close() {
            closing.set(true);
            super.close();
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            // Nothing: this connection's own handshake is `capture.attach`,
            // which attachData sends once the socket is up.
        }

        @Override
        public void onMessage(String text) {
            if (attached.get()) {
                // Spec 4.7: the planes do not mix.  An event or a request on a
                // data connection is a peer that has lost track of which socket
                // it is writing to, and acting on it would put control traffic
                // behind whatever audio is queued - the very thing the split
                // exists to prevent.  Ended like a malformed frame, for the same
                // reason: a peer whose framing is broken is not one to keep
                // measuring with.
                if (log.isErrorEnabled()) {
                    log.error("net client: text on the data connection of capture {} - a data "
                            + "connection carries nothing but audio after its attach",
                            captureId);
                }
                connClose(NetCloseReason.PROTOCOL_ERROR);
                return;
            }
            // The answer to this connection's own attach, and only that; it
            // correlates through the session's pending table like any other.
            onText(text);
        }

        @Override
        public void onMessage(ByteBuffer binary) {
            byte[] raw = new byte[binary.remaining()];
            binary.get(raw);
            onBinary(raw);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            dataConnections.remove(this);
            if (closing.get() || code == CloseFrame.NORMAL) {
                return;
            }
            if (log.isWarnEnabled()) {
                log.warn("net client: the data connection of capture {} dropped ({}) - "
                        + "the session with {} is dead", captureId, code, server);
            }
            connClose(NetCloseReason.TRANSPORT_CLOSED, code);
        }

        @Override
        public void onError(Exception e) {
            if (closing.get()) {
                return;
            }
            if (log.isWarnEnabled()) {
                log.warn("net client: transport error on the data connection of capture {}: {}",
                        captureId, e.toString());
            }
            connClose(NetCloseReason.TRANSPORT_ERROR);
        }
    }

    /** The transport never connected: nothing listens at the address, or the
     *  timeout ran out first.  A subtype of the refusal exceptions so existing
     *  catches keep working, but typed, because "nobody answered" is the one
     *  connect failure with a translation of its own. */
    public static final class NoAnswerException extends IllegalStateException {
        private NoAnswerException(String message) {
            super(message);
        }
    }

    /**
     * The WebSocket library, kept inside: nothing outside this class names a
     * transport type, so the protocol above and the socket below can be read -
     * and changed - separately.  It does no protocol work at all; every callback
     * hands straight over to the session.
     */
    private final class Transport extends WebSocketClient {

        private Transport(URI address) {
            super(address);
            setConnectionLostTimeout(LIBRARY_KEEPALIVE_OFF);
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            // Nothing to do: the protocol's own handshake is `hello`, which
            // open() sends once the socket is up.
        }

        @Override
        public void onMessage(String text) {
            onText(text);
        }

        @Override
        public void onMessage(ByteBuffer binary) {
            byte[] raw = new byte[binary.remaining()];
            binary.get(raw);
            onBinary(raw);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            connClose(NetCloseReason.TRANSPORT_CLOSED, code);
        }

        @Override
        public void onError(Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("net client: transport error on {}: {}", server, e.toString());
            }
            connClose(NetCloseReason.TRANSPORT_ERROR);
        }
    }
}
