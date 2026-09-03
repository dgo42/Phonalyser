/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.enums.PersistenceMode as a frozen constant object
// plus its two instance/static methods as functions taking the name. The string VALUES are the
// enum names preferences.js persists ('OFF' / 'S_05' / ... / 'MANUAL').

/** Oscilloscope display persistence ("digital phosphor") - how long a swept trace
 *  lingers before fading. OFF clears each frame; INFINITE never decays (accumulate
 *  forever); the timed presets decay with that time constant; MANUAL uses the separate
 *  manual-seconds preference (NaN sentinel here). GPU path only. */
export const PersistenceMode = Object.freeze({
  OFF: 'OFF',
  S_05: 'S_05',
  S_1: 'S_1',
  S_2: 'S_2',
  S_5: 'S_5',
  S_10: 'S_10',
  S_15: 'S_15',
  S_20: 'S_20',
  INFINITE: 'INFINITE',
  MANUAL: 'MANUAL',
});

/** Persistence time in seconds per mode name: 0 = off, < 0 = infinite, > 0 = decay time
 *  constant; NaN = use the manual pref (PersistenceMode.seconds field, index-aligned). */
const PERSISTENCE_SECONDS = Object.freeze({
  OFF: 0.0,
  S_05: 0.5,
  S_1: 1.0,
  S_2: 2.0,
  S_5: 5.0,
  S_10: 10.0,
  S_15: 15.0,
  S_20: 20.0,
  INFINITE: -1.0,
  MANUAL: NaN,
});

/** Effective persistence time, substituting {@code manualSeconds} for MANUAL:
 *  0 = off, < 0 = infinite, > 0 = finite decay time (PersistenceMode.effectiveSeconds).
 *  @param {string} mode a PersistenceMode name
 *  @param {number} manualSeconds
 *  @returns {number} */
export function effectiveSeconds(mode, manualSeconds) {
  return mode === PersistenceMode.MANUAL ? manualSeconds : PERSISTENCE_SECONDS[mode];
}

/** Parses an enum name, falling back to {@code def} on null / unknown
 *  (PersistenceMode.fromNameOr).
 *  @param {?string} name
 *  @param {string} def a PersistenceMode name
 *  @returns {string} */
export function fromNameOr(name, def) {
  if (name == null) return def;
  return Object.prototype.hasOwnProperty.call(PersistenceMode, name) ? name : def;
}
