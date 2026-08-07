/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.enums.{DeviceChannelMode, OutputChannels}
// as frozen constant objects. The string VALUES are the tokens stored in the
// device-profile document (DeviceChannelMode.valueOf / OutputChannels.name), so
// these constants interoperate with the persisted store - they just give the
// device-profile code named handles instead of bare string literals.
//
// Home rationale: the web has no general "enums" module (scope-enums.js is
// scope-scoped), so the shared device-profile enum lives here at the store level
// next to device-profiles.js, its sole consumer. OutputChannels - the generator
// output-lane gate - is a sibling enum with no store consumer yet; it rides along
// here per the porting brief so both device-facing enums share one home.

/** How a device endpoint's channels are calibrated. MONO is a single physical
 *  channel (one full-scale, pushed into both left/right scalars); LINKED is a
 *  stereo endpoint whose two channels share one range selection but keep their
 *  own per-channel full-scale (fsLeft / fsRight of the single active row);
 *  INDEPENDENT lets each channel sit on its own range with its own full-scale. */
export const DeviceChannelMode = Object.freeze({
  MONO: 'MONO',
  LINKED: 'LINKED',
  INDEPENDENT: 'INDEPENDENT',
});

/** Output-lane gate for the signal generator - which physical DAC channel(s)
 *  carry the tone. BOTH drives both lanes (the default); LEFT / RIGHT drive one
 *  lane and write digital silence to the other. */
export const OutputChannels = Object.freeze({
  BOTH: 'BOTH',
  LEFT: 'LEFT',
  RIGHT: 'RIGHT',
});
