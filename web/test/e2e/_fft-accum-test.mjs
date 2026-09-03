/*
 * Phonalyser web - cross-tick FFT accumulator verification (no browser).
 *
 * Temp (_-prefixed) test for the cross-tick running accumulator ported from the
 * desktop FftAnalyzerWorker into js/fft/fft-accumulator.js + js/audio/fft-controller.js.
 *
 * Drives the EXACT analysis path the controller uses - FftAnalyzer (the per-window
 * brain) + FftAccumulator (the cross-tick fold) - in-process (the Worker is just an
 * off-thread shell around analyze(); here we call analyze() directly, which is what
 * the worker does). Synthesizes a pure 1 kHz sine at −6 dBFS @ 48 kHz and asserts:
 *
 *  (a) a spectrum is produced from only ~1-2 frames of samples (well under the
 *      avgTarget averaging depth) - the bug fix: the first spectrum no longer waits
 *      for the WHOLE averaging window to fill.
 *  (b) the fundamental is at 1 kHz with amplitude ≈ −6 dBFS (±0.5 dB).
 *  (c) feeding more ticks makes accumFrames / framesDone climb toward avgTarget AND
 *      drops the noise floor between bins (a deeper coherent average).
 *
 * Plus the long-run refinements (the deferred follow-ups now ported to full parity
 * with the desktop FftAnalyzerWorker):
 *  (d) LONG-RUN COHERENCE - an off-grid tone over ≥60 ∞-mode ticks: the κ phase-slope
 *      refine + phase-lock loop must HOLD the fundamental magnitude (≤0.2 dB drift);
 *      without them the pinned single-frame κ residual would vector-cancel it.
 *  (e) DUAL-TONE - two off-grid tones near −6 dBFS: the restart pins the pair (kappa1
 *      and kappa2), the published product grid a·kappa1 + b·kappa2 joins the harmonic
 *      ladder as extra de-rotation regions, and the region-constant rotation holds BOTH
 *      magnitudes (±0.3 dB) over a long coherent average (one shared reference phasor
 *      would smear the second tone).
 *
 * Run: node test/e2e/_fft-accum-test.mjs   (exits non-zero on any FAIL)
 */
import { FftAnalyzer } from '../../js/fft/fft-analyzer.js';
import { FftAccumulator } from '../../js/fft/fft-accumulator.js';

let pass = 0, fail = 0;
const ok = (cond, msg) => { if (cond) { pass++; console.log('PASS: ' + msg); } else { fail++; console.log('FAIL: ' + msg); } };

// ── Signal / analysis parameters (mirror the controller's _setupFftAnalysis) ──
const SR = 48000;
const FFT = 4096;            // power of 2; 1 kHz lands near bin 85.3 (sub-bin -> de-rotation exercised)
const TONE_HZ = 1000.0;
const AMP_DBFS = -6.0;
const AMP = Math.pow(10.0, AMP_DBFS / 20.0);   // peak amplitude for a −6 dBFS sine
const WINDOW = 'BH4';
const HARMONICS = 9;
const COHERENT = true;
const AVG_TARGET = 16;                          // ring depth (finite average)

const overlapFrac = 0.0;                         // PCT_0
const hop = Math.max(1, Math.round(FFT * (1 - overlapFrac)));
const perTickFrames = COHERENT ? 2 : 1;          // controller: coherent ? 2 : 1
const bufLen = FFT + (perTickFrames - 1) * hop;  // controller bufLen (sized to ~1-2 frames)
const targetN = 2 * AVG_TARGET;                  // controller ring cap (2·ringN)

// A small +noise so the noise floor is non-trivial and CAN drop with averaging.
// A fixed seed (deterministic) so the test is reproducible.
let seed = 0x12345678;
function rnd() {                                 // xorshift32 -> [−1, 1)
  seed ^= seed << 13; seed ^= seed >>> 17; seed ^= seed << 5; seed >>>= 0;
  return (seed / 0xffffffff) * 2 - 1;
}
const NOISE_AMP = 1e-3;                           // ≈ −60 dBFS broadband noise

/** The absolute-timeline sample at index n: the pure tone + a touch of noise. */
function sampleAt(n) {
  return AMP * Math.sin(2 * Math.PI * TONE_HZ * n / SR) + NOISE_AMP * rnd();
}

// One per-tick window built from absolute sample [absStart, absStart+bufLen).
function buildWindow(absStart) {
  const w = new Float64Array(bufLen);
  for (let i = 0; i < bufLen; i++) w[i] = sampleAt(absStart + i);
  return w;
}

