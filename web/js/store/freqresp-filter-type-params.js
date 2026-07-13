/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.preferences.FreqRespFilterTypeParams.
//
// Per-FilterType snapshot of every Frequency-Response filter parameter scalar
// plus the by-order/by-spec mode flag. Preferences keeps one per filter type in
// freqRespFilterParamsByType — the single source of truth for the filter scalars.
// fromType() gives pinned per-type defaults whose passband/stopband edges satisfy
// each type's edge-ordering semantics (LP stop>pass, HP stop<pass, BP SB>PB,
// NOTCH PB>SB), pinned at a 4:1 prototype stop ratio, so a fresh type always draws.

import { FilterType } from '../dsp/filter-types.js';

/** Per-FilterType Frequency-Response filter parameter scalars. */
export class FreqRespFilterTypeParams {
  constructor() {
    this.modeOrder     = false;
    this.rippleDb      = 1.0;
    this.stopAttenDb   = 40.0;
    this.centerHz      = 1000.0;
    this.passHz        = 1000.0;
    this.stopHz        = 2000.0;
    this.orderPassHz   = 1000.0;
    this.orderRippleDb = 1.0;
    this.order         = 4;
    this.q             = 1.0;
  }

  /** Pinned per-type defaults (only the edge triplet varies by type). */
  static fromType(type) {
    const p = new FreqRespFilterTypeParams();
    switch (type) {
      case FilterType.LOW_PASS:
        p.centerHz = 1000.0; p.passHz = 1000.0; p.stopHz = 4000.0; // stop > pass (4:1)
        break;
      case FilterType.HIGH_PASS:
        p.centerHz = 1000.0; p.passHz = 1000.0; p.stopHz = 250.0;  // stop < pass (4:1)
        break;
      case FilterType.BAND_PASS:
        p.centerHz = 1000.0; p.passHz = 500.0;  p.stopHz = 2000.0; // SB > PB (4:1)
        break;
      case FilterType.NOTCH:
        p.centerHz = 1000.0; p.passHz = 1000.0; p.stopHz = 250.0;  // PB > SB (4:1)
        break;
    }
    return p;
  }
}
