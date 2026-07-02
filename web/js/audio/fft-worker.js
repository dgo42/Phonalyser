/*
 * Phonalyser web — FFT analyzer worker (off the main thread).
 * Buffer-and-analyze: the engine hands a window of N·(frames) capture samples;
 * this worker runs the faithful FftAnalyzer.analyze (own coherent/incoherent
 * cross-frame averaging, sub-bin fundamental, harmonics, THD/SNR/SINAD) and
 * returns the populated FftResult. Heavy FFT/analyze stays here so the render
 * loop on the main thread never blocks.
 *
 * threads > 1 → THIS worker coordinates a nested pool of fft-pool-worker.js
 * siblings (prelude → split the frame ranges → gather → merge → finalize, all
 * in-worker). Java parity: FftAnalyzerWorker.parallelChunks (FftAnalyzerWorker
 * .java:640-642) splits chunks ON the background analyzer thread — the UI
 * thread never coordinates. (The first web port ran the coordinator on the
 * main thread, which froze the UI at threads > 1.)
 * GNU AGPL v3 or later.
 */
import { FftAnalyzer } from '../fft/fft-analyzer.js';
import { FftResult } from '../fft/fft-result.js';

const analyzer = new FftAnalyzer();
const slot = new FftResult();   // reused pool slot — analyze/prelude/finalize write into it

// Nested parallel pool (threads > 1). The controller serializes dispatches
// (one analysis in flight), so a single pending gather is enough.
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
 *  transferring `slot`'s) keeps the pool slot reusable next tick. */
function postResult(r, id, t0) {
  const out = {
    id,
    ms: performance.now() - t0,
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
    noisePower: r.noisePower, avgNoiseFloorDbFs: r.avgNoiseFloorDbFs,
    imdProductA: r.imdProductA ? r.imdProductA.slice() : null,
    imdProductB: r.imdProductB ? r.imdProductB.slice() : null,
    imdProductBin: r.imdProductBin ? r.imdProductBin.slice() : null,
  };
  self.postMessage(out, [
    out.amplitudeDbFs.buffer, out.phaseDeg.buffer, out.re.buffer, out.im.buffer,
    out.harmonicBins.buffer, out.harmonicHz.buffer, out.harmonicDbFs.buffer, out.harmonicPct.buffer,
  ]);
}

/** threads > 1: single-threaded prelude here, per-frame accumulation fanned out
 *  to the nested pool, merge + finalize back here — the exact stage split the
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
    postResult(r, d.id, t0);
    return;
  }

  ensurePool(W);
  pending = { id: d.id, t0, sp, partials: new Array(W), got: 0, want: W, error: null,
    snrLo: d.snrFreqMin, snrHi: d.snrFreqMax, fundRefDbFs };
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
    // THIS worker — the UI thread never pays for them.
    const win = d.samples.slice();
    pool[i].postMessage({
      type: 'partial', id: d.id, w: i, frameStart, frameEnd,
      samples: win, sharedParams: sp,
    }, [win.buffer]);
    frameStart = frameEnd;
  }
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
  if (g.error) { self.postMessage({ id: g.id, error: g.error }); return; }
  let r;
  try {
    const merged = analyzer.mergePartials(g.partials, g.sp);
    r = analyzer.finalize(merged, g.sp, g.snrLo, g.snrHi, g.fundRefDbFs, slot);
  } catch (err) { self.postMessage({ id: g.id, error: err.message }); return; }
  postResult(r, g.id, g.t0);
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
  postResult(r, d.id, t0);
};
