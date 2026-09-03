/*
 * Phonalyser web - the scope's calibrate-button gate (Java ScopePane.refreshCalibrateButtonEnabled).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Bench bug: "server backend: scope - adc calibration button is disabled even if amplitude > 0.5V".
 * The 25 % rule presumes a full scale worth measuring against; on a selection with no calibration
 * the volts on screen are a provisional full scale times a normalized reading, so the operator
 * cannot see how far off the threshold they are - and the calibration they are refused is what
 * would make the number real. The gate therefore drops to an anti-silence floor there, and keeps
 * the accuracy rule for a calibrated selection.
 *
 * Driven on the real prototype with a hand-made `this`, as the other shell tests do.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

const nodes = new Map();
globalThis.$ = (sel) => {
  const key = String(sel);
  if (!nodes.has(key)) nodes.set(key, { props: {} });
  const node = nodes.get(key);
  return { prop: (name, v) => { node.props[name] = v; return node; } };
};

const { ScopeTabControl } = await import('../js/scope/scope-tab-control.js');

const syncCalibrateEnabled = ScopeTabControl.prototype.syncCalibrateEnabled;

/** The control surface syncCalibrateEnabled touches. fsVrms 1.0 -> full scale 2.83 Vpp. */
function control({ vpp, uncalibrated = false, recording = true, fsVrms = 1.0 }) {
  return Object.assign(Object.create(ScopeTabControl.prototype), {
    view: { latest: vpp == null ? null : { vpp } },
    prefs: { adcFsVoltageRms: { get: () => fsVrms } },
    pane: { recState: () => recording },
    _inputUncalibrated: () => uncalibrated,
  });
}

function enabled() {
  return nodes.get('#scopeCalibrate').props.disabled === false;
}

test('a CALIBRATED selection keeps the 25 % accuracy gate', () => {
  syncCalibrateEnabled.call(control({ vpp: 0.5 }));            // 0.5 / 2.83 = 18 %
  assert.equal(enabled(), false, 'under a quarter of full scale - too noisy to calibrate from');

  syncCalibrateEnabled.call(control({ vpp: 1.0 }));            // 1.0 / 2.83 = 35 %
  assert.equal(enabled(), true);
});

test('a CALIBRATED wide range is gated on occupancy, not on absolute volts', () => {
  // The Cosmos 10 V position: full scale 28.3 Vpp, a 1.03 Vrms sine reads
  // 2.9 Vpp = 10 % of the range. A healthy absolute level is no longer an
  // alternative to the accuracy rule - the fraction gate alone decides, so this
  // refuses however many volts it carries.
  syncCalibrateEnabled.call(control({ vpp: 2.9, fsVrms: 10.0 }));
  assert.equal(enabled(), false, 'under a quarter of a wide range - refused');

  // Well under the rule on the same range: refused for the same reason.
  syncCalibrateEnabled.call(control({ vpp: 1.2, fsVrms: 10.0 }));
  assert.equal(enabled(), false, '1.2 Vpp is 4 % of a 28.3 Vpp full scale');

  // And the rule opens on the same range once a quarter of it is occupied.
  syncCalibrateEnabled.call(control({ vpp: 8.0, fsVrms: 10.0 }));
  assert.equal(enabled(), true, '8.0 / 28.3 = 28 % - the accuracy rule is met');
});

test('an UNCALIBRATED selection is not gated on volts it has no full scale for', () => {
  syncCalibrateEnabled.call(control({ vpp: 0.5, uncalibrated: true }));
  assert.equal(enabled(), true, 'this is the bench case: 0.5 V on a server backend');

  // Far below the accuracy rule and still offered - the correction self-corrects from any seed.
  syncCalibrateEnabled.call(control({ vpp: 0.01, uncalibrated: true }));
  assert.equal(enabled(), true);
});

test('silence is still refused, calibrated or not', () => {
  syncCalibrateEnabled.call(control({ vpp: 0, uncalibrated: true }));
  assert.equal(enabled(), false);

  // Below the -60 dBFS p-p floor: a reading of the converter's own noise.
  syncCalibrateEnabled.call(control({ vpp: 0.0001, uncalibrated: true }));
  assert.equal(enabled(), false, '0.0001 / 2.83 = 0.004 % - silence, not a signal');
});

test('no capture and no measurement are refused whatever the calibration says', () => {
  syncCalibrateEnabled.call(control({ vpp: 2.0, uncalibrated: true, recording: false }));
  assert.equal(enabled(), false);

  syncCalibrateEnabled.call(control({ vpp: null, uncalibrated: true }));
  assert.equal(enabled(), false);
});

test('a control built without the seam gates exactly as it always did', () => {
  const plain = Object.assign(Object.create(ScopeTabControl.prototype), {
    view: { latest: { vpp: 0.5 } },
    prefs: { adcFsVoltageRms: { get: () => 1.0 } },
    pane: { recState: () => true },
    _inputUncalibrated: () => false,
  });
  syncCalibrateEnabled.call(plain);
  assert.equal(enabled(), false);
});
