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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.server.ServerWebSocketContainer;

import org.edgo.audio.measure.net.proto.BinaryFrame;
import org.edgo.audio.measure.net.proto.ErrorCode;
import org.edgo.audio.measure.net.proto.JsonCodec;
import org.edgo.audio.measure.net.proto.MessageType;
import org.edgo.audio.measure.net.proto.NetError;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.net.proto.NetMessage;
import org.edgo.audio.measure.net.proto.NetProto;
import org.edgo.audio.measure.sound.AudioBackend;

import lombok.extern.log4j.Log4j2;

/**
 * The front door of both planes: the WebSocket endpoint of spec 4, and the only
 * type in the package that names a WebSocket type at all.  It turns transport
 * events into session calls and nothing else - the protocol lives in
 * {@link ClientSession}.
 *
 * <p>It no longer owns a listener.  {@link CombinedFront} binds the ONE port and
 * hands the requests that carry a WebSocket upgrade here; this type says WHAT
 * the endpoint is ({@link #configure}) and holds the connections it produces.
 *
 * <p><b>Which plane a connection is.</b>  Spec 4 dials both planes at the same
 * url, so an upgraded socket is nothing yet: its FIRST text message decides.
 * {@code hello} makes it a control connection and builds the session behind it;
 * {@code capture.attach} makes it the data connection of one capture (spec 4.7).
 * Anything else, or {@link NetProto#PLANE_DECLARATION_TIMEOUT_MS} of silence,
 * and it is closed.  Nothing is built before that message arrives - no session,
 * no worker, no keepalive - because a socket that has not said what it is is not
 * a session, and a keepalive belongs to a session.
 *
 * <p>It is therefore where a connection's collaborators are assembled: one
 * session per control connection, wired to its own channel, its own request
 * thread and its own tickers, drawn from the clock the composition root handed
 * it.  Constructing them here is the point - a connection cannot exist before
 * its socket does, so this is the only place that can.  WHAT a ticker is remains
 * {@link ServerMain}'s decision: this type schedules nothing itself.
 *
 * <p>And it is the only type that holds every live session at once, which makes
 * it the one that can fan a server event out to all of them - the
 * {@code ev.devices.changed} broadcast of spec 4.3 - and the one that can answer
 * which session a {@code clientId} names.
 */
@Log4j2
public final class WsFront {

    /** Where a client opens the control channel: the server root, so the same
     *  URL serves the web bundle to a browser and upgrades a desktop client. */
    private static final String UPGRADE_PATH = "/";
    /** Distinguishes a connection's audio thread from its request thread in a
     *  thread dump - the two are deliberately separate (see
     *  {@link CaptureStream}). */
    private static final String AUDIO_LANE = "audio-";

    /** Longest capture batch spec 5 describes ("~10 - 100 ms"). */
    private static final int MAX_BATCH_MS = 100;
    /** Highest sample rate a served backend offers - the QA40x's. */
    private static final int MAX_BATCH_RATE_HZ = 384_000;
    /** Widest stereo frame a device can be opened at: two 32-bit samples. */
    private static final int MAX_BATCH_FRAME_BYTES = 8;
    private static final int MILLIS_PER_SECOND = 1_000;
    /** The widest binary message this server can produce: one such batch plus
     *  the frame header of spec 5 - 307 216 bytes, five times Jetty's own
     *  64 KiB default, which is why this is set at all. */
    private static final long MAX_PCM_MESSAGE_BYTES =
            (long) MAX_BATCH_RATE_HZ * MAX_BATCH_FRAME_BYTES * MAX_BATCH_MS
                    / MILLIS_PER_SECOND + BinaryFrame.HEADER_BYTES;
    /** How much room is left above that.  A cap is a connection-killing limit,
     *  not a budget: it costs nothing until it is hit, and the batch size a
     *  future backend hands over is not this type's to predict. */
    private static final int MESSAGE_HEADROOM = 4;
    private static final long MAX_BINARY_MESSAGE_BYTES =
            MAX_PCM_MESSAGE_BYTES * MESSAGE_HEADROOM;
    /** The control plane's own cap.  Text messages are the JSON of spec 4, and
     *  the largest of them is a {@code devices.list} of a big bench - kilobytes,
     *  not megabytes; this is headroom, not a size anything reaches. */
    private static final long MAX_TEXT_MESSAGE_BYTES = 1024L * 1024L;
    /** No transport-level idle timeout.  Liveness is decided by the 500 ms
     *  keepalive of spec 4.1 - a second, slower detector is switched off rather
     *  than left to disagree with it (a capture stream can legitimately be the
     *  only thing on the socket for minutes). */
    private static final Duration NO_IDLE_TIMEOUT = Duration.ZERO;

