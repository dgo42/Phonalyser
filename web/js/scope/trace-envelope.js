/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.gui.scope.TraceEnvelope (HEAD).
//
// Digital-phosphor accumulation and coverage pen for the oscilloscope's dense
// trace (more than one sample per pixel). Pure signal math (no canvas, no view
// state), unit-testable in isolation.
//
// columnCrossings is the accumulation: in one pass over the displayed window it
// counts, per pixel, how many consecutive-sample spans cross it — the pixel's
// dwell time, the DSO "phosphor" brightness. Each sample pair is SPLIT at every
// pixel-column boundary it crosses and each sub-span registered in its own column
// (through a per-column difference array prefix-summed on the fly), so a diagonal
// reads as a diagonal instead of one full-height block per column. With sin x/x
// rails on it also exports, per column, the band-limited value-extent as unrounded
// row-space "band" ends: refineExtreme widens an extreme only where a real sample is
// a genuine LOCAL extremum (the isLocalExtremum guard), so a monotone edge crossing
// bounds the band and adjacent columns of a split diagonal meet exactly at the
// shared crossing.
//
// penRasterize strokes the configured trace width along that band as a true round
// coverage pen (radius lineWidth/2): a swept stadium over the column's recorded
// x-extent, or — for a steep single-traversal column — a tilted capsuleColumn so a
// near-vertical flank's side fringe ramps with y. The whole rasterisation runs on a
// supersampled sub-pixel grid (with fringeDilate appending the vector stroke's AA
// fringe) and is box-averaged down (downsampleBox), so every blitted pixel carries a
// true decimal coverage.
//
// Number types mirror Java: JS number (= double) for all transforms; the diff
// accumulator is an Int32Array, the band arrays Float32Array (Java float[], so a
// store rounds to float32 exactly as Java's (float) cast does), and the coverage /
// alpha grids are Uint8Array (Java byte[] used unsigned 0..255 — Uint8Array is
// naturally unsigned, so the values match without reproducing Java's signed-byte &
// 0xFF pitfalls).

import { lanczos } from '../dsp/lanczos.js';

/** Sub-sample search step (in samples) for {@link refineExtreme} — the old ScopeView
 *  RECON_REFINE_STEP: 0.1 already lands the recovered crest within a fraction of a
 *  canvas pixel, so a finer grid buys nothing. */
const RECON_REFINE_STEP = 0.1;

/** Four quarter-pixel horizontal subsample offsets across an output pixel column
 *  [x, x+1) for the swept-stadium pen ({@link penRasterize}): probing the round cap at
 *  the column quarters restores the side anti-aliasing that point-sampling the single
 *  column centre destroyed at integral/half pen widths. Read only, so the pen's hot
 *  path allocates nothing. */
const SUBSAMPLE_OFFSETS = [0.125, 0.375, 0.625, 0.875];

/** How far a steep column's band row-span (bandBot − bandTop) may exceed the path's
 *  endpoint |entryY − exitY| and still be treated as a SINGLE monotone traversal by
 *  the {@link penRasterize} capsule branch — rows, on the supersampled grid. The band
 *  spans the sinc-refined value-extent, which widens a monotone flank a little past
 *  its raw endpoints; 2.0 rows absorbs that rail widening without admitting a genuine
 *  up-then-down (multi-crossing) column, whose band is far taller than its
 *  |entryY − exitY| and must stay a stadium. */
const CAPSULE_TRAVERSAL_SLACK = 2.0;

/** Reconstructs the band-limited curve within ±1 sample of the extreme sample at
 *  {@code idx} and returns the more-extreme of {@code seed} (the raw sample) and the
 *  curve — recovering a crest/trough that drifted between samples. */
function refineExtreme(data, n, idx, step, seed, findMax) {
  let best = seed;
  const lo = Math.max(0.0, idx - 1.0);
  const hi = Math.min(n - 1.0, idx + 1.0);
  for (let pos = lo; pos <= hi; pos += step) {
    const v = lanczos(data, n, pos, 1.0);
    if (findMax ? v > best : v < best) best = v;
  }
  return best;
}

