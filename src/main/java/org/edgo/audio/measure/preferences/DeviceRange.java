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

import lombok.Data;

/**
 * One row of a device endpoint's range table — a single attenuator / gain
 * position and the full-scale voltage(s) that position calibrates to.  A plain
 * soundcard has exactly one row; a switchable front-end (QA40x relays, the E1DA
 * Cosmos manual DIP) has one row per position.
 *
 * <p>Full-scale is stored in <b>volts RMS</b> on disk for both directions (the
 * DAC's peak-amplitude form is converted amplitude&harr;RMS at the resolver, to
 * match the existing top-level {@code dacFsVoltageRms} on-disk convention).
 * {@link #fsRight} equals {@link #fsLeft} when the endpoint shares one full-scale
 * across both channels — the YAML always emits a {@code {left, right}} pair for
 * {@code fsVrms} (the reader still accepts a scalar shorthand for old files).
 */
@Data
public class DeviceRange {
    private String label;
    private double fsLeft;
    private double fsRight;

    /** {@code true} once a real crosshair calibration wrote this row's full-scale
     *  — set by {@code Preferences.writeActiveRangeFs}.  The upgrade-safe seed
     *  merge never refreshes a calibrated row's nominal, so the user's measured
     *  value survives app upgrades that add new cards / ranges.  Serialized ONLY
     *  when {@code true}; the reader tolerates its absence (default {@code false}). */
    private boolean calibrated;

    /** Human-facing DISPLAY label for the ranges table when it differs from the
     *  {@link #label} KEY — e.g. a QA40x input row shows the verbose
     *  {@code N "dBV" real N dBFS or (N−9) dBV} while the key stays plain
     *  {@code "N dBV"}.  Backend-agnostic: any card builder may set it; ordinary
     *  rows leave it {@code null} and fall back to {@link #label}.  NOT serialized —
     *  device-provided cards re-derive it on every build. */
    private String displayLabel;

    /** The label to show in the ranges table: {@link #displayLabel} when set, else
     *  the plain {@link #label} key. */
    public String displayLabelOrKey() {
        return displayLabel != null ? displayLabel : label;
    }

    /** A fresh copy of this row — used to deep-copy an endpoint's range table so
     *  a copied profile shares no mutable row with its source (or the catalog). */
    public DeviceRange deepCopy() {
        DeviceRange c = new DeviceRange();
        c.label      = label;
        c.fsLeft     = fsLeft;
        c.fsRight    = fsRight;
        c.calibrated = calibrated;
        c.displayLabel = displayLabel;
        return c;
    }
}
