/*
 * Phonalyser web - pool-path ≡ serial-path equivalence (no browser).
 *
 * The threads>1 FFT path was moved OFF the main thread into fft-worker.js: the
 * worker runs prelude -> splits the frame ranges across nested fft-pool-worker.js
 * siblings (accumulatePartial) -> mergePartials -> finalize. Node has no Web
 * Workers, so this drives the EXACT same FftAnalyzer stage split in-process and
 * asserts it produces the SAME result as the single-shot analyze() the serial
 * path runs - for several worker counts W, including splits with a remainder.
 *
 * Run: node test/e2e/_fft-pool-equivalence-test.mjs   (exits non-zero on any FAIL)
 */
import { FftAnalyzer } from '../../js/fft/fft-analyzer.js';
import { FftResult } from '../../js/fft/fft-result.js';

let pass = 0, fail = 0;
const ok = (cond, msg) => { if (cond) { pass++; console.log('PASS: ' + msg); } else { fail++; console.log('FAIL: ' + msg); } };

// ── Signal / analysis parameters (mirror _fft-accum-test) ──
const SR = 48000;
const FFT = 4096;
const TONE_HZ = 1000.0;            // ~bin 85.3 -> sub-bin de-rotation exercised
const AMP = Math.pow(10.0, -6.0 / 20.0);
const WINDOW = 'BH4';
const HARMONICS = 9;
const OVERLAP = 'PCT_50';          // 50% overlap -> multi-frame window
const COHERENT = true;

let seed = 0x2468ace1;
function rnd() {
  seed ^= seed << 13; seed ^= seed >>> 17; seed ^= seed << 5; seed >>>= 0;
  return (seed / 0xffffffff) * 2 - 1;
}

// 7 frames at 50% overlap: bufLen = FFT + 6·hop (hop = FFT/2).
const FRAMES = 7;
const hop = FFT / 2;
const bufLen = FFT + (FRAMES - 1) * hop;
const samples = new Float64Array(bufLen);
for (let n = 0; n < bufLen; n++) {
  samples[n] = AMP * Math.sin(2 * Math.PI * TONE_HZ * n / SR) + 1e-3 * rnd();
}

console.log('\n== FFT pool-path equivalence (prelude+split+merge+finalize vs analyze) ==');
console.log(`SR=${SR} fftSize=${FFT} tone=${TONE_HZ}Hz window=${WINDOW} overlap=50% frames=${FRAMES}`);

// ── Reference: the serial analyze() (what the worker runs at threads=1). ──
const refAnalyzer = new FftAnalyzer();
const refSlot = new FftResult();
const ref = refAnalyzer.analyze(samples, SR, FFT, HARMONICS, WINDOW, OVERLAP,
  0, 0, COHERENT, NaN, TONE_HZ, refSlot);
const refAmp = ref.amplitudeDbFs.slice();
const refFund = { bin: ref.fundamentalBin, hz: ref.fundamentalHzRefined, dbfs: ref.fundamentalDbFs };
const refThd = ref.thdPct, refSnr = ref.snrDb, refFrames = ref.frameCount;

ok(refFrames === FRAMES, `reference analyze() sees ${refFrames} frames (expected ${FRAMES})`);

// ── Pooled stage split, in-process, for several W (incl. remainder splits). ──
for (const W of [2, 3, 5]) {
  const a = new FftAnalyzer();          // fresh analyzer per W (worker isolation)
  const slot = new FftResult();
  const sp = a.prelude(samples, SR, FFT, HARMONICS, WINDOW, OVERLAP, COHERENT, TONE_HZ, slot);
  ok(sp.frameCount === FRAMES, `W=${W}: prelude frameCount=${sp.frameCount} (expected ${FRAMES})`);

  // Contiguous ranges exactly as fft-worker.js splits them.
  const base = Math.floor(sp.frameCount / W), rem = sp.frameCount % W;
  const partials = [];
  let frameStart = 0;
  for (let i = 0; i < W; i++) {
    const frameEnd = frameStart + base + (i < rem ? 1 : 0);
    // Each pool worker uses its OWN analyzer instance on its OWN window copy.
    const pw = new FftAnalyzer();
    partials.push(pw.accumulatePartial(samples.slice(), frameStart, frameEnd, sp));
    frameStart = frameEnd;
  }
  const merged = a.mergePartials(partials, sp);
  const r = a.finalize(merged, sp, 0, 0, NaN, slot);

  ok(r.frameCount === refFrames, `W=${W}: frameCount ${r.frameCount} == serial ${refFrames}`);

  let maxDiff = 0;
  for (let k = 0; k < refAmp.length; k++) {
    const d = Math.abs(r.amplitudeDbFs[k] - refAmp[k]);
    if (Number.isFinite(d) && d > maxDiff) maxDiff = d;
  }
  ok(maxDiff < 1e-6, `W=${W}: spectrum matches serial (max |Δ| = ${maxDiff.toExponential(2)} dB < 1e-6)`);
  ok(r.fundamentalBin === refFund.bin, `W=${W}: fundamental bin ${r.fundamentalBin} == ${refFund.bin}`);
  ok(Math.abs(r.fundamentalHzRefined - refFund.hz) < 1e-9,
    `W=${W}: refined fundamental ${r.fundamentalHzRefined.toFixed(6)} == ${refFund.hz.toFixed(6)} Hz`);
  ok(Math.abs(r.fundamentalDbFs - refFund.dbfs) < 1e-6,
    `W=${W}: fundamental ${r.fundamentalDbFs.toFixed(6)} dBFS == ${refFund.dbfs.toFixed(6)}`);
  ok(Math.abs(r.thdPct - refThd) / Math.max(refThd, 1e-12) < 1e-6,
    `W=${W}: THD ${r.thdPct.toExponential(6)}% == ${refThd.toExponential(6)}%`);
  ok(Math.abs(r.snrDb - refSnr) < 1e-6, `W=${W}: SNR ${r.snrDb.toFixed(6)} dB == ${refSnr.toFixed(6)} dB`);
}

// Degenerate split (frameCount < W): the worker accumulates 0..frameCount itself.
{
  const a = new FftAnalyzer();
  const slot = new FftResult();
  const sp = a.prelude(samples, SR, FFT, HARMONICS, WINDOW, OVERLAP, COHERENT, TONE_HZ, slot);
  const part = a.accumulatePartial(samples, 0, sp.frameCount, sp);
  const r = a.finalize(part, sp, 0, 0, NaN, slot);
  ok(Math.abs(r.fundamentalDbFs - refFund.dbfs) < 1e-6,
    `frameCount<W fallback: fundamental ${r.fundamentalDbFs.toFixed(6)} dBFS == serial`);
}

console.log(`\nfft-pool-equivalence: ${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
