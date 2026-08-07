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

import lombok.Getter;

/**
 * The connection's clock, hand-cranked: {@link #advance(int)} moves it exactly as
 * many keepalive periods as the test wants, in no time at all.  A sleeping test
 * would be slow AND flaky; this one is neither, and it proves the connection
 * reads no wall clock of its own.
 *
 * <p>A ticker that is never advanced is just as useful: the loopback runs hand
 * one out so the client's own keepalive stays silent while the SERVER's pings -
 * which arrive on the reader thread and are answered there - are the only
 * heartbeat in the test.
 *
 * <p>The fields are volatile because the connection starts its ticker on
 * whichever thread opened it and may stop it from the reader thread, while the
 * test advances it from its own.
 */
final class FakeTicker implements Ticker {

    @Getter
    private volatile long periodMs;
    @Getter
    private volatile boolean stopped;

    private volatile Runnable task;

    @Override
    public void start(long periodMs, Runnable task) {
        this.periodMs = periodMs;
        this.task = task;
        this.stopped = false;
    }

    @Override
    public void stop() {
        stopped = true;
    }

    /** Fires {@code periods} keepalive periods; a stopped ticker fires none,
     *  exactly like the real scheduler after a cancel. */
    void advance(int periods) {
        for (int i = 0; i < periods && !stopped; i++) {
            task.run();
        }
    }
}
