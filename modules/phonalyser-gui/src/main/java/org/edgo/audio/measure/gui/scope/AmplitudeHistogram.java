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

package org.edgo.audio.measure.gui.scope;

import java.util.Arrays;

import lombok.Getter;

/**
 * Amplitude occupancy counter: how often the signal sat at each level.  Feeding
 * it a capture answers "where does this waveform spend its time" - a sine piles
 * up at its two extremes (the classic bathtub, because a sinusoid moves slowest
 * at its turning points), noise makes a Gaussian bell, and a clipped signal
 * grows a spike where the rail is.
 *
 * <h2>Resolution follows the signal, not full scale</h2>
 * The range is symmetric about zero and sized by the signal's own observed
 * extremes - so 0&nbsp;V is always the centre of the axis - divided into
 * {@value #MICRO_PER_BAR} micro-bins per displayed bar.  Rendering sums whole
 * groups of micro-bins into bars, so a bar is never narrower than one micro-bin
 * and the plot cannot break up into a comb of stripes with gaps.
 *
 * <p>Dividing FULL SCALE by a fixed bin count instead would tie the resolution
 * to the converter's range rather than to what is being measured: on a card with
 * 1.79&nbsp;V<sub>RMS</sub> full scale, 2048 divisions is a 2.5&nbsp;mV bin -
 * six orders of magnitude coarser than the 1.18&nbsp;nV the 32-bit converter
 * actually resolves, and useless for anything small enough to be interesting.
 *
 * <h2>Re-ranging</h2>
 * {@link #fit} is called with the signal's peak before each block's samples are
 * added.  While it fits, counting continues and the distribution accumulates
 * without bound.  When it no longer fits, the range is re-established and the
 * counts are CLEARED - deliberately, because counts gathered at a materially
 * different level describe a different signal, and merging the two would present
 * two populations as one distribution.  A restart is the honest signal that the
 * measurement changed.
 *
 * <p>The peak handed in must itself be a STABLE one - the caller aggregates it
 * over the scope's measurement-average window.  A single block's peak is a random
 * draw from the signal's tail, and ranging on that would restart the distribution
 * on almost every pass of anything noisy.
 *
 * <p>An eighth of the range at each end is headroom, and that headroom IS the
 * tolerance: the signal may grow into it without forcing a restart, and until it
 * is exceeded no sample is ever clamped.  A signal that SHRINKS keeps its range
 * (and so gets coarser); a reset re-ranges onto whatever is present now.  Resets
 * come from the histogram window's own button and from the scope's statistics
 * reset - the events that invalidate the running avg / min / max invalidate the
 * distribution too.
 *
 * <h2>Units and the resolution floor</h2>
 * Deliberately unit-agnostic: it is fed NORMALISED samples EXACTLY AS CAPTURED -
 * no DC removal, no filtering - and stores nothing but counts, so the range is in
 * the same units as whatever was added.  The paint side multiplies the bin edges
 * by the channel's peak volts to label the axis, which means a recalibration
 * relabels the display without disturbing a single count.
 *
 * <p>0&nbsp;V is the centre of the RANGE, not of the data: a signal carrying a DC
 * offset sits off the centre line by exactly that offset, which is the honest
 * picture and makes the offset readable.  Subtracting a running mean estimate
 * instead would bin one voltage into different bins as the estimate settled.
 *
 * <p>Bin width is floored at one converter code ({@link #MIN_BIN_WIDTH}).  Below
 * that a bin could only ever hold the gaps between codes, and a dead-flat signal
 * would produce a zero-width range.
 *
 * <h2>Threading</h2>
 * Not synchronised, and it does not need to be.  {@link #fit} / {@link #add} /
 * {@link #reset()} all run on the measurement worker thread - a reset asked for
 * by the UI is a request the worker consumes on its next pass, never an in-place
 * clear underneath an {@code add()}.  The UI thread only ever reads a
 * {@link #snapshot()} the worker publishes through a volatile field, so the
 * painter never walks an array that is being mutated underneath it.
 */
