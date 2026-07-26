/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.gui.scope.AmplitudeHistogram — the amplitude
// histogram's accumulator: geometry, ranging, aggregation. No DOM, no canvas; the plot lives in
// scope/histogram-view.js and the binning in the measurement path.
//
// The rules below were each a shipped-and-rejected defect on the Java bench, so they are
// requirements rather than preferences:
//
//   • RESOLUTION FOLLOWS THE SIGNAL, never full scale. Dividing ±full-scale into fixed bins gave a
//     2.5 mV bin on a 1.79 V-RMS card — six orders of magnitude coarser than the ~1.18 nV a 32-bit
//     converter resolves — so a millivolt signal occupied ~2 bins and the plot collapsed to two
//     bars. The range comes from the signal's own peak (fit()).
//   • ACCUMULATE FAR FINER THAN YOU DRAW. MICRO_PER_BAR micro-bins per displayed bar; the renderer
//     sums whole groups into bars, so a bar is never narrower than one micro-bin and the plot
//     cannot break into a comb of stripes with gaps.
//   • THE RANGE IS SYMMETRIC ABOUT ZERO. The samples are binned exactly as captured — no DC
//     removal — and the plot resolves the offset at paint time by centring on the distribution's
//     own mean (meanBin), so a DC offset changes what the axis is measured FROM, not which bin a
//     sample lands in.
//   • RE-RANGING CLEARS THE COUNTS. Counts gathered at a materially different level describe a
//     different signal; merging would present two populations as one distribution.

/** Micro-bins accumulated per displayed bar. */
export const MICRO_PER_BAR = 256;

/**
 * Narrowest permitted bin, in normalised units — one code of a 32-bit converter. Not a practical
 * resolution limit (the analog noise floor sits tens of dB above the LSB); it exists so a
 * dead-flat digital-loopback block cannot produce a zero-width range and a divide-by-zero.
 */
export const MIN_BIN_WIDTH = 2 ** -31;

/** Fewest displayed bars the geometry is defined for. */
const MIN_DISPLAY_BARS = 2;

export class AmplitudeHistogram {

  /**
   * @param {number} displayBars bars the plot intends to draw; the accumulator holds
   *        MICRO_PER_BAR micro-bins per bar. Clamped up to MIN_DISPLAY_BARS.
   */
  constructor(displayBars) {
    // THROWS on unusable geometry rather than clamping (Java: IllegalArgumentException). A caller
    // asking for one bar has a bug — a single bar is not a distribution — and silently widening it
    // would hide that behind a plot that looks plausible.
    const bars = Math.trunc(displayBars);
    if (!Number.isFinite(bars) || bars < MIN_DISPLAY_BARS) {
      throw new Error(`displayBars must be at least ${MIN_DISPLAY_BARS}, got ${displayBars}`);
    }
    /** @type {number} */
    this.microBins = bars * MICRO_PER_BAR;
    /** Counts per micro-bin. Float64 so a long unbounded collection cannot overflow an int32. */
    this._counts = new Float64Array(this.microBins);
    this._total = 0;
    this._maxCount = 0;
    this._ranged = false;
    this._binWidth = 0;
    this._rangeMin = 0;
    this._rangeMax = 0;
  }

  /** True once fit() has established a range. */
  get ranged() { return this._ranged; }
  /** Normalised value of the range's upper edge (lower edge is its negation). */
  get rangeMax() { return this._rangeMax; }
  /** Normalised value of the range's lower edge. */
  get rangeMin() { return this._rangeMin; }
  /** Width of one micro-bin, in normalised units. */
  get binWidth() { return this._binWidth; }

  /**
   * Ensures the range covers [min, max], SYMMETRICALLY about zero.
   *
   * Shrinking never re-ranges: a quietened signal keeps its counts and simply occupies fewer bins,
   * which is what lets the windowed peak tumble without costing the distribution.
   *
   * @param {number} min lowest sample to accommodate
   * @param {number} max highest sample to accommodate
   * @returns {boolean} true when the existing range fits and the counts were KEPT; false when the
   *          range was rebuilt and the counts cleared
   */
  fit(min, max) {
    const half = Math.max(Math.abs(min), Math.abs(max));
    // A non-finite bound cannot size anything: report "fits" and leave the range exactly as it was,
    // so one NaN in a block never restarts a healthy distribution.
    if (!Number.isFinite(half)) {
      return true;
    }
    if (this._ranged && half <= this._rangeMax) {
      return true;
    }
    // An eighth of the range at EACH end, so the signal occupies the middle six eighths. Headroom
    // IS the re-range tolerance: a tight pad (the first attempt used half a bar, ~1 % of span) is
    // far narrower than noise wanders, so the range escaped nearly every pass and every escape
    // restarted the distribution. It is free on screen because the renderer aggregates over
    // OCCUPIED bins only, so padding never becomes a bar.
    const headroom = Math.trunc(this.microBins / 8);
    const width = Math.max(MIN_BIN_WIDTH, (2 * half) / (this.microBins - 2 * headroom));
    this._clearCounts();
    this._binWidth = width;
    this._rangeMax = (this.microBins / 2) * width;
    this._rangeMin = -this._rangeMax;
    this._ranged = true;
    return false;
  }

