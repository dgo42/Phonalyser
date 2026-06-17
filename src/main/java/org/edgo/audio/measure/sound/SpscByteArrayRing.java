/*
 * Phonalyser — precision audio measurement workbench.
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

/**
 * Lock-free single-producer single-consumer ring buffer of {@code byte[]}
 * references.  Replaces {@code LinkedBlockingQueue} / {@code
 * ConcurrentLinkedQueue} so an audio capture thread and its consume thread
 * exchange chunks without any per-call {@code Node} allocation.  Used in
 * pairs by {@link WdmksRecorder} and {@link WasapiRecorder}: one ring carries
 * filled packets capture→consumer, a second carries recycled buffers
 * consumer→capture (the producer/consumer roles simply reversed).
 *
 * <p>Capacity must be a power of two — {@link #mask} reduces the
 * modulo-arithmetic to a bitwise AND on the hot path.  Memory ordering
 * is provided by the {@code volatile} {@link #writePos} / {@link
 * #readPos} cursors: a release-store of {@code writePos} happens-before
 * the matching acquire-load by the consumer, so a slot written by the
 * producer is visible to the consumer when it reads at that index.
 *
 * <p><b>Strictly one producer and one consumer thread per ring</b> — a second
 * producer can silently lose a slot (two offers reading the same
 * {@code writePos}).  A producer-side thread that has a buffer rejected by a
 * full ring must keep it in its own thread-local spare rather than offering
 * it back to the pool ring it consumes from.
 */
final class SpscByteArrayRing {

    private final byte[][] slots;
    private final int mask;
    /** Producer-only writes; consumer reads via volatile semantics. */
    private volatile long writePos;
    /** Consumer-only writes; producer reads via volatile semantics. */
    private volatile long readPos;

    SpscByteArrayRing(int capacity) {
        if (Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("capacity must be a power of two: " + capacity);
        }
        this.slots = new byte[capacity][];
        this.mask  = capacity - 1;
    }

    /** Producer call: returns {@code true} if accepted, {@code false} if full. */
    boolean release(byte[] item) {
        long w = writePos;
        if (w - readPos >= slots.length) return false;
        slots[(int) (w & mask)] = item;
        writePos = w + 1;
        return true;
    }

    /** Consumer call: returns the next item or {@code null} if empty. */
    byte[] aquire() {
        long r = readPos;
        if (r >= writePos) return null;
        int idx = (int) (r & mask);
        byte[] item = slots[idx];
        slots[idx] = null;       // release reference for GC
        readPos = r + 1;
        return item;
    }

    boolean isEmpty() {
        return readPos >= writePos;
    }

    /** Resets both cursors and clears slot references.  Call only while neither thread is using the ring. */
    void clear() {
        for (int i = 0; i < slots.length; i++) slots[i] = null;
        writePos = 0;
        readPos  = 0;
    }
}
