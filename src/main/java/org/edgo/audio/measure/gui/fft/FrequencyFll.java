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

import lombok.Getter;

import org.edgo.audio.measure.enums.AlignGenerator;

/**
 * Frequency-lock loop that aligns the generator's DDS frequency with the FFT bin
 * grid.  One-step deadbeat with <b>exact transport bookkeeping</b>: when a
 * correction is issued, the loop records the absolute capture position where the
 * corrected signal can first appear in a measurement
 * ({@code writePos + DAC-drain guard}); until a result's analysis window
 * <em>starts</em> past that position, every update is held unconditionally —
 * such a measurement provably predates the correction, and acting on it would
 * stack a second correction onto an error the in-flight one already cancels.
 *
 * <h2>Update law</h2>
 * <pre>
 *   measurement window starts before correctionVisibleFrom → hold (in flight)
 *   |detected − target| ≤ LOCK_PPM·target                  → hold (locked / noise)
 *   else → correction −= error;  correctionVisibleFrom = writePos + drain
 * </pre>
 *
 * <p>Earlier revisions modelled the transport heuristically — update-call
 * counts, a relative arrival test, a measured dead time, a give-up timeout.
 * Those mis-measured the dead time under fine-track noise and then re-corrected
 * faster than the ~3–4 s physical loop delay (DAC buffer drain + the analysis
 * window, whose measurement frames sit at the window START), stacking full-size
 * corrections every second — a runaway that walked the generator hundreds of
 * ppm off grid.  Gating on capture sample positions replaces all of it and is
 * immune to IRREGULAR delays too: a buffer overrun / re-sync jumps
 * {@code samplesAbsStart} forward past the gap, display throttling and GC
 * pauses change only the update cadence — none of which the gate even sees.
 * Corrections can neither stack nor wedge, by construction; a transient
 * mis-measurement costs exactly one bounded, fully observed round trip.
 *
 * <h2>Threading</h2>
 * <p>Not synchronized.  Called from the SWT UI thread.
 */
public final class FrequencyFll implements FrequencyAligner {

    /** Steady-state lock band (ppm of target): hold within it, re-correct when
     *  drift leaves it.  Must be ≈ the measurement's own frequency jitter —
     *  below it the loop chases noise, far above it real drift goes
     *  uncorrected. */
    private static final double LOCK_PPM        = 0.01;
    /** Output-pipeline drain guard (seconds): a correction published now still
     *  has the OLD tone queued ahead of it in the DAC's hardware buffer
     *  (≈480 ms on the JavaSound render path).  Added past the publish-time
     *  capture head when computing where the corrected signal becomes
     *  measurable. */
    private static final double DRAIN_GUARD_SEC = 0.7;

    /** Current correction in Hz — add to the snap target before publishing the trim. */
    @Getter
    private double correction = 0.0;
    /** Absolute capture sample position from which a measurement window
     *  reflects the last issued correction; {@code -1} = nothing in flight
     *  (every measurement is usable). */
    private long correctionVisibleFrom = -1;

    @Override
    public void update(double target, double detected, long absStartSamples, long latestSamplePos,
                       int sampleRate, int fftSize) {
        if (!Double.isFinite(target) || !Double.isFinite(detected) || !(target > 0.0)) {
            return;
        }
        // Transport gate: the measurement's frames begin at absStartSamples —
        // if that predates the point where the last correction reached the
        // ADC, the measurement reflects the UNcorrected signal.  Hold.
        if (correctionVisibleFrom >= 0 && absStartSamples < correctionVisibleFrom) {
            return;
        }
        correctionVisibleFrom = -1;            // in-flight correction fully observed
        double error = detected - target;
        if (Math.abs(error) <= LOCK_PPM * 1e-6 * target) {
            return;                            // within the lock band — hold
        }
        // Cancel the (fully observed) error in one step, then wait until the
        // capture provably contains the corrected signal.
        correction -= error;
        if (sampleRate > 0) {
            correctionVisibleFrom = latestSamplePos
                    + (long) Math.ceil(DRAIN_GUARD_SEC * sampleRate);
        }
    }

    @Override
    public void reset() {
        correction            = 0.0;
        correctionVisibleFrom = -1;
    }

    public AlignGenerator getMode() {
        return AlignGenerator.FLL;
    }
}
