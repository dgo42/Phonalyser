/*
 * Phonalyser web - which button lights while a FILE plays.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Two rules, and they are each other's opposite:
 *
 *   the SMALL file button carries the active state while a file plays
 *   the BIG play button is the DDS tone's alone and stays dark
 *
 * A round that drove the big button from the file path got this exactly
 * backwards, so both halves are asserted together.
 *
 * The defect was NOT in setGenFileBtn - it was in what the click handler feeds
 * it. The handler ends with setGenFileBtn(engine.filePlaying), and on a LANE
 * backend (loopback, QA40x) the controller answered false while the file was
 * audibly playing, because the buffer-sink branch recorded no sink. So the two
 * links are driven in order here: the real controller over a real lane sink,
 * then the pane's sync over the controller's own answer. The pane's click
 * handler itself is not fired - bind() wires the whole preferences/property
 * world, which this harness would have to fake wholesale to reach one line.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

// jQuery, to the surface the pane's button sync uses.
const nodes = new Map();
function fakeNode(sel) {
  return {
    sel, classes: new Set(),
    addClass(n) { this.classes.add(n); return this; },
    removeClass(n) { this.classes.delete(n); return this; },
    toggleClass(n, on) { if (on) this.classes.add(n); else this.classes.delete(n); return this; },
    hasClass(n) { return this.classes.has(n); },
    attr() { return this; },
    text() { return this; },
    on() { return this; },
    off() { return this; },
  };
}
globalThis.$ = (sel) => {
  if (!nodes.has(sel)) nodes.set(sel, fakeNode(sel));
  return nodes.get(sel);
};

const { GeneratorController } = await import('../js/generator/generator-controller.js');
const { GeneratorPane } = await import('../js/generator/generator-pane.js');
const { Preferences } = await import('../js/store/preferences.js');
const { UNIT_FAMILIES, unitValue, baseToken, logToken }
  = await import('../js/widgets/unit-conversion.js');
const { ditherBitsForDbv } = await import('../js/dsp/dither-math.js');

const BITS = baseToken(UNIT_FAMILIES.DITHER);
const DBV = logToken(UNIT_FAMILIES.DITHER);

/** Puts the stored dither back where the suite found it, so one test's entered value cannot
 *  decide another's open. */
async function withStoredDither(entered, fsAmpl, body) {
  const prefs = Preferences.instance();
  const dither = prefs.genDither.get();
  const fs = prefs.getDacFsVoltageAmpl();
  prefs.setDacFsVoltageAmpl(fsAmpl);
  prefs.genDither.set(entered);
  try {
    return await body(prefs);
  } finally {
    prefs.genDither.set(dither);
    prefs.setDacFsVoltageAmpl(fs);
  }
}

const big = () => $('#genPlay');
const small = () => $('#genFilePlay');

/** A LANE backend's playback sink - no emittedHz, so it is local, and playBuffer
 *  is the entry the loopback and QA40x lanes answer on. */
function laneSink(opened = []) {
  return {
    sampleRate: 48000,
    async open(spec) { opened.push(spec); return 48000; },
    async playBuffer() {},
    async close() {},
  };
}

test('file play opens its lane with the CONFIGURED generator dither, like the tone does', async () => {
  // The old reasoning was that a file is already quantised. It is not, by the time it leaves:
  // the calibration scale multiplies every sample and the lane re-quantises the product at the
  // DAC depth - an undithered rounding, which is exactly what dither is for.
  const out = Preferences.instance().current();
  out.outputSampleRate = 48000;
  out.outputBitDepth = 24;
  const opened = [];
  const ctrl = new GeneratorController(Preferences.instance(), { openPlayback: () => laneSink(opened), status: () => {} });

  await withStoredDither(unitValue(1.0, BITS), 2.0,
    () => ctrl.playFileBuffer([new Float32Array(8)], 48000, false, { name: 'a.wav' }, 24));

  assert.equal(opened.length, 1, 'the lane was opened');
  assert.equal(opened[0].ditherBits, 1, 'carrying the configured dither, not a hard zero');
});

