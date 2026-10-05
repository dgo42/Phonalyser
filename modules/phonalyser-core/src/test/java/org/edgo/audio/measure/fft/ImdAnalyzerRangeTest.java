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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.edgo.audio.measure.enums.WindowType;
import org.junit.jupiter.api.Test;

/**
 * Range semantics of the IMD product readout: a product whose frequency
 * falls outside the measurable spectrum (below DC or beyond the last bin)
 * must report {@code NaN} - "not measurable" - never a synthetic floor
 * level.  One-sided DFD3 (SMPTE-style tone pairs, where {@code 2f1 − f2}
 * lands below DC) must still report the measurable sideband, and the
 * combined IMD power must skip unmeasurable products instead of going NaN.
 *
 * <p>And what the readout does with lines that are generated rather than
 * measured: the two pilots of the fundamental phase alignment, which the TD+N
 * residual has to leave out.
 */
class ImdAnalyzerRangeTest {

    private static final double BIN_BW_HZ = 10.0;
    /** Spectrum length in bins - top frequency ≈ 40.95 kHz. */
    private static final int    BINS      = 4096;
    /** Any physically meaningful dBFS reading sits far above this. */
    private static final double ABSURD_DBFS = -400.0;
    /** The bins the alignment's two pilots sit on: 30 kHz and 35 kHz at this
     *  fixture's 10 Hz bins - above both tones and above every product the
     *  readout evaluates (the highest is 4·f2 − 3·f1 = 23 kHz for the pair
     *  used here), and far enough apart that the two spans do not touch. */
    private static final int PILOT1_BIN = 3_000;
    private static final int PILOT2_BIN = 3_500;
    /** BH7's main-lobe half-width in bins - the width the analyser stamps
     *  around each pilot's bin, the window having seven coefficients. */
    private static final int BH7_LOBE_BINS = 7;
    /** The level of each pilot.  -100 dBFS is 1e-5 of full scale: 2.4
     *  times the power of the fixture's whole noise floor per line, so a
     *  residual that counted them would read several dB high. */
    private static final double PILOT_LEVEL_DBFS = -100.0;
    /** How far the two TD+N readings may differ, dB. */
    private static final double TDN_TOLERANCE_DB = 0.01;

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

    /** The same spectrum with the alignment's two pilots pinned on it the way
     *  the analyser pins them: the window it was analysed with, each pilot's
     *  bin, and the bins that bin covers - itself plus the window's main lobe
     *  on either side.  {@code linesOn} adds the pilot LINES at those bins.
     *
     *  <p>Both spectra carry the same pinned spans, so what the comparison
     *  isolates are the lines and not the handful of floor bins the spans
     *  cover. */
    private FftResult spectrumWithPilots(double f1Hz, double f2Hz, boolean linesOn) {
        FftResult r = spectrumWithTones(f1Hz, f2Hz);
        r.windowType      = WindowType.BH7;
        r.pilot1Bin       = PILOT1_BIN;
        r.pilot1MaskLoBin = PILOT1_BIN - BH7_LOBE_BINS;
        r.pilot1MaskHiBin = PILOT1_BIN + BH7_LOBE_BINS;
        r.pilot2Bin       = PILOT2_BIN;
        r.pilot2MaskLoBin = PILOT2_BIN - BH7_LOBE_BINS;
        r.pilot2MaskHiBin = PILOT2_BIN + BH7_LOBE_BINS;
        if (linesOn) {
            r.amplitudeDbFs[PILOT1_BIN] = PILOT_LEVEL_DBFS;
            r.amplitudeDbFs[PILOT2_BIN] = PILOT_LEVEL_DBFS;
        }
        return r;
    }

