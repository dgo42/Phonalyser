package org.edgo.audio.measure.wav;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.edgo.audio.measure.common.StereoSample;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RIFF round-trip tests: write a known PCM byte stream via {@link WavWriter}
 * and read it back via {@link WavReader}, confirming header metadata
 * (sample rate, channels, bit depth, frame count) and per-sample values
 * survive the write→read pass.  Catches endianness, header-finalisation
 * and channel-interleave regressions.
 */
class WavRoundtripTest {

    @Test
    void pcm16_stereo_header_andFrameCountMatch(@TempDir Path tmp) throws IOException {
        int sampleRate    = 48_000;
        int channels      = 2;
        int bitsPerSample = 16;
        int frames        = 100;

        File out = tmp.resolve("test.wav").toFile();

        // Build a known PCM16 stereo byte stream: left channel ramps
        // 0 → 99 as little-endian 16-bit signed; right channel mirrors
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

        WavReader r = new WavReader(out.getAbsolutePath());
        assertEquals(sampleRate,    r.getSampleRate());
        assertEquals(channels,      r.getChannels());
        assertEquals(bitsPerSample, r.getBitsPerSample());
        assertEquals(frames,        r.getFrameCount());
    }

    @Test
    void pcm16_stereo_perSampleValuesRoundTrip(@TempDir Path tmp) throws IOException {
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

        // Read back; WavReader.process() delivers samples as
        // OFFSET-BINARY (unsigned 0..2^bits-1) so signed value v becomes
        // (v - SHORT_MIN).  WavReader's readSample for 16-bit:
        //   s = (short)(...);  return (s - 0x8000) & 0xFFFF;
        // So a written +0  arrives as 0xFFFF8000 & 0xFFFF = 0x8000 = 32768
        //    a written -1   arrives as 0xFFFF7FFF & 0xFFFF = 0x7FFF = 32767
        //    a written +1   arrives as 0xFFFF8001 & 0xFFFF = 0x8001 = 32769
        // Verify the round-trip is invariant: same offset-binary
        // encoding the rest of the pipeline expects.
        List<int[]> out2 = new ArrayList<>();
        new WavReader(out.getAbsolutePath()).process(samples -> {
            for (StereoSample s : samples) {
                out2.add(new int[]{ s.ch0, s.ch1 });
            }
        });
        assertEquals(frames, out2.size(), "frame count round-trip");

        for (int f = 0; f < frames; f++) {
            int expectedLeft  = (f - 0x8000) & 0xFFFF;
            int expectedRight = (-f - 0x8000) & 0xFFFF;
            assertEquals(expectedLeft,  out2.get(f)[0], "left frame " + f);
            assertEquals(expectedRight, out2.get(f)[1], "right frame " + f);
        }
    }

    @Test
    void pcm24_stereo_headerMatches(@TempDir Path tmp) throws IOException {
        // 24-bit PCM: 3 bytes per sample, 2 channels → 6 bytes per frame.
        int frames = 5;
        File out = tmp.resolve("24bit.wav").toFile();
        try (WavWriter w = new WavWriter(out, 192_000, 2, 24, false)) {
            byte[] silence = new byte[frames * 2 * 3];
            w.writeRaw(silence, silence.length);
        }
        WavReader r = new WavReader(out.getAbsolutePath());
        assertEquals(192_000, r.getSampleRate());
        assertEquals(2,       r.getChannels());
        assertEquals(24,      r.getBitsPerSample());
        assertEquals(frames,  r.getFrameCount());
    }

    @Test
    void pcm16_mono_headerMatches(@TempDir Path tmp) throws IOException {
        File out = tmp.resolve("mono.wav").toFile();
        try (WavWriter w = new WavWriter(out, 44_100, 1, 16, false)) {
            // 10 frames of silence
            byte[] silence = new byte[10 * 2];
            w.writeRaw(silence, silence.length);
        }
        WavReader r = new WavReader(out.getAbsolutePath());
        assertEquals(44_100, r.getSampleRate());
        assertEquals(1,      r.getChannels());
        assertEquals(10,     r.getFrameCount());
    }
}
