/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.gui.scope.HistogramView - the amplitude histogram's
// PLOT: its own view with its own palette, axes and paint. The scope contributes only the toolbar
// toggle; this class owns everything inside the window. (In Java a lambda drawing inside the scope
// view was rejected twice for exactly that reason.)
//
// The shape is the reading: sine -> bathtub (two peaks at the extremes), noise -> Gaussian bell,
// clipping -> a spike at the rail, one-sided clipping -> unequal bars about the centre.

import { formatVoltsSi, niceLinearMajors, formatCount } from '../ui/axis-format.js';

/** Plot margins (Java MARGIN_*): the left gutter holds the voltage tick labels, the top line the
 *  "V" caption, the bottom line the occupancy ticks. The gutter fits the longest label the voltage
 *  axis can produce - sign + four digits + point + SI prefix ("-12.5 µ"); at 52 px the leading
 *  digit and every minus sign were clipped. */
const MARGIN_LEFT = 68;
const MARGIN_RIGHT = 8;
const MARGIN_TOP = 16;
const MARGIN_BOTTOM = 22;
/** Target major-tick counts on the two auto-ranged axes (Java X_TICKS / Y_TICKS). */
const X_TICKS = 5;
const Y_TICKS = 8;
/** Perpendicular tick marks on the frame (Java MAJOR_TICK_LEN / MAJOR_TICK_WIDTH). */
const MAJOR_TICK_LEN = 6;
const MAJOR_TICK_WIDTH = 2;
const LABEL_FONT = '11px system-ui, sans-serif';
const LINE_H = 13;

export class HistogramView {

  /**
   * @param {HTMLCanvasElement} canvas the plot surface (the window sizes it)
   * @param {Object} deps
   * @param {() => ?Object} deps.snapshot   the selected channel's histogram snapshot, or null
   * @param {() => number} deps.peakVolts   that channel's full-scale peak volts, read at PAINT time
   * @param {() => number} deps.barCount    the oscHistogramBins preference
   * @param {() => Object} deps.palette     { background, grid, text, bar } scope colours
   */
  constructor(canvas, { snapshot, peakVolts, barCount, palette }) {
    this._canvas = canvas;
    this._snapshot = snapshot;
    this._peakVolts = peakVolts;
    this._barCount = barCount;
    this._palette = palette;
  }

  /** Repaints from the current snapshot. Safe to call on every frame; cheap when empty. */
  render() {
    const canvas = this._canvas;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    const W = canvas.width, H = canvas.height;
    const pal = this._palette();
    ctx.fillStyle = pal.background;
    ctx.fillRect(0, 0, W, H);

    const plotX = MARGIN_LEFT;
    const plotW = Math.max(1, W - MARGIN_LEFT - MARGIN_RIGHT);
    const plotY = MARGIN_TOP;
    const plotH = Math.max(1, H - MARGIN_TOP - MARGIN_BOTTOM);

    const h = this._snapshot();
    if (!h || h.getTotal() === 0 || h.firstOccupied() < 0) {
      // Nothing binned yet: an empty frame labelled with the converter's own range, and no
      // division by a zero maximum count.
      const peakV = this._peakVolts();
      this._drawGrid(ctx, pal, plotX, plotY, plotW, plotH, -peakV, peakV, 1);
      return;
    }

    const micro = h.microBins;
    const lo = h.firstOccupied(), hi = h.lastOccupied();
    // Centre on the distribution's OWN mean and window symmetrically about it: the samples were
    // binned exactly as captured, so a DC offset changes what the axis is measured FROM, not which
    // bin a sample landed in. Both halves are therefore always equally tall, and asymmetry shows as
    // unequal bar LENGTHS - which is what makes one-sided clipping visible.
    const centre = Math.round(h.meanBin());
    const maxReach = Math.min(centre, micro - centre);
    let reach = Math.max(centre - lo, hi + 1 - centre);
    reach = Math.max(1, Math.min(reach, maxReach));
    let from = centre - reach, to = centre + reach;

    let bars = this._aggregate(h, from, to);
    // Crop one-pixel tails and re-aggregate: rows of 1-px stubs squeeze the readable part of the
    // distribution into a fraction of the plot.
    const cropped = this._cropTails(h, bars, from, to, centre, plotW);
    from = cropped.from; to = cropped.to; bars = cropped.bars;

    const zeroRef = h.binValue(centre);
    const peak = this._peakVolts();
    const loLabel = (h.binLowerEdge(from) - zeroRef) * peak;
    const hiLabel = (h.binLowerEdge(to) - zeroRef) * peak;

    let maxCount = 0;
    for (const c of bars) if (c > maxCount) maxCount = c;
    this._drawGrid(ctx, pal, plotX, plotY, plotW, plotH, loLabel, hiLabel, Math.max(1, maxCount));

    if (maxCount <= 0) return;
    // Bar 0 holds the LOWEST voltage, so it goes at the BOTTOM (screen y grows down).
    ctx.fillStyle = pal.bar;
    for (let i = 0; i < bars.length; i++) {
      if (bars[i] === 0) continue;
      const yTop = plotY + Math.round((bars.length - 1 - i) * plotH / bars.length);
      const yBot = plotY + Math.round((bars.length - i) * plotH / bars.length);
      const barW = Math.round(bars[i] / maxCount * plotW);
      ctx.fillRect(plotX + 1, yTop, Math.max(1, barW - 1), Math.max(1, yBot - yTop - 1));
    }
  }

