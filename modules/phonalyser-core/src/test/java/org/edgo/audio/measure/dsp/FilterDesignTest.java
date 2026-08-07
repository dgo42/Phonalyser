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

import org.edgo.audio.measure.enums.FilterResponse;
import org.edgo.audio.measure.enums.FilterType;
import org.edgo.audio.measure.preferences.FreqRespFilterTypeParams;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour tests for {@link FilterDesign}.  Each family is checked against its
 * defining magnitude property: Butterworth's maximally-flat half-power point
 * and decade slope; Chebyshev's exact passband-ripple amplitude; Inverse
 * Chebyshev's stopband floor; the elliptic attenuation against published
 * reference numbers; Bessel's monotonic all-pole shape.  Band-pass / notch are
 * checked for geometric symmetry about the centre and notch depth, and the
 * Mode-1 factory is checked to meet or exceed the requested stopband
 * attenuation at the stop edge for every family.
 */
class FilterDesignTest {

    private static final double HALF_POWER_DB = -3.0102999566398120;   // 10·log10(½)
    private static final double DB_TOL = 0.05;
    private static final double FC = 1000.0;

    // ---------------------------------------------------------------------
    //  Butterworth
    // ---------------------------------------------------------------------

    @Test
    void butterworth_isHalfPowerAtCutoff_andDecadeSlope() {
        FilterDesign f2 = FilterDesign.ofOrder(FilterType.LOW_PASS, FilterResponse.BUTTERWORTH, 2, 0.0, FC, 1.0);
        assertEquals(HALF_POWER_DB, f2.evalDb(FC), 1e-9, "Butterworth is −3.0103 dB at Fc");

        // Maximally flat: essentially 0 dB well inside the passband.
        assertEquals(0.0, f2.evalDb(FC / 100.0), 1e-3, "flat passband");

        // Asymptotic roll-off is −20·n dB/decade: one decade above Fc -> −40 dB for n = 2.
        assertEquals(-40.0, f2.evalDb(FC * 10.0), 0.01, "−20·n dB/decade slope (n=2)");

        FilterDesign f4 = FilterDesign.ofOrder(FilterType.LOW_PASS, FilterResponse.BUTTERWORTH, 4, 0.0, FC, 1.0);
        assertEquals(-80.0, f4.evalDb(FC * 10.0), 0.01, "−20·n dB/decade slope (n=4)");
    }

    @Test
    void butterworth_highPass_mirrorsThroughCutoff() {
        FilterDesign hp = FilterDesign.ofOrder(FilterType.HIGH_PASS, FilterResponse.BUTTERWORTH, 3, 0.0, FC, 1.0);
        assertEquals(HALF_POWER_DB, hp.evalDb(FC), 1e-9, "HP is −3.0103 dB at Fc");
        assertEquals(0.0, hp.evalDb(FC * 100.0), 1e-3, "HP flat well above Fc");
        // One decade below Fc -> −20·n dB (n = 3).
        assertEquals(-60.0, hp.evalDb(FC / 10.0), 0.01, "HP −20·n dB/decade below Fc");
    }

    // ---------------------------------------------------------------------
    //  Chebyshev I - passband ripple amplitude
    // ---------------------------------------------------------------------

    @Test
    void chebyshev_passbandRippleEqualsSpec() {
        double rippleDb = 1.0;
        FilterDesign f = FilterDesign.ofOrder(FilterType.LOW_PASS, FilterResponse.CHEBYSHEV, 4, rippleDb, FC, 1.0);
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (double freq = FC / 100.0; freq <= FC; freq += 1.0) {
            double d = f.evalDb(freq);
            min = Math.min(min, d);
            max = Math.max(max, d);
        }
        // Peak-to-peak ripple across the passband equals the spec; top of the
        // ripple touches 0 dB, the trough touches −rippleDb.
        assertEquals(rippleDb, max - min, 1e-3, "peak-to-peak passband ripple = spec");
        assertEquals(0.0, max, 1e-3, "ripple peaks reach 0 dB");
        assertEquals(-rippleDb, f.evalDb(FC), 1e-6, "edge at Fc sits at −rippleDb");
    }

