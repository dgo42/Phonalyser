/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.enums.TriggerType as a frozen constant object; the
// string VALUES are the enum names preferences.js persists.

/** Trigger event type: EDGE fires on a level crossing (the classic Schmitt
 *  trigger); GLITCH fires on a dV/dt discontinuity - a per-sample jump far
 *  beyond the signal's own bounded slew, e.g. a dropped-samples DAC gap.
 *  The TriggerEdge slope applies to both: crossing direction for EDGE,
 *  jump sign for GLITCH. */
export const TriggerType = Object.freeze({
  EDGE: 'EDGE',
  GLITCH: 'GLITCH',
});
