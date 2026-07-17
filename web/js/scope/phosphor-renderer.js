/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.gui.scope.PhosphorRenderer (HEAD), plus
// the web replacement for MeasurementPainter.drawAlphaImage + AlphaImageScratch
// (Canvas2DAlphaPainter below).
//
// Dense-trace digital-phosphor renderer (more than one sample per pixel): rasterises
// a displayed window as a DSO-style DIGITAL PHOSPHOR — a coverage image where each
// pixel's brightness is its dwell time — and blits it as one channel-colour-tinted
// image through a painter, replacing the vector polyline entirely.
//
// The pure signal math lives in trace-envelope.js (accumulate → pen → fringe →
// downsample); this class OWNS the pooled scratch that math streams through — the
// per-column span accumulator, the coverage buffers (device-res + supersampled), the
// exported band arrays, the count→alpha LUT — so the hot path allocates nothing.
//
// Adaptive device resolution: render() reads painter.getPixelScale() EVERY frame and
// rasterises at the surface's DEVICE resolution (round(widthPx·scale) ×
// round(heightPx·scale)), then blits that device-res buffer into the LOGICAL
// rectangle. At scale 1 every derived value collapses to the logical-resolution path
// and the output is byte-identical to rasterising at logical size. The web scope
// canvas backing store is at CSS/logical resolution (no devicePixelRatio scaling, no
// context transform), so pixelScale is 1 there; the full scale path is kept faithful
// for any future HiDPI backing.

import { columnCrossings, penRasterize, fringeDilate, downsampleBox } from './trace-envelope.js';

/** Digital-phosphor: alpha floor for a hit pixel (crossing count ≥ 1). 1.0 (the
 *  default) makes EVERY hit pixel full trace brightness, so the phosphor image matches
 *  the sparse anti-aliased sin x/x stroke across the spp == 1 boundary. A value < 1.0
 *  re-enables true phosphor grading (this floor plus the log knee up to
 *  {@link DPO_SATURATION_COUNT}); {@link phosphorLut} short-circuits the log when the
 *  floor is 1.0. Bench knob. */
const DPO_SINGLE_HIT_ALPHA = 1.0;
/** Digital-phosphor: dwell (crossing) count that saturates a pixel to full intensity;
 *  a count at or above it maps to alpha 255, so the LUT is this many entries long
 *  (+1 for count 0). Bench-tunable. */
const DPO_SATURATION_COUNT = 64;
/** 8-bit alpha for a fully saturated phosphor pixel. */
const DPO_ALPHA_MAX = 255;
/** Digital-phosphor supersampling factor: the histogram, band export and coverage pen
 *  are rasterised on a grid of 1/this DEVICE-px cells (2 = the maintainer's 0.5 px
 *  histogram) and box-averaged down, so every final pixel carries a true decimal
 *  coverage. 1 = rasterise at device resolution (no supersampling). Bench knob. */
const DPO_SUPERSAMPLE = 2;

export class PhosphorRenderer {

  constructor() {
    /** Per-column span DIFFERENCE accumulator (Int32Array, height+2), pooled and grown
     *  on demand. Only allocated under phosphor grading (counts). */
    this._phosphorDiff = null;
    /** Device-resolution coverage/alpha buffer the finished trace is blitted from. */
    this._phosphorAlpha = null;
    /** Supersampled sibling of _phosphorAlpha (ss > 1): the histogram/pen rasterise
     *  into this (ss·devW)×(ss·devH) grid, box-averaged down for the blit. */
    this._phosphorAlphaSs = null;
    /** Scratch for {@link fringeDilate}'s horizontal pass (same sub-cell dimensions). */
    this._phosphorFringe = null;
    /** Per-column band ends (UNROUNDED row space) exported by columnCrossings and
     *  consumed by penRasterize. Float32Array (Java float[]); a blank column is NaN. */
    this._phosphorBandTop = null;
    this._phosphorBandBot = null;
    this._phosphorBandXLo = null;
    this._phosphorBandXHi = null;
    this._phosphorBandEntryX = null;
    this._phosphorBandEntryY = null;
    this._phosphorBandExitX = null;
    this._phosphorBandExitY = null;
    /** Count→alpha LUT, built once by {@link phosphorLut}. */
    this._phosphorLut = null;

    // Previous frame's non-zero GRID-coordinate content box (inclusive; prevX1 < prevX0
    // when the last frame drew nothing). Cleared at the next frame's start so a steady
    // trace repaints only its own bounding box instead of the whole buffer.
    this._prevX0 = 0; this._prevX1 = -1; this._prevY0 = 0; this._prevY1 = 0;
    // Grid + device dimensions the pools were last sized for; any change forces a
    // whole-buffer clear (the packed stride / region coords are otherwise stale).
    this._prevWSs = -1; this._prevHSs = -1; this._prevDevW = -1; this._prevDevH = -1;
    /** Test seam: force a WHOLE-buffer repaint (full clear + whole-grid passes) instead
     *  of the incremental dirty region, so a test can assert the two flows are
     *  byte-identical. Production never sets it. */
    this.fullRepaint = false;
  }

