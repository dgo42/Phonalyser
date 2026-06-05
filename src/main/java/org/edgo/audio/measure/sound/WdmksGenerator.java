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

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.edgo.audio.measure.common.Closeables;
import org.edgo.audio.measure.generator.SignalGenerator;

import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import lombok.extern.log4j.Log4j2;

/**
 * Stereo PCM playback via PortAudio's WDM-KS host API, callback mode.
 * PortAudio invokes {@link #paCallback} on its realtime audio thread,
 * which pulls samples from the active {@link SignalGenerator} straight
 * into the output pointer.  Mirrors {@link JavaSoundGenerator}'s public
 * surface so the iterative compensation flow runs identically through
 * {@link AudioBackend}.
 */
@Log4j2
public class WdmksGenerator implements AudioPlayback {

    private static final int CHANNELS = 2;

    private final WdmksDeviceManager.WdmksDeviceRef device;
    private final int    sampleRate;
    private final int    bitDepth;
    private volatile int ditherBits;
    private int    bytesPerFrame;
    private final Random rng;

    private Pointer stream;

    private volatile SignalGenerator currentGenerator;
    private volatile long            totalFrames;   // 0 = unlimited (continuous)
    private volatile AtomicBoolean   currentStopFlag;
    private final    AtomicLong      framesProduced = new AtomicLong();
    private          byte[]          scratch;       // touched only from callback thread
    /** PortAudio paOutputUnderflow / Underflow counters since the last supervisor drain. */
    private final    AtomicLong      paOutputUnderflowCount = new AtomicLong();
    private final    AtomicLong      paOutputOverflowCount  = new AtomicLong();

    /**
     * Anonymous inner class (not a lambda) so JNA's Callback SAM detection is
     * unambiguous on every JVM version.
     */
    private final PortAudio.PaStreamCallback paCallback = new PortAudio.PaStreamCallback() {
        @Override
        public int callback(Pointer input, Pointer output,
                            NativeLong frameCount,
                            Pointer timeInfo,
                            NativeLong statusFlags,
                            Pointer userData) {
            long flags = (statusFlags == null) ? 0L : statusFlags.longValue();
            if ((flags & PortAudio.paOutputUnderflow) != 0) paOutputUnderflowCount.incrementAndGet();
            if ((flags & PortAudio.paOutputOverflow)  != 0) paOutputOverflowCount.incrementAndGet();
            int frames = frameCount.intValue();
            int bytes  = frames * bytesPerFrame;
            if (scratch == null || scratch.length < bytes) scratch = new byte[bytes];
            SignalGenerator gen = currentGenerator;
            if (gen == null) {
                Arrays.fill(scratch, 0, bytes, (byte) 0);
                output.write(0, scratch, 0, bytes);
                return PortAudio.paComplete;
            }
            fillBuffer(gen, scratch, frames);
            output.write(0, scratch, 0, bytes);
            long produced = framesProduced.addAndGet(frames);
            if (totalFrames > 0 && produced >= totalFrames) return PortAudio.paComplete;
            AtomicBoolean sf2 = currentStopFlag;
            if (sf2 != null && sf2.get())                   return PortAudio.paComplete;
            return PortAudio.paContinue;
        }
    };

    public WdmksGenerator(WdmksDeviceManager.WdmksDeviceRef device,
                          int sampleRate, int bitDepth, int ditherBits) {
        this.device        = device;
        this.sampleRate    = sampleRate;
        this.bitDepth      = bitDepth;
        this.ditherBits    = ditherBits;
        this.bytesPerFrame = (bitDepth / 8) * CHANNELS;
        // Always allocate so live setDitherBits(>0) calls don't NPE in tpdfNoise().
        this.rng           = new Random();
    }

    @Override
    public void open() {
        PortAudio.Lib lib = PortAudio.lib();

        PortAudio.PaDeviceInfo info = lib.Pa_GetDeviceInfo(device.paDeviceIndex());
        // WDM-KS stalls after the priming callback if suggestedLatency is 0 or
        // framesPerBuffer fights the KS pin's preferred size.  Use the device's
        // own default-high latency (more headroom against GC pauses / scheduler
        // jitter than defaultLow*) and let PortAudio pick the natural pin size.
        double suggestedLatency = (info != null && info.defaultHighOutputLatency > 0)
                ? info.defaultHighOutputLatency : 0.05;

        PortAudio.PaStreamParameters out = new PortAudio.PaStreamParameters();
        out.device                    = device.paDeviceIndex();
        out.channelCount              = CHANNELS;
        out.sampleFormat              = PortAudio.paSampleFormatFor(bitDepth);
        out.suggestedLatency          = suggestedLatency;
        out.hostApiSpecificStreamInfo = null;
        out.write();

        PortAudio.check(lib.Pa_IsFormatSupported(null, out, sampleRate),
                "Pa_IsFormatSupported(output " + sampleRate + " Hz / " + bitDepth + " bit)");

        PointerByReference handle = new PointerByReference();
        int rc = lib.Pa_OpenStream(handle,
                null, out,
                sampleRate,
                PortAudio.paFramesPerBufferUnspecified,
                PortAudio.paClipOff,
                paCallback, null);
        PortAudio.check(rc, "Pa_OpenStream(output)");
        stream = handle.getValue();

        log.info("WDM-KS generator opened : {}", device.name());
        log.info("Output rate/bits        : {} Hz / {} bit", sampleRate, bitDepth);
        log.info("Suggested latency       : {} ms (host-picked buffer size)",
                Math.round(suggestedLatency * 1000));
        if (ditherBits > 0) log.info("Dithering               : TPDF {} bit", ditherBits);
    }

