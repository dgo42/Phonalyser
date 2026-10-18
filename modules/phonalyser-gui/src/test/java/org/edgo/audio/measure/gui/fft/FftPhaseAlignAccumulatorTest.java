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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.edgo.audio.measure.bind.Property;
import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.FundamentalPhaseAlignment;
import org.edgo.audio.measure.enums.WindowType;
import org.edgo.audio.measure.fft.CoherentAccumulator;
import org.edgo.audio.measure.fft.FftAnalyzer;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.test.AbstractReflectiveTest;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fundamental phase alignment inside the real cross-cycle accumulator.
 *
 * <p>Vocabulary.  An <b>analysis cycle</b> is one pass of the analyser: a
 * window of {@code fftSize + hop} samples is analysed as two overlapping
 * frames and the result is added to the cross-cycle accumulator
 * ({@code accumulateIntoForeverBuffer}), which de-rotates it onto the first
 * cycle's time origin and sums.  <b>phiF</b> is the fundamental's phase
 * excess for an analysis cycle, in radians: how far the analysed
 * channel's fundamental phase stands from the drift-free time base an
 * alignment mode supplies.  Behind a notch filter the centre frequency drifts
 * thermally and the phase response at the centre is steep, so the fundamental -
 * and only it - moves; the accumulator's tracking loop has ONE phase input and
 * charges that move to the time base, where it reaches harmonic h multiplied
 * by h and vector-cancels the harmonics over a long average.
 *
 * <p>The capture is synthetic and needs no device: the analysed channel is an
 * on-bin fundamental whose phase carries a bounded random walk (the notch's
 * drift) plus a fixed harmonic ladder, and the reference channel is the same
 * fundamental WITHOUT the walk (the un-notched tap the reference-channel mode
 * feeds on).  phiF is computed exactly as the worker computes it -
 * {@link FftAnalyzer#binPhasor} on the reference window against the analysed
 * spectrum's raw fundamental bin - and written into the worker's own per-cycle
 * field, which is where the accumulator reads it.  Everything below that field
 * is production code, reached as it stands: the accumulator step, its rollback
 * and the field are private, and a productive class is not widened to let a
 * test in, so the harness goes through reflection.
 *
 * <p>Untagged on purpose: it is fast (a small FFT, a few hundred analysis
 * cycles) and runs in the normal build, unlike the exploratory drift harness
 * beside it.
 */
class FftPhaseAlignAccumulatorTest extends AbstractReflectiveTest {

    private static final int        SAMPLE_RATE = 48_000;
    private static final int        FFT_SIZE    = 4096;
    private static final WindowType WINDOW      = WindowType.BH7;
    private static final FftOverlap OVERLAP     = FftOverlap.PCT_75;
    /** The overlap's hop: the advance between the frames of successive
     *  analysis cycles - the worker's own geometry. */
    private static final int        HOP         = FFT_SIZE / 4;
    private static final int        WIN_LEN     = FFT_SIZE;
    private static final int        CYCLES      = 300;

    /** On-bin fundamental: an integer bin makes the accumulated level exactly
     *  the synthesized one, so a tolerance of 0.1 dB measures the coherent sum
     *  and nothing else. */
    private static final int        FUND_BIN      = 64;
    private static final int        HARMONICS     = 8;          // H2..H9
    private static final double     FUND_DBFS     = -20.0;
    /** Harmonics far above the analysis floor (the noise below reaches about
     *  -170 dBFS per bin), which is what makes a 0.1 dB tolerance reachable. */
    private static final double     HARMONIC_DBFS = -60.0;
    private static final double     HARMONIC_PHASE_RAD = 0.7;
    private static final double     NOISE_STD     = 1.0e-6;
    private static final long       SEED          = 20_260_915L;

    /** The notch's phase drift as the fundamental sees it: a random walk
     *  bounded at 30 degrees over the run, its drift RATE mean-reverting with
     *  a time constant of about a second - a thermal drift barely moves during
     *  one 5120-sample window, and a walk that did would be a modulation of
     *  the fundamental rather than a drift of it. */
    private static final double     WALK_MAX_RAD       = Math.toRadians(30.0);
    private static final double     WALK_TAU_SAMPLES   = 50_000.0;
    private static final double     WALK_RATE_STEP_RAD = 2.0e-8;
    /** The fixture must actually exercise the defect: a run whose walk stayed
     *  small would pass every assertion below for the wrong reason. */
    private static final double     WALK_MIN_SPAN_RAD  = Math.toRadians(15.0);

    /** Levels must land within this of the synthesized truth when the
     *  alignment holds the time base still. */
    private static final double     LEVEL_TOL_DB  = 0.1;
    /** The fundamental's own tolerance is wider: its phase is modulated
     *  WITHIN a window (the walk does not stand still for 5120 samples), which
     *  spreads a little of its power into its own sidebands - an effect the
     *  harmonics do not have. */
    private static final double     FUND_TOL_DB   = 0.2;
    /** Least loss the unaligned run must show at H9 - the defect the mode
     *  exists to remove. */
    private static final double     H9_MIN_LOSS_DB = 3.0;
    /** A constant excess is a constant rotation of the fundamental's region and
     *  a constant offset of the loop's phase input: both cancel, so the levels
     *  must be the ones a zero excess gives, to floating-point noise. */
    private static final double     CONST_PHIF_RAD  = 0.7;
    private static final double     IDENTICAL_TOL_DB = 1.0e-6;
    /** Analysis cycles between two steps of the held-excess case, and the
     *  step the notch's phase takes in that time.  The excess is HELD until
     *  the next step replaces it, so in between the modelled notch stands
     *  still and the held value is exact. */
    private static final int        HOLD_CYCLES   = 50;
    private static final double     HOLD_STEP_RAD = Math.toRadians(4.0);

    /** The most samples an analysis cycle of the slipped case loses without
     *  telling the counter: past half a period of the fundamental -
     *  {@code FFT_SIZE / (2 FUND_BIN)} = 32 samples - so on some analysis
     *  cycles the count names the wrong period, which a ladder of whole
     *  multiples of the fundamental must not see. */
    private static final int        SLIP_MAX_SAMPLES = 40;
    /** The least number of analysis cycles the pilot case with the gap must
     *  average: a run in which the pair settled on few frames would hold its
     *  levels on those few and prove nothing about the rest. */
    private static final int        SLIP_MIN_AVERAGED_CYCLES = CYCLES * 3 / 4;

    /** The two pilot bins of the cases below, both whole bins of the
     *  transform and spaced ONE FUNDAMENTAL BIN apart - 992 and 992 - 64 -
     *  which is what makes their phase difference the fundamental's own share
     *  of the advance.  Both stand clear of the fundamental's ladder (bin 64
     *  and its harmonics to 576): 928 is 14.5 of them. */
    private static final int    PILOT_BIN  = 992;
    private static final int    PILOT2_BIN = PILOT_BIN - FUND_BIN;
    /** Amplitude of every bin of the synthetic frame that carries no tone:
     *  60 dB under the pilot's own line, so the worker's per-cycle check finds
     *  the pilot standing over its local floor. */
    private static final double PILOT_FLOOR = 1.0e-3;
    /** The margin a pilot line must hold over its local floor, dB: the
     *  preference's default, which the worker's start() loads - the harness
     *  never starts the worker, so it writes the value itself.  Over the flat
     *  floor above, a frame carrying the line reads 60 dB and one without it
     *  0 dB. */
    private static final double PILOT_MARGIN_DB = 20.0;
    /** Analysis cycles each pilot case runs.  Enough that a case which loses
     *  samples part way through still leaves the resolver a decision span and
     *  its confirming frames afterwards: a span is two transform lengths,
     *  which at these hops is about five analysis cycles. */
    private static final int    PILOT_CYCLES = 24;
    /** The hops a pilot case draws from, in samples - the random overlap
     *  draws a new one every analysis cycle, so the cases must not stand on
     *  one. */
    private static final int[]  PILOT_HOPS   = { 1024, 1280, 1536, 1792, 2048 };
    /** The fixed hop of the single-pilot case: with one line the whole turns
     *  come from the hop counter, which is right only while the counter's
     *  advance and the stream's agree to under half a turn of that line. */
    private static final int    SINGLE_PILOT_HOP = 2048;
    /** The gap the cases below lose without telling the hop counter, samples.
     *  Six of pilot 1's periods, and inside the HALF FUNDAMENTAL PERIOD -
     *  {@code FFT_SIZE / (2 FUND_BIN)} = 32 samples here - that the counter
     *  still has to place, the pilots' difference placing the rest exactly.
     *  A single line had to be placed to half of its own period, 2.1 samples
     *  here, which is what a re-sync used to break. */
    private static final double GAP_SAMPLES = 25.0;
    /** The accumulated-pilot case: frames, the hop between them and the level
     *  of the two lines.  The hop is a whole number of neither pilot's period
     *  - 1000 times 992 and 1000 times 1303 are both far from a multiple of
     *  the transform's 4096 - so each frame finds both lines at a phase of
     *  their own and only a member of their own in the family can bring them
     *  back together. */
    private static final int    PILOT_ACCUM_CYCLES = 8;
    private static final int    PILOT_ACCUM_HOP    = 1000;
    private static final double PILOT_ACCUM_DBFS   = -40.0;
    private static final double PILOT_PHASE_RAD    = 0.9;
    private static final double PILOT2_PHASE_RAD   = -2.4;
    /** The relative error left between the two converter clocks by the
     *  fundamental's frequency lock: it trims the generator until the
     *  fundamental sits on its bin in the capture's clock, and the pilots,
     *  generated on the same clock, ride the same trim, so every line stands
     *  off its bin by this one ratio - the lock's own residual of 0.02 ppm. */
    private static final double CLOCK_ERROR = 0.02e-6;
    /** How far the pilot sits off the CENTRE of its bin, that ratio at its
     *  own bin. */
    private static final double PILOT_OFFSET_BINS = CLOCK_ERROR * PILOT_BIN;
    /** One clock error displaces every line in proportion to its own bin, so
     *  the other lines' share of the same error follows the ratio of the
     *  bins.  Both pilots are then read at the advance one stretched clock
     *  gives, the tones owe exactly that stretched advance their turn, and
     *  the error leaves no excess behind. */
    private static final double FUND_OFFSET_BINS =
            PILOT_OFFSET_BINS * FUND_BIN / (double) PILOT_BIN;
    /** Where the fundamental and pilot 1 actually sit, in bins; pilot 2 rides
     *  the same relative error, which the frame builder applies at its own
     *  bin's ratio. */
    private static final double PILOT_POS  = PILOT_BIN  + PILOT_OFFSET_BINS;
    private static final double FUND_POS   = FUND_BIN   + FUND_OFFSET_BINS;
    /** The time base's own slip per analysis cycle, in samples: what a
     *  converter pair running at slightly different rates adds to the
     *  window's position.  It turns EVERY line, the pilot by
     *  {@code 2 pi f2 dt} and the fundamental by {@code 2 pi f0 dt}, which
     *  is what the pilot method cancels. */
    private static final double PILOT_SLIP_SAMPLES = 0.05;
    /** The notch's own phase step per analysis cycle, radians: a drift that
     *  turns the fundamental ALONE, which is what the excess must report.  A
     *  ninth of a degree per analysis cycle, inside the excess change the
     *  worker allows between two read frames (a third of a degree plus the
     *  drift of the elapsed time), and forty times a real notch's drift over
     *  one hop. */
    private static final double PILOT_NOTCH_STEP_RAD = 0.002;
    /** Degrees the pilot cases allow around an exact answer. */
    private static final double PILOT_TOL_DEG = 1.0e-3;

    /** The worker members this harness drives, by name because that is what
     *  reflection takes: the private field holding the next analysis cycle's
     *  phase excess, the private accumulate step, and the private accumulator
     *  whose overlay writes the average back onto a result for the display
     *  path and whose rollback the discontinuity path uses. */
    private static final String PHI_F_FIELD = "pendingPhiF";
    private static final String ACCUMULATE  = "accumulateIntoForeverBuffer";
    private static final String ACCUMULATOR = "accumulator";

    private static final Logger LOG = LogManager.getLogger("FftPhaseAlign");

    /** One analysis cycle's phase excess in radians, as the driver supplies
     *  it. */
    @FunctionalInterface
    private interface CyclePhiF {
        double of(FftAnalyzer analyzer, FftResult r, long base);
    }

    /**
     * Decision case 1: the analysed channel's fundamental phase random-walks
     * up to 30 degrees over the run while its harmonics stand still.  Fed the
     * reference channel's phase, the accumulator holds H2..H9 at their
     * synthesized levels; fed nothing (mode NONE), the tracking loop charges
     * the walk to the time base and H9 - nine times the fundamental's phase
     * error - sinks.
     */
    @Test
    void referenceChannelHoldsTheHarmonicsThatNoneLoses() {
        int total = (CYCLES - 1) * HOP + WIN_LEN;
        double[] walk      = phaseWalk(SEED, total);
        double[] analysed  = channel(SEED + 1, total, FUND_BIN, HARMONICS, 0.0, walk);
        double[] reference = channel(SEED + 2, total, FUND_BIN, 0, 0.0, null);
        double   walkSpan  = span(walk);
        assertTrue(walkSpan >= WALK_MIN_SPAN_RAD,
                "the fundamental's phase must actually walk; span was "
                        + fmt(Math.toDegrees(walkSpan)) + " deg");

        double[] refWin = new double[WIN_LEN];
        double[] aligned = accumulate(analysed, (analyzer, r, base) -> {
            System.arraycopy(reference, (int) base, refWin, 0, WIN_LEN);
            return phaseExcess(analyzer, r, refWin);
        });
        double[] none = accumulate(analysed, (analyzer, r, base) -> 0.0);

        LOG.info("phase walk span = {} deg", fmt(Math.toDegrees(walkSpan)));
        LOG.info("aligned: fund={} H2..H9={}", fmt(aligned[0]), fmtAll(aligned));
        LOG.info("none   : fund={} H2..H9={}", fmt(none[0]), fmtAll(none));

        assertEquals(FUND_DBFS, aligned[0], FUND_TOL_DB,
                "the aligned fundamental must hold its level");
        for (int h = 2; h <= HARMONICS + 1; h++) {
            assertEquals(HARMONIC_DBFS, aligned[h - 1], LEVEL_TOL_DB,
                    "H" + h + " must hold its level while the fundamental's phase walks");
        }
        double h9Loss = HARMONIC_DBFS - none[HARMONICS];
        assertTrue(h9Loss >= H9_MIN_LOSS_DB,
                "unaligned H9 must lose at least " + H9_MIN_LOSS_DB + " dB; lost "
                        + fmt(h9Loss) + " dB");
    }

    /**
     * Decision case 2: a constant phase excess changes no accumulated level.
     * The two application points - the loop's fundamental phase and the
     * fundamental region's rotation - must carry the SAME constant, or the
     * region's vector sum shrinks (a sign error) or the loop chases an offset
     * that is not there.
     */
    @Test
    void constantExcessLeavesEveryLevelWhereZeroDoes() {
        int total = (CYCLES - 1) * HOP + WIN_LEN;
        double[] analysed = channel(SEED + 3, total, FUND_BIN, HARMONICS, 0.0, null);

        double[] zero     = accumulate(analysed, (analyzer, r, base) -> 0.0);
        double[] constant = accumulate(analysed, (analyzer, r, base) -> CONST_PHIF_RAD);

        LOG.info("zero    : fund={} H2..H9={}", fmt(zero[0]), fmtAll(zero));
        LOG.info("constant: fund={} H2..H9={}", fmt(constant[0]), fmtAll(constant));

        for (int i = 0; i <= HARMONICS; i++) {
            assertEquals(zero[i], constant[i], IDENTICAL_TOL_DB,
                    (i == 0 ? "the fundamental" : "H" + (i + 1))
                            + " must be untouched by a constant phase excess");
        }
    }

    /**
     * The reference recipe itself: two channels carrying the SAME off-bin
     * fundamental at a known phase difference.  phiF must be that difference at
     * every analysis cycle - constant, and free of the per-cycle hop advance.
     * Off-bin on purpose: with an integer bin and this hop the analyser's
     * de-rotation is the identity and a wrong recipe would pass unnoticed.
     */
    @Test
    void phaseExcessIsTheChannelDifference() {
        final double offBinPos   = FUND_BIN + 0.3;
        final double analysedPhi = 1.1;
        final double referencePhi = -0.4;
        int total = (CYCLES - 1) * HOP + WIN_LEN;
        double[] analysed  = channel(SEED + 4, total, offBinPos, HARMONICS, analysedPhi, null);
        double[] reference = channel(SEED + 5, total, offBinPos, 0, referencePhi, null);

        FftAnalyzer analyzer = new FftAnalyzer();
        double[] win    = new double[WIN_LEN];
        double[] refWin = new double[WIN_LEN];
        double expected = Math.IEEEremainder(analysedPhi - referencePhi, 2.0 * Math.PI);
        long base = 0;
        for (int t = 0; t < CYCLES; t++) {
            System.arraycopy(analysed,  (int) base, win,    0, WIN_LEN);
            System.arraycopy(reference, (int) base, refWin, 0, WIN_LEN);
            FftResult r = analyze(analyzer, win, base);
            double phiF = phaseExcess(analyzer, r, refWin);
            assertEquals(expected, phiF, 1.0e-3,
                    "phiF must be the two channels' fundamental phase difference at analysis cycle " + t);
            base += HOP;
        }
    }

    /**
     * What the worker feeds its accumulator in mode REFERENCE_CHANNEL is the
     * CHANGE of the measured excess since the collection's first analysis
     * cycle, not the excess itself: the accumulated fundamental then keeps
     * the notch's baseline phase, the phase the loaded correction de-embeds
     * and the predistortion frame reference subtracts.  Two channels at a
     * fixed difference give zero at every analysis cycle; a difference that
     * grows by a step afterwards gives that step.
     */
    @Test
    void referenceExcessFedToTheAccumulatorIsTheChangeSinceTheFirstCycle() throws Exception {
        final double offBinPos    = FUND_BIN + 0.3;
        final double analysedPhi  = 1.1;
        final double referencePhi = -0.4;
        final double stepRad      = 0.3;
        int total = (CYCLES - 1) * HOP + WIN_LEN;
        double[] analysed  = channel(SEED + 7, total, offBinPos, HARMONICS, analysedPhi, null);
        double[] stepped   = channel(SEED + 7, total, offBinPos, HARMONICS, analysedPhi + stepRad, null);
        double[] reference = channel(SEED + 8, total, offBinPos, 0, referencePhi, null);

        FftAnalyzer       analyzer = new FftAnalyzer();
        FftAnalyzerWorker worker   = new FftAnalyzerWorker(null);
        double[] win    = new double[WIN_LEN];
        double[] refWin = new double[WIN_LEN];
        // The analysed and the reference window the worker's frame read
        // takes, and the frame geometry it reads them with - the state the
        // analysis-cycle loop would have set.
        FieldUtils.writeField(worker, "analyzeBuf", win, true);
        FieldUtils.writeField(worker, "refWinBuf", refWin, true);
        FieldUtils.writeField(worker, "winValid", true, true);
        FieldUtils.writeField(worker, "pendingHopSamples", HOP, true);

        long base = 0;
        for (int t = 0; t < CYCLES; t++) {
            System.arraycopy(analysed,  (int) base, win,    0, WIN_LEN);
            System.arraycopy(reference, (int) base, refWin, 0, WIN_LEN);
            double fed = cyclePhiF(worker, analyze(analyzer, win, base));
            assertEquals(0.0, fed, 1.0e-3,
                    "a constant difference feeds no excess at analysis cycle " + t);
            base += HOP;
        }
        System.arraycopy(stepped,   0, win,    0, WIN_LEN);
        System.arraycopy(reference, 0, refWin, 0, WIN_LEN);
        assertEquals(stepRad, cyclePhiF(worker, analyze(analyzer, win, 0)), 1.0e-3,
                "a step of the difference feeds the step");
    }

    /**
     * The reference channel as the frame's clock.  Before every analysis
     * cycle the capture loses a different number of samples without telling
     * the counter, so the counted advance is wrong on every frame - by up to
     * {@link #SLIP_MAX_SAMPLES}, more than half a period of the fundamental.
     * In mode REFERENCE_CHANNEL the worker turns each frame by the turn its
     * own read of the reference channel shows, and every harmonic holds its
     * level while the fundamental's phase walks through the notch; turned by
     * the count, with the tracking loop left to follow a time base that jumps
     * from cycle to cycle, H9 sinks.
     */
    @Test
    void theReferenceChannelsTurnCarriesTheFrameThroughUncountedSlips() throws Exception {
        int total = (CYCLES - 1) * HOP + WIN_LEN + SLIP_MAX_SAMPLES;
        double[] walk      = phaseWalk(SEED + 9, total);
        double[] analysed  = channel(SEED + 10, total, FUND_BIN, HARMONICS, 0.0, walk);
        double[] reference = channel(SEED + 11, total, FUND_BIN, 0, 0.0, null);
        Random rng   = new Random(SEED + 12);
        int[]  slips = new int[CYCLES];
        for (int t = 0; t < CYCLES; t++) {
            slips[t] = rng.nextInt(SLIP_MAX_SAMPLES + 1);
        }

        double[] measured = accumulateSlipped(analysed, reference, slips);
        double[] counted  = accumulateSlipped(analysed, null, slips);

        LOG.info("measured: fund={} H2..H9={}", fmt(measured[0]), fmtAll(measured));
        LOG.info("counted : fund={} H2..H9={}", fmt(counted[0]), fmtAll(counted));

        assertEquals(FUND_DBFS, measured[0], FUND_TOL_DB,
                "the fundamental must hold its level on the reference channel's turn");
        for (int h = 2; h <= HARMONICS + 1; h++) {
            assertEquals(HARMONIC_DBFS, measured[h - 1], LEVEL_TOL_DB,
                    "H" + h + " must hold its level through slips the counter never saw");
        }
        double h9Loss = HARMONIC_DBFS - counted[HARMONICS];
        assertTrue(h9Loss >= H9_MIN_LOSS_DB,
                "turned by the count, H9 must lose at least " + H9_MIN_LOSS_DB + " dB; lost "
                        + fmt(h9Loss) + " dB");
    }

    /** Drives a worker over a capture whose window at analysis cycle
     *  {@code t} starts {@code slips[t]} samples later than the counter says,
     *  and returns the accumulated {@code [fundamental, H2 .. H9]} in dBFS.
     *  With a reference capture the worker reads its own excess in mode
     *  REFERENCE_CHANNEL on every analysis cycle - the read that also takes
     *  the reference channel's turn; without one it accumulates by the count
     *  alone. */
    private double[] accumulateSlipped(double[] analysed, double[] reference, int[] slips)
            throws Exception {
        FftAnalyzer       analyzer = new FftAnalyzer();
        FftAnalyzerWorker worker   = new FftAnalyzerWorker(null);
        double[] win    = new double[WIN_LEN];
        double[] refWin = new double[WIN_LEN];
        if (reference != null) {
            FieldUtils.writeField(worker, "analyzeBuf", win, true);
            FieldUtils.writeField(worker, "refWinBuf", refWin, true);
            FieldUtils.writeField(worker, "winValid", true, true);
            FieldUtils.writeField(worker, "pendingHopSamples", HOP, true);
        }
        FftResult r = null;
        long base = 0;
        for (int t = 0; t < CYCLES; t++) {
            int at = (int) base + slips[t];
            System.arraycopy(analysed, at, win, 0, WIN_LEN);
            r = analyze(analyzer, win, base);
            double phiF = 0.0;
            if (reference != null) {
                System.arraycopy(reference, at, refWin, 0, WIN_LEN);
                phiF = cyclePhiF(worker, r);
            }
            accumulateCycle(worker, phiF, r, base);
            base += HOP;
        }
        return levels(worker, r);
    }

    /**
     * The pilot pair as the frame's clock.  Half way through the run the
     * capture loses {@value #GAP_SAMPLES} samples without telling the
     * counter, so from there on the counted advance is wrong on every frame
     * by more than a third of the fundamental's period - at H9 a turn that
     * sets the second half of the run against the first.  In mode PILOT_TONE
     * the worker turns each frame by the change of the two pilots' phase
     * difference - the lines stand one fundamental apart, so that difference
     * turns as the emitted fundamental does - and every harmonic holds its
     * level while the fundamental's phase walks through the notch.
     */
    @Test
    void thePilotPairsTurnCarriesTheFrameThroughAnUncountedGap() throws Exception {
        int gap   = (int) GAP_SAMPLES;
        int total = (CYCLES - 1) * HOP + WIN_LEN + gap;
        double[] walk     = phaseWalk(SEED + 13, total);
        double[] analysed = channel(SEED + 14, total, FUND_BIN, HARMONICS, 0.0, walk);
        addPilots(analysed);
        int[] slips = new int[CYCLES];
        for (int t = CYCLES / 2; t < CYCLES; t++) {
            slips[t] = gap;
        }

        double[] measured = accumulatePilotSlipped(analysed, slips);

        LOG.info("pilot pair: fund={} H2..H9={}", fmt(measured[0]), fmtAll(measured));
        assertEquals(FUND_DBFS, measured[0], FUND_TOL_DB,
                "the fundamental must hold its level on the pilot pair's turn");
        for (int h = 2; h <= HARMONICS + 1; h++) {
            assertEquals(HARMONIC_DBFS, measured[h - 1], LEVEL_TOL_DB,
                    "H" + h + " must hold its level through a gap the counter never saw");
        }
    }

    /** Adds the two pilot lines, each on its whole bin, to a capture. */
    private void addPilots(double[] capture) {
        double a  = Math.pow(10.0, PILOT_ACCUM_DBFS / 20.0);
        double w1 = 2.0 * Math.PI * PILOT_BIN / FFT_SIZE;
        double w2 = 2.0 * Math.PI * PILOT2_BIN / FFT_SIZE;
        for (int k = 0; k < capture.length; k++) {
            capture[k] += a * (Math.sin(w1 * k + PILOT_PHASE_RAD)
                    + Math.sin(w2 * k + PILOT2_PHASE_RAD));
        }
    }

    /** Drives a pilot-mode worker over a capture whose window at analysis
     *  cycle {@code t} starts {@code slips[t]} samples later than the counter
     *  says, the way the analysis-cycle loop does: the worker reads its own
     *  excess, and an analysis cycle its alignment left unsettled is not
     *  averaged.  Returns the accumulated {@code [fundamental, H2 .. H9]} in
     *  dBFS. */
    private double[] accumulatePilotSlipped(double[] analysed, int[] slips) throws Exception {
        FftAnalyzer       analyzer = new FftAnalyzer();
        FftAnalyzerWorker worker   = pilotWorker();
        double[]  win      = new double[WIN_LEN];
        FftResult r        = null;
        long      base     = 0;
        int       averaged = 0;
        for (int t = 0; t < CYCLES; t++) {
            System.arraycopy(analysed, (int) base + slips[t], win, 0, WIN_LEN);
            analyzer.setPilotBins(PILOT_BIN, PILOT2_BIN);
            r = analyze(analyzer, win, base);
            FieldUtils.writeField(worker, "winAbsStart", base, true);
            FieldUtils.writeField(worker, "phaseAlignUnsettled", false, true);
            double phiF = call(worker, "cyclePhiF", FundamentalPhaseAlignment.PILOT_TONE, r, true);
            if (!(Boolean) field(worker, "phaseAlignUnsettled")) {
                accumulateCycle(worker, phiF, r, base);
                averaged++;
            }
            base += HOP;
        }
        assertTrue(averaged >= SLIP_MIN_AVERAGED_CYCLES,
                "the pair must settle on at least " + SLIP_MIN_AVERAGED_CYCLES + " of " + CYCLES
                        + " analysis cycles; it settled on " + averaged);
        return levels(worker, r);
    }

    /**
     * A change of the generated signal ends the alignment's collection: the
     * first frames every later reading of that collection is taken against go,
     * so the next frame starts a collection of its own - a tone that changed
     * has a new phase through the notch, and nothing measured on the old tone
     * may be applied to it.
     */
    @Test
    void aChangeOfTheGeneratedSignalStartsTheAlignmentAfresh() throws Exception {
        FftAnalyzerWorker worker = new FftAnalyzerWorker(null);
        FieldUtils.writeField(worker, "pilotFirstTaken",    true, true);
        FieldUtils.writeField(worker, "pilotRefFirstTaken", true, true);

        call(worker, "restartAlignment");

        assertFalse((Boolean) field(worker, "pilotRefFirstTaken"),
                "the reference channel's first frame is taken anew");
        assertFalse((Boolean) field(worker, "pilotFirstTaken"),
                "and so is the frame the pilot's relation is read against");
    }

    /**
     * A change of the alignment MODE ends the collection as well: the mode is
     * what supplies the time base, so nothing measured under the old one may
     * be applied under the new one - and entering the pilot mode has to elect
     * a pilot and ask the generator for it, which the restart is what does.
     * The worker's answer to the changed setting is the flag its own thread
     * consumes at the start of its next cycle.
     *
     * <p>In the application the listener is registered by the worker's
     * {@code start()}, which takes a capture device and starts a thread; here
     * it is read off the worker and put on the preference directly, so the
     * case stays inside this class's headless harness.
     */
    @Test
    void aChangeOfTheAlignmentModeArmsTheRestart() throws Exception {
        FftAnalyzerWorker worker = new FftAnalyzerWorker(null);
        Preferences prefs = Preferences.instance();
        prefs.setTransientMode(true);   // the setting is written through the live singleton
        FundamentalPhaseAlignment previous = prefs.getFftFundamentalPhaseAlignment();
        FundamentalPhaseAlignment other = previous == FundamentalPhaseAlignment.PILOT_TONE
                ? FundamentalPhaseAlignment.NONE : FundamentalPhaseAlignment.PILOT_TONE;
        Consumer<FundamentalPhaseAlignment> listener = field(worker, "alignModeChanged");
        Property<FundamentalPhaseAlignment> mode = prefs.fftFundamentalPhaseAlignmentProperty();
        AtomicBoolean armed = field(worker, "alignRestartPending");
        mode.addListener(listener);
        try {
            armed.set(false);

            prefs.setFftFundamentalPhaseAlignment(other);

            assertTrue(armed.get(),
                    "the collection goes on under a time base the operator has just changed");
        } finally {
            mode.removeListener(listener);
            prefs.setFftFundamentalPhaseAlignment(previous);
        }
    }

    /**
     * Mode PILOT_TONE, the case the mode exists for: a wander of the TIME
     * BASE (the capture clock running against the playback clock) turns every
     * line, each in proportion to its own bin.  The pair then resolves an
     * advance stretched by exactly that relative error, the fundamental owes
     * its whole turn to that same stretched advance, and the excess stays at
     * zero - the notch did not move.  The hops are drawn per analysis
     * cycle, as the random overlap draws them, and all three lines sit off
     * their bin centres by one relative clock error, as lines carried on one
     * converter pair do.
     */
    @Test
    void aCommonTimeBaseDriftLeavesThePilotExcessAtZero() throws Exception {
        FftAnalyzerWorker worker = pilotWorker();
        double excess = 0.0;
        long   pos    = 0;
        for (int t = 0; t < PILOT_CYCLES; t++) {
            double at = pos + PILOT_SLIP_SAMPLES * t;
            excess = pilotCycle(worker, pos, FUND_POS, PILOT_POS, at, 0.0, true);
            pos += PILOT_HOPS[t % PILOT_HOPS.length];
        }
        LOG.info("pilot, common drift: excess = {} deg", fmt(Math.toDegrees(excess)));
        assertEquals(0.0, Math.toDegrees(excess), PILOT_TOL_DEG,
                "a drift both lines share is not the notch's and must cancel");
    }

    /**
     * The other half: a drift that turns the FUNDAMENTAL alone - the notch's
     * centre frequency moving with temperature, where the phase response is
     * steep - reaches the excess whole, on top of a time-base wander both
     * lines share; whole up to the worker's averaging over its last frames,
     * which lags a steady drift by half the window.
     */
    @Test
    void aNotchOnlyDriftBecomesThePilotExcess() throws Exception {
        FftAnalyzerWorker worker = pilotWorker();
        double excess = 0.0;
        long   pos    = 0;
        for (int t = 0; t < PILOT_CYCLES; t++) {
            double at = pos + PILOT_SLIP_SAMPLES * t;
            excess = pilotCycle(worker, pos, FUND_POS, PILOT_POS, at, PILOT_NOTCH_STEP_RAD * t, true);
            pos += PILOT_HOPS[t % PILOT_HOPS.length];
        }
        double expected = notchRadAt(PILOT_CYCLES - 1);
        LOG.info("pilot, notch drift: excess = {} deg, notch turned {} deg",
                fmt(Math.toDegrees(excess)), fmt(Math.toDegrees(expected)));
        assertEquals(Math.toDegrees(expected), Math.toDegrees(excess), PILOT_TOL_DEG,
                "the fundamental's own rotation since the first analysis cycle IS the excess");
    }

    /**
     * The window's own position leaves no ramp: with every line on its bin
     * and the window hopping half a transform per analysis cycle - where an
     * odd bin's raw phase alternates by half a turn - each line's turn is
     * exactly the one the resolved advance explains, and the excess stays at
     * zero.
     */
    @Test
    void theWindowPositionLeavesNoRamp() throws Exception {
        FftAnalyzerWorker worker = pilotWorker();
        double excess = 0.0;
        long   pos    = 0;
        for (int t = 0; t < PILOT_CYCLES; t++) {
            excess = pilotCycle(worker, pos, FUND_BIN, PILOT_BIN, pos, 0.0, true);
            pos += FFT_SIZE / 2;
        }
        LOG.info("pilot, half-transform hops: excess = {} deg", fmt(Math.toDegrees(excess)));
        assertEquals(0.0, Math.toDegrees(excess), PILOT_TOL_DEG,
                "a frame at any position reads the same relation, so there is no excess");
    }

    /**
     * The signal shifts against the window's position between two analysis
     * cycles by more than half a pilot period, so pilot 1's raw phase steps
     * past half a turn while the fundamental's steps by a few degrees.  The
     * hop counter knows nothing of the shift, so its guess at the advance is
     * wrong by it; the two pilots' ladders agree on the true advance, the
     * fundamental owes its own turn to that advance, and no excess is left.
     */
    @Test
    void aShiftPastHalfAPilotPeriodForwardLeavesNoExcess() throws Exception {
        assertShiftLeavesNoExcess(FFT_SIZE / (2.0 * PILOT_BIN) + 0.15, "forward");
    }

    /** The same the other way round. */
    @Test
    void aShiftPastHalfAPilotPeriodBackwardLeavesNoExcess() throws Exception {
        assertShiftLeavesNoExcess(-(FFT_SIZE / (2.0 * PILOT_BIN) + 0.6), "backward");
    }

    /** Many periods at once: {@value #GAP_SAMPLES} samples, which at this
     *  fixture's bins is six pilot periods and stays inside the half
     *  fundamental period the hop counter has to place. */
    @Test
    void aShiftOfManyPilotPeriodsLeavesNoExcess() throws Exception {
        assertShiftLeavesNoExcess(GAP_SAMPLES, "of " + (int) GAP_SAMPLES + " samples");
    }

    /** Two whole periods of the fundamental less a few samples: the capture
     *  losing a millisecond's worth - the shift that on a bench's bins used
     *  to leave a fraction of a degree that nothing could tell from drift. */
    @Test
    void aShiftOfTwoFundamentalPeriodsLeavesNoExcess() throws Exception {
        assertShiftLeavesNoExcess(2.0 * FFT_SIZE / FUND_BIN - 3.0, "of two fundamental periods");
    }

    private void assertShiftLeavesNoExcess(double shift, String direction) throws Exception {
        FftAnalyzerWorker worker = pilotWorker();
        double excess = 0.0;
        long   pos    = 0;
        for (int t = 0; t < PILOT_CYCLES; t++) {
            double at = pos + (t >= PILOT_CYCLES / 2 ? shift : 0.0);
            excess = pilotCycle(worker, pos, FUND_POS, PILOT_POS, at, 0.0, true);
            pos += PILOT_HOPS[t % PILOT_HOPS.length];
        }
        LOG.info("pilot, shift {}: excess = {} deg", direction, fmt(Math.toDegrees(excess)));
        assertEquals(0.0, Math.toDegrees(excess), PILOT_TOL_DEG,
                "a " + direction + " shift of the signal against the window's position moves "
                        + "both lines alike and leaves no excess");
    }

    /** A worker with the pair elected on the two bins, the line margin its
     *  start() would load, and nothing else: the
     *  pilot path reads the frame handed to it, never a device.  The frame
     *  handed to it is the fresh one alone, so it starts at the window's
     *  first sample, where the analysis-cycle loop's drawn hop would
     *  otherwise put it. */
    private FftAnalyzerWorker pilotWorker() throws Exception {
        FftAnalyzerWorker worker = new FftAnalyzerWorker(null);
        FieldUtils.writeField(worker, "pilotReader1", new PilotReader(PILOT_BIN), true);
        FieldUtils.writeField(worker, "pilotReader2", new PilotReader(PILOT2_BIN), true);
        FieldUtils.writeField(worker, "pilotPair",
                new PairAdvance(PILOT_BIN, PILOT2_BIN, FFT_SIZE, SAMPLE_RATE), true);
        FieldUtils.writeField(worker, "pendingHopSamples", 0, true);
        FieldUtils.writeField(worker, "pilotMarginDb", PILOT_MARGIN_DB, true);
        return worker;
    }

    /** The excess the worker applies at analysis cycle {@code t} under the
     *  notch drift: the notch's rotation since the first frame, which the
     *  per-frame changes sum to exactly - the pilot path holds the sum itself
     *  and averages nothing. */
    private double notchRadAt(int t) {
        return PILOT_NOTCH_STEP_RAD * t;
    }

    /** One pilot analysis cycle with both lines present. */
    private double pilotCycle(FftAnalyzerWorker worker, long absStart, double kappaF,
            double kappaP, double at, double notchRad, boolean pilotLine) throws Exception {
        return pilotCycle(worker, absStart, kappaF, kappaP, at, notchRad, pilotLine, pilotLine);
    }

    /** One pilot analysis cycle: the analysis window's absolute start, which
     *  the analysis-cycle loop sets and the pilot path reads as the frame's
     *  time; the fresh frame, with the three lines at their positions in
     *  bins, the frame starting {@code at} samples into the stream and the
     *  fundamental turned by {@code notchRad} on top; which of the two pilot
     *  lines the cycle's spectrum carries; and then the excess the mode feeds
     *  the accumulator.  The second pilot rides the same relative clock error
     *  as the first, one error displacing every line in proportion to its own
     *  bin. */
    private double pilotCycle(FftAnalyzerWorker worker, long absStart, double kappaF,
            double kappaP, double at, double notchRad, boolean pilotLine,
            boolean pilot2Line) throws Exception {
        double   kappaP2 = kappaP * PILOT2_BIN / (double) PILOT_BIN;
        double[] frame   = pilotSamples(kappaF, kappaP, kappaP2, at, notchRad);
        FieldUtils.writeField(worker, "winAbsStart", absStart, true);
        FieldUtils.writeField(worker, "analyzeBuf", frame, true);
        return call(worker, "cyclePhiF",
                FundamentalPhaseAlignment.PILOT_TONE, pilotSpectrum(frame, pilotLine, pilot2Line), true);
    }

    /**
     * A frame whose pilot bins hold no line - the pair buried for a moment -
     * measures nothing: its excess is predicted from the tone's own excess
     * over the frames before it, fitted against the frames' own times on the
     * capture's clock and read at the refused frame's time.  Under a notch
     * drifting steadily in time the fit continues the drift exactly, so the
     * refused frame's excess is the drift's value at that frame, not the last
     * measured one - and the frames arrive at the random overlap's uneven
     * hops, so the fit must read the time, not count the frames.
     */
    @Test
    void aFrameWithoutThePilotLineTakesThePredictedExcess() throws Exception {
        FftAnalyzerWorker worker = pilotWorker();
        long pos = 0;
        for (int t = 0; t < PILOT_CYCLES; t++) {
            pilotCycle(worker, pos, FUND_POS, PILOT_POS, pos + PILOT_SLIP_SAMPLES * t,
                    steadyNotchRad(pos), true);
            pos += PILOT_HOPS[t % PILOT_HOPS.length];
        }
        double predicted = pilotCycle(worker, pos, FUND_POS, PILOT_POS,
                pos + PILOT_SLIP_SAMPLES * PILOT_CYCLES, steadyNotchRad(pos), false);
        LOG.info("pilot, line refused: excess = {} deg, the drift there {} deg",
                fmt(Math.toDegrees(predicted)), fmt(Math.toDegrees(steadyNotchRad(pos))));
        assertEquals(Math.toDegrees(steadyNotchRad(pos)), Math.toDegrees(predicted), PILOT_TOL_DEG,
                "the refused frame's excess is the drift continued to the frame's own time");
    }

    /** A notch drift steady in TIME: one {@link #PILOT_NOTCH_STEP_RAD} per
     *  first hop's worth of samples, at the absolute position {@code pos}. */
    private double steadyNotchRad(long pos) {
        return PILOT_NOTCH_STEP_RAD * pos / PILOT_HOPS[0];
    }

    /**
     * A gap in the capture - {@value #GAP_SAMPLES} samples the converter
     * dropped without saying so, which the hop counter cannot know about -
     * leaves the notch's excess exactly where a run without the gap leaves it.
     * The signal moves against the window by those samples in the middle of
     * the run, so every line's phase jumps: pilot 1 by six of its own turns,
     * the fundamental by a third of one.  The pilots' difference is the
     * fundamental's own rotation, which places the advance inside the period
     * the counter names, and the fundamental's jump is explained by it.  The
     * notch meanwhile turns by its step per frame in both runs, so the two
     * must end on the same number.
     */
    @Test
    void aGapOfLostSamplesLeavesTheSameExcess() throws Exception {
        double plain  = notchRunExcess(0.0);
        double gapped = notchRunExcess(GAP_SAMPLES);
        LOG.info("pilot, {}-sample gap: excess {} deg, without the gap {} deg",
                GAP_SAMPLES, fmt(Math.toDegrees(gapped)), fmt(Math.toDegrees(plain)));
        assertEquals(Math.toDegrees(notchRadAt(PILOT_CYCLES - 1)), Math.toDegrees(plain),
                PILOT_TOL_DEG, "the run without a gap reports the notch's own rotation");
        assertEquals(Math.toDegrees(plain), Math.toDegrees(gapped), PILOT_TOL_DEG,
                "and the pair resolves the gap, so the run with it reports the same");
    }

    /** One run of {@link #PILOT_CYCLES} frames under the notch's per-frame
     *  step, the signal moved against the window by {@code gap} samples from
     *  the middle of the run on; the excess the last frame feeds. */
    private double notchRunExcess(double gap) throws Exception {
        FftAnalyzerWorker worker = pilotWorker();
        double excess = 0.0;
        long   pos    = 0;
        for (int t = 0; t < PILOT_CYCLES; t++) {
            double at = pos + PILOT_SLIP_SAMPLES * t + (t >= PILOT_CYCLES / 2 ? gap : 0.0);
            excess = pilotCycle(worker, pos, FUND_POS, PILOT_POS, at, notchRadAt(t), true);
            pos += PILOT_HOPS[t % PILOT_HOPS.length];
        }
        return excess;
    }

    /**
     * An election that found ONE admissible pilot still aligns: with no second
     * ladder to settle the whole turns, the count the hop counter's nominal
     * advance implies stands in for them, and at a fixed hop - where that
     * count is right - the notch's own drift reaches the excess exactly as it
     * does under the pair.  What is lost with the second line is the vernier:
     * samples dropped between two frames move the true advance off the
     * counter's and a single line cannot tell that from its own next turn,
     * which is what the election's warning states.
     */
    @Test
    void aSinglePilotAlignsTheNotchDriftAtAFixedHop() throws Exception {
        FftAnalyzerWorker worker = pilotWorker();
        FieldUtils.writeField(worker, "pilotReader2", null, true);
        FieldUtils.writeField(worker, "pilotPair", null, true);
        double excess = 0.0;
        long   pos    = 0;
        for (int t = 0; t < PILOT_CYCLES; t++) {
            double at = pos + PILOT_SLIP_SAMPLES * t;
            excess = pilotCycle(worker, pos, FUND_POS, PILOT_POS, at, notchRadAt(t), true);
            pos += SINGLE_PILOT_HOP;
        }
        LOG.info("single pilot, notch drift: excess = {} deg, notch turned {} deg",
                fmt(Math.toDegrees(excess)), fmt(Math.toDegrees(notchRadAt(PILOT_CYCLES - 1))));
        assertEquals(Math.toDegrees(notchRadAt(PILOT_CYCLES - 1)), Math.toDegrees(excess),
                PILOT_TOL_DEG,
                "one pilot and the hop counter carry the notch's rotation at a fixed hop");
    }

    /**
     * ONE pilot line buried is enough: the advance is what the two lines agree
     * on, so a frame that can offer only one of them measures nothing and
     * takes the drift its trend predicts at its own time, exactly as a frame
     * that lost both does.
     */
    @Test
    void oneMissingPilotLineTakesThePredictedExcess() throws Exception {
        FftAnalyzerWorker worker = pilotWorker();
        long pos = 0;
        for (int t = 0; t < PILOT_CYCLES; t++) {
            pilotCycle(worker, pos, FUND_POS, PILOT_POS, pos + PILOT_SLIP_SAMPLES * t,
                    steadyNotchRad(pos), true);
            pos += PILOT_HOPS[t % PILOT_HOPS.length];
        }
        double predicted = pilotCycle(worker, pos, FUND_POS, PILOT_POS,
                pos + PILOT_SLIP_SAMPLES * PILOT_CYCLES, steadyNotchRad(pos), true, false);
        LOG.info("pilot, second line refused: excess = {} deg, the drift there {} deg",
                fmt(Math.toDegrees(predicted)), fmt(Math.toDegrees(steadyNotchRad(pos))));
        assertEquals(Math.toDegrees(steadyNotchRad(pos)), Math.toDegrees(predicted), PILOT_TOL_DEG,
                "a frame with one line left is a frame without a measurement");
    }

    /**
     * Two frames in a row without a measurement look exactly like a gap in the
     * stream, so the second one asks the analysis-cycle loop for the re-sync
     * a detected discontinuity gets: the loop discards that frame whole,
     * re-anchors the window and publishes no result of its own, which is what
     * leaves the warning banner standing until the reloaded window produces
     * one.  The first frame alone asks for nothing - it is bridged by the
     * trend.
     */
    @Test
    void twoFramesWithoutAMeasurementAskForTheReSync() throws Exception {
        FftAnalyzerWorker worker = pilotWorker();
        long pos = runMeasuredFrames(worker, PILOT_CYCLES);

        pilotCycle(worker, pos, FUND_POS, PILOT_POS, pos, steadyNotchRad(pos), false);
        assertFalse((Boolean) field(worker, "pilotResyncPending"),
                "one frame is bridged by the trend and re-syncs nothing");
        pos += PILOT_HOPS[0];

        pilotCycle(worker, pos, FUND_POS, PILOT_POS, pos, steadyNotchRad(pos), false);
        assertTrue((Boolean) field(worker, "pilotResyncPending"),
                "the second asks the analysis-cycle loop to discard the frame and re-sync");
    }

    /**
     * The third frame in a row means the re-sync did not bring the lines back
     * and the coherent average has no time base at all: the recording ends
     * through the auto-stop path the stop-after-N count uses, and the operator
     * is told once.
     */
    @Test
    void threeFramesWithoutAMeasurementEndTheRecording() throws Exception {
        FftAnalyzerWorker worker = pilotWorker();
        long pos = runMeasuredFrames(worker, PILOT_CYCLES);

        for (int i = 0; i < 3; i++) {
            pilotCycle(worker, pos, FUND_POS, PILOT_POS, pos, steadyNotchRad(pos), false);
            pos += PILOT_HOPS[0];
        }
        AtomicBoolean paused = field(worker, "paused");
        assertTrue(paused.get(), "the recording is stopped through the auto-stop path");
        assertTrue((Boolean) field(worker, "pilotLostPublished"),
                "and the operator is told, once");
    }

    /** Frames the pair measures, as a run starts with; the absolute position
     *  the next frame stands at. */
    private long runMeasuredFrames(FftAnalyzerWorker worker, int frames) throws Exception {
        long pos = 0;
        for (int t = 0; t < frames; t++) {
            pilotCycle(worker, pos, FUND_POS, PILOT_POS, pos + PILOT_SLIP_SAMPLES * t,
                    steadyNotchRad(pos), true);
            pos += PILOT_HOPS[t % PILOT_HOPS.length];
        }
        return pos;
    }

    /**
     * The two pilot lines hold together in the ACCUMULATED spectrum, which is
     * a different claim from every case above: those read the fresh frame and
     * measure, this one asks what the accumulator does with the lines
     * afterwards.  Each pilot sits on a whole bin of its own, so its phase
     * advances with that bin's time shift and with nothing of the
     * fundamental's fractional position; a line left to ride the region of
     * whichever ladder member is nearest would be turned by that member's
     * phase instead and would average itself away.
     *
     * <p>The hop is a whole number of neither pilot's period - 1000 samples
     * against periods of 4096/992 and 4096/1303 samples - so every frame finds
     * both lines at a different phase and nothing but a member of their own
     * can bring them back together.  Eight frames then sum to eight times what
     * one contributes, to within a hundredth of it.
     */
    @Test
    void theAccumulatedPilotLinesHoldTheirCoherentSum() throws Exception {
        int total = (PILOT_ACCUM_CYCLES - 1) * PILOT_ACCUM_HOP + WIN_LEN;
        double[] capture = pilotCapture(total);
        FftAnalyzer       analyzer = new FftAnalyzer();
        FftAnalyzerWorker worker   = pilotWorker();
        double[]  win  = new double[WIN_LEN];
        FftResult r    = null;
        long      base = 0;
        for (int t = 0; t < PILOT_ACCUM_CYCLES; t++) {
            System.arraycopy(capture, (int) base, win, 0, WIN_LEN);
            r = analyzeWithPilots(analyzer, win, base);
            // what the pilot reading leaves for the lobes' own de-rotation:
            // each line's raw phase at this frame's start, its own turn since
            // the capture began
            FieldUtils.writeField(worker, "pilotRawPhase1",
                    Math.IEEEremainder(2.0 * Math.PI * PILOT_BIN * base / FFT_SIZE, 2.0 * Math.PI), true);
            FieldUtils.writeField(worker, "pilotRawPhase2",
                    Math.IEEEremainder(2.0 * Math.PI * PILOT2_BIN * base / FFT_SIZE, 2.0 * Math.PI), true);
            accumulateCycle(worker, 0.0, r, base);
            base += PILOT_ACCUM_HOP;
        }
        CoherentAccumulator accumulator = field(worker, ACCUMULATOR);
        double[] accumRe = accumulator.getSumRe();
        double[] accumIm = accumulator.getSumIm();
        int weight = Math.max(1, r.frameCount);
        for (int bin : new int[]{ PILOT_BIN, PILOT2_BIN }) {
            double one = Math.hypot(r.re[bin], r.im[bin]) * weight;
            double sum = Math.hypot(accumRe[bin], accumIm[bin]);
            LOG.info("pilot bin {}: accumulated {}, one frame {}, ratio {}",
                    bin, fmt(sum), fmt(one), fmt(sum / one));
            assertEquals(PILOT_ACCUM_CYCLES * one, sum, 0.01 * PILOT_ACCUM_CYCLES * one,
                    "the line at bin " + bin + " must sum coherently over "
                            + PILOT_ACCUM_CYCLES + " frames");
        }
    }

    /** A capture carrying the fundamental on its bin and both pilots on
     *  theirs, each at a phase of its own and none of them drifting: what the
     *  accumulator sees while the alignment has nothing to correct. */
    private double[] pilotCapture(int n) {
        double[] s  = new double[n];
        double   a1 = Math.pow(10.0, FUND_DBFS / 20.0);
        double   ap = Math.pow(10.0, PILOT_ACCUM_DBFS / 20.0);
        for (int k = 0; k < n; k++) {
            s[k] = a1 * Math.sin(2.0 * Math.PI * FUND_BIN   * k / FFT_SIZE)
                 + ap * Math.sin(2.0 * Math.PI * PILOT_BIN  * k / FFT_SIZE + PILOT_PHASE_RAD)
                 + ap * Math.sin(2.0 * Math.PI * PILOT2_BIN * k / FFT_SIZE + PILOT2_PHASE_RAD);
        }
        return s;
    }

    /** One analysis cycle's analysis with the two pilot bins pinned on the
     *  analyser, as the analysis-cycle loop pins them once they are
     *  elected. */
    private FftResult analyzeWithPilots(FftAnalyzer analyzer, double[] win, long base) {
        analyzer.setPilotBins(PILOT_BIN, PILOT2_BIN);
        return analyze(analyzer, win, base);
    }

    /**
     * The overlay restates the fundamental's level scalars from the bin it
     * just wrote.  The frequency-response correction runs on the overlaid
     * spectrum before the readouts are recomputed and reads its level scale
     * from those scalars over the bin's magnitude; scalars left from the
     * fresh analysis cycle would put the accumulated spectrum on the fresh
     * cycle's scale, off by however far a drifting notch has moved the
     * fundamental since the collection began.
     */
    @Test
    void theOverlayRestatesTheFundamentalFromTheAccumulatedBin() {
        int total = (CYCLES - 1) * HOP + WIN_LEN;
        double[] analysed = channel(SEED + 3, total, FUND_BIN, HARMONICS, 0.0, null);
        FftAnalyzer       analyzer = new FftAnalyzer();
        FftAnalyzerWorker worker   = new FftAnalyzerWorker(null);
        double[] win = new double[WIN_LEN];
        FftResult r  = null;
        long base = 0;
        for (int t = 0; t < CYCLES; t++) {
            System.arraycopy(analysed, (int) base, win, 0, WIN_LEN);
            r = analyze(analyzer, win, base);
            accumulateCycle(worker, 0.0, r, base);
            base += HOP;
        }
        // a level the fresh analysis cycle never had, as a drifted notch
        // would leave it
        r.fundamentalDbFs   = FUND_DBFS + 6.0;
        r.fundamentalLinear = Math.pow(10.0, r.fundamentalDbFs / 20.0);
        overlay(worker, r);
        assertEquals(r.amplitudeDbFs[FUND_BIN], r.fundamentalDbFs, 1.0e-12,
                "the fundamental's level is the overlaid bin's");
        assertEquals(Math.pow(10.0, r.amplitudeDbFs[FUND_BIN] / 20.0), r.fundamentalLinear,
                1.0e-12 * r.fundamentalLinear, "and the linear scalar is that level");
        assertEquals(FUND_DBFS, r.fundamentalDbFs, FUND_TOL_DB,
                "which is the synthesized fundamental, not the value planted before");
    }

    /** The fresh frame as the pilot path reads its phases: the three lines as
     *  unit cosines over one transform, each with the phase its position
     *  gives it {@code at} samples into the stream - which is the phase the
     *  windowed bin carries, a line's phase at the frame's start. */
    private double[] pilotSamples(double kappaF, double kappaP, double kappaP2,
            double at, double notchRad) {
        return pilotSamples(kappaF, kappaP, kappaP2, at, at, notchRad);
    }

    /** The same frame with the fundamental placed on its own: starting
     *  {@code fundAt} samples into the stream where the two pilots start at
     *  {@code at}, which is what a fundamental the capture disturbed alone -
     *  a glitch inside the window - looks like to the reader. */
    private double[] pilotSamples(double kappaF, double kappaP, double kappaP2,
            double at, double fundAt, double notchRad) {
        double[] s = new double[FFT_SIZE];
        for (int n = 0; n < s.length; n++) {
            s[n] = Math.cos(binPhase(kappaF, fundAt + n) + notchRad)
                 + Math.cos(binPhase(kappaP, at + n))
                 + Math.cos(binPhase(kappaP2, at + n));
        }
        return s;
    }

    /** One analysis cycle's spectrum as the pilot path reads it: at the
     *  three lines' bins the phasors the analysed frame carries there - the
     *  pilots' only when {@code pilotLine} / {@code pilot2Line} - which is
     *  where the path reads each line's phase and its level over the floor;
     *  every other bin the floor the line check measures against; the
     *  fundamental's refined frequency naming its bin, which is how the path
     *  finds it before an accumulator exists; the window the frame is read
     *  with and the sample rate the frame's time is counted with.  Nothing
     *  else of the result is read on that path. */
    private FftResult pilotSpectrum(double[] frame, boolean pilotLine, boolean pilot2Line) {
        FftResult r = new FftResult();
        r.fftSize              = FFT_SIZE;
        r.sampleRate           = SAMPLE_RATE;
        r.freqResolution       = (double) SAMPLE_RATE / FFT_SIZE;
        r.windowType           = WINDOW;
        r.fundamentalHzRefined = FUND_POS  * r.freqResolution;
        r.pilot1Bin            = PILOT_BIN;
        r.pilot2Bin            = PILOT2_BIN;
        r.re = new double[FFT_SIZE / 2 + 1];
        r.im = new double[FFT_SIZE / 2 + 1];
        Arrays.fill(r.re, PILOT_FLOOR);
        FftAnalyzer analyzer = new FftAnalyzer();
        writeLine(analyzer, r, frame, FUND_BIN);
        if (pilotLine)  writeLine(analyzer, r, frame, PILOT_BIN);
        if (pilot2Line) writeLine(analyzer, r, frame, PILOT2_BIN);
        return r;
    }

    /** The phasor the frame carries at {@code bin}, read the way the analysis
     *  reads a frame's bin, written into the result's bins. */
    private void writeLine(FftAnalyzer analyzer, FftResult r, double[] frame, int bin) {
        FftAnalyzer.BinPhasor p = analyzer.frameBinPhasor(frame, 0, FFT_SIZE, WINDOW, bin);
        r.re[bin] = p.re();
        r.im[bin] = p.im();
    }

    /** The phase a line at {@code kappa} bins shows when the analysis window
     *  starts {@code samples} samples into the capture: its own advance,
     *  reduced to one turn so the arithmetic stays exact however long the
     *  run. */
    private double binPhase(double kappa, double samples) {
        return 2.0 * Math.PI * Math.IEEEremainder(kappa * samples, FFT_SIZE) / FFT_SIZE;
    }

    /** The worker's own excess for this analysis cycle in mode
     *  REFERENCE_CHANNEL - the private method the analysis-cycle loop calls,
     *  reached by reflection. */
    private double cyclePhiF(FftAnalyzerWorker worker, FftResult r) {
        return call(worker, "cyclePhiF", FundamentalPhaseAlignment.REFERENCE_CHANNEL, r, true);
    }

    /**
     * An excess that is HELD: the notch's phase steps once per interval and
     * stands still in between, and the accumulator is fed the same reading
     * until the next step replaces it.  The fundamental region stays coherent
     * and the harmonics keep their levels - the held reading reaches the
     * fundamental alone.  Then the retroactive half: one more analysis
     * cycle with a non-zero excess is accumulated and taken back out again
     * the way a discontinuity gate takes it out, which has to leave the
     * accumulator exactly where it was - the subtractable ring slot must hold
     * what was added, the extra rotation included.
     */
    @Test
    void aHeldExcessKeepsTheLevelsAndLeavesNoResidue() {
        // One analysis cycle longer than the run: the last one is accumulated
        // and then removed again, which is the residue half of the case.
        int total = CYCLES * HOP + WIN_LEN;
        double[] excess   = new double[total];
        for (int k = 0; k < total; k++) {
            excess[k] = heldExcessRad(k);
        }
        double[] analysed = channel(SEED + 6, total, FUND_BIN, HARMONICS, 0.0, excess);

        FftAnalyzer       analyzer = new FftAnalyzer();
        FftAnalyzerWorker worker   = new FftAnalyzerWorker(null);
        double[] win = new double[WIN_LEN];
        FftResult r  = null;
        long base = 0;
        for (int t = 0; t < CYCLES; t++) {
            System.arraycopy(analysed, (int) base, win, 0, WIN_LEN);
            r = analyze(analyzer, win, base);
            accumulateCycle(worker, heldExcessRad(base), r, base);
            base += HOP;
        }
        double[] held = levels(worker, r);
        LOG.info("held   : fund={} H2..H9={}", fmt(held[0]), fmtAll(held));
        assertEquals(FUND_DBFS, held[0], FUND_TOL_DB,
                "the fundamental must hold its level on a held reading");
        for (int h = 2; h <= HARMONICS + 1; h++) {
            assertEquals(HARMONIC_DBFS, held[h - 1], LEVEL_TOL_DB,
                    "H" + h + " must be untouched by the held reading");
        }

        System.arraycopy(analysed, (int) base, win, 0, WIN_LEN);
        FftResult extra = analyze(analyzer, win, base);
        accumulateCycle(worker, heldExcessRad(base), extra, base);
        this.<CoherentAccumulator>field(worker, ACCUMULATOR).subtractPreviousCycles();
        double[] after = levels(worker, extra);
        for (int i = 0; i <= HARMONICS; i++) {
            assertEquals(held[i], after[i], IDENTICAL_TOL_DB,
                    (i == 0 ? "the fundamental" : "H" + (i + 1))
                            + " must be back where it was after the analysis cycle is removed");
        }
    }

    /** The fundamental's phase excess at {@code sample}: one step per
     *  interval, standing still in between.  The analysis cycle whose window
     *  starts at that sample is handed the same number, which is what holding
     *  a reading until the next one means. */
    private double heldExcessRad(long sample) {
        return HOLD_STEP_RAD * ((sample / HOP) / HOLD_CYCLES);
    }

    // ── Drivers ────────────────────────────────────────────────────────────

    /**
     * One accumulated analysis cycle: the cycle's phase excess goes into the
     * worker's private field - the state the accumulate step reads, and the
     * only way in now that the setter is gone - and then the step runs.
     *
     * <p>Nothing clears the field afterwards, which is why the rollback below
     * needs no write of its own: it runs with the excess still in force, the
     * situation on the worker's own discontinuity path, where the cycle that
     * fired the gate is removed while its excess is the one that was applied.
     */
    private void accumulateCycle(FftAnalyzerWorker worker, double phiF, FftResult r, long base) {
        try {
            FieldUtils.writeField(worker, PHI_F_FIELD, phiF, true);
        } catch (IllegalAccessException ex) {
            throw new AssertionError("the worker's " + PHI_F_FIELD + " is not reachable", ex);
        }
        call(worker, ACCUMULATE, r, base, true, Integer.MAX_VALUE);
    }

    /** Drives the real accumulator over the whole capture, one analysis cycle
     *  per hop, and reads the accumulated levels back through the worker's own
     *  overlay.  Returns {@code [fundamental, H2 .. H9]} in dBFS, taken at the
     *  tones' own bins - every tone is on-bin, so the peak bin IS the level. */
    private double[] accumulate(double[] analysed, CyclePhiF phiF) {
        FftAnalyzer       analyzer = new FftAnalyzer();
        FftAnalyzerWorker worker   = new FftAnalyzerWorker(null);     // headless
        double[] win = new double[WIN_LEN];
        FftResult r  = null;
        long base = 0;
        for (int t = 0; t < CYCLES; t++) {
            System.arraycopy(analysed, (int) base, win, 0, WIN_LEN);
            r = analyze(analyzer, win, base);
            accumulateCycle(worker, phiF.of(analyzer, r, base), r, base);
            base += HOP;
        }
        return levels(worker, r);
    }

    /** The accumulated levels read back through the accumulator's overlay:
     *  {@code [fundamental, H2 .. H9]} in dBFS, taken at the tones' own bins -
     *  every tone is on-bin, so the peak bin IS the level.  {@code r} is the
     *  slot the overlay writes into and carries no meaning of its own
     *  afterwards. */
    private double[] levels(FftAnalyzerWorker worker, FftResult r) {
        overlay(worker, r);
        double[] levels = new double[HARMONICS + 1];
        for (int i = 0; i <= HARMONICS; i++) {
            levels[i] = r.amplitudeDbFs[(i + 1) * FUND_BIN];
        }
        return levels;
    }

    /** The worker's accumulator, written back onto {@code r} - the display
     *  path's step. */
    private void overlay(FftAnalyzerWorker worker, FftResult r) {
        this.<CoherentAccumulator>field(worker, ACCUMULATOR).overlayOnto(r);
    }

    /** One analysis cycle's analysis, with the worker's own settings: the
     *  spectrum only (the stats are re-derived from the accumulator), single
     *  tone, no hint. */
    private FftResult analyze(FftAnalyzer analyzer, double[] win, long base) {
        analyzer.setSpectrumOnly(true);
        analyzer.setMultiTone(false);
        analyzer.setSecondToneHintHz(Double.NaN);
        return analyzer.analyze(win, SAMPLE_RATE, FFT_SIZE, HARMONICS, HARMONICS, WINDOW, OVERLAP,
                0.0, 0.0, true, Double.NaN, false, Double.NaN,
                new FftResult(), OVERLAP.hopSamples(FFT_SIZE));
    }

    /** The worker's phiF: the analysed spectrum's RAW fundamental-bin phase
     *  (before any accumulator de-rotation) minus the same bin's phase on the
     *  reference window, read by the analyser's single-bin recipe on the same
     *  frame, at the window's start. */
    private double phaseExcess(FftAnalyzer analyzer, FftResult r, double[] refWin) {
        FftAnalyzer.BinPhasor ref = analyzer.frameBinPhasor(refWin, 0, FFT_SIZE, WINDOW, FUND_BIN);
        return Math.IEEEremainder(
                Math.atan2(r.im[FUND_BIN], r.re[FUND_BIN])
                        - Math.atan2(ref.im(), ref.re()),
                2.0 * Math.PI);
    }

    // ── Synthetic capture ──────────────────────────────────────────────────

    /** One channel: a tone at {@code binPos} bins carrying {@code phase} plus
     *  the per-sample phase excess {@code excess} (null = none), the first
     *  {@code harmonics} rungs of its ladder at a fixed phase each, and white
     *  noise.  Plain trigonometry per sample - at these lengths the phase
     *  argument stays exact, so no recurrence is needed. */
    private double[] channel(long seed, int n, double binPos, int harmonics,
                             double phase, double[] excess) {
        double[] s   = new double[n];
        Random   rng = new Random(seed);
        double   w   = 2.0 * Math.PI * binPos / FFT_SIZE;
        double   a1  = Math.pow(10.0, FUND_DBFS / 20.0);
        double   ah  = Math.pow(10.0, HARMONIC_DBFS / 20.0);
        for (int k = 0; k < n; k++) {
            double v = a1 * Math.sin(w * k + phase + (excess != null ? excess[k] : 0.0));
            for (int h = 2; h <= harmonics + 1; h++) {
                v += ah * Math.sin(h * w * k + h * HARMONIC_PHASE_RAD);
            }
            s[k] = v + NOISE_STD * rng.nextGaussian();
        }
        return s;
    }

    /** The fundamental's phase excess per sample: a random walk clamped to
     *  ±{@link #WALK_MAX_RAD} whose drift RATE is itself mean-reverting, so
     *  the phase moves smoothly on the scale of one analysis window - the
     *  thermal drift of a notch, not a modulation.  At a rail the rate is
     *  clipped to the direction that leaves it, so the walk turns around
     *  instead of sticking. */
    private double[] phaseWalk(long seed, int n) {
        double[] psi  = new double[n];
        Random   rng  = new Random(seed);
        double   rate = 0.0;
        double   cur  = 0.0;
        for (int k = 0; k < n; k++) {
            rate += -rate / WALK_TAU_SAMPLES + WALK_RATE_STEP_RAD * rng.nextGaussian();
            cur  += rate;
            if (cur >  WALK_MAX_RAD) { cur =  WALK_MAX_RAD; rate = Math.min(rate, 0.0); }
            if (cur < -WALK_MAX_RAD) { cur = -WALK_MAX_RAD; rate = Math.max(rate, 0.0); }
            psi[k] = cur;
        }
        return psi;
    }

    /** The peak-to-peak excursion of a walk, in radians. */
    private double span(double[] walk) {
        double lo = walk[0];
        double hi = walk[0];
        for (double v : walk) {
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        return hi - lo;
    }

    private String fmt(double v) {
        return String.format("%.3f", v);
    }

    private String fmtAll(double[] levels) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < levels.length; i++) {
            sb.append(' ').append(fmt(levels[i]));
        }
        return sb.toString();
    }
}
