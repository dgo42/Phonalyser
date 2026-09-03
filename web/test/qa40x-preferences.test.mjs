/*
 * Phonalyser web - Qa40xPreferences live/edit contract + custom-prefs registry test.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Covers the SubPreferences contract as Qa40xPreferences implements it (LIVE vs EDIT,
 * Cancel = never commit, fromMap tolerating garbage / a missing key) and the registry
 * side in js/store/preferences.js - above all the REPLAY at registration time, which is
 * how a lazily built backend manager ever sees its saved values.
 *
 * preferences.js persists to localStorage and reads navigator in its constructor, so
 * minimal shims go on globalThis BEFORE the module is imported.
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
const { Qa40xPreferences } = await import('../js/qa40x/qa40x-preferences.js');

/** A fresh non-singleton Preferences, with the constructor's debounced seed-save
 *  drained: a timer still pending here would fire mid-test and rewrite the document. */
function newPrefs() {
  const p = new Preferences();
  p.flush();
  return p;
}

/** The persisted document, as an object. */
function storedDoc() {
  return JSON.parse(localStorage.getItem(PREFS_KEY) || '{}');
}

test('the block is keyed qa40x and serialises the live value under i2s', () => {
  const block = new Qa40xPreferences();
  assert.equal(block.key(), 'qa40x');
  assert.deepEqual(block.toMap(), { i2s: false }, 'default off, one entry named i2s');

  block.setI2sEnabledEdit(true);
  assert.deepEqual(block.toMap(), { i2s: false }, 'toMap serialises LIVE, never the edit value');
  block.commitEdit();
  assert.deepEqual(block.toMap(), { i2s: true });
});

test('editing is pending until commitEdit; beginEdit re-seeds edit from live', () => {
  const block = new Qa40xPreferences();

  block.setI2sEnabledEdit(true);
  assert.equal(block.i2sEnabledEdit(), true, 'the dialog sees its own pending value');
  assert.equal(block.i2sEnabled(), false, 'live is untouched until OK');

  block.commitEdit();
  assert.equal(block.i2sEnabled(), true, 'commitEdit copies edit into live');

  // A later dialog session that is abandoned must not leave edit on the stale choice.
  block.setI2sEnabledEdit(false);
  block.beginEdit();
  assert.equal(block.i2sEnabledEdit(), true, 'beginEdit re-seeds edit from live');
});

test('Cancel (no commitEdit) leaves the live value and the saved document untouched', () => {
  localStorage.clear();
  const prefs = newPrefs();
  const block = new Qa40xPreferences();
  prefs.registerCustomPreferences(block);

  // Preferences dialog opens, the QA40x settings dialog is OK'd (edit only), then the
  // Preferences dialog is CANCELLED - commitCustomPreferencesEdit is never called.
  prefs.beginCustomPreferencesEdit();
  block.setI2sEnabledEdit(true);
  prefs.save();

  assert.equal(block.i2sEnabled(), false, 'Cancel never reaches the live value');
  assert.deepEqual(storedDoc().custom, { qa40x: { i2s: false } }, 'and never reaches the document');
  assert.equal(block.i2sEnabledEdit(), true, 'Cancel does not clean up - the abandoned edit lingers');

  // Reopening the dialog re-seeds every registered block, so that abandoned edit
  // cannot show up pre-selected in this session.
  prefs.beginCustomPreferencesEdit();
  assert.equal(block.i2sEnabledEdit(), false, 'the reopened dialog is seeded from live');

  // The OK path, for contrast: commit before the save and both change.
  block.setI2sEnabledEdit(true);
  prefs.commitCustomPreferencesEdit();
  prefs.save();

  assert.equal(block.i2sEnabled(), true, 'OK commits every registered block');
  assert.deepEqual(storedDoc().custom, { qa40x: { i2s: true } });
});

test('fromMap keeps the current value for a garbage or missing entry, and syncs edit', () => {
  const block = new Qa40xPreferences();
  block.setI2sEnabledEdit(true);
  block.commitEdit();   // live = true

  block.fromMap({ i2s: 'yes' });          // a newer/older release wrote another type
  assert.equal(block.i2sEnabled(), true, 'an unrecognised value keeps the current one');
  block.fromMap({});                      // key absent
  assert.equal(block.i2sEnabled(), true, 'a missing key keeps the current one');
  block.fromMap({ i2sEnabled: false });   // wrong key name
  assert.equal(block.i2sEnabled(), true, 'an unknown key changes nothing');

  // A dialog opened before the values arrived must not keep showing the stale state.
  block.setI2sEnabledEdit(false);
  block.fromMap({ i2s: true });
  assert.equal(block.i2sEnabledEdit(), true, 'fromMap re-syncs the edit value');
});

test('registering AFTER a load replays the stored block into the new instance', () => {
  localStorage.clear();
  const first = newPrefs();
  const written = new Qa40xPreferences();
  first.registerCustomPreferences(written);
  written.setI2sEnabledEdit(true);
  first.commitCustomPreferencesEdit();
  first.save();

  // A new session: load() runs in the constructor, long before the lazily built
  // backend manager exists - so nothing is registered while the document is read.
  const next = newPrefs();
  const late = new Qa40xPreferences();
  assert.equal(late.i2sEnabled(), false, 'the block starts at its default');

  next.registerCustomPreferences(late);
  assert.equal(late.i2sEnabled(), true, 'registration replays the stored value');
  assert.equal(late.i2sEnabledEdit(), true, 'and the edit value follows it');
});

test("a never-registered block's saved settings survive the next save", () => {
  localStorage.clear();
  const seed = newPrefs();
  seed.registerCustomPreferences(new Qa40xPreferences());
  seed.save();
  // Another component's block, as an earlier/newer release would have left it.
  const doc = storedDoc();
  doc.custom.someOtherBackend = { foo: 42 };
  localStorage.setItem(PREFS_KEY, JSON.stringify(doc));

  const prefs = newPrefs();   // only qa40x registers this session
  prefs.registerCustomPreferences(new Qa40xPreferences());
  prefs.save();

  assert.deepEqual(storedDoc().custom.someOtherBackend, { foo: 42 },
    'the unregistered block is carried through untouched');
});
