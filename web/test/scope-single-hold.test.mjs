/*
 * Phonalyser web - ScopeView SINGLE-mode hold-on-switch contract test (C19).
 * Run with `node web/test/scope-single-hold.test.mjs`.
 *
 * Reproduces the C19 regression: switching the trigger mode Auto -> SINGLE used
 * to blank the canvas because there was no captured single frame and no trigger,
 * so the armed-no-capture branch returned drawing nothing. The fix snapshots the
 * last live frame every paint (_snapshotFrame) and, on entering SINGLE, seeds
 * _singleHeld/_frame from it so _drawHeldFrame repaints the last trace with all
 * overlays instead of blanking.
 *
 * Headless: stubs a Canvas + 2D context (every drawing call is a no-op that also
 * tallies how much was drawn) and a Preferences proxy, then drives ScopeView.render
 * across the mode switch and asserts the held-frame path runs and paints.
 */
// The ScopeView constructor registers window-level drag listeners; provide a
// headless stub before importing so the module loads outside a browser.
globalThis.window = globalThis.window || { addEventListener: () => {} };
globalThis.performance = globalThis.performance || { now: () => Date.now() };

const { ScopeView } = await import('../js/scope/scope-view.js');

let passed = 0, failed = 0;
function ok(cond, msg) { if (cond) { passed++; } else { failed++; console.error('FAIL:', msg); } }

// --- 2D context stub: every method is a no-op; count the path-stroke / fill /
// drawn primitives so we can tell a real trace was painted vs. a blank canvas. ---
function makeCtx() {
  const ctx = { _drawOps: 0, canvas: null };
  const noop = () => {};
  for (const m of ['save', 'restore', 'beginPath', 'closePath', 'moveTo', 'lineTo',
                   'arc', 'rect', 'setLineDash', 'fillText', 'clearRect', 'setTransform']) ctx[m] = noop;
  ctx.stroke = () => { ctx._drawOps++; };
  ctx.fill = () => { ctx._drawOps++; };
  ctx.fillRect = () => { ctx._drawOps++; };
  ctx.strokeRect = () => { ctx._drawOps++; };
  ctx.measureText = () => ({ width: 10 });
  return ctx;
}

function makeCanvas() {
  const ctx = makeCtx();
  const canvas = {
    width: 1200, height: 240, clientWidth: 1200, clientHeight: 240,
    style: {}, title: '',
    getContext: () => ctx,
    addEventListener: () => {},
    getBoundingClientRect: () => ({ left: 0, top: 0, width: 1200, height: 240 }),
  };
  ctx.canvas = canvas;
  canvas._ctx = ctx;
  return canvas;
}

// --- Preferences proxy: each accessed property is a {get,set} backed by a map,
// pre-seeded with the values render() reads. Unknown keys default to a benign
// value so the test stays compact and resilient to incidental getters. ---
function makePrefs(initial) {
  const vals = Object.assign({
    oscLeftOffsetFrac: 0.5, oscRightOffsetFrac: 0.5,
    oscTriggerPositionFrac: 0.5, oscTriggerLevelFrac: 0.5,
    oscLeftVoltsPerDiv: 0.1, oscRightVoltsPerDiv: 0.1,
    oscLeftAcMode: false, oscRightAcMode: false,
    oscLeftChannelEnabled: true, oscRightChannelEnabled: false,
    oscLeftSincInterpEnabled: false, oscRightSincInterpEnabled: false,
    oscLeftChannelColor: 0x00d7ff, oscRightChannelColor: 0xffd700,
    oscLeftLpf: 'NONE', oscRightLpf: 'NONE',
    oscLeftMainsSuppression: 'NONE', oscRightMainsSuppression: 'NONE',
    oscTriggerChannel: 'L', oscTriggerMode: 'AUTO', oscTriggerEdge: 'RISE',
    oscTriggerHysteresisEnabled: false, oscTriggerHysteresisDiv: 0.5,
    oscShowReconstructedBeat: false, oscShowMeasurementTable: false,
    oscMeasurementChannel: 'L', oscMeasurementAverageSeconds: 1.0,
    oscTimePerDiv: 1e-4, oscLineWidth: 2.0, oscDotDiameter: 5,
    adcFsVoltageRms: 1.0,
  }, initial || {});
  const cache = {};
  const handler = {
    get(_t, key) {
      if (key === 'save') return () => {};
      // Per-channel ADC peak-volts method (Preferences.getAdcPeakVolts): the view reads
      // each channel's own full-scale; RIGHT falls back to LEFT (LINKED) when unset.
      if (key === 'getAdcPeakVolts') {
        return (ch) => (ch === 'R' && vals.adcFsVoltageRmsRight != null
          ? vals.adcFsVoltageRmsRight : vals.adcFsVoltageRms) * Math.SQRT2;
      }
      if (!(key in cache)) {
        cache[key] = {
          get: () => vals[key],
          set: (v) => { vals[key] = v; },
          addListener: () => {},
        };
      }
      return cache[key];
    },
  };
  return new Proxy({}, handler);
}

