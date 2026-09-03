/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.enums.TriggerEdge as a frozen constant object; the
// string VALUES are the enum names preferences.js persists ('RISE' / 'FALL').

/** Trigger slope: capture on the rising or falling edge. */
export const TriggerEdge = Object.freeze({
  RISE: 'RISE',
  FALL: 'FALL',
});
