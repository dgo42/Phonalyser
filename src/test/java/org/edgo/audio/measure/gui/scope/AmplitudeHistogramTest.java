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

package org.edgo.audio.measure.gui.scope;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the range-tracking behaviour and the accumulation contract of
 * {@link AmplitudeHistogram} before any GUI code depends on it.  SWT-free, so it
 * runs on any CI agent.
 */
class AmplitudeHistogramTest {

    private static final int BARS  = 50;                                    // the plot's bar count
    private static final int MICRO = BARS * AmplitudeHistogram.MICRO_PER_BAR;

    /** Feeds a sine the way the worker does: extremes first, then the samples. */
    private AmplitudeHistogram sine(double amp, int n) {
        AmplitudeHistogram h = new AmplitudeHistogram(BARS);
        h.fit(-amp, amp);
        for (int i = 0; i < n; i++) h.add(amp * Math.sin(2.0 * Math.PI * i / 997.0));
        return h;
    }

    @Test
    void nothingIsCountedBeforeARangeExists() {
        AmplitudeHistogram h = new AmplitudeHistogram(BARS);
        assertFalse(h.isRanged());
        h.add(0.5);
        assertEquals(0, h.getTotal(), "add() before fit() must not guess a range");
        assertEquals(-1, h.firstOccupied());
    }

    @Test
    void theRangeIsSymmetricAboutZeroWithAnEighthOfHeadroomEachEnd() {
        AmplitudeHistogram h = new AmplitudeHistogram(BARS);
        assertFalse(h.fit(-0.4, 0.4), "the first fit establishes a range, so nothing survived");
        assertEquals(-h.getRangeMax(), h.getRangeMin(), 1e-12, "0 V is the axis centre");
        assertTrue(h.getRangeMax() > 0.4, "headroom beyond the observed peak");
        assertEquals(MICRO / 8 * h.getBinWidth(), h.getRangeMax() - 0.4, 1e-12);
        assertEquals(h.getRangeMin() + MICRO * h.getBinWidth(), h.getRangeMax(), 1e-12);
        assertEquals(0.0, h.binLowerEdge(MICRO / 2), 1e-12, "the middle bin starts at 0 V");
    }

    @Test
    void anAsymmetricSignalStillKeepsZeroInTheMiddle() {
        // A waveform clipped on one side only must not slide the zero line: the
        // range is driven by whichever extreme reaches further, and the asymmetry
        // shows as unequal bar heights about the centre instead.
        AmplitudeHistogram h = new AmplitudeHistogram(BARS);
        h.fit(-0.1, 0.8);
        assertEquals(-h.getRangeMax(), h.getRangeMin(), 1e-12);
        assertTrue(h.getRangeMax() > 0.8, "sized by the further extreme, not the span");
        assertEquals(0.0, h.binLowerEdge(MICRO / 2), 1e-12);
    }

    @Test
    void resolutionFollowsTheSignalNotFullScale() {
        // The whole point.  A signal a thousandth of full scale must get a bin a
        // thousandth as wide — not a bin sized to the converter's range.
        AmplitudeHistogram big   = new AmplitudeHistogram(BARS);
        AmplitudeHistogram small = new AmplitudeHistogram(BARS);
        big.fit(-1.0, 1.0);
        small.fit(-0.001, 0.001);
        assertEquals(1000.0, big.getBinWidth() / small.getBinWidth(), 1e-6);
    }

    @Test
    void binWidthNeverGoesBelowOneConverterCode() {
        // Unreachable with a real converter — the analog noise floor sits tens of dB
        // above the LSB — but a digital loopback can hand us a dead-flat block, and a
        // zero-width range would divide by zero on the next add().
        AmplitudeHistogram h = new AmplitudeHistogram(BARS);
        h.fit(0.0, 0.0);
        assertTrue(h.getBinWidth() > 0.0, "a flat block must still produce a usable range");
        assertEquals(1.0 / (1L << 31), h.getBinWidth(), 0.0, "floored at one 32-bit code");
        h.add(0.0);
        assertEquals(1, h.getTotal());
    }

    @Test
    void aSignalGrowingWithinTheHeadroomKeepsItsCounts() {
        AmplitudeHistogram h = new AmplitudeHistogram(BARS);
        h.fit(-0.5, 0.5);
        for (int i = 0; i < 1000; i++) h.add(0.1);
        // Grow by a third of the headroom — still inside the range.
        double grow = 0.5 + AmplitudeHistogram.MICRO_PER_BAR / 6.0 * h.getBinWidth();
        assertTrue(h.fit(-grow, grow), "inside the headroom the distribution must survive");
        assertEquals(1000, h.getTotal());
    }

