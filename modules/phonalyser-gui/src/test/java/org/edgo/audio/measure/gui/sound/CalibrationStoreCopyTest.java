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
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.DeviceChannelMode;
import org.edgo.audio.measure.preferences.AudioDeviceProfile;
import org.edgo.audio.measure.preferences.BackendKey;
import org.edgo.audio.measure.preferences.DeviceEndpointConfig;
import org.edgo.audio.measure.preferences.DeviceRange;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.DeviceRef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The copy half of the calibration rule: a bench with no calibration for a device
 * may be offered THIS machine's values for it, but only values this machine
 * actually has.
 *
 * <p>What is pinned here is the refusal.  The offer is the operator's to accept
 * and the accepted write is spec 4.3's {@code device.setCalibration} (covered on
 * the server side); what must never happen is a copy that invents a calibration -
 * a bench told a full scale nobody measured would mis-scale every measurement any
 * client takes on it from then on, and the number would look exactly as
 * authoritative as a real one.
 */
class CalibrationStoreCopyTest {

    private static final String SERVER_ID = "bench-1";
    private static final String DEVICE_NAME = "QA403";
    private static final String LOCAL_CARD = "Local QA403";
    private static final String RANGE_LABEL = "default";
    private static final double EPS = 1e-9;
    /** What the reference holds before a refused write, and must still hold
     *  after it. */
    private static final double LEGACY_FS = 3.25;
    /** What the operator types into the Calibrate dialog. */
    private static final double TYPED_FS = 1.75;

    private final BackendKey bench = BackendKey.of(SERVER_ID, AudioBackendType.QA40X);

    @Test
    void anUnknownDeviceHasNothingToCopy() {
        Preferences prefs = detached();

        assertFalse(new CalibrationStore(prefs)
                        .copyCardCalibrationToBench(bench, deviceRef()),
                "no card of ours recognises the name - there is no calibration to "
                        + "offer, and the bench must not be sent one");
    }

    @Test
    void aCardWhoseRowWasNeverCalibratedHasNothingToCopyEither() {
        Preferences prefs = detached();
        prefs.putAudioDeviceProfile(card(0.0, 0.0));

        assertFalse(new CalibrationStore(prefs)
                        .copyCardCalibrationToBench(bench, deviceRef()),
                "a bound card with zeros in its row is an uncalibrated device, and "
                        + "a full scale of zero is a division by zero on the bench");
    }

    @Test
    void halfACalibrationIsNotOffered() {
        Preferences prefs = detached();
        prefs.putAudioDeviceProfile(card(1.0, 0.0));

        assertFalse(new CalibrationStore(prefs)
                        .copyCardCalibrationToBench(bench, deviceRef()),
                "one calibrated channel would scale one side of the bench and leave "
                        + "the other reading infinity");
    }

    // -- the calibrate buttons: apply on SUCCESS, never before ----------------

    /**
     * A write the bench does NOT take must leave this client's own reference
     * exactly where it was.
     *
     * <p>The scalars used to move first, so the operator could see the reading
     * change at once.  But a refusal - the analyzer owns its values, another
     * client holds the device, the wire is down - then left the typed number
     * standing as this client's dBV reference while the bench kept its own, with
     * nothing to put it back.  A calibration that did not happen must not look
     * like one that did.
     */
    @Test
    void aRefusedRemoteWriteLeavesTheClientsOwnReferenceAlone() {
        Preferences prefs = onABench();
        prefs.setAdcFsVoltageRms(LEGACY_FS);
        prefs.setAdcFsVoltageRmsRight(LEGACY_FS);

        // No device of that name is enumerated on the bench, so the write cannot
        // even be addressed - the plainest refusal there is.
        assertFalse(new CalibrationStore(prefs).storeAdcCalibration(TYPED_FS),
                "the caller has to know it did not land");

        assertEquals(LEGACY_FS, prefs.getAdcFsVoltageRms(Channel.L), EPS,
                "and the reference is the bench's, not what was typed here");
        assertEquals(LEGACY_FS, prefs.getAdcFsVoltageRms(Channel.R), EPS);
    }

    @Test
    void aRefusedRemoteDacWriteLeavesTheGeneratorsFullScaleAlone() {
        Preferences prefs = onABench();
        prefs.setDacFsVoltageAmpl(LEGACY_FS);
        prefs.setDacFsVoltageAmplRight(LEGACY_FS);

        assertFalse(new CalibrationStore(prefs).storeDacCalibration(Channel.R, TYPED_FS));

        assertEquals(LEGACY_FS, prefs.getDacFsVoltageAmpl(Channel.R), EPS,
                "a generator driven at a full scale the bench never accepted would "
                        + "emit a level nobody asked for");
        assertEquals(LEGACY_FS, prefs.getDacFsVoltageAmpl(Channel.L), EPS);
    }

