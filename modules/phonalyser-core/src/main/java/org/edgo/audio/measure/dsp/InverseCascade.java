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

    /**
     * The cascade that takes this one off again: every factor's complex
     * reciprocal, {@code conj(v)/|v|^2}, per bin and per lobe, the lobes and
     * their anchors kept.  Applied by {@link FreqRespCalHelper#applyInverseInPlace}
     * to a spectrum this cascade corrected, with the lobes measured on that
     * corrected spectrum, it gives the spectrum back: the stretch exponent of a
     * lobe bin is the same before and after the correction.  A bin marked
     * {@code (0, 0)} stays marked.
     */
    public InverseCascade reciprocal() {
        double[] re = null;
        double[] im = null;
        if (invRe != null) {
            re = new double[invRe.length];
            im = new double[invIm.length];
            for (int k = 0; k < invRe.length; k++) {
                double magSq = invRe[k] * invRe[k] + invIm[k] * invIm[k];
                if (!(magSq > 0.0)) continue;
                re[k] =  invRe[k] / magSq;
                im[k] = -invIm[k] / magSq;
            }
        }
        LobeCorrection[] back = new LobeCorrection[lobes.length];
        for (int t = 0; t < lobes.length; t++) {
            LobeCorrection lc = lobes[t];
            if (lc == null) continue;
            double magSq = lc.invRe() * lc.invRe() + lc.invIm() * lc.invIm();
            if (!(magSq > 0.0)) continue;
            back[t] = new LobeCorrection(lc.lobe(), lc.invRe() / magSq, -lc.invIm() / magSq);
        }
        return new InverseCascade(re, im, back);
    }
}
