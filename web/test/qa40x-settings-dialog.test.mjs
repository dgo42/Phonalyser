/*
 * Phonalyser web - Qa40xSettingsDialog contract test.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Pins the two things a green build cannot, both straight out of
 * org.edgo.audio.measure.sound.qa40x.Qa40xSettingsDialog:
 *
 *   1. THE SEED. Java sets `accepted = i2sEnabled` while building, so every path that does not
 *      run the OK listener - Cancel, Escape, the backdrop, the title-bar close - hands the caller
 *      back the value the dialog opened with. Qa40xDeviceManager.openCustomPreferences writes the
 *      result straight into i2sEnabledEdit, so a dialog that returned `false` on Cancel would
 *      silently switch the front-panel I2S port OFF for anyone who opened the panel and backed out.
 *      "No callback fired" is not enough - the returned value has to BE the seed.
 *   2. THE ROW ORDER + COUNT. Eight rows, firmwareVersion -> serialNumber -> usbVoltage ->
 *      usbCurrent -> isoCurrent -> temperature -> capability -> capability2, each a READ-ONLY input so
 *      a serial can be selected and copied. Qa40xDeviceInfo's constructor takes serialNumber LAST
 *      while the panel shows it SECOND, so the per-row values here are all distinct: a row wired to
 *      its neighbour's field cannot pass.
 *
 * The dialog builds its own DOM, so a minimal fake document + Bootstrap Modal stand in (there is no
 * jsdom in this project, and every other unit test here is headless). The fake's hide() fires
 * hidden.bs.modal SYNCHRONOUSLY where Bootstrap fires it after the transition - irrelevant to the
 * contract, since open() only resolves from that event either way.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

// ---------------------------------------------------------------------------
// Minimal DOM: only the surface qa40x-settings-dialog.js actually touches.
// ---------------------------------------------------------------------------

function fakeElement(tag) {
  const listeners = new Map();
  const node = {
    tagName: String(tag).toUpperCase(),
    children: [],
    parentNode: null,
    className: '',
    textContent: '',
    style: {},
    appendChild(child) { node.children.push(child); child.parentNode = node; return child; },
    setAttribute(name, value) { node[name] = value; },
    hasAttribute(name) { return node[name] !== undefined; },
    addEventListener(type, fn) {
      const bucket = listeners.get(type);
      if (bucket) bucket.push(fn); else listeners.set(type, [fn]);
    },
    remove() {
      if (node.parentNode) {
        node.parentNode.children = node.parentNode.children.filter((c) => c !== node);
      }
      node.parentNode = null;
    },
    classList: {
      contains: (c) => node.className.split(/\s+/).includes(c),
      add: (c) => { if (!node.classList.contains(c)) node.className = `${node.className} ${c}`.trim(); },
      remove: (c) => { node.className = node.className.split(/\s+/).filter((x) => x !== '' && x !== c).join(' '); },
      toggle: (c, on) => { if (on) node.classList.add(c); else node.classList.remove(c); },
    },
    /** Test-side event dispatch (no real DOM event system here). */
    fire(type, event) { for (const fn of listeners.get(type) || []) fn(event || { preventDefault() {} }); },
  };
  return node;
}

class FakeModal {
  constructor(element) { this.element = element; this.shown = false; this.disposed = false; }
  show() { this.shown = true; }
  hide() { this.shown = false; this.element.fire('hidden.bs.modal'); }
  dispose() { this.disposed = true; }
}

// What modal-stack.js reads off the page when the dialog is shown: the modals already open and
// the backdrops in the document. Empty except in the stacking test, which is the only place the
// dialog is opened over another one.
const openModals = [];
const backdrops = [];
globalThis.document = {
  createElement: fakeElement,
  body: fakeElement('body'),
  querySelectorAll: (selector) => (selector === '.modal.show' ? openModals : backdrops),
};
globalThis.window = { bootstrap: { Modal: FakeModal } };
// Headless there are no bundles, so t() returns the key and warns once per key - the labels are
// asserted as keys below, and the warnings would bury the real failures.
console.warn = () => {};

