/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * The Preferences a test hands its controllers: a DETACHED store (never touches localStorage)
 * with the given values written in - by the store's own property names, so a test says exactly
 * what the shipped code reads. The active backend's block fields (inputDeviceName,
 * inputSampleRate, ...) are written on the block; the two ENTERED values (amplitude, dither) are
 * given resolved, as V RMS and bits, and stored through the unit vocabulary the store keeps.
 */
import { Preferences } from '../js/store/preferences.js';
import { UNIT_FAMILIES, unitValue, baseToken } from '../js/widgets/unit-conversion.js';

const AMPLITUDE_BASE_UNIT = baseToken(UNIT_FAMILIES.AMPLITUDE);
const DITHER_BASE_UNIT = baseToken(UNIT_FAMILIES.DITHER);

/**
 * @param {Object} values preference values by the store's property names; backend-block fields
 *   land on the active backend's block; genAmplitudeVrms / genDitherBits are the resolved
 *   entered values
 * @returns {Preferences} a detached store with the values applied
 */
export function testPrefs(values = {}) {
  const prefs = new Preferences(true);
  const block = prefs.current();
  for (const [name, value] of Object.entries(values)) {
    if (name in block) block[name] = value;
    else if (name === 'genAmplitudeVrms') prefs.genAmplitude.set(unitValue(value, AMPLITUDE_BASE_UNIT));
    else if (name === 'genDitherBits') prefs.genDither.set(unitValue(value, DITHER_BASE_UNIT));
    else if (prefs[name] != null && typeof prefs[name].set === 'function') prefs[name].set(value);
    else throw new Error(`testPrefs: '${name}' is not a preference the store keeps`);
  }
  return prefs;
}
