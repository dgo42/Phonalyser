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

package org.edgo.audio.measure.gui.fft.predistortion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.DoubleFunction;

import org.edgo.audio.measure.dsp.HarmonicCompensation;
import org.edgo.audio.measure.dsp.IntermodCompensation;
import org.edgo.audio.measure.dsp.IntermodCompensation.GeneratorCorrections;
import org.edgo.audio.measure.enums.AlignGenerator;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.gui.common.CorrectionStore;
import org.edgo.audio.measure.gui.fft.predistortion.PredistortionEngine.LineReadout;
import org.edgo.audio.measure.gui.fft.predistortion.PredistortionEngine.Listener;
import org.edgo.audio.measure.gui.fft.predistortion.PredistortionEngine.StopReason;
import org.edgo.audio.measure.preferences.Preferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.edgo.audio.measure.gui.fft.predistortion.PredistortionEngine.SKIPPED_FRAMES;
import static org.edgo.audio.measure.gui.fft.predistortion.PredistortionEngine.WINDOW_VALUES;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one-shot predistortion engine's decision on synthetic results: the
 * settle, the results it does not take, the clearance gate, the wobble, the
 * correction built on the settle and the stop by hand.
 *
 * <p>Each result carries its lines on a flat noise floor, so the local band
 * top beside a line is the floor itself (median plus four MAD of equal bins)
 * and a line's clearance is its level less the floor.  Both tones' DAC phases
 * stand at minus a quarter turn, which zeroes the frame reference of both
 * correction sets, so a line's phase is the phase of its raw peak.  The
 * listener runs on the calling thread.
 */
class PredistortionEngineTest {

    private static final int    FFT_SIZE       = 4096;
    private static final int    BINS           = FFT_SIZE / 2 + 1;
    private static final double BIN_HZ         = 10.0;
    private static final int    SAMPLE_RATE    = (int) (FFT_SIZE * BIN_HZ);
    /** The window's equivalent noise bandwidth, bins - how far the floor
     *  window of a line keeps away from the line's own bin. */
    private static final double NENBW_BINS     = 1.5;
    private static final double FLOOR_DBFS     = -140.0;
    private static final double DB_PER_DECADE  = 20.0;
    private static final double DECADE         = 10.0;
    /** H2 .. H9, eight lines. */
    private static final int    HARMONIC_LINES = 8;
    /** The lines of H2 and H3 in the engine's order. */
    private static final int    H2_LINE        = 0;
    private static final int    H3_LINE        = 1;
    private static final double F0_HZ          = 1000.0;
    private static final double F1_HZ          = 9000.0;
    private static final double F2_HZ          = 10000.0;
    /** The intermodulation grid of the plan in its order, and the names the
     *  wizard shows for it. */
    private static final int[]    PRODUCT_A     = { 2, -1, 3, -2, 4, -3, 5, -4 };
    private static final int[]    PRODUCT_B     = { -1, 2, -2, 3, -3, 4, -4, 5 };
    private static final String[] PRODUCT_NAMES = {
        "2F1-F2", "2F2-F1", "3F1-2F2", "3F2-2F1", "4F1-3F2", "4F2-3F1", "5F1-4F2", "5F2-4F1",
    };
    private static final double CLEAR_DB          = 40.0;
    private static final double UNDER_GATE_DB     = 2.0;
    /** Per-line clearance step of the correction cases, dB, so the lines
     *  carry distinct levels. */
    private static final double CLEARANCE_STEP_DB = 2.0;
    private static final double PERCENT           = 100.0;
    /** The level of a line {@link #CLEAR_DB} over the floor, percent of the
     *  single tone's 0 dBFS fundamental. */
    private static final double CLEAR_LINE_PCT    = PERCENT * Math.pow(DECADE, (FLOOR_DBFS + CLEAR_DB) / DB_PER_DECADE);
    /** The limit of a single-tone line at {@link #CLEAR_DB} under
     *  {@link #TARGET_PCT}, degrees. */
    private static final double LIMIT_DEG         = 0.0158;
    /** The phase's part of a line's share of the target, as the engine
     *  applies it. */
    private static final double PHASE_SHARE       = 0.2;
    /** The target THD, percent, the cases run at: the phase's part of a
     *  line's share is 2 L sin(limit / 2), the target the eight shares summed
     *  in power. */
    private static final double TARGET_PCT        = Math.sqrt(HARMONIC_LINES) * 2.0 * CLEAR_LINE_PCT
            * Math.sin(Math.toRadians(LIMIT_DEG) / 2.0) / PHASE_SHARE;
    /** The share case: its target puts a line's share at the level of a line
     *  {@link #SHARE_CLEARANCE_DB} over the floor; H3 stands gated but under
     *  it. */
    private static final double SHARE_CLEARANCE_DB = 20.0;
    private static final double UNDER_SHARE_DB     = 10.0;
    /** A dual tone's manual fundamental, dBFS - over the measured tones -
     *  and the share of it the configured split gives F1, percent. */
    private static final double MANUAL_FUND_DBFS   = 6.0;
    private static final double SPLIT_PCT          = 70.0;
    /** A dual tone's measured reference without a manual fundamental: F1 and
     *  F2 at 0 dBFS each through a unit response, summed. */
    private static final double MEASURED_TONES_LIN = 2.0;
    /** Both tones' DAC phase: -(phase + pi/2) is the frame reference, zero
     *  here. */
    private static final double DAC_PHASE_RAD     = -Math.PI / 2.0;
    /** The lines' phases: a distinct one per line, none near half a turn. */
    private static final double FIRST_PHASE_DEG   = 20.0;
    private static final double PHASE_STEP_DEG    = 37.0;
    private static final double QUARTER_TURN_DEG  = 90.0;
    /** A line under the gate swings this far either way of its phase on
     *  every result. */
    private static final double SWING_DEG         = 45.0;
    private static final double ONE_DEGREE        = 1.0;
    /** Width of the band the drawn phases stand in, degrees, and the centre
     *  of a uniform draw. */
    private static final double SPREAD_DEG        = 0.001;
    private static final double DRAW_CENTRE       = 0.5;
    private static final long   SEED              = 42L;
    /** Results fed past the end of a run, or before a stop. */
    private static final int    EXTRA_RESULTS     = 10;
    private static final double WOBBLE_TOLERANCE_DEG = 1.0e-9;
    /** How close the one-degree case comes to its first-order share. */
    private static final double SHARE_TOLERANCE_DEG  = 1.0e-6;
    /** Relative rounding of a mean over the window's values. */
    private static final double RELATIVE_TOLERANCE   = 1.0e-12;
    private static final String STORE_LABEL          = "FFT";

