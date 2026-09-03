/*
 * Phonalyser web - the analyser's frame-geometry contract (port of the desktop
 * FftFrameOffsetsTest, plus the web-side hop draw and the .fft provenance).
 *
 * The full analyze path takes the stated frame SPACING and frame COUNT; the
 * fixed overlaps derive both through FftOverlap.hopSamples, and the random
 * overlap has no derivable spacing at all - its per-tick drawn hop enters as the
 * stated step. "Hop" here is the number of fresh samples between two successive
 * analysis frame starts; "tick" is one analysis cycle of the FFT consumer (a few
 * per second), which slides its window by exactly one hop.
 *
 * Covers: the spacing derivations and the random-mode refusal, the geometry
 * validation, bit-identity between the derived and the hand-stated geometry, the
 * coherent level's independence from the stated spacing, the random hop
 * lattice, and the .fft geometry headers surviving a save/load round trip
 * (including a file written before they existed).
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

import { FftAnalyzer } from '../js/fft/fft-analyzer.js';
import { FftResult } from '../js/fft/fft-result.js';
import { FftController } from '../js/fft/fft-controller.js';
import { testPrefs } from './_test-prefs.mjs';
import {
  hopSamples, minHopSamples, maxHopSamples, isRandom, RAND_HOP_STEPS,
  RAND_MIN_OVERLAP_PCT, RAND_MAX_OVERLAP_PCT, OVERLAP_LABEL, FFT_OVERLAP_VALUES,
} from '../js/enums/fft-overlap.js';
import { PREFERENCES_SCHEMA } from '../js/store/schema/preferences.schema.js';
import { saveSpectrum, loadSpectrum } from '../js/fft/fft-spectrum.js';
import { parabolicBinInterp } from '../js/dsp/mathutil.js';

const SAMPLE_RATE = 48000;
const FFT_SIZE = 8192;

/** A pure sine of `samples` length at `freqHz`. */
function sine(freqHz, samples) {
  const x = new Float64Array(samples);
  for (let n = 0; n < samples; n++) {
    x[n] = 0.5 * Math.sin(2 * Math.PI * freqHz * n / SAMPLE_RATE);
  }
  return x;
}

// ── FftOverlap spacing derivations ──────────────────────────────────────────

test('hopSamples: the fixed modes derive the rounded spacing', () => {
  assert.equal(hopSamples('PCT_0', FFT_SIZE), FFT_SIZE);
  assert.equal(hopSamples('PCT_50', FFT_SIZE), FFT_SIZE / 2);
  assert.equal(hopSamples('PCT_75', FFT_SIZE), FFT_SIZE / 4);
  assert.equal(hopSamples('PCT_87_5', FFT_SIZE), FFT_SIZE / 8);
  assert.equal(hopSamples('PCT_93_75', FFT_SIZE), FFT_SIZE / 16);
  // Never collapses to zero, however small the frame.
  assert.equal(hopSamples('PCT_93_75', 2), 1);
});

test('hopSamples: the random mode refuses - it has no fixed spacing', () => {
  assert.ok(isRandom('RAND'));
  assert.throws(() => hopSamples('RAND', FFT_SIZE), /no fixed frame spacing/);
});

test('the random range: the MINIMUM percent yields the LARGEST hop', () => {
  // A wider overlap leaves less fresh signal per hop, so RAND_MAX_OVERLAP_PCT is
  // the densest spacing and RAND_MIN_OVERLAP_PCT the sparsest.
  assert.ok(RAND_MIN_OVERLAP_PCT < RAND_MAX_OVERLAP_PCT);
  const min = minHopSamples('RAND', FFT_SIZE);
  const max = maxHopSamples('RAND', FFT_SIZE);
  assert.equal(min, Math.round(FFT_SIZE * (1 - RAND_MAX_OVERLAP_PCT / 100)));
  assert.equal(max, Math.round(FFT_SIZE * (1 - RAND_MIN_OVERLAP_PCT / 100)));
  assert.ok(min < max, `random min hop ${min} must be below the max hop ${max}`);
  // A fixed mode has ONE hop: both bounds coincide with it.
  for (const ov of ['PCT_0', 'PCT_50', 'PCT_75', 'PCT_87_5', 'PCT_93_75']) {
    assert.equal(minHopSamples(ov, FFT_SIZE), hopSamples(ov, FFT_SIZE));
    assert.equal(maxHopSamples(ov, FFT_SIZE), hopSamples(ov, FFT_SIZE));
  }
  assert.equal(OVERLAP_LABEL.RAND, 'Random');
});

// ── Geometry validation ─────────────────────────────────────────────────────

test('analyze rejects a bad stated geometry', () => {
  const signal = sine(1000, FFT_SIZE * 2);
  const analyzer = new FftAnalyzer();
  const run = (step, frameCount) => analyzer.analyze(
    signal, SAMPLE_RATE, FFT_SIZE, 8, 'HANN', 'PCT_0', 0, 0, true, NaN, NaN,
    new FftResult(), step, frameCount);
  // The spacing must be positive.
  assert.throws(() => run(0, 2), /Frame spacing must be positive/);
  // At least one frame.
  assert.throws(() => run(4096, 0), /Signal too short/);
  // The last frame must fit the buffer.
  assert.throws(() => run(FFT_SIZE + 1, 2), /Stated geometry exceeds the buffer/);
});