/**
 * Digital-phosphor accumulation (more than one sample per pixel): in a single pass
 * over the displayed window [dispStart, dispStart+dispCount] it counts, for every
 * pixel, how many consecutive-sample spans cross it — the pixel's dwell time, which
 * the renderer maps to brightness. Pure math, streamed so the hot loop allocates
 * nothing.
 *
 * <p>For each consecutive sample pair (both in [0, n) — a blank / out-of-data sample
 * breaks the chain, exactly as the vector renderer blanked pos ∈ [0, n−1]), the
 * segment is SPLIT at every pixel-column boundary it crosses and each sub-span
 * registered in ITS own column, so a diagonal reads as a diagonal rather than one
 * full-height block per column.
 *
 * <p>With {@code sinc} set, each column's band ends are exported as UNROUNDED
 * row-space floats into {@code bandTop}/{@code bandBot} ({@link flushRails}): the band
 * spans the column's VALUE-EXTENT (real samples AND mid-slope boundary crossings), so
 * adjacent columns of a split diagonal meet exactly at the shared crossing. Its
 * extreme samples are additionally refined by {@link refineExtreme} and allowed to
 * WIDEN the band only where a sample is itself the extent. With {@code sinc} clear the
 * crossing counts are byte-identical to the raw accumulation and the band is left all
 * NaN.
 *
 * <p>When {@code counts} is false the entire count pass is skipped — no {@code diff}
 * writes, no prefix-sum flush, no {@code sink} calls (so {@code diff} may be null);
 * only the sin x/x band export runs. The renderer sets it false at the full-brightness
 * floor, where the sink was already a no-op and the pen alone draws the trace.
 * {@code diff} is caller-pooled and must be at least {@code height + 2} long; it must
 * be all-zero on entry and is left all-zero on exit. {@code bandTop}/{@code bandBot}
 * are caller-pooled row-space band outputs (each at least {@code width} long); this
 * pass fills them NaN on entry and writes a column's band only when {@code sinc} is
 * set, so a blank column stays NaN.
 *
 * @param {Float32Array|number[]} data
 * @param {number} n
 * @param {number} dispStart
 * @param {number} dispCount
 * @param {number} width
 * @param {number} height
 * @param {number} subSampleOffset
 * @param {number} centerY
 * @param {number} vScale
 * @param {number} dcOffset
 * @param {boolean} sinc
 * @param {boolean} counts
 * @param {Int32Array|null} diff  at least height+2 long; may be null when counts false
 * @param {Float32Array} bandTop
 * @param {Float32Array} bandBot
 * @param {Float32Array} bandXLo
 * @param {Float32Array} bandXHi
 * @param {Float32Array} bandEntryX
 * @param {Float32Array} bandEntryY
 * @param {Float32Array} bandExitX
 * @param {Float32Array} bandExitY
 * @param {(x:number,y:number,count:number)=>void} sink
 */
