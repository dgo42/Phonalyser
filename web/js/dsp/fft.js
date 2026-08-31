/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Iterative in-place radix-2 Cooley-Tukey FFT on parallel real/imag Float64Array.
// JavaScript `number` is IEEE-754 binary64 - the same type as the desktop's Java
// `double` - so this produces bit-identical results to the Java FFT engine.

/** @returns {boolean} true when n is a power of two (and > 0). */
export function isPow2(n) { return n > 0 && (n & (n - 1)) === 0; }

/**
 * In-place Gold-Rader bit-reversal permutation - the reordering every butterfly stage below
 * assumes. Cheap next to the stages, and kept whole: it permutes across the entire array, so it
 * is the one part of the transform that cannot be cut into independent ranges.
 * Faithful port of Fft.java bitReverse.
 */
export function bitReverse(re, im) {
  const n = re.length;
  for (let i = 1, j = 0; i < n; i++) {
    let bit = n >> 1;
    for (; j & bit; bit >>= 1) j ^= bit;
    j ^= bit;
    if (i < j) { let t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
  }
}

/**
 * Butterfly GROUPS [gStart, gEnd) of one stage of length `len`. Within a stage the n/len groups
 * each touch a disjoint slice [g*len, g*len+len) and nothing else, so any partition of them
 * produces exactly the butterflies the whole stage produces - the same operations on the same
 * numbers, only divided between hands. Every group seeds its twiddle at 1 + 0i, as the serial
 * loop does, so a partitioned stage is bit-identical to an unpartitioned one.
 * Faithful port of Fft.java stageGroups.
 *
 * @param {number} wr cos(-2*PI/len), computed once by the caller and shared by every group.
 * @param {number} wi sin(-2*PI/len).
 */
export function stageGroups(re, im, gStart, gEnd, len, wr, wi) {
  const half = len >> 1;
  for (let g = gStart; g < gEnd; g++) {
    const i = g * len;
    let cr = 1, ci = 0;
    for (let k = 0; k < half; k++) {
      const p = i + k, q = p + half;
      const ar = re[p], ai = im[p];
      const vr = re[q] * cr - im[q] * ci, vi = re[q] * ci + im[q] * cr;
      re[p] = ar + vr; im[p] = ai + vi;
      re[q] = ar - vr; im[q] = ai - vi;
      const ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr;
    }
  }
}

/**
 * Butterflies [kStart, kEnd) of the SINGLE group at offset `base`, in a stage of length `len`,
 * seeding the twiddle recurrence at kStart with one cos/sin. This is what lets the last stages -
 * which have fewer groups than there are hands, and a huge k-loop in each - be divided at all.
 *
 * A chunk seeded this way does NOT reproduce the serial loop's last bits, because the serial loop
 * reaches k by rotating a unit phasor kStart times more than this does. It is not worse: each
 * recurrence chain here is SHORTER than the serial one it replaces, so its accumulated rotation
 * error is smaller. The desktop makes exactly the same trade for the same reason
 * (Fft.java stageKRange and the numerics note in its class doc).
 *
 * @param {number} ang -2*PI/len, the per-butterfly rotation the seed is raised to.
 */
export function stageKRange(re, im, base, len, kStart, kEnd, ang, wr, wi) {
  const half = len >> 1;
  let cr = Math.cos(ang * kStart);
  let ci = Math.sin(ang * kStart);
  for (let k = kStart; k < kEnd; k++) {
    const p = base + k, q = p + half;
    const ar = re[p], ai = im[p];
    const vr = re[q] * cr - im[q] * ci, vi = re[q] * ci + im[q] * cr;
    re[p] = ar + vr; im[p] = ai + vi;
    re[q] = ar - vr; im[q] = ai - vi;
    const ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr;
  }
}

/**
 * Forward FFT, in place. `re`/`im` are Float64Array of equal length N (a power of two).
 * On return they hold the complex spectrum X[k], k = 0..N-1.
 *
 * This is the SERIAL REFERENCE: one thread, every stage whole, every group's twiddle seeded at
 * 1 + 0i. It is what the desktop's own serial path computes bit for bit, and that identity is the
 * promise the cross-tree parity rests on - a parallel transform is measured against THIS.
 */
export function fft(re, im) {
  const n = re.length;
  bitReverse(re, im);
  // Butterflies; twiddle by incremental rotation (recurrence error ~1e-13, far below any audio floor).
  for (let len = 2; len <= n; len <<= 1) {
    const ang = -2 * Math.PI / len;
    stageGroups(re, im, 0, n / len, len, Math.cos(ang), Math.sin(ang));
  }
}

/** Below this transform size a divided transform loses to the serial one - the hand-off costs
 *  more than the butterflies saved. Fft.java PARALLEL_THRESHOLD. */
export const PARALLEL_THRESHOLD = 1 << 16;   // 65536 points
/** Fewest butterflies worth giving a k-chunk its own twiddle seed: below this the seeding cos/sin
 *  and the hand-off outweigh the division. Fft.java MIN_K_CHUNK. */
export const MIN_K_CHUNK = 1 << 14;          // 16384 butterflies

/**
 * The slice of ONE stage that lane `lane` of `lanes` is responsible for - the whole partition
 * rule in one pure function, so the lanes and the tests apply the same one. Faithful port of the
 * task-building half of Fft.java butterfliesParallel.
 *
 * Two regimes, exactly as the desktop:
 *  - Plenty of groups (the early stages): divide the GROUPS. Each lane runs whole groups, so the
 *    stage is bit-identical to the serial one however many lanes there are.
 *  - Fewer groups than lanes (the last log2(lanes) stages, where one group holds half the
 *    transform): divide each group's K-LOOP instead, each chunk seeding its own twiddle. Without
 *    this the largest fifth of the work would stay on one lane and cap the whole transform.
 *    These stages are the ones that do not reproduce the serial last bits - see stageKRange.
 *
 * Every lane derives its own slice from (n, len, lanes, lane) alone, so the lanes need to be told
 * nothing per stage beyond the stage length - and the slices are disjoint and cover the stage
 * whatever order the lanes run in.
 *
 * @param {boolean} [kChunked=true] whether the second regime is allowed at all. With it off the
 *   stages that have fewer groups than lanes are run whole by one lane instead of being divided,
 *   which makes EVERY stage bit-identical to the serial transform at the price of the last
 *   stages' share of the work - the largest fifth of it. This is the seam that decides between
 *   the desktop's own trade (speed, with the last stages machine-dependent) and full identity;
 *   the choice belongs to the caller, and dsp keeps no setting of its own.
 */
export function runStageSlice(re, im, n, len, lane, lanes, kChunked = true) {
  const ang = -2 * Math.PI / len;
  const wr = Math.cos(ang), wi = Math.sin(ang);
  const groups = n / len;
  const tasks = Math.min(lanes, groups);
  if (tasks * 2 > lanes + 1) {
    const per = Math.ceil(groups / tasks);
    const gStart = lane * per;
    if (lane >= tasks || gStart >= groups) return;         // more lanes than work: this one idles
    stageGroups(re, im, gStart, Math.min(groups, gStart + per), len, wr, wi);
    return;
  }
  const half = len >> 1;
  const kChunks = kChunked
    ? Math.min(Math.max(1, Math.trunc(lanes / groups)), Math.max(1, Math.trunc(half / MIN_K_CHUNK)))
    : 1;
  if (groups * kChunks <= 1) {
    if (lane === 0) stageGroups(re, im, 0, groups, len, wr, wi);   // nothing to divide
    return;
  }
  const kPer = Math.ceil(half / kChunks);
  // The work items are (group, k-chunk) pairs; the lanes take them round-robin. Which lane gets
  // which item cannot change the result - the items are disjoint - so this needs no agreement
  // beyond the count.
  for (let m = lane; m < groups * kChunks; m += lanes) {
    const g = Math.trunc(m / kChunks), c = m % kChunks;
    const kStart = c * kPer;
    if (kStart >= half) continue;
    stageKRange(re, im, g * len, len, kStart, Math.min(half, kStart + kPer), ang, wr, wi);
  }
}

/**
 * Inverse FFT, in place, via conjugation: ifft(x) = conj(fft(conj(x))) / N.
 * Used by frequency-response deconvolution. Leaves re/im as the time-domain signal.
 */
export function ifft(re, im) {
  const n = re.length;
  for (let i = 0; i < n; i++) im[i] = -im[i];
  fft(re, im);
  const inv = 1 / n;
  for (let i = 0; i < n; i++) { re[i] = re[i] * inv; im[i] = -im[i] * inv; }
}
