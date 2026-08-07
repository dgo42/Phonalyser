/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.dsp.FilterDesign.
//
// Ideal analog-filter magnitude response, evaluated per point in dB, for the
// "Filters" overlay of the Frequency Response pane. Pure value object: the two
// static factories resolve the design (family order + the constants that define
// the magnitude-squared function) once, and evalDb() is a cheap per-pixel /
// per-data-point evaluation. The special-function math (Chebyshev / Bessel /
// elliptic) and the Mode-1 order derivation live in FilterMath.

import { FilterType, FilterResponse, hasRipple } from './filter-types.js';
import {
  rippleEpsilonSq,
  deriveOrder,
  besselHalfPowerScale,
  ellipticSelectivityForOrder,
  clampOrder,
  chebyshevT,
  ellipticRational,
  besselCoeffs,
  besselMagSqAt,
} from './filter-math.js';

/** Passband reference level; every family is normalised so |H| ≈ 1 there. */
const PASSBAND_MAG_SQ = 1.0;
const MINUS_INF_DB = -1000.0;
/** Default stopband attenuation used to pin an elliptic design's ξ when the
 *  user gives order + ripple but no explicit stop edge (Mode 2). */
const ELLIPTIC_ORDER_STOP_DB = 60.0;
/** getStopAttenDb() sentinel: this design carries no user stopband attenuation
 *  spec (Mode 2, design-by-order). */
export const NO_STOP_ATTEN_SPEC = NaN;

export class FilterDesign {

  /**
   * Internal raw constructor (the 10 fields). Use ofSpec() / ofOrder().
   */
  constructor(type, response, order,
              epsilonSq, xi, besselScale, protoScale,
              fcHz, bandwidthHz, stopAttenDb) {
    this._type        = type;
    this._response    = response;
    this._order       = order;
    this._epsilonSq   = epsilonSq;
    this._xi          = xi;
    this._besselScale = besselScale;
    this._protoScale  = protoScale;
    this._fcHz        = fcHz;
    this._bandwidthHz = bandwidthHz;
    this._stopAttenDb = stopAttenDb;
  }

  // ===================================================================
  //  Factories
  // ===================================================================

  /**
   * Mode 1 - design by specification: the order is derived as the minimum
   * meeting stopAttenDb at the stop edge.
   *
   * LP/HP: passHz is the passband edge Fc, stopHz the stopband edge Fs,
   * centerHz ignored. BP/NOTCH: centerHz is the center f0, passHz the passband
   * width PB, stopHz the stopband width SB.
   */
  static ofSpec(type, response, rippleDb, stopAttenDb, centerHz, passHz, stopHz) {
    if (type == null || response == null) {
      throw new Error('type/response must not be null');
    }
    if (stopAttenDb <= 0.0) {
      throw new Error('stopAttenDb must be positive');
    }
    if (hasRipple(response) && rippleDb <= 0.0) {
      throw new Error('rippleDb must be positive for equiripple families');
    }

    // Resolve the band mapping (fc, bandwidth) and the prototype stop-edge
    // ratio ws (= wStop / wPass, ws > 1) from the requested type.
    let fc;
    let bandwidth;
    let ws;
    if (type === FilterType.LOW_PASS) {
      if (!(passHz > 0.0 && stopHz > passHz)) {
        throw new Error('LP requires 0 < passHz < stopHz');
      }
      fc = passHz; bandwidth = 0.0; ws = stopHz / passHz;
    } else if (type === FilterType.HIGH_PASS) {
      if (!(stopHz > 0.0 && passHz > stopHz)) {
        throw new Error('HP requires 0 < stopHz < passHz');
      }
      fc = passHz; bandwidth = 0.0; ws = passHz / stopHz;   // prototype swaps s -> 1/s
    } else if (type === FilterType.BAND_PASS) {
      // Band-pass: PB is the INNER (narrow) passband, SB the OUTER (wide)
      // deep-rejection band => SB > PB, and the LP->BP transform maps the
      // outer SB edge to the higher prototype frequency ws = SB/PB.
      if (centerHz <= 0.0) {
        throw new Error('BP requires centerHz > 0');
      }
      if (!(passHz > 0.0 && stopHz > passHz)) {
        throw new Error('BP requires 0 < passHz(PB) < stopHz(SB)');
      }
      fc = centerHz; bandwidth = passHz; ws = stopHz / passHz;
    } else {
      // Notch (band-stop): the reciprocal band mapping inverts the roles -
      // PB is the OUTER (wide) passband-return band, SB the INNER (narrow)
      // deep-rejection band => PB > SB, and the stop ratio matching the
      // reciprocal transform is ws = PB/SB.
      if (centerHz <= 0.0) {
        throw new Error('NOTCH requires centerHz > 0');
      }
      if (!(stopHz > 0.0 && passHz > stopHz)) {
        throw new Error('NOTCH requires 0 < stopHz(SB) < passHz(PB)');
      }
      fc = centerHz; bandwidth = passHz; ws = passHz / stopHz;
    }

    const eps2 = rippleEpsilonSq(response, rippleDb, stopAttenDb);
    const xiVal = (response === FilterResponse.ELLIPTIC) ? ws : 0.0;
    const n = deriveOrder(response, rippleDb, stopAttenDb, ws);
    const bScale = (response === FilterResponse.BESSEL) ? besselHalfPowerScale(n) : 1.0;
    // Inverse Chebyshev's prototype reference edge (w = 1) is the STOPBAND
    // edge, not the passband edge every band type maps to. Slide it onto
    // the passband edge the user specified by scaling w down by ws.
    const pScale = (response === FilterResponse.INV_CHEBYSHEV) ? 1.0 / ws : 1.0;
    return new FilterDesign(type, response, n, eps2, xiVal, bScale, pScale, fc, bandwidth, stopAttenDb);
  }