    private final ServerConfig config;
    private final LockRegistry locks;
    private final Qa40xGuard qa40x;
    /** The analyzer's own commands (spec 4.6), shared by every connection: one
     *  analyzer, and a type with no per-connection state to keep. */
    private final Qa40xSession qa40xSession;
    private final DeviceCatalog catalog;
    private final AudioBackend audio;
    private final FileStore files;
    private final JsonCodec codec;
    /** Where a new connection's periodic tasks get their clock - the keepalive of
     *  spec 4.1 and the generator's position pushes of spec 4.5.  A factory
     *  rather than a scheduler: what a tick IS belongs to the composition root,
     *  and handing this front a scheduler would make every connection's sense of
     *  time impossible to drive from a test. */
    private final Supplier<Ticker> tickers;
    /** The live sessions, keyed by their CONTROL connection (spec 4). */
    private final Map<Session, ClientSession> sessions = new ConcurrentHashMap<>();
    /** The same sessions by the handle a {@code capture.attach} names (spec
     *  4.1, 4.7).  A separate index rather than a scan: an attach arrives on the
     *  transport thread and must not walk every session on the bench, and the
     *  handle is a secret whose only use is exactly this lookup. */
    private final Map<String, ClientSession> byClientId = new ConcurrentHashMap<>();
    /** Sockets that have upgraded but not yet declared their plane (spec 4),
     *  each with the clock that will close it if it never does. */
    private final Map<Session, PendingConnection> pending = new ConcurrentHashMap<>();
    /** The attached data connections of spec 4.7, keyed by their socket - what
     *  turns a close callback into "which capture of which session just lost its
     *  audio". */
    private final Map<Session, DataConnection> dataSockets = new ConcurrentHashMap<>();

    public WsFront(ServerConfig config, LockRegistry locks, Qa40xGuard qa40x,
            Qa40xSession qa40xSession, DeviceCatalog catalog, AudioBackend audio,
            FileStore files, JsonCodec codec, Supplier<Ticker> tickers) {
        this.config = config;
        this.locks = locks;
        this.qa40x = qa40x;
        this.qa40xSession = qa40xSession;
        this.catalog = catalog;
        this.audio = audio;
        this.files = files;
        this.codec = codec;
        this.tickers = tickers;
        locks.addChangeListener(this::broadcastDevicesChanged);
    }

    /**
     * What the upgrade of spec 4 produces, and the limits the audio of spec 5
     * needs - told to the container {@link CombinedFront} owns, because the
     * endpoint is this type's business and the transport is not.
     *
     * <p>The message caps are raised from Jetty's 64 KiB defaults because a
     * single PCM batch is several times that at the rates this bench measures
     * at (see {@link #MAX_PCM_MESSAGE_BYTES}), and the frame cap with them so a
     * batch still crosses as ONE frame rather than a fragmented one.
     */
    public void configure(ServerWebSocketContainer container) {
        container.setIdleTimeout(NO_IDLE_TIMEOUT);
        container.setMaxTextMessageSize(MAX_TEXT_MESSAGE_BYTES);
        container.setMaxBinaryMessageSize(MAX_BINARY_MESSAGE_BYTES);
        container.setMaxFrameSize(MAX_BINARY_MESSAGE_BYTES);
        container.addMapping(UPGRADE_PATH,
                (request, response, callback) -> new Endpoint());
    }

    /**
     * A socket upgraded, and that is ALL that is known about it: spec 4 leaves
     * the plane to the first message, so nothing is built here but the channel
     * to answer on and the clock that closes a connection which never speaks.
     */
    private void opened(Session session) {
        WsSessionChannel channel = new WsSessionChannel(session, codec);
        Ticker undeclared = tickers.get();
        pending.put(session, new PendingConnection(channel, undeclared));
        undeclared.start(NetProto.PLANE_DECLARATION_TIMEOUT_MS,
                () -> planeUndeclared(session));
        if (log.isDebugEnabled()) {
            log.debug("net server: a connection from {} upgraded, waiting for its plane",
                    peerOf(session));
        }
    }

