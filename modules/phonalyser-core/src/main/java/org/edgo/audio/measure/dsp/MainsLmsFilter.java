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

import lombok.Getter;

/**
 * Mains-hum rejection by an <b>adaptive LMS line canceller</b>: it models the
 * hum as a sum of sinusoids at the mains fundamental and its harmonics, adapts
 * their amplitudes and phases to the measured signal, and subtracts the
 * estimate.
 *
 * <p>Each harmonic <i>k</i> has a quadrature reference pair
 * (cos&nbsp;<i>kφ</i>, sin&nbsp;<i>kφ</i>) and two adaptive weights; the
 * estimate is their weighted sum and the output is the input minus the
 * estimate.  A least-mean-squares update steers the weights so the estimate
 * converges to whatever sinusoid sits at each harmonic — including a drifting
 * amplitude/phase — while everything off those exact frequencies passes through
 * untouched.  Because the model is purely sinusoidal (no DC term), the DC /
 * operating point is preserved either way, and the test tone (off the mains
 * grid) is left intact — safe to run ahead of coherent FFT averaging.
 *
 * <p>The mains phase is a continuous accumulator advancing by {@code f₀/fs}
 * per sample (so it follows slow drift), and the per-harmonic references are
 * built by incremental rotation rather than per-sample trig.  Successive calls
 * are assumed contiguous in the stream.  Not thread-safe — drive from one
 * thread.
 */
public final class MainsLmsFilter implements MainsTimeFilter {

    /** Top of the modelled hum band (Hz); harmonics above this are not cancelled. */
    private static final double MAX_HARMONIC_HZ = 1000.0;
    /** Hard cap on harmonic count (sizes the weight arrays). */
    private static final int    MAX_HARMONICS = (int) Math.ceil(MAX_HARMONIC_HZ / MainsFrequencyTracker.MIN_MAINS_HZ);
    /** LMS step.  The canceller's notch BANDWIDTH at each harmonic is
     *  proportional to this, so it is kept small to remove the hum LINE without
     *  gouging a wide notch in the surrounding floor.  Mains is rock-stable, so
     *  the resulting slow adaptation is fine. */
    private static final double MU = 5.0e-4;

    private final int sampleRate;
    private final MainsFrequencyTracker tracker;
    private final double[] weightCos = new double[MAX_HARMONICS + 1];   // 1-based by harmonic
    private final double[] weightSin = new double[MAX_HARMONICS + 1];

    /** Tuned mains fundamental (Hz), or {@code NaN} before the first lock. */
    @Getter
    private double mainsHz = Double.NaN;
    /** Window-start mains phase in radians, [0,2π); advanced by the absolute-index
     *  delta each call so the references stay aligned across snapshots / overlap. */
    private double phase;
    private long   prevAbsStart;
    private boolean started;

    public MainsLmsFilter(int sampleRate) {
        if (sampleRate <= 0) throw new IllegalArgumentException("sampleRate must be > 0");
        this.sampleRate = sampleRate;
        this.tracker    = new MainsFrequencyTracker(sampleRate);
    }

    @Override
    public double track(double[] ref, int len) {
        double f0 = tracker.track(ref, len);
        if (!Double.isNaN(f0)) mainsHz = f0;
        return f0;
    }

    @Override
    public double track(float[] ref, int len) {
        double f0 = tracker.track(ref, len);
        if (!Double.isNaN(f0)) mainsHz = f0;
        return f0;
    }

    @Override
    public boolean isTuned() {
        return !Double.isNaN(mainsHz);
    }

    /** Sinusoidal model carries no DC term, so cancelling never touches the
     *  operating point — both entry points share one path. */
    @Override
    public void process(float[] data, int len, long absStart) {
        filter(data, len, absStart);
    }

    @Override
    public void processPreservingDc(float[] data, int len, long absStart) {
        filter(data, len, absStart);
    }

    @Override
    public void process(double[] data, int len, long absStart) {
        filterD(data, len, absStart);
    }

    @Override
    public void processPreservingDc(double[] data, int len, long absStart) {
        filterD(data, len, absStart);
    }

    @Override
    public void reset() {
        Arrays.fill(weightCos, 0.0);
        Arrays.fill(weightSin, 0.0);
        phase   = 0.0;
        started = false;
    }

