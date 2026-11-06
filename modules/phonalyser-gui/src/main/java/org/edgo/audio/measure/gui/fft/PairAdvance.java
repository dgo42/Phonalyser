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

import java.util.ArrayList;
import java.util.List;

import org.edgo.audio.measure.dsp.FftDsp;

import lombok.Getter;

/**
 * The fundamental's phase drift since a collection's first read frame - its
 * excess - read from three lines: the two pilots and the tone itself.
 *
 * <p>A line at bin {@code k} of an {@code N}-point transform turns by
 * {@code 2 pi k D / N} radians over an advance of {@code D} samples, and a
 * measured phase is known only modulo one turn, so no single line fixes
 * {@code D}.  The tone's own change carries two things at once - that turn
 * and the drift the notch has added - and the pilots exist to take the first
 * one away: they stand far above the notch, so their phases carry the
 * advance alone.
 *
 * <p>The two pilots are elected ONE FUNDAMENTAL BIN APART, {@code p1 - p2 =
 * k0}.  Their difference is then, identically,
 *
 * <pre>
 *   d1 - d2 = 2 pi (p1 - p2) D / N = 2 pi k0 D / N
 * </pre>
 *
 * which is the fundamental's own share of the same advance.  Subtracting it
 * from the tone's measured change leaves the drift:
 *
 * <pre>
 *   excess = wrap( toneChange - wrap(d1 - d2) )
 * </pre>
 *
 * <p>Everything here is modulo one turn and that is why it holds.  Each
 * measured phase sits on an unknown branch; the difference of the two pilots
 * carries an unknown whole number of turns with it, and because the spacing
 * equals the fundamental's bin exactly, that unknown enters the fundamental's
 * angle as a whole number of turns as well and wraps away.  An advance wrong
 * by {@code N / k0} samples moves the fundamental by exactly one turn and
 * harmonic {@code h} by exactly {@code h} turns - indistinguishable, for
 * every {@code h}.  So a gap of any size, on the capture side or in the
 * generated signal, costs nothing: there is no whole-turn count to recover,
 * no search and no window on the advance.  The drift's continuity is not
 * assumed either: where the caller predicts the tone's excess from the
 * earlier frames, that prediction only CHOOSES among repetitions of the
 * fundamental's period that the pilots' own residuals barely tell apart -
 * it never admits or refuses a reading - so a step in the drift is still
 * reported on the frame it happens.
 *
 * <p>Nothing is carried between frames: one instance serves a whole collection
 * and the bins change only with the election.
 */
public final class PairAdvance {

    /** One whole turn of phase, radians. */
    private static final double TURN_RAD = 2.0 * Math.PI;
    /** How many repetitions of the fundamental's own period either side of the
     *  capture's count a candidate advance may be looked for.  Each repetition
     *  fits the pilots' difference exactly as well as the truth does, and the
     *  count is what says which one the frame stands on - but a lost packet
     *  puts it out by more than one, so the neighbours have to be tried.  The
     *  first repetition neither residual can tell from the truth lies about
     *  sixty of them out. */
    private static final int SEARCH_PERIODS = 20;
    /** How much stream one decision is gathered over, in transform lengths.
     *  Two frames less than a window apart share most of their samples and
     *  repeat ONE reading; a decision needs independent ones, and how many
     *  frames that takes depends on the hop, which is not ours to choose. */
    private static final double DECISION_SPAN_TRANSFORMS = Math.sqrt(2);
    /** Frames that took no part in choosing a candidate which must agree with
     *  it before any reading is taken from it.  A candidate that wins the
     *  score is not yet trusted: without this the wrong one reaches the
     *  readout on about one decision in a hundred. */
    private static final int CONFIRM_FRAMES = 2;
    /** How far the fine reading may stand from the coarse one before a
     *  candidate is refused or a held correction dropped, in units of the
     *  reading noise the frame carries: the coarse reading holds both pilots'
     *  phase noise whole, which the caller measures on the frame's own
     *  floor.  5.8 sigma is the 0.4 degrees a bench pair standing 60 dB over
     *  its floor admits, and it follows the pilots' level from there - a
     *  fixed angle drops the right correction every few frames once the
     *  noise nears it, and admits the wrong repetition once it passes it. */
    private static final double ADMIT_SIGMAS = 5.8;
    /** The admission's ceiling, radians: a third of the nine degrees the
     *  nearest wrong repetition moves the fine reading, so a pair too weak
     *  for that bound keeps refusing rather than admitting the wrong turn. */
    private static final double ADMIT_CAP_RAD = Math.toRadians(3.0);
    /** The admission's floor, radians: the arithmetic's own rounding on a
     *  turn, so a frame that carries no noise at all still fits itself. */
    private static final double ADMIT_FLOOR_RAD = 1.0e-9;
    /** How much the admission widens for every candidate refused in a row,
     *  up to {@link #ADMIT_CAP_RAD}.  The admission is measured from the
     *  median miss of the SETTLED frames, and a glitch can move the pilots'
     *  standing miss by a degree in one event - more than the admission of a
     *  pair 60 dB over its floor.  With the admission fixed, the right
     *  candidate then misses the stale median on every frame, no frame
     *  settles, and the median that would absorb the move can never be
     *  learned: a deadlock.  Each refusal doubles the admission instead, so
     *  the right repetition is admitted within a few decisions - still well
     *  inside the nine degrees of the nearest wrong one - and the median
     *  relearns the new miss from the frames that then settle. */
    private static final double ADMIT_WIDEN_PER_REFUSAL = 2.0;
    /** How far back the miss's running median reaches, seconds of capture -
     *  or one window, whichever is longer: long against the miss's own
     *  swing, which has about the window's period, short against the walk
     *  of its offset over minutes. */
    private static final double MISS_MEDIAN_SEC = 15.0;
    /** The most settled frames the median is taken over: what a small
     *  window under a deep overlap can put into that span. */
    private static final int MISS_RING_FRAMES = 256;
    /** The largest offset the median may stand for, radians at the
     *  fundamental: an offset beyond it is no slow walk of the pilots but a
     *  disturbed collection, and the admission is measured from zero again. */
    private static final double MISS_MEDIAN_CAP_RAD = Math.toRadians(2.0);

