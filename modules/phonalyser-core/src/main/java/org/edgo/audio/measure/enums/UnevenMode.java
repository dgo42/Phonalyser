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

/** Unevenness readout mode in the Frequency Response pane.  {@code OFF}
 *  computes nothing and draws no readout; {@code LEVEL} takes a ±dB tolerance
 *  and reports the frequency span within it; {@code RANGE} takes a frequency
 *  range and reports the ±dB spread over it.  Declaration order IS the
 *  Unevenness radio-group order. */
public enum UnevenMode {
    OFF,
    LEVEL,
    RANGE;

    private UnevenMode() {}
}