    /** Records the listener's callbacks in the order they arrive. */
    private static final class Recorder implements Listener {

        private int started;
        private final List<StopReason> reasons = new ArrayList<>();
        private final List<Boolean>    results = new ArrayList<>();

        @Override
        public void onStarted() {
            started++;
        }

        @Override
        public void onFinished(StopReason reason, boolean hasResult) {
            reasons.add(reason);
            results.add(hasResult);
        }
    }

    /** The least averages the cases run at: a taken result carries it. */
    private static final int MIN_AVERAGES = 1000;

    private final Recorder recorder = new Recorder();
    private boolean        previousTransient;
    private GenSignalForm  previousForm;
    private double         previousTargetPct;
    private int            previousMinAverages;
    private double         previousSplitPct;
    private boolean        previousCoherent;
    private double         previousAverages;
    private boolean        previousFundFromGen;
    private AlignGenerator previousAlign;

    /** The engine reads the form, the target THD, the least averages and the dual-tone split,
     *  and its start writes four FFT settings - all through the live
     *  singleton. */
    @BeforeEach
    void preferencesSet() {
        Preferences prefs = Preferences.instance();
        previousTransient   = prefs.isTransientMode();
        prefs.setTransientMode(true);
        previousForm        = prefs.getGenSignalForm();
        previousTargetPct   = prefs.getPredistortionTargetPct();
        previousMinAverages = prefs.getPredistortionMinAverages();
        previousSplitPct    = prefs.getGenDualToneSplitPct();
        previousCoherent    = prefs.isFftCoherentAveraging();
        previousAverages    = prefs.getFftAverages();
        previousFundFromGen = prefs.isFftFundFromGenerator();
        previousAlign       = prefs.getFftAlignGenerator();
        prefs.setPredistortionTargetPct(TARGET_PCT);
        prefs.setPredistortionMinAverages(MIN_AVERAGES);
    }

    @AfterEach
    void preferencesRestored() {
        Preferences prefs = Preferences.instance();
        prefs.setGenSignalForm(previousForm);
        prefs.setPredistortionTargetPct(previousTargetPct);
        prefs.setPredistortionMinAverages(previousMinAverages);
        prefs.setGenDualToneSplitPct(previousSplitPct);
        prefs.setFftCoherentAveraging(previousCoherent);
        prefs.setFftAverages(previousAverages);
        prefs.setFftFundFromGenerator(previousFundFromGen);
        prefs.setFftAlignGenerator(previousAlign);
        prefs.setTransientMode(previousTransient);
    }

    // -------------------------------------------------------------------------
    // Settle
    // -------------------------------------------------------------------------

