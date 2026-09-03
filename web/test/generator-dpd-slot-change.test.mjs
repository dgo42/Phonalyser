/*
 * Phonalyser web - what a .dpd load or clear does to the RUNNING generator.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Three rules, from the Java lane's dpdChanged:
 *
 *   a .dpd arriving for the PLAYING compensated form goes onto the running DDS
 *     live - never a stop+start, which would replay the parameters the generator
 *     was last STARTED with and silently undo every live amplitude / frequency
 *     edit made since;
 *   an EMPTIED compensated-sine slot ENDS the tone - that form IS its
 *     corrections, and without a table it would emit a plain sine under the
 *     compensated label, so a THD measured on it would be recorded as
 *     predistorted;
 *   an EMPTIED dual-tone slot only drops the intermod corrections - the slot is
 *     optional there, and the tone plays on.
 *
 * And the guard around all three: a slot that is not the form now playing, or a
 * stopped generator, changes nothing.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

// jQuery to the surface dpdChanged uses: the form combo answers a value, the play
// button records what the visuals sync did to it.
const nodes = new Map();
function fakeNode(sel) {
  return {
    sel, classes: new Set(), value: '',
    val(v) { if (v === undefined) return this.value; this.value = v; return this; },
    addClass(n) { this.classes.add(n); return this; },
    removeClass(n) { this.classes.delete(n); return this; },
    hasClass(n) { return this.classes.has(n); },
    attr() { return this; },
    text() { return this; },
    prop() { return this; },
    css() { return this; },
    closest() { return this; },
    find() { return this; },
    data() { return this; },
    removeData() { return this; },
    on() { return this; },
    each() { return this; },
  };
}
globalThis.$ = (sel) => {
  if (!nodes.has(sel)) nodes.set(sel, fakeNode(sel));
  return nodes.get(sel);
};

const { GeneratorPane } = await import('../js/generator/generator-pane.js');
const { GenSignalForm } = await import('../js/enums/gen-signal-form.js');

const DPD_TEXT = '# dpd\n1,0.01,0.0\n';

/** A minimal Property: the value plus the listeners a write notifies, which is the whole of
 *  what the pane's binding uses. */
function prop(initial) {
  let v = initial;
  const listeners = [];
  return {
    get: () => v,
    set: (nv) => { if (nv === v) return; v = nv; for (const l of listeners) l(nv); },
    addListener: (l) => listeners.push(l),
  };
}

/** A pane whose CORRECTIONS BINDING is wired, so the tests drive the same path the app does:
 *  a preference write, never a direct dpdChanged call. bind() proper would pull in the whole
 *  DOM world, so only bindCorrections runs - the method that owns the listeners. */
function paneWith({ playing, form, slot, dualSlot = null }) {
  const posted = [];
  let stopped = 0;
  const pane = Object.create(GeneratorPane.prototype);
  pane.engine = {
    generatorOn: playing,
    snapped: 1000,
    postGen: (msg) => posted.push(msg),
    stopGenerator: async () => { stopped++; },
  };
  const genDpd = prop(slot);
  const genDpdDual = prop(dualSlot);
  const genSignalForm = prop(form);
  pane.prefs = {
    genDpd, genDpdDual, genSignalForm,
    genDpdName: prop(null), genDpdDualName: prop(null),
    getGenDpd: (f) => (f === GenSignalForm.DUAL_TONE_COMP ? genDpdDual.get() : genDpd.get()),
    setGenDpd: (f, v) => (f === GenSignalForm.DUAL_TONE_COMP ? genDpdDual.set(v) : genDpd.set(v)),
    setGenDpdName: () => {},
    save: () => {},
  };
  pane.genCorrNames = {};
  pane.onAir = null;
  pane.setOnAir = (on) => { pane.onAir = on; };
  pane.refreshCorrectionsRow = () => {};
  pane._getField = () => null;
  $('#signalForm').val(form);
  $('#genPlay').addClass('playing');
  GeneratorPane.prototype.bindCorrections.call(pane);
  return { pane, prefs: pane.prefs, posted, stopped: () => stopped };
}

/** One microtask turn - dpdChanged is async and the listeners do not await it. */
const settle = () => new Promise((r) => setTimeout(r, 0));

