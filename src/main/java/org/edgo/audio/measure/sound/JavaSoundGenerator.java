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

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;

import lombok.extern.log4j.Log4j2;
import org.edgo.audio.measure.generator.SignalGenerator;

/**
 * Generator that streams a {@link SignalGenerator} to the JVM's default
 * {@link SourceDataLine} — the same audio path
 * {@code FilePlayController} uses for file playback.  JavaSound dispatches
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

    private final int        sampleRate;
    private final int        bitDepth;
    private final int        bytesPerSample;
    private final int        bytesPerFrame;
    private final String     deviceName;   // null = JavaSound default mixer
    private volatile int     ditherBits;
    private final Random     rng;

    private SourceDataLine   line;

    public JavaSoundGenerator(int sampleRate, int bitDepth, int ditherBits) {
        this(sampleRate, bitDepth, ditherBits, null);
    }

    /**
     * @param deviceName output device name to honour (matched against
     *                   {@link Mixer.Info#getName()} substring) — pass
     *                   {@code null} to let {@link AudioSystem#getLine}
     *                   pick the platform default mixer.
     */
    public JavaSoundGenerator(int sampleRate, int bitDepth, int ditherBits, String deviceName) {
        this.sampleRate     = sampleRate;
        this.bitDepth       = bitDepth;
        this.ditherBits     = ditherBits;
        this.deviceName     = deviceName;
        this.bytesPerSample = bitDepth / 8;
        this.bytesPerFrame  = bytesPerSample * CHANNELS;
        this.rng            = new Random();
    }

    @Override
    public void open() throws LineUnavailableException {
        AudioFormat fmt = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate, bitDepth, CHANNELS,
                bytesPerFrame, sampleRate, false);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
        Mixer.Info chosenMixer = findMixer(info);
        if (chosenMixer != null) {
            line = (SourceDataLine) AudioSystem.getMixer(chosenMixer).getLine(info);
        } else {
            line = (SourceDataLine) AudioSystem.getLine(info);
        }
        line.open(fmt);
        int hwFrames = line.getBufferSize() / bytesPerFrame;
        log.info("JavaSound generator opened: format={}, hw-buffer={} frames ({} ms), mixer={}",
                fmt, hwFrames, hwFrames * 1000 / sampleRate,
                chosenMixer != null ? chosenMixer.getName() : "<JavaSound default>");
        if (ditherBits > 0) log.info("Dithering: TPDF {} bit", ditherBits);
    }

    /**
     * Walks {@link AudioSystem#getMixerInfo()} for the first mixer whose
     * name contains {@link #deviceName} <em>and</em> can supply a
     * {@link SourceDataLine} matching {@code info}.  Returns {@code null}
     * if no name was requested or no match exists — the caller then
     * falls back to {@link AudioSystem#getLine(Line.Info)}.
     */
    private Mixer.Info findMixer(DataLine.Info info) {
        if (deviceName == null || deviceName.isEmpty()) return null;
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            if (!mi.getName().contains(deviceName)) continue;
            Mixer m = AudioSystem.getMixer(mi);
            if (m.isLineSupported(info)) return mi;
        }
        log.warn("JavaSound: no mixer matches '{}', falling back to default", deviceName);
        return null;
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

        warmupJit(generator, buf);
        // Warmup consumed nextSample() calls that advance sweep playback
        // state; reset so the real stream sees the sweep from sample 0
        // (otherwise a freq-response measurement captures a mid-sweep
        // recording while the deconv reference starts at zero).
        generator.resetSweepPosition();

        line.start();
        log.info("JavaSound playback started (continuous) — pre-filling {} frames ({} ms)...",
                preFillCount * BUFFER_FRAMES,
                preFillCount * BUFFER_FRAMES * 1000 / sampleRate);

        for (int i = 0; i < preFillCount; i++) {
            fillBuffer(generator, buf, BUFFER_FRAMES);
            line.write(buf, 0, BUFFER_FRAMES * bytesPerFrame);
        }
        readyLatch.countDown();

        while (!stopFlag.get()) {
            fillBuffer(generator, buf, BUFFER_FRAMES);
            line.write(buf, 0, BUFFER_FRAMES * bytesPerFrame);
        }

        line.drain();
        line.stop();
        log.info("JavaSound playback stopped.");
    }

    @Override
    public void setDitherBits(int bits) {
        this.ditherBits = Math.max(0, bits);
    }

    @Override
    public void close() {
        if (line != null) {
            line.close();
            line = null;
        }
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
        if (bitDepth == 8) {
            for (int i = 0; i < frames; i++) {
                double sample = clamp(gen.nextSample() + tpdfNoise());
                byte   val    = (byte) Math.round(sample * 127.0);
                int    offset = i * bytesPerFrame;
                buf[offset]     = val;
                buf[offset + 1] = val;
            }
        } else {
            long maxVal = (1L << (bitDepth - 1)) - 1;
            for (int i = 0; i < frames; i++) {
                double sample = clamp(gen.nextSample() + tpdfNoise());
                long   pcm    = (long) Math.round(sample * maxVal);
                int    offset = i * bytesPerFrame;
                for (int b = 0; b < bytesPerSample; b++) {
                    byte byteVal = (byte) (pcm >> (8 * b));
                    buf[offset + b]                  = byteVal;
                    buf[offset + bytesPerSample + b] = byteVal;
                }
            }
        }
    }

    private double tpdfNoise() {
        if (ditherBits == 0) return 0.0;
        return (rng.nextDouble() - rng.nextDouble()) / (1L << (ditherBits - 1));
    }

    private double clamp(double v) {
        return v > 1.0 ? 1.0 : (v < -1.0 ? -1.0 : v);
    }
}
