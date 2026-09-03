/*
 * Phonalyser web - PhosphorRenderer dirty-region + adaptive-scale contract test.
 * Run with `node web/test/phosphor-renderer.test.mjs`.
 *
 * Ports Java PhosphorRendererDirtyRegionTest (item c) and PhosphorRendererScaleTest:
 *
 *   - the dirty-region incremental flow (only the trace's content box repainted per
 *     frame) must be BYTE-IDENTICAL to the whole-buffer flow (the fullRepaint seam),
 *     across two consecutive different frames and after a trace MOVES vertical bands;
 *   - the coverage image is built at DEVICE resolution round(logical·pixelScale) and
 *     blitted into the LOGICAL draw rectangle (scale 1 ⇒ image dims == draw dims;
 *     scale 1.5 ⇒ image = round(logical·1.5), draw rect unchanged);
 *   - a plain sine rasterises to plausible coverage: non-zero inside the band, zero
 *     outside (item d).
 *
 * Driven through recording painter stubs (no canvas, no GL) - render() calls only
 * painter.getPixelScale() + painter.drawAlphaImage(), a null tint passes straight
 * through - exactly like the Java CapturePainter / RecordingPainter.
 */

import { PhosphorRenderer } from '../js/scope/phosphor-renderer.js';

let passed = 0, failed = 0;
function ok(cond, msg) { if (cond) { passed++; } else { failed++; console.error('FAIL:', msg); } }
function eq(a, b, msg) { ok(a === b, `${msg} (got ${a}, want ${b})`); }
function arrEq(a, b, msg) {
  let same = a.length === b.length;
  let firstDiff = -1;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) { same = false; firstDiff = i; break; }
  ok(same, same ? msg : `${msg} - first diff at ${firstDiff}: ${a[firstDiff]} vs ${b[firstDiff]}`);
}

/** Captures a COPY of the coverage buffer the renderer blits (first imgW*imgH bytes);
 *  getPixelScale is 1 (logical resolution). */
class CapturePainter {
  constructor() { this.alpha = null; this.imgW = 0; this.imgH = 0; }
  getPixelScale() { return 1; }
  drawAlphaImage(a, imgW, imgH /* , destX, destY, drawW, drawH, color */) {
    this.imgW = imgW; this.imgH = imgH;
    this.alpha = a.slice(0, imgW * imgH);
  }
}

/** Records the getPixelScale the renderer reads and the drawAlphaImage image / draw
 *  dimensions it emits. */
class RecordingPainter {
  constructor(scale) { this.scale = scale; this.imgW = 0; this.imgH = 0; this.drawW = 0; this.drawH = 0; this.calls = 0; }
  getPixelScale() { return this.scale; }
  drawAlphaImage(alpha, imgW, imgH, destX, destY, drawW, drawH /* , color */) {
    this.imgW = imgW; this.imgH = imgH; this.drawW = drawW; this.drawH = drawH; this.calls++;
  }
}

// ===== dirty-region byte-identical (item c) =====
const WIDTH = 120, HEIGHT = 80, DISP_COUNT = 480;
const CENTER_Y = HEIGHT / 2.0, V_SCALE = 8.0, LINE_WIDTH = 2;

function confinedTrace() {
  const d = new Float32Array(600);
  for (let i = 0; i < d.length; i++) d[i] = 0.35 * Math.sin(i * 0.21);
  return d;
}

function renderCapture(fullRepaint, frames, data) {
  const r = new PhosphorRenderer();
  r.fullRepaint = fullRepaint;
  const p = new CapturePainter();
  for (let f = 0; f < frames; f++) {
    r.render(p, data, data.length, 0, 0.0, DISP_COUNT, WIDTH, HEIGHT,
      CENTER_Y, V_SCALE, 0.0, LINE_WIDTH, null);
  }
  return p.alpha;
}

