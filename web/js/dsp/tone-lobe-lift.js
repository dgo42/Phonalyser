/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.dsp.ToneLobeLift.
//
// Shared "lift a tone's main lobe" DSP. To rescale a tone - by 1/|H| for a
// calibration, or to a user level for manual fundamental - the goal is a clean
// rescaled lobe: the window's whole main lobe stretched up, its feet pinned to
// the noise floor (a smooth dome sitting on the grass), NOT a flat-topped box.
//
//   1. Noise floor = median + CEIL_K·MAD over a wide flank band (robust: the
//      lobe / harmonics / mains / spurs falling inside the band are outliers).
//   2. Lobe extent: walk out from the peak until it drops to that floor.
//   3. Stretch: a vertical stretch anchored on the floor - feet stay put, peak
//      pulled to peak·factor, every bin scaled by its log-height above floor.
//
// `mag` arguments are int->double accessors, i.e. functions `(k) => magnitude`.

/** Noise ceiling = median + CEIL_K·MAD of the flank band. */
const CEIL_K = 4.0;

export class ToneLobeLift {
  /**
   * @param {number} [floorGap=10]     bins skipped each side before sampling flanks
   * @param {number} [floorSpan=2048]  bins sampled each side for the noise estimate
   * @param {number} [maxLobeBins=2048] safety cap on the lobe half-width
   */
  constructor(floorGap = 10, floorSpan = 2048, maxLobeBins = 2048) {
    this._floorGap = Math.max(1, floorGap);
    this._floorSpan = Math.max(1, floorSpan);
    this._maxLobeBins = Math.max(1, maxLobeBins);
  }

  /**
   * Noise-floor ceiling (same units as mag) near peak: median + CEIL_K·MAD of
   * the bins flanking the tone. Robust - a wide main lobe, harmonics, mains
   * lines or spurs falling inside the band are outliers the median/MAD ignore.
   * Returns 0 when no flanks are in range.
   * @param {(k:number)=>number} mag   bin-magnitude accessor
   * @param {number} peak              peak bin index
   * @param {number} maxBin            highest valid bin index
   * @returns {number}  noise-floor ceiling
   */
  localFloor(mag, peak, maxBin) {
    const buf = new Float64Array(2 * this._floorSpan);
    let m = 0;
    for (let k = peak - this._floorGap - this._floorSpan; k < peak - this._floorGap; k++) {
      if (k >= 1 && k <= maxBin) buf[m++] = mag(k);
    }
    for (let k = peak + this._floorGap + 1; k <= peak + this._floorGap + this._floorSpan; k++) {
      if (k >= 1 && k <= maxBin) buf[m++] = mag(k);
    }
    if (m === 0) return 0.0;
    const c = buf.slice(0, m);
    c.sort();
    const median = c[(m / 2) | 0];
    for (let i = 0; i < m; i++) c[i] = Math.abs(buf[i] - median);
    c.sort();
    const mad = c[(m / 2) | 0];
    return median + CEIL_K * mad;
  }

  /**
   * Inclusive [lo, hi] bin span of the main lobe: the contiguous bins around
   * peak that stand above the noise floor. The peak itself is always included;
   * the walk runs out to where the lobe drops to the noise.
   * @param {(k:number)=>number} mag
   * @param {number} peak
   * @param {number} maxBin
   * @param {number} floor
   * @returns {[number, number]}  [lo, hi]
   */
  lobeBins(mag, peak, maxBin, floor) {
    return [this._edge(mag, peak, -1, maxBin, floor),
            this._edge(mag, peak, +1, maxBin, floor)];
  }

  _edge(mag, peak, dir, maxBin, floor) {
    let foot = peak;
    for (let step = 1; step <= this._maxLobeBins; step++) {
      const k = peak + dir * step;
      if (k < 1 || k > maxBin) break;
      if (mag(k) <= floor) break;   // dropped to the noise - lobe ends
      foot = k;
    }
    return foot;
  }

  /**
   * Vertical stretch of a lobe magnitude, anchored on the noise floor: the feet
   * stay put and the peak is pulled up to peak·factor, every bin scaled by its
   * log-height above the floor - |X'| = |X|·factor^t with
   * t = ln(|X|/floor) / ln(peak/floor) (t = 1 at the peak, 0 at the floor).
   * A bin at or below the floor is left alone; a tone whose peak is itself
   * buried at/below the floor is simply scaled by factor.
   * @param {number} mag     bin magnitude
   * @param {number} floor   noise floor
   * @param {number} peak    peak magnitude
   * @param {number} factor  target peak gain
   * @returns {number}  stretched magnitude
   */
  stretch(mag, floor, peak, factor) {
    if (!(peak > floor)) return mag * factor;
    if (!(mag > floor)) return mag;
    let t = Math.log(mag / floor) / Math.log(peak / floor);
    // A bin standing ABOVE the assumed peak (t > 1) - a noise bin in the lobe
    // of a weak tone whose true peak isn't at round(toneHz/binWidth) - must not
    // be lifted MORE than the peak itself. Cap at the peak's own factor.
    if (t > 1.0) t = 1.0;
    return mag * Math.pow(factor, t);
  }
}
