/*
 * Phonalyser web - the display-preparation FALLBACK, and that it latches.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Run with the web suite: npm test in web/.
 *
 * Vocabulary (defined once): a TICK is one analysis cycle - one window of capture samples
 * analysed, a few per second. DISPLAY PREPARATION is what turns the running average into the
 * spectrum the view draws: the cumulative overlay, the .frc de-embed, the plot-time mains comb,
 * one statistics recompute and the blue "before-cal" dots. An ACCUMULATED tick normally has that
 * prepared in a nested worker beside the next analysis; when that worker cannot be BUILT, the same
 * module runs in place on the analysis thread instead, which is slower and identical.
 *
 * What is asked here is the fail-fast rule of that fallback. The construction failure is
 * discovered on the FIRST tick that needs the worker, is said ONCE, and is latched for the rest of
 * the session - every later tick goes straight to the in-place stage rather than paying a failing
 * construction per tick. And the fallback must be a WHOLE answer: the tick comes back finalized,
 * carrying a full half-spectrum and a harmonic table, with nothing left pending. A tick that
 * answered "pending" for a worker that can never answer would hold the display slot open until a
 * watchdog expired, which is seconds of blank trace for a failure already known.
 *
 * A plain node run IS that failure, which is why it can be asked here at all: node has no Worker
 * constructor, so the construction throws exactly where a browser context that refuses one does.
 */

// The worker module installs its message handler on self as it loads - a worker global a plain
// node run does not have. This stub is what a worker environment gives it: somewhere to install
// the handler, and somewhere for a posted result to go.
const posted = [];
globalThis.self = { postMessage: (m) => posted.push(m), onmessage: null };

// The construction failure is reported on the console, once, and that line is the only evidence a
// second attempt was or was not made - so it is collected from the load onwards.
const warnings = [];
const realWarn = console.warn;
console.warn = (...args) => { warnings.push(args.join(' ')); };
await import('../js/fft/fft-worker.js');

const SAMPLE_RATE_HZ = 48000;
const FFT_SIZE = 1024;
const HARMONIC_COUNT = 5;
const TONE_HZ = SAMPLE_RATE_HZ * 64 / FFT_SIZE;    // exactly on bin 64
/** Ticks folded here - enough that a per-tick construction attempt could not hide. */
const TICKS = 4;

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

/** The tick message the controller posts for an ACCUMULATED tick it wants shown - the one case
 *  that is handed to the display worker, and so the one that discovers it cannot be built. */
function tick(resetAccum) {
  return {
    id: 1, samples: toneWindow(), sampleRate: SAMPLE_RATE_HZ, fftSize: FFT_SIZE,
    harmonicCount: HARMONIC_COUNT, windowType: 'BH7', overlap: 'PCT_0',
    step: FFT_SIZE, frameCount: 1, snrFreqMin: 0, snrFreqMax: 0,
    coherentAveraging: true, fundRefDbFs: NaN, expectedFundHz: NaN,
    multiTone: false, secondToneHintHz: NaN, threads: 1, timeGate: false,
    winAbsStart: 0, accumulate: true, targetN: Infinity, spectralGate: false,
    strongToneRelDb: 100.0, resetAccum, resyncAccum: false,
    finalize: true, mainsF0: 0, wantCalGain: false,
  };
}

/** Posts one message into the worker and returns what it posted back. */
function run(message) {
  posted.length = 0;
  self.onmessage({ data: message });
  return posted[posted.length - 1];
}

/** The construction failure, as reported: one line naming the display worker. */
const buildFailures = () => warnings.filter((m) => m.indexOf('FFT display worker') >= 0);

// --- 1. THE FIRST TICK THAT NEEDS THE WORKER DISCOVERS IT CANNOT BE BUILT, and is answered anyway.
{
  run({ calRows: [] });
  const first = run(tick(true));
  ok(buildFailures().length === 1, 'the failure to build the display worker is reported, once');
  ok(first.finalized === true, 'and the tick that found it comes back with its display prepared');
  ok(first.finalizePending === false,
    'never pending - no slot is held open for an answer that cannot come');
  ok(first.amplitudeDbFs.length === FFT_SIZE / 2 + 1,
    'the fallback answer carries the whole half-spectrum, not an empty trace');
  ok(first.harmonicDbFs.length === HARMONIC_COUNT,
    'and the harmonic table the distortion readout draws');
  ok(Number.isFinite(first.fundamentalDbFs) && first.fundamentalDbFs > -300.0,
    'with the tone found in it');
}

// --- 2. IT IS LATCHED FOR THE SESSION. Every later tick takes the in-place stage without trying
// the construction again: one failure, one line, and no per-tick cost for the rest of the run.
// The fallback moves only WHERE the display is prepared, so the average must go on deepening
// across all of them.
{
  let depth = 0;
  let deepens = true;
  for (let i = 0; i < TICKS; i++) {
    const r = run(tick(false));
    ok(r.finalized === true && r.finalizePending === false,
      'tick ' + (i + 1) + ' after the failure is prepared in place too');
    if (!(r.accumFrames > depth)) deepens = false;
    depth = r.accumFrames;
  }
  ok(buildFailures().length === 1,
    'and the construction is not attempted again - the failure is said once for the session');
  ok(deepens, 'the fold ran on every one of them - the fallback costs no depth');
}

console.warn = realWarn;
console.log('fft-finalize-fallback.test: ' + passed + ' passed, ' + failed + ' failed');
process.exit(failed === 0 ? 0 : 1);
