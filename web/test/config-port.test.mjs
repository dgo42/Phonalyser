/*
 * Phonalyser web - the JSON config editor's one seam.
 *
 * WHAT THIS PINS. store/config-port.js is the ONLY module allowed to touch Preferences or
 * DeviceProfileStore ("there is only one connection to entire program loading
 * preferences/devices from live app and saving it back in live app" - maintainer). Everything
 * below is about that promise holding:
 *
 *   - the two narrow accessors the ports stand on do what they say (round-trip, and APPLY to
 *     the LIVE object rather than through storage);
 *   - applying a device document REPLACES rather than appends - the failure mode that would
 *     otherwise duplicate every card and make deletion impossible;
 *   - the corrupt entry is ONE optional entry keyed off the fixed `<key>.corrupt` the
 *     quarantine writes, not an enumeration;
 *   - the dialog's contract: no import of either store outside config-port.js.
 */
import test from 'node:test';
import { strict as assert } from 'node:assert';
import { readFileSync } from 'node:fs';

// --- shims: an in-memory localStorage + a navigator, as device-profiles.test.mjs installs ---
const store = new Map();
globalThis.localStorage = {
  getItem: (k) => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => { store.set(k, String(v)); },
  removeItem: (k) => { store.delete(k); },
  clear: () => { store.clear(); },
};
if (!globalThis.navigator) globalThis.navigator = { platform: 'Win32', userAgent: 'node' };

const { Preferences } = await import('../js/store/preferences.js');
const { DeviceProfileStore } = await import('../js/store/device-profiles.js');
const { createConfigPorts } = await import('../js/store/config-port.js');

const EMPTY_CATALOG = { formatVersion: 1, contentVersion: 0, audioDevices: [] };
let keyCounter = 0;
const freshStore = () => new DeviceProfileStore(new Preferences(true),
  { storageKey: 'phonalyser.devices.test.' + (++keyCounter), catalog: EMPTY_CATALOG });

const CARD = {
  name: 'Test ADC',
  match: ['Test ADC'],
  input: {
    channels: 'LINKED',
    ranges: [{ label: '1.7V', fsVrms: { left: 1.7, right: 1.7 }, calibrated: true }],
    activeRange: '1.7V',
  },
};

// ---------------------------------------------------------------------------
// the accessors the ports stand on
// ---------------------------------------------------------------------------

test('preferences: configDocument() is the document save() would write', () => {
  const prefs = new Preferences(true);
  prefs.uiFontNormal.set('Consolas 11');
  const doc = prefs.configDocument();
  assert.equal(doc.uiFontNormal, 'Consolas 11');
  assert.ok(doc.formatVersion > 0, 'the format marker is part of the document');
});

test('preferences: configDocument() hands out a detached object', () => {
  const prefs = new Preferences(true);
  const doc = prefs.configDocument();
  doc.uiFontNormal = 'mutated';
  assert.notEqual(prefs.uiFontNormal.get(), 'mutated',
    'mutating the returned document must not reach into the live preferences');
});

test('preferences: applyConfigDocument() reaches the LIVE object, not storage', () => {
  const prefs = new Preferences(true);   // transient: save() is a no-op, so storage cannot help
  const doc = prefs.configDocument();
  doc.uiFontNormal = 'Segoe UI 13';
  prefs.applyConfigDocument(doc);
  assert.equal(prefs.uiFontNormal.get(), 'Segoe UI 13',
    'the edit must land on the live object even when nothing is persisted - applying through '
    + 'load() would have round-tripped it through localStorage and lost it here');
});

test('preferences: applying leaves the loading flag down', () => {
  const prefs = new Preferences(true);
  prefs.applyConfigDocument(prefs.configDocument());
  // A flag left raised would silently disable the debounced auto-save for the rest of the run.
  assert.equal(prefs._loading, false);
});

// ---------------------------------------------------------------------------
// devices - the replace-not-append rule
// ---------------------------------------------------------------------------

