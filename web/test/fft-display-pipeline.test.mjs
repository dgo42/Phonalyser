/*
 * Phonalyser web - the display-preparation pipeline's contract.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Run with `node web/test/fft-display-pipeline.test.mjs`.
 *
 * Vocabulary (defined once): a TICK is one analysis cycle - one window of capture samples
 * analysed. The FOLD adds it into the running cross-tick average. DISPLAY PREPARATION is what
 * turns that average into the spectrum the view draws: the cumulative overlay, the .frc de-embed,
 * the mains comb, the statistics recompute and the blue dots. It now runs on its own thread while
 * the next tick is analysed, so a DISPLAYED tick costs the cadence of a DECLINED one.
 *
 * What is asked here is the seam, not the arithmetic (the arithmetic is asked where it lives):
 * that the overlay done from COPIED sums is the same overlay done from the accumulator itself,
 * bit for bit - because that copy is the whole reason the two stages can run at once; that only
 * one frame is ever being prepared; that a frame from before a statistics restart is dropped by
 * the epoch it CARRIES, since a single dispatch slot cannot speak for a message that arrives out
 * of band; that the depth readout can only advance; that a frame prepared ACROSS the seam is
 * indistinguishable to a consumer from the same tick prepared in place, field for field; and that
 * the average readout is stamped in ONE place, from the depth in TICKS the controller holds.
 */
import { FftAccumulator } from '../js/fft/fft-accumulator.js';
import { FftAnalyzer } from '../js/fft/fft-analyzer.js';
import { FftResult } from '../js/fft/fft-result.js';
import { FftController } from '../js/fft/fft-controller.js';
import { testPrefs } from './_test-prefs.mjs';
import {
  buildFinalizeJob, conversionBin, displaySnapshot, finalizeForDisplay, loadJobInto,
  overlayFromSums, setCalRows,
} from '../js/fft/fft-finalize.js';
import { MessageBus } from '../js/bus/message-bus.js';
import { Events } from '../js/bus/events.js';

let passed = 0, failed = 0;
function ok(cond, msg) { if (cond) { passed++; } else { failed++; console.error('FAIL:', msg); } }

/** Every displayed result leaves the controller as FFT_RESULT_AVAILABLE on the bus (the pane's
 *  seam); collects them in order. The returned array's stop() drops the subscription - the bus
 *  is one process-wide instance, so a block that forgets it would count the next block's frames. */
function shownResults() {
  const shown = [];
  const sub = (r) => shown.push(r);
  MessageBus.instance().subscribe(Events.FFT_RESULT_AVAILABLE, sub);
  shown.stop = () => MessageBus.instance().unsubscribe(Events.FFT_RESULT_AVAILABLE, sub);
  return shown;
}

const SR = 48000, FFT = 4096, TONE = 997.31;
/** Harmonics the analysis carries (H2..H6) - the distortion table's rows. */
const HARMONICS = 5;
/** An average some hundreds of ticks deep, to read the two counts of it apart: at two frames per
 *  coherent tick the accumulator holds 1222 frames for these 611 ticks. */
const DEPTH_TICKS = 611;

/** A real analysed tick, so the overlay has a real conversion anchor to read. */
function analysedTick(frames = 2) {
  const step = FFT / 2;
  const buf = new Float64Array(FFT + (frames - 1) * step);
  for (let i = 0; i < buf.length; i++) {
    buf[i] = 0.5 * Math.sin(2 * Math.PI * TONE * i / SR)
      + 1e-3 * Math.sin(2 * Math.PI * 2 * TONE * i / SR);
  }
  const az = new FftAnalyzer();
  return az.analyze(buf, SR, FFT, HARMONICS, 'BH7', 'PCT_50', 0, 0, true, NaN, NaN,
    new FftResult(), step, frames);
}

