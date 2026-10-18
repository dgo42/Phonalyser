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

import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.WindowType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two readings of a tone's fractional bin, driven with a synthetic tone
 * deliberately placed BETWEEN two bins: the one-frame log-power parabola the
 * analysis reads a frame's fundamental with, and the phase change of the
 * tone's bin between two frames ({@link FftAnalyzer#frameBinPhasor} read
 * twice, {@link MathUtil#kappaFromPhaseStep}) that the frequency lock is
 * steered with on the reference channel and that the coherent average
 * re-pins its fundamental by.  An estimate good only to the nearest bin
 * would be 0.7 Hz out at a 1 M-point FFT on a 48 kHz capture and useless for
 * a loop that chases parts per million.
 *
 * <p>Both window shapes the measurement meets are covered - the analysis
 * default BH7, whose main lobe spans several bins, and RECT, whose lobe is
 * one bin wide.
 */
class FftAnalyzerRefinedBinTest {

    private static final int FFT_SIZE    = 4096;
    private static final int SAMPLE_RATE = 48_000;
    /** The tone's integer bin - clear of DC and of the Nyquist end. */
    private static final int TONE_BIN = 500;
    /** How far between bins the tone sits, in bins: an awkward fraction, so a
     *  method that merely returned the peak bin, or rounded, cannot pass. */
    private static final double TONE_OFFSET_BINS = 0.37;
    /** The accuracy the one-frame parabola reaches under a tapered window. */
    private static final double TOLERANCE_BINS = 0.01;
    /** The accuracy of the phase change between two frames on a clean tone:
     *  rounding only. */
    private static final double PHASE_STEP_TOLERANCE_BINS = 1e-6;

    private final FftAnalyzer analyzer = new FftAnalyzer();

    @Test
    void offBinToneIsRecoveredWithTheAnalysisWindow() {
        assertParabolaRecovers(WindowType.BH7, TOLERANCE_BINS);
    }

    @Test
    void offBinToneIsRecoveredFromThePhaseChangeBetweenFrames() {
        // The analysis window only: under RECT the tone's own image at the
        // negative frequency leaks into the bin's phase and the step is no
        // longer exact.
        assertPhaseStepRecovers(WindowType.BH7, FFT_SIZE / 2);
    }

    /**
     * The same tone read through no window at all.  A rectangular window's main
     * lobe is a Dirichlet kernel one bin wide, and in the log-power domain that
     * shape is not a parabola: the three-point fit reads the offset SHORT - here
     * it answers about 0.21 bin for a tone 0.37 bins above its peak - so this
     * case states the accuracy the recipe actually reaches without a taper
     * rather than the tapered-window figure above.  The estimate still has to
     * land inside the right bin interval and on the right side of the peak,
     * which is what a broken fit would lose.
     */
    @Test
    void offBinToneWithoutAWindowIsRecoveredOnlyCoarsely() {
        double trueBin = TONE_BIN + TONE_OFFSET_BINS;
        double refined = assertParabolaRecovers(WindowType.RECT, 0.2);
        assertTrue(refined > TONE_BIN && refined < trueBin,
                "an untapered lobe reads short, but stays between the peak bin "
                        + "and the tone: " + refined);
    }

    /** The analysis of one frame of the tone, held to {@code toleranceBins}.
     *  Returns the estimate in bins. */
    private double assertParabolaRecovers(WindowType window, double toleranceBins) {
        double trueBin = TONE_BIN + TONE_OFFSET_BINS;
        FftResult r = analyzer.analyze(tone(trueBin, FFT_SIZE), SAMPLE_RATE, FFT_SIZE, 8, 8,
                window, FftOverlap.PCT_0, 0.0, 0.0, true, Double.NaN, false, Double.NaN,
                new FftResult(), FftOverlap.PCT_0.hopSamples(FFT_SIZE));
        double refined = r.fundamentalHzRefined / r.freqResolution;
        assertEquals(trueBin, refined, toleranceBins,
                "fractional bin of a tone " + TONE_OFFSET_BINS + " bins above bin "
                        + TONE_BIN + " (" + window + ", one frame)");
        return refined;
    }

    /** The phase change of the tone's bin between two frames {@code step}
     *  samples apart, which on a clean tone is exact. */
    private void assertPhaseStepRecovers(WindowType window, int step) {
        double trueBin = TONE_BIN + TONE_OFFSET_BINS;
        double[] samples = tone(trueBin, step + FFT_SIZE);
        FftAnalyzer.BinPhasor first  = analyzer.frameBinPhasor(samples, 0,    FFT_SIZE, window, TONE_BIN);
        FftAnalyzer.BinPhasor second = analyzer.frameBinPhasor(samples, step, FFT_SIZE, window, TONE_BIN);
        double refined = MathUtil.kappaFromPhaseStep(Math.atan2(first.im(), first.re()),
                Math.atan2(second.im(), second.re()), TONE_BIN, step, FFT_SIZE);
        assertEquals(trueBin, refined, PHASE_STEP_TOLERANCE_BINS,
                "fractional bin from the phase step (" + window + ", step " + step + ")");
    }

    /** {@code length} samples of a unit sine whose frequency is
     *  {@code bins} bins of an {@link #FFT_SIZE}-point transform. */
    private double[] tone(double bins, int length) {
        double[] samples = new double[length];
        double w = 2.0 * Math.PI * bins / FFT_SIZE;
        for (int n = 0; n < length; n++) {
            samples[n] = Math.sin(w * n);
        }
        return samples;
    }
}
