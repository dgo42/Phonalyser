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
 * Common contract for the time-domain mains-hum filters (IIR comb,
 * synchronous subtractor, LMS line canceller) so the oscilloscope and the FFT
 * pre-filter can select and drive any of them uniformly.
 *
 * <p>Typical use: {@link #track} occasionally on a reference block to lock the
 * mains frequency, then {@link #process} (or {@link #processPreservingDc})
 * every block.  Implementations keep state across {@code process} calls and
 * are not thread-safe — drive each from one thread.
 */
public interface MainsTimeFilter {

    /** Re-estimates the mains fundamental from {@code ref} (the signal being
     *  filtered, or a dedicated pickup) and retunes; returns the locked
     *  frequency in Hz, or {@code NaN} when nothing confident is found. */
    double track(double[] ref, int len);

    /** Single-precision {@link #track} overload. */
    double track(float[] ref, int len);

    /** Filters {@code data} in place over {@code len} samples.  No-op until the
     *  filter has been tuned (call {@link #track} first).  {@code absStart} is
     *  the absolute index of {@code data[0]} in the continuous capture stream:
     *  phase-locked filters advance their mains phase by the <em>delta</em> from
     *  the previous call's {@code absStart}, so they stay aligned across
     *  non-contiguous snapshots (scope) and overlapping windows (FFT) alike.
     *  Stream-stateful filters (the comb) ignore it. */
    void process(float[] data, int len, long absStart);

    /** Like {@link #process} but preserves the block's DC (mean) level — only
     *  the mains hum is removed, the operating point is left intact. */
    void processPreservingDc(float[] data, int len, long absStart);

    /** Double-precision {@link #process(float[], int, long)} — the FFT path
     *  filters its capture window in {@code double} to keep full precision. */
    void process(double[] data, int len, long absStart);

    /** Double-precision {@link #processPreservingDc(float[], int, long)}. */
    void processPreservingDc(double[] data, int len, long absStart);

    /** Clears per-block filter state (e.g. on a capture restart) without
     *  changing the current tuning. */
    void reset();

    /** Drops the frequency lock and any cached tuning so the next {@link #track}
     *  re-detects from scratch. */
    void resetTracking();

    /** True once the filter has been tuned to a mains frequency. */
    boolean isTuned();

    /** Current tuned fundamental (Hz), or {@code NaN} before the first lock. */
    double getMainsHz();
}
