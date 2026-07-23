/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of the SWT-free brain of NumericStepField:
//   - org.edgo.audio.measure.gui.widgets.UnitFamily   (units / families / switching)
//   - org.edgo.audio.measure.gui.widgets.NumericStepModel (the three step policies)
// plus a thin DOM controller (NumericStepField) that wires the existing
// `.numfield` chrome (input.nf-input + the two `.nf-step button`s) to wheel /
// arrow / keyboard / commit-or-revert exactly like the SWT field.  Like Java's
// single SWT Text, the input carries the WHOLE "1 kHz" string (number + unit),
// so an ordinary edit ("1" → "1.5") keeps its unit and reparses in-unit; there
// is no separate unit label.

import { t } from '../i18n/i18n.js';

// ----------------------------------------------------------------------------
// constants (1:1 with NumericStepModel)
// ----------------------------------------------------------------------------
const REL_EPS = 1e-9;
const VALUE_SIG_DIGITS = 12;
const PERCENT_STEP_FACTOR = 1.1;
const LOG_WHEEL_STEP_DB = 10;
const DB_PER_DECADE = 20.0;
const PERCENT_STEP_LABEL = '10 %';
const WHEEL_GLYPH = '⟳';
const ARROWS_GLYPH = '▲▼';
const SERIES_SEP = '·';
const SERIES_HINT_MAX = 7;

// DITHER policy constants (1:1 with NumericStepModel). The 20 dB/decade term is
// the existing DB_PER_DECADE.
const DITHER_DB_PER_BIT = 6.0206;       // one TPDF bit is 6.0206 dB (RMS = 2^−(bits−1)/√6)
const DITHER_TPDF_OFFSET_DB = 7.782;    // constant term = 20·log10(1/√6)
const DITHER_DBV_STEP = 10.0;           // dBV-view wheel/arrow notch
const DITHER_DBV_DECIMALS = 1;          // decimals shown for the dBV view
// The Off vocabulary, shared by every policy that has an Off state — a 0-bit
// dither, an averages count of 1, …: the text such a value renders as, and the
// word _isPrefixOf accepts in full or as any prefix.
export const OFF_LABEL = 'Off';
// The unbounded vocabulary, for a field whose max is infinite: this word is
// accepted in full or as any prefix (i, in, inf, …).
export const INFINITY_LABEL = 'Infinity';
// Rendered form of an unbounded value, and the shortest way to type one.
const INFINITY_SIGN = '∞';

// Number + optional trailing unit suffix; both micro code points (µ U+00B5,
// μ U+03BC) accepted.
const NUMBER_WITH_UNIT = /^([+-]?[0-9]*\.?[0-9]+(?:[eE][+-]?[0-9]+)?)\s*([%µμ\w./]*)$/;
// Lenient mid-edit alphabet for the Verify filter: any characters a valid entry can
// contain, in ANY order — NOT a positional grammar. A positional prefix-grammar
// silently swallowed every letter typed with the caret inside/before the number
// ("19|.002 kHz" + m → "19m.002 kHz" has digits/dot AFTER the letter and failed),
// making units untypeable during in-place edits. Ordering is enforced by commit(),
// the strict gate. Mirrors Java NumericStepModel.PARTIAL_INPUT (same relaxation).
const PARTIAL_INPUT = /^[\s+\-.,0-9a-zA-Zµμ%/∞]*$/;
// ----------------------------------------------------------------------------
// Unit / UnitFamily (port of UnitFamily.java)
// ----------------------------------------------------------------------------

/** One display/input unit: i18n suffix key, canonical factor (linear) or dB
 *  marker, and the ASCII aliases accepted on input. */
