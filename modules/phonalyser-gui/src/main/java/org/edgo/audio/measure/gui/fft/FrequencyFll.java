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

import lombok.Getter;

import org.edgo.audio.measure.common.Constants;
import org.edgo.audio.measure.enums.AlignGenerator;

/**
 * Frequency-lock loop that aligns the generator's DDS frequency with the FFT bin
 * grid.  One-step deadbeat with <b>exact transport bookkeeping</b>: when a
 * correction is issued, the loop records the absolute capture position by which
 * a whole window of the corrected signal has been captured
 * ({@code writePos + output path + one window}); until a result's analysis
 * window <em>ends</em> past that position, every update is held
 * unconditionally - such a measurement still holds samples of the uncorrected
 * signal, and acting on it would stack a second correction onto an error the
 * in-flight one already cancels.
 *
 * <h2>Update law</h2>
 * <pre>
 *   measurement window ends before correctionVisibleFrom   -> hold (in flight)
 *   tracking and |detected − target| > TRACK_BOUND_PPM·target
 *        -> ignored, a bad frame - until such readings have persisted for
 *           PERSIST_SEC or one window, then correction moves toward them
 *           by at most TRACK_STEP_PPM·target per reading
 *   |detected − target| ≤ LOCK_PPM·target                  -> hold (locked / noise)
 *   else -> correction −= error;
 *           correctionVisibleFrom = writePos + output path + one window
 * </pre>
 *
 * <p>Tracking begins with the first reading within {@link #TRACKED_PPM} of the
 * target and ends with a reset.  Before it - a fresh loop, the two converter
 * crystals' tolerance still uncorrected - a correction may be as large as
 * the caller's plausibility bound allows; once the trimmed tone has been
 * measured on grid, no crystal moves it further than the tracking bound
 * between two readings, so a reading out there is a bad frame and costs
 * nothing, and a real move is followed a bounded step at a time.
 *
 * <p>Earlier revisions modelled the transport heuristically - update-call
 * counts, a relative arrival test, a measured dead time, a give-up timeout.
 * Those mis-measured the dead time under fine-track noise and then re-corrected
 * faster than the ~3-4 s physical loop delay (DAC buffer drain + the analysis
 * window, whose measurement frames sit at the window START), stacking full-size
 * corrections every second - a runaway that walked the generator hundreds of
 * ppm off grid.  Gating on capture sample positions replaces all of it and is
 * immune to IRREGULAR delays too: a buffer overrun / re-sync jumps
 * {@code samplesAbsStart} forward past the gap, display throttling and GC
 * pauses change only the update cadence - none of which the gate even sees.
 * Corrections can neither stack nor wedge, by construction; a transient
 * mis-measurement costs exactly one bounded, fully observed round trip.
 *
 * <h2>Threading</h2>
 * <p>Not synchronized.  Stepped on the FFT analyser worker's thread; reset there
 * or, while no worker thread runs, on the caller's thread.
 */
public final class FrequencyFll implements FrequencyAligner {

