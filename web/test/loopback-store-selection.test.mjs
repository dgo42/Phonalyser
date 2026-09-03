/*
 * Phonalyser web - the LOOPBACK backend selection survives a reload.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * The store gates BOTH the backend selection and every perBackend key against the legal enum
 * names, and drops silently what it does not recognise. A backend missing from that list is
 * therefore not merely unlisted - it is un-storable: the selection falls back on every reload and
 * the whole per-backend block (device, rates, the two sample widths) is discarded with it, so the
 * operator's depth choice would not survive pressing F5. That gate is what these cases pin, in
 * both directions: LOOPBACK passes it, an unknown name still does not.
 *
 * preferences.js persists to localStorage and reads navigator in its constructor, so minimal
 * shims go on globalThis BEFORE the module is imported.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

const store = new Map();
globalThis.localStorage = {
  getItem: (k) => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => { store.set(k, String(v)); },
  removeItem: (k) => { store.delete(k); },
  clear: () => { store.clear(); },
};
if (!globalThis.navigator) globalThis.navigator = { platform: 'Win32', userAgent: 'node' };

const { Preferences, PREFS_KEY } = await import('../js/store/preferences.js');
const { LOOPBACK_BACKEND } = await import('../js/loopback/loopback-device-ref.js');

/** A fresh non-singleton Preferences, with the constructor's debounced seed-save drained. */
function newPrefs() {
  const p = new Preferences();
  p.flush();
  return p;
}

/** The persisted document, as an object. */
function storedDoc() {
  return JSON.parse(localStorage.getItem(PREFS_KEY) || '{}');
}

/** Selects a backend, fills its per-backend block with recognisable values and persists. */
function persistSelection(name) {
  const prefs = newPrefs();
  prefs.backend.set(name);
  const block = prefs.prefsFor(name);
  block.inputDeviceName = 'Phonalyser Loopback';
  block.outputDeviceName = 'Phonalyser Loopback';
  block.inputSampleRate = 96000;
  block.inputBitDepth = 20;
  block.outputSampleRate = 96000;
  block.outputBitDepth = 20;
  prefs.save();
  return prefs;
}

test('a LOOPBACK selection and its per-backend block survive a reload', () => {
  localStorage.clear();
  persistSelection(LOOPBACK_BACKEND);

  // The document as written, then the same document as a fresh page reads it.
  const doc = storedDoc();
  assert.equal(doc.backend, LOOPBACK_BACKEND, 'the selection reaches storage');
  assert.ok(doc.perBackend && doc.perBackend[LOOPBACK_BACKEND], 'and so does its block');

  const reloaded = newPrefs();

  assert.equal(reloaded.backend.get(), LOOPBACK_BACKEND, 'the selection is still in force');
  const block = reloaded.prefsFor(LOOPBACK_BACKEND);
  assert.equal(block.inputDeviceName, 'Phonalyser Loopback');
  assert.equal(block.outputDeviceName, 'Phonalyser Loopback');
  assert.equal(block.inputSampleRate, 96000);
  assert.equal(block.outputSampleRate, 96000);
  // The two depths are what the lanes ask for at each session boundary - the values the whole
  // backend is about.
  assert.equal(block.inputBitDepth, 20);
  assert.equal(block.outputBitDepth, 20);
});

test('an unknown backend name is still dropped - the gate is real, not absent', () => {
  localStorage.clear();
  persistSelection(LOOPBACK_BACKEND);
  // The same document with the name changed to one no build knows: this is what LOOPBACK itself
  // did before it joined the list.
  const doc = storedDoc();
  doc.backend = 'NOT_A_BACKEND';
  doc.perBackend.NOT_A_BACKEND = doc.perBackend[LOOPBACK_BACKEND];
  delete doc.perBackend[LOOPBACK_BACKEND];
  localStorage.setItem(PREFS_KEY, JSON.stringify(doc));

  const reloaded = newPrefs();

  assert.notEqual(reloaded.backend.get(), 'NOT_A_BACKEND', 'the selection falls back');
  // prefsFor() creates blocks lazily, so an absent key reads as the defaults - which is exactly
  // how a dropped block is observed: the stored rate and depth are gone.
  const dropped = reloaded.prefsFor('NOT_A_BACKEND');
  assert.equal(dropped.inputDeviceName, null, 'the block was never read in');
  assert.notEqual(dropped.inputBitDepth, 20);
});

test('the LOOPBACK block is independent of the other backends', () => {
  localStorage.clear();
  const prefs = persistSelection(LOOPBACK_BACKEND);
  // Switching backend must bring back that backend's own devices and widths, which is the whole
  // point of the per-backend map.
  prefs.backend.set('WEB_AUDIO');
  prefs.save();

  const reloaded = newPrefs();

  assert.equal(reloaded.backend.get(), 'WEB_AUDIO');
  assert.equal(reloaded.prefsFor(LOOPBACK_BACKEND).inputBitDepth, 20,
    'the loopback block is remembered while another backend is selected');
  assert.notEqual(reloaded.prefsFor('WEB_AUDIO').inputBitDepth, 20,
    'and it is not the block Web Audio reads');
});
