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
      case 'AMPLITUDE': return canonical < MICRO_SWITCH ? u[0]
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

const POLICY = { FIXED: 'FIXED', LIST: 'LIST', PERCENT: 'PERCENT' };

export class NumericStepModel {
  /**
   * Three factory shapes mirroring the three Java constructors:
   *   fixed:   { family, min, max, wheelStep, arrowStep, decimals }
   *   list:    { family, min, max, series:[...], maxDecimals }
   *   percent: { family, min, max, maxDecimals }
   */
  constructor(cfg) {
    this.family = cfg.family;
    this.min = cfg.min;
    this.max = cfg.max;
    this.stickyUnit = null;
    this.namedValue = NaN;
    this.namedValueLabel = null;
    if (Array.isArray(cfg.series)) {
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
  }

  getValue() { return this.value; }

  setValue(v) {
    if (Number.isNaN(v)) return;
    this.value = this._clamp(this._roundSig(v));
  }

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
    }
  }

  arrow(dir) {
    switch (this.policy) {
      case POLICY.FIXED: this.setValue(this.value + dir * this.arrowStep); break;
      case POLICY.LIST: this.setValue(this._listJump(dir)); break;
      case POLICY.PERCENT: this.setValue(this._plusOneDisplayedUnit(dir)); break;
    }
  }

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
    if (!Number.isFinite(this.value)) return '∞';
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
    if (!Number.isFinite(v)) return '∞';
    if (this._isNamedValue(v)) return this.namedValueLabel;
    return this._formatIn(v, this.family.displayUnit(v));
  }

  commit(text) {
    if (text == null) return false;
    let s = String(text).trim().replace(/,/g, '.').replace(/μ/g, 'µ');
    if (s === '') return false;
    if (s.length > 1 && s.endsWith('.') && /[0-9]/.test(s.charAt(s.length - 2))) s = s.slice(0, -1);
    if (!Number.isFinite(this.max)) {
      const low = s.toLowerCase();
      if (s === '∞' || low === 'inf' || low === 'infinity') { this.stickyUnit = null; this.value = Infinity; return true; }
    }
    if (this.namedValueLabel != null && s.toLowerCase() === this.namedValueLabel.trim().toLowerCase()) {
      this.stickyUnit = null; this.value = this._clamp(this._roundSig(this.namedValue)); return true;
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
  setMin(m) { this.model.setMin(m); this.refresh(); }
  setMax(m) { this.model.setMax(m); this.refresh(); }
  setLogDisplay(on) { this.model.setLogDisplay(on); this.refresh(); }
  setDisabled(d) {
    this.disabled = d;
    this.input.disabled = d;
    const field = this.input.closest('.numfield');
    if (field) field.classList.toggle('disabled', d);
    if (this.upBtn) this.upBtn.disabled = d;
    if (this.downBtn) this.downBtn.disabled = d;
  }
}
