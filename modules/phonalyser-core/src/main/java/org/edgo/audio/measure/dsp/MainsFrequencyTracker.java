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
 * Live mains-fundamental estimator shared by every mains-rejection filter.
 *
 * <p>Scans the 50 Hz and 60 Hz bands (each with its 2nd harmonic) of a
 * reference block, discriminates 50 vs 60 by total band energy, and locks to
 * the stronger source — refining the estimate to a few mHz by parabolic
 * interpolation.  The lock is exponentially smoothed (mains drifts only
 * ±0.1–0.5 Hz, so heavy averaging resists per-window jitter and the occasional
 * mis-lock) and outlier detections are rejected; a window with no detected
 * line holds the existing lock rather than dropping it.
 *
 * <p>Because rectifier / diode-bridge loads carry most of their hum in the 2nd
 * harmonic, the lock fires when EITHER the fundamental or its 2nd harmonic is a
 * genuine line, and the fundamental is then derived from whichever is cleaner.
 *
 * <p>Extracted from {@code MainsCombFilter} so the comb, the synchronous
 * subtractor and the LMS canceller all detect the mains frequency the same
 * way.  Not thread-safe — drive it from one thread.
 */
public final class MainsFrequencyTracker {

    /** Lowest fundamental the trackers will accept (Hz). */
    public static final double MIN_MAINS_HZ = 45.0;
    /** Highest fundamental the trackers will accept (Hz). */
    public static final double MAX_MAINS_HZ = 65.0;

    /** Half-width (Hz) of each detection band scanned around 50 / 60. */
    private static final double DETECT_SPAN_HZ = 2.0;
    /** Coarse scan step (Hz) inside each detection band. */
    private static final double DETECT_STEP_HZ = 0.2;
    /** A mains line is accepted only when its Goertzel power beats the
     *  scanned-baseline median by this factor (≈ 6 dB). */
    private static final double LOCK_RATIO = 4.0;
    /** EWMA weight on the existing lock (vs. a fresh detection).  High, because
     *  mains is very stable (50/60 Hz ± a few mHz over seconds). */
    private static final double LOCK_SMOOTH = 0.97;
    /** A detection farther than this from the current lock is an outlier. */
    private static final double MAX_LOCK_DRIFT_HZ = 0.5;

    private final int sampleRate;

    /** Exponentially-smoothed lock frequency, or {@code NaN} until first lock. */
    @Getter
    private double lockHz = Double.NaN;

    /** Scratch for the windowed reference; grown on demand. */
    private double[] windowScratch = new double[0];
    /** Reusable single-precision scratch for the {@code double[]} overload. */
    private float[]  narrowScratch = new float[0];

    public MainsFrequencyTracker(int sampleRate) {
        if (sampleRate <= 0) throw new IllegalArgumentException("sampleRate must be > 0");
        this.sampleRate = sampleRate;
    }

    /** Drops the lock so the next {@link #track} re-detects from scratch and
     *  snaps immediately (no EWMA inertia from the old lock). */
    public void resetTracking() {
        lockHz = Double.NaN;
    }

    /** Double-precision reference overload — narrows into reusable scratch and
     *  reuses the single-precision estimator (the estimate is insensitive to
     *  float vs double). */
    public double track(double[] ref, int len) {
        if (ref == null) return Double.NaN;
        len = Math.min(len, ref.length);
        if (narrowScratch.length < len) narrowScratch = new float[len];
        for (int i = 0; i < len; i++) narrowScratch[i] = (float) ref[i];
        return track(narrowScratch, len);
    }

    /**
     * Estimates the mains fundamental from {@code ref}, returning the smoothed
     * lock (Hz) — or the held lock (possibly {@code NaN}) when no confident
     * line is found this window.
     */
    public double track(float[] ref, int len) {
        if (ref == null) return lockHz;
        len = Math.min(len, ref.length);
        // Need a few mains cycles for a meaningful estimate.
        if (len < (int) (4 * sampleRate / MIN_MAINS_HZ)) return lockHz;

        if (windowScratch.length < len) windowScratch = new double[len];
        double[] w = windowScratch;
        // Hann window to suppress leakage from the (usually dominant) test
        // signal into the mains bands.
        double norm = 2.0 * Math.PI / (len - 1);
        for (int i = 0; i < len; i++) {
            w[i] = ref[i] * 0.5 * (1.0 - Math.cos(norm * i));
        }

        // Score 50 vs 60 by fundamental AND 2nd harmonic (50→50/100, 60→60/120);
        // H2 bands use twice the span (the harmonic drifts 2× as far in Hz).
        BandScan h1a = scanBand(w, len, 50.0,  DETECT_SPAN_HZ);
        BandScan h2a = scanBand(w, len, 100.0, 2 * DETECT_SPAN_HZ);
        BandScan h1b = scanBand(w, len, 60.0,  DETECT_SPAN_HZ);
        BandScan h2b = scanBand(w, len, 120.0, 2 * DETECT_SPAN_HZ);

        boolean is50 = (h1a.peakPower + h2a.peakPower) >= (h1b.peakPower + h2b.peakPower);
        BandScan h1 = is50 ? h1a : h1b;
        BandScan h2 = is50 ? h2a : h2b;

        // Lock when EITHER harmonic is a genuine line; on a miss, hold the lock.
        double baseline = scannedBaseline(h1a, h1b, h2a, h2b);
        if (baseline <= 0 || Math.max(h1.peakPower, h2.peakPower) < LOCK_RATIO * baseline) return lockHz;

        // Derive f0 from the cleaner harmonic (H2/2 also halves the Hz error).
        double f0 = (h2.peakPower > h1.peakPower) ? h2.refinedHz / 2.0 : h1.refinedHz;
        if (Double.isNaN(lockHz)) {
            lockHz = f0;
        } else if (Math.abs(f0 - lockHz) <= MAX_LOCK_DRIFT_HZ) {
            lockHz = LOCK_SMOOTH * lockHz + (1.0 - LOCK_SMOOTH) * f0;
        }
        return lockHz;
    }