    /**
     * The first message never came (spec 4): the connection is closed, and
     * nothing has to be given back because nothing was ever built for it.
     *
     * <p>It stops its own ticker first, which is what makes this repeating task
     * a one-shot - the same shape the session's teardown watchdog uses.
     */
    private void planeUndeclared(Session session) {
        PendingConnection waiting = pending.remove(session);
        if (waiting == null) {
            return;
        }
        waiting.ticker().stop();
        if (log.isWarnEnabled()) {
            log.warn("net server: {} said nothing within {} ms - closing a connection that "
                    + "never declared its plane", peerOf(session),
                    NetProto.PLANE_DECLARATION_TIMEOUT_MS);
        }
        waiting.channel().close("no hello and no capture.attach");
    }

    /**
     * The first message on a fresh connection, which is what it IS (spec 4).
     *
     * <p>An undecodable one is treated exactly like a wrong one: the point of
     * the rule is that a connection whose plane cannot be read is not a
     * connection this server can serve.
     */
    private void declarePlane(Session session, WsSessionChannel channel, String message) {
        NetMessage first;
        try {
            first = codec.read(message);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            if (log.isWarnEnabled()) {
                log.warn("net server: undecodable first message from {}: {}",
                        peerOf(session), e.toString());
            }
            channel.close("a connection's first message must be hello or capture.attach");
            return;
        }
        MessageType type = first.getType();
        if (type == MessageType.HELLO) {
            openControl(session, channel, first);
            return;
        }
        if (type == MessageType.CAPTURE_ATTACH) {
            openData(session, channel, first);
            return;
        }
        if (log.isWarnEnabled()) {
            log.warn("net server: {} opened with '{}' - a connection's first message must be "
                    + "hello (control) or capture.attach (data)", peerOf(session), first.getT());
        }
        channel.close("a connection's first message must be hello or capture.attach");
    }

    /** One control connection, with everything its session needs built around
     *  it - the assembly that used to happen at the upgrade, now that the
     *  {@code hello} has proved this is the plane it belongs to (spec 4). */
    private void openControl(Session session, WsSessionChannel channel, NetMessage hello) {
        String peer = peerOf(session);
        CaptureStreamer captures = new CaptureStreamer(audio, catalog, codec, channel,
                new ThreadSessionWorker(AUDIO_LANE + peer), qa40x);
        SessionWorker worker = new ThreadSessionWorker(peer);
        GeneratorSession generator = new GeneratorSession(audio, catalog, captures, files,
                qa40x, codec, channel, worker, tickers.get(), System::currentTimeMillis);
        ClientSession clientSession = new ClientSession(config, locks, qa40x, catalog,
                captures, generator, qa40xSession, codec, channel, tickers.get(), worker);
        sessions.put(session, clientSession);
        byClientId.put(clientSession.getClientId(), clientSession);
        // The keepalive of spec 4.1 starts HERE and not at the upgrade: it is a
        // session's heartbeat, and until this message there was no session.
        clientSession.start();
        if (log.isInfoEnabled()) {
            log.info("net server: control connection from {}", peer);
        }
        clientSession.onMessage(hello);
    }

    /**
     * One data connection (spec 4.7): the socket names the session it belongs to
     * and the capture it will carry, and from the answer on it carries nothing
     * but that capture's binary frames.
     *
     * <p>A refusal is ANSWERED and then closes the socket - a connection that
     * may not attach has no other purpose, and the client reads the pair as the
     * failure of the {@code capture.open} it dialled for.  The {@code clientId}
     * is never named in a log line: it is the ticket to somebody's captures.
     */
    private void openData(Session session, WsSessionChannel channel, NetMessage attach) {
        Integer id = attach.getId();
        String handle = attach.optString(NetFields.CLIENT_ID);
        Integer captureId = attach.optInt(NetFields.CAPTURE_ID);
        ClientSession owner = handle == null ? null : byClientId.get(handle);
        try {
            if (owner == null) {
                throw new NetException(ErrorCode.BAD_REQUEST,
                        "capture.attach names no session this server is serving");
            }
            owner.attachCapture(captureId, channel);
        } catch (NetException e) {
            if (log.isWarnEnabled()) {
                log.warn("net server: {} could not attach to capture {}: {}",
                        peerOf(session), captureId, e.getMessage());
            }
            if (id != null) {
                channel.send(new NetMessage(id, new NetError(e.getCode(), e.getMessage())));
            }
            channel.close("capture.attach refused");
            return;
        }
        dataSockets.put(session, new DataConnection(owner, channel, captureId));
        // After the bind, so a refusal answers nothing but the refusal - and
        // immediately, so the answer is on the socket before anything else can
        // be: the frames of this capture cannot start until the client has read
        // it and sent capture.start (spec 4.4).
        if (id != null) {
            channel.send(new NetMessage(id));
        }
    }