    /** The two pilots' bins, {@code p1 - p2} the fundamental's own bin by the
     *  election. */
    private final int p1;
    private final int p2;
    /** The transform size, samples: a line at bin {@code k} repeats every
     *  {@code fftSize / k} samples. */
    private final int fftSize;
    /** The capture's sample rate, hertz: what turns the median's span in
     *  seconds into samples of the advance. */
    private final int sampleRate;
    /** How far the capture's own count of the advance was last found to stand
     *  from the advance the pilots measure, samples.  The losses the count
     *  never sees ADD UP over a collection - a score of samples at each
     *  re-sync, and a collection holds many - so the count is corrected by
     *  what the last frame found before the next frame is placed by it.
     *  Without that, the gap to be bridged is the whole collection's losses
     *  and it crosses the half period this rests on; with it, only one
     *  frame's own loss has to stay inside. */
    private double carriedSamples;
    /** The correction in force, samples, or {@link Double#NaN} while there is
     *  none: the frames since the last one was refused are being gathered for
     *  a new decision. */
    private double heldSamples = Double.NaN;
    /** A correction that has won the choice but is not yet trusted, and how
     *  many more frames must agree with it. */
    private double candidateSamples = Double.NaN;
    private int    confirmLeft;
    /** The frames gathered for the next decision: their advance as the capture
     *  counts it, the two pilots' changes on each, the tone's change and the
     *  excess predicted for the frame (NaN where there was none). */
    private final List<double[]> gathered = new ArrayList<>();
    /** The last settled frames' miss - the fine readings' mean against the
     *  coarse reading, radians at the fundamental - with the advance each
     *  was read at, and the median of those within {@link #MISS_MEDIAN_SEC}
     *  of the newest.  The pilots' own phases walk slowly against their
     *  difference, tenths of a degree at the fundamental on the bench, and
     *  that walk says nothing about the turn count, which a wrong repetition
     *  moves by nine degrees; the admission is therefore measured from the
     *  median, not from zero, or the walk alone would drop a right
     *  correction.  Zero until settled frames have shown one, after a reset,
     *  and where it would exceed {@link #MISS_MEDIAN_CAP_RAD}. */
    private final double[] missRing = new double[MISS_RING_FRAMES];
    private final double[] missAt   = new double[MISS_RING_FRAMES];
    private int    missCount;
    private int    missNext;
    private double missMedianRad;
    /** The last drift a settled frame read, radians, held while frames are
     *  being gathered - it moves by thousandths of a degree per frame, so
     *  holding it is nearer the truth than the coarse reading alone.  NaN
     *  before the collection's first settled frame. */
    private double lastExcessRad  = Double.NaN;
    private double lastExcess2Rad = Double.NaN;
    /** Whether the next frame may propose a candidate straight from the
     *  capture's count instead of gathering for one.  True at a collection's
     *  start - the frame the advance is counted from is its own zero, so
     *  nothing has been lost uncounted yet - and again whenever a correction
     *  stops fitting: what was lost there is one event's worth, and while it
     *  stays inside half a fundamental period the nearest value to the
     *  correction carried so far IS the new one.  Cleared once proposed, so a
     *  candidate the confirming frames refuse falls back to the full gather. */
    private boolean seedPending = true;
    /** Candidates refused since the last one was accepted - what widens the
     *  admission, see {@link #ADMIT_WIDEN_PER_REFUSAL}. */
    private int refusedCandidates;
    /** What the last frame read, for the trace - settled or not: the coarse
     *  reading (the pilots' difference, radians at the fundamental) and each
     *  pilot's fine reading under the correction in force, the candidate
     *  under test while one is, or the correction carried in while none is.
     *  Their miss against the coarse reading is what no candidate can repair
     *  when the pilots' own phases have moved against their difference.  NaN
     *  before the first frame. */
    @Getter
    private double traceCoarseRad = Double.NaN;
    @Getter
    private double traceFine1Rad  = Double.NaN;
    @Getter
    private double traceFine2Rad  = Double.NaN;
    /** The admission the last frame was judged by, radians at the
     *  fundamental: how far a fine reading may miss the coarse one, from the
     *  frame's own reading noise.  NaN before the first frame. */
    @Getter
    private double traceAdmitRad  = Double.NaN;