  /** Aggregates to the preferred bar count, clamped to the span and forced EVEN so the centre
   *  falls on a bar boundary rather than through the middle of a bar. */
  _aggregate(h, from, to) {
    const span = to - from;
    let count = Math.max(2, Math.min(Math.trunc(this._barCount()) || 50, span));
    if (count % 2 !== 0) count -= 1;
    return h.aggregate(from, to, Math.max(2, count));
  }

  /**
   * Finds the furthest bar that draws wider than a single pixel, keeps ONE bar beyond it so the
   * tail is not cut flush, narrows the window symmetrically about `centre`, and re-aggregates.
   *
   * The width test is exact (`count * plotWidth > maxCount`) rather than rounded: a rounded test
   * disagrees with what the painter actually draws at the boundary.
   */
  _cropTails(h, bars, from, to, centre, plotW) {
    let maxCount = 0;
    for (const c of bars) if (c > maxCount) maxCount = c;
    if (maxCount <= 0) return { from, to, bars };
    let furthest = -1;
    for (let i = 0; i < bars.length; i++) {
      const mid = (bars.length - 1) / 2;
      if (bars[i] * plotW > maxCount) {
        const dist = Math.abs(i - mid);
        if (dist > furthest) furthest = dist;
      }
    }
    if (furthest < 0) return { from, to, bars };
    const binsPerBar = (to - from) / bars.length;
    const keep = Math.ceil((furthest + 1) * binsPerBar);   // one bar beyond the furthest wide bar
    const reach = Math.max(1, Math.min(keep, Math.min(centre, h.microBins - centre)));
    const nf = centre - reach, nt = centre + reach;
    if (nt - nf >= to - from) return { from, to, bars };    // nothing to crop
    return { from: nf, to: nt, bars: this._aggregate(h, nf, nt) };
  }

  /**
   * The two axes (Java HistogramView.drawHistogramGrid -> AbstractMeasurementView.drawGrid):
   * occupancy along the bottom, voltage up the left, each nice-number LINEAR over its own
   * auto-ranged span - neither axis is ever logarithmic here, unlike the FFT's.
   *
   * Grid lines at the majors, a frame in the axis colour, outward major tick marks, tick labels
   * outside the plot (counts below, volts in the left gutter), and the "V" caption in the top
   * margin over an over-painted background so it stays legible against the topmost label.
   */
  _drawGrid(ctx, pal, x, y, w, h, vLo, vHi, maxCount) {
    const xMajors = niceLinearMajors(0, maxCount, X_TICKS);
    const yMajors = niceLinearMajors(vLo, vHi, Y_TICKS);
    const xPos = (v) => x + Math.round((maxCount <= 0 ? 0 : v / maxCount) * w);
    const yPos = (v) => y + Math.round((1 - (v - vLo) / Math.max(1e-30, vHi - vLo)) * h);

    ctx.lineWidth = 1;
    ctx.strokeStyle = pal.grid;
    ctx.beginPath();
    for (const v of xMajors) { const px = xPos(v) + 0.5; ctx.moveTo(px, y); ctx.lineTo(px, y + h); }
    for (const v of yMajors) { const py = yPos(v) + 0.5; ctx.moveTo(x, py); ctx.lineTo(x + w, py); }
    ctx.stroke();

    ctx.strokeStyle = pal.axis;
    ctx.strokeRect(x + 0.5, y + 0.5, w, h);

    // Outward major tick marks on the bottom and left edges.
    ctx.lineWidth = MAJOR_TICK_WIDTH;
    ctx.beginPath();
    for (const v of xMajors) { const px = xPos(v) + 0.5; ctx.moveTo(px, y + h); ctx.lineTo(px, y + h + MAJOR_TICK_LEN); }
    for (const v of yMajors) { const py = yPos(v) + 0.5; ctx.moveTo(x - MAJOR_TICK_LEN, py); ctx.lineTo(x, py); }
    ctx.stroke();
    ctx.lineWidth = 1;

    ctx.font = LABEL_FONT;
    ctx.fillStyle = pal.text;
    ctx.textBaseline = 'top';
    ctx.textAlign = 'center';
    for (const v of xMajors) ctx.fillText(formatCount(v), xPos(v), y + h + MAJOR_TICK_LEN + 2);

    // Voltage labels down the gutter, skipping any that would collide with the last one drawn.
    ctx.textAlign = 'right';
    ctx.textBaseline = 'middle';
    let lastPy = null;
    for (const v of yMajors) {
      const py = yPos(v);
      if (lastPy !== null && Math.abs(py - lastPy) < LINE_H) continue;
      ctx.fillText(formatVoltsSi(v), x - MAJOR_TICK_LEN - 4, py);
      lastPy = py;
    }

    // The "V" caption sits in the top margin and deliberately over-paints the topmost tick label
    // (Java: "overpaint a small background rectangle on top of the topmost tick label so the
    // caption stays legible"). The band covers the label's FULL height - a label centred on the
    // plot's top edge reaches half a line below it, and a band that stopped at the edge left the
    // lower half of the digits showing through under the caption.
    const capTy = (y >= LINE_H) ? y - LINE_H : y + 2;
    ctx.fillStyle = pal.background;
    ctx.fillRect(0, capTy - 1, x - MAJOR_TICK_LEN - 2, (y + LINE_H / 2 + 1) - (capTy - 1));
    ctx.fillStyle = pal.text;
    ctx.textBaseline = 'top';
    ctx.fillText('V', x - MAJOR_TICK_LEN - 4, capTy);
  }
}
