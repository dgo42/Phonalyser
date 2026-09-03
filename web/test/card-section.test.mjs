/*
 * Phonalyser web - CardSection contract test: the LOCAL card combo and the BENCH one.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of the invariants in gui/preferences/PreferencesDialog.CardSection -
 * refresh / refreshBenchCards / onCardSelected / bindBenchCard / commitStagedBindings / rangeCard -
 * plus the two-step OK-time calibration test the dialog composes around it
 * (Preferences.isUncalibrated(DeviceRef, boolean)).
 *
 * The point of the file is the SEAM: with no cardSource - and with one that answers null for a
 * LOCAL selection - every path must behave exactly as it did before the seam existed; with one
 * that answers a bench's cards, the combo lists the SERVER's card names, offers no "New card...",
 * stages a pick instead of writing the local store, and shows ranges only for a card the DEVICE
 * itself supplied.
 *
 * There is no jsdom in this project (every other unit test here is headless), so a minimal jQuery
 * stands in - only the surface card-section.js actually touches: option lists on a <select>,
 * val/prop/css/toggleClass/on/is and HTML-string element creation.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

// ---------------------------------------------------------------------------
// Minimal jQuery: selectors resolve to ONE stable node each (as a document does),
// an HTML string builds a new one.
// ---------------------------------------------------------------------------

class FakeNode {
  constructor(html = '<div></div>') {
    this.html = html;
    this.tag = (/^<(\w+)/.exec(html) || [null, 'div'])[1];
    this.attrValue = (/value="([^"]*)"/.exec(html) || [null, null])[1];
    this.text = (/>([^<]*)</.exec(html) || [null, ''])[1];
    /** {value, text} per <option> appended - a <select>'s item list. */
    this.options = [];
    this.children = [];
    this.props = { checked: /\bchecked\b/.test(html) };
    this.styles = {};
    this.classes = new Map();
    this.handlers = new Map();
    this.value = this.attrValue;
    /** The selected option's value, or null when nothing is selected. */
    this.selected = null;
  }

  empty() { this.options = []; this.children = []; this.selected = null; return this; }

  append(...items) {
    for (const item of items) {
      const node = item instanceof FakeNode ? item : new FakeNode(String(item));
      if (node.tag === 'option') this.options.push({ value: node.attrValue, text: node.text });
      else this.children.push(node);
    }
    return this;
  }

  val(v) {
    if (v === undefined) return this.options.length > 0 ? this.selected : this.value;
    if (this.options.length > 0) {
      const hit = this.options.find((o) => o.value === String(v));
      this.selected = hit ? hit.value : null;   // jQuery: no matching option -> selectedIndex = -1
      return this;
    }
    this.value = v;
    return this;
  }

  prop(name, v) { this.props[name] = v; return this; }
  css(name, v) { this.styles[name] = v; return this; }
  toggleClass(name, on) { this.classes.set(name, !!on); return this; }
  is(selector) { return selector === ':checked' ? !!this.props.checked : false; }
  on(events, fn) { for (const e of events.split(' ')) this.handlers.set(e, fn); return this; }
  /** Test-side dispatch (no real event system here). */
  fire(event) { const fn = this.handlers.get(event); return fn ? fn() : undefined; }
  /** The option TEXTS, i.e. what the combo shows. */
  items() { return this.options.map((o) => o.text); }
  /** The text of the selected option, or null. */
  shown() {
    const hit = this.options.find((o) => o.value === this.selected);
    return hit ? hit.text : null;
  }
}

const dom = new Map();
function $(arg) {
  if (arg instanceof FakeNode) return arg;
  const s = String(arg);
  if (s.startsWith('<')) return new FakeNode(s);
  let node = dom.get(s);
  if (!node) { node = new FakeNode(); dom.set(s, node); }
  return node;
}
globalThis.$ = $;
// Headless there are no bundles, so t() returns the key and warns once per key.
console.warn = () => {};

// --- shims device-profiles.js / preferences.js need before import ---
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
const { CardSection } = await import('../js/shell/card-section.js');
const { PreferencesDialog } = await import('../js/shell/preferences-dialog.js');

const EMPTY_CATALOG = { formatVersion: 1, contentVersion: 0, audioDevices: [] };
let keyCounter = 0;

/** A card with one range row on the asked direction (the shape the combo lists). */
function card(name, { input = true, match = [name], label = 'default', fromDevice = false } = {}) {
  const p = new AudioDeviceProfile();
  p.name = name;
  p.match = [...match];
  const range = new DeviceRange();
  range.label = label;
  range.fsLeft = 1.0;
  range.fsRight = 1.0;
  const ep = input ? p.input : p.output;
  ep.ranges.push(range);
  ep.activeRange = label;
  ep.calibrationFromDevice = fromDevice;
  return p;
}

