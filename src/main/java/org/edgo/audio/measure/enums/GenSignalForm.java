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

package org.edgo.audio.measure.enums;

import lombok.Getter;

/** Waveform produced by the audio signal generator. */
public enum GenSignalForm {
    SINE(true),
    SINE_COMP(true),
    TRIANGLE(true),
    RECTANGLE(true),
    WHITE_NOISE(false),
    PINK_NOISE(false),
    PINK_NOISE_LINEAR(false),
    LINEAR_SWEEP(true),
    LOG_SWEEP(true),
    DUAL_TONE(true),
    DUAL_TONE_COMP(true);

    /** True for waveforms with a repeating period — the noise forms are the
     *  exception.  Periodic forms truncate file exports to a whole number of
     *  periods and keep the generator frequency field meaningful. */
    @Getter
    private final boolean periodic;

    private GenSignalForm(boolean periodic) {
        this.periodic = periodic;
    }

    /** True for the two-tone waveforms — plain {@link #DUAL_TONE} and its
     *  intermod-compensated sibling {@link #DUAL_TONE_COMP}.  Drives
     *  every "is this a two-tone signal?" branch (IMD analysis, the scope's
     *  beat reconstruction, the generator's two-frequency block) so the
     *  compensated form is treated exactly like the uncorrected one. */
    public boolean isDualTone() {
        return this == DUAL_TONE || this == DUAL_TONE_COMP;
    }

    public static GenSignalForm fromString(String s) {
        return switch (s.toLowerCase()) {
            case "sine"                                                 -> SINE;
            case "sine_compensated", "sine_hmc"                         -> SINE_COMP;
            case "triangle", "tri"                                      -> TRIANGLE;
            case "rectangle", "rect", "square", "pulse"                 -> RECTANGLE;
            case "white", "white_noise"                                 -> WHITE_NOISE;
            case "pink", "pink_noise"                                   -> PINK_NOISE;
            case "pink_linear", "pink_noise_linear"                     -> PINK_NOISE_LINEAR;
            case "linear_sweep", "sweep", "chirp"                       -> LINEAR_SWEEP;
            case "log_sweep", "farina"                                  -> LOG_SWEEP;
            case "dual_tone", "dualtone", "twotone", "two_tone"         -> DUAL_TONE;
            case "dual_tone_compensated", "dualtone_hmc", "twotone_hmc" -> DUAL_TONE_COMP;
            default -> throw new IllegalArgumentException("Unknown signal form: " + s +
                    ". Valid: sine, sine_compensated, triangle, rectangle, white_noise, pink_noise, pink_noise_linear, linear_sweep, log_sweep, dual_tone, dual_tone_compensated");
        };
    }
}
