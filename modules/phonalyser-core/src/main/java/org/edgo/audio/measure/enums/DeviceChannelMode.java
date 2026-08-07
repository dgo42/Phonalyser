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

package org.edgo.audio.measure.enums;

/** How a device endpoint's channels are calibrated.  {@code MONO} is a single
 *  physical channel: one full-scale per range and one calibration value, which
 *  the resolver pushes into BOTH left/right scalars so downstream consumers never
 *  care.  {@code LINKED} is a stereo endpoint whose two channels share one range
 *  selection but keep their own per-channel full-scale (both {@code fsLeft} and
 *  {@code fsRight} of the single active row).  {@code INDEPENDENT} lets each
 *  channel sit on its own range with its own full-scale (a dual-mono unit such as
 *  the E1DA Cosmos DM ADC, or the per-channel Cosmos DIP attenuator).  The
 *  constant name is the token stored in the device-profile YAML ({@link #valueOf}). */
public enum DeviceChannelMode {
    MONO,
    LINKED,
    INDEPENDENT;

    private DeviceChannelMode() {}
}
