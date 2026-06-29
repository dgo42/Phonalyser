package org.edgo.audio.measure.gui.scope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the SWT-free pan/zoom engine {@link ScopeNav}: the viewport mapping
 * and every horizontal/vertical move/zoom transform, including virtual (off-screen)
 * trigger offsets, the whole-buffer pan clamps, and the coupled proportional V/div zoom.
 */
class ScopeNavTest {

    private static final int X = 10;   // horizontal divisions
    private static final int Y = 10;   // vertical divisions
    private static final double PEAK = 1.4142;          // ≈ 1 V rms ADC full-scale peak
    private static final ScopeNav NAV = new ScopeNav(X, Y, OscParse.voltsPerDivTargets());

    // ---- viewport mapping ----

    @Test
    void viewLeftAndViewport_centredOffset() {
        assertEquals(950.0, NAV.viewLeftAbs(1000, 0.5, 100), 1e-9);
        ScopeNav.Viewport vp = NAV.viewport(1000, 0.5, 100, 900);
        assertEquals(50, vp.dispStart());
        assertEquals(0.0, vp.subSampleOffset(), 1e-9);
        assertEquals(100, vp.dispCount());        // never reduced — renderer blanks edges
    }

    @Test
    void viewport_subSampleOffsetSplit() {
        ScopeNav.Viewport vp = NAV.viewport(1000.3, 0.0, 100, 1000);
        assertEquals(0, vp.dispStart());
        assertEquals(0.3, vp.subSampleOffset(), 1e-9);
    }

    @Test
    void viewport_virtualOffset_windowHangsOffBufferLeft() {
        // offset 1.5 (virtual) pushes the window left of the buffer → negative dispStart,
        // full count kept so the renderer blanks the missing columns (no stretch).
        ScopeNav.Viewport vp = NAV.viewport(1000, 1.5, 100, 900);
        assertEquals(-50, vp.dispStart());
        assertEquals(100, vp.dispCount());
    }

    // ---- horizontal move ----

    @Test
    void moveTriggerOffset_isHalfDivPerTick_unclamped() {
        assertEquals(0.55, NAV.moveTriggerOffset(0.5, +1), 1e-9);   // ½ div = 0.5/10
        assertEquals(0.45, NAV.moveTriggerOffset(0.5, -1), 1e-9);
        assertTrue(NAV.moveTriggerOffset(0.98, +1) > 1.0);          // may go virtual
    }

    @Test
    void clampFrozenOffset_keepsSliverOverlappingBuffer() {
        double anchor = 500;
        int    disp   = 100;
        // a big POSITIVE offset drives the window far left → clamped so the oldest
        // sliver sits at the right edge
        double hi = NAV.clampFrozenOffset(10.0, anchor, disp, 0, 1000, 2.0);
        assertEquals(-98.0, NAV.viewLeftAbs(anchor, hi, disp), 1e-6);   // oldest-disp+2
        // a big NEGATIVE offset drives it far right → newest sliver at the left edge
        double lo = NAV.clampFrozenOffset(-10.0, anchor, disp, 0, 1000, 2.0);
        assertEquals(998.0, NAV.viewLeftAbs(anchor, lo, disp), 1e-6);   // latest-2
        // in-range value passes through
        assertEquals(0.5, NAV.clampFrozenOffset(0.5, anchor, disp, 0, 1000, 2.0), 1e-9);
    }

    @Test
    void moveFileCentre_halfDivAndClamp() {
        assertEquals(495.0, NAV.moveFileCentre(500, +1, 100, 0, 1000), 1e-9);   // ½ div = 5
        assertEquals(950.0, NAV.moveFileCentre(950, -1, 100, 0, 1000), 1e-9);   // clamped at maxC
    }

    @Test
    void clampFileCentre_windowWiderThanFile_snapsToMidpoint() {
        assertEquals(500.0, NAV.clampFileCentre(123, 2000, 0, 1000), 1e-9);
    }

    @Test
    void fileViewWindow_centredScroll() {
        // writePos 200k, 1M-frame buffer, 10k-sample window, centre at 100k, 48 kHz.
        ScopeNav.ViewWindow vw = NAV.fileViewWindow(100_000, 10_000, 200_000, 1_000_000, 48_000);
        assertFalse(vw.followLatest());
        assertEquals(5_000,   vw.minCentre());
        assertEquals(195_000, vw.maxCentre());
        assertEquals(100_000.0, vw.clampedCentre(), 1e-9);
        assertEquals(95_000, vw.mainBackOffset());   // writePos − (centre + disp/2)
        assertEquals(76_000, vw.condensedBackOffset());
    }

    @Test
    void fileViewWindow_followLatest_whenCentreNegative() {
        ScopeNav.ViewWindow vw = NAV.fileViewWindow(-1, 10_000, 200_000, 1_000_000, 48_000);
        assertTrue(vw.followLatest());
        assertEquals(0, vw.mainBackOffset());
    }

    @Test
    void fileViewWindow_clampsCentreToRange() {
        ScopeNav.ViewWindow vw = NAV.fileViewWindow(999_999, 10_000, 200_000, 1_000_000, 48_000);
        assertEquals(195_000.0, vw.clampedCentre(), 1e-9);   // clamped to maxCentre
        assertEquals(0, vw.mainBackOffset());                // newest window
    }

    @Test
    void zoomFileCentre_keepsSampleUnderMouse() {
        // mouse at the left edge (frac 0); zoom in 100→40 samples.
        double centre = 500;
        double next   = NAV.zoomFileCentre(centre, 0.0, 100, 40);
        // sample under the mouse before = centre + 100*(0-0.5) = 450; after must match.
        double before = centre + 100 * (0.0 - 0.5);
        double after  = next   +  40 * (0.0 - 0.5);
        assertEquals(before, after, 1e-9);
        // mouse exactly at the middle → centre unchanged.
        assertEquals(centre, NAV.zoomFileCentre(centre, 0.5, 100, 40), 1e-9);
    }

