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

package org.edgo.audio.measure.gui.fft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fundamental's drift read from two pilots spaced ONE FUNDAMENTAL BIN
 * apart.  Every phase a case hands over is generated from {@code 2 pi k D / N}
 * and wrapped, as a spectrum's own phase is, with the notch's drift added to
 * the fundamental's; every expected value is that drift and nothing else.
 *
 * <p>What the cases are evidence FOR: that the drift comes out right whatever
 * the advance is - no matter how many whole turns each line has made, and
 * without the advance being told to the method at all.
 */
class PairAdvanceTest {

    /** A pair at the bench geometry, spaced one fundamental bin apart: 384 kHz
     *  over 2M points, the fundamental on bin 5478. */
    private static final int PILOT_BIN_1 = 106889;
    private static final int FUND_BIN    = 5478;
    private static final int PILOT_BIN_2 = PILOT_BIN_1 - FUND_BIN;
    private static final int FFT_SIZE    = 1 << 21;
    private static final int SAMPLE_RATE = 384000;
    /** The second tone of a dual pair, on its own bin. */
    private static final int TONE_2_BIN  = 8213;

    private static final double TURN_RAD  = 2.0 * Math.PI;
    /** The notch's drift the cases put on the fundamental, radians. */
    private static final double DRIFT_RAD = Math.toRadians(0.1);
    /** What a drift may miss its generated value by, radians: a ten-thousandth
     *  of a degree, far under the 0.1 degrees the bench asks for. */
    private static final double ANGLE_TOL = Math.toRadians(1.0e-4);
    /** Single tone: no second tone to read. */
    private static final double NO_TONE_2 = Double.NaN;
    /** Neither is read any more; both are handed over as a real caller does. */
    private static final double NOMINAL_ADVANCE = 165000.0;
    private static final double NO_TREND        = Double.NaN;
    /** The reading noise the cases hand over with every frame, radians: a
     *  bench pair's two pilots at 60 dB over their floor, at which the pair
     *  admits a candidate within 0.4 degrees.  The readings themselves are
     *  exact here, or carry a stated error, never noise. */
    private static final double READING_SIGMA_RAD = 1.2e-3;

    /** The phase a line at {@code bin} shows after an advance of
     *  {@code advance} samples, radians wrapped to one turn, with
     *  {@code extraRad} added - a reading error or the notch's drift. */
    private double phase(int bin, double advance, double extraRad) {
        double turns = bin * advance / (double) FFT_SIZE;
        return Math.IEEEremainder(TURN_RAD * (turns - Math.rint(turns)) + extraRad, TURN_RAD);
    }

    private PairAdvance pair() {
        return new PairAdvance(PILOT_BIN_1, PILOT_BIN_2, FFT_SIZE, SAMPLE_RATE);
    }

    /** The hop the driving cases below advance by, samples: a sixteenth of the
     *  transform, the deepest overlap the analysis runs at. */
    private static final double HOP = FFT_SIZE / 16.0;

    /**
     * Drives the pair until it has SETTLED on a correction and returns the
     * frame it settled with.  A correction is gathered over a span of stream
     * and then agreed with by frames that took no part in choosing it, so no
     * single frame can settle one; the cases below hand over as many as that
     * takes, at a constant drift and a constant loss.
     *
     * @param lost samples the capture never counted, from the first frame on
     */
    private PairAdvance.Result settle(PairAdvance pair, double startAdvance,
                                      double driftRad, double lost) {
        return settle(pair, startAdvance, driftRad, lost, 0.0);
    }

    /** The same, with a reading error put on BOTH pilots' phases. */
    private PairAdvance.Result settle(PairAdvance pair, double startAdvance,
                                      double driftRad, double lost, double errRad) {
        PairAdvance.Result last = null;
        for (int frame = 0; frame < 80; frame++) {
            double advance = startAdvance + frame * HOP + lost;
            double d1 = phase(PILOT_BIN_1, advance, errRad);
            double d2 = phase(PILOT_BIN_2, advance, errRad);
            double c  = phase(FUND_BIN,    advance, driftRad);
            last = pair.resolve(d1, d2, advance - lost, c, FUND_BIN,
                                NO_TONE_2, 0, NO_TREND, NO_TREND, READING_SIGMA_RAD);
            if (last.settled()) {
                return last;
            }
        }
        return last;
    }

    /** Where a drive settled: the frame index, -1 when it never did, and the
     *  last result. */
    private record Settled(int frame, PairAdvance.Result result) {}

