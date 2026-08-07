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

package org.edgo.audio.measure.gui.freqresp;

/**
 * Bundle of both channels' Frequency Response results from a single
 * stereo capture + deconvolution pass.  Always carries both - neither
 * field is {@code null} for production runs.
 *
 * <p>{@code rawPeakLin} is the largest absolute sample seen in that one raw
 * capture, over both channels, on the normalised {@code [-1, +1]} scale - the
 * evidence for {@link #clipped()}.  It is measured before any deconvolution,
 * so it reports what the ADC actually delivered rather than what the transfer
 * function looks like afterwards.
 */
public record StereoFreqRespResult(FreqRespResult left, FreqRespResult right,
                                   double rawPeakLin) {

    /** Peak level at or above which the capture is treated as clipped.
     *  0.9995 rather than 1.0 because a converter's positive rail never
     *  reaches exactly 1: 16-bit full scale normalises to 0.99997, and a
     *  hard-limited sweep is typically shaved a hair below the rail by the
     *  anti-alias filter.  −0.0043 dBFS is clipping territory in any case:
     *  no deliberate measurement level sits there. */
    private static final double CLIP_THRESHOLD_LIN = 0.9995;

    /** True when the raw sweep capture touched the converter's rail, so the
     *  deconvolved response is distorted no matter how clean it looks. */
    public boolean clipped() {
        return rawPeakLin >= CLIP_THRESHOLD_LIN;
    }
}