    @Override
    public void play(SignalGenerator generator, int durationSeconds) {
        currentGenerator = generator;
        totalFrames      = (long) sampleRate * durationSeconds;
        currentStopFlag  = null;
        framesProduced.set(0);

        PortAudio.check(PortAudio.lib().Pa_StartStream(stream), "Pa_StartStream(output)");
        log.info("WDM-KS playback started.");
        try {
            long nextUnderrunLog = System.nanoTime() + 1_000_000_000L;
            while (PortAudio.lib().Pa_IsStreamActive(stream) == 1) {
                Thread.sleep(50);
                nextUnderrunLog = maybeLogPaOutputGaps(nextUnderrunLog);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try { PortAudio.lib().Pa_StopStream(stream); } catch (Throwable t) {
            log.warn("Pa_StopStream: {}", t.getMessage());
        }
        currentGenerator = null;
        log.info("WDM-KS playback finished.");
    }

    @Override
    public void play(SignalGenerator generator, AtomicBoolean stopFlag, CountDownLatch readyLatch) {
        currentGenerator = generator;
        totalFrames      = 0;            // unlimited
        currentStopFlag  = stopFlag;
        framesProduced.set(0);

        // JIT warmup — see CsjsoundGenerator#warmupJit for the rationale.
        // We can't influence PortAudio's callback thread directly, but
        // running fillBuffer here on the GUI thread forces C2 to compile
        // it before PA's audio thread first calls it.  Same compiled
        // method body is then used by the callback.
        warmupJit(generator);
        // Warmup consumed nextSample() calls that advance sweep playback
        // state; reset so the real stream sees the sweep from sample 0
        // (otherwise a freq-response measurement captures a mid-sweep
        // recording while the deconv reference starts at zero).
        generator.resetSweepPosition();

        PortAudio.check(PortAudio.lib().Pa_StartStream(stream), "Pa_StartStream(output)");
        log.info("WDM-KS playback started (continuous, callback mode).");
        readyLatch.countDown();

        try {
            long nextUnderrunLog = System.nanoTime() + 1_000_000_000L;
            while (!stopFlag.get() && PortAudio.lib().Pa_IsStreamActive(stream) == 1) {
                Thread.sleep(50);
                nextUnderrunLog = maybeLogPaOutputGaps(nextUnderrunLog);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try { PortAudio.lib().Pa_StopStream(stream); } catch (Throwable t) {
            log.warn("Pa_StopStream: {}", t.getMessage());
        }
        currentGenerator = null;
        log.info("WDM-KS playback stopped.");
    }

    /**
     * Drains the PortAudio output-underflow / overflow counters and
     * emits a single rate-limited warning when either is non-zero.
     * Returns the next nano-time the supervisor should check again.
     */
    private long maybeLogPaOutputGaps(long nextLogNanos) {
        long now = System.nanoTime();
        if (now < nextLogNanos) return nextLogNanos;
        long under = paOutputUnderflowCount.getAndSet(0);
        long over  = paOutputOverflowCount.getAndSet(0);
        if (under > 0 || over > 0) {
            log.warn("WDM-KS output gaps: paOutputUnderflow={}, paOutputOverflow={}", under, over);
        }
        return now + 1_000_000_000L;
    }

    /**
     * Pre-runs {@link #fillBuffer} for ~500 ms so C2 finishes compiling
     * (and any speculation-driven deopts settle) before PortAudio's
     * callback thread first invokes it.  Buffer is sized to the same
     * frame count the callback uses in steady state — PortAudio picks
     * an arbitrary value at stream-open time, so we use the device's
     * suggested-latency-derived size as a proxy.
     */
    private void warmupJit(SignalGenerator gen) {
        // 1024 frames is a conservative typical PA WDM-KS chunk size.
        // Wrong size still warms the code path; only the inner loop
        // count differs.
        int warmFrames = 1024;
        byte[] warmBuf = new byte[warmFrames * bytesPerFrame];
        long deadline = System.nanoTime() + 500_000_000L;
        int  iterations = 0;
        while (System.nanoTime() < deadline) {
            fillBuffer(gen, warmBuf, warmFrames);
            iterations++;
        }
        log.info("WDM-KS JIT warmup: {} iterations ({} samples)",
                 iterations, iterations * warmFrames);
    }

    private void fillBuffer(SignalGenerator gen, byte[] buf, int frames) {
        int bytesPerSample = bitDepth / 8;

        if (bitDepth == 8) {
            // PortAudio paInt8 is signed [-128, +127]; CsjsoundGenerator uses unsigned 8-bit
            // for JavaSound. Here we emit signed-PCM directly.
            for (int i = 0; i < frames; i++) {
                double sample = clamp(gen.nextSample() + tpdfNoise());
                byte   val    = (byte) Math.round(sample * 127.0);
                int    offset = i * bytesPerFrame;
                buf[offset]     = val; // left
                buf[offset + 1] = val; // right
            }
        } else {
            long maxVal = (1L << (bitDepth - 1)) - 1;
            for (int i = 0; i < frames; i++) {
                double sample = clamp(gen.nextSample() + tpdfNoise());
                long   pcm    = (long) Math.round(sample * maxVal);
                int    offset = i * bytesPerFrame;
                for (int b = 0; b < bytesPerSample; b++) {
                    byte byteVal = (byte) (pcm >> (8 * b));
                    buf[offset + b]                  = byteVal; // left
                    buf[offset + bytesPerSample + b] = byteVal; // right
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
        if (stream != null) {
            Closeables.tryQuietly("Pa_CloseStream", () -> PortAudio.lib().Pa_CloseStream(stream));
            stream = null;
        }
    }
}
