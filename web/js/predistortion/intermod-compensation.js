/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.dsp.IntermodCompensation.
//
// Complex accumulator for closed-loop DAC intermodulation pre-distortion - the
// two-tone sibling of HarmonicCompensation. Every distortion product of a two-
// tone signal sits at a·f₁ + b·f₂ for integer (a, b) with phase a·θ₁ + b·θ₂, so a
// single (a, b)-indexed correction set cancels them all. Holds a fixed grid of
// such products and accumulates an LMS-style correction phasor per product; the
// .frc de-embed, the dBV conversion and the division by the DAC fundamental are
// applied on the way OUT. All math uses Float64Array.
//
// Both conversions also divide by the per-product PLANT transfer - what the chain
// answers to a commanded correction at that product, measured once by measurePlant
// and installed by setPlant. It is the identity until a probe installs one, so an
// accumulator that never sees a probe converts exactly as it always did. Without it
// the loop assumes a perfect chain, and an excess transport delay turns every
// command by an angle that grows with the product's frequency until the unit-step
// iteration stops contracting.

/** Highest |a|+|b| the FFT analyzer is guaranteed to de-rotate. */
const INTERMOD_ORDER_CAP = 5;

/** Unit calibration response [magLin=1, phaseRad=0] - a no-op divide for the b=0
 *  harmonics the display already de-embedded. */
const UNIT_RESPONSE = [1.0, 0.0];

/**
 * Generator-shaped snapshot of the non-zero dual-tone correction phasors.
 * @typedef {Object} IntermodGeneratorCorrections
 * @property {Float64Array} ampRatios amplitude ratio to the DAC F1 fundamental
 * @property {Int32Array}   coefA     θ₁ multiplier of each product
 * @property {Int32Array}   coefB     θ₂ multiplier of each product
 * @property {Float64Array} phiInits  de-embedded initial phase (rad) of each product
 */

/**
 * Per-product complex phasors with the grid coordinates and the frequency each
 * belongs to. Carries a frame's measurement (measurePhasors) and the measured
 * plant (measurePlant), whose unmeasured entries are NaN.
 * @typedef {Object} ProductPhasors
 * @property {Int32Array}   coefA theta1 multiplier of each product
 * @property {Int32Array}   coefB theta2 multiplier of each product
 * @property {Float64Array} re    real parts
 * @property {Float64Array} im    imaginary parts
 * @property {Float64Array} hz    frequency each entry belongs to (0 = not measured)
 */

export class IntermodCompensation {
  /** @param {number} maxHarmonics highest per-tone harmonic in the grid. */
  constructor(maxHarmonics) {
    // The full-array copy path passes the seven arrays directly (see copy()).
    if (arguments.length === 7) {
      const [coefA, coefB, accRe, accIm, freqHz, plantRe, plantIm] = arguments;
      this.coefA = coefA.slice();
      this.coefB = coefB.slice();
      this.accRe = accRe.slice();
      this.accIm = accIm.slice();
      this.freqHz = freqHz.slice();
      this.plantRe = plantRe.slice();
      this.plantIm = plantIm.slice();
      return;
    }
    const grid = buildGrid(maxHarmonics);
    const n = grid.length;
    this.coefA = new Int32Array(n);
    this.coefB = new Int32Array(n);
    for (let i = 0; i < n; i++) {
      this.coefA[i] = grid[i][0];
      this.coefB[i] = grid[i][1];
    }
    this.accRe = new Float64Array(n);
    this.accIm = new Float64Array(n);
    this.freqHz = new Float64Array(n);
    /** Per-product PLANT transfer G - what the DAC-to-ADC chain answers to a
     *  commanded correction at that product, measured once by the probe
     *  (measurePlant). Identity (1, 0) until one is installed, which leaves every
     *  outgoing correction bit-for-bit what it was before a plant existed. */
    this.plantRe = new Float64Array(n);
    this.plantIm = new Float64Array(n);
    this.plantRe.fill(1.0);                              // identity plant
  }