    /** Steady-state lock band (ppm of target): hold within it, re-correct when
     *  drift leaves it.  Must be ≈ the measurement's own frequency jitter -
     *  below it the loop chases noise, far above it real drift goes
     *  uncorrected. */
    private static final double LOCK_PPM        = 0.01;
    /** A reading this close to the target (ppm of target) says the trimmed
     *  tone is on grid: from it on the loop is tracking and the bound below
     *  applies. */
    private static final double TRACKED_PPM     = 0.05;
    /** How far a reading may stand from the target while tracking (ppm of
     *  target).  Two crystals move ppb per second, so a reading further out
     *  between two readings is a bad frame, not a moved clock, and is
     *  ignored - unless such readings persist for {@link #PERSIST_SEC} or
     *  one window, which is a signal that really moved. */
    private static final double TRACK_BOUND_PPM = 0.1;
    /** The most one reading may move the correction while tracking (ppm of
     *  target): a persistent move is followed a step at a time, so a run of
     *  bad frames costs at most this per reading. */
    private static final double TRACK_STEP_PPM  = 0.1;
    /** How long readings beyond the tracking bound must persist before they
     *  are followed, seconds of capture - or one window, whichever is
     *  longer, so that a long window with little overlap still gets more
     *  than one reading. */
    private static final double PERSIST_SEC     = 2.0;
    /** The output path, seconds: from the trim event through the driver's
     *  output buffer, the DAC, the bench and the ADC back into the capture.
     *  Added past the publish-time capture head, with one window on top,
     *  when computing by when a whole window of the corrected signal has
     *  been captured. */
    private static final double OUTPUT_PATH_SEC = 0.010;
    /** EWMA weight on the previous drift estimate when a deadbeat folds a
     *  fresh slope measurement in - smooths the estimator noise riding on
     *  each band exit. */
    private static final double DRIFT_SMOOTH    = 0.7;
    /** Drift sanity cap (ppm of target per second): non-disciplined crystals
     *  wander orders below this; anything larger is a mis-measurement. */
    private static final double MAX_DRIFT_PPM_PER_SEC = 0.01;
    /** Minimum capture-time baseline between two deadbeats for the residual
     *  to qualify as a slope measurement - closer exits are estimator noise,
     *  not wander, and would poison the drift estimate. */
    private static final double MIN_DRIFT_BASELINE_SEC = 2.0;
    /** The longest gap between two readings, seconds of capture - or one
     *  window, whichever is longer - over which the drift estimate is still
     *  applied.  A longer gap is a pause of the readings, not drift observed:
     *  a wrong rate times a long pause, applied at once, moves the generator
     *  bins off the grid. */
    private static final double DRIFT_GAP_SEC = 10.0;

    /** Current correction in Hz - add to the snap target before publishing the trim. */
    @Getter
    private double correction = 0.0;
    /** Absolute capture sample position by which a whole window of the last
     *  issued correction's signal has been captured - a measurement window
     *  ending past it reflects that correction; {@code -1} = nothing in
     *  flight (every measurement is usable). */
    private long correctionVisibleFrom = -1;
    /** Drift-rate feedforward (Hz/s): the EWMA-tracked rate at which the
     *  measured error rebuilds between corrections - the relative wander of
     *  the two free-running converter crystals.  Applied predictively on
     *  every update whose previous reading is at most {@link #DRIFT_GAP_SEC}
     *  or one window old, so the error stays near zero INSTEAD of
     *  sawtoothing to the band edge once per transport round-trip. */
    private double driftHzPerSec = 0.0;
    /** Capture position of the previous update - feedforward dt baseline. */
    private long lastUpdateAbsPos = -1;
    /** Capture position of the previous deadbeat - slope-measurement baseline. */
    private long lastDeadbeatAbsPos = -1;
    /** Whether the trimmed tone has been measured within {@link #TRACKED_PPM}
     *  of the target since the last reset. */
    @Getter
    private boolean tracking;
    /** Capture position of the first reading of the current run beyond the
     *  tracking bound; {@code -1} while readings are inside it. */
    private long outOfBoundSinceAbsPos = -1;

