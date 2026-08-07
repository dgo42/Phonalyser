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

/**
 * The passage of time, injected.  The connection's keepalive is a periodic task
 * and a test must be able to advance it instantly and deterministically - so
 * {@link NetConnection} never reads a wall clock and never sleeps: it is handed
 * a ticker and asks it to call back.
 *
 * <p>One ticker drives one task and belongs to whoever owns that task, so
 * {@link #stop()} needs no handle: closing the owner stops its ticker.
 *
 * <p>The headless server declares the same seam in its own package.  The two are
 * deliberately NOT shared: the server module must not appear on the client's
 * class path (nor the reverse), and the shared artefact between them is the wire
 * format alone - a two-method interface is the cheapest possible price for that
 * boundary.
 */
public interface Ticker {

    /** Starts calling {@code task} every {@code periodMs}, first call one
     *  period from now.  Calling it twice on the same ticker replaces the
     *  previous task. */
    void start(long periodMs, Runnable task);

    /** Stops the callbacks.  Idempotent - teardown paths call it blindly. */
    void stop();
}