    @Test
    void theRunSettlesExactlyOnceAndNeverAfter() {
        PredistortionEngine engine = started(GenSignalForm.SINE);
        FftResult r = harmonics(distinctPhases(HARMONIC_LINES), uniform(HARMONIC_LINES, CLEAR_DB));
        for (int n = 1; n < WINDOW_VALUES; n++) {
            assertFalse(engine.onResult(r), "result " + n + " leaves the windows short");
        }
        assertTrue(engine.onResult(r), "the result that fills the windows settles");
        for (int n = 0; n < EXTRA_RESULTS; n++) {
            assertFalse(engine.onResult(r), "a call after the end");
        }
        assertEquals(1, recorder.started);
        assertEquals(List.of(StopReason.SETTLED), recorder.reasons);
        assertEquals(List.of(true), recorder.results);
    }

    @Test
    void aResultWithoutTheDacPhaseOrOfTheFirstFramesIsNotTaken() {
        PredistortionEngine engine = started(GenSignalForm.SINE);
        double[] phaseDeg    = distinctPhases(HARMONIC_LINES);
        double[] clearanceDb = uniform(HARMONIC_LINES, CLEAR_DB);
        FftResult withoutPhase = harmonics(phaseDeg, clearanceDb);
        withoutPhase.dacFundPhaseRad = Double.NaN;
        FftResult ofTheFirstFrames = harmonics(phaseDeg, clearanceDb);
        ofTheFirstFrames.frameCount = SKIPPED_FRAMES - 1;
        for (int n = 0; n < WINDOW_VALUES; n++) {
            assertFalse(engine.onResult(withoutPhase), "no DAC phase");
            assertFalse(engine.onResult(ofTheFirstFrames), "one of the first frames");
        }
        for (LineReadout line : engine.readouts()) {
            assertFalse(line.windowFull(), line.name() + " took none of them");
        }
        FftResult taken = harmonics(phaseDeg, clearanceDb);
        for (int n = 1; n < WINDOW_VALUES; n++) {
            assertFalse(engine.onResult(taken), "taken result " + n);
        }
        assertTrue(engine.onResult(taken), "the window's worth of taken results settles");
    }

    @Test
    void aResultUnderTheLeastAveragesIsTakenButDoesNotSettle() {
        PredistortionEngine engine = started(GenSignalForm.SINE);
        double[] phaseDeg    = distinctPhases(HARMONIC_LINES);
        double[] clearanceDb = uniform(HARMONIC_LINES, CLEAR_DB);
        FftResult shallow = harmonics(phaseDeg, clearanceDb);
        shallow.frameCount = MIN_AVERAGES - 1;
        for (int n = 1; n <= WINDOW_VALUES; n++) {
            assertFalse(engine.onResult(shallow), "result " + n + " averages too few frames");
        }
        for (LineReadout line : engine.readouts()) {
            assertTrue(line.windowFull(), line.name() + " took every one of them");
        }
        assertTrue(engine.onResult(harmonics(phaseDeg, clearanceDb)), "the first result with enough averages settles");
    }

    // -------------------------------------------------------------------------
    // Clearance gate
    // -------------------------------------------------------------------------

    @Test
    void aLineUnderTheGateNeitherBlocksTheSettleNorEntersTheCorrection() {
        PredistortionEngine engine = started(GenSignalForm.SINE);
        double[] phaseDeg    = distinctPhases(HARMONIC_LINES);
        double[] clearanceDb = uniform(HARMONIC_LINES, CLEAR_DB);
        clearanceDb[H3_LINE] = UNDER_GATE_DB;
        double h3Deg = phaseDeg[H3_LINE];
        for (int n = 1; n <= WINDOW_VALUES; n++) {
            phaseDeg[H3_LINE] = h3Deg + (n % 2 == 0 ? SWING_DEG : -SWING_DEG);
            assertEquals(n == WINDOW_VALUES, engine.onResult(harmonics(phaseDeg, clearanceDb)), "result " + n);
        }
        LineReadout h3 = engine.readouts().get(H3_LINE);
        assertFalse(h3.gated(), "H3 under the gate");
        assertTrue(h3.windowFull(), "its window is fed all the same");
        assertTrue(h3.wobbleDeg() > h3.limitDeg(), "a wobble far over its limit, " + h3.wobbleDeg() + " deg");

        HarmonicCompensation correction = engine.getHarmonicsInForce();
        assertArrayEquals(new double[] { 0.0, 0.0 }, correction.correction(H3_LINE), 0.0, "H3 gets no entry");
        for (int h = 0; h < HARMONIC_LINES; h++) {
            if (h == H3_LINE) continue;
            double[] entry = correction.correction(h);
            assertNotEquals(0.0, Math.hypot(entry[0], entry[1]), "H" + (h + 2) + " gets its entry");
        }
    }

