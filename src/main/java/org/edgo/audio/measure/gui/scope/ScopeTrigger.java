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

package org.edgo.audio.measure.gui.scope;

import org.edgo.audio.measure.common.Lanczos;

import lombok.experimental.UtilityClass;

/**
 * Trigger-edge detection for the oscilloscope.  Pure math; decoupled
 * from {@link ScopeView} so the Schmitt-trigger + sub-sample
 * refinement logic is unit-testable without instantiating an SWT widget.
 *
 * <p>{@link #find} walks the captured buffer with a hysteresis-banded
 * Schmitt trigger and returns the rightmost qualified crossing's
 * fractional sample index, sub-sample-refined via either linear
 * interpolation or band-limited sinc reconstruction (uses {@link
 * Lanczos#lanczos}).
 */
@UtilityClass
public class ScopeTrigger {

    /**
     * Walks {@code data[from .. to)} with a hysteresis-banded Schmitt
     * trigger and returns the rightmost qualified crossing's fractional
     * sample index, or {@code -1.0} if none was found.
     *
     * <p>With {@code hysteresis == 0} the two thresholds collapse onto
     * {@code level}, recovering single-sample-bracket behaviour.
     *
     * @param sincRefine  when {@code true}, bisects the sinc-reconstructed
     *                    signal between the bracketing samples for sub-
     *                    sample accuracy.  When {@code false}, uses linear
     *                    interpolation between the two samples.
     */
    public double find(float[] data, int n, int from, int to,
                       float level, boolean rising, boolean sincRefine,
                       float hysteresis) {
        return find(data, n, from, to, level, rising, sincRefine, hysteresis, 0.0);
    }

    /**
     * Variant of {@link #find} that suppresses qualified crossings spaced
     * closer than {@code minSpacingSamples} apart — the next accepted
     * trigger must lie at least that many samples after the previously
     * accepted one.  Used by the scope in DUAL_TONE mode to lock the
     * display onto the slow {@code |F1-F2|} beat envelope: the carrier
     * crosses the trigger level many times per beat, so a {@code
     * minSpacingSamples = sampleRate / |F1-F2|} keeps only one trigger
     * per beat cycle.
     *
     * <p>With {@code minSpacingSamples <= 0} this collapses onto the
     * no-holdoff behaviour of {@link #find(float[], int, int, int, float,
     * boolean, boolean, float)}.
     */
    public double find(float[] data, int n, int from, int to,
                       float level, boolean rising, boolean sincRefine,
                       float hysteresis, double minSpacingSamples) {
        float lo = level - hysteresis;
        float hi = level + hysteresis;
        // Determine the incoming Schmitt state by walking back from `from`
        // until we find a sample firmly outside the dead-band.  Falling back
        // to the opposite-of-fire-direction lets a clean signal that starts
        // inside the dead-band still produce a first trigger.
        int state = 0;
        for (int j = from - 1; j >= 0; j--) {
            if (data[j] <= lo) { state = -1; break; }
            if (data[j] >= hi) { state = +1; break; }
        }
        if (state == 0) state = rising ? -1 : +1;

        // Cheap linear estimate + left index of the COMMITTED crossing.  The costly
        // sinc refine() runs ONCE at the end, on that single winner — not per level
        // crossing, not per cycle.  A periodic signal above hysteresis confirms a
        // trigger every cycle (hundreds over the ~1 s search window) and a noisy
        // sub-hysteresis signal crosses the level on every wiggle; refining each was
        // the per-frame cost that throttled AUTO rendering and made the trace feel
        // frozen.  Only the last trigger is returned, so only it needs sub-sample
        // precision; the linear estimates drive the minimum-spacing gate.
        double lastTrigger    = -1.0;
        int    lastTriggerIdx = -1;
        double pendingCrossing = -1.0;
        int    pendingPrevIdx  = -1;
        float prev = data[from - 1];
        for (int i = from; i < to; i++) {
            float curr = data[i];
            if (rising) {
                if (prev < level && curr >= level) {
                    pendingCrossing = linear(prev, curr, i - 1, level);
                    pendingPrevIdx  = i - 1;
                }
                if (curr <= lo) {
                    state = -1;
                } else if (curr >= hi) {
                    if (state == -1 && pendingCrossing >= 0
                            && (lastTrigger < 0
                                || pendingCrossing - lastTrigger >= minSpacingSamples)) {
                        lastTrigger    = pendingCrossing;
                        lastTriggerIdx = pendingPrevIdx;
                    }
                    state = +1;
                    pendingCrossing = -1.0;
                }
            } else {
                if (prev > level && curr <= level) {
                    pendingCrossing = linear(prev, curr, i - 1, level);
                    pendingPrevIdx  = i - 1;
                }
                if (curr >= hi) {
                    state = +1;
                } else if (curr <= lo) {
                    if (state == +1 && pendingCrossing >= 0
                            && (lastTrigger < 0
                                || pendingCrossing - lastTrigger >= minSpacingSamples)) {
                        lastTrigger    = pendingCrossing;
                        lastTriggerIdx = pendingPrevIdx;
                    }
                    state = -1;
                    pendingCrossing = -1.0;
                }
            }
            prev = curr;
        }
        if (lastTriggerIdx < 0 || !sincRefine) return lastTrigger;
        return refine(data, n, lastTriggerIdx, lastTriggerIdx + 1, level, rising);
    }

    /**
     * Linear interpolation of the {@code level}-crossing between samples
     * at indices {@code prevIdx} and {@code prevIdx + 1}.
     */
    public double linear(float prev, float curr, int prevIdx, float level) {
        float denom = curr - prev;
        if (denom == 0) return prevIdx;
        return prevIdx + (level - prev) / denom;
    }

    /**
     * Bisects the sinc-interpolated signal between {@code a} and {@code b}
     * to find the precise crossing of {@code level}.  10 iterations give
     * sub-millisample precision (2⁻¹⁰ ≈ 0.001 sample).  Uses the unit-
     * scale Lanczos kernel — i.e. the band-limited reconstruction at the
     * input sample rate.
     */
    public double refine(float[] data, int n, double a, double b,
                         float level, boolean rising) {
        for (int iter = 0; iter < 10; iter++) {
            double m = 0.5 * (a + b);
            double val = Lanczos.lanczos(data, n, m, 1.0);
            boolean atRightSide = rising ? (val >= level) : (val <= level);
            if (atRightSide) b = m;
            else             a = m;
        }
        return 0.5 * (a + b);
    }
}