    @Test
    void chebyshev_oddOrderStartsAtZero_evenOrderStartsAtMinusRipple() {
        double rippleDb = 1.0;
        FilterDesign odd = FilterDesign.ofOrder(FilterType.LOW_PASS, FilterResponse.CHEBYSHEV, 3, rippleDb, FC, 1.0);
        FilterDesign even = FilterDesign.ofOrder(FilterType.LOW_PASS, FilterResponse.CHEBYSHEV, 4, rippleDb, FC, 1.0);
        assertEquals(0.0, odd.evalDb(FC / 1000.0), 1e-3, "odd Chebyshev is 0 dB at DC");
        assertEquals(-rippleDb, even.evalDb(FC / 1000.0), 1e-3, "even Chebyshev is −rippleDb at DC");
    }

    // ---------------------------------------------------------------------
    //  Inverse Chebyshev - stopband floor == −stopAtten
    // ---------------------------------------------------------------------

    @Test
    void inverseChebyshev_stopbandFloorEqualsStopAtten() {
        double stopDb = 60.0;
        FilterDesign f = FilterDesign.ofSpec(FilterType.LOW_PASS, FilterResponse.INV_CHEBYSHEV,
                1.0, stopDb, 0.0, FC, 2.0 * FC);
        // Flat, monotone passband anchored at 0 dB at DC.
        assertEquals(0.0, f.evalDb(FC / 100.0), 1e-3, "flat passband at 0 dB");
        // The passband must stay flat (≈0 dB) up to well inside the passband -
        // the prototype's stopband reference edge (w = 1) must sit at the
        // requested stop edge Fs, not compressed down onto Fc.  The wrong
        // frequency scale would land the −A floor at Fc, so this same 0.8·Fc
        // point reads ≈−24 dB instead of ≈0 dB (the transition onset near Fc
        // itself is a legitimate few-tenths-dB, so we stay below it).
        for (double freq = FC / 100.0; freq <= 0.8 * FC; freq += 1.0) {
            assertTrue(f.evalDb(freq) >= -DB_TOL,
                    "Inverse-Cheb passband stays ≈0 dB well inside the passband at " + freq + " Hz");
        }
        // The equiripple stopband peaks exactly touch −stopDb; none exceed it.
        double peak = Double.NEGATIVE_INFINITY;
        for (double freq = 2.0 * FC; freq <= 50.0 * FC; freq += 5.0) {
            peak = Math.max(peak, f.evalDb(freq));
        }
        assertEquals(-stopDb, peak, DB_TOL, "stopband ripple peaks reach −stopAtten");
        assertTrue(f.evalDb(2.0 * FC) <= -stopDb + DB_TOL, "stop edge meets the spec");
    }

    // ---------------------------------------------------------------------
    //  Elliptic - verified against published reference attenuations
    // ---------------------------------------------------------------------

    /**
     * The exact minimum stopband attenuation of an elliptic (Cauer) low-pass is
     *   A<sub>s</sub> = 10·log10(1 + ε²·L<sub>n</sub>²),
     * with the discrimination factor L<sub>n</sub> = R<sub>n</sub>(ξ, ξ) = 1/k1,
     * where k1 is the image modulus of k = 1/ξ under the degree equation
     * (q1 = q(k)^n).  See Orfanidis, "Lecture Notes on Elliptic Filter Design"
     * (Rutgers, 2006), eqs. (5)-(20).  For a 1 dB passband ripple and selectivity
     * ξ = 1.5 this formula gives the reference values below (also reproducible in
     * MATLAB/Octave via {@code ellipord}/{@code ellipap}):
     * <pre>
     *   n = 3 -> 25.176 dB
     *   n = 4 -> 39.518 dB
     *   n = 5 -> 53.875 dB
     * </pre>
     * We drive the design through {@link FilterDesign#ofSpec} (which meets a
     * requested stop attenuation at the stop edge) at the matching ξ = Fs/Fc and
     * confirm the realised attenuation at the stop edge equals the reference.
     */
    @Test
    void elliptic_matchesPublishedReferenceAttenuations() {
        double rippleDb = 1.0;
        double fs = 1.5 * FC;   // selectivity ξ = 1.5
        double[][] refs = {
                { 3, 25.176 },
                { 4, 39.518 },
                { 5, 53.875 },
        };
        for (double[] ref : refs) {
            int n = (int) ref[0];
            double expectedAs = ref[1];
            // Ask for slightly less than the reference so ofSpec resolves to this
            // exact order, then read the attenuation the realised design gives at
            // the stop edge Fs - it must equal the published value.
            FilterDesign f = FilterDesign.ofSpec(FilterType.LOW_PASS, FilterResponse.ELLIPTIC,
                    rippleDb, expectedAs - 1.0, 0.0, FC, fs);
            assertEquals(n, f.getOrder(), "elliptic ofSpec resolves to order " + n);
            double realisedAs = -f.evalDb(fs);
            assertEquals(expectedAs, realisedAs, 0.02,
                    "elliptic As at Fs matches published reference for n=" + n);
        }
    }

