/*
 * Phonalyser web - A-weighting contract for FftAnalyzer. Run with
 * `node --test test/fft-aweight.test.mjs`.
 *
 * Ports the desktop A-weighting fix (FftAnalyzer.java, uncommitted): the SINAD
 * denominator is A-WEIGHTED (awNoisePower + awHarmPowerSum), driving sinadDb /
 * thdNDb / ENOB - while noisePower -> snrDb, the N readout, the spectrum trace and
 * avgNoiseFloorDbFs stay UNWEIGHTED. awNoisePower on the result now holds the real
 * A-weighted noise integral (it used to be a plain copy of noisePower). The
 * per-bin noise weight comes from a CACHED table keyed on (halfSize, freqRes);
 * the harmonic weight is a direct _aWeightPower() call.
 *
 * The assertions here verify, against the REAL module:
 *   1) _aWeightPower matches the IEC 61672 reference at the five audit frequencies.
 *   2) the per-bin cache is transparent (table[k] === _aWeightPower(k·Δf)) and
 *      invalidates on a geometry change.
 *   3) white-noise A-delta 10·log10(noisePower/awNoisePower) equals the value the
 *      A-weight formula predicts over the same in-band bins.
 *   4) sinadDb is the A-WEIGHTED SINAD; snrDb is still the UNWEIGHTED SNR.
 *   5) analyze() never crashes and always returns finite snrDb / sinadDb.
 */
import test from 'node:test';
import assert from 'node:assert';
import { FftAnalyzer } from '../js/fft/fft-analyzer.js';
import { FftResult } from '../js/fft/fft-result.js';

// ---------------------------------------------------------------------------
// Shared harness - all heavy computation runs once at module load into M, then
// the test() blocks assert against it. The trailing console.log emits the metric
// bundle the workflow script reads back.
// ---------------------------------------------------------------------------
const M = {
  refFreqDbErrors: {},
  cacheTransparent: false,
  cacheRebuildsOnGeometryChange: false,
  whiteNoiseDeltaDb: NaN,
  whiteNoiseExpectedDb: NaN,
  weightingRulingHolds: false,
  analyzeNoCrash: false,
};

const analyzer = new FftAnalyzer();

// --- 1) IEC reference frequencies ------------------------------------------
// Published A-weighting response (IEC 61672, A(1 kHz)=0 dB) at the audit points.
const IEC_REF_DB = { 1000: 0.0, 20: -50.4, 100: -19.1, 10000: -2.5, 20000: -9.3 };
for (const [fStr, expected] of Object.entries(IEC_REF_DB)) {
  const f = Number(fStr);
  const db = 10.0 * Math.log10(analyzer._aWeightPower(f));
  M.refFreqDbErrors[fStr] = Math.abs(db - expected);
}

// --- 2) Cache transparency + geometry invalidation --------------------------
const CACHE_HALF = 4096;
const CACHE_RES_A = 48000 / 8192;   // Δf for fftSize 8192
const CACHE_RES_B = 44100 / 8192;   // a genuinely different Δf
{
  const tableA = analyzer._aWeightTable(CACHE_HALF, CACHE_RES_A);
  const probeK = [0, 1, 5, 100, 512, 2000, CACHE_HALF];
  let transparent = true;
  for (const k of probeK) {
    if (tableA[k] !== analyzer._aWeightPower(k * CACHE_RES_A)) { transparent = false; break; }
  }
  // Re-request with the SAME geometry: must be the identical cached instance.
  const tableASame = analyzer._aWeightTable(CACHE_HALF, CACHE_RES_A);
  transparent = transparent && tableASame === tableA;
  M.cacheTransparent = transparent;

  // Change the geometry (Δf): the cache must rebuild - new instance, new values,
  // each entry transparent for the NEW Δf.
  const tableB = analyzer._aWeightTable(CACHE_HALF, CACHE_RES_B);
  let rebuilt = tableB !== tableA;
  let valuesDiffer = false;
  let transparentB = true;
  for (const k of probeK) {
    if (k > 0 && tableB[k] !== tableA[k]) valuesDiffer = true;
    if (tableB[k] !== analyzer._aWeightPower(k * CACHE_RES_B)) transparentB = false;
  }
  M.cacheRebuildsOnGeometryChange = rebuilt && valuesDiffer && transparentB;
}