test('devices: configDocument() round-trips through applyConfigDocument()', () => {
  const s = freshStore();
  s.applyConfigDocument({ formatVersion: 1, contentVersion: 0, audioDevices: [CARD] });
  const doc = s.configDocument();
  assert.equal(doc.audioDevices.length, 1);
  assert.equal(doc.audioDevices[0].name, 'Test ADC');
});

test('devices: applying REPLACES the cards - it never appends', () => {
  const s = freshStore();
  const doc = { formatVersion: 1, contentVersion: 0, audioDevices: [CARD] };
  s.applyConfigDocument(doc);
  s.applyConfigDocument(doc);
  assert.equal(s.configDocument().audioDevices.length, 1,
    'applying the same document twice must not double the cards - the reader only ever adds, '
    + 'so applyConfigDocument has to clear first');
});

test('devices: removing a card in the document really removes it', () => {
  const s = freshStore();
  s.applyConfigDocument({ formatVersion: 1, contentVersion: 0, audioDevices: [CARD] });
  s.applyConfigDocument({ formatVersion: 1, contentVersion: 0, audioDevices: [] });
  assert.equal(s.configDocument().audioDevices.length, 0);
});

test('devices: bindings are replaced too, not merged', () => {
  const s = freshStore();
  s.applyConfigDocument({
    formatVersion: 1, contentVersion: 0, audioDevices: [CARD],
    bindings: { 'Line (Test ADC)': 'Test ADC' },
  });
  assert.deepEqual(s.configDocument().bindings, { 'Line (Test ADC)': 'Test ADC' });
  s.applyConfigDocument({ formatVersion: 1, contentVersion: 0, audioDevices: [CARD] });
  assert.equal(s.configDocument().bindings, undefined,
    'a document with no bindings block must leave none behind');
});

// The floor under the clear-first rule. Preferences is safe by construction - every
// assignment in _fromMap is type-guarded, so a sparse document is a no-op - but this store
// clears before it reads, so a document that merely OMITS audioDevices would empty it and
// persist that. Nothing upstream can catch it: the quarantine fires only on a parse error and
// the dialog checks only that the text parses to an object.
test('devices: a document with no audioDevices is REFUSED, not applied', () => {
  const s = freshStore();
  s.applyConfigDocument({ formatVersion: 1, contentVersion: 0, audioDevices: [CARD] });
  assert.throws(
    () => s.applyConfigDocument({ formatVersion: 1, contentVersion: 2 }),
    (e) => e.i18nKey === 'web.jsonConfig.error.noAudioDevices',
    'the refusal must carry the key the dialog words it with',
  );
  assert.equal(s.configDocument().audioDevices.length, 1,
    'the cards must still be there - a refused apply may not have cleared anything');
});

test('devices: a non-array audioDevices is refused the same way', () => {
  const s = freshStore();
  s.applyConfigDocument({ formatVersion: 1, contentVersion: 0, audioDevices: [CARD] });
  for (const bad of [{ audioDevices: null }, { audioDevices: 'none' }, { audioDevices: {} }]) {
    assert.throws(() => s.applyConfigDocument({ formatVersion: 1, ...bad }),
      (e) => e.i18nKey === 'web.jsonConfig.error.noAudioDevices');
  }
  assert.equal(s.configDocument().audioDevices.length, 1);
});

test('devices: an EXPLICIT empty list is accepted and really does clear the store', () => {
  const s = freshStore();
  s.applyConfigDocument({ formatVersion: 1, contentVersion: 0, audioDevices: [CARD] });
  s.applyConfigDocument({ formatVersion: 1, contentVersion: 0, audioDevices: [] });
  assert.equal(s.configDocument().audioDevices.length, 0,
    'emptying the store on purpose must stay possible - the guard is about the ABSENT key, '
    + 'not about refusing to delete');
});

test('devices: a refused apply leaves the persisted document untouched', () => {
  const s = freshStore();
  s.applyConfigDocument({ formatVersion: 1, contentVersion: 0, audioDevices: [CARD] });
  try { s.applyConfigDocument({ formatVersion: 1 }); } catch (e) { /* expected */ }
  assert.equal(JSON.parse(localStorage.getItem(s._storageKey)).audioDevices.length, 1);
});