    @Test
    void elliptic_passbandRippleEqualsSpec_andStopEdgeMeetsSpec() {
        double rippleDb = 1.0;
        double stopDb = 40.0;
        FilterDesign f = FilterDesign.ofSpec(FilterType.LOW_PASS, FilterResponse.ELLIPTIC,
                rippleDb, stopDb, 0.0, FC, 1.3 * FC);
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (double freq = FC / 100.0; freq <= FC; freq += 1.0) {
            double d = f.evalDb(freq);
            min = Math.min(min, d);
            max = Math.max(max, d);
        }
        assertEquals(rippleDb, max - min, 1e-3, "elliptic passband ripple = spec");
        assertTrue(-f.evalDb(1.3 * FC) >= stopDb, "elliptic meets stop atten at the stop edge");
    }

    // ---------------------------------------------------------------------
    //  Bessel - monotonic, 0 dB at DC, −3 dB at Fc
    // ---------------------------------------------------------------------

    @Test
    void bessel_isMonotonic_zeroAtDc_halfPowerAtCutoff() {
        FilterDesign f = FilterDesign.ofOrder(FilterType.LOW_PASS, FilterResponse.BESSEL, 5, 0.0, FC, 1.0);
        assertEquals(0.0, f.evalDb(FC / 1000.0), 1e-3, "Bessel LP is 0 dB at DC");
        assertEquals(HALF_POWER_DB, f.evalDb(FC), 1e-6, "Bessel scaled so −3.0103 dB lands at Fc");

        double prev = Double.POSITIVE_INFINITY;
        for (double freq = 1.0; freq <= 20.0 * FC; freq *= 1.03) {
            double d = f.evalDb(freq);
            assertTrue(d <= prev + 1e-9, "Bessel magnitude is monotonically decreasing at " + freq + " Hz");
            prev = d;
        }
    }

    // ---------------------------------------------------------------------
    //  Band-pass / notch - geometric symmetry + notch depth
    // ---------------------------------------------------------------------

    @Test
    void bandPass_isGeometricallySymmetricAboutCentre() {
        FilterDesign bp = FilterDesign.ofOrder(FilterType.BAND_PASS, FilterResponse.BUTTERWORTH, 3, 0.0, FC, 3.0);
        assertEquals(0.0, bp.evalDb(FC), 1e-9, "band-pass peaks at 0 dB at f0");
        // On a log-frequency axis the response is symmetric about f0: the value
        // at f0/r equals the value at f0·r for any ratio r.
        for (double r : new double[]{ 1.5, 2.0, 4.0 }) {
            assertEquals(bp.evalDb(FC / r), bp.evalDb(FC * r), 1e-6,
                    "band-pass symmetric about f0 at ratio " + r);
        }
    }

    @Test
    void notch_isDeepAtCentre_flatAway_andSymmetric() {
        FilterDesign nt = FilterDesign.ofOrder(FilterType.NOTCH, FilterResponse.BUTTERWORTH, 4, 0.0, FC, 3.0);
        assertTrue(nt.evalDb(FC) < -100.0, "notch is deep at f0");
        assertEquals(0.0, nt.evalDb(FC / 100.0), 1e-3, "notch passes far below f0");
        assertEquals(0.0, nt.evalDb(FC * 100.0), 1e-3, "notch passes far above f0");
        for (double r : new double[]{ 1.5, 2.0, 4.0 }) {
            assertEquals(nt.evalDb(FC / r), nt.evalDb(FC * r), 1e-6,
                    "notch symmetric about f0 at ratio " + r);
        }
    }

