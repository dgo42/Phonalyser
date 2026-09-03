/*
 * Phonalyser web - the divided transform's partition rule.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Run with `node web/test/fft-stage-partition.test.mjs`.
 *
 * Vocabulary (defined once): a STAGE is one of the log2(N) butterfly passes of a radix-2 FFT. A
 * GROUP is one independent slice of a stage - a stage of length len has N/len of them and they
 * touch disjoint elements. The K-LOOP is the butterflies inside one group. A LANE is one worker
 * that runs part of a stage. The TWIDDLE is the unit phasor each butterfly multiplies by, walked
 * forward by one complex multiply per step from a seed.
 *
 * What is asked here: running every lane's slice of every stage - in any order, at any lane count
 * - reproduces the serial transform. Where the stage is divided by GROUPS that must be BIT FOR
 * BIT, because each group seeds its twiddle at 1 + 0i exactly as the serial loop does. Where it
 * is divided by K-CHUNKS it cannot be, and must not pretend to be: a chunk seeds its twiddle
 * partway along with one cos/sin, which is a SHORTER recurrence than the serial loop's and so
 * carries LESS accumulated rotation error, not more. That trade is the desktop's own
 * (Fft.java stageKRange), and this file pins its size.
 *
 * The last question is about the LANE SET rather than the arithmetic: when the Threads setting
 * changes, WHEN the new count takes effect. A lane parked at a barrier must not be terminated
 * underneath a transform, so the change is recorded and adopted at the start of the next
 * transform, where replacing the set abandons no work.
 */
import { fft, bitReverse, runStageSlice, PARALLEL_THRESHOLD, MIN_K_CHUNK } from '../js/dsp/fft.js';
import { FftTransformPool } from '../js/fft/fft-transform-pool.js';

let passed = 0, failed = 0;
function ok(cond, msg) { if (cond) { passed++; } else { failed++; console.error('FAIL:', msg); } }

/** A signal with structure at several scales, so a mis-assigned slice shows up somewhere. */
function seed(n) {
  const re = new Float64Array(n), im = new Float64Array(n);
  for (let i = 0; i < n; i++) {
    re[i] = Math.sin(i * 0.031) + 0.25 * Math.cos(i * 0.7) + 1e-6 * Math.sin(i * 3.1);
    im[i] = 0.1 * Math.cos(i * 0.017);
  }
  return { re, im };
}

/** The transform run as the lanes run it: bit-reversal whole, then every stage, every lane. */
function dividedFft(n, lanes, laneOrder, kChunked = true) {
  const { re, im } = seed(n);
  bitReverse(re, im);
  for (let len = 2; len <= n; len <<= 1) {
    for (const lane of laneOrder(lanes)) runStageSlice(re, im, n, len, lane, lanes, kChunked);
  }
  return { re, im };
}

const ascending = (lanes) => Array.from({ length: lanes }, (_, i) => i);
const descending = (lanes) => ascending(lanes).reverse();

/** Largest absolute difference between two spectra, and whether they are identical. */
function compare(a, b) {
  let maxAbs = 0, identical = true;
  for (let k = 0; k < a.re.length; k++) {
    if (a.re[k] !== b.re[k] || a.im[k] !== b.im[k]) identical = false;
    maxAbs = Math.max(maxAbs, Math.abs(a.re[k] - b.re[k]), Math.abs(a.im[k] - b.im[k]));
  }
  return { maxAbs, identical };
}

function serialFft(n) {
  const { re, im } = seed(n);
  fft(re, im);
  return { re, im };
}

// --- 1. Every lane count reproduces the serial transform, and the ORDER of the lanes cannot
// matter: their slices are disjoint, so a stage is the same set of butterflies however it is
// handed out. The sizes here are small enough to stay in the group-split regime at these lane
// counts, where the answer must be exact.
for (const n of [1024, 4096, 16384]) {
  for (const lanes of [1, 2, 3, 4, 8]) {
    const serial = serialFft(n);
    const asc = compare(serial, dividedFft(n, lanes, ascending));
    const desc = compare(serial, dividedFft(n, lanes, descending));
    ok(asc.maxAbs < 1e-9, `n=${n} lanes=${lanes}: the divided transform reproduces the serial one`);
    ok(desc.maxAbs === asc.maxAbs,
      `n=${n} lanes=${lanes}: the lane order changes nothing - disjoint slices`);
  }
}

// --- 2. WHERE it is exact. A stage divided by whole groups seeds every twiddle at 1 + 0i, as the
// serial loop does, so those stages are bit-identical. Asked directly, stage by stage, so the
// claim is about the regime and not about a size that happens to avoid the other one.
{
  const n = 4096, lanes = 4;
  let exactStages = 0, chunkStages = 0;
  for (let len = 2; len <= n; len <<= 1) {
    const groups = n / len;
    const tasks = Math.min(lanes, groups);
    const groupSplit = tasks * 2 > lanes + 1;
    const a = seed(n), b = seed(n);
    bitReverse(a.re, a.im); bitReverse(b.re, b.im);
    // One stage only, from the same starting point: serial against the lanes.
    runStageSlice(a.re, a.im, n, len, 0, 1);
    for (let lane = 0; lane < lanes; lane++) runStageSlice(b.re, b.im, n, len, lane, lanes);
    const { identical } = compare(a, b);
    if (groupSplit) { exactStages++; ok(identical, `len=${len}: a group-divided stage is bit-identical`); }
    else chunkStages++;
  }
  ok(exactStages > 0 && chunkStages > 0,
    'the 4096-point transform at 4 lanes exercises BOTH regimes, so both claims are tested');
}

