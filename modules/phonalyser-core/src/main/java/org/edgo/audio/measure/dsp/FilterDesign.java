/*
 * Phonalyser - precision audio measurement workbench.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.edgo.audio.measure.dsp;

import lombok.Getter;
import org.edgo.audio.measure.enums.FilterResponse;
import org.edgo.audio.measure.enums.FilterType;

/**
 * Ideal analog-filter magnitude response, evaluated per point in dB, for the
 * "Filters" overlay of the Frequency Response pane.  This is a pure value
 * object: the two static factories resolve the design (family order + the
 * constants that define the magnitude-squared function) once, and
 * {@link #evalDb(double)} is a cheap per-pixel / per-data-point evaluation.
 * The special-function math (Chebyshev / Bessel / elliptic) and the Mode-1
 * order derivation live in the {@link FilterMath} utility namespace.
 *
 * <h2>What it computes</h2>
 * We only ever draw an amplitude response, so the whole design is done in the
 * magnitude-squared domain of the analog prototype - no pole/zero placement,
 * no bilinear transform.  Everything is built from a normalised low-pass
 * prototype |H<sub>LP</sub>(w)|² where {@code w} is the normalised radian
 * frequency (w = 1 at the prototype's reference edge), then mapped to the
 * requested band:
 *
 * <ul>
 *   <li><b>Low-pass</b>: w = f / Fc.</li>
 *   <li><b>High-pass</b>: w = Fc / f (the classic s -> 1/s prototype swap).</li>
 *   <li><b>Band-pass</b>: W = (f/f0 − f0/f)·(f0/B) - the standard
 *       low-pass-to-band-pass frequency transform (Q = f0/B).</li>
 *   <li><b>Notch (band-stop)</b>: W = 1 / [ (f/f0 − f0/f)·(f0/B) ] - the
 *       band-stop transform (reciprocal of the band-pass mapping).</li>
 * </ul>
 *
 * <h2>Prototype families (all magnitude-squared)</h2>
 * <ul>
 *   <li><b>Butterworth</b>: |H|² = 1 / (1 + w<sup>2n</sup>).  Maximally flat;
 *       −3.0103 dB at w = 1 by construction.</li>
 *   <li><b>Chebyshev&nbsp;I</b>: |H|² = 1 / (1 + ε²·T<sub>n</sub>²(w)) with the
 *       ripple factor ε² = 10<sup>R/10</sup> − 1.  Equiripple in the passband
 *       (peak-to-peak ripple = R dB), monotonic stopband.</li>
 *   <li><b>Inverse Chebyshev</b> (Chebyshev&nbsp;II): equiripple stopband,
 *       flat passband.  |H|² = ε²T<sub>n</sub>²(1/w) / (1 + ε²T<sub>n</sub>²(1/w))
 *       with ε² = 1 / (10<sup>A/10</sup> − 1); here w = 1 is the STOPBAND edge
 *       and the stopband floor sits at −A dB.</li>
 *   <li><b>Elliptic</b> (Cauer): |H|² = 1 / (1 + ε²·R<sub>n</sub>²(ξ, w)),
 *       R<sub>n</sub> the elliptic rational function ({@link FilterMath}).
 *       Equiripple in BOTH bands.</li>
 *   <li><b>Bessel</b> (Thomson): no closed-form magnitude.  From the reverse
 *       Bessel polynomial θ<sub>n</sub>(s), |H(jw)|² = θ<sub>n</sub>(0)² /
 *       |θ<sub>n</sub>(jw)|²; the prototype is frequency-scaled so the −3.0103 dB
 *       point lands at w = 1, matching the other families' convention.</li>
 * </ul>
 *
 * <h2>Order derivation (Mode&nbsp;1, {@link #ofSpec})</h2>
 * The minimum order meeting {@code stopAttenDb} at the stop edge comes from the
 * standard closed-form order formulas per family (Butterworth / Chebyshev /
 * Inverse Chebyshev via log ratios; Elliptic via the ratio of complete elliptic
 * integrals K - all in {@link FilterMath}).  Bessel has no such formula and no
 * ripple - its order is found by numerically searching for the lowest order
 * whose prototype reaches {@code stopAttenDb} at the stop edge, capped at
 * {@link FilterMath#MAX_ORDER}; Bessel is the only family allowed to fall short
 * of the spec (then it uses that cap).
 *
 * <p>All results are returned in dB with the passband at ≈ 0 dB.  The VIEW
 * aligns the curve to the measured response with
 * {@code anchorDb + evalDb(f) − evalDb(1000)}, so this class need not normalise
 * to 1 kHz itself.
 */