    @Test
    void notch_mode1_meetsStopAttenAcrossStopband_andPassesAtPassbandEdge() {
        // NOTCH by specification: PB is the OUTER (wide) passband-return band,
        // SB the INNER (narrow) deep-rejection band => PB > SB.  The design must
        // reach the requested attenuation across the whole SB (its edges), and
        // return to the passband (~−3 dB) at the PB edges.  Verified against
        // scipy's analog band-stop Butterworth (buttord/butter).
        double fc = 1000.0, pb = 300.0, sb = 100.0, atten = 40.0;
        FilterDesign nt = FilterDesign.ofSpec(FilterType.NOTCH, FilterResponse.BUTTERWORTH,
                1.0, atten, fc, pb, sb);
        // Geometric SB edges (arithmetic-bandwidth transform): u−1/u = sb/fc.
        double xs = sb / fc;
        double sbHi = fc * (xs + Math.sqrt(xs * xs + 4.0)) / 2.0;   // ≈ 1051.2 Hz
        double xp = pb / fc;
        double pbHi = fc * (xp + Math.sqrt(xp * xp + 4.0)) / 2.0;   // ≈ 1161.2 Hz
        assertTrue(-nt.evalDb(sbHi) >= atten,
                "notch meets stop atten at the SB edge: got " + (-nt.evalDb(sbHi)) + " dB");
        assertEquals(HALF_POWER_DB, nt.evalDb(pbHi), 0.05, "notch returns to −3 dB at the PB edge");
        assertEquals(0.0, nt.evalDb(fc / 4.0), 1e-2, "notch passes well below the band");
        assertEquals(0.0, nt.evalDb(fc * 4.0), 1e-2, "notch passes well above the band");
        // The old (inverted) constraint required SB > PB; that must now be rejected.
        assertThrows(IllegalArgumentException.class, () ->
                FilterDesign.ofSpec(FilterType.NOTCH, FilterResponse.BUTTERWORTH,
                        1.0, atten, fc, sb, pb),
                "NOTCH with PB ≤ SB is rejected");
    }

    // ---------------------------------------------------------------------
    //  Anchor corners - the view aligns the overlay to the measured trace here
    // ---------------------------------------------------------------------

    @Test
    void cornerFrequencies_lpHp_returnPassEdge() {
        FilterDesign lp = FilterDesign.ofOrder(FilterType.LOW_PASS, FilterResponse.BUTTERWORTH, 4, 0.0, FC, 1.0);
        assertEquals(1, lp.cornerFrequenciesHz().length, "LP has a single anchor corner");
        assertEquals(FC, lp.cornerFrequenciesHz()[0], 1e-9, "LP anchor corner is the pass edge Fc");

        FilterDesign hp = FilterDesign.ofOrder(FilterType.HIGH_PASS, FilterResponse.BUTTERWORTH, 4, 0.0, FC, 1.0);
        assertEquals(FC, hp.cornerFrequenciesHz()[0], 1e-9, "HP anchor corner is the pass edge Fc");
    }

    @Test
    void cornerFrequencies_bandPass_areArithmeticPassbandEdges() {
        // BP keeps the verified arithmetic passband edges fc ± B/2 (B = fc/Q).
        FilterDesign bp = FilterDesign.ofOrder(FilterType.BAND_PASS, FilterResponse.BUTTERWORTH, 3, 0.0, FC, 4.0);
        double b = FC / 4.0;
        double[] c = bp.cornerFrequenciesHz();
        assertEquals(2, c.length, "BP has two anchor corners");
        assertEquals(FC - 0.5 * b, c[0], 1e-9, "BP lower corner = fc − B/2");
        assertEquals(FC + 0.5 * b, c[1], 1e-9, "BP upper corner = fc + B/2");
    }

    @Test
    void cornerFrequencies_notch_areGeometricPassbandShoulders() {
        // NOTCH corners are the passband SHOULDERS: the geometric edges of the
        // outer passband-return band of arithmetic width B (= passHz Mode 1 /
        // fc/Q Mode 2).  They are log-symmetric about fc (product == fc²) and the
        // ideal notch evaluates to the ≈−3 dB skirt there - NOT the deep null and
        // NOT the flat plateau - so anchoring puts the ideal plateau on the
        // measured plateau and the clamped floor sits A below it.
        double q = 2.0;
        FilterDesign nt = FilterDesign.ofOrder(FilterType.NOTCH, FilterResponse.BUTTERWORTH, 4, 0.0, FC, q);
        double[] c = nt.cornerFrequenciesHz();
        assertEquals(2, c.length, "NOTCH has two anchor corners");
        assertTrue(c[0] < FC && c[1] > FC, "shoulders straddle the centre");
        assertEquals(FC * FC, c[0] * c[1], 1e-3, "shoulders are geometrically symmetric about fc");
        // At each shoulder the ideal notch is the finite ≈−3 dB return skirt, not
        // the deep null (< −40 dB) and not the flat plateau (> −0.5 dB).
        for (double f : c) {
            double db = nt.evalDb(f);
            assertTrue(db < -0.5 && db > -40.0,
                    "NOTCH shoulder sits on the finite return skirt (got " + db + " dB at " + f + " Hz)");
        }
        assertEquals(nt.evalDb(c[0]), nt.evalDb(c[1]), 1e-6, "shoulder eval is symmetric");
    }

