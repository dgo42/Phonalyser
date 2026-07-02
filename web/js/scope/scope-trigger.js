/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.gui.scope.ScopeTrigger.
//
// Trigger-edge detection for the oscilloscope. find() walks the captured buffer
// with a hysteresis-banded Schmitt trigger and returns the rightmost qualified
// crossing's fractional sample index, sub-sample-refined via either linear
// interpolation or band-limited sinc reconstruction (Lanczos.lanczos at scale 1).

import { lanczos } from '../dsp/lanczos.js';

/**
 * Linear interpolation of the `level`-crossing between samples at indices
 * `prevIdx` and `prevIdx + 1`.
 * @param {number} prev     sample value at prevIdx
 * @param {number} curr     sample value at prevIdx + 1
 * @param {number} prevIdx  index of the earlier sample
 * @param {number} level    crossing level
 * @returns {number} fractional crossing index
 */
export function linear(prev, curr, prevIdx, level) {
  const denom = curr - prev;
  if (denom === 0) return prevIdx;
  return prevIdx + (level - prev) / denom;
}

/**
 * Bisects the sinc-interpolated signal between `a` and `b` to find the precise
 * crossing of `level`. 10 iterations give sub-millisample precision
 * (2⁻¹⁰ ≈ 0.001 sample). Uses the unit-scale Lanczos kernel — i.e. the
 * band-limited reconstruction at the input sample rate.
 * @param {Float32Array|Float64Array|number[]} data
 * @param {number} n       valid length of `data`
 * @param {number} a       left bracket (fractional index)
 * @param {number} b       right bracket (fractional index)
 * @param {number} level   crossing level
 * @param {boolean} rising true → rising edge
 * @returns {number} refined fractional crossing index
 */
export function refine(data, n, a, b, level, rising) {
  for (let iter = 0; iter < 10; iter++) {
    const m = 0.5 * (a + b);
    const val = lanczos(data, n, m, 1.0);
    const atRightSide = rising ? (val >= level) : (val <= level);
    if (atRightSide) b = m;
    else             a = m;
  }
  return 0.5 * (a + b);
}

/**
 * Walks `data[from .. to)` with a hysteresis-banded Schmitt trigger and returns
 * the rightmost qualified crossing's fractional sample index, or -1.0 if none.
 *
 * With `hysteresis === 0` the two thresholds collapse onto `level`, recovering
 * single-sample-bracket behaviour.
 *
 * `minSpacingSamples` suppresses qualified crossings spaced closer than that
 * many samples apart — the next accepted trigger must lie at least that far
 * after the previously accepted one. Used in DUAL_TONE mode to lock onto the
 * slow |F1-F2| beat envelope (one trigger per beat cycle). With
 * `minSpacingSamples <= 0` this collapses onto the no-holdoff behaviour.
 *
 * @param {Float32Array|Float64Array|number[]} data sample buffer
 * @param {number} n       valid length of `data`
 * @param {number} from    inclusive search start (≥ 1; data[from-1] is read)
 * @param {number} to      exclusive search end
 * @param {number} level   trigger level
 * @param {boolean} rising true → trigger on rising edge
 * @param {boolean} sincRefine when true, the committed winner is sinc-bisected
 *                  ONCE for sub-sample accuracy; when false, its linear estimate
 *                  is returned. Either way the per-crossing gate uses the cheap
 *                  linear estimate, so refine() runs at most once per call.
 * @param {number} hysteresis Schmitt dead-band half-width
 * @param {number} [minSpacingSamples=0] minimum spacing between accepted triggers
 * @returns {number} fractional trigger index, or -1.0 if none found
 */
export function find(data, n, from, to, level, rising, sincRefine,
                     hysteresis, minSpacingSamples = 0.0) {
  const lo = level - hysteresis;
  const hi = level + hysteresis;
  // Determine the incoming Schmitt state by walking back from `from` until we
  // find a sample firmly outside the dead-band. Falling back to the
  // opposite-of-fire-direction lets a clean signal that starts inside the
  // dead-band still produce a first trigger.
  let state = 0;
  for (let j = from - 1; j >= 0; j--) {
    if (data[j] <= lo) { state = -1; break; }
    if (data[j] >= hi) { state = +1; break; }
  }
  if (state === 0) state = rising ? -1 : +1;

  // Cheap linear estimate + left index of the COMMITTED crossing. The costly
  // sinc refine() runs ONCE at the end, on that single winner — not per level
  // crossing, not per cycle. A periodic signal above hysteresis confirms a
  // trigger every cycle (hundreds over the ~1 s search window) and a noisy
  // sub-hysteresis signal crosses the level on every wiggle; refining each was
  // the per-frame cost that throttled AUTO rendering. Only the last trigger is
  // returned, so only it needs sub-sample precision; the linear estimates drive
  // the minimum-spacing gate.
  let lastTrigger = -1.0;
  let lastTriggerIdx = -1;
  let pendingCrossing = -1.0;
  let pendingPrevIdx = -1;
  let prev = data[from - 1];
  for (let i = from; i < to; i++) {
    const curr = data[i];
    if (rising) {
      if (prev < level && curr >= level) {
        pendingCrossing = linear(prev, curr, i - 1, level);
        pendingPrevIdx = i - 1;
      }
      if (curr <= lo) {
        state = -1;
      } else if (curr >= hi) {
        if (state === -1 && pendingCrossing >= 0
            && (lastTrigger < 0
                || pendingCrossing - lastTrigger >= minSpacingSamples)) {
          lastTrigger = pendingCrossing;
          lastTriggerIdx = pendingPrevIdx;
        }
        state = +1;
        pendingCrossing = -1.0;
      }
    } else {
      if (prev > level && curr <= level) {
        pendingCrossing = linear(prev, curr, i - 1, level);
        pendingPrevIdx = i - 1;
      }
      if (curr >= hi) {
        state = +1;
      } else if (curr <= lo) {
        if (state === +1 && pendingCrossing >= 0
            && (lastTrigger < 0
                || pendingCrossing - lastTrigger >= minSpacingSamples)) {
          lastTrigger = pendingCrossing;
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
