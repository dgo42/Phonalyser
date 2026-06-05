/*
 * Phonalyser — precision audio measurement workbench.
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

package org.edgo.audio.measure.gui.common;

/**
 * Central home for compile-time DEBUG switches — hand-toggled overlays and
 * diagnostics that never reach the UI.  Collecting them here makes it easy to
 * see at a glance what is currently turned on (and to switch it all off before
 * a release).
 */
public final class DebugSwitches {

    /** DEBUG hard switch: Overlay the mains comb's frequency response (red) on the FFT, anchored
     *  at H2's level, so the notch positions vs the harmonics are visible.
     *  {@code false} removes the overlay. */
    public static final boolean SHOW_MAINS_COMB_RESPONSE = true;

    /** DEBUG hard switch: overlay the spectral discontinuity detector's three
     *  reject gates (gate-2 floor-reference curve, gate-1 near-carrier pedestal
     *  line, gate-3 total-power line) on the FFT, anchored to the displayed
     *  floor, so it is visible what each gate would reject. */
    public static final boolean SHOW_DISCONTINUITY_GATES = false;

    private DebugSwitches() {}
}