    @Test
    void aLineThatGrowsOverTheGateIsAwaitedFromThatResultOn() {
        PredistortionEngine engine = started(GenSignalForm.SINE);
        double[] phaseDeg    = distinctPhases(HARMONIC_LINES);
        double[] clearanceDb = uniform(HARMONIC_LINES, CLEAR_DB);
        double h3Deg = phaseDeg[H3_LINE];
        // Under the gate H3 stands a quarter turn off the phase it keeps once
        // it has grown over the gate.
        phaseDeg[H3_LINE]    = h3Deg + QUARTER_TURN_DEG;
        clearanceDb[H3_LINE] = UNDER_GATE_DB;
        FftResult under = harmonics(phaseDeg, clearanceDb);
        for (int n = 1; n < WINDOW_VALUES; n++) {
            assertFalse(engine.onResult(under), "result " + n);
        }
        phaseDeg[H3_LINE]    = h3Deg;
        clearanceDb[H3_LINE] = CLEAR_DB;
        FftResult over = harmonics(phaseDeg, clearanceDb);
        assertFalse(engine.onResult(over), "H3 holds the run on the result it grew over the gate");
        List<LineReadout> readouts = engine.readouts();
        assertTrue(readouts.get(H3_LINE).gated(), "H3 gated");
        for (int h = 0; h < HARMONIC_LINES; h++) {
            if (h == H3_LINE) continue;
            LineReadout line = readouts.get(h);
            assertTrue(line.windowFull() && line.wobbleDeg() <= line.limitDeg(), line.name() + " within its limit");
        }
        // The run settles on the first result whose window of H3 holds no
        // value from under the gate: the newest values alike, the oldest a
        // quarter turn off pulls the full window's mean by atan(1/255), about
        // 0.22 deg, over the limit of 0.016 deg at 40 dB.
        int settlingResult = 2 * WINDOW_VALUES - 1;
        for (int n = WINDOW_VALUES + 1; n < settlingResult; n++) {
            assertFalse(engine.onResult(over), "result " + n + " still holds a value of H3 from under the gate");
        }
        assertTrue(engine.onResult(over), "result " + settlingResult);
        double[] entry = engine.getHarmonicsInForce().correction(H3_LINE);
        assertNotEquals(0.0, Math.hypot(entry[0], entry[1]), "H3 gets its entry");
    }

    @Test
    void aLineTheResultDoesNotCarryIsSkippedOnIt() {
        PredistortionEngine engine = started(GenSignalForm.SINE);
        FftResult r = harmonics(distinctPhases(HARMONIC_LINES), uniform(HARMONIC_LINES, CLEAR_DB));
        // The result did not locate H3: it carries neither its frequency nor
        // its bin.
        r.harmonicHz[H3_LINE]   = 0.0;
        r.harmonicBins[H3_LINE] = 0;
        settle(engine, r);
        List<LineReadout> readouts = engine.readouts();
        LineReadout h3 = readouts.get(H3_LINE);
        assertFalse(h3.gated(), "H3 not gated");
        assertFalse(h3.windowFull(), "nothing entered the window of H3");
        for (int h = 0; h < HARMONIC_LINES; h++) {
            if (h == H3_LINE) continue;
            LineReadout line = readouts.get(h);
            assertTrue(line.windowFull(), line.name() + " window full");
        }
        assertArrayEquals(new double[] { 0.0, 0.0 }, engine.getHarmonicsInForce().correction(H3_LINE), 0.0,
                "H3 gets no entry");
    }

    @Test
    void aLineMissingOnTheSettlingResultGetsNoEntry() {
        PredistortionEngine engine = started(GenSignalForm.SINE);
        double[] phaseDeg    = distinctPhases(HARMONIC_LINES);
        double[] clearanceDb = uniform(HARMONIC_LINES, CLEAR_DB);
        FftResult carried = harmonics(phaseDeg, clearanceDb);
        for (int n = 1; n < WINDOW_VALUES; n++) {
            assertFalse(engine.onResult(carried), "result " + n + " leaves the windows short");
        }
        // The settling result does not locate H3: the window of H3 stays one
        // value short, and the frequency and the clearance it held from the
        // results before stand for nothing.
        FftResult withoutH3 = harmonics(phaseDeg, clearanceDb);
        withoutH3.harmonicHz[H3_LINE]   = 0.0;
        withoutH3.harmonicBins[H3_LINE] = 0;
        assertTrue(engine.onResult(withoutH3), "the other lines' full windows settle");
        assertArrayEquals(new double[] { 0.0, 0.0 }, engine.getHarmonicsInForce().correction(H3_LINE), 0.0,
                "H3 gets no entry");
    }

