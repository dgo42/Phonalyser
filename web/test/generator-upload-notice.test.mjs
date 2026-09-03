/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * The bench-upload notice, end to end over the real MessageBus (GeneratorController's upload span
 * -> Events.FILE_UPLOAD_STARTED / FINISHED -> GeneratorPane.openUploadNotice / closeUploadNotice).
 *
 * The web diverges from the desktop here on purpose: the notice is a MODAL carrying its own
 * Cancel, because nothing is playing yet while the bytes travel.
 *
 * What the pair has to guarantee, one test per link:
 *   the two names and values are the desktop's           (fileUpload.started / .finished)
 *   started raises the modal, finished takes it down     (the pane's subscriptions)
 *   a finished with no started changes nothing           (close is idempotent)
 *   a second started stacks no second handler            (open is idempotent)
 *   EVERY exit of the upload span publishes finished     (the controller's finally)
 *   Cancel stops the bench and ends like a refusal       (the cooperative stop flag)
 * The last two are the point: a notice that outlives its transfer is worse than none, and a
 * cancel must reach the far end without reporting an error the operator did not cause.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

// The pane touches jQuery, the DOM and the Bootstrap modal; all three are stubbed to the
// surface it actually uses.
const nodes = new Map();
function fakeNode(sel) {
  return {
    sel, classes: new Set(), props: {}, handlers: new Map(),
    addClass(name) { this.classes.add(name); return this; },
    removeClass(name) { this.classes.delete(name); return this; },
    toggleClass(name, on) { if (on) this.classes.add(name); else this.classes.delete(name); return this; },
    prop(k, v) { if (v === undefined) return this.props[k]; this.props[k] = v; return this; },
    off(ev) { this.handlers.delete(ev); return this; },
    on(ev, fn) { this.handlers.set(ev, fn); return this; },
    attr() { return this; },
    text() { return this; },
    click(ev) { const h = this.handlers.get(ev); if (h) h(); },
  };
}
globalThis.$ = (sel) => {
  if (!nodes.has(sel)) nodes.set(sel, fakeNode(sel));
  return nodes.get(sel);
};

/** The modal element + the one Bootstrap instance the pane resolves from it. */
const modalEl = {
  listeners: new Map(),
  addEventListener(type, fn) { this.listeners.set(type, fn); },
  removeEventListener(type) { this.listeners.delete(type); },
  fire(type) { const fn = this.listeners.get(type); if (fn) fn(); },
};
let instance = null;
let shown = false;
const fakeModal = {
  show() { shown = true; },
  hide() { shown = false; },
};
globalThis.document = { getElementById: (id) => (id === 'genUploadModal' ? modalEl : null) };
globalThis.window = {
  bootstrap: {
    Modal: {
      getOrCreateInstance: () => { instance = instance || fakeModal; return instance; },
      getInstance: () => instance,
    },
  },
  addEventListener: () => {},
  removeEventListener: () => {},
};

const { MessageBus } = await import('../js/bus/message-bus.js');
const { Events } = await import('../js/bus/events.js');
const { GeneratorController } = await import('../js/generator/generator-controller.js');
const { GeneratorPane } = await import('../js/generator/generator-pane.js');

/** The pane, really constructed - its constructor is where the two subscriptions are armed, so a
 *  hand-made prototype object would test the test instead of the wiring. */
const pane = new GeneratorPane({}, {}, {});
const notice = () => shown;
const reset = () => { shown = false; instance = null; modalEl.listeners.clear(); };
const cancelBtn = () => $('#genUploadCancel');

/** A controller with the upload span intact and everything around it recorded: the end-of-session
 *  bookkeeping, the failure localization and the detached watch each have their own tests. */
function uploader({ throws = null } = {}) {
  const c = Object.create(GeneratorController.prototype);
  c.calls = [];
  c._endRemoteFile = async () => { c.calls.push('end'); };
  c._reportRemoteFileFailure = () => { c.calls.push('reported'); };
  // The watch is where playback has begun: what the notice looks like HERE is the whole question.
  c._watchRemoteFile = async () => { c.calls.push('watch'); c.noticeAtWatch = notice(); };
  const sink = {
    playFile: async () => {
      c.calls.push('playFile');
      if (throws) throw throws;
    },
  };
  return { c, sink };
}

/** Records the two events in the order they reach the bus. */
function watchBus() {
  const seen = [];
  const onStart = () => seen.push('started');
  const onEnd = () => seen.push('finished');
  MessageBus.instance().subscribe(Events.FILE_UPLOAD_STARTED, onStart);
  MessageBus.instance().subscribe(Events.FILE_UPLOAD_FINISHED, onEnd);
  return { seen, stop() {
    MessageBus.instance().unsubscribe(Events.FILE_UPLOAD_STARTED, onStart);
    MessageBus.instance().unsubscribe(Events.FILE_UPLOAD_FINISHED, onEnd);
  } };
}

test('the event names carry the desktop values', () => {
  assert.equal(Events.FILE_UPLOAD_STARTED, 'fileUpload.started');
  assert.equal(Events.FILE_UPLOAD_FINISHED, 'fileUpload.finished');
});

test('started raises the modal, finished takes it down', () => {
  reset();
  MessageBus.instance().publish(Events.FILE_UPLOAD_STARTED);
  assert.equal(notice(), true, 'the operator is told what the wait is');
  assert.equal(cancelBtn().prop('disabled'), false, 'and can end it');
  MessageBus.instance().publish(Events.FILE_UPLOAD_FINISHED);
  assert.equal(notice(), false, 'and it goes away with the transfer');
});

test('a finished with no started changes nothing', () => {
  reset();
  MessageBus.instance().publish(Events.FILE_UPLOAD_FINISHED);
  assert.equal(notice(), false);
  pane.closeUploadNotice();                   // and again, directly - close is idempotent
  assert.equal(notice(), false);
});

test('a second started stacks no second handler', () => {
  reset();
  MessageBus.instance().publish(Events.FILE_UPLOAD_STARTED);
  MessageBus.instance().publish(Events.FILE_UPLOAD_STARTED);
  assert.equal(cancelBtn().handlers.size, 1, 'one Cancel handler, however many starts');
  assert.equal(notice(), true);
  MessageBus.instance().publish(Events.FILE_UPLOAD_FINISHED);
  assert.equal(notice(), false, 'and ONE finished is enough to clear it');
});

test('a transfer that ends inside the fade-in still closes the notice', () => {
  reset();
  MessageBus.instance().publish(Events.FILE_UPLOAD_STARTED);
  MessageBus.instance().publish(Events.FILE_UPLOAD_FINISHED);   // hide ignored mid-transition
  shown = true;                                                 // as Bootstrap leaves it
  modalEl.fire('shown.bs.modal');
  assert.equal(notice(), false, 'the re-issued close catches it');
});

test('the upload span pairs the events on the success path, with the watch outside', async () => {
  reset();
  const w = watchBus();
  const { c, sink } = uploader();

  await GeneratorController.prototype._startRemoteFilePlayback.call(c, sink,
    { bytes: new Uint8Array([1, 2, 3]), name: 'tone.wav' }, false);

  assert.deepEqual(w.seen, ['started', 'finished'], 'up while the bytes move, down when commanded');
  assert.equal(notice(), false, 'the notice never sits over the playing track');
  // Only the first two entries are settled when this call returns: the session end rides on the
  // DETACHED chain behind the watch, which is deliberately not awaited here (nor by the caller).
  assert.deepEqual(c.calls.slice(0, 2), ['playFile', 'watch'], 'the watch runs after the span');
  // The binding rule: the modal exists only while the file is being uploaded. By the time
  // playback is commanded - which is what the watch waits on - it is already hidden.
  assert.equal(c.noticeAtWatch, false, 'the modal is gone the moment playback is commanded');
  w.stop();
});

test('a REFUSED upload still takes the notice down', async () => {
  reset();
  const w = watchBus();
  const { c, sink } = uploader({ throws: new Error('the bench said no') });

  await GeneratorController.prototype._startRemoteFilePlayback.call(c, sink,
    { bytes: new Uint8Array([1, 2, 3]), name: 'tone.wav' }, false);

  assert.deepEqual(w.seen, ['started', 'finished'], 'the failure exit closes it too');
  assert.equal(notice(), false, 'no notice is left standing over a transfer that is over');
  assert.deepEqual(c.calls, ['playFile', 'reported', 'end'], 'and the refusal is still reported');
  w.stop();
});

test('a file with no bytes never opens a notice at all', async () => {
  reset();
  const w = watchBus();
  const { c } = uploader();
  c.filePlayError = null;

  await GeneratorController.prototype._startRemoteFilePlayback.call(c, null, null, false);

  assert.deepEqual(w.seen, [], 'nothing was uploaded, so nothing was announced');
  assert.equal(notice(), false);
  w.stop();
});

test('Cancel stops the bench, closes the notice and reports no error', async () => {
  reset();
  const w = watchBus();
  const stops = [];
  let release = null;
  // The real stopFile and the real end-of-session run here: what is under test is that the
  // button reaches the far end through them.
  const c = Object.create(GeneratorController.prototype);
  c.filePlayError = null;
  c._watchRemoteFile = async () => {};
  const sink = {
    playFile: async () => { await new Promise((r) => { release = r; }); },
    stopFile: async () => { stops.push('stopFile'); },
    close: async () => {},
  };
  const previousEngine = pane.engine;
  pane.engine = c;                       // the pane's Cancel calls engine.stopFile()

  const run = GeneratorController.prototype._startRemoteFilePlayback.call(c, sink,
    { bytes: new Uint8Array([1, 2, 3]), name: 'tone.wav' }, false);
  assert.equal(notice(), true, 'the modal is up while the bytes move');

  cancelBtn().click('click.genUpload');
  assert.equal(cancelBtn().prop('disabled'), true, 'the button goes dead - one shot');
  assert.equal(c._remoteFile.stopped, true, 'the cooperative flag is set');
  assert.equal(notice(), true, 'and the notice stays up until the span itself ends');

  release();                             // the command in flight returns, as the wire does
  await run;
  await new Promise((r) => setTimeout(r, 0));   // let the detached tail run

  assert.deepEqual(w.seen, ['started', 'finished'], 'a cancel ends like any other outcome');
  assert.equal(notice(), false, 'the notice comes down with the finished event');
  assert.equal(c.filePlayError, null, 'a cancel is not a failure and reports none');
  assert.deepEqual(stops, ['stopFile'], 'the bench is told to stop what it was handed');
  assert.equal(c.filePlaying, false, 'and nothing is left claiming to play');
  pane.engine = previousEngine;
  w.stop();
});