const analyzer = new FftAnalyzer();
const accum = new FftAccumulator();

/** One controller tick: analyze the window (spectrum-only, like the accumulate
 *  path), fold into the accumulator, overlay + recomputeStats off the average.
 *  Returns the populated per-window result (now showing the cumulative average). */
function tick(absStart) {
  analyzer.setMultiTone(false);
  analyzer.setSecondToneHintHz(NaN);
  analyzer.setSpectrumOnly(true);                // accumulate path skips per-tick stats
  const r = analyzer.analyze(buildWindow(absStart), SR, FFT, HARMONICS,
    WINDOW, 'PCT_0', 0, 0, COHERENT, NaN, NaN);
  accum.accumulate(r, absStart, COHERENT, targetN);
  accum.overlayOnto(r);
  analyzer.setSpectrumOnly(false);
  analyzer.recomputeStats(r);
  return r;
}

// Noise-floor probe: median amplitudeDbFs of bins well away from the fundamental
// and its harmonics (the "grass between the bins"). Lower = deeper average.
function noiseFloorDbFs(r) {
  const half = r.fftSize / 2;
  const fundBin = Math.round(r.fundamentalHzRefined / r.freqResolution);
  const vals = [];
  for (let k = 8; k <= half - 8; k++) {
    // skip the fundamental lobe + each harmonic lobe (±6 bins)
    let nearSignal = Math.abs(k - fundBin) <= 6;
    for (let h = 2; h <= HARMONICS + 1 && !nearSignal; h++) {
      if (Math.abs(k - h * fundBin) <= 6) nearSignal = true;
    }
    if (!nearSignal && Number.isFinite(r.amplitudeDbFs[k])) vals.push(r.amplitudeDbFs[k]);
  }
  vals.sort((a, b) => a - b);
  return vals[Math.floor(vals.length / 2)];
}

console.log(`\n== cross-tick FFT accumulator test ==`);
console.log(`SR=${SR} fftSize=${FFT} tone=${TONE_HZ}Hz amp=${AMP_DBFS}dBFS window=${WINDOW} avgTarget=${AVG_TARGET}`);
console.log(`hop=${hop} perTickFrames=${perTickFrames} bufLen=${bufLen} (= N + (perTickFrames-1)*hop)\n`);

// ── (a) First spectrum from ~1-2 frames, NOT the whole averaging window ──────
// The OLD bug sized the per-tick window to N + (avgTarget-1)*hop. Prove ours is
// sized to perTickFrames frames and a usable spectrum comes out of frame 1.
const oldBufLen = FFT + (AVG_TARGET - 1) * hop;
ok(bufLen < oldBufLen,
  `per-tick window ${bufLen} samples << old whole-avg window ${oldBufLen} samples`);
ok(bufLen <= FFT + perTickFrames * hop,
  `per-tick window holds ≤ ${perTickFrames} frames (independent of avgTarget=${AVG_TARGET})`);

const r1 = tick(0);
ok(r1.frameCount <= perTickFrames && accum.accumFrames <= perTickFrames,
  `first spectrum produced from ${accum.accumFrames} frame(s) (≤ ${perTickFrames}, far below avgTarget ${AVG_TARGET})`);
ok(Number.isFinite(r1.amplitudeDbFs[r1.fundamentalBin]) && r1.amplitudeDbFs[r1.fundamentalBin] > -200,
  `first tick yields a real spectrum (fundamental bin is finite, not dead)`);

// ── (b) Fundamental at 1 kHz with amplitude ≈ −6 dBFS (±0.5 dB) ──────────────
const fundHzErr = Math.abs(r1.fundamentalHzRefined - TONE_HZ);
ok(fundHzErr < r1.freqResolution,
  `fundamental at ${r1.fundamentalHzRefined.toFixed(3)} Hz (target ${TONE_HZ}, err ${fundHzErr.toFixed(3)} Hz < ${r1.freqResolution.toFixed(2)} Hz/bin)`);
const fundDb = r1.fundamentalDbFs;
ok(Math.abs(fundDb - AMP_DBFS) <= 0.5,
  `fundamental amplitude ${fundDb.toFixed(3)} dBFS ≈ ${AMP_DBFS} dBFS (±0.5 dB)`);

