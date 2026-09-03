/*
 * Phonalyser web - values stored AS ENTERED (number plus unit), and resolved at use.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * The defect these pin: a value entered in a calibration-dependent unit was stored only in its
 * canonical form, so a backend or device change - or a reload against a different DAC calibration -
 * silently redefined what had been entered. What is stored now is the pair the operator typed, and
 * the canonical readings resolve it against the LIVE full scale at every read.
 *
 * The twin of the Java EnteredUnitPairTest, including its D1 regression proof: a combined
 * number-and-unit edit is ONE notification carrying a whole value, never the new unit against the
 * old number.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

const stored = new Map();
globalThis.localStorage = {
  getItem: (k) => (stored.has(k) ? stored.get(k) : null),
  setItem: (k, v) => { stored.set(k, String(v)); },
  removeItem: (k) => { stored.delete(k); },
  clear: () => { stored.clear(); },
};
if (!globalThis.navigator) globalThis.navigator = { platform: 'Win32', userAgent: 'node' };

const { Preferences } = await import('../js/store/preferences.js');
const { UNIT_FAMILIES, unitValue, baseToken, logToken, convert }
  = await import('../js/widgets/unit-conversion.js');
const { ditherBitsForDbv, ditherDbvForBits } = await import('../js/dsp/dither-math.js');

const EPS = 1e-9;
/** A DAC peak full scale to work against, and its double, so a value that depends on the
 *  calibration provably moves with it. */
const FS_AMPL = 2.0;
const FS_AMPL_DOUBLED = 4.0;
const SQRT2 = Math.SQRT2;
const VOLT = baseToken(UNIT_FAMILIES.AMPLITUDE);
const BITS = baseToken(UNIT_FAMILIES.DITHER);
const DBV = logToken(UNIT_FAMILIES.AMPLITUDE);
const DBFS = 'dbfs';

/** A store with no disk behind it (transientMode), as the other store tests build one. */
function freshPrefs() {
  stored.clear();
  return new Preferences(true);
}

/** The document the store writes, and the reader that takes one back. */
const written = (prefs) => prefs._toMap();
const read = (prefs, doc) => prefs._fromMap(doc);

test('an entered value survives save and load exactly as entered', () => {
  const prefs = freshPrefs();
  prefs.genAmplitude.set(unitValue(-6.0, DBFS));
  prefs.genDither.set(unitValue(-100.0, DBV));
  prefs.freqRespAmplitude.set(unitValue(-3.0, DBV));
  prefs.tuneNotchAmplitude.set(unitValue(0.25, VOLT));

  // On disk it stays two plain keys per field - the number where it always was, the unit beside it.
  const doc = written(prefs);
  assert.equal(doc.genAmplitude, -6.0);
  assert.equal(doc.genAmplitudeUnit, DBFS);
  assert.equal(doc.genDither, -100.0);
  assert.equal(doc.genDitherUnit, DBV);
  assert.ok(!('genAmplitudeVrms' in doc), 'the canonical-only key is gone - the entered value is the storage');
  assert.ok(!('genDitherDbvDisplay' in doc), 'the display flag is the unit token now');

  const back = freshPrefs();
  read(back, doc);
  assert.deepEqual(back.genAmplitude.get(), { value: -6.0, unit: DBFS });
  assert.deepEqual(back.genDither.get(), { value: -100.0, unit: DBV });
  assert.deepEqual(back.freqRespAmplitude.get(), { value: -3.0, unit: DBV });
  assert.deepEqual(back.tuneNotchAmplitude.get(), { value: 0.25, unit: VOLT });
});

