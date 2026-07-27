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

import lombok.experimental.UtilityClass;
import lombok.extern.log4j.Log4j2;
import org.edgo.audio.measure.generator.SignalGenerator;
import org.edgo.audio.measure.wav.WavWriter;

import java.io.File;
import java.io.IOException;
import java.util.Random;

/**
 * One-shot WAV exporter for the generator pane's "Save" action.
 * Writes a stereo PCM file where both channels carry the same signal,
 * sampled at the same {@code sampleRate} / {@code bitDepth} the playback
 * line would use.  Dither (if any) is applied identically to the live
 * path so the file's noise floor matches what the user hears.
 */
@Log4j2
@UtilityClass
public class WavSignalExporter {

    private final int CHANNELS       = 2;
    private final int BUFFER_FRAMES  = 4096;

    /**
     * Renders {@code durationSeconds} of {@code generator} into {@code outFile}.
     * The file is overwritten if it exists.  Returns the byte count written
     * (header + data).
     *
     * @throws IOException on filesystem failure
     */
    public long export(SignalGenerator generator,
                              File outFile,
                              int sampleRate,
                              int bitDepth,
                              double durationSeconds,
                              int ditherBits) throws IOException {
        Random rng = ditherBits > 0 ? new Random() : null;
        long totalFrames = Math.max(0, (long) Math.round(durationSeconds * sampleRate));
        int  bytesPerSample = bitDepth / 8;
        int  bytesPerFrame  = bytesPerSample * CHANNELS;
        byte[] buf          = new byte[BUFFER_FRAMES * bytesPerFrame];

        try (WavWriter w = new WavWriter(outFile, sampleRate, CHANNELS, bitDepth, false)) {
            long written = 0;
            while (written < totalFrames) {
                int frames = (int) Math.min(BUFFER_FRAMES, totalFrames - written);
                fillBuffer(generator, buf, frames, bitDepth, ditherBits, rng);
                w.writeRaw(buf, frames * bytesPerFrame);
                written += frames;
            }
            log.info("WAV export: {} ({} frames, {} sec, {} Hz, {} bit, dither {} bit)",
                    outFile.getAbsolutePath(), totalFrames, durationSeconds, sampleRate, bitDepth, ditherBits);
            return outFile.length();
        }
    }

    /**
     * Same packing logic as {@code CsjsoundGenerator.fillBuffer}, but
     * inline here so the WAV path doesn't have to acquire a real audio
     * line.  Identical sample-by-sample output to the live playback.
     */
    private void fillBuffer(SignalGenerator gen, byte[] buf, int frames,
                            int bitDepth, int ditherBits, Random rng) {
        int bytesPerSample = bitDepth / 8;
        int bytesPerFrame  = bytesPerSample * CHANNELS;
        if (bitDepth == 8) {
            // Unsigned PCM offset-binary, silence at 128.
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
