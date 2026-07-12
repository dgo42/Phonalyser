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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.edgo.audio.measure.dsp.SineFit;
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
                    double f = SignalMeasurements.from(tone(trueF, fs, n, 0.5, ph), n, fs, 1.0, true)
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
            double f = SignalMeasurements.from(d, n, fs, 1.0, true).getFrequency();
            assertTrue(Math.abs(f - trueF) < TOL_HZ,
                    String.format("phase=%.2f: f=%.5f err=%+.5f", ph, f, f - trueF));
        }
    }

    // ---- Dual-tone residual: independent DAC/ADC clocks ---------------------
    // The scope worker seeds the dual-tone residual's two frequencies from the
    // generator's commanded values and re-pins each on the captured signal
    // (refineFrequencyAround, ±2 Hz — the worker's FREQ_REFINE_HALF_HZ).  With
    // independent DAC/ADC clocks the tones arrive scaled by the clock ratio
    // (ppm), which no hardware on the test bench can reproduce on demand — so
    // these tests SIMULATE the offset and pin both halves of the fix: the
    // refinement must recover the AS-CAPTURED frequencies from the commanded
    // seeds, and the least-squares subtraction at the refined frequencies must
    // be clean where the commanded-frequency fit leaves fundamental leakage.

    /** Commanded CCIF-style pair (Hz) the "generator" emits. */
    private static final double DUAL_F1_HZ = 19_000.0;
    private static final double DUAL_F2_HZ = 20_000.0;
    /** Worker's raw re-pin half-band (Hz) — mirrors FREQ_REFINE_HALF_HZ. */
    private static final double REFINE_HALF_HZ = 2.0;
    /** Residual fit-window scale: 65 536 samples @ 192 kHz ≈ 0.34 s — the
     *  worst case for clock-offset phase drift (RESIDUAL_FIT_MAX_SAMPLES). */
    private static final double DUAL_FS = 192_000.0;
    private static final int    DUAL_N  = 65_536;

    private float[] dualTone(double f1, double f2, double fs, int n) {
        float[] d = new float[n];
        for (int i = 0; i < n; i++) {
            double t = i / fs;
            d[i] = (float) (0.4 * Math.sin(2 * Math.PI * f1 * t + 0.7)
                          + 0.4 * Math.sin(2 * Math.PI * f2 * t + 2.1));
        }
        return d;
    }

    @Test
    void dualToneClockOffset_refinesToAsCapturedFrequencies() {
        // ±80 ppm spans real crystal offsets with margin while staying inside
        // the ±2 Hz refine band at 20 kHz (80 ppm → 1.6 Hz).
        for (double ppm : new double[]{-80, -20, 20, 80}) {
            double scale = 1.0 + ppm * 1e-6;
            double true1 = DUAL_F1_HZ * scale;
            double true2 = DUAL_F2_HZ * scale;
            float[] d = dualTone(true1, true2, DUAL_FS, DUAL_N);
            double r1 = SignalMeasurements.refineFrequencyAround(d, DUAL_N, DUAL_FS, DUAL_F1_HZ, REFINE_HALF_HZ);
            double r2 = SignalMeasurements.refineFrequencyAround(d, DUAL_N, DUAL_FS, DUAL_F2_HZ, REFINE_HALF_HZ);
            assertTrue(Math.abs(r1 - true1) < TOL_HZ,
                    String.format("%.0f ppm: f1=%.5f err=%+.5f", ppm, r1, r1 - true1));
            assertTrue(Math.abs(r2 - true2) < TOL_HZ,
                    String.format("%.0f ppm: f2=%.5f err=%+.5f", ppm, r2, r2 - true2));
        }
    }

    @Test
    void dualToneClockOffset_refinedFitSubtractsClean_commandedFitLeaks() {
        double scale = 1.0 + 50e-6;                       // 50 ppm ADC-vs-DAC offset
        double true1 = DUAL_F1_HZ * scale;                // 19 000.95 Hz as captured
        double true2 = DUAL_F2_HZ * scale;                // 20 001.00 Hz as captured
        float[] d = dualTone(true1, true2, DUAL_FS, DUAL_N);

        // End-to-end path: commanded seeds → refined as-captured frequencies.
        double r1 = SignalMeasurements.refineFrequencyAround(d, DUAL_N, DUAL_FS, DUAL_F1_HZ, REFINE_HALF_HZ);
        double r2 = SignalMeasurements.refineFrequencyAround(d, DUAL_N, DUAL_FS, DUAL_F2_HZ, REFINE_HALF_HZ);

        double rmsRefined   = dualFitResidualRms(d, r1, r2);
        double rmsCommanded = dualFitResidualRms(d, DUAL_F1_HZ, DUAL_F2_HZ);

        // 50 ppm over 0.34 s ≈ 2 rad of phase drift — the commanded-frequency
        // fit must leave gross fundamental leakage (a sizeable fraction of the
        // 0.4 amplitude), while the refined fit subtracts into the numeric
        // floor.  The ratio is the point: the fix buys orders of magnitude.
        assertTrue(rmsRefined < 2e-3,
                String.format("refined-fit residual RMS=%.6f (expected < 2e-3)", rmsRefined));
        assertTrue(rmsCommanded > 0.02,
                String.format("commanded-fit residual RMS=%.6f (expected > 0.02)", rmsCommanded));
        assertTrue(rmsCommanded > 20 * rmsRefined,
                String.format("leakage ratio %.1f× (expected > 20×)", rmsCommanded / rmsRefined));
    }

    /** RMS of the two-tone least-squares residual — initial fit of each tone
     *  plus two alternating refit rounds, mirroring the scope residual's
     *  cross-leakage cancellation (each round refits one tone on the signal
     *  minus the other tone's current model). */
    private double dualFitResidualRms(float[] x, double fA, double fB) {
        SineFit fitA = SineFit.of(x, 0, DUAL_N, DUAL_FS, fA);
        SineFit fitB = SineFit.of(subtractModel(x, fitA), 0, DUAL_N, DUAL_FS, fB);
        for (int round = 0; round < 2; round++) {
            fitA = SineFit.of(subtractModel(x, fitB), 0, DUAL_N, DUAL_FS, fA);
            fitB = SineFit.of(subtractModel(x, fitA), 0, DUAL_N, DUAL_FS, fB);
        }
        double sumSq = 0;
        for (int i = 0; i < DUAL_N; i++) {
            double r = x[i] - model(fitA, i) - model(fitB, i);
            sumSq += r * r;
        }
        return Math.sqrt(sumSq / DUAL_N);
    }

    private float[] subtractModel(float[] x, SineFit fit) {
        float[] out = new float[DUAL_N];
        for (int i = 0; i < DUAL_N; i++) {
            out[i] = (float) (x[i] - model(fit, i));
        }
        return out;
    }

    /** Fit model value at sample {@code i}: a·sin(ωi) + b·cos(ωi) + c. */
    private double model(SineFit fit, int i) {
        double w = 2 * Math.PI * fit.getFrequencyHz() / DUAL_FS * i;
        return fit.getA() * Math.sin(w) + fit.getB() * Math.cos(w) + fit.getC();
    }
}
