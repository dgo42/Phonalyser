/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of the FFT-spectrum frequency-response compensation in
// org.edgo.audio.measure.dsp.FreqRespCalHelper (applyCompensationInPlace,
// correctToneLobe, capturePreCorrectionPeaks, computeOverlay).
//
// RENDER-TIME .frc de-embed of an averaged FftResult: the raw accumulated
// spectrum is divided by the loaded H(f) calibration on the way to the screen
// AND to the THD/IMD measurement table, then FftAnalyzer.recomputeStats
// re-derives fundamental / harmonics / THD / SNR / SINAD / THD+N from the
// corrected spectrum. The accumulator itself stays RAW (the caller passes a
// deepCopy()); the generator is untouched.

import { interpolate } from '../dsp/frc.js';
import { ToneLobeLift } from '../dsp/tone-lobe-lift.js';

/** Shared per-tone lobe lift (floor estimate + data-derived lobe extent +
 *  power-domain proportional scale), mirroring the Java static LOBE. */
const LOBE = new ToneLobeLift();

/**
 * Mutates `r` in place: divides every FFT bin (within the swept range) by H(f)
 * interpolated from the per-point filter calibration, then calls
 * analyzer.recomputeStats(r) so the fundamental level, harmonic table, THD,
 * THD+N, SNR, and noise stats all reflect the corrected spectrum.
 *
 * @param {import('./fft-result.js').FftResult} r           result to correct (mutated)
 * @param {{freqs:Float64Array, magLin:Float64Array, phaseRad:Float64Array}} cal
 * @param {boolean} correctAllBins  true = "with noise" (divide every bin);
 *                                   false = per-tone lobe lift (dots + THD only).
 * @param {import('./fft-analyzer.js').FftAnalyzer} analyzer  for recomputeStats.
 */
export function applyCompensationInPlace(r, cal, correctAllBins, analyzer) {
  // Snapshot the pre-correction fundamental/harmonic peaks for the chart's blue "before-cal"
  // dots BEFORE the de-embed mutates the spectrum (Java FreqRespCalHelper captures here too).
  r.preCorrectionPeaks = capturePreCorrectionPeaks(r);
  const half = r.fftSize / 2;
  const binWidth = r.sampleRate / r.fftSize;
  const fLo = cal.freqs[0];
  const fHi = cal.freqs[cal.freqs.length - 1];

  const oldFundMag = Math.hypot(r.re[r.fundamentalBin], r.im[r.fundamentalBin]);
  const linPerMag = oldFundMag > 0.0 ? r.fundamentalLinear / oldFundMag : 0.0;

  if (correctAllBins) {
    // "With noise": every in-range bin divided by H at its OWN frequency.
    for (let k = 1; k <= half; k++) {
      const f = k * binWidth;
      if (f < fLo || f > fHi) continue;
      const h = interpolate(cal, f);
      const hMag = h[0];
      if (hMag <= 0.0) continue;
      const hRe = hMag * Math.cos(h[1]), hIm = hMag * Math.sin(h[1]);
      const hMagSq = hMag * hMag;
      const xRe = r.re[k], xIm = r.im[k];
      const zRe = (xRe * hRe + xIm * hIm) / hMagSq;
      const zIm = (xIm * hRe - xRe * hIm) / hMagSq;
      r.re[k] = zRe;
      r.im[k] = zIm;
      const newAmpLin = Math.hypot(zRe, zIm) * linPerMag;
      r.amplitudeDbFs[k] = newAmpLin > 1e-15 ? 20.0 * Math.log10(newAmpLin) : -300.0;
      r.phaseDeg[k] = radToDeg(Math.atan2(zIm, zRe));
    }
  } else {
    // Per-TONE lobe lift: each tone's whole main lobe lifted by ONE cal value at
    // the tone frequency, proportionally above the local noise floor.
    const done = new Uint8Array(half + 1);
    const fundHz = (Number.isFinite(r.fundamentalHzRefined) && r.fundamentalHzRefined > 0.0)
      ? r.fundamentalHzRefined : r.fundamentalBin * binWidth;
    correctToneLobe(r, cal, fundHz, half, binWidth, linPerMag, fLo, fHi, done);
    for (let h = 0; h < r.harmonicCount; h++) {
      if (r.harmonicBins[h] > 0) {
        correctToneLobe(r, cal, r.harmonicHz[h], half, binWidth, linPerMag, fLo, fHi, done);
      }
    }
    // Second tone (dual-tone) is a fundamental, not a harmonic of F1.
    if (!Number.isNaN(r.fundamental2HzRefined) && r.fundamental2HzRefined > 0.0) {
      correctToneLobe(r, cal, r.fundamental2HzRefined, half, binWidth, linPerMag, fLo, fHi, done);
    }
  }

  analyzer.recomputeStats(r);
}

