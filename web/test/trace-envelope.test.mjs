/*
 * Phonalyser web - TraceEnvelope digital-phosphor math contract test.
 * Run with `node web/test/trace-envelope.test.mjs`.
 *
 * Ports the pure-math fixtures of Java
 * org.edgo.audio.measure.gui.scope.TraceEnvelopeCrossingsTest to lock the JS port
 * of columnCrossings (dwell accumulation + sin x/x band export), penRasterize
 * (round swept-stadium + tilted-capsule coverage pen), fringeDilate and
 * downsampleBox against the desktop's exact 8-bit output.
 *
 * Every count fixture uses the identity value->row transform (centerY = 0,
 * vScale = −1, dcOffset = 0), so row = round(value) and a sample of value v lands on
 * pixel row v - the expected per-row crossing counts are exact and readable. The pen
 * fixtures build bandTop/bandBot directly in row space (NaN = blank), so the disk
 * geometry and its anti-aliasing are asserted as exact 8-bit coverage.
 *
 * Adds the bounded-vs-full-range checks (item b): the region-bounded penRasterize /
 * fringeDilate / downsampleBox must be byte-identical to the whole-range calls.
 */

import {
  columnCrossings, penRasterize, fringeDilate, downsampleBox,
} from '../js/scope/trace-envelope.js';

let passed = 0, failed = 0;
function ok(cond, msg) { if (cond) { passed++; } else { failed++; console.error('FAIL:', msg); } }
function eq(a, b, msg) { ok(a === b, `${msg} (got ${a}, want ${b})`); }
function approx(a, b, tol, msg) { ok(Math.abs(a - b) <= tol, `${msg} (got ${a}, want ${b})`); }
function arrEq(a, b, msg) {
  let same = a.length === b.length;
  for (let i = 0; same && i < a.length; i++) if (a[i] !== b[i]) same = false;
  ok(same, `${msg} (got [${a}], want [${b}])`);
}

const CENTER_Y = 0.0;
const V_SCALE  = -1.0;   // row == round(value)
const DC       = 0.0;

/** Accumulation (sin x/x rails off) into a [height][width] grid of per-pixel crossing
 *  counts (0 where the sink never fired). */
function crossings(data, dispStart, dispCount, width, height, subSampleOffset) {
  const diff = new Int32Array(height + 2);
  const bandTop = new Float32Array(width), bandBot = new Float32Array(width);
  const bandXLo = new Float32Array(width), bandXHi = new Float32Array(width);
  const eX = new Float32Array(width), eY = new Float32Array(width);
  const xX = new Float32Array(width), xY = new Float32Array(width);
  const grid = Array.from({ length: height }, () => new Array(width).fill(0));
  columnCrossings(data, data.length, dispStart, dispCount, width, height, subSampleOffset,
    CENTER_Y, V_SCALE, DC, false, true, diff, bandTop, bandBot, bandXLo, bandXHi,
    eX, eY, xX, xY, (x, y, count) => { grid[y][x] = count; });
  return grid;
}

/** Accumulation with the sin x/x rails ON, filling the caller's bandTop/bandBot and
 *  returning the per-pixel crossing-count grid. */
function crossingsSinc(data, dispStart, dispCount, width, height, subSampleOffset, bandTop, bandBot) {
  const diff = new Int32Array(height + 2);
  const bandXLo = new Float32Array(width), bandXHi = new Float32Array(width);
  const eX = new Float32Array(width), eY = new Float32Array(width);
  const xX = new Float32Array(width), xY = new Float32Array(width);
  const grid = Array.from({ length: height }, () => new Array(width).fill(0));
  columnCrossings(data, data.length, dispStart, dispCount, width, height, subSampleOffset,
    CENTER_Y, V_SCALE, DC, true, true, diff, bandTop, bandBot, bandXLo, bandXHi,
    eX, eY, xX, xY, (x, y, count) => { grid[y][x] = count; });
  return grid;
}

function nanRow(width) { const a = new Float32Array(width); a.fill(NaN); return a; }

/** penRasterize into a fresh [height][width] unsigned alpha grid, with explicit
 *  per-column x-extents and optional capsule entry/exit (NaN = classic stadium). */
