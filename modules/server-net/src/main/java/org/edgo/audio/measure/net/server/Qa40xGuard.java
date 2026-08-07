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

import java.util.LinkedHashMap;
import java.util.Map;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.net.proto.ErrorCode;
import org.edgo.audio.measure.sound.AudioBackend;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * The analyzer the whole server shares: its ONE clock, and the safe state it has
 * to be left in.  Both are properties of the hardware, not of a connection, so
 * they live here - one instance per server, beside the {@link LockRegistry},
 * built by {@link ServerMain} and handed to every connection.
 *
 * <p><b>One clock.</b>  Spec 4.4: "QA40x equal-rates rule is enforced
 * server-side: {@code capture.open}/{@code gen.open} on QA40x with mismatched
 * rates -> {@code BAD_REQUEST}".  The analyzer derives input and output from a
 * single register-9 divider, and its manager re-clocks the running duplex
 * session whenever a lane opens at another rate - so a second stream at another
 * rate does not fail, it silently makes every measurement already running wrong.
 * The rule therefore has to see EVERY open stream, not one connection's: a
 * server with two units attached hands them out as two separate locks, so client
 * A and client B can each hold one and each ask for its own rate.
 *
 * <p><b>One safe state.</b>  Spec 4.1's teardown ends with "park hardware (QA40x
 * attenuator safe)": the manager's {@code shutdown()} writes maximum input
 * attenuation and the low output range and releases the transport, which is what
 * keeps a session that measured at 0&nbsp;dBV from leaving the input at full
 * sensitivity after the client vanished.  That write stops the shared session,
 * so it may only happen when NOBODY is holding the analyzer any more - hence
 * {@link #parkIfIdle()} asks the registry rather than parking on every teardown.
 *
 * <p>Every method is safe from any thread: the rate map is guarded by this
 * object (check-and-record must be one step, or two clients racing an open would
 * both pass), and the park is idempotent in the manager itself.
 */
@Log4j2
@RequiredArgsConstructor
public final class Qa40xGuard {

    private final AudioBackend audio;
    private final LockRegistry locks;

    /** The rate each open QA40x stream runs at, across ALL connections.  Keyed
     *  by the device because a lock is held by one connection at a time and one
     *  capture is open per device, which makes it unique server-wide; insertion
     *  ordered so the refusal always names the same stream. */
    private final Map<DeviceLock, Integer> rates = new LinkedHashMap<>();

    /**
     * Records the rate {@code device} is about to open at, or refuses the open
     * because another QA40x stream already runs at a different one.  A no-op for
     * every other backend - they have a clock per device.
     *
     * @throws NetException {@code BAD_REQUEST}, the code spec 4.4 names for
     *         mismatched rates
     */
    public synchronized void claimRate(DeviceLock device, int rateHz) {
        if (device.backend() != AudioBackendType.QA40X) {
            return;
        }
        for (Map.Entry<DeviceLock, Integer> open : rates.entrySet()) {
            if (open.getValue() != rateHz) {
                throw new NetException(ErrorCode.BAD_REQUEST,
                        "the QA40x has one clock for both directions: " + device
                                + " cannot open at " + rateHz + " Hz while "
                                + open.getKey() + " streams at " + open.getValue() + " Hz");
            }
        }
        rates.put(device, rateHz);
    }

    /** Forgets a stream that closed - the rate it held no longer constrains the
     *  next open.  Silent when the device never streamed: every close path calls
     *  this blindly. */
    public synchronized void releaseRate(DeviceLock device) {
        rates.remove(device);
    }

    /**
     * The park of spec 4.1, when the analyzer is nobody's: with no QA40x lock
     * left in the registry there is no measurement to interrupt, so the manager
     * writes its safe state and releases the transport.  While another
     * connection still holds either direction this does nothing - parking a
     * device someone is measuring with would be the very fault it prevents.
     *
     * <p>Call it from a thread that may block on a device: the safe state is a
     * USB register write on hardware that may be wedged or already unplugged.
     */
    public void parkIfIdle() {
        AudioBackendType type = AudioBackendType.QA40X;
        if (!audio.isAvailable(type) || locks.anyHeld(type)) {
            return;
        }
        audio.qa40xManager().shutdown();
        if (log.isInfoEnabled()) {
            log.info("net server: QA40x parked - no connection holds it any more");
        }
    }

    // The server's own start-up / shutdown park does NOT live here.  It is not a
    // property of the analyzer: every backend gets the same lifecycle call, on
    // the same rule (served, runnable on this host, held by nobody), and
    // ServerMain - which owns the served list and the registry - is what runs it.
    // See AudioDeviceManager#setup().
}
