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

package org.edgo.audio.measure.sound.qa40x;

import org.edgo.audio.measure.sound.AudioDeviceManager;

import java.util.List;

/**
 * Everything about a QA402/QA403 that is NOT the audio stream: its identity and
 * telemetry, its two full-scale range selectors, the calibration page it carries
 * and its front-panel I2S port.
 *
 * <p><b>Why it is an interface, and why it lives with the driver.</b>  Three
 * callers need that surface: this backend's own settings panel, the net server,
 * which serves the same surface over the wire (net protocol 4.6), and a test
 * that has no analyzer to talk to.  All three compile against this module - the
 * server carries every local backend it can serve - so the contract belongs
 * beside the driver that implements it.  The core module stays free of types
 * named after one vendor's box.
 *
 * <p>It is deliberately NOT part of {@link AudioDeviceManager}: a sound card has
 * no attenuator, no calibration page and no expansion port, so a device-manager
 * contract carrying them would make every other backend implement eight methods
 * it has to refuse.  A caller asks {@code instanceof} instead, exactly as it does
 * for any other optional capability.
 *
 * <p>Ranges are in dBV, the unit the analyzer's own registers and the operator's
 * range table both use.  A live change restarts the shared duplex session (QA40x
 * doc §10) - that is the implementation's business, not the caller's.
 */
public interface Qa40xControl {

    /** One row of the device's calibration page: a full-scale range and the two
     *  channels' LINEAR correction factors at it, which is what a client's
     *  {@code calibrationFromDevice} card pipeline consumes unchanged. */
    record CalibrationRow(int dbv, double left, double right) {
    }

    /** The identity and telemetry snapshot, opening the device if no session has
     *  done so yet.  Never throws: it feeds a read-only panel, so an absent or
     *  wedged analyzer answers {@link Qa40xDeviceInfo#NONE} rather than breaking
     *  the dialog it is on. */
    Qa40xDeviceInfo readDeviceInfo();

    /** The selectable input full-scale values in dBV, most sensitive first. */
    int[] inputRangesDbv();

    /** The selectable output full-scale values in dBV. */
    int[] outputRangesDbv();

    /** The input range in force - the one a new session would open at. */
    int activeInputRangeDbv();

    /** The output range in force. */
    int activeOutputRangeDbv();

    /** Selects the input full-scale range, restarting a running session.
     *
     *  @throws IllegalArgumentException when {@code dbv} is not one of
     *          {@link #inputRangesDbv()} */
    void setInputRange(int dbv);

    /** Selects the output full-scale range, restarting a running session.
     *
     *  @throws IllegalArgumentException when {@code dbv} is not one of
     *          {@link #outputRangesDbv()} */
    void setOutputRange(int dbv);

    /**
     * The device's calibration page for one direction, one row per range.
     *
     * @param input true for the ADC rows, false for the DAC rows
     * @throws IllegalStateException when no analyzer is attached - the page is
     *         read from the device, so there is nothing to answer with
     */
    List<CalibrationRow> calibration(boolean input);

    /** Whether the front-panel I2S expansion port is on, which moves the
     *  supported output sample widths. */
    boolean isI2sEnabled();

    /** Switches the front-panel I2S expansion port.  It takes effect at the next
     *  session boundary, exactly as the desktop path applies it. */
    void setI2sEnabled(boolean enabled);
}
