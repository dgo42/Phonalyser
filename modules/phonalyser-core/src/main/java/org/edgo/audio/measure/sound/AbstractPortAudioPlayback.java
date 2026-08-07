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

package org.edgo.audio.measure.sound;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.edgo.audio.measure.common.Closeables;
import org.edgo.audio.measure.enums.OutputChannels;
import org.edgo.audio.measure.generator.SignalGenerator;

import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import lombok.extern.log4j.Log4j2;

/**
 * Shared base for the PortAudio callback-mode playback backends
 * ({@link WdmksGenerator} on Windows WDM-KS, {@link CoreAudioGenerator} on
 * macOS).  Both host APIs drive an identical render path - PortAudio invokes
 * {@link #paCallback} on its realtime audio thread, which pulls samples from
 * the active {@link SignalGenerator} straight into the output pointer - so the
 * entire body lives here; subclasses differ only by the device index/name they
 * pass up and a backend label used in log messages.
 */
@Log4j2
public abstract class AbstractPortAudioPlayback implements AudioPlayback {

    private static final int CHANNELS = 2;

    private final int          paDeviceIndex;
    private final String       deviceName;
    private final String       backendLabel;
    private final int          sampleRate;
    private final int          bitDepth;
    private final PcmQuantizer quantizer;
    private final int          bytesPerFrame;

    private Pointer stream;

