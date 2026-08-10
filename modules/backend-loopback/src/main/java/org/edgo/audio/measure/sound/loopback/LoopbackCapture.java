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

import lombok.extern.log4j.Log4j2;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.generator.SignalGenerator;
import org.edgo.audio.measure.sound.AbstractPcmCapture;
import org.edgo.audio.measure.sound.PcmQuantizer;

/**
 * The capture lane of the digital loopback.  It takes the blocks the playback
 * lane quantised and delivers them to the listeners through the base class,
 * paced to the wall clock at the selected sample rate - the workers above
 * assume a real-time stream, and a capture that ran as fast as the CPU allows
 * would starve their averaging of any time reference.
 *
 * <p>When no playback lane is producing, the delivered block is this lane's own
 * dithered silence rather than digital zeros.  That is the point of the
 * backend: a bench whose noise floor is known exactly, present whether or not a
 * signal happens to be playing.
 */
@Log4j2
public final class LoopbackCapture extends AbstractPcmCapture {

    /** Blocks per second - a 20 ms block.  Far below the base class's 2 s
     *  delivery deadline at every rate on the ladder, so a slow rate cannot be
     *  mistaken for a stalled device. */
    private static final int BLOCKS_PER_SECOND = 50;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long NANOS_PER_MILLI  = 1_000_000L;
    /** Join timeout (ms) for the consume thread on stop. */
    private static final long STOP_JOIN_MS = 2000;

    private final LoopbackCrossing crossing;
    private final int blockFrames;
    /** Quantiser for the silence this lane delivers when nothing is playing -
     *  the same class the playback lane encodes with, at the same depth, so
     *  the floor is identical whether or not a signal is present. */
    private final PcmQuantizer silenceQuantizer;
    private final SignalGenerator silence;

    private byte[] silenceBlock;
    private Thread consumeThread;

    LoopbackCapture(int sampleRate, int bitDepth, LoopbackCrossing crossing) {
        super(sampleRate, bitDepth);
        this.crossing    = crossing;
        this.blockFrames = Math.max(1, sampleRate / BLOCKS_PER_SECOND);
        // Dither depth follows the SELECTED depth, so the last bit always
        // carries TPDF dither - see LoopbackPlayback for why this lane never
        // takes the caller's dither setting.
        this.silenceQuantizer = new PcmQuantizer(bitDepth, bitDepth);
        // Amplitude zero: every sample is exactly 0.0 before quantisation, so
        // what reaches the listeners is the dither floor and nothing else.
        this.silence = new SignalGenerator(GenSignalForm.SINE, 1000.0, sampleRate, 0.0, 1.0);
    }

    @Override
    public void open() {
        silenceBlock = new byte[blockFrames * frameSize];
        // Nothing from an earlier capture may reach this one.
        crossing.clear();
    }

    @Override
    public void startRecording() {
        if (silenceBlock == null) {
            throw new IllegalStateException("Call open() before startRecording()");
        }
        recording.set(true);
        // Armed before the thread exists, so the write is published to it.
        armDeliveryDeadline();
        consumeThread = new Thread(this::consumeLoop, "loopback-capture");
        consumeThread.setDaemon(true);
        consumeThread.start();
        if (log.isInfoEnabled()) {
            log.info("Loopback recording started: {} Hz, {} bits", sampleRate, bitDepth);
        }
    }

    @Override
    public void stopRecording() throws InterruptedException {
        recording.set(false);
        if (consumeThread != null) {
            consumeThread.join(STOP_JOIN_MS);
            consumeThread = null;
        }
        if (log.isInfoEnabled()) {
            log.info("Loopback recording stopped.");
        }
    }

    @Override
    public void close() {
        if (recording.get()) {
            try {
                stopRecording();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        // The lane is not held open across captures: the next open() starts
        // from a cleared crossing and a fresh buffer.
        silenceBlock = null;
        crossing.clear();
    }

    /** Delivers one block per block-period, timed against an ABSOLUTE schedule
     *  (start + n·period) so the stream cannot drift the way repeated relative
     *  sleeps would. */
    private void consumeLoop() {
        long blockNanos = blockFrames * NANOS_PER_SECOND / sampleRate;
        long start      = System.nanoTime();
        long delivered  = 0;
        try {
            while (recording.get()) {
                delivered++;
                long waitNanos = (start + delivered * blockNanos) - System.nanoTime();
                if (waitNanos > 0) {
                    Thread.sleep(waitNanos / NANOS_PER_MILLI, (int) (waitNanos % NANOS_PER_MILLI));
                }
                byte[] block = crossing.poll();
                if (block == null) {
                    silenceQuantizer.encode(silence, silenceBlock, blockFrames);
                    block = silenceBlock;
                }
                dispatch(block, blockFrames * frameSize);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
