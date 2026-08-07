/*
 * Phonalyser web - FFT analyzer POOL worker (one of W parallel accumulators).
 * Parallelises the AVERAGING FRAMES: the coordinator runs the single-threaded
 * prelude (shared kFractional / kappa2 / IMD grid) once, then broadcasts those
 * sharedParams to every pool worker. Each worker accumulates ITS contiguous
 * frame range [frameStart, frameEnd) via FftAnalyzer.accumulatePartial - the
 * exact same per-frame derotation as the serial path, keyed off the GLOBAL frame
 * index - and posts its partial {sumRe,sumIm} back as transferables. The
 * coordinator sums the partials (associative) and runs finalize. Heavy per-frame
 * FFTs stay off the main thread.
 * GNU AGPL v3 or later.
 */
import { FftAnalyzer } from './fft-analyzer.js';

const analyzer = new FftAnalyzer();

self.onmessage = (e) => {
  const d = e.data;
  if (d.type !== 'partial') return;
  const t0 = performance.now();
  let part;
  try {
    part = analyzer.accumulatePartial(d.samples, d.frameStart, d.frameEnd, d.sharedParams);
  } catch (err) {
    self.postMessage({ id: d.id, w: d.w, error: err.message });
    return;
  }
  const out = {
    id: d.id, w: d.w, ms: performance.now() - t0,
    sumRe: part.sumRe, sumIm: part.sumIm, acceptedFrames: part.acceptedFrames,
  };
  self.postMessage(out, [out.sumRe.buffer, out.sumIm.buffer]);
};
