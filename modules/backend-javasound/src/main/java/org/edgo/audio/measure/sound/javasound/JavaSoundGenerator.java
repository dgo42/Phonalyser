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

package org.edgo.audio.measure.sound.javasound;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;

import lombok.extern.log4j.Log4j2;
import org.edgo.audio.measure.enums.OutputChannels;
import org.edgo.audio.measure.generator.SignalGenerator;
import org.edgo.audio.measure.sound.AudioPlayback;
import org.edgo.audio.measure.sound.PcmQuantizer;

/**
 * Generator that streams a {@link SignalGenerator} to the JVM's default
 * {@link SourceDataLine} - the same audio path
 * {@code GeneratorController} uses for file playback.  JavaSound dispatches
 * to whichever mixer is registered (csjsound's WASAPI-exclusive provider
 * if present, Windows default otherwise), and the realtime render work
 * happens inside that mixer's native code instead of in our Java thread.
 *
 * <p>This is the experimental "use FilePlay's path for DDS" route: zero
 * JNA round-trips per chunk, no event-handle wait loop, no
 * {@code GetBuffer / ReleaseBuffer} timing window for the driver to
 * underrun.  Effectively offloads the realtime audio scheduling to the
 * same code that already produces gap-free file playback on the user's
 * device.
 */
@Log4j2
public class JavaSoundGenerator implements AudioPlayback {

    private static final int CHANNELS      = 2;
    private static final int BUFFER_FRAMES = 4096;
    /** Floor for the no-progress deadline.  The real deadline is three BLOCK
     *  times, derived per session in {@link #play(SignalGenerator, AtomicBoolean,
     *  CountDownLatch)}: in steady state the device drains one block per block
     *  time and admission opens at exactly that cadence (85 ms at 48 kHz,
     *  21 ms at 192 kHz), so several missed cadences mean the device has
     *  stopped draining - full stop.  The floor used to be 200 ms, but the
     *  PACED writer checks admission every few ms and catches every transient
     *  the old blocking write silently absorbed - a VM/USB hiccup of 205 ms
     *  killed a healthy lane on the bench server, and the 500 ms hardware ring
     *  rides such stalls out inaudibly anyway.  Both 200 ms and 500 ms are too
     *  fragile a floor for that reason, so it is one second. */
    private static final long MIN_WRITE_DEADLINE_NANOS = 1_000_000_000L;
    /** How often the watchdog looks; fine enough to notice inside the deadline,
     *  coarse enough to cost nothing. */
    private static final long WATCHDOG_POLL_MS = 200L;
    /** How long the paced writer sleeps when the line has no room - the
     *  admission poll of the non-blocking write discipline (csjsound's blocking
     *  write parks the render thread inside the driver HOLDING the line monitor,
     *  so an unplug mid-write wedged the thread beyond release; pacing on
     *  {@code available()} keeps the thread out of the driver).  A few ms: far
     *  inside one block time at any rate, so admission is never the cadence
     *  bottleneck. */
    private static final long WRITE_POLL_MS = 3L;

    private final int          sampleRate;
    private final int          bitDepth;
    private final int          bytesPerFrame;
    private final String       deviceName;   // null = JavaSound default mixer
    private final PcmQuantizer quantizer;
    private final JavaSoundDeviceManager deviceManager;

    private SourceDataLine   line;
    /** Raised (once, CAS) when the device stopped accepting audio - by the
     *  render loop's short-write check or by the watchdog - so the render loop
     *  ends rather than going round again on a dead line.  {@code play()}
     *  consumes it after the loop and THROWS the recorded {@link #lostDetail}
     *  up: the caller that started the lane owns the failure.  When the write
     *  is wedged INSIDE the driver the play thread can never return to throw -
     *  then the loss surfaces at stop, where the lane abandons the wedged
     *  thread. */
    private final AtomicBoolean laneLost = new AtomicBoolean();
    /** What exactly the driver did, recorded by whichever detector won the
     *  {@link #laneLost} CAS; carried up in the exception. */
    private volatile String lostDetail;
    /** When the last block was accepted by the line.  Written by the render
     *  thread, read by the watchdog. */
    private volatile long lastWriteNanos;