    // ─── Detection helpers ───────────────────────────────────────────────────

    /** One band scan's result: the sub-step-refined peak plus the raw power
     *  grid, so {@link #scannedBaseline} can reuse the Goertzels. */
    private record BandScan(double refinedHz, double peakPower,
                            double loHz, double[] mags) {}

    /** Scans {@code ±spanHz} around {@code centerHz}; the peak is refined to
     *  sub-step by parabolic interpolation on log-power. */
    private BandScan scanBand(double[] w, int len, double centerHz, double spanHz) {
        double lo = centerHz - spanHz;
        int m = (int) Math.round(2 * spanHz / DETECT_STEP_HZ) + 1;
        double[] mag = new double[m];
        int bestK = 0;
        for (int k = 0; k < m; k++) {
            mag[k] = goertzelPower(w, len, lo + k * DETECT_STEP_HZ);
            if (mag[k] > mag[bestK]) bestK = k;
        }
        double refinedHz = lo + bestK * DETECT_STEP_HZ;
        if (bestK > 0 && bestK < m - 1) {
            double a = Math.log(mag[bestK - 1] + 1e-30);
            double b = Math.log(mag[bestK]     + 1e-30);
            double c = Math.log(mag[bestK + 1] + 1e-30);
            double denom = a - 2 * b + c;
            double delta = (Math.abs(denom) < 1e-15) ? 0.0 : 0.5 * (a - c) / denom;
            if (delta < -0.5) delta = -0.5;
            if (delta >  0.5) delta =  0.5;
            refinedHz = lo + (bestK + delta) * DETECT_STEP_HZ;
        }
        return new BandScan(refinedHz, mag[bestK], lo, mag);
    }

    /** Median Goertzel power across the four detection bands — a robust floor
     *  the strongest line must clear to count as real mains. */
    private double scannedBaseline(BandScan h1a, BandScan h1b,
                                   BandScan h2a, BandScan h2b) {
        int m = (int) Math.round(2 * DETECT_SPAN_HZ / DETECT_STEP_HZ) + 1;
        double[] all = new double[4 * m];
        int idx = 0;
        idx = copyBandPowers(all, idx, h1a, 50.0,  m);
        idx = copyBandPowers(all, idx, h1b, 60.0,  m);
        idx = copyBandPowers(all, idx, h2a, 100.0, m);
        idx = copyBandPowers(all, idx, h2b, 120.0, m);
        if (idx == 0) return 0.0;
        Arrays.sort(all, 0, idx);
        return all[idx / 2];
    }

    /** Copies the {@code m} powers spanning ±{@link #DETECT_SPAN_HZ} around
     *  {@code centerHz} from a scan's grid into {@code out}; returns the
     *  advanced fill index. */
    private int copyBandPowers(double[] out, int idx, BandScan scan,
                               double centerHz, int m) {
        double lo = centerHz - DETECT_SPAN_HZ;
        int start = (int) Math.round((lo - scan.loHz()) / DETECT_STEP_HZ);
        for (int k = 0; k < m; k++) {
            int src = start + k;
            if (src >= 0 && src < scan.mags().length) {
                out[idx++] = scan.mags()[src];
            }
        }
        return idx;
    }

    /** Goertzel single-frequency power |X(f)|² over a windowed block. */
    private double goertzelPower(double[] w, int len, double freqHz) {
        double omega = 2.0 * Math.PI * freqHz / sampleRate;
        double coeff = 2.0 * Math.cos(omega);
        double s1 = 0.0, s2 = 0.0;
        for (int i = 0; i < len; i++) {
            double s = w[i] + coeff * s1 - s2;
            s2 = s1;
            s1 = s;
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2;
    }
}
