package org.edgo.audio.measure.gui.scope;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MeasurementStats} — Welford online accumulator
 * driving the oscilloscope measurement-table cur/avg/min/max/σ display.
 * Pins NaN-skip behaviour + numerical stability vs the textbook formula.
 */
class MeasurementStatsTest {

    @Test
    void empty_accumulator_returnsNaN() {
        MeasurementStats s = new MeasurementStats();
        assertTrue(Double.isNaN(s.getMean()));
        assertTrue(Double.isNaN(s.getMin()));
        assertTrue(Double.isNaN(s.getMax()));
        assertTrue(Double.isNaN(s.getSigma()));
    }

    @Test
    void singleSample_meanEqualsValue_sigmaStillNaN() {
        MeasurementStats s = new MeasurementStats();
        s.add(5.0);
        assertEquals(5.0, s.getMean(),  1e-12);
        assertEquals(5.0, s.getMin(),   1e-12);
        assertEquals(5.0, s.getMax(),   1e-12);
        // σ undefined with n=1 (Bessel-correction divisor n-1 = 0).
        assertTrue(Double.isNaN(s.getSigma()));
    }

    @Test
    void uniformSamples_matchAnalytic() {
        // {1, 2, 3, 4, 5}: mean=3, min=1, max=5, σ=√(10/4)=√2.5 (sample-σ).
        MeasurementStats s = new MeasurementStats();
        for (int v = 1; v <= 5; v++) s.add(v);
        assertEquals(3.0,           s.getMean(),  1e-12);
        assertEquals(1.0,           s.getMin(),   1e-12);
        assertEquals(5.0,           s.getMax(),   1e-12);
        assertEquals(Math.sqrt(2.5), s.getSigma(), 1e-12);
    }

    @Test
    void nan_samples_areSkipped() {
        MeasurementStats s = new MeasurementStats();
        s.add(2.0);
        s.add(Double.NaN);
        s.add(4.0);
        s.add(Double.NaN);
        // Should behave as if only {2, 4} were added.
        assertEquals(3.0, s.getMean(), 1e-12);
        assertEquals(2.0, s.getMin(),  1e-12);
        assertEquals(4.0, s.getMax(),  1e-12);
        // σ for {2, 4}: mean=3, sum((x-mean)²) = 2, σ = √(2/1) = √2.
        assertEquals(Math.sqrt(2), s.getSigma(), 1e-12);
    }

    @Test
    void welford_stableForLargeOffset() {
        // Catastrophic-cancellation regime for the naive formula:
        // add 1e9, 1e9+1, 1e9+2 (variance = 1).  Welford handles it.
        MeasurementStats s = new MeasurementStats();
        s.add(1e9);
        s.add(1e9 + 1);
        s.add(1e9 + 2);
        assertEquals(1e9 + 1, s.getMean(),  1e-9);
        assertEquals(1.0,      s.getSigma(), 1e-9);
    }
}
