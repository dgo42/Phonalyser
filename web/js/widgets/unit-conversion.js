/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.gui.widgets.UnitFamily +
// UnitConversion + UnitValue: the unit vocabulary a field displays in, and the
// arithmetic that converts a number between two units of ONE family against a
// live DAC full scale.
//
// This module sits BELOW the widget, exactly as the Java pair does: the
// preference store holds what the operator ENTERED (a number plus the unit it
// was typed in) and resolves it here at USE. A canonical value alone cannot say
// what was entered - 0 dBFS is one voltage on one DAC calibration and another
// on the next, and a dither stated in dBV is a different bit count under every
// full scale - so a backend, device or recalibration would silently redefine a
// value already stored.
//
// Units travel as their TOKEN: the unit.* i18n key without its prefix (v, dbv,
// dbfs, bits). That is a locale-independent name the family already carries, so
// there is no second vocabulary to drift from it, and a stored value resolves
// the same way in every language. Only the factor-1 units of a family have a
// token: a scaled linear unit (mV, uV, nV) never sticks - the display
// auto-ranges through it - so a value entered in one is stored in the base unit.

import { t } from '../i18n/i18n.js';
import { ditherDbvForBits, ditherBitsForDbv } from '../dsp/dither-math.js';

/** dB per factor-of-10 amplitude - the dBV and dBFS exponent scale. */
const DB_PER_DECADE = 20.0;
/** sqrt(2) - the peak-to-RMS ratio anchoring 0 dBFS to a full-scale SINE. */
const ROOT_TWO = Math.sqrt(2.0);
/** Separates the unit namespace from the token in an i18n key. */
const TOKEN_SEPARATOR = '.';
/** A dither stated logarithmically resolves to a real depth: 0 (Off) is only
 *  ever entered and stored in bits. */
const DITHER_MIN_BITS = 1.0;
/** Deepest dither the sample formats can carry (32-bit PCM). */
const DITHER_MAX_BITS = 32.0;

/** One display/input unit: i18n suffix key, canonical factor (linear) or dB
 *  marker, the ASCII aliases accepted on input, and - for a unit stated RELATIVE
 *  to a live full scale (dBFS) - the fsRelative marker. A full-scale-relative unit
 *  cannot convert through `factor`/`log`: it needs the live full scale, so it is
 *  resolved here and by the model (Java UnitFamily.Unit.fsRelative). Defaults
 *  false, leaving every existing 4-arg declaration unchanged. */
export class Unit {
  constructor(i18nKey, factor, log, aliases, fsRelative = false) {
    this.i18nKey = i18nKey; this.factor = factor; this.log = log; this.aliases = aliases;
    this.fsRelative = fsRelative;
  }
  suffix() { return this.i18nKey == null ? '' : t(this.i18nKey); }
  toCanonical(x) { return this.log ? Math.pow(10.0, x / DB_PER_DECADE) : x * this.factor; }
  fromCanonical(v) { return this.log ? DB_PER_DECADE * Math.log10(v) : v / this.factor; }
  matches(typed) {
    if (!typed) return false;
    if (typed.toLowerCase() === this.suffix().toLowerCase()) return true;
    return this.aliases.includes(typed.toLowerCase());
  }
}

const KILO_SWITCH_HZ = 1e3, HALF_UNIT_SWITCH = 0.5, MICRO_SWITCH = 1e-6, MILLI_SWITCH = 1e-3, UNIT_SWITCH = 1.0;

/** Unit family: an ordered unit list + the automatic display-unit switching +
 *  the suffix-less default-unit index (-1 = use the currently displayed unit). */
export class UnitFamilyDef {
  constructor(name, defaultUnitIndex, units) {
    this.name = name; this.defaultUnitIndex = defaultUnitIndex; this.units = units;
  }
  displayUnit(canonical) {
    const u = this.units;
    switch (this.name) {
      case 'FREQUENCY': return canonical < KILO_SWITCH_HZ ? u[0] : u[1];
      case 'AMPLITUDE':
      case 'VOLTAGE': return canonical < MICRO_SWITCH ? u[0]
        : canonical < MILLI_SWITCH ? u[1]
          : (canonical < HALF_UNIT_SWITCH ? u[2] : u[3]);
      case 'TIME': return canonical < HALF_UNIT_SWITCH ? u[0] : u[1];
      case 'TIME_PER_DIV': return canonical < MILLI_SWITCH ? u[0] : canonical < UNIT_SWITCH ? u[1] : u[2];
      case 'VOLTS_PER_DIV': return canonical < MICRO_SWITCH ? u[0]
        : canonical < MILLI_SWITCH ? u[1]
          : canonical < UNIT_SWITCH ? u[2] : u[3];
      default: return u[0];
    }
  }
  defaultUnit(canonical) {
    // The unit applied to suffix-less input: the family's fixed BASE unit
    // (Hz / V / s / s/div / V/div); -1 = the unit currently displayed (no
    // family uses it any more). Mirrors UnitFamily.defaultUnit exactly.
    return this.defaultUnitIndex >= 0 ? this.units[this.defaultUnitIndex] : this.displayUnit(canonical);
  }
  match(typedSuffix) {
    for (const u of this.units) if (u.matches(typedSuffix)) return u;
    return null;
  }
  logUnit() { for (const u of this.units) if (u.log) return u; return null; }
  fsRelativeUnit() { for (const u of this.units) if (u.fsRelative) return u; return null; }
}

