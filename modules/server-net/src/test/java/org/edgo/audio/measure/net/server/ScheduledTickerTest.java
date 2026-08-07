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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one property of the real ticker a fake cannot stand in for: that a tick
 * which throws does not END the repeating task.
 *
 * <p>{@code scheduleAtFixedRate} cancels a task the first time its body throws -
 * permanently, and in total silence, because the throwable goes into a future
 * nobody ever calls {@code get()} on.  Every periodic job on this server is a
 * session's keepalive or the hot-plug look, so a task cancelled that way is a
 * connection that is never declared dead (its devices stay held for a client
 * that has gone) or a bench whose hot-plug is never noticed again.
 */
class ScheduledTickerTest {

    private static final String THREAD_NAME = "net-keepalive-test";
    /** Fast, so the test is quick; the assertion waits on a latch, not a clock. */
    private static final long PERIOD_MS = 5;
    /** Enough repeats that only a task which really kept running reaches them. */
    private static final int TICKS_WANTED = 3;
    /** Long enough that only a cancelled task can miss the latch. */
    private static final long PATIENT_MS = 5_000;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, THREAD_NAME);
                thread.setDaemon(true);
                return thread;
            });

    @AfterEach
    void stopTheScheduler() {
        scheduler.shutdownNow();
    }

    @Test
    void aTickThatFaultsNativelyDoesNotCancelTheRepeatingTask() throws InterruptedException {
        CountDownLatch ticks = new CountDownLatch(TICKS_WANTED);
        Ticker ticker = new ScheduledTicker(scheduler);

        ticker.start(PERIOD_MS, () -> {
            ticks.countDown();
            // What a keepalive that touched a device it can no longer reach gets
            // back: not an exception - an Error, out of the faulted native call.
            throw new Error("Invalid memory access");
        });

        assertTrue(ticks.await(PATIENT_MS, TimeUnit.MILLISECONDS),
                "the next period happens whatever this one did - a repeating task "
                        + "that let a throwable out would have been cancelled by the "
                        + "scheduler on the very first tick, without a word");
        ticker.stop();
    }

    @Test
    void stopEndsTheTicks() throws InterruptedException {
        CountDownLatch first = new CountDownLatch(1);
        Ticker ticker = new ScheduledTicker(scheduler);
        ticker.start(PERIOD_MS, first::countDown);
        assertTrue(first.await(PATIENT_MS, TimeUnit.MILLISECONDS));

        ticker.stop();

        CountDownLatch afterStop = new CountDownLatch(1);
        ticker.start(PERIOD_MS, afterStop::countDown);
        assertTrue(afterStop.await(PATIENT_MS, TimeUnit.MILLISECONDS),
                "and a stopped ticker starts again - every teardown path calls "
                        + "stop() blindly");
        ticker.stop();
    }
}
