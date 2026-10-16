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
import java.util.List;
import java.util.function.DoubleFunction;

import org.edgo.audio.measure.dsp.FreqRespCalHelper;
import org.edgo.audio.measure.dsp.FreqRespCalibration;
import org.edgo.audio.measure.dsp.HarmonicCompensation;
import org.edgo.audio.measure.dsp.HarmonicCompensation.HarmonicPhasors;
import org.edgo.audio.measure.dsp.IntermodCompensation;
import org.edgo.audio.measure.dsp.IntermodCompensation.ProductPhasors;
import org.edgo.audio.measure.enums.AlignGenerator;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.MainsSuppression;
import org.edgo.audio.measure.enums.WindowType;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.gui.common.CorrectionStore;
import org.edgo.audio.measure.gui.common.GuiUtil;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioBackend;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * One-shot DAC predistortion engine for the GUI wizard: a decision object the
 * FFT worker drives, with no thread of its own.  The worker hands it every
 * published result on the worker thread ({@link #onResult}); the engine
 * takes each line's accumulator value off the result and judges whether the
 * line's running means have settled:
 *
 * <ol>
 *   <li>a result is taken from the frame count {@value #SKIPPED_FRAMES} on,
 *       the count at which the accumulator's kappa measurement has closed,
 *       in every phase alignment mode: its lines are read against the DAC
 *       fundamental's phase where the alignment states it, else against the
 *       measured tone less the calibration's static phase;</li>
 *   <li>every line the result carries has its phase and level put into its
 *       window of the last {@value #WINDOW_VALUES} values; a line the result
 *       does not carry is skipped on it; a line is gated while its raw level
 *       stands {@link HarmonicCompensation#APPLY_CLEARANCE_DB} or more over
 *       its local band top;</li>
 *   <li>from a line's second value on, its tail means - the circular means
 *       of the newest N phases for N = 1 .. the values present, at most
 *       {@value #WINDOW_VALUES} - give the line's wobble, the highest less
 *       the lowest of them;</li>
 *   <li>the line's limit is the phase accuracy its compensation needs to
 *       leave a residual at or under the phase's part of the line's share of
 *       the target THD - half the target over the square root of eight, the
 *       count of lines; the other half is left to the magnitude error and
 *       the drift between the measurement and the applied file: a
 *       line standing L over the fundamental, both de-embedded, compensated
 *       with a phase error d leaves 2 L sin(d / 2), so the limit is
 *       2 asin(share / (2 L)).  A line at or under its share needs no
 *       accuracy and has no limit; only a gated line over its share is
 *       awaited;</li>
 *   <li>settled: the result averages at least the least averages set in the
 *       preferences (below that the band tops of the weaker lines have not
 *       fallen far enough for their readings to stand clear, whatever the
 *       phase wobbles say) and every awaited line has a full window and a
 *       wobble within its
 *       limit on that same result.  The correction is then built once from
 *       each line's moving average - the circular mean of its phases, the
 *       linear mean of its levels - as one full step of the form's correction
 *       set, and the run ends.</li>
 * </ol>
 *
 * <p>The lines are the harmonics H2 .. H{@value #HIGHEST_HARMONIC} for a
 * single tone and the products of the intermodulation grid for a dual
 * tone.  The listener callbacks reach the UI thread through the
 * application's marshal.  The engine talks to neither the bus nor the
 * generator.
 */
@Log4j2
public final class PredistortionEngine {

    /** Why the engine ended the run - drives the wizard's end-of-run
     *  message.  A stop by hand is the wizard's own action and has no
     *  reason here. */
    public enum StopReason {
        SETTLED, ERROR;

        private StopReason() {}
    }

    /** Wizard callbacks, both delivered on the UI thread. */
    public interface Listener {
        /** The lines as they stand: after the start, with the form's lines
         *  laid out fresh, and after every result the run takes, with what
         *  that result made of them.  On the settling result it precedes
         *  {@link #onFinished}. */
        void onResult(List<LineReadout> readouts);
        /** The engine ended the run.  {@code hasResult} is true when the run
         *  settled and the correction built on the settle holds at least one
         *  line (enables Save). */
        void onFinished(StopReason reason, boolean hasResult);
    }

    /** One line as the wizard shows it: its name, whether the last taken
     *  result gated it, whether its window is full, its wobble over the
     *  values present and its limit, in degrees, and its clearance over its
     *  local band top on the last taken result, in dB - the wobble
     *  {@link Double#NaN} while fewer than two values are in, the limit
     *  before the line's first value and when that value's level against
     *  the fundamental is not known, the clearance while not computable; the
     *  limit {@link Double#POSITIVE_INFINITY} when the line stands at or
     *  under half its share of the target, where no phase error leaves more
     *  than the share. */
    public record LineReadout(String name, boolean gated, boolean windowFull,
                              double wobbleDeg, double limitDeg, double clearanceDb) {}

    /**
     * One line's window of its last {@link #WINDOW_VALUES} accumulator values
     * and what the last taken result made of it.
     */
    private static final class LineWindow {

        private final String   name;
        /** The line's share of the run's target THD, percent of the
         *  fundamental. */
        private final double   sharePct;
        /** Each value's phase (rad) and level (linear, full scale 1), a ring:
         *  {@link #newest} indexes the last value written. */
        private final double[] phaseRad = new double[WINDOW_VALUES];
        private final double[] level    = new double[WINDOW_VALUES];
        private int     newest = -1;
        private int     count;
        /** The line's frequency and its clearance over its local band top
         *  (dB) on the last taken result; 0 and {@link Double#NaN} when that
         *  result did not carry the line. */
        private double  frequencyHz;
        private double  clearanceDb  = Double.NaN;
        private boolean gated;
        /** True when the last taken result put the line at or under its
         *  share of the target: it needs no accuracy and is not awaited. */
        private boolean belowShare;
        private double  wobbleDeg    = Double.NaN;
        private double  limitDeg     = Double.NaN;
        /** The moving average: the circular mean of the phases present in the
         *  window and the linear mean of their levels. */
        private double  meanPhaseRad = Double.NaN;
        private double  meanLevel    = Double.NaN;

        LineWindow(String name, double sharePct) {
            this.name     = name;
            this.sharePct = sharePct;
        }

        /** Takes the line's phasor of one result into the window and derives
         *  the gate, the limit and, from the line's second value on, the tail
         *  means from it; {@code linePct} is the line's de-embedded level in
         *  percent of the de-embedded fundamental.  A line the result did not
         *  locate (no frequency) is skipped: nothing enters its window, it is
         *  not awaited, and it stands without frequency and clearance, so a
         *  correction built on this result leaves it out. */
        void take(double re, double im, double lineHz, double lineClearanceDb, double linePct) {
            if (!(lineHz > 0.0)) {
                frequencyHz = 0.0;
                clearanceDb = Double.NaN;
                gated       = false;
                return;
            }
            newest = (newest + 1) % WINDOW_VALUES;
            phaseRad[newest] = Math.atan2(im, re);
            level[newest]    = Math.hypot(re, im);
            if (count < WINDOW_VALUES) count++;
            frequencyHz = lineHz;
            clearanceDb = lineClearanceDb;
            // A reading under the clearance gate is a noise vector: it is not
            // awaited, and the correction set's own gate leaves it out.
            gated    = Double.isFinite(lineClearanceDb)
                    && lineClearanceDb >= HarmonicCompensation.APPLY_CLEARANCE_DB;
            // Compensated with a phase error d, the line leaves a residual of
            // 2 L sin(d / 2); the limit is the d that leaves its share - none
            // once the line is at or under half its share, where no d leaves
            // more.  A line at or under its share needs no accuracy and is not
            // awaited; its limit is still stated.  A level that is not known
            // gives no limit and is still awaited, so it holds the run.
            belowShare = linePct <= sharePct;
            limitDeg   = linePct > sharePct / 2.0
                    ? Math.toDegrees(2.0 * Math.asin(sharePct / (2.0 * linePct)))
                    : linePct >= 0.0 ? Double.POSITIVE_INFINITY : Double.NaN;
            // A single value has no spread: the wobble starts with the second.
            if (count >= 2) tailMeans();
        }

        /**
         * The tail means over the values present: for N = 1 .. the count of
         * values in the window (at most {@link #WINDOW_VALUES}) the circular
         * mean of the newest N phases, each phase taken as its offset from
         * the newest one, so the means come out wrapped about the newest
         * value.  Their spread is the wobble, the last of them the moving
         * average's phase; the levels give the moving average's level as
         * their linear mean.
         */
        private void tailMeans() {
            double newestPhase = phaseRad[newest];
            double sumCos   = 0.0;
            double sumSin   = 0.0;
            double sumLevel = 0.0;
            double tailMean = 0.0;
            double highest  = Double.NEGATIVE_INFINITY;
            double lowest   = Double.POSITIVE_INFINITY;
            for (int n = 0; n < count; n++) {
                int slot = (newest - n + WINDOW_VALUES) % WINDOW_VALUES;
                double offset = phaseRad[slot] - newestPhase;
                sumCos   += Math.cos(offset);
                sumSin   += Math.sin(offset);
                sumLevel += level[slot];
                tailMean  = Math.atan2(sumSin, sumCos);
                highest   = Math.max(highest, tailMean);
                lowest    = Math.min(lowest, tailMean);
            }
            wobbleDeg    = Math.toDegrees(highest - lowest);
            meanPhaseRad = newestPhase + tailMean;
            meanLevel    = sumLevel / count;
        }

        boolean isFull() {
            return count == WINDOW_VALUES;
        }

        /** True when the last taken result gated the line and did not put it
         *  at or under its share of the target - the settle waits for it. */
        boolean awaited() {
            return gated && !belowShare;
        }

        /** True when the window is full and its wobble within the limit; a
         *  limit or wobble that is not computable never is. */
        boolean withinLimit() {
            return isFull() && wobbleDeg <= limitDeg;
        }

        /** The moving average as a phasor, real part. */
        double meanRe() {
            return meanLevel * Math.cos(meanPhaseRad);
        }

        /** The moving average as a phasor, imaginary part. */
        double meanIm() {
            return meanLevel * Math.sin(meanPhaseRad);
        }

        LineReadout readout() {
            return new LineReadout(name, gated, isFull(), wobbleDeg, limitDeg, clearanceDb);
        }
    }

    /** Values each line's window holds - the most its running means span,
     *  and the count an awaited line needs to settle. */
    public static final int    WINDOW_VALUES = 256;
    /** The frame count from which on a result is taken: the count at which
     *  the coherent accumulator's kappa measurement has closed.  Over the
     *  frames before it the fundamental's fractional bin is still being
     *  refined, so a result of a lower count reads its lines' phases against
     *  a reference still in motion.  The accumulator's own constant
     *  ({@code CoherentAccumulator.KAPPA_MEAS_FRAMES}) is package-private in
     *  the core module and not reachable from here. */
    public static final int    SKIPPED_FRAMES   = 24;
    /** The highest harmonic a single-tone run corrects: its lines are
     *  H2 .. H9, whatever harmonic count the analyser is set to. */
    public static final int    HIGHEST_HARMONIC = 9;

    /** The single-tone correction sets index harmonics from H2, so the
     *  highest harmonic means one slot fewer. */
    private static final int    HARMONIC_SLOTS = HIGHEST_HARMONIC - 1;
    /** The lines a run corrects, eight for either form; the target THD is
     *  shared among them in power, so each line's share is the target over
     *  the square root of this count. */
    private static final int    LINE_COUNT = 8;
    /** The phase error is allowed this fraction of a line's share: the
     *  magnitude error and the drift between the measurement and the applied
     *  file spend the rest of it. */
    private static final double PHASE_SHARE = 0.2;
    /** The settle's correction is one full step of the correction set. */
    private static final double FULL_STEP = 1.0;
    private static final double PERCENT   = 100.0;
    /** A level in dBFS to its linear amplitude: 10^(dB / 20). */
    private static final double DECADE        = 10.0;
    private static final double DB_PER_DECADE = 20.0;
    /** The sides a dual-tone line is named by, as the IMD table names its
     *  products: lower - F2 - F1, or a multiple of the first tone less the
     *  second; upper - F1 + F2, or a multiple of the second tone less the
     *  first. */
    private static final String LOWER_SIDEBAND = "L";
    private static final String UPPER_SIDEBAND = "H";

    /** The least frame count a result needs to settle the run, read from the
     *  preferences at the start. */
    private int minAverages;
    /** Loaded {@code .frc} corrections.  Their phase at the tones goes into
     *  the reading of every result, where the correction sets use it only for
     *  a tone the result carries no DAC phase of: both tones without a phase
     *  alignment, F2 of a dual tone where the alignment leaves the second
     *  tone's DAC phase unset.  Their full response de-embeds the correction on the wizard's
     *  Save ({@link #calResponseFor}). */
    private final CorrectionStore correctionStore;
    private final Listener        listener;
    /** The run's lines in the order of the form's correction set. */
    private final List<LineWindow> lines = new ArrayList<>();

    /** No run is on before {@link #start}. */
    private boolean finished = true;
    /** True when this run is compensating a two-tone signal (drives the
     *  wizard's save / apply branch). */
    @Getter private boolean dualTone;
    /** The set the lines are read with on every result: the single-tone one
     *  or the dual-tone one; the other stays null. */
    private HarmonicCompensation harmonicReader;
    private IntermodCompensation intermodReader;
    /** The dual-tone grid's products, in the order of {@link #lines}: each
     *  product's multiple of the first tone and of the second; null for a
     *  single tone. */
    private int[] toneOneMultiples;
    private int[] toneTwoMultiples;
    /** The single-tone correction set built on the settle, for the wizard's
     *  Save.  {@code null} before the settle and in dual-tone mode. */
    @Getter private HarmonicCompensation harmonicsInForce;
    /** The dual-tone counterpart of {@link #harmonicsInForce}; {@code null}
     *  before the settle and in single-tone mode. */
    @Getter private IntermodCompensation intermodInForce;

    /** Lays out the lines of the form the preferences hold at once, so the
     *  readouts name them before the first start. */
    public PredistortionEngine(CorrectionStore correctionStore, Listener listener) {
        this.correctionStore = correctionStore;
        this.listener        = listener;
        layoutLines(Preferences.instance());
    }

    /** Lays out the run's lines for the form and the target the preferences
     *  hold: fresh windows, the reader of the form, the share of the target. */
    private void layoutLines(Preferences prefs) {
        double sharePct = PHASE_SHARE * prefs.getPredistortionTargetPct() / Math.sqrt(LINE_COUNT);
        dualTone = prefs.getGenSignalForm().isDualTone();
        lines.clear();
        if (dualTone) {
            harmonicReader = null;
            intermodReader = new IntermodCompensation();
            // Only the grid's products are read here; the tones are not known
            // before the first result.
            ProductPhasors grid = intermodReader.grid(0.0, 0.0);
            toneOneMultiples = grid.toneOneMultiples();
            toneTwoMultiples = grid.toneTwoMultiples();
            for (int product = 0; product < toneOneMultiples.length; product++) {
                lines.add(new LineWindow(lineName(toneOneMultiples[product], toneTwoMultiples[product]), sharePct));
            }
        } else {
            harmonicReader   = new HarmonicCompensation(HARMONIC_SLOTS);
            intermodReader   = null;
            toneOneMultiples = null;
            toneTwoMultiples = null;
            for (int slot = 0; slot < HARMONIC_SLOTS; slot++) {
                lines.add(new LineWindow(lineName(slot + 2, 0), sharePct));   // slot 0 is H2
            }
        }
    }

    /**
     * Starts a run against the already-running generator and analyser: a
     * fresh state, the measurement preferences, then the fresh lines to
     * {@link Listener#onResult}.  The form, the target THD (percent) and the
     * least averages are read from the preferences; every line gets an equal
     * share of the target.  UI thread, before the engine is registered on the
     * worker - the worker thread reads what is set here from then on.
     */
    public void start() {
        Preferences prefs = Preferences.instance();
        minAverages      = prefs.getPredistortionMinAverages();
        harmonicsInForce = null;
        intermodInForce  = null;
        layoutLines(prefs);
        finished = false;

        // Predistortion needs a deep, phase-coherent, generator-locked
        // measurement: switch the FFT to INFINITE coherent averaging, take the
        // fundamental from the generator and lock the tone with the FLL.
        // Window + mains suppression are only RECOMMENDED - the user's choice
        // is left intact, with a hint logged when it isn't ideal.
        prefs.setFftCoherentAveraging(true);
        prefs.setFftAverages(Double.POSITIVE_INFINITY);
        prefs.setFftFundFromGenerator(true);
        prefs.setFftAlignGenerator(AlignGenerator.FLL);
        if (log.isInfoEnabled()) {
            WindowType window = prefs.getFftWindow();
            if (window != WindowType.BH7) {
                log.info("Predistortion: FFT window is {} - BH7 is recommended for the deepest harmonic separation.",
                        window);
            }
            MainsSuppression mains = prefs.getFftMainsSuppression();
            if (mains != MainsSuppression.NONE) {
                log.info("Predistortion: mains suppression is {} - recommend turning it OFF (it can notch a measured harmonic).",
                        mains);
            }
        }
        publishReadouts();
    }

    // -------------------------------------------------------------------------
    // Decision
    // -------------------------------------------------------------------------

    /**
     * Takes one result - called by the worker on the worker thread right
     * after it publishes the result.  The engine reads the result's DAC
     * fundamental phase, its frame count and the lines' phasors; nothing of
     * {@code result} is kept past the call.  Cheap and never blocking.
     *
     * @return true on the result that settled the run - exactly once per
     *         run; false on every other call, including every call after the
     *         run ended
     */
    public boolean onResult(FftResult result) {
        if (finished) return false;
        boolean settled;
        try {
            settled = judge(result) && end();
        } catch (RuntimeException ex) {
            // The worker's analysis cycle outlives a failed judgement; the
            // run does not.
            if (log.isErrorEnabled()) {
                log.error("Predistortion engine failed", ex);
            }
            finish(StopReason.ERROR);
            return false;
        }
        // The lines as this result left them, ahead of the end it may have
        // brought: both go the same way to the UI thread, in this order.
        publishReadouts();
        if (settled) announce(StopReason.SETTLED);
        return settled;
    }

    /**
     * Takes {@code r} into the windows and judges the settle; on the settling
     * result the correction is built.
     *
     * @return true when {@code r} settled the run
     */
    private boolean judge(FftResult r) {
        // Every line's phase is read against the fundamental as the DAC
        // emitted it - the phase the alignment states where it runs, else the
        // measured tone less the calibration's static phase - and over the
        // collection's first frames the coherent average's fundamental bin is
        // still being refined: a result averaging fewer than SKIPPED_FRAMES
        // frames reads phases from no stable frame and would put noise into
        // the windows.
        if (r.frameCount < SKIPPED_FRAMES) return false;
        if (dualTone) {
            takeProducts(r);
        } else {
            takeHarmonics(r);
        }
        if (r.frameCount < minAverages || !settled()) return false;
        buildCorrection();
        return true;
    }

    /** Reads the harmonics of {@code r} with the single-tone set and takes
     *  them into the windows. */
    private void takeHarmonics(FftResult r) {
        HarmonicPhasors phasors = harmonicReader.measurePhasors(r,
                calResponseAt(r.fundamentalHzRefined, r.channelLeft)[1]);
        take(r, phasors.re(), phasors.im(), phasors.hz(), phasors.clearanceDb());
    }

    /** Reads the intermodulation products of {@code r} with the dual-tone set
     *  and takes them into the windows. */
    private void takeProducts(FftResult r) {
        boolean left = r.channelLeft;
        double  f1Hz = r.fundamentalHzRefined;
        double  f2Hz = r.fundamental2HzRefined;
        ProductPhasors phasors = intermodReader.measurePhasors(r, f1Hz, f2Hz,
                calResponseAt(f1Hz, left)[1], calResponseAt(f2Hz, left)[1]);
        take(r, phasors.re(), phasors.im(), phasors.hz(), phasors.clearanceDb());
    }

    /** Takes one result's phasors, index-parallel to {@link #lines}, into the
     *  lines' windows, each with its level in percent of the fundamental -
     *  the line de-embedded at its frequency over the de-embedded
     *  fundamental, the figures the displayed distortion is built from - and
     *  its clearance: the de-embedded accumulated line over the accumulated
     *  band top beside it.  The reader's clearance is the accumulated line
     *  over that band top as captured; de-embedding the line takes 20 log10
     *  of the response at its frequency off it. */
    private void take(FftResult r, double[] re, double[] im, double[] hz, double[] clearanceDb) {
        boolean left = r.channelLeft;
        double fundamentalLin = deembeddedFundamentalLinear(r, left);
        for (int i = 0; i < lines.size(); i++) {
            double response = calResponseAt(hz[i], left)[0];
            double linePct = PERCENT * Math.hypot(re[i], im[i]) / response / fundamentalLin;
            double lineClearanceDb = clearanceDb[i] - DB_PER_DECADE * Math.log10(response);
            lines.get(i).take(re[i], im[i], hz[i], lineClearanceDb, linePct);
        }
    }

    /** True when every awaited line has a full window and a wobble within its
     *  limit. */
    private boolean settled() {
        for (LineWindow line : lines) {
            if (line.awaited() && !line.withinLimit()) return false;
        }
        return true;
    }

    /**
     * Builds the correction once, as one full step of a fresh correction set
     * of the form: per line the moving average as a phasor, with the
     * frequency and the clearance of the last taken result.  The set's own
     * gate leaves out every line the last result did not gate: a line under
     * the clearance gate by its clearance, a line the result did not carry by
     * its missing frequency.
     */
    private void buildCorrection() {
        int lineCount = lines.size();
        double[] re          = new double[lineCount];
        double[] im          = new double[lineCount];
        double[] hz          = new double[lineCount];
        double[] clearanceDb = new double[lineCount];
        for (int i = 0; i < lineCount; i++) {
            LineWindow line = lines.get(i);
            re[i]          = line.meanRe();
            im[i]          = line.meanIm();
            hz[i]          = line.frequencyHz;
            clearanceDb[i] = line.clearanceDb;
        }
        if (dualTone) {
            IntermodCompensation correction = new IntermodCompensation();
            correction.accumulate(new ProductPhasors(toneOneMultiples, toneTwoMultiples, re, im, hz, clearanceDb),
                    FULL_STEP);
            intermodInForce = correction;
        } else {
            HarmonicCompensation correction = new HarmonicCompensation(HARMONIC_SLOTS);
            correction.accumulate(new HarmonicPhasors(re, im, hz, clearanceDb), FULL_STEP);
            harmonicsInForce = correction;
        }
    }

    /** A snapshot of every line's readout, in the order of the form's
     *  correction set.  On the thread that holds the engine: the UI thread
     *  while the engine is not registered on the worker, the worker thread
     *  while it is. */
    public List<LineReadout> readouts() {
        List<LineReadout> snapshot = new ArrayList<>(lines.size());
        for (LineWindow line : lines) {
            snapshot.add(line.readout());
        }
        return snapshot;
    }

    /** Ends the run once with {@code reason}; a call after the end changes
     *  nothing. */
    private void finish(StopReason reason) {
        if (end()) announce(reason);
    }

    /** Marks the run ended; false when it had ended already. */
    private boolean end() {
        if (finished) return false;
        finished = true;
        return true;
    }

    /** {@link Listener#onResult} on the UI thread with the lines' snapshot:
     *  in place when called there, posted from the worker thread. */
    private void publishReadouts() {
        List<LineReadout> snapshot = readouts();
        GuiUtil.marshal(() -> listener.onResult(snapshot));
    }

    /** {@link Listener#onFinished} on the UI thread, posted from the worker
     *  thread, with whether the correction built on the settle holds a
     *  line. */
    private void announce(StopReason reason) {
        boolean hasResult = hasResult();
        if (log.isInfoEnabled()) {
            log.info("Predistortion finished: {}", reason);
        }
        GuiUtil.marshal(() -> listener.onFinished(reason, hasResult));
    }

    /** True when the correction built on the settle holds at least one
     *  non-zero line. */
    private boolean hasResult() {
        return dualTone
                ? intermodInForce != null && intermodInForce.hasCorrections()
                : harmonicsInForce != null && harmonicsInForce.hasCorrections();
    }

    /** A line's name in the wizard: "H2" for harmonic 2 of a single tone;
     *  for a dual tone the IMD readout's name (ImdAnalyzer): "d2L" for
     *  F2 - F1 and "d2H" for F1 + F2; for a sideband "dnL" for
     *  (n-1) F1 - (n-2) F2 and "dnH" for (n-1) F2 - (n-2) F1 - n is one
     *  above the positive multiple, the side the tone it multiplies. */
    private String lineName(int toneOneMultiple, int toneTwoMultiple) {
        if (!dualTone) return "H" + toneOneMultiple;
        if (toneOneMultiple + toneTwoMultiple != 1) {
            return "d2" + (toneOneMultiple < 0 ? LOWER_SIDEBAND : UPPER_SIDEBAND);
        }
        return toneOneMultiple > 0
                ? "d" + (toneOneMultiple + 1) + LOWER_SIDEBAND
                : "d" + (toneTwoMultiple + 1) + UPPER_SIDEBAND;
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

    /** The fundamental the line levels are a ratio against, linear on the
     *  dBFS scale, computed as the displayed distortion computes it.  A single
     *  tone: the manual fundamental where set - the level before the notch,
     *  taken as it stands - else the measured tone de-embedded at its
     *  frequency.  A dual tone: |F1| + |F2|, the reference of the displayed
     *  intermodulation (ImdAnalyzer), each tone measured and de-embedded at
     *  its frequency, F2 being the {@code (0, 1)} entry of the analyser's
     *  product grid as the dual-tone set reads it; where the manual
     *  fundamental is set it is the two tones' combined level, split between
     *  them by their measured ratio.  {@link Double#NaN} when the result did
     *  not capture a measured tone. */
    private double deembeddedFundamentalLinear(FftResult r, boolean left) {
        boolean manual = Double.isFinite(r.fundamentalTrueDbFs);
        double manualLin = manual ? Math.pow(DECADE, r.fundamentalTrueDbFs / DB_PER_DECADE) : Double.NaN;
        double f1Lin = Math.hypot(r.rawFundRe, r.rawFundIm) / calResponseAt(r.fundamentalHzRefined, left)[0];
        if (!dualTone) return manual ? manualLin : f1Lin;
        double[] f2Phasor = intermodReader.secondTonePhasor(r);
        double f2RawLin = f2Phasor != null ? Math.hypot(f2Phasor[0], f2Phasor[1]) : Double.NaN;
        double f2Lin = f2RawLin / calResponseAt(r.fundamental2HzRefined, left)[0];
        return manual ? manualLin * (f1Lin + f2Lin) / Math.hypot(f1Lin, f2Lin) : f1Lin + f2Lin;
    }

    /** The loaded {@code .frc} response as a frequency function for the given
     *  channel - the de-embed the wizard's Save applies to the correction's
     *  entries as it writes the {@code .dpd} file. */
    public DoubleFunction<double[]> calResponseFor(boolean left) {
        return f -> calResponseAt(f, left);
    }

    /** The F1 tone's DAC output level (Vrms) - the dual-tone correction's ratio
     *  reference (the products are injected relative to F1).  Derived from the
     *  total generator amplitude and the dual-tone split: with weights
     *  {@code w1 = split%, w2 = 100-split%}, tone 1 carries
     *  {@code total * w1 / sqrt(w1^2 + w2^2)} of the combined RMS - the
     *  tones' summed level times {@code w1}. */
    public double dualToneFundamentalVrms() {
        return dualToneSum(generatorAmplitudeVrms()) * Preferences.instance().getGenDualToneSplitPct() / PERCENT;
    }

    /** The two tones' levels summed, from their combined RMS level split as
     *  configured: each tone carries {@code combined * w / sqrt(w1^2 + w2^2)}
     *  with {@code w1 = split%, w2 = 100-split%}, and the weights sum to
     *  one - the DAC amplitude's split. */
    private double dualToneSum(double combinedLevel) {
        double w1 = Preferences.instance().getGenDualToneSplitPct() / PERCENT;
        return combinedLevel / Math.hypot(w1, 1.0 - w1);
    }

    /** The left lane's entered generator amplitude as V RMS against the output
     *  device's full scale in force - the lane the predistortion measures. */
    private double generatorAmplitudeVrms() {
        return AudioBackend.instance().calibrationOrDefault(false)
                .amplitudeVrms(Preferences.instance().getGenAmplitudeLeft(), Channel.L);
    }

}
