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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ScopeTrigger} - the Schmitt-banded edge detector
 * that anchors every scope frame.  A regression here either drops valid
 * triggers (visible as a frozen / unstable display) or fires on noise.
 */
class ScopeTriggerTest {

    @Test
    void linear_simpleMidpointCrossing() {
        // prev=-1 at idx 5, curr=+1 at idx 6, level=0 -> crossing at 5.5.
        double t = ScopeTrigger.linear(-1f, +1f, 5, 0f);
        assertEquals(5.5, t, 1e-12);
    }

    @Test
    void linear_zeroDenominator_returnsPrevIdx() {
        // prev == curr -> no crossing direction; fall back to prevIdx.
        assertEquals(7.0, ScopeTrigger.linear(0.5f, 0.5f, 7, 0f), 1e-12);
    }

    @Test
    void find_risingEdge_returnsLastCrossing() {
        // Square wave with rising edges between idx 7->8, 23->24, 39->40,
        // 55->56.  Hysteresis = 0 -> every crossing qualifies; find()
        // returns the rightmost, sub-sample-refined by linear
        // interpolation (prev=-1, curr=+1, level=0 -> +0.5).
        float[] data = new float[64];
        for (int i = 0; i < data.length; i++) {
            data[i] = (i / 8) % 2 == 0 ? -1f : +1f;
        }
        double trig = ScopeTrigger.find(data, data.length, 1, data.length - 1,
                0f, true, false, 0f);
        assertEquals(55.5, trig, 1e-9);
    }

    @Test
    void find_fallingEdge_returnsLastCrossing() {
        float[] data = new float[64];
        for (int i = 0; i < data.length; i++) {
            data[i] = (i / 8) % 2 == 0 ? +1f : -1f;
        }
        double trig = ScopeTrigger.find(data, data.length, 1, data.length - 1,
                0f, false, false, 0f);
        assertTrue(trig > 0.0,
                "falling trigger should find something on this signal, got " + trig);
    }

    @Test
    void find_noQualifiedCrossing_returnsMinusOne() {
        // Constant signal above the level -> no crossing.
        float[] data = new float[64];
        for (int i = 0; i < data.length; i++) data[i] = 0.5f;

        double trig = ScopeTrigger.find(data, data.length, 1, data.length - 1,
                0f, true, false, 0f);
        assertEquals(-1.0, trig, 1e-12);
    }

    @Test
    void find_hysteresisSuppressesNoiseTriggers() {
        // Noisy signal that crosses the bare level but doesn't venture
        // outside ±0.5 hysteresis band.  With hysteresis applied, NO
        // trigger should fire even though the bare-level crossings exist.
        float[] data = new float[64];
        // start safely below the dead band so the Schmitt state seeds LOW
        for (int i = 0; i < 4; i++) data[i] = -0.6f;
        for (int i = 4; i < data.length; i++) {
            // small oscillation through zero but staying inside ±0.5.
            data[i] = (i % 4 < 2) ? -0.2f : +0.2f;
        }
        double trig = ScopeTrigger.find(data, data.length, 1, data.length - 1,
                0f, true, false, 0.5f);
        assertEquals(-1.0, trig, 1e-12,
                "noise within hysteresis band must not trigger, got " + trig);
    }

    @Test
    void find_hysteresisStillTriggersOnRealEdge() {
        // Same setup but signal goes from -0.6 to +0.6 - outside the
        // ±0.5 hysteresis band -> trigger fires.
        float[] data = new float[64];
        for (int i = 0; i < 32; i++) data[i] = -0.6f;
        for (int i = 32; i < data.length; i++) data[i] = +0.6f;

        double trig = ScopeTrigger.find(data, data.length, 1, data.length - 1,
                0f, true, false, 0.5f);
        // Linear crossing through 0 between idx 31 (-0.6) and idx 32 (+0.6)
        // lands at 31.5.
        assertEquals(31.5, trig, 1e-9);
    }

