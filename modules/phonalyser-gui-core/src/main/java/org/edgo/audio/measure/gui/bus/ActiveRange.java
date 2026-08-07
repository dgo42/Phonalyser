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

package org.edgo.audio.measure.gui.bus;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Payload of {@link Events#DEVICE_ACTIVE_RANGE_CHANGED}: one direction of a
 * device-provided card whose active full-scale range changed when the Preferences
 * dialog was closed with OK.  Deliberately device-agnostic - it names neither a
 * card nor a backend; a subscriber that owns a live range identifies the affected
 * device from its own current state (selected device / open session) rather than
 * from this payload.
 *
 * @param input            {@code true} for the input (capture) direction, {@code false} for output
 * @param activeRangeLabel the newly active range-row label as shown on the card
 */
@Data
@AllArgsConstructor
public class ActiveRange {
    private boolean input;
    private String activeRangeLabel;
}
