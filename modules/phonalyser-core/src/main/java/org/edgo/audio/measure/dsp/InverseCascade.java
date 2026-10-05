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
 * The loaded calibration rows cascaded into the correction one frame is
 * divided by, in the two forms the rows come in.  The with-noise rows give a
 * reciprocal per bin over the whole spectrum - {@code null} arrays when no
 * such row is loaded, so the applying pass skips the spectrum walk.  The
 * tone-only rows give one {@link LobeCorrection} per tone slot of
 * {@link FreqRespCalHelper#toneFrequencies}, {@code null} where the frame
 * carries no such tone or no row spans it.
 *
 * @param invRe real part of the with-noise reciprocal per bin
 *              {@code 0 .. fftSize/2}, or {@code null}; {@code (0, 0)} marks a
 *              bin no with-noise row reached
 * @param invIm imaginary part of the same, or {@code null}
 * @param lobes the tone-only corrections by tone slot
 */
public record InverseCascade(double[] invRe, double[] invIm, LobeCorrection[] lobes) {

    /** Whether any with-noise row reached the spectrum. */
    public boolean hasNoiseRows() {
        return invRe != null;
    }
}
