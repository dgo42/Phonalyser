package org.edgo.audio.measure.gui.common;

/**
 * Central home for compile-time DEBUG switches — hand-toggled overlays and
 * diagnostics that never reach the UI.  Collecting them here makes it easy to
 * see at a glance what is currently turned on (and to switch it all off before
 * a release).
 */
public final class DebugSwitches {

    private DebugSwitches() {}

    /** DEBUG hard switch: Overlay the mains comb's frequency response (red) on the FFT, anchored
     *  at H2's level, so the notch positions vs the harmonics are visible.
     *  {@code false} removes the overlay. */
    public static final boolean SHOW_MAINS_COMB_RESPONSE = true;

    /** DEBUG hard switch: overlay the spectral discontinuity detector's three
     *  reject gates (gate-2 floor-reference curve, gate-1 near-carrier pedestal
     *  line, gate-3 total-power line) on the FFT, anchored to the displayed
     *  floor, so it is visible what each gate would reject. */
    public static final boolean SHOW_DISCONTINUITY_GATES = false;
}
