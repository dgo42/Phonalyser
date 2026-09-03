/*
 * Phonalyser web - the frequency-response L/R channel switch must re-derive what the
 * view shows FOR the newly selected channel.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * The unevenness readout (the response-flatness figure printed over the plot) and the
 * ideal-filter overlay anchor are CACHES held by FreqRespView: the readout is written
 * only by _recomputeUnevenness() out of the ACTIVE channel's result, the anchor only by
 * the overlay paint after _invalidateFilterAnchor() dropped it. Which channel is active
 * comes from the freqRespLeftVisible / freqRespRightVisible preferences, but neither
 * cache is derived again when those flip, so a channel switch that only repaints keeps
 * the PREVIOUS channel's numbers on screen - the readout then changes only when an
 * unrelated unevenness control is touched (each of those preferences does force a
 * recompute).
 *
 * The desktop's two L/R button listeners call invalidateFilterAnchor() and
 * recomputeUnevenness() before redraw() (gui/freqresp/FreqRespView.java:375-394). In the
 * web the L/R buttons are DOM elements owned by the FreqResp pane header and by the
 * tune-notch wizard, so the view exposes ONE entry point, setActiveChannel(left), that
 * both of them drive.
 *
 * Three drivers, one behaviour: the view's own entry point, the real FreqRespPane.bind()
 * click handlers, and the wizard's _selectChannel - each over a real FreqRespView holding
 * two channel results of deliberately different flatness (left +/-0.5 dB, right +/-5 dB).
 *
 * Run: node --test web/test/freqresp-channel-switch.test.mjs
 */
import test from 'node:test';
import assert from 'node:assert/strict';

// No globalThis.document: the view's RectZoom machinery early-outs without one, and the
// paint (the only other DOM consumer, through the CSS-variable font lookup) is replaced
// by a spy below.
globalThis.window = globalThis.window || { addEventListener: () => {} };

const { FreqRespView } = await import('../js/freqresp/freqresp-view.js');
const { FreqRespPane } = await import('../js/freqresp/freqresp-pane.js');
const { TuneNotchWizard } = await import('../js/freqresp/tune-notch-wizard.js');
const { UnevenMode } = await import('../js/enums/uneven-mode.js');

const SAMPLE_RATE = 48000;
const POINTS = 40;
const DIP_FROM = 10;
const DIP_TO = 15;          // exclusive - a 5-point plateau survives the 3-point despike
const LEFT_DIP_DB = -1.0;   // -> half-span 0.5 dB
const RIGHT_DIP_DB = -10.0; // -> half-span 5.0 dB
const LEFT_PLUS_DB = 0.5;
const RIGHT_PLUS_DB = 5.0;
const ANCHOR_SEED_DB = 3.25;

/** One channel result on a 20 Hz - 20 kHz log grid: flat at 0 dB with a plateau dip of
 *  dipDb, so the Mode-B (range) unevenness is exactly half the dip. */
function makeResult(channel, dipDb) {
  const freqs = new Float64Array(POINTS);
  const magLin = new Float64Array(POINTS);
  const phaseRad = new Float64Array(POINTS);
  for (let i = 0; i < POINTS; i++) {
    freqs[i] = 20.0 * Math.pow(10.0, 3.0 * i / (POINTS - 1));
    const db = (i >= DIP_FROM && i < DIP_TO) ? dipDb : 0.0;
    magLin[i] = Math.pow(10.0, db / 20.0);
  }
  return { channel, sampleRate: SAMPLE_RATE, freqs, magLin, phaseRad };
}

/** Preferences stand-in: every freqResp* key answers as a bound property (get/set/
 *  addListener), save() and current() as plain calls. */
function makePrefs(initial) {
  const vals = Object.assign({
    freqRespLeftVisible: true, freqRespRightVisible: false,
    freqRespApplyCalibration: false, freqRespNotchEnabled: false,
    freqRespUnevenMode: UnevenMode.RANGE, freqRespUnevenNotch: false,
    freqRespUnevenDb: 3.0, freqRespUnevenStartHz: 20.0, freqRespUnevenStopHz: 20000.0,
    freqRespShowFilter: false, freqRespPhaseVisible: false,
  }, initial || {});
  const cache = {};
  return new Proxy({}, { get(_t, key) {
    if (key === 'save') return () => {};
    if (key === 'current') return () => ({ inputSampleRate: SAMPLE_RATE });
    if (!(key in cache)) {
      cache[key] = {
        get: () => vals[key],
        set: (v) => { vals[key] = v; },
        addListener: () => {},
      };
    }
    return cache[key];
  } });
}

/** Canvas stand-in - the view stores the 2D context but never paints here. */
function makeCanvas() {
  return {
    width: 1200, height: 440, clientWidth: 1200, clientHeight: 440, style: {},
    getContext: () => ({}), addEventListener: () => {},
    getBoundingClientRect: () => ({ left: 0, top: 0, width: 1200, height: 440 }),
  };
}

