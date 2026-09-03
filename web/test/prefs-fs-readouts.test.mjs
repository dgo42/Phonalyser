/*
 * Phonalyser web - what the Preferences dialog's ADC/DAC full-scale lines show.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * The maintainer's report: "switching remote device don't show proper ADC/DAC calibration values
 * in preferences dialog until OK. Opening dialog second time - shows remote, server calibration
 * values". The device combos STAGE - nothing is applied until OK - so a readout drawn from the
 * live scalars showed the OUTGOING device's full scale under the incoming device's name.
 *
 * So the lines are asserted to come from the SELECTED device: the server's stored pair for a bench
 * device, this machine's card for a local one, the standing scalars only where neither answers -
 * and the live session is asserted untouched, because a staged pick is display only.
 *
 * Driven on the real prototype with a hand-made "this", as card-section.test.mjs drives the same
 * dialog: what is under test is the resolution and the units, not the modal.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

// Minimal jQuery: one stable node per selector, and text() both writes and reads.
const dom = new Map();
globalThis.$ = (selector) => {
  const key = String(selector);
  let node = dom.get(key);
  if (!node) {
    node = { value: '', text(v) { if (v === undefined) return node.value; node.value = v; return node; } };
    dom.set(key, node);
  }
  return node;
};
console.warn = () => {};

const stored = new Map();
globalThis.localStorage = {
  getItem: (k) => (stored.has(k) ? stored.get(k) : null),
  setItem: (k, v) => { stored.set(k, String(v)); },
  removeItem: (k) => { stored.delete(k); },
  clear: () => { stored.clear(); },
};
if (!globalThis.navigator) globalThis.navigator = { platform: 'Win32', userAgent: 'node' };

const { Preferences } = await import('../js/store/preferences.js');
const { DeviceProfileStore, AudioDeviceProfile, DeviceRange } =
  await import('../js/store/device-profiles.js');
const { PreferencesDialog } = await import('../js/shell/preferences-dialog.js');

const SQRT2 = Math.SQRT2;
const EMPTY_CATALOG = { formatVersion: 1, contentVersion: 0, audioDevices: [] };
let keyCounter = 0;

/** One card - one physical box, so BOTH endpoints - recognising "match", calibrated at inFs /
 *  outFs V RMS on both channels of its input / output. */
function card(name, { match = [name], inFs = 1.0, outFs = 1.0 } = {}) {
  const p = new AudioDeviceProfile();
  p.name = name;
  p.match = [...match];
  for (const [ep, fs] of [[p.input, inFs], [p.output, outFs]]) {
    const range = new DeviceRange();
    range.label = 'default';
    range.fsLeft = fs;
    range.fsRight = fs;
    ep.ranges.push(range);
    ep.activeRange = 'default';
  }
  return p;
}

/**
 * The dialog surface refreshFsReadouts / _applyDeviceProfile touch. "device" is what the combos
 * currently show, and moving it IS the switch the operator makes.
 */
function host({ backend = 'WEB_AUDIO', cards = [], calibration = undefined } = {}) {
  const prefs = new Preferences(true);
  const store = new DeviceProfileStore(prefs, {
    storageKey: 'phonalyser.devices.fsreadout.' + (++keyCounter), catalog: EMPTY_CATALOG,
  });
  for (const p of cards) store.putAudioDeviceProfile(p);
  prefs.backend.set(backend);
  const state = { device: 'Line In (Focusrite)' };
  return Object.assign(Object.create(PreferencesDialog.prototype), {
    prefs,
    deviceStore: store,
    // The seam app.js builds over BenchCards: an object is the bench's own calibration, null is a
    // bench that has none, undefined is a device this bench does not offer at all.
    _cardSource: { calibration: () => calibration },
    inputDeviceLabel: () => state.device,
    outputDeviceLabel: () => state.device,
    state,
    /** The four lines, as the operator reads them. */
    lines: () => ({
      adcL: $('#adcFsVrms').text(), adcR: $('#adcFsVrmsRight').text(),
      dacL: $('#dacFsAmpl').text(), dacR: $('#dacFsAmplRight').text(),
    }),
  });
}

/** The backend combo's own pick: prefs.backend IS what every per-backend lookup keys off, so the
 *  dialog stages the switch there (PreferencesDialog.onBackendChanged). */
function useBackend(h, name) {
  h.prefs.backend.set(name);
}

const refreshFsReadouts = PreferencesDialog.prototype.refreshFsReadouts;
const applyDeviceProfile = PreferencesDialog.prototype._applyDeviceProfile;

// ---------------------------------------------------------------------------
// The switch itself
// ---------------------------------------------------------------------------

