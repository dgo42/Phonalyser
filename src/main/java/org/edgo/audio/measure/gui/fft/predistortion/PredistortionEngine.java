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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.swt.widgets.Display;
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
        /** One averaging round finished — {@code result} is a private copy. */
        void onRound(int round, double thdPct, FftResult result);
        /** The loop ended.  {@code hasResult} is true when at least one
         *  non-empty correction was computed (enables Save). */
        void onFinished(StopReason reason, double bestThdPct, boolean hasResult);
    }

    /** Harmonics within this many dB of the noise floor are skipped (they
     *  would random-walk the accumulator) — matches the CLI default. */
    private static final double SNR_MARGIN_DB     = 10.0;
    /** LMS step μ — full residual per round (CLI default). */
    private static final double COMP_STEP         = 1.0;
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
    /** Stall gate: stop when the best THD improved by less than this
     *  (relative) across {@link #STALL_WINDOW} rounds. */
    private static final double STALL_REL_FRACTION = 0.05;
    private static final int    STALL_WINDOW       = 3;
    /** Factor the averaging window grows by when a round more than halves the
     *  distortion (a 2× drop earns a 2× longer next round). */
    private static final double ROUND_GROW_FACTOR     = 2.0;
    /** Ceiling on the adaptive growth — at most this many times the base
     *  Duration, so a long convergence can't make a single round unbounded. */
    private static final double MAX_ROUND_GROW_FACTOR = 8.0;

    private final Display             display;
    private final GeneratorController gen;
    private final FftController       fft;
    private final FftView             view;
    private final Listener            listener;

    private volatile boolean stopRequested;
    private Thread thread;

    /** Live phase, polled by the wizard timer. */
    @Getter private volatile Phase phase = Phase.IDLE;
    /** Current round number (0-based), polled live. */
    @Getter private volatile int   currentRound;
    /** Wall-clock bounds of the active COLLECTING window, for the progress bar. */
    private volatile long collectStartMs;
    private volatile long collectEndMs;

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

    /** Spawns the loop on a daemon thread. */
    public void start(double durationSec, double targetThdPct) {
        stopRequested = false;
        thread = new Thread(() -> runLoop(durationSec, targetThdPct), "predistortion-engine");
        thread.setDaemon(true);
        thread.start();
    }

    /** Requests a graceful stop after the current step. */
    public void stop() {
        stopRequested = true;
    }

    // -------------------------------------------------------------------------
    // Loop
    // -------------------------------------------------------------------------

    private void runLoop(double durationSec, double targetThdPct) {
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
            List<Double> distHistory = new ArrayList<>();

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
            // Adaptive averaging window: starts at the user's Duration and
            // doubles whenever a round more than halves the distortion (the
            // smaller residual is nearer the floor and needs deeper averaging
            // to resolve) — capped at MAX_ROUND_GROW_FACTOR× the base.
            double roundDuration = durationSec;
            double prevDistPct   = Double.NaN;
            while (!stopRequested) {
                currentRound = round;
                phase = Phase.COLLECTING;
                FftResult r = collectFor(roundDuration);
                if (r == null) {
                    reason = stopRequested ? StopReason.USER_STOP : StopReason.ERROR;
                    break;
                }
                double distPct = dualTone ? imdPct(r) : r.thdPct;
                distHistory.add(distPct);
                if (distPct < bestThdPct) {
                    bestThdPct = distPct;
                    bestResult = r;
                    if (dualTone) bestIntermod = imdAppl.copy();
                    else          bestApplied  = harmAppl.copy();
                }
                if (Double.isFinite(prevDistPct) && distPct > 0.0 && distPct <= prevDistPct / 2.0) {
                    roundDuration = Math.min(roundDuration * ROUND_GROW_FACTOR,
                                             durationSec * MAX_ROUND_GROW_FACTOR);
                }
                prevDistPct = distPct;
                final int       roundIdx = round;
                final FftResult roundRes = r;
                final double    roundDist = distPct;
                on(() -> listener.onRound(roundIdx, roundDist, roundRes));

                if (targetThdPct > 0 && distPct <= targetThdPct) { reason = StopReason.TARGET_REACHED; break; }
                if (stalled(distHistory))                        { reason = StopReason.STALLED;        break; }

                // Accumulate this round's residual and hot-apply for the next.
                phase = Phase.APPLYING;
                if (dualTone) {
                    imd.accumulate(r, r.fundamentalHzRefined, r.fundamental2HzRefined, SNR_MARGIN_DB, COMP_STEP);
                    imdAppl = imd.copy();
                    IntermodCompensation.GeneratorCorrections gc = imd.toGeneratorCorrections();
                    ui(() -> gen.applyDualToneCompensation(gc.ampRatios(), gc.coefA(), gc.coefB(), gc.phiInits()));
                } else {
                    harm.accumulate(r, SNR_MARGIN_DB, COMP_STEP);
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

    /** Seconds remaining in the current averaging window — drives the wizard's
     *  countdown.  0 outside the COLLECTING phase. */
    public double getCollectRemainingSec() {
        if (phase != Phase.COLLECTING) return 0.0;
        return Math.max(0.0, (collectEndMs - System.currentTimeMillis()) / 1000.0);
    }

    /** Fraction [0, 1] of the current averaging window elapsed — drives the
     *  wizard's per-round progress bar.  Returns 1 once the window is done
     *  (applying / settling / finished) and 0 before the first window. */
    public double getCollectProgress() {
        if (phase != Phase.COLLECTING) {
            return phase.ordinal() > Phase.COLLECTING.ordinal() ? 1.0 : 0.0;
        }
        long s = collectStartMs, e = collectEndMs, now = System.currentTimeMillis();
        if (e <= s) return 0.0;
        return Math.max(0.0, Math.min(1.0, (now - s) / (double) (e - s)));
    }

    /** Dual-tone distortion figure being minimised: the combined intermod-product
     *  power as a percentage of the two fundamentals (the IMD analogue of THD).
     *  Falls back to the single-tone THD if the IMD analysis is unavailable. */
    private double imdPct(FftResult r) {
        ImdResult imd = fft.analyzeImd(r);
        return (imd != null && Double.isFinite(imd.imdPwrPct)) ? imd.imdPwrPct : r.thdPct;
    }

    /** Blocks until the tone is within {@link #ALIGN_PPM} of {@code target}
     *  for {@link #ALIGN_FRAMES} consecutive polls, the timeout elapses, or a
     *  stop is requested.  Returns whether alignment was confirmed. */
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

    /** Averages for {@code durationSec} (polling for a stop), then returns a
     *  private copy of the finalized displayed result, or {@code null} on
     *  stop / when no result is available. */
    private FftResult collectFor(double durationSec) {
        collectStartMs = System.currentTimeMillis();
        long end = collectStartMs + Math.round(durationSec * 1000.0);
        collectEndMs = end;
        while (System.currentTimeMillis() < end) {
            if (stopRequested) return null;
            sleep(POLL_MS);
        }
        return readResult();
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

    /** True when the best THD improved by less than {@link #STALL_REL_FRACTION}
     *  (relative) across the last {@link #STALL_WINDOW} rounds. */
    private boolean stalled(List<Double> thd) {
        int n = thd.size();
        if (n < STALL_WINDOW + 1) return false;
        double bestNow    = Collections.min(thd);
        double bestBefore = Collections.min(thd.subList(0, n - STALL_WINDOW));
        return (bestBefore - bestNow) <= STALL_REL_FRACTION * bestBefore;
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
