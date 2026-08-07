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

import java.util.ArrayList;
import java.util.List;

import lombok.Getter;

/**
 * The session's request thread, hand-cranked: the task runs inline, so a test
 * asserts on the answer the moment it drove the message in.  It keeps the
 * production ORDER (one request at a time, in arrival order) without the
 * production thread - the same trade {@link FakeTicker} makes for time.
 *
 * <p>{@link #hold()} stops it running them, which is how the audio lane's
 * saturation is reached on purpose: the capture keeps delivering batches while
 * nothing drains them, exactly as it does when the client stops reading.
 */
final class FakeWorker implements SessionWorker {

    @Getter
    private boolean shutdown;

    private final List<Runnable> held = new ArrayList<>();
    private boolean holding;

    @Override
    public void submit(Runnable task) {
        if (shutdown) {
            return;
        }
        if (holding) {
            held.add(task);
            return;
        }
        task.run();
    }

    /** Queues what is submitted from now on instead of running it - a consumer
     *  that has stopped keeping up. */
    void hold() {
        holding = true;
    }

    /** Runs everything held, in order, and goes back to running inline. */
    void release() {
        holding = false;
        List<Runnable> pending = new ArrayList<>(held);
        held.clear();
        for (Runnable task : pending) {
            task.run();
        }
    }

    @Override
    public void shutdown() {
        shutdown = true;
    }

    /** Nothing to wait for: what was submitted ran inline as it arrived (or is
     *  waiting for {@link #release()}, which is the test's own doing). */
    @Override
    public void awaitShutdown(long timeoutMs) {
        // Intentionally empty - see the javadoc.
    }
}