// --- 1. THE COPIED SUMS GIVE THE SAME OVERLAY. The accumulator stays on the analysis thread and
// its sums are copied across the seam, because the next tick's fold writes them while the display
// is being prepared - a shared view would tear the spectrum. The copy is only admissible if it
// changes nothing, so this compares the two overlays element for element.
for (const coherent of [true, false]) {
  const r = analysedTick();
  const acc = new FftAccumulator();
  acc.setStrongToneRelDb(100);
  acc.reset();
  acc.accumulate(r, 0, coherent, Infinity);
  acc.accumulate(r, FFT / 2, coherent, Infinity);

  // The conversion pair is read at the pinned bin BEFORE either overlay - both sides must read it
  // at the same bin, which is why that bin has one definition.
  const kFund = conversionBin(r);
  const sums = {
    coherent: acc.isCoherent,
    frames: acc.accumFrames,
    re: acc.sumRe ? acc.sumRe.slice() : null,
    im: acc.sumIm ? acc.sumIm.slice() : null,
    pow: acc.sumPow ? acc.sumPow.slice() : null,
    convMag: Math.hypot(r.re[kFund], r.im[kFund]),
    convAmp: r.amplitudeDbFs[kFund] > -290.0 ? Math.pow(10.0, r.amplitudeDbFs[kFund] / 20.0) : 0.0,
  };

  const byAccumulator = r.deepCopy();
  acc.overlayOnto(byAccumulator);
  const bySums = r.deepCopy();
  overlayFromSums(bySums, sums);

  let same = true;
  for (let k = 0; k <= FFT / 2 && same; k++) {
    same = byAccumulator.re[k] === bySums.re[k] && byAccumulator.im[k] === bySums.im[k]
      && byAccumulator.amplitudeDbFs[k] === bySums.amplitudeDbFs[k]
      && byAccumulator.phaseDeg[k] === bySums.phaseDeg[k];
  }
  ok(same, `the overlay from copied sums is bit-identical (${coherent ? 'coherent' : 'incoherent'})`);
}

// --- 2. THE CONVERSION BIN HAS ONE DEFINITION, and the clamp is part of it: a refined
// fundamental can round past the last bin, and the factor has to come from a bin that exists.
{
  const half = FFT / 2;
  const at = (hz) => conversionBin({ fftSize: FFT, fundamentalHzRefined: hz, freqResolution: SR / FFT });
  ok(at(TONE) === Math.round(TONE / (SR / FFT)), 'an ordinary tone maps to its own bin');
  ok(at(0) === 1, 'no tone at all still names a bin that exists');
  ok(at(SR) === half, 'a frequency past Nyquist is clamped to the last bin, never past it');
}

// --- 3. AT MOST TWO FRAMES IN FLIGHT. The display gate is what decides which tick is prepared,
// so the cap lives with it: the port preserves send order, and two are allowed because the
// analysis answer that resumes the feed races the display frame on a separate channel - a cap of
// one declined every second tick over that race. At the cap no further tick is displayed, and the
// tick is folded exactly as a throttled one is; deliveries count back down one at a time.
{
  const c = controller();
  c._lastShowMs = 0;               // long enough ago that the throttle would allow a frame
  ok(c._showThisTick() === true, 'with nothing outstanding the gate shows the tick');
  c._armFinalizeWatchdog();
  ok(c._finalizeJobs === 1, 'asking for a frame counts one outstanding');
  c._lastShowMs = 0;
  ok(c._showThisTick() === true, 'one outstanding job still admits the next tick - the race case');
  c._armFinalizeWatchdog();
  ok(c._finalizeJobs === 2, 'which counts a second job out');
  c._lastShowMs = 0;
  ok(c._showThisTick() === false, 'and at the cap no further tick is displayed');
  c._finalizeDelivered();
  ok(c._finalizeJobs === 1, 'a delivery counts one back in');
  ok(c._finalizeTimer !== null, 'and keeps the watchdog armed for the one still out');
  c._lastShowMs = 0;
  ok(c._showThisTick() === true, 'below the cap the gate shows again');
  c._finalizeDelivered();
  ok(c._finalizeJobs === 0, 'the last delivery empties the cap');
  ok(c._finalizeTimer === null, 'and takes the watchdog with it - no timer is left armed');
  c._finalizeDelivered();
  ok(c._finalizeJobs === 0, 'a late delivery after a write-off cannot count below empty');
}

// --- 4. THE CARRIED EPOCH DECIDES. A display frame answers out of band, so the dispatch's single
// epoch slot cannot speak for it: the frame carries the epoch of the tick it was built from and
// THAT is compared. A frame from before a statistics restart is dropped whole.
{
  const c = controller();
  const shown = shownResults();
  c._accumEpoch = 7;
  c._armFinalizeWatchdog();
  c._onDisplayResult(displayFrame(3));
  ok(shown.length === 0, 'a frame from a superseded average is dropped');
  ok(c._finalizeJobs === 0, 'and still counts out of the cap - a dropped frame cannot wedge it');
  c._armFinalizeWatchdog();
  c._onDisplayResult(displayFrame(7));
  ok(shown.length === 1, 'a frame of the current average is shown');
  shown.stop();
}

