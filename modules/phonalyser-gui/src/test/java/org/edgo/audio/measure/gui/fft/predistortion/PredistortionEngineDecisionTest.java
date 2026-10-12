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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.edgo.audio.measure.enums.AlignGenerator;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.fft.FftResult.LineState;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.GeneratorCorrectionsRequest;
import org.edgo.audio.measure.gui.bus.GeneratorCorrectionsRequest.Kind;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.fft.predistortion.LineRestarts.Line;
import org.edgo.audio.measure.gui.fft.predistortion.PredistortionEngine.StopReason;
import org.edgo.audio.measure.gui.widgets.UnitConversion;
import org.edgo.audio.measure.gui.widgets.UnitFamily;
import org.edgo.audio.measure.gui.widgets.UnitValue;
import org.edgo.audio.measure.preferences.Preferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine's decision on each result: the apply rule (a line 5 dB or more
 * over its local band top is applied, every such line of one cycle in one
 * request), the guard on a line waiting for its reset, the report of a
 * restart once the waiting mark goes, and the stop rule - with the
 * distortion, every line held at its local band top, at or under the target,
 * a line under half its share of the target is parked at once, a line
 * between half the target and the target once its clearance over its local
 * band top grew by less than 1 dB while its averages doubled, and the run
 * ends when every line is parked.  The results are built by hand as the coherent average stamps
 * them: every line's local band top at {@link #BAND_TOP_DBFS} plus its own
 * offset, its raw level that plus its clearance.  The UI thread is the
 * test's own (or a queue drained by hand), the generator requests are read
 * off the bus.
 */
class PredistortionEngineDecisionTest {

    /** H2..H9 - the set the calc-max-harmonic preference of 9 gives. */
    private static final int    HARMONICS = 8;
    private static final int    CALC_MAX_HARMONIC = 9;
    private static final double BIN_HZ = 1.0;
    private static final int    BINS = 24_001;
    private static final double F1_HZ = 1000.0;
    private static final double F2_HZ = 1300.0;
    private static final double BAND_TOP_DBFS = -150.0;
    private static final int    FRAMES = 40;
    /** The first result and two that agree with it settle the tone. */
    private static final int    RESULTS_TO_ALIGN = 3;
    /** Far above any distortion the synthetic lines add up to (about 1e-5 %
     *  of the 0 dBFS fundamental): half of it is always reached. */
    private static final double LOOSE_TARGET_PCT = 100.0;
    /** Far below it: half of it is never reached. */
    private static final double TIGHT_TARGET_PCT = 1.0e-12;
    private static final double GEN_AMPLITUDE_VRMS = 0.5;
    private static final double LOG_BASE = 10.0;
    private static final double AMPLITUDE_DB_PER_DECADE = 20.0;
    /** A target fifteen times one line on its band top: the eight lines on
     *  their band tops sum to under three times that, and half of one
     *  line's share of the target is 2.65 times it - every line on its band
     *  top falls under the first park rule. */
    private static final double TARGET_IN_BAND_TOP_LINES = 15.0;
    /** H2 raised 20 dB: ten band-top lines, between half the target and the
     *  target - the second park rule's range. */
    private static final double SECOND_RULE_RAISE_DB = 20.0;
    /** H2 raised 12 dB: four band-top lines, over the first rule's floor and
     *  under the second rule's range. */
    private static final double GAP_RAISE_DB = 12.0;

    private final List<GeneratorCorrectionsRequest> requests = new ArrayList<>();
    private final Consumer<GeneratorCorrectionsRequest> onRequest = requests::add;
    private final FakeRestarts restarts = new FakeRestarts();
    private final RecordingListener listener = new RecordingListener();
    private final Preferences prefs = Preferences.instance();

    private PredistortionEngine engine;
    private boolean previousTransient;
    private GenSignalForm previousForm;
    private int previousMaxHarmonic;
    private UnitValue previousAmplitude;
    private boolean previousCoherent;
    private double previousAverages;
    private boolean previousFundFromGenerator;
    private AlignGenerator previousAlign;

    /** One line of a synthetic result: its coefficients, its clearance over
     *  its local band top, its waiting mark, its local count and how far its
     *  local band top stands over {@link #BAND_TOP_DBFS}. */
    private record Spec(int a, int b, double clearanceDb, boolean waiting, int localCount, double topDb) {
        Spec clearBy(double db) {
            return new Spec(a, b, db, waiting, localCount, topDb);
        }

