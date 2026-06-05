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

/**
 * Phase-locked tracker for the FFT cross-tick coherent de-rotation frequency.
 *
 * <p>Coherent cross-tick averaging sums each tick's spectrum after de-rotating
 * it back to a common time origin, using a fractional fundamental bin
 * {@code kappa}.  If {@code kappa} is even slightly wrong, the de-rotated
 * fundamental phase drifts linearly with the tick's sample offset, and summing
 * those progressively-misaligned frames smears the fundamental into a sinc and
 * shrinks its peak.  For a stable tone the magnitude is preserved iff
 * {@code kappa} is exact.
 *
 * <p>This tracks {@code kappa} with a <b>measure-then-correct</b> loop, which
 * is unconditionally stable (a naive per-tick PLL is not: the de-rotation uses
 * the {@code kappa} it is adjusting, and the growing sample-offset lever arm
 * makes it diverge).  Over a short window of ticks {@code kappa} is held fixed
 * while the de-rotated fundamental phase is observed; the phase advances
 * linearly with sample offset at a slope {@code 2π·(κ_true − κ)/N}, so a slope
 * fit yields the error <em>directly</em> (independent of the absolute offset)
 * and one full correction nulls it.  When the residual per-tick drift stays
 * tiny for a few windows it declares {@link #isLocked() lock}; the caller then
 * freezes {@code kappa}, re-anchors the accumulator to "now", and accumulates
 * with the exact frequency.
 *
 * <p>Per-tick phase change stays below π (the seed is within &lt;1 bin), so the
 * per-tick wrap is unambiguous.  Pure (no I/O / SWT) and unit-tested.
 */
public final class DerotationPhaseLock {

    private final int    fftSize;
    private final int    window;         // ticks per measurement window
    private final double lockDriftRad;   // per-tick drift below which a window is "stable"
    private final int    lockWindows;    // consecutive stable windows to declare lock
    private final int    maxWindows;     // fallback: lock anyway after this many windows

    private double  kappa;
    private boolean locked;

    // Current window state.
    private boolean hasPrev;
    private double  prevPhase;
    private long    prevDelta;
    private double  sumDPhi;       // Σ of per-tick (unwrapped) phase change
    private long    sumDDelta;     // Σ of per-tick sample-offset change
    private int     count;         // contributing ticks this window
    private int     stableWindows;
    private int     totalWindows;

    /**
     * @param fftSize       FFT length N.
     * @param seedKappa     initial fractional fundamental bin.
     * @param window        ticks per measurement window (≥ 2).
     * @param lockDriftRad  per-tick drift magnitude below which a window counts
     *                      as stable.  Must be TIGHT: a tiny per-tick drift
     *                      integrates to a large smear over hundreds of ticks,
     *                      so e.g. 0.001 rad — NOT 0.02 — is needed to force the
     *                      loop to correct the seed bias before locking.
     * @param lockWindows   consecutive stable windows required to declare lock.
     * @param maxWindows    safety fallback: lock with the best κ after this many
     *                      windows even if the tight threshold isn't met (so a
     *                      noisy/low-SNR signal can't leave it "locking" forever).
     */
    public DerotationPhaseLock(int fftSize, double seedKappa, int window,
                               double lockDriftRad, int lockWindows, int maxWindows) {
        this.fftSize      = fftSize;
        this.kappa        = seedKappa;
        this.window       = Math.max(2, window);
        this.lockDriftRad = lockDriftRad;
        this.lockWindows  = Math.max(1, lockWindows);
        this.maxWindows   = Math.max(lockWindows + 1, maxWindows);
    }

    /**
     * Feeds the de-rotated fundamental phase observed at sample offset
     * {@code delta} (relative to the de-rotation reference).  {@code kappa} is
     * held fixed within a window; at the end of each window it is corrected by
     * the measured phase slope.
     *
     * @return {@code true} once locked (freeze kappa and start accumulating).
     */
    public boolean observe(double phaseObs, long delta) {
        if (locked) return true;
        if (hasPrev) {
            sumDPhi   += wrapToPi(phaseObs - prevPhase);
            sumDDelta += (delta - prevDelta);
            count++;
        }
        prevPhase = phaseObs;
        prevDelta = delta;
        hasPrev   = true;

        if (count >= window && sumDDelta != 0) {
            totalWindows++;
            double slope       = sumDPhi / (double) sumDDelta;     // rad/sample
            double perTickDrift = Math.abs(sumDPhi) / count;       // mean |Δφ| per tick
            if (perTickDrift < lockDriftRad) {
                if (++stableWindows >= lockWindows) locked = true;
            } else {
                stableWindows = 0;
                kappa += slope * fftSize / (2.0 * Math.PI);        // full correction
            }
            // Safety fallback: a noisy signal may never get under the tight
            // drift threshold; accept the best κ so far rather than never lock.
            if (!locked && totalWindows >= maxWindows) locked = true;
            // Start a fresh window measured against the (possibly corrected) kappa.
            sumDPhi = 0;
            sumDDelta = 0;
            count = 0;
            hasPrev = false;
        }
        return locked;
    }

    public double  kappa()    { return kappa; }
    public boolean isLocked() { return locked; }

    /** Wraps an angle to (-π, π]. */
    public static double wrapToPi(double a) {
        double x = (a + Math.PI) % (2.0 * Math.PI);
        if (x < 0) x += 2.0 * Math.PI;
        return x - Math.PI;
    }
}
