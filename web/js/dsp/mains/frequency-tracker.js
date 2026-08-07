/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.dsp.MainsFrequencyTracker.
//
// Live mains-fundamental estimator shared by every mains-rejection filter.
// Scans the 50 Hz and 60 Hz bands (each with its 2nd harmonic) of a reference
// block, discriminates 50 vs 60 by total band energy, locks to the stronger
// source and refines to a few mHz by parabolic interpolation on log-power. The
// lock is exponentially smoothed and outlier detections are rejected; a window
// with no detected line holds the existing lock rather than dropping it.

/** Lowest fundamental the trackers will accept (Hz). */
export const MIN_MAINS_HZ = 45.0;
/** Highest fundamental the trackers will accept (Hz). */
export const MAX_MAINS_HZ = 65.0;

/** Half-width (Hz) of each detection band scanned around 50 / 60. */
const DETECT_SPAN_HZ = 2.0;
/** Coarse scan step (Hz) inside each detection band. */
const DETECT_STEP_HZ = 0.2;
/** A mains line is accepted only when its Goertzel power beats the
 *  scanned-baseline median by this factor (≈ 6 dB). */
const LOCK_RATIO = 4.0;
/** EWMA weight on the existing lock (vs. a fresh detection). High, because
 *  mains is very stable (50/60 Hz ± a few mHz over seconds). */
const LOCK_SMOOTH = 0.97;
/** A detection farther than this from the current lock is an outlier. */
const MAX_LOCK_DRIFT_HZ = 0.5;

export class MainsFrequencyTracker {
  /**
   * @param {number} sampleRate  capture sample rate (Hz), > 0
   */
  constructor(sampleRate) {
    if (sampleRate <= 0) throw new Error('sampleRate must be > 0');
    this._sampleRate = sampleRate;
    /** Exponentially-smoothed lock frequency, or NaN until first lock. */
    this._lockHz = NaN;
    this._windowScratch = new Float64Array(0);
  }

  /** Current smoothed lock frequency (Hz), or NaN. */
  getLockHz() {
    return this._lockHz;
  }

  /** Drops the lock so the next track() re-detects from scratch and snaps
   *  immediately (no EWMA inertia from the old lock). */
  resetTracking() {
    this._lockHz = NaN;
  }

  /**
   * Estimates the mains fundamental from ref, returning the smoothed lock (Hz)
   * - or the held lock (possibly NaN) when no confident line is found this
   * window. Accepts Float32Array, Float64Array or number[].
   * @param {Float32Array|Float64Array|number[]} ref
   * @param {number} len
   * @returns {number}  lock frequency (Hz) or NaN
   */
  track(ref, len) {
    if (ref == null) return this._lockHz;
    len = Math.min(len, ref.length);
    // Need a few mains cycles for a meaningful estimate.
    if (len < ((4 * this._sampleRate / MIN_MAINS_HZ) | 0)) return this._lockHz;

    if (this._windowScratch.length < len) this._windowScratch = new Float64Array(len);
    const w = this._windowScratch;
    // Hann window to suppress leakage from the (usually dominant) test signal
    // into the mains bands.
    const norm = 2.0 * Math.PI / (len - 1);
    for (let i = 0; i < len; i++) {
      w[i] = ref[i] * 0.5 * (1.0 - Math.cos(norm * i));
    }

    // Score 50 vs 60 by fundamental AND 2nd harmonic (50->50/100, 60->60/120);
    // H2 bands use twice the span (the harmonic drifts 2× as far in Hz).
    const h1a = this._scanBand(w, len, 50.0, DETECT_SPAN_HZ);
    const h2a = this._scanBand(w, len, 100.0, 2 * DETECT_SPAN_HZ);
    const h1b = this._scanBand(w, len, 60.0, DETECT_SPAN_HZ);
    const h2b = this._scanBand(w, len, 120.0, 2 * DETECT_SPAN_HZ);

    const is50 = (h1a.peakPower + h2a.peakPower) >= (h1b.peakPower + h2b.peakPower);
    const h1 = is50 ? h1a : h1b;
    const h2 = is50 ? h2a : h2b;

    // Lock when EITHER harmonic is a genuine line; on a miss, hold the lock.
    const baseline = this._scannedBaseline(h1a, h1b, h2a, h2b);
    if (baseline <= 0 || Math.max(h1.peakPower, h2.peakPower) < LOCK_RATIO * baseline) {
      return this._lockHz;
    }

    // Derive f0 from the cleaner harmonic (H2/2 also halves the Hz error).
    const f0 = (h2.peakPower > h1.peakPower) ? h2.refinedHz / 2.0 : h1.refinedHz;
    if (Number.isNaN(this._lockHz)) {
      this._lockHz = f0;
    } else if (Math.abs(f0 - this._lockHz) <= MAX_LOCK_DRIFT_HZ) {
      this._lockHz = LOCK_SMOOTH * this._lockHz + (1.0 - LOCK_SMOOTH) * f0;
    }
    return this._lockHz;
  }

