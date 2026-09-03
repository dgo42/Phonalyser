/*
 * Phonalyser web - a fixed high overlap leaves a COMB in the averaged noise floor,
 * and irregular window spacing destroys it.
 *
 * Why the comb exists. At 93.75 percent overlap every sample appears in 16
 * consecutive frames, so successive windows carry CORRELATED noise. The cross-tick
 * de-rotation turns each window by one constant phasor per family region, not by
 * each bin's own time shift, so a noise bin k in the region of the member at
 * position p keeps a residual per-tick rotation of 2*pi*(k - p)*hop/N. Where that
 * residual is a whole turn the correlated noise adds COHERENTLY and does not
 * average down; elsewhere it rotates away and falls like independent noise. The
 * pattern repeats every N/hop bins - 16 here.
 *
 * MEASUREMENT NOTE, and why the tone is chosen as it is. Folding the floor on the
 * absolute bin index only shows the comb when every family member falls in the same
 * residue class. Members sit at h*kappa, so their residues are (h*kappa) mod 16 -
 * with an arbitrary kappa they spread across all 16 classes and a global fold
 * smears the comb away. That is a property of the METRIC, not of the signal: on a
 * noise-only input the same run reads 26 dB at one transform length and 6 dB at
 * another purely by where the pinned kappa landed. So the fixture uses a real tone
 * whose kappa is an exact multiple of the fold period, which makes every member
 * share a residue class by construction. The measurement then reads the physics
 * rather than an alignment accident - it is stable to about 1.5 dB across a fourfold
 * range of transform lengths.
 *
 * Vocabulary: a TICK is one analysis cycle; the HOP is the fresh samples between
 * successive analysed window starts; COMB SPREAD is the peak-to-trough range of the
 * averaged floor's mean level per residue class k mod (N/hop).
 *
 * This is the observable behind the window-spacing invariant that
 * fft-window-spacing.test.mjs pins: a window dropped because an analysis was still
 * in flight makes the spacing an irregular multiple of the hop, which collapses the
 * comb to a fraction of what the exact hop shows. It does not collapse it as far as
 * the random mode does: a dropped spacing is an integer MULTIPLE of the base hop, so
 * every window still lands on the same lattice and the residual rotations still share
 * the period N/hop. RAND's draws come off a 16-value lattice of DIFFERENT hops, whose
 * comb periods superpose and very nearly cancel.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

import { FftAnalyzer } from '../js/fft/fft-analyzer.js';
import { FftResult } from '../js/fft/fft-result.js';
import { FftAccumulator } from '../js/fft/fft-accumulator.js';
import { minHopSamples, maxHopSamples, RAND_HOP_STEPS } from '../js/enums/fft-overlap.js';

const SR = 48000;
const FFT = 8192;
const WINDOW = 'BH7';
const HOP = Math.round(FFT * 0.0625);          // 93.75 percent
const PERIOD = FFT / HOP;                      // 16
const KAPPA = 10 * PERIOD;                     // 160 bins - a multiple of the period
const TONE_HZ = KAPPA * SR / FFT;
const TICKS = 80;

/** Tone plus a small broadband floor; deterministic, no Math.random. */
function signal(len) {
  const x = new Float64Array(len);
  let s = 0x2545f491;
  for (let i = 0; i < len; i++) {
    s = (s * 1664525 + 1013904223) >>> 0;
    x[i] = 0.5 * Math.sin(2 * Math.PI * TONE_HZ * i / SR) + (s / 0x100000000 - 0.5) * 0.002;
  }
  return x;
}

/** Accumulates TICKS windows spaced by hopOf(t) and returns the comb spread of the
 *  averaged floor, measured on noise bins clear of the tone's harmonic lobes. */
function combSpread(overlapToken, hopOf, bufHop) {
  const need = FFT + (TICKS + 4) * (bufHop + 1);
  const x = signal(need);
  const ana = new FftAnalyzer();
  const acc = new FftAccumulator();
  const slot = new FftResult();
  let abs = 0;
  let r = null;
  for (let t = 0; t < TICKS; t++) {
    if (abs + FFT + bufHop > need) break;
    r = ana.analyze(x.subarray(abs, abs + FFT + bufHop), SR, FFT, 9, WINDOW,
      overlapToken, 0, 0, true, NaN, NaN, slot, hopOf(t), 2);
    acc.accumulate(r, abs, true, Infinity);
    abs += hopOf(t);
  }
  const out = r.deepCopy();
  acc.overlayOnto(out);
  ana.recomputeStats(out);
  const res = SR / FFT;
  const lo = Math.round(1500 / res), hi = Math.round(9000 / res);
  const sum = new Array(PERIOD).fill(0);
  const cnt = new Array(PERIOD).fill(0);
  for (let k = lo; k <= hi; k++) {
    if (Math.abs(k % KAPPA) < 6) continue;      // clear of the harmonic lobes
    sum[k % PERIOD] += out.amplitudeDbFs[k];
    cnt[k % PERIOD]++;
  }
  const means = sum.map((v, i) => (cnt[i] ? v / cnt[i] : NaN)).filter(Number.isFinite);
  return {
    spread: Math.max(...means) - Math.min(...means),
    peak: Math.max(...means),
    trough: Math.min(...means),
    frames: acc.accumFrames,
  };
}