test('the convenience path refuses the random overlap - the caller must state the geometry', () => {
  const signal = sine(1000, FFT_SIZE * 2);
  assert.throws(() => new FftAnalyzer().analyze(
    signal, SAMPLE_RATE, FFT_SIZE, 8, 'HANN', 'RAND', 0, 0, true, NaN, NaN),
  /no fixed frame spacing/);
});

// ── Stated geometry == the convenience derivation, to the bit ───────────────

test('a hand-stated geometry matches the derived one exactly', () => {
  const binWidth = SAMPLE_RATE / FFT_SIZE;
  const freqHz = 1000 + 0.3 * binWidth;
  const signal = sine(freqHz, FFT_SIZE * 3);

  const derived = new FftAnalyzer().analyze(
    signal, SAMPLE_RATE, FFT_SIZE, 8, 'HANN', 'PCT_75', 0, 0, true, NaN, NaN,
    new FftResult());

  const step = hopSamples('PCT_75', FFT_SIZE);
  const frameCount = Math.trunc((signal.length - FFT_SIZE) / step) + 1;
  const stated = new FftAnalyzer().analyze(
    signal, SAMPLE_RATE, FFT_SIZE, 8, 'HANN', 'PCT_75', 0, 0, true, NaN, NaN,
    new FftResult(), step, frameCount);

  assert.equal(derived.frameCount, stated.frameCount);
  assert.equal(derived.stepSamples, stated.stepSamples);
  assert.equal(stated.stepSamples, step);
  assert.equal(derived.fundamentalHzRefined, stated.fundamentalHzRefined);
  assert.equal(derived.thdPct, stated.thdPct);
  assert.deepEqual(derived.amplitudeDbFs, stated.amplitudeDbFs);
});

// ── Variable spacing: the de-rotation rides the true positions ──────────────

test('the stated spacing does not move the coherent fundamental level', () => {
  const binWidth = SAMPLE_RATE / FFT_SIZE;
  const freqHz = 1000 + 0.3 * binWidth;
  const signal = sine(freqHz, FFT_SIZE * 3);

  // Two different spacings in the random-draw regime cut two different frame sets
  // from the same buffer; the per-frame de-rotation rides each stated spacing, so
  // the coherent fundamental must read the same level either way. Arithmetic that
  // de-rotated at a spacing other than the one the frames were cut at would
  // mis-rotate frames and partially cancel the tone - 1024 vs 1000 samples differ
  // by more than half a cycle of this fundamental per frame.
  const a = new FftAnalyzer().analyze(
    signal, SAMPLE_RATE, FFT_SIZE, 8, 'HANN', 'RAND', 0, 0, true, NaN, NaN,
    new FftResult(), 1000, 6);
  const b = new FftAnalyzer().analyze(
    signal, SAMPLE_RATE, FFT_SIZE, 8, 'HANN', 'RAND', 0, 0, true, NaN, NaN,
    new FftResult(), 1024, 6);

  assert.ok(Math.abs(a.fundamentalHzRefined - freqHz) < binWidth * 0.1,
    `refined Hz ${a.fundamentalHzRefined} must track the ${freqHz} Hz tone at a stated spacing`);
  assert.ok(Math.abs(a.fundamentalDbFs - b.fundamentalDbFs) < 0.05,
    `coherent level must not depend on the stated spacing (${a.fundamentalDbFs} vs ${b.fundamentalDbFs})`);
  assert.equal(a.stepSamples, 1000, 'the stated spacing is stamped');
  assert.equal(b.stepSamples, 1024, 'the stated spacing is stamped');
});

// ── The consumer's per-tick hop draw ────────────────────────────────────────

/** A bare FftController (no capture, no worker) with only the hop-draw state. */
function controllerWithHopBounds(overlap) {
  const prefs = testPrefs({ inputSampleRate: SAMPLE_RATE, fftLength: FFT_SIZE, fftOverlap: overlap });
  const gen = { snapped: 1000, binW: SAMPLE_RATE / FFT_SIZE, running: false, computeAnalysisFreqs() {} };
  const c = new FftController({}, gen, prefs, {});
  c.N = FFT_SIZE;
  c.minHop = minHopSamples(overlap, FFT_SIZE);
  c.maxHop = maxHopSamples(overlap, FFT_SIZE);
  return c;
}

