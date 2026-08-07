/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful ports of the oscilloscope's per-channel HF-cleanup filters:
//   - org.edgo.audio.measure.dsp.LowPassFilter  (Chebyshev Type I low-pass,
//     cascade of 2nd-order DF2T biquads; pass-through above Nyquist)
//   - org.edgo.audio.measure.dsp.MedianFilter   (sliding-window de-spike)
//
// Both are the time-domain filters ScopeView.applyHfLowPass applies in place to
// a channel's display/trigger window before the trace and trigger search read
// it. LpfMode.HZ_80 -> LowPassFilter(80 kHz); LpfMode.DESPIKE -> MedianFilter.

/** Chebyshev Type I pass-band ripple (dB) - LowPassFilter.RIPPLE_DB. */
const RIPPLE_DB = 0.5;

/**
 * Chebyshev Type I low-pass filter - a cascade of 2nd-order sections, used to
 * strip HF spikes (switching / RF pickup above the audio band) from a captured
 * channel before display / measurement. Inactive (pass-through) when the cutoff
 * is at or above Nyquist - so 80 kHz is a no-op at 48/96 kHz and only does work
 * at the high sample rates where such spikes appear.
 */
export class LowPassFilter {
  /**
   * @param {number} sampleRate capture sample rate (Hz), > 0
   * @param {number} cutoffHz   −3 dB corner (Hz)
   * @param {number} order      filter order; rounded up to the next even number
   */
  constructor(sampleRate, cutoffHz, order) {
    if (sampleRate <= 0) throw new Error('sampleRate must be > 0');
    const even = Math.max(2, order + (order & 1));   // round up to even, ≥ 2
    const sections = even / 2;
    this._b0 = new Float64Array(sections);
    this._b1 = new Float64Array(sections);
    this._b2 = new Float64Array(sections);
    this._a1 = new Float64Array(sections);
    this._a2 = new Float64Array(sections);
    this._z1 = new Float64Array(sections);
    this._z2 = new Float64Array(sections);
    // Inactive (pass-through) when the cutoff can't do anything useful.
    this._active = cutoffHz > 0 && cutoffHz < sampleRate / 2.0;
    if (!this._active) return;

    // Chebyshev Type I prototype: pole spread set by the ripple factor ε.
    const eps = Math.sqrt(Math.pow(10.0, RIPPLE_DB / 10.0) - 1.0);
    const invEps = 1.0 / eps;
    const v0 = Math.log(invEps + Math.sqrt(invEps * invEps + 1.0)) / even;   // asinh(1/ε)/N
    const sinhv = Math.sinh(v0);
    const coshv = Math.cosh(v0);
    const kWarp = Math.tan(Math.PI * cutoffHz / sampleRate);   // pre-warped analog cutoff
    for (let k = 0; k < sections; k++) {
      const theta = Math.PI * (2.0 * k + 1.0) / (2.0 * even);
      const sp = -sinhv * Math.sin(theta) * kWarp;   // real part (negative)
      const op = coshv * Math.cos(theta) * kWarp;    // imag part
      const c = sp * sp + op * op;                   // |p'|²
      const d = -2.0 * sp;                            // > 0
      // Bilinear transform of H(s) = c / (s² + d·s + c); numerator c·(1+z⁻¹)²,
      // unity DC gain per section.
      const a0 = 1.0 + d + c;
      this._b0[k] = c / a0;
      this._b1[k] = 2.0 * c / a0;
      this._b2[k] = c / a0;
      this._a1[k] = 2.0 * (c - 1.0) / a0;
      this._a2[k] = (1.0 - d + c) / a0;
    }
  }

  /** True when the cutoff is below Nyquist and the filter actually processes;
   *  false means process() is a pass-through. */
  isActive() {
    return this._active;
  }

  /** Clears the filter state (call before each block when re-reading overlapping
   *  windows, as the oscilloscope does). */
  reset() {
    this._z1.fill(0.0);
    this._z2.fill(0.0);
  }

  /** Filters {@code data} in place through every section. No-op when inactive. */
  process(data, len) {
    if (!this._active) return;
    len = Math.min(len, data.length);
    const sections = this._b0.length;
    const b0 = this._b0, b1 = this._b1, b2 = this._b2, a1 = this._a1, a2 = this._a2;
    const z1 = this._z1, z2 = this._z2;
    const f32 = data instanceof Float32Array;
    for (let i = 0; i < len; i++) {
      let x = data[i];
      for (let s = 0; s < sections; s++) {
        const y = b0[s] * x + z1[s];
        z1[s] = b1[s] * x - a1[s] * y + z2[s];
        z2[s] = b2[s] * x - a2[s] * y;
        x = y;
      }
      data[i] = f32 ? Math.fround(x) : x;
    }
  }
}

/**
 * Sliding-window median ("de-spike") filter - for each sample outputs the
 * median of the {@code window} samples centred on it, removing impulsive spikes
 * while preserving genuine waveform edges (no ringing). Memoryless across blocks.
 */
export class MedianFilter {
  /** @param {number} window median window size; forced odd and clamped to ≥ 3. */
  constructor(window) {
    let w = Math.max(3, window);
    if ((w & 1) === 0) w++;          // force odd so the median is well-defined
    this._window = w;
    this._in = new Float32Array(0);
    this._sortBuf = new Float32Array(w);
  }

  /** Replaces each sample of {@code data[0..len)} with the median of the window
   *  centred on it, in place. */
  process(data, len) {
    len = Math.min(len, data.length);
    if (len <= 0) return;
    if (this._in.length < len) this._in = new Float32Array(len);
    const inp = this._in;
    for (let i = 0; i < len; i++) inp[i] = data[i];
    const half = this._window >> 1;
    for (let i = 0; i < len; i++) {
      const lo = Math.max(0, i - half);
      const hi = Math.min(len - 1, i + half);
      data[i] = this._median(lo, hi);
    }
  }

  /** Median of {@code in[lo..hi]} via insertion sort into the scratch buffer. */
  _median(lo, hi) {
    const n = hi - lo + 1;
    const sortBuf = this._sortBuf, inp = this._in;
    for (let j = 0; j < n; j++) {
      const v = inp[lo + j];
      let k = j - 1;
      while (k >= 0 && sortBuf[k] > v) { sortBuf[k + 1] = sortBuf[k]; k--; }
      sortBuf[k + 1] = v;
    }
    return sortBuf[n >> 1];
  }
}