    @Test
    void aSignalLeavingTheRangeRestartsTheDistribution() {
        AmplitudeHistogram h = new AmplitudeHistogram(BARS);
        h.fit(-0.5, 0.5);
        for (int i = 0; i < 1000; i++) h.add(0.1);
        assertFalse(h.fit(-0.9, 0.9), "outside the headroom the counts are cleared");
        assertEquals(0, h.getTotal(), "counts from a different level must not be merged in");
        assertEquals(0, h.getMaxCount());
        assertTrue(h.getRangeMax() > 0.9, "and the range has moved onto the new signal");
    }

    @Test
    void aShrinkingSignalKeepsTheRangeUntilItIsReset() {
        // Documented behaviour: shrinking never forces a restart, so a quiet passage
        // does not throw away minutes of accumulation.  Reset is how the user
        // re-ranges onto a smaller signal.
        AmplitudeHistogram h = new AmplitudeHistogram(BARS);
        h.fit(-0.5, 0.5);
        double wide = h.getBinWidth();
        for (int i = 0; i < 1000; i++) h.add(0.1);
        assertTrue(h.fit(-0.01, 0.01));
        assertEquals(1000, h.getTotal());
        assertEquals(wide, h.getBinWidth(), 0.0);
        h.reset();
        assertFalse(h.isRanged());
        h.fit(-0.01, 0.01);
        assertTrue(h.getBinWidth() < wide / 10.0, "after a reset the range follows the small signal");
    }

    @Test
    void aSampleLandsInTheBinWhoseRangeContainsIt() {
        AmplitudeHistogram h = new AmplitudeHistogram(BARS);
        h.fit(-1.0, 1.0);
        h.add(h.binLowerEdge(700) + h.getBinWidth() / 2.0);
        assertEquals(1, h.getCount(700));
        assertEquals(1, h.getTotal());
    }

    @Test
    void outOfRangeSamplesClampIntoTheEdgeBins() {
        // Only reachable inside the headroom in normal use, where it is a shift of
        // less than half a bar — but it must never lose a sample or throw.
        AmplitudeHistogram h = new AmplitudeHistogram(BARS);
        h.fit(-0.5, 0.5);
        h.add(5.0);
        h.add(-5.0);
        h.add(Double.POSITIVE_INFINITY);
        h.add(Double.NEGATIVE_INFINITY);
        assertEquals(2, h.getCount(MICRO - 1), "positive overrange piles into the top bin");
        assertEquals(2, h.getCount(0),         "negative overrange piles into the bottom bin");
        assertEquals(4, h.getTotal(),          "clamped samples still count towards the total");
    }

    @Test
    void nanIsDroppedRatherThanCountedOrCrashing() {
        AmplitudeHistogram h = new AmplitudeHistogram(BARS);
        h.fit(-1.0, 1.0);
        h.add(Double.NaN);
        assertEquals(0, h.getTotal());
        assertEquals(0, h.getMaxCount());
        // A non-finite extreme must not corrupt an established range either.
        double lo = h.getRangeMin();
        assertTrue(h.fit(Double.NaN, 1.0));
        assertEquals(lo, h.getRangeMin(), 0.0);
    }

    @Test
    void maxCountTracksTheTallestBin() {
        AmplitudeHistogram h = new AmplitudeHistogram(BARS);
        h.fit(-1.0, 1.0);
        for (int i = 0; i < 7; i++) h.add(0.5);
        h.add(-0.5);
        assertEquals(7, h.getMaxCount(), "the painter's horizontal full scale");
    }

    @Test
    void aSineFillsTheEdgesMoreThanTheCentre() {
        // The bathtub: a sinusoid moves slowest at its turning points, so it spends
        // most of its time near ±peak.  This is the shape the window exists to show.
        AmplitudeHistogram h = sine(0.9, 100_000);
        int[] bars = h.aggregate(h.firstOccupied(), h.lastOccupied() + 1, BARS);
        int edge   = bars[0] + bars[BARS - 1];
        int centre = bars[BARS / 2] + bars[BARS / 2 - 1];
        assertTrue(edge > centre * 3,
                "sine should pile up at the extremes: edge=" + edge + " centre=" + centre);
        assertEquals(100_000, h.getTotal());
    }

    @Test
    void everyBarIsPopulatedWhateverTheSignalLevel() {
        // The defect this design exists to prevent: with the range pinned to full
        // scale, a small signal occupied a handful of bins and the plot collapsed to
        // a comb — or, once clamped, to two bars.  Ranging on the signal makes the
        // level irrelevant.
        for (double amp : new double[] {0.9, 1e-3, 1e-6}) {
            AmplitudeHistogram h = sine(amp, 200_000);
            int[] bars = h.aggregate(h.firstOccupied(), h.lastOccupied() + 1, BARS);
            assertEquals(BARS, bars.length);
            for (int i = 0; i < BARS; i++) {
                assertTrue(bars[i] > 0, "amp=" + amp + " left bar " + i + " empty");
            }
        }
    }