  /**
   * LMS-style accumulate of the two-tone distortion products measured in `r`. The
   * accumulator holds each product's ABSOLUTE measured level (dBFS-linear, off the
   * conv-scaled phasors) as a complex phasor, window-derotated; the .frc de-embed,
   * the dBV conversion and the division by the DAC fundamental are applied on the
   * way OUT. Every detected product is corrected - no noise gate.
   *
   * Each product a·f₁+b·f₂ is de-rotated by a·(φ₁+π/2) + b·(φ₂+π/2), where
   * φ₁ = arg(X_F1) − argH(f₁) and φ₂ = arg(X_F2) − argH(f₂) are the measured
   * fundamental phases with the twin-T notch phase removed. F2 is the (0,1) grid
   * product. The b=0 F1 harmonics read the display-de-embedded re/im phase +
   * rawHarmonicDbFs magnitude (unit response downstream); every b≠0 product reads
   * the raw conv-scaled phasor and is de-embedded downstream.
   *
   * @param {import('../fft/fft-result.js').FftResult} r measured FFT result
   * @param {number} f1Hz tone-1 frequency (Hz)
   * @param {number} f2Hz tone-2 frequency (Hz)
   * @param {number} step LMS step μ
   * @param {number} calF1PhaseRad argH(f₁) - the .frc phase at F1
   * @param {number} calF2PhaseRad argH(f₂) - the .frc phase at F2
   */
  accumulate(r, f1Hz, f2Hz, step, calF1PhaseRad, calF2PhaseRad) {
    const z = this.measurePhasors(r, f1Hz, f2Hz, calF1PhaseRad, calF2PhaseRad);
    for (let g = 0; g < this.accRe.length; g++) {
      if (!(z.hz[g] > 0.0)) continue;                           // product not measured in this frame
      this.accRe[g] += step * z.re[g];
      this.accIm[g] += step * z.im[g];
      this.freqHz[g] = z.hz[g];
    }
  }

  /**
   * The distortion products of one frame as complex phasors in the accumulator's
   * frame - the construction accumulate() adds in, and the same one the probe
   * differences to measure the plant, so both read a frame identically. A product
   * the analyzer did not publish this tick carries (0, 0) at frequency 0.
   *
   * @param {import('../fft/fft-result.js').FftResult} r measured FFT result
   * @param {number} f1Hz tone-1 frequency (Hz)
   * @param {number} f2Hz tone-2 frequency (Hz)
   * @param {number} calF1PhaseRad argH(f1) - the .frc phase at F1
   * @param {number} calF2PhaseRad argH(f2) - the .frc phase at F2
   * @returns {ProductPhasors}
   */
  measurePhasors(r, f1Hz, f2Hz, calF1PhaseRad, calF2PhaseRad) {
    const re = new Float64Array(this.coefA.length);
    const im = new Float64Array(this.coefA.length);
    const hz = new Float64Array(this.coefA.length);
    const out = { coefA: this.coefA.slice(), coefB: this.coefB.slice(), re, im, hz };
    if (r.re == null || r.im == null) return out;
    const fundBin = r.fundamentalBin;
    if (fundBin <= 0 || fundBin >= r.re.length || !(f1Hz > 0.0) || !(f2Hz > 0.0)) return out;

    const f1Re = !Number.isNaN(r.rawFundRe) ? r.rawFundRe : r.re[fundBin];
    const f1Im = !Number.isNaN(r.rawFundIm) ? r.rawFundIm : r.im[fundBin];
    const phi1 = Math.atan2(f1Im, f1Re) - calF1PhaseRad;        // F1 frame, notch phase removed
    // F2 is the (0,1) product in the analyzer's de-rotated grid.
    const f2Idx = productIndex(r, 0, 1);
    const phi2 = (f2Idx >= 0 && r.rawPeakRe != null && f2Idx < r.rawPeakRe.length)
      ? Math.atan2(r.rawPeakIm[f2Idx], r.rawPeakRe[f2Idx]) - calF2PhaseRad
      : phi1;

    for (let g = 0; g < this.coefA.length; g++) {
      const slot = this._rawSlot(r, g, f1Hz, f2Hz);
      if (slot == null) continue;
      const refPhase = this.coefA[g] * (phi1 + Math.PI / 2.0) + this.coefB[g] * (phi2 + Math.PI / 2.0);
      const phiInit = slot[1] - refPhase;
      re[g] = slot[0] * Math.cos(phiInit);
      im[g] = slot[0] * Math.sin(phiInit);
      hz[g] = slot[2];
    }
    return out;
  }