public final class AmplitudeHistogram {

    /** Micro-bins accumulated per displayed bar.  Rendering sums this many into
     *  one bar, which is what guarantees a smooth bar at any signal level. */
    public static final int MICRO_PER_BAR = 256;
    /** Reserve held at EACH end of the range, as a fraction of it: the signal
     *  occupies the middle six eighths and an eighth is headroom either side.
     *  Generous on purpose - this IS the re-range tolerance, so it is what keeps
     *  the ordinary wander of a noisy signal's extremes from restarting the
     *  distribution.  It costs nothing on screen: the display aggregates over the
     *  OCCUPIED bins only, so unused padding never becomes a bar. */
    private static final int HEADROOM_DIVISOR = 8;
    /** Finest bin the data can justify: one code of a 32-bit sample, in the
     *  normalised units the accumulator is fed.  32-bit is the deepest capture
     *  format the app supports, so nothing narrower can ever be populated. */
    private static final double MIN_BIN_WIDTH = 1.0 / (1L << 31);

    @Getter
    private final int microBins;
    private final int[] bins;

    /** Range currently covered, and the width of one micro-bin.  Meaningless
     *  until {@link #fit} has been called once - see {@link #isRanged()}. */
    @Getter
    private double rangeMin;
    @Getter
    private double rangeMax;
    @Getter
    private double binWidth;
    @Getter
    private boolean ranged;

    /** Samples counted since the range was last established. */
    @Getter
    private long total;
    /** Tallest micro-bin.  Tracked as samples arrive so the accumulator never
     *  has to be walked just to find it. */
    @Getter
    private int maxCount;

    /**
     * @param displayBars bars the plot intends to draw; the accumulator holds
     *                    {@value #MICRO_PER_BAR} micro-bins for each of them.
     *                    Must leave room for the headroom at both ends.
     */
    public AmplitudeHistogram(int displayBars) {
        if (displayBars < 2) {
            throw new IllegalArgumentException("displayBars must be >= 2, got " + displayBars);
        }
        this.microBins = displayBars * MICRO_PER_BAR;
        this.bins      = new int[microBins];
    }

    /** Private copy constructor for {@link #snapshot()}. */
    private AmplitudeHistogram(AmplitudeHistogram src) {
        this.microBins = src.microBins;
        this.bins      = src.bins.clone();
        this.rangeMin  = src.rangeMin;
        this.rangeMax  = src.rangeMax;
        this.binWidth  = src.binWidth;
        this.ranged    = src.ranged;
        this.total     = src.total;
        this.maxCount  = src.maxCount;
    }

    /**
     * Prepares the accumulator for a block whose mean-removed extremes are
     * {@code min}...{@code max}, and reports whether the counts collected so far
     * survived.  Call once per block, before {@link #add}.
     *
     * @return {@code true} if the existing distribution was kept, {@code false}
     *         if the signal no longer fitted and the counts were cleared
     */
    public boolean fit(double min, double max) {
        if (!Double.isFinite(min) || !Double.isFinite(max) || min > max) return ranged;
        // SYMMETRIC about zero, always: 0 V is the axis centre, so the range is
        // driven by whichever extreme is further from it.  An asymmetric waveform
        // therefore shows its asymmetry as unequal BAR HEIGHTS about the centre
        // line, which is where it is readable, instead of by sliding the zero.
        double half = Math.max(Math.abs(min), Math.abs(max));
        if (ranged && half <= rangeMax) return true;
        // The signal occupies everything except the headroom at each end, so it may
        // grow by an eighth of the range before this has to happen again.
        int headroom = microBins / HEADROOM_DIVISOR;
        double w = Math.max(MIN_BIN_WIDTH, 2.0 * half / (microBins - 2.0 * headroom));
        Arrays.fill(bins, 0);
        total    = 0;
        maxCount = 0;
        binWidth = w;
        rangeMax = microBins / 2.0 * w;
        rangeMin = -rangeMax;
        ranged   = true;
        return false;
    }

