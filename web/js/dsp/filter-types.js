/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.enums.FilterType, FilterResponse and
// UnevenMode. Declaration order is load-bearing - it IS the combo / radio-group
// order in the Frequency-Response filter UI, so keep these object keys in order
// (Object.values preserves insertion order).

/** Passband shape of an ideal filter overlay in the Frequency Response pane. */
export const FilterType = Object.freeze({
  LOW_PASS: 'LOW_PASS',
  HIGH_PASS: 'HIGH_PASS',
  BAND_PASS: 'BAND_PASS',
  NOTCH: 'NOTCH',
});

/** Approximation family used to synthesise a filter's magnitude response. */
export const FilterResponse = Object.freeze({
  BESSEL: 'BESSEL',
  BUTTERWORTH: 'BUTTERWORTH',
  CHEBYSHEV: 'CHEBYSHEV',
  ELLIPTIC: 'ELLIPTIC',
  INV_CHEBYSHEV: 'INV_CHEBYSHEV',
});

/**
 * true for the families with a user-settable ripple parameter (Chebyshev I
 * passband ripple, Elliptic passband ripple, and - reusing the same field -
 * Inverse Chebyshev stopband ripple). Port of FilterResponse.hasRipple().
 * @param {string} response a FilterResponse value
 * @returns {boolean}
 */
export function hasRipple(response) {
  return response === FilterResponse.CHEBYSHEV
      || response === FilterResponse.ELLIPTIC
      || response === FilterResponse.INV_CHEBYSHEV;
}

/**
 * Unevenness readout mode in the Frequency Response pane. OFF computes nothing;
 * LEVEL takes a ±dB tolerance and reports the frequency span within it; RANGE
 * takes a frequency range and reports the ±dB spread over it.
 */
export const UnevenMode = Object.freeze({
  OFF: 'OFF',
  LEVEL: 'LEVEL',
  RANGE: 'RANGE',
});
