/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.enums.{OscSliderId, TriggerEdge,
// TriggerMode} as frozen constant objects. The string VALUES match the legal
// serialised enum names used elsewhere in the web port (preferences.js stores
// 'RISE'/'FALL', 'AUTO'/'NORMAL'/'SINGLE'), so these constants interoperate with
// the persisted state — they just give the nav/trigger code named handles
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
