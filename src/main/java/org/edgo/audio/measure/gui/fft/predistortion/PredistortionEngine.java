/*
 * Phonalyser — precision audio measurement workbench.
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

import org.eclipse.swt.widgets.Display;
import org.edgo.audio.measure.enums.AlignGenerator;
import org.edgo.audio.measure.enums.MainsSuppression;
import org.edgo.audio.measure.enums.WindowType;
import org.edgo.audio.measure.dsp.HarmonicCompensation;
import org.edgo.audio.measure.dsp.HarmonicCompensation.GeneratorCorrections;
import org.edgo.audio.measure.dsp.IntermodCompensation;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.gui.fft.FftController;
import org.edgo.audio.measure.gui.fft.FftView;
import org.edgo.audio.measure.gui.fft.ImdResult;
import org.edgo.audio.measure.gui.generator.GeneratorController;
import org.edgo.audio.measure.preferences.Preferences;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * Closed-loop DAC harmonic-predistortion engine for the GUI wizard — the
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
 *   <li>{@linkplain GeneratorController#applyCompensation hot-apply} the
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

    /** Why {@link #runLoop} ended — drives the wizard's end-of-run message. */
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
        /** One averaging round finished — {@code result} is a private copy,
         *  {@code averages} the number of frames it actually averaged. */
        void onRound(int round, double thdPct, int averages, FftResult result);
        /** The loop ended.  {@code hasResult} is true when at least one
         *  non-empty correction was computed (enables Save). */
        void onFinished(StopReason reason, double bestThdPct, boolean hasResult);
    }

    /** LMS step μ — full residual per round (CLI default). */
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
    /** Tone counts as aligned when the measured fundamental STOPS moving —
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
    private final GeneratorController gen;
    private final FftController       fft;
    private final FftView             view;
    private final Listener            listener;

    private volatile boolean stopRequested;
    /** Ends only the CURRENT averaging round early (the loop continues). */
    private volatile boolean roundStopRequested;
    private Thread thread;

    /** Live phase, polled by the wizard timer. */
    @Getter private volatile Phase phase = Phase.IDLE;
    /** Current round number (0-based), polled live. */
    @Getter private volatile int   currentRound;
    /** Target FFT-average count for the active COLLECTING round — drives the
     *  remaining-averages readout. */
    private volatile int collectTargetAvg;

    /** The single-tone correction set that produced {@link #bestResult} (the
     *  lowest-distortion round), retained for saving.  {@code null} in dual-tone
     *  mode — see {@link #bestIntermod}. */
    @Getter private HarmonicCompensation bestApplied;
    /** The dual-tone counterpart of {@link #bestApplied} — the lowest-distortion
     *  round's intermod correction set.  {@code null} in single-tone mode. */
    @Getter private IntermodCompensation bestIntermod;
    /** True when this run is compensating a two-tone signal (drives the wizard's
     *  save / apply branch). */
    @Getter private boolean              dualTone;
    /** The lowest-distortion round's finalized result — provenance for the header. */
    @Getter private FftResult            bestResult;
    @Getter private double               bestThdPct = Double.MAX_VALUE;

    public PredistortionEngine(Display display, GeneratorController gen,
                               FftController fft, FftView view, Listener listener) {
        this.display  = display;
        this.gen      = gen;
        this.fft      = fft;
        this.view     = view;
        this.listener = listener;
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

    /** Ends the CURRENT averaging round early — the loop takes the frames
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
        try {
            int maxH = Math.max(1, Preferences.instance().getFftCalcMaxHarmonic());
            dualTone = Preferences.instance().getGenSignalForm().isDualTone();
            // One of the two accumulators is live; the other stays null.  Both
            // share the same loop skeleton — only the distortion metric, the
            // accumulate call and the hot-apply differ between single / dual tone.
            HarmonicCompensation harm     = dualTone ? null : new HarmonicCompensation(maxH);
            IntermodCompensation imd       = dualTone ? new IntermodCompensation(maxH) : null;
            HarmonicCompensation harmAppl  = dualTone ? null : harm.copy();   // empty = round 0
            IntermodCompensation imdAppl   = dualTone ? imd.copy() : null;
            double target = gen.effectiveFrequency();          // F1 emit freq — the align target

            // Predistortion needs a deep, phase-coherent, generator-locked
            // measurement: switch the FFT to INFINITE coherent averaging (the
            // engine itself bounds the per-round depth via reset +
            // completedAnalyses), take the fundamental from the generator and
            // lock the tone with the FLL.  Window + mains suppression are only
            // RECOMMENDED — the user's choice is left intact, with a hint logged
            // when it isn't ideal.
            ui(() -> {
                Preferences p = Preferences.instance();
                p.setFftCoherentAveraging(true);
                p.setFftAverages(Double.POSITIVE_INFINITY);
                p.setFftFundFromGenerator(true);
                p.setFftAlignGenerator(AlignGenerator.FLL);
                if (log.isInfoEnabled()) {
                    if (p.getFftWindow() != WindowType.HFT248D) {
                        log.info("Predistortion: FFT window is {} — HFT248D is recommended for the deepest harmonic separation.",
                                p.getFftWindow());
                    }
                    if (p.getFftMainsSuppression() != MainsSuppression.NONE) {
                        log.info("Predistortion: mains suppression is {} — recommend turning it OFF (it can notch a measured harmonic).",
                                p.getFftMainsSuppression());
                    }
                }
            });

            ui(() -> { if (!fft.isRecording()) fft.startRecording(); });

            // Always calibrate from the RAW DAC: drop any compensation already
            // on the generator (a previously-loaded .dpd) so round 0 measures
            // the true distortion and the accumulator builds the full
            // correction from scratch — otherwise the first apply would replace
            // the loaded correction with only this run's residual delta.
            ui(gen::clearCompensation);
            sleep(APPLY_SETTLE_MS);

            phase = Phase.ALIGNING;
            on(listener::onAligning);
            if (!waitForAlign()) {
                if (stopRequested) { reason = StopReason.USER_STOP; return; }
                log.warn("Predistortion: tone did not stabilise within {} ms — proceeding", ALIGN_TIMEOUT_MS);
            }
            ui(fft::resetStatistics);                          // fresh baseline for round 0

            int round = 0;
            double baselineDist = Double.NaN;   // round 0's distortion — the fixed reference
            double prevDist     = Double.NaN;   // last completed round's distortion
            while (!stopRequested) {
                currentRound = round;
                // The round is seeded from how far the LAST residual fell below
                // round 0, then keeps growing live as THIS round's residual drops
                // further — one formula, see collectUntilAverages / sizeAverages.
                phase = Phase.COLLECTING;
                int maxAverages = (int) Math.round(baseAverages * MAX_AVG_GROW);
                FftResult r = collectUntilAverages(baseAverages, baselineDist, prevDist, maxAverages);
                if (r == null) {
                    reason = stopRequested ? StopReason.USER_STOP : StopReason.ERROR;
                    break;
                }
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

                // No auto-stop yet — the loop runs until the user clicks Stop
                // (an optional Target THD still ends it early when set).
                if (targetThdPct > 0 && distPct <= targetThdPct) { reason = StopReason.TARGET_REACHED; break; }

                // Accumulate this round's residual and hot-apply for the next.
                phase = Phase.APPLYING;
                if (dualTone) {
                    imd.accumulate(r, r.fundamentalHzRefined, r.fundamental2HzRefined, COMP_STEP);
                    imdAppl = imd.copy();
                    IntermodCompensation.GeneratorCorrections gc = imd.toGeneratorCorrections();
                    ui(() -> gen.applyDualToneCompensation(gc.ampRatios(), gc.coefA(), gc.coefB(), gc.phiInits()));
                } else {
                    harm.accumulate(r, COMP_STEP);
                    harmAppl = harm.copy();
                    GeneratorCorrections gc = harm.toGeneratorCorrections(target);
                    ui(() -> gen.applyCompensation(gc.ampRatios(), gc.harmonicNumbers(), gc.phiInits()));
                }
                phase = Phase.SETTLING;
                sleep(APPLY_SETTLE_MS);
                if (stopRequested) { reason = StopReason.USER_STOP; break; }
                ui(fft::resetStatisticsAfterSignalChange);
                round++;
            }
        } catch (Exception ex) {
            log.error("Predistortion engine failed", ex);
            reason = StopReason.ERROR;
        } finally {
            phase = Phase.FINISHED;
            final StopReason fr = reason;
            final boolean hasResult = dualTone
                    ? (bestIntermod != null && bestIntermod.hasCorrections())
                    : (bestApplied  != null && bestApplied.hasCorrections());
            on(() -> listener.onFinished(fr, bestThdPct, hasResult));
        }
    }

    /** FFT averages still to collect in the current round — drives the wizard's
     *  countdown.  0 outside the COLLECTING phase. */
    public int getCollectRemainingAverages() {
        if (phase != Phase.COLLECTING) return 0;
        return Math.max(0, collectTargetAvg - fft.completedAnalyses());
    }

    /** Dual-tone distortion figure being minimised: the combined intermod-product
     *  power as a percentage of the two fundamentals (the IMD analogue of THD).
     *  Falls back to the single-tone THD if the IMD analysis is unavailable. */
    private double imdPct(FftResult r) {
        ImdResult imd = fft.analyzeImd(r);
        return (imd != null && Double.isFinite(imd.imdPwrPct)) ? imd.imdPwrPct : r.thdPct;
    }

    /** Blocks until the measured fundamental is stable — consecutive frames
     *  agree within {@link #ALIGN_BIN_FRACTION} of a bin for {@link #ALIGN_FRAMES}
     *  polls — the timeout elapses, or a stop is requested. */
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
     *  result, or {@code null} on stop.  The target is {@link #sizeAverages}
     *  seeded from {@code prevDistPct} (the last round's residual) and then
     *  re-evaluated against the LIVE residual on every collected average, so a
     *  round that keeps improving keeps averaging.  {@link FftController#completedAnalyses()}
     *  was re-zeroed by the reset that precedes every round. */
    private FftResult collectUntilAverages(int baseAverages, double baselineDistPct,
                                           double prevDistPct, int maxAverages) {
        collectTargetAvg   = sizeAverages(baseAverages, baselineDistPct, prevDistPct, maxAverages);
        roundStopRequested = false;
        while (fft.completedAnalyses() < collectTargetAvg) {
            if (stopRequested) return null;
            // Manual "Stop round": take the frames averaged so far (at least
            // one) and proceed to apply, instead of waiting out the target.
            if (roundStopRequested && fft.completedAnalyses() >= 1) break;
            int target = sizeAverages(baseAverages, baselineDistPct, liveDistPct(), maxAverages);
            if (target > collectTargetAvg) collectTargetAvg = target;
            sleep(POLL_MS);
        }
        return readResult();
    }

    /** The round-sizing formula, shared by the seed and the live update:
     *  {@code baseAverages × (THD0 / THD) × EXTEND_DROP_FACTOR}, clamped to
     *  {@code [baseAverages, maxAverages]}.  A residual N× below round 0 averages
     *  N× longer (linear), with {@link #EXTEND_DROP_FACTOR} of extra margin.
     *  Returns {@code baseAverages} until a reference and a measurement exist
     *  (round 0, or before the first live frame). */
    private int sizeAverages(int baseAverages, double baselineDistPct, double distPct, int maxAverages) {
        if (!(baselineDistPct > 0) || !(distPct > 0)) return baseAverages;
        int target = (int) Math.round(baseAverages * (baselineDistPct / distPct) * EXTEND_DROP_FACTOR);
        return Math.max(baseAverages, Math.min(target, maxAverages));
    }

    /** Current cumulative distortion off the live FFT result — THD for a single
     *  tone, the combined intermod % for a dual tone — matching the round
     *  metric.  {@code NaN} when no result is available yet. */
    private double liveDistPct() {
        FftResult r = readResult();
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
