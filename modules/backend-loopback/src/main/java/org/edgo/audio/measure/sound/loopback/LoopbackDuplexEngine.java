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

package org.edgo.audio.measure.sound.loopback;

import java.util.concurrent.atomic.AtomicBoolean;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.generator.SignalGenerator;
import org.edgo.audio.measure.sound.PcmQuantizer;

/**
 * The digital loopback session - ONE always-duplex stream per device manager, on
 * the same session model the QA40x analyzer runs (doc/QA40X-PROTOCOL.md §10):
 * input and output are two lanes of a single stream, and clients attach / detach
 * lanes rather than starting a direction of their own.  The loop has one clock, so
 * a capture and a playback that ran independently would be two clocks pretending
 * to be one.
 *
 * <h2>Lifecycle</h2>
 * <ul>
 *   <li>The FIRST attach ({@link #attachGenerator} or {@link #attachCapture})
 *       starts the session; a LATER attach swaps the lane's source / sink
 *       <b>live</b>, with no restart.</li>
 *   <li>An unattached generator lane produces DITHERED SILENCE, not digital
 *       zeros: the samples go through the lane quantiser at the session depth with
 *       the default last-bit TPDF, which is what makes this backend a bench with a
 *       known noise floor (0.5 LSB RMS at the selected depth) whether or not a
 *       signal happens to be playing.  Dither exists on the PLAYBACK side only -
 *       here as everywhere - so the floor is PRODUCED, and the capture lane is a
 *       pure consumer of whatever the session rendered.</li>
 *   <li>The session has exactly ONE capture consumer.  A second attach with a lane
 *       already live is a wiring bug (two independent capture owners on one
 *       stream); it is refused loudly rather than silently overwriting the first
 *       consumer, which would starve one view and split the single capture
 *       lane.</li>
 *   <li>The LAST detach ends the session.</li>
 *   <li>A format change ({@link #changeFormat}) is a full stop + start while
 *       running: the block sizing and the silence quantiser are the session's.</li>
 * </ul>
 *
 * <h2>What a session without hardware is instead</h2>
 * There is no USB transport under this engine, so the properties the analyzer's
 * session derives from one are simply absent here, and the mechanisms that exist
 * to survive them are absent with them:
 * <ul>
 *   <li><b>The clock is a pacer thread</b> rendering {@value #BLOCKS_PER_SECOND}
 *       blocks per second against an ABSOLUTE schedule (start + n*blockNanos), so
 *       the stream cannot drift the way repeated relative sleeps would; a slot
 *       whose wait has already elapsed is rendered immediately, which is the
 *       catch-up.  On the analyzer a completed USB read is that clock.</li>
 *   <li><b>No write debt, no register sequences, no two-lock discipline.</b>
 *       Nothing here blocks on a device, so there is no lock that may not be held
 *       across a transfer and no pacing backlog to repay.</li>
 *   <li><b>No transfer-failure channel.</b>  Nothing can vanish mid-stream: a
 *       software lane has no device to lose, so neither lane has a failure to
 *       confess and neither carries one.</li>
 *   <li><b>Render then deliver, on the one thread.</b>  The pacer hands each
 *       rendered block SYNCHRONOUSLY to the attached consumer, so a transiently
 *       late consumer cannot arise and there is no queue between the lanes - the
 *       engine IS the pipe.  The block buffer is reused for exactly that reason:
 *       the consumer has read it before the next render can touch it.</li>
 * </ul>
 */
@Log4j2
public final class LoopbackDuplexEngine {

    /** Blocks per second - a 20 ms block.  Far below the capture base class's 2 s
     *  delivery deadline at every rate on the ladder, so a slow rate cannot be
     *  mistaken for a stalled device. */
    private static final int  BLOCKS_PER_SECOND = 50;
    private static final long NANOS_PER_SECOND  = 1_000_000_000L;
    private static final long NANOS_PER_MILLI   = 1_000_000L;
    private static final int  CHANNELS          = 2;
    /** The silence source's nominal tone: at amplitude zero every sample is
     *  exactly 0.0 before quantisation, so what leaves the lane is the dither
     *  floor and nothing else - the frequency only has to exist. */
    private static final double SILENCE_TONE_HZ = 1000.0;

