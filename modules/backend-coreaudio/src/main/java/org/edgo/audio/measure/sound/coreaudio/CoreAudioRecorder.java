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

package org.edgo.audio.measure.sound.coreaudio;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import org.edgo.audio.measure.common.Closeables;
import org.edgo.audio.measure.sound.AbstractPortAudioCapture;
import org.edgo.audio.measure.sound.PortAudio;
import org.edgo.audio.measure.sound.SpscByteArrayRing;
import org.edgo.audio.measure.sound.wdmks.WdmksRecorder;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import lombok.extern.log4j.Log4j2;

/**
 * Stereo PCM capture via PortAudio's CoreAudio host API (macOS), callback mode.
 * PortAudio invokes {@link #paCallback} on its realtime audio thread with
 * freshly captured frames; the callback copies the bytes into {@link #queue}
 * and returns immediately.  A dedicated consumer thread drains the queue and
 * runs the listener {@link AbstractPcmCapture#dispatch}, so heavy listener work
 * never blocks the audio thread.  macOS counterpart of {@link WdmksRecorder} -
 * same PortAudio binding, different host API; kept separate so the Windows path
 * is untouched.
 *
 * <p>Mono input devices are opened at 1 channel (the realtime callback reads
 * the single channel with one {@code input.read}); the mono->stereo upmix
 * happens off the audio thread in {@link AbstractPcmCapture#dispatch}.
 */
@Log4j2
public class CoreAudioRecorder extends AbstractPortAudioCapture {

    /** This backend's name in the log and in a reported device loss. */
    private static final String BACKEND_LABEL = "CoreAudio";

    /** The delivery deadline until the FIRST block of a run arrives: an
     *  aggregate device assembles its IO for several seconds before it speaks,
     *  and a capture switched onto one was being declared lost before it had a
     *  chance to.  After the first block the standard deadline stands - a
     *  running stream that goes silent HAS stopped. */
    private static final long FIRST_BLOCK_DEADLINE_NANOS = 8_000_000_000L;

    private final CoreAudioDeviceManager.CoreAudioDeviceRef device;

    /** The HAL, injected by the manager that owns it: the volume pin in
     *  {@link #open()} is the one thing this capture changes about the machine. */
    private final CoreAudioHal hal;

    private Pointer stream;                 // PaStream*
    private Thread consumerThread;

    /** Hand-off from the PA audio thread to the consume thread - lock-free SPSC
     *  ring of {@code byte[]} references so offer/poll don't allocate per chunk. */
    private final SpscByteArrayRing queue       = new SpscByteArrayRing(64);
    /** Recycled buffer pool drained by the audio thread, refilled by the
     *  consume thread once a chunk has been processed. */
    private final SpscByteArrayRing bufferPool  = new SpscByteArrayRing(64);
    private final AtomicLong        overflowCount = new AtomicLong();
    /** Whether this run has delivered its first block yet - what picks between
     *  the assembly grace and the standard delivery deadline. */
    private volatile boolean firstBlockSeen;

    /** Audio-thread-only spare: keeps a queue-rejected buffer for the next
     *  callback instead of offering it back to {@link #bufferPool} (SPSC pool
     *  has the consume thread as its sole producer). */
    private byte[] callbackSpare;
    /** Logged once when the audio callback faults, so a per-block failure logs
     *  a single stack trace instead of flooding the log. */
    private final AtomicBoolean callbackFaultLogged = new AtomicBoolean();

    private final PortAudio.PaStreamCallback paCallback = (input, output, frameCount, ti, sf, ud) -> {
        // Don't self-complete via paComplete: on CoreAudio the callback-driven
        // stop races the explicit Pa_AbortStream in stopRecording() and can
        // deadlock.  Keep returning paContinue; stopRecording() owns the stop.
        if (!recording.get()) return PortAudio.paContinue;
        int frames = frameCount.intValue();
        // Raw captured bytes - mono (captureChannels == 1) or stereo.  No upmix
        // on the realtime thread: dispatch() splits a mono channel to stereo on
        // the consume thread.
        int bytes  = frames * sampleBytes * captureChannels;
        byte[] buf = callbackSpare;
        callbackSpare = null;
        if (buf == null) buf = bufferPool.aquire();
        if (buf == null || buf.length != bytes) {
            buf = new byte[bytes];
        }
        try {
            input.read(0, buf, 0, bytes);
        } catch (Throwable th) {
            // JNA would otherwise swallow this (stderr + return 0) and the
            // capture would silently stop delivering.  Log once, keep the
            // buffer as the next spare, keep the stream running.
            if (callbackFaultLogged.compareAndSet(false, true)) {
                log.error("CoreAudio capture callback failed - dropping block: {}", th.toString(), th);
            }
            callbackSpare = buf;
            return PortAudio.paContinue;
        }
        if (!queue.release(buf)) {
            overflowCount.incrementAndGet();
            callbackSpare = buf;
        }
        return PortAudio.paContinue;
    };

    public CoreAudioRecorder(CoreAudioDeviceManager.CoreAudioDeviceRef device, int sampleRate,
            int bitDepth, CoreAudioHal hal) {
        super(BACKEND_LABEL, sampleRate, bitDepth,
                Math.min(2, Math.max(1, device.maxInputChannels())));
        this.device = device;
        this.hal = hal;
    }

