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

import org.edgo.audio.measure.common.Closeables;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

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
public class WdmksRecorder extends AbstractPcmCapture {

    private final WdmksDeviceManager.WdmksDeviceRef device;

    private Pointer stream;                 // PaStream*
    private Thread consumerThread;

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
    /** Pool misses (callback had to allocate) since the last consumer log —
     *  steady state must be 0; non-zero means the pool is poisoned with
     *  wrong-sized buffers (PortAudio changed its block size mid-stream)
     *  and the audio thread is allocating per callback. */
    private final AtomicLong        poolMissAllocSinceLog = new AtomicLong();
    /** Audio-thread-only spare: keeps a queue-rejected buffer for the next
     *  callback instead of offering it back to {@link #bufferPool} — the
     *  pool ring is SPSC with the consume thread as its sole producer, and
     *  a second producer can silently lose a slot. */
    private byte[] callbackSpare;
    /** Logged once when the audio callback faults, so a per-block failure logs
     *  a single stack trace instead of flooding the log. */
    private final AtomicBoolean callbackFaultLogged = new AtomicBoolean();

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
        // Raw captured bytes — mono (captureChannels == 1) or stereo; dispatch()
        // upmixes a mono channel to stereo off the audio thread.
        int bytes  = frames * sampleBytes * captureChannels;
        byte[] buf = callbackSpare;
        callbackSpare = null;
        if (buf == null) buf = bufferPool.aquire();
        if (buf == null || buf.length != bytes) {
            buf = new byte[bytes];
            poolMissAllocSinceLog.incrementAndGet();
        }
        try {
            input.read(0, buf, 0, bytes);
        } catch (Throwable th) {
            // JNA would otherwise swallow this (stderr + return 0) and the
            // capture would silently stop delivering.  Log once, keep the
            // buffer as the next spare, keep the stream running.
            if (callbackFaultLogged.compareAndSet(false, true)) {
                log.error("WDM-KS capture callback failed — dropping block: {}", th.toString(), th);
            }
            callbackSpare = buf;
            return PortAudio.paContinue;
        }
        if (!queue.release(buf)) {
            overflowCount.incrementAndGet();
            droppedFramesSinceLog.addAndGet(frames);
            callbackSpare = buf;
        }
        return PortAudio.paContinue;
    };

    public WdmksRecorder(WdmksDeviceManager.WdmksDeviceRef device, int sampleRate, int bitDepth) {
        super(sampleRate, bitDepth, Math.min(2, Math.max(1, device.maxInputChannels())));
        this.device = device;
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
        in.channelCount              = captureChannels;
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

        log.info("WDM-KS recorder opened : {}", device.name());
        log.info("Capture format         : {}", getFormat());
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
        while (recording.get() || !queue.isEmpty()) {
            byte[] buffer = queue.aquire();
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
                dispatch(buffer, buffer.length);
            } finally {
                bufferPool.release(buffer);
            }
        }
    }

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

    // SpscByteArrayRing was extracted to its own top-level class when
    // WasapiRecorder adopted the same capture→consumer decoupling.
}