test('devices: applying persists, so the next load sees it', () => {
  const s = freshStore();
  s.applyConfigDocument({ formatVersion: 1, contentVersion: 0, audioDevices: [CARD] });
  const raw = JSON.parse(localStorage.getItem(s._storageKey));
  assert.equal(raw.audioDevices[0].name, 'Test ADC');
});

// ---------------------------------------------------------------------------
// the ports
// ---------------------------------------------------------------------------

test('the two ports carry everything the dialog needs and nothing more', () => {
  const ports = createConfigPorts({ prefs: new Preferences(true), deviceStore: freshStore() });
  assert.deepEqual(ports.map((p) => p.id), ['preferences', 'devices']);
  for (const p of ports) {
    for (const k of ['id', 'titleKey', 'storageKey', 'schema', 'loadLive', 'applyLive', 'corruptEntry']) {
      assert.ok(p[k] != null, `${p.id} port is missing ${k}`);
    }
    assert.equal(typeof p.loadLive, 'function');
    assert.equal(typeof p.applyLive, 'function');
    assert.equal(typeof p.corruptEntry, 'function');
    assert.equal(typeof p.schema, 'object');
  }
});

test('the ports read and write the INJECTED live objects', () => {
  const prefs = new Preferences(true);
  const deviceStore = freshStore();
  const [prefsPort, devicesPort] = createConfigPorts({ prefs, deviceStore });

  const pDoc = prefsPort.loadLive();
  pDoc.uiFontNormal = 'Arial 10';
  prefsPort.applyLive(pDoc);
  assert.equal(prefs.uiFontNormal.get(), 'Arial 10', 'the preferences port drives the given instance');

  devicesPort.applyLive({ formatVersion: 1, contentVersion: 0, audioDevices: [CARD] });
  assert.equal(deviceStore.configDocument().audioDevices.length, 1,
    'the devices port drives the given store, not a second one it built');
});

test('corruptEntry() is one optional entry on the fixed .corrupt key', () => {
  const prefs = new Preferences(true);
  const [prefsPort] = createConfigPorts({ prefs, deviceStore: freshStore() });
  localStorage.removeItem(prefsPort.storageKey + '.corrupt');
  assert.equal(prefsPort.corruptEntry(), null, 'nothing quarantined -> nothing offered');
  localStorage.setItem(prefsPort.storageKey + '.corrupt', '{ broken');
  assert.equal(prefsPort.corruptEntry(), '{ broken', 'the kept text is handed back verbatim');
});

test('corruptEntry() never throws when storage refuses', () => {
  // Built while storage still works: the swap below is only about the READ, and a store
  // constructed against a crippled storage would log a seed failure that is not the point.
  const [p] = createConfigPorts({ prefs: new Preferences(true), deviceStore: freshStore() });
  const real = globalThis.localStorage;
  globalThis.localStorage = { getItem() { throw new Error('denied'); } };
  try {
    assert.equal(p.corruptEntry(), null, 'a refusing storage reads as "no copy", not as a crash - '
      + 'this runs while a menu is opening');
  } finally {
    globalThis.localStorage = real;
  }
});

// ---------------------------------------------------------------------------
// the seam itself
// ---------------------------------------------------------------------------

test('config-port.js is the ONLY new module that imports either store', () => {
  const read = (p) => readFileSync(new URL(p, import.meta.url), 'utf8');
  const importsStore = (src) => /from\s+'[^']*(?:preferences|device-profiles)\.js'/.test(src);

  assert.ok(importsStore(read('../js/store/config-port.js')),
    'the port is where the stores are reached');
  for (const f of ['../js/shell/json-config-dialog.js',
    '../js/store/schema/preferences.schema.js',
    '../js/store/schema/devices.schema.js']) {
    assert.ok(!importsStore(read(f)),
      `${f} must not import a store - the dialog and the schemas live on the far side of the seam`);
  }
});
