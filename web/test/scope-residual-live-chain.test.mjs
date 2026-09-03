/*
 * Phonalyser web - dual-tone residual END-TO-END LIVE-SEED-CHAIN test.
 *
 * The earlier scope-residual-dual test proved the residual MATH by INJECTING the
 * measured pair straight into view._lastMeas - so the LIVE seed chain that feeds
 * dualF1/dualF2 was never exercised. This harness injects NO measurements. It drives
 * the exact runtime path the app uses:
 *
 *   real GeneratorController (config = DUAL_TONE 19000/20000, snap-to-bin ON @192k)
 *     -> computeAnalysisFreqs()  (the only thing that populates gen.snapped)
 *     -> backend wiring getSnapped:()=>gen.snapped / getSnapped2:()=>gen._genEmitFreq2()
 *   real _measParams() equivalent building { dual, f1Hz:getSnapped, f2Hz:getSnapped2 }
 *   real OscMeasCompute fed a SYNTHETIC contiguous 19/20k capture stream whose tones
 *     are deliberately +25 ppm off the snapped pair (a real ADC clock offset)
 *     -> publish() per channel -> view.publishMeasurement(r)
 *   real ScopeView._computeResidual through the render residual path.
 *
 * ASSERTS at every publish:
 *   - the params seeds (f1Hz/f2Hz) are FINITE and equal the snapped emitted pair;
 *   - the published dualF1/dualF2 are finite and within 1e-3 Hz of the TRUE captured tones;
 *   - the DRAWN residual's worst 15-35 kHz artifact is < -80 dBc.
 *
 * Also covers the seed-chain state hazard the backend wiring creates: getSnapped reads
 * the CACHED gen.snapped field (populated only by computeAnalysisFreqs, i.e. on generator
 * start/retune) while getSnapped2 calls the LIVE _genEmitFreq2() method. A "configured but
 * computeAnalysisFreqs-not-yet-run" state therefore yields seed1=0 (unsnapped) while
 * seed2 is live - the exact asymmetry that would leave tone 1 unsubtracted on hardware.
 *
 * Run: node web/test/scope-residual-live-chain.test.mjs
 */
globalThis.window = globalThis.window || { addEventListener: () => {} };
globalThis.performance = globalThis.performance || { now: () => Date.now() };
globalThis.AudioContext = globalThis.AudioContext || function () {};   // never instantiated (we never startGenerator)

const { ScopeView } = await import('../js/scope/scope-view.js');
const { OscMeasCompute } = await import('../js/scope/osc-meas-compute.js');
const { GeneratorController } = await import('../js/generator/generator-controller.js');
const { GenSignalForm, isDualTone } = await import('../js/enums/gen-signal-form.js');
const { testPrefs } = await import('./_test-prefs.mjs');

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
    oscShowMeasurementTable: true, oscLeftResidualEnabled: true, oscRightResidualEnabled: false,
    oscMeasurementChannel: 'L', oscMeasurementAverageSeconds: 1.0, oscTimePerDiv: 2e-4,
    oscLineWidth: 2.0, oscDotDiameter: 0, adcFsVoltageRms: 1.0,
    genSignalForm: GenSignalForm.DUAL_TONE,
  }, initial || {});
  const cache = {};
  return new Proxy({}, { get(_t, key) {
    if (key === 'save') return () => {};
    if (!(key in cache)) cache[key] = { get: () => vals[key], set: (v) => { vals[key] = v; } };
    return cache[key];
  } });
}

// Goertzel single-bin magnitude (DC-removed), same as signal-measurements / the sister test.
function goertzel(data, from, len, sampleRate, freq) {
  let mean = 0; for (let i = 0; i < len; i++) mean += data[from + i]; mean /= len;
  const w = 2 * Math.PI * freq / sampleRate, coeff = 2 * Math.cos(w);
  let q1 = 0, q2 = 0;
  for (let i = 0; i < len; i++) { const q0 = (data[from + i] - mean) + coeff * q1 - q2; q2 = q1; q1 = q0; }
  return Math.sqrt(q1 * q1 + q2 * q2 - q1 * q2 * coeff);
}

