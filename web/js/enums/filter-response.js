/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.enums.FilterResponse plus its hasRipple() as a
// function taking the value. Declaration order is load-bearing - it IS the radio-group order
// in the Frequency-Response filter UI, so keep these object keys in order.

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
