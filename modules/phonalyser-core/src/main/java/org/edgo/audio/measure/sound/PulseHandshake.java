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

package org.edgo.audio.measure.sound;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import lombok.Getter;
import lombok.Setter;

/**
 * The exchange between the two threads of one delay measurement, once per
 * edge of each pulse: the capture thread asks the playback thread for the
 * edge; the playback writes it and answers at once.  Each edge keeps its two
 * stamps ({@code System.nanoTime()}): the playback's departure of the edge
 * from the line's queue, and the capture's arrival of the edge.  Either side
 * may abandon the exchange; every wait on it then ends.
 */
public final class PulseHandshake {

    /** One edge's exchange and stamps. */
    public final class Edge {

        /** Whether this edge rises, from {@code -level} to {@code +level}, or falls. */
        @Getter
        private final boolean rising;
        private final CountDownLatch requestLatch = new CountDownLatch(1);
        private final CountDownLatch answerLatch  = new CountDownLatch(1);
        private volatile boolean answered;
        /** When the edge left the line's queue for the device; 0 until it has. */
        @Getter
        @Setter
        private volatile long departureNanos;
        /** When the edge reached the capture line; -1 until it has. */
        @Getter
        @Setter
        private volatile long arrivalNanos = -1L;

        private Edge(boolean rising) {
            this.rising = rising;
        }

        /** Capture: the level before the edge has settled; the playback may
         *  write the edge. */
        public void request() {
            requestLatch.countDown();
        }

        /** Playback: whether the capture has asked for the edge - polled from
         *  the writer's loop. */
        public boolean isRequested() {
            return requestLatch.getCount() == 0 && !abandoned;
        }

        /** Playback: the edge has been written to the line. */
        public void answer() {
            answered = true;
            answerLatch.countDown();
        }

        /** Capture: waits for the playback's answer.
         *  @return {@code false} when the answer did not come within
         *          {@code timeoutMs}, or the exchange was abandoned */
        public boolean awaitAnswer(long timeoutMs) throws InterruptedException {
            return answerLatch.await(timeoutMs, TimeUnit.MILLISECONDS) && answered;
        }

        /** This edge's delay: its arrival at the capture against its
         *  departure from the line.  {@code NaN} while a stamp is missing. */
        public double delayMs() {
            if (arrivalNanos < 0L || departureNanos == 0L) {
                return Double.NaN;
            }
            return (arrivalNanos - departureNanos) / 1e6;
        }

        private void release() {
            requestLatch.countDown();
            answerLatch.countDown();
        }
    }

    /** The edges in the order they are written: each pulse's rise, then its fall. */
    @Getter
    private final List<Edge> edges;
    /** Whether either side gave up on the exchange. */
    @Getter
    private volatile boolean abandoned;

    /** @param pulses how many pulses the measurement sends - two edges each */
    public PulseHandshake(int pulses) {
        List<Edge> list = new ArrayList<>(2 * pulses);
        for (int i = 0; i < pulses; i++) {
            list.add(new Edge(true));
            list.add(new Edge(false));
        }
        edges = Collections.unmodifiableList(list);
    }

    /** Either side gave up: every wait on this exchange ends, and no edge is
     *  requested or written through it any more. */
    public void abandon() {
        abandoned = true;
        for (Edge edge : edges) {
            edge.release();
        }
    }
}
