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

package org.edgo.audio.measure.gui.fft;

import org.edgo.audio.measure.common.Constants;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link FrequencyFll} against a simulated lock plant: the generator runs
 * at {@code target + correction}, the FFT measures it as {@code target + correction
 * + drift}, and a correction issued now only reaches the measurement {@code DELAY}
 * frames later (the rig transport delay + window fill).  The loop must cancel the
 * drift, lock the measured frequency to within {@link #LOCK_PPM} of the target, and
 * issue exactly ONE correction per disturbance, holding until a whole window of
 * the corrected signal has been captured rather than stacking corrections it
 * can't yet see the effect of - and, once the trimmed tone has been measured on
 * grid, treat a reading no crystal can produce as a bad frame, following only a
 * move that persists, a bounded step at a time.
 */
class FrequencyFllTest {

    private static final double TARGET   = 1000.0;
    private static final double LOCK_PPM = 0.01;                       // matches FrequencyFll.LOCK_PPM
    private static final double TOL_HZ   = LOCK_PPM * Constants.PPM * TARGET;   // the lock band, in Hz
    private static final int    SR       = 384000;
    private static final int    N        = 1 << 13;
    /** Frames of N after a correction until a measured window lies entirely
     *  past the output path and one window (matches FrequencyFll.OUTPUT_PATH_SEC,
     *  10 ms = 3840 samples, plus N): the second frame after the correction is
     *  the first the loop reads. */
    private static final int    WAIT_FRAMES = 2;
    /** The tracking bound and the step it is followed by, in Hz at the target
     *  (matches FrequencyFll.TRACK_BOUND_PPM and TRACK_STEP_PPM, 0.1 ppm). */
    private static final double BOUND_HZ = 0.1 * Constants.PPM * TARGET;
    /** Frames of N over which readings beyond the bound must persist before
     *  they are followed: 2 s of capture (matches FrequencyFll.PERSIST_SEC),
     *  93.75 frames, so the 94th frame beyond the bound is the first step. */
    private static final int    PERSIST_FRAMES = (int) Math.ceil(2.0 * SR / N);

    /** Result of one plant run: how many times the correction changed, the
     *  correction after every frame, and the final measured error (Hz). */
    private static final class Run {
        int      corrections;
        double[] corr;
        double   finalErrorHz;
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
        r.corr         = corr;
        r.finalErrorHz = detected - TARGET;
        return r;
    }

    private double[] constantDrift(int frames, double driftHz) {
        double[] d = new double[frames];
        Arrays.fill(d, driftHz);
        return d;
    }

    @Test
    void correctsOnceThenHoldsUntilLocked() {
        // A constant 50 ppm drift, correction arriving within the loop's wait.
        // The loop must correct ONCE and then hold (no stacking), and the
        // measurement must land within the lock band.
        int frames = 60;
        Run r = drive(frames, WAIT_FRAMES, constantDrift(frames, 0.05));   // 50 ppm
        assertEquals(1, r.corrections, "must correct once then hold (no dead-time over-regulation)");
        assertTrue(Math.abs(r.finalErrorHz) <= TOL_HZ,
                "did not lock: " + r.finalErrorHz / TARGET * 1e6 + " ppm");
    }

    @Test
    void theWaitIsAWholeWindowPastTheOutputPath() {
        // After a correction the loop reads nothing until a measured window
        // ends past the correction's capture position plus the output path
        // plus one window: the frame right after the correction still holds
        // the old tone and is held whatever it reads; the second frame is
        // read again.
        FrequencyFll fll = new FrequencyFll();
        fll.update(TARGET, TARGET + 0.05, 0, N, SR, N);                    // 50 ppm: corrected
        double corrected = fll.getCorrection();
        fll.update(TARGET, TARGET + 0.05, N, 2L * N, SR, N);               // old tone still: held
        assertEquals(corrected, fll.getCorrection(), "the frame after the correction is held");
        fll.update(TARGET, TARGET, 2L * N, 3L * N, SR, N);                 // on grid: read, tracking from here
        fll.update(TARGET, TARGET + 0.05 * Constants.PPM * TARGET, 3L * N, 4L * N, SR, N);
        assertNotEquals(corrected, fll.getCorrection(),
                "a reading inside the bound is acted on once the wait is over");
    }

    @Test
    void aMoveWhileTrackingIsFollowedInSteps() {
        // Lock, then step the drift by 0.5 ppm for good.  While tracking, a
        // reading beyond 0.1 ppm is a bad frame and ignored - the correction
        // stands untouched for the whole persistence - and only then is the
        // move followed, 0.1 ppm per read frame, until the loop is locked
        // again: one correction for the initial drift, five for the move.
        int frames = 200;
        double[] drift = constantDrift(frames, 0.05);
        for (int i = 40; i < frames; i++) drift[i] = 0.05 + 0.5 * Constants.PPM * TARGET;
        Run r = drive(frames, WAIT_FRAMES, drift);
        int firstStep = 40 + PERSIST_FRAMES;
        assertEquals(r.corr[0], r.corr[firstStep - 1], "ignored until the readings have persisted");
        assertNotEquals(r.corr[0], r.corr[firstStep], "followed once they have");
        for (int i = 1; i < frames; i++) {
            assertTrue(Math.abs(r.corr[i] - r.corr[i - 1]) <= BOUND_HZ * (1.0 + 1e-9),
                    "frame " + i + ": a step larger than the bound");
        }
        assertEquals(6, r.corrections, "the initial correction and five steps of 0.1 ppm");
        assertTrue(Math.abs(r.finalErrorHz) <= TOL_HZ,
                "did not re-lock after the move: " + r.finalErrorHz / TARGET * 1e6 + " ppm");
    }

    @Test
    void aBadReadingWhileTrackingIsIgnored() {
        // Locked and tracking, one frame reads 4.5 ppm off - a broken frame,
        // not a crystal - and the next frames read on grid again.  The
        // correction does not move.
        int frames = 60;
        double[] drift = constantDrift(frames, 0.05);
        drift[20] += 4.5 * Constants.PPM * TARGET;
        Run r = drive(frames, WAIT_FRAMES, drift);
        assertEquals(1, r.corrections, "a single bad reading must not move the correction");
        assertEquals(r.corr[19], r.corr[21], "the correction stands across the bad frame");
        assertTrue(Math.abs(r.finalErrorHz) <= TOL_HZ,
                "must stay locked: " + r.finalErrorHz / TARGET * 1e6 + " ppm");
    }

    @Test
    void aLongGapMakesNoDriftStep() {
        // The loop holds a drift estimate (a residual folded in over a 4-s
        // baseline).  A reading 100 s after the previous one gets no drift
        // step - the pause is not drift observed - while a reading one frame
        // after the previous one does.
        FrequencyFll fll = new FrequencyFll();
        long fourSec = 4L * SR;
        fll.update(TARGET, TARGET + 0.05, 0, N, SR, N);                    // 50 ppm: corrected
        fll.update(TARGET, TARGET, 2L * N, 3L * N, SR, N);                 // on grid: tracking
        fll.update(TARGET, TARGET + 0.05 * Constants.PPM * TARGET,         // 0.05 ppm over 4 s: a rate
                fourSec, fourSec + N, SR, N);
        double before = fll.getCorrection();
        long muchLater = fourSec + 100L * SR;
        fll.update(TARGET, TARGET, muchLater, muchLater + N, SR, N);       // 100-s gap: no step
        assertEquals(before, fll.getCorrection(), "a long gap applies no drift");
        fll.update(TARGET, TARGET, muchLater + N, muchLater + 2L * N, SR, N);   // one frame later: a step
        assertNotEquals(before, fll.getCorrection(), "one frame later the drift step is made");
    }

    @Test
    void subBandJitterIsHeldNotChased() {
        // Measurement jitter INSIDE the lock band must not provoke corrections -
        // the loop holds instead of chasing noise.
        int frames = 80;
        double[] drift = constantDrift(frames, 0.05);
        for (int i = 0; i < frames; i++) {
            drift[i] += (i % 2 == 0 ? 1 : -1) * 0.4 * TOL_HZ;        // ±0.4 band, alternating
        }
        Run r = drive(frames, WAIT_FRAMES, drift);
        assertEquals(1, r.corrections, "sub-band jitter must be held, not chased");
        assertTrue(Math.abs(r.finalErrorHz) <= TOL_HZ,
                "must stay inside the lock band: " + r.finalErrorHz / TARGET * 1e6 + " ppm");
    }

    @Test
    void stuckMeasurementCannotStackCorrections() {
        // Worst-case transport fault: the plant NEVER reflects corrections (e.g.
        // trim events not reaching the generator, or a measurement pinned by a
        // poisoned average).  The loop may keep re-correcting, but only once per
        // wait - never faster than a whole window of the corrected signal could
        // physically arrive.  Regression test for the runaway that walked the
        // generator hundreds of ppm off grid: heuristic dead-time tracking
        // mis-measured the loop delay and stacked full-size corrections every
        // second against a ~3.6 s physical round trip.
        FrequencyFll fll = new FrequencyFll();
        long absStart = 0;
        int changes = 0;
        double prev = fll.getCorrection();
        int frames = 200;
        for (int i = 0; i < frames; i++) {
            fll.update(TARGET, TARGET + 0.05, absStart, absStart + N, SR, N);
            if (fll.getCorrection() != prev) changes++;
            prev = fll.getCorrection();
            absStart += N;
        }
        // One correction per wait of WAIT_FRAMES frames at most.
        int maxAllowed = frames / WAIT_FRAMES + 2;
        assertTrue(changes <= maxAllowed,
                "corrections stacked faster than the wait: " + changes
                        + " in " + frames + " frames (max " + maxAllowed + ")");
    }
}
