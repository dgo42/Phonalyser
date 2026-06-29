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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sound.sampled.AudioFormat;
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

    private final int          sampleRate;
    private final int          bitDepth;
    private final int          bytesPerFrame;
    private final String       deviceName;   // null = JavaSound default mixer
    private final PcmQuantizer quantizer;
    private final JavaSoundDeviceManager deviceManager;

    private SourceDataLine   line;

    public JavaSoundGenerator(int sampleRate, int bitDepth, int ditherBits,
                              JavaSoundDeviceManager deviceManager) {
        this(sampleRate, bitDepth, ditherBits, null, deviceManager);
    }

    /**
     * @param deviceName    output device name to honour (matched against
     *                      {@link Mixer.Info#getName()} substring) — pass
     *                      {@code null} to let the platform default mixer be used.
     * @param deviceManager opens the {@link SourceDataLine} on the mixer whose
     *                      name matches {@code deviceName} (or the default).
     */
    public JavaSoundGenerator(int sampleRate, int bitDepth, int ditherBits, String deviceName,
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
        quantizer.setDitherBits(bits);
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
        quantizer.encode(gen, buf, frames);
    }
}
