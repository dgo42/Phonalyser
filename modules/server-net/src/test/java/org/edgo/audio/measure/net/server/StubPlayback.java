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

package org.edgo.audio.measure.net.server;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.edgo.audio.measure.enums.OutputChannels;
import org.edgo.audio.measure.generator.SignalGenerator;
import org.edgo.audio.measure.sound.AudioPlayback;

import lombok.Getter;
import lombok.Setter;

/**
 * An output line with no DAC behind it: it keeps the generator it was handed and
 * lets the test pull samples out of it.
 *
 * <p>That is the only honest way to ask what the generator is EMITTING.  The
 * commanded state says what was asked for; a trim, a bin snap or an amplitude
 * change is only real if it reaches the DDS, and the DDS answers exactly one
 * question - {@code nextSample()}.  So this stub renders like a real render loop
 * does and {@link #measuredHz(int)} reads the frequency back off the waveform,
 * rather than the test asserting on the number it just sent.
 *
 * <p>It follows the production contract: pre-fill, count the ready latch down,
 * then run until the stop flag rises.  The parking loop is deliberately dumb -
 * the samples the test wants are pulled from the TEST's thread while the play
 * thread waits, which keeps the assertion deterministic instead of racing a
 * render loop for a buffer.
 */
final class StubPlayback implements AudioPlayback {

    /** What the "hardware buffer" pre-fill consumes before the latch drops. */
    static final int PREFILL_SAMPLES = 64;
    /** How often the parked play thread looks at its stop flag, in
     *  milliseconds. */
    private static final long POLL_MS = 1;

    @Getter
    private final int sampleRate;
    @Getter
    private final int bitDepth;

    @Getter
    private volatile boolean opened;
    @Getter
    private volatile boolean closed;
    /** How many render sessions ran - one per {@code gen.start}. */
    @Getter
    private volatile int playCount;
    /** The lane gate {@code gen.open} asked for; the setter IS the
     *  {@code AudioPlayback} one. */
    @Getter
    @Setter
    private volatile OutputChannels outputChannels;
    /** The RIGHT lane's scale as last pushed - {@code fsLeft/fsRight} of the
     *  card the lane was opened on (spec 4.5).  It never reaches the DDS, so the
     *  rendered waveform cannot show it and the line has to say so itself. */
    @Getter
    private volatile double rightLaneScale;

    private volatile SignalGenerator generator;

    StubPlayback(int sampleRate, int bitDepth) {
        this.sampleRate = sampleRate;
        this.bitDepth = bitDepth;
    }

    @Override
    public void open() {
        opened = true;
    }

    @Override
    public void play(SignalGenerator source, int durationSeconds) {
        throw new UnsupportedOperationException(
                "the net server only ever plays continuously");
    }

    @Override
    public void play(SignalGenerator source, AtomicBoolean stopFlag,
            CountDownLatch readyLatch) {
        generator = source;
        playCount++;
        render(PREFILL_SAMPLES);
        readyLatch.countDown();
        while (!stopFlag.get()) {
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public void setChannelScale(double left, double right) {
        rightLaneScale = right;
    }

    /** How many times this lane was closed - a lane handed back twice is a
     *  double release, which on a real device is a refusal or worse. */
    @Getter
    private volatile int closeCount;

    @Override
    public void close() {
        closed = true;
        closeCount++;
    }

    /** Pulls {@code count} samples the way the render loop does. */
    double[] render(int count) {
        SignalGenerator source = generator;
        double[] rendered = new double[count];
        for (int i = 0; i < count; i++) {
            rendered[i] = source.nextSample();
        }
        return rendered;
    }

    /** The frequency the DDS is actually emitting, counted off the waveform:
     *  upward zero crossings over {@code count} samples.  Accurate to about one
     *  cycle in the window, which is all it takes to tell a trimmed tone from an
     *  untrimmed one. */
    double measuredHz(int count) {
        double[] rendered = render(count);
        int crossings = 0;
        for (int i = 1; i < rendered.length; i++) {
            if (rendered[i - 1] < 0 && rendered[i] >= 0) {
                crossings++;
            }
        }
        return crossings * (double) sampleRate / count;
    }
}
