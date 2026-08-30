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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.WindowType;
import org.edgo.audio.measure.fft.FftAnalyzer;
import org.edgo.audio.measure.fft.FftResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The whole-spectrum {@code .frc} de-embedding - dividing EVERY FFT bin, noise
 * included, by the calibration response H(f) - asked as two questions.
 *
 * <p>First, EQUIVALENCE.  The response is now sampled at every bin once
 * ({@link FreqRespCalHelper#binCorrections}) and reused, instead of being
 * interpolated inside the per-bin divide.  The reference below is the per-bin
 * interpolation written out longhand from the public
 * {@link FreqRespCalHelper#interpolate}, and the corrected spectrum must match
 * it BIT FOR BIT - a de-embedded spectrum is a measurement, so "close enough"
 * is not the standard: the same input must keep producing the same numbers.
 *
 * <p>Second, the CACHE KEY.  The consumer holds one response per loaded
 * calibration row and rebuilds it when the rows, the analysed channel, the FFT
 * length or the capture rate change.  What is asked here is why each of those
 * belongs in that key: the response genuinely differs when any one of them
 * moves, so a key missing one would hand a later frame numbers computed for a
 * different grid or a different channel.
 */
class FreqRespCalDeEmbedTest {

    private static final int SAMPLE_RATE_HZ = 48_000;
    /** Small on purpose - the equivalence is per bin, so a short spectrum asks
     *  it as completely as a long one and the test stays quick. */
    private static final int FFT_SIZE = 1_024;
    private static final int DOUBLED_RATE_HZ = 96_000;
    private static final int DOUBLED_FFT_SIZE = 2_048;
    private static final int MAX_HARMONIC = 8;
    private static final int FUNDAMENTAL_BIN = 100;
    private static final double FUNDAMENTAL_AMPLITUDE = 0.5;
    /** Second harmonic at -40 dBc, so the corrected harmonic table is not trivially empty. */
    private static final double SECOND_HARMONIC_AMPLITUDE = FUNDAMENTAL_AMPLITUDE * 0.01;
    /** Below this linear amplitude the reference clamps the dB value, exactly as
     *  the whole-spectrum divide does. */
    private static final double DB_FLOOR_LIMIT = 1e-15;
    private static final double DB_FLOOR = -300.0;
    /** Row of {@link FreqRespCalHelper#binCorrections}' answer holding |H(f)|²
     *  per bin - the row that also marks a bin as untouchable with a zero. */
    private static final int H_MAG_SQ = 2;
    private static final double TWO = 2.0;

    /**
     * The cached response and the per-bin interpolation must produce the same
     * spectrum to the last bit - and the same readouts derived from it, since
     * the divide ends in a full statistics recompute.
     */
    @Test
    @DisplayName("the sampled response de-embeds bit-identically to per-bin interpolation")
    void theSampledResponseMatchesPerBinInterpolation() {
        FreqRespCalibration cal = notchLikeCal(1.0);
        FftResult reference = analyzedTone();
        FftResult sampled = reference.deepCopy();
        double uncorrectedFundamentalDbFs = reference.amplitudeDbFs[FUNDAMENTAL_BIN];

        deEmbedByInterpolatingEveryBin(reference, cal);
        new FftAnalyzer().recomputeStats(reference);

        FreqRespCalHelper.applyCompensationInPlace(sampled, cal, true,
                FreqRespCalHelper.binCorrections(cal, sampled.fftSize, sampled.sampleRate));

        assertNotEquals(uncorrectedFundamentalDbFs, sampled.amplitudeDbFs[FUNDAMENTAL_BIN],
                "the de-embedding changed nothing, so the comparison below would hold "
                        + "even if neither path worked");
        for (int k = 0; k <= FFT_SIZE / 2; k++) {
            assertEquals(reference.re[k], sampled.re[k], "real part of bin " + k);
            assertEquals(reference.im[k], sampled.im[k], "imaginary part of bin " + k);
            assertEquals(reference.amplitudeDbFs[k], sampled.amplitudeDbFs[k],
                    "level of bin " + k);
            assertEquals(reference.phaseDeg[k], sampled.phaseDeg[k], "phase of bin " + k);
        }
        assertEquals(reference.fundamentalDbFs, sampled.fundamentalDbFs, "fundamental level");
        assertEquals(reference.thdPct, sampled.thdPct, "THD");
        assertEquals(reference.snrDb, sampled.snrDb, "SNR");
    }

    /**
     * The FFT length is part of the key because the response is sampled at BIN
     * frequencies: doubling the length halves the bin width, so bin k of the
     * longer transform sits at half the frequency it did.
     */
    @Test
    @DisplayName("the response is specific to the FFT length")
    void theResponseFollowsTheFftLength() {
        FreqRespCalibration cal = notchLikeCal(1.0);
        double[][] shortFft = FreqRespCalHelper.binCorrections(cal, FFT_SIZE, SAMPLE_RATE_HZ);
        double[][] longFft = FreqRespCalHelper.binCorrections(cal, DOUBLED_FFT_SIZE, SAMPLE_RATE_HZ);

        assertEquals(FFT_SIZE / 2 + 1, shortFft[H_MAG_SQ].length, "one value per bin up to Nyquist");
        assertEquals(DOUBLED_FFT_SIZE / 2 + 1, longFft[H_MAG_SQ].length,
                "one value per bin up to Nyquist");
        assertNotEquals(shortFft[H_MAG_SQ][FUNDAMENTAL_BIN], longFft[H_MAG_SQ][FUNDAMENTAL_BIN],
                "bin " + FUNDAMENTAL_BIN + " is a different frequency at each length, so its "
                        + "response must differ - a key without the length would reuse the "
                        + "wrong grid's numbers");
    }

    /**
     * The capture rate is part of the key for the same reason at a fixed length:
     * the bin width is rate/length, so the same bin index is a different
     * frequency on a differently-clocked capture.
     */
    @Test
    @DisplayName("the response is specific to the capture rate")
    void theResponseFollowsTheCaptureRate() {
        FreqRespCalibration cal = notchLikeCal(1.0);
        double[][] atRate = FreqRespCalHelper.binCorrections(cal, FFT_SIZE, SAMPLE_RATE_HZ);
        double[][] atDoubleRate = FreqRespCalHelper.binCorrections(cal, FFT_SIZE, DOUBLED_RATE_HZ);

        assertNotEquals(atRate[H_MAG_SQ][FUNDAMENTAL_BIN], atDoubleRate[H_MAG_SQ][FUNDAMENTAL_BIN],
                "the same bin index is a different frequency at a different capture rate");
    }

    /**
     * The channel and the loaded rows are part of the key because they choose
     * WHICH calibration is sampled: the two channels of one file are measured
     * separately, and a different loaded file is a different response entirely.
     */
    @Test
    @DisplayName("the response is specific to the calibration it was sampled from")
    void theResponseFollowsTheCalibration() {
        FreqRespCalibration leftChannel = notchLikeCal(1.0);
        FreqRespCalibration rightChannel = notchLikeCal(0.5);
        FreqRespCalibration otherFile = notchLikeCal(2.0);

        double[][] fromLeft = FreqRespCalHelper.binCorrections(leftChannel, FFT_SIZE, SAMPLE_RATE_HZ);
        double[][] fromRight = FreqRespCalHelper.binCorrections(rightChannel, FFT_SIZE, SAMPLE_RATE_HZ);
        double[][] fromOther = FreqRespCalHelper.binCorrections(otherFile, FFT_SIZE, SAMPLE_RATE_HZ);

        assertNotEquals(fromLeft[H_MAG_SQ][FUNDAMENTAL_BIN], fromRight[H_MAG_SQ][FUNDAMENTAL_BIN],
                "the analysed channel picks the calibration, so switching channel must "
                        + "re-sample it");
        assertNotEquals(fromLeft[H_MAG_SQ][FUNDAMENTAL_BIN], fromOther[H_MAG_SQ][FUNDAMENTAL_BIN],
                "a different loaded row is a different response");
    }

    /** Bins outside the calibration's frequency range carry a zero {@code |H|²},
     *  which is what tells the divide to leave them alone - the range guard the
     *  per-bin loop used to apply itself. */
    @Test
    @DisplayName("bins outside the calibrated range are marked untouchable")
    void binsOutsideTheCalibratedRangeAreMarked() {
        FreqRespCalibration cal = notchLikeCal(1.0);
        double[][] h = FreqRespCalHelper.binCorrections(cal, FFT_SIZE, SAMPLE_RATE_HZ);
        double binWidth = SAMPLE_RATE_HZ / (double) FFT_SIZE;
        int nyquistBin = FFT_SIZE / 2;
        double calTopHz = cal.freqs[cal.freqs.length - 1];

        assertTrue(nyquistBin * binWidth > calTopHz,
                "the test grid must reach past the calibration's top point, or there is no "
                        + "out-of-range bin to ask about");
        assertEquals(0.0, h[H_MAG_SQ][nyquistBin],
                "a bin above the calibrated range is left untouched");
        assertTrue(h[H_MAG_SQ][FUNDAMENTAL_BIN] > 0.0, "while an in-range bin is corrected");
    }

    /**
     * The per-bin interpolation the divide used to do, written out longhand from
     * the public {@link FreqRespCalHelper#interpolate}: an independent oracle for
     * the equivalence assertion, deliberately not sharing code with the
     * implementation under test.
     */
    private void deEmbedByInterpolatingEveryBin(FftResult r, FreqRespCalibration cal) {
        int half = r.fftSize / 2;
        double binWidth = r.sampleRate / (double) r.fftSize;
        double fLo = cal.freqs[0];
        double fHi = cal.freqs[cal.freqs.length - 1];
        double oldFundMag = Math.hypot(r.re[r.fundamentalBin], r.im[r.fundamentalBin]);
        double linPerMag = oldFundMag > 0.0 ? r.fundamentalLinear / oldFundMag : 0.0;
        for (int k = 1; k <= half; k++) {
            double f = k * binWidth;
            if (f < fLo || f > fHi) continue;
            double[] h = FreqRespCalHelper.interpolate(cal, f);
            double hMag = h[0];
            if (hMag <= 0.0) continue;
            double hRe = hMag * Math.cos(h[1]);
            double hIm = hMag * Math.sin(h[1]);
            double hMagSq = hMag * hMag;
            double xRe = r.re[k], xIm = r.im[k];
            double zRe = (xRe * hRe + xIm * hIm) / hMagSq;
            double zIm = (xIm * hRe - xRe * hIm) / hMagSq;
            r.re[k] = zRe;
            r.im[k] = zIm;
            double newAmpLin = Math.hypot(zRe, zIm) * linPerMag;
            r.amplitudeDbFs[k] = newAmpLin > DB_FLOOR_LIMIT
                    ? 20.0 * Math.log10(newAmpLin) : DB_FLOOR;
            r.phaseDeg[k] = Math.toDegrees(Math.atan2(zIm, zRe));
        }
    }

    /**
     * A calibration shaped like the benches this feature is used on: a unity
     * pass-band with a deep, phase-rotating notch in the middle, on log-spaced
     * points.  {@code scale} multiplies every magnitude, standing for the second
     * channel of a file or a different loaded file - both of which the response
     * must follow.
     */
    private FreqRespCalibration notchLikeCal(double scale) {
        double[] freqs = { 20.0, 50.0, 200.0, 800.0, 950.0, 1_000.0, 1_050.0,
                           2_000.0, 5_000.0, 12_000.0, 20_000.0 };
        double[] magLin = { 1.0, 1.0, 0.98, 0.6, 0.05, 1e-4, 0.05, 0.7, 0.95, 1.0, 0.9 };
        double[] phaseRad = new double[freqs.length];
        for (int i = 0; i < freqs.length; i++) {
            magLin[i] *= scale;
            // A notch turns the phase through half a circle across its null.
            phaseRad[i] = -Math.PI / TWO + Math.PI * i / (freqs.length - 1.0);
        }
        return new FreqRespCalibration(freqs, magLin, phaseRad);
    }

    /** A measured spectrum to correct: a tone on a bin centre plus its second
     *  harmonic, analysed exactly as the FFT pane analyses one. */
    private FftResult analyzedTone() {
        double toneHz = (double) SAMPLE_RATE_HZ / FFT_SIZE * FUNDAMENTAL_BIN;
        double[] signal = new double[FFT_SIZE * 2];
        for (int n = 0; n < signal.length; n++) {
            signal[n] = FUNDAMENTAL_AMPLITUDE * Math.sin(TWO * Math.PI * toneHz * n / SAMPLE_RATE_HZ)
                    + SECOND_HARMONIC_AMPLITUDE
                            * Math.sin(TWO * Math.PI * 2 * toneHz * n / SAMPLE_RATE_HZ);
        }
        return new FftAnalyzer().analyze(signal, SAMPLE_RATE_HZ, FFT_SIZE, MAX_HARMONIC,
                WindowType.HANN, FftOverlap.PCT_0, 0.0, 0.0, true, Double.NaN, false);
    }
}
