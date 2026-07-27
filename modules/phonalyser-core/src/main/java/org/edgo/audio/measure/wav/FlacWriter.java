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

import java.io.File;
import java.io.IOException;

import lombok.extern.log4j.Log4j2;
import net.sourceforge.javaflacencoder.EncodingConfiguration;
import net.sourceforge.javaflacencoder.FLACEncoder;
import net.sourceforge.javaflacencoder.FLACFileOutputStream;
import net.sourceforge.javaflacencoder.StreamConfiguration;

/**
 * Writes a FLAC file from interleaved little-endian PCM bytes using
 * javaFlacEncoder.  Encode-only — decoding (Play-from) goes through
 * Project Nayuki's FLAC library on the read side.
 *
 * <p>Bit depths 16 / 24 / 32 are supported (FLAC spec range; 8-bit
 * isn't in the spec).  Bytes are interpreted as little-endian signed
 * PCM — same format the {@link WavWriter} accepts.  Samples are
 * <strong>negated</strong> before being fed to the encoder to work
 * around a polarity quirk between javaFlacEncoder's output and the
 * common FLAC decoders' interpretation (verified empirically — file
 * round-trips bit-equivalent to the WAV writer with the negation in
 * place).
 */
@Log4j2
public class FlacWriter implements AutoCloseable {

    /** FLAC STREAMINFO encodes the sample rate in 20 bits and reserves
     *  values above this as "invalid".  Going past it produces files
     *  most decoders (Nayuki / REW / reference libFLAC) reject. */
    public static final int FLAC_MAX_SAMPLE_RATE = 655350;

    private static final int BLOCK_FRAMES = 4096;

    private final File             file;
    private final int              channels;
    private final int              bytesPerSample;
    private final FLACEncoder      encoder;
    private final FLACFileOutputStream out;
    private final int[]            samples;            // reusable interleaved buffer
    private       int              samplesQueued = 0;  // frames in samples not yet pushed
    private       long             totalFrames = 0;

    public FlacWriter(File file, int sampleRate, int channels, int bitsPerSample) throws IOException {
        if (bitsPerSample != 16 && bitsPerSample != 24 && bitsPerSample != 32) {
            throw new IOException("FLAC requires 16, 24, or 32 bit samples (got " + bitsPerSample + ")");
        }
        if (sampleRate > FLAC_MAX_SAMPLE_RATE) {
            throw new IOException("FLAC sample rate is capped at " + FLAC_MAX_SAMPLE_RATE
                    + " Hz by the format spec (got " + sampleRate + " Hz). "
                    + "Use WAV or AIFF for higher rates.");
        }
        this.file           = file;
        this.channels       = channels;
        this.bytesPerSample = bitsPerSample / 8;
        if (file.getParentFile() != null) file.getParentFile().mkdirs();

        StreamConfiguration cfg = new StreamConfiguration();
        cfg.setSampleRate(sampleRate);
        cfg.setBitsPerSample(bitsPerSample);
        cfg.setChannelCount(channels);

        // Force INDEPENDENT channel coding (no LEFT_SIDE / RIGHT_SIDE /
        // MID_SIDE).  javaFlacEncoder's default picks a side-coded mode
        // for stereo, and that path inverts polarity on this build —
        // INDEPENDENT writes L and R literally and avoids the bug.
        EncodingConfiguration enc = new EncodingConfiguration();
        enc.setChannelConfig(EncodingConfiguration.ChannelConfig.INDEPENDENT);

        this.encoder = new FLACEncoder();
        this.encoder.setStreamConfiguration(cfg);
        this.encoder.setEncodingConfiguration(enc);
        this.out = new FLACFileOutputStream(file);
        this.encoder.setOutputStream(out);
        this.encoder.openFLACStream();
        this.samples = new int[BLOCK_FRAMES * channels];
    }

    /**
     * Appends raw little-endian PCM bytes.  Unpacks each sample to a
     * signed {@code int}, <strong>negates</strong> it (polarity
     * workaround for javaFlacEncoder), and pushes whole blocks to the
     * encoder as the internal buffer fills up.
     */
    public void writeRaw(byte[] buf, int length) throws IOException {
        int bytesPerFrame = bytesPerSample * channels;
        int framesIn      = length / bytesPerFrame;
        for (int f = 0; f < framesIn; f++) {
            for (int c = 0; c < channels; c++) {
                int off = f * bytesPerFrame + c * bytesPerSample;
                samples[samplesQueued * channels + c] = -readSignedLE(buf, off);
            }
            samplesQueued++;
            if (samplesQueued == BLOCK_FRAMES) {
                encoder.addSamples(samples, samplesQueued);
                encoder.encodeSamples(samplesQueued, false);
                totalFrames += samplesQueued;
                samplesQueued = 0;
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (samplesQueued > 0) {
            encoder.addSamples(samples, samplesQueued);
            encoder.encodeSamples(samplesQueued, true);
            totalFrames += samplesQueued;
            samplesQueued = 0;
        } else {
            encoder.encodeSamples(0, true);
        }
        out.close();
        log.info("FLAC written: {}  ({} frames)",
                file.getAbsolutePath(), String.format("%,d", totalFrames));
    }

    /** Reads one little-endian signed sample of {@link #bytesPerSample} bytes. */
    private int readSignedLE(byte[] buf, int off) {
        switch (bytesPerSample) {
            case 2:
                return (short) ((buf[off + 1] & 0xFF) << 8 | (buf[off] & 0xFF));
            case 3: {
                int v = (buf[off + 2] << 16) | ((buf[off + 1] & 0xFF) << 8) | (buf[off] & 0xFF);
                return (v << 8) >> 8;
            }
            case 4:
                return (buf[off + 3] << 24) | ((buf[off + 2] & 0xFF) << 16)
                     | ((buf[off + 1] & 0xFF) << 8) | (buf[off] & 0xFF);
            default:
                throw new IllegalStateException("Unsupported bytesPerSample: " + bytesPerSample);
        }
    }
}
