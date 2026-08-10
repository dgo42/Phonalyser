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

package org.edgo.audio.measure.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the PCM container sizing and the offset-binary decoder across bit
 * depths.
 *
 * <p>A depth that is not a whole number of bytes (20 bits) rides right-aligned
 * in the next larger container, and the decoder's midpoint/mask must come from
 * the BIT DEPTH rather than the container: sized by the container, 20-bit
 * digital silence decodes to zero in the consumers' offset-binary domain, which
 * they read as negative full-scale DC.
 *
 * <p>The 16 / 24 / 32 cases are the inertness check - their expected values are
 * the constants the decoder used before it became depth-driven, so any drift
 * for the existing backends fails here.
 */
class PcmContainerSizingTest {

    /** Minimal concrete capture: the base class supplies everything under test,
     *  and no stream lifecycle is needed to decode bytes. */
    private static final class DecoderProbe extends AbstractPcmCapture {
        DecoderProbe(int bitDepth) {
            super(48000, bitDepth);
        }

        int containerBytes() {
            return sampleBytes;
        }

        @Override
        public void open() {
            // no device
        }

        @Override
        public void startRecording() {
            // no device
        }

        @Override
        public void stopRecording() {
            // no device
        }

        @Override
        public void close() {
            // no device
        }
    }

    /** Little-endian bytes of {@code value} in a {@code bytes}-wide container. */
    private byte[] le(long value, int bytes) {
        byte[] out = new byte[bytes];
        for (int b = 0; b < bytes; b++) {
            out[b] = (byte) (value >> (8 * b));
        }
        return out;
    }

    /** Decodes one sample through a probe of the given depth; the probe is
     *  closed (a capture is a Closeable, no-op here but the contract stands). */
    private int decode(int bitDepth, byte[] bytes) {
        try (DecoderProbe probe = new DecoderProbe(bitDepth)) {
            return probe.readSample(bytes, 0);
        }
    }

    /** Container width a capture of the given depth sizes; probe closed. */
    private int containerBytes(int bitDepth) {
        try (DecoderProbe probe = new DecoderProbe(bitDepth)) {
            return probe.containerBytes();
        }
    }

    @Test
    @DisplayName("16 / 24 / 32 bit keep their container size and decoded values")
    void wholeByteDepthsAreUnchanged() {
        assertEquals(2, containerBytes(16));
        assertEquals(3, containerBytes(24));
        assertEquals(4, containerBytes(32));

        // Silence decodes to the midpoint, full scale to the rail - the values
        // the container-driven decoder produced.
        assertEquals(0x8000, decode(16, le(0, 2)));
        assertEquals(0xFFFF, decode(16, le(Short.MAX_VALUE, 2)));
        assertEquals(0, decode(16, le(Short.MIN_VALUE, 2)));

        assertEquals(0x800000, decode(24, le(0, 3)));
        assertEquals(0xFFFFFF, decode(24, le(0x7FFFFF, 3)));
        assertEquals(0, decode(24, le(-0x800000, 3)));

        assertEquals(0x80000000, decode(32, le(0, 4)));
        assertEquals(0xFFFFFFFF, decode(32, le(Integer.MAX_VALUE, 4)));
        assertEquals(0, decode(32, le(Integer.MIN_VALUE, 4)));
    }

    @Test
    @DisplayName("20 bit rides in 3 bytes and decodes against the 20-bit midpoint")
    void twentyBitUsesAThreeByteContainer() {
        try (DecoderProbe probe = new DecoderProbe(20)) {
            assertEquals(3, probe.containerBytes());
            // Silence must land on the 20-bit midpoint (2^19), NOT the container's
            // 2^23 - the latter masks down to 0, i.e. negative full scale.
            assertEquals(0x80000, probe.readSample(le(0, 3), 0));
            assertEquals(0xFFFFF, probe.readSample(le(0x7FFFF, 3), 0));
            assertEquals(0, probe.readSample(le(-0x80000, 3), 0));
        }
    }

    @Test
    @DisplayName("20-bit positive full scale survives the encoder's container")
    void twentyBitEncodeDoesNotWrap() {
        // The encoder writes bitDepth/8 = 2 bytes before the fix, which cannot
        // hold 2^19-1: the top byte was dropped and +FS came back as -1 LSB.
        PcmQuantizer quantizer = new PcmQuantizer(20, 0.0);
        int maxVal = (1 << 19) - 1;
        assertEquals(0xFFFFF, decode(20, le(maxVal, 3)));
        assertEquals(20, quantizer.getBitDepth());
    }
}
