/*
 * Phonalyser web - FLL closed-loop LOCK probe (no browser).
 *
 * ROOT-CAUSE guard for the Δosc clock-drift readout. The FLL (js/dsp/fll.js
 * FrequencyFll, driven from js/audio/fft-controller.js applyFrequencyLock) steers the
 * generator so the CAPTURED tone lands on the FFT bin and Δosc nulls. The web runs the
 * generator (outCtx) and the capture (inCtx) on TWO INDEPENDENT AudioContext clocks, so
 * the tone the ADC measures is the commanded output frequency scaled by the two clocks'
 * ratio (1+ε), ε ≈ +80 ppm. Java has ONE exclusive-mode clock, so it never sees this.
 *
 * The Δosc display + the coherent FFT reference the CAPTURE-rate FFT bin grid
 * (fft-view.js expected = snap(genFrequencyHz, r.sampleRate)). The BUG was that the FLL
 * targeted this.snapped - the generator's OUTPUT-rate bin grid - so it locked the tone
 * to the wrong grid; Δosc stayed ≈80 ppm even though the loop reported meas==target.
 *
 * This probe simulates the two-clock offset END-TO-END without a browser and drives the
 * REAL FrequencyFll loop for ~30 ticks under BOTH targets:
 *
 *   OLD: target = snap(rawTone, OUTPUT rate)  ->  Δosc stays ≈80 ppm  (regression)
 *   NEW: target = snap(rawTone, CAPTURE rate) ->  Δosc -> ~0            (the fix)
 *
 * Δosc_equivalent per tick = meas − snap(genFreq_command, CAPTURE rate), where
 * meas = genFreq_command · (1+ε) is what the ADC captures. PASS requires the NEW target
 * to converge |Δosc| < 1 ppm while the OLD target stays ≈80 ppm.
 *
 * Run: node test/e2e/_fll-lock-probe.mjs   (exits non-zero on any FAIL)
 */
import { FrequencyFll } from '../../js/dsp/fll.js';

let pass = 0, fail = 0;
const ok = (cond, msg) => { if (cond) { pass++; console.log('PASS: ' + msg); } else { fail++; console.log('FAIL: ' + msg); } };

// ── Two-clock model ───────────────────────────────────────────────────────────
// Nominal rates are equal (48000), but the LIVE AudioContext clocks differ: the
// capture clock runs ε faster than the output clock, so a tone commanded at g (output
// timebase) is MEASURED by the ADC as g·(1+ε).
const OUT_RATE = 48000;          // generator (outCtx) clock rate
const EPS = 80e-6;               // +80 ppm output↔capture clock ratio (the runtime split):
                                 // the non-exclusive ADC reports a HIGHER rate than the DAC.
const CAP_RATE = OUT_RATE * (1.0 + EPS);   // capture (inCtx) reported rate - 80 ppm ABOVE out
const FFT = 32768;               // analysis length
const RAW_TONE = 1002.40;        // the entered generator frequency (Hz)

// Snap a frequency to a sample rate's FFT bin grid (faithful FftBinSnap.snapIfEnabled /
// GeneratorController.snapToRate / fft-view _snapIfEnabled).
const snapTo = (rate, raw) => {
  const binHz = rate / FFT;
  return Math.round(raw / binHz) * binHz;
};

// The published generator BASE is always the OUTPUT-rate snapped tone (this.snapped =
// what the DAC plays); the FLL correction is added on top before playback.
const BASE = snapTo(OUT_RATE, RAW_TONE);
// Targets on the two grids.
const TARGET_OUTPUT  = snapTo(OUT_RATE, RAW_TONE);   // OLD (bug): output-rate bin
const TARGET_CAPTURE = snapTo(CAP_RATE, RAW_TONE);   // NEW (fix): capture-rate bin

// The ADC measurement of a commanded output frequency g: the tone scaled by the clock ratio.
const capture = (gCommand) => gCommand * (1.0 + EPS);

