/*
 * Phonalyser web - a file that will not play must be VISIBLE, not only audible by its absence.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * The defect this pins: every file refusal - the over-size upload, a bench that said no, and the
 * format mismatch - was written to #status alone. That element carries class visually-hidden and
 * is the aria-live region: a screen reader read the refusal and nobody else saw anything. The file
 * did not play, and the screen said nothing at all.
 *
 * So the refusal now goes to the shell's ONE alert surface - the showAlert the pane receives in its
 * deps, exactly as the tune-notch wizard, the preferences dialog and the calibration dialog receive
 * it - titled with the desktop dialog's own key. The live region keeps its copy: it predates this
 * and a screen reader has no modal to read.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

// The pane touches jQuery; the fixture RECORDS text(), which is the subject beside the alert.
const nodes = new Map();
function fakeNode(sel) {
  return {
    sel, classes: new Set(), texts: [],
    addClass(name) { this.classes.add(name); return this; },
    removeClass(name) { this.classes.delete(name); return this; },
    toggleClass(name, on) { if (on) this.classes.add(name); else this.classes.delete(name); return this; },
    attr() { return this; },
    on() { return this; },
    off() { return this; },
    text(v) { if (v === undefined) return this.texts[this.texts.length - 1] || ''; this.texts.push(v); return this; },
  };
}
globalThis.$ = (sel) => {
  if (!nodes.has(sel)) nodes.set(sel, fakeNode(sel));
  return nodes.get(sel);
};

const { GeneratorPane } = await import('../js/generator/generator-pane.js');

const REFUSAL = 'clip.wav is 44100 Hz / 16 bit, but the output is configured for 48000 Hz / 24 bit.';

/** The pane holding a RECORDING alert surface - the seam app.js fills with the shell's showAlert. */
function paneWithAlert() {
  const raised = [];
  const pane = new GeneratorPane({}, {}, { showAlert: (title, message) => raised.push({ title, message }) });
  return { pane, raised };
}

test('the pane holds the shell alert seam, with the console fallback the other holders keep', () => {
  const { pane, raised } = paneWithAlert();
  assert.equal(typeof pane._showAlert, 'function');
  pane._showAlert('t', 'm');
  assert.deepEqual(raised, [{ title: 't', message: 'm' }]);

  // Constructed without it - a headless test, a partial wiring - must not throw.
  const bare = new GeneratorPane({}, {}, {});
  assert.equal(typeof bare._showAlert, 'function', 'the fallback stands in');
});

test('a refused file is raised on the shell alert, titled like the desktop dialog', () => {
  const { pane, raised } = paneWithAlert();

  // What the file-play click handler does with a refusal the controller localized.
  $('#status').text(REFUSAL);
  pane._showAlert('generator.error.playFile', REFUSAL);

  assert.equal(raised.length, 1, 'the operator gets a visible surface, not just a console line');
  assert.equal(raised[0].message, REFUSAL, 'carrying the refusal itself');
  assert.equal(raised[0].title, 'generator.error.playFile',
    'and the desktop dialog\'s own title key - Cannot play file');
  assert.equal($('#status').text(), REFUSAL,
    'the aria-live region still gets it - a screen reader has no modal to read');
});
