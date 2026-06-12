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

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;

import javax.sound.sampled.AudioFormat;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

import org.edgo.audio.measure.common.StereoSample;

import static org.edgo.audio.measure.sound.WasapiNative.*;

/**
 * Stereo PCM capture via WASAPI in exclusive-event mode (shared
 * fallback if exclusive is refused).  Opens
 * {@code IAudioClient::Initialize} with
 * {@code AUDCLNT_STREAMFLAGS_EVENTCALLBACK}, sets an event handle, and
 * loops on the audio thread waiting on that handle and draining
 * {@code IAudioCaptureClient::GetBuffer}.
 *
 * <p>The capture-event thread only copies packet bytes into pooled buffers
 * and hands them to a consume thread over an {@link SpscByteArrayRing} —
 * the same decoupling {@link WdmksRecorder} uses.  Listener work (per-sample
 * decode, ring append, bus publish) therefore can never delay the next
 * {@code WaitForSingleObject} past the event deadline, however expensive a
 * consumer gets.
 *
 * <p>Mirrors {@link WdmksRecorder}'s public surface so the GUI scope
 * view and CLI tools can drive it identically through
 * {@link AudioBackend}.
 */
@Log4j2
public class WasapiRecorder implements AudioCapture {

    private static final int CHANNELS = 2;

    private final WasapiDeviceManager       devices;
    private final WasapiDeviceManager.WasapiDeviceRef device;
    private final int sampleRate;
    private final int bitDepth;
    private final int sampleBytes;
    private final int frameSize;
    @Getter
    private final AudioFormat format;

    private Pointer immDevice;
    private Pointer audioClient;
    private Pointer captureClient;
    private Pointer eventHandle;
    private int     bufferFrames;

    private final AtomicBoolean recording = new AtomicBoolean(false);
    private Thread captureThread;
    private Thread consumerThread;
    /** Consume-thread-only decode staging for {@link #sampleListener}. */
    private StereoSample[] sampleBuf = new StereoSample[0];

    /** SPSC ring of filled packets — capture-event thread → consume thread. */
    private final SpscByteArrayRing queue      = new SpscByteArrayRing(64);
    /** Recycled buffer pool — consume thread → capture-event thread (the
     *  SPSC roles simply reversed). */
    private final SpscByteArrayRing bufferPool = new SpscByteArrayRing(64);
    /** Capture-thread-only spare: holds a queue-rejected buffer for the next
     *  packet instead of offering it back to {@link #bufferPool} (which
     *  would make the capture thread a second producer on that ring). */
    private byte[] captureSpare;
    /** Frames dropped on a full queue since the last consumer-side log. */
    private final AtomicLong droppedFramesSinceLog  = new AtomicLong();
    /** WASAPI packet-flag counters since the last consumer-side log. */
    private final AtomicLong silentPacketsSinceLog  = new AtomicLong();
    private final AtomicLong silentFramesSinceLog   = new AtomicLong();
    private final AtomicLong discontinuitiesSinceLog = new AtomicLong();

    @Setter
    private Consumer<StereoSample[]> sampleListener;
    @Setter
    private PcmBatchListener         pcmBatchListener;
    @Setter
    private Consumer<byte[]>         rawBytesListener;