    // ---- glitch (discontinuity) trigger ----

    /** 1 ms merge window at the test's 384 kHz rate. */
    private static final int MERGE = 384;

    /** ~2 kHz sine at 384 kHz (192 samples/period) with amplitude {@code a}. */
    private float[] glitchTestSine(int n, double a) {
        float[] d = new float[n];
        double w = 2 * Math.PI / 192.0;
        for (int i = 0; i < n; i++) d[i] = (float) (a * Math.sin(w * i));
        return d;
    }

    @Test
    void findGlitch_cleanSine_noTrigger() {
        // A sine's largest |Δ²| is π/2 × its mean |Δ²| - far below the 8×
        // threshold, so a clean tone must never fire, at any amplitude.
        for (double a : new double[] { 1.0, 0.001 }) {
            float[] d = glitchTestSine(4096, a);
            assertEquals(-1.0, ScopeTrigger.findGlitch(d, 1, d.length, true,  MERGE, Double.NaN), 1e-12);
            assertEquals(-1.0, ScopeTrigger.findGlitch(d, 1, d.length, false, MERGE, Double.NaN), 1e-12);
        }
    }

    @Test
    void findGlitch_droppedSamplesGap_anchorsStartOrEnd() {
        // Dropped-samples DAC gap: output zeroed for 150 samples starting at
        // the positive peak.  Entry and recovery bursts merge into ONE glitch;
        // ↑ (anchorStart) lands on the last clean sample before the drop,
        // ↓ on the first settled sample after the recovery.
        float[] d = glitchTestSine(4096, 1.0);
        int gapStart = 1968;
        int gapEnd   = gapStart + 150;
        for (int i = gapStart; i < gapEnd; i++) d[i] = 0f;

        double start = ScopeTrigger.findGlitch(d, 1, d.length, true,  MERGE, Double.NaN);
        double end   = ScopeTrigger.findGlitch(d, 1, d.length, false, MERGE, Double.NaN);
        assertEquals(gapStart - 1, start, 1e-9, "glitch start anchor");
        assertEquals(gapEnd + 1,   end,   1e-9, "glitch end anchor");
    }

    @Test
    void findGlitch_zeroCrossingSplice_detected() {
        // ADC-side cutoff at a rising zero crossing: the waveform splices into
        // a FALLING crossing - value stays ≈ 0 (no dV/dt step for a first-
        // difference gate) but the slope flips, breaking the local linear
        // prediction by ~2·A·ω/fs ≈ 60× the sine's curvature ceiling.
        int splice = 2112;                    // multiple of 192 -> rising crossing
        float[] d = glitchTestSine(4096, 1.0);
        double w = 2 * Math.PI / 192.0;
        for (int k = splice; k < d.length; k++) {
            d[k] = (float) -Math.sin(w * (k - splice));   // descending through zero
        }
        double start = ScopeTrigger.findGlitch(d, 1, d.length, true, MERGE, Double.NaN);
        assertEquals(splice, start, 1e-9, "splice detected at the crossing");
    }

    @Test
    void findGlitch_amplitudeIndependent() {
        // The threshold scales with the signal's own mean |Δ²|, so the same
        // relative gap fires identically on a 1 mV-scale signal.
        float[] d = glitchTestSine(4096, 0.001);
        int gapStart = 1968;
        for (int i = gapStart; i < gapStart + 150; i++) d[i] = 0f;
        assertEquals(gapStart - 1,
                ScopeTrigger.findGlitch(d, 1, d.length, true, MERGE, Double.NaN), 1e-9);
    }

    @Test
    void findGlitch_flatSignal_returnsMinusOne() {
        float[] d = new float[512];
        for (int i = 0; i < d.length; i++) d[i] = 0.25f;
        assertEquals(-1.0, ScopeTrigger.findGlitch(d, 1, d.length, true, MERGE, Double.NaN), 1e-12);
    }
}
