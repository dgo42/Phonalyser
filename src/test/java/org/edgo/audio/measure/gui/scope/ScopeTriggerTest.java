package org.edgo.audio.measure.gui.scope;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ScopeTrigger} — the Schmitt-banded edge detector
 * that anchors every scope frame.  A regression here either drops valid
 * triggers (visible as a frozen / unstable display) or fires on noise.
 */
class ScopeTriggerTest {

    @Test
    void linear_simpleMidpointCrossing() {
        // prev=-1 at idx 5, curr=+1 at idx 6, level=0 → crossing at 5.5.
        double t = ScopeTrigger.linear(-1f, +1f, 5, 0f);
        assertEquals(5.5, t, 1e-12);
    }

    @Test
    void linear_zeroDenominator_returnsPrevIdx() {
        // prev == curr → no crossing direction; fall back to prevIdx.
        assertEquals(7.0, ScopeTrigger.linear(0.5f, 0.5f, 7, 0f), 1e-12);
    }

    @Test
    void find_risingEdge_returnsLastCrossing() {
        // Square wave with rising edges between idx 7→8, 23→24, 39→40,
        // 55→56.  Hysteresis = 0 → every crossing qualifies; find()
        // returns the rightmost, sub-sample-refined by linear
        // interpolation (prev=-1, curr=+1, level=0 → +0.5).
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
        // Constant signal above the level → no crossing.
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
        // Same setup but signal goes from -0.6 to +0.6 — outside the
        // ±0.5 hysteresis band → trigger fires.
        float[] data = new float[64];
        for (int i = 0; i < 32; i++) data[i] = -0.6f;
        for (int i = 32; i < data.length; i++) data[i] = +0.6f;

        double trig = ScopeTrigger.find(data, data.length, 1, data.length - 1,
                0f, true, false, 0.5f);
        // Linear crossing through 0 between idx 31 (-0.6) and idx 32 (+0.6)
        // lands at 31.5.
        assertEquals(31.5, trig, 1e-9);
    }
}
