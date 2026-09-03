/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * A server's backends offered beside the local ones (spec 4.3's explicit listing rule) - and
 * the proof that offering them changes NOTHING about the local ones. Three things are pinned:
 * the net: namespacing that keeps a bench's QA40X apart from this machine's, the cached entry
 * list the combo build reads synchronously, and the scan branch that fills the device combos
 * from the bench's own catalogue.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

// ---------------------------------------------------------------------------
// A <select> and its <option>s, and nothing else - the whole DOM surface
// buildBackendSelector touches. (card-section.test.mjs has its own fake for a
// different surface; this one carries per-option disabled/title, which is what
// "listed and disabled" is asserted on.)
// ---------------------------------------------------------------------------
class FakeSelect {
  constructor(tag = 'select') {
    this.tag = tag; this.length = 1; this.options = []; this.selected = null;
    this.value = null; this.label = ''; this.disabled = false; this.title = null;
    this.handlers = new Map();
  }
  empty() { this.options = []; this.selected = null; return this; }
  /** jQuery accepts an element OR an html string; the backend combo builds elements, the rate
   *  and depth combos build strings (fillIntCombo), so both shapes land here. */
  append(option) {
    if (typeof option === 'string') {
      this.options.push({ value: (/value="([^"]*)"/.exec(option) || [null, null])[1],
        label: (/>([^<]*)</.exec(option) || [null, ''])[1],
        // The device combo marks a held bench device with BOTH a class (grey + italic) and
        // `disabled` (unpickable), so the double carries both attributes the assertions are about.
        cls: (/class="([^"]*)"/.exec(option) || [null, ''])[1],
        disabled: /\sdisabled(?=[\s>])/.test(option) });
      return this;
    }
    this.options.push(option);
    return this;
  }
  val(v) {
    if (v === undefined) return this.tag === 'option' ? this.value : this.selected;
    if (this.tag === 'option') { this.value = v; return this; }
    const hit = this.options.find((o) => o.value === v);
    this.selected = hit ? hit.value : null;   // jQuery: no matching option -> selectedIndex = -1
    return this;
  }
  text(label) { this.label = label; return this; }
  prop(name, v) { this[name] = v; return this; }
  attr(name, v) { this[name] = v; return this; }
  on(events, fn) { this.handlers.set(events, fn); return this; }
  off() { return this; }
  toggleClass() { return this; }
  /** jQuery's find('option').map(...).get() - how the dialog reads the rates a combo currently
   *  offers (rateOptions), which is what scan() records for the backend it just enumerated. */
  find(selector) {
    const list = selector === 'option' ? this.options : [];
    return { length: list.length, map: (fn) => ({ get: () => list.map((o, i) => fn(i, o)) }) };
  }
  /** What the combo SHOWS, in order. */
  items() { return this.options.map((o) => o.label); }
}
const dom = new Map();
globalThis.$ = (arg) => {
  const s = String(arg);
  if (s.startsWith('<option')) return new FakeSelect('option');
  let node = dom.get(s);
  if (!node) { node = new FakeSelect(); dom.set(s, node); }
  return node;
};

const backing = new Map();
globalThis.localStorage = {
  getItem: (k) => (backing.has(k) ? backing.get(k) : null),
  setItem: (k, v) => { backing.set(k, String(v)); },
  removeItem: (k) => { backing.delete(k); },
  clear: () => { backing.clear(); },
};
if (!globalThis.navigator) globalThis.navigator = { platform: 'Win32', userAgent: 'node' };