// ── (c) Deeper average: accumFrames climbs toward avgTarget; noise floor drops ─
const floorAfter1 = noiseFloorDbFs(r1);
let rN = r1;
let absStart = 0;
const TICKS = 20;                                // > avgTarget so the ring fills
for (let t = 1; t < TICKS; t++) {
  absStart += hop;                               // uniform hop -> uniform de-rotation delta
  rN = tick(absStart);
}
const floorAfterN = noiseFloorDbFs(rN);
const depthTicks = Math.round(accum.accumFrames / perTickFrames);

ok(accum.accumFrames > perTickFrames,
  `accumFrames deepened: ${accum.accumFrames} frames after ${TICKS} ticks (started at ${perTickFrames})`);
ok(depthTicks >= AVG_TARGET,
  `displayed depth reached avgTarget: ${depthTicks}/${AVG_TARGET} (ring cap = ${targetN} frames)`);
ok(Math.abs(rN.fundamentalDbFs - AMP_DBFS) <= 0.5,
  `fundamental holds at ${rN.fundamentalDbFs.toFixed(3)} dBFS after the deep average (±0.5 dB)`);
ok(floorAfterN < floorAfter1 - 1.0,
  `noise floor dropped with averaging: ${floorAfter1.toFixed(2)} -> ${floorAfterN.toFixed(2)} dBFS (≥1 dB deeper)`);

// ── Ring bound: accumFrames must not run away past the ring cap ──────────────
ok(accum.accumFrames <= targetN,
  `ring accumulator bounded at ≤ targetN: ${accum.accumFrames} ≤ ${targetN} frames`);

// ── Incoherent (power) path sanity: still gives the right amplitude + deepens ─
{
  const iAcc = new FftAccumulator();
  const iAna = new FftAnalyzer();
  function itick(absStart) {
    iAna.setMultiTone(false); iAna.setSecondToneHintHz(NaN); iAna.setSpectrumOnly(true);
    const r = iAna.analyze(buildWindow(absStart), SR, FFT, HARMONICS, WINDOW, 'PCT_0', 0, 0, false, NaN, NaN);
    iAcc.accumulate(r, absStart, false, targetN);
    iAcc.overlayOnto(r);
    iAna.setSpectrumOnly(false); iAna.recomputeStats(r);
    return r;
  }
  let ir = itick(0);
  for (let t = 1, a = 0; t < TICKS; t++) { a += hop; ir = itick(a); }
  ok(Math.abs(ir.fundamentalDbFs - AMP_DBFS) <= 0.5,
    `incoherent power average: fundamental ${ir.fundamentalDbFs.toFixed(3)} dBFS ≈ ${AMP_DBFS} dBFS (±0.5 dB)`);
  ok(iAcc.accumFrames > perTickFrames,
    `incoherent accumFrames deepened: ${iAcc.accumFrames} frames`);
}