        Spec waitingForReset() {
            return new Spec(a, b, clearanceDb, true, localCount, topDb);
        }

        Spec frames(int n) {
            return new Spec(a, b, clearanceDb, waiting, n, topDb);
        }

        Spec raisedBy(double db) {
            return new Spec(a, b, clearanceDb, waiting, localCount, db);
        }
    }

    /** Records every restart request and every statistics export. */
    private final class FakeRestarts implements LineRestarts {
        private final List<List<Line>> requested = new ArrayList<>();
        private final List<Path> exported = new ArrayList<>();

        @Override
        public void requestLineRestart(List<Line> lines) {
            requested.add(List.copyOf(lines));
        }

        @Override
        public void exportStatistics(Path file) {
            exported.add(file);
        }
    }

    /** Records every callback the engine delivers. */
    private final class RecordingListener implements PredistortionEngine.Listener {
        //private int started;
        private final List<List<String>> applied = new ArrayList<>();
        private final List<List<String>> restarted = new ArrayList<>();
        private final List<List<String>> parked = new ArrayList<>();
        private final List<List<String>> unparked = new ArrayList<>();
        private final List<StopReason> finished = new ArrayList<>();
        private final List<Boolean> hasResult = new ArrayList<>();

        @Override
        public void onStarted() {
            //started++;
        }

        @Override
        public void onLinesApplied(List<String> names) {
            applied.add(List.copyOf(names));
        }

        @Override
        public void onLinesRestarted(List<String> names) {
            restarted.add(List.copyOf(names));
        }

        @Override
        public void onLinesParked(List<String> names) {
            parked.add(List.copyOf(names));
        }

        @Override
        public void onLinesUnparked(List<String> names) {
            unparked.add(List.copyOf(names));
        }

        @Override
        public void onFinished(StopReason reason, boolean result) {
            finished.add(reason);
            hasResult.add(result);
        }
    }

    @BeforeEach
    void keepThePreferences() {
        // The engine writes its measurement preferences through the live
        // singleton; transient mode keeps them off the preferences file.
        previousTransient         = prefs.isTransientMode();
        prefs.setTransientMode(true);
        previousForm              = prefs.getGenSignalForm();
        previousMaxHarmonic       = prefs.getFftCalcMaxHarmonic();
        previousAmplitude         = prefs.getGenAmplitudeLeft();
        previousCoherent          = prefs.isFftCoherentAveraging();
        previousAverages          = prefs.getFftAverages();
        previousFundFromGenerator = prefs.isFftFundFromGenerator();
        previousAlign             = prefs.getFftAlignGenerator();
        prefs.setFftCalcMaxHarmonic(CALC_MAX_HARMONIC);
        prefs.setGenAmplitudeLeft(new UnitValue(GEN_AMPLITUDE_VRMS, UnitConversion.baseToken(UnitFamily.AMPLITUDE)));
        MessageBus.instance().subscribe(Events.GENERATOR_CORRECTIONS, onRequest);
    }

    @AfterEach
    void restoreThePreferences() {
        MessageBus.instance().unsubscribe(Events.GENERATOR_CORRECTIONS, onRequest);
        prefs.setGenSignalForm(previousForm);
        prefs.setFftCalcMaxHarmonic(previousMaxHarmonic);
        prefs.setGenAmplitudeLeft(previousAmplitude);
        prefs.setFftCoherentAveraging(previousCoherent);
        prefs.setFftAverages(previousAverages);
        prefs.setFftFundFromGenerator(previousFundFromGenerator);
        prefs.setFftAlignGenerator(previousAlign);
        prefs.setTransientMode(previousTransient);
    }

    /** Starts a run on {@code form} and settles the tone, kappa not yet
     *  refined, so the next result is the first one decided on. */
    private void start(GenSignalForm form, double targetPct) {
        start(form, targetPct, Runnable::run, true);
    }

    private void start(GenSignalForm form, double targetPct, Executor uiThread, boolean align) {
        prefs.setGenSignalForm(form);
        engine = new PredistortionEngine(uiThread, MessageBus.instance(), restarts, null, listener);
        engine.start(targetPct);
        assertEquals(Kind.CLEAR, requests.get(0).kind(), "the run clears the generator's table first");
        requests.clear();
        if (align) {
            for (int i = 0; i < RESULTS_TO_ALIGN; i++) {
                FftResult r = base();
                r.coherentKappaRefined = false;
                engine.onResult(r);
            }
        }
    }

