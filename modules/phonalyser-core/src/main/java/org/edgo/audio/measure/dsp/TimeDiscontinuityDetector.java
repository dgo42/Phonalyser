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

/**
 * Time-domain waveform-discontinuity detector, shared by the oscilloscope's
 * glitch trigger and the FFT worker's time-domain rejection gate - so both
 * instruments agree on what counts as a damaged block (dropped-sample DAC
 * gaps, ADC-side cutoffs, phase-jump splices).
 *
 * <p>Each sample is predicted from the sinusoid recurrence
 * {@code d[i] ≈ a·d[i−1] − d[i−2]} with {@code a = 2·cos ω} estimated from the
 * window itself by least squares.  The recurrence is EXACT for a clean tone at
 * any frequency, so the prediction-error baseline is the noise floor - unlike
 * a plain second difference (the {@code a = 2} special case), whose baseline
 * is the tone's own curvature {@code A·ω²} and which therefore goes deaf as
 * the signal frequency rises (at 20 kHz / 384 kHz the curvature threshold
 * reaches ~0.5·A, hiding every glitch smaller than a full-peak drop).  The
 * rare glitch samples cannot bias the least-squares estimate or the mean-based
 * threshold.  Frequency-domain counterpart: {@link SpectralDiscontinuityDetector}.
 */
public final class TimeDiscontinuityDetector {

    /**
     * Noise term of the detection threshold: this factor × the window's mean
     * |prediction error|.  For a clean tone the error is noise-limited, where
     * 8× ≈ 6.4 σ - broadband noise never reaches it on its own.  Residual
     * harmonics / a second tone raise the baseline (they don't fit a
     * single-tone recurrence), and the term self-scales with them.  Added
     * LINEARLY to the event term below, so the threshold rides ON TOP of
     * whatever the environment contributes - a low-level capture whose noise
     * dominates the tone cannot fire on its own statistics.
     */
    private static final double REL_FACTOR = 8.0;

    /**
     * Event term of the detection threshold: the fraction of the span's tone
     * amplitude {@code A = √2·RMS} that DEFINES a discontinuity - a fast jump
     * of at least this fraction within a few samples (a zero-pause dropout
     * entry, a glued capture splice).  Smaller excursions are signal
     * behaviour, not events.  The threshold is the linear sum
     * {@code REL_FACTOR·meanAbs + EVENT_FRACTION·A}: a real event's
     * recurrence error is ≈ the jump size itself (a dropout's recovery
     * boundary reaches ≈ A, decades above), so it crosses the sum, while
     * noise alone never does - its own contribution is already inside the
     * mean term.  Sub-sample capture-timing slips are cleared with ~24 dB of
     * margin: a 50 ns slip at 20 kHz enters the recurrence weighted by
     * {@code a = 2·cos ω}, a measured worst case of {@code ≈ 0.012·A}.
     */
    private static final double EVENT_FRACTION = 0.2;

    /** Discontinuity bursts closer than this (seconds) belong to ONE glitch - a
     *  dropout's entry and recovery boundaries (the observed USB gaps run
     *  120-160 µs) merge, so a caller can anchor on the glitch's start or end
     *  as a whole rather than on each boundary separately. */
    public static final double MERGE_SECONDS = 0.001;

    /**
     * Finds the rightmost discontinuity in {@code data[from .. to)}.  Fires on
     * amplitude steps AND slope breaks anywhere on the waveform (peaks, flanks,
     * zero crossings alike) regardless of direction.  Consecutive above-threshold
     * samples form a burst; bursts closer than {@code mergeSamples} form ONE
     * glitch (a dropout = entry burst + body + recovery burst).
     *
     * @param anchorStart {@code true} -> return the last clean sample before the
     *        glitch, {@code false} -> the first settled sample after it
     * @param omega the KNOWN fundamental as {@code 2π·f/sampleRate} - pins the
     *        recurrence coefficient exactly, so the tone nulls to the noise floor
     *        even when an in-window glitch or harmonics would bias the estimate;
     *        {@code NaN} -> least-squares self-estimate from the window (external /
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
        double sumSq  = 0;
        for (int i = start; i < to; i++) {
            sumAbs += Math.abs(data[i] - a * data[i - 1] + data[i - 2]);
            sumSq  += (double) data[i] * data[i];
        }
        double meanAbs = sumAbs / (to - start);
        if (meanAbs <= 0) return -1.0;
        // √2·RMS over the span ≈ the tone amplitude A (tone-dominated data); with
        // no dominant tone it collapses toward the noise scale and the noise
        // term carries the threshold alone.
        double amplitude = Math.sqrt(2.0 * sumSq / (to - start));
        float threshold = (float) (REL_FACTOR * meanAbs + EVENT_FRACTION * amplitude);
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
                    glitchEnd = i;                    // same glitch - extend to this burst
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

    /**
     * Whether {@code data[0 .. n)} contains any discontinuity - the yes/no
     * verdict of {@link #findDiscontinuity} on {@code double[]} data for the
     * FFT worker, whose capture window stays in double precision to preserve
     * the measurement floor (converting a multi-million-sample window to float
     * per analysis cycle would cost an allocation + copy for nothing).  Same
     * model: sinusoid-recurrence prediction error vs the linear threshold
     * {@code REL_FACTOR·mean|error| + EVENT_FRACTION·amplitude}.
     *
     * @param data  the samples
     * @param n     how many leading samples are tested
     * @param omega the tone's angular frequency per sample; NaN to fit it from the data
     * @return {@code true} when a sample breaks the prediction by more than the threshold
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
        double sumSq  = 0;
        for (int i = start; i < n; i++) {
            sumAbs += Math.abs(data[i] - a * data[i - 1] + data[i - 2]);
            sumSq  += data[i] * data[i];
        }
        double meanAbs = sumAbs / (n - start);
        if (meanAbs <= 0) return false;
        // √2·RMS over the span ≈ the tone amplitude A (tone-dominated data); with
        // no dominant tone it collapses toward the noise scale and the noise
        // term carries the threshold alone.
        double amplitude = Math.sqrt(2.0 * sumSq / (n - start));
        double threshold = REL_FACTOR * meanAbs + EVENT_FRACTION * amplitude;
        for (int i = start; i < n; i++) {
            if (Math.abs(data[i] - a * data[i - 1] + data[i - 2]) > threshold) return true;
        }
        return false;
    }
}