/** A CardSection over a fresh store, wired to the fake DOM ids used throughout. */
function newSection({ cards = [], device = 'Line In (Focusrite)', cardSource = null, input = true,
  confirm = false } = {}) {
  const prefs = new Preferences(true);
  const store = new DeviceProfileStore(prefs, {
    storageKey: 'phonalyser.devices.cardsection.' + (++keyCounter), catalog: EMPTY_CATALOG,
  });
  for (const p of cards) store.putAudioDeviceProfile(p);
  const bound = [];
  const origBind = store.bindDeviceToCard.bind(store);
  store.bindDeviceToCard = (dev, name) => { bound.push([dev, name]); return origBind(dev, name); };
  const suffix = '.' + keyCounter;
  const prompts = [];
  const alerts = [];
  const section = new CardSection(prefs, store, { open: async () => null }, {
    input,
    comboSel: '#combo' + suffix,
    editSel: '#edit' + suffix,
    rangesSel: '#ranges' + suffix,
    deviceLabel: () => device,
    showConfirm: async (title, message) => { prompts.push([title, message]); return confirm; },
    showAlert: (title, message) => { alerts.push([title, message]); },
    isStaging: () => true,
    cardSource,
  }).bind();   // the combo's change + edit-click listeners, as the dialog arms them
  return {
    section, store, prefs, bound, prompts, alerts,
    combo: $('#combo' + suffix),
    edit: $('#edit' + suffix),
    ranges: $('#ranges' + suffix),
  };
}

/** A cardSource double with the seam's contract (app.js builds the live one over BenchCards).
 *  `calibration` follows the seam's three-valued answer: an object = the bench's own, null = it has
 *  none, undefined = it does not offer the device at all. */
function fakeSource({ names = [], boundCard = null, calibration = null, refused = [],
  copyTakes = true, outcome = null } = {}) {
  const staged = [];
  return {
    calls: 0,
    staged,
    commits: [],
    cleared: 0,
    asked: 0,
    copied: [],
    async cards(input, device) {
      this.calls++;
      this.lastDevice = device;
      if (names == null) return null;                       // a LOCAL selection
      const pick = staged.length > 0 ? staged[staged.length - 1][2] : boundCard;
      return { names, bound: pick };
    },
    stage(input, device, cardName) { staged.push([input, device, cardName]); },
    calibration: () => calibration,
    async commit(input) { this.commits.push(input); return refused; },
    clearStaged() { this.cleared++; staged.length = 0; },
    // The offer is spent only when the question is SETTLED, never merely asked (S4).
    settled: false,
    mayCopyAsk() { this.asked++; return !this.settled; },
    settleCopyAsk() { this.settled = true; },
    // The propagation's outcome, in BenchCards.Copied's own vocabulary.
    async copyCalibration(input, device) {
      this.copied.push([input, device]);
      return outcome != null ? outcome : (copyTakes ? 'CARD' : 'NOTHING');
    },
  };
}

// ---------------------------------------------------------------------------
// LOCAL mode - unchanged by the seam
// ---------------------------------------------------------------------------

test('local: the combo lists this direction\'s cards + "New card..." and preselects the resolved one',
  async () => {
    const h = newSection({
      cards: [card('Focusrite'), card('Speakers', { input: false, match: ['Speakers'] })],
      device: 'Line In (Focusrite)',
    });
    const pick = await h.section.refresh();
    // The output-only card is hidden on the input side; the last entry is always "New card...".
    assert.deepEqual(h.combo.items(), ['Focusrite', 'preferences.audio.card.new']);
    assert.equal(h.combo.shown(), 'Focusrite');
    assert.equal(pick.name, 'Focusrite');
    assert.equal(h.edit.props.disabled, false);
    assert.equal(h.ranges.children.length, 1);              // the one range row
  });

test('local: picking a card writes the binding AND the alias into the live store', async () => {
  const h = newSection({ cards: [card('Focusrite', { match: ['nothing-matches'] })], device: 'USB Codec' });
  await h.section.refresh();
  assert.equal(h.combo.shown(), 'preferences.audio.card.new');   // nothing resolved
  h.combo.val('0');
  await h.combo.fire('change');
  assert.deepEqual(h.bound, [['USB Codec', 'Focusrite']]);
  assert.ok(h.store.findAudioDeviceProfile('Focusrite').match.includes('USB Codec'));
});

