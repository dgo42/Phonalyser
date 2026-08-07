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

package org.edgo.audio.measure.gui.sound;

import java.util.HashSet;
import java.util.Set;

import org.edgo.audio.measure.preferences.BackendKey;

/**
 * Which give-this-bench-device-a-card offers this RUN has already SETTLED -
 * the offer is made once, never silently, and a decline is remembered for the
 * session.
 *
 * <p>Session means the application's, not the dialog's: an operator who said no
 * must not be asked again the next time they open Preferences, which is exactly
 * when they would be looking at that device.  It is deliberately NOT persisted -
 * a decline is about this sitting, not about the installation, and a bench that
 * gets calibrated properly next week should be offered again.
 *
 * <p>Keyed per bench, DIRECTION and device.  A duplex device is listed under one
 * name in both directions and is two separate uncalibrated endpoints; without the
 * direction in the key, copying the input's calibration would silently spend the
 * one offer the output was owed and leave it uncalibrated for the session.
 */
public final class CalibrationCopyOffers {

    private final Set<String> settled = new HashSet<>();

    /**
     * Whether this bench + direction + device may still be put to the operator -
     * true until the question has been SETTLED.
     *
     * <p>Asking is not settling: a wire glitch, a bench that went away between the
     * confirm and the write, a device another client took a second earlier - none
     * of those is an answer, and burning the offer on one would cost the operator
     * the whole run (this memory is deliberately not persisted, so only a restart
     * would re-arm it).  The caller records the outcome with {@link #settle} once
     * it HAS one: a decline, or an attempt that reached the bench.
     */
    public boolean mayAsk(BackendKey bench, boolean input, String deviceName) {
        return !settled.contains(key(bench, input, deviceName));
    }

    /** Records that the question has been answered - the operator declined, or
     *  something actually landed on the bench.  Idempotent. */
    public void settle(BackendKey bench, boolean input, String deviceName) {
        settled.add(key(bench, input, deviceName));
    }

    private String key(BackendKey bench, boolean input, String deviceName) {
        return bench.key() + "|" + (input ? "in" : "out") + "|" + deviceName;
    }
}
