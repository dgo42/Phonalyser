package org.edgo.audio.measure.gui.scope;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Frequency-accuracy regression for {@link SignalMeasurements}.
 *
 * <p>Reproduces the field report where the scope read a clean ~1003.6 Hz tone
 * ~0.2 Hz low versus the FFT.  The cause was spectral-leakage bias in the bare
 * (rectangular-window) Goertzel peak, which grows on short buffers; the fix
 * re-refines the located peak on a Hann-windowed copy.  These tests pin the
 * measured frequency to within ±0.01 Hz for clean and lightly-contaminated
 * tones across the buffer lengths and sample rates the worker actually uses.
 */
class SignalMeasurementsFreqTest {

    private static final double TOL_HZ = 0.01;

    private static float[] tone(double f0, double fs, int n, double amp, double phase) {
        float[] d = new float[n];
        for (int i = 0; i < n; i++) {
            d[i] = (float) (amp * Math.sin(2 * Math.PI * f0 * i / fs + phase));
        }
        return d;
    }

    @Test
    void cleanToneWithinTolAtAnyWindowAndRate() {
        double trueF = 1003.601;
        // Window by DURATION (≥ 20 ms ≈ 20 cycles): the worker reads up to
        // 96 000 samples (~0.25 s @ 384 kHz / 1 s @ 96 kHz), so any realistic
        // operating point has far more than this minimum.
        for (double fs : new double[]{96_000, 384_000}) {
            for (double seconds : new double[]{0.02, 0.05, 0.1, 0.25, 0.5, 1.0}) {
                int n = Math.min(96_000, (int) Math.round(fs * seconds));
                for (double ph = 0; ph < 2 * Math.PI; ph += Math.PI / 4) {
                    double f = SignalMeasurements.compute(tone(trueF, fs, n, 0.5, ph), n, fs, 1.0)
                                                 .getFrequency();
                    assertTrue(Math.abs(f - trueF) < TOL_HZ,
                            String.format("fs=%.0f n=%d phase=%.2f: f=%.5f err=%+.5f", fs, n, ph, f, f - trueF));
                }
            }
        }
    }

    @Test
    void harmonicsAndNoiseWithinTol() {
        double fs = 96_000, trueF = 1003.601;
        int n = 48_000;
        Random rnd = new Random(11);
        for (double ph = 0; ph < 2 * Math.PI; ph += Math.PI / 4) {
            float[] d = new float[n];
            for (int i = 0; i < n; i++) {
                double t = i / fs;
                d[i] = (float) (0.5 * Math.sin(2 * Math.PI * trueF * t + ph)
                        + 5e-3 * Math.sin(2 * Math.PI * 2 * trueF * t)        // -40 dB H2
                        + 3e-3 * Math.sin(2 * Math.PI * 3 * trueF * t)        // H3
                        + 1.6e-3 * rnd.nextGaussian());                       // ~-50 dB noise
            }
            double f = SignalMeasurements.compute(d, n, fs, 1.0).getFrequency();
            assertTrue(Math.abs(f - trueF) < TOL_HZ,
                    String.format("phase=%.2f: f=%.5f err=%+.5f", ph, f, f - trueF));
        }
    }
}
