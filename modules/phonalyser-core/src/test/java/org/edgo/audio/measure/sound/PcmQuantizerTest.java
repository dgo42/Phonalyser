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

import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.enums.OutputChannels;
import org.edgo.audio.measure.generator.SignalGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PcmQuantizer} per-channel output: proves the encoder is byte-identical
 * to the pre-per-channel encoding in the default (BOTH + unity-scale) case, that
 * the Left/Right gate silences the un-selected lane (mid-code 0), and that the
 * right-lane scale is applied only to the right slot.
 *
 * <p>Dither is left OFF ({@code ditherBits == 0}) so {@code encode} is fully
 * deterministic and the two lanes come from the same {@code nextSample()} draw —
 * every assertion compares within one buffer, no RNG reproducibility needed.
 * The generator is a plain SINE DDS (no internal randomness), so a fresh
 * instance per encoder yields an identical sample stream.
 */
class PcmQuantizerTest {

    private static final int    SAMPLE_RATE = 48_000;
    private static final double FREQ_HZ     = 1_000.0;
    private static final double VRMS        = 1.0;
    private static final double DAC_FS_VRMS = 2.79351;
    private static final int    FRAMES      = 480;   // 10 full cycles at 1 kHz / 48 kHz

    private SignalGenerator sine() {
        return new SignalGenerator(GenSignalForm.SINE, FREQ_HZ, SAMPLE_RATE, VRMS, DAC_FS_VRMS);
    }

    /** Reference encoder replicating the pre-per-channel behaviour: one sample
     *  written identically to both lanes, no scale, no gate. */
    private byte[] legacyEncode(SignalGenerator gen, int bitDepth, int frames) {
        int bytesPerSample = bitDepth / 8;
        int bytesPerFrame  = bytesPerSample * 2;
        byte[] buf = new byte[frames * bytesPerFrame];
        if (bitDepth == 8) {
            for (int i = 0; i < frames; i++) {
                double sample = clamp(gen.nextSample());
                byte   val    = (byte) Math.round(sample * 127.0);
                int    off    = i * bytesPerFrame;
                buf[off]     = val;
                buf[off + 1] = val;
            }
        } else {
            long maxVal = (1L << (bitDepth - 1)) - 1;
            for (int i = 0; i < frames; i++) {
                double sample = clamp(gen.nextSample());
                long   pcm    = (long) Math.round(sample * maxVal);
                int    off    = i * bytesPerFrame;
                for (int b = 0; b < bytesPerSample; b++) {
                    byte bv = (byte) (pcm >> (8 * b));
                    buf[off + b]                  = bv;
                    buf[off + bytesPerSample + b] = bv;
                }
            }
        }
        return buf;
    }

    private double clamp(double v) {
        return v > 1.0 ? 1.0 : (v < -1.0 ? -1.0 : v);
    }

    /** Little-endian signed sample decode from lane byte offset {@code base}. */
    private long decode(byte[] buf, int base, int bytesPerSample) {
        long v = 0;
        for (int b = 0; b < bytesPerSample; b++) {
            v |= (buf[base + b] & 0xFFL) << (8 * b);
        }
        // sign-extend from bytesPerSample*8 bits
        int shift = 64 - bytesPerSample * 8;
        return (v << shift) >> shift;
    }

    @Test
    void bothWithUnityScale_isByteIdenticalToLegacy_16bit() {
        byte[] expected = legacyEncode(sine(), 16, FRAMES);

        PcmQuantizer q = new PcmQuantizer(16, 0);   // defaults: scale 1.0, gate BOTH
        byte[] actual = new byte[FRAMES * 2 * 2];
        q.encode(sine(), actual, FRAMES);

        assertArrayEquals(expected, actual,
                "default (BOTH + unity scale) 16-bit encoding must be byte-identical to the legacy encoder");
    }

    @Test
    void bothWithUnityScale_isByteIdenticalToLegacy_24bit() {
        byte[] expected = legacyEncode(sine(), 24, FRAMES);

        PcmQuantizer q = new PcmQuantizer(24, 0);
        q.setChannelScale(1.0, 1.0);
        q.setOutputChannels(OutputChannels.BOTH);
        byte[] actual = new byte[FRAMES * 3 * 2];
        q.encode(sine(), actual, FRAMES);

        assertArrayEquals(expected, actual,
                "explicit BOTH + unity scale 24-bit encoding must be byte-identical to the legacy encoder");
    }

    @Test
    void bothWithUnityScale_isByteIdenticalToLegacy_8bit() {
        byte[] expected = legacyEncode(sine(), 8, FRAMES);

        PcmQuantizer q = new PcmQuantizer(8, 0);
        byte[] actual = new byte[FRAMES * 2];
        q.encode(sine(), actual, FRAMES);

        assertArrayEquals(expected, actual,
                "default 8-bit encoding must be byte-identical to the legacy encoder");
    }

