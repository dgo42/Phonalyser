/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.dsp.FreqRespCalHelper#computeFromLogSweep
// (and the FreqRespCalibration value type from
// org.edgo.audio.measure.dsp.FreqRespCalibration), plus the output-grid helpers
// FreqRespCalHelper#binAlignedFreqs / #logSpacedFreqs.
//
// Reconstructs the per-frequency filter calibration H(f) from a captured
// recording of a Farina log-sweep by direct frequency-domain deconvolution
// H = Y / X:
//   1. Build the reference X with the same per-side Hann (Tukey) fade the
//      SignalGenerator applied, placed after leadInSamples zeros; zero-pad both
//      X and Y to M = nextPow2(max(yRec.length, leadIn + sweepRef.length)).
//   2. FFT both; per single-sided bin k = 0..M/2 form H = Y·conj(X)/|X|².
//   3. IFFT H to the impulse response, locate the main peak with sub-sample
//      quadratic refinement → transport delay; remove it as a linear-phase term
//      exp(+j·2π·k·delay/M) (advancing H so the IR sits at sample 0).
//   4. Sample H at each requested frequency by linear interpolation between the
//      two straddling complex bins; magnitude = hypot, phase = atan2.
//   5. Normalise magnitude by amplitudeVRms / adcFsVoltageRms so a calibrated
//      unity-gain loopback reads 1.0 (= 0 dB).
//   6. Savitzky-Golay smooth (window 7, order 3): magnitude directly, phase in
//      (sin, cos) space recombined via atan2 to survive ±π wraps at notches.
//
// The Java IR-domain time gate (USE_IR_GATING) is compiled off upstream, so it
// is omitted here. The desktop uses JTransforms' packed real FFT; this port
// runs the equivalent computation over the web's complex fft/ifft (same IEEE-754
// binary64 arithmetic), which is mathematically identical bin-for-bin.

import { fft, ifft } from '../dsp/fft.js';
import { savGolCoefficients, applySavGol } from '../dsp/savgol.js';

/** Compile-time switch mirror: Savitzky-Golay smoothing of the output arrays. */
const USE_SAVGOL_FILTER = true;
/** SG window length in output samples (odd, > SAVGOL_ORDER). */
const SAVGOL_WINDOW = 7;
/** SG polynomial order. */
const SAVGOL_ORDER = 3;

/**
 * In-memory filter calibration: a sparse set of measured magnitude/phase points
 * at strictly-ascending log-spaced frequencies. Mirrors
 * org.edgo.audio.measure.dsp.FreqRespCalibration.
 *
 * @typedef {Object} FreqRespCalibration
 * @property {Float64Array} freqs    length N, strictly ascending Hz
 * @property {Float64Array} magLin   |H| linear, passband normalised to ~1.0
 * @property {Float64Array} phaseRad  phase in radians
 */

/** Smallest power of two ≥ x (≥ 1). Mirrors fft.MathUtil#nextPow2. */
function nextPow2(x) {
  if (x <= 1) return 1;
  let p = 1;
  while (p < x) p <<= 1;
  return p;
}

/**
 * Output frequency grid sampled EXACTLY at the deconvolution's FFT bin centres
 * (k·binHz) across [startHz, stopHz], so {@link computeFromLogSweep} reads each
 * bin with fractional offset 0 — no phase-sensitive complex interpolation
 * between bins (which facets the trace into a frame-to-frame comb). binHz must
 * be sampleRate / nextPow2(captureLength) — the spacing of the FFT
 * computeFromLogSweep builds.
 *
 * When the band spans more than maxPoints bins (a wide band on a fine grid) the
 * between-bin wiggle is sub-point anyway and a full bin grid would explode the
 * point count, so it falls back to {@link logSpacedFreqs} capped at maxPoints.
 * Faithful port of FreqRespCalHelper#binAlignedFreqs.
 *
 * @param {number} startHz   band start (Hz)
 * @param {number} stopHz    band stop (Hz)
 * @param {number} binHz     deconvolution FFT bin spacing (Hz)
 * @param {number} maxPoints output-grid point-count ceiling
 * @returns {Float64Array} ascending frequency grid (Hz)
 */
