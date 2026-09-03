/*
 * Phonalyser web - FFT restart-from-0 semantics (retest #7/#26) + THD-while-stopped (#24).
 *
 * Temp (_-prefixed) regression test for the user's exact spec: "FFT should start from 0
 * ONLY if FFT length, window, get fundamental, align generator, coherent are changed.
 * All other changes SHOULDN'T cause restart! Restart FFT means reset statistic and
 * average accumulator." (Java: FftView.java:436/449/451 window/length/coherent ->
 * resetStatistics; FftTabControl.java:377-380 overlap does NOT reset - "the worker
 * adapts next tick"; :391-397 averages does NOT reset.)
 *
 * Drives the REAL FftController (js/audio/fft-controller.js) with the REAL FftAccumulator
 * and real analyzer-produced spectra, plus the REAL FftTabControl THD recompute path
 * (js/fft/fft-tab-control.js - constructor + onThdSettingChanged need no DOM). Asserts:
 *
 *  (1) the accumulator DEPTH SURVIVES an overlap change and a threads change mid-run
 *      (hop/bufLen/poolSize re-derived live by _syncLiveConfig, NO accumulator reset);
 *  (2) the accumulator RESETS on the five restart controls' effect - window / coherent /
 *      fund-from-gen / align funnel through engine.resetAnalyses(), FFT length through the
 *      consumer rebuild (_setupFftAnalysis) - and the tab-control source wires exactly that;
 *  (3) with recording STOPPED, a THD-band change recomputes thdPct on the stored result
 *      (FftTabControl.onThdSettingChanged -> recomputeStaticResult -> recomputeStats ->
 *      setResult + view render) with no capture restart and no stats reset;
 *  (4) the align OFF->ON transition resets the FLL loop only (Java FftView.java:465-466),
 *      exactly once per transition.
 *
 * Run: node test/e2e/_fft-restart-semantics-test.mjs   (exits non-zero on any FAIL)
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { FftController } from '../../js/fft/fft-controller.js';
import { FftTabControl } from '../../js/fft/fft-tab-control.js';
import { FftAnalyzer } from '../../js/fft/fft-analyzer.js';
import { testPrefs } from '../_test-prefs.mjs';

let pass = 0, fail = 0;
const ok = (cond, msg) => { if (cond) { pass++; console.log('PASS: ' + msg); } else { fail++; console.log('FAIL: ' + msg); } };

// ── Signal / analysis parameters ─────────────────────────────────────────────
const SR = 48000;
const FFT = 4096;
const TONE_HZ = 100 * SR / FFT;              // on-grid fundamental (bin 100) = 1171.875 Hz
const AMP = Math.pow(10.0, -6.0 / 20.0);     // −6 dBFS
const H2_REL = 0.01;                         // 1% second harmonic -> thdPct ≈ 1
let seed = 0x2468ace1;
const rnd = () => { seed ^= seed << 13; seed ^= seed >>> 17; seed ^= seed << 5; seed >>>= 0; return (seed / 0xffffffff) * 2 - 1; };
const sampleAt = (n) => AMP * Math.sin(2 * Math.PI * TONE_HZ * n / SR)
                      + AMP * H2_REL * Math.sin(2 * Math.PI * 2 * TONE_HZ * n / SR)
                      + 1e-4 * rnd();

// Headless: _setupFftAnalysis spawns a Worker (browser API) - stub it; the analysis
// itself is driven in-process below (analyze() is exactly what the worker runs).
globalThis.Worker = class { constructor() {} postMessage() {} terminate() {} set onmessage(_) {} set onerror(_) {} };

// High-backlog reader: keeps the DISPLAY throttle's showNow gate closed so the ticks we
// drive stay on the accumulate-only path; available()=0 so feedFft only runs the live
// config sync (which is the handler effect under test - host.readConfig + next batch).
const idleReader = {
  available: () => 0, read: () => 0, seekToLatest() {},
  getReadPos: () => 0, getWritePos: () => 10_000_000,
  // The capture-death seam the feed consults first - a live lane answers "not finished".
  isFinished: () => false, takeFinishedReasonForReport: () => null,
};

function makeController(prefs) {
  const gen = {
    snapped: TONE_HZ, binW: SR / FFT, genNode: null, running: false,
    genFreq: TONE_HZ, fllErrHz: 0, fllLocked: false, fllStable: 0, rejectedCount: 0,
    computeAnalysisFreqs() {}, snapToRate: () => TONE_HZ,
  };
  const c = new FftController({ acquire: async () => idleReader, release: async () => {} }, gen, prefs, {});
  c._setupFftAnalysis();          // real geometry (N/hop/bufLen/pool) off the preferences
  c._fftOn = true;
  c._fftReader = idleReader;
  return c;
}

const analyzer = new FftAnalyzer();
/** The worker's accumulator, modelled: the fold lives in fft-worker.js and only its verdict
 *  (accumFrames / accumDepthTicks) comes back, so each tick here folds two frames into a depth
 *  kept per controller - started over when the controller's reset one-shot rides the tick,
 *  kept across its re-sync one-shot, exactly what the worker does with the two flags. */