// --- 5. THE DEPTH READOUT ONLY ADVANCES. It is assigned from the ANALYSIS answers, which arrive
// in tick order; a display frame is later and may have been built from an older depth, so it must
// not be allowed to assign it backwards.
{
  const c = controller();
  const shown = shownResults();
  c._accumEpoch = 1;
  c.framesDone = 40;
  c._armFinalizeWatchdog();
  const stale = displayFrame(1);
  stale.frameCount = 2;            // what the frame itself was built from
  c._onDisplayResult(stale);
  ok(c.framesDone === 40, 'a late frame does not walk the depth back');
  ok(shown.length === 1 && shown[0].framesDone === 39,
    'and what it reports is the depth the analysis reached, less the seed tick');
  shown.stop();
}

/**
 * What a consumer walks on a displayed result, read off the consumers themselves: the trace, the
 * cursor readouts and the THD table in js/fft/fft-view.js (with js/fft/fft-view-correction.js and
 * js/fft/imd-analyzer.js, which the view calls on the way), the average label and the floated
 * table in js/fft/fft-pane.js, and the DAC-predistortion chain reached through
 * js/shell/predistortion-host.js (js/predistortion/harmonic-compensation.js and
 * intermod-compensation.js, plus FftResult.noisePeakFloorDbFs / rawHarmonicDbFs, which read the
 * spectrum and the harmonic table back out).
 *
 * The fields the CONTROLLER stamps afterwards - binW, framesDone, avgTarget, channelLeft, fll,
 * perf - and the IMD table the view correction computes are not here: they are added after the
 * display preparation, equally on both paths, and the counter among them is question 7.
 */
const DISPLAY_READ_SET = [
  'amplitudeDbFs', 're', 'im', 'fftSize', 'sampleRate', 'freqResolution', 'frameCount',
  'fundamentalBin', 'fundamentalHzRefined', 'fundamental2HzRefined',
  'fundamentalDbFs', 'fundamentalLinear', 'fundamentalTrueDbFs', 'fundamentalDynExclusionHz',
  'harmonicCount', 'harmonicBins', 'harmonicHz', 'harmonicDbFs', 'harmonicPct',
  'thdPct', 'thdNDb', 'snrDb', 'awThdNDb', 'enobSinadDb',
  'snrFreqMin', 'snrFreqMax', 'noisePower', 'avgNoiseFloorDbFs',
  'preCorrectionPeaks', 'rawFundRe', 'rawFundIm', 'rawPeakRe', 'rawPeakIm',
  'imdProductA', 'imdProductB', 'imdProductBin',
];

// --- 6. THE TWO PATHS ARE INDISTINGUISHABLE TO A CONSUMER. An accumulated tick has its display
// prepared ACROSS the seam (buildFinalizeJob -> loadJobInto -> finalizeForDisplay ->
// displaySnapshot); a tick with no average behind it, and every tick once the fallback is latched,
// is prepared IN PLACE from the accumulator itself. Both end in the same posted shape, so what is
// asked is whether the job carries everything the stage needs. A scalar that fails to travel is
// invisible in the arrays that do: a job without fftSize sizes the rebuilt spectrum to a single
// bin, so the overlay writes one bin and the statistics are recomputed over nothing, and what
// reaches the screen is an empty trace with no distortion table - beside a counter, stamped
// elsewhere, that goes on climbing. Every field the consumers read is therefore compared against
// the in-place answer for the SAME tick, with and without a loaded correction.
for (const calibrated of [false, true]) {
  setCalRows(calibrated ? [{ cal: flatCal(), withNoise: true }] : []);
  const r = analysedTick();
  const acc = new FftAccumulator();
  acc.setStrongToneRelDb(100);
  acc.reset();
  acc.accumulate(r, 0, true, Infinity);
  acc.accumulate(r, FFT / 2, true, Infinity);
  // What the analysis thread stamps on the tick before either path prepares it.
  r.frameCount = acc.accumFrames;
  r.coherentKappa = acc.accumKFractional;

  const { a, b } = preparedPair(r, acc);
  const tag = calibrated ? 'with a correction loaded' : 'with none loaded';
  let missing = '', differs = '';
  for (const f of DISPLAY_READ_SET) {
    const inA = Object.prototype.hasOwnProperty.call(a, f);
    const inB = Object.prototype.hasOwnProperty.call(b, f);
    if (inA !== inB) missing = f;
    else if (!sameValue(a[f], b[f])) differs = f;
  }
  ok(missing === '',
    'the pipelined frame carries every field the in-place one does (' + tag + '): ' + missing);
  ok(differs === '',
    'and the same value in each of them (' + tag + '): ' + differs);
  ok(b.fftSize === FFT && b.amplitudeDbFs.length === FFT / 2 + 1,
    'the pipelined spectrum is a full half-spectrum, not an empty trace (' + tag + ')');
  ok(b.harmonicCount === HARMONICS && b.harmonicDbFs.length === HARMONICS,
    'and carries the harmonic table the distortion readout draws (' + tag + ')');
}
setCalRows([]);

