/*
 * Phonalyser web - stop-after-N Scope+FFT FREEZE repro / regression (no browser).
 *
 * Temp (_-prefixed) test for the fix in js/audio/fft-controller.js to the user report
 * "During add last FFT frame to average Scope & FFT freeze!": when ∞-mode FFT averaging
 * hits the stop-after-N cap, BOTH scope and FFT froze (main-thread render starved).
 *
 * Two defects, both traced in _onWorkerResult / the per-batch feed:
 *   1. RE-FIRE - the stop-after-N trip had no `!this._fftPausedByStopN` guard, so it
 *      re-fired onFftAutoStopped() on EVERY subsequent tick. Java (FftAnalyzerWorker
 *      lines 1830-1838) publishes FFT_RECORDING_AUTO_STOPPED once, then paused.set(true)
 *      makes workerLoop's `if (!paused.get())` skip doAnalysis() forever after.
 *   2. UNTHROTTLED BURST - showNow was force-trued while paused, so the heavy O(N)
 *      finalize ran on every tick until the async setFftRecording(false) released the
 *      reader - an unthrottled window that starved the scope rAF. Java pauses the WORKER
 *      when stop-after-N trips (no further ticks); the web must pause the FEED to match.
 *
 * This drives the REAL FftController._onWorkerResult (fire-once guard + showNow gate)
 * and the REAL feedFft (feed-gate) and _setupFftAnalysis (restart reset). The only stubs
 * are at the worker/capture boundary - the FFT accumulator (its reject/accumulate is what
 * the off-thread worker does) and the shared-capture reader - so the three defended
 * predicates run against the actual object state, not a reimplementation.
 *
 * This is the smallest faithful slice: the live worker+reader dispatch is too coupled to
 * exercise headless, so we test the fire-once guard predicate + the feed-gate predicate +
 * the restart reset directly, driving the real methods that carry them.
 *
 * Run: node test/e2e/_fft-autostop-test.mjs   (exits non-zero on any FAIL)
 */
import { FftController } from '../../js/fft/fft-controller.js';
import { MessageBus } from '../../js/bus/message-bus.js';
import { Events } from '../../js/bus/events.js';
import { testPrefs } from '../_test-prefs.mjs';

let pass = 0, fail = 0;
const ok = (cond, msg) => { if (cond) { pass++; console.log('PASS: ' + msg); } else { fail++; console.log('FAIL: ' + msg); } };

const STOP_AFTER_N = 5;
const EXTRA_TICKS = 3;   // drive the tick count PAST the cap

// ── Worker/capture-boundary stubs (everything else is the real controller) ──────
// The cross-tick fold lives in the analyzer worker; what comes back per tick is its verdict -
// the depth it reached. Modelled here as one folded frame per tick (perTickFrames 1), so the
// depth readout climbs one per accepted tick exactly as the worker's answer makes it.
let folded = 0;

// A minimal per-tick result message in ∞ (coherent) mode. The stop-after-N +
// showNow gate + fire-once guard are reached before any spectral finalize that
// would need real arrays; the display rebuild runs in the worker and is not asked for here.
function tickMsg() {
  folded += 1;
  return {
    error: null, ms: 1, coherentAveraging: true,
    fftSize: 4096, freqResolution: 48000 / 4096, frameCount: 1,
    accumFrames: folded, accumDepthTicks: folded,
    fundamentalHzRefined: 1000, fundamental2HzRefined: -1,
    re: new Float64Array(1), im: new Float64Array(1),
    amplitudeDbFs: new Float64Array(1),
  };
}

/** Counts the analyser's self-stop announcements (FFT_RECORDING_AUTO_STOPPED - the one seam
 *  the pane's teardown subscribes to) until off() is called. */
function autoStops() {
  let n = 0;
  const listener = () => { n++; };
  MessageBus.instance().subscribe(Events.FFT_RECORDING_AUTO_STOPPED, listener);
  return { count: () => n, off: () => MessageBus.instance().unsubscribe(Events.FFT_RECORDING_AUTO_STOPPED, listener) };
}

// Fake reader whose backlog is HIGH (writePos - readPos large) so the DISPLAY_MIN
// gate stays closed for a normal tick - proving the ONLY thing that force-shows the
// paused tick is the pause flag, and that no burst follows once paused.
// isFinished / takeFinishedReasonForReport: the capture-death seam the feed consults first - a
// live lane answers "not finished".
const reader = {
  getWritePos: () => 1_000_000, getReadPos: () => 0, getReadPos_: 0,
  isFinished: () => false, takeFinishedReasonForReport: () => null,
  available: () => 0,   // nothing new to gather - the feed the result handler re-enters returns at once
};

