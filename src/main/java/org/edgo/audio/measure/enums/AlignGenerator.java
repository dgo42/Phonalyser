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
 * How the FFT view steers the DDS generator onto the FFT bin grid:
 * <ul>
 *   <li>{@link #NONE} — no alignment (the generator runs free),</li>
 *   <li>{@link #FLL}  — a frequency-lock loop that corrects then waits for the
 *       measurement to settle within tolerance of the target before correcting
 *       again.</li>
 * </ul>
 */
public enum AlignGenerator {
    NONE("None"),
    FLL("FLL");

    public final String label;

    private AlignGenerator(String label) {
        this.label = label;
    }

    public static AlignGenerator fromString(String s) {
        if (s == null) {
            return NONE;
        }
        try {
            return AlignGenerator.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }
}