  /**
   * Rasterises the whole displayed window as a DSO-style DIGITAL PHOSPHOR and blits it
   * as one channel-colour-tinted image, replacing the vector polyline. Faithful port
   * of PhosphorRenderer.render (Java :178-316).
   *
   * @param {{getPixelScale:()=>number, drawAlphaImage:Function}} painter
   * @param {Float32Array|number[]} data
   * @param {number} n
   * @param {number} dispStart
   * @param {number} subSampleOffset
   * @param {number} dispCount
   * @param {number} widthPx   LOGICAL plot width
   * @param {number} heightPx  LOGICAL plot height
   * @param {number} centerY
   * @param {number} vScale
   * @param {number} dcOffset
   * @param {number} lineWidth
   * @param {string|number|{r:number,g:number,b:number}|null} color  channel tint
   */
  render(painter, data, n, dispStart, subSampleOffset, dispCount,
         widthPx, heightPx, centerY, vScale, dcOffset, lineWidth, color) {
    if (widthPx <= 0 || heightPx <= 0) return;
    // Adaptive: read the surface's device scale EVERY frame and rasterise at DEVICE
    // resolution, then blit into the LOGICAL rect. At scale 1 all of the below
    // collapses to the logical-resolution path — byte-identical.
    const pixelScale = painter.getPixelScale();
    const devW = Math.max(1, Math.round(widthPx  * pixelScale));
    const devH = Math.max(1, Math.round(heightPx * pixelScale));
    const ss  = Math.max(1, DPO_SUPERSAMPLE);
    const wSs = devW * ss;
    const hSs = devH * ss;
    // Vertical value→row transform scale from LOGICAL px into the (devH·ss) grid rows.
    const sy = hSs / heightPx;
    // At the full-brightness floor (DPO_SINGLE_HIT_ALPHA ≥ 1.0) the count pass is a
    // no-op — the sink writes nothing and the pen alone draws — so we skip the ENTIRE
    // count pass and don't size/zero the diff accumulator.
    const counts = DPO_SINGLE_HIT_ALPHA < 1.0;
    if (counts && (this._phosphorDiff === null || this._phosphorDiff.length < hSs + 2)) {
      this._phosphorDiff = new Int32Array(hSs + 2);
    }
    if (this._phosphorBandTop === null || this._phosphorBandTop.length < wSs) {
      this._phosphorBandTop = new Float32Array(wSs);
      this._phosphorBandBot = new Float32Array(wSs);
      this._phosphorBandXLo = new Float32Array(wSs);
      this._phosphorBandXHi = new Float32Array(wSs);
      this._phosphorBandEntryX = new Float32Array(wSs);
      this._phosphorBandEntryY = new Float32Array(wSs);
      this._phosphorBandExitX = new Float32Array(wSs);
      this._phosphorBandExitY = new Float32Array(wSs);
    }
    const pixels = devW * devH;
    if (this._phosphorAlpha === null || this._phosphorAlpha.length < pixels) {
      this._phosphorAlpha = new Uint8Array(pixels);
    }
    let gridBuf;
    if (ss > 1) {
      const cells = wSs * hSs;
      if (this._phosphorAlphaSs === null || this._phosphorAlphaSs.length < cells) {
        this._phosphorAlphaSs = new Uint8Array(cells);
      }
      gridBuf = this._phosphorAlphaSs;
    } else {
      gridBuf = this._phosphorAlpha;   // no supersampling → the pen writes the device buffer directly
    }
    const alpha = this._phosphorAlpha;
    // DIRTY-REGION FRAME CLEAR. A raster-dimension change (or the full-repaint seam)
    // invalidates the stored box → whole-buffer fill; otherwise clear ONLY the previous
    // frame's content box (which held ALL of last frame's non-zero coverage).
    const dimsChanged = wSs !== this._prevWSs || hSs !== this._prevHSs
        || devW !== this._prevDevW || devH !== this._prevDevH;
    if (dimsChanged || this.fullRepaint) {
      alpha.fill(0, 0, pixels);
      if (ss > 1) gridBuf.fill(0, 0, wSs * hSs);
    } else if (this._prevX1 >= this._prevX0) {
      this._clearRegion(gridBuf, wSs, this._prevX0, this._prevX1, this._prevY0, this._prevY1);
      if (ss > 1) this._clearRegion(alpha, devW,
          Math.floor(this._prevX0 / ss), Math.floor(this._prevX1 / ss),
          Math.floor(this._prevY0 / ss), Math.floor(this._prevY1 / ss));
    }
    this._prevWSs = wSs; this._prevHSs = hSs; this._prevDevW = devW; this._prevDevH = devH;
    const grid = gridBuf;
    const lut  = this._lut();
    const lutMax = lut.length - 1;
    const w = wSs;
    // sin x/x rails ON: each column exports its band-limited crest/trough. At the
    // full-brightness floor (counts == false) the count pass is skipped entirely and the
    // sink is a no-op — the pen alone rasterises the trace; under phosphor grading the
    // sink packs counts→LUT and the pen lays its dimmer outline over it via max.
    const sink = counts
        ? (x, y, count) => { grid[y * w + x] = lut[Math.min(count, lutMax)]; }
        : () => { };
    columnCrossings(data, n, dispStart, dispCount, wSs, hSs,
        subSampleOffset, centerY * sy, vScale * sy, dcOffset, true, counts, this._phosphorDiff,
        this._phosphorBandTop, this._phosphorBandBot, this._phosphorBandXLo, this._phosphorBandXHi,
        this._phosphorBandEntryX, this._phosphorBandEntryY, this._phosphorBandExitX,
        this._phosphorBandExitY, sink);
    // Content bounding box from the exported bands: first/last non-NaN column and the
    // extreme band rows, grown by the pen's reach and the fringe radius.
    let colLo = wSs, colHi = -1;
    let rowMin = Number.POSITIVE_INFINITY, rowMax = Number.NEGATIVE_INFINITY;
    for (let x = 0; x < wSs; x++) {
      const top = this._phosphorBandTop[x];
      if (Number.isNaN(top)) continue;
      if (colLo === wSs) colLo = x;
      colHi = x;
      const bot = this._phosphorBandBot[x];
      if (top < rowMin) rowMin = top;
      if (bot < rowMin) rowMin = bot;
      if (top > rowMax) rowMax = top;
      if (bot > rowMax) rowMax = bot;
    }
    let penColLo, penColHi, rgX0, rgX1, rgY0, rgY1;
    if (this.fullRepaint) {                          // test seam → whole-grid passes
      penColLo = 0; penColHi = wSs - 1;
      rgX0 = 0; rgX1 = wSs - 1; rgY0 = 0; rgY1 = hSs - 1;
    } else if (colHi < colLo) {                       // blank frame → nothing to stroke
      penColLo = 0; penColHi = -1;
      rgX0 = 0; rgX1 = -1; rgY0 = 0; rgY1 = -1;
    } else {
      const penReach = Math.ceil(lineWidth * sy * 0.5) + 1;   // matches penRasterize's reach
      const margin   = penReach + (ss > 1 ? ss : 0);          // + the fringe radius
      penColLo = colLo; penColHi = colHi;
      rgX0 = Math.max(0, colLo - margin);
      rgX1 = Math.min(wSs - 1, colHi + margin);
      rgY0 = Math.max(0, Math.floor(rowMin) - margin);
      rgY1 = Math.min(hSs - 1, Math.ceil(rowMax) + margin);
    }
    if (penColHi >= penColLo) {
      const outlineAlpha = Math.round(DPO_SINGLE_HIT_ALPHA * DPO_ALPHA_MAX);
      penRasterize(this._phosphorBandTop, this._phosphorBandBot, this._phosphorBandXLo,
          this._phosphorBandXHi, this._phosphorBandEntryX, this._phosphorBandEntryY,
          this._phosphorBandExitX, this._phosphorBandExitY, wSs, hSs, lineWidth * sy,
          outlineAlpha, penColLo, penColHi, grid);
      if (ss > 1) {
        const cells = wSs * hSs;
        if (this._phosphorFringe === null || this._phosphorFringe.length < cells) {
          this._phosphorFringe = new Uint8Array(cells);
        }
        fringeDilate(grid, wSs, hSs, ss, this._phosphorFringe, rgX0, rgX1, rgY0, rgY1);
        downsampleBox(grid, wSs, ss, alpha, devW, devH,
            Math.floor(rgX0 / ss), Math.floor(rgX1 / ss),
            Math.floor(rgY0 / ss), Math.floor(rgY1 / ss));
      }
    }
    this._prevX0 = rgX0; this._prevX1 = rgX1; this._prevY0 = rgY0; this._prevY1 = rgY1;
    // Device-res coverage buffer (devW×devH) blitted into the LOGICAL widthPx×heightPx rect.
    painter.drawAlphaImage(alpha, devW, devH, 0, 0, widthPx, heightPx, color);
  }

