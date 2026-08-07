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

package org.edgo.audio.measure.net.server;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.net.proto.NetMessage;

/**
 * The identity of one lockable resource: a device AND a direction (spec 4.3 -
 * "locks are per {@code (backend, index, direction)}").  A duplex device is two
 * locks, so one client may capture from an interface while another drives its
 * output.
 *
 * <p>The device ref on the wire also carries a {@code name}, and it is
 * deliberately NOT part of this key: the name is what the server validates
 * against the current enumeration to answer {@code DEVICE_STALE} after a
 * hot-plug, not part of the identity two clients contend for.
 */
public record DeviceLock(AudioBackendType backend, int index, boolean input) {

    /** The lock a request's device ref names.  {@code backend} is matched
     *  against the enum NAME, exactly as spec 4.3 puts it on the wire
     *  ({@code "JAVASOUND"}, {@code "QA40X"}) - not against the command line's
     *  friendly aliases, and not against what this host happens to support:
     *  whether the device is there is answered by the enumeration, with
     *  {@code DEVICE_STALE}.
     *
     *  @throws IllegalArgumentException when a ref field is missing or the
     *          backend is not a known name - the caller answers
     *          {@code BAD_REQUEST}. */
    public static DeviceLock fromMessage(NetMessage message) {
        String backend = message.optString(NetFields.BACKEND);
        Integer index = message.optInt(NetFields.INDEX);
        Boolean input = message.optBoolean(NetFields.INPUT);
        if (backend == null || index == null || input == null) {
            throw new IllegalArgumentException(
                    "device ref needs backend, index and input, got: " + message);
        }
        try {
            return new DeviceLock(AudioBackendType.valueOf(backend), index, input);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown backend: " + backend, e);
        }
    }

    /** How the device reads in an error message or a log line. */
    @Override
    public String toString() {
        return backend + "[" + index + "] " + (input ? "input" : "output");
    }
}
