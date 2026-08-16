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

package org.edgo.audio.measure.gui.widgets;

/**
 * One value AS ENTERED: the number the operator typed and the token of the unit
 * they typed it in ({@code v}, {@code dbv}, {@code dbfs}, {@code bits} - see
 * {@link UnitConversion} for where the tokens come from).
 *
 * <p>The two halves are ONE value because they only mean anything together:
 * {@code -100} is a dither of unknown depth until the unit says whether it is
 * bits or dBV, and the same figure in the other unit is a different physical
 * quantity.  Carrying them as a single immutable value is what makes a change
 * atomic - a store writes it in one move, so no observer can ever see the new
 * unit against the old number and drive the engine with a quantity nobody
 * entered.
 *
 * <p>Records compare by value, so an observable holding one still swallows a
 * write that changes nothing, and still reports a change when only the unit
 * moved.
 */
public record UnitValue(double value, String unit) {
}
