package org.edgo.audio.measure.sound;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;

import lombok.extern.log4j.Log4j2;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.edgo.audio.measure.generator.SignalGenerator;

import static org.edgo.audio.measure.sound.WasapiNative.*;

/**
 * Stereo PCM playback via WASAPI in exclusive-event mode (shared
 * fallback if exclusive is refused).  Pulls samples from a
 * {@link SignalGenerator}, packs them into the device's PCM format and
 * writes them via {@code IAudioRenderClient::GetBuffer / ReleaseBuffer}
 * on every event-handle wake-up.
 *
 * <p>Mirrors {@link WdmksGenerator}'s public surface so the GUI play
 * button and CLI flows drive it identically through
 * {@link AudioBackend}.
 */
@Log4j2
public class WasapiGenerator implements AudioPlayback {

    private static final int CHANNELS = 2;

    private final WasapiDeviceManager       devices;
    private final WasapiDeviceManager.WasapiDeviceRef device;
    private final int sampleRate;
    private final int bitDepth;
    private final int bytesPerSample;
    private final int bytesPerFrame;
    private volatile int ditherBits;
    private final Random rng;

    private Pointer immDevice;
    private Pointer audioClient;
    private Pointer renderClient;
    private Pointer eventHandle;
    private int     bufferFrames;