test('local: a cardSource that answers null leaves every path byte-identical', async () => {
  const source = fakeSource({ names: null });
  const h = newSection({ cards: [card('Focusrite')], device: 'Line In (Focusrite)', cardSource: source });
  await h.section.refresh();
  assert.deepEqual(h.combo.items(), ['Focusrite', 'preferences.audio.card.new']);
  h.combo.val('0');
  await h.combo.fire('change');
  assert.deepEqual(h.bound, [['Line In (Focusrite)', 'Focusrite']]);   // the STORE, not the seam
  assert.deepEqual(source.staged, []);
  assert.deepEqual(await h.section.commitStagedBindings(), []);        // nothing to send
});

// ---------------------------------------------------------------------------
// BENCH mode
// ---------------------------------------------------------------------------

test('bench: the combo lists the SERVER\'s card names, with no "New card..." entry', async () => {
  const source = fakeSource({ names: ['QA403', 'Bench ADC'], boundCard: 'Bench ADC' });
  const h = newSection({ cards: [card('Focusrite')], device: 'Bench line in', cardSource: source });
  const pick = await h.section.refresh();
  assert.deepEqual(h.combo.items(), ['QA403', 'Bench ADC']);   // no local card, no "New card..."
  assert.equal(h.combo.shown(), 'Bench ADC');                  // preselected from boundCard()
  assert.equal(pick, null);                                    // a bench device resolves to no local card
  assert.equal(source.lastDevice, 'Bench line in');
});

test('bench: a bound card the server no longer offers leaves the combo empty', async () => {
  const source = fakeSource({ names: ['QA403'], boundCard: 'Retired card' });
  const h = newSection({ device: 'Bench line in', cardSource: source });
  await h.section.refresh();
  assert.equal(h.combo.selected, null);
  assert.equal(h.combo.shown(), null);
});

test('bench: a pick is STAGED - never written to the local store - and shows on the next refresh',
  async () => {
    const source = fakeSource({ names: ['QA403', 'Bench ADC'], boundCard: 'Bench ADC' });
    const h = newSection({ cards: [card('QA403')], device: 'Bench line in', cardSource: source });
    await h.section.refresh();
    h.combo.val('0');
    await h.combo.fire('change');
    assert.deepEqual(source.staged, [[true, 'Bench line in', 'QA403']]);
    assert.deepEqual(h.bound, []);            // nothing reached this machine's binding block
    await h.section.refresh();
    assert.equal(h.combo.shown(), 'QA403');   // the staged pick wins over the bench's own answer
  });

test('bench: OK walks the staged picks through the seam and reports what was refused', async () => {
  const source = fakeSource({ names: ['QA403'], refused: ['QA403'] });
  const h = newSection({ device: 'Bench line in', cardSource: source });
  await h.section.refresh();
  assert.deepEqual(await h.section.commitStagedBindings(), ['QA403']);
  assert.deepEqual(source.commits, [true]);   // this direction only
});

test('bench: no ranges and no card editing for a card that lives on the server', async () => {
  // A LOCAL card of the SAME NAME as one of the bench's must not put its ranges - or its full
  // scales - under a device calibrated on another machine.
  const source = fakeSource({ names: ['QA403'], boundCard: 'QA403' });
  const h = newSection({ cards: [card('QA403', { match: ['Bench line in'] })], device: 'Bench line in', cardSource: source });
  await h.section.refresh();
  assert.equal(h.combo.shown(), 'QA403');
  assert.equal(h.ranges.children.length, 0);
  assert.equal(h.edit.props.disabled, true);
});

test('bench: a device-provided card resolved BY DEVICE does show its ranges (the synced QA40x)',
  async () => {
    const source = fakeSource({ names: ['QA403'], boundCard: 'QA403' });
    const synced = card('QA403', { match: ['Bench QA403'], label: '0 dBV', fromDevice: true });
    const second = new DeviceRange();
    second.label = '-20 dBV';
    second.fsLeft = 0.1; second.fsRight = 0.1;
    synced.input.ranges.push(second);
    const h = newSection({ cards: [synced], device: 'Bench QA403', cardSource: source });
    await h.section.refresh();
    assert.equal(h.ranges.children.length, 2);       // both attenuator positions
    assert.equal(h.edit.props.disabled, true);       // still not editable here
  });

/** The label field of the range row at {@code i} - what the operator actually reads. */
function rangeLabelShown(h, i) {
  const row = h.ranges.children[i];
  const field = row.children.find((c) => /range-label/.test(c.html));
  return field ? field.value : null;
}

