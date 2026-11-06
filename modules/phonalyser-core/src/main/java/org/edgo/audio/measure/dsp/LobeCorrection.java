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

import org.edgo.audio.measure.fft.SpectralLine;

/**
 * What the tone-only calibration rows do to one tone: the reciprocal of their
 * product at the tone's own frequency.  Applied to the tone's lobe as the
 * spectrum measured it (its {@link SpectralLine}) by
 * {@link FreqRespCalHelper#liftLobe}:
 * the phase turns every bin of the lobe in full, the magnitude is applied
 * stretched - in full at the peak, not at all at a bin on the floor, in
 * between by the bin's log-height above the floor.
 *
 * @param invRe real part of {@code 1/G}, {@code G} the rows' product at the
 *              tone frequency
 * @param invIm imaginary part of {@code 1/G}
 */
public record LobeCorrection(double invRe, double invIm) {

    /** {@code |1/G|} - the full lift, the one the peak gets.
     *
     * @return the magnitude of the reciprocal */
    public double magnitude() {
        return Math.hypot(invRe, invIm);
    }
}
