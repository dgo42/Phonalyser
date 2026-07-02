/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.gui.freqresp.RiaaCurve.
//
// Static evaluator for the RIAA equalisation curves. The record (encode)
// transfer function in the s-domain is
//
//     H_record(s) = (s·T1 + 1)(s·T3 + 1) / (s·T2 + 1)
//
// with the four standard time constants T1=3180µs, T2=318µs, T3=75µs, and the
// IEC subsonic high-pass T4=7950µs. All evaluations are normalised so the 1 kHz
// reading is 0 dB regardless of which flags are active. The playback (decode)
// curve is the vertical mirror of the record curve. IEC is applied by DIVIDING
// the record magnitude by the IEC single-pole high-pass magnitude (the inverse
// of how IEC enters playback), keeping record⁻¹ · playback = identity.

export const T1_SEC = 3180e-6;
export const T2_SEC = 318e-6;
export const T3_SEC = 75e-6;
export const T4_SEC = 7950e-6;

/** Reference frequency at which the normalised curve reads 0 dB. */
export const REF_HZ = 1000.0;

/**
 * Record-curve magnitude in dB before the 1 kHz normalisation step.
 *
 * @param {number} fHz frequency (Hz)
 * @param {boolean} iec apply the IEC subsonic high-pass amendment
 * @returns {number} record magnitude in dB
 */
function recordDb(fHz, iec) {
  const w = 2.0 * Math.PI * fHz;
  const wt1 = w * T1_SEC;
  const wt2 = w * T2_SEC;
  const wt3 = w * T3_SEC;
  let magSq = ((1.0 + wt1 * wt1) * (1.0 + wt3 * wt3)) / (1.0 + wt2 * wt2);
  if (iec) {
    const wt4 = w * T4_SEC;
    const hpSq = (wt4 * wt4) / (1.0 + wt4 * wt4);
    if (hpSq > 0.0) magSq /= hpSq;
  }
  return 10.0 * Math.log10(magSq);
}

/**
 * Magnitude of the configured RIAA curve at {@code fHz} in decibels, normalised
 * so |H(1 kHz)| = 0 dB regardless of which flag combination is active.
 *
 * @param {number} fHz     frequency (Hz); values ≤ 0 are clamped to 1e-9
 * @param {boolean} reverse false → playback (decode) curve; true → record (encode) curve
 * @param {boolean} iec     true → also apply the IEC subsonic high-pass
 * @returns {number} curve magnitude in dB
 */
export function evalDb(fHz, reverse, iec) {
  const f = fHz > 0.0 ? fHz : 1e-9;
  const db = recordDb(f, iec) - recordDb(REF_HZ, iec);
  return reverse ? db : -db;
}
