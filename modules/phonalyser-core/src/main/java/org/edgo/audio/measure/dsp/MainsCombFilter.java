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

package org.edgo.audio.measure.dsp;

import java.util.Arrays;

/**
 * Mains-hum rejection via a frequency-tracked IIR comb.
 *
 * <p>A single feedback comb
 * <pre>
 *   H(z) = (1 − z^−N) / (1 − α · z^−N),     N = sampleRate / f₀
 * </pre>
 * places a notch at DC and at every harmonic of {@code f₀}
 * (f₀, 2f₀, 3f₀, ... up to Nyquist) in one structure - the natural fit
 * for mains interference, whose energy sits at 50/60 Hz and its integer
 * harmonics.  {@code α} (0 &lt; α &lt; 1) sets how sharp each notch is;
 * the −3 dB width is uniform across all harmonics and is derived from a
 * caller-supplied bandwidth in Hz.
 *
 * <h2>Frequency tracking</h2>
 * <p>Mains drifts (±0.1-0.5 Hz), and the k-th harmonic drifts k× as far
 * in Hz, so a fixed comb loses depth on the high harmonics.  {@link
 * #track(float[], int)} measures the true mains fundamental from a
 * reference block - auto-detecting whether the source is 50 Hz or 60 Hz
 * - and re-tunes the whole comb by adjusting {@code N}.  Because every
 * notch is locked to {@code k·f₀}, one estimate repositions the entire
 * comb: tracking {@code f₀} to a few mHz keeps even the high harmonics
 * in their notches.
 *
 * <p>{@code N} is generally fractional, so the {@code z^−N} delays use
 * linear interpolation between the two straddling integer taps.  Linear
 * interpolation slightly reduces notch depth at the highest harmonics
 * (its mild treble droop mistunes them a touch); for mains - where the
 * significant harmonics are below ~1 kHz - this is negligible.  Swap in
 * a first-order all-pass fractional delay if deep rejection near Nyquist
 * is ever needed.
 *
 * <h2>Usage</h2>
 * <pre>
 *   MainsCombFilter f = new MainsCombFilter(48000, 2.0);   // 2 Hz notches
 *   f.track(referenceBlock, refLen);                       // lock to 50/60
 *   f.process(audioBlock, len);                            // filter in place
 * </pre>
 * Typical streaming use re-tracks occasionally (mains drift is slow) and
 * processes every block; the comb's delay-line state persists across
 * {@code process} calls.  Not thread-safe - drive it from one thread.
 */
public final class MainsCombFilter implements MainsTimeFilter {

    /** Default −3 dB notch bandwidth (Hz) for the mains comb - the value used
     *  by the FFT / scope combs throughout the app.  Callers pass it to the
     *  constructor (a different width can still be chosen per instance). */
    public static final double DEFAULT_NOTCH_BANDWIDTH_HZ = 2.5;

    /** Lowest fundamental the comb will tune to (Hz) - also sizes the delay
     *  line.  Shared with the other mains filters via the tracker. */
    private static final double MIN_MAINS_HZ = MainsFrequencyTracker.MIN_MAINS_HZ;
    /** Highest fundamental the comb will tune to (Hz). */
    private static final double MAX_MAINS_HZ = MainsFrequencyTracker.MAX_MAINS_HZ;

    private final int    sampleRate;
    /** Per-sample-delay pole radius ρ = exp(−π·BW/fs); α = ρ^N. */
    private final double rho;

    /** x and y delay lines (circular).  Length covers the largest N
     *  (lowest f₀) plus the two interpolation taps. */
    private final double[] xBuf;
    private final double[] yBuf;
    private final int      bufLen;
    private int            pos;          // next write index

    private double mainsHz = Double.NaN; // current tuning (NaN until tuned)
    private int    nInt;                 // floor(N)
    private double nFrac;                // N − floor(N)
    private double alpha;                // ρ^N

    /** Shared mains-frequency detector - survives {@link #reset} (only the
     *  delay lines are cleared there). */
    private final MainsFrequencyTracker tracker;

    /**
     * @param sampleRate  capture sample rate (Hz)
     * @param notchBandwidthHz  −3 dB width of each harmonic notch (Hz);
     *        smaller = deeper/narrower (less damage to nearby signal,
     *        but relies on accurate tracking).  Typical 1-4 Hz.
     */
    public MainsCombFilter(int sampleRate, double notchBandwidthHz) {
        if (sampleRate <= 0) throw new IllegalArgumentException("sampleRate must be > 0");
        if (!(notchBandwidthHz > 0)) throw new IllegalArgumentException("bandwidth must be > 0");
        this.sampleRate  = sampleRate;
        this.tracker     = new MainsFrequencyTracker(sampleRate);
        this.rho = Math.exp(-Math.PI * notchBandwidthHz / sampleRate);
        // Largest N is at the lowest tunable mains frequency.
        int nMax = (int) Math.ceil(sampleRate / MIN_MAINS_HZ);
        this.bufLen = nMax + 4;
        this.xBuf = new double[bufLen];
        this.yBuf = new double[bufLen];
    }

    /** Current comb fundamental in Hz, or {@code NaN} before the first
     *  successful {@link #track}/{@link #retune}. */
    public double getMainsHz() {
        return mainsHz;
    }