  /**
   * The raw reading of grid slot `g` in `r`: [magLin, phaseRad, freqHz], or null
   * when the frame does not carry that product. The single definition both the
   * accumulator frame and the product-presence test read through, so the two can
   * never disagree about what a frame says: a b=0 F1 harmonic takes the
   * display-de-embedded re/im phase with the raw harmonic level (and unit response
   * downstream), every b!=0 product takes the raw conv-scaled phasor.
   *
   * @returns {?[number, number, number]}
   */
  _rawSlot(r, g, f1Hz, f2Hz) {
    const a = this.coefA[g];
    const b = this.coefB[g];
    const fp = a * f1Hz + b * f2Hz;
    if (!(fp > 0.0)) return null;
    let magLin, phiRaw;
    if (b === 0) {
      const idx = a - 2;                           // harmonicBins[0] = 2nd harmonic
      if (r.harmonicBins == null || idx < 0 || idx >= r.harmonicCount) return null;
      const bin = r.harmonicBins[idx];
      if (bin <= 0 || bin >= r.re.length) return null;
      magLin = Math.pow(10.0, r.rawHarmonicDbFs(idx) / 20.0);   // absolute (lobe dBFS)
      phiRaw = Math.atan2(r.im[bin], r.re[bin]);                // display-de-embedded phase
    } else {
      const i = productIndex(r, a, b);
      if (i < 0 || r.rawPeakRe == null || i >= r.rawPeakRe.length) return null;
      magLin = Math.hypot(r.rawPeakRe[i], r.rawPeakIm[i]);      // absolute (conv-scaled dBFS-linear)
      phiRaw = Math.atan2(r.rawPeakIm[i], r.rawPeakRe[i]);      // raw phase (de-embed downstream)
    }
    return [magLin, phiRaw, fp];
  }

  /**
   * True when `r` carries the analyzer's de-rotated phasor for at least one MIXED
   * (b != 0) product of this grid - read through the very _rawSlot the measurement
   * uses, so the answer cannot differ from what a measurement would find.
   *
   * The per-product de-rotation is a per-tick decision: a frame taken right after a
   * statistics re-anchor or a discontinuity re-sync can carry no de-rotated
   * products at all (imdProductA empty). A plant measured from such a frame is not
   * wrong, it is HALF measured - the b=0 harmonics still read out of the harmonic
   * table while every mixed product looks "not measured this tick" and silently
   * keeps the identity, so nothing in the result marks the loss. A caller that gets
   * to choose its frame - the probe - must therefore ask first.
   *
   * @param {?import('../fft/fft-result.js').FftResult} r
   * @returns {boolean}
   */
  carriesProducts(r) {
    if (r == null || r.re == null || r.im == null) return false;
    const f1Hz = r.fundamentalHzRefined;
    const f2Hz = r.fundamental2HzRefined;
    if (!(f1Hz > 0.0) || !(f2Hz > 0.0)) return false;
    for (let g = 0; g < this.coefA.length; g++) {
      if (this.coefB[g] !== 0 && this._rawSlot(r, g, f1Hz, f2Hz) != null) return true;
    }
    return false;
  }