// The REAL English bundle off the disk, so an option's TEXT is what the operator would read
// rather than a key name - the lockedBy label below is asserted character for character.
const I18N_DIR = fileURLToPath(new URL('../i18n/', import.meta.url));
globalThis.fetch = async (path) => ({ ok: true,
  text: async () => readFileSync(I18N_DIR + String(path).replace(/^i18n\//, ''), 'utf8') });

const { initBase, t } = await import('../js/i18n/i18n.js');
await initBase();
const { netBackendValue, remoteBackendOf, namesNetDevice, NET_BACKEND_PREFIX } = await import('../js/net/net-device-ref.js');
const { NetDeviceManager } = await import('../js/net/net-device-manager.js');
const { NetPreferences, makeServerEntry } = await import('../js/net/net-preferences.js');
const { scanDevicesForBackend } = await import('../js/audio/devices.js');
const { MessageType, NetFields } = await import('../js/net/net-proto.js');
const { AudioBackend } = await import('../js/audio/backend.js');
const { Preferences } = await import('../js/store/preferences.js');
const { PreferencesDialog } = await import('../js/shell/preferences-dialog.js');

/** One turn of the microtask/timer queue - the manager's re-ask is fire-and-forget. */
const tick = () => new Promise((r) => setTimeout(r, 0));

/** A real PreferencesDialog over the fake combo, wired exactly as app.js wires it: the entries are
 *  read from the manager's cache through a live getter, and the manager is built FIRST. */
function comboDialog(manager = null, more = {}) {
  const sel = new FakeSelect();
  dom.set('#backendSel', sel);
  const prefs = new Preferences(true);
  prefs.backend.set('WEB_AUDIO');
  const dialog = new PreferencesDialog(null, prefs, {
    netBackends: () => (manager ? manager.getRemoteBackendEntries() : []),
    ...more,
  });
  return { dialog, sel, prefs };
}

/** app.js's backendManager seam, verbatim: the QA40x by name, the BENCH's own manager for any
 *  net: value that bench offers (it owns those devices' formats), else none. */
function appBackendManager(manager, qa40x = null) {
  return (name) => {
    if (name === 'QA40X') return qa40x;
    return (manager && manager.isRemoteBackend(name)) ? manager : null;
  };
}

/** The device combos' selected INDEX, which is how the dialog picks the device out of the
 *  manager's own listing (Java pickedDevice indexes devices.inputs). */
const shownIndex = { inSel: 0, outSel: 0 };
if (!globalThis.document) {
  globalThis.document = { getElementById: (id) => ({ selectedIndex: shownIndex[id] }) };
}

class FakeConnection {
  constructor() { this.isOpen = true; this.serverName = 'Bench QA403'; this.serverId = 'uuid-1'; this._id = 0; this.answers = {}; this.events = []; this.closeListeners = []; }
  newRequest(t) { return { t, id: ++this._id }; }
  async request(m) { const s = this.answers[m.t]; return (typeof s === 'function' ? s(m) : s) || { t: MessageType.RESP, id: m.id, ok: true, data: {} }; }
  uiRequest(m) { return this.request(m); }
  refusal(what) { return new Error(what); }
  addEventListener(l) { this.events.push(l); }
  removeEventListener(l) { this.events = this.events.filter((x) => x !== l); }
  addCloseListener(l) { this.closeListeners.push(l); }
  removeCloseListener(l) { this.closeListeners = this.closeListeners.filter((x) => x !== l); }
  deliver(e) { for (const l of [...this.events]) l(e); }
  close() { this.isOpen = false; }
}

function backendRow(name, { available = true, operational = true } = {}) {
  return { [NetFields.BACKEND]: name, [NetFields.DISPLAY_NAME]: name === 'QA40X' ? 'QA40x' : name,
    [NetFields.AVAILABLE]: available, [NetFields.OPERATIONAL]: operational };
}

function deviceRow(index, name, input, lockBy = null) {
  return { [NetFields.INDEX]: index, [NetFields.NAME]: name, [NetFields.DESCRIPTION]: `${name} (bench)`,
    [NetFields.VENDOR]: 'v', [NetFields.INPUT]: input, [NetFields.OUTPUT]: !input,
    [NetFields.HAS_BIT_DEPTH]: true,
    [NetFields.LOCK]: lockBy == null ? null : { [NetFields.BY]: lockBy },
    [NetFields.FORMATS]: [{ [NetFields.RATE]: 48000, [NetFields.BITS]: 24, [NetFields.CHANNELS]: 2 },
      { [NetFields.RATE]: 96000, [NetFields.BITS]: 24, [NetFields.CHANNELS]: 2 }] };
}

/** A manager wired to a scripted bench but NOT yet connected - the state app.js builds it in. */
function benchManager(rows = [backendRow('QA40X'), backendRow('COREAUDIO', { operational: false })]) {
  const connection = new FakeConnection();
  connection.answers[MessageType.BACKEND_LIST] = (m) => ({ t: MessageType.RESP, id: m.id, ok: true,
    data: { [NetFields.BACKENDS]: rows } });
  connection.answers[MessageType.BACKEND_SELECT] = (m) => ({ t: MessageType.RESP, id: m.id, ok: true,
    data: { [NetFields.BACKEND]: m[NetFields.BACKEND],
      [NetFields.DEVICES]: [deviceRow(0, 'QA403', true), deviceRow(0, 'QA403', false)] } });
  const manager = new NetDeviceManager({ preferences: new NetPreferences(), openConnection: async () => connection });
  const server = makeServerEntry('uuid-1', 'Bench QA403', '10.0.0.1', 8377, true);
  return { manager, connection, server };
}

async function connected(rows) {
  const bench = benchManager(rows);
  await bench.manager.connect(bench.server);
  return bench;
}

test('a bench backend and a local one of the SAME name are different choices', () => {
  assert.equal(netBackendValue('QA40X'), `${NET_BACKEND_PREFIX}QA40X`);
  assert.equal(remoteBackendOf('net:QA40X'), 'QA40X', 'the wire keeps the bare name');
  assert.equal(remoteBackendOf('QA40X'), null, 'a local backend is not remote');
  assert.equal(remoteBackendOf('WEB_AUDIO'), null);
  assert.equal(remoteBackendOf(null), null);
});

test('B1: a backend the server cannot serve is never offered - hidden, not greyed', async () => {
  const { manager, server } = benchManager([backendRow('QA40X'),
    backendRow('COREAUDIO', { operational: false }), backendRow('WASAPI', { available: false })]);
  const { dialog, sel } = comboDialog(manager);
  await manager.connect(server);

  dialog.buildBackendSelector();

  assert.deepEqual(sel.items(), ['Web Audio', 'QA40x', 'Loopback', 'Bench QA403 -> QA40x'],
    'not available and not operational are both "this machine cannot serve it"');
  assert.equal(manager.getRemoteBackendEntries().length, 3,
    'the CATALOGUE still carries all three - the filtering is the combo\'s, not the manager\'s');
});

test('the entries are CACHED at connect and read synchronously', async () => {
  const { manager } = await connected();

  // No await here: this is exactly what the combo build does.
  const entries = manager.getRemoteBackendEntries();
  assert.equal(entries.length, 2);
  assert.deepEqual(entries[0], { backend: 'QA40X', displayName: 'Bench QA403 -> QA40x',
    available: true, operational: true, hasBitDepth: false });
  assert.equal(entries[1].operational, false,
    'a backend the bench has but cannot run is listed and disabled, like the QA40x locally');
  assert.equal(manager.isRemoteBackend('net:QA40X'), true);
  assert.equal(manager.isRemoteBackend('net:SOMETHING_ELSE'), false, 'not one THIS server offers');
  assert.equal(manager.isRemoteBackend('QA40X'), false, 'the local one is never routed at the bench');
});

test('ev.devices.changed re-asks the offer - the bench\'s list can move with its devices', async () => {
  const { manager, connection } = await connected();
  connection.answers[MessageType.BACKEND_LIST] = (m) => ({ t: MessageType.RESP, id: m.id, ok: true,
    data: { [NetFields.BACKENDS]: [backendRow('JAVASOUND')] } });

  connection.deliver({ t: MessageType.EV_DEVICES_CHANGED, [NetFields.BACKENDS]: [] });
  await new Promise((r) => setTimeout(r, 0));   // the re-ask is fire-and-forget on the delivery path

  assert.deepEqual(manager.getRemoteBackendEntries().map((e) => e.backend), ['JAVASOUND']);
});

test('disconnect empties the offer - nothing lists a backend that cannot be selected', async () => {
  const { manager } = await connected();
  await manager.disconnect();

  assert.deepEqual(manager.getRemoteBackendEntries(), []);
  assert.equal(manager.isRemoteBackend('net:QA40X'), false);
});

test('the scan branch fills the combos from the bench\'s catalogue, by NAME', async () => {
  const { manager, connection } = await connected();

  const found = await scanDevicesForBackend('net:QA40X', null, () => {}, null, manager);

  assert.equal(connection.answers[MessageType.BACKEND_SELECT] != null, true);
  assert.deepEqual(found.inputs,
    [{ id: 'QA403', label: '[0] QA403 (QA403 (bench)) - v', nativeRate: 96000, lockedBy: null }],
    'the id is the NAME - a remote device has no browser deviceId, and the wire names it so - '
    + 'and the label is the DESKTOP\'s: index, name, description, vendor');
  assert.deepEqual(found.outputs,
    [{ id: 'QA403', label: '[0] QA403 (QA403 (bench)) - v', lockedBy: null }]);
});

test('B9: a bench device another client is measuring on is greyed, NAMES the holder, and '
  + 'cannot be picked',
  async () => {
    // Spec 4.3 sends the lock with every device precisely so a client can show it; the web
    // showed nothing at all, so a taken device looked free until the open was refused. The
    // desktop renders it through preferences.device.lockedBy - the same key - and REFUSES the
    // pick (revertLockedDevicePick snaps the combo back), so the web's option carries `disabled`.
    // Greyed but selectable was this cycle's regression: italic, and still pickable into a
    // refusal.
    const { manager, connection, server } = benchManager();
    connection.answers[MessageType.BACKEND_SELECT] = (m) => ({ t: MessageType.RESP, id: m.id,
      ok: true,
      data: { [NetFields.BACKEND]: m[NetFields.BACKEND], [NetFields.DEVICES]: [
        deviceRow(0, 'QA403', true, "Developer's laptop"), deviceRow(0, 'QA403', false)] } });
    await manager.connect(server);
    const { dialog } = comboDialog(manager);
    const inSel = new FakeSelect(); dom.set('#inSel', inSel);
    const outSel = new FakeSelect(); dom.set('#outSel', outSel);

    const found = await scanDevicesForBackend('net:QA40X', null, () => {}, null, manager);
    dialog.fillDeviceCombos(found.inputs, found.outputs);

    const shown = '[0] QA403 (QA403 (bench)) - v';
    assert.equal(found.inputs[0].lockedBy, "Developer's laptop",
      'the holder rides the listing row - only the net branch can see it');
    assert.equal(inSel.options[0].label,
      `${t('preferences.device.lockedBy', shown, "Developer's laptop")} - 96000 Hz`);
    assert.equal(inSel.options[0].cls, 'device-locked', 'greyed by class');
    assert.equal(inSel.options[0].disabled, true,
      'and DISABLED - a device somebody else is measuring on is not a choice, exactly as the '
      + 'desktop refuses the pick');
    assert.equal(inSel.options[0].value, 'QA403',
      'the VALUE is still the wire name: the holder is chrome, not identity');
    assert.equal(outSel.options[0].label, shown, 'the free direction reads exactly as before');
    assert.equal(outSel.options[0].cls, '');
    assert.equal(outSel.options[0].disabled, false,
      'and the free direction stays pickable - only the held one is refused');
    assert.equal(namesNetDevice(manager.listInputDevices()[0], inSel.options[0].label), true,
      'and the card seam still recognises the device through the longer text (B6)');
  });

test('LOCAL backends are untouched: the net manager is never consulted for them', async () => {
  const { manager } = await connected();
  let asked = 0;
  const watched = { isRemoteBackend: (v) => { asked++; return manager.isRemoteBackend(v); },
    selectBackend: () => { throw new Error('a local scan must never select a remote backend'); },
    listInputDevices: () => { throw new Error('nor read its catalogue'); },
    listOutputDevices: () => { throw new Error('nor read its catalogue'); },
    listSupportedFormats: () => [] };

  // QA40X: the LOCAL branch is tested first and returns, so the net manager is not even asked
  // - a local backend's path is exactly the code it always was, with nothing inserted ahead of
  // it. (Its throwing stubs prove no part of the net client is reached.)
  const qa = { listInputDevices: async () => [], listOutputDevices: async () => [] };
  const local = await scanDevicesForBackend('QA40X', qa, () => {}, null, watched);

  assert.deepEqual(local, { inputs: [], outputs: [] });
  assert.equal(asked, 0, 'the local branch short-circuits before the remote test');
  assert.equal(manager.isRemoteBackend('QA40X'), false,
    'and even asked directly, a local name is never routed at the bench');
});

test('with no net client at all, every backend keeps its old path', async () => {
  // The parameter is optional: a build without a net manager passes null, and the branch is
  // simply never taken.
  const qa = { listInputDevices: async () => [], listOutputDevices: async () => [] };
  assert.deepEqual(await scanDevicesForBackend('QA40X', qa, () => {}), { inputs: [], outputs: [] });
});

// --- through the REAL wiring (F12/F13/F14): the seams above are correct in isolation, and were
// --- reached by nothing. Each of these drives the layer that forwards, not the layer that works.

test('THROUGH THE ENGINE: a scan on a server backend reaches the bench, not getUserMedia',
  async () => {
    const { manager, connection } = await connected();
    // AudioBackend.prototype with a minimal `this` - no backend construction, no browser globals.
    // The point is the FORWARD: scanDevicesForBackend's net branch is already proven above, and
    // was unreachable because scanDevices dropped the manager argument.
    const engine = {
      _net: manager,
      activeBackend: () => 'net:QA40X',
      _status: () => {},
      _qa40xGranter: () => null,
      _requireQa40x: () => { throw new Error('a net backend must not ask for the local analyzer'); },
    };

    const found = await AudioBackend.prototype.scanDevices.call(engine);

    assert.deepEqual(found.inputs,
      [{ id: 'QA403', label: '[0] QA403 (QA403 (bench)) - v', nativeRate: 96000, lockedBy: null }],
      'the combos fill from the BENCH - with the manager dropped this was the local probe');
    assert.equal(connection.answers[MessageType.BACKEND_SELECT] != null, true);
    assert.equal(manager.getRemoteBackendEntries().length, 2, 'and the session is untouched');
  });

test('THROUGH THE COMBO: connecting a bench rebuilds the backend picker', async () => {
  const { manager, server } = benchManager();
  const { dialog, sel, prefs } = comboDialog(manager);
  dialog.buildBackendSelector();
  assert.deepEqual(sel.items(), ['Web Audio', 'QA40x', 'Loopback'], 'nothing connected yet');

  // The REAL notification: the manager announces its own cache change, the dialog is subscribed.
  await manager.connect(server);
  await tick();

  // B1 (maintainer bench): a backend the SERVER cannot serve - CoreAudio on a non-Mac, a QA40X
  // with no analyzer attached - is not offered at all. It is not a choice the operator has.
  assert.deepEqual(sel.items(), ['Web Audio', 'QA40x', 'Loopback', 'Bench QA403 -> QA40x']);
  assert.equal(sel.selected, 'WEB_AUDIO', 'and the selection the operator had is preserved');

  // ...and when the session goes, the row becomes the REMEMBERED one rather than disappearing.
  // C3 (maintainer bench) overrides F14's original tail here: migrating the preference to a
  // local backend at this moment is what lost every remote device / rate / width / card
  // selection, since they are stored under the net: key. The combo still names what the
  // preference says, which is all F14 required of it.
  prefs.backend.set('net:QA40X');
  await manager.disconnect();
  await tick();
  assert.equal(sel.items().length, 4, 'the three local rows plus the remembered bench one');
  assert.ok(sel.items()[3].startsWith('QA40X ('), sel.items()[3]);
  assert.equal(prefs.backend.get(), 'net:QA40X', 'the PREFERENCE is kept for the bench\'s return');
  assert.equal(sel.selected, 'net:QA40X', 'and the combo shows it - the two cannot disagree');
});

test('the FULL packaging still offers the local backends (the embedded profile\'s counterpart)',
  () => {
    // The proof that R2.3's build flag changed nothing here: this file - like the whole suite -
    // runs with __PHONALYSER_EMBEDDED__ absent, which is what the dev tree and docs/web run with.
    const { dialog, sel } = comboDialog();
    dialog.buildBackendSelector();
    assert.deepEqual(sel.items(), ['Web Audio', 'QA40x', 'Loopback']);
  });

test('B1 refinement: a page SERVED by a server offers no local backend at all', async () => {
  // The maintainer's rule: the server runs plain http, so that page is not a secure context and
  // has neither getUserMedia nor WebUSB. Web Audio would fail on every open, and the QA40x row -
  // which B1 kept, disabled and explained, because "this browser lacks WebUSB" is actionable -
  // is not actionable here at all. Same runtime fact the auto-connect reads (one probe).
  const { manager, server } = benchManager([backendRow('QA40X'), backendRow('JAVASOUND')]);
  const { dialog, sel, prefs } = comboDialog(manager, { servedByServer: () => true });
  await manager.connect(server);

  dialog.buildBackendSelector();

  assert.deepEqual(sel.items(), ['Bench QA403 -> QA40x', 'Bench QA403 -> JAVASOUND'],
    'the bench\'s backends and nothing else');
  assert.equal(prefs.backend.get(), 'net:QA40X',
    'and the stored WEB_AUDIO, which this page cannot open, moves to the first one offered');

  // The SAME dialog code on a page that was not served by a server: unchanged.
  const plain = comboDialog(manager, { servedByServer: () => false });
  plain.dialog.buildBackendSelector();
  assert.deepEqual(plain.sel.items(),
    ['Web Audio', 'QA40x', 'Loopback', 'Bench QA403 -> QA40x', 'Bench QA403 -> JAVASOUND']);
});

test('C3: a stored net: backend SURVIVES a reload - shown offline, never migrated away',
  async () => {
    // The combo is built when the dialog is wired, which is BEFORE the auto-connect or the
    // servers dialog can have opened a session. Migrating the preference to a local backend
    // there is what made every remote device / rate / width / card selection "gone after a
    // reload" (maintainer bench C3): they are stored under the net: key. F14's rule - the combo
    // must not disagree with the preference - is kept by SHOWING the remembered row instead.
    const { dialog, sel, prefs } = comboDialog();
    prefs.backend.set('net:QA40X');            // remembered from the last session
    prefs.prefsFor('net:QA40X').inputDeviceName = 'QA403';

    dialog.buildBackendSelector();

    assert.equal(prefs.backend.get(), 'net:QA40X', 'the preference is untouched');
    assert.equal(sel.selected, 'net:QA40X', 'and the combo shows it, so the two cannot disagree');
    assert.equal(sel.items().length, 4);
    assert.ok(sel.items()[3].startsWith('QA40X ('), sel.items()[3]);   // "(offline)", localized
    assert.equal(prefs.prefsFor('net:QA40X').inputDeviceName, 'QA403',
      'and the bench\'s own device slot is still there for the session that comes back');

    // A LOCAL preference this build cannot offer still falls back - that rule is unchanged.
    prefs.backend.set('WDMKS');
    dialog.buildBackendSelector();
    assert.equal(prefs.backend.get(), 'WEB_AUDIO');
  });

test('C3: the parked bench backend lists NO devices - never this machine\'s', async () => {
  const { manager } = await javaSoundBench();
  await manager.disconnect();

  const found = await scanDevicesForBackend('net:JAVASOUND', null, () => {}, null, manager);

  assert.deepEqual(found, { inputs: [], outputs: [] },
    'falling through to the local probe would list this machine\'s microphones under a combo '
    + 'that says it is showing a server\'s');
});

// ---------------------------------------------------------------------------
// The MAINTAINER'S BENCH, as it actually answers (B3 / B5 / B7 / B8). A JavaSound server on
// Windows names every DirectSound device the same thing in its DESCRIPTION - "Direct Audio
// Device: DirectSound Capture" - and puts the real device in the NAME. Everything below is
// driven through the real manager, the real enumeration branch and the real dialog methods:
// each of these defects lived in a FORWARD between two pieces that were right on their own.
// ---------------------------------------------------------------------------

/** One JavaSound mixer as the server reports it (JavaSoundDeviceManager: name = Mixer.Info's
 *  getName, description = getDescription - shared by every DirectSound device - vendor =
 *  getVendor). */
function javaSoundRow(index, name, input) {
  return { [NetFields.INDEX]: index, [NetFields.NAME]: name,
    [NetFields.DESCRIPTION]: 'Direct Audio Device: DirectSound Capture',
    [NetFields.VENDOR]: 'Unknown Vendor', [NetFields.INPUT]: input, [NetFields.OUTPUT]: !input,
    [NetFields.HAS_BIT_DEPTH]: true, [NetFields.LOCK]: null,
    [NetFields.FORMATS]: [
      { [NetFields.RATE]: 44100, [NetFields.BITS]: 16, [NetFields.CHANNELS]: 2 },
      { [NetFields.RATE]: 48000, [NetFields.BITS]: 16, [NetFields.CHANNELS]: 2 },
      { [NetFields.RATE]: 48000, [NetFields.BITS]: 24, [NetFields.CHANNELS]: 2 }] };
}

const JS_INPUTS = ['Primary Sound Capture Driver', 'Microphone (Realtek(R) Audio)',
  'Line (2- USB Audio CODEC)'];

async function javaSoundBench() {
  const connection = new FakeConnection();
  connection.answers[MessageType.BACKEND_LIST] = (m) => ({ t: MessageType.RESP, id: m.id, ok: true,
    data: { [NetFields.BACKENDS]: [backendRow('JAVASOUND')] } });
  connection.answers[MessageType.BACKEND_SELECT] = (m) => ({ t: MessageType.RESP, id: m.id, ok: true,
    data: { [NetFields.BACKEND]: m[NetFields.BACKEND],
      [NetFields.DEVICES]: [...JS_INPUTS.map((name, i) => javaSoundRow(i, name, true)),
        javaSoundRow(0, 'Primary Sound Driver', false),
        javaSoundRow(1, 'Speakers (Realtek(R) Audio)', false)] } });
  const manager = new NetDeviceManager({ preferences: new NetPreferences(),
    openConnection: async () => connection });
  await manager.connect(makeServerEntry('uuid-1', 'Bench', '10.0.0.1', 8377, true));
  return { manager, connection };
}

test('B3: devices that SHARE a description are still told apart - the name is on screen',
  async () => {
    const { manager } = await javaSoundBench();

    const found = await scanDevicesForBackend('net:JAVASOUND', null, () => {}, null, manager);

    assert.deepEqual(found.inputs.map((d) => d.id), JS_INPUTS,
      'the <select> VALUE is the wire name - what every request and the open resolve by');
    assert.equal(new Set(found.inputs.map((d) => d.label)).size, JS_INPUTS.length,
      'three devices, three DIFFERENT labels: `description || name` printed one sentence three '
      + 'times over and the operator could not tell which row was which');
    for (const device of found.inputs) {
      assert.ok(device.label.includes(device.id),
        `the real device name belongs in the label: ${device.label}`);
    }
    assert.equal(found.inputs[2].label,
      '[2] Line (2- USB Audio CODEC) (Direct Audio Device: DirectSound Capture) - Unknown Vendor',
      'character for character the desktop label (DeviceRef.displayName)');
    assert.equal(found.inputs[0].nativeRate, 48000, 'the highest rate the SERVER offers');
  });

test('B3: the rate and bit-depth combos offer what the SERVER declared, and nothing else',
  async () => {
    const { manager } = await javaSoundBench();
    const { dialog, prefs } = comboDialog(manager, { backendManager: appBackendManager(manager) });
    prefs.backend.set('net:JAVASOUND');
    await manager.selectBackend('JAVASOUND');   // what the scan does before filling the combos
    shownIndex.inSel = 2;                       // the operator picked "Line (2- USB Audio CODEC)"
    shownIndex.outSel = 1;

    assert.equal(dialog.shownManager(), manager,
      'the bench IS the manager of its own backend - with none, the dialog fell back to the '
      + 'web\'s static rate list and hid the depth rows');
    assert.deepEqual(await dialog.backendRates(true), [44100, 48000]);
    assert.deepEqual(await dialog.backendRates(false), [44100, 48000]);
    assert.deepEqual(await dialog.backendDepths(true), [16, 24],
      'spec 4.3 inlines the widths with the device; the operator picks from those');
    assert.deepEqual(await dialog.backendDepths(false), [16, 24]);
    assert.equal(dialog.backendHasBitDepth(), true,
      'hasBitDepth is true on every server backend, so BOTH depth rows are shown');
  });

test('C1: each DIRECTION offers its own widths - an asymmetric bench is shown asymmetrically',
  async () => {
    // His bench: the input probes 24 bit, the line out takes 16, 24 and 32. A device's formats
    // are per direction on the wire (spec 4.3 inlines them with the device, and a device object
    // names one direction), so the two combos must never be filled from one another's list.
    const wide = (index, name, input, bitsList) => ({
      [NetFields.INDEX]: index, [NetFields.NAME]: name,
      [NetFields.DESCRIPTION]: 'Direct Audio Device: DirectSound Playback',
      [NetFields.VENDOR]: 'Unknown Vendor', [NetFields.INPUT]: input, [NetFields.OUTPUT]: !input,
      [NetFields.HAS_BIT_DEPTH]: true, [NetFields.LOCK]: null,
      [NetFields.FORMATS]: bitsList.map((bits) => (
        { [NetFields.RATE]: 48000, [NetFields.BITS]: bits, [NetFields.CHANNELS]: 2 })),
    });
    const connection = new FakeConnection();
    connection.answers[MessageType.BACKEND_LIST] = (m) => ({ t: MessageType.RESP, id: m.id, ok: true,
      data: { [NetFields.BACKENDS]: [backendRow('JAVASOUND')] } });
    connection.answers[MessageType.BACKEND_SELECT] = (m) => ({ t: MessageType.RESP, id: m.id, ok: true,
      data: { [NetFields.BACKEND]: m[NetFields.BACKEND], [NetFields.DEVICES]: [
        wide(0, 'Microphone (Cubilux)', true, [24]),
        wide(0, 'Line out (Cubilux)', false, [16, 24, 32])] } });
    const manager = new NetDeviceManager({ preferences: new NetPreferences(),
      openConnection: async () => connection });
    await manager.connect(makeServerEntry('uuid-1', 'Bench', '10.0.0.1', 8377, true));
    await manager.selectBackend('JAVASOUND');
    const { dialog, prefs } = comboDialog(manager, { backendManager: appBackendManager(manager) });
    prefs.backend.set('net:JAVASOUND');
    shownIndex.inSel = 0;
    shownIndex.outSel = 0;

    assert.deepEqual(await dialog.backendDepths(true), [24], 'the capture side, unchanged');
    assert.deepEqual(await dialog.backendDepths(false), [16, 24, 32],
      'and the line out offers every width the SERVER declared for it - one direction\'s list '
      + 'must never be filled from the other\'s');

    // ...and the OUTPUT combos are actually re-filled when that device is picked. Nothing did
    // this before: only #inSel's change re-derived anything, so the output rows kept whatever
    // the first output device offered (bind() wires this method to #outSel's change).
    const depthCombo = dom.get('#outDepth') || new FakeSelect();
    dom.set('#outDepth', depthCombo);
    dom.set('#outRate', new FakeSelect());
    await dialog.refreshOutputRatesAndDepths();
    assert.deepEqual(depthCombo.items(), ['16 bits', '24 bits', '32 bits'],
      'every width the server declared for the SELECTED output device is on screen');
    assert.deepEqual(dom.get('#outRate').items(), ['48000 Hz']);
  });

test('B5: a bench backend never reaches the LOCAL analyzer; a bench QA40x still finds its own',
  async () => {
    const qa40x = { hasCustomPreferences: () => true };
    const { manager } = await javaSoundBench();
    const { dialog, prefs } = comboDialog(manager,
      { backendManager: appBackendManager(manager, qa40x) });

    prefs.backend.set('net:JAVASOUND');
    assert.equal(dialog.settingsManager(), null,
      'a JavaSound device on a server has no analyzer behind it - resolving the settings by the '
      + 'whole selection FIRST and by type only as a fallback is what let a remote pick reach '
      + 'this machine\'s QA40x');

    prefs.backend.set('net:QA40X');
    assert.equal(dialog.settingsManager(), qa40x,
      'and spec 4.6 still resolves a bench analyzer\'s settings by TYPE (R2.2-6 unbroken)');

    prefs.backend.set('QA40X');
    assert.equal(dialog.settingsManager(), qa40x, 'the local one is unchanged');
  });

test('B6: the card seam finds the bench ref behind EVERY spelling the combo carries',
  async () => {
    const { manager } = await javaSoundBench();
    await manager.selectBackend('JAVASOUND');
    const ref = manager.listInputDevices()[2];
    // What app.js's benchRef does, over the real catalogue and the real matcher.
    const find = (input, text) => (input ? manager.listInputDevices() : manager.listOutputDevices())
      .find((r) => namesNetDevice(r, text)) || null;

    assert.equal(find(true, 'Line (2- USB Audio CODEC)'), ref,
      'the wire NAME - what a staged binding is remembered by, and what commit() passes back in');
    assert.equal(find(true, ref.displayName()), ref, 'the OUTPUT combo\'s option text');
    assert.equal(find(true, `${ref.displayName()} - 48000 Hz`), ref,
      'and the INPUT combo\'s, whose text carries the rate fillDeviceCombos appends - this is '
      + 'the one that matched nothing, so a bench card could be listed but never selected');
    assert.equal(find(true, 'Direct Audio Device: DirectSound Capture'), null,
      'a bare description names no ONE device on this bench - three of them share it');
    assert.equal(find(true, 'Line'), null, 'and a prefix of a name is not that name');
  });

test('B7/B8: an open on a name the bench does not offer says WHY, not "reason unknown"',
  async () => {
    const { manager } = await javaSoundBench();
    await manager.selectBackend('JAVASOUND');
    // A real AudioBackend, minus the constructor's browser globals: every method on the path
    // (_newNetCaptureSource -> _requireNet -> the manager's own resolution) is the shipped one.
    const engine = Object.create(AudioBackend.prototype);
    engine._net = manager;

    // The good case first: the configured NAME resolves to the bench's own ref.
    assert.ok(engine._newNetCaptureSource('Line (2- USB Audio CODEC)'),
      'the selected device opens through the manager\'s own catalogue');

    let failure = null;
    try {
      engine._newNetCaptureSource('Line (1- Some Other Card)');   // renamed, or never committed
    } catch (e) {
      failure = e;
    }
    assert.ok(failure, 'a name this bench does not offer must not open its neighbour');
    assert.equal(manager.classifyFailure(failure).name, 'DEVICE_NOT_FOUND',
      'the operator is told the device is not there; a bare Error classified as UNKNOWN and the '
      + 'start reported "reason unknown", which names neither the device nor anything to do');
    assert.ok(failure.message.includes('Line (1- Some Other Card)'), 'and the log line names it');
  });

// ---------------------------------------------------------------------------
// B16 / B18 - the Preferences dialog's own scan, driven through the REAL enumeration path: the
// dialog's scan() -> AudioEngine.scanDevices -> scanDevicesForBackend's net branch -> the manager's
// backend.select on the scripted connection. Both defects live in that chain and in nothing else.
// ---------------------------------------------------------------------------

/** The engine the dialog scans through: AudioEngine's own method over a minimal `this`, so the
 *  forward under test is the shipped one (the same shape the ENGINE test above uses). */
function scanningEngine(manager, prefs) {
  return {
    _net: manager,
    activeBackend: () => prefs.backend.get(),
    _status: () => {},
    _qa40xGranter: () => null,
    _requireQa40x: () => { throw new Error('a net backend must not ask for the local analyzer'); },
    scanDevices(fromUserGesture) {
      return AudioBackend.prototype.scanDevices.call(this, fromUserGesture);
    },
  };
}

/** The dialog a backend switch runs on: the real one, over the fake combos, with the engine
 *  above wired in. */
function scanningDialog(manager, more = {}) {
  const prefs = new Preferences(true);
  const dialog = new PreferencesDialog(null, prefs, {
    netBackends: () => manager.getRemoteBackendEntries(),
    backendManager: appBackendManager(manager),
    ...more,
  });
  dialog.engine = scanningEngine(manager, prefs);
  dom.set('#inSel', new FakeSelect());
  dom.set('#outSel', new FakeSelect());
  dom.set('#inRate', new FakeSelect());
  dom.set('#outRate', new FakeSelect());
  dom.set('#status', new FakeSelect());
  dom.set('#scan', new FakeSelect());
  return { dialog, prefs };
}

/** Waits for the fire-and-forget scan a backend switch starts (onBackendChanged does not await
 *  it - the combos settle on a later tick). */
async function settle(done) {
  for (let i = 0; i < 50 && !done(); i++) await tick();
  return done();
}

/** Switches the dialog to a backend and waits until that backend's OWN device is in the combo. */
function switchBackend(dialog, backend) {
  dialog.onBackendChanged(backend);
  return settle(() => dom.get('#inSel').items()
    .some((label) => label.includes(`${remoteBackendOf(backend)} in`)));
}

/** A bench offering TWO backends, each with its own single device pair. `refuse.on` turns every
 *  backend.select into the refusal a backend the server cannot run answers with. */
async function twoBackendBench() {
  const connection = new FakeConnection();
  connection.answers[MessageType.BACKEND_LIST] = (m) => ({ t: MessageType.RESP, id: m.id, ok: true,
    data: { [NetFields.BACKENDS]: [backendRow('QA40X'), backendRow('WASAPI')] } });
  const selects = [];
  const refuse = { on: false };
  connection.answers[MessageType.BACKEND_SELECT] = (m) => {
    selects.push(m[NetFields.BACKEND]);
    if (refuse.on) {
      return { t: MessageType.RESP, id: m.id, ok: false,
        error: { code: 'BAD_REQUEST', message: 'this backend is not operational' } };
    }
    return { t: MessageType.RESP, id: m.id, ok: true,
      data: { [NetFields.BACKEND]: m[NetFields.BACKEND], [NetFields.DEVICES]: [
        deviceRow(0, `${m[NetFields.BACKEND]} in`, true), deviceRow(0, `${m[NetFields.BACKEND]} out`, false)] } };
  };
  const manager = new NetDeviceManager({ preferences: new NetPreferences(),
    openConnection: async () => connection });
  await manager.connect(makeServerEntry('uuid-1', 'Bench', '10.0.0.1', 8377, true));
  return { manager, connection, selects, refuse };
}

test('B16: switching BACK to a bench backend re-selects it on the session, cache or no cache',
  async () => {
    // The session answers only for the backend it was last ROUTED at (net-device-manager.call).
    // Rebuilding the combos from the _known cache leaves it routed at whichever backend was
    // scanned last, so cards.list came back null and the card combo was empty until the operator
    // pressed "Scan devices" - which repaired it precisely because it re-routes.
    const { manager, selects } = await twoBackendBench();
    const { dialog, prefs } = scanningDialog(manager);

    assert.ok(await switchBackend(dialog, 'net:QA40X'), `first switch: ${selects}`);
    assert.ok(await switchBackend(dialog, 'net:WASAPI'), `second switch: ${selects}`);
    assert.ok(await switchBackend(dialog, 'net:QA40X'),
      `the switch BACK must select again - the cache says nothing about where the session points: ${selects}`);

    assert.deepEqual(selects, ['QA40X', 'WASAPI', 'QA40X']);
    assert.equal(prefs.backend.get(), 'net:QA40X');
    assert.notEqual(await manager.call('net:QA40X', MessageType.CARDS_LIST), null,
      'and THAT is the symptom: a session routed elsewhere answers null, so the card combo is '
      + 'filled from an empty list');
    assert.deepEqual(dom.get('#inSel').items(), ['[0] QA40X in (QA40X in (bench)) - v - 96000 Hz'],
      'the device combo carries the backend that is now selected, not the one before it');
  });

test('B18: a scan that answers NOTHING keeps the devices already shown', async () => {
  // A refused backend.select and an unreachable server both arrive as {inputs:[],outputs:[]}, and
  // fillDeviceCombos empties both selects before filling them - so a refusal wiped the device
  // combos, and with them (through the device label the card sections key off) the card combos.
  const { manager, connection, selects } = await twoBackendBench();
  const { dialog } = scanningDialog(manager);

  assert.ok(await switchBackend(dialog, 'net:QA40X'), 'the good scan filled the combos');
  const shown = dom.get('#inSel').items();
  const shownOut = dom.get('#outSel').items();
  assert.equal(selects.length, 1);

  connection.answers[MessageType.BACKEND_SELECT] = (m) => ({ t: MessageType.RESP, id: m.id,
    ok: false, error: { code: 'BAD_REQUEST', message: 'this backend is not operational' } });
  await dialog.scan(true);

  assert.deepEqual(dom.get('#inSel').items(), shown, 'the input combo still shows what it showed');
  assert.deepEqual(dom.get('#outSel').items(), shownOut, 'and so does the output combo');
  assert.equal(dom.get('#status').label, 'nothing was listed - the devices already shown are kept.',
    'the reason is on the status line, not in an emptied combo');
  assert.deepEqual(dialog.knownForShownBackend().inputs.map((d) => d.id), ['QA40X in'],
    'and the empty listing is not cached either - the next switch back would replay it');
});

test('B16/B18: a switch to a backend that answers NOTHING never shows the previous one\'s devices',
  async () => {
    // The two rules meet here: the switch scans (B16) and the scan answers nothing, so the
    // keep-guard (B18) must NOT keep - the options on screen belong to the backend being left.
    // Kept, they would be shown as the new backend's, and OK would write one of those ids into
    // its slot for the first open to fail on (devices.js documents that hazard for the local
    // probe; this is the same thing one layer up).
    const { manager, refuse } = await twoBackendBench();
    const { dialog } = scanningDialog(manager);

    assert.ok(await switchBackend(dialog, 'net:QA40X'), 'the outgoing backend is listed first');
    refuse.on = true;
    dialog.onBackendChanged('net:WASAPI');
    const cleared = await settle(() => !dom.get('#inSel').items().some((l) => l.includes('QA40X in')));

    assert.ok(cleared, `the previous backend's devices are still on screen: ${dom.get('#inSel').items()}`);
    assert.deepEqual(dom.get('#inSel').items(), [],
      'nothing is known for this backend and nothing is persisted for it - the truthful empty');
    assert.deepEqual(dom.get('#outSel').items(), []);
    assert.match(dom.get('#status').label, /showing what is known for this backend/);
  });

test('B16/B18: ...and where that backend HAS been listed before, its own devices come back',
  async () => {
    const { manager, refuse } = await twoBackendBench();
    const { dialog } = scanningDialog(manager);

    assert.ok(await switchBackend(dialog, 'net:WASAPI'), 'listed once, so it has a cache');
    assert.ok(await switchBackend(dialog, 'net:QA40X'));
    refuse.on = true;
    dialog.onBackendChanged('net:WASAPI');
    const own = await settle(() => dom.get('#inSel').items().some((l) => l.includes('WASAPI in')));

    assert.ok(own, `the cache was not used: ${dom.get('#inSel').items()}`);
    assert.ok(!dom.get('#inSel').items().some((l) => l.includes('QA40X in')));
  });

test('B16: a switch while the app is BUSY still rebuilds the combos for the backend picked',
  async () => {
    // scan() serializes with a live capture reopen and refuses while the shared flag is held. The
    // switch still has to leave the new backend's own devices on screen - the cache path is what
    // every backend switch did before the re-scan branch existed.
    const { manager, selects } = await twoBackendBench();
    let busy = false;
    const { dialog } = scanningDialog(manager, { isBusy: () => busy });

    assert.ok(await switchBackend(dialog, 'net:QA40X'));
    assert.ok(await switchBackend(dialog, 'net:WASAPI'));
    const before = selects.length;
    busy = true;
    dialog.onBackendChanged('net:QA40X');
    const back = await settle(() => dom.get('#inSel').items().some((l) => l.includes('QA40X in')));

    assert.ok(back, `the combos were left showing the other backend: ${dom.get('#inSel').items()}`);
    assert.equal(selects.length, before, 'and nothing was enumerated while the device was busy');
  });

test('B16: a scan overtaken by a backend switch paints nothing and files nothing', async () => {
  // Two things used to go wrong when a switch landed inside a scan's await. The cache write read
  // prefs.backend AFTER the await, filing one backend's listing under the other's key; and the
  // paint went ahead regardless, putting the enumerated backend's devices on screen under the new
  // backend's name - stamped as ITS devices, so a later empty scan of it kept them as its own.
  // Neither half survives: a result that belongs to a backend the operator has left is dropped.
  const { manager } = await twoBackendBench();
  const { dialog, prefs } = scanningDialog(manager);
  assert.ok(await switchBackend(dialog, 'net:WASAPI'), 'the backend the operator ends up on');
  const shown = dom.get('#inSel').items();

  prefs.backend.set('net:QA40X');             // a scan starts on one backend...
  const scanning = dialog.scan();
  prefs.backend.set('net:WASAPI');            // ...and the operator is back on the other before it lands
  await scanning;
  await settle(() => false);                  // give any late repaint every chance to happen

  assert.deepEqual(dom.get('#inSel').items(), shown,
    'the combos still show the backend the operator is ON, not the one the scan enumerated');
  assert.equal(dialog._comboBackend, 'net:WASAPI',
    'and they are still stamped as its devices - a wrong stamp is what makes an empty scan keep '
    + 'a foreign listing');
  assert.deepEqual([...dialog._known.keys()], ['net:WASAPI'],
    'nothing was filed for the enumerated backend either: the entry carries the rate lists, and '
    + 'those can only be read off the combos this scan may no longer touch - so the next visit '
    + 'to it enumerates again. What must never happen is a listing filed under the NEW backend.');
  assert.deepEqual(dialog._known.get('net:WASAPI').inputs.map((d) => d.id), ['WASAPI in'],
    'and the entry it does have is untouched');
});

test('B16: Cancel after browsing another bench backend re-routes the session back', async () => {
  // The rollback put the original backend's devices back on screen but left the SESSION routed at
  // the browsed one - the same empty card combo as the original defect, reached with two clicks.
  const { manager, selects } = await twoBackendBench();
  const { dialog } = scanningDialog(manager);

  assert.ok(await switchBackend(dialog, 'net:QA40X'));
  dialog.snapshotAudioPrefs();                       // the dialog opens here
  assert.ok(await switchBackend(dialog, 'net:WASAPI'));   // the operator browses the other one
  selects.length = 0;

  await dialog.restoreAudioPrefs();                  // Cancel

  assert.deepEqual(selects, ['QA40X'], 'the session is pointed back at the backend in force');
  assert.notEqual(await manager.call('net:QA40X', MessageType.CARDS_LIST), null,
    'so its card list answers again');
  assert.ok(dom.get('#inSel').items().some((l) => l.includes('QA40X in')),
    `the devices came back too: ${dom.get('#inSel').items()}`);
});