test('the entered figures are unmoved by a full-scale change, and the readings re-solve', () => {
  const prefs = freshPrefs();
  prefs.setDacFsVoltageAmpl(FS_AMPL);
  prefs.genAmplitude.set(unitValue(-6.0, DBFS));
  prefs.genDither.set(unitValue(-100.0, DBV));
  const vrmsBefore = prefs.genAmplitudeVrms.get();
  const bitsBefore = prefs.genDitherBits.get();
  assert.ok(Math.abs(vrmsBefore - FS_AMPL / SQRT2 * Math.pow(10, -6 / 20)) < EPS);
  assert.ok(Math.abs(bitsBefore - ditherBitsForDbv(-100.0, FS_AMPL)) < EPS);

  prefs.setDacFsVoltageAmpl(FS_AMPL_DOUBLED);
  assert.deepEqual(prefs.genAmplitude.get(), { value: -6.0, unit: DBFS }, 'the entered value is untouched');
  assert.deepEqual(prefs.genDither.get(), { value: -100.0, unit: DBV });
  assert.ok(Math.abs(prefs.genAmplitudeVrms.get() - 2 * vrmsBefore) < EPS,
    'twice the full scale is twice the volts for the same dBFS');
  assert.ok(Math.abs(prefs.genDitherBits.get() - ditherBitsForDbv(-100.0, FS_AMPL_DOUBLED)) < EPS,
    'the same level is one more bit under twice the full scale');
  assert.ok(prefs.genDitherBits.get() > bitsBefore);
});

test('a unit-only edit fires once', () => {
  const prefs = freshPrefs();
  prefs.genAmplitude.set(unitValue(1.0, VOLT));
  const seen = [];
  prefs.genAmplitude.addListener((v) => seen.push(v));
  // Same figure, another unit: 1 dBV is not 1 V. A consumer watching a number alone would never
  // learn that the drive changed.
  prefs.genAmplitude.set(unitValue(1.0, DBV));
  assert.equal(seen.length, 1, 'exactly one notification');
  assert.deepEqual(seen[0], { value: 1.0, unit: DBV });
  assert.ok(Math.abs(prefs.genAmplitudeVrms.get() - Math.pow(10, 1 / 20)) < EPS);
});

test('re-writing the same entered value notifies nobody', () => {
  const prefs = freshPrefs();
  prefs.genAmplitude.set(unitValue(0.5, VOLT));
  const seen = [];
  prefs.genAmplitude.addListener((v) => seen.push(v));
  // A frozen pair is a NEW object every time; without value equality the field echoing back what
  // it was just handed would loop.
  prefs.genAmplitude.set(unitValue(0.5, VOLT));
  assert.equal(seen.length, 0, 'an equal pair is a no-op write');
});

test('a combined number-and-unit edit notifies ONCE, and never with a mixed value', () => {
  // Regression guard: while the number and the unit were two entries, an edit that moved both was
  // two writes - and the first notification carried the NEW unit against the OLD number. A dither
  // going from Off to -100 dBV momentarily resolved as a real depth of about one bit, which
  // reached the running lane. One value, one write, one event.
  const prefs = freshPrefs();
  prefs.setDacFsVoltageAmpl(FS_AMPL);
  prefs.genDither.set(unitValue(0.0, BITS));
  const seen = [], resolved = [];
  prefs.genDither.addListener((v) => {
    seen.push(v);
    // Read the way every consumer does - through the converting reading.
    resolved.push(prefs.genDitherBits.get());
  });
  prefs.genDither.set(unitValue(-100.0, DBV));
  assert.equal(seen.length, 1, 'one edit, one notification');
  assert.deepEqual(seen[0], { value: -100.0, unit: DBV });
  assert.ok(Math.abs(resolved[0] - ditherBitsForDbv(-100.0, FS_AMPL)) < EPS,
    'the value seen by a consumer is the one that was entered, whole');
});

test('a pre-pair document converts once, per field', () => {
  const prefs = freshPrefs();
  const fs = prefs.getDacFsVoltageAmpl();     // the store's own scale, what the conversion uses
  read(prefs, {
    genAmplitudeVrms: 0.5,
    genAmplitudeDbvDisplay: true,
    genDitherBits: 12.0,
    genDitherDbvDisplay: true,
    freqRespAmplitudeVrms: 1.5,
    freqRespAmplitudeDbvDisplay: false,
    tuneNotchAmplitudeVrms: 0.75,
  });
  assert.equal(prefs.genAmplitude.get().unit, DBV);
  assert.ok(Math.abs(prefs.genAmplitude.get().value - 20 * Math.log10(0.5)) < EPS);
  assert.ok(Math.abs(prefs.genAmplitudeVrms.get() - 0.5) < EPS, 'the canonical value is unchanged');
  assert.equal(prefs.genDither.get().unit, DBV);
  assert.ok(Math.abs(prefs.genDither.get().value - ditherDbvForBits(12.0, fs)) < EPS);
  assert.deepEqual(prefs.freqRespAmplitude.get(), { value: 1.5, unit: VOLT }, 'no flag - volts as entered');
  assert.deepEqual(prefs.tuneNotchAmplitude.get(), { value: 0.75, unit: VOLT },
    'that field never had a flag');
});

