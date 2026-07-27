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

package org.edgo.audio.measure.dsp;

import java.util.Arrays;

/**
 * Chebyshev Type I low-pass filter — a cascade of 2nd-order sections, used
 * to strip high-frequency spikes (e.g. switching / RF pickup above the
 * audio band) from a captured channel before it is displayed or measured.
 *
 * <p>The filter is a Chebyshev Type I of the requested (even) order with
 * {@value #RIPPLE_DB} dB pass-band ripple — chosen over Butterworth for its
 * substantially steeper transition, so spikes only a little above the
 * cutoff are rejected hard.  Each conjugate analog pole pair is mapped to a
 * digital biquad by the bilinear transform (with frequency pre-warping) and
 * run in Direct-Form-II transposed.  Sections are normalised to unity DC
 * gain (0 dB), so low-frequency signals keep their amplitude exactly and
 * the equiripple sits in the upper pass band where the measured signal
 * isn't.
 *
 * <p>When the requested cutoff is at or above Nyquist the filter is
 * {@linkplain #isActive() inactive} and {@link #process} passes the
 * signal through untouched — so an 80 kHz cutoff is a no-op at 48/96 kHz
 * sample rates (where there is nothing above 80 kHz to remove) and only
 * does work at the high sample rates where such spikes actually appear.
 *
 * <p>Stateful and single-threaded, like {@link MainsCombFilter}.  The
 * Butterworth settles within a handful of samples, so callers that
 * {@link #reset} before each block (the oscilloscope's per-paint model)
 * incur no visible edge transient.
 */
public final class LowPassFilter {

    /** Chebyshev Type I pass-band ripple (dB).  Small enough to keep
     *  amplitude error negligible, large enough for a steep transition. */
    private static final double RIPPLE_DB = 0.5;

    private final boolean active;
    /** Per-section normalised biquad coefficients (a0 folded to 1). */
    private final double[] b0, b1, b2, a1, a2;
    /** Per-section DF2T state. */
    private final double[] z1, z2;

    /**
     * @param sampleRate capture sample rate (Hz)
     * @param cutoffHz   −3 dB corner (Hz)
     * @param order      Butterworth order; rounded up to the next even number
     */
    public LowPassFilter(int sampleRate, double cutoffHz, int order) {
        if (sampleRate <= 0) throw new IllegalArgumentException("sampleRate must be > 0");
        int even = Math.max(2, order + (order & 1));   // round up to even, ≥ 2
        int sections = even / 2;
        this.b0 = new double[sections];
        this.b1 = new double[sections];
        this.b2 = new double[sections];
        this.a1 = new double[sections];
        this.a2 = new double[sections];
        this.z1 = new double[sections];
        this.z2 = new double[sections];
        // Inactive (pass-through) when the cutoff can't do anything useful.
        this.active = cutoffHz > 0 && cutoffHz < sampleRate / 2.0;
        if (!active) return;

        // Chebyshev Type I prototype: pole spread set by the ripple factor ε.
        double eps    = Math.sqrt(Math.pow(10.0, RIPPLE_DB / 10.0) - 1.0);
        double invEps = 1.0 / eps;
        double v0     = Math.log(invEps + Math.sqrt(invEps * invEps + 1.0)) / even;   // asinh(1/ε)/N
        double sinhv  = Math.sinh(v0);
        double coshv  = Math.cosh(v0);
        double kWarp  = Math.tan(Math.PI * cutoffHz / sampleRate);   // pre-warped analog cutoff
        for (int k = 0; k < sections; k++) {
            double theta = Math.PI * (2.0 * k + 1.0) / (2.0 * even);
            // Normalised analog prototype pole (cutoff = 1), then denormalised
            // to the warped cutoff: p' = (σ + jω)·kWarp.
            double sp = -sinhv * Math.sin(theta) * kWarp;   // real part (negative)
            double op =  coshv * Math.cos(theta) * kWarp;   // imag part
            double c  = sp * sp + op * op;                  // |p'|²
            double d  = -2.0 * sp;                           // > 0
            // Bilinear transform of H(s) = c / (s² + d·s + c), numerator
            // c·(1+z⁻¹)², giving unity DC gain per section.
            double a0 = 1.0 + d + c;
            b0[k] =  c            / a0;
            b1[k] =  2.0 * c      / a0;
            b2[k] =  c            / a0;
            a1[k] =  2.0 * (c - 1.0) / a0;
            a2[k] = (1.0 - d + c) / a0;
        }
    }

    /** True when the cutoff is below Nyquist and the filter actually
     *  processes; false means {@link #process} is a pass-through. */
    public boolean isActive() {
        return active;
    }

    /** Clears the filter state (call before each block when re-reading
     *  overlapping windows, as the oscilloscope does). */
    public void reset() {
        Arrays.fill(z1, 0.0);
        Arrays.fill(z2, 0.0);
    }

    /** Filters {@code data} in place through every section.  No-op when
     *  {@link #isActive()} is false. */
    public void process(float[] data, int len) {
        if (!active) return;
        len = Math.min(len, data.length);
        int sections = b0.length;
        for (int i = 0; i < len; i++) {
            double x = data[i];
            for (int s = 0; s < sections; s++) {
                double y = b0[s] * x + z1[s];
                z1[s] = b1[s] * x - a1[s] * y + z2[s];
                z2[s] = b2[s] * x - a2[s] * y;
                x = y;
            }
            data[i] = (float) x;
        }
    }
}