// Δosc-equivalent the DISPLAY computes (fft-view.js): the captured tone minus the
// CAPTURE-rate bin grid the FFT actually uses for the entered tone. `expected` is the
// raw entered tone snapped to the capture grid (constant, == TARGET_CAPTURE) - the loop
// nulls Δosc only by driving the CAPTURED tone (meas) onto that capture bin.
const EXPECTED = snapTo(CAP_RATE, RAW_TONE);
const deltaOsc = (gCommand) => capture(gCommand) - EXPECTED;

// Drive the real FrequencyFll for `ticks` with the given target grid. Returns the final
// Δosc-equivalent in ppm (relative to the capture-rate target).
function run(target, ticks) {
  const fll = new FrequencyFll();
  // Monotonically advancing capture positions so the transport gate opens (each tick a
  // full window advances; positions are in capture samples). hop == FFT here.
  const hop = FFT;
  let absStart = 0;
  let writePos = FFT;               // live write head leads the window by ~one window
  let gCommand = BASE;              // last PUBLISHED generator command (output timebase)
  let lastDeltaPpm = 0;
  for (let t = 0; t < ticks; t++) {
    const meas = capture(gCommand);                 // ADC measures the in-flight tone
    fll.update(target, meas, absStart, writePos, CAP_RATE, FFT);
    gCommand = BASE + fll.correction;               // publish base + correction
    lastDeltaPpm = 1e6 * deltaOsc(gCommand) / target;
    absStart += hop;                                // window start advances one hop
    writePos += hop;                                // write head advances with it
  }
  return { deltaPpm: lastDeltaPpm, correction: fll.correction, gCommand };
}

console.log('\n== FLL closed-loop LOCK probe (two-clock offset) ==');
console.log(`outRate=${OUT_RATE} capRate=${CAP_RATE.toFixed(3)} (non-exclusive ADC reports ${(EPS * 1e6).toFixed(1)} ppm higher)`);
console.log(`rawTone=${RAW_TONE} Hz  fftSize=${FFT}`);
console.log(`BASE(snap@out)=${BASE.toFixed(6)}  TARGET_OUTPUT=${TARGET_OUTPUT.toFixed(6)}  TARGET_CAPTURE=${TARGET_CAPTURE.toFixed(6)}`);
console.log(`display expected (snap raw @ capture rate) = ${EXPECTED.toFixed(6)} Hz`);

const TICKS = 30;

// BEFORE = the SHIPPING behaviour: the FLL targets this.snapped (the OUTPUT-rate bin).
// It converges so meas == TARGET_OUTPUT, but the captured tone then sits off the
// CAPTURE-rate FFT bin (what the display measures against) by the ≈80 ppm clock split.
const oldRun = run(TARGET_OUTPUT, TICKS);
console.log(`\nΔosc BEFORE (OLD target = this.snapped / output grid): ${oldRun.deltaPpm.toFixed(2)} ppm  corr=${oldRun.correction >= 0 ? '+' : ''}${oldRun.correction.toFixed(6)} Hz`);

// AFTER = the FIX: the FLL targets the CAPTURE-rate bin (fllTarget), so it steers the
// CAPTURED tone onto the exact FFT bin the display references -> Δosc nulls.
const newRun = run(TARGET_CAPTURE, TICKS);
console.log(`Δosc AFTER  (NEW target = fllTarget / capture grid): ${newRun.deltaPpm.toFixed(2)} ppm  corr=${newRun.correction >= 0 ? '+' : ''}${newRun.correction.toFixed(6)} Hz\n`);

ok(Math.abs(oldRun.deltaPpm) > 50,
  `BEFORE - OLD target (output grid) does NOT null Δosc: ${oldRun.deltaPpm.toFixed(2)} ppm stays ≈80 ppm (the bug)`);
ok(Math.abs(newRun.deltaPpm) < 1.0,
  `AFTER - NEW target (capture grid) NULLS Δosc: |${newRun.deltaPpm.toFixed(4)}| ppm < 1 ppm (captured tone on the FFT bin)`);

console.log(`\nfll-lock-probe: ${pass} passed, ${fail} failed\n`);
process.exit(fail === 0 ? 0 : 1);
