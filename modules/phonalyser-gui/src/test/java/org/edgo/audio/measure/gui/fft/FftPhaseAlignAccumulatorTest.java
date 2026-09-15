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
import org.edgo.audio.measure.enums.WindowType;
import org.edgo.audio.measure.fft.FftAnalyzer;
import org.edgo.audio.measure.fft.FftResult;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fundamental phase alignment inside the real cross-tick accumulator.
 *
 * <p>Vocabulary.  A <b>tick</b> is one analysis cycle: a window of
 * {@code fftSize + hop} samples is analysed as two overlapping frames and the
 * result is added to the cross-tick accumulator
 * ({@code accumulateIntoForeverBuffer}), which de-rotates it
 * onto the first tick's time origin and sums.  <b>phiF</b> is the
 * fundamental's phase excess for a tick, in radians: how far the analysed
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
 * spectrum's raw fundamental bin - and written into the worker's own per-tick
 * field, which is where the accumulator reads it.  Everything below that field
 * is production code, reached as it stands: the accumulator step, its rollback
 * and the field are private, and a productive class is not widened to let a
 * test in, so the harness goes through reflection.
 *
 * <p>Untagged on purpose: it is fast (a small FFT, a few hundred ticks) and
 * runs in the normal build, unlike the exploratory drift harness beside it.
 */
class FftPhaseAlignAccumulatorTest {

    private static final int        SAMPLE_RATE = 48_000;
    private static final int        FFT_SIZE    = 4096;
    private static final WindowType WINDOW      = WindowType.BH7;
    private static final FftOverlap OVERLAP     = FftOverlap.PCT_75;
    /** The overlap's hop, and with it the spacing of the two frames a tick
     *  analyses - the worker's own geometry. */
    private static final int        HOP         = FFT_SIZE / 4;
    private static final int        FRAMES      = 2;
    private static final int        WIN_LEN     = FFT_SIZE + HOP;
    private static final int        TICKS       = 300;

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
    /** Ticks between two probes of the periodical-sweep case, and the step the
     *  notch's phase takes in that time.  A probe's reading is HELD until the
     *  next one replaces it, so between probes the modelled notch stands still
     *  and the held value is exact - which is what the mode promises and what
     *  the schedule's phase budget keeps true on a real one. */
    private static final int        PROBE_EVERY_TICKS = 50;
    private static final double     PROBE_STEP_RAD    = Math.toRadians(4.0);

    /** The worker members this harness drives, by name because that is what
     *  reflection takes: the private field holding the next tick's phase
     *  excess, the private accumulate step, the private rollback the
     *  discontinuity path uses, and the private overlay that writes the
     *  accumulator back onto a result for the display path. */
    private static final String PHI_F_FIELD = "pendingPhiF";
    private static final String ACCUMULATE  = "accumulateIntoForeverBuffer";
    private static final String SUBTRACT    = "subtractPreviousTicks";
    private static final String OVERLAY     = "overlayAccumulatorOnto";

    private static final Logger LOG = LogManager.getLogger("FftPhaseAlign");

    /** One tick's phase excess in radians, as the driver supplies it. */
    @FunctionalInterface
    private interface TickPhiF {
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
        int total = (TICKS - 1) * HOP + WIN_LEN;
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
        int total = (TICKS - 1) * HOP + WIN_LEN;
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
     * every tick - constant, and free of the per-tick hop advance.  Off-bin on
     * purpose: with an integer bin and this hop the analyser's de-rotation is
     * the identity and a wrong recipe would pass unnoticed.
     */
    @Test
    void phaseExcessIsTheChannelDifference() {
        final double offBinPos   = FUND_BIN + 0.3;
        final double analysedPhi = 1.1;
        final double referencePhi = -0.4;
        int total = (TICKS - 1) * HOP + WIN_LEN;
        double[] analysed  = channel(SEED + 4, total, offBinPos, HARMONICS, analysedPhi, null);
        double[] reference = channel(SEED + 5, total, offBinPos, 0, referencePhi, null);

        FftAnalyzer analyzer = new FftAnalyzer();
        double[] win    = new double[WIN_LEN];
        double[] refWin = new double[WIN_LEN];
        double expected = Math.IEEEremainder(analysedPhi - referencePhi, 2.0 * Math.PI);
        long base = 0;
        for (int t = 0; t < TICKS; t++) {
            System.arraycopy(analysed,  (int) base, win,    0, WIN_LEN);
            System.arraycopy(reference, (int) base, refWin, 0, WIN_LEN);
            FftResult r = analyze(analyzer, win, base);
            double phiF = phaseExcess(analyzer, r, refWin);
            assertEquals(expected, phiF, 1.0e-3,
                    "phiF must be the two channels' fundamental phase difference at tick " + t);
            base += HOP;
        }
    }