const workerDepth = new Map();
/** One worker tick: real analyze() on the absolute-timeline window (what fft-worker.js
 *  does), handed to the REAL _onWorkerResult with the dispatch stamps set the way
 *  _dispatch would set them. */
function tick(c, absStart) {
  const w = new Float64Array(c.bufLen);
  for (let i = 0; i < c.bufLen; i++) w[i] = sampleAt(absStart + i);
  analyzer.setMultiTone(false);
  analyzer.setSecondToneHintHz(NaN);
  analyzer.setSpectrumOnly(true);
  const r = analyzer.analyze(w, SR, FFT, 9, 'BH4', c._prefs.fftOverlap.get(), 0, 0, true, NaN, NaN);
  analyzer.setSpectrumOnly(false);
  r.ms = 1;
  // The one-shots a dispatch would carry to the worker: a reset empties the fold, a re-sync
  // keeps its depth.
  if (c._workerResetPending) { workerDepth.set(c, 0); c._workerResetPending = false; }
  c._workerResyncPending = false;
  const depth = (workerDepth.get(c) || 0) + 2;
  workerDepth.set(c, depth);
  r.accumFrames = depth;
  r.accumDepthTicks = depth / 2;
  c._busy = true;
  c._tickEpoch = c._accumEpoch;
  c._tickAbsStart = absStart;
  c._tickAbsCapStart = absStart;
  c._tickWritePos = absStart + c.bufLen;
  c._lastShowMs = performance.now();   // keep the display throttle closed (accumulate-only)
  c._onWorkerResult(r);
}

console.log('\n== FFT restart-from-0 semantics (#7/#26) + THD-while-stopped (#24) ==\n');

