/*
 * Phonalyser web - QA40x factory calibration page parsing.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Mirrors org.edgo.audio.measure.sound.qa40x.Qa40xCalibrationTest: parsing against
 * a synthetic 512-byte page built from the doc/QA40X-PROTOCOL.md §6 offset tables -
 * every ADC range (24...108) and DAC range (120...156), both channels (right = left + 6)
 * - plus the fromTransport() read procedure (select 0x10, 128 reads of reg 0x19,
 * little-endian repack).
 *
 * The transport double is the SHARED one (test/fake-qa40x-transport.mjs), exactly as
 * the Java test uses the shared FakeTransport: a private copy drifts from the real
 * double and quietly stops proving the same seam.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  CAL_PAGE_BYTES, CAL_READ_COUNT, Qa40xCalibration,
} from '../js/qa40x/qa40x-calibration.js';
import {
  CAL_PAGE_SELECT_VALUE, REG_CAL_PAGE_SELECT, REG_CAL_READ,
  inputRangeCode, inputRangeDbvValues, outputRangeCode, outputRangeDbvValues,
} from '../js/qa40x/qa40x-protocol.js';
import { FakeTransport } from './fake-qa40x-transport.mjs';

const TOL = 1e-9;
const RECORD_BYTES = 6;
const ADC_BASE = 24;
const DAC_BASE = 120;
const RANGE_STRIDE = 12;
const LE = true;

/** assertEquals(expected, actual, delta) in the JUnit argument order. */
const near = (expected, actual, delta, what) =>
  assert.ok(Math.abs(expected - actual) <= delta, `${what}: got ${actual}, want ${expected} ±${delta}`);

/** Writes one (int16 level, float32 dB) little-endian record at `offset`. */
function putRecord(page, offset, level, db) {
  page.setInt16(offset, level, LE);
  page.setFloat32(offset + 2, db, LE);
}

/** A synthetic cal page: ADC dB = 1+code (left) / -(1+code) (right), DAC dB = 2+code / -(2+code). */
function syntheticBlob() {
  const blob = new Uint8Array(CAL_PAGE_BYTES);
  const page = new DataView(blob.buffer);
  for (const dbv of inputRangeDbvValues()) {
    const code = inputRangeCode(dbv);
    const leftOffset = ADC_BASE + code * RANGE_STRIDE;
    putRecord(page, leftOffset, dbv, 1.0 + code);
    putRecord(page, leftOffset + RECORD_BYTES, dbv, -(1.0 + code));
  }
  for (const dbv of outputRangeDbvValues()) {
    const code = outputRangeCode(dbv);
    const leftOffset = DAC_BASE + code * RANGE_STRIDE;
    putRecord(page, leftOffset, dbv, 2.0 + code);
    putRecord(page, leftOffset + RECORD_BYTES, dbv, -(2.0 + code));
  }
  return blob;
}

const linear = (db) => Math.pow(10.0, db / 20.0);

test('fromBlob: parses every ADC range and channel', () => {
  const cal = Qa40xCalibration.fromBlob(syntheticBlob());
  for (const dbv of inputRangeDbvValues()) {
    const code = inputRangeCode(dbv);
    near(linear(1.0 + code), cal.adcLinearFactor(dbv, false), TOL, `ADC left factor at ${dbv} dBV`);
    near(linear(-(1.0 + code)), cal.adcLinearFactor(dbv, true), TOL, `ADC right factor at ${dbv} dBV`);
  }
});

test('fromBlob: parses every DAC range and channel', () => {
  const cal = Qa40xCalibration.fromBlob(syntheticBlob());
  for (const dbv of outputRangeDbvValues()) {
    const code = outputRangeCode(dbv);
    near(linear(2.0 + code), cal.dacLinearFactor(dbv, false), TOL, `DAC left factor at ${dbv} dBV`);
    near(linear(-(2.0 + code)), cal.dacLinearFactor(dbv, true), TOL, `DAC right factor at ${dbv} dBV`);
  }
});

test('fromTransport: runs the read procedure and matches the blob', async () => {
  const blob = syntheticBlob();
  const fake = new FakeTransport();
  // Each register-read reply is the little-endian decode of a 4-byte group;
  // fromTransport re-packs it little-endian, round-tripping to the same page.
  const words = new DataView(blob.buffer);
  for (let i = 0; i < CAL_READ_COUNT; i++) {
    fake.readReplies.push(words.getInt32(i * 4, LE));
  }

  const cal = await Qa40xCalibration.fromTransport(fake);

  // Cal-page select is written first (reg 0x0D = 0x10) - and the ORDER is pinned
  // through the double's one ordered op log, not just "a select happened
  // somewhere": reading the 128 words before selecting the page returns whatever
  // page the device was left on (§6), which parses into plausible-looking garbage
  // and silently mis-scales every measurement.
  assert.deepEqual(fake.registerWrites, [{ reg: REG_CAL_PAGE_SELECT, value: CAL_PAGE_SELECT_VALUE }],
    'exactly one register write: the page select');
  assert.deepEqual(fake.ops, [
    `reg=${REG_CAL_PAGE_SELECT}:${CAL_PAGE_SELECT_VALUE}`,
    ...Array(CAL_READ_COUNT).fill(`read=${REG_CAL_READ}`),
  ], 'page select FIRST, then exactly 128 reads of reg 0x19 and nothing else');
  // Same factors as parsing the page directly.
  const reference = Qa40xCalibration.fromBlob(blob);
  for (const dbv of inputRangeDbvValues()) {
    near(reference.adcLinearFactor(dbv, false), cal.adcLinearFactor(dbv, false), TOL, `ADC left ${dbv}`);
    near(reference.adcLinearFactor(dbv, true), cal.adcLinearFactor(dbv, true), TOL, `ADC right ${dbv}`);
  }
  for (const dbv of outputRangeDbvValues()) {
    near(reference.dacLinearFactor(dbv, false), cal.dacLinearFactor(dbv, false), TOL, `DAC left ${dbv}`);
    near(reference.dacLinearFactor(dbv, true), cal.dacLinearFactor(dbv, true), TOL, `DAC right ${dbv}`);
  }
});

test('fromBlob: rejects a short page', () => {
  assert.throws(() => Qa40xCalibration.fromBlob(new Uint8Array(256)), /at least 512 bytes/);
});
