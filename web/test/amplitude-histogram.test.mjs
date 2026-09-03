/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Port of AmplitudeHistogramTest - the contract of the scope amplitude histogram's accumulator.
// Each case pins a rule that was a shipped-and-rejected defect on the Java bench: resolution that
// followed full scale instead of the signal, a re-range tolerance narrower than noise wanders,
// merging counts across a level change, and DC removal that smeared one distribution into two.

import test from 'node:test';
import assert from 'node:assert/strict';

import { AmplitudeHistogram, MICRO_PER_BAR, MIN_BIN_WIDTH } from '../js/scope/amplitude-histogram.js';

const BARS = 50;
const MICRO = BARS * MICRO_PER_BAR;

/** A histogram ranged on `amp` and filled with `n` samples of a sine of that amplitude. */
function sine(amp, n) {
  const h = new AmplitudeHistogram(BARS);
  h.fit(-amp, amp);
  for (let i = 0; i < n; i++) h.add(amp * Math.sin((2 * Math.PI * i) / 997));
  return h;
}

const close = (actual, expected, tol, msg) =>
  assert.ok(Math.abs(actual - expected) <= tol,
    `${msg || ''} expected ${expected} ± ${tol}, got ${actual}`);

// --- geometry --------------------------------------------------------------

test('nothing is counted before a range exists', () => {
  const h = new AmplitudeHistogram(BARS);
  assert.equal(h.ranged, false);
  h.add(0.5);
  assert.equal(h.getTotal(), 0, 'add() before fit() must not guess a range');
  assert.equal(h.firstOccupied(), -1);
});

test('the range is symmetric about zero with an eighth of headroom each end', () => {
  const h = new AmplitudeHistogram(BARS);
  assert.equal(h.fit(-0.4, 0.4), false, 'the first fit establishes a range, so nothing survived');
  close(h.rangeMin, -h.rangeMax, 1e-12, '0 V is the axis centre:');
  assert.ok(h.rangeMax > 0.4, 'headroom beyond the observed peak');
  close(h.rangeMax - 0.4, (MICRO / 8) * h.binWidth, 1e-12, 'an eighth of the range:');
  close(h.rangeMin + MICRO * h.binWidth, h.rangeMax, 1e-12, 'the bins span the range:');
  close(h.binLowerEdge(MICRO / 2), 0.0, 1e-12, 'the middle bin starts at 0 V:');
});

test('an asymmetric signal still keeps zero in the middle', () => {
  const h = new AmplitudeHistogram(BARS);
  h.fit(-0.2, 0.8);
  close(h.rangeMin, -h.rangeMax, 1e-12);
  assert.ok(h.rangeMax > 0.8, 'sized by the further extreme, not the span');
  close(h.binLowerEdge(MICRO / 2), 0.0, 1e-12);
});

test('resolution follows the signal, not full scale', () => {
  // The defect this design exists to prevent: a fixed full-scale range gave a millivolt signal
  // ~2 bins, and the plot collapsed to two bars.
  const big = new AmplitudeHistogram(BARS);
  const small = new AmplitudeHistogram(BARS);
  big.fit(-1.0, 1.0);
  small.fit(-1e-3, 1e-3);
  close(big.binWidth / small.binWidth, 1000.0, 1e-6, 'bin width scales with the signal:');
});

test('bin width never goes below one converter code', () => {
  const h = new AmplitudeHistogram(BARS);
  h.fit(0.0, 0.0);                       // a dead-flat digital-loopback block
  assert.ok(h.binWidth > 0.0, 'a flat block must still produce a usable range');
  assert.equal(h.binWidth, MIN_BIN_WIDTH, 'floored at one 32-bit code');
  h.add(0.0);
  assert.equal(h.getTotal(), 1);
});

// --- ranging over time -----------------------------------------------------

test('a signal growing within the headroom keeps its counts', () => {
  const h = new AmplitudeHistogram(BARS);
  h.fit(-0.5, 0.5);
  for (let i = 0; i < 1000; i++) h.add(0.5 * Math.sin(i));
  const grow = h.rangeMax * 0.99;
  assert.equal(h.fit(-grow, grow), true, 'inside the headroom the distribution must survive');
  assert.equal(h.getTotal(), 1000);
});

