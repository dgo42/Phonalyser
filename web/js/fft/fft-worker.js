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
import {
  buildFinalizeJob, cal, displaySnapshot, dropCalCascade, finalizeForDisplay,
  finalizeJobTransfers, setCalRows,
} from './fft-finalize.js';
import { FftTransformPool, describeTransformRegime } from './fft-transform-pool.js';
import { describeErrorEvent, formatMs } from '../util/debug.js';

// The DIVIDED transform. A radix-2 transform of the tick's frame is the whole cost of an analysis
// at the large FFT lengths - one serial loop over twenty stages - and this splits each of those
// stages across lane workers that share the arrays outright. It needs cross-origin isolation to
// do that; without it every transform stays serial and nothing else changes (canDivideTransforms).
// The lane count is the Threads setting, pushed per dispatch: it is the transform this control
// divides now, so it finally has something to act on at a one- or two-frame tick.
// The URL is resolved ONCE and kept: it is what every lane is built from, and it is what the
// pool's failure warnings name - a lane set that never starts is learned from a timeout, which
// carries no file of its own.
const LANE_WORKER_URL = new URL('./fft-pool-worker.js', import.meta.url);
const transformPool = new FftTransformPool(1,
  () => new Worker(LANE_WORKER_URL, { type: 'module' }), LANE_WORKER_URL.href);
const dividedTransform = (re, im) => transformPool.transform(re, im);
// Said ONCE per session - this worker is built fresh for each one - at the first tick, when both
// the Threads setting and the transform size are known. See describeTransformRegime for why it
// is a warning and not a debug line.
let regimeReported = false;

const analyzer = new FftAnalyzer({ transform: dividedTransform });
const slot = new FftResult();   // reused pool slot - analyze/prelude/finalize write into it

// Time-domain discontinuity gate (Java FftAnalyzerWorker.timeDetector) - the scope's
// glitch detector run on the tick's raw window. A splice/dropout breaks the sinusoid
// recurrence decades above the noise floor even when its spectral footprint slips under
// the frequency-domain gates (and vice versa), so the scope trigger and the FFT rejection
// agree on what counts as a damaged block. The detector pass runs HERE (the raw window is
// transferred to this worker); the controller applies the re-sync recovery off the flag.
const timeDetector = new TimeDiscontinuityDetector();

// ---------------------------------------------------------------------------
// THE DISPLAY-PREPARATION SEAM. The stage itself lives in fft-finalize.js; what is here is the
// decision of WHERE it runs and the job that carries a tick to it.
//
// The desktop analyses on one thread and prepares the display on another, over an event bus, so
// a displayed tick costs the same cadence as one that is only folded. This is that split: an
// ACCUMULATED tick is handed to fft-finalize-worker.js, which answers the main thread directly
// over a port, while this worker goes straight on to the next window.
//
// TWO CASES STAY HERE, by design and not by omission:
//   - A tick with NO average behind it (averaging off). There is no accumulator, so there are no
//     sums to send - what is displayed is the tick's OWN spectrum, which lives in the four
//     per-window arrays. Sending those would be sending the whole result to save the cheap case:
//     no overlay to run, and one window's statistics.
//   - The FALLBACK, when a nested worker cannot be built (a context that refuses one, and node,
//     where the tests drive these modules directly). The same imported stage runs in place, so
//     the behaviour is exactly what it was before the split - only slower.
// ---------------------------------------------------------------------------

/** The display-preparation worker, or null while it has not been needed or could not be built. */
let finalizeWorker = null;
/** False once building one has failed - the fallback then runs for the rest of the session. */
let finalizePipelineOk = true;

/** The display worker's script, resolved once: what it is built from, and what its failures name.
 *  A load failure reports the file that actually failed - which is the module the shell imports
 *  as often as the shell itself - so both are stated. */
const FINALIZE_WORKER_URL = new URL('./fft-finalize-worker.js', import.meta.url);

