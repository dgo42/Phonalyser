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
 * Mains-hum rejection by <b>synchronous subtraction</b>: it learns the
 * recurring hum waveform over one mains period and subtracts it.
 *
 * <p>One mains period contains <em>every</em> harmonic at once, so a single
 * period-locked template removes 50/60 Hz and all its harmonics together —
 * unlike a comb, it carves no per-harmonic notch and gouges no spectrum, and
 * unlike a fixed notch its width is irrelevant.  Any component that is NOT
 * periodic at the mains period (the test tone, broadband noise) does not
 * accumulate in the template and passes through untouched — which is also why
 * this is safe to run ahead of coherent FFT averaging: it removes additive hum
 * without disturbing the test tone's amplitude or phase.
 *
 * <h2>How it works</h2>
 * <p>The template holds the hum sampled at {@value #TEMPLATE_BINS} phase
 * points across one mains period.  Each input sample is mapped to its mains
 * phase (a continuous accumulator advancing by {@code f₀/fs} per sample, so it
 * tracks slow mains drift), the interpolated template value is subtracted, and
 * the template is nudged toward the input by a small step {@value #MU} — an
 * LMS update whose fixed point is the mean of the input at that phase, i.e. the
 * periodic hum.  Non-periodic content averages to zero in the template.
 *
 * <p>{@link #process} removes the whole periodic component (hum + any periodic
 * DC); {@link #processPreservingDc} removes only the AC hum and keeps the
 * operating point.  Successive calls are assumed contiguous in the stream.
 * Not thread-safe — drive it from one thread.
 */
public final class MainsSyncSubtractFilter implements MainsTimeFilter {

    /** Phase points across one mains period.  512 gives an effective template
     *  rate of 512·f₀ (≈ 25.6 kHz at 50 Hz), so any residual image of an
     *  in-band test tone lands ABOVE the audio band rather than inside it (a
     *  128-point template imaged a 1 kHz tone at 6400−1000 ≈ 5.4 kHz).
     *  Requires ≳512 samples per mains period — true at every audio rate. */
    private static final int    TEMPLATE_BINS = 512;
    /** LMS step for the template update — deliberately small so the
     *  (non-periodic) test tone is AVERAGED OUT of the template instead of
     *  leaking into it (a too-large step leaves a tone residual that the
     *  template then re-radiates as an image and partly subtracts from the
     *  fundamental).  Mains is rock-stable, so slow convergence (~5 s) is fine. */
    private static final double MU = 0.001;

    private final int sampleRate;
    private final MainsFrequencyTracker tracker;
    private final double[] template = new double[TEMPLATE_BINS];

    /** Tuned mains fundamental (Hz), or {@code NaN} before the first lock. */
    @Getter
    private double mainsHz = Double.NaN;
    /** Window-start mains phase in periods, [0,1); advanced by the absolute-index
     *  delta each call so it stays aligned across snapshots / overlapping windows. */
    private double phase;
    private long   prevAbsStart;
    private boolean started;

    public MainsSyncSubtractFilter(int sampleRate) {
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

    @Override
    public void process(float[] data, int len, long absStart) {
        filter(data, len, absStart, false);
    }

    @Override
    public void processPreservingDc(float[] data, int len, long absStart) {
        filter(data, len, absStart, true);
    }

    @Override
    public void process(double[] data, int len, long absStart) {
        filterD(data, len, absStart, false);
    }

    @Override
    public void processPreservingDc(double[] data, int len, long absStart) {
        filterD(data, len, absStart, true);
    }

    @Override
    public void reset() {
        Arrays.fill(template, 0.0);
        phase   = 0.0;
        started = false;
    }

    @Override
    public void resetTracking() {
        tracker.resetTracking();
        mainsHz = Double.NaN;
    }

    private void filter(float[] data, int len, long absStart, boolean preserveDc) {
        if (!isTuned()) return;
        len = Math.min(len, data.length);
        final double perSample = mainsHz / sampleRate;     // periods per sample
        final int M = TEMPLATE_BINS;
        // Advance the window-start phase by the gap since the previous call so
        // the template stays aligned across non-contiguous / overlapping blocks.
        if (started) {
            phase += (absStart - prevAbsStart) * perSample;
            phase -= Math.floor(phase);
        } else {
            started = true;
        }
        prevAbsStart = absStart;
        // DC of the template = the operating point to keep when preserving DC.
        double dc = 0.0;
        if (preserveDc) {
            for (double v : template) dc += v;
            dc /= M;
        }
        double p = phase;
        for (int i = 0; i < len; i++) {
            double fb = p * M;
            int b0 = (int) fb;                              // p ∈ [0,1) ⇒ fb ∈ [0,M)
            if (b0 >= M) b0 = M - 1;
            int b1 = (b0 + 1) % M;
            double w = fb - b0;
            double est = template[b0] * (1.0 - w) + template[b1] * w;

            double x = data[i];
            double residual = x - est;                      // drives the template
            double out = preserveDc ? x - (est - dc) : residual;
            data[i] = (float) out;

            // LMS template update (interpolated tap), fixed point = periodic hum.
            double step = MU * residual;
            template[b0] += step * (1.0 - w);
            template[b1] += step * w;

            p += perSample;
            if (p >= 1.0) p -= 1.0;
        }
        // phase holds the WINDOW-START phase; advanced by the inter-call delta
        // above, never by this block's own length (overlapping windows re-use it).
    }

    /** Double-precision twin of {@link #filter} for the FFT capture window. */
    private void filterD(double[] data, int len, long absStart, boolean preserveDc) {
        if (!isTuned()) return;
        len = Math.min(len, data.length);
        final double perSample = mainsHz / sampleRate;
        final int M = TEMPLATE_BINS;
        if (started) {
            phase += (absStart - prevAbsStart) * perSample;
            phase -= Math.floor(phase);
        } else {
            started = true;
        }
        prevAbsStart = absStart;
        double dc = 0.0;
        if (preserveDc) {
            for (double v : template) dc += v;
            dc /= M;
        }
        double p = phase;
        for (int i = 0; i < len; i++) {
            double fb = p * M;
            int b0 = (int) fb;
            if (b0 >= M) b0 = M - 1;
            int b1 = (b0 + 1) % M;
            double w = fb - b0;
            double est = template[b0] * (1.0 - w) + template[b1] * w;
            double x = data[i];
            double residual = x - est;
            data[i] = preserveDc ? x - (est - dc) : residual;
            double step = MU * residual;
            template[b0] += step * (1.0 - w);
            template[b1] += step * w;
            p += perSample;
            if (p >= 1.0) p -= 1.0;
        }
    }
}
