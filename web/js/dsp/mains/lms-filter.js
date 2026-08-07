/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.dsp.MainsLmsFilter.
//
// Mains-hum rejection by an adaptive LMS line canceller: it models the hum as
// a sum of sinusoids at the mains fundamental and its harmonics, adapts their
// amplitudes and phases to the measured signal, and subtracts the estimate.
// Each harmonic k has a quadrature reference pair (cos kφ, sin kφ) and two
// adaptive weights; a least-mean-squares update steers the weights so the
// estimate converges to whatever sinusoid sits at each harmonic while
// everything off those exact frequencies passes through untouched. Because
// the model is purely sinusoidal (no DC term), the DC / operating point is
// preserved either way. The mains phase is a continuous accumulator advancing
// by f₀/fs per sample; process calls advance it by the absStart delta so the
// references stay aligned across snapshots / overlapping windows.

import { MainsFrequencyTracker, MIN_MAINS_HZ } from './frequency-tracker.js';

// Top of the modelled hum band (Hz); harmonics above this are not cancelled.
const MAX_HARMONIC_HZ = 1000.0;
// Hard cap on harmonic count (sizes the weight arrays).
const MAX_HARMONICS = Math.ceil(MAX_HARMONIC_HZ / MIN_MAINS_HZ);
// LMS step. The canceller's notch BANDWIDTH at each harmonic is proportional
// to this, so it is kept small to remove the hum LINE without gouging a wide
// notch in the surrounding floor.
const MU = 5.0e-4;

const TWO_PI = 2.0 * Math.PI;

export class MainsLmsFilter {
  /**
   * @param {number} sampleRate  capture sample rate (Hz), > 0
   */
  constructor(sampleRate) {
    if (sampleRate <= 0) throw new Error('sampleRate must be > 0');
    this._sampleRate = sampleRate;
    this._tracker = new MainsFrequencyTracker(sampleRate);
    this._weightCos = new Float64Array(MAX_HARMONICS + 1);   // 1-based by harmonic
    this._weightSin = new Float64Array(MAX_HARMONICS + 1);
    this._mainsHz = NaN;      // tuned fundamental, NaN before the first lock
    this._phase = 0.0;        // window-start mains phase in radians, [0,2π)
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
   * Filters data in place over len samples. No-op until tuned. absStart is
   * the absolute index of data[0] in the continuous capture stream - the
   * mains phase advances by the DELTA from the previous call's absStart.
   * @param {Float32Array|Float64Array} data
   * @param {number} len
   * @param {number} [absStart=0]
   */
  process(data, len, absStart = 0) {
    this._filter(data, len, absStart);
  }

  /**
   * Same as process(): the sinusoidal model carries no DC term, so cancelling
   * never touches the operating point - both entry points share one path.
   * @param {Float32Array|Float64Array} data
   * @param {number} len
   * @param {number} [absStart=0]
   */
  processPreservingDc(data, len, absStart = 0) {
    this._filter(data, len, absStart);
  }

  /** Clears the adapted weights + phase state (e.g. on a capture restart)
   *  without changing the current tuning. */
  reset() {
    this._weightCos.fill(0.0);
    this._weightSin.fill(0.0);
    this._phase = 0.0;
    this._started = false;
  }

  /** Drops the frequency lock and the adapted weights so the next track()
   *  re-detects from scratch. */
  resetTracking() {
    this._tracker.resetTracking();
    this._mainsHz = NaN;
    this.reset();
  }

  /** Shared filter core (Java filter/filterD collapse to one - typed-array
   *  stores round to the element type automatically). */
  _filter(data, len, absStart) {
    if (!this.isTuned()) return;
    len = Math.min(len, data.length);
    const dPhase = TWO_PI * this._mainsHz / this._sampleRate;
    // Advance the window-start phase by the gap since the previous call so
    // the references stay aligned across non-contiguous / overlapping blocks.
    if (this._started) {
      this._phase += (absStart - this._prevAbsStart) * dPhase;
      this._phase %= TWO_PI;
      if (this._phase < 0) this._phase += TWO_PI;
    } else {
      this._started = true;
    }
    this._prevAbsStart = absStart;
    // Cancel every harmonic whose frequency is still inside the hum band.
    const kMax = Math.max(1, Math.min(MAX_HARMONICS, Math.floor(MAX_HARMONIC_HZ / this._mainsHz)));
    const wc = this._weightCos, ws = this._weightSin;
    let ph = this._phase;
    for (let i = 0; i < len; i++) {
      const c1 = Math.cos(ph), s1 = Math.sin(ph);
      // Estimate the hum and accumulate the per-harmonic references by
      // incremental rotation: (c_k, s_k) = rotate (c_{k-1}, s_{k-1}) by φ.
      let ck = c1, sk = s1;
      let est = 0.0;
      for (let k = 1; k <= kMax; k++) {
        est += wc[k] * ck + ws[k] * sk;
        if (k < kMax) {
          const cn = ck * c1 - sk * s1;
          sk = sk * c1 + ck * s1;
          ck = cn;
        }
      }
      const e = data[i] - est;
      data[i] = e;

      // LMS weight update with the same references (rebuild by rotation).
      const step = MU * e;
      ck = c1; sk = s1;
      for (let k = 1; k <= kMax; k++) {
        wc[k] += step * ck;
        ws[k] += step * sk;
        if (k < kMax) {
          const cn = ck * c1 - sk * s1;
          sk = sk * c1 + ck * s1;
          ck = cn;
        }
      }
      ph += dPhase;
      if (ph >= TWO_PI) ph -= TWO_PI;
    }
    // this._phase holds the WINDOW-START phase; overlapping windows re-use
    // it, so it is advanced only by the inter-call delta above.
  }
}