export function binAlignedFreqs(startHz, stopHz, binHz, maxPoints) {
  const k0 = Math.max(1, Math.ceil(startHz / binHz));
  const k1 = Math.floor(stopHz / binHz);
  const n = k1 - k0 + 1;
  if (n < 2 || n > maxPoints) {
    return logSpacedFreqs(startHz, stopHz, Math.min(maxPoints, Math.max(2, n)));
  }
  const freqs = new Float64Array(n);
  for (let i = 0; i < n; i++) {
    freqs[i] = (k0 + i) * binHz;
  }
  return freqs;
}

/**
 * Log-spaced (geometric) output grid: {@code points} points from startHz to
 * stopHz inclusive. Faithful port of FreqRespCalHelper#logSpacedFreqs.
 *
 * @param {number} startHz band start (Hz, > 0)
 * @param {number} stopHz  band stop (Hz, > 0)
 * @param {number} points  number of grid points (≥ 2)
 * @returns {Float64Array} ascending frequency grid (Hz)
 */
export function logSpacedFreqs(startHz, stopHz, points) {
  const freqs = new Float64Array(points);
  const logStart = Math.log(startHz);
  const logEnd = Math.log(stopHz);
  for (let i = 0; i < points; i++) {
    const t = i / (points - 1);
    freqs[i] = Math.exp(logStart + (logEnd - logStart) * t);
  }
  return freqs;
}

/**
 * Reconstructs H(f) from the captured Farina log-sweep recording by
 * frequency-domain deconvolution H = Y / X, removing the DAC↔ADC transport
 * delay and normalising the passband to ~1.0 (= 0 dB).
 *
 * @param {ArrayLike<number>} yRec        captured recording samples
 * @param {ArrayLike<number>} sweepRef    unit-amplitude reference sweep X(t) (unwindowed)
 * @param {number} leadInSamples          silent samples before the sweep in the playback timeline
 * @param {number} sampleRate             sample rate (Hz)
 * @param {ArrayLike<number>} freqs       output frequency grid (Hz, ascending)
 * @param {number} amplitudeVRms          DAC drive level (V RMS)
 * @param {number} adcFsVoltageRms        ADC full-scale level (V RMS); ≤ 0 disables magnitude normalisation
 * @param {number} [fadeSamples=0]        per-side Hann fade length applied to the reference (match the player)
 * @param {boolean} [applySavGolFilter=true] false skips the final Savitzky-Golay output smoothing.
 *   Java's applySavGol overload parameter (renamed here so it doesn't shadow the imported
 *   applySavGol kernel). The Tune-notch wizard disables it: its bin-aligned output grid is
 *   coarse (a few Hz per point), so the fixed SAVGOL_WINDOW-point SG window spans ~15-20 Hz
 *   and rounds the bottom off a deep, narrow notch null (it read ≈9 dB shallow). A dense
 *   main-pane grid keeps SG on, where the window is sub-Hz and harmless.
 * @returns {FreqRespCalibration} the per-point calibration on the {@code freqs} grid
 */