class Unit {
  constructor(i18nKey, factor, log, aliases) {
    this.i18nKey = i18nKey; this.factor = factor; this.log = log; this.aliases = aliases;
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
class UnitFamilyDef {
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
}

/** UNIT_FAMILIES — mirrors the UnitFamily enum. */
export const UNIT_FAMILIES = {
  // Suffix-less (digits-only) input is Hz, the base unit; "k"/"kh" are short
  // aliases for kHz (UnitFamily.FREQUENCY).
  FREQUENCY: new UnitFamilyDef('FREQUENCY', 0, [
    new Unit('unit.hz', 1.0, false, ['hz']),
    new Unit('unit.khz', 1e3, false, ['khz', 'kh', 'k']),
  ]),
  AMPLITUDE: new UnitFamilyDef('AMPLITUDE', 3, [
    new Unit('unit.nv', 1e-9, false, ['nv', 'n']),
    new Unit('unit.uv', 1e-6, false, ['uv', 'u', 'µ', 'μ']),
    new Unit('unit.mv', 1e-3, false, ['mv', 'm']),
    new Unit('unit.v', 1.0, false, ['v']),
    new Unit('unit.dbv', 1.0, true, ['dbv']),
  ]),
  // nV / µV / mV / V — AMPLITUDE without the logarithmic dBV unit, for calibration-value
  // entry where a dB reference makes no sense (UnitFamily.VOLTAGE). Same linear switching
  // thresholds and V default as AMPLITUDE.
  VOLTAGE: new UnitFamilyDef('VOLTAGE', 3, [
    new Unit('unit.nv', 1e-9, false, ['nv', 'n']),
    new Unit('unit.uv', 1e-6, false, ['uv', 'u', 'µ', 'μ']),
    new Unit('unit.mv', 1e-3, false, ['mv', 'm']),
    new Unit('unit.v', 1.0, false, ['v']),
  ]),
  // Generator dither depth: whole/fractional bits (base) or a full-scale-aware dBV VIEW of that
  // value (UnitFamily.DITHER). The bits⇄dBV conversion is NOT the plain Unit log formula — it is
  // full-scale- and bit-depth-aware and lives in the NumericStepModel DITHER policy (fed the live
  // full-scale supplier); these units carry only the suffixes and the "which view" marker.
  // Suffix-less (digits-only) input is bits, the base unit; dBV sticks for display once typed.
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

// ----------------------------------------------------------------------------
// NumericStepModel (port of NumericStepModel.java)
// ----------------------------------------------------------------------------

const POLICY = { FIXED: 'FIXED', LIST: 'LIST', PERCENT: 'PERCENT', DITHER: 'DITHER' };

export class NumericStepModel {
  /**
   * Four factory shapes mirroring the four Java constructors:
   *   fixed:   { family, min, max, wheelStep, arrowStep, decimals }
   *   list:    { family, min, max, series:[...], maxDecimals }
   *   percent: { family, min, max, maxDecimals }
   *   dither:  { family, maxBits, fsAmplSupplier }
   */
  constructor(cfg) {
    this.family = cfg.family;
    this.min = cfg.min;
    this.max = cfg.max;
    this.stickyUnit = null;
    this.namedValue = NaN;
    this.namedValueLabel = null;
    // DITHER-only config (null for every other policy).
    this.fsAmplSupplier = null;
    if (cfg.maxBits !== undefined) {
      // DITHER: 0 (Off) or [1, maxBits] bits, possibly fractional, shown as bits or a
      // full-scale-aware dBV VIEW of the same value. fsAmplSupplier yields the live PEAK full-scale
      // (Vpeak) and comes IN as config (never a singleton reach-in); the dBV is the physical level
      // relative to that full-scale. Off sits at the TOP.
      this.policy = POLICY.DITHER;
      this.min = 0; this.max = cfg.maxBits;
      this.wheelStep = 0; this.arrowStep = 0;
      this.series = null;
      this.decimals = -1; this.maxDecimals = DITHER_DBV_DECIMALS;
      this.fsAmplSupplier = cfg.fsAmplSupplier;
      // The config dBV (the full-scale term) that `value` was last reconciled against; reanchor()
      // moves the bits by the config delta to hold the displayed dBV across a full-scale change.
      this.ditherConfigDbv = this._ditherFsDbv();
    } else if (Array.isArray(cfg.series)) {
      this.policy = POLICY.LIST;
      this.wheelStep = 0; this.arrowStep = 0;
      this.series = cfg.series.slice();
      this.decimals = -1; this.maxDecimals = cfg.maxDecimals;
    } else if (cfg.wheelStep !== undefined) {
      this.policy = POLICY.FIXED;
      this.wheelStep = cfg.wheelStep; this.arrowStep = cfg.arrowStep;
      this.series = null;
      this.decimals = cfg.decimals; this.maxDecimals = cfg.decimals;
    } else {
      this.policy = POLICY.PERCENT;
      this.wheelStep = 0; this.arrowStep = 0;
      this.series = null;
      this.decimals = -1; this.maxDecimals = cfg.maxDecimals;
    }
    this.value = this.min;
    // When set, the field renders empty and holds no value — the disabled, never-measured
    // channel row in the calibration dialog (avoids the clamp-to-min "1 nV" artifact).
    // Cleared by any value mutation (NumericStepModel.blank).
    this.blank = false;
  }

  getValue() { return this.value; }

  isBlank() { return this.blank; }

  setValue(v) {
    if (Number.isNaN(v)) return;
    this.blank = false;
    this.value = this._clamp(this._roundSig(v));
  }

  /** Puts the model into the blank state: no value, an empty rendered text. The next
   *  setValue / wheel / arrow / successful commit leaves it (NumericStepModel.setBlank). */
  setBlank() { this.blank = true; }

  setMin(min) { this.min = min; this.value = this._clamp(this.value); }
  setMax(max) { this.max = max; this.value = this._clamp(this.value); }
  setSeries(series) { if (this.policy === POLICY.LIST) this.series = series.slice(); }
  setNamedValue(value, label) { this.namedValue = value; this.namedValueLabel = label; }

  wheel(dir) {
    switch (this.policy) {
      case POLICY.FIXED: this.setValue(this.value + dir * this.wheelStep); break;
      case POLICY.LIST: this.setValue(this._listJump(dir)); break;
      case POLICY.PERCENT: {
        const u = this.currentUnit();
        if (u.log) this.setValue(u.toCanonical(this._logGridStep(u.fromCanonical(this.value), dir)));
        else this.setValue(dir > 0 ? this._percentUp(this.value) : this._percentDown(this.value));
        break;
      }
      case POLICY.DITHER: this._ditherStep(dir); break;
    }
  }

  arrow(dir) {
    switch (this.policy) {
      case POLICY.FIXED: this.setValue(this.value + dir * this.arrowStep); break;
      case POLICY.LIST: this.setValue(this._listJump(dir)); break;
      case POLICY.PERCENT: this.setValue(this._plusOneDisplayedUnit(dir)); break;
      case POLICY.DITHER: this._ditherStep(dir); break;
    }
  }

  // ---- DITHER policy (port of NumericStepModel DITHER methods) --------------

  /** One dither step: dir=+1 up (fewer bits → toward Off) / −1 down (more bits).
   *  Bits view walks whole ±1-bit steps (a fractional value keeps its fraction);
   *  dBV view walks exactly ±10 dBV (fractional bits, no snap). Off sits at the
   *  TOP: stepping up from 1 bit reaches Off; down from Off reaches 1 bit. */
  _ditherStep(dir) {
    if (this.value <= 0) { this.setValue(dir > 0 ? 0 : 1); return; }   // Off: up stays Off, down → 1 bit
    if (this.currentUnit().log) {                                      // dBV view: exactly ±10 dBV
      const stepped = this._ditherBitsForDbv(this._ditherDbvForBits(this.value) + dir * DITHER_DBV_STEP);
      this.setValue(dir > 0 && stepped < 1 ? 0 : this._clampBits(stepped));
    } else {                                                           // bits view: whole ±1-bit step
      const nv = this.value - dir;                                     // up (+1) → fewer bits, toward Off
      this.setValue(dir > 0 && nv < 1 ? 0 : this._clampBits(nv));
    }
  }

  /** dBV of the DAC PEAK full-scale (Vpeak) — NOT the RMS full-scale (/√2), which
   *  would read ~3 dB low: the TPDF dither RMS is relative to the peak full-scale. */
  _ditherFsDbv() { return DB_PER_DECADE * Math.log10(this.fsAmplSupplier()); }

  /** dBV of the TPDF dither at `bits` (≥1) — the physical level relative to the peak full-scale. */
  _ditherDbvForBits(bits) {
    return -(bits - 1) * DITHER_DB_PER_BIT - DITHER_TPDF_OFFSET_DB
      + this._ditherFsDbv();
  }

  /** The (fractional) bit count whose TPDF dither lands at `dbv` — exact inverse
   *  of _ditherDbvForBits, un-clamped. */
  _ditherBitsForDbv(dbv) {
    return 1 + (this._ditherFsDbv() - DITHER_TPDF_OFFSET_DB - dbv) / DITHER_DB_PER_BIT;
  }

  /** Clamps a non-Off dither depth to [1, maxBits]. */
  _clampBits(bits) { return Math.max(1.0, Math.min(this.max, bits)); }

  /** Reacts to a config change (full-scale) holding the CURRENTLY DISPLAYED value:
   *  dBV view keeps the shown dBV and re-solves the bits under the new full-scale;
   *  bits view (and Off) keep the bits, only the dBV readout moves. Returns true when
   *  the stored bit count changed. */
  reanchor() {
    if (this.policy !== POLICY.DITHER) return false;
    const newConfigDbv = this._ditherFsDbv();
    const before = this.value;
    if (this.isLogDisplay() && this.value > 0) {
      this.value = this._clampBits(this.value + (newConfigDbv - this.ditherConfigDbv) / DITHER_DB_PER_BIT);
    }
    this.ditherConfigDbv = newConfigDbv;
    return this.value !== before;
  }

  /** Renders the current dither value: Off, a bit count, or the full-scale-aware
   *  dBV view — per the current (sticky) display unit. */
  _ditherText() {
    if (this.value <= 0) return OFF_LABEL;
    const u = this.currentUnit();
    if (u.log) return this._format(this._ditherDbvForBits(this.value), DITHER_DBV_DECIMALS) + ' ' + u.suffix();
    return this._trimTrailingZeros(this._format(this.value, this.maxDecimals)) + ' ' + u.suffix();
  }

  /** The current dither value in the OTHER unit (bits⇄dBV) for a companion label;
   *  empty for Off or a non-DITHER policy. */
  companionText() {
    if (this.policy !== POLICY.DITHER || this.value <= 0) return '';
    if (this.currentUnit().log) {   // field shows dBV → label shows bits
      return this._trimTrailingZeros(this._format(this.value, this.maxDecimals)) + ' ' + this.family.defaultUnit(this.value).suffix();
    }
    return this._format(this._ditherDbvForBits(this.value), DITHER_DBV_DECIMALS) + ' ' + this.family.logUnit().suffix();
  }

  /** Parses a dither entry: `Off` — or any prefix of it (`o`, `of`) — and 0 → Off;
   *  a bare number or a `bits` suffix → that bit count (clamped to [1, maxBits],
   *  0 → Off), possibly fractional; a `dBV` suffix → the full-scale-aware
   *  fractional bit count (sticks the dBV view). */
  _commitDither(text) {
    const t = String(text).trim().replace(/,/g, '.');
    if (t === '') return false;                       // empty → unchanged (Java commitDither)
    if (this._isPrefixOf(OFF_LABEL, t)) {             // "o" / "of" / "off" — keep the current view
      this.blank = false;
      this.value = 0;
      return true;
    }
    const m = NUMBER_WITH_UNIT.exec(t);
    if (!m) return false;
    const num = parseFloat(m[1]);
    if (!Number.isFinite(num)) return false;
    const suffix = m[2].trim();
    const unit = suffix === '' ? this.family.defaultUnit(this.value) : this.family.match(suffix);
    if (unit == null) return false;
    this.blank = false;
    if (unit.log) {   // dBV → fractional bits, dBV sticks
      this.stickyUnit = unit;
      this.value = this._clampBits(this._roundSig(this._ditherBitsForDbv(num)));
    } else {          // bits (base): 0 → Off, else [1, maxBits]
      this.stickyUnit = null;
      this.value = num <= 0 ? 0 : this._clampBits(this._roundSig(num));
    }
    return true;
  }

  /** `x` rendered with `decimals` places, dot decimal separator (Locale.ROOT). */
  _format(x, decimals) { return x.toFixed(decimals); }

  _logGridStep(db, dir) {
    const d = this._roundSig(db) / LOG_WHEEL_STEP_DB;
    const next = (dir > 0) ? Math.floor(d + REL_EPS) + 1 : Math.ceil(d - REL_EPS) - 1;
    return next * LOG_WHEEL_STEP_DB;
  }

  _plusOneDisplayedUnit(dir) {
    const u = this.currentUnit();
    if (u.log) return u.toCanonical(u.fromCanonical(this.value) + dir);
    return this.value + dir * u.factor;
  }

  _percentUp(v) {
    const lsb = this._displayLsb();
    if (v <= 0) return lsb;
    const target = v * PERCENT_STEP_FACTOR;
    const g = Math.max(this._gridStep(target), lsb);
    let r = Math.floor(target / g * (1 + REL_EPS)) * g;
    if (r <= v * (1 + REL_EPS)) r = v + g;
    return r;
  }

  _percentDown(v) {
    if (v <= 0) return v;
    const lsb = this._displayLsb();
    const decade = Math.pow(10, Math.floor(Math.log10(v) + REL_EPS));
    const mantissa = v / decade;
    const m = Math.round(mantissa);
    if (decade >= lsb && Math.abs(mantissa - m) < REL_EPS * 10) {
      return m > 1 ? (m - 1) * decade : 9 * decade / 10.0;
    }
    const g = Math.max(this._gridStep(v), lsb);
    let r = (Math.ceil(v / g * (1 - REL_EPS)) - 1) * g;
    if (r >= v * (1 - REL_EPS)) r = v - g;
    return r;
  }

  _displayLsb() {
    if (this.decimals >= 0) return 0;
    const u = this.currentUnit();
    if (u.log) return 0;
    return Math.pow(10, -this.maxDecimals) * u.factor;
  }

  _gridStep(v) { return Math.pow(10, Math.floor(Math.log10(v) + REL_EPS)) / 10.0; }

  _listJump(dir) {
    for (let i = 0; i < this.series.length; i++) {
      if (this._sameValue(this.series[i], this.value)) {
        const next = i + dir;
        if (next < 0 || next >= this.series.length) return this.value;
        return this.series[next];
      }
    }
    if (dir > 0) {
      let best = Infinity;
      for (const s of this.series) if (s > this.value * (1 + REL_EPS) + Number.MIN_VALUE && s < best) best = s;
      return !Number.isFinite(best) ? this.value : best;
    }
    let best = -Infinity;
    for (const s of this.series) if (s < this.value * (1 - REL_EPS) - Number.MIN_VALUE && s > best) best = s;
    return !Number.isFinite(best) ? this.value : best;
  }

  _sameValue(a, b) {
    if (a === b) return true;
    if (!Number.isFinite(a) || !Number.isFinite(b)) return false;
    return Math.abs(a - b) <= Math.max(Math.abs(a), Math.abs(b)) * REL_EPS;
  }

  text() {
    if (this.blank) return '';
    if (this.policy === POLICY.DITHER) return this._ditherText();
    if (!Number.isFinite(this.value)) return INFINITY_SIGN;
    if (this._isNamedValue(this.value)) return this.namedValueLabel;
    return this._formatIn(this.value, this.currentUnit());
  }

  _formatIn(canonical, u) {
    const x = u.fromCanonical(canonical);
    const num = (this.decimals >= 0)
      ? x.toFixed(this.decimals)
      : this._trimTrailingZeros(x.toFixed(this.maxDecimals));
    const suffix = u.suffix();
    return suffix === '' ? num : num + ' ' + suffix;
  }

  stepHint() {
    switch (this.policy) {
      case POLICY.FIXED:
        return `${WHEEL_GLYPH} ±${this._formatStep(this.wheelStep)}, ${ARROWS_GLYPH} ±${this._formatStep(this.arrowStep)}`;
      case POLICY.PERCENT: {
        const u = this.currentUnit();
        if (u.log) return `${WHEEL_GLYPH} ±${LOG_WHEEL_STEP_DB} dB, ${ARROWS_GLYPH} ±1 dB`;
        const suffix = u.suffix();
        return `${WHEEL_GLYPH} ±${PERCENT_STEP_LABEL}, ${ARROWS_GLYPH} ±1${suffix === '' ? '' : ' ' + suffix}`;
      }
      case POLICY.LIST:
        return `${WHEEL_GLYPH}${ARROWS_GLYPH} ${this._seriesHint()}`;
      case POLICY.DITHER: {
        const u = this.currentUnit();
        return u.log
          ? `${WHEEL_GLYPH}${ARROWS_GLYPH} ±${DITHER_DBV_STEP} ${u.suffix()}`
          : `${WHEEL_GLYPH}${ARROWS_GLYPH} ±1 ${u.suffix()}`;
      }
      default: return '';
    }
  }

  _formatStep(canonicalStep) { return this._formatIn(canonicalStep, this.family.displayUnit(canonicalStep)); }

  _seriesHint() {
    const n = this.series.length;
    if (n === 0) return '';
    if (n > SERIES_HINT_MAX) return this._seriesEntry(this.series[0]) + SERIES_SEP + '…' + SERIES_SEP + this._seriesEntry(this.series[n - 1]);
    return this.series.map(s => this._seriesEntry(s)).join(SERIES_SEP);
  }

  _seriesEntry(v) {
    if (!Number.isFinite(v)) return INFINITY_SIGN;
    if (this._isNamedValue(v)) return this.namedValueLabel;
    return this._formatIn(v, this.family.displayUnit(v));
  }

  commit(text) {
    if (text == null) return false;
    if (this.policy === POLICY.DITHER) return this._commitDither(text);
    let s = String(text).trim().replace(/,/g, '.').replace(/μ/g, 'µ');
    if (s === '') return false;
    if (s.length > 1 && s.endsWith('.') && /[0-9]/.test(s.charAt(s.length - 2))) s = s.slice(0, -1);
    if (!Number.isFinite(this.max) && (s === INFINITY_SIGN || this._isPrefixOf(INFINITY_LABEL, s))) {
      this.stickyUnit = null; this.blank = false; this.value = Infinity; return true;
    }
    if (this._matchesNamedLabel(s)) {
      this.stickyUnit = null; this.blank = false; this.value = this._clamp(this._roundSig(this.namedValue)); return true;
    }
    const m = NUMBER_WITH_UNIT.exec(s);
    if (!m) return false;
    const num = parseFloat(m[1]);
    if (!Number.isFinite(num)) return false;
    const suffix = m[2].trim();
    let unit;
    if (suffix === '') {
      unit = this.family.defaultUnit(this.value);
      this.stickyUnit = null;
    } else {
      unit = this.family.match(suffix);
      // Unknown suffix → reject, value unchanged (Java NumericStepModel.commit).
      // Aliases are FAMILY-scoped ("k"/"kh" are kHz only in FREQUENCY, "m"/"u"
      // are mV/µV only in AMPLITUDE) — there is deliberately no generic SI-prefix
      // fallback, so a frequency alias can't parse in a voltage field.
      if (unit == null) return false;
      this.stickyUnit = unit.log ? unit : null;
    }
    this.blank = false;
    this.value = this._clamp(this._roundSig(unit.toCanonical(num)));
    return true;
  }

  currentUnit() { return this.stickyUnit != null ? this.stickyUnit : this.family.displayUnit(this.value); }
  isLogDisplay() { return this.stickyUnit != null && this.stickyUnit.log; }
  setLogDisplay(on) {
    const log = this.family.logUnit();
    if (log == null) return;
    if (on) this.stickyUnit = log;
    else if (this.isLogDisplay()) this.stickyUnit = null;
  }
  acceptsPartial(text) { return PARTIAL_INPUT.test(text); }

  _clamp(v) { return Math.max(this.min, Math.min(this.max, v)); }
  _isNamedValue(v) { return this.namedValueLabel != null && Math.abs(v - this.namedValue) <= Math.abs(this.namedValue) * REL_EPS; }

  /** True when `t` is a non-empty, case-insensitive prefix of `word`. One shared
   *  rule so a named state can be committed from a single keystroke — o/of/off,
   *  i/in/inf/… — the way the unit suffixes already take a prefix. */
  _isPrefixOf(word, t) {
    return t.length > 0 && t.length <= word.length
        && word.slice(0, t.length).toLowerCase() === t.toLowerCase();
  }

  /** Named-value label match: exact, or — when that named value IS the Off state —
   *  any prefix of OFF_LABEL. Prefixes are deliberately confined to Off: for a
   *  label like "Nyquist/2" a lone letter must never commit. */
  _matchesNamedLabel(t) {
    if (this.namedValueLabel == null) return false;
    const label = this.namedValueLabel.trim();
    return t.toLowerCase() === label.toLowerCase()
        || (label.toLowerCase() === OFF_LABEL.toLowerCase() && this._isPrefixOf(OFF_LABEL, t));
  }
  _roundSig(v) {
    if (v === 0 || !Number.isFinite(v)) return v;
    const scale = Math.pow(10, VALUE_SIG_DIGITS - 1 - Math.floor(Math.log10(Math.abs(v))));
    return Math.round(v * scale) / scale;
  }
  _trimTrailingZeros(num) {
    if (num.indexOf('.') < 0) return num;
    let s = num;
    while (s.endsWith('0')) s = s.slice(0, -1);
    if (s.endsWith('.')) s = s.slice(0, -1);
    return s;
  }
}

// ----------------------------------------------------------------------------
// NumericStepField — thin DOM controller over the existing `.numfield` chrome
// ----------------------------------------------------------------------------

const REPEAT_DELAY_MS = 300, REPEAT_PERIOD_MS = 100;

export class NumericStepField {
  /**
   * DOM controller over the `.numfield` chrome — faithful to the Java NumericStepField:
   * the value AUTO-RANGES its unit (1000 → "1 kHz", 1e-4 V → "100 µV"), the ▲▼ buttons +
   * wheel + arrow keys step along the model's 1-2-5 / log ladder, and free-text entry
   * parses units ("1.5k", "-3 dBV", "2 kHz"). Exactly like Java's single SWT Text, the
   * FULL composed string ("1 kHz") lives in the input — number and unit together — so an
   * ordinary edit ("1" → "1.5") keeps its unit ("1.5 kHz" → 1500 Hz) and an untouched
   * commit round-trips the value verbatim. There is no separate unit label.
   * @param {HTMLInputElement} input  the `.nf-input` element (id kept).
   * @param {NumericStepModel} model
   * @param {{onChange?:(value:number)=>void, tooltipBase?:string}} [opts]
   */
  constructor(input, model, opts = {}) {
    this.input = input;
    this.model = model;
    this.onChange = opts.onChange || null;
    this.tooltipBase = opts.tooltipBase || '';
    this.disabled = false;

    const field = input.closest('.numfield');
    const stepBtns = field ? field.querySelectorAll('.nf-step button') : [];
    this.upBtn = stepBtns[0] || null;
    this.downBtn = stepBtns[1] || null;

    input.setAttribute('type', 'text');   // text so the model can show "1 kHz" / "100 µV"
    input.removeAttribute('step'); input.removeAttribute('min'); input.removeAttribute('max');

    this._wire();
    this.refresh();
  }

  /** The text to commit — the whole input, verbatim, exactly like Java's
   *  {@code field.getText()}. The input already carries "1 kHz" (number + unit),
   *  so an untouched commit round-trips the value and a digits-only edit stays
   *  suffix-less (the model then applies the family's BASE unit / clears a sticky
   *  dBV, per UnitFamily.defaultUnit / NumericStepModel.commit). */
  _committed() {
    return this.input.value.trim();
  }

  _wire() {
    const input = this.input;
    const field = input.closest('.numfield') || input;   // wheel also over the unit box + ▲▼

    field.addEventListener('wheel', (e) => {
      if (this.disabled) return;
      e.preventDefault();
      this.model.commit(this._committed());
      this._mutate(() => this.model.wheel(e.deltaY < 0 ? 1 : -1));
    }, { passive: false });

    input.addEventListener('keydown', (e) => {
      if (this.disabled) return;
      if (e.key === 'ArrowUp' || e.key === 'ArrowDown') {
        e.preventDefault();
        this.model.commit(this._committed());
        this._mutate(() => this.model.arrow(e.key === 'ArrowUp' ? 1 : -1));
      }
    });

    // NO per-keystroke filtering (deliberate divergence from Java's SWT Verify,
    // by explicit user order): the browser's beforeinput gating swallowed keys
    // during legitimate edits (e.g. typing "7k Hz" then deleting "Hz"), and web
    // input events / IME make keystroke-level gating unreliable. Free typing;
    // commit on Enter / blur remains the ONLY gate — invalid text reverts there.

    const commitOrRevert = () => {
      const before = this.model.getValue(), logBefore = this.model.isLogDisplay();
      if (this.model.commit(this._committed())) {
        this.refresh();
        if ((!Object.is(before, this.model.getValue()) || logBefore !== this.model.isLogDisplay()) && this.onChange) {
          this.onChange(this.model.getValue());
        }
      } else this.refresh();
    };
    input.addEventListener('change', commitOrRevert);
    input.addEventListener('blur', commitOrRevert);

    if (this.upBtn) this._wireRepeat(this.upBtn, 1);
    if (this.downBtn) this._wireRepeat(this.downBtn, -1);
  }

  _wireRepeat(btn, dir) {
    let delayTimer = null, repeatTimer = null;
    const step = () => {
      if (this.disabled) { stop(); return; }
      this.model.commit(this._committed()); this._mutate(() => this.model.arrow(dir));
    };
    const stop = () => {
      if (delayTimer) { clearTimeout(delayTimer); delayTimer = null; }
      if (repeatTimer) { clearInterval(repeatTimer); repeatTimer = null; }
    };
    btn.addEventListener('pointerdown', (e) => {
      if (this.disabled || e.button !== 0) return;
      e.preventDefault();
      step();
      delayTimer = setTimeout(() => { repeatTimer = setInterval(step, REPEAT_PERIOD_MS); }, REPEAT_DELAY_MS);
    });
    btn.addEventListener('pointerup', stop);
    btn.addEventListener('pointerleave', stop);
  }

  _mutate(fn) {
    const before = this.model.getValue(), logBefore = this.model.isLogDisplay();
    fn();
    this.refresh();
    if ((!Object.is(before, this.model.getValue()) || logBefore !== this.model.isLogDisplay()) && this.onChange) {
      this.onChange(this.model.getValue());
    }
  }

  /** Writes the whole composed string ("1 kHz", "100 µV", or a bare number for
   *  the NONE family) into the input — exactly Java's {@code field.setText(model.text())}. */
  refresh() {
    this.input.value = this.model.text();
    const hint = this.model.stepHint();
    this.input.title = this.tooltipBase ? `${this.tooltipBase} (${hint})` : hint;
  }

  setValue(v) { this.model.setValue(v); this.refresh(); }
  getValue() { return this.model.getValue(); }
  /** Renders the field empty and holding no value — the disabled, never-measured channel
   *  row in the calibration dialog. isBlank stays true until setValue / a committed edit
   *  enters a value (NumericStepField.setBlank / isBlank). */
  setBlank() { this.model.setBlank(); this.refresh(); }
  isBlank() { return this.model.isBlank(); }
  setMin(m) { this.model.setMin(m); this.refresh(); }
  setMax(m) { this.model.setMax(m); this.refresh(); }
  setLogDisplay(on) { this.model.setLogDisplay(on); this.refresh(); }
  isLogDisplay() { return this.model.isLogDisplay(); }
  /** The current value in the alternate unit (a DITHER field's bits⇄dBV), for a
   *  companion label beside the field; empty when there is no alternate view. */
  companionText() { return this.model.companionText(); }
  /** DITHER: re-solve for a config change (full-scale) holding the displayed value,
   *  then re-render. Returns true when the stored bit count changed so the caller can
   *  persist + restart. */
  reanchor() { const changed = this.model.reanchor(); this.refresh(); return changed; }
  setDisabled(d) {
    this.disabled = d;
    this.input.disabled = d;
    const field = this.input.closest('.numfield');
    if (field) field.classList.toggle('disabled', d);
    if (this.upBtn) this.upBtn.disabled = d;
    if (this.downBtn) this.downBtn.disabled = d;
  }
}