    @Override
    public void update(double target, double detected, long absStartSamples, long latestSamplePos,
                       int sampleRate, int fftSize) {
        if (!Double.isFinite(target) || !Double.isFinite(detected) || !(target > 0.0)) {
            return;
        }
        // Drift feedforward: predictive sub-band microsteps on EVERY update
        // (held ones included - prediction needs no transport verification;
        // its residual is folded back in at the next deadbeat).  dt comes
        // from capture positions, so display cadence / GC pauses don't skew it.
        if (lastUpdateAbsPos >= 0 && sampleRate > 0 && absStartSamples > lastUpdateAbsPos) {
            long gap    = absStartSamples - lastUpdateAbsPos;
            long gapMax = Math.max((long) Math.ceil(DRIFT_GAP_SEC * sampleRate), fftSize);
            if (gap <= gapMax) correction -= driftHzPerSec * (gap / (double) sampleRate);
        }
        lastUpdateAbsPos = absStartSamples;
        // Transport gate: the measurement's window ends at absStartSamples +
        // fftSize - if that predates the point by which a whole window of the
        // corrected signal was captured, the measurement still holds samples
        // of the UNcorrected signal.  Hold.
        if (correctionVisibleFrom >= 0 && absStartSamples + fftSize < correctionVisibleFrom) {
            return;
        }
        correctionVisibleFrom = -1;            // in-flight correction fully observed
        double error = detected - target;
        if (tracking) {
            if (Math.abs(error) > TRACK_BOUND_PPM * Constants.PPM * target) {
                // No crystal moves this far between two readings: a bad
                // frame, and the trim and the drift estimate stay - unless
                // the readings persist, which is a signal that really moved
                // and is followed a bounded step at a time.
                if (outOfBoundSinceAbsPos < 0) outOfBoundSinceAbsPos = absStartSamples;
                long persist = Math.max((long) Math.ceil(PERSIST_SEC * sampleRate), fftSize);
                if (absStartSamples - outOfBoundSinceAbsPos < persist) return;
                double step = TRACK_STEP_PPM * Constants.PPM * target;
                correction -= Math.max(-step, Math.min(step, error));
                lastDeadbeatAbsPos = absStartSamples;
                markCorrectionInFlight(latestSamplePos, sampleRate, fftSize);
                return;
            }
            outOfBoundSinceAbsPos = -1;
        } else if (Math.abs(error) <= TRACKED_PPM * Constants.PPM * target) {
            tracking = true;
        }
        if (Math.abs(error) <= LOCK_PPM * Constants.PPM * target) {
            return;                            // within the lock band - hold
        }
        // Fold the residual into the drift estimate: error that accumulated
        // since the last deadbeat DESPITE the feedforward measures the slope
        // estimation error.  Gated on a sane baseline and clamped so a noisy
        // band exit can't poison the estimate.
        if (lastDeadbeatAbsPos >= 0 && sampleRate > 0) {
            double dtc = (absStartSamples - lastDeadbeatAbsPos) / (double) sampleRate;
            if (dtc >= MIN_DRIFT_BASELINE_SEC) {
                double cap   = target * MAX_DRIFT_PPM_PER_SEC * Constants.PPM;
                double sMeas = Math.max(-cap, Math.min(cap, error / dtc));
                driftHzPerSec = DRIFT_SMOOTH * driftHzPerSec + (1.0 - DRIFT_SMOOTH) * sMeas;
                driftHzPerSec = Math.max(-cap, Math.min(cap, driftHzPerSec));
            }
        }
        lastDeadbeatAbsPos = absStartSamples;
        // Cancel the (fully observed) error in one step, then wait until the
        // capture provably contains the corrected signal.
        correction -= error;
        markCorrectionInFlight(latestSamplePos, sampleRate, fftSize);
    }

    /** Records by when a whole window of the correction just issued has been
     *  captured: the capture head at publish, the output path, and one
     *  window.  Every reading from a window that ends before it is held. */
    private void markCorrectionInFlight(long latestSamplePos, int sampleRate, int fftSize) {
        if (sampleRate > 0) {
            correctionVisibleFrom = latestSamplePos
                    + (long) Math.ceil(OUTPUT_PATH_SEC * sampleRate) + fftSize;
        }
    }

    @Override
    public void reset() {
        correction            = 0.0;
        correctionVisibleFrom = -1;
        driftHzPerSec         = 0.0;
        lastUpdateAbsPos      = -1;
        lastDeadbeatAbsPos    = -1;
        tracking              = false;
        outOfBoundSinceAbsPos = -1;
    }

    public AlignGenerator getMode() {
        return AlignGenerator.FLL;
    }
}