export function columnCrossings(data, n, dispStart, dispCount, width, height,
                                subSampleOffset, centerY, vScale, dcOffset,
                                sinc, counts, diff, bandTop, bandBot,
                                bandXLo, bandXHi, bandEntryX, bandEntryY, bandExitX, bandExitY,
                                sink) {
  if (dispCount < 2 || width <= 0 || height <= 0) return;
  for (let x = 0; x < width; x++) {
    bandTop[x] = NaN; bandBot[x] = NaN;
    bandXLo[x] = NaN; bandXHi[x] = NaN;
    bandEntryX[x] = NaN; bandEntryY[x] = NaN;
    bandExitX[x] = NaN; bandExitY[x] = NaN;
  }
  const pxPerSample = width / dispCount;
  const maxRow = height - 1;
  const last = dispStart + dispCount;
  let curCol = -1;
  let curLo = 0;
  let curHi = 0;
  let curIdxMin = 0;
  let curIdxMax = 0;
  let curValMin = Number.POSITIVE_INFINITY;   // value-extent of ALL the column receives
  let curValMax = Number.NEGATIVE_INFINITY;   // (samples AND boundary crossings), pre-round
  let curXLo = Number.POSITIVE_INFINITY;      // x-extent of the path within the column
  let curXHi = Number.NEGATIVE_INFINITY;      // (fractional, ⊂ [col, col+1]) — the pen sweeps
  let curEntryX = NaN;                         // path ENTRY point (first sub-span left) and
  let curEntryY = NaN;                         // EXIT point (latest sub-span right), unrounded
  let curExitX = NaN;                          // row space — the tilted-capsule endpoints the
  let curExitY = NaN;                          // pen sweeps for a steep single-traversal column
  let curHasSample = false;                    // did any REAL sample land in this column?
  let prevOk = false;
  let prevRow = 0;
  for (let k = dispStart; k <= last; k++) {
    const ok = k >= 0 && k < n;
    const row = ok ? valueToRow(data[k], centerY, vScale, dcOffset, maxRow) : 0;
    if (prevOk && ok) {                                  // pair (k-1, k) — both valid
      const xf0 = (k - 1 - dispStart - subSampleOffset) * pxPerSample;
      const xf1 = (k     - dispStart - subSampleOffset) * pxPerSample;
      const col0 = clampCol(Math.floor(xf0), width);
      const col1 = clampCol(Math.floor(xf1), width);
      for (let c = col0; c <= col1; c++) {
        // Sub-span endpoints: the real sample at each pair end, a LINEAR-interpolated
        // boundary crossing at each interior column edge. Reuse the pair's already-
        // rounded rows for the sample ends so an unsplit pair stays byte-identical.
        const valL = c === col0 ? data[k - 1] : crossAt(c,     data[k - 1], data[k], xf0, xf1);
        const valR = c === col1 ? data[k]     : crossAt(c + 1, data[k - 1], data[k], xf0, xf1);
        const rowL = c === col0 ? prevRow : valueToRow(valL, centerY, vScale, dcOffset, maxRow);
        const rowR = c === col1 ? row     : valueToRow(valR, centerY, vScale, dcOffset, maxRow);
        const lo = Math.min(rowL, rowR);               // raw crossing rows (pen adds width)
        const hi = Math.max(rowL, rowR);               // both already clamped to [0, maxRow]
        // The sub-span's fractional X endpoints within this column (⊂ [c, c+1]).
        const sxL = Math.max(c, Math.min(c + 1.0, xf0));
        const sxR = Math.max(c, Math.min(c + 1.0, xf1));
        if (c !== curCol) {                            // column advanced → flush the last
          if (curCol >= 0) {
            if (counts) flushColumn(curCol, curLo, curHi, diff, sink);
            if (sinc) flushRails(curCol, curHasSample, curIdxMin, curIdxMax,
                curValMin, curValMax,
                curXLo, curXHi, curEntryX, curEntryY, curExitX, curExitY,
                data, n, centerY, vScale, dcOffset,
                bandTop, bandBot, bandXLo, bandXHi,
                bandEntryX, bandEntryY, bandExitX, bandExitY);
          }
          curCol = c;
          curLo = lo;
          curHi = hi;
          curValMin = Number.POSITIVE_INFINITY;
          curValMax = Number.NEGATIVE_INFINITY;
          curXLo = Number.POSITIVE_INFINITY;
          curXHi = Number.NEGATIVE_INFINITY;
          curHasSample = false;
          // Path ENTRY = the column's first sub-span left point, unrounded row of valL.
          curEntryX = sxL;
          curEntryY = centerY - (valL - dcOffset) * vScale;
        } else {
          if (lo < curLo) curLo = lo;
          if (hi > curHi) curHi = hi;
        }
        // Path EXIT = the latest sub-span's right point, unrounded row of valR.
        curExitX = sxR;
        curExitY = centerY - (valR - dcOffset) * vScale;
        // Fold the sub-span's endpoint VALUES into the column's value-extent.
        if (valL < curValMin) curValMin = valL;
        if (valL > curValMax) curValMax = valL;
        if (valR < curValMin) curValMin = valR;
        if (valR > curValMax) curValMax = valR;
        // Fold the sub-span's fractional X-RANGE into the column's x-extent.
        if (sxL < curXLo) curXLo = sxL;
        if (sxR > curXHi) curXHi = sxR;
        // Extreme-index tracking stays sample-only (refinement needs real samples).
        if (c === col0) {
          if (!curHasSample) { curIdxMin = k - 1; curIdxMax = k - 1; curHasSample = true; }
          else {
            if (data[k - 1] < data[curIdxMin]) curIdxMin = k - 1;
            if (data[k - 1] > data[curIdxMax]) curIdxMax = k - 1;
          }
        }
        if (c === col1) {
          if (!curHasSample) { curIdxMin = k; curIdxMax = k; curHasSample = true; }
          else {
            if (data[k] < data[curIdxMin]) curIdxMin = k;
            if (data[k] > data[curIdxMax]) curIdxMax = k;
          }
        }
        if (counts) {
          diff[lo]++;
          diff[hi + 1]--;
        }
      }
    }
    prevOk = ok;
    prevRow = row;
  }
  if (curCol >= 0) {
    if (counts) flushColumn(curCol, curLo, curHi, diff, sink);
    if (sinc) flushRails(curCol, curHasSample, curIdxMin, curIdxMax,
        curValMin, curValMax,
        curXLo, curXHi, curEntryX, curEntryY, curExitX, curExitY,
        data, n, centerY, vScale, dcOffset,
        bandTop, bandBot, bandXLo, bandXHi,
        bandEntryX, bandEntryY, bandExitX, bandExitY);
  }
}