    public PairAdvance(int p1, int p2, int fftSize, int sampleRate) {
        this.p1         = p1;
        this.p2         = p2;
        this.fftSize    = fftSize;
        this.sampleRate = sampleRate;
    }

    /** Forgets everything the collection had settled: a new one counts its
     *  advance from its own first frame, so no correction, candidate or
     *  reading of an earlier one is its. */
    public void reset() {
        carriedSamples   = 0.0;
        heldSamples      = Double.NaN;
        candidateSamples = Double.NaN;
        confirmLeft      = 0;
        lastExcessRad    = Double.NaN;
        lastExcess2Rad   = Double.NaN;
        seedPending      = true;
        refusedCandidates = 0;
        traceCoarseRad   = Double.NaN;
        traceFine1Rad    = Double.NaN;
        traceFine2Rad    = Double.NaN;
        traceAdmitRad    = Double.NaN;
        gathered.clear();
        missCount        = 0;
        missNext         = 0;
        missMedianRad    = 0.0;
    }

    /**
     * What the lines make of one frame: the whole turns each pilot has made
     * since the collection's first frame, how far the two pilots' fine
     * readings stand apart at the fundamental's frequency ({@code rigidRad} -
     * their own reading noise, a few thousandths of a degree), how far the
     * coarse reading stands from them ({@code elasticRad} - the margin the
     * branch was picked with, against a step of about 18 degrees), and each
     * tone's excess since that first frame (radians; the second
     * {@link Double#NaN} where the tone is off the harmonic ladder).
     *
     * <p>{@code settled} is what the accumulator must read: false while no
     * correction is in force - the collection's opening frames, and the
     * frames after a loss until a new correction has been decided and
     * confirmed.  The excess of such a frame is the last settled one held,
     * good for the trace and the readout and NOT good enough to average on,
     * because it stands still while the drift does not.  The residuals are
     * {@link Double#NaN} there, and so is everything where the election did
     * not hold the spacing.
     *
     * <p>{@code advanceSamples} is the advance the pair read on this frame -
     * the two pilots' FINE reading, whole turns from the correction in force,
     * expressed in samples - measured, not counted, with the lines' reading
     * noise divided by the ratio of their bins to the fundamental's.  It is
     * what the accumulator turns a frame by: every bin k by
     * {@code 2 pi k advance / N}, with each tone's own excess on top of its
     * lobe; the excess is measured against this same reading, so the
     * fundamental's alignment closes exactly and the harmonics turn by the
     * sub-sample time base instead of the coarse decision's whole samples.
     * {@link Double#NaN} while nothing is settled.
     */
    public record Result(long m1, long m2, double rigidRad, double elasticRad,
                         double excessChangeRad, double excessChange2Rad,
                         boolean settled, double advanceSamples) {}

