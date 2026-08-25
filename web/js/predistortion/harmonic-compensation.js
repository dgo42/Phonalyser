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
// Both conversions also divide by the per-harmonic PLANT transfer - what the
// DAC-to-ADC chain actually answers to a commanded correction, measured once by
// measurePlant and installed by setPlant. It is the identity until a probe
// installs one, so an accumulator that never sees a probe converts exactly as it
// always did. Without it the loop assumes a perfect chain: an excess transport
// delay turns every command by n times the same angle, and beyond a quarter turn
// the unit-step iteration stops contracting and the high harmonics grow instead
// of shrinking.
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

/**
 * Per-harmonic complex phasors with the frequency each belongs to, index
 * h = harmonic H(h+2). Carries a frame's measurement (measurePhasors) and the
 * measured plant (measurePlant), whose unmeasured entries are NaN.
 * @typedef {Object} HarmonicPhasors
 * @property {Float64Array} re real parts
 * @property {Float64Array} im imaginary parts
 * @property {Float64Array} hz frequency each entry belongs to (0 = not measured)
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
    /** Per-harmonic PLANT transfer G - how the DAC-to-ADC chain actually answers a
     *  commanded correction, measured once by the probe (measurePlant). Identity
     *  (1, 0) until one is installed, which leaves every outgoing correction
     *  bit-for-bit what it was before a plant existed. */
    this.plantRe = new Float64Array(maxHarmonics);
    this.plantIm = new Float64Array(maxHarmonics);
    this.plantRe.fill(1.0);                                   // identity plant
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
    const z = this.measurePhasors(r, calFundPhaseRad);
    for (let h = 0; h < this.maxHarmonics; h++) {
      if (!(z.hz[h] > 0.0)) continue;                         // harmonic not measured in this frame
      this.accRe[h] += step * z.re[h];
      this.accIm[h] += step * z.im[h];
      this.hFreqs[h] = z.hz[h];
    }
  }

  /**
   * The harmonics of one frame as complex phasors in the accumulator's frame - the
   * construction accumulate() adds in, and the same one the probe differences to
   * measure the plant, so both read the measurement identically. Magnitude is the
   * harmonic's ABSOLUTE measured level (linear, from its de-embedded-chain dBFS),
   * phase is the RAW measured phase de-rotated by n*phi1 as described on
   * accumulate(); a harmonic that was not located carries (0, 0) at frequency 0.
   *
   * @param {import('../fft/fft-result.js').FftResult} r measured FFT result
   * @param {number} calFundPhaseRad .frc phase (rad) at the fundamental f1
   * @returns {HarmonicPhasors}
   */
  measurePhasors(r, calFundPhaseRad) {
    const re = new Float64Array(this.maxHarmonics);
    const im = new Float64Array(this.maxHarmonics);
    const hz = new Float64Array(this.maxHarmonics);
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
      re[h] = magLin * Math.cos(phiInit);
      im[h] = magLin * Math.sin(phiInit);
      hz[h] = r.harmonicHz[h];
    }
    return { re, im, hz };
  }

  /**
   * Measures the per-harmonic plant transfer G - what the chain actually does with
   * a correction it is commanded to inject - from two frames: one collected with
   * no probe, one with KNOWN partials applied through the same generator path the
   * corrections use. The DUT's own distortion and the noise are common to both
   * frames, so their difference is the probe's arrival alone; dividing it by the
   * arrival the command should have produced leaves the chain's own magnitude and
   * phase answer.
   *
   * The expected arrival follows the measurement's own normalisation: the
   * generator SUBTRACTS the commanded phasor, the partial reaches the ADC through
   * H(f_h), and the harmonics are measured against a fundamental that carries
   * |H(f1)| - a notch on f1 makes that a large factor, which is why it must not be
   * dropped: expected = -c * H(f_h) * |F1_raw| / |H(f1)|.
   *
   * A harmonic whose arrival does not stand `clearanceDb` above the frame's noise
   * floor gets NaN - a noise reading must never become a plant to divide by.
   *
   * @param {import('../fft/fft-result.js').FftResult} baseline frame with no probe applied
   * @param {import('../fft/fft-result.js').FftResult} probe    frame with the probe partials applied
   * @param {ArrayLike<number>} probeAmpRatios commanded amplitude ratio to the DAC
   *        fundamental, index h = harmonic H(h+2); 0 = harmonic not probed
   * @param {ArrayLike<number>} probePhases commanded initial phase (rad) per harmonic
   * @param {(f: number) => [number, number]} calResponseAt loaded .frc response
   *        [magLin, phaseRad] at a frequency
   * @param {number} clearanceDb dB the arrival must clear the noise floor by
   * @returns {HarmonicPhasors}
   */
  measurePlant(baseline, probe, probeAmpRatios, probePhases, calResponseAt, clearanceDb) {
    const gRe = new Float64Array(this.maxHarmonics);
    const gIm = new Float64Array(this.maxHarmonics);
    const hz = new Float64Array(this.maxHarmonics);
    gRe.fill(NaN);
    gIm.fill(NaN);

    const calF1 = calResponseAt(probe.fundamentalHzRefined);
    const base = this.measurePhasors(baseline, calResponseAt(baseline.fundamentalHzRefined)[1]);
    const probed = this.measurePhasors(probe, calF1[1]);
    const fundLin = Math.hypot(probe.rawFundRe, probe.rawFundIm);
    if (!(fundLin > 0.0) || !(calF1[0] > 0.0)) {
      return { re: gRe, im: gIm, hz };                  // nothing to normalise against
    }

    for (let h = 0; h < this.maxHarmonics; h++) {
      if (h >= probeAmpRatios.length || !(probeAmpRatios[h] > 0.0)) continue;
      if (!(base.hz[h] > 0.0) || !(probed.hz[h] > 0.0)) continue;
      const dRe = probed.re[h] - base.re[h];
      const dIm = probed.im[h] - base.im[h];
      const arrivalDb = 20.0 * Math.log10(Math.hypot(dRe, dIm));
      if (!(arrivalDb >= probe.avgNoiseFloorDbFs + clearanceDb)) continue;

      const calH = calResponseAt(probed.hz[h]);
      const hRe = calH[0] * Math.cos(calH[1]);
      const hIm = calH[0] * Math.sin(calH[1]);
      const cRe = probeAmpRatios[h] * Math.cos(probePhases[h]);
      const cIm = probeAmpRatios[h] * Math.sin(probePhases[h]);
      const scale = -fundLin / calF1[0];                // the generator subtracts the command
      const eRe = scale * (cRe * hRe - cIm * hIm);
      const eIm = scale * (cRe * hIm + cIm * hRe);
      const den = eRe * eRe + eIm * eIm;
      if (!(den > 0.0)) continue;
      gRe[h] = (dRe * eRe + dIm * eIm) / den;           // measured / expected
      gIm[h] = (dIm * eRe - dRe * eIm) / den;
      hz[h] = probed.hz[h];
    }
    return { re: gRe, im: gIm, hz };
  }

  /** Installs a measured plant. An entry that is not finite (no trustworthy probe
   *  for that harmonic) or exactly zero keeps the identity, so such a harmonic is
   *  corrected exactly as it was before any probe ran.
   *  @param {HarmonicPhasors} plant */
  setPlant(plant) {
    for (let h = 0; h < this.maxHarmonics && h < plant.re.length; h++) {
      const re = plant.re[h];
      const im = plant.im[h];
      if (!Number.isFinite(re) || !Number.isFinite(im)) continue;
      if (re === 0.0 && im === 0.0) continue;
      this.plantRe[h] = re;
      this.plantIm[h] = im;
    }
  }

  /**
   * The accumulated phasor as it LEAVES the accumulator: divided by the measured
   * plant, so the command answers the chain's own magnitude and phase instead of
   * assuming a perfect one. With the identity plant the division is by 1 + 0j -
   * the accumulator passes through unchanged, bit for bit.
   *
   * Public here because the .dpd writer is its own module (io/dpd.js) rather than
   * a method on this class as on the desktop, and a saved file must reproduce what
   * the generator was actually running - so the plant division stays defined in
   * exactly one place.
   *
   * @param {number} h harmonic slot
   * @returns {[number, number]} the plant-divided [re, im]
   */
  correctedPhasor(h) {
    const gRe = this.plantRe[h];
    const gIm = this.plantIm[h];
    const den = gRe * gRe + gIm * gIm;
    if (!(den > 0.0)) return [this.accRe[h], this.accIm[h]];
    return [(this.accRe[h] * gRe + this.accIm[h] * gIm) / den,
      (this.accIm[h] * gRe - this.accRe[h] * gIm) / den];
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
      if (this.accRe[h] === 0.0 && this.accIm[h] === 0.0) continue;
      const z = this.correctedPhasor(h);                             // ÷ measured plant G(f_h)
      const re = z[0], im = z[1];
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
    // The plant travels with the copy: the retained best-round snapshot is what
    // gets saved, and it must convert to the generator exactly as the live
    // accumulator did.
    c.plantRe.set(this.plantRe);
    c.plantIm.set(this.plantIm);
    return c;
  }
}
