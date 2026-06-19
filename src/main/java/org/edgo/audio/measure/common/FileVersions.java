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

package org.edgo.audio.measure.common;

import lombok.experimental.UtilityClass;

/**
 * Format versions stamped into every file the application writes, so a future
 * loader can branch per version when a format changes.  Write-only for now —
 * no migration logic exists yet; bump the matching constant in the SAME commit
 * that changes a format.
 */
@UtilityClass
public class FileVersions {

    /** {@code preferences.yaml} ({@code formatVersion} key). */
    public final int PREFERENCES_YAML = 1;

    /** Saved {@code .fft} spectrum files ({@code # format_version=} header). */
    public final int FFT_SPECTRUM = 1;

    /** {@code .frc} filter-calibration files ({@code # format_version=} header).
     *  Reset to 1 for the first release — development-era files carried up to 6. */
    public final int FRC_CALIBRATION = 1;

    /** {@code applied_compensation_*.csv} DAC harmonic-predistortion files
     *  written by the predistortion wizard ({@code # format_version=} header).
     *  The data rows stay byte-compatible with the CLI iterative-compensate
     *  output; only the GUI adds the version + gen/FFT/THD provenance header. */
    public final int PREDISTORTION = 1;
}
