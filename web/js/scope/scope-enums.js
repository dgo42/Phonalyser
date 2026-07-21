/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.enums.{OscSliderId, TriggerEdge,
// TriggerMode, TriggerType, PersistenceMode} as frozen constant objects. The string VALUES
// match the legal serialised enum names used elsewhere in the web port (preferences.js stores
// 'RISE'/'FALL', 'AUTO'/'NORMAL'/'SINGLE', 'OFF'/'S_05'/…/'MANUAL'), so these constants
// interoperate with the persisted state — they just give the nav/trigger code named handles
// instead of bare string literals.

/** Which on-canvas slider the user is dragging: a per-channel vertical offset,
 *  the trigger-level marker, or the trigger-position (time) marker. */
export const OscSliderId = Object.freeze({
  OFFSET: 'OFFSET',
  TRIGGER_LEVEL: 'TRIGGER_LEVEL',
  TRIGGER_POSITION: 'TRIGGER_POSITION',
});

/** Trigger slope: capture on the rising or falling edge. */
export const TriggerEdge = Object.freeze({
  RISE: 'RISE',
  FALL: 'FALL',
});

/** Capture mode for the oscilloscope trigger. */
export const TriggerMode = Object.freeze({
  AUTO: 'AUTO',
  NORMAL: 'NORMAL',
  SINGLE: 'SINGLE',
});

/** Trigger event type: EDGE fires on a level crossing (the classic Schmitt
 *  trigger); GLITCH fires on a dV/dt discontinuity — a per-sample jump far
 *  beyond the signal's own bounded slew, e.g. a dropped-samples DAC gap.
 *  The TriggerEdge slope applies to both: crossing direction for EDGE,
 *  jump sign for GLITCH. */
export const TriggerType = Object.freeze({
  EDGE: 'EDGE',
  GLITCH: 'GLITCH',
});

/** Oscilloscope display persistence ("digital phosphor") — how long a swept trace
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

/** Combo labels, index-aligned with the mode names (PersistenceMode.LABELS). NOT i18n
 *  in the Java original — hard-coded strings. */
export const PERSISTENCE_LABELS = Object.freeze(
  ['Off', '0.5 s', '1 s', '2 s', '5 s', '10 s', '15 s', '20 s', '∞', 'Manual']);

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
