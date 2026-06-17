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

import lombok.experimental.UtilityClass;

/**
 * Central home for compile-time DEBUG switches — hand-toggled overlays and
 * diagnostics that never reach the UI.  Collecting them here makes it easy to
 * see at a glance what is currently turned on (and to switch it all off before
 * a release).
 */
@UtilityClass
public final class DebugSwitches {

    /** DEBUG hard switch: Overlay the mains comb's frequency response (red) on the FFT, anchored
     *  at H2's level, so the notch positions vs the harmonics are visible.
     *  {@code false} removes the overlay. */
    public static final boolean SHOW_MAINS_COMB_RESPONSE = Boolean.parseBoolean("false");

    /** DEBUG hard switch: overlay the de-embedded calibration filter's response
     *  (the green line) on the FFT, anchored at H2's level, so the loaded
     *  {@code .frc} correction curve is visible against the spectrum.
     *  {@code false} removes the overlay. */
    public static final boolean SHOW_CAL_OVERLAY = Boolean.parseBoolean("false");

    /** DEBUG hard switch: overlay the spectral discontinuity detector's three
     *  reject gates (gate-2 floor-reference curve, gate-1 near-carrier pedestal
     *  line, gate-3 total-power line) on the FFT, anchored to the displayed
     *  floor, so it is visible what each gate would reject. */
    public static final boolean SHOW_DISCONTINUITY_GATES = Boolean.parseBoolean("false");

    /** DEBUG hard switch: log the three stages of FFT result latency, one WARN
     *  line each per displayed frame — 1. worker analyze time
     *  ({@code FftAnalyzerWorker}), 2. worker→UI handoff (asyncExec coalescing
     *  queue), 3. first repaint of the fresh result ({@code FftView}, timed via
     *  {@code startRender}/{@code gotFftResult}).  WARN so the timings show up
     *  without touching the logger config. */
    public static final boolean SHOW_FFT_ANALYZE_TIME = Boolean.parseBoolean("false");

    /** DEBUG hard switch: trace the generator frequency-lock chain, one WARN
     *  line per link per displayed frame — the loop inputs/outputs in
     *  {@code FftController.applyFrequencyLock} (per-tone target, measured,
     *  correction, published trim, plus gate refusals) and the trim
     *  application in {@code GeneratorController}.  Shows which link breaks
     *  when a frequency diff sticks: loop never called, measurement biased,
     *  trim published but not applied, or applied but the analog tone
     *  doesn't move. */
    public static final boolean TRACE_FLL = Boolean.parseBoolean("false");
}