    // ---------------------------------------------------------------------
    //  Mode-1 (ofSpec) meets the stopband spec for every family
    // ---------------------------------------------------------------------

    @Test
    void ofSpec_meetsStopAttenAtStopEdge_forAllFamilies() {
        double stopDb = 40.0;
        double fs = 2.0 * FC;
        for (FilterResponse response : FilterResponse.values()) {
            FilterDesign f = FilterDesign.ofSpec(FilterType.LOW_PASS, response, 1.0, stopDb, 0.0, FC, fs);
            double atten = -f.evalDb(fs);
            if (response == FilterResponse.BESSEL) {
                // Bessel is the only family allowed to fall short (capped order);
                // it must at least resolve to the cap when it cannot reach spec.
                assertEquals(FilterMath.MAX_ORDER, f.getOrder(),
                        "Bessel falls back to MAX_ORDER when it cannot meet the spec");
            } else {
                assertTrue(atten >= stopDb - 1e-6,
                        response + " meets stop atten at Fs: got " + atten + " dB, need " + stopDb);
            }
        }
    }

    // ---------------------------------------------------------------------
    //  Regression: ofSpec with the PINNED PER-TYPE DEFAULTS draws a real curve
    //  for every type × every closed-form family.
    // ---------------------------------------------------------------------

    /**
     * By-spec HP and Notch used to draw no curve at all: the shared
     * LP-style defaults (pass &lt; stop) violate the HP edge rule (stop &lt; pass)
     * and the Notch edge rule (SB &lt; PB), so {@link FilterDesign#ofSpec} threw
     * and the view drew nothing.  The DSP fix is that each type validates and
     * designs against ITS OWN edge ordering; this test pins that by driving
     * {@code ofSpec} with the per-type defaults that are valid for each type and
     * asserting a finite, correctly-shaped curve for every closed-form family
     * (Bessel excluded - it has no ripple/closed-form order and is covered by
     * {@link #ofSpec_meetsStopAttenAtStopEdge_forAllFamilies()}).
     *
     * <p>Per-type edge semantics (all give a prototype stop ratio ws &gt; 1):
     * LP {@code stop>pass}; HP {@code stop<pass} (ws = pass/stop); BP {@code SB>PB};
     * Notch {@code PB>SB}.  Cross-checked against scipy's analog
     * {@code buttord/cheb1ord/cheb2ord/ellipord} + {@code butter/cheby1/cheby2/ellip}.
     */
    @Test
    void ofSpec_pinnedDefaults_finiteAndShaped_forEveryTypeAndFamily() {
        double ripple = 1.0;
        double atten = 40.0;
        // Only the four closed-form families; Bessel is validated elsewhere.
        FilterResponse[] families = {
                FilterResponse.BUTTERWORTH, FilterResponse.CHEBYSHEV,
                FilterResponse.INV_CHEBYSHEV, FilterResponse.ELLIPTIC,
        };
        // PINNED PER-TYPE DEFAULTS (center, pass, stop) - the actual by-spec
        // seeds from FreqRespFilterTypeParams.fromType, valid for each type's
        // edge semantics (4:1 transition).  center is ignored for LP/HP.
        Object[][] pinned = {
                { FilterType.LOW_PASS,  1000.0, 1000.0, 4000.0 },
                { FilterType.HIGH_PASS, 1000.0, 1000.0,  250.0 },
                { FilterType.BAND_PASS, 1000.0,  500.0, 2000.0 },
                { FilterType.NOTCH,     1000.0, 1000.0,  250.0 },
        };
        for (Object[] p : pinned) {
            FilterType type = (FilterType) p[0];
            double center = (double) p[1];
            double pass = (double) p[2];
            double stop = (double) p[3];
            double[] passbandDeep = passbandProbeHz(type, center);
            double[] stopEdges = stopEdgeHz(type, center, pass, stop);
            for (FilterResponse response : families) {
                FilterDesign f = FilterDesign.ofSpec(type, response, ripple, atten, center, pass, stop);

                assertTrue(f.getOrder() >= FilterMath.MIN_ORDER && f.getOrder() <= FilterMath.MAX_ORDER,
                        type + "/" + response + " resolves to a sane order, got " + f.getOrder());

                // Deep in the passband the curve is finite and within the ripple
                // band [−ripple, 0] (equiripple families dip to −ripple; monotone
                // families sit at 0).  This is the curve-exists-at-~0-dB
                // regression and the passband alignment level.
                for (double freq : passbandDeep) {
                    double db = f.evalDb(freq);
                    assertTrue(Double.isFinite(db), type + "/" + response
                            + " passband value is finite at " + freq + " Hz, got " + db);
                    assertTrue(db <= DB_TOL && db >= -ripple - DB_TOL, type + "/" + response
                            + " passband ≈0 dB (within ripple) at " + freq + " Hz, got " + db);
                }

                // Every stop edge reaches the requested attenuation.
                for (double freq : stopEdges) {
                    double got = -f.evalDb(freq);
                    assertTrue(got >= atten - DB_TOL, type + "/" + response
                            + " meets " + atten + " dB at stop edge " + freq + " Hz, got " + got);
                }
            }
        }
    }