    private Spec line(int a, int b) {
        return new Spec(a, b, 0.0, false, FRAMES, 0.0);
    }

    /** One line on its band top in percent, as the engine converts it: the
     *  held distortion of eight such lines over the square root of eight,
     *  read off a run whose target is never reached. */
    private double bandTopLinePct() {
        start(GenSignalForm.SINE, TIGHT_TARGET_PCT);
        engine.onResult(singleTone(HARMONICS));
        return engine.getHeldDistortionPct() / Math.sqrt(HARMONICS);
    }

    /** The parts every result shares: the bin grid, the fundamental F1 with
     *  its raw phasor at zero phase, the global frame count, kappa refined
     *  and an empty line-state list. */
    private FftResult base() {
        FftResult r = new FftResult();
        r.freqResolution       = BIN_HZ;
        r.frameCount           = FRAMES;
        r.re                   = new double[BINS];
        r.im                   = new double[BINS];
        r.fundamentalBin       = bin(F1_HZ);
        r.fundamentalHzRefined = F1_HZ;
        r.rawFundRe            = 1.0;
        r.rawFundIm            = 0.0;
        r.coherentKappaRefined = true;
        r.lineStates           = new ArrayList<>();
        return r;
    }

    /** A single-tone result with harmonics H2 .. H(harmonicCount + 1), each
     *  on its band top unless {@code overrides} names it. */
    private FftResult singleTone(int harmonicCount, Spec... overrides) {
        FftResult r = base();
        r.harmonicCount = harmonicCount;
        r.harmonicBins  = new int[harmonicCount];
        r.harmonicHz    = new double[harmonicCount];
        r.harmonicDbFs  = new double[harmonicCount];
        r.rawPeakRe     = new double[harmonicCount];
        r.rawPeakIm     = new double[harmonicCount];
        for (int h = 0; h < harmonicCount; h++) {
            Spec s = spec(h + 2, 0, overrides);
            r.harmonicHz[h]   = (h + 2) * F1_HZ;
            r.harmonicBins[h] = bin(r.harmonicHz[h]);
            r.harmonicDbFs[h] = BAND_TOP_DBFS + s.topDb() + s.clearanceDb();
            // The averaged raw peak carries the level and the phase.
            r.rawPeakRe[h]    = Math.pow(LOG_BASE, r.harmonicDbFs[h] / AMPLITUDE_DB_PER_DECADE);
            r.lineStates.add(state(s, r.harmonicBins[h]));
        }
        return r;
    }

    /** A dual-tone result carrying the second tone and the given products
     *  in the analyser's grid, each product's raw peak at its level. */
    private FftResult dualTone(Spec... products) {
        FftResult r = base();
        r.fundamental2HzRefined = F2_HZ;
        int n = products.length + 1;
        r.imdProductA   = new int[n];
        r.imdProductB   = new int[n];
        r.imdProductBin = new int[n];
        r.rawPeakRe     = new double[n];
        r.rawPeakIm     = new double[n];
        // Entry 0: the second tone, the (0, 1) entry of the grid.
        r.imdProductB[0]   = 1;
        r.imdProductBin[0] = bin(F2_HZ);
        r.rawPeakRe[0]     = 1.0;
        for (int i = 0; i < products.length; i++) {
            Spec s = products[i];
            int bin = bin(s.a() * F1_HZ + s.b() * F2_HZ);
            r.imdProductA[i + 1]   = s.a();
            r.imdProductB[i + 1]   = s.b();
            r.imdProductBin[i + 1] = bin;
            r.rawPeakRe[i + 1]     = Math.pow(LOG_BASE,
                    (BAND_TOP_DBFS + s.topDb() + s.clearanceDb()) / AMPLITUDE_DB_PER_DECADE);
            r.lineStates.add(state(s, bin));
        }
        return r;
    }

    /** Moves {@code r}'s global frame count to {@code frames}, and with it
     *  every line still on the global count - as adding frames or taking
     *  them out again moves them in the coherent average. */
    private FftResult counted(FftResult r, int frames) {
        int before = r.frameCount;
        r.lineStates.replaceAll(s -> s.localCount() != before ? s
                : new LineState(s.a(), s.b(), s.bin(), frames, s.waitingReset(), s.localBandTopDbFs()));
        r.frameCount = frames;
        return r;
    }

    /** The bin of {@code hz}: every frequency of these results is a whole
     *  number of bins. */
    private int bin(double hz) {
        return (int) (hz / BIN_HZ);
    }

