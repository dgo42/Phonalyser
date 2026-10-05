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
 * What the tone-only calibration rows do to one tone's lobe: the reciprocal
 * of their product at the tone's own frequency, and the lobe it is applied
 * to.  The phase of the reciprocal turns every bin of the lobe in full; its
 * magnitude is applied stretched - in full at the peak, not at all at a bin
 * on the floor, in between by the bin's log-height above the floor - so the
 * lobe grows taller on its feet instead of lifting off the floor as a box.
 *
 * @param lobe  the lobe the correction is written across
 * @param invRe real part of {@code 1/G}, {@code G} the rows' product at the
 *              tone frequency
 * @param invIm imaginary part of {@code 1/G}
 */
public record LobeCorrection(ToneLobe lobe, double invRe, double invIm) {

    /** {@code |1/G|} - the full lift, the one the peak gets. */
    public double magnitude() {
        return Math.hypot(invRe, invIm);
    }
}
