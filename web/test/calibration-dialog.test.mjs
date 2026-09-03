/*
 * Phonalyser web - where an ADC/DAC calibrate WRITE goes (Java gui/sound/CalibrationStore).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * The maintainer's ruling: "if calibration is made (ADC/DAC calibration dialog) calibration should
 * happen on server if server device is connected". So the destination is the device's OWN machine
 * - this one's card store for a local device, the bench's store (spec 4.3 device.setCalibration,
 * under that device's lock) for a device plugged into a server - and a bench that REFUSES the
 * write must leave this client holding exactly what the bench still holds.
 *
 * Driven on the real prototype with a hand-made `this`, as card-section.test.mjs drives the
 * Preferences dialog: what is under test is the routing and the arithmetic, not the modal.
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
globalThis.$ = () => ({ text: () => {}, on: () => {}, addClass: () => {}, removeClass: () => {} });
console.warn = () => {};   // headless there is no bundle, so t() warns once per key and returns it

const { Preferences } = await import('../js/store/preferences.js');
const { CalibrationDialog } = await import('../js/shell/calibration-dialog.js');

const SQRT2 = Math.SQRT2;

/** The dialog surface _storeAdc / _storeDac touch. `bench` null = a LOCAL selection. */
function host({ bench = null } = {}) {
  const prefs = new Preferences(true);
  const local = [];
  return Object.assign(Object.create(CalibrationDialog.prototype), {
    prefs,
    deviceStore: {
      storeAdcCalibration: (fs, label) => local.push(['adc', fs, label]),
      storeAdcCalibrationChannel: (ch, fs, label) => local.push(['adc', ch, fs, label]),
      storeDacCalibration: (fs, label) => local.push(['dac', fs, label]),
      storeDacCalibrationChannel: (ch, fs, label) => local.push(['dac', ch, fs, label]),
    },
    _bench: () => bench,
    local,
  });
}

/** A bench double: records the pairs it was sent and answers `takes`. */
function bench(takes = true) {
  const sent = [];
  return { sent, remote: () => 'net:WASAPI', send: async (...args) => { sent.push(args); return takes; } };
}

// ---------------------------------------------------------------------------
// LOCAL - unchanged by the seam
// ---------------------------------------------------------------------------

test('a local device is calibrated where it always was - this machine\'s card store', async () => {
  const h = host();

  assert.equal(await CalibrationDialog.prototype._storeAdc.call(h, 'R', 3.0, 'Line In', true), true);
  assert.equal(await CalibrationDialog.prototype._storeAdc.call(h, 'L', 4.0, 'Line In', false), true);
  assert.equal(await CalibrationDialog.prototype._storeDac.call(h, 'L', 5.0, 'Speakers', false), true);

  assert.deepEqual(h.local, [
    ['adc', 'R', 3.0, 'Line In'],     // a bound stereo card: only the measured channel
    ['adc', 4.0, 'Line In'],          // MONO / unbound: the shared both-channels full scale
    ['dac', 5.0, 'Speakers'],
  ]);
});

// ---------------------------------------------------------------------------
// BENCH - the write goes to the server, and only a stored one moves anything here
// ---------------------------------------------------------------------------

test('a bench device is calibrated ON THE SERVER, and the unmeasured channel travels unchanged',
  async () => {
    const b = bench();
    const h = host({ bench: b });
    h.prefs.setAdcFsVoltageRms(1.5);
    h.prefs.setAdcFsVoltageRmsRight(2.5);

    assert.equal(await CalibrationDialog.prototype._storeAdc.call(h, 'R', 8.0, 'Bench in', true), true);

    assert.deepEqual(b.sent, [[true, 'Bench in', 1.5, 8.0]], 'left as it stood, right as measured');
    assert.deepEqual(h.local, [], 'and nothing was written into this machine\'s cards');
    assert.equal(h.prefs.getAdcFsVoltageRms('L'), 1.5);
    assert.equal(h.prefs.getAdcFsVoltageRms('R'), 8.0, 'applied only after the bench took it');
  });

test('an unbound bench device sends the pair and moves both scalars', async () => {
  const b = bench();
  const h = host({ bench: b });

  assert.equal(await CalibrationDialog.prototype._storeAdc.call(h, 'L', 6.0, 'Bench in', false), true);

  assert.deepEqual(b.sent, [[true, 'Bench in', 6.0, 6.0]]);
  assert.equal(h.prefs.getAdcFsVoltageRms('L'), 6.0);
  assert.equal(h.prefs.getAdcFsVoltageRms('R'), 6.0);
});