    // -------------------------------------------------------------------------
    // Share of the target
    // -------------------------------------------------------------------------

    @Test
    void aGatedLineUnderItsShareOfTheTargetIsNotAwaited() {
        Preferences.instance().setPredistortionTargetPct(Math.sqrt(HARMONIC_LINES)
                * PERCENT * Math.pow(DECADE, (FLOOR_DBFS + SHARE_CLEARANCE_DB) / DB_PER_DECADE) / PHASE_SHARE);
        PredistortionEngine engine = started(GenSignalForm.SINE);
        double[] phaseDeg    = distinctPhases(HARMONIC_LINES);
        double[] clearanceDb = uniform(HARMONIC_LINES, CLEAR_DB);
        clearanceDb[H3_LINE] = UNDER_SHARE_DB;
        double h3Deg = phaseDeg[H3_LINE];
        // Awaited, H3 would hold the run with a wobble far over any limit.
        for (int n = 1; n <= WINDOW_VALUES; n++) {
            phaseDeg[H3_LINE] = h3Deg + (n % 2 == 0 ? SWING_DEG : -SWING_DEG);
            assertEquals(n == WINDOW_VALUES, engine.onResult(harmonics(phaseDeg, clearanceDb)), "result " + n);
        }
        List<LineReadout> readouts = engine.readouts();
        LineReadout h3 = readouts.get(H3_LINE);
        assertTrue(h3.gated(), "H3 over the clearance gate");
        assertTrue(Double.isNaN(h3.limitDeg()), "H3 under its share has no limit");
        assertTrue(Double.isFinite(readouts.get(H2_LINE).limitDeg()), "H2 over its share has its limit");
    }

    // -------------------------------------------------------------------------
    // Wobble
    // -------------------------------------------------------------------------

    @Test
    void aConstantPhaseWobblesByZero() {
        PredistortionEngine engine = started(GenSignalForm.SINE);
        FftResult r = harmonics(distinctPhases(HARMONIC_LINES), uniform(HARMONIC_LINES, CLEAR_DB));
        for (LineReadout line : engine.readouts()) {
            assertTrue(Double.isNaN(line.wobbleDeg()), line.name() + " has no wobble before its first value");
        }
        engine.onResult(r);
        for (LineReadout line : engine.readouts()) {
            assertTrue(Double.isNaN(line.wobbleDeg()), line.name() + " has no wobble on its first value");
        }
        for (int n = 2; n <= WINDOW_VALUES; n++) {
            engine.onResult(r);
            for (LineReadout line : engine.readouts()) {
                assertEquals(0.0, line.wobbleDeg(), WOBBLE_TOLERANCE_DEG, line.name() + " on value " + n);
            }
        }
        for (LineReadout line : engine.readouts()) {
            assertEquals(LIMIT_DEG, line.limitDeg(), WOBBLE_TOLERANCE_DEG, line.name() + " limit");
        }
    }

    @Test
    void theNewestValueOneDegreeOffWobblesByOneDegreeLessItsShare() {
        PredistortionEngine engine = started(GenSignalForm.SINE);
        double[] phaseDeg    = distinctPhases(HARMONIC_LINES);
        double[] clearanceDb = uniform(HARMONIC_LINES, CLEAR_DB);
        FftResult history = harmonics(phaseDeg, clearanceDb);
        for (int n = 1; n < WINDOW_VALUES; n++) {
            assertFalse(engine.onResult(history), "result " + n);
        }
        phaseDeg[H2_LINE] += ONE_DEGREE;
        assertFalse(engine.onResult(harmonics(phaseDeg, clearanceDb)), "a wobble of a degree is over the limit");
        // The full window's mean stands off the history by the newest value's
        // share of its degree, atan2(sin 1, 255 + cos 1) - 1/256 of a degree
        // to first order; the shortest tail, the newest value alone, stands
        // on it.
        double oneDegreeRad = Math.toRadians(ONE_DEGREE);
        double shareDeg = Math.toDegrees(Math.atan2(Math.sin(oneDegreeRad),
                (WINDOW_VALUES - 1) + Math.cos(oneDegreeRad)));
        double wobbleDeg = engine.readouts().get(H2_LINE).wobbleDeg();
        assertEquals(ONE_DEGREE - shareDeg, wobbleDeg, WOBBLE_TOLERANCE_DEG);
        assertEquals(ONE_DEGREE - ONE_DEGREE / WINDOW_VALUES, wobbleDeg, SHARE_TOLERANCE_DEG);
    }

