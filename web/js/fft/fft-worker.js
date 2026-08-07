/*
 * Phonalyser web - FFT analyzer worker (off the main thread).
 * Buffer-and-analyze: the engine hands a window of N·(frames) capture samples;
 * this worker runs the faithful FftAnalyzer.analyze (own coherent/incoherent
 * cross-frame averaging, sub-bin fundamental, harmonics, THD/SNR/SINAD) and
 * returns the populated FftResult. Heavy FFT/analyze stays here so the render
 * loop on the main thread never blocks.
 *
 * threads > 1 -> THIS worker coordinates a nested pool of fft-pool-worker.js
 * siblings (prelude -> split the frame ranges -> gather -> merge -> finalize, all
 * in-worker). Java parity: FftAnalyzerWorker.parallelChunks (FftAnalyzerWorker
 * .java:640-642) splits chunks ON the background analyzer thread - the UI
 * thread never coordinates. (The first web port ran the coordinator on the
 * main thread, which froze the UI at threads > 1.)
 * GNU AGPL v3 or later.
 */
import { FftAnalyzer } from './fft-analyzer.js';
import { FftResult } from './fft-result.js';
import { TimeDiscontinuityDetector } from '../dsp/time-discontinuity.js';
import { FftAccumulator } from './fft-accumulator.js';

const analyzer = new FftAnalyzer();
const slot = new FftResult();   // reused pool slot - analyze/prelude/finalize write into it

// Time-domain discontinuity gate (Java FftAnalyzerWorker.timeDetector) - the scope's
// glitch detector run on the tick's raw window. A splice/dropout breaks the sinusoid
// recurrence decades above the noise floor even when its spectral footprint slips under
// the frequency-domain gates (and vice versa), so the scope trigger and the FFT rejection
// agree on what counts as a damaged block. The detector pass runs HERE (the raw window is
// transferred to this worker); the controller applies the re-sync recovery off the flag.
const timeDetector = new TimeDiscontinuityDetector();

// Step 5b: worker-side SHADOW copy of the cross-tick fold (FOLD_IN_WORKER parity harness).
// Lazily created; runs the exact FftAccumulator the controller runs, fed the same per-window
// results + reset/resync one-shots, so the controller can prove the fold ports byte-for-byte
// before it is moved off the main thread (§5c). null / unused unless d.foldInWorker.
let shadowAccum = null;

/** Java FftAnalyzerWorker doAnalysis (time-domain gate): the tick's own refined
 *  fundamental pins the recurrence prediction exactly, so the reject threshold rides
 *  on the noise floor at any signal frequency; NaN (no tone) self-estimates.
 *  @param {Float64Array} samples the raw tick window
 *  @param {FftResult} r this tick's analyzed result (fundamentalHzRefined + sampleRate)
 *  @returns {boolean} true => the window contains a time-domain discontinuity */
function timeDiscontinuity(samples, r) {
  const f0 = r.fundamentalHzRefined;
  const omega = (f0 > 0 && f0 < r.sampleRate / 2.0)
    ? 2.0 * Math.PI * f0 / r.sampleRate : NaN;
  return timeDetector.detect(samples, samples.length, omega);
}

/** Fundamental bin(s) the spectral gate measures the near-carrier pedestal around
 *  (mirror of FftController._fundamentalBins) - needed by the shadow fold's reject(). */
function fundamentalBins(r) {
  if (!(r.freqResolution > 0)) return null;
  const f1 = (Number.isFinite(r.fundamentalHzRefined) && r.fundamentalHzRefined > 0)
    ? Math.round(r.fundamentalHzRefined / r.freqResolution) : -1;
  const f2 = (Number.isFinite(r.fundamental2HzRefined) && r.fundamental2HzRefined > 0)
    ? Math.round(r.fundamental2HzRefined / r.freqResolution) : -1;
  if (f1 > 0 && f2 > 0) return Int32Array.of(f1, f2);
  if (f1 > 0) return Int32Array.of(f1);
  if (f2 > 0) return Int32Array.of(f2);
  return null;
}

