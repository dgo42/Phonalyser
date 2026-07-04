/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.gui.scope.MeasurementStats.
//
// Online avg / min / max / variance accumulator (Welford's method) used by the
// oscilloscope measurement table. Skips NaN inputs so missing-cycle samples
// don't poison the window stats.

/** Online Welford avg/min/max/sigma accumulator (NaN-skipping). */
export class MeasurementStats {

  constructor() {
    this._count = 0;
    this._mean = 0.0;
    this._m2 = 0.0;
    this._min = Number.POSITIVE_INFINITY;
    this._max = Number.NEGATIVE_INFINITY;
  }

  /** Folds one sample into the running stats; NaN inputs are ignored. */
  add(v) {
    if (Number.isNaN(v)) return;
    this._count++;
    const delta = v - this._mean;
    this._mean += delta / this._count;
    this._m2 += delta * (v - this._mean);
    if (v < this._min) this._min = v;
    if (v > this._max) this._max = v;
  }

  /** Running mean (the average), or NaN before any sample. */
  getMean() { return this._count > 0 ? this._mean : NaN; }

  /** Smallest sample seen, or NaN before any sample. */
  getMin() { return this._count > 0 ? this._min : NaN; }

  /** Largest sample seen, or NaN before any sample. */
  getMax() { return this._count > 0 ? this._max : NaN; }

  /** Sample standard deviation (Bessel-corrected), or NaN below two samples. */
  getSigma() { return this._count > 1 ? Math.sqrt(this._m2 / (this._count - 1)) : NaN; }
}
