/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.enums.DeviceChannelMode as a frozen constant
// object. The string VALUES are the tokens stored in the device-profile document
// (DeviceChannelMode.valueOf), so the constant interoperates with the persisted store.

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
