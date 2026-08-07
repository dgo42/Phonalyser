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

package org.edgo.audio.measure.gui.backend.qa40x;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

// The transport contract lives with the QA40x driver, one module down; this stub
// used to sit beside it.
import org.edgo.audio.measure.sound.qa40x.Qa40xTransport;

import lombok.Setter;

/**
 * Recording {@link Qa40xTransport} test double.  It logs every register write in
 * order, hands back queued register-read replies, and holds audio transfers until
 * a test completes them synchronously - so the engine's async discipline can be
 * driven step by step on the test thread.
 */
final class FakeTransport implements Qa40xTransport {

    /** One recorded register write. */
    record RegWrite(int reg, int value) {}

    /** Register writes in submission order. */
    final List<RegWrite> registerWrites = new ArrayList<>();
    /** Combined ordered op log for sequencing assertions (writes + reads + cancelAll + close). */
    final List<String> ops = new ArrayList<>();
    /** FIFO replies returned by {@link #registerRead} (e.g. cal words). */
    final Deque<Integer> readReplies = new ArrayDeque<>();
    /** Snapshots (copied to {@code length}) of every submitted audio write, in order. */
    final List<byte[]> submittedWrites = new ArrayList<>();
    /** Original write buffers awaiting completion. */
    final Deque<byte[]> pendingWriteBuffers = new ArrayDeque<>();
    /** Read buffers awaiting completion. */
    final Deque<byte[]> pendingReadBuffers = new ArrayDeque<>();

    int cancelAllCount;

    @Setter
    private TransferListener listener;

    @Override
    public void registerWrite(int reg, int value) {
        registerWrites.add(new RegWrite(reg, value));
        ops.add("reg=" + reg + ":" + value);
    }

    @Override
    public int registerRead(int reg) {
        ops.add("read=" + reg);
        Integer reply = readReplies.poll();
        return reply == null ? 0 : reply;
    }

    @Override
    public void submitAudioWrite(byte[] data, int length) {
        submittedWrites.add(Arrays.copyOf(data, length));
        pendingWriteBuffers.add(data);
    }

    @Override
    public void submitAudioRead(byte[] buffer) {
        pendingReadBuffers.add(buffer);
    }

    @Override
    public void cancelAll() {
        cancelAllCount++;
        ops.add("cancelAll");
    }

    @Override
    public void close() {
        ops.add("close");
    }

    /** Completes the oldest pending read, filling {@code payload} into its buffer. */
    void completeNextRead(byte[] payload) {
        byte[] buffer = pendingReadBuffers.poll();
        int n = Math.min(payload.length, buffer.length);
        System.arraycopy(payload, 0, buffer, 0, n);
        listener.readCompleted(buffer, n);
    }

    /** Completes the oldest pending read with its full buffer length (contents as-is). */
    void completeNextRead() {
        byte[] buffer = pendingReadBuffers.poll();
        listener.readCompleted(buffer, buffer.length);
    }

    /** Completes the oldest pending write. */
    void completeNextWrite() {
        byte[] buffer = pendingWriteBuffers.poll();
        listener.writeCompleted(buffer, buffer.length);
    }

    /** Total bytes across all submitted audio writes. */
    int totalWriteBytesSubmitted() {
        int total = 0;
        for (byte[] chunk : submittedWrites) {
            total += chunk.length;
        }
        return total;
    }
}
