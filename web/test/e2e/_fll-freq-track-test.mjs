/*
 * Phonalyser web - FLL frequency-tracking regression (no browser).
 *
 * ROOT-CAUSE guard for the frequency-lock loop. The FLL (js/audio/fft-controller.js
 * applyFrequencyLock) nulls detected−target by steering the generator; it reads the
 * detected tone frequency off r.fundamentalHzRefined. If that value is PINNED to the
 * generator hint (the commanded/expected frequency) rather than the TRUE captured
 * tone frequency in the ADC timebase, the loop always sees detected≈target and never
 * corrects the real DAC↔ADC clock offset - the FLL is inert.
 *
 * This test synthesises a pure tone at a KNOWN true frequency F that is OFFSET from
 * the hint (simulating an ~80 ppm clock offset between what we command and what the
 * ADC sees), drives the EXACT analysis path the controller uses - FftAnalyzer.analyze
 * (spectrum-only, the accumulate path) + FftAccumulator (cross-tick fold) + overlay +
 * recomputeStats - over many coherent ∞-mode ticks, then asserts:
 *
 *   fundamentalHzRefined ≈ F (the true captured frequency)   NOT   ≈ hint.
 *
 * With the analyzer measuring the true phase-slope frequency (Java FftAnalyzer:
 * `fundHzRefined = kFractional * freqRes`, kFractional from the fundamental's inter-
 * frame phase advance - the hint only picks the search bin), refined tracks F and the
 * assertion passes. If a regression re-pins fundamentalHzRefined to the hint, refined
 * reads ≈1002.40 and this test FAILS.
 *
 * Run: node test/e2e/_fll-freq-track-test.mjs   (exits non-zero on any FAIL)
 */
import { FftAnalyzer } from '../../js/fft/fft-analyzer.js';
import { FftAccumulator } from '../../js/fft/fft-accumulator.js';

let pass = 0, fail = 0;
const ok = (cond, msg) => { if (cond) { pass++; console.log('PASS: ' + msg); } else { fail++; console.log('FAIL: ' + msg); } };

// ── Signal / analysis parameters (mirror the controller's live FFT setup) ─────
const SR = 48000;
const FFT = 32768;              // freq-res ≈ 1.46 Hz; the 80 ppm offset (~0.08 Hz) is sub-bin
const WINDOW = 'BH4';
const HARMONICS = 9;
const COHERENT = true;

// The generator commands (and the FLL targets) this frequency - the HINT passed to
// analyze as expectedFundHz (Java fftFundFromGenerator ⇒ snapped).
const HINT_HZ = 1002.40;
// The ADC actually captures the tone ~80 ppm LOW (a real DAC↔ADC clock offset):
// the true tone frequency in the ADC timebase. The FLL must SEE this, not the hint.
const CLOCK_PPM = -80;
const TRUE_HZ = HINT_HZ * (1.0 + CLOCK_PPM * 1e-6);   // ≈ 1002.3198 Hz
const AMP = Math.pow(10.0, -6.0 / 20.0);              // −6 dBFS sine

const overlapFrac = 0.0;                              // PCT_0
const hop = Math.max(1, Math.round(FFT * (1 - overlapFrac)));
const perTickFrames = COHERENT ? 2 : 1;              // controller: coherent ? 2 : 1
const bufLen = FFT + (perTickFrames - 1) * hop;      // ~1-2 frames per tick

// A touch of deterministic noise so the spectrum is realistic.
let seed = 0x0badf00d;
const rnd = () => { seed ^= seed << 13; seed ^= seed >>> 17; seed ^= seed << 5; seed >>>= 0; return (seed / 0xffffffff) * 2 - 1; };
const NOISE_AMP = 1e-4;

// The absolute-timeline capture sample at index n: the TRUE-frequency tone (+noise).
const sampleAt = (n) => AMP * Math.sin(2 * Math.PI * TRUE_HZ * n / SR) + NOISE_AMP * rnd();
const buildWindow = (absStart) => { const w = new Float64Array(bufLen); for (let i = 0; i < bufLen; i++) w[i] = sampleAt(absStart + i); return w; };

const analyzer = new FftAnalyzer();
const accum = new FftAccumulator();

// One controller tick: analyze the window with expectedFundHz = HINT (the FLL/gen
// hint), fold into the accumulator, overlay + recomputeStats off the deep average.
function tick(absStart) {
  analyzer.setMultiTone(false);
  analyzer.setSecondToneHintHz(NaN);
  analyzer.setSpectrumOnly(true);                    // accumulate path skips per-tick stats
  const r = analyzer.analyze(buildWindow(absStart), SR, FFT, HARMONICS,
    WINDOW, 'PCT_0', 0, 0, COHERENT, NaN, HINT_HZ);  // 11th arg = expectedFundHz (the hint)
  accum.accumulate(r, absStart, COHERENT, Infinity); // ∞ forever-mode cumulative mean
  accum.overlayOnto(r);
  analyzer.setSpectrumOnly(false);
  analyzer.recomputeStats(r);
  return r;
}

console.log('\n== FLL frequency-tracking test ==');
console.log(`SR=${SR} fftSize=${FFT} window=${WINDOW}`);
console.log(`hint(target)=${HINT_HZ} Hz  clock=${CLOCK_PPM} ppm  ⇒ TRUE captured tone=${TRUE_HZ.toFixed(6)} Hz`);

let r = null, absStart = 0;
const TICKS = 80;                                    // > KAPPA_MEAS_FRAMES so the κ refine + PLL engage
for (let t = 0; t < TICKS; t++) { r = tick(absStart); absStart += hop; }

const refined = r.fundamentalHzRefined;
const freqRes = r.freqResolution;
const errVsTrue = Math.abs(refined - TRUE_HZ);
const errVsHint = Math.abs(refined - HINT_HZ);
const offsetHz = Math.abs(TRUE_HZ - HINT_HZ);

console.log(`\nfreqRes=${freqRes.toFixed(4)} Hz  offset(true−hint)=${(TRUE_HZ - HINT_HZ).toFixed(6)} Hz (${(offsetHz / freqRes).toFixed(4)} bin)`);
console.log(`fundamentalHzRefined=${refined.toFixed(6)} Hz  |err vs TRUE|=${errVsTrue.toFixed(6)}  |err vs HINT|=${errVsHint.toFixed(6)}\n`);

// The core assertion: refined must reflect the TRUE captured tone (so the FLL sees
// the clock offset), NOT be pinned to the hint. Tolerance well under the 0.08 Hz
// offset so a pinned value cannot sneak through.
ok(errVsTrue < 0.01,
  `refined tracks the TRUE captured frequency: ${refined.toFixed(6)} ≈ ${TRUE_HZ.toFixed(6)} Hz (err ${errVsTrue.toFixed(6)} < 0.01 Hz)`);
ok(errVsHint > 0.5 * offsetHz,
  `refined is NOT pinned to the hint: |refined−hint|=${errVsHint.toFixed(6)} Hz reflects the ${CLOCK_PPM} ppm offset (not ≈0)`);
// The quantity the FLL error is built from (detected − target): it must be ≈ the
// real offset, so the loop has an error to null.
const fllErrHz = refined - HINT_HZ;
ok(Math.abs(fllErrHz - (TRUE_HZ - HINT_HZ)) < 0.01,
  `FLL error (detected−target) = ${fllErrHz.toFixed(6)} Hz ≈ the real clock offset ${(TRUE_HZ - HINT_HZ).toFixed(6)} Hz (loop can correct)`);

console.log(`\nfll-freq-track: ${pass} passed, ${fail} failed\n`);
process.exit(fail === 0 ? 0 : 1);
