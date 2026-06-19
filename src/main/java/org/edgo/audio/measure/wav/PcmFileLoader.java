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

package org.edgo.audio.measure.wav;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.Locale;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import io.nayuki.flac.common.StreamInfo;
import io.nayuki.flac.decode.FlacDecoder;
import lombok.extern.log4j.Log4j2;

/**
 * Singleton loader that decodes WAV / AIFF / FLAC into a uniform
 * little-endian signed-PCM {@link AudioInputStream}.  WAV and AIFF
 * route through built-in {@code javax.sound.sampled} SPIs; FLAC goes
 * through Nayuki's {@link FlacDecoder} and is repackaged in-memory
 * (whole-file decode — sized for the 10–60 s clips this app deals
 * with).
 *
 * <p>Used by both the generator's file-player and the scope's
 * Play-from controller so the FLAC path lives in exactly one place.
 */
@Log4j2
public final class PcmFileLoader {

    private static volatile PcmFileLoader INSTANCE;

    private PcmFileLoader() {}

    public static PcmFileLoader instance() {
        PcmFileLoader local = INSTANCE;
        if (local == null) {
            synchronized (PcmFileLoader.class) {
                local = INSTANCE;
                if (local == null) {
                    local = new PcmFileLoader();
                    INSTANCE = local;
                }
            }
        }
        return local;
    }

    /**
     * Opens {@code file} as a signed-PCM {@link AudioInputStream}.
     * WAV / AIFF rely on the JDK's AudioSystem (with on-the-fly
     * conversion if needed); FLAC is decoded eagerly via Nayuki and
     * wrapped in a {@link ByteArrayInputStream}.
     */
    public AudioInputStream openAsPcm(File file) throws Exception {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".flac")) {
            return decodeFlac(file);
        }
        AudioInputStream raw = AudioSystem.getAudioInputStream(file);
        AudioFormat src = raw.getFormat();
        log.info("File source format: {}", src);
        if (src.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED)
                || src.getEncoding().equals(AudioFormat.Encoding.PCM_UNSIGNED)) {
            return raw;
        }
        if (!AudioSystem.isConversionSupported(AudioFormat.Encoding.PCM_SIGNED, src)) {
            throw new IllegalStateException(
                    "No SPI conversion from " + src.getEncoding() + " to PCM_SIGNED.");
        }
        AudioInputStream pcm = AudioSystem.getAudioInputStream(
                AudioFormat.Encoding.PCM_SIGNED, raw);
        log.info("File converted to: {}", pcm.getFormat());
        return pcm;
    }

    private AudioInputStream decodeFlac(File file) throws Exception {
        StreamInfo info;
        int[][] samples;
        try (FlacDecoder dec = new FlacDecoder(file)) {
            while (dec.readAndHandleMetadataBlock() != null) { /* consume metadata */ }
            info = dec.streamInfo;
            if (info.numSamples <= 0) {
                throw new IllegalStateException("FLAC file has no STREAMINFO sample count");
            }
            samples = new int[info.numChannels][(int) info.numSamples];
            int off = 0;
            int n;
            while ((n = dec.readAudioBlock(samples, off)) > 0) {
                off += n;
            }
        }
        int bytesPerSample = info.sampleDepth / 8;
        if (bytesPerSample <= 0) {
            throw new IllegalStateException("Unsupported FLAC sample depth: " + info.sampleDepth);
        }
        int bytesPerFrame = bytesPerSample * info.numChannels;
        int totalFrames   = samples[0].length;
        byte[] pcm = new byte[totalFrames * bytesPerFrame];
        for (int f = 0; f < totalFrames; f++) {
            int frameOff = f * bytesPerFrame;
            for (int c = 0; c < info.numChannels; c++) {
                int s = samples[c][f];
                int off = frameOff + c * bytesPerSample;
                for (int b = 0; b < bytesPerSample; b++) {
                    pcm[off + b] = (byte) (s >> (8 * b));
                }
            }
        }
        AudioFormat fmt = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                info.sampleRate,
                info.sampleDepth,
                info.numChannels,
                bytesPerFrame,
                info.sampleRate,
                false);
        log.info("FLAC decoded: {} ({} Hz / {} bit / {} ch, {} frames)",
                file.getName(), info.sampleRate, info.sampleDepth,
                info.numChannels, totalFrames);
        return new AudioInputStream(new ByteArrayInputStream(pcm), fmt, totalFrames);
    }
}