test('a REFUSED bench write leaves this client exactly where the bench still is', async () => {
  const b = bench(false);
  const h = host({ bench: b });
  h.prefs.setAdcFsVoltageRms(1.5);
  h.prefs.setAdcFsVoltageRmsRight(2.5);

  assert.equal(await CalibrationDialog.prototype._storeAdc.call(h, 'L', 8.0, 'Bench in', true), false);

  assert.equal(h.prefs.getAdcFsVoltageRms('L'), 1.5, 'nothing moved - the bench kept its own');
  assert.equal(h.prefs.getAdcFsVoltageRms('R'), 2.5);
  assert.deepEqual(h.local, []);
});

test('the DAC pair is sent as RMS volts, though the scalars it comes from are peak amplitudes',
  async () => {
    const b = bench();
    const h = host({ bench: b });

    assert.equal(await CalibrationDialog.prototype._storeDac.call(h, 'L', 2.0, 'Bench out', false), true);

    assert.deepEqual(b.sent, [[false, 'Bench out', 2.0 / SQRT2, 2.0 / SQRT2]]);
    assert.equal(h.prefs.getDacFsVoltageAmpl('L'), 2.0, 'the scalar stays the peak form');
    assert.equal(h.prefs.getDacFsVoltageAmpl('R'), 2.0);
  });

// ---------------------------------------------------------------------------
// The correction the dialog computes on top of an already-calibrated reading
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// The WHOLE submit, through the real openAdc / openDac + OK - where the values just stored reach
// the running measurement. A bench DAC is the case that needed a page reload: the SERVER converts
// volts with the full scale it read when the lane OPENED, and spec 4.5 carries no full scale, so
// only a re-open moves the level (calibration-dialog._applyToLiveTone).
// ---------------------------------------------------------------------------

/** A NumericStepField stand-in: what _seedRow seeds and _onOk commits + reads. */
function stepField() {
  const f = {
    tooltipBase: '', disabled: false, value: NaN, input: { value: '' },
    setDisabled(v) { f.disabled = v; },
    setValue(v) { f.value = v; f.input.value = String(v); },
    getValue() { return f.value; },
    model: { commit: (text) => { f.value = parseFloat(text); } },
    /** What the operator's meter said, typed into the row. */
    type(text) { f.input.value = text; },
  };
  return f;
}

/** The dialog surface the two openers touch, on the real prototype. */
function dialog({ bench = null, stereo = false, label = 'Bench out' } = {}) {
  const prefs = new Preferences(true);
  const left = stepField();
  const right = stepField();
  const calls = [];
  return Object.assign(Object.create(CalibrationDialog.prototype), {
    prefs,
    engine: { retuneGenerator: () => calls.push('retune') },
    deviceStore: {
      isBoundStereo: () => stereo,
      storeDacCalibration: (fs) => calls.push(['dac', fs]),
      storeDacCalibrationChannel: (ch, fs) => calls.push(['dac', ch, fs]),
      storeAdcCalibration: (fs) => calls.push(['adc', fs]),
      storeAdcCalibrationChannel: (ch, fs) => calls.push(['adc', ch, fs]),
    },
    _leftField: left,
    _rightField: right,
    _inputLabel: () => label,
    _outputLabel: () => label,
    _bench: () => bench,
    _restartGenerator: async () => calls.push('restart'),
    _modal: () => ({ show: () => {}, hide: () => {} }),
    left, right, calls,
  });
}

test('a bench DAC calibrate RE-OPENS the lane - the bench converts with what it read at open',
  async () => {
    const b = bench();
    const h = dialog({ bench: b });
    h.prefs.genAmplitudeVrms.set(1.0);
    h.prefs.setDacFsVoltageAmpl(2.0);
    h.prefs.setDacFsVoltageAmplRight(2.0);

    CalibrationDialog.prototype.openDac.call(h);
    h.left.type('0.5');                      // the meter reads half of what was commanded
    await CalibrationDialog.prototype._onOk.call(h);

    assert.deepEqual(b.sent, [[false, 'Bench out', 1.0 / SQRT2, 1.0 / SQRT2]]);
    assert.equal(h.prefs.getDacFsVoltageAmpl('L'), 1.0, 'applied here the moment the bench took it');
    assert.equal(h.prefs.getDacFsVoltageAmpl('R'), 1.0);
    assert.deepEqual(h.calls, ['restart'], 'a retune cannot move a level the server computes');
  });