// --- 7. THE AVERAGE READOUT IS STAMPED IN ONE PLACE, FROM THE DEPTH IN TICKS. Two counts exist
// for one average and they differ by the tick's frames-per-tick: the accumulator counts FRAMES,
// and a coherent tick folds two of them, while the readout the operator watches counts TICKS. A
// display frame carries the frame count it was built from, so a reader that helps itself to that
// reports the same average as two numbers a factor of two apart. Every displayed result leaves
// through the controller's emit, and THAT is where the readout is stamped - from the controller's
// own depth, less the seed tick, whichever sender the frame came from.
{
  const c = controller();
  const shown = shownResults();
  c._accumEpoch = 2;
  c.framesDone = DEPTH_TICKS;                     // what the analysis answers set, in ticks
  c._armFinalizeWatchdog();
  const frame = displayFrame(2);
  frame.frameCount = 2 * DEPTH_TICKS;             // the accumulator's own depth, in frames
  c._onDisplayResult(frame);
  ok(shown.length === 1 && shown[0].framesDone === DEPTH_TICKS - 1,
    'a display frame reports the depth in ticks the controller holds, less the seed tick');
  ok(shown[0].framesDone !== 2 * DEPTH_TICKS - 1,
    'and never the frame count it was built from - the two differ by the frames per tick');
  ok(shown[0].frameCount === 2 * DEPTH_TICKS,
    'the frame depth still rides along for the readouts that want frames');

  // The stopped-state rebuild is the other sender of a display result, and it leaves through the
  // same emit: one average cannot read as two numbers depending on which side answered.
  const rebuilt = displayFrame(2);
  rebuilt.reemit = true;
  rebuilt.frameCount = 2 * DEPTH_TICKS;
  c._onWorkerResult(rebuilt);
  ok(shown.length === 2 && shown[1].framesDone === shown[0].framesDone,
    'a rebuilt frame from the stopped state is stamped by the same point, with the same count');
  shown.stop();
}

// --- 8. EVERY AVERAGING MODE IS A NUMERIC STATE, AND THE PACING IS THE SAME IN ALL OF THEM.
// The Averages dial offers Off, 1, a ring of N, and unbounded. Off and 1 are the modes with NO
// accumulator behind them - one window IS the spectrum - and the depth readout for them is that
// window's own frame count. It must come from the geometry this side stated for the tick: the
// frame count travels only with a DISPLAYED result, so reading it off the answer of a tick the
// display gate declined yields undefined, and undefined through a comparison is NaN - which is
// what the averages label then shows, from the first declined tick onward.
//
// The display gate is asked once per tick, at dispatch, before anything about the tick's display
// path is decided, so its pacing binds every mode alike: it grants a tick that has waited and
// declines the one that follows it immediately. That is asked here for all three modes together,
// because a rule that binds in one mode and not another is the shape of defect this pins.
for (const m of [{ label: 'Off', averages: 0 }, { label: '1', averages: 1 },
  { label: 'a ring of 4', averages: 4 }]) {
  const c = controller(m.averages);
  const tag = 'averages ' + m.label + ': ';
  const accumulates = c.foreverMode || c.ringN >= 2;
  ok(Number.isFinite(c.avgTarget) && c.avgTarget >= 1,
    tag + 'the target is a number every readout can compare against');
  ok(c.foreverMode === false, tag + 'and the mode is not mistaken for unbounded averaging');
  ok(accumulates === (m.averages >= 2), tag + 'it accumulates only from two up');

  c._lastShowMs = 0;
  ok(c._showThisTick() === true, tag + 'the display gate grants a tick that has waited');
  ok(c._showThisTick() === false, tag + 'and declines the one right behind it - the pacing binds');

  // A tick the gate declined: counters only, no spectrum, no frame count.
  c._accumEpoch = 5; c._tickEpoch = 5;
  c._handleWorkerResult({
    id: 1, ms: 2.0, finalized: false,
    accumFrames: accumulates ? 2 : 0, accumDepthTicks: accumulates ? 1 : 0,
  });
  ok(Number.isFinite(c.framesDone), tag + 'a declined tick leaves the depth a number');
  ok(Number.isFinite(c.averagesDisplayed()), tag + 'so the averages label can never read NaN');
  if (!accumulates) {
    ok(c.framesDone === 1, tag + 'the depth is the single window own frame count');
    ok(c.averagesDisplayed() === 0, tag + 'and no average has been collected to report');
  }
}