    /**
     * Spec 4.7: a data connection's attach is its only client message, so
     * anything after it is a protocol error - the socket closes.
     *
     * <p>And the session goes with it.  The client will see this close as the
     * unexpected end of an attached data connection, which its own death rule
     * (spec 4.1) turns into a dead session; a server that kept the session alive
     * would sit on the bench's locks until its keepalive noticed, holding
     * devices for a peer that has already stopped measuring.
     */
    private void strayTextOnData(Session session) {
        DataConnection data = dataSockets.remove(session);
        if (data == null) {
            return;
        }
        if (log.isWarnEnabled()) {
            log.warn("net session {}: text on the data connection of capture {} - a data "
                    + "connection carries nothing but its attach", data.owner().getClientName(),
                    data.captureId());
        }
        data.channel().close("a data connection carries no client messages after its attach");
        data.owner().close("protocol error on a data connection");
    }

    /**
     * A connection ended - for a control connection, the only announcement a
     * client that was killed, halted or unplugged ever makes, and therefore the
     * path its devices come back on.
     *
     * <p>Which of the three registries the socket is in says what its close
     * MEANS: a connection that never declared its plane simply goes away, a data
     * connection is measured against spec 4.7's discriminator, and a control
     * connection takes the whole session with it.
     *
     * <p>The session is named by ITS OWN name and not by the socket's address.
     * This method runs on a connection that is already gone, and it has just
     * taken that connection out of {@link #sessions}: a transport call made
     * here - while composing a log line, of all things - that faulted would
     * leave a session nothing points at any more and nothing has closed, with
     * its locks, its capture lines and its generator still in its name.  Only
     * the keepalive would notice, seconds later, and the server's own shutdown
     * could no longer reach that session at all.  The name is the session's own
     * field, so composing the label cannot fail and cannot decide whether the
     * teardown runs.
     */
    private void closed(Session session, int code) {
        // Null when the connection never opened - Jetty still reports the close
        // of an upgrade that failed, and there is nothing behind it.
        if (session == null) {
            return;
        }
        PendingConnection waiting = pending.remove(session);
        if (waiting != null) {
            waiting.ticker().stop();
            return;
        }
        DataConnection data = dataSockets.remove(session);
        if (data != null) {
            onTransportThread("ending the data connection of capture " + data.captureId(),
                    () -> dataClosed(data, code));
            return;
        }
        ClientSession clientSession = sessions.remove(session);
        if (clientSession != null) {
            byClientId.remove(clientSession.getClientId());
            onTransportThread("closing the session of " + clientSession.getClientName(),
                    () -> clientSession.close("transport closed (" + code + ")"));
        }
    }

    /**
     * Spec 4.1's death rule against spec 4.7's discriminator: a data
     * connection's close kills the session unless THIS end started it.
     *
     * <p>The mark is the channel's own, raised before the socket is closed, so
     * every orderly end is covered by the one test - the {@code capture.close}
     * or {@code device.release} that closed the stream, the lane failure that
     * closed it, the session teardown, and the refused attach that never became
     * a data connection at all.  Anything else is the client's transport going
     * away under a capture that is still open, and a session whose audio has
     * stopped arriving is not one to keep the bench's locks for.
     */
    private void dataClosed(DataConnection data, int code) {
        if (data.channel().isClosedByThisEnd()) {
            return;
        }
        if (log.isWarnEnabled()) {
            log.warn("net session {}: the data connection of capture {} dropped ({}) - "
                    + "the session is dead", data.owner().getClientName(), data.captureId(),
                    code);
        }
        data.owner().close("a data connection dropped (" + code + ")");
    }