    /** The same drive with a reading error on pilot 1 ALONE - which moves the
     *  coarse reading whole and the fine reading by a twentieth, so the miss
     *  between them is the error itself - and with {@code expectedRad} handed
     *  over as the trend's prediction on every frame. */
    private Settled settle(PairAdvance pair, double startAdvance, double driftRad, double lost,
                           double err1Rad, double expectedRad) {
        PairAdvance.Result last = null;
        for (int frame = 0; frame < 80; frame++) {
            double advance = startAdvance + frame * HOP + lost;
            double d1 = phase(PILOT_BIN_1, advance, err1Rad);
            double d2 = phase(PILOT_BIN_2, advance, 0.0);
            double c  = phase(FUND_BIN,    advance, driftRad);
            last = pair.resolve(d1, d2, advance - lost, c, FUND_BIN,
                                NO_TONE_2, 0, expectedRad, NO_TREND, READING_SIGMA_RAD);
            if (last.settled()) {
                return new Settled(frame, last);
            }
        }
        return new Settled(-1, last);
    }

    /** Half the fundamental's own period, samples: how far the capture's count
     *  may stand from the truth and still leave the reading exact. */
    private static final double HALF_PERIOD = FFT_SIZE / (2.0 * FUND_BIN);
    /** One fundamental period, samples: the spacing of the repetitions a
     *  correction can be looked for at. */
    private static final double PERIOD = FFT_SIZE / (double) FUND_BIN;

    /**
     * THE EVIDENCE.  The drift is recovered for advances spanning eight orders
     * of magnitude - a single sample, a fundamental period, the few hundred
     * samples a driver's silent xrun loses, a whole transform length, and four
     * hundred million samples, which is a seventeen-minute collection at
     * 384 kHz.  Each pilot has made millions of whole turns by the last of
     * them; they are read from the capture's count, which is exact here.
     */
    @Test
    void theDriftComesOutForAnyAdvance() {
        for (double advance : new double[] { 1.0, 382.83, 1138.0, 131072.0,
                                             FFT_SIZE, 3.0 * FFT_SIZE + 17.0, 396066048.0 }) {
            String what = "an advance of " + advance + " samples: ";
            PairAdvance.Result r = settle(pair(), advance, DRIFT_RAD, 0.0);
            assertEquals(DRIFT_RAD, r.excessChangeRad(), ANGLE_TOL, what + "the notch's drift");
            assertEquals(0.0, r.rigidRad(), ANGLE_TOL, what + "the two lines agree");
        }
    }

    /**
     * THE EVIDENCE FOR THE RE-SYNC.  The capture's count is what a silent xrun
     * or a gap in the generated signal falsifies, and the reading stands as
     * long as the count is right to half a FUNDAMENTAL period - 191 samples
     * here.  A single pilot needed it right to half of its own period, 9.8
     * samples: twenty times tighter, which is why it could not cross a
     * re-sync.  The re-syncs the bench logged moved 3, 7, 16 and 17 samples.
     */
    @Test
    void theCountMayBeWrongByHalfAFundamentalPeriod() {
        double advance = 1234567.0;
        double d1 = phase(PILOT_BIN_1, advance, 0.0);
        double d2 = phase(PILOT_BIN_2, advance, 0.0);
        double c  = phase(FUND_BIN,    advance, DRIFT_RAD);

        for (double off : new double[] { 0.0, 3.0, -17.0, 100.0, -180.0, HALF_PERIOD - 1.0 }) {
            // a fresh collection each time: the correction one frame finds is
            // carried into the next, so these would otherwise build on it
            PairAdvance.Result r = pair().resolve(d1, d2, advance + off,
                    c, FUND_BIN, NO_TONE_2, 0, NO_TREND, NO_TREND, READING_SIGMA_RAD);
            assertEquals(DRIFT_RAD, r.excessChangeRad(), ANGLE_TOL,
                    "a count off by " + off + " samples: the notch's drift");
        }
    }