/** A controller with just the state these questions read.
 *  @param averages the Averages setting, from the series the dial offers: 0 for Off, 1 for a
 *   single spectrum, a count for a ring, Infinity for unbounded averaging. */
function controller(averages = Infinity) {
  const prefs = testPrefs({
    inputSampleRate: SR, fftLength: FFT, fftOverlap: 'PCT_50', fftAverages: averages,
    fftCoherentAveraging: true, fftThreads: 1, fftChannel: 'R', fftMainsSuppression: 'NONE',
    genSignalForm: 'SINE', fftStrongToneRelDb: 100,
  });
  const gen = { snapped: TONE, binW: SR / FFT, running: false, computeAnalysisFreqs() {} };
  const c = new FftController({}, gen, prefs, {});
  c._fftOn = true;
  c.N = FFT;
  c.bufLen = FFT;
  c._fftReader = null;             // no backlog to read - the gate's caught-up branch applies
  // The ONE derivation of the averaging mode from the setting, so what is asked below is the
  // code's own classification of Off / 1 / a ring and not a copy of it made here.
  c._syncLiveConfig();
  return c;
}

/** The shape a display frame arrives in - only the fields these questions touch. */
function displayFrame(epoch) {
  const r = analysedTick(1);
  const out = r.deepCopy();
  out.kind = 'display';
  out.epoch = epoch;
  return out;
}

/** The two prepared frames of ONE analysed tick, from one accumulator state: the display rebuilt
 *  IN PLACE beside the accumulator, and the same tick rebuilt ACROSS the job seam. The overlay
 *  reads the accumulator without writing it, so both may be taken from the same fold. */
function preparedPair(r, acc) {
  const d = { mainsF0: 0, wantCalGain: false };
  const inPlace = r.deepCopy();
  const job = buildFinalizeJob(r.deepCopy(), d, { id: 1, epoch: 1 }, acc);
  finalizeForDisplay(inPlace, d, (x) => acc.overlayOnto(x));
  const slot = new FftResult();
  loadJobInto(slot, job);
  finalizeForDisplay(slot, d, (x) => overlayFromSums(x, job.sums));
  return { a: displaySnapshot(inPlace, {}).out, b: displaySnapshot(slot, {}).out };
}

/** Value equality across the two posted shapes: NaN counts as itself (an absent second tone is
 *  NaN on both sides), spectra and harmonic tables are walked element for element, and the blue
 *  dots are a pair of arrays. */
function sameValue(x, y) {
  if (x == null || y == null) return x == null && y == null;
  if (typeof x === 'string' || typeof y === 'string') return x === y;
  if (typeof x === 'number') return x === y || (Number.isNaN(x) && Number.isNaN(y));
  if (x.length === undefined || y.length === undefined) return x === y;
  if (x.length !== y.length) return false;
  for (let i = 0; i < x.length; i++) if (!sameValue(x[i], y[i])) return false;
  return true;
}

/** A flat with-noise response of half amplitude over the whole analysed band: every corrected bin
 *  moves by the same amount, so a difference between the two paths cannot hide inside the
 *  correction that both of them apply. */
function flatCal() {
  return {
    freqs: Float64Array.of(1.0, SR / 2),
    magLin: Float64Array.of(0.5, 0.5),
    phaseRad: Float64Array.of(0.0, 0.0),
  };
}

