package org.edgo.audio.measure.gui.scope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The proportional dual-channel V/div zoom rule (spec §Vertical-zoom): both
 * channels zoom together; a manual (off-1-2-5) channel keeps its proportion to
 * the channel that steps the rule.  Exercises {@link ScopeFormat}'s pure math
 * with the spec's worked examples.
 */
class VoltsPerDivCoupleTest {

    private static final double[] RULE = OscParse.voltsPerDivTargets();
    private static final int IN  = -1;   // zoom in  → smaller V/div
    private static final int OUT = +1;   // zoom out → larger V/div
    private static final double NO_CEIL = 0;

    @Test
    void onRuleDetection() {
        assertTrue(ScopeFormat.onVoltsPerDivRule(500e-6, RULE));
        assertTrue(ScopeFormat.onVoltsPerDivRule(200e-6, RULE));
        assertFalse(ScopeFormat.onVoltsPerDivRule(250e-6, RULE));
        assertFalse(ScopeFormat.onVoltsPerDivRule(300e-6, RULE));
    }

    @Test
    void nextRungBothDirections() {
        assertEquals(200e-6, ScopeFormat.nextVoltsPerDivRung(500e-6, IN,  RULE), 1e-12);
        assertEquals(200e-6, ScopeFormat.nextVoltsPerDivRung(250e-6, IN,  RULE), 1e-12);
        assertEquals(500e-6, ScopeFormat.nextVoltsPerDivRung(250e-6, OUT, RULE), 1e-12);
        assertEquals(500e-6, ScopeFormat.nextVoltsPerDivRung(300e-6, OUT, RULE), 1e-12);
    }

    /** Spec example 1: left on rule (500 µV) leads, right (250 µV) follows by ratio. */
    @Test
    void example1_oneOffRule_zoomIn() {
        double[] r = ScopeFormat.coupleVoltsPerDivZoom(500e-6, 250e-6, IN, RULE, NO_CEIL);
        assertEquals(200e-6, r[0], 1e-12);
        assertEquals(100e-6, r[1], 1e-12);   // 250 * (200/500)
    }

    /** Spec example 2: both off rule; right (300 µV) is nearest 500 µV, leads. */
    @Test
    void example2_bothOffRule_zoomOut() {
        double[] r = ScopeFormat.coupleVoltsPerDivZoom(250e-6, 300e-6, OUT, RULE, NO_CEIL);
        assertEquals(250e-6 * 500.0 / 300.0, r[0], 1e-12);   // 416.667 µV
        assertEquals(500e-6, r[1], 1e-12);
    }

    /** Both on the rule → still proportional; the base is picked so the SCALED
     *  channel lands nearest a rung.  500µV/200µV zoom-in: left base → right
     *  200·(200/500)=80µV (20µV off 100µV) beats right base → left 250µV (50µV off 200µV). */
    @Test
    void bothOnRule_holdsRatio_scaledNearestRung() {
        double[] r = ScopeFormat.coupleVoltsPerDivZoom(500e-6, 200e-6, IN, RULE, NO_CEIL);
        assertEquals(200e-6, r[0], 1e-12);
        assertEquals( 80e-6, r[1], 1e-12);
        assertEquals(2.5, r[0] / r[1], 1e-9);          // ratio held
    }

    /** Worked example: 500µV/1mV zoom-in → 250µV/500µV (right base; left 250µV is
     *  50µV off 200µV, beating left base's 400µV which is 100µV off 500µV). */
    @Test
    void bothOnRule_worked_500u_1m_zoomIn() {
        double[] r = ScopeFormat.coupleVoltsPerDivZoom(500e-6, 1e-3, IN, RULE, NO_CEIL);
        assertEquals(250e-6, r[0], 1e-12);
        assertEquals(500e-6, r[1], 1e-12);
        assertEquals(0.5, r[0] / r[1], 1e-9);          // ratio 1:2 held
    }

    /** A single active channel just walks the rule; the inactive one is untouched. */
    @Test
    void singleActiveChannel() {
        double[] r = ScopeFormat.coupleVoltsPerDivZoom(100e-6, 0, IN, RULE, NO_CEIL);
        assertEquals(50e-6, r[0], 1e-12);
        assertEquals(0,     r[1], 0);
    }

    /** Zoom-out is blocked once a channel would exceed the FS-fills-height ceiling. */
    @Test
    void zoomOutBlockedAtCeiling() {
        double ceil = 0.5;   // pretend FS fills the grid at 0.5 V/div
        double[] r = ScopeFormat.coupleVoltsPerDivZoom(0.5, 0.2, OUT, RULE, ceil);
        assertEquals(0.5, r[0], 1e-12);   // unchanged — blocked
        assertEquals(0.2, r[1], 1e-12);
    }

    /** An off-rule follower keeps its proportion to the on-rule leader through a zoom. */
    @Test
    void proportionPreservedWithOffRuleFollower() {
        // left on rule (1 mV) leads; right (400 µV, off rule) keeps its 0.4 proportion.
        double[] in = ScopeFormat.coupleVoltsPerDivZoom(1e-3, 400e-6, IN, RULE, NO_CEIL);
        assertEquals(500e-6, in[0], 1e-12);          // 1m → 500u (rule)
        assertEquals(200e-6, in[1], 1e-12);          // 400u * 0.5
        assertEquals(0.4, in[1] / in[0], 1e-12);     // proportion 400/1000 held
    }
}