/** Applies the cal to a tone's WHOLE main lobe with ONE value taken at the
 *  tone's frequency, in the power domain so the lobe keeps its shape where it
 *  stands above the noise yet tapers smoothly back onto the floor at its wings.
 *  Phase is rotated by −argH. Lobe extent is data-derived (ToneLobeLift). */
function correctToneLobe(r, cal, toneHz, half, binWidth, linPerMag, fLo, fHi, done) {
  if (!(toneHz > 0.0) || toneHz < fLo || toneHz > fHi) return 0;
  const peak = Math.round(toneHz / binWidth);
  if (peak < 1 || peak > half) return 0;
  const h = interpolate(cal, toneHz);
  if (h[0] <= 0.0) return 0;
  const invMag = 1.0 / h[0];                            // |1/H| at the tone freq
  const cosC = Math.cos(h[1]), sinC = Math.sin(h[1]);   // ·exp(−j·argH)

  const mag = (k) => Math.hypot(r.re[k], r.im[k]);
  const floor = LOBE.localFloor(mag, peak, half);
  const edges = LOBE.lobeBins(mag, peak, half, floor);
  const peakMag = mag(peak);

  let n = 0;
  for (let k = edges[0]; k <= edges[1]; k++) {
    if (k < 1 || k > half || done[k]) continue;
    const xRe = r.re[k], xIm = r.im[k];
    const m = Math.hypot(xRe, xIm);
    const newMag = LOBE.stretch(m, floor, peakMag, invMag);
    const scale = m > 0.0 ? newMag / m : 0.0;
    const zRe = (xRe * cosC + xIm * sinC) * scale;      // X·exp(−j·argH)·scale
    const zIm = (xIm * cosC - xRe * sinC) * scale;
    r.re[k] = zRe;
    r.im[k] = zIm;
    const newAmpLin = newMag * linPerMag;
    r.amplitudeDbFs[k] = newAmpLin > 1e-15 ? 20.0 * Math.log10(newAmpLin) : -300.0;
    r.phaseDeg[k] = radToDeg(Math.atan2(zIm, zRe));
    done[k] = 1;
    n++;
  }
  return n;
}

/**
 * Snapshots the pre-correction fundamental + harmonic peak levels so the chart
 * can draw blue "before-cal" dots alongside the red "after-cal" dots. Returns
 * [freqs, dbFs] with index 0 = fundamental, 1..harmonicCount = H2..HN.
 *
 * @param {import('./fft-result.js').FftResult} result
 * @returns {[Float64Array, Float64Array]}
 */
export function capturePreCorrectionPeaks(result) {
  const count = 1 + result.harmonicCount;
  const freqs = new Float64Array(count);
  const dbFs = new Float64Array(count);
  freqs[0] = result.fundamentalHz;
  dbFs[0] = result.fundamentalDbFs;
  for (let h = 0; h < result.harmonicCount; h++) {
    if (result.harmonicBins[h] > 0) {
      freqs[1 + h] = result.harmonicHz[h];
      dbFs[1 + h] = result.harmonicDbFs[h];
    }
  }
  return [freqs, dbFs];
}

/**
 * Builds an inverted-cal overlay for the FFT chart. Returns [freqs, dbFs] keyed
 * to the H2 reference, or null when no H2 reference is available.
 *
 * @param {{freqs:Float64Array, magLin:Float64Array, phaseRad:Float64Array}} cal
 * @param {import('./fft-result.js').FftResult} result
 * @returns {?[Float64Array, Float64Array]}
 */
export function computeOverlay(cal, result) {
  if (result.harmonicCount === 0 || result.harmonicBins[0] <= 0) return null;
  const h2Freq = result.harmonicHz[0];
  const h2DbFs = result.harmonicDbFs[0];
  if (!(h2Freq > 0.0)) return null;
  const h = interpolate(cal, h2Freq);
  const calDbAtH2 = h[0] > 0.0 ? 20.0 * Math.log10(h[0]) : -300.0;
  const offset = h2DbFs + calDbAtH2;
  const freqs = cal.freqs.slice();
  const dbFs = new Float64Array(cal.magLin.length);
  for (let i = 0; i < cal.magLin.length; i++) {
    const calDb = cal.magLin[i] > 0.0 ? 20.0 * Math.log10(cal.magLin[i]) : -300.0;
    dbFs[i] = -calDb + offset;
  }
  return [freqs, dbFs];
}

/** Math.toDegrees. */
function radToDeg(rad) { return rad * (180.0 / Math.PI); }
