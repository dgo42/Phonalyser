/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.dsp.MainsCombFilter.
//
// Mains-hum rejection via a frequency-tracked IIR comb:
//   H(z) = (1 − z^−N) / (1 − α·z^−N),   N = sampleRate / f₀
// placing a notch at DC and at every harmonic of f₀ up to Nyquist. α (= ρ^N,
// ρ = exp(−π·BW/fs)) sets the −3 dB notch width. N is generally fractional, so
// the z^−N delays use linear interpolation between the two straddling integer
// taps. The comb can either filter the time-domain input in place, or apply its
// peak-normalized response as a per-bin divide on an already-computed magnitude
// spectrum (the frequency-domain correction).

import { MainsFrequencyTracker, MIN_MAINS_HZ, MAX_MAINS_HZ } from './frequency-tracker.js';

/** Default −3 dB notch bandwidth (Hz) for the mains comb — the value used by
 *  the FFT / scope combs throughout the app. */
export const DEFAULT_NOTCH_BANDWIDTH_HZ = 2.5;

/** Plot floor (dB): a notch reads as a clean gap to this depth, not −∞. */
const CORR_FLOOR_DB = -120.0;
/** Only harmonics below this are notched (real hum band; protects tones). */
const CORR_MAX_HZ = 2500.0;
/** Rebuild the cached response only once f0 drifts past this. */
const CORR_F0_REBUILD_HZ = 0.005;

export class MainsCombFilter {
  /**
   * @param {number} sampleRate        capture sample rate (Hz), > 0
   * @param {number} notchBandwidthHz  −3 dB width of each harmonic notch (Hz), > 0
   */
  constructor(sampleRate, notchBandwidthHz) {
    if (sampleRate <= 0) throw new Error('sampleRate must be > 0');
    if (!(notchBandwidthHz > 0)) throw new Error('bandwidth must be > 0');
    this._sampleRate = sampleRate;
    this._tracker = new MainsFrequencyTracker(sampleRate);
    // Per-sample-delay pole radius ρ = exp(−π·BW/fs); α = ρ^N.
    this._rho = Math.exp(-Math.PI * notchBandwidthHz / sampleRate);
    // Largest N is at the lowest tunable mains frequency.
    const nMax = Math.ceil(sampleRate / MIN_MAINS_HZ);
    this._bufLen = nMax + 4;
    this._xBuf = new Float64Array(this._bufLen);
    this._yBuf = new Float64Array(this._bufLen);
    this._pos = 0;

    this._mainsHz = NaN;  // current tuning (NaN until tuned)
    this._nInt = 0;       // floor(N)
    this._nFrac = 0;      // N − floor(N)
    this._alpha = 0;      // ρ^N

    this._corrDb = null;      // cached per-bin dB delta (0 above the band)
    this._corrF0 = NaN;
    this._corrRes = 0;
    this._corrMaxBin = 0;
  }

  /** Current comb fundamental in Hz, or NaN before the first successful
   *  track()/retune(). */
  getMainsHz() {
    return this._mainsHz;
  }

  /** True once the comb has been tuned to a mains frequency. */
  isTuned() {
    return !Number.isNaN(this._mainsHz);
  }

  /**
   * Normalized magnitude response at fHz, linear (0..1): ≈1 in the passband,
   * → 0 at every mains harmonic (k·f₀). Closed form of
   * H(z) = (1 − z⁻ᴺ)/(1 − α·z⁻ᴺ) on the unit circle with N = sampleRate/f₀,
   * scaled by (1+α)/2 so the anti-notch PEAK is exactly 1. Returns 1.0 while
   * untuned.
   * @param {number} fHz
   * @returns {number}  normalized magnitude (0..1)
   */
  magnitudeAt(fHz) {
    if (!this.isTuned()) return 1.0;
    const wn = 2.0 * Math.PI * fHz / this._mainsHz;   // ωN = 2π·f/f₀
    const c = Math.cos(wn);
    const num = 2.0 * (1.0 - c);
    const den = 1.0 - 2.0 * this._alpha * c + this._alpha * this._alpha;
    const h = (den > 0.0) ? Math.sqrt(Math.max(0.0, num / den)) : 0.0;
    return h * (1.0 + this._alpha) / 2.0;             // peak-normalize → 1
  }

  // ── Frequency-domain correction ────────────────────────────────────────

  /**
   * Divides the cached, band-limited, peak-normalized response (in dB) into a
   * magnitude spectrum in place: dbFs (and dbV, the same delta, when non-null)
   * get the notch added per bin. Re-tunes to f0Hz and rebuilds the cache when
   * it has moved. No-op for a non-positive f0Hz (mains off / unlocked).
   * @param {Float64Array|number[]} dbFs            magnitude spectrum (dBFS), modified in place
   * @param {Float64Array|number[]|null} dbV        parallel spectrum (dBV) or null
   * @param {number} freqResolution                 bin width (Hz)
   * @param {number} f0Hz                            mains fundamental (Hz)
   */
  applySpectrumCorrection(dbFs, dbV, freqResolution, f0Hz) {
    if (!(f0Hz > 0.0) || dbFs == null || freqResolution <= 0.0) return;
    const n = dbFs.length;
    if (this._corrDb == null || this._corrDb.length !== n || this._corrRes !== freqResolution
        || Math.abs(this._corrF0 - f0Hz) > CORR_F0_REBUILD_HZ) {
      this.retune(f0Hz);
      this._rebuildCorrection(n, freqResolution);
    }
    const corr = this._corrDb;
    const max = Math.min(this._corrMaxBin, n - 1);
    for (let k = 1; k <= max; k++) {
      const d = corr[k];
      if (d === 0.0) continue;
      dbFs[k] += d;
      if (dbV != null && k < dbV.length) dbV[k] += d;
    }
  }

