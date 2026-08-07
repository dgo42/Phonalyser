/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.dsp.MainsSyncSubtractFilter.
//
// Mains-hum rejection by synchronous subtraction: it learns the recurring hum
// waveform over one mains period and subtracts it. One mains period contains
// EVERY harmonic at once, so a single period-locked template removes 50/60 Hz
// and all its harmonics together - unlike a comb, it carves no per-harmonic
// notch and gouges no spectrum. Any component that is NOT periodic at the
// mains period (the test tone, broadband noise) does not accumulate in the
// template and passes through untouched.
//
// The template holds the hum sampled at TEMPLATE_BINS phase points across one
// mains period. Each input sample is mapped to its mains phase (a continuous
// accumulator advancing by f₀/fs per sample), the interpolated template value
// is subtracted, and the template is nudged toward the input by a small LMS
// step MU whose fixed point is the mean of the input at that phase - i.e. the
// periodic hum. Successive process calls advance the window-start phase by
// the absStart delta so the template stays aligned across non-contiguous
// snapshots (scope) and overlapping windows (FFT) alike.

import { MainsFrequencyTracker } from './frequency-tracker.js';

// Phase points across one mains period. 512 gives an effective template rate
// of 512·f₀ (≈ 25.6 kHz at 50 Hz), so any residual image of an in-band test
// tone lands ABOVE the audio band rather than inside it.
const TEMPLATE_BINS = 512;
// LMS step for the template update - deliberately small so the (non-periodic)
// test tone is AVERAGED OUT of the template instead of leaking into it. Mains
// is rock-stable, so slow convergence (~5 s) is fine.
const MU = 0.001;

export class MainsSyncSubtractFilter {
  /**
   * @param {number} sampleRate  capture sample rate (Hz), > 0
   */
  constructor(sampleRate) {
    if (sampleRate <= 0) throw new Error('sampleRate must be > 0');
    this._sampleRate = sampleRate;
    this._tracker = new MainsFrequencyTracker(sampleRate);
    this._template = new Float64Array(TEMPLATE_BINS);
    this._mainsHz = NaN;      // tuned fundamental, NaN before the first lock
    this._phase = 0.0;        // window-start mains phase in periods, [0,1)
    this._prevAbsStart = 0;
    this._started = false;
  }

  /** Current tuned fundamental (Hz), or NaN before the first lock. */
  getMainsHz() {
    return this._mainsHz;
  }

  /** True once the filter has been tuned to a mains frequency. */
  isTuned() {
    return !Number.isNaN(this._mainsHz);
  }

  /**
   * Re-estimates the mains fundamental from ref via the shared
   * MainsFrequencyTracker and retunes; returns the locked frequency (Hz), or
   * NaN when nothing confident is found. Accepts Float32Array, Float64Array
   * or number[].
   * @param {Float32Array|Float64Array|number[]} ref
   * @param {number} len
   * @returns {number}  locked frequency (Hz) or NaN
   */
  track(ref, len) {
    const f0 = this._tracker.track(ref, len);
    if (!Number.isNaN(f0)) this._mainsHz = f0;
    return f0;
  }

  /**
   * Filters data in place over len samples, removing the whole periodic
   * component (hum + any periodic DC). No-op until tuned. absStart is the
   * absolute index of data[0] in the continuous capture stream - the mains
   * phase advances by the DELTA from the previous call's absStart.
   * @param {Float32Array|Float64Array} data
   * @param {number} len
   * @param {number} [absStart=0]
   */
  process(data, len, absStart = 0) {
    this._filter(data, len, absStart, false);
  }

  /**
   * Like process(), but removes only the AC hum and keeps the operating point
   * (the template's DC is left in the signal).
   * @param {Float32Array|Float64Array} data
   * @param {number} len
   * @param {number} [absStart=0]
   */
  processPreservingDc(data, len, absStart = 0) {
    this._filter(data, len, absStart, true);
  }

  /** Clears the learned template + phase state (e.g. on a capture restart)
   *  without changing the current tuning. */
  reset() {
    this._template.fill(0.0);
    this._phase = 0.0;
    this._started = false;
  }

  /** Drops the frequency lock so the next track() re-detects from scratch. */
  resetTracking() {
    this._tracker.resetTracking();
    this._mainsHz = NaN;
  }

  /** Shared filter core (Java filter/filterD collapse to one - typed-array
   *  stores round to the element type automatically). */
  _filter(data, len, absStart, preserveDc) {
    if (!this.isTuned()) return;
    len = Math.min(len, data.length);
    const perSample = this._mainsHz / this._sampleRate;   // periods per sample
    const template = this._template;
    const M = TEMPLATE_BINS;
    // Advance the window-start phase by the gap since the previous call so
    // the template stays aligned across non-contiguous / overlapping blocks.
    if (this._started) {
      this._phase += (absStart - this._prevAbsStart) * perSample;
      this._phase -= Math.floor(this._phase);
    } else {
      this._started = true;
    }
    this._prevAbsStart = absStart;
    // DC of the template = the operating point to keep when preserving DC.
    let dc = 0.0;
    if (preserveDc) {
      for (let b = 0; b < M; b++) dc += template[b];
      dc /= M;
    }
    let p = this._phase;
    for (let i = 0; i < len; i++) {
      const fb = p * M;
      let b0 = Math.trunc(fb);                            // p ∈ [0,1) => fb ∈ [0,M)
      if (b0 >= M) b0 = M - 1;
      const b1 = (b0 + 1) % M;
      const w = fb - b0;
      const est = template[b0] * (1.0 - w) + template[b1] * w;

      const x = data[i];
      const residual = x - est;                           // drives the template
      data[i] = preserveDc ? x - (est - dc) : residual;

      // LMS template update (interpolated tap), fixed point = periodic hum.
      const step = MU * residual;
      template[b0] += step * (1.0 - w);
      template[b1] += step * w;

      p += perSample;
      if (p >= 1.0) p -= 1.0;
    }
    // this._phase holds the WINDOW-START phase; advanced by the inter-call
    // delta above, never by this block's own length (overlapping windows
    // re-use it).
  }
}
