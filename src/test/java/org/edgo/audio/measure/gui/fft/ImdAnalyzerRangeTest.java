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

package org.edgo.audio.measure.gui.fft;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.edgo.audio.measure.fft.FftResult;
import org.junit.jupiter.api.Test;

/**
 * Range semantics of the IMD product readout: a product whose frequency
 * falls outside the measurable spectrum (below DC or beyond the last bin)
 * must report {@code NaN} — "not measurable" — never a synthetic floor
 * voltage.  One-sided DFD3 (SMPTE-style tone pairs, where {@code 2f1 − f2}
 * lands below DC) must still report the measurable sideband, and the
 * combined IMD power must skip unmeasurable products instead of going NaN.
 */
class ImdAnalyzerRangeTest {

    private static final double BIN_BW_HZ = 10.0;
    /** Spectrum length in bins — top frequency ≈ 40.95 kHz. */
    private static final int    BINS      = 4096;
    /** Any physically meaningful dBV reading sits far above this. */
    private static final double ABSURD_DBV = -400.0;

    /** Synthetic dBFS spectrum: −140 dBFS floor with two −6 dBFS tones. */
    private FftResult spectrumWithTones(double f1Hz, double f2Hz) {
        FftResult r = new FftResult();
        r.freqResolution = BIN_BW_HZ;
        r.fftSize        = BINS * 2;
        r.sampleRate     = (int) Math.round(BIN_BW_HZ * BINS * 2);
        r.amplitudeDbFs  = new double[BINS];
        Arrays.fill(r.amplitudeDbFs, -140.0);
        r.amplitudeDbFs[(int) Math.round(f1Hz / BIN_BW_HZ)] = -6.0;
        r.amplitudeDbFs[(int) Math.round(f2Hz / BIN_BW_HZ)] = -6.0;
        r.fundamentalHzRefined   = f1Hz;
        r.fundamental2HzRefined  = f2Hz;
        r.fundamentalTrueDbFs    = Double.NaN;   // no manual override
        return r;
    }

    @Test
    void outOfRangeProducts_reportNaN_neverMinus600() {
        // 600 Hz + 15 kHz: 2f1−f2 and all higher dnL sidebands land below
        // DC; d4H (43.8 kHz) and d5H (58.2 kHz) land beyond the spectrum.
        ImdResult imd = new ImdAnalyzer().analyze(
                spectrumWithTones(600.0, 15_000.0), 600.0, 15_000.0, 0.0);

        assertTrue(Double.isNaN(imd.dnLDbV[3]), "2f1-f2 below DC must be NaN");
        assertTrue(Double.isNaN(imd.dnHDbV[4]), "d4H beyond spectrum must be NaN");
        assertTrue(Double.isNaN(imd.dnHDbV[5]), "d5H beyond spectrum must be NaN");
        assertTrue(Double.isFinite(imd.dnHDbV[3]), "d3H (29.4 kHz) is measurable");
        assertTrue(Double.isFinite(imd.dfd2Pct), "f2-f1 (14.4 kHz) is measurable");
        // SMPTE-style one-sided DFD3: the lower sideband is below DC, but
        // the upper one is measurable and must still be reported.
        assertTrue(Double.isFinite(imd.dfd3Pct), "one-sided DFD3 must still report");
        // NaN products are skipped in the power sum, never poisoning it.
        assertTrue(Double.isFinite(imd.imdPwrPct), "IMD power must skip NaN products");

        for (int k = 2; k <= ImdResult.MAX_ORDER; k++) {
            assertAbsurdFree(imd.dnLDbV[k], "dnLDbV[" + k + "]");
            assertAbsurdFree(imd.dnHDbV[k], "dnHDbV[" + k + "]");
        }
    }

    @Test
    void allInRangePair_everyProductFinite() {
        // 19 + 20 kHz CCIF pair: every product from d2 to d5 fits the grid.
        ImdResult imd = new ImdAnalyzer().analyze(
                spectrumWithTones(19_000.0, 20_000.0), 19_000.0, 20_000.0, 0.0);

        for (int k = 2; k <= ImdResult.MAX_ORDER; k++) {
            assertTrue(Double.isFinite(imd.dnLDbV[k]), "dnLDbV[" + k + "] finite");
            assertTrue(Double.isFinite(imd.dnHDbV[k]), "dnHDbV[" + k + "] finite");
        }
        assertTrue(Double.isFinite(imd.dfd2Pct));
        assertTrue(Double.isFinite(imd.dfd3Pct));
        assertTrue(Double.isFinite(imd.imdPwrPct));
    }

    /** A dBV cell is either NaN (unmeasurable) or a plausible voltage. */
    private void assertAbsurdFree(double dbv, String what) {
        assertTrue(Double.isNaN(dbv) || dbv > ABSURD_DBV,
                what + " = " + dbv);
    }
}