test('a bench DAC calibrate re-opens the lane ONCE, however many channels were written',
  async () => {
    const b = bench();
    const h = dialog({ bench: b, stereo: true });
    h.prefs.genAmplitudeVrms.set(1.0);
    h.prefs.setDacFsVoltageAmpl(2.0);
    h.prefs.setDacFsVoltageAmplRight(2.0);

    CalibrationDialog.prototype.openDac.call(h);
    h.left.type('0.5');
    h.right.type('0.25');
    await CalibrationDialog.prototype._onOk.call(h);

    assert.equal(b.sent.length, 2, 'both channels were written');
    assert.equal(h.prefs.getDacFsVoltageAmpl('L'), 1.0);
    assert.equal(h.prefs.getDacFsVoltageAmpl('R'), 0.5);
    assert.deepEqual(h.calls, ['restart'], 'one submit, one re-open - not one per row');
  });

test('a LOCAL DAC calibrate still rescales the live tone with no restart', async () => {
  const h = dialog({ label: 'Speakers' });
  h.prefs.genAmplitudeVrms.set(1.0);
  h.prefs.setDacFsVoltageAmpl(2.0);

  CalibrationDialog.prototype.openDac.call(h);
  h.left.type('0.5');
  await CalibrationDialog.prototype._onOk.call(h);

  assert.deepEqual(h.calls, [['dac', 1.0], 'retune'], 'the card store, then a seamless rescale');
});

test('a REFUSED bench DAC write leaves the tone that is playing alone', async () => {
  const b = bench(false);
  const h = dialog({ bench: b });
  h.prefs.genAmplitudeVrms.set(1.0);
  h.prefs.setDacFsVoltageAmpl(2.0);

  CalibrationDialog.prototype.openDac.call(h);
  h.left.type('0.5');
  await CalibrationDialog.prototype._onOk.call(h);

  assert.equal(h.prefs.getDacFsVoltageAmpl('L'), 2.0, 'the bench kept its own');
  assert.deepEqual(h.calls, [], 'and nothing re-opened a lane that is still correct');
});

test('a bench ADC calibrate needs no re-open: this client scales the samples it receives',
  async () => {
    const b = bench();
    const h = dialog({ bench: b, label: 'Bench in' });
    h.prefs.setAdcFsVoltageRms(2.0);
    h.prefs.setAdcFsVoltageRmsRight(2.0);

    CalibrationDialog.prototype.openAdc.call(h, 0.8, 'L');   // the pane read 0.8 Vrms
    h.left.type('1.0');                                      // the meter says 1.0
    await CalibrationDialog.prototype._onOk.call(h);

    assert.deepEqual(b.sent, [[true, 'Bench in', 2.5, 2.5]]);
    assert.equal(h.prefs.getAdcFsVoltageRms('L'), 2.5, 'in force on the very next frame');
    assert.equal(h.prefs.getAdcFsVoltageRms('R'), 2.5);
    assert.deepEqual(h.calls, [], 'the input direction has no lane to re-open');
  });

test('calibrating twice converges: the second pass corrects by 1.0', async () => {
  const b = bench();
  const h = host({ bench: b });
  h.prefs.setAdcFsVoltageRms(2.0);

  // Pass 1: the bench's full scale is 2.0 V, the pane reads 0.8 V and the meter says 1.0 V.
  const first = h.prefs.getAdcFsVoltageRms('L') * (1.0 / 0.8);
  await CalibrationDialog.prototype._storeAdc.call(h, 'L', first, 'Bench in', false);
  assert.equal(h.prefs.getAdcFsVoltageRms('L'), 2.5);

  // Pass 2: the stream is scaled by the new full scale, so the pane now READS the known 1.0 V.
  const second = h.prefs.getAdcFsVoltageRms('L') * (1.0 / 1.0);
  await CalibrationDialog.prototype._storeAdc.call(h, 'L', second, 'Bench in', false);
  assert.equal(h.prefs.getAdcFsVoltageRms('L'), 2.5, 'a correction of 1.0 - it has converged');
});
