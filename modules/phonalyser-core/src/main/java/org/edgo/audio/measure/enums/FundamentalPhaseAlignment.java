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

package org.edgo.audio.measure.enums;

/** Time base the FFT coherent average uses for the fundamental's phase.
 *  {@link #NONE} = the analysed channel's own fundamental drives the
 *  tracking loop, so a notch whose centre frequency drifts moves the
 *  fundamental's phase alone and the loop misreads it as a time slip,
 *  landing it on harmonic h multiplied by h; {@link #REFERENCE_CHANNEL} =
 *  the opposite input channel carries the un-notched fundamental and
 *  supplies the drift-free phase the loop tracks; {@link #PERIODICAL_SWEEP}
 *  = a periodic sweep probe of the notch region measures the fundamental's
 *  phase drift and rotates the fundamental region alone by it, leaving the
 *  harmonics untouched.  Combo labels come from the i18n bundle, not from
 *  this enum. */
public enum FundamentalPhaseAlignment {
    NONE,
    REFERENCE_CHANNEL,
    PERIODICAL_SWEEP;

    private FundamentalPhaseAlignment() {}

    /** Parses an enum name, falling back to {@code def} on null / unknown. */
    public static FundamentalPhaseAlignment fromNameOr(String name, FundamentalPhaseAlignment def) {
        if (name == null) return def;
        try { return valueOf(name); }
        catch (IllegalArgumentException e) { return def; }
    }
}