// --- 9. THE DISPLAYED FRAME STEERS THE FREQUENCY LOCK. The desktop steers once per displayed
// frame from its result-display pipeline; a frame prepared across the seam arrives out of band,
// so it carries its own transport timebase (samplesAbsStart / writePos) and the steer reads the
// FRAME, never a controller slot the next dispatch has already overwritten. A stopped-state
// re-emit is display-only - stale data must not trim a live generator - and a frame from a
// superseded average must not steer any more than it may display.
{
  const c = controller();
  const steered = [];
  c._steerSingleTone = (r) => steered.push(r);
  c._prefs.genSnapToFftBin.set(true);
  c._prefs.fftFundFromGenerator.set(true);
  c._prefs.fftAlignGenerator.set('FLL');
  c._gen.running = true;           // _genOn delegates to the generator's own running flag
  c._accumEpoch = 3;

  const frame = displayFrame(3);
  frame.samplesAbsStart = 123456;
  frame.writePos = 999999;
  c._onDisplayResult(frame);
  ok(steered.length === 1, 'a displayed frame of the current average steers the lock once');
  ok(steered[0].samplesAbsStart === 123456 && steered[0].writePos === 999999,
    'and the steer reads the transport timebase the frame itself carries');

  const reemit = displayFrame(3);
  reemit.reemit = true;
  c._onDisplayResult(reemit);
  ok(steered.length === 1, 'a stopped-state re-emit displays but does not steer');

  const stale = displayFrame(2);
  c._onDisplayResult(stale);
  ok(steered.length === 1, 'a frame from a superseded average does not steer');

  c._prefs.genSnapToFftBin.set(false);
  c._onDisplayResult(displayFrame(3));
  ok(steered.length === 1, 'with snap-to-bin off the lock stays inert on the display path too');
}

// --- 10. A LOCK RESET TELLS THE GENERATOR. Clearing only the loop state would leave the
// generator at the last published trim, and the fresh loop - which assumes generator = target +
// correction - would overshoot by exactly that stale amount on its first correction; so the
// reset publishes the trim-reset event the generator answers by re-applying its snapped
// frequencies (silently - a trim reset resets no averaging anywhere).
{
  const c = controller();
  const { MessageBus } = await import('../js/bus/message-bus.js');
  const { Events } = await import('../js/bus/events.js');
  let told = 0;
  const sub = () => told++;
  MessageBus.instance().subscribe(Events.GENERATOR_FREQ_TRIM_RESET, sub);
  c._resetFrequencyLock();
  MessageBus.instance().unsubscribe(Events.GENERATOR_FREQ_TRIM_RESET, sub);
  ok(told === 1, 'resetting the frequency lock publishes the trim-reset round exactly once');
}

// --- 11. AN ANALYSER SELF-STOP IS ONE BUS ROUND. Every path on which the analyser stops by
// itself - the capture ending from below among them - publishes the auto-stopped event the pane
// subscribes; there is no second notification mechanism, so a path cannot again be wired to one
// generation and dead on the other. The capture-death path also claims the end reason exactly
// once, for the pane's single operator report.
{
  const c = controller();
  const { MessageBus } = await import('../js/bus/message-bus.js');
  const { Events } = await import('../js/bus/events.js');
  let stops = 0;
  const sub = () => stops++;
  MessageBus.instance().subscribe(Events.FFT_RECORDING_AUTO_STOPPED, sub);
  let claimed = 0;
  c._fftReader = {
    isFinished: () => true,
    takeFinishedReasonForReport: () => { claimed++; return { name: 'DEVICE_LOST' }; },
    available: () => 0,
    getReadPos: () => 0,
    getWritePos: () => 0,
  };
  c.feedFft();
  MessageBus.instance().unsubscribe(Events.FFT_RECORDING_AUTO_STOPPED, sub);
  ok(stops === 1, 'a capture that ended from below publishes the auto-stopped round once');
  ok(claimed === 1, 'and claims the end reason exactly once for the operator report');
  ok(c.captureEndReason() && c.captureEndReason().name === 'DEVICE_LOST',
    'which the pane then reads off the controller for the one localized alert');
}

console.log(`fft-display-pipeline.test: ${passed} passed, ${failed} failed`);
process.exit(failed === 0 ? 0 : 1);
