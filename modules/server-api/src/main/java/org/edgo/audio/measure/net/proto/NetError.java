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

import org.edgo.audio.measure.enums.DeviceFailureReason;

/**
 * The {@code error} object of a failed response (spec 4.0/4.2):
 * {@code {"code":"DEVICE_LOCKED","message":"...","by":"Developer's laptop"}}.
 *
 * <p>{@code code} is kept as the raw wire string, not as an {@link ErrorCode},
 * so a code from a newer peer survives intact for display; ask about a known
 * code with {@link #is(ErrorCode)} instead of comparing text. {@code by} is
 * present only for {@link ErrorCode#DEVICE_LOCKED} and is otherwise null.
 *
 * <p>{@code reason} is the same kind of optional extra for a DEVICE refusal:
 * the name of a {@link DeviceFailureReason}, so a client can tell the operator
 * WHY the far end's device would not open in the operator's own language -
 * {@code message} is the server's language and its driver text is nobody's.
 * Null for every fault that is not a device's, and null from any peer that
 * does not send it; {@link #failureReason()} answers {@code UNKNOWN} then.
 */
public record NetError(String code, String message, String by, String reason) {

    /** Error without a lock owner - the usual case. */
    public NetError(ErrorCode code, String message) {
        this(code.name(), message, null, null);
    }

    /** {@code DEVICE_LOCKED} and anything else that names the holder. */
    public NetError(ErrorCode code, String message, String by) {
        this(code.name(), message, by, null);
    }

    /** A device refusal that knows why it refused.  An {@code UNKNOWN} reason
     *  is left OFF the wire rather than spelled out: it is what the far side
     *  reads from an absent field anyway, and every non-device refusal would
     *  otherwise carry a device reason that says nothing. */
    public NetError(ErrorCode code, String message, DeviceFailureReason reason) {
        this(code.name(), message, null,
                (reason == null || reason == DeviceFailureReason.UNKNOWN) ? null : reason.name());
    }

    /** The reason this error carries, or {@link DeviceFailureReason#UNKNOWN}
     *  when it carries none - including a name only a newer peer knows. */
    public DeviceFailureReason failureReason() {
        return DeviceFailureReason.fromName(reason);
    }

    /** True when this error carries exactly {@code expected}. */
    public boolean is(ErrorCode expected) {
        return expected.name().equals(code);
    }
}