const { Qa40xDeviceInfo } = await import('../js/qa40x/qa40x-device-info.js');
const { Qa40xSettingsDialog, deviceInfoRows } = await import('../js/qa40x/qa40x-settings-dialog.js');
const { stackedZ } = await import('../js/ui/modal-stack.js');

/** Distinct per-field values: any row reading its neighbour's field shows up immediately. */
const INFO = new Qa40xDeviceInfo(
  'fw-60', '4.980 V', '0.250 A', '0.120 A', '31.5 °C', '0x00000003', '0x0000000A', '0A1B2C3D');

/** Java build()'s row order - the label keys, top to bottom. */
const EXPECTED_KEYS = [
  'qa40x.settings.firmwareVersion',
  'qa40x.settings.serialNumber',
  'qa40x.settings.usbVoltage',
  'qa40x.settings.usbCurrent',
  'qa40x.settings.isoCurrent',
  'qa40x.settings.temperature',
  'qa40x.settings.capability',
  'qa40x.settings.capability2',
];

const EXPECTED_VALUES = [
  'fw-60', '0A1B2C3D', '4.980 V', '0.250 A', '0.120 A', '31.5 °C', '0x00000003', '0x0000000A',
];

const walk = (node, out = []) => { out.push(node); for (const c of node.children) walk(c, out); return out; };
const byId = (root, id) => walk(root).find((n) => n.id === id);
const tagged = (root, tag) => walk(root).filter((n) => n.tagName === tag);

/** Opens a dialog on its own fake parent; returns the pending promise plus the built root. */
function show(seed) {
  const parent = fakeElement('div');
  const dialog = new Qa40xSettingsDialog(parent, INFO);
  const pending = dialog.open(seed);
  return { parent, pending, root: parent.children[parent.children.length - 1] };
}

// ---------------------------------------------------------------------------
// 1. Rows: the pure spec
// ---------------------------------------------------------------------------

test('deviceInfoRows lists the eight device rows in Java build() order', () => {
  const rows = deviceInfoRows(INFO);
  assert.equal(rows.length, 8);
  assert.deepEqual(rows.map((r) => r.labelKey), EXPECTED_KEYS);
  assert.deepEqual(rows.map((r) => r.value), EXPECTED_VALUES);
});

// ---------------------------------------------------------------------------
// 2. Rows: what the dialog actually renders (a build() that ignored the spec would pass above)
// ---------------------------------------------------------------------------

test('the dialog renders the eight rows in order, read-only, label before value', async () => {
  const { pending, root } = show(false);
  // Every row is one label + one read-only text input; the toggle is a BUTTON, so the INPUT list is
  // exactly the value fields.
  const fields = tagged(root, 'INPUT');
  assert.equal(fields.length, 8);
  assert.deepEqual(fields.map((f) => f.value), EXPECTED_VALUES);
  assert.ok(fields.every((f) => f.readOnly === true && f.type === 'text'),
    'values must be read-only TEXT inputs so a serial or capability word can be copied');
  assert.deepEqual(tagged(root, 'LABEL').map((l) => l.textContent), EXPECTED_KEYS);
  // The note precedes the rows, the toggle follows them (Java build order).
  const body = walk(root).find((n) => n.className === 'modal-body');
  assert.equal(body.children[0].textContent, 'qa40x.settings.note');
  assert.equal(body.children[body.children.length - 1].id, 'qa40xSettingsI2s');

  byId(root, 'qa40xSettingsOk').fire('click');
  await pending;
});

// ---------------------------------------------------------------------------
// 3. The seed: everything that is not OK returns it unchanged
// ---------------------------------------------------------------------------

for (const seed of [true, false]) {
  test(`Cancel returns the seed unchanged (${seed})`, async () => {
    const { pending, root } = show(seed);
    walk(root).find((n) => n.textContent === 'common.cancel').fire('click');
    assert.equal(await pending, seed);
  });

  test(`Cancel returns the seed even after the toggle was flipped (${seed})`, async () => {
    const { pending, root } = show(seed);
    byId(root, 'qa40xSettingsI2s').fire('click');
    walk(root).find((n) => n.textContent === 'common.cancel').fire('click');
    assert.equal(await pending, seed);
  });

  test(`a dismiss that runs no button handler returns the seed (${seed})`, async () => {
    // Escape / backdrop / title-bar close: Bootstrap hides, hidden.bs.modal resolves.
    const { pending, root } = show(seed);
    root.fire('hidden.bs.modal');
    assert.equal(await pending, seed);
  });
}