public final class FilterDesign {

    /** Passband reference level; every family is normalised so |H| ≈ 1 there. */
    private static final double PASSBAND_MAG_SQ = 1.0;
    private static final double MINUS_INF_DB = -1000.0;
    /** Default stopband attenuation used to pin an elliptic design's ξ when the
     *  user gives order + ripple but no explicit stop edge (Mode 2). */
    private static final double ELLIPTIC_ORDER_STOP_DB = 60.0;
    /** {@link #getStopAttenDb()} sentinel: this design carries no user stopband
     *  attenuation spec (Mode 2, design-by-order). */
    public static final double NO_STOP_ATTEN_SPEC = Double.NaN;

    /** Passband shape - read by the view's per-type curve alignment. */
    @Getter
    private final FilterType type;
    private final FilterResponse response;
    /** Resolved filter order (Mode 1 derives it; Mode 2 clamps the given one). */
    @Getter
    private final int order;

    // --- prototype constants (meaning depends on `response`) --------------
    private final double epsilonSq;   // ripple factor ε² (Cheb I / Inv Cheb / Elliptic)
    private final double xi;          // elliptic selectivity ξ = ws/wp (Elliptic only)
    private final double besselScale; // w-scale so Bessel −3 dB lands at w = 1
    /** Extra prototype-frequency scale applied to {@code w} after the band
     *  mapping.  1.0 for every family whose prototype reference edge (w = 1)
     *  is the passband edge.  Inverse Chebyshev's w = 1 is the STOPBAND edge,
     *  so Mode 1 sets this to 1/ws to slide the design onto the passband edge
     *  the user actually specified (Mode 2 has no ws and keeps 1.0). */
    private final double protoScale;

    // --- band mapping (center f0 and bandwidth B for BP / NOTCH) ----------
    /** LP/HP cutoff Fc, or BP/NOTCH center f0 - read by the view to place the
     *  per-type passband/stopband anchor region on the measured curve. */
    @Getter
    private final double fcHz;        // LP/HP cutoff, or BP/NOTCH center f0
    /** BP/NOTCH passband width B (= f0/Q); 0 for LP/HP.  Read by the view to
     *  size the BP passband / NOTCH stopband anchor region around {@link #fcHz}. */
    @Getter
    private final double bandwidthHz; // BP/NOTCH passband width B (= f0/Q)
    /** User-specified stopband attenuation A in dB (Mode 1, {@link #ofSpec}); the
     *  design's realistic stopband floor sits at −A.  {@link #NO_STOP_ATTEN_SPEC}
     *  (NaN) when built by order (Mode 2, {@link #ofOrder}), which carries no
     *  attenuation spec.  Read-only; no design math depends on it - the view uses
     *  it to render a monotone-family NOTCH null at its specified depth instead of
     *  the mathematically-unbounded −∞. */
    @Getter
    private final double stopAttenDb;

    private FilterDesign(FilterType type, FilterResponse response, int order,
                         double epsilonSq, double xi, double besselScale, double protoScale,
                         double fcHz, double bandwidthHz, double stopAttenDb) {
        this.type        = type;
        this.response    = response;
        this.order       = order;
        this.epsilonSq   = epsilonSq;
        this.xi          = xi;
        this.besselScale = besselScale;
        this.protoScale  = protoScale;
        this.fcHz        = fcHz;
        this.bandwidthHz = bandwidthHz;
        this.stopAttenDb = stopAttenDb;
    }

    // =====================================================================
    //  Factories
    // =====================================================================