  /** Zeroes the inclusive {@code [x0, x1] × [y0, y1]} box of a packed {@code stride}-wide
   *  byte buffer, one fill per row — the dirty-region frame clear. Callers guarantee
   *  {@code x0 ≤ x1} and {@code y0 ≤ y1}. */
  _clearRegion(buf, stride, x0, x1, y0, y1) {
    for (let y = y0; y <= y1; y++) {
      const base = y * stride;
      buf.fill(0, base + x0, base + x1 + 1);
    }
  }

  /** Lazily builds the count→alpha LUT once, indexed by {@code min(count,
   *  DPO_SATURATION_COUNT)}: {@code alpha(0) = 0}; else {@code DPO_SINGLE_HIT_ALPHA +
   *  (1 − DPO_SINGLE_HIT_ALPHA)·min(1, ln(1+count)/ln(1+DPO_SATURATION_COUNT))}, scaled
   *  to 8-bit. Precomputed so the hot loop never evaluates ln(). */
  _lut() {
    if (this._phosphorLut === null) {
      const lut = new Uint8Array(DPO_SATURATION_COUNT + 1);
      if (DPO_SINGLE_HIT_ALPHA >= 1.0) {
        // Floor at full brightness → every hit is full alpha; no log grading needed.
        lut.fill(Math.round(DPO_ALPHA_MAX), 1);
      } else {
        const denom = Math.log(1.0 + DPO_SATURATION_COUNT);
        for (let c = 1; c <= DPO_SATURATION_COUNT; c++) {
          const norm = Math.min(1.0, Math.log(1.0 + c) / denom);
          const a = DPO_SINGLE_HIT_ALPHA + (1.0 - DPO_SINGLE_HIT_ALPHA) * norm;
          lut[c] = Math.round(a * DPO_ALPHA_MAX);
        }
      }
      this._phosphorLut = lut;   // lut[0] left 0 → count 0 fully transparent
    }
    return this._phosphorLut;
  }
}