  /**
   * Counts one sample, EXACTLY AS CAPTURED — no DC removal, no filtering. A value outside the
   * range clamps to the edge bin rather than being dropped, so the tails stay visible.
   *
   * @param {number} v normalised sample
   */
  add(v) {
    if (!Number.isFinite(v) || !this._ranged) {
      return;                                   // NaN / Infinity dropped; nothing to bin into yet
    }
    let index = Math.floor((v - this._rangeMin) / this._binWidth);
    if (index < 0) index = 0;
    else if (index >= this.microBins) index = this.microBins - 1;
    const next = this._counts[index] + 1;
    this._counts[index] = next;
    this._total++;
    if (next > this._maxCount) this._maxCount = next;
  }

  /** Clears the counts AND the range, so the next fit() re-establishes the geometry. */
  reset() {
    this._clearCounts();
    this._ranged = false;
    this._binWidth = 0;
    this._rangeMin = 0;
    this._rangeMax = 0;
  }

  /** @param {number} i micro-bin index @returns {number} its count (0 outside the range) */
  getCount(i) {
    return (i >= 0 && i < this.microBins) ? this._counts[i] : 0;
  }

  /** @returns {number} samples counted since the last clear. */
  getTotal() { return this._total; }

  /** @returns {number} the largest single micro-bin count. */
  getMaxCount() { return this._maxCount; }

  /** @param {number} i micro-bin index @returns {number} the normalised value at its lower edge. */
  binLowerEdge(i) {
    return this._rangeMin + i * this._binWidth;
  }

  /**
   * The normalised value at a FRACTIONAL bin position, so the renderer can ask for the value at a
   * bin boundary or centre without duplicating the geometry.
   *
   * @param {number} fractionalPos position in bin units
   * @returns {number} the normalised value there
   */
  binValue(fractionalPos) {
    return this._rangeMin + fractionalPos * this._binWidth;
  }

  /** @returns {number} lowest occupied micro-bin, or -1 when empty. */
  firstOccupied() {
    for (let i = 0; i < this.microBins; i++) {
      if (this._counts[i] > 0) return i;
    }
    return -1;
  }

  /** @returns {number} highest occupied micro-bin, or -1 when empty. */
  lastOccupied() {
    for (let i = this.microBins - 1; i >= 0; i--) {
      if (this._counts[i] > 0) return i;
    }
    return -1;
  }

  /**
   * The count-weighted centroid, in bin units — the distribution's own middle, which is what the
   * plot centres on so a DC offset relabels the axis instead of pushing the shape off-screen.
   *
   * @returns {number} the centroid in bin units, or -1 when empty
   */
  meanBin() {
    if (this._total <= 0) return -1;
    let weighted = 0;
    for (let i = 0; i < this.microBins; i++) {
      const c = this._counts[i];
      if (c > 0) weighted += c * (i + 0.5);
    }
    return weighted / this._total;
  }

  /**
   * Sums micro-bins [fromBin, toBinExclusive) into `outBins` buckets. Each source bin is added
   * WHOLE to the bucket its centre falls in, so the total is preserved exactly and no count is
   * split or double-counted.
   *
   * @param {number} fromBin first micro-bin (inclusive)
   * @param {number} toBinExclusive one past the last micro-bin
   * @param {number} outBins buckets to produce
   * @returns {Float64Array} the bucket sums, bucket 0 being the LOWEST voltage
   */
  aggregate(fromBin, toBinExclusive, outBins) {
    const buckets = Math.max(1, Math.trunc(outBins) || 1);
    const out = new Float64Array(buckets);
    const from = Math.max(0, Math.trunc(fromBin));
    const to = Math.min(this.microBins, Math.trunc(toBinExclusive));
    const span = to - from;
    if (span <= 0) return out;
    for (let i = from; i < to; i++) {
      const c = this._counts[i];
      if (c <= 0) continue;
      // The source bin's CENTRE decides its bucket — never its edge, which would let a bin
      // straddling a boundary be counted twice or not at all.
      let bucket = Math.floor(((i + 0.5 - from) / span) * buckets);
      if (bucket < 0) bucket = 0;
      else if (bucket >= buckets) bucket = buckets - 1;
      out[bucket] += c;
    }
    return out;
  }

  /**
   * A detached copy for the renderer: the same read API over a frozen set of counts, so a paint
   * cannot see the distribution change under it mid-frame (the binning runs on the measurement
   * cadence, the paint on the frame cadence).
   *
   * @returns {AmplitudeHistogram} an independent instance holding a copy of this state
   */
  snapshot() {
    const copy = new AmplitudeHistogram(this.microBins / MICRO_PER_BAR);
    copy._counts = this._counts.slice();
    copy._total = this._total;
    copy._maxCount = this._maxCount;
    copy._ranged = this._ranged;
    copy._binWidth = this._binWidth;
    copy._rangeMin = this._rangeMin;
    copy._rangeMax = this._rangeMax;
    return copy;
  }

  _clearCounts() {
    this._counts.fill(0);
    this._total = 0;
    this._maxCount = 0;
  }
}
