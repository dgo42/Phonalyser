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

/**
 * The mean of the last {@code window} values fed in, over a fixed window.
 *
 * <p>A single FFT frame's level of the fundamental carries the block's own
 * noise; a drift read from it would follow that noise rather than the filter.
 * Averaging a constant number of consecutive frames divides the noise down
 * while leaving the drift, and a constant window keeps the averaged value's
 * delay and its noise the same at the start of a collection and hours into
 * it, so two readings are comparable.
 *
 * <p>{@link #isFilled()} separates the fill phase, where the window still
 * holds fewer values than it will hold later and the mean is therefore
 * noisier, from the steady phase the caller reads against.
 *
 * <p>The mean is summed over the held values on every read rather than
 * carried as a running sum: at a window of a few values and a few reads per
 * second the cost is nothing, and no rounding accumulates over a collection
 * of hours.
 *
 * <p>Not thread-safe: fed and read on one thread, like the analyser's other
 * per-frame state.
 */
public final class MovingAverage {

    /** The held values, oldest overwritten first. */
    private final double[] ring;
    /** How many of {@link #ring} hold a value, up to its length - how far a
     *  window still filling has come. */
    @Getter
    private int count;
    /** Where the next value goes. */
    private int next;

    public MovingAverage(int window) {
        ring = new double[window];
    }

    /** Adds {@code value}, dropping the oldest once the window is full. */
    public void feed(double value) {
        ring[next] = value;
        next = (next + 1) % ring.length;
        if (count < ring.length) count++;
    }

    /** Whether the window holds its full length of values. */
    public boolean isFilled() {
        return count == ring.length;
    }

    /** The mean of the held values, NaN while none are held. */
    public double value() {
        if (count == 0) return Double.NaN;
        double sum = 0.0;
        for (int i = 0; i < count; i++) sum += ring[i];
        return sum / count;
    }

    /** Forgets every held value - the window fills again from empty. */
    public void reset() {
        count = 0;
        next  = 0;
    }
}