/** Applies the shared {@code round(centerY − (value − dcOffset)·vScale)} value→row
 *  transform and clamps to {@code [0, height−1]} — the same mapping the vector trace
 *  uses for Y. */
function valueToRow(value, centerY, vScale, dcOffset, maxRow) {
  const r = Math.round(centerY - (value - dcOffset) * vScale);
  return Math.max(0, Math.min(maxRow, r));
}

/** Clamps a raw pixel column into {@code [0, width)}. */
function clampCol(col, width) {
  return col < 0 ? 0 : col >= width ? width - 1 : col;
}

/** Whether the sample at {@code idx} is a LOCAL extremum against its neighbour samples
 *  — the precondition for {@link refineExtreme}. An array-edge sample is never
 *  refined. */
function isLocalExtremum(data, n, idx, findMax) {
  if (idx <= 0 || idx >= n - 1) return false;
  return findMax
      ? data[idx] >= data[idx - 1] && data[idx] >= data[idx + 1]
      : data[idx] <= data[idx - 1] && data[idx] <= data[idx + 1];
}

/** Linear value of the segment {@code (xf0,v0)→(xf1,v1)} at integer column boundary
 *  {@code b} (with {@code xf0 < b < xf1}). */
function crossAt(b, v0, v1, xf0, xf1) {
  return v0 + (v1 - v0) * ((b - xf0) / (xf1 - xf0));
}

/** Prefix-sums {@code diff[lo..hi]} into per-row crossing counts, emits each non-zero
 *  {@code (col, y, count)} to {@code sink}, and zeroes the touched range so {@code diff}
 *  is clean for the next column. */
function flushColumn(col, lo, hi, diff, sink) {
  let running = 0;
  for (let y = lo; y <= hi; y++) {
    running += diff[y];
    if (running > 0) sink(col, y, running);
    diff[y] = 0;
  }
  diff[hi + 1] = 0;
}

/** Exports a just-flushed column's band ends for {@link penRasterize} to stroke, as
 *  unrounded row-space floats through the SAME {@code centerY − (value − dcOffset)·
 *  vScale} value→row transform as the accumulation but WITHOUT rounding. The band spans
 *  the column's VALUE-EXTENT; the extreme samples are refined against the full data by
 *  {@link refineExtreme} and widen the band only where a sample is itself the extent. */
function flushRails(col, hasSample, idxMin, idxMax,
                    valExtMin, valExtMax,
                    xLo, xHi, entryX, entryY, exitX, exitY,
                    data, n, centerY, vScale, dcOffset,
                    bandTop, bandBot, bandXLo, bandXHi,
                    bandEntryX, bandEntryY, bandExitX, bandExitY) {
  let maxVal = valExtMax;
  let minVal = valExtMin;
  if (hasSample) {
    if (data[idxMax] >= valExtMax && isLocalExtremum(data, n, idxMax, true)) {
      maxVal = Math.max(refineExtreme(data, n, idxMax, RECON_REFINE_STEP,
          data[idxMax], true), valExtMax);
    }
    if (data[idxMin] <= valExtMin && isLocalExtremum(data, n, idxMin, false)) {
      minVal = Math.min(refineExtreme(data, n, idxMin, RECON_REFINE_STEP,
          data[idxMin], false), valExtMin);
    }
  }
  bandTop[col] = centerY - (maxVal - dcOffset) * vScale;
  bandBot[col] = centerY - (minVal - dcOffset) * vScale;
  bandXLo[col] = xLo;
  bandXHi[col] = xHi;
  bandEntryX[col] = entryX;
  bandEntryY[col] = entryY;
  bandExitX[col]  = exitX;
  bandExitY[col]  = exitY;
}

