/*
 * Phonalyser web - dBFS amplitude entry on the DAC-driven amplitude fields.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Mirrors the Java NumericStepModelTest cases amplitude_shortDbvAliases_dAndDb,
 * amplitude_dbfsEntry_anchorsToRmsFullScaleSine and
 * amplitude_dbfsRejectedWithoutSupplier_leavesValueUnchanged.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { NumericStepModel, UNIT_FAMILIES as F } from '../js/widgets/numeric-step-field.js';
import { rawRms } from '../js/generator/dds-kernel.js';
import { GenSignalForm } from '../js/enums/gen-signal-form.js';

const FS_PEAK = 4.0;                       // DAC peak full scale (Vpeak)
const FS_RMS = FS_PEAK / Math.SQRT2;       // 0 dBFS ≡ full-scale SINE (AES17) -> 2.8284 Vrms

/** An AMPLITUDE field WITH the live full-scale supplier (generator / FR sweep / tune-notch).
 *  max is the full-scale SINE Vrms (fsPeak/√2) - the single ceiling behind V, dBV and dBFS. */
const withSupplier = () => new NumericStepModel({
  family: F.AMPLITUDE, min: 1e-9, max: FS_RMS, maxDecimals: 5, fsAmplSupplier: () => FS_PEAK,
});
/** An AMPLITUDE field WITHOUT a supplier (the FFT manual fundamental) - must refuse dBFS. */
const noSupplier = () => new NumericStepModel({
  family: F.AMPLITUDE, min: 1e-9, max: 200, maxDecimals: 5,
});

const near = (a, b, tol = 1e-6) => Math.abs(a - b) <= tol;

test('dBFS entry anchors to the RMS full-scale sine', () => {
  const m = withSupplier();
  assert.equal(m.commit('0 dbfs'), true);
  assert.ok(near(m.value, FS_RMS, 1e-4), `0 dBFS -> ${m.value}, want ${FS_RMS}`);

  assert.equal(m.commit('-20 dbfs'), true);
  assert.ok(near(m.value, FS_RMS / 10, 1e-4), `-20 dBFS -> ${m.value}, want ${FS_RMS / 10}`);

  // -6.0206 dBFS is exactly half the full-scale amplitude.
  assert.equal(m.commit('-6.0206 dbfs'), true);
  assert.ok(near(m.value, FS_RMS / 2, 1e-4), `-6.02 dBFS -> ${m.value}`);
});

test('dbf and DBFS are accepted aliases; the case is irrelevant', () => {
  for (const s of ['0 dbfs', '0 DBFS', '0 dbf', '0 DbF']) {
    const m = withSupplier();
    assert.equal(m.commit(s), true, `commit("${s}")`);
    assert.ok(near(m.value, FS_RMS, 1e-4), `${s} -> ${m.value}`);
  }
});

test('dBFS sticks for display, and a suffix-less entry releases it back to volts', () => {
  const m = withSupplier();
  m.commit('-20 dbfs');
  // Assert the sticky-unit STATE, not the localized suffix: these tests run with no i18n bundle,
  // so t() echoes the key ("unit.dbfs") rather than "dBFS".
  assert.equal(m.currentUnit().fsRelative, true, 'dBFS must stick as the display unit');
  assert.equal(m.currentUnit().i18nKey, 'unit.dbfs');
  // Round-trip: the rendered number reads back as what was typed.
  assert.ok(near(parseFloat(m.text()), -20, 1e-3), `dBFS round-trip: ${m.text()}`);

  m.commit('0.5');                       // suffix-less -> the family's base unit (V), sticky released
  assert.ok(!m.currentUnit().fsRelative, `released: ${m.currentUnit().i18nKey}`);
  assert.ok(near(m.value, 0.5, 1e-9), `0.5 -> ${m.value}`);
});

test('the wheel and arrows step dBFS in dB while it is sticky', () => {
  const m = withSupplier();
  m.commit('-20 dbfs');
  const dbfs = (v) => 20 * Math.log10(v * Math.SQRT2 / FS_PEAK);

  m.arrow(+1);                            // arrows: ±1 dB
  assert.ok(near(dbfs(m.value), -19, 1e-6), `arrow up -> ${dbfs(m.value)} dBFS`);

  m.commit('-20 dbfs');
  m.wheel(+1);                            // wheel: the 10 dB grid
  assert.ok(near(dbfs(m.value), -10, 1e-6), `wheel up -> ${dbfs(m.value)} dBFS`);
});

test('a typed 0 dBFS redisplays as 0, not -0', () => {
  // Peak full scale 2.0: the 12-significant-digit canonical rounding lands
  // sqrt(2) a hair BELOW, the back-conversion reads a few 1e-12 dB negative,
  // and toFixed keeps the sign of a tiny negative - the field showed
  // "-0 dBFS".  Mirrors amplitude_zeroDbfsRedisplaysAsZeroNotMinusZero.
  const m = new NumericStepModel({
    family: F.AMPLITUDE, min: 1e-9, max: 2.0 / Math.SQRT2, maxDecimals: 5,
    fsAmplSupplier: () => 2.0,
  });
  assert.equal(m.commit('0 dbfs'), true);
  assert.ok(!m.text().startsWith('-'), `typed zero must not redisplay signed: ${m.text()}`);
  assert.equal(parseFloat(m.text()), 0);
  assert.equal(m.commit('-10 dbfs'), true);
  m.wheel(1);
  assert.ok(!m.text().startsWith('-'), `stepped-to zero must not redisplay signed: ${m.text()}`);
  assert.equal(parseFloat(m.text()), 0);
});

