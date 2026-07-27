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

/** Overlap fraction between successive FFT frames.  Higher overlap
 *  speeds up the analyser update rate at the cost of CPU. */
public enum FftOverlap {
    PCT_0(0.0,    "0%"),
    PCT_50(0.5,   "50%"),
    PCT_75(0.75,  "75%"),
    PCT_87_5(0.875,  "87.5%"),
    PCT_93_75(0.9375, "93.75%");

    public final double fraction;
    public final String label;

    private FftOverlap(double fraction, String label) {
        this.fraction = fraction;
        this.label    = label;
    }

    public static FftOverlap fromString(String s) {
        String norm = s.trim().replace("%", "").replace(",", ".");
        switch (norm) {
            case "0":
                return PCT_0;
            case "50":
                return PCT_50;
            case "75":
                return PCT_75;
            case "87.5":
            case "87":
                return PCT_87_5;
            case "93.75":
            case "93":
                return PCT_93_75;
            default:
                throw new IllegalArgumentException(
                        "Unknown overlap: " + s + " — use 0, 50, 75, 87.5, or 93.75");
        }
    }
}
