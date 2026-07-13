/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.dsp.SineFit.
//
// Exact 3-parameter least-squares sine fit at a fixed frequency:
//   y[k] = a·sin(omega·k) + b·cos(omega·k) + c,   omega = 2·PI·freqHz/sampleRate.
// With the frequency held fixed the model is linear in a, b, c, so a single pass
// builds the 3×3 normal equations (accumulated via a sin/cos rotation recurrence,
// no per-sample trig) and a partial-pivoted Gaussian elimination solves them.
// All math is plain JS number (= Java double), so the fit is bit-identical.

/** Exact 3-parameter least-squares sine fit at a fixed frequency. */
export class SineFit {
  /**
   * @param {number} a           sine coefficient of the fit model
   * @param {number} b           cosine coefficient of the fit model
   * @param {number} c           DC offset of the fit model
   * @param {number} frequencyHz fixed frequency the fit was performed at (Hz)
   * @param {number} omega       angular increment per sample (rad); internal, no getter in Java
   */
  constructor(a, b, c, frequencyHz, omega) {
    this.a = a;
    this.b = b;
    this.c = c;
    this.frequencyHz = frequencyHz;
    this._omega = omega;
  }

  /**
   * Fits the model over {@code data[from .. from+len)}, with k = 0 at index from.
   * Port of SineFit.of(float[] data, int from, int len, double sampleRate, double freqHz).
   * @param {Float32Array|Float64Array|number[]} data signal buffer
   * @param {number} from window start index
   * @param {number} len window length in samples
   * @param {number} sampleRate sample rate in Hz
   * @param {number} freqHz fixed frequency to fit at (Hz)
   * @returns {SineFit}
   */
  static of(data, from, len, sampleRate, freqHz) {
    const omega = 2.0 * Math.PI * freqHz / sampleRate;
    const cosOmega = Math.cos(omega), sinOmega = Math.sin(omega);
    let curSin = 0.0, curCos = 1.0;

    let ss = 0, sc = 0, s1 = 0, cc = 0, c1 = 0;
    let ys = 0, yc = 0, y1 = 0;
    for (let i = 0; i < len; i++) {
      const sn = curSin, cn = curCos, yn = data[from + i];
      ss += sn * sn;  sc += sn * cn;  s1 += sn;
      cc += cn * cn;  c1 += cn;
      ys += yn * sn;  yc += yn * cn;  y1 += yn;
      const nextSin = sn * cosOmega + cn * sinOmega;
      curCos = cn * cosOmega - sn * sinOmega;
      curSin = nextSin;
    }
    return SineFit._fromNormalEquations(ss, sc, s1, cc, c1, len, ys, yc, y1, freqHz, omega);
  }

  /**
   * Fits the model over the whole {@code samples} array (k = 0 at index 0).
   * Port of SineFit.of(double[] samples, int sampleRate, double freqHz).
   * @param {Float64Array|number[]} samples signal samples
   * @param {number} sampleRate sample rate in Hz
   * @param {number} freqHz fixed frequency to fit at (Hz)
   * @returns {SineFit}
   */
  static ofArray(samples, sampleRate, freqHz) {
    const omega = 2.0 * Math.PI * freqHz / sampleRate;
    const cosOmega = Math.cos(omega), sinOmega = Math.sin(omega);
    let curSin = 0.0, curCos = 1.0;
    const N = samples.length;

    let ss = 0, sc = 0, s1 = 0, cc = 0, c1 = 0;
    let ys = 0, yc = 0, y1 = 0;
    for (let n = 0; n < N; n++) {
      const sn = curSin, cn = curCos, yn = samples[n];
      ss += sn * sn;  sc += sn * cn;  s1 += sn;
      cc += cn * cn;  c1 += cn;
      ys += yn * sn;  yc += yn * cn;  y1 += yn;
      const nextSin = sn * cosOmega + cn * sinOmega;
      curCos = cn * cosOmega - sn * sinOmega;
      curSin = nextSin;
    }
    return SineFit._fromNormalEquations(ss, sc, s1, cc, c1, N, ys, yc, y1, freqHz, omega);
  }