test('a signal leaving the range restarts the distribution', () => {
  const h = new AmplitudeHistogram(BARS);
  h.fit(-0.5, 0.5);
  for (let i = 0; i < 1000; i++) h.add(0.5 * Math.sin(i));
  assert.equal(h.fit(-0.9, 0.9), false, 'outside the headroom the counts are cleared');
  assert.equal(h.getTotal(), 0, 'counts from a different level must not be merged in');
  assert.equal(h.getMaxCount(), 0);
  assert.ok(h.rangeMax > 0.9, 'and the range has moved onto the new signal');
});

test('a shrinking signal keeps the range until it is reset', () => {
  const h = new AmplitudeHistogram(BARS);
  h.fit(-1.0, 1.0);
  for (let i = 0; i < 1000; i++) h.add(Math.sin(i));
  const wide = h.binWidth;
  assert.equal(h.fit(-0.01, 0.01), true, 'shrinking never re-ranges, so nothing is lost');
  assert.equal(h.getTotal(), 1000);
  assert.equal(h.binWidth, wide);
  h.reset();
  assert.equal(h.ranged, false);
  h.fit(-0.01, 0.01);
  assert.ok(h.binWidth < wide / 10.0, 'after a reset the range follows the small signal');
});

// --- counting --------------------------------------------------------------

test('a sample lands in the bin whose range contains it', () => {
  const h = new AmplitudeHistogram(BARS);
  h.fit(-1.0, 1.0);
  h.add(h.binLowerEdge(700) + h.binWidth / 2);
  assert.equal(h.getCount(700), 1);
  assert.equal(h.getTotal(), 1);
});

test('out-of-range samples clamp into the edge bins', () => {
  const h = new AmplitudeHistogram(BARS);
  h.fit(-0.5, 0.5);
  h.add(10.0); h.add(1e9);
  h.add(-10.0); h.add(-1e9);
  assert.equal(h.getCount(MICRO - 1), 2, 'positive overrange piles into the top bin');
  assert.equal(h.getCount(0), 2, 'negative overrange piles into the bottom bin');
  assert.equal(h.getTotal(), 4, 'clamped samples still count towards the total');
});

test('NaN is dropped rather than counted or crashing', () => {
  const h = new AmplitudeHistogram(BARS);
  h.fit(-1.0, 1.0);
  const lo = h.rangeMin;
  h.add(NaN);
  assert.equal(h.getTotal(), 0);
  assert.equal(h.getMaxCount(), 0);
  // A non-finite bound must not re-range either: one bad sample in a block cannot be allowed to
  // restart a healthy distribution.
  assert.equal(h.fit(NaN, 1.0), true);
  assert.equal(h.rangeMin, lo);
});

test('maxCount tracks the tallest bin', () => {
  const h = new AmplitudeHistogram(BARS);
  h.fit(-1.0, 1.0);
  const v = h.binLowerEdge(123) + h.binWidth / 2;
  for (let i = 0; i < 7; i++) h.add(v);
  h.add(h.binLowerEdge(200) + h.binWidth / 2);
  assert.equal(h.getMaxCount(), 7, "the painter's horizontal full scale");
});

test('a sine fills the edges more than the centre', () => {
  const h = sine(0.8, 100_000);
  const lo = h.firstOccupied(), hi = h.lastOccupied();
  const bars = h.aggregate(lo, hi + 1, BARS);
  const edge = bars[0] + bars[BARS - 1];
  const centre = bars[BARS / 2 - 1] + bars[BARS / 2];
  assert.ok(edge > centre * 3,
    `a sine's bathtub shape: edges ${edge} should dwarf the centre ${centre}`);
  assert.equal(h.getTotal(), 100_000);
});

