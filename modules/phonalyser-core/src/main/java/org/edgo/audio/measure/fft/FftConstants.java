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

package org.edgo.audio.measure.fft;

import lombok.experimental.UtilityClass;

/**
 * Numeric constants of the FFT analysis pipeline.
 */
@UtilityClass
public class FftConstants {

    /** The random overlap mode's draw range, in overlap percent: every hop is
     *  drawn uniformly between these two.  {@link Fft#minHopSamples} /
     *  {@link Fft#maxHopSamples} turn them into frame spacings - the analyser
     *  worker draws between those bounds, and the noise-skirt walk sizes for
     *  the densest one.  A wider overlap leaves less fresh signal per hop, so
     *  the MAXIMUM percent yields the SMALLEST hop. */
    public final double RAND_MIN_OVERLAP_PCT = 87.5;
    public final double RAND_MAX_OVERLAP_PCT = 93.75;
}