    @Test
    void valuesWithinAThousandthOfADegreeWobbleByLessThanThat() {
        PredistortionEngine engine = started(GenSignalForm.SINE);
        double[] phaseDeg    = distinctPhases(HARMONIC_LINES);
        double[] clearanceDb = uniform(HARMONIC_LINES, CLEAR_DB);
        double meanDeg = phaseDeg[H2_LINE];
        Random draws = new Random(SEED);
        boolean settled = false;
        for (int n = 0; n < WINDOW_VALUES; n++) {
            phaseDeg[H2_LINE] = meanDeg + (draws.nextDouble() - DRAW_CENTRE) * SPREAD_DEG;
            settled = engine.onResult(harmonics(phaseDeg, clearanceDb));
        }
        double wobbleDeg = engine.readouts().get(H2_LINE).wobbleDeg();
        assertTrue(wobbleDeg < SPREAD_DEG, "wobble " + wobbleDeg + " deg");
        assertTrue(settled, "within the limit of " + LIMIT_DEG + " deg the run settles");
    }

    // -------------------------------------------------------------------------
    // Correction
    // -------------------------------------------------------------------------

    @Test
    void theSettleBuildsTheCorrectionOneResultGives() {
        PredistortionEngine engine = started(GenSignalForm.SINE);
        FftResult r = harmonics(distinctPhases(HARMONIC_LINES), steppedClearances(HARMONIC_LINES));
        settle(engine, r);
        // The engine's correction store is empty: no phase is de-embedded.
        HarmonicCompensation expected = new HarmonicCompensation(HARMONIC_LINES);
        expected.accumulate(expected.measurePhasors(r, 0.0), 1.0);
        HarmonicCompensation built = engine.getHarmonicsInForce();
        assertNotNull(built, "the settle built a correction");
        for (int h = 0; h < HARMONIC_LINES; h++) {
            double[] want = expected.correction(h);
            double[] got  = built.correction(h);
            double magnitude = Math.hypot(want[0], want[1]);
            assertNotEquals(0.0, magnitude, "H" + (h + 2) + " is gated");
            assertEquals(want[0], got[0], RELATIVE_TOLERANCE * magnitude, "H" + (h + 2) + " real part");
            assertEquals(want[1], got[1], RELATIVE_TOLERANCE * magnitude, "H" + (h + 2) + " imaginary part");
        }
        assertEquals(List.of(true), recorder.results);
    }

    @Test
    void aDualToneRunSettlesOnTheProductsAndBuildsTheirCorrection() {
        PredistortionEngine engine = started(GenSignalForm.DUAL_TONE);
        assertTrue(engine.isDualTone());
        List<String> names = engine.readouts().stream().map(LineReadout::name).toList();
        assertEquals(List.of(PRODUCT_NAMES), names);
        int productCount = PRODUCT_A.length;
        Preferences.instance().setGenDualToneSplitPct(SPLIT_PCT);
        double[] clearanceDb = steppedClearances(productCount);
        FftResult r = products(distinctPhases(productCount), clearanceDb);
        r.fundamentalTrueDbFs = MANUAL_FUND_DBFS;
        settle(engine, r);
        IntermodCompensation expected = new IntermodCompensation();
        expected.accumulate(expected.measurePhasors(r, F1_HZ, F2_HZ, 0.0, 0.0), 1.0);
        IntermodCompensation built = engine.getIntermodInForce();
        assertNotNull(built, "the settle built a correction");
        // Read back through unit response and unit levels: the entries are
        // the accumulated phasors themselves.
        DoubleFunction<double[]> unitResponse = f -> new double[] { 1.0, 0.0 };
        GeneratorCorrections want = expected.toGeneratorCorrections(unitResponse, 1.0, 1.0);
        GeneratorCorrections got  = built.toGeneratorCorrections(unitResponse, 1.0, 1.0);
        assertEquals(productCount, want.ampRatios().length, "every product is gated");
        assertArrayEquals(want.coefA(), got.coefA());
        assertArrayEquals(want.coefB(), got.coefB());
        for (int g = 0; g < productCount; g++) {
            assertEquals(want.ampRatios()[g], got.ampRatios()[g], RELATIVE_TOLERANCE * want.ampRatios()[g],
                    PRODUCT_NAMES[g] + " level");
            assertEquals(want.phiInits()[g], got.phiInits()[g], RELATIVE_TOLERANCE, PRODUCT_NAMES[g] + " phase");
        }
        assertEquals(List.of(StopReason.SETTLED), recorder.reasons);
        assertEquals(List.of(true), recorder.results);
        // The products' levels are percent of the manual fundamental split
        // between the tones as configured, the two tones summed.
        double manualLin = Math.pow(DECADE, MANUAL_FUND_DBFS / DB_PER_DECADE);
        double w1 = SPLIT_PCT / PERCENT;
        double w2 = 1.0 - w1;
        double f1Lin = manualLin * w1 / Math.hypot(w1, w2);
        double f2Lin = manualLin * w2 / Math.hypot(w1, w2);
        assertProductLimits(engine, clearanceDb, f1Lin + f2Lin);
    }