// ════════════════════════════════════════════════════════════════════════════
// (1) NO-RESET controls: overlap + threads mid-run - depth SURVIVES
// ════════════════════════════════════════════════════════════════════════════
{
  const prefs = testPrefs({
    fftLength: FFT, inputSampleRate: SR, fftAverages: Infinity, fftOverlap: 'PCT_0',
    fftCoherentAveraging: true, fftWindow: 'BH4', fftThreads: 1, fftChannel: 'R',
    fftStopAfterNEnabled: false, fftStopAfterN: 10, fftMainsSuppression: 'NONE',
    fftAlignGenerator: 'NONE', fftStrongToneRelDb: 100, genSignalForm: 'SINE',
  });
  const c = makeController(prefs);
  let abs = 0;
  for (let t = 0; t < 8; t++) { tick(c, abs); abs += c.maxHop; }
  const depthBefore = c.framesDone;
  const ticksBefore = c.completedAnalyses();
  // The first tick flips the averaging mode (lastAccumulate) and restarts the fold, so eight
  // ticks fold seven - the mode-transition reset of the desktop worker, kept.
  ok(depthBefore >= 7, `mid-run: the depth readout follows the folded ticks (framesDone=${depthBefore}, ticks=${ticksBefore})`);

  // ── OVERLAP change (the #overlap handler effect: the preference write -> the running
  //    consumer re-derives hop/bufLen on the next batch; Java FftTabControl.java:377-380). ──
  prefs.fftOverlap.set('PCT_50');
  const epochBefore = c._accumEpoch;
  c.feedFft();                     // next capture batch -> _syncLiveConfig
  ok(c.maxHop === Math.round(FFT * 0.5), `overlap 0->50%: hop bounds re-derived live (maxHop=${c.maxHop})`);
  ok(c.bufLen === FFT + c.maxHop, `overlap 0->50%: bufLen re-derived live (bufLen=${c.bufLen})`);
  ok(c._workerResyncPending === true && c._workerResetPending === false,
    'overlap change RE-ANCHORS the accumulator (slope + PLL) and keeps its depth - a re-sync, never a reset');
  ok(c.framesDone === depthBefore,
    `overlap change KEEPS the depth readout (${c.framesDone} === ${depthBefore})`);
  ok(c.completedAnalyses() === ticksBefore,
    `overlap change KEEPS the statistics tick count (${c.completedAnalyses()})`);
  ok(c._accumEpoch === epochBefore + 1,
    'overlap change bumps the epoch ONCE (drops only the in-flight old-geometry window)');

  // Depth keeps GROWING after the overlap change (κ/PLL re-anchored via onResync, not reset).
  abs += 100000;                   // the ring re-fills from later samples - a real timebase jump
  for (let t = 0; t < 6; t++) { tick(c, abs); abs += c.maxHop; }
  ok(c.framesDone > depthBefore,
    `average keeps deepening across the overlap change (${depthBefore} -> ${c.framesDone})`);

  // ── THREADS change (the #threads handler effect): pool re-sized live, NO reset. ──
  const depthT = c.framesDone;
  const epochT = c._accumEpoch;
  prefs.fftThreads.set(3);
  c.feedFft();
  const hwCap = Math.max(1, (navigator.hardwareConcurrency || 4) - 1);
  ok(c.poolSize === Math.max(1, Math.min(16, hwCap, 3)),
    `threads 1->3: poolSize re-derived live (poolSize=${c.poolSize})`);
  ok(c.framesDone === depthT && c._accumEpoch === epochT && !c._workerResetPending && !c._workerResyncPending,
    `threads change touches NOTHING else - depth ${c.framesDone} kept, no epoch bump, no one-shot`);

  // ════════════════════════════════════════════════════════════════════════
  // (2) RESET controls: window / coherent / fund-from-gen / align -> resetAnalyses;
  //     FFT length -> consumer rebuild (_setupFftAnalysis). Depth goes to 0 each time.
  // ════════════════════════════════════════════════════════════════════════
  // window / coherent / fund-from-gen / align all funnel through the SAME handler effect:
  // the preference write + engine.resetAnalyses() (Java FftView.java:436/451 resetStatistics;
  // fund-from-gen + align restart per the user's spec). Assert the effect zeroes the
  // statistics + accumulator and bumps the epoch (in-flight windows dropped).
  ok(c.framesDone > 0, `pre-reset: depth present (${c.framesDone})`);
  const epochR = c._accumEpoch;
  prefs.fftWindow.set('HFT248D');  // what the window control writes
  c.resetAnalyses();               // the handler's engine.resetAnalyses()
  ok(c.framesDone === 0 && c.completedAnalyses() === 0 && c._workerResetPending === true,
    'window/coherent/fund-from-gen/align handler effect (resetAnalyses) RESETS stats + accumulator to 0');
  ok(c._accumEpoch === epochR + 1, 'resetAnalyses bumps the accumulator epoch (in-flight window dropped)');

  // Re-collect, then FFT LENGTH: the #fftSize handler restarts the consumer (restartFft ->
  // setRecording(false/true) -> _setupFftAnalysis) - the rebuild starts the average from 0.
  for (let t = 0; t < 4; t++) { tick(c, abs); abs += c.maxHop; }
  ok(c.framesDone > 0, `re-collected depth before the length change (${c.framesDone})`);
  c._setupFftAnalysis();           // the restart path's setup (Java FftView.java:449 resetStatistics)
  ok(c.framesDone === 0 && c.completedAnalyses() === 0 && c._workerResetPending === true,
    'FFT-length handler effect (consumer rebuild) RESETS stats + accumulator to 0');

  // ── (4) align OFF->ON resets the FLL loop exactly once (Java FftView.java:465-466). ──
  let fllResets = 0;
  c._fll = { reset: () => { fllResets++; } };
  prefs.fftAlignGenerator.set('FLL');   // what the #align handler writes
  c.feedFft();
  ok(fllResets === 1, 'align OFF->ON resets the FLL loop (fresh convergence) exactly once');
  c.feedFft();
  ok(fllResets === 1, 'align staying ON does NOT re-reset the FLL loop');
  prefs.fftAlignGenerator.set('NONE');
  c.feedFft();
  ok(fllResets === 1, 'align ON->OFF holds the converged correction (no loop reset)');
}