    @Override
    public void resetTracking() {
        tracker.resetTracking();
        mainsHz = Double.NaN;
        reset();
    }

    private void filter(float[] data, int len, long absStart) {
        if (!isTuned()) return;
        len = Math.min(len, data.length);
        final double dPhase = 2.0 * Math.PI * mainsHz / sampleRate;
        // Advance the window-start phase by the gap since the previous call so
        // the references stay aligned across non-contiguous / overlapping blocks.
        if (started) {
            phase += (absStart - prevAbsStart) * dPhase;
            phase %= 2.0 * Math.PI;
            if (phase < 0) phase += 2.0 * Math.PI;
        } else {
            started = true;
        }
        prevAbsStart = absStart;
        // Cancel every harmonic whose frequency is still inside the hum band.
        final int kMax = Math.max(1, Math.min(MAX_HARMONICS, (int) Math.floor(MAX_HARMONIC_HZ / mainsHz)));
        final double[] wc = weightCos, ws = weightSin;
        double ph = phase;
        for (int i = 0; i < len; i++) {
            double c1 = Math.cos(ph), s1 = Math.sin(ph);
            // Estimate the hum and accumulate the per-harmonic references by
            // incremental rotation: (c_k, s_k) = rotate (c_{k-1}, s_{k-1}) by φ.
            double ck = c1, sk = s1;
            double est = 0.0;
            for (int k = 1; k <= kMax; k++) {
                est += wc[k] * ck + ws[k] * sk;
                if (k < kMax) {
                    double cn = ck * c1 - sk * s1;
                    sk = sk * c1 + ck * s1;
                    ck = cn;
                }
            }
            double e = data[i] - est;
            data[i] = (float) e;

            // LMS weight update with the same references (rebuild by rotation).
            double step = MU * e;
            ck = c1; sk = s1;
            for (int k = 1; k <= kMax; k++) {
                wc[k] += step * ck;
                ws[k] += step * sk;
                if (k < kMax) {
                    double cn = ck * c1 - sk * s1;
                    sk = sk * c1 + ck * s1;
                    ck = cn;
                }
            }
            ph += dPhase;
            if (ph >= 2.0 * Math.PI) ph -= 2.0 * Math.PI;
        }
        // phase holds the WINDOW-START phase; overlapping windows re-use it, so
        // it is advanced only by the inter-call delta above.
    }

    /** Double-precision twin of {@link #filter} for the FFT capture window. */
    private void filterD(double[] data, int len, long absStart) {
        if (!isTuned()) return;
        len = Math.min(len, data.length);
        final double dPhase = 2.0 * Math.PI * mainsHz / sampleRate;
        if (started) {
            phase += (absStart - prevAbsStart) * dPhase;
            phase %= 2.0 * Math.PI;
            if (phase < 0) phase += 2.0 * Math.PI;
        } else {
            started = true;
        }
        prevAbsStart = absStart;
        final int kMax = Math.max(1, Math.min(MAX_HARMONICS, (int) Math.floor(MAX_HARMONIC_HZ / mainsHz)));
        final double[] wc = weightCos, ws = weightSin;
        double ph = phase;
        for (int i = 0; i < len; i++) {
            double c1 = Math.cos(ph), s1 = Math.sin(ph);
            double ck = c1, sk = s1;
            double est = 0.0;
            for (int k = 1; k <= kMax; k++) {
                est += wc[k] * ck + ws[k] * sk;
                if (k < kMax) {
                    double cn = ck * c1 - sk * s1;
                    sk = sk * c1 + ck * s1;
                    ck = cn;
                }
            }
            double e = data[i] - est;
            data[i] = e;
            double step = MU * e;
            ck = c1; sk = s1;
            for (int k = 1; k <= kMax; k++) {
                wc[k] += step * ck;
                ws[k] += step * sk;
                if (k < kMax) {
                    double cn = ck * c1 - sk * s1;
                    sk = sk * c1 + ck * s1;
                    ck = cn;
                }
            }
            ph += dPhase;
            if (ph >= 2.0 * Math.PI) ph -= 2.0 * Math.PI;
        }
    }
}
