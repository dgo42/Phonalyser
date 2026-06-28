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

/**
 * Oscilloscope display persistence ("digital phosphor") — how long a swept trace
 * lingers before fading.  {@link #OFF} clears each frame; {@link #INFINITE} never
 * decays (accumulate forever); the timed presets decay with that time constant;
 * {@link #MANUAL} uses the separate manual-seconds preference.  GPU path only.
 */
public enum PersistenceMode {
    OFF(0.0),
    S_05(0.5),
    S_1(1.0),
    S_2(2.0),
    INFINITE(-1.0),
    MANUAL(Double.NaN);

    /** Combo labels, index-aligned with {@link #values()}. */
    public static final String[] LABELS = { "Off", "0.5 s", "1 s", "2 s", "∞", "Manual" };

    /** Persistence time in seconds: {@code 0} = off, {@code < 0} = infinite,
     *  {@code > 0} = decay time constant; {@link Double#NaN} = use the manual pref. */
    public final double seconds;

    private PersistenceMode(double seconds) {
        this.seconds = seconds;
    }

    /** Effective persistence time, substituting {@code manualSeconds} for {@link #MANUAL}:
     *  {@code 0} = off, {@code < 0} = infinite, {@code > 0} = finite decay time. */
    public double effectiveSeconds(double manualSeconds) {
        return this == MANUAL ? manualSeconds : seconds;
    }

    /** Parses an enum name, falling back to {@code def} on null / unknown. */
    public static PersistenceMode fromNameOr(String name, PersistenceMode def) {
        if (name == null) return def;
        try { return valueOf(name); }
        catch (IllegalArgumentException e) { return def; }
    }
}
