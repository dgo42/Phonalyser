/*
 * Phonalyser web - the analyzer worker's FINALIZE stage.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Run with `node web/test/fft-worker-finalize.test.mjs`.
 *
 * Vocabulary (defined once): a TICK is one analysis cycle of the FFT consumer - one window of
 * capture samples analysed, a few per second. FINALIZE is the display rebuild that follows the
 * analysis: the cumulative overlay, the .frc de-embed, the plot-time mains comb, one statistics
 * recompute and the blue "before-cal" dots. The DISPLAY GATE decides, per tick, whether that
 * rebuild happens at all - it is what keeps the screen refresh bounded while the average keeps
 * deepening.
 *
 * What is asked here: the rebuild runs where the analysis runs, off the main thread, and the
 * gate decides ONLY whether a tick is rebuilt for display. A declined tick still enters the
 * average, so a skipped frame never costs depth - the property the whole per-tick throttle
 * stands on.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

// The worker module installs its message handler on `self` as it loads - a worker global a
// plain node run does not have. This stub is what a worker environment gives it: somewhere to
// install the handler, and somewhere for a posted result to go.
const posted = [];
globalThis.self = { postMessage: (m) => posted.push(m), onmessage: null };
const worker = await import('../js/fft/fft-worker.js');
const { primaryAccum } = worker;

const SAMPLE_RATE_HZ = 48000;
const FFT_SIZE = 1024;
const TONE_HZ = SAMPLE_RATE_HZ * 64 / FFT_SIZE;    // exactly on bin 64
/** A flat response of half amplitude: every corrected bin rises by 20*log10(2). */
const CAL_MAG_LIN = 0.5;
const CAL_GAIN_DB = 20.0 * Math.log10(CAL_MAG_LIN);
/** The de-embed is a per-bin complex multiply, so a flat response lands within rounding. */
const DB_TOLERANCE = 1e-9;

let passed = 0, failed = 0;
function ok(cond, msg) { if (cond) { passed++; } else { failed++; console.error('FAIL:', msg); } }

/** One window of a tone sitting exactly on a bin. */
function toneWindow() {
  const s = new Float64Array(FFT_SIZE);
  for (let i = 0; i < FFT_SIZE; i++) {
    s[i] = 0.5 * Math.sin(2.0 * Math.PI * TONE_HZ * i / SAMPLE_RATE_HZ);
  }
  return s;
}

/** A flat with-noise calibration spanning the whole analysed band. */
function flatCal() {
  return {
    freqs: Float64Array.of(1.0, SAMPLE_RATE_HZ / 2),
    magLin: Float64Array.of(CAL_MAG_LIN, CAL_MAG_LIN),
    phaseRad: Float64Array.of(0.0, 0.0),
  };
}

/** The tick message the controller posts, with only what this question varies spelled out. */
function tick({ finalize, resetAccum = false, accumulate = false }) {
  return {
    id: 1, samples: toneWindow(), sampleRate: SAMPLE_RATE_HZ, fftSize: FFT_SIZE,
    harmonicCount: 5, windowType: 'BH7', overlap: 'PCT_0',
    step: FFT_SIZE, frameCount: 1, snrFreqMin: 0, snrFreqMax: 0,
    coherentAveraging: true, fundRefDbFs: NaN, expectedFundHz: NaN,
    multiTone: false, secondToneHintHz: NaN, threads: 1, timeGate: false,
    winAbsStart: 0, accumulate, targetN: Infinity, spectralGate: false,
    strongToneRelDb: 100.0, resetAccum, resyncAccum: false,
    finalize, mainsF0: 0, wantCalGain: false,
  };
}

/** Posts one message into the worker and returns what it posted back. */
function run(message) {
  posted.length = 0;
  self.onmessage({ data: message });
  return posted[posted.length - 1];
}

// --- 1. With rows loaded, a finalized tick comes back DE-EMBEDDED, and the blue dots carry the
// levels the correction started from.
{
  run({ calRows: [] });
  const raw = run(tick({ finalize: true, resetAccum: true }));
  ok(raw.finalized === true, 'a tick the gate accepted is marked finalized');
  ok(raw.preCorrectionPeaks == null, 'with no rows loaded there is nothing to draw a blue dot for');

  run({ calRows: [{ cal: flatCal(), withNoise: true }] });
  const corrected = run(tick({ finalize: true, resetAccum: true }));
  ok(Math.abs((corrected.fundamentalDbFs - raw.fundamentalDbFs) + CAL_GAIN_DB) < DB_TOLERANCE,
    'the de-embed lifts the fundamental by exactly what the response takes off it');
  ok(Math.abs((corrected.amplitudeDbFs[300] - raw.amplitudeDbFs[300]) + CAL_GAIN_DB) < DB_TOLERANCE,
    'and a with-noise row lifts a bare noise bin by the same amount');
  ok(corrected.preCorrectionPeaks != null, 'the blue dots ride the result');
  ok(Math.abs(corrected.preCorrectionPeaks[1][0] - raw.fundamentalDbFs) < DB_TOLERANCE,
    'and stand where the measurement stood before the correction');
}

// --- 2. The display gate decides the REBUILD, never the average: a declined tick carries no
// spectrum to show and still deepens the collected depth.
{
  run({ calRows: [] });
  const first = run(tick({ finalize: true, resetAccum: true, accumulate: true }));
  ok(first.finalized === true, 'the first tick is rebuilt');
  const depthAfterFirst = first.accumFrames;
  ok(depthAfterFirst > 0, 'and it entered the average');

  const declined = run(tick({ finalize: false, accumulate: true }));
  ok(declined.finalized === false, 'a declined tick says so');
  ok(declined.preCorrectionPeaks == null, 'and carries no rebuilt display');
  ok(declined.accumFrames > depthAfterFirst,
    'while the depth it added is the same depth a shown tick would have added');
  ok(primaryAccum.accumFrames === declined.accumFrames,
    'the depth reported is the accumulator the analyzer keeps');
}

// --- 3. The stopped-state rebuild: the average survives the tick that produced it, so a
// calibration loaded afterwards is answered from the accumulator alone, with no new window.
{
  run({ calRows: [] });
  run(tick({ finalize: true, resetAccum: true, accumulate: true }));
  const beforeDepth = primaryAccum.accumFrames;

  run({ calRows: [{ cal: flatCal(), withNoise: true }] });
  const rebuilt = run({ reemit: true, mainsF0: 0, wantCalGain: false });
  ok(rebuilt.reemit === true && !rebuilt.empty, 'the analyzer answers the rebuild request');
  ok(rebuilt.finalized === true, 'with a finished display result');
  ok(rebuilt.preCorrectionPeaks != null, 'carrying the blue dots of the newly loaded row');
  ok(primaryAccum.accumFrames === beforeDepth,
    'and the average is untouched - nothing was folded to answer');
}

console.log(`fft-worker-finalize.test: ${passed} passed, ${failed} failed`);
process.exit(failed === 0 ? 0 : 1);