/**
 * A QA40x INPUT row shows the VERBOSE label, exactly as the desktop dialog does -
 * `0 "dBV" real 0 dBFS or -9 dBV`, not the bare key.
 *
 * The maintainer's screenshots showed the web printing the plain key for both a LOCAL
 * QA40x and a bench one, while the Java dialog showed the verbose text: the row carries
 * the verbose text as displayLabel and the table was reading the KEY.
 */
test('a device-owned row shows its verbose display label, not the plain key', async () => {
  const source = fakeSource({ names: ['QA403'], boundCard: 'QA403' });
  const synced = card('QA403', { match: ['Bench QA403'], label: '0 dBV', fromDevice: true });
  synced.input.ranges[0].displayLabel = '0 "dBV" real 0 dBFS or -9 dBV';
  const h = newSection({ cards: [synced], device: 'Bench QA403', cardSource: source });

  await h.section.refresh();

  assert.equal(rangeLabelShown(h, 0), '0 "dBV" real 0 dBFS or -9 dBV');
});

/** An ordinary card's row has no display label, so the KEY is what shows - and the key is
 *  what the rename flow round-trips. */
test('an ordinary row still shows its key, which is what a rename edits', async () => {
  const h = newSection({ cards: [card('Focusrite', { label: 'default' })] });

  await h.section.refresh();

  assert.equal(rangeLabelShown(h, 0), 'default');
});

/** The label input of the range row at {@code i}; {@code right} picks the INDEPENDENT
 *  mirror (the second one in the row). */
function labelField(h, i, right = false) {
  const fields = h.ranges.children[i].children.filter((c) => /range-label/.test(c.html));
  return right ? fields[1] : fields[0];
}

/** The row as the STORE has it - the live card and the persisted document, which is where
 *  a corrupted key would actually hurt. */
function storedRow(h, cardName, i = 0) {
  const doc = JSON.parse(globalThis.localStorage.getItem(h.store._storageKey));
  const persisted = doc.audioDevices.find((c) => c.name === cardName).input.ranges[i];
  return { live: h.store.findAudioDeviceProfile(cardName).input.ranges[i], persisted };
}

/**
 * A device-owned row's label field is READONLY, not disabled - it still focuses and still
 * fires blur. So the rename handler must not be BOUND to it at all (Java binds the label /
 * add / remove listeners inside `if (!deviceProvided)`).
 *
 * Without that, reading the field is enough to break the device: click in, click away, and
 * the verbose text the field now shows is committed into the row's protocol KEY - the short
 * `N dBV` the backend decodes into a register value (QA40x reg 0x05 / 0x06) - and persisted.
 */
test('a device-owned row cannot commit its verbose text into the protocol key', async () => {
  const source = fakeSource({ names: ['QA403'], boundCard: 'QA403' });
  const synced = card('QA403', { match: ['Bench QA403'], label: '0 dBV', fromDevice: true });
  synced.input.ranges[0].displayLabel = '0 "dBV" real 0 dBFS or -9 dBV';
  const h = newSection({ cards: [synced], device: 'Bench QA403', cardSource: source });
  await h.section.refresh();
  assert.equal(rangeLabelShown(h, 0), '0 "dBV" real 0 dBFS or -9 dBV');   // it IS showing it

  labelField(h, 0).fire('blur');       // the operator clicked in to read it, and away again

  const row = storedRow(h, 'QA403');
  assert.equal(row.live.label, '0 dBV', 'the key the protocol decodes is untouched');
  assert.equal(row.persisted.label, '0 dBV', 'and nothing verbose reached the store');
  assert.equal(row.live.displayLabel, '0 "dBV" real 0 dBFS or -9 dBV',
    'the display text is still there - it is the KEY that must not move');
});

/** The same for the INDEPENDENT mirror, which gets the same binding. */
test('a device-owned INDEPENDENT row cannot commit through its right-hand field', async () => {
  const source = fakeSource({ names: ['QA403'], boundCard: 'QA403' });
  const synced = card('QA403', { match: ['Bench QA403'], label: '0 dBV', fromDevice: true });
  synced.input.channels = 'INDEPENDENT';
  synced.input.activeRangeRight = '0 dBV';
  synced.input.ranges[0].displayLabel = '0 "dBV" real 0 dBFS or -9 dBV';
  const h = newSection({ cards: [synced], device: 'Bench QA403', cardSource: source });
  await h.section.refresh();

  labelField(h, 0, true).fire('blur');

  const row = storedRow(h, 'QA403');
  assert.equal(row.live.label, '0 dBV');
  assert.equal(row.persisted.label, '0 dBV');
});