  /**
   * Mode 2 - design by order.
   *
   * LP/HP: passFreqHz is the cutoff, q ignored. BP/NOTCH: passFreqHz is the
   * center f0 and the bandwidth is center/q.
   */
  static ofOrder(type, response, order, rippleDb, passFreqHz, q) {
    if (type == null || response == null) {
      throw new Error('type/response must not be null');
    }
    if (passFreqHz <= 0.0) {
      throw new Error('passFreqHz must be positive');
    }
    if (hasRipple(response) && rippleDb <= 0.0) {
      throw new Error('rippleDb must be positive for equiripple families');
    }

    const n = clampOrder(order);
    let fc;
    let bandwidth;
    if (type === FilterType.BAND_PASS || type === FilterType.NOTCH) {
      if (q <= 0.0) {
        throw new Error('BP/NOTCH requires q > 0');
      }
      fc = passFreqHz; bandwidth = passFreqHz / q;
    } else {
      fc = passFreqHz; bandwidth = 0.0;
    }

    // Inverse Chebyshev / Elliptic need a ripple/selectivity spec even in
    // Mode 2. Order mode supplies no stop edge, so we adopt each family's
    // natural convention: the Inverse-Chebyshev stopband ripple equals
    // `rippleDb`, and the elliptic selectivity ξ is derived from the
    // (order, ripple) pairing at a nominal stopband attenuation.
    const eps2 = rippleEpsilonSq(response, rippleDb, rippleDb);
    let xiVal = 0.0;
    if (response === FilterResponse.ELLIPTIC) {
      xiVal = ellipticSelectivityForOrder(n, rippleDb, ELLIPTIC_ORDER_STOP_DB);
    }
    const bScale = (response === FilterResponse.BESSEL) ? besselHalfPowerScale(n) : 1.0;
    // Mode 2 supplies no stop edge: Inverse Chebyshev's stopband floor sits
    // at the given cutoff (w = 1) by the family's natural order convention,
    // so no prototype-frequency rescale is applied here. No attenuation spec
    // is carried, so the NOTCH display floor falls back to the view's default.
    return new FilterDesign(type, response, n, eps2, xiVal, bScale, 1.0, fc, bandwidth, NO_STOP_ATTEN_SPEC);
  }

  // ===================================================================
  //  Getters
  // ===================================================================

  get type() { return this._type; }

  get order() { return this._order; }

  get fcHz() { return this._fcHz; }

  get bandwidthHz() { return this._bandwidthHz; }

  get stopAttenDb() { return this._stopAttenDb; }

  /**
   * The filter's critical frequencies in Hz - the points a resolution-limited
   * overlay sampler must hit exactly so the corner / null is never skipped by
   * pixel-grid luck. BP/NOTCH return the center fcHz (the passband peak /
   * stopband null); LP/HP return the pass-edge cutoff fcHz.
   */
  criticalFrequenciesHz() {
    return [this._fcHz];
  }

  /**
   * The anchor corners in Hz - the frequency(s) the view aligns the ideal
   * overlay to on the measured trace. LP/HP: the single pass-edge cutoff fcHz.
   * BAND_PASS: the arithmetic passband edges { fc − B/2, fc + B/2 }. NOTCH: the
   * passband SHOULDERS, the geometric edges { f0/u, f0·u } with
   * u = (x + √(x²+4))/2, x = B/f0.
   */
  cornerFrequenciesHz() {
    if (this._type === FilterType.BAND_PASS) {
      return [this._fcHz - 0.5 * this._bandwidthHz, this._fcHz + 0.5 * this._bandwidthHz];
    }
    if (this._type === FilterType.NOTCH) {
      // Geometric edges of the band of arithmetic width `bandwidthHz`
      // centred (geometrically) on fcHz: u − 1/u = B/f0 =>
      // u = (x + √(x²+4))/2 with x = B/f0. The shoulders are f0/u and f0·u.
      const x = this._bandwidthHz / this._fcHz;
      const u = (x + Math.sqrt(x * x + 4.0)) / 2.0;
      return [this._fcHz / u, this._fcHz * u];
    }
    return [this._fcHz];
  }

