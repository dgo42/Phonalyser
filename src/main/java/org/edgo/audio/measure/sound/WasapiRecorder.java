package org.edgo.audio.measure.sound;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;

import lombok.extern.log4j.Log4j2;

import javax.sound.sampled.AudioFormat;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final AudioFormat format;

    private Pointer immDevice;
    private Pointer audioClient;
    private Pointer captureClient;
    private Pointer eventHandle;
    private int     bufferFrames;

    private final AtomicBoolean recording = new AtomicBoolean(false);
    private Thread captureThread;
    private StereoSample[] sampleBuf = new StereoSample[0];

    private Consumer<StereoSample[]> sampleListener;
    private PcmBatchListener         pcmBatchListener;
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

    @Override public void setSampleListener  (Consumer<StereoSample[]> l) { sampleListener   = l; }
    @Override public void setPcmBatchListener(PcmBatchListener l)        { pcmBatchListener = l; }
    @Override public void setRawBytesListener(Consumer<byte[]> l)        { rawBytesListener = l; }

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
                log.info("WASAPI exclusive refused — re-trying in shared mode");
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
        int hr = callHR(audioClient, VT_AC_START);
        if (hr != S_OK) {
            recording.set(false);
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
        if (captureThread != null) captureThread.join(2000);
        log.info("WASAPI recording stopped.");
    }

    /**
     * Audio-thread loop.  Waits on the event handle (signalled by
     * WASAPI when a fresh packet is available), drains every queued
     * packet via {@code GetBuffer/ReleaseBuffer}, copies into a reused
     * heap byte[], and dispatches to the configured listener.
     */
    private void captureLoop() {
        ensureComInit();
        byte[] heap = new byte[Math.max(bufferFrames, 1024) * frameSize];
        PointerByReference ppData       = new PointerByReference();
        IntByReference     framesAvail  = new IntByReference();
        IntByReference     flags        = new IntByReference();
        IntByReference     nextPacket   = new IntByReference();
        long silentPackets        = 0;
        long silentFrames         = 0;
        long discontinuityPackets = 0;
        long lastFlagLogNanos     = 0L;
        final long FLAG_LOG_INTERVAL_NANOS = 1_000_000_000L;

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
                if (heap.length < bytes) heap = new byte[bytes];

                boolean silent = (flagValue & AUDCLNT_BUFFERFLAGS_SILENT) != 0;
                boolean discontinuity = (flagValue & AUDCLNT_BUFFERFLAGS_DATA_DISCONTINUITY) != 0;
                if (silent) {
                    silentPackets++;
                    silentFrames += frames;
                    java.util.Arrays.fill(heap, 0, bytes, (byte) 0);
                } else {
                    Pointer data = ppData.getValue();
                    if (data != null) data.read(0, heap, 0, bytes);
                }
                if (discontinuity) discontinuityPackets++;
                callHR(captureClient, VT_CC_RELEASE_BUFFER, frames);

                long now = System.nanoTime();
                if ((silentPackets > 0 || discontinuityPackets > 0)
                        && now - lastFlagLogNanos >= FLAG_LOG_INTERVAL_NANOS) {
                    log.warn("WASAPI gaps: silent={} packets ({} frames, ~{} ms), discontinuities={}",
                            silentPackets, silentFrames,
                            silentFrames * 1000L / Math.max(1, sampleRate),
                            discontinuityPackets);
                    silentPackets = 0;
                    silentFrames  = 0;
                    discontinuityPackets = 0;
                    lastFlagLogNanos = now;
                }

                dispatch(heap, bytes);
            }
        }
    }

    private void dispatch(byte[] buffer, int bytes) {
        if (rawBytesListener != null) {
            byte[] copy = java.util.Arrays.copyOf(buffer, bytes);
            rawBytesListener.accept(copy);
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

    @Override public AudioFormat getFormat()   { return format; }
    @Override public boolean     isRecording() { return recording.get(); }

    @Override
    public void close() {
        if (recording.get()) {
            try { stopRecording(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
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
