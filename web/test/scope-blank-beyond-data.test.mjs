/*
 * Phonalyser web - item #4: the LIVE/frozen polyline trace must draw NOTHING past the
 * captured data end (Java drawTrace: the non-envelope point provider returns Double.NaN for
 * pos < 0 || pos > n-1, breaking the SWT Path - unconditional, every mode). The web port
 * previously only honoured blankBeyondData in the ENVELOPE (zoomed-OUT) branch; the
 * non-envelope (zoomed-IN, ≤1 sample/px) branch called lanczos()/lerpAt(), which CLAMP the
 * position to [0,n-1] and return the last sample - a flatline + trailing polyline past the
 * data end (the user's 200 µs/div stopped-frame screenshot).
 *
 * Reproduces a zoomed-in window whose right side extends past `available`, captures every
 * polyline vertex, and asserts no trace vertex is emitted for a column whose sample position
 * lies beyond n-1.
 *
 * Run: node web/test/scope-blank-beyond-data.test.mjs
 */
globalThis.window = globalThis.window || { addEventListener: () => {} };
globalThis.performance = globalThis.performance || { now: () => Date.now() };

const { ScopeView } = await import('../js/scope/scope-view.js');

let passed = 0, failed = 0;
function ok(cond, msg) { if (cond) { passed++; } else { failed++; console.error('FAIL:', msg); } }

// Context stub that records every moveTo/lineTo of the CURRENT path as {x,y,op}. A NaN y or
// a "no point" (pen lifted) shows up as a break: the trace path never visits that column.
function makeCtx() {
  const ctx = { _drawOps: 0, canvas: null, _pts: [], _capture: false };
  const noop = () => {};
  for (const m of ['save', 'restore', 'closePath', 'arc', 'rect', 'setLineDash',
                   'fillText', 'clearRect', 'setTransform']) ctx[m] = noop;
  ctx.beginPath = () => { if (ctx._capture) ctx._pts = []; };
  ctx.moveTo = (x, y) => { if (ctx._capture) ctx._pts.push({ x, y, op: 'M' }); };
  ctx.lineTo = (x, y) => { if (ctx._capture) ctx._pts.push({ x, y, op: 'L' }); };
  ctx.stroke = () => { ctx._drawOps++; };
  ctx.fill = () => { ctx._drawOps++; };
  ctx.fillRect = () => { ctx._drawOps++; };
  ctx.strokeRect = () => { ctx._drawOps++; };
  ctx.measureText = () => ({ width: 10 });
  return ctx;
}

function makeCanvas(w = 1000, h = 240) {
  const ctx = makeCtx();
  const canvas = {
    width: w, height: h, clientWidth: w, clientHeight: h, style: {}, title: '',
    getContext: () => ctx, addEventListener: () => {},
    getBoundingClientRect: () => ({ left: 0, top: 0, width: w, height: h }),
  };
  ctx.canvas = canvas; canvas._ctx = ctx;
  return canvas;
}

function makePrefs(initial) {
  const vals = Object.assign({
    oscLeftOffsetFrac: 0.5, oscRightOffsetFrac: 0.5,
    oscTriggerPositionFrac: 0.5, oscTriggerLevelFrac: 0.5,
    oscLeftVoltsPerDiv: 0.2, oscRightVoltsPerDiv: 0.2,
    oscLeftAcMode: false, oscRightAcMode: false,
    oscLeftChannelEnabled: true, oscRightChannelEnabled: false,
    oscLeftSincInterpEnabled: false, oscRightSincInterpEnabled: false,   // linear polyline
    oscLeftChannelColor: 0x00d7ff, oscRightChannelColor: 0xffd700,
    oscLeftLpf: 'NONE', oscRightLpf: 'NONE',
    oscLeftMainsSuppression: 'NONE', oscRightMainsSuppression: 'NONE',
    oscTriggerChannel: 'L', oscTriggerMode: 'AUTO', oscTriggerEdge: 'RISE',
    oscTriggerHysteresisEnabled: false, oscTriggerHysteresisDiv: 0.5,
    oscShowReconstructedBeat: false, oscShowMeasurementTable: false,
    oscLeftResidualEnabled: false, oscRightResidualEnabled: false,
    oscMeasurementChannel: 'L', oscMeasurementAverageSeconds: 1.0,
    oscTimePerDiv: 2e-4, oscLineWidth: 2.0, oscDotDiameter: 0,
    adcFsVoltageRms: 1.0,
  }, initial || {});
  const cache = {};
  return new Proxy({}, { get(_t, key) {
    if (key === 'save') return () => {};
    if (!(key in cache)) cache[key] = { get: () => vals[key], set: (v) => { vals[key] = v; } };
    return cache[key];
  } });
}

const SR = 192000;

// ===== _drawTrace directly: a zoomed-in window (samplesPerPx<1 -> non-envelope polyline)
// whose columns run PAST the data end must draw nothing there (pen lifted), mirroring Java's
// Double.NaN point provider. This isolates the exact branch item #4 is about, free of the
// nav/anchor math. =====
function runDrawTrace(sinc) {
  const canvas = makeCanvas(1000, 240);
  const prefs = makePrefs({ oscLeftSincInterpEnabled: sinc });
  const view = new ScopeView(canvas, { prefs });
  const ctx = canvas._ctx;

  // 384-sample display window across 1000 px -> samplesPerPx≈0.38<1 -> non-envelope branch.
  const n = 200;                 // captured data ends at index 199
  const windowSamples = 384;     // window is WIDER than the data -> right side spills past n-1
  const buf = new Float32Array(n);
  for (let i = 0; i < n; i++) buf[i] = 0.5 * Math.sin(2 * Math.PI * 20000 * i / SR);

  const cols = canvas.width;
  const samplesPerPx = windowSamples / cols;
  const pxPerSample = cols / windowSamples;
  const sampleToY = (v) => 120 - v * 100;

  ctx._capture = true;
  // startSample 0: viewLeft at buffer start; the window (384) extends 184 samples past n(200).
  view._drawTrace(ctx, cols, 240, buf, n, 0, windowSamples, cols, samplesPerPx, pxPerSample,
                  sampleToY, sinc, '#00d7ff', 0);
  ctx._capture = false;

  const pts = ctx._pts;
  // The last IN-DATA column is where pos = n-1 = 199. Beyond that x, no vertex may appear.
  // Solve sx from pos = sx * samplesPerPx (startSample 0) -> sx = (n-1)/samplesPerPx.
  const lastValidSx = (n - 1) / samplesPerPx;   // ≈ 518 px of 1000
  const maxX = pts.length ? Math.max(...pts.map((p) => p.x)) : 0;
  return { pts, maxX, lastValidSx };
}

for (const sinc of [false, true]) {
  const label = sinc ? 'sinc' : 'linear';
  const { pts, maxX, lastValidSx } = runDrawTrace(sinc);
  ok(pts.length > 0, `${label}: trace polyline emitted vertices`);
  // Java blanks pos>n-1 -> no vertex right of the last valid column (+1 px slop for rounding).
  ok(maxX <= lastValidSx + 1,
     `${label}: trace stops at the data end (maxX=${maxX.toFixed(1)}, lastValidSx=${lastValidSx.toFixed(1)}, W=1000)`);
}

console.log(`\nscope-blank-beyond-data: ${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
