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

package org.edgo.audio.measure.sound.qa40x;

import lombok.extern.log4j.Log4j2;

import org.edgo.audio.measure.sound.AbstractPcmCapture;
import org.edgo.audio.measure.sound.SpscByteArrayRing;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * QA402/QA403 stereo capture — a thin AudioCapture client that {@code attach}es /
 * {@code detach}es the capture lane on the manager's one duplex engine (doc §10).
 * All the device-agnostic machinery (the little-endian int32 {@link #readSample}
 * decode, the captured AudioFormat, the listener fan-out in {@code dispatch}) is
 * inherited from {@link AbstractPcmCapture}; the wire is already interleaved
 * little-endian int32 stereo, so the ADC bytes pass straight through.
 *
 * <p>The engine reports each ADC read on its USB event thread — the thread that
 * also paces every transfer, so it must never wait on a consumer.  {@link #onAudio}
 * therefore only copies the batch into a pooled buffer and hands it to a consume
 * thread over an {@link SpscByteArrayRing} — the same decoupling
 * {@code WasapiRecorder} / {@code WdmksRecorder} use.  Listener work (per-sample
 * decode, ring append) can then never delay the next read submission, however
 * expensive a consumer gets; a full queue drops the batch (counted, rate-limited
 * log) rather than stalling the USB thread.
 *
 * <p>The right input is NOT inverted and channels are NOT swapped — those are
 * QA401-only quirks (doc §9 item 6).
 */
@Log4j2
public final class Qa40xRecorder extends AbstractPcmCapture {

    /** Effective capture depth: the QA40x wire carries int32 frames, but only the
     *  24 MSBs hold signal (the low byte is zero padding, doc §5).  We present the
     *  device as a true 24-bit capture — dropping the pad byte per sample in
     *  {@link #onAudio} — so the advertised depth equals the delivered sample width.
     *  The shared capture path ({@code SharedCapture}) derives its frame stride and
     *  full-scale midpoint from this depth, so advertised-vs-delivered MUST match or
     *  every sample is read at the wrong offset (broadband noise + amplitude blow-up). */
    private static final int QA40X_BIT_DEPTH = 24;
    /** Wire container width per sample (int32) before the pad byte is dropped. */
    private static final int WIRE_SAMPLE_BYTES = 4;
    /** Delivered width per sample after dropping the little-endian pad (low) byte. */
    private static final int PACKED_SAMPLE_BYTES = 3;
    /** SPSC ring capacity (batches) — mirrors the WASAPI recorder's queue depth. */
    private static final int QUEUE_CAPACITY = 64;
    /** Consume-thread park while the queue is empty — batches are ~10 ms apart. */
    private static final long EMPTY_PARK_NANOS = 500_000L;
    private static final long LOG_INTERVAL_NANOS = 1_000_000_000L;

    private final Qa40xDeviceManager manager;

    /** SPSC ring of filled batches — USB event thread → consume thread. */
    private final SpscByteArrayRing queue      = new SpscByteArrayRing(QUEUE_CAPACITY);
    /** Recycled buffer pool — consume thread → USB event thread (roles reversed). */
    private final SpscByteArrayRing bufferPool = new SpscByteArrayRing(QUEUE_CAPACITY);
    /** USB-thread-only spare: holds a queue-rejected buffer for the next batch
     *  instead of offering it back to {@link #bufferPool} (which would make the
     *  USB thread a second producer on that ring). */
    private byte[] captureSpare;
    /** Batches dropped on a full queue since the last consume-side log. */
    private final AtomicLong droppedBatchesSinceLog = new AtomicLong();

    private Qa40xDuplexEngine engine;
    private Thread consumerThread;

    Qa40xRecorder(Qa40xDeviceManager manager, int sampleRate) {
        super(sampleRate, QA40X_BIT_DEPTH, 2);
        this.manager = manager;
    }

    @Override
    public void open() {
        engine = manager.acquireEngine(sampleRate);
        log.info("QA40x recorder opened : {}", getFormat());
    }

    @Override
    public void startRecording() {
        if (engine == null) {
            throw new IllegalStateException("Call open() before startRecording()");
        }
        queue.clear();
        bufferPool.clear();
        captureSpare = null;
        droppedBatchesSinceLog.set(0);
        recording.set(true);
        consumerThread = new Thread(this::consumeLoop, "qa40x-consume");
        consumerThread.setDaemon(true);
        consumerThread.setPriority(Thread.NORM_PRIORITY + 1);
        consumerThread.start();
        engine.attachCapture(this::onAudio);      // starts the duplex stream if idle
        log.info("QA40x recording started.");
    }

    @Override
    public void stopRecording() throws InterruptedException {
        recording.set(false);
        if (engine != null) {
            engine.detachCapture();               // stops the stream only if the last client detaches
        }
        Thread t = consumerThread;
        if (t != null) {
            t.join(1_000L);                       // drains the queue, then exits on the flag
            consumerThread = null;
        }
        log.info("QA40x recording stopped.");
    }

    @Override
    public void close() {
        if (recording.get()) {
            try {
                stopRecording();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Engine capture-lane sink, on the USB event thread: copy the batch into a
     *  pooled buffer and enqueue it for the consume thread — never dispatch here
     *  (the same thread paces every transfer, doc §5).  A full queue drops the
     *  batch and keeps the buffer as the thread-local spare. */
    private void onAudio(byte[] buffer, int length) {
        if (!recording.get()) {
            return;
        }
        // Repack int32 wire samples to 24-bit: keep the 3 high (little-endian)
        // bytes, drop the zero low/pad byte, so the delivered width matches the
        // advertised 24-bit depth the shared capture path strides by.
        int samples   = length / WIRE_SAMPLE_BYTES;
        int outLength = samples * PACKED_SAMPLE_BYTES;
        byte[] copy = captureSpare;
        captureSpare = null;
        if (copy == null) copy = bufferPool.aquire();
        if (copy == null || copy.length != outLength) copy = new byte[outLength];
        for (int i = 0, o = 0; i < length; i += WIRE_SAMPLE_BYTES, o += PACKED_SAMPLE_BYTES) {
            copy[o]     = buffer[i + 1];          // little-endian: byte 0 is the zero pad
            copy[o + 1] = buffer[i + 2];
            copy[o + 2] = buffer[i + 3];
        }
        if (!queue.release(copy)) {
            droppedBatchesSinceLog.incrementAndGet();
            captureSpare = copy;                  // keep thread-local — never offer to the pool from here
        }
    }

    /** Consume-thread loop: drains {@link #queue}, runs the listener dispatch,
     *  recycles buffers into {@link #bufferPool}, and emits the rate-limited drop
     *  diagnostics the USB thread only counts. */
    private void consumeLoop() {
        long lastLogNanos = 0L;
        while (recording.get() || !queue.isEmpty()) {
            long now = System.nanoTime();
            if (now - lastLogNanos >= LOG_INTERVAL_NANOS) {
                long dropped = droppedBatchesSinceLog.getAndSet(0);
                if (dropped > 0 && log.isWarnEnabled()) {
                    log.warn("QA40x capture: {} batch(es) dropped on a full consume queue", dropped);
                }
                lastLogNanos = now;
            }
            byte[] buffer = queue.aquire();
            if (buffer == null) {
                // Ring empty — park briefly instead of busy-spinning; batches
                // arrive every ~10 ms, 500 µs is plenty of resolution.
                LockSupport.parkNanos(EMPTY_PARK_NANOS);
                continue;
            }
            try {
                dispatch(buffer, buffer.length);
            } finally {
                bufferPool.release(buffer);
            }
        }
    }
}