function pen(bandTop, bandBot, width, height, lineWidth, alpha255,
            bandXLo, bandXHi, eX, eY, xX, xY) {
  if (!bandXLo) {   // default: full-column x-extents (the classic stadium fixtures)
    bandXLo = new Float32Array(width); bandXHi = new Float32Array(width);
    for (let c = 0; c < width; c++) { bandXLo[c] = c; bandXHi[c] = c + 1; }
  }
  eX = eX || nanRow(width); eY = eY || nanRow(width);
  xX = xX || nanRow(width); xY = xY || nanRow(width);
  const out = new Uint8Array(width * height);
  penRasterize(bandTop, bandBot, bandXLo, bandXHi, eX, eY, xX, xY,
    width, height, lineWidth, alpha255, 0, width - 1, out);
  const grid = Array.from({ length: height }, () => new Array(width).fill(0));
  for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) grid[y][x] = out[y * width + x];
  return grid;
}

function f32(arr) { return Float32Array.from(arr); }

// ===== columnCrossings: dwell accumulation =====
{
  // A ramp pair (0 -> 4) in one column spans pixel rows 0..4 (5 rows) -> each +1.
  const g = crossings(f32([0, 4]), 0, 2, 1, 8, 0.0);
  const col = []; for (let y = 0; y < 8; y++) col.push(g[y][0]);
  arrEq(col, [1, 1, 1, 1, 1, 0, 0, 0], 'monotone ramp pair lights exactly its span rows');
}
{
  // A pair within one pixel row (3 -> 3) registers that single row once.
  const g = crossings(f32([3, 3]), 0, 2, 1, 8, 0.0);
  const col = []; for (let y = 0; y < 8; y++) col.push(g[y][0]);
  arrEq(col, [0, 0, 0, 1, 0, 0, 0, 0], 'flat pair dwells on its single row');
}
{
  // A triangle (0 -> 2 -> 4) in one column: spans [0,2] and [2,4] overlap at apex row 2.
  const g = crossings(f32([0, 2, 4]), 0, 3, 1, 6, 0.0);
  const col = []; for (let y = 0; y < 6; y++) col.push(g[y][0]);
  arrEq(col, [1, 1, 2, 1, 1, 0], 'triangle peak: interior 2, rails 1');
}
{
  // dispStart = −1 puts the first window sample OUT of data, breaking the phantom pair.
  const g = crossings(f32([4, 0]), -1, 3, 3, 6, 0.0);
  const col0 = [], col1 = [];
  for (let y = 0; y < 6; y++) { col0.push(g[y][0]); col1.push(g[y][1]); }
  arrEq(col0, [0, 0, 0, 0, 0, 0], 'out-of-data sample breaks the pair chain (col 0 blank)');
  arrEq(col1, [1, 1, 1, 1, 1, 0], 'the valid pair still fires (col 1 span)');
}
{
  // A smooth raised-cosine arch with its true crest between samples 20 and 21.
  const n = 41;
  const data = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    let hgt = 0.5 * (1.0 + Math.cos(2 * Math.PI * (i - 20.5) / 4.0));
    if (Math.abs(i - 20.5) > 2.0) hgt = 0.0;
    data[i] = 10.0 + 20.0 * hgt;
  }
  const bandTop = new Float32Array(1), bandBot = new Float32Array(1);
  crossingsSinc(data, 0, n - 1, 1, 40, 0.0, bandTop, bandBot);
  ok(bandTop[0] > 27.5, 'sinc export lifts the refined crest past the sampled rail (row 27)');
  ok(bandBot[0] < 11, 'sinc export carries the refined trough at the arch floor (value 10)');
}
{
  // Falling ramp (30 -> 20) at spp 1.25 straddling the col-0/col-1 boundary at b = 1.
  const bandTop = new Float32Array(4), bandBot = new Float32Array(4);
  const g = crossingsSinc(f32([30, 20]), -1, 5, 4, 36, 0.0, bandTop, bandBot);
  eq(g[30][0], 1, 'col0 sub-span row 30');
  eq(g[28][0], 1, 'col0 sub-span row 28');
  eq(g[27][0], 0, 'col0 above the crossing');
  eq(g[24][0], 0, 'col0 no staircase full block');
  eq(g[28][1], 1, 'col1 gets the rest, row 28');
  eq(g[20][1], 1, 'col1 gets the rest, row 20');
  eq(g[29][1], 0, 'col1 nothing above the crossing');
  approx(bandBot[0], 27.5, 1e-4, 'col0 band bottom = interpolated crossing');
  approx(bandTop[1], 27.5, 1e-4, 'col1 band top = interpolated crossing');
  approx(bandBot[0], bandTop[1], 1e-4, 'the bands connect at the shared crossing');
  ok(bandTop[0] >= 30 - 1e-4, 'outer top reaches the real sample');
  ok(bandBot[1] <= 20 + 1e-4, 'outer bottom reaches the real sample');
}
{
  // A pair fully inside one column must be byte-identical whether rails are on or off.
  const off = crossings(f32([0, 4]), 0, 2, 1, 8, 0.0);
  const bandTop = new Float32Array(1), bandBot = new Float32Array(1);
  const on = crossingsSinc(f32([0, 4]), 0, 2, 1, 8, 0.0, bandTop, bandBot);
  const colOff = [], colOn = [];
  for (let y = 0; y < 8; y++) { colOff.push(off[y][0]); colOn.push(on[y][0]); }
  arrEq(colOn, colOff, 'rails toggle never touches the dwell counts');
}
{
  // A steep rising edge (0 -> 12) crossing the boundary mid-pair (b = 1, crossing row 3).
  const bandTop = new Float32Array(4), bandBot = new Float32Array(4);
  const g = crossingsSinc(f32([0, 12]), -1, 5, 4, 20, 0.0, bandTop, bandBot);
  eq(g[0][0], 1, 'left col partial rise, row 0');
  eq(g[3][0], 1, 'left col up to crossing, row 3');
  eq(g[4][0], 0, 'left col stops at crossing');
  eq(g[12][1], 1, 'right col the rest, row 12');
  eq(g[2][1], 0, 'right col starts at crossing');
  approx(bandTop[0], 3, 1e-4, 'left top = crossing');
  approx(bandBot[1], 3, 1e-4, 'right bottom = crossing');
}
{
  // The mid-slope crossing bounds the band, unrefined (NOT pulled to the neighbour trough).
  const bandTop = new Float32Array(4), bandBot = new Float32Array(4);
  crossingsSinc(f32([30, 20]), -1, 5, 4, 36, 0.0, bandTop, bandBot);
  approx(bandBot[0], 27.5, 1e-4, 'band end from mid-slope crossing = linear value, unrefined');
  ok(bandBot[0] > 21, 'decisively not pulled to the neighbour trough (20)');
}