  /**
   * The probe command for the WHOLE grid in the generator's dual-tone shape: every
   * product commanded at `ampRatio` (ratio to the DAC F1 tone) with initial phases
   * stepped `phaseStepRad` apart. The caller hands the same object to the generator
   * and to measurePlant, so what was commanded and what the measurement divides by
   * cannot drift apart.
   *
   * Stepped phases are not required to measure the plant - any known phase would do
   * - but they make a product delivered to the wrong DDS partial show up as a plant
   * phase that does not fit the measured pattern.
   *
   * @param {number} ampRatio     amplitude ratio to the DAC F1 tone
   * @param {number} phaseStepRad phase step between consecutive partials (rad)
   * @returns {IntermodGeneratorCorrections}
   */
  probeCommand(ampRatio, phaseStepRad) {
    const n = this.coefA.length;
    const amp = new Float64Array(n);
    const phi = new Float64Array(n);
    for (let g = 0; g < n; g++) {
      amp[g] = ampRatio;
      phi[g] = g * phaseStepRad;
    }
    return { ampRatios: amp, coefA: this.coefA.slice(), coefB: this.coefB.slice(), phiInits: phi };
  }

  /**
   * Measures the per-product plant transfer G - what the chain actually does with a
   * correction it is commanded to inject - from two frames: one collected with no
   * probe, one with the probeCommand partials applied through the same generator
   * path the corrections use. The DUT's own products and the noise are common to
   * both frames, so their difference is the probe's arrival alone; dividing it by
   * the arrival the command should have produced leaves the chain's own magnitude
   * and phase answer.
   *
   * The expected arrival is the inverse of what toGeneratorCorrections commands, so
   * the plant absorbs every modelling step that conversion takes: the generator
   * SUBTRACTS the commanded phasor, the partial reaches the ADC through the
   * response this class de-embeds against (H(f_p) for a b!=0 product, unit for the
   * b=0 harmonics the display already de-embedded), and the products are measured
   * against a fundamental that carries |H(f1)|:
   * expected = -c * H_p * |F1_raw| / |H(f1)|.
   *
   * A product whose arrival does not stand `clearanceDb` above the frame's noise
   * floor gets NaN - a noise reading must never become a plant to divide by.
   *
   * @param {import('../fft/fft-result.js').FftResult} baseline frame with no probe applied
   * @param {import('../fft/fft-result.js').FftResult} probe    frame with the probe partials applied
   * @param {IntermodGeneratorCorrections} command what probeCommand handed the generator
   * @param {(f: number) => [number, number]} calResponseAt loaded .frc response
   *        [magLin, phaseRad] at a frequency
   * @param {number} clearanceDb dB the arrival must clear the noise floor by
   * @returns {ProductPhasors}
   */
  measurePlant(baseline, probe, command, calResponseAt, clearanceDb) {
    const n = this.coefA.length;
    const gRe = new Float64Array(n);
    const gIm = new Float64Array(n);
    const hz = new Float64Array(n);
    gRe.fill(NaN);
    gIm.fill(NaN);
    const out = { coefA: this.coefA.slice(), coefB: this.coefB.slice(), re: gRe, im: gIm, hz };

    const f1 = probe.fundamentalHzRefined;
    const f2 = probe.fundamental2HzRefined;
    if (!(f1 > 0.0) || !(f2 > 0.0)) return out;
    const calF1 = calResponseAt(f1);
    const calF2Phase = calResponseAt(f2)[1];
    const fundLin = Math.hypot(probe.rawFundRe, probe.rawFundIm);
    if (!(fundLin > 0.0) || !(calF1[0] > 0.0)) return out;

    const base = this.measurePhasors(baseline, baseline.fundamentalHzRefined,
      baseline.fundamental2HzRefined,
      calResponseAt(baseline.fundamentalHzRefined)[1],
      calResponseAt(baseline.fundamental2HzRefined)[1]);
    const probed = this.measurePhasors(probe, f1, f2, calF1[1], calF2Phase);

    for (let g = 0; g < n; g++) {
      if (g >= command.ampRatios.length || !(command.ampRatios[g] > 0.0)) continue;
      if (!(base.hz[g] > 0.0) || !(probed.hz[g] > 0.0)) continue;
      const dRe = probed.re[g] - base.re[g];
      const dIm = probed.im[g] - base.im[g];
      const arrivalDb = 20.0 * Math.log10(Math.hypot(dRe, dIm));
      if (!(arrivalDb >= probe.avgNoiseFloorDbFs + clearanceDb)) continue;

      const cal = this.deEmbedResponse(g, probed.hz[g], calResponseAt);
      const hRe = cal[0] * Math.cos(cal[1]);
      const hIm = cal[0] * Math.sin(cal[1]);
      const cRe = command.ampRatios[g] * Math.cos(command.phiInits[g]);
      const cIm = command.ampRatios[g] * Math.sin(command.phiInits[g]);
      const scale = -fundLin / calF1[0];                 // the generator subtracts the command
      const eRe = scale * (cRe * hRe - cIm * hIm);
      const eIm = scale * (cRe * hIm + cIm * hRe);
      const den = eRe * eRe + eIm * eIm;
      if (!(den > 0.0)) continue;
      gRe[g] = (dRe * eRe + dIm * eIm) / den;            // measured / expected
      gIm[g] = (dIm * eRe - dRe * eIm) / den;
      hz[g] = probed.hz[g];
    }
    return out;
  }