    public WasapiGenerator(WasapiDeviceManager devices,
                           WasapiDeviceManager.WasapiDeviceRef device,
                           int sampleRate, int bitDepth, int ditherBits) {
        this.devices        = devices;
        this.device         = device;
        this.sampleRate     = sampleRate;
        this.bitDepth       = bitDepth;
        this.ditherBits     = ditherBits;
        this.bytesPerSample = bitDepth / 8;
        this.bytesPerFrame  = bytesPerSample * CHANNELS;
        // Always allocate so live setDitherBits(>0) doesn't NPE in tpdfNoise().
        this.rng            = new Random();
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
            LongByReference defPeriod = new LongByReference();
            LongByReference minPeriod = new LongByReference();
            long bufDuration = (callHR(audioClient, VT_AC_GET_DEVICE_PERIOD,
                    defPeriod, minPeriod) == S_OK)
                    ? defPeriod.getValue()
                    : 10 * REF_TIME_PER_MILLISEC;

            boolean exclusive = initializeExclusive(bufDuration);
            if (!exclusive) {
                log.info("WASAPI exclusive refused — re-trying in shared mode");
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

            PointerByReference ppRender = new PointerByReference();
            hr = callHR(audioClient, VT_AC_GET_SERVICE,
                    IID_IAudioRenderClient, ppRender);
            if (hr != S_OK || ppRender.getValue() == null) {
                throw new IllegalStateException(
                        "GetService(IAudioRenderClient) failed: 0x"
                                + Integer.toHexString(hr));
            }
            renderClient = ppRender.getValue();

            log.info("WASAPI generator opened : {}", device.name());
            log.info("Output rate/bits        : {} Hz / {} bit", sampleRate, bitDepth);
            log.info("HW buffer               : {} frames ({} ms)",
                    bufferFrames,
                    bufferFrames * 1000 / sampleRate);
            if (ditherBits > 0) log.info("Dithering               : TPDF {} bit", ditherBits);
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
                    bufDuration = Math.round(REF_TIME_PER_SEC * (double) frames / sampleRate);
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
    public void play(SignalGenerator generator, int durationSeconds) {
        AtomicBoolean stop = new AtomicBoolean(false);
        CountDownLatch ready = new CountDownLatch(1);
        long totalFrames = (long) sampleRate * durationSeconds;
        renderLoop(generator, stop, ready, totalFrames);
    }

    @Override
    public void play(SignalGenerator generator, AtomicBoolean stopFlag, CountDownLatch readyLatch) {
        renderLoop(generator, stopFlag, readyLatch, 0);
    }

    /**
     * Core render loop: prime the buffer, signal {@code readyLatch},
     * then on each event-handle wake-up fill whatever space the device
     * has freed via {@code GetBuffer/ReleaseBuffer}.  Stops cleanly when
     * either {@code stopFlag} is raised or {@code totalFrames} (when
     * non-zero) have been delivered.
     */
    private void renderLoop(SignalGenerator generator,
                            AtomicBoolean stopFlag,
                            CountDownLatch readyLatch,
                            long totalFrames) {
        if (audioClient == null || renderClient == null) {
            throw new IllegalStateException("Call open() before play()");
        }
        ensureComInit();

        // Reusable scratch — one allocation outside the hot path so the
        // audio thread doesn't churn the young generation every tick.
        byte[] scratch = new byte[bufferFrames * bytesPerFrame];
        long framesProduced = 0;

        // JIT warmup: exercise the encode hot path before the device
        // starts pulling real audio.  See CsjsoundGenerator#warmupJit
        // for the rationale; here we drive encodeIntoScratch directly
        // (no GetBuffer/ReleaseBuffer COM call, no SDR side-effect).
        warmupJit(generator, scratch);

        // Pre-fill the entire hardware buffer once so the device has
        // something to play the instant Start() is called.  Without
        // this, exclusive mode glitches on the very first event tick.
        framesProduced += fillNextBlock(generator, scratch, bufferFrames);
        int hr = callHR(audioClient, VT_AC_START);
        if (hr != S_OK) {
            throw new IllegalStateException(
                    "IAudioClient.Start failed: 0x" + Integer.toHexString(hr));
        }
        readyLatch.countDown();
        log.info("WASAPI playback started.");

        try {
            IntByReference padding = new IntByReference();
            // Expected ns between event signals — buffer-frames converted
            // to time at sampleRate.  Threshold is 1.2× so we catch the
            // 200 µs-class delays that still produce audible glitches
            // even when the device doesn't fully underrun.
            final long expectedTickNanos = (long) bufferFrames * 1_000_000_000L / sampleRate;
            final long underrunThresholdNanos = (expectedTickNanos * 12L) / 10L;
            // Time spent INSIDE fillNextBlock — if it ever exceeds half
            // the buffer period the next event will be missed.
            final long fillBudgetNanos = expectedTickNanos / 2L;
            log.info("WASAPI render loop: tick={} µs, underrun-threshold={} µs, fill-budget={} µs, buffer={} frames",
                    expectedTickNanos / 1000, underrunThresholdNanos / 1000, fillBudgetNanos / 1000, bufferFrames);
            long lastTickNanos        = System.nanoTime();
            long underrunCount        = 0;
            long worstLagNanos        = 0;
            long slowFillCount        = 0;
            long worstFillNanos       = 0;
            long partialFillCount     = 0;
            long minAvailObserved     = Integer.MAX_VALUE;
            long maxTickNanos         = 0;
            long lastLogNanos         = System.nanoTime();
            long ticksSinceLog        = 0;
            final long LOG_INTERVAL_NANOS = 5_000_000_000L;  // every 5 s
            while (!stopFlag.get()) {
                if (totalFrames > 0 && framesProduced >= totalFrames) break;
                int waitRc = Kernel32.INSTANCE.WaitForSingleObject(eventHandle, 200);
                if (waitRc != WAIT_OBJECT_0) continue;

                long now = System.nanoTime();
                long deltaNanos = now - lastTickNanos;
                lastTickNanos = now;
                ticksSinceLog++;
                if (deltaNanos > maxTickNanos) maxTickNanos = deltaNanos;
                if (deltaNanos > underrunThresholdNanos) {
                    underrunCount++;
                    if (deltaNanos > worstLagNanos) worstLagNanos = deltaNanos;
                }

                hr = callHR(audioClient, VT_AC_GET_CURRENT_PADDING, padding);
                if (hr != S_OK) {
                    log.warn("GetCurrentPadding: 0x{}", Integer.toHexString(hr));
                    continue;
                }
                int avail = bufferFrames - padding.getValue();
                if (avail < minAvailObserved) minAvailObserved = avail;
                if (avail > 0 && avail < bufferFrames) partialFillCount++;
                if (avail <= 0) continue;

                int frames = avail;
                if (totalFrames > 0) {
                    long remaining = totalFrames - framesProduced;
                    if (remaining < frames) frames = (int) remaining;
                }
                long fillStart = System.nanoTime();
                framesProduced += fillNextBlock(generator, scratch, frames);
                long fillNanos = System.nanoTime() - fillStart;
                if (fillNanos > fillBudgetNanos) {
                    slowFillCount++;
                    if (fillNanos > worstFillNanos) worstFillNanos = fillNanos;
                }

                if (now - lastLogNanos >= LOG_INTERVAL_NANOS) {
                    // Always log so we know the loop is alive and what
                    // the per-window stats look like, even when no
                    // anomaly was detected.
                    log.info("WASAPI render 5s: ticks={}, maxTick={}µs, late={}, partialFills={}, minAvail={}, slowFills={} (worstFill={}µs)",
                            ticksSinceLog, maxTickNanos / 1000,
                            underrunCount, partialFillCount,
                            (minAvailObserved == Integer.MAX_VALUE) ? -1 : minAvailObserved,
                            slowFillCount, worstFillNanos / 1000);
                    underrunCount     = 0;
                    slowFillCount     = 0;
                    worstLagNanos     = 0;
                    worstFillNanos    = 0;
                    partialFillCount  = 0;
                    minAvailObserved  = Integer.MAX_VALUE;
                    maxTickNanos      = 0;
                    ticksSinceLog     = 0;
                    lastLogNanos      = now;
                }
            }
        } finally {
            try { callHR(audioClient, VT_AC_STOP); } catch (Throwable t) {
                log.warn("IAudioClient.Stop: {}", t.getMessage());
            }
            log.info("WASAPI playback stopped.");
        }
    }

    /**
     * Fills {@code frames} of audio into a WASAPI render-side buffer
     * (acquired with {@code GetBuffer}, released with
     * {@code ReleaseBuffer}) using a native scratch {@link Memory} as
     * staging.  Returns the frame count actually written so the caller
     * can advance its totals.
     */
    /**
     * Pre-runs {@link #encodeIntoScratch} for ~500 ms so C2 finishes
     * compiling (and any speculation-driven deopts settle) before the
     * device starts pulling real audio.  Uses the same scratch buffer
     * the render loop will use, so the JIT sees an identical call site
     * to the steady-state one.
     */
    private void warmupJit(SignalGenerator gen, byte[] scratch) {
        long deadline = System.nanoTime() + 500_000_000L;
        int  iterations = 0;
        while (System.nanoTime() < deadline) {
            encodeIntoScratch(gen, scratch, bufferFrames);
            iterations++;
        }
        log.info("WASAPI JIT warmup: {} iterations ({} samples)",
                 iterations, iterations * bufferFrames);
    }

    private int fillNextBlock(SignalGenerator gen, byte[] scratch, int frames) {
        PointerByReference ppBuf = new PointerByReference();
        int hr = callHR(renderClient, VT_RC_GET_BUFFER, frames, ppBuf);
        if (hr != S_OK || ppBuf.getValue() == null) {
            log.warn("IAudioRenderClient.GetBuffer: 0x{}", Integer.toHexString(hr));
            return 0;
        }
        Pointer dst = ppBuf.getValue();
        encodeIntoScratch(gen, scratch, frames);
        // Single Java→native copy directly into WASAPI's mapped buffer.
        dst.write(0, scratch, 0, frames * bytesPerFrame);
        callHR(renderClient, VT_RC_RELEASE_BUFFER, frames, 0);
        return frames;
    }

    /**
     * Generates {@code frames} stereo PCM samples (same signal both
     * channels) directly into the reusable {@code scratch} byte array,
     * encoded as little-endian signed PCM matching {@link #bitDepth}.
     * No allocation on the audio hot path.
     */
    private void encodeIntoScratch(SignalGenerator gen, byte[] scratch, int frames) {
        if (bitDepth == 8) {
            // WASAPI 8-bit PCM is signed (unlike JavaSound's unsigned 8-bit).
            for (int i = 0; i < frames; i++) {
                double sample = clamp(gen.nextSample() + tpdfNoise());
                byte   val    = (byte) Math.round(sample * 127.0);
                int    offset = i * bytesPerFrame;
                scratch[offset]     = val;
                scratch[offset + 1] = val;
            }
        } else {
            long maxVal = (1L << (bitDepth - 1)) - 1;
            for (int i = 0; i < frames; i++) {
                double sample = clamp(gen.nextSample() + tpdfNoise());
                long   pcm    = (long) Math.round(sample * maxVal);
                int    offset = i * bytesPerFrame;
                for (int b = 0; b < bytesPerSample; b++) {
                    byte byteVal = (byte) (pcm >> (8 * b));
                    scratch[offset + b]                  = byteVal;
                    scratch[offset + bytesPerSample + b] = byteVal;
                }
            }
        }
    }

    @Override
    public void setDitherBits(int bits) {
        this.ditherBits = Math.max(0, bits);
    }

    private double tpdfNoise() {
        if (ditherBits == 0) return 0.0;
        return (rng.nextDouble() - rng.nextDouble()) / (1L << (ditherBits - 1));
    }

    private double clamp(double v) {
        return v > 1.0 ? 1.0 : (v < -1.0 ? -1.0 : v);
    }

    @Override
    public void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        if (renderClient != null) { release(renderClient); renderClient = null; }
        if (audioClient  != null) { release(audioClient);  audioClient  = null; }
        if (immDevice    != null) { release(immDevice);    immDevice    = null; }
        if (eventHandle  != null) { Kernel32.INSTANCE.CloseHandle(eventHandle); eventHandle = null; }
    }

}