    /**
     * The fundamental's drift since the collection's FIRST read frame, from
     * {@code d1} and {@code d2}, the two pilots' measured phase changes since
     * that frame (radians, each modulo one turn), and the tones' measured
     * changes since it: {@code toneChangeRad} at bin {@code toneBin}, and in
     * dual tone {@code tone2ChangeRad} at {@code tone2Bin} ({@link Double#NaN}
     * and 0 in single tone).
     *
     * <p>The pilots' difference is the advance's rotation at the fundamental,
     * so the drift is what is left of the tone's change after it is taken out.
     * A second tone is carried only where its bin is a whole multiple of the
     * first's - the difference is known modulo one turn, and only a whole
     * multiple keeps the turns it dropped whole - so a dual pair's second tone
     * comes back {@link Double#NaN}.
     *
     * <p>{@code readingSigmaRad} is the noise of the coarse reading on this
     * frame, radians - both pilots' phase noise together, measured by the
     * caller on the frame's own floor - and sets how far a candidate may miss
     * the coarse reading and still be admitted.
     *
     * <p>{@code nominalAdvance} is the capture's own count of the advance
     * since the first frame, samples: what every correction is relative to.
     *
     * <p>{@code expectedChangeRad} is the tone's excess the trend over the
     * earlier frames predicts for this frame, radians, or {@link Double#NaN}
     * where the caller has none it trusts.  It chooses among the repetitions
     * of the fundamental's period that the pilots' own residuals barely tell
     * apart: at a bench pair's ratio the repetition two periods out moves
     * the fine reading by only 0.6 degrees, which a miss near the admission
     * cannot be told from, while it moves the tone's excess by that same 0.6
     * degrees against a prediction good to a few hundredths.  It never
     * admits or refuses a reading.  {@code expectedChange2Rad} is not read.
     */
    public Result resolve(double d1, double d2, double nominalAdvance,
                          double toneChangeRad, int toneBin, double tone2ChangeRad, int tone2Bin,
                          double expectedChangeRad, double expectedChange2Rad,
                          double readingSigmaRad) {
        if (p1 - p2 != toneBin) {
            // The construction rests on the spacing; without it the difference
            // is a rotation at another bin and nothing here holds.
            return new Result(0, 0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, false, Double.NaN);
        }
        // COARSE.  The advance's rotation AT THE FUNDAMENTAL, straight from
        // the pair: the spacing is the fundamental's bin, so the difference
        // needs no scaling and the whole turns inside it wrap away.  It is
        // immune to a gap of any size - and it carries both pilots' reading
        // noise at full size, which is why it is not the answer by itself.
        double coarse = Math.IEEEremainder(d1 - d2, TURN_RAD);
        // What a candidate may miss the coarse reading by on THIS frame: the
        // frame's own reading noise times the sigmas, between floor and cap.
        double admit = Math.min(ADMIT_CAP_RAD,
                Math.max(ADMIT_FLOOR_RAD, ADMIT_SIGMAS * readingSigmaRad)
                        * Math.pow(ADMIT_WIDEN_PER_REFUSAL, refusedCandidates));
        // The frame's readings for the trace, whatever the frame then decides:
        // under the correction in force, else the candidate under test, else
        // the correction carried in.
        double traceSamples = nominalAdvance + (!Double.isNaN(heldSamples) ? heldSamples
                : !Double.isNaN(candidateSamples) ? candidateSamples : carriedSamples);
        traceCoarseRad = coarse;
        traceAdmitRad  = admit;
        traceFine1Rad  = fine(d1, p1, traceSamples, toneBin);
        traceFine2Rad  = fine(d2, p2, traceSamples, toneBin);

        // A candidate on probation must be agreed with by frames that took no
        // part in choosing it.  Until it is, this frame yields no reading of
        // its own - which is what keeps a wrong candidate out of the readout
        // altogether instead of merely rare in it.
        if (!Double.isNaN(candidateSamples)) {
            if (fits(d1, d2, nominalAdvance + candidateSamples, toneBin, coarse, admit)) {
                if (--confirmLeft <= 0) {
                    heldSamples       = candidateSamples;
                    carriedSamples    = candidateSamples;
                    candidateSamples  = Double.NaN;
                    refusedCandidates = 0;
                }
            } else {
                candidateSamples = Double.NaN;
                refusedCandidates++;
                gathered.clear();
            }
            if (!Double.isNaN(candidateSamples)) {
                return held(coarse, toneChangeRad, tone2ChangeRad, tone2Bin, toneBin);
            }
        }
        // The correction in force still has to fit: when it stops fitting,
        // samples have been lost and a new one must be decided.
        if (!Double.isNaN(heldSamples)
                && !fits(d1, d2, nominalAdvance + heldSamples, toneBin, coarse, admit)) {
            heldSamples = Double.NaN;
            seedPending = true;     // one event's loss: the nearest value is the answer
            gathered.clear();
        }
        if (Double.isNaN(heldSamples)) {
            if (seedPending) {
                // No gathering where the answer is already known to within one
                // repetition: the coarse reading fixes the correction modulo a
                // fundamental period, and the right repetition is the one
                // nearest what was carried in - zero at a collection's start,
                // the last correction after a loss - as long as what was lost
                // stays inside half that period (191 samples here, against the
                // tens a glitch costs).  It is confirmed like any other
                // candidate, so the cost is two frames instead of a decision
                // span of two transforms, about thirty frames at this overlap;
                // and a loss too large for it fails confirmation and the
                // frames that follow decide it the long way.
                seedPending = false;
                double period = fftSize / (double) toneBin;
                candidateSamples = carriedSamples + Math.IEEEremainder(
                        coarse / TURN_RAD * period - (nominalAdvance + carriedSamples), period);
                // With a prediction of the tone's excess the repetition is
                // chosen by it instead: a loss past half a period puts the
                // nearest value one or more periods off, and the excess tells
                // every repetition within reach apart where the count cannot.
                if (!Double.isNaN(expectedChangeRad)) {
                    candidateSamples = nearestToPrediction(candidateSamples, period, nominalAdvance,
                            d1, d2, toneChangeRad, toneBin, expectedChangeRad);
                }
                confirmLeft = CONFIRM_FRAMES;
                return held(coarse, toneChangeRad, tone2ChangeRad, tone2Bin, toneBin);
            }
            gathered.add(new double[] { nominalAdvance, d1, d2, toneChangeRad, expectedChangeRad });
            double span = gathered.get(gathered.size() - 1)[0] - gathered.get(0)[0];
            if (gathered.size() < 2 || span < DECISION_SPAN_TRANSFORMS * fftSize) {
                return held(coarse, toneChangeRad, tone2ChangeRad, tone2Bin, toneBin);
            }
            candidateSamples = decide(toneBin);
            confirmLeft      = CONFIRM_FRAMES;
            gathered.clear();
            return held(coarse, toneChangeRad, tone2ChangeRad, tone2Bin, toneBin);
        }
        // FINE.  Each pilot's own phase, its whole turns taken from the
        // correction in force, brought DOWN to the fundamental - which divides
        // the line's reading noise by the ratio of the bins, 19.5 for the
        // upper line of a bench pair.  Both lines are read, so their errors
        // average.
        double snapped = nominalAdvance + heldSamples;
        double fine1  = fine(d1, p1, snapped, toneBin);
        double fine2  = fine(d2, p2, snapped, toneBin);
        double atTone = 0.5 * (fine1 + fine2);
        // The fine reading is the fundamental's phase advance with its whole
        // turns; at bin toneBin the fundamental turns 2 pi toneBin / N per
        // sample, which puts the advance back into samples.
        double fineAdvance = atTone * fftSize / (TURN_RAD * toneBin);
        double excess = Math.IEEEremainder(toneChangeRad - atTone, TURN_RAD);
        lastExcessRad = excess;
        // A second tone can be carried only where its bin is a WHOLE multiple
        // of the first's.  The difference above is known modulo one turn, and
        // scaling it brings the turns it dropped back scaled with it: at a
        // whole multiple they are whole turns again and wrap away, at any
        // other ratio they do not, and the figure would be meaningless.  The
        // harmonic ladder is safe by construction; a dual pair's second tone
        // is not, and states so.
        boolean carried = !Double.isNaN(tone2ChangeRad) && tone2Bin > 0
                && tone2Bin % toneBin == 0;
        double excess2 = carried
                ? Math.IEEEremainder(tone2ChangeRad - atTone * (tone2Bin / toneBin), TURN_RAD)
                : Double.NaN;
        lastExcess2Rad = excess2;
        recordMiss(nominalAdvance, Math.IEEEremainder(atTone - coarse, TURN_RAD));
        return new Result(turns(d1, p1, snapped), turns(d2, p2, snapped),
                fine1 - fine2, Math.IEEEremainder(coarse - atTone, TURN_RAD),
                excess, excess2, true, fineAdvance);
    }

