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

package org.edgo.audio.measure.sound.qa40x;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.Setter;
import lombok.extern.log4j.Log4j2;

import org.edgo.audio.measure.enums.OutputChannels;
import org.edgo.audio.measure.generator.SignalGenerator;
import org.edgo.audio.measure.sound.AudioPlayback;

/**
 * QA402/QA403 stereo playback — a thin AudioPlayback client that {@code attach}es
 * / {@code detach}es the generator lane on the manager's one duplex engine (doc
 * §10).  This class IS the engine's generator sample source: for each frame it
 * pulls one sample from the mono {@link SignalGenerator}, applies the per-lane
 * full-scale scale and the Left/Right/Both gate, and scales to int32.
 *
 * <h2>Amplitude convention (doc §6, Qa40xLevels)</h2>
 * The generator already emits samples normalised to [-1, +1] <b>peak</b>-relative
 * to the DAC full scale it was given (its {@code dacFsVoltageAmpl} is the range's
 * peak full-scale voltage, set from the device card).  So the wire sample is just
 * {@code round(sample · MAXINT)} — the PEAK convention, with the range dBV and the
 * on-device cal factor already folded into the card's full-scale voltage.  There
 * is deliberately NO extra √2/RMS factor here (that would be ~3&nbsp;dB hot).  The
 * engine does the L/R swap and little-endian packing (doc §5).
 *
 * <h2>Live tunables</h2>
 * {@link #setChannelScale} and {@link #setOutputChannels} are honoured live inside
 * the sample source.  {@link #setDitherBits} is a documented no-op — dither is
 * meaningless at 32-bit (there is no quantisation headroom worth dithering).
 */
@Log4j2
public final class Qa40xGenerator implements AudioPlayback {

    private static final int    MAXINT   = Qa40xLevels.MAXINT;
    private static final int    CHANNELS = 2;
    private static final long   STOP_POLL_MILLIS = 50;

    private final Qa40xDeviceManager manager;
    private final int sampleRate;

    private Qa40xDuplexEngine engine;
    private volatile SignalGenerator currentGenerator;
    private volatile double  scaleL = 1.0;
    private volatile double  scaleR = 1.0;
    /** Output-lane gate, honoured live in {@link #nextFrames} (default BOTH). */
    @Setter
    private volatile OutputChannels outputChannels = OutputChannels.BOTH;
    private volatile boolean attached;

    Qa40xGenerator(Qa40xDeviceManager manager, int sampleRate, int ditherBits) {
        this.manager    = manager;
        this.sampleRate = sampleRate;
        // ditherBits is accepted for API symmetry but is a no-op at 32-bit — see setDitherBits.
    }

    @Override
    public void open() {
        engine = manager.acquireEngine(sampleRate);
        log.info("QA40x generator opened : {} Hz / 32 bit", sampleRate);
    }

    @Override
    public void play(SignalGenerator generator, int durationSeconds) {
        startLane(generator);
        try {
            Thread.sleep((long) durationSeconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        stopLane();
    }

    @Override
    public void play(SignalGenerator generator, AtomicBoolean stopFlag, CountDownLatch readyLatch) {
        startLane(generator);
        // The engine primes the output past the start threshold synchronously in
        // attachGenerator(); once startLane() returns, priming is complete.
        readyLatch.countDown();
        log.info("QA40x playback started (continuous).");
        try {
            while (!stopFlag.get()) {
                Thread.sleep(STOP_POLL_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        stopLane();
        log.info("QA40x playback stopped.");
    }

    @Override
    public void setChannelScale(double left, double right) {
        this.scaleL = left;
        this.scaleR = right;
    }

    @Override
    public void close() {
        stopLane();
    }

    private void startLane(SignalGenerator generator) {
        if (engine == null) {
            engine = manager.acquireEngine(sampleRate);
        }
        currentGenerator = generator;
        // Warmup consumed nextSample() advances sweep state; rewind to sample 0 so a
        // freq-response sweep aligns with the deconvolution reference (audio-backends memory).
        generator.resetSweepPosition();
        attached = true;
        engine.attachGenerator(this::nextFrames);   // live source swap if the stream is already up
    }

    private void stopLane() {
        if (attached) {
            attached = false;
            engine.detachGenerator();
            currentGenerator = null;
        }
    }

    /**
     * Engine generator-lane source: fills {@code destination} with {@code frames}
     * interleaved LOGICAL L,R int32 samples ({@code [2i]} = left, {@code [2i+1]} =
     * right).  Runs on the engine's USB event thread.
     */
    private void nextFrames(int[] destination, int frames) {
        SignalGenerator gen = currentGenerator;
        OutputChannels gate = outputChannels;
        double sl = scaleL;
        double sr = scaleR;
        boolean wantL = gate != OutputChannels.RIGHT;
        boolean wantR = gate != OutputChannels.LEFT;
        for (int f = 0; f < frames; f++) {
            double sample = (gen != null) ? clamp(gen.nextSample()) : 0.0;
            destination[CHANNELS * f]     = wantL ? toInt32(sample * sl) : 0;
            destination[CHANNELS * f + 1] = wantR ? toInt32(sample * sr) : 0;
        }
    }

    /** Peak-convention scale to int32: {@code round(clamp(v) · MAXINT)}, saturated to ±MAXINT. */
    private int toInt32(double v) {
        long scaled = Math.round(clamp(v) * MAXINT);
        if (scaled > MAXINT) {
            return MAXINT;
        }
        if (scaled < -MAXINT) {
            return -MAXINT;
        }
        return (int) scaled;
    }

    private double clamp(double v) {
        return v > 1.0 ? 1.0 : (v < -1.0 ? -1.0 : v);
    }
}
