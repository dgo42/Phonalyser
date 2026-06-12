/*
 * Phonalyser — precision audio measurement workbench.
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

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@link FftAnalyzer#selectKth} against a full sort — the selection
 * feeds the SNR / THD+N noise-floor median and 10th percentile, so a subtle
 * partition bug would silently skew every published noise figure.
 */
class SelectKthTest {

    private static final int  RANDOM_ROUNDS = 200;
    private static final long SEED          = 0xF17EBEEFL;

    @Test
    void matchesFullSortOnRandomArrays() {
        FftAnalyzer analyzer = new FftAnalyzer();
        Random rnd = new Random(SEED);
        for (int round = 0; round < RANDOM_ROUNDS; round++) {
            int len = 1 + rnd.nextInt(2000);
            double[] data = new double[len];
            for (int i = 0; i < len; i++) {
                // Mix magnitudes across many decades like real bin powers.
                data[i] = Math.pow(10.0, -12 + 12 * rnd.nextDouble());
            }
            double[] sorted = Arrays.copyOf(data, len);
            Arrays.sort(sorted);
            int k = rnd.nextInt(len);
            assertEquals(sorted[k], analyzer.selectKth(Arrays.copyOf(data, len), len, k),
                    "k=" + k + " len=" + len + " round=" + round);
        }
    }

    @Test
    void handlesDegenerateShapes() {
        FftAnalyzer analyzer = new FftAnalyzer();
        // Single element.
        assertEquals(7.0, analyzer.selectKth(new double[]{ 7.0 }, 1, 0));
        // All equal — every rank must return the constant.
        double[] flat = new double[64];
        Arrays.fill(flat, 3.25);
        assertEquals(3.25, analyzer.selectKth(flat.clone(), flat.length, 0));
        assertEquals(3.25, analyzer.selectKth(flat.clone(), flat.length, 31));
        assertEquals(3.25, analyzer.selectKth(flat.clone(), flat.length, 63));
        // Already sorted and reverse-sorted, extreme ranks.
        double[] asc = new double[101];
        for (int i = 0; i < asc.length; i++) asc[i] = i;
        double[] desc = new double[101];
        for (int i = 0; i < desc.length; i++) desc[i] = asc.length - 1 - i;
        assertEquals(0.0,   analyzer.selectKth(asc.clone(),  asc.length,  0));
        assertEquals(100.0, analyzer.selectKth(asc.clone(),  asc.length,  100));
        assertEquals(50.0,  analyzer.selectKth(desc.clone(), desc.length, 50));
        // Length shorter than the backing array (scratch-pool usage).
        double[] padded = { 5.0, 1.0, 3.0, 999.0, 999.0 };
        assertEquals(3.0, analyzer.selectKth(padded, 3, 1));
    }
}
