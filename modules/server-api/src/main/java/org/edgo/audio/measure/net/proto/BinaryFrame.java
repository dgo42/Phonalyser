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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * One WebSocket binary audio frame (spec 5): a fixed 16-byte little-endian
 * header followed by the payload.
 *
 * <pre>
 *   offset  size  field
 *        0  u8    frameType   1 = PCM, 2 = MARKER, 3 = GAP, 4 = reserved
 *        1  u8    reserved, 0
 *        2  u16   streamId    = captureId
 *        4  u64   packetCounter  per stream, +1 per frame of ANY type
 *       12  u32   n           payload bytes / marker kind / lost frames
 *       16  ...   payload
 * </pre>
 *
 * <p>Little-endian throughout - the QA40x native order, and what a browser
 * data view reads without byte shuffling.  The unsigned header fields are held
 * in wider signed Java types ({@code streamId} in an int, {@code n} in a long)
 * so a value with the top bit set never arrives negative.
 *
 * <p>The frame is a record, so equality on {@code payload} is array IDENTITY;
 * compare the encoded bytes (or the individual accessors) in tests, never two
 * frames directly.
 */
public record BinaryFrame(int typeCode, int streamId, long packetCounter, long n,
        byte[] payload) {

    /** Fixed header size - the payload starts here. */
    public static final int HEADER_BYTES = 16;

    /** {@link FrameType#MARKER} kind 1: the first PCM byte after this frame is
     *  aligned with sweep output sample 0, to within one capture batch. */
    public static final int MARKER_SWEEP_START = 1;

    private static final byte[] EMPTY_PAYLOAD = new byte[0];

    /** PCM frame: {@code n} is the payload byte count by construction. */
    public BinaryFrame(FrameType type, int streamId, long packetCounter, byte[] payload) {
        this(type.getCode(), streamId, packetCounter, payload.length, payload);
    }

    /** MARKER or GAP frame: no payload, {@code n} carries the marker kind or
     *  the number of lost stereo frames. */
    public BinaryFrame(FrameType type, int streamId, long packetCounter, long n) {
        this(type.getCode(), streamId, packetCounter, n, EMPTY_PAYLOAD);
    }

    /** Decodes one received binary message. Everything after the header is the
     *  payload - the WebSocket message boundary carries the total length, which
     *  is what lets a receiver skip a frame type it does not know (spec 1). */
    public static BinaryFrame parse(byte[] message) {
        if (message.length < HEADER_BYTES) {
            throw new IllegalArgumentException(
                    "net binary frame shorter than its header: " + message.length
                            + " < " + HEADER_BYTES + " bytes");
        }
        ByteBuffer buf = ByteBuffer.wrap(message).order(ByteOrder.LITTLE_ENDIAN);
        int typeCode = buf.get() & 0xFF;
        buf.get();                              // offset 1: reserved
        int streamId = buf.getShort() & 0xFFFF;
        long packetCounter = buf.getLong();
        long n = buf.getInt() & 0xFFFFFFFFL;
        byte[] payload = new byte[message.length - HEADER_BYTES];
        buf.get(payload);
        if (typeCode == FrameType.PCM.getCode() && n != payload.length) {
            throw new IllegalArgumentException(
                    "PCM frame declares n=" + n + " but carries " + payload.length
                            + " payload bytes");
        }
        return new BinaryFrame(typeCode, streamId, packetCounter, n, payload);
    }

    /** The decoded type; {@link FrameType#UNKNOWN} for a code this build does
     *  not know, which the receiver skips. */
    public FrameType type() {
        return FrameType.fromCode(typeCode);
    }

    /** The frame as it goes on the wire: header + payload, one array. */
    public byte[] toBytes() {
        byte[] out = new byte[HEADER_BYTES + payload.length];
        ByteBuffer buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) typeCode);
        buf.put((byte) 0);                      // offset 1: reserved
        buf.putShort((short) streamId);
        buf.putLong(packetCounter);
        buf.putInt((int) n);
        buf.put(payload);
        return out;
    }
}
