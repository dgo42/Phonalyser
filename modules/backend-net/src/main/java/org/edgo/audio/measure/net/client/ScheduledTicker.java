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

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import lombok.RequiredArgsConstructor;

/**
 * The production {@link Ticker}: a fixed-rate task on a scheduler the caller
 * owns.  One instance per ticking owner (one per connection); the scheduler is
 * supplied from outside because a keepalive callback is a short JSON write and
 * several connections can share a single thread between them.
 */
@RequiredArgsConstructor
public final class ScheduledTicker implements Ticker {

    private final ScheduledExecutorService scheduler;

    private ScheduledFuture<?> scheduled;

    @Override
    public synchronized void start(long periodMs, Runnable task) {
        stop();
        scheduled = scheduler.scheduleAtFixedRate(task, periodMs, periodMs,
                TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (scheduled != null) {
            scheduled.cancel(false);
            scheduled = null;
        }
    }
}