// ════════════════════════════════════════════════════════════════════════════
// LONG-RUN COHERENCE (κ phase-slope refine + phase-lock loop)
// ════════════════════════════════════════════════════════════════════════════
// Feed MANY (≥60) ticks of a stable tone whose TRUE frequency is off the bin grid
// by a fraction of a bin. The single-frame κ the accumulator pins on tick 0 carries
// a tiny residual error; WITHOUT the κ refine + PLL that residual ramps the de-
// rotated fundamental phase (2π·Δκ·delta/N) and slowly vector-cancels the coherent
// magnitude over the run. With them, the fundamental magnitude must HOLD. Uses ∞
// (forever) mode so the running sum is a true cumulative mean (no ring-window scale).
{
  // A tone landing ~0.37 bin off an integer bin so the single-frame κ is imperfect
  // (true ≠ round) - exactly the case the refine + PLL exist for.
  const LR_FFT = 4096;
  const LR_BIN_OFF = 100 + 0.37;                 // fractional bin (off-grid)
  const LR_TONE_HZ = LR_BIN_OFF * SR / LR_FFT;   // ≈ 1176.7 Hz
  const LR_AMP = Math.pow(10.0, AMP_DBFS / 20.0);
  const LR_HOP = LR_FFT;                          // PCT_0
  const LR_PERTICK = 2;                           // coherent ? 2 : 1
  const LR_BUFLEN = LR_FFT + (LR_PERTICK - 1) * LR_HOP;
  let lseed = 0x2468ace0;
  const lrnd = () => { lseed ^= lseed << 13; lseed ^= lseed >>> 17; lseed ^= lseed << 5; lseed >>>= 0; return (lseed / 0xffffffff) * 2 - 1; };
  const lrSampleAt = (n) => LR_AMP * Math.sin(2 * Math.PI * LR_TONE_HZ * n / SR) + NOISE_AMP * lrnd();
  const lrWindow = (absStart) => { const w = new Float64Array(LR_BUFLEN); for (let i = 0; i < LR_BUFLEN; i++) w[i] = lrSampleAt(absStart + i); return w; };

  const lAna = new FftAnalyzer();
  const lAcc = new FftAccumulator();
  // Magnitude (dB) of the cumulative-average fundamental bin - the quantity the
  // coherent average must hold (a vector cancellation shows up here directly).
  const fundMagDb = (r) => {
    const fb = Math.round(r.fundamentalHzRefined / r.freqResolution);
    return r.amplitudeDbFs[fb];
  };
  const lTick = (absStart) => {
    lAna.setMultiTone(false); lAna.setSecondToneHintHz(NaN); lAna.setSpectrumOnly(true);
    const r = lAna.analyze(lrWindow(absStart), SR, LR_FFT, HARMONICS, WINDOW, 'PCT_0', 0, 0, true, NaN, NaN);
    lAcc.accumulate(r, absStart, true, Infinity);   // ∞ forever-mode cumulative mean
    lAcc.overlayOnto(r);
    lAna.setSpectrumOnly(false); lAna.recomputeStats(r);
    return r;
  };

  const LR_TICKS = 80;                             // > KAPPA_MEAS_FRAMES window so the refine + PLL engage
  let lrEarlyDb = null;
  let lrLateDb = null;
  let a = 0;
  for (let t = 0; t < LR_TICKS; t++) {
    const r = lTick(a);
    a += LR_HOP;
    if (t === 4) lrEarlyDb = fundMagDb(r);         // a few ticks in (κ pinned, average shallow)
    if (t === LR_TICKS - 1) lrLateDb = fundMagDb(r);
  }
  const lrDriftDb = Math.abs(lrLateDb - lrEarlyDb);
  ok(lrDriftDb <= 0.2,
    `long-run coherence: off-grid (${LR_BIN_OFF} bin) fundamental held ${lrEarlyDb.toFixed(3)}->${lrLateDb.toFixed(3)} dBFS over ${LR_TICKS} ticks (drift ${lrDriftDb.toFixed(3)} dB ≤ 0.2)`);
  ok(Math.abs(lrLateDb - AMP_DBFS) <= 0.5,
    `long-run coherence: fundamental still ≈ ${AMP_DBFS} dBFS after the deep average (${lrLateDb.toFixed(3)} dBFS, ±0.5 dB)`);
}