    private Spec spec(int a, int b, Spec... overrides) {
        for (Spec s : overrides) {
            if (s.a() == a && s.b() == b) return s;
        }
        return line(a, b);
    }

    private LineState state(Spec s, int bin) {
        return new LineState(s.a(), s.b(), bin, s.localCount(), s.waiting(), BAND_TOP_DBFS + s.topDb());
    }

    @Test
    void aLineSixDecibelsClearIsAppliedAndOneFourDecibelsClearIsNot() {
        start(GenSignalForm.SINE, LOOSE_TARGET_PCT);
        engine.onResult(singleTone(HARMONICS, line(2, 0).clearBy(6.0), line(3, 0).clearBy(4.0)));

        assertEquals(1, requests.size(), "one request");
        GeneratorCorrectionsRequest q = requests.get(0);
        assertEquals(Kind.HARMONIC_LINES, q.kind());
        assertArrayEquals(new int[]{ 2 }, q.harmonics(), "only H2 is sent");
        assertEquals(1, q.ampRatios().length);
        assertTrue(q.ampRatios()[0] > 0.0);
        assertNull(q.coefA());
        assertEquals(List.of(List.of(new Line(2, 0))), restarts.requested);
        assertEquals(List.of(List.of("H2")), listener.applied);
        assertTrue(listener.finished.isEmpty(), "a cycle that applies does not stop");
        assertEquals(FRAMES, engine.getFrameCount());
    }

    @Test
    void everyClearLineOfOneCycleGoesInOneRequest() {
        start(GenSignalForm.SINE, LOOSE_TARGET_PCT);
        engine.onResult(singleTone(HARMONICS, line(2, 0).clearBy(6.0), line(4, 0).clearBy(8.0),
                line(7, 0).clearBy(12.0)));

        assertEquals(1, requests.size(), "one request for the whole cycle");
        assertArrayEquals(new int[]{ 2, 4, 7 }, requests.get(0).harmonics());
        assertEquals(3, requests.get(0).ampRatios().length);
        assertEquals(List.of(List.of(new Line(2, 0), new Line(4, 0), new Line(7, 0))), restarts.requested,
                "one restart request for all three");
        assertEquals(List.of(List.of("H2", "H4", "H7")), listener.applied);
    }

    @Test
    void aLineWaitingForItsResetIsNotDecidedOn() {
        start(GenSignalForm.SINE, LOOSE_TARGET_PCT);
        engine.onResult(singleTone(HARMONICS, line(2, 0).clearBy(12.0).waitingForReset()));

        assertTrue(requests.isEmpty(), "no request for a waiting line");
        assertTrue(restarts.requested.isEmpty());
        assertTrue(listener.applied.isEmpty());
        assertTrue(listener.finished.isEmpty(), "a waiting line holds the stop");
    }

    @Test
    void noDecisionBeforeTheToneIsSettledAndKappaRefined() {
        start(GenSignalForm.SINE, LOOSE_TARGET_PCT, Runnable::run, false);
        // The first result: no earlier one agrees with it yet.
        engine.onResult(singleTone(HARMONICS, line(2, 0).clearBy(12.0)));
        assertTrue(requests.isEmpty(), "the tone is not settled on one result");

        FftResult unrefined = singleTone(HARMONICS, line(2, 0).clearBy(12.0));
        unrefined.coherentKappaRefined = false;
        engine.onResult(unrefined);
        engine.onResult(unrefined);
        assertTrue(requests.isEmpty(), "no decision while kappa is not refined");

        engine.onResult(singleTone(HARMONICS, line(2, 0).clearBy(12.0)));
        assertEquals(1, requests.size(), "settled and refined: H2 is applied");
    }

    @Test
    void theRestartIsReportedOnceTheWaitingMarkGoesAndTheStopWaitsForItsFirstFrame() {
        start(GenSignalForm.SINE, LOOSE_TARGET_PCT);
        engine.onResult(singleTone(HARMONICS, line(2, 0).clearBy(6.0)));
        assertEquals(List.of(List.of("H2")), listener.applied);

        engine.onResult(singleTone(HARMONICS, line(2, 0).waitingForReset()));
        assertTrue(listener.restarted.isEmpty(), "still waiting");
        assertTrue(listener.finished.isEmpty(), "a waiting line holds the stop");

        engine.onResult(singleTone(HARMONICS, line(2, 0).frames(0)));
        assertEquals(List.of(List.of("H2")), listener.restarted, "the mark has gone");
        assertTrue(listener.finished.isEmpty(), "no frame since the reset holds the stop");

        engine.onResult(singleTone(HARMONICS, line(2, 0).frames(1)));
        assertEquals(List.of(StopReason.TARGET_REACHED), listener.finished, "H2 read again and parked: all parked");
        assertEquals(List.of(true), listener.hasResult, "H2's correction is in force");
        assertEquals(1, requests.size(), "nothing applied after the first cycle");
        assertTrue(restarts.exported.isEmpty(), "the statistics switch is off");
    }