/**
 * Strokes a true round coverage pen of EXACTLY {@code lineWidth} pixels along a
 * per-column band and composites it into the packed {@code width×height} alpha buffer
 * {@code out} ({@code out[y·width + x]}, unsigned 0..255) via max — so it lays OVER an
 * already-packed interior without erasing it. Pure math, no allocation.
 *
 * <p>Swept-stadium model: with {@code h = lineWidth/2} the pen is a disk of radius
 * {@code h} swept along the path's TRUE horizontal extent within the source column
 * ([bandXLo[c], bandXHi[c]], falling back to the full column when NaN). Its coverage of
 * an output pixel is estimated by FOUR quarter-pixel horizontal subsamples
 * ({@link SUBSAMPLE_OFFSETS}).
 *
 * <p>Steep-column capsule branch: when a source column is steep AND a single
 * traversal, its path entry/exit points drive {@link capsuleColumn} instead — the disk
 * swept along the tilted segment so per row the neighbour's coverage ramps with the
 * drifting x.
 *
 * <p>Column bounds: {@code bandColLo}/{@code bandColHi} are the first/last non-NaN band
 * columns (empty band = {@code bandColLo > bandColHi} — nothing stroked). The outer
 * loop is bounded to {@code [bandColLo − reach, bandColHi + reach]} — identical output
 * to sweeping every column (the interior already skips NaN sources), just without the
 * wasted NaN scans.
 */
export function penRasterize(bandTop, bandBot, bandXLo, bandXHi,
                             bandEntryX, bandEntryY, bandExitX, bandExitY,
                             width, height, lineWidth, alpha255,
                             bandColLo, bandColHi, out) {
  if (width <= 0 || height <= 0) return;
  if (bandColLo > bandColHi) return;    // no non-NaN band this frame — nothing to stroke
  const h = lineWidth * 0.5;
  const hSq = h * h;
  const reach = Math.ceil(h) + 1;       // source columns each side that can reach out[x]
  const maxRow = height - 1;
  const xStart = Math.max(0, bandColLo - reach);
  const xEnd   = Math.min(width - 1, bandColHi + reach);
  for (let x = xStart; x <= xEnd; x++) {
    for (let dx = -reach; dx <= reach; dx++) {
      const c = x + dx;
      if (c < 0 || c >= width) continue;
      const top = bandTop[c];
      const bot = bandBot[c];
      if (Number.isNaN(top) || Number.isNaN(bot)) continue;
      // The pen sweeps the path's TRUE x-extent within the column, not the whole column.
      let sxLo = bandXLo[c];
      let sxHi = bandXHi[c];
      if (Number.isNaN(sxLo)) { sxLo = c; sxHi = c + 1.0; }   // no extent recorded → full column
      // Steep single-traversal column → tilted round-capped CAPSULE instead of stadium.
      const entryY = bandEntryY[c];
      const exitY  = bandExitY[c];
      if (!Number.isNaN(entryY) && !Number.isNaN(exitY)
          && (bot - top) > (sxHi - sxLo)
          && (bot - top) - Math.abs(entryY - exitY) <= CAPSULE_TRAVERSAL_SLACK) {
        capsuleColumn(bandEntryX[c], entryY, bandExitX[c], exitY,
            x, h, hSq, height, alpha255, width, out);
        continue;
      }
      // Stadium sweep: probe the pen swept along source column c's full width [c, c+1)
      // at four quarter-pixel positions of the output pixel [x, x+1).
      const vy0 = subsampleVy(x + SUBSAMPLE_OFFSETS[0], sxLo, sxHi, h, hSq);
      const vy1 = subsampleVy(x + SUBSAMPLE_OFFSETS[1], sxLo, sxHi, h, hSq);
      const vy2 = subsampleVy(x + SUBSAMPLE_OFFSETS[2], sxLo, sxHi, h, hSq);
      const vy3 = subsampleVy(x + SUBSAMPLE_OFFSETS[3], sxLo, sxHi, h, hSq);
      const vyMax = Math.max(Math.max(vy0, vy1), Math.max(vy2, vy3));
      if (vyMax < 0.0) continue;                      // all four subsamples off the sweep
      let jLo = Math.floor(top - vyMax);              // widest interval bounds the rows
      let jHi = Math.ceil(bot + vyMax) - 1;
      if (jLo < 0) jLo = 0;
      if (jHi > maxRow) jHi = maxRow;
      for (let j = jLo; j <= jHi; j++) {
        const cov = subsampleVcov(vy0, top, bot, j) + subsampleVcov(vy1, top, bot, j)
                  + subsampleVcov(vy2, top, bot, j) + subsampleVcov(vy3, top, bot, j);
        const a = Math.round(alpha255 * cov / SUBSAMPLE_OFFSETS.length);   // mean of 4
        if (a <= 0) continue;
        const idx = j * width + x;
        if (out[idx] < a) out[idx] = a;               // max-combine across source cols
      }
    }
  }
}

