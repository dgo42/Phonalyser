/*
 * Phonalyser web - ScopeView freeze-on-stop held-frame contract test.
 * Run with `node web/test/scope-freeze-beat.test.mjs`.
 *
 * Reproduces the "beat disappears when stopping in AUTO" regression: stopping the
 * recording used to leave stale pixels because nothing replayed the last frame.
 * The fix keeps the last live frame's snapshot (which carries the reconstructed-beat
 * overlay, captured at draw time) and replays it through _drawHeldFrame via the
 * standalone renderFrozen() entry the pane calls after a stop / on a resize.
 *
 * Headless: stubs a Canvas + 2D context (drawing calls tally how much was drawn) and
 * a Preferences proxy, then:
 *   1. renders a live DUAL-tone AUTO frame with the reconstructed beat enabled,
 *   2. freeze()s + renderFrozen()s (the stop),
 * and asserts the held frame carries the beat polyline and the frozen replay paints it.
 */
globalThis.window = globalThis.window || { addEventListener: () => {} };
globalThis.performance = globalThis.performance || { now: () => Date.now() };

const { ScopeView } = await import('../js/scope/scope-view.js');

let passed = 0, failed = 0;
function ok(cond, msg) { if (cond) { passed++; } else { failed++; console.error('FAIL:', msg); } }

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
    oscLeftResidualEnabled: false, oscRightResidualEnabled: false,
    oscTriggerChannel: 'L', oscTriggerMode: 'AUTO', oscTriggerEdge: 'RISE',
    oscTriggerHysteresisEnabled: false, oscTriggerHysteresisDiv: 0.5,
    oscShowReconstructedBeat: true, oscShowMeasurementTable: false,
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
      if (!(key in cache)) cache[key] = { get: () => vals[key], set: (v) => { vals[key] = v; }, addListener: () => {} };
      return cache[key];
    },
  };
  return new Proxy({}, handler);
}

const SR = 48000;
const N = 8192;

// A two-tone sum on L so the dual-tone beat reconstruction has a genuine |F1-F2|
// envelope to trace (F1 = 1000 Hz, F2 = 1050 Hz -> 50 Hz beat).
const F1 = 1000, F2 = 1050;
function dualBuf() {
  const b = new Float32Array(N);
  for (let i = 0; i < N; i++) {
    const t = i / SR;
    b[i] = 0.4 * Math.sin(2 * Math.PI * F1 * t) + 0.4 * Math.sin(2 * Math.PI * F2 * t);
  }
  return b;
}

// ===== Test: live dual render captures a beat overlay; stop freezes + replays it =====
{
  const canvas = makeCanvas();
  const prefs = makePrefs();
  const view = new ScopeView(canvas, { prefs });

  let heldCalls = 0;
  const realHeld = view._drawHeldFrame.bind(view);
  view._drawHeldFrame = (...a) => { heldCalls++; return realHeld(...a); };

  const buf = dualBuf();
  const info = { inRate: SR, available: N, period: SR / ((F1 + F2) / 2), peakVolts: 1.0,
                 bufL: buf, bufR: buf, dualTone: true, f1Hz: F1, f2Hz: F2,
                 generatorRunning: true };

  // --- Live AUTO paint with the reconstructed beat on: the full path runs and the
  // snapshot frame captures the beat overlay. ---
  view.render(buf, info);
  ok(view._frame != null, 'live dual AUTO render snapshots a frame');
  ok(Array.isArray(view._frame.beats) && view._frame.beats.length >= 1,
     'the snapshotted frame carries the reconstructed-beat polyline');
  const beat = view._frame.beats[0];
  let beatEnergy = 0;
  for (let i = 0; i < beat.snap.length; i++) beatEnergy += Math.abs(beat.snap[i]);
  ok(beatEnergy > 0, 'the captured beat buffer is a non-trivial polyline (has energy)');

  // --- Stop: freeze + standalone frozen replay (what the pane does on record stop). ---
  view.freeze();
  ok(view._frozen === true, 'freeze() arms the frozen-replay state (a frame exists)');

  const opsBefore = canvas._ctx._drawOps;
  const heldBefore = heldCalls;
  const painted = view.renderFrozen();
  ok(painted === true, 'renderFrozen() paints while frozen');
  ok(heldCalls === heldBefore + 1, 'renderFrozen() replays the frame through _drawHeldFrame');
  ok(canvas._ctx._drawOps > opsBefore, 'the frozen replay draws to the canvas (not blank)');

  // The frozen frame still carries the beat AFTER the replay (the overlay is drawn from
  // the captured samples, so stopping in AUTO does NOT lose the beat).
  ok(view._frame.beats.length >= 1, 'the frozen frame keeps the beat overlay after replay');

  // --- A resize-style second replay repaints identically (idempotent, non-blank). ---
  const opsResize = canvas._ctx._drawOps;
  ok(view.renderFrozen() === true, 'a second frozen replay (resize) still paints');
  ok(canvas._ctx._drawOps > opsResize, 'the resize replay is not blank');
}

// ===== Guard: no frame ever captured -> freeze is a no-op, renderFrozen paints nothing =====
{
  const canvas = makeCanvas();
  const prefs = makePrefs({ oscTriggerMode: 'SINGLE', oscTimePerDiv: N / SR });
  const view = new ScopeView(canvas, { prefs });
  view.setSingleArmed(true);
  view.render(dualBuf(), { inRate: SR, available: N, period: SR / 1000, peakVolts: 1.0,
    bufL: dualBuf(), bufR: dualBuf(), dualTone: false, f1Hz: 0, f2Hz: 0 });
  view.freeze();
  ok(view._frozen === false, 'freeze() is a no-op when no live frame was ever captured');
  ok(view.renderFrozen() === false, 'renderFrozen() paints nothing with no captured frame');
}

console.log(`\nScopeView freeze-beat: ${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
