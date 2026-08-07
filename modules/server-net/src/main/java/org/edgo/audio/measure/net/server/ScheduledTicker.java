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

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * The production {@link Ticker}: a fixed-rate task on the server's shared
 * scheduler.  One instance per ticking owner (one per session), all of them
 * sharing the single executor the server creates - the callbacks are a JSON
 * ping each, so they cost a thread between them, not a thread each.
 *
 * <p><b>Nothing escapes a tick.</b>  {@code scheduleAtFixedRate} cancels a
 * repeating task the first time its body throws - permanently, and without a
 * word: the future carries the throwable and nobody ever calls {@code get()} on
 * it.  A keepalive that stopped that way leaves its session pinging nothing and
 * never declared dead, which is a bench holding devices for a client that is
 * gone.  So the body is guarded here, at the boundary, and the next period
 * happens whatever this one did.
 */
@Log4j2
@RequiredArgsConstructor
public final class ScheduledTicker implements Ticker {

    private final ScheduledExecutorService scheduler;

    private ScheduledFuture<?> scheduled;

    @Override
    public synchronized void start(long periodMs, Runnable task) {
        stop();
        scheduled = scheduler.scheduleAtFixedRate(() -> tick(task), periodMs, periodMs,
                TimeUnit.MILLISECONDS);
    }

    /** One period, guarded - see the class comment on why a repeating task may
     *  never let anything out. */
    private void tick(Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            if (log.isErrorEnabled()) {
                log.error("net server: a scheduled tick failed - the task goes on", t);
            }
        }
    }

    @Override
    public synchronized void stop() {
        if (scheduled != null) {
            scheduled.cancel(false);
            scheduled = null;
        }
    }
}
