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

import org.edgo.audio.measure.preferences.Preferences;

/**
 * The readout settings a spectrum's statistics are calculated with: how many
 * harmonics are evaluated, how many of them the THD sums, the distortion band
 * the noise and distortion figures integrate over, and the manual
 * fundamental.  Read from the preferences in one place, for the live analysis
 * and for the view alike, and kept in the result as the inputs its statistics
 * stand on.
 *
 * @param harmonicCount         the harmonics evaluated, counted from the 2nd:
 *                              H2 .. H(harmonicCount + 1) are placed, measured
 *                              and de-embedded
 * @param thdHarmonics          how many of them the THD sums, counted from the
 *                              2nd, each still subject to the distortion band
 * @param bandMinHz             lower edge of the distortion band in Hz, 0 for
 *                              no limit
 * @param bandMaxHz             upper edge of the distortion band in Hz, 0 for
 *                              no limit
 * @param manualFundamentalDbFs the manual fundamental in dBFS - the level every
 *                              ratio stands on in place of the measured one,
 *                              so an external notch on the fundamental cannot
 *                              poison them - {@link Double#NaN} when none is set
 */
public record ReadoutSettings(int harmonicCount, int thdHarmonics,
                              double bandMinHz, double bandMaxHz,
                              double manualFundamentalDbFs) {

    /** The highest harmonic evaluated at the least, numbered with the
     *  fundamental as H1: H9, whatever lower value the preference holds. */
    private static final int MIN_CALC_MAX_HARMONIC = 9;

    /** The settings as the preferences state them.  The preferences name a
     *  harmonic by its number, H(N), while the settings count harmonics from
     *  the 2nd, so H(N) is N - 1 of them.  The manual fundamental is stated in
     *  volts RMS and arrives here in dBFS through {@code dbvOffsetDb}, the
     *  analysed channel's dBFS -> dBV offset: the analysis speaks one scale. */
    public static ReadoutSettings fromPreferences(Preferences prefs, double dbvOffsetDb) {
        double manualVrms = prefs.getFftManualFundVrms();
        double manualDbFs = (prefs.isFftManualFundEnabled() && manualVrms > 0.0)
                ? 20.0 * Math.log10(manualVrms) - dbvOffsetDb
                : Double.NaN;
        return new ReadoutSettings(
                Math.max(MIN_CALC_MAX_HARMONIC, prefs.getFftCalcMaxHarmonic()) - 1,
                Math.max(1, prefs.getFftThdMaxHarmonic() - 1),
                prefs.isFftDistMinEnabled() ? prefs.getFftDistMinHz() : 0.0,
                prefs.isFftDistMaxEnabled() ? prefs.getFftDistMaxHz() : 0.0,
                manualDbFs);
    }

    /** These settings without the manual fundamental - for a spectrum read
     *  back from a file, which was saved with it already applied. */
    public ReadoutSettings withoutManualFundamental() {
        return new ReadoutSettings(harmonicCount, thdHarmonics, bandMinHz, bandMaxHz, Double.NaN);
    }
}
