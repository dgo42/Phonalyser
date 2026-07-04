/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.dsp.TimeDiscontinuityDetector.
//
// Time-domain waveform-discontinuity detector, shared by the oscilloscope's
// glitch trigger and the FFT worker's time-domain rejection gate — so both
// instruments agree on what counts as a damaged block (dropped-sample DAC
// gaps, ADC-side cutoffs, phase-jump splices).
//
// Each sample is predicted from the sinusoid recurrence
//   d[i] ≈ a·d[i−1] − d[i−2]   with   a = 2·cos ω
// estimated from the window itself by least squares. The recurrence is EXACT
// for a clean tone at any frequency, so the prediction-error baseline is the
// noise floor — unlike a plain second difference (the a = 2 special case),
// whose baseline is the tone's own curvature A·ω² and which therefore goes
// deaf as the signal frequency rises (at 20 kHz / 384 kHz the curvature
// threshold reaches ~0.5·A, hiding every glitch smaller than a full-peak
// drop). The rare glitch samples cannot bias the least-squares estimate or
// the mean-based threshold. Frequency-domain counterpart:
// js/dsp/spectral-discontinuity-detector.js.
//
// PORT NOTE — overload collapse: the desktop class carries a float[] path
// (scope capture buffers) and a double[] twin of detect() (the FFT worker's
// double-precision window, kept separate purely to avoid a per-tick
// float-conversion copy). JS numbers are IEEE doubles, so both overloads
// collapse into ONE Float64Array path here; the Java float path's float-
// precision intermediate rounding is NOT reproduced (the double path is the
// higher-precision reference, and the 8×-mean threshold sits decades above
// any float-vs-double rounding difference).

/**
 * Threshold = this factor × the window's mean |prediction error|. For a
 * clean tone the error is noise-limited, where 8× ≈ 6.4 σ — broadband
 * noise never fires; a splice breaks the prediction by a large fraction
 * of the amplitude, decades above. Residual harmonics / a second tone
 * raise the baseline (they don't fit a single-tone recurrence), and the
 * threshold self-scales with them.
 */
const THRESHOLD_FACTOR = 8.0;

/**
 * Time-domain waveform-discontinuity detector (pure math, stateless).
 * See the module comment for the detection model.
 */
export class TimeDiscontinuityDetector {

  /** Discontinuity bursts closer than this (seconds) belong to ONE glitch — a
   *  dropout's entry and recovery boundaries (the observed USB gaps run
   *  120–160 µs) merge, so a caller can anchor on the glitch's start or end
   *  as a whole rather than on each boundary separately. */
  static MERGE_SECONDS = 0.001;

  /**
   * Finds the rightmost discontinuity in `data[from .. to)`. Fires on
   * amplitude steps AND slope breaks anywhere on the waveform (peaks, flanks,
   * zero crossings alike) regardless of direction. Consecutive above-threshold
   * samples form a burst; bursts closer than `mergeSamples` form ONE glitch
   * (a dropout = entry burst + body + recovery burst).
   *
   * @param {Float64Array|Float32Array|number[]} data sample buffer
   * @param {number} from  inclusive search start
   * @param {number} to    exclusive search end
   * @param {boolean} anchorStart true → return the last clean sample before
   *        the glitch, false → the first settled sample after it
   * @param {number} mergeSamples bursts closer than this merge into one glitch
   * @param {number} omega the KNOWN fundamental as 2π·f/sampleRate — pins the
   *        recurrence coefficient exactly, so the tone nulls to the noise floor
   *        even when an in-window glitch or harmonics would bias the estimate;
   *        NaN → least-squares self-estimate from the window (external /
   *        unknown signals)
   * @returns {number} that index for the RIGHTMOST glitch, or -1.0 when
   *        nothing qualifies (including flat / silent input)
   */
  findDiscontinuity(data, from, to, anchorStart, mergeSamples, omega) {
    const start = Math.max(from, 2);
    if (to - start < 3) return -1.0;
    let a;
    if (Number.isNaN(omega)) {
      // Least-squares estimate of the recurrence coefficient a = 2·cos ω:
      // minimising Σ (d[i] − a·d[i−1] + d[i−2])² over the window gives
      // a = Σ d[i−1]·(d[i] + d[i−2]) / Σ d[i−1]².  Exact for a clean tone;
      // clamped to the valid sinusoid range (a degenerate window falls back
      // to a = 2, the plain second difference).
      let num = 0;
      let den = 0;
      for (let i = start; i < to; i++) {
        num += data[i - 1] * (data[i] + data[i - 2]);
        den += data[i - 1] * data[i - 1];
      }
      a = (den > 0) ? Math.max(-2.0, Math.min(2.0, num / den)) : 2.0;
    } else {
      a = 2.0 * Math.cos(omega);
    }
    let sumAbs = 0;
    for (let i = start; i < to; i++) {
      sumAbs += Math.abs(data[i] - a * data[i - 1] + data[i - 2]);
    }
    const meanAbs = sumAbs / (to - start);
    if (meanAbs <= 0) return -1.0;
    const threshold = THRESHOLD_FACTOR * meanAbs;
    // Track only the rightmost glitch: a burst either extends it (within the
    // merge window) or starts a new one that replaces it.
    let glitchStart = -1;   // first burst's first error index
    let glitchEnd = -1;     // one past the last burst's last error index
    let burstStart = -1;
    for (let i = start; i <= to; i++) {           // i === to closes a trailing burst
      const above = i < to
          && Math.abs(data[i] - a * data[i - 1] + data[i - 2]) > threshold;
      if (above) {
        if (burstStart < 0) burstStart = i;
        continue;
      }
      if (burstStart >= 0) {
        if (glitchEnd >= 0 && burstStart - glitchEnd <= mergeSamples) {
          glitchEnd = i;                          // same glitch — extend to this burst
        } else {
          glitchStart = burstStart;               // a new (rightmost) glitch
          glitchEnd = i;
        }
        burstStart = -1;
      }
    }
    if (glitchStart < 0) return -1.0;
    // d[glitchStart-1] = last sample still on the old trend; d[glitchEnd-1] =
    // first sample that fits the local prediction again after the glitch.
    return anchorStart ? glitchStart - 1 : glitchEnd - 1;
  }

  /**
   * Rejection-gate convenience: whether `data[0 .. n)` contains any
   * discontinuity. Burst merging is irrelevant for a yes/no verdict; `omega`
   * as in {@link TimeDiscontinuityDetector#findDiscontinuity}. (Single path
   * here — see the PORT NOTE; the Java float[] and double[] detect() twins
   * both map onto this.)
   *
   * @param {Float64Array|Float32Array|number[]} data sample buffer
   * @param {number} n     valid length of `data`
   * @param {number} omega known fundamental as 2π·f/sampleRate, or NaN
   * @returns {boolean} true when a discontinuity is present
   */
  detect(data, n, omega) {
    return this.findDiscontinuity(data, 2, n, true, 0, omega) >= 0;
  }
}
