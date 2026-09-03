/*
 * Phonalyser web - parallel-FFT correctness contract. Run with
 * `node web/test/fft-parallel.test.mjs`.
 *
 * The pool path parallelises the AVERAGING FRAMES: a single-threaded prelude
 * (shared kFractional / kappa2 / IMD grid) feeds W workers, each of which
 * accumulates a contiguous frame range via accumulatePartial; the partials are
 * summed (associative) and finalized. This test proves - WITHOUT real Workers -
 * that splitting [0,frameCount) into W disjoint ranges and merging is identical
 * to the serial analyze() across the full config matrix.
 *
 * For every (fftSize, averages, overlap, coherent) it runs:
 *   1. serial:   analyzer.analyze(...)
 *   2. parallel: prelude -> accumulatePartial over each of W disjoint ranges ->
 *                mergePartials -> finalize  (W simulated in-process)
 * and asserts the spectra (amplitudeDbFs/re/im sampled bins) AND the key stats
 * (fundamentalHzRefined, thdPct, snrDb, avgNoiseFloorDbFs) match within ~1e-9
 * (relative for large magnitudes).
 */
import { FftAnalyzer } from '../js/fft/fft-analyzer.js';
import { FftResult } from '../js/fft/fft-result.js';

let passed = 0, failed = 0;
const fails = [];
function ok(cond, msg) { if (cond) passed++; else { failed++; fails.push(msg); } }

/** Relative-tolerant compare: |a−b| ≤ eps·max(1,|a|,|b|). */
function close(a, b, eps) {
  if (Number.isNaN(a) && Number.isNaN(b)) return true;
  const scale = Math.max(1.0, Math.abs(a), Math.abs(b));
  return Math.abs(a - b) <= eps * scale;
}

/** Deterministic multi-harmonic + noise signal (no Math.random -> reproducible). */
function makeSignal(len, sampleRate, f0) {
  const x = new Float64Array(len);
  const tones = [[f0, 1.0, 0.3], [2 * f0, 0.05, 1.1], [3 * f0, 0.02, 2.4],
                 [4 * f0, 0.008, 0.7], [5 * f0, 0.003, 1.9]];
  // Cheap deterministic PRNG (LCG) for the broadband noise floor.
  let s = 0x2545f491;
  const rnd = () => { s = (s * 1664525 + 1013904223) >>> 0; return s / 0x100000000 - 0.5; };
  for (let n = 0; n < len; n++) {
    let v = 0;
    for (const [f, a, ph] of tones) v += a * Math.sin(2 * Math.PI * f * n / sampleRate + ph);
    v += 1e-4 * rnd();
    x[n] = v;
  }
  return x;
}

const EPS = 1e-9;
const FFT_SIZES = [1024, 4096];
const AVERAGES = [1, 4, 16];
const OVERLAPS = ['PCT_0', 'PCT_50'];
const COHERENT = [true, false];
const POOL_W = [1, 2, 3, 4, 8];          // worker counts to simulate per config
const SAMPLE_RATE = 48000;
const HARMONICS = 9;
const WINDOW = 'BH4';
const OVERLAP_FRAC = { PCT_0: 0.0, PCT_50: 0.5 };

let configs = 0;
for (const fftSize of FFT_SIZES) {
  // Tone placed ~0.37 bin off the grid so the sub-bin κ refinement is exercised.
  const toneHz = 50 * SAMPLE_RATE / fftSize + 0.37 * SAMPLE_RATE / fftSize;  // 50.37 bins
  for (const averages of AVERAGES) {
    for (const overlap of OVERLAPS) {
      const step = Math.max(1, Math.round(fftSize * (1 - OVERLAP_FRAC[overlap])));
      const len = fftSize + (averages - 1) * step;
      const signal = makeSignal(len, SAMPLE_RATE, toneHz);
      for (const coherent of COHERENT) {
        configs++;
        const tag = `N=${fftSize} avg=${averages} ov=${overlap} coh=${coherent}`;

        // --- serial reference ---
        const serialAz = new FftAnalyzer();
        const serial = serialAz.analyze(signal, SAMPLE_RATE, fftSize, HARMONICS,
          WINDOW, overlap, 0, 0, coherent, NaN, toneHz, new FftResult());

        for (const W of POOL_W) {
          // --- parallel path: prelude -> W disjoint accumulatePartial -> merge -> finalize ---
          const az = new FftAnalyzer();
          const slot = new FftResult();
          const sp = az.prelude(signal, SAMPLE_RATE, fftSize, HARMONICS,
            WINDOW, overlap, coherent, toneHz, slot);
          const fc = sp.frameCount;
          // Contiguous ranges covering [0, fc) - first (fc % W) get one extra frame.
          const effW = Math.min(W, fc);
          const base = Math.floor(fc / effW);
          const rem = fc % effW;
          const partials = [];
          let fs = 0;
          for (let i = 0; i < effW; i++) {
            const len2 = base + (i < rem ? 1 : 0);
            const fe = fs + len2;
            partials.push(az.accumulatePartial(signal, fs, fe, sp));
            fs = fe;
          }
          ok(fs === fc, `${tag} W=${W}: ranges cover all frames (${fs}/${fc})`);
          const merged = az.mergePartials(partials, sp);
          const par = az.finalize(merged, sp, 0, 0, NaN, slot);

          // --- frameCount + key stats ---
          ok(par.frameCount === serial.frameCount, `${tag} W=${W}: frameCount`);
          ok(close(par.fundamentalHzRefined, serial.fundamentalHzRefined, EPS),
            `${tag} W=${W}: fundamentalHzRefined ${par.fundamentalHzRefined} vs ${serial.fundamentalHzRefined}`);
          ok(close(par.thdPct, serial.thdPct, EPS),
            `${tag} W=${W}: thdPct ${par.thdPct} vs ${serial.thdPct}`);
          ok(close(par.snrDb, serial.snrDb, EPS),
            `${tag} W=${W}: snrDb ${par.snrDb} vs ${serial.snrDb}`);
          ok(close(par.avgNoiseFloorDbFs, serial.avgNoiseFloorDbFs, EPS),
            `${tag} W=${W}: avgNoiseFloorDbFs ${par.avgNoiseFloorDbFs} vs ${serial.avgNoiseFloorDbFs}`);

          // --- sampled spectrum bins (amplitudeDbFs, re, im) ---
          const half = fftSize / 2;
          const sampleBins = [0, 1, 25, 50, 51, 100, 151, half >> 1, half - 1, half];
          for (const k of sampleBins) {
            ok(close(par.amplitudeDbFs[k], serial.amplitudeDbFs[k], EPS),
              `${tag} W=${W}: amplitudeDbFs[${k}] ${par.amplitudeDbFs[k]} vs ${serial.amplitudeDbFs[k]}`);
            ok(close(par.re[k], serial.re[k], EPS),
              `${tag} W=${W}: re[${k}] ${par.re[k]} vs ${serial.re[k]}`);
            ok(close(par.im[k], serial.im[k], EPS),
              `${tag} W=${W}: im[${k}] ${par.im[k]} vs ${serial.im[k]}`);
          }
        }
      }
    }
  }
}

console.log(`\nfft-parallel: ${configs} configs × {W=1,2,3,4,8}`);
console.log(`fft-parallel: ${passed} passed, ${failed} failed`);
if (failed) { for (const f of fails.slice(0, 20)) console.error('FAIL:', f); }
process.exit(failed ? 1 : 0);