    private volatile SignalGenerator currentGenerator;
    private volatile long            totalFrames;   // 0 = unlimited (continuous)
    private volatile AtomicBoolean   currentStopFlag;
    private final    AtomicLong      framesProduced = new AtomicLong();
    private          byte[]          scratch;       // touched only from callback thread
    /** PortAudio paOutputUnderflow / Overflow counters since the last supervisor drain. */
    private final    AtomicLong      paOutputUnderflowCount = new AtomicLong();
    private final    AtomicLong      paOutputOverflowCount  = new AtomicLong();
    /** Set once the audio callback has logged a fault, so a failure that
     *  repeats every callback (e.g. the generator throwing on every block)
     *  logs one stack trace instead of flooding the log at the callback rate. */
    private final    AtomicBoolean   callbackFaultLogged    = new AtomicBoolean();

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
            try {
                fillBuffer(gen, scratch, frames);
            } catch (Throwable th) {
                // A throw here would otherwise be swallowed by JNA (stderr +
                // return 0), muting output while the stream still reports
                // active - the "generator looks on but nothing comes out"
                // wedge.  Log the cause once, emit silence, keep the stream
                // alive so the supervisor can stop it cleanly.
                if (callbackFaultLogged.compareAndSet(false, true)) {
                    log.error("{} generator callback failed - muting output: {}", backendLabel, th.toString(), th);
                }
                Arrays.fill(scratch, 0, bytes, (byte) 0);
            }
            output.write(0, scratch, 0, bytes);
            long produced = framesProduced.addAndGet(frames);
            if (totalFrames > 0 && produced >= totalFrames) return PortAudio.paComplete;
            AtomicBoolean sf2 = currentStopFlag;
            if (sf2 != null && sf2.get())                   return PortAudio.paComplete;
            return PortAudio.paContinue;
        }
    };

    protected AbstractPortAudioPlayback(int paDeviceIndex, String deviceName, String backendLabel,
                                        int sampleRate, int bitDepth, double ditherBits) {
        this.paDeviceIndex = paDeviceIndex;
        this.deviceName    = deviceName;
        this.backendLabel  = backendLabel;
        this.sampleRate    = sampleRate;
        this.bitDepth      = bitDepth;
        this.quantizer     = new PcmQuantizer(bitDepth, ditherBits);
        this.bytesPerFrame = (bitDepth / 8) * CHANNELS;
    }

    @Override
    public void open() {
        PortAudio.Lib lib = PortAudio.lib();

        PortAudio.PaDeviceInfo info = lib.Pa_GetDeviceInfo(paDeviceIndex);
        // Use the device's own default-high output latency (more headroom
        // against GC pauses / scheduler jitter than defaultLow*) and let
        // PortAudio pick the natural host buffer size.  On WDM-KS this also
        // avoids the post-priming stall a 0 latency / fought framesPerBuffer
        // can trigger.
        double suggestedLatency = (info != null && info.defaultHighOutputLatency > 0)
                ? info.defaultHighOutputLatency : 0.05;

        PortAudio.PaStreamParameters out = new PortAudio.PaStreamParameters();
        out.device                    = paDeviceIndex;
        out.channelCount              = CHANNELS;
        out.sampleFormat              = PortAudio.paSampleFormatFor(bitDepth);
        out.suggestedLatency          = suggestedLatency;
        out.hostApiSpecificStreamInfo = null;
        out.write();

        PortAudio.check(lib.Pa_IsFormatSupported(null, out, sampleRate),
                "Pa_IsFormatSupported(output " + sampleRate + " Hz / " + bitDepth + " bit)");

        PointerByReference handle = new PointerByReference();
        // Counted BEFORE the open, so a device-list refresh can never call
        // Pa_Terminate while this open is in flight - see PortAudio#streamOpening.
        PortAudio.streamOpening();
        int rc = lib.Pa_OpenStream(handle,
                null, out,
                sampleRate,
                PortAudio.paFramesPerBufferUnspecified,
                PortAudio.paClipOff,
                paCallback, null);
        if (rc != PortAudio.paNoError) {
            PortAudio.streamClosed();
        }
        PortAudio.check(rc, "Pa_OpenStream(output)");
        stream = handle.getValue();

        log.info("{} generator opened : {}", backendLabel, deviceName);
        log.info("Output rate/bits   : {} Hz / {} bit", sampleRate, bitDepth);
        log.info("Suggested latency  : {} ms (host-picked buffer size)",
                Math.round(suggestedLatency * 1000));
        if (quantizer.getDitherBits() > 0) log.info("Dithering          : TPDF {} bit", quantizer.getDitherBits());
    }

    @Override
    public void play(SignalGenerator generator, int durationSeconds) {
        currentGenerator = generator;
        totalFrames      = (long) sampleRate * durationSeconds;
        currentStopFlag  = null;
        framesProduced.set(0);

        PortAudio.check(PortAudio.lib().Pa_StartStream(stream), "Pa_StartStream(output)");
        log.info("{} playback started.", backendLabel);
        boolean deviceVanished = false;
        try {
            long nextUnderrunLog   = System.nanoTime() + 1_000_000_000L;
            long nextPresenceCheck = nextUnderrunLog;
            while (PortAudio.lib().Pa_IsStreamActive(stream) == 1) {
                Thread.sleep(50);
                nextUnderrunLog = maybeLogPaOutputGaps(nextUnderrunLog);
                long now = System.nanoTime();
                if (now >= nextPresenceCheck) {
                    nextPresenceCheck = now + 1_000_000_000L;
                    if (!deviceStillPresent()) {
                        deviceVanished = true;
                        break;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // The loop ends when the stream goes inactive, which happens for two
        // quite different reasons: the callback returned paComplete because it
        // had produced every frame asked for (ours), or the device ended it
        // (not ours).  The frame count is what tells them apart - except for a
        // device that left the machine under a stream the host API keeps
        // nominally active, whose callback goes on counting frames into the
        // void; that case is the presence check's own verdict.
        IllegalStateException endedByItself = deviceVanished
                ? streamEnded("the device left the machine")
                : (framesProduced.get() < totalFrames)
                ? streamEnded("after " + framesProduced.get() + " of " + totalFrames + " frames")
                : null;
        haltStream();
        currentGenerator = null;
        if (endedByItself != null) throw endedByItself;
        log.info("{} playback finished.", backendLabel);
    }

    @Override
    public void play(SignalGenerator generator, AtomicBoolean stopFlag, CountDownLatch readyLatch) {
        currentGenerator = generator;
        totalFrames      = 0;            // unlimited
        currentStopFlag  = stopFlag;
        framesProduced.set(0);

        // JIT warmup - see CsjsoundGenerator#warmupJit for the rationale.
        // We can't influence PortAudio's callback thread directly, but
        // running fillBuffer here on the GUI thread forces C2 to compile
        // it before PA's audio thread first calls it.  Same compiled
        // method body is then used by the callback.
        if (generator.needsJitWarmup()) {
            warmupJit(generator);
            // Warmup consumed nextSample() calls that advance sweep playback
            // state; reset so the real stream sees the sweep from sample 0
            // (otherwise a freq-response measurement captures a mid-sweep
            // recording while the deconv reference starts at zero).
            generator.resetSweepPosition();
        }

        PortAudio.check(PortAudio.lib().Pa_StartStream(stream), "Pa_StartStream(output)");
        log.info("{} playback started (continuous, callback mode).", backendLabel);
        readyLatch.countDown();

        boolean deviceVanished = false;
        try {
            long nextUnderrunLog   = System.nanoTime() + 1_000_000_000L;
            long nextPresenceCheck = nextUnderrunLog;
            while (!stopFlag.get() && PortAudio.lib().Pa_IsStreamActive(stream) == 1) {
                Thread.sleep(50);
                nextUnderrunLog = maybeLogPaOutputGaps(nextUnderrunLog);
                long now = System.nanoTime();
                if (now >= nextPresenceCheck) {
                    nextPresenceCheck = now + 1_000_000_000L;
                    if (!deviceStillPresent()) {
                        deviceVanished = true;
                        break;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // This loop condition already asked the one question that matters and
        // then threw the answer away: it exits BOTH when the operator stopped
        // the tone and when the stream ended by itself, and returned normally
        // either way - so a device pulled mid-tone left the caller believing it
        // was still playing.  A continuous lane has no frame budget to run out
        // of (totalFrames == 0), so the stop flag is the only reason of OURS -
        // and a device that left the machine under a nominally active stream is
        // the presence check's own verdict.
        IllegalStateException endedByItself = deviceVanished
                ? streamEnded("the device left the machine")
                : !stopFlag.get()
                ? streamEnded("the tone was still running")
                : null;
        haltStream();
        currentGenerator = null;
        if (endedByItself != null) throw endedByItself;
        log.info("{} playback stopped.", backendLabel);
    }

    /**
     * Whether this stream's DEVICE is still on the machine - asked once a
     * second while playback waits, because a host API may leave the stream
     * nominally ACTIVE after the device is pulled (macOS does, aggregate
     * devices included), and the wait loops above would then run for ever on
     * a tone nobody hears.  The base cannot answer it - PortAudio's device
     * snapshot never changes - so the default is {@code true} and a backend
     * with a live device source overrides.
     */
    protected boolean deviceStillPresent() {
        return true;
    }

    /** The open {@code PaStream*}, for a subclass whose host API needs its own
     *  teardown discipline - null outside open/close. */
    protected final Pointer stream() {
        return stream;
    }

    /**
     * Halts the stream at teardown.  The default is {@code Pa_StopStream},
     * which drains what was already written.  A host API whose stop can BLOCK
     * for ever - CoreAudio waits on a stop semaphore a vanished device never
     * signals, hanging the play thread so the close is abandoned and the open
     * stream vetoes every device-list rebuild until the process dies -
     * overrides with its own discipline.
     */
    protected void haltStream() {
        try { PortAudio.lib().Pa_StopStream(stream); } catch (Throwable t) {
            log.warn("Pa_StopStream: {}", t.getMessage());
        }
    }

    /**
     * The stream ended and it was not us - the device was unplugged, or the host
     * API aborted it.  The loss travels UP as the returned exception, thrown out
     * of {@code play()} on the play thread after the stream teardown - the
     * caller that started the lane owns the failure.
     *
     * <p><b>Wrong only in the safe direction.</b>  Both call sites establish
     * first that this end was not one of ours: the continuous lane checks its
     * stop flag (the sole reason its callback ever returns {@code paComplete}),
     * and the fixed-duration lane checks that fewer frames were produced than
     * were asked for.  An interrupt is not mistaken for a loss either - it
     * raises neither condition.  What is NOT promised is the converse: a host
     * API that leaves the stream nominally active after the device is pulled is
     * never noticed here, and playback goes on silently as it did before.
     */
    private IllegalStateException streamEnded(String detail) {
        log.error("{} playback stream ended by itself - {}; the device was "
                + "unplugged or the host API aborted it", backendLabel, detail);
        return new IllegalStateException(
                backendLabel + " playback stream ended (" + detail + ")");
    }

    /**
     * Drains the PortAudio output-underflow / overflow counters and emits a
     * single rate-limited warning when either is non-zero.  Returns the next
     * nano-time the supervisor should check again.
     */
    private long maybeLogPaOutputGaps(long nextLogNanos) {
        long now = System.nanoTime();
        if (now < nextLogNanos) return nextLogNanos;
        long under = paOutputUnderflowCount.getAndSet(0);
        long over  = paOutputOverflowCount.getAndSet(0);
        if (under > 0 || over > 0) {
            log.warn("{} output gaps: paOutputUnderflow={}, paOutputOverflow={}", backendLabel, under, over);
        }
        return now + 1_000_000_000L;
    }

    /**
     * Pre-runs {@link #fillBuffer} for ~500 ms so C2 finishes compiling (and
     * any speculation-driven deopts settle) before PortAudio's callback thread
     * first invokes it.  1024 frames is a conservative typical PA chunk size;
     * a wrong size still warms the code path, only the inner loop count differs.
     */
    private void warmupJit(SignalGenerator gen) {
        int warmFrames = 1024;
        byte[] warmBuf = new byte[warmFrames * bytesPerFrame];
        long deadline = System.nanoTime() + 500_000_000L;
        int  iterations = 0;
        while (System.nanoTime() < deadline) {
            fillBuffer(gen, warmBuf, warmFrames);
            iterations++;
        }
        log.info("{} JIT warmup: {} iterations ({} samples)",
                 backendLabel, iterations, iterations * warmFrames);
    }

    private void fillBuffer(SignalGenerator gen, byte[] buf, int frames) {
        quantizer.encode(gen, buf, frames);
    }

    @Override
    public void setDitherBits(double bits) {
        quantizer.setDitherBits(bits);
    }

    @Override
    public void setChannelScale(double left, double right) {
        quantizer.setChannelScale(left, right);
    }

    @Override
    public void setOutputChannels(OutputChannels channels) {
        quantizer.setOutputChannels(channels);
    }

    @Override
    public void close() {
        if (stream != null) {
            Closeables.tryQuietly("Pa_CloseStream", () -> PortAudio.lib().Pa_CloseStream(stream));
            PortAudio.streamClosed();
            stream = null;
        }
    }
}