/** Builds the display worker on first use, or answers null when it cannot be built. */
function ensureFinalizeWorker() {
  if (finalizeWorker || !finalizePipelineOk) return finalizeWorker;
  try {
    const w = new Worker(FINALIZE_WORKER_URL, { type: 'module' });
    w.onerror = (ev) => {
      // A script that 404s does NOT throw out of the constructor above - the worker is created and
      // then fails to load, asynchronously. So this is where a missing file is learned, and any
      // job already sent is lost with it. Say so ONCE - with everything the event carries, since
      // the file it names is the whole diagnosis - latch the fallback for the session, and TELL
      // THE OTHER SIDE at once: it is holding a frame slot open for an answer that will never
      // come, and without this it would wait out its whole watchdog before showing anything.
      console.warn('FFT display worker failed to load: ' + describeErrorEvent(ev)
        + ' [worker script ' + FINALIZE_WORKER_URL.href + ']'
        + ' - the display is prepared on the analysis thread for this session');
      finalizePipelineOk = false;
      try { w.terminate(); } catch (_) {}
      if (finalizeWorker === w) finalizeWorker = null;
      self.postMessage({ kind: 'finalizeFailed' });
    };
    // Its answers go straight to the main thread: this worker is busy with the next window, and a
    // finished frame that waited for this event loop would arrive a whole analysis late.
    const channel = new MessageChannel();
    w.postMessage({ port: channel.port1 }, [channel.port1]);
    self.postMessage({ kind: 'displayPort', port: channel.port2 }, [channel.port2]);
    // The rows it must correct with - this worker holds the same set for its own two cases.
    w.postMessage({ calRows: cal.rows });
    finalizeWorker = w;
  } catch (e) {
    console.warn('FFT display worker could not be built: ' + describeErrorEvent(e)
      + ' [worker script ' + FINALIZE_WORKER_URL.href + ']'
      + ' - the display is prepared on the analysis thread');
    finalizePipelineOk = false;
    finalizeWorker = null;
  }
  return finalizeWorker;
}

/** Hands an accumulated tick to the display worker. The job and the transfer list are BUILT BY
 *  THE STAGE ITSELF (fft-finalize.js), which is also what unpacks them on the other side, so the
 *  two halves of one seam cannot name their fields differently: the scalars are swept there
 *  rather than listed, and the list of buffers a job owns is derived from the job. A null job
 *  means there is no average behind the tick to send. Answers whether the tick was taken - a
 *  false says the caller must prepare the display itself. */
function sendFinalizeJob(r, d, id, epoch, reemit) {
  const w = ensureFinalizeWorker();
  if (!w) return false;
  const job = buildFinalizeJob(r, d, { id, epoch, reemit }, primaryAccum);
  if (job == null) return false;
  w.postMessage({ job }, finalizeJobTransfers(job));
  return true;
}

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
 *  (mirror of FftController._fundamentalBins) - the fold's reject() reads them. */
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

/** The cross-tick accumulator - the fold itself, running beside the analysis rather than on the
 *  main thread. The controller keeps every RECOVERY the gates ask for: it owns the capture
 *  cursor, the epoch and the drain skip, none of which exist here. */
const primaryAccum = new FftAccumulator();

/** Folds this tick in and answers what the controller must act on: which gate fired (if any)
 *  and whether the tick was accumulated. Same order as the desktop - the cheap time-domain
 *  gate on the raw window first, then the spectral one, each taking the previous tick back out
 *  before the controller re-syncs. */
