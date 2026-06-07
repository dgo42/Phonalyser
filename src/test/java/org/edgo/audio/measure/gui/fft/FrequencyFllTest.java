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

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link FrequencyFll} against a simulated lock plant: the generator runs
 * at {@code target + correction}, the FFT measures it as {@code target + correction
 * + drift}, and a correction issued now only reaches the measurement {@code DELAY}
 * frames later (the rig transport delay + window fill).  The loop must cancel the
 * drift, lock the measured frequency to within {@link #LOCK_PPM} of the target, and
 * — crucially — issue exactly ONE correction per disturbance, holding through the
 * dead time rather than stacking corrections it can't yet see the effect of.
 */
class FrequencyFllTest {

    private static final double TARGET   = 1000.0;
    private static final double LOCK_PPM = 0.01;                       // matches FrequencyFll.LOCK_PPM
    private static final double TOL_HZ   = LOCK_PPM * 1e-6 * TARGET;   // the lock band, in Hz
    private static final int    SR       = 384000;
    private static final int    N        = 1 << 13;

    /** Result of one plant run: how many times the correction changed and the final
     *  measured error (Hz). */
    private static final class Run {
        int    corrections;
        double finalErrorHz;
    }

    private Run drive(int frames, int delay, double[] driftAt) {
        FrequencyFll fll = new FrequencyFll();
        double[] corr = new double[frames];
        long absStart = 0;
        int changes = 0;
        double prev = fll.getCorrection();
        double detected = TARGET;
        for (int i = 0; i < frames; i++) {
            double applied = (i - delay >= 0) ? corr[i - delay] : 0.0;
            detected = TARGET + driftAt[i] + applied;
            fll.update(TARGET, detected, absStart, absStart + N, SR, N);
            corr[i] = fll.getCorrection();
            if (corr[i] != prev) changes++;
            prev = corr[i];
            absStart += N;
        }
        Run r = new Run();
        r.corrections  = changes;
        r.finalErrorHz = detected - TARGET;
        return r;
    }

    private static double[] constantDrift(int frames, double driftHz) {
        double[] d = new double[frames];
        Arrays.fill(d, driftHz);
        return d;
    }

    @Test
    void correctsOnceThenHoldsUntilLocked() {
        // A constant 50 ppm drift, correction arriving 5 frames late.  The loop must
        // correct ONCE and then hold through the 5-frame dead time (no stacking), and
        // the measurement must land within ±0.1 ppm.
        int frames = 60, delay = 5;
        Run r = drive(frames, delay, constantDrift(frames, 0.05));   // 50 ppm
        assertEquals(1, r.corrections, "must correct once then hold (no dead-time over-regulation)");
        assertTrue(Math.abs(r.finalErrorHz) <= TOL_HZ,
                "did not lock to 0.1 ppm: " + r.finalErrorHz / TARGET * 1e6 + " ppm");
    }

    @Test
    void holdsThroughALongDeadTime() {
        // Even with a long (20-frame) dead time the loop issues a single correction
        // and waits it out — the whole point of waiting for ±0.1 ppm rather than
        // re-correcting every frame the error is still non-zero.
        int frames = 80, delay = 20;
        Run r = drive(frames, delay, constantDrift(frames, 0.03));   // 30 ppm
        assertEquals(1, r.corrections, "a long dead time must not provoke extra corrections");
        assertTrue(Math.abs(r.finalErrorHz) <= TOL_HZ,
                "did not lock after the long dead time: " + r.finalErrorHz / TARGET * 1e6 + " ppm");
    }

    @Test
    void reCorrectsWhenDriftLeavesTheBand() {
        // Lock, then step the drift (a sudden clock-ratio change): the error leaves
        // the band, so the loop issues a SECOND correction and re-locks — two
        // corrections total, not a continuous stream.
        int frames = 120, delay = 5;
        double[] drift = constantDrift(frames, 0.05);
        for (int i = 40; i < frames; i++) drift[i] = 0.08;           // +30 ppm step at frame 40
        Run r = drive(frames, delay, drift);
        assertEquals(2, r.corrections, "one correction per disturbance, then hold");
        assertTrue(Math.abs(r.finalErrorHz) <= TOL_HZ,
                "did not re-lock after the drift step: " + r.finalErrorHz / TARGET * 1e6 + " ppm");
    }

    @Test
    void ignoresJitterInsideTheBand() {
        // Once locked, per-frame jitter whose peak-to-peak stays inside the lock band
        // must not provoke any correction — the loop holds.  (A one-step deadbeat
        // bakes in the jitter at the correction instant, so the residual swings by up
        // to the peak-to-peak; keep the amplitude below half the band.)
        int frames = 80, delay = 5;
        double[] drift = constantDrift(frames, 0.05);
        for (int i = 0; i < frames; i++) {
            drift[i] += (i % 2 == 0 ? 1 : -1) * 0.4 * TOL_HZ;        // ±0.4 band, alternating
        }
        Run r = drive(frames, delay, drift);
        assertEquals(1, r.corrections, "sub-band jitter must not trigger corrections");
        assertTrue(Math.abs(r.finalErrorHz) <= TOL_HZ,
                "jitter pushed it out of band: " + r.finalErrorHz / TARGET * 1e6 + " ppm");
    }
}
