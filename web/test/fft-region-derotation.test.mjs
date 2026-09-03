/*
 * Phonalyser web - the region-constant coherent de-rotation contract.
 *
 * Vocabulary (defined once): KAPPA is a tone's FRACTIONAL bin position
 * (refined Hz / bin width). A FAMILY MEMBER is one entry of the coherent family
 * - a harmonic h*kappa of the pinned fundamental, or a published intermodulation
 * product a*kappa1 + b*kappa2. A REGION is the span of bins one member's single
 * constant phasor covers; the regions tile the spectrum at the MIDPOINTS between
 * neighbouring member positions. A TICK is one analysis cycle of the FFT
 * consumer (a few per second); the cross-tick ACCUMULATOR folds each tick into a
 * running sum, and its RESTART is the moment that averaging begins anew.
 *
 * Covers both sides of the rebuild:
 *  - the cross-tick accumulator (FftAccumulator, the half spectrum [0, halfSize]):
 *    family construction, midpoint tiling, the dual pair's merged product grid,
 *    and the RECT per-bin ramp exception;
 *  - the analyzer's pass 2 (FftAnalyzer, the FULL transform [0, fftSize)): the
 *    family carried in sharedParams, the fractional hMax divisor, and the
 *    negative-frequency half rotating by the CONJUGATE phasor.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

import { FftAccumulator } from '../js/fft/fft-accumulator.js';
import { FftAnalyzer } from '../js/fft/fft-analyzer.js';
import { FftResult } from '../js/fft/fft-result.js';
import { ieeeRemainder } from '../js/dsp/mathutil.js';

const FFT = 64;
const HALF = FFT / 2;
const N = HALF + 1;
const KAPPA = 5.0;          // the pinned fundamental sits on bin 5 exactly
const DELTA = 3;            // samples between the pinning tick and the next

/** A per-window result shaped as the accumulator consumes it: every bin 1 + 0j,
 *  so whatever comes out of the fold IS the phasor that bin was rotated by. */
function flatResult({ windowType = 'BH7', f2Hz = 0, pa = null, pb = null } = {}) {
  return {
    fftSize: FFT,
    sampleRate: FFT,            // 1 Hz per bin
    freqResolution: 1.0,
    fundamentalHzRefined: KAPPA,
    fundamental2HzRefined: f2Hz,
    frameCount: 1,
    windowType,
    re: Float64Array.from({ length: N }, () => 1.0),
    im: new Float64Array(N),
    imdProductA: pa,
    imdProductB: pb,
  };
}

/** Folds the pinning tick (delta 0, identity rotation) and then one tick at
 *  DELTA, and returns that second tick's contribution per bin. */
function secondTickPhasors(opts) {
  const acc = new FftAccumulator();
  acc.accumulate(flatResult(opts), 0, true, Infinity);
  const base = { re: acc._accumRe.slice(), im: acc._accumIm.slice() };
  acc.accumulate(flatResult(opts), DELTA, true, Infinity);
  const re = new Float64Array(N);
  const im = new Float64Array(N);
  for (let k = 0; k < N; k++) {
    re[k] = acc._accumRe[k] - base.re[k];
    im[k] = acc._accumIm[k] - base.im[k];
  }
  return { acc, re, im };
}

const PHI1 = -2.0 * Math.PI * ieeeRemainder(DELTA * KAPPA, FFT) / FFT;

/** Asserts bin k carries exp(i*mult*PHI1) - the phasor of ladder member `mult`. */
function assertLadderMember(re, im, k, mult, why) {
  const want = mult * PHI1;
  assert.ok(Math.abs(re[k] - Math.cos(want)) < 1e-9 && Math.abs(im[k] - Math.sin(want)) < 1e-9,
    `${why}: bin ${k} carries (${re[k].toFixed(6)}, ${im[k].toFixed(6)}), `
    + `expected member ${mult} (${Math.cos(want).toFixed(6)}, ${Math.sin(want).toFixed(6)})`);
}

// ── The accumulator's family and its midpoint regions ───────────────────────

