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

package org.edgo.audio.measure.fft;

/**
 * One line of a spectrum as the statistics measure it - a fundamental, a
 * harmonic, a tone of a dual tone or an intermodulation product: where it
 * stands, the lobe it fills, the floor under it, and its level twice, as
 * measured and de-embedded.  The geometry is measured each time the
 * statistics run, on the spectrum as it arrives, before the levels; every
 * lift of the line's lobe reads it from here.
 *
 * @param frequencyHz             the line's frequency in Hz
 * @param bin                     the line's bin; -1 for a line outside the
 *                                measurable range
 * @param halfWidthBins           the window's main lobe plus the leakage
 *                                guard to either side of the bin, in bins
 * @param skirtHalfWidthBins      the lobe as walked out from the bin to the
 *                                floor, in bins to either side; the bins the
 *                                line's lift reaches
 * @param bandTopDbFs             the floor the line stands on, dBFS on the
 *                                spectrum as it arrived: the top of the local
 *                                noise band for a distortion line, the skirt
 *                                walk's floor for a fundamental;
 *                                {@link Double#NaN} where none was measured
 * @param rawAmplitudeDbFs        the level as measured on the spectrum, dBFS,
 *                                a distortion line clamped at its band-top from
 *                                below - {@link Double#NaN} for a line outside
 *                                the measurable range or not measured yet
 * @param deembeddedAmplitudeDbFs the same level with the {@code .frc}
 *                                correction at the line's bin taken out; equal
 *                                to {@code rawAmplitudeDbFs} without one
 */
public record SpectralLine(double frequencyHz, int bin, int halfWidthBins,
                           int skirtHalfWidthBins, double bandTopDbFs,
                           double rawAmplitudeDbFs, double deembeddedAmplitudeDbFs) {

    /** How close, dB, a line's measured level stands to its band-top when the
     *  clamp wrote it: the clamp's linear-to-dB round trip, nothing a measured
     *  level lands on by chance. */
    private static final double CLAMP_MATCH_DB = 1e-9;

    /** A line's geometry before its levels are measured.
     *
     * @param frequencyHz        the line's frequency in Hz
     * @param bin                the line's bin; -1 outside the measurable range
     * @param halfWidthBins      the main lobe plus the leakage guard, in bins
     * @param skirtHalfWidthBins the lobe walked out to the floor, in bins
     * @param bandTopDbFs        the floor the line stands on, dBFS
     * @return the line, its levels {@link Double#NaN} */
    public static SpectralLine of(double frequencyHz, int bin, int halfWidthBins,
                                  int skirtHalfWidthBins, double bandTopDbFs) {
        return new SpectralLine(frequencyHz, bin, halfWidthBins, skirtHalfWidthBins,
                bandTopDbFs, Double.NaN, Double.NaN);
    }

    /** This line with its levels measured.
     *
     * @param raw        the level as measured, dBFS
     * @param deembedded the level de-embedded, dBFS
     * @return the line with the levels */
    public SpectralLine withLevels(double raw, double deembedded) {
        return new SpectralLine(frequencyHz, bin, halfWidthBins, skirtHalfWidthBins,
                bandTopDbFs, raw, deembedded);
    }

    /** Whether the line has a bin on the spectrum.
     *
     * @return {@code true} inside the measurable range */
    public boolean measurable() {
        return bin >= 1;
    }

    /** A distortion line's reading clamped at its band-top from below.  A
     *  line under its floor carries no stable level: what its bin holds is
     *  the noise there, wandering across the floor's band from one collection
     *  to the next, and summed into THD it would move THD and everything
     *  steered by it.  So the reading is clamped at the band-top and is free
     *  to rise above it.
     *
     * @param linear the reading, linear amplitude (full scale 1)
     * @return the reading clamped; unchanged where no band-top was measured
     *         or the reading is not finite */
    public double clampedAtBandTop(double linear) {
        if (!Double.isFinite(bandTopDbFs) || !Double.isFinite(linear)) return linear;
        return Math.max(linear, Math.pow(10.0, bandTopDbFs / 20.0));
    }

    /** Whether the band-top clamp set this line's reading
     *  ({@link #clampedAtBandTop}): its measured level stands on its band-top.
     *
     * @return {@code true} for a reading the clamp set */
    public boolean clamped() {
        return Double.isFinite(bandTopDbFs) && Double.isFinite(rawAmplitudeDbFs)
                && Math.abs(rawAmplitudeDbFs - bandTopDbFs) <= CLAMP_MATCH_DB;
    }
}
