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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import lombok.Data;

/**
 * A logical, backend-independent calibration profile for one physical soundcard.
 * The profile IS the card; {@link #match} is its single list of device-name
 * recognition-and-binding entries — the unified successor of the old per-backend
 * alias maps and the seed recognition patterns.
 *
 * <p>Calibration lives in the per-direction {@link DeviceEndpointConfig}'s range
 * table (one row per attenuator / gain position, one row for a plain card).  The
 * legacy global {@code adcFsVoltageRms} / {@code dacFsVoltageAmpl} scalars remain
 * the read fallback when a selected card has no profile entry; every new
 * calibration write auto-creates a profile here instead.
 *
 * <p>Each {@link #match} entry is a case-insensitive substring test: a live
 * device name is bound to this card when any entry is contained in the device
 * name (see {@link #matchStrength}), and on overlap between cards the longest
 * matching entry wins.  A seeded well-known card arrives with short recognition
 * substrings (e.g. {@code "Cosmos ADC"}); binding a device to a card
 * {@link #bindDeviceName appends} the full device name when nothing already
 * matches.  The list is persisted only when non-empty.
 */
@Data
public class AudioDeviceProfile {
    private String name;
    private List<String> match = new ArrayList<>();
    private DeviceEndpointConfig input  = new DeviceEndpointConfig();
    private DeviceEndpointConfig output = new DeviceEndpointConfig();

    /** Length of the longest {@link #match} entry that is a case-insensitive
     *  substring of {@code deviceName}, or {@code -1} when none match — the
     *  most-specific-wins strength the resolver ranks cards by. */
    public int matchStrength(String deviceName) {
        if (deviceName == null) return -1;
        String hay = deviceName.toLowerCase(Locale.ROOT);
        int best = -1;
        for (String entry : match) {
            if (entry == null || entry.isEmpty()) continue;
            if (hay.contains(entry.toLowerCase(Locale.ROOT)) && entry.length() > best) {
                best = entry.length();
            }
        }
        return best;
    }

    /** True when any {@link #match} entry is a case-insensitive substring of
     *  {@code deviceName}. */
    public boolean matches(String deviceName) {
        return matchStrength(deviceName) >= 0;
    }

    /** Binds this card to {@code deviceName} by appending it to {@link #match},
     *  but only when no existing entry already matches it (idempotent bind);
     *  returns {@code true} when an entry was appended. */
    public boolean bindDeviceName(String deviceName) {
        if (deviceName == null || deviceName.isEmpty() || matches(deviceName)) return false;
        match.add(deviceName);
        return true;
    }
}