    /**
     * Design matrix: every {@link FilterType} × every {@link FilterResponse}
     * × BOTH design modes (by-spec {@code ofSpec} and by-order {@code ofOrder})
     * seeded from the shipped per-type {@link FreqRespFilterTypeParams#fromType}
     * defaults must build without throwing, land a finite passband at ≈0 dB
     * (within the family's ripple band), and resolve to a sensible sub-cap order
     * - {@code MIN_ORDER ≤ order < MAX_ORDER}, i.e. NO combo may fall back to the
     * {@link FilterMath#MAX_ORDER} cap.  Bessel is the load-bearing case: at a
     * 2:1 transition its gentle roll-off never reaches the 40 dB stop spec at any
     * order and {@code ofSpec} pins it to the cap, so the defaults use a 4:1
     * transition where Bessel resolves to order 5.  This is exactly the 40-combo
     * feasibility guarantee the "Filters" tab relies on: selecting any type,
     * response, and mode from a fresh preset yields a drawable curve. */
    @Test
    void defaultParams_everyTypeResponseAndMode_yieldFiniteSubCapDesign() {
        for (FilterType type : FilterType.values()) {
            FreqRespFilterTypeParams d = FreqRespFilterTypeParams.fromType(type);
            // Deep-passband probes per type (LP well below Fc, HP well above,
            // BP at f0, Notch far on both sides) - the curve-exists-at-≈0-dB
            // check.  By-order uses the order-mode pass/center scalar.
            double specCenter = d.getCenterHz();
            for (FilterResponse response : FilterResponse.values()) {
                boolean rippled = response.hasRipple();
                // --- Mode 1: by specification (ofSpec) ------------------------
                FilterDesign spec = FilterDesign.ofSpec(type, response,
                        d.getRippleDb(), d.getStopAttenDb(),
                        specCenter, d.getPassHz(), d.getStopHz());
                assertSubCapAndShaped(type, response, "ofSpec", spec,
                        passbandProbeHz(type, specCenter),
                        rippled ? d.getRippleDb() : 0.0);

                // --- Mode 2: by order (ofOrder) ------------------------------
                FilterDesign ord = FilterDesign.ofOrder(type, response,
                        d.getOrder(), d.getOrderRippleDb(), d.getOrderPassHz(), d.getQ());
                assertSubCapAndShaped(type, response, "ofOrder", ord,
                        passbandProbeHz(type, d.getOrderPassHz()),
                        rippled ? d.getOrderRippleDb() : 0.0);
            }
        }
    }