const SR = 192000, PEAK = 1.0, AMP = 0.4;
const FFT = 32768, PPM = 25e-6;

// ===================================================================================
// PART A - the seed-chain STATE hazard: getSnapped reads the cached gen.snapped field.
// Configure the generator for dual-tone but do NOT run computeAnalysisFreqs yet, and
// observe what the backend-wired getters return. This is the "configured-but-controller
// -state-X" probe the diagnosis asks for.
// ===================================================================================
{
  const prefs = testPrefs({ genSignalForm: GenSignalForm.DUAL_TONE, genDualToneFreq1Hz: 19000,
    genDualToneFreq2Hz: 20000, genSnapToFftBin: true, fftLength: FFT, outputSampleRate: SR,
    inputSampleRate: SR, genAmplitudeVrms: AMP, dacFsVoltageAmpl: 1.0 });
  const gen = new GeneratorController(prefs, { status: () => {} });
  // Backend wiring (backend.js:104-105) verbatim.
  const getSnapped = () => gen.snapped;
  const getSnapped2 = () => gen._genEmitFreq2();

  // BEFORE computeAnalysisFreqs: gen.snapped is its constructor default (0). getSnapped2
  // is the LIVE method, already snapped. This is the asymmetry.
  const seed1Before = getSnapped();
  const seed2Before = getSnapped2();
  console.log(`[A] before computeAnalysisFreqs: seed1=${seed1Before}  seed2=${seed2Before}`);
  ok(seed1Before === 0, `configured-but-not-computed: getSnapped()===0 (cached field unset) - the seed-chain hazard (got ${seed1Before})`);
  ok(seed2Before > 0, `getSnapped2() is live (already snapped ${seed2Before.toFixed(3)} Hz)`);

  // AFTER computeAnalysisFreqs (what startGenerator / retuneGenerator / the capture's
  // computeAnalysisFreqs callback all run): gen.snapped is populated and snapped.
  // outSampleRate is 0 (no real output context in the test) so _genEmitFreq falls back to
  // c.outRate = SR - the same 192k bin grid the hardware app uses. This models the app's
  // steady state, where computeAnalysisFreqs HAS run (SharedCapture calls it every batch).
  gen.computeAnalysisFreqs();
  const seed1After = getSnapped();
  const seed2After = getSnapped2();
  console.log(`[A] after computeAnalysisFreqs:  seed1=${seed1After.toFixed(3)}  seed2=${seed2After.toFixed(3)}`);
  ok(Number.isFinite(seed1After) && seed1After > 0, `getSnapped() finite+snapped after computeAnalysisFreqs (${seed1After.toFixed(3)})`);
  const binHz = SR / FFT;
  const expect1 = Math.round(19000 / binHz) * binHz;
  const expect2 = Math.round(20000 / binHz) * binHz;
  ok(Math.abs(seed1After - expect1) < 1e-6, `getSnapped() equals the bin-snapped tone 1 (${seed1After.toFixed(3)} vs ${expect1.toFixed(3)})`);
  ok(Math.abs(seed2After - expect2) < 1e-6, `getSnapped2() equals the bin-snapped tone 2 (${seed2After.toFixed(3)} vs ${expect2.toFixed(3)})`);
}