/** And the guard must not cost an ORDINARY row its rename - that field is the one place a
 *  range is renamed at all. */
test('an ordinary row still renames on blur', async () => {
  const h = newSection({ cards: [card('Focusrite', { label: 'default' })] });
  await h.section.refresh();

  const field = labelField(h, 0);
  field.val('lo gain');
  field.fire('blur');

  const row = storedRow(h, 'Focusrite');
  assert.equal(row.live.label, 'lo gain', 'the key follows what the operator typed');
  assert.equal(row.persisted.label, 'lo gain');
  assert.equal(row.live.displayLabel, null, 'an editable row never gains a display label');
});

// ---------------------------------------------------------------------------
// The settle tail that asks an analyzer for its card (PreferencesDialog.syncQa40xCardFor).
// Driven on a hand-made `this`: what is under test is WHICH sync the settled backend picks,
// and that leaving a bench drops its transient card.
// ---------------------------------------------------------------------------

/** The dialog surface `syncQa40xCardFor` touches, and nothing else - on the REAL prototype, so
 *  the shared helpers it calls (dropBenchCard) are the shipped ones. */
function syncHost({ backend, settings }) {
  const cleared = [];
  return Object.assign(Object.create(PreferencesDialog.prototype), {
    prefs: { backend: { get: () => backend } },
    deviceStore: { clearTransientProfiles: () => cleared.push(backend) },
    settingsManager: () => settings,
    inputCard: { refresh: () => {} },
    outputCard: { refresh: () => {} },
    refreshFsReadouts: () => {},
    cleared,
  });
}

const syncQa40xCardFor = PreferencesDialog.prototype.syncQa40xCardFor;

test('settling on a BENCH analyzer syncs the remote card and keeps the overlay', async () => {
  const calls = [];
  const host = syncHost({
    backend: 'net:QA40X',
    settings: {
      syncRemoteCard: async (b) => { calls.push(['remote', b]); return null; },
      syncLocalCard: async () => { calls.push(['local']); return null; },
    },
  });

  syncQa40xCardFor.call(host, 'net:QA40X');
  await new Promise((resolve) => setImmediate(resolve));

  assert.deepEqual(calls, [['remote', 'net:QA40X']]);
  assert.deepEqual(host.cleared, [], 'the bench card is what is being shown - not dropped');
});

/** S2: the LOCAL analyzer gets the same tail, so a first selection with an empty store has
 *  its ranges. And leaving the bench takes the transient card with it (S1). */
test('settling on the LOCAL analyzer syncs its own card and drops the bench overlay', async () => {
  const calls = [];
  const host = syncHost({
    backend: 'QA40X',
    settings: {
      syncRemoteCard: async () => { calls.push(['remote']); return null; },
      syncLocalCard: async () => { calls.push(['local']); return null; },
    },
  });

  syncQa40xCardFor.call(host, 'QA40X');
  await new Promise((resolve) => setImmediate(resolve));

  assert.deepEqual(calls, [['local']]);
  assert.deepEqual(host.cleared, ['QA40X'], 'no bench card is in force on a local selection');
});

/**
 * N4: the session ENDING drops the bench card too - not just a backend switch.
 *
 * The overlay is that analyzer's; a dead bench's attenuator positions must not stay on screen
 * until the operator happens to change backend. Driven through the real card section over a
 * real store, so what is asserted is the RANGES BLOCK, not a mock call.
 */
test('N4: losing the session drops the bench card and empties the ranges block', async () => {
  const source = fakeSource({ names: ['QA403'], boundCard: 'QA403' });
  const h = newSection({ device: 'Bench QA403', cardSource: source });
  const bench = card('QA403', { match: ['Bench QA403'], label: '0 dBV', fromDevice: true });
  bench.input.ranges.push(Object.assign(new DeviceRange(),
    { label: '6 dBV', fsLeft: 2, fsRight: 2 }));
  h.store.putTransientProfile(bench);                  // what syncRemoteCard publishes
  await h.section.refresh();
  assert.equal(h.ranges.children.length, 2, 'the bench analyzer\'s positions are on screen');

  const host = Object.assign(Object.create(PreferencesDialog.prototype), {
    prefs: { backend: { get: () => 'net:QA40X' } },
    deviceStore: h.store,
    inputCard: h.section,
    outputCard: null,
    buildBackendSelector: () => {},
    scan: async () => {},
  });
  await PreferencesDialog.prototype.onNetBackendsChanged.call(host, []);   // the session went

  assert.equal(h.store.findAudioDeviceProfile('QA403'), null, 'the overlay is gone');
  assert.equal(h.ranges.children.length, 0, 'and the ranges block emptied with it');
});