    /** Keeps a settled frame's miss, read at an advance of {@code atSamples},
     *  and renews the running median the admission is measured from: the
     *  median of the kept misses within {@link #MISS_MEDIAN_SEC} - or one
     *  window - of this one.
     *
     * @param atSamples the advance the miss was read at, samples
     * @param missRad   the miss, radians */
    private void recordMiss(double atSamples, double missRad) {
        missRing[missNext] = missRad;
        missAt[missNext]   = atSamples;
        missNext = (missNext + 1) % missRing.length;
        if (missCount < missRing.length) missCount++;
        double   span   = Math.max(MISS_MEDIAN_SEC * sampleRate, fftSize);
        double[] recent = new double[missCount];
        int      n      = 0;
        for (int i = 0; i < missCount; i++) {
            if (atSamples - missAt[i] <= span) recent[n++] = missRing[i];
        }
        // Upper middle selected first: every entry below its index is then no
        // larger, so the lower middle is the largest of those.
        double upper  = FftDsp.selectKth(recent, n, n / 2);
        double median = n % 2 == 1 ? upper : 0.5 * (FftDsp.selectKth(recent, n / 2, n / 2 - 1) + upper);
        missMedianRad = Math.abs(median) < MISS_MEDIAN_CAP_RAD ? median : 0.0;
    }

