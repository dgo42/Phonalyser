// Smoke test for the DAC-predistortion result-class fix. Temp (_-prefixed).
// Headless, no browser.
//
// Regression class this catches: "the emitted FFT result is a PLAIN object missing
// FftResult methods / raw-phasor fields". The FFT worker hands the engine a plain
// transfer object (postMessage snapshot) - NOT a FftResult instance. Before the fix
// the predistortion path consumed that plain object directly, so
//   - r.captureRawPeaks / r.rawHarmonicDbFs were undefined (not functions), and
//   - r.rawFundRe / r.rawPeakRe / r.rawPeakIm were never stamped,
// which made HarmonicCompensation.accumulate / IntermodCompensation.accumulate
// either throw (calling a missing method) or silently accumulate nothing.
//
// The fix is FftResult.adopt(plain) - re-adopt the plain transfer object as a real
// FftResult so its methods are callable - plus captureRawPeaks() on the main thread
// (re/im still raw) so the raw phasors exist for the de-embed.
//
// This test mimics the EXACT worker transfer shape (see js/audio/fft-worker.js `out`):
// a plain {} with typed arrays, no prototype, no raw-phasor fields. It then drives
// FftResult.adopt + captureRawPeaks and both compensation accumulators the way
// js/predistortion/engine.js does, asserting no throw + finite accumulator state.

import { FftResult } from '../../js/fft/fft-result.js';
import { HarmonicCompensation } from '../../js/predistortion/harmonic-compensation.js';
import { IntermodCompensation } from '../../js/predistortion/intermod-compensation.js';

let fails = 0, passes = 0;
const ok = (cond, msg) => {
  if (!cond) { console.log('FAIL: ' + msg); fails++; }
  else { console.log('ok: ' + msg); passes++; }
};

// ── Synthetic transfer-shape PLAIN object (the worker's `out`) ────────────────
// Single-tone 1 kHz @ 48 kHz, fftSize 16384. amplitudeDbFs/re/im are Float64Array
// of length fftSize/2+1; harmonic arrays populated for ~5 harmonics. Geometry is
// self-consistent: freqResolution = sampleRate/fftSize, fundamentalBin =
// round(f0/freqResolution). A non-trivial complex value sits at the fundamental and
// at each harmonic bin so the raw phasors and rawHarmonicDbFs come out finite.
const SAMPLE_RATE = 48000;
const FFT_SIZE = 16384;
const FREQ_RES = SAMPLE_RATE / FFT_SIZE;          // 2.9296875 Hz/bin
const BIN_COUNT = FFT_SIZE / 2 + 1;               // single-sided
const F0 = 1000;
const HARMONIC_COUNT = 5;                          // H2..H6

/** dBFS -> linear magnitude (the amplitude factor in the bins is assumed unity here:
 *  captureRawPeaks rescales by amplitudeDbFs/|phasor| so absolute level is exact). */
function buildSingleTonePlain() {
  const amplitudeDbFs = new Float64Array(BIN_COUNT);
  const phaseDeg = new Float64Array(BIN_COUNT);
  const re = new Float64Array(BIN_COUNT);
  const im = new Float64Array(BIN_COUNT);
  amplitudeDbFs.fill(-140);                         // broadband noise floor

  const fundamentalBin = Math.round(F0 / FREQ_RES); // 341
  const fundamentalHzRefined = F0;                  // exact for the test

  // Fundamental phasor: non-trivial complex value, ~ -1 dBFS level.
  re[fundamentalBin] = 0.80;
  im[fundamentalBin] = 0.30;
  amplitudeDbFs[fundamentalBin] = -1.0;

  const harmonicBins = new Int32Array(HARMONIC_COUNT);
  const harmonicHz = new Float64Array(HARMONIC_COUNT);
  const harmonicDbFs = new Float64Array(HARMONIC_COUNT);
  const harmonicPct = new Float64Array(HARMONIC_COUNT);
  for (let h = 0; h < HARMONIC_COUNT; h++) {
    const n = h + 2;                                // H2..H6
    const hz = n * F0;
    const bin = Math.round(hz / FREQ_RES);
    harmonicBins[h] = bin;
    harmonicHz[h] = hz;
    harmonicDbFs[h] = -60 - 6 * h;                  // descending harmonics
    harmonicPct[h] = Math.pow(10, (harmonicDbFs[h] - (-1.0)) / 20) * 100;
    // Non-trivial complex value at each harmonic bin.
    re[bin] = 0.012 / (h + 1);
    im[bin] = 0.005 * (h + 1);
    amplitudeDbFs[bin] = harmonicDbFs[h];
  }

  // PLAIN object - mirrors js/audio/fft-worker.js `out` exactly: scalars + typed
  // arrays, NO prototype, and crucially NO rawFundRe/rawPeakRe/rawPeakIm fields.
  return {
    id: 1, ms: 0.5,
    fftSize: FFT_SIZE, sampleRate: SAMPLE_RATE, frameCount: 8,
    freqResolution: FREQ_RES, windowType: 'BH4', overlap: 'PCT_0',
    amplitudeDbFs, phaseDeg, re, im,
    fundamentalBin, fundamentalHz: F0, fundamentalHzRefined,
    fundamental2HzRefined: NaN,
    fundamentalDbFs: -1.0, fundamentalLinear: Math.pow(10, -1.0 / 20),
    fundamentalTrueDbFs: NaN, fundamentalDynExclusionHz: 5,
    harmonicCount: HARMONIC_COUNT,
    harmonicBins, harmonicHz, harmonicDbFs, harmonicPct,
    thdPct: 0.1, thdDb: -60, thdNDb: -58, snrDb: 90, sinadDb: 88,
    snrFreqMin: 0, snrFreqMax: 0, coherentAveraging: true,
    noisePower: 1e-12, avgNoiseFloorDbFs: -140,
    imdProductA: null, imdProductB: null, imdProductBin: null,
    channelLeft: true,
  };
}