// ════════════════════════════════════════════════════════════════════════════
// (2b) WIRING: the tab-control source routes each control to the right effect
// ════════════════════════════════════════════════════════════════════════════
{
  const src = readFileSync(fileURLToPath(new URL('../../js/fft/fft-tab-control.js', import.meta.url)), 'utf8');
  // Handler block = from its registration to the next handler registration / field rebind,
  // with comment lines stripped (the comments cite the OLD restartFft behaviour).
  const block = (marker, end) => {
    const i = src.indexOf(marker);
    if (i < 0) return null;
    const j = src.indexOf(end, i + marker.length);
    return src.slice(i, j < 0 ? src.length : j)
      .split('\n').filter((l) => !l.trim().startsWith('//')).join('\n');
  };
  const bSize = block("$('#fftSize').on", "$('#threads')");
  const bThreads = block("$('#threads').on", "// OVERLAP");
  // The SECOND #overlap handler - the first one writes the preference (bound right after the
  // FFT-settings inputs); the tile refresh is the one whose effect is under test.
  const bOverlap = block("$('#overlap').on('change', () => this.updateTabSub())", '// WINDOW');
  const bWinCoh = block("$('#window,#coherent').on", '// ALIGN');
  const bAlign = block("$('#align').on", '// Fundamental-from-generator');
  const bFund = block("$('#fftFundFromGen').on", '// THD settings');
  // #4: the THD inputs are NumericStepFields now - the shared commit path is
  // commitThdSettings (checkbox change handlers + the four fields' onChange rebinds).
  const bThd = block('const commitThdSettings', 'const fAvg');
  const bAvg = block('fAvg.onChange', 'const fManFund');
  const bManFund = block('fManFund.onChange', '// ----- tile tabs');
  ok(bSize != null && bSize.includes('restartFft()'),
    '#fftSize handler restarts the consumer (reset from 0 - Java FftView.java:449)');
  ok(bThreads != null && !bThreads.includes('restartFft') && !bThreads.includes('resetAnalyses'),
    '#threads handler neither restarts nor resets (live pool re-derive)');
  ok(bOverlap != null && !bOverlap.includes('restartFft') && !bOverlap.includes('resetAnalyses'),
    '#overlap handler neither restarts nor resets (Java FftTabControl.java:377-380)');
  ok(bWinCoh != null && bWinCoh.includes('resetAnalyses'),
    '#window/#coherent handler resets the statistics (Java FftView.java:436/:451)');
  ok(bAlign != null && bAlign.includes('resetAnalyses'),
    '#align handler resets the statistics (user spec, retest #7)');
  ok(bFund != null && bFund.includes('resetAnalyses'),
    '#fftFundFromGen handler resets the statistics (user spec, retest #7)');
  ok(bThd != null && !bThd.includes('restartFft') && !bThd.includes('resetAnalyses')
      && bThd.includes('onThdSettingChanged'),
    'THD-settings handler: no restart, no reset - live readConfig + static recompute (#24/#26)');
  ok(bAvg != null && !bAvg.includes('restartFft') && !bAvg.includes('resetAnalyses'),
    '#averages rebind: no restart, no reset (Java FftTabControl.java:391-397)');
  ok(bManFund != null && !bManFund.includes('restartFft')
      && bManFund.includes('onThdSettingChanged'),
    'manual-fundamental rebind: THD-settings path, no restart (Java FftView.java:478)');
}

