/*
 * Executable test for scope signal measurements (Vpp correctness).
 *
 * Runs the REAL compute() from js/scope/signal-measurements.js and asserts on
 * its output:
 *   1) Vpp ≈ Vrms·2·√2 for a clean sine, and Vpp is independent of the DC
 *      pedestal D (DC must not leak into Vpp).
 *   2) A half-filled buffer (length 8192, only first 4000 = signal) computed
 *      with n=4000 gives the SAME Vpp as a tight 4000-length array - the zero
 *      tail must never be read.
 *   3) The `broadband` flag's two-sided contract, which is what makes the
 *      off-thread frequency scan correct: a weak/noisy tone must leave
 *      frequency NaN when the flag is FALSE (the live 10 Hz publish loop -
 *      that NaN is precisely what makes osc-meas-worker.js kick
 *      requestFreqScan), and the SAME window must resolve to the true
 *      fundamental when it is TRUE (what osc-freq-worker.js computes and folds
 *      back in via engine.setAsyncFreq).
 */

import { compute } from '../js/scope/signal-measurements.js';

let passed = 0;
let failed = 0;

function check(name, cond, detail) {
  if (cond) {
    passed++;
  } else {
    failed++;
    console.error(`  FAIL: ${name}${detail ? ' - ' + detail : ''}`);
  }
}

const SAMPLE_RATE = 48000;
const PEAK_VOLTS = 1.0;          // normalized -> volts 1:1, so Vpp == (max-min)
const A = 30e-6;                 // normalized amplitude
const FREQ = 1000;               // Hz
const N = 4000;

// Build a clean sine of amplitude A on a DC pedestal D over `len` samples,
// with `n` valid samples populated; the remainder of the buffer stays 0.
function sineBuffer(len, n, amp, dc) {
  const buf = new Float32Array(len);
  for (let i = 0; i < n; i++) {
    buf[i] = dc + amp * Math.sin(2 * Math.PI * FREQ * i / SAMPLE_RATE);
  }
  return buf;
}

// --- Test 1: Vpp ≈ Vrms·2·√2 and independent of DC pedestal -----------------
{
  const noDc = sineBuffer(N, N, A, 0);
  const withDc = sineBuffer(N, N, A, 98e-6);

  const mNoDc = compute(noDc, N, SAMPLE_RATE, PEAK_VOLTS);
  const mWithDc = compute(withDc, N, SAMPLE_RATE, PEAK_VOLTS);

  // Vpp ≈ Vrms · 2 · √2 within 2%.
  const expectedVpp = mNoDc.vrms * 2 * Math.sqrt(2);
  const relErr = Math.abs(mNoDc.vpp - expectedVpp) / expectedVpp;
  check('Vpp ≈ Vrms·2·√2 (within 2%)', relErr <= 0.02,
        `vpp=${mNoDc.vpp}, vrms·2·√2=${expectedVpp}, relErr=${(relErr * 100).toFixed(3)}%`);

  // Vpp independent of DC: D=0 vs D=98e-6 must match.
  const dcRelErr = Math.abs(mNoDc.vpp - mWithDc.vpp) / mNoDc.vpp;
  check('Vpp independent of DC pedestal', dcRelErr <= 1e-9,
        `vpp(D=0)=${mNoDc.vpp}, vpp(D=98e-6)=${mWithDc.vpp}`);

  // Sanity: the DC offset itself is actually reflected in vmean (proves the
  // pedestal was really present, so the Vpp match above is meaningful). The
  // tolerance allows the sine's partial-cycle residual (4000 samples ≈ 83.33
  // cycles) on top of the 98e-6 pedestal - that residual is ≤ amplitude/π·(1/N)
  // scale, well under 1e-6 here.
  check('vmean tracks the DC pedestal', Math.abs(mWithDc.vmean - 98e-6) < 1e-6,
        `vmean=${mWithDc.vmean}`);
}

