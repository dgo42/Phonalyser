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

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@link FftDsp#selectKth} against a full sort - the selection feeds
 * the SNR / THD+N noise-floor median and the IMD readout's noise floor, so a
 * subtle partition bug would silently skew every published noise figure.
 */
class SelectKthTest {

    private static final int  RANDOM_ROUNDS = 200;
    private static final long SEED          = 0xF17EBEEFL;
    /** The longest random array, entries. */
    private static final int  MAX_LENGTH    = 2000;
    /** The decades the random entries span, like real bin powers. */
    private static final int  DECADES       = 12;

    /** The selection matches the full sort at a random rank of random
     *  arrays spanning many decades. */
    @Test
    void matchesFullSortOnRandomArrays() {
        Random random = new Random(SEED);
        for (int round = 0; round < RANDOM_ROUNDS; round++) {
            int length = 1 + random.nextInt(MAX_LENGTH);
            double[] values = new double[length];
            for (int index = 0; index < length; index++) {
                values[index] = Math.pow(10.0, -DECADES + DECADES * random.nextDouble());
            }
            double[] sorted = Arrays.copyOf(values, length);
            Arrays.sort(sorted);
            int rank = random.nextInt(length);
            assertEquals(sorted[rank], FftDsp.selectKth(Arrays.copyOf(values, length), length, rank),
                    "rank=" + rank + " length=" + length + " round=" + round);
        }
    }

    /** One entry, all equal, sorted and reverse-sorted arrays, and a length
     *  shorter than the backing array. */
    @Test
    void handlesDegenerateShapes() {
        // Single entry.
        assertEquals(7.0, FftDsp.selectKth(new double[]{ 7.0 }, 1, 0));
        // All equal - every rank must return the constant.
        double[] flat = new double[64];
        Arrays.fill(flat, 3.25);
        assertEquals(3.25, FftDsp.selectKth(flat.clone(), flat.length, 0));
        assertEquals(3.25, FftDsp.selectKth(flat.clone(), flat.length, 31));
        assertEquals(3.25, FftDsp.selectKth(flat.clone(), flat.length, 63));
        // Already sorted and reverse-sorted, extreme ranks.
        double[] ascending = new double[101];
        for (int index = 0; index < ascending.length; index++) ascending[index] = index;
        double[] descending = new double[101];
        for (int index = 0; index < descending.length; index++) {
            descending[index] = ascending.length - 1 - index;
        }
        assertEquals(0.0,   FftDsp.selectKth(ascending.clone(),  ascending.length,  0));
        assertEquals(100.0, FftDsp.selectKth(ascending.clone(),  ascending.length,  100));
        assertEquals(50.0,  FftDsp.selectKth(descending.clone(), descending.length, 50));
        // Length shorter than the backing array (a reused pool).
        double[] padded = { 5.0, 1.0, 3.0, 999.0, 999.0 };
        assertEquals(3.0, FftDsp.selectKth(padded, 3, 1));
    }
}