test('a dither stored as dBV reaches the file lane RESOLVED to bits at the open', async () => {
  // The stored dither is a value+unit pair; a dBV entry is a LEVEL and only becomes a depth once
  // it is resolved against the live DAC full scale. An open that carried the raw number - or the
  // unseeded snapshot's zero - would put a lane with a last-bit fallback at the session depth,
  // which sits below any measurement and reads on the bench as no dither at all, while a change
  // made DURING play (which does carry a resolved value) appears to work.
  const out = Preferences.instance().current();
  out.outputSampleRate = 48000;
  out.outputBitDepth = 24;
  const fsAmpl = 2.0;
  const opened = [];
  const ctrl = new GeneratorController(Preferences.instance(), { openPlayback: () => laneSink(opened), status: () => {} });

  await withStoredDither(unitValue(-100.0, DBV), fsAmpl,
    () => ctrl.playFileBuffer([new Float32Array(8)], 48000, false, { name: 'a.wav' }, 24));

  assert.equal(opened.length, 1, 'the lane was opened');
  const expected = ditherBitsForDbv(-100.0, fsAmpl);
  assert.ok(Math.abs(opened[0].ditherBits - expected) < 1e-9,
    `the open carries ${expected} bits, the resolution of -100 dBV against a ${fsAmpl} V peak `
    + `full scale, not the raw -100 and not a zero (got ${opened[0].ditherBits})`);
  assert.ok(opened[0].ditherBits > 0);
});

test('a dither change MID-FILE reaches the playing lane, as it does in Java', async () => {
  // Java pushes setDitherBits to the open file line as well as the tone lane. The web tone ride
  // is retuneGenerator, which returns early while the tone is off - and a file plays with the
  // tone off, so the file lane is pushed before that guard.
  const out = Preferences.instance().current();
  out.outputSampleRate = 48000;
  out.outputBitDepth = 24;
  const pushed = [];
  const lane = {
    sampleRate: 48000,
    port: { postMessage: (m) => pushed.push(m) },
    async open() { return 48000; },
    async playBuffer() {},
    async close() {},
  };
  const ctrl = new GeneratorController(Preferences.instance(), { openPlayback: () => lane, status: () => {} });
  await ctrl.playFileBuffer([new Float32Array(8)], 48000, false, { name: 'a.wav' }, 24);

  // The operator moves the dither field mid-file (the pane writes the entered value).
  await withStoredDither(unitValue(8, BITS), 2.0, () => ctrl.retuneGenerator());

  assert.equal(pushed.length, 1, 'the playing file lane was pushed, with the tone stopped');
  assert.equal(pushed[0].ditherBits, 8);
});

test('a file on a LANE backend really reports as playing - the input to the button', async () => {
  // The file must MATCH the configured output, or the format refusal - the other
  // half of this file player - stops it before any lane opens.
  const out = Preferences.instance().current();
  out.outputSampleRate = 48000;
  out.outputBitDepth = 24;
  const ctrl = new GeneratorController(Preferences.instance(), { openPlayback: () => laneSink(), status: () => {} });

  await ctrl.playFileBuffer([new Float32Array(8)], 48000, false, { name: 'a.wav' }, 24);

  assert.equal(ctrl.filePlaying, true,
    'the lane sink is recorded, or the pane is told nothing is playing and dims the button');
});

test('the file button carries the active state, the big play button does not', () => {
  const pane = new GeneratorPane({}, {}, { isGenRunning: () => false });

  pane.setGenFileBtn(true);

  assert.equal(small().hasClass('playing'), true,
    'the SMALL button is the one that shows a file playing');
  assert.equal(big().hasClass('playing'), false,
    'and the BIG button stays the DDS tone\'s alone');
});

test('stopping the file clears the small button and leaves the big one dark', () => {
  const pane = new GeneratorPane({}, {}, { isGenRunning: () => false });

  pane.setGenFileBtn(true);
  pane.setGenFileBtn(false);

  assert.equal(small().hasClass('playing'), false);
  assert.equal(big().hasClass('playing'), false);
});
