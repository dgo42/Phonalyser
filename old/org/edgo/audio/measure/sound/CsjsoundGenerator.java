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

import org.edgo.audio.measure.generator.SignalGenerator;

import lombok.extern.log4j.Log4j2;

/**
 * Plays a {@link SignalGenerator} signal to a specific audio output device.
 *
 * Stereo output — the same signal is written to both channels.
 * PCM encoding:
 *   8-bit  → PCM_UNSIGNED (center = 128), matches Java Sound convention.
 *  16/24/32-bit → PCM_SIGNED, little-endian.
 */
@Log4j2
public class CsjsoundGenerator implements AudioPlayback {

    private static final int BUFFER_FRAMES    = 4096;
    private static final int HW_BUFFER_FRAMES = BUFFER_FRAMES * 64;  // ~682 ms at 384 kHz
    private static final int CHANNELS      = 2;

    private final Mixer.Info mixerInfo;
    private final int        sampleRate;
    private final int        bitDepth;
    /** TPDF dither resolution in bits; 0 = dithering disabled. */
    private final int        ditherBits;
    private final Random     rng;
    private SourceDataLine   line;

    /**
     * @param ditherBits TPDF dither resolution in bits (e.g. 24); 0 to disable dithering.
     *                   The dither noise amplitude equals ±1 LSB at the given bit depth.
     */
    public CsjsoundGenerator(Mixer.Info mixerInfo, int sampleRate, int bitDepth, int ditherBits) {
        this.mixerInfo  = mixerInfo;
        this.sampleRate = sampleRate;
        this.bitDepth   = bitDepth;
        this.ditherBits = ditherBits;
        this.rng        = ditherBits > 0 ? new Random() : null;
    }

    public void open() throws LineUnavailableException {
        AudioFormat format = buildFormat();
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        Mixer mixer = AudioSystem.getMixer(mixerInfo);
        if (!mixer.isLineSupported(info)) {
            throw new LineUnavailableException(
                    "Mixer '" + mixerInfo.getName() + "' does not support format: " + format);
        }
        line = (SourceDataLine) mixer.getLine(info);
        line.open(format, HW_BUFFER_FRAMES * (bitDepth / 8) * CHANNELS);
        int actualHwFrames = line.getBufferSize() / ((bitDepth / 8) * CHANNELS);
        log.info("Generator opened : {}", mixerInfo.getName());
        log.info("Output format    : {}", format);
        log.info("HW buffer        : {} frames ({} ms)", actualHwFrames, actualHwFrames * 1000 / sampleRate);
        if (ditherBits > 0) {
            log.info("Dithering        : TPDF {} bit", ditherBits);
        }
    }

    /**
     * Generates and plays {@code durationSeconds} seconds of audio, then blocks until
     * the hardware buffer drains.
     */
    public void play(SignalGenerator generator, int durationSeconds) {
        int    bytesPerFrame  = (bitDepth / 8) * CHANNELS;
        byte[] buf            = new byte[BUFFER_FRAMES * bytesPerFrame];
        long   totalFrames    = (long) sampleRate * durationSeconds;
        long   framesWritten  = 0;

        line.start();
        log.info("Playback started.");

        while (framesWritten < totalFrames) {
            int frames = (int) Math.min(BUFFER_FRAMES, totalFrames - framesWritten);
            fillBuffer(generator, buf, frames);
            line.write(buf, 0, frames * bytesPerFrame);
            framesWritten += frames;
        }

        line.drain();
        line.stop();
        log.info("Playback finished.");
    }

    /**
     * Plays continuously until {@code stopFlag} is set to {@code true}.
     * Returns within one buffer period (≈ {@link #BUFFER_FRAMES} / sampleRate seconds)
     * after the flag is raised, then drains the hardware buffer and stops the line.
     *
     * <p>{@code readyLatch} is counted down after the first buffer has been written to
     * the hardware, so the caller can synchronise on actual audio output having started
     * rather than relying on a fixed sleep.
     *
     * <p>Use this overload together with a dedicated recorder to ensure the generator
     * always outlasts the recording window regardless of startup-timing jitter.
     */
    public void play(SignalGenerator generator, AtomicBoolean stopFlag, CountDownLatch readyLatch) {
        int    bytesPerFrame  = (bitDepth / 8) * CHANNELS;
        byte[] buf            = new byte[BUFFER_FRAMES * bytesPerFrame];
        int    actualHwFrames = line.getBufferSize() / bytesPerFrame;
        int    preFillCount   = Math.max(1, actualHwFrames / BUFFER_FRAMES);

        line.start();
        log.info("Playback started (continuous) — pre-filling {} frames ({} ms)...",
                 preFillCount * BUFFER_FRAMES,
                 preFillCount * BUFFER_FRAMES * 1000 / sampleRate);

        // Fill the entire hardware buffer before signalling readiness.
        // This gives the DAC maximum headroom so any OS or GC scheduling
        // pause cannot drain the buffer before the next write.
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
        log.info("Playback stopped.");
    }

    private void fillBuffer(SignalGenerator gen, byte[] buf, int frames) {
        int bytesPerSample = bitDepth / 8;
        int bytesPerFrame  = bytesPerSample * CHANNELS;

        if (bitDepth == 8) {
            // Unsigned PCM: 0..255, silence at 128
            for (int i = 0; i < frames; i++) {
                double sample = clamp(gen.nextSample() + tpdfNoise());
                byte   val    = (byte) ((int) Math.round(sample * 127.0) + 128 & 0xFF);
                int    offset = i * bytesPerFrame;
                buf[offset]     = val; // left
                buf[offset + 1] = val; // right
            }
        } else {
            // Signed little-endian PCM
            long maxVal = (1L << (bitDepth - 1)) - 1;
            for (int i = 0; i < frames; i++) {
                double sample = clamp(gen.nextSample() + tpdfNoise());
                long   pcm    = (long) Math.round(sample * maxVal);
                int    offset = i * bytesPerFrame;
                for (int b = 0; b < bytesPerSample; b++) {
                    byte byteVal = (byte) (pcm >> (8 * b));
                    buf[offset + b]                = byteVal; // left
                    buf[offset + bytesPerSample + b] = byteVal; // right
                }
            }
        }
    }

    /**
     * TPDF (Triangular PDF) dither noise with amplitude ±1 LSB at {@code ditherBits}.
     * Returns 0 when dithering is disabled.
     */
    private double tpdfNoise() {
        if (ditherBits == 0) {
            return 0.0;
        }
        return (rng.nextDouble() - rng.nextDouble()) / (1L << (ditherBits - 1));
    }

    private double clamp(double v) {
        return v > 1.0 ? 1.0 : (v < -1.0 ? -1.0 : v);
    }

    private AudioFormat buildFormat() {
        int frameSize = (bitDepth / 8) * CHANNELS;
        if (bitDepth == 8) {
            return new AudioFormat(AudioFormat.Encoding.PCM_UNSIGNED,
                    sampleRate, 8, CHANNELS, frameSize, sampleRate, false);
        }
        return new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                sampleRate, bitDepth, CHANNELS, frameSize, sampleRate, false);
    }

    @Override
    public void close() {
        if (line != null) {
            line.close();
            line = null;
        }
    }
}
