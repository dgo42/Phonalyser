/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.enums.TriggerMode as a frozen constant object; the
// string VALUES are the enum names preferences.js persists ('AUTO' / 'NORMAL' / 'SINGLE').

/** Capture mode for the oscilloscope trigger. */
export const TriggerMode = Object.freeze({
  AUTO: 'AUTO',
  NORMAL: 'NORMAL',
  SINGLE: 'SINGLE',
});