/** A live session that merely re-listed its devices keeps the card - dropping it there would
 *  blank the ranges mid-measurement on every ev.devices.changed. */
test('N4: an ev.devices.changed on a LIVE session keeps the bench card', async () => {
  const source = fakeSource({ names: ['QA403'], boundCard: 'QA403' });
  const h = newSection({ device: 'Bench QA403', cardSource: source });
  h.store.putTransientProfile(card('QA403', { match: ['Bench QA403'], label: '0 dBV', fromDevice: true }));

  const host = Object.assign(Object.create(PreferencesDialog.prototype), {
    prefs: { backend: { get: () => 'WEB_AUDIO' } },     // no re-scan on a local selection
    deviceStore: h.store,
    inputCard: h.section,
    outputCard: null,
    buildBackendSelector: () => {},
    scan: async () => {},
  });
  await PreferencesDialog.prototype.onNetBackendsChanged.call(host, [{ backend: 'QA40X' }]);

  assert.notEqual(h.store.findAudioDeviceProfile('QA403'), null,
    'the bench is still there - its card is what the ranges are drawn from');
});

test('a backend with no analyzer settings syncs nothing at all', async () => {
  const host = syncHost({ backend: 'WEB_AUDIO', settings: {} });   // web-audio has neither method

  assert.doesNotThrow(() => syncQa40xCardFor.call(host, 'WEB_AUDIO'));
  await new Promise((resolve) => setImmediate(resolve));

  assert.deepEqual(host.cleared, ['WEB_AUDIO'], 'though the overlay still goes');
});

test('bench: the "no card assigned" prompt is never raised for a bench device', async () => {
  const source = fakeSource({ names: ['QA403'] });
  const h = newSection({ device: 'Bench line in', cardSource: source });
  await h.section.onDeviceChanged();
  assert.deepEqual(h.prompts, []);
});

// ---------------------------------------------------------------------------
// Ruling D4: the copy-calibration offer (Java CardSection.offerCalibrationCopy)
// ---------------------------------------------------------------------------

/** A bench device with no calibration of its own, whose name this machine HAS a calibrated card
 *  for - the one situation the offer exists for. */
function offerable(extra = {}) {
  return newSection({
    cards: [card('Focusrite', { match: ['Bench line in'] })],
    device: 'Bench line in',
    cardSource: fakeSource({ names: ['QA403'], calibration: null }),
    ...extra,
  });
}

test('D4: a bench device with no calibration is offered this machine\'s card values - once',
  async () => {
    const h = offerable({ confirm: true });
    await h.section.onDeviceChanged();
    assert.deepEqual(h.prompts, [['preferences.audio.card.copyCalibration.title',
      'preferences.audio.card.copyCalibration.message']]);
    assert.deepEqual(h.section._cardSource.copied, [[true, 'Bench line in']]);

    // Asked and answered: switching device and back must not ask again this run.
    await h.section.onDeviceChanged();
    assert.equal(h.prompts.length, 1);
    assert.equal(h.section._cardSource.copied.length, 1);
  });

test('D4: declining copies nothing - and is still spent', async () => {
  const h = offerable({ confirm: false });
  await h.section.onDeviceChanged();
  assert.equal(h.prompts.length, 1);
  assert.deepEqual(h.section._cardSource.copied, [], 'don\'t copy silent - and don\'t copy on NO');
});

test('D4: a bench that would not store it says so; nothing else raises a dialog', async () => {
  const h = newSection({
    cards: [card('Focusrite', { match: ['Bench line in'] })],
    device: 'Bench line in',
    cardSource: fakeSource({ names: ['QA403'], calibration: null, copyTakes: false }),
    confirm: true,
  });
  await h.section.onDeviceChanged();
  // The CARD dialog's own title, not the server list's: this failure is about the card the
  // operator was just asked about (verify F16).
  assert.deepEqual(h.alerts, [['preferences.audio.card.copyCalibration.title',
    'preferences.audio.card.copyCalibration.failed']]);
});