{
  const data = confinedTrace();
  const full   = renderCapture(true,  1, data);   // whole-buffer clear + full passes (reference)
  const dirty1 = renderCapture(false, 1, data);   // frame 1: whole clear (dims changed) + REGIONAL passes
  const dirty2 = renderCapture(false, 2, data);   // frame 2: INCREMENTAL clear + regional passes
  arrEq(dirty1, full, 'regional passes == full passes (frame 1)');
  arrEq(dirty2, full, 'incremental clear + regional passes == full passes (frame 2)');
}
{
  // Two traces in DIFFERENT vertical bands: rendering B after A on one renderer must
  // leave the SAME bytes as rendering B alone - A's dirty region was fully cleared.
  const a = new Float32Array(600), b = new Float32Array(600);
  for (let i = 0; i < a.length; i++) {
    a[i] = 0.30 * Math.sin(i * 0.21) + 0.9;   // upper band
    b[i] = 0.30 * Math.sin(i * 0.21) - 0.9;   // lower band
  }
  const moving = new PhosphorRenderer();
  const mp = new CapturePainter();
  moving.render(mp, a, a.length, 0, 0.0, DISP_COUNT, WIDTH, HEIGHT, CENTER_Y, V_SCALE, 0.0, LINE_WIDTH, null);
  moving.render(mp, b, b.length, 0, 0.0, DISP_COUNT, WIDTH, HEIGHT, CENTER_Y, V_SCALE, 0.0, LINE_WIDTH, null);
  const fresh = renderCapture(false, 1, b);
  arrEq(mp.alpha, fresh, 'the previous frame\'s region must be fully erased when the trace moves');
}

// ===== adaptive device resolution (PhosphorRendererScaleTest) =====
const WIDTH_PX = 100, HEIGHT_PX = 60, SCALE_DISP = 400;
function sine(n) { const d = new Float32Array(n); for (let i = 0; i < n; i++) d[i] = Math.sin(i * 0.3); return d; }
function renderScale(p) {
  const data = sine(500);
  new PhosphorRenderer().render(p, data, data.length, 0, 0.0, SCALE_DISP,
    WIDTH_PX, HEIGHT_PX, HEIGHT_PX / 2.0, 20.0, 0.0, 2, null);
}
{
  const p = new RecordingPainter(1.0);
  renderScale(p);
  eq(p.calls, 1, 'one blit per render');
  eq(p.imgW, WIDTH_PX, 'scale 1: image width == logical');
  eq(p.imgH, HEIGHT_PX, 'scale 1: image height == logical');
  eq(p.drawW, WIDTH_PX, 'scale 1: draw width == logical');
  eq(p.drawH, HEIGHT_PX, 'scale 1: draw height == logical');
  eq(p.imgW, p.drawW, 'scale 1: image dims == draw dims (w)');
  eq(p.imgH, p.drawH, 'scale 1: image dims == draw dims (h)');
}
{
  const p = new RecordingPainter(1.5);
  renderScale(p);
  eq(p.calls, 1, 'one blit per render (hidpi)');
  eq(p.imgW, Math.round(WIDTH_PX * 1.5), 'scale 1.5: image at device width (150)');
  eq(p.imgH, Math.round(HEIGHT_PX * 1.5), 'scale 1.5: image at device height (90)');
  eq(p.drawW, WIDTH_PX, 'scale 1.5: logical draw width unchanged');
  eq(p.drawH, HEIGHT_PX, 'scale 1.5: logical draw height unchanged');
}

// ===== item (d): a plain sine rasterises to plausible coverage =====
{
  const data = confinedTrace();
  const p = renderCapture(false, 1, data);   // scale 1 -> 120×80 coverage
  // The 0.35-amplitude sine at vScale 8 around centerY 40 lands on rows ~37..43.
  let bandSum = 0, bandMax = 0;
  for (let y = 36; y <= 44; y++) for (let x = 0; x < WIDTH; x++) {
    const v = p[y * WIDTH + x]; bandSum += v; if (v > bandMax) bandMax = v;
  }
  ok(bandSum > 0, 'sine coverage is non-zero inside the band');
  ok(bandMax === 255, 'the band core reaches full brightness');
  let outsideClean = true;
  for (let y = 0; y <= 20; y++) for (let x = 0; x < WIDTH; x++) if (p[y * WIDTH + x] !== 0) outsideClean = false;
  for (let y = 60; y < HEIGHT; y++) for (let x = 0; x < WIDTH; x++) if (p[y * WIDTH + x] !== 0) outsideClean = false;
  ok(outsideClean, 'coverage is zero well outside the band');
}

console.log(`\nPhosphorRenderer: ${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