// ===== item (a): counts == false leaves the band export identical to counts == true =====
{
  const n = 200;
  const data = new Float32Array(n);
  for (let i = 0; i < n; i++) data[i] = 12.0 * Math.sin(i * 0.37) + 3.0 * Math.sin(i * 0.09);
  const width = 12, height = 48, dispStart = 5, dispCount = 180, sso = 0.3;

  const onTop = new Float32Array(width), onBot = new Float32Array(width);
  const onXLo = new Float32Array(width), onXHi = new Float32Array(width);
  const onEX = new Float32Array(width), onEY = new Float32Array(width);
  const onXX = new Float32Array(width), onXY = new Float32Array(width);
  const diff = new Int32Array(height + 2);
  columnCrossings(data, n, dispStart, dispCount, width, height, sso, CENTER_Y, V_SCALE, DC,
    true, true, diff, onTop, onBot, onXLo, onXHi, onEX, onEY, onXX, onXY, () => {});

  const offTop = new Float32Array(width), offBot = new Float32Array(width);
  const offXLo = new Float32Array(width), offXHi = new Float32Array(width);
  const offEX = new Float32Array(width), offEY = new Float32Array(width);
  const offXX = new Float32Array(width), offXY = new Float32Array(width);
  let sinkFired = false;
  // counts == false -> diff is never touched (pass null to prove it), sink must never fire.
  columnCrossings(data, n, dispStart, dispCount, width, height, sso, CENTER_Y, V_SCALE, DC,
    true, false, null, offTop, offBot, offXLo, offXHi, offEX, offEY, offXX, offXY,
    () => { sinkFired = true; });

  ok(!sinkFired, 'sink must not fire when counts == false');
  arrEq(offTop, onTop, 'band top identical when counts off');
  arrEq(offBot, onBot, 'band bottom identical when counts off');
  arrEq(offXLo, onXLo, 'x-extent lo identical when counts off');
  arrEq(offXHi, onXHi, 'x-extent hi identical when counts off');
  arrEq(offEX, onEX, 'entry x identical when counts off');
  arrEq(offEY, onEY, 'entry y identical when counts off');
  arrEq(offXX, onXX, 'exit x identical when counts off');
  arrEq(offXY, onXY, 'exit y identical when counts off');
}