test('0 dBFS is the ceiling: a higher entry clamps', () => {
  const m = withSupplier();
  for (const s of ['+2 dbfs', '2 dbfs', '10 dbfs', '3.01 dbfs']) {
    assert.equal(m.commit(s), true, `commit("${s}")`);
    assert.ok(near(m.value, FS_RMS, 1e-4), `${s} -> ${m.value}, want the full-scale sine ${FS_RMS}`);
  }
});

test('0 dBFS is the ceiling: stepping up saturates there', () => {
  const dbfs = (v) => 20 * Math.log10(v * Math.SQRT2 / FS_PEAK);
  const m = withSupplier();

  m.commit('0 dbfs');
  m.arrow(+1);                                   // already at the ceiling
  assert.ok(near(dbfs(m.value), 0, 1e-9), `arrow at 0 dBFS -> ${dbfs(m.value)}`);
  m.wheel(+1);
  assert.ok(near(dbfs(m.value), 0, 1e-9), `wheel at 0 dBFS -> ${dbfs(m.value)}`);

  m.commit('-4 dbfs');                           // a 10 dB wheel step would overshoot to +6
  m.wheel(+1);
  assert.ok(dbfs(m.value) <= 1e-9, `wheel from -4 dBFS must not exceed 0 -> ${dbfs(m.value)}`);
});

test('the ceiling applies in EVERY unit: V, dBV and dBFS all clamp to the full-scale sine', () => {
  // Volts: above the full-scale sine must clamp (it would only clip) - the reported bug.
  const v = withSupplier();
  assert.equal(v.commit('3.5 v'), true);
  assert.ok(near(v.value, FS_RMS, 1e-4), `3.5 V -> ${v.value}, want ${FS_RMS}`);

  // dBV: the same ceiling expressed in dBV.
  const d = withSupplier();
  const ceilDbv = 20 * Math.log10(FS_RMS);
  assert.equal(d.commit(`${(ceilDbv + 6).toFixed(3)} dbv`), true);
  assert.ok(near(d.value, FS_RMS, 1e-4), `+6 dB over ceiling in dBV -> ${d.value}`);

  // dBFS: 0 dBFS IS the ceiling.
  const f = withSupplier();
  assert.equal(f.commit('+2 dbfs'), true);
  assert.ok(near(f.value, FS_RMS, 1e-4), `+2 dBFS -> ${f.value}`);
});

test('the no-clip ceiling is waveform-aware: fsPeak · rawRms(form)', () => {
  // The DDS drives amplitude = Vrms / (fsPeak · rawRms(form)), so digital full scale - the onset of
  // clipping - is Vrms = fsPeak · rawRms(form). Each form therefore has its own headroom.
  const cases = [
    [GenSignalForm.SINE, FS_PEAK / Math.SQRT2],
    [GenSignalForm.RECTANGLE, FS_PEAK],
    [GenSignalForm.TRIANGLE, FS_PEAK / Math.sqrt(3)],
  ];
  for (const [form, expectedMax] of cases) {
    assert.ok(near(FS_PEAK * rawRms(form), expectedMax, 1e-9), `rawRms(${form}) ceiling`);
    const m = new NumericStepModel({
      family: F.AMPLITUDE, min: 1e-9, max: FS_PEAK * rawRms(form), maxDecimals: 5,
      fsAmplSupplier: () => FS_PEAK,
    });
    m.commit('99 v');                                  // far over scale -> clamps to this form's max
    assert.ok(near(m.value, expectedMax, 1e-4), `${form} clamps to ${expectedMax}, got ${m.value}`);
  }
  // A rectangle may legitimately reach fsPeak - 3 dB above the sine ceiling.
  assert.ok(FS_PEAK * rawRms(GenSignalForm.RECTANGLE) > FS_PEAK * rawRms(GenSignalForm.SINE),
    'a rectangle has more Vrms headroom than a sine');
});

test('d and db are dBV aliases on an amplitude field', () => {
  for (const s of ['-6 db', '-6 d', '-6 dbv']) {
    const m = withSupplier();
    assert.equal(m.commit(s), true, `commit("${s}")`);
    // dBV is an absolute-volts unit: -6 dBV = 10^(-6/20) V ≈ 0.5012 Vrms.
    assert.ok(near(m.value, Math.pow(10, -6 / 20), 1e-6), `${s} -> ${m.value}`);
  }
});

test('dBFS is refused with no supplier, leaving the value untouched', () => {
  const m = noSupplier();
  m.commit('1 v');
  const before = m.value;
  assert.equal(m.commit('0 dbfs'), false, 'dBFS must be refused');
  assert.equal(m.value, before, 'value must be unchanged');
  assert.ok(!/dBFS/.test(m.text()), `no sticky dBFS: ${m.text()}`);
  // dBV still works on that field.
  assert.equal(m.commit('-6 dbv'), true);
});
