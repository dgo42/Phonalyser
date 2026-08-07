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

import lombok.Getter;

/**
 * The injected clock, hand-cranked: {@link #advance(int)} moves the session
 * exactly as many keepalive periods as the test wants, in no time at all.  A
 * sleeping test would be slow AND flaky; this one is neither, and it proves the
 * session reads no wall clock of its own.
 *
 * <p>The fields are volatile because the loopback run starts a ticker on the
 * transport's accept thread (that is where a connection's session is built) and
 * advances it from the test thread.
 */
final class FakeTicker implements Ticker {

    @Getter
    private volatile long periodMs;
    @Getter
    private volatile boolean stopped;

    private volatile Runnable task;
    /** Armed by {@link #faultOnNextStop()} - see there. */
    private volatile boolean faultOnStop;

    @Override
    public void start(long periodMs, Runnable task) {
        this.periodMs = periodMs;
        this.task = task;
        this.stopped = false;
    }

    @Override
    public void stop() {
        stopped = true;
        if (faultOnStop) {
            faultOnStop = false;
            throw new Error("Invalid memory access");
        }
    }

    /**
     * Makes the NEXT {@link #stop()} raise an {@link Error}, the way a teardown
     * step that ends in a native device call fails when the hardware was pulled
     * (JNA raises one out of a faulted invocation).  One-shot, so the owner's
     * later stops behave normally.
     *
     * <p>It is the ticker rather than the line that carries this because a
     * generator's teardown stops its state ticker FIRST, before any of its
     * guarded steps - so this is the throw that reaches the caller of
     * {@code closeAll()} whole, which is exactly what the session's own teardown
     * has to survive.
     */
    void faultOnNextStop() {
        faultOnStop = true;
    }

    /** Fires {@code periods} keepalive periods; a stopped ticker fires none,
     *  exactly like the real scheduler after a cancel. */
    void advance(int periods) {
        for (int i = 0; i < periods && !stopped; i++) {
            task.run();
        }
    }
}