// ===== penRasterize: swept-stadium =====
{
  // A hairline band centred in row 2, width 1: own row full, blank neighbours catch fringe.
  const centred = pen(f32([NaN, 2.5, NaN]), f32([NaN, 2.5, NaN]), 3, 6, 1, 255);
  eq(centred[2][1], 255, 'hairline own row full');
  eq(centred[1][1], 0, 'row above blank');
  eq(centred[3][1], 0, 'row below blank');
  eq(centred[2][0], 104, 'stadium side fringe into blank neighbour (left)');
  eq(centred[2][2], 104, 'stadium side fringe into blank neighbour (right)');

  const straddle = pen(f32([2.0]), f32([2.0]), 1, 6, 1, 255);
  eq(straddle[1][0], 128, 'boundary straddle splits row 1');
  eq(straddle[2][0], 128, 'boundary straddle splits row 2');
  eq(straddle[0][0], 0, 'straddle row 0 blank');
}
{
  // A width-2 hairline centred in row 2 spans exactly two pixels with AA boundary rows.
  const g = pen(f32([2.5]), f32([2.5]), 1, 6, 2, 255);
  eq(g[1][0], 128, 'width-2 hairline row 1 half');
  eq(g[2][0], 255, 'width-2 hairline row 2 full');
  eq(g[3][0], 128, 'width-2 hairline row 3 half');
  approx((g[1][0] + g[2][0] + g[3][0]) / 255.0, 2.0, 0.02, 'two pixels of coverage mass');
}
{
  // The round cap's horizontal AA (the reason for the four subsamples).
  const width = 10, height = 10;
  const top = nanRow(width), bot = nanRow(width);
  top[5] = 5.5; bot[5] = 5.5;
  const g = pen(top, bot, width, height, 1, 255);
  eq(g[5][5], 255, 'own column own row full');
  eq(g[5][6], 104, 'first neighbour right round-cap fringe');
  eq(g[5][4], 104, 'first neighbour left symmetric');
  eq(g[5][7], 0, 'second neighbour beyond the cap');
}
{
  // width 3 (reach 3): the round cap decays outward and is zero beyond reach.
  const width = 10, height = 10;
  const top = nanRow(width), bot = nanRow(width);
  top[5] = 5.5; bot[5] = 5.5;
  const g = pen(top, bot, width, height, 3, 255);
  eq(g[5][5], 255, 'source column centre row');
  eq(g[4][5], 255, 'source column cap fills band');
  eq(g[5][6], 255, 'first neighbour cap centre full');
  eq(g[4][6], 225, 'first neighbour edge row decayed');
  eq(g[5][7], 128, 'second neighbour further decayed');
  eq(g[5][8], 0, 'third column decayed to nothing');
  let clean = true; for (let y = 0; y < height; y++) if (g[y][9] !== 0) clean = false;
  ok(clean, 'beyond reach untouched');
}
{
  // A refined crest carried as an unrounded float covers a row raw rounding would miss.
  const g = pen(f32([2.9]), f32([2.9]), 1, 6, 1, 255);
  eq(g[2][0], 153, 'fractional band covers row 2 (0.6)');
  eq(g[3][0], 102, 'fractional band covers row 3 (0.4)');
}
{
  // A vertical band whose x-extent is the single fractional position x = 1.25.
  const width = 4, height = 12;
  const top = nanRow(width), bot = nanRow(width);
  const xLo = nanRow(width), xHi = nanRow(width);
  top[1] = 2; bot[1] = 8; xLo[1] = 1.25; xHi[1] = 1.25;
  const g = pen(top, bot, width, height, 2, 255, xLo, xHi);
  eq(g[5][1], 255, 'own column interior row full');
  eq(g[5][0], 191, 'left neighbour 3 of 4 subsamples');
  eq(g[5][2], 64, 'right neighbour 1 of 4 - asymmetry proves fractional x');
  eq(g[5][3], 0, 'beyond the pen');
}

