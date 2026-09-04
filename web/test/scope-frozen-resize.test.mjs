/*
 * Phonalyser web - ScopeView frozen-view resize-repaint contract test.
 * Run with `node web/test/scope-frozen-resize.test.mjs`.
 *
 * Reproduces the "frozen scope redraws from the ADC" bug: a stopped scope's
 * _lastBuf still points at the LIVE capture ring (which keeps filling while other
 * modules hold the device), and the view's ResizeObserver used to feed it back
 * through the full live render() - so any canvas size change (on macOS the
 * overlay scrollbar fading in under the pointer is enough) replaced the frozen
 * trace with whatever the ADC carried at that moment.
 *
 * The contract under test: _repaintAtCurrentSize() - the ResizeObserver's action -
 * must replay the held frame while frozen and never run the live pipeline; a live
 * or never-rendered view must repaint exactly as before.
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
    oscShowReconstructedBeat: false, oscShowMeasurementTable: false,
    oscMeasurementChannel: 'L', oscMeasurementAverageSeconds: 1.0,
    oscTimePerDiv: 1e-4, oscLineWidth: 2.0, oscDotDiameter: 5,
    adcFsVoltageRms: 1.0,
  }, initial || {});
  const cache = {};
  const handler = {
    get(_t, key) {
      if (key === 'save') return () => {};
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

function sineBuf(freq) {
  const b = new Float32Array(N);
  for (let i = 0; i < N; i++) b[i] = 0.5 * Math.sin(2 * Math.PI * freq * i / SR);
  return b;
}

// ===== Frozen: the resize repaint replays the held frame, never the live ring =====
{
  const canvas = makeCanvas();
  const prefs = makePrefs();
  const view = new ScopeView(canvas, { prefs });

  let heldCalls = 0, liveCalls = 0;
  const realHeld = view._drawHeldFrame.bind(view);
  view._drawHeldFrame = (...a) => { heldCalls++; return realHeld(...a); };
  const realBody = view._renderBody.bind(view);
  view._renderBody = (...a) => { liveCalls++; return realBody(...a); };

  const buf = sineBuf(1000);
  const info = { inRate: SR, available: N, period: SR / 1000, peakVolts: 1.0,
                 bufL: buf, bufR: buf, dualTone: false, f1Hz: 0, f2Hz: 0 };

  view.render(buf, info);
  ok(view._frame != null, 'live AUTO render snapshots a frame');
  const liveAfterRender = liveCalls;

  view.freeze();
  ok(view._frozen === true, 'freeze() arms the frozen-replay state');

  // The device keeps capturing for other holders: the ring the view still
  // references via _lastBuf now carries a DIFFERENT signal.
  const mutated = sineBuf(3333);
  buf.set(mutated);

  const opsBefore = canvas._ctx._drawOps;
  const heldBefore = heldCalls;
  view._repaintAtCurrentSize();
  ok(heldCalls === heldBefore + 1,
     'frozen resize repaint replays the held frame through _drawHeldFrame');
  ok(liveCalls === liveAfterRender,
     'frozen resize repaint never runs the live pipeline over the mutated ring');
  ok(canvas._ctx._drawOps > opsBefore, 'the frozen resize repaint is not blank');
}

// ===== Live: the resize repaint still runs the normal live render =====
{
  const canvas = makeCanvas();
  const prefs = makePrefs();
  const view = new ScopeView(canvas, { prefs });

  let liveCalls = 0;
  const realBody = view._renderBody.bind(view);
  view._renderBody = (...a) => { liveCalls++; return realBody(...a); };

  const buf = sineBuf(1000);
  view.render(buf, { inRate: SR, available: N, period: SR / 1000, peakVolts: 1.0,
                     bufL: buf, bufR: buf, dualTone: false, f1Hz: 0, f2Hz: 0 });
  const before = liveCalls;
  view._repaintAtCurrentSize();
  ok(liveCalls === before + 1, 'a live (not frozen) resize repaint re-runs the live render');
}

// ===== Nothing ever rendered: the resize repaint keeps the idle grid painted =====
{
  const canvas = makeCanvas();
  const view = new ScopeView(canvas, { prefs: makePrefs() });
  let idleCalls = 0;
  const realIdle = view.renderIdle.bind(view);
  view.renderIdle = (...a) => { idleCalls++; return realIdle(...a); };
  view._repaintAtCurrentSize();
  ok(idleCalls === 1, 'with no frame at all the resize repaint falls back to the idle grid');
}

console.log(`\nScopeView frozen-resize: ${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