/** UNIT_FAMILIES - mirrors the UnitFamily enum. */
export const UNIT_FAMILIES = {
  // Suffix-less (digits-only) input is Hz, the base unit; "k"/"kh" are short
  // aliases for kHz (UnitFamily.FREQUENCY).
  FREQUENCY: new UnitFamilyDef('FREQUENCY', 0, [
    new Unit('unit.hz', 1.0, false, ['hz']),
    new Unit('unit.khz', 1e3, false, ['khz', 'kh', 'k']),
  ]),
  // dBFS is stated relative to the LIVE full scale, so it carries the fsRelative marker and is
  // resolved against the injected supplier (a field with no supplier refuses it -
  // the FFT manual fundamental, an ADC-side reference far above DAC full scale).
  AMPLITUDE: new UnitFamilyDef('AMPLITUDE', 3, [
    new Unit('unit.nv', 1e-9, false, ['nv', 'n']),
    new Unit('unit.uv', 1e-6, false, ['uv', 'u', 'µ', 'μ']),
    new Unit('unit.mv', 1e-3, false, ['mv', 'm']),
    new Unit('unit.v', 1.0, false, ['v']),
    new Unit('unit.dbv', 1.0, true, ['dbv', 'db', 'd']),
    new Unit('unit.dbfs', 1.0, false, ['dbfs', 'dbf'], true),
  ]),
  // nV / µV / mV / V - AMPLITUDE without the logarithmic dBV unit, for calibration-value
  // entry where a dB reference makes no sense (UnitFamily.VOLTAGE). Same linear switching
  // thresholds and V default as AMPLITUDE.
  VOLTAGE: new UnitFamilyDef('VOLTAGE', 3, [
    new Unit('unit.nv', 1e-9, false, ['nv', 'n']),
    new Unit('unit.uv', 1e-6, false, ['uv', 'u', 'µ', 'μ']),
    new Unit('unit.mv', 1e-3, false, ['mv', 'm']),
    new Unit('unit.v', 1.0, false, ['v']),
  ]),
  // Generator dither depth: whole/fractional bits (base) or a full-scale-aware dBV VIEW of that
  // value (UnitFamily.DITHER). The bits to dBV conversion is NOT the plain Unit log formula - it
  // is full-scale-aware and lives in dsp/dither-math.js; these units carry only the suffixes and
  // the "which view" marker. Suffix-less input is bits, the base unit; dBV sticks once typed.
  DITHER: new UnitFamilyDef('DITHER', 0, [
    new Unit('unit.bits', 1.0, false, ['b', 'bi', 'bit', 'bits']),
    new Unit('unit.dbv', 1.0, true, ['d', 'db', 'dbv']),
  ]),
  TIME: new UnitFamilyDef('TIME', 1, [
    new Unit('unit.ms', 1e-3, false, ['ms']),
    new Unit('unit.s', 1.0, false, ['s']),
  ]),
  // Suffix-less (digits-only) input is s/div, the base unit (UnitFamily.TIME_PER_DIV).
  TIME_PER_DIV: new UnitFamilyDef('TIME_PER_DIV', 2, [
    new Unit('unit.usdiv', 1e-6, false, ['us/div', 'us', 'µs']),
    new Unit('unit.msdiv', 1e-3, false, ['ms/div', 'ms']),
    new Unit('unit.sdiv', 1.0, false, ['s/div', 's']),
  ]),
  // Suffix-less (digits-only) input is V/div, the base unit (UnitFamily.VOLTS_PER_DIV).
  VOLTS_PER_DIV: new UnitFamilyDef('VOLTS_PER_DIV', 3, [
    new Unit('unit.nvdiv', 1e-9, false, ['nv/div', 'nv', 'n']),
    new Unit('unit.uvdiv', 1e-6, false, ['uv/div', 'uv', 'µv', 'u', 'µ', 'μ']),
    new Unit('unit.mvdiv', 1e-3, false, ['mv/div', 'mv', 'm']),
    new Unit('unit.vdiv', 1.0, false, ['v/div', 'v']),
  ]),
  PERCENT: new UnitFamilyDef('PERCENT', 0, [new Unit('unit.percent', 1.0, false, ['%'])]),
  PIXEL: new UnitFamilyDef('PIXEL', 0, [new Unit('unit.px', 1.0, false, ['px'])]),
  DECIBEL: new UnitFamilyDef('DECIBEL', 0, [new Unit('unit.db', 1.0, false, ['db'])]),
  SECONDS: new UnitFamilyDef('SECONDS', 0, [new Unit('unit.s', 1.0, false, ['s'])]),
  DIVISIONS: new UnitFamilyDef('DIVISIONS', 0, [new Unit('unit.div', 1.0, false, ['div'])]),
  NONE: new UnitFamilyDef('NONE', 0, [new Unit(null, 1.0, false, [])]),
};