test('the pinning tick rotates by the identity - it IS the reference', () => {
  const acc = new FftAccumulator();
  acc.accumulate(flatResult(), 0, true, Infinity);
  for (let k = 0; k < N; k++) {
    assert.equal(acc._accumRe[k], 1.0);
    assert.equal(acc._accumIm[k], 0.0);
  }
});

test('regions tile at the MIDPOINTS between neighbouring members', () => {
  // kappa 5 over a half spectrum of 32 bins: members at 0, 5, 10, 15, 20, 25, 30,
  // so the midpoints are 2.5, 7.5, 12.5, ... A bin takes its region's member, and
  // the boundary is the midpoint - not the nearest member, not a fixed lobe width.
  const { re, im } = secondTickPhasors();
  assertLadderMember(re, im, 0, 0, 'DC belongs to the h = 0 member');
  assertLadderMember(re, im, 2, 0, 'bin 2 is below the 2.5 midpoint');
  assertLadderMember(re, im, 3, 1, 'bin 3 is above the 2.5 midpoint');
  assertLadderMember(re, im, 5, 1, 'the fundamental bin is its own member');
  assertLadderMember(re, im, 7, 1, 'bin 7 is below the 7.5 midpoint');
  assertLadderMember(re, im, 8, 2, 'bin 8 is above the 7.5 midpoint');
  assertLadderMember(re, im, 30, 6, 'the top ladder member covers its own bin');
  assertLadderMember(re, im, 32, 6, 'the last region runs to Nyquist');
});

test('h = 0 is a member and carries the identity phasor', () => {
  const acc = new FftAccumulator();
  acc.accumulate(flatResult(), 0, true, Infinity);
  const n = acc._buildFamily(flatResult(), HALF, PHI1, 0.0);
  assert.equal(acc._famPos[0], 0.0, 'the first member sits at position 0');
  assert.equal(acc._famCos[0], 1.0);
  assert.equal(acc._famSin[0], 0.0);
  // hMax = trunc(min(32, 32/5)) = 6, so members are h = 0..6 and nothing else.
  assert.equal(n, 7, 'the ladder is h = 0 .. 6 for kappa 5 over 32 bins');
  for (let i = 1; i < n; i++) {
    assert.ok(acc._famPos[i] > acc._famPos[i - 1], 'positions ascend');
    assert.ok(acc._famPos[i] <= HALF, 'no member lands outside the half spectrum');
  }
});

test('a pinned dual pair merges the product grid into the ladder, position-sorted', () => {
  // Products (0,1) at 12, (1,1) at 17 and (-1,1) at 7 with kappa2 = 12.
  const pa = Int32Array.of(0, 1, -1);
  const pb = Int32Array.of(1, 1, 1);
  const opts = { f2Hz: 12.0, pa, pb };
  const acc = new FftAccumulator();
  acc.accumulate(flatResult(opts), 0, true, Infinity);
  assert.equal(acc._accumKappa2, 12.0, 'the second fundamental is pinned at restart');
  const phi2 = -2.0 * Math.PI * ieeeRemainder(DELTA * 12.0, FFT) / FFT;
  const n = acc._buildFamily(flatResult(opts), HALF, PHI1, phi2);
  const pos = Array.from(acc._famPos.slice(0, n));
  assert.deepEqual(pos, [0, 5, 7, 10, 12, 15, 17, 20, 25, 30],
    'the ladder and the grid merge into one ascending position list');
  // Each grid member rides ONE derived phasor a*Phi1 + b*Phi2 - never the phase of
  // its own bin, and never a ladder harmonic's.
  const check = (position, a, b) => {
    const i = pos.indexOf(position);
    const want = ieeeRemainder(a * PHI1 + b * phi2, 2.0 * Math.PI);
    assert.ok(Math.abs(acc._famCos[i] - Math.cos(want)) < 1e-12
      && Math.abs(acc._famSin[i] - Math.sin(want)) < 1e-12,
    `the (${a},${b}) product at ${position} carries a*Phi1 + b*Phi2`);
  };
  check(12, 0, 1);
  check(17, 1, 1);
  check(7, -1, 1);
});