    /**
     * Count-weighted centre of the collected distribution, in bin units (index plus
     * fraction), or {@code -1} when nothing has been counted.
     *
     * <p>The painter windows the plot symmetrically about this, and labels the axis
     * relative to it, so the mean always lands on the middle line.  Computing it
     * from the counts - rather than subtracting a running estimate before binning -
     * is what lets the samples be counted exactly as captured: a DC offset moves
     * neither the bins nor the picture, only what the axis is measured from.
     */
    public double meanBin() {
        if (total == 0) return -1.0;
        double acc = 0.0;
        for (int i = 0; i < microBins; i++) {
            if (bins[i] != 0) acc += (double) bins[i] * (i + 0.5);
        }
        return acc / total;
    }

    /** The value at a fractional bin position, in the caller's units - for labelling
     *  an axis relative to {@link #meanBin()}. */
    public double binValue(double binPos) {
        return rangeMin + binWidth * binPos;
    }

    /**
     * Counts one mean-removed sample.  Does nothing until {@link #fit} has
     * established a range.  A value outside the range lands in the nearest edge
     * micro-bin, which within the headroom is a shift of less than half a bar.
     */
    public void add(double v) {
        // NaN fails every comparison below, so it is dropped rather than
        // poisoning a bin index.
        if (!ranged || Double.isNaN(v)) return;
        int i = (int) ((v - rangeMin) / binWidth);
        if (i < 0)               i = 0;
        else if (i >= microBins) i = microBins - 1;
        int c = ++bins[i];
        if (c > maxCount) maxCount = c;
        total++;
    }

    /** Drops every count AND the range, so the next {@link #fit} re-ranges onto
     *  whatever the signal is now.  That is what lets the window's reset button
     *  recover the resolution after the signal has shrunk. */
    public void reset() {
        Arrays.fill(bins, 0);
        total    = 0;
        maxCount = 0;
        ranged   = false;
    }

    /** Count in {@code bin}, indexed 0 (at {@link #getRangeMin()}) ...
     *  {@link #getMicroBins()}−1. */
    public int getCount(int bin) {
        return bins[bin];
    }

    /** The lower edge of {@code bin}, in the caller's units.  Accepts
     *  {@link #getMicroBins()} itself, giving the range's upper edge. */
    public double binLowerEdge(int bin) {
        return rangeMin + binWidth * bin;
    }

    /** Lowest micro-bin holding anything, or {@code -1} when nothing has been
     *  added.  With {@link #lastOccupied()} this is what lets the display
     *  auto-range onto the part of the range the signal actually uses. */
    public int firstOccupied() {
        for (int i = 0; i < microBins; i++) if (bins[i] != 0) return i;
        return -1;
    }

    /** Highest micro-bin holding anything, or {@code -1} when nothing has been added. */
    public int lastOccupied() {
        for (int i = microBins - 1; i >= 0; i--) if (bins[i] != 0) return i;
        return -1;
    }

    /**
     * Sums micro-bins {@code [fromBin, toBinExclusive)} down into {@code outBins}
     * buckets - the drawn bars.
     *
     * <p>Micro-bins divide unevenly in general; each is added whole to the bucket
     * its centre falls in, so the total is preserved exactly and no count is
     * split or double-counted.
     */
    public int[] aggregate(int fromBin, int toBinExclusive, int outBins) {
        if (outBins < 1) throw new IllegalArgumentException("outBins must be >= 1, got " + outBins);
        int from = Math.max(0, fromBin);
        int to   = Math.min(microBins, toBinExclusive);
        int[] out = new int[outBins];
        int span = to - from;
        if (span <= 0) return out;
        for (int i = from; i < to; i++) {
            if (bins[i] == 0) continue;
            int b = (int) ((long) (i - from) * outBins / span);
            if (b >= outBins) b = outBins - 1;
            out[b] += bins[i];
        }
        return out;
    }

    /** An immutable-by-convention deep copy for the paint thread. */
    public AmplitudeHistogram snapshot() {
        return new AmplitudeHistogram(this);
    }
}
