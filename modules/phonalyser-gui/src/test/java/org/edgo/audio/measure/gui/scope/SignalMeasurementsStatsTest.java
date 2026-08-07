package org.edgo.audio.measure.gui.scope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * Whole-period Vmean / Vrms integration in {@link SignalMeasurements}: a fixed
 * measurement window holds a fractional number of signal cycles, and the mean of
 * that fraction is an amplitude-proportional, capture-phase-dependent residual
 * (up to A/(π·cycles) - ~mV at full scale, which swamped the Vmean σ over the
 * 5 s stats window).  These tests pin the crossing-bounded whole-period
 * integration: Vmean stays at the true DC level regardless of the window's
 * phase or the signal's amplitude, so only the noise floor moves it.
 */
class SignalMeasurementsStatsTest {

    private static final int    SAMPLE_RATE = 384_000;
    private static final double PEAK_VOLTS  = 2.536;    // ≈ 1.7932 Vrms ADC FS
    private static final int    N           = 96_000;   // the worker's 0.25 s read window
    private static final double FREQ_HZ     = 1003.6;   // ~250.9 cycles in the window

    private float[] sine(double amplitude, double dcOffset, double phase) {
        float[] d = new float[N];
        double w = 2 * Math.PI * FREQ_HZ / SAMPLE_RATE;
        for (int i = 0; i < N; i++) {
            d[i] = (float) (dcOffset + amplitude * Math.sin(w * i + phase));
        }
        return d;
    }

    @Test
    void vmean_fullScaleSine_phaseIndependent() {
        // The naive full-window mean of this signal wanders ~0.1-3 mV with the
        // capture phase; whole-period integration must hold Vmean within 10 µV
        // at every phase.
        for (double phase : new double[] { 0.0, 0.7, 1.3, 2.1, 2.9 }) {
            SignalMeasurements m = SignalMeasurements.from(
                    sine(1.0, 0.0, phase), N, SAMPLE_RATE, PEAK_VOLTS, false);
            assertTrue(Math.abs(m.getVmean()) < 10e-6,
                    "Vmean " + m.getVmean() + " V at phase " + phase);
        }
    }

    @Test
    void vmean_preservesTrueDcOffset() {
        SignalMeasurements m = SignalMeasurements.from(
                sine(0.5, 0.1, 0.4), N, SAMPLE_RATE, PEAK_VOLTS, false);
        assertEquals(0.1 * PEAK_VOLTS, m.getVmean(), 10e-6);
    }

    @Test
    void vrms_wholePeriods_matchesAmplitude() {
        SignalMeasurements m = SignalMeasurements.from(
                sine(1.0, 0.0, 0.9), N, SAMPLE_RATE, PEAK_VOLTS, false);
        assertEquals(PEAK_VOLTS / Math.sqrt(2), m.getVrms(), 1e-4);
    }

    @Test
    void vmean_dcOnly_fallsBackToFullWindow() {
        float[] d = new float[N];
        Arrays.fill(d, 0.25f);
        SignalMeasurements m = SignalMeasurements.from(d, N, SAMPLE_RATE, PEAK_VOLTS, false);
        assertEquals(0.25 * PEAK_VOLTS, m.getVmean(), 1e-6);
    }
}
