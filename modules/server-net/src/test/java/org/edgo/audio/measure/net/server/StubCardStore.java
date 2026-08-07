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

package org.edgo.audio.measure.net.server;

import org.edgo.audio.measure.enums.DeviceChannelMode;
import org.edgo.audio.measure.preferences.AudioDeviceProfile;
import org.edgo.audio.measure.preferences.DeviceEndpointConfig;
import org.edgo.audio.measure.preferences.DeviceRange;
import org.edgo.audio.measure.preferences.Preferences;

import lombok.Getter;

/**
 * The server's device cards, with nothing in them: a detached, transient
 * {@link Preferences} whose inherited profile list is cleared, so a test decides
 * exactly which devices this bench is calibrated for and nothing is read from -
 * or written to - the developer's own {@code devices.yaml}.
 *
 * <p>Every server-net test that builds a {@link DeviceCatalog} needs one, because
 * the catalog publishes the {@code cal} of spec 4.3 from it.  Most want it empty,
 * which is the {@code cal:null} an uncalibrated bench reports; the calibration
 * tests seed a card with {@link #card}.
 */
final class StubCardStore {

    /** The label a single-row seeded card carries - one row, always active, which
     *  is what a freshly calibrated sound card looks like. */
    private static final String RANGE_LABEL = "default";
    /** How far a seeded row's RIGHT full scale sits from its left, so the two
     *  channels are never interchangeable in an assertion. */
    private static final double RIGHT_OFFSET = 0.5;

    @Getter
    private final Preferences prefs;

    StubCardStore() {
        Preferences store = Preferences.instance().copyForDialog();
        store.setTransientMode(true);
        for (AudioDeviceProfile inherited : store.getAudioDeviceProfiles()) {
            store.removeAudioDeviceProfile(inherited.getName());
        }
        this.prefs = store;
    }

    /**
     * Binds a LINKED card to {@code deviceName} in one direction with one active
     * range row carrying the two full-scales - the shape
     * {@code device.setCalibration} produces and {@code cal} reports back.
     *
     * @param fromDevice whether the endpoint's calibration is the DEVICE's own (a
     *                   QA40x reading its EEPROM), which spec 4.3 refuses to let
     *                   a client write
     */
    void card(String name, String deviceName, boolean input, double fsLeft,
            double fsRight, boolean fromDevice) {
        AudioDeviceProfile profile = new AudioDeviceProfile();
        profile.setName(name);
        profile.getMatch().add(deviceName);
        DeviceEndpointConfig endpoint = input ? profile.getInput() : profile.getOutput();
        endpoint.setChannels(DeviceChannelMode.LINKED);
        endpoint.setCalibrationFromDevice(fromDevice);
        endpoint.getRanges().add(row(RANGE_LABEL, fsLeft, fsRight));
        endpoint.setActiveRange(RANGE_LABEL);
        prefs.putAudioDeviceProfile(profile);
    }

    /**
     * A LINKED card with TWO range rows - the shape an ANALYZER's card has, one
     * calibrated row per attenuator position, and what a range change moves
     * between.  {@code activeLabel} is the row in force to begin with.
     *
     * <p>Each row's right full scale is its left plus {@link #RIGHT_OFFSET}, so a
     * payload that crossed the two channels fails on the values rather than
     * passing on symmetry.
     */
    void rangedCard(String name, String deviceName, boolean input, String activeLabel,
            double activeFsLeft, String otherLabel, double otherFsLeft) {
        AudioDeviceProfile profile = new AudioDeviceProfile();
        profile.setName(name);
        profile.getMatch().add(deviceName);
        DeviceEndpointConfig endpoint = input ? profile.getInput() : profile.getOutput();
        endpoint.setChannels(DeviceChannelMode.LINKED);
        endpoint.getRanges().add(row(activeLabel, activeFsLeft, activeFsLeft + RIGHT_OFFSET));
        endpoint.getRanges().add(row(otherLabel, otherFsLeft, otherFsLeft + RIGHT_OFFSET));
        endpoint.setActiveRange(activeLabel);
        prefs.putAudioDeviceProfile(profile);
    }

    private DeviceRange row(String label, double fsLeft, double fsRight) {
        DeviceRange row = new DeviceRange();
        row.setLabel(label);
        row.setFsLeft(fsLeft);
        row.setFsRight(fsRight);
        return row;
    }
}