    /** The LOCAL path is untouched by all of this: there is no round trip to
     *  succeed, the store write IS the success, and the scalar moves as it always
     *  did. */
    @Test
    void aLocalWriteStillMovesTheScalarAtOnce() {
        Preferences prefs = detached();
        prefs.setBackend(AudioBackendType.WASAPI);
        prefs.current().setInputDeviceName(DEVICE_NAME);

        assertTrue(new CalibrationStore(prefs).storeAdcCalibration(TYPED_FS));

        assertEquals(TYPED_FS, prefs.getAdcFsVoltageRms(Channel.L), EPS);
        assertNotNull(prefs.deviceCalibration(DEVICE_NAME, true),
                "and the local calibrate flow created the card it needed");
    }

    // -- is the CURRENT selection calibrated at all (the calibrate-button gate) --

    /**
     * The bench failure this pins: on the server backend the scope's ADC Calibrate
     * button stayed disabled even at an amplitude above 0.5 V.  It gates on the
     * reading occupying a quarter of full scale, which presumes a full scale worth
     * measuring against - so the pane asks this first and drops the accuracy rule
     * when the answer is that there is none.
     */
    @Test
    void aBenchDeviceTheBenchHasNoCalibrationForIsUncalibrated() {
        Preferences prefs = onABench();

        assertTrue(new CalibrationStore(prefs).isUncalibrated(true),
                "the bench does not offer the device at all, so nothing is left to "
                        + "vouch for what its volts mean");
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
        Preferences prefs = onABench();
        prefs.putAudioDeviceProfile(card(1.0, 1.0));

        assertTrue(new CalibrationStore(prefs).isUncalibrated(true),
                "a calibrated local card of the same name says nothing about the "
                        + "exemplar on the bench");
    }

    @Test
    void aLocalDeviceIsTheNameLookupItAlwaysWas() {
        Preferences prefs = detached();
        prefs.setBackend(AudioBackendType.WASAPI);
        prefs.current().setInputDeviceName(DEVICE_NAME);

        assertTrue(new CalibrationStore(prefs).isUncalibrated(true),
                "no card of ours recognises it");

        prefs.putAudioDeviceProfile(card(1.0, 1.0));
        assertFalse(new CalibrationStore(prefs).isUncalibrated(true),
                "and a calibrated card of ours is exactly what makes it calibrated");
    }

    @Test
    void anUncalibratedRowIsNoCalibrationForTheGateEither() {
        Preferences prefs = detached();
        prefs.setBackend(AudioBackendType.WASAPI);
        prefs.current().setInputDeviceName(DEVICE_NAME);
        prefs.putAudioDeviceProfile(card(0.0, 0.0));

        assertTrue(new CalibrationStore(prefs).isUncalibrated(true),
                "a bound card whose row was never calibrated is an uncalibrated "
                        + "device, and the gate must not measure against its zeros");
    }

    /** A detached store whose selection is a BENCH, with the device committed -
     *  everything {@code CalibrationStore} reads to decide where a write goes. */
    private Preferences onABench() {
        Preferences prefs = detached();
        prefs.setSelectedBackend(bench);
        prefs.current().setInputDeviceName(DEVICE_NAME);
        prefs.current().setOutputDeviceName(DEVICE_NAME);
        return prefs;
    }

    /** The bench's input handle for the device - the caller of this copy already
     *  holds one (it is the fallback of a card propagation), so the refusals below
     *  are decided by THIS machine's store alone. */
    private DeviceRef deviceRef() {
        return new StubRef();
    }

    /** A detached, transient Preferences with the inherited profile list cleared,
     *  so nothing here touches the developer's own device store. */
    private Preferences detached() {
        Preferences prefs = Preferences.instance().copyForDialog();
        prefs.setTransientMode(true);
        for (AudioDeviceProfile inherited : prefs.getAudioDeviceProfiles()) {
            prefs.removeAudioDeviceProfile(inherited.getName());
        }
        return prefs;
    }

    /** This machine's input card for the device, with one LINKED active row. */
    private AudioDeviceProfile card(double fsLeft, double fsRight) {
        AudioDeviceProfile profile = new AudioDeviceProfile();
        profile.setName(LOCAL_CARD);
        profile.getMatch().add(DEVICE_NAME);
        DeviceRange row = new DeviceRange();
        row.setLabel(RANGE_LABEL);
        row.setFsLeft(fsLeft);
        row.setFsRight(fsRight);
        DeviceEndpointConfig endpoint = profile.getInput();
        endpoint.setChannels(DeviceChannelMode.LINKED);
        endpoint.getRanges().add(row);
        endpoint.setActiveRange(RANGE_LABEL);
        return profile;
    }

    /** The bench's input device, as little of it as the copy reads. */
    private record StubRef() implements DeviceRef {

        @Override
        public int index() {
            return 0;
        }

        @Override
        public String name() {
            return DEVICE_NAME;
        }

        @Override
        public String description() {
            return "stub";
        }

        @Override
        public String vendor() {
            return "stub";
        }

        @Override
        public AudioBackendType backend() {
            return AudioBackendType.QA40X;
        }

        @Override
        public AudioBackendType carrier() {
            return AudioBackendType.NET;
        }

        @Override
        public boolean isInput() {
            return true;
        }

        @Override
        public boolean isOutput() {
            return false;
        }
    }
}