// --- 3. WHERE it is not, and by how much. The last stages divide the k-loop, and a chunk seeded
// partway along does not reproduce the serial last bits. The difference must stay at the level of
// double-precision rounding - decades below any audio noise floor - and never grow into a
// measurable one.
{
  const n = 65536, lanes = 8;
  const serial = serialFft(n);
  const divided = dividedFft(n, lanes, ascending);
  const { maxAbs, identical } = compare(serial, divided);
  // The spectrum's own scale, to read that difference against.
  let peak = 0;
  for (let k = 0; k < n; k++) peak = Math.max(peak, Math.abs(serial.re[k]), Math.abs(serial.im[k]));
  ok(!identical, 'the k-chunked stages do not reproduce the serial last bits - as designed');
  ok(maxAbs / peak < 1e-12,
    `and the difference is rounding, not signal: ${(maxAbs / peak).toExponential(2)} of the peak`);
}

// --- 4. The thresholds are the desktop's, and they are what decides whether any of this runs.
{
  ok(PARALLEL_THRESHOLD === 65536, 'the engage threshold is the desktop s 64k points');
  ok(MIN_K_CHUNK === 16384, 'and the smallest worthwhile k-chunk is its 16k butterflies');
}

// --- 5. A single lane IS the serial transform: the partition rule must not perturb the path the
// cross-tree bit-identity promise rests on.
{
  for (const n of [1024, 65536]) {
    const { identical } = compare(serialFft(n), dividedFft(n, 1, ascending));
    ok(identical, `n=${n}: one lane runs exactly the serial butterflies`);
  }
}

// --- 6. THE SEAM. With the last stages left undivided, EVERY stage is bit-identical to the
// serial transform at every lane count - each lane still runs whole groups, seeded at 1 + 0i.
// That is the alternative the k-chunking buys speed against, and it must stay one argument away:
// the coordinator holds the choice as a single constant, and both settings are exercised here so
// it can be changed on measurements rather than on a rewrite.
{
  for (const n of [4096, 65536]) {
    for (const lanes of [2, 4, 8, 16]) {
      const { identical } = compare(serialFft(n), dividedFft(n, lanes, ascending, false));
      ok(identical,
        `n=${n} lanes=${lanes}: undivided last stages reproduce the serial transform exactly`);
    }
  }
  // ...and it is a real restriction, not a setting that changes nothing: at a lane count the
  // last stages cannot fill, the two settings compute different last bits.
  const n = 65536, lanes = 8;
  const withChunks = dividedFft(n, lanes, ascending, true);
  const without = dividedFft(n, lanes, ascending, false);
  ok(!compare(withChunks, without).identical,
    'the two settings really do differ where the last stages are divided');
}

// --- 7. A THREADS CHANGE IS RECORDED, NOT ACTED ON. The setting can move at any moment - between
// the two frames of a tick, or while a transform is running - and a lane parked at a barrier
// cannot be taken away from it: torn down there, the transform under way is left to a watchdog and
// the analysis stops for as long as that watchdog lasts. So the setter records the wanted count
// and nothing else; the next transform to START adopts it, which is the one moment the lane set
// can be replaced without abandoning work.
//
// A plain node run has no Worker, so the lanes here are the objects the INJECTED factory returns -
// which is the seam that makes the set's lifetime testable at all. What a lane computes inside a
// stage is asked above, serially.
{
  const built = [];
  const newLane = () => {
    // A lane is STARTED as it is built - it is handed the control block and the shared pair and
    // parks in its stage loop - so the stand-in has to accept that message.
    const lane = {
      terminated: false, started: null,
      postMessage(m) { this.started = m; },
      terminate() { this.terminated = true; },
    };
    built.push(lane);
    return lane;
  };
  const pool = new FftTransformPool(3, newLane);
  ok(pool.lanes === 3, 'the pool divides across the count it was built with');

  // The lane set a divided transform holds between the frames of a tick.
  pool._ensure(1024);
  ok(built.length === 2, 'a three-lane transform holds two lane workers - lane 0 is the coordinator');

  pool.setLanes(6);
  ok(pool.lanes === 3, 'a Threads change does not take effect where it is made');
  ok(!built.some((w) => w.terminated),
    'and terminates nothing: a lane at a barrier is never taken away from a running transform');
  ok(pool._ctrl != null, 'the shared control block and the staging pair survive it as well');

  // The next transform adopts it. Below the engage threshold - and in any context without shared
  // memory, which is every node run - that transform is the serial one, and must still be exact.
  const n = 1024;
  const s = seed(n);
  pool.transform(s.re, s.im);
  ok(pool.lanes === 6, 'the next transform to start adopts the new count');
  ok(built.every((w) => w.terminated), 'and only THERE is the old lane set released');
  ok(compare(s, serialFft(n)).identical,
    'and what it computed is the serial transform, bit for bit');

  // The setter records a count, it does not queue them: several changes between transforms leave
  // the last one to be adopted.
  pool.setLanes(2);
  pool.setLanes(4);
  const s2 = seed(n);
  pool.transform(s2.re, s2.im);
  ok(pool.lanes === 4, 'several changes between two transforms adopt the last of them');

  // The failure latch is deliberately NOT re-armed by a Threads change: a lane set that could not
  // start will not start because the setting moved, and re-arming it made every change pay the
  // lane watchdog again.
  pool._laneFailed = true;
  pool.setLanes(8);
  ok(pool._laneFailed === true, 'a Threads change does not clear the lane-failure latch');
  const s3 = seed(n);
  pool.transform(s3.re, s3.im);
  ok(pool._laneFailed === true && pool.lanes === 8,
    'nor does the transform that adopts the new count');
}

console.log(`fft-stage-partition.test: ${passed} passed, ${failed} failed`);
process.exit(failed === 0 ? 0 : 1);