/** Rasterises ONE steep single-traversal source column as a tilted round-capped
 *  CAPSULE into output column {@code x}: the pen disk swept along the straight path
 *  {@code (entryX,entryY)→(exitX,exitY)} rather than the vertical band, so the
 *  neighbour's side coverage RAMPS as the path's x drifts with y. A vertical path
 *  ({@code entryX == exitX}) gives a CONSTANT xAt, hence a constant per-row fringe —
 *  the correct behaviour, deliberately untapered. Pure, allocation-free. */
function capsuleColumn(entryX, entryY, exitX, exitY, x, h, hSq, height, alpha255, width, out) {
  const yTop = Math.min(entryY, exitY);
  const yBot = Math.max(entryY, exitY);
  let jLo = Math.floor(yTop - h);
  let jHi = Math.ceil(yBot + h) - 1;
  if (jLo < 0) jLo = 0;
  if (jHi > height - 1) jHi = height - 1;
  const dyDen = exitY - entryY;
  for (let j = jLo; j <= jHi; j++) {
    const yc = j + 0.5;
    const dy = yc < yTop ? yTop - yc : yc > yBot ? yc - yBot : 0.0;
    if (dy > h) continue;                          // beyond the round cap
    const hw = Math.sqrt(hSq - dy * dy);
    let t = dyDen === 0.0 ? 0.5 : (yc - entryY) / dyDen;
    if (t < 0.0) t = 0.0; else if (t > 1.0) t = 1.0;
    const xAt = entryX + (exitX - entryX) * t;
    const lo = Math.max(xAt - hw, x);
    const hi = Math.min(xAt + hw, x + 1.0);
    let hcov = hi - lo;
    if (hcov <= 0.0) continue;                     // this output column is off the swept path
    if (hcov > 1.0) hcov = 1.0;
    const a = Math.round(alpha255 * hcov);
    if (a <= 0) continue;
    const idx = j * width + x;
    if (out[idx] < a) out[idx] = a;                // max-combine across source cols
  }
}

/** Vertical half-height of the swept round pen at horizontal subsample {@code u} over
 *  the path's x-extent {@code [xLo, xHi]}: {@code √(h²−d²)} where {@code d} is the
 *  distance from {@code u} to that interval. Returns {@code −1} — a skip marker counting
 *  0 in the four-subsample mean — when {@code d > h}. */
function subsampleVy(u, xLo, xHi, h, hSq) {
  const d = u < xLo ? xLo - u : u > xHi ? u - xHi : 0.0;
  if (d > h) return -1.0;
  return Math.sqrt(hSq - d * d);
}

/** Vertical coverage of pixel row {@code [j, j+1)} by one subsample's swept band
 *  {@code [top−vy, bot+vy]}, clamped to {@code [0, 1]}; a skipped subsample
 *  ({@code vy < 0}) contributes 0. */
function subsampleVcov(vy, top, bot, j) {
  if (vy < 0.0) return 0.0;
  const lo = Math.max(top - vy, j);
  const hi = Math.min(bot + vy, j + 1.0);
  const v = hi - lo;
  return v <= 0.0 ? 0.0 : v > 1.0 ? 1.0 : v;
}