test('a .dpd for the playing compensated form goes onto the running DDS live', async () => {
  const { prefs, posted, stopped } = paneWith({
    playing: true, form: GenSignalForm.SINE_COMP, slot: null,
  });
  prefs.setGenDpd(GenSignalForm.SINE_COMP, DPD_TEXT);   // the browse button's write
  await settle();

  assert.equal(stopped(), 0, 'the generator is NOT stopped for a live correction swap');
  assert.equal(posted.length, 1, 'exactly one message reaches the running generator');
  assert.equal(posted[0].dpdText, DPD_TEXT, 'carrying the .dpd text the sink parses');
  assert.equal(posted[0].dpdFrequency, 1000, 'at the effective frequency');
  assert.equal($('#genPlay').hasClass('playing'), true, 'and the tone plays on');
});

test('an emptied compensated-sine slot ends the tone and takes the visuals down', async () => {
  const { pane, prefs, posted, stopped } = paneWith({
    playing: true, form: GenSignalForm.SINE_COMP, slot: DPD_TEXT,
  });
  prefs.setGenDpd(GenSignalForm.SINE_COMP, null);   // the clear button's write
  await settle();

  assert.equal(stopped(), 1, 'the generator is stopped - a compensated sine IS its corrections');
  assert.equal(posted.length, 0, 'nothing is posted to a generator that is ending');
  assert.equal($('#genPlay').hasClass('playing'), false, 'the play button goes dark');
  assert.equal(pane.onAir, false, 'and the ON-AIR indicator follows');
});

test('an emptied dual-tone slot ends the tone too - the two compensated forms are equal', async () => {
  const { pane, prefs, posted, stopped } = paneWith({
    playing: true, form: GenSignalForm.DUAL_TONE_COMP, slot: null, dualSlot: DPD_TEXT,
  });
  prefs.setGenDpd(GenSignalForm.DUAL_TONE_COMP, null);
  await settle();

  assert.equal(stopped(), 1,
    'the dual slot is no more optional than the sine s - a plain two-tone pair under the '
    + 'compensated label has its intermodulation recorded as predistorted');
  assert.equal(posted.length, 0, 'nothing is posted to a generator that is ending');
  assert.equal($('#genPlay').hasClass('playing'), false, 'the play button goes dark');
  assert.equal(pane.onAir, false, 'and the ON-AIR indicator follows');
});

test('a slot that is not the playing form, and a stopped generator, change nothing', async () => {
  const other = paneWith({ playing: true, form: GenSignalForm.SINE, slot: null });
  other.prefs.setGenDpd(GenSignalForm.SINE_COMP, DPD_TEXT);
  await settle();
  assert.equal(other.posted.length, 0, 'a load into another form s slot reaches no running tone');
  assert.equal(other.stopped(), 0, 'and stops nothing');

  const idle = paneWith({ playing: false, form: GenSignalForm.SINE_COMP, slot: DPD_TEXT });
  idle.prefs.setGenDpd(GenSignalForm.SINE_COMP, null);
  await settle();
  assert.equal(idle.stopped(), 0, 'an emptied slot with nothing playing stops nothing');
  assert.equal(idle.posted.length, 0, 'and posts nothing');
});

/**
 * The wizard's Apply, after a finished run and a Save: it writes the .dpd TEXT into the slot and
 * THEN the form, and that pair alone must put the correction on the running tone - no direct
 * call, no restart. The generator is playing a plain SINE at that moment (the run measures the
 * raw DAC), so the slot write finds the old form and returns; the form write is what loads it.
 *
 * This is the bench defect: Apply used to write the file NAME into a slot that holds the TEXT,
 * and nothing carried either to the DDS.
 */
test('Apply after a finished run puts the saved correction on the running tone', async () => {
  const { prefs, posted, stopped } = paneWith({
    playing: true, form: GenSignalForm.SINE, slot: null,
  });

  prefs.setGenDpd(GenSignalForm.SINE_COMP, DPD_TEXT);   // Apply writes the slot FIRST
  await settle();
  assert.equal(posted.length, 0,
    'the slot write alone reaches nothing - the generator still plays the plain sine');

  prefs.genSignalForm.set(GenSignalForm.SINE_COMP);     // ...and the form SECOND
  await settle();

  assert.equal(stopped(), 0, 'Apply does not stop the tone');
  assert.equal(posted.length, 1, 'exactly one message carries the correction to the running DDS');
  assert.equal(posted[0].dpdText, DPD_TEXT, 'and it carries the saved TEXT, not a file name');
  assert.equal(posted[0].dpdFrequency, 1000, 'at the effective frequency');
});
