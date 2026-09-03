/*
 * Phonalyser web - the EMBEDDED packaging: one source tree, two builds.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * What is pinned here is the DIFFERENCE, through the real paths that read the flag:
 *   - the backend combo offers the server's backends and nothing else (no Web Audio, no QA40x);
 *   - the engine's capture/playback dispatch never constructs a local source.
 *
 * The full profile is proven by the REST of the suite: every other file runs with the identifier
 * absent, which is exactly what the dev tree and the docs/web build run with - and the combo's
 * local entries are pinned there too (net-backend-selection.test.mjs).
 *
 * The flag is a BUILD-TIME define, so it cannot be toggled once a module has read it. This file
 * declares it on globalThis BEFORE its first dynamic import - the same shape esbuild's `define:`
 * gives the bundle - and the runner gives every test file its own process, so nothing here can
 * leak into a file that expects the full app.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

// BEFORE any module under test is loaded: build-profile.js reads this once, at evaluation.
globalThis.__PHONALYSER_EMBEDDED__ = true;

const backing = new Map();
globalThis.localStorage = {
  getItem: (k) => (backing.has(k) ? backing.get(k) : null),
  setItem: (k, v) => { backing.set(k, String(v)); },
  removeItem: (k) => { backing.delete(k); }, clear: () => { backing.clear(); },
};

// A <select> and its options - the whole DOM surface buildBackendSelector touches.
const options = [];
const sel = {
  length: 1, empty() { options.length = 0; return this; },
  append(o) { options.push(o); return this; },
  val() { return this; }, prop() { return this; }, attr() { return this; },
  on() { return this; }, off() { return this; }, toggleClass() { return this; },
};
globalThis.$ = (arg) => {
  if (!String(arg).startsWith('<option')) return sel;
  const o = { value: null, label: null, disabled: false };
  return { val(v) { o.value = v; return this; }, text(l) { o.label = l; return this; },
    prop(n, v) { o[n] = v; return this; }, attr() { return this; }, o };
};

const { EMBEDDED } = await import('../js/shell/build-profile.js');
const { Preferences } = await import('../js/store/preferences.js');
const { PreferencesDialog } = await import('../js/shell/preferences-dialog.js');
const { AudioBackend } = await import('../js/audio/backend.js');

test('the packaging flag is what the build declared', () => {
  assert.equal(EMBEDDED, true);
});

test('EMBEDDED: the backend combo offers the server\'s backends and NOTHING local', () => {
  const prefs = new Preferences(true);
  prefs.backend.set('WEB_AUDIO');
  const netBackends = () => ([{ backend: 'QA40X', displayName: 'Bench QA403 -> QA40x',
    available: true, operational: true }]);

  new PreferencesDialog(null, prefs, { netBackends }).buildBackendSelector();

  assert.deepEqual(options.map((x) => x.o.label), ['Bench QA403 -> QA40x'],
    'the page is served over plain http from a LAN host - getUserMedia and WebUSB are not there');
  assert.equal(prefs.backend.get(), 'net:QA40X',
    'and a stored LOCAL selection falls back to what the combo can actually offer');
});

test('EMBEDDED: the backend never constructs a local capture source or playback sink', () => {
  // A minimal `this` ON the real prototype - no backend construction, no browser globals.
  const engine = Object.assign(Object.create(AudioBackend.prototype),
    { _status: () => {}, _net: null });

  for (const backend of ['WEB_AUDIO', 'QA40X']) {
    assert.throws(() => engine._newCaptureSource(backend),
      /serves only Phonalyser server backends/,
      `${backend} must fail HERE, not deep inside a browser API that does not exist on this page`);
    engine.activeBackend = () => backend;
    assert.throws(() => engine.openPlayback({}),
      /serves only Phonalyser server backends/);
  }

  // ...and a server backend still dispatches: the guard is about LOCAL devices, not about the app.
  engine.activeBackend = () => 'net:QA40X';
  assert.throws(() => engine.openPlayback({}),
    /the session to the bench is gone/, 'the net path is reached and fails for its OWN reason');
});