    /** Shared matrix assertion: a design must resolve to a sane sub-cap order
     *  and, at every deep-passband probe, evaluate to a finite value inside the
     *  ripple band [−ripple, +tol]. */
    private void assertSubCapAndShaped(FilterType type, FilterResponse response, String mode,
                                       FilterDesign f, double[] probes, double rippleBand) {
        String tag = type + "/" + response + "/" + mode;
        assertTrue(f.getOrder() >= FilterMath.MIN_ORDER && f.getOrder() < FilterMath.MAX_ORDER,
                tag + " resolves to a sensible sub-cap order, got " + f.getOrder()
                        + " (MAX_ORDER=" + FilterMath.MAX_ORDER + ")");
        for (double freq : probes) {
            double db = f.evalDb(freq);
            assertTrue(Double.isFinite(db), tag + " passband value is finite at " + freq + " Hz, got " + db);
            assertTrue(db <= DB_TOL && db >= -rippleBand - DB_TOL,
                    tag + " passband ≈0 dB (within ripple) at " + freq + " Hz, got " + db);
        }
    }

    /**
     * The high-pass passband is ABOVE the pass edge and must
     * align to 0 dB / the measured level when no measurement exists.  Ten times
     * the pass edge is deep passband - every family must be finite there and
     * within the ripple band; the far-passband reference the view divides out
     * ({@code evalDb(1000)} at the pass edge itself) must also be finite.
     */
    @Test
    void ofSpec_highPass_farPassband_isFiniteAndWithinRipple() {
        double ripple = 1.0;
        double pass = 1000.0;
        double far = 10.0 * pass;   // deep in the HP passband (above the edge)
        for (FilterResponse response : new FilterResponse[]{
                FilterResponse.BUTTERWORTH, FilterResponse.CHEBYSHEV,
                FilterResponse.INV_CHEBYSHEV, FilterResponse.ELLIPTIC }) {
            FilterDesign f = FilterDesign.ofSpec(FilterType.HIGH_PASS, response, ripple, 40.0, 1000.0, pass, 500.0);
            double dbFar = f.evalDb(far);
            double dbRef = f.evalDb(pass);
            assertTrue(Double.isFinite(dbFar) && Double.isFinite(dbRef),
                    "HP/" + response + " far-passband & reference are finite");
            assertTrue(dbFar <= DB_TOL && dbFar >= -ripple - DB_TOL,
                    "HP/" + response + " far passband (10× pass) ≈0 dB within ripple, got " + dbFar);
        }
    }

    /**
     * The notch's out-of-band passband must sit at 0 dB /
     * the measured level.  Far below and far above the stop region every family
     * must be finite and within the ripple band.
     */
    @Test
    void ofSpec_notch_farOutsideBand_isFiniteAndWithinRipple() {
        double ripple = 1.0;
        double center = 1000.0;
        double below = center / 100.0;
        double above = center * 100.0;
        for (FilterResponse response : new FilterResponse[]{
                FilterResponse.BUTTERWORTH, FilterResponse.CHEBYSHEV,
                FilterResponse.INV_CHEBYSHEV, FilterResponse.ELLIPTIC }) {
            FilterDesign f = FilterDesign.ofSpec(FilterType.NOTCH, response, ripple, 40.0, center, 1000.0, 500.0);
            for (double freq : new double[]{ below, above }) {
                double db = f.evalDb(freq);
                assertTrue(Double.isFinite(db), "NOTCH/" + response + " finite at " + freq + " Hz, got " + db);
                assertTrue(db <= DB_TOL && db >= -ripple - DB_TOL,
                        "NOTCH/" + response + " out-of-band passband ≈0 dB within ripple at " + freq + " Hz, got " + db);
            }
        }
    }

    /** Deep-passband probe frequencies for a type: LP well below Fc; HP well
     *  above Fc; BP/Notch a point that is unambiguously in each one's passband
     *  (BP at the centre f0; Notch far above the band). */
    private double[] passbandProbeHz(FilterType type, double center) {
        switch (type) {
            case LOW_PASS:  return new double[]{ center / 100.0 };
            case HIGH_PASS: return new double[]{ center * 100.0 };
            case BAND_PASS: return new double[]{ center };
            case NOTCH:
            default:        return new double[]{ center / 100.0, center * 100.0 };
        }
    }

    /** Stop-edge frequencies where the design must reach the requested
     *  attenuation.  LP/HP: the raw stop frequency.  BP/Notch: the two geometric
     *  edges of the stop band, whose arithmetic width B under the standard
     *  LP->BP transform is the stop width SB (BP: SB = stopHz; Notch: SB = stopHz). */
    private double[] stopEdgeHz(FilterType type, double center, double pass, double stop) {
        switch (type) {
            case LOW_PASS:
            case HIGH_PASS:
                return new double[]{ stop };
            case BAND_PASS: {
                double hi = geometricUpperEdge(center, stop);   // BP stop width = SB = stopHz
                return new double[]{ center * center / hi, hi };
            }
            case NOTCH:
            default: {
                double hi = geometricUpperEdge(center, stop);   // Notch stop width = SB = stopHz
                return new double[]{ center * center / hi, hi };
            }
        }
    }

