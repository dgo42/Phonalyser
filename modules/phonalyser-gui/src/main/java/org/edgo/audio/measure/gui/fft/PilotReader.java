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

package org.edgo.audio.measure.gui.fft;

import lombok.Getter;
import lombok.Setter;

/**
 * One pilot line's reader: the whole bin the line sits on, whether the bin
 * carried its line in the last frame, and the raw phase of the collection's
 * FIRST frame, against which every later frame's change is measured.
 *
 * <p>The phase itself is read by the worker from the frame's own spectrum.
 * What this type gives is the change since that first frame on the nearest
 * branch of one turn, and no more: a pilot turns hundreds of times between two
 * frames and thousands since the collection began, so the fraction of a turn
 * is all one line has to give.  The whole turns are settled by the two pilots
 * and the fundamental together in {@link PairAdvance}.
 *
 * <p>The baseline is the first frame and stays there for the whole collection:
 * every reading is an absolute one, taken from the same zero, so a frame the
 * search settles wrongly is wrong on that frame alone and the next frame is
 * measured from the start again.  A chain of changes from frame to frame would
 * carry such an error for good.
 *
 * <p>Not thread-safe: fed and read on one thread, like the analyser's other
 * per-frame state.
 */
public final class PilotReader {

    /** One whole turn of phase, radians. */
    private static final double TURN_RAD = 2.0 * Math.PI;

    /** The whole bin of the transform the line sits on. */
    @Getter
    private final int bin;
    /** The margin test of the last frame: whether the bin carried its line. */
    @Getter
    @Setter
    private boolean lineHeld;
    /** The collection's first frame's raw phase, radians - the zero of every
     *  reading that follows. */
    private double firstPhase;
    /** Whether that first frame's phase is held to measure against. */
    @Getter
    private boolean firstTaken;

    public PilotReader(int bin) {
        this.bin = bin;
    }

    /**
     * The phase this line gained since the collection's first frame, radians
     * on the nearest branch of one turn.  Zero while no first frame is held
     * yet.  Changes nothing: {@link #takeFirst} sets the zero.
     */
    public double changeSince(double rawPhase) {
        return firstTaken ? Math.IEEEremainder(rawPhase - firstPhase, TURN_RAD) : 0.0;
    }

    /** Makes {@code rawPhase} the zero every later frame is measured from: the
     *  frame it was read on is the collection's first. */
    public void takeFirst(double rawPhase) {
        firstTaken = true;
        firstPhase = rawPhase;
    }

    /** Forgets the zero - the next frame is a first frame again. */
    public void reset() {
        firstTaken = false;
        firstPhase = 0.0;
    }
}