/** jQuery stand-in that records every click handler by selector. */
function makeJQuery(handlers) {
  const nodes = new Map();
  const node = (sel) => {
    if (!nodes.has(sel)) {
      nodes.set(sel, {
        sel,
        addClass() { return this; },
        removeClass() { return this; },
        toggleClass() { return this; },
        text() { return this; },
        on(evt, fn) { if (evt === 'click') handlers.set(sel, fn); return this; },
        eq(i) { return node(`${sel}:eq(${i})`); },
      });
    }
    return nodes.get(sel);
  };
  return node;
}

/** A real FreqRespView carrying both channel results, its render() replaced by a spy that
 *  records the two caches AT PAINT TIME (so "recomputed before the repaint" is testable). */
function makeView(prefs) {
  const view = new FreqRespView(makeCanvas(), prefs,
    { getEntries: () => [], getDirect: () => null }, {});
  const painted = [];
  view.render = () => painted.push({
    plusDb: view._unevenPlusDb, anchor: view._filterAnchorCache,
  });
  view.setLeftResult(makeResult('L', LEFT_DIP_DB));
  view.setRightResult(makeResult('R', RIGHT_DIP_DB));
  return { view, painted };
}

/** Asserts the whole switch effect: the visibility preferences flipped, the unevenness
 *  readout was re-derived for the newly shown channel, the filter anchor was dropped, and
 *  both had already happened when the repaint ran. */
function assertSwitched(prefs, view, painted, toLeft) {
  const expectedPlusDb = toLeft ? LEFT_PLUS_DB : RIGHT_PLUS_DB;
  assert.equal(prefs.freqRespLeftVisible.get(), toLeft, 'L visibility follows the switch');
  assert.equal(prefs.freqRespRightVisible.get(), !toLeft, 'R visibility is its opposite');
  assert.ok(Math.abs(view._unevenPlusDb - expectedPlusDb) < 1e-9,
    `the readout follows the newly shown channel - ${expectedPlusDb} dB half-span (got ${view._unevenPlusDb})`);
  assert.ok(Number.isNaN(view._filterAnchorCache),
    'the ideal-filter overlay anchor is dropped, so it re-anchors on the new curve');
  assert.ok(painted.length >= 1, 'the switch repainted');
  const last = painted[painted.length - 1];
  assert.ok(Math.abs(last.plusDb - expectedPlusDb) < 1e-9,
    'the recompute happened BEFORE the repaint, not after it');
  assert.ok(Number.isNaN(last.anchor), 'and the anchor was already invalid when the paint ran');
}

test('setActiveChannel(false) re-derives the readout and the anchor for R', () => {
  const prefs = makePrefs();
  const { view, painted } = makeView(prefs);
  assert.ok(Math.abs(view._unevenPlusDb - LEFT_PLUS_DB) < 1e-9,
    `the left channel's half-span is 0.5 dB (got ${view._unevenPlusDb})`);
  view._filterAnchorCache = ANCHOR_SEED_DB;   // an anchor computed against the left curve
  painted.length = 0;

  view.setActiveChannel(false);

  assertSwitched(prefs, view, painted, false);
});

test('setActiveChannel(true) re-derives them back for L', () => {
  const prefs = makePrefs();
  const { view, painted } = makeView(prefs);
  view.setActiveChannel(false);
  view._filterAnchorCache = ANCHOR_SEED_DB;   // an anchor computed against the right curve
  view._unevenPlusDb = NaN;                   // only a real recompute can bring 0.5 back
  painted.length = 0;

  view.setActiveChannel(true);

  assertSwitched(prefs, view, painted, true);
});

test('the pane L/R header buttons drive the switch through the view', () => {
  const prefs = makePrefs();
  const { view, painted } = makeView(prefs);
  const handlers = new Map();
  const pane = Object.create(FreqRespPane.prototype);
  pane.prefs = prefs;
  pane.view = view;
  pane.$ = makeJQuery(handlers);
  pane.tabControl = { bind() {}, refreshRiaaEnable() {} };
  pane.controller = { runSweep() {} };
  pane.wizard = { open() {} };
  pane.bind();
  view._filterAnchorCache = ANCHOR_SEED_DB;
  painted.length = 0;

  handlers.get('#tab-fr .lr.r')();
  assertSwitched(prefs, view, painted, false);

  view._filterAnchorCache = ANCHOR_SEED_DB;
  view._unevenPlusDb = NaN;
  painted.length = 0;
  handlers.get('#tab-fr .lr.l')();
  assertSwitched(prefs, view, painted, true);
});

test('the tune-notch wizard L/R buttons drive the same switch on its embedded view', () => {
  const prefs = makePrefs();          // the wizard's DETACHED viewPrefs copy
  const { view, painted } = makeView(prefs);
  const wizard = Object.create(TuneNotchWizard.prototype);
  wizard.viewPrefs = prefs;
  wizard.view = view;
  wizard.$ = makeJQuery(new Map());
  view._filterAnchorCache = ANCHOR_SEED_DB;
  painted.length = 0;

  wizard._selectChannel(false);

  assertSwitched(prefs, view, painted, false);
});