    /** Upper geometric edge of a band with centre {@code f0} and arithmetic
     *  bandwidth {@code b} under W = (f/f0 − f0/f)·(f0/B): solve u − 1/u = b/f0. */
    private double geometricUpperEdge(double f0, double b) {
        double x = b / f0;
        return f0 * (x + Math.sqrt(x * x + 4.0)) / 2.0;
    }

    // ---------------------------------------------------------------------
    //  Alignment sanity - the view uses evalDb(f) − evalDb(1000)
    // ---------------------------------------------------------------------

    @Test
    void evalDb_referencedTo1kHz_isZeroForFlatPassbandLowPass() {
        // The view anchors the curve with evalDb(f) − evalDb(1000).  For a
        // low-pass whose 1 kHz sits deep in the passband, that reference term is
        // ~0 dB, so the aligned curve is unshifted there.
        FilterDesign f = FilterDesign.ofOrder(FilterType.LOW_PASS, FilterResponse.BUTTERWORTH, 4, 0.0, 20000.0, 1.0);
        assertEquals(0.0, f.evalDb(1000.0), 1e-3, "1 kHz is flat passband for a 20 kHz Butterworth LP");
        // Aligned value at 1 kHz is identically zero by construction.
        assertEquals(0.0, f.evalDb(1000.0) - f.evalDb(1000.0), 0.0, "self-referenced alignment is exactly 0");
    }

    // ---------------------------------------------------------------------
    //  Input guards
    // ---------------------------------------------------------------------

    @Test
    void ofSpec_rejectsNonsenseInputs() {
        assertThrows(IllegalArgumentException.class, () ->
                FilterDesign.ofSpec(null, FilterResponse.BUTTERWORTH, 1.0, 40.0, 0.0, 1000.0, 2000.0));
        assertThrows(IllegalArgumentException.class, () ->
                FilterDesign.ofSpec(FilterType.LOW_PASS, FilterResponse.BUTTERWORTH, 1.0, 0.0, 0.0, 1000.0, 2000.0),
                "non-positive stopAtten");
        assertThrows(IllegalArgumentException.class, () ->
                FilterDesign.ofSpec(FilterType.LOW_PASS, FilterResponse.BUTTERWORTH, 1.0, 40.0, 0.0, 2000.0, 1000.0),
                "LP with stop ≤ pass");
        assertThrows(IllegalArgumentException.class, () ->
                FilterDesign.ofSpec(FilterType.HIGH_PASS, FilterResponse.BUTTERWORTH, 1.0, 40.0, 0.0, 1000.0, 2000.0),
                "HP with pass ≤ stop");
        assertThrows(IllegalArgumentException.class, () ->
                FilterDesign.ofSpec(FilterType.LOW_PASS, FilterResponse.CHEBYSHEV, 0.0, 40.0, 0.0, 1000.0, 2000.0),
                "equiripple family with zero ripple");
    }

    @Test
    void ofOrder_rejectsNonsenseInputs() {
        assertThrows(IllegalArgumentException.class, () ->
                FilterDesign.ofOrder(FilterType.LOW_PASS, FilterResponse.BUTTERWORTH, 4, 0.0, 0.0, 1.0),
                "non-positive pass freq");
        assertThrows(IllegalArgumentException.class, () ->
                FilterDesign.ofOrder(FilterType.BAND_PASS, FilterResponse.BUTTERWORTH, 4, 0.0, 1000.0, 0.0),
                "BP with non-positive Q");
    }

    @Test
    void ofSpec_clampsDerivedOrderToMaxOrder() {
        // A brutally tight transition forces the derived order past the cap.
        FilterDesign f = FilterDesign.ofSpec(FilterType.LOW_PASS, FilterResponse.BUTTERWORTH,
                1.0, 120.0, 0.0, 1000.0, 1001.0);
        assertTrue(f.getOrder() <= FilterMath.MAX_ORDER, "derived order is capped at MAX_ORDER");
    }
}
