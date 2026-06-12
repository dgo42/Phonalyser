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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

import javax.sound.sampled.AudioFormat;

import org.edgo.audio.measure.common.Closeables;
import org.edgo.audio.measure.common.StereoSample;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;

/**
 * Stereo PCM capture via PortAudio's WDM-KS host API, callback mode.
 * PortAudio invokes {@link #paCallback} on its realtime audio thread with
 * freshly captured frames; the callback copies the bytes into
 * {@link #queue} and returns immediately. A dedicated consumer thread
 * drains the queue and invokes the user-supplied listeners, so heavy
 * listener work never blocks the audio thread.
 */
@Log4j2
public class WdmksRecorder implements AudioCapture {

    private final WdmksDeviceManager.WdmksDeviceRef device;
    private final int sampleRate;
    private final int bitDepth;
    private final int sampleBytes;
    private int frameSize;
    @Getter
    private final AudioFormat format;

    private Pointer stream;                 // PaStream*
    private final AtomicBoolean recording = new AtomicBoolean(false);
    private Thread consumerThread;
    private StereoSample[] sampleBuf;
    @Setter
    private Consumer<StereoSample[]> sampleListener;
    @Setter
    private PcmBatchListener pcmBatchListener;
    @Setter
    private Consumer<byte[]> rawBytesListener;

    /**
     * Hand-off from the PA audio thread to the consume thread.  Lock-free
     * SPSC ring of {@code byte[]} references — replaces {@code
     * LinkedBlockingQueue} so {@code offer}/{@code poll} don't allocate a
     * {@code Node} per chunk.  Capacity 64 matches the prior queue limit.
     */
    private final SpscByteArrayRing queue       = new SpscByteArrayRing(64);
    /**
     * Recycled buffer pool drained by the PA audio thread, refilled by the
     * consume thread once a chunk has been processed.  Same SPSC ring (the
     * producer/consumer roles are simply reversed for this pool).
     */
    private final SpscByteArrayRing bufferPool  = new SpscByteArrayRing(64);
    private final AtomicLong        overflowCount = new AtomicLong();
    /** Frames dropped (queue full) since the last consumer-thread log. */
    private final AtomicLong        droppedFramesSinceLog = new AtomicLong();
    /** PortAudio status-flag counters since the last consumer log. */
    private final AtomicLong        paInputOverflowCount  = new AtomicLong();
    private final AtomicLong        paInputUnderflowCount = new AtomicLong();
    /** Audio-thread-only spare: keeps a queue-rejected buffer for the next
     *  callback instead of offering it back to {@link #bufferPool} — the
     *  pool ring is SPSC with the consume thread as its sole producer, and
     *  a second producer can silently lose a slot. */
    private byte[] callbackSpare;

    /**
     * Audio-thread callback. Pulls a same-sized scratch buffer from
     * {@link #bufferPool} (allocating only on cold start or when PortAudio
     * changes its block size mid-stream), copies the captured PCM into it
     * and hands it to the consumer thread via {@link #queue}. Reusing
     * buffers keeps the audio thread allocation-free in steady state, so
     * the GC can't pause it into a dropout.
     */
    private final PortAudio.PaStreamCallback paCallback = (input, output, frameCount, ti, sf, ud) -> {
        if (!recording.get()) return PortAudio.paComplete;
        long flags = (sf == null) ? 0L : sf.longValue();
        if ((flags & PortAudio.paInputOverflow)  != 0) paInputOverflowCount.incrementAndGet();
        if ((flags & PortAudio.paInputUnderflow) != 0) paInputUnderflowCount.incrementAndGet();
        int frames = frameCount.intValue();
        int bytes  = frames * frameSize;
        byte[] buf = callbackSpare;
        callbackSpare = null;
        if (buf == null) buf = bufferPool.poll();
        if (buf == null || buf.length != bytes) buf = new byte[bytes];
        input.read(0, buf, 0, bytes);
        if (!queue.offer(buf)) {
            overflowCount.incrementAndGet();
            droppedFramesSinceLog.addAndGet(frames);
            callbackSpare = buf;
        }
        return PortAudio.paContinue;
    };

