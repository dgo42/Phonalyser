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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.edgo.audio.measure.enums.WindowType;
import org.junit.jupiter.api.Test;

/**
 * The intermodulation readout travels in the result: {@link FftAnalyzer}
 * fills {@link FftResult#imd} wherever it derives the statistics - for a
 * spectrum that carries a second tone - and clears it for one that does not.
 * A stored spectrum finds its second tone itself and gets the same readout
 * through {@link FftAnalyzer#applyDistortionSettings}.
 */
class FftAnalyzerImdTest {

    private static final int    FFT_SIZE  = 8192;
    private static final double BIN_BW_HZ = 10.0;
    private static final double FLOOR_DBFS = -140.0;
    private static final double TONE1_DBFS = -6.0;
    /** Weaker than the first tone so the stored fundamental search settles on
     *  the first one. */
    private static final double TONE2_DBFS = -12.0;
    private static final double F1_HZ = 19_000.0;
    private static final double F2_HZ = 20_000.0;
    /** Hann's normalized equivalent noise bandwidth, bins. */
    private static final double HANN_NENBW_BINS = 1.5;
    /** Lower edge of the stored fundamental search, Hz. */
    private static final double FUND_MIN_HZ = 20.0;
    /** How far a frequency read off the synthetic spectrum may sit from the
     *  tone it was placed at, Hz - a tenth of a bin. */
    private static final double FREQ_TOLERANCE_HZ = 1.0;

    /** Synthetic single-sided spectrum, bins 0 ... FFT_SIZE / 2: a flat floor
     *  with a line at each given frequency (a NaN frequency places none). */
    private FftResult spectrum(double f1Hz, double f2Hz) {
        FftResult r = new FftResult();
        r.fftSize        = FFT_SIZE;
        r.freqResolution = BIN_BW_HZ;
        r.sampleRate     = (int) Math.round(BIN_BW_HZ * FFT_SIZE);
        r.windowType     = WindowType.HANN;
        r.windowNenbwBins = HANN_NENBW_BINS;
        r.ensureArrays(FFT_SIZE / 2 + 1, 0);
        Arrays.fill(r.amplitudeDbFs, FLOOR_DBFS);
        r.amplitudeDbFs[(int) Math.round(f1Hz / BIN_BW_HZ)] = TONE1_DBFS;
        if (!Double.isNaN(f2Hz)) {
            r.amplitudeDbFs[(int) Math.round(f2Hz / BIN_BW_HZ)] = TONE2_DBFS;
        }
        r.fundamentalBin       = (int) Math.round(f1Hz / BIN_BW_HZ);
        r.fundamentalHz        = f1Hz;
        r.fundamentalHzRefined = f1Hz;
        r.fundamentalTrueDbFs  = Double.NaN;   // no manual fundamental
        return r;
    }

    /**
     * recomputeStats fills {@code imd} for a two-tone spectrum - with F1 the
     * LOWER tone even when the analyser's fundamental is the upper one - and
     * sets it to null for a single tone, also on a result that carried a
     * dual-tone readout before.
     */
    @Test
    void recomputeStatsFillsImdForTwoTonesAndClearsItForOne() {
        FftAnalyzer analyzer = new FftAnalyzer();

        FftResult dual = spectrum(F1_HZ, F2_HZ);
        dual.fundamental2HzRefined = F2_HZ;
        analyzer.recomputeStats(dual);
        assertNotNull(dual.imd, "a two-tone spectrum carries an IMD readout");
        assertEquals(F1_HZ, dual.imd.f1Hz, FREQ_TOLERANCE_HZ);
        assertEquals(F2_HZ, dual.imd.f2Hz, FREQ_TOLERANCE_HZ);
        assertTrue(Double.isFinite(dual.imd.dfd2Pct), "f2 - f1 is measurable");

        // The fundamental is the upper tone, its partner below it.
        FftResult swapped = spectrum(F2_HZ, F1_HZ);
        swapped.fundamental2HzRefined = F1_HZ;
        analyzer.recomputeStats(swapped);
        assertNotNull(swapped.imd);
        assertEquals(F1_HZ, swapped.imd.f1Hz, FREQ_TOLERANCE_HZ, "F1 is the lower tone");
        assertTrue(swapped.imd.diffHz > 0.0, "difference frequency is positive");

        FftResult single = spectrum(F1_HZ, Double.NaN);
        single.imd = dual.imd;   // a recycled slot's stale readout
        analyzer.recomputeStats(single);
        assertNull(single.imd, "a single tone carries no IMD readout");

        FftResult copy = dual.deepCopy();
        assertNotSame(dual.imd, copy.imd, "a copy owns its IMD readout");
        assertEquals(dual.imd.tdnPct, copy.imd.tdnPct);
    }

    /** A stored spectrum finds its second tone and gets the IMD readout
     *  through applyDistortionSettings. */
    @Test
    void storedSpectrumFindsItsSecondTone() {
        FftResult stored = spectrum(F1_HZ, F2_HZ);
        FftAnalyzer analyzer = new FftAnalyzer(stored);
        analyzer.rebuildPhasors();
        analyzer.findFundamental(FUND_MIN_HZ);
        analyzer.findSecondTone();
        assertEquals(F2_HZ, stored.fundamental2HzRefined, FREQ_TOLERANCE_HZ);

        analyzer.applyDistortionSettings(0, 0, Double.NaN, 0.0, 0.0);
        assertNotNull(stored.imd, "the stored dual tone carries an IMD readout");
        assertEquals(F2_HZ, stored.imd.f2Hz, FREQ_TOLERANCE_HZ);
    }
}
