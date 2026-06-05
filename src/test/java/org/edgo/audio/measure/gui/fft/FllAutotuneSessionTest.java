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

package org.edgo.audio.measure.gui.fft;

import org.edgo.audio.measure.gui.fft.FllAutotuneSession.Phase;
import org.edgo.audio.measure.gui.fft.FllAutotuneSession.Rule;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link FllAutotuneSession} against a simulated frequency-lock
 * plant — unity gain with a pure transport delay of {@code L} frames
 * (one such "frame" = one FFT result) — and checks that the relay forms a
 * clean limit cycle whose period and gain match the theory:
 * <pre>
 *   Tu ≈ 2·L·dt          (delay → half-period)
 *   Ku ≈ 4/π   (a ≈ d)   (unity gain → err swings ≈ ±d)
 * </pre>
 * Also covers the two failure exits (no limit cycle, too many re-syncs).
 */
class FllAutotuneSessionTest {

    private static final int    SR     = 48000;
    private static final long   HOP    = 16000;         // dt = 1/3 s per frame
    private static final double DT     = HOP / (double) SR;
    // Window == hop in the test so the ≥1-window flip gate is a no-op and the
    // plant's own delay sets the cycle period; bin small enough that the relay
    // floors size d/ε from the noise, not the bin clamp.
    private static final int    WIN    = (int) HOP;
    private static final double BIN    = 0.05;

    /** Runs the relay against an L-frame delay plant and returns the
     *  finished session. */
    private FllAutotuneSession runDelayPlant(int delayFrames, int maxFrames, double noiseHz) {
        return runDelayPlantRule(delayFrames, maxFrames, noiseHz, Rule.CLASSIC);
    }

    private FllAutotuneSession runDelayPlantRule(int delayFrames, int maxFrames, double noiseHz, Rule rule) {
        FllAutotuneSession s = new FllAutotuneSession(0.0, rule);
        double target = 1000.0;
        double[] uHist = new double[maxFrames];
        Random rnd = new Random(42);
        for (int n = 0; n < maxFrames && s.isActive(); n++) {
            double pastU = (n - delayFrames >= 0) ? uHist[n - delayFrames] : 0.0;
            double detected = target + pastU + noiseHz * rnd.nextGaussian();
            double corr = s.process(target, detected, n * HOP, SR, WIN, BIN);
            uHist[n] = corr;                            // bias = 0 → u = corr
        }
        return s;
    }

    @Test
    void relayFormsLimitCycleAndTunes() {
        int L = 6;                                      // θ = 2 s → Tu = 4 s
        FllAutotuneSession s = runDelayPlant(L, 400, 0.0008);

        assertEquals(Phase.DONE, s.getPhase(), "should converge to DONE");

        double expectTu = 2 * L * DT;                   // 4.0 s
        assertEquals(expectTu, s.getMeasuredTuSeconds(), 1.5 * DT,
                "ultimate period ≈ 2·delay");

        double ku = s.getUltimateGainKu();
        assertTrue(ku > 0.8 && ku < 2.0, "Ku ≈ 4/π for unity-gain plant, was " + ku);

        // Ziegler–Nichols classic must give finite, positive gains.
        assertTrue(s.getKp() > 0 && s.getKi() > 0 && s.getKd() > 0,
                "PID gains positive: kp=" + s.getKp() + " ki=" + s.getKi() + " kd=" + s.getKd());
        // CLASSIC: Ki = Kp/(Tu/2), Kd = Kp·Tu/8.
        assertEquals(s.getKp() / (expectTu / 2.0), s.getKi(), 1e-9);
        assertEquals(s.getKp() * (expectTu / 8.0),  s.getKd(), 1e-9);
    }

    @Test
    void imcRuleIsPureIntegralNoOvershoot() {
        int L = 6;
        FllAutotuneSession s = runDelayPlantRule(L, 400, 0.0008, Rule.IMC);
        assertEquals(Phase.DONE, s.getPhase());
        // Pure integral: no proportional (stale-error feedback) or derivative
        // (noise amplification) on a deadtime plant.
        assertEquals(0.0, s.getKp(), 0.0, "Kp must be 0 for IMC");
        assertEquals(0.0, s.getKd(), 0.0, "Kd must be 0 for IMC");
        assertTrue(s.getKi() > 0, "Ki positive");
        // Ki == 1 / (K·(λ+θ)), θ=Tu/2, K=4/(π·Ku), λ=IMC_LAMBDA·θ.
        double tu = s.getMeasuredTuSeconds();
        double theta = 0.5 * tu;
        double k = 4.0 / (Math.PI * s.getUltimateGainKu());
        double expectedKi = 1.0 / (k * (FllAutotuneSession.IMC_LAMBDA * theta + theta));
        assertEquals(expectedKi, s.getKi(), expectedKi * 1e-9);
    }

    @Test
    void differentDelayGivesProportionalPeriod() {
        FllAutotuneSession s = runDelayPlant(10, 600, 0.0008);
        assertEquals(Phase.DONE, s.getPhase());
        assertEquals(2 * 10 * DT, s.getMeasuredTuSeconds(), 1.5 * DT);
    }

    @Test
    void noLimitCycleFails() {
        // Plant ignores the relay (zero gain) → err never crosses the
        // bands → stall → FAILED.
        FllAutotuneSession s = new FllAutotuneSession(
                0.0, Rule.CLASSIC, 12, 2, 5, 4.0, 3.0, 25);   // maxStall = 25
        double target = 1000.0;
        Random rnd = new Random(7);
        for (int n = 0; n < 120 && s.isActive(); n++) {
            double detected = target + 0.0005 * rnd.nextGaussian();   // no response to u
            s.process(target, detected, n * HOP, SR, WIN, BIN);
        }
        assertEquals(Phase.FAILED, s.getPhase());
        assertEquals("autotune.fail.nolimitcycle", s.getFailMessage());
    }

    @Test
    void resyncsAreCountedNotFatal() {
        // Re-syncs are expected from the dither — they accumulate as
        // warnings but never abort the run.
        FllAutotuneSession s = new FllAutotuneSession(0.0, Rule.CLASSIC);
        for (int i = 0; i < 10; i++) s.onResync("fft.warning.discontinuity");
        assertEquals(Phase.NOISE, s.getPhase(), "still running");
        assertEquals(10, s.getResyncCount());
    }
}