// ===== penRasterize: tilted capsule =====
{
  // A near-vertical traversal whose x drifts one column over 8 rows -> the maintainer's ramp.
  const width = 3, height = 10;
  const top = nanRow(width), bot = nanRow(width), xLo = nanRow(width), xHi = nanRow(width);
  const eX = nanRow(width), eY = nanRow(width), xX = nanRow(width), xY = nanRow(width);
  top[1] = 0; bot[1] = 8; xLo[1] = 1.0; xHi[1] = 2.0;
  eX[1] = 1.0; eY[1] = 0; xX[1] = 2.0; xY[1] = 8;
  const g = pen(top, bot, width, height, 2, 255, xLo, xHi, eX, eY, xX, xY);
  approx(g[0][2], 16, 1, 'right neighbour ramps up, row 0');
  approx(g[4][2], 143, 1, 'right neighbour ramps up, row 4');
  approx(g[7][2], 239, 1, 'right neighbour ramps up, row 7');
  approx(g[0][0], 239, 1, 'left neighbour mirror, row 0');
  approx(g[4][0], 112, 1, 'left neighbour mirror, row 4');
  eq(g[3][1], 255, 'own column fully covered along the flank');
}
{
  // A perfectly vertical traversal keeps a constant fringe; the cap decays past the ends.
  const width = 3, height = 10;
  const top = nanRow(width), bot = nanRow(width), xLo = nanRow(width), xHi = nanRow(width);
  const eX = nanRow(width), eY = nanRow(width), xX = nanRow(width), xY = nanRow(width);
  top[1] = 0; bot[1] = 8; xLo[1] = 1.5; xHi[1] = 1.5;
  eX[1] = 1.5; eY[1] = 0; xX[1] = 1.5; xY[1] = 8;
  const g = pen(top, bot, width, height, 2, 255, xLo, xHi, eX, eY, xX, xY);
  eq(g[2][0], 128, 'constant side fringe row 2');
  eq(g[3][0], 128, 'constant side fringe row 3');
  eq(g[4][0], 128, 'constant side fringe row 4 - untapered');
  eq(g[8][0], 93, 'round cap decays past the flank end');
  ok(g[8][0] < g[3][0], 'the cap is dimmer than the body plateau');
}
{
  // A tall up-then-down column is NOT a single traversal -> falls back to the stadium.
  const width = 5, height = 24;
  const top = nanRow(width), bot = nanRow(width), xLo = nanRow(width), xHi = nanRow(width);
  const eX = nanRow(width), eY = nanRow(width), xX = nanRow(width), xY = nanRow(width);
  top[2] = 0; bot[2] = 20; xLo[2] = 2; xHi[2] = 3;
  eX[2] = 2.5; eY[2] = 9; xX[2] = 2.5; xY[2] = 11;
  const g = pen(top, bot, width, height, 2, 255, xLo, xHi, eX, eY, xX, xY);
  eq(g[0][2], 255, 'stadium fills the whole band (capsule would leave row 0 at 0)');
  eq(g[20][2], 255, 'and the far bottom rail');
}

// ===== downsampleBox + fringeDilate =====
{
  const src = new Uint8Array([
    255, 255, 255, 0,
    255, 255, 0, 0,
    128, 128, 0, 0,
    64, 64, 0, 0,
  ]);
  const dst = new Uint8Array(4);
  downsampleBox(src, 4, 2, dst, 2, 2, 0, 1, 0, 1);
  eq(dst[0], 255, 'downsample block 0');
  eq(dst[1], 64, 'downsample block 1');
  eq(dst[2], 96, 'downsample block 2');
  eq(dst[3], 0, 'downsample block 3');
}
{
  const w = 8, h = 6;
  const grid = new Uint8Array(w * h);
  for (let y = 0; y < h; y++) { grid[y * w + 4] = 255; grid[y * w + 5] = 255; }
  const scratch = new Uint8Array(w * h);
  fringeDilate(grid, w, h, 2, scratch, 0, w - 1, 0, h - 1);
  eq(grid[2 * w + 4], 255, 'fringe interior untouched');
  eq(grid[2 * w + 3], 170, 'one cell out: 2/3');
  eq(grid[2 * w + 2], 85, 'two cells out: 1/3');
  eq(grid[2 * w + 6], 170, 'symmetric right side');
  const dst = new Uint8Array((w / 2) * (h / 2));
  downsampleBox(grid, w, 2, dst, w / 2, h / 2, 0, w / 2 - 1, 0, h / 2 - 1);
  eq(dst[1 * (w / 2) + 1], 128, 'downsampled left fringe pixel');
  eq(dst[1 * (w / 2) + 2], 255, 'aligned core stays full brightness');
  eq(dst[1 * (w / 2) + 3], 128, 'right fringe pixel');
}

