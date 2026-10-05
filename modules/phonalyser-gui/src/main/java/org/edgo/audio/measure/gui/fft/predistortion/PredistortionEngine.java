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

import java.util.Locale;
import java.util.function.DoubleFunction;

import org.eclipse.swt.widgets.Display;
import org.edgo.audio.measure.common.AppPaths;
import org.edgo.audio.measure.dsp.FreqRespCalHelper;
import org.edgo.audio.measure.dsp.FreqRespCalibration;
import org.edgo.audio.measure.dsp.HarmonicCompensation;
import org.edgo.audio.measure.dsp.HarmonicCompensation.GeneratorCorrections;
import org.edgo.audio.measure.dsp.IntermodCompensation;
import org.edgo.audio.measure.enums.AlignGenerator;
import org.edgo.audio.measure.enums.MainsSuppression;
import org.edgo.audio.measure.enums.WindowType;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.fft.ImdResult;
import org.edgo.audio.measure.gui.common.CorrectionStore;
import org.edgo.audio.measure.gui.common.DebugSwitches;
import org.edgo.audio.measure.gui.fft.FftController;
import org.edgo.audio.measure.gui.fft.FftView;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.GeneratorCorrectionsRequest;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioBackend;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * Closed-loop DAC harmonic-predistortion engine for the GUI wizard - the
 * live counterpart of the CLI {@code IterativeCompensateMode}.  Runs the
 * iterative loop on a daemon thread against the ALREADY-RUNNING generator
 * and FFT analyser:
 *
 * <ol>
 *   <li>ensure the FFT is recording, wait for the FLL to settle the tone on
 *       its bin (read from the live {@link FftView#getLastResult()});</li>
 *   <li>each round: reset the FFT statistics, average for the configured
 *       duration, read the finalized result's THD + harmonics, and
 *       {@linkplain HarmonicCompensation#accumulate accumulate} the
 *       correction;</li>
 *   <li>hot-apply (over the bus, {@link Events#GENERATOR_CORRECTIONS}) the
 *       accumulated correction to the running generator (no restart), settle,
 *       reset and repeat.</li>
 * </ol>
 *
 * <p>Stops when the target THD is reached, when the best THD has improved by
 * less than {@value #STALL_REL_FRACTION} (relative) over the last
 * {@value #STALL_WINDOW} rounds, or on user request.  The best-THD round's
 * applied correction is retained for saving.  All widget access is marshalled
 * to the UI thread; listener callbacks are delivered on the UI thread.
 */
@Log4j2
public final class PredistortionEngine {

    /** Why {@link #runLoop} ended - drives the wizard's end-of-run message. */
    public enum StopReason {
        TARGET_REACHED, STALLED, USER_STOP, ERROR;

        private StopReason() {}
    }

    /** Live phase of the loop, polled by the wizard's refresh timer to show
     *  "what's going on" (the autotune-dialog rendering style). */
    public enum Phase {
        IDLE, ALIGNING, COLLECTING, APPLYING, SETTLING, FINISHED;

        private Phase() {}
    }

    /** Wizard callbacks, all delivered on the UI thread. */
    public interface Listener {
        /** Waiting for the FLL to settle the tone onto its bin. */
        void onAligning();
        /** One averaging round finished - {@code result} is a private copy,
         *  {@code averages} the number of frames it actually averaged. */
        void onRound(int round, double thdPct, int averages, FftResult result);
        /** The loop ended.  {@code hasResult} is true when at least one
         *  non-empty correction was computed (enables Save). */
        void onFinished(StopReason reason, double bestThdPct, boolean hasResult);
    }

    /** LMS step μ - full residual per round (CLI default). */
    private static final double COMP_STEP         = 1.0;
    /** Ceiling on the THD0/THD averaging-count growth, so a deep convergence
     *  can't make a single round run essentially forever (64x base = a 64x THD
     *  improvement; the user can still Stop). */
    private static final double MAX_AVG_GROW = 64.0;
    /** Averaging-margin factor in the round-sizing formula (see {@link
     *  #sizeAverages}): the target is {@code baseAverages × THD0/THD ×
     *  EXTEND_DROP_FACTOR}, so each round averages this much beyond the bare
     *  THD0/THD ratio to measure the shrinking residual with headroom. */
    private static final double EXTEND_DROP_FACTOR = 0.75;
    /** Tone counts as aligned when the measured fundamental STOPS moving -
     *  consecutive frames agree to within this fraction of an FFT bin.  The
     *  test is frame-to-frame stability, NOT a match against a computed target
     *  frequency (which need not equal the measured bin): an already-locked
     *  generator clears it at once instead of waiting out the whole timeout. */
    private static final double ALIGN_BIN_FRACTION = 0.1;
    /** Consecutive settled polls required before the loop starts. */
    private static final int    ALIGN_FRAMES      = 2;
    private static final long   ALIGN_TIMEOUT_MS  = 10_000;
    private static final long   POLL_MS           = 150;
    /** Settle after a live correction change before resetting statistics so
     *  the first window doesn't straddle the old signal. */
    private static final long   APPLY_SETTLE_MS   = 200;

    private final Display             display;
    private final FftController       fft;
    private final FftView             view;
    private final Listener            listener;
    /** Loaded {@code .frc} corrections - the engine de-embeds their phase off
     *  the raw measured phasors itself when computing the DAC correction (the
     *  store only holds the loaded files). */
    private final CorrectionStore correctionStore;

    private volatile boolean stopRequested;
    /** Ends only the CURRENT averaging round early (the loop continues). */
    private volatile boolean roundStopRequested;
    private Thread thread;
    /** This run's diagnostic record - opened when {@link #runLoop} starts,
     *  closed when it ends, and written only from the loop thread.  A disabled
     *  instance while {@link DebugSwitches#WRITE_DPD_STATS} is off.  It never
     *  throws: a file error disables the record and the loop runs on. */
    private PredistortionStatsWriter stats;

    /** Live phase, polled by the wizard timer. */
    @Getter private volatile Phase phase = Phase.IDLE;
    /** Current round number (0-based), polled live. */
    @Getter private volatile int   currentRound;
    /** Target FFT-average count for the active COLLECTING round - drives the
     *  remaining-averages readout. */
    private volatile int collectTargetAvg;

    /** The single-tone correction set that produced {@link #bestResult} (the
     *  lowest-distortion round), retained for saving.  {@code null} in dual-tone
     *  mode - see {@link #bestIntermod}. */
    @Getter private HarmonicCompensation bestApplied;
    /** The dual-tone counterpart of {@link #bestApplied} - the lowest-distortion
     *  round's intermod correction set.  {@code null} in single-tone mode. */
    @Getter private IntermodCompensation bestIntermod;
    /** True when this run is compensating a two-tone signal (drives the wizard's
     *  save / apply branch). */
    @Getter private boolean              dualTone;
    /** The lowest-distortion round's finalized result - provenance for the header. */
    @Getter private FftResult            bestResult;
    @Getter private double               bestThdPct = Double.MAX_VALUE;

    public PredistortionEngine(Display display, FftController fft, FftView view,
                               CorrectionStore correctionStore, Listener listener) {
        this.display         = display;
        this.fft             = fft;
        this.view            = view;
        this.correctionStore = correctionStore;
        this.listener        = listener;
    }

    /** Spawns the loop on a daemon thread.  Each round averages {@code baseAverages}
     *  FFT frames (more as the distortion drops), corrects the measurable
     *  harmonics and repeats; the loop runs until the user stops it. */
    public void start(int baseAverages, double targetThdPct) {
        stopRequested = false;
        thread = new Thread(() -> runLoop(baseAverages, targetThdPct), "predistortion-engine");
        thread.setDaemon(true);
        thread.start();
    }

    /** Requests a graceful stop after the current step. */
    public void stop() {
        stopRequested = true;
    }

    /** Hands a correction set to the generator over the bus - the one seam
     *  between this loop and the tone it corrects.  UI thread. */
    private void publishCorrections(GeneratorCorrectionsRequest request) {
        MessageBus.instance().publish(Events.GENERATOR_CORRECTIONS, request);
    }

    /** Tone 1 as the generator emits it, asked over the bus against the
     *  analyser's capture rate; {@code 0.0} when nothing answers. */
    private double emittedFundamentalHz() {
        Integer analysisRate = Preferences.instance().current().getInputSampleRate();
        double[] emitted = MessageBus.instance().request(Events.GENERATOR_EMITTED_HZ, analysisRate);
        return (emitted != null && emitted.length > 0) ? emitted[0] : 0.0;
    }

    /** Ends the CURRENT averaging round early - the loop takes the frames
     *  collected so far, applies the correction and continues with the next
     *  round (unlike {@link #stop()}, which ends the whole loop). */
    public void stopRound() {
        roundStopRequested = true;
    }

    // -------------------------------------------------------------------------
    // Loop
    // -------------------------------------------------------------------------

    private void runLoop(int baseAverages, double targetThdPct) {
        StopReason reason = StopReason.USER_STOP;
        // Switch off: a disabled record, which no-ops every call site - the
        // correction math is unaffected either way.
        stats = DebugSwitches.WRITE_DPD_STATS
                ? new PredistortionStatsWriter(AppPaths.instance().getLogsDir())
                : new PredistortionStatsWriter();
        // A fresh run starts from a CLEAN state machine - a re-started wizard
        // must behave exactly like the first start: no best round, no round
        // counter, no leftover round-stop flag from wherever the last run
        // was cut.
        currentRound         = 0;
        collectTargetAvg     = 0;
        roundStopRequested   = false;
        bestApplied          = null;
        bestIntermod         = null;
        bestResult           = null;
        bestThdPct           = Double.MAX_VALUE;
        try {
            // The accumulators index harmonics from H2, so the highest
            // harmonic number in the preferences (H9) means one slot fewer.
            int maxH = Math.max(1, Preferences.instance().getFftCalcMaxHarmonic() - 1);
            dualTone = Preferences.instance().getGenSignalForm().isDualTone();
            // One of the two accumulators is live; the other stays null.  Both
            // share the same loop skeleton - only the distortion metric, the
            // accumulate call and the hot-apply differ between single / dual tone.
            HarmonicCompensation harm     = dualTone ? null : new HarmonicCompensation(maxH);
            IntermodCompensation imd       = dualTone ? new IntermodCompensation(maxH) : null;
            HarmonicCompensation harmAppl  = dualTone ? null : harm.copy();   // empty = round 0
            IntermodCompensation imdAppl   = dualTone ? imd.copy() : null;
            // F1 as the generator EMITS it - the align target.  Asked over the
            // bus like every other reader of the emitted tones; the analyser's
            // rate is the grid the local answer snaps to.
            double target = emittedFundamentalHz();

            // Predistortion needs a deep, phase-coherent, generator-locked
            // measurement: switch the FFT to INFINITE coherent averaging (the
            // engine itself bounds the per-round depth via reset +
            // completedAnalyses), take the fundamental from the generator and
            // lock the tone with the FLL.  Window + mains suppression are only
            // RECOMMENDED - the user's choice is left intact, with a hint logged
            // when it isn't ideal.
            ui(() -> {
                Preferences p = Preferences.instance();
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
            });

            ui(() -> { if (!fft.isRecording()) fft.startRecording(); });

            // Always calibrate from the RAW DAC: drop any table still on the
            // generator (a set an earlier run in this window hot-swapped) so
            // round 0 measures the true distortion and the accumulator builds
            // the full correction from scratch - otherwise the first apply
            // would replace the loaded correction with only this run's residual
            // delta.  A saved correction file is not on the tone by now: the
            // wizard plays the plain sibling of a compensated waveform for the
            // run.  On a plain tone that carries nothing the drop changes
            // nothing and announces nothing.
            //
            // Every correction sent to the generator below is guarded by the
            // stop flag ON THE UI THREAD: the wizard's close requests the stop
            // and then reverts the generator, both on that thread, and a set
            // already queued behind the close would otherwise land after the
            // revert and leave this run's corrections playing under the
            // restored form.  Checked where it runs, the set is ordered against
            // the revert.  The generator is never held here: the sets travel
            // over the bus.
            ui(() -> {
                if (!stopRequested) {
                    publishCorrections(new GeneratorCorrectionsRequest(
                            GeneratorCorrectionsRequest.Kind.CLEAR, null, null, null, null, null));
                }
            });
            sleep(APPLY_SETTLE_MS);

            phase = Phase.ALIGNING;
            on(listener::onAligning);
            if (!waitForAlign()) {
                if (stopRequested) { reason = StopReason.USER_STOP; return; }
                log.warn("Predistortion: tone did not stabilise within {} ms - proceeding", ALIGN_TIMEOUT_MS);
            }
            // Round 0 begins from zero: the statistics are emptied first, so
            // the fresh count carries no frame of the uncorrected tone.
            ui(fft::resetStatistics);

            int round = 0;
            double baselineDist = Double.NaN;   // round 0's distortion - the fixed reference
            double prevDist     = Double.NaN;   // last completed round's distortion
            while (!stopRequested) {
                currentRound = round;
                // The round is sized by the residual-ratio formula, seeded
                // from the last round's residual and re-evaluated live while
                // the round collects.  See collectUntilAverages.
                phase = Phase.COLLECTING;
                int maxAverages = (int) Math.round(baseAverages * MAX_AVG_GROW);
                FftResult r = collectUntilAverages(round, baseAverages, baselineDist, prevDist,
                        maxAverages, harm);
                if (r == null) {
                    reason = stopRequested ? StopReason.USER_STOP : StopReason.ERROR;
                    break;
                }
                stats.round(round, r);
                int roundAvgDone = fft.completedAnalyses();
                double distPct = dualTone ? imdPct(r) : r.thdPct;
                if (!Double.isFinite(baselineDist) && distPct > 0.0) baselineDist = distPct;
                prevDist = distPct;
                if (distPct < bestThdPct) {
                    bestThdPct = distPct;
                    bestResult = r;
                    if (dualTone) bestIntermod = imdAppl.copy();
                    else          bestApplied  = harmAppl.copy();
                }
                final int       roundIdx = round;
                final FftResult roundRes = r;
                final double    roundDist = distPct;
                final int       roundAvg = roundAvgDone;
                on(() -> listener.onRound(roundIdx, roundDist, roundAvg, roundRes));

                // No auto-stop yet - the loop runs until the user clicks Stop
                // (an optional Target THD still ends it early when set).
                if (targetThdPct > 0 && distPct <= targetThdPct) { reason = StopReason.TARGET_REACHED; break; }
                // Stopped during the round above: it has been scored and, if it
                // is the best so far, its correction is the one kept.  Nothing
                // further is applied - the generator stays on what produced the
                // measurement the operator stopped at.
                if (stopRequested) { reason = StopReason.USER_STOP; break; }

                // Accumulate this round's residual and hot-apply for the next.
                phase = Phase.APPLYING;
                final boolean chanLeft = r.channelLeft;
                if (dualTone) {
                    double f1Hz = r.fundamentalHzRefined;
                    double f2Hz = r.fundamental2HzRefined;
                    // The products the round acted on, as measured - the
                    // harmonic-only ROUND record above cannot carry them.
                    stats.productRound(round, r, imd.rawPhasors(r, f1Hz, f2Hz),
                            imd.secondTonePhasor(r));
                    imd.accumulate(r, f1Hz, f2Hz, COMP_STEP,
                            calResponseAt(f1Hz, chanLeft)[1],
                            calResponseAt(f2Hz, chanLeft)[1]);
                    imdAppl = imd.copy();
                    IntermodCompensation.GeneratorCorrections gc = imd.toGeneratorCorrections(
                            f -> calResponseAt(f, chanLeft),
                            AudioBackend.instance().calibrationOrDefault(true).fsRms(Channel.L),
                            dualToneFundamentalVrms());
                    stats.productCorrection(round, gc, f1Hz, f2Hz);
                    ui(() -> {
                        if (!stopRequested) {
                            publishCorrections(new GeneratorCorrectionsRequest(
                                    GeneratorCorrectionsRequest.Kind.INTERMOD, gc.ampRatios(), null,
                                    gc.coefA(), gc.coefB(), gc.phiInits()));
                        }
                    });
                } else {
                    harm.accumulate(r, COMP_STEP, calResponseAt(r.fundamentalHzRefined, chanLeft)[1]);
                    harmAppl = harm.copy();
                    logCorrections(round, harm, maxH);
                    AudioBackend audio = AudioBackend.instance();
                    GeneratorCorrections gc = harm.toGeneratorCorrections(
                            target, f -> calResponseAt(f, chanLeft),
                            audio.calibrationOrDefault(true).fsRms(Channel.L),
                            generatorAmplitudeVrms());
                    stats.correction(round, gc);
                    ui(() -> {
                        if (!stopRequested) {
                            publishCorrections(new GeneratorCorrectionsRequest(
                                    GeneratorCorrectionsRequest.Kind.HARMONIC, gc.ampRatios(),
                                    gc.harmonicNumbers(), null, null, gc.phiInits()));
                        }
                    });
                }
                phase = Phase.SETTLING;
                sleep(APPLY_SETTLE_MS);
                if (stopRequested) { reason = StopReason.USER_STOP; break; }
                // The next round begins from zero on the corrected tone: the
                // statistics are emptied first, so the fresh count carries no
                // frame of the tone the previous round measured.
                ui(fft::resetStatisticsAfterSignalChange);
                round++;
            }
        } catch (Exception ex) {
            log.error("Predistortion engine failed", ex);
            reason = StopReason.ERROR;
        } finally {
            stats.close();
            phase = Phase.FINISHED;
            final StopReason fr = reason;
            final boolean hasResult = dualTone
                    ? (bestIntermod != null && bestIntermod.hasCorrections())
                    : (bestApplied  != null && bestApplied.hasCorrections());
            on(() -> listener.onFinished(fr, bestThdPct, hasResult));
        }
    }

    /** FFT averages still to collect in the current round - drives the wizard's
     *  countdown.  0 outside the COLLECTING phase. */
    public int getCollectRemainingAverages() {
        if (phase != Phase.COLLECTING) return 0;
        return Math.max(0, collectTargetAvg - fft.completedAnalyses());
    }

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

    /** The F1 tone's DAC output level (Vrms) - the dual-tone correction's ratio
     *  reference (the products are injected relative to F1).  Derived from the
     *  total generator amplitude and the dual-tone split: with weights
     *  {@code w₁ = split%, w₂ = 100−split%}, tone-1 carries
     *  {@code total · w₁/√(w₁²+w₂²)} of the combined RMS. */
    public double dualToneFundamentalVrms() {
        Preferences p = Preferences.instance();
        double w1 = p.getGenDualToneSplitPct() / 100.0;
        double w2 = 1.0 - w1;
        double norm = Math.hypot(w1, w2);
        double total = generatorAmplitudeVrms();
        return norm > 0.0 ? total * w1 / norm : total;
    }

    /** The left lane's entered generator amplitude as V RMS against the output
     *  device's full scale in force - the lane the predistortion measures. */
    private double generatorAmplitudeVrms() {
        return AudioBackend.instance().calibrationOrDefault(false)
                .amplitudeVrms(Preferences.instance().getGenAmplitudeLeft(), Channel.L);
    }

    /** Dual-tone distortion figure being minimised: the combined intermod-product
     *  power as a percentage of the two fundamentals (the IMD analogue of THD).
     *  Falls back to the single-tone THD when the result carries no products. */
    private double imdPct(FftResult r) {
        ImdResult imd = r.imd;
        return (imd != null && Double.isFinite(imd.imdPwrPct)) ? imd.imdPwrPct : r.thdPct;
    }

    /** Two lines per round: the change each harmonic's correction took this
     *  round, and the accumulated correction it stands at afterwards - both as
     *  magnitude in dBFS (the ADC level the correction cancels, before the
     *  chain's de-embedding) and angle in degrees in the accumulator's frame;
     *  "-" for a harmonic without a correction. */
    private void logCorrections(int round, HarmonicCompensation harm, int maxH) {
        if (!log.isInfoEnabled()) return;
        StringBuilder delta = new StringBuilder();
        StringBuilder total = new StringBuilder();
        for (int h = 0; h < maxH; h++) {
            appendPhasor(delta, h, harm.lastDelta(h));
            appendPhasor(total, h, harm.correction(h));
        }
        log.info("Predistortion round {} correction delta:{}", round, delta);
        log.info("Predistortion round {} accumulated correction:{}", round, total);
    }

    private void appendPhasor(StringBuilder sb, int h, double[] p) {
        double mag = Math.hypot(p[0], p[1]);
        sb.append(" H").append(h + 2).append(' ');
        if (mag > 0.0) {
            sb.append(String.format(Locale.US, "%.2f dBFS %+.1f deg;",
                    20.0 * Math.log10(mag), Math.toDegrees(Math.atan2(p[1], p[0]))));
        } else {
            sb.append("-;");
        }
    }

    /** Blocks until the measured fundamental is stable - consecutive frames
     *  agree within {@link #ALIGN_BIN_FRACTION} of a bin for {@link #ALIGN_FRAMES}
     *  polls - the timeout elapses, or a stop is requested. */
    private boolean waitForAlign() {
        long deadline = System.currentTimeMillis() + ALIGN_TIMEOUT_MS;
        int settled = 0;
        double prevHz = Double.NaN;
        while (System.currentTimeMillis() < deadline && !stopRequested) {
            FftResult r = readResult();
            if (r != null && Double.isFinite(r.fundamentalHzRefined) && r.freqResolution > 0) {
                double hz = r.fundamentalHzRefined;
                if (Double.isFinite(prevHz)
                        && Math.abs(hz - prevHz) <= r.freqResolution * ALIGN_BIN_FRACTION) {
                    if (++settled >= ALIGN_FRAMES) return true;
                } else {
                    settled = 0;
                }
                prevHz = hz;
            }
            sleep(POLL_MS);
        }
        return false;
    }

    /** Averages until the FFT has accumulated the round's target frames (polling
     *  for a stop), then returns a private copy of the finalized displayed
     *  result, or {@code null} on stop.  The target is seeded by
     *  {@link #sizeAverages} from {@code prevDistPct} (the last round's
     *  residual) and re-evaluated against the LIVE residual at every poll,
     *  only rising, single tone and dual tone alike.
     *  {@link FftController#completedAnalyses()} was re-zeroed by the reset
     *  that precedes every round.  {@code statsRound} labels the collected
     *  frames in the diagnostic record; {@code harm} (single tone, else
     *  null) only supplies the clearances of the round's closing log line. */
    private FftResult collectUntilAverages(int statsRound, int baseAverages, double baselineDistPct,
                                           double prevDistPct, int maxAverages,
                                           HarmonicCompensation harm) {
        collectTargetAvg   = sizeAverages(baseAverages, baselineDistPct, prevDistPct, maxAverages);
        roundStopRequested = false;
        if (log.isInfoEnabled()) {
            log.info("Predistortion round {}: target {} averages (last round's residual {} %, round 0's {} %)",
                    statsRound, collectTargetAvg, prevDistPct, baselineDistPct);
        }
        while (fft.completedAnalyses() < collectTargetAvg) {
            if (stopRequested) {
                // Stop ENDS this round, it does not throw it away: the frames
                // averaged so far are a measurement like any other, taken with
                // the correction that is in force, and it is that pair the
                // operator is looking at when deciding to stop.  Discarding it
                // would keep as "best" a correction the run has already beaten.
                return fft.completedAnalyses() >= 1 ? readResult() : null;
            }
            // Manual "Stop round": take the frames averaged so far (at least
            // one) and proceed to apply, instead of waiting out the target.
            if (roundStopRequested && fft.completedAnalyses() >= 1) break;
            FftResult live = readResult();
            stats.frame(statsRound, live);
            double liveDistPct = distortionPct(live);
            int target = sizeAverages(baseAverages, baselineDistPct, liveDistPct, maxAverages);
            if (target > collectTargetAvg) {
                if (log.isInfoEnabled()) {
                    log.info("Predistortion round {}: target {} -> {} at {} averages done (live residual {} %, round 0's {} %)",
                            statsRound, collectTargetAvg, target, fft.completedAnalyses(),
                            liveDistPct, baselineDistPct);
                }
                collectTargetAvg = target;
            }
            sleep(POLL_MS);
        }
        FftResult done = readResult();
        if (harm != null && log.isInfoEnabled()) {
            log.info("Predistortion round {}: collected {} averages; clearances {}",
                    statsRound, fft.completedAnalyses(), formatClearances(done, harm));
        }
        return done;
    }

    /** Each harmonic's clearance over its local floor in dB, H2 first, one
     *  decimal, "-" for a harmonic without a reading - the per-harmonic
     *  evidence behind a round's size. */
    private String formatClearances(FftResult r, HarmonicCompensation harm) {
        if (r == null) return "none";
        double[] clear = harm.measurePhasors(r, 0.0).clearanceDb();
        StringBuilder sb = new StringBuilder();
        for (int h = 0; h < clear.length; h++) {
            if (h > 0) sb.append(' ');
            sb.append('H').append(h + 2).append('=');
            sb.append(Double.isFinite(clear[h]) ? String.format(Locale.US, "%.1f", clear[h]) : "-");
        }
        return sb.toString();
    }

    /** The round-sizing formula, shared by the seed and the live
     *  update: {@code baseAverages × (THD0 / THD) × EXTEND_DROP_FACTOR}, clamped to
     *  {@code [baseAverages, maxAverages]}.  A residual N× below round 0 averages
     *  N× longer (linear), with {@link #EXTEND_DROP_FACTOR} of extra margin.
     *  Returns {@code baseAverages} until a reference and a measurement exist
     *  (round 0, or before the first live frame). */
    private int sizeAverages(int baseAverages, double baselineDistPct, double distPct, int maxAverages) {
        if (!(baselineDistPct > 0) || !(distPct > 0)) return baseAverages;
        int target = (int) Math.round(baseAverages * (baselineDistPct / distPct) * EXTEND_DROP_FACTOR);
        return Math.max(baseAverages, Math.min(target, maxAverages));
    }

    /** Cumulative distortion of an FFT result - THD for a single tone, the
     *  combined intermod % for a dual tone - matching the round metric.
     *  {@code NaN} when no result is available yet. */
    private double distortionPct(FftResult r) {
        if (r == null) return Double.NaN;
        return dualTone ? imdPct(r) : r.thdPct;
    }

    /** Reads {@link FftView#getLastResult()} on the UI thread and returns a
     *  deep copy (the view reassigns, never mutates, its reference). */
    private FftResult readResult() {
        FftResult[] box = new FftResult[1];
        ui(() -> {
            FftResult r = view.getLastResult();
            box[0] = (r != null) ? r.deepCopy() : null;
        });
        return box[0];
    }

    // -------------------------------------------------------------------------
    // Thread helpers
    // -------------------------------------------------------------------------

    /** Runs {@code action} on the UI thread, blocking until done. */
    private void ui(Runnable action) {
        if (!display.isDisposed()) display.syncExec(action);
    }

    /** Delivers a listener callback on the UI thread without blocking. */
    private void on(Runnable callback) {
        if (!display.isDisposed()) display.asyncExec(callback);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stopRequested = true;
        }
    }
}