    @Test
    void anAppliedLineIsNotDecidedOnAgainBeforeItsMarkShows() {
        start(GenSignalForm.SINE, LOOSE_TARGET_PCT);
        engine.onResult(singleTone(HARMONICS, line(2, 0).clearBy(12.0)));
        assertEquals(1, requests.size());

        // The request has not reached the worker yet: the line is still
        // unmarked on its old average, 12 dB clear.
        engine.onResult(counted(singleTone(HARMONICS, line(2, 0).clearBy(12.0)), FRAMES + 2));
        assertEquals(1, requests.size(), "no second apply on the old average");
        assertTrue(listener.restarted.isEmpty(), "an unmarked line has not restarted");
        assertTrue(listener.finished.isEmpty(), "a requested line holds the stop");

        engine.onResult(counted(singleTone(HARMONICS, line(2, 0).waitingForReset()), FRAMES + 4));
        assertTrue(listener.finished.isEmpty(), "a waiting line holds the stop");
        engine.onResult(singleTone(HARMONICS, line(2, 0).frames(2)));
        assertEquals(List.of(List.of("H2")), listener.restarted);
        assertEquals(List.of(StopReason.TARGET_REACHED), listener.finished);
    }

    @Test
    void aResetSeenWithoutItsMarkStillReportsTheRestart() {
        start(GenSignalForm.SINE, LOOSE_TARGET_PCT);
        // H2 applied on the global count: no gap between the two counts.
        engine.onResult(singleTone(HARMONICS, line(2, 0).clearBy(6.0)));

        // The mark came and went between two results: the global count now
        // stands 38 frames ahead of H2's local count, which only the line's
        // own reset does.
        engine.onResult(singleTone(HARMONICS, line(2, 0).frames(2)));
        assertEquals(List.of(List.of("H2")), listener.restarted);
        assertEquals(List.of(StopReason.TARGET_REACHED), listener.finished);
    }

    @Test
    void aDiscontinuityThatTakesFramesOutReadsAsNoReset() {
        start(GenSignalForm.SINE, LOOSE_TARGET_PCT);
        engine.onResult(singleTone(HARMONICS, line(2, 0).clearBy(12.0)));
        assertEquals(1, requests.size());

        // A gate took the decided cycle's frames out again: the global count
        // and H2's local count, still unmarked, both stand under their counts
        // at the apply.
        engine.onResult(counted(singleTone(HARMONICS, line(2, 0).clearBy(12.0)), FRAMES - 2));
        assertEquals(1, requests.size(), "no second apply on the old average");
        assertTrue(listener.restarted.isEmpty(), "taking frames out is no reset");
        assertTrue(listener.finished.isEmpty(), "the requested line holds the stop");
    }

    @Test
    void aNewEpochReportsTheRestart() {
        start(GenSignalForm.SINE, LOOSE_TARGET_PCT);
        engine.onResult(singleTone(HARMONICS, line(2, 0).clearBy(6.0)));

        // The collection was reset and averaged anew: a new epoch, H2
        // unmarked on the new, smaller global count.
        FftResult fresh = counted(singleTone(HARMONICS), 4);
        fresh.epoch = 1;
        engine.onResult(fresh);
        assertEquals(List.of(List.of("H2")), listener.restarted);
        assertEquals(List.of(StopReason.TARGET_REACHED), listener.finished, "the stop is not held");
    }

    @Test
    void aRestartOfTheCollectionReportsTheRestart() {
        start(GenSignalForm.SINE, LOOSE_TARGET_PCT);
        engine.onResult(singleTone(HARMONICS, line(2, 0).clearBy(6.0)));

        // The collection restarted in the same epoch (a new FFT size): kappa
        // is measured anew and every count starts from zero.
        FftResult restarted = counted(singleTone(HARMONICS), 4);
        restarted.coherentKappaRefined = false;
        engine.onResult(restarted);
        assertEquals(List.of(List.of("H2")), listener.restarted);
        assertTrue(listener.finished.isEmpty(), "no decision before kappa is refined");

        engine.onResult(counted(singleTone(HARMONICS), FRAMES));
        assertEquals(List.of(StopReason.TARGET_REACHED), listener.finished, "the stop is not held");
    }