export function computeFromLogSweep(
  yRec, sweepRef, leadInSamples, sampleRate, freqs,
  amplitudeVRms, adcFsVoltageRms, fadeSamples = 0, applySavGolFilter = true,
) {
  const xLen = leadInSamples + sweepRef.length;
  const needed = Math.max(yRec.length, xLen);
  const M = nextPow2(needed);

  // Build the reference X with the same per-side Hann fade the SignalGenerator
  // applies to playback, so Y and X carry matching boundary shapes and the
  // start/stop leakage cancels in the Y/X division.
  const n = sweepRef.length;
  const fS = Math.max(0, Math.min(fadeSamples, Math.trunc(n / 2)));
  const xRe = new Float64Array(M);
  const xIm = new Float64Array(M);
  for (let i = 0; i < n; i++) {
    let env;
    if (fS > 0 && i < fS) {
      env = 0.5 * (1.0 - Math.cos((Math.PI * i) / fS));
    } else if (fS > 0 && i >= n - fS) {
      const back = n - 1 - i;
      env = 0.5 * (1.0 - Math.cos((Math.PI * back) / fS));
    } else {
      env = 1.0;
    }
    xRe[leadInSamples + i] = sweepRef[i] * env;
  }
  const yRe = new Float64Array(M);
  const yIm = new Float64Array(M);
  const yLim = Math.min(yRec.length, M);
  for (let i = 0; i < yLim; i++) yRe[i] = yRec[i];

  fft(xRe, xIm);
  fft(yRe, yIm);

  // Single-sided H = Y·conj(X) / |X|², bins 0..M/2.
  const halfBins = M / 2;
  const hRe = new Float64Array(halfBins + 1);
  const hIm = new Float64Array(halfBins + 1);
  for (let k = 0; k <= halfBins; k++) {
    const xr = xRe[k], xi = xIm[k];
    const yr = yRe[k], yi = yIm[k];
    const xMag2 = xr * xr + xi * xi;
    if (xMag2 <= 0.0) {
      hRe[k] = 0.0;
      hIm[k] = 0.0;
    } else {
      hRe[k] = (yr * xr + yi * xi) / xMag2;
      hIm[k] = (yi * xr - yr * xi) / xMag2;
    }
  }

  // Impulse response via Hermitian-mirrored inverse FFT; locate the main peak.
  const delaySamples = irPeakDelay(hRe, hIm, halfBins, M);

  // Remove the transport delay as a linear-phase term exp(+j·2π·k·delay/M).
  for (let k = 0; k <= halfBins; k++) {
    const theta = (2.0 * Math.PI * k * delaySamples) / M;
    const c = Math.cos(theta), s = Math.sin(theta);
    const re = hRe[k] * c - hIm[k] * s;
    const im = hRe[k] * s + hIm[k] * c;
    hRe[k] = re;
    hIm[k] = im;
  }

  // Sample H(f) on the output grid: linear interpolation of the complex bins.
  const nPoints = freqs.length;
  const magLin = new Float64Array(nPoints);
  const phaseRad = new Float64Array(nPoints);
  const binHz = sampleRate / M;
  for (let i = 0; i < nPoints; i++) {
    const bin = freqs[i] / binHz;
    let k0 = Math.floor(bin);
    let k1 = k0 + 1;
    if (k0 < 0) k0 = 0;
    if (k0 > halfBins) k0 = halfBins;
    if (k1 > halfBins) k1 = halfBins;
    const frac = bin - Math.floor(bin);
    const re = hRe[k0] * (1.0 - frac) + hRe[k1] * frac;
    const im = hIm[k0] * (1.0 - frac) + hIm[k1] * frac;
    magLin[i] = Math.hypot(re, im);
    phaseRad[i] = Math.atan2(im, re);
  }

  // Normalisation: |Y/X| at unity loopback = amplitudeVRms / adcFsVoltageRms.
  const adcPeakNormalised = adcFsVoltageRms > 0.0 ? amplitudeVRms / adcFsVoltageRms : 0.0;
  if (adcPeakNormalised > 0.0) {
    for (let i = 0; i < nPoints; i++) magLin[i] /= adcPeakNormalised;
  }

  // Savitzky-Golay smoothing of the final arrays. Magnitude smoothed directly;
  // phase smoothed in (sin, cos) space and recombined via atan2 to handle the
  // ±π wraparound at notches. Boundary samples use reflection.
  if (USE_SAVGOL_FILTER && applySavGolFilter && nPoints >= SAVGOL_WINDOW) {
    const coeffs = savGolCoefficients(SAVGOL_WINDOW, SAVGOL_ORDER);
    const sinPh = new Float64Array(nPoints);
    const cosPh = new Float64Array(nPoints);
    for (let i = 0; i < nPoints; i++) {
      sinPh[i] = Math.sin(phaseRad[i]);
      cosPh[i] = Math.cos(phaseRad[i]);
    }
    const smoothMag = new Float64Array(nPoints);
    const smoothSin = new Float64Array(nPoints);
    const smoothCos = new Float64Array(nPoints);
    for (let i = 0; i < nPoints; i++) {
      smoothMag[i] = applySavGol(magLin, i, coeffs);
      smoothSin[i] = applySavGol(sinPh, i, coeffs);
      smoothCos[i] = applySavGol(cosPh, i, coeffs);
    }
    for (let i = 0; i < nPoints; i++) {
      magLin[i] = smoothMag[i];
      phaseRad[i] = Math.atan2(smoothSin[i], smoothCos[i]);
    }
  }

  return { freqs, magLin, phaseRad };
}

