/*
 * Phonalyser web - precision audio measurement workbench (browser port).
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
// so an ordinary edit ("1" -> "1.5") keeps its unit and reparses in-unit; there
// is no separate unit label.

import { t } from '../i18n/i18n.js';
import { ditherDbvForBits, ditherBitsForDbv } from '../dsp/dither-math.js';
// The unit vocabulary and the conversions between two units of one family live
// one layer down, with the preference store as their other consumer: a stored
// value is resolved against the unit it was ENTERED in, and field and store must
// use the same arithmetic to do it. Re-exported so the widget stays the single
// import for everything that builds a field.
import { UNIT_FAMILIES, unitValue, unitToken, convert, baseToken }
  from './unit-conversion.js';

export { UNIT_FAMILIES };

// ----------------------------------------------------------------------------
// constants (1:1 with NumericStepModel)
// ----------------------------------------------------------------------------
const REL_EPS = 1e-9;
const VALUE_SIG_DIGITS = 12;
const PERCENT_STEP_FACTOR = 1.1;
const LOG_WHEEL_STEP_DB = 10;
const PERCENT_STEP_LABEL = '10 %';
const WHEEL_GLYPH = '⟳';
const ARROWS_GLYPH = '▲▼';
const SERIES_SEP = '·';
const SERIES_HINT_MAX = 7;

// DITHER policy constants (1:1 with NumericStepModel). The bits <-> dBV
// arithmetic lives in dsp/dither-math.js; the wrappers below only close over
// this field's live full-scale supplier.
const DITHER_DBV_STEP = 10.0;           // dBV-view wheel/arrow notch
const DITHER_DBV_DECIMALS = 1;          // decimals shown for the dBV view
// The Off vocabulary, shared by every policy that has an Off state - a 0-bit
// dither, an averages count of 1, ...: the text such a value renders as, and the
// word _isPrefixOf accepts in full or as any prefix.
export const OFF_LABEL = 'Off';
// The unbounded vocabulary, for a field whose max is infinite: this word is
// accepted in full or as any prefix (i, in, inf, ...).
export const INFINITY_LABEL = 'Infinity';
// Rendered form of an unbounded value, and the shortest way to type one.
const INFINITY_SIGN = '∞';

// Number + optional trailing unit suffix; both micro code points (µ U+00B5,
// μ U+03BC) accepted.
const NUMBER_WITH_UNIT = /^([+-]?[0-9]*\.?[0-9]+(?:[eE][+-]?[0-9]+)?)\s*([%µμ\w./]*)$/;
// Lenient mid-edit alphabet for the Verify filter: any characters a valid entry can
// contain, in ANY order - NOT a positional grammar. A positional prefix-grammar
// silently swallowed every letter typed with the caret inside/before the number
// ("19|.002 kHz" + m -> "19m.002 kHz" has digits/dot AFTER the letter and failed),
// making units untypeable during in-place edits. Ordering is enforced by commit(),
// the strict gate. Mirrors Java NumericStepModel.PARTIAL_INPUT (same relaxation).
const PARTIAL_INPUT = /^[\s+\-.,0-9a-zA-Zµμ%/∞]*$/;
// ----------------------------------------------------------------------------
// NumericStepModel (port of NumericStepModel.java)
// ----------------------------------------------------------------------------

const POLICY = { FIXED: 'FIXED', LIST: 'LIST', PERCENT: 'PERCENT', DITHER: 'DITHER' };

export class NumericStepModel {
  /**
   * Four factory shapes mirroring the four Java constructors:
   *   fixed:   { family, min, max, wheelStep, arrowStep, decimals }
   *   list:    { family, min, max, series:[...], maxDecimals }
   *   percent: { family, min, max, maxDecimals, fsAmplSupplier? }
   *   dither:  { family, maxBits, fsAmplSupplier }
   */
  constructor(cfg) {
    this.family = cfg.family;
    this.min = cfg.min;
    this.max = cfg.max;
    this.stickyUnit = null;
    this.namedValue = NaN;
    this.namedValueLabel = null;
    // Live PEAK full scale (Vpeak), injected as config - never a singleton reach-in. Required by
    // the DITHER policy, and OPTIONAL on an AMPLITUDE field: present => a dBFS entry is accepted,
    // absent => refused (the FFT manual fundamental, an ADC-side reference unrelated to DAC FS).
    this.fsAmplSupplier = cfg.fsAmplSupplier || null;
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
    // When set, the field renders empty and holds no value - the disabled, never-measured
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
        // dBFS walks the same 10-dB grid as dBV, but in the dBFS domain (via the live full scale);
        // setValue's range clamp saturates it at the field max (= 0 dBFS).
        if (u.fsRelative) this.setValue(this._canonicalFromDbfs(this._logGridStep(this._dbfsFromCanonical(this.value), dir)));
        else if (u.log) this.setValue(u.toCanonical(this._logGridStep(u.fromCanonical(this.value), dir)));
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

  /** One dither step: dir=+1 up (fewer bits -> toward Off) / −1 down (more bits).
   *  Bits view walks whole ±1-bit steps (a fractional value keeps its fraction);
   *  dBV view walks exactly ±10 dBV (fractional bits, no snap). Off sits at the
   *  TOP: stepping up from 1 bit reaches Off; down from Off reaches 1 bit. */
  _ditherStep(dir) {
    if (this.value <= 0) { this.setValue(dir > 0 ? 0 : 1); return; }   // Off: up stays Off, down -> 1 bit
    if (this.currentUnit().log) {                                      // dBV view: exactly ±10 dBV
      const stepped = this._ditherBitsForDbv(this._ditherDbvForBits(this.value) + dir * DITHER_DBV_STEP);
      this.setValue(dir > 0 && stepped < 1 ? 0 : this._clampBits(stepped));
    } else {                                                           // bits view: whole ±1-bit step
      const nv = this.value - dir;                                     // up (+1) -> fewer bits, toward Off
      this.setValue(dir > 0 && nv < 1 ? 0 : this._clampBits(nv));
    }
  }

  /** dBV of the DAC PEAK full-scale - see dsp/dither-math.js for why the
   *  reference is the peak, not the RMS, full-scale. */
  _ditherFsDbv() { return ditherFsDbv(this.fsAmplSupplier()); }

  /** dBV of the TPDF dither at the given bits (>= 1) - the physical level
   *  relative to the live peak full-scale. */
  _ditherDbvForBits(bits) { return ditherDbvForBits(bits, this.fsAmplSupplier()); }

  /** The (fractional) bit count whose TPDF dither lands at the given dBV -
   *  exact inverse of _ditherDbvForBits, un-clamped. */
  _ditherBitsForDbv(dbv) { return ditherBitsForDbv(dbv, this.fsAmplSupplier()); }

  /** Clamps a non-Off dither depth to [1, maxBits]. */
  _clampBits(bits) { return Math.max(1.0, Math.min(this.max, bits)); }

  // ---- full-scale-relative unit (dBFS) - AMPLITUDE fields with a supplier ----

  /** Canonical Vrms -> dBFS against the live full scale. 0 dBFS ≡ a full-scale SINE (AES17), so
   *  the reference is the RMS full scale (peak/√2) - form-independent, since the stored quantity
   *  is the sine's Vrms. (Deliberately unlike the DITHER policy's PEAK anchor, a noise-floor level
   *  with different semantics.) Only reached on the sticky-dBFS display / step path, where a
   *  successful dBFS commit guaranteed a non-null supplier. */
  _dbfsFromCanonical(canonical) {
    return convert(this.family, canonical, baseToken(this.family),
      unitToken(this.family.fsRelativeUnit()), this.fsAmplSupplier());
  }

  /** dBFS -> canonical Vrms against the live full scale - the inverse of _dbfsFromCanonical. */
  _canonicalFromDbfs(dbfs) {
    return convert(this.family, dbfs, unitToken(this.family.fsRelativeUnit()),
      baseToken(this.family), this.fsAmplSupplier());
  }

  /**
   * What this field currently SHOWS as one entered value: the operator's own
   * figure and the unit it is stated in. That pair is what a preference stores,
   * because a canonical value alone cannot say what was entered - the same
   * voltage is a different dBFS figure under every calibration.
   * @returns {{value: number, unit: string}}
   */
  enteredValue() {
    const u = this._storedUnit();
    let number;
    if (this.policy === POLICY.DITHER) {
      number = u.log && this.value > 0 ? this._ditherDbvForBits(this.value) : this.value;
    } else {
      number = u.fsRelative ? this._dbfsFromCanonical(this.value) : u.fromCanonical(this.value);
    }
    return unitValue(number, unitToken(u));
  }

  /**
   * Replays a stored entered value through the ordinary commit path, so the
   * sticky unit, the clamps and the full-scale resolution are the ones the
   * operator's own typing goes through - there is no second way in.
   * @returns {boolean} false (value unchanged) when the token names no unit of
   *          this family, exactly as a typed suffix would be refused
   */
  seedPair(entered) {
    return this.commit(entered.value + ' ' + entered.unit);
  }

  /** The unit a stored value carries: the sticky logarithmic or
   *  full-scale-relative choice, else the family's base unit. A scaled linear
   *  unit (mV, uV, nV) never sticks - the display auto-ranges through it - so
   *  what such a display means is the base-unit number. A dither of Off has no
   *  level, so it too is the base unit whatever is sticky. */
  _storedUnit() {
    const u = this.currentUnit();
    if (this.policy === POLICY.DITHER && this.value <= 0) return this.family.defaultUnit(this.value);
    return (u.log || u.fsRelative) ? u : this.family.defaultUnit(this.value);
  }

  /** Renders the current dither value: Off, a bit count, or the full-scale-aware
   *  dBV view - per the current (sticky) display unit. */
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
    if (this.currentUnit().log) {   // field shows dBV -> label shows bits
      return this._trimTrailingZeros(this._format(this.value, this.maxDecimals)) + ' ' + this.family.defaultUnit(this.value).suffix();
    }
    return this._format(this._ditherDbvForBits(this.value), DITHER_DBV_DECIMALS) + ' ' + this.family.logUnit().suffix();
  }

  /** Parses a dither entry: `Off` - or any prefix of it (`o`, `of`) - and 0 -> Off;
   *  a bare number or a `bits` suffix -> that bit count (clamped to [1, maxBits],
   *  0 -> Off), possibly fractional; a `dBV` suffix -> the full-scale-aware
   *  fractional bit count (sticks the dBV view). */
  _commitDither(text) {
    const t = String(text).trim().replace(/,/g, '.');
    if (t === '') return false;                       // empty -> unchanged (Java commitDither)
    if (this._isPrefixOf(OFF_LABEL, t)) {             // "o" / "of" / "off" - keep the current view
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
    if (unit.log) {   // dBV -> fractional bits, dBV sticks
      this.stickyUnit = unit;
      this.value = this._clampBits(this._roundSig(this._ditherBitsForDbv(num)));
    } else {          // bits (base): 0 -> Off, else [1, maxBits]
      this.stickyUnit = null;
      this.value = num <= 0 ? 0 : this._clampBits(this._roundSig(num));
    }
    return true;
  }

  /** `x` rendered with `decimals` places, dot decimal separator (Locale.ROOT).
   *  Rounded NUMERICALLY first: toFixed alone keeps the sign of a tiny negative
   *  (a dB round-trip landing a hair under an exact zero would render "-0");
   *  Math.round goes through a signless integer zero. */
  _format(x, decimals) {
    if (Number.isFinite(x)) {
      const pow = Math.pow(10, decimals);
      x = Math.round(x * pow) / pow;
    }
    return x.toFixed(decimals);
  }

  _logGridStep(db, dir) {
    const d = this._roundSig(db) / LOG_WHEEL_STEP_DB;
    const next = (dir > 0) ? Math.floor(d + REL_EPS) + 1 : Math.ceil(d - REL_EPS) - 1;
    return next * LOG_WHEEL_STEP_DB;
  }

  _plusOneDisplayedUnit(dir) {
    const u = this.currentUnit();
    if (u.fsRelative) return this._canonicalFromDbfs(this._dbfsFromCanonical(this.value) + dir);
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
    // A full-scale-relative unit (dBFS) is converted against the live full scale; the only caller
    // reaching that branch is text() via a sticky dBFS currentUnit, so the supplier is non-null.
    const x = u.fsRelative ? this._dbfsFromCanonical(canonical) : u.fromCanonical(canonical);
    const num = (this.decimals >= 0)
      ? this._format(x, this.decimals)
      : this._trimTrailingZeros(this._format(x, this.maxDecimals));
    const suffix = u.suffix();
    return suffix === '' ? num : num + ' ' + suffix;
  }

  stepHint() {
    switch (this.policy) {
      case POLICY.FIXED:
        return `${WHEEL_GLYPH} ±${this._formatStep(this.wheelStep)}, ${ARROWS_GLYPH} ±${this._formatStep(this.arrowStep)}`;
      case POLICY.PERCENT: {
        const u = this.currentUnit();
        // dBV and dBFS step identically - both in dB.
        if (u.log || u.fsRelative) return `${WHEEL_GLYPH} ±${LOG_WHEEL_STEP_DB} dB, ${ARROWS_GLYPH} ±1 dB`;
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
    if (n > SERIES_HINT_MAX) return this._seriesEntry(this.series[0]) + SERIES_SEP + '...' + SERIES_SEP + this._seriesEntry(this.series[n - 1]);
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
      // Unknown suffix -> reject, value unchanged (Java NumericStepModel.commit).
      // Aliases are FAMILY-scoped ("k"/"kh" are kHz only in FREQUENCY, "m"/"u"
      // are mV/µV only in AMPLITUDE) - there is deliberately no generic SI-prefix
      // fallback, so a frequency alias can't parse in a voltage field.
      if (unit == null) return false;
      // A full-scale-relative unit (dBFS) is resolved from the live full scale below; a field with
      // no supplier wired (the FFT manual fundamental) refuses it BEFORE any state is mutated,
      // leaving the value unchanged.
      if (unit.fsRelative && this.fsAmplSupplier == null) return false;
      // Both the log unit (dBV) and the full-scale-relative unit (dBFS) stick: the family's
      // range-based display switching can never select either, so an explicit choice must hold.
      // Linear units always re-enter the automatic switching, so they never stick.
      this.stickyUnit = (unit.log || unit.fsRelative) ? unit : null;
    }
    this.blank = false;
    // 0 dBFS ≡ full-scale SINE (AES17): the stored quantity is the sine's Vrms, so the anchor is
    // the RMS full scale (peak/√2). The ceiling is enforced by the field's own max (set to that
    // same full-scale-sine Vrms), so V, dBV and dBFS entries all clamp to it identically.
    const canonical = unit.fsRelative ? this._canonicalFromDbfs(num) : unit.toCanonical(num);
    this.value = this._clamp(this._roundSig(canonical));
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
   *  rule so a named state can be committed from a single keystroke - o/of/off,
   *  i/in/inf/... - the way the unit suffixes already take a prefix. */
  _isPrefixOf(word, t) {
    return t.length > 0 && t.length <= word.length
        && word.slice(0, t.length).toLowerCase() === t.toLowerCase();
  }

  /** Named-value label match: exact, or - when that named value IS the Off state -
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
// NumericStepField - thin DOM controller over the existing `.numfield` chrome
// ----------------------------------------------------------------------------

const REPEAT_DELAY_MS = 300, REPEAT_PERIOD_MS = 100;

export class NumericStepField {
  /**
   * DOM controller over the `.numfield` chrome - faithful to the Java NumericStepField:
   * the value AUTO-RANGES its unit (1000 -> "1 kHz", 1e-4 V -> "100 µV"), the ▲▼ buttons +
   * wheel + arrow keys step along the model's 1-2-5 / log ladder, and free-text entry
   * parses units ("1.5k", "-3 dBV", "2 kHz"). Exactly like Java's single SWT Text, the
   * FULL composed string ("1 kHz") lives in the input - number and unit together - so an
   * ordinary edit ("1" -> "1.5") keeps its unit ("1.5 kHz" -> 1500 Hz) and an untouched
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

  /** The text to commit - the whole input, verbatim, exactly like Java's
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
    // commit on Enter / blur remains the ONLY gate - invalid text reverts there.

    const commitOrRevert = () => {
      const before = this.model.getValue(), unitBefore = this.model.enteredValue().unit;
      if (this.model.commit(this._committed())) {
        this.refresh();
        if ((!Object.is(before, this.model.getValue())
          || unitBefore !== this.model.enteredValue().unit) && this.onChange) {
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

  // The unit is half of what a preference stores and it can change on its own -
  // the same figure re-entered as dBFS moves the meaning without moving the
  // number - so the snapshot is the entered UNIT, not just the dBV flag.
  _mutate(fn) {
    const before = this.model.getValue(), unitBefore = this.model.enteredValue().unit;
    fn();
    this.refresh();
    if ((!Object.is(before, this.model.getValue())
      || unitBefore !== this.model.enteredValue().unit) && this.onChange) {
      this.onChange(this.model.getValue());
    }
  }

  /** Writes the whole composed string ("1 kHz", "100 µV", or a bare number for
   *  the NONE family) into the input - exactly Java's {@code field.setText(model.text())}. */
  refresh() {
    this.input.value = this.model.text();
    const hint = this.model.stepHint();
    this.input.title = this.tooltipBase ? `${this.tooltipBase} (${hint})` : hint;
  }

  setValue(v) { this.model.setValue(v); this.refresh(); }
  getValue() { return this.model.getValue(); }
  /** Renders the field empty and holding no value - the disabled, never-measured channel
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
  /** What the field SHOWS as one entered value - the pair a preference stores. */
  enteredValue() { return this.model.enteredValue(); }
  /** Replays a stored entered value through the ordinary commit path (sticky unit,
   *  clamps and full-scale resolution included) and re-renders. A SEED, so no
   *  listener fires: what it lays in is what it was read from, and a write-back
   *  would only echo. Also the re-solve after a recalibration - the entered text
   *  lands unchanged and the canonical value underneath it moves. */
  seedPair(entered) { this.model.seedPair(entered); this.refresh(); }
  setDisabled(d) {
    this.disabled = d;
    this.input.disabled = d;
    const field = this.input.closest('.numfield');
    if (field) field.classList.toggle('disabled', d);
    if (this.upBtn) this.upBtn.disabled = d;
    if (this.downBtn) this.downBtn.disabled = d;
  }
}