    @Test
    void aLineStillClearHoldsTheStopUnderTheTarget() {
        start(GenSignalForm.SINE, LOOSE_TARGET_PCT);
        engine.onResult(singleTone(HARMONICS, line(5, 0).clearBy(6.0)));

        assertEquals(1, requests.size(), "the clear line is applied");
        assertTrue(listener.finished.isEmpty(), "and the run goes on");
    }

    @Test
    void theRunStopsOnceEveryLineIsParkedUnderHalfItsShare() {
        start(GenSignalForm.SINE, LOOSE_TARGET_PCT);
        engine.onResult(singleTone(HARMONICS));

        assertEquals(List.of(StopReason.TARGET_REACHED), listener.finished, "every line under half its share");
        assertEquals(List.of(List.of("H2", "H3", "H4", "H5", "H6", "H7", "H8", "H9")), listener.parked,
                "one park event naming every line");
        assertEquals(List.of(false), listener.hasResult, "nothing was applied");
        assertTrue(requests.isEmpty());

        engine.onResult(singleTone(HARMONICS, line(3, 0).clearBy(12.0)));
        assertTrue(requests.isEmpty(), "a finished run decides nothing");
        assertEquals(1, listener.finished.size());
    }

    @Test
    void aLineInTheSecondRangeIsParkedOnceItsClearanceGrewUnderOneDecibelWhileItsAveragesDoubled() {
        double target = TARGET_IN_BAND_TOP_LINES * bandTopLinePct();
        start(GenSignalForm.SINE, target);
        Spec h2 = line(2, 0).raisedBy(SECOND_RULE_RAISE_DB);
        engine.onResult(singleTone(HARMONICS, h2));
        assertTrue(listener.finished.isEmpty(), "H2 opens its window, the other lines are parked");

        engine.onResult(singleTone(HARMONICS, h2.frames(2 * FRAMES - 1).clearBy(0.8)));
        assertTrue(listener.finished.isEmpty(), "its averages have not doubled yet");

        engine.onResult(singleTone(HARMONICS, h2.frames(2 * FRAMES).clearBy(0.8)));
        assertEquals(List.of(StopReason.TARGET_REACHED), listener.finished,
                "clearance grown 0.8 dB while the averages doubled: noise");
        assertTrue(requests.isEmpty(), "nothing was applied");
    }

    @Test
    void aLineInTheSecondRangeWhoseClearanceGrewOpensItsNextWindow() {
        double target = TARGET_IN_BAND_TOP_LINES * bandTopLinePct();
        start(GenSignalForm.SINE, target);
        Spec h2 = line(2, 0).raisedBy(SECOND_RULE_RAISE_DB);
        engine.onResult(singleTone(HARMONICS, h2));

        engine.onResult(singleTone(HARMONICS, h2.frames(2 * FRAMES).clearBy(1.5)));
        assertTrue(listener.finished.isEmpty(), "grown 1.5 dB: a residual - the next window opens here");

        engine.onResult(singleTone(HARMONICS, h2.frames(4 * FRAMES).clearBy(2.0)));
        assertEquals(List.of(StopReason.TARGET_REACHED), listener.finished,
                "grown 0.5 dB over the next doubling");
    }

    /** The case the held level misjudged: a line just under its band top,
     *  which the averaging lowers by 3 dB while the line's own averages
     *  double.  The held level - that band top - falls; the line itself
     *  holds, so its clearance grows by the 3 dB. */
    @Test
    void aLineItsFallingBandTopUncoversIsNotParked() {
        double target = TARGET_IN_BAND_TOP_LINES * bandTopLinePct();
        start(GenSignalForm.SINE, target);
        engine.onResult(singleTone(HARMONICS, line(2, 0).raisedBy(SECOND_RULE_RAISE_DB).clearBy(-0.4)));

        engine.onResult(singleTone(HARMONICS,
                line(2, 0).raisedBy(SECOND_RULE_RAISE_DB - 3.0).clearBy(2.6).frames(2 * FRAMES)));
        assertTrue(listener.finished.isEmpty(), "its clearance grew 3 dB: a residual, not parked");
        assertTrue(requests.isEmpty(), "nor applied yet - under 5 dB clear");
    }

