/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.enums.UnevenMode. Declaration order is
// load-bearing - it IS the radio-group order in the Frequency-Response pane.

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
