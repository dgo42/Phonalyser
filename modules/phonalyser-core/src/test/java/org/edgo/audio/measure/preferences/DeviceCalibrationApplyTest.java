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

import java.util.List;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.enums.DeviceChannelMode;
import org.edgo.audio.measure.sound.DeviceCalibration;
import org.edgo.audio.measure.sound.DeviceRef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Is this selection calibrated at all?" - the trigger of the Preferences
 * dialog's warning, and the one resolution it shares with the device managers:
 * a calibration belongs to, and is stored on, the machine the device is
 * connected to.
 *
 * <p>The case that has to be pinned is the COLLISION.  A device on a Phonalyser
 * server arrives carrying the full scales that server stores for it (net spec
 * 4.3's {@code cal}), and this machine's own card store may well hold a card
 * whose {@code match} list recognises the same name - a second QA403, or simply
 * the card left behind by the analyzer that used to be plugged in here.  Resolved
 * by name, that local card would silently mis-scale every measurement taken on a
 * bench across the room, and nothing on screen would say so.  The managers keep
 * the two apart by construction - a local manager reads this machine's cards,
 * the net manager reads what the device carries - and the warning has to agree
 * with them.
 */
class DeviceCalibrationApplyTest {

    private static final String DEVICE_NAME = "QA403";
    private static final String LOCAL_CARD = "Local QA403";
    private static final String RANGE_LABEL = "default";
    /** What THIS machine's card says the device's full scales are. */
    private static final double LOCAL_FS_LEFT = 1.0;
    private static final double LOCAL_FS_RIGHT = 1.1;
    /** What the bench that actually owns the device says they are - nowhere near
     *  the local card's, so a resolution that took the wrong one is unmistakable. */
    private static final double WIRE_FS_LEFT = 7.5;
    private static final double WIRE_FS_RIGHT = 9.5;

    /**
     * A range row that was never calibrated carries zeros, and a full scale is
     * the divisor every level is computed against - so the ONE resolution answers
     * "no calibration", the same {@code null} an unbound name gets.
     *
     * <p>It matters at the single point rather than at each caller because they
     * all read it: the managers, the {@code cal} a Phonalyser server publishes
     * per device (net spec 4.3), and the full scale a remote generator lane opens
     * at.  A zero reaching any of them is an infinite reading or a division by
     * zero, and nothing on screen would say which.
     */
    @Test
    void anUncalibratedRowIsNoCalibrationAtAll() {
        AudioDeviceProfile card = uncalibratedCard(0.0, 0.0);

        assertNull(card.getInput().calibration());
        assertNull(card.getOutput().calibration(),
                "and in either direction - the row is the same one");
    }

    @Test
    void oneUncalibratedChannelIsEnoughToRefuseThePair() {
        AudioDeviceProfile card = uncalibratedCard(LOCAL_FS_LEFT, 0.0);

        assertNull(card.getInput().calibration(),
                "half a calibration would scale one channel and leave the other "
                        + "reading infinity");
    }

    @Test
    void aDeviceCarryingItsOwnCalibrationIsCalibrated() {
        Preferences prefs = detached();

        assertFalse(prefs.isUncalibrated(
                onABench(true, new DeviceCalibration(WIRE_FS_LEFT, WIRE_FS_RIGHT)), true),
                "the bench that owns the device sent its full scales - there is "
                        + "nothing to warn about");
    }

    @Test
    void aDeviceWithNeitherWireCalibrationNorCardIsUncalibrated() {
        Preferences prefs = detached();

        assertTrue(prefs.isUncalibrated(local(true), true));
        assertTrue(prefs.isUncalibrated(DEVICE_NAME, true),
                "and the name-only form answers the same for a device this process "
                        + "holds no ref for");
    }

    @Test
    void aLocalCardWithValuesCoversADeviceOfTHISMachine() {
        Preferences prefs = detached();
        prefs.putAudioDeviceProfile(card(true));

        assertFalse(prefs.isUncalibrated(local(true), true));
        assertTrue(prefs.isUncalibrated(local(false), false),
                "the card was calibrated for the INPUT only - the output half of "
                        + "the same selection is still uncalibrated");
    }

    /**
     * A device on a BENCH is calibrated by that bench and by nothing else.  A
     * local card that merely recognises its name is the SOURCE for propagating a
     * calibration up to the server (net spec 4.3 {@code cards.put} /
     * {@code device.setCalibration}) - never a silent calibrator down here.
     */
    @Test
    void aLocalCardNeverCoversADeviceOnABench() {
        Preferences prefs = detached();
        prefs.putAudioDeviceProfile(card(true));

        assertTrue(prefs.isUncalibrated(onABench(true, null), true),
                "the bench has no calibration for it, so it IS uncalibrated - this "
                        + "machine's card of the same name is not an answer for a "
                        + "device across the room");
    }

    @Test
    void aCardWhoseRowWasNeverCalibratedIsUncalibrated() {
        Preferences prefs = detached();
        prefs.putAudioDeviceProfile(uncalibratedCard(0.0, 0.0));

        assertTrue(prefs.isUncalibrated(local(true), true),
                "a bound card with zeros in its row is an uncalibrated device "
                        + "however it got there");
    }

    /** The one exception: a device with builtin calibration, such as a QA40x -
     *  the analyzer's own values are not the operator's to supply, so warning
     *  them about it would be noise they cannot act on. */
    @Test
    void aDeviceProvidedCalibrationIsExemptEvenWithAnEmptyRow() {
        Preferences prefs = detached();
        AudioDeviceProfile analyzer = uncalibratedCard(0.0, 0.0);
        analyzer.getInput().setCalibrationFromDevice(true);
        prefs.putAudioDeviceProfile(analyzer);

        assertFalse(prefs.isUncalibrated(local(true), true));
        assertTrue(prefs.isUncalibrated(local(false), false),
                "the flag is per endpoint - the output half of the same card is "
                        + "not exempt");
    }

    /** A card bound to the device whose ONE row carries the given full scales -
     *  both endpoints, so either direction resolves to it. */
    private AudioDeviceProfile uncalibratedCard(double fsLeft, double fsRight) {
        AudioDeviceProfile profile = new AudioDeviceProfile();
        profile.setName(LOCAL_CARD);
        profile.getMatch().add(DEVICE_NAME);
        for (DeviceEndpointConfig endpoint
                : List.of(profile.getInput(), profile.getOutput())) {
            DeviceRange row = new DeviceRange();
            row.setLabel(RANGE_LABEL);
            row.setFsLeft(fsLeft);
            row.setFsRight(fsRight);
            endpoint.setChannels(DeviceChannelMode.LINKED);
            endpoint.getRanges().add(row);
            endpoint.setActiveRange(RANGE_LABEL);
        }
        return profile;
    }

    /** A detached, transient Preferences with the inherited profile list cleared,
     *  so nothing here touches the live singleton's on-disk store. */
    private Preferences detached() {
        Preferences prefs = Preferences.instance().copyForDialog();
        prefs.setTransientMode(true);
        for (AudioDeviceProfile inherited : prefs.getAudioDeviceProfiles()) {
            prefs.removeAudioDeviceProfile(inherited.getName());
        }
        prefs.setBackend(AudioBackendType.WASAPI);
        return prefs;
    }

    /** THIS machine's card for a device of that name - one LINKED active row. */
    private AudioDeviceProfile card(boolean input) {
        AudioDeviceProfile profile = new AudioDeviceProfile();
        profile.setName(LOCAL_CARD);
        profile.getMatch().add(DEVICE_NAME);
        DeviceRange row = new DeviceRange();
        row.setLabel(RANGE_LABEL);
        row.setFsLeft(LOCAL_FS_LEFT);
        row.setFsRight(LOCAL_FS_RIGHT);
        DeviceEndpointConfig endpoint = input ? profile.getInput() : profile.getOutput();
        endpoint.setChannels(DeviceChannelMode.LINKED);
        endpoint.getRanges().add(row);
        endpoint.setActiveRange(RANGE_LABEL);
        return profile;
    }

    /** A device of THIS machine, carrying no calibration of its own - the case the
     *  card store has always answered. */
    private DeviceRef local(boolean input) {
        return new StubRef(DEVICE_NAME, input, null, false);
    }

    /** The same device on a Phonalyser server, with whatever calibration that
     *  server holds for it (null = none). */
    private DeviceRef onABench(boolean input, DeviceCalibration cal) {
        return new StubRef(DEVICE_NAME, input, cal, true);
    }

    /** A device handle with nothing on it but what the warning reads: its name,
     *  its direction, the calibration it did or did not come with, and whether it
     *  is plugged into THIS machine or into a bench. */
    private record StubRef(String name, boolean isInput, DeviceCalibration calibration,
            boolean remote) implements DeviceRef {

        @Override
        public int index() {
            return 0;
        }

        /** A bench device is reached through the dual-level carrier, which is what
         *  {@code DeviceRef.remote()} reads; a local one names its own backend. */
        @Override
        public AudioBackendType carrier() {
            return remote ? AudioBackendType.NET : AudioBackendType.WASAPI;
        }

        @Override
        public String description() {
            return "stub";
        }

        @Override
        public String vendor() {
            return "stub";
        }

        /** What the device IS - a QA403 answers the same either way; only the
         *  CARRIER says where it is plugged in. */
        @Override
        public AudioBackendType backend() {
            return AudioBackendType.QA40X;
        }

        @Override
        public boolean isOutput() {
            return !isInput;
        }
    }
}
