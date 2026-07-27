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

package org.edgo.audio.measure.fft;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Convergence + sign check for {@link DerotationPhaseLock}.  Models the worker
 * loop: each tick the fundamental is de-rotated with the lock's CURRENT kappa,
 * so the observed (residual) phase is 2π·(κtrue − κ)·delta/N; the loop must
 * drive κ → κtrue and lock — and stay stable (the old per-tick PLL diverged).
 */
class DerotationPhaseLockTest {

    private static final int  N   = 2_097_152;     // 2M FFT
    private static final long HOP = 262_144;       // 87.5% overlap

    private void runConverges(double kappaTrue, double seedErr, double noiseRad) {
        DerotationPhaseLock pll =
                new DerotationPhaseLock(N, kappaTrue + seedErr, 8, 0.001, 2, 12);
        Random rnd = new Random(1);
        double phi0 = 0.7;
        long delta = 0;
        boolean locked = false;
        for (int t = 0; t < 400 && !locked; t++) {
            delta += HOP;
            double residual = 2.0 * Math.PI * (kappaTrue - pll.kappa()) * delta / N
                            + phi0 + noiseRad * rnd.nextGaussian();
            locked = pll.observe(DerotationPhaseLock.wrapToPi(residual), delta);
        }
        assertTrue(locked, String.format("did not lock (seedErr=%.4f noise=%.3f, err=%.5f)",
                seedErr, noiseRad, pll.kappa() - kappaTrue));
        assertTrue(Math.abs(pll.kappa() - kappaTrue) < 2e-3,
                String.format("kappa not converged: err=%.6f (seedErr=%.4f noise=%.3f)",
                        pll.kappa() - kappaTrue, seedErr, noiseRad));
    }

    @Test
    void convergesForBothSignsAndSeeds() {
        double kappaTrue = 5482.371;   // ~1003.6 Hz @ 384k / 2M
        for (double seedErr : new double[]{+0.05, -0.05, +0.2, -0.2, +0.5, -0.5}) {
            runConverges(kappaTrue, seedErr, 0.0);
        }
    }

    @Test
    void convergesWithPhaseNoise() {
        double kappaTrue = 5482.371;
        for (double seedErr : new double[]{+0.3, -0.3}) {
            runConverges(kappaTrue, seedErr, 0.0005);  // high-SNR-grade per-tick phase noise
        }
    }
}