    public JavaSoundGenerator(int sampleRate, int bitDepth, double ditherBits,
                              JavaSoundDeviceManager deviceManager) {
        this(sampleRate, bitDepth, ditherBits, null, deviceManager);
    }

    /**
     * @param deviceName    output device name to honour (matched against
     *                      {@link Mixer.Info#getName()} substring) - pass
     *                      {@code null} to let the platform default mixer be used.
     * @param deviceManager opens the {@link SourceDataLine} on the mixer whose
     *                      name matches {@code deviceName} (or the default).
     */
    public JavaSoundGenerator(int sampleRate, int bitDepth, double ditherBits, String deviceName,
                              JavaSoundDeviceManager deviceManager) {
        this.sampleRate     = sampleRate;
        this.bitDepth       = bitDepth;
        this.deviceName     = deviceName;
        this.bytesPerFrame  = (bitDepth / 8) * CHANNELS;
        this.quantizer      = new PcmQuantizer(bitDepth, ditherBits);
        this.deviceManager  = deviceManager;
    }

    @Override
    public void open() throws LineUnavailableException {
        AudioFormat fmt = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate, bitDepth, CHANNELS,
                bytesPerFrame, sampleRate, false);
        line = deviceManager.openOutputLine(deviceName, fmt);
        int hwFrames = line.getBufferSize() / bytesPerFrame;
        if (log.isInfoEnabled()) {
            log.info("JavaSound generator opened: format={}, hw-buffer={} frames ({} ms)",
                    fmt, hwFrames, hwFrames * 1000 / sampleRate);
        }
        if (quantizer.getDitherBits() > 0) log.info("Dithering: TPDF {} bit", quantizer.getDitherBits());
    }

    @Override
    public void play(SignalGenerator generator, int durationSeconds) {
        byte[] buf           = new byte[BUFFER_FRAMES * bytesPerFrame];
        long   totalFrames   = (long) sampleRate * durationSeconds;
        long   framesWritten = 0;
        line.start();
        log.info("JavaSound playback started ({} s).", durationSeconds);
        while (framesWritten < totalFrames) {
            int frames = (int) Math.min(BUFFER_FRAMES, totalFrames - framesWritten);
            fillBuffer(generator, buf, frames);
            line.write(buf, 0, frames * bytesPerFrame);
            framesWritten += frames;
        }
        line.drain();
        line.stop();
        log.info("JavaSound playback finished.");
    }

    @Override
    public void play(SignalGenerator generator, AtomicBoolean stopFlag, CountDownLatch readyLatch) {
        byte[] buf            = new byte[BUFFER_FRAMES * bytesPerFrame];
        int    hwFrames       = line.getBufferSize() / bytesPerFrame;
        int    preFillCount   = Math.max(1, hwFrames / BUFFER_FRAMES);

        if (generator.needsJitWarmup()) {
            warmupJit(generator, buf);
            // Warmup consumed nextSample() calls that advance sweep playback
            // state; reset so the real stream sees the sweep from sample 0
            // (otherwise a freq-response measurement captures a mid-sweep
            // recording while the deconv reference starts at zero).
            generator.resetSweepPosition();
        }

        laneLost.set(false);
        lostDetail = null;
        lastWriteNanos = System.nanoTime();
        line.start();
        log.info("JavaSound playback started (continuous) - pre-filling {} frames ({} ms)...",
                preFillCount * BUFFER_FRAMES,
                preFillCount * BUFFER_FRAMES * 1000 / sampleRate);

        // Three write cadences of silence = the device stopped draining.  See
        // MIN_WRITE_DEADLINE_NANOS for why the hardware buffer size is NOT a
        // term here: ~256 ms at 48 kHz, the 200 ms floor at 192 kHz, and still
        // honest at 8 kHz where one block alone is half a second.
        long blockNanos    = BUFFER_FRAMES * 1_000_000_000L / sampleRate;
        long deadlineNanos = Math.max(MIN_WRITE_DEADLINE_NANOS, 3L * blockNanos);

        AtomicBoolean watchdogStop = new AtomicBoolean();
        Thread watchdog = startWriteWatchdog(stopFlag, watchdogStop, deadlineNanos);
        try {
            for (int i = 0; i < preFillCount && !laneLost.get(); i++) {
                writeBlock(generator, stopFlag, buf, deadlineNanos);
            }
            readyLatch.countDown();

            while (!stopFlag.get() && !laneLost.get()) {
                writeBlock(generator, stopFlag, buf, deadlineNanos);
            }
        } finally {
            watchdogStop.set(true);
            try {
                // Bounded, and it is a thread WE started and still hold: it polls
                // a flag, so it always comes back well inside this.
                watchdog.join(2L * WATCHDOG_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (laneLost.get()) {
            // Nothing to drain into a device that is gone, and drain() on one
            // waits for a buffer that will never empty.  close() (bounded, from
            // the caller's finally) is the only teardown left that means anything.
            // The loss travels UP as this exception, on this play thread - the
            // caller that started the lane owns the failure.
            String detail = lostDetail != null ? lostDetail
                    : "the output device stopped accepting audio";
            throw new IllegalStateException("JavaSound playback on '" + deviceName
                    + "' ended: " + detail);
        }
        drainAndStop();
        log.info("JavaSound playback stopped.");
    }

    /** The confession for the WEDGED face of an unplug: the render thread is
     *  stuck inside {@code nWrite} (which holds the line monitor, so nothing
     *  can release it) and the exception above can never travel - the
     *  watchdog's verdict is then the only voice this lane has left.  The
     *  lane above folds it into its ended-from-below state when consulted. */
    @Override
    public Throwable lostFromBelow() {
        if (!laneLost.get()) {
            return null;
        }
        String detail = lostDetail != null ? lostDetail
                : "the output device stopped accepting audio";
        return new IllegalStateException("JavaSound playback on '" + deviceName
                + "' ended: " + detail);
    }

    /** One block, PACED on the line's admission instead of blocking in it: only
     *  what {@code available()} says fits is ever written - whole frames - and
     *  when nothing fits the loop sleeps
     *  {@link #WRITE_POLL_MS} instead of entering the driver.  csjsound's
     *  blocking write holds the line monitor across the native call, so an
     *  unplug mid-write used to wedge the render thread beyond release
     *  (close/flush queue behind the same monitor); a paced writer keeps the
     *  monitor free and the thread always able to leave.
     *
     *  <p>An unplugged device has two faces here, and each keeps its own
     *  detection.  Nothing admitted for the write deadline - the loop declares
     *  the lane lost itself now (the stamp below moves only when bytes went
     *  in; the outside watchdog stays as the backstop for a write that blocks
     *  DESPITE fitting, the available()/write race).  And a fitting write
     *  RETURNING SHORT (0 of the 16384 admitted bytes accepted) is the line
     *  telling us it was stopped, flushed or closed under
     *  us - the driver itself saying the lane is dead: declared at once, no
     *  timer involved. */
    private void writeBlock(SignalGenerator generator, AtomicBoolean stopFlag, byte[] buf,
                            long deadlineNanos) {
        fillBuffer(generator, buf, BUFFER_FRAMES);
        int requested = BUFFER_FRAMES * bytesPerFrame;
        int offset = 0;
        while (offset < requested && !stopFlag.get() && !laneLost.get()) {
            int room = (line.available() / bytesPerFrame) * bytesPerFrame;
            int chunk = Math.min(room, requested - offset);
            if (chunk > 0) {
                int written = line.write(buf, offset, chunk);
                if (written > 0) {
                    offset += written;
                    lastWriteNanos = System.nanoTime();
                }
                if (written == chunk) {
                    continue;
                }
                if (stopFlag.get()) {
                    return;         // our own stop released the write - benign
                }
                declareLost("playback accepted " + written + " of " + chunk
                        + " admitted bytes - the line is no longer taking audio");
                return;
            }
            if (System.nanoTime() - lastWriteNanos > deadlineNanos) {
                declareLost("playback admitted nothing for "
                        + ((System.nanoTime() - lastWriteNanos) / 1_000_000L)
                        + " ms - the device stopped draining");
                return;
            }
            try {
                Thread.sleep(WRITE_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** The one death verdict, CAS-guarded so whichever detector fires first
     *  (admission deadline, short write, or the watchdog backstop) records
     *  the reason exactly once. */
    private void declareLost(String detail) {
        if (laneLost.compareAndSet(false, true)) {
            lostDetail = detail;
            if (log.isErrorEnabled()) {
                log.error("JavaSound playback on '{}' is over: {} - the device was "
                        + "unplugged or its driver stopped consuming", deviceName, detail);
            }
        }
    }

    /**
     * Watches the render loop from outside it, because from inside there is
     * nothing to see: {@code SourceDataLine.write} blocks until the device has
     * taken every byte, and a device that has been unplugged takes none ever
     * again.  The render thread is then stuck in a native frame that cannot be
     * interrupted, the tone has stopped, and - before this - nothing said so:
     * the play thread never returned, the pane's Play button stayed lit, and the
     * next Play was refused for ever because the previous "playback" was still
     * shutting down.
     *
     * <p>On expiry it does the two things that are actually available.  It
     * records what happened in {@link #lostDetail}, so a render loop that can
     * still return throws it up out of {@code play()}.  And it raises
     * {@link #laneLost} so the render loop ends instead of writing into a dead
     * line again.  It deliberately does NOT call {@code flush()} - the API's
     * documented release for a write blocked in another thread - because this
     * provider makes that a lie: see the comment at the call site it was removed
     * from, in the loop below.  The blocked render thread itself is unreleasable
     * and is abandoned by the controller as a wedged lane.
     */
    private Thread startWriteWatchdog(AtomicBoolean stopFlag, AtomicBoolean watchdogStop,
                                      long deadlineNanos) {
        Thread watchdog = new Thread(() -> {
            try {
                while (!watchdogStop.get() && !stopFlag.get()) {
                    Thread.sleep(WATCHDOG_POLL_MS);
                    if (watchdogStop.get() || stopFlag.get()) {
                        return;
                    }
                    long silentNanos = System.nanoTime() - lastWriteNanos;
                    if (silentNanos < deadlineNanos) {
                        continue;
                    }
                    // Word-for-word the shape of the capture side's stall detail
                    // ("capture delivered nothing for N ms"), direction swapped -
                    // both lanes' deaths must read as one family in the log;
                    // otherwise the generator reports the same unplug with a
                    // wholly different message than the capture does.
                    declareLost("playback accepted nothing for "
                            + (silentNanos / 1_000_000L) + " ms");
                    // NO flush.  The documented release for a write blocked in
                    // another thread cannot work on this provider: csjsound's
                    // write HOLDS the line's monitor across the blocking native
                    // call and its flush is synchronized on the same monitor, so
                    // the flush only queues behind the very call it was meant to
                    // release - whichever thread issues it is then wedged too
                    // (a thread dump shows flush BLOCKED in
                    // SimpleDataLine.flush on the monitor generator-play holds
                    // inside nWrite).  The render thread is unreleasable; the
                    // lane above already abandons it as wedged.
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable th) {
                // Thread boundary: a watchdog that dies silently is a watchdog
                // that is not watching, and nothing above would ever know.
                log.error("JavaSound playback watchdog failed: {}", th.toString(), th);
            }
        }, "javasound-write-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        return watchdog;
    }

    /** The normal end of a tone: let the queued audio play out, then stop.
     *  Both wait on the device, and both run on the play thread - whose
     *  boundedness lives one level up: the lane joins it with a two-second
     *  budget and abandons it if the driver will not let it go. */
    private void drainAndStop() {
        SourceDataLine open = line;
        if (open == null) {
            return;
        }
        open.drain();
        open.stop();
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
        SourceDataLine open = line;
        line = null;
        if (open != null) {
            // Called from the play thread's finally - the thread the lane joins
            // with its own bounded budget and abandons if the driver holds it.
            open.close();
        }
        // The line is no longer ours: put the mixer back as the open found it.
        deviceManager.restoreOutputVolume(deviceName);
    }

    private void warmupJit(SignalGenerator gen, byte[] buf) {
        long deadline = System.nanoTime() + 500_000_000L;
        int  iterations = 0;
        while (System.nanoTime() < deadline) {
            fillBuffer(gen, buf, BUFFER_FRAMES);
            iterations++;
        }
        log.info("JavaSound JIT warmup: {} iterations ({} samples)",
                 iterations, iterations * BUFFER_FRAMES);
    }

    private void fillBuffer(SignalGenerator gen, byte[] buf, int frames) {
        quantizer.encode(gen, buf, frames);
    }
}