    public WdmksRecorder(WdmksDeviceManager.WdmksDeviceRef device, int sampleRate, int bitDepth) {
        this.device      = device;
        this.sampleRate  = sampleRate;
        this.bitDepth    = bitDepth;
        this.sampleBytes = bitDepth / 8;
        this.frameSize   = sampleBytes * 2;
        this.format = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate, bitDepth, 2,
                frameSize, sampleRate, false);
    }

    @Override
    public void open() {
        PortAudio.Lib lib = PortAudio.lib();

        PortAudio.PaDeviceInfo info = lib.Pa_GetDeviceInfo(device.paDeviceIndex());
        // WDM-KS stalls after the priming callback if suggestedLatency is 0 or
        // framesPerBuffer fights the KS pin's preferred size.  Prefer the
        // device's own default-LOW latency so PortAudio asks the KS pin for
        // small chunks (~10–20 ms) — that drives the callback rate up to
        // 50–100 Hz so the scope cap/s isn't bound by 100–200 ms KS periods.
        // Fall back to defaultHighInputLatency (and finally 25 ms) if the
        // driver reports 0 for the low value.
        double suggestedLatency =
                (info != null && info.defaultLowInputLatency  > 0) ? info.defaultLowInputLatency
              : (info != null && info.defaultHighInputLatency > 0) ? info.defaultHighInputLatency
              : 0.025;

        PortAudio.PaStreamParameters in = new PortAudio.PaStreamParameters();
        in.device                    = device.paDeviceIndex();
        in.channelCount              = 2;
        in.sampleFormat              = PortAudio.paSampleFormatFor(bitDepth);
        in.suggestedLatency          = suggestedLatency;
        in.hostApiSpecificStreamInfo = null;
        in.write();

        PortAudio.check(lib.Pa_IsFormatSupported(in, null, sampleRate),
                "Pa_IsFormatSupported(input " + sampleRate + " Hz / " + bitDepth + " bit)");

        PointerByReference handle = new PointerByReference();
        int rc = lib.Pa_OpenStream(handle,
                in, null,
                sampleRate,
                PortAudio.paFramesPerBufferUnspecified,
                PortAudio.paClipOff,
                paCallback, null);
        PortAudio.check(rc, "Pa_OpenStream(input)");
        stream = handle.getValue();

        sampleBuf = new StereoSample[0];

        log.info("WDM-KS recorder opened : {}", device.name());
        log.info("Capture format         : {}", format);
        log.info("Suggested latency      : {} ms (host-picked buffer size)",
                Math.round(suggestedLatency * 1000));
    }

    @Override
    public void startRecording() {
        if (stream == null) throw new IllegalStateException("Call open() before startRecording()");
        recording.set(true);
        queue.clear();
        bufferPool.clear();
        overflowCount.set(0);
        consumerThread = new Thread(this::consumeLoop, "wdmks-consume");
        consumerThread.setDaemon(true);
        consumerThread.setPriority(Thread.NORM_PRIORITY + 1);
        consumerThread.start();
        PortAudio.check(PortAudio.lib().Pa_StartStream(stream), "Pa_StartStream(input)");
        log.info("WDM-KS recording started on: {}", device.name());
    }

    @Override
    public void stopRecording() throws InterruptedException {
        recording.set(false);
        if (stream != null) {
            try {
                PortAudio.lib().Pa_StopStream(stream);
            } catch (Throwable t) {
                log.warn("Pa_StopStream: {}", t.getMessage());
            }
        }
        if (consumerThread != null) consumerThread.join(2000);
        long drops = overflowCount.get();
        if (drops > 0) log.warn("WDM-KS capture queue overflowed {} times", drops);
        log.info("WDM-KS recording stopped.");
    }

    private void consumeLoop() {
        long lastOverflowLogNanos = 0L;
        final long OVERFLOW_LOG_INTERVAL_NANOS = 1_000_000_000L;
        while (recording.get() || !queue.isEmpty()) {
            long now = System.nanoTime();
            if (now - lastOverflowLogNanos >= OVERFLOW_LOG_INTERVAL_NANOS) {
                long dropped = droppedFramesSinceLog.getAndSet(0);
                long paOver  = paInputOverflowCount.getAndSet(0);
                long paUnder = paInputUnderflowCount.getAndSet(0);
                if (dropped > 0 || paOver > 0 || paUnder > 0) {
                    log.warn("WDM-KS gaps: queue-dropped={} frames (~{} ms), paInputOverflow={}, paInputUnderflow={}",
                            dropped, dropped * 1000L / Math.max(1, sampleRate), paOver, paUnder);
                }
                lastOverflowLogNanos = now;
            }
            byte[] buffer = queue.poll();
            if (buffer == null) {
                // Ring empty.  Park briefly so we don't busy-spin a core
                // while waiting for the next chunk (audio period is 10–20 ms;
                // 500 µs gives plenty of resolution without burning CPU).
                // Windows timer granularity may stretch this to ~1 ms, which
                // is still well below one audio period — the producer can
                // queue up to 63 chunks of headroom anyway.
                LockSupport.parkNanos(500_000L);
                continue;
            }
            try {
                if (rawBytesListener != null) rawBytesListener.accept(buffer);
                // Prefer the PCM-bytes listener — it skips the StereoSample[]
                // alloc/decode entirely (the consumer does its own decode
                // into reusable float buffers).  Only one of the two is set
                // in any real configuration; if both are, the PCM listener
                // wins (used by the GUI scope view).
                if (pcmBatchListener != null) {
                    pcmBatchListener.accept(buffer, buffer.length);
                } else if (sampleListener != null) {
                    int nFrames = buffer.length / frameSize;
                    if (sampleBuf.length != nFrames) {
                        sampleBuf = new StereoSample[nFrames];
                        for (int i = 0; i < nFrames; i++) sampleBuf[i] = new StereoSample();
                    }
                    for (int f = 0; f < nFrames; f++) {
                        int offset = f * frameSize;
                        sampleBuf[f].ch0 = readSample(buffer, offset);
                        sampleBuf[f].ch1 = readSample(buffer, offset + sampleBytes);
                    }
                    sampleListener.accept(sampleBuf);
                }
            } finally {
                bufferPool.offer(buffer);
            }
        }
    }

    /** Same offset-binary decoder as {@link WasapiRecorder#readSample}. */
    @Override
    public int readSample(byte[] pcm, int offset) {
        switch (sampleBytes) {
            case 1:
                return (pcm[offset] + (byte) 0x80) & 0xFF;
            case 2:
                return ((short) ((pcm[offset + 1] & 0xFF) << 8 | (pcm[offset] & 0xFF)) + (short) 0x8000) & 0xFFFF;
            case 3:
                return ((((pcm[offset + 2]) << 16)
                     | ((pcm[offset + 1] & 0xFF) << 8)
                     |  (pcm[offset]     & 0xFF)) + 0x800000) & 0xFFFFFF;
            case 4:
                return (((pcm[offset + 3] << 24)
                     | ((pcm[offset + 2] & 0xFF) << 16)
                     | ((pcm[offset + 1] & 0xFF) << 8)
                     |  (pcm[offset]     & 0xFF)) + 0x80000000) & 0xFFFFFFFF;
            default:
                throw new IllegalStateException("Unsupported sampleBytes: " + sampleBytes);
        }
    }

    @Override
    public boolean isRecording() { return recording.get(); }

    @Override
    public void close() {
        if (recording.get()) {
            try { stopRecording(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (stream != null) {
            Closeables.tryQuietly("Pa_CloseStream", () -> PortAudio.lib().Pa_CloseStream(stream));
            stream = null;
        }
    }

    /**
     * Lock-free single-producer single-consumer ring buffer of {@code byte[]}
     * references.  Replaces {@code LinkedBlockingQueue} / {@code
     * ConcurrentLinkedQueue} so the audio thread and the consume thread
     * exchange chunks without any per-call {@code Node} allocation.
     *
     * <p>Capacity must be a power of two — {@link #mask} reduces the
     * modulo-arithmetic to a bitwise AND on the hot path.  Memory ordering
     * is provided by the {@code volatile} {@link #writePos} / {@link
     * #readPos} cursors: a release-store of {@code writePos} happens-before
     * the matching acquire-load by the consumer, so a slot written by the
     * producer is visible to the consumer when it reads at that index.
     */
    private static final class SpscByteArrayRing {
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
        boolean offer(byte[] item) {
            long w = writePos;
            if (w - readPos >= slots.length) return false;
            slots[(int) (w & mask)] = item;
            writePos = w + 1;
            return true;
        }

        /** Consumer call: returns the next item or {@code null} if empty. */
        byte[] poll() {
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
}
