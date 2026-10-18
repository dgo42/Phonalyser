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

package org.edgo.audio.measure.wav;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RIFF round-trip tests: write a known PCM byte stream via {@link WavWriter}
 * and read it back via {@link PcmFileLoader} - the loader the generator's
 * file player and the scope's Play-from open files through - confirming
 * header metadata (sample rate, channels, bit depth, frame count) and
 * per-sample values survive the write->read pass.  Catches endianness,
 * header-finalisation and channel-interleave regressions.
 */
class WavRoundtripTest {

    @Test
    void pcm16_stereo_header_andFrameCountMatch(@TempDir Path tmp) throws Exception {
        int sampleRate    = 48_000;
        int channels      = 2;
        int bitsPerSample = 16;
        int frames        = 100;

        File out = tmp.resolve("test.wav").toFile();

        // Build a known PCM16 stereo byte stream: left channel ramps
        // 0 -> 99 as little-endian 16-bit signed; right channel mirrors
        // with negative values.  This gives us per-sample identifiable
        // signatures so the reader can be verified frame-by-frame.
        ByteBuffer bb = ByteBuffer.allocate(frames * 2 * 2)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int f = 0; f < frames; f++) {
            bb.putShort((short)  f);          // left
            bb.putShort((short) -f);          // right
        }
        byte[] pcm = bb.array();

        try (WavWriter w = new WavWriter(out, sampleRate, channels, bitsPerSample, false)) {
            w.writeRaw(pcm, pcm.length);
        }

        assertTrue(Files.exists(out.toPath()), "file written");
        assertTrue(Files.size(out.toPath()) > pcm.length, "file size > pcm size (header present)");

        try (AudioInputStream in = PcmFileLoader.instance().openAsPcm(out)) {
            AudioFormat format = in.getFormat();
            assertEquals(sampleRate,    format.getSampleRate());
            assertEquals(channels,      format.getChannels());
            assertEquals(bitsPerSample, format.getSampleSizeInBits());
            assertEquals(frames,        in.getFrameLength());
        }
    }

    @Test
    void pcm16_stereo_perSampleValuesRoundTrip(@TempDir Path tmp) throws Exception {
        int sampleRate    = 48_000;
        int frames        = 50;
        File out = tmp.resolve("samples.wav").toFile();

        // Write 50 frames where left[f] = f, right[f] = -f.
        ByteBuffer bb = ByteBuffer.allocate(frames * 2 * 2)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int f = 0; f < frames; f++) {
            bb.putShort((short)  f);
            bb.putShort((short) -f);
        }

        try (WavWriter w = new WavWriter(out, sampleRate, 2, 16, false)) {
            w.writeRaw(bb.array(), bb.array().length);
        }

        // Read back: the loader hands 16-bit WAV PCM through as signed
        // little-endian, so every frame decodes to the value it was
        // written with.
        byte[] back;
        try (AudioInputStream in = PcmFileLoader.instance().openAsPcm(out)) {
            assertEquals(AudioFormat.Encoding.PCM_SIGNED, in.getFormat().getEncoding());
            assertEquals(frames, in.getFrameLength(), "frame count round-trip");
            back = in.readAllBytes();
        }
        ByteBuffer rb = ByteBuffer.wrap(back).order(ByteOrder.LITTLE_ENDIAN);
        for (int f = 0; f < frames; f++) {
            assertEquals(f,  rb.getShort(), "left frame " + f);
            assertEquals(-f, rb.getShort(), "right frame " + f);
        }
    }

    @Test
    void pcm24_stereo_headerMatches(@TempDir Path tmp) throws Exception {
        // 24-bit PCM: 3 bytes per sample, 2 channels -> 6 bytes per frame.
        int frames = 5;
        File out = tmp.resolve("24bit.wav").toFile();
        try (WavWriter w = new WavWriter(out, 192_000, 2, 24, false)) {
            byte[] silence = new byte[frames * 2 * 3];
            w.writeRaw(silence, silence.length);
        }
        try (AudioInputStream in = PcmFileLoader.instance().openAsPcm(out)) {
            AudioFormat format = in.getFormat();
            assertEquals(192_000, format.getSampleRate());
            assertEquals(2,       format.getChannels());
            assertEquals(24,      format.getSampleSizeInBits());
            assertEquals(frames,  in.getFrameLength());
        }
    }

    @Test
    void pcm16_mono_headerMatches(@TempDir Path tmp) throws Exception {
        File out = tmp.resolve("mono.wav").toFile();
        try (WavWriter w = new WavWriter(out, 44_100, 1, 16, false)) {
            // 10 frames of silence
            byte[] silence = new byte[10 * 2];
            w.writeRaw(silence, silence.length);
        }
        try (AudioInputStream in = PcmFileLoader.instance().openAsPcm(out)) {
            AudioFormat format = in.getFormat();
            assertEquals(44_100, format.getSampleRate());
            assertEquals(1,      format.getChannels());
            assertEquals(10,     in.getFrameLength());
        }
    }
}