  /** Installs a measured plant. An entry that is not finite (no trustworthy probe
   *  for that product) or exactly zero keeps the identity, so such a product is
   *  corrected exactly as it was before any probe ran.
   *  @param {ProductPhasors} plant */
  setPlant(plant) {
    for (let g = 0; g < this.plantRe.length && g < plant.re.length; g++) {
      const re = plant.re[g];
      const im = plant.im[g];
      if (!Number.isFinite(re) || !Number.isFinite(im)) continue;
      if (re === 0.0 && im === 0.0) continue;
      this.plantRe[g] = re;
      this.plantIm[g] = im;
    }
  }

  /**
   * The accumulated phasor as it LEAVES the accumulator: divided by the measured
   * plant, so the command answers the chain's own magnitude and phase instead of
   * assuming a perfect one. With the identity plant the division is by 1 + 0j - the
   * accumulator passes through unchanged, bit for bit.
   *
   * Public here because the .dpd writer is its own module (io/dpd.js) rather than a
   * method on this class as on the desktop, and a saved file must reproduce what
   * the generator was actually running - so the plant division stays defined in
   * exactly one place.
   *
   * @param {number} g grid slot
   * @returns {[number, number]} the plant-divided [re, im]
   */
  correctedPhasor(g) {
    const gRe = this.plantRe[g];
    const gIm = this.plantIm[g];
    const den = gRe * gRe + gIm * gIm;
    if (!(den > 0.0)) return [this.accRe[g], this.accIm[g]];
    return [(this.accRe[g] * gRe + this.accIm[g] * gIm) / den,
      (this.accIm[g] * gRe - this.accRe[g] * gIm) / den];
  }

  /**
   * The chain response product `g` is de-embedded against on the way out: the
   * loaded .frc at its frequency, or unity for a b=0 harmonic the display already
   * de-embedded. The single definition of that convention - the conversions and the
   * plant measurement all read it here, so none of them can drift from the others.
   *
   * @param {number} g grid slot
   * @param {number} freqHz the product's frequency
   * @param {(f: number) => [number, number]} calResponseAt
   * @returns {[number, number]} [magLin, phaseRad]
   */
  deEmbedResponse(g, freqHz, calResponseAt) {
    return this.coefB[g] === 0 ? UNIT_RESPONSE : calResponseAt(freqHz);
  }

  /** True once at least one product has accumulated a non-zero correction. */
  hasCorrections() {
    for (let g = 0; g < this.accRe.length; g++) {
      if (this.accRe[g] !== 0.0 || this.accIm[g] !== 0.0) return true;
    }
    return false;
  }

