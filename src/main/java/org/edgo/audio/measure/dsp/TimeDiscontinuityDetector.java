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

/**
 * Time-domain waveform-discontinuity detector, shared by the oscilloscope's
 * glitch trigger and the FFT worker's time-domain rejection gate — so both
 * instruments agree on what counts as a damaged block (dropped-sample DAC
 * gaps, ADC-side cutoffs, phase-jump splices).
 *
 * <p>Each sample is predicted from the sinusoid recurrence
 * {@code d[i] ≈ a·d[i−1] − d[i−2]} with {@code a = 2·cos ω} estimated from the
 * window itself by least squares.  The recurrence is EXACT for a clean tone at
 * any frequency, so the prediction-error baseline is the noise floor — unlike
 * a plain second difference (the {@code a = 2} special case), whose baseline
 * is the tone's own curvature {@code A·ω²} and which therefore goes deaf as
 * the signal frequency rises (at 20 kHz / 384 kHz the curvature threshold
 * reaches ~0.5·A, hiding every glitch smaller than a full-peak drop).  The
 * rare glitch samples cannot bias the least-squares estimate or the mean-based
 * threshold.  Frequency-domain counterpart: {@link SpectralDiscontinuityDetector}.
 */
public final class TimeDiscontinuityDetector {

    /**
     * Threshold = this factor × the window's mean |prediction error|.  For a
     * clean tone the error is noise-limited, where 8× ≈ 6.4 σ — broadband
     * noise never fires; a splice breaks the prediction by a large fraction
     * of the amplitude, decades above.  Residual harmonics / a second tone
     * raise the baseline (they don't fit a single-tone recurrence), and the
     * threshold self-scales with them.
     */
    private static final float THRESHOLD_FACTOR = 8.0f;

    /** Discontinuity bursts closer than this (seconds) belong to ONE glitch — a
     *  dropout's entry and recovery boundaries (the observed USB gaps run
     *  120–160 µs) merge, so a caller can anchor on the glitch's start or end
     *  as a whole rather than on each boundary separately. */
    public static final double MERGE_SECONDS = 0.001;

    /**
     * Finds the rightmost discontinuity in {@code data[from .. to)}.  Fires on
     * amplitude steps AND slope breaks anywhere on the waveform (peaks, flanks,
     * zero crossings alike) regardless of direction.  Consecutive above-threshold
     * samples form a burst; bursts closer than {@code mergeSamples} form ONE
     * glitch (a dropout = entry burst + body + recovery burst).
     *
     * @param anchorStart {@code true} → return the last clean sample before the
     *        glitch, {@code false} → the first settled sample after it
     * @param omega the KNOWN fundamental as {@code 2π·f/sampleRate} — pins the
     *        recurrence coefficient exactly, so the tone nulls to the noise floor
     *        even when an in-window glitch or harmonics would bias the estimate;
     *        {@code NaN} → least-squares self-estimate from the window (external /
     *        unknown signals)
     * @return that index for the RIGHTMOST glitch, or {@code -1.0} when nothing
     *         qualifies (including flat / silent input)
     */
    public double findDiscontinuity(float[] data, int from, int to,
                                    boolean anchorStart, int mergeSamples, double omega) {
        int start = Math.max(from, 2);
        if (to - start < 3) return -1.0;
        float a;
        if (Double.isNaN(omega)) {
            // Least-squares estimate of the recurrence coefficient a = 2·cos ω:
            // minimising Σ (d[i] − a·d[i−1] + d[i−2])² over the window gives
            // a = Σ d[i−1]·(d[i] + d[i−2]) / Σ d[i−1]².  Exact for a clean tone;
            // clamped to the valid sinusoid range (a degenerate window falls back
            // to a = 2, the plain second difference).
            double num = 0;
            double den = 0;
            for (int i = start; i < to; i++) {
                num += (double) data[i - 1] * ((double) data[i] + data[i - 2]);
                den += (double) data[i - 1] * data[i - 1];
            }
            a = (den > 0) ? (float) Math.max(-2.0, Math.min(2.0, num / den)) : 2f;
        } else {
            a = (float) (2.0 * Math.cos(omega));
        }
        double sumAbs = 0;
        for (int i = start; i < to; i++) {
            sumAbs += Math.abs(data[i] - a * data[i - 1] + data[i - 2]);
        }
        double meanAbs = sumAbs / (to - start);
        if (meanAbs <= 0) return -1.0;
        float threshold = (float) (THRESHOLD_FACTOR * meanAbs);
        // Track only the rightmost glitch: a burst either extends it (within the
        // merge window) or starts a new one that replaces it.
        int glitchStart = -1;   // first burst's first error index
        int glitchEnd   = -1;   // one past the last burst's last error index
        int burstStart  = -1;
        for (int i = start; i <= to; i++) {           // i == to closes a trailing burst
            boolean above = i < to
                    && Math.abs(data[i] - a * data[i - 1] + data[i - 2]) > threshold;
            if (above) {
                if (burstStart < 0) burstStart = i;
                continue;
            }
            if (burstStart >= 0) {
                if (glitchEnd >= 0 && burstStart - glitchEnd <= mergeSamples) {
                    glitchEnd = i;                    // same glitch — extend to this burst
                } else {
                    glitchStart = burstStart;         // a new (rightmost) glitch
                    glitchEnd   = i;
                }
                burstStart = -1;
            }
        }
        if (glitchStart < 0) return -1.0;
        // d[glitchStart-1] = last sample still on the old trend; d[glitchEnd-1] =
        // first sample that fits the local prediction again after the glitch.
        return anchorStart ? glitchStart - 1 : glitchEnd - 1;
    }

    /** Rejection-gate convenience: whether {@code data[0 .. n)} contains any
     *  discontinuity.  Burst merging is irrelevant for a yes/no verdict;
     *  {@code omega} as in {@link #findDiscontinuity}. */
    public boolean detect(float[] data, int n, double omega) {
        return findDiscontinuity(data, 2, n, true, 0, omega) >= 0;
    }

    /**
     * {@code double[]} twin of {@link #detect(float[], int, double)} for the FFT
     * worker, whose capture window stays in double precision to preserve the
     * measurement floor (converting a multi-million-sample window to float per
     * tick would cost an allocation + copy for nothing).  Same model: sinusoid-
     * recurrence prediction error vs {@link #THRESHOLD_FACTOR} × mean |error|.
     */
    public boolean detect(double[] data, int n, double omega) {
        int start = 2;
        if (n - start < 3) return false;
        double a;
        if (Double.isNaN(omega)) {
            double num = 0;
            double den = 0;
            for (int i = start; i < n; i++) {
                num += data[i - 1] * (data[i] + data[i - 2]);
                den += data[i - 1] * data[i - 1];
            }
            a = (den > 0) ? Math.max(-2.0, Math.min(2.0, num / den)) : 2.0;
        } else {
            a = 2.0 * Math.cos(omega);
        }
        double sumAbs = 0;
        for (int i = start; i < n; i++) {
            sumAbs += Math.abs(data[i] - a * data[i - 1] + data[i - 2]);
        }
        double meanAbs = sumAbs / (n - start);
        if (meanAbs <= 0) return false;
        double threshold = THRESHOLD_FACTOR * meanAbs;
        for (int i = start; i < n; i++) {
            if (Math.abs(data[i] - a * data[i - 1] + data[i - 2]) > threshold) return true;
        }
        return false;
    }
}
