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
import java.util.function.IntToDoubleFunction;

/**
 * Shared "lift a tone's main lobe" DSP, used by both the {@code .frc}
 * frequency-response compensation and the manual-fundamental display.
 *
 * <p>A tone's energy fills the analysis window's main lobe.  To rescale the
 * tone — by {@code 1/|H|} for a calibration, or to a user level for manual
 * fundamental — the goal is a clean rescaled lobe: the window's <b>whole main
 * lobe</b> stretched up, its <b>feet pinned to the noise floor</b> (a smooth
 * dome sitting on the grass), NOT a flat-topped box with vertical cliffs.
 *
 * <h2>1. The noise floor — measured FAR from the lobe, robustly</h2>
 * <p>A wide window (e.g. BH7) has a main lobe hundreds of bins across, so the
 * flank band used to estimate the noise must reach well past it AND tolerate a
 * chunk of lobe falling inside it.  The estimate is therefore
 * {@code median + K·MAD} over a wide flank band: the median + MAD ignore the
 * lobe (and any harmonics / mains / spurs) as outliers, returning the true
 * noise-floor ceiling regardless of how wide the lobe is.
 *
 * <h2>2. Lobe extent — out to where the lobe meets the noise</h2>
 * <p>The lobe is walked out from the peak until it drops to that noise floor,
 * so the <em>entire</em> main lobe is covered (a bin or two for a coherent
 * rectangular tone; ±~190 bins for BH7).
 *
 * <h2>3. The stretch — pin the feet, pull the peak up</h2>
 * <p>{@link #stretch} is a vertical stretch anchored on the noise floor: the
 * feet stay exactly on the floor and the peak is pulled up to {@code peak·factor},
 * every bin scaled by its log-height above the floor.  The lobe grows TALLER (a
 * smooth dome on the grass) rather than the whole body lifting off the floor and
 * cliffing back down.  A tone whose peak is itself buried at/below the floor is
 * simply scaled.
 */
public final class ToneLobeLift {

    /** Noise ceiling = median + {@code CEIL_K}·MAD of the flank band. */
    private static final double CEIL_K = 4.0;

    private final int floorGap;      // bins skipped each side before sampling the flanks
    private final int floorSpan;     // bins sampled each side for the noise estimate
    private final int maxLobeBins;   // safety cap on the lobe half-width

    public ToneLobeLift() {
        this(10, 2048, 2048);
    }

    public ToneLobeLift(int floorGap, int floorSpan, int maxLobeBins) {
        this.floorGap    = Math.max(1, floorGap);
        this.floorSpan   = Math.max(1, floorSpan);
        this.maxLobeBins = Math.max(1, maxLobeBins);
    }

    /** Noise-floor <b>ceiling</b> (same units as {@code mag}) near {@code peak}:
     *  {@code median + CEIL_K·MAD} of the bins flanking the tone.  Robust — a
     *  wide main lobe, harmonics, mains lines or spurs falling inside the band
     *  are outliers the median/MAD ignore.  Returns 0 when no flanks are in
     *  range.  (Named "floor" because it is the level a bin must rise ABOVE to
     *  count as part of the lobe, and the level the lobe's feet taper back to.) */
    public double localFloor(IntToDoubleFunction mag, int peak, int maxBin) {
        double[] buf = new double[2 * floorSpan];
        int m = 0;
        for (int k = peak - floorGap - floorSpan; k < peak - floorGap; k++) {
            if (k >= 1 && k <= maxBin) buf[m++] = mag.applyAsDouble(k);
        }
        for (int k = peak + floorGap + 1; k <= peak + floorGap + floorSpan; k++) {
            if (k >= 1 && k <= maxBin) buf[m++] = mag.applyAsDouble(k);
        }
        if (m == 0) return 0.0;
        double[] c = Arrays.copyOf(buf, m);
        Arrays.sort(c);
        double median = c[m / 2];
        for (int i = 0; i < m; i++) c[i] = Math.abs(buf[i] - median);
        Arrays.sort(c);
        double mad = c[m / 2];
        return median + CEIL_K * mad;
    }

    /** Inclusive {@code [lo, hi]} bin span of the main lobe: the contiguous bins
     *  around {@code peak} that stand above the noise floor {@code floor}.  The
     *  peak itself is always included (so a sub-floor tone still yields its one
     *  peak bin); the walk runs out to where the lobe drops to the noise. */
    public int[] lobeBins(IntToDoubleFunction mag, int peak, int maxBin, double floor) {
        return new int[]{ edge(mag, peak, -1, maxBin, floor),
                          edge(mag, peak, +1, maxBin, floor) };
    }

    private int edge(IntToDoubleFunction mag, int peak, int dir, int maxBin, double floor) {
        int foot = peak;
        for (int step = 1; step <= maxLobeBins; step++) {
            int k = peak + dir * step;
            if (k < 1 || k > maxBin) break;
            if (mag.applyAsDouble(k) <= floor) break;   // dropped to the noise — lobe ends
            foot = k;
        }
        return foot;
    }

    /** Vertical <b>stretch</b> of a lobe magnitude, anchored on the noise floor:
     *  the feet stay put and the peak is pulled up to {@code peak·factor}, every
     *  bin scaled by its log-height above the floor —
     *  {@code |X'| = |X|·factor^t} with {@code t = ln(|X|/floor) / ln(peak/floor)}
     *  (t = 1 at the peak, 0 at the floor).  Stretches the lobe taller (a smooth
     *  dome on the grass) instead of lifting the body off the floor.  A bin at or
     *  below the floor is left alone; a tone whose peak is itself buried at/below
     *  the floor is simply scaled by {@code factor}. */
    public double stretch(double mag, double floor, double peak, double factor) {
        if (!(peak > floor)) return mag * factor;
        if (!(mag > floor))  return mag;
        double t = Math.log(mag / floor) / Math.log(peak / floor);
        // A bin standing ABOVE the assumed peak (t > 1) — a noise bin in the lobe
        // of a weak tone whose true peak isn't at round(toneHz/binWidth) — must not
        // be lifted MORE than the peak itself (factor^t ≫ factor), which spikes it
        // to ~2× the cal gain.  Cap at the peak's own factor.
        if (t > 1.0) t = 1.0;
        return mag * Math.pow(factor, t);
    }
}
