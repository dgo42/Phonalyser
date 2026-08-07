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

/**
 * The complete error vocabulary of spec 4.2.  The constant name IS the wire
 * text, so there is nothing to keep in sync; a code a peer does not know stays
 * readable because {@link NetError} keeps the raw string and the human-readable
 * {@code message} beside it.
 */
public enum ErrorCode {

    /** The two version RANGES do not overlap, so no session is possible and the
     *  server closes.  Spec 1 negotiates ranges, NOT equality: {@code hello}
     *  offers {@code [protoMin..proto]} (an absent {@code protoMin} means the
     *  range is just {@code proto}), the server intersects it with its own
     *  {@link NetProto#PROTO_MIN_VERSION}..{@link NetProto#PROTO_VERSION} and
     *  answers with the HIGHEST version inside both - so a v2 client offering
     *  {@code proto=2, protoMin=1} MUST be accepted and served at v1.  Only an
     *  empty intersection is this error, and the message names both ranges. */
    PROTO_MISMATCH,

    /** Malformed message, or a value the protocol forbids (mismatched rates). */
    BAD_REQUEST,

    /** Unknown message type - the forward-compatibility answer of spec 1. */
    UNSUPPORTED,

    /** The connection does not hold the lock this call requires. */
    NOT_LOCKED,

    /** Another connection holds the lock; the answer carries {@code by}. */
    DEVICE_LOCKED,

    /** The device index moved (hot-plug re-enumeration): the name no longer
     *  matches, so the client must re-list before retrying. */
    DEVICE_STALE,

    /** Server-side capture/playback failure or hardware detach. */
    DEVICE_ERROR,

    /** A device ref names a backend other than the one this session committed to
     *  with {@code backend.select} (spec 4.3).  Selection is per-connection, so
     *  this says the CLIENT is confused about its own choice, not that the
     *  server lacks the backend - and it is deliberately not
     *  {@code DEVICE_STALE}: nothing moved, and re-listing would not help. */
    BACKEND_MISMATCH,

    /** No uploaded file with that id (never uploaded, or already dropped). */
    NO_SUCH_FILE,

    /** Upload above the size cap. */
    FILE_TOO_LARGE,

    /** Anything unexpected on the server; the message carries the detail. */
    INTERNAL;

    private ErrorCode() {
    }
}
