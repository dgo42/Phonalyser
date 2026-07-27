/*
 * Phonalyser — precision audio measurement workbench.
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

package org.edgo.audio.measure.sound.qa40x;

/**
 * A snapshot of the analyzer's identity and live telemetry registers, already
 * decoded to display strings (doc §4 extended register map, §6 telemetry).
 * Read by {@link Qa40xDeviceManager} — which owns the transport — and shown
 * read-only by {@link Qa40xSettingsDialog}.
 *
 * <p>Every field is a string rather than a number because each register decodes
 * differently (millivolts, tenths of a degree, a packed hex serial) and because
 * a value that cannot be read has to render as {@link #UNAVAILABLE} rather than
 * as a misleading zero.
 */
public record Qa40xDeviceInfo(String firmwareVersion,
                              String usbVoltage,
                              String usbCurrent,
                              String isoCurrent,
                              String temperature,
                              String capability,
                              String capability2,
                              String serialNumber) {

    /** Shown for a register that could not be read, and for the QA403's
     *  ISO-supply current, which only the QA402 has (§6). */
    public static final String UNAVAILABLE = "---";

    /** The all-unavailable snapshot — nothing was read because the device is
     *  not open.  A shared constant so every caller shows the same thing. */
    public static final Qa40xDeviceInfo NONE = new Qa40xDeviceInfo(
            UNAVAILABLE, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE,
            UNAVAILABLE, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE);
}