test('the random hop walks the 16-value lattice in a fixed cycle inside the range', () => {
  const c = controllerWithHopBounds('RAND');
  const min = c.minHop, max = c.maxHop;
  const quantum = Math.max(1, Math.trunc((max - min) / RAND_HOP_STEPS));
  const firstCycle = [];
  for (let i = 0; i < 3 * RAND_HOP_STEPS; i++) {
    const hop = c._nextHop();
    assert.ok(hop > min, `a hop ${hop} must exceed the bare minimum ${min}`);
    assert.ok(hop <= max, `a hop ${hop} must not exceed the maximum ${max}`);
    assert.equal((hop - min) % quantum, 0, `a hop ${hop} must land on a lattice boundary`);
    // Step k of the cycle is the (k+1)-th lattice point; the cycle wraps after 16.
    assert.equal(hop, Math.min(max, min + (1 + (i % RAND_HOP_STEPS)) * quantum),
      `hop ${i} is lattice step ${i % RAND_HOP_STEPS}`);
    if (i < RAND_HOP_STEPS) firstCycle.push(hop);
  }
  assert.equal(new Set(firstCycle).size, RAND_HOP_STEPS, `one cycle visits all ${RAND_HOP_STEPS} lattice values`);
  assert.equal(firstCycle[RAND_HOP_STEPS - 1], max, 'the last step of the cycle IS the maximum hop');
});

test('a fixed overlap yields its one hop every time', () => {
  const c = controllerWithHopBounds('PCT_75');
  for (let i = 0; i < 100; i++) assert.equal(c._nextHop(), FFT_SIZE / 4);
});

// ── .fft geometry provenance ────────────────────────────────────────────────

/** The minimal spectrum shape saveSpectrum reads (16 bins of a flat spectrum). */
function spectrumForSave(overlap, stepSamples) {
  const bins = 17;
  return {
    fftSize: 32, sampleRate: 48000, frameCount: 4, freqResolution: 1500,
    windowType: 'BH4', overlap, stepSamples, coherentAveraging: true,
    snrFreqMin: 0, snrFreqMax: 0, fundamentalTrueDbFs: NaN, harmonicCount: 9,
    binBwSqrt: Math.sqrt(1500),
    amplitudeDbFs: Float64Array.from({ length: bins }, (_, k) => (k === 4 ? -6 : -120)),
    phaseDeg: new Float64Array(bins),
  };
}

test('the .fft file saves and restores the overlap and the frame spacing', () => {
  const text = saveSpectrum(spectrumForSave('RAND', 713), { dbvOffsetDb: 0 });
  assert.ok(text.includes('# overlap=RAND'), 'the overlap is written as the enum NAME');
  assert.ok(text.includes('# step_samples=713'), 'the frame spacing is written');
  const loaded = loadSpectrum(text, 0, 9, parabolicBinInterp);
  assert.equal(loaded.result.overlap, 'RAND');
  assert.equal(loaded.result.stepSamples, 713);
});

test('a spectrum with no spacing writes no step_samples line', () => {
  const text = saveSpectrum(spectrumForSave('PCT_75', 0), { dbvOffsetDb: 0 });
  assert.ok(text.includes('# overlap=PCT_75'));
  assert.ok(!text.includes('# step_samples='), 'an unknown spacing is simply not stamped');
  assert.equal(loadSpectrum(text, 0, 9, parabolicBinInterp).result.stepSamples, 0);
});

test('a file written before the geometry headers still loads', () => {
  const text = saveSpectrum(spectrumForSave('PCT_75', 512), { dbvOffsetDb: 0 })
    .split('\n').filter(l => !l.startsWith('# overlap=') && !l.startsWith('# step_samples='))
    .join('\n');
  const loaded = loadSpectrum(text, 0, 9, parabolicBinInterp);
  assert.equal(loaded.result.overlap, null, 'no header -> no overlap, and no throw');
  assert.equal(loaded.result.stepSamples, 0);
});

test('an unknown overlap token is ignored rather than restored', () => {
  const text = saveSpectrum(spectrumForSave('PCT_75', 512), { dbvOffsetDb: 0 })
    .replace('# overlap=PCT_75', '# overlap=n/a');
  assert.equal(loadSpectrum(text, 0, 9, parabolicBinInterp).result.overlap, null);
});

// ── One definition of the token set ─────────────────────────────────────────

test('every consumer of the overlap token set flows from the enum module', () => {
  // The JSON-config schema declares the same constants twice; both must come from
  // the module, or a new mode would validate in one place and not the other.
  assert.deepEqual(PREFERENCES_SCHEMA.properties.fftOverlap.enum, FFT_OVERLAP_VALUES);
  assert.deepEqual(
    PREFERENCES_SCHEMA.properties.fftPresets.additionalProperties.properties.overlap.enum,
    FFT_OVERLAP_VALUES);
});

test('the settings combo in index.html matches the enum module', () => {
  // Static HTML cannot import, so this is the ONE place the token set is repeated.
  // Pin it here instead: the combo's values and its visible labels are the enum's,
  // in the enum's order (Java builds the combo from FftOverlap.values() and .label).
  const html = readFileSync(new URL('../index.html', import.meta.url), 'utf8');
  const select = html.match(/<select id="overlap"[\s\S]*?<\/select>/);
  assert.ok(select, 'the overlap select exists');
  const opts = [...select[0].matchAll(/<option value="([^"]+)"[^>]*>([^<]*)<\/option>/g)];
  assert.deepEqual(opts.map(o => o[1]), FFT_OVERLAP_VALUES, 'same tokens, same order');
  assert.deepEqual(opts.map(o => o[2]), FFT_OVERLAP_VALUES.map(v => OVERLAP_LABEL[v]),
    'each option shows the enum label');
});