    /**
     * THE LONG RUN.  The count never sees the samples a re-sync loses, so
     * they add up over a collection: sixty frames losing 17 samples each -
     * the largest single re-sync the bench logged - put the count 1020
     * samples out, five times the half period the reading rests on.  It holds
     * all the way, because what the last frame found corrects the next
     * frame's count, so the gap to be bridged is one frame's loss and never
     * their sum.
     */
    @Test
    void lossesThatAddUpOverACollectionAreCarried() {
        PairAdvance pair = pair();
        double hop      = 131072.0;
        double lost     = 17.0;
        double counted  = 0.0;
        double advance  = 0.0;
        for (int frame = 1; frame <= 60; frame++) {
            counted += hop;                    // what the capture believes
            advance += hop + lost;             // what the stream actually did
            double d1 = phase(PILOT_BIN_1, advance, 0.0);
            double d2 = phase(PILOT_BIN_2, advance, 0.0);
            double c  = phase(FUND_BIN,    advance, DRIFT_RAD);

            PairAdvance.Result r = pair.resolve(d1, d2, counted,
                    c, FUND_BIN, NO_TONE_2, 0, NO_TREND, NO_TREND, READING_SIGMA_RAD);
            assertEquals(DRIFT_RAD, r.excessChangeRad(), ANGLE_TOL,
                    "frame " + frame + ", count " + (advance - counted)
                            + " samples out: the notch's drift");
        }
    }

    /**
     * A loss far PAST half a fundamental period is recovered, which is the
     * whole point of choosing the correction rather than taking it from the
     * count.  Half a period is 191 samples here; one USB packet at 384 kHz is
     * 384, and two of the glitches the bench shows fall in the same discarded
     * stretch come to 200.  All three are recovered.
     */
    @Test
    void aLossPastHalfAPeriodIsRecovered() {
        for (double lost : new double[] { HALF_PERIOD + 5.0, 200.0, 384.0, 2000.0, 7000.0 }) {
            PairAdvance.Result r = settle(pair(), 1234567.0, DRIFT_RAD, lost);
            assertEquals(DRIFT_RAD, r.excessChangeRad(), ANGLE_TOL,
                    lost + " samples lost and never counted: the notch's drift");
        }
    }

    /**
     * A STEP in the drift is reported on the frame it happens.  Nothing is
     * carried between frames and no continuity is assumed, so a notch that
     * moves is read at its new value immediately - the case the trend-fed
     * search could not see.
     */
    @Test
    void aStepInTheDriftIsReportedAtOnce() {
        PairAdvance pair = pair();
        double advance = 4096.0;
        double step    = Math.toRadians(-3.43);
        double d1 = phase(PILOT_BIN_1, advance, 0.0);
        double d2 = phase(PILOT_BIN_2, advance, 0.0);

        PairAdvance.Result before = pair.resolve(d1, d2, advance,
                phase(FUND_BIN, advance, DRIFT_RAD), FUND_BIN, NO_TONE_2, 0, NO_TREND, NO_TREND,
                READING_SIGMA_RAD);
        PairAdvance.Result after  = pair.resolve(d1, d2, advance,
                phase(FUND_BIN, advance, DRIFT_RAD + step), FUND_BIN, NO_TONE_2, 0,
                NO_TREND, NO_TREND, READING_SIGMA_RAD);
        assertEquals(DRIFT_RAD, before.excessChangeRad(), ANGLE_TOL, "before the step");
        assertEquals(DRIFT_RAD + step, after.excessChangeRad(), ANGLE_TOL, "after the step");
    }

    /**
     * A line on a WHOLE MULTIPLE of the fundamental's bin is carried: the
     * pilots' difference dropped a whole number of turns, and a whole multiple
     * of it drops whole turns again.  This is the harmonic ladder, so the
     * rotation the accumulator needs at every harmonic follows from the same
     * one reading.
     */
    @Test
    void aWholeMultipleOfTheFundamentalIsCarried() {
        PairAdvance pair = pair();
        double advance = 700000.0;
        double drift2  = Math.toRadians(-0.25);
        for (int h : new int[] { 2, 3, 9 }) {
            int    bin = h * FUND_BIN;
            double d1  = phase(PILOT_BIN_1, advance, 0.0);
            double d2  = phase(PILOT_BIN_2, advance, 0.0);
            double c1  = phase(FUND_BIN,    advance, DRIFT_RAD);
            double c2  = phase(bin,         advance, drift2);

            PairAdvance.Result r = pair.resolve(d1, d2, advance,
                    c1, FUND_BIN, c2, bin, NO_TREND, NO_TREND, READING_SIGMA_RAD);
            assertEquals(DRIFT_RAD, r.excessChangeRad(), ANGLE_TOL,
                    "harmonic " + h + ": the fundamental's drift");
            assertEquals(drift2, r.excessChange2Rad(), ANGLE_TOL,
                    "harmonic " + h + ": the line's own drift");
        }
    }

