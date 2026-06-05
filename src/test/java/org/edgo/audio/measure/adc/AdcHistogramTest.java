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

package org.edgo.audio.measure.adc;

import org.edgo.audio.measure.common.StereoSample;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AdcHistogram}.  Records synthetic samples, then
 * verifies the bin counters, the unique-code count, and the running
 * min/max/total bookkeeping behave per spec.
 */
class AdcHistogramTest {

    @Test
    void emptyHistogram_initialState() {
        AdcHistogram h = new AdcHistogram(8);
        assertEquals(8,      h.getBitDepth());
        assertEquals(256L,   h.getBinCount());
        assertEquals(0L,     h.getTotalCount());
        assertEquals(0L,     h.getUniqueCodes());
        // getCount returns 0 for any code that hasn't been recorded.
        for (int code = 0; code < 256; code++) {
            assertEquals(0, h.getCount(code));
        }
    }

    @Test
    void singleSample_updatesBinAndCounters() {
        AdcHistogram h = new AdcHistogram(8);
        h.record(sample(42));

        assertEquals(1L, h.getTotalCount());
        assertEquals(1L, h.getUniqueCodes());
        assertEquals(1,  h.getCount(42));
        assertEquals(0,  h.getCount(41));
    }

    @Test
    void multipleSamples_countsAccumulate() {
        AdcHistogram h = new AdcHistogram(8);
        for (int i = 0; i < 10; i++) h.record(sample(100));
        for (int i = 0; i < 3;  i++) h.record(sample(200));

        assertEquals(13L, h.getTotalCount());
        assertEquals(2L,  h.getUniqueCodes(), "100 and 200 are the only visited codes");
        assertEquals(10,  h.getCount(100));
        assertEquals(3,   h.getCount(200));
    }

    @Test
    void minCount_maxCount_trackHottestAndColdestBin() {
        AdcHistogram h = new AdcHistogram(8);
        // Three codes: 50 (5 hits), 100 (10 hits), 150 (1 hit).
        for (int i = 0; i < 5;  i++) h.record(sample(50));
        for (int i = 0; i < 10; i++) h.record(sample(100));
        h.record(sample(150));

        // minCount is the LATEST per-record bin count seen along the
        // way (records traverse 1, 1, 1, ...); maxCount is the
        // highest-ever per-record value.  Implementation detail:
        // see record() body — min/max are updated EVERY tick from the
        // newly-incremented bin's value.  So minCount == 1 (first hit
        // of any new code) and maxCount == 10.
        assertEquals(1L,  h.getMinCount());
        assertEquals(10L, h.getMaxCount());
    }

    @Test
    void recordArray_isEquivalentToLoopOverIndividual() {
        AdcHistogram a = new AdcHistogram(8);
        AdcHistogram b = new AdcHistogram(8);

        StereoSample[] batch = new StereoSample[] {
                sample(10), sample(20), sample(20), sample(30),
        };
        a.record(batch);
        for (StereoSample s : batch) b.record(s);

        assertEquals(a.getTotalCount(),  b.getTotalCount());
        assertEquals(a.getUniqueCodes(), b.getUniqueCodes());
        for (long code : new long[] { 10, 20, 30 }) {
            assertEquals(a.getCount(code), b.getCount(code));
        }
    }

    @Test
    void bitDepth_24_acceptsLargerCodes() {
        // 24-bit ADC → 16 777 216 codes.  Verify the histogram
        // accepts codes near the top of the range.
        AdcHistogram h = new AdcHistogram(24);
        long topCode = (1L << 24) - 1;
        h.record(sample((int) topCode));
        h.record(sample(0));
        h.record(sample(0));

        assertEquals(3L, h.getTotalCount());
        assertEquals(2L, h.getUniqueCodes());
        assertEquals(2,  h.getCount(0));
        assertEquals(1,  h.getCount(topCode));
        assertTrue(h.getBinCount() == (1L << 24));
    }

    private static StereoSample sample(int code) {
        StereoSample s = new StereoSample();
        s.ch0 = code;
        s.ch1 = code;
        return s;
    }
}
