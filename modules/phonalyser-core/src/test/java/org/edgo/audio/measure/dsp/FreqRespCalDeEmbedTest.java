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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.function.IntToDoubleFunction;

import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.WindowType;
import org.edgo.audio.measure.fft.FftAnalyzer;
import org.edgo.audio.measure.fft.FftResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code .frc} de-embedding of an FFT spectrum, cascaded into ONE per-bin
 * complex array for every loaded calibration row of the analysed channel
 * ({@link FreqRespCalHelper#cascadeInverse}) and applied with one complex
 * multiply per bin ({@link FreqRespCalHelper#applyInverseInPlace}), asked as
 * four groups of questions.
 *
 * <p>EQUIVALENCE.  A single with-noise row - one that divides every bin, noise
 * included - must produce exactly the spectrum a per-bin interpolation
 * produces.  The reference below is that interpolation written out longhand
 * from the public {@link FreqRespCalHelper#interpolate}, sharing no code with
 * the methods under test, and the comparison is BIT FOR BIT: a de-embedded
 * spectrum is a measurement, so the same input must keep producing the same
 * numbers.
 *
 * <p>SERIES.  Two rows are two filters in series, so their cascade is the
 * COMPLEX PRODUCT of the two responses.  Applying the product once and
 * applying the two responses one after the other are the same operation in
 * exact arithmetic and differ only by where the roundings fall, so that
 * comparison carries a tolerance while the single-row one does not.
 *
 * <p>TONES.  A tone-only row corrects the tones alone: one value per tone,
 * written across the tone's whole main lobe, with every other bin - the noise
 * between the tones - left as the analyser wrote it.  Mixed with a with-noise
 * row, the lobe bins carry both responses and the rest carries only the
 * with-noise one.
 *
 * <p>KEYS.  The array is sampled on a grid (FFT length, capture rate), on a
 * calibration, and - while a tone-only row is active - on the frame's tone
 * positions and lobe extents.  What is asked here is why each of those belongs
 * in the key the consumer holds: the response genuinely differs when one
 * moves, so a key missing one would hand a later frame numbers computed for a
 * different grid, a different channel or bins the tones have left.
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
     *  the applying pass does. */
    private static final double DB_FLOOR_LIMIT = 1e-15;
    private static final double DB_FLOOR = -300.0;
    private static final double TWO = 2.0;
    /** Relative slack for the series comparison: the product applied once and
     *  the two responses applied one after the other round at different points,
     *  which reaches a few units in the last place of a double. */
    private static final double SERIES_RELATIVE_TOLERANCE = 1e-12;
    /** Absolute slack in dB for the same comparison. */
    private static final double SERIES_DB_TOLERANCE = 1e-9;
    /** Absolute slack in degrees for a phase rotation stated in degrees. */
    private static final double PHASE_DEGREE_TOLERANCE = 1e-9;
    private static final double HALF_TURN_DEG = 180.0;
    private static final double FULL_TURN_DEG = 360.0;

    // -------------------------------------------------------------------------
    // Equivalence and series
    // -------------------------------------------------------------------------

    /**
     * The cascaded array and the per-bin interpolation must produce the same
     * spectrum to the last bit - and the same readouts derived from it, which
     * is why both sides are put through the same statistics recompute.
     */
    @Test
    @DisplayName("one with-noise row de-embeds bit-identically to per-bin interpolation")
    void theCascadeMatchesPerBinInterpolation() {
        FreqRespCalibration cal = notchLikeCal(1.0);
        FftResult reference = analyzedTone();
        FftResult cascaded = reference.deepCopy();
        double uncorrectedFundamentalDbFs = reference.amplitudeDbFs[FUNDAMENTAL_BIN];
        double linPerMag = levelScaleOf(reference);

        deEmbedByInterpolatingEveryBin(reference, cal, linPerMag);
        new FftAnalyzer().recomputeStats(reference);

        FreqRespCalHelper.applyInverseInPlace(cascaded, withNoiseInverse(cascaded, List.of(cal)));
        new FftAnalyzer().recomputeStats(cascaded);

        assertNotEquals(uncorrectedFundamentalDbFs, cascaded.amplitudeDbFs[FUNDAMENTAL_BIN],
                "the de-embedding changed nothing, so the comparison below would hold "
                        + "even if neither path worked");
        for (int k = 0; k <= FFT_SIZE / 2; k++) {
            assertEquals(reference.re[k], cascaded.re[k], "real part of bin " + k);
            assertEquals(reference.im[k], cascaded.im[k], "imaginary part of bin " + k);
            assertEquals(reference.amplitudeDbFs[k], cascaded.amplitudeDbFs[k],
                    "level of bin " + k);
            assertEquals(reference.phaseDeg[k], cascaded.phaseDeg[k], "phase of bin " + k);
        }
        assertEquals(reference.fundamentalDbFs, cascaded.fundamentalDbFs, "fundamental level");
        assertEquals(reference.thdPct, cascaded.thdPct, "THD");
        assertEquals(reference.snrDb, cascaded.snrDb, "SNR");
    }

    /**
     * Two loaded rows are two filters in series.  The cascade divides once by
     * the complex product of the two responses; the sequential reference
     * divides by one response and then by the other, on the SAME level scale
     * (taken from the uncorrected frame, as the cascade takes it).  Exact
     * arithmetic makes the two identical; double arithmetic rounds the product
     * and the two quotients at different points, which is why this comparison
     * carries {@link #SERIES_RELATIVE_TOLERANCE} while the single-row one above
     * carries none.
     */
    @Test
    @DisplayName("two with-noise rows in series equal their complex product applied once")
    void twoRowsInSeriesEqualTheirComplexProduct() {
        FreqRespCalibration first = notchLikeCal(1.0);
        FreqRespCalibration second = slopedCal();
        FftResult sequential = analyzedTone();
        FftResult cascaded = sequential.deepCopy();
        double uncorrected = sequential.amplitudeDbFs[FUNDAMENTAL_BIN];
        double linPerMag = levelScaleOf(sequential);

        deEmbedByInterpolatingEveryBin(sequential, first, linPerMag);
        deEmbedByInterpolatingEveryBin(sequential, second, linPerMag);

        FreqRespCalHelper.applyInverseInPlace(cascaded,
                withNoiseInverse(cascaded, List.of(first, second)));

        assertNotEquals(uncorrected, cascaded.amplitudeDbFs[FUNDAMENTAL_BIN],
                "neither path corrected anything, so the comparison below is empty");
        for (int k = 0; k <= FFT_SIZE / 2; k++) {
            assertEquals(sequential.re[k], cascaded.re[k], slack(sequential.re[k]),
                    "real part of bin " + k);
            assertEquals(sequential.im[k], cascaded.im[k], slack(sequential.im[k]),
                    "imaginary part of bin " + k);
            assertEquals(sequential.amplitudeDbFs[k], cascaded.amplitudeDbFs[k],
                    SERIES_DB_TOLERANCE, "level of bin " + k);
        }
    }

    // -------------------------------------------------------------------------
    // Tone-only rows
    // -------------------------------------------------------------------------

    /**
     * A tone-only row writes ONE value per tone - the response at the tone
     * frequency - across that tone's whole main lobe, and touches nothing else.
     * Inside the lobe the value is applied as a stretch: the phase turns every
     * bin in full, the magnitude divides the peak in full and a bin on the
     * floor not at all, a bin between by its log-height above the floor - the
     * lobe grows taller on its feet instead of lifting off the floor as a
     * box.  Every other bin must still carry the analyser's own numbers,
     * unchanged in all four of its outputs.
     */
    @Test
    @DisplayName("a tone-only row corrects the tone lobes and nothing else")
    void aToneOnlyRowCorrectsOnlyTheToneLobes() {
        FreqRespCalibration cal = notchLikeCal(1.0);
        FftResult corrected = analyzedTone();
        FftResult raw = corrected.deepCopy();
        boolean[] inLobe = toneLobeBins(raw, cal);
        ToneLobe fundamentalLobe = lobeSpanAt(raw, raw.fundamentalHzRefined);
        double[] h = FreqRespCalHelper.interpolate(cal, raw.fundamentalHzRefined);

        int reported = FreqRespCalHelper.applyInverseInPlace(corrected,
                toneOnlyInverse(corrected, List.of(cal)));

        assertTrue(reported > 0, "nothing was corrected, so the checks below are empty");
        double peakLift = 0.0;
        double footLift = Double.POSITIVE_INFINITY;
        for (int k = fundamentalLobe.lo(); k <= fundamentalLobe.hi(); k++) {
            double rawMag = Math.hypot(raw.re[k], raw.im[k]);
            double newMag = Math.hypot(corrected.re[k], corrected.im[k]);
            double t = ToneLobeLift.stretchExponent(rawMag, fundamentalLobe.floorMag(), fundamentalLobe.peakMag());
            double expected = rawMag * Math.pow(1.0 / h[0], t);
            assertEquals(expected, newMag, slack(expected),
                    "bin " + k + " of the fundamental lobe is divided by the response magnitude "
                            + "raised to its stretch exponent");
            double turned = wrapDegrees(corrected.phaseDeg[k] - raw.phaseDeg[k]
                    + Math.toDegrees(h[1]));
            assertEquals(0.0, turned, PHASE_DEGREE_TOLERANCE,
                    "bin " + k + " of the fundamental lobe is rotated by the response phase");
            double liftDb = 20.0 * Math.log10(newMag / rawMag);
            peakLift = Math.max(peakLift, liftDb);
            footLift = Math.min(footLift, liftDb);
        }
        assertEquals(-20.0 * Math.log10(h[0]), peakLift, 1e-9, "the peak gets the full lift");
        assertTrue(footLift < peakLift / TWO,
                "the lobe's feet get less than half the peak's lift - a dome, not a box; feet "
                        + footLift + " dB against the peak's " + peakLift + " dB");
        for (int k = 0; k <= FFT_SIZE / 2; k++) {
            if (inLobe[k]) continue;
            assertEquals(raw.re[k], corrected.re[k], "real part of untouched bin " + k);
            assertEquals(raw.im[k], corrected.im[k], "imaginary part of untouched bin " + k);
            assertEquals(raw.amplitudeDbFs[k], corrected.amplitudeDbFs[k],
                    "level of untouched bin " + k);
            assertEquals(raw.phaseDeg[k], corrected.phaseDeg[k], "phase of untouched bin " + k);
        }
    }

    /**
     * A with-noise row and a tone-only row loaded together: the two groups
     * commute in the product, so a lobe bin must carry BOTH responses and every
     * bin between the tones only the with-noise one.  This is where a cascade
     * that lost a group, or that let the tone-only row reach past its lobes,
     * shows up.
     */
    @Test
    @DisplayName("a with-noise row and a tone-only row multiply where their bins meet")
    void mixedRowsMultiplyOnTheLobeBins() {
        FreqRespCalibration wide = notchLikeCal(1.0);
        FreqRespCalibration tones = slopedCal();
        FftResult mixed = analyzedTone();
        FftResult raw = mixed.deepCopy();
        boolean[] inLobe = toneLobeBins(raw, tones);
        double binWidth = SAMPLE_RATE_HZ / (double) FFT_SIZE;
        double[] toneSet = FreqRespCalHelper.toneFrequencies(mixed);
        ToneLobe[] spans = FreqRespCalHelper.toneLobeSpans(mixed, toneSet);

        InverseCascade inv = FreqRespCalHelper.cascadeInverse(List.of(wide), List.of(tones),
                toneSet, spans, mixed.fftSize, mixed.sampleRate);
        int reported = FreqRespCalHelper.applyInverseInPlace(mixed, inv);

        assertTrue(reported > 0, "nothing was corrected, so the checks below are empty");
        int lobeBin = FUNDAMENTAL_BIN;
        int noiseBin = firstBinBetweenTones(inLobe, wide);
        assertTrue(inLobe[lobeBin], "the fundamental's own bin is a lobe bin");
        assertTrue(noiseBin > 0,
                "the frame must have a bin between the tones inside the with-noise range, "
                        + "or the second half of this question is empty");
        // The with-noise row corrects a bin at the bin's own frequency, the
        // tone-only row the tone's lobe at the tone's frequency.
        double bothMag = FreqRespCalHelper.interpolate(wide, lobeBin * binWidth)[0]
                * FreqRespCalHelper.interpolate(tones, raw.fundamentalHzRefined)[0];
        assertEquals(Math.hypot(raw.re[lobeBin], raw.im[lobeBin]) / bothMag,
                Math.hypot(mixed.re[lobeBin], mixed.im[lobeBin]),
                slack(Math.hypot(raw.re[lobeBin], raw.im[lobeBin]) / bothMag),
                "a lobe bin is divided by BOTH responses");
        double wideOnly = FreqRespCalHelper.interpolate(wide, noiseBin * binWidth)[0];
        assertEquals(Math.hypot(raw.re[noiseBin], raw.im[noiseBin]) / wideOnly,
                Math.hypot(mixed.re[noiseBin], mixed.im[noiseBin]),
                slack(Math.hypot(raw.re[noiseBin], raw.im[noiseBin]) / wideOnly),
                "a bin between the tones is divided by the with-noise response alone");
    }

    /**
     * With no with-noise row loaded the correction covers a few lobes, so the
     * applying pass walks the marked bins instead of the whole spectrum: the
     * number of bins it reports is exactly the number of lobe bins, and far
     * short of the spectrum.
     */
    @Test
    @DisplayName("the tone-only pass reports the lobe bins as its corrected count")
    void theToneOnlyPassWalksOnlyTheLobeBins() {
        FreqRespCalibration cal = notchLikeCal(1.0);
        FftResult frame = analyzedTone();
        boolean[] inLobe = toneLobeBins(frame.deepCopy(), cal);
        int expected = 0;
        for (boolean marked : inLobe) {
            if (marked) expected++;
        }

        int reported = FreqRespCalHelper.applyInverseInPlace(frame,
                toneOnlyInverse(frame, List.of(cal)));

        assertTrue(expected > 0, "the tone set must reach the spectrum, or nothing is asked here");
        assertEquals(expected, reported, "every lobe bin and no other bin is written");
        assertTrue(reported < FFT_SIZE / 2,
                "a tone-only correction that walks the whole spectrum is the pass this "
                        + "fast path exists to avoid");
    }

    // -------------------------------------------------------------------------
    // One row on, one row off
    // -------------------------------------------------------------------------

    /**
     * A tone-only row taken off again by its reciprocal, sampled on the
     * corrected spectrum's own lobes, gives the uncorrected spectrum back: the
     * stretch exponent of a lobe bin is the same on the corrected lobe - the
     * floor beside it untouched, the peak and the bin lifted by the same power
     * of the response - so the lift comes off exactly, and the phase turns
     * back.
     */
    @Test
    @DisplayName("a tone-only row comes off exactly as it went on")
    void aToneOnlyRowComesOffExactlyAsItWentOn() {
        FreqRespCalibration cal = notchLikeCal(1.0);
        FftResult frame = analyzedTone();
        FftResult raw = frame.deepCopy();

        FreqRespCalHelper.applyInverseInPlace(frame, toneOnlyInverse(frame, List.of(cal)));
        new FftAnalyzer().recomputeStats(frame);
        assertNotEquals(raw.amplitudeDbFs[FUNDAMENTAL_BIN], frame.amplitudeDbFs[FUNDAMENTAL_BIN],
                "the row changed nothing, so taking it off proves nothing");
        FreqRespCalHelper.applyInverseInPlace(frame, toneOnlyInverse(frame, List.of(cal)).reciprocal());

        assertBack(raw, frame);
    }

    /** A with-noise row is one complex factor per bin, so its reciprocal takes
     *  it off every bin it reached. */
    @Test
    @DisplayName("a with-noise row comes off exactly as it went on")
    void aWithNoiseRowComesOffExactlyAsItWentOn() {
        FreqRespCalibration cal = notchLikeCal(1.0);
        FftResult frame = analyzedTone();
        FftResult raw = frame.deepCopy();

        FreqRespCalHelper.applyInverseInPlace(frame, withNoiseInverse(frame, List.of(cal)));
        new FftAnalyzer().recomputeStats(frame);
        FreqRespCalHelper.applyInverseInPlace(frame, withNoiseInverse(frame, List.of(cal)).reciprocal());

        assertBack(raw, frame);
    }

    /**
     * Two tone-only rows applied one at a time, each sampled on the spectrum
     * the other left, equal their cascade applied once on the uncorrected
     * spectrum: a row switched on next to an active one lands where the
     * correction from scratch would.
     */
    @Test
    @DisplayName("tone-only rows applied one at a time equal their cascade")
    void toneOnlyRowsAppliedOneAtATimeEqualTheirCascade() {
        FreqRespCalibration first = notchLikeCal(1.0);
        FreqRespCalibration second = slopedCal();
        FftResult oneByOne = analyzedTone();
        FftResult cascaded = oneByOne.deepCopy();

        FreqRespCalHelper.applyInverseInPlace(oneByOne, toneOnlyInverse(oneByOne, List.of(first)));
        new FftAnalyzer().recomputeStats(oneByOne);
        FreqRespCalHelper.applyInverseInPlace(oneByOne, toneOnlyInverse(oneByOne, List.of(second)));

        FreqRespCalHelper.applyInverseInPlace(cascaded, toneOnlyInverse(cascaded, List.of(first, second)));

        assertBack(cascaded, oneByOne);
    }

    /**
     * A power average the result carries is corrected exactly where and as
     * much as the spectrum: started equal to the spectrum, it stays equal to
     * it on every restated bin.  The Nyquist bin is left out - the analyser
     * halves its level and a restated bin does not.
     */
    @Test
    @DisplayName("a power average moves with the spectrum it belongs to")
    void aPowerAverageMovesWithTheSpectrum() {
        FreqRespCalibration cal = notchLikeCal(1.0);
        FftResult frame = analyzedTone();
        // Each bin's level on the scale a restated bin is written on.
        double linPerMag = levelScaleOf(frame);
        frame.powerAverageDbFs = new double[frame.amplitudeDbFs.length];
        for (int k = 0; k < frame.powerAverageDbFs.length; k++) {
            double amp = Math.hypot(frame.re[k], frame.im[k]) * linPerMag;
            frame.powerAverageDbFs[k] = amp > DB_FLOOR_LIMIT ? 20.0 * Math.log10(amp) : DB_FLOOR;
        }
        frame.hasPowerAverage  = true;
        double before = frame.powerAverageDbFs[FUNDAMENTAL_BIN];

        InverseCascade inv = withNoiseInverse(frame, List.of(cal));
        FreqRespCalHelper.applyInverseInPlace(frame, inv);

        assertNotEquals(before, frame.powerAverageDbFs[FUNDAMENTAL_BIN],
                "the correction moved nothing, so the comparison below is empty");
        for (int k = 1; k < FFT_SIZE / 2; k++) {
            if (FreqRespCalHelper.gainDbAt(inv, k) == 0.0) continue;   // a bin the row never reached
            assertEquals(frame.amplitudeDbFs[k], frame.powerAverageDbFs[k], SERIES_DB_TOLERANCE,
                    "the power average of bin " + k + " moved with its level");
        }
    }

    /** {@code actual} carries {@code expected}'s spectrum, bin for bin, within
     *  the rounding the two orders of operation leave - relative to the bin's
     *  magnitude, since a part near zero (a phase near a quarter turn) keeps
     *  the rounding of the whole phasor. */
    private void assertBack(FftResult expected, FftResult actual) {
        for (int k = 0; k <= FFT_SIZE / 2; k++) {
            double magnitudeSlack = slack(Math.hypot(expected.re[k], expected.im[k]));
            assertEquals(expected.re[k], actual.re[k], magnitudeSlack, "real part of bin " + k);
            assertEquals(expected.im[k], actual.im[k], magnitudeSlack, "imaginary part of bin " + k);
            assertEquals(expected.amplitudeDbFs[k], actual.amplitudeDbFs[k], SERIES_DB_TOLERANCE,
                    "level of bin " + k);
        }
    }

    // -------------------------------------------------------------------------
    // Rebuild keys
    // -------------------------------------------------------------------------

    /** The tone frequencies decide which bins carry the tone-only response, so
     *  a frame whose fundamental sits on another bin is a frame the held
     *  response no longer fits. */
    @Test
    @DisplayName("a moved fundamental bin moves the tone set")
    void aMovedFundamentalBinMovesTheToneSet() {
        FftResult frame = analyzedTone();
        double[] tones = FreqRespCalHelper.toneFrequencies(frame);

        FftResult moved = frame.deepCopy();
        moved.fundamentalHzRefined = frame.fundamentalHzRefined + frame.freqResolution;
        double[] movedTones = FreqRespCalHelper.toneFrequencies(moved);

        assertNotEquals(tones[0], movedTones[0],
                "the tone moved to another bin, so a response written into the old bins "
                        + "stands in the wrong place");
    }

    /** The response is written across each tone's lobe, so a frame whose lobe
     *  reaches other bins - a deeper average lowers the floor and widens it -
     *  needs the array built again. */
    @Test
    @DisplayName("a moved lobe span shows up in the spans")
    void aMovedLobeSpanShowsUpInTheSpans() {
        FftResult frame = analyzedTone();
        double[] tones = FreqRespCalHelper.toneFrequencies(frame);
        ToneLobe[] spans = FreqRespCalHelper.toneLobeSpans(frame, tones);
        ToneLobe span = lobeSpanAt(frame, frame.fundamentalHzRefined);

        FftResult widened = frame.deepCopy();
        int beyond = span.hi() + 1;
        widened.re[beyond] = frame.re[frame.fundamentalBin] / TWO;
        widened.im[beyond] = 0.0;
        ToneLobe[] widenedSpans = FreqRespCalHelper.toneLobeSpans(widened, tones);

        assertTrue(beyond <= FFT_SIZE / 2, "the widened bin must be inside the spectrum");
        assertFalse(FreqRespCalHelper.sameGeometry(spans, widenedSpans),
                "the lobe now reaches a bin the held response does not cover");
    }

    /** Nothing moved, so nothing is rebuilt: between rebuilds a frame costs the
     *  applying pass and this comparison, and no interpolation at all. */
    @Test
    @DisplayName("an unchanged frame keeps the same tone set and spans")
    void anUnchangedFrameKeepsTheSameSpans() {
        FftResult frame = analyzedTone();
        double[] tones = FreqRespCalHelper.toneFrequencies(frame);
        ToneLobe[] spans = FreqRespCalHelper.toneLobeSpans(frame, tones);

        FftResult again = frame.deepCopy();
        double[] againTones = FreqRespCalHelper.toneFrequencies(again);
        ToneLobe[] againSpans = FreqRespCalHelper.toneLobeSpans(again, againTones);

        assertTrue(Arrays.equals(tones, againTones), "an identical later frame's tone set");
        assertTrue(FreqRespCalHelper.sameGeometry(spans, againSpans), "and its lobe spans");
        assertTrue(spans.length > 1 + MAX_HARMONIC,
                "absent tones keep their slot, so the list is the full tone set and two "
                        + "frames always compare slot for slot");
    }

    // -------------------------------------------------------------------------
    // What the array is specific to
    // -------------------------------------------------------------------------

    /** A bin outside the calibration's swept range has no response to divide
     *  by, so it keeps the analyser's own numbers and reports no gain. */
    @Test
    @DisplayName("bins outside the calibrated range stay untouched")
    void binsOutsideTheCalibratedRangeStayUntouched() {
        FreqRespCalibration cal = notchLikeCal(1.0);
        FftResult frame = flatFrame(FFT_SIZE, SAMPLE_RATE_HZ);
        FftResult raw = frame.deepCopy();
        int nyquistBin = FFT_SIZE / 2;
        double binWidth = SAMPLE_RATE_HZ / (double) FFT_SIZE;

        InverseCascade inv = withNoiseInverse(frame, List.of(cal));
        FreqRespCalHelper.applyInverseInPlace(frame, inv);

        assertTrue(nyquistBin * binWidth > cal.freqs[cal.freqs.length - 1],
                "the test grid must reach past the calibration's top point, or there is no "
                        + "out-of-range bin to ask about");
        assertEquals(0.0, FreqRespCalHelper.gainDbAt(inv, nyquistBin),
                "a bin above the calibrated range carries no correction");
        assertEquals(raw.re[nyquistBin], frame.re[nyquistBin], "real part of the Nyquist bin");
        assertEquals(raw.im[nyquistBin], frame.im[nyquistBin], "imaginary part of the Nyquist bin");
        assertNotEquals(0.0, FreqRespCalHelper.gainDbAt(inv, FUNDAMENTAL_BIN),
                "while an in-range bin is corrected");
    }

    /**
     * The FFT length is part of the key because the response is sampled at BIN
     * frequencies: doubling the length halves the bin width, so bin k of the
     * longer transform sits at half the frequency it did.
     */
    @Test
    @DisplayName("the correction is specific to the FFT length")
    void theCorrectionFollowsTheFftLength() {
        FreqRespCalibration cal = notchLikeCal(1.0);

        double shortFft = gainAtFundamental(cal, FFT_SIZE, SAMPLE_RATE_HZ);
        double longFft = gainAtFundamental(cal, DOUBLED_FFT_SIZE, SAMPLE_RATE_HZ);

        assertNotEquals(shortFft, longFft,
                "bin " + FUNDAMENTAL_BIN + " is a different frequency at each length, so its "
                        + "response must differ - a key without the length would reuse the "
                        + "wrong grid's numbers");
    }

    /**
     * The capture rate is part of the key for the same reason at a fixed length:
     * the bin width is rate over length, so the same bin index is a different
     * frequency on a differently-clocked capture.
     */
    @Test
    @DisplayName("the correction is specific to the capture rate")
    void theCorrectionFollowsTheCaptureRate() {
        FreqRespCalibration cal = notchLikeCal(1.0);

        double atRate = gainAtFundamental(cal, FFT_SIZE, SAMPLE_RATE_HZ);
        double atDoubleRate = gainAtFundamental(cal, FFT_SIZE, DOUBLED_RATE_HZ);

        assertNotEquals(atRate, atDoubleRate,
                "the same bin index is a different frequency at a different capture rate");
    }

    /**
     * The channel and the loaded rows are part of the key because they choose
     * WHICH calibration is sampled: the two channels of one file are measured
     * separately, and a different loaded file is a different response entirely.
     */
    @Test
    @DisplayName("the correction is specific to the calibration it was sampled from")
    void theCorrectionFollowsTheCalibration() {
        double fromLeft = gainAtFundamental(notchLikeCal(1.0), FFT_SIZE, SAMPLE_RATE_HZ);
        double fromRight = gainAtFundamental(notchLikeCal(0.5), FFT_SIZE, SAMPLE_RATE_HZ);
        double fromOther = gainAtFundamental(notchLikeCal(TWO), FFT_SIZE, SAMPLE_RATE_HZ);

        assertNotEquals(fromLeft, fromRight,
                "the analysed channel picks the calibration, so switching channel must "
                        + "re-sample it");
        assertNotEquals(fromLeft, fromOther, "a different loaded row is a different response");
    }

    /** The array is one value per bin of the grid it was sampled on, so a
     *  spectrum of another length is a different number of bins - which is the
     *  grid guard the consumer makes before reading it. */
    @Test
    @DisplayName("the array carries one value per bin of its own grid")
    void theArrayCarriesOneValuePerBinOfItsOwnGrid() {
        FreqRespCalibration cal = notchLikeCal(1.0);

        InverseCascade shortInv = withNoiseInverse(flatFrame(FFT_SIZE, SAMPLE_RATE_HZ), List.of(cal));
        InverseCascade longInv =
                withNoiseInverse(flatFrame(DOUBLED_FFT_SIZE, SAMPLE_RATE_HZ), List.of(cal));

        assertEquals(FFT_SIZE / 2 + 1, shortInv.invRe().length, "one value per bin up to Nyquist");
        assertEquals(DOUBLED_FFT_SIZE / 2 + 1, longInv.invRe().length,
                "one value per bin up to Nyquist");
        assertEquals(0.0, FreqRespCalHelper.gainDbAt(shortInv, DOUBLED_FFT_SIZE / 2),
                "a bin index past the sampled grid carries no correction");
    }

    // -------------------------------------------------------------------------
    // The oracle and the fixtures
    // -------------------------------------------------------------------------

    /**
     * The per-bin interpolation and divide written out longhand from the public
     * {@link FreqRespCalHelper#interpolate}: an independent oracle for the
     * equivalence assertions, deliberately not sharing code with the methods
     * under test.  The response is inverted per bin and then multiplied in,
     * which is the operation the cascade performs; the level scale is supplied
     * so a second pass keeps the scale of the uncorrected frame.
     */
    private void deEmbedByInterpolatingEveryBin(FftResult r, FreqRespCalibration cal,
                                                double linPerMag) {
        int half = r.fftSize / 2;
        double binWidth = r.sampleRate / (double) r.fftSize;
        double fLo = cal.freqs[0];
        double fHi = cal.freqs[cal.freqs.length - 1];
        for (int k = 1; k <= half; k++) {
            double f = k * binWidth;
            if (f < fLo || f > fHi) continue;
            double[] h = FreqRespCalHelper.interpolate(cal, f);
            double hMag = h[0];
            if (hMag <= 0.0) continue;
            double hRe = hMag * Math.cos(h[1]);
            double hIm = hMag * Math.sin(h[1]);
            double hMagSq = hMag * hMag;
            double vRe = hRe / hMagSq;
            double vIm = -hIm / hMagSq;
            double xRe = r.re[k], xIm = r.im[k];
            double zRe = xRe * vRe - xIm * vIm;
            double zIm = xRe * vIm + xIm * vRe;
            r.re[k] = zRe;
            r.im[k] = zIm;
            double newAmpLin = Math.hypot(zRe, zIm) * linPerMag;
            r.amplitudeDbFs[k] = newAmpLin > DB_FLOOR_LIMIT
                    ? 20.0 * Math.log10(newAmpLin) : DB_FLOOR;
            r.phaseDeg[k] = Math.toDegrees(Math.atan2(zIm, zRe));
        }
    }

    /** The cascade of with-noise rows alone, sampled on the frame's grid. */
    private InverseCascade withNoiseInverse(FftResult r, List<FreqRespCalibration> rows) {
        double[] tones = FreqRespCalHelper.toneFrequencies(r);
        return FreqRespCalHelper.cascadeInverse(rows, List.of(), tones,
                FreqRespCalHelper.toneLobeSpans(r, tones), r.fftSize, r.sampleRate);
    }

    /** The cascade of tone-only rows alone, sampled on the frame's grid. */
    private InverseCascade toneOnlyInverse(FftResult r, List<FreqRespCalibration> rows) {
        double[] tones = FreqRespCalHelper.toneFrequencies(r);
        return FreqRespCalHelper.cascadeInverse(List.of(), rows, tones,
                FreqRespCalHelper.toneLobeSpans(r, tones), r.fftSize, r.sampleRate);
    }

    /** Linear amplitude per unit of raw bin magnitude, read off the
     *  fundamental of the UNCORRECTED frame - the scale every restated level
     *  is expressed on. */
    private double levelScaleOf(FftResult r) {
        double fundMag = Math.hypot(r.re[r.fundamentalBin], r.im[r.fundamentalBin]);
        return fundMag > 0.0 ? r.fundamentalLinear / fundMag : 0.0;
    }

    /** The lobe of the tone at {@code toneHz}, found the way the correction
     *  finds it: peak bin, robust local floor, walk out to the floor, the
     *  floor and the peak's magnitude carried for the stretch. */
    private ToneLobe lobeSpanAt(FftResult r, double toneHz) {
        int half = r.fftSize / 2;
        double binWidth = r.sampleRate / (double) r.fftSize;
        IntToDoubleFunction mag = k -> Math.hypot(r.re[k], r.im[k]);
        int peak = (int) Math.round(toneHz / binWidth);
        double floor = ToneLobeLift.localFloor(mag, peak, half, binWidth, r.windowType);
        int[] edges = ToneLobeLift.lobeBins(mag, peak, half, floor, binWidth, r.windowType);
        return new ToneLobe(peak, edges[0], edges[1], floor, mag.applyAsDouble(peak));
    }

    /** Every bin a tone-only row of {@code cal} reaches on {@code r}: the lobe
     *  spans of the fundamental and of each located harmonic that lies inside
     *  the calibration's swept range.  The frame carries no second tone, so no
     *  intermod product enters. */
    private boolean[] toneLobeBins(FftResult r, FreqRespCalibration cal) {
        double fLo = cal.freqs[0];
        double fHi = cal.freqs[cal.freqs.length - 1];
        boolean[] marked = new boolean[r.fftSize / 2 + 1];
        double[] tones = new double[1 + r.harmonicCount];
        tones[0] = r.fundamentalHzRefined;
        for (int i = 0; i < r.harmonicCount; i++) {
            tones[1 + i] = r.harmonicBins[i] > 0 ? r.harmonicHz[i] : 0.0;
        }
        for (double toneHz : tones) {
            if (!(toneHz > 0.0) || toneHz < fLo || toneHz > fHi) continue;
            ToneLobe span = lobeSpanAt(r, toneHz);
            for (int k = span.lo(); k <= span.hi(); k++) {
                if (k >= 1 && k < marked.length) marked[k] = true;
            }
        }
        return marked;
    }

    /** The first bin above the fundamental that no tone lobe covers and that
     *  the with-noise calibration still spans - a bin only the with-noise row
     *  can have touched.  A synthetic spectrum's lobes are found on its own
     *  numerical noise, so which bins are free is a property of the frame, not
     *  a fixed offset.  Returns 0 when the frame has no such bin. */
    private int firstBinBetweenTones(boolean[] inLobe, FreqRespCalibration withNoiseCal) {
        double binWidth = SAMPLE_RATE_HZ / (double) FFT_SIZE;
        double fHi = withNoiseCal.freqs[withNoiseCal.freqs.length - 1];
        for (int k = FUNDAMENTAL_BIN + 1; k < inLobe.length; k++) {
            if (!inLobe[k] && k * binWidth <= fHi) return k;
        }
        return 0;
    }

    /** The cascade's gain at the fundamental bin of a flat frame on the given
     *  grid - one number standing for the whole sampled response. */
    private double gainAtFundamental(FreqRespCalibration cal, int fftSize, int sampleRate) {
        return FreqRespCalHelper.gainDbAt(
                withNoiseInverse(flatFrame(fftSize, sampleRate), List.of(cal)), FUNDAMENTAL_BIN);
    }

    /** Absolute slack matching {@link #SERIES_RELATIVE_TOLERANCE} at the size
     *  of the value compared. */
    private double slack(double expected) {
        return Math.abs(expected) * SERIES_RELATIVE_TOLERANCE + DB_FLOOR_LIMIT;
    }

    /** An angle in degrees folded into (-180, +180]. */
    private double wrapDegrees(double deg) {
        double d = deg % FULL_TURN_DEG;
        if (d > HALF_TURN_DEG) d -= FULL_TURN_DEG;
        if (d <= -HALF_TURN_DEG) d += FULL_TURN_DEG;
        return d;
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

    /** A second, differently shaped calibration - a plain rising response with
     *  its own phase - so a cascade of two rows is not a cascade of one
     *  response with itself. */
    private FreqRespCalibration slopedCal() {
        double[] freqs = { 20.0, 200.0, 2_000.0, 20_000.0 };
        double[] magLin = { 0.3, 0.55, 0.8, 1.2 };
        double[] phaseRad = { 0.2, 0.35, -0.4, 0.9 };
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
        return new FftAnalyzer().analyze(signal, SAMPLE_RATE_HZ, FFT_SIZE, MAX_HARMONIC, MAX_HARMONIC,
                WindowType.HANN, FftOverlap.PCT_0, 0.0, 0.0, true, Double.NaN, false, Double.NaN,
                new FftResult(), FftOverlap.PCT_0.hopSamples(FFT_SIZE));
    }

    /** A single-tone frame of the stated geometry with the same magnitude in
     *  every bin - enough for the questions that ask only which frequency a bin
     *  index stands for. */
    private FftResult flatFrame(int fftSize, int sampleRate) {
        FftResult r = new FftResult();
        r.ensureArrays(fftSize / 2 + 1, 0);
        r.fftSize = fftSize;
        r.sampleRate = sampleRate;
        r.freqResolution = sampleRate / (double) fftSize;
        r.harmonicCount = 0;
        r.fundamentalBin = FUNDAMENTAL_BIN;
        r.fundamentalHz = FUNDAMENTAL_BIN * r.freqResolution;
        r.fundamentalHzRefined = r.fundamentalHz;
        r.fundamental2HzRefined = Double.NaN;
        r.fundamentalLinear = 1.0;
        for (int k = 0; k <= fftSize / 2; k++) {
            r.re[k] = 1.0;
            r.im[k] = 0.0;
            r.amplitudeDbFs[k] = 0.0;
            r.phaseDeg[k] = 0.0;
        }
        return r;
    }
}
