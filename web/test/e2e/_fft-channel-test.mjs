/*
 * Phonalyser web - FFT L/R channel-switch verification (no browser).
 *
 * Temp (_-prefixed) test for the FFT-analyzed-channel switch ported from the
 * desktop into js/audio/fft-controller.js. The Java FftView L/R buttons select
 * which ADC channel the FFT ANALYZES (L -> ch0/left, R -> ch1/right), switching
 * resets the statistics + accumulator, and the worker stamps r.channelLeft off
 * the channel it ACTUALLY read (FftAnalyzerWorker:1535-6, 1592, 1872; FftView:439).
 *
 * Drives the real FftController with a MOCK reader that records which of the
 * (leftBuf, rightBuf) args each read() gets - so we can assert the SELECTED
 * channel's buffer is the non-null one - and asserts setFftChannel:
 *  (a) 'L' -> _wantLeft = true, and feedFft passes the read buffer as the LEFT arg;
 *  (b) 'R' -> _wantLeft = false, and feedFft passes it as the RIGHT arg;
 *  (c) a real switch calls resetAnalyses (accumulator reset + epoch bump);
 *  (d) a no-op switch (same channel) does NOT reset.
 *
 * Run: node test/e2e/_fft-channel-test.mjs   (exits non-zero on any FAIL)
 */
import { FftController } from '../../js/fft/fft-controller.js';
import { MessageBus } from '../../js/bus/message-bus.js';
import { Events } from '../../js/bus/events.js';
import { testPrefs } from '../_test-prefs.mjs';

let pass = 0, fail = 0;
const ok = (cond, msg) => { if (cond) { pass++; console.log('PASS: ' + msg); } else { fail++; console.log('FAIL: ' + msg); } };

// The preferences the controller consults for the channel + read path.
const prefs = testPrefs({ fftChannel: 'L', inputSampleRate: 48000 });

// A stub generator (the FFT controller only reads a few accessors during feedFft;
// none are exercised on the drain-free read path we drive here).
const genStub = {};

// Mock reader: available() returns a fixed batch once, read() records which arg
// (left/right) was non-null and returns the requested count, then drains to 0.
function makeReader(batch) {
  let served = false;
  return {
    lastLeftNonNull: null, lastRightNonNull: null, readCalls: 0,
    // The capture-death seam the feed consults first - a live lane answers "not finished".
    isFinished() { return false; },
    takeFinishedReasonForReport() { return null; },
    available() { return served ? 0 : batch; },
    getReadPos() { return 0; },
    getWritePos() { return batch; },
    seekToLatest() {},
    read(count, outLeft, outRight) {
      this.readCalls++;
      this.lastLeftNonNull = outLeft != null;
      this.lastRightNonNull = outRight != null;
      served = true;
      return Math.min(count, batch);
    },
  };
}

console.log('\n== FFT L/R channel-switch test ==\n');

// ── Build a controller and put it into a minimal "recording" state ───────────
const fft = new FftController(null, genStub, prefs, {});
// Stub the analyze path so _accumulate's dispatch can't reach the (absent) worker;
// feedFft only needs to run through the channel-selecting read.
fft._accumulate = () => {};

// Initialize _wantLeft the way _setupFftAnalysis would from the channel preference 'L'.
fft._wantLeft = (prefs.fftChannel.get() === 'L');
fft._fftOn = true;

// (a) Default 'L' -> _wantLeft true, feedFft reads into the LEFT arg.
ok(fft._wantLeft === true, "config.channel='L' -> _wantLeft = true");
{
  const rdr = makeReader(128);
  fft._fftReader = rdr;
  fft.feedFft();
  ok(rdr.lastLeftNonNull === true && rdr.lastRightNonNull === false,
    'L: feedFft passes the read buffer as the LEFT arg (ch0), right arg null');
}

// (b) Switch to 'R' -> _wantLeft false + resetAnalyses fires.
{
  let resetCalled = 0;
  const origReset = fft.resetAnalyses.bind(fft);
  const epochBefore = fft._accumEpoch;
  fft.resetAnalyses = () => { resetCalled++; origReset(); };
  fft.setFftChannel('R');
  ok(fft._wantLeft === false, "setFftChannel('R') -> _wantLeft = false");
  ok(prefs.fftChannel.get() === 'L',
    "setFftChannel('R') re-targets the read only - the channel PREFERENCE is the L/R buttons' to write");
  ok(resetCalled === 1, "setFftChannel('R') calls resetAnalyses exactly once (reset stats+accumulator)");
  ok(fft._accumEpoch === epochBefore + 1, 'resetAnalyses bumped the accumulator epoch (collection restarts from 0)');
  fft.resetAnalyses = origReset;
}

// (c) 'R' selected -> feedFft reads into the RIGHT arg (ch1).
{
  const rdr = makeReader(128);
  fft._fftReader = rdr;
  fft.feedFft();
  ok(rdr.lastLeftNonNull === false && rdr.lastRightNonNull === true,
    'R: feedFft passes the read buffer as the RIGHT arg (ch1), left arg null');
}

// (d) No-op switch (same channel) does NOT reset.
{
  let resetCalled = 0;
  const origReset = fft.resetAnalyses.bind(fft);
  fft.resetAnalyses = () => { resetCalled++; origReset(); };
  fft.setFftChannel('R');   // already R
  ok(resetCalled === 0 && fft._wantLeft === false,
    "setFftChannel('R') when already R is a no-op (no reset, _wantLeft unchanged)");
  fft.resetAnalyses = origReset;
}

// (e) _emit stamps r.channelLeft = _wantLeft (Java FftAnalyzerWorker:1872).
{
  // R currently selected -> _wantLeft false -> r.channelLeft must be false.
  const emitted = { binW: 0 };
  fft.binW; // no-op access guard (genStub has no binW getter path here)
  // _emit reads this.binW / this.snapped / config via the gen stub; give the minimum.
  Object.defineProperty(genStub, 'binW', { value: 1, configurable: true });
  Object.defineProperty(genStub, 'snapped', { value: 1000, configurable: true });
  Object.defineProperty(genStub, 'genFreq', { value: 1000, configurable: true });
  Object.defineProperty(genStub, 'fllErrHz', { value: 0, configurable: true });
  Object.defineProperty(genStub, 'fllLocked', { value: false, configurable: true });
  Object.defineProperty(genStub, 'rejectedCount', { value: 0, configurable: true });
  let got = null;
  const sub = (r) => { got = r; };
  MessageBus.instance().subscribe(Events.FFT_RESULT_AVAILABLE, sub);
  fft.framesDone = 0; fft.foreverMode = false; fft.avgTarget = 16;
  fft._emit(emitted);
  MessageBus.instance().unsubscribe(Events.FFT_RESULT_AVAILABLE, sub);
  ok(got && got.channelLeft === false,
    '_emit stamps r.channelLeft = _wantLeft (R selected -> channelLeft false)');
  // Flip back to L and re-emit -> channelLeft true.
  fft.setFftChannel('L');
  const emitted2 = { binW: 0 };
  fft._emit(emitted2);
  ok(emitted2.channelLeft === true,
    '_emit stamps r.channelLeft = _wantLeft (L selected -> channelLeft true)');
}

console.log(`\nfft-channel: ${pass} passed, ${fail} failed\n`);
process.exit(fail === 0 ? 0 : 1);