function foldTick(d, r, timeDisc) {
  // A restart-from-zero empties the average AND drops the cascade with it: the analysed channel
  // and the tone positions the cascade was keyed on both move with a restart (the desktop's view
  // drops its own held response in resetStatistics for the same reason).
  if (d.resetAccum) {
    primaryAccum.reset();
    dropCalCascade();
    // The same one-shot, carried one hop further: the display worker holds the cascade the
    // pipelined frames are corrected with, and a restart moves every key it was built on.
    if (finalizeWorker) finalizeWorker.postMessage({ dropCal: true });
  }
  if (d.resyncAccum) primaryAccum.onResync();
  primaryAccum.setStrongToneRelDb(d.strongToneRelDb ?? 100.0);
  if (!d.accumulate) return { gate: null, accumulated: false };
  if (d.timeGate && timeDisc) {
    primaryAccum.subtractPreviousTicks();
    return { gate: 'time', accumulated: false };
  }
  if (d.spectralGate
      && primaryAccum.reject(r.re, r.im, r.fftSize / 2, r.freqResolution, fundamentalBins(r))) {
    primaryAccum.subtractPreviousTicks();
    const g = primaryAccum.spectralDiagnostics;
    return {
      gate: 'spectral', accumulated: false,
      diag: { gates: g.lastGates, score: g.lastScore, scoreThresh: g.lastScoreThresh,
        powerDb: g.lastPowerDb, powerMed: g.lastPowerMed, powerThresh: g.lastPowerThresh,
        pedestal: g.lastPedestalExcess, pedestalThresh: g.lastPedestalThresh },
    };
  }
  const accumulated = primaryAccum.accumulate(r, d.winAbsStart, !!r.coherentAveraging, d.targetN);
  return { gate: null, accumulated };
}

/**
 * ONE line per analysed tick, stage by stage, printed only for a tick the page asked to be timed.
 * The stages of a tick are spread across this thread and the one that dispatched it, and the
 * question the line answers - where the time of a tick goes - can only be read from all of them
 * side by side: the window gathering measured by the dispatcher travels in with the window, the
 * windowing, the per-frame transforms, the de-rotation and the statistics come from the analyser
 * that just ran, and the fold, the display preparation and the post are measured here.
 *
 * console.log directly, and NOT the shared debug() helper: that helper reads a setting of the
 * page, and a worker has neither that storage nor a query string of its own to read it from -
 * which is why the switch travels with each window instead.
 *
 * @param d the tick message (its stated geometry and the dispatcher's own measurement).
 * @param st the analyser's stage milliseconds for this tick.
 * @param id the dispatch id, so a line can be matched to the window it describes.
 * @param marks {gateFold, display, post, total, path} - what was measured on this side.
 */
function logTickStages(d, st, id, marks) {
  const t = st.transformMs;
  const how = transformPool.divides(d.fftSize)
    ? ' (divided x' + transformPool.lanes + ' lanes)' : ' (serial)';
  let line = '[fft] tick ' + id + ' ' + d.fftSize + ' pts @' + d.sampleRate + ' Hz'
    + ' | 1 read ' + formatMs(d.readMs > 0 ? d.readMs : 0)
    + ' | 2 window ' + formatMs(st.windowMs)
    + ' | 3 fft1 ' + formatMs(t.length > 0 ? t[0] : 0)
    + ' | 4 fft2 ' + formatMs(t.length > 1 ? t[1] : 0) + how;
  if (t.length > 2) {
    let rest = 0;
    for (let i = 2; i < t.length; i++) rest += t[i];
    line += ' | 4b fft3.. ' + formatMs(rest) + ' over ' + (t.length - 2);
  }
  console.log(line
    + ' | 5 derotate ' + formatMs(st.derotateMs)
    + ' | 6 gate+fold ' + formatMs(marks.gateFold)
    + ' | 7 stats ' + formatMs(st.statsMs)
    + ' | 8 display ' + formatMs(marks.display) + ' ' + marks.path
    + ' | 9 post ' + formatMs(marks.post)
    + ' | total ' + formatMs(marks.total));
}

/** Compute the time-domain gate verdict, fold the tick, finalize it for display when the
 *  controller asked for one, then post. The finalize is skipped for a tick the display gate
 *  does not want - the fold still happens, so no depth is lost. */
