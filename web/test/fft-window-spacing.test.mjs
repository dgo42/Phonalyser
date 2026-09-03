/*
 * Phonalyser web - the analysed windows are spaced by EXACTLY the hop.
 *
 * Java's FFT worker gathers and analyses on one thread: a window that comes due is
 * always the next one analysed, so the spacing between analysed windows is the hop,
 * every tick. The web analyses asynchronously, so the only way to hold that
 * invariant is to consume no capture while an analysis is in flight - the backlog
 * waits in the ring instead of being read and discarded.
 *
 * Vocabulary: a TICK is one analysis cycle; the HOP is the fresh samples between
 * two successive analysed window starts; "due" means the ring has gathered a whole
 * hop since the last dispatch. A dropped window would space the analysed sequence
 * by hop x (1 + drops) - an irregular multiple set by main-thread load, which is a
 * randomised overlap at every setting.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';

import { FftController } from '../js/fft/fft-controller.js';
import { testPrefs } from './_test-prefs.mjs';
import { hopSamples } from '../js/enums/fft-overlap.js';
import { MessageBus } from '../js/bus/message-bus.js';
import { Events } from '../js/bus/events.js';

const SR = 48000;
const FFT = 1024;
const OVERLAP = 'PCT_75';
const HOP = hopSamples(OVERLAP, FFT);        // 256
const PER_TICK_FRAMES = 2;
const BUF_LEN = FFT + (PER_TICK_FRAMES - 1) * HOP;

/** A capture reader holding `total` samples, handing them out on demand. */
function fakeReader(total) {
  let readPos = 0;
  return {
    _writePos: total,
    isFinished: () => false,
    takeFinishedReasonForReport: () => null,
    available() { return this._writePos - readPos; },
    read(n, left, right) {
      const dst = left || right;
      const take = Math.min(n, this._writePos - readPos);
      for (let i = 0; i < take; i++) if (dst) dst[i] = Math.sin(readPos + i);
      readPos += take;
      return take;
    },
    getReadPos: () => readPos,
    getWritePos() { return this._writePos; },
    seekToLatest() { readPos = this._writePos; },
  };
}

/** A controller wired to stubs, with the fold replaced by a no-op so the test
 *  isolates the feed/dispatch seam. `analysisFeeds` is how many feedFft calls the
 *  analysis takes to come back - the "slow analyzer" the invariant must survive. */
function controllerWithSlowAnalyzer(analysisFeeds, totalSamples) {
  const prefs = testPrefs({
    inputSampleRate: SR, fftLength: FFT, fftOverlap: OVERLAP, fftAverages: Infinity,
    fftCoherentAveraging: true, fftThreads: 1, fftChannel: 'R', fftMainsSuppression: 'NONE',
    genSignalForm: 'SINE', fftFundFromGenerator: false, fftManualFundEnabled: false,
    fftStrongToneRelDb: 100, fftDetectTimeDiscontinuity: false,
  });
  const gen = {
    snapped: 1000, binW: SR / FFT, genNode: null, running: false,
    computeAnalysisFreqs() {}, _genEmitFreq2: () => 0,
  };
  const c = new FftController({}, gen, prefs, {});
  // Real geometry, without opening a worker or a capture.
  c.N = FFT;
  c.minHop = HOP; c.maxHop = HOP; c.pendingHop = HOP; c.perTickFrames = PER_TICK_FRAMES;
  c.bufLen = BUF_LEN;
  c.buf = new Float64Array(BUF_LEN);
  c.bufW = 0; c.bufFilled = 0;
  c.foreverMode = true; c.ringN = 1; c.avgTarget = Infinity;
  c.warmup = 0;
  c._refill = HOP;
  c._winAbsStart = 0; c._dispatchedOnce = false;
  c._busy = false; c._dispatchId = 0; c.dropped = 0;
  c._wantLeft = false;
  c._accumEpoch = 0;
  c._fftOn = true;
  c._fftReader = fakeReader(totalSamples);
  // The fold is not what this test is about; leave only the busy-clear + resume, and
  // record what the seam was handed so an error can be recognised.
  let handled = null;
  c._handleWorkerResult = (r) => { handled = r; };

  const posted = [];
  let pending = null, sinceDispatch = 0;
  c.worker = {
    postMessage(msg) { posted.push(msg); pending = msg; sinceDispatch = 0; },
  };
  /** One scheduler step: feed, then let a slow analysis finish when its time is up. */
  const step = () => {
    c.feedFft();
    if (pending) {
      sinceDispatch++;
      if (sinceDispatch >= analysisFeeds) { pending = null; c._onWorkerResult({ id: 0 }); }
    }
  };
  /** One scheduler step whose analysis FAILS at the worker level instead of replying -
   *  the top-level onerror path, which posts no result at all. */
  const stepFailing = () => {
    c.feedFft();
    if (pending) { pending = null; c._onWorkerError({ message: 'boom' }); }
  };
  return { c, posted, step, stepFailing, lastHandled: () => handled };
}