// ── 1. The plain transfer object is genuinely prototype-less ─────────────────
const plain = buildSingleTonePlain();
ok(!(plain instanceof FftResult), 'transfer object is a PLAIN object, not a FftResult');
ok(typeof plain.captureRawPeaks !== 'function', 'plain object has NO captureRawPeaks method (regression precondition)');
ok(plain.rawFundRe === undefined, 'plain object has NO rawFundRe field (regression precondition)');

// ── 2. adopt + captureRawPeaks restore methods and stamp raw phasors ─────────
const r = FftResult.adopt(plain);
r.captureRawPeaks();

ok(r instanceof FftResult, 'FftResult.adopt yields a FftResult instance');
ok(typeof r.rawHarmonicDbFs === 'function', 'r.rawHarmonicDbFs is a function after adopt');
ok(typeof r.captureRawPeaks === 'function', 'r.captureRawPeaks is a function after adopt');
ok(Number.isFinite(r.rawHarmonicDbFs(0)), `r.rawHarmonicDbFs(0) is finite (${r.rawHarmonicDbFs(0)})`);
ok(Number.isFinite(r.rawFundRe) && Number.isFinite(r.rawFundIm),
  `captureRawPeaks stamped a finite fundamental phasor (re=${r.rawFundRe}, im=${r.rawFundIm})`);
ok(r.rawPeakRe != null && r.rawPeakRe.length === HARMONIC_COUNT,
  `rawPeakRe stamped for ${HARMONIC_COUNT} harmonics (got ${r.rawPeakRe ? r.rawPeakRe.length : 'null'})`);
ok(r.rawPeakRe != null && Number.isFinite(r.rawPeakRe[0]) && Number.isFinite(r.rawPeakIm[0]),
  `rawPeak[0] phasor is finite (re=${r.rawPeakRe && r.rawPeakRe[0]}, im=${r.rawPeakIm && r.rawPeakIm[0]})`);

// ── 3. HarmonicCompensation.accumulate - single-tone path (engine.js:228) ────
{
  const maxH = HARMONIC_COUNT;
  const harm = new HarmonicCompensation(maxH);
  const calFundPhaseRad = 0.0;                       // no .frc loaded
  let threw = null;
  try {
    harm.accumulate(r, 1.0, calFundPhaseRad);        // COMP_STEP = 1.0
  } catch (e) { threw = e; }
  ok(threw === null, 'HarmonicCompensation.accumulate did NOT throw' + (threw ? ': ' + threw.message : ''));
  ok(harm.hasCorrections(), 'HarmonicCompensation accumulated a non-zero correction');
  ok(Number.isFinite(harm.accRe[0]) && Number.isFinite(harm.accIm[0]),
    `harm.accRe[0]/accIm[0] are finite (${harm.accRe[0]}, ${harm.accIm[0]})`);
  ok(harm.accRe[0] !== 0.0 || harm.accIm[0] !== 0.0, 'harm accumulator[0] is non-zero');
}

