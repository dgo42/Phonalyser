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

package org.edgo.audio.measure.preferences;

import java.nio.file.Path;

import org.edgo.audio.measure.bind.Property;

/**
 * One row of a pane's Load-calibration list - a single {@code .frc} file plus
 * its two per-row toggles.  Reused by both the FFT pane and the Frequency
 * Response pane (the FreqResp pane simply ignores {@link #withNoise()}).
 *
 * <p>{@link #path()}, {@link #active()} and {@link #withNoise()} are
 * observable {@link Property} values.  Each row's checkbox two-way binds to its
 * toggle via {@code Bindings.check}.  The {@link CalibrationList} holding the
 * entry - the FreqResp list or the FFT list - subscribes to all three, so a
 * loaded / cleared file or a flipped toggle reaches the list's listeners, the
 * pane's calibration tab and the debounced save of {@link Preferences} among
 * them.
 *
 * <p>An empty row (one the user added but has not yet populated) is a valid
 * entry with a {@code null} path; keeping it in the list preserves the row
 * count across restarts, matching the legacy empty-string placeholder rows.
 */
public final class CalibrationEntry {

    /** Path to the loaded {@code .frc} / CSV file, or {@code null} for an
     *  empty (added-but-unpopulated) row. */
    private final Property<String> path;

    /** "Active" toggle - when {@code true} (and a file is loaded) this row's
     *  correction is pushed into the calibration store and applied. */
    private final Property<Boolean> active;

    /** "With noise" toggle - when {@code true} this row's correction applies to
     *  every FFT bin (noise floor included), not just the harmonic / dot
     *  positions.  Unused by the FreqResp pane. */
    private final Property<Boolean> withNoise;

    /** Creates an empty row: no path, inactive, noise-correction off. */
    public CalibrationEntry() {
        this(null, false, false);
    }

    public CalibrationEntry(String path, boolean active, boolean withNoise) {
        this.path      = new Property<>(path);
        this.active    = new Property<>(active);
        this.withNoise = new Property<>(withNoise);
    }

    public String getPath() {
        return path.get();
    }

    public void setPath(String path) {
        this.path.set(path);
    }

    public Property<String> path() {
        return path;
    }

    /** True when this row references the same file as {@code other}, comparing
     *  normalised absolute paths so {@code ./} segments and separator
     *  differences don't cause a spurious mismatch (Windows paths are also
     *  case-insensitive, which the normalised compare honours on that OS). */
    public boolean matchesPath(String other) {
        String own = path.get();
        if (own == null || other == null) return false;
        try {
            return Path.of(own).toAbsolutePath().normalize()
                    .equals(Path.of(other).toAbsolutePath().normalize());
        } catch (RuntimeException e) {
            return own.equals(other);
        }
    }

    public Property<Boolean> active() {
        return active;
    }

    public Property<Boolean> withNoise() {
        return withNoise;
    }
}