    /** True once the comb has been tuned to a mains frequency. */
    public boolean isTuned() {
        return !Double.isNaN(mainsHz);
    }

    /** Directly tunes the comb fundamental, bypassing detection.  The
     *  frequency is clamped to {@code [MIN_MAINS_HZ, MAX_MAINS_HZ]}.
     *  Delay-line state is preserved (a re-tune is a smooth glide, not a
     *  reset). */
    public void retune(double f0Hz) {
        double f0 = Math.max(MIN_MAINS_HZ, Math.min(MAX_MAINS_HZ, f0Hz));
        double n  = sampleRate / f0;
        this.nInt   = (int) Math.floor(n);
        this.nFrac  = n - nInt;
        this.alpha  = Math.pow(rho, n);
        this.mainsHz = f0;
    }

    /** Clears the delay-line state (e.g. on a capture restart) without
     *  changing the current tuning. */
    public void reset() {
        Arrays.fill(xBuf, 0.0);
        Arrays.fill(yBuf, 0.0);
        pos = 0;
    }

    /** Drops the frequency lock and untunes the comb so the next {@link #track}
     *  re-detects from scratch and snaps immediately (no EWMA inertia from the
     *  old lock).  Unlike {@link #reset} (which keeps the lock), use this when
     *  the analysis context is reset and the mains estimate should start
     *  fresh. */
    public void resetTracking() {
        tracker.resetTracking();
        mainsHz = Double.NaN;
    }

    /** Re-estimates the mains fundamental from {@code ref} via the shared
     *  {@link MainsFrequencyTracker} and retunes the comb; returns the locked
     *  frequency (Hz), or the held lock when nothing confident is found. */
    public double track(double[] ref, int len) {
        double f0 = tracker.track(ref, len);
        if (!Double.isNaN(f0)) retune(f0);
        return f0;
    }

    public double track(float[] ref, int len) {
        double f0 = tracker.track(ref, len);
        if (!Double.isNaN(f0)) retune(f0);
        return f0;
    }

    /**
     * Filters {@code data} in place (length {@code len}).  No-op until
     * the comb has been tuned (call {@link #track} or {@link #retune}
     * first) - an untuned filter passes the signal through unchanged.
     * {@code absStart} is ignored: the comb carries phase in its delay line.
     */
    public void process(float[] data, int len, long absStart) {
        if (!isTuned()) return;
        len = Math.min(len, data.length);
        final double a = alpha;
        final double g = nFrac;
        final double g1 = 1.0 - g;
        for (int i = 0; i < len; i++) {
            double in = data[i];
            int iA = pos - nInt;       // delay nInt
            int iB = iA - 1;           // delay nInt + 1
            if (iA < 0) iA += bufLen;
            if (iB < 0) iB += bufLen;
            double xN = g1 * xBuf[iA] + g * xBuf[iB];
            double yN = g1 * yBuf[iA] + g * yBuf[iB];
            double y  = in - xN + a * yN;
            xBuf[pos] = in;
            yBuf[pos] = y;
            pos++;
            if (pos >= bufLen) pos = 0;
            data[i] = (float) y;
        }
    }

    /**
     * Like {@link #process}, but preserves the block's DC level: the comb
     * inherently notches DC (its zero at k=0), which would re-centre a
     * DC-coupled trace and zero its mean.  This variant subtracts the
     * block mean, combs the zero-mean signal, then adds the mean back - so
     * only the mains hum (50/60 Hz + harmonics) is removed and the DC
     * operating point is left intact.  Used by the oscilloscope, where the
     * displayed amplitude and {@code Vmean} should reflect "hum removed",
     * not "hum and DC removed".  No-op until tuned.
     */
    public void processPreservingDc(float[] data, int len, long absStart) {
        if (!isTuned()) return;
        len = Math.min(len, data.length);
        if (len <= 0) return;
        double sum = 0.0;
        for (int i = 0; i < len; i++) sum += data[i];
        float mean = (float) (sum / len);
        for (int i = 0; i < len; i++) data[i] -= mean;
        process(data, len, absStart);
        for (int i = 0; i < len; i++) data[i] += mean;
    }

    @Override
    public void process(double[] data, int len, long absStart) {
        if (!isTuned()) return;
        len = Math.min(len, data.length);
        final double a = alpha;
        final double g = nFrac;
        final double g1 = 1.0 - g;
        for (int i = 0; i < len; i++) {
            double in = data[i];
            int iA = pos - nInt;
            int iB = iA - 1;
            if (iA < 0) iA += bufLen;
            if (iB < 0) iB += bufLen;
            double xN = g1 * xBuf[iA] + g * xBuf[iB];
            double yN = g1 * yBuf[iA] + g * yBuf[iB];
            double y  = in - xN + a * yN;
            xBuf[pos] = in;
            yBuf[pos] = y;
            pos++;
            if (pos >= bufLen) pos = 0;
            data[i] = y;
        }
    }

    @Override
    public void processPreservingDc(double[] data, int len, long absStart) {
        if (!isTuned()) return;
        len = Math.min(len, data.length);
        if (len <= 0) return;
        double sum = 0.0;
        for (int i = 0; i < len; i++) sum += data[i];
        double mean = sum / len;
        for (int i = 0; i < len; i++) data[i] -= mean;
        process(data, len, absStart);
        for (int i = 0; i < len; i++) data[i] += mean;
    }
}