function finishAndPost(r, d, id, t0) {
  // Null unless this tick is being timed, and every mark below is taken only then.
  const st = analyzer.stageMs;
  const tGate = st ? performance.now() : 0;
  const timeDisc = d.timeGate && timeDiscontinuity(d.samples, r);
  const fold = foldTick(d, r, timeDisc);
  const tFold = st ? performance.now() : 0;
  let finalized = false;
  let pending2 = false;
  let calGainDb = null;
  // Which of the display paths this tick took, for the log line: the pipeline, the in-place stage
  // a tick with no average behind it always uses, or that same stage reached because the pipeline
  // could not be built or has been latched off.
  let path = 'gated';
  if (fold.gate == null) {
    r.frameCount = d.accumulate ? primaryAccum.accumFrames : r.frameCount;
    r.coherentKappa = primaryAccum.hasData ? primaryAccum.accumKFractional : NaN;
    path = 'not asked';
    if (d.finalize) {
      // An ACCUMULATED tick goes down the pipeline - the display is prepared beside the next
      // analysis, not in front of it. Anything else is prepared here: a tick with no average has
      // no sums to send and only its own cheap statistics to redo, and a controller that has
      // latched the pipeline off asks for this path outright.
      const pipelined = fold.accumulated && !d.finalizeInPlace
        && sendFinalizeJob(r, d, id, d.epoch, false);
      if (pipelined) {
        pending2 = true;
        path = 'pipelined';
      } else {
        calGainDb = finalizeForDisplay(r, d, fold.accumulated ? (x) => primaryAccum.overlayOnto(x) : null);
        finalized = true;
        path = fold.accumulated ? 'in-place (fallback)' : 'in-place';
      }
    }
  }
  const tFinalize = st ? performance.now() : 0;
  postResult(r, id, t0, { timeDisc, fold, finalized, finalizePending: pending2, calGainDb });
  if (st) {
    const tEnd = performance.now();
    logTickStages(d, st, id, {
      gateFold: tFold - tGate, display: tFinalize - tFold,
      post: tEnd - tFinalize, total: tEnd - t0, path,
    });
  }
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

/** Rebuilds the display from the RAW accumulator alone and posts it, without folding a new
 *  window in - the answer to a calibration change made while the FFT is STOPPED, where no
 *  further tick would ever apply it. The accumulator survives the stop, and the last analyzed
 *  window's slot still carries the scalars the overlay + recompute rebuild from, so this is the
 *  same finalize a live tick runs, minus the fold. Refused (an empty answer, and the display
 *  keeps what it shows) with nothing accumulated yet, before the first window, while a pooled
 *  gather holds the slot, or while a display job is already outstanding - the answer to that one
 *  would arrive after this and overwrite it. The next request succeeds. */
function reemitDisplay(d) {
  if (pending || d.jobOutstanding || !primaryAccum.hasData || !(slot.fftSize > 0)) {
    self.postMessage({ reemit: true, empty: true });
    return;
  }
  const t0 = performance.now();
  slot.frameCount = primaryAccum.accumFrames;
  slot.coherentKappa = primaryAccum.accumKFractional;
  if (!d.finalizeInPlace && sendFinalizeJob(slot, d, -1, d.epoch, true)) {
    self.postMessage({ reemit: true, finalizePending: true });
    return;
  }
  const calGainDb = finalizeForDisplay(slot, d, (x) => primaryAccum.overlayOnto(x));
  postResult(slot, -1, t0, { timeDisc: false, fold: null, finalized: true, reemit: true, calGainDb });
}

/** Transferable snapshot of the FftResult fields the engine + the readout + the predistortion
 *  consume. Cloning the spectral arrays (not transferring `slot`'s) keeps the pool slot
 *  reusable next tick.
 *  `timeDisc` = the time-domain discontinuity verdict for this window (the controller mirrors
 *  Java's gate order off it - time gate before spectral); `fold` = this tick's fold verdict
 *  (null for a re-emit, which folds nothing); `finalized` = the result carries the display
 *  rebuild; `reemit` marks the answer to a stopped-state re-emit request. */
function postResult(r, id, t0, { timeDisc, fold, finalized, finalizePending, reemit, calGainDb }) {
  const head = {
    kind: 'analysis',
    id,
    ms: performance.now() - t0,
    timeDiscontinuity: !!timeDisc,
    // The fold's verdict + depth (the controller owns every recovery the gates ask for), and
    // whether this result already carries the display rebuild or a job is preparing one.
    gate: fold ? fold.gate : undefined,
    gateDiag: fold && fold.diag ? fold.diag : undefined,
    accumulated: fold ? fold.accumulated : undefined,
    accumFrames: primaryAccum.accumFrames,
    // THE DEPTH, IN TICKS, AND ITS ONE OWNER. The accumulator counts FRAMES, and a coherent tick
    // folds two of them; the readout the operator watches counts TICKS. Converting one into the
    // other needs the tick's own frames-per-tick, which only this side knows for certain - a
    // reader that has to infer it from whatever fields happen to have travelled will infer it
    // differently for two message shapes and report two numbers a factor of two apart for the
    // same average. So the conversion is done HERE, once, and what crosses is the answer.
    accumDepthTicks: Math.round(primaryAccum.accumFrames / (r.coherentAveraging ? 2 : 1)),
    finalized: !!finalized,
    finalizePending: !!finalizePending,
    reemit: !!reemit,
  };
  // A tick whose display was NOT prepared here carries counters only: the pipelined one is
  // answered by the display worker over its own port, and a declined one has nothing to show.
  if (!finalized) { self.postMessage(head); return; }
  const { out, transfer } = displaySnapshot(r, Object.assign(head, { calGainDb: calGainDb || null }));
  self.postMessage(out, transfer);
}


/** threads > 1: single-threaded prelude here, per-frame accumulation fanned out
 *  to the nested pool, merge + finalize back here - the exact stage split the
 *  old main-thread coordinator ran, now entirely off the UI thread. */
function analyzePooled(d, W, t0) {
  let sp;
  try {
    // Explicit geometry: the frame spacing is the consumer's drawn pending hop
    // and the frame count is stated - the window may be sized for the LARGEST
    // hop, so inferring frames from the buffer length would over-count under the
    // random overlap.
    sp = analyzer.prelude(d.samples, d.sampleRate, d.fftSize, d.harmonicCount,
      d.windowType, d.overlap, d.coherentAveraging, d.expectedFundHz, slot,
      d.step, d.frameCount);
  } catch (err) { self.postMessage({ id: d.id, error: err.message }); return; }

  const frameCount = sp.frameCount;
  const fundRefDbFs = d.fundRefDbFs === undefined ? NaN : d.fundRefDbFs;
  // WHICH frames are worth handing to another thread. The prelude above transformed the
  // leading frames to find the fundamental and its sub-bin position, and this analyzer reads
  // those back instead of transforming them again - they cost a de-rotation pass and nothing
  // more. A sub-worker cannot: it runs its own analyzer and would transform the frame from
  // scratch, on its own copy of the window. So the split covers ONLY the frames the prelude
  // left behind, and never across more workers than there are of them.
  const heldFrames = Math.min(frameCount, analyzer.preludeFrameCount(d.samples, sp));
  const width = Math.max(1, Math.min(W, frameCount - heldFrames));
  // Nothing to split - which at a geometry of one or two frames per tick is every tick, since
  // the prelude has then already transformed all of them. Accumulate + finalize here.
  if (width < 2) {
    let r;
    try {
      const part = analyzer.accumulatePartial(d.samples, 0, frameCount, sp);
      r = analyzer.finalize(part, sp, d.snrFreqMin, d.snrFreqMax, fundRefDbFs, slot);
    } catch (err) { self.postMessage({ id: d.id, error: err.message }); return; }
    finishAndPost(r, d, d.id, t0);
    return;
  }

  ensurePool(width);
  // The held frames are this worker's own partial, kept FIRST so the merge still adds the
  // ranges in ascending frame order; only the sub-worker ranges are waited for.
  const offset = heldFrames > 0 ? 1 : 0;
  // `samples` kept by reference for the post-gather time-domain gate (the sub-workers
  // got transferred COPIES, so d.samples stays intact through the gather).
  pending = { id: d.id, t0, sp, partials: new Array(offset + width), offset,
    got: 0, want: width, error: null,
    snrLo: d.snrFreqMin, snrHi: d.snrFreqMax, fundRefDbFs,
    samples: d.samples, timeGate: !!d.timeGate, d };
  // Contiguous frame ranges over what is left: the first (splittable % width) ranges carry one
  // extra frame so the union covers [heldFrames, frameCount) with no gaps/overlap.
  const splittable = frameCount - heldFrames;
  const base = Math.floor(splittable / width);
  const rem = splittable % width;
  let frameStart = heldFrames;
  for (let i = 0; i < width; i++) {
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
  // The held frames' own de-rotation pass, run here while the pool works. It cannot throw a
  // geometry error - the prelude already validated the same range.
  if (offset > 0) pending.partials[0] = analyzer.accumulatePartial(d.samples, 0, heldFrames, sp);
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
  else { g.partials[g.offset + p.w] = { sumRe: p.sumRe, sumIm: p.sumIm, acceptedFrames: p.acceptedFrames }; }
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
  // CONTROL messages (no window attached): the loaded calibration rows changed - resolved for
  // the analysed channel by the side that owns that channel - or the display is to be rebuilt
  // from the average alone, a calibration change made while the FFT is stopped. A restart of the
  // statistics reaches the held cascade through the fold's own resetAccum one-shot.
  if (!d.samples) {
    if (d.calRows !== undefined) {
      // Applied to THIS thread's holder - which the two cases that finalize here correct with -
      // and relayed to the display worker's, so whichever path runs has the same cascade.
      setCalRows(d.calRows);
      if (finalizeWorker) finalizeWorker.postMessage({ calRows: d.calRows || [] });
    }
    if (d.reemit) reemitDisplay(d);
    return;
  }
  const t0 = performance.now();
  // Per-stage timing for THIS tick, armed from the switch that travelled with the window. It is a
  // setting of the page, and this thread can read neither the storage it lives in nor a query
  // string of its own - so it is stated per tick. Disarmed, every stage below costs a null test.
  analyzer.startStageTiming(!!d.debug);
  // The Threads setting reaches the divided transform here, between ticks - never during one,
  // since the controller keeps a single analysis in flight. A changed count rebuilds the lanes;
  // an unchanged one costs nothing.
  transformPool.setLanes(Math.max(1, d.threads | 0));
  // ...and the lanes are SPAWNED here too, in the background, rather than inside the first
  // transform that wants them: a lane worker takes a moment to load, and paying that where the
  // transform waits for it put most of a second into the first tick of a session. Ticks until
  // they announce themselves simply run serial.
  transformPool.warmUp(d.fftSize);
  // Java parity (FftAnalyzerWorker): the full statistics - THD, SNR, SINAD, the noise sweeps - are
  // DISPLAY-gated, not per-tick. They are needed on this thread only for a tick that is displayed
  // AND prepared here; a pipelined tick has them recomputed where its display is prepared, and a
  // tick no one shows needs none of them. The spectrum, the refined fundamental, the harmonic bins
  // and the raw phasors are produced either way, which is everything the fold, the gates and the
  // frequency lock read.
  analyzer.setSpectrumOnly(!(d.finalize && (!d.accumulate || d.finalizeInPlace)));
  if (!regimeReported) {
    regimeReported = true;
    console.warn(describeTransformRegime(transformPool.lanes, d.fftSize));
  }
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
      // Stated geometry (see analyzePooled): the drawn hop and the frame count.
      d.expectedFundHz, slot, d.step, d.frameCount);
  } catch (err) {
    self.postMessage({ id: d.id, error: err.message });
    return;
  }
  finishAndPost(r, d, d.id, t0);
};

// The fold and the accumulator it keeps. The message seam above answers whole ticks only, so
// these are named here for the tests that ask one question at a time - what a gated tick does to
// the collected depth. The cascade and its rebuild rule moved to fft-finalize.js and are named
// there.
export { foldTick, primaryAccum };