    public WasapiRecorder(WasapiDeviceManager devices,
                          WasapiDeviceManager.WasapiDeviceRef device,
                          int sampleRate, int bitDepth) {
        this.devices     = devices;
        this.device      = device;
        this.sampleRate  = sampleRate;
        this.bitDepth    = bitDepth;
        this.sampleBytes = bitDepth / 8;
        this.frameSize   = sampleBytes * CHANNELS;
        this.format = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate, bitDepth, CHANNELS,
                frameSize, sampleRate, false);
    }

    @Override
    public void open() {
        ensureComInit();
        immDevice = devices.openDevice(device.endpointId());
        if (immDevice == null) {
            throw new IllegalStateException("WASAPI device disappeared: " + device.name());
        }
        try {
            audioClient = activateAudioClient(immDevice);
            // Target an exclusive-mode buffer of at least 50 ms.  The
            // device period (typically ~3-10 ms) is too small to
            // absorb JVM GC pauses or scheduler jitter — the
            // resulting sample drops show up as WASAPI
            // discontinuities and produce phase-coherence rejections
            // downstream.  Round up to a multiple of the device
            // period so the driver accepts the size on the first
            // Initialize call; AUDCLNT_E_BUFFER_SIZE_NOT_ALIGNED
            // still has a retry path below for the rare misalignment.
            LongByReference defPeriod = new LongByReference();
            LongByReference minPeriod = new LongByReference();
            final long TARGET_BUF_DURATION = 50 * REF_TIME_PER_MILLISEC;
            long bufDuration;
            if (callHR(audioClient, VT_AC_GET_DEVICE_PERIOD,
                    defPeriod, minPeriod) == S_OK) {
                long period = Math.max(1, defPeriod.getValue());
                long periods = Math.max(1L, (TARGET_BUF_DURATION + period - 1) / period);
                bufDuration = period * periods;
            } else {
                bufDuration = TARGET_BUF_DURATION;
            }

            boolean exclusive = initializeExclusive(bufDuration);
            if (!exclusive) {
                // WARN, not info: in shared mode the Windows audio engine sits in
                // the signal path — its per-channel session volume / balance and
                // any APO scale the samples, so captured levels are no longer the
                // raw measurement-grade input exclusive mode guarantees.
                if (log.isWarnEnabled()) {
                    log.warn("WASAPI exclusive refused — falling back to SHARED mode: "
                            + "the Windows mixer (volume/balance/APOs) now scales the input; "
                            + "absolute levels are not measurement-grade");
                }
                // Activate again; AudioClient is single-use after a
                // failed Initialize.
                release(audioClient);
                audioClient = activateAudioClient(immDevice);
                initializeShared();
            }

            IntByReference framesRef = new IntByReference();
            int hr = callHR(audioClient, VT_AC_GET_BUFFER_SIZE, framesRef);
            if (hr != S_OK) {
                throw new IllegalStateException(
                        "GetBufferSize failed: 0x" + Integer.toHexString(hr));
            }
            bufferFrames = framesRef.getValue();

            PointerByReference ppCap = new PointerByReference();
            hr = callHR(audioClient, VT_AC_GET_SERVICE,
                    IID_IAudioCaptureClient, ppCap);
            if (hr != S_OK || ppCap.getValue() == null) {
                throw new IllegalStateException(
                        "GetService(IAudioCaptureClient) failed: 0x"
                                + Integer.toHexString(hr));
            }
            captureClient = ppCap.getValue();

            log.info("WASAPI recorder opened : {}", device.name());
            log.info("Capture format         : {}", format);
            log.info("HW buffer              : {} frames ({} ms)",
                    bufferFrames,
                    bufferFrames * 1000 / sampleRate);
        } catch (RuntimeException ex) {
            closeQuietly();
            throw ex;
        }
    }

    private Pointer activateAudioClient(Pointer dev) {
        PointerByReference pp = new PointerByReference();
        int hr = callHR(dev, VT_DEVICE_ACTIVATE,
                IID_IAudioClient, CLSCTX_ALL, null, pp);
        if (hr != S_OK || pp.getValue() == null) {
            throw new IllegalStateException(
                    "IMMDevice.Activate(IAudioClient) failed: 0x"
                            + Integer.toHexString(hr));
        }
        return pp.getValue();
    }

    /**
     * Tries exclusive-mode Initialize with an event handle.  On
     * {@code AUDCLNT_E_BUFFER_SIZE_NOT_ALIGNED} this fetches the
     * aligned buffer size and retries once with the corrected duration
     * (the canonical WASAPI alignment fix-up).
     *
     * <p>Returns {@code true} if exclusive mode succeeded.  Returns
     * {@code false} for the two refusals that warrant the shared-mode
     * fallback: {@code AUDCLNT_E_EXCLUSIVE_MODE_NOT_ALLOWED} and
     * {@code AUDCLNT_E_DEVICE_IN_USE}.  Any other HRESULT is rethrown
     * because it points at a real configuration problem (bad rate, bad
     * device, etc.) rather than a sharing conflict.
     */
    private boolean initializeExclusive(long initialBufDuration) {
        Memory wfx = buildWaveFormatExtensible(sampleRate, bitDepth);
        long bufDuration = initialBufDuration;
        for (int attempt = 0; attempt < 2; attempt++) {
            int hr = callHR(audioClient, VT_AC_INITIALIZE,
                    AUDCLNT_SHAREMODE_EXCLUSIVE,
                    AUDCLNT_STREAMFLAGS_EVENTCALLBACK | AUDCLNT_STREAMFLAGS_NOPERSIST,
                    bufDuration, bufDuration, wfx, null);
            if (hr == S_OK) {
                attachEvent();
                return true;
            }
            if (hr == AUDCLNT_E_BUFFER_SIZE_NOT_ALIGNED && attempt == 0) {
                IntByReference framesRef = new IntByReference();
                if (callHR(audioClient, VT_AC_GET_BUFFER_SIZE, framesRef) == S_OK) {
                    int frames = framesRef.getValue();
                    // hnsBufferDuration = (10000 * 1000 / nSamplesPerSec * nFrames) + 0.5
                    bufDuration = Math.round(REF_TIME_PER_SEC * (double) frames / sampleRate);
                    // Re-activate: a failed Initialize leaves the client unusable.
                    release(audioClient);
                    audioClient = activateAudioClient(immDevice);
                    continue;
                }
            }
            if (hr == AUDCLNT_E_EXCLUSIVE_MODE_NOT_ALLOWED
                    || hr == AUDCLNT_E_DEVICE_IN_USE) {
                return false;
            }
            throw new IllegalStateException(
                    "IAudioClient.Initialize(EXCLUSIVE) failed: 0x"
                            + Integer.toHexString(hr));
        }
        return false;
    }

    private void initializeShared() {
        Memory wfx = buildWaveFormatExtensible(sampleRate, bitDepth);
        // Shared mode: hnsPeriodicity must be 0; pick ~200 ms buffer
        // for plenty of jitter headroom.
        long bufDuration = 200 * REF_TIME_PER_MILLISEC;
        int hr = callHR(audioClient, VT_AC_INITIALIZE,
                AUDCLNT_SHAREMODE_SHARED,
                AUDCLNT_STREAMFLAGS_EVENTCALLBACK | AUDCLNT_STREAMFLAGS_NOPERSIST,
                bufDuration, 0L, wfx, null);
        if (hr != S_OK) {
            throw new IllegalStateException(
                    "IAudioClient.Initialize(SHARED) failed: 0x"
                            + Integer.toHexString(hr));
        }
        attachEvent();
    }

    private void attachEvent() {
        eventHandle = Kernel32.INSTANCE.CreateEventW(null, 0, 0, null);
        if (eventHandle == null) {
            throw new IllegalStateException("CreateEventW failed: " + Native.getLastError());
        }
        int hr = callHR(audioClient, VT_AC_SET_EVENT_HANDLE, eventHandle);
        if (hr != S_OK) {
            throw new IllegalStateException(
                    "SetEventHandle failed: 0x" + Integer.toHexString(hr));
        }
    }

    @Override
    public void startRecording() {
        if (audioClient == null || captureClient == null) {
            throw new IllegalStateException("Call open() before startRecording()");
        }
        recording.set(true);
        // Rings are safe to clear here: stopRecording joined both threads
        // (or none ever ran).
        queue.clear();
        bufferPool.clear();
        captureSpare = null;
        droppedFramesSinceLog.set(0);
        silentPacketsSinceLog.set(0);
        silentFramesSinceLog.set(0);
        discontinuitiesSinceLog.set(0);
        consumerThread = new Thread(this::consumeLoop, "wasapi-consume");
        consumerThread.setDaemon(true);
        consumerThread.setPriority(Thread.NORM_PRIORITY + 1);
        consumerThread.start();
        int hr = callHR(audioClient, VT_AC_START);
        if (hr != S_OK) {
            recording.set(false);   // consumer exits on its next flag check
            throw new IllegalStateException(
                    "IAudioClient.Start failed: 0x" + Integer.toHexString(hr));
        }
        captureThread = new Thread(this::captureLoop, "wasapi-capture");
        captureThread.setDaemon(true);
        captureThread.setPriority(Thread.MAX_PRIORITY);
        captureThread.start();
        log.info("WASAPI recording started on: {}", device.name());
    }

    @Override
    public void stopRecording() throws InterruptedException {
        recording.set(false);
        if (audioClient != null) {
            try {
                callHR(audioClient, VT_AC_STOP);
            } catch (Throwable t) {
                log.warn("IAudioClient.Stop threw: {}", t.getMessage());
            }
        }
        if (captureThread  != null) captureThread.join(2000);
        if (consumerThread != null) consumerThread.join(2000);
        long dropped = droppedFramesSinceLog.getAndSet(0);
        if (dropped > 0) {
            log.warn("WASAPI capture queue dropped {} frames in total", dropped);
        }
        log.info("WASAPI recording stopped.");
    }

    /**
     * Capture-event thread loop.  Waits on the event handle (signalled by
     * WASAPI when a fresh packet is available), drains every queued packet
     * via {@code GetBuffer/ReleaseBuffer} into a pooled buffer and hands it
     * to the consume thread over {@link #queue}.  Copy-only: all listener
     * work runs on the consume thread, so a slow consumer can never delay
     * the next event wait (it costs queued buffers, counted and logged,
     * instead of capture stalls).
     */
    private void captureLoop() {
        ensureComInit();
        PointerByReference ppData       = new PointerByReference();
        IntByReference     framesAvail  = new IntByReference();
        IntByReference     flags        = new IntByReference();
        IntByReference     nextPacket   = new IntByReference();

        while (recording.get()) {
            int waitRc = Kernel32.INSTANCE.WaitForSingleObject(eventHandle, 200);
            if (waitRc != WAIT_OBJECT_0) {
                // Timed out (no audio yet) or wait failed — re-check the
                // running flag and try again.  Don't bail out on the
                // first idle period; the device may take a few ms to
                // start producing frames.
                continue;
            }
            while (true) {
                int hr = callHR(captureClient, VT_CC_GET_NEXT_PACKET_SIZE, nextPacket);
                if (hr != S_OK || nextPacket.getValue() == 0) break;

                hr = callHR(captureClient, VT_CC_GET_BUFFER,
                        ppData, framesAvail, flags, null, null);
                if (hr != S_OK) {
                    log.warn("IAudioCaptureClient.GetBuffer: 0x{}", Integer.toHexString(hr));
                    break;
                }
                int frames    = framesAvail.getValue();
                int bytes     = frames * frameSize;
                int flagValue = flags.getValue();
                // Pooled buffer; allocation only on cold start or a packet-size
                // change (exclusive event mode delivers fixed period-size
                // packets, so steady state is allocation-free).
                byte[] buf = captureSpare;
                captureSpare = null;
                if (buf == null) buf = bufferPool.poll();
                if (buf == null || buf.length != bytes) buf = new byte[bytes];

                if ((flagValue & AUDCLNT_BUFFERFLAGS_SILENT) != 0) {
                    silentPacketsSinceLog.incrementAndGet();
                    silentFramesSinceLog.addAndGet(frames);
                    Arrays.fill(buf, 0, bytes, (byte) 0);
                } else {
                    Pointer data = ppData.getValue();
                    if (data != null) data.read(0, buf, 0, bytes);
                }
                if ((flagValue & AUDCLNT_BUFFERFLAGS_DATA_DISCONTINUITY) != 0) {
                    discontinuitiesSinceLog.incrementAndGet();
                }
                callHR(captureClient, VT_CC_RELEASE_BUFFER, frames);

                if (!queue.offer(buf)) {
                    droppedFramesSinceLog.addAndGet(frames);
                    captureSpare = buf;   // keep thread-local — never offer to the pool from here
                }
            }
        }
    }

    /** Consume-thread loop: drains {@link #queue}, runs the listener
     *  dispatch, recycles buffers into {@link #bufferPool}, and emits the
     *  rate-limited gap diagnostics the capture thread only counts. */
    private void consumeLoop() {
        long lastLogNanos = 0L;
        final long LOG_INTERVAL_NANOS = 1_000_000_000L;
        while (recording.get() || !queue.isEmpty()) {
            long now = System.nanoTime();
            if (now - lastLogNanos >= LOG_INTERVAL_NANOS) {
                long silent  = silentPacketsSinceLog.getAndSet(0);
                long sFrames = silentFramesSinceLog.getAndSet(0);
                long disc    = discontinuitiesSinceLog.getAndSet(0);
                long dropped = droppedFramesSinceLog.getAndSet(0);
                if (silent > 0 || disc > 0 || dropped > 0) {
                    log.warn("WASAPI gaps: silent={} packets ({} frames, ~{} ms), discontinuities={}, queue-dropped={} frames",
                            silent, sFrames,
                            sFrames * 1000L / Math.max(1, sampleRate),
                            disc, dropped);
                }
                lastLogNanos = now;
            }
            byte[] buffer = queue.poll();
            if (buffer == null) {
                // Ring empty — park briefly instead of busy-spinning; the
                // packet period is ms-scale, 500 µs is plenty of resolution.
                LockSupport.parkNanos(500_000L);
                continue;
            }
            try {
                dispatch(buffer, buffer.length);
            } finally {
                bufferPool.offer(buffer);
            }
        }
    }

    /** Listener dispatch — consume thread only.  Buffers are recycled after
     *  this returns, so listeners must consume synchronously (the same
     *  contract {@link WdmksRecorder}'s consume loop has always had). */
    private void dispatch(byte[] buffer, int bytes) {
        if (rawBytesListener != null) {
            rawBytesListener.accept(buffer);
        }
        if (pcmBatchListener != null) {
            pcmBatchListener.accept(buffer, bytes);
        } else if (sampleListener != null) {
            int nFrames = bytes / frameSize;
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
    }

    /** Offset-binary decoder — unsigned 0..2^bitsPerSample-1 minus midpoint. */
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

    @Override public boolean     isRecording() { return recording.get(); }

    @Override
    public void close() {
        if (recording.get()) {
            try { stopRecording(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        // Never release the COM objects while the capture thread may still
        // be calling into them (vtable call on a released object is a native
        // crash, not an exception).  If the thread won't die, leak the
        // objects deliberately.
        Thread t = captureThread;
        if (t != null && t.isAlive()) {
            try { t.join(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (t.isAlive()) {
                log.error("WASAPI capture thread still alive — leaking COM objects instead of releasing under it.");
                return;
            }
        }
        closeQuietly();
    }

    private void closeQuietly() {
        if (captureClient != null) { release(captureClient); captureClient = null; }
        if (audioClient   != null) { release(audioClient);   audioClient   = null; }
        if (immDevice     != null) { release(immDevice);     immDevice     = null; }
        if (eventHandle   != null) { Kernel32.INSTANCE.CloseHandle(eventHandle); eventHandle = null; }
    }
}
