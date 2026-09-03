/*
 * Phonalyser web - a finished frequency-response measurement must be saveable to a .frc.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Two stacked web-only defects kept the Save-to button dead after a sweep:
 *
 * (1) ORDERING. captureAndDeconvolve publishes FREQRESP_MEASUREMENT_STOPPED from its finally,
 *     and the bus dispatches synchronously, so STOPPED reaches the pane BEFORE runSweep's
 *     continuation puts the result on the view. The pane's only measurement-lifecycle re-gate
 *     was that STOPPED subscription, so the enable cascade ran against a just-cleared view and
 *     every result-dependent control (RIAA Compare, the Filters Compare, Save-to) stayed
 *     disabled until an unrelated control happened to re-run the cascade. The desktop orders it
 *     the other way: the worker publishes FREQRESP_RESULT_AVAILABLE (FreqRespAnalyzerWorker.java:191)
 *     before its finally queues STOPPED (:197-202).
 *
 * (2) AN OBSOLETE GATE. The web gated Save-to on a "worth saving as a calibration" test (this
 *     session's own measurement, driven above 0.5 V RMS). The desktop's Save button is
 *     deliberately ALWAYS enabled (FreqRespTabControl.java:2111-2117) and its save path refuses
 *     only when both channels are null (:2120-2128) - which the web already does in
 *     saveMeasurement. So a sweep driven at a normal low bench level, and any response loaded
 *     from a .frc, could not be written out at all.
 *
 * Both tests drive the REAL runSweep / pane subscriptions / enable cascade over a REAL
 * FreqRespView; only the capture itself, the tab control's DOM build and jQuery are stubs. The
 * MessageBus is process-wide, so each harness keeps its own view + button state and a previous
 * harness's still-subscribed handlers only touch their own stubs.
 *
 * Run: node --test web/test/freqresp-save-gate.test.mjs
 */
import test from 'node:test';
import assert from 'node:assert/strict';

globalThis.window = globalThis.window || { addEventListener: () => {} };

const { FreqRespView } = await import('../js/freqresp/freqresp-view.js');
const { FreqRespPane } = await import('../js/freqresp/freqresp-pane.js');
const { FreqRespTabControl } = await import('../js/freqresp/freqresp-tab-control.js');
const { FreqRespController } = await import('../js/freqresp/freqresp-controller.js');
const { makeFreqRespResult, makeStereoResult } = await import('../js/freqresp/stereo-result.js');
const { MessageBus } = await import('../js/bus/message-bus.js');
const { Events } = await import('../js/bus/events.js');

const SAMPLE_RATE = 48000;
const POINTS = 32;
/** Below the deleted gate's 0.5 V RMS floor - a perfectly normal bench drive level. */
const LOW_DRIVE_VRMS = 0.2;
const SAVE_BTN = '#frSaveBtn';
const RIAA_COMPARE = '#frRiaaCompare';

function makeCurve(channel, sourceFilePath, amplitudeVrms) {
  const freqs = new Float64Array(POINTS);
  const magLin = new Float64Array(POINTS);
  const phaseRad = new Float64Array(POINTS);
  for (let i = 0; i < POINTS; i++) {
    freqs[i] = 20.0 * Math.pow(10.0, 3.0 * i / (POINTS - 1));
    magLin[i] = 1.0;
  }
  const params = {
    startHz: freqs[0], stopHz: freqs[POINTS - 1], sweepPoints: POINTS,
    durationSec: 1.0, leadInSec: 0.1, amplitudeVrms,
  };
  return makeFreqRespResult(channel, SAMPLE_RATE, freqs, magLin, phaseRad,
    params, sourceFilePath, sourceFilePath != null);
}

/** Preferences stand-in: every key answers as a bound property; save()/current() are plain calls. */
function makePrefs() {
  const vals = {
    freqRespLeftVisible: true, freqRespRightVisible: false,
    freqRespShowRiaa: false, freqRespReverseRiaa: false,
    freqRespIecAmendment: false, freqRespCompareMode: false,
    freqRespApplyCalibration: false, freqRespUnevenMode: 'OFF',
    freqRespShowFilter: false, freqRespPhaseVisible: false,
  };
  const cache = {};
  return new Proxy({}, { get(_t, key) {
    if (key === 'save') return () => {};
    if (key === 'current') return () => ({ inputSampleRate: SAMPLE_RATE });
    if (!(key in cache)) {
      cache[key] = { get: () => vals[key], set: (v) => { vals[key] = v; }, addListener: () => {} };
    }
    return cache[key];
  } });
}