/** Step 5b: run the shadow cross-tick fold for this tick and return the cumulative spectrum
 *  for the controller's parity check, or null if the tick was gated out / not accumulated.
 *  Mirrors FftController._onWorkerResult's fold (same FftAccumulator, same gate order
 *  time->spectral, same reset/resync one-shots), overlaid onto a SCRATCH copy so the posted
 *  per-window arrays stay raw for the main fold. */
function shadowFold(d, r, timeDisc) {
  if (!shadowAccum) shadowAccum = new FftAccumulator();
  if (d.resetAccum) shadowAccum.reset();
  if (d.resyncAccum) shadowAccum.onResync();
  shadowAccum.setStrongToneRelDb(d.strongToneRelDb ?? 100.0);
  if (!d.accumulate) return null;
  if (timeDisc) return null;                         // time gate: dropped, not folded
  if (d.spectralGate && shadowAccum.reject(r.re, r.im, r.fftSize / 2, r.freqResolution, fundamentalBins(r))) {
    return null;                                     // spectral gate: dropped
  }
  if (!shadowAccum.accumulate(r, d.winAbsStart, !!r.coherentAveraging, d.targetN)) return null;
  const scratch = {
    fftSize: r.fftSize, freqResolution: r.freqResolution, fundamentalHzRefined: r.fundamentalHzRefined,
    re: r.re.slice(), im: r.im.slice(), amplitudeDbFs: r.amplitudeDbFs.slice(), phaseDeg: r.phaseDeg.slice(),
  };
  shadowAccum.overlayOnto(scratch);
  return { wAmp: scratch.amplitudeDbFs, wAccumFrames: shadowAccum.accumFrames };
}

/** Compute the time-domain gate verdict + (optionally) the shadow fold, then post the result. */
function finishAndPost(r, d, id, t0) {
  const timeDisc = d.timeGate && timeDiscontinuity(d.samples, r);
  const shadow = d.foldInWorker ? shadowFold(d, r, timeDisc) : null;
  postResult(r, id, t0, timeDisc, shadow);
}

// Nested parallel pool (threads > 1). The controller serializes dispatches
// (one analysis in flight), so a single pending gather is enough.
// A hung sub-worker (no partial, no error) would otherwise leave `pending` unresolved and the
// controller's one-in-flight gate stuck forever -> the whole FFT stalls. The watchdog tears the
// pool down and finishes the tick SERIALLY so analysis self-heals (no Java analog - its pool
// runs in one JVM; a browser sub-worker can wedge independently).
const POOL_WATCHDOG_MS = 5000;
let pool = [];
let pending = null;

function ensurePool(W) {
  if (pool.length === W) return;
  for (const w of pool) { try { w.terminate(); } catch (_) {} }
  pool = [];
  const url = new URL('./fft-pool-worker.js', import.meta.url);
  for (let i = 0; i < W; i++) {
    const w = new Worker(url, { type: 'module' });
    w.onmessage = (e) => onPartial(e.data);
    // A hard sub-worker failure must still complete the gather, or the
    // controller's one-in-flight gate would stall the FFT forever.
    w.onerror = (ev) => onPartial({ id: pending ? pending.id : -1, w: -1, error: ev.message || 'fft-pool-worker error' });
    pool.push(w);
  }
}

/** Transferable snapshot of the FftResult fields the engine + the render-time
 *  .frc de-embed + the readout consume. Cloning the spectral arrays (not
 *  transferring `slot`'s) keeps the pool slot reusable next tick.
 *  `timeDisc` = the time-domain discontinuity verdict for this window (the
 *  controller mirrors Java's gate order off it - time gate before spectral). */