    /** The session format and everything sized from it.  Written only under this
     *  engine's monitor and only while the pacer is stopped, so the pacer reads
     *  them without any lock of its own (the {@code start()} of the thread
     *  publishes them). */
    @Getter
    private int sampleRateHz;
    /** The session's quantiser resolution AND the silence lane's dither depth. */
    @Getter
    private int bitDepth;
    private int blockFrames;
    private int frameSize;
    private byte[] block;
    /** The unattached generator lane: digital zero through the lane quantiser at
     *  the session depth, dithered on its last bit.  Exposed because it is the
     *  session's ONE floor: a lane that has to emit a block with no generator
     *  behind it fills it from here rather than writing digital zeros, so nothing
     *  this backend delivers is ever undithered.  Called only from the pacer
     *  thread, whether by this engine or by the attached lane. */
    @Getter
    private SampleSource silence;

    /** The ATTACHED generator lane, or null when none is - read by the pacer once
     *  per block, so a lane attach / detach is a live swap and needs nothing more
     *  than a single volatile reference write.  Null rather than "the silence"
     *  because the silence belongs to a FORMAT: each pacer renders the one it was
     *  started with, and nothing waits for a departing one to notice a new. */
    private volatile SampleSource attachedSource;
    private volatile CaptureConsumer consumer;
    /** Whether the session is running, i.e. at least one lane is attached. */
    @Getter
    private volatile boolean streaming;

    private boolean generatorAttached;
    private boolean captureAttached;
    /** The RUNNING pacer's own flag, and the reason nothing ever waits for it: the
     *  session's end clears this and returns, the pacer sees it at its current
     *  iteration and exits.  Per-thread rather than one shared flag because a
     *  format change starts a new pacer at once - a departing pacer reading the
     *  engine's {@code streaming} would find it true again and never stop. */
    private AtomicBoolean pacerRun;

    LoopbackDuplexEngine(int sampleRateHz, int bitDepth) {
        applyFormat(sampleRateHz, bitDepth);
    }

    /** Attaches / live-swaps the generator lane's sample source; starts the session if idle. */
    public synchronized void attachGenerator(SampleSource generatorSource) {
        if (generatorSource == null) {
            throw new IllegalArgumentException("generatorSource");
        }
        this.attachedSource = generatorSource;
        generatorAttached = true;
        if (!streaming) {
            startSession();
        }
    }

    /** Detaches the generator lane (reverts to dithered silence); ends the session
     *  if it was the last client. */
    public synchronized void detachGenerator() {
        this.attachedSource = null;
        generatorAttached = false;
        if (streaming && !captureAttached) {
            endSession();
        }
    }

    /**
     * Attaches the capture lane's consumer; starts the session if idle.  One
     * engine has exactly ONE capture consumer - the scope and the FFT share it
     * through {@code SharedCapture} - so a second attach with a lane already live
     * is refused rather than silently replacing the first.
     */
    public synchronized void attachCapture(CaptureConsumer captureConsumer) {
        if (captureConsumer == null) {
            throw new IllegalArgumentException("captureConsumer");
        }
        if (captureAttached) {
            throw new IllegalStateException("loopback capture lane already attached - "
                    + "one duplex engine has a single capture consumer (scope and FFT share it "
                    + "through SharedCapture); refusing to split the stream");
        }
        this.consumer = captureConsumer;
        captureAttached = true;
        if (!streaming) {
            startSession();
        }
    }

    /** Detaches the capture lane (rendered blocks are discarded); ends the session
     *  if it was the last client. */
    public synchronized void detachCapture() {
        this.consumer = null;
        captureAttached = false;
        if (streaming && !generatorAttached) {
            endSession();
        }
    }

    /**
     * Moves the WHOLE session to another rate / depth; a full stop + start while
     * running.  Both fields together, because both size the session: the block
     * frame count comes from the rate, and the frame stride and the silence
     * quantiser from the depth - a session cannot be half at one format.
     *
     * <p>A lane that opens at a format the session is not on is what brings this
     * about - playing a file recorded at another rate, for instance.  The
     * consumer's own advertised {@code AudioFormat} is frozen at its construction,
     * exactly as the analyzer's is: a capture already running keeps delivering,
     * and the application's two directions are kept on one format by the
     * preferences constraint rather than by a notification from here.
     */
    public synchronized void changeFormat(int hz, int bits) {
        boolean running = streaming;
        if (running) {
            endSession();
        }
        applyFormat(hz, bits);
        if (running) {
            startSession();
        }
    }