    /** The second rule reversed: a parked line in its range whose clearance
     *  grew by more than 1 dB while its averages doubled is unparked.  H3,
     *  between the two rules, is never parked and keeps the run going. */
    @Test
    void aParkedLineInTheSecondRangeWhoseClearanceGrewIsUnparked() {
        double target = TARGET_IN_BAND_TOP_LINES * bandTopLinePct();
        start(GenSignalForm.SINE, target);
        Spec h2 = line(2, 0).raisedBy(SECOND_RULE_RAISE_DB);
        Spec h3 = line(3, 0).raisedBy(GAP_RAISE_DB);
        engine.onResult(singleTone(HARMONICS, h2, h3));
        engine.onResult(singleTone(HARMONICS, h2.frames(2 * FRAMES).clearBy(0.8), h3));
        assertEquals(List.of(List.of("H4", "H5", "H6", "H7", "H8", "H9"), List.of("H2")), listener.parked,
                "H2 grown 0.8 dB while its averages doubled: parked");

        // The parked H2 opens its window here.
        engine.onResult(singleTone(HARMONICS, h2.frames(2 * FRAMES).clearBy(0.8), h3));
        engine.onResult(singleTone(HARMONICS, h2.frames(4 * FRAMES - 1).clearBy(2.3), h3));
        assertTrue(listener.unparked.isEmpty(), "its averages have not doubled yet");

        engine.onResult(singleTone(HARMONICS, h2.frames(4 * FRAMES).clearBy(2.3), h3));
        assertEquals(List.of(List.of("H2")), listener.unparked, "grown 1.5 dB while its averages doubled");
        assertTrue(listener.finished.isEmpty());
        assertTrue(requests.isEmpty(), "under 5 dB clear: not applied");
    }

    @Test
    void aParkedLineInTheSecondRangeWhoseClearanceHeldStaysParked() {
        double target = TARGET_IN_BAND_TOP_LINES * bandTopLinePct();
        start(GenSignalForm.SINE, target);
        Spec h2 = line(2, 0).raisedBy(SECOND_RULE_RAISE_DB);
        Spec h3 = line(3, 0).raisedBy(GAP_RAISE_DB);
        engine.onResult(singleTone(HARMONICS, h2, h3));
        engine.onResult(singleTone(HARMONICS, h2.frames(2 * FRAMES).clearBy(0.8), h3));
        engine.onResult(singleTone(HARMONICS, h2.frames(2 * FRAMES).clearBy(0.8), h3));
        engine.onResult(singleTone(HARMONICS, h2.frames(4 * FRAMES).clearBy(1.6), h3));

        assertTrue(listener.unparked.isEmpty(), "grown 0.8 dB while its averages doubled: stays parked");
    }

    @Test
    void aLineBetweenTheTwoRulesHoldsTheStop() {
        double target = TARGET_IN_BAND_TOP_LINES * bandTopLinePct();
        start(GenSignalForm.SINE, target);
        Spec h2 = line(2, 0).raisedBy(GAP_RAISE_DB);
        engine.onResult(singleTone(HARMONICS, h2));
        engine.onResult(singleTone(HARMONICS, h2.frames(8 * FRAMES)));

        assertTrue(listener.finished.isEmpty(), "over half its share and under half the target: never parked");
        assertTrue(requests.isEmpty(), "nor applied while it stands on its band top");
    }

    @Test
    void aDistortionOverTheTargetUnparksEveryLine() {
        double target = TARGET_IN_BAND_TOP_LINES * bandTopLinePct();
        start(GenSignalForm.SINE, target);
        Spec h2 = line(2, 0).raisedBy(SECOND_RULE_RAISE_DB);
        engine.onResult(singleTone(HARMONICS, h2));

        // H3's band top rises far enough to lift the distortion over the
        // target: every line is unparked and H2's window dropped.
        engine.onResult(singleTone(HARMONICS, h2.frames(FRAMES + FRAMES / 2),
                line(3, 0).raisedBy(SECOND_RULE_RAISE_DB + 3.0)));
        assertTrue(listener.finished.isEmpty());
        List<String> ruleOneLines = List.of("H3", "H4", "H5", "H6", "H7", "H8", "H9");
        assertEquals(List.of(ruleOneLines), listener.parked, "H2 opened its window, the others parked");
        assertEquals(List.of(ruleOneLines), listener.unparked, "the parked lines unparked over the target");

        // Back under the target: H2's window opens anew from here, so the
        // count that doubles its first window judges nothing.
        engine.onResult(singleTone(HARMONICS, h2.frames(2 * FRAMES)));
        assertTrue(listener.finished.isEmpty(), "the window restarted after the unpark");
        engine.onResult(singleTone(HARMONICS, h2.frames(4 * FRAMES)));
        assertEquals(List.of(StopReason.TARGET_REACHED), listener.finished);
    }

