/*
 * Phonalyser web - QA40x raw <-> volts level math.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Mirrors org.edgo.audio.measure.sound.qa40x.Qa40xLevelsTest, pinning the range
 * semantics of the REAL device (bench 2026-07-20, doc §6 cheat-sheet -
 * supersedes the 2026-07-17 mock ruling): the OUTPUT label is genuine per-leg
 * RMS dBV, while the INPUT "N dBV" label is a Vpp-differential (dBFS) clip
 * reference - the input's true RMS full scale sits 9 dB below its label (+3
 * peak/RMS and +6 differential ×2) - plus the DAC peak-volts convention
 * (including the RMS-trap ~3 dB error).
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  MAXINT, adcVolts, dacInt32, inputFullScaleRmsVolts, outputFullScaleRmsVolts,
} from '../js/qa40x/qa40x-levels.js';

const TOL = 1e-6;

/** assertEquals(expected, actual, delta) in the JUnit argument order. */
const near = (expected, actual, delta, what) =>
  assert.ok(Math.abs(expected - actual) <= delta, `${what}: got ${actual}, want ${expected} ±${delta}`);

test('adcVolts: full scale is half the Vpp-differential clip', () => {
  // The "N dBV" input label is the Vpp-DIFFERENTIAL clip: 10^(N/20) is
  // peak-to-peak, so a full-scale sample (±MAXINT) is its half - the peak - and
  // a full-scale sine reads ≈ (N − 9) dBV RMS (doc §6).
  near(0.5, adcVolts(MAXINT, 0, 1.0), TOL, '0 dBV range, cal 1');
  near(Math.pow(10.0, 6.0 / 20.0) / 2.0, adcVolts(MAXINT, 6, 1.0), TOL, '6 dBV range, cal 1');
  near(Math.pow(10.0, 6.0 / 20.0), adcVolts(MAXINT, 6, 2.0), TOL, '6 dBV range, cal 2');
});

test('adcVolts: scales linearly with raw', () => {
  const full = adcVolts(MAXINT, 6, 1.0);
  const half = adcVolts(Math.trunc(MAXINT / 2), 6, 1.0);
  near(full / 2.0, half, 1e-3, 'half scale');
});

test('dacInt32: uses the -(dbv+3) exponent', () => {
  // Independent re-derivation of the -(dbv+3) exponent at mid-scale (no saturation).
  const expected = Math.round(Math.pow(10.0, -(18 + 3) / 20.0) * MAXINT);
  assert.equal(dacInt32(1.0, 18, 1.0), expected);
});

test('dacInt32: the RMS trap lands ~3 dB low', () => {
  const vrms = 1.0;
  const correct = dacInt32(Math.sqrt(2.0) * vrms, 18, 1.0);   // PEAK - correct
  const trap = dacInt32(vrms, 18, 1.0);                       // RMS - the ~3 dB trap
  const errDb = 20.0 * Math.log10(correct / trap);
  near(3.0103, errDb, 0.01, 'RMS-trap error');
});

test('dacInt32: saturates to ±MAXINT', () => {
  assert.equal(dacInt32(1000.0, 18, 1.0), MAXINT);
  assert.equal(dacInt32(-1000.0, 18, 1.0), -MAXINT);
});

test('inputFullScaleRmsVolts: 9 dB below the label', () => {
  // The input label is a Vpp-differential (dBFS) reference: RMS full scale =
  // 10^(N/20)/(2√2), i.e. a constant 9.03 dB below the label (doc §6;
  // supersedes the mock-era "label = RMS" bench case).
  const fs18 = inputFullScaleRmsVolts(18, 1.0);
  near(Math.pow(10.0, 18 / 20.0) / (2.0 * Math.sqrt(2.0)), fs18, TOL, 'exact 10^(N/20)/(2√2)');
  near(Math.pow(10.0, (18 - 9.0) / 20.0), fs18, 0.02, 'label − 9 dB');
});

test('outputFullScaleRmsVolts: near the nominal dBV', () => {
  const fs18 = outputFullScaleRmsVolts(18, 1.0);
  near(7.9339039, fs18, 1e-4, '18 dBV output full scale');
  // 10^(3/20) ≈ √2, so FS RMS ≈ nominal 10^(18/20) = 7.9433 (within ~0.12 %).
  near(Math.pow(10.0, 18 / 20.0), fs18, 0.02, 'nominal 10^(18/20)');
});

test('full-scale RMS: the input sits 9 dB below the output at matching labels', () => {
  // The mock-era symmetry ruling is superseded (doc §6, bench 2026-07-20): a
  // digital mock loopback shows no differential doubling, so it could not
  // surface the input's 9 dB offset. At matching labels the output full scale is
  // 2·10^(3/20) (= +9.03 dB) above the input full scale - one √2 (peak/RMS) plus
  // one ×2 (differential), for every range.
  for (const dbv of [0, 6, 12, 18]) {
    const ratio = outputFullScaleRmsVolts(dbv, 1.0) / inputFullScaleRmsVolts(dbv, 1.0);
    near(2.0 * Math.pow(10.0, 3.0 / 20.0), ratio, TOL, `ratio at ${dbv} dBV`);
  }
});
