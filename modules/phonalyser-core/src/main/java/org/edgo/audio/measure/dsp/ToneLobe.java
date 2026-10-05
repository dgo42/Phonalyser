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

package org.edgo.audio.measure.dsp;

/**
 * The main lobe of one tone as {@link ToneLobeLift} finds it on a frame's raw
 * bin magnitudes: its peak bin, the inclusive bin span the lobe fills down to
 * the local noise floor, and the two levels the lobe's stretch is anchored on
 * - the floor ceiling the feet sit on and the peak's own magnitude.  The
 * geometry (the three bins) is what decides whether a held correction still
 * fits a later frame; the two levels shape the correction inside the span.
 *
 * @param peak     the tone's peak bin
 * @param lo       first bin of the lobe, inclusive
 * @param hi       last bin of the lobe, inclusive
 * @param floorMag the local noise-floor ceiling in raw bin magnitude, the
 *                 level a lobe bin must stand above to be lifted at all
 * @param peakMag  the peak bin's raw magnitude
 */
public record ToneLobe(int peak, int lo, int hi, double floorMag, double peakMag) {

    /** Whether {@code other} covers the same bins - the fit test between a
     *  held correction and a later frame; the levels do not enter it. */
    public boolean sameBins(ToneLobe other) {
        return other != null && peak == other.peak && lo == other.lo && hi == other.hi;
    }
}