test('a dither of Off converts to the base unit whatever the old flag said', () => {
  const prefs = freshPrefs();
  read(prefs, { genDitherBits: 0.0, genDitherDbvDisplay: true });
  assert.deepEqual(prefs.genDither.get(), { value: 0.0, unit: BITS });
  assert.equal(prefs.genDitherBits.get(), 0.0);
});

test('a stored entered value wins over the pre-pair keys of the same field', () => {
  const prefs = freshPrefs();
  read(prefs, {
    genAmplitude: -12.0,
    genAmplitudeUnit: DBV,
    genAmplitudeVrms: 0.5,                    // stale, from an older release
    genAmplitudeDbvDisplay: false,
  });
  assert.deepEqual(prefs.genAmplitude.get(), { value: -12.0, unit: DBV });
});

test('a canonical write stores the base unit', () => {
  const prefs = freshPrefs();
  prefs.genAmplitude.set(unitValue(-6.0, DBFS));
  prefs.genAmplitudeVrms.set(0.25);
  assert.deepEqual(prefs.genAmplitude.get(), { value: 0.25, unit: VOLT });
  prefs.genDither.set(unitValue(-100.0, DBV));
  prefs.genDitherBits.set(8.0);
  assert.deepEqual(prefs.genDither.get(), { value: 8.0, unit: BITS });
});

test('a floor is expressed in the unit that is stored', () => {
  const prefs = freshPrefs();
  prefs.setDacFsVoltageAmpl(FS_AMPL);
  prefs.freqRespAmplitude.set(unitValue(-60.0, DBV));
  const floor = prefs.amplitudeIn(prefs.freqRespAmplitude, 1e-4);
  assert.equal(floor.unit, DBV, 'clamping must not move the operator back to volts');
  assert.ok(Math.abs(floor.value - 20 * Math.log10(1e-4)) < EPS);
});

test('a freqresp preset carries the entered value, and an old one reads as volts', () => {
  const prefs = freshPrefs();
  read(prefs, {
    freqRespPresets: {
      old: { startHz: 20, stopHz: 20000, amplitudeVrms: 0.75 },
      recent: { startHz: 20, stopHz: 20000, amplitude: -6.0, amplitudeUnit: DBFS },
    },
  });
  const old = prefs.freqRespPresets.get('old');
  assert.equal(old.amplitude, 0.75);
  assert.equal(old.amplitudeUnit, VOLT, 'a canonical-only preset IS a base-unit entry');
  const recent = prefs.freqRespPresets.get('recent');
  assert.equal(recent.amplitude, -6.0);
  assert.equal(recent.amplitudeUnit, DBFS);

  const doc = written(prefs);
  assert.equal(doc.freqRespPresets.recent.amplitude, -6.0);
  assert.equal(doc.freqRespPresets.recent.amplitudeUnit, DBFS);
});

test('the token vocabulary is the unit key suffix, in any locale', () => {
  // Tokens are resolved from the unit KEY, never from the localized suffix, so a stored value
  // means the same thing whatever language the app runs in.
  assert.equal(VOLT, 'v');
  assert.equal(BITS, 'bits');
  assert.equal(DBV, 'dbv');
  assert.ok(Math.abs(convert(UNIT_FAMILIES.AMPLITUDE, -6, DBFS, VOLT, 2.0)
    - 2.0 / SQRT2 * Math.pow(10, -6 / 20)) < EPS);
  assert.ok(Math.abs(convert(UNIT_FAMILIES.DITHER, 12, BITS, DBV, 2.0)
    - ditherDbvForBits(12, 2.0)) < EPS);
  // An unknown token reads as the base unit rather than poisoning the value with NaN.
  assert.equal(convert(UNIT_FAMILIES.AMPLITUDE, 0.4, 'furlongs', VOLT, 2.0), 0.4);
});