test('every window that comes due is analysed - none is dropped', () => {
  // 40 hops of capture, an analysis that takes 5 feed calls to return. On a
  // consume-and-discard dispatch the intervening feeds would eat the backlog and
  // the dropped windows would never be analysed.
  const TOTAL = BUF_LEN + 40 * HOP;
  const { c, posted, step } = controllerWithSlowAnalyzer(5, TOTAL);
  for (let i = 0; i < 4000; i++) step();
  assert.equal(c.dropped, 0, 'no window was dropped');
  assert.ok(posted.length >= 40, `every due window reached the analyzer (${posted.length} of 41)`);
});

test('the analysed windows advance by EXACTLY the hop, never a multiple', () => {
  const TOTAL = BUF_LEN + 40 * HOP;
  const { posted, step } = controllerWithSlowAnalyzer(5, TOTAL);
  for (let i = 0; i < 4000; i++) step();
  const starts = posted.map(m => m.winAbsStart);
  assert.equal(starts[0], 0, 'the first analysed window starts the run');
  const deltas = [];
  for (let i = 1; i < starts.length; i++) deltas.push(starts[i] - starts[i - 1]);
  const distinct = [...new Set(deltas)];
  assert.deepEqual(distinct, [HOP],
    `every spacing is the hop ${HOP}; saw ${JSON.stringify(distinct)}`);
});

test('the stated frame spacing travels with every window', () => {
  const TOTAL = BUF_LEN + 20 * HOP;
  const { posted, step } = controllerWithSlowAnalyzer(3, TOTAL);
  for (let i = 0; i < 2000; i++) step();
  for (const m of posted) {
    assert.equal(m.step, HOP, 'the analysis states the hop as its frame spacing');
    assert.equal(m.frameCount, PER_TICK_FRAMES);
  }
});

test('a slower analysis changes the RATE but not the spacing', () => {
  // The invariant must not depend on how far behind the analyser runs.
  const TOTAL = BUF_LEN + 30 * HOP;
  for (const slow of [1, 3, 9, 25]) {
    const { posted, step } = controllerWithSlowAnalyzer(slow, TOTAL);
    for (let i = 0; i < 6000; i++) step();
    const starts = posted.map(m => m.winAbsStart);
    const distinct = [...new Set(starts.slice(1).map((v, i) => v - starts[i]))];
    assert.deepEqual(distinct, [HOP],
      `analysis taking ${slow} feeds still spaces windows by ${HOP}`);
  }
});

test('a TOP-LEVEL worker error does not strand the feed', () => {
  // The worker fails outside a posted result - a module that will not load, an
  // uncaught throw on the analysis thread. Nothing replies, so unless that path
  // clears the in-flight flag the feed's busy gate stops the consumer for good; and
  // because the gate sits before the OVERRUN check, no overrun would ever be
  // detected either. The FFT would go quiet with nothing said after the first line.
  const TOTAL = BUF_LEN + 20 * HOP;
  const { c, posted, step, stepFailing, lastHandled } = controllerWithSlowAnalyzer(1e9, TOTAL);
  step();
  assert.equal(posted.length, 1, 'the first window went out');
  assert.ok(c._busy, 'and its analysis is in flight');

  c._onWorkerError({ message: 'boom' });
  // The flag is cleared and the feed resumes inside that call, and the resume finds a
  // backlog waiting - so by the time it returns the NEXT window is already out and
  // the flag is legitimately set again. What proves the strand is gone is that a
  // second window was dispatched at all: on a handler that only logs, nothing clears
  // the flag, feedFft returns at its busy gate and this count stays at one forever.
  assert.ok(posted.length > 1,
    `the feed resumed and dispatched the next window (${posted.length} dispatched)`);
  assert.match(String(lastHandled().error), /fft-worker/,
    'and the failure arrives through the same seam a posted analysis failure takes');

  for (let i = 0; i < 200; i++) stepFailing();
  assert.ok(posted.length > 10,
    `and it keeps going through further failures (${posted.length} dispatched)`);
});

