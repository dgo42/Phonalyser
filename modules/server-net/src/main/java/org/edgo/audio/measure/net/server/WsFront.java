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
import org.edgo.audio.measure.net.proto.JsonCodec;
import org.edgo.audio.measure.net.proto.MessageType;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.net.proto.NetMessage;
import org.edgo.audio.measure.sound.AudioBackend;

import lombok.extern.log4j.Log4j2;

/**
 * The control plane's front door: the WebSocket endpoint of spec 4, and the only
 * type in the package that names a WebSocket type at all.  It turns transport
 * events into session calls and nothing else - the protocol lives in
 * {@link ClientSession}.
 *
 * <p>It no longer owns a listener.  {@link CombinedFront} binds the ONE port and
 * hands the requests that carry a WebSocket upgrade here; this type says WHAT
 * the endpoint is ({@link #configure}) and holds the sessions it produces.
 *
 * <p>It is also where a connection's collaborators are assembled: one session
 * per socket, wired to its own channel, its own request thread and its own
 * tickers, drawn from the clock the composition root handed it.  Constructing
 * them here is the point - a connection cannot exist before its socket does, so
 * this is the only place that can.  WHAT a ticker is remains
 * {@link ServerMain}'s decision: this type schedules nothing itself.
 *
 * <p>And it is the only type that holds every live session at once, which makes
 * it the one that can fan a server event out to all of them - the
 * {@code ev.devices.changed} broadcast of spec 4.3.
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
    private final Map<Session, ClientSession> sessions = new ConcurrentHashMap<>();

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

    /** One connection, with everything it needs built around it. */
    private void opened(Session session) {
        String peer = String.valueOf(session.getRemoteSocketAddress());
        SessionChannel channel = new WsSessionChannel(session, codec);
        CaptureStreamer captures = new CaptureStreamer(audio, catalog, codec, channel,
                new ThreadSessionWorker(AUDIO_LANE + peer), qa40x);
        SessionWorker worker = new ThreadSessionWorker(peer);
        GeneratorSession generator = new GeneratorSession(audio, catalog, captures, files,
                qa40x, codec, channel, worker, tickers.get(), System::currentTimeMillis);
        ClientSession clientSession = new ClientSession(config, locks, qa40x, catalog,
                captures, generator, qa40xSession, codec, channel, tickers.get(), worker);
        sessions.put(session, clientSession);
        clientSession.start();
        if (log.isInfoEnabled()) {
            log.info("net server: connection from {}", peer);
        }
    }

    /**
     * The connection ended - the only announcement a client that was killed,
     * halted or unplugged ever makes, and therefore the path its devices come
     * back on.
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
        ClientSession clientSession = session == null ? null : sessions.remove(session);
        if (clientSession != null) {
            onTransportThread("closing the session of " + clientSession.getClientName(),
                    () -> clientSession.close("transport closed (" + code + ")"));
        }
    }

    private void received(Session session, String message) {
        ClientSession clientSession = sessions.get(session);
        if (clientSession == null) {
            if (log.isWarnEnabled()) {
                log.warn("net server: message on an unknown connection {}",
                        session.getRemoteSocketAddress());
            }
            return;
        }
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
        if (log.isWarnEnabled()) {
            log.warn("net session {}: transport error: {}",
                    clientSession == null ? session.getRemoteSocketAddress()
                            : clientSession.getClientName(), error.toString());
        }
        if (clientSession != null) {
            onTransportThread("closing the session of " + clientSession.getClientName(),
                    () -> clientSession.close("transport error"));
        }
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
        for (ClientSession session : open) {
            session.close(reason);
        }
        for (ClientSession session : open) {
            session.awaitClosed(timeoutMs);
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
     * One socket's end of the transport: Jetty's callbacks, routed to the
     * session assembly above.
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
