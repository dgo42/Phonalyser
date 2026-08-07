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

package org.edgo.audio.measure.sound.qa40x;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

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

    /** When set, every register transfer fails the way a handle to a device that
     *  has been unplugged fails - {@code LIBUSB_ERROR_IO} out of the bulk
     *  transfer, in both directions.  What a real analyzer does after a
     *  detach / re-attach, and what a manager holding that handle has to
     *  notice. */
    @Setter
    private boolean failTransfers;

    /** How many of the next register transfers fault the way a NATIVE call
     *  faults - see {@link #failNextWithNativeError(int)}. */
    private int nativeFaults;

    @Setter
    private TransferListener listener;

    /**
     * Makes the next {@code count} register transfers raise an {@link Error},
     * which is what JNA does when the invocation ITSELF faults: the analyzer's
     * cable was pulled and the native call went into memory nobody owns any more
     * ("Invalid memory access" out of {@code libusb_bulk_transfer}).  That is the
     * failure every {@code catch (RuntimeException)} in the stack used to let
     * straight through - quite unlike {@link #setFailTransfers},
     * which is a device that is still there and answered an error code.
     */
    void failNextWithNativeError(int count) {
        nativeFaults = count;
    }

    @Override
    public void registerWrite(int reg, int value) {
        failIfDead("registerWrite", reg);
        registerWrites.add(new RegWrite(reg, value));
        ops.add("reg=" + reg + ":" + value);
    }

    @Override
    public int registerRead(int reg) {
        failIfDead("registerRead", reg);
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

    /** The refusal a dead handle gives, worded as the binding words it - as an
     *  error code, or as the native fault of {@link #failWithNativeError}. */
    private void failIfDead(String what, int reg) {
        String detail = what + "(0x" + Integer.toHexString(reg) + ") failed: ";
        if (nativeFaults > 0) {
            nativeFaults--;
            throw new Error(detail + "Invalid memory access");
        }
        if (failTransfers) {
            throw new IllegalStateException(detail + "LIBUSB_ERROR_IO");
        }
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
