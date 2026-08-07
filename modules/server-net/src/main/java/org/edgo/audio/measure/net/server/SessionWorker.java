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

/**
 * One connection's request thread, injected - the third of the session's narrow
 * seams, beside {@link SessionChannel} (where its answers go) and {@link Ticker}
 * (when its keepalive fires).
 *
 * <p>A request may open a device, and opening a device takes as long as the
 * hardware takes.  That work must therefore run on a thread that belongs to this
 * connection ALONE: the transport hands several connections to one reader
 * thread, so working inline there stalls strangers, and holding a session lock
 * across it would stall this session's own keepalive and teardown.  One thread
 * per connection also keeps the requests of that connection in the order the
 * client sent them, which is the property the protocol assumes throughout.
 *
 * <p>Injected rather than created, so a test runs the same code inline and
 * asserts on the answer the moment it drove the message in.
 */
public interface SessionWorker {

    /** Runs {@code task} on the session's own thread, after everything already
     *  queued for it.  A task submitted after {@link #shutdown()} is dropped -
     *  the connection it belonged to is gone. */
    void submit(Runnable task);

    /** Stops accepting work.  Idempotent, and never waits for a task in
     *  flight - teardown paths call it blindly, from any thread. */
    void shutdown();

    /** Waits up to {@code timeoutMs} for the work submitted before
     *  {@link #shutdown()} to finish - the session's teardown, when the server
     *  itself is stopping and the JVM is about to take these threads with it.
     *  Gives up when the time is out: a device that stopped answering must not
     *  hold a shutdown open for ever. */
    void awaitShutdown(long timeoutMs);
}
