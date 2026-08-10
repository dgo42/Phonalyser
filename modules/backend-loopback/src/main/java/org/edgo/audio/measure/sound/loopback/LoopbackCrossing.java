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

package org.edgo.audio.measure.sound.loopback;

import org.edgo.audio.measure.sound.SpscByteArrayRing;

/**
 * Where the playback lane hands its quantised PCM to the capture lane.  One
 * instance per manager, so the two lanes opened from the same manager meet -
 * a playback with no capture (or the reverse) simply finds the other end idle.
 *
 * <p>The queue is a single-producer / single-consumer ring: the playback render
 * thread is the only producer and the capture consume thread the only consumer,
 * which is exactly the contract {@link SpscByteArrayRing} states.  It is
 * non-blocking on both ends, so pacing is the lanes' business, not the ring's -
 * a producer that finds it full has simply run ahead of the capture clock and
 * waits out its own block rather than spinning.
 */
final class LoopbackCrossing {

    /** Ring slots: blocks in flight between the lanes.  Power of two, as the
     *  ring requires, and deliberately fixed - the capture clock, not the queue
     *  depth, decides how fast blocks move. */
    private static final int RING_SLOTS = 32;

    private final SpscByteArrayRing ring = new SpscByteArrayRing(RING_SLOTS);

    /** Offers one block of interleaved-stereo PCM to the capture lane; false
     *  when the ring is full (the caller keeps the buffer - it must not be
     *  recycled while the consumer may still be reading an earlier one). */
    boolean offer(byte[] block) {
        return ring.release(block);
    }

    /** The next block, or null when the playback lane has not produced one -
     *  the capture lane then delivers its own dithered silence for that slot. */
    byte[] poll() {
        return ring.aquire();
    }

    /** Drops whatever is in flight, so a capture that starts after an earlier
     *  one cannot be handed the previous run's tail. */
    void clear() {
        while (ring.aquire() != null) {
            // discard
        }
    }
}