// ===================================================================================
// PART B - the full live chain, generator in STEADY STATE (computeAnalysisFreqs run),
// no injected measurements. Feed OscMeasCompute a synthetic +25 ppm-off capture stream,
// publish through view.publishMeasurement, then render the residual and assert.
// ===================================================================================
{
  const genPrefs = testPrefs({ genSignalForm: GenSignalForm.DUAL_TONE, genDualToneFreq1Hz: 19000,
    genDualToneFreq2Hz: 20000, genSnapToFftBin: true, fftLength: FFT, outputSampleRate: SR,
    inputSampleRate: SR, genAmplitudeVrms: AMP, dacFsVoltageAmpl: 1.0 });
  const gen = new GeneratorController(genPrefs, { status: () => {} });
  gen.computeAnalysisFreqs();   // steady state - SharedCapture drives this every batch
  const getSnapped = () => gen.snapped;
  const getSnapped2 = () => gen._genEmitFreq2();

  const SNAP1 = getSnapped(), SNAP2 = getSnapped2();
  // The TRUE captured tones - deliberately +25 ppm off the snapped emit grid, like a real
  // ADC whose clock differs from the DAC. The residual seeds from the SNAPPED pair and must
  // still refine onto these true tones (the whole point of the raw-window refine chain).
  const TRUE1 = SNAP1 * (1 + PPM);
  const TRUE2 = SNAP2 * (1 + PPM);

  const canvas = makeCanvas();
  const prefs = makePrefs();
  const view = new ScopeView(canvas, { prefs });

  // Real OscMeasCompute - the SAME engine the osc-meas worker owns.
  const meas = new OscMeasCompute();

  // The per-tick measurement params - the exact structure scope-pane._measParams builds,
  // reading the LIVE pref dual flag + the backend-wired snapped getters.
  const measParams = () => {
    const dual = isDualTone(prefs.genSignalForm.get());
    const per = () => ({ mainsMode: 'NONE', avgSeconds: 1.0,
                         dual, f1Hz: getSnapped(), f2Hz: getSnapped2() });
    return { sampleRate: SR, peakVoltsL: PEAK, peakVoltsR: PEAK, avgSeconds: 1.0, L: per(), R: per() };
  };

  // Synthetic contiguous capture: feed the OscMeasCompute stream in ~100 ms batches (the
  // worker's cadence) so the rolling collection window fills like the live stream. absPos
  // advances inside feed(); we generate a single contiguous phase-continuous 2-tone signal.
  const phi1 = 0.7, phi2 = 2.1;
  const sample = (i) => AMP * Math.sin(2 * Math.PI * TRUE1 * i / SR + phi1)
                      + AMP * Math.sin(2 * Math.PI * TRUE2 * i / SR + phi2);
  const BATCH = Math.round(SR * 0.1);   // 100 ms
  const BATCHES = 12;                    // > 1 s avg window -> collection saturates
  let absPos = 0;

  let publishes = 0;
  let worstSeedErr = 0, worstToneErr = 0;
  for (let b = 0; b < BATCHES; b++) {
    const buf = new Float32Array(BATCH);
    for (let i = 0; i < BATCH; i++) buf[i] = sample(absPos + i);
    const pp = measParams();
    // The dual flag + seeds the params carry MUST match the snapped emitted pair, every tick.
    ok(pp.L.dual === true, `[B] params dual flag true (tick ${b})`);
    ok(Number.isFinite(pp.L.f1Hz) && pp.L.f1Hz > 0, `[B] params f1Hz finite+positive = ${pp.L.f1Hz} (tick ${b})`);
    ok(Number.isFinite(pp.L.f2Hz) && pp.L.f2Hz > 0, `[B] params f2Hz finite+positive = ${pp.L.f2Hz} (tick ${b})`);
    const seedErr = Math.max(Math.abs(pp.L.f1Hz - SNAP1), Math.abs(pp.L.f2Hz - SNAP2));
    if (seedErr > worstSeedErr) worstSeedErr = seedErr;
    ok(seedErr < 1e-9, `[B] params seeds equal the snapped emitted pair (err ${seedErr.toExponential(2)}, tick ${b})`);

    meas.feed('L', buf, BATCH, SR, { mainsMode: 'NONE', avgSeconds: 1.0 });
    absPos += BATCH;

    if (!meas.hasData('L')) continue;
    // publish() runs the REAL dual-tone refine chain: seeds from pp.L.f1Hz/f2Hz (the snapped
    // pair), refines each on the raw collection window onto the TRUE captured tone.
    const rL = meas.publish('L', SR, PEAK, pp.L);
    if (!rL) continue;
    view.publishMeasurement({ resultL: rL, resultR: null,
                              leftMeanNorm: rL.vmean / PEAK, rightMeanNorm: NaN });
    publishes++;

    // The published pair must be finite AND land on the TRUE captured tones (not the snapped
    // seeds) - that is what makes the residual immune to the ppm clock offset.
    ok(Number.isFinite(rL.dualF1) && Number.isFinite(rL.dualF2),
       `[B] published dualF1/F2 finite (${rL.dualF1}, ${rL.dualF2})`);
    const toneErr = Math.max(Math.abs(rL.dualF1 - TRUE1), Math.abs(rL.dualF2 - TRUE2));
    if (toneErr > worstToneErr) worstToneErr = toneErr;
    ok(toneErr < 1e-3, `[B] published pair within 1e-3 Hz of the TRUE captured tones (err ${toneErr.toExponential(2)} Hz)`);
  }
  ok(publishes >= 5, `[B] the live chain published (${publishes} publishes)`);
  console.log(`[B] SNAP=(${SNAP1.toFixed(3)}, ${SNAP2.toFixed(3)})  TRUE=(${TRUE1.toFixed(4)}, ${TRUE2.toFixed(4)})`);
  console.log(`[B] worst seed err ${worstSeedErr.toExponential(2)} Hz  worst refined-tone err ${worstToneErr.toExponential(2)} Hz  over ${publishes} publishes`);

  // ----- now render the residual through the REAL _computeResidual, seeded ONLY by what
  // the live chain published into view._lastMeas (NO injection). -----
  // A long contiguous capture buffer for the display + fit-window lookback.
  const N = 200000;
  const cap = new Float32Array(N);
  for (let i = 0; i < N; i++) cap[i] = sample(i);
  const info = { inRate: SR, available: N, period: SR / ((TRUE1 + TRUE2) / 2), peakVolts: PEAK,
                 absStart: 0, bufL: cap, bufR: cap, dualTone: true,
                 f1Hz: SNAP1, f2Hz: SNAP2, generatorRunning: true };

  // Confirm the residual reads the LIVE-published pair, not info.f1Hz/f2Hz.
  ok(view._lastMeas.L && Number.isFinite(view._lastMeas.L.dualF1),
     '[B] _lastMeas.L carries the live-published dual pair (no injection)');

  const windowSamples = Math.round(2e-4 * 10 * SR);   // 200 us/div -> 384
  const dispCount = windowSamples;
  const dispStart = 100000;
  const PAD = 80;   // LANCZOS_PADDING

  const scratch = view._computeResidual(cap, N, dispStart, dispCount, PAD, SR, true, PEAK, info);
  ok(scratch != null, '[B] residual computed (non-null) from the live-published seeds');

  const dispInScratch = view._residualDispStart;
  const sliceLen = view._residualSliceLen;
  const visFrom = dispInScratch;
  const visLen = Math.min(dispCount, sliceLen - dispInScratch);

  const refBuf = new Float32Array(visLen);
  for (let i = 0; i < visLen; i++) refBuf[i] = AMP * Math.sin(2 * Math.PI * TRUE1 * i / SR);
  const refMag = goertzel(refBuf, 0, visLen, SR, TRUE1);

  let worstDbc = -999, worstF = 0;
  for (let f = 15000; f <= 35000; f += 50) {
    const mag = goertzel(scratch, visFrom, visLen, SR, f);
    const dbc = 20 * Math.log10(Math.max(mag, 1e-30) / refMag);
    if (dbc > worstDbc) { worstDbc = dbc; worstF = f; }
  }
  console.log(`[B] drawn residual: worst artifact ${worstDbc.toFixed(1)} dBc @ ${worstF} Hz  (visLen=${visLen}, refMag=${refMag.toExponential(2)})`);
  ok(worstDbc < -80, `[B] no drawn-residual artifact above -80 dBc (worst ${worstDbc.toFixed(1)} dBc @ ${worstF} Hz)`);
}

console.log(`\nscope-residual-live-chain: ${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