    /**
     * Mode&nbsp;1 - design by specification: the order is derived as the
     * minimum meeting {@code stopAttenDb} at the stop edge.
     *
     * <p>LP/HP: {@code passHz} is the passband edge Fc, {@code stopHz} the
     * stopband edge Fs, {@code centerHz} ignored.  BP/NOTCH: {@code centerHz}
     * is the center f0, {@code passHz} the passband width PB, {@code stopHz}
     * the stopband width SB.
     *
     * @param rippleDb     passband ripple R in dB (Cheb I / Elliptic)
     * @param stopAttenDb  required stopband attenuation A in dB at the stop edge
     */
    public static FilterDesign ofSpec(FilterType type, FilterResponse response, double rippleDb, double stopAttenDb, double centerHz, double passHz, double stopHz) {   // static-ok: value-object factory (of-family); name fixed by the cross-agent API contract
        if (type == null || response == null) {
            throw new IllegalArgumentException("type/response must not be null");
        }
        if (stopAttenDb <= 0.0) {
            throw new IllegalArgumentException("stopAttenDb must be positive");
        }
        if (response.hasRipple() && rippleDb <= 0.0) {
            throw new IllegalArgumentException("rippleDb must be positive for equiripple families");
        }

        // Resolve the band mapping (fc, bandwidth) and the prototype stop-edge
        // ratio ws (= wStop / wPass, ws > 1) from the requested type.
        double fc;
        double bandwidth;
        double ws;
        if (type == FilterType.LOW_PASS) {
            if (!(passHz > 0.0 && stopHz > passHz)) {
                throw new IllegalArgumentException("LP requires 0 < passHz < stopHz");
            }
            fc = passHz; bandwidth = 0.0; ws = stopHz / passHz;
        } else if (type == FilterType.HIGH_PASS) {
            if (!(stopHz > 0.0 && passHz > stopHz)) {
                throw new IllegalArgumentException("HP requires 0 < stopHz < passHz");
            }
            fc = passHz; bandwidth = 0.0; ws = passHz / stopHz;   // prototype swaps s -> 1/s
        } else if (type == FilterType.BAND_PASS) {
            // Band-pass: PB is the INNER (narrow) passband, SB the OUTER (wide)
            // deep-rejection band => SB > PB, and the LP->BP transform maps the
            // outer SB edge to the higher prototype frequency ws = SB/PB.
            if (centerHz <= 0.0) {
                throw new IllegalArgumentException("BP requires centerHz > 0");
            }
            if (!(passHz > 0.0 && stopHz > passHz)) {
                throw new IllegalArgumentException("BP requires 0 < passHz(PB) < stopHz(SB)");
            }
            fc = centerHz; bandwidth = passHz; ws = stopHz / passHz;
        } else {
            // Notch (band-stop): the reciprocal band mapping inverts the roles -
            // PB is the OUTER (wide) passband-return band, SB the INNER (narrow)
            // deep-rejection band => PB > SB, and the stop ratio matching the
            // reciprocal transform is ws = PB/SB.
            if (centerHz <= 0.0) {
                throw new IllegalArgumentException("NOTCH requires centerHz > 0");
            }
            if (!(stopHz > 0.0 && passHz > stopHz)) {
                throw new IllegalArgumentException("NOTCH requires 0 < stopHz(SB) < passHz(PB)");
            }
            fc = centerHz; bandwidth = passHz; ws = passHz / stopHz;
        }

        double eps2 = FilterMath.rippleEpsilonSq(response, rippleDb, stopAttenDb);
        double xiVal = (response == FilterResponse.ELLIPTIC) ? ws : 0.0;
        int n = FilterMath.deriveOrder(response, rippleDb, stopAttenDb, ws);
        double bScale = (response == FilterResponse.BESSEL) ? FilterMath.besselHalfPowerScale(n) : 1.0;
        // Inverse Chebyshev's prototype reference edge (w = 1) is the STOPBAND
        // edge, not the passband edge every band type maps to.  Slide it onto
        // the passband edge the user specified by scaling w down by ws.
        double pScale = (response == FilterResponse.INV_CHEBYSHEV) ? 1.0 / ws : 1.0;
        return new FilterDesign(type, response, n, eps2, xiVal, bScale, pScale, fc, bandwidth, stopAttenDb);
    }

