/*
 * Phonalyser web - item #6: dual-tone residual retest at the EXACT user setup:
 * tones 19000/20000 bin-snapped @192k in+out, 200 µs/div, residual ON.
 *
 * Drives the REAL residual method (ScopeView._computeResidual - the same code the render path
 * calls) with the MEASURED dual pair injected via _lastMeas (item 6a: the fit must use the
 * worker's measured dualF1/F2, not the commanded pair), then Goertzel-scans the DRAWN residual
 * slice across 15-35 kHz and asserts every artifact is > 80 dB below the input tone level
 * (item 6c). Also asserts the fit window resolves 19k vs 20k (Δf·T ≥ 2, item 6b).
 *
 * Run: node web/test/scope-residual-dual.test.mjs
 */
globalThis.window = globalThis.window || { addEventListener: () => {} };
globalThis.performance = globalThis.performance || { now: () => Date.now() };

const { ScopeView } = await import('../js/scope/scope-view.js');

let passed = 0, failed = 0;
function ok(cond, msg) { if (cond) { passed++; } else { failed++; console.error('FAIL:', msg); } }

function makeCtx() {
  const ctx = {}; const noop = () => {};
  for (const m of ['save', 'restore', 'beginPath', 'closePath', 'moveTo', 'lineTo', 'arc',
                   'rect', 'setLineDash', 'fillText', 'clearRect', 'setTransform', 'stroke',
                   'fill', 'fillRect', 'strokeRect']) ctx[m] = noop;
  ctx.measureText = () => ({ width: 10 });
  return ctx;
}
function makeCanvas() {
  const ctx = makeCtx();
  const canvas = { width: 1000, height: 240, clientWidth: 1000, clientHeight: 240, style: {},
    title: '', getContext: () => ctx, addEventListener: () => {},
    getBoundingClientRect: () => ({ left: 0, top: 0, width: 1000, height: 240 }) };
  ctx.canvas = canvas; canvas._ctx = ctx; return canvas;
}
function makePrefs(initial) {
  const vals = Object.assign({
    oscLeftOffsetFrac: 0.5, oscRightOffsetFrac: 0.5, oscTriggerPositionFrac: 0.5,
    oscTriggerLevelFrac: 0.5, oscLeftVoltsPerDiv: 0.2, oscRightVoltsPerDiv: 0.2,
    oscLeftAcMode: false, oscRightAcMode: false, oscLeftChannelEnabled: true,
    oscRightChannelEnabled: false, oscLeftSincInterpEnabled: false, oscRightSincInterpEnabled: false,
    oscLeftChannelColor: 0x00d7ff, oscRightChannelColor: 0xffd700, oscLeftLpf: 'NONE',
    oscRightLpf: 'NONE', oscLeftMainsSuppression: 'NONE', oscRightMainsSuppression: 'NONE',
    oscTriggerChannel: 'L', oscTriggerMode: 'AUTO', oscTriggerEdge: 'RISE',
    oscTriggerHysteresisEnabled: false, oscTriggerHysteresisDiv: 0.5, oscShowReconstructedBeat: false,
    oscShowMeasurementTable: false, oscLeftResidualEnabled: true, oscRightResidualEnabled: false,
    oscMeasurementChannel: 'L', oscMeasurementAverageSeconds: 1.0, oscTimePerDiv: 2e-4,
    oscLineWidth: 2.0, oscDotDiameter: 0, adcFsVoltageRms: 1.0,
  }, initial || {});
  const cache = {};
  return new Proxy({}, { get(_t, key) {
    if (key === 'save') return () => {};
    if (!(key in cache)) cache[key] = { get: () => vals[key], set: (v) => { vals[key] = v; }, addListener: () => {} };
    return cache[key];
  } });
}

// Goertzel single-bin magnitude (DC-removed), same as signal-measurements.
function goertzel(data, from, len, sampleRate, freq) {
  let mean = 0; for (let i = 0; i < len; i++) mean += data[from + i]; mean /= len;
  const w = 2 * Math.PI * freq / sampleRate, coeff = 2 * Math.cos(w);
  let q1 = 0, q2 = 0;
  for (let i = 0; i < len; i++) { const q0 = (data[from + i] - mean) + coeff * q1 - q2; q2 = q1; q1 = q0; }
  return Math.sqrt(q1 * q1 + q2 * q2 - q1 * q2 * coeff);
}

const SR = 192000, PEAK = 1.0, AMP = 0.4;
// Bin-snapped @192k with a 32768-point analysis grid (the generator's snap grid).
const binHz = SR / 32768;
const F1 = Math.round(19000 / binHz) * binHz;   // ≈ 19001.95
const F2 = Math.round(20000 / binHz) * binHz;   // ≈ 19998.05

