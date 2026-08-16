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

import lombok.experimental.UtilityClass;

/**
 * TPDF dither depth (bits) to absolute level (dBV) and back.
 *
 * <p>The dither is added to the peak-normalised sample (±1 ≡ the DAC's PEAK
 * full-scale amplitude), and its RMS at {@code bits} bits is
 * {@code 2^−(bits−1)/√6} of that peak.  The dBV reference is therefore the
 * peak full-scale voltage itself - NOT the RMS full-scale ({@code /√2}),
 * which would read about 3 dB low.
 */
@UtilityClass
public class DitherMath {

    /** dB per factor-of-10 amplitude - the dBV exponent scale. */
    private final double DB_PER_DECADE = 20.0;

    /** One TPDF bit is 6.0206 dB: each extra bit of depth halves the dither
     *  amplitude.  Public because a dBV-sized step or delta converts to bits
     *  by dividing by this. */
    public final double DB_PER_BIT = 6.0206;

    /** 20·log10(√6) - how far the TPDF RMS ({@code 2^−(bits−1)/√6}) sits
     *  below its one-bit peak amplitude. */
    private final double TPDF_OFFSET_DB = 7.782;

    /** dBV of the DAC PEAK full-scale amplitude {@code fsAmpl} - the
     *  reference every dither level is stated against (see the class
     *  comment). */
    public double fsDbv(double fsAmpl) {
        return DB_PER_DECADE * Math.log10(fsAmpl);
    }

    /** dBV of the TPDF dither at {@code bits} bits (bits ≥ 1) against the
     *  peak full-scale amplitude {@code fsAmpl}. */
    public double dbvForBits(double bits, double fsAmpl) {
        return -(bits - 1) * DB_PER_BIT - TPDF_OFFSET_DB + fsDbv(fsAmpl);
    }

    /** The (fractional) bit count whose TPDF dither lands at {@code dbv}
     *  against {@code fsAmpl} - the exact inverse of {@link #dbvForBits},
     *  un-clamped. */
    public double bitsForDbv(double dbv, double fsAmpl) {
        return 1 + (fsDbv(fsAmpl) - TPDF_OFFSET_DB - dbv) / DB_PER_BIT;
    }
}
