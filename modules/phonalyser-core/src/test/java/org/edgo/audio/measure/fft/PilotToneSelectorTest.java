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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.edgo.audio.measure.enums.WindowType;
import org.junit.jupiter.api.Test;

/**
 * The pilot election on one analysis geometry: a transform of 2^21 samples at
 * 384 kHz under the BH7 window, nine protected harmonic orders, pilots up to
 * 20 kHz.
 */
class PilotToneSelectorTest {

    private static final int    FFT_SIZE        = 1 << 21;
    private static final double SAMPLE_RATE_HZ  = 384000.0;
    private static final int    PROTECTED_ORDER = 9;
    private static final double CEILING_HZ      = 20000.0;
    /** Tone bins around 1004 Hz.  At bin 5486 the best single line is bin
     *  107069, and its companion one fundamental below, bin 101583, stands
     *  0.26 Hz from 18600 Hz and falls to rule 4 - while the neighbouring
     *  candidates' companions are admissible. */
    private static final int[]  TONE_BINS       = { 5477, 5484, 5486 };

    @Test
    void aPairIsElectedWhereverOneExists() {
        for (int tone : TONE_BINS) {
            PilotToneSelector.PilotPair pair = new PilotToneSelector(
                    SAMPLE_RATE_HZ, SAMPLE_RATE_HZ, FFT_SIZE, new int[] { tone }, PROTECTED_ORDER,
                    WindowType.BH7, CEILING_HZ).selectPair();
            assertNull(pair.reason(), "both pilots are elected, tone bin " + tone);
            assertEquals(tone, pair.bin1() - pair.bin2(),
                    "the pilots stand one fundamental apart, tone bin " + tone);
        }
    }
}