  /**
   * Band-limited, floored mains correction (dB) at a single frequency. Uses the
   * current tuning. 0 dB outside the notched band.
   * @param {number} fHz
   * @returns {number}  correction (dB)
   */
  correctionDb(fHz) {
    if (fHz > CORR_MAX_HZ || !this.isTuned()) return 0.0;
    const mag = this.magnitudeAt(fHz);
    return (mag > 0.0) ? Math.max(CORR_FLOOR_DB, 20.0 * Math.log10(mag)) : CORR_FLOOR_DB;
  }

  /** (Re)builds corrDb for the current tuning over [0, CORR_MAX_HZ]; bins above
   *  the band stay 0. */
  _rebuildCorrection(n, res) {
    const corr = (this._corrDb != null && this._corrDb.length === n)
      ? this._corrDb : new Float64Array(n);
    corr.fill(0.0);
    const maxBin = Math.min(n - 1, Math.floor(CORR_MAX_HZ / res));
    for (let k = 1; k <= maxBin; k++) corr[k] = this.correctionDb(k * res);
    this._corrDb = corr;
    this._corrF0 = this._mainsHz;
    this._corrRes = res;
    this._corrMaxBin = maxBin;
  }

  /**
   * Directly tunes the comb fundamental, bypassing detection. The frequency is
   * clamped to [MIN_MAINS_HZ, MAX_MAINS_HZ]. Delay-line state is preserved.
   * @param {number} f0Hz
   */
  retune(f0Hz) {
    const f0 = Math.max(MIN_MAINS_HZ, Math.min(MAX_MAINS_HZ, f0Hz));
    const n = this._sampleRate / f0;
    this._nInt = Math.floor(n);
    this._nFrac = n - this._nInt;
    this._alpha = Math.pow(this._rho, n);
    this._mainsHz = f0;
  }

  /** Clears the delay-line state (e.g. on a capture restart) without changing
   *  the current tuning. */
  reset() {
    this._xBuf.fill(0.0);
    this._yBuf.fill(0.0);
    this._pos = 0;
  }

  /** Drops the frequency lock and untunes the comb so the next track()
   *  re-detects from scratch. Also invalidates the cached correction. */
  resetTracking() {
    this._tracker.resetTracking();
    this._mainsHz = NaN;
    this._corrDb = null;
    this._corrF0 = NaN;
  }

  /**
   * Re-estimates the mains fundamental from ref via the shared
   * MainsFrequencyTracker and retunes the comb; returns the locked frequency
   * (Hz), or the held lock when nothing confident is found. Accepts
   * Float32Array, Float64Array or number[].
   * @param {Float32Array|Float64Array|number[]} ref
   * @param {number} len
   * @returns {number}  locked frequency (Hz) or NaN
   */
  track(ref, len) {
    const f0 = this._tracker.track(ref, len);
    if (!Number.isNaN(f0)) this.retune(f0);
    return f0;
  }

  /**
   * Filters data in place (length len). No-op until the comb has been tuned.
   * absStart is ignored: the comb carries phase in its delay line. When data is
   * a Float32Array the output is rounded to float32 (matching the Java float
   * overload's (float) cast); a Float64Array keeps full precision.
   * @param {Float32Array|Float64Array} data
   * @param {number} len
   * @param {number} [absStart=0]
   */
  process(data, len, absStart = 0) {
    if (!this.isTuned()) return;
    len = Math.min(len, data.length);
    const a = this._alpha;
    const g = this._nFrac;
    const g1 = 1.0 - g;
    const xBuf = this._xBuf, yBuf = this._yBuf, bufLen = this._bufLen;
    const f32 = data instanceof Float32Array;
    for (let i = 0; i < len; i++) {
      const inp = data[i];
      let iA = this._pos - this._nInt;   // delay nInt
      let iB = iA - 1;                    // delay nInt + 1
      if (iA < 0) iA += bufLen;
      if (iB < 0) iB += bufLen;
      const xN = g1 * xBuf[iA] + g * xBuf[iB];
      const yN = g1 * yBuf[iA] + g * yBuf[iB];
      const y = inp - xN + a * yN;
      xBuf[this._pos] = inp;
      yBuf[this._pos] = y;
      this._pos++;
      if (this._pos >= bufLen) this._pos = 0;
      data[i] = f32 ? Math.fround(y) : y;
    }
  }

  /**
   * Like process(), but preserves the block's DC (mean) level: subtracts the
   * block mean, combs the zero-mean signal, then adds the mean back — so only
   * the mains hum (50/60 Hz + harmonics) is removed and the DC operating point
   * is left intact. No-op until tuned.
   * @param {Float32Array|Float64Array} data
   * @param {number} len
   * @param {number} [absStart=0]
   */
  processPreservingDc(data, len, absStart = 0) {
    if (!this.isTuned()) return;
    len = Math.min(len, data.length);
    if (len <= 0) return;
    const f32 = data instanceof Float32Array;
    let sum = 0.0;
    for (let i = 0; i < len; i++) sum += data[i];
    const mean = f32 ? Math.fround(sum / len) : sum / len;
    for (let i = 0; i < len; i++) data[i] -= mean;
    this.process(data, len, absStart);
    for (let i = 0; i < len; i++) data[i] += mean;
  }
}