test('switching to a BENCH device shows the SERVER\'s values at once, with no OK', () => {
  const h = host({
    cards: [card('Focusrite', { inFs: 1.0, outFs: 1.0 })],
    calibration: { fsRmsLeft: 3.0, fsRmsRight: 4.0 },
  });
  refreshFsReadouts.call(h);
  assert.equal(h.lines().adcL, '1.000000', 'the local card, before the switch');

  useBackend(h, 'net:WASAPI');
  h.state.device = 'Bench line in';
  refreshFsReadouts.call(h);

  assert.deepEqual(h.lines(), {
    adcL: '3.000000', adcR: '4.000000',
    // The DAC line reads in PEAK volts; a card - here the bench's - stores RMS.
    dacL: (3.0 * SQRT2).toFixed(6), dacR: (4.0 * SQRT2).toFixed(6),
  });
});

test('switching BACK to a local device shows that card\'s own values', () => {
  const h = host({
    backend: 'net:WASAPI',
    cards: [card('Focusrite', { inFs: 1.25, outFs: 1.5 })],
    calibration: { fsRmsLeft: 3.0, fsRmsRight: 3.0 },
  });
  h.state.device = 'Bench line in';
  refreshFsReadouts.call(h);
  assert.equal(h.lines().adcL, '3.000000');

  useBackend(h, 'WEB_AUDIO');
  h.state.device = 'Line In (Focusrite)';
  refreshFsReadouts.call(h);

  assert.deepEqual(h.lines(), {
    adcL: '1.250000', adcR: '1.250000',
    dacL: (1.5 * SQRT2).toFixed(6), dacR: (1.5 * SQRT2).toFixed(6),
  });
});

test('a staged pick writes nothing: the live scalars are still the outgoing device\'s', () => {
  const h = host({ calibration: { fsRmsLeft: 3.0, fsRmsRight: 3.0 } });
  h.prefs.setAdcFsVoltageRms(0.75);
  h.prefs.setDacFsVoltageAmpl(2.0);

  useBackend(h, 'net:WASAPI');
  h.state.device = 'Bench line in';
  refreshFsReadouts.call(h);

  assert.equal(h.lines().adcL, '3.000000', 'the bench\'s value is on screen');
  assert.equal(h.prefs.getAdcFsVoltageRms('L'), 0.75, 'and nothing of the running session moved');
  assert.equal(h.prefs.getDacFsVoltageAmpl('L'), 2.0);
});

test('a bench device the server has no calibration for shows what a measurement would use', () => {
  const h = host({ calibration: null });
  h.prefs.setAdcFsVoltageRms(0.75);
  h.prefs.setDacFsVoltageAmpl(2.0);

  useBackend(h, 'net:WASAPI');
  h.state.device = 'Uncalibrated bench in';
  refreshFsReadouts.call(h);

  // Not the local card of the same name (a different exemplar) and not a blank: with nothing
  // applied for such a device, the standing scalars are literally what it would be measured by.
  assert.equal(h.lines().adcL, '0.750000');
  assert.equal(h.lines().dacL, '2.000000');
});

// ---------------------------------------------------------------------------
// One decision, two consumers: what is SHOWN is what an OK would APPLY
// ---------------------------------------------------------------------------

test('the OK apply lands exactly on the values the lines showed - bench', () => {
  const h = host({ backend: 'net:WASAPI', calibration: { fsRmsLeft: 3.0, fsRmsRight: 4.0 } });
  h.state.device = 'Bench line in';
  refreshFsReadouts.call(h);
  const shown = h.lines();

  applyDeviceProfile.call(h, 'Bench line in', true);
  applyDeviceProfile.call(h, 'Bench line in', false);

  assert.equal(h.prefs.getAdcFsVoltageRms('L').toFixed(6), shown.adcL);
  assert.equal(h.prefs.getAdcFsVoltageRms('R').toFixed(6), shown.adcR);
  assert.equal(h.prefs.getDacFsVoltageAmpl('L').toFixed(6), shown.dacL);
  assert.equal(h.prefs.getDacFsVoltageAmpl('R').toFixed(6), shown.dacR);
});

test('the OK apply lands exactly on the values the lines showed - local card', () => {
  const h = host({ cards: [card('Focusrite', { inFs: 1.25, outFs: 1.5 })] });
  refreshFsReadouts.call(h);
  const shown = h.lines();

  applyDeviceProfile.call(h, 'Line In (Focusrite)', true);
  applyDeviceProfile.call(h, 'Line In (Focusrite)', false);

  assert.equal(h.prefs.getAdcFsVoltageRms('L').toFixed(6), shown.adcL);
  assert.equal(h.prefs.getDacFsVoltageAmpl('L').toFixed(6), shown.dacL);
});
