/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.dsp.DitherMath: TPDF dither depth
// (bits) to absolute level (dBV) and back. The dither is added to the
// peak-normalised sample (+-1 = the DAC's PEAK full-scale amplitude), and its
// RMS at b bits is 2^-(b-1)/sqrt(6) of that peak - so the dBV reference is
// the peak full-scale voltage itself, NOT the RMS full scale (/sqrt(2)),
// which would read about 3 dB low. Side-effect-free pure math.

/** dB per factor-of-10 amplitude - the dBV exponent scale. */
const DB_PER_DECADE = 20.0;

/** One TPDF bit is 6.0206 dB: each extra bit of depth halves the dither
 *  amplitude. Exported because a dBV-sized step or delta converts to bits by
 *  dividing by this. */
export const DITHER_DB_PER_BIT = 6.0206;

/** 20*log10(sqrt(6)) - how far the TPDF RMS sits below its one-bit peak. */
const TPDF_OFFSET_DB = 7.782;

/**
 * dBV of the DAC PEAK full-scale amplitude - the reference every dither level
 * is stated against (see the header).
 * @param {number} fsAmpl  DAC peak full-scale amplitude (V)
 * @returns {number}
 */
export function ditherFsDbv(fsAmpl) {
  return DB_PER_DECADE * Math.log10(fsAmpl);
}

/**
 * dBV of the TPDF dither at the given depth against the peak full scale.
 * @param {number} bits    dither depth (>= 1, may be fractional)
 * @param {number} fsAmpl  DAC peak full-scale amplitude (V)
 * @returns {number}
 */
export function ditherDbvForBits(bits, fsAmpl) {
  return -(bits - 1) * DITHER_DB_PER_BIT - TPDF_OFFSET_DB + ditherFsDbv(fsAmpl);
}

/**
 * The (fractional) bit count whose TPDF dither lands at the given dBV - the
 * exact inverse of ditherDbvForBits, un-clamped.
 * @param {number} dbv     dither level (dBV)
 * @param {number} fsAmpl  DAC peak full-scale amplitude (V)
 * @returns {number}
 */
export function ditherBitsForDbv(dbv, fsAmpl) {
  return 1 + (ditherFsDbv(fsAmpl) - TPDF_OFFSET_DB - dbv) / DITHER_DB_PER_BIT;
}