test('RECT keeps the per-bin time-shift ramp instead of the regions', () => {
  // With the frequency lock on-bin a rectangular window's tone is a SINGLE bin -
  // no lobe to hold together - while its 1/df skirt would cross every midpoint.
  const { re, im } = secondTickPhasors({ windowType: 'RECT' });
  const scale = KAPPA / Math.round(KAPPA);                 // 1.0 here
  const theta = -2.0 * Math.PI * ieeeRemainder(DELTA * scale, FFT) / FFT;
  for (const k of [0, 1, 3, 8, 17, 32]) {
    const want = k * theta;
    assert.ok(Math.abs(re[k] - Math.cos(want)) < 1e-9 && Math.abs(im[k] - Math.sin(want)) < 1e-9,
      `RECT bin ${k} rides the ramp k*theta, not a region phasor`);
  }
  // ...and the ramp is strictly per-bin: no two neighbours share a phasor.
  assert.ok(Math.abs(re[8] - re[9]) > 1e-6, 'neighbouring RECT bins differ');
});

test('with no pinned fundamental the tick is added straight', () => {
  const acc = new FftAccumulator();
  const r0 = flatResult();
  r0.fundamentalHzRefined = 0;      // nothing coherent to align
  acc.accumulate(r0, 0, true, Infinity);
  const r1 = flatResult();
  r1.fundamentalHzRefined = 0;
  acc.accumulate(r1, DELTA, true, Infinity);
  for (let k = 0; k < N; k++) {
    assert.ok(Math.abs(acc._accumRe[k] - 2.0) < 1e-12 && Math.abs(acc._accumIm[k]) < 1e-12,
      `bin ${k} added with the identity rotation`);
  }
});

// ── The analyzer's pass 2 over the FULL transform ───────────────────────────

const SR = 48000;
const AN_FFT = 4096;

/** Sum of tones plus a tiny deterministic dither (no Math.random). */
function signal(len, tones) {
  const x = new Float64Array(len);
  let s = 0x2545f491;
  const rnd = () => { s = (s * 1664525 + 1013904223) >>> 0; return s / 0x100000000 - 0.5; };
  for (let n = 0; n < len; n++) {
    let v = 1e-7 * rnd();
    for (const [f, a] of tones) v += a * Math.sin(2 * Math.PI * f * n / SR);
    x[n] = v;
  }
  return x;
}

test('the family in sharedParams starts at h = 0 and stops inside the band', () => {
  const binW = SR / AN_FFT;
  const toneHz = 1000 + 0.37 * binW;               // deliberately off-bin
  const x = signal(AN_FFT * 3, [[toneHz, 0.5]]);
  const a = new FftAnalyzer();
  const sp = a.prelude(x, SR, AN_FFT, 8, 'BH7', 'PCT_75', true, NaN, new FftResult());
  assert.ok(sp.famCount > 0, 'a pinned fundamental builds a family');
  assert.equal(sp.famA[0], 0, 'the ladder starts at h = 0');
  assert.equal(sp.famB[0], 0);
  assert.equal(sp.famPos[0], 0.0);
  // hMax uses the FRACTIONAL divisor, so the top member is the last harmonic that
  // still lands in band - a rounded divisor would push members past Nyquist.
  for (let i = 0; i < sp.famCount; i++) {
    assert.ok(sp.famPos[i] <= sp.halfSize, `member ${i} at ${sp.famPos[i]} is in band`);
    if (i > 0) assert.ok(sp.famPos[i] > sp.famPos[i - 1], 'positions ascend');
  }
});

