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

package org.edgo.audio.measure.gui.sound;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.enums.DeviceChannelMode;
import org.edgo.audio.measure.gui.generator.BenchGeneratorStub;
import org.edgo.audio.measure.preferences.AudioDeviceProfile;
import org.edgo.audio.measure.preferences.BackendKey;
import org.edgo.audio.measure.preferences.DeviceEndpointConfig;
import org.edgo.audio.measure.preferences.DeviceRange;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether the CURRENT selection is calibrated at all - the gate the scope's
 * calibrate button drops its accuracy rule on, and the warning the Preferences
 * dialog raises on a committed selection - answered by {@link AudioBackend}
 * through the selected device's manager.
 *
 * <p>The bench failure this pins: on the server backend the scope's ADC
 * Calibrate button stayed disabled even at an amplitude above 0.5 V.  It gates
 * on the reading occupying a quarter of full scale, which presumes a full scale
 * worth measuring against - so the pane asks this first and drops the accuracy
 * rule when the answer is that there is none.
 *
 * <p>The bench is the stub of the generator tests ({@link BenchGeneratorStub},
 * this module's net carrier), whose devices carry whatever calibration they
 * carry and nothing of this machine's.  Runs against the live singleton in
 * transient mode; every touched preference is restored afterwards.
 */
class CalibrationGateTest {

    private static final String SERVER_ID = "b7e0-bench-gate";
    private static final String LOCAL_CARD = "Local twin";
    private static final String RANGE_LABEL = "default";

    private BackendKey previousActive;
    private BackendKey previousSelection;
    private String previousInputDevice;

    @BeforeEach
    void measureOnTheBench() {
        Preferences prefs = Preferences.instance();
        prefs.setTransientMode(true);
        previousSelection = prefs.getSelectedBackend();
        AudioBackend audio = AudioBackend.instance();
        previousActive = audio.activeKey();
        BackendKey bench = BackendKey.of(SERVER_ID, AudioBackendType.QA40X);
        audio.setActive(bench);
        prefs.setSelectedBackend(bench);
        previousInputDevice = prefs.current().getInputDeviceName();
        prefs.current().setInputDeviceName(BenchGeneratorStub.INPUT_NAME);
    }

    @AfterEach
    void leaveTheBench() {
        Preferences prefs = Preferences.instance();
        prefs.current().setInputDeviceName(previousInputDevice);
        prefs.removeAudioDeviceProfile(LOCAL_CARD);
        AudioBackend.instance().setActive(previousActive);
        if (previousSelection != null) {
            prefs.setSelectedBackend(previousSelection);
        }
    }

    @Test
    void aBenchDeviceTheBenchHasNoCalibrationForIsUncalibrated() {
        assertTrue(AudioBackend.instance().isUncalibrated(true),
                "the bench sent no calibration with the device, so nothing is left "
                        + "to vouch for what its volts mean");
        assertFalse(AudioBackend.instance().isCalibrationFromDevice(true));
    }

    /**
     * The exemplar rule, at the gate: a bench may have the same model at both
     * ends, and this machine's card for it describes a DIFFERENT box with its
     * own attenuators.  It may be propagated up on request - it may never make a
     * bench selection look calibrated, or the gate would go on refusing the
     * calibration using numbers that belong to another device.
     */
    @Test
    void aLocalCardOfTheSameNameNeverMakesABenchSelectionCalibrated() {
        Preferences.instance().putAudioDeviceProfile(localCard(BenchGeneratorStub.INPUT_NAME));

        assertTrue(AudioBackend.instance().isUncalibrated(true),
                "a calibrated local card of the same name says nothing about the "
                        + "exemplar on the bench");
    }

    /** This machine's calibrated input card recognising the bench device's name. */
    private AudioDeviceProfile localCard(String deviceName) {
        AudioDeviceProfile profile = new AudioDeviceProfile();
        profile.setName(LOCAL_CARD);
        profile.getMatch().add(deviceName);
        DeviceRange row = new DeviceRange();
        row.setLabel(RANGE_LABEL);
        row.setFsLeft(1.0);
        row.setFsRight(1.0);
        DeviceEndpointConfig endpoint = profile.getInput();
        endpoint.setChannels(DeviceChannelMode.LINKED);
        endpoint.getRanges().add(row);
        endpoint.setActiveRange(RANGE_LABEL);
        return profile;
    }
}