function postResult(r, id, t0, timeDisc, shadow) {
  const out = {
    id,
    ms: performance.now() - t0,
    timeDiscontinuity: !!timeDisc,
    fftSize: r.fftSize, sampleRate: r.sampleRate, frameCount: r.frameCount,
    freqResolution: r.freqResolution, windowType: r.windowType, overlap: r.overlap,
    amplitudeDbFs: r.amplitudeDbFs.slice(), phaseDeg: r.phaseDeg.slice(),
    re: r.re.slice(), im: r.im.slice(),
    fundamentalBin: r.fundamentalBin, fundamentalHz: r.fundamentalHz,
    fundamentalHzRefined: r.fundamentalHzRefined,
    fundamental2HzRefined: r.fundamental2HzRefined,
    fundamentalDbFs: r.fundamentalDbFs, fundamentalLinear: r.fundamentalLinear,
    fundamentalTrueDbFs: r.fundamentalTrueDbFs,
    fundamentalDynExclusionHz: r.fundamentalDynExclusionHz,
    harmonicCount: r.harmonicCount,
    harmonicBins: r.harmonicBins.slice(), harmonicHz: r.harmonicHz.slice(),
    harmonicDbFs: r.harmonicDbFs.slice(), harmonicPct: r.harmonicPct.slice(),
    thdPct: r.thdPct, thdDb: r.thdDb, thdNDb: r.thdNDb,
    snrDb: r.snrDb, sinadDb: r.sinadDb,
    snrFreqMin: r.snrFreqMin, snrFreqMax: r.snrFreqMax,
    coherentAveraging: r.coherentAveraging,
    noisePower: r.noisePower, windowNenbwBins: r.windowNenbwBins, avgNoiseFloorDbFs: r.avgNoiseFloorDbFs,
    imdProductA: r.imdProductA ? r.imdProductA.slice() : null,
    imdProductB: r.imdProductB ? r.imdProductB.slice() : null,
    imdProductBin: r.imdProductBin ? r.imdProductBin.slice() : null,
  };
  // Step 5b: attach the shadow fold's cumulative spectrum for the controller's parity check.
  if (shadow) { out.wAmp = shadow.wAmp; out.wAccumFrames = shadow.wAccumFrames; }
  const transfer = [
    out.amplitudeDbFs.buffer, out.phaseDeg.buffer, out.re.buffer, out.im.buffer,
    out.harmonicBins.buffer, out.harmonicHz.buffer, out.harmonicDbFs.buffer, out.harmonicPct.buffer,
  ];
  if (shadow) transfer.push(out.wAmp.buffer);
  self.postMessage(out, transfer);
}

/** threads > 1: single-threaded prelude here, per-frame accumulation fanned out
 *  to the nested pool, merge + finalize back here - the exact stage split the
 *  old main-thread coordinator ran, now entirely off the UI thread. */
function analyzePooled(d, W, t0) {
  let sp;
  try {
    sp = analyzer.prelude(d.samples, d.sampleRate, d.fftSize, d.harmonicCount,
      d.windowType, d.overlap, d.coherentAveraging, d.expectedFundHz, slot);
  } catch (err) { self.postMessage({ id: d.id, error: err.message }); return; }

  const frameCount = sp.frameCount;
  const fundRefDbFs = d.fundRefDbFs === undefined ? NaN : d.fundRefDbFs;
  // Too few frames to split (splitting buys nothing): accumulate + finalize here.
  if (frameCount < W) {
    let r;
    try {
      const part = analyzer.accumulatePartial(d.samples, 0, frameCount, sp);
      r = analyzer.finalize(part, sp, d.snrFreqMin, d.snrFreqMax, fundRefDbFs, slot);
    } catch (err) { self.postMessage({ id: d.id, error: err.message }); return; }
    finishAndPost(r, d, d.id, t0);
    return;
  }

  ensurePool(W);
  // `samples` kept by reference for the post-gather time-domain gate (the sub-workers
  // got transferred COPIES, so d.samples stays intact through the gather).
  pending = { id: d.id, t0, sp, partials: new Array(W), got: 0, want: W, error: null,
    snrLo: d.snrFreqMin, snrHi: d.snrFreqMax, fundRefDbFs,
    samples: d.samples, timeGate: !!d.timeGate, d };
  // Contiguous frame ranges: the first (frameCount % W) ranges carry one extra
  // frame so the union covers [0, frameCount) with no gaps/overlap.
  const base = Math.floor(frameCount / W);
  const rem = frameCount % W;
  let frameStart = 0;
  for (let i = 0; i < W; i++) {
    const len = base + (i < rem ? 1 : 0);
    const frameEnd = frameStart + len;
    // Each sub-worker gets its OWN copy of the sample window (transferred so it
    // owns it; d.samples stays intact for the next copy). The copies happen in
    // THIS worker - the UI thread never pays for them.
    const win = d.samples.slice();
    pool[i].postMessage({
      type: 'partial', id: d.id, w: i, frameStart, frameEnd,
      samples: win, sharedParams: sp,
    }, [win.buffer]);
    frameStart = frameEnd;
  }
  // Self-heal a wedged sub-worker: if the gather hasn't completed in POOL_WATCHDOG_MS, finish
  // this tick serially (onPoolTimeout). Cleared in onPartial the moment the gather completes.
  pending.watchdog = setTimeout(() => onPoolTimeout(d.id), POOL_WATCHDOG_MS);
}