function makeController() {
  const gen = {
    snapped: 1000, binW: 48000 / 4096, genNode: null, running: false,
    genFreq: 1000, fllErrHz: 0, fllLocked: false, fllStable: 0, rejectedCount: 0,
    computeAnalysisFreqs() {},
  };
  const prefs = testPrefs({
    fftLength: 4096, inputSampleRate: 48000, fftAverages: Infinity, fftOverlap: 'PCT_0',
    fftStopAfterNEnabled: true, fftStopAfterN: STOP_AFTER_N,
    fftCoherentAveraging: true, fftAlignGenerator: 'NONE', fftMainsSuppression: 'NONE',
    fftStrongToneRelDb: 100, fftThreads: 1, fftWindow: 'BH4', genSignalForm: 'SINE',
  });
  const c = new FftController({ acquire: async () => reader, release: async () => {} }, gen, prefs, {});
  // Put the controller into the exact ∞-mode state _onWorkerResult keys off, without
  // spawning a real Worker/AudioWorklet (headless has neither): foreverMode + ringN.
  c._fftOn = true;
  c._fftReader = reader;
  c.foreverMode = true;
  c.ringN = 1;
  c.avgTarget = Infinity;
  c.bufLen = 4096;
  c.N = 4096;
  c.minHop = 4096; c.maxHop = 4096; c.pendingHop = 4096; c.perTickFrames = 1;
  folded = 0;   // a fresh session starts an empty fold
  c._accumEpoch = 0;
  c._tickEpoch = 0;
  c._lastShowMs = performance.now();   // gate closed (just shown) for the non-paused ticks
  return c;
}

console.log('\n== stop-after-N freeze regression ==');
console.log(`stopAfterN=${STOP_AFTER_N}, drive ${STOP_AFTER_N + EXTRA_TICKS} ticks past the cap\n`);

// ── (1) FIRE-ONCE: onFftAutoStopped called EXACTLY once across N + extra ticks ──
{
  const c = makeController();
  const stops = autoStops();

  for (let t = 0; t < STOP_AFTER_N + EXTRA_TICKS; t++) {
    c._tickEpoch = c._accumEpoch;   // pass the epoch gate (fresh, no reset in flight)
    c._onWorkerResult(tickMsg());
  }
  stops.off();

  ok(stops.count() === 1,
    `FFT_RECORDING_AUTO_STOPPED published EXACTLY once over ${STOP_AFTER_N + EXTRA_TICKS} ticks (was ${stops.count()})`);
  ok(c.pausedByStopN === true,
    `controller latched _fftPausedByStopN after the cap (pausedByStopN=${c.pausedByStopN})`);
  ok(c.completedAnalyses() >= STOP_AFTER_N,
    `tick count reached the cap: completedAnalyses=${c.completedAnalyses()} >= ${STOP_AFTER_N}`);
}

// ── (2) FEED-GATE: ZERO further dispatch work once paused ───────────────────────
// feedFft() is the per-batch feed entry the SharedCapture fan-out calls; the feed
// gate must make it a no-op the instant stop-after-N latches the pause, so no window
// buffers/dispatches (the unthrottled burst that starved the scope). Prove it by
// spying on _accumulate - the very next thing feedFft does after the reader read.
{
  const c = makeController();
  let accumulateCalls = 0;
  const realAccumulate = c._accumulate.bind(c);
  c._accumulate = (s, n) => { accumulateCalls++; return realAccumulate(s, n); };

  // Not paused yet: feedFft must PROCEED (reach the reader). Give the reader some
  // fresh samples so a real feed would buffer+dispatch.
  let readPos = 0;
  const feedReader = {
    getWritePos: () => 1_000_000, getReadPos: () => readPos,
    isFinished: () => false, takeFinishedReasonForReport: () => null,
    available: () => 0,   // 0 => feedFft returns after the drain checks, before _accumulate
    read: () => 0, seekToLatest() { readPos = 500000; },
  };
  c._fftReader = feedReader;
  c._drainSkipRemaining = 0;

  c._fftPausedByStopN = false;
  c.feedFft();   // not paused: proceeds past the gate (available()===0 => early return, but PAST the gate)
  // Now latch the pause and confirm feedFft short-circuits at the TOP - no reader
  // touched, no buffering, no dispatch.
  c._fftPausedByStopN = true;
  let readerTouched = false;
  const guardedReader = new Proxy(feedReader, { get(t, p) { readerTouched = true; return t[p]; } });
  c._fftReader = guardedReader;
  for (let t = 0; t < EXTRA_TICKS; t++) c.feedFft();

  ok(accumulateCalls === 0,
    `no window buffered/dispatched while paused: _accumulate called ${accumulateCalls} times (expected 0)`);
  ok(readerTouched === false,
    `feedFft short-circuits at the top while paused - reader untouched (touched=${readerTouched})`);
}