// ===== item (b): bounded region == whole-range calls (byte-identical) =====
{
  // Build a realistic supersampled band on a sine so there is a genuine content sub-box.
  const n = 400;
  const data = new Float32Array(n);
  for (let i = 0; i < n; i++) data[i] = 0.35 * Math.sin(i * 0.21);
  const ss = 2, width = 240, height = 160, ssTop = 8.0;
  const bandTop = new Float32Array(width), bandBot = new Float32Array(width);
  const bandXLo = new Float32Array(width), bandXHi = new Float32Array(width);
  const eX = new Float32Array(width), eY = new Float32Array(width);
  const xX = new Float32Array(width), xY = new Float32Array(width);
  const diff = new Int32Array(height + 2);
  columnCrossings(data, n, 0, 960, width, height, 0.0, height / 2.0 * ss, ssTop, 0.0,
    true, true, diff, bandTop, bandBot, bandXLo, bandXHi, eX, eY, xX, xY, () => {});
  // content columns
  let colLo = width, colHi = -1, rowMin = Infinity, rowMax = -Infinity;
  for (let x = 0; x < width; x++) {
    const t = bandTop[x];
    if (Number.isNaN(t)) continue;
    if (colLo === width) colLo = x;
    colHi = x;
    const b = bandBot[x];
    rowMin = Math.min(rowMin, t, b); rowMax = Math.max(rowMax, t, b);
  }
  const lineWidth = 2 * ss;

  // penRasterize: bounded [colLo, colHi] vs full [0, width-1].
  const outFull = new Uint8Array(width * height);
  const outBound = new Uint8Array(width * height);
  penRasterize(bandTop, bandBot, bandXLo, bandXHi, eX, eY, xX, xY,
    width, height, lineWidth, 255, 0, width - 1, outFull);
  penRasterize(bandTop, bandBot, bandXLo, bandXHi, eX, eY, xX, xY,
    width, height, lineWidth, 255, colLo, colHi, outBound);
  arrEq(outBound, outFull, 'bounded penRasterize == full-range penRasterize');

  // fringeDilate: whole grid vs the content box grown by radius (= ss).
  const penReach = Math.ceil(lineWidth * 0.5) + 1;
  const margin = penReach + ss;
  const rgX0 = Math.max(0, colLo - margin), rgX1 = Math.min(width - 1, colHi + margin);
  const rgY0 = Math.max(0, Math.floor(rowMin) - margin), rgY1 = Math.min(height - 1, Math.ceil(rowMax) + margin);
  const gridFull = outFull.slice();
  const gridBound = outFull.slice();
  const scratch = new Uint8Array(width * height);
  fringeDilate(gridFull, width, height, ss, scratch, 0, width - 1, 0, height - 1);
  scratch.fill(0);
  fringeDilate(gridBound, width, height, ss, scratch, rgX0, rgX1, rgY0, rgY1);
  arrEq(gridBound, gridFull, 'bounded fringeDilate == full-grid fringeDilate');

  // downsampleBox: whole image vs the content box / ss.
  const devW = width / ss, devH = height / ss;
  const dstFull = new Uint8Array(devW * devH);
  const dstBound = new Uint8Array(devW * devH);
  downsampleBox(gridFull, width, ss, dstFull, devW, devH, 0, devW - 1, 0, devH - 1);
  downsampleBox(gridFull, width, ss, dstBound, devW, devH,
    Math.floor(rgX0 / ss), Math.floor(rgX1 / ss), Math.floor(rgY0 / ss), Math.floor(rgY1 / ss));
  arrEq(dstBound, dstFull, 'bounded downsampleBox == full-image downsampleBox');
}

console.log(`\nTraceEnvelope: ${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