/**
 * Inverse-FFTs the single-sided H spectrum (Hermitian-mirrored to full length)
 * and returns the transport delay in samples — the |IR| peak index with
 * sub-sample quadratic refinement. Mirrors the IR-peak block of
 * FreqRespCalHelper#computeFromLogSweep.
 *
 * @param {Float64Array} hRe single-sided real part, length halfBins+1
 * @param {Float64Array} hIm single-sided imag part, length halfBins+1
 * @param {number} halfBins  M/2
 * @param {number} M         FFT length (power of two)
 * @returns {number} delay in samples (fractional)
 */
function irPeakDelay(hRe, hIm, halfBins, M) {
  const re = new Float64Array(M);
  const im = new Float64Array(M);
  for (let k = 0; k <= halfBins; k++) {
    re[k] = hRe[k];
    im[k] = hIm[k];
  }
  // Hermitian symmetry for the negative-frequency half so the IR is real.
  for (let k = 1; k < halfBins; k++) {
    re[M - k] = hRe[k];
    im[M - k] = -hIm[k];
  }
  ifft(re, im);

  let peakIdx = 0;
  let peakVal = 0.0;
  for (let i = 0; i < M; i++) {
    const v = Math.abs(re[i]);
    if (v > peakVal) { peakVal = v; peakIdx = i; }
  }
  let delaySamples = peakIdx;
  if (peakIdx > 0 && peakIdx < M - 1) {
    const yM = Math.abs(re[peakIdx - 1]);
    const y0 = Math.abs(re[peakIdx]);
    const yP = Math.abs(re[peakIdx + 1]);
    const denom = yM - 2.0 * y0 + yP;
    if (denom !== 0.0) {
      delaySamples = peakIdx + (0.5 * (yM - yP)) / denom;
    }
  }
  return delaySamples;
}

/**
 * Interpolates the calibration's H(f) at an arbitrary frequency, in
 * log-frequency: magnitude in dB, phase via complex unit-phasor interpolation
 * (handles ±180° wraps at notches). Faithful port of
 * FreqRespCalHelper#interpolate.
 *
 * @param {FreqRespCalibration} cal the calibration
 * @param {number} freq            frequency (Hz)
 * @returns {[number, number]} [magLin, phaseRad]
 */
export function interpolate(cal, freq) {
  let lo = 0;
  let hi = cal.freqs.length - 1;
  if (freq <= cal.freqs[lo]) return [cal.magLin[lo], cal.phaseRad[lo]];
  if (freq >= cal.freqs[hi]) return [cal.magLin[hi], cal.phaseRad[hi]];
  while (hi - lo > 1) {
    const mid = (lo + hi) >>> 1;
    if (cal.freqs[mid] <= freq) lo = mid; else hi = mid;
  }
  const f0 = cal.freqs[lo], f1 = cal.freqs[hi];
  const t = (Math.log(freq) - Math.log(f0)) / (Math.log(f1) - Math.log(f0));

  const m0 = cal.magLin[lo], m1 = cal.magLin[hi];
  const db0 = m0 > 0.0 ? 20.0 * Math.log10(m0) : -300.0;
  const db1 = m1 > 0.0 ? 20.0 * Math.log10(m1) : -300.0;
  const db = db0 + (db1 - db0) * t;
  const mag = Math.pow(10.0, db / 20.0);

  const re0 = Math.cos(cal.phaseRad[lo]), im0 = Math.sin(cal.phaseRad[lo]);
  const re1 = Math.cos(cal.phaseRad[hi]), im1 = Math.sin(cal.phaseRad[hi]);
  const reT = re0 + (re1 - re0) * t;
  const imT = im0 + (im1 - im0) * t;
  const phi = (reT === 0.0 && imT === 0.0) ? cal.phaseRad[lo] : Math.atan2(imT, reT);

  return [mag, phi];
}

/**
 * Mutates {@code measured} in place: divides every magnitude by the calibration
 * interpolated at the same frequency, and subtracts the calibration phase, so
 * the resulting curve represents the device-under-test alone. Faithful port of
 * FreqRespCalHelper#divideInPlace.
 *
 * @param {FreqRespCalibration} measured    the measured curve (mutated)
 * @param {FreqRespCalibration} calibration the reference calibration
 */
export function divideInPlace(measured, calibration) {
  for (let i = 0; i < measured.freqs.length; i++) {
    const [calMag, calPhi] = interpolate(calibration, measured.freqs[i]);
    if (calMag > 0.0) measured.magLin[i] /= calMag;
    measured.phaseRad[i] -= calPhi;
  }
}