    private void received(Session session, String message) {
        ClientSession clientSession = sessions.get(session);
        if (clientSession != null) {
            deliver(clientSession, message);
            return;
        }
        PendingConnection waiting = pending.remove(session);
        if (waiting != null) {
            // The plane is declared, so the connection is no longer waiting to
            // be closed for saying nothing - whatever it turns out to be.
            waiting.ticker().stop();
            onTransportThread("reading the first message from " + peerOf(session),
                    () -> declarePlane(session, waiting.channel(), message));
            return;
        }
        if (dataSockets.containsKey(session)) {
            onTransportThread("ending a data connection that spoke out of turn",
                    () -> strayTextOnData(session));
            return;
        }
        if (log.isWarnEnabled()) {
            log.warn("net server: message on an unknown connection {}", peerOf(session));
        }
    }

    /** One control message, on the session it belongs to. */
    private void deliver(ClientSession clientSession, String message) {
        onTransportThread("delivering a message to " + clientSession.getClientName(), () -> {
            try {
                clientSession.onMessage(codec.read(message));
            } catch (JsonProcessingException | IllegalArgumentException e) {
                // No id to correlate an error response to, so the only honest
                // answer is a log line (spec 4.0 keys every response on the id).
                // A message that DID decode can no longer fail here: the session
                // answers its own handler faults with INTERNAL (spec 4.2).
                if (log.isWarnEnabled()) {
                    log.warn("net session {}: undecodable message dropped: {}",
                            clientSession.getClientName(), e.toString());
                }
            }
        });
    }

    private void failed(Session session, Throwable error) {
        if (session == null) {
            // A fault before the connection was ever open: there is no session
            // to end, and the upgrade the client attempted simply failed.
            if (log.isWarnEnabled()) {
                log.warn("net server: a connection failed before it opened: {}",
                        error.toString());
            }
            return;
        }
        ClientSession clientSession = sessions.get(session);
        DataConnection data = dataSockets.get(session);
        if (log.isWarnEnabled()) {
            log.warn("net session {}: transport error: {}", nameOf(clientSession, data, session),
                    error.toString());
        }
        ClientSession dying = faultedSession(clientSession, data);
        if (dying != null) {
            onTransportThread("closing the session of " + dying.getClientName(),
                    () -> dying.close("transport error"));
        }
    }

    /**
     * The session a transport fault ends, or null when it ends none.
     *
     * <p>A fault on EITHER plane is the session's death (spec 4.1): a data
     * connection that faulted is one whose audio has stopped, and the close
     * callback that follows would find the socket already out of the map.
     * Except one this end was already closing - a transport that faults while a
     * stream is being shut down is reporting the shutdown, and spec 4.7's
     * discriminator applies to a fault exactly as it does to a close.
     */
    private ClientSession faultedSession(ClientSession control, DataConnection data) {
        if (control != null) {
            return control;
        }
        if (data != null && !data.channel().isClosedByThisEnd()) {
            return data.owner();
        }
        return null;
    }

    /** What to call a faulted connection in a log line: the session's own name
     *  where there is one, and the socket's address for a connection that never
     *  declared its plane. */
    private String nameOf(ClientSession control, DataConnection data, Session session) {
        if (control != null) {
            return control.getClientName();
        }
        return data == null ? peerOf(session) : data.owner().getClientName();
    }

    /** The socket's address, for a log line - the ONLY thing a connection that
     *  has not declared its plane can be named by. */
    private String peerOf(Session session) {
        return String.valueOf(session.getRemoteSocketAddress());
    }