/** Gathers one sub-worker's partial sum; once all are in, merges (associative)
 *  and finalizes, then posts the same result shape the serial path posts. */
function onPartial(p) {
  const g = pending;
  if (!g || p.id !== g.id) return;             // stale partial from a dropped tick
  if (p.error) { g.error = p.error; }
  else { g.partials[p.w] = { sumRe: p.sumRe, sumIm: p.sumIm, acceptedFrames: p.acceptedFrames }; }
  if (++g.got < g.want) return;

  pending = null;
  clearTimeout(g.watchdog);
  if (g.error) { self.postMessage({ id: g.id, error: g.error }); return; }
  let r;
  try {
    const merged = analyzer.mergePartials(g.partials, g.sp);
    r = analyzer.finalize(merged, g.sp, g.snrLo, g.snrHi, g.fundRefDbFs, slot);
  } catch (err) { self.postMessage({ id: g.id, error: err.message }); return; }
  finishAndPost(r, g.d, g.id, g.t0);
}

/** Watchdog: a sub-worker wedged (no partial, no error) - tear the pool down (rebuilt on the
 *  next threads>1 dispatch) and finish THIS tick on the coordinator thread so the controller's
 *  one-in-flight gate resolves instead of stalling the FFT forever. */
function onPoolTimeout(id) {
  const g = pending;
  if (!g || g.id !== id) return;   // already completed (watchdog cleared) or superseded
  pending = null;
  for (const w of pool) { try { w.terminate(); } catch (_) {} }
  pool = [];
  let r;
  try {
    const part = analyzer.accumulatePartial(g.samples, 0, g.sp.frameCount, g.sp);
    r = analyzer.finalize(part, g.sp, g.snrLo, g.snrHi, g.fundRefDbFs, slot);
  } catch (err) { self.postMessage({ id: g.id, error: err.message }); return; }
  finishAndPost(r, g.d, g.id, g.t0);
}

self.onmessage = (e) => {
  const d = e.data;
  const t0 = performance.now();
  // Dual-tone: hint the second tone so analyze populates fundamental2HzRefined
  // and the IMD-product grid (the IMD analyzer consumes both).
  analyzer.setMultiTone(!!d.multiTone);
  analyzer.setSecondToneHintHz(d.multiTone && d.secondToneHintHz > 0 ? d.secondToneHintHz : NaN);

  const W = Math.max(1, d.threads | 0);
  if (W > 1) { analyzePooled(d, W, t0); return; }

  let r;
  try {
    r = analyzer.analyze(
      d.samples, d.sampleRate, d.fftSize, d.harmonicCount,
      d.windowType, d.overlap, d.snrFreqMin, d.snrFreqMax,
      d.coherentAveraging,
      d.fundRefDbFs === undefined ? NaN : d.fundRefDbFs,   // THD manual-fundamental anchor
      d.expectedFundHz, slot);
  } catch (err) {
    self.postMessage({ id: d.id, error: err.message });
    return;
  }
  finishAndPost(r, d, d.id, t0);
};
