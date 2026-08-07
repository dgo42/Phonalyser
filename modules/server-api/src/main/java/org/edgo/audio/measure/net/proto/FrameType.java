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

import lombok.Getter;

/**
 * The {@code frameType} byte at offset 0 of a binary audio frame (spec 5).
 *
 * <p>The meaning of the header's {@code n} field depends on this type: payload
 * byte count for {@link #PCM}, marker kind for {@link #MARKER}, lost stereo
 * frames for {@link #GAP}.
 */
public enum FrameType {

    /** Captured audio: {@code n} = payload byte count, native interleaved PCM. */
    PCM(1),

    /** In-band position mark, no payload: {@code n} = marker kind. */
    MARKER(2),

    /** Server-side data loss confession: {@code n} = lost stereo frames. */
    GAP(3),

    /** Reserved for client-to-server audio (the playback skeleton of spec 7).
     *  NOT implemented in v1 - never sent, never accepted. */
    UPLINK_PCM(4),

    /** Not a wire value - what {@link #fromCode} answers for a type this build
     *  does not know. Spec 1: unknown binary frames are skipped. */
    UNKNOWN(0);

    /** The byte written at offset 0. */
    @Getter
    private final int code;

    private FrameType(int code) {
        this.code = code;
    }

    /** Never throws: an unrecognised code maps to {@link #UNKNOWN}. */
    public static FrameType fromCode(int code) {
        for (FrameType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        return UNKNOWN;
    }
}