    /**
     * What a frame reads while no correction is in force: the drift the last
     * settled frame found, which moves by thousandths of a degree per frame
     * and is therefore nearer the truth than anything this frame can settle on
     * its own.  Before the collection's first settled frame there is nothing
     * to hold, and the coarse reading serves - unbiased, and noisy by the two
     * pilots' own reading noise.
     */
    private Result held(double coarse, double toneChangeRad,
                        double tone2ChangeRad, int tone2Bin, int toneBin) {
        double excess = Double.isNaN(lastExcessRad)
                ? Math.IEEEremainder(toneChangeRad - coarse, TURN_RAD)
                : lastExcessRad;
        double excess2 = Double.isNaN(lastExcess2Rad)
                ? (!Double.isNaN(tone2ChangeRad) && tone2Bin > 0 && tone2Bin % toneBin == 0
                        ? Math.IEEEremainder(tone2ChangeRad - coarse * (tone2Bin / toneBin),
                                             TURN_RAD)
                        : Double.NaN)
                : lastExcess2Rad;
        return new Result(0, 0, Double.NaN, Double.NaN, excess, excess2, false, Double.NaN);
    }

    /** Whether a correction still explains this frame: its fine reading must
     *  stand within {@code admitRad} of the pilots' own difference, measured
     *  from the slow offset the settled frames have shown
     *  ({@link #missMedianRad}) - which no wrong repetition does: the
     *  nearest moves it nine degrees. */
    private boolean fits(double d1, double d2, double samples, int toneBin, double coarse,
                         double admitRad) {
        double atTone = 0.5 * (fine(d1, p1, samples, toneBin) + fine(d2, p2, samples, toneBin));
        return Math.abs(Math.IEEEremainder(atTone - coarse - missMedianRad, TURN_RAD)) <= admitRad;
    }

