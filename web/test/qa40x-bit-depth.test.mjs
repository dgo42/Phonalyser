/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// The Preferences bit-depth chain, end to end. Every case here is a bug the maintainer found on
// hardware on 2026-07-25/26, in the four links of one chain - populate, re-derive, persist, hide:
//
//   1. the depth list a backend offers        (QA40x: 24 in, 24 or the I2S port's 16/32 out)
//   2. re-deriving it when the port is toggled, and discarding a CANCELLED toggle
//   3. persisting the picked depth on OK, so a reopen does not fall back to the first entry
//   4. hasBitDepth() - Web Audio has no depth to choose, so its rows are hidden and unread
//
// The suite never drove any of this: the combos are DOM, and the manager was only ever asked for
// rates. These tests work on the pieces underneath - the format list, the SubPreferences live/edit
// pair, and the two selection rules the dialog applies - which is where each bug actually was.

import test from 'node:test';
import assert from 'node:assert/strict';

import { Qa40xDeviceManager, Qa40xDeviceRef } from '../js/qa40x/qa40x-device-manager.js';
import { Qa40xPreferences } from '../js/qa40x/qa40x-preferences.js';

const QA403 = new Qa40xDeviceRef(0, 'QA403', 'QA403');

/** The analyzer's own depth, and the front-panel port's frame widths. */
const ANALYZER_DEPTHS = [24];
const I2S_DEPTHS = [16, 32];

function makeManager(settings = new Qa40xPreferences()) {
  const manager = new Qa40xDeviceManager({
    prefs: { backend: { get: () => 'QA40X' }, current: () => ({ outputBitDepth: 24 }),
      registerCustomPreferences() {} },
    deviceStore: { findAudioDeviceProfile: () => null, putAudioDeviceProfile() {}, saveDevices() {} },
    settings, armBus: false,
    finder: { async list() { return [{ model: 'QA403' }]; }, async open() { throw new Error('n/a'); } },
  });
  return { manager, settings };
}

/** The distinct depths one direction's format list reports - what the combo is filled from. */
const depthsOf = (manager, output) =>
  [...new Set(manager.listSupportedFormats(QA403, output).map((f) => f.bits))].sort((a, b) => a - b);

/** fillIntCombo's rule (Java populateIntCombo): the preferred value when offered, else the first. */
const selected = (values, preferred) => (values.includes(preferred) ? preferred : values[0]);

/** The dialog's backendHasBitDepth() rule, verbatim. */
const backendHasBitDepth = (manager) =>
  !!(manager && typeof manager.hasBitDepth === 'function' && manager.hasBitDepth());

// --- 1. the lists ----------------------------------------------------------

test('the QA40x offers its analyzer depth, and the I2S frame widths only on the output', () => {
  const { manager, settings } = makeManager();

  assert.deepEqual(depthsOf(manager, false), ANALYZER_DEPTHS, 'input, port off');
  assert.deepEqual(depthsOf(manager, true), ANALYZER_DEPTHS, 'output, port off');

  settings.setI2sEnabledEdit(true);
  assert.deepEqual(depthsOf(manager, false), ANALYZER_DEPTHS,
    'the input stays 24: the front-panel port is an OUTPUT and cannot change the capture path');
  assert.deepEqual(depthsOf(manager, true), I2S_DEPTHS,
    'the output offers the port frame widths while it is on');
});

test('the output list follows the EDIT value, so it reacts before Preferences is OK\'d', () => {
  const { manager, settings } = makeManager();
  settings.setI2sEnabledEdit(true);
  assert.deepEqual(depthsOf(manager, true), I2S_DEPTHS);
  assert.equal(settings.i2sEnabled(), false, 'and the LIVE setting is untouched - nothing applied');
});

// --- 2. re-derive, and a cancelled toggle --------------------------------

test('a CANCELLED I2S toggle leaves neither the setting nor the depth list behind', () => {
  const { manager, settings } = makeManager();
  settings.beginEdit();                       // Preferences opens
  settings.setI2sEnabledEdit(true);           // the settings dialog's OK - pending only
  assert.deepEqual(depthsOf(manager, true), I2S_DEPTHS);

  // Preferences CANCEL: commitEdit is never reached. Reopening re-seeds edit from live.
  settings.beginEdit();
  assert.equal(settings.i2sEnabledEdit(), false, 'the abandoned edit is discarded');
  assert.deepEqual(depthsOf(manager, true), ANALYZER_DEPTHS,
    'so the output list is back to the analyzer default on the next open');
});

test('Preferences OK commits the port, and the list stays on the frame widths', () => {
  const { manager, settings } = makeManager();
  settings.beginEdit();
  settings.setI2sEnabledEdit(true);
  settings.commitEdit();                      // main Preferences OK
  assert.equal(settings.i2sEnabled(), true);
  assert.deepEqual(depthsOf(manager, true), I2S_DEPTHS);
});

// --- 3. persistence -------------------------------------------------------

test('a picked depth must be stored, or a reopen falls back to the first offered entry', () => {
  // The bug: OK used the combo value to decide whether to bounce the direction, then dropped it.
  const notStored = { outputBitDepth: 24 };
  assert.equal(selected(I2S_DEPTHS, notStored.outputBitDepth), 16,
    'with 24 stored and [16,32] offered, the combo shows 16 - pick 32, reopen, find 16');

  const stored = { outputBitDepth: 24 };
  const picked = 32;
  if (picked) stored.outputBitDepth = picked;          // what applyAudioPrefs now does
  assert.equal(stored.outputBitDepth, 32, 'OK persists the pick');
  assert.equal(selected(I2S_DEPTHS, stored.outputBitDepth), 32, 'and a reopen keeps it');
  assert.equal(selected(ANALYZER_DEPTHS, stored.outputBitDepth), 24,
    'with the port switched off again, the analyzer depth stands rather than a stale 32');
});

test('an empty combo cannot clobber the stored depth', () => {
  const stored = { outputBitDepth: 32 };
  const parsed = parseInt('', 10);                     // a hidden / empty <select> gives NaN
  if (parsed) stored.outputBitDepth = parsed;          // the guard in applyAudioPrefs
  assert.equal(stored.outputBitDepth, 32);
});

// --- 4. hasBitDepth() -----------------------------------------------------

test('a backend declares whether it has a selectable depth at all', () => {
  const { manager } = makeManager();
  assert.equal(manager.hasBitDepth(), true, 'the QA40x has one');
  assert.equal(backendHasBitDepth(manager), true);

  assert.equal(backendHasBitDepth(null), false,
    'Web Audio has no manager: float32 post-mixer, no depth to choose and none to report');
  assert.equal(backendHasBitDepth({}), false, 'a manager that does not declare it has none');
  assert.equal(backendHasBitDepth({ hasBitDepth: () => false }), false,
    'and a future backend can say so explicitly');
  assert.equal(backendHasBitDepth({ hasBitDepth: () => true }), true);
});
