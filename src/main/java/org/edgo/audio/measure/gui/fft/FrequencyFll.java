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
 * grid.  It cancels the measured error in one step, then <b>waits for that
 * correction to land</b> before issuing the next — never stacking corrections on an
 * error the in-flight one will already fix (the cause of dead-time over-regulation).
 *
 * <h2>Update law</h2>
 * <p>"Landed" is judged <b>relative to the size of the correction</b>: after
 * correcting an error {@code e₀}, the loop holds until the measured error has fallen
 * to {@link #ARRIVED_FRACTION} of {@code e₀} (≈95&nbsp;% of the correction is
 * reflected).  Only then does it act again — correcting the residual if it is still
 * outside the lock band, otherwise holding.  Successive residuals shrink toward zero
 * (e.g. 1.75&nbsp;ppm → 0.0875&nbsp;ppm → …), so the loop settles well inside the band.
 * <pre>
 *   error = detected − target
 *   waiting:  hold while |error| &gt; ARRIVED_FRACTION · e₀     // correction still landing
 *   landed / steady:
 *       |error| ≤ LOCK_PPM·target → hold (locked / within noise)
 *       else                      → correction −= error, wait  // one-step deadbeat
 * </pre>
 * The relative arrival test is what lets a correction be judged landed at any scale
 * and lets the residual be chased below {@link #LOCK_PPM}.  {@code LOCK_PPM} is the
 * steady-state band: it must be ≈ the measurement's own frequency jitter — below it
 * the loop would chase noise (chatter) and a wide relative band would miss real
 * drift, so it cannot be zero, but it is small (the noise floor), not a coarse lock.
 *
 * <h2>Threading</h2>
 * <p>Not synchronized.  Called from the SWT UI thread.
 */
public final class FrequencyFll implements FrequencyAligner {

    /** A correction has landed once the measured error has fallen to this fraction of
     *  the amount that was corrected (≈98 % reflected). */
    private static final double ARRIVED_FRACTION = 0.02;
    /** Steady-state lock band (ppm of target): hold within it, re-correct when drift
     *  leaves it.  Must be ≥ the measurement's <em>peak-to-peak</em> frequency jitter,
     *  because a one-step deadbeat bakes in the jitter present at the correction
     *  instant, so the residual can swing by up to that peak-to-peak.  Below it the
     *  loop chases noise (chatter) and can't tell drift from jitter, so it can't be
     *  zero — it is the measurement's own frequency-noise floor. */
    private static final double LOCK_PPM         = 0.01;

    /** Current correction in Hz — add to the snap target before publishing the trim. */
    @Getter
    private double  correction        = 0.0;
    /** |error| at the moment the current correction was issued — the reference the
     *  relative arrival threshold scales from. */
    private double  errorAtCorrection = 0.0;
    private boolean waiting           = false;

    @Override
    public void update(double target, double detected, long absStartSamples, long latestSamplePos,
                       int sampleRate, int fftSize) {
        if (!Double.isFinite(target) || !Double.isFinite(detected) || !(target > 0.0)) {
            return;
        }
        double error  = detected - target;
        double absErr = Math.abs(error);

        if (waiting) {
            if (absErr > ARRIVED_FRACTION * errorAtCorrection) {
                return;                            // correction still landing — hold, don't stack another
            }
            waiting = false;                       // landed
        }
        if (absErr <= LOCK_PPM * 1e-6 * target) {
            return;                                // within the lock band — hold
        }
        // Cancel the current (landed) error in one step, then wait for it to land.
        correction -= error;
        errorAtCorrection = absErr;
        waiting = true;
    }

    @Override
    public void reset() {
        correction        = 0.0;
        errorAtCorrection = 0.0;
        waiting           = false;
    }

    public AlignGenerator getMode() {
        return AlignGenerator.FLL;
    }
}
