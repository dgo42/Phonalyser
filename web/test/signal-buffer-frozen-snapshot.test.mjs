/*
 * Phonalyser web - frozen-snapshot absolute-coordinate contract test.
 * Run with `node web/test/signal-buffer-frozen-snapshot.test.mjs`.
 *
 * Ports org.edgo.audio.measure.gui.sound.SignalBufferReaderTest
 * .frozenSnapshotKeepsAbsoluteCoordinates: the frozen copy carries the source ring's
 * write position, capacity and modulo layout, so an absolute-position read answers
 * identically from ring and copy and consumer-held anchors need no translation. A copy
 * that restarted at position zero forced a rebase against a live write position read
 * AFTER the copy - the samples appended in between shifted every anchor.
 */

import { SignalBuffer } from '../js/audio/signal-buffer.js';
import { SignalBufferReader } from '../js/audio/signal-buffer-reader.js';

let passed = 0, failed = 0;
function ok(cond, msg) { if (cond) { passed++; } else { failed++; console.error('FAIL:', msg); } }
function eq(a, b, msg) { ok(a === b, `${msg} (got ${a}, want ${b})`); }

/** Appends n sample pairs whose left value is the absolute index and whose right value is
 *  its negation - so every sample identifies the position it was written at. */
function append(buf, from, n) {
  const l = new Float64Array(n), r = new Float64Array(n);
  for (let i = 0; i < n; i++) { l[i] = from + i; r[i] = -(from + i); }
  buf.appendBatch(l, r, n);
}

/** Whether out[0..n) holds the ascending values first, first+1, ... */
function ascendingFrom(out, n, first) {
  for (let i = 0; i < n; i++) if (out[i] !== first + i) return false;
  return true;
}

// ===== The snapshot answers in the SOURCE's absolute coordinates =====
{
  const b = new SignalBuffer(48_000, 100 / 48_000);
  eq(b.getCapacity(), 100, 'ring capacity');
  const rd = new SignalBufferReader(b);
  append(b, 0, 150);                      // wrapped: resident 50..149
  const snap = rd.frozenSnapshot();

  // One point of truth for positions: the copy's write position IS the source's at the
  // moment of the copy, so consumer-held anchors need no translation against it.
  eq(snap.getWritePos(), rd.getWritePos(), 'the copy keeps the source write position');
  eq(snap.getCapacity(), rd.getCapacity(), 'the copy keeps the source capacity');

  // An absolute-position read answers identically from ring and copy.
  const live = new Float64Array(30), froz = new Float64Array(30);
  eq(rd.readEndingAt(120, 30, live, null), 30, 'ring returns 30 samples ending at 120');
  eq(snap.readEndingAt(120, 30, froz, null), 30, 'copy returns 30 samples ending at 120');
  let same = true;
  for (let i = 0; i < 30; i++) if (live[i] !== froz[i]) same = false;
  ok(same, 'an absolute-position read answers identically from ring and copy');
  ok(ascendingFrom(froz, 30, 90), 'the copied span is samples 90..119 by value');

  // The whole resident span, across the ring wrap, on both channels.
  const wholeL = new Float64Array(100), wholeR = new Float64Array(100);
  eq(snap.readLatest(100, wholeL, wholeR), 100, 'the copy holds a full ring of samples');
  ok(ascendingFrom(wholeL, 100, 50), 'the copy reads back 50..149 across the wrap (left)');
  let rightOk = true;
  for (let i = 0; i < 100; i++) if (wholeR[i] !== -(50 + i)) rightOk = false;
  ok(rightOk, 'the copy reads back -50..-149 across the wrap (right)');
}

// ===== The copy is standalone: the live ring keeps filling, the snapshot does not =====
{
  const b = new SignalBuffer(48_000, 100 / 48_000);
  const rd = new SignalBufferReader(b);
  append(b, 0, 150);
  const snap = rd.frozenSnapshot();

  append(b, 150, 150);                    // live resident span moves to 200..299
  eq(snap.getWritePos(), 150, 'the copy never grows while the live ring keeps filling');
  const froz = new Float64Array(30), live = new Float64Array(30);
  eq(rd.readEndingAt(120, 30, live, null), 0, 'the ring has dropped the span ending at 120');
  eq(snap.readEndingAt(120, 30, froz, null), 30, 'the copy still holds the span ending at 120');
  ok(ascendingFrom(froz, 30, 90), 'the held samples are unchanged 90..119');
}

// ===== The copy's capacity is EXACT, never re-derived through seconds =====
{
  // 44100 Hz, capacity 13: ceil(44100 * (13 / 44100)) is 14 in binary64, so a copy that
  // rebuilt itself from a seconds value would get a 14-slot ring and shift every wrapped
  // index against the positions it inherited.
  const b = new SignalBuffer(44_100, 12.5 / 44_100);
  eq(b.getCapacity(), 13, 'ring capacity');
  append(b, 0, 20);                       // wrapped: resident 7..19
  const frozen = b.frozenCopy();
  eq(frozen.getCapacity(), 13, 'the copy keeps the exact capacity, not a seconds round-trip');
  eq(frozen.getWritePos(), 20, 'the copy keeps the source write position');
  const out = new Float64Array(13);
  eq(frozen.readLatest(13, out, null), 13, 'the copy holds the whole 13-sample ring');
  ok(ascendingFrom(out, 13, 7), 'the copy reads back 7..19 across the wrap');
}

console.log(`\nSignalBuffer frozen snapshot: ${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
