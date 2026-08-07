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

package org.edgo.audio.measure.sound;

/**
 * What one volt means on one device and in one direction: the full-scale RMS
 * voltage of each channel, as the device card in force stores it.
 *
 * <p>RMS in BOTH directions, which is what the card holds - the generator's
 * peak-amplitude form is {@code fsRms × √2} and is derived where it is applied,
 * so the two channels of a capture and of a playback carry the same unit here
 * and a value cannot be mistaken for the other form.
 *
 * <p>It exists because calibration follows the device's HOST: a device plugged
 * into a Phonalyser server is calibrated there, and its values travel with the
 * device rather than being looked up again in the client's own card store - a
 * store that knows nothing about the bench across the room and could only match
 * it by a name collision.  {@link DeviceRef#calibration()} is how a device
 * carries it; {@code null} there means "no stored calibration", not "one volt".
 *
 * @param fsRmsLeft  the LEFT channel's full-scale, in volts RMS
 * @param fsRmsRight the RIGHT channel's full-scale, in volts RMS - equal to
 *                   {@code fsRmsLeft} for a mono card, where one physical
 *                   channel fills both
 */
public record DeviceCalibration(double fsRmsLeft, double fsRmsRight) {
}
