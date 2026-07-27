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

/**
 * Trigger event type: EDGE fires on a level crossing (the classic Schmitt
 * trigger); GLITCH fires on a dV/dt discontinuity — a per-sample jump far
 * beyond the signal's own bounded slew, e.g. a dropped-samples DAC gap.
 * The {@link TriggerEdge} slope applies to both: crossing direction for
 * EDGE, jump sign for GLITCH.
 */
public enum TriggerType {
    EDGE,
    GLITCH;

    private TriggerType() {}
}
