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

package org.edgo.audio.measure.gui.generator;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Random;

import lombok.experimental.UtilityClass;
import lombok.extern.log4j.Log4j2;

import org.edgo.audio.measure.generator.SignalGenerator;
import org.edgo.audio.measure.wav.AiffWriter;
import org.edgo.audio.measure.wav.FlacWriter;
import org.edgo.audio.measure.wav.WavWriter;
import org.edgo.audio.measure.enums.AudioFileFormat;
import org.edgo.audio.measure.gui.interfaces.PcmSink;

/**
 * One-shot exporter for the generator pane's "Save to…" action.
 * Picks the file format from the target's extension ({@code .wav},
 * {@code .flac}, {@code .aif} / {@code .aiff}) and writes the same
 * PCM stream {@link org.edgo.audio.measure.sound.CsjsoundGenerator}
 * would produce on the wire — same fillBuffer logic, same TPDF
 * dither.  Stereo, both channels carry the same signal.
 *
 * <p>For periodic forms (sine, triangle, rectangle, …) the length is
 * truncated to an integer number of signal periods so the file loops
 * cleanly without a click.  Non-periodic forms (noise, sweeps) use
 * the requested duration as-is.
 */
@Log4j2
@UtilityClass
public class SignalFileExporter {

    private final int CHANNELS      = 2;
    private final int BUFFER_FRAMES = 4096;

    /**
     * @param signalFrequencyHz pass &gt; 0 for periodic forms (file is
     *        truncated to an integer multiple of the period); pass 0 / NaN
     *        for noise / sweep where there's no period to align to.
     */
    public long export(SignalGenerator generator, File outFile,
                       int sampleRate, int bitDepth,
                       double durationSeconds, int ditherBits,
                       double signalFrequencyHz) throws IOException {
        long requestedFrames = Math.max(1, Math.round(durationSeconds * sampleRate));
        long totalFrames = truncateToFullPeriods(requestedFrames, sampleRate, signalFrequencyHz);

        String name = outFile.getName().toLowerCase(Locale.ROOT);
        AudioFileFormat fmt =
                name.endsWith(".flac")                          ? AudioFileFormat.FLAC
              : name.endsWith(".aiff") || name.endsWith(".aif") ? AudioFileFormat.AIFF
              :                                                   AudioFileFormat.WAV;

        try (PcmSink sink = openSink(fmt, outFile, sampleRate, bitDepth)) {
            Random rng = ditherBits > 0 ? new Random() : null;
            int bytesPerSample = bitDepth / 8;
            int bytesPerFrame  = bytesPerSample * CHANNELS;
            byte[] buf = new byte[BUFFER_FRAMES * bytesPerFrame];
            long written = 0;
            while (written < totalFrames) {
                int frames = (int) Math.min(BUFFER_FRAMES, totalFrames - written);
                fillBuffer(generator, buf, frames, bitDepth, ditherBits, rng);
                sink.writeRaw(buf, frames * bytesPerFrame);
                written += frames;
            }
            log.info("Exported: {} ({}, {} frames, {} s, {} Hz / {} bit, dither {} bit)",
                    outFile.getAbsolutePath(), fmt, totalFrames,
                    String.format(Locale.ROOT, "%.4f", totalFrames / (double) sampleRate),
                    sampleRate, bitDepth, ditherBits);
        }
        return outFile.length();
    }

    /** Snaps {@code requested} down to the largest multiple of one signal period. */
    private long truncateToFullPeriods(long requested, int sampleRate, double freqHz) {
        if (!(freqHz > 0.0) || sampleRate <= 0) return requested;
        long period = Math.max(1, Math.round(sampleRate / freqHz));
        long n = (requested / period) * period;
        return Math.max(period, n);   // never drop the file to zero frames
    }


    private PcmSink openSink(AudioFileFormat fmt, File outFile, int sampleRate, int bitDepth)
            throws IOException {
        switch (fmt) {
            case FLAC: {
                FlacWriter w = new FlacWriter(outFile, sampleRate, CHANNELS, bitDepth);
                return new PcmSink() {
                    @Override public void writeRaw(byte[] buf, int length) throws IOException { w.writeRaw(buf, length); }
                    @Override public void close()                          throws IOException { w.close(); }
                };
            }
            case AIFF: {
                AiffWriter w = new AiffWriter(outFile, sampleRate, CHANNELS, bitDepth);
                return new PcmSink() {
                    @Override public void writeRaw(byte[] buf, int length) throws IOException { w.writeRaw(buf, length); }
                    @Override public void close()                          throws IOException { w.close(); }
                };
            }
            default: {
                WavWriter w = new WavWriter(outFile, sampleRate, CHANNELS, bitDepth, false);
                return new PcmSink() {
                    @Override public void writeRaw(byte[] buf, int length) throws IOException { w.writeRaw(buf, length); }
                    @Override public void close()                          throws IOException { w.close(); }
                };
            }
        }
    }

    /**
     * Sample-pack logic mirroring
     * {@link org.edgo.audio.measure.sound.CsjsoundGenerator}'s fillBuffer
     * so a saved file equals the bytes that would have been streamed to
     * the DAC.  Stereo: both channels get the same sample.
     */
    private void fillBuffer(SignalGenerator gen, byte[] buf, int frames,
                            int bitDepth, int ditherBits, Random rng) {
        int bytesPerSample = bitDepth / 8;
        int bytesPerFrame  = bytesPerSample * CHANNELS;
        if (bitDepth == 8) {
            // Unsigned PCM offset-binary, silence at 128 (matches WAV
            // 8-bit convention; FLAC rejects 8-bit so this branch is
            // WAV-only by precondition).
            for (int i = 0; i < frames; i++) {
                double sample = clamp(gen.nextSample() + tpdfNoise(ditherBits, rng));
                byte   val    = (byte) ((int) Math.round(sample * 127.0) + 128 & 0xFF);
                int    off    = i * bytesPerFrame;
                buf[off]     = val;
                buf[off + 1] = val;
            }
        } else {
            long maxVal = (1L << (bitDepth - 1)) - 1;
            for (int i = 0; i < frames; i++) {
                double sample = clamp(gen.nextSample() + tpdfNoise(ditherBits, rng));
                long   pcm    = (long) Math.round(sample * maxVal);
                int    off    = i * bytesPerFrame;
                for (int b = 0; b < bytesPerSample; b++) {
                    byte bv = (byte) (pcm >> (8 * b));
                    buf[off + b]                  = bv;
                    buf[off + bytesPerSample + b] = bv;
                }
            }
        }
    }

    private double tpdfNoise(int ditherBits, Random rng) {
        if (ditherBits == 0 || rng == null) return 0.0;
        return (rng.nextDouble() - rng.nextDouble()) / (1L << (ditherBits - 1));
    }

    private double clamp(double v) {
        if (v >  1.0) return  1.0;
        if (v < -1.0) return -1.0;
        return v;
    }
}