    @Test
    void theMeanIsReportedInBinUnitsAndReadsBackAsAVoltage() {
        // The painter centres the plot on this and labels the axis relative to it,
        // which is what lets samples be counted exactly as captured: a DC offset
        // moves what the axis is measured FROM, not which bin a sample lands in.
        AmplitudeHistogram h = new AmplitudeHistogram(BARS);
        assertEquals(-1.0, h.meanBin(), 0.0, "no mean until something is counted");
        h.fit(-1.0, 1.0);
        double offset = 0.4;
        int n = 50_000;
        for (int i = 0; i < n; i++) h.add(offset + 0.05 * Math.sin(2.0 * Math.PI * i / 313.0));
        assertEquals(offset, h.binValue(h.meanBin()), h.getBinWidth(),
                "the reported mean must read back as the offset that was fed in");
    }

    @Test
    void aDcOffsetShiftsTheMeanButNotTheShape() {
        // Same distribution twice, once around zero and once around an offset: the
        // occupied SPAN must be identical, so centring the display on the mean
        // presents them identically.
        AmplitudeHistogram centred = new AmplitudeHistogram(BARS);
        AmplitudeHistogram offset  = new AmplitudeHistogram(BARS);
        centred.fit(-1.0, 1.0);
        offset.fit(-1.0, 1.0);
        for (int i = 0; i < 20_000; i++) {
            double v = 0.1 * Math.sin(2.0 * Math.PI * i / 257.0);
            centred.add(v);
            offset.add(v + 0.3);
        }
        int spanCentred = centred.lastOccupied() - centred.firstOccupied();
        int spanOffset  = offset.lastOccupied()  - offset.firstOccupied();
        assertTrue(Math.abs(spanCentred - spanOffset) <= 1,
                "an offset must not change the width: " + spanCentred + " vs " + spanOffset);
        assertEquals(0.3, offset.binValue(offset.meanBin()) - centred.binValue(centred.meanBin()),
                     2.0 * offset.getBinWidth());
    }

    @Test
    void occupiedRangeIsEmptyUntilSomethingIsAdded() {
        AmplitudeHistogram h = new AmplitudeHistogram(BARS);
        h.fit(-1.0, 1.0);
        assertEquals(-1, h.firstOccupied());
        assertEquals(-1, h.lastOccupied());
    }

    @Test
    void aggregatePreservesTheTotalAndHonoursTheBucketCount() {
        AmplitudeHistogram h = sine(0.3, 20_000);
        int[] bars = h.aggregate(h.firstOccupied(), h.lastOccupied() + 1, BARS);
        assertEquals(BARS, bars.length);
        long sum = 0;
        for (int b : bars) sum += b;
        assertEquals(20_000, sum, "no count may be split, dropped or double-counted");
    }

    @Test
    void aggregateOfAnEmptyOrInvertedRangeIsAllZeroes() {
        AmplitudeHistogram h = new AmplitudeHistogram(BARS);
        h.fit(-1.0, 1.0);
        int[] bars = h.aggregate(h.firstOccupied(), h.lastOccupied() + 1, 10);   // -1, 0
        assertEquals(10, bars.length);
        for (int b : bars) assertEquals(0, b);
    }

    @Test
    void binEdgesSpanTheRangeAndTheTopEdgeIsReachable() {
        AmplitudeHistogram h = new AmplitudeHistogram(BARS);
        h.fit(-0.25, 0.75);
        assertEquals(h.getRangeMin(), h.binLowerEdge(0), 1e-12);
        // The painter labels the axis with binLowerEdge(lastOccupied + 1), which for a
        // full range is one past the last bin — pure arithmetic, never an array read.
        assertEquals(h.getRangeMax(), h.binLowerEdge(MICRO), 1e-12);
    }

    @Test
    void snapshotIsDecoupledFromFurtherAccumulation() {
        // The worker keeps binning while the UI thread paints the copy.
        AmplitudeHistogram h = new AmplitudeHistogram(BARS);
        h.fit(-1.0, 1.0);
        h.add(0.25);
        AmplitudeHistogram snap = h.snapshot();
        h.add(0.25);
        h.add(0.25);
        assertEquals(1, snap.getTotal(), "the snapshot must not see later samples");
        assertEquals(3, h.getTotal());
        assertEquals(1, snap.getMaxCount());
        assertEquals(h.getRangeMin(), snap.getRangeMin(), 0.0);
    }

    @Test
    void constructorRejectsUnusableGeometry() {
        assertThrows(IllegalArgumentException.class, () -> new AmplitudeHistogram(1));
        assertThrows(IllegalArgumentException.class, () -> new AmplitudeHistogram(0));
        assertThrows(IllegalArgumentException.class, () -> new AmplitudeHistogram(-5));
    }
}