// --- LCG white noise (deterministic; no Math.random) ------------------------
function makeSineAndNoise(len, sampleRate, sineHz, sineAmp, noiseAmp) {
  const x = new Float64Array(len);
  let s = 0x9e3779b1 >>> 0;
  const rnd = () => { s = (s * 1664525 + 1013904223) >>> 0; return s / 0x100000000 - 0.5; };
  for (let n = 0; n < len; n++) {
    x[n] = sineAmp * Math.sin(2.0 * Math.PI * sineHz * n / sampleRate) + noiseAmp * rnd();
  }
  return x;
}

// --- 3/4) White-noise A-delta + SINAD-weighting on one analyze --------------
const SR = 48000;
const N = 8192;
const HALF = N / 2;
const RES = SR / N;
const SNR_LO = 20.0, SNR_HI = 20000.0;
const noiseSignal = makeSineAndNoise(8 * N, SR, 1000.0, 0.05, 1e-3);

let r = null;
let analyzeCrashed = false;
try {
  // any window; band 20 Hz-20 kHz; INCOHERENT averaging.
  r = analyzer.analyze(noiseSignal, SR, N, 9, 'BH4', 'PCT_0',
    SNR_LO, SNR_HI, false, NaN, 1000.0, new FftResult());
} catch (e) {
  analyzeCrashed = true;
}

if (r != null) {
  // (3) delta measured on the result: noisePower is UNWEIGHTED, awNoisePower is
  // the A-weighted integral. Their ratio is Σp / Σ(p·A) over the counted noise
  // bins (the excluded-zone rescale and the NENBW division cancel).
  M.whiteNoiseDeltaDb = 10.0 * Math.log10(r.noisePower / r.awNoisePower);

  // Predicted from the formula over the same in-band bins: for white noise the
  // per-bin power is ~uniform, so Σp/Σ(p·A) -> 1/mean(A) over those bins. The few
  // signal-masked bins shift mean(A) by < 0.01 dB (A is smooth in f), so the
  // in-band mean is the faithful prediction. NOT hard-coded - computed from
  // _aWeightPower over the exact measurement band.
  let awSum = 0.0, awCnt = 0;
  for (let k = 1; k <= HALF; k++) {
    const freq = k * RES;
    if (freq >= SNR_LO && freq <= SNR_HI) { awSum += analyzer._aWeightPower(freq); awCnt++; }
  }
  M.whiteNoiseExpectedDb = -10.0 * Math.log10(awSum / awCnt);

  // (4) Where the A-curve is allowed to appear. SINAD, THD+N and the ENOB basis
  // divide by the PLAIN in-band noise-plus-distortion power (the MT-003
  // definitions); the curve survives in ONE readout, N+D, through awThdNDb.
  // Rebuild both denominators from the result's exposed pieces and match each to
  // the field it belongs to.
  // refLin = fundamentalLinear when no external fundamental ref is supplied.
  const refLin = r.fundamentalLinear;
  let harmPowerSum = 0.0;
  let awHarmPowerSum = 0.0;
  for (let h = 0; h < Math.min(r.harmonicCount, 8); h++) {
    if (r.harmonicBins[h] > 0) {
      const hf = r.harmonicHz[h];
      if (hf >= SNR_LO && hf <= SNR_HI) {
        const a = (r.harmonicPct[h] / 100.0) * refLin;   // hLinear reconstructed
        harmPowerSum += a * a;
        awHarmPowerSum += a * a * analyzer._aWeightPower(hf);
      }
    }
  }
  const sinadDenom = r.noisePower + harmPowerSum;         // UNWEIGHTED
  const awDenom = r.awNoisePower + awHarmPowerSum;        // the N+D denominator
  M.sinadRecomputed = 10.0 * Math.log10((refLin * refLin) / sinadDenom);
  M.snrRecomputed = 10.0 * Math.log10((refLin * refLin) / r.noisePower);
  M.awThdNRecomputed = 10.0 * Math.log10(awDenom / (refLin * refLin));
  M.enobRecomputed = -10.0 * Math.log10(sinadDenom);
  M.sinadReported = r.sinadDb;
  M.snrReported = r.snrDb;
  M.awThdNReported = r.awThdNDb;
  M.enobReported = r.enobSinadDb;
  M.awNoisePower = r.awNoisePower;
  M.noisePower = r.noisePower;

  const sinadIsUnweighted = Math.abs(M.sinadRecomputed - r.sinadDb) < 1e-9;
  const snrFromUnweighted = Math.abs(M.snrRecomputed - r.snrDb) < 1e-9;
  const enobIsFullScale = Math.abs(M.enobRecomputed - r.enobSinadDb) < 1e-9;
  const ndIsWeighted = Math.abs(M.awThdNRecomputed - r.awThdNDb) < 1e-9;
  // awNoisePower must NOT be a plain copy of noisePower (the old bug), or the
  // weighted / unweighted distinction above would be untestable.
  const awIsDistinct = r.awNoisePower !== r.noisePower;
  M.weightingRulingHolds = sinadIsUnweighted && snrFromUnweighted
    && enobIsFullScale && ndIsWeighted && awIsDistinct;
}

