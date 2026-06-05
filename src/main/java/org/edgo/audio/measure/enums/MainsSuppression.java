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

/** Mains-hum (50/60 Hz + harmonics) suppression applied to the captured
 *  signal before FFT averaging.  {@link #NONE} = pass-through;
 *  {@link #IIR_COMB} = frequency-tracked IIR comb. */
public enum MainsSuppression {
    NONE,
    IIR_COMB;

    /** Combo labels, index-aligned with {@link #values()}.  Lives on the
     *  enum (not a pane formatter) so both the FFT and scope panes can use
     *  it without a cross-package dependency. */
    public static final String[] LABELS = { "None", "IIR comb" };

    private MainsSuppression() {}

    /** Parses an enum name, falling back to {@code def} on null / unknown. */
    public static MainsSuppression fromNameOr(String name, MainsSuppression def) {
        if (name == null) return def;
        try { return valueOf(name); }
        catch (IllegalArgumentException e) { return def; }
    }
}