    /**
     * A second tone that is NOT a whole multiple of the fundamental's bin
     * cannot be carried, and says so rather than returning a figure the
     * scaling has corrupted: the turns the difference dropped come back scaled
     * by a fraction and no longer wrap away.
     */
    @Test
    void aSecondToneOffTheLadderIsRefused() {
        PairAdvance pair = pair();
        double advance = 700000.0;
        double d1 = phase(PILOT_BIN_1, advance, 0.0);
        double d2 = phase(PILOT_BIN_2, advance, 0.0);
        double c1 = phase(FUND_BIN,    advance, DRIFT_RAD);
        double c2 = phase(TONE_2_BIN,  advance, Math.toRadians(-0.25));

        PairAdvance.Result r = pair.resolve(d1, d2, advance,
                c1, FUND_BIN, c2, TONE_2_BIN, NO_TREND, NO_TREND, READING_SIGMA_RAD);
        assertEquals(DRIFT_RAD, r.excessChangeRad(), ANGLE_TOL, "the fundamental's drift");
        assertTrue(Double.isNaN(r.excessChange2Rad()), "bin " + TONE_2_BIN + " is off the ladder");
    }

    /** A pair elected at any other spacing states that it measured nothing,
     *  rather than returning a figure the construction does not support. */
    @Test
    void aWrongSpacingMeasuresNothing() {
        PairAdvance wrong = new PairAdvance(PILOT_BIN_1, 90305, FFT_SIZE, SAMPLE_RATE);
        PairAdvance.Result r = wrong.resolve(0.1, 0.2, NOMINAL_ADVANCE,
                0.3, FUND_BIN, NO_TONE_2, 0, NO_TREND, NO_TREND, READING_SIGMA_RAD);
        assertTrue(Double.isNaN(r.excessChangeRad()), "no drift");
        assertTrue(Double.isNaN(r.rigidRad()), "no residual");
    }

    /**
     * The fine stage is what the precision comes from: a reading error on a
     * pilot reaches the drift divided by the ratio of the bins, 19.5 for the
     * upper line of this pair, where the coarse difference alone would carry
     * it at full size.  Both pilots are read, so their errors average.
     */
    @Test
    void aReadingErrorIsDividedByTheBinRatio() {
        double err = Math.toRadians(0.18);
        PairAdvance.Result r = settle(pair(), 65536.0, DRIFT_RAD, 0.0, err);
        double reached  = Math.abs(r.excessChangeRad() - DRIFT_RAD);
        double atCoarse = 0.5 * err * (FUND_BIN / (double) PILOT_BIN_1
                                     + FUND_BIN / (double) PILOT_BIN_2);
        assertEquals(atCoarse, reached, ANGLE_TOL, "the error, divided by the bins");
        assertTrue(reached < err / 15.0, "at least fifteen times smaller than the reading error");
    }

    /**
     * A candidate is not used until frames that took no part in choosing it
     * agree with it: the first frames of a collection therefore read no
     * residuals at all, and the drift they report is the coarse one, which is
     * unbiased.  This is what keeps a wrongly chosen candidate out of the
     * readout entirely rather than merely rare in it.
     */
    @Test
    void nothingIsReadFromAnUnconfirmedCandidate() {
        PairAdvance pair = pair();
        double advance = 65536.0;
        double d1 = phase(PILOT_BIN_1, advance, 0.0);
        double d2 = phase(PILOT_BIN_2, advance, 0.0);
        double c  = phase(FUND_BIN,    advance, DRIFT_RAD);

        PairAdvance.Result first = pair.resolve(d1, d2, advance, c, FUND_BIN,
                NO_TONE_2, 0, NO_TREND, NO_TREND, READING_SIGMA_RAD);
        assertTrue(!first.settled(), "the first frame settles nothing, so it is not averaged");
        assertTrue(Double.isNaN(first.rigidRad()), "and reports no residuals");
        assertEquals(DRIFT_RAD, first.excessChangeRad(), ANGLE_TOL,
                "while still reading the drift, for the trace");

        PairAdvance.Result settled = settle(pair(), advance, DRIFT_RAD, 0.0);
        assertTrue(settled.settled(), "enough frames settle one");
        assertTrue(!Double.isNaN(settled.rigidRad()), "and it reports its residuals");
    }