test('a PERMANENTLY failing worker still analyses every due window, at the hop', () => {
  // Java's workerLoop catches the analysis and keeps ticking. The retry is limited by
  // the capture: a further dispatch needs a whole fresh hop, so this cannot spin.
  const TOTAL = BUF_LEN + 30 * HOP;
  const { c, posted, stepFailing } = controllerWithSlowAnalyzer(1e9, TOTAL);
  for (let i = 0; i < 2000; i++) stepFailing();
  assert.equal(c.dropped, 0, 'no window was dropped');
  assert.ok(posted.length >= 30,
    `every due window was still handed to the analyzer (${posted.length})`);
  const starts = posted.map(m => m.winAbsStart);
  const deltas = [];
  for (let i = 1; i < starts.length; i++) deltas.push(starts[i] - starts[i - 1]);
  assert.deepEqual([...new Set(deltas)], [HOP],
    'and the spacing is still exactly the hop - a failed tick folds nothing, it skips nothing');
});

test('a TOP-LEVEL worker error is REPORTED on the console, and raises no banner', () => {
  // Keeping the pump running is only half of it: the failure is also said, on the
  // console, as the desktop says its own - carrying what the worker reported. There
  // is no operator-facing surface for it. The pane's warn channel stays what it is,
  // the capture re-sync channel the overrun and the discontinuity recovery publish
  // on, and a worker failure must not appear there.
  const TOTAL = BUF_LEN + 20 * HOP;
  const { c } = controllerWithSlowAnalyzer(1e9, TOTAL);
  const keys = [];
  const said = [];
  const bus = MessageBus.instance();
  const off = bus.subscribe(Events.FFT_CAPTURE_RESYNC, (k) => keys.push(k));
  const realWarn = console.warn;
  console.warn = (...args) => { said.push(args.join(' ')); };
  try {
    c.feedFft();
    assert.ok(c._busy, 'a window is in flight');
    c._onWorkerError({ message: 'boom' });
  } finally {
    console.warn = realWarn;
    if (typeof off === 'function') off();
  }
  assert.ok(said.some((m) => m.indexOf('boom') >= 0),
    'the worker failure is reported, with what the worker said');
  assert.deepEqual(keys, [],
    'and nothing is published on the capture warn channel (saw ' + JSON.stringify(keys) + ')');
});

test('no locale carries a worker-failure banner text', () => {
  // The surface is gone, and a key left behind in one locale file is how a removed
  // banner comes back: the next edit finds it and wires something to it again.
  const dir = new URL('../i18n/', import.meta.url);
  const files = readdirSync(dir).filter(f => /^messages(_[A-Za-z_]+)?\.properties$/.test(f));
  assert.ok(files.length > 20, 'found the locale files (' + files.length + ')');
  for (const f of files) {
    const src = readFileSync(new URL(f, dir), 'utf8');
    assert.ok(src.indexOf('web.fft.warning.workerError') < 0,
      f + ' carries no worker-failure banner key');
  }
});

test('the worker is WIRED to that handler, not to a bare log', () => {
  // Booleans, not assert.match - a failing match would print the whole module.
  const src = readFileSync(new URL('../js/fft/fft-controller.js', import.meta.url), 'utf8');
  assert.ok(/this\.worker\.onerror = \(ev\) => this\._onWorkerError\(ev\)/.test(src),
    'the top-level worker error routes into _onWorkerError');
  assert.ok(!/onerror = \(ev\) => console\./.test(src),
    'and not into a console line that returns without clearing the in-flight flag');
});

test('nothing is consumed from the capture while an analysis is in flight', () => {
  // The cursor must not move between a dispatch and its reply - that is what keeps
  // the next window at its correct absolute position.
  const TOTAL = BUF_LEN + 10 * HOP;
  const { c, step } = controllerWithSlowAnalyzer(6, TOTAL);
  step();                                  // fills and dispatches the first window
  assert.ok(c._busy, 'an analysis is in flight');
  const posAtDispatch = c._fftReader.getReadPos();
  // There must be capture still WAITING, or "the cursor did not move" would be
  // vacuously true - which is exactly how it reads on a greedy feed that has
  // already swallowed the whole backlog.
  assert.ok(c._fftReader.available() > 0,
    `capture is waiting to be read (${c._fftReader.available()} samples)`);
  for (let i = 0; i < 4; i++) c.feedFft();  // further feeds while busy
  assert.equal(c._fftReader.getReadPos(), posAtDispatch,
    'the read cursor did not move while the analysis was in flight');
  assert.ok(c._fftReader.available() > 0, 'and the backlog is still there, unconsumed');
});