    /** Sizes everything the session format decides and rebuilds the silence
     *  source.  Caller holds the monitor, and a pacer of the PREVIOUS format may
     *  still be inside its final iteration - nothing waited for it.  Everything
     *  written here is therefore replaced rather than mutated, and the pacer holds
     *  its own format as locals taken at entry (see {@link #pace}), so a departing
     *  thread goes on rendering the format it was started with and cannot mix the
     *  old buffer with the new silence (a deeper one overruns it). */
    private void applyFormat(int hz, int bits) {
        this.sampleRateHz = hz;
        this.bitDepth     = bits;
        this.blockFrames  = Math.max(1, hz / BLOCKS_PER_SECOND);
        // Rounded UP: a depth that is not a whole number of bytes rides
        // right-aligned in the next larger container (20 bits in 3 bytes).
        this.frameSize    = ((bits + 7) / 8) * CHANNELS;
        this.block        = new byte[blockFrames * frameSize];
        // The floor is the DEPTH's own - TPDF on the last bit of the selected
        // depth - and it is produced here, on the playback side, because that is
        // the only side dither exists on.
        PcmQuantizer quantizer = new PcmQuantizer(bits, bits);
        SignalGenerator zero = new SignalGenerator(GenSignalForm.SINE, SILENCE_TONE_HZ, hz, 0.0, 1.0);
        this.silence = (destination, frames) -> quantizer.encode(zero, destination, frames);
    }

    /** Caller holds the monitor.  ONE thread per session: the first lane to attach
     *  starts it and the second simply renders on the one already running. */
    private void startSession() {
        streaming = true;
        AtomicBoolean run = new AtomicBoolean(true);
        pacerRun = run;
        // The format goes to the thread as VALUES, so a later changeFormat cannot
        // reach the one that is departing.
        Thread pacer = new Thread(pacerFor(run, silence, block, blockFrames, frameSize, sampleRateHz),
                "loopback-session");
        pacer.setDaemon(true);
        pacer.start();
        if (log.isInfoEnabled()) {
            log.info("Loopback session started: {} Hz, {} bits", sampleRateHz, bitDepth);
        }
    }

    /** Caller holds the monitor.  NOTHING WAITS: the run flag is cleared and this
     *  returns, the pacer exits at its current iteration.  A detach is called from
     *  a UI thread, and a session with no hardware under it has nothing whose
     *  teardown could be observed - so there is nobody for whom the wait would be
     *  worth the risk of one. */
    private void endSession() {
        streaming = false;
        if (pacerRun != null) {
            pacerRun.set(false);
            pacerRun = null;
        }
        if (log.isInfoEnabled()) {
            log.info("Loopback session stopped.");
        }
    }

    /**
     * The pacer's body: one block per block period against an ABSOLUTE schedule,
     * handed straight to the attached consumer.
     *
     * <p>It is closed over the format it was STARTED with - the buffer, its sizes
     * and the session's silence - because nothing waits for a pacer to stop.  A
     * {@link #changeFormat} therefore reaches only the thread it starts: the one it
     * left running finishes its iteration on the old buffer at the old depth, where
     * a deeper new silence would have overrun it.  The attached lane is read LIVE,
     * which is what makes an attach mid-session a swap rather than a restart.
     */
    private Runnable pacerFor(AtomicBoolean run, SampleSource sessionSilence, byte[] buffer,
                              int frames, int stride, int rateHz) {
        int bytes = frames * stride;
        long blockNanos = (long) frames * NANOS_PER_SECOND / rateHz;
        return () -> {
            long start    = System.nanoTime();
            long rendered = 0;
            try {
                while (run.get()) {
                    rendered++;
                    long waitNanos = (start + rendered * blockNanos) - System.nanoTime();
                    if (waitNanos > 0) {
                        Thread.sleep(waitNanos / NANOS_PER_MILLI, (int) (waitNanos % NANOS_PER_MILLI));
                    }
                    SampleSource live = attachedSource;
                    (live != null ? live : sessionSilence).nextBlock(buffer, frames);
                    CaptureConsumer sink = consumer;
                    if (sink != null) {
                        sink.onAudio(buffer, bytes);
                    }
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        };
    }

    /** Source of PCM for the generator lane. */
    @FunctionalInterface
    public interface SampleSource {
        /**
         * Fills {@code destination} with {@code frames} frames of interleaved
         * stereo signed little-endian PCM at the session's bit depth - the
         * encoding {@link PcmQuantizer} produces, which is what the capture path
         * decodes.  {@code destination} is at least {@code frames} frames long.
         */
        void nextBlock(byte[] destination, int frames);
    }

    /** Sink for the rendered blocks on the capture lane. */
    @FunctionalInterface
    public interface CaptureConsumer {
        /**
         * Receives one rendered block: {@code length} bytes of {@code block},
         * interleaved stereo signed little-endian PCM at the session's bit depth.
         * Consume synchronously; the buffer is reused after return.
         */
        void onAudio(byte[] block, int length);
    }
}
