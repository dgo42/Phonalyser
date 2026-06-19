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

/** Per-channel high-frequency cleanup mode for the oscilloscope.
 *  {@link #NONE} = off; {@link #HZ_80} = 80 kHz Chebyshev low-pass (for
 *  continuous HF content); {@link #DESPIKE} = median de-spike (for
 *  impulsive glitches, no ringing). */
public enum LpfMode {
    NONE(0.0, 0),
    HZ_80(80_000.0, 0),
    DESPIKE(0.0, 7);

    /** Combo labels, index-aligned with {@link #values()}. */
    public static final String[] LABELS = { "None", "80kHz", "Despike" };

    /** −3 dB corner in Hz when this is a low-pass; {@code 0} otherwise. */
    public final double cutoffHz;
    /** Median window in samples when this is a de-spike; {@code 0} otherwise. */
    public final int    window;

    LpfMode(double cutoffHz, int window) {
        this.cutoffHz = cutoffHz;
        this.window   = window;
    }

    /** Parses an enum name, falling back to {@code def} on null / unknown. */
    public static LpfMode fromNameOr(String name, LpfMode def) {
        if (name == null) return def;
        try { return valueOf(name); }
        catch (IllegalArgumentException e) { return def; }
    }
}
