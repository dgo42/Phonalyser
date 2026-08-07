/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.generator.SignalGenerator#renderLogSweep
// and #sweepEnvelope, plus org.edgo.audio.measure.dsp.FreqRespCalHelper#sweepFadeSamples
// (SWEEP_FADE_FRACTION_PER_SIDE).
//
// Renders the unit-amplitude (peak 1.0) Farina exponential sine sweep that the
// desktop DAC plays and that the deconvolution uses as its reference X(t):
//
//     x(t) = sin( K · (exp(t/L) − 1) ),   L = T / ln(f1/f0),   K = 2π·f0·L
//
// with t = n/fs and T = sweepSamples/fs. Phase starts at zero (sample 0 is
// exactly 0) so the sweep concatenates with leading silence without a
// discontinuity. The Tukey envelope is a per-side raised-cosine (Hann) fade -
// the same cosine taper the SignalGenerator applies during playback and that
// computeFromLogSweep applies to the reference - suppressing start/stop
// spectral leakage.

/** Per-side Hann fade duration as a fraction of the sweep length (a Tukey
 *  window with α = 2 × this). Matches FreqRespCalHelper.SWEEP_FADE_FRACTION_PER_SIDE. */
export const SWEEP_FADE_FRACTION_PER_SIDE = 0.05;

/**
 * Renders a unit-amplitude (peak 1.0) Farina exponential sine sweep.
 *
 * @param {number} f0           start frequency (Hz, instantaneous frequency at sample 0)
 * @param {number} f1           end frequency (Hz, instantaneous frequency at the last sample)
 * @param {number} sweepSamples sweep length in samples
 * @param {number} sampleRate   sample rate (Hz)
 * @returns {Float64Array} the rendered sweep, length sweepSamples
 */
export function renderLogSweep(f0, f1, sweepSamples, sampleRate) {
  const T = sweepSamples / sampleRate;
  const L = T / Math.log(f1 / f0);
  const K = 2.0 * Math.PI * f0 * L;
  const buf = new Float64Array(sweepSamples);
  for (let n = 0; n < sweepSamples; n++) {
    const t = n / sampleRate;
    buf[n] = Math.sin(K * (Math.exp(t / L) - 1.0));
  }
  return buf;
}

/**
 * Per-side Hann fade length in samples for a sweep of {@code sweepSamples}.
 * Mirrors FreqRespCalHelper#sweepFadeSamples - the same value must be used for
 * the played sweep envelope and for the deconvolution reference window, or the
 * leakage ripple the window suppresses reappears.
 *
 * @param {number} sweepSamples sweep length in samples
 * @returns {number} per-side fade length in samples (truncated toward zero)
 */
export function sweepFadeSamples(sweepSamples) {
  return Math.trunc(sweepSamples * SWEEP_FADE_FRACTION_PER_SIDE);
}

/**
 * Raised-cosine (Hann) envelope value at sample {@code idx} of a cycle of
 * length {@code cycleLength}, with {@code fadeIn}/{@code fadeOut} sample-length
 * tapers - a Tukey window. Faithful port of SignalGenerator#sweepEnvelope:
 * returns 1.0 in the steady-state middle and when both fade lengths are 0.
 *
 * @param {number} idx         sample index within the cycle
 * @param {number} cycleLength total cycle length in samples
 * @param {number} fadeIn      fade-in length in samples
 * @param {number} fadeOut     fade-out length in samples
 * @returns {number} envelope multiplier in [0, 1]
 */
export function sweepEnvelope(idx, cycleLength, fadeIn, fadeOut) {
  if (fadeIn > 0 && idx < fadeIn) {
    return 0.5 * (1.0 - Math.cos((Math.PI * idx) / fadeIn));
  }
  if (fadeOut > 0 && idx >= cycleLength - fadeOut) {
    let back = cycleLength - 1 - idx;
    if (back < 0) back = 0;
    return 0.5 * (1.0 - Math.cos((Math.PI * back) / fadeOut));
  }
  return 1.0;
}

/**
 * Renders the Farina sweep and applies a symmetric per-side Hann (Tukey) fade
 * of {@code fadeSamples} samples in-place, matching the windowed waveform the
 * desktop SignalGenerator sends to the DAC. Convenience wrapper over
 * {@link renderLogSweep} + {@link sweepEnvelope}.
 *
 * @param {number} f0           start frequency (Hz)
 * @param {number} f1           end frequency (Hz)
 * @param {number} sweepSamples sweep length in samples
 * @param {number} sampleRate   sample rate (Hz)
 * @param {number} fadeSamples  per-side Hann fade length in samples (clamped to sweepSamples/2)
 * @returns {Float64Array} the windowed sweep, length sweepSamples
 */
export function renderWindowedLogSweep(f0, f1, sweepSamples, sampleRate, fadeSamples) {
  const buf = renderLogSweep(f0, f1, sweepSamples, sampleRate);
  const fS = Math.max(0, Math.min(fadeSamples, Math.trunc(sweepSamples / 2)));
  if (fS > 0) {
    for (let i = 0; i < sweepSamples; i++) {
      buf[i] *= sweepEnvelope(i, sweepSamples, fS, fS);
    }
  }
  return buf;
}
