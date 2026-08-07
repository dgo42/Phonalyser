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

import java.net.InetSocketAddress;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * The ONE socket this server listens on, and the only type that names a Jetty
 * type at all: {@code http://host:port/info} and {@code ws://host:port/} are the
 * same port, the same connector and the same thread pool.
 *
 * <p><b>Why one port.</b>  Two of them were two chances for an operator to open
 * the wrong one in a firewall, two numbers in every beacon and every remembered
 * server, and a browser loading the web bundle from one port had to be told the
 * other.  A WebSocket upgrade is an ordinary HTTP request until the handshake
 * completes, so one listener can serve both planes and the protocol says which
 * is meant.
 *
 * <p><b>The chain.</b>  {@code Server -> ContextHandler("/") ->
 * WebSocketUpgradeHandler -> HttpFront}.  A request carrying
 * {@code Upgrade: websocket} for the mapped path is taken by the upgrade
 * handler and becomes a session; everything else falls through to
 * {@link HttpFront}, so a plain {@code GET /} still serves the web bundle.
 *
 * <p><b>What it does NOT own.</b>  The endpoint's behaviour is {@link WsFront}'s
 * and the HTTP semantics are {@link HttpFront}'s - both arrive through the
 * constructor.  This type binds a port, starts, and stops.
 */
@Log4j2
@RequiredArgsConstructor
public final class CombinedFront {

    /** The whole server is one context at the root: there is no application to
     *  mount under a prefix, and spec 3's paths are absolute. */
    private static final String ROOT_CONTEXT = "/";
    /** Names the request threads in a dump.  Jetty's pool serves the HTTP
     *  handlers AND the WebSocket callbacks - one lane, as before, kept away
     *  from the keepalive scheduler and the sessions' own workers. */
    private static final String POOL_NAME = "net-http";

    private final ServerConfig config;
    /** The fall-through handler: everything that is not an upgrade. */
    private final HttpFront http;
    /** The upgrade endpoint's owner - it configures the container this front
     *  builds, because WHAT a session is belongs to it and not here. */
    private final WsFront ws;

    /** Null until {@link #start()} succeeded; the two Jetty objects that have
     *  to outlive it, since stopping is two steps. */
    private Server server;
    private ServerConnector connector;
    /** The port the connector really bound, LATCHED at start rather than asked
     *  for on demand.  {@link ServerConnector#getLocalPort()} answers a negative
     *  sentinel the moment {@link #stopAccepting()} closes the accepting
     *  channel - and that window is exactly when the live connections are still
     *  being served, so a client asking {@code /info} during the goodbye would
     *  be told a "port" that is not one.  Volatile: written by whoever starts
     *  the server, read by every request thread and by the beacon. */
    private volatile int boundPort;

    /**
     * Binds the port and serves both planes on it.  Synchronous: Jetty's start
     * returns once the connector is accepting, so a refused port is a start-up
     * fault here and not a log line half a second later.
     *
     * @throws IllegalStateException when the port cannot be bound - an operator
     *         who named a port and silently got none would have a server no
     *         client can find
     */
    public void start() {
        QueuedThreadPool pool = new QueuedThreadPool();
        pool.setName(POOL_NAME);
        Server jetty = new Server(pool);
        ServerConnector accepting = new ServerConnector(jetty);
        InetSocketAddress address = config.address();
        accepting.setHost(address.getHostString());
        accepting.setPort(address.getPort());
        jetty.addConnector(accepting);
        ContextHandler context = new ContextHandler(ROOT_CONTEXT);
        WebSocketUpgradeHandler upgrade =
                WebSocketUpgradeHandler.from(jetty, context, ws::configure);
        upgrade.setHandler(http);
        context.setHandler(upgrade);
        jetty.setHandler(context);
        try {
            jetty.start();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "cannot listen on port " + config.getPort(), e);
        }
        server = jetty;
        connector = accepting;
        boundPort = accepting.getLocalPort();
        if (log.isInfoEnabled()) {
            log.info("net server: HTTP and WebSocket listening on port {}", getPort());
        }
    }

    /**
     * Stops taking new connections while the live ones go on working.
     *
     * <p>That order is the whole point: a session's teardown TALKS to its client
     * (spec 4.1 - the locks go back and the goodbye is sent on the socket), so
     * the sockets must still be there while it runs.  Closing the accepting
     * channel is what stops a client from connecting into a server that is
     * already going down.
     */
    public void stopAccepting() {
        ServerConnector accepting = connector;
        if (accepting != null) {
            accepting.close();
        }
    }

    /** Stops the server for good.  Idempotent, and never throws: it runs from
     *  the shutdown hook, where there is nothing left to tell. */
    public void stop() {
        Server running = server;
        server = null;
        connector = null;
        if (running == null) {
            return;
        }
        try {
            running.stop();
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("net server: the front did not stop cleanly: {}", e.toString());
            }
        }
    }

    /** The port actually bound - the configured one, or the one the OS picked
     *  when the configuration asked for an ephemeral port ({@code --port 0}).
     *  It keeps answering that number while the server comes down, which is
     *  what the beacon, the {@code /servers} self row and {@code /info} need
     *  (see {@link #boundPort}). */
    public int getPort() {
        int bound = boundPort;
        return bound > 0 ? bound : config.getPort();
    }
}
