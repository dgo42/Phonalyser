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

package org.edgo.audio.measure.enums;

/** Overlap fraction between successive FFT frames.  Higher overlap
 *  speeds up the analyser update rate at the cost of CPU. */
public enum FftOverlap {
    PCT_0(0.0,    "0%"),
    PCT_50(0.5,   "50%"),
    PCT_75(0.75,  "75%"),
    PCT_87_5(0.875,  "87.5%"),
    PCT_93_75(0.9375, "93.75%"),
    /** Random per-hop overlap: every hop is drawn uniformly from the range
     *  {@link #RAND_MIN_OVERLAP_PCT} .. {@link #RAND_MAX_OVERLAP_PCT}, which
     *  this mode owns as part of its identity rather than reading from any
     *  setting.  Carries no single fraction, so every consumer resolves the
     *  effective spacing through the drawn hop, or through
     *  {@link #minHopSamples}/{@link #maxHopSamples} where one bounding
     *  number is needed. */
    RAND(Double.NaN, "Random");

    /** The random mode's draw range, in overlap percent.  A wider overlap
     *  leaves less fresh signal per hop, so the MAXIMUM percent yields the
     *  SMALLEST hop.  The label deliberately carries no numbers - these can
     *  change. */
    public static final double RAND_MIN_OVERLAP_PCT = 80.0;
    public static final double RAND_MAX_OVERLAP_PCT = 93.75;

    public final double fraction;
    public final String label;

    private FftOverlap(double fraction, String label) {
        this.fraction = fraction;
        this.label    = label;
    }

    /** True for the random mode - the one constant whose {@link #fraction} is
     *  not a usable number. */
    public boolean isRandom() {
        return this == RAND;
    }

    /** Frame spacing in samples at {@code fftSize} for a fixed overlap.  The
     *  random mode has no single spacing - each hop is drawn per tick from
     *  its own range - so asking it for one is a caller error. */
    public int hopSamples(int fftSize) {
        if (isRandom()) {
            throw new IllegalStateException(
                    "Random overlap carries no fixed frame spacing - the hop is drawn per tick");
        }
        return Math.max(1, (int) Math.round(fftSize * (1.0 - fraction)));
    }

    /** Smallest spacing this mode can produce at {@code fftSize}: the fixed
     *  hop, or the random range's densest spacing (its MAXIMUM overlap
     *  percent).  The noise-skirt walk sizes its look-ahead from this - the
     *  averaged floor's texture is assumed as coarse as the densest spacing
     *  can make it, because random hopping is not credited with
     *  decorrelating it. */
    public int minHopSamples(int fftSize) {
        return isRandom()
                ? Math.max(1, (int) Math.round(fftSize * (1.0 - RAND_MAX_OVERLAP_PCT / 100.0)))
                : hopSamples(fftSize);
    }

    /** Largest spacing this mode can produce at {@code fftSize}: the fixed
     *  hop, or the random range's sparsest spacing (its MINIMUM overlap
     *  percent).  Sizes every buffer that must hold a hop of any drawn
     *  size without reallocating mid-gather. */
    public int maxHopSamples(int fftSize) {
        return isRandom()
                ? Math.max(1, (int) Math.round(fftSize * (1.0 - RAND_MIN_OVERLAP_PCT / 100.0)))
                : hopSamples(fftSize);
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
                        "Unknown overlap: " + s + " - use 0, 50, 75, 87.5, or 93.75");
        }
    }
}
