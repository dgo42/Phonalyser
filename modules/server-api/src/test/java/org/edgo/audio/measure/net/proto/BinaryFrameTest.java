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

package org.edgo.audio.measure.net.proto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Wire conformance of the 16-byte binary header (spec 5).
 *
 * <p>The golden arrays are written out byte by byte BY HAND from the spec table
 * - not produced by the encoder - so they prove the field offsets and the
 * little-endian order rather than merely proving the encoder agrees with
 * itself.  Values are chosen to expose sign mistakes: a {@code streamId} of
 * 0xFFFF (would be −1 as a signed short), a {@code packetCounter} above 2^32
 * (would be truncated by a 32-bit read) and a multi-byte {@code n}.
 */
class BinaryFrameTest {

    private static final int STREAM_ID = 0x1234;
    private static final long COUNTER = 0x0102030405060708L;

    @Test
    void pcmHeaderMatchesTheHandWrittenGoldenBytes() {
        byte[] payload = {0x11, 0x22, 0x33, 0x44};
        BinaryFrame frame = new BinaryFrame(FrameType.PCM, STREAM_ID, COUNTER, payload);

        byte[] expected = {
            0x01,                                            // frameType = PCM
            0x00,                                            // reserved
            0x34, 0x12,                                      // streamId 0x1234 LE
            0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01,  // counter u64 LE
            0x04, 0x00, 0x00, 0x00,                          // n = 4 payload bytes
            0x11, 0x22, 0x33, 0x44,                          // payload
        };
        assertArrayEquals(expected, frame.toBytes(),
                "PCM frame must match the spec's byte layout exactly");
    }

    @Test
    void markerHeaderMatchesTheHandWrittenGoldenBytes() {
        BinaryFrame frame = new BinaryFrame(FrameType.MARKER, 7, 5L,
                BinaryFrame.MARKER_SWEEP_START);

        byte[] expected = {
            0x02,                                            // frameType = MARKER
            0x00,                                            // reserved
            0x07, 0x00,                                      // streamId 7 LE
            0x05, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // counter 5 u64 LE
            0x01, 0x00, 0x00, 0x00,                          // n = kind sweepStart
        };
        assertArrayEquals(expected, frame.toBytes(),
                "a MARKER frame is header-only, kind 1 = sweepStart");
        assertEquals(BinaryFrame.HEADER_BYTES, frame.toBytes().length,
                "MARKER carries no payload");
    }

    @Test
    void gapHeaderMatchesTheHandWrittenGoldenBytes() {
        long counterPast32Bits = 4_294_967_296L;             // 2^32
        int lostStereoFrames = 1_000;                        // 0x3E8
        BinaryFrame frame = new BinaryFrame(FrameType.GAP, 0xFFFF, counterPast32Bits,
                lostStereoFrames);

        byte[] expected = {
            0x03,                                            // frameType = GAP
            0x00,                                            // reserved
            (byte) 0xFF, (byte) 0xFF,                        // streamId 65535 LE
            0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00,  // counter 2^32 u64 LE
            (byte) 0xE8, 0x03, 0x00, 0x00,                   // n = 1000 lost frames
        };
        assertArrayEquals(expected, frame.toBytes(),
                "GAP frame must match the spec's byte layout exactly");
    }

    @Test
    void pcmRoundTripsThroughParse() {
        byte[] payload = {(byte) 0x80, 0x7F, (byte) 0xFF, 0x00, 0x01, (byte) 0xAA};
        BinaryFrame sent = new BinaryFrame(FrameType.PCM, STREAM_ID, COUNTER, payload);

        BinaryFrame received = BinaryFrame.parse(sent.toBytes());

        assertEquals(FrameType.PCM, received.type());
        assertEquals(STREAM_ID, received.streamId());
        assertEquals(COUNTER, received.packetCounter());
        assertEquals(payload.length, received.n());
        assertArrayEquals(payload, received.payload(),
                "PCM payload bytes must survive the round trip untouched");
    }

    @Test
    void unsignedHeaderFieldsSurviveTheirTopBit() {
        BinaryFrame received = BinaryFrame.parse(
                new BinaryFrame(FrameType.GAP, 0xFFFF, -1L, 0xFFFFFFFFL).toBytes());

        assertEquals(0xFFFF, received.streamId(),
                "streamId is u16: 65535, never −1");
        assertEquals(0xFFFFFFFFL, received.n(),
                "n is u32: 4294967295, never −1");
        assertEquals(-1L, received.packetCounter(),
                "packetCounter is u64: all-ones comes back as the same 64 bits");
    }

    @Test
    void markerAndGapRoundTripTheirNValue() {
        BinaryFrame marker = BinaryFrame.parse(new BinaryFrame(FrameType.MARKER, 3, 9L,
                BinaryFrame.MARKER_SWEEP_START).toBytes());
        assertEquals(FrameType.MARKER, marker.type());
        assertEquals(BinaryFrame.MARKER_SWEEP_START, marker.n());
        assertEquals(0, marker.payload().length);

        BinaryFrame gap = BinaryFrame.parse(new BinaryFrame(FrameType.GAP, 3, 10L, 480L)
                .toBytes());
        assertEquals(FrameType.GAP, gap.type());
        assertEquals(480L, gap.n(), "GAP n is the lost stereo frame count");
    }

    @Test
    void reservedUplinkTypeIsDeclaredButNeverPcm() {
        assertEquals(4, FrameType.UPLINK_PCM.getCode(),
                "type 4 stays reserved for client to server audio (spec 7)");
        assertEquals(FrameType.UPLINK_PCM, FrameType.fromCode(4));
    }

    @Test
    void unknownFrameTypeIsSkippableNotFatal() {
        byte[] wire = new BinaryFrame(9, 2, 4L, 3L, new byte[] {1, 2, 3}).toBytes();

        BinaryFrame received = BinaryFrame.parse(wire);

        assertEquals(FrameType.UNKNOWN, received.type(),
                "an unknown frame type must decode, so the receiver can skip it");
        assertEquals(9, received.typeCode(), "the raw code stays readable");
        assertEquals(3, received.payload().length);
    }

    @Test
    void shortMessageIsRejected() {
        byte[] truncated = new byte[BinaryFrame.HEADER_BYTES - 1];

        assertThrows(IllegalArgumentException.class, () -> BinaryFrame.parse(truncated),
                "a message shorter than the header is not a frame");
    }

    @Test
    void pcmWithMismatchedLengthIsRejected() {
        // n says 8 payload bytes, the message carries 4 - a corrupt sender.
        byte[] wire = new BinaryFrame(FrameType.PCM.getCode(), 1, 0L, 8L,
                new byte[] {1, 2, 3, 4}).toBytes();

        assertThrows(IllegalArgumentException.class, () -> BinaryFrame.parse(wire),
                "a PCM frame whose n disagrees with its payload length is corrupt");
    }
}
