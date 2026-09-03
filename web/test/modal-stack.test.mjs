/*
 * Phonalyser web - a dialog opened on top of another dialog (maintainer bench B4).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * WHAT THIS PROVES AND WHAT IT CANNOT. Bootstrap gives every modal z-index 1055 and every
 * backdrop 1050 (scss/_variables.scss), so a second dialog's backdrop cannot cover the first
 * dialog - which stays lit and clickable while a dialog is supposedly modal over it. The rule
 * that fixes it is arithmetic over the number of modals already open, and the wiring is two
 * event listeners; both are driven here against real EventTarget elements.
 *
 * What node cannot show is Bootstrap's own timing (that its backdrop really is in the document
 * by the microtask after `show.bs.modal`, and that the browser paints the result) - that is the
 * maintainer's re-test, and it is why the wiring is kept to two listeners and one number.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

import { stackedZ, stackOverOpenModals, initialFocusTarget, MODAL_Z, BACKDROP_Z } from '../js/ui/modal-stack.js';

/** The smallest element the helper touches: events, a style object, focus(), and the controls
 *  inside it (what querySelectorAll answers for the focus-on-open half). */
class FakeElement extends EventTarget {
  constructor(className = '', controls = []) {
    super();
    this.className = className;
    this.style = {};
    this.focused = 0;
    this.controls = controls;
  }

  focus() { this.focused++; }

  querySelectorAll() { return this.controls; }

  fire(type) { this.dispatchEvent(new Event(type)); }
}

/** One control of a dialog - enabled and visible unless the test says otherwise (a real
 *  element's offsetParent is null exactly when it is not laid out). */
class FakeControl {
  constructor({ name, disabled = false, hidden = false, marked = false } = {}) {
    this.name = name;
    this.disabled = disabled;
    this.offsetParent = hidden ? null : {};
    this.marked = marked;
    this.focused = 0;
  }

  hasAttribute(attribute) { return attribute === 'data-initial-focus' && this.marked; }

  focus() { this.focused++; }
}

/** A document whose querySelectorAll answers from a list, by the two selectors used. */
function fakeDoc({ open = [], backdrops = [] } = {}) {
  return {
    querySelectorAll: (selector) => {
      if (selector === '.modal.show') return open;
      if (selector === '.modal-backdrop') return backdrops;
      throw new Error(`unexpected selector ${selector}`);
    },
  };
}

const flush = () => new Promise((resolve) => queueMicrotask(resolve));

test('nothing open underneath: Bootstrap\'s own layer is left exactly as it is', () => {
  assert.deepEqual(stackedZ(0), { modal: MODAL_Z, backdrop: BACKDROP_Z });
});

test('each level clears the dialog below it AND that dialog\'s backdrop', () => {
  const one = stackedZ(1);
  assert.ok(one.backdrop > MODAL_Z,
    'the whole point: the upper backdrop must cover the dialog underneath, which is at 1055 - '
    + 'Bootstrap\'s own 1050 is BELOW it, which is why the first dialog stayed clickable');
  assert.ok(one.modal > one.backdrop, 'and the upper dialog is above its own backdrop');
  assert.ok(one.modal < 1070,
    'while staying under $zindex-popover / $zindex-tooltip, so a tooltip inside it still shows');
  assert.deepEqual(stackedZ(2).modal - one.modal, one.modal - MODAL_Z, 'one even step per level');
});

test('opening OVER an open dialog raises this one and its own backdrop', async () => {
  const prefs = new FakeElement('modal show');
  const servers = new FakeElement('modal');
  const prefsBackdrop = new FakeElement('modal-backdrop');
  const ownBackdrop = new FakeElement('modal-backdrop');
  // Bootstrap appends the new backdrop at the END of the document during show(), so the last
  // one is this dialog's own.
  const doc = fakeDoc({ open: [prefs], backdrops: [prefsBackdrop, ownBackdrop] });
  stackOverOpenModals(servers, doc);

  servers.fire('show.bs.modal');
  await flush();

  const z = stackedZ(1);
  assert.equal(servers.style.zIndex, String(z.modal));
  assert.equal(ownBackdrop.style.zIndex, String(z.backdrop));
  assert.equal(prefsBackdrop.style.zIndex, undefined,
    'only THIS dialog\'s backdrop is moved - the one underneath keeps its own layer');
});

