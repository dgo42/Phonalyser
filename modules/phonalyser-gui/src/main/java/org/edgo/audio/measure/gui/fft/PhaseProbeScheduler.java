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

/**
 * When the next notch-phase probe is due - the schedule alone, none of the
 * measuring.
 *
 * <p>A probe is one interruption of a running FFT collection in which the
 * fundamental's phase through the notch filter is measured afresh (a short
 * sweep across a narrow band around the fundamental, deconvolved and read at
 * the fundamental); the difference against the collection's first, baseline,
 * probe is the phase excess the accumulator is corrected by, and it is held
 * constant until the next probe replaces it.  Between probes, therefore, the
 * correction ages at the notch's own drift rate, and that is what sets the
 * interval: the period is the phase budget divided by the drift rate the last
 * two probes measured.
 *
 * <p>Every time and every phase arrives as an argument - this class reads no
 * clock - so the caller owns the one time base (the worker's own thread) and a
 * test can drive years of schedule in a millisecond.  Times are seconds, the
 * phase residual is degrees (the budget below is a degree figure, so the one
 * conversion sits at the caller).  State is the last probe only: its time and
 * its phase, which with the probe being recorded are the "last two probes" the
 * rate comes from.
 */
public final class PhaseProbeScheduler {

    /** Interval from the baseline probe to the second one.  The drift rate
     *  cannot be measured before two probes exist, so the first interval is
     *  fixed rather than derived: a minute puts the second probe close enough
     *  to the baseline that a fast-drifting notch is caught early, while one
     *  seam per minute costs the average little. */
    private static final double PROBE_PERIOD_INITIAL_SEC = 60.0;
    /** How much the held correction is allowed to age between probes, in
     *  degrees of fundamental phase.  The correction is constant between
     *  probes, so the error it carries is the drift rate times the interval;
     *  dividing this budget by the measured rate is what keeps that error at a
     *  degree whatever the notch's temperature is doing. */
    private static final double PROBE_PHASE_BUDGET_DEG = 1.0;
    /** Floor of the derived interval.  Each probe pauses the accumulation for
     *  the length of the sweep, so a runaway drift rate must not be answered
     *  with probes so dense that the collection spends its time probing. */
    private static final double PROBE_PERIOD_MIN_SEC = 30.0;
    /** Ceiling of the derived interval, also the answer for a notch that
     *  measured as perfectly still: a rate of zero divides into an infinite
     *  interval, and a filter is re-measured at least this often because a
     *  rate read from two probes is an estimate, not a promise. */
    private static final double PROBE_PERIOD_MAX_SEC = 600.0;

    /** Time of the last recorded probe, or {@code NaN} before the first one -
     *  the state that makes the baseline probe due immediately. */
    private double lastProbeAtSec = Double.NaN;
    /** Earliest time a probe may run again whatever the interval says, or
     *  {@code NaN} when nothing holds it back.  Only a probe that could not
     *  measure sets it: such a probe belongs in no drift history, but retrying
     *  it at the next opportunity would spend the collection on probes that
     *  cannot deliver, so the floor interval separates the attempts. */
    private double notBeforeSec = Double.NaN;
    /** Phase residual of the last recorded probe, in degrees. */
    private double lastPhaseDeg;
    /** The interval currently in force, in seconds. */
    @Getter
    private double periodSec = PROBE_PERIOD_INITIAL_SEC;

    /** Whether a probe is due at {@code nowSec}.  True before any probe has
     *  been recorded - that is the baseline probe of the collection, which runs
     *  before the first accumulated tick. */
    public boolean isDue(double nowSec) {
        if (!Double.isNaN(notBeforeSec) && nowSec < notBeforeSec) {
            return false;
        }
        if (Double.isNaN(lastProbeAtSec)) {
            return true;
        }
        return nowSec - lastProbeAtSec >= periodSec;
    }

    /**
     * Records a completed probe and derives the next interval from it and the
     * one before it: the drift rate is the phase they differ by over the time
     * they are apart, and the interval is the budget divided by that rate,
     * clamped.  The baseline probe has nothing to pair with and leaves the
     * initial interval standing, as do two probes that arrived at the same
     * instant - a rate needs a gap to be measured over.
     *
     * @param atSec     when the probe completed, on the caller's time base
     * @param phaseDeg  the probe's phase residual at the fundamental, degrees
     */
    public void probeCompleted(double atSec, double phaseDeg) {
        notBeforeSec = Double.NaN;   // a probe that measured re-establishes the cadence
        if (!Double.isNaN(lastProbeAtSec)) {
            double elapsedSec = atSec - lastProbeAtSec;
            if (elapsedSec > 0.0) {
                double driftDegPerSec = Math.abs(phaseDeg - lastPhaseDeg) / elapsedSec;
                periodSec = clampPeriod(PROBE_PHASE_BUDGET_DEG / driftDegPerSec);
            }
        }
        lastProbeAtSec = atSec;
        lastPhaseDeg   = phaseDeg;
    }

    /**
     * Records a probe that could not measure - it timed out, or its engine
     * refused to start.  Such a probe enters NO drift history: it carries no
     * phase, so the rate the interval is derived from is still the one the
     * last two measuring probes gave, and the interval in force is untouched.
     * The only thing it moves is the next opportunity, to
     * {@link #PROBE_PERIOD_MIN_SEC} from now - far enough that a failing probe
     * cannot be retried on every analysis cycle, near enough that the first
     * one to succeed again is not held back.
     *
     * @param atSec when the probe was abandoned, on the caller's time base
     */
    public void probeFailed(double atSec) {
        notBeforeSec = atSec + PROBE_PERIOD_MIN_SEC;
    }

    /** Forgets every probe: the next {@link #isDue(double)} is a baseline probe
     *  again and the interval is back to its initial value.  What a collection
     *  start does, since a drift rate measured across a stopped collection says
     *  nothing about the one beginning now. */
    public void reset() {
        lastProbeAtSec = Double.NaN;
        notBeforeSec   = Double.NaN;
        lastPhaseDeg   = 0.0;
        periodSec      = PROBE_PERIOD_INITIAL_SEC;
    }

    /** The derived interval held inside its bounds.  An infinite interval (a
     *  drift rate that measured zero) lands on the ceiling. */
    private double clampPeriod(double seconds) {
        return Math.min(PROBE_PERIOD_MAX_SEC, Math.max(PROBE_PERIOD_MIN_SEC, seconds));
    }
}
