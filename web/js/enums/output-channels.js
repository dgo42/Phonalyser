/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.enums.OutputChannels as a frozen constant object.
// The string VALUES are the enum names the preference persists (OutputChannels.name).

/** Output-lane gate for the signal generator - which physical DAC channel(s)
 *  carry the tone. BOTH drives both lanes (the default); LEFT / RIGHT drive one
 *  lane and write digital silence to the other. */
export const OutputChannels = Object.freeze({
  BOTH: 'BOTH',
  LEFT: 'LEFT',
  RIGHT: 'RIGHT',
});