    @Test
    void theRunGoesOnWhileTheHeldDistortionStandsOverTheTarget() {
        start(GenSignalForm.SINE, TIGHT_TARGET_PCT);
        engine.onResult(singleTone(HARMONICS));
        engine.onResult(counted(singleTone(HARMONICS), 2 * FRAMES));

        assertTrue(listener.finished.isEmpty(), "every line on its band top still sums over the target");
        assertTrue(requests.isEmpty());
    }

    @Test
    void linesBeyondTheCorrectionSetAreIgnored() {
        start(GenSignalForm.SINE, LOOSE_TARGET_PCT);
        // H10 and H11 lie beyond the calc-max-harmonic preference of 9.
        engine.onResult(singleTone(HARMONICS + 2, line(10, 0).clearBy(12.0), line(11, 0).clearBy(12.0)));

        assertTrue(requests.isEmpty(), "no request for a line outside the set");
        assertEquals(List.of(StopReason.TARGET_REACHED), listener.finished, "nor do they hold the stop");
    }

    @Test
    void aStopEndsTheRunAtOnceWithTheCorrectionInForce() {
        start(GenSignalForm.SINE, LOOSE_TARGET_PCT);
        engine.onResult(singleTone(HARMONICS, line(2, 0).clearBy(6.0)));
        engine.stop();

        assertEquals(List.of(StopReason.USER_STOP), listener.finished);
        assertEquals(List.of(true), listener.hasResult);
        engine.onResult(singleTone(HARMONICS, line(3, 0).clearBy(12.0)));
        assertEquals(1, requests.size(), "a stopped run applies nothing more");
    }

    @Test
    void aCorrectionQueuedBehindTheStopIsDropped() {
        List<Runnable> queue = new ArrayList<>();
        start(GenSignalForm.SINE, LOOSE_TARGET_PCT, queue::add, true);
        engine.onResult(singleTone(HARMONICS, line(2, 0).clearBy(6.0)));
        engine.stop();
        queue.forEach(Runnable::run);

        assertTrue(requests.isEmpty(), "the request that ran after the stop was not sent");
        assertTrue(restarts.requested.isEmpty(), "nor was a restart requested for it");
        assertTrue(listener.applied.isEmpty());
        assertEquals(List.of(StopReason.USER_STOP), listener.finished);
        assertEquals(List.of(false), listener.hasResult, "the dropped set is not in force");
        assertNull(engine.getHarmonicsInForce());
    }

    @Test
    void aDualToneAppliesTheProductsByTheirCoefficients() {
        start(GenSignalForm.DUAL_TONE, LOOSE_TARGET_PCT);
        engine.onResult(dualTone(line(2, -1).clearBy(6.0), line(-1, 2).clearBy(4.0), line(1, 1),
                line(-1, 1).clearBy(7.0)));

        assertEquals(1, requests.size(), "one request");
        GeneratorCorrectionsRequest q = requests.get(0);
        assertEquals(Kind.INTERMOD_LINES, q.kind());
        // In the order of the correction grid: F2-F1 before 2F1-F2.
        assertArrayEquals(new int[]{ -1, 2 }, q.coefA());
        assertArrayEquals(new int[]{ 1, -1 }, q.coefB());
        assertNull(q.harmonics());
        assertEquals(List.of(List.of(new Line(-1, 1), new Line(2, -1))), restarts.requested);
        assertEquals(List.of(List.of("F2-F1", "2F1-F2")), listener.applied);
    }

    @Test
    void aDualToneStopsOnItsProducts() {
        start(GenSignalForm.DUAL_TONE, LOOSE_TARGET_PCT);
        engine.onResult(dualTone(line(2, -1).waitingForReset(), line(-1, 2), line(1, 1)));
        assertTrue(listener.finished.isEmpty(), "a waiting product holds the stop");

        engine.onResult(dualTone(line(2, -1), line(-1, 2), line(1, 1)));
        assertEquals(List.of(List.of("2F1-F2")), listener.restarted);
        assertEquals(List.of(StopReason.TARGET_REACHED), listener.finished);
        assertTrue(requests.isEmpty());
    }
}