    @Test
    void aDualToneRunWithoutAManualFundamentalTakesTheMeasuredTones() {
        PredistortionEngine engine = started(GenSignalForm.DUAL_TONE);
        int productCount = PRODUCT_A.length;
        double[] clearanceDb = steppedClearances(productCount);
        settle(engine, products(distinctPhases(productCount), clearanceDb));
        assertProductLimits(engine, clearanceDb, MEASURED_TONES_LIN);
    }

    // -------------------------------------------------------------------------
    // Stop by hand
    // -------------------------------------------------------------------------

    @Test
    void aStopByHandBeforeTheSettleHasNoResult() {
        PredistortionEngine engine = started(GenSignalForm.SINE);
        FftResult r = harmonics(distinctPhases(HARMONIC_LINES), uniform(HARMONIC_LINES, CLEAR_DB));
        for (int n = 0; n < EXTRA_RESULTS; n++) {
            assertFalse(engine.onResult(r), "result " + n);
        }
        engine.stop();
        assertEquals(List.of(StopReason.USER_STOP), recorder.reasons);
        assertEquals(List.of(false), recorder.results);
        for (int n = 0; n < WINDOW_VALUES; n++) {
            assertFalse(engine.onResult(r), "the run ended");
        }
        engine.stop();
        assertEquals(1, recorder.reasons.size(), "the end is announced once");
        assertNull(engine.getHarmonicsInForce(), "no correction without a settle");
    }

    @Test
    void aStopByHandAfterTheSettleKeepsTheSettledResult() {
        PredistortionEngine engine = started(GenSignalForm.SINE);
        settle(engine, harmonics(distinctPhases(HARMONIC_LINES), uniform(HARMONIC_LINES, CLEAR_DB)));
        engine.stop();
        assertEquals(List.of(StopReason.SETTLED), recorder.reasons, "the stop after the end announces nothing");
        assertEquals(List.of(true), recorder.results);
        assertNotNull(engine.getHarmonicsInForce(), "the settled correction stays");
    }

    // -------------------------------------------------------------------------
    // Harness
    // -------------------------------------------------------------------------

    /** An engine on {@code form}, started; its listener is the recorder and
     *  its executor the calling thread. */
    private PredistortionEngine started(GenSignalForm form) {
        Preferences.instance().setGenSignalForm(form);
        PredistortionEngine engine = new PredistortionEngine(Runnable::run,
                new CorrectionStore(STORE_LABEL, null), recorder);
        engine.start();
        return engine;
    }

    /** Feeds {@code r} a window's worth of times: the last call settles. */
    private void settle(PredistortionEngine engine, FftResult r) {
        for (int n = 1; n < WINDOW_VALUES; n++) {
            assertFalse(engine.onResult(r), "result " + n);
        }
        assertTrue(engine.onResult(r), "the window's worth of results settles");
    }

    /** Asserts every product's limit against {@code referenceLin}, the
     *  fundamental the levels are percent of: the phase's part of a line's
     *  share is {@link #PHASE_SHARE} of {@link #TARGET_PCT} over the square
     *  root of the eight lines, its limit 2 asin(share / (2 L)). */
    private void assertProductLimits(PredistortionEngine engine, double[] clearanceDb, double referenceLin) {
        int productCount = PRODUCT_A.length;
        double sharePct = PHASE_SHARE * TARGET_PCT / Math.sqrt(productCount);
        List<LineReadout> readouts = engine.readouts();
        for (int g = 0; g < productCount; g++) {
            double linePct  = PERCENT * Math.pow(DECADE, (FLOOR_DBFS + clearanceDb[g]) / DB_PER_DECADE)
                    / referenceLin;
            double limitDeg = Math.toDegrees(2.0 * Math.asin(sharePct / (2.0 * linePct)));
            assertEquals(limitDeg, readouts.get(g).limitDeg(), WOBBLE_TOLERANCE_DEG, PRODUCT_NAMES[g] + " limit");
        }
    }

    private double[] distinctPhases(int lineCount) {
        double[] phaseDeg = new double[lineCount];
        for (int i = 0; i < lineCount; i++) {
            phaseDeg[i] = FIRST_PHASE_DEG + PHASE_STEP_DEG * i;
        }
        return phaseDeg;
    }

    private double[] uniform(int lineCount, double value) {
        double[] values = new double[lineCount];
        Arrays.fill(values, value);
        return values;
    }