// ════════════════════════════════════════════════════════════════════════════
// (3) THD change while STOPPED recomputes the stored result (#24)
// ════════════════════════════════════════════════════════════════════════════
{
  // Build a genuine analyzed spectrum with a 1% H2 -> thdPct ≈ 1.
  const w = new Float64Array(FFT);
  for (let i = 0; i < FFT; i++) w[i] = sampleAt(i);
  analyzer.setMultiTone(false);
  analyzer.setSecondToneHintHz(NaN);
  let stored = analyzer.analyze(w, SR, FFT, 9, 'BH4', 'PCT_0', 0, 0, true, NaN, NaN);
  const thd0 = stored.thdPct;
  ok(thd0 > 0.9 && thd0 < 1.1, `stored result carries the 1% H2 (thdPct=${thd0.toFixed(4)})`);

  // The REAL FftTabControl with the engine STOPPED, over the preferences it reads the THD
  // settings from (the controls write them; here the test writes them directly).
  const engineStub = { running: false };
  const thdPrefs = testPrefs({ fftCalcMaxHarmonic: 9, fftManualFundEnabled: false,
    fftDistMinEnabled: false, fftDistMaxEnabled: false });
  thdPrefs.dbvOffsetDb = 0;   // the Vrms->dBFS map is then 20·log10(v)
  let setResultCalls = 0, renderCalls = 0;
  const tab = new FftTabControl(engineStub, thdPrefs, {
    pane: {
      getResult: () => stored,
      setResult: (r) => { stored = r; setResultCalls++; },
    },
    fftView: { render: () => { renderCalls++; } },
    fftViewCorrection: null, frcStore: [], getField: () => null, io: null,
    restartFft: () => { throw new Error('THD change must NOT restart the capture'); },
    showConfirm: null, setStatus: () => {}, tileChips: () => '',
  });
  // User enables the LP distortion bound BELOW H2 (dist max = 1.5·f0): H2 leaves the
  // band -> thdPct must recompute to ~0 on the STORED result, immediately, no restart.
  thdPrefs.fftDistMaxHz.set(1.5 * TONE_HZ);
  thdPrefs.fftDistMaxEnabled.set(true);
  tab.onThdSettingChanged();
  ok(stored.thdPct < 0.05,
    `LP band below H2 recomputes thdPct on the stored result: ${thd0.toFixed(4)} -> ${stored.thdPct.toFixed(4)} %`);
  ok(setResultCalls === 1 && renderCalls === 1,
    `stopped-state THD change re-renders (setResult=${setResultCalls}, render=${renderCalls})`);

  // Band removed again -> thdPct returns to ≈1% (the spectrum itself was never touched).
  thdPrefs.fftDistMaxEnabled.set(false);
  tab.onThdSettingChanged();
  ok(Math.abs(stored.thdPct - thd0) < 0.02,
    `removing the band restores thdPct (${stored.thdPct.toFixed(4)} ≈ ${thd0.toFixed(4)})`);

  // Max-harmonics change resizes the harmonic table on the stored result
  // (Java FftController.recomputeStaticResult:490-498): the COUNT of H2..HN is
  // max(9, calcMax) - 1, so calcMax 20 asks for 19 harmonics.
  thdPrefs.fftCalcMaxHarmonic.set(20);
  tab.onThdSettingChanged();
  ok(stored.harmonicCount === 19 && stored.harmonicBins.length === 19,
    `calc-max-harmonic change re-derives the harmonic table while stopped (count=${stored.harmonicCount})`);

  // Manual fundamental (g28): anchoring the reference 6 dB ABOVE the measured level
  // must halve the harmonic ratio -> thdPct ≈ 0.5% (Java recomputeStaticResult:499-502).
  // With dbvOffsetDb = 0 the Vrms->dBFS map is 20·log10(v), so v = 2·AMP puts the anchor
  // exactly 2× (+6.02 dB) above the measured −6 dBFS fundamental.
  thdPrefs.fftCalcMaxHarmonic.set(10);   // back to 9 harmonics
  thdPrefs.fftManualFundEnabled.set(true);
  thdPrefs.fftManualFundVrms.set(2 * AMP);
  tab.onThdSettingChanged();
  ok(Math.abs(stored.thdPct - thd0 / 2) < 0.02,
    `manual-fundamental anchor applies while stopped (thdPct=${stored.thdPct.toFixed(4)} ≈ ${(thd0 / 2).toFixed(4)})`);
}

console.log(`\nfft-restart-semantics: ${pass} passed, ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