  // ─── Detection helpers ──────────────────────────────────────────────────

  /** Scans ±spanHz around centerHz; the peak is refined to sub-step by
   *  parabolic interpolation on log-power. Returns
   *  { refinedHz, peakPower, loHz, mags }. */
  _scanBand(w, len, centerHz, spanHz) {
    const lo = centerHz - spanHz;
    const m = Math.round(2 * spanHz / DETECT_STEP_HZ) + 1;
    const mag = new Float64Array(m);
    let bestK = 0;
    for (let k = 0; k < m; k++) {
      mag[k] = this._goertzelPower(w, len, lo + k * DETECT_STEP_HZ);
      if (mag[k] > mag[bestK]) bestK = k;
    }
    let refinedHz = lo + bestK * DETECT_STEP_HZ;
    if (bestK > 0 && bestK < m - 1) {
      const a = Math.log(mag[bestK - 1] + 1e-30);
      const b = Math.log(mag[bestK] + 1e-30);
      const c = Math.log(mag[bestK + 1] + 1e-30);
      const denom = a - 2 * b + c;
      let delta = (Math.abs(denom) < 1e-15) ? 0.0 : 0.5 * (a - c) / denom;
      if (delta < -0.5) delta = -0.5;
      if (delta > 0.5) delta = 0.5;
      refinedHz = lo + (bestK + delta) * DETECT_STEP_HZ;
    }
    return { refinedHz, peakPower: mag[bestK], loHz: lo, mags: mag };
  }

  /** Median Goertzel power across the four detection bands - a robust floor the
   *  strongest line must clear to count as real mains. */
  _scannedBaseline(h1a, h1b, h2a, h2b) {
    const m = Math.round(2 * DETECT_SPAN_HZ / DETECT_STEP_HZ) + 1;
    const all = new Float64Array(4 * m);
    let idx = 0;
    idx = this._copyBandPowers(all, idx, h1a, 50.0, m);
    idx = this._copyBandPowers(all, idx, h1b, 60.0, m);
    idx = this._copyBandPowers(all, idx, h2a, 100.0, m);
    idx = this._copyBandPowers(all, idx, h2b, 120.0, m);
    if (idx === 0) return 0.0;
    const c = all.slice(0, idx);
    c.sort();
    return c[(idx / 2) | 0];
  }

  /** Copies the m powers spanning ±DETECT_SPAN_HZ around centerHz from a scan's
   *  grid into out; returns the advanced fill index. */
  _copyBandPowers(out, idx, scan, centerHz, m) {
    const lo = centerHz - DETECT_SPAN_HZ;
    const start = Math.round((lo - scan.loHz) / DETECT_STEP_HZ);
    for (let k = 0; k < m; k++) {
      const src = start + k;
      if (src >= 0 && src < scan.mags.length) {
        out[idx++] = scan.mags[src];
      }
    }
    return idx;
  }

  /** Goertzel single-frequency power |X(f)|² over a windowed block. */
  _goertzelPower(w, len, freqHz) {
    const omega = 2.0 * Math.PI * freqHz / this._sampleRate;
    const coeff = 2.0 * Math.cos(omega);
    let s1 = 0.0, s2 = 0.0;
    for (let i = 0; i < len; i++) {
      const s = w[i] + coeff * s1 - s2;
      s2 = s1;
      s1 = s;
    }
    return s1 * s1 + s2 * s2 - coeff * s1 * s2;
  }
}