test('every bar is populated whatever the signal level', () => {
  // With the range pinned to full scale a small signal occupied a handful of bins and the plot
  // collapsed to a comb - or, once clamped, to two bars. Ranging on the signal makes level irrelevant.
  for (const amp of [0.9, 1e-3, 1e-6]) {
    const h = sine(amp, 200_000);
    const bars = h.aggregate(h.firstOccupied(), h.lastOccupied() + 1, BARS);
    assert.equal(bars.length, BARS);
    for (let i = 0; i < BARS; i++) {
      assert.ok(bars[i] > 0, `amp=${amp} left bar ${i} empty`);
    }
  }
});

// --- the mean, which is what makes "count as captured" possible ------------

test('the mean is reported in bin units and reads back as a voltage', () => {
  const h = new AmplitudeHistogram(BARS);
  assert.equal(h.meanBin(), -1, 'no mean until something is counted');
  h.fit(-1.0, 1.0);
  const offset = 0.4;
  for (let i = 0; i < 50_000; i++) h.add(offset + 0.05 * Math.sin((2 * Math.PI * i) / 313));
  close(h.binValue(h.meanBin()), offset, h.binWidth,
    'the reported mean must read back as the offset that was fed in:');
});

test('a DC offset shifts the mean but not the shape', () => {
  const centred = new AmplitudeHistogram(BARS);
  const offset = new AmplitudeHistogram(BARS);
  centred.fit(-1.0, 1.0);
  offset.fit(-1.0, 1.0);
  for (let i = 0; i < 20_000; i++) {
    const v = 0.1 * Math.sin((2 * Math.PI * i) / 257);
    centred.add(v);
    offset.add(v + 0.3);
  }
  const spanCentred = centred.lastOccupied() - centred.firstOccupied();
  const spanOffset = offset.lastOccupied() - offset.firstOccupied();
  assert.ok(Math.abs(spanCentred - spanOffset) <= 1,
    `an offset must not change the width: ${spanCentred} vs ${spanOffset}`);
  close(offset.binValue(offset.meanBin()) - centred.binValue(centred.meanBin()), 0.3,
    2.0 * offset.binWidth);
});

test('the occupied range is empty until something is added', () => {
  const h = new AmplitudeHistogram(BARS);
  h.fit(-1.0, 1.0);
  assert.equal(h.firstOccupied(), -1);
  assert.equal(h.lastOccupied(), -1);
});

// --- aggregation -----------------------------------------------------------

test('aggregate preserves the total and honours the bucket count', () => {
  const h = sine(0.3, 20_000);
  const bars = h.aggregate(h.firstOccupied(), h.lastOccupied() + 1, BARS);
  assert.equal(bars.length, BARS);
  let sum = 0;
  for (const b of bars) sum += b;
  assert.equal(sum, 20_000, 'no count may be split, dropped or double-counted');
});

test('aggregate of an empty or inverted range is all zeroes', () => {
  const h = sine(0.3, 1000);
  for (const bars of [h.aggregate(10, 10, 10), h.aggregate(50, 20, 10)]) {
    assert.equal(bars.length, 10);
    for (const b of bars) assert.equal(b, 0);
  }
});

test('bin edges span the range and the top edge is reachable', () => {
  const h = new AmplitudeHistogram(BARS);
  h.fit(-1.0, 1.0);
  close(h.binLowerEdge(0), h.rangeMin, 1e-12);
  close(h.binLowerEdge(MICRO), h.rangeMax, 1e-12);
});

test('a snapshot is decoupled from further accumulation', () => {
  const h = new AmplitudeHistogram(BARS);
  h.fit(-1.0, 1.0);
  h.add(0.1);
  const snap = h.snapshot();
  h.add(0.2); h.add(0.2);
  assert.equal(snap.getTotal(), 1, 'the snapshot must not see later samples');
  assert.equal(h.getTotal(), 3);
  assert.equal(snap.getMaxCount(), 1);
  assert.equal(snap.rangeMin, h.rangeMin);
});

test('the constructor rejects unusable geometry', () => {
  for (const bad of [1, 0, -5]) {
    assert.throws(() => new AmplitudeHistogram(bad), /at least 2/,
      `${bad} bars is not a distribution`);
  }
});