    /**
     * Mode&nbsp;2 - design by order.
     *
     * <p>LP/HP: {@code passFreqHz} is the cutoff, {@code q} ignored.  BP/NOTCH:
     * {@code passFreqHz} is the center f0 and the bandwidth is {@code center/q}.
     *
     * @param order     filter order (clamped to [1, {@link FilterMath#MAX_ORDER}])
     * @param rippleDb  passband ripple in dB (equiripple families only)
     */
    public static FilterDesign ofOrder(FilterType type, FilterResponse response, int order, double rippleDb, double passFreqHz, double q) {   // static-ok: value-object factory (of-family); name fixed by the cross-agent API contract
        if (type == null || response == null) {
            throw new IllegalArgumentException("type/response must not be null");
        }
        if (passFreqHz <= 0.0) {
            throw new IllegalArgumentException("passFreqHz must be positive");
        }
        if (response.hasRipple() && rippleDb <= 0.0) {
            throw new IllegalArgumentException("rippleDb must be positive for equiripple families");
        }

        int n = FilterMath.clampOrder(order);
        double fc;
        double bandwidth;
        if (type == FilterType.BAND_PASS || type == FilterType.NOTCH) {
            if (q <= 0.0) {
                throw new IllegalArgumentException("BP/NOTCH requires q > 0");
            }
            fc = passFreqHz; bandwidth = passFreqHz / q;
        } else {
            fc = passFreqHz; bandwidth = 0.0;
        }

        // Inverse Chebyshev / Elliptic need a ripple/selectivity spec even in
        // Mode 2.  Order mode supplies no stop edge, so we adopt each family's
        // natural convention: the Inverse-Chebyshev stopband ripple equals
        // `rippleDb`, and the elliptic selectivity ξ is derived from the
        // (order, ripple) pairing at a nominal stopband attenuation.
        double eps2 = FilterMath.rippleEpsilonSq(response, rippleDb, rippleDb);
        double xiVal = 0.0;
        if (response == FilterResponse.ELLIPTIC) {
            xiVal = FilterMath.ellipticSelectivityForOrder(n, rippleDb, ELLIPTIC_ORDER_STOP_DB);
        }
        double bScale = (response == FilterResponse.BESSEL) ? FilterMath.besselHalfPowerScale(n) : 1.0;
        // Mode 2 supplies no stop edge: Inverse Chebyshev's stopband floor sits
        // at the given cutoff (w = 1) by the family's natural order convention,
        // so no prototype-frequency rescale is applied here.  No attenuation spec
        // is carried, so the NOTCH display floor falls back to the view's default.
        return new FilterDesign(type, response, n, eps2, xiVal, bScale, 1.0, fc, bandwidth, NO_STOP_ATTEN_SPEC);
    }

    /**
     * The filter's critical frequencies in Hz - the points a resolution-limited
     * overlay sampler must hit exactly so the corner / null is never skipped by
     * pixel-grid luck.  BP/NOTCH return the center {@link #fcHz}
     * (the passband peak / stopband null); LP/HP return the pass-edge cutoff
     * {@link #fcHz}.  Read-only; no design math depends on this.
     */
    public double[] criticalFrequenciesHz() {
        return new double[] { fcHz };
    }

    /**
     * The anchor corners in Hz - the frequency(s) the view aligns the ideal
     * overlay to on the measured trace.  Each corner is a point
     * where the ideal magnitude is a finite, near-plateau skirt value, so the
     * measured curve there sits on its own passband plateau and the alignment
     * offset carries no stopband-depth bias.  Read-only; no design math depends
     * on this.
     *
     * <ul>
     *   <li><b>LP/HP</b>: the single pass-edge cutoff {@link #fcHz}.</li>
     *   <li><b>BAND_PASS</b>: the arithmetic passband edges
     *       {@code { fc − B/2, fc + B/2 }} (B = {@link #bandwidthHz}, the
     *       passband width) - the existing, verified BP corners, unchanged.</li>
     *   <li><b>NOTCH</b>: the passband SHOULDERS - the geometric edges of the
     *       outer passband-return band whose arithmetic width under the
     *       band-stop transform is {@link #bandwidthHz} (Mode 1: {@code passHz};
     *       Mode 2: {@code fc/Q}).  Solving {@code u − 1/u = B/f0} gives the
     *       log-symmetric pair {@code { f0/u, f0·u }}, where the ideal notch
     *       returns to its ≈−3 dB / −ripple skirt.  Anchoring there puts the
     *       ideal plateau on the measured plateau, so the clamped floor sits the
     *       user's attenuation A below it and the drawn depth tracks A.  (The
     *       arithmetic {@code fc ± B/2} would be log-asymmetric - one edge on the
     *       plateau, the other deep on the skirt - so the shoulders are the
     *       geometric edges, not the arithmetic midpoints.)</li>
     * </ul>
     */
    public double[] cornerFrequenciesHz() {
        if (type == FilterType.BAND_PASS) {
            return new double[] { fcHz - 0.5 * bandwidthHz, fcHz + 0.5 * bandwidthHz };
        }
        if (type == FilterType.NOTCH) {
            // Geometric edges of the band of arithmetic width `bandwidthHz`
            // centred (geometrically) on fcHz: u − 1/u = B/f0 =>
            // u = (x + √(x²+4))/2 with x = B/f0.  The shoulders are f0/u and f0·u.
            double x = bandwidthHz / fcHz;
            double u = (x + Math.sqrt(x * x + 4.0)) / 2.0;
            return new double[] { fcHz / u, fcHz * u };
        }
        return new double[] { fcHz };
    }

