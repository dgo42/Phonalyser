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

package org.edgo.audio.measure.gui.common;

import lombok.experimental.UtilityClass;

/**
 * Central home for compile-time DEBUG switches - hand-toggled overlays and
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
     *  line each per displayed frame - 1. worker analyze time
     *  ({@code FftAnalyzerWorker}), 2. worker->UI handoff (asyncExec coalescing
     *  queue), 3. first repaint of the fresh result ({@code FftView}, timed via
     *  {@code startRender}/{@code gotFftResult}).  WARN so the timings show up
     *  without touching the logger config. */
    public static final boolean SHOW_FFT_ANALYZE_TIME = Boolean.parseBoolean("false");

    /** DEBUG hard switch: trace the generator frequency-lock chain, one WARN
     *  line per link per analysis cycle - the loop inputs/outputs in
     *  {@code FftAnalyzerWorker.applyFrequencyLock} (per-tone target, measured,
     *  correction, then {@code pub=} with the trim when one went out on that
     *  update or {@code held} when the correction did not move, plus gate
     *  refusals) and the trim
     *  application in {@code GeneratorController}.  Shows which link breaks
     *  when a frequency diff sticks: loop never called, measurement biased,
     *  trim published but not applied, or applied but the analog tone
     *  doesn't move. */
    public static final boolean TRACE_FLL = Boolean.parseBoolean("true");

    /** DEBUG hard switch: write a per-cycle record of the coherent FFT
     *  accumulator - one {@code fft-cycles-<timestamp>.csv} per accumulator
     *  run in the application's log directory: every analysis cycle's
     *  de-rotation inputs (absolute window position, intra-call step,
     *  elapsed-sample delta, phase-lock correction, refined kappa, ramp slope
     *  theta) plus the complex spectrum around the fundamental bin, fresh and
     *  accumulated.  A measurement record for offline lobe analysis, not a
     *  log - a long run writes thousands of rows, hence a switch.
     *  {@code false} writes nothing. */
    public static final boolean WRITE_FFT_CYCLE_STATS = Boolean.parseBoolean("false");

    /** DEBUG hard switch: trace what the fundamental's phase does to its
     *  harmonics across a coherent FFT average.  With it on, the worker logs
     *  the phase of every harmonic h relative to h times the fundamental's own
     *  phase - a relation a pure time shift leaves alone, so what moves in it
     *  is the fundamental moving by itself, which is exactly what a drifting
     *  notch does and what the phase alignment corrects.  Three lines: the
     *  relations at the first accumulated frame of a collection (no correction
     *  applied yet), every phase excess as it is measured, and the relations
     *  again on every later frame with that excess taken into account - so the
     *  correction can be read off the log as the numbers standing still.
     *  Beside each phase line a level line: the fundamental and the harmonics
     *  in dBFS at their bins, for the fresh frame and for the accumulator, so
     *  a residual harmonic growing or shrinking over a long collection (a DAC
     *  correction ageing, a floor averaging down) can be read off the log too.
     *  {@code false} logs none of it. */
    public static final boolean TRACE_HARMONIC_PHASE = Boolean.parseBoolean("true");

    /** DEBUG hard switch: in the pilot-tone phase alignment, measure the
     *  FUNDAMENTAL on the reference channel on every analysis cycle - the
     *  analyser's windowed single-bin Goertzel of the opposite channel's window
     *  at the fundamental's bin - and extend the per-cycle phase excess line
     *  with what it yields.  That channel carries the fundamental clear of the
     *  notch, so it is the control the pilot is held against: its phase
     *  change is the time base alone, and the pilot's own change less that
     *  change scaled by the ratio of the two bins is the pilot's deviation,
     *  expected to stand at zero.  The pilot is never read on the reference
     *  channel - a pilot read there is that channel's own converter path and
     *  says nothing about the notch.  Costs the reference mirror and one
     *  Goertzel per analysis cycle.  Steers nothing: the frequency lock and the
     *  excess stay as they are in that mode.  In the reference-channel alignment
     *  the same switch asks the generator for the pilot and reads it as the
     *  control of the reference's reading, the roles reversed, on the same
     *  trace line.  {@code false} measures nothing.  Needs
     *  {@link #TRACE_HARMONIC_PHASE}, which writes the line it extends. */
    public static final boolean TRACE_PILOT_PHASE = Boolean.parseBoolean("false");

    /** DEBUG hard value: accepted frames the pilot-tone phase alignment
     *  averages its two readings over - the relation's change and the
     *  fundamental's change against the reference channel, each over the
     *  last this many frames.  Zero averages nothing: every value is the
     *  fresh frame's own, so a step between two frames shows whole in the
     *  frame it happens in; N divides the frame's own noise down by N and
     *  spreads a step over N frames.  The phases themselves are read from
     *  one raw frame at any setting. */
    public static final int PILOT_AVERAGE_FRAMES = Integer.parseInt("0");

}
