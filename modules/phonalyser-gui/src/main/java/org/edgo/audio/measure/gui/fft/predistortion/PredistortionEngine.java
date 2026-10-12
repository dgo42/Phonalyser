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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.DoubleFunction;

import org.edgo.audio.measure.common.AppPaths;
import org.edgo.audio.measure.dsp.FreqRespCalHelper;
import org.edgo.audio.measure.dsp.FreqRespCalibration;
import org.edgo.audio.measure.dsp.HarmonicCompensation;
import org.edgo.audio.measure.dsp.HarmonicCompensation.GeneratorCorrections;
import org.edgo.audio.measure.dsp.HarmonicCompensation.HarmonicPhasors;
import org.edgo.audio.measure.dsp.IntermodCompensation;
import org.edgo.audio.measure.dsp.IntermodCompensation.ProductPhasors;
import org.edgo.audio.measure.enums.AlignGenerator;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.MainsSuppression;
import org.edgo.audio.measure.enums.WindowType;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.fft.FftResult.LineState;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.GeneratorCorrectionsRequest;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.CorrectionStore;
import org.edgo.audio.measure.gui.common.DebugSwitches;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioBackend;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * Closed-loop DAC predistortion engine for the GUI wizard: a decision object
 * the FFT worker drives, with no thread of its own.  The worker hands it
 * every published result on the worker thread ({@link #onResult}); the
 * engine takes a cheap decision on it and never sleeps or blocks:
 *
 * <ol>
 *   <li>no decision until the FLL has settled the tone on its bin and the
 *       coherent average's kappa is refined;</li>
 *   <li>every correctable line whose raw level stands
 *       {@link HarmonicCompensation#APPLY_CLEARANCE_DB} or more over its
 *       local band top is corrected by the full step in that cycle - all of
 *       them in one request to the generator - and the restart of its
 *       averaging is requested; a line waiting for its reset is not decided
 *       on;</li>
 *   <li>while the distortion, every line held at its local band top, stands
 *       at or under the target T, lines are parked - never applied nor
 *       judged again: a line under {@value #PARK_SHARE_FRACTION} x T over the
 *       square root of the line count (half its share of T when every line
 *       carries the same power) at once; a line between
 *       {@value #PARK_RANGE_LOW_FRACTION} x T and T once its clearance over
 *       its local band top grew by less than {@value #PARK_EMERGE_DB} dB
 *       while its own averages doubled - and a parked line in that range is
 *       unparked once its clearance grew by more.  Every line
 *       parked: the run ends.  The distortion over T again: every line is
 *       unparked.</li>
 * </ol>
 *
 * <p>The correctable lines are the harmonics H2 up to the calc-max-harmonic
 * preference for a single tone and the products of the intermodulation grid
 * for a dual tone; a line the result lists beyond them is ignored.  The
 * result of the run is the correction in force when it ends.  Correction
 * requests and listener callbacks reach the UI thread through the injected
 * executor; the requests travel over the bus.
 */
@Log4j2
public final class PredistortionEngine {

    /** Why the run ended - drives the wizard's end-of-run message. */
    public enum StopReason {
        TARGET_REACHED, USER_STOP, ERROR;

        private StopReason() {}
    }

    /** Wizard callbacks, all delivered on the UI thread. */
    public interface Listener {
        /** The run has started. */
        void onStarted();
        /** The named lines' corrections went to the generator in one request,
         *  and the restart of their averaging is requested. */
        void onLinesApplied(List<String> names);
        /** The named lines' averaging has restarted: the mark that had them
         *  waiting for their reset is gone. */
        void onLinesRestarted(List<String> names);
        /** The named lines were parked: neither applied nor judged by the
         *  park rules again while they stay parked. */
        void onLinesParked(List<String> names);
        /** The named parked lines were unparked: decided on again. */
        void onLinesUnparked(List<String> names);
        /** The run ended.  {@code hasResult} is true when the correction in
         *  force holds at least one non-zero line (enables Save). */
        void onFinished(StopReason reason, boolean hasResult);
    }

    /** One correctable line as one result reads it: its slot in the
     *  correction set, its coefficients, its phasor in the set's frame, its
     *  frequency, its clearance over its local band top in dB and its
     *  averaging state. */
    private record Reading(int slot, LineRestarts.Line line, double re, double im, double hz,
                           double clearanceDb, LineState state) {}

    /** An applied line's averaging state on the result it was applied on:
     *  the collection's epoch, and how many frames the global frame count
     *  stood ahead of the line's local count.  Adding or taking out frames
     *  moves both counts alike; only the line's own reset widens the gap. */
    private record AppliedAt(long epoch, int countGap) {}

    /** A line's window under the second park rule, parked or not: its
     *  clearance over its local band top in dB and its local count when the
     *  window opened. */
    private record ClearanceWindow(double clearanceDb, int localCount) {}

    /** First park rule: a line under this fraction of its share of the
     *  target - the target over the square root of the line count - adds
     *  too little to the distortion to be worth an average. */
    private static final double PARK_SHARE_FRACTION = 0.5;
    /** Second park rule: the lower edge of the range it covers, as a
     *  fraction of the target; the upper edge is the target. */
    private static final double PARK_RANGE_LOW_FRACTION = 0.5;
    /** Second park rule: the factor the line's own averages grow by over a
     *  window - doubled, the band top of noise falls 3 dB. */
    private static final double PARK_COUNT_FACTOR = 2.0;
    /** Second park rule: a line whose clearance grew by less than this over
     *  its window is parked - noise sinks with its band top, a residual
     *  stands clear of it; a parked line whose clearance grew by more is
     *  unparked. */
    private static final double PARK_EMERGE_DB = 1.0;
    /** Tone counts as aligned when the measured fundamental STOPS moving -
     *  consecutive results agree to within this fraction of an FFT bin.  The
     *  test is result-to-result stability, NOT a match against a computed
     *  target frequency (which need not equal the measured bin): an
     *  already-locked generator clears it at once. */
    private static final double ALIGN_BIN_FRACTION = 0.1;
    /** Consecutive agreeing results required before the first decision. */
    private static final int    ALIGN_RESULTS = 2;
    private static final double LOG_BASE = 10.0;
    /** Decibels per decade of an amplitude ratio. */
    private static final double AMPLITUDE_DB_PER_DECADE = 20.0;
    private static final double PERCENT = 100.0;
    /** The statistics file of a run, in the logs directory:
     *  {@code dpd-stats-<timestamp>.csv}. */
    private static final String STATS_FILE_PREFIX = "dpd-stats-";
    private static final String STATS_FILE_SUFFIX = ".csv";
    private static final DateTimeFormatter STATS_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    /** The tones a dual-tone line is named by. */
    private static final String TONE1 = "F1";
    private static final String TONE2 = "F2";

    /** Runs a task on the UI thread, deferred - listener callbacks and the
     *  correction requests go through it. */
    private final Executor        uiThread;
    /** Carries the correction requests to the generator. */
    private final MessageBus      bus;
    /** The averaging restarts of the applied lines and the statistics
     *  export. */
    private final LineRestarts    lineRestarts;
    /** Loaded {@code .frc} corrections - the engine de-embeds their phase off
     *  the raw measured phasors itself when computing the DAC correction (the
     *  store only holds the loaded files). */
    private final CorrectionStore correctionStore;
    private final Listener        listener;
    /** The applied lines no result has shown waiting for their reset yet,
     *  each with its averaging state at the apply; worker thread.  The
     *  restart request reaches the worker only once the UI thread has sent
     *  the correction, and the mark follows from there, so results in
     *  between still show such a line unmarked on its old average. */
    private final Map<LineRestarts.Line, AppliedAt> requested = new HashMap<>();
    /** The lines a result has shown waiting for their reset; worker
     *  thread. */
    private final Set<LineRestarts.Line> waiting = new HashSet<>();
    /** The parked lines: never applied, and judged only for their unpark
     *  while the held distortion stays at or under the target.  Worker
     *  thread. */
    private final Set<LineRestarts.Line> parked = new HashSet<>();
    /** The open windows of the second park rule.  Worker thread. */
    private final Map<LineRestarts.Line, ClearanceWindow> clearanceWindows = new HashMap<>();

    private volatile boolean stopRequested;
    private volatile boolean finished;
    /** The last result's global frame count - the frames of the whole
     *  collection. */
    @Getter private volatile int frameCount;
    /** The distortion in percent with every line held at its local band top,
     *  as the last decided result measured it - the figure the stop rule
     *  judges, and the run's distortion for the wizard's Save.
     *  {@link Double#NaN} before the first decided result. */
    @Getter private volatile double heldDistortionPct = Double.NaN;
    /** True when this run is compensating a two-tone signal (drives the
     *  wizard's save / apply branch). */
    @Getter private boolean dualTone;
    private double targetPct;
    /** Harmonic slots of the correction sets, H2 first. */
    private int    maxHarmonics;
    /** The correction set the worker thread steps line by line: the
     *  single-tone one, or the dual-tone one; the other stays null. */
    private HarmonicCompensation harmonics;
    private IntermodCompensation intermod;
    /** The single-tone correction set the generator was given last, for the
     *  wizard's Save; committed on the UI thread together with the request
     *  that carried it, so it is always what the generator plays.
     *  {@code null} before the first apply and in dual-tone mode. */
    @Getter private HarmonicCompensation harmonicsInForce;
    /** The dual-tone counterpart of {@link #harmonicsInForce}; {@code null}
     *  before the first apply and in single-tone mode. */
    @Getter private IntermodCompensation intermodInForce;
    /** Latched once the FLL has settled the tone; worker thread. */
    private boolean aligned;
    private int     agreeingResults;
    private double  lastFundamentalHz = Double.NaN;

    public PredistortionEngine(Executor uiThread, MessageBus bus, LineRestarts lineRestarts,
                               CorrectionStore correctionStore, Listener listener) {
        this.uiThread        = uiThread;
        this.bus             = bus;
        this.lineRestarts    = lineRestarts;
        this.correctionStore = correctionStore;
        this.listener        = listener;
    }

    /**
     * Starts a run against the already-running generator and analyser: a
     * fresh state, the measurement preferences, the generator's table
     * cleared, then {@link Listener#onStarted}.  UI thread, before the
     * engine is registered on the worker - the worker thread reads what is
     * set here from then on.
     */
    public void start(double targetThdPct) {
        // A fresh run starts from a CLEAN state: a re-started wizard must
        // behave exactly like the first start.
        stopRequested     = false;
        finished          = false;
        frameCount        = 0;
        heldDistortionPct = Double.NaN;
        targetPct         = targetThdPct;
        aligned           = false;
        agreeingResults   = 0;
        lastFundamentalHz = Double.NaN;
        requested.clear();
        waiting.clear();
        parked.clear();
        clearanceWindows.clear();
        harmonicsInForce  = null;
        intermodInForce   = null;
        Preferences p = Preferences.instance();
        // The sets index harmonics from H2, so the highest harmonic number
        // in the preferences (H9) means one slot fewer.
        maxHarmonics = Math.max(1, p.getFftCalcMaxHarmonic() - 1);
        dualTone     = p.getGenSignalForm().isDualTone();
        harmonics    = dualTone ? null : new HarmonicCompensation(maxHarmonics);
        intermod     = dualTone ? new IntermodCompensation(maxHarmonics) : null;

        // Predistortion needs a deep, phase-coherent, generator-locked
        // measurement: switch the FFT to INFINITE coherent averaging (each
        // applied line restarts its own average), take the fundamental from
        // the generator and lock the tone with the FLL.  Window + mains
        // suppression are only RECOMMENDED - the user's choice is left
        // intact, with a hint logged when it isn't ideal.
        p.setFftCoherentAveraging(true);
        p.setFftAverages(Double.POSITIVE_INFINITY);
        p.setFftFundFromGenerator(true);
        p.setFftAlignGenerator(AlignGenerator.FLL);
        if (log.isInfoEnabled()) {
            if (p.getFftWindow() != WindowType.BH7) {
                log.info("Predistortion: FFT window is {} - BH7 is recommended for the deepest harmonic separation.",
                        p.getFftWindow());
            }
            if (p.getFftMainsSuppression() != MainsSuppression.NONE) {
                log.info("Predistortion: mains suppression is {} - recommend turning it OFF (it can notch a measured harmonic).",
                        p.getFftMainsSuppression());
            }
        }

        // Always calibrate from the RAW DAC: drop any table still on the
        // generator (a set an earlier run in this window hot-swapped) so the
        // run starts on the true distortion and the set builds the full
        // correction from scratch - otherwise the first apply would replace
        // the loaded correction with only this run's residual delta.  A saved
        // correction file is not on the tone by now: the wizard plays the
        // plain sibling of a compensated waveform for the run.  On a plain
        // tone that carries nothing the drop changes nothing and announces
        // nothing.
        bus.publish(Events.GENERATOR_CORRECTIONS, new GeneratorCorrectionsRequest(
                GeneratorCorrectionsRequest.Kind.CLEAR, null, null, null, null, null));
        on(listener::onStarted);
    }

    /** Ends the run at once with {@link StopReason#USER_STOP}; a correction
     *  still queued for the UI thread is dropped there.  UI thread. */
    public void stop() {
        stopRequested = true;
        finish(StopReason.USER_STOP);
    }

    // -------------------------------------------------------------------------
    // Decision
    // -------------------------------------------------------------------------

    /**
     * Takes the decision on one result - called by the worker on the worker
     * thread right after it publishes the result.  The decision reads the
     * captured raw peak phasors, the line states and the scalars of the
     * analysis; nothing of {@code result} is kept past the call.  Cheap and
     * never blocking; a no-op once the run has ended.
     */
    public void onResult(FftResult result) {
        if (finished || stopRequested) return;
        frameCount = result.frameCount;
        try {
            decide(result);
        } catch (RuntimeException ex) {
            // The worker's analysis cycle outlives a failed decision; the run
            // does not.
            log.error("Predistortion engine failed", ex);
            finish(StopReason.ERROR);
        }
    }

    private void decide(FftResult r) {
        // Judged on every result, so the agreeing results are consecutive.
        boolean settled = alignedOn(r);
        // A result no coherent average stamped carries no line state.
        if (r.lineStates == null) return;
        reportRestarts(r);
        if (!settled || !r.coherentKappaRefined) return;
        List<Reading> readings = read(r);
        apply(r, readings);
        double[] linePcts = heldLinePercents(r, readings);
        double sumSquares = 0.0;
        for (double pct : linePcts) {
            sumSquares += pct * pct;
        }
        double heldPct = Math.sqrt(sumSquares);
        heldDistortionPct = heldPct;
        if (heldPct > targetPct) {
            if (!parked.isEmpty()) {
                List<String> unparked = new ArrayList<>();
                for (Reading z : readings) {
                    if (parked.contains(z.line())) unparked.add(lineName(z.line()));
                }
                on(() -> listener.onLinesUnparked(unparked));
                if (log.isInfoEnabled()) {
                    log.info("Predistortion: distortion {} % over the target {} % - every line unparked",
                            heldPct, targetPct);
                }
            }
            parked.clear();
            clearanceWindows.clear();
            return;
        }
        judgeParking(readings, linePcts);
        if (readings.isEmpty()) return;
        for (Reading z : readings) {
            if (!parked.contains(z.line())) return;
        }
        if (log.isInfoEnabled()) {
            log.info("Predistortion: every line parked - distortion {} % at or under the target {} %",
                    heldPct, targetPct);
        }
        finish(StopReason.TARGET_REACHED);
    }

    /**
     * Parks and unparks by the rules that hold while the held distortion
     * stands at or under the target.  An unparked line under
     * {@link #PARK_SHARE_FRACTION} of its share of the target is parked at
     * once.  A line between {@link #PARK_RANGE_LOW_FRACTION} x target and the
     * target is judged by its clearance over its local band top each time its
     * own averages grew {@link #PARK_COUNT_FACTOR} times: unparked, it is
     * parked when the clearance grew by less than {@link #PARK_EMERGE_DB} dB;
     * parked, it is unparked when the clearance grew by more.  The clearance,
     * not the held level: a line under its band top is held at that band top,
     * which only falls with averaging.  A line without a reading of its own
     * is not judged; a line that leaves the range, or whose count fell back
     * (its average restarted), loses its window.
     *
     * @param linePcts each reading's held level in percent, in the order of
     *                 {@code readings}
     */
    private void judgeParking(List<Reading> readings, double[] linePcts) {
        double shareFloorPct = PARK_SHARE_FRACTION * targetPct / Math.sqrt(readings.size());
        double rangeLowPct   = PARK_RANGE_LOW_FRACTION * targetPct;
        List<String> underShare  = new ArrayList<>();
        List<String> notEmerging = new ArrayList<>();
        List<String> emerging    = new ArrayList<>();
        for (int i = 0; i < readings.size(); i++) {
            Reading z = readings.get(i);
            LineRestarts.Line line = z.line();
            if (!standsOnItsOwnReading(z)) continue;
            boolean isParked = parked.contains(line);
            double pct = linePcts[i];
            if (!isParked && pct < shareFloorPct) {
                parkLine(line);
                underShare.add(lineName(line));
                continue;
            }
            if (pct < rangeLowPct || pct >= targetPct) {
                clearanceWindows.remove(line);
                continue;
            }
            double growthDb = clearanceGrowthDb(line, z);
            if (isParked && growthDb > PARK_EMERGE_DB) {
                parked.remove(line);
                clearanceWindows.remove(line);
                emerging.add(lineName(line));
            } else if (!isParked && growthDb < PARK_EMERGE_DB) {
                parkLine(line);
                notEmerging.add(lineName(line));
            }
        }
        if (log.isInfoEnabled()) {
            if (!underShare.isEmpty()) {
                log.info("Predistortion: {} parked - under {} % ({} x the target over the square root of {} lines)",
                        String.join(", ", underShare), shareFloorPct, PARK_SHARE_FRACTION, readings.size());
            }
            if (!notEmerging.isEmpty()) {
                log.info("Predistortion: {} parked - clearance grown under {} dB while its averages grew {} times",
                        String.join(", ", notEmerging), PARK_EMERGE_DB, PARK_COUNT_FACTOR);
            }
            if (!emerging.isEmpty()) {
                log.info("Predistortion: {} unparked - clearance grown over {} dB while its averages grew {} times",
                        String.join(", ", emerging), PARK_EMERGE_DB, PARK_COUNT_FACTOR);
            }
        }
        List<String> nowParked = new ArrayList<>(underShare);
        nowParked.addAll(notEmerging);
        if (!nowParked.isEmpty()) on(() -> listener.onLinesParked(nowParked));
        if (!emerging.isEmpty()) on(() -> listener.onLinesUnparked(emerging));
    }

    /**
     * The growth in dB of the line's clearance over its local band top across
     * its window, judged once the line's own averages grew
     * {@link #PARK_COUNT_FACTOR} times - the next window opens there.
     * {@link Double#NaN} while the window runs, where it opens (the first
     * time, or after the line's count fell back), and without a clearance.
     */
    private double clearanceGrowthDb(LineRestarts.Line line, Reading z) {
        double clearanceDb = z.clearanceDb();
        if (!Double.isFinite(clearanceDb)) return Double.NaN;
        int count = z.state().localCount();
        ClearanceWindow w = clearanceWindows.get(line);
        boolean opens = w == null || count < w.localCount();
        if (!opens && count < PARK_COUNT_FACTOR * w.localCount()) return Double.NaN;
        clearanceWindows.put(line, new ClearanceWindow(clearanceDb, count));
        return opens ? Double.NaN : clearanceDb - w.clearanceDb();
    }

    private void parkLine(LineRestarts.Line line) {
        parked.add(line);
        clearanceWindows.remove(line);
    }

    /** True once the measured fundamental is stable - consecutive results
     *  agree within {@link #ALIGN_BIN_FRACTION} of a bin for
     *  {@link #ALIGN_RESULTS} results - and from then on for the whole run. */
    private boolean alignedOn(FftResult r) {
        if (aligned) return true;
        if (!Double.isFinite(r.fundamentalHzRefined) || !(r.freqResolution > 0)) return false;
        double hz = r.fundamentalHzRefined;
        if (Double.isFinite(lastFundamentalHz)
                && Math.abs(hz - lastFundamentalHz) <= r.freqResolution * ALIGN_BIN_FRACTION) {
            aligned = ++agreeingResults >= ALIGN_RESULTS;
        } else {
            agreeingResults = 0;
        }
        lastFundamentalHz = hz;
        return aligned;
    }

    /**
     * Follows each applied line to its reset and reports the lines whose
     * local region has been reset: a line seen waiting whose mark has gone,
     * or a requested line reset without a result showing the mark in
     * between ({@link #resetSince}).  An applied line still unmarked on its
     * old average stays requested.
     */
    private void reportRestarts(FftResult r) {
        List<String> restarted = new ArrayList<>();
        for (LineState s : r.lineStates) {
            LineRestarts.Line line = new LineRestarts.Line(s.a(), s.b());
            if (s.waitingReset()) {
                requested.remove(line);
                waiting.add(line);
                continue;
            }
            boolean reset = waiting.remove(line);
            AppliedAt at = requested.get(line);
            if (at != null && resetSince(at, r, s)) {
                requested.remove(line);
                reset = true;
            }
            if (reset) restarted.add(lineName(line));
        }
        if (!restarted.isEmpty()) on(() -> listener.onLinesRestarted(restarted));
    }

    /**
     * True when the averaging of a line applied at {@code at} has been reset
     * since, as result {@code r} with the line's state {@code s} shows it:
     * the collection was reset (a new epoch), or restarted (kappa no longer
     * refined - only a restart or a reset clears it, and the apply needed it
     * set), or the line's own region was reset (the global frame count now
     * stands further ahead of the line's local count than at the apply).  A
     * discontinuity that takes frames out again lowers both counts alike and
     * reads as no reset.
     */
    private boolean resetSince(AppliedAt at, FftResult r, LineState s) {
        return r.epoch != at.epoch()
                || !r.coherentKappaRefined
                || r.frameCount - s.localCount() > at.countGap();
    }

    /** The correctable lines of {@code r} with their phasors, clearances and
     *  averaging states - the harmonics the single-tone set has a slot for,
     *  or the products on the dual-tone grid.  The clearances come from the
     *  result's line states. */
    private List<Reading> read(FftResult r) {
        boolean left = r.channelLeft;
        List<Reading> readings = new ArrayList<>();
        if (dualTone) {
            double f1Hz = r.fundamentalHzRefined;
            double f2Hz = r.fundamental2HzRefined;
            ProductPhasors z = intermod.measurePhasors(r, f1Hz, f2Hz,
                    calResponseAt(f1Hz, left)[1], calResponseAt(f2Hz, left)[1]);
            for (int g = 0; g < z.hz().length; g++) {
                addReading(readings, r, g, new LineRestarts.Line(z.coefA()[g], z.coefB()[g]),
                        z.re()[g], z.im()[g], z.hz()[g], z.clearanceDb()[g]);
            }
        } else {
            HarmonicPhasors z = harmonics.measurePhasors(r, calResponseAt(r.fundamentalHzRefined, left)[1]);
            for (int h = 0; h < z.hz().length; h++) {
                addReading(readings, r, h, new LineRestarts.Line(h + 2, 0),
                        z.re()[h], z.im()[h], z.hz()[h], z.clearanceDb()[h]);
            }
        }
        return readings;
    }

    /** Adds the line to {@code readings} when the result measured it and
     *  carries its averaging state; a line without either is neither applied
     *  nor judged by the stop rule. */
    private void addReading(List<Reading> readings, FftResult r, int slot, LineRestarts.Line line,
                            double re, double im, double hz, double clearanceDb) {
        if (!(hz > 0.0)) return;
        LineState s = r.lineState(line.a(), line.b());
        if (s != null) readings.add(new Reading(slot, line, re, im, hz, clearanceDb, s));
    }

    /** True when the line may be applied now: not parked, standing on a
     *  reading of its own, and its raw level
     *  {@link HarmonicCompensation#APPLY_CLEARANCE_DB} or more over its
     *  local band top. */
    private boolean standsClear(Reading z) {
        return !parked.contains(z.line()) && standsOnItsOwnReading(z)
                && Double.isFinite(z.clearanceDb()) && z.clearanceDb() >= HarmonicCompensation.APPLY_CLEARANCE_DB;
    }

    /** True when the line stands on a reading of its own: neither requested
     *  and not yet marked nor waiting for its reset, at least one frame since
     *  it, and a measured local band top. */
    private boolean standsOnItsOwnReading(Reading z) {
        LineState s = z.state();
        return !requested.containsKey(z.line()) && !s.waitingReset() && s.localCount() > 0
                && Double.isFinite(s.localBandTopDbFs());
    }

    /**
     * Applies every line of {@code readings} that stands clear: each one
     * stepped by the full step in the correction set, all of their generator
     * entries sent in one request, then the restart of their averaging
     * requested.  Only the changed lines travel; the generator keeps every
     * other entry.  The restart is requested on the UI thread right after
     * the request is sent, so the worker places the restart's bound from the
     * moment the generator received the change, not from the decision.
     */
    private void apply(FftResult r, List<Reading> readings) {
        List<Reading> clear = new ArrayList<>();
        for (Reading z : readings) {
            if (standsClear(z)) clear.add(z);
        }
        if (clear.isEmpty()) return;
        int n = clear.size();
        DoubleFunction<double[]> cal = calResponseFor(r.channelLeft);
        double adcFsVrms = adcFullScaleVrms();
        double dacVrms   = dacFundamentalVrms();
        double[] ampRatios       = new double[n];
        double[] phiInits        = new double[n];
        int[]    harmonicNumbers = dualTone ? null : new int[n];
        int[]    coefA           = dualTone ? new int[n] : null;
        int[]    coefB           = dualTone ? new int[n] : null;
        List<LineRestarts.Line> lines = new ArrayList<>(n);
        List<String> names = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Reading z = clear.get(i);
            if (dualTone) {
                IntermodCompensation.GeneratorCorrections e = intermod.applyLine(z.line().a(), z.line().b(),
                        z.re(), z.im(), z.hz(), cal, adcFsVrms, dacVrms);
                ampRatios[i] = e.ampRatios()[0];
                phiInits[i]  = e.phiInits()[0];
                coefA[i]     = e.coefA()[0];
                coefB[i]     = e.coefB()[0];
            } else {
                GeneratorCorrections e = harmonics.applyLine(z.slot(), z.re(), z.im(), z.hz(),
                        r.fundamentalHzRefined, cal, adcFsVrms, dacVrms);
                ampRatios[i]       = e.ampRatios()[0];
                phiInits[i]        = e.phiInits()[0];
                harmonicNumbers[i] = e.harmonicNumbers()[0];
            }
            lines.add(z.line());
            names.add(lineName(z.line()));
        }
        GeneratorCorrectionsRequest request = new GeneratorCorrectionsRequest(dualTone
                ? GeneratorCorrectionsRequest.Kind.INTERMOD_LINES
                : GeneratorCorrectionsRequest.Kind.HARMONIC_LINES,
                ampRatios, harmonicNumbers, coefA, coefB, phiInits);
        HarmonicCompensation harmonicsAfter = dualTone ? null : harmonics.copy();
        IntermodCompensation intermodAfter  = dualTone ? intermod.copy() : null;
        // Every correction sent to the generator is guarded by the stop flag
        // ON THE UI THREAD: the wizard's close requests the stop and then
        // reverts the generator, both on that thread, and a set already
        // queued behind the close would otherwise land after the revert and
        // leave this run's corrections playing under the restored form.
        // Checked where it runs, the set is ordered against the revert.  The
        // generator is never held here: the sets travel over the bus.  The
        // set in force, the restart request and the log follow the publish in
        // the same place, so a set dropped there is neither in force nor
        // restarts a line.
        on(() -> {
            if (stopRequested) return;
            bus.publish(Events.GENERATOR_CORRECTIONS, request);
            lineRestarts.requestLineRestart(lines);
            harmonicsInForce = harmonicsAfter;
            intermodInForce  = intermodAfter;
            logApply(names, clear);
            listener.onLinesApplied(names);
        });
        // Not decided on again until a result shows the reset or its mark.
        for (Reading z : clear) {
            requested.put(z.line(), new AppliedAt(r.epoch, r.frameCount - z.state().localCount()));
        }
    }

    /**
     * Each line's level in percent of the fundamental, both de-embedded by
     * the loaded {@code .frc} response at their own frequencies.  A line is
     * the larger of its raw level and its local band top (the raw level alone
     * while the line has no measured top).  The fundamental is the manual
     * one where it is set, else the measured one - for a dual tone the two
     * measured tones summed.  Summed in power the lines give the held
     * distortion.
     *
     * @return one level per reading, in the order of {@code readings}
     */
    private double[] heldLinePercents(FftResult r, List<Reading> readings) {
        boolean left = r.channelLeft;
        double fundLin = deembeddedFundamentalLinear(r, left);
        double[] pcts = new double[readings.size()];
        for (int i = 0; i < readings.size(); i++) {
            Reading z = readings.get(i);
            double rawLin  = Math.hypot(z.re(), z.im());
            double topDbFs = z.state().localBandTopDbFs();
            double heldLin = Double.isFinite(topDbFs)
                    ? Math.max(rawLin, Math.pow(LOG_BASE, topDbFs / AMPLITUDE_DB_PER_DECADE))
                    : rawLin;
            pcts[i] = PERCENT * heldLin / calResponseAt(z.hz(), left)[0] / fundLin;
        }
        return pcts;
    }

    /** The fundamental the distortion is a ratio against, linear on the dBFS
     *  scale: the manual fundamental where it is set; else the measured tone
     *  de-embedded at its frequency - for a dual tone F1 and F2 each so and
     *  summed. */
    private double deembeddedFundamentalLinear(FftResult r, boolean left) {
        if (!dualTone && Double.isFinite(r.fundamentalTrueDbFs)) {
            return Math.pow(LOG_BASE, r.fundamentalTrueDbFs / AMPLITUDE_DB_PER_DECADE);
        }
        double f1Re = !Double.isNaN(r.rawFundRe) ? r.rawFundRe : r.re[r.fundamentalBin];
        double f1Im = !Double.isNaN(r.rawFundIm) ? r.rawFundIm : r.im[r.fundamentalBin];
        double f1Lin = Math.hypot(f1Re, f1Im) / calResponseAt(r.fundamentalHzRefined, left)[0];
        if (!dualTone) return f1Lin;
        return f1Lin + intermod.tone2RawLinear(r) / calResponseAt(r.fundamental2HzRefined, left)[0];
    }

    /** Ends the run once: the statistics export while the debug switch is
     *  on, then {@link Listener#onFinished} on the UI thread with whether the
     *  correction in force holds a line. */
    private synchronized void finish(StopReason reason) {
        if (finished) return;
        finished = true;
        if (DebugSwitches.WRITE_DPD_STATS) {
            lineRestarts.exportStatistics(AppPaths.instance().getLogsDir().resolve(
                    STATS_FILE_PREFIX + LocalDateTime.now().format(STATS_STAMP) + STATS_FILE_SUFFIX));
        }
        if (log.isInfoEnabled()) {
            log.info("Predistortion finished: {}", reason);
        }
        on(() -> listener.onFinished(reason, hasResult()));
    }

    /** True when the correction in force holds at least one non-zero line.
     *  UI thread. */
    private boolean hasResult() {
        return dualTone
                ? intermodInForce != null && intermodInForce.hasCorrections()
                : harmonicsInForce != null && harmonicsInForce.hasCorrections();
    }

    /** One line per apply: the applied lines and each one's clearance over
     *  its local band top in dB, one decimal. */
    private void logApply(List<String> names, List<Reading> applied) {
        if (!log.isInfoEnabled()) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < applied.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(names.get(i)).append('=')
              .append(String.format(Locale.US, "%.1f", applied.get(i).clearanceDb()));
        }
        log.info("Predistortion applied, clearance over the local band top in dB: {}", sb);
    }

    /** A line's name in the wizard: "H2" for harmonic 2 of a single tone;
     *  for a dual tone the terms of the product, the positive one first -
     *  "2F1-F2", "F2-F1", "F1+F2", "3F1". */
    private String lineName(LineRestarts.Line line) {
        int a = line.a();
        int b = line.b();
        if (!dualTone) return "H" + a;
        if (b == 0) return term(a, TONE1);
        if (a == 0) return term(b, TONE2);
        if (a > 0 && b > 0) return term(a, TONE1) + "+" + term(b, TONE2);
        return a > 0 ? term(a, TONE1) + "-" + term(-b, TONE2)
                     : term(b, TONE2) + "-" + term(-a, TONE1);
    }

    /** One term of a product name: the tone with its coefficient, a
     *  coefficient of one left out. */
    private String term(int coefficient, String tone) {
        return coefficient == 1 ? tone : coefficient + tone;
    }

    // -------------------------------------------------------------------------
    // De-embedding and references
    // -------------------------------------------------------------------------

    /** Combined calibration response {@code [magLin, phaseRad]} the loaded
     *  {@code .frc} corrections impose at {@code freqHz} for the analysed channel
     *  - the product of {@code H(f)} over every loaded entry (its left or right
     *  calibration per {@code left}): magnitudes multiply, phases add.  Entries
     *  whose calibration does not span {@code freqHz} contribute unit response,
     *  matching the display de-embed which only corrects in-range bins.  The
     *  correction divides each raw fundamental / harmonic / intermod-product
     *  phasor by this (de-embedding both magnitude and phase). */
    private double[] calResponseAt(double freqHz, boolean left) {
        double mag = 1.0, phase = 0.0;
        if (correctionStore != null && freqHz > 0.0) {
            for (CorrectionStore.Entry e : correctionStore.getEntries()) {
                FreqRespCalibration cal = left ? e.getCalibration().left()
                                               : e.getCalibration().right();
                if (cal == null || cal.freqs.length == 0) continue;
                if (freqHz < cal.freqs[0] || freqHz > cal.freqs[cal.freqs.length - 1]) continue;
                double[] h = FreqRespCalHelper.interpolate(cal, freqHz);
                mag   *= h[0];
                phase += h[1];
            }
        }
        return new double[]{ mag, phase };
    }

    /** The loaded {@code .frc} response as a frequency function for the given
     *  channel - so the save path applies the same de-embed as the live apply. */
    public DoubleFunction<double[]> calResponseFor(boolean left) {
        return f -> calResponseAt(f, left);
    }

    /** The ADC full scale (V RMS) of the left channel in force - the
     *  dBFS-to-volts step of every correction entry. */
    private double adcFullScaleVrms() {
        return AudioBackend.instance().calibrationOrDefault(true).fsRms(Channel.L);
    }

    /** The DAC fundamental (V RMS) the run's entries are ratios against: the
     *  F1 tone's level for a dual tone, the generator amplitude otherwise. */
    private double dacFundamentalVrms() {
        return dualTone ? dualToneFundamentalVrms() : generatorAmplitudeVrms();
    }

    /** The F1 tone's DAC output level (Vrms) - the dual-tone correction's ratio
     *  reference (the products are injected relative to F1).  Derived from the
     *  total generator amplitude and the dual-tone split: with weights
     *  {@code w1 = split%, w2 = 100-split%}, tone 1 carries
     *  {@code total * w1 / sqrt(w1^2 + w2^2)} of the combined RMS - the
     *  tones' summed level times {@code w1}. */
    public double dualToneFundamentalVrms() {
        return dualToneSumVrms() * Preferences.instance().getGenDualToneSplitPct() / PERCENT;
    }

    /** The two tones' DAC output levels summed (Vrms): each tone carries
     *  {@code total * w / sqrt(w1^2 + w2^2)} of the combined RMS, and the
     *  weights sum to one. */
    private double dualToneSumVrms() {
        double w1 = Preferences.instance().getGenDualToneSplitPct() / PERCENT;
        return generatorAmplitudeVrms() / Math.hypot(w1, 1.0 - w1);
    }

    /** The left lane's entered generator amplitude as V RMS against the output
     *  device's full scale in force - the lane the predistortion measures. */
    private double generatorAmplitudeVrms() {
        return AudioBackend.instance().calibrationOrDefault(false)
                .amplitudeVrms(Preferences.instance().getGenAmplitudeLeft(), Channel.L);
    }

    /** Delivers {@code callback} on the UI thread. */
    private void on(Runnable callback) {
        uiThread.execute(callback);
    }
}