// ── (3) RESTART clears the pause and resumes the feed ───────────────────────────
// A fresh setRecording(true)/_setupFftAnalysis MUST clear _fftPausedByStopN before the
// feed resumes, else the new feed-gate permanently blocks the restart.
{
  const c = makeController();
  c._fftPausedByStopN = true;   // as if a prior round auto-stopped

  // _setupFftAnalysis is the start path setRecording(true) runs before acquiring the
  // reader + feeding (fft-controller.js: setRecording on-branch calls _setupFftAnalysis
  // FIRST). It must reset the pause. It spawns a Worker, which headless lacks - stub the
  // Worker global so the setup completes without a browser.
  globalThis.Worker = class { constructor() {} postMessage() {} terminate() {} set onmessage(_) {} set onerror(_) {} };
  c._setupFftAnalysis();

  ok(c.pausedByStopN === false,
    `_setupFftAnalysis (setRecording start path) cleared the pause (pausedByStopN=${c.pausedByStopN})`);

  // With the pause cleared, feedFft no longer short-circuits at the top.
  let reached = false;
  c._fftReader = {
    isFinished: () => false, takeFinishedReasonForReport: () => null,
    available: () => { reached = true; return 0; }, getReadPos: () => 0, getWritePos: () => 0,
    seekToLatest() {},   // the round-boundary reset below re-anchors the window on the cursor
  };
  c._drainSkipRemaining = 0;
  c.feedFft();
  ok(reached === true,
    `feed resumes after restart: feedFft proceeds past the gate to the reader (reached=${reached})`);

  // Also confirm the predistortion round-boundary reset (resetAnalyses -> Java
  // resetStatistics paused.set(false)) clears the pause, so a new round can't starve.
  c._fftPausedByStopN = true;
  c.resetAnalyses();
  ok(c.pausedByStopN === false,
    `resetAnalyses (predistortion round boundary) also cleared the pause (pausedByStopN=${c.pausedByStopN})`);
}

// ── (4) INFINITE TARGET + stop-after-N enabled MID-RUN once N already reached ───
// #9: with an ∞ (forever) averaging target and stop-after-N, the FFT must auto-stop
// once the tick count reaches N - even if stop-after was toggled on AFTER N ticks had
// already accumulated. Simulate: run past N with stop-after DISABLED (no stop), then
// enable it and confirm the very next tick trips the auto-stop exactly once. The controller
// reads the stop-after preference on every tick - headless we write the preference directly,
// as the stop-after toggle does, and drive the real _onWorkerResult.
{
  const c = makeController();
  c._prefs.fftStopAfterNEnabled.set(false);   // stop-after OFF while N ticks accumulate
  const stops = autoStops();

  // Run WELL past N with stop-after disabled - must NOT auto-stop (∞ keeps averaging).
  for (let t = 0; t < STOP_AFTER_N + EXTRA_TICKS; t++) {
    c._tickEpoch = c._accumEpoch;
    c._onWorkerResult(tickMsg());
  }
  ok(stops.count() === 0 && c.pausedByStopN === false,
    `∞ target keeps running while stop-after is OFF past N (ticks=${c.completedAnalyses()}, stops=${stops.count()})`);

  // Now enable stop-after (N already exceeded) - the next tick must auto-stop promptly, once.
  c._prefs.fftStopAfterNEnabled.set(true);
  c._tickEpoch = c._accumEpoch;
  c._onWorkerResult(tickMsg());
  ok(stops.count() === 1 && c.pausedByStopN === true,
    `enabling stop-after with N already reached auto-stops on the next ∞ tick (stops=${stops.count()})`);

  // Further ticks would be gated by the feed pause in production; a stray _onWorkerResult
  // must still not re-fire the auto-stop (fire-once guard).
  c._tickEpoch = c._accumEpoch;
  c._onWorkerResult(tickMsg());
  stops.off();
  ok(stops.count() === 1, `no re-fire after the ∞ mid-run auto-stop (stops=${stops.count()})`);
}

console.log(`\nfft-autostop: ${pass} passed, ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
