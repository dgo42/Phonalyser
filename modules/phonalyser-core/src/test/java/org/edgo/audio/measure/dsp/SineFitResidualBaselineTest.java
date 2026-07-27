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

package org.edgo.audio.measure.dsp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the scope "Residual" baseline-wander bug.
 *
 * <p>The scope residual trace displays {@code captured − best-fit single tone}.
 * The fit window follows the display slice, whose boundaries walk every capture
 * (trigger sub-sample jitter, rolling ring buffer).  Over a non-integer-cycle
 * window {@code {1, sin, cos}} are not orthogonal, so each per-capture
 * least-squares fit re-splits the true DC between {@code c} and the nonzero
 * windowed mean of the fitted sinusoid.  The split — and therefore the visible
 * mean of {@code x − (a·sin + b·cos)} — wanders frame to frame, drifting the
 * residual baseline by tens of µV (bench: ±20 µV at ~1–5 Hz = the capture rate).
 *
 * <p>The fix removes the WHOLE model ({@code a·sin + b·cos + c}) — making the
 * residual exactly orthogonal to the constant over the fit window — and adds
 * back a stable, long-averaged DC.  This test reproduces the sliding-window +
 * trigger-jitter scenario and asserts that:
 * <ul>
 *   <li>the OLD pipeline ({@link SineFit#subtractSineInto}, DC left in) exhibits
 *       a clearly-visible visible-mean wander, and</li>
 *   <li>the FIXED pipeline ({@link SineFit#subtractFullInto} + stable DC) pins
 *       the visible mean to that stable DC with a spread orders of magnitude
 *       smaller — bounded well under the observed artefact.</li>
 * </ul>
 *
 * <p>The signal is a clean tone plus DC plus a small fixed harmonic (no random
 * noise).  The frame-to-frame driver is modelled deterministically and exactly
 * as it arises in the field: each capture's fit runs at a slightly different,
 * imperfect frequency (the phase-slope polish lands a hair off true — bench:
 * from the ADC/THD+N floor; here from a deterministic ±{@code SEED_ERR_HZ}
 * sweep), while the window boundary walks a full cycle across frames.  Both make
 * each per-capture fit re-split the true DC differently between {@code c} and the
 * windowed mean of {@code a·sin+b·cos} — the pure H1 DC-split artefact.  Bounds
 * are derived from the offline simulation ({@code tmp/residual_drift_sim.py})
 * with margin.
 */
class SineFitResidualBaselineTest {

    private static final int    SAMPLE_RATE = 192_000;
    private static final double FREQ_HZ     = 997.3;     // non-integer cycles / window
    private static final double AMPLITUDE   = 1.4;       // ≈ 1 Vrms peak (bench)
    private static final double PHASE_RAD   = 0.7;
    private static final double DC          = 3.0e-3;    // true, stable DC (normalized units)
    private static final double H3_AMP      = 5.0e-5;    // fixed 3rd-harmonic contaminant

    private static final int    FRAMES        = 200;
    private static final int    VISIBLE_CYCLES = 10;     // ~10 cycles on screen (bench t/div)
    private static final int    PAD           = 32;      // Lanczos padding around the visible slice
    private static final int    STRIDE        = 613;     // per-frame window walk (coprime-ish → sweeps all phases)
    private static final double SEED_ERR_HZ   = 5.0e-4;  // per-frame POST-POLISH fit-frequency residue (sim: sub-mHz)

    // Bounds (normalized units → the trace is displayed in these units directly).
    // Measured (this deterministic run): old FIT-window p2p ≈ 0.65 µV (= the c-hat
    // re-split), fix FIT-window pinned ≪ 0.01 µV; old VISIBLE p2p ≈ 1.34 µV, fix
    // VISIBLE ≈ 0.69 µV (irreducible tone remnant, shared).  Bench artefact ±20 µV.
    private static final double OLD_FIT_SPREAD_MIN = 0.3e-6;  // the DC re-split must be visible (measured ~0.65 µV)
    private static final double FIX_FIT_SPREAD_MAX = 0.05e-6; // fix pins the fit-window baseline (float32 rounding only)
    private static final double FIX_VIS_SPREAD_MAX = 3.0e-6;  // 3 µV ceiling, ~7× under the bench ±20 µV artefact
    private static final double FIX_MEAN_TOL       = 3.0e-6;  // fixed baseline pinned to the true, stable DC

    /** Ideal fundamental at absolute sample index {@code k}. */
    private double fundamental(double k) {
        return AMPLITUDE * Math.sin(2.0 * Math.PI * FREQ_HZ * k / SAMPLE_RATE + PHASE_RAD);
    }

    /** Fixed 3rd-harmonic contaminant at absolute sample index {@code k}. */
    private double harmonic3(double k) {
        return H3_AMP * Math.sin(2.0 * Math.PI * 3.0 * FREQ_HZ * k / SAMPLE_RATE);
    }

    @Test
    void slidingWindowResidual_fixPinsBaseline_oldPipelineWanders() {
        int visible = (int) Math.round(VISIBLE_CYCLES * SAMPLE_RATE / FREQ_HZ);

        // A large source buffer holding the (absolute-time) tone; each "frame"
        // reads a display slice at a different, jittered absolute start so the
        // fit-window boundary walks exactly like the live ring buffer.
        int lead  = 4 * visible;                 // room to slide the window left
        int total = lead + FRAMES + 4 * visible; // + room to slide right
        float[] buf = new float[total];
        for (int i = 0; i < total; i++) {
            buf[i] = (float) (fundamental(i) + DC + harmonic3(i));
        }

        // Per-frame residual means: over the VISIBLE window (what the eye reads
        // as the baseline) and over the FIT window (where LS orthogonality lives).
        double[] oldVisMean = new double[FRAMES];
        double[] fixVisMean = new double[FRAMES];
        double[] oldFitMean = new double[FRAMES];
        double[] fixFitMean = new double[FRAMES];
        float[]  scratch    = new float[visible + 2 * PAD];

        for (int frame = 0; frame < FRAMES; frame++) {
            // Window walk: a coprime-ish stride sweeps the boundary across all
            // phases over the run (the rolling ring buffer + trigger placement).
            int dispStart = lead + (frame * STRIDE) % (2 * visible);

            // Fit window = the padded display slice (grow-left does not fire at
            // ~10 visible cycles — the padded slice already exceeds the floor),
            // exactly as computeResidual does at bench t/div.
            int fitFrom = dispStart - PAD;
            int fitLen  = visible + 2 * PAD;

            // Per-frame fit frequency lands a hair off true — the POST-polish
            // residue (bench: ADC/THD+N floor; sim: sub-mHz).  computeResidual
            // polishes f before the final fit, so the final fit sees only this
            // small residue; a deterministic saw over ±SEED_ERR_HZ models it.
            double fitFreq = FREQ_HZ + SEED_ERR_HZ * (2.0 * ((frame * 0.6180339887) % 1.0) - 1.0);

            SineFit fit = SineFit.of(buf, fitFrom, fitLen, SAMPLE_RATE, fitFreq);

            // OLD pipeline: subtract sinusoid only (fit DC c left in the trace).
            // Over the fit window the residual mean IS c-hat (LS orthogonality),
            // and c-hat is the wandering DC re-split — the H1 artefact.
            fit.subtractSineInto(buf, fitFrom, fitLen, 0.0, scratch, 0);
            oldVisMean[frame] = visibleMean(scratch, PAD, visible);
            oldFitMean[frame] = visibleMean(scratch, 0, fitLen);

            // FIXED pipeline: subtract the whole model, add back the STABLE DC
            // (long-averaged Vmean — here the known true DC constant).  Over the
            // fit window this is pinned to dcStable by construction, independent
            // of f-hat, because the LS residual is orthogonal to the constant.
            fit.subtractFullInto(buf, fitFrom, fitLen, 0.0, DC, scratch, 0);
            fixVisMean[frame] = visibleMean(scratch, PAD, visible);
            fixFitMean[frame] = visibleMean(scratch, 0, fitLen);
        }

        double oldVisSpread = peakToPeak(oldVisMean);
        double fixVisSpread = peakToPeak(fixVisMean);
        double oldFitSpread = peakToPeak(oldFitMean);
        double fixFitSpread = peakToPeak(fixFitMean);
        double fixMeanAvg    = mean(fixVisMean);

        // 1. The bug is real: over the fit window the old pipeline's baseline
        //    wanders (that wander == c-hat, the per-frame DC re-split).
        assertTrue(oldFitSpread > OLD_FIT_SPREAD_MIN,
                "old fit-window baseline should wander > " + OLD_FIT_SPREAD_MIN
                        + " (the c-hat re-split), got " + oldFitSpread);

        // 2. Root-cause proof: the fix pins the fit-window baseline to dcStable
        //    essentially exactly — the wander is gone by construction, not merely
        //    reduced (float32 rounding only).
        assertTrue(fixFitSpread < FIX_FIT_SPREAD_MAX,
                "fixed fit-window baseline must be pinned < " + FIX_FIT_SPREAD_MAX
                        + ", got " + fixFitSpread);

        // 3. Over the VISIBLE window (what the eye reads) the fix removes the
        //    c-hat component and stays well under the bench artefact (±20 µV),
        //    strictly improving on the old pipeline.  The small residue that
        //    remains is genuine leftover fundamental from the f-hat mismatch —
        //    shared by both pipelines, NOT the DC-split artefact.
        assertTrue(fixVisSpread < FIX_VIS_SPREAD_MAX,
                "fixed visible baseline spread must be < " + FIX_VIS_SPREAD_MAX
                        + " (bench artefact was ±20 µV), got " + fixVisSpread);
        assertTrue(fixVisSpread < oldVisSpread,
                "fixed visible spread " + fixVisSpread
                        + " must improve on old " + oldVisSpread);

        // 4. The fixed baseline sits at the true, stable DC — NOT re-anchored to
        //    zero — so the DC-coupled display (AC toggle off) keeps its true DC.
        assertTrue(Math.abs(fixMeanAvg - DC) < FIX_MEAN_TOL,
                "fixed baseline must sit at the stable DC " + DC
                        + ", got " + fixMeanAvg);
    }

    /** Mean of {@code buf[from .. from+len)}. */
    private double visibleMean(float[] buf, int from, int len) {
        double sum = 0.0;
        for (int i = 0; i < len; i++) sum += buf[from + i];
        return sum / len;
    }

    private double mean(double[] v) {
        double sum = 0.0;
        for (double x : v) sum += x;
        return sum / v.length;
    }

    private double peakToPeak(double[] v) {
        double min = v[0], max = v[0];
        for (double x : v) {
            if (x < min) min = x;
            if (x > max) max = x;
        }
        return max - min;
    }
}
