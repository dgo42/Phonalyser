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
 * The playback lane of the digital loopback.  It quantises the generator to the
 * selected bit depth and hands the blocks to the capture lane instead of to a
 * device, paced to the wall clock so the crossing runs at the sample rate the
 * caller asked for.
 *
 * <p>{@code setDitherBits} is deliberately NOT overridden - the interface's
 * no-op default is the wanted behaviour here, for the reason given at the
 * quantiser below.
 */
@Log4j2
public final class LoopbackPlayback implements AudioPlayback {

    /** Matches the capture lane's block period - one produced block per
     *  consumed block keeps the crossing shallow. */
    private static final int BLOCKS_PER_SECOND = 50;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long NANOS_PER_MILLI  = 1_000_000L;

    private final LoopbackCrossing crossing;
    private final int sampleRate;
    private final int blockFrames;
    private final int frameSize;
    /** The dither depth is derived from the SELECTED bit depth and nothing
     *  else: dithering at bit N puts TPDF on the last bit, which is what makes
     *  this backend a bench with a KNOWN noise floor.  The caller's dither
     *  setting - the openPlayback argument and the live setDitherBits call the
     *  generator lane makes - is therefore not consulted; honouring it would
     *  let the floor move (or vanish) under a measurement. */
    private final PcmQuantizer quantizer;

    private volatile boolean open;

    LoopbackPlayback(int sampleRate, int bitDepth, LoopbackCrossing crossing) {
        this.crossing    = crossing;
        this.sampleRate  = sampleRate;
        this.blockFrames = Math.max(1, sampleRate / BLOCKS_PER_SECOND);
        this.quantizer   = new PcmQuantizer(bitDepth, bitDepth);
        this.frameSize   = ((bitDepth + 7) / 8) * 2;
    }

    @Override
    public void open() {
        open = true;
    }

    @Override
    public void play(SignalGenerator generator, int durationSeconds) {
        AtomicBoolean stop = new AtomicBoolean(false);
        long frames = (long) durationSeconds * sampleRate;
        render(generator, stop, null, frames);
    }

    @Override
    public void play(SignalGenerator generator, AtomicBoolean stopFlag, CountDownLatch readyLatch) {
        render(generator, stopFlag, readyLatch, Long.MAX_VALUE);
    }

    /** Quantises and offers one block per block-period against an ABSOLUTE
     *  schedule, so the lane cannot drift.  A block the crossing refuses (the
     *  capture lane is behind, or absent) is dropped rather than retried: this
     *  lane runs on the clock, exactly as a device would. */
    private void render(SignalGenerator generator, AtomicBoolean stopFlag,
                        CountDownLatch readyLatch, long maxFrames) {
        if (!open) {
            throw new IllegalStateException("Call open() before play()");
        }
        long blockNanos = blockFrames * NANOS_PER_SECOND / sampleRate;
        long start      = System.nanoTime();
        long produced   = 0;
        long framesLeft = maxFrames;
        if (readyLatch != null) {
            readyLatch.countDown();   // nothing to pre-fill: the crossing is ready
        }
        try {
            while (!stopFlag.get() && framesLeft > 0) {
                int frames = (int) Math.min(blockFrames, framesLeft);
                // A fresh buffer per block: the consumer may still be reading
                // the previous one, so a shared buffer would tear.
                byte[] block = new byte[frames * frameSize];
                quantizer.encode(generator, block, frames);
                crossing.offer(block);
                framesLeft -= frames;
                produced++;
                long waitNanos = (start + produced * blockNanos) - System.nanoTime();
                if (waitNanos > 0) {
                    Thread.sleep(waitNanos / NANOS_PER_MILLI, (int) (waitNanos % NANOS_PER_MILLI));
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        open = false;
    }
}
