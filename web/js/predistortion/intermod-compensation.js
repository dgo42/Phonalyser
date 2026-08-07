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

export class IntermodCompensation {
  /** @param {number} maxHarmonics highest per-tone harmonic in the grid. */
  constructor(maxHarmonics) {
    // The full-array copy path passes the six arrays directly (see copy()).
    if (arguments.length === 6) {
      const [coefA, coefB, accRe, accIm, freqHz] = arguments;
      this.coefA = coefA.slice();
      this.coefB = coefB.slice();
      this.accRe = accRe.slice();
      this.accIm = accIm.slice();
      this.freqHz = freqHz.slice();
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
    if (r.re == null || r.im == null) return;
    const fundBin = r.fundamentalBin;
    if (fundBin <= 0 || fundBin >= r.re.length || !(f1Hz > 0.0) || !(f2Hz > 0.0)) return;

    const f1Re = !Number.isNaN(r.rawFundRe) ? r.rawFundRe : r.re[fundBin];
    const f1Im = !Number.isNaN(r.rawFundIm) ? r.rawFundIm : r.im[fundBin];
    const phi1 = Math.atan2(f1Im, f1Re) - calF1PhaseRad;        // F1 frame, notch phase removed
    // F2 is the (0,1) product in the analyzer's de-rotated grid.
    const f2Idx = productIndex(r, 0, 1);
    const phi2 = (f2Idx >= 0 && r.rawPeakRe != null && f2Idx < r.rawPeakRe.length)
      ? Math.atan2(r.rawPeakIm[f2Idx], r.rawPeakRe[f2Idx]) - calF2PhaseRad
      : phi1;

    for (let g = 0; g < this.coefA.length; g++) {
      const a = this.coefA[g], b = this.coefB[g];
      const fp = a * f1Hz + b * f2Hz;
      if (!(fp > 0.0)) continue;

      let magLin, phiRaw;
      if (b === 0) {
        const idx = a - 2;                         // harmonicBins[0] = 2nd harmonic
        if (r.harmonicBins == null || idx < 0 || idx >= r.harmonicCount) continue;
        const bin = r.harmonicBins[idx];
        if (bin <= 0 || bin >= r.re.length) continue;
        magLin = Math.pow(10.0, r.rawHarmonicDbFs(idx) / 20.0);   // absolute (lobe dBFS)
        phiRaw = Math.atan2(r.im[bin], r.re[bin]);                // display-de-embedded phase
      } else {
        const i = productIndex(r, a, b);
        if (i < 0 || r.rawPeakRe == null || i >= r.rawPeakRe.length) continue;
        magLin = Math.hypot(r.rawPeakRe[i], r.rawPeakIm[i]);      // absolute (conv-scaled dBFS-linear)
        phiRaw = Math.atan2(r.rawPeakIm[i], r.rawPeakRe[i]);      // raw phase (de-embed downstream)
      }
      const refPhase = a * (phi1 + Math.PI / 2.0) + b * (phi2 + Math.PI / 2.0);
      const phiInit = phiRaw - refPhase;
      this.accRe[g] += step * magLin * Math.cos(phiInit);
      this.accIm[g] += step * magLin * Math.sin(phiInit);
      this.freqHz[g] = fp;
    }
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
      const cal = this.coefB[g] === 0 ? UNIT_RESPONSE : calResponseAt(this.freqHz[g]);
      const magDe = Math.hypot(this.accRe[g], this.accIm[g]) / (cal[0] > 0.0 ? cal[0] : 1.0);
      const vP = magDe * adcFsVoltageRms;                          // ADC dBFS -> absolute Vrms
      amp[i] = dacFundamentalVrms > 0.0 ? vP / dacFundamentalVrms : 0.0;   // ratio to DAC F1
      phi[i] = Math.atan2(this.accIm[g], this.accRe[g]) - cal[1];
      a[i] = this.coefA[g];
      b[i] = this.coefB[g];
      i++;
    }
    return { ampRatios: amp, coefA: a, coefB: b, phiInits: phi };
  }

  /** Deep copy - lets the wizard snapshot the best-THD round's accumulator while
   *  the loop keeps updating the live one. */
  copy() {
    return new IntermodCompensation(this.coefA, this.coefB, this.accRe, this.accIm, this.freqHz, null);
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
