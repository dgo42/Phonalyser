/*
 * Phonalyser web - the prelude's frame spectra, reused by the accumulation.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Run with `node web/test/fft-prelude-frame-reuse.test.mjs`.
 *
 * Vocabulary (defined once): a FRAME is one FFT-length window of the tick's capture buffer;
 * a tick analyses one or two of them, spaced by the drawn hop. The PRELUDE is the
 * single-threaded first stage of an analysis - it transforms the leading frames to find the
 * fundamental and its sub-bin position (kappa), and hands the per-frame de-rotation everything
 * it needs. The ACCUMULATION is the second stage: it transforms each frame and adds it into the
 * tick's sum, de-rotated.
 *
 * What is asked here: the prelude's transforms ARE the accumulation's first frames, so the
 * accumulation reads them back instead of transforming the same samples twice - and the sum it
 * produces is bit for bit the sum it produced when it transformed them itself. That equality is
 * the whole licence for the reuse: a spectrum is a measurement, so the same input must keep
 * producing the same numbers.
 *
 * And the consequence for the worker pool: frames the prelude already holds are pointless to
 * hand to another thread, which would transform them from scratch on its own copy of the
 * window. At a one- or two-frame tick that leaves nothing to split.
 */
import { FftAnalyzer } from '../js/fft/fft-analyzer.js';
import { FftResult } from '../js/fft/fft-result.js';

let passed = 0, failed = 0;
function ok(cond, msg) { if (cond) { passed++; } else { failed++; console.error('FAIL:', msg); } }

const SAMPLE_RATE_HZ = 48000;
const FFT_SIZE = 4096;
const HARMONIC_COUNT = 5;
const WINDOW = 'BH7';
const OVERLAP = 'PCT_0';
/** Deliberately off-bin, so the sub-bin fundamental and the de-rotation both do real work. */
const TONE_HZ = 1002.3193071646;

/** `frames` frames of an off-bin tone with a second harmonic, spaced by `step`. */
function signal(frames, step) {
  const buf = new Float64Array(FFT_SIZE + (frames - 1) * step);
  for (let i = 0; i < buf.length; i++) {
    buf[i] = 0.5 * Math.sin(2.0 * Math.PI * TONE_HZ * i / SAMPLE_RATE_HZ)
      + 1e-3 * Math.sin(2.0 * Math.PI * 2.0 * TONE_HZ * i / SAMPLE_RATE_HZ);
  }
  return buf;
}

const preludeOf = (az, buf, frames, step, coherent = true) => az.prelude(
  buf, SAMPLE_RATE_HZ, FFT_SIZE, HARMONIC_COUNT, WINDOW, OVERLAP, coherent, NaN,
  new FftResult(), step, frames);

/** True when two partial sums are identical in every element. */
function samePartial(a, b) {
  if (a.acceptedFrames !== b.acceptedFrames) return false;
  for (let k = 0; k < a.sumRe.length; k++) {
    if (a.sumRe[k] !== b.sumRe[k] || a.sumIm[k] !== b.sumIm[k]) return false;
  }
  return true;
}

// --- 1. The reuse changes no number. The analyzer that ran the prelude reads its frames back;
// a fresh one - which is exactly what a pool sub-worker is - transforms them. Both sums must be
// identical, coherent and incoherent alike, at one frame and at two.
for (const coherent of [true, false]) {
  for (const frames of [1, 2, 3]) {
    const step = FFT_SIZE / 4;
    const buf = signal(frames, step);
    const az = new FftAnalyzer();
    const sp = preludeOf(az, buf, frames, step, coherent);
    const reused = az.accumulatePartial(buf, 0, frames, sp);
    const transformed = new FftAnalyzer().accumulatePartial(buf, 0, frames, sp);
    ok(samePartial(reused, transformed),
      `the reused frames give the same sum as transforming them (${frames} frames, `
      + `${coherent ? 'coherent' : 'incoherent'})`);
  }
}