/**
 * Web replacement for MeasurementPainter.drawAlphaImage + AlphaImageScratch: adapts a
 * Canvas2D context to the painter interface {@link PhosphorRenderer#render} needs
 * (getPixelScale + drawAlphaImage). It owns a POOLED offscreen canvas + ImageData
 * (reallocated only when the device dimensions change — the AlphaImageScratch
 * discipline), so a steady-state frame churns nothing.
 *
 * <p>The coverage buffer carries only per-pixel alpha; drawAlphaImage builds an RGBA
 * image where every pixel gets the channel tint's RGB and the coverage byte as ALPHA,
 * putImageData()s it into the offscreen (putImageData REPLACES pixels — it must not go
 * straight to the scope canvas), then drawImage()s the offscreen into the destination
 * rect so it composites source-over like the desktop's alpha-image draw.
 */
export class Canvas2DAlphaPainter {

  constructor() {
    this._ctx = null;
    this._scale = 1;
    this._canvas = null;   // pooled offscreen
    this._offCtx = null;
    this._img = null;      // pooled ImageData
    this._w = 0;
    this._h = 0;
  }

  /** Points this painter at the destination context + device pixel scale for the frame
   *  about to be rendered (the scope reuses ONE painter across channels). */
  setTarget(ctx, pixelScale) {
    this._ctx = ctx;
    this._scale = pixelScale || 1;
  }