const SR = 48000;
const N = 4096;

// A clean sine on the L channel so the AUTO render finds a trigger and runs the
// full path (populating the snapshot frame).
function sineBuf(freq) {
  const b = new Float32Array(N);
  for (let i = 0; i < N; i++) b[i] = 0.5 * Math.sin(2 * Math.PI * freq * i / SR);
  return b;
}

// ===== Test 1: Auto frame populates the snapshot; Auto->SINGLE holds, not blank =====
{
  const canvas = makeCanvas();
  const prefs = makePrefs();
  const view = new ScopeView(canvas, { prefs });

  // Spy on the held-frame path.
  let heldCalls = 0;
  const realHeld = view._drawHeldFrame.bind(view);
  view._drawHeldFrame = (...a) => { heldCalls++; return realHeld(...a); };

  const buf = sineBuf(1000);
  // tDiv small enough that the trigger search range is non-empty (window << buffer).
  const info = { inRate: SR, available: N, period: SR / 1000, peakVolts: 1.0,
                 bufL: buf, bufR: buf, dualTone: false, f1Hz: 0, f2Hz: 0 };

  // --- AUTO paint: full path runs, snapshot frame captured. ---
  view.render(buf, info);
  ok(view._frame != null, 'AUTO render snapshots a live frame into _frame');
  ok(canvas._ctx._drawOps > 0, 'AUTO render actually paints the canvas');

  // --- Switch to SINGLE. The switch paint disarms (Java drawWaveforms resets
  // singleArmed on a mode change) and SEEDS the hold from the last live frame.
  // Force NO trigger from here on (window >= buffer => empty search range). ---
  prefs.oscTriggerMode.set('SINGLE');
  prefs.oscTimePerDiv.set(N / SR);   // windowSamples = N -> trigTo <= trigFrom -> no trigger

  const opsAtSwitch = canvas._ctx._drawOps;
  const heldAtSwitch = heldCalls;
  view.render(buf, info);

  ok(view._singleHeld === true, 'entering SINGLE seeds _singleHeld from the live frame');
  ok(view._frame != null, 'the seeded held frame survives the Auto->SINGLE switch');
  ok(heldCalls === heldAtSwitch + 1, 'the SINGLE switch paint draws the HELD frame (not blank)');
  ok(canvas._ctx._drawOps > opsAtSwitch, 'SINGLE switch paint draws something (canvas not blank)');

  // --- Now arm and paint again: still no trigger, so it keeps holding the seeded
  // frame (the core C19 behaviour: armed-waiting shows the last trace, not blank). ---
  view.setSingleArmed(true);
  const opsArmed = canvas._ctx._drawOps;
  const heldArmed = heldCalls;
  view.render(buf, info);

  ok(view._singleArmed === true, 'SINGLE stays armed waiting for its trigger');
  ok(heldCalls === heldArmed + 1, 'armed-no-trigger draws the HELD frame (not blank)');
  ok(canvas._ctx._drawOps > opsArmed, 'armed-waiting paint is not blank');
}

// ===== Test 2: SINGLE armed with NEVER a prior frame stays blank (no regression) =====
{
  const canvas = makeCanvas();
  const prefs = makePrefs({ oscTriggerMode: 'SINGLE', oscTimePerDiv: N / SR });
  const view = new ScopeView(canvas, { prefs });
  view.setSingleArmed(true);

  const buf = sineBuf(1000);
  const info = { inRate: SR, available: N, period: SR / 1000, peakVolts: 1.0,
                 bufL: buf, bufR: buf, dualTone: false, f1Hz: 0, f2Hz: 0 };
  view.render(buf, info);
  ok(view._frame == null, 'armed SINGLE with no prior live frame holds nothing (stays blank)');
}

console.log(`\nScopeView SINGLE-hold: ${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