  /**
   * Solves the accumulated 3×3 normal-equation system by Gaussian elimination
   * with partial pivoting and wraps the result.
   */
  static _fromNormalEquations(ss, sc, s1, cc, c1, n, ys, yc, y1, freqHz, omega) {
    const aug = [
      new Float64Array([ss, sc, s1, ys]),
      new Float64Array([sc, cc, c1, yc]),
      new Float64Array([s1, c1, n,  y1]),
    ];
    for (let col = 0; col < 3; col++) {
      let maxRow = col;
      for (let row = col + 1; row < 3; row++) {
        if (Math.abs(aug[row][col]) > Math.abs(aug[maxRow][col])) maxRow = row;
      }
      const tmp = aug[col]; aug[col] = aug[maxRow]; aug[maxRow] = tmp;
      const diag = aug[col][col];
      if (Math.abs(diag) < 1e-15) continue;
      for (let row = col + 1; row < 3; row++) {
        const f = aug[row][col] / diag;
        for (let j = col; j <= 3; j++) aug[row][j] -= f * aug[col][j];
      }
    }
    const x = new Float64Array(3);
    for (let i = 2; i >= 0; i--) {
      x[i] = aug[i][3];
      for (let j = i + 1; j < 3; j++) x[i] -= aug[i][j] * x[j];
      x[i] /= aug[i][i];
    }
    return new SineFit(x[0], x[1], x[2], freqHz, omega);
  }

  /** Peak amplitude of the fitted sinusoid, hypot(a, b). */
  amplitude() { return Math.hypot(this.a, this.b); }

  /** Initial phase of the fitted sinusoid in radians, atan2(b, a). */
  phaseRadians() { return Math.atan2(this.b, this.a); }

  /**
   * Subtracts the fitted sinusoid (excluding the DC term c) from
   * src[srcFrom .. srcFrom+count) into dst[dstFrom ..]. DC is left in the trace
   * on purpose (the AC toggle handles DC). Uses a double-precision sin/cos
   * rotation recurrence seeded at omega·kOffset. Pass a Float32Array dst to match
   * Java's (float) cast on store.
   */
  subtractSineInto(src, srcFrom, count, kOffset, dst, dstFrom) {
    const startAngle = this._omega * kOffset;
    let curSin = Math.sin(startAngle);
    let curCos = Math.cos(startAngle);
    const cosOmega = Math.cos(this._omega), sinOmega = Math.sin(this._omega);
    const a = this.a, b = this.b;
    for (let i = 0; i < count; i++) {
      const sine = a * curSin + b * curCos;
      dst[dstFrom + i] = src[srcFrom + i] - sine;
      const nextSin = curSin * cosOmega + curCos * sinOmega;
      curCos = curCos * cosOmega - curSin * sinOmega;
      curSin = nextSin;
    }
  }

  /**
   * Subtracts the WHOLE fitted model — sinusoid and the DC term c — from
   * src[srcFrom .. srcFrom+count), then adds back a caller-supplied replacement
   * DC dcAdd, into dst[dstFrom ..]. Same recurrence as subtractSineInto.
   */
  subtractFullInto(src, srcFrom, count, kOffset, dcAdd, dst, dstFrom) {
    const startAngle = this._omega * kOffset;
    let curSin = Math.sin(startAngle);
    let curCos = Math.cos(startAngle);
    const cosOmega = Math.cos(this._omega), sinOmega = Math.sin(this._omega);
    const a = this.a, b = this.b, c = this.c;
    for (let i = 0; i < count; i++) {
      const model = a * curSin + b * curCos + c;
      dst[dstFrom + i] = src[srcFrom + i] - model + dcAdd;
      const nextSin = curSin * cosOmega + curCos * sinOmega;
      curCos = curCos * cosOmega - curSin * sinOmega;
      curSin = nextSin;
    }
  }
}
