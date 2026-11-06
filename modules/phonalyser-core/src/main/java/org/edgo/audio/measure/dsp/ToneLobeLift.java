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
 * Shared "lift a tone's main lobe" DSP, used by both the {@code .frc}
 * frequency-response compensation and the manual-fundamental display.
 *
 * <p>A tone's energy fills the analysis window's main lobe.  To rescale the
 * tone - by {@code 1/|H|} for a calibration, or to a user level for manual
 * fundamental - the goal is a clean rescaled lobe: the window's <b>whole main
 * lobe</b> stretched up, its <b>feet pinned to the noise floor</b> (a smooth
 * dome sitting on the grass), NOT a flat-topped box with vertical cliffs.
 *
 * <h2>1. The noise floor - measured past the main lobe, robustly</h2>
 * <p>The floor the stretch stands on is a band-top read beside the tone
 * ({@link FftDsp#pedestalFloor}, {@link FftDsp#lineFloor}): the median + MAD
 * ignore the skirt (and any harmonics / mains / spurs) as outliers.
 *
 * <h2>2. Lobe extent - out to where the lobe meets the noise</h2>
 * <p>The lobe is walked out from the peak until it drops to that noise floor
 * ({@link FftDsp#lobeBins}), so the <em>entire</em> main lobe and the skirt
 * above the floor are covered.
 *
 * <h2>3. The stretch - pin the feet, pull the peak up</h2>
 * <p>{@link #stretch} is a vertical stretch anchored on the noise floor: the
 * feet stay exactly on the floor and the peak is pulled up to {@code peak·factor},
 * every bin scaled by its log-height above the floor.  The lobe grows TALLER (a
 * smooth dome on the grass) rather than the whole body lifting off the floor and
 * cliffing back down.  A tone whose peak is itself buried at/below the floor is
 * simply scaled.
 */
public final class ToneLobeLift {

    private ToneLobeLift() {}

    /** Vertical <b>stretch</b> of a lobe magnitude, anchored on the noise floor:
     *  the feet stay put and the peak is pulled up to {@code peak·factor}, every
     *  bin scaled by its log-height above the floor -
     *  {@code |X'| = |X|·factor^t} with {@code t = ln(|X|/floor) / ln(peak/floor)}
     *  (t = 1 at the peak, 0 at the floor).  Stretches the lobe taller (a smooth
     *  dome on the grass) instead of lifting the body off the floor.  A bin at or
     *  below the floor is left alone; a tone whose peak is itself buried at/below
     *  the floor is simply scaled by {@code factor}. */
    public static double stretch(double mag, double floor, double peak, double factor) {
        return mag * Math.pow(factor, stretchExponent(mag, floor, peak));
    }

    /** The exponent {@code t} of the stretch alone - {@code 0} for a bin at or
     *  below the floor, {@code 1} at the peak (and for every bin of a tone whose
     *  peak is itself buried at or below the floor, which is simply scaled),
     *  the log-height between - so a caller applying the factor to a complex
     *  bin can raise the magnitude alone. */
    public static double stretchExponent(double mag, double floor, double peak) {
        if (!(peak > floor)) return 1.0;
        if (!(mag > floor))  return 0.0;
        double t = Math.log(mag / floor) / Math.log(peak / floor);
        // A bin standing ABOVE the assumed peak (t > 1) - a noise bin in the lobe
        // of a weak tone whose true peak isn't at round(toneHz/binWidth) - must not
        // be lifted MORE than the peak itself (factor^t ≫ factor), which spikes it
        // to ~2× the cal gain.  Cap at the peak's own factor.
        return Math.min(t, 1.0);
    }
}