test('D4: silent when the bench has its own, when it does not offer the device, and for a '
  + 'calibrationFromDevice card', async () => {
  const own = offerable();
  own.section._cardSource.calibration = () => ({ fsRmsLeft: 1, fsRmsRight: 1 });
  await own.section.onDeviceChanged();
  assert.deepEqual(own.prompts, [], 'the bench calibrated it itself - nothing to offer');

  const absent = offerable();
  absent.section._cardSource.calibration = () => undefined;
  await absent.section.onDeviceChanged();
  assert.deepEqual(absent.prompts, [], 'no such device on this bench - nothing to copy TO');

  // A QA40x's values are the analyzer's; neither side may overwrite them.
  const fromDevice = newSection({
    cards: [card('QA403', { match: ['Bench line in'], fromDevice: true })],
    device: 'Bench line in',
    cardSource: fakeSource({ names: ['QA403'], calibration: null }),
    confirm: true,
  });
  await fromDevice.section.onDeviceChanged();
  assert.deepEqual(fromDevice.prompts, []);

  // And nothing at all when this machine has no card for the name.
  const nothing = newSection({
    device: 'Bench line in', cardSource: fakeSource({ names: ['QA403'], calibration: null }),
  });
  await nothing.section.onDeviceChanged();
  assert.deepEqual(nothing.prompts, []);
});

/** S4: the WHOLE card goes up, and what actually landed is what the operator is told. */
test('S4: only the VALUES landing is said out loud - the card itself is silent', async () => {
  const whole = offerable({ confirm: true });
  await whole.section.onDeviceChanged();
  assert.deepEqual(whole.alerts, [], 'the card and its binding: nothing to report');

  const values = newSection({
    cards: [card('Focusrite', { match: ['Bench line in'] })],
    device: 'Bench line in',
    cardSource: fakeSource({ names: ['QA403'], calibration: null, outcome: 'VALUES_ONLY' }),
    confirm: true,
  });
  await values.section.onDeviceChanged();
  assert.deepEqual(values.alerts, [['preferences.audio.card.copyCalibration.title',
    'preferences.audio.card.copyCalibration.valuesOnly']],
  'the operator confirmed "copy card X" - being given only its numbers is not the same thing');
});

test('S4: a card that landed UNBOUND is reported as itself, not as a failure', async () => {
  const h = newSection({
    cards: [card('Focusrite', { match: ['Bench line in'] })],
    device: 'Bench line in',
    cardSource: fakeSource({ names: ['QA403'], calibration: null, outcome: 'CARD_UNBOUND' }),
    confirm: true,
  });

  await h.section.onDeviceChanged();

  assert.deepEqual(h.alerts, [['preferences.audio.card.copyCalibration.title',
    'preferences.audio.card.bindFailed']], 'the card IS on the bench - say so, no silent orphan');
});

/** S4: a transport failure is not an ANSWER, so it must not cost the operator the offer. */
test('S4: a failed copy may be offered again; a declined one may not', async () => {
  const failed = newSection({
    cards: [card('Focusrite', { match: ['Bench line in'] })],
    device: 'Bench line in',
    cardSource: fakeSource({ names: ['QA403'], calibration: null, outcome: 'NOTHING' }),
    confirm: true,
  });

  await failed.section.onDeviceChanged();
  await failed.section.onDeviceChanged();

  assert.equal(failed.prompts.length, 2, 'nothing reached the bench - the question stands');
  assert.equal(failed.section._cardSource.copied.length, 2);

  const declined = offerable({ confirm: false });
  await declined.section.onDeviceChanged();
  await declined.section.onDeviceChanged();
  assert.equal(declined.prompts.length, 1, 'a NO is an answer, and it is remembered for the run');
});

test('D4: a LOCAL selection never offers anything, whatever the store holds', async () => {
  const h = newSection({
    cards: [card('Focusrite')], device: 'Line In (Focusrite)',
    cardSource: fakeSource({ names: null }), confirm: true,
  });
  await h.section.onDeviceChanged();
  assert.deepEqual(h.prompts, [], 'a local device is not on anybody\'s bench');
  assert.deepEqual(h.section._cardSource.copied, []);
});

// ---------------------------------------------------------------------------
// The OK-time calibration test the dialog composes (Preferences.isUncalibrated(DeviceRef, ...))
// ---------------------------------------------------------------------------

