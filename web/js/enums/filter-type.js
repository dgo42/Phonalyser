/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.enums.FilterType. Declaration order is
// load-bearing - it IS the combo order in the Frequency-Response filter UI, so keep
// these object keys in order (Object.values preserves insertion order).

/** Passband shape of an ideal filter overlay in the Frequency Response pane. */
export const FilterType = Object.freeze({
  LOW_PASS: 'LOW_PASS',
  HIGH_PASS: 'HIGH_PASS',
  BAND_PASS: 'BAND_PASS',
  NOTCH: 'NOTCH',
});