/**
 * One value AS ENTERED: the number the operator typed and the token of the unit
 * they typed it in - the twin of the Java UnitValue record.
 *
 * The two halves are ONE value because they only mean anything together, and
 * carrying them as a single frozen object is what makes a change atomic: a
 * store writes it in one move, so no observer can ever see the new unit against
 * the old number and drive the engine with a quantity nobody entered.
 *
 * @param {number} value
 * @param {string} unit  factor-1 unit token (v, dbv, dbfs, bits)
 * @returns {{value: number, unit: string}} frozen
 */
export function unitValue(value, unit) {
  return Object.freeze({ value, unit });
}

/** Whether two entered values are the same pair - the web twin of the Java
 *  record's value equality, which the store's change guard relies on. */
export function unitValueEquals(a, b) {
  return a != null && b != null && Object.is(a.value, b.value) && a.unit === b.unit;
}

/** The token stored for a unit: its i18n key without the `unit.` namespace.
 *  Empty for a suffix-less unit (no family in a stored value has one). */
export function unitToken(unit) {
  const key = unit.i18nKey;
  return key == null ? '' : key.substring(key.indexOf(TOKEN_SEPARATOR) + 1);
}

/** Token of a family's base unit - what a value carries when its number is
 *  already the canonical quantity (V RMS, dither bits). */
export function baseToken(family) {
  return unitToken(baseUnit(family));
}

/** Token of a family's logarithmic unit (dBV), or the base token for a family
 *  without one. */
export function logToken(family) {
  const log = family.logUnit();
  return log == null ? baseToken(family) : unitToken(log);
}

/** Whether a token names a family's logarithmic unit - the one display choice a
 *  value alone cannot reveal. */
export function isLogToken(family, token) {
  const log = family.logUnit();
  return log != null && unitToken(log) === token;
}

/**
 * A number, read in one unit of a family, expressed in another. An unknown
 * token is read as the family's base unit - a stored value whose token no
 * longer names a unit still yields its number rather than a NaN.
 *
 * @param {UnitFamilyDef} family
 * @param {number} value
 * @param {string} sourceUnit  token the number is stated in
 * @param {string} targetUnit  token to express it in
 * @param {number} fsAmpl      DAC PEAK full-scale amplitude (Vpeak); consulted
 *                             only where a unit is defined against it (dBFS,
 *                             and the dither dBV)
 * @returns {number}
 */
export function convert(family, value, sourceUnit, targetUnit, fsAmpl) {
  const source = unitOf(family, sourceUnit);
  const target = unitOf(family, targetUnit);
  if (source === target) return value;
  return fromCanonical(family, toCanonical(family, value, source, fsAmpl), target, fsAmpl);
}

/** The unit a token names within a family, defaulting to the base unit.
 *  Matching is on the TOKEN, never on the localized suffix, so a stored value
 *  resolves identically in every language. */
function unitOf(family, token) {
  const log = family.logUnit();
  if (log != null && unitToken(log) === token) return log;
  const fsRelative = family.fsRelativeUnit();
  if (fsRelative != null && unitToken(fsRelative) === token) return fsRelative;
  return baseUnit(family);
}

/** The family's base unit: the one applied to suffix-less input, which is also
 *  the unit its canonical quantity is expressed in. */
function baseUnit(family) {
  return family.defaultUnit(0);
}

function toCanonical(family, value, source, fsAmpl) {
  if (family.name === 'DITHER') {
    // The dither dBV is a TPDF noise level against the peak full scale, not a
    // plain logarithm of the bit count - see dsp/dither-math.js.
    return source.log ? clampBits(ditherBitsForDbv(value, fsAmpl)) : value;
  }
  return source.fsRelative
    ? fsAmpl / ROOT_TWO * Math.pow(10.0, value / DB_PER_DECADE)
    : source.toCanonical(value);
}

function fromCanonical(family, canonical, target, fsAmpl) {
  if (family.name === 'DITHER') {
    return target.log ? ditherDbvForBits(canonical, fsAmpl) : canonical;
  }
  return target.fsRelative
    ? DB_PER_DECADE * Math.log10(canonical * ROOT_TWO / fsAmpl)
    : target.fromCanonical(canonical);
}

function clampBits(bits) {
  return Math.max(DITHER_MIN_BITS, Math.min(DITHER_MAX_BITS, bits));
}
