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
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one property of the real session thread a fake cannot stand in for: that
 * the server's own shutdown can WAIT for the teardown it started.
 *
 * <p>Spec 4.1 names the server stopping as one of the ends the teardown is
 * reached from, and it ends with the DAC lines closed and the QA40x parked.
 * These threads are daemons - a {@code stop()} that returned while the task was
 * still queued would let the JVM exit and kill the teardown halfway through,
 * which on a bench means an analyzer left at full input sensitivity.
 */
class ThreadSessionWorkerTest {

    private static final String PEER = "127.0.0.1:65000";
    /** Long enough that only a broken wait can miss it. */
    private static final long PATIENT_MS = 5_000;
    /** Short enough that the blocked task cannot possibly have finished. */
    private static final long IMPATIENT_MS = 50;

    @Test
    void awaitShutdownComesBackOnlyOnceTheTeardownHasRun() {
        ThreadSessionWorker worker = new ThreadSessionWorker(PEER);
        CountDownLatch admitted = new CountDownLatch(1);
        AtomicBoolean tornDown = new AtomicBoolean();
        worker.submit(() -> tornDown.set(await(admitted)));

        worker.shutdown();
        admitted.countDown();
        worker.awaitShutdown(PATIENT_MS);

        assertTrue(tornDown.get(),
                "shutdown() only stops NEW work; the teardown already queued is what "
                        + "the server has to see finish before the JVM goes");
    }

    @Test
    void aTeardownThatWillNotFinishStillLetsTheServerGo() {
        ThreadSessionWorker worker = new ThreadSessionWorker(PEER);
        CountDownLatch wedged = new CountDownLatch(1);
        AtomicBoolean tornDown = new AtomicBoolean();
        try {
            worker.submit(() -> tornDown.set(await(wedged)));
            worker.shutdown();

            worker.awaitShutdown(IMPATIENT_MS);

            assertFalse(tornDown.get(),
                    "a device that stopped answering must not hold the shutdown open "
                            + "for ever - the wait is bounded, and the process goes on");
        } finally {
            wedged.countDown();
        }
    }

    /**
     * A task that faults NATIVELY must not take the lane down with it.
     *
     * <p>Everything this thread runs ends in a device call - a request handler, an
     * audio drain, the teardown - and JNA raises an Error out of an invocation on
     * hardware that was pulled.  What is queued BEHIND that failure is the
     * teardown, which is the only thing that gives the connection's locks and
     * lines back.
     *
     * <p>The single-thread executor already survives this by replacing the thread
     * it lost, so this pins the LANE's promise rather than the guard inside it
     * (that guard's own job is to name the connection in the log instead of
     * leaving a bare stack dump on stderr).  The promise is worth pinning: it is
     * what any later move to a shared or fixed pool would have to keep.
     */
    @Test
    void aTaskThatFaultsNativelyLeavesTheLaneServingTheNextOne() {
        ThreadSessionWorker worker = new ThreadSessionWorker(PEER);
        CountDownLatch afterTheFault = new CountDownLatch(1);

        worker.submit(() -> {
            throw new Error("Invalid memory access");
        });
        worker.submit(afterTheFault::countDown);
        worker.shutdown();
        worker.awaitShutdown(PATIENT_MS);

        assertEquals(0, afterTheFault.getCount(),
                "the teardown queued behind a failed request still runs - anything "
                        + "else strands every device this connection holds");
    }

    /** Waits for {@code latch}, reporting whether it really was released. */
    private boolean await(CountDownLatch latch) {
        try {
            latch.await();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