  // ===================================================================
  //  Per-point evaluation
  // ===================================================================

  /**
   * Magnitude of the ideal filter at fHz in dB (passband ≈ 0 dB). Cheap enough
   * to call once per pixel and once per measured data point. Non-positive /
   * non-finite frequencies clamp to a tiny positive value.
   */
  evalDb(fHz) {
    const f = (fHz > 0.0 && Number.isFinite(fHz)) ? fHz : 1e-9;
    const w = this.normalisedFrequency(f);
    const magSq = this.prototypeMagSq(w);
    if (!(magSq > 0.0)) return MINUS_INF_DB;
    return 10.0 * Math.log10(magSq);
  }

  /**
   * Maps a real frequency to the low-pass prototype's normalised frequency w
   * (w = 1 at the prototype reference edge) for the configured band type, then
   * applies protoScale (1.0 except for Mode-1 Inverse Chebyshev, whose
   * reference edge is the stopband).
   */
  normalisedFrequency(f) {
    let w;
    switch (this._type) {
      case FilterType.HIGH_PASS:
        w = this._fcHz / f;
        break;
      case FilterType.BAND_PASS:
        w = Math.abs(this.bandTransform(f));
        break;
      case FilterType.NOTCH: {
        const bt = this.bandTransform(f);
        w = (bt === 0.0) ? Infinity   // at f0 -> deep stop
                         : Math.abs(1.0 / bt);
        break;
      }
      case FilterType.LOW_PASS:
      default:
        w = f / this._fcHz;
        break;
    }
    return w * this._protoScale;
  }

  /** Standard LP->BP frequency mapping W = (f/f0 − f0/f)·(f0/B). */
  bandTransform(f) {
    const ratio = f / this._fcHz - this._fcHz / f;
    return ratio * (this._fcHz / this._bandwidthHz);
  }

  /** Normalised low-pass prototype |H(w)|² for the configured family. */
  prototypeMagSq(w) {
    const aw = Math.abs(w);
    switch (this._response) {
      case FilterResponse.BUTTERWORTH:
        return this.butterworthMagSq(aw);
      case FilterResponse.CHEBYSHEV:
        return this.chebyshevMagSq(aw);
      case FilterResponse.INV_CHEBYSHEV:
        return this.invChebyshevMagSq(aw);
      case FilterResponse.ELLIPTIC:
        return this.ellipticMagSq(aw);
      case FilterResponse.BESSEL:
        return this.besselMagSq(aw);
      default:
        return this.butterworthMagSq(aw);
    }
  }

  /** |H|² = 1 / (1 + w^{2n}). */
  butterworthMagSq(w) {
    const w2n = Math.pow(w, 2.0 * this._order);
    return PASSBAND_MAG_SQ / (1.0 + w2n);
  }

  /** |H|² = 1 / (1 + ε²·Tn²(w)); equiripple passband. */
  chebyshevMagSq(w) {
    const tn = chebyshevT(this._order, w);
    return PASSBAND_MAG_SQ / (1.0 + this._epsilonSq * tn * tn);
  }

  /**
   * Inverse Chebyshev |H|² = ε²Tn²(1/w) / (1 + ε²Tn²(1/w)) with w = 1 the
   * stopband edge. As w->0 (deep passband) Tn(1/w)->∞ so |H|²->1; at w = 1 the
   * floor equals ε²Tn²(1)/(1+...) = ε²/(1+ε²) = 10^{−A/10}.
   */
  invChebyshevMagSq(w) {
    if (w <= 0.0) return PASSBAND_MAG_SQ;
    const tn = chebyshevT(this._order, 1.0 / w);
    const e2t2 = this._epsilonSq * tn * tn;
    if (!Number.isFinite(e2t2)) return PASSBAND_MAG_SQ;   // 1/w huge -> passband
    return e2t2 / (1.0 + e2t2);
  }

  /** |H|² = 1 / (1 + ε²·Rn²(ξ, w)) - elliptic rational function. */
  ellipticMagSq(w) {
    const rn = ellipticRational(this._order, this._xi, w);
    return PASSBAND_MAG_SQ / (1.0 + this._epsilonSq * rn * rn);
  }

  /** Bessel |H(jw)|² = θn(0)² / |θn(j·s·w)|², s the −3 dB frequency scale. */
  besselMagSq(w) {
    const c = besselCoeffs(this._order);
    return besselMagSqAt(c, this._order, this._besselScale * w);
  }
}