// ── 4. Dual-tone variant -> IntermodCompensation.accumulate (engine.js:219) ───
// Build a dual-tone transfer object: f1 = 1 kHz, f2 = 1.1 kHz, with an IMD-product
// grid the analyzer "de-rotated". The plain carries imdProductA/B/Bin (Int32Array,
// aligned) and the second-tone field fundamental2HzRefined - exactly the widened
// worker transfer shape. captureRawPeaks then stamps rawPeak[] over imdProductBin.
{
  const F1 = 1000, F2 = 1100;
  const dual = buildSingleTonePlain();
  dual.fundamental2HzRefined = F2;

  // Products the analyzer de-rotated this tick (must intersect IntermodCompensation's
  // fixed grid: (0,1)=F2, (1,1)=f1+f2, (2,-1)=2f1-f2, (1,2)=f1+2f2).
  const products = [
    [0, 1],   // F2 - used for the φ2 reference (productIndex(r,0,1))
    [1, 1],   // f1 + f2
    [2, -1],  // 2f1 - f2
    [1, 2],   // f1 + 2f2
  ];
  const nP = products.length;
  const imdProductA = new Int32Array(nP);
  const imdProductB = new Int32Array(nP);
  const imdProductBin = new Int32Array(nP);
  for (let i = 0; i < nP; i++) {
    const a = products[i][0], b = products[i][1];
    imdProductA[i] = a;
    imdProductB[i] = b;
    const fp = a * F1 + b * F2;
    const bin = Math.round(fp / FREQ_RES);
    imdProductBin[i] = bin;
    // Non-trivial complex value at each product bin so the conv-scaled raw phasor
    // (and hence the de-rotated magnitude) is finite and non-zero.
    if (bin > 0 && bin < dual.re.length) {
      dual.re[bin] = 0.008 / (i + 1);
      dual.im[bin] = 0.004 * (i + 1);
      dual.amplitudeDbFs[bin] = -70 - 4 * i;
    }
  }
  dual.imdProductA = imdProductA;
  dual.imdProductB = imdProductB;
  dual.imdProductBin = imdProductBin;

  const rd = FftResult.adopt(dual);
  rd.captureRawPeaks();
  ok(rd.rawPeakRe != null && rd.rawPeakRe.length === nP,
    `dual-tone rawPeakRe stamped over imdProductBin (len ${rd.rawPeakRe ? rd.rawPeakRe.length : 'null'} == ${nP})`);

  const imd = new IntermodCompensation(HARMONIC_COUNT);
  let threw = null;
  try {
    // Exactly as js/predistortion/engine.js:219 calls it.
    imd.accumulate(rd, rd.fundamentalHzRefined, rd.fundamental2HzRefined, 1.0,
      0.0,   // calF1PhaseRad (no .frc)
      0.0);  // calF2PhaseRad
  } catch (e) { threw = e; }
  ok(threw === null, 'IntermodCompensation.accumulate did NOT throw' + (threw ? ': ' + threw.message : ''));
  ok(imd.hasCorrections(), 'IntermodCompensation accumulated a non-zero correction');

  // Every populated accumulator slot must be finite (no NaN leaked from a missing
  // raw phasor / undefined method).
  let allFinite = true, nonZeroSlots = 0;
  for (let g = 0; g < imd.accRe.length; g++) {
    if (!Number.isFinite(imd.accRe[g]) || !Number.isFinite(imd.accIm[g])) allFinite = false;
    if (imd.accRe[g] !== 0.0 || imd.accIm[g] !== 0.0) nonZeroSlots++;
  }
  ok(allFinite, 'all IntermodCompensation accumulator entries are finite');
  ok(nonZeroSlots > 0, `IntermodCompensation has ${nonZeroSlots} non-zero product slot(s)`);

  // The de-embedded generator corrections must also be finite (the OUT path).
  const gc = imd.toGeneratorCorrections(() => [1.0, 0.0], 1.0, 1.0);
  let gcFinite = gc.ampRatios.length > 0;
  for (let i = 0; i < gc.ampRatios.length; i++) {
    if (!Number.isFinite(gc.ampRatios[i]) || !Number.isFinite(gc.phiInits[i])) gcFinite = false;
  }
  ok(gcFinite, `IntermodCompensation.toGeneratorCorrections is finite (${gc.ampRatios.length} product(s))`);
}

console.log(`predistortion-smoke: ${passes} passed, ${fails} failed`);
process.exit(fails === 0 ? 0 : 1);