test('the negative-frequency half rotates by the CONJUGATE phasor', () => {
  // A real input's per-frame transform is Hermitian. The region-constant rotation
  // preserves that ONLY if the upper half takes the member at |kf| with the
  // conjugated phasor; rotating it like a positive frequency breaks the symmetry.
  const binW = SR / AN_FFT;
  const x = signal(AN_FFT * 3, [[1000 + 0.37 * binW, 0.5], [3000 + 0.11 * binW, 0.02]]);
  const a = new FftAnalyzer();
  const sp = a.prelude(x, SR, AN_FFT, 8, 'BH7', 'PCT_75', true, NaN, new FftResult());
  const part = a.accumulatePartial(x, 0, sp.frameCount, sp);
  // Measured against the SPECTRUM's peak: a near-null noise bin has no meaningful
  // relative scale of its own, and a mis-rotated half would break the symmetry by
  // a fraction of the tone, not of the floor.
  let peak = 0;
  for (let k = 0; k < AN_FFT; k++) peak = Math.max(peak, Math.hypot(part.sumRe[k], part.sumIm[k]));
  let worst = 0;
  for (let k = 1; k < AN_FFT / 2; k++) {
    const dr = Math.abs(part.sumRe[k] - part.sumRe[AN_FFT - k]);
    const di = Math.abs(part.sumIm[k] + part.sumIm[AN_FFT - k]);
    worst = Math.max(worst, Math.hypot(dr, di) / peak);
  }
  assert.ok(worst < 1e-12, `the de-rotated sum stays Hermitian (worst break ${worst} of the peak)`);
});

test('the hint-less dual F2 is the strongest remaining peak, at any level or spacing', () => {
  // 60 dB down and only ~20 bins from F1: the pair is simply THE TWO STRONGEST
  // TONES, so neither a minimum level nor a minimum separation may reject it.
  const binW = SR / AN_FFT;
  const f1 = 1000 + 0.37 * binW;
  const f2 = 1000 + 20.2 * binW;
  const x = signal(AN_FFT * 3, [[f1, 0.5], [f2, 0.5e-3]]);
  const a = new FftAnalyzer();
  a.setMultiTone(true);
  a.setSecondToneHintHz(NaN);                       // no commanded hint
  const r = a.analyze(x, SR, AN_FFT, 8, 'BH7', 'PCT_75', 0, 0, true, NaN, NaN, new FftResult());
  assert.ok(Math.abs(r.fundamental2HzRefined - f2) < binW,
    `F2 found at ${r.fundamental2HzRefined} (expected ~${f2})`);
  assert.ok(r.imdProductBin.length > 0, 'a pinned pair publishes the product grid');
});

test('a single-tone tick publishes NO product grid', () => {
  // A recycled result slot must not leak a previous dual-tone tick's grid.
  const binW = SR / AN_FFT;
  const x = signal(AN_FFT * 3, [[1000 + 0.37 * binW, 0.5]]);
  const a = new FftAnalyzer();
  const r = a.analyze(x, SR, AN_FFT, 8, 'BH7', 'PCT_75', 0, 0, true, NaN, NaN, new FftResult());
  assert.equal(r.imdProductA.length, 0);
  assert.equal(r.imdProductB.length, 0);
  assert.equal(r.imdProductBin.length, 0);
});

test('an off-bin tone holds its level as the frame count grows', () => {
  // The headline property: a tapered window's lobe advances by ONE phase per hop,
  // so a constant phasor per region keeps it intact. A per-bin ramp would carve
  // the lobe into a comb and the level would sink as frames accumulate.
  const binW = SR / AN_FFT;
  const toneHz = 1000 + 0.37 * binW;
  const a = new FftAnalyzer();
  const shortSig = signal(AN_FFT * 2, [[toneHz, 0.5]]);
  const longSig = signal(AN_FFT * 12, [[toneHz, 0.5]]);
  const few = a.analyze(shortSig, SR, AN_FFT, 8, 'BH7', 'PCT_75', 0, 0, true, NaN, NaN,
    new FftResult());
  const many = a.analyze(longSig, SR, AN_FFT, 8, 'BH7', 'PCT_75', 0, 0, true, NaN, NaN,
    new FftResult());
  assert.ok(many.frameCount > few.frameCount * 3, 'the long capture really has more frames');
  assert.ok(Math.abs(many.fundamentalDbFs - few.fundamentalDbFs) < 0.05,
    `level held ${few.fundamentalDbFs.toFixed(4)} -> ${many.fundamentalDbFs.toFixed(4)} dBFS `
    + `over ${few.frameCount} -> ${many.frameCount} frames`);
});