  /**
   * Converts the accumulated ABSOLUTE (ADC-dBFS) phasors to the generator's dual-
   * tone compensation quad - applying HERE (on the values reaching the DDS): the
   * .frc de-embed (÷H, −argH) for every b≠0 product (b=0 harmonics took the
   * display de-embed already -> unit response), the ADC-dBFS -> Vrms conversion, and
   * the division by the DAC F1 fundamental. Products below the gate are dropped.
   *
   * @param {(f: number) => [number, number]} calResponseAt .frc response [magLin, phaseRad]
   * @param {number} adcFsVoltageRms ADC full-scale (Vrms)
   * @param {number} dacFundamentalVrms DAC F1 fundamental output (Vrms)
   * @returns {IntermodGeneratorCorrections}
   */
  toGeneratorCorrections(calResponseAt, adcFsVoltageRms, dacFundamentalVrms) {
    let valid = 0;
    for (let g = 0; g < this.accRe.length; g++) {
      if (this.accRe[g] !== 0.0 || this.accIm[g] !== 0.0) valid++;
    }
    const amp = new Float64Array(valid);
    const a = new Int32Array(valid);
    const b = new Int32Array(valid);
    const phi = new Float64Array(valid);
    let i = 0;
    for (let g = 0; g < this.accRe.length; g++) {
      if (this.accRe[g] === 0.0 && this.accIm[g] === 0.0) continue;
      const z = this.correctedPhasor(g);                           // ÷ measured plant G(f_p)
      const cal = this.deEmbedResponse(g, this.freqHz[g], calResponseAt);
      const magDe = Math.hypot(z[0], z[1]) / (cal[0] > 0.0 ? cal[0] : 1.0);
      const vP = magDe * adcFsVoltageRms;                          // ADC dBFS -> absolute Vrms
      amp[i] = dacFundamentalVrms > 0.0 ? vP / dacFundamentalVrms : 0.0;   // ratio to DAC F1
      phi[i] = Math.atan2(z[1], z[0]) - cal[1];
      a[i] = this.coefA[g];
      b[i] = this.coefB[g];
      i++;
    }
    return { ampRatios: amp, coefA: a, coefB: b, phiInits: phi };
  }

  /** Deep copy - lets the wizard snapshot the best-THD round's accumulator while
   *  the loop keeps updating the live one. The plant travels with the copy: the
   *  retained snapshot is what gets saved, and it must convert to the generator
   *  exactly as the live accumulator did. */
  copy() {
    return new IntermodCompensation(this.coefA, this.coefB, this.accRe, this.accIm,
      this.freqHz, this.plantRe, this.plantIm);
  }
}

/** Builds the fixed correction grid: F1 harmonics (h,0) and F2 harmonics (0,h) up
 *  to the harmonic cap, plus the low-order intermodulation comb. Order kept ≤
 *  INTERMOD_ORDER_CAP for the b≠0 products so each is one the analyzer de-rotated. */
function buildGrid(maxHarmonics) {
  const g = [];
  const hMax = Math.max(2, maxHarmonics);
  for (let h = 2; h <= hMax; h++) g.push([h, 0]);                            // F1 harmonics
  for (let h = 2; h <= Math.min(hMax, INTERMOD_ORDER_CAP); h++) g.push([0, h]); // F2 harmonics
  // Intermod comb (|a|+|b| ≤ INTERMOD_ORDER_CAP): 2nd, 3rd and 5th order.
  const intermod = [
    [1, 1], [-1, 1],                       // f1+f2, f2−f1            (2nd order)
    [2, -1], [-1, 2], [2, 1], [1, 2],      // 2f1−f2, 2f2−f1, 2f1+f2, f1+2f2 (3rd)
    [3, -2], [-2, 3],                      // 3f1−2f2, 3f2−2f1        (5th order)
  ];
  for (const ab of intermod) g.push(ab);
  return g;
}

/** Index of a b≠0 product in the analyzer's de-rotated grid (so the raw phasor at
 *  rawPeak[i] / bin at imdProductBin[i] can be read), or -1 when the analyzer
 *  didn't de-rotate it this tick. */
function productIndex(r, a, b) {
  const pa = r.imdProductA, pb = r.imdProductB;
  if (pa == null) return -1;
  for (let i = 0; i < pa.length; i++) {
    if (pa[i] === a && pb[i] === b) return i;
  }
  return -1;
}