    /** {@inheritDoc}  The base asks for this to run its liveness check; the
     *  stream's whole lifecycle stays here. */
    @Override
    protected Pointer paStream() {
        return stream;
    }

    /** {@inheritDoc}  Longer until the run's first block - see
     *  {@link #FIRST_BLOCK_DEADLINE_NANOS}. */
    @Override
    protected long deliveryDeadlineNanos() {
        return firstBlockSeen ? super.deliveryDeadlineNanos() : FIRST_BLOCK_DEADLINE_NANOS;
    }

    /** {@inheritDoc}  Asked of the live HAL: a macOS aggregate whose members
     *  all left keeps clocking zeroes into this stream, so neither silence nor
     *  {@code Pa_IsStreamActive} ever reports the loss. */
    @Override
    protected boolean deviceStillPresent() {
        return hal.devicePresent(device.name(), false);
    }

    @Override
    public void open() {
        PortAudio.Lib lib = PortAudio.lib();

        PortAudio.PaDeviceInfo info = lib.Pa_GetDeviceInfo(device.paDeviceIndex());
        // Prefer the device's own default-LOW input latency so PortAudio asks
        // CoreAudio for small chunks (~10-20 ms) - that drives the callback
        // rate up so the scope cap/s isn't throttled by long buffers.  Fall
        // back to defaultHighInputLatency (and finally 25 ms) when 0.
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
        // Counted BEFORE the open, so a device-list refresh can never call
        // Pa_Terminate while this open is in flight - see PortAudio#streamOpening.
        PortAudio.streamOpening();
        int rc = lib.Pa_OpenStream(handle,
                in, null,
                sampleRate,
                PortAudio.paFramesPerBufferUnspecified,
                PortAudio.paClipOff,
                paCallback, null);
        if (rc != PortAudio.paNoError) {
            PortAudio.streamClosed();
        }
        PortAudio.check(rc, "Pa_OpenStream(input)");
        stream = handle.getValue();

        // The line is ours: pin the device's own input volume to its 0 dB
        // before a single sample is taken through it.  A mixer control anywhere
        // else scales every volt this capture reports and is invisible to the
        // calibration behind it (see CoreAudioHal#pinVolumeToUnity).  After the
        // open, never before: an open that failed leaves the machine alone.
        hal.pinVolumeToUnity(device.name(), false);

        log.info("CoreAudio recorder opened : {}", device.name());
        log.info("Capture format            : {} ({})", getFormat(),
                captureChannels == 1 ? "mono upmixed to stereo" : "stereo");
        log.info("Suggested latency         : {} ms (host-picked buffer size)",
                Math.round(suggestedLatency * 1000));
    }

    @Override
    public void startRecording() {
        if (stream == null) throw new IllegalStateException("Call open() before startRecording()");
        recording.set(true);
        queue.clear();
        bufferPool.clear();
        overflowCount.set(0);
        firstBlockSeen = false;
        // Reset before the consume thread exists, so it is safely published to
        // it: the stream is started BELOW, after that thread is running, and a
        // liveness check that ran in between would find it not yet active.
        resetLivenessCheck();
        consumerThread = new Thread(this::consumeLoop, "coreaudio-consume");
        consumerThread.setDaemon(true);
        consumerThread.setPriority(Thread.NORM_PRIORITY + 1);
        consumerThread.start();
        PortAudio.check(PortAudio.lib().Pa_StartStream(stream), "Pa_StartStream(input)");
        log.info("CoreAudio recording started on: {}", device.name());
    }

    @Override
    public void stopRecording() throws InterruptedException {
        recording.set(false);
        if (stream != null) {
            // Pa_AbortStream, not Pa_StopStream: on CoreAudio Pa_StopStream
            // blocks on a stop semaphore the host API doesn't reliably signal,
            // hanging the GUI thread.  Abort halts the IO proc immediately;
            // there is nothing to drain on an input stream.
            try {
                PortAudio.lib().Pa_AbortStream(stream);
            } catch (Throwable t) {
                log.warn("Pa_AbortStream: {}", t.getMessage());
            }
        }
        if (consumerThread != null) consumerThread.join(2000);
        long drops = overflowCount.get();
        if (drops > 0) log.warn("CoreAudio capture queue overflowed {} times", drops);
        log.info("CoreAudio recording stopped.");
    }

    private void consumeLoop() {
        while (recording.get() || !queue.isEmpty()) {
            byte[] buffer = queue.aquire();
            if (buffer == null) {
                // Ring empty.  Park briefly so we don't busy-spin a core while
                // waiting for the next chunk (audio period is 10-20 ms).
                //
                // An empty ring is also the only symptom a pulled USB device
                // has: the callback simply stops being invoked.  So a long
                // enough silence is what prompts the one question that can tell
                // "quiet" from "gone".
                if (ringEmpty()) {
                    return;
                }
                LockSupport.parkNanos(500_000L);
                continue;
            }
            ringDelivered();
            firstBlockSeen = true;
            try {
                dispatch(buffer, buffer.length);
            } finally {
                bufferPool.release(buffer);
            }
            if (devicePresenceLost()) {
                return;
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
            PortAudio.streamClosed();
            stream = null;
        }
        // The line is no longer ours: put the volume back as the open found it.
        hal.restoreVolume(device.name(), false);
    }
}
