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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import lombok.extern.log4j.Log4j2;

/**
 * The production {@link SessionWorker}: one thread, owned by one connection, for
 * that connection's whole life.
 *
 * <p>A thread per connection is affordable - a bench serves a handful of clients
 * - and it is the only shape that lets a request block on hardware without
 * anyone else noticing.  The thread is a daemon and named after the peer, so a
 * stuck device call is one line in a thread dump with the client's address on
 * it.
 *
 * <p>The executor is this object's own state, not an injected collaborator:
 * owning exactly one single-thread executor IS the class.
 */
@Log4j2
public final class ThreadSessionWorker implements SessionWorker {

    private static final String THREAD_PREFIX = "net-session-";

    /** The peer this lane belongs to - what a thread dump and a teardown that
     *  ran out of time are named after. */
    private final String name;
    private final ExecutorService executor;

    public ThreadSessionWorker(String name) {
        this.name = name;
        this.executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, THREAD_PREFIX + name);
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void submit(Runnable task) {
        try {
            executor.execute(() -> run(task));
        } catch (RejectedExecutionException e) {
            // The session closed between the caller's check and this submit.
            // There is nobody left to answer, and no lock left to release.
        }
    }

    /** One task, guarded - the executor boundary.  What runs on this lane is a
     *  request handler, an audio drain and the teardown, and every one of them
     *  ends in a device call: JNA raises an Error out of a native invocation on
     *  a device that was unplugged, and an Error let out here is a thread dying
     *  with nothing but the JVM's own stack dump to say the connection's lane
     *  has stopped.  The thread survives, so what was queued behind the failure
     *  - the teardown, most of all - still runs. */
    private void run(Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            if (log.isErrorEnabled()) {
                log.error("net session {}: a task on the session lane failed", name, t);
            }
        }
    }

    @Override
    public void shutdown() {
        executor.shutdown();
    }

    @Override
    public void awaitShutdown(long timeoutMs) {
        try {
            if (!executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
                    && log.isWarnEnabled()) {
                log.warn("net session {}: the teardown was still running after {} ms",
                        name, timeoutMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
