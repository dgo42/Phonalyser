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

import org.edgo.audio.measure.enums.MainsSuppression;

import lombok.experimental.UtilityClass;

/** Builds the {@link MainsTimeFilter} for a {@link MainsSuppression} mode —
 *  the single place the scope (display + measurement) and the FFT pre-filter
 *  map the selected mode to a filter instance.  Lives in {@code dsp} (which
 *  already depends on {@code enums}) so the enum stays a dependency-free leaf. */
@UtilityClass
public class MainsFilters {

    /** A filter for {@code mode}, or {@code null} for {@link MainsSuppression#NONE}.
     *  {@code combNotchBwHz} is the −3 dB notch width used only by the IIR comb. */
    public MainsTimeFilter of(MainsSuppression mode, int sampleRate, double combNotchBwHz) {
        return switch (mode) {
            case IIR_COMB      -> new MainsCombFilter(sampleRate, combNotchBwHz);
            case SYNC_SUBTRACT -> new MainsSyncSubtractFilter(sampleRate);
            case LMS           -> new MainsLmsFilter(sampleRate);
            case NONE          -> null;
        };
    }
}