    /** Clearances falling from {@link #CLEAR_DB} by
     *  {@link #CLEARANCE_STEP_DB} per line, every one over the gate. */
    private double[] steppedClearances(int lineCount) {
        double[] clearanceDb = new double[lineCount];
        for (int i = 0; i < lineCount; i++) {
            clearanceDb[i] = CLEAR_DB - CLEARANCE_STEP_DB * i;
        }
        return clearanceDb;
    }

    /** A single-tone result carrying harmonic H(h+2) at {@code phaseDeg[h]}
     *  and {@code clearanceDb[h]} over the floor. */
    private FftResult harmonics(double[] phaseDeg, double[] clearanceDb) {
        FftResult r = spectrum(HARMONIC_LINES);
        r.fundamentalBin       = bin(F0_HZ);
        r.fundamentalHzRefined = F0_HZ;
        r.harmonicCount        = HARMONIC_LINES;
        for (int h = 0; h < HARMONIC_LINES; h++) {
            double hz = (h + 2) * F0_HZ;
            r.harmonicHz[h]   = hz;
            r.harmonicBins[h] = bin(hz);
            r.harmonicDbFs[h] = FLOOR_DBFS + clearanceDb[h];
            line(r, h, bin(hz), phaseDeg[h], clearanceDb[h]);
        }
        return r;
    }

    /** A dual-tone result carrying product {@code (PRODUCT_A[g],
     *  PRODUCT_B[g])} at {@code phaseDeg[g]} and {@code clearanceDb[g]} over
     *  the floor, on the analyser's product grid, and F2 at 0 dBFS as the
     *  grid's {@code (0, 1)} entry behind the products, where the engine
     *  reads the second tone's level. */
    private FftResult products(double[] phaseDeg, double[] clearanceDb) {
        int productCount = PRODUCT_A.length;
        int f2Slot       = productCount;
        FftResult r = spectrum(productCount + 1);
        r.fundamentalBin        = bin(F1_HZ);
        r.fundamentalHzRefined  = F1_HZ;
        r.fundamental2HzRefined = F2_HZ;
        r.imdProductA           = Arrays.copyOf(PRODUCT_A, productCount + 1);
        r.imdProductB           = Arrays.copyOf(PRODUCT_B, productCount + 1);
        r.imdProductBin         = new int[productCount + 1];
        for (int g = 0; g < productCount; g++) {
            int productBin = bin(PRODUCT_A[g] * F1_HZ + PRODUCT_B[g] * F2_HZ);
            r.imdProductBin[g] = productBin;
            line(r, g, productBin, phaseDeg[g], clearanceDb[g]);
        }
        r.imdProductA[f2Slot]   = 0;
        r.imdProductB[f2Slot]   = 1;
        r.imdProductBin[f2Slot] = bin(F2_HZ);
        r.rawPeakRe[f2Slot]     = 1.0;
        r.rawPeakIm[f2Slot]     = 0.0;
        return r;
    }

    /** A result with the flat floor, the bin grid, the window's bandwidth,
     *  both tones' DAC phases and the frame count of the first taken result,
     *  sized for {@code lineCount} raw peaks. */
    private FftResult spectrum(int lineCount) {
        FftResult r = new FftResult();
        r.ensureArrays(BINS, HARMONIC_LINES);
        Arrays.fill(r.amplitudeDbFs, FLOOR_DBFS);
        r.sampleRate           = SAMPLE_RATE;
        r.fftSize              = FFT_SIZE;
        r.freqResolution       = BIN_HZ;
        r.windowNenbwBins      = NENBW_BINS;
        r.fundamentalTrueDbFs  = Double.NaN;
        r.rawFundRe            = 1.0;
        r.rawFundIm            = 0.0;
        r.dacFundPhaseRad      = DAC_PHASE_RAD;
        r.dacFund2PhaseRad     = DAC_PHASE_RAD;
        r.frameCount           = MIN_AVERAGES;
        r.channelLeft          = true;
        r.rawPeakRe            = new double[lineCount];
        r.rawPeakIm            = new double[lineCount];
        return r;
    }

    /** Writes one line: its raw peak phasor at {@code slot} and its level
     *  into its bin, which the line's own floor window leaves out. */
    private void line(FftResult r, int slot, int lineBin, double phaseDeg, double clearanceDb) {
        double levelDbFs = FLOOR_DBFS + clearanceDb;
        double levelLin  = Math.pow(DECADE, levelDbFs / DB_PER_DECADE);
        double phaseRad  = Math.toRadians(phaseDeg);
        r.rawPeakRe[slot]        = levelLin * Math.cos(phaseRad);
        r.rawPeakIm[slot]        = levelLin * Math.sin(phaseRad);
        r.amplitudeDbFs[lineBin] = levelDbFs;
    }

    private int bin(double hz) {
        return (int) Math.round(hz / BIN_HZ);
    }
}
