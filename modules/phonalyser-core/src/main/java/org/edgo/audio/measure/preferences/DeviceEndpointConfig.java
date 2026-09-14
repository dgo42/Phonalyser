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

import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.DeviceChannelMode;
import org.edgo.audio.measure.sound.DeviceCalibration;

import lombok.Data;

/**
 * One direction (input or output) of an {@link AudioDeviceProfile}: the channel
 * mode plus the endpoint's variable-length range table and its selected active
 * range(s).
 *
 * <p>The schema is per-channel-capable from day one: {@link #channels} declares
 * whether the two channels share one range ({@link DeviceChannelMode#LINKED}) or
 * each sit on their own ({@link DeviceChannelMode#INDEPENDENT}).  {@link #activeRangeLeft}
 * names the selected row's label; {@link #activeRangeRight} names the right
 * channel's active row and is consulted only in {@code INDEPENDENT} mode (a
 * {@code LINKED} endpoint resolves both channels from {@link #activeRangeLeft}).
 *
 * <p>The full scales IN FORCE - the active row's, per channel, under the mode -
 * are read and written here, where the rows and the markers live
 * ({@link #calibration()}, {@link #storeFullScale}); the device managers ask
 * and tell this endpoint and hold no full scale of their own.
 */
@Data
public class DeviceEndpointConfig {

    /** Label of the row a first calibrate creates on an endpoint that has none. */
    private static final String DEFAULT_RANGE_LABEL = "default";
    private DeviceChannelMode channels = DeviceChannelMode.LINKED;
    private List<DeviceRange> ranges   = new ArrayList<>();
    private String activeRangeLeft;
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

    /**
     * The full scales in force: the active row's, per channel.  MONO: the one
     * physical channel's {@code fsLeft} fills both.  LINKED: left and right from
     * the SAME active row.  INDEPENDENT: left from the {@link #activeRangeLeft} row,
     * right from the {@link #activeRangeRight} row (a dangling right label falls
     * back to the left marker's row).
     *
     * <p>"In force" includes ABOVE ZERO on both channels.  A full scale is the
     * divisor every level is computed against, so a zero is not a quiet device -
     * it is a division by zero, or an infinite reading, depending on which way
     * it is applied.  A row that was never calibrated carries exactly that zero,
     * and such a row is an UNCALIBRATED device however it got there.
     *
     * @return the pair, or {@code null} when the table is empty or the active
     *         row is uncalibrated
     */
    public DeviceCalibration calibration() {
        DeviceRange rowLeft = activeRange(Channel.L);
        if (rowLeft == null) {
            return null;
        }
        double fsLeft = rowLeft.getFsLeft();
        double fsRight;
        switch (channels) {
            case INDEPENDENT: fsRight = activeRange(Channel.R).getFsRight(); break;
            case LINKED:      fsRight = rowLeft.getFsRight();                break;
            default:          fsRight = fsLeft;                              break;   // MONO
        }
        if (fsLeft <= 0.0 || fsRight <= 0.0) {
            return null;
        }
        return new DeviceCalibration(fsLeft, fsRight);
    }

    /** The active range row for {@code ch}.  LEFT (and any MONO / LINKED
     *  endpoint) uses {@link #activeRangeLeft}; RIGHT on an INDEPENDENT endpoint uses
     *  {@link #activeRangeRight}, falling back to the {@code activeRangeLeft} row
     *  when the right label is unset or dangles.  Both fall back to the first
     *  row, or {@code null} when the table is empty. */
    public DeviceRange activeRange(Channel ch) {
        if (ranges == null || ranges.isEmpty()) return null;
        if (ch == Channel.R && channels == DeviceChannelMode.INDEPENDENT
                && activeRangeRight != null) {
            for (DeviceRange r : ranges) {
                if (activeRangeRight.equals(r.getLabel())) return r;
            }
            // Dangling right label -> fall back to the activeRangeLeft row below.
        }
        if (activeRangeLeft != null) {
            for (DeviceRange r : ranges) {
                if (activeRangeLeft.equals(r.getLabel())) return r;
            }
        }
        return ranges.get(0);
    }

    /**
     * Writes a measured full scale into {@code ch}'s active-range field: LEFT
     * -> the {@link #activeRangeLeft} row's {@code fsLeft}; RIGHT -> the
     * {@link #activeRangeRight} row's {@code fsRight} on an INDEPENDENT endpoint
     * (a dangling right label -> the {@code activeRangeLeft} row), or the SAME active
     * row's {@code fsRight} on a LINKED one.  A MONO endpoint has one physical
     * channel, so the value lands on both fields whichever channel names it.
     * An empty table gets a {@value #DEFAULT_RANGE_LABEL} row, selected as
     * active.
     *
     * <p>One channel per write, with one exception: the other channel's field
     * still at zero - never calibrated - takes the same value.  A row with one
     * channel calibrated and the other at zero is no calibration at all
     * ({@link #calibration()} refuses it), so the first calibrate of a fresh
     * card would leave the device uncalibrated until the other channel was
     * measured too; the two channels of one card share its design, so the
     * measured one is the best estimate of the other until it is measured
     * itself.
     */
    public void storeFullScale(Channel ch, double fsRms) {
        if (channels == DeviceChannelMode.MONO) {
            DeviceRange row = seedableActiveRow(Channel.L);
            row.setFsLeft(fsRms);
            row.setFsRight(fsRms);
            row.setCalibrated(true);
            return;
        }
        writeChannel(ch, fsRms);
        Channel other = ch == Channel.L ? Channel.R : Channel.L;
        DeviceRange otherRow = activeRange(other);
        double otherFs = other == Channel.L ? otherRow.getFsLeft() : otherRow.getFsRight();
        if (otherFs <= 0.0) {
            writeChannel(other, fsRms);
        }
    }

    private void writeChannel(Channel ch, double fsRms) {
        DeviceRange row = seedableActiveRow(ch);
        if (ch == Channel.R) {
            row.setFsRight(fsRms);
        } else {
            row.setFsLeft(fsRms);
        }
        // A real calibration wrote this row - protect it from seed-merge refresh.
        row.setCalibrated(true);
    }

    /** {@code ch}'s active range row, seeding a {@value #DEFAULT_RANGE_LABEL}
     *  row (and selecting it as active) when the table is empty. */
    private DeviceRange seedableActiveRow(Channel ch) {
        DeviceRange row = activeRange(ch);
        if (row == null) {
            row = new DeviceRange();
            row.setLabel(DEFAULT_RANGE_LABEL);
            ranges.add(row);
        }
        if (activeRangeLeft == null) {
            activeRangeLeft = row.getLabel();
        }
        return row;
    }

    /** A deep copy of this endpoint - new range-row objects, so a copied profile
     *  (a dialog edit copy, a seeded profile) shares no mutable state with its
     *  source. */
    public DeviceEndpointConfig deepCopy() {
        DeviceEndpointConfig c = new DeviceEndpointConfig();
        c.channels              = channels;
        c.activeRangeLeft       = activeRangeLeft;
        c.activeRangeRight      = activeRangeRight;
        c.calibrationFromDevice = calibrationFromDevice;
        for (DeviceRange r : ranges) {
            c.ranges.add(r.deepCopy());
        }
        return c;
    }
}