    /**
     * One transport callback, guarded.
     *
     * <p>Jetty hands EVERY connection of this server to a shared pool, so a
     * throwable let out of one connection's callback is not that connection's
     * problem: it is a pooled thread dying with several benches on it.  And these
     * callbacks are precisely the paths that end in device work - a close runs
     * the teardown, a message runs a request - so the throwable in question is
     * the one JNA raises when the hardware is pulled.  The connection that
     * faulted is named, and the thread goes on serving the others.
     */
    private void onTransportThread(String what, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            if (log.isErrorEnabled()) {
                log.error("net server: {} failed", what, t);
            }
        }
    }

    /**
     * Ends every live connection and WAITS for each session's teardown - the
     * server-shutdown end of the one teardown path of spec 4.1, which is the
     * only one whose caller is about to take the JVM (and with it every
     * session's daemon thread) away.
     *
     * <p>They are all closed first and awaited afterwards, so the benches come
     * down side by side: each teardown runs on its own session's thread, and a
     * client stuck on a wedged device would otherwise add its whole timeout to
     * every other connection's.
     */
    public void closeSessions(String reason, long timeoutMs) {
        List<ClientSession> open = new ArrayList<>(sessions.values());
        sessions.clear();
        byClientId.clear();
        // The connections that never became a session: nothing to tear down,
        // but a socket the shutdown would otherwise leave to Jetty.
        List<PendingConnection> undeclared = new ArrayList<>(pending.values());
        pending.clear();
        for (PendingConnection waiting : undeclared) {
            waiting.ticker().stop();
            waiting.channel().close(reason);
        }
        for (ClientSession session : open) {
            session.close(reason);
        }
        for (ClientSession session : open) {
            session.awaitClosed(timeoutMs);
        }
        // AFTER the teardowns, which close the data connections of the captures
        // they stop (spec 4.7) - what is left here is a socket whose teardown
        // could not reach it, and it is closed rather than left to the JVM.
        List<DataConnection> streaming = new ArrayList<>(dataSockets.values());
        dataSockets.clear();
        for (DataConnection data : streaming) {
            data.channel().close(reason);
        }
    }

    /**
     * Spec 4.3: every client is told about every lock change, so a device
     * another client just took - or just lost by dying, which spec 6 promises
     * the others see free within 2.5 s - grays out live instead of failing on
     * the next acquire.
     *
     * <p>Fired by the {@link LockRegistry} on whichever thread changed it: a
     * session's request thread, or the keepalive declaring one dead - and by
     * {@link ServerMain}'s hot-plug rescan on its own lane, which is the other
     * half of the same sentence in spec 4.3.  It must therefore not block, and
     * does not - the sends go straight onto the sockets.
     *
     * <p>Spec 4.3 says the event carries the full {@code devices.list} payload,
     * and it does - but built from the LAST enumeration rather than a fresh one.
     * A lock change cannot move a device, and one of the threads that lands here
     * is the keepalive scheduler declaring a client dead: enumerating hardware
     * on it would stall the pings of every other connection and could kill them
     * too.  {@link ServerMain} enumerates once at start-up so that "last
     * enumeration" always exists.
     */
    public void broadcastDevicesChanged() {
        NetMessage event = new NetMessage(MessageType.EV_DEVICES_CHANGED)
                .put(NetFields.BACKENDS, catalog.lastScan());
        for (ClientSession session : sessions.values()) {
            session.send(event);
        }
    }

    /**
     * A socket that has upgraded and not yet said which plane it is (spec 4):
     * the channel to answer or close it on, and the clock that will close it if
     * the first message never comes.
     *
     * <p>Its own state and its own lifetime, both shorter than any session's -
     * which is why it is not carried as two parallel maps.
     */
    private record PendingConnection(WsSessionChannel channel, Ticker ticker) {
    }

    /**
     * An attached data connection (spec 4.7): the session it was let in by, the
     * channel that knows whether a close was ours, and the capture it carries.
     *
     * <p>The capture id is kept for the log lines: a bench with three streams
     * open has three of these sockets, and "a data connection dropped" says
     * nothing an operator can act on.
     */
    private record DataConnection(ClientSession owner, WsSessionChannel channel,
            Integer captureId) {
    }

    /**
     * One socket's end of the transport: Jetty's callbacks, routed to the
     * dispatch above.
     *
     * <p>It holds its own {@link Session} because Jetty passes one only to the
     * open callback, and it is {@code AutoDemanding} because this server reads
     * every message the client sends - there is no flow control to exercise on
     * the inbound side, where the whole traffic is small JSON requests.
     *
     * <p>PUBLIC, and not by preference: Jetty binds these callbacks with method
     * handles it unreflects at upgrade time, and a private class's public
     * methods are not accessible to it - the upgrade then fails with a 500 and
     * no client can connect at all.  Nothing outside constructs one: the
     * creator in {@link #configure} is the only caller.
     */
    public final class Endpoint implements Session.Listener.AutoDemanding {

        private Session session;

        @Override
        public void onWebSocketOpen(Session connected) {
            session = connected;
            opened(connected);
        }

        @Override
        public void onWebSocketText(String message) {
            received(session, message);
        }

        @Override
        public void onWebSocketError(Throwable error) {
            failed(session, error);
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason) {
            closed(session, statusCode);
        }
    }
}