    /**
     * The correction the gathered frames agree on best, samples.
     *
     * <p>Every repetition of the fundamental's period within reach of the
     * capture's count is tried.  Each is scored by how improbable its two
     * residuals are together - the two pilots against each other, and their
     * averaged reading against their own difference, measured from the slow
     * offset the settled frames have shown ({@link #missMedianRad}) - in
     * units of the noise each carries.  Only the RATIO of those two noises
     * matters to the ranking, and the bins fix it: the difference carries
     * one pilot's noise whole, the averaged reading carries it divided by
     * the ratio of the bins.
     *
     * <p>Where the frames carry a prediction of the tone's excess, a third
     * residual joins the score: how far each repetition's excess stands from
     * it.  The repetition two periods out moves the fine reading by only 0.6
     * degrees at a bench pair's ratio - the size of a miss the admission
     * still allows, so the first two residuals cannot tell it from the truth
     * - while it moves the excess by that same 0.6 degrees against a
     * prediction good to a few hundredths.  In the same units and at the
     * same weight, the term outweighs any miss advantage that repetition can
     * have.
     *
     * <p>Nothing is thresholded.  A threshold throws the TRUE correction away
     * whenever one of its frames sits a few sigma out, which over a
     * collection's worth of decisions is a certainty; a score never does.
     */
    private double decide(int toneBin) {
        double period = fftSize / (double) toneBin;
        double[] first = gathered.get(0);
        double coarse0 = Math.IEEEremainder(first[1] - first[2], TURN_RAD);
        double anchor  = first[0] + carriedSamples;
        double base    = anchor + Math.IEEEremainder(coarse0 / TURN_RAD * period - anchor, period);
        // how much louder the difference's noise is than the averaged
        // reading's, from the bins alone
        double ratio   = Math.sqrt(2.0)
                / Math.hypot(toneBin / (double) p1, toneBin / (double) p2);
        double bestScore = Double.POSITIVE_INFINITY;
        double best      = base - first[0];
        for (int step = -SEARCH_PERIODS; step <= SEARCH_PERIODS; step++) {
            double corr = base + step * period - first[0];
            double sumRigid = 0.0;
            double sumDev   = 0.0;
            double sumCont  = 0.0;
            int    nCont    = 0;
            for (double[] g : gathered) {
                double f1 = fine(g[1], p1, g[0] + corr, toneBin);
                double f2 = fine(g[2], p2, g[0] + corr, toneBin);
                sumRigid += f1 - f2;
                sumDev   += Math.IEEEremainder(0.5 * (f1 + f2)
                        - Math.IEEEremainder(g[1] - g[2], TURN_RAD) - missMedianRad, TURN_RAD);
                if (!Double.isNaN(g[4])) {
                    sumCont += excessDistance(g[1], g[2], g[0] + corr, toneBin, g[3], g[4]);
                    nCont++;
                }
            }
            double n      = gathered.size();
            double rigid  = sumRigid / n;
            double dev    = sumDev / n;
            double cont   = nCont > 0 ? sumCont / nCont : 0.0;
            double score  = rigid * rigid * ratio * ratio + dev * dev + cont * cont;
            if (score < bestScore) {
                bestScore = score;
                best      = corr;
            }
        }
        return best;
    }

    /** The repetition of {@code period} about {@code candidate}, within
     *  {@link #SEARCH_PERIODS} either way, whose excess on this frame stands
     *  nearest the predicted one, samples. */
    private double nearestToPrediction(double candidate, double period, double nominalAdvance,
                                       double d1, double d2, double toneChangeRad, int toneBin,
                                       double expectedRad) {
        double best         = candidate;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int step = -SEARCH_PERIODS; step <= SEARCH_PERIODS; step++) {
            double corr     = candidate + step * period;
            double distance = Math.abs(excessDistance(d1, d2, nominalAdvance + corr, toneBin,
                                                      toneChangeRad, expectedRad));
            if (distance < bestDistance) {
                bestDistance = distance;
                best         = corr;
            }
        }
        return best;
    }

    /** How far the tone's excess under a correction of {@code samples} stands
     *  from the predicted one, radians wrapped to one turn. */
    private double excessDistance(double d1, double d2, double samples, int toneBin,
                                  double toneChangeRad, double expectedRad) {
        double atTone = 0.5 * (fine(d1, p1, samples, toneBin) + fine(d2, p2, samples, toneBin));
        return Math.IEEEremainder(toneChangeRad - atTone - expectedRad, TURN_RAD);
    }

    /** The whole turns a pilot at bin {@code p} has made over an advance of
     *  {@code samples}, its measured phase holding the rest of the turn.  The
     *  count is the elapsed time times the pilot's frequency, so it passes
     *  2^31 after some 30 hours of one collection at a 20 kHz pilot - and a
     *  narrowing conversion of a floating-point value CLAMPS in Java, which
     *  would freeze the count at the maximum instead of showing itself as a
     *  jump.  Long is beyond reach: 2^63 turns is millions of years. */
    private long turns(double d, int p, double samples) {
        return (long) Math.rint(samples * p / fftSize - d / TURN_RAD);
    }

    /** One pilot's phase at the FUNDAMENTAL's frequency, radians: its measured
     *  phase plus those whole turns, scaled by the bins - which divides the
     *  line's own reading noise by that same ratio. */
    private double fine(double d, int p, double samples, int toneBin) {
        return TURN_RAD * toneBin * (d / TURN_RAD + turns(d, p, samples)) / p;
    }

}
