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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.extern.log4j.Log4j2;

import org.edgo.audio.measure.generator.SignalGenerator;
import org.edgo.audio.measure.sound.AudioPlayback;
import org.edgo.audio.measure.sound.PcmQuantizer;

/**
 * The playback lane of the digital loopback - a thin AudioPlayback client that
 * {@code attach}es / {@code detach}es the generator lane on the manager's one
 * {@link LoopbackDuplexEngine}.  This class IS the engine's generator sample
 * source: it quantises the generator to the session's bit depth straight into the
 * block the engine is about to hand to the capture lane.
 *
 * <p>The lane is NEVER undithered: with no configured dither it quantises with
 * TPDF on the last bit of the selected depth - the known floor the backend exists
 * for - and a dither the generator configures overrides that default, live
 * included, exactly as it would on a hardware lane.  The engine's UNATTACHED
 * generator lane applies the same default to digital zero, so the floor is
 * continuous across a start and a stop.
 *
 * <p><b>This lane renders nothing on the play thread.</b>  The samples are pulled
 * from {@link #nextBlock} by the engine's pacer thread, which is the session's one
 * clock, so all the play call does is stay alive for as long as the tone should
 * sound - the same shape the analyzer's lane has.
 */
@Log4j2
public final class LoopbackPlayback implements AudioPlayback,
        LoopbackDuplexEngine.SampleSource {

    /** Stop-flag poll period on the play thread; the samples come off the engine's clock. */
    private static final long STOP_POLL_MILLIS = 50;

    private final LoopbackDeviceManager manager;
    private final int sampleRate;
    private final int bitDepth;
    /** By DEFAULT the dither depth is the SELECTED bit depth: TPDF on the last
     *  bit, which is what makes this backend a bench with a KNOWN noise floor.
     *  A dither the generator configures - the openPlayback argument or the
     *  live setDitherBits call - overrides the default; a zero (dither off)
     *  falls back to it, so the lane is never undithered. */
    private final PcmQuantizer quantizer;

    private LoopbackDuplexEngine engine;
    private volatile SignalGenerator currentGenerator;
    private volatile boolean attached;

    LoopbackPlayback(LoopbackDeviceManager manager, int sampleRate, int bitDepth, double ditherBits) {
        this.manager    = manager;
        this.sampleRate = sampleRate;
        this.bitDepth   = bitDepth;
        this.quantizer  = new PcmQuantizer(bitDepth, effectiveDither(ditherBits));
    }

    /** A configured depth wins; zero (dither off) means the last-bit default -
     *  see the quantiser field. */
    private double effectiveDither(double bits) {
        return bits > 0 ? bits : bitDepth;
    }

    @Override
    public void setDitherBits(double bits) {
        quantizer.setDitherBits(effectiveDither(bits));
    }

    @Override
    public void open() {
        engine = manager.acquireEngine(sampleRate, bitDepth);
        if (log.isInfoEnabled()) {
            log.info("Loopback playback opened : {} Hz, {} bits", sampleRate, bitDepth);
        }
    }

    @Override
    public void play(SignalGenerator generator, int durationSeconds) {
        startLane(generator);
        try {
            Thread.sleep((long) durationSeconds * 1000L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        stopLane();
    }

    @Override
    public void play(SignalGenerator generator, AtomicBoolean stopFlag, CountDownLatch readyLatch) {
        startLane(generator);
        if (readyLatch != null) {
            readyLatch.countDown();   // nothing to pre-fill: the engine is already rendering
        }
        try {
            while (!stopFlag.get()) {
                Thread.sleep(STOP_POLL_MILLIS);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        stopLane();
    }

    @Override
    public void close() {
        stopLane();
    }

    private void startLane(SignalGenerator generator) {
        if (engine == null) {
            engine = manager.acquireEngine(sampleRate, bitDepth);
        }
        currentGenerator = generator;
        // Warmup consumed nextSample() advances sweep state; rewind to sample 0 so a
        // freq-response sweep aligns with the deconvolution reference.
        generator.resetSweepPosition();
        attached = true;
        // ITSELF, not a method reference: the engine's lane source is this object,
        // so a live swap while the session runs replaces the whole lane.
        engine.attachGenerator(this);
    }

    /** Gives the generator lane back.  {@link #attached} records what was
     *  ACHIEVED, not what was attempted, so a teardown that faulted half-way
     *  leaves a stop still to be attempted by the caller's retry. */
    private void stopLane() {
        if (!attached) {
            return;
        }
        engine.detachGenerator();
        attached = false;
        currentGenerator = null;
    }

    /**
     * Engine generator-lane source, on the pacer thread: quantises {@code frames}
     * frames of the generator into {@code destination} as interleaved stereo
     * signed little-endian PCM at the lane's bit depth.
     */
    @Override
    public void nextBlock(byte[] destination, int frames) {
        SignalGenerator gen = currentGenerator;
        if (gen == null) {
            // Between the detach and the lane's own bookkeeping.  Filled from the
            // SESSION's silence, never with digital zeros: this backend's floor is
            // dither on the last bit of the selected depth, and one undithered
            // block reads as minus infinity where every neighbouring block reads
            // the known floor - a hole in the very thing the bench exists to show.
            engine.getSilence().nextBlock(destination, frames);
            return;
        }
        quantizer.encode(gen, destination, frames);
    }
}