function makeCanvas() {
  return {
    width: 1200, height: 440, clientWidth: 1200, clientHeight: 440, style: {},
    getContext: () => ({}), addEventListener: () => {},
    getBoundingClientRect: () => ({ left: 0, top: 0, width: 1200, height: 440 }),
  };
}

/**
 * jQuery stand-in over a per-selector state map. Every control starts ENABLED, as the markup
 * does - index.html carries no disabled attribute on the Save-to button - so an assertion that
 * a control is enabled fails exactly when the code under test disabled it.
 * #frRiaa answers checked (the RIAA Show gate); #frFilterShow answers as absent, which is the
 * documented early-out of refreshFilterEnable.
 */
function makeJQuery(state) {
  const nodes = new Map();
  const node = (sel) => {
    if (!nodes.has(sel)) {
      if (!(sel in state)) state[sel] = { disabled: false };
      nodes.set(sel, {
        sel,
        get length() { return sel === '#frFilterShow' ? 0 : 1; },
        is(what) { return what === ':checked' && sel === '#frRiaa'; },
        prop(name, value) {
          if (value === undefined) return state[sel][name];
          state[sel][name] = value; return this;
        },
        addClass() { return this; }, removeClass() { return this; }, toggleClass() { return this; },
        text() { return this; }, html() { return this; }, val() { return this; },
        on() { return this; }, eq(i) { return node(`${sel}:eq(${i})`); },
      });
    }
    return nodes.get(sel);
  };
  return node;
}

/** The real pane + tab control + controller over a real view; the capture is the only stub. */
function harness() {
  const prefs = makePrefs();
  // Seeded with the markup's own starting state (index.html declares neither control disabled),
  // so an assertion reads "nothing disabled it", not "nothing asked about it".
  const state = { [SAVE_BTN]: { disabled: false }, [RIAA_COMPARE]: { disabled: false } };
  const $ = makeJQuery(state);
  const view = new FreqRespView(makeCanvas(), prefs,
    { getEntries: () => [], getDirect: () => null }, {});
  view.render = () => {};

  const tabControl = Object.create(FreqRespTabControl.prototype);
  tabControl.prefs = prefs;
  tabControl.view = view;
  tabControl.$ = $;
  tabControl._filterFields = {};
  tabControl.bind = () => {};   // the tile-tab / DOM build is not what these tests drive

  const controller = Object.create(FreqRespController.prototype);
  controller.$ = $;
  controller.view = view;
  controller.running = false;
  controller.prefs = prefs;

  const pane = Object.create(FreqRespPane.prototype);
  pane.prefs = prefs;
  pane.view = view;
  pane.$ = $;
  pane.tabControl = tabControl;
  pane.controller = controller;
  pane.wizard = { open() {} };
  pane.bind();

  return { prefs, view, tabControl, controller, state };
}

test('a sweep driven below 0.5 V RMS leaves Save-to and RIAA Compare enabled', async () => {
  const { view, controller, state } = harness();
  const stereo = makeStereoResult(
    makeCurve('L', null, LOW_DRIVE_VRMS), makeCurve('R', null, LOW_DRIVE_VRMS), 0.0);
  // The real capture's finally publishes STOPPED before the promise resolves - the exact
  // ordering that put the enable cascade in front of the result.
  controller.captureAndDeconvolve = async () => {
    MessageBus.instance().publish(Events.FREQRESP_MEASUREMENT_STOPPED);
    return stereo;
  };

  await controller.runSweep();

  assert.ok(view.hasAnyResult(), 'the sweep left its result on the view');
  assert.equal(state[SAVE_BTN].disabled, false,
    'the Save-to button is enabled after a finished sweep, whatever the drive level');
  assert.equal(state[RIAA_COMPARE].disabled, false,
    'the RIAA Compare gate saw the result too - the cascade ran after the view was populated');
});

test('a response loaded from a .frc leaves Save-to enabled', () => {
  const { view, tabControl, state } = harness();
  view.setLeftResult(makeCurve('L', 'bench-loopback.frc', 0.0));
  view.setRightResult(makeCurve('R', 'bench-loopback.frc', 0.0));

  tabControl.refreshRiaaEnable();   // the cascade every pane entry point runs

  assert.equal(state[SAVE_BTN].disabled, false,
    'a loaded measurement can be written back out - the save path refuses only when both channels are null');
});
