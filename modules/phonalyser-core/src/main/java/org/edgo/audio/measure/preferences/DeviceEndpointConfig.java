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

import java.util.ArrayList;
import java.util.List;

import org.edgo.audio.measure.enums.DeviceChannelMode;

import lombok.Data;

/**
 * One direction (input or output) of an {@link AudioDeviceProfile}: the channel
 * mode plus the endpoint's variable-length range table and its selected active
 * range(s).
 *
 * <p>The schema is per-channel-capable from day one: {@link #channels} declares
 * whether the two channels share one range ({@link DeviceChannelMode#LINKED}) or
 * each sit on their own ({@link DeviceChannelMode#INDEPENDENT}).  {@link #activeRange}
 * names the selected row's label; {@link #activeRangeRight} names the right
 * channel's active row and is consulted only in {@code INDEPENDENT} mode (a
 * {@code LINKED} endpoint resolves both channels from {@link #activeRange}).
 */
@Data
public class DeviceEndpointConfig {
    private DeviceChannelMode channels = DeviceChannelMode.LINKED;
    private List<DeviceRange> ranges   = new ArrayList<>();
    private String activeRange;
    private String activeRangeRight;

    /** {@code true} when this direction's full-scale values are OWNED by the
     *  device - a QA40x reports its own input / output calibration (per-direction,
     *  hence the flag lives on the endpoint, not the profile).  The store never
     *  writes calibration into such ranges, and the once-per-upgrade seed merge
     *  takes them wholesale from the seed with no calibrated-value overlay (the
     *  device supplies the values at runtime).  Seed/device-set, never user-set.
     *  Serialized ONLY when {@code true}; the reader tolerates its absence
     *  (default {@code false}). */
    private boolean calibrationFromDevice;

    /** A deep copy of this endpoint - new range-row objects, so a copied profile
     *  (a dialog edit copy, a seeded profile) shares no mutable state with its
     *  source. */
    public DeviceEndpointConfig deepCopy() {
        DeviceEndpointConfig c = new DeviceEndpointConfig();
        c.channels              = channels;
        c.activeRange           = activeRange;
        c.activeRangeRight      = activeRangeRight;
        c.calibrationFromDevice = calibrationFromDevice;
        for (DeviceRange r : ranges) {
            c.ranges.add(r.deepCopy());
        }
        return c;
    }
}