/** Appends the vector stroke's AA fringe to a supersampled coverage grid IN PLACE: a
 *  soft-max CONE dilation so every coverage edge grows a ramp of graded neighbours
 *  while the interior stays at FULL brightness (max with itself). Runs on the sub-cell
 *  grid before the box downsample, radius {@code ss} sub-cells = 1 px of ramp;
 *  {@code scratch} is a caller-pooled buffer at least {@code w·h} (holds the horizontal
 *  pass). Pure.
 *
 *  <p>{@code x0,x1,y0,y1} bound the WRITTEN region (inclusive, clamped): the caller
 *  passes the content bounding box already grown by {@code radius}, so every cell the
 *  cone can light lies inside it and everything outside is 0 in the (cleared) grid. The
 *  horizontal pass fills {@code radius} extra rows each side. Passing the whole grid
 *  ({@code 0,w−1,0,h−1}) reproduces the full-grid pass byte-for-byte. */
export function fringeDilate(grid, w, h, radius, scratch, x0, x1, y0, y1) {
  const denom = radius + 1;
  const cx0 = Math.max(0, x0);
  const cx1 = Math.min(w - 1, x1);
  const cy0 = Math.max(0, y0);
  const cy1 = Math.min(h - 1, y1);
  const hy0 = Math.max(0, cy0 - radius);       // horizontal pass feeds the ±radius rows the
  const hy1 = Math.min(h - 1, cy1 + radius);   // vertical pass reads back
  for (let y = hy0; y <= hy1; y++) {           // horizontal max-plus cone pass over [cx0, cx1]
    const base = y * w;
    for (let x = cx0; x <= cx1; x++) {
      let best = 0;
      for (let d = -radius; d <= radius; d++) {
        const sx = x + d;
        if (sx < 0 || sx >= w) continue;
        const v = Math.floor((grid[base + sx] * (denom - Math.abs(d))) / denom);
        if (v > best) best = v;
      }
      scratch[base + x] = best;
    }
  }
  for (let x = cx0; x <= cx1; x++) {           // vertical pass, back into the grid
    for (let y = cy0; y <= cy1; y++) {
      let best = 0;
      for (let d = -radius; d <= radius; d++) {
        const sy = y + d;
        if (sy < 0 || sy >= h) continue;
        const v = Math.floor((scratch[sy * w + x] * (denom - Math.abs(d))) / denom);
        if (v > best) best = v;
      }
      grid[y * w + x] = best;
    }
  }
}

/** Box-averages a supersampled coverage grid down to pixel resolution: every output
 *  pixel {@code (x, y)} of {@code dst} becomes the rounded mean of its {@code ss×ss}
 *  sub-cell block in {@code src} ({@code srcW == ss·dstW} cells per row, unsigned
 *  0..255). Run AFTER {@link fringeDilate}. Pure, allocation-free.
 *
 *  <p>{@code ox0,ox1,oy0,oy1} bound the WRITTEN output pixels (inclusive, clamped):
 *  only pixels whose {@code ss×ss} source block can overlap the fringe content are
 *  recomputed; every other output pixel reads an all-zero block (→ 0) and is left as
 *  the caller's cleared 0, so passing the whole image reproduces the full downsample
 *  byte-for-byte. */
export function downsampleBox(src, srcW, ss, dst, dstW, dstH, ox0, ox1, oy0, oy1) {
  const cells = ss * ss;
  const half  = (cells / 2) | 0;
  const cy0 = Math.max(0, oy0);
  const cy1 = Math.min(dstH - 1, oy1);
  const cx0 = Math.max(0, ox0);
  const cx1 = Math.min(dstW - 1, ox1);
  for (let y = cy0; y <= cy1; y++) {
    const srcRow0 = y * ss;
    for (let x = cx0; x <= cx1; x++) {
      const srcCol0 = x * ss;
      let sum = 0;
      for (let sy = 0; sy < ss; sy++) {
        const base = (srcRow0 + sy) * srcW + srcCol0;
        for (let sx = 0; sx < ss; sx++) {
          sum += src[base + sx];
        }
      }
      dst[y * dstW + x] = Math.floor((sum + half) / cells);
    }
  }
}