    // ---- horizontal zoom ----

    @Test
    void zoomTriggerOffset_aroundTrigger_unchanged() {
        assertEquals(0.7, NAV.zoomTriggerOffset(0.7, 1000, 2000, 0.2, false), 1e-12);
    }

    @Test
    void zoomTriggerOffset_aroundMouse_canGoVirtual() {
        // 4× zoom-in (1000→250 samples) with the mouse at the left edge carries a
        // near-right trigger far off-screen; uses the ACTUAL sample ratio, not t/div.
        double posNew = NAV.zoomTriggerOffset(0.9, 1000, 250, 0.0, true);
        assertEquals(3.6, posNew, 1e-9);
        // mouse exactly on the trigger → offset unchanged.
        assertEquals(0.5, NAV.zoomTriggerOffset(0.5, 1000, 2000, 0.5, true), 1e-12);
        // rounding case: at a fine time base 96→ samples don't divide evenly; the ratio
        // is the integer one, so the anchor matches the render exactly.
        assertEquals(0.0 - (0.0 - 0.5) * (10.0 / 9.0),
                NAV.zoomTriggerOffset(0.5, 10, 9, 0.0, true), 1e-12);
    }

    // ---- vertical move ----

    @Test
    void moveVertical_bothChannelsTogether() {
        double[] r = NAV.moveVertical(0.5, 1.0, true, 0.5, 1.0, true, +1, PEAK);
        assertEquals(0.45, r[0], 1e-9);   // -dir*0.05
        assertEquals(0.45, r[1], 1e-9);
    }

    @Test
    void moveVertical_clampedByTighterChannel() {
        // right channel near its bound (V/div=1 → bounds [0,1]) stops both at the same delta.
        double[] r = NAV.moveVertical(0.5, 0.01, true, 0.02, 1.0, true, +1, PEAK);
        assertEquals(0.0, r[1], 1e-9);    // right hit 0.0
        assertEquals(0.48, r[0], 1e-9);   // left moved by the same clamped -0.02
    }

    @Test
    void moveVertical_inactiveChannelIgnored() {
        double[] r = NAV.moveVertical(0.5, 1.0, true, 0.5, 1.0, false, +1, PEAK);
        assertEquals(0.45, r[0], 1e-9);
        assertEquals(0.5,  r[1], 1e-9);   // off → unchanged, and doesn't constrain the clamp
    }

    // ---- vertical zoom (coupled + re-anchored) ----

    @Test
    void zoomVertical_specExample_offRuleFollowerCouples() {
        // (500µV on rule, 250µV off rule) zoom-in around middle → (200µV, 100µV), offsets unchanged.
        double[] r = NAV.zoomVertical(500e-6, 0.5, true, 250e-6, 0.5, true, -1, 0.5, 1e9);
        assertEquals(200e-6, r[0], 1e-12);
        assertEquals(100e-6, r[1], 1e-12);
        assertEquals(0.5, r[2], 1e-12);   // offset at the anchor → unchanged
        assertEquals(0.5, r[3], 1e-12);
    }

    @Test
    void zoomVertical_reanchorsOffsetAtMiddle() {
        // zoom-in (500µV→200µV, ratio old/new=2.5); an off-middle zero line moves away from middle.
        double[] r = NAV.zoomVertical(500e-6, 0.7, true, 0, 0, false, -1, 0.5, 1e9);
        assertEquals(200e-6, r[0], 1e-12);
        assertEquals(0.5 + 0.2 * 2.5, r[2], 1e-9);   // 0.5 + (0.7-0.5)*500/200 = 1.0
    }

    @Test
    void zoomVertical_mouseAnchorKeepsVoltageUnderPointer() {
        // ctrl+wheel zoom-in with mouse at top (frac 0); single channel.
        double[] r = NAV.zoomVertical(1e-3, 0.5, true, 0, 0, false, -1, 0.0, 1e9);
        double newV = r[0];                                   // 1m → 500u
        assertEquals(500e-6, newV, 1e-12);
        // voltage at frac 0 before and after must match.
        double vBefore = (0.5 - 0.0) * Y * 1e-3;
        double vAfter  = (r[2] - 0.0) * Y * newV;
        assertEquals(vBefore, vAfter, 1e-12);
    }

    @Test
    void zoomOutCeiling_roundsUpToRungWhereFsFits() {
        // 2.53 V peak (≈1.791 Vrms FS): exact-fill 0.506 V/div lands between rungs →
        // ceiling rounds UP to 1 V/div (FS fits), not 500 mV/div (which clips FS).
        assertEquals(1.0, NAV.zoomOutVoltsPerDivCeiling(2.53), 1e-9);
        assertEquals(0.5, NAV.zoomOutVoltsPerDivCeiling(PEAK), 1e-9);   // 1.4142 peak → 0.283 → 0.5
    }

    @Test
    void zoomVertical_zoomOutCeilingBlocks() {
        double ceil = NAV.zoomOutVoltsPerDivCeiling(PEAK);   // 2*1.4142/10 ≈ 0.283
        // already at the ceiling rung → zoom-out is blocked, offsets untouched.
        double[] r = NAV.zoomVertical(ceil, 0.5, true, ceil, 0.5, true, +1, 0.5, PEAK);
        assertEquals(ceil, r[0], 1e-12);
        assertEquals(ceil, r[1], 1e-12);
    }
}
