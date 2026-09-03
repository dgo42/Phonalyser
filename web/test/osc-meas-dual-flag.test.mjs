/*
 * Contract test for diagnosis #1 (DUAL MODE REACHING THE WORKER).
 *
 * The live measurement table showed a single finite frequency (~19 kHz, the SUM
 * crossing reading) for a CCIF 19000/20000 Hz dual tone - the symptom of the
 * per-channel publish params carrying dual:false into the compute engine. This
 * test pins the compute contract the runtime relies on:
 *
 *   Same dual-tone window, two publishes:
 *     dual:false -> frequency is FINITE (the wrong single-tone reading the user saw)
 *     dual:true  -> frequency is CLEARED to NaN (table shows '---') AND the two
 *                  refined tones dualF1/dualF2 are published within 1e-3 Hz.
 *
 * So whenever the live params carry dual:true the 'f' row reads '---' and the
 * residual/beat paths get a clean captured pair. Run: node test/osc-meas-dual-flag.test.mjs
 */
globalThis.performance = globalThis.performance || { now: () => Date.now() };

import { OscMeasCompute } from '../js/scope/osc-meas-compute.js';

let passed = 0, failed = 0;
function ok(cond, msg) { if (cond) passed++; else { failed++; console.error('FAIL:', msg); } }

const SR = 192000, PEAK = 1.0;
// The user's exact CCIF pair, snapped to the 192k/32768 output bin grid (snap ON).
const binHz = SR / 32768;
const f1 = Math.round(19000 / binHz) * binHz;   // 19001.953125
const f2 = Math.round(20000 / binHz) * binHz;   // 19998.046875
const amp = 0.4, ph1 = 0.3, ph2 = 1.1;

// Stream 5 s of the dual tone gaplessly into the engine, exactly like the client.
function feedDual() {
  const eng = new OscMeasCompute();
  const total = Math.round(5.0 * SR), BATCH = Math.round(0.1 * SR);
  const batch = new Float32Array(BATCH);
  const feedOpts = { mainsMode: 'NONE', avgSeconds: 5.0 };
  let absK = 0;
  while (absK < total) {
    const n = Math.min(BATCH, total - absK);
    for (let i = 0; i < n; i++) {
      const k = absK + i;
      batch[i] = amp * Math.sin(2 * Math.PI * f1 * k / SR + ph1)
               + amp * Math.sin(2 * Math.PI * f2 * k / SR + ph2);
    }
    eng.feed('L', batch, n, SR, feedOpts);
    absK += n;
  }
  return eng;
}

// dual:false - the buggy runtime state: a finite single-tone frequency leaks to the table.
{
  const eng = feedDual();
  const m = eng.publish('L', SR, PEAK, { mainsMode: 'NONE', dual: false, f1Hz: f1, f2Hz: f2 });
  ok(m && Number.isFinite(m.frequency),
     `dual:false publishes a FINITE (wrong) single-tone frequency (${m && m.frequency})`);
  console.log(`[dual:false] frequency=${m && m.frequency && m.frequency.toFixed(3)} (the leaked single-tone reading)`);
}

// dual:true - the correct state: frequency cleared, both tones refined off the raw window.
{
  const eng = feedDual();
  const m = eng.publish('L', SR, PEAK, { mainsMode: 'NONE', dual: true, f1Hz: f1, f2Hz: f2 });
  ok(m && Number.isNaN(m.frequency),
     `dual:true CLEARS frequency to NaN -> 'f' row reads '---' (got ${m && m.frequency})`);
  ok(m && Number.isFinite(m.dualF1) && Math.abs(m.dualF1 - f1) < 1e-3,
     `dual:true publishes dualF1 within 1e-3 Hz (${m && m.dualF1 && m.dualF1.toFixed(6)} vs ${f1})`);
  ok(m && Number.isFinite(m.dualF2) && Math.abs(m.dualF2 - f2) < 1e-3,
     `dual:true publishes dualF2 within 1e-3 Hz (${m && m.dualF2 && m.dualF2.toFixed(6)} vs ${f2})`);
  console.log(`[dual:true] frequency=${m && m.frequency}  dualF1=${m && m.dualF1 && m.dualF1.toFixed(5)}  dualF2=${m && m.dualF2 && m.dualF2.toFixed(5)}`);
}

console.log(`\nosc-meas-dual-flag: ${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
