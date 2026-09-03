/*
 * Phonalyser web - OFF-NOMINAL tone contract for the coherent cross-tick average and
 * the generator frequency hint. Run with `node --test test/fft-coherent-offtone.test.mjs`.
 *
 * Field bug (2026-07-26, 384 kHz / 2 M / BH7 / 93.75 % / coherent / ∞): with the captured
 * tone sitting ~13 bins ABOVE the commanded nominal, the desktop read the fundamental at
 * 0.00 dBV while the web read −18.45 dBV and a THD inflated by exactly that ratio - yet the
 * HARMONIC table matched the desktop to tenths of a dB. Two things are pinned here:
 *
 *  1) The cross-tick accumulator is NOT the culprit. Its de-rotation κ₀ is seeded from each
 *     tick's MEASURED sub-bin fundamental (fundamentalHzRefined / freqResolution), never from
 *     the commanded nominal, so an off-nominal tone accumulates with ZERO progressive loss -
 *     the fundamental must not sink tick over tick.
 *  2) The generator FREQUENCY hint (expectedFundHz) must be gated on the generator ACTUALLY
 *     RUNNING, exactly as Java (FftAnalyzerWorker:1697-1714 `genActive && prefs
 *     .isFftFundFromGenerator()`). The hint pins the analyzer's fundamental search to ±10 bins
 *     around the commanded frequency (fft-analyzer.js:241-246 / :572-583); with an EXTERNAL
 *     tone outside that window the level + THD denominator are read off the main lobe's skirt
 *     while the harmonic grid - which rides the measured kFractional - stays correct. That is
 *     the exact field signature, so an idle generator must never install the hint.
 */
import test from 'node:test';
import assert from 'node:assert';
import { FftAnalyzer } from '../js/fft/fft-analyzer.js';
import { FftResult } from '../js/fft/fft-result.js';
import { FftAccumulator } from '../js/fft/fft-accumulator.js';
import { FftController } from '../js/fft/fft-controller.js';
import { testPrefs } from './_test-prefs.mjs';
import { GenSignalForm } from '../js/enums/gen-signal-form.js';
import { MessageBus } from '../js/bus/message-bus.js';
import { Events } from '../js/bus/events.js';

// ── geometry: the field's bin layout, scaled down so the test runs in ms ─────
// The de-rotation phase ramp from a κ error is 2π·(tick·hop)·Δκ/N = 2π·tick·Δκ/16 at 93.75 %
// overlap - independent of N - so a 16 k FFT with the tone at ~5474 bins is tick-for-tick the
// field's 2 M FFT with the tone at ~5474 bins.
const FS = 48000;
const N = 32768;
const FREQ_RES = FS / N;                       // 1.46484375 Hz/bin
const HOP = N / 16;                            // 93.75 % overlap
const BUF_LEN = N + HOP;                       // perTickFrames = 2, as the controller sizes it
const NOMINAL_HZ = 5461 * FREQ_RES;            // commanded (bin 5461)
const OFF_BINS = 13.0083;                      // the field's measured offset, in bins
const TONE_HZ = NOMINAL_HZ + OFF_BINS * FREQ_RES;
const KAPPA_TRUE = TONE_HZ / FREQ_RES;
const PEAK_BIN = Math.round(KAPPA_TRUE);
const TICKS = 10;
const WINDOW = 'BH7';
const OVERLAP = 'PCT_93_75';
const HARMONICS = 9;

/** Second-harmonic level of the test tone - far enough down that only a correct
 *  fundamental denominator yields the true THD. */
const H2_LIN = Math.pow(10, -130 / 20);

/** One analysis window of the continuous off-nominal tone, starting at absolute sample n. */
function windowAt(absStart) {
  const x = new Float64Array(BUF_LEN);
  const cyc = TONE_HZ / FS;
  const cyc2 = 2 * TONE_HZ / FS;
  for (let j = 0; j < BUF_LEN; j++) {
    const n = absStart + j;
    x[j] = Math.sin(2 * Math.PI * ((n * cyc) % 1) + 0.3)
      + H2_LIN * Math.sin(2 * Math.PI * ((n * cyc2) % 1) + 1.1);
  }
  return x;
}

// ── 1) cross-tick coherent average of an off-nominal tone ───────────────────
// Drives the real chain the controller runs per tick: analyze -> accumulate -> overlayOnto.
const analyzer = new FftAnalyzer();
const accum = new FftAccumulator();
const slot = new FftResult();
const curve = [];
let kappaSeeded = NaN;
for (let tick = 0; tick < TICKS; tick++) {
  const absStart = tick * HOP;
  const r = analyzer.analyze(windowAt(absStart), FS, N, HARMONICS, WINDOW, OVERLAP,
    0, 0, true, NaN, NaN, slot);
  accum.accumulate(r, absStart, true, Infinity);
  if (tick === 0) kappaSeeded = accum.accumKFractional;
  const o = {
    fftSize: r.fftSize, freqResolution: r.freqResolution,
    fundamentalHzRefined: r.fundamentalHzRefined,
    re: r.re.slice(), im: r.im.slice(),
    amplitudeDbFs: r.amplitudeDbFs.slice(), phaseDeg: r.phaseDeg.slice(),
  };
  accum.overlayOnto(o);
  curve.push({ tick, accDb: o.amplitudeDbFs[PEAK_BIN], tickKappa: r.fundamentalHzRefined / FREQ_RES });
}