// ════════════════════════════════════════════════════════════════════════════
// DUAL-TONE (the pinned pair + the product grid riding its own member)
// ════════════════════════════════════════════════════════════════════════════
// Two strong tones (1 kHz + 1.1 kHz), both near −6 dBFS, both off-grid. Assert the
// dual pair is PINNED at the restart and that BOTH fundamentals hold their magnitude
// over a long coherent average - F2 rides its own (0,1) grid member, whereas one
// shared reference phasor would smear it (it would ride the wrong κ).
{
  // fftSize 32768 -> bin width ≈ 1.46 Hz, so 1 kHz and 1.1 kHz are ~68 bins apart.
  const MT_FFT = 32768;
  const MT_F1_HZ = 1000.0 + 0.41 * SR / MT_FFT;   // off-grid tone 1 (~1000.6 Hz)
  const MT_F2_HZ = 1100.0 + 0.63 * SR / MT_FFT;   // off-grid tone 2 (~1100.9 Hz)
  const MT_AMP = Math.pow(10.0, AMP_DBFS / 20.0);
  const MT_HOP = MT_FFT;
  const MT_PERTICK = 2;
  const MT_BUFLEN = MT_FFT + (MT_PERTICK - 1) * MT_HOP;
  let mseed = 0x13579bdf;
  const mrnd = () => { mseed ^= mseed << 13; mseed ^= mseed >>> 17; mseed ^= mseed << 5; mseed >>>= 0; return (mseed / 0xffffffff) * 2 - 1; };
  const mtSampleAt = (n) => MT_AMP * Math.sin(2 * Math.PI * MT_F1_HZ * n / SR)
                          + MT_AMP * Math.sin(2 * Math.PI * MT_F2_HZ * n / SR)
                          + NOISE_AMP * mrnd();
  const mtWindow = (absStart) => { const w = new Float64Array(MT_BUFLEN); for (let i = 0; i < MT_BUFLEN; i++) w[i] = mtSampleAt(absStart + i); return w; };

  const mAna = new FftAnalyzer();
  const mAcc = new FftAccumulator();
  // Magnitude (dB) at a given true frequency's nearest bin off the cumulative average.
  const magDbAtHz = (r, hz) => {
    const fb = Math.round(hz / r.freqResolution);
    return r.amplitudeDbFs[fb];
  };
  const mTick = (absStart) => {
    mAna.setMultiTone(true); mAna.setSecondToneHintHz(MT_F2_HZ); mAna.setSpectrumOnly(true);
    const r = mAna.analyze(mtWindow(absStart), SR, MT_FFT, HARMONICS, WINDOW, 'PCT_0', 0, 0, true, NaN, NaN);
    mAcc.accumulate(r, absStart, true, Infinity);
    mAcc.overlayOnto(r);
    mAna.setSpectrumOnly(false); mAna.recomputeStats(r);
    return r;
  };

  // Tick 0 pins the accumulator's coherent family (the restart pins κ1 and κ2).
  // The family is KNOWLEDGE, not a spectral property: nothing is hunted in the
  // spectrum. A dual pair is pinned when the analyzer published a second
  // fundamental, and the published product grid a·κ1+b·κ2 then joins the harmonic
  // ladder as extra regions. Assert exactly that: κ2 pinned on F2's fractional bin.
  const r0 = mTick(0);
  const f2Bin = MT_F2_HZ / r0.freqResolution;
  ok(mAcc._accumKappa2 > 0 && Math.abs(mAcc._accumKappa2 - f2Bin) < 1.0,
    `the dual pair is pinned: kappa2=${mAcc._accumKappa2.toFixed(3)} vs F2 bin ${f2Bin.toFixed(3)}`);

  const MT_TICKS = 80;
  let f1Early = magDbAtHz(r0, MT_F1_HZ), f2Early = magDbAtHz(r0, MT_F2_HZ);
  let f1Late = f1Early, f2Late = f2Early;
  let aa = 0;
  for (let t = 1; t < MT_TICKS; t++) {
    aa += MT_HOP;
    const r = mTick(aa);
    if (t === 4) { f1Early = magDbAtHz(r, MT_F1_HZ); f2Early = magDbAtHz(r, MT_F2_HZ); }
    if (t === MT_TICKS - 1) { f1Late = magDbAtHz(r, MT_F1_HZ); f2Late = magDbAtHz(r, MT_F2_HZ); }
  }
  const f1Drift = Math.abs(f1Late - f1Early);
  const f2Drift = Math.abs(f2Late - f2Early);
  ok(f1Drift <= 0.3,
    `dual-tone: F1 (~${MT_F1_HZ.toFixed(1)} Hz) held ${f1Early.toFixed(3)}->${f1Late.toFixed(3)} dBFS over ${MT_TICKS} ticks (drift ${f1Drift.toFixed(3)} dB ≤ 0.3)`);
  ok(f2Drift <= 0.3,
    `dual-tone: F2 (~${MT_F2_HZ.toFixed(1)} Hz) held ${f2Early.toFixed(3)}->${f2Late.toFixed(3)} dBFS over ${MT_TICKS} ticks (drift ${f2Drift.toFixed(3)} dB ≤ 0.3)`);
  // Both tones near −6 dBFS still (no lobe collapsed into the grid). This check reads
  // the single nearest bin, so an off-grid tone scallops a few tenths of a dB low
  // (F2 sits ~0.63 bin off -> closer to the half-bin worst case) - the ±0.8 dB band
  // proves the lobe SURVIVED at full level, which is the point; the magnitude-HOLD
  // assertions above (drift ≤ 0.3 dB) are the real region-constant de-rotation test.
  ok(Math.abs(f1Late - AMP_DBFS) <= 0.8 && Math.abs(f2Late - AMP_DBFS) <= 0.8,
    `dual-tone: both fundamentals ≈ ${AMP_DBFS} dBFS after the deep average (F1 ${f1Late.toFixed(2)}, F2 ${f2Late.toFixed(2)} dBFS, ±0.8 dB)`);
}

console.log(`\n${fail === 0 ? 'ALL PASSED' : fail + ' FAILED'}  (${pass} passed, ${fail} failed)\n`);
process.exit(fail === 0 ? 0 : 1);