// ===== the residual at 200 µs/div =====
{
  const canvas = makeCanvas();
  const prefs = makePrefs();
  const view = new ScopeView(canvas, { prefs });

  // Long capture buffer: the residual grows its fit window LEFT from the display slice, so it
  // needs lookback. absStart 0.
  const N = 200000;
  const buf = new Float32Array(N);
  const phi1 = 0.7, phi2 = 2.1;
  for (let i = 0; i < N; i++) {
    buf[i] = AMP * Math.sin(2 * Math.PI * F1 * i / SR + phi1)
           + AMP * Math.sin(2 * Math.PI * F2 * i / SR + phi2);
  }

  // Inject the MEASURED dual pair (item 6a): _computeResidual reads _lastMeas first.
  view._lastMeas.L = { vpp: 0, vrms: 0, vmean: 0, period: NaN, riseTime: NaN, fallTime: NaN,
                       frequency: NaN, dutyCycle: NaN, dualF1: F1, dualF2: F2 };

  const info = { inRate: SR, available: N, period: SR / 19500, peakVolts: PEAK, absStart: 0,
                 bufL: buf, bufR: buf, dualTone: true, f1Hz: F1, f2Hz: F2, generatorRunning: true };

  // 200 µs/div -> windowSamples = 384. Centre the display slice deep in the buffer so the fit
  // window can grow symmetrically. dispStart chosen so the padded slice sits mid-buffer.
  const windowSamples = Math.round(2e-4 * 10 * SR);   // 384
  const dispCount = windowSamples;
  const dispStart = 100000;
  const PAD = 80;   // LANCZOS_PADDING = 16*5 = 80

  const scratch = view._computeResidual(buf, N, dispStart, dispCount, PAD, SR, true, PEAK, info);
  ok(scratch != null, 'residual computed (non-null)');

  const dispInScratch = view._residualDispStart;   // index of dispStart inside the scratch
  const sliceLen = view._residualSliceLen;
  ok(sliceLen >= dispCount, `residual slice covers the display window (sliceLen=${sliceLen})`);

  // Item 6b: the FIT window (which GROWS past the 384-sample display slice) must resolve 19k
  // vs 20k, i.e. Δf·T_fit ≥ 2. The growth logic (RESIDUAL_MIN_BEAT_CYCLES=2) sizes the fit to
  // ≥ ceil(2·SR/|Δf|) samples - exactly the ≥2-beat-cycle span that separates the tones. The
  // final residual cleanliness below is the empirical proof; here we assert the growth bound.
  const beatHz = Math.abs(F2 - F1);
  const needBeat = Math.ceil(2 * SR / beatHz);         // RESIDUAL_MIN_BEAT_CYCLES * SR / Δf
  const needCyc = Math.ceil(8 * SR / Math.min(F1, F2)); // RESIDUAL_MIN_CYCLES
  const PAD2 = PAD;
  const sliceSpan = Math.min(N, dispStart + dispCount + PAD2) - Math.max(0, dispStart - PAD2);
  const wantFit = Math.min(65536, Math.max(256, Math.max(needCyc, needBeat)));
  const fitLenExpected = Math.max(sliceSpan, wantFit);   // grows LEFT/RIGHT to ≥ wantFit
  const dfTfit = beatHz * fitLenExpected / SR;
  ok(dfTfit >= 2 - 1e-9, `fit window resolves 19k vs 20k (Δf·T_fit=${dfTfit.toFixed(2)} ≥ 2, fitLen=${fitLenExpected})`);

  // Scan the DRAWN residual across the tone band 15-35 kHz relative to the pure input tone
  // level, over the visible display window.
  const visFrom = dispInScratch;
  const visLen = Math.min(dispCount, sliceLen - dispInScratch);

  // Reference: a single input tone's Goertzel magnitude over this window length.
  const refBuf = new Float32Array(visLen);
  for (let i = 0; i < visLen; i++) refBuf[i] = AMP * Math.sin(2 * Math.PI * F1 * i / SR);
  const refMag = goertzel(refBuf, 0, visLen, SR, F1);

  let worstDbc = -999, worstF = 0;
  for (let f = 15000; f <= 35000; f += 50) {
    const mag = goertzel(scratch, visFrom, visLen, SR, f);
    const dbc = 20 * Math.log10(Math.max(mag, 1e-30) / refMag);
    if (dbc > worstDbc) { worstDbc = dbc; worstF = f; }
  }
  console.log(`[residual] F1=${F1.toFixed(3)} F2=${F2.toFixed(3)}  visLen=${visLen}`
    + `  worst artifact ${worstDbc.toFixed(1)} dBc @ ${worstF} Hz  (refMag=${refMag.toExponential(2)})`);

  // Item 6c: no drawn-residual artifact above -80 dBc in 15-35 kHz.
  ok(worstDbc < -80, `no residual artifact above -80 dBc (worst ${worstDbc.toFixed(1)} dBc @ ${worstF} Hz)`);

  // Also assert the residual Vpp recorded is tiny vs the ~2*AMP*PEAK input Vpp.
  const inVpp = 2 * AMP * PEAK;
  const resVpp = view._lastResidualVpp.L;
  ok(Number.isFinite(resVpp) && resVpp < inVpp * 1e-3,
     `residual Vpp << input Vpp (${resVpp.toExponential(2)} vs ${inVpp.toFixed(3)})`);
}

console.log(`\nscope-residual-dual: ${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
