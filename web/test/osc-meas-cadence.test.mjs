/*
 * Cadence test for diagnosis #2 (TABLE STALLS EVERY ~5 s).
 *
 * The per-tick measurement used to run the Goertzel frequency refines over the WHOLE
 * rolling collection window (up to 5 s = ~1 M samples at 192 k), costing ~2 s per publish
 * and stalling the 100 ms cadence. The fix bounds the FREQUENCY work (compute + the two
 * dual-tone refines + the single-tone raw re-pin) to the most-recent ≤MEAS_MAX_SAMPLES
 * (96000) samples - Java ScopeMeasurementWorker measN = min(count, 96000) - while the
 * whole-period Vmean/Vrms still integrates the full window.
 *
 * This drives the engine like the worker over a 6 s run (5 s window) with a weak-signal
 * segment, times each publish, and asserts no single publish exceeds 300 ms (so the real
 * ~100 ms setInterval cadence never opens a > 300 ms gap). Also asserts the fix did not
 * regress the dual-tone refine precision (dualF1/F2 exact off the bounded tail).
 *
 * Run:  node test/osc-meas-cadence.test.mjs
 */
globalThis.performance = globalThis.performance || { now: () => Date.now() };

import { OscMeasCompute } from '../js/scope/osc-meas-compute.js';

let passed = 0, failed = 0;
function ok(cond, msg) { if (cond) passed++; else { failed++; console.error('FAIL:', msg); } }

const SR = 192000, PEAK = 1.0, AVG_SEC = 5.0, RUN_SEC = 6.0;
const BATCH = Math.round(0.1 * SR), PUBLISH_MS = 100;
const MAX_GAP_MS = 300;   // the real setInterval cadence must never open a gap wider than this

const binHz = SR / 32768;
const f1 = Math.round(19000 / binHz) * binHz;
const f2 = Math.round(20000 / binHz) * binHz;

// Streams RUN_SEC of the given sample() through the engine in contiguous 100 ms batches,
// publishing at the 100 ms cadence, timing each publish. weakUntilSec forces a weak/noisy
// (near-zero tone, mostly noise) segment at the start so the single-tone crossing search
// fails and the async-fallback path is exercised.
function runTimed(sample, pubOpts) {
  const eng = new OscMeasCompute();
  const total = Math.round(RUN_SEC * SR);
  const batch = new Float32Array(BATCH);
  const feedOpts = { mainsMode: 'NONE', avgSeconds: AVG_SEC };
  const durations = [];
  let absK = 0, sincePublishMs = 0;
  const perBatchMs = 100;
  let last = null;
  while (absK < total) {
    const n = Math.min(BATCH, total - absK);
    for (let i = 0; i < n; i++) batch[i] = sample(absK + i);
    eng.feed('L', batch, n, SR, feedOpts);
    absK += n;
    sincePublishMs += perBatchMs;
    while (sincePublishMs >= PUBLISH_MS) {
      sincePublishMs -= PUBLISH_MS;
      const t0 = performance.now();
      const m = eng.publish('L', SR, PEAK, pubOpts);
      const dt = performance.now() - t0;
      if (m) { durations.push(dt); last = m; }
    }
  }
  return { durations, last };
}

// ===== DUAL 19000/20000 @ 192k, weak (mostly-noise) first 1.5 s then full tone =====
{
  const amp = 0.4;
  let seed = 12345;
  const rnd = () => { seed = (seed * 1103515245 + 12345) & 0x7fffffff; return seed / 0x7fffffff - 0.5; };
  const weakSamp = Math.round(1.5 * SR);
  const { durations, last } = runTimed(
    (k) => (k < weakSamp)
      ? 1e-4 * rnd()                                     // weak/noise segment
      : amp * Math.sin(2 * Math.PI * f1 * k / SR + 0.3)   // full dual tone
        + amp * Math.sin(2 * Math.PI * f2 * k / SR + 1.1),
    { mainsMode: 'NONE', dual: true, f1Hz: f1, f2Hz: f2 });

  let maxDt = 0;
  for (const d of durations) if (d > maxDt) maxDt = d;
  console.log(`[cadence] ${durations.length} publishes, max ${maxDt.toFixed(1)} ms/publish (limit ${MAX_GAP_MS})`);
  ok(durations.length > 0, 'cadence: published at least once');
  ok(maxDt < MAX_GAP_MS, `cadence: no publish exceeds ${MAX_GAP_MS} ms (max ${maxDt.toFixed(1)} ms)`);
  ok(Number.isFinite(last.dualF1) && Math.abs(last.dualF1 - f1) < 1e-3,
     `cadence: bounded-tail dualF1 still exact (${last.dualF1 && last.dualF1.toFixed(6)} vs ${f1})`);
  ok(Number.isFinite(last.dualF2) && Math.abs(last.dualF2 - f2) < 1e-3,
     `cadence: bounded-tail dualF2 still exact (${last.dualF2 && last.dualF2.toFixed(6)} vs ${f2})`);
}

console.log(`\nosc-meas-cadence: ${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