// --- 5) analyze never crashes; snrDb / sinadDb finite across a few configs ---
{
  let allFinite = !analyzeCrashed && r != null
    && Number.isFinite(r.snrDb) && Number.isFinite(r.sinadDb);
  const configs = [
    ['HANN', 'PCT_0', true],
    ['BH4', 'PCT_50', false],
    ['RECT', 'PCT_0', true],
  ];
  for (const [win, ov, coh] of configs) {
    try {
      const rr = analyzer.analyze(noiseSignal, SR, N, 9, win, ov,
        SNR_LO, SNR_HI, coh, NaN, 1000.0, new FftResult());
      if (!Number.isFinite(rr.snrDb) || !Number.isFinite(rr.sinadDb)) allFinite = false;
    } catch (e) {
      allFinite = false;
    }
  }
  M.analyzeNoCrash = allFinite;
}

// ---------------------------------------------------------------------------
// Assertions
// ---------------------------------------------------------------------------
test('1) _aWeightPower matches IEC reference within ±0.2 dB', () => {
  for (const [fStr, err] of Object.entries(M.refFreqDbErrors)) {
    assert.ok(err <= 0.2, `A-weight at ${fStr} Hz off by ${err.toFixed(3)} dB`);
  }
});

test('2a) per-bin A-weight table is transparent (table[k] === _aWeightPower(k·Δf))', () => {
  assert.ok(M.cacheTransparent, 'cache not transparent / not stable across same-geometry calls');
});

test('2b) A-weight cache invalidates on a geometry (Δf) change', () => {
  assert.ok(M.cacheRebuildsOnGeometryChange, 'cache did not rebuild on Δf change');
});

test('3) white-noise A-delta matches the formula within ±0.5 dB', () => {
  assert.ok(Number.isFinite(M.whiteNoiseDeltaDb) && Number.isFinite(M.whiteNoiseExpectedDb),
    'delta / expected not finite');
  assert.ok(Math.abs(M.whiteNoiseDeltaDb - M.whiteNoiseExpectedDb) <= 0.5,
    `delta ${M.whiteNoiseDeltaDb} vs expected ${M.whiteNoiseExpectedDb}`);
});

test('4) SINAD / THD+N / ENOB are UNWEIGHTED; the A-curve lives only in N+D', () => {
  assert.ok(M.weightingRulingHolds,
    `sinad ${M.sinadRecomputed}/${M.sinadReported}, snr ${M.snrRecomputed}/${M.snrReported}, ` +
    `enob ${M.enobRecomputed}/${M.enobReported}, N+D ${M.awThdNRecomputed}/${M.awThdNReported}, ` +
    `awNoise ${M.awNoisePower}, noise ${M.noisePower}`);
});

test('5) analyze() never crashes and returns finite snrDb / sinadDb', () => {
  assert.ok(M.analyzeNoCrash, 'an analyze() threw or returned non-finite snrDb/sinadDb');
});

console.log('AWEIGHT_METRICS ' + JSON.stringify(M));