    /**
     * THE REPETITION TWO PERIODS OUT.  At this pair's ratio, 19.512 to the
     * fundamental, a correction two fundamental periods off moves both fine
     * readings by only 0.47 degrees (two periods are 39.025 turns of pilot 1,
     * a fortieth of a turn short of whole).  A reading error on one pilot
     * that puts the miss at 0.35 degrees - inside the 0.4 the admission
     * allows - makes that repetition fit the pilots BETTER than the truth,
     * and on the pilots alone the pair settles on it: the drift comes out
     * 0.47 degrees wrong.  The prediction of the drift tells the two apart,
     * because the wrong repetition moves the excess by that same 0.47
     * degrees.  The loss is put past the seed's reach, so the frames decide.
     */
    @Test
    void thePredictionTellsTheRepetitionTwoPeriodsOutApart() {
        double err1 = Math.toRadians(0.36);
        double lost = HALF_PERIOD + 5.0;
        Settled blind = settle(pair(), 1234567.0, DRIFT_RAD, lost, err1, NO_TREND);
        assertTrue(blind.frame() >= 0, "the pilots alone settle");
        assertEquals(Math.toRadians(0.47), Math.abs(blind.result().excessChangeRad() - DRIFT_RAD),
                Math.toRadians(0.03), "and on the repetition two periods out");
        Settled told = settle(pair(), 1234567.0, DRIFT_RAD, lost, err1, DRIFT_RAD);
        assertEquals(DRIFT_RAD, told.result().excessChangeRad(), Math.toRadians(0.02),
                "with the prediction the truth wins, to the fine reading's share of the error");
    }

    /**
     * THE SEED BY THE PREDICTION.  A loss of two periods and a little puts the
     * value nearest the carried correction two periods off; on the pilots
     * alone that seed is refused and the frames decide the long way, a
     * decision span later.  With the prediction the seed is the repetition
     * whose excess continues the trend, and it settles in the two confirming
     * frames.
     */
    @Test
    void thePredictionSeedsTheRightRepetitionAtOnce() {
        double lost = 2.0 * PERIOD + 10.0;
        Settled blind = settle(pair(), 1234567.0, DRIFT_RAD, lost, 0.0, NO_TREND);
        assertEquals(DRIFT_RAD, blind.result().excessChangeRad(), ANGLE_TOL, "the long way, still right");
        assertTrue(blind.frame() > 10, "but a decision span later: frame " + blind.frame());
        Settled told = settle(pair(), 1234567.0, DRIFT_RAD, lost, 0.0, DRIFT_RAD);
        assertEquals(DRIFT_RAD, told.result().excessChangeRad(), ANGLE_TOL, "seeded right");
        assertEquals(CONFIRMING_FRAMES, told.frame(), "settled by the confirming frames alone");
    }

    /** The frame index a seeded candidate settles on: the seed's own frame
     *  and the two that confirm it. */
    private static final int CONFIRMING_FRAMES = 2;

    /**
     * A SLOW WALK OF THE PILOTS' PHASES against their difference - tenths of
     * a degree at the fundamental on the bench - says nothing about the turn
     * count, and the pair measures its admission from the walk's median
     * instead of from zero.  A reading error on pilot 1 alone that grows
     * from nothing to 0.45 degrees over 40 frames puts the miss past the
     * 0.4-degree admission; the pair stays settled throughout, because the
     * median follows the walk, and the drift comes out to the fine reading's
     * share of the error.
     */
    @Test
    void aSlowWalkOfThePilotsIsAbsorbed() {
        PairAdvance pair = pair();
        double advance = 1234567.0;
        double walkRad = Math.toRadians(0.45);
        int settledFrom = -1;
        for (int frame = 0; frame < 140; frame++) {
            double err1 = frame < 40 ? 0.0 : walkRad * Math.min(1.0, (frame - 40) / 40.0);
            double d1 = phase(PILOT_BIN_1, advance, err1);
            double d2 = phase(PILOT_BIN_2, advance, 0.0);
            double c  = phase(FUND_BIN,    advance, DRIFT_RAD);
            PairAdvance.Result r = pair.resolve(d1, d2, advance, c, FUND_BIN,
                    NO_TONE_2, 0, NO_TREND, NO_TREND, READING_SIGMA_RAD);
            if (settledFrom < 0 && r.settled()) settledFrom = frame;
            if (settledFrom >= 0) {
                assertTrue(r.settled(), "frame " + frame + ": dropped by the walk");
                assertEquals(DRIFT_RAD, r.excessChangeRad(), Math.toRadians(0.02),
                        "frame " + frame + ": the drift, to the fine reading's share of the error");
            }
            advance += HOP;
        }
        assertTrue(settledFrom >= 0 && settledFrom < 40, "settled before the walk began");
    }

}
