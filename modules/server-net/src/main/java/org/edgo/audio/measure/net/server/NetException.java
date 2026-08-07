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

import org.edgo.audio.measure.enums.DeviceFailureReason;
import org.edgo.audio.measure.net.proto.ErrorCode;

import lombok.Getter;

/**
 * A request that failed for a reason spec 4.2 names - a stale device, a rate the
 * hardware forbids, a capture id nobody opened.
 *
 * <p>It exists so the code that DETECTS the fault does not have to own the
 * transport: {@link DeviceCatalog} and {@link CaptureStreamer} throw it from
 * wherever they are, and {@link ClientSession#dispatch} - the one type that may
 * write to the connection - turns it into the {@code resp} the client reads.
 * Anything else escaping a handler is a fault the protocol does not name and
 * becomes {@code INTERNAL}, which is exactly the distinction the two catch
 * clauses draw.
 */
public final class NetException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The spec 4.2 code the client is answered with. */
    @Getter
    private final ErrorCode code;

    /** WHY a device refused, when the fault was a device at all - the backend's
     *  own reading of its own error, sent beside the code so the far side can
     *  say it in ITS language.  {@code UNKNOWN} for every non-device fault,
     *  which is what the plain constructor leaves it as. */
    @Getter
    private final DeviceFailureReason reason;

    public NetException(ErrorCode code, String message) {
        this(code, message, DeviceFailureReason.UNKNOWN);
    }

    /** A device refusal that knows why. */
    public NetException(ErrorCode code, String message, DeviceFailureReason reason) {
        super(message);
        this.code = code;
        this.reason = (reason == null) ? DeviceFailureReason.UNKNOWN : reason;
    }
}