    /**
     * Decision case 3, the periodical-sweep mode: the notch's phase steps once
     * per probe interval and stands still between, and the accumulator is fed
     * the reading a probe HELD from one interval to the next.  The fundamental
     * region stays coherent and the harmonics keep their levels - the held
     * reading reaches the fundamental alone.  Then the retroactive half: one
     * more tick with a non-zero excess is accumulated and taken back out again
     * the way a discontinuity gate takes it out, which has to leave the
     * accumulator exactly where it was - the subtractable ring slot must hold
     * what was added, the extra rotation included.
     */
    @Test
    void heldSweepReadingKeepsTheLevelsAndLeavesNoResidue() {
        // One tick longer than the run: the last one is accumulated and then
        // removed again, which is the residue half of the case.
        int total = TICKS * HOP + WIN_LEN;
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
        for (int t = 0; t < TICKS; t++) {
            System.arraycopy(analysed, (int) base, win, 0, WIN_LEN);
            r = analyze(analyzer, win, base);
            accumulateTick(worker, heldExcessRad(base), r, base);
            base += HOP;
        }
        double[] held = levels(worker, r);
        LOG.info("held   : fund={} H2..H9={}", fmt(held[0]), fmtAll(held));
        assertEquals(FUND_DBFS, held[0], FUND_TOL_DB,
                "the fundamental must hold its level on a held probe reading");
        for (int h = 2; h <= HARMONICS + 1; h++) {
            assertEquals(HARMONIC_DBFS, held[h - 1], LEVEL_TOL_DB,
                    "H" + h + " must be untouched by the held reading");
        }

        System.arraycopy(analysed, (int) base, win, 0, WIN_LEN);
        FftResult extra = analyze(analyzer, win, base);
        accumulateTick(worker, heldExcessRad(base), extra, base);
        call(worker, SUBTRACT);
        double[] after = levels(worker, extra);
        for (int i = 0; i <= HARMONICS; i++) {
            assertEquals(held[i], after[i], IDENTICAL_TOL_DB,
                    (i == 0 ? "the fundamental" : "H" + (i + 1))
                            + " must be back where it was after the tick is removed");
        }
    }

    /** The fundamental's phase excess at {@code sample}: one step per probe
     *  interval, standing still in between.  The tick whose window starts at
     *  that sample is handed the same number, which is what holding a probe's
     *  reading until the next probe means. */
    private double heldExcessRad(long sample) {
        return PROBE_STEP_RAD * ((sample / HOP) / PROBE_EVERY_TICKS);
    }

    // ── Drivers ────────────────────────────────────────────────────────────

    /**
     * The one place this class reaches into the worker: the accumulate step,
     * its rollback and the overlay that reads the accumulator back are all
     * private - a productive class is not widened to let a test in - so a
     * renamed member fails here and not at every call site.
     */
    private void call(FftAnalyzerWorker worker, String method, Object... args) {
        try {
            MethodUtils.invokeMethod(worker, true, method, args);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("the worker's " + method + " is not reachable", ex);
        }
    }

    /**
     * One accumulated tick: the tick's phase excess goes into the worker's
     * private field - the state the accumulate step reads, and the only way
     * in now that the setter is gone - and then the step runs.
     *
     * <p>Nothing clears the field afterwards, which is why the rollback below
     * needs no write of its own: it runs with the excess still in force, the
     * situation on the worker's own discontinuity path, where the tick that
     * fired the gate is removed while its excess is the one that was applied.
     */
    private void accumulateTick(FftAnalyzerWorker worker, double phiF, FftResult r, long base) {
        try {
            FieldUtils.writeField(worker, PHI_F_FIELD, phiF, true);
        } catch (IllegalAccessException ex) {
            throw new AssertionError("the worker's " + PHI_F_FIELD + " is not reachable", ex);
        }
        call(worker, ACCUMULATE, r, base, true, Integer.MAX_VALUE);
    }

    /** Drives the real accumulator over the whole capture, one tick per hop,
     *  and reads the accumulated levels back through the worker's own overlay.
     *  Returns {@code [fundamental, H2 .. H9]} in dBFS, taken at the tones' own
     *  bins - every tone is on-bin, so the peak bin IS the level. */
    private double[] accumulate(double[] analysed, TickPhiF phiF) {
        FftAnalyzer       analyzer = new FftAnalyzer();
        FftAnalyzerWorker worker   = new FftAnalyzerWorker(null);     // headless
        double[] win = new double[WIN_LEN];
        FftResult r  = null;
        long base = 0;
        for (int t = 0; t < TICKS; t++) {
            System.arraycopy(analysed, (int) base, win, 0, WIN_LEN);
            r = analyze(analyzer, win, base);
            accumulateTick(worker, phiF.of(analyzer, r, base), r, base);
            base += HOP;
        }
        return levels(worker, r);
    }

    /** The accumulated levels read back through the worker's own overlay:
     *  {@code [fundamental, H2 .. H9]} in dBFS, taken at the tones' own bins -
     *  every tone is on-bin, so the peak bin IS the level.  {@code r} is the
     *  slot the overlay writes into and carries no meaning of its own
     *  afterwards. */
    private double[] levels(FftAnalyzerWorker worker, FftResult r) {
        call(worker, OVERLAY, r);
        double[] levels = new double[HARMONICS + 1];
        for (int i = 0; i <= HARMONICS; i++) {
            levels[i] = r.amplitudeDbFs[(i + 1) * FUND_BIN];
        }
        return levels;
    }

    /** One tick's analysis, with the worker's own settings: the spectrum only
     *  (the stats are re-derived from the accumulator), single tone, no hint. */
    private FftResult analyze(FftAnalyzer analyzer, double[] win, long base) {
        analyzer.setSamplesAbsStart(base);
        analyzer.setSpectrumOnly(true);
        analyzer.setMultiTone(false);
        analyzer.setSecondToneHintHz(Double.NaN);
        return analyzer.analyze(win, SAMPLE_RATE, FFT_SIZE, HARMONICS, WINDOW, OVERLAP,
                0.0, 0.0, true, Double.NaN, false, Double.NaN);
    }

    /** The worker's phiF: the analysed spectrum's RAW fundamental-bin phase
     *  (before any accumulator de-rotation) minus the same bin's phase on the
     *  reference window, produced by the analyser's single-bin recipe over the
     *  same span, the same frame geometry and the same de-rotation frequency. */
    private double phaseExcess(FftAnalyzer analyzer, FftResult r, double[] refWin) {
        double kFractional = r.fundamentalHzRefined / r.freqResolution;
        FftAnalyzer.BinPhasor ref = analyzer.binPhasor(refWin, FFT_SIZE, WINDOW,
                FUND_BIN, HOP, FRAMES, kFractional);
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
