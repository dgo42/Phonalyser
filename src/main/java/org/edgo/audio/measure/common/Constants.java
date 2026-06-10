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

package org.edgo.audio.measure.common;

import lombok.experimental.UtilityClass;

/**
 * Cross-cutting numeric constants shared across measurement views.
 */
@UtilityClass
public class Constants {

    /** Floor of the canonical dBFS magnitude domain — well below any realistic ADC
     *  noise floor; zoom-in can still tighten well inside it.  The FFT range is stored
     *  and panned/zoomed/clamped in this dBFS domain; units convert only at draw time. */
    public final double MAG_FLOOR_DBFS = -300.0;
}
