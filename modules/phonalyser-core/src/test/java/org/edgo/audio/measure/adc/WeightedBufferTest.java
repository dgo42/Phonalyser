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

package org.edgo.audio.measure.adc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.edgo.audio.measure.common.StereoSample;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link WeightedBuffer} - the DNL/INL weight table and the
 * code-map linearisation derived from it.  Drives the {@code --analyze-histogram}
 * pipeline and the {@code --load-weighted} mapping used by record-mapped-wav,
 * process-wav, fft-analyze and gen-fft.  A regression here silently mis-corrects
 * every captured sample.
 */
class WeightedBufferTest {

    @Test
    void constructor_setsBinCountFromBitDepth() {
        WeightedBuffer b = new WeightedBuffer(8);
        // bitDepth 8 -> 256 bins; get() on every code returns 0 before compute().
        for (int code = 0; code < 256; code++) {
            assertEquals(0f, b.get(code), 0f);
        }
    }

    @Test
    void compute_mismatchedBitDepth_throws() {
        WeightedBuffer b = new WeightedBuffer(8);
        AdcHistogram   h = new AdcHistogram(16);
        assertThrows(IllegalArgumentException.class, () -> b.compute(h, 4));
    }

    @Test
    void compute_uniformHistogram_innerWeightsAreCloseToUnity() {
        // Uniform histogram (one hit per code) -> every bin's count matches the
        // local moving-average baseline -> weight ≈ 1.0 for every inner bin
        // (the 25-tap FIR introduces small ringing, so we allow a few percent
        // tolerance and skip the rails where the moving average is incomplete).
        int bits = 8;
        AdcHistogram   h = new AdcHistogram(bits);
        for (int code = 0; code < (1 << bits); code++) {
            h.record(sample(code));
        }
        WeightedBuffer b = new WeightedBuffer(bits);
        b.compute(h, 32);

        for (int code = 64; code < (1 << bits) - 64; code++) {
            assertEquals(1.0f, b.get(code), 0.05f,
                    "uniform histogram -> near-unity weights (code " + code + ")");
        }
    }

    @Test
    void buildCodeMap_monotonicallyNonDecreasing() {
        // The CDF-based map must be monotonically non-decreasing - otherwise
        // mapping inverts adjacent codes and the linearised output goes
        // backward in time.  This is the most important invariant.
        int bits = 8;
        AdcHistogram   h = new AdcHistogram(bits);
        // Triangular distribution: count = code for the lower half, then
        // (255-code) for the upper.
        for (int code = 0; code < 128; code++) {
            for (int n = 0; n < code + 1; n++) h.record(sample(code));
        }
        for (int code = 128; code < 256; code++) {
            for (int n = 0; n < (256 - code); n++) h.record(sample(code));
        }
        WeightedBuffer b = new WeightedBuffer(bits);
        b.compute(h, 8);
        b.buildCodeMap();

        double prev = -1.0;
        for (int code = 0; code < 256; code++) {
            double mapped = b.correctedCode(sample(code)).ch1;
            assertTrue(mapped >= prev,
                    "code map must be non-decreasing: code " + code
                            + " -> " + mapped + " < prev " + prev);
            prev = mapped;
        }
    }

    @Test
    void buildCodeMap_outputCodesStayWithinRange() {
        // Output codes must lie in [0, maxCode] - the CDF normalisation is
        // sized exactly so the last bin reaches maxCode.
        int bits = 8;
        AdcHistogram   h = new AdcHistogram(bits);
        // Heavy DC offset toward the lower half.
        for (int code = 0; code < 256; code++) {
            int hits = (code < 100) ? 50 : 1;
            for (int n = 0; n < hits; n++) h.record(sample(code));
        }
        WeightedBuffer b = new WeightedBuffer(bits);
        b.compute(h, 8);
        b.buildCodeMap();

        int maxCode = (1 << bits) - 1;
        for (int code = 0; code < 256; code++) {
            double mapped = b.correctedCode(sample(code)).ch1;
            assertTrue(mapped >= 0f && mapped <= maxCode + 1f,
                    "mapped code " + mapped + " out of [0, " + maxCode + "] for raw " + code);
        }
    }

    @Test
    void applyVoltageScale_multipliesWeightsByVoltsPerLsb() {
        int bits = 8;
        AdcHistogram h = new AdcHistogram(bits);
        for (int code = 0; code < (1 << bits); code++) {
            h.record(sample(code));
        }
        WeightedBuffer b = new WeightedBuffer(bits);
        b.compute(h, 16);

        float beforeMid = b.get(128);
        double scaleVolts = 5.0;   // full-scale peak-to-peak
        b.applyVoltageScale(scaleVolts);

        float voltsPerLsb = (float) (scaleVolts / 256);
        assertEquals(beforeMid * voltsPerLsb, b.get(128), 1e-6f);
    }

    private static StereoSample sample(int code) {
        StereoSample s = new StereoSample();
        s.ch0 = code;
        s.ch1 = code;
        return s;
    }
}
