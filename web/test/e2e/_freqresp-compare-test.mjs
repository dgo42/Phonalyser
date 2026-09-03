/*
 * Phonalyser web - FreqResp compare-mode (measured − RIAA reference) math test
 * (no browser). Temp (_-prefixed) verification for step 6 of the FreqResp port:
 *
 *   (a) Anchor at 1 kHz: a SYNTHETIC measured curve that exactly equals the RIAA
 *       playback reference (so measDb − refDb is a constant offset everywhere) yields a
 *       smoothed compare curve that reads 0 dB at 1 kHz AND everywhere else (a constant
 *       minus its own 1 kHz value is 0). Asserts the value interpolated at 1 kHz is 0,
 *       and that min ≈ max ≈ 0 over the 20 Hz-25 kHz band.
 *   (b) A deliberate +3 dB bump added at 2 kHz survives the (lightly-smoothed) subtraction
 *       and shows up as a positive Δ near 2 kHz, while 1 kHz still reads 0 dB (the anchor).
 *
 * Run: node test/e2e/_freqresp-compare-test.mjs   (exits non-zero on any FAIL)
 */
import { FreqRespView } from '../../js/freqresp/freqresp-view.js';
import { evalDb } from '../../js/dsp/riaa.js';

let pass = 0, fail = 0;
const ok = (cond, msg) => { if (cond) { pass++; console.log('PASS: ' + msg); } else { fail++; console.log('FAIL: ' + msg); } };

const SR = 48000;
const F0 = 20, F1 = 24000;
const POINTS = 2048;

// Log-spaced output grid [F0, F1].
function logGrid(f0, f1, points) {
  const g = new Float64Array(points);
  const l0 = Math.log(f0), l1 = Math.log(f1);
  for (let i = 0; i < points; i++) g[i] = Math.exp(l0 + (l1 - l0) * (i / (points - 1)));
  return g;
}

function boundedStub(v) { let val = v; return { get: () => val, set: (x) => { val = x; }, addListener() {} }; }

console.log('\n== FreqResp compare-mode (measured − RIAA) math test ==');
console.log(`SR=${SR} points=${POINTS} band ${F0}-${F1} Hz\n`);

const freqs = logGrid(F0, F1, POINTS);

// A SYNTHETIC measured curve = the RIAA PLAYBACK reference (reverse=false, iec=false), shifted
// up by a constant +10 dB (a flat gain). measDb − refDb is then +10 dB at EVERY point -> after the
// 1 kHz anchor subtraction the compare curve is 0 dB everywhere.
const REF_REVERSE = false, REF_IEC = false;
const magLinFlat = new Float64Array(POINTS);
for (let i = 0; i < POINTS; i++) {
  const refDb = evalDb(freqs[i], REF_REVERSE, REF_IEC);
  magLinFlat[i] = Math.pow(10, (refDb + 10.0) / 20.0);   // measured = ref + 10 dB
}

const prefs = {
  freqRespCompareSmoothWindow: boundedStub(6),
  freqRespReverseRiaa: boundedStub(REF_REVERSE),
  freqRespIecAmendment: boundedStub(REF_IEC),
  freqRespCompareMode: boundedStub(true),
  freqRespShowRiaa: boundedStub(true),
  freqRespShowFilter: boundedStub(false),   // the RIAA reference, not the ideal-filter one
  freqRespLeftVisible: boundedStub(true),
  freqRespRightVisible: boundedStub(false),
};

// Headless: build a view object with just the fields _getCompareDiff reads (no canvas).
const view = Object.create(FreqRespView.prototype);
view.prefs = prefs;
view._compareDiffCache = null;
view._compareDiffCacheSrc = null;
view._compareDiffCacheReverse = false;
view._compareDiffCacheIec = false;
view._compareDiffCacheWindow = -1;

const srcFlat = { freqs, magLin: magLinFlat, phaseRad: new Float64Array(POINTS) };
const diffFlat = view._getCompareDiff(srcFlat, REF_REVERSE, REF_IEC);

// Δ at 1 kHz, interpolated from the same smoothed array the view draws / reads.
function interpAt(freqs, vals, f) {
  let lo = 0, hi = freqs.length - 1;
  while (hi - lo > 1) { const mid = (lo + hi) >>> 1; if (freqs[mid] <= f) lo = mid; else hi = mid; }
  const t = (Math.log(f) - Math.log(freqs[lo])) / (Math.log(freqs[hi]) - Math.log(freqs[lo]));
  return vals[lo] + t * (vals[hi] - vals[lo]);
}

const at1k = interpAt(freqs, diffFlat.smoothed, 1000.0);
ok(Math.abs(at1k) < 1e-6, `flat (measured = ref + 10 dB): compare curve reads 0 dB at 1 kHz (got ${at1k.toExponential(2)})`);
ok(Math.abs(diffFlat.minDb) < 1e-6 && Math.abs(diffFlat.maxDb) < 1e-6,
  `flat: min ≈ max ≈ 0 dB over 20 Hz-25 kHz (min ${diffFlat.minDb.toExponential(2)}, max ${diffFlat.maxDb.toExponential(2)})`);

// ── (b) A +3 dB bump at 2 kHz survives the subtraction (positive Δ near 2 kHz) ──────
const magLinBump = Float64Array.from(magLinFlat);
const BUMP_HZ = 2000.0, BUMP_DB = 3.0;
// Apply the bump over a narrow band around 2 kHz so the smoothing window doesn't wash it out.
for (let i = 0; i < POINTS; i++) {
  if (freqs[i] >= 1800 && freqs[i] <= 2200) magLinBump[i] *= Math.pow(10, BUMP_DB / 20.0);
}
const srcBump = { freqs, magLin: magLinBump, phaseRad: new Float64Array(POINTS) };
const diffBump = view._getCompareDiff(srcBump, REF_REVERSE, REF_IEC);
const at1kB = interpAt(freqs, diffBump.smoothed, 1000.0);
const at2kB = interpAt(freqs, diffBump.smoothed, BUMP_HZ);
ok(Math.abs(at1kB) < 0.05, `bump: 1 kHz still anchored to ~0 dB (got ${at1kB.toFixed(4)} dB)`);
ok(at2kB > 1.5, `bump: +3 dB at 2 kHz shows as a positive Δ (got ${at2kB.toFixed(3)} dB > 1.5)`);
ok(diffBump.maxDb >= at2kB - 1e-9, `bump: maxDb covers the 2 kHz peak (max ${diffBump.maxDb.toFixed(3)} dB)`);

// ── (c) Cache: same (src, reverse, iec, W) returns the SAME object (no recompute) ────
const again = view._getCompareDiff(srcBump, REF_REVERSE, REF_IEC);
ok(again === diffBump, 'compare-diff cache: an identical (src, reverse, iec, W) call returns the cached object');

console.log(`\n${fail === 0 ? 'ALL PASSED' : fail + ' FAILED'}  (${pass} passed, ${fail} failed)\n`);
process.exit(fail === 0 ? 0 : 1);