    @Test
    void outOfRangeProducts_reportNaN_neverMinus600() {
        // 600 Hz + 15 kHz: 2f1−f2 and all higher dnL sidebands land below
        // DC; d4H (43.8 kHz) and d5H (58.2 kHz) land beyond the spectrum.
        ImdResult imd = new ImdAnalyzer().analyze(
                spectrumWithTones(600.0, 15_000.0), 600.0, 15_000.0);

        assertTrue(Double.isNaN(imd.dnLDbFs[3]), "2f1-f2 below DC must be NaN");
        assertTrue(Double.isNaN(imd.dnHDbFs[4]), "d4H beyond spectrum must be NaN");
        assertTrue(Double.isNaN(imd.dnHDbFs[5]), "d5H beyond spectrum must be NaN");
        assertTrue(Double.isFinite(imd.dnHDbFs[3]), "d3H (29.4 kHz) is measurable");
        assertTrue(Double.isFinite(imd.dfd2Pct), "f2-f1 (14.4 kHz) is measurable");
        // SMPTE-style one-sided DFD3: the lower sideband is below DC, but
        // the upper one is measurable and must still be reported.
        assertTrue(Double.isFinite(imd.dfd3Pct), "one-sided DFD3 must still report");
        // NaN products are skipped in the power sum, never poisoning it.
        assertTrue(Double.isFinite(imd.imdPwrPct), "IMD power must skip NaN products");

        for (int k = 2; k <= ImdResult.MAX_ORDER; k++) {
            assertAbsurdFree(imd.dnLDbFs[k], "dnLDbFs[" + k + "]");
            assertAbsurdFree(imd.dnHDbFs[k], "dnHDbFs[" + k + "]");
        }
    }

    @Test
    void allInRangePair_everyProductFinite() {
        // 19 + 20 kHz CCIF pair: every product from d2 to d5 fits the grid.
        ImdResult imd = new ImdAnalyzer().analyze(
                spectrumWithTones(19_000.0, 20_000.0), 19_000.0, 20_000.0);

        for (int k = 2; k <= ImdResult.MAX_ORDER; k++) {
            assertTrue(Double.isFinite(imd.dnLDbFs[k]), "dnLDbFs[" + k + "] finite");
            assertTrue(Double.isFinite(imd.dnHDbFs[k]), "dnHDbFs[" + k + "] finite");
        }
        assertTrue(Double.isFinite(imd.dfd2Pct));
        assertTrue(Double.isFinite(imd.dfd3Pct));
        assertTrue(Double.isFinite(imd.imdPwrPct));
    }

    /**
     * Both pilot lines of the fundamental phase alignment stay out of TD+N -
     * the dual-tone readout's total distortion plus noise, the drop from the
     * total RMS to the two tones.  A pilot is generated as a time base for
     * the phase measurement, not produced by the device under test: it is
     * neither one of the tones nor a distortion product, and at their level
     * the pair would lay a floor of its own under the very figure a
     * predistorted dual tone is driven below.  Asked as the difference the two
     * LINES make on one and the same spectrum: none, to within a hundredth of
     * a dB.  Left in the residual, they raise TD+N by more than 5 dB on this
     * fixture.
     */
    @Test
    void thePilotLinesStayOutOfTheTdnResidual() {
        double f1Hz = 19_000.0;
        double f2Hz = 20_000.0;
        double tdnPlain = new ImdAnalyzer()
                .analyze(spectrumWithPilots(f1Hz, f2Hz, false), f1Hz, f2Hz).tdnPct;
        double tdnPilots = new ImdAnalyzer()
                .analyze(spectrumWithPilots(f1Hz, f2Hz, true), f1Hz, f2Hz).tdnPct;

        assertTrue(tdnPlain > 0.0, "the fixture's own noise floor gives no TD+N to compare against");
        assertEquals(0.0, 20.0 * Math.log10(tdnPilots / tdnPlain), TDN_TOLERANCE_DB,
                "a pilot line reached TD+N: " + tdnPlain + " % without them, "
                        + tdnPilots + " % with them");
    }

    /** A dBFS cell is either NaN (unmeasurable) or a plausible level. */
    private void assertAbsurdFree(double dbfs, String what) {
        assertTrue(Double.isNaN(dbfs) || dbfs > ABSURD_DBFS,
                what + " = " + dbfs);
    }
}
