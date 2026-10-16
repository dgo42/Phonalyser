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

import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.commons.lang3.reflect.MethodUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.FundamentalPhaseAlignment;
import org.edgo.audio.measure.enums.WindowType;
import org.edgo.audio.measure.fft.FftAnalyzer;
import org.edgo.audio.measure.fft.FftResult;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pilot-tone phase alignment through the REAL analyser.
 *
 * <p>Vocabulary.  An <b>analysis cycle</b> is one pass of the analyser:
 * the window slides by a hop of fresh samples and the whole window is
 * analysed as two overlapping frames.  The <b>pilots</b> are two
 * generated tones on whole bins far above the protected harmonics whose
 * phases the alignment reads on every analysis cycle; between two frames
 * each fixes the capture's <b>advance</b> - how far the stream moved, in
 * samples - only modulo its own period, and the two together
 * settle it.  The <b>excess</b> is the rotation the alignment charges to the
 * fundamental's bin alone, in radians, which the accumulator then applies to
 * that bin and to nothing else: the fundamental's own turn between two frames
 * less the turn that resolved advance explains at its bin, summed from the
 * collection's first read frame.  The <b>deviation</b> below is the pilot trace's third
 * value: the fundamental's drift as the REFERENCE channel reads it, less the
 * excess, both from the same smoothed changes.  The reference channel
 * carries the fundamental clear of the notch, so its reading is the notch's
 * drift alone, and the deviation is what the pilot's reading differs from
 * it by: zero while the pilot is the clean time-base reference the method
 * takes it for.
 *
 * <p>Why this class exists beside the accumulator harness: that one
 * synthesizes the frame by hand and therefore proves the worker against its
 * own assumptions.  Here every number the worker reads comes from the real
 * analyser over real samples - the analysis cycle's spectrum from
 * {@link FftAnalyzer#analyze}, the phases from its single-frame bin read on
 * the same window - a stream of two double-precision sines, windowed,
 * hopped and transformed the way the analysis-cycle loop does it, so a
 * disagreement between the analysed side and the single-bin side shows up
 * instead of being assumed away.
 *
 * <p>The fundamental sits on bin 85 and the two pilots on bins 1317 and 1601,
 * the coprime pair an election yields.  All three lines are displaced by ONE
 * relative clock error: a converter pair running at slightly different rates
 * holds every captured line off its bin centre by that error times its own
 * bin.  Both pilots then report an advance stretched by exactly that error and
 * the fundamental owes its whole turn to that same stretched advance, so the
 * error leaves no excess behind - and reading a line a third of a bin off its
 * bin costs a phase offset that is the same in every frame and cancels in the
 * difference between two.
 */
class FftPilotToneAnalyserTest {

    private static final int        SAMPLE_RATE = 48_000;
    private static final int        FFT_SIZE    = 4096;
    private static final WindowType WINDOW      = WindowType.BH7;
    /** Harmonics the analysis evaluates, as the analysis-cycle loop
     *  states them. */
    private static final int        HARMONICS   = 8;
    private static final int        CYCLES      = 20;

    /** The fundamental's bin: the frequency lock's target, so the line sits on
     *  it up to the clock error below. */
    private static final int    FUND_BIN  = 85;
    /** The elected pair's bins, both whole bins of the transform and spaced
     *  ONE FUNDAMENTAL BIN apart - 1317 and 1317 - 85 - which is what makes
     *  their phase difference the fundamental's own share of the advance.
     *  Both stand clear of the protected ladder (bin 85 to its ninth harmonic
     *  at 765), and 85 bins apart neither sits in the other's floor
     *  neighbourhood. */
    private static final int    PILOT1_BIN = 1317;
    private static final int    PILOT2_BIN = PILOT1_BIN - FUND_BIN;
    /** The relative error left between the two converter clocks by the
     *  fundamental's frequency lock: it trims the generator until the
     *  fundamental sits on its bin in the capture's clock, and the pilots,
     *  generated on the same clock, ride the same trim, so all three lines
     *  stand off their bins by this one ratio - the lock's own residual of
     *  0.02 ppm, 0.026 bins at pilot 1. */
    private static final double CLOCK_ERROR = 0.02e-6;
    /** Where the three lines actually sit, in bins: one clock error displaces
     *  every one of them in proportion to its own bin. */
    private static final double FUND_POS   = FUND_BIN   * (1.0 + CLOCK_ERROR);
    private static final double PILOT1_POS = PILOT1_BIN * (1.0 + CLOCK_ERROR);
    private static final double PILOT2_POS = PILOT2_BIN * (1.0 + CLOCK_ERROR);
    /** The fundamental's frequency as the generator states it - the hint the
     *  analysis-cycle loop passes, so the fundamental is found by its lock
     *  target and never by a peak search, which would take the pilot for it. */
    private static final double FUND_HINT_HZ = FUND_BIN * (double) SAMPLE_RATE / FFT_SIZE;

    private static final double FUND_AMP    = 0.1;      // -20 dBFS
    private static final double PILOT_AMP   = 0.01;     // -40 dBFS
    private static final double FUND_PHASE   = 0.7;
    private static final double PILOT_PHASE  = -1.3;
    private static final double PILOT2_PHASE = 2.1;

    /** The time base's slip per analysis cycle, in samples: what a converter
     *  pair at slightly different rates adds to the window's position.  It
     *  turns the pilot by 2 pi f2 dt and the fundamental by 2 pi f0 dt, and
     *  the pilot method exists to cancel it. */
    private static final double SLIP_SAMPLES = 0.05;
    /** The notch's own phase step per analysis cycle, radians: a drift that
     *  turns the fundamental ALONE, which is what the excess must report. */
    private static final double NOTCH_STEP_RAD = 0.002;

    /** The fixed hop: 50 percent overlap. */
    private static final int    FIXED_HOP = FFT_SIZE / 2;
    /** The random mode's hop lattice at this transform size, from the mode's
     *  own range - the analysis-cycle loop draws one of these every cycle. */
    private static final int    RAND_HOP_MIN  = FftOverlap.RAND.minHopSamples(FFT_SIZE);
    private static final int    RAND_HOP_MAX  = FftOverlap.RAND.maxHopSamples(FFT_SIZE);
    private static final int    RAND_HOP_STEP =
            Math.max(1, (RAND_HOP_MAX - RAND_HOP_MIN) / FftOverlap.RAND_HOP_STEPS);
    private static final long   SEED = 20_260_921L;

    /** Degrees the cases allow around an exact answer. */
    private static final double TOL_DEG = 0.01;

    private static final Logger LOG = LogManager.getLogger("FftPilotToneAnalyser");

    /** What one run produced: the excess fed to the accumulator at every
     *  analysis cycle, the deviation, the trace's third value, at every cycle
     *  (radians), the advance handed to the accumulator (samples) and whether
     *  the cycle went unsettled. */
    private record Run(double[] excessRad, double[] deviationRad,
                       double[] advanceSamples, boolean[] unsettled) {}

    /**
     * A drift of the TIME BASE alone, at a fixed 50 percent overlap: every
     * line turns, each in proportion to its own bin, the pair resolves the
     * advance that accounts for all of it, and the excess must stay at zero.
     * Nothing here is written into a bin by hand: the phases come out of the
     * analyser.
     */
    @Test
    void aSharedTimeBaseDriftCancelsThroughTheRealAnalyser() throws Exception {
        Run run = drive(false, false, "shared drift, fixed hop");
        assertZeroExcess(run, "a drift both lines share is not the notch's and must cancel");
        assertDeviationZero(run, "shared drift, fixed hop");
    }

    /**
     * A drift that turns the FUNDAMENTAL alone - the notch's centre moving
     * with temperature, where its phase response is steep - on top of the
     * shared time-base drift: the excess must report that rotation and
     * nothing else, averaged over the worker's window.
     */
    @Test
    void aNotchOnlyDriftBecomesTheExcessThroughTheRealAnalyser() throws Exception {
        Run run = drive(true, false, "notch drift, fixed hop");
        assertNotchExcess(run, "the fundamental's own rotation since the first analysis cycle IS the excess");
        assertDeviationZero(run, "notch drift, fixed hop");
    }

    /** The shared drift again with a hop drawn per analysis cycle, as the
     *  random overlap draws it: no per-cycle advance the recipe removes can be
     *  a nominal one. */
    @Test
    void aSharedTimeBaseDriftCancelsWithADrawnHop() throws Exception {
        Run run = drive(false, true, "shared drift, drawn hop");
        assertZeroExcess(run, "a drift both lines share must cancel at any drawn hop");
        assertDeviationZero(run, "shared drift, drawn hop");
    }

    /** The notch drift again with a hop drawn per analysis cycle. */
    @Test
    void aNotchOnlyDriftBecomesTheExcessWithADrawnHop() throws Exception {
        Run run = drive(true, true, "notch drift, drawn hop");
        assertNotchExcess(run, "the notch's rotation must reach the excess at any drawn hop");
        assertDeviationZero(run, "notch drift, drawn hop");
    }

    /**
     * The pilot re-zero under the notch drift: armed before one analysis
     * cycle, it makes that cycle's totals the carries and its readings the new
     * zero.  The excess handed on must continue the notch's rotation without
     * a step on ANY cycle - settled or not - and once the pair has confirmed
     * its seed candidate (two cycles), the advance must continue the time
     * base's slip from the collection's first cycle, not from the new zero:
     * that continuity is what keeps the accumulator's frames valid.
     */
    @Test
    void aPilotRezeroCarriesExcessAndAdvance() throws Exception {
        int retrackAt = 8;
        Run run = drive(true, false, retrackAt, "notch drift, re-zero at analysis cycle " + retrackAt);
        assertNotchExcess(run, "the excess must continue across the re-zero");
        // Analysis cycles 1 and 2 are the collection's OWN seed and confirm
        // cycles; from there to the re-zero cycle the pair stands settled.
        for (int t = 3; t <= retrackAt; t++) {
            assertFalse(run.unsettled()[t], "settled before and on the re-zero analysis cycle, cycle " + t);
        }
        assertTrue(run.unsettled()[retrackAt + 1] && run.unsettled()[retrackAt + 2],
                "the reset pair spends its seed and confirm analysis cycles unsettled");
        for (int t = retrackAt + 3; t < CYCLES; t++) {
            assertFalse(run.unsettled()[t], "settled again after the confirm analysis cycles, cycle " + t);
            double expected = (double) FIXED_HOP * t + SLIP_SAMPLES * t;
            assertEquals(expected, run.advanceSamples()[t], 0.05,
                    "the advance must continue from the collection's first analysis cycle, cycle " + t);
        }
    }

    // ── Assertions ─────────────────────────────────────────────────────────

    private void assertZeroExcess(Run run, String why) {
        for (int t = 0; t < run.excessRad().length; t++) {
            assertEquals(0.0, Math.toDegrees(run.excessRad()[t]), TOL_DEG, why + ", analysis cycle " + t);
        }
    }

    /** The excess the worker applies at analysis cycle {@code t} under the
     *  notch drift: the notch's rotation since the first cycle, which the
     *  per-frame changes sum to exactly - the pilot path
     *  holds the sum itself. */
    private double expectedNotchExcessRad(int t) {
        return NOTCH_STEP_RAD * t;
    }

    private void assertNotchExcess(Run run, String why) {
        for (int t = 0; t < run.excessRad().length; t++) {
            assertEquals(Math.toDegrees(expectedNotchExcessRad(t)),
                    Math.toDegrees(run.excessRad()[t]), TOL_DEG, why + ", analysis cycle " + t);
        }
    }

    /** The pilot's reading of the drift must agree with the reference
     *  channel's: all three lines are read in the same frame, so under a
     *  shared time-base drift and under a notch drift alike the deviation
     *  is nothing at all.  A remainder is something rotating the
     *  pilot that the clean fundamental does not see, and the run's log
     *  carries the per-cycle geometry to say where. */
    private void assertDeviationZero(Run run, String label) {
        for (int t = 0; t < run.deviationRad().length; t++) {
            assertEquals(0.0, Math.toDegrees(run.deviationRad()[t]), TOL_DEG,
                    "the pilot's reading must agree with the reference channel's, "
                            + label + ", analysis cycle " + t
                            + " - see the per-cycle log lines for the frame geometry");
        }
    }

    // ── The run ────────────────────────────────────────────────────────────

    /**
     * One run of the fixture: {@code CYCLES} windows of a live stream, each
     * analysed the way the analysis-cycle loop analyses it, each handed to the
     * worker's own per-cycle phase excess in mode PILOT_TONE.  The worker's
     * private members are driven by reflection - a productive class is not
     * widened to let a test in - and the members are the ones the
     * analysis-cycle loop sets: the two elected pilots with the pair
     * arithmetic their election admitted, the window's absolute start, the
     * drawn hop, the analysed window, the reference mirror and its valid flag.
     */
    private Run drive(boolean notchDrift, boolean randomHop, String label) throws Exception {
        return drive(notchDrift, randomHop, -1, label);
    }

    /** The same run with the pilot re-zero armed before analysis cycle
     *  {@code retrackAtCycle} ({@code -1} for never): that cycle executes it
     *  at its end, the way the analysis-cycle loop arms it once the frequency
     *  lock is tracking. */
    private Run drive(boolean notchDrift, boolean randomHop, int retrackAtCycle,
                      String label) throws Exception {
        FftAnalyzer       analyzer = new FftAnalyzer();
        FftAnalyzerWorker worker   = new FftAnalyzerWorker();          // headless
        FieldUtils.writeField(worker, "pilotReader1", new PilotReader(PILOT1_BIN), true);
        FieldUtils.writeField(worker, "pilotReader2", new PilotReader(PILOT2_BIN), true);
        FieldUtils.writeField(worker, "pilotPair",
                new PairAdvance(PILOT1_BIN, PILOT2_BIN, FFT_SIZE, SAMPLE_RATE), true);
        FieldUtils.writeField(worker, "winValid", true, true);
        Random    rng       = new Random(SEED);
        long      pos       = 0;
        double[]  excess    = new double[CYCLES];
        double[]  deviation = new double[CYCLES];
        double[]  advances  = new double[CYCLES];
        boolean[] unsettled = new boolean[CYCLES];
        for (int t = 0; t < CYCLES; t++) {
            int      hop   = randomHop
                    ? RAND_HOP_MIN + RAND_HOP_STEP * rng.nextInt(FftOverlap.RAND_HOP_STEPS + 1)
                    : FIXED_HOP;
            double   slip  = SLIP_SAMPLES * t;
            double   notch = notchDrift ? NOTCH_STEP_RAD * t : 0.0;
            double[] win   = capture(pos, hop, slip, notch);
            // The mirror is the reference channel: the SAME span and the same
            // time base, with the fundamental taken ahead of the notch - which
            // is what that channel is for and the only thing that separates it
            // from the analysed one here.
            double[] ref   = capture(pos, hop, slip, 0.0);
            FieldUtils.writeField(worker, "analyzeBuf", win, true);
            FieldUtils.writeField(worker, "refWinBuf", ref, true);
            FieldUtils.writeField(worker, "pendingHopSamples", hop, true);
            FieldUtils.writeField(worker, "winAbsStart", pos, true);
            if (t == retrackAtCycle) {
                FieldUtils.writeField(worker, "pilotRetrackPending", true, true);
            }
            FftResult r = analyse(analyzer, win, hop);
            excess[t]    = cyclePhiF(worker, r);
            deviation[t] = pilotAgainstReference(worker, r);
            advances[t]  = (Double)  FieldUtils.readField(worker, "pilotAdvanceSamples", true);
            unsettled[t] = (Boolean) FieldUtils.readField(worker, "phaseAlignUnsettled", true);
            // The geometry and the measured positions, so a case that fails
            // says WHERE the two sides parted.
            LOG.info("{} analysis cycle {}: hop {}, frames {}, window start {}, pilot pos {} bins, "
                            + "fundamental pos {} bins, pilot vs ref chan {} deg, excess {} deg",
                    label, t, hop, r.frameCount, pos,
                    String.format("%.6f", r.pilot1HzRefined / r.freqResolution),
                    String.format("%.6f", r.fundamentalHzRefined / r.freqResolution),
                    String.format("%.6f", Math.toDegrees(deviation[t])),
                    String.format("%.6f", Math.toDegrees(excess[t])));
            pos += hop;
        }
        return new Run(excess, deviation, advances, unsettled);
    }

    /** One cycle's analysis with the analysis-cycle loop's own arguments: the
     *  spectrum only (the stats are re-derived from the accumulator), single
     *  tone, no second-tone hint, the pilot pinned on its bin, coherent
     *  averaging on, and the frame spacing stated - the drawn hop. */
    private FftResult analyse(FftAnalyzer analyzer, double[] win, int hop) {
        analyzer.setSpectrumOnly(true);
        analyzer.setMultiTone(false);
        analyzer.setSecondToneHintHz(Double.NaN);
        analyzer.setPilotBins(PILOT1_BIN, PILOT2_BIN);
        return analyzer.analyze(win, SAMPLE_RATE, FFT_SIZE, HARMONICS, HARMONICS,
                WINDOW, FftOverlap.PCT_50, 0.0, 0.0, true, Double.NaN, false, FUND_HINT_HZ,
                new FftResult(), hop);
    }

    /** The worker's own third trace value after this analysis cycle, radians -
     *  "pilot vs ref chan": the reference channel's reading of the
     *  fundamental's drift less the excess, both taken from the worker rather
     *  than recomputed, so the case tests the production formula and not a
     *  copy of it. */
    private double pilotAgainstReference(FftAnalyzerWorker worker, FftResult r) throws Exception {
        double againstReference = (Double) MethodUtils.invokeMethod(worker, true,
                "fundamentalAgainstReferenceRad");
        return (Double) MethodUtils.invokeMethod(worker, true,
                "pilotAgainstReferenceRad", againstReference);
    }

    /** The worker's own excess for this analysis cycle in mode PILOT_TONE - the
     *  private method the analysis-cycle loop calls, reached by reflection. */
    private double cyclePhiF(FftAnalyzerWorker worker, FftResult r) throws Exception {
        return (Double) MethodUtils.invokeMethod(worker, true, "cyclePhiF",
                FundamentalPhaseAlignment.PILOT_TONE, r, true);
    }

    // ── The capture ────────────────────────────────────────────────────────

    /**
     * The window an analysis cycle analyses: the three lines as
     * double-precision sines, sampled over {@code FFT_SIZE + hop} samples
     * starting at the absolute position {@code pos}, with the time base
     * slipped by {@code slip} samples and the fundamental turned by
     * {@code notchRad} on top.  A slip that grows from analysis cycle to
     * analysis cycle is what a converter pair running at slightly different
     * rates does; a rotation of the fundamental alone is what a
     * drifting notch does.  Each line's phase argument is reduced to one turn
     * before the sine, so the arithmetic stays exact however far the stream
     * has run.
     */
    private double[] capture(long pos, int hop, double slip, double notchRad) {
        double[] s = new double[FFT_SIZE + hop];
        for (int n = 0; n < s.length; n++) {
            double at = pos + n + slip;
            s[n] = FUND_AMP  * Math.sin(binPhase(FUND_POS,   at) + FUND_PHASE + notchRad)
                 + PILOT_AMP * Math.sin(binPhase(PILOT1_POS, at) + PILOT_PHASE)
                 + PILOT_AMP * Math.sin(binPhase(PILOT2_POS, at) + PILOT2_PHASE);
        }
        return s;
    }

    /** The phase of a line at {@code kappa} bins {@code samples} samples into
     *  the stream, reduced to one turn. */
    private double binPhase(double kappa, double samples) {
        return 2.0 * Math.PI * Math.IEEEremainder(kappa * samples, FFT_SIZE) / FFT_SIZE;
    }
}