test('the server\'s own calibration answers first; a null one falls through to the card store',
  async () => {
    const calls = [];
    const self = {
      deviceStore: { isUncalibrated: (name, input) => { calls.push([name, input]); return true; } },
      _cardSource: { calibration: () => ({ fsRmsLeft: 1.0, fsRmsRight: 1.0 }) },
    };
    assert.equal(PreferencesDialog.prototype._isUncalibrated.call(self, 'Bench line in', true), false);
    assert.deepEqual(calls, []);                     // the store was not even asked
    self._cardSource = { calibration: () => null };
    assert.equal(PreferencesDialog.prototype._isUncalibrated.call(self, 'Bench line in', true), true);
    assert.deepEqual(calls, [['Bench line in', true]]);
    self._cardSource = null;                         // a build with no net client
    assert.equal(PreferencesDialog.prototype._isUncalibrated.call(self, 'Line In', false), true);
    assert.deepEqual(calls[1], ['Line In', false]);
  });

// ---------------------------------------------------------------------------
// WHOSE full scale scales the samples (PreferencesDialog._applyDeviceProfile, the web half of
// Java Preferences.applyDeviceProfile(DeviceRef, boolean)).
//
// The invariant is the maintainer's: "server can have same device as client, but this are
// different exemplars and they can have different calibration!" - so the SAME model name at both
// ends must never cross. Driven on the real prototype over a real store, so what is pinned is the
// shipped precedence and the shipped write into the scalars.
// ---------------------------------------------------------------------------

/** A store holding one LOCAL card for `match`, calibrated to fsLeft / fsRight. */
function localCard(match, fsLeft, fsRight) {
  const prefs = new Preferences(true);
  const store = new DeviceProfileStore(prefs, {
    storageKey: 'phonalyser.devices.exemplar.' + (++keyCounter), catalog: EMPTY_CATALOG,
  });
  const p = card(match, { match: [match] });
  p.input.ranges[0].fsLeft = fsLeft;
  p.input.ranges[0].fsRight = fsRight;
  store.putAudioDeviceProfile(p);
  return { prefs, store };
}

/** The dialog surface _applyDeviceProfiles touches, and nothing else. */
function applyHost({ prefs, store, backend, device, calibration }) {
  return Object.assign(Object.create(PreferencesDialog.prototype), {
    prefs: Object.assign(prefs, { backend: { get: () => backend } }),
    deviceStore: store,
    _cardSource: { calibration: () => calibration },
    inputDeviceLabel: () => device,
    outputDeviceLabel: () => '',
  });
}

test('exemplars: a bench CB5 is scaled by the SERVER\'s full scale, never by this machine\'s CB5 card',
  async () => {
    const { prefs, store } = localCard('CB5', 2.0, 2.0);
    const host = applyHost({
      prefs, store, backend: 'net:WASAPI', device: 'CB5 Analog In',
      calibration: { fsRmsLeft: 7.0, fsRmsRight: 9.0 },      // what the bench stores for ITS CB5
    });

    PreferencesDialog.prototype._applyDeviceProfiles.call(host);

    assert.equal(prefs.getAdcFsVoltageRms('L'), 7.0, 'the bench exemplar\'s left full scale');
    assert.equal(prefs.getAdcFsVoltageRms('R'), 9.0, 'and its right - the local card is 2.0/2.0');
  });

test('exemplars: the same name on a LOCAL selection still resolves this machine\'s card', async () => {
  const { prefs, store } = localCard('CB5', 2.0, 2.0);
  const host = applyHost({
    prefs, store, backend: 'WEB_AUDIO', device: 'CB5 Analog In',
    // The seam answers for a bench alone; a local selection never asks it (app.js returns
    // undefined there) - answering here proves the branch, not the double, decides.
    calibration: { fsRmsLeft: 7.0, fsRmsRight: 9.0 },
  });

  PreferencesDialog.prototype._applyDeviceProfiles.call(host);

  assert.equal(prefs.getAdcFsVoltageRms('L'), 2.0, 'the LOCAL exemplar\'s card, not the bench\'s');
  assert.equal(prefs.getAdcFsVoltageRms('R'), 2.0);
});

test('exemplars: a bench device the server has no calibration for falls back to NOTHING', async () => {
  const { prefs, store } = localCard('CB5', 2.0, 2.0);
  prefs.setAdcFsVoltageRms(0.5);
  prefs.setAdcFsVoltageRmsRight(0.5);
  const host = applyHost({
    prefs, store, backend: 'net:WASAPI', device: 'CB5 Analog In', calibration: null,
  });

  PreferencesDialog.prototype._applyDeviceProfiles.call(host);

  assert.equal(prefs.getAdcFsVoltageRms('L'), 0.5,
    'the standing scalar keeps its value - this machine\'s CB5 card is a different box');
  assert.equal(prefs.getAdcFsVoltageRms('R'), 0.5);
});