test('opening on its own changes nothing at all', async () => {
  const servers = new FakeElement('modal');
  const backdrop = new FakeElement('modal-backdrop');
  const doc = fakeDoc({ open: [], backdrops: [backdrop] });
  stackOverOpenModals(servers, doc);

  servers.fire('show.bs.modal');
  await flush();

  assert.equal(servers.style.zIndex, '', 'the ordinary single-dialog case is untouched');
  assert.equal(backdrop.style.zIndex, undefined);
});

test('closing gives the layer back and hands the focus to the dialog still open', () => {
  const prefs = new FakeElement('modal show');
  const servers = new FakeElement('modal');
  const doc = fakeDoc({ open: [prefs], backdrops: [] });
  stackOverOpenModals(servers, doc);
  servers.style.zIndex = '1065';

  servers.fire('hidden.bs.modal');

  assert.equal(servers.style.zIndex, '', 'a later open re-decides its level from what is open then');
  assert.equal(prefs.focused, 1,
    'Bootstrap\'s FocusTrap.deactivate() removes the document handlers wholesale, so the dialog '
    + 'underneath is left with no trap - the focus has to be handed back explicitly');
});

// ---------------------------------------------------------------------------
// B20 - the OPEN half: the focus goes INTO the dialog, not onto its root
// ---------------------------------------------------------------------------

test('B20: the dialog opens with the focus on the control it marks as its default', () => {
  const cancel = new FakeControl({ name: 'cancel' });
  const ok = new FakeControl({ name: 'ok', marked: true });
  const settings = new FakeElement('modal', [cancel, ok]);
  stackOverOpenModals(settings, fakeDoc({ open: [new FakeElement('modal show')] }));

  settings.fire('shown.bs.modal');

  assert.equal(ok.focused, 1,
    'the desktop opens on its default button; Bootstrap focuses the modal ROOT and stops there, '
    + 'so a dialog raised over Preferences opened on nothing the operator could type into');
  assert.equal(cancel.focused, 0, 'and not on the first control that happens to be in the markup');
  assert.equal(settings.focused, 0);
});

test('B20: the title-bar close button is chrome - it never takes the opening focus', () => {
  // It is the FIRST button in every dialog's markup, so without this every unmarked dialog would
  // open on the X. Escape already does what it does.
  const dialog = new FakeElement('modal', [new FakeControl({ name: 'first' })]);
  dialog.querySelectorAll = (selector) => {
    assert.ok(selector.includes('button:not(.btn-close)'), selector);
    return dialog.controls;
  };
  stackOverOpenModals(dialog, fakeDoc());

  dialog.fire('shown.bs.modal');

  assert.equal(dialog.controls[0].focused, 1);
});

test('B20: with nothing marked, the first usable control takes the focus', () => {
  const hidden = new FakeControl({ name: 'hidden', hidden: true });
  const disabled = new FakeControl({ name: 'disabled', disabled: true });
  const first = new FakeControl({ name: 'first' });
  const later = new FakeControl({ name: 'later' });
  const dialog = new FakeElement('modal', [hidden, disabled, first, later]);
  stackOverOpenModals(dialog, fakeDoc());

  dialog.fire('shown.bs.modal');

  assert.equal(first.focused, 1, 'DOM order, skipping what cannot take the focus at all');
  assert.equal(later.focused, 0);
  assert.equal(disabled.focused, 0);
  assert.equal(hidden.focused, 0);
});

test('B20: a marked control that is disabled is passed over like any other', () => {
  // The servers dialog marks Connect, which is disabled until a row is selected - focusing it
  // would leave the caret outside the dialog, which is the very thing this fixes.
  const connect = new FakeControl({ name: 'connect', disabled: true, marked: true });
  const host = new FakeControl({ name: 'host' });
  const dialog = new FakeElement('modal', [connect, host]);
  stackOverOpenModals(dialog, fakeDoc());

  dialog.fire('shown.bs.modal');

  assert.equal(connect.focused, 0);
  assert.equal(host.focused, 1);
  assert.equal(initialFocusTarget(dialog), host);
});

test('B20: a dialog with no control at all is left alone', () => {
  const dialog = new FakeElement('modal', []);
  stackOverOpenModals(dialog, fakeDoc());

  dialog.fire('shown.bs.modal');   // must not throw

  assert.equal(initialFocusTarget(dialog), null);
});

test('closing with nothing underneath focuses nothing', () => {
  const servers = new FakeElement('modal');
  const doc = fakeDoc({ open: [], backdrops: [] });
  stackOverOpenModals(servers, doc);

  servers.fire('hidden.bs.modal');   // must not throw on an empty list

  assert.equal(servers.style.zIndex, '');
});
