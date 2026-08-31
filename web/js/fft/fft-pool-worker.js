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
import { runStageSlice } from '../dsp/fft.js';
import {
  CTRL_GENERATION, CTRL_DONE, CTRL_MODE, CTRL_LEN, CTRL_SIZE, CTRL_LANES, CTRL_READY,
  CTRL_KCHUNK, MODE_END,
} from './fft-transform-lanes.js';

const analyzer = new FftAnalyzer();

/** Runs THIS lane's share of one transform, stage by stage, until the coordinator ends it.
 *  The whole exchange happens in the shared control block (fft-transform-lanes.js): the lane
 *  sleeps until the generation changes, runs the slice of that stage it derives for itself,
 *  reports done and sleeps again. It does not return to the message loop meanwhile - which is
 *  the point: twenty stages of a million-point transform are twenty barriers, not twenty
 *  messages. */
function runTransformLane(ctrl, re, im, lane) {
  // Capture the generation BEFORE announcing readiness: the coordinator waits for every lane to
  // be counted here, so no lane can mistake the first stage's bump for one it has already seen.
  let seen = Atomics.load(ctrl, CTRL_GENERATION);
  Atomics.add(ctrl, CTRL_READY, 1);
  Atomics.notify(ctrl, CTRL_READY);
  for (;;) {
    Atomics.wait(ctrl, CTRL_GENERATION, seen);
    seen = Atomics.load(ctrl, CTRL_GENERATION);
    const ending = Atomics.load(ctrl, CTRL_MODE) === MODE_END;
    if (!ending) {
      runStageSlice(re, im, Atomics.load(ctrl, CTRL_SIZE), Atomics.load(ctrl, CTRL_LEN),
        lane, Atomics.load(ctrl, CTRL_LANES), Atomics.load(ctrl, CTRL_KCHUNK) === 1);
    }
    Atomics.add(ctrl, CTRL_DONE, 1);
    Atomics.notify(ctrl, CTRL_DONE);
    if (ending) return;
  }
}

self.onmessage = (e) => {
  const d = e.data;
  if (d.type === 'transform') {
    // The buffers are SHARED, not copied: this lane writes into the very arrays the coordinator
    // and its siblings are writing into, each in its own disjoint slice of the stage.
    runTransformLane(new Int32Array(d.ctrl), new Float64Array(d.re), new Float64Array(d.im), d.lane);
    return;
  }
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
