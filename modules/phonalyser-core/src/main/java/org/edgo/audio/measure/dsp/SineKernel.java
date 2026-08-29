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

import org.edgo.audio.measure.common.Constants;

import lombok.experimental.UtilityClass;

/**
 * Shared table-plus-Taylor sine/cosine kernel - the DDS generator's exact
 * evaluation scheme, factored out so every precision-critical sinusoid in the
 * code base (the generator's waveforms, the oscilloscope residual's fitted
 * reference) is computed by the SAME math.
 *
 * <p>A full-wave 4096-entry lookup supplies the coarse angle θ₀; the
 * remaining fractional phase Δθ (≤ 2π/4096 ≈ 1.5e-3 rad) is corrected by a
 * short Taylor series through the angle-addition identities:
 * <pre>sin(θ₀+Δθ) = sin θ₀·cos Δθ + cos θ₀·sin Δθ</pre>
 * At this table size the 5th order already truncates at ~2e-20 of full scale
 * (≈ −390 dBc), far below double precision; the 7th and 9th orders are
 * headroom (and room to shrink the table).
 *
 * <p>ORDER SWITCH - uncomment exactly ONE order in EACH helper, keeping the
 * two helpers at the same order.
 */
@UtilityClass
public final class SineKernel {

    /** Table index width in bits; the table holds one full wave. */
    public static final int TABLE_BITS = 12;
    private static final int TABLE_SIZE = 1 << TABLE_BITS;   // 4096
    /** Radians per table step - the maximum magnitude Δθ the Taylor helpers
     *  ever see when driven from the table split. */
    private static final double STEP_RAD = 2.0 * Math.PI / TABLE_SIZE;

    private static final double[] SINE_TABLE = new double[TABLE_SIZE];
    private static final double[] COS_TABLE  = new double[TABLE_SIZE];

    static {
        for (int i = 0; i < TABLE_SIZE; i++) {
            double angle = 2.0 * Math.PI * i / TABLE_SIZE;
            SINE_TABLE[i] = Math.sin(angle);
            COS_TABLE[i]  = Math.cos(angle);
        }
    }

    /** cos(Δθ) Taylor series; {@code dx2 = Δθ²}.  Valid for the small
     *  fractional angles of the table split (|Δθ| ≤ {@link #STEP_RAD}). */
    public static double taylorCos(double dx2) {
        // 5th order - cos to Δθ⁴:
        return 1.0 + dx2 * (-0.5 + dx2 * (1.0 / 24.0));
        // 7th order - cos to Δθ⁶:
        // return 1.0 + dx2 * (-0.5 + dx2 * (1.0 / 24.0 + dx2 * (-1.0 / 720.0)));
        // 9th order - cos to Δθ⁸:
        // return 1.0 + dx2 * (-0.5 + dx2 * (1.0 / 24.0 + dx2 * (-1.0 / 720.0 + dx2 * (1.0 / 40320.0))));
    }

    /** sin(Δθ) Taylor series; {@code dx = Δθ}, {@code dx2 = Δθ²}.  Valid for
     *  the small fractional angles of the table split. */
    public static double taylorSin(double dx, double dx2) {
        // 5th order - sin to Δθ⁵:
        return dx * (1.0 + dx2 * (-1.0 / 6.0 + dx2 * (1.0 / 120.0)));
        // 7th order - sin to Δθ⁷:
        // return dx * (1.0 + dx2 * (-1.0 / 6.0 + dx2 * (1.0 / 120.0 + dx2 * (-1.0 / 5040.0))));
        // 9th order - sin to Δθ⁹:
        // return dx * (1.0 + dx2 * (-1.0 / 6.0 + dx2 * (1.0 / 120.0 + dx2 * (-1.0 / 5040.0 + dx2 * (1.0 / 362880.0)))));
    }

    /**
     * Sine of a 64-bit DDS phase accumulator (one full turn = 2^64) - the
     * generator's hot path: top {@link #TABLE_BITS} bits select the table
     * entry, the rest carry Δθ.
     */
    public static double sinAtPhaseAcc(long phaseAcc) {
        int    idx  = (int) (phaseAcc >>> (64 - TABLE_BITS));
        long   frac = phaseAcc & ((1L << (64 - TABLE_BITS)) - 1);
        double dx   = frac * Constants.TWO_PI_OVER_2_64;         // fractional radians
        double dx2  = dx * dx;
        return SINE_TABLE[idx] * taylorCos(dx2) + COS_TABLE[idx] * taylorSin(dx, dx2);
    }

    /** Cosine sibling of {@link #sinAtPhaseAcc}:
     *  cos(θ₀+Δθ) = cos θ₀·cos Δθ − sin θ₀·sin Δθ, same error budget. */
    public static double cosAtPhaseAcc(long phaseAcc) {
        int    idx  = (int) (phaseAcc >>> (64 - TABLE_BITS));
        long   frac = phaseAcc & ((1L << (64 - TABLE_BITS)) - 1);
        double dx   = frac * Constants.TWO_PI_OVER_2_64;
        double dx2  = dx * dx;
        return COS_TABLE[idx] * taylorCos(dx2) - SINE_TABLE[idx] * taylorSin(dx, dx2);
    }

    /**
     * Sine of an angle given in TURNS (1.0 = one full cycle; any real value,
     * negatives included).  For a sinusoid synthesized per sample as
     * {@code sinTurns(k * f/fs)} the phase comes from ONE multiply, so there
     * is no recurrence drift over arbitrarily long spans.
     */
    public static double sinTurns(double turns) {
        double t      = turns - Math.floor(turns);               // [0, 1)
        double scaled = t * TABLE_SIZE;
        int    idx    = (int) scaled;
        double dx     = (scaled - idx) * STEP_RAD;
        double dx2    = dx * dx;
        return SINE_TABLE[idx] * taylorCos(dx2) + COS_TABLE[idx] * taylorSin(dx, dx2);
    }

    /** Cosine sibling of {@link #sinTurns}: cos(θ₀+Δθ) = cos θ₀·cos Δθ − sin θ₀·sin Δθ. */
    public static double cosTurns(double turns) {
        double t      = turns - Math.floor(turns);
        double scaled = t * TABLE_SIZE;
        int    idx    = (int) scaled;
        double dx     = (scaled - idx) * STEP_RAD;
        double dx2    = dx * dx;
        return COS_TABLE[idx] * taylorCos(dx2) - SINE_TABLE[idx] * taylorSin(dx, dx2);
    }
}