// --- 2. WHICH frames are held: the prelude transforms frame 0 always, and frame 1 whenever the
// tick has one - it needs the pair to measure the phase advance the sub-bin fundamental comes
// from. Nothing beyond that.
{
  const step = FFT_SIZE / 2;
  const az = new FftAnalyzer();
  const three = signal(3, step);
  const sp3 = preludeOf(az, three, 3, step);
  ok(az.preludeFrameCount(three, sp3) === 2, 'a multi-frame tick holds its first two frames');

  const one = signal(1, step);
  const sp1 = preludeOf(az, one, 1, step);
  ok(az.preludeFrameCount(one, sp1) === 1,
    'a single-frame tick holds the one frame it transformed');

  ok(new FftAnalyzer().preludeFrameCount(one, sp1) === 0,
    'an analyzer that did not run the prelude holds nothing - a pool worker transforms');
}

// --- 3. The identity is checked, not assumed: another signal, another spacing or another
// window is another frame, and must be transformed afresh.
{
  const step = FFT_SIZE / 2;
  const buf = signal(2, step);
  const az = new FftAnalyzer();
  const sp = preludeOf(az, buf, 2, step);
  ok(az.preludeFrameCount(buf, sp) === 2, 'the frames of this tick are held');

  const other = signal(2, step);   // same numbers, different buffer
  ok(az.preludeFrameCount(other, sp) === 0, 'another signal buffer holds nothing');
  ok(az.preludeFrameCount(buf, { ...sp, step: step / 2 }) === 0,
    'another spacing holds nothing - the whole set belongs to one geometry');
  ok(az.preludeFrameCount(buf, { ...sp, window: sp.window.slice() }) === 0,
    'another window holds nothing');
  ok(az.preludeFrameCount(buf, { ...sp, fftSize: FFT_SIZE / 2 }) === 0,
    'another FFT length holds nothing');
}

// --- 4. A later prelude replaces the set - the analyzer never answers with a frame of a signal
// it has moved on from.
{
  const step = FFT_SIZE / 2;
  const first = signal(2, step);
  const second = signal(2, step);
  const az = new FftAnalyzer();
  const spFirst = preludeOf(az, first, 2, step);
  preludeOf(az, second, 2, step);
  ok(az.preludeFrameCount(first, spFirst) === 0, 'the previous tick s frames are gone');
}

// --- 5. The worker's consequence: with every frame of the tick already held there is nothing
// to split, so a threads setting above 1 takes the same path as 1. Proven by running it: a
// nested pool would need real Workers, which this environment has none of, so a posted result
// IS the proof that no pool was reached.
{
  const posted = [];
  globalThis.self = { postMessage: (m) => posted.push(m), onmessage: null };
  await import('../js/fft/fft-worker.js');
  const step = FFT_SIZE / 2;
  const buf = signal(2, step);
  let threw = null;
  try {
    self.onmessage({ data: {
      id: 7, samples: buf, sampleRate: SAMPLE_RATE_HZ, fftSize: FFT_SIZE,
      harmonicCount: HARMONIC_COUNT, windowType: WINDOW, overlap: OVERLAP,
      step, frameCount: 2, snrFreqMin: 0, snrFreqMax: 0, coherentAveraging: true,
      fundRefDbFs: NaN, expectedFundHz: NaN, multiTone: false, secondToneHintHz: NaN,
      threads: 8, timeGate: false, winAbsStart: 0, accumulate: false, targetN: Infinity,
      spectralGate: false, strongToneRelDb: 100.0, resetAccum: true, resyncAccum: false,
      finalize: true, mainsF0: 0, wantCalGain: false,
    } });
  } catch (e) { threw = e; }
  ok(threw === null, 'a two-frame tick at 8 threads reaches no pool - it never leaves this thread');
  ok(posted.length === 1 && posted[0].finalized === true && !posted[0].error,
    'and it answers with a finished result');
}

console.log(`fft-prelude-frame-reuse.test: ${passed} passed, ${failed} failed`);
process.exit(failed === 0 ? 0 : 1);
