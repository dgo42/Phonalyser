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

package org.edgo.audio.measure.preferences;

import java.nio.file.Path;

import org.edgo.audio.measure.bind.Property;

import lombok.Getter;
import lombok.Setter;

/**
 * One row of a pane's Load-calibration list — a single {@code .frc} file plus
 * its two per-row toggles.  Reused by both the FFT pane and the Frequency
 * Response pane (the FreqResp pane simply ignores {@link #withNoise()}).
 *
 * <p>{@link #path} is plain mutable state: a row's file is chosen by the
 * file-browse and the row is rebuilt around the new path, so it never needs
 * change notification — the pane re-pushes the calibration store and triggers a
 * save by hand after a browse / clear.  {@link #active()} and
 * {@link #withNoise()} are observable {@link Property} values so each row's
 * checkbox two-way binds via {@code Bindings.check}; {@link Preferences} wires
 * them to its debounced save when the entry is tracked, so a toggle persists the
 * same way a scalar preference does.
 *
 * <p>An empty row (one the user added but has not yet populated) is a valid
 * entry with a {@code null} {@link #path}; keeping it in the list preserves the
 * row count across restarts, matching the legacy empty-string placeholder rows.
 */
public final class CalibrationEntry {

    /** Path to the loaded {@code .frc} / CSV file, or {@code null} for an
     *  empty (added-but-unpopulated) row. */
    @Getter @Setter private String path;

    /** "Active" toggle — when {@code true} (and a file is loaded) this row's
     *  correction is pushed into the calibration store and applied. */
    private final Property<Boolean> active;

    /** "With noise" toggle — when {@code true} this row's correction applies to
     *  every FFT bin (noise floor included), not just the harmonic / dot
     *  positions.  Unused by the FreqResp pane. */
    private final Property<Boolean> withNoise;

    /** Creates an empty row: no path, inactive, noise-correction off. */
    public CalibrationEntry() {
        this(null, false, false);
    }

    public CalibrationEntry(String path, boolean active, boolean withNoise) {
        this.path      = path;
        this.active    = new Property<>(active);
        this.withNoise = new Property<>(withNoise);
    }

    /** True when this row references the same file as {@code other}, comparing
     *  normalised absolute paths so {@code ./} segments and separator
     *  differences don't cause a spurious mismatch (Windows paths are also
     *  case-insensitive, which the normalised compare honours on that OS). */
    public boolean matchesPath(String other) {
        if (path == null || other == null) return false;
        try {
            return Path.of(path).toAbsolutePath().normalize()
                    .equals(Path.of(other).toAbsolutePath().normalize());
        } catch (RuntimeException e) {
            return path.equals(other);
        }
    }

    public Property<Boolean> active() {
        return active;
    }

    public Property<Boolean> withNoise() {
        return withNoise;
    }
}