// --- Test 2: half-filled buffer (zero tail must never be read) ---------------
{
  const tight = sineBuffer(N, N, A, 98e-6);           // length 4000, all signal
  const padded = sineBuffer(8192, N, A, 98e-6);       // length 8192, first 4000 signal, rest 0

  const mTight = compute(tight, N, SAMPLE_RATE, PEAK_VOLTS);
  const mPadded = compute(padded, N, SAMPLE_RATE, PEAK_VOLTS);

  check('half-filled buffer Vpp == tight Vpp (zero tail not read)',
        mPadded.vpp === mTight.vpp,
        `padded.vpp=${mPadded.vpp}, tight.vpp=${mTight.vpp}`);

  // Guard against the failure mode where the zero tail drags min down to 0,
  // inflating Vpp to ~(max - 0). With a +98e-6 pedestal and 30e-6 amplitude the
  // true min is ~68e-6 (>0), so a leaked zero tail would change min -> 0.
  check('padded Vpp not inflated by zero tail',
        mPadded.vpp < 1.5 * (A * 2 * PEAK_VOLTS),
        `padded.vpp=${mPadded.vpp}, true Vpp≈${A * 2 * PEAK_VOLTS}`);
}

// --- Test 3: the broadband flag's two-sided contract ------------------------
// Replaces test/e2e/_freq-worker-test.mjs, which drove this same invariant through a
// real browser Worker but named modules (js/scope/freq-scan.js, FreqScanClient) that no
// longer exist - and, being '_'-prefixed, was executed by nothing, so the rot went
// unnoticed. The plumbing it exercised is covered by the osc-meas-* tests; the DSP
// invariant is compute()'s, and lives here where the rest of compute() is pinned.
{
  const SCAN_FREQ = 1234;          // the probe's tone - deliberately not a bin centre
  const SCAN_N = 8192;
  const TONE_AMP = 0.04;           // normalized
  const NOISE_PP = 0.03;           // uniform peak-to-peak; SNR ≈ 10 dB - inside the crossing band
  const HEAVY_NOISE_PP = 0.1;      // ±0.05 > the amplitude - no crossing band survives this

  // A fixed-seed LCG, NOT Math.random: the pass/fail verdict must not depend on which
  // noise draw the run happened to get.
  function lcg(seed) {
    let s = seed >>> 0;
    return () => { s = (s * 1664525 + 1013904223) >>> 0; return s / 4294967296; };
  }

  function noisyTone(noisePp, seed) {
    const rand = lcg(seed);
    const buf = new Float32Array(SCAN_N);
    for (let i = 0; i < SCAN_N; i++) {
      buf[i] = TONE_AMP * Math.sin(2 * Math.PI * SCAN_FREQ * i / SAMPLE_RATE)
             + (rand() - 0.5) * noisePp;
    }
    return buf;
  }

  // Control: with no noise the CHEAP crossing search resolves the tone on its own, so
  // the NaN below is the weak-signal path talking - not a broken compute().
  const clean = compute(noisyTone(0, 12345), SCAN_N, SAMPLE_RATE, PEAK_VOLTS, false);
  check('clean tone resolves WITHOUT the broadband scan',
        Math.abs(clean.frequency - SCAN_FREQ) < 15, `frequency=${clean.frequency}`);

  // A noise band inside the crossing hysteresis: the cheap path resolves the tone itself -
  // the bare midpoint used to cross dozens of times per period here and hand the window to
  // the sweep (or, live, to whatever the last sweep had found).
  const noisy = compute(noisyTone(NOISE_PP, 12345), SCAN_N, SAMPLE_RATE, PEAK_VOLTS, false);
  check('a tone in a noise band below the crossing hysteresis resolves on the cheap path',
        Math.abs(noisy.frequency - SCAN_FREQ) < 15, `frequency=${noisy.frequency}`);

  const buried = noisyTone(HEAVY_NOISE_PP, 12345);

  // The live loop's half of the contract: cheap NaN rather than a costly sweep.
  const off = compute(buried, SCAN_N, SAMPLE_RATE, PEAK_VOLTS, false);
  check('a buried tone leaves frequency NaN when broadband=false',
        Number.isNaN(off.frequency), `frequency=${off.frequency}`);

  // The worker's half: the same window, swept, finds the fundamental.
  const on = compute(buried, SCAN_N, SAMPLE_RATE, PEAK_VOLTS, true);
  check('a buried tone IS found when broadband=true (~1234 Hz)',
        Math.abs(on.frequency - SCAN_FREQ) < 15, `frequency=${on.frequency}`);
}

console.log(`scope-measurement: ${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
