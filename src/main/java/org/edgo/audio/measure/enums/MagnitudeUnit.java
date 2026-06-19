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

package org.edgo.audio.measure.enums;

import lombok.Getter;

/**
 * Magnitude unit selector for a frequency-domain view's magnitude axis.
 * Carries only the unit identity and whether it plots on a log magnitude
 * axis — display labels live in the GUI (i18n) and the dBFS→unit conversion
 * in {@code Preferences#convertFromDbFs}.
 */
public enum MagnitudeUnit {
    V        (true),
    V_SQRT_HZ(true),
    DBV      (false),
    DBFS     (false);

    /** Linear-amplitude units (V, V/√Hz) plot on a log magnitude axis; the dB units
     *  are already log-domain, so for them the axis stays linear in dB. */
    @Getter
    private final boolean log;

    private MagnitudeUnit(boolean log) { this.log = log; }
}