// ---------------------------------------------------------------------------
// 4. OK returns the toggle state
// ---------------------------------------------------------------------------

test('OK returns the toggle state, and the toggle starts on the seed', async () => {
  for (const seed of [true, false]) {
    const untouched = show(seed);
    assert.equal(byId(untouched.root, 'qa40xSettingsI2s').classList.contains('active'), seed,
      'the toggle must open latched to the seed');
    byId(untouched.root, 'qa40xSettingsOk').fire('click');
    assert.equal(await untouched.pending, seed);

    const flipped = show(seed);
    byId(flipped.root, 'qa40xSettingsI2s').fire('click');
    assert.equal(byId(flipped.root, 'qa40xSettingsI2s').classList.contains('active'), !seed);
    byId(flipped.root, 'qa40xSettingsOk').fire('click');
    assert.equal(await flipped.pending, !seed);
  }
});

test('Enter accepts, like the SWT default button', async () => {
  const { pending, root } = show(false);
  byId(root, 'qa40xSettingsI2s').fire('click');
  root.fire('keydown', { key: 'Enter', preventDefault() {} });
  assert.equal(await pending, true);
});

test('a key other than Enter neither accepts nor closes', async () => {
  const { pending, root } = show(true);
  let settled = false;
  pending.then(() => { settled = true; });
  root.fire('keydown', { key: 'a', preventDefault() {} });
  await Promise.resolve();
  assert.equal(settled, false);
  walk(root).find((n) => n.textContent === 'common.cancel').fire('click');
  assert.equal(await pending, true);
});

// ---------------------------------------------------------------------------
// 5. Lifecycle: a fresh element per open, dropped on hide (Java disposes its Shell)
// ---------------------------------------------------------------------------

test('the element is mounted under the parent and removed again on hide', async () => {
  const { parent, pending, root } = show(true);
  assert.equal(parent.children.length, 1);
  assert.equal(root.id, 'qa40xSettingsModal');
  byId(root, 'qa40xSettingsOk').fire('click');
  await pending;
  assert.equal(parent.children.length, 0, 'a leaked element would stack on the next open');
});

// ---------------------------------------------------------------------------
// 6. Opened from inside Preferences (B19 / B20)
// ---------------------------------------------------------------------------

test('B19: opened over Preferences it takes a layer of its own - backdrop included', async () => {
  const prefs = fakeElement('div');
  prefs.className = 'modal show';
  openModals.push(prefs);
  const own = fakeElement('div');
  own.className = 'modal-backdrop';
  backdrops.push(own);
  const { pending, root } = show(false);

  root.fire('show.bs.modal');
  await new Promise((resolve) => queueMicrotask(resolve));

  const z = stackedZ(1);
  assert.equal(root.style.zIndex, String(z.modal));
  assert.equal(own.style.zIndex, String(z.backdrop),
    'Bootstrap\'s backdrop sits at 1050, BELOW the 1055 of the dialog underneath - which is why '
    + 'Preferences stayed lit and clickable behind this one');
  assert.equal(byId(root, 'qa40xSettingsOk').hasAttribute('data-initial-focus'), true,
    'and the focus opens on OK, which is Java\'s default button (B20)');

  byId(root, 'qa40xSettingsOk').fire('click');
  await pending;
  openModals.length = 0;
  backdrops.length = 0;
});

test('a null parent mounts on document.body', async () => {
  const before = document.body.children.length;
  const dialog = new Qa40xSettingsDialog(null, INFO);
  const pending = dialog.open(false);
  assert.equal(document.body.children.length, before + 1);
  byId(document.body.children[before], 'qa40xSettingsOk').fire('click');
  await pending;
  assert.equal(document.body.children.length, before);
});