    // =====================================================================
    //  Per-point evaluation
    // =====================================================================

    /**
     * Magnitude of the ideal filter at {@code fHz} in dB (passband ≈ 0 dB).
     * Cheap enough to call once per pixel and once per measured data point.
     * Non-positive / non-finite frequencies clamp to a tiny positive value.
     */
    public double evalDb(double fHz) {
        double f = (fHz > 0.0 && Double.isFinite(fHz)) ? fHz : 1e-9;
        double w = normalisedFrequency(f);
        double magSq = prototypeMagSq(w);
        if (!(magSq > 0.0)) return MINUS_INF_DB;
        return 10.0 * Math.log10(magSq);
    }

    /**
     * Maps a real frequency to the low-pass prototype's normalised frequency
     * w (w = 1 at the prototype reference edge) for the configured band type,
     * then applies {@link #protoScale} (1.0 except for Mode-1 Inverse
     * Chebyshev, whose reference edge is the stopband - see the field doc).
     */
    private double normalisedFrequency(double f) {
        double w;
        switch (type) {
            case HIGH_PASS:
                w = fcHz / f;
                break;
            case BAND_PASS:
                w = Math.abs(bandTransform(f));
                break;
            case NOTCH: {
                double bt = bandTransform(f);
                w = (bt == 0.0) ? Double.POSITIVE_INFINITY   // at f0 -> deep stop
                                : Math.abs(1.0 / bt);
                break;
            }
            case LOW_PASS:
            default:
                w = f / fcHz;
                break;
        }
        return w * protoScale;
    }

    /** Standard LP->BP frequency mapping W = (f/f0 − f0/f)·(f0/B). */
    private double bandTransform(double f) {
        double ratio = f / fcHz - fcHz / f;
        return ratio * (fcHz / bandwidthHz);
    }

    /** Normalised low-pass prototype |H(w)|² for the configured family. */
    private double prototypeMagSq(double w) {
        double aw = Math.abs(w);
        switch (response) {
            case BUTTERWORTH:
                return butterworthMagSq(aw);
            case CHEBYSHEV:
                return chebyshevMagSq(aw);
            case INV_CHEBYSHEV:
                return invChebyshevMagSq(aw);
            case ELLIPTIC:
                return ellipticMagSq(aw);
            case BESSEL:
                return besselMagSq(aw);
            default:
                return butterworthMagSq(aw);
        }
    }

    /** |H|² = 1 / (1 + w^{2n}). */
    private double butterworthMagSq(double w) {
        double w2n = Math.pow(w, 2.0 * order);
        return PASSBAND_MAG_SQ / (1.0 + w2n);
    }

    /** |H|² = 1 / (1 + ε²·Tn²(w)); equiripple passband. */
    private double chebyshevMagSq(double w) {
        double tn = FilterMath.chebyshevT(order, w);
        return PASSBAND_MAG_SQ / (1.0 + epsilonSq * tn * tn);
    }

    /**
     * Inverse Chebyshev |H|² = ε²Tn²(1/w) / (1 + ε²Tn²(1/w)) with w = 1 the
     * stopband edge.  As w->0 (deep passband) Tn(1/w)->∞ so |H|²->1; at w = 1 the
     * floor equals ε²Tn²(1)/(1+...) = ε²/(1+ε²) = 10^{−A/10}.
     */
    private double invChebyshevMagSq(double w) {
        if (w <= 0.0) return PASSBAND_MAG_SQ;
        double tn = FilterMath.chebyshevT(order, 1.0 / w);
        double e2t2 = epsilonSq * tn * tn;
        if (!Double.isFinite(e2t2)) return PASSBAND_MAG_SQ;   // 1/w huge -> passband
        return e2t2 / (1.0 + e2t2);
    }

    /** |H|² = 1 / (1 + ε²·Rn²(ξ, w)) - elliptic rational function. */
    private double ellipticMagSq(double w) {
        double rn = FilterMath.ellipticRational(order, xi, w);
        return PASSBAND_MAG_SQ / (1.0 + epsilonSq * rn * rn);
    }

    /** Bessel |H(jw)|² = θn(0)² / |θn(j·s·w)|², s the −3 dB frequency scale. */
    private double besselMagSq(double w) {
        double[] c = FilterMath.besselCoeffs(order);
        return FilterMath.besselMagSqAt(c, order, besselScale * w);
    }
}
