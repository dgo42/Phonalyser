/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.dsp.HarmonicCompensation.
//
// Complex accumulator for closed-loop DAC harmonic pre-distortion. Each measured
// FftResult contributes an LMS-style update of the per-harmonic correction phasor
// (re, im): the harmonic's ABSOLUTE measured level (linear, from its de-embedded-
// chain dBFS) and its RAW window-derotated phase are added in, scaled by step μ.
// The .frc de-embed and the division by the fundamental are applied on the way
// OUT (toGeneratorCorrections / writeDpd rows), not in the accumulator.
//
// Index h is harmonic H(h+2). All math uses Float64Array (browser `number` is
// IEEE-754 binary64, bit-identical to the desktop `double`).

/**
 * Generator-shaped snapshot of the non-zero correction phasors.
 * @typedef {Object} HarmonicGeneratorCorrections
 * @property {Float64Array} ampRatios       per-harmonic amplitude ratio to the DAC fundamental
 * @property {Int32Array}   harmonicNumbers harmonic number (2, 3, ...) of each entry
 * @property {Float64Array} phiInits        de-embedded initial phase (rad) of each entry
 */

export class HarmonicCompensation {
  /** @param {number} maxHarmonics number of harmonic slots (H2..H(maxHarmonics+1)). */
  constructor(maxHarmonics) {
    this.maxHarmonics = maxHarmonics;
    /** Per-harmonic accumulated correction phasor (real / imaginary parts) and
     *  the harmonic's measured frequency. Index h is harmonic H(h+2). */
    this.accRe = new Float64Array(maxHarmonics);
    this.accIm = new Float64Array(maxHarmonics);
    this.hFreqs = new Float64Array(maxHarmonics);
  }

  /**
   * LMS-style accumulate of the harmonics measured in `r`. The accumulator holds,
   * per harmonic, a COMPLEX phasor whose magnitude is the harmonic's ABSOLUTE
   * measured level (linear, from its de-embedded-chain dBFS) and whose phase is
   * the RAW measured phase, window-derotated.
   *
   * The fundamental phase is the per-round frame reference: φ₁ is the measured
   * arg(X₁) MINUS the .frc phase at f₁ (`calFundPhaseRad`); the whole spectrum is
   * de-rotated by it (each harmonic by n·φ₁) so F0 -> 0° and every round lands in
   * the same frame. The cal-phase subtraction removes the twin-T notch's ≈π.
   *
   * @param {import('../fft/fft-result.js').FftResult} r measured FFT result
   * @param {number} step LMS step μ
   * @param {number} calFundPhaseRad .frc phase (rad) at the fundamental f₁
   */
  accumulate(r, step, calFundPhaseRad) {
    const re1 = !Number.isNaN(r.rawFundRe) ? r.rawFundRe : r.re[r.fundamentalBin];
    const im1 = !Number.isNaN(r.rawFundIm) ? r.rawFundIm : r.im[r.fundamentalBin];
    const phi1 = Math.atan2(im1, re1) - calFundPhaseRad;      // window phase, F0 notch phase removed
    const delayRadPerHz = -(phi1 + Math.PI / 2.0) / r.fundamentalHzRefined;

    const count = Math.min(r.harmonicCount, this.maxHarmonics);
    for (let h = 0; h < count; h++) {
      const bin = r.harmonicBins[h];
      if (bin <= 0) continue;

      const magLin = Math.pow(10.0, r.rawHarmonicDbFs(h) / 20.0);    // absolute ADC level, pre-.frc
      const rawRe = (r.rawPeakRe != null && h < r.rawPeakRe.length) ? r.rawPeakRe[h] : r.re[bin];
      const rawIm = (r.rawPeakIm != null && h < r.rawPeakIm.length) ? r.rawPeakIm[h] : r.im[bin];
      const phiH = Math.atan2(rawIm, rawRe);                         // RAW phase; .frc de-embed is downstream
      const phiInit = phiH + r.harmonicHz[h] * delayRadPerHz;
      this.accRe[h] += step * magLin * Math.cos(phiInit);
      this.accIm[h] += step * magLin * Math.sin(phiInit);
      this.hFreqs[h] = r.harmonicHz[h];
    }
  }

  /** True once at least one harmonic has accumulated a non-zero correction. */
  hasCorrections() {
    for (let h = 0; h < this.maxHarmonics; h++) {
      if (this.accRe[h] !== 0.0 || this.accIm[h] !== 0.0) return true;
    }
    return false;
  }

  /**
   * Converts the accumulated ABSOLUTE (ADC-dBFS) phasors to the generator's
   * compensation triple - applying HERE (on the values that reach the DDS): the
   * .frc de-embed (magnitude AND phase), the ADC-dBFS -> absolute-volts conversion,
   * and the division by the DAC fundamental. Harmonics that never rose above the
   * gate are dropped.
   *
   * @param {number} fundamentalHz fundamental frequency (Hz)
   * @param {(f: number) => [number, number]} calResponseAt returns the loaded
   *        .frc response [magLin, phaseRad] at a frequency
   * @param {number} adcFsVoltageRms ADC full-scale (Vrms) - dBFS -> Vrms
   * @param {number} dacFundamentalVrms DAC fundamental output (Vrms) the ratio is taken against
   * @returns {HarmonicGeneratorCorrections}
   */
  toGeneratorCorrections(fundamentalHz, calResponseAt, adcFsVoltageRms, dacFundamentalVrms) {
    let valid = 0;
    for (let h = 0; h < this.maxHarmonics; h++) {
      if (this.accRe[h] !== 0.0 || this.accIm[h] !== 0.0) valid++;
    }
    const amp = new Float64Array(valid);
    const num = new Int32Array(valid);
    const phi = new Float64Array(valid);
    let i = 0;
    for (let h = 0; h < this.maxHarmonics; h++) {
      const re = this.accRe[h], im = this.accIm[h];
      if (re === 0.0 && im === 0.0) continue;
      const cal = calResponseAt(this.hFreqs[h]);                     // [magLin, phaseRad] of H(f_h)
      const magDe = Math.hypot(re, im) / (cal[0] > 0.0 ? cal[0] : 1.0);    // ÷ chain magnitude (de-embed)
      const vH = magDe * adcFsVoltageRms;                            // ADC dBFS -> absolute Vrms at DAC out
      amp[i] = dacFundamentalVrms > 0.0 ? vH / dacFundamentalVrms : 0.0;   // ratio to the DAC fundamental
      phi[i] = Math.atan2(im, re) - cal[1];                          // − chain phase (de-embed)
      num[i] = Math.round(this.hFreqs[h] / fundamentalHz);
      i++;
    }
    return { ampRatios: amp, harmonicNumbers: num, phiInits: phi };
  }

  /** Deep copy - lets the wizard snapshot the best-THD iteration's accumulator
   *  while the loop keeps updating the live one. */
  copy() {
    const c = new HarmonicCompensation(this.maxHarmonics);
    c.accRe.set(this.accRe);
    c.accIm.set(this.accIm);
    c.hFreqs.set(this.hFreqs);
    return c;
  }
}