const randMin = minHopSamples('RAND', FFT);
const randMax = maxHopSamples('RAND', FFT);
const quantum = Math.max(1, Math.trunc((randMax - randMin) / RAND_HOP_STEPS));
// Math.imul, not a plain multiply: s * 1103515245 reaches 4.7e18, far past the 2^53
// where a float64 product stops being exact, so the low bits the generator lives on
// are gone before the truncation sees them. That degenerate stream repeats one hop
// for twenty draws at a stretch and measures as a nearly FIXED spacing.
function drawnHops() {
  let s = 987654321 | 0;
  return () => { s = (Math.imul(s, 1103515245) + 12345) | 0;
    return Math.min(randMax, randMin + (1 + ((s >>> 0) % RAND_HOP_STEPS)) * quantum); };
}

const exact = combSpread('PCT_93_75', () => HOP, HOP);
// The defect's own shape: a window comes due while the previous analysis is still
// running, so the spacing becomes an irregular multiple set by main-thread load.
const dropped = combSpread('PCT_93_75', t => (1 + (t * 7919 % 3)) * HOP, 3 * HOP);
const noOverlap = combSpread('PCT_0', () => FFT, FFT);
const random = combSpread('RAND', drawnHops(), randMax);

test('a fixed 93.75 percent overlap leaves a large comb', () => {
  assert.ok(exact.spread > 30.0,
    `comb spread at the exact hop is ${exact.spread.toFixed(2)} dB`);
});

test('no overlap leaves none - frames that share nothing cannot interfere', () => {
  assert.ok(noOverlap.spread < 5.0,
    `comb spread at 0 percent overlap is ${noOverlap.spread.toFixed(2)} dB`);
  assert.ok(exact.spread > noOverlap.spread * 8,
    `the correlated case dwarfs the uncorrelated one `
    + `(${exact.spread.toFixed(2)} vs ${noOverlap.spread.toFixed(2)} dB)`);
});

test('IRREGULAR spacing - a dropped window - collapses the comb', () => {
  // The bench symptom: with windows dropped under load a fixed overlap stops
  // showing its comb, so every setting looks alike. Measured at equal frame counts.
  assert.equal(dropped.frames, exact.frames, 'compared at the same frame count');
  assert.ok(dropped.spread < exact.spread * 0.5,
    `dropping windows collapses the comb from ${exact.spread.toFixed(2)} dB to `
    + `${dropped.spread.toFixed(2)} dB`);
});

test('at EQUAL averages the correlated floor does not fall like an uncorrelated one', () => {
  // Sixteen frames share every sample at 93.75 percent, so an average of N frames
  // there carries about N/16 independent looks. The consequence is not a floor that
  // sits uniformly higher: it SPLITS. The classes where the correlated noise adds
  // coherently hold up against the uncorrelated case, while the rest cancel harder
  // than random phases would - the residual rotation is deterministic and spread
  // evenly over a turn, which cancels better than chance. The integrated readout is
  // therefore nearly level, which is why an aggregate number cannot decide this.
  assert.equal(exact.frames, noOverlap.frames, 'compared at the same frame count');
  assert.ok(exact.peak - noOverlap.peak > 4.0,
    `the coherent classes hold up: ${(exact.peak - noOverlap.peak).toFixed(2)} dB above `
    + `the uncorrelated floor (${exact.peak.toFixed(2)} vs ${noOverlap.peak.toFixed(2)} dBFS)`);
  assert.ok(noOverlap.trough - exact.trough > 15.0,
    `and the rest cancel harder: ${(noOverlap.trough - exact.trough).toFixed(2)} dB below it `
    + `(${exact.trough.toFixed(2)} vs ${noOverlap.trough.toFixed(2)} dBFS)`);
});

test('the random mode very nearly removes the comb', () => {
  // RAND draws every hop from 90.6 to 93.75 percent, so its frames stay just as
  // correlated as the fixed hop's - the same samples recur in the same number of
  // windows. What changes is that each drawn hop carries its OWN comb period, and
  // superposing sixteen of them leaves almost nothing standing. Correlation alone
  // does not make a comb; a REPEATED spacing does.
  assert.ok(random.spread < exact.spread * 0.15,
    `the drawn hops collapse the comb (${random.spread.toFixed(2)} vs `
    + `${exact.spread.toFixed(2)} dB at the fixed hop)`);
  assert.ok(random.spread < dropped.spread * 0.6,
    `below even the dropped-window case, whose spacings are integer multiples of one `
    + `hop and so share its period (${random.spread.toFixed(2)} vs `
    + `${dropped.spread.toFixed(2)} dB)`);
  assert.ok(random.spread > noOverlap.spread * 1.5,
    `without quite reaching the uncorrelated floor - the draw spans 3 percent of `
    + `overlap, not the whole range (${random.spread.toFixed(2)} vs `
    + `${noOverlap.spread.toFixed(2)} dB)`);
});