  getPixelScale() {
    return this._scale;
  }

  /** Blits the single-channel coverage buffer as one tinted image into the destination
   *  rect. {@code alpha} may be longer than {@code imgW*imgH} (a pooled buffer); only the
   *  first {@code imgW*imgH} bytes are read. */
  drawAlphaImage(alpha, imgW, imgH, destX, destY, drawW, drawH, color) {
    const ctx = this._ctx;
    if (!ctx || imgW <= 0 || imgH <= 0) return;
    if (this._canvas === null || this._w !== imgW || this._h !== imgH) {
      const c = makeOffscreen(imgW, imgH);
      this._canvas = c;
      this._offCtx = c ? c.getContext('2d') : null;
      this._img = this._offCtx ? this._offCtx.createImageData(imgW, imgH) : null;
      this._w = imgW;
      this._h = imgH;
    }
    if (this._offCtx === null || this._img === null) return;
    const { r, g, b } = parseTint(color);
    const out = this._img.data;   // Uint8ClampedArray, imgW*imgH*4
    const px = imgW * imgH;
    for (let i = 0, j = 0; i < px; i++, j += 4) {
      out[j] = r; out[j + 1] = g; out[j + 2] = b; out[j + 3] = alpha[i];
    }
    this._offCtx.putImageData(this._img, 0, 0);
    const prevAlpha = ctx.globalAlpha;
    ctx.globalAlpha = 1;
    ctx.drawImage(this._canvas, 0, 0, imgW, imgH, destX, destY, drawW, drawH);
    ctx.globalAlpha = prevAlpha;
  }
}

/** Allocates a pooled offscreen canvas: a DOM canvas where available (its drawImage
 *  source works everywhere), else an OffscreenCanvas, else null (no canvas host). */
function makeOffscreen(w, h) {
  if (typeof document !== 'undefined' && document.createElement) {
    const c = document.createElement('canvas');
    c.width = w; c.height = h;
    return c;
  }
  if (typeof OffscreenCanvas !== 'undefined') return new OffscreenCanvas(w, h);
  return null;
}

/** Parses a channel tint (hex string '#rgb'/'#rrggbb', 0xRRGGBB number, or {r,g,b}) to
 *  its 8-bit RGB components; a null tint (e.g. a test) reads white — the alpha buffer
 *  carries the shape. */
function parseTint(color) {
  if (color == null) return { r: 255, g: 255, b: 255 };
  if (typeof color === 'string') {
    let s = color.trim();
    if (s[0] === '#') s = s.slice(1);
    if (s.length === 3) s = s[0] + s[0] + s[1] + s[1] + s[2] + s[2];
    const num = parseInt(s, 16);
    return { r: (num >> 16) & 0xFF, g: (num >> 8) & 0xFF, b: num & 0xFF };
  }
  if (typeof color === 'number') {
    return { r: (color >> 16) & 0xFF, g: (color >> 8) & 0xFF, b: color & 0xFF };
  }
  return { r: color.r | 0, g: color.g | 0, b: color.b | 0 };
}