    @Test
    void bothLanesEqual_whenScalesEqual_16bit() {
        PcmQuantizer q = new PcmQuantizer(16, 0);
        byte[] buf = new byte[FRAMES * 2 * 2];
        q.encode(sine(), buf, FRAMES);

        for (int i = 0; i < FRAMES; i++) {
            int off = i * 4;
            assertEquals(decode(buf, off, 2), decode(buf, off + 2, 2),
                    "BOTH + unity scale must write identical left/right at frame " + i);
        }
    }

    @Test
    void gateLeft_zeroesRightSlot_16bit() {
        PcmQuantizer q = new PcmQuantizer(16, 0);
        q.setOutputChannels(OutputChannels.LEFT);
        byte[] buf = new byte[FRAMES * 2 * 2];
        q.encode(sine(), buf, FRAMES);

        byte[] leftRef = legacyEncode(sine(), 16, FRAMES);
        boolean sawNonZeroLeft = false;
        for (int i = 0; i < FRAMES; i++) {
            int off = i * 4;
            // Left lane unchanged from the legacy left sample.
            assertEquals(decode(leftRef, off, 2), decode(buf, off, 2),
                    "LEFT gate must leave the left lane untouched at frame " + i);
            assertEquals(0L, decode(buf, off + 2, 2),
                    "LEFT gate must silence the right lane at frame " + i);
            if (decode(buf, off, 2) != 0L) sawNonZeroLeft = true;
        }
        assertTrue(sawNonZeroLeft, "left lane should carry the tone (not all silence)");
    }

    @Test
    void gateRight_zeroesLeftSlot_16bit() {
        PcmQuantizer q = new PcmQuantizer(16, 0);
        q.setOutputChannels(OutputChannels.RIGHT);
        byte[] buf = new byte[FRAMES * 2 * 2];
        q.encode(sine(), buf, FRAMES);

        byte[] rightRef = legacyEncode(sine(), 16, FRAMES);
        boolean sawNonZeroRight = false;
        for (int i = 0; i < FRAMES; i++) {
            int off = i * 4;
            assertEquals(0L, decode(buf, off, 2),
                    "RIGHT gate must silence the left lane at frame " + i);
            assertEquals(decode(rightRef, off, 2), decode(buf, off + 2, 2),
                    "RIGHT gate must leave the right lane untouched at frame " + i);
            if (decode(buf, off + 2, 2) != 0L) sawNonZeroRight = true;
        }
        assertTrue(sawNonZeroRight, "right lane should carry the tone (not all silence)");
    }

    @Test
    void gateLeft_zeroesRightSlot_8bit() {
        PcmQuantizer q = new PcmQuantizer(8, 0);
        q.setOutputChannels(OutputChannels.LEFT);
        byte[] buf = new byte[FRAMES * 2];
        q.encode(sine(), buf, FRAMES);

        for (int i = 0; i < FRAMES; i++) {
            int off = i * 2;
            assertEquals((byte) 0, buf[off + 1],
                    "LEFT gate must silence the signed-8-bit right slot (0) at frame " + i);
        }
    }

    @Test
    void scaleR_appliedToRightLaneOnly_16bit() {
        // Right full-scale is half the left → scaleR = 2.0: the right slot is
        // twice the left slot (up to clamping), the left slot is unchanged.
        PcmQuantizer q = new PcmQuantizer(16, 0);
        q.setChannelScale(1.0, 2.0);
        byte[] buf = new byte[FRAMES * 2 * 2];
        q.encode(sine(), buf, FRAMES);

        byte[] leftRef = legacyEncode(sine(), 16, FRAMES);
        // Expected right computed from a fresh generator with the SAME arithmetic
        // the quantizer uses on the true (pre-quantization) sample — comparing
        // against the quantized left code would inject a spurious ±1 LSB error.
        SignalGenerator ref = sine();
        long maxVal = (1L << 15) - 1;
        for (int i = 0; i < FRAMES; i++) {
            int off = i * 4;
            long left  = decode(buf, off, 2);
            long right = decode(buf, off + 2, 2);
            assertEquals(decode(leftRef, off, 2), left,
                    "left lane (scale 1.0) must be unchanged at frame " + i);
            long expectedRight = Math.round(clamp(ref.nextSample() * 2.0) * maxVal);
            assertEquals(expectedRight, right,
                    "right lane must apply scaleR=2.0 (with clamp) at frame " + i);
        }
    }

    @Test
    void scaleR_clampsWithoutOverflow_16bit() {
        // A large right scale drives the right lane past full-scale; it must
        // clamp to ±(2^15−1), never wrap to a negative code.
        PcmQuantizer q = new PcmQuantizer(16, 0);
        q.setChannelScale(1.0, 100.0);
        byte[] buf = new byte[FRAMES * 2 * 2];
        q.encode(sine(), buf, FRAMES);

        long maxVal = (1L << 15) - 1;
        for (int i = 0; i < FRAMES; i++) {
            int off = i * 4;
            long right = decode(buf, off + 2, 2);
            assertTrue(right >= -maxVal - 1 && right <= maxVal,
                    "right lane must stay within signed-16-bit range at frame " + i + " (was " + right + ")");
        }
    }
}