test('accumulator κ₀ is seeded from the MEASURED sub-bin fundamental, not the nominal', () => {
  assert.ok(Math.abs(kappaSeeded - KAPPA_TRUE) < 0.01,
    `κ₀ ${kappaSeeded} should track the measured ${KAPPA_TRUE}, not the nominal ${NOMINAL_HZ / FREQ_RES}`);
  for (const c of curve) {
    assert.ok(Math.abs(c.tickKappa - KAPPA_TRUE) < 0.01,
      `tick ${c.tick}: per-tick κ ${c.tickKappa} should track the measured ${KAPPA_TRUE}`);
  }
});

test('coherent cross-tick average holds an off-nominal fundamental flat (no progressive loss)', () => {
  const first = curve[0].accDb;
  assert.ok(Number.isFinite(first), 'first tick produced a finite fundamental level');
  for (const c of curve) {
    assert.ok(Math.abs(c.accDb - first) <= 0.05,
      `tick ${c.tick}: fundamental ${c.accDb.toFixed(3)} dB drifted from ${first.toFixed(3)} dB`);
  }
  // A clean single tone pins NO second fundamental, so the coherent family is the
  // pure harmonic ladder h*kappa and no product grid joins it.
  assert.ok(Number.isNaN(accum._accumKappa2), 'no dual pair pinned for a single tone');
});

// ── 2) the generator hint pins the LEVEL readout - so it must be gated ──────
test('a nominal hint outside the tone\'s ±10-bin window reads the level off the lobe skirt', () => {
  const samples = windowAt(0);
  const auto = new FftAnalyzer().analyze(samples, FS, N, HARMONICS, WINDOW, OVERLAP,
    0, 0, true, NaN, NaN, new FftResult());
  const hinted = new FftAnalyzer().analyze(samples, FS, N, HARMONICS, WINDOW, OVERLAP,
    0, 0, true, NaN, NOMINAL_HZ, new FftResult());
  // Both measure the same sub-bin FREQUENCY (the harmonic grid rides this) ...
  assert.ok(Math.abs(auto.fundamentalHzRefined - TONE_HZ) < 1e-3);
  assert.ok(Math.abs(hinted.fundamentalHzRefined - TONE_HZ) < 1e-3);
  // ... but the hinted LEVEL is pinned to the search window's edge bin, far below the peak.
  assert.strictEqual(auto.fundamentalBin, PEAK_BIN);
  assert.ok(hinted.fundamentalBin < PEAK_BIN, 'hinted fundamental bin is stuck inside ±10 bins');
  assert.ok(hinted.fundamentalDbFs < auto.fundamentalDbFs - 10,
    `hinted level ${hinted.fundamentalDbFs.toFixed(2)} dB should sit far below the true peak `
    + `${auto.fundamentalDbFs.toFixed(2)} dB`);
  // ...and THD is inflated by exactly that ratio (refLin is the same wrong bin).
  assert.ok(hinted.thdPct > auto.thdPct * 3, 'THD inflates with the depressed fundamental');
});

/** A controller wired to stubs, with just the state _dispatch reads. */
function controllerWith({ running, fundFromGenerator }) {
  const prefs = testPrefs({
    inputSampleRate: FS, outputSampleRate: FS, fftLength: N, genFrequencyHz: NOMINAL_HZ,
    genDualToneFreq2Hz: 0, genSignalForm: GenSignalForm.SINE, fftCalcMaxHarmonic: HARMONICS + 1,
    fftWindow: WINDOW, fftOverlap: OVERLAP, fftCoherentAveraging: true, fftAverages: Infinity,
    fftMainsSuppression: 'NONE', fftFundFromGenerator: fundFromGenerator,
    fftManualFundEnabled: false, fftThreads: 1, fftChannel: 'R',
  });
  const gen = {
    snapped: NOMINAL_HZ, binW: FREQ_RES, genNode: null, running,
    computeAnalysisFreqs() {}, snapToRate: () => NOMINAL_HZ, snapToRate2: () => 0,
  };
  const c = new FftController({}, gen, prefs, {});
  c.N = N; c.minHop = HOP; c.maxHop = HOP; c.pendingHop = HOP;
  c.perTickFrames = 2; c.bufLen = BUF_LEN;
  c.buf = new Float64Array(BUF_LEN); c.bufW = 0;
  c.foreverMode = true; c.ringN = 1; c.poolSize = 1;
  c._busy = false; c._dispatchId = 0;
  const posted = [];
  c.worker = { postMessage: (msg) => posted.push(msg) };
  // The hint is ASKED for (GENERATOR_EMITTED_HZ), not recomputed from the config - the
  // generator answers with what it emits (tone 2 = 0: a single tone has no second tone).
  const bus = MessageBus.instance();
  bus.registerResponder(Events.GENERATOR_EMITTED_HZ, () => [NOMINAL_HZ, 0]);
  try {
    c._dispatch(0, 0);
  } finally {
    bus.unregisterResponder(Events.GENERATOR_EMITTED_HZ);
  }
  return posted[0];
}

test('the generator frequency hint is gated on the generator RUNNING (Java parity)', () => {
  const idle = controllerWith({ running: false, fundFromGenerator: true });
  assert.ok(Number.isNaN(idle.expectedFundHz),
    'generator idle ⇒ no hint: an external tone must be found by the global-max search');
  const live = controllerWith({ running: true, fundFromGenerator: true });
  assert.strictEqual(live.expectedFundHz, NOMINAL_HZ,
    'generator running + pref on ⇒ hint the commanded frequency, exactly as Java');
  const prefOff = controllerWith({ running: true, fundFromGenerator: false });
  assert.ok(Number.isNaN(prefOff.expectedFundHz), 'pref off ⇒ no hint');
});
