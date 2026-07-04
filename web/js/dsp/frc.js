/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Render-time frequency-response de-embed primitives — a faithful port of the
// per-frequency helpers in org.edgo.audio.measure.dsp.FreqRespCalHelper that
// operate on an already-loaded FreqRespCalibration ({freqs, magLin, phaseRad},
// see io/frc.js loadFrc): interpolate (log-frequency H(f) lookup), deEmbed
// (divide one complex phasor by H), divideInPlace (de-embed a whole sweep),
// and calResponseAt (the {magLin, phaseRad} accessor the DAC-predistortion and
// FFT-compensation paths consume).
//
// CRITICAL: this is purely a RENDER-TIME correction of the measured spectrum /
// sweep — it has NOTHING to do with the generator. The accumulator stays RAW;
// the .frc is subtracted on the way to the screen and the measurement table.

/**
 * Interpolates the calibration's H(f) at an arbitrary frequency. Uses
 * log-frequency as the interpolation variable, magnitude in dB, and phase via
 * complex unit-phasor interpolation to handle ±180° wraps at notches.
 *
 * @param {{freqs:Float64Array, magLin:Float64Array, phaseRad:Float64Array}} cal
 * @param {number} freq
 * @returns {[number, number]} [magLin, phaseRad]
 */
export function interpolate(cal, freq) {
  let lo = 0, hi = cal.freqs.length - 1;
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
 * Divides a single complex phasor (re, im) by the calibration response
 * H = magLin·e^{j·phaseRad}, de-embedding BOTH magnitude and phase
 * (X/H = X·e^{−j·phaseRad}/magLin). A non-positive magLin (no cal at this
 * frequency) returns the phasor unchanged.
 *
 * @returns {[number, number]} [re, im] of X/H
 */
export function deEmbed(re, im, magLin, phaseRad) {
  if (!(magLin > 0.0)) return [re, im];
  const c = Math.cos(phaseRad), s = Math.sin(phaseRad);
  return [(re * c + im * s) / magLin, (im * c - re * s) / magLin];
}

/**
 * Convenience accessor returning H(freq) as {magLin, phaseRad} — the
 * `calResponseAt` callback shape the DAC-predistortion and FFT-compensation
 * paths inject. Returns unity (no correction) when `cal` is null.
 *
 * @param {?{freqs:Float64Array, magLin:Float64Array, phaseRad:Float64Array}} cal
 * @param {number} freq
 * @returns {{magLin:number, phaseRad:number}}
 */
export function calResponseAt(cal, freq) {
  if (cal == null) return { magLin: 1.0, phaseRad: 0.0 };
  const [magLin, phaseRad] = interpolate(cal, freq);
  return { magLin, phaseRad };
}

/**
 * Mutates `measured` in place: divides every magnitude by the calibration
 * interpolated at the same frequency and subtracts the cal phase
 * (phase_out = phase_meas − phase_cal), so the result represents the
 * device-under-test alone. Faithful port of FreqRespCalHelper.divideInPlace.
 *
 * @param {{freqs:Float64Array, magLin:Float64Array, phaseRad:Float64Array}} measured
 * @param {{freqs:Float64Array, magLin:Float64Array, phaseRad:Float64Array}} calibration
 */
export function divideInPlace(measured, calibration) {
  for (let i = 0; i < measured.freqs.length; i++) {
    const [calMag, calPhi] = interpolate(calibration, measured.freqs[i]);
    if (calMag > 0.0) measured.magLin[i] /= calMag;
    measured.phaseRad[i] -= calPhi;
  }
}
