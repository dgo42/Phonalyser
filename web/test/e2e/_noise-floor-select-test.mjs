/*
 * Phonalyser web - noisePeakFloorDbFs quickselect + memoization (no browser).
 *
 * The predistortion wizard polls noisePeakFloorDbFs() at 100 ms (Java
 * PredistortionWizardDialog:346). The web now memoizes it per FftResult and
 * computes the 99.9-percentile via an O(n) k-th order statistic instead of a
 * full comparator sort. Asserts the selected value is EXACTLY what the full
 * ascending sort yields (Java FftResult:369-371) across many random spectra,
 * and that the memo returns the identical value without recomputation.
 *
 * Run: node test/e2e/_noise-floor-select-test.mjs   (exits non-zero on any FAIL)
 */
import { FftResult } from '../../js/fft/fft-result.js';

let pass = 0, fail = 0;
const ok = (cond, msg) => { if (cond) { pass++; console.log('PASS: ' + msg); } else { fail++; console.log('FAIL: ' + msg); } };

let seed = 0x1337c0de;
function rnd() {
  seed ^= seed << 13; seed ^= seed >>> 17; seed ^= seed << 5; seed >>>= 0;
  return seed / 0xffffffff;
}

// Build a synthetic FftResult with a random spectrum + fundamental/harmonics marked.
function makeResult(n, fundBin) {
  const r = new FftResult();
  r.freqResolution = 1;
  r.fundamentalDynExclusionHz = 8;
  r.fundamentalBin = fundBin;
  r.harmonicCount = 5;
  r.harmonicBins = new Float64Array([fundBin * 2, fundBin * 3, fundBin * 4, fundBin * 5, fundBin * 6]);
  r.amplitudeDbFs = new Float64Array(n);
  for (let i = 0; i < n; i++) r.amplitudeDbFs[i] = -160 + 80 * rnd();
  r.amplitudeDbFs[fundBin] = -6;                       // a peak the exclusion must remove
  if (n > 100) r.amplitudeDbFs[100] = -Infinity;       // non-finite bins must be skipped
  return r;
}

/** The reference value: the ORIGINAL full-sort implementation (Java :363-371). */
function referenceFloor(r) {
  const n = r.amplitudeDbFs.length;
  const excl = new Uint8Array(n);
  const fundHalf = Math.max(1, Math.ceil(r.fundamentalDynExclusionHz / r.freqResolution));
  r._markSignalBins(excl, r.fundamentalBin, fundHalf);
  const harmHalf = Math.max(3, Math.trunc(fundHalf / 8));
  for (let i = 0; i < r.harmonicCount && i < r.harmonicBins.length; i++) {
    r._markSignalBins(excl, r.harmonicBins[i], harmHalf);
  }
  const noise = [];
  for (let b = 1; b < n; b++) {
    if (!excl[b] && Number.isFinite(r.amplitudeDbFs[b])) noise.push(r.amplitudeDbFs[b]);
  }
  if (!noise.length) return NaN;
  noise.sort((x, y) => x - y);
  return noise[Math.min(noise.length - 1, Math.round(0.999 * (noise.length - 1)))];
}

console.log('\n== noisePeakFloorDbFs: quickselect == full sort, memoized ==');

let allEqual = true;
for (const n of [64, 1024, 4097, 65537]) {
  for (let trial = 0; trial < 5; trial++) {
    const r = makeResult(n, 1 + Math.trunc(rnd() * (n / 8)));
    const want = referenceFloor(r);
    const got = r.noisePeakFloorDbFs();
    if (!(got === want || (Number.isNaN(got) && Number.isNaN(want)))) {
      allEqual = false;
      console.log(`  n=${n} trial=${trial}: got ${got} want ${want}`);
    }
  }
}
ok(allEqual, 'quickselect percentile identical to the full-sort value (4 sizes × 5 random spectra)');

// Memoization: same instance returns the identical value; mutating the spectrum
// afterwards does NOT change it (consumers only read post-mutation - documented).
{
  const r = makeResult(4096, 100);
  const first = r.noisePeakFloorDbFs();
  r.amplitudeDbFs[2000] = 0;
  ok(r.noisePeakFloorDbFs() === first, 'memoized: second call returns the identical cached value');
}

// Degenerate: no spectrum -> NaN (and NaN is memoizable without breaking).
{
  const r = new FftResult();
  ok(Number.isNaN(r.noisePeakFloorDbFs()), 'no spectrum -> NaN');
  const r2 = makeResult(64, 8);
  r2.amplitudeDbFs.fill(-Infinity);
  ok(Number.isNaN(r2.noisePeakFloorDbFs()) && Number.isNaN(r2.noisePeakFloorDbFs()),
    'all-non-finite spectrum -> NaN, stable across calls');
}

// Speed sanity at a realistic size (1M bins ≈ 2M FFT): quickselect must be fast.
{
  const n = 1 << 20;
  const r = makeResult(n, 5000);
  const t0 = performance.now();
  r.noisePeakFloorDbFs();
  const ms = performance.now() - t0;
  console.log(`  1M-bin floor computed in ${ms.toFixed(1)} ms`);
  ok(ms < 200, `1M-bin compute ${ms.toFixed(1)} ms < 200 ms (was a full comparator sort)`);
  const t1 = performance.now();
  for (let i = 0; i < 100; i++) r.noisePeakFloorDbFs();
  const msMemo = performance.now() - t1;
  ok(msMemo < 5, `100 memoized re-reads ${msMemo.toFixed(2)} ms < 5 ms (the 100 ms poll is now free)`);
}

console.log(`\nnoise-floor-select: ${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
